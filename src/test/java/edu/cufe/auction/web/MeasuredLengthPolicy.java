package edu.cufe.auction.web;

import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.LiveCompletionInbox;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 工程实验的逐人截断规则。收到提供方凭据与世界实际结算是两个独立阶段。 */
final class MeasuredLengthPolicy {
    static final String VERSION = "measured-length-local-v1";
    private final Supplier<DecisionRun> ledger;
    private final Set<String> closedActions = new HashSet<>();
    private record Pending(DecisionRun.Call call, DecisionModel.Reply reply) { }
    private final Map<String, Pending> awaiting = new HashMap<>();

    MeasuredLengthPolicy(Supplier<DecisionRun> ledger) {
        this.ledger = ledger;
    }

    static boolean matches(DecisionRun.Call call, LiveCompletionInbox.Completion completion,
                           LiveDecisionProvider.ProviderProvenance provenance) {
        return "PROVIDER_LENGTH".equals(completion.failureCode()) && "LENGTH".equals(provenance.outcome())
                && measuredFailure(call, completion, provenance)
                && "length".equals(provenance.invocation().finishReason()) && completion.reply().usage().outputTokens()==8192;
    }

    /** 有身份、完整调用政策、单次用量和同一尝试的提供方失败回执。 */
    static boolean measuredFailure(DecisionRun.Call call, LiveCompletionInbox.Completion completion,
                                   LiveDecisionProvider.ProviderProvenance provenance) {
        var reply = completion.reply();
        var usage = reply.usage();
        var invocation = provenance.invocation();
        var plan = call.plan();
        return plan != null && plan.inputBytes() == call.inputText().getBytes(StandardCharsets.UTF_8).length
                && completion.failureCode() != null && !completion.businessReply()
                && reply.body().isEmpty() && LiveDecisionProvider.USAGE_SOURCE.equals(reply.usageSource())
                && LiveDecisionProvider.THINKING_PROVENANCE_SCHEMA.equals(provenance.schema())
                && completion.failureCode().equals(provenance.failureCode())
                && plan.route().model().equals(provenance.requestedModel())
                && safeId(provenance.providerReportedModel()) && safeId(provenance.requestId())
                && call.runId().equals(reply.runId()) && call.runId().equals(provenance.runId())
                && call.actionId().equals(reply.actionId()) && call.actionId().equals(provenance.actionId())
                && call.attemptId().equals(reply.attemptId()) && call.attemptId().equals(provenance.attemptId())
                && call.attemptNo() == reply.attemptNo() && call.attemptNo() == provenance.attemptNo()
                && call.recoveryGeneration() == reply.recoveryGeneration()
                && call.recoveryGeneration() == provenance.recoveryGeneration()
                && invocation != null
                && "enabled".equals(invocation.thinkingType()) && "high".equals(invocation.reasoningEffort())
                && DeepSeekClient.THINKING_HIGH_POLICY.equals(invocation.providerPolicy())
                && invocation.providerPolicy().equals(plan.route().providerPolicy())
                && invocation.maxOutputTokens() == 8192 && plan.route().maxOutputTokens() == 8192
                && usage != null && usage.attempts() == 1 && usage.inputTokens() > 0 && usage.outputTokens() <= invocation.maxOutputTokens();
    }

    /** 先验证每段真实来源，再将本世界的每次历史截断绑定到已核验的原回执和真实扣量。 */
    void restore(java.nio.file.Path seed) throws java.io.IOException {
        var world = ledger.get();
        var physical = MeasuredLengthEvidence.restore(seed, this, world);
        for (var decision : world.state().decisions()) {
            for (var attempt : decision.attempts()) {
                var key = new LiveCompletionInbox.AttemptKey(decision.runId(), decision.actionId(), attempt.attemptId(),
                        attempt.attemptNo(), attempt.recoveryGeneration());
                if (!physical.contains(key)) {
                    // 只有探针在供应商入口前返回的本地拒绝可以没有外发回执。
                    if (!"PROBE_LIMIT".equals(attempt.usageSource()) || attempt.declaredUsage() != null
                            || !attempt.replyBody().isEmpty() || !java.util.Set.of("PROBE_CALL_LIMIT", "PROBE_EVIDENCE_STOP").contains(attempt.reason()))
                        throw new IllegalArgumentException("世界存在没有逐段提供方来源的已派发尝试");
                    continue;
                }
                if (!"PROVIDER_LENGTH".equals(attempt.reason())) continue;
                var archived = awaiting.get(decision.actionId());
                if (archived == null || !archived.reply().attemptId().equals(attempt.attemptId()))
                    throw new IllegalArgumentException("账本的历史截断缺少原始提供方回执");
            }
        }
        if (!confirmBefore(null, 0)) throw new IllegalArgumentException("历史原始回执与世界实扣金额不相符");
    }

    private static boolean safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_.:-]{1,64}");
    }

    synchronized boolean remember(DecisionRun.Call call, DecisionModel.Reply reply) {
        return !closedActions.contains(reply.actionId()) && awaiting.putIfAbsent(reply.actionId(), new Pending(call, reply)) == null;
    }

    synchronized boolean awaitingSettlement() {
        return !awaiting.isEmpty();
    }

    /** 只读取世界账本。先让原完成候选交还 writer，再在下次外发前确认实扣；写入失败或未结算均不得放行。 */
    boolean confirmBefore(String nextActionId, long timeoutMillis) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            synchronized (this) {
                if (closedActions.contains(nextActionId) || awaiting.containsKey(nextActionId)) return false;
                if (awaiting.isEmpty()) return true;
                var world = ledger.get();
                var it = awaiting.values().iterator();
                while (it.hasNext()) {
                    var pending = it.next();
                    var expected = pending.reply();
                    var decision = DecisionModel.decision(world.state(), expected.actionId()).orElse(null);
                    if (decision == null || !pending.call().plan().equals(decision.plan())
                            || !pending.call().inputText().equals(decision.inputText())
                            || !pending.call().allowedTools().equals(decision.allowedTools())) return false;
                    var attempt = decision.lastAttempt();
                    if (!attempt.attemptId().equals(expected.attemptId()) || attempt.attemptNo() != expected.attemptNo()
                            || attempt.recoveryGeneration() != expected.recoveryGeneration()
                            || !decision.runId().equals(expected.runId())) return false;
                    if (!attempt.settledDone()) continue;
                    // DecisionModel.State 的构造不变量会用每个 settled 重新核算个人与全局用量。
                    if (decision.status() != DecisionModel.Status.EXPIRED || decision.outcome() != DecisionModel.Outcome.TECHNICAL_FAILED
                            || !decision.recordedTool().isEmpty()
                            || world.actionRef(decision.actionId()) != null || !attempt.replyReceived()
                            || attempt.outcome() != DecisionModel.Outcome.TECHNICAL_FAILED
                            || !"PROVIDER_LENGTH".equals(attempt.reason()) || !attempt.replyBody().isEmpty()
                            || !expected.usageSource().equals(attempt.usageSource())
                            || !expected.usage().equals(attempt.declaredUsage()) || !expected.usage().equals(attempt.settled()))
                        return false;
                    it.remove();
                    closedActions.add(expected.actionId());
                }
                if (awaiting.isEmpty()) return true;
            }
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
