package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.DigitalIndustryConfig;
import edu.cufe.auction.world.DigitalOperations;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 独立审查补测：重算 SHA 的自洽篡改仍不能冒充原命令。
 *
 * <p>两条边界：只改快照待办（事件保持原样）与同时改排程事件+快照待办（两者自洽、SHA 正确），
 * 但原命令正文（意图信封）不动。前者应被“非执行帧只能追加原待办”拒绝，后者必须被原意信封
 * 中“正文逐字一致”拒绝。完整改写意图信封本身仍无外部签名证明（已知限制，见 CommandIntent）。</p>
 */
class IndependentJournalTamperTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    private static final String COMPANY_ID = "tamper-cmd";
    private static final String TASK_ID = "tamper-task";

    @TempDir Path dir;

    private Path scheduleOnce(String name) throws Exception {
        Path journal = dir.resolve(name);
        try (WorldRuntime world = ComposedIndustryScenario.openBaseline(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, DigitalIndustryConfig.COMPANY,
                    BaselineIndustryConfig.directors(DigitalIndustryConfig.COMPANY),
                    DigitalIndustryConfig.MANAGER_PERSON, "tamper", List.of(0L));
            long due = world.snapshot().timeMillis() + 2000L;
            assertEquals("SCHEDULED:" + TASK_ID, world
                    .openRoleSession(DigitalIndustryConfig.MANAGER_PERSON)
                    .digitalAction(COMPANY_ID, TASK_ID, due, DigitalIndustryConfig.COMPANY,
                            new DigitalOperations.Request(DigitalOperations.Action.BEGIN_PILOT,
                                    Map.of()), 1000));
        }
        // 未篡改时必须能恢复。
        try (WorldRuntime world = ComposedIndustryScenario.openBaseline(journal)) {
            assertTrue(world.snapshot().pending().stream()
                    .anyMatch(task -> task.taskId().equals(TASK_ID)));
        }
        return journal;
    }

    @Test
    void identityRewriteOfTheFrameRoundTrips() throws Exception {
        Path journal = scheduleOnce("identity.journal");
        int frame = lastFrameContaining(journal, "cmd:" + COMPANY_ID);
        List<byte[]> frames = frames(journal);
        ObjectNode commit = (ObjectNode) JSON.readTree(frames.get(frame));
        String before = snapshotText(commit);
        String after = JSON.writeValueAsString(snapshotOf(commit));
        if (!before.equals(after)) {
            int index = 0;
            while (index < before.length() && index < after.length()
                    && before.charAt(index) == after.charAt(index)) {
                index++;
            }
            int from = Math.max(0, index - 120);
            int toB = Math.min(before.length(), index + 200);
            int toA = Math.min(after.length(), index + 200);
            System.out.println("[identity-diff] at " + index);
            System.out.println("[identity-diff] before=" + before.substring(from, toB));
            System.out.println("[identity-diff] after =" + after.substring(from, toA));
        }
        rewriteFrame(journal, frame, c -> putSnapshot(c, snapshotOf(c)));
        try (WorldRuntime world = ComposedIndustryScenario.openBaseline(journal)) {
            assertTrue(world.snapshot().pending().stream()
                    .anyMatch(task -> task.taskId().equals(TASK_ID)));
        }
    }

    private static String snapshotText(ObjectNode commit) {
        ArrayNode events = (ArrayNode) commit.get("events");
        for (JsonNode event : events) {
            if ("STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                return event.get("payload").asText();
            }
        }
        throw new IllegalStateException("帧里没有状态快照");
    }

    @Test
    void snapshotOnlyTamperIsRejectedEvenWithAValidFrameDigest() throws Exception {
        Path journal = scheduleOnce("snapshot-only.journal");
        int frame = lastFrameContaining(journal, "cmd:" + COMPANY_ID);
        rewriteFrame(journal, frame, commit -> {
            ObjectNode snapshot = snapshotOf(commit);
            mutatePending(snapshot, TASK_ID);
            putSnapshot(commit, snapshot);
        });
        Exception failure = assertThrows(Exception.class,
                () -> ComposedIndustryScenario.openBaseline(journal));
        System.out.println("[snapshot-only tamper] " + failure.getClass().getSimpleName()
                + ": " + failure.getMessage());
    }

    @Test
    void eventAndSnapshotRewrittenConsistentlyStillCannotImpersonateTheRecordedCommand()
            throws Exception {
        Path journal = scheduleOnce("self-consistent.journal");
        int frame = lastFrameContaining(journal, "cmd:" + COMPANY_ID);
        rewriteFrame(journal, frame, commit -> {
            ArrayNode events = (ArrayNode) commit.get("events");
            for (JsonNode event : events) {
                String type = event.get("type").asText();
                if ("TASK_SCHEDULED_V1".equals(type)) {
                    ObjectNode eventObject = (ObjectNode) event;
                    eventObject.put("payload", mutateTask(eventObject.get("payload").asText()));
                }
                if ("STATE_SNAPSHOT_V1".equals(type)) {
                    ObjectNode snapshot = snapshotOf(commit);
                    mutatePending(snapshot, TASK_ID);
                    putSnapshot(commit, snapshot);
                }
            }
        });
        Exception failure = assertThrows(Exception.class,
                () -> ComposedIndustryScenario.openBaseline(journal));
        System.out.println("[self-consistent tamper] " + failure.getClass().getSimpleName()
                + ": " + failure.getMessage());
        if (failure.getMessage() != null) {
            assertTrue(failure.getMessage().contains("意图") || failure.getMessage().contains("原命令"),
                    "拒绝理由必须指向原命令/意图不一致，实际：" + failure.getMessage());
        }
    }

    // ---------------------------------------------------------------- 篡改工具

    /** 把待办里的业务正文工时从 1000 改成 2000（仍是合法数字，但不是原命令）。 */
    private static String mutateTask(String pendingTaskJson) {
        try {
            ObjectNode task = (ObjectNode) JSON.readTree(pendingTaskJson);
            ObjectNode wrapper = (ObjectNode) JSON.readTree(task.get("payload").asText());
            ObjectNode body = (ObjectNode) JSON.readTree(wrapper.get("payload").asText());
            body.put("durationMillis", 2000L);
            wrapper.put("payload", JSON.writeValueAsString(body));
            task.put("payload", JSON.writeValueAsString(wrapper));
            return JSON.writeValueAsString(task);
        } catch (Exception failure) {
            throw new IllegalStateException("待办篡改失败", failure);
        }
    }

    private static void mutatePending(ObjectNode snapshot, String taskId) {
        ArrayNode pending = (ArrayNode) snapshot.get("pending");
        boolean found = false;
        for (int index = 0; index < pending.size(); index++) {
            JsonNode task = pending.get(index);
            if (taskId.equals(task.get("taskId").asText())) {
                try {
                    pending.set(index, JSON.readTree(mutateTask(JSON.writeValueAsString(task))));
                } catch (Exception failure) {
                    throw new IllegalStateException("快照待办写回失败", failure);
                }
                found = true;
            }
        }
        assertTrue(found, "快照里必须存在待篡改的待办");
    }

    private static ObjectNode snapshotOf(ObjectNode commit) {
        ArrayNode events = (ArrayNode) commit.get("events");
        for (JsonNode event : events) {
            if ("STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                try {
                    return (ObjectNode) JSON.readTree(event.get("payload").asText());
                } catch (Exception failure) {
                    throw new IllegalStateException("快照解析失败", failure);
                }
            }
        }
        throw new IllegalStateException("帧里没有状态快照");
    }

    private static void putSnapshot(ObjectNode commit, ObjectNode snapshot) {
        ArrayNode events = (ArrayNode) commit.get("events");
        for (JsonNode event : events) {
            if ("STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                try {
                    ((ObjectNode) event).put("payload", JSON.writeValueAsString(snapshot));
                } catch (Exception failure) {
                    throw new IllegalStateException("快照写回失败", failure);
                }
                return;
            }
        }
        throw new IllegalStateException("帧里没有状态快照");
    }

    private static List<byte[]> frames(Path file) throws Exception {
        byte[] all = Files.readAllBytes(file);
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset < all.length) {
            int length = ByteBuffer.wrap(all, offset, 4).getInt();
            offset += 8;
            byte[] payload = new byte[length];
            System.arraycopy(all, offset, payload, 0, length);
            offset += length + 32;
            frames.add(payload);
        }
        return frames;
    }

    private static int lastFrameContaining(Path file, String needle) throws Exception {
        List<byte[]> frames = frames(file);
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (new String(frames.get(i), StandardCharsets.UTF_8).contains(needle)) {
                return i;
            }
        }
        throw new IllegalStateException("日志中没有包含 " + needle + " 的帧");
    }

    /** 对指定帧做语义篡改，重算该帧 SHA 后整体写回。 */
    private static void rewriteFrame(Path file, int index, Consumer<ObjectNode> mutation)
            throws Exception {
        List<byte[]> frames = frames(file);
        ObjectNode commit = (ObjectNode) JSON.readTree(frames.get(index));
        mutation.accept(commit);
        byte[] payload = JSON.writeValueAsBytes(commit);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < frames.size(); i++) {
            byte[] bytes = i == index ? payload : frames.get(i);
            out.write(ByteBuffer.allocate(8).putInt(bytes.length).putInt(~bytes.length).array());
            out.write(bytes);
            out.write(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        Files.write(file, out.toByteArray());
    }
}
