package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.RoleAccess.Role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2b 正式合同：live 决定的持久派发、schema10 逐批转换审计与重启语义。
 *
 * <p>真实网络与 Web pump 属于 B2c：本类不发任何提供方请求，只用本地 WorldJournal 与纯核回执。</p>
 */
class WorldRuntimeLiveDecisionTest {

    private static final String RUN = "live-decision-run";
    private static final long SEED = 11L;
    private static final long DAY = 86_400_000L;
    private static final long END = 30 * DAY;
    private static final long THINK = 1_000L;
    private static final long ACTION = 2_000L;
    private static final String WAKE = "SCHEDULED";

    private static final String ORG_F = "ORG-F";
    private static final String PRODUCT = "fund-a";
    private static final String ACC_FUND = "ACC-FUND";
    private static final String ACC_INV = "INV";
    private static final String INV = "investor";
    private static final String SYM = "CUFE1";

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";
    private static final String ERIN = "erin";

    private static final String TARGET_SET_ARGS = "{\"targetId\":\"tgt-1\",\"expectedVersion\":0,"
            + "\"organizationPartyId\":\"ORG-F\",\"productOwnerId\":\"fund-a\","
            + "\"accountId\":\"ACC-FUND\",\"symbol\":\"CUFE1\",\"absoluteTargetQuantity\":100,"
            + "\"priceLimit\":\"10.00\",\"deadlineMillis\":900000,\"sourceResearchId\":\"res-1\"}";

    private static final String PLAN_DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    Path tempDir;

    @BeforeEach
    void createWorkspaceDirectory() throws IOException {
        Path base = Path.of("target", "live-runtime-tests");
        Files.createDirectories(base);
        tempDir = Files.createTempDirectory(base, "case-");
    }

    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true)).build();

    // ------------------------------------------------------------------
    // 1. 档案与 schema 成对
    // ------------------------------------------------------------------

    @Test
    @DisplayName("live 档案必须含旧 schema9 全部模块与 live-decisions:1，版本严格成对")
    void liveManifestRequiresFullModulesAndPairedVersions() {
        assertTrue(liveManifest().hasLiveDecisions(), "新档案必须显式 live");
        assertFalse(dividendManifest().hasLiveDecisions(), "旧 schema9 不是 live");

        Map<String, Integer> withoutLive = schemaNineModules();
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1, withoutLive, Map.of(),
                RunManifest.LIVE_DECISIONS_V1), "缺 live-decisions:1 必须拒绝");

        Map<String, Integer> withoutDividend = liveModules();
        withoutDividend.remove("dividend");
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1, withoutDividend, Map.of(),
                RunManifest.LIVE_DECISIONS_V1), "schema10 必须保留旧 schema9 全部分红能力");

        Map<String, Integer> withExtra = liveModules();
        withExtra.put("unknown-module", 1);
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1, withExtra, Map.of(),
                RunManifest.LIVE_DECISIONS_V1), "不得夹带未声明模块");

        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1, liveModules(), Map.of(),
                RunManifest.SCRIPTED_DECISIONS_V1), "live 档案不得配 scripted 模型版本");
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_DIVIDEND_WORLD_EXPERIMENT_V1, schemaNineModules(), Map.of(),
                RunManifest.LIVE_DECISIONS_V1), "旧 schema9 不得配 live 模型版本");
    }

    @Test
    @DisplayName("live 配置只配新档案；旧 1..9 档案继续拒 live profile")
    void runtimePairsLiveProfileWithManifestOnly() {
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(
                tempDir.resolve("live-without-live-rule.journal"), dividendManifest(), OPENINGS,
                registry(), MFG, charter(), roles(), info(), liveConfig()),
                "旧 schema9 档案不得接收 live 配置");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(
                tempDir.resolve("live-rule-scripted.journal"), liveManifest(), OPENINGS,
                registry(), MFG, charter(), roles(), info(), scriptedConfig()),
                "live 档案不得接收 scripted 配置");
    }

    // ------------------------------------------------------------------
    // 2. 开决定：route 绑定、输入冻结、命令重放
    // ------------------------------------------------------------------

    @Test
    @DisplayName("live 开决定必须带 route；旧档案拒 route；plan 由真实 input 字节冻结")
    void openFreezesPlanFromRegeneratedInput() throws IOException {
        Path file = tempDir.resolve("freeze.journal");
        DecisionModel.Route route = route("research", 1, 2);
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("s-1");
            long before = rt.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> rt.openDecisions("o-bad",
                    List.of(new DecisionRun.Request(ALICE, "a-1", WAKE))),
                    "live 请求缺 route 必须拒绝");
            assertEquals(before, rt.snapshot().position());

            assertEquals("DECIDING:1", rt.openDecisions("o-1",
                    List.of(new DecisionRun.Request(ALICE, "a-1", WAKE, route))));
            DecisionModel.Decision decision = decision(rt, "a-1");
            assertNotNull(decision.plan(), "live 决定必须有冻结计划");
            assertEquals(route, decision.plan().route(), "route 必须原样冻结");
            assertEquals(decision.inputText().getBytes(StandardCharsets.UTF_8).length,
                    decision.plan().inputBytes(), "inputBytes 必须等于真实 UTF-8 字节");

            long afterOpen = rt.snapshot().position();
            assertEquals("DECIDING:1", rt.openDecisions("o-1",
                    List.of(new DecisionRun.Request(ALICE, "a-1", WAKE, route))),
                    "同一命令同内容重放返回原回报");
            assertEquals(afterOpen, rt.snapshot().position(), "重放不得新建批次");

            settleWait(rt, "a-1");
            assertThrows(IllegalArgumentException.class, () -> rt.openDecisions("o-2",
                    List.of(new DecisionRun.Request(ALICE, "a-1", WAKE, route("research", 1, 2, "key-2")))),
                    "既有 action 的 route 不得改写");
            assertEquals("DECIDING:0", rt.openDecisions("o-3",
                    List.of(new DecisionRun.Request(ALICE, "a-1", WAKE, route))),
                    "同 route 的既有 action 不再新建");
        }
    }

    @Test
    @DisplayName("旧 scripted 档案不接受带 route 的请求")
    void scriptedRuntimeRejectsRouteRequest() throws IOException {
        try (WorldRuntime rt = openScripted("scripted-route.journal")) {
            rt.beginDecisionSession("s-1");
            long before = rt.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> rt.openDecisions("o-1",
                    List.of(new DecisionRun.Request(ALICE, "a-1", WAKE, route("research", 0, 2)))));
            assertEquals(before, rt.snapshot().position());
        }
    }

    // ------------------------------------------------------------------
    // 3. 先持久派发事实，再返回一次 Call
    // ------------------------------------------------------------------

    @Test
    @DisplayName("派发先落盘再返回唯一 Call；关闭后日志有 DECISION_DISPATCH_V1 与 dispatch 事实")
    void dispatchPersistsFactBeforeReturningCall() throws IOException {
        Path file = tempDir.resolve("dispatch.journal");
        String attemptId;
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(new DecisionRun.Request(ALICE, "a-1", WAKE,
                    route("research", 1, 2))));
            long position = rt.snapshot().position();
            DecisionModel.Decision decision = decision(rt, "a-1");

            WorldRuntime.DispatchResult result = rt.dispatchLiveDecision("cmd-1", "a-1");
            assertEquals("DISPATCHED", result.verdict());
            assertNotNull(result.call());
            assertTrue(result.reason() == null || result.reason().isEmpty(),
                    "成功派发不应带拒绝原因");
            assertEquals(position + 1, rt.snapshot().position(), "派发必须单独持久一帧");
            assertEquals(1, rt.outstandingLiveDispatches());
            assertEquals(decision.plan(), result.call().plan(), "Call 必须携带冻结计划");

            DecisionRun.Dispatch fact = rt.snapshot().decisions().orElseThrow()
                    .dispatch(result.call().attemptId());
            assertNotNull(fact);
            assertEquals("a-1", fact.actionId());
            assertEquals(result.call().attemptNo(), fact.attemptNo());
            assertEquals(result.call().recoveryGeneration(), fact.recoveryGeneration());
            assertEquals(position + 1, fact.dispatchPosition());
            assertEquals(rt.snapshot().timeMillis(), fact.dispatchTimeMillis());
            assertTrue(fact.planDigest().matches("[0-9a-f]{64}"), "计划摘要必须是 64 位小写十六进制");
            assertEquals(decision.inputDigest(), fact.inputDigest());
            attemptId = result.call().attemptId();
        }
        List<WorldCommit> commits = commits(file);
        assertTrue(hasEvent(commits, "DECISION_DISPATCH_V1"), "日志必须记录派发领域事件");
        ObjectNode snapshot = lastSnapshot(commits);
        assertEquals(1, snapshot.path("decisions").path("dispatches").size(),
                "最后快照必须保留派发事实");
        assertEquals(attemptId, snapshot.path("decisions").path("dispatches")
                .fieldNames().next());
    }

    @Test
    @DisplayName("同命令同 action 重放只回 ALREADY_DISPATCHED；同命令异 action 抛 IAE，均不写盘")
    void commandReplayNeverReturnsCall() throws IOException {
        try (WorldRuntime rt = open("replay.journal")) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(
                    new DecisionRun.Request(ALICE, "a-1", WAKE, route("research", 1, 2)),
                    new DecisionRun.Request(BOB, "b-1", WAKE, route("research", 1, 2))));
            assertEquals("DISPATCHED", rt.dispatchLiveDecision("cmd-1", "a-1").verdict());
            long position = rt.snapshot().position();

            WorldRuntime.DispatchResult replay = rt.dispatchLiveDecision("cmd-1", "a-1");
            assertEquals("ALREADY_DISPATCHED", replay.verdict());
            assertNull(replay.call(), "重放绝不能再次授权");
            assertEquals(position, rt.snapshot().position());

            assertThrows(IllegalArgumentException.class,
                    () -> rt.dispatchLiveDecision("cmd-1", "b-1"),
                    "同一命令标识不得对应不同 action");
            assertEquals(position, rt.snapshot().position());
            assertEquals(1, rt.outstandingLiveDispatches());
        }
    }

    @Test
    @DisplayName("同一旧 attempt 换新命令不再授权；未知与已终态动作返回 EMPTY")
    void sameAttemptDifferentCommandAndTerminalActions() throws IOException {
        try (WorldRuntime rt = open("attempt-once.journal")) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(new DecisionRun.Request(ALICE, "a-1", WAKE,
                    route("research", 1, 2))));
            WorldRuntime.DispatchResult first = rt.dispatchLiveDecision("cmd-1", "a-1");
            assertEquals("DISPATCHED", first.verdict());
            long position = rt.snapshot().position();

            WorldRuntime.DispatchResult second = rt.dispatchLiveDecision("cmd-2", "a-1");
            assertEquals("ALREADY_DISPATCHED", second.verdict(),
                    "同一旧 attempt 不得因换命令再次授权");
            assertNull(second.call());
            assertEquals(position, rt.snapshot().position());

            WorldRuntime.DispatchResult unknown = rt.dispatchLiveDecision("cmd-3", "nope");
            assertEquals("EMPTY", unknown.verdict());
            assertNull(unknown.call());
            assertEquals(position, rt.snapshot().position());

            DecisionRun.Call call = first.call();
            assertEquals("RECORDED", rt.acceptDecisionReply("r-1",
                    reply(call, "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 20, 5))));
            WorldRuntime.DispatchResult settled = rt.dispatchLiveDecision("cmd-4", "a-1");
            assertEquals("EMPTY", settled.verdict(), "已终态动作不得再派发");
            assertNull(settled.call());
            assertEquals(0, rt.outstandingLiveDispatches());
        }
    }

    @Test
    @DisplayName("旧 dispatchDecision 入口在 live 档案必须拒绝且不写盘")
    void legacyDispatchEntryRejectsLive() throws IOException {
        try (WorldRuntime rt = open("legacy-entry.journal")) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(new DecisionRun.Request(ALICE, "a-1", WAKE,
                    route("research", 1, 2))));
            long position = rt.snapshot().position();
            assertThrows(IllegalStateException.class, () -> rt.dispatchDecision("a-1"));
            assertEquals(position, rt.snapshot().position());
            assertEquals(0, rt.outstandingLiveDispatches());
            assertNull(rt.snapshot().decisions().orElseThrow()
                    .dispatch(decision(rt, "a-1").lastAttempt().attemptId()));
        }
    }

    // ------------------------------------------------------------------
    // 4. 回执/失败/超时必须有已持久派发
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未派发的 live 回执、失败与超时一律拒绝，状态不前进")
    void undispatchedReplyFailureAndTimeoutAreRejected() throws IOException {
        try (WorldRuntime rt = open("undispatched.journal")) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(new DecisionRun.Request(ALICE, "a-1", WAKE,
                    route("research", 1, 2))));
            DecisionModel.Decision before = decision(rt, "a-1");
            DecisionModel.Attempt attempt = before.lastAttempt();
            long position = rt.snapshot().position();

            DecisionModel.Reply reply = new DecisionModel.Reply(RUN, "a-1", attempt.attemptId(),
                    attempt.attemptNo(), attempt.recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                    DecisionModel.Usage.unknownCost(1, 20, 5), "provider:test");
            String rejected = rt.acceptDecisionReply("r-1", reply);
            assertTrue(rejected.startsWith("REJECTED:"), "未派发回执必须拒绝：" + rejected);
            assertEquals(position, rt.snapshot().position());
            assertEquals(DecisionModel.Status.OPEN, decision(rt, "a-1").status());
            assertFalse(decision(rt, "a-1").lastAttempt().settledDone());

            String failed = rt.failDecisionAttempt("a-1", attempt.attemptNo(), attempt.attemptId(),
                    attempt.recoveryGeneration(), "PROVIDER_TIMEOUT");
            assertTrue(failed.startsWith("REJECTED:"), "未派发失败必须拒绝：" + failed);
            assertEquals(position, rt.snapshot().position());
            assertEquals(0, rt.expireDecisionTimeouts(), "未派发尝试不进入墙钟超时");
            assertEquals(position, rt.snapshot().position());

            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("cmd-1", "a-1");
            assertEquals("DISPATCHED", dispatched.verdict());
            assertEquals("RECORDED", rt.acceptDecisionReply("r-2", reply(dispatched.call(),
                    "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 20, 5))),
                    "派发之后同一回执必须可结算");
        }
    }

    @Test
    @DisplayName("未派发旧代次回执不得借归档入口制造提供方事实")
    void undispatchedStaleReplyDoesNotArchive() throws IOException {
        Path file = tempDir.resolve("undispatched-stale.journal");
        DecisionModel.Reply reply;
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(new DecisionRun.Request(ALICE, "a-1", WAKE,
                    route("research", 1, 2))));
            DecisionModel.Attempt attempt = decision(rt, "a-1").lastAttempt();
            reply = new DecisionModel.Reply(RUN, "a-1", attempt.attemptId(), attempt.attemptNo(),
                    attempt.recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                    DecisionModel.Usage.unknownCost(1, 20, 5), "provider:test");
        }
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("s-2");
            var before = rt.snapshot();
            assertTrue(rt.acceptDecisionReply("stale", reply).startsWith("REJECTED:"));
            assertEquals(before, rt.snapshot());
            assertTrue(rt.snapshot().decisions().orElseThrow().archives().isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // 5. 并发与预算闸
    // ------------------------------------------------------------------

    @Test
    @DisplayName("run 实际在途按已持久派发计数：2 满后拒绝，结算一个才放行")
    void runMaxInFlightCountsPersistedDispatches() throws IOException {
        try (WorldRuntime rt = open(tempDir.resolve("inflight.journal"),
                liveConfig(2, 3, 60_000L))) {
            rt.beginDecisionSession("s-1");
            assertEquals("DECIDING:3", rt.openDecisions("o-1", List.of(
                    new DecisionRun.Request(ALICE, "a-1", WAKE, route("research", 1, 4)),
                    new DecisionRun.Request(BOB, "b-1", WAKE, route("research", 1, 4)),
                    new DecisionRun.Request(CAROL, "c-1", WAKE, route("research", 1, 4)))));

            WorldRuntime.DispatchResult firstDispatch = rt.dispatchLiveDecision("d-1", "a-1");
            assertEquals("DISPATCHED", firstDispatch.verdict());
            assertEquals("DISPATCHED", rt.dispatchLiveDecision("d-2", "b-1").verdict());
            long position = rt.snapshot().position();
            WorldRuntime.DispatchResult blocked = rt.dispatchLiveDecision("d-3", "c-1");
            assertEquals("REJECTED", blocked.verdict());
            assertNull(blocked.call());
            assertFalse(blocked.reason() == null || blocked.reason().isBlank(), "拒绝必须给原因");
            assertEquals(position, rt.snapshot().position(), "拒绝不得写盘");
            assertEquals(2, rt.outstandingLiveDispatches());

            assertEquals("RECORDED", rt.acceptDecisionReply("r-1", reply(firstDispatch.call(),
                    "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 20, 5))));
            assertEquals(1, rt.outstandingLiveDispatches());
            assertEquals("DISPATCHED", rt.dispatchLiveDecision("d-4", "c-1").verdict());
        }
    }

    @Test
    @DisplayName("scene 并发取最新已观察 Route：同 scene 满后拒绝，跨 scene 独立，降 cap 仍拦新派发")
    void sceneConcurrencyUsesLatestObservedRoute() throws IOException {
        try (WorldRuntime rt = open("scene.journal")) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(
                    new DecisionRun.Request(ALICE, "a-1", WAKE, route("research", 1, 1)),
                    new DecisionRun.Request(BOB, "b-1", WAKE, route("research", 1, 1)),
                    new DecisionRun.Request(DAVE, "d-1", WAKE, route("trading", 1, 1))));
            WorldRuntime.DispatchResult researchA = rt.dispatchLiveDecision("d-a", "a-1");
            assertEquals("DISPATCHED", researchA.verdict());
            assertEquals("REJECTED", rt.dispatchLiveDecision("d-b", "b-1").verdict());
            assertEquals("DISPATCHED", rt.dispatchLiveDecision("d-d", "d-1").verdict());

            assertEquals("DECIDING:1", rt.openDecisions("o-2", List.of(
                    new DecisionRun.Request(CAROL, "c-1", WAKE, route("research", 2, 1)))));
            assertEquals(2, DecisionModel.latestRoute(
                    rt.snapshot().decisions().orElseThrow().state(), "research")
                    .orElseThrow().revision(), "最新已观察版本必须生效");
            assertEquals("REJECTED", rt.dispatchLiveDecision("d-c", "c-1").verdict(),
                    "降 cap 后新派发仍须受未结算在途限制");

            assertEquals("RECORDED", rt.acceptDecisionReply("r-a", reply(researchA.call(),
                    "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 20, 5))));
            assertEquals("DISPATCHED", rt.dispatchLiveDecision("d-c2", "c-1").verdict());
        }
    }

    @Test
    @DisplayName("已知超额冻结后所有新派发拒绝，且不改状态")
    void overrunBlocksAllNewDispatches() throws IOException {
        try (WorldRuntime rt = open("overrun.journal")) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(
                    new DecisionRun.Request(ALICE, "a-1", WAKE, route("research", 1, 2)),
                    new DecisionRun.Request(BOB, "b-1", WAKE, route("research", 1, 2))));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("d-a", "a-1");
            assertEquals("DISPATCHED", dispatched.verdict());
            long reserved = decision(rt, "a-1").lastAttempt().reserved().inputTokens();
            DecisionModel.Usage overrun = DecisionModel.Usage.unknownCost(1, reserved + 1, 5);
            assertEquals("TECHNICAL_FAILED:" + DecisionModel.REASON_USAGE_EXCEEDS_RESERVATION,
                    rt.acceptDecisionReply("r-a", reply(dispatched.call(),
                            "{\"tool\":\"role.wait\"}", overrun)));
            assertTrue(DecisionModel.liveBudgetBlocked(
                    rt.snapshot().decisions().orElseThrow().state()));

            long position = rt.snapshot().position();
            WorldRuntime.DispatchResult blocked = rt.dispatchLiveDecision("d-b", "b-1");
            assertEquals("REJECTED", blocked.verdict());
            assertNull(blocked.call());
            assertEquals(position, rt.snapshot().position());
        }
    }

    @Test
    @DisplayName("派发提交 I/O 失败：抛 IOException、暂停、快照不动、绝不返回 Call")
    void dispatchCommitFailureReturnsNoCall() throws IOException {
        Path file = tempDir.resolve("dispatch-io.journal");
        String attemptId;
        long position;
        WorldRuntime rt = open(file);
        try {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(new DecisionRun.Request(ALICE, "a-1", WAKE,
                    route("research", 1, 2))));
            attemptId = decision(rt, "a-1").lastAttempt().attemptId();
            position = rt.snapshot().position();
            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, () -> rt.dispatchLiveDecision("cmd-io", "a-1"));
            } finally {
                Thread.interrupted();
            }
            assertTrue(rt.isPaused(), "写失败必须暂停运行");
            assertEquals(position, rt.snapshot().position(), "失败批次不得生效");
            assertNull(rt.snapshot().decisions().orElseThrow().dispatch(attemptId),
                    "失败不得留下派发事实");
            assertEquals(0, rt.outstandingLiveDispatches());
        } finally {
            quietClose(rt);
        }
        try (WorldRuntime reopened = open(file)) {
            assertEquals(position, reopened.snapshot().position());
            assertNull(reopened.snapshot().decisions().orElseThrow().dispatch(attemptId));
        }
    }

    // ------------------------------------------------------------------
    // 6. 重启：旧 attempt 保守结算，新 attempt 须新命令
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重启先 begin；旧 attempt 保守结算、旧事实保留、旧命令无 Call、新 attempt 新命令才授权")
    void restartSettlesOldAttemptAndRequiresNewCommandForNewAttempt() throws IOException {
        Path file = tempDir.resolve("restart.journal");
        String oldAttempt;
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(new DecisionRun.Request(ALICE, "a-1", WAKE,
                    route("research", 1, 2))));
            WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("cmd-1", "a-1");
            assertEquals("DISPATCHED", dispatched.verdict());
            oldAttempt = dispatched.call().attemptId();
            assertEquals(1, rt.outstandingLiveDispatches());
        }
        try (WorldRuntime rt = open(file)) {
            long position = rt.snapshot().position();
            assertThrows(IllegalStateException.class,
                    () -> rt.dispatchLiveDecision("cmd-before-begin", "a-1"),
                    "重启后未 begin 不得派发");
            assertEquals(position, rt.snapshot().position());

            rt.beginDecisionSession("s-2");
            DecisionModel.Decision decision = decision(rt, "a-1");
            assertEquals(2, decision.attempts().size(), "旧代次未结算尝试必须保守结算并按预算重试");
            assertEquals(DecisionModel.Status.OPEN, decision.status());
            String newAttempt = decision.lastAttempt().attemptId();
            assertNotEquals(oldAttempt, newAttempt, "新 attempt 必须是新身份");
            assertEquals(oldAttempt, decision.attempts().get(0).attemptId());

            DecisionRun run = rt.snapshot().decisions().orElseThrow();
            assertNotNull(run.dispatch(oldAttempt), "旧派发事实必须保留");
            assertNull(run.dispatch(newAttempt));
            assertEquals(0, rt.outstandingLiveDispatches(), "旧尝试已结算，不计在途");
            assertTrue(DecisionModel.sceneUsage(run.state(), "research").inputTokens() > 0,
                    "保守结算必须占用场景预算，不得重启复原");

            WorldRuntime.DispatchResult replay = rt.dispatchLiveDecision("cmd-1", "a-1");
            assertEquals("ALREADY_DISPATCHED", replay.verdict());
            assertNull(replay.call());
            assertEquals(position + 1, rt.snapshot().position());

            WorldRuntime.DispatchResult fresh = rt.dispatchLiveDecision("cmd-2", "a-1");
            assertEquals("DISPATCHED", fresh.verdict(), "新 attempt 必须能用新命令显式派发");
            assertEquals(newAttempt, fresh.call().attemptId());
            assertEquals(1, rt.outstandingLiveDispatches());
        }
    }

    @Test
    @DisplayName("live 静态工具参数失败可持久与恢复，保留声明且不安排业务任务")
    void liveStaticDtoFailureIsRecordedAndReopened() throws IOException {
        Path file = tempDir.resolve("live-dto.journal");
        DecisionModel.Usage usage = DecisionModel.Usage.unknownCost(1, 100, 40);
        String body = "{\"tool\":\"role.target.set\",\"args\":"
                + TARGET_SET_ARGS.substring(0, TARGET_SET_ARGS.length() - 1)
                + ",\"quantity\":3}}";
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("s");
            rt.openDecisions("o", List.of(new DecisionRun.Request(BOB, "dto", WAKE,
                    route("trading", 1, 2))));
            DecisionRun.Call call = rt.dispatchLiveDecision("p", "dto").call();
            assertEquals("TECHNICAL_FAILED:TOOL_ARGS_INVALID",
                    rt.acceptDecisionReply("r", reply(call, body, usage)));
            assertEquals(usage, decision(rt, "dto").attempts().get(0).declaredUsage());
            assertEquals(2, decision(rt, "dto").attempts().size());
            assertNull(rt.snapshot().decisions().orElseThrow().actionRef("dto"));
        }
        try (WorldRuntime rt = open(file)) {
            assertEquals(body, decision(rt, "dto").attempts().get(0).replyBody());
            assertEquals(usage, decision(rt, "dto").attempts().get(0).declaredUsage());
            assertNull(rt.snapshot().decisions().orElseThrow().actionRef("dto"));
        }
    }

    @Test
    @DisplayName("live 无回执失败 JSON 完整绑定尝试身份，支持引号与换行并可恢复")
    void liveFailureUsesSafeJsonAndFullIdentity() throws Exception {
        int caseNumber = 0;
        for (String action : List.of("normal-failure", "quoted\"action\n")) {
            Path file = tempDir.resolve("failure-" + (++caseNumber) + ".journal");
            DecisionRun.Call call;
            String reason = "provider\"failure\n";
            try (WorldRuntime rt = open(file)) {
                rt.beginDecisionSession("s");
                rt.openDecisions("o", List.of(new DecisionRun.Request(ALICE, action, WAKE,
                        route("research", 1, 2))));
                call = rt.dispatchLiveDecision("p", action).call();
                assertEquals("TECHNICAL_FAILED:" + reason, rt.failDecisionAttempt(action,
                        call.attemptNo(), call.attemptId(), call.recoveryGeneration(), reason));
            }
            List<WorldCommit> history = commits(file);
            WorldCommit failure = history.get(history.size() - 1);
            JsonNode content = json.readTree(failure.commandContent());
            assertEquals(action, content.path("actionId").asText());
            assertEquals(call.attemptId(), content.path("attemptId").asText());
            assertEquals(call.attemptNo(), content.path("attemptNo").asInt());
            assertEquals(call.recoveryGeneration(), content.path("recoveryGeneration").asLong());
            assertEquals(reason, content.path("reason").asText());
            try (WorldRuntime rt = open(file)) {
                assertEquals(DecisionModel.REASON_UNKNOWN_USAGE + ":" + reason,
                        decision(rt, action).attempts().get(0).reason());
            }
            int[] changed = {0};
            Path forged = rewriteCommits("identity-" + caseNumber + ".journal", file,
                    commit -> {
                        JsonNode event = decisionEvent(commit, "DECISION_FAILED_V1");
                        if (event == null) {
                            return;
                        }
                        try {
                            ObjectNode bad = (ObjectNode) json.readTree(commit.path("commandContent").asText());
                            bad.put("attemptNo", call.attemptNo() + 1);
                            String text = json.writeValueAsString(bad);
                            commit.put("commandContent", text);
                            ((ObjectNode) event).put("payload", text);
                            changed[0]++;
                        } catch (IOException error) {
                            throw new IllegalStateException(error);
                        }
                    });
            assertEquals(1, changed[0]);
            assertThrows(IOException.class, () -> open(forged).close());
        }
    }

    // ------------------------------------------------------------------
    // 7. schema10 逐批转换审计（完整 copy/re-sign）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("末帧身份复用拒绝也不得夹带总现金守恒的账户间移转")
    void rejectedAuditCannotTransferCashInLastFrame() throws Exception {
        Path source = tempDir.resolve("rejected-audit-source.journal");
        try (WorldRuntime rt = open(source)) {
            rt.beginDecisionSession("s");
            rt.openDecisions("o", List.of(new DecisionRun.Request(ALICE, "a", WAKE,
                    route("research", 1, 2))));
            DecisionRun.Call call = rt.dispatchLiveDecision("p", "a").call();
            assertEquals("RECORDED", rt.acceptDecisionReply("wait", reply(call,
                    "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 20, 5))));
            assertEquals("REJECTED:IDENTITY_REUSE", rt.acceptDecisionReply("q-wait-again",
                    reply(call, "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 21, 5))));
        }
        Path control = rewriteCommits("rejected-control.journal", source, commit -> { });
        try (WorldRuntime rt = open(control)) {
            assertEquals(new BigDecimal("1000.00"), rt.snapshot().accounts().get(ACC_FUND).cash());
        }
        int[] changed = {0};
        Path forged = rewriteCommits("rejected-forged.journal", source, commit -> {
            String before = commit.toString();
            mutate(commit, "rejected-cash-transfer");
            if (!before.equals(commit.toString())) {
                changed[0]++;
            }
        });
        assertEquals(1, changed[0]);
        assertThrows(IOException.class, () -> open(forged).close());
    }

    @Test
    @DisplayName("合法 live 日志可重开；完整 re-sign 只改目标字段的反例必须拒绝")
    void resignedFramesRejectOnlyTargetedMutations() throws Exception {
        Path source = tempDir.resolve("audit-source.journal");
        buildAuditJournal(source);

        Path control = rewriteCommits("audit-control.journal", source, commit -> { });
        try (WorldRuntime ignored = open(control, auditConfig())) {
            assertNotNull(ignored.snapshot().decisions());
        }

        for (String mutation : List.of("dispatch-plan-digest", "dispatch-attempt",
                "dispatch-removed", "dispatch-added", "reply-outcome", "open-command-route",
                "drop-plan", "failure-reason", "timeout-reason", "session-generation",
                "dispatch-cash-transfer", "rejected-cash-transfer", "conflicting-event",
                "duplicate-audit", "missing-audit")) {
            int[] changed = {0};
            Path forged = rewriteCommits("audit-" + mutation + ".journal", source,
                    commit -> {
                        String before = commit.toString();
                        mutate(commit, mutation);
                        if (!before.equals(commit.toString())) {
                            changed[0]++;
                        }
                    });
            assertTrue(changed[0] > 0, "反例必须实际修改至少一个目标帧：" + mutation);
            assertThrows(IOException.class, () -> open(forged, auditConfig()).close(),
                    "篡改帧必须被逐批转换审计拒绝：" + mutation);
        }
    }

    @Test
    @DisplayName("旧 schema9 快照不得携带 plan 或 dispatches；合法对照仍可恢复")
    void oldSchemaNineRejectsPlanAndDispatch() throws Exception {
        Path source = tempDir.resolve("schema9-source.journal");
        try (WorldRuntime rt = openScripted(source)) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(new DecisionRun.Request(ALICE, "a-1", WAKE)));
        }
        Path control = rewriteCommits("schema9-control.journal", source, commit -> { });
        try (WorldRuntime ignored = openScripted(control)) {
            assertNotNull(ignored.snapshot().decisions());
        }

        int[] planChanges = {0};
        Path withPlan = rewriteCommits("schema9-plan.journal", source, commit -> {
            ObjectNode snapshot = snapshotOf(commit);
            if (snapshot == null) {
                return;
            }
            for (JsonNode decision : snapshot.path("decisions").path("state").path("decisions")) {
                ObjectNode node = (ObjectNode) decision;
                int bytes = node.path("inputText").asText().getBytes(StandardCharsets.UTF_8).length;
                ObjectNode plan = json.createObjectNode();
                plan.set("route", routeNode("research", 0, 2, 10));
                plan.put("inputBytes", bytes);
                node.set("plan", plan);
                planChanges[0]++;
            }
            putSnapshot(commit, snapshot);
        });
        assertTrue(planChanges[0] > 0, "旧档plan反例必须实际修改决定");
        assertThrows(IOException.class, () -> openScripted(withPlan).close(),
                "旧 schema9 不得读取计划");

        int[] dispatchChanges = {0};
        Path withDispatch = rewriteCommits("schema9-dispatch.journal", source, commit -> {
            ObjectNode snapshot = snapshotOf(commit);
            if (snapshot == null) {
                return;
            }
            ObjectNode decisions = (ObjectNode) snapshot.path("decisions");
            JsonNode history = decisions.path("state").path("decisions");
            if (history.isEmpty()) {
                return;
            }
            ObjectNode dispatch = json.createObjectNode();
            JsonNode attempt = history.get(0).path("attempts").get(0);
            dispatch.put("attemptId", attempt.path("attemptId").asText());
            dispatch.put("actionId", history.get(0).path("actionId").asText());
            dispatch.put("attemptNo", attempt.path("attemptNo").asInt());
            dispatch.put("recoveryGeneration", attempt.path("recoveryGeneration").asLong());
            dispatch.put("planDigest", PLAN_DIGEST);
            dispatch.put("inputDigest", attempt.path("inputDigest").asText());
            dispatch.put("dispatchPosition", snapshot.path("position").asLong());
            dispatch.put("dispatchTimeMillis", snapshot.path("timeMillis").asLong());
            dispatch.put("authorizedAtEpochMillis", 1_700_000_000_000L);
            ObjectNode map = json.createObjectNode();
            map.set(attempt.path("attemptId").asText(), dispatch);
            decisions.set("dispatches", map);
            dispatchChanges[0]++;
            putSnapshot(commit, snapshot);
        });
        assertTrue(dispatchChanges[0] > 0, "旧档dispatch反例必须实际修改决定");
        assertThrows(IOException.class, () -> openScripted(withDispatch).close(),
                "旧 schema9 不得读取派发事实");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private WorldRuntime open(String name) throws IOException {
        return open(tempDir.resolve(name));
    }

    private WorldRuntime open(Path file) throws IOException {
        return open(file, liveConfig());
    }

    private WorldRuntime open(Path file, DecisionModel.Config config) throws IOException {
        return new WorldRuntime(file, liveManifest(), OPENINGS, registry(), MFG, charter(),
                roles(), info(), config);
    }

    private WorldRuntime openScripted(String name) throws IOException {
        return openScripted(tempDir.resolve(name));
    }

    private WorldRuntime openScripted(Path file) throws IOException {
        return new WorldRuntime(file, dividendManifest(), OPENINGS, registry(), MFG, charter(),
                roles(), info(), scriptedConfig());
    }

    private static void quietClose(WorldRuntime rt) {
        try {
            rt.close();
        } catch (RuntimeException ignored) {
            // 中断已关闭通道，不影响证据
        }
    }

    private static DecisionModel.Config liveConfig() {
        return liveConfig(2, 3, 60_000L);
    }

    /** 审计夹具使用 5ms 墙钟，保证超时事件可真实产生。 */
    private static DecisionModel.Config auditConfig() {
        return liveConfig(2, 3, 5L);
    }

    private static DecisionModel.Config liveConfig(int maxInFlight, int maxAttempts,
                                                   long wallTimeoutMillis) {
        DecisionModel.Usage cap = DecisionModel.Usage.unknownCost(500, 20_000_000, 5_000_000);
        Map<String, DecisionModel.Usage> persons = new TreeMap<>();
        for (String person : List.of(ALICE, BOB, CAROL, DAVE, ERIN)) {
            persons.put(person, DecisionModel.Usage.unknownCost(100, 4_000_000, 1_000_000));
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_DEEPSEEK_HTTP,
                "deepseek:live-runtime-test", DecisionModel.ESTIMATE_VERSION,
                Map.of(WAKE, THINK), actionMillis(),
                new DecisionModel.Limits(maxAttempts, 20_000, 4_000, 60_000, 4_096, 4,
                        maxInFlight, wallTimeoutMillis),
                new DecisionModel.Budget(cap, persons), DecisionModel.Profile.LIVE_HTTP_V1);
    }

    private static DecisionModel.Config scriptedConfig() {
        DecisionModel.Usage zero = new DecisionModel.Usage(20, 40_000, 20_480,
                new BigDecimal("0.00"));
        Map<String, DecisionModel.Usage> persons = new TreeMap<>();
        for (String person : List.of(ALICE, BOB, CAROL, DAVE, ERIN)) {
            persons.put(person, zero);
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted:live-test",
                "scripted-v1", Map.of(WAKE, THINK), actionMillis(),
                new DecisionModel.Limits(2, 20_000, 4_000, 2_000, 1_024, 4, 2, 60_000L),
                new DecisionModel.Budget(zero, persons));
    }

    private static Map<String, Long> actionMillis() {
        Map<String, Long> action = new TreeMap<>();
        for (String tool : List.of(RoleTradingView.TOOL_READING_START,
                RoleTradingView.TOOL_READING_CANCEL, RoleTradingView.TOOL_RESEARCH_SUBMIT,
                RoleTradingView.TOOL_TARGET_SET, RoleTradingView.TOOL_TARGET_REVIEW,
                RoleTradingView.TOOL_TARGET_HALT, RoleTradingView.TOOL_TARGET_CONFIRM,
                RoleTradingView.TOOL_TARGET_CANCEL, RoleTradingView.TOOL_CHILD_SUBMIT,
                RoleTradingView.TOOL_CHILD_CANCEL)) {
            action.put(tool, ACTION);
        }
        return action;
    }

    private static DecisionModel.Route route(String scene, long revision, int concurrency) {
        return route(scene, revision, concurrency, "key-1");
    }

    private static DecisionModel.Route route(String scene, long revision, int concurrency,
                                             String slot) {
        return new DecisionModel.Route(scene, slot, "deepseek-flash", revision, 20, 1_000_000,
                1_024, concurrency);
    }

    private static ObjectNode routeNode(String scene, long revision, int concurrency, int calls) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("sceneId", scene);
        node.put("slotId", "key-1");
        node.put("model", "deepseek-flash");
        node.put("revision", revision);
        node.put("maxCalls", calls);
        node.put("maxTotalTokens", 10_000);
        node.put("maxOutputTokens", 1_024);
        node.put("maxConcurrency", concurrency);
        return node;
    }

    private static DecisionModel.Reply reply(DecisionRun.Call call, String body,
                                             DecisionModel.Usage usage) {
        return new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                call.attemptNo(), call.recoveryGeneration(), body, usage, "provider:test");
    }

    private static void settleWait(WorldRuntime rt, String actionId) throws IOException {
        WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("settle-" + actionId,
                actionId);
        assertEquals("DISPATCHED", dispatched.verdict(), dispatched.reason());
        assertEquals("RECORDED", rt.acceptDecisionReply("settle-reply-" + actionId,
                reply(dispatched.call(), "{\"tool\":\"role.wait\"}",
                        DecisionModel.Usage.unknownCost(1, 20, 5))));
    }

    private static DecisionModel.Decision decision(WorldRuntime rt, String actionId) {
        return DecisionModel.decision(rt.snapshot().decisions().orElseThrow().state(), actionId)
                .orElseThrow();
    }

    private static Map<String, Integer> schemaNineModules() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions",
                "dividend")) {
            modules.put(name, 1);
        }
        return modules;
    }

    private static Map<String, Integer> liveModules() {
        Map<String, Integer> modules = schemaNineModules();
        modules.put("live-decisions", 1);
        return modules;
    }

    private static RunManifest liveManifest() {
        return new RunManifest(RUN, SEED,
                RunManifest.RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1, liveModules(),
                Map.of("fixture", "live-decision-runtime-v1"), RunManifest.LIVE_DECISIONS_V1);
    }

    private static RunManifest dividendManifest() {
        return new RunManifest(RUN, SEED,
                RunManifest.RULES_DIVIDEND_WORLD_EXPERIMENT_V1, schemaNineModules(),
                Map.of("fixture", "live-decision-runtime-v1"), RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private static RoleRun.Config roles() {
        List<RoleAccess.ProductMandate> mandates = List.of(
                new RoleAccess.ProductMandate(ORG_F, PRODUCT, List.of(ACC_FUND)));
        List<RoleAccess.Appointment> appointments = new ArrayList<>(List.of(
                new RoleAccess.Appointment("A-1", ALICE, ORG_F, Role.RESEARCHER, 0L, END, null),
                new RoleAccess.Appointment("A-2", BOB, ORG_F, Role.PORTFOLIO_MANAGER, 0L, END, null),
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

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), SYM);

    private static final List<OpeningAccount> OPENINGS = List.of(
            new OpeningAccount(ACC_FUND, PRODUCT, new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount(ACC_INV, INV, new BigDecimal("0.00"), "实验开局历史禀赋"),
            new OpeningAccount("CO", "company", new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "实验开局历史禀赋"),
            new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "实验开局历史禀赋"),
            new OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "实验开局历史禀赋"));

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party(ORG_F, PartyKind.ORGANIZATION, "中央财经资管"),
                new OpeningRegistry.Party("cufe", PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INV, PartyKind.PERSON, "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner(PRODUCT, OpeningRegistry.OwnerKind.PRODUCT,
                        null),
                new OpeningRegistry.PropertyOwner("orgf", OpeningRegistry.OwnerKind.ORGANIZATION,
                        ORG_F),
                new OpeningRegistry.PropertyOwner(ALICE, OpeningRegistry.OwnerKind.PERSON, ALICE),
                new OpeningRegistry.PropertyOwner(BOB, OpeningRegistry.OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CAROL, OpeningRegistry.OwnerKind.PERSON, CAROL),
                new OpeningRegistry.PropertyOwner(DAVE, OpeningRegistry.OwnerKind.PERSON, DAVE),
                new OpeningRegistry.PropertyOwner(ERIN, OpeningRegistry.OwnerKind.PERSON, ERIN),
                new OpeningRegistry.PropertyOwner(INV, OpeningRegistry.OwnerKind.PERSON, INV),
                new OpeningRegistry.PropertyOwner("company", OpeningRegistry.OwnerKind.ORGANIZATION,
                        "cufe"),
                new OpeningRegistry.PropertyOwner("supplier", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("customer", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("ops", OpeningRegistry.OwnerKind.AGGREGATE,
                        null));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYM, "cufe", 200L, new BigDecimal("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }

    // ------------------------------------------------------------------
    // 日志读取与重签（只在 runtime 关闭后读）
    // ------------------------------------------------------------------

    private static List<WorldCommit> commits(Path file) throws IOException {
        try (WorldJournal reader = new WorldJournal(file, RUN)) {
            return reader.commits();
        }
    }

    private static boolean hasEvent(List<WorldCommit> commits, String type) {
        for (WorldCommit commit : commits) {
            for (WorldEvent event : commit.events()) {
                if (event.type().equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ObjectNode lastSnapshot(List<WorldCommit> commits) throws IOException {
        ObjectNode last = null;
        for (WorldCommit commit : commits) {
            for (WorldEvent event : commit.events()) {
                if ("STATE_SNAPSHOT_V1".equals(event.type())) {
                    last = (ObjectNode) json.readTree(event.payload());
                }
            }
        }
        if (last == null) {
            throw new AssertionError("日志中没有状态快照");
        }
        return last;
    }

    private Path rewriteCommits(String name, Path source,
                                java.util.function.Consumer<ObjectNode> change)
            throws Exception {
        Path destination = tempDir.resolve(name);
        try (DataInputStream input = new DataInputStream(Files.newInputStream(source));
             DataOutputStream output = new DataOutputStream(Files.newOutputStream(destination))) {
            while (input.available() > 0) {
                int length = input.readInt();
                assertEquals(~length, input.readInt());
                byte[] payload = input.readNBytes(length);
                assertEquals(32, input.readNBytes(32).length);
                ObjectNode commit = (ObjectNode) json.readTree(payload);
                change.accept(commit);
                byte[] rewritten = json.writeValueAsBytes(commit);
                output.writeInt(rewritten.length);
                output.writeInt(~rewritten.length);
                output.write(rewritten);
                output.write(MessageDigest.getInstance("SHA-256").digest(rewritten));
            }
        }
        return destination;
    }

    private ObjectNode snapshotOf(ObjectNode commit) {
        for (JsonNode event : commit.withArray("events")) {
            if ("STATE_SNAPSHOT_V1".equals(event.path("type").asText())) {
                try {
                    return (ObjectNode) json.readTree(event.path("payload").asText());
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        }
        return null;
    }

    private void putSnapshot(ObjectNode commit, ObjectNode snapshot) {
        try {
            String text = json.writeValueAsString(snapshot);
            for (JsonNode event : commit.withArray("events")) {
                if ("STATE_SNAPSHOT_V1".equals(event.path("type").asText())) {
                    ((ObjectNode) event).put("payload", text);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void mutate(ObjectNode commit, String mutation) {
        switch (mutation) {
            case "dispatch-plan-digest" -> {
                JsonNode event = decisionEvent(commit, "DECISION_DISPATCH_V1");
                if (event == null) {
                    return;
                }
                try {
                    ObjectNode payload = (ObjectNode) json.readTree(event.path("payload").asText());
                    String digest = payload.path("planDigest").asText();
                    payload.put("planDigest", (digest.charAt(0) == '0' ? "1" : "0")
                            + digest.substring(1));
                    ((ObjectNode) event).put("payload", json.writeValueAsString(payload));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            case "dispatch-attempt" -> {
                JsonNode event = decisionEvent(commit, "DECISION_DISPATCH_V1");
                if (event == null) {
                    return;
                }
                try {
                    ObjectNode payload = (ObjectNode) json.readTree(event.path("payload").asText());
                    payload.put("attemptId", "ATT-999");
                    ((ObjectNode) event).put("payload", json.writeValueAsString(payload));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            case "dispatch-removed" -> {
                var events = commit.withArray("events");
                final List<JsonNode> kept = new ArrayList<>();
                for (JsonNode event : events) {
                    if (!"DECISION_DISPATCH_V1".equals(event.path("type").asText())) {
                        kept.add(event);
                    }
                }
                events.removeAll();
                kept.forEach(events::add);
            }
            case "dispatch-added" -> {
                if (!"cmd:t-1".equals(commit.path("commandId").asText())) {
                    return;
                }
                ObjectNode snapshot = snapshotOf(commit);
                ObjectNode decisions = (ObjectNode) snapshot.path("decisions");
                JsonNode existing = decisions.path("dispatches");
                if (!existing.isObject() || existing.isEmpty()) {
                    return;
                }
                ObjectNode dispatch = ((ObjectNode) existing.elements().next()).deepCopy();
                JsonNode target = null;
                for (JsonNode decision : decisions.path("state").path("decisions")) {
                    JsonNode last = decision.path("attempts").get(
                            decision.path("attempts").size() - 1);
                    if (last.path("settled").path("attempts").asLong(0) != 1L) {
                        target = last;
                        dispatch.put("actionId", decision.path("actionId").asText());
                        try {
                            dispatch.put("planDigest", java.util.HexFormat.of().formatHex(
                                    MessageDigest.getInstance("SHA-256").digest(
                                            json.writeValueAsBytes(decision.path("plan")))));
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                        break;
                    }
                }
                if (target == null) {
                    return;
                }
                dispatch.put("attemptId", target.path("attemptId").asText());
                dispatch.put("attemptNo", target.path("attemptNo").asInt());
                dispatch.put("recoveryGeneration",
                        target.path("recoveryGeneration").asLong());
                dispatch.put("inputDigest", target.path("inputDigest").asText());
                dispatch.put("dispatchPosition", snapshot.path("position").asLong());
                dispatch.put("dispatchTimeMillis", snapshot.path("timeMillis").asLong());
                ((ObjectNode) decisions.path("dispatches")).set(
                        target.path("attemptId").asText(), dispatch);
                putSnapshot(commit, snapshot);
            }
            case "reply-outcome" -> {
                JsonNode event = decisionEvent(commit, "DECISION_REPLIED_V1");
                if (event != null) {
                    ((ObjectNode) event).put("payload", "WAIT");
                }
            }
            case "open-command-route" -> {
                if (!commit.path("commandId").asText().startsWith("dec-open:")) {
                    return;
                }
                try {
                    var requests = (com.fasterxml.jackson.databind.node.ArrayNode)
                            json.readTree(commit.path("commandContent").asText());
                    ObjectNode request = (ObjectNode) requests.get(0);
                    ObjectNode route = (ObjectNode) request.path("route");
                    if (route.isEmpty()) {
                        return;
                    }
                    route.put("revision", route.path("revision").asLong() + 1);
                    ((ObjectNode) commit).put("commandContent", json.writeValueAsString(requests));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            case "drop-plan" -> {
                ObjectNode snapshot = snapshotOf(commit);
                if (snapshot == null) {
                    return;
                }
                for (JsonNode decision : snapshot.path("decisions").path("state").path("decisions")) {
                    ((ObjectNode) decision).remove("plan");
                }
                putSnapshot(commit, snapshot);
            }
            case "failure-reason" -> {
                JsonNode event = decisionEvent(commit, "DECISION_FAILED_V1");
                if (event == null) {
                    return;
                }
                try {
                    ObjectNode payload = (ObjectNode) json.readTree(event.path("payload").asText());
                    payload.put("reason", "SCRIPT_MISSING");
                    ((ObjectNode) event).put("payload", json.writeValueAsString(payload));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            case "timeout-reason" -> {
                JsonNode event = decisionEvent(commit, "DECISION_TIMEOUT_V1");
                if (event == null) {
                    return;
                }
                try {
                    ObjectNode payload = (ObjectNode) json.readTree(event.path("payload").asText());
                    payload.put("reason", "LATE");
                    ((ObjectNode) event).put("payload", json.writeValueAsString(payload));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            case "session-generation" -> {
                JsonNode event = decisionEvent(commit, "DECISION_SESSION_OPEN_V1");
                if (event == null) {
                    return;
                }
                try {
                    ObjectNode payload = (ObjectNode) json.readTree(event.path("payload").asText());
                    payload.put("generation", payload.path("generation").asLong() + 1);
                    ((ObjectNode) event).put("payload", json.writeValueAsString(payload));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            case "dispatch-cash-transfer", "rejected-cash-transfer" -> {
                boolean target = "dispatch-cash-transfer".equals(mutation)
                        ? decisionEvent(commit, "DECISION_DISPATCH_V1") != null
                        : "dec-reply:q-wait-again".equals(commit.path("commandId").asText());
                if (!target) {
                    return;
                }
                ObjectNode snapshot = snapshotOf(commit);
                ObjectNode fund = (ObjectNode) snapshot.path("accounts").path(ACC_FUND);
                ObjectNode investor = (ObjectNode) snapshot.path("accounts").path(ACC_INV);
                fund.put("cash", fund.path("cash").decimalValue().subtract(new BigDecimal("1.00")));
                investor.put("cash", investor.path("cash").decimalValue().add(new BigDecimal("1.00")));
                putSnapshot(commit, snapshot);
            }
            case "conflicting-event" -> {
                if (decisionEvent(commit, "DECISION_REPLIED_V1") != null) {
                    ObjectNode event = json.createObjectNode();
                    event.put("eventId", "conflict:" + commit.path("position").asLong());
                    event.put("type", "DECISION_FAILED_V1");
                    event.put("payload", "{}");
                    commit.withArray("events").add(event);
                }
            }
            case "duplicate-audit", "missing-audit" -> {
                if (decisionEvent(commit, "DECISION_REPLIED_V1") == null) {
                    return;
                }
                ObjectNode audit = (ObjectNode) decisionEvent(commit, "DECISION_AUDIT_V1");
                if ("duplicate-audit".equals(mutation)) {
                    ObjectNode duplicate = audit.deepCopy();
                    duplicate.put("eventId", audit.path("eventId").asText() + ":extra");
                    commit.withArray("events").add(duplicate);
                } else {
                    List<JsonNode> kept = new ArrayList<>();
                    for (JsonNode event : commit.withArray("events")) {
                        if (!"DECISION_AUDIT_V1".equals(event.path("type").asText())) {
                            kept.add(event);
                        }
                    }
                    commit.withArray("events").removeAll().addAll(kept);
                }
            }
            default -> throw new IllegalArgumentException("未知变异：" + mutation);
        }
    }

    private static JsonNode decisionEvent(ObjectNode commit, String type) {
        for (JsonNode event : commit.withArray("events")) {
            if (type.equals(event.path("type").asText())) {
                return event;
            }
        }
        return null;
    }

    /**
     * 生成一份含 session/open/dispatch/reply(WAIT+INTENT)/identity-reuse/failure/timeout/
     * 非决定批次/旧回执归档的合法 live 日志。
     */
    private void buildAuditJournal(Path file) throws Exception {
        try (WorldRuntime rt = open(file, auditConfig())) {
            rt.beginDecisionSession("s-1");
            rt.openDecisions("o-1", List.of(
                    new DecisionRun.Request(ALICE, "a-wait", WAKE, route("research", 1, 2)),
                    new DecisionRun.Request(BOB, "b-intent", WAKE, route("research", 1, 2)),
                    new DecisionRun.Request(CAROL, "c-fail", WAKE, route("research", 1, 2)),
                    new DecisionRun.Request(DAVE, "d-late", WAKE, route("research", 1, 2))));

            WorldRuntime.DispatchResult wait = rt.dispatchLiveDecision("p-wait", "a-wait");
            assertEquals("DISPATCHED", wait.verdict());
            assertEquals("RECORDED", rt.acceptDecisionReply("q-wait", reply(wait.call(),
                    "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 20, 5))));
            assertEquals("REJECTED:IDENTITY_REUSE", rt.acceptDecisionReply("q-wait-again",
                    reply(wait.call(), "{\"tool\":\"role.wait\"}",
                            DecisionModel.Usage.unknownCost(1, 21, 5))));

            WorldRuntime.DispatchResult intent = rt.dispatchLiveDecision("p-intent", "b-intent");
            assertEquals("DISPATCHED", intent.verdict());
            assertEquals("RECORDED", rt.acceptDecisionReply("q-intent", reply(intent.call(),
                    "{\"tool\":\"role.target.set\",\"args\":" + TARGET_SET_ARGS + "}",
                    DecisionModel.Usage.unknownCost(1, 40, 20))));

            WorldRuntime.DispatchResult fail = rt.dispatchLiveDecision("p-fail", "c-fail");
            assertEquals("DISPATCHED", fail.verdict());
            assertEquals("TECHNICAL_FAILED:PROVIDER_TIMEOUT", rt.failDecisionAttempt("c-fail",
                    fail.call().attemptNo(), fail.call().attemptId(),
                    fail.call().recoveryGeneration(), "PROVIDER_TIMEOUT"));

            rt.scheduleTransfer("t-1", "task-1", 100_000L, PRODUCT, ACC_FUND, ACC_INV,
                    new BigDecimal("1.00"), 0L);

            WorldRuntime.DispatchResult late = rt.dispatchLiveDecision("p-late", "d-late");
            assertEquals("DISPATCHED", late.verdict());
            Thread.sleep(30L);
            assertEquals(1, rt.expireDecisionTimeouts(), "墙钟超时必须结算一次");
        }
        // 第二代：begin 保守结算旧代次，再归档一条旧回执。
        try (WorldRuntime rt = open(file, auditConfig())) {
            rt.beginDecisionSession("s-2");
            DecisionModel.Decision late = decision(rt, "d-late");
            DecisionModel.Attempt old = late.attempts().get(0);
            DecisionModel.Usage usage = DecisionModel.Usage.unknownCost(1, 15, 5);
            assertEquals("STALE_GENERATION", rt.acceptDecisionReply("q-stale",
                    new DecisionModel.Reply(RUN, "d-late", old.attemptId(), old.attemptNo(),
                            old.recoveryGeneration(), "{\"tool\":\"role.wait\"}", usage,
                            "provider:late")));
        }
    }
}
