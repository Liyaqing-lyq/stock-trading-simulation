package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.InformationModel.Audience;
import edu.cufe.auction.world.InformationModel.CloseReason;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.RoleAccess.Action;
import edu.cufe.auction.world.RoleAccess.Appointment;
import edu.cufe.auction.world.RoleAccess.Decision;
import edu.cufe.auction.world.RoleAccess.ProductMandate;
import edu.cufe.auction.world.RoleAccess.Role;
import edu.cufe.auction.world.RoleRun.Config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4a.2/M4b.2：任职与实际阅读接入唯一世界日志（informed-stock-company-experiment-v1 / schema6）。
 *
 * <p>七组固定契约：配置与伪造恢复映射；四人共用有限产品账户且经理没有下单工具；PUBLIC/ORG/DIRECT
 * 与无泄露；10:00 开始 30 分钟、10:30 才可引用且中途 v2 不替代；阅读中/同刻离职与继任者不克隆；
 * 重复命令与重启不重复经历；真实完成帧半帧恢复一次。普通已离任者仍可读 PUBLIC、本人直投与
 * 本人个人账户。</p>
 *
 * <p>这是 {@code model=none} 的确定性夹具：没有真实模型、没有网络、没有角色下单/改目标/风险暂停
 * 工具；所有时刻都是显式模拟毫秒。</p>
 */
@DisplayName("WorldRuntime：任职与实际阅读")
class WorldRuntimeRoleReadingTest {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-role-reading";
    private static final String SYM = "CUFE1";
    private static final long DAY = ManufacturingModel.DAY_MILLIS;
    private static final long HOUR = 3_600_000L;
    private static final long END = 30 * DAY;
    private static final long HALF_HOUR = 30 * 60_000L;
    private static final long MINUTE = 60_000L;

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), SYM);

    private static final List<OpeningAccount> OPENINGS = List.of(
            new OpeningAccount("ACC-FUND", "fund-a", bd("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount("ACC-ORG", "orgf", bd("500.00"), "实验开局历史禀赋"),
            new OpeningAccount("ACC-ORG2", "orgg", bd("700.00"), "实验开局历史禀赋"),
            new OpeningAccount("ACC-ALICE", "alice", bd("200.00"), "实验开局历史禀赋"),
            new OpeningAccount("ACC-BOB", "bob", bd("210.00"), "实验开局历史禀赋"),
            new OpeningAccount("ACC-CAROL", "carol", bd("150.00"), "实验开局历史禀赋"),
            new OpeningAccount("ACC-DAVE", "dave", bd("160.00"), "实验开局历史禀赋"),
            new OpeningAccount("ACC-ERIN", "erin", bd("100.00"), "实验开局历史禀赋"),
            new OpeningAccount("ACC-FRANK", "frank", bd("50.00"), "实验开局历史禀赋"),
            new OpeningAccount("ACC-GRACE", "grace", bd("80.00"), "实验开局历史禀赋"),
            new OpeningAccount("CO", "company", bd("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount("SUP", "supplier", bd("100.00"), "实验开局历史禀赋"),
            new OpeningAccount("CUS", "customer", bd("2000.00"), "实验开局历史禀赋"),
            new OpeningAccount("OPS", "ops", bd("0.00"), "实验开局历史禀赋"),
            new OpeningAccount("INV", "investor", bd("500.00"), "实验开局历史禀赋"));

    private static final List<String> READERS = List.of("alice", "bob", "carol", "dave",
            "erin", "frank", "grace");

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 组 1：配置必须成对、伪造恢复映射被拒
    // ------------------------------------------------------------------

    @Test
    @DisplayName("配置成对：缺配置/旧档案多配置/未知读者/预造撤销均在建文件前拒绝")
    void configurationMustPairWithModulesBeforeAnyFileExists() throws IOException {
        Path missing = tempDir.resolve("missing-roles.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(missing,
                manifest(), OPENINGS, registry(), MFG, charter(), null, info()),
                "任职与阅读档案必须给配置");
        assertFalse(Files.exists(missing), "写盘前失败不得留下文件");

        Path extra = tempDir.resolve("extra-roles.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(extra,
                governedManifest(), OPENINGS, registry(), MFG, charter(), roles(), info()),
                "旧治理档案不得携带任职配置");
        assertFalse(Files.exists(extra));

        Path unknownReader = tempDir.resolve("unknown-reader.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(unknownReader,
                manifest(), OPENINGS, registry(), MFG, charter(), roles(),
                new InformationRun.Config(List.of("alice", "ghost"))),
                "登记读者必须是已登记人物");
        assertFalse(Files.exists(unknownReader));

        Path wrongKind = tempDir.resolve("wrong-kind.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(wrongKind,
                manifest(), OPENINGS, registry(), MFG, charter(), roles(),
                new InformationRun.Config(List.of("alice", "ORG-F"))),
                "组织不能作为登记读者");
        assertFalse(Files.exists(wrongKind));

        assertThrows(IllegalArgumentException.class, () -> new Config(
                        List.of(mandate()), List.of(new Appointment("A-0", "alice", "ORG-F",
                                Role.RESEARCHER, 0L, END, 5L))),
                "初始任职不得预造撤销记录");

        Path file = tempDir.resolve("valid.journal");
        try (WorldRuntime runtime = open(file)) {
            assertTrue(runtime.snapshot().roles().isPresent());
            assertTrue(runtime.snapshot().information().isPresent());
            assertEquals(READERS, runtime.snapshot().information().orElseThrow()
                    .information().persons());
            assertEquals(4, runtime.snapshot().roles().orElseThrow().access().appointments().size());
        }

        // 换一套初始任职重开同一日志必须拒绝
        Config swapped = new Config(List.of(mandate()), List.of(
                new Appointment("A-1x", "alice", "ORG-F", Role.RESEARCHER, 0L, END, null),
                new Appointment("A-2x", "bob", "ORG-F", Role.PORTFOLIO_MANAGER, 0L, END, null),
                new Appointment("A-3x", "carol", "ORG-F", Role.RISK_OFFICER, 0L, END, null),
                new Appointment("A-4x", "dave", "ORG-F", Role.EXECUTION_TRADER, 0L, END, null)));
        assertThrows(IOException.class, () -> new WorldRuntime(file, manifest(), OPENINGS,
                registry(), MFG, charter(), swapped, info()), "换一套开局配置必须拒绝");
    }

    @Test
    @DisplayName("伪造恢复映射：改初始任职人物/改登记读者/改账户绑定都在恢复期被拒")
    void forgedRecoveryMappingIsRejected() throws IOException {
        Path file = tempDir.resolve("forged.journal");
        try (WorldRuntime runtime = open(file)) {
            runtime.advanceTo(1_000L);
        }
        // 把第一条初始任职的人物换成另一个已登记人物：validateAgainst 会通过，但必须与冻结配置不符
        Path forgedPerson = tamper(file, "forged-person.journal", root -> true, root ->
                ((ObjectNode) root.path("roles").path("access").path("appointments").get(0))
                        .put("personPartyId", "frank"));
        assertThrows(IOException.class, () -> open(forgedPerson),
                "初始任职人物被改写必须拒绝");

        // 把登记读者换成未登记人物
        Path forgedReaders = tamper(file, "forged-readers.journal", root -> true, root -> {
            ArrayNode persons = (ArrayNode) root.path("information").path("information")
                    .path("persons");
            persons.removeAll();
            persons.add("ghost");
            persons.add("alice");
        });
        assertThrows(IOException.class, () -> open(forgedReaders), "登记读者被改写必须拒绝");

        // 把产品账户换成未授权账户（个人账户），破坏产品授权/账户集合
        Path forgedMandate = tamper(file, "forged-mandate.journal", root -> true, root ->
                ((ObjectNode) root.path("roles").path("access").path("mandates").get(0))
                        .putArray("accountIds").add("ACC-ALICE"));
        assertThrows(IOException.class, () -> open(forgedMandate), "产品授权账户被改写必须拒绝");
    }

    // ------------------------------------------------------------------
    // 组 2：四个不同人物共用一个有限产品账户，经理没有下单工具
    // ------------------------------------------------------------------

    @Test
    @DisplayName("四岗共用同一有限产品账户；经理/研究员/风控都没有下单工具，执行员也不获得目标或风控工具")
    void fourRolesShareOneProductAccountWithoutTradingTools() throws IOException {
        Path file = tempDir.resolve("four-roles.journal");
        try (WorldRuntime runtime = open(file)) {
            runtime.advanceTo(1000L);
            BigDecimal fundCash = cash(runtime, "ACC-FUND");
            assertEquals(0, fundCash.compareTo(bd("1000.00")));

            for (String person : List.of("alice", "bob", "carol", "dave")) {
                RoleView view = runtime.roleView(person);
                assertEquals(1, view.appointments().size(), person + " 应有一条当前任职");
                RoleView.Account fund = account(view, "ACC-FUND");
                assertNotNull(fund, person + " 必须看到同一产品账户");
                assertEquals(RoleView.AccountKind.PRODUCT, fund.kind());
                assertEquals(0, fund.cash().compareTo(fundCash), "四岗读的是同一份钱，不是四份");
                assertTrue(view.toolsAreReadOnly(), person + " 的工具只能是阅读能力");
                assertFalse(accountIds(view).contains("ACC-ORG"), "雇主自有账户不得出现在产品授权里");
                assertFalse(accountIds(view).contains("ACC-ORG2"));
            }
            assertEquals(15, runtime.snapshot().accounts().size(), "开局账户数不变；四岗不复制资金");

            assertEquals(Decision.NOT_AUTHORIZED, decision(runtime.roleView("bob"),
                    Action.EXECUTE_ORDER, "ACCOUNT:ACC-FUND"), "经理不能下单");
            assertEquals(Decision.ALLOW, decision(runtime.roleView("dave"),
                    Action.EXECUTE_ORDER, "ACCOUNT:ACC-FUND"), "执行员可以对授权账户下单");
            assertEquals(Decision.NOT_AUTHORIZED, decision(runtime.roleView("alice"),
                    Action.SET_PORTFOLIO_TARGET, "PRODUCT:fund-a"), "研究员不能改目标");
            assertEquals(Decision.NOT_AUTHORIZED, decision(runtime.roleView("dave"),
                    Action.ISSUE_RISK_HALT, "PRODUCT:fund-a"), "执行员不能发风控暂停");

            List<String> managerTools = runtime.roleView("bob").tools();
            List<String> traderTools = runtime.roleView("dave").tools();
            assertEquals(managerTools, traderTools, "岗位权限不改变已接入工具集合");
            for (String tool : managerTools) {
                assertTrue(tool.startsWith("information."), "只允许阅读工具：" + tool);
            }

            // 管理端原始状态不属于角色结果
            String json = new ObjectMapper().writeValueAsString(runtime.roleView("dave"));
            for (String forbidden : List.of("lotId", "sourceTaskId", "internalSourceIds",
                    "position", "mandates", "governance", "manufacturing", "registry",
                    "ACC-ALICE", "ACC-BOB", "READ-")) {
                assertFalse(json.contains(forbidden), "角色视图不得包含 " + forbidden);
            }
            assertThrows(IllegalArgumentException.class, () -> runtime.roleView("ghost"),
                    "未知人物统一拒绝");
        }
    }

    // ------------------------------------------------------------------
    // 组 3：PUBLIC / ORG / DIRECT 受众与无泄露
    // ------------------------------------------------------------------

    @Test
    @DisplayName("公共/组织/直投可见性精确；跨机构与别人账户无泄露；正文注入不增权限")
    void audiencesDoNotLeakAcrossPeopleOrganizationsOrAccounts() throws IOException {
        Path file = tempDir.resolve("audiences.journal");
        try (WorldRuntime runtime = open(file)) {
            publish(runtime, "doc-public", "grace", Audience.publicAudience(), "公共摘要",
                    "公开正文", 1 * HOUR);
                publish(runtime, "doc-org", "grace", Audience.organization("ORG-F"), "组织摘要",
                    "组织正文", 2 * HOUR);
            publish(runtime, "doc-alice", "grace", Audience.direct("alice"), "直投甲",
                    "给 alice 的正文", 3 * HOUR);
            publish(runtime, "doc-bob", "grace", Audience.direct("bob"), "直投乙",
                    "给 bob 的正文", 4 * HOUR);
            runtime.advanceTo(5 * HOUR);

            assertEquals(List.of("doc-public"), catalogIds(runtime.roleView("frank")),
                    "没有任职的登记人物只看到公共资料");
            assertEquals(List.of("doc-public"), catalogIds(runtime.roleView("grace")));
            assertEquals(List.of("doc-alice", "doc-org", "doc-public"),
                    catalogIds(runtime.roleView("alice")), "alice 看到公共 + 本组织 + 本人直投");
            assertEquals(List.of("doc-bob", "doc-org", "doc-public"),
                    catalogIds(runtime.roleView("bob")), "bob 不得看到 alice 的直投");

            assertFalse(catalogIds(runtime.roleView("frank")).contains("doc-org"),
                    "未任职者不得看到组织资料");
            assertThrows(IllegalArgumentException.class, () -> runtime.roleView("ghost-org"));

            // 正文里的越权指令只是文字：读完不增加任何权限，也不暴露别的账户
            runtime.scheduleStartReading("c-read-org", "t-read-org", 5 * HOUR + 1, "alice",
                    "doc-org", 1);
            runtime.advanceTo(6 * HOUR + 1);
            assertTrue(runtime.canCite("alice", "doc-org", 1), "组织任职者读完组织资料可引用");
            assertEquals(Decision.NOT_AUTHORIZED, decision(runtime.roleView("alice"),
                    Action.EXECUTE_ORDER, "ACCOUNT:ACC-FUND"), "读完正文不授予下单权");
            assertFalse(accountIds(runtime.roleView("alice")).contains("ACC-BOB"),
                    "读完正文不暴露别人账户");
            assertFalse(accountIds(runtime.roleView("alice")).contains("ACC-ORG"),
                    "雇主自有账户仍不可见");
        }
    }

    // ------------------------------------------------------------------
    // 组 4：10:00 读 30 分钟，10:30 才可引用，中途 v2 不替代
    // ------------------------------------------------------------------

    @Test
    @DisplayName("10:00 开始 30 分钟阅读：10:05 无全文，10:30 固定 v1 可引用，v2 中途发布不替代")
    void fixedVersionIsDeliveredOnlyAtDue() throws IOException {
        Path file = tempDir.resolve("fixed-version.journal");
        long publishAt = 10 * HOUR - 1_000L;
        long startAt = 10 * HOUR;
        long dueAt = startAt + HALF_HOUR;
        try (WorldRuntime runtime = open(file)) {
            publish(runtime, "doc-news", "grace", Audience.publicAudience(), "第一版",
                    "第一版正文 v1", publishAt, HALF_HOUR);
            runtime.advanceTo(publishAt);
            runtime.scheduleStartReading("c-start", "t-start", startAt, "alice", "doc-news", 1);
            runtime.advanceTo(startAt);
            assertEquals(1, runtime.roleView("alice").readings().size(), "开始后应有活动阅读");
            assertTrue(runtime.roleView("alice").observations().isEmpty(), "尚未完成没有经历");

            runtime.advanceTo(startAt + 5 * 60_000L);
            RoleView midway = runtime.roleView("alice");
            assertEquals(1, midway.readings().size());
            assertTrue(midway.observations().isEmpty(), "10:05 不得交付全文");
            assertFalse(runtime.canCite("alice", "doc-news", 1), "未读完不能引用");
            String midwayJson = new ObjectMapper().writeValueAsString(midway);
            assertFalse(midwayJson.contains("第一版正文 v1"), "提醒视图不含正文");

            // 阅读中途发布 v2：不能替代正在读的 v1
            publish(runtime, "doc-news", null, null, "第二版", "第二版正文 v2",
                    startAt + 10 * 60_000L, HALF_HOUR);
            runtime.advanceTo(startAt + 10 * 60_000L);
            assertTrue(runtime.canCite("alice", "doc-news", 1) == false);
            assertEquals(2, runtime.roleView("alice").catalog().get(0).version(),
                    "目录显示最新已发布版本");

            runtime.advanceTo(dueAt);
            List<RoleView.Experience> observations =
                    runtime.roleView("alice").observations();
            assertEquals(1, observations.size(), "到期只产生一份经历");
            assertEquals(1, observations.get(0).ref().version(), "经历固定为开始时的 v1");
            assertEquals(1, observations.get(0).ref().index(), "经历固定为本人第 1 次阅读");
            assertEquals("alice", observations.get(0).ref().personPartyId(),
                    "本人范围引用必须带人物，不用全局 READ-n");
            assertEquals("第一版正文 v1", observations.get(0).body());
            assertEquals(dueAt, observations.get(0).completedAt());
            assertTrue(runtime.canCite("alice", "doc-news", 1), "10:30 起可引用 v1");
            assertFalse(runtime.canCite("alice", "doc-news", 2), "没有读过 v2 不能引用");
            assertTrue(runtime.roleView("alice").readings().isEmpty(), "完成后不再有活动阅读");
        }
    }

    // ------------------------------------------------------------------
    // 组 5：阅读中/同刻离职、原经历保留、新人不克隆
    // ------------------------------------------------------------------

    @Test
    @DisplayName("阅读中离职与同刻离职都 DENIED 并释放工时；老经历保留；继任者不克隆")
    void leavingDuringReadingDeniesButKeepsOldExperience() throws IOException {
        Path file = tempDir.resolve("leaving.journal");
        try (WorldRuntime runtime = open(file)) {
            // 让 alice 真正读完一份组织资料（2h 阅读，5h 完成）
            publish(runtime, "doc-kept", "grace", Audience.organization("ORG-F"), "留档",
                    "旧经验正文", 1 * HOUR, 2 * HOUR);
            runtime.advanceTo(1 * HOUR);
            runtime.scheduleStartReading("c-old", "t-old", 3 * HOUR, "alice", "doc-kept", 1);
            runtime.advanceTo(3 * HOUR);

            // 阅读中途离职：5:40 撤销，6:01 到期复核 → DENIED
            publish(runtime, "doc-denied", "grace", Audience.organization("ORG-F"), "被拒",
                    "不该拿到的正文", 4 * HOUR, 1 * HOUR);
            runtime.advanceTo(4 * HOUR);
            runtime.scheduleStartReading("c-mid", "t-mid", 5 * HOUR + 1, "alice", "doc-denied", 1);
            runtime.scheduleRevokeAppointment("c-revoke-alice", "t-revoke-alice",
                    5 * HOUR + 40 * 60_000L, "A-1");
            // 继任者在撤销当刻上任：半开区间允许同刻接任
            runtime.scheduleAppointment("c-appoint-erin", "t-appoint-erin",
                    5 * HOUR + 40 * 60_000L, "A-5", "erin", "ORG-F", Role.RESEARCHER,
                    5 * HOUR + 40 * 60_000L, END);
            runtime.advanceTo(5 * HOUR + 40 * 60_000L);
            assertEquals(1, runtime.roleView("alice").observations().size(), "离职前已完成的经历");
            assertEquals(1, runtime.roleView("erin").appointments().size(), "继任者已上任");
            assertTrue(runtime.roleView("erin").observations().isEmpty(), "新人经历从空开始");

            runtime.advanceTo(6 * HOUR + 1);
            assertEquals(1, runtime.roleView("alice").observations().size(),
                    "离职后不得获得新经历");
            assertFalse(runtime.canCite("alice", "doc-denied", 1));
            assertTrue(runtime.roleView("alice").appointments().isEmpty(), "已离任");
            assertEquals(1, denied(runtime), "到期复核无权限必须留下 DENIED 终态");

            // 同刻离职：erin 的阅读恰在到期时刻被撤销（RULES 先于 COMPLETION）
            publish(runtime, "doc-erin", "grace", Audience.organization("ORG-F"), "继任",
                    "继任者可读的正文", 7 * HOUR, 1 * HOUR);
            runtime.advanceTo(7 * HOUR);
            runtime.scheduleStartReading("c-erin", "t-erin", 8 * HOUR, "erin", "doc-erin", 1);
            runtime.advanceTo(8 * HOUR);
            assertEquals(1, runtime.roleView("erin").readings().size());
            runtime.scheduleRevokeAppointment("c-revoke-erin", "t-revoke-erin", 9 * HOUR, "A-5");
            runtime.advanceTo(9 * HOUR);
            assertTrue(runtime.roleView("erin").observations().isEmpty(),
                    "同刻离职不得在到期时学到无权正文");
            assertEquals(2, denied(runtime), "同刻离职同样留下 DENIED 终态");
            assertEquals(1, runtime.roleView("alice").observations().size(),
                    "老经历保留，不因离职丢失");

            // 普通已离任者：仍可 PUBLIC、本人直投、本人个人账户
            publish(runtime, "doc-public-late", "grace", Audience.publicAudience(), "公开",
                    "公开正文", 9 * HOUR + 1, HALF_HOUR);
            publish(runtime, "doc-alice-late", "grace", Audience.direct("alice"), "直投",
                    "直投正文", 9 * HOUR + 2, HALF_HOUR);
            runtime.advanceTo(10 * HOUR);
            RoleView after = runtime.roleView("alice");
            assertTrue(after.appointments().isEmpty());
            assertEquals(List.of("doc-alice-late", "doc-public-late"), catalogIds(after),
                    "离任者仍看公共与本人直投；组织资料随任职关闭");
            assertTrue(accountIds(after).contains("ACC-ALICE"), "本人个人账户仍可见");
            assertFalse(accountIds(after).contains("ACC-FUND"), "离任后不再看到产品账户");
            assertTrue(after.permissions().isEmpty(), "没有任职就没有岗位权限状态");

            // 工时可再次使用：离任后仍能开始并完成一份公共资料
            runtime.scheduleStartReading("c-late", "t-late", 11 * HOUR, "alice",
                    "doc-public-late", 1);
            runtime.advanceTo(11 * HOUR + HALF_HOUR);
            assertEquals(2, runtime.roleView("alice").observations().size(),
                    "离任者读公共资料仍能产生经历，工时可再次使用");
        }
    }

    // ------------------------------------------------------------------
    // 组 6：取消只取消本单 child
    // ------------------------------------------------------------------

    @Test
    @DisplayName("取消只移除本单自动完成待办；取消别人的阅读单被拒且不影响其 child")
    void cancelRemovesOnlyItsOwnChild() throws IOException {
        Path file = tempDir.resolve("cancel.journal");
        try (WorldRuntime runtime = open(file)) {
            publish(runtime, "doc-c", "grace", Audience.publicAudience(), "取消样本",
                    "取消正文", 1 * HOUR, HALF_HOUR);
            runtime.advanceTo(1 * HOUR);
            runtime.scheduleStartReading("c-a", "t-a", 2 * HOUR, "alice", "doc-c", 1);
            runtime.scheduleStartReading("c-b", "t-b", 2 * HOUR, "bob", "doc-c", 1);
            runtime.advanceTo(2 * HOUR);
            assertEquals(2, pending(runtime, PendingTask.INFORMATION_COMPLETE_READ_V1).size());
            RoleView.ReadingRef aliceRead = runtime.roleView("alice").readings().get(0).ref();
            RoleView.ReadingRef bobRead = runtime.roleView("bob").readings().get(0).ref();
            assertEquals("alice", aliceRead.personPartyId());
            assertEquals("bob", bobRead.personPartyId());
            assertFalse(aliceRead.equals(bobRead), "两个人同刻读同版本仍有不同的本人引用");

            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleCancelReading(
                            "c-cancel-b", "t-cancel-b", 2 * HOUR + 5 * 60_000L, "bob", aliceRead),
                    "拿别人的本人引用必须拒绝");
            runtime.scheduleCancelReading("c-cancel-b2", "t-cancel-b2", 2 * HOUR + 5 * 60_000L,
                    "bob", new RoleView.ReadingRef("bob", aliceRead.docId(), aliceRead.version(),
                            aliceRead.index() + 1));
            runtime.advanceTo(2 * HOUR + 5 * 60_000L);
            assertEquals("NOT_FOUND", report(runtime, "t-cancel-b2").reason(),
                    "不匹配的本人序号取消不到活动单");
            assertEquals(2, pending(runtime, PendingTask.INFORMATION_COMPLETE_READ_V1).size(),
                    "不匹配的取消不得移除任何 child");

            runtime.scheduleCancelReading("c-cancel-a", "t-cancel-a", 2 * HOUR + 6 * 60_000L,
                    "alice", aliceRead);
            runtime.advanceTo(2 * HOUR + 6 * 60_000L);
            assertEquals("CANCELLED", report(runtime, "t-cancel-a").reason());
            assertEquals(1, pending(runtime, PendingTask.INFORMATION_COMPLETE_READ_V1).size(),
                    "只移除本单 child");
            assertTrue(runtime.roleView("alice").observations().isEmpty(), "取消不产生经历");
            assertTrue(runtime.roleView("bob").readings().size() == 1, "别人的阅读不受影响");

            // 取消后工时释放，立即重开（最小可排程时点）：新实例必须拿到新的本人序号
            runtime.scheduleStartReading("c-a2", "t-a2", 2 * HOUR + 6 * 60_000L + 1, "alice",
                    "doc-c", 1);
            runtime.advanceTo(2 * HOUR + 6 * 60_000L + 1);
            assertEquals("STARTED", report(runtime, "t-a2").reason());
            assertEquals(2, pending(runtime, PendingTask.INFORMATION_COMPLETE_READ_V1).size());
            RoleView.ReadingRef aliceAgain = runtime.roleView("alice").readings().get(0).ref();
            assertEquals(2, aliceAgain.index(), "同人同刻重开必须得到新的本人序号");
            assertFalse(aliceRead.equals(aliceAgain), "旧引用不得等于新实例引用");

            // 拿已取消实例的旧引用换一个 commandId 再取消：不得误伤新实例
            runtime.scheduleCancelReading("c-cancel-stale", "t-cancel-stale",
                    2 * HOUR + 7 * 60_000L, "alice", aliceRead);
            runtime.advanceTo(2 * HOUR + 7 * 60_000L);
            assertEquals("NOT_FOUND", report(runtime, "t-cancel-stale").reason(),
                    "旧实例引用不得取消新实例");
            assertEquals(2, pending(runtime, PendingTask.INFORMATION_COMPLETE_READ_V1).size(),
                    "新实例与它的 child 都不受影响");
            assertEquals(aliceAgain, runtime.roleView("alice").readings().get(0).ref());
        }
    }

    @Test
    @DisplayName("同人同毫秒开始、取消、重开：旧引用不能命中新阅读实例")
    void sameMillisecondRestartDoesNotReuseReadingReference() throws IOException {
        long at = 2 * HOUR;
        List<String> order = new ArrayList<>();
        WorldScheduler scheduler = new WorldScheduler(SEED);
        for (String id : List.of("same-a", "same-b", "same-c")) {
            scheduler.schedule(id, at, WorldScheduler.Phase.COMPLETION, () -> order.add(id));
        }
        scheduler.runUntil(at);
        RoleView.ReadingRef original;
        try (WorldRuntime sample = open(tempDir.resolve("same-sample.journal"))) {
            publish(sample, "doc-same", "grace", Audience.publicAudience(), "同刻", "正文", HOUR);
            sample.scheduleStartReading("same-first", order.get(0), at, "alice", "doc-same", 1);
            sample.advanceTo(at);
            original = sample.roleView("alice").readings().get(0).ref();
        }
        try (WorldRuntime runtime = open(tempDir.resolve("same-actual.journal"))) {
            publish(runtime, "doc-same", "grace", Audience.publicAudience(), "同刻", "正文", HOUR);
            runtime.scheduleStartReading("same-first", order.get(0), at, "alice", "doc-same", 1);
            runtime.scheduleCancelReading("same-cancel", order.get(1), at, "alice", original);
            runtime.scheduleStartReading("same-second", order.get(2), at, "alice", "doc-same", 1);
            runtime.advanceTo(at);
            RoleView.ReadingRef current = runtime.roleView("alice").readings().get(0).ref();
            assertEquals(2, current.index());
            assertFalse(original.equals(current));
            assertEquals(1, runtime.snapshot().information().orElseThrow().information().closed().size());
            runtime.scheduleCancelReading("same-stale", "same-stale", at + 1, "alice", original);
            runtime.advanceTo(at + 1);
            assertEquals("NOT_FOUND", report(runtime, "same-stale").reason());
            assertEquals(current, runtime.roleView("alice").readings().get(0).ref());
            assertEquals(1, pending(runtime, PendingTask.INFORMATION_COMPLETE_READ_V1).size());
        }
    }

    // ------------------------------------------------------------------
    // 组 7：重复命令、重启与半帧恢复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重复命令与重启不重复经历；同一命令重发返回原回报")
    void duplicateCommandsAndRestartDoNotDuplicateExperience() throws IOException {
        Path file = tempDir.resolve("restart.journal");
        long publishAt = 1 * HOUR;
        long dueAt = 3 * HOUR;
        try (WorldRuntime runtime = open(file)) {
            String first = runtime.schedulePublishDocument("c-pub", "t-pub", publishAt, "doc-r",
                    "grace", Audience.publicAudience(), "重启样本", "重启正文", "", HALF_HOUR,
                    List.of("src-1"));
            assertEquals("SCHEDULED:t-pub", first);
            runtime.advanceTo(publishAt);
            assertEquals(first, runtime.schedulePublishDocument("c-pub", "t-pub", publishAt,
                    "doc-r", "grace", Audience.publicAudience(), "重启样本", "重启正文", "",
                    HALF_HOUR, List.of("src-1")), "同命令同内容返回原回报");
            runtime.scheduleStartReading("c-read", "t-read", 2 * HOUR, "alice", "doc-r", 1);
            runtime.advanceTo(dueAt);
            assertEquals(1, runtime.roleView("alice").observations().size());
        }
        long position;

        try (WorldRuntime restarted = open(file)) {
            assertEquals(1, restarted.roleView("alice").observations().size(),
                    "恢复只读已确认经历，不重读");
            assertEquals(1, restarted.snapshot().information().orElseThrow().information()
                    .closed().size(), "恢复不产生第二份终态");
            position = restarted.snapshot().position();
            restarted.advanceTo(dueAt);
            assertEquals(position, restarted.snapshot().position(), "无新到期任务不追加批次");
            assertEquals(1, restarted.roleView("alice").observations().size());
        }
    }

    @Test
    @DisplayName("真实完成帧半帧：恢复丢弃不完整尾帧，重跑只完成一次；同 seed 乱序结果一致")
    void interruptedCompletionFrameRecoversExactlyOnce() throws IOException {
        Path base = tempDir.resolve("interrupted.journal");
        long startAt = 1 * HOUR;
        long dueAt = 2 * HOUR;
        try (WorldRuntime runtime = open(base)) {
            publish(runtime, "doc-i", "grace", Audience.publicAudience(), "半帧",
                    "半帧正文", 1000L, 1 * HOUR);
            runtime.advanceTo(1000L);
            runtime.scheduleStartReading("c-i", "t-i", startAt, "alice", "doc-i", 1);
            runtime.advanceTo(startAt);
        }
        // 完整完成一次，得到带完成帧的日志
        Path completed = tempDir.resolve("completed.journal");
        Files.copy(base, completed);
        try (WorldRuntime runtime = open(completed)) {
            runtime.advanceTo(dueAt);
            assertEquals(1, runtime.roleView("alice").observations().size());
        }
        byte[] bytes = Files.readAllBytes(completed);
        assertTrue(bytes.length > 64);
        // 切断最后一帧的尾部：模拟写了一半的完成帧
        Path torn = tempDir.resolve("torn.journal");
        Files.write(torn, java.util.Arrays.copyOf(bytes, bytes.length - 10));
        try (WorldRuntime runtime = open(torn)) {
            assertTrue(runtime.roleView("alice").observations().isEmpty(),
                    "半帧里的完成不得被确认");
            assertEquals(1, runtime.roleView("alice").readings().size(), "活动阅读仍在");
            assertEquals(1, pending(runtime, PendingTask.INFORMATION_COMPLETE_READ_V1).size());
            runtime.advanceTo(dueAt);
            assertEquals(1, runtime.roleView("alice").observations().size(), "重跑只完成一次");
        }
        // 再重启一次：仍然只有一份经历
        try (WorldRuntime runtime = open(torn)) {
            assertEquals(1, runtime.roleView("alice").observations().size());
            assertEquals(1, runtime.snapshot().information().orElseThrow().information()
                    .closed().size());
        }

        // 同 seed、相反排程顺序 → 输入与结果一致
        List<RoleView.Experience> forward = readingOutcome(
                tempDir.resolve("order-fwd.journal"), false);
        List<RoleView.Experience> reversed = readingOutcome(
                tempDir.resolve("order-rev.journal"), true);
        assertEquals(forward, reversed, "同 seed 下排程顺序不改变最终经历");
        assertEquals(2, forward.size());
    }

    // ------------------------------------------------------------------
    // 组 8：隐藏人物不变性与跨活动不丢状态（Codex 追加契约）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("隐藏人物多读一次不改变本人的 RoleView JSON（无全局 READ-n 泄露）")
    void hiddenPersonReadingDoesNotChangeMyRoleView() throws IOException {
        ObjectMapper json = new ObjectMapper();
        String baseline = aliceViewAtThreeHours(tempDir.resolve("hidden-a.journal"), false, json);
        String withHidden = aliceViewAtThreeHours(tempDir.resolve("hidden-b.journal"), true, json);
        assertEquals(baseline, withHidden, "别人的隐蔽阅读不得改变本人的视图 JSON");
        assertFalse(withHidden.contains("READ-"), "视图不得暴露管理端全局 READ-n 序号");
        assertFalse(withHidden.contains("sourceTaskId"));
    }

    /** 同一时刻的 alice 视图；{@code hiddenReading} 为真时另一人物先多读一次。 */
    private String aliceViewAtThreeHours(Path file, boolean hiddenReading, ObjectMapper json)
            throws IOException {
        try (WorldRuntime runtime = open(file)) {
            publish(runtime, "doc-hidden", "grace", Audience.publicAudience(), "别人看的",
                    "别人看的正文", 1 * HOUR, HALF_HOUR);
            publish(runtime, "doc-mine", "grace", Audience.publicAudience(), "本人看的",
                    "本人看的正文", 1 * HOUR + 1, HALF_HOUR);
            runtime.advanceTo(2 * HOUR);
            if (hiddenReading) {
                runtime.scheduleStartReading("c-hidden", "t-hidden", 2 * HOUR + 1, "erin",
                        "doc-hidden", 1);
            }
            runtime.scheduleStartReading("c-mine", "t-mine", 3 * HOUR, "alice", "doc-mine", 1);
            runtime.advanceTo(3 * HOUR);
            return json.writeValueAsString(runtime.roleView("alice"));
        }
    }

    @Test
    @DisplayName("现金转移与自动关期穿插进行时，任职/资料/活动阅读/经历都不丢失且视图反映新余额")
    void crossActivityKeepsRoleReadingStateAndMoney() throws IOException {
        Path file = tempDir.resolve("cross-activity.journal");
        try (WorldRuntime runtime = open(file)) {
            publish(runtime, "doc-x", "grace", Audience.organization("ORG-F"), "跨活动",
                    "跨活动正文", 1 * HOUR, 2 * HOUR);
            runtime.advanceTo(1 * HOUR);
            String transfer = runtime.scheduleTransfer("c-tr", "t-tr", 2 * HOUR, "alice",
                    "ACC-ALICE", "ACC-BOB", bd("50.00"), 0L);
            assertEquals("SCHEDULED:t-tr", transfer);
            runtime.scheduleStartReading("c-x", "t-x", 3 * HOUR, "alice", "doc-x", 1);
            runtime.advanceTo(3 * HOUR);
            assertEquals(0, cash(runtime, "ACC-ALICE").compareTo(bd("150.00")),
                    "现金转移真实扣减本人账户");
            assertEquals(1, runtime.roleView("alice").readings().size(), "阅读不因转账丢失");
            assertEquals(1, runtime.roleView("alice").appointments().size(), "任职不因转账丢失");

            runtime.advanceTo(5 * HOUR);
            assertEquals(1, runtime.roleView("alice").observations().size());
            assertTrue(runtime.canCite("alice", "doc-x", 1));

            // 自动关期与后续批次不得冲掉任职、资料与经历
            runtime.advanceTo(30 * DAY + 1);
            assertEquals(1, runtime.roleView("alice").observations().size(),
                    "自动关期后经历仍在");
            assertEquals(1, runtime.snapshot().information().orElseThrow().information()
                    .documents().size(), "资料不丢失");
            assertEquals(1, runtime.snapshot().manufacturing().orElseThrow().ledger()
                    .closedPeriods(), "第 0 期已强制关期");
            assertEquals(0, cash(runtime, "ACC-ALICE").compareTo(bd("150.00")),
                    "关期不改个人现金");
            assertEquals(0, cash(runtime, "ACC-BOB").compareTo(bd("260.00")),
                    "收款方拿到同一笔钱");
            assertTrue(runtime.roleView("alice").observations().get(0).body()
                    .contains("跨活动正文"), "经历仍是固定版本全文");
        }
    }

    // ------------------------------------------------------------------
    // 组 9：命令身份绑定（due/task/kind/引用）与完成帧真实 I/O 失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一命令换到期/任务/种类一律拒绝且不写盘；原参数重放仍返回原 SCHEDULED")
    void commandIdentityBindsDueTaskKindAndReference() throws IOException {
        Path file = tempDir.resolve("command-identity.journal");
        try (WorldRuntime runtime = open(file)) {
            String first = runtime.schedulePublishDocument("c-pub", "t-pub", 1 * HOUR, "doc-id",
                    "grace", Audience.publicAudience(), "身份", "身份正文", "", HALF_HOUR,
                    List.of("internal-id"));
            runtime.advanceTo(1 * HOUR);
            long position = runtime.snapshot().position();

            assertEquals(first, runtime.schedulePublishDocument("c-pub", "t-pub", 1 * HOUR,
                    "doc-id", "grace", Audience.publicAudience(), "身份", "身份正文", "",
                    HALF_HOUR, List.of("internal-id")), "原参数重放返回原回报");
            assertEquals(position, runtime.snapshot().position(), "重放不写盘");

            assertThrows(IllegalArgumentException.class, () -> runtime.schedulePublishDocument(
                    "c-pub", "t-pub", 2 * HOUR, "doc-id", "grace", Audience.publicAudience(),
                    "身份", "身份正文", "", HALF_HOUR, List.of("internal-id")),
                    "同一命令不得换到期");
            assertThrows(IllegalArgumentException.class, () -> runtime.schedulePublishDocument(
                    "c-pub", "t-pub-2", 1 * HOUR, "doc-id", "grace", Audience.publicAudience(),
                    "身份", "身份正文", "", HALF_HOUR, List.of("internal-id")),
                    "同一命令不得换任务标识");
            assertEquals(position, runtime.snapshot().position(), "拒绝后仍不写盘");

            // 同类参数换动作种类：payload 相同但 kind 不同，必须由完整待办比较拦下
            runtime.schedulePurchaseOrder("c-kind", "t-kind", 2 * HOUR, "company", "po-kind", 10);
            assertEquals(position + 1, runtime.snapshot().position());
            long afterKind = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleProduction(
                    "c-kind", "t-kind", 2 * HOUR, "company", "po-kind", 10),
                    "同一命令不得换任务种类");
            assertEquals(afterKind, runtime.snapshot().position(), "换种类被拒且不写盘");

            // 引用字段进入规范请求：换版本/换本人序号都拒绝
            runtime.advanceTo(2 * HOUR);
            runtime.scheduleStartReading("c-start", "t-start", 3 * HOUR, "alice", "doc-id", 1);
            runtime.advanceTo(3 * HOUR);
            assertEquals("SCHEDULED:t-start", runtime.scheduleStartReading("c-start", "t-start",
                    3 * HOUR, "alice", "doc-id", 1), "开始阅读原参数重放");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleStartReading(
                    "c-start", "t-start", 3 * HOUR, "alice", "doc-id", 2),
                    "同一命令不得换阅读版本");
        }
    }

    @Test
    @DisplayName("活动阅读已持久后线程中断使完成帧写入失败：当场不发布、暂停；重开只完成一次")
    void interruptedCompletionFrameFailsWithoutPublishing() throws IOException {
        Path file = tempDir.resolve("interrupt.journal");
        long startAt = 2 * HOUR;
        long dueAt = 3 * HOUR;
        long position;
        try (WorldRuntime runtime = open(file)) {
            publish(runtime, "doc-int", "grace", Audience.publicAudience(), "中断",
                    "中断正文", 1 * HOUR, 1 * HOUR);
            runtime.advanceTo(1 * HOUR);
            runtime.scheduleStartReading("c-int", "t-int", startAt, "alice", "doc-int", 1);
            runtime.advanceTo(startAt);
            assertEquals(1, runtime.roleView("alice").readings().size());
            WorldRuntime.Snapshot before = runtime.snapshot();
            position = runtime.snapshot().position();
            int reports = runtime.snapshot().information().orElseThrow().reports().size();

            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, () -> runtime.advanceTo(dueAt),
                        "中断必须让真实完成帧写入抛 IOException");
            } finally {
                Thread.interrupted();
            }

            assertTrue(runtime.isPaused(), "写失败必须暂停");
            assertEquals(before, runtime.snapshot(), "完整快照保持已确认旧版");
            assertEquals(position, runtime.snapshot().position(), "当场不发布新快照");
            int reportsNow = runtime.snapshot().information().orElseThrow().reports().size();
            assertEquals(reports, reportsNow, "报告不增加");
            assertTrue(runtime.roleView("alice").observations().isEmpty(), "经历不产生");
            assertTrue(runtime.snapshot().information().orElseThrow().information()
                    .closed().isEmpty(), "终态不产生");
            assertEquals(1, runtime.roleView("alice").readings().size(), "活动单仍在");
            assertEquals(1, pending(runtime, PendingTask.INFORMATION_COMPLETE_READ_V1).size(),
                    "唯一 child 仍在");
        }

        try (WorldRuntime reopened = open(file)) {
            assertEquals(1, reopened.roleView("alice").readings().size());
            reopened.advanceTo(dueAt);
            assertEquals(1, reopened.roleView("alice").observations().size(), "重开只完成一次");
            assertEquals(1, reopened.snapshot().information().orElseThrow().information()
                    .closed().size());
            assertEquals(0, pending(reopened, PendingTask.INFORMATION_COMPLETE_READ_V1).size());
        }
    }

    // ------------------------------------------------------------------
    // 夹具：一次完整阅读（可反向排程）
    // ------------------------------------------------------------------

    private List<RoleView.Experience> readingOutcome(Path file, boolean reverse)
            throws IOException {
        long publishAt = 1 * HOUR;
        long startAt = 2 * HOUR;
        try (WorldRuntime runtime = open(file)) {
            // 同一到期时刻的两条阅读，按相反顺序排程：公平顺序不得改变最终经历
            if (reverse) {
                runtime.scheduleStartReading("c-o-b", "t-o-b", startAt, "bob", "doc-o", 1);
                runtime.scheduleStartReading("c-o-a", "t-o-a", startAt, "alice", "doc-o", 1);
            } else {
                runtime.scheduleStartReading("c-o-a", "t-o-a", startAt, "alice", "doc-o", 1);
                runtime.scheduleStartReading("c-o-b", "t-o-b", startAt, "bob", "doc-o", 1);
            }
            publish(runtime, "doc-o", "grace", Audience.publicAudience(), "顺序",
                    "顺序正文", publishAt, HALF_HOUR);
            runtime.advanceTo(startAt);
            runtime.advanceTo(startAt + HALF_HOUR);
            List<RoleView.Experience> all = new ArrayList<>(
                    runtime.roleView("alice").observations());
            all.addAll(runtime.roleView("bob").observations());
            return all;
        }
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private WorldRuntime open(Path file) throws IOException {
        return new WorldRuntime(file, manifest(), OPENINGS, registry(), MFG, charter(),
                roles(), info());
    }

    private void publish(WorldRuntime runtime, String docId, String publisher, Audience audience,
                         String title, String body, long dueTimeMillis) throws IOException {
        publish(runtime, docId, publisher, audience, title, body, dueTimeMillis, HALF_HOUR);
    }

    private void publish(WorldRuntime runtime, String docId, String publisher, Audience audience,
                         String title, String body, long dueTimeMillis, long duration)
            throws IOException {
        if (publisher == null) {
            runtime.schedulePublishDocumentVersion("c-ver-" + docId + "-" + dueTimeMillis,
                    "t-ver-" + docId + "-" + dueTimeMillis, dueTimeMillis, docId, title, body,
                    "", duration, List.of("internal-" + docId));
        } else {
            runtime.schedulePublishDocument("c-pub-" + docId + "-" + dueTimeMillis,
                    "t-pub-" + docId + "-" + dueTimeMillis, dueTimeMillis, docId, publisher,
                    audience, title, body, "", duration, List.of("internal-" + docId));
        }
    }

    // ------------------------------------------------------------------
    // 断言辅助
    // ------------------------------------------------------------------

    private static RoleView.Account account(RoleView view, String accountId) {
        for (RoleView.Account account : view.accounts()) {
            if (account.accountId().equals(accountId)) {
                return account;
            }
        }
        return null;
    }

    private static List<String> accountIds(RoleView view) {
        List<String> ids = new ArrayList<>();
        view.accounts().forEach(account -> ids.add(account.accountId()));
        return ids;
    }

    private static List<String> catalogIds(RoleView view) {
        List<String> ids = new ArrayList<>();
        view.catalog().forEach(entry -> ids.add(entry.docId()));
        return ids;
    }

    private static Decision decision(RoleView view, Action action, String scope) {
        for (RoleView.Permission permission : view.permissions()) {
            if (permission.action() == action && permission.scope().equals(scope)) {
                return permission.decision();
            }
        }
        throw new AssertionError("缺少权限状态：" + action + "/" + scope);
    }

    private static List<PendingTask> pending(WorldRuntime runtime, String kind) {
        List<PendingTask> found = new ArrayList<>();
        for (PendingTask task : runtime.snapshot().pending()) {
            if (task.kind().equals(kind)) {
                found.add(task);
            }
        }
        return found;
    }

    private static InformationRun.Report report(WorldRuntime runtime, String taskId) {
        InformationRun.Report found = runtime.snapshot().information().orElseThrow()
                .reports().get(taskId);
        assertNotNull(found, "缺少阅读报告：" + taskId);
        return found;
    }

    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }

    private static int denied(WorldRuntime runtime) {
        int count = 0;
        for (InformationModel.ClosedReading closed : runtime.snapshot().information().orElseThrow()
                .information().closed()) {
            if (closed.reason() == CloseReason.DENIED) {
                count++;
            }
        }
        return count;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ------------------------------------------------------------------
    // 篡改与恢复辅助
    // ------------------------------------------------------------------

    private Path tamper(Path source, String name, Predicate<ObjectNode> when,
                        Consumer<ObjectNode> change) throws IOException {
        Path target = tempDir.resolve(name);
        ObjectMapper json = new ObjectMapper();
        try (WorldJournal original = new WorldJournal(source, RUN);
             WorldJournal rewritten = new WorldJournal(target, RUN)) {
            for (WorldCommit commit : original.commits()) {
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    if ("STATE_SNAPSHOT_V1".equals(event.type())) {
                        ObjectNode root = (ObjectNode) json.readTree(event.payload());
                        if (when == null || when.test(root)) {
                            change.accept(root);
                            events.add(new WorldEvent(event.eventId(), event.type(),
                                    json.writeValueAsString(root)));
                        } else {
                            events.add(event);
                        }
                    } else {
                        events.add(event);
                    }
                }
                rewritten.commit(commit.commandId(), commit.commandContent(), commit.result(),
                        events);
            }
        }
        return target;
    }

    // ------------------------------------------------------------------
    // 登记与清单
    // ------------------------------------------------------------------

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        modules.put("manufacturing", 1);
        modules.put("governance", 1);
        modules.put("roles", 1);
        modules.put("information", 1);
        return new RunManifest(RUN, SEED, RunManifest.RULES_INFORMED_STOCK_COMPANY_EXPERIMENT_V1,
                modules, Map.of("fixture", "role-reading-test-v1"), RunManifest.MODEL_NONE);
    }

    private static RunManifest governedManifest() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        modules.put("manufacturing", 1);
        modules.put("governance", 1);
        return new RunManifest(RUN, SEED, RunManifest.RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1,
                modules, Map.of(), RunManifest.MODEL_NONE);
    }

    private static ProductMandate mandate() {
        return new ProductMandate("ORG-F", "fund-a", List.of("ACC-FUND"));
    }

    private static Config roles() {
        return new Config(List.of(mandate()), List.of(
                new Appointment("A-1", "alice", "ORG-F", Role.RESEARCHER, 0L, END, null),
                new Appointment("A-2", "bob", "ORG-F", Role.PORTFOLIO_MANAGER, 0L, END, null),
                new Appointment("A-3", "carol", "ORG-F", Role.RISK_OFFICER, 0L, END, null),
                new Appointment("A-4", "dave", "ORG-F", Role.EXECUTION_TRADER, 0L, END, null)));
    }

    private static InformationRun.Config info() {
        return new InformationRun.Config(READERS);
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter("cufe", List.of(
                new BoardModel.Seat("s1", "alice"), new BoardModel.Seat("s2", "bob"),
                new BoardModel.Seat("s3", "carol"), new BoardModel.Seat("s4", "dave"),
                new BoardModel.Seat("s5", "erin")), "s1", BoardModel.QUORUM);
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = new ArrayList<>(List.of(
                new OpeningRegistry.Party("ORG-F", PartyKind.ORGANIZATION, "中央财经资管"),
                new OpeningRegistry.Party("ORG-G", PartyKind.ORGANIZATION, "外部机构"),
                new OpeningRegistry.Party("cufe", PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party("alice", PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party("bob", PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party("carol", PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party("dave", PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party("erin", PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party("frank", PartyKind.PERSON, "己"),
                new OpeningRegistry.Party("grace", PartyKind.PERSON, "庚"),
                new OpeningRegistry.Party("investor", PartyKind.PERSON, "投资者")));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner("fund-a", OwnerKind.PRODUCT, null),
                new OpeningRegistry.PropertyOwner("orgf", OwnerKind.ORGANIZATION, "ORG-F"),
                new OpeningRegistry.PropertyOwner("orgg", OwnerKind.ORGANIZATION, "ORG-G"),
                new OpeningRegistry.PropertyOwner("alice", OwnerKind.PERSON, "alice"),
                new OpeningRegistry.PropertyOwner("bob", OwnerKind.PERSON, "bob"),
                new OpeningRegistry.PropertyOwner("carol", OwnerKind.PERSON, "carol"),
                new OpeningRegistry.PropertyOwner("dave", OwnerKind.PERSON, "dave"),
                new OpeningRegistry.PropertyOwner("erin", OwnerKind.PERSON, "erin"),
                new OpeningRegistry.PropertyOwner("frank", OwnerKind.PERSON, "frank"),
                new OpeningRegistry.PropertyOwner("grace", OwnerKind.PERSON, "grace"),
                new OpeningRegistry.PropertyOwner("company", OwnerKind.ORGANIZATION, "cufe"),
                new OpeningRegistry.PropertyOwner("supplier", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("customer", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("ops", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("investor", OwnerKind.PERSON, "investor"));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYM, "cufe", 200, bd("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation("INV", SYM, 200, new BigDecimal("10.0000"),
                        ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }
}
