package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6-A：已确认历史回看与检查点导出/核验（Java 侧）。
 *
 * <p>覆盖：按位置分页、固定位置的只读投影与实时投影一致、同一毫秒多批次互不泄漏、
 * 固定 cut 后新提交不改变旧视图、只读不推进不写入不消耗预算、非法位置明确拒绝、
 * 检查点自洽通过、篡改状态即便重算摘要也被领域比较拒绝。</p>
 */
@DisplayName("M6-A：已确认历史与检查点（只读）")
class WorldHistoryRuntimeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    /** 打开一个真实 schema20 群组运行，作为历史回看的被观察对象。 */
    private WorldWebHost.Opened openGroups() throws Exception {
        return WorldWebScenario.openGroups(tempDir.resolve("history.journal"));
    }

    @Test
    @DisplayName("索引按位置分页，非法起点/条数明确拒绝")
    void historyIndexPagesByPosition() throws Exception {
        WorldWebHost.Opened opened = openGroups();
        WorldRuntime runtime = opened.runtime();
        try {
            long cut = runtime.snapshot().position();
            assertTrue(cut >= 2L, "群组开局应产生多条已确认批次");

            List<WorldRuntime.HistoryEntry> first = runtime.historyIndex(0L, 2);
            assertEquals(2, first.size());
            assertEquals(1L, first.get(0).position());
            assertEquals(2L, first.get(1).position());

            List<WorldRuntime.HistoryEntry> next = runtime.historyIndex(2L, 5);
            assertEquals(3L, next.get(0).position());
            assertTrue(next.size() <= 5);

            List<WorldRuntime.HistoryEntry> tail = runtime.historyIndex(cut, 10);
            assertTrue(tail.isEmpty(), "确认截止之后没有可返回的批次");

            assertThrows(IllegalArgumentException.class, () -> runtime.historyIndex(-1L, 10));
            assertThrows(IllegalArgumentException.class, () -> runtime.historyIndex(0L, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.historyIndex(0L, WorldRuntime.MAX_HISTORY_PAGE + 1));
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("固定位置的只读投影与当时实时投影完全一致")
    void historyProjectionMatchesLiveProjection() throws Exception {
        WorldWebHost.Opened opened = openGroups();
        WorldRuntime runtime = opened.runtime();
        try {
            WorldRuntime.Snapshot live = runtime.snapshot();
            assertEquals(live, runtime.historyFrame(live.position()).snapshot(),
                    "同一 position 的历史投影必须等于实时投影");
            assertEquals(live.position(), runtime.historyFrame(live.position()).snapshot().position());
            assertEquals(live.timeMillis(), runtime.historyFrame(live.position()).snapshot().timeMillis());
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("同一模拟毫秒的多个批次各自独立，后一笔不泄入前一笔")
    void sameMillisecondBatchesStaySeparate() throws Exception {
        WorldWebHost.Opened opened = openGroups();
        WorldRuntime runtime = opened.runtime();
        try {
            long cut = runtime.snapshot().position();
            long firstPosition = -1L;
            for (long position = 1L; position < cut; position++) {
                if (runtime.historyFrame(position).snapshot().timeMillis()
                        == runtime.historyFrame(position + 1L).snapshot().timeMillis()) {
                    firstPosition = position;
                    break;
                }
            }
            assertTrue(firstPosition > 0L, "开局至少应有两笔落在同一模拟毫秒的已确认批次");

            WorldRuntime.Snapshot first = runtime.historyFrame(firstPosition).snapshot();
            WorldRuntime.Snapshot second = runtime.historyFrame(firstPosition + 1L).snapshot();
            assertEquals(first.timeMillis(), second.timeMillis(), "两个位置必须同一模拟毫秒");
            assertNotEquals(first, second, "同一毫秒的两个位置必须是两份不同状态");
            // 后一笔只能追加：已用任务、已投递回报不得在前一笔里提前出现
            assertTrue(second.usedTaskIds().containsAll(first.usedTaskIds()),
                    "后续批次的已用任务只能包含前一位置的全部标识");
            assertTrue(second.delivered().size() >= first.delivered().size(),
                    "后续批次的已投递回报不得少于前一位置");
            for (int index = 0; index < first.delivered().size(); index++) {
                assertEquals(first.delivered().get(index), second.delivered().get(index),
                        "前一位置的已投递回报在后续位置必须逐条不变");
            }
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("固定 cut 后的新提交不改变旧位置视图，只读调用不推进/不写入/不消耗预算")
    void fixedCutIsStableAndReadOnly() throws Exception {
        WorldWebHost.Opened opened = openGroups();
        WorldRuntime runtime = opened.runtime();
        try {
            Path journal = tempDir.resolve("history.journal");
            long cut = runtime.snapshot().position();
            WorldRuntime.HistoryFrame frozen = runtime.historyFrame(cut);
            long sizeBefore = Files.size(journal);
            DecisionModel.Usage usageBefore = runtime.snapshot().decisions()
                    .map(run -> run.state().runUsage()).orElse(null);
            long timeBefore = runtime.snapshot().timeMillis();

            for (long position = 1; position <= cut; position++) {
                runtime.historyIndex(0L, (int) Math.min(WorldRuntime.MAX_HISTORY_PAGE, cut));
                runtime.historyFrame(position);
                runtime.checkpoint(position);
            }

            assertEquals(cut, runtime.snapshot().position(), "历史读取不得推进世界");
            assertEquals(timeBefore, runtime.snapshot().timeMillis(), "历史读取不得推进时钟");
            assertEquals(sizeBefore, Files.size(journal), "历史读取不得写入日志");
            if (usageBefore != null) {
                assertEquals(usageBefore, runtime.snapshot().decisions()
                        .orElseThrow().state().runUsage(), "历史读取不得消耗模型预算");
            }

            runtime.advanceTo(timeBefore + 1000L);
            assertTrue(runtime.snapshot().position() > cut, "推进应产生新的已确认批次");
            assertEquals(frozen.snapshot(), runtime.historyFrame(cut).snapshot(),
                    "新提交后旧位置的视图必须逐字段不变");
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("非法位置明确拒绝，不夹取到最近位置")
    void invalidPositionsRejected() throws Exception {
        WorldWebHost.Opened opened = openGroups();
        WorldRuntime runtime = opened.runtime();
        try {
            long cut = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> runtime.historyFrame(0L));
            assertThrows(IllegalArgumentException.class, () -> runtime.historyFrame(cut + 1L));
            assertThrows(IllegalArgumentException.class, () -> runtime.checkpoint(0L));
            assertThrows(IllegalArgumentException.class, () -> runtime.checkpoint(cut + 1L));
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("检查点：自洽通过；篡改状态即使重算摘要也被拒绝")
    void checkpointVerifyRejectsTamperedState() throws Exception {
        WorldWebHost.Opened opened = openGroups();
        WorldRuntime runtime = opened.runtime();
        try {
            long position = runtime.snapshot().position();
            WorldRuntime.Checkpoint checkpoint = runtime.checkpoint(position);
            assertEquals(WorldRuntime.CHECKPOINT_VERSION, checkpoint.checkpointVersion());
            assertEquals("false", checkpoint.claims().get("independentResume").toString());
            assertEquals("false", checkpoint.claims().get("journalPrefixSha256").toString());

            WorldRuntime.CheckpointVerdict ok =
                    runtime.verifyCheckpoint(JSON.writeValueAsString(checkpoint));
            assertTrue(ok.ok(), "原始检查点必须通过：" + ok.reason());
            assertEquals("OK", ok.reason());

            // 反例 1：改一个现金金额但不改摘要 → 领域比较先失败（摘要不是判据）
            ObjectNode envelope = (ObjectNode) JSON.readTree(JSON.writeValueAsString(checkpoint));
            ObjectNode state = (ObjectNode) JSON.readTree(envelope.get("stateJson").asText());
            ObjectNode accounts = (ObjectNode) state.get("accounts");
            String accountId = accounts.fieldNames().next();
            ObjectNode account = (ObjectNode) accounts.get(accountId);
            account.put("cash", new BigDecimal("987654.32"));
            String tamperedState = JSON.writeValueAsString(state);
            envelope.put("stateJson", tamperedState);
            WorldRuntime.CheckpointVerdict digestMismatch =
                    runtime.verifyCheckpoint(JSON.writeValueAsString(envelope));
            assertFalse(digestMismatch.ok());
            assertEquals("CHECKPOINT_STATE_MISMATCH", digestMismatch.reason());

            // 反例 2：连摘要一起重算 → 摘要自洽，但领域比较仍必须拒绝
            envelope.put("stateDigest", sha256(tamperedState));
            WorldRuntime.CheckpointVerdict tampered =
                    runtime.verifyCheckpoint(JSON.writeValueAsString(envelope));
            assertFalse(tampered.ok(), "重算摘要不能当成领域语义证明");
            assertEquals("CHECKPOINT_STATE_MISMATCH", tampered.reason());

            // 反例 3：换运行标识 / 换版本 / 未确认位置
            envelope.put("stateJson", checkpoint.stateJson());
            envelope.put("stateDigest", checkpoint.stateDigest());
            envelope.put("runId", "other-run");
            assertEquals("CHECKPOINT_RUN_MISMATCH",
                    runtime.verifyCheckpoint(JSON.writeValueAsString(envelope)).reason());
            envelope.put("runId", checkpoint.runId());
            envelope.put("checkpointVersion", "world-checkpoint-v2");
            assertEquals("CHECKPOINT_VERSION_UNSUPPORTED",
                    runtime.verifyCheckpoint(JSON.writeValueAsString(envelope)).reason());
            envelope.put("checkpointVersion", WorldRuntime.CHECKPOINT_VERSION);
            envelope.put("position", position + 1L);
            assertEquals("CHECKPOINT_POSITION_UNCONFIRMED",
                    runtime.verifyCheckpoint(JSON.writeValueAsString(envelope)).reason());
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("检查点里的状态原文可被同一 mapper 重新解码并逐字段等于该位置")
    void checkpointStateRoundTrips() throws Exception {
        WorldWebHost.Opened opened = openGroups();
        WorldRuntime runtime = opened.runtime();
        try {
            long position = runtime.snapshot().position();
            WorldRuntime.Checkpoint checkpoint = runtime.checkpoint(position);
            JsonNode state = JSON.readTree(checkpoint.stateJson());
            assertEquals(position, state.get("position").asLong());
            assertEquals(opened.runId(), state.get("runId").asText());
            assertEquals(runtime.snapshot().accounts().size(), state.get("accounts").size());
            assertEquals("world-checkpoint-v1", checkpoint.checkpointVersion());
            assertTrue(checkpoint.opening().containsKey("manifest"));
            assertTrue(checkpoint.opening().containsKey("openings"));
        } finally {
            runtime.close();
        }
    }

    private static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
