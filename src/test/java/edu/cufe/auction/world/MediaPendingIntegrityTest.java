package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** 正常会话生成在途任务；仅改最后快照并重算合法摘要，必须拒绝伪造排程。 */
class MediaPendingIntegrityTest {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static { JSON.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true)); }

    private static ObjectNode mediaTask(ObjectNode state) {
        for (JsonNode task : state.path("pending"))
            if (MediaRun.KINDS.contains(task.path("kind").asText())) return (ObjectNode) task;
        throw new AssertionError("测试原件必须含真实媒体在途任务");
    }

    private static void changeBody(ObjectNode state, Consumer<ObjectNode> change) {
        try {
            ObjectNode task = mediaTask(state);
            ObjectNode role = (ObjectNode) JSON.readTree(task.path("payload").asText());
            ObjectNode body = (ObjectNode) JSON.readTree(role.path("body").asText());
            change.accept(body);
            role.put("body", JSON.writeValueAsString(body));
            task.put("payload", JSON.writeValueAsString(role));
        } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
    }

    private static void rejected(Consumer<ObjectNode> change) throws Exception {
        rejected(change, false);
    }

    private static void rejected(Consumer<ObjectNode> change, boolean forgeScheduleEvent) throws Exception {
        Path root = Path.of(System.getProperty("media.integrity.test.root", "target/media-integrity-tests"));
        Files.createDirectories(root);
        Path dir = Files.createTempDirectory(root, "case-");
        Path original = dir.resolve("original.journal"), forged = dir.resolve("forged.journal");
        try (var world = MediaRuntimeBridgeTest.openForProbe(original, 10000)) {
            world.openRoleSession("rita").proposeStory("proposal", "proposal-task", 300,
                    new WorldRoleSession.MediaProposeDraft("one", "WIRE", "original"));
        }
        try (var world = MediaRuntimeBridgeTest.openForProbe(original, 10000)) {
            assertEquals(1, world.snapshot().pending().stream().filter(t -> MediaRun.KINDS.contains(t.kind())).count(),
                    "未篡改对照必须可恢复且含一个真实在途工作");
        }
        boolean changed = false;
        try (var from = new WorldJournal(original, "media-runtime-test");
             var to = new WorldJournal(forged, "media-runtime-test")) {
            var commits = from.commits();
            for (var commit : commits) {
                var events = new ArrayList<WorldEvent>();
                for (var event : commit.events()) {
                    if (commit.position() == commits.get(commits.size() - 1).position()
                            && event.type().equals("STATE_SNAPSHOT_V1")) {
                        ObjectNode state = (ObjectNode) JSON.readTree(event.payload());
                        JsonNode before = state.deepCopy();
                        change.accept(state);
                        assertNotEquals(before, state, "反例必须实际改变目标字段");
                        changed = true;
                        events.add(new WorldEvent(event.eventId(), event.type(), JSON.writeValueAsString(state)));
                        if (forgeScheduleEvent) {
                            JsonNode forgedTask = state.path("pending").get(state.path("pending").size() - 1);
                            events.add(new WorldEvent("task-scheduled:" + forgedTask.path("taskId").asText(),
                                    "TASK_SCHEDULED_V1", JSON.writeValueAsString(forgedTask)));
                        }
                    } else events.add(event);
                }
                to.commit(commit.commandId(), commit.commandContent(), commit.result(), events);
            }
        }
        assertTrue(changed);
        assertThrows(java.io.IOException.class, () -> {
            try (var ignored = MediaRuntimeBridgeTest.openForProbe(forged, 10000)) { }
        }, "合法摘要不能掩盖伪造在途任务");
    }

    @Test void pendingBodyCannotChange() throws Exception {
        rejected(state -> changeBody(state, body -> {
            try {
                ObjectNode draft = (ObjectNode) JSON.readTree(body.path("body").asText());
                draft.put("topic", "forged"); body.put("body", JSON.writeValueAsString(draft));
            } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
        }));
    }
    @Test void pendingObservationCannotChange() throws Exception {
        rejected(state -> changeBody(state, body -> body.put("observedPosition", body.path("observedPosition").asLong() + 1)));
    }
    @Test void pendingOrganizationCannotChange() throws Exception {
        rejected(state -> changeBody(state, body -> body.put("organizationPartyId", "media-b")));
    }
    @Test void pendingBudgetRejectionCannotChange() throws Exception {
        rejected(state -> changeBody(state, body -> body.put("budgetRejected", true)));
    }
    @Test void pendingStartTimeCannotChange() throws Exception {
        rejected(state -> changeBody(state, body -> body.put("startedAtMillis", 1)));
    }
    @Test void newlyScheduledTaskCannotDisappear() throws Exception {
        rejected(state -> {
            String id = mediaTask(state).path("taskId").asText();
            ArrayNode pending = (ArrayNode) state.path("pending");
            for (int i = pending.size() - 1; i >= 0; i--)
                if (pending.get(i).path("taskId").asText().equals(id)) pending.remove(i);
        });
    }
    @Test void extraPendingTaskCannotAppear() throws Exception {
        rejected(state -> {
            ObjectNode task = mediaTask(state).deepCopy();
            task.put("taskId", "forged-media-task");
            ((ArrayNode) state.path("pending")).add(task);
            ((ArrayNode) state.path("usedTaskIds")).add("forged-media-task");
        });
    }
    @Test void forgedScheduleEventCannotCloneAnExistingCommand() throws Exception {
        rejected(state -> {
            ObjectNode task = mediaTask(state).deepCopy();
            task.put("taskId", "forged-media-task");
            ((ArrayNode) state.path("pending")).add(task);
            ((ArrayNode) state.path("usedTaskIds")).add("forged-media-task");
        }, true);
    }
}
