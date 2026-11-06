package edu.cufe.auction.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2a 正式合同（按 2026-11-07-scene-decision-budget.md 与 Root 的 9 点纠正）。
 *
 * <p>手算基准：{@code inputText} = {@code {"observe":true}} = 16 UTF-8 字节；
 * 输入预留 16+1024 = 1040，输出预留 128，单次 scene 预留 1168。
 * 所有 Route 自身合法（输出≥128、总量≥256），拒绝都来自目标规则而不是非法夹具。</p>
 */
class DecisionModelRouteTest {

    private static final String RUN = "route-test-run";
    private static final String SCENE = "research";
    private static final String OTHER_SCENE = "trading";
    private static final String INPUT_TEXT = "{\"observe\":true}";
    private static final long BYTES = 16L;
    private static final long INPUT_RESERVED = BYTES + 1024L;
    private static final int OUTPUT_RESERVED = 128;
    private static final long CALL_RESERVED = INPUT_RESERVED + OUTPUT_RESERVED;

    // ---------------------------------------------------------------- 旧兼容

    @Test
    @DisplayName("旧 16 参 Decision 显式可构造；scripted 预留费用仍是已知 0.00；旧 JSON 无 plan 键")
    void legacyConstructorsUnchanged() throws Exception {
        DecisionModel.State state = openLegacy();
        DecisionModel.Result opened = DecisionModel.openDecision(state, legacyRequest("legacy-a"),
                0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, opened.verdict(), opened.reason());

        DecisionModel.Attempt attempt = opened.decision().lastAttempt();
        assertNotNull(attempt.reserved().cost(), "scripted 预留费用必须是已知金额");
        assertEquals(new BigDecimal("0.00"), attempt.reserved().cost());
        assertNull(attempt.reserved().costStatus(), "scripted 不带 UNKNOWN 状态");

        DecisionModel.Decision explicit = new DecisionModel.Decision(RUN, "legacy-a", "alice",
                "wake", 1_000L, 1_001L, 0L, 1_000L, "role-decision-tools-v1",
                List.of(DecisionModel.TOOL_WAIT), INPUT_TEXT, opened.decision().inputDigest(),
                DecisionModel.Status.OPEN, DecisionModel.Outcome.NONE, "",
                opened.decision().attempts());
        assertEquals(opened.decision(), explicit, "旧十六参构造顺序不得改变");
        assertNull(explicit.plan());

        String json = DecisionModel.toJson(opened.state());
        assertFalse(json.contains("plan"), "旧 JSON 不得出现 plan 键");
        assertEquals(opened.state(), DecisionModel.fromJson(json));
    }

    // ---------------------------------------------------------------- plan 边界

    @Test
    @DisplayName("脚本档案不能接受合法 plan：拒绝且原状态不变")
    void scriptedProfileRejectsPlan() {
        DecisionModel.State state = openLegacy();
        DecisionModel.ProviderPlan plan = plan(SCENE, 0L, OUTPUT_RESERVED, 256);

        DecisionModel.OpenRequest request = requestWithPlan("scripted-plan", plan);
        DecisionModel.Result result = DecisionModel.openDecision(state, request, 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.REJECTED, result.verdict());
        assertNull(result.decision());
        assertEquals(state, result.state(), "拒绝时状态必须原样返回");
    }

    @Test
    @DisplayName("inputBytes 必须等于冻结输入的 UTF-8 字节数，范围 1..60000")
    void inputBytesMustMatchFrozenInput() {
        DecisionModel.State state = openLive();

        DecisionModel.ProviderPlan wrong = new DecisionModel.ProviderPlan(
                route(SCENE, 0L, OUTPUT_RESERVED, 256), BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> requestWithPlan("bytes-wrong", wrong));

        assertThrows(IllegalArgumentException.class, () -> new DecisionModel.ProviderPlan(
                route(SCENE, 0L, OUTPUT_RESERVED, 256), 0L), "0 字节不合法");
        assertThrows(IllegalArgumentException.class, () -> new DecisionModel.ProviderPlan(
                route(SCENE, 0L, OUTPUT_RESERVED, 256), 60_001L), "超过 60000 字节不合法");

        DecisionModel.Result accepted = DecisionModel.openDecision(state,
                requestWithPlan("bytes-ok", plan(SCENE, 0L, OUTPUT_RESERVED, 4_000)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, accepted.verdict(), accepted.reason());
    }

    @Test
    @DisplayName("预留 = 1040 输入 + 路由输出；输出超过 perCall 上限才拒绝")
    void reservationFollowsPlan() {
        DecisionModel.State state = openLive();
        DecisionModel.Result opened = DecisionModel.openDecision(state,
                requestWithPlan("res-1", plan(SCENE, 0L, OUTPUT_RESERVED, 4_000)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, opened.verdict(), opened.reason());

        DecisionModel.Attempt attempt = opened.decision().lastAttempt();
        assertEquals(INPUT_RESERVED, attempt.reserved().inputTokens());
        assertEquals(OUTPUT_RESERVED, attempt.reserved().outputTokens());
        assertEquals(1, attempt.reserved().attempts());
        assertNull(attempt.reserved().cost(), "live 预留费用未知");

        // Route 自身合法（512≥128、总量≥256），但超过 Config.perCallOutputTokens=256
        DecisionModel.ProviderPlan tooLarge = new DecisionModel.ProviderPlan(
                route(SCENE, 0L, 512, 4_000), BYTES);
        DecisionModel.Result rejected = DecisionModel.openDecision(state,
                requestWithPlan("res-2", tooLarge), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.REJECTED, rejected.verdict());
        assertNull(rejected.decision());
        assertEquals(state, rejected.state());
    }

    @Test
    @DisplayName("同一 action 改 route 是冲突；完全相同计划才是 DUPLICATE")
    void changedPlanIsConflict() {
        DecisionModel.State state = openLive();
        DecisionModel.Result first = DecisionModel.openDecision(state,
                requestWithPlan("same", plan(SCENE, 0L, OUTPUT_RESERVED, 4_000)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, first.verdict());

        DecisionModel.Result changed = DecisionModel.openDecision(first.state(),
                requestWithPlan("same", planWithSlot(SCENE, 0L, OUTPUT_RESERVED, 4_000, "key-2")),
                0L, 1_000L);
        assertEquals(DecisionModel.Verdict.REJECTED, changed.verdict());
        assertEquals(DecisionModel.REASON_ACTION_ID_CONFLICT, changed.reason());

        DecisionModel.Result repeat = DecisionModel.openDecision(first.state(),
                requestWithPlan("same", plan(SCENE, 0L, OUTPUT_RESERVED, 4_000)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.DUPLICATE, repeat.verdict());
    }

    @Test
    @DisplayName("更低 revision 拒绝；同 revision 不同 Route 不得接受")
    void revisionAndRouteIdentity() {
        DecisionModel.State state = openLive();
        DecisionModel.Result higher = DecisionModel.openDecision(state,
                requestWithPlan("rev-5", plan(SCENE, 5L, OUTPUT_RESERVED, 4_000)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, higher.verdict());

        DecisionModel.Result lower = DecisionModel.openDecision(higher.state(),
                requestWithPlan("rev-4", plan(SCENE, 4L, OUTPUT_RESERVED, 4_000)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.REJECTED, lower.verdict());
        assertNull(lower.decision());
        assertEquals(higher.state(), lower.state());

        DecisionModel.Result sameRevOtherRoute = DecisionModel.openDecision(higher.state(),
                requestWithPlan("rev-5b", planWithSlot(SCENE, 5L, OUTPUT_RESERVED, 4_000, "key-3")),
                0L, 1_000L);
        assertFalse(sameRevOtherRoute.verdict() == DecisionModel.Verdict.ACCEPTED,
                "同 revision 同 scene 不得有不同 Route");
    }

    // ---------------------------------------------------------------- scene 预算

    @Test
    @DisplayName("scene cap 恰好等于 1168 合法，少 1 拒绝")
    void sceneCapBoundary() {
        DecisionModel.State state = openLive();
        DecisionModel.Result exact = DecisionModel.openDecision(state,
                requestWithPlan("cap-eq", plan(SCENE, 0L, OUTPUT_RESERVED, (int) CALL_RESERVED)),
                0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, exact.verdict(), exact.reason());

        DecisionModel.Result tight = DecisionModel.openDecision(state,
                requestWithPlan("cap-tight", plan(SCENE, 0L, OUTPUT_RESERVED,
                        (int) CALL_RESERVED - 1)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, tight.verdict());
        assertTrue(tight.decision().attempts().isEmpty());
    }

    @Test
    @DisplayName("scene 已用跨 revision：920+1168，1800 不够、2500 够、256 保留历史")
    void sceneUsageAcrossRevisions() {
        DecisionModel.State state = openLive();
        DecisionModel.Result first = DecisionModel.openDecision(state,
                requestWithPlan("u1", plan(SCENE, 1L, OUTPUT_RESERVED, (int) CALL_RESERVED)),
                0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, first.verdict(), first.reason());

        DecisionModel.Attempt attempt = first.decision().lastAttempt();
        DecisionModel.Result settled = DecisionModel.submitReply(first.state(), new DecisionModel.Reply(
                RUN, "u1", attempt.attemptId(), 1, first.state().recoveryGeneration(),
                "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 900, 20), "provider"));
        assertEquals(DecisionModel.Verdict.ACCEPTED, settled.verdict(), settled.reason());

        assertEquals(900, DecisionModel.sceneUsage(settled.state(), SCENE).inputTokens());
        assertEquals(20, DecisionModel.sceneUsage(settled.state(), SCENE).outputTokens());
        assertNull(DecisionModel.sceneUsage(settled.state(), SCENE).cost(), "scene 费用必须未知");

        DecisionModel.Result tooSmall = DecisionModel.openDecision(settled.state(),
                requestWithPlan("u2", plan(SCENE, 2L, OUTPUT_RESERVED, 1_800)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, tooSmall.verdict(),
                "920+1168=2088 > 1800");
        assertTrue(tooSmall.decision().attempts().isEmpty());

        DecisionModel.Result enough = DecisionModel.openDecision(settled.state(),
                requestWithPlan("u3", plan(SCENE, 3L, OUTPUT_RESERVED, 2_500)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, enough.verdict(), enough.reason());

        DecisionModel.Result lowered = DecisionModel.openDecision(enough.state(),
                requestWithPlan("u4", plan(SCENE, 4L, OUTPUT_RESERVED, 256)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, lowered.verdict(),
                "降到 256 后新预留必然不足");
        assertEquals(900, DecisionModel.sceneUsage(lowered.state(), SCENE).inputTokens(),
                "已用不得因降 cap 清零");
    }

    @Test
    @DisplayName("run 与 person 各自独立不足")
    void runAndPersonAreIndependent() {
        DecisionModel.State personTight = openLive(DecisionModel.Usage.unknownCost(100, 1_000_000,
                500_000), DecisionModel.Usage.unknownCost(100, 1_039, 200));
        DecisionModel.Result personBlocked = DecisionModel.openDecision(personTight,
                requestWithPlan("p1", plan(SCENE, 0L, OUTPUT_RESERVED, 4_000)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, personBlocked.verdict(),
                "person 输入预留1040 > 输入cap1039");

        DecisionModel.State runTight = openLive(DecisionModel.Usage.unknownCost(100, 1_039, 200),
                DecisionModel.Usage.unknownCost(100, 1_000_000, 500_000));
        DecisionModel.Result runBlocked = DecisionModel.openDecision(runTight,
                requestWithPlan("r1", plan(SCENE, 0L, OUTPUT_RESERVED, 4_000)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, runBlocked.verdict(),
                "run 输入预留1040 > 输入cap1039");
    }

    @Test
    @DisplayName("scene calls 上限与跨 scene 独立")
    void sceneCallsAndCrossScene() {
        DecisionModel.State state = openLive();
        DecisionModel.Result first = DecisionModel.openDecision(state,
                requestWithPlan("c1", planWithCalls(SCENE, 0L, 1)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, first.verdict());

        DecisionModel.Result second = DecisionModel.openDecision(first.state(),
                requestWithPlan("c2", planWithCalls(SCENE, 0L, 1)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, second.verdict(),
                "scene maxCalls=1 时第二个请求必须拒绝");

        DecisionModel.Result otherScene = DecisionModel.openDecision(second.state(),
                requestWithPlan("c3", planWithCalls(OTHER_SCENE, 0L, 1)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, otherScene.verdict(),
                "另一个 scene 的额度独立");
        assertEquals(1, DecisionModel.sceneReserved(otherScene.state(), OTHER_SCENE).attempts());
    }

    // ---------------------------------------------------------------- 未知与超额

    @Test
    @DisplayName("未知回执按预留占额；已知超额保留声明并全局停发")
    void unknownAndOverrun() {
        DecisionModel.State state = openLive();
        DecisionModel.Result first = DecisionModel.openDecision(state,
                requestWithPlan("x1", plan(SCENE, 0L, OUTPUT_RESERVED, 4_000)), 0L, 1_000L);
        DecisionModel.Result second = DecisionModel.openDecision(first.state(),
                requestWithPlan("x2", plan(SCENE, 0L, OUTPUT_RESERVED, 4_000)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, second.verdict());

        DecisionModel.Attempt x1 = first.decision().lastAttempt();
        DecisionModel.Result unknown = DecisionModel.submitReply(second.state(),
                new DecisionModel.Reply(RUN, "x1", x1.attemptId(), 1,
                        second.state().recoveryGeneration(), "{\"tool\":\"role.wait\"}", null,
                        "UNKNOWN"));
        assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, unknown.decision().outcome());
        assertTrue(DecisionModel.sceneUsage(unknown.state(), SCENE).inputTokens()
                >= INPUT_RESERVED, "未知按预留占额");

        DecisionModel.Attempt x2 = second.decision().lastAttempt();
        DecisionModel.Result overrun = DecisionModel.submitReply(unknown.state(),
                new DecisionModel.Reply(RUN, "x2", x2.attemptId(), 1,
                        unknown.state().recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                        DecisionModel.Usage.unknownCost(1, INPUT_RESERVED + 1, 10), "provider"));
        assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, overrun.decision().outcome());
        assertEquals(INPUT_RESERVED + 1,
                overrun.decision().lastAttempt().declaredUsage().inputTokens(),
                "声明不得截断为预留");
        assertTrue(DecisionModel.liveBudgetBlocked(overrun.state()));
        assertFalse(DecisionModel.liveBudgetBlocked(unknown.state()));
    }

    // ---------------------------------------------------------------- 恢复与伪造

    @Test
    @DisplayName("JSON 往返保留 plan；篡改 inputBytes 必被拒（先证明字段真的变了）")
    void jsonRoundTripAndForgery() throws Exception {
        DecisionModel.State state = openLive();
        DecisionModel.Result opened = DecisionModel.openDecision(state,
                requestWithPlan("j1", plan(SCENE, 2L, OUTPUT_RESERVED, 4_000)), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, opened.verdict());

        String json = DecisionModel.toJson(opened.state());
        assertTrue(json.contains("\"inputBytes\":" + BYTES), "必须持久 inputBytes");
        DecisionModel.State back = DecisionModel.fromJson(json);
        assertEquals(opened.state(), back);
        assertEquals(INPUT_RESERVED, DecisionModel.sceneReserved(back, SCENE).inputTokens());

        String forged = json.replace("\"inputBytes\":" + BYTES, "\"inputBytes\":1");
        assertFalse(forged.equals(json), "夹具必须真的改到字段");
        assertThrows(RuntimeException.class, () -> DecisionModel.fromJson(forged),
                "篡改输入字节必须拒绝");
    }

    @Test
    @DisplayName("预算不足的零尝试终态保留 plan")
    void zeroAttemptDecisionKeepsPlan() {
        DecisionModel.State state = openLive();
        DecisionModel.ProviderPlan tight = plan(SCENE, 0L, OUTPUT_RESERVED, 256);
        DecisionModel.Result result = DecisionModel.openDecision(state,
                requestWithPlan("z1", tight), 0L, 1_000L);

        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, result.verdict());
        assertTrue(result.decision().attempts().isEmpty());
        assertEquals(tight, result.decision().plan(), "零尝试终态也必须保留计划");
    }

    @Test
    @DisplayName("技术失败后的 retry 沿用原计划预留")
    void retryKeepsFrozenReservation() {
        DecisionModel.State state = openLive();
        DecisionModel.Result opened = DecisionModel.openDecision(state,
                requestWithPlan("rt1", planWithSlot(SCENE, 0L, OUTPUT_RESERVED, 4_000, "key-4")),
                0L, 1_000L);
        assertEquals(DecisionModel.Verdict.ACCEPTED, opened.verdict());
        DecisionModel.Attempt attempt = opened.decision().lastAttempt();

        DecisionModel.Result failed = DecisionModel.failAttempt(opened.state(), "rt1",
                attempt.attemptNo(), attempt.attemptId(),
                opened.state().recoveryGeneration(), "PROVIDER_TIMEOUT");
        DecisionModel.Attempt last = failed.decision().lastAttempt();
        assertEquals(OUTPUT_RESERVED, last.reserved().outputTokens(), "retry 沿用原输出预留");
        assertEquals(INPUT_RESERVED, last.reserved().inputTokens(), "retry 沿用原输入预留");
        assertEquals(2, failed.decision().attempts().size());
        assertFalse(last.attemptId().equals(attempt.attemptId()), "retry 必须是新 attempt");
        assertEquals(opened.decision().plan(), failed.decision().plan());
    }

    @Test
    @DisplayName("路由边界逐项校验，不接受任意提供方或非法槽位")
    void routeBoundsAreRealConstructorChecks() {
        assertEquals(BYTES, INPUT_TEXT.getBytes(StandardCharsets.UTF_8).length);
        for (String scene : List.of("governance", "research", "trading", "media", "evaluation")) {
            assertEquals(scene, route(scene, 0, 128, 256).sceneId());
        }
        assertThrows(IllegalArgumentException.class, () -> route("other", 0, 128, 256));
        assertThrows(IllegalArgumentException.class, () -> route(SCENE, -1, 128, 256));
        assertThrows(IllegalArgumentException.class, () -> route(SCENE, 0, 127, 256));
        assertThrows(IllegalArgumentException.class, () -> route(SCENE, 0, 8193, 10000));
        assertThrows(IllegalArgumentException.class, () -> route(SCENE, 0, 128, 255));
        assertThrows(IllegalArgumentException.class, () -> route(SCENE, 0, 512, 256));
        assertThrows(IllegalArgumentException.class, () -> planWithSlot(SCENE, 0, 128, 256, "key-6"));
        assertThrows(IllegalArgumentException.class, () -> new DecisionModel.Route(SCENE, "key-1",
                "other-model", 0, 10, 4000, 128, 1));
        assertThrows(IllegalArgumentException.class, () -> new DecisionModel.Route(SCENE, "key-1",
                "deepseek-flash", 0, 1, 4000, 128, 2));
    }

    @Test
    @DisplayName("输入使用UTF8字节而非字符；输入单次上限独立生效")
    void unicodeBytesAndInputLimit() {
        String text = "中".repeat(400); // 1200字节，预留2224，超过本夹具perCallInput=2000
        var plan = new DecisionModel.ProviderPlan(route(SCENE, 0, 128, 10000), 1200);
        var request = new DecisionModel.OpenRequest(RUN, "unicode", "alice", "wake", 1000, 0,
                1000, "role-decision-tools-v1", List.of(DecisionModel.TOOL_WAIT), text, plan);
        var state = openLive();
        var rejected = DecisionModel.openDecision(state, request, 0, 1000);
        assertEquals(DecisionModel.Verdict.REJECTED, rejected.verdict());
        assertEquals(state, rejected.state());
        assertThrows(IllegalArgumentException.class, () -> new DecisionModel.OpenRequest(RUN,
                "wrong-chars", "alice", "wake", 1000, 0, 1000, "role-decision-tools-v1",
                List.of(DecisionModel.TOOL_WAIT), text,
                new DecisionModel.ProviderPlan(route(SCENE, 0, 128, 10000), text.length())));
    }

    @Test
    @DisplayName("低预算revision被观察后，旧决定失败不能借旧cap重试；恢复保留限制")
    void lowerCapBlocksRetryAndSurvivesRecovery() {
        var first = DecisionModel.openDecision(openLive(), requestWithPlan("old-cap",
                plan(SCENE, 1, 128, 4000)), 0, 1000);
        var lowered = DecisionModel.openDecision(first.state(), requestWithPlan("lower-cap",
                plan(SCENE, 2, 128, 256)), 0, 1000);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, lowered.verdict());
        assertEquals(2, DecisionModel.latestRoute(lowered.state(), SCENE).orElseThrow().revision());
        assertEquals(first.decision().plan(), lowered.state().decisions().get(0).plan());
        var a = first.decision().lastAttempt();
        var failed = DecisionModel.failAttempt(lowered.state(), "old-cap", 1, a.attemptId(),
                a.recoveryGeneration(), "TIMEOUT");
        assertEquals(1, failed.decision().attempts().size());
        assertEquals(DecisionModel.Status.EXPIRED, failed.decision().status());
        assertEquals(INPUT_RESERVED, DecisionModel.sceneUsage(failed.state(), SCENE).inputTokens());
        assertEquals(0, DecisionModel.sceneReserved(failed.state(), SCENE).attempts());
        var restored = DecisionModel.fromJson(DecisionModel.toJson(lowered.state()));
        var resumed = DecisionModel.resumeDecision(DecisionModel.beginSession(
                DecisionModel.recover(restored)).state(), "old-cap");
        assertEquals(1, resumed.decision().attempts().size());
        assertEquals(DecisionModel.Status.EXPIRED, resumed.decision().status());
    }

    @Test
    @DisplayName("新路线不改旧决定的key/model/output；重试依然冻结原计划")
    void routeChangeDoesNotRewriteRetry() {
        var old = planWithSlot(SCENE, 1, 128, 10000, "key-4");
        var first = DecisionModel.openDecision(openLive(), requestWithPlan("frozen", old), 0, 1000);
        var newer = new DecisionModel.ProviderPlan(new DecisionModel.Route(SCENE, "key-5",
                "deepseek-v4-pro", 2, 10, 10000, 256, 1), BYTES);
        var both = DecisionModel.openDecision(first.state(), requestWithPlan("new-route", newer), 0, 1000);
        assertEquals(DecisionModel.Verdict.ACCEPTED, both.verdict());
        var a = first.decision().lastAttempt();
        var retry = DecisionModel.failAttempt(both.state(), "frozen", 1, a.attemptId(),
                a.recoveryGeneration(), "TIMEOUT");
        assertEquals(2, retry.decision().attempts().size());
        assertEquals(old, retry.decision().plan());
        assertEquals(128, retry.decision().lastAttempt().reserved().outputTokens());
        assertEquals(newer.route(), DecisionModel.latestRoute(retry.state(), SCENE).orElseThrow());
        assertEquals(2, DecisionModel.sceneReserved(retry.state(), SCENE).attempts());
        assertEquals(384, DecisionModel.sceneReserved(retry.state(), SCENE).outputTokens());
    }

    @Test
    @DisplayName("正常结算退预留差额；scene不能借退款恢复已经消耗的调用次数")
    void actualUsageReleasesTokensButNotCallCount() {
        var first = DecisionModel.openDecision(openLive(), requestWithPlan("actual",
                planWithCalls(SCENE, 0, 1)), 0, 1000);
        var a = first.decision().lastAttempt();
        var result = DecisionModel.submitReply(first.state(), new DecisionModel.Reply(RUN,
                "actual", a.attemptId(), 1, a.recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                DecisionModel.Usage.unknownCost(1, 10, 5), "provider"));
        assertEquals(DecisionModel.Usage.unknownCost(1, 10, 5), DecisionModel.sceneUsage(result.state(), SCENE));
        assertEquals(DecisionModel.Usage.unknownCost(0, 0, 0), DecisionModel.sceneReserved(result.state(), SCENE));
        var blocked = DecisionModel.openDecision(result.state(), requestWithPlan("after-call",
                planWithCalls(SCENE, 0, 1)), 0, 1000);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, blocked.verdict());
    }

    @Test
    @DisplayName("输出超plan但未超global仍是超额；阻断新调用并保留准确声明")
    void outputOverPlanFreezesGlobally() {
        var first = DecisionModel.openDecision(openLive(), requestWithPlan("output",
                plan(SCENE, 0, 128, 10000)), 0, 1000);
        var a = first.decision().lastAttempt();
        var over = DecisionModel.submitReply(first.state(), new DecisionModel.Reply(RUN, "output",
                a.attemptId(), 1, a.recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                DecisionModel.Usage.unknownCost(1, 10, 129), "provider"));
        assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, over.decision().outcome());
        assertEquals(1, over.decision().attempts().size());
        assertEquals(129, over.decision().lastAttempt().declaredUsage().outputTokens());
        assertEquals(128, over.decision().lastAttempt().settled().outputTokens());
        assertTrue(DecisionModel.liveBudgetBlocked(over.state()));
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, DecisionModel.openDecision(over.state(),
                requestWithPlan("other-scene", plan(OTHER_SCENE, 0, 128, 10000)), 0, 1000).verdict());
    }

    @Test
    @DisplayName("全局路由revision倒退不能通过换场景逃避")
    void revisionIsGlobalAcrossScenes() {
        var first = DecisionModel.openDecision(openLive(), requestWithPlan("newest",
                plan(SCENE, 5, 128, 10000)), 0, 1000);
        var rejected = DecisionModel.openDecision(first.state(), requestWithPlan("stale-other",
                plan(OTHER_SCENE, 4, 128, 10000)), 0, 1000);
        assertEquals(DecisionModel.Verdict.REJECTED, rejected.verdict());
        assertEquals(first.state(), rejected.state());
    }

    @Test
    @DisplayName("恢复不能接受占额超过全部历史调用上限的场景")
    void restoredSceneCannotExceedAllObservedCallCaps() {
        var first = DecisionModel.openDecision(openLive(), requestWithPlan("calls-a",
                plan(SCENE, 1, 128, 4000)), 0, 1000);
        var second = DecisionModel.openDecision(first.state(), requestWithPlan("calls-b",
                plan(SCENE, 1, 128, 4000)), 0, 1000);
        String legal = DecisionModel.toJson(second.state());
        assertEquals(second.state(), DecisionModel.fromJson(legal));
        String forged = legal.replace("\"maxCalls\":10", "\"maxCalls\":1");
        assertFalse(forged.equals(legal));
        assertThrows(IllegalArgumentException.class, () -> DecisionModel.fromJson(forged));
    }

    @Test
    @DisplayName("恢复不能接受占额超过全部历史token上限的场景")
    void restoredSceneCannotExceedAllObservedTokenCaps() {
        var first = DecisionModel.openDecision(openLive(), requestWithPlan("tokens-a",
                plan(SCENE, 1, 128, 4000)), 0, 1000);
        var second = DecisionModel.openDecision(first.state(), requestWithPlan("tokens-b",
                plan(SCENE, 1, 128, 4000)), 0, 1000);
        String legal = DecisionModel.toJson(second.state());
        assertEquals(second.state(), DecisionModel.fromJson(legal));
        String forged = legal.replace("\"maxTotalTokens\":4000", "\"maxTotalTokens\":1168");
        assertFalse(forged.equals(legal));
        assertThrows(IllegalArgumentException.class, () -> DecisionModel.fromJson(forged));
    }

    // ---------------------------------------------------------------- 夹具

    private static DecisionModel.Limits limits() {
        return new DecisionModel.Limits(3, 60_000, 20_000, 2_000, 256, 4, 2, 15_000);
    }

    private static DecisionModel.Config liveConfig() {
        return liveConfig(DecisionModel.Usage.unknownCost(100, 1_000_000, 500_000),
                DecisionModel.Usage.unknownCost(100, 500_000, 250_000));
    }

    private static DecisionModel.Config liveConfig(DecisionModel.Usage run,
                                                   DecisionModel.Usage person) {
        return new DecisionModel.Config("DEEPSEEK_HTTP", "deepseek:route-test",
                "utf8-plus-1024-v1", Map.of("wake", 1L), Map.of(), limits(),
                new DecisionModel.Budget(run, Map.of("alice", person)),
                DecisionModel.Profile.LIVE_HTTP_V1);
    }

    private static DecisionModel.Config legacyConfig() {
        DecisionModel.Usage cap = new DecisionModel.Usage(2, 1_000, 500, new BigDecimal("0.00"));
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted:route-test",
                "scripted-v1", Map.of("wake", 1L), Map.of(),
                new DecisionModel.Limits(2, 20_000, 20_000, 200, 100, 4, 2, 15_000),
                new DecisionModel.Budget(cap, Map.of("alice", cap)));
    }

    private static DecisionModel.Route route(String sceneId, long revision, int outputTokens,
                                             int maxTotalTokens) {
        return new DecisionModel.Route(sceneId, "key-1", "deepseek-flash", revision,
                10, maxTotalTokens, outputTokens, 1);
    }

    private static DecisionModel.ProviderPlan plan(String sceneId, long revision,
                                                   int outputTokens, int maxTotalTokens) {
        return new DecisionModel.ProviderPlan(
                route(sceneId, revision, outputTokens, maxTotalTokens), BYTES);
    }

    private static DecisionModel.ProviderPlan planWithSlot(String sceneId, long revision,
                                                           int outputTokens, int maxTotalTokens,
                                                           String slotId) {
        return new DecisionModel.ProviderPlan(new DecisionModel.Route(sceneId, slotId,
                "deepseek-flash", revision, 10, maxTotalTokens, outputTokens, 1), BYTES);
    }

    private static DecisionModel.ProviderPlan planWithCalls(String sceneId, long revision,
                                                            int calls) {
        return new DecisionModel.ProviderPlan(new DecisionModel.Route(sceneId, "key-1",
                "deepseek-flash", revision, calls, 100_000, OUTPUT_RESERVED, 1), BYTES);
    }

    private static DecisionModel.OpenRequest legacyRequest(String actionId) {
        return new DecisionModel.OpenRequest(RUN, actionId, "alice", "wake", 1_000L, 0L, 1_000L,
                "role-decision-tools-v1", List.of(DecisionModel.TOOL_WAIT), INPUT_TEXT);
    }

    private static DecisionModel.OpenRequest requestWithPlan(String actionId,
                                                            DecisionModel.ProviderPlan plan) {
        return new DecisionModel.OpenRequest(RUN, actionId, "alice", "wake", 1_000L, 0L, 1_000L,
                "role-decision-tools-v1", List.of(DecisionModel.TOOL_WAIT), INPUT_TEXT, plan);
    }

    private static DecisionModel.State openLive() {
        return openLive(liveConfig());
    }

    private static DecisionModel.State openLive(DecisionModel.Config config) {
        return DecisionModel.beginSession(DecisionModel.open(RUN, 7L, config)).state();
    }

    private static DecisionModel.State openLive(DecisionModel.Usage run, DecisionModel.Usage person) {
        return openLive(liveConfig(run, person));
    }

    private static DecisionModel.State openLegacy() {
        return DecisionModel.beginSession(DecisionModel.open(RUN, 7L, legacyConfig())).state();
    }
}
