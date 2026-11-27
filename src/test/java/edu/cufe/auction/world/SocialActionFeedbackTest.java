package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SocialActionFeedbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SENDER = "sam";
    @TempDir Path dir;

    @Test void senderFollowsOnlyTheBoundMessageAndNeverGetsTheRecipientsReadStatus() throws Exception {
        Path file = dir.resolve("messages.journal");
        var expected = JSON.createArrayNode();
        try (var world = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            read(world, SENDER, "social:contact-intro");
            for (String to : new String[]{"nina", "omar"}) {
                String action = "letter-to-" + to;
                var ref = send(world, action, to);
                assertEquals("PENDING", last(world, SENDER).path("execution").textValue());
                world.advanceTo(ref.dueMillis());
                var writing = last(world, SENDER);
                assertEquals("IN_PROGRESS", writing.path("execution").textValue());
                assertEquals("WRITING", writing.path("messageStatus").textValue());
                String id = SocialMessageModel.messageId(SENDER, DecisionRun.actionCommandId(action));
                assertEquals(id, writing.required("messageId").textValue());
                var draft = world.snapshot().social().orElseThrow().state().drafts().get(0);
                world.advanceTo(draft.completedAt());
                var queued = last(world, SENDER);
                assertEquals("QUEUED", queued.required("messageStatus").textValue());
                assertEquals("IN_PROGRESS", queued.required("execution").textValue());
                assertFalse(queued.has("accepted"));
                var mail = world.socialView(SENDER).outbox().stream().filter(m -> id.equals(m.messageId())).findFirst().orElseThrow();
                world.advanceTo(mail.dueAt());
                var delivered = last(world, SENDER);
                assertEquals("APPLIED", delivered.required("execution").textValue());
                assertEquals("DELIVERED", delivered.required("messageStatus").textValue());
                assertEquals(to, delivered.required("toPartyId").textValue());
                assertEquals(mail.dueAt(), delivered.required("resultAt").longValue());
                assertTrue(delivered.required("accepted").booleanValue());
                assertFalse(delivered.has("read"));
                assertFalse(delivered.has("resultPosition"));
                read(world, to, SocialMessageModel.docIdFor(id));
                assertEquals(delivered, last(world, SENDER));
                assertFalse(last(world, to).has("messageId"));
                expected.add(delivered);

                var altered = JSON.createObjectNode().put("actionId", action).put("tool", "social.send");
                var begin = JSON.createObjectNode().put("draftJson", JSON.createObjectNode().put("toPartyId", "bea").toString());
                var commands = Map.of(DecisionRun.actionCommandId(action), JSON.createObjectNode().put("body", begin.toString()).toString());
                var cut = world.snapshot();
                SocialActionFeedback.apply(altered,SENDER,cut.decisions().orElseThrow(),cut.social().orElseThrow(),
                        commands,cut.position(),cut.timeMillis());
                assertFalse(altered.has("messageId"));
            }
        }
        try (var reopened = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            var rows = reopened.autonomyRoleObservation(SENDER).at("/engineering/deliberation/recentDecisions");
            assertEquals(expected.get(1), rows.get(0));
            assertEquals(expected.get(0), rows.get(1));
        }
    }

    @Test void lossOfReachabilityDuringWritingOrDeliveryStaysPrivateAndBlockActionsReportToTheirOwner() throws Exception {
        for (boolean stillWriting : new boolean[]{true, false}) {
            try (var world = ComposedIndustryScenario.openInterviewTenCompanyStudy(dir.resolve("blocked-" + stillWriting + ".journal"))) {
                read(world, SENDER, "social:contact-intro");
                var ref = send(world, "at-risk", "nina");
                world.advanceTo(ref.dueMillis());
                var run = world.snapshot().social().orElseThrow();
                long finish = run.state().drafts().get(0).completedAt();
                if (!stillWriting) world.advanceTo(finish);
                // 直接角色动作可安排在当前短暂写作或投递窗口内，只由屏蔽一方执行。
                long effect = world.snapshot().timeMillis() + run.blockMillis();
                world.openRoleSession("nina").blockPerson("pref","pref",effect,SENDER);
                world.advanceTo(effect);
                world.advanceTo(finish + run.config().deliveryMillis());
                var failed = last(world, SENDER);
                assertEquals("REJECTED", failed.required("execution").textValue());
                assertEquals("UNDELIVERED", failed.required("businessReject").textValue());
                assertEquals("UNDELIVERED", failed.required("messageStatus").textValue());
                assertFalse(failed.toString().contains("BLOCKED"));
                assertTrue(world.socialView("nina").inbox().isEmpty());
                var args = JSON.createObjectNode().put("targetPartyId", SENDER);
                var unblock = PersonalMarketTest.decide(world, "nina", "restore", "social.unblock", args);
                world.advanceTo(unblock.dueMillis());
                var restored = last(world, "nina");
                assertTrue(restored.required("accepted").booleanValue());
                assertEquals("UNBLOCKED", restored.required("reason").textValue());
                var block = PersonalMarketTest.decide(world, "nina", "block-again", "social.block", args);
                world.advanceTo(block.dueMillis());
                assertEquals("BLOCKED",last(world,"nina").required("reason").textValue());
                assertEquals(failed,last(world,SENDER));
                var cannotBegin = send(world, "not-reachable", "nina");
                world.advanceTo(cannotBegin.dueMillis());
                assertEquals("UNDELIVERED",last(world,SENDER).required("businessReject").textValue());
            }
        }
    }

    @Test void originalV6RetainsItsUnknownDecisionDespiteADeliveredOutboxEntry() throws Exception {
        Path file = dir.resolve("v6.journal");
        String oldInput;
        try (var world=ComposedIndustryScenario.openPersonalTenCompanyStudy(file)) {
            read(world,SENDER,"social:contact-intro");
            var ref=send(world,"legacy","nina");
            var rules=world.snapshot().social().orElseThrow().config();
            world.advanceTo(ref.dueMillis()+rules.sendMillis()+rules.deliveryMillis());
            assertEquals(SocialMessageModel.OutboxStatus.DELIVERED,world.socialView(SENDER).outbox().get(0).status());
            assertEquals("UNKNOWN",last(world,SENDER).required("execution").textValue());
            oldInput=world.autonomyRoleObservation(SENDER).toString();
        }
        try (var reopened=ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            assertEquals(oldInput,reopened.autonomyRoleObservation(SENDER).toString());
        }
    }

    private static DecisionRun.ActionRef send(WorldRuntime world,String action,String to) throws Exception {
        var args=JSON.createObjectNode().put("toPartyId",to).put("channel","私信").put("title","核对资料").put("body","哪份资料可核对？");
        var ref=PersonalMarketTest.decide(world,SENDER,action,"social.send",args);
        assertNotNull(ref);
        return ref;
    }

    private static JsonNode last(WorldRuntime world,String person) throws Exception {
        return world.autonomyRoleObservation(person).at("/engineering/deliberation/lastActionResult");
    }

    private static void read(WorldRuntime world,String person,String doc) throws Exception {
        long at=world.snapshot().timeMillis()+1;
        world.openRoleSession(person).startReading("read-"+at,"read-"+at,at,doc,1);
        world.advanceTo(at);
        var reading=world.roleView(person).readings().stream().filter(r->doc.equals(r.ref().docId())).findFirst().orElseThrow();
        world.advanceTo(reading.dueAt());
    }
}
