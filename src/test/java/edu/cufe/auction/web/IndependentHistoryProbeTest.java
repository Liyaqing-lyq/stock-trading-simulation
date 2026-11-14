package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.EngineeringModel;
import edu.cufe.auction.world.GroupModel;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRuntime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 独立验收探针（不属于作者候选）：历史帧不是实时 accessor、分页/Long 边界、只读、
 * 以及检查点信封字段是否真正被绑定。
 */
class IndependentHistoryProbeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("旧位置视图来自该帧：与当时实时快照逐字段相等，且不等同当前 accessor")
    void oldFrameIsHistoricalNotLive() throws Exception {
        WorldWebHost.Opened opened = WorldWebScenario.openGroups(tempDir.resolve("h1.journal"));
        WorldWebHost host = new WorldWebHost(() -> opened);
        try {
            WorldRuntime runtime = opened.runtime();
            WorldRuntime.Snapshot snapshot0 = runtime.snapshot();
            long p0 = snapshot0.position();
            runtime.advanceTo(snapshot0.timeMillis() + 1_000L);
            runtime.advanceTo(snapshot0.timeMillis() + 2_000L);
            WorldRuntime.Snapshot live = runtime.snapshot();
            assertTrue(live.position() > p0, "推进后应有新位置");
            assertNotEquals(snapshot0.timeMillis(), live.timeMillis(), "新旧时点应不同");

            WorldRuntime.HistoryFrame frame = runtime.historyFrame(p0);
            assertEquals(snapshot0, frame.snapshot(), "历史帧必须等于当时的实时快照");
            assertEquals(snapshot0.timeMillis(), frame.snapshot().timeMillis());
            assertNotEquals(live.timeMillis(), frame.snapshot().timeMillis(),
                    "旧位置不得读到当前时钟");

            WorldWebHost.HistoryView view = host.historyView(p0);
            assertEquals(Long.toString(p0), view.position());
            assertEquals(Long.toString(snapshot0.timeMillis()), view.timeMillis());
            assertTrue(view.historical());

            // 账户：逐行对照该帧快照，而不是当前状态。
            Map<String, BigDecimal> expectedCash = new LinkedHashMap<>();
            snapshot0.accounts().forEach((id, account) -> expectedCash.put(id, account.cash()));
            assertEquals(new java.util.TreeSet<>(expectedCash.keySet()),
                    new java.util.TreeSet<>(rowIds(view.accounts(), "accountId")),
                    "历史账户集合必须来自该帧");
            for (Map<String, Object> row : view.accounts().rows()) {
                BigDecimal cash = new BigDecimal(String.valueOf(row.get("cash")));
                assertEquals(0, cash.compareTo(expectedCash.get(String.valueOf(row.get("accountId")))),
                        "历史现金必须来自该帧：" + row);
            }
            assertEquals(snapshot0.pending().stream().map(PendingTask::taskId).toList(),
                    rowIds(view.pending(), "taskId"), "待办必须来自该帧");
            assertEquals(snapshot0.roles().orElseThrow().access().appointments().stream()
                            .map(RoleAccess.Appointment::id).toList(),
                    rowIds(view.roles(), "appointmentId"), "任职必须来自该帧");
            assertEquals(snapshot0.information().orElseThrow().information().documents().stream()
                            .map(InformationModel.Document::docId).toList(),
                    rowIds(view.information(), "docId"), "资料索引必须来自该帧");
            assertEquals(snapshot0.engineering().orElseThrow().companies().keySet().stream()
                            .sorted().toList(),
                    rowIds(view.engineering(), "companyPartyId").stream().sorted().toList(),
                    "工程分区必须来自该帧");
            long groups = frame.groups().map(run -> (long) run.state().groups().size()).orElse(0L);
            assertEquals(groups, view.groups().rows().size(), "群组分区必须来自该帧");
            GroupModel.State groupState = frame.groups().orElseThrow().state();
            assertEquals(groupState.groups().stream().map(GroupModel.Group::groupId).sorted().toList(),
                    rowIds(view.groups(), "groupId").stream().sorted().toList());

            // 当前截止位置与旧位置的视图必须不同（证明不是把 live accessor 当历史）。
            WorldWebHost.HistoryView atCut = host.historyView(live.position());
            assertNotEquals(view.timeMillis(), atCut.timeMillis(),
                    "旧位置与当前截止的时点必须不同");
            assertNotEquals(view.position(), atCut.position());

            // 再推进后旧帧仍稳定。
            runtime.advanceTo(live.timeMillis() + 1_000L);
            assertEquals(snapshot0, runtime.historyFrame(p0).snapshot(),
                    "新提交后旧位置必须逐字段不变");
        } finally {
            host.close();
        }
    }

    @Test
    @DisplayName("分页与 Long 边界：不截断、不溢出、越界明确")
    void paginationAndLongBoundaries() throws Exception {
        WorldWebHost.Opened opened = WorldWebScenario.openGroups(tempDir.resolve("h2.journal"));
        try {
            WorldRuntime runtime = opened.runtime();
            long cut = runtime.snapshot().position();
            assertTrue(cut >= 2L);
            assertEquals(1L, runtime.historyIndex(0L, 1).get(0).position());
            assertTrue(runtime.historyIndex(cut, 5).isEmpty());
            assertTrue(runtime.historyIndex(Long.MAX_VALUE, 1).isEmpty(),
                    "超大 after 不得截断成负索引或回卷");
            assertTrue(runtime.historyIndex(3_000_000_000L, 1).isEmpty(),
                    "超过 int 的 after 不得被截断回卷");
            assertEquals(Math.min(cut, WorldRuntime.MAX_HISTORY_PAGE),
                    runtime.historyIndex(0L, WorldRuntime.MAX_HISTORY_PAGE).size());

            assertThrows(IllegalArgumentException.class, () -> runtime.historyFrame(0L));
            assertThrows(IllegalArgumentException.class, () -> runtime.historyFrame(cut + 1L));
            assertThrows(IllegalArgumentException.class, () -> runtime.historyFrame(Long.MAX_VALUE));

            assertThrows(IllegalArgumentException.class, () -> runtime.historyIndex(-1L, 1));
            assertThrows(IllegalArgumentException.class, () -> runtime.historyIndex(0L, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.historyIndex(0L, WorldRuntime.MAX_HISTORY_PAGE + 1));

            // 同一毫秒的相邻批次必须是两份状态，后一笔只追加。
            long same = -1L;
            for (long p = 1L; p < cut; p++) {
                if (runtime.historyFrame(p).snapshot().timeMillis()
                        == runtime.historyFrame(p + 1L).snapshot().timeMillis()) {
                    same = p;
                    break;
                }
            }
            assertTrue(same > 0L, "开局应存在同毫秒相邻批次");
            WorldRuntime.Snapshot a = runtime.historyFrame(same).snapshot();
            WorldRuntime.Snapshot b = runtime.historyFrame(same + 1L).snapshot();
            assertEquals(a.timeMillis(), b.timeMillis());
            assertNotEquals(a, b, "同毫秒不同位置必须是两份状态");
            assertTrue(b.usedTaskIds().containsAll(a.usedTaskIds()));
        } finally {
            opened.runtime().close();
        }
    }

    @Test
    @DisplayName("只读：索引/视图/导出/核验（含失败）不改日志字节、位置、时钟、预算")
    void readOnlyAcrossAllPaths() throws Exception {
        Path journal = tempDir.resolve("h3.journal");
        WorldWebHost.Opened opened = WorldWebScenario.openGroups(journal);
        try {
            WorldRuntime runtime = opened.runtime();
            long cut = runtime.snapshot().position();
            long size = Files.size(journal);
            long position = runtime.snapshot().position();
            long time = runtime.snapshot().timeMillis();
            DecisionModel.Usage usage = runtime.snapshot().decisions()
                    .map(run -> run.state().runUsage()).orElse(null);

            for (long p = 1; p <= cut; p++) {
                runtime.historyIndex(0L, (int) Math.min(cut, WorldRuntime.MAX_HISTORY_PAGE));
                runtime.historyFrame(p);
                WorldRuntime.Checkpoint checkpoint = runtime.checkpoint(p);
                assertTrue(runtime.verifyCheckpoint(JSON.writeValueAsString(checkpoint)).ok());
            }
            for (String bad : List.of("", "not json", "[]", "{\"checkpointVersion\":\"x\"}")) {
                assertFalse(runtime.verifyCheckpoint(bad).ok(), "失败核验不应通过：" + bad);
            }

            assertEquals(size, Files.size(journal), "不得写入日志");
            assertEquals(position, runtime.snapshot().position(), "不得推进世界");
            assertEquals(time, runtime.snapshot().timeMillis(), "不得推进时钟");
            if (usage != null) {
                assertEquals(usage, runtime.snapshot().decisions().orElseThrow()
                        .state().runUsage(), "不得消耗模型预算");
            }
        } finally {
            opened.runtime().close();
        }
    }

    @Test
    @DisplayName("检查点信封字段绑定：除状态外 opening/cut/摘要等是否也被核验")
    void checkpointEnvelopeBinding() throws Exception {
        WorldWebHost.Opened opened = WorldWebScenario.openGroups(tempDir.resolve("h4.journal"));
        try {
            WorldRuntime runtime = opened.runtime();
            long cut = runtime.snapshot().position();
            WorldRuntime.Checkpoint checkpoint = runtime.checkpoint(cut);
            String original = JSON.writeValueAsString(checkpoint);
            assertTrue(runtime.verifyCheckpoint(original).ok(), "原始信封必须通过");

            // 已绑定：清单/命令/回报/状态摘要/位置/运行/版本。
            assertEquals("CHECKPOINT_MANIFEST_MISMATCH", reason(runtime, mutate(checkpoint,
                    env -> ((ObjectNode) env.get("manifest")).put("runId", "other"))));
            assertEquals("CHECKPOINT_COMMIT_MISMATCH", reason(runtime, mutate(checkpoint,
                    env -> env.put("commitCommandId", "other"))));
            assertEquals("CHECKPOINT_COMMIT_MISMATCH", reason(runtime, mutate(checkpoint,
                    env -> env.put("commitResult", "other"))));
            assertEquals("CHECKPOINT_DIGEST_MISMATCH", reason(runtime, mutate(checkpoint,
                    env -> env.put("stateDigest", "00"))));
            assertEquals("CHECKPOINT_POSITION_UNCONFIRMED", reason(runtime, mutate(checkpoint,
                    env -> env.put("position", cut + 1L))));
            assertEquals("CHECKPOINT_RUN_MISMATCH", reason(runtime, mutate(checkpoint,
                    env -> env.put("runId", "other"))));
            assertEquals("CHECKPOINT_VERSION_UNSUPPORTED", reason(runtime, mutate(checkpoint,
                    env -> env.put("checkpointVersion", "world-checkpoint-v2"))));

            // 待确认：这些字段被篡改后是否仍通过？
            Map<String, String> accepted = new LinkedHashMap<>();
            recordIfAccepted(runtime, checkpoint, accepted, "opening",
                    env -> ((ObjectNode) ((com.fasterxml.jackson.databind.node.ArrayNode)
                            ((ObjectNode) env.get("opening")).get("openings")).get(0))
                            .put("cash", new BigDecimal("999999.99")));
            recordIfAccepted(runtime, checkpoint, accepted, "cutPosition",
                    env -> env.put("cutPosition", cut + 1L));
            recordIfAccepted(runtime, checkpoint, accepted, "timeMillis",
                    env -> env.put("timeMillis", 1L));
            recordIfAccepted(runtime, checkpoint, accepted, "lastPhase",
                    env -> env.put("lastPhase", "FORGED"));
            recordIfAccepted(runtime, checkpoint, accepted, "commitDigest",
                    env -> env.put("commitDigest", "00"));
            recordIfAccepted(runtime, checkpoint, accepted, "commitEventCount",
                    env -> env.put("commitEventCount", 999));
            recordIfAccepted(runtime, checkpoint, accepted, "stateDigestKind",
                    env -> env.put("stateDigestKind", "forged"));
            recordIfAccepted(runtime, checkpoint, accepted, "claims.independentResume",
                    env -> ((ObjectNode) env.get("claims")).put("independentResume", true));
            if (!accepted.isEmpty()) {
                fail("以下检查点信封字段被篡改后仍被判为通过（未绑定）：" + accepted);
            }
        } finally {
            opened.runtime().close();
        }
    }

    // ---------------------------------------------------------------- 夹具

    private interface Mutator {
        void apply(ObjectNode envelope);
    }

    private static String mutate(WorldRuntime.Checkpoint checkpoint, Mutator mutator)
            throws Exception {
        ObjectNode envelope = (ObjectNode) JSON.readTree(JSON.writeValueAsString(checkpoint));
        mutator.apply(envelope);
        return JSON.writeValueAsString(envelope);
    }

    private static String reason(WorldRuntime runtime, String envelope) {
        return runtime.verifyCheckpoint(envelope).reason();
    }

    private static void recordIfAccepted(WorldRuntime runtime, WorldRuntime.Checkpoint checkpoint,
            Map<String, String> accepted, String field, Mutator mutator) throws Exception {
        String envelope = mutate(checkpoint, mutator);
        WorldRuntime.CheckpointVerdict verdict = runtime.verifyCheckpoint(envelope);
        if (verdict.ok()) {
            accepted.put(field, "accepted");
        } else {
            System.out.println("[bound] " + field + " -> " + verdict.reason());
        }
    }

    private static List<String> rowIds(WorldWebHost.Section section, String key) {
        List<String> values = new ArrayList<>();
        for (Map<String, Object> row : section.rows()) {
            values.add(String.valueOf(row.get(key)));
        }
        return values;
    }
}
