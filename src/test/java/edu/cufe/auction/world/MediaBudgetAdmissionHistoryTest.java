package edu.cufe.auction.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 同时刻的预算按实际提交顺序准入，后续取消不会追认已拒绝的工作。 */
class MediaBudgetAdmissionHistoryTest {
    private static void prepare(WorldRuntime world, boolean reporterFirst) throws Exception {
        world.schedulePublishDocument("source", "source-task", 1, "source-doc", "alice",
                InformationModel.Audience.publicAudience(), "source", "simulated", "fixture", 1,
                List.of("source-doc"));
        world.advanceTo(1);
        world.openRoleSession("rita").startReading("read", "read-task", 2, "source-doc", 1);
        world.advanceTo(3);
        world.openRoleSession("rita").proposeStory("p", "pt", 303,
                new WorldRoleSession.MediaProposeDraft("one", "WIRE", "simulated"));
        world.advanceTo(303);
        var story = world.snapshot().media().orElseThrow().stateOf("media-a").stories().get(0);
        var ref = world.roleView("rita").observations().get(0).ref();
        world.openRoleSession("rita").submitStory("submit", "submit-task", 1303,
                new WorldRoleSession.MediaSubmitDraft(story.storyId(), 0, "title", "body", "source",
                        List.of(new WorldRoleSession.MediaCitation(ref.docId(), ref.version(), ref.index()))));
        world.advanceTo(1303);
        story = world.snapshot().media().orElseThrow().stateOf("media-a").stories().get(0);
        world.openRoleSession("ed").startReading("edit-read", "edit-read-task", 1304, story.draftDocId(), 1);
        world.advanceTo(1354);
        // 内部任务名称含人物前缀，ed 排在 rita 前；真实准入不能依赖它。
        if (reporterFirst) {
            world.openRoleSession("rita").proposeStory("q", "a-propose-task", 1654,
                    new WorldRoleSession.MediaProposeDraft("two", "WIRE", "simulated"));
        }
        world.openRoleSession("ed").reviewStory("review", "z-review-task", 1854,
                new WorldRoleSession.MediaReviewDraft(story.storyId(), 1, true, ""));
        if (!reporterFirst) {
            world.openRoleSession("rita").proposeStory("q", "a-propose-task", 1654,
                    new WorldRoleSession.MediaProposeDraft("two", "WIRE", "simulated"));
        }
    }

    private static Path journal() throws Exception {
        Path root = Path.of("target", "media-admission-history");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "case-").resolve("world.journal");
    }

    @Test void sameTimestampUsesAdmissionOrderAcrossRecovery() throws Exception {
        Path journal = journal();
        try (var world = MediaRuntimeBridgeTest.openForProbe(journal, 1800)) { prepare(world, true); }
        try (var world = MediaRuntimeBridgeTest.openForProbe(journal, 1800)) {
            world.advanceTo(1854);
            assertEquals(1600, world.snapshot().media().orElseThrow().budgetOf("media-a").spentMillis());
            assertEquals(2, world.snapshot().media().orElseThrow().stateOf("media-a").stories().size());
        }
    }

    @Test void cancellingReservationDoesNotRetroactivelyAdmitRejectedWork() throws Exception {
        Path journal = journal();
        try (var world = MediaRuntimeBridgeTest.openForProbe(journal, 1800)) {
            prepare(world, false);
            world.openRoleSession("ed").cancelMediaWork("cancel", "cancel-task", 1454);
            world.advanceTo(1454);
            assertEquals(1400, world.snapshot().media().orElseThrow().budgetOf("media-a").spentMillis());
        }
        try (var world = MediaRuntimeBridgeTest.openForProbe(journal, 1800)) {
            world.advanceTo(1854);
            var media = world.snapshot().media().orElseThrow();
            assertEquals(1400, media.budgetOf("media-a").spentMillis());
            assertEquals(1, media.stateOf("media-a").stories().size());
            assertEquals(1, media.reports().values().stream().filter(r -> r.reason().equals("BUDGET")).count());
        }
    }
}
