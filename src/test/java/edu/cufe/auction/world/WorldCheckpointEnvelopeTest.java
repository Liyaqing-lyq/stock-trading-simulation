package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6-A 修复回归：检查点信封的每个外层约定字段都必须绑定到已验证原帧。
 *
 * <p>独审 F1 的复现是「伪造开局/时间/阶段/整批摘要/事件数/摘要口径/声明后仍返回 OK」。
 * 这里逐项给出期望原因码，并覆盖 cutPosition 的合法区间与等价数值写法。</p>
 */
@DisplayName("M6-A：检查点外层字段绑定（F1 回归）")
class WorldCheckpointEnvelopeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private interface Mutator {
        void apply(ObjectNode envelope);
    }

    @Test
    @DisplayName("每个外层字段被篡改都按各自原因码拒绝")
    void everyOuterFieldIsBound() throws Exception {
        WorldWebHost.Opened opened = WorldWebScenario.openGroups(tempDir.resolve("bind.journal"));
        WorldRuntime runtime = opened.runtime();
        try {
            long cut = runtime.snapshot().position();
            assertTrue(cut >= 2L, "群组开局应有多条确认批次");
            WorldRuntime.Checkpoint checkpoint = runtime.checkpoint(cut);
            assertTrue(runtime.verifyCheckpoint(JSON.writeValueAsString(checkpoint)).ok(),
                    "原始信封必须通过");

            Map<String, String> expectations = new LinkedHashMap<>();
            expectations.put("opening.cash", "CHECKPOINT_OPENING_MISMATCH");
            expectations.put("opening.missing", "CHECKPOINT_OPENING_MISMATCH");
            expectations.put("cutPosition.above", "CHECKPOINT_CUT_INVALID");
            expectations.put("cutPosition.below", "CHECKPOINT_CUT_INVALID");
            expectations.put("timeMillis", "CHECKPOINT_TIME_MISMATCH");
            expectations.put("lastPhase", "CHECKPOINT_PHASE_MISMATCH");
            expectations.put("commitEventCount", "CHECKPOINT_COMMIT_EVENT_MISMATCH");
            expectations.put("commitDigest", "CHECKPOINT_COMMIT_DIGEST_MISMATCH");
            expectations.put("stateDigestKind", "CHECKPOINT_DIGEST_KIND_UNSUPPORTED");
            expectations.put("commitDigestKind", "CHECKPOINT_DIGEST_KIND_UNSUPPORTED");
            expectations.put("claims.branchSource", "CHECKPOINT_CLAIM_MISMATCH");
            expectations.put("claims.note", "CHECKPOINT_CLAIM_MISMATCH");
            expectations.put("claims.extra", "CHECKPOINT_CLAIM_MISMATCH");
            expectations.put("unknownField", "CHECKPOINT_UNKNOWN_FIELD");
            expectations.put("generatedAt", "CHECKPOINT_GENERATED_AT_INVALID");

            for (Map.Entry<String, String> expectation : expectations.entrySet()) {
                String name = expectation.getKey();
                String reason = runtime.verifyCheckpoint(
                        mutate(checkpoint, mutation(name, cut))).reason();
                assertEquals(expectation.getValue(), reason, "字段未按要求绑定：" + name);
            }
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("cutPosition 允许落后于当前截止，但不得小于 position 或超过当前截止")
    void cutPositionBoundsFollowTheActiveCut() throws Exception {
        WorldWebHost.Opened opened = WorldWebScenario.openGroups(tempDir.resolve("cut.journal"));
        WorldRuntime runtime = opened.runtime();
        try {
            long cut = runtime.snapshot().position();
            WorldRuntime.Checkpoint checkpoint = runtime.checkpoint(cut);
            runtime.advanceTo(runtime.snapshot().timeMillis() + 1_000L);
            assertTrue(runtime.snapshot().position() > cut, "推进后截止位置应增大");
            assertTrue(runtime.verifyCheckpoint(JSON.writeValueAsString(checkpoint)).ok(),
                    "导出后世界继续推进，旧信封仍应通过（cutPosition 不超过当前截止）");
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("等价数值写法按值比较：1000.0 与 1000.00 相同，真实改动仍拒绝")
    void equivalentNumberFormatsCompareByValue() throws Exception {
        WorldWebHost.Opened opened = WorldWebScenario.openGroups(tempDir.resolve("value.journal"));
        WorldRuntime runtime = opened.runtime();
        try {
            long cut = runtime.snapshot().position();
            WorldRuntime.Checkpoint checkpoint = runtime.checkpoint(cut);
            String scaled = mutate(checkpoint, envelope -> {
                ObjectNode opening = (ObjectNode) envelope.get("opening");
                ArrayNode openings = (ArrayNode) opening.get("openings");
                ObjectNode first = (ObjectNode) openings.get(0);
                BigDecimal original = new BigDecimal(first.get("cash").asText());
                first.put("cash", original.stripTrailingZeros());
            });
            assertTrue(runtime.verifyCheckpoint(scaled).ok(),
                    "数值等价的写法应按值视为相同：" + runtime.verifyCheckpoint(scaled).reason());
            String changed = mutate(checkpoint, envelope -> {
                ObjectNode opening = (ObjectNode) envelope.get("opening");
                ArrayNode openings = (ArrayNode) opening.get("openings");
                ((ObjectNode) openings.get(0)).put("cash", new BigDecimal("1000.01"));
            });
            assertEquals("CHECKPOINT_OPENING_MISMATCH", runtime.verifyCheckpoint(changed).reason(),
                    "真实金额改动必须拒绝");
        } finally {
            runtime.close();
        }
    }

    // ---------------------------------------------------------------- 夹具

    private static Mutator mutation(String name, long cut) {
        return envelope -> {
            ObjectNode opening = envelope.get("opening") instanceof ObjectNode node ? node : null;
            switch (name) {
                case "opening.cash" -> {
                    ArrayNode openings = (ArrayNode) opening.get("openings");
                    ((ObjectNode) openings.get(0)).put("cash", new BigDecimal("999999.99"));
                }
                case "opening.missing" -> opening.remove("openings");
                case "cutPosition.above" -> envelope.put("cutPosition", cut + 1L);
                case "cutPosition.below" -> envelope.put("cutPosition", 0L);
                case "timeMillis" -> envelope.put("timeMillis", 1L);
                case "lastPhase" -> envelope.put("lastPhase", "FORGED");
                case "commitEventCount" -> envelope.put("commitEventCount",
                        envelope.get("commitEventCount").asInt() + 1);
                case "commitDigest" -> envelope.put("commitDigest", "00");
                case "stateDigestKind" -> envelope.put("stateDigestKind", "forged");
                case "commitDigestKind" -> envelope.put("commitDigestKind", "forged");
                case "claims.branchSource" -> ((ObjectNode) envelope.get("claims"))
                        .put("branchSource", true);
                case "claims.note" -> ((ObjectNode) envelope.get("claims"))
                        .put("note", "可以独立续跑");
                case "claims.extra" -> ((ObjectNode) envelope.get("claims"))
                        .put("verifiedByAuthor", true);
                case "unknownField" -> envelope.put("signature", "self-signed");
                case "generatedAt" -> envelope.put("generatedAtEpochMillis", -1L);
                default -> throw new IllegalArgumentException("未知篡改项：" + name);
            }
        };
    }

    private static String mutate(WorldRuntime.Checkpoint checkpoint, Mutator mutator)
            throws Exception {
        ObjectNode envelope = (ObjectNode) JSON.readTree(JSON.writeValueAsString(checkpoint));
        mutator.apply(envelope);
        return JSON.writeValueAsString(envelope);
    }
}
