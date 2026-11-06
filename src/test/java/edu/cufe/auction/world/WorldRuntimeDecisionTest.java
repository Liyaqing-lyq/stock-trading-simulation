package edu.cufe.auction.world;

import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.InformationModel.Audience;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.RoleAccess.Role;
import edu.cufe.auction.world.RoleTradingView;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4d Task2–3：决定屏障与运行桥（{@code decision-experiment-v1} / schema8）。
 *
 * <p>本类是本轮<b>测试先行</b>产物：只使用既有真实有限世界与真实交易夹具，要求实现方提供
 * {@link DecisionRun} 及其运行入口。所有断言都以可观察行为为准（日志位置、批次内容、
 * 世界时间、钱股、回报），不使用 Mock 世界账，金额 BigDecimal、数量 long。</p>
 *
 * <p>手算锚点：思考耗时 1&nbsp;000 ms、动作耗时 2&nbsp;000 ms，因此屏障 = 决定点 + 1&nbsp;000，
 * 动作到期 = 屏障 + 2&nbsp;000。</p>
 */
@DisplayName("WorldRuntime：M4d 决定屏障与尝试")
class WorldRuntimeDecisionTest {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-decision-runtime";
    private static final String SYM = "CUFE1";
    private static final long DAY = 86_400_000L;
    private static final long END = 30 * DAY;
    private static final long THINK = 1_000L;
    private static final long ACTION = 2_000L;
    private static final long READ_MILLIS = 5_000L;

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
    private static final String ERIN = "erin";

    private static final String WAKE = "SCHEDULED";
    private static final String SRC_DOC = "src-1";

    private static final String TARGET_SET_ARGS = "{\"targetId\":\"tgt-1\",\"expectedVersion\":0,"
            + "\"organizationPartyId\":\"ORG-F\",\"productOwnerId\":\"fund-a\","
            + "\"accountId\":\"ACC-FUND\",\"symbol\":\"CUFE1\",\"absoluteTargetQuantity\":100,"
            + "\"priceLimit\":\"10.00\",\"deadlineMillis\":900000,\"sourceResearchId\":\"res-1\"}";

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 1. 档案与 schema 成对
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新档案必须 decisions:1 + scripted 版本 + 成对 Config，旧档案不得多带")
    void decisionProfileRequiresPairedModuleConfigAndScriptedVersion() throws IOException {
        Map<String, Integer> withoutDecisions = modules();
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_DECISION_EXPERIMENT_V1, withoutDecisions, Map.of(),
                RunManifest.SCRIPTED_DECISIONS_V1), "新档案必须声明 decisions:1");

        Map<String, Integer> withDecisions = modules();
        withDecisions.put("decisions", 1);
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_DECISION_EXPERIMENT_V1, withDecisions, Map.of(),
                RunManifest.MODEL_NONE), "新档案不能用 none 隐式开启决定");

        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_ROLE_TRADING_EXPERIMENT_V1, withDecisions, Map.of(),
                RunManifest.SCRIPTED_DECISIONS_V1), "旧档案不得带 decisions 模块");

        Path missing = tempDir.resolve("missing-config.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(missing, manifest(),
                OPENINGS, registry(), MFG, charter(), roles(), info()),
                "新档案缺 Config 必须在建文件前拒绝");
        assertFalse(Files.exists(missing), "写盘前失败不得留下文件");

        Path extra = tempDir.resolve("extra-config.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(extra, legacyManifest(),
                OPENINGS, registry(), MFG, charter(), roles(), info(), config(4, 2, 60_000L, 2)),
                "旧档案多带 Config 必须在建文件前拒绝");
        assertFalse(Files.exists(extra), "写盘前失败不得留下文件");
    }

    @Test
    @DisplayName("schema8 持久 decisions；旧七参档案不出现 decisions 字段")
    void schemaEightPersistsDecisionsAndLegacySevenDoesNot() throws IOException {
        Path decisionJournal = tempDir.resolve("schema8.journal");
        try (WorldRuntime rt = open(decisionJournal)) {
            assertTrue(rt.snapshot().decisions().isPresent(), "新档案必须有决定子集");
            assertEquals(0, decisions(rt).decisions().size());
        }
        String json = snapshotJson(decisionJournal);
        assertTrue(json.contains("\"schemaVersion\":8"), "新档案版本必须是 8");
        assertTrue(json.contains("\"decisions\""), "新档案必须持久决定子集");

        Path legacyJournal = tempDir.resolve("schema7.journal");
        try (WorldRuntime rt = openLegacy(legacyJournal)) {
            assertTrue(rt.snapshot().decisions().isEmpty(), "旧档案没有决定子集");
            assertEquals(0, rt.snapshot().pending().stream()
                    .filter(task -> task.kind().contains("DECISION")).count());
        }
        String legacy = snapshotJson(legacyJournal);
        assertTrue(legacy.contains("\"schemaVersion\":7"), "旧档案版本仍是 7");
        assertFalse(legacy.contains("\"decisions\""), "旧档案不得凭空多出 decisions 字段");
    }

    // ------------------------------------------------------------------
    // 2. 开闸
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新运行与重开都必须显式开闸，未开闸拒绝开决定与收结果")
    void freshRunRefusesDispatchUntilBeginSession() throws IOException {
        Path file = tempDir.resolve("gate.journal");
        DecisionModel.Reply fabricated = new DecisionModel.Reply(RUN, "g-1", "ATT-1", 1, 1,
                "{\"tool\":\"role.wait\"}", usage(1, 10, 5), "scripted:test-v1");
        long position;
        try (WorldRuntime rt = open(file)) {
            position = rt.snapshot().position();
            assertThrows(IllegalStateException.class,
                    () -> rt.openDecisions("d-0", List.of(request(ALICE, "g-1"))),
                    "未开闸不得开决定");
            assertThrows(IllegalStateException.class,
                    () -> rt.acceptDecisionReply("d-0", fabricated), "未开闸不得收结果");
            assertEquals(position, rt.snapshot().position(), "拒绝不写盘");
            assertEquals(0, decisions(rt).decisions().size());

            assertTrue(rt.beginDecisionSession("open-1").startsWith("SESSION_OPEN"));
            assertEquals("DECIDING:1", rt.openDecisions("d-1", List.of(request(ALICE, "g-1"))));
            assertEquals(1, decisions(rt).decisions().size());
        }
        try (WorldRuntime reopened = open(file)) {
            assertThrows(IllegalStateException.class,
                    () -> reopened.openDecisions("d-2", List.of(request(BOB, "g-2"))),
                    "重开后本地派发默认关闭");
            reopened.beginDecisionSession("open-2");
            assertEquals("DECIDING:1",
                    reopened.openDecisions("d-3", List.of(request(BOB, "g-2"))));
            assertEquals(2, decisions(reopened).decisions().size());
        }
    }

    // ------------------------------------------------------------------
    // 3. 一批冻结同一截止位置与输入裁剪
    // ------------------------------------------------------------------

    @Test
    @DisplayName("一次开批把两人冻在同一截止位置，输入不含内部元数据且工具取观察交集")
    void oneBatchFreezesIdenticalCutoffForEachPerson() throws IOException {
        try (WorldRuntime rt = open("batch.journal")) {
            rt.beginDecisionSession("open-1");
            RoleTradingView aliceView = rt.openRoleSession(ALICE).view();
            assertEquals("DECIDING:2", rt.openDecisions("d-1",
                    List.of(request(ALICE, "a-1"), request(BOB, "b-1"))));

            long position = rt.snapshot().position();
            DecisionModel.Decision alice = decision(rt, "a-1");
            DecisionModel.Decision bob = decision(rt, "b-1");
            assertEquals(alice.cutoffPosition(), bob.cutoffPosition(), "两人必须同一截止位置");
            assertEquals(alice.cutoffTimeMillis(), bob.cutoffTimeMillis(), "两人必须同一截止时点");
            assertEquals(position - 1, alice.cutoffPosition(), "截止位置是开批前已确认位置");
            assertEquals(alice.cutoffTimeMillis(), alice.decisionAtMillis(),
                    "决定点只能是当前已确认时点");
            assertEquals(alice.decisionAtMillis() + THINK, alice.barrierAtMillis(),
                    "屏障 = 决定点 + 思考耗时");

            for (DecisionModel.Decision decision : List.of(alice, bob)) {
                String text = decision.inputText();
                assertTrue(text.contains(decision.personPartyId()), "输入必须标明本人");
                assertTrue(text.contains("role.wait"), "输入必须列可用工具");
                for (String forbidden : List.of("cutoffPosition", "cutoffTime", "attemptId",
                        "recoveryGeneration", "orderId", "\"sequence\"")) {
                    assertFalse(text.contains(forbidden), "输入不得含内部元数据：" + forbidden);
                }
            }
            for (RoleView.Account account : aliceView.profile().accounts()) {
                assertTrue(alice.inputText().contains(account.accountId()),
                        "输入必须含本人获权资产：" + account.accountId());
            }
            assertTrue(bob.inputText().contains("role.target.set"), "经理输入必须列岗位工具");

            Set<String> expected = new TreeSet<>(aliceView.tools());
            expected.remove(RoleTradingView.TOOL_CITE);
            // 纯核合同要求允许集合必须含唯一内建观望 role.wait，故在此并入后再比较。
            expected.add(DecisionModel.TOOL_WAIT);
            assertEquals(expected, new TreeSet<>(alice.allowedTools()),
                    "允许工具 = 观察工具去掉只读引用（并含内建观望）");
            assertTrue(alice.allowedTools().contains(DecisionModel.TOOL_WAIT));
        }
    }

    // ------------------------------------------------------------------
    // 4. 回复 + 决定 + 动作任务同一帧
    // ------------------------------------------------------------------

    @Test
    @DisplayName("一次回复把决定、动作任务与已用标识写进同一批，位置只前进一")
    void replyDecisionAndActionLandInOneFrame() throws IOException {
        try (WorldRuntime rt = open("reply.journal")) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(BOB, "b-target")));
            long before = rt.snapshot().position();

            assertEquals("RECORDED", rt.acceptDecisionReply("r-1", dispatchedReply(rt, "b-target",
                    RoleTradingView.TOOL_TARGET_SET, TARGET_SET_ARGS, usage(1, 100, 40))));
            assertEquals(before + 1, rt.snapshot().position(), "决定与动作任务必须同批，只加一帧");

            DecisionModel.Decision recorded = decision(rt, "b-target");
            assertEquals(DecisionModel.Status.RECORDED, recorded.status());
            assertEquals(DecisionModel.Outcome.INTENT, recorded.outcome());
            assertEquals(RoleTradingView.TOOL_TARGET_SET, recorded.recordedTool());
            assertEquals(recorded.barrierAtMillis() + ACTION,
                    DecisionModel.actionDueMillis(decisions(rt), "b-target"));

            PendingTask task = pendingContaining(rt, "b-target");
            assertEquals(PendingTask.ROLE_TRADING_TARGET_V1, task.kind(), "必须复用原任务种类");
            assertEquals(WorldScheduler.Phase.COMPLETION, task.phase());
            assertEquals(recorded.barrierAtMillis() + ACTION, task.dueTimeMillis());
            assertTrue(task.taskId().contains("b-target"), "任务标识由 actionId 稳定派生");
            assertFalse(task.taskId().contains("ATT-"), "任务标识不依赖尝试序号");
            assertTrue(task.payload().contains("\"personPartyId\":\"bob\""), "动作由决定绑定人物");
            assertTrue(rt.snapshot().usedTaskIds().contains(task.taskId()));
        }
    }

    // ------------------------------------------------------------------
    // 5. 屏障钳制
    // ------------------------------------------------------------------

    @Test
    @DisplayName("屏障前与同刻的规则/投递/完成照常执行，停在观察前且重复推进无空批")
    void barrierStopsBeforeObservationAndRunsEarlierPhases() throws IOException {
        try (WorldRuntime rt = open("barrier.journal")) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "a-open")));
            long barrier = decision(rt, "a-open").barrierAtMillis();
            assertEquals(THINK, barrier);

            rt.scheduleAppointment("ap-1", "ap-t", barrier, "A-6", ERIN, ORG_G,
                    Role.RESEARCHER, barrier, END);
            rt.scheduleTransfer("tr-1", "tr-t", barrier - 100, PRODUCT, ACC_FUND, ACC_INV,
                    new BigDecimal("10.00"), 100L);
            rt.openRoleSession(DAVE).submitChild("c-d", "t-d", barrier,
                    new WorldRoleSession.ChildDraft("tgt-x", 1, 1, "d-1", 10L, price("10.00")));

            rt.advanceTo(barrier);
            assertEquals(barrier, rt.snapshot().timeMillis(), "inclusive 等于屏障也必须停下");
            assertEquals(WorldScheduler.Phase.COMPLETION.name(), rt.snapshot().lastPhase(),
                    "屏障批不得伪装成观察");
            assertEquals(1, rt.snapshot().delivered().size(), "同刻投递必须执行");
            assertEquals(barrier, rt.snapshot().delivered().get(0).deliveredAtMillis());
            assertEquals(new BigDecimal("10.00"), rt.snapshot().delivered().get(0).amount());
            assertEquals(990_00L, cents(cash(rt, ACC_FUND)), "转账已在屏障前结算");
            assertEquals(1, rt.openRoleSession(ERIN).view().appointments().size(),
                    "屏障同刻的 RULES 任职必须生效");
            assertNotNull(report(rt, DAVE, "t-d"), "屏障同刻的 COMPLETION 必须执行");

            DecisionRun.Barrier pending = rt.pendingBarrier().orElseThrow();
            assertEquals(barrier, pending.timeMillis());
            assertEquals(List.of("a-open"), pending.actionIds());

            long position = rt.snapshot().position();
            rt.advanceTo(barrier + 5 * DAY);
            assertEquals(position, rt.snapshot().position(), "未决屏障反复推进不得追加空批");
            assertEquals(barrier, rt.snapshot().timeMillis(), "世界时间停在屏障");
        }
    }

    // ------------------------------------------------------------------
    // 6. 十个可变工具的原样映射
    // ------------------------------------------------------------------

    @Test
    @DisplayName("十个可变工具逐项映射原任务种类，观望不排业务任务")
    void allTenMutableToolsMapToTheOriginalKinds() throws IOException {
        record Case(String person, String tool, String args, String kind) {
        }
        List<Case> cases = List.of(
                new Case(ALICE, RoleTradingView.TOOL_READING_START,
                        "{\"docId\":\"src-1\",\"version\":1}", PendingTask.ROLE_TRADING_READ_V1),
                new Case(ALICE, RoleTradingView.TOOL_READING_CANCEL,
                        "{\"docId\":\"src-1\",\"version\":1,\"index\":1}",
                        PendingTask.ROLE_TRADING_UNREAD_V1),
                new Case(ALICE, RoleTradingView.TOOL_RESEARCH_SUBMIT,
                        "{\"researchId\":\"res-9\",\"organizationPartyId\":\"ORG-F\","
                                + "\"productOwnerId\":\"fund-a\",\"symbol\":\"CUFE1\","
                                + "\"docId\":\"note-9\",\"title\":\"t\",\"body\":\"b\","
                                + "\"citations\":[{\"docId\":\"src-1\",\"version\":1}]}",
                        PendingTask.ROLE_TRADING_RESEARCH_V1),
                new Case(BOB, RoleTradingView.TOOL_TARGET_SET, TARGET_SET_ARGS,
                        PendingTask.ROLE_TRADING_TARGET_V1),
                new Case(BOB, RoleTradingView.TOOL_TARGET_CONFIRM,
                        "{\"targetId\":\"tgt-1\",\"expectedVersion\":1,\"riskRevision\":1}",
                        PendingTask.ROLE_TRADING_CONFIRM_V1),
                new Case(BOB, RoleTradingView.TOOL_TARGET_CANCEL,
                        "{\"targetId\":\"tgt-1\",\"expectedVersion\":1}",
                        PendingTask.ROLE_TRADING_CANCEL_V1),
                new Case(CAROL, RoleTradingView.TOOL_TARGET_REVIEW,
                        "{\"targetId\":\"tgt-1\",\"expectedVersion\":1,\"approved\":true,"
                                + "\"maxTotalQuantity\":100,\"maxChildQuantity\":40,"
                                + "\"priceLimit\":\"10.00\",\"validUntilMillis\":900000,"
                                + "\"reason\":\"复核\"}",
                        PendingTask.ROLE_TRADING_REVIEW_V1),
                new Case(CAROL, RoleTradingView.TOOL_TARGET_HALT,
                        "{\"haltId\":\"halt-1\",\"targetId\":\"tgt-1\",\"expectedVersion\":1,"
                                + "\"untilMillis\":900000,\"reason\":\"暂停\"}",
                        PendingTask.ROLE_TRADING_HALT_V1),
                new Case(DAVE, RoleTradingView.TOOL_CHILD_SUBMIT,
                        "{\"parentId\":\"tgt-1\",\"parentVersion\":1,\"riskRevision\":1,"
                                + "\"childId\":\"c-9\",\"quantity\":10,\"limitPrice\":\"10.00\"}",
                        PendingTask.ROLE_TRADING_CHILD_V1),
                new Case(DAVE, RoleTradingView.TOOL_CHILD_CANCEL,
                        "{\"parentId\":\"tgt-1\",\"childId\":\"c-9\"}",
                        PendingTask.ROLE_TRADING_CHILD_CANCEL_V1));

        try (WorldRuntime rt = open("mapping.journal")) {
            rt.beginDecisionSession("open-1");
            int index = 0;
            for (Case item : cases) {
                index++;
                String actionId = "m-" + index;
                assertEquals("DECIDING:1", rt.openDecisions("d-" + index,
                        List.of(request(item.person(), actionId))));
                assertEquals("RECORDED", rt.acceptDecisionReply("r-" + index,
                        dispatchedReply(rt, actionId, item.tool(), item.args(),
                                usage(1, 60, 20))), item.tool());
                assertEquals(item.kind(), pendingContaining(rt, actionId).kind(),
                        "工具必须映射原任务种类：" + item.tool());
            }

            assertEquals("DECIDING:1", rt.openDecisions("d-wait", List.of(request(ALICE, "m-wait"))));
            assertEquals("RECORDED", rt.acceptDecisionReply("r-wait",
                    dispatchedReply(rt, "m-wait", DecisionModel.TOOL_WAIT, null, usage(1, 20, 5))));
            DecisionModel.Decision wait = decision(rt, "m-wait");
            assertEquals(DecisionModel.Outcome.WAIT, wait.outcome());
            assertTrue(wait.recordedTool().isEmpty(), "观望不记录业务工具");
            assertNull(pendingOrNull(rt, "m-wait"), "观望不得排业务任务");
        }
    }

    // ------------------------------------------------------------------
    // 7. 静态 DTO 失败 = 已知用量技术失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("静态工具参数失败按已知用量技术失败结算并沿用原观察重试，不写成观望")
    void staticDtoFailureIsTechnicalAndRetriesTheSameObservation() throws IOException {
        try (WorldRuntime rt = open("dto.journal")) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(BOB, "b-dto")));
            String body = "{\"tool\":\"role.target.set\",\"args\":{\"targetId\":\"tgt-1\","
                    + "\"expectedVersion\":0,\"organizationPartyId\":\"ORG-F\","
                    + "\"productOwnerId\":\"fund-a\",\"accountId\":\"ACC-FUND\","
                    + "\"symbol\":\"CUFE1\",\"absoluteTargetQuantity\":100,"
                    + "\"priceLimit\":\"10.00\",\"deadlineMillis\":900000,"
                    + "\"sourceResearchId\":\"res-1\",\"quantity\":3}}";
            DecisionModel.Usage declared = usage(1, 100, 40);
            long barrier = decision(rt, "b-dto").barrierAtMillis();
            DecisionModel.Reply receipt = dispatchedReply(rt, "b-dto", body, declared);

            String code = rt.acceptDecisionReply("r-1", receipt);
            assertTrue(code.startsWith("TECHNICAL_FAILED"), code);
            DecisionModel.Decision failed = decision(rt, "b-dto");
            assertEquals(DecisionModel.Status.OPEN, failed.status(), "技术失败不是观望，按原输入重试");
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, failed.outcome());
            assertEquals(2, failed.attempts().size());
            assertEquals(declared, failed.attempts().get(0).settled(),
                    "已知脚本用量必须按原声明结算，不能写成未知保守值");
            assertEquals(body, failed.attempts().get(0).replyBody(), "必须保留原回执正文");
            assertEquals("scripted:test-v1", failed.attempts().get(0).usageSource());
            assertTrue(failed.attempts().get(0).reason().startsWith("TOOL_ARGS_INVALID"),
                    failed.attempts().get(0).reason());
            assertEquals(failed.inputDigest(), failed.attempts().get(1).inputDigest(),
                    "重试沿用原观察");
            assertEquals(barrier, failed.barrierAtMillis(), "重试沿用原屏障");
            assertEquals(2, failed.attempts().get(1).attemptNo());
            assertNull(pendingOrNull(rt, "b-dto"), "静态失败不得排业务任务");

            long position = rt.snapshot().position();
            assertEquals("DUPLICATE", rt.acceptDecisionReply("r-2", receipt),
                    "同一回执不得二次结算");
            assertEquals(position, rt.snapshot().position());
        }
    }

    // ------------------------------------------------------------------
    // 8. 反序回调
    // ------------------------------------------------------------------

    @Test
    @DisplayName("两人反序回调得到相同钱股、相同下一轮输入与稳定动作标识")
    void reversedCallbacksProduceSameEconomyAndNextInput() throws IOException {
        Scenario forward = scenario("order-forward.journal", false);
        Scenario reversed = scenario("order-reversed.journal", true);

        assertEquals(forward.accounts(), reversed.accounts(), "现金账户必须一致");
        assertEquals(forward.securities(), reversed.securities(), "证券台账必须一致");
        assertEquals(forward.exchange(), reversed.exchange(), "订单与成交必须一致");
        assertEquals(forward.aliceInput(), reversed.aliceInput(), "下一轮输入必须一致");
        assertEquals(forward.bobInput(), reversed.bobInput(), "下一轮输入必须一致");
        assertEquals(forward.pendingTaskIds(), reversed.pendingTaskIds(),
                "动作任务标识不得依赖回调顺序");
        for (String taskId : forward.pendingTaskIds()) {
            assertTrue(taskId.contains("x-a") || taskId.contains("x-b"), taskId);
            assertFalse(taskId.contains("ATT-"), "标识不依赖尝试序号");
        }
    }

    private record Scenario(Map<String, CashLedger.CashAccount> accounts,
                            Optional<SecuritiesLedger.State> securities,
                            Optional<WorldExchange.State> exchange,
                            String aliceInput, String bobInput, Set<String> pendingTaskIds) {
    }

    private Scenario scenario(String journal, boolean reverseCallbacks) throws IOException {
        try (WorldRuntime rt = open(journal)) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "x-a"), request(BOB, "x-b")));
            DecisionModel.Reply alice = dispatchedReply(rt, "x-a",
                    RoleTradingView.TOOL_READING_START, "{\"docId\":\"src-1\",\"version\":1}",
                    usage(1, 80, 30));
            DecisionModel.Reply bob = dispatchedReply(rt, "x-b", DecisionModel.TOOL_WAIT, null,
                    usage(1, 40, 10));
            List<DecisionModel.Reply> order = reverseCallbacks
                    ? List.of(bob, alice) : List.of(alice, bob);
            for (DecisionModel.Reply reply : order) {
                assertEquals("QUEUED", rt.offerDecisionCallback(reply));
            }
            assertEquals(2, rt.drainDecisionCallbacks(), "两条候选都必须应用");
            assertEquals(DecisionModel.Status.RECORDED, decision(rt, "x-a").status());
            assertEquals(DecisionModel.Status.RECORDED, decision(rt, "x-b").status());

            rt.openDecisions("d-2", List.of(request(ALICE, "y-a"), request(BOB, "y-b")));
            Set<String> pending = new TreeSet<>();
            for (PendingTask task : rt.snapshot().pending()) {
                if (task.kind().equals(PendingTask.ROLE_TRADING_READ_V1)) {
                    pending.add(task.taskId());
                }
            }
            return new Scenario(rt.snapshot().accounts(), rt.snapshot().securities(),
                    rt.snapshot().exchange(), decision(rt, "y-a").inputText(),
                    decision(rt, "y-b").inputText(), pending);
        }
    }

    // ------------------------------------------------------------------
    // 9. 撤销任职后不能借新任职
    // ------------------------------------------------------------------

    @Test
    @DisplayName("决定打开时的任职被撤销并改任后，完成期按旧绑定拒绝")
    void revokedAppointmentCannotBeBorrowedFromANewOne() throws IOException {
        try (WorldRuntime rt = open("scope.journal")) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(BOB, "b-scope")));
            assertEquals("RECORDED", rt.acceptDecisionReply("r-1", dispatchedReply(rt, "b-scope",
                    RoleTradingView.TOOL_TARGET_SET, TARGET_SET_ARGS, usage(1, 100, 40))));

            rt.scheduleRevokeAppointment("rv-1", "rv-t", 490L, "A-2");
            rt.scheduleRevokeAppointment("rv-2", "rv-t2", 495L, "A-2G");
            rt.scheduleAppointment("ap-2", "ap-t", 500L, "A-9", BOB, ORG_G,
                    Role.PORTFOLIO_MANAGER, 500L, END);
            rt.advanceTo(500L);
            assertEquals(1, rt.openRoleSession(BOB).view().appointments().size(),
                    "改任后本人只剩新组织任职");

            rt.advanceTo(decision(rt, "b-scope").barrierAtMillis() + ACTION);
            RoleTradingRun.Report report = report(rt, BOB, "b-scope");
            assertFalse(report.accepted(), "旧绑定失效必须拒绝");
            assertEquals(RoleTradingModel.REASON_NOT_AUTHORIZED, report.reason());
            assertEquals(0, rt.snapshot().trading().orElseThrow().trading().targets().size(),
                    "不得在旧或新组织留下目标");
        }
    }

    // ------------------------------------------------------------------
    // 10. 队列容量与暂停候选保留
    // ------------------------------------------------------------------

    @Test
    @DisplayName("队列满明确返回 QUEUE_FULL；暂停中候选保留且不产生经济效果")
    void queueCapacityAndPauseRetainCandidates() throws IOException {
        try (WorldRuntime rt = open(tempDir.resolve("queue.journal"), config(1, 2, 60_000L, 2))) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "q-a"), request(BOB, "q-b")));
            DecisionModel.Reply alice = dispatchedReply(rt, "q-a", DecisionModel.TOOL_WAIT, null,
                    usage(1, 30, 10));
            DecisionModel.Reply bob = dispatchedReply(rt, "q-b", DecisionModel.TOOL_WAIT, null,
                    usage(1, 30, 10));
            assertEquals("QUEUED", rt.offerDecisionCallback(alice));
            assertEquals("QUEUE_FULL", rt.offerDecisionCallback(bob), "满队列必须明确返回");
            assertEquals(1, rt.queuedDecisionCallbacks());
            assertEquals(1, rt.drainDecisionCallbacks());
            assertEquals(0, rt.queuedDecisionCallbacks());
            assertEquals(DecisionModel.Status.RECORDED, decision(rt, "q-a").status());
            assertEquals(DecisionModel.Status.OPEN, decision(rt, "q-b").status());
        }

        Path paused = tempDir.resolve("paused.journal");
        WorldRuntime rt = open(paused);
        rt.beginDecisionSession("open-1");
        rt.openDecisions("d-1", List.of(request(ALICE, "p-a")));
        DecisionModel.Reply receipt = dispatchedReply(rt, "p-a", DecisionModel.TOOL_WAIT, null,
                usage(1, 30, 10));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> rt.scheduleTransfer("tr-p", "tr-pt", 900L,
                    PRODUCT, ACC_FUND, ACC_INV, new BigDecimal("1.00"), 0L));
        } finally {
            Thread.interrupted();
        }
        assertTrue(rt.isPaused(), "写失败后暂停");
        long position = rt.snapshot().position();
        assertEquals("QUEUED", rt.offerDecisionCallback(receipt), "暂停中只入队");
        assertEquals(0, rt.drainDecisionCallbacks(), "暂停中不得应用");
        assertEquals(1, rt.queuedDecisionCallbacks(), "候选必须保留而不是静默丢弃");
        assertEquals(position, rt.snapshot().position());
        assertEquals(DecisionModel.Status.OPEN, decision(rt, "p-a").status());
        quietClose(rt);
    }

    // ------------------------------------------------------------------
    // 11. 派发身份与并发上限
    // ------------------------------------------------------------------

    @Test
    @DisplayName("派发不写盘、重复派发返回同一身份，超过 maxInFlight 明确拒绝")
    void dispatchIsIdempotentAndBoundedByMaxInFlight() throws IOException {
        try (WorldRuntime rt = open(tempDir.resolve("dispatch.journal"),
                config(4, 1, 60_000L, 2))) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "i-a"), request(BOB, "i-b")));
            long position = rt.snapshot().position();

            DecisionRun.Call first = rt.dispatchDecision("i-a");
            assertEquals(position, rt.snapshot().position(), "派发不得写盘");
            assertEquals("i-a", first.actionId());
            assertEquals(decision(rt, "i-a").lastAttempt().attemptId(), first.attemptId());
            assertEquals(1, first.attemptNo());
            assertEquals(decision(rt, "i-a").inputText(), first.inputText(),
                    "派发返回冻结输入原文");
            assertEquals(first, rt.dispatchDecision("i-a"), "重复派发返回同一身份");
            assertThrows(IllegalStateException.class, () -> rt.dispatchDecision("i-b"),
                    "超过 maxInFlight 必须拒绝");

            assertEquals("QUEUED", rt.offerDecisionCallback(new DecisionModel.Reply(first.runId(),
                    first.actionId(), first.attemptId(), first.attemptNo(),
                    first.recoveryGeneration(),
                    "{\"tool\":\"role.wait\"}", usage(1, 20, 5), "scripted:test-v1")));
            assertEquals(1, rt.drainDecisionCallbacks());
            assertEquals("i-b", rt.dispatchDecision("i-b").actionId(), "名额释放后可继续派发");
        }
    }

    // ------------------------------------------------------------------
    // 12. 真实墙钟超时
    // ------------------------------------------------------------------

    @Test
    @DisplayName("墙钟超时按预留保守结算一次，不推进世界时间也不排业务任务")
    void wallClockTimeoutSettlesOnceWithoutMovingTheWorld() throws Exception {
        try (WorldRuntime rt = open(tempDir.resolve("timeout.journal"),
                config(4, 4, 5L, 2))) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "w-a")));
            DecisionModel.Usage reserved = usage(1, 120, 60);
            Map<String, CashLedger.CashAccount> accounts = rt.snapshot().accounts();
            rt.dispatchDecision("w-a");
            Thread.sleep(30L);

            assertEquals(1, rt.expireDecisionTimeouts(), "超时必须结算一次");
            assertEquals(0, rt.expireDecisionTimeouts(), "重复检查不得二次结算");
            DecisionModel.Decision expired = decision(rt, "w-a");
            assertEquals(DecisionModel.Status.OPEN, expired.status(), "仍按原观察有界重试");
            assertEquals(2, expired.attempts().size());
            assertEquals(reserved, expired.attempts().get(0).settled(), "未知用量按预留结算");
            assertEquals(0L, rt.snapshot().timeMillis(), "墙钟不得推进模拟时间");
            assertEquals(accounts, rt.snapshot().accounts(), "钱股不变");
            assertNull(pendingOrNull(rt, "w-a"), "超时不得补出业务动作");
        }
    }

    // ------------------------------------------------------------------
    // 13. 未知外壳 / 观望带参 = 技术失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未知工具与观望带参都是技术失败，不得变成观望")
    void unknownToolAndMalformedWaitAreTechnicalFailures() throws IOException {
        try (WorldRuntime rt = open("shell.journal")) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(BOB, "s-1")));
            String code = rt.acceptDecisionReply("r-1", dispatchedReply(rt, "s-1",
                    "role.target.review", "{}", usage(1, 30, 10)));
            assertTrue(code.startsWith("TECHNICAL_FAILED"), code);
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decision(rt, "s-1").outcome());
            assertNull(pendingOrNull(rt, "s-1"));

            rt.openDecisions("d-2", List.of(request(CAROL, "s-2")));
            String waitWithArgs = "{\"tool\":\"role.wait\",\"args\":{}}";
            DecisionModel.Reply receipt = dispatchedReply(rt, "s-2", waitWithArgs,
                    usage(1, 30, 10));
            assertTrue(rt.acceptDecisionReply("r-2", receipt).startsWith("TECHNICAL_FAILED"));
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decision(rt, "s-2").outcome());
            assertNull(pendingOrNull(rt, "s-2"));
        }
    }

    // ------------------------------------------------------------------
    // 14. 旧代次与重复回执
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重开后旧回执只归档不产生第二次效果，新代次回执生效且重复不再写盘")
    void reopenedGenerationArchivesTheOldReceipt() throws IOException {
        Path file = tempDir.resolve("generation.journal");
        DecisionModel.Reply oldReceipt;
        long positionAfterRecord;
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "g-a")));
            oldReceipt = dispatchedReply(rt, "g-a", DecisionModel.TOOL_WAIT, null,
                    usage(1, 30, 10));
        }
        try (WorldRuntime reopened = open(file)) {
            reopened.beginDecisionSession("open-2");
            DecisionModel.Decision resumed = decision(reopened, "g-a");
            assertEquals(2, resumed.attempts().size(), "新代次按原输入有界重试");
            assertEquals(2L, resumed.attempts().get(1).recoveryGeneration());
            assertEquals(1L, resumed.attempts().get(0).recoveryGeneration());

            long position = reopened.snapshot().position();
            DecisionModel.State beforeArchive = decisions(reopened);
            String code = reopened.acceptDecisionReply("r-old", oldReceipt);
            assertEquals("STALE_GENERATION", code, "旧回执只持久归档");
            assertEquals(position + 1, reopened.snapshot().position(), "归档追加一个审计批次");
            assertEquals(beforeArchive, decisions(reopened), "归档不得再次结算或产生决定");
            assertEquals("STALE_GENERATION", reopened.acceptDecisionReply("r-old", oldReceipt));
            assertEquals(position + 1, reopened.snapshot().position(), "相同归档重放不重复写盘");

            DecisionModel.Reply fresh = dispatchedReply(reopened, "g-a", DecisionModel.TOOL_WAIT,
                    null, usage(1, 25, 8));
            assertEquals("RECORDED", reopened.acceptDecisionReply("r-new", fresh));
            positionAfterRecord = reopened.snapshot().position();
            assertEquals("DUPLICATE", reopened.acceptDecisionReply("r-dup", fresh),
                    "同一回执不得二次写盘");
            assertEquals(positionAfterRecord, reopened.snapshot().position());
            assertEquals(2, reopened.snapshot().decisions().orElseThrow().state()
                    .decisions().get(0).attempts().size());
        }
    }

    // ------------------------------------------------------------------
    // 15. 真实线程中断 C1/C2/C3 与半帧
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C1 开批与 C2 落盘中断保留整帧并暂停，重开重发只结算一次")
    void interruptedOpenAndReplyKeepTheWholeSnapshot() throws IOException {
        Path journal = tempDir.resolve("c1c2.journal");
        WorldRuntime.Snapshot beforeOpen;
        WorldRuntime first = open(journal);
        try {
            first.beginDecisionSession("open-1");
            beforeOpen = first.snapshot();
            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class,
                        () -> first.openDecisions("d-1", List.of(request(ALICE, "c1-a"))),
                        "调用前中断必须抛 IOException");
            } finally {
                Thread.interrupted();
            }
            assertTrue(first.isPaused());
            assertEquals(beforeOpen, first.snapshot(), "整帧必须与中断前完全相同");
            assertEquals(0, first.snapshot().decisions().orElseThrow().state().decisions().size());
        } finally {
            quietClose(first);
        }

        WorldRuntime.Snapshot checkpoint;
        DecisionModel.Reply receipt;
        try (WorldRuntime rt = open(journal)) {
            assertFalse(rt.isPaused());
            assertEquals(beforeOpen.position(), rt.snapshot().position());
            rt.beginDecisionSession("open-2");
            assertEquals("DECIDING:1", rt.openDecisions("d-2", List.of(request(ALICE, "c1-a"))));
            receipt = dispatchedReply(rt, "c1-a", DecisionModel.TOOL_WAIT, null,
                    usage(1, 30, 10));
            checkpoint = rt.snapshot();
            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class,
                        () -> rt.acceptDecisionReply("r-1", receipt), "落盘中断必须抛 IOException");
            } finally {
                Thread.interrupted();
            }
            assertTrue(rt.isPaused());
            assertEquals(checkpoint, rt.snapshot(), "回复与动作都不得出现");
            assertEquals(DecisionModel.Status.OPEN, decision(rt, "c1-a").status());
            assertNull(pendingOrNull(rt, "c1-a"));
            quietClose(rt);
        }

        try (WorldRuntime again = open(journal)) {
            assertEquals(checkpoint.position(), again.snapshot().position(), "中断帧不得生效");
            again.beginDecisionSession("open-3");
            long afterBegin = again.snapshot().position();
            String code = again.acceptDecisionReply("r-2", receipt);
            assertEquals("STALE_GENERATION", code, "旧代次回执只做持久归档");
            assertEquals(afterBegin + 1, again.snapshot().position(), "归档恰好追加一个审计批次");
            assertEquals("STALE_GENERATION", again.acceptDecisionReply("r-3", receipt));
            assertEquals(afterBegin + 1, again.snapshot().position(), "相同归档重放不重复写盘");
            assertNull(pendingOrNull(again, "c1-a"), "旧回执不得产生业务动作");
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decision(again, "c1-a").outcome(),
                    "旧尝试已按预留保守结算，决定仍等新代次回执");
        }
    }

    @Test
    @DisplayName("C3 业务完成中断整帧不变；半帧副本回滚后重做只生效一次")
    void interruptedCompletionAndHalfFrameApplyExactlyOnce() throws IOException {
        Path journal = tempDir.resolve("c3.journal");
        WorldRuntime.Snapshot checkpoint;
        long due;
        WorldRuntime interrupted = open(journal);
        try {
            interrupted.schedulePublishDocument("p-1", "p-t", 500L, SRC_DOC, ALICE,
                    Audience.organization(ORG_F), "订单线索", "渠道反馈订单改善，金额待证实。",
                    "调研纪要", READ_MILLIS, List.of("internal-src"));
            interrupted.advanceTo(500L);
            interrupted.beginDecisionSession("open-1");
            interrupted.openDecisions("d-1", List.of(request(ALICE, "c3-a")));
            assertEquals("RECORDED", interrupted.acceptDecisionReply("r-1",
                    dispatchedReply(interrupted, "c3-a",
                            RoleTradingView.TOOL_READING_START,
                            "{\"docId\":\"src-1\",\"version\":1}", usage(1, 80, 30))));
            checkpoint = interrupted.snapshot();
            due = checkpoint.pending().stream()
                    .filter(task -> task.kind().equals(PendingTask.ROLE_TRADING_READ_V1))
                    .mapToLong(PendingTask::dueTimeMillis).findFirst().orElseThrow();

            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, () -> interrupted.advanceTo(due),
                        "业务完成中断必须抛 IOException");
            } finally {
                Thread.interrupted();
            }
            assertTrue(interrupted.isPaused());
            assertEquals(checkpoint, interrupted.snapshot(),
                    "订单/成交/链接/决定引用必须整体保留");
        } finally {
            quietClose(interrupted);
        }

        long fullPosition;
        try (WorldRuntime resumed = open(journal)) {
            resumed.beginDecisionSession("open-2");
            long afterBegin = resumed.snapshot().position();
            resumed.advanceTo(due);
            assertEquals(afterBegin + 1, resumed.snapshot().position(),
                    "重开推进只落一帧业务完成（begin 另有代次审计批次）");
            assertTrue(report(resumed, ALICE, "c3-a").accepted(), "阅读动作应被受理");
            assertNull(pendingOrNull(resumed, "c3-a"), "动作任务已被消费");
            assertNotNull(pendingOrNull(resumed, "read:"), "阅读完成子任务应排好");
            fullPosition = resumed.snapshot().position();
            assertEquals(1_000_00L, cents(cash(resumed, ACC_FUND)), "钱股必须守恒");
        }

        long fullSize = Files.size(journal);
        Path half = copy("c3-half.journal", journal);
        try (FileChannel channel = FileChannel.open(half, StandardOpenOption.WRITE)) {
            channel.truncate(fullSize - 4);
        }
        try (WorldRuntime rt = open(half)) {
            assertEquals(fullPosition - 1, rt.snapshot().position(), "半帧营业帧不得生效");
            assertNull(reportOrNull(rt, ALICE, "c3-a"), "半帧回滚后不得留回报");
            assertNotNull(pendingOrNull(rt, "c3-a"), "动作任务必须回到待办");
            rt.beginDecisionSession("open-4");
            long afterBegin = rt.snapshot().position();
            rt.advanceTo(due);
            assertTrue(report(rt, ALICE, "c3-a").accepted());
            assertEquals(afterBegin + 1, rt.snapshot().position(), "重做只落一帧业务完成");
            rt.advanceTo(due);
            assertEquals(afterBegin + 1, rt.snapshot().position(), "重复推进不得重复生效");
        }
    }

    // ------------------------------------------------------------------
    // 16. 未完成动作摘要进入下一轮输入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("本人已记录但尚未完成的动作摘要出现在下一次输入，且不泄露他人动作")
    void nextInputCarriesOwnUnfinishedAction() throws IOException {
        try (WorldRuntime rt = open("summary.journal")) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(BOB, "n-1")));
            assertEquals("RECORDED", rt.acceptDecisionReply("r-1", dispatchedReply(rt, "n-1",
                    RoleTradingView.TOOL_TARGET_SET, TARGET_SET_ARGS, usage(1, 100, 40))));

            assertEquals("DECIDING:2", rt.openDecisions("d-2",
                    List.of(request(BOB, "n-2"), request(CAROL, "n-3"))));
            String bobNext = decision(rt, "n-2").inputText();
            String carolNext = decision(rt, "n-3").inputText();
            assertTrue(bobNext.contains("n-1"), "本人未完成动作必须出现在下一轮输入");
            assertTrue(bobNext.contains(RoleTradingView.TOOL_TARGET_SET));
            assertFalse(carolNext.contains("n-1"), "不得泄露他人动作");
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var pending = json.readTree(bobNext).path("pendingActions").get(0);
            assertEquals(json.readTree(TARGET_SET_ARGS), pending.path("args"),
                    "本人尚未完成的承诺须携带原业务参数");
        }
    }

    // ------------------------------------------------------------------
    // 17. 批内拒绝与幂等
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同批重复人/动作与第二个人决定被拒；已完成重放先查幂等不重投影")
    void batchRejectsDuplicatesAndReplaysIdempotently() throws IOException {
        try (WorldRuntime rt = open("batch-guard.journal")) {
            rt.beginDecisionSession("open-1");
            long position = rt.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> rt.openDecisions("d-1",
                    List.of(request(ALICE, "z-1"), request(ALICE, "z-2"))),
                    "同一批不得给同一人两个决定");
            assertThrows(IllegalArgumentException.class, () -> rt.openDecisions("d-2",
                    List.of(request(ALICE, "z-3"), request(BOB, "z-3"))),
                    "同一批不得复用动作标识");
            assertEquals(position, rt.snapshot().position(), "整批拒绝不写盘");

            assertEquals("DECIDING:1", rt.openDecisions("d-3", List.of(request(ALICE, "z-4"))));
            assertThrows(IllegalArgumentException.class,
                    () -> rt.openDecisions("d-4", List.of(request(ALICE, "z-5"))),
                    "同一人物至多一个未解决决定");

            assertEquals("RECORDED", rt.acceptDecisionReply("r-1",
                    dispatchedReply(rt, "z-4", DecisionModel.TOOL_WAIT, null, usage(1, 20, 5))));
            long afterFirst = rt.snapshot().position();
            assertEquals("DECIDING:0", rt.openDecisions("d-5", List.of(request(ALICE, "z-4"))),
                    "已完成重放先查幂等，不新开决定");
            assertEquals(afterFirst, rt.snapshot().position(), "幂等重放不写盘");
            assertEquals(1, decisions(rt).decisions().size());
            assertThrows(IllegalArgumentException.class, () -> rt.openDecisions("d-6",
                    List.of(new DecisionRun.Request(ALICE, "z-4", "OTHER_WAKE"))),
                    "同动作换内容必须拒绝");
        }
    }

    // ------------------------------------------------------------------
    // 18. 真实交易竞争：两人反序回调同经济
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实订单竞争下两人反序回调得到相同订单、成交与钱股")
    void reversedCallbacksWithRealTradeKeepTheSameOrdersAndFills() throws IOException {
        TradeScene forward = tradeScene("trade-forward.journal", false);
        TradeScene reversed = tradeScene("trade-reverse.journal", true);

        assertEquals(forward.accounts(), reversed.accounts(), "现金账户必须一致");
        assertEquals(forward.securities(), reversed.securities(), "证券台账必须一致");
        assertEquals(forward.exchange(), reversed.exchange(), "订单与成交必须一致");
        assertEquals(2, forward.orderCount(), "外部卖单 + 决定子单各一张");
        assertEquals(1, forward.tradeCount(), "只应有唯一一次真实成交");
        assertEquals(20L, forward.filled(), "子单成交 20 股");
        assertEquals(800_00L, forward.fundCashCents(), "按实际成交价扣款");
        assertEquals(20L, forward.fundHeld(), "产品账户持有 20 股");
        assertTrue(forward.readingAccepted(), "阅读动作必须被受理");
        assertEquals(forward.nextAliceInput(), reversed.nextAliceInput(), "下一轮输入必须一致");
        assertEquals(forward.nextDaveInput(), reversed.nextDaveInput(), "下一轮输入必须一致");
    }

    private record TradeScene(Map<String, CashLedger.CashAccount> accounts,
                              Optional<SecuritiesLedger.State> securities,
                              Optional<WorldExchange.State> exchange,
                              int orderCount, int tradeCount, long filled,
                              long fundCashCents, long fundHeld, boolean readingAccepted,
                              String nextAliceInput, String nextDaveInput) {
    }

    private TradeScene tradeScene(String journal, boolean reverseCallbacks) throws IOException {
        try (WorldRuntime rt = open(journal)) {
            long base = 0L;
            rt.schedulePublishDocument("c-src", "t-src", base + 500L, SRC_DOC, ALICE,
                    Audience.organization(ORG_F), "订单线索", "渠道反馈订单改善，金额待证实。",
                    "调研纪要", READ_MILLIS, List.of("internal-src"));
            rt.advanceTo(base + 500L);
            rt.openRoleSession(ALICE).startReading("c-r1", "t-r1", base + 1_000L, SRC_DOC, 1L);
            rt.advanceTo(base + 1_000L);
            rt.advanceTo(base + 1_000L + READ_MILLIS);
            rt.openRoleSession(ALICE).submitResearch("c-res", "t-res", base + 62_000L,
                    new WorldRoleSession.ResearchDraft("res-1", ORG_F, PRODUCT, SYM,
                            "note-1", "S 公司订单线索", "渠道反馈订单改善，交付需持续核对。",
                            List.of(new WorldRoleSession.Citation(SRC_DOC, 1L))));
            rt.advanceTo(base + 62_000L);
            rt.openRoleSession(BOB).startReading("c-r2", "t-r2", base + 63_000L, "note-1", 1L);
            rt.advanceTo(base + 63_000L);
            rt.advanceTo(base + 63_000L + READ_MILLIS);
            rt.openRoleSession(BOB).setTarget("c-tgt", "t-tgt", base + 124_000L,
                    new WorldRoleSession.TargetDraft("tgt-1", 0, ORG_F, PRODUCT, ACC_FUND, SYM,
                            100L, price("10.00"), 3 * DAY, "res-1"));
            rt.advanceTo(base + 124_000L);
            rt.openRoleSession(CAROL).reviewTarget("c-rev", "t-rev", base + 125_000L,
                    new WorldRoleSession.ReviewDraft("tgt-1", 1, true, 100L, 40L, price("10.00"),
                            3 * DAY, "实验任务输入"));
            rt.advanceTo(base + 125_000L);
            rt.openRoleSession(BOB).confirmTarget("c-cfm", "t-cfm", base + 126_000L, "tgt-1",
                    1, 1);
            rt.advanceTo(base + 126_000L);

            rt.scheduleOrder("o-sell", "ot-sell", base + 126_500L, INV, ACC_INV, SYM, Side.SELL,
                    edu.cufe.auction.model.OrderType.LIMIT, price("10.00"), 20L);
            rt.advanceTo(base + 126_500L);

            rt.beginDecisionSession("open-1");
            assertEquals("DECIDING:2", rt.openDecisions("d-1", List.of(
                    request(DAVE, "tr-d"), request(ALICE, "tr-a"))));
            DecisionModel.Reply child = dispatchedReply(rt, "tr-d",
                    RoleTradingView.TOOL_CHILD_SUBMIT,
                    "{\"parentId\":\"tgt-1\",\"parentVersion\":1,\"riskRevision\":1,"
                            + "\"childId\":\"c-1\",\"quantity\":20,\"limitPrice\":\"10.00\"}",
                    usage(1, 100, 40));
            DecisionModel.Reply reading = dispatchedReply(rt, "tr-a",
                    RoleTradingView.TOOL_READING_START,
                    "{\"docId\":\"note-1\",\"version\":1}", usage(1, 60, 20));
            List<DecisionModel.Reply> order = reverseCallbacks
                    ? List.of(reading, child) : List.of(child, reading);
            for (DecisionModel.Reply reply : order) {
                assertEquals("QUEUED", rt.offerDecisionCallback(reply));
            }
            assertEquals(2, rt.drainDecisionCallbacks(), "两条候选都必须应用");
            rt.advanceTo(base + 129_500L);

            long filled = 0L;
            for (RoleTradingRun.Report report : rt.snapshot().trading().orElseThrow()
                    .reportsOf(DAVE)) {
                if (report.clientTaskId().equals("tr-d") && report.accepted()) {
                    filled = 20L;
                }
            }
            assertTrue(report(rt, ALICE, "tr-a").accepted(), "阅读动作应被受理");
            rt.openDecisions("d-2", List.of(request(ALICE, "n-a"), request(DAVE, "n-d")));
            return new TradeScene(rt.snapshot().accounts(), rt.snapshot().securities(),
                    rt.snapshot().exchange(), rt.snapshot().exchange().orElseThrow().orders().size(),
                    rt.snapshot().exchange().orElseThrow().trades().size(), filled,
                    cents(cash(rt, ACC_FUND)),
                    RoleTradingModel.held(rt.snapshot().securities().orElseThrow(), ACC_FUND, SYM),
                    report(rt, ALICE, "tr-a").accepted(), decision(rt, "n-a").inputText(),
                    decision(rt, "n-d").inputText());
        }
    }

    // ------------------------------------------------------------------
    // 19. 真实成交完成帧的线程中断与恢复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实下单成交帧中断保留整帧，重开只成交一次且钱股守恒")
    void interruptedRealFillAppliesExactlyOnce() throws IOException {
        Path journal = tempDir.resolve("fill-interrupt.journal");
        WorldRuntime.Snapshot checkpoint;
        long due;
        WorldRuntime first = open(journal);
        try {
            first.schedulePublishDocument("c-src", "t-src", 500L, SRC_DOC, ALICE,
                    Audience.organization(ORG_F), "订单线索", "渠道反馈订单改善，金额待证实。",
                    "调研纪要", READ_MILLIS, List.of("internal-src"));
            first.advanceTo(500L);
            first.openRoleSession(ALICE).startReading("c-r1", "t-r1", 1_000L, SRC_DOC, 1L);
            first.advanceTo(1_000L);
            first.advanceTo(1_000L + READ_MILLIS);
            first.openRoleSession(ALICE).submitResearch("c-res", "t-res", 62_000L,
                    new WorldRoleSession.ResearchDraft("res-1", ORG_F, PRODUCT, SYM,
                            "note-1", "S 公司订单线索", "渠道反馈订单改善，交付需持续核对。",
                            List.of(new WorldRoleSession.Citation(SRC_DOC, 1L))));
            first.advanceTo(62_000L);
            first.openRoleSession(BOB).startReading("c-r2", "t-r2", 63_000L, "note-1", 1L);
            first.advanceTo(63_000L);
            first.advanceTo(63_000L + READ_MILLIS);
            first.openRoleSession(BOB).setTarget("c-tgt", "t-tgt", 124_000L,
                    new WorldRoleSession.TargetDraft("tgt-1", 0, ORG_F, PRODUCT, ACC_FUND, SYM,
                            100L, price("10.00"), 3 * DAY, "res-1"));
            first.advanceTo(124_000L);
            first.openRoleSession(CAROL).reviewTarget("c-rev", "t-rev", 125_000L,
                    new WorldRoleSession.ReviewDraft("tgt-1", 1, true, 100L, 40L, price("10.00"),
                            3 * DAY, "实验任务输入"));
            first.advanceTo(125_000L);
            first.openRoleSession(BOB).confirmTarget("c-cfm", "t-cfm", 126_000L, "tgt-1", 1, 1);
            first.advanceTo(126_000L);
            first.scheduleOrder("o-sell", "ot-sell", 126_500L, INV, ACC_INV, SYM, Side.SELL,
                    edu.cufe.auction.model.OrderType.LIMIT, price("10.00"), 20L);
            first.advanceTo(126_500L);

            first.beginDecisionSession("open-1");
            first.openDecisions("d-1", List.of(request(DAVE, "f-d")));
            assertEquals("RECORDED", first.acceptDecisionReply("r-1", dispatchedReply(first,
                    "f-d", RoleTradingView.TOOL_CHILD_SUBMIT,
                    "{\"parentId\":\"tgt-1\",\"parentVersion\":1,\"riskRevision\":1,"
                            + "\"childId\":\"c-1\",\"quantity\":20,\"limitPrice\":\"10.00\"}",
                    usage(1, 100, 40))));
            checkpoint = first.snapshot();
            due = checkpoint.pending().stream()
                    .filter(task -> task.kind().equals(PendingTask.ROLE_TRADING_CHILD_V1))
                    .mapToLong(PendingTask::dueTimeMillis).findFirst().orElseThrow();

            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, () -> first.advanceTo(due),
                        "成交完成中断必须抛 IOException");
            } finally {
                Thread.interrupted();
            }
            assertTrue(first.isPaused());
            assertEquals(checkpoint, first.snapshot(), "订单、成交、链接与决定引用必须整体保留");
            assertEquals(1, first.snapshot().exchange().orElseThrow().orders().size(),
                    "只有外部卖单");
        } finally {
            quietClose(first);
        }

        try (WorldRuntime resumed = open(journal)) {
            resumed.beginDecisionSession("open-2");
            long afterBegin = resumed.snapshot().position();
            resumed.advanceTo(due);
            assertEquals(afterBegin + 1, resumed.snapshot().position(), "只落一帧成交完成");
            assertEquals(2, resumed.snapshot().exchange().orElseThrow().orders().size());
            assertEquals(1, resumed.snapshot().exchange().orElseThrow().trades().size());
            assertEquals(20L, RoleTradingModel.held(resumed.snapshot().securities().orElseThrow(),
                    ACC_FUND, SYM));
            assertEquals(800_00L, cents(cash(resumed, ACC_FUND)));
            assertTrue(report(resumed, DAVE, "f-d").accepted());
            long applied = resumed.snapshot().position();
            resumed.advanceTo(due);
            assertEquals(applied, resumed.snapshot().position(), "重复推进不重复成交");
            assertEquals(1, resumed.snapshot().exchange().orElseThrow().trades().size());
        }
        try (WorldRuntime again = open(journal)) {
            assertEquals(2, again.snapshot().exchange().orElseThrow().orders().size(),
                    "重启后订单不重复");
            assertEquals(1, again.snapshot().exchange().orElseThrow().trades().size(),
                    "重启后成交不重复");
        }
    }

    @Test
    void recordedActionsReplayBeforeAnotherOpenDecisionForTheSamePerson() throws IOException {
        try (WorldRuntime rt = open("replay-later-open.journal")) {
            rt.beginDecisionSession("session");
            List<DecisionRun.Request> original = List.of(request(ALICE, "old-action"));
            assertEquals("DECIDING:1", rt.openDecisions("original-host", original));
            rt.acceptDecisionReply("old-result", dispatchedReply(rt, "old-action",
                    DecisionModel.TOOL_WAIT, null, usage(1, 20, 5)));
            rt.advanceTo(100);
            rt.openDecisions("next-host", List.of(request(ALICE, "next-action")));
            long position = rt.snapshot().position();
            assertEquals("DECIDING:1", rt.openDecisions("original-host", original));
            assertEquals("DECIDING:0", rt.openDecisions("same-action-new-host", original));
            assertEquals(position, rt.snapshot().position());
        }
    }

    @Test
    void originalHostCommandCannotBeReusedForADifferentBatch() throws IOException {
        try (WorldRuntime rt = open("host-command-content.journal")) {
            rt.beginDecisionSession("session");
            rt.openDecisions("host", List.of(request(ALICE, "first")));
            rt.acceptDecisionReply("first-result", dispatchedReply(rt, "first",
                    DecisionModel.TOOL_WAIT, null, usage(1, 20, 5)));
            WorldRuntime.Snapshot before = rt.snapshot();
            assertThrows(IllegalArgumentException.class,
                    () -> rt.openDecisions("host", List.of(request(BOB, "different"))));
            assertEquals(before, rt.snapshot());
            assertFalse(rt.isPaused(), "完整命令冲突应在持久提交前拒绝");
        }
    }

    @Test
    void initialBudgetStopIsDurablyRecordedWithoutACall() throws IOException {
        DecisionModel.Config limited = withoutAliceBudget();
        try (WorldRuntime rt = open(tempDir.resolve("initial-budget-stop.journal"), limited)) {
            rt.beginDecisionSession("session");
            long position = rt.snapshot().position();
            assertEquals("DECIDING:0", rt.openDecisions("blocked", List.of(request(ALICE, "blocked"))));
            DecisionModel.Decision blocked = DecisionModel.decision(decisions(rt), "blocked").orElse(null);
            assertNotNull(blocked, "零预算也必须保存预算耗尽终态");
            assertEquals(DecisionModel.Outcome.BUDGET_EXHAUSTED, blocked.outcome());
            assertEquals(DecisionModel.Status.EXPIRED, blocked.status());
            assertTrue(blocked.attempts().isEmpty());
            assertEquals(usage(0, 0, 0), decisions(rt).runUsage());
            assertEquals(position + 1, rt.snapshot().position());
            assertTrue(rt.pendingBarrier().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onePersonsBudgetStopDoesNotDropOrBlockTheOtherRequest(boolean reverse) throws IOException {
        try (WorldRuntime rt = open(tempDir.resolve("mixed-budget-" + reverse + ".journal"),
                withoutAliceBudget())) {
            rt.beginDecisionSession("session");
            List<DecisionRun.Request> requests = reverse
                    ? List.of(request(BOB, "allowed"), request(ALICE, "blocked"))
                    : List.of(request(ALICE, "blocked"), request(BOB, "allowed"));
            String code = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> rt.openDecisions("batch", requests));
            assertEquals("DECIDING:1", code);
            assertNotNull(DecisionModel.decision(decisions(rt), "blocked").orElse(null));
            assertEquals(DecisionModel.Outcome.BUDGET_EXHAUSTED, decision(rt, "blocked").outcome());
            assertEquals(DecisionModel.Status.OPEN, decision(rt, "allowed").status());
            assertEquals(decision(rt, "blocked").cutoffPosition(), decision(rt, "allowed").cutoffPosition());
            assertFalse(rt.isPaused());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"read-zero-version", "read-empty-doc", "read-missing-doc", "read-null-doc", "cancel-zero-index",
            "cancel-missing-index", "target-missing-goal", "target-null-goal",
            "target-fractional-goal", "review-missing-choice", "review-null-choice", "forged-person"})
    void malformedToolArgumentsCannotBecomeBusinessIntents(String mutation) throws Exception {
        try (WorldRuntime rt = open("strict-" + mutation + ".journal")) {
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            String person = ALICE;
            String tool = RoleTradingView.TOOL_READING_START;
            var args = json.createObjectNode().put("docId", SRC_DOC).put("version", 1);
            switch (mutation) {
                case "read-zero-version" -> args.put("version", 0);
                case "read-empty-doc" -> args.put("docId", "");
                case "read-missing-doc" -> args.remove("docId");
                case "read-null-doc" -> args.putNull("docId");
                case "cancel-zero-index", "cancel-missing-index" -> {
                    tool = RoleTradingView.TOOL_READING_CANCEL;
                    if (mutation.equals("cancel-zero-index")) args.put("index", 0);
                }
                case "target-missing-goal", "target-null-goal", "target-fractional-goal" -> {
                    person = BOB;
                    tool = RoleTradingView.TOOL_TARGET_SET;
                    args = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(TARGET_SET_ARGS);
                    if (mutation.equals("target-missing-goal")) args.remove("absoluteTargetQuantity");
                    if (mutation.equals("target-null-goal")) args.putNull("absoluteTargetQuantity");
                    if (mutation.equals("target-fractional-goal")) args.put("absoluteTargetQuantity", 100.9);
                }
                case "review-missing-choice", "review-null-choice" -> {
                    person = CAROL;
                    tool = RoleTradingView.TOOL_TARGET_REVIEW;
                    args = json.createObjectNode().put("targetId", "tgt-1").put("expectedVersion", 1)
                            .put("maxTotalQuantity", 100).put("maxChildQuantity", 40)
                            .put("priceLimit", "10.00").put("validUntilMillis", 900000).put("reason", "检查");
                    if (mutation.equals("review-null-choice")) args.putNull("approved");
                }
                case "forged-person" -> {
                    person = DAVE;
                    tool = RoleTradingView.TOOL_CHILD_CANCEL;
                    args = json.createObjectNode().put("parentId", "tgt-1").put("childId", "child")
                            .put("personPartyId", ALICE);
                }
                default -> throw new AssertionError(mutation);
            }
            rt.beginDecisionSession("session");
            rt.openDecisions("observation", List.of(request(person, mutation)));
            String body = body(tool, args.toString());
            WorldRuntime.Snapshot before = rt.snapshot();
            String code = rt.acceptDecisionReply("result", dispatchedReply(rt, mutation, body, usage(1, 20, 5)));
            assertTrue(code.startsWith("TECHNICAL_FAILED:"), mutation + " " + code);
            DecisionModel.Attempt attempt = decision(rt, mutation).attempts().get(0);
            assertEquals(body, attempt.replyBody());
            assertEquals(usage(1, 20, 5), attempt.settled());
            assertEquals(before.accounts(), rt.snapshot().accounts());
            assertEquals(before.pending(), rt.snapshot().pending());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void separateCallbackDrainsRemainDurable(boolean intent) throws IOException {
        Path file = tempDir.resolve("two-drains-" + intent + ".journal");
        WorldRuntime.Snapshot expected;
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("session");
            for (String action : List.of("first", "second")) {
                rt.openDecisions("open-" + action, List.of(request(ALICE, action)));
                String tool = intent ? RoleTradingView.TOOL_READING_START : DecisionModel.TOOL_WAIT;
                String args = intent ? "{\"docId\":\"source\",\"version\":1}" : null;
                rt.offerDecisionCallback(dispatchedReply(rt, action, tool, args, usage(1, 20, 5)));
                assertEquals(1, org.junit.jupiter.api.Assertions.assertDoesNotThrow(rt::drainDecisionCallbacks));
                assertEquals(DecisionModel.Status.RECORDED, decision(rt, action).status());
            }
            rt.advanceTo(100);
            expected = rt.snapshot();
        }
        try (WorldRuntime restored = open(file)) {
            assertEquals(expected, restored.snapshot(), "每次发布均须已有对应持久帧");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void foreignRunCannotReplayOrArchiveAnOwnedAttempt(boolean settled) throws IOException {
        try (WorldRuntime rt = open("foreign-run-" + settled + ".journal")) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(request(ALICE, "action")));
            DecisionModel.Reply own = dispatchedReply(rt, "action", DecisionModel.TOOL_WAIT,
                    null, usage(1, 20, 5));
            if (settled) rt.acceptDecisionReply("owned", own);
            else rt.beginDecisionSession("next-generation");
            DecisionModel.Reply foreign = new DecisionModel.Reply("other-run", own.actionId(),
                    own.attemptId(), own.attemptNo(), own.recoveryGeneration(), own.body(),
                    own.usage(), own.usageSource());
            WorldRuntime.Snapshot before = rt.snapshot();
            assertEquals("REJECTED:RUN_MISMATCH", rt.acceptDecisionReply("foreign", foreign));
            assertEquals(before, rt.snapshot());
        }
    }

    @Test
    void replyHostMemoIncludesTheWholeReceipt() throws IOException {
        try (WorldRuntime rt = open("reply-host-memo.journal")) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(request(ALICE, "one"), request(BOB, "two")));
            DecisionModel.Reply first = dispatchedReply(rt, "one", DecisionModel.TOOL_WAIT,
                    null, usage(1, 20, 5));
            DecisionModel.Reply second = dispatchedReply(rt, "two", DecisionModel.TOOL_WAIT,
                    null, usage(1, 20, 5));
            assertEquals("RECORDED", rt.acceptDecisionReply("host", first));
            WorldRuntime.Snapshot before = rt.snapshot();
            assertEquals("RECORDED", rt.acceptDecisionReply("host", first));
            assertThrows(IllegalArgumentException.class, () -> rt.acceptDecisionReply("host", second));
            assertEquals(before, rt.snapshot());
            assertFalse(rt.isPaused());
        }
    }

    @Test
    void failuresAndRepliesShareTheConfiguredQueueCapacity() throws IOException {
        try (WorldRuntime rt = open(tempDir.resolve("queue-capacity.journal"), config(1, 2, 60000, 2))) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(request(ALICE, "one"), request(BOB, "two")));
            DecisionModel.Reply first = dispatchedReply(rt, "one", DecisionModel.TOOL_WAIT,
                    null, usage(1, 20, 5));
            DecisionRun.Call second = rt.dispatchDecision("two");
            assertEquals("QUEUED", rt.offerDecisionCallback(first));
            assertEquals("QUEUE_FULL", rt.offerDecisionFailure(new WorldRuntime.DecisionFailure(
                    second.actionId(), second.attemptNo(), second.attemptId(),
                    second.recoveryGeneration(), "SCRIPT_MISSING")));
            assertEquals(1, rt.queuedDecisionCallbacks());
        }
    }

    @Test
    void failedFailureCommitRetainsTheQueuedCandidate() throws IOException {
        WorldRuntime rt = open("failure-commit.journal");
        try {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(request(ALICE, "one")));
            DecisionRun.Call call = rt.dispatchDecision("one");
            rt.offerDecisionFailure(new WorldRuntime.DecisionFailure(call.actionId(), call.attemptNo(),
                    call.attemptId(), call.recoveryGeneration(), "SCRIPT_MISSING"));
            WorldRuntime.Snapshot before = rt.snapshot();
            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, rt::drainDecisionCallbacks);
            } finally {
                Thread.interrupted();
            }
            assertEquals(before, rt.snapshot());
            assertTrue(rt.isPaused());
            assertEquals(1, rt.queuedDecisionCallbacks(), "提交失败仍持有原候选");
        } finally {
            quietClose(rt);
        }
    }

    @Test
    void maximumWallTimeoutDoesNotOverflowIntoImmediateExpiry() throws IOException {
        try (WorldRuntime rt = open(tempDir.resolve("long-timeout.journal"),
                config(4, 2, Long.MAX_VALUE, 2))) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(request(ALICE, "one")));
            rt.dispatchDecision("one");
            WorldRuntime.Snapshot before = rt.snapshot();
            assertEquals(0, rt.expireDecisionTimeouts());
            assertEquals(before, rt.snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-pending", "ref-tool", "ref-due", "extra-binding",
            "fractional-attempt", "fractional-reservation"})
    void resignedHistoryMustStillLinkDecisionsToTheirActualBusinessTask(String mutation) throws Exception {
        Path source = tempDir.resolve("original-" + mutation + ".journal");
        WorldRuntime.Snapshot expected;
        try (WorldRuntime rt = open(source)) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(request(ALICE, "read")));
            assertEquals("RECORDED", rt.acceptDecisionReply("receipt", dispatchedReply(rt, "read",
                    RoleTradingView.TOOL_READING_START, "{\"docId\":\"source\",\"version\":1}", usage(1, 20, 5))));
            expected = rt.snapshot();
        }
        Path control = rewriteSnapshots("control-" + mutation + ".journal", source, snapshot -> { });
        try (WorldRuntime restored = open(control)) {
            assertEquals(expected, restored.snapshot(), "合法全历史重签对照必须先恢复");
        }
        int[] changed = {0};
        Path forged = rewriteSnapshots("forged-" + mutation + ".journal", source, snapshot -> {
            var run = snapshot.path("decisions");
            var ref = (com.fasterxml.jackson.databind.node.ObjectNode) run.path("actionRefs").get("read");
            if (ref == null) return;
            changed[0]++;
            switch (mutation) {
                case "missing-pending" -> {
                    var pending = (com.fasterxml.jackson.databind.node.ArrayNode) snapshot.path("pending");
                    for (int index = pending.size() - 1; index >= 0; index--) {
                        if (pending.get(index).path("taskId").equals(ref.path("taskId"))) pending.remove(index);
                    }
                }
                case "ref-tool" -> ref.put("tool", RoleTradingView.TOOL_READING_CANCEL);
                case "ref-due" -> ref.put("dueMillis", 9000);
                case "extra-binding" -> {
                    var binding = run.path("bindings").path("read").deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) binding).put("actionId", "ghost");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) run.path("bindings")).set("ghost", binding);
                }
                case "fractional-attempt" -> ((com.fasterxml.jackson.databind.node.ObjectNode)
                        run.path("state").path("decisions").get(0).path("attempts").get(0)).put("attemptNo", 1.9);
                case "fractional-reservation" -> ((com.fasterxml.jackson.databind.node.ObjectNode)
                        run.path("state").path("decisions").get(0).path("attempts").get(0)
                                .path("reserved")).put("inputTokens", 120.9);
                default -> throw new AssertionError(mutation);
            }
        });
        assertTrue(changed[0] > 0, "篡改必须触及真实决定帧");
        assertThrows(IOException.class, () -> {
            try (WorldRuntime ignored = open(forged)) { }
        }, mutation);
    }

    private Path rewriteSnapshots(String name, Path source,
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> change) throws Exception {
        var json = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .nodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true)).build();
        Path destination = tempDir.resolve(name);
        try (var input = new java.io.DataInputStream(Files.newInputStream(source));
             var output = new java.io.DataOutputStream(Files.newOutputStream(destination))) {
            while (input.available() > 0) {
                int length = input.readInt();
                assertEquals(~length, input.readInt());
                byte[] payload = input.readNBytes(length);
                assertEquals(32, input.readNBytes(32).length);
                var commit = json.readTree(payload);
                for (var event : commit.path("events")) {
                    if (event.path("type").asText().equals("STATE_SNAPSHOT_V1")) {
                        var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode)
                                json.readTree(event.path("payload").asText());
                        change.accept(snapshot);
                        ((com.fasterxml.jackson.databind.node.ObjectNode) event).put("payload", json.writeValueAsString(snapshot));
                    }
                }
                byte[] rewritten = json.writeValueAsBytes(commit);
                output.writeInt(rewritten.length);
                output.writeInt(~rewritten.length);
                output.write(rewritten);
                output.write(java.security.MessageDigest.getInstance("SHA-256").digest(rewritten));
            }
        }
        return destination;
    }

    @Test
    void observationCannotCrossAnUnresolvedBarrier() throws IOException {
        try (WorldRuntime rt = open("observation-barrier.journal")) {
            rt.beginDecisionSession("session");
            List<DecisionRun.Request> original = List.of(request(ALICE, "old"));
            rt.openDecisions("original", original);
            rt.advanceTo(THINK);
            WorldRuntime.Snapshot before = rt.snapshot();
            assertEquals("DECIDING:1", rt.openDecisions("original", original));
            assertThrows(IllegalStateException.class,
                    () -> rt.openDecisions("new-observation", List.of(request(BOB, "new"))));
            assertEquals(before, rt.snapshot());
            assertFalse(rt.isPaused());
        }
    }

    @Test
    void replayingAPriorSessionCommandAfterRestartDoesNotReopenTheDispatchGate() throws IOException {
        Path file = tempDir.resolve("session-replay.journal");
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("original-session");
            rt.openDecisions("observe", List.of(request(ALICE, "one")));
        }
        try (WorldRuntime restored = open(file)) {
            long position = restored.snapshot().position();
            assertEquals("SESSION_OPEN:1", restored.beginDecisionSession("original-session"));
            assertEquals(position, restored.snapshot().position());
            assertThrows(IllegalStateException.class, () -> restored.dispatchDecision("one"));
            assertEquals("SESSION_OPEN:2", restored.beginDecisionSession("new-session"));
            assertEquals(2, restored.dispatchDecision("one").recoveryGeneration());
        }
    }

    @Test
    void readingCancellationUsesThePersonalReferencePresentInTheInput() throws IOException {
        try (WorldRuntime rt = open("reading-cancel-input.journal")) {
            rt.schedulePublishDocument("source", "source-task", 100, SRC_DOC, ALICE,
                    Audience.publicAudience(), "资料", "正文", "公开来源", READ_MILLIS, List.of());
            rt.advanceTo(100);
            rt.openRoleSession(ALICE).startReading("read", "read-task", 200, SRC_DOC, 1);
            rt.advanceTo(200);
            RoleView.ReadingRef expected = rt.openRoleSession(ALICE).view().profile().readings().get(0).ref();
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(request(ALICE, "cancel")));
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var input = json.readTree(decision(rt, "cancel").inputText());
            var reading = input.path("profile").path("activeReadings").get(0);
            assertEquals(expected.index(), reading.path("index").asLong(), "输入必须提供可操作的本人阅读引用");
            String args = json.createObjectNode().put("docId", reading.path("docId").asText())
                    .put("version", reading.path("version").asLong())
                    .put("index", reading.path("index").asLong()).toString();
            assertEquals("RECORDED", rt.acceptDecisionReply("cancel", dispatchedReply(rt, "cancel",
                    RoleTradingView.TOOL_READING_CANCEL, args, usage(1, 20, 5))));
            rt.advanceTo(200 + THINK + ACTION);
            assertTrue(report(rt, ALICE, "cancel").accepted());
            assertTrue(rt.openRoleSession(ALICE).view().profile().readings().isEmpty());
        }
    }

    private static DecisionModel.Config withoutAliceBudget() {
        DecisionModel.Config normal = config(4, 2, 60000, 3);
        Map<String, DecisionModel.Usage> caps = new TreeMap<>(normal.budget().personCap());
        caps.put(ALICE, usage(0, 0, 0));
        return new DecisionModel.Config(normal.providerKind(), normal.providerId(), normal.pricingVersion(),
                normal.thinkMillisByWake(), normal.actionMillisByTool(), normal.limits(),
                new DecisionModel.Budget(normal.budget().runCap(), caps));
    }

    // ------------------------------------------------------------------
    // 18. M4e 决定墙钟审计（测试先行：缺 DecisionAudit / decisionAudits）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("审计：已知派发与回执记录完整身份、真实位置与两段等待")
    void auditRecordsKnownDispatchWaitForReply() throws Exception {
        try (WorldRuntime rt = open(tempDir.resolve("audit-reply.journal"))) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "au-a")));
            DecisionModel.Usage declared = usage(1, 120, 45);
            DecisionRun.Call call = rt.dispatchDecision("au-a");
            Thread.sleep(20L);

            String body = body(DecisionModel.TOOL_WAIT, null);
            DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                    call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body, declared,
                    "scripted:test-v1");
            long receiveLower = System.currentTimeMillis();
            assertEquals("QUEUED", rt.offerDecisionCallback(reply));
            long receiveUpper = System.currentTimeMillis();
            assertEquals(1, rt.drainDecisionCallbacks());
            long recordUpper = System.currentTimeMillis();

            List<WorldRuntime.DecisionAudit> audits = rt.decisionAudits();
            assertEquals(1, audits.size(), "一次回执恰一条审计");
            WorldRuntime.DecisionAudit audit = audits.get(0);
            assertEquals("REPLY", audit.kind());
            assertEquals("RECORDED", audit.outcome());
            assertEquals(RUN, audit.runId());
            assertEquals("au-a", audit.actionId());
            assertEquals(call.attemptId(), audit.attemptId());
            assertEquals(call.attemptNo(), audit.attemptNo());
            assertEquals(call.recoveryGeneration(), audit.recoveryGeneration());
            assertEquals(rt.snapshot().position(), audit.position(), "审计位置必须是真实提交位置");

            assertNotNull(audit.dispatchedAtEpochMillis(), "本进程派发必须有派发时点");
            assertNotNull(audit.receivedAtEpochMillis(), "回调必须带接收时点");
            assertTrue(audit.receivedAtEpochMillis() >= receiveLower);
            assertTrue(audit.receivedAtEpochMillis() <= receiveUpper + 1L,
                    "接收时点不得晚于入队观测上界");
            assertTrue(audit.recordedAtEpochMillis() >= audit.receivedAtEpochMillis(),
                    "记录时点不得早于接收时点");
            assertTrue(audit.recordedAtEpochMillis() <= recordUpper + 1L);

            assertNotNull(audit.dispatchToRecordMillis(), "已知派发必须给出等待量");
            assertTrue(audit.dispatchToRecordMillis() >= 15L,
                    "派发等待量必须覆盖真实 sleep 20ms：" + audit.dispatchToRecordMillis());
            assertTrue(audit.dispatchToRecordMillis()
                            <= recordUpper - audit.dispatchedAtEpochMillis() + 1L,
                    "等待量不得超过墙钟观测上界");
            assertNotNull(audit.receiveToRecordMillis());
            assertTrue(audit.receiveToRecordMillis() >= 0L);
            assertTrue(audit.receiveToRecordMillis() <= audit.dispatchToRecordMillis(),
                    "接收等待量不得大于派发等待量");

            assertEquals("", audit.body(), "正常回执只在快照保存正文");
            assertEquals(sha256Hex(body), audit.bodySha256(), "审计以摘要关联同批原尝试正文");
            assertEquals(declared, audit.declaredUsage());
            assertEquals("scripted:test-v1", audit.usageSource());
            assertEquals(declared, decision(rt, "au-a").lastAttempt().settled(),
                    "接受回执按真实用量结算，不按预留");
        }
    }

    @Test
    @DisplayName("审计：宿主直投没有派发标记，派发时点与派发等待明确缺失")
    void auditMarksUnknownDispatchWhenHostSubmitsDirectly() throws IOException {
        try (WorldRuntime rt = open("audit-direct.journal")) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "au-direct")));
            DecisionModel.Decision decision = decision(rt, "au-direct");
            DecisionModel.Attempt attempt = decision.lastAttempt();
            DecisionModel.Usage declared = usage(1, 40, 8);
            DecisionModel.Reply reply = new DecisionModel.Reply(decision.runId(), decision.actionId(),
                    attempt.attemptId(), attempt.attemptNo(), attempt.recoveryGeneration(),
                    body(DecisionModel.TOOL_WAIT, null), declared, "scripted:test-v1");
            long before = rt.snapshot().position();

            assertEquals("RECORDED", rt.acceptDecisionReply("host-direct-1", reply));
            assertEquals(before + 1, rt.snapshot().position(), "直投回执仍只加一帧");

            WorldRuntime.DecisionAudit audit = rt.decisionAudits().get(0);
            assertEquals("REPLY", audit.kind());
            assertEquals(rt.snapshot().position(), audit.position());
            assertNull(audit.dispatchedAtEpochMillis(), "未派发不得补造派发时点");
            assertNull(audit.dispatchToRecordMillis(), "未知派发不得补造等待量");
            assertNotNull(audit.receivedAtEpochMillis(), "直投以入口时刻为接收时点");
            assertNotNull(audit.receiveToRecordMillis());
            assertTrue(audit.recordedAtEpochMillis() >= audit.receivedAtEpochMillis());
        }
    }

    @Test
    @DisplayName("审计：候选在队列真实等待，接收时点取回调线程而非 writer 处理时刻")
    void auditKeepsArrivalTimeWhileCallbackWaitsInQueue() throws Exception {
        try (WorldRuntime rt = open(tempDir.resolve("audit-queue.journal"))) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "au-q")));
            DecisionRun.Call call = rt.dispatchDecision("au-q");
            DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                    call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                    body(DecisionModel.TOOL_WAIT, null), usage(1, 30, 10), "scripted:test-v1");

            long beforeOffer = System.nanoTime();
            assertEquals("QUEUED", rt.offerDecisionCallback(reply));
            long offeredAt = System.currentTimeMillis();
            Thread.sleep(60L);
            assertEquals(1, rt.drainDecisionCallbacks());
            long totalMillis = (System.nanoTime() - beforeOffer) / 1_000_000L;

            WorldRuntime.DecisionAudit audit = rt.decisionAudits().get(0);
            assertTrue(audit.receivedAtEpochMillis() <= offeredAt + 1L,
                    "接收时点必须是入队时刻，不能是 writer 处理时刻");
            assertTrue(audit.recordedAtEpochMillis() >= audit.receivedAtEpochMillis() + 40L,
                    "真实排队至少 40ms 必须体现在记录时点");
            assertNotNull(audit.receiveToRecordMillis());
            assertTrue(audit.receiveToRecordMillis() >= 40L,
                    "接收等待量必须覆盖真实排队时间：" + audit.receiveToRecordMillis());
            assertTrue(audit.receiveToRecordMillis() <= totalMillis);
            assertTrue(audit.dispatchToRecordMillis() >= audit.receiveToRecordMillis(),
                    "派发等待量不小于接收等待量");
        }
    }

    @Test
    @DisplayName("审计：重开只读原审计、不生成新墙钟，且不落进状态快照")
    void auditIsReadOnlyAcrossReopen() throws Exception {
        Path file = tempDir.resolve("audit-reopen.journal");
        List<WorldRuntime.DecisionAudit> before;
        long position;
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "au-r")));
            DecisionModel.Reply reply = dispatchedReply(rt, "au-r", DecisionModel.TOOL_WAIT, null,
                    usage(1, 25, 5));
            assertEquals("QUEUED", rt.offerDecisionCallback(reply));
            assertEquals(1, rt.drainDecisionCallbacks());
            before = rt.decisionAudits();
            position = rt.snapshot().position();
            assertEquals(1, before.size());
        }
        try (WorldRuntime reopened = open(file)) {
            assertEquals(before, reopened.decisionAudits(), "重开必须逐字段复现原审计");
            assertEquals(position, reopened.snapshot().position(), "读取审计不得写盘");
            reopened.beginDecisionSession("open-2");
            assertEquals(before, reopened.decisionAudits(), "接续不得生成新审计或补造时点");
        }
        try (WorldJournal reader = new WorldJournal(file, RUN)) {
            for (WorldCommit commit : reader.commits()) {
                for (WorldEvent event : commit.events()) {
                    assertFalse("STATE_SNAPSHOT_V1".equals(event.type())
                                    && event.payload().contains("DECISION_AUDIT"),
                            "审计不得进入状态快照：" + commit.position());
                }
            }
        }
    }

    @Test
    @DisplayName("审计：已结算相同回执 DUPLICATE 零写盘，换宿主命令也不重复")
    void duplicateReceiptKeepsAuditAndPositionUnchanged() throws IOException {
        try (WorldRuntime rt = open("audit-duplicate.journal")) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "au-dup")));
            DecisionModel.Reply reply = dispatchedReply(rt, "au-dup", DecisionModel.TOOL_WAIT, null,
                    usage(1, 20, 5));
            assertEquals("RECORDED", rt.acceptDecisionReply("r-1", reply));
            long position = rt.snapshot().position();
            int audits = rt.decisionAudits().size();
            assertEquals(1, audits);

            assertEquals("RECORDED", rt.acceptDecisionReply("r-1", reply));
            assertEquals(position, rt.snapshot().position(), "同一宿主命令重放须返回原结果且不得写盘");
            assertEquals(audits, rt.decisionAudits().size());

            assertEquals("DUPLICATE", rt.acceptDecisionReply("r-2", reply),
                    "换宿主命令的相同回执仍按已消费身份去重");
            assertEquals(position, rt.snapshot().position(), "换宿主命令也不得写盘");
            assertEquals(audits, rt.decisionAudits().size(), "不得新增审计");
        }
    }

    @Test
    @DisplayName("审计：已消费身份换正文/用量/来源各留一条，原文精确，同一条重投零写盘")
    void identityReuseRecordsEachDistinctReplyOnce() throws IOException {
        List<WorldRuntime.DecisionAudit> refused;
        long refusedPosition;
        try (WorldRuntime rt = open("audit-identity.journal")) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "au-id")));
            DecisionModel.Usage firstUsage = usage(1, 20, 5);
            DecisionModel.Reply accepted = dispatchedReply(rt, "au-id", DecisionModel.TOOL_WAIT,
                    null, firstUsage);
            assertEquals("RECORDED", rt.acceptDecisionReply("r-1", accepted));

            DecisionModel.State frozen = decisions(rt);
            DecisionModel.Attempt consumed = decision(rt, "au-id").lastAttempt();
            Map<String, CashLedger.CashAccount> accounts = rt.snapshot().accounts();
            List<PendingTask> pending = rt.snapshot().pending();
            long position = rt.snapshot().position();
            int audits = rt.decisionAudits().size();

            String otherBody = "{\"tool\":\"role.wait\",\"args\":{}}";
            DecisionModel.Reply changedBody = replyOf("au-id", consumed, otherBody, firstUsage,
                    "scripted:test-v1");
            assertEquals("REJECTED:IDENTITY_REUSE",
                    rt.acceptDecisionReply("rej-body", changedBody));
            assertEquals(position + 1, rt.snapshot().position(), "拒绝必须留下独立审计批次");
            assertEquals(audits + 1, rt.decisionAudits().size());
            WorldRuntime.DecisionAudit rejected = rt.decisionAudits().get(audits);
            assertEquals("REJECTED_IDENTITY_REUSE", rejected.kind());
            assertEquals("REJECTED:IDENTITY_REUSE", rejected.outcome());
            assertEquals(rt.snapshot().position(), rejected.position());
            assertEquals(RUN, rejected.runId());
            assertEquals("au-id", rejected.actionId());
            assertEquals(consumed.attemptId(), rejected.attemptId());
            assertEquals(consumed.attemptNo(), rejected.attemptNo());
            assertEquals(consumed.recoveryGeneration(), rejected.recoveryGeneration());
            assertEquals(otherBody, rejected.body(), "必须保留被拒绝回执的精确原文");
            assertEquals(sha256Hex(otherBody), rejected.bodySha256());
            assertEquals(firstUsage, rejected.declaredUsage(), "必须记录该回执的声明用量");
            assertEquals("scripted:test-v1", rejected.usageSource());
            assertNull(rejected.dispatchedAtEpochMillis(), "已结算身份没有在途派发");
            assertNull(rejected.dispatchToRecordMillis());
            assertNotNull(rejected.receivedAtEpochMillis());

            assertEquals(frozen, decisions(rt), "拒绝不得改变决定、尝试或预算");
            assertEquals(accounts, rt.snapshot().accounts(), "拒绝不得改变钱股");
            assertEquals(pending, rt.snapshot().pending(), "拒绝不得增删待办");

            assertEquals("REJECTED:IDENTITY_REUSE",
                    rt.acceptDecisionReply("rej-body-2", changedBody));
            assertEquals(position + 1, rt.snapshot().position(),
                    "同一非法回执换宿主命令重投不得重复落盘");
            assertEquals(audits + 1, rt.decisionAudits().size());

            DecisionModel.Usage secondUsage = usage(1, 21, 6);
            assertEquals("REJECTED:IDENTITY_REUSE", rt.acceptDecisionReply("rej-usage",
                    replyOf("au-id", consumed, otherBody, secondUsage, "scripted:test-v1")),
                    "同正文改用量也必须留一条独立记录");
            assertEquals(position + 2, rt.snapshot().position());
            assertEquals(audits + 2, rt.decisionAudits().size());
            assertEquals(secondUsage, rt.decisionAudits().get(audits + 1).declaredUsage());

            assertEquals("REJECTED:IDENTITY_REUSE", rt.acceptDecisionReply("rej-source",
                    replyOf("au-id", consumed, otherBody, secondUsage, "scripted:test-v2")),
                    "同正文同用量改来源也必须留一条独立记录");
            assertEquals(position + 3, rt.snapshot().position());
            assertEquals(audits + 3, rt.decisionAudits().size());
            assertEquals("scripted:test-v2", rt.decisionAudits().get(audits + 2).usageSource());

            assertEquals(frozen, decisions(rt), "全部拒绝后决定与预算仍逐字段不变");
            assertEquals(accounts, rt.snapshot().accounts());
            assertEquals(pending, rt.snapshot().pending());

            // 队列路径的拒绝：仍写审计批次，但 drain 不把它计为已应用决定。
            DecisionModel.Reply queued = replyOf("au-id", consumed, otherBody,
                    usage(1, 22, 7), "scripted:test-v1");
            assertEquals("QUEUED", rt.offerDecisionCallback(queued));
            long queuedBefore = rt.snapshot().position();
            int queuedAudits = rt.decisionAudits().size();
            assertEquals(0, rt.drainDecisionCallbacks(), "拒绝不得计为已应用决定");
            assertEquals(queuedBefore + 1, rt.snapshot().position(), "拒绝仍留下一个审计批次");
            assertEquals(queuedAudits + 1, rt.decisionAudits().size());
            assertEquals(0, rt.queuedDecisionCallbacks(), "候选已被处理并移除");
            refused = rt.decisionAudits();
            refusedPosition = rt.snapshot().position();
        }
        try (WorldRuntime reopened = open("audit-identity.journal")) {
            assertEquals(refusedPosition, reopened.snapshot().position(), "读取不得写盘");
            assertEquals(refused, reopened.decisionAudits(),
                    "含拒绝审计的档案必须按原尝试与拒绝原文关系恢复");
        }
    }

    @Test
    @DisplayName("审计：无回执的失败与超时不冒充收到回执")
    void failureAndTimeoutAuditHaveNoReceipt() throws Exception {
        try (WorldRuntime rt = open(tempDir.resolve("audit-failure.journal"))) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "au-f")));
            DecisionRun.Call call = rt.dispatchDecision("au-f");
            assertEquals("TECHNICAL_FAILED:SCRIPT_ERROR", rt.failDecisionAttempt("au-f",
                    call.attemptNo(), call.attemptId(), call.recoveryGeneration(), "SCRIPT_ERROR"));

            WorldRuntime.DecisionAudit audit = rt.decisionAudits().get(0);
            assertEquals("FAILURE", audit.kind());
            assertEquals("TECHNICAL_FAILED:SCRIPT_ERROR", audit.outcome());
            assertEquals(rt.snapshot().position(), audit.position());
            assertEquals(call.attemptId(), audit.attemptId());
            assertNull(audit.receivedAtEpochMillis(), "无回执不得补接收时点");
            assertNull(audit.receiveToRecordMillis());
            assertNotNull(audit.dispatchedAtEpochMillis());
            assertNotNull(audit.dispatchToRecordMillis());
            assertTrue(audit.dispatchToRecordMillis() >= 0L);
            assertEquals("", audit.body());
            assertNull(audit.declaredUsage(), "无回执时没有提供方声明用量");
        }
        try (WorldRuntime rt = open(tempDir.resolve("audit-timeout.journal"),
                config(4, 4, 5L, 2))) {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "au-t")));
            rt.dispatchDecision("au-t");
            Thread.sleep(30L);
            assertEquals(1, rt.expireDecisionTimeouts());

            WorldRuntime.DecisionAudit audit = rt.decisionAudits().get(0);
            assertEquals("TIMEOUT", audit.kind());
            assertEquals("TIMEOUT", audit.outcome());
            assertEquals(rt.snapshot().position(), audit.position());
            assertNull(audit.receivedAtEpochMillis(), "超时没有回执");
            assertNull(audit.receiveToRecordMillis());
            assertNotNull(audit.dispatchedAtEpochMillis());
            assertTrue(audit.dispatchToRecordMillis() >= 20L,
                    "等待量必须覆盖真实超时等待：" + audit.dispatchToRecordMillis());
        }
    }

    @Test
    @DisplayName("审计：真实写盘失败时旧快照、待处理候选与已确认审计都不变")
    void ioFailureKeepsSnapshotQueueAndAudits() throws IOException {
        Path file = tempDir.resolve("audit-io.journal");
        long position;
        WorldRuntime.Snapshot checkpoint;
        List<WorldRuntime.DecisionAudit> before;
        WorldRuntime rt = open(file);
        try {
            rt.beginDecisionSession("open-1");
            rt.openDecisions("d-1", List.of(request(ALICE, "au-io")));
            DecisionModel.Reply reply = dispatchedReply(rt, "au-io", DecisionModel.TOOL_WAIT, null,
                    usage(1, 25, 5));
            assertEquals("QUEUED", rt.offerDecisionCallback(reply));
            checkpoint = rt.snapshot();
            position = checkpoint.position();
            before = rt.decisionAudits();

            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, rt::drainDecisionCallbacks,
                        "真实 I/O 失败必须抛 IOException");
            } finally {
                Thread.interrupted();
            }
            assertTrue(rt.isPaused(), "写失败必须暂停");
            assertEquals(checkpoint, rt.snapshot(), "旧快照必须完整保留");
            assertEquals(position, rt.snapshot().position(), "失败批次不得生效");
            assertEquals(1, rt.queuedDecisionCallbacks(), "候选必须保留而不是静默丢弃");
            assertEquals(before, rt.decisionAudits(), "失败不得产生已确认审计");
        } finally {
            quietClose(rt);
        }
        try (WorldRuntime reopened = open(file)) {
            assertEquals(position, reopened.snapshot().position(), "中断帧不得生效");
            assertEquals(before, reopened.decisionAudits(), "重开只读原历史，不补造审计");
            assertEquals(0, reopened.queuedDecisionCallbacks(), "新进程队列为空");
        }
    }

    @Test
    void committedRejectionBindsTheOriginalHostCommand() throws IOException {
        try (WorldRuntime rt = open("rejected-host.journal")) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(request(ALICE, "one")));
            var original = dispatchedReply(rt, "one", DecisionModel.TOOL_WAIT, null, usage(1, 20, 5));
            rt.acceptDecisionReply("accepted", original);
            var rejected = new DecisionModel.Reply(original.runId(), original.actionId(), original.attemptId(),
                    original.attemptNo(), original.recoveryGeneration(), original.body(), usage(1, 21, 5), original.usageSource());
            assertEquals("REJECTED:IDENTITY_REUSE", rt.acceptDecisionReply("rejected-host", rejected));
            WorldRuntime.Snapshot before = rt.snapshot();
            var different = new DecisionModel.Reply(rejected.runId(), rejected.actionId(), rejected.attemptId(),
                    rejected.attemptNo(), rejected.recoveryGeneration(), rejected.body(), rejected.usage(), "different-source");
            assertThrows(IllegalArgumentException.class, () -> rt.acceptDecisionReply("rejected-host", different));
            assertEquals(before, rt.snapshot());
            assertFalse(rt.isPaused());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"usage", "source", "body", "run", "outcome", "negative-wait",
            "stale-usage", "stale-source"})
    void resignedRejectionAuditMustMatchTheWholeCommittedReceipt(String mutation) throws Exception {
        Path file = tempDir.resolve("rejection-source-" + mutation + ".journal");
        List<WorldRuntime.DecisionAudit> expected;
        WorldRuntime.Snapshot snapshot;
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(request(ALICE, "one")));
            var original = dispatchedReply(rt, "one", DecisionModel.TOOL_WAIT, null, usage(1, 20, 5));
            if (mutation.startsWith("stale-")) {
                rt.beginDecisionSession("second-session");
                rt.acceptDecisionReply("stale", original);
            } else {
                rt.acceptDecisionReply("accepted", original);
                var rejected = new DecisionModel.Reply(original.runId(), original.actionId(), original.attemptId(),
                        original.attemptNo(), original.recoveryGeneration(), "{\"tool\":\"role.wait\",\"args\":{}}",
                        usage(1, 21, 5), "scripted:rejected");
                rt.acceptDecisionReply("rejected", rejected);
            }
            expected = rt.decisionAudits();
            snapshot = rt.snapshot();
        }
        Path control = rewriteAuditEvents("rejection-control-" + mutation + ".journal", file, audit -> { });
        try (WorldRuntime rt = open(control)) {
            assertEquals(snapshot, rt.snapshot(), "合法重签对照须先恢复完整快照");
            assertEquals(expected, rt.decisionAudits());
        }
        int[] changed = {0};
        Path forged = rewriteAuditEvents("rejection-forged-" + mutation + ".journal", file, audit -> {
            boolean target = mutation.startsWith("stale-")
                    ? audit.path("outcome").asText().equals("STALE_GENERATION")
                    : audit.path("kind").asText().equals("REJECTED_IDENTITY_REUSE");
            if (!target) return;
            changed[0]++;
            switch (mutation) {
                case "usage", "stale-usage" -> ((com.fasterxml.jackson.databind.node.ObjectNode) audit.path("declaredUsage"))
                        .put("inputTokens", 22);
                case "source", "stale-source" -> audit.put("usageSource", "forged-source");
                case "run" -> audit.put("runId", "other-run");
                case "outcome" -> audit.put("outcome", "REJECTED:OTHER_REASON");
                case "negative-wait" -> audit.put("receiveToRecordMillis", -1);
                case "body" -> {
                    String body = "{\"tool\":\"role.wait\",\"args\":{\"forged\":true}}";
                    audit.put("body", body).put("bodySha256", sha256Hex(body));
                }
                default -> throw new AssertionError(mutation);
            }
        });
        assertTrue(changed[0] > 0);
        assertThrows(IOException.class, () -> {
            try (WorldRuntime ignored = open(forged)) { }
        }, mutation);
    }

    @Test
    void pastSettledAttemptCannotAcquireAnotherReplyAuditInALaterBatch() throws Exception {
        Path source = tempDir.resolve("audit-causality-source.journal");
        try (WorldRuntime rt = open(source)) {
            rt.beginDecisionSession("session");
            rt.openDecisions("observe", List.of(request(ALICE, "one")));
            rt.acceptDecisionReply("original", dispatchedReply(rt, "one", DecisionModel.TOOL_WAIT,
                    null, usage(1, 20, 5)));
            rt.advanceTo(1);
        }
        Path control = rewriteAuditEvents("audit-causality-control.journal", source, audit -> { });
        try (WorldRuntime rt = open(control)) {
            assertEquals(1, rt.decisionAudits().size(), "合法重签对照只有原回执审计");
        }
        List<WorldCommit> commits;
        try (WorldJournal reader = new WorldJournal(source, RUN)) {
            commits = reader.commits();
        }
        WorldCommit original = commits.stream()
                .filter(commit -> commit.events().stream().anyMatch(event -> event.type().equals("DECISION_AUDIT_V1")))
                .findFirst().orElseThrow();
        String auditJson = original.events().stream().filter(event -> event.type().equals("DECISION_AUDIT_V1"))
                .findFirst().orElseThrow().payload();
        var json = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .nodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true)).build();
        Path forged = tempDir.resolve("audit-causality-forged.journal");
        try (WorldJournal writer = new WorldJournal(forged, RUN)) {
            for (WorldCommit commit : commits) {
                if (commit.position() != commits.size()) {
                    writer.commit(commit.commandId(), commit.commandContent(), commit.result(), commit.events());
                    continue;
                }
                var audit = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(auditJson);
                audit.put("position", commit.position());
                List<WorldEvent> events = new ArrayList<>(commit.events());
                events.add(new WorldEvent("dec-audit:" + audit.path("attemptId").asText() + ":" + commit.position(),
                        "DECISION_AUDIT_V1", json.writeValueAsString(audit)));
                writer.commit("forged-later-reply", original.commandContent(), "RECORDED", events);
            }
        }
        assertThrows(IOException.class, () -> {
            try (WorldRuntime ignored = open(forged)) { }
        }, "旧尝试不能在后续批次再次冒充首次回执");
    }

    @ParameterizedTest
    @ValueSource(strings = {"body-direct", "usage-direct", "source-direct", "body-queue", "usage-queue", "source-queue"})
    void conflictingArchivedReplyIsAuditedWithoutBlockingTheCurrentAttempt(String variant) throws Exception {
        Path file = tempDir.resolve("stale-conflict-" + variant + ".journal");
        List<WorldRuntime.DecisionAudit> expected;
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("first-session");
            rt.openDecisions("observe", List.of(request(ALICE, "one")));
            DecisionModel.Reply old = dispatchedReply(rt, "one", DecisionModel.TOOL_WAIT, null, usage(1, 20, 5));
            rt.beginDecisionSession("next-session");
            assertEquals("STALE_GENERATION", rt.acceptDecisionReply("first-old", old));
            WorldRuntime.Snapshot before = rt.snapshot();
            var conflict = new DecisionModel.Reply(old.runId(), old.actionId(), old.attemptId(), old.attemptNo(),
                    old.recoveryGeneration(), variant.startsWith("body") ? "{\"tool\":\"role.wait\",\"args\":{}}" : old.body(),
                    variant.startsWith("usage") ? usage(1, 21, 5) : old.usage(),
                    variant.startsWith("source") ? "scripted:other" : old.usageSource());
            DecisionModel.Reply current = dispatchedReply(rt, "one", DecisionModel.TOOL_WAIT, null, usage(1, 22, 6));
            if (variant.endsWith("queue")) {
                rt.offerDecisionCallback(conflict);
                rt.offerDecisionCallback(current);
                assertEquals(1, org.junit.jupiter.api.Assertions.assertDoesNotThrow(rt::drainDecisionCallbacks),
                        "拒绝旧冲突后当前回执仍须能够记录");
                assertEquals(0, rt.queuedDecisionCallbacks());
            } else {
                assertEquals("REJECTED:IDENTITY_REUSE", org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                        () -> rt.acceptDecisionReply("conflict", conflict)));
                assertEquals(before.decisions(), rt.snapshot().decisions(), "旧冲突不改变决定或预算");
                assertEquals(before.position() + 1, rt.snapshot().position());
                assertEquals("RECORDED", rt.acceptDecisionReply("current", current));
            }
            assertEquals(before.accounts(), rt.snapshot().accounts());
            assertEquals(before.pending(), rt.snapshot().pending());
            assertEquals(before.position() + 2, rt.snapshot().position());
            assertEquals(1, rt.snapshot().decisions().orElseThrow().archives().size());
            assertEquals(DecisionModel.Status.RECORDED, decision(rt, "one").status());
            assertEquals(3, rt.decisionAudits().size());
            WorldRuntime.DecisionAudit rejected = rt.decisionAudits().get(1);
            assertEquals("REJECTED_IDENTITY_REUSE", rejected.kind());
            assertEquals(conflict.body(), rejected.body());
            assertEquals(conflict.usage(), rejected.declaredUsage());
            assertEquals(conflict.usageSource(), rejected.usageSource());
            long position = rt.snapshot().position();
            assertEquals("REJECTED:IDENTITY_REUSE", rt.acceptDecisionReply("conflict-again", conflict));
            assertEquals(position, rt.snapshot().position(), "相同旧冲突也按完整回执去重");
            assertFalse(rt.isPaused());
            expected = rt.decisionAudits();
        }
        try (WorldRuntime rt = open(file)) {
            assertEquals(expected, rt.decisionAudits());
        }
    }

    private Path rewriteAuditEvents(String name, Path source,
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> change) throws IOException {
        var json = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .nodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true)).build();
        List<WorldCommit> commits;
        try (WorldJournal reader = new WorldJournal(source, RUN)) {
            commits = reader.commits();
        }
        Path destination = tempDir.resolve(name);
        try (WorldJournal writer = new WorldJournal(destination, RUN)) {
            for (WorldCommit commit : commits) {
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    if (event.type().equals("DECISION_AUDIT_V1")) {
                        var audit = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(event.payload());
                        change.accept(audit);
                        event = new WorldEvent(event.eventId(), event.type(), json.writeValueAsString(audit));
                    }
                    events.add(event);
                }
                writer.commit(commit.commandId(), commit.commandContent(), commit.result(), events);
            }
        }
        return destination;
    }

    private static DecisionModel.Reply replyOf(String actionId, DecisionModel.Attempt attempt,
                                               String body, DecisionModel.Usage usage,
                                               String source) {
        return new DecisionModel.Reply(RUN, actionId, attempt.attemptId(), attempt.attemptNo(),
                attempt.recoveryGeneration(), body, usage, source);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                out.append(Character.forDigit((value >> 4) & 0xF, 16));
                out.append(Character.forDigit(value & 0xF, 16));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少 SHA-256", impossible);
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private WorldRuntime open(String name) throws IOException {
        return open(tempDir.resolve(name), config(4, 2, 60_000L, 2));
    }

    private WorldRuntime open(Path file) throws IOException {
        return open(file, config(4, 2, 60_000L, 2));
    }

    private WorldRuntime open(Path file, DecisionModel.Config config) throws IOException {
        return new WorldRuntime(file, manifest(), OPENINGS, registry(), MFG, charter(), roles(),
                info(), config);
    }

    private WorldRuntime openLegacy(Path file) throws IOException {
        return new WorldRuntime(file, legacyManifest(), OPENINGS, registry(), MFG, charter(),
                roles(), info());
    }

    private static void quietClose(WorldRuntime rt) {
        try {
            rt.close();
        } catch (RuntimeException ignored) {
            // 中断已关闭通道，不影响证据
        }
    }

    private static DecisionRun.Request request(String person, String actionId) {
        return new DecisionRun.Request(person, actionId, WAKE);
    }

    private static DecisionModel.State decisions(WorldRuntime rt) {
        return rt.snapshot().decisions().orElseThrow().state();
    }

    private static DecisionModel.Decision decision(WorldRuntime rt, String actionId) {
        return DecisionModel.decision(decisions(rt), actionId).orElseThrow();
    }

    private static DecisionModel.Reply dispatchedReply(WorldRuntime rt, String actionId,
                                                       String tool, String args,
                                                       DecisionModel.Usage usage) throws IOException {
        DecisionRun.Call call = rt.dispatchDecision(actionId);
        return new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                call.attemptNo(), call.recoveryGeneration(), body(tool, args), usage,
                "scripted:test-v1");
    }

    private static DecisionModel.Reply dispatchedReply(WorldRuntime rt, String actionId,
                                                       String body,
                                                       DecisionModel.Usage usage) throws IOException {
        DecisionRun.Call call = rt.dispatchDecision(actionId);
        return new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                call.attemptNo(), call.recoveryGeneration(), body, usage, "scripted:test-v1");
    }

    private static String body(String tool, String args) {
        if (args == null) {
            return "{\"tool\":\"" + tool + "\"}";
        }
        return "{\"tool\":\"" + tool + "\",\"args\":" + args + "}";
    }

    private static PendingTask pendingContaining(WorldRuntime rt, String fragment) {
        PendingTask found = pendingOrNull(rt, fragment);
        assertNotNull(found, "缺少任务：" + fragment);
        return found;
    }

    private static PendingTask pendingOrNull(WorldRuntime rt, String fragment) {
        for (PendingTask task : rt.snapshot().pending()) {
            if (task.taskId().contains(fragment)) {
                return task;
            }
        }
        return null;
    }

    private static RoleTradingRun.Report report(WorldRuntime rt, String person,
                                                String clientTaskId) {
        RoleTradingRun.Report found = reportOrNull(rt, person, clientTaskId);
        assertNotNull(found, "缺少回报：" + person + "/" + clientTaskId);
        return found;
    }

    private static RoleTradingRun.Report reportOrNull(WorldRuntime rt, String person,
                                                      String clientTaskId) {
        for (RoleTradingRun.Report report : rt.snapshot().trading().orElseThrow()
                .reportsOf(person)) {
            if (report.clientTaskId().equals(clientTaskId)) {
                return report;
            }
        }
        return null;
    }

    private static BigDecimal cash(WorldRuntime rt, String accountId) {
        return rt.snapshot().accounts().get(accountId).cash();
    }

    private static long cents(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }

    private Path copy(String name, Path source) throws IOException {
        Path target = tempDir.resolve(name);
        Files.copy(source, target);
        return target;
    }

    private String snapshotJson(Path journal) throws IOException {
        try (WorldJournal reader = new WorldJournal(journal, RUN)) {
            for (WorldCommit commit : reader.commits()) {
                for (WorldEvent event : commit.events()) {
                    if ("STATE_SNAPSHOT_V1".equals(event.type())) {
                        return event.payload();
                    }
                }
            }
        }
        throw new AssertionError("日志中没有状态快照");
    }

    private static DecisionModel.Usage usage(long calls, long inputTokens, long outputTokens) {
        return new DecisionModel.Usage(calls, inputTokens, outputTokens, new BigDecimal("0.00"));
    }

    private static DecisionModel.Config config(int queueCapacity, int maxInFlight,
                                               long wallTimeoutMillis, int maxAttempts) {
        Map<String, Long> action = new TreeMap<>();
        for (String tool : List.of(RoleTradingView.TOOL_READING_START,
                RoleTradingView.TOOL_READING_CANCEL, RoleTradingView.TOOL_RESEARCH_SUBMIT,
                RoleTradingView.TOOL_TARGET_SET, RoleTradingView.TOOL_TARGET_REVIEW,
                RoleTradingView.TOOL_TARGET_HALT, RoleTradingView.TOOL_TARGET_CONFIRM,
                RoleTradingView.TOOL_TARGET_CANCEL, RoleTradingView.TOOL_CHILD_SUBMIT,
                RoleTradingView.TOOL_CHILD_CANCEL)) {
            action.put(tool, ACTION);
        }
        Map<String, DecisionModel.Usage> caps = new TreeMap<>();
        for (String person : List.of(ALICE, BOB, CAROL, DAVE, ERIN)) {
            caps.put(person, usage(20, 2_400, 1_200));
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted-test-v1",
                "scripted-zero-v1", Map.of(WAKE, THINK), action,
                new DecisionModel.Limits(maxAttempts, 20_000, 4_000, 120, 60, queueCapacity,
                        maxInFlight, wallTimeoutMillis),
                new DecisionModel.Budget(usage(100, 12_000, 6_000), caps));
    }

    private static Map<String, Integer> modules() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading")) {
            modules.put(name, 1);
        }
        return modules;
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = modules();
        modules.put("decisions", 1);
        return new RunManifest(RUN, SEED, RunManifest.RULES_DECISION_EXPERIMENT_V1, modules,
                Map.of("fixture", "decision-runtime-v1"), RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private static RunManifest legacyManifest() {
        return new RunManifest(RUN, SEED, RunManifest.RULES_ROLE_TRADING_EXPERIMENT_V1, modules(),
                Map.of("fixture", "role-trading-runtime-v1"), RunManifest.MODEL_NONE);
    }

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

    private static RoleRun.Config roles() {
        List<RoleAccess.ProductMandate> mandates = List.of(
                new RoleAccess.ProductMandate(ORG_F, PRODUCT, List.of(ACC_FUND)),
                new RoleAccess.ProductMandate(ORG_G, PRODUCT_G, List.of(ACC_FUND_G)));
        List<RoleAccess.Appointment> appointments = new ArrayList<>(List.of(
                new RoleAccess.Appointment("A-1", ALICE, ORG_F, Role.RESEARCHER, 0L, END, null),
                new RoleAccess.Appointment("A-2", BOB, ORG_F, Role.PORTFOLIO_MANAGER, 0L, END,
                        null),
                new RoleAccess.Appointment("A-2G", BOB, ORG_G, Role.PORTFOLIO_MANAGER, 0L, END,
                        null),
                new RoleAccess.Appointment("A-3", CAROL, ORG_F, Role.RISK_OFFICER, 0L, END, null),
                new RoleAccess.Appointment("A-4", DAVE, ORG_F, Role.EXECUTION_TRADER, 0L, END,
                        null)));
        return new RoleRun.Config(mandates, appointments);
    }

    private static InformationRun.Config info() {
        return new InformationRun.Config(List.of(ALICE, BOB, CAROL, DAVE, ERIN));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter("cufe", List.of(
                new BoardModel.Seat("s1", ALICE), new BoardModel.Seat("s2", BOB),
                new BoardModel.Seat("s3", CAROL), new BoardModel.Seat("s4", DAVE),
                new BoardModel.Seat("s5", ERIN)), "s1", BoardModel.QUORUM);
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
                new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
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
                new OpeningRegistry.PropertyOwner(ERIN, OwnerKind.PERSON, ERIN),
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
