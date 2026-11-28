package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

class SocialMessageHistoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    private static JsonNode choose(WorldRuntime world, String person, String action, String tool, Map<String,?> args) throws Exception {
        var call = PharmaDecisionToolTest.open(world, person, action);
        var input = JSON.readTree(call.inputText());
        var reply = JSON.createObjectNode().put("tool", tool);
        reply.set("args", JSON.valueToTree(args));
        reply.putObject("decision").put("objective", input.at("/engineering/deliberation/objectives/0/id").asText())
                .put("basis", "核对自己收到的往来信件").put("reviewAfterMillis",60_000);
        assertEquals("QUEUED", world.offerDecisionCallback(new DecisionModel.Reply(call.runId(),call.actionId(),
                call.attemptId(),call.attemptNo(),call.recoveryGeneration(),reply.toString(),
                DecisionModel.Usage.unknownCost(1,80,30),"fixture")));
        assertEquals(1, world.drainDecisionCallbacks());
        var scheduled = world.snapshot().decisions().orElseThrow().actionRef(action);
        assertNotNull(scheduled);
        world.advanceTo(scheduled.dueMillis());
        return world.autonomyRoleObservation(person);
    }

    private static List<String> ids(JsonNode view,String path) {
        return StreamSupport.stream(view.at(path).spliterator(), false)
                .map(row->row.path("messageId").asText()).toList();
    }

    @Test void bothPartiesCanFindAnOmittedEnvelopeAndOnlyReadingRevealsItsBody() throws Exception {
        Path journal=dir.resolve("mail.journal");
        String target, doc;
        try(var world=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(journal)) {
            world.advanceTo(9L*3_600_000);
            long at=world.snapshot().timeMillis()+1;
            world.openRoleSession("omar").startReading("directory","directory",at,WorldWebScenario.SOCIAL_CONTACT_DOC,1);
            world.advanceTo(at);
            world.advanceTo(world.roleView("omar").readings().get(0).dueAt());
            var channel=world.socialView("omar").contacts().stream().filter(x->x.targetPartyId().equals("nina"))
                    .findFirst().orElseThrow().channel();
            for(int i=1;i<=10;i++) {
                long due=world.snapshot().timeMillis()+60_000;
                String task="letter-"+i;
                world.openRoleSession("omar").sendMessage(task,task,due,new WorldRoleSession.SocialSendDraft(
                        "nina",channel,"编号 "+i,"密封内文-"+i));
                world.advanceTo(due+WorldWebScenario.SOCIAL_DELIVERY_MILLIS);
            }
            var envelopes=world.socialView("nina").inbox().stream()
                    .sorted(java.util.Comparator.comparingLong(SocialMessageModel.EnvelopeView::deliveredAt)).toList();
            var hidden=envelopes.get(2);
            target=hidden.messageId(); doc=hidden.docId();
            var opening=world.autonomyRoleObservation("nina");
            assertEquals(10,opening.at("/social/mailbox/unread").asInt());
            assertEquals(5,ids(opening,"/social/inbox").size());
            assertTrue(ids(opening,"/social/inbox").contains(envelopes.get(0).messageId()));
            assertFalse(ids(opening,"/social/inbox").contains(target));
            assertFalse(ids(world.autonomyRoleObservation("omar"),"/social/outbox").contains(target));
            var page=choose(world,"nina","first-page",RunManifest.PARTICIPANT_HISTORY_TOOL,Map.of("section","messages"));
            var last=page.at("/engineering/historyLookup/lastResult");
            assertEquals(4,last.required("ids").size());
            var next=choose(world,"nina","next-page",RunManifest.PARTICIPANT_HISTORY_TOOL,
                    Map.of("section","messages","before",last.required("nextBefore").asText()));
            assertTrue(ids(next,"/social/inbox").contains(target));
            assertFalse(world.canCite("nina",doc,1));
            assertFalse(next.toString().contains("密封内文-3"));
            var sent=choose(world,"omar","sender-search",RunManifest.PARTICIPANT_HISTORY_TOOL,
                    Map.of("section","messages","id",target));
            assertTrue(ids(sent,"/social/outbox").contains(target));
            var stranger=choose(world,"investor","unrelated",RunManifest.PARTICIPANT_HISTORY_TOOL,
                    Map.of("section","messages","id",target));
            assertTrue(stranger.at("/engineering/historyLookup/lastResult/ids").isEmpty());
            assertFalse(stranger.toString().contains("密封内文-3"));
            assertFalse(world.canReadDocumentVersion("investor",doc,1));
            choose(world,"nina","read-found","role.reading.start",Map.of("docId",doc,"version",1));
            world.advanceTo(world.roleView("nina").readings().get(0).dueAt());
            assertTrue(world.canCite("nina",doc,1));
            assertEquals(9,world.autonomyRoleObservation("nina").at("/social/mailbox/unread").asInt());
        }
        byte[] original=Files.readAllBytes(journal);
        try(var world=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(journal)) {
            assertTrue(world.canCite("nina",doc,1));
            assertTrue(ids(world.autonomyRoleObservation("nina"),"/social/inbox").contains(target));
        }
        assertArrayEquals(original,Files.readAllBytes(journal));
    }
}
