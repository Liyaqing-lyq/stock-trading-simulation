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
