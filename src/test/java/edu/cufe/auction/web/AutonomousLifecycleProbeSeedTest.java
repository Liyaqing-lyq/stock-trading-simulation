package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 接续必须在任何输出或凭据加载前核验冻结身份，并保留持久预算原文。 */
class AutonomousLifecycleProbeSeedTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test void refusesDifferentSourceBeforeCreatingOutput() throws Exception {
        Path seed = seed();
        var config = (ObjectNode) JSON.readTree(Files.readString(seed.resolve("config.json")));
        config.put("sourceCommit", "older-source");
        writeIdentity(seed, config);
        reject(seed);
    }

    @Test void refusesDifferentModeBeforeCreatingOutput() throws Exception {
        Path seed = seed();
        var config = (ObjectNode) JSON.readTree(Files.readString(seed.resolve("config.json")));
        config.put("mode", "OPERATING");
        writeIdentity(seed, config);
        reject(seed);
    }

    @Test void refusesDifferentPromptBeforeCreatingOutput() throws Exception {
        Path seed = seed();
        Files.writeString(seed.resolve("system-prompt.txt"), "a different frozen prompt");
        reject(seed);
    }

    @Test void refusesMissingMalformedAndDisagreeingIdentity() throws Exception {
        for (String damage : new String[]{"missing-config", "missing-prompt", "missing-result-config", "malformed-config", "malformed-result", "wrong-config-type", "disagreement", "unfinished"}) {
            Path seed = seed();
            switch (damage) {
                case "missing-config" -> Files.delete(seed.resolve("config.json"));
                case "missing-prompt" -> Files.delete(seed.resolve("system-prompt.txt"));
                case "missing-result-config" -> Files.writeString(seed.resolve("result.json"), "{\"live\":{\"physicalInFlight\":0}}");
                case "malformed-config" -> Files.writeString(seed.resolve("config.json"), "{");
                case "malformed-result" -> Files.writeString(seed.resolve("result.json"), "{");
                case "wrong-config-type" -> {
                    var config = (ObjectNode) JSON.readTree(Files.readString(seed.resolve("config.json")));
                    config.put("maxHttpCalls", "20"); writeIdentity(seed, config);
                }
                case "disagreement" -> {
                    var result = (ObjectNode) JSON.readTree(Files.readString(seed.resolve("result.json")));
                    ((ObjectNode) result.path("config")).put("sourceCommit", "other-result-source");
                    Files.writeString(seed.resolve("result.json"), result.toString());
                }
                case "unfinished" -> Files.writeString(seed.resolve("call-1.json"), "{}");
                default -> fail(damage);
            }
            reject(seed);
        }
    }

    @Test void validSeedCopiesAccumulatedBudgetAndRoutesWithoutReinitialization() throws Exception {
        Path seed = seed();
        Path out = root.resolve("resumed");
        Path config = config(seed);
        // Deliberately no credentials: validation and copying must complete before key loading.
        assertThrows(java.io.IOException.class, () -> AutonomousLifecycleProbe.main(new String[]{out.toString(), root.resolve("absent-keys").toString(), config.toString()}));
        assertArrayEquals(Files.readAllBytes(seed.resolve("world.journal")), Files.readAllBytes(out.resolve("world.journal")));
        assertArrayEquals(Files.readAllBytes(seed.resolve(SceneRoutes.FILE_NAME)), Files.readAllBytes(out.resolve(SceneRoutes.FILE_NAME)));
    }

    @Test void planningModesFreezeTheirRelativePromptBeforeLoadingKeys() throws Exception {
        for (String mode : new String[]{"PLANNING", "PLANNING_OPERATING", "INFORMED_OPERATING", "CONTEXTUAL_INFORMED_OPERATING"}) {
            Path config = root.resolve(mode + ".json"), out = root.resolve(mode);
            JSON.writeValue(config.toFile(), assertDoesNotThrow(() -> new AutonomousLifecycleProbe.Config(mode, 20, 1, 86400000, 3600000, null, "same-source")));
            assertThrows(java.io.IOException.class, () -> AutonomousLifecycleProbe.main(new String[]{out.toString(), root.resolve("absent-keys").toString(), config.toString()}));
            assertEquals(LiveDecisionProvider.PLANNING_SYSTEM_TEXT, Files.readString(out.resolve("system-prompt.txt")));
        }
    }

    @Test void planningSeedRequiresRelativePromptAndPreservesPersistentBytes() throws Exception {
        Path seed = seed();
        var frozen = (ObjectNode) JSON.readTree(Files.readString(seed.resolve("config.json")));
        frozen.put("mode", "PLANNING_OPERATING"); writeIdentity(seed, frozen);
        Path config = root.resolve("planning-request.json"), out = root.resolve("planning-resume");
        JSON.writeValue(config.toFile(), new AutonomousLifecycleProbe.Config("PLANNING_OPERATING", 20, 1, 86400000, 3600000, seed.toString(), "same-source"));
        assertThrows(IllegalArgumentException.class, () -> AutonomousLifecycleProbe.main(new String[]{out.toString(), root.resolve("absent-keys").toString(), config.toString()}));
        assertFalse(Files.exists(out));
        Files.writeString(seed.resolve("system-prompt.txt"), LiveDecisionProvider.PLANNING_SYSTEM_TEXT);
        assertThrows(java.io.IOException.class, () -> AutonomousLifecycleProbe.main(new String[]{out.toString(), root.resolve("absent-keys").toString(), config.toString()}));
        assertArrayEquals(Files.readAllBytes(seed.resolve("world.journal")), Files.readAllBytes(out.resolve("world.journal")));
        assertArrayEquals(Files.readAllBytes(seed.resolve(SceneRoutes.FILE_NAME)), Files.readAllBytes(out.resolve(SceneRoutes.FILE_NAME)));
    }


    @Test void informedFactoryUsesFrozenManagerInformationAndPlanningManifest() throws Exception {
        var method = assertDoesNotThrow(() -> AutonomousLifecycleProbe.class.getDeclaredMethod("openWorld", String.class, Path.class));
        method.setAccessible(true);
        try (var world = (edu.cufe.auction.world.WorldRuntime) method.invoke(null, "INFORMED_OPERATING", root.resolve("informed-world.journal"))) {
            var manifest = world.profile().manifest();
            assertEquals("web-informed-operating-v1", manifest.runId());
            assertEquals("manager-v1", manifest.dataVersions().get("operating-information"));
            assertEquals("bounded-history-v1", manifest.dataVersions().get("role-input-policy"));
        }
    }

    @Test void informedSeedRejectsMissingOrInconsistentProvenanceBeforeOutputOrKeys() throws Exception {
        for (String damage : new String[]{"missing", "schema", "identity", "requested-model", "outcome", "reply-identity", "policy", "summary", "incomplete", "orphan", "calls-count", "reply-body", "missing-input", "wrong-input-bytes"}) {
            Path seed = informedSeed();
            var sidecar = (ObjectNode) JSON.readTree(seed.resolve("provider-1.json").toFile());
            var result = (ObjectNode) JSON.readTree(seed.resolve("result.json").toFile());
            switch (damage) {
                case "missing" -> Files.delete(seed.resolve("provider-1.json"));
                case "schema" -> {sidecar.put("schema", "provider-provenance-v0"); JSON.writeValue(seed.resolve("provider-1.json").toFile(), sidecar);}
                case "identity" -> {sidecar.put("attemptId", "other-attempt"); JSON.writeValue(seed.resolve("provider-1.json").toFile(), sidecar);}
                case "requested-model" -> {sidecar.put("requestedModel", "deepseek-v4-pro"); JSON.writeValue(seed.resolve("provider-1.json").toFile(), sidecar);}
                case "outcome" -> {sidecar.put("outcome", "LENGTH"); JSON.writeValue(seed.resolve("provider-1.json").toFile(), sidecar);}
                case "reply-identity" -> {
                    var reply = (ObjectNode) JSON.readTree(seed.resolve("reply-1.json").toFile());
                    ((ObjectNode) reply.path("reply")).put("actionId", "other-action"); JSON.writeValue(seed.resolve("reply-1.json").toFile(), reply);
                }
                case "reply-body" -> {
                    var reply = (ObjectNode) JSON.readTree(seed.resolve("reply-1.json").toFile());
                    ((ObjectNode) reply.path("reply")).put("body", ""); JSON.writeValue(seed.resolve("reply-1.json").toFile(), reply);
                }
                case "missing-input" -> {
                    var call = (ObjectNode) JSON.readTree(seed.resolve("call-1.json").toFile());
                    call.remove("inputText"); JSON.writeValue(seed.resolve("call-1.json").toFile(), call);
                }
                case "wrong-input-bytes" -> {
                    var call = (ObjectNode) JSON.readTree(seed.resolve("call-1.json").toFile());
                    ((ObjectNode) call.path("plan")).put("inputBytes", 12); JSON.writeValue(seed.resolve("call-1.json").toFile(), call);
                }
                case "policy" -> Files.writeString(seed.resolve("provider-provenance-policy.json"), "{}");
                case "summary" -> {((ObjectNode)result.path("providerProvenance")).put("recordedCalls", 0); JSON.writeValue(seed.resolve("result.json").toFile(), result);}
                case "incomplete" -> {((ObjectNode)result.path("providerProvenance")).put("evidenceComplete", false); JSON.writeValue(seed.resolve("result.json").toFile(), result);}
                case "orphan" -> Files.copy(seed.resolve("provider-1.json"), seed.resolve("provider-2.json"));
                case "calls-count" -> {result.put("httpCalls", 2); JSON.writeValue(seed.resolve("result.json").toFile(), result);}
                default -> fail(damage);
            }
            Path out = root.resolve("refused-" + damage);
            Path config = informedConfig(seed, "INFORMED_OPERATING");
            assertThrows(IllegalArgumentException.class, () -> AutonomousLifecycleProbe.main(new String[]{out.toString(), root.resolve("absent-keys").toString(), config.toString()}), damage);
            assertFalse(Files.exists(out), damage);
        }
    }

    @Test void informedSeedAcceptsExplicitUnknownModelAndPreservesPersistentBytes() throws Exception {
        Path seed = informedSeed();
        Path out = root.resolve("informed-resumed"), config = informedConfig(seed, "INFORMED_OPERATING");
        assertThrows(java.io.IOException.class, () -> AutonomousLifecycleProbe.main(new String[]{out.toString(), root.resolve("absent-keys").toString(), config.toString()}));
        assertArrayEquals(Files.readAllBytes(seed.resolve("world.journal")), Files.readAllBytes(out.resolve("world.journal")));
        assertArrayEquals(Files.readAllBytes(seed.resolve(SceneRoutes.FILE_NAME)), Files.readAllBytes(out.resolve(SceneRoutes.FILE_NAME)));
        assertTrue(Files.isRegularFile(out.resolve("provider-provenance-policy.json")));
        assertEquals(JSON.readTree(seed.resolve("provider-provenance-policy.json").toFile()), JSON.readTree(out.resolve("provider-provenance-policy.json").toFile()));
    }


    @Test void informedSeedAcceptsCompleteTechnicalFailureWithUnknownProviderIdentity() throws Exception {
        Path seed = informedSeed();
        var reply = (ObjectNode) JSON.readTree(seed.resolve("reply-1.json").toFile());
        ((ObjectNode) reply.path("reply")).put("body", ""); reply.put("failureCode", "HTTP_SERVICE_UNAVAILABLE");
        JSON.writeValue(seed.resolve("reply-1.json").toFile(), reply);
        var provider = (ObjectNode) JSON.readTree(seed.resolve("provider-1.json").toFile());
        provider.put("outcome", "UNKNOWN").put("failureCode", "HTTP_SERVICE_UNAVAILABLE");
        JSON.writeValue(seed.resolve("provider-1.json").toFile(), provider);
        Path out = root.resolve("failed-call-resumed"), config = informedConfig(seed, "INFORMED_OPERATING");
        assertThrows(java.io.IOException.class, () -> AutonomousLifecycleProbe.main(new String[]{out.toString(), root.resolve("absent-keys").toString(), config.toString()}));
        assertArrayEquals(Files.readAllBytes(seed.resolve("world.journal")), Files.readAllBytes(out.resolve("world.journal")));
    }

    @Test void informedAndOldPlanningSeedsCannotCrossModesDespiteIdenticalPrompt() throws Exception {
        Path seed = informedSeed();
        Path config = informedConfig(seed, "PLANNING_OPERATING"), out = root.resolve("cross-mode");
        assertThrows(IllegalArgumentException.class, () -> AutonomousLifecycleProbe.main(new String[]{out.toString(), root.resolve("absent-keys").toString(), config.toString()}));
        assertFalse(Files.exists(out));
        var frozen = (ObjectNode) JSON.readTree(seed.resolve("config.json").toFile());
        frozen.put("mode", "PLANNING_OPERATING"); writeIdentity(seed, frozen);
        Path reverse = informedConfig(seed, "INFORMED_OPERATING");
        assertThrows(IllegalArgumentException.class, () -> AutonomousLifecycleProbe.main(new String[]{out.toString(), root.resolve("absent-keys").toString(), reverse.toString()}));
        assertFalse(Files.exists(out));
    }

    @Test void contextualFactoryAndSeedStayVersionedAndProvenanceBound() throws Exception {
        var method=AutonomousLifecycleProbe.class.getDeclaredMethod("openWorld",String.class,Path.class);method.setAccessible(true);
        try(var w=(edu.cufe.auction.world.WorldRuntime)method.invoke(null,"CONTEXTUAL_INFORMED_OPERATING",root.resolve("v5.journal"))){
            assertEquals("web-informed-operating-v2",w.profile().manifest().runId());
            assertEquals("deliberation-v5",w.profile().manifest().dataVersions().get("role-context"));
        }
        Path seed=informedSeed();
        Path request=informedConfig(seed,"CONTEXTUAL_INFORMED_OPERATING"),out=root.resolve("v5-resume");
        assertThrows(IllegalArgumentException.class,()->AutonomousLifecycleProbe.main(new String[]{out.toString(),root.resolve("absent-keys").toString(),request.toString()}));
        assertFalse(Files.exists(out));
        var frozen=(ObjectNode)JSON.readTree(seed.resolve("config.json").toFile());frozen.put("mode","CONTEXTUAL_INFORMED_OPERATING");
        var result=(ObjectNode)JSON.readTree(seed.resolve("result.json").toFile());result.set("config",frozen.deepCopy());
        JSON.writeValue(seed.resolve("config.json").toFile(),frozen);JSON.writeValue(seed.resolve("result.json").toFile(),result);
        Path oldRequest=informedConfig(seed,"INFORMED_OPERATING");
        assertThrows(IllegalArgumentException.class,()->AutonomousLifecycleProbe.main(new String[]{out.toString(),root.resolve("absent-keys").toString(),oldRequest.toString()}));
        assertFalse(Files.exists(out));
        byte[] provider=Files.readAllBytes(seed.resolve("provider-1.json"));Files.delete(seed.resolve("provider-1.json"));
        assertThrows(IllegalArgumentException.class,()->AutonomousLifecycleProbe.main(new String[]{out.toString(),root.resolve("absent-keys").toString(),request.toString()}));
        assertFalse(Files.exists(out));Files.write(seed.resolve("provider-1.json"),provider);
        assertThrows(java.io.IOException.class,()->AutonomousLifecycleProbe.main(new String[]{out.toString(),root.resolve("absent-keys").toString(),request.toString()}));
        assertArrayEquals(Files.readAllBytes(seed.resolve("world.journal")),Files.readAllBytes(out.resolve("world.journal")));
        assertArrayEquals(Files.readAllBytes(seed.resolve(SceneRoutes.FILE_NAME)),Files.readAllBytes(out.resolve(SceneRoutes.FILE_NAME)));
        assertTrue(Files.isRegularFile(out.resolve("provider-provenance-policy.json")));
    }

    private Path informedConfig(Path seed, String mode) throws Exception {
        Path config = root.resolve("request-" + mode + "-" + seed.getFileName() + ".json");
        JSON.writeValue(config.toFile(), new AutonomousLifecycleProbe.Config(mode, 20, 1, 86400000, 3600000, seed.toString(), "same-source"));
        return config;
    }
    private Path informedSeed() throws Exception {
        Path seed = seed();
        var frozen = (ObjectNode) JSON.readTree(seed.resolve("config.json").toFile());
        frozen.put("mode", "INFORMED_OPERATING"); writeIdentity(seed, frozen);
        Files.writeString(seed.resolve("system-prompt.txt"), LiveDecisionProvider.PLANNING_SYSTEM_TEXT);
        Files.writeString(seed.resolve("provider-provenance-policy.json"), "{\"schema\":\"provider-provenance-v1\",\"identityScope\":\"PROVIDER_DECLARED_ONLY\",\"underlyingModelVersion\":\"UNKNOWN\"}");
        var route = new edu.cufe.auction.world.DecisionModel.Route("trading", "key-1", "deepseek-flash", 7, 5, 5000, 512, 2);
        var call = new edu.cufe.auction.world.DecisionRun.Call("run-1", "action-1", "attempt-1", 1, 1, "wake", java.util.List.of("role.wait"), "observation", new edu.cufe.auction.world.DecisionModel.ProviderPlan(route, 11));
        var reply = edu.cufe.auction.world.LiveCompletionInbox.Completion.business(new edu.cufe.auction.world.DecisionModel.Reply("run-1", "action-1", "attempt-1", 1, 1, "{\"tool\":\"role.wait\"}", null, "DEEPSEEK_HTTP"));
        JSON.writeValue(seed.resolve("call-1.json").toFile(), call);
        JSON.writeValue(seed.resolve("reply-1.json").toFile(), reply);
        Files.writeString(seed.resolve("provider-1.json"), "{\"schema\":\"provider-provenance-v1\",\"runId\":\"run-1\",\"actionId\":\"action-1\",\"attemptId\":\"attempt-1\",\"attemptNo\":1,\"recoveryGeneration\":1,\"requestedModel\":\"deepseek-flash\",\"providerReportedModel\":null,\"requestId\":null,\"outcome\":\"COMPLETE\",\"failureCode\":null}");
        var result = (ObjectNode) JSON.readTree(seed.resolve("result.json").toFile()); result.put("httpCalls", 1);
        result.set("providerProvenance", JSON.readTree("{\"schema\":\"provider-provenance-v1\",\"evidenceComplete\":true,\"completedCalls\":1,\"recordedCalls\":1,\"missingIdentityCalls\":1,\"reportedModelCounts\":{},\"failureCode\":null,\"underlyingModelVersion\":\"UNKNOWN\"}"));
        JSON.writeValue(seed.resolve("result.json").toFile(), result);
        return seed;
    }

    private void reject(Path seed) throws Exception {
        Path out = root.resolve("rejected-" + seed.getFileName());
        Path config = config(seed);
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> AutonomousLifecycleProbe.main(new String[]{out.toString(), root.resolve("absent-keys").toString(), config.toString()})),
            () -> assertFalse(Files.exists(out), "invalid seed must not create a resumable-looking output directory"));
    }

    private Path config(Path seed) throws Exception {
        Path config = root.resolve("requested-" + seed.getFileName() + ".json");
        JSON.writeValue(config.toFile(), new AutonomousLifecycleProbe.Config("ACCOUNTABLE", 20, 1, 86400000, 3600000, seed.toString(), "same-source"));
        return config;
    }

    private Path seed() throws Exception {
        Path seed = Files.createTempDirectory(root, "seed-");
        var config = JSON.valueToTree(new AutonomousLifecycleProbe.Config("ACCOUNTABLE", 20, 1, 86400000, 3600000, null, "same-source"));
        writeIdentity(seed, (ObjectNode) config);
        Files.writeString(seed.resolve("system-prompt.txt"), LiveDecisionProvider.DELIBERATION_SYSTEM_TEXT);
        Files.writeString(seed.resolve("world.journal"), "persistent run budget already spent: calls=7 tokens=17000\n");
        try (var routes = SceneRoutes.open(seed)) { assertEquals(0, routes.view().revision()); }
        return seed;
    }

    private void writeIdentity(Path seed, ObjectNode config) throws Exception {
        Files.writeString(seed.resolve("config.json"), config.toString());
        var result = JSON.createObjectNode();
        result.set("config", config.deepCopy());
        result.putObject("live").put("physicalInFlight", 0);
        Files.writeString(seed.resolve("result.json"), result.toString());
    }
}
