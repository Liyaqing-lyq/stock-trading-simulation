package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroupRefsInputTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LINKS = "/groups/readPostSources";
    @TempDir Path dir;

    @Test void exactEditionAndActorAreRegeneratedAtTheDecisionCut() throws Exception {
        Path firstFile = dir.resolve("first.journal");
        var valid = decision(firstFile, true, "public-a");
        Path otherFile = dir.resolve("other.journal");
        var different = decision(otherFile, true, "public-b");
        var control = decision(dir.resolve("legacy.journal"), false, "public-a");
        assertTrue(JSON.readTree(control.inputText()).at(LINKS).isMissingNode());
        var expected = JSON.readTree(valid.inputText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.at(LINKS + "/0/refs/0"))
                .put("docId", "public-b");
        assertEquals(expected, JSON.readTree(different.inputText()), "两份历史输入仅此版真正引用的公开来源不同");
        assertNotEquals(valid.inputDigest(), different.inputDigest());

        // 另一个真实世界提供合法的输入与摘要；按该值重建原档最后一帧的规范差量和帧 SHA。
        var scan = WorldJournalScanner.scan(firstFile);
        Path wrong = dir.resolve("wrong-source.journal");
        int changes = 0;
        String previous = null;
        try (var writer = new WorldJournal(wrong, scan.runId())) {
            for (var commit : scan.commits()) {
                var events = new ArrayList<WorldEvent>();
                for (var event : commit.events()) {
                    if (!StateDeltaCodec.TYPE.equals(event.type())) {
                        events.add(event);
                        continue;
                    }
                    String current = StateDeltaCodec.apply(scan.runId(), commit.position(), previous, event.payload());
                    if (commit.position() == scan.commits().size()) {
                        String fake = current.replace(JSON.writeValueAsString(valid.inputText()),
                                JSON.writeValueAsString(different.inputText())).replace(valid.inputDigest(), different.inputDigest());
                        assertNotEquals(current, fake);
                        assertTrue(fake.contains(JSON.writeValueAsString(different.inputText())));
                        events.add(new WorldEvent(event.eventId(), event.type(),
                                StateDeltaCodec.encode(scan.runId(), commit.position(), previous, fake)));
                        changes++;
                    } else events.add(event);
                    previous = current;
                }
                writer.commit(commit.commandId(), commit.commandContent(), commit.result(), events);
            }
        }
        assertEquals(1, changes);
        var rejected = assertThrows(IOException.class, () -> open(wrong, true).close());
        assertTrue(rejected.getMessage().contains("决定输入"), rejected.toString());

        try (var w = open(otherFile, true)) {
            String post = expected.at(LINKS + "/0/docId").asText();
            read(w, "carol", post, 2);
            assertEquals("public-b", w.autonomyRoleObservation("carol").at(LINKS + "/0/refs/0/docId").asText());
            long due = w.snapshot().timeMillis() + w.groupsRun().config().leaveMillis();
            w.openRoleSession("carol").leaveGroup("leave", "leave-task", due,
                    new WorldRoleSession.GroupLeaveDraft(WorldWebScenario.GROUPS_OPEN));
            w.advanceTo(due);
            var former = w.autonomyRoleObservation("carol");
            assertTrue(former.at("/profile/readExperiences").toString().contains("群帖更正正文"));
            assertTrue(former.at(LINKS).isMissingNode());
        }
    }

    private DecisionModel.Decision decision(Path journal, boolean scoped, String source) throws Exception {
        DecisionModel.Decision saved;
        try (var w = open(journal, scoped)) {
            for (String ref : List.of("public-a", "public-b", "alice-only")) {
                long when = w.snapshot().timeMillis() + 1;
                var audience = ref.equals("alice-only") ? InformationModel.Audience.direct("alice")
                        : InformationModel.Audience.publicAudience();
                w.schedulePublishDocument("pub-" + ref, "pub-task-" + ref, when, ref, "alice", audience,
                        ref, "材料正文", "公开来源说明", 2, List.of());
                w.advanceTo(when);
                read(w, "alice", ref, 1);
            }
            var group = WorldWebScenario.GROUPS_OPEN;
            long due = w.snapshot().timeMillis() + w.groupsRun().config().postMillis();
            var author = w.openRoleSession("alice");
            author.postToGroup("initial", "initial-task", due, new WorldRoleSession.GroupPostDraft(
                    group, "第一版", "群帖正文", List.of(new WorldRoleSession.Citation("public-b", 1))));
            w.advanceTo(due + WorldWebScenario.GROUPS_DELIVERY_MILLIS);
            var post = w.groupsRun().state().posts().get(0).docId();
            read(w, "carol", post, 1);
            var original = w.autonomyRoleObservation("carol").at(LINKS);
            if (scoped) assertEquals("public-b", original.at("/0/refs/0/docId").asText());
            else assertTrue(original.isMissingNode());
            due = w.snapshot().timeMillis() + w.groupsRun().config().postMillis();
            author.correctGroupPost("correct", "correct-task", due, new WorldRoleSession.GroupCorrectDraft(
                    post, 1, "更正", "群帖更正正文", List.of(new WorldRoleSession.Citation(source, 1),
                    new WorldRoleSession.Citation("alice-only", 1))));
            w.advanceTo(due + WorldWebScenario.GROUPS_DELIVERY_MILLIS);
            assertTrue(w.autonomyRoleObservation("carol").at(LINKS).isMissingNode());
            assertTrue(w.autonomyRoleObservation("rita").at(LINKS).isMissingNode());
            read(w, "bob", post, 2);
            var bob = w.autonomyRoleObservation("bob");
            assertFalse(bob.toString().contains("alice-only"));
            assertFalse(InformationModel.canCite(w.snapshot().information().orElseThrow().information(),
                    "bob", source, 1, w.snapshot().timeMillis()), "看到关联资料编号不会代替本人阅读");
            if (scoped) {
                JsonNode links = bob.at(LINKS);
                assertEquals(1, links.size());
                assertEquals(post, links.at("/0/docId").asText());
                assertEquals(2, links.at("/0/version").asInt());
                assertEquals(1, links.at("/0/refs").size());
                assertEquals(source, links.at("/0/refs/0/docId").asText());
                assertEquals(1, links.at("/0/refs/0/version").asInt());
            } else assertTrue(bob.at(LINKS).isMissingNode());
            w.beginDecisionSession("session");
            w.openDecisions("open", List.of(new DecisionRun.Request("bob", "source-cut", "wake",
                    new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1, 20, 1_000_000, 1024, 1))));
            saved = w.snapshot().decisions().orElseThrow().state().decisions().get(0);
            assertEquals(bob.at(LINKS), JSON.readTree(saved.inputText()).at(LINKS));
        }
        try (var reopened = open(journal, scoped)) {
            assertEquals(saved, reopened.snapshot().decisions().orElseThrow().state().decisions().get(0));
        }
        return saved;
    }

    private static WorldRuntime open(Path journal, boolean scoped) throws Exception {
        return scoped ? ComposedIndustryScenario.openOpeningCopiesOperating(journal,
                Path.of("src/main/resources/world/opening-copies-example.json"))
                : ComposedIndustryScenario.openBaselinePartsOperating(journal);
    }

    private static void read(WorldRuntime w, String who, String doc, int version) throws Exception {
        long start = w.snapshot().timeMillis() + 1;
        String id = who + "-read-" + doc + "-" + version;
        w.openRoleSession(who).startReading(id, id + "-task", start, doc, version);
        w.advanceTo(start);
        w.advanceTo(w.roleView(who).readings().get(0).dueAt());
    }
}
