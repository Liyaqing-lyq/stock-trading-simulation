package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ParticipantInterviewHistoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    private static void contact(WorldRuntime world) throws Exception {
        long at=world.snapshot().timeMillis()+1;
        world.openRoleSession("rita").startReading("read-contact","read-contact",at,"contact:investor-public",1);
        world.advanceTo(at);
        world.advanceTo(world.roleView("rita").readings().get(0).dueAt());
        world.advanceTo(34_200_000L);
    }

    private static String invite(WorldRuntime world,int number,boolean declined) throws Exception {
        String key = "history-"+number;
        String request = InterviewModel.requestId("rita",key);
        long at = world.snapshot().timeMillis()+world.interviewRun().config().inviteMillis();
        world.openRoleSession("rita").inviteInterview("invite-"+key,"invite-"+key,at,new WorldRoleSession.InterviewInviteDraft(
                key,"investor","关于已披露的年度订单 "+number,at+60_000,at+180_000,60_000));
        world.advanceTo(at);
        if (declined) {
            long due=at+world.interviewRun().config().respondMillis();
            world.openRoleSession("investor").respondInterview("no-"+key,"no-"+key,due,
                    new WorldRoleSession.InterviewRespondDraft(request,false,-1));
            world.advanceTo(due);
            assertTrue(world.interviewRun().coordinations().get(WorldRuntime.roleTaskId("investor","no-"+key)).accepted());
        }
        return request;
    }

    private static JsonNode lookup(WorldRuntime world,String person,String action,Map<String,String> args) throws Exception {
        var call=PharmaDecisionToolTest.open(world,person,action);
        var seen=JSON.readTree(call.inputText());
        var reply=JSON.createObjectNode().put("tool",RunManifest.PARTICIPANT_HISTORY_TOOL);
        reply.set("args",JSON.valueToTree(args));
        reply.putObject("decision").put("objective",seen.at("/engineering/deliberation/objectives/0/id").asText())
                .put("basis","核对本人过去的预约和执行回执。").put("reviewAfterMillis",60000);
        assertEquals("QUEUED",world.offerDecisionCallback(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),
                call.attemptNo(),call.recoveryGeneration(),reply.toString(),DecisionModel.Usage.unknownCost(1,40,30),"fixture")));
        assertEquals(1,world.drainDecisionCallbacks());
        var decisions=world.snapshot().decisions().orElseThrow();
        var ref=decisions.actionRef(action);
        assertNotNull(ref,decisions.state().decisions().get(decisions.state().decisions().size()-1).toString());
        long barrier=DecisionModel.decision(decisions.state(),action).orElseThrow().barrierAtMillis();
        assertEquals(1_000,ref.dueMillis()-barrier);
        world.advanceTo(ref.dueMillis());
        return world.autonomyRoleObservation(person);
    }

    @Test void allPendingAndOnlyFourEndedDefaultThenOwnDetailAndReceiptCanBeRecovered() throws Exception {
        var path=dir.resolve("new.journal");
        String first,live,serialized;
        try (var w=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(path)) {
            contact(w);
            first=invite(w,1,true);
            for(int i=2;i<=7;i++) invite(w,i,true);
            live=invite(w,8,false);
            var preview=w.autonomyRoleObservation("investor").at("/interview/invites");
            assertEquals(5,preview.size());
            assertFalse(preview.toString().contains(first));
            assertTrue(preview.toString().contains(live));
            var page=lookup(w,"investor","first-page",Map.of("section","interview")).at("/engineering/historyLookup/lastResult");
            assertEquals(4,page.required("ids").size());
            String next=page.required("nextBefore").asText();
            var older=lookup(w,"investor","second-page",Map.of("section","interview","before",next));
            assertEquals(3,older.at("/engineering/historyLookup/lastResult/ids").size());
            assertTrue(older.at("/interview/invites").toString().contains(first));
            var exact=lookup(w,"investor","single",Map.of("section","interview","id",first));
            var receipt=exact.at("/engineering/historyLookup/lastResult/receipts/0");
            assertTrue(receipt.required("accepted").booleanValue());
            assertEquals("INTERVIEW_RESPOND_V1",receipt.required("kind").asText());
            assertEquals(first,receipt.required("requestId").asText());
            assertEquals(1,exact.at("/engineering/historyLookup/lastResult/receipts").size());
            assertFalse(w.canCite("investor","contact:investor-public",1));
            var privateCase=lookup(w,"nina","other-person",Map.of("section","interview","id",first));
            var absent=lookup(w,"nina","unknown",Map.of("section","interview","id","missing"));
            for (var blocked:List.of(privateCase,absent)) {
                assertEquals(0,blocked.at("/engineering/historyLookup/lastResult/ids").size());
                assertEquals(0,blocked.at("/engineering/historyLookup/lastResult/receipts").size());
                assertEquals(0,blocked.at("/interview/invites").size());
            }
            serialized=w.autonomyRoleObservation("investor").toString();
        }
        byte[] bytes=Files.readAllBytes(path);
        try (var w=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(path)) {
            assertEquals(serialized,w.autonomyRoleObservation("investor").toString());
        }
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }

    @Test void rejectedInvitationWithoutAnAppointmentKeepsEveryOwnReceiptButRevealsNothingToItsTarget() throws Exception {
        try (var w=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(dir.resolve("rejections.journal"))) {
            contact(w);
            var times = new java.util.ArrayList<Long>();
            String key="early-empty-case";
            String id=InterviewModel.requestId("rita",key);
            for(int i=0;i<6;i++) {
                long at=w.snapshot().timeMillis()+w.interviewRun().config().inviteMillis();
                String command="early-"+i;
                w.openRoleSession("rita").inviteInterview(command,command,at,new WorldRoleSession.InterviewInviteDraft(
                        key,"investor","核对窗口",at+59_999,at+180_000,60_000));
                w.advanceTo(at);
                var receipt=w.interviewRun().coordinations().get(WorldRuntime.roleTaskId("rita",command));
                assertEquals("WINDOW",receipt.reason());
                times.add(at);
            }
            assertEquals(0,w.interviewView("rita").invites().size());
            var found=lookup(w,"rita","failed-lookup",Map.of("section","interview","id",id));
            var result=found.at("/engineering/historyLookup/lastResult");
            assertEquals(4,result.required("receipts").size());
            assertEquals(1,result.at("/receipts/0/windowTiming/outsideByMillis").asInt());
            assertEquals(times.get(5).longValue(),result.at("/receipts/0/timeMillis").asLong());
            var prior=lookup(w,"rita","failed-page",Map.of("section","interview","before",result.required("nextBefore").asText()))
                    .at("/engineering/historyLookup/lastResult");
            assertEquals(2,prior.required("receipts").size());
            assertEquals(times.get(0).longValue(),prior.at("/receipts/1/timeMillis").asLong());
            assertFalse(prior.has("nextBefore"));
            var other=lookup(w,"investor","unreceived",Map.of("section","interview","id",id));
            assertEquals(0,other.at("/engineering/historyLookup/lastResult/ids").size());
            assertEquals(0,other.at("/engineering/historyLookup/lastResult/receipts").size());
        }
    }

    @Test void previousVersionStillIncludesEveryEndedInterviewAndNoHistoryTool() throws Exception {
        var path=dir.resolve("legacy.journal");
        String original;
        try (var w=ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(path)) {
            contact(w);
            for(int i=1;i<=6;i++) invite(w,i,true);
            var seen=w.autonomyRoleObservation("investor");
            assertEquals(6,seen.at("/interview/invites").size());
            assertFalse(seen.toString().contains(RunManifest.PARTICIPANT_HISTORY_TOOL));
            original=seen.toString();
        }
        try (var w=ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(path)) {
            assertEquals(original,w.autonomyRoleObservation("investor").toString());
        }
    }
}
