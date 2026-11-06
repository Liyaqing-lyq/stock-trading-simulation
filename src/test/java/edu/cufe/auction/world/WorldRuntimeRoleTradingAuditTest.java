package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.InformationModel.Audience;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.RoleAccess.Role;
import edu.cufe.auction.world.RoleTradingModel.Status;
import edu.cufe.auction.world.WorldRoleSession.ChildDraft;
import edu.cufe.auction.world.WorldRoleSession.Citation;
import edu.cufe.auction.world.WorldRoleSession.ResearchDraft;
import edu.cufe.auction.world.WorldRoleSession.ReviewDraft;
import edu.cufe.auction.world.WorldRoleSession.TargetDraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独审缺口回归：提前撤任存量子单、离任会话裁剪、更新后到期任务、恢复交叉验证、内部标识碰撞。
 *
 * <p>用合法日志帧分别验证业务约束、恢复拒绝和正常交易的兼容行为。</p>
 */
@DisplayName("WorldRuntime：四岗交易独审缺口")
class WorldRuntimeRoleTradingAuditTest {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-role-trading-audit";
    private static final String SYM = "CUFE1";
    private static final long DAY = 86_400_000L;
    private static final long END = 30 * DAY;
    private static final long EARLY_REVOKE = DAY + 3_600_000L;

    private static final String ORG_F = "ORG-F";
    private static final String ORG_G = "ORG-G";
    private static final String PRODUCT = "fund-a";
    private static final String PRODUCT_G = "fund-b";
    private static final String ACC_FUND = "ACC-FUND";
    private static final String ACC_FUND_G = "ACC-FUND2";
    private static final String ACC_INV = "INV";
    private static final String INV = "investor";

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";

    private static final String TGT = "tgt-1";
    private static final String SRC_DOC = "src-1";
    private static final String NOTE_DOC = "note-1";
    private static final String RESEARCH = "res-1";

    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper
            .builder()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .nodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true))
            .build();

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // A. 提前撤任：存量子单必须同批撤销
    // ------------------------------------------------------------------

    @Test
    @DisplayName("提前撤任在当批撤销该任职的活动子单，保留成交，新单被拒")
    void revocationCancelsExistingChildrenInSameBatch() throws IOException {
        try (WorldRuntime rt = open("revoke.journal")) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);

            child(rt, DAVE, "c1", 40L, "10.00", 200_000L);
            rt.advanceTo(200_000L);
            assertEquals(WorldExchange.OrderStatus.ACTIVE, orderOf(rt, "c1").status());
            assertEquals(400_00L, cents(frozen(rt)));

            rt.scheduleRevokeAppointment("rv1", "rt1", EARLY_REVOKE, "A-4");
            rt.advanceTo(EARLY_REVOKE);

            assertEquals(WorldExchange.OrderStatus.CANCELLED, orderOf(rt, "c1").status(),
                    "撤任当批必须撤销该任职的存量子单");
            assertEquals(0L, cents(frozen(rt)), "冻结在同一批释放");
            assertEquals(0L, orderOf(rt, "c1").filled());

            child(rt, DAVE, "c2", 10L, "10.00", EARLY_REVOKE + 1);
            rt.advanceTo(EARLY_REVOKE + 1);
            assertEquals(RoleTradingModel.REASON_NOT_AUTHORIZED, notice(rt, DAVE, t("c2")).reason());
        }
    }

    @Test
    @DisplayName("研究员离任不撤基金订单，另一组织任职不受波及")
    void researchDepartureLeavesFundOrdersAlone() throws IOException {
        try (WorldRuntime rt = open("revoke-research.journal")) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
            child(rt, DAVE, "c1", 40L, "10.00", 200_000L);
            rt.advanceTo(200_000L);

            rt.scheduleRevokeAppointment("rv1", "rt1", EARLY_REVOKE, "A-1");
            rt.advanceTo(EARLY_REVOKE);
            assertEquals(WorldExchange.OrderStatus.ACTIVE, orderOf(rt, "c1").status(),
                    "研究员的任职撤销不撤基金产品的单");
        }
    }

    // ------------------------------------------------------------------
    // B. 离任会话裁剪
    // ------------------------------------------------------------------

    @Test
    @DisplayName("离任旧会话看不到离任后新研究、没有旧岗位工具，也不借新任职读新产品")
    void departedSessionDoesNotInheritNewAppointmentView() throws IOException {
        try (WorldRuntime rt = open("departed.journal")) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
            WorldRoleSession oldBob = rt.openRoleSession(BOB);
            assertTrue(oldBob.view().tools().contains(RoleTradingView.TOOL_TARGET_SET));

            rt.scheduleRevokeAppointment("rv1", "rt1", EARLY_REVOKE, "A-2");
            rt.advanceTo(EARLY_REVOKE);
            assertEquals(0, oldBob.view().appointments().size(), "观察任职已失效");
            assertFalse(oldBob.view().tools().contains(RoleTradingView.TOOL_TARGET_SET),
                    "离任后不再列岗位工具");

            // 离任后研究员重新发表：旧会话不得看见
            rt.schedulePublishDocument("c-src2", "t-src2", EARLY_REVOKE + 1_000L, SRC_DOC, ALICE,
                    Audience.organization(ORG_F), "离职后的新线索", "离任后的组织材料。", "调研纪要",
                    60_000L, List.of("internal-2"));
            rt.advanceTo(EARLY_REVOKE + 1_000L);
            session(rt, ALICE).startReading("c-r3", "t-r3", EARLY_REVOKE + 2_000L, SRC_DOC, 1L);
            rt.advanceTo(EARLY_REVOKE + 2_000L);
            rt.advanceTo(EARLY_REVOKE + 62_000L);
            session(rt, ALICE).submitResearch("c-res2", "t-res2", EARLY_REVOKE + 63_000L,
                    new ResearchDraft("res-2", ORG_F, PRODUCT, SYM, "note-2", "离职后新线索",
                            "离任之后才发表的组织研究。", List.of(new Citation(SRC_DOC, 1L))));
            rt.advanceTo(EARLY_REVOKE + 63_000L);

            assertEquals(0, oldBob.view().research().size(),
                    "离任后旧会话不吸收任何组织研究（观察任职与在任取交集）");

            // 同人改任另一组织：旧会话不得借新任职读取新产品
            rt.scheduleAppointment("ap2", "at2", EARLY_REVOKE + 64_000L, "A-2G", BOB, ORG_G,
                    Role.PORTFOLIO_MANAGER, EARLY_REVOKE + 64_000L, END);
            rt.advanceTo(EARLY_REVOKE + 64_000L);
            WorldRoleSession fresh = rt.openRoleSession(BOB);
            assertEquals(1, fresh.view().appointments().size());
            assertEquals(ORG_G, fresh.view().appointments().get(0).organizationPartyId());
            assertTrue(fresh.view().profile().accounts().stream()
                    .anyMatch(account -> account.accountId().equals(ACC_FUND_G)),
                    "新会话按新任职可见新产品账户");
            assertTrue(oldBob.view().profile().accounts().stream()
                    .noneMatch(account -> account.accountId().equals(ACC_FUND_G)));
        }
    }

    // ------------------------------------------------------------------
    // C. 更新后的目标截止
    // ------------------------------------------------------------------

    @Test
    @DisplayName("更新后新版本自带截止任务；较短审核期限不会挡掉目标截止")
    void updatedTargetSchedulesItsOwnDeadline() throws IOException {
        try (WorldRuntime rt = open("expiry.journal")) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
            child(rt, DAVE, "c1", 40L, "10.00", 200_000L);
            rt.advanceTo(200_000L);

            long updateAt = 300_000L;
            session(rt, BOB).setTarget("cu1", "ut1", updateAt,
                    new TargetDraft(TGT, 1, ORG_F, PRODUCT, ACC_FUND, SYM, 60L, price("10.00"),
                            DAY + 5_000_000L, RESEARCH));
            rt.advanceTo(updateAt);
            rt.advanceTo(updateAt + 1);
            assertEquals(2, target(rt, TGT).current().version(), "撤净后建立新版本");
            assertEquals(Status.PENDING_REVIEW, target(rt, TGT).status());

            // 较短审核有效期不应吞掉目标截止
            session(rt, CAROL).reviewTarget("cr2", "rt2", updateAt + 2_000L,
                    new ReviewDraft(TGT, 2, true, 20L, 20L, price("10.00"), updateAt + 100_000L,
                            "短期限"));
            rt.advanceTo(updateAt + 2_000L);
            session(rt, BOB).confirmTarget("cc2", "ct2", updateAt + 3_000L, TGT, 2, 1);
            rt.advanceTo(updateAt + 3_000L);

            rt.advanceTo(updateAt + 100_001L);
            assertEquals(Status.ACTIVE, target(rt, TGT).status(),
                    "审核到期不销毁母任务，只停止新单");
            rt.advanceTo(DAY + 5_000_000L);
            assertEquals(Status.EXPIRED, target(rt, TGT).status(),
                    "目标截止必须由自己版本的定时任务触发");
        }
    }

    // ------------------------------------------------------------------
    // D. 恢复交叉验证
    // ------------------------------------------------------------------

    @Test
    @DisplayName("合法校验和的历史伪造在恢复期被拒：缺截止、改基点、换人物、伪造证据")
    void forgedHistoricalFramesAreRejectedOnRecovery() throws IOException {
        Path journal = tempDir.resolve("forged.journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
            child(rt, DAVE, "c1", 40L, "10.00", 200_000L);
            rt.advanceTo(200_000L);
        }

        // 对照：同样的重签流程但不改动内容，必须能正常恢复
        Path control = resign("control.journal", journal, false, snapshot -> { });
        try (WorldRuntime rt = open(control)) {
            assertEquals(1, rt.snapshot().trading().orElseThrow().trading().children().size(),
                    "重签本身不得改变语义");
        }

        Path missingDeadline = resign("missing-deadline.journal", journal, true, snapshot -> {
            ArrayNode pending = (ArrayNode) snapshot.path("pending");
            for (int index = pending.size() - 1; index >= 0; index--) {
                if (PendingTask.ROLE_TRADING_EXPIRE_V1.equals(pending.get(index).path("kind").asText())) {
                    pending.remove(index);
                }
            }
        });
        assertThrows(IOException.class, () -> open(missingDeadline), "缺目标截止任务必须拒绝");

        Path oldBase = resign("old-base.journal", journal, true, snapshot -> {
            for (JsonNode target : snapshot.path("trading").path("trading").path("targets")) {
                ObjectNode first = (ObjectNode) target.path("versions").get(0);
                first.put("absoluteTargetQuantity", 120);
                first.put("baseQuantity", 20);
            }
        });
        assertThrows(IOException.class, () -> open(oldBase), "基点与创建时持仓不一致必须拒绝");

        Path replaceActor = resign("replace-actor.journal", journal, true, snapshot -> {
            for (JsonNode link : snapshot.path("trading").path("trading").path("children")) {
                ((ObjectNode) link).put("personPartyId", CAROL);
            }
        });
        assertThrows(IOException.class, () -> open(replaceActor), "子单链接换了人物必须拒绝");

        Path researchEvidence = resign("research-evidence.journal", journal, true, snapshot -> {
            for (JsonNode note : snapshot.path("trading").path("trading").path("research")) {
                ((ObjectNode) note.path("citations").get(0)).put("version", 9);
            }
        });
        assertThrows(IOException.class, () -> open(researchEvidence), "引用未读版本必须拒绝");
    }

    // ------------------------------------------------------------------
    // E. 内部标识不得被外部任务占用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("外部任务先占用 roleT 自动标识时，目标截止与撤单仍会自动排上")
    void externalTaskCannotStarveInternalAutoTask() throws IOException {
        try (WorldRuntime rt = open("collision.journal")) {
            // 用外部世界下单任务占住内部自动任务的候选标识
            rt.scheduleOrder("oc", "roleT:expire:" + TGT + ":1:0", 1_000L, INV, ACC_INV, SYM,
                    Side.SELL, OrderType.LIMIT, price("10.00"), 1L);
            rt.advanceTo(1_000L);

            establishTarget(rt, 2_000L, 2 * DAY, 2 * DAY - 1);
            child(rt, DAVE, "c1", 40L, "10.00", 200_000L);
            rt.advanceTo(200_000L);
            assertEquals(WorldExchange.OrderStatus.ACTIVE, orderOf(rt, "c1").status());

            rt.advanceTo(2 * DAY);
            assertEquals(Status.EXPIRED, target(rt, TGT).status(),
                    "截止任务不能被外部标识占用而丢失");
            assertEquals(WorldExchange.OrderStatus.CANCELLED, orderOf(rt, "c1").status());
            assertEquals(0L, cents(frozen(rt)));
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    @Test
    @DisplayName("普通成交先于目标或穿插更新时，基点取当时实际持仓并可恢复")
    void ordinaryTradesRemainValidBeforeAndBetweenTargets() throws IOException {
        Path file = tempDir.resolve("ordinary.journal");
        try (WorldRuntime rt = open(file)) {
            rt.scheduleOrder("sell-first", "sell-first-t", 1L, INV, ACC_INV, SYM,
                    Side.SELL, OrderType.LIMIT, price("8.00"), 10L);
            rt.scheduleOrder("buy-first", "buy-first-t", 2L, PRODUCT, ACC_FUND, SYM,
                    Side.BUY, OrderType.LIMIT, price("9.00"), 10L);
            rt.advanceTo(2L);
            establishResearch(rt, 10L);
            session(rt, BOB).setTarget("first-target", "first-target-t", 124_010L,
                    new TargetDraft(TGT, 0, ORG_F, PRODUCT, ACC_FUND, SYM, 100L,
                            price("10.00"), 2 * DAY, RESEARCH));
            rt.advanceTo(124_010L);
            assertEquals(10L, target(rt, TGT).current().baseQuantity());
            rt.scheduleOrder("sell-next", "sell-next-t", 125_010L, INV, ACC_INV, SYM,
                    Side.SELL, OrderType.LIMIT, price("9.00"), 20L);
            rt.scheduleOrder("buy-next", "buy-next-t", 126_010L, PRODUCT, ACC_FUND, SYM,
                    Side.BUY, OrderType.LIMIT, price("9.00"), 20L);
            rt.advanceTo(126_010L);
            session(rt, BOB).setTarget("update-target", "update-target-t", 127_010L,
                    new TargetDraft(TGT, 1, ORG_F, PRODUCT, ACC_FUND, SYM, 50L,
                            price("10.00"), 2 * DAY, RESEARCH));
            rt.advanceTo(127_011L);
            assertEquals(30L, target(rt, TGT).current().baseQuantity());
            assertEquals(20L, target(rt, TGT).current().changeQuantity());
            assertEquals(price("740.00"), rt.snapshot().accounts().get(ACC_FUND).cash());
        }
        try (WorldRuntime rt = open(file)) {
            assertEquals(30L, target(rt, TGT).current().baseQuantity());
        }
    }

    @Test
    @DisplayName("无任职人物仍可实际阅读公开与本人直投资料")
    void departedPersonCanReadPublicAndDirectDocuments() throws IOException {
        try (WorldRuntime rt = open("public-reading.journal")) {
            rt.scheduleRevokeAppointment("revoke-pm", "revoke-pm-t", 1L, "A-2");
            rt.advanceTo(1L);
            WorldRoleSession person = session(rt, BOB);
            rt.schedulePublishDocument("pub", "pub-t", 2L, "public-doc", ALICE,
                    Audience.publicAudience(), "公开", "公开正文", "公开来源", 1L, List.of());
            rt.schedulePublishDocument("direct", "direct-t", 3L, "direct-doc", ALICE,
                    Audience.direct(BOB), "直投", "本人正文", "本人来源", 1L, List.of());
            rt.advanceTo(3L);
            person.startReading("read-public", "read-public-t", 4L, "public-doc", 1L);
            rt.advanceTo(5L);
            assertTrue(person.canCite("public-doc", 1L));
            person.startReading("read-direct", "read-direct-t", 6L, "direct-doc", 1L);
            rt.advanceTo(7L);
            assertTrue(person.canCite("direct-doc", 1L));
        }
    }

    @Test
    @DisplayName("组织阅读的完成复核原观察任职，同人重新任职须重新阅读")
    void readingCompletionCannotBorrowReplacementAppointment() throws IOException {
        try (WorldRuntime rt = open("reading-rehire.journal")) {
            rt.schedulePublishDocument("pub", "pub-t", 1L, "org-doc", ALICE,
                    Audience.organization(ORG_F), "组织", "组织正文", "组织来源", 1000L, List.of());
            rt.advanceTo(1L);
            WorldRoleSession original = session(rt, BOB);
            original.startReading("old-read", "old-read-t", 2L, "org-doc", 1L);
            rt.scheduleRevokeAppointment("revoke-pm", "revoke-pm-t", 3L, "A-2");
            rt.scheduleAppointment("rehire-pm", "rehire-pm-t", 4L, "A-2-new", BOB, ORG_F,
                    Role.PORTFOLIO_MANAGER, 4L, END);
            rt.advanceTo(1002L);
            assertFalse(original.canCite("org-doc", 1L));
            WorldRoleSession fresh = session(rt, BOB);
            fresh.startReading("fresh-read", "fresh-read-t", 1003L, "org-doc", 1L);
            rt.advanceTo(2003L);
            assertTrue(fresh.canCite("org-doc", 1L));
        }
    }

    @Test
    @DisplayName("全部后续帧一致伪造回报人物仍被原命令归属校验拒绝")
    void forgedReportPersonIsRejectedAgainstOriginalRequest() throws IOException {
        Path journal = tempDir.resolve("report-owner.journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
        }
        Path forged = resign("forged-report.journal", journal, true, snapshot -> {
            for (JsonNode report : snapshot.path("trading").path("reports")) {
                if ("c-r2".equals(report.path("clientCommandId").asText())) {
                    ((ObjectNode) report).put("personPartyId", CAROL);
                }
            }
        });
        assertThrows(IOException.class, () -> open(forged));
    }

    @Test
    @DisplayName("全部后续帧一致改写真实子单数量仍被原请求校验拒绝")
    void forgedOrderQuantityIsRejectedAgainstOriginalRequest() throws IOException {
        Path journal = tempDir.resolve("order-quantity.journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
            child(rt, DAVE, "c1", 40L, "10.00", 200_000L);
            rt.advanceTo(200_000L);
        }
        Path forged = resign("forged-quantity.journal", journal, true, snapshot -> {
            for (JsonNode order : snapshot.path("exchange").path("orders")) {
                ((ObjectNode) order).put("quantity", 41L);
            }
        });
        assertThrows(IOException.class, () -> open(forged));
    }

    @Test
    @DisplayName("目标基点未变时，伪造绝对数量仍必须符合经理的原始请求")
    void forgedTargetQuantityIsRejectedAgainstOriginalRequest() throws IOException {
        Path journal = tempDir.resolve("target-quantity.journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
        }
        Path forged = resign("forged-target-quantity.journal", journal, true, snapshot -> {
            for (JsonNode target : snapshot.path("trading").path("trading").path("targets")) {
                ((ObjectNode) target.path("versions").get(0)).put("absoluteTargetQuantity", 900L);
            }
        });
        assertThrows(IOException.class, () -> open(forged));
    }

    @Test
    @DisplayName("合法范围内伪造风险单笔额度也必须符合风控原始请求")
    void forgedReviewBoundsAreRejectedAgainstOriginalRequest() throws IOException {
        Path journal = tempDir.resolve("review-bound.journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
        }
        Path forged = resign("forged-review-bound.journal", journal, true, snapshot -> {
            for (JsonNode review : snapshot.path("trading").path("trading").path("reviews")) {
                ((ObjectNode) review).put("maxChildQuantity", 100L);
            }
        });
        assertThrows(IOException.class, () -> open(forged));
    }

    @Test
    @DisplayName("同版本连续暂停各有对应期限，延长暂停后按新期限恢复待审")
    void repeatedHaltUsesTheLatestEffectiveEnd() throws IOException {
        try (WorldRuntime rt = open("repeated-halt.journal")) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
            session(rt, CAROL).haltTarget("halt1", "halt1-t", 200_000L,
                    new WorldRoleSession.HaltDraft("halt1", TGT, 1, 400_000L, "首次暂停"));
            rt.advanceTo(200_000L);
            session(rt, CAROL).haltTarget("halt2", "halt2-t", 300_000L,
                    new WorldRoleSession.HaltDraft("halt2", TGT, 1, 500_000L, "延长暂停"));
            rt.advanceTo(400_000L);
            assertEquals(Status.HALTED, target(rt, TGT).status());
            rt.advanceTo(500_000L);
            assertEquals(Status.PENDING_REVIEW, target(rt, TGT).status());
        }
    }

    @Test
    @DisplayName("Long最大值是合法未来截止，目标仍排程并可恢复")
    void maximumLongDeadlineRemainsSchedulable() throws IOException {
        Path file = tempDir.resolve("max-deadline.journal");
        try (WorldRuntime rt = open(file)) {
            establishTarget(rt, 0L, Long.MAX_VALUE, Long.MAX_VALUE - 1);
            assertEquals(Long.MAX_VALUE, target(rt, TGT).current().deadlineMillis());
        }
        try (WorldRuntime rt = open(file)) {
            assertEquals(Long.MAX_VALUE, target(rt, TGT).current().deadlineMillis());
        }
    }

    @Test
    @DisplayName("Long最大任期保留合法子单及任期结束待办")
    void maximumLongAppointmentEndRemainsSchedulable() throws IOException {
        Path file = tempDir.resolve("max-appointment.journal");
        try (WorldRuntime rt = open(file)) {
            rt.scheduleRevokeAppointment("revoke-trader", "revoke-trader-t", 1L, "A-4");
            rt.advanceTo(1L);
            rt.scheduleAppointment("open-trader", "open-trader-t", 2L, "A-4-open", DAVE,
                    ORG_F, Role.EXECUTION_TRADER, 2L, Long.MAX_VALUE);
            rt.advanceTo(2L);
            establishTarget(rt, 10L, 2 * DAY, 2 * DAY - 1);
            child(rt, DAVE, "c1", 40L, "10.00", 200_000L);
            rt.advanceTo(200_000L);
            assertTrue(orderOf(rt, "c1").live());
            assertTrue(rt.snapshot().pending().stream().anyMatch(task ->
                    task.kind().equals(PendingTask.ROLE_TRADING_APPOINTMENT_END_V1)
                            && task.dueTimeMillis() == Long.MAX_VALUE));
        }
        try (WorldRuntime rt = open(file)) {
            assertTrue(orderOf(rt, "c1").live());
        }
    }

    @Test
    @DisplayName("非法价格在排程前拒绝，完整世界状态与运行许可保持有效")
    void invalidPricesAreRejectedBeforeScheduling() throws IOException {
        try (WorldRuntime rt = open("bad-price.journal")) {
            WorldRuntime.Snapshot before = rt.snapshot();
            for (String price : List.of("0.00", "-1.00", "1.001")) {
                assertThrows(IllegalArgumentException.class, () -> session(rt, DAVE).submitChild(
                        "bad-price", "bad-price-t", 1L,
                        new ChildDraft(TGT, 1, 1, "bad-child", 1L, new BigDecimal(price))));
            }
            assertEquals(before, rt.snapshot());
            assertFalse(rt.isPaused());
        }
    }

    @Test
    @DisplayName("研究必填正文和确认版本在构造业务意图时校验")
    void invalidBusinessShapeIsRejectedBeforeScheduling() {
        assertThrows(IllegalArgumentException.class, () -> new ResearchDraft(RESEARCH, ORG_F,
                PRODUCT, SYM, NOTE_DOC, "报告", " ", List.of(new Citation(SRC_DOC, 1L))));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldRoleSession.ConfirmDraft(TGT, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldRoleSession.CancelTargetDraft(" ", 1));
    }

    @Test
    @DisplayName("他人直投资料和未知资料返回相同授权拒绝")
    void hiddenDirectDocumentAndUnknownDocumentHaveSameReason() throws IOException {
        try (WorldRuntime rt = open("reading-privacy.journal")) {
            rt.schedulePublishDocument("private", "private-t", 1L, "hidden-doc", ALICE,
                    Audience.direct(ALICE), "私人标题", "私人正文", "私人来源", 1L, List.of());
            rt.advanceTo(1L);
            WorldRoleSession bob = session(rt, BOB);
            bob.startReading("hidden-read", "hidden-read-t", 2L, "hidden-doc", 1L);
            bob.startReading("unknown-read", "unknown-read-t", 3L, "missing-doc", 1L);
            rt.advanceTo(3L);
            assertEquals(notice(rt, BOB, "unknown-read-t").reason(),
                    notice(rt, BOB, "hidden-read-t").reason());
            assertEquals("NOT_AUTHORIZED", notice(rt, BOB, "hidden-read-t").reason());
            assertFalse(bob.canCite("hidden-doc", 1L));
        }
    }

    @Test
    @DisplayName("额外孤儿系统待办在恢复期拒绝")
    void orphanRoleTimerIsRejectedOnRecovery() throws IOException {
        Path journal = tempDir.resolve("orphan-timer.journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
        }
        Path forged = resign("forged-orphan-timer.journal", journal, true, snapshot -> {
            if (snapshot.path("trading").path("trading").path("targets").isEmpty()) {
                return;
            }
            ObjectNode timer = JSON.createObjectNode();
            timer.put("taskId", "orphan-timer");
            timer.put("dueTimeMillis", 900_000L);
            timer.put("phase", "RULES");
            timer.put("kind", PendingTask.ROLE_TRADING_HALT_RELEASE_V1);
            timer.put("payload", "{\"targetId\":\"ghost\",\"version\":1}");
            ((ArrayNode) snapshot.path("pending")).add(timer);
            ((ArrayNode) snapshot.path("usedTaskIds")).add("orphan-timer");
        });
        assertThrows(IOException.class, () -> open(forged));
    }

    @Test
    @DisplayName("到期待办的时点须与目标及审核原期限相同")
    void alteredTimerDueIsRejectedOnRecovery() throws IOException {
        Path journal = tempDir.resolve("timer-due.journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
        }
        Path forged = resign("forged-timer-due.journal", journal, true, snapshot -> {
            for (JsonNode timer : snapshot.path("pending")) {
                if (PendingTask.ROLE_TRADING_EXPIRE_V1.equals(timer.path("kind").asText())) {
                    ((ObjectNode) timer).put("dueTimeMillis", timer.path("dueTimeMillis").asLong() - 1000L);
                }
            }
        });
        assertThrows(IOException.class, () -> open(forged));
    }

    @Test
    @DisplayName("目标截止仍在时，丢失较早审核到期待办也须拒绝恢复")
    void missingReviewExpiryIsRejectedOnRecovery() throws IOException {
        Path journal = tempDir.resolve("review-timer.journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
        }
        Path forged = resign("forged-review-timer.journal", journal, true, snapshot -> {
            ArrayNode timers = (ArrayNode) snapshot.path("pending");
            for (int index = timers.size() - 1; index >= 0; index--) {
                if (PendingTask.ROLE_TRADING_EXPIRE_V1.equals(timers.get(index).path("kind").asText())
                        && timers.get(index).path("dueTimeMillis").asLong() == 2 * DAY - 1) {
                    timers.remove(index);
                }
            }
        });
        assertThrows(IOException.class, () -> open(forged));
    }

    @ParameterizedTest(name = "停止动作 {0} 的撤单待办必须存在")
    @ValueSource(strings = {"update", "halt", "cancel", "review"})
    void missingStopSweepIsRejectedOnRecovery(String mode) throws IOException {
        Path journal = tempDir.resolve("stop-" + mode + ".journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
            child(rt, DAVE, "c1", 40L, "10.00", 150_000L);
            rt.advanceTo(150_000L);
            switch (mode) {
                case "update" -> session(rt, BOB).setTarget("stop", "stop-t", 200_000L,
                        new TargetDraft(TGT, 1, ORG_F, PRODUCT, ACC_FUND, SYM, 120L,
                                price("10.00"), 2 * DAY, RESEARCH));
                case "halt" -> session(rt, CAROL).haltTarget("stop", "stop-t", 200_000L,
                        new WorldRoleSession.HaltDraft("halt-stop", TGT, 1, 300_000L, "暂停"));
                case "cancel" -> session(rt, BOB).cancelTarget("stop", "stop-t", 200_000L, TGT, 1);
                case "review" -> session(rt, CAROL).reviewTarget("stop", "stop-t", 200_000L,
                        new ReviewDraft(TGT, 1, true, 40L, 10L, price("9.00"), 2 * DAY - 1, "收紧"));
            }
            rt.advanceTo(200_000L);
            assertTrue(orderOf(rt, "c1").live(), "停止批次保留真实撤单前的挂单");
        }
        Path forged = resign("forged-stop-" + mode + ".journal", journal, true, snapshot -> {
            ArrayNode timers = (ArrayNode) snapshot.path("pending");
            for (int index = timers.size() - 1; index >= 0; index--) {
                if (PendingTask.ROLE_TRADING_SWEEP_V1.equals(timers.get(index).path("kind").asText())) {
                    timers.remove(index);
                }
            }
        });
        assertThrows(IOException.class, () -> open(forged));
    }

    @Test
    @DisplayName("暂停状态必须保留自己期限的恢复待办")
    void missingHaltReleaseIsRejectedOnRecovery() throws IOException {
        Path journal = tempDir.resolve("halt-release.journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
            session(rt, CAROL).haltTarget("stop", "stop-t", 200_000L,
                    new WorldRoleSession.HaltDraft("halt-stop", TGT, 1, 300_000L, "暂停"));
            rt.advanceTo(200_000L);
        }
        Path forged = resign("forged-halt-release.journal", journal, true, snapshot -> {
            ArrayNode timers = (ArrayNode) snapshot.path("pending");
            for (int index = timers.size() - 1; index >= 0; index--) {
                if (PendingTask.ROLE_TRADING_HALT_RELEASE_V1.equals(timers.get(index).path("kind").asText())) {
                    timers.remove(index);
                }
            }
        });
        assertThrows(IOException.class, () -> open(forged));
    }

    @Test
    @DisplayName("迟到的旧版本撤单任务保持新版本真实挂单")
    void staleSweepCannotCancelNewVersionOrders() throws IOException {
        Path journal = tempDir.resolve("stale-sweep.journal");
        try (WorldRuntime rt = open(journal)) {
            establishTarget(rt, 0L, 2 * DAY, 2 * DAY - 1);
            session(rt, BOB).setTarget("update", "update-t", 200_000L,
                    new TargetDraft(TGT, 1, ORG_F, PRODUCT, ACC_FUND, SYM, 120L,
                            price("10.00"), 2 * DAY, RESEARCH));
            rt.advanceTo(200_001L);
            session(rt, CAROL).reviewTarget("review2", "review2-t", 210_000L,
                    new ReviewDraft(TGT, 2, true, 120L, 40L, price("10.00"), 2 * DAY - 1, "新版本"));
            rt.advanceTo(210_000L);
            session(rt, BOB).confirmTarget("confirm2", "confirm2-t", 220_000L, TGT, 2, 1);
            rt.advanceTo(220_000L);
            session(rt, DAVE).submitChild("new-child", "new-child-t", 230_000L,
                    new ChildDraft(TGT, 2, 1, "new-child", 20L, price("10.00")));
            rt.advanceTo(230_000L);
        }
        Path late = resign("late-old-sweep.journal", journal, true, snapshot -> {
            if (snapshot.path("timeMillis").asLong() < 230_000L) {
                return;
            }
            ObjectNode timer = JSON.createObjectNode();
            timer.put("taskId", "late-old-sweep");
            timer.put("dueTimeMillis", 240_000L);
            timer.put("phase", "RULES");
            timer.put("kind", PendingTask.ROLE_TRADING_SWEEP_V1);
            timer.put("payload", "{\"targetId\":\"" + TGT + "\",\"version\":1}");
            ((ArrayNode) snapshot.path("pending")).add(timer);
            ((ArrayNode) snapshot.path("usedTaskIds")).add("late-old-sweep");
        });
        try (WorldRuntime rt = open(late)) {
            WorldExchange.State before = exchange(rt);
            rt.advanceTo(240_000L);
            assertEquals(before, exchange(rt), "旧版本规则不得改动新版本交易账");
            assertTrue(orderOf(rt, "new-child").live());
        }
    }

    private static String t(String childId) {
        return "t-" + childId;
    }

    private void establishTarget(WorldRuntime rt, long base, long deadline, long riskUntil)
            throws IOException {
        establishResearch(rt, base);
        session(rt, BOB).setTarget("c-tgt", "t-tgt", base + 124_000L,
                new TargetDraft(TGT, 0, ORG_F, PRODUCT, ACC_FUND, SYM, 100L, price("10.00"),
                        deadline, RESEARCH));
        rt.advanceTo(base + 124_000L);
        session(rt, CAROL).reviewTarget("c-rev", "t-rev", base + 125_000L,
                new ReviewDraft(TGT, 1, true, 100L, 40L, price("10.00"), riskUntil, "实验任务输入"));
        rt.advanceTo(base + 125_000L);
        session(rt, BOB).confirmTarget("c-cfm", "t-cfm", base + 126_000L, TGT, 1, 1);
        rt.advanceTo(base + 126_000L);
    }

    private void establishResearch(WorldRuntime rt, long base) throws IOException {
        rt.schedulePublishDocument("c-src", "t-src", base + 500L, SRC_DOC, ALICE,
                Audience.organization(ORG_F), "订单线索", "渠道反馈订单改善，金额待证实。",
                "调研纪要", 60_000L, List.of("internal-src"));
        rt.advanceTo(base + 500L);
        session(rt, ALICE).startReading("c-r1", "t-r1", base + 1_000L, SRC_DOC, 1L);
        rt.advanceTo(base + 1_000L);
        rt.advanceTo(base + 61_000L);
        session(rt, ALICE).submitResearch("c-res", "t-res", base + 62_000L,
                new ResearchDraft(RESEARCH, ORG_F, PRODUCT, SYM, NOTE_DOC, "S 公司订单线索",
                        "渠道反馈订单改善，交付需持续核对。", List.of(new Citation(SRC_DOC, 1L))));
        rt.advanceTo(base + 62_000L);
        session(rt, BOB).startReading("c-r2", "t-r2", base + 63_000L, NOTE_DOC, 1L);
        rt.advanceTo(base + 63_000L);
        rt.advanceTo(base + 123_000L);
    }

    private void child(WorldRuntime rt, String person, String childId, long quantity, String price,
                       long due) throws IOException {
        child(rt, person, childId, quantity, price, due, 1, 1);
    }

    private void child(WorldRuntime rt, String person, String childId, long quantity, String price,
                       long due, int version, int revision) throws IOException {
        session(rt, person).submitChild("c-" + childId, t(childId), due,
                new ChildDraft(TGT, version, revision, childId, quantity, price(price)));
    }

    private WorldRuntime open(String name) throws IOException {
        return open(tempDir.resolve(name));
    }

    private WorldRuntime open(Path file) throws IOException {
        return new WorldRuntime(file, manifest(), OPENINGS, registry(), MFG, charter(), roles(),
                info());
    }

    private static WorldRoleSession session(WorldRuntime rt, String person) throws IOException {
        return rt.openRoleSession(person);
    }

    private static RoleTradingView.TargetView target(WorldRuntime rt, String id) throws IOException {
        for (RoleTradingView.TargetView target : session(rt, BOB).view().targets()) {
            if (target.targetId().equals(id)) {
                return target;
            }
        }
        throw new AssertionError("缺少目标视图：" + id);
    }

    private static RoleTradingView.Notice notice(WorldRuntime rt, String person, String clientTaskId)
            throws IOException {
        for (RoleTradingView.Notice notice : session(rt, person).view().notices()) {
            if (notice.clientTaskId().equals(clientTaskId)) {
                return notice;
            }
        }
        throw new AssertionError("缺少回报：" + person + "/" + clientTaskId);
    }

    private static WorldExchange.State exchange(WorldRuntime rt) {
        return rt.snapshot().exchange().orElseThrow();
    }

    private static WorldExchange.Order orderOf(WorldRuntime rt, String childId) {
        for (RoleTradingModel.ChildLink link : rt.snapshot().trading().orElseThrow().trading()
                .children()) {
            if (link.childId().equals(childId)) {
                return exchange(rt).order(link.orderId());
            }
        }
        throw new AssertionError("缺少订单链接：" + childId);
    }

    private static BigDecimal frozen(WorldRuntime rt) {
        return WorldExchange.frozenCash(exchange(rt), ACC_FUND);
    }

    private static long cents(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }

    // ------------------------------------------------------------------
    // 合法校验和重签（与本轮生产实现无关，测试内自备）
    // ------------------------------------------------------------------

    private Path resign(String name, Path source, boolean expectChanged,
                        Consumer<ObjectNode> change) throws IOException {
        Path target = tempDir.resolve(name);
        int changedFrames = 0;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(source)));
             DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(target)))) {
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException end) {
                    break;
                }
                in.readInt();
                byte[] payload = new byte[length];
                in.readFully(payload);
                byte[] ignored = new byte[32];
                in.readFully(ignored);
                ObjectNode commit = (ObjectNode) JSON.readTree(payload);
                ArrayNode events = (ArrayNode) commit.path("events");
                for (int index = 0; index < events.size(); index++) {
                    ObjectNode event = (ObjectNode) events.get(index);
                    if ("STATE_SNAPSHOT_V1".equals(event.path("type").asText())) {
                        ObjectNode snapshot = (ObjectNode) JSON.readTree(event.path("payload").asText());
                        JsonNode original = snapshot.deepCopy();
                        change.accept(snapshot);
                        if (!snapshot.equals(original)) {
                            changedFrames++;
                        }
                        event.put("payload", JSON.writeValueAsString(snapshot));
                    }
                }
                byte[] rewritten = JSON.writeValueAsBytes(commit);
                out.writeInt(rewritten.length);
                out.writeInt(~rewritten.length);
                out.write(rewritten);
                out.write(MessageDigest.getInstance("SHA-256").digest(rewritten));
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        assertEquals(expectChanged, changedFrames > 0, "重签夹具必须实际执行指定变更");
        return target;
    }

    // ------------------------------------------------------------------
    // 清单与开局
    // ------------------------------------------------------------------

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), SYM);

    private static final List<OpeningAccount> OPENINGS = List.of(
            new OpeningAccount(ACC_FUND, PRODUCT, new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount(ACC_FUND_G, PRODUCT_G, new BigDecimal("500.00"), "实验开局历史禀赋"),
            new OpeningAccount(ACC_INV, INV, new BigDecimal("0.00"), "实验开局历史禀赋"),
            new OpeningAccount("CO", "company", new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "实验开局历史禀赋"),
            new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "实验开局历史禀赋"),
            new OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "实验开局历史禀赋"));

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading")) {
            modules.put(name, 1);
        }
        return new RunManifest(RUN, SEED, RunManifest.RULES_ROLE_TRADING_EXPERIMENT_V1, modules,
                Map.of("fixture", "role-trading-audit-v1"), RunManifest.MODEL_NONE);
    }

    private static RoleRun.Config roles() {
        List<RoleAccess.ProductMandate> mandates = List.of(
                new RoleAccess.ProductMandate(ORG_F, PRODUCT, List.of(ACC_FUND)),
                new RoleAccess.ProductMandate(ORG_G, PRODUCT_G, List.of(ACC_FUND_G)));
        List<RoleAccess.Appointment> appointments = new ArrayList<>(List.of(
                new RoleAccess.Appointment("A-1", ALICE, ORG_F, Role.RESEARCHER, 0L, END, null),
                new RoleAccess.Appointment("A-2", BOB, ORG_F, Role.PORTFOLIO_MANAGER, 0L, END, null),
                new RoleAccess.Appointment("A-3", CAROL, ORG_F, Role.RISK_OFFICER, 0L, END, null),
                new RoleAccess.Appointment("A-4", DAVE, ORG_F, Role.EXECUTION_TRADER, 0L, END,
                        null)));
        return new RoleRun.Config(mandates, appointments);
    }

    private static InformationRun.Config info() {
        return new InformationRun.Config(List.of(ALICE, BOB, CAROL, DAVE));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter("cufe", List.of(
                new BoardModel.Seat("s1", ALICE), new BoardModel.Seat("s2", BOB),
                new BoardModel.Seat("s3", CAROL), new BoardModel.Seat("s4", DAVE),
                new BoardModel.Seat("s5", "eve")), "s1", BoardModel.QUORUM);
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party(ORG_F, PartyKind.ORGANIZATION, "中央财经资管"),
                new OpeningRegistry.Party(ORG_G, PartyKind.ORGANIZATION, "外部机构"),
                new OpeningRegistry.Party("cufe", PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party("eve", PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INV, PartyKind.PERSON, "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner(PRODUCT, OwnerKind.PRODUCT, null),
                new OpeningRegistry.PropertyOwner(PRODUCT_G, OwnerKind.PRODUCT, null),
                new OpeningRegistry.PropertyOwner("orgf", OwnerKind.ORGANIZATION, ORG_F),
                new OpeningRegistry.PropertyOwner("orgg", OwnerKind.ORGANIZATION, ORG_G),
                new OpeningRegistry.PropertyOwner(ALICE, OwnerKind.PERSON, ALICE),
                new OpeningRegistry.PropertyOwner(BOB, OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CAROL, OwnerKind.PERSON, CAROL),
                new OpeningRegistry.PropertyOwner(DAVE, OwnerKind.PERSON, DAVE),
                new OpeningRegistry.PropertyOwner(INV, OwnerKind.PERSON, INV),
                new OpeningRegistry.PropertyOwner("company", OwnerKind.ORGANIZATION, "cufe"),
                new OpeningRegistry.PropertyOwner("supplier", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("customer", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("ops", OwnerKind.AGGREGATE, null));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYM, "cufe", 200L, new BigDecimal("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }
}
