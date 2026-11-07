package edu.cufe.auction.world;

import java.nio.file.*;

/** 独立边界回归：命令重试、拒绝任务取消、记者与编辑并发争用组织工时。 */
class MediaRuntimeBoundaryTest {
    static void require(boolean yes, String name) { org.junit.jupiter.api.Assertions.assertTrue(yes, name); }
    static WorldRoleSession.MediaProposeDraft draft(String id) {
        return new WorldRoleSession.MediaProposeDraft(id, "WIRE", "simulated topic");
    }
    static Path directory() throws Exception {
        Path root=Path.of(System.getProperty("media.boundary.test.root", "target/media-boundary-tests"));
        Files.createDirectories(root);return Files.createTempDirectory(root,"case-");
    }
    @org.junit.jupiter.api.Test
    void sameCommandRetryIsIdempotent() throws Exception {
        Path root=directory();
            try (var world = MediaRuntimeBridgeTest.openForProbe(root.resolve("retry.journal"), 10000)) {
                var session = world.openRoleSession("rita");
                String first = session.proposeStory("p", "pt", 300, draft("one"));
                long position = world.snapshot().position();
                String second = session.proposeStory("p", "pt", 300, draft("one"));
                require(first.equals(second) && world.snapshot().position() == position, "retry changed state");
            }
    }
    @org.junit.jupiter.api.Test
    void cancellingBudgetRejectionChargesNothing() throws Exception {
        Path root=directory();
            Path journal = root.resolve("reject-cancel.journal");
            try (var world = MediaRuntimeBridgeTest.openForProbe(journal, 300)) {
                world.openRoleSession("rita").proposeStory("p", "pt", 300, draft("one"));
                world.advanceTo(300);
                world.openRoleSession("rita").proposeStory("q", "qt", 600, draft("two"));
                world.openRoleSession("rita").cancelMediaWork("cancel", "ct", 400);
                world.advanceTo(400);
                require(world.snapshot().media().orElseThrow().budgetOf("media-a").spentMillis() == 300,
                        "cancel must not charge rejected work");
                world.advanceTo(601);
            }
            try (var world = MediaRuntimeBridgeTest.openForProbe(journal, 300)) {
                require(world.snapshot().media().orElseThrow().stateOf("media-a").stories().size() == 1,
                        "rejected story appeared after recovery");
            }
    }
    @org.junit.jupiter.api.Test
    void retryPreservesTaskAndDueIdentity() throws Exception {
        Path root=directory();
            try (var world = MediaRuntimeBridgeTest.openForProbe(root.resolve("retry-binding.journal"), 10000)) {
                var session = world.openRoleSession("rita");
                session.proposeStory("p", "pt", 300, draft("one"));
                boolean wrongTaskRejected=false, wrongTimeRejected=false;
                try { session.proposeStory("p", "different-task", 300, draft("one")); }
                catch (IllegalArgumentException expected) { wrongTaskRejected=true; }
                try { session.proposeStory("p", "pt", 999, draft("one")); }
                catch (IllegalArgumentException expected) { wrongTimeRejected=true; }
                require(wrongTaskRejected && wrongTimeRejected, "same command accepted different task or due time");
            }
    }
    @org.junit.jupiter.api.Test
    void reporterAndEditorShareOrganizationBudget() throws Exception {
        Path root=directory();
            Path journal = root.resolve("competition.journal");
            try (var world = MediaRuntimeBridgeTest.openForProbe(journal, 1800)) {
                world.schedulePublishDocument("source", "source-task", 1, "source-doc", "alice",
                        InformationModel.Audience.publicAudience(), "source", "simulated", "fixture", 1,
                        java.util.List.of("source-doc"));
                world.advanceTo(1);
                world.openRoleSession("rita").startReading("read", "read-task", 2, "source-doc", 1);
                world.advanceTo(3);
                world.openRoleSession("rita").proposeStory("p", "pt", 303, draft("one"));
                world.advanceTo(303);
                var story = world.snapshot().media().orElseThrow().stateOf("media-a").stories().get(0);
                var ref = world.roleView("rita").observations().get(0).ref();
                world.openRoleSession("rita").submitStory("submit", "submit-task", 1303,
                        new WorldRoleSession.MediaSubmitDraft(story.storyId(), 0, "title", "body", "source",
                                java.util.List.of(new WorldRoleSession.MediaCitation(ref.docId(), ref.version(), ref.index()))));
                world.advanceTo(1303);
                story = world.snapshot().media().orElseThrow().stateOf("media-a").stories().get(0);
                world.openRoleSession("ed").startReading("edit-read", "edit-read-task", 1304, story.draftDocId(), 1);
                world.advanceTo(1354);
                world.openRoleSession("ed").reviewStory("review", "review-task", 1854,
                        new WorldRoleSession.MediaReviewDraft(story.storyId(), 1, true, ""));
                world.openRoleSession("rita").proposeStory("q", "qt", 1654, draft("two"));
                require(world.mediaWorkAudit().orElseThrow().budgets().stream()
                        .filter(b -> b.organizationPartyId().equals("media-a")).findFirst().orElseThrow()
                        .reservedMillis() == 500, "reservation exceeds organization cap");
                world.advanceTo(1854);
                var run = world.snapshot().media().orElseThrow();
                require(run.stateOf("media-a").stories().size() == 1, "both reporters consumed same budget");
                require(run.reports().values().stream().filter(report -> report.reason().equals("BUDGET")).count() == 1,
                        "losing reporter must receive business budget rejection");
            }
            try (var world = MediaRuntimeBridgeTest.openForProbe(journal, 1800)) {
                require(world.snapshot().media().orElseThrow().budgetOf("media-a").spentMillis() == 1800,
                        "budget changed during recovery");
            }
    }
}
