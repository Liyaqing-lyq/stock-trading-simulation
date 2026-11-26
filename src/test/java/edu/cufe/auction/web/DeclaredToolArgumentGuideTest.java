package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DeclaredToolArgumentGuideTest {
    @TempDir Path dir;

    private WorldRuntime openWorld() throws Exception {
        var draft = dir.resolve("opening-copies.json");
        if (!Files.exists(draft)) Files.copy(Path.of("src/main/resources/world/opening-copies-example.json"), draft);
        return AutonomousLifecycleProbe.openWorld("OPENING_COPIES", dir.resolve("world.journal"));
    }

    private static void read(WorldRuntime w, String person, String doc) throws Exception {
        long at = w.snapshot().timeMillis()+1;
        w.openRoleSession(person).startReading("read-"+at, "read-task-"+at, at, doc, 1);
        w.advanceTo(at);
        w.advanceTo(w.roleView(person).readings().get(0).dueAt());
    }

    private static boolean hasDoc(JsonNode rows, String id) {
        for (var entry : rows) if (id.equals(entry.path("docId").asText())) return true;
        return false;
    }

    @Test void originalFundViewBytesAndToolsAreStableAndAMessageNeverGrantsSourceAccess() throws Exception {
        try (var previous = ComposedIndustryScenario.openFundOfficeRoutingOperating(dir.resolve("old-fund.journal"))) {
            var observation = previous.autonomyRoleObservation("bob");
            // 原 c698b8d5 基金档同入口同人物开局的完整可见 JSON 字节摘要。
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(observation.toString().getBytes(StandardCharsets.UTF_8)));
            assertEquals("4faad11999bcc459cb0dd2c1495cdb2494e9ddd5bbeda40d9b4c5e3f20dd9f72", digest);
            assertFalse(observation.path("engineering").has("toolArgumentGuide"));
        }
        String doc = "private-source-for-researcher";
        try (var w = openWorld()) {
            var own = w.autonomyRoleObservation("alice");
            assertEquals("declared-fields-v1", own.at("/engineering/toolArgumentGuide").textValue());
            long at = w.snapshot().timeMillis()+1;
            w.schedulePublishDocument("source", "source-task", at, doc, "alice",
                    InformationModel.Audience.direct("alice"), "工作底稿", "仅研究员可阅读的原文", "本人记录", 2, List.of());
            w.advanceTo(at);
            read(w, "alice", doc);
            read(w, "alice", "fund-mandate:cufe:fund-a");
            var channel = w.socialView("alice").contacts().stream()
                    .filter(c -> c.targetPartyId().equals("bob") && c.channel().startsWith("基金工作/")).findFirst().orElseThrow();
            assertTrue(hasDoc(w.autonomyRoleObservation("alice").at("/engineering/researchNotes/citableReadings"), doc));
            long due = w.snapshot().timeMillis()+WorldWebScenario.SOCIAL_SEND_MILLIS;
            w.openRoleSession("alice").sendMessage("letter", "letter-task", due,
                    new WorldRoleSession.SocialSendDraft("bob", channel.channel(), "资料名称", "内部底稿编号 "+doc));
            w.advanceTo(due+WorldWebScenario.SOCIAL_DELIVERY_MILLIS);
            String letter = w.socialView("bob").inbox().get(0).docId();
            read(w, "bob", letter);
            var reader = w.autonomyRoleObservation("bob");
            assertTrue(hasDoc(reader.at("/engineering/researchNotes/citableReadings"), letter));
            assertFalse(hasDoc(reader.at("/engineering/researchNotes/citableReadings"), doc));
            assertFalse(hasDoc(reader.at("/profile/catalog"), doc));
        }
        try (var w = openWorld()) {
            assertFalse(hasDoc(w.autonomyRoleObservation("bob").at("/engineering/researchNotes/citableReadings"), doc));
        }
    }

    @Test void selectedProbeKeepsOnlyRequestedStaffAcrossWorldTimeAndRejectsUnknownPeople() throws Exception {
        assertNull(new AutonomousLifecycleProbe.Config("OPENING_COPIES", 4, 1, 180_000,
                3_600_000, null, "fixture").persons());
        assertThrows(IllegalArgumentException.class, () -> new AutonomousLifecycleProbe.Config(
                "OPENING_COPIES", 4, 1, 180_000, 3_600_000, null, "fixture", List.of()));
        var config = new AutonomousLifecycleProbe.Config("OPENING_COPIES", 4, 1, 180_000,
                3_600_000, null, "fixture", List.of("alice", "bob"));
        assertEquals(LiveDecisionProvider.DECLARED_FIELDS_SYSTEM_TEXT, AutonomousLifecycleProbe.systemPrompt(config));
        var people = new ConcurrentLinkedQueue<String>();
        try (var routes = SceneRoutes.open(dir);
             var host = new WorldWebHost(() -> {
                 var w = openWorld(); return new WorldWebHost.Opened(w, w.profile().manifest().runId());
             }, routes, (call, timeout) -> {
                 try {
                     var view = new com.fasterxml.jackson.databind.ObjectMapper().readTree(call.inputText());
                     people.add(view.required("person").textValue());
                     String goal = view.at("/engineering/deliberation/objectives/0/id").asText();
                     String body = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("tool","role.wait")
                             .set("decision", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                                     .put("objective",goal).put("basis","待复查").put("reviewAfterMillis",60000)).toString();
                     return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),
                             call.attemptId(),call.attemptNo(),call.recoveryGeneration(),body,
                             DecisionModel.Usage.unknownCost(1,10,20),"FIXTURE"));
                 } catch (Exception cause) { throw new AssertionError(cause); }
             })) {
            routes.replace(0, AutonomousLifecycleProbe.probeRoutes(routes.view(),"OPENING_COPIES"));
            var initial = host.autonomyStatus();
            long position = Long.parseLong(initial.position());
            assertThrows(IllegalArgumentException.class,()->host.startAutonomy(position,routes.view().revision(),
                    config.targetMillis(),config.stepMillis(),Set.of("nobody")));
            host.startAutonomy(position,routes.view().revision(),config.targetMillis(),config.stepMillis(),Set.copyOf(config.persons()));
            long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (host.autonomyStatus().running() && System.nanoTime()<deadline) Thread.sleep(10);
            assertEquals("TARGET_REACHED",host.autonomyStatus().reason());
            assertEquals(Set.of("alice","bob"),Set.copyOf(people));
            assertTrue(host.autonomyStatus().cycles()>1);
        }
    }
}
