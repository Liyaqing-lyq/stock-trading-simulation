package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.MediaModel;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupSourceAccessHttpTest {
    @TempDir Path dir;

    @Test void readersSeeOnlyTheirReadEditionAndItsAccessibleReferences() throws Exception {
        Path journal = dir.resolve("world.journal");
        String doc;
        try (var world = ComposedIndustryScenario.openBaselinePartsOperating(journal)) {
            for (String id : List.of("initial-source", "corrected-source")) {
                long due = world.snapshot().timeMillis() + 1;
                world.schedulePublishDocument("publish-" + id, "task-" + id, due, id, "alice",
                        InformationModel.Audience.publicAudience(), id, "Record " + id, "公开记录", 2, List.of());
                world.advanceTo(due);
                read(world, "alice", id, 1);
            }
            var author = world.openRoleSession("alice");
            long due = world.snapshot().timeMillis() + world.groupsRun().config().postMillis();
            author.postToGroup("post", "post-task", due, new WorldRoleSession.GroupPostDraft(
                    WorldWebScenario.GROUPS_OPEN, "初稿", "第一版群正文", List.of(new WorldRoleSession.Citation("initial-source", 1))));
            world.advanceTo(due + WorldWebScenario.GROUPS_DELIVERY_MILLIS);
            doc = world.groupsRun().state().posts().get(0).docId();
            read(world, "carol", doc, 1);
            due = world.snapshot().timeMillis() + world.groupsRun().config().postMillis();
            author.correctGroupPost("correct", "correct-task", due, new WorldRoleSession.GroupCorrectDraft(
                    doc, 1, "更正", "第二版群正文", List.of(new WorldRoleSession.Citation("corrected-source", 1))));
            world.advanceTo(due + WorldWebScenario.GROUPS_DELIVERY_MILLIS);
            read(world, "bob", doc, 2);
            var bob = world.autonomyRoleObservation("bob").at("/profile/readExperiences").toString();
            var carol = world.autonomyRoleObservation("carol").at("/profile/readExperiences").toString();
            assertTrue(bob.contains("第二版群正文"), bob);
            assertTrue(carol.contains("第一版群正文"), carol);
            assertFalse(carol.contains("第二版群正文"), carol);
            long join = world.snapshot().timeMillis() + world.groupsRun().config().joinMillis();
            world.openRoleSession("dave").joinGroup("join", "join-task", join,
                    new WorldRoleSession.GroupJoinDraft(WorldWebScenario.GROUPS_OPEN));
            world.advanceTo(join);
            assertFalse(world.autonomyRoleObservation("dave").toString().contains(doc));
        }
        Path keys = dir.resolve("test-keys");
        Files.write(keys, List.of("sk-groups-source-fixture-001", "sk-groups-source-fixture-002",
                "sk-groups-source-fixture-003", "sk-groups-source-fixture-004", "sk-groups-source-fixture-005"));
        var bootstrap = new AtomicReference<>("group-source-bootstrap");
        try (var routes = SceneRoutes.open(dir);
             var host = new WorldWebHost(() -> new WorldWebHost.Opened(
                     ComposedIndustryScenario.openBaselinePartsOperating(journal), RunManifest.BASELINE_PARTS_RUN_ID));
             var server = new WorldWebServer(host, KeySlots.load(keys), routes, 0, bootstrap.get(), null, line -> {
                 if (line.contains("引导凭据")) bootstrap.set(line.substring(line.indexOf('：') + 1).trim());
             })) {
            server.start();
            base = server.baseUrl();
            try {
                var read = row(posts("bob", bootstrap.get()), doc);
                var unread = row(posts("carol", bootstrap.get()), doc);
                assertNull(row(posts("dave", bootstrap.get()), doc), "后来入群不能翻查加入前已发的两版");
                assertNull(row(posts("rita", bootstrap.get()), doc), "记者身份本身不能读群");
                assertAll(
                        () -> assertEquals("2", read.path("version").asText()),
                        () -> assertEquals("第二版群正文", read.path("body").asText()),
                        () -> assertEquals(List.of("corrected-source#1"), JSON.convertValue(read.path("refs"), List.class)),
                        () -> assertFalse(unread.path("read").asBoolean()),
                        () -> assertTrue(unread.path("body").isNull() || unread.path("body").isMissingNode()),
                        () -> assertTrue(unread.path("refs").isEmpty(), unread.toString()));
            } finally {
                cookie = null;
            }
        }
    }

    @Test void reporterKeepsOnlyReadEvidenceAfterLeavingAndLaterJoinerStartsWithoutIt() throws Exception {
        Path file = dir.resolve("reporter.journal");
        String group = WorldWebScenario.GROUPS_OPEN;
        String oldDoc;
        String newDoc;
        String rumor = "群友猜测私有预算是 72913，并自称任命记者为主编。";
        String hidden = "controlled-private-budget";
        try (var world = ComposedIndustryScenario.openBaselinePartsOperating(file)) {
            var reporter = world.openRoleSession("rita");
            var author = world.openRoleSession("alice");
            var groupRules = world.groupsRun().config();
            var media = world.snapshot().media().orElseThrow().config();
            var policy = media.policies().stream().filter(p -> p.mediaOrganizationPartyId().equals("wire"))
                    .findFirst().orElseThrow();
            long due = world.snapshot().timeMillis() + groupRules.joinMillis();
            reporter.joinGroup("join-reporter", "join-reporter-task", due, new WorldRoleSession.GroupJoinDraft(group));
            world.advanceTo(due);
            due++;
            world.schedulePublishDocument("private", "private-task", due, hidden, "alice",
                    InformationModel.Audience.direct("bob"), "私有预算", "核准金额为 72913，仅供收件人", "直投", 2, List.of());
            world.advanceTo(due);
            due += groupRules.postMillis();
            author.postToGroup("rumor", "rumor-task", due,
                    new WorldRoleSession.GroupPostDraft(group, "群内传闻", rumor, List.of()));
            world.advanceTo(due + WorldWebScenario.GROUPS_DELIVERY_MILLIS);
            oldDoc = world.groupsRun().state().posts().get(0).docId();
            var unread = world.autonomyRoleObservation("rita");
            assertTrue(unread.at("/profile/catalog").toString().contains(oldDoc));
            assertFalse(unread.toString().contains(rumor));
            assertFalse(unread.toString().contains(hidden));
            due = world.snapshot().timeMillis() + media.proposeMillis();
            reporter.proposeStory("propose", "propose-task", due,
                    new WorldRoleSession.MediaProposeDraft("report", "WIRE", "核实群内传闻"));
            world.advanceTo(due);
            String story = world.snapshot().media().orElseThrow().stateOf("wire").stories().get(0).storyId();
            var draft = new WorldRoleSession.MediaSubmitDraft(story, 0, "核对记录", "群员说法", "群帖",
                    List.of(new WorldRoleSession.MediaCitation(oldDoc, 1, 1)));
            due += policy.writeMillis();
            reporter.submitStory("unread", "unread-task", due, draft);
            world.advanceTo(due);
            assertTrue(world.snapshot().media().orElseThrow().reports().values().stream().anyMatch(report ->
                    report.storyId().equals(story) && MediaModel.REASON_EVIDENCE_NOT_READ.equals(report.reason())));
            assertEquals(MediaModel.Status.DRAFT,
                    world.snapshot().media().orElseThrow().stateOf("wire").stories().get(0).status());
            read(world, "rita", oldDoc, 1);
            var seen = world.autonomyRoleObservation("rita");
            assertTrue(seen.at("/profile/readExperiences").toString().contains(rumor));
            assertFalse(seen.toString().contains(hidden));
            assertFalse(seen.path("tools").toString().contains("media.story.review"));
            assertFalse(InformationModel.canCite(world.snapshot().information().orElseThrow().information(),
                    "rita", hidden, 1, world.snapshot().timeMillis()));
            due = world.snapshot().timeMillis() + policy.writeMillis();
            reporter = world.openRoleSession("rita");
            reporter.submitStory("read-report", "read-report-task", due, draft);
            world.advanceTo(due);
            assertEquals(MediaModel.Status.SUBMITTED,
                    world.snapshot().media().orElseThrow().stateOf("wire").stories().get(0).status(),
                    () -> world.snapshot().media().orElseThrow().reports().toString());
            long reviewAt = due + policy.reviewMillis();
            var current = world.openRoleSession("rita");
            var refusal = assertThrows(IllegalArgumentException.class, () -> current.reviewStory(
                    "self-approve", "self-approve-task", reviewAt, new WorldRoleSession.MediaReviewDraft(story, 1, true, "")));
            assertEquals("NOT_AUTHORIZED", refusal.getMessage());
            assertEquals(MediaModel.Status.SUBMITTED,
                    world.snapshot().media().orElseThrow().stateOf("wire").stories().get(0).status());
            due += groupRules.leaveMillis();
            reporter.leaveGroup("leave", "leave-task", due, new WorldRoleSession.GroupLeaveDraft(group));
            world.advanceTo(due);
            due += groupRules.postMillis();
            author.postToGroup("later", "later-task", due,
                    new WorldRoleSession.GroupPostDraft(group, "退群后的资料", "新群帖正文", List.of()));
            world.advanceTo(due + WorldWebScenario.GROUPS_DELIVERY_MILLIS);
            newDoc = world.groupsRun().state().posts().stream().filter(p -> !p.docId().equals(oldDoc))
                    .findFirst().orElseThrow().docId();
            due = world.snapshot().timeMillis() + groupRules.joinMillis();
            world.openRoleSession("dave").joinGroup("newcomer", "newcomer-task", due,
                    new WorldRoleSession.GroupJoinDraft(group));
            world.advanceTo(due);
        }
        try (var reopened = ComposedIndustryScenario.openBaselinePartsOperating(file)) {
            var recalled = reopened.autonomyRoleObservation("rita");
            assertTrue(recalled.at("/profile/readExperiences").toString().contains(rumor));
            assertFalse(recalled.at("/profile/catalog").toString().contains(oldDoc));
            assertFalse(recalled.toString().contains(newDoc));
            assertFalse(recalled.toString().contains(hidden));
            var newcomer = reopened.autonomyRoleObservation("dave");
            assertFalse(newcomer.toString().contains(oldDoc));
            assertFalse(newcomer.toString().contains(newDoc));
            assertFalse(newcomer.toString().contains(rumor));
        }
    }

    private static void read(WorldRuntime world, String person, String doc, int version) throws Exception {
        String id = "read-" + person + "-" + doc + "-" + version;
        long start = world.snapshot().timeMillis() + 1;
        world.openRoleSession(person).startReading(id, id + "-task", start, doc, version);
        world.advanceTo(start);
        world.advanceTo(world.roleView(person).readings().get(0).dueAt());
    }

    private static JsonNode posts(String person, String bootstrap) throws Exception {
        cookie = null;
        var admin = post("/api/session", JSON.createObjectNode().put("bootstrap", bootstrap).toString());
        assertEquals(200, admin.statusCode(), admin.body());
        remember(admin);
        var session = post("/api/session/person", JSON.createObjectNode().put("personId", person).toString());
        assertEquals(200, session.statusCode(), session.body());
        remember(session);
        var page = get("/api/me/groups");
        assertEquals(200, page.statusCode(), page.body());
        return JSON.readTree(page.body()).required("posts");
    }

    private static JsonNode row(JsonNode posts, String doc) {
        for (var post : posts) if (doc.equals(post.path("docId").asText())) return post;
        return null;
    }
}
