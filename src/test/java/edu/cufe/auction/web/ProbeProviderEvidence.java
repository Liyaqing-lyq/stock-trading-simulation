package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.LiveCompletionInbox;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

/** INFORMED_OPERATING 探针专用审计；不参与世界状态、模型输入或旧探针证据。 */
final class ProbeProviderEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String POLICY_FILE = "provider-provenance-policy.json";
    static final String WRITE_FAILURE = "PROBE_EVIDENCE_WRITE_FAILED";
    private final Path out;
    private final TreeMap<String, Integer> reportedModels = new TreeMap<>();
    private int completed, recorded, missingIdentity;
    private volatile boolean failed;
    private final boolean thinking;
    private final MeasuredLengthPolicy measuredLength;
    private final TargetTransportPolicy transport;
    private volatile String stopReason;
    static final String TECHNICAL_FAILURE = "PROBE_PROVIDER_TECHNICAL_FAILURE";
    static final String UNKNOWN_EVIDENCE = "PROBE_PROVIDER_EVIDENCE_UNKNOWN";

    ProbeProviderEvidence(Path out) { this(out, false); }

    ProbeProviderEvidence(Path out, boolean thinking) { this(out, thinking, null); }

    ProbeProviderEvidence(Path out, boolean thinking, MeasuredLengthPolicy measuredLength) {
        this(out,thinking,measuredLength,null);
    }

    ProbeProviderEvidence(Path out, boolean thinking, MeasuredLengthPolicy measuredLength, TargetTransportPolicy transport) {
        this.transport = transport;
        this.out = out;
        this.thinking = thinking;
        this.measuredLength = measuredLength;
    }

    static ObjectNode policy() { return policy(false); }

    static ObjectNode policy(boolean thinking) { return policy(thinking, false); }

    private static ObjectNode policy(boolean thinking, boolean measured) { return policy(thinking,measured,null); }

    private static ObjectNode policy(boolean thinking, boolean measured, String targetPolicy) {
        var result = JSON.createObjectNode().put("schema", thinking ? LiveDecisionProvider.THINKING_PROVENANCE_SCHEMA : LiveDecisionProvider.PROVENANCE_SCHEMA)
                .put("identityScope", "PROVIDER_DECLARED_ONLY").put("underlyingModelVersion", "UNKNOWN");
        if (thinking) result.put("providerPolicy", DeepSeekClient.THINKING_HIGH_POLICY);
        if (measured) result.put("technicalFailurePolicy", MeasuredLengthPolicy.VERSION);
        if (targetPolicy != null) result.put("transportFailurePolicy",targetPolicy);
        return result;
    }

    static void freezePolicy(Path out) throws IOException {
        freezePolicy(out, false);
    }

    static void freezePolicy(Path out, boolean thinking) throws IOException {
        freezePolicy(out, thinking, false);
    }

    static void freezePolicy(Path out, boolean thinking, boolean measured) throws IOException {
        freezePolicy(out,thinking,measured,(String) null);
    }

    static void freezePolicy(Path out, boolean thinking, boolean measured, boolean target) throws IOException {
        freezePolicy(out,thinking,measured,target ? TargetTransportPolicy.VERSION : null);
    }

    static void freezePolicy(Path out, boolean thinking, boolean measured, String targetPolicy) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve(POLICY_FILE).toFile(), policy(thinking, measured, targetPolicy));
    }

    void settleMeasuredLengths(String nextActionId, long timeoutMillis) {
        if (measuredLength == null || stopRequested()) return;
        try {
            if (!measuredLength.confirmBefore(nextActionId, timeoutMillis)) stopReason = UNKNOWN_EVIDENCE;
        } catch (RuntimeException inconsistent) {
            stopReason = UNKNOWN_EVIDENCE;
        }
    }

    int allocate(java.util.concurrent.atomic.AtomicInteger issued, String actionId, long timeoutMillis) {
        if (measuredLength == null) return issued.incrementAndGet();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!stopRequested()) {
            long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            settleMeasuredLengths(actionId, Math.max(0, left));
            // 与 evaluate 中登记新截断共用锁。获得序号的调用已获派发，执行中的调用可以原样结算。
            synchronized (this) {
                if (stopRequested()) return 0;
                if (!measuredLength.awaitingSettlement()) return issued.incrementAndGet();
                if (left <= 0) { stopReason = UNKNOWN_EVIDENCE; return 0; }
            }
        }
        return 0;
    }

    boolean failed() { return failed; }
    boolean stopRequested() { return failed || stopReason != null; }
    String stopReason() { return failed ? WRITE_FAILURE : stopReason; }

    LiveCompletionInbox.Completion evaluate(int index, DecisionRun.Call call, long timeout,
                                            LiveDecisionProvider provider) {
        try {
            if (call.plan() == null || !java.util.Objects.equals(call.plan().route().providerPolicy(),
                    thinking ? DeepSeekClient.THINKING_HIGH_POLICY : null)) throw invalid();
            write("call-", index, call);
        } catch (IOException | RuntimeException failure) {
            failed = true;
            // 尚未发送请求；不能让没有原输入的请求进入真实提供方。
            throw new UncheckedIOException(new IOException(WRITE_FAILURE));
        }
        var audited = provider.evaluateAudited(call, timeout);
        synchronized (this) {
            completed++;
            count(audited.provenance());
            boolean isolated = transport != null && transport.isolatedFailure(call,audited.provenance(),audited.completion());
            boolean measured = measuredLength != null && MeasuredLengthPolicy.matches(call,audited.completion(),audited.provenance());
            if (thinking && stopReason == null && !isolated) {
                if (!audited.completion().businessReply()) {
                    if (!measured || !measuredLength.remember(call, audited.completion().reply()))
                        stopReason = transport == null ? TECHNICAL_FAILURE : TECHNICAL_FAILURE + ":" + audited.completion().failureCode();
                }
                else if (!completeMetadata(audited.provenance(), audited.completion())) stopReason = UNKNOWN_EVIDENCE;
            }
            if (transport != null) {
                transport.record(index,isolated,measured,audited.completion().failureCode());
                if (stopReason == null && transport.tripped()) stopReason = TargetTransportPolicy.STOP;
            }
        }
        // 已收到的完成候选必须原样返回。每项落盘独立尝试，额外证据失败不能覆盖已付费结果。
        try {
            write("reply-", index, audited.completion());
        } catch (IOException | RuntimeException failure) { failed = true; }
        try {
            write("provider-", index, audited.provenance());
            synchronized (this) { recorded++; }
        } catch (IOException | RuntimeException failure) { failed = true; }
        return audited.completion();
    }

    private void write(String prefix, int index, Object value) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve(prefix + index + ".json").toFile(), value);
    }

    private void count(LiveDecisionProvider.ProviderProvenance provenance) {
        if (provenance.providerReportedModel() == null || provenance.requestId() == null) missingIdentity++;
        if (provenance.providerReportedModel() != null) reportedModels.merge(provenance.providerReportedModel(), 1, Integer::sum);
    }

    synchronized ObjectNode summary(int expectedCalls, int physicalInFlight) {
        var result = JSON.createObjectNode().put("schema", thinking ? LiveDecisionProvider.THINKING_PROVENANCE_SCHEMA : LiveDecisionProvider.PROVENANCE_SCHEMA)
                .put("evidenceComplete", !failed && completed == expectedCalls && recorded == completed && physicalInFlight == 0)
                .put("completedCalls", completed).put("recordedCalls", recorded).put("missingIdentityCalls", missingIdentity);
        result.set("reportedModelCounts", JSON.valueToTree(reportedModels));
        if (transport != null) result.set("transport",transport.summary());
        if (failed) result.put("failureCode", WRITE_FAILURE); else result.putNull("failureCode");
        return result.put("underlyingModelVersion", "UNKNOWN");
    }

    /** 新模式接续必须有逐调用完整来源；显式 null 身份允许保留 UNKNOWN，不允许补造。 */
    static void validateSeed(Path seed, JsonNode result) throws IOException { validateSeed(seed, result, false); }

    static void validateSeed(Path seed, JsonNode result, boolean thinking) throws IOException {
        validateSeed(seed, result, thinking, false);
    }

    static void validateSeed(Path seed, JsonNode result, boolean thinking, boolean measured) throws IOException {
        var cfg = result.has("config") ? JSON.treeToValue(result.get("config"),AutonomousLifecycleProbe.Config.class) : null;
        boolean target = cfg != null && cfg.runUntilTarget();
        var parent = target && cfg.seed() != null ? Path.of(cfg.seed()) : null;
        String version = target ? TargetTransportPolicy.frozenVersion(seed) : null;
        if (parent != null && !version.equals(TargetTransportPolicy.frozenVersion(parent))) throw invalid();
        var policy = target ? new TargetTransportPolicy(version,TargetTransportPolicy.inherited(parent)) : null;
        if (!Files.isRegularFile(seed.resolve(POLICY_FILE))
                || !policy(thinking, measured, version).equals(JSON.readTree(seed.resolve(POLICY_FILE).toFile())))
            throw invalid();
        JsonNode calls = result.path("httpCalls");
        if (!calls.isIntegralNumber() || !calls.canConvertToInt() || calls.intValue() < 0 || calls.intValue() > evidenceCallLimit(result))
            throw invalid();
        int total = calls.intValue();
        var expected = new ProbeProviderEvidence(seed,thinking,null,policy);
        try (var paths = Files.list(seed)) {
            var pattern = java.util.regex.Pattern.compile("(call-|reply-|provider-)[0-9]+[.]json");
            var counts = paths.map(p -> pattern.matcher(p.getFileName().toString())).filter(java.util.regex.Matcher::matches)
                    .collect(java.util.stream.Collectors.groupingBy(match -> match.group(1), java.util.stream.Collectors.counting()));
            for (String prefix : java.util.List.of("call-", "reply-", "provider-"))
                if (counts.getOrDefault(prefix, 0L) != total) throw invalid();
        }
        var identities = new java.util.HashSet<String>();
        for (int index = 1; index <= total; index++) {
            JsonNode call = JSON.readTree(seed.resolve("call-" + index + ".json").toFile());
            JsonNode reply = JSON.readTree(seed.resolve("reply-" + index + ".json").toFile());
            JsonNode raw = JSON.readTree(seed.resolve("provider-" + index + ".json").toFile());
            if (call == null || reply == null || raw == null || !call.isObject() || !reply.isObject() || !raw.isObject()) throw invalid();
            var frozenCall = JSON.treeToValue(call, DecisionRun.Call.class);
            var completion = JSON.treeToValue(reply, LiveCompletionInbox.Completion.class);
            if (frozenCall.plan() == null
                    || frozenCall.plan().inputBytes() != frozenCall.inputText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    || !call.equals(JSON.readTree(JSON.writeValueAsBytes(frozenCall)))
                    || !reply.equals(JSON.readTree(JSON.writeValueAsBytes(completion)))) throw invalid();
            var provenance = JSON.treeToValue(raw, LiveDecisionProvider.ProviderProvenance.class);
            if (!raw.equals(JSON.readTree(JSON.writeValueAsBytes(provenance))) || !(thinking ? LiveDecisionProvider.THINKING_PROVENANCE_SCHEMA : LiveDecisionProvider.PROVENANCE_SCHEMA).equals(provenance.schema())) throw invalid();
            String frozenPolicy = frozenCall.plan().route().providerPolicy();
            if (!java.util.Objects.equals(frozenPolicy, thinking ? DeepSeekClient.THINKING_HIGH_POLICY : null)) throw invalid();
            if (thinking) {
                if (!edu.cufe.auction.world.RoleDeliberation.CONTEXTUAL_VERSION.equals(
                        JSON.readTree(frozenCall.inputText()).path("engineering").path("roleContextVersion").asText())) throw invalid();
                validateInvocation(provenance, frozenCall, completion);
                boolean isolated = policy != null && policy.isolatedFailure(frozenCall,provenance,completion);
                boolean length = measured && MeasuredLengthPolicy.matches(frozenCall,completion,provenance);
                if (policy != null) policy.record(index,isolated,length,completion.failureCode());
                if (measured && !completion.businessReply() && !isolated && !length) throw invalid();
            }
            else if (provenance.invocation() != null) throw invalid();
            var identity = JSON.createObjectNode();
            for (String field : new String[]{"runId", "actionId", "attemptId", "attemptNo", "recoveryGeneration"}) {
                JsonNode value = call.path(field);
                if (!value.equals(raw.path(field)) || !value.equals(reply.path("reply").path(field))) throw invalid();
                if (field.equals("attemptNo") || field.equals("recoveryGeneration")) {
                    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) throw invalid();
                } else if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
                identity.set(field, value);
            }
            if (!identities.add(identity.toString()) || !raw.path("requestedModel").isTextual()
                    || !raw.path("requestedModel").equals(call.path("plan").path("route").path("model"))
                    || provenance.requestedModel().isBlank()
                    || !safeIdentifier(raw.path("providerReportedModel")) || !safeIdentifier(raw.path("requestId"))
                    || !raw.path("failureCode").equals(reply.path("failureCode"))) throw invalid();
            if (provenance.outcome() == null) throw invalid();
            if (provenance.outcome().equals("UNKNOWN")) {
                if (provenance.providerReportedModel() != null || provenance.requestId() != null
                        || provenance.failureCode() == null || !(provenance.failureCode().startsWith("HTTP_")
                        || provenance.failureCode().equals("LOCAL_PROVIDER_ERROR"))) throw invalid();
            } else {
                DeepSeekClient.Outcome outcome;
                try { outcome = DeepSeekClient.Outcome.valueOf(provenance.outcome()); }
                catch (IllegalArgumentException invalid) { throw invalid(); }
                String failure = outcome == DeepSeekClient.Outcome.COMPLETE ? null : "PROVIDER_" + outcome.name();
                if (!java.util.Objects.equals(failure, provenance.failureCode())) throw invalid();
            }
            expected.completed++; expected.recorded++; expected.count(provenance);
        }
        if (policy != null && policy.tripped() || !expected.summary(total, 0).equals(result.path("providerProvenance"))) throw invalid();
    }

    static int evidenceCallLimit(JsonNode result) throws IOException {
        return result.has("config") ? JSON.treeToValue(result.get("config"), AutonomousLifecycleProbe.Config.class).callLimit() : 3000;
    }

    private static boolean completeMetadata(LiveDecisionProvider.ProviderProvenance provenance,
                                            LiveCompletionInbox.Completion completion) {
        return provenance.providerReportedModel() != null && provenance.requestId() != null
                && completion.reply().usage() != null && provenance.invocation() != null
                && "stop".equals(provenance.invocation().finishReason());
    }

    private static void validateInvocation(LiveDecisionProvider.ProviderProvenance provenance,
                                           DecisionRun.Call call, LiveCompletionInbox.Completion completion) {
        var invocation = provenance.invocation();
        if (invocation == null || !DeepSeekClient.THINKING_HIGH_POLICY.equals(invocation.providerPolicy())
                || !"enabled".equals(invocation.thinkingType()) || !"high".equals(invocation.reasoningEffort())
                || invocation.maxOutputTokens() != call.plan().route().maxOutputTokens()
                || invocation.maxOutputTokens() != 8192) throw invalid();
        String finish = invocation.finishReason();
        if (finish != null && !java.util.Set.of("stop", "length", "content_filter", "tool_calls").contains(finish)) throw invalid();
        switch (provenance.outcome() == null ? "" : provenance.outcome()) {
            case "COMPLETE" -> { if (!completion.businessReply() || !completeMetadata(provenance, completion)) throw invalid(); }
            case "LENGTH" -> { if (!"length".equals(finish)) throw invalid(); }
            case "FILTERED" -> { if (!"content_filter".equals(finish)) throw invalid(); }
            case "EMPTY" -> { if (finish != null && !"stop".equals(finish)) throw invalid(); }
            case "TOOL_CALLS" -> { }
            case "UNKNOWN" -> {
                if (finish != null || provenance.failureCode() == null) throw invalid();
                if (!"LOCAL_PROVIDER_ERROR".equals(provenance.failureCode())) {
                    try { DeepSeekClient.Failure.Code.valueOf(provenance.failureCode().startsWith("HTTP_") ? provenance.failureCode().substring(5) : ""); }
                    catch (IllegalArgumentException unknownFailure) { throw invalid(); }
                }
            }
            default -> throw invalid();
        }
        if (!"COMPLETE".equals(provenance.outcome()) && (completion.businessReply() || !completion.reply().body().isEmpty())) throw invalid();
        if (!LiveDecisionProvider.USAGE_SOURCE.equals(completion.reply().usageSource())
                || !"UNKNOWN".equals(provenance.outcome()) && completion.reply().usage() == null) throw invalid();
    }

    private static boolean safeIdentifier(JsonNode value) {
        return value.isNull() || value.isTextual() && value.textValue().matches("[A-Za-z0-9_.:-]{1,64}");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("种子提供方来源证据缺失或不一致");
    }
}
