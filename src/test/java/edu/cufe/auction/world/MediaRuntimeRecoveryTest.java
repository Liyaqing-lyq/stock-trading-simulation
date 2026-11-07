package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4h.2 恢复真实性：合法 checksum 下的伪造必须被逐帧重算拒绝；写失败不留半帧。
 */
class MediaRuntimeRecoveryTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    static {
        JSON.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    }

    /** 提出选题完成的日志（story + 报告 + 工作记录各一）。 */

    /** 私有临时目录：父目录不存在时先创建（不碰系统 Temp）。 */
    private static Path caseDir(String prefix) throws IOException {
        Path root = Path.of("out", "recovery");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, prefix);
    }

    private static Path proposeJournal(Path dir) throws Exception {
        Path journal = dir.resolve("base.journal");
        try (WorldRuntime world = MediaRuntimeBridgeTest.openForProbe(journal, 100_000L)) {
            world.openRoleSession("rita").proposeStory("p", "pt", 300,
                    new WorldRoleSession.MediaProposeDraft("one", "WIRE", "original"));
            world.advanceTo(300);
        }
        return journal;
    }

    /** 提出选题完成 + 一个尚未完成的提交待办。 */
    private static Path pendingJournal(Path dir) throws Exception {
        Path journal = dir.resolve("pending.journal");
        try (WorldRuntime world = MediaRuntimeBridgeTest.openForProbe(journal, 100_000L)) {
            world.openRoleSession("rita").proposeStory("p", "pt", 300,
                    new WorldRoleSession.MediaProposeDraft("one", "WIRE", "original"));
            world.advanceTo(300);
            long now = world.snapshot().timeMillis();
            world.openRoleSession("rita").submitStory("s", "st", now + 1_000,
                    new WorldRoleSession.MediaSubmitDraft(
                            WorldRuntime.mediaStoryKey("rita", "one"), 0, "标题", "正文", "来源",
                            List.of(new WorldRoleSession.MediaCitation("ann-missing", 1, 1))));
        }
        return journal;
    }

    /** 当前遍历到的是否为最后一个已确认帧（插入类伪造只在最后一帧做，才会与重算不符）。 */
    private static boolean lastFrame;

    /** 逐提交复制日志，允许改写 STATE_SNAPSHOT；返回是否真的改到东西。 */
    private static boolean rewrite(Path source, Path target, Consumer<ObjectNode> mutator)
            throws Exception {
        boolean[] changed = {false};
        try (WorldJournal from = new WorldJournal(source, "media-runtime-test");
             WorldJournal to = new WorldJournal(target, "media-runtime-test")) {
            List<WorldCommit> commits = new ArrayList<>(from.commits());
            for (int index = 0; index < commits.size(); index++) {
                WorldCommit commit = commits.get(index);
                lastFrame = index == commits.size() - 1;
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    if (!"STATE_SNAPSHOT_V1".equals(event.type())) {
                        events.add(event);
                        continue;
                    }
                    ObjectNode root = (ObjectNode) JSON.readTree(event.payload());
                    String before = JSON.writeValueAsString(root);
                    mutator.accept(root);
                    String after = JSON.writeValueAsString(root);
                    if (!before.equals(after)) {
                        changed[0] = true;
                    }
                    events.add(new WorldEvent(event.eventId(), event.type(), after));
                }
                to.commit(commit.commandId(), commit.commandContent(), commit.result(), events);
            }
        }
        return changed[0];
    }

    private static void assertRejected(Path journal, String what) {
        assertThrows(Exception.class, () -> {
            try (WorldRuntime ignored = MediaRuntimeBridgeTest.openForProbe(journal, 100_000L)) {
                // 打开即应失败
            }
        }, what);
    }

    private static ObjectNode storyNode(ObjectNode root) {
        JsonNode states = root.path("media").path("states");
        if (!states.isArray() || states.size() == 0) {
            return null;
        }
        JsonNode stories = states.get(0).path("stories");
        if (!stories.isArray() || stories.size() == 0 || !stories.get(0).isObject()) {
            return null;
        }
        return (ObjectNode) stories.get(0);
    }

    /** 改写最后一帧里在途媒体任务的字段（payload 是两层转义 JSON）。 */
    private static void mutatePendingMedia(ObjectNode state, java.util.function.Consumer<ObjectNode> bodyMutation) {
        JsonNode pending = state.path("pending");
        if (!pending.isArray()) {
            return;
        }
        for (JsonNode node : pending) {
            if (!node.path("kind").asText().startsWith("MEDIA_")) {
                continue;
            }
            ObjectNode task = (ObjectNode) node;
            try {
                ObjectNode role = (ObjectNode) JSON.readTree(task.get("payload").asText());
                ObjectNode mediaBody = (ObjectNode) JSON.readTree(role.get("body").asText());
                bodyMutation.accept(mediaBody);
                role.put("body", JSON.writeValueAsString(mediaBody));
                task.put("payload", JSON.writeValueAsString(role));
            } catch (Exception failure) {
                throw new IllegalStateException("改写夹具失败", failure);
            }
            return;
        }
    }

    @Test
    void pendingMediaTaskTamperingIsRejected() throws Exception {
        Path dir = caseDir("pending-tamper-");
        Path base = pendingJournal(dir);

        Path draft = dir.resolve("draft.journal");
        assertTrue(rewrite(base, draft, root -> mutatePendingMedia(root, mediaBody -> {
            try {
                ObjectNode inner = (ObjectNode) JSON.readTree(mediaBody.get("body").asText());
                inner.put("topic", "被篡改的选题");
                mediaBody.put("body", JSON.writeValueAsString(inner));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        })));
        assertRejected(draft, "在途任务的选题正文被改写");

        Path position = dir.resolve("position.journal");
        assertTrue(rewrite(base, position, root -> mutatePendingMedia(root, mediaBody ->
                mediaBody.put("observedPosition",
                        mediaBody.path("observedPosition").asLong() + 1))));
        assertRejected(position, "在途任务的观察位置被改写");

        Path organization = dir.resolve("org.journal");
        assertTrue(rewrite(base, organization, root -> mutatePendingMedia(root, mediaBody ->
                mediaBody.put("organizationPartyId", "media-b"))));
        assertRejected(organization, "在途任务的所属组织被改写");

        Path removed = dir.resolve("removed.journal");
        boolean[] deleted = {false};
        assertTrue(rewrite(base, removed, root -> {
            JsonNode pending = root.path("pending");
            if (!pending.isArray()) {
                return;
            }
            for (int index = pending.size() - 1; index >= 0; index--) {
                if (pending.get(index).path("kind").asText().startsWith("MEDIA_")) {
                    ((com.fasterxml.jackson.databind.node.ArrayNode) pending).remove(index);
                    deleted[0] = true;
                }
            }
        }));
        assertTrue(deleted[0]);
        assertRejected(removed, "在途任务被删除");
    }

    @Test
    void forgedTopicIsRejected() throws Exception {
        Path dir = caseDir("topic-");
        Path forged = dir.resolve("forged.journal");
        boolean changed = rewrite(proposeJournal(dir), forged, root -> {
            ObjectNode story = storyNode(root);
            if (story != null) {
                story.put("topic", "forged");
            }
        });
        assertTrue(changed);
        assertRejected(forged, "伪造选题正文必须被逐帧重算拒绝");
    }

    @Test
    void forgedReportIsRejected() throws Exception {
        Path dir = caseDir("report-");
        Path forged = dir.resolve("forged.journal");
        boolean changed = rewrite(proposeJournal(dir), forged, root -> {
            JsonNode reports = root.path("media").path("reports");
            if (!reports.isObject()) {
                return;
            }
            reports.fields().forEachRemaining(entry -> {
                ObjectNode report = (ObjectNode) entry.getValue();
                report.put("accepted", !report.path("accepted").asBoolean());
                report.put("reason", "FORGED");
            });
        });
        assertTrue(changed);
        assertRejected(forged, "伪造报告结论必须被拒绝");
    }

    @Test
    void forgedWorkAndBudgetTogetherAreRejected() throws Exception {
        Path dir = caseDir("work-");
        Path forged = dir.resolve("forged.journal");
        boolean changed = rewrite(proposeJournal(dir), forged, root -> {
            JsonNode budgets = root.path("media").path("budgets");
            JsonNode works = root.path("media").path("works");
            if (!budgets.isArray() || budgets.size() == 0 || !works.isArray()
                    || works.size() == 0) {
                return;
            }
            ObjectNode budget = (ObjectNode) budgets.get(0);
            budget.put("spentMillis", budget.path("spentMillis").asLong() + 7);
            ObjectNode work = (ObjectNode) works.get(0);
            work.put("chargedMillis", work.path("chargedMillis").asLong() + 7);
        });
        assertTrue(changed);
        assertRejected(forged, "工作与预算同步伪造也必须被拒绝");
    }

    @Test
    void forgedStoryVersionIsRejected() throws Exception {
        Path dir = caseDir("version-");
        Path forged = dir.resolve("forged.journal");
        boolean changed = rewrite(proposeJournal(dir), forged, root -> {
            ObjectNode story = storyNode(root);
            if (story != null) {
                story.put("revisionNo", 5);
            }
        });
        assertTrue(changed);
        assertRejected(forged, "伪造稿件版本必须被拒绝");
    }

    @Test
    void deletedPendingMediaTaskIsRejected() throws Exception {
        Path dir = caseDir("delete-");
        Path forged = dir.resolve("forged.journal");
        boolean[] removed = {false};
        boolean changed = rewrite(pendingJournal(dir), forged, root -> {
            var pending = root.path("pending");
            if (!pending.isArray()) {
                return;
            }
            for (int index = pending.size() - 1; index >= 0; index--) {
                String kind = pending.get(index).path("kind").asText();
                if (kind.startsWith("MEDIA_")) {
                    ((com.fasterxml.jackson.databind.node.ArrayNode) pending).remove(index);
                    removed[0] = true;
                }
            }
        });
        assertTrue(changed && removed[0]);
        assertRejected(forged, "删掉媒体待办必须被拒绝");
    }

    @Test
    void insertedPendingMediaTaskIsRejected() throws Exception {
        Path dir = caseDir("insert-");
        Path forged = dir.resolve("forged.journal");
        boolean changed = rewrite(proposeJournal(dir), forged, root -> {
            if (!lastFrame) {
                return;
            }
            JsonNode pending = root.path("pending");
            if (!pending.isArray()) {
                return;
            }
            ObjectNode fake = JSON.createObjectNode();
            fake.put("taskId", "roleT:4:rita:ghost");
            fake.put("dueTimeMillis", pending.size() > 0
                    ? pending.get(0).path("dueTimeMillis").asLong() + 1_000 : 5_000);
            fake.put("phase", "COMPLETION");
            fake.put("kind", "MEDIA_STORY_SUBMIT_V1");
            fake.put("payload", "{}");
            ((com.fasterxml.jackson.databind.node.ArrayNode) pending).add(fake);
        });
        assertTrue(changed);
        assertRejected(forged, "插入伪造媒体待办必须被拒绝");
    }

    @Test
    void writeFailureLeavesNoHalfMediaFrame() throws Exception {
        Path dir = caseDir("io-");
        Path journal = pendingJournal(dir);
        WorldRuntime world = MediaRuntimeBridgeTest.openForProbe(journal, 100_000L);
        WorldRuntime.Snapshot checkpoint = world.snapshot();
        long due = checkpoint.timeMillis() + 1_000;
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> world.advanceTo(due));
        } finally {
            Thread.interrupted();
        }
        assertEquals(checkpoint, world.snapshot(), "写失败不能发布半帧");
        world.close();
        try (WorldRuntime restored = MediaRuntimeBridgeTest.openForProbe(journal, 100_000L)) {
            assertEquals(checkpoint, restored.snapshot(), "重开后仍是写失败前的确认帧");
            restored.advanceTo(due);
            MediaModel.State state = restored.snapshot().media().orElseThrow().stateOf("media-a");
            // 证据不存在：业务拒绝也必须被持久化，世界继续
            assertEquals(MediaModel.Status.DRAFT, state.stories().get(0).status());
            assertTrue(restored.snapshot().media().orElseThrow().reports().values().stream()
                    .anyMatch(report -> !report.accepted()
                            && MediaRun.KIND_SUBMIT.equals(report.kind())));
        }
    }
}
