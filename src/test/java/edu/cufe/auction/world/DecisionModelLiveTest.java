package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 正式纯核：未知费用、profile 隔离、live 预留与超额冻结。
 *
 * <p>全部直接测试 {@link DecisionModel} 现有公开入口，不再造平行状态机。
 * 旧契约（四参 Usage、七参 Config、旧 JSON、旧 covers）保持不变；新能力只在
 * {@code Profile.LIVE_HTTP_V1} 下生效。</p>
 */
class DecisionModelLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SEED = 7L;

    // ---------------------------------------------------------------- 旧契约

    @Test
    @DisplayName("旧四参 Usage 仍拒绝 null 费用，且 JSON 不含新字段")
    void legacyUsageKeepsInvariants() throws Exception {
        assertThrows(RuntimeException.class, () -> new DecisionModel.Usage(1, 0, 0, null));

        DecisionModel.Usage usage = new DecisionModel.Usage(1, 20, 10, new BigDecimal("1.50"));
        assertEquals("1.50", usage.cost().toPlainString());
        assertEquals(DecisionModel.Usage.zero().cost(), new BigDecimal("0.00"));

        String json = JSON.writeValueAsString(usage);
        assertEquals("{\"attempts\":1,\"inputTokens\":20,\"outputTokens\":10,\"cost\":1.50}", json);
        assertEquals(usage, JSON.readValue(json, DecisionModel.Usage.class));
        assertFalse(json.contains("costStatus"));
    }

    @Test
    @DisplayName("旧 covers 金额不足为假；金额足够为真")
    void legacyCoversUnchanged() {
        DecisionModel.Usage cap = new DecisionModel.Usage(1, 10, 10, new BigDecimal("1.00"));
        assertTrue(cap.covers(new DecisionModel.Usage(1, 10, 10, new BigDecimal("0.99"))));
        assertFalse(cap.covers(new DecisionModel.Usage(1, 10, 10, new BigDecimal("1.01"))));
        assertFalse(cap.covers(new DecisionModel.Usage(1, 11, 10, new BigDecimal("0.01"))));
    }

    @Test
    @DisplayName("旧七参 Config 仍只接受 scripted，JSON 不出现 profile 键")
    void legacyConfigStillScriptedOnly() throws Exception {
        DecisionModel.Config config = legacyConfig();
        assertNull(config.profile());
        String json = JSON.writeValueAsString(config);
        assertFalse(json.contains("profile"));
        DecisionModel.Config back = JSON.readValue(json, DecisionModel.Config.class);
        assertNull(back.profile());

        assertThrows(IllegalArgumentException.class,
                () -> legacyConfigWithProvider(DecisionModel.PROVIDER_SCRIPTED, "x", "v1",
                        new BigDecimal("1.00")));
        assertThrows(IllegalArgumentException.class,
                () -> legacyConfigWithProvider("DEEPSEEK_HTTP", "x", "v1",
                        new BigDecimal("0.00")), "旧七参构造不能借新provider常量启用live");
    }

    // ---------------------------------------------------------------- 未知费用

    @Test
    @DisplayName("unknownCost 工厂：费用为 null、状态 UNKNOWN，JSON 显式表达")
    void unknownCostFactory() throws Exception {
        DecisionModel.Usage unknown = DecisionModel.Usage.unknownCost(1, 20, 10);
        assertNull(unknown.cost());
        assertEquals(DecisionModel.CostStatus.UNKNOWN, unknown.costStatus());
        assertFalse(unknown.equals(DecisionModel.Usage.zero()));

        String json = JSON.writeValueAsString(unknown);
        assertTrue(json.contains("\"cost\":null"));
        assertTrue(json.contains("\"costStatus\":\"UNKNOWN\""));
        assertEquals(unknown, JSON.readValue(json, DecisionModel.Usage.class));
    }

    @Test
    @DisplayName("UNKNOWN 配数值费用、或 null 费用无状态，都必须拒绝")
    void mixedCostStatesRejected() {
        assertThrows(RuntimeException.class, () -> new DecisionModel.Usage(1, 0, 0,
                new BigDecimal("0.01"), DecisionModel.CostStatus.UNKNOWN));
        assertThrows(RuntimeException.class,
                () -> new DecisionModel.Usage(1, 0, 0, null, null));
        assertEquals(new DecisionModel.Usage(1, 0, 0, new BigDecimal("0.01")),
                new DecisionModel.Usage(1, 0, 0, new BigDecimal("0.01"), null),
                "缺status是旧已知费用语义，正费用Usage仍合法；仅脚本Config预算限制零费用");
    }

    @Test
    @DisplayName("未知费用在加减中传播，计数保持精确；旧 covers 遇未知为假")
    void unknownArithmetic() {
        DecisionModel.Usage unknown = DecisionModel.Usage.unknownCost(1, 20, 10);
        DecisionModel.Usage known = new DecisionModel.Usage(1, 5, 5, new BigDecimal("0.10"));

        DecisionModel.Usage sum = unknown.plus(known);
        assertNull(sum.cost());
        assertEquals(2, sum.attempts());
        assertEquals(25, sum.inputTokens());
        assertEquals(15, sum.outputTokens());

        DecisionModel.Usage diff = unknown.minus(known);
        assertNull(diff.cost());
        assertEquals(15, diff.inputTokens());

        assertFalse(unknown.covers(known));
        assertFalse(known.covers(unknown));
        assertFalse(DecisionModel.Usage.zero().covers(unknown));

        assertThrows(ArithmeticException.class, () -> new DecisionModel.Usage(
                Long.MAX_VALUE, 0, 0, new BigDecimal("0.00"))
                .plus(new DecisionModel.Usage(1, 0, 0, new BigDecimal("0.00"))));
    }

    // ---------------------------------------------------------------- 新 profile

    @Test
    @DisplayName("新 profile 只接受 DEEPSEEK_HTTP 与未知费用预算")
    void liveConfigRules() throws Exception {
        DecisionModel.Config live = liveConfig(10, 10_000, 10_000);
        assertEquals(DecisionModel.Profile.LIVE_HTTP_V1, live.profile());
        String json = JSON.writeValueAsString(live);
        assertTrue(json.contains("\"profile\":\"LIVE_HTTP_V1\""));
        assertEquals(live.profile(), JSON.readValue(json, DecisionModel.Config.class).profile());

        assertThrows(IllegalArgumentException.class, () -> new DecisionModel.Config(
                DecisionModel.PROVIDER_SCRIPTED, "bad", "v1", Map.of("wake", 1L), Map.of(),
                limits(), liveBudget(10, 10_000, 10_000), DecisionModel.Profile.LIVE_HTTP_V1));

        assertThrows(IllegalArgumentException.class, () -> new DecisionModel.Config(
                "DEEPSEEK_HTTP", "bad", "v1", Map.of("wake", 1L), Map.of(), limits(),
                knownBudget(), DecisionModel.Profile.LIVE_HTTP_V1));
    }

    @Test
    @DisplayName("旧档案不能带未知费用预算；新档案不能带 0.00 费用预算")
    void budgetCostRulesAreSeparated() {
        assertThrows(IllegalArgumentException.class, () -> new DecisionModel.Config(
                DecisionModel.PROVIDER_SCRIPTED, "x", "v1", Map.of("wake", 1L), Map.of(),
                limits(), liveBudget(10, 10_000, 10_000), null));
        assertThrows(IllegalArgumentException.class, () -> new DecisionModel.Config(
                "DEEPSEEK_HTTP", "x", "v1", Map.of("wake", 1L), Map.of(), limits(),
                knownBudget(), DecisionModel.Profile.LIVE_HTTP_V1));
    }

    // ---------------------------------------------------------------- live 预留

    @Test
    @DisplayName("live 开决定按 token 预留：预算够则接受，未结算计数为零且费用未知")
    void liveOpenDecisionUsesTokenBudget() {
        DecisionModel.State state = openLive(liveConfig(10, 10_000, 10_000));
        DecisionModel.Result result = DecisionModel.openDecision(state, request("a1"), 0L, 1_000L);

        assertEquals(DecisionModel.Verdict.ACCEPTED, result.verdict(), result.reason());
        DecisionModel.Attempt attempt = result.decision().lastAttempt();
        assertNull(attempt.reserved().cost(), "live 预留费用未知");
        assertEquals(DecisionModel.CostStatus.UNKNOWN, attempt.reserved().costStatus());
        assertEquals(DecisionModel.Usage.unknownCost(0, 0, 0), attempt.settled(),
                "live未结算与起点累计都不能写成已知0元");
        assertEquals(DecisionModel.Usage.unknownCost(0, 0, 0), state.runUsage());
        assertFalse(attempt.settledDone());
        assertTrue(DecisionModel.liveBudgetBlocked(result.state()) == false);
    }

    @Test
    @DisplayName("live token 预算不足时不创建可派发尝试")
    void liveBudgetExhaustedBeforeAttempt() {
        DecisionModel.State state = openLive(liveConfig(0, 0, 0));
        DecisionModel.Result result = DecisionModel.openDecision(state, request("a1"), 0L, 1_000L);

        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, result.verdict());
        assertTrue(result.decision().attempts().isEmpty(), "预算不足不得创建尝试");
    }

    @Test
    @DisplayName("live 合法 wait 回执：按实际计数结算，费用保持未知")
    void liveWaitReplySettlesWithinReservation() {
        DecisionModel.State opened = openLive(liveConfig(10, 10_000, 10_000));
        DecisionModel.Result decision = DecisionModel.openDecision(opened, request("a1"), 0L, 1_000L);
        DecisionModel.Attempt attempt = decision.decision().lastAttempt();

        DecisionModel.Result replied = DecisionModel.submitReply(decision.state(), new DecisionModel.Reply(
                RUN, "a1", attempt.attemptId(), 1, decision.state().recoveryGeneration(),
                "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 60, 40), "provider"));

        assertEquals(DecisionModel.Verdict.ACCEPTED, replied.verdict(), replied.reason());
        DecisionModel.Attempt settled = replied.decision().lastAttempt();
        assertTrue(settled.settledDone());
        assertNull(settled.settled().cost(), "live 结算费用仍未知");
        assertEquals(60, settled.settled().inputTokens());
        assertEquals(DecisionModel.Outcome.WAIT, settled.outcome());
        assertFalse(DecisionModel.liveBudgetBlocked(replied.state()));
    }

    @Test
    @DisplayName("已知超预留：保留声明、按预留结算、不自动重试、全局冻结")
    void liveOverrunFreezesFurtherCalls() {
        DecisionModel.State opened = openLive(liveConfig(10, 10_000, 10_000));
        DecisionModel.Result decision = DecisionModel.openDecision(opened, request("a1"), 0L, 1_000L);
        DecisionModel.Attempt attempt = decision.decision().lastAttempt();
        long limit = attempt.reserved().inputTokens();

        DecisionModel.Result replied = DecisionModel.submitReply(decision.state(), new DecisionModel.Reply(
                RUN, "a1", attempt.attemptId(), 1, decision.state().recoveryGeneration(),
                "{\"tool\":\"role.wait\"}",
                DecisionModel.Usage.unknownCost(1, limit + 1, 10), "provider"));

        DecisionModel.Attempt settled = replied.decision().lastAttempt();
        assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, settled.outcome());
        assertTrue(settled.reason().contains(
                DecisionModel.REASON_USAGE_EXCEEDS_RESERVATION), settled.reason());
        assertNotNull(settled.declaredUsage(), "声明用量必须保留");
        assertEquals(limit + 1, settled.declaredUsage().inputTokens(), "声明不得截断为预留");
        assertEquals(limit, settled.settled().inputTokens(), "按预留保守结算");
        assertEquals(1, replied.decision().attempts().size(), "超额不得自动建立下一尝试");
        assertEquals(DecisionModel.Status.EXPIRED, replied.decision().status());

        assertTrue(DecisionModel.liveBudgetBlocked(replied.state()), "超预留后必须全局阻断");

        DecisionModel.Result next = DecisionModel.openDecision(replied.state(), request("a2"), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, next.verdict());
        assertTrue(next.decision().attempts().isEmpty());
    }

    @Test
    @DisplayName("另一个在途尝试结算成功也不能解除冻结或触发 retry")
    void otherInFlightReplyDoesNotUnblock() {
        DecisionModel.State opened = openLive(liveConfig(10, 10_000, 10_000));
        DecisionModel.Result first = DecisionModel.openDecision(opened, request("a1"), 0L, 1_000L);
        DecisionModel.Result second = DecisionModel.openDecision(first.state(), request("a2"), 0L, 1_000L);
        DecisionModel.Attempt firstAttempt = first.decision().lastAttempt();
        DecisionModel.Attempt secondAttempt = second.decision().lastAttempt();

        DecisionModel.Result overrun = DecisionModel.submitReply(second.state(), new DecisionModel.Reply(
                RUN, "a1", firstAttempt.attemptId(), 1, second.state().recoveryGeneration(),
                "{\"tool\":\"role.wait\"}",
                DecisionModel.Usage.unknownCost(1, firstAttempt.reserved().inputTokens() + 5, 1),
                "provider"));
        assertTrue(DecisionModel.liveBudgetBlocked(overrun.state()));

        DecisionModel.Result clean = DecisionModel.submitReply(overrun.state(), new DecisionModel.Reply(
                RUN, "a2", secondAttempt.attemptId(), 1, overrun.state().recoveryGeneration(),
                "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 10, 5), "provider"));
        assertTrue(DecisionModel.liveBudgetBlocked(clean.state()), "合法回执不能解除冻结");

        DecisionModel.Result again = DecisionModel.openDecision(clean.state(), request("a3"), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, again.verdict());
    }

    @Test
    @DisplayName("recover/beginSession 不能绕过冻结")
    void recoverAndBeginSessionCannotBypass() {
        DecisionModel.State opened = openLive(liveConfig(10, 10_000, 10_000));
        DecisionModel.Result decision = DecisionModel.openDecision(opened, request("a1"), 0L, 1_000L);
        DecisionModel.Attempt attempt = decision.decision().lastAttempt();
        DecisionModel.Result overrun = DecisionModel.submitReply(decision.state(), new DecisionModel.Reply(
                RUN, "a1", attempt.attemptId(), 1, decision.state().recoveryGeneration(),
                "{\"tool\":\"role.wait\"}",
                DecisionModel.Usage.unknownCost(1, attempt.reserved().inputTokens() + 1, 1), "provider"));
        assertTrue(DecisionModel.liveBudgetBlocked(overrun.state()));

        DecisionModel.State recovered = DecisionModel.recover(overrun.state());
        assertTrue(DecisionModel.liveBudgetBlocked(recovered));
        DecisionModel.State resumed = DecisionModel.beginSession(recovered).state();
        assertTrue(DecisionModel.liveBudgetBlocked(resumed));

        DecisionModel.Result next = DecisionModel.openDecision(resumed, request("a2"), 0L, 1_000L);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, next.verdict());
    }

    @Test
    @DisplayName("同一回执重复提交是 DUPLICATE，累计不翻倍")
    void duplicateReplyIsIdempotent() {
        DecisionModel.State opened = openLive(liveConfig(10, 10_000, 10_000));
        DecisionModel.Result decision = DecisionModel.openDecision(opened, request("a1"), 0L, 1_000L);
        DecisionModel.Attempt attempt = decision.decision().lastAttempt();
        DecisionModel.Reply reply = new DecisionModel.Reply(RUN, "a1", attempt.attemptId(), 1,
                decision.state().recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                DecisionModel.Usage.unknownCost(1, 60, 40), "provider");

        DecisionModel.Result first = DecisionModel.submitReply(decision.state(), reply);
        DecisionModel.Result second = DecisionModel.submitReply(first.state(), reply);

        assertEquals(DecisionModel.Verdict.DUPLICATE, second.verdict());
        assertEquals(first.state().runUsage(), second.state().runUsage());
        assertEquals(1, second.state().runUsage().attempts());
    }

    @Test
    @DisplayName("伪造累计用量无法构造 State")
    void forgedAccumulationRejected() {
        DecisionModel.State opened = openLive(liveConfig(10, 10_000, 10_000));
        DecisionModel.Result decision = DecisionModel.openDecision(opened, request("a1"), 0L, 1_000L);
        DecisionModel.Attempt attempt = decision.decision().lastAttempt();
        DecisionModel.Result replied = DecisionModel.submitReply(decision.state(), new DecisionModel.Reply(
                RUN, "a1", attempt.attemptId(), 1, decision.state().recoveryGeneration(),
                "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 60, 40), "provider"));

        DecisionModel.State honest = replied.state();
        DecisionModel.Usage inflated = new DecisionModel.Usage(5, 500, 500, new BigDecimal("0.00"));
        assertThrows(IllegalArgumentException.class, () -> new DecisionModel.State(
                honest.runId(), honest.seed(), honest.config(), honest.recoveryGeneration(),
                honest.sessionOpen(), honest.decisions(), inflated, honest.personUsage(),
                honest.nextAttemptSequence()));
    }

    @Test
    @DisplayName("脚本档案不受 live 冻结影响：无超额时 liveBudgetBlocked 为假")
    void scriptedStateNeverBlocked() {
        DecisionModel.State scripted = DecisionModel.beginSession(
                DecisionModel.open(RUN, SEED, legacyConfig())).state();
        assertFalse(DecisionModel.liveBudgetBlocked(scripted));
    }

    // ---------------------------------------------------------------- 夹具

    @Test
    @DisplayName("逐人预留先于调用：运行预算富余也不能穿透人物预算，实际结算后释放差额")
    void personReservationControlsNextAttempt() {
        var budget = new DecisionModel.Budget(DecisionModel.Usage.unknownCost(10, 10_000, 10_000),
                Map.of("alice", DecisionModel.Usage.unknownCost(10, 300, 150)));
        var config = new DecisionModel.Config("DEEPSEEK_HTTP", "test", "unpriced-v1",
                Map.of("wake", 1L), Map.of(), limits(), budget, DecisionModel.Profile.LIVE_HTTP_V1);
        var first = DecisionModel.openDecision(openLive(config), request("first"), 0, 1_000);
        assertEquals(DecisionModel.Verdict.ACCEPTED, first.verdict());
        var blocked = DecisionModel.openDecision(first.state(), request("blocked"), 0, 1_000);
        assertEquals(DecisionModel.Verdict.BUDGET_EXHAUSTED, blocked.verdict());
        assertTrue(blocked.decision().attempts().isEmpty());
        var attempt = first.decision().lastAttempt();
        var settled = DecisionModel.submitReply(blocked.state(), new DecisionModel.Reply(RUN, "first",
                attempt.attemptId(), attempt.attemptNo(), attempt.recoveryGeneration(),
                "{\"tool\":\"role.wait\"}", DecisionModel.Usage.unknownCost(1, 50, 20), "PROVIDER"));
        var next = DecisionModel.openDecision(settled.state(), request("next"), 0, 1_000);
        assertEquals(DecisionModel.Verdict.ACCEPTED, next.verdict(), next.reason());
        assertEquals(1, next.decision().attempts().size());
    }

    @Test
    @DisplayName("未知提供方用量全额占用预留，调用预算耗尽后不重试也不退款")
    void missingUsageConservativelyConsumesReservation() {
        var opened = DecisionModel.openDecision(openLive(liveConfig(1, 200, 100)),
                request("missing"), 0, 1_000);
        var attempt = opened.decision().lastAttempt();
        var result = DecisionModel.submitReply(opened.state(), new DecisionModel.Reply(RUN, "missing",
                attempt.attemptId(), attempt.attemptNo(), attempt.recoveryGeneration(),
                "{\"tool\":\"role.wait\"}", null, "UNKNOWN"));
        assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, result.decision().outcome());
        assertEquals(1, result.decision().attempts().size());
        assertEquals(attempt.reserved(), result.state().runUsage());
        assertNull(result.decision().lastAttempt().declaredUsage());
        assertNull(result.state().runUsage().cost());
    }

    @Test
    @DisplayName("已有超额后，另一个在途失败也不得自动重试")
    void inFlightFailureAfterOverrunDoesNotRetry() {
        DecisionModel.State opened = openLive(liveConfigRetryable());
        DecisionModel.Result first = DecisionModel.openDecision(opened, request("a1"), 0L, 1_000L);
        DecisionModel.Result second = DecisionModel.openDecision(first.state(), request("a2"), 0L, 1_000L);
        DecisionModel.Attempt firstAttempt = first.decision().lastAttempt();
        DecisionModel.Attempt secondAttempt = second.decision().lastAttempt();

        DecisionModel.Result overrun = DecisionModel.submitReply(second.state(), new DecisionModel.Reply(
                RUN, "a1", firstAttempt.attemptId(), 1, second.state().recoveryGeneration(),
                "{\"tool\":\"role.wait\"}",
                DecisionModel.Usage.unknownCost(1, firstAttempt.reserved().inputTokens() + 1, 1),
                "provider"));
        assertTrue(DecisionModel.liveBudgetBlocked(overrun.state()));

        DecisionModel.Result failed = DecisionModel.failAttempt(overrun.state(), "a2",
                secondAttempt.attemptNo(), secondAttempt.attemptId(),
                overrun.state().recoveryGeneration(), "PROVIDER_TIMEOUT");

        assertEquals(1, failed.decision().attempts().size(),
                "已有超额时不得为另一个在途尝试创建 retry");
        assertEquals(DecisionModel.Status.EXPIRED, failed.decision().status());
    }

    @Test
    @DisplayName("超额后 recover+beginSession 再 resume 旧未结算尝试也不得重试")
    void resumeAfterRecoveryCannotRetryWhenBlocked() {
        DecisionModel.State opened = openLive(liveConfigRetryable());
        DecisionModel.Result first = DecisionModel.openDecision(opened, request("a1"), 0L, 1_000L);
        DecisionModel.Result second = DecisionModel.openDecision(first.state(), request("a2"), 0L, 1_000L);
        DecisionModel.Attempt firstAttempt = first.decision().lastAttempt();

        DecisionModel.Result overrun = DecisionModel.submitReply(second.state(), new DecisionModel.Reply(
                RUN, "a1", firstAttempt.attemptId(), 1, second.state().recoveryGeneration(),
                "{\"tool\":\"role.wait\"}",
                DecisionModel.Usage.unknownCost(1, firstAttempt.reserved().inputTokens() + 1, 1),
                "provider"));
        assertTrue(DecisionModel.liveBudgetBlocked(overrun.state()));

        DecisionModel.State recovered = DecisionModel.recover(overrun.state());
        DecisionModel.State resumed = DecisionModel.beginSession(recovered).state();
        assertTrue(DecisionModel.liveBudgetBlocked(resumed));

        DecisionModel.Result resumedDecision = DecisionModel.resumeDecision(resumed, "a2");
        assertEquals(1, resumedDecision.decision().attempts().size(),
                "超额后恢复也不得为旧未结算尝试创建 retry");
        assertEquals(DecisionModel.Status.EXPIRED, resumedDecision.decision().status());
        assertTrue(DecisionModel.liveBudgetBlocked(resumedDecision.state()));
    }

    @Test
    @DisplayName("live 回执不能把费用未知洗成已知零；原声明保留为技术失败")
    void liveKnownCostIsInvalidDeclaration() {
        var opened = DecisionModel.openDecision(openLive(liveConfig(10, 10_000, 10_000)),
                request("cost"), 0, 1_000);
        var attempt = opened.decision().lastAttempt();
        var declared = new DecisionModel.Usage(1, 60, 40, new BigDecimal("0.00"));
        var result = DecisionModel.submitReply(opened.state(), new DecisionModel.Reply(RUN,
                "cost", attempt.attemptId(), 1, attempt.recoveryGeneration(),
                "{\"tool\":\"role.wait\"}", declared, "provider"));
        var settled = result.decision().attempts().get(0);
        assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, settled.outcome());
        assertEquals(declared, settled.declaredUsage());
        assertEquals(attempt.reserved(), settled.settled());
        assertNull(result.state().runUsage().cost());
    }

    @Test
    @DisplayName("未结算零计数也必须符合档案费用语义；合法 JSON 对照可恢复")
    void pendingCostStatusCannotCrossProfiles() throws Exception {
        for (boolean live : List.of(false, true)) {
            var cap = new DecisionModel.Usage(10, 10_000, 10_000, new BigDecimal("0.00"));
            var config = live ? liveConfig(10, 10_000, 10_000)
                    : new DecisionModel.Config("SCRIPTED", "old", "free", Map.of("wake", 1L),
                    Map.of(), limits(), new DecisionModel.Budget(cap, Map.of("alice", cap)));
            var state = DecisionModel.openDecision(openLive(config), request("pending"), 0, 1_000).state();
            assertEquals(state, JSON.readValue(JSON.writeValueAsBytes(state), DecisionModel.State.class));
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(state);
            var settled = (com.fasterxml.jackson.databind.node.ObjectNode)
                    node.get("decisions").get(0).get("attempts").get(0).get("settled");
            if (live) {
                settled.put("cost", BigDecimal.ZERO);
                settled.remove("costStatus");
            } else {
                settled.putNull("cost");
                settled.put("costStatus", "UNKNOWN");
            }
            assertThrows(Exception.class, () -> JSON.treeToValue(node, DecisionModel.State.class));
        }
    }

    @Test
    @DisplayName("旧档案拒绝新 UNKNOWN 回执，保持旧状态及序列化形态")
    void legacyReplyCannotIntroduceUnknownCostSchema() {
        var cap = new DecisionModel.Usage(10, 10_000, 10_000, new BigDecimal("0.00"));
        var config = new DecisionModel.Config("SCRIPTED", "old", "free", Map.of("wake", 1L),
                Map.of(), limits(), new DecisionModel.Budget(cap, Map.of("alice", cap)));
        var opened = DecisionModel.openDecision(openLive(config), request("old"), 0, 1_000);
        var a = opened.decision().lastAttempt();
        var result = DecisionModel.submitReply(opened.state(), new DecisionModel.Reply(RUN, "old",
                a.attemptId(), 1, a.recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                DecisionModel.Usage.unknownCost(1, 60, 40), "provider"));
        assertEquals(DecisionModel.Verdict.REJECTED, result.verdict());
        assertEquals(opened.state(), result.state());
    }

    private static final String RUN = "live-test-run";

    private static DecisionModel.Limits limits() {
        return new DecisionModel.Limits(2, 20_000, 20_000, 200, 100, 4, 2, 15_000);
    }

    private static DecisionModel.Budget liveBudget(long attempts, long input, long output) {
        DecisionModel.Usage cap = DecisionModel.Usage.unknownCost(attempts, input, output);
        return new DecisionModel.Budget(cap, Map.of("alice",
                DecisionModel.Usage.unknownCost(attempts, input, output)));
    }

    private static DecisionModel.Budget knownBudget() {
        DecisionModel.Usage zero = new DecisionModel.Usage(0, 10_000, 10_000,
                new BigDecimal("0.00"));
        return new DecisionModel.Budget(zero, Map.of("alice", zero));
    }

    private static DecisionModel.Config legacyConfig() {
        DecisionModel.Usage zero = new DecisionModel.Usage(0, 0, 0, new BigDecimal("0.00"));
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted:test",
                "scripted-v1", Map.of("wake", 1L), Map.of(), limits(),
                new DecisionModel.Budget(zero, Map.of("alice", zero)));
    }

    private static DecisionModel.Config legacyConfigWithProvider(String provider, String id,
                                                                 String version,
                                                                 BigDecimal cost) {
        DecisionModel.Usage cap = new DecisionModel.Usage(0, 0, 0, cost);
        return new DecisionModel.Config(provider, id, version, Map.of("wake", 1L), Map.of(),
                limits(), new DecisionModel.Budget(cap, Map.of("alice", cap)));
    }

    private static DecisionModel.Config liveConfig(long attempts, long input, long output) {
        return new DecisionModel.Config("DEEPSEEK_HTTP", "deepseek:test", "pricing-2026-11-07",
                Map.of("wake", 1L), Map.of(), limits(), liveBudget(attempts, input, output),
                DecisionModel.Profile.LIVE_HTTP_V1);
    }

    /** 允许三次尝试的 live 配置：用于验证“已有超额不得自动 retry”。 */
    private static DecisionModel.Config liveConfigRetryable() {
        DecisionModel.Limits retryLimits = new DecisionModel.Limits(3, 20_000, 20_000, 200, 100,
                4, 2, 15_000);
        return new DecisionModel.Config("DEEPSEEK_HTTP", "deepseek:test", "pricing-2026-11-07",
                Map.of("wake", 1L), Map.of(), retryLimits, liveBudget(10, 10_000, 10_000),
                DecisionModel.Profile.LIVE_HTTP_V1);
    }

    private static DecisionModel.State openLive(DecisionModel.Config config) {
        DecisionModel.State opened = DecisionModel.open(RUN, SEED, config);
        return DecisionModel.beginSession(opened).state();
    }

    private static DecisionModel.OpenRequest request(String actionId) {
        List<String> tools = new ArrayList<>(List.of(DecisionModel.TOOL_WAIT));
        return new DecisionModel.OpenRequest(RUN, actionId, "alice", "wake", 1_000L, 0L, 1_000L,
                "tools-v1", tools, "{\"observe\":true}");
    }
}
