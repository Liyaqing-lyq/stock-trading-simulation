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

    ProbeProviderEvidence(Path out) { this.out = out; }

    static ObjectNode policy() {
        return JSON.createObjectNode().put("schema", LiveDecisionProvider.PROVENANCE_SCHEMA)
                .put("identityScope", "PROVIDER_DECLARED_ONLY").put("underlyingModelVersion", "UNKNOWN");
    }

    static void freezePolicy(Path out) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve(POLICY_FILE).toFile(), policy());
    }

    boolean failed() { return failed; }

    LiveCompletionInbox.Completion evaluate(int index, DecisionRun.Call call, long timeout,
                                            LiveDecisionProvider provider) {
        try {
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
        var result = JSON.createObjectNode().put("schema", LiveDecisionProvider.PROVENANCE_SCHEMA)
                .put("evidenceComplete", !failed && completed == expectedCalls && recorded == completed && physicalInFlight == 0)
                .put("completedCalls", completed).put("recordedCalls", recorded).put("missingIdentityCalls", missingIdentity);
        result.set("reportedModelCounts", JSON.valueToTree(reportedModels));
        if (failed) result.put("failureCode", WRITE_FAILURE); else result.putNull("failureCode");
        return result.put("underlyingModelVersion", "UNKNOWN");
    }

    /** 新模式接续必须有逐调用完整来源；显式 null 身份允许保留 UNKNOWN，不允许补造。 */
    static void validateSeed(Path seed, JsonNode result) throws IOException {
        if (!Files.isRegularFile(seed.resolve(POLICY_FILE))
                || !policy().equals(JSON.readTree(seed.resolve(POLICY_FILE).toFile())))
            throw invalid();
        JsonNode calls = result.path("httpCalls");
        if (!calls.isIntegralNumber() || !calls.canConvertToInt() || calls.intValue() < 0 || calls.intValue() > 3000)
            throw invalid();
        int total = calls.intValue();
        var expected = new ProbeProviderEvidence(seed);
        try (var paths = Files.list(seed)) {
            var names = paths.map(p -> p.getFileName().toString()).toList();
            for (String prefix : new String[]{"call-", "reply-", "provider-"}) {
                var matched = names.stream().filter(n -> n.matches(prefix + "[0-9]+[.]json")).toList();
                if (matched.size() != total) throw invalid();
                for (int index = 1; index <= total; index++)
                    if (!matched.contains(prefix + index + ".json") || !Files.isRegularFile(seed.resolve(prefix + index + ".json")))
                        throw invalid();
            }
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
            if (!raw.equals(JSON.readTree(JSON.writeValueAsBytes(provenance))) || !LiveDecisionProvider.PROVENANCE_SCHEMA.equals(provenance.schema())) throw invalid();
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
        if (!expected.summary(total, 0).equals(result.path("providerProvenance"))) throw invalid();
    }

    private static boolean safeIdentifier(JsonNode value) {
        return value.isNull() || value.isTextual() && value.textValue().matches("[A-Za-z0-9_.:-]{1,64}");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("种子提供方来源证据缺失或不一致");
    }
}
