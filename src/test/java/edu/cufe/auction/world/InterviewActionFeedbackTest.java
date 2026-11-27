package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InterviewActionFeedbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    @Test void eachResponseReportsItsOwnBudgetRefusalWhileBothInvitationsRemainProposed() throws Exception {
        Path file = dir.resolve("v7.journal");
        JsonNode beforeClose;
        try (var world = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            readContact(world, "sam");
            readContact(world, "digital-manager");
            var fromSam = invite(world, "sam", "first", 1_800_000, true);
            var fromDesk = invite(world, "digital-manager", "second", 1_800_000, true);
            var response = JSON.createObjectNode().put("requestId", fromSam).put("accept", true).put("startMillis", 36_000_000);
            var first = act(world, "investor", "respond-1", "interview.respond", response, true);
            var second = act(world, "investor", "respond-2", "interview.respond", response, true);
            for (var row : List.of(first, second)) {
                assertEquals("REJECTED", row.required("execution").textValue());
                assertFalse(row.required("accepted").booleanValue());
                assertEquals("BUDGET", row.required("businessReject").textValue());
                assertEquals(fromSam, row.required("requestId").textValue());
                assertEquals("investigative", row.required("organizationPartyId").textValue());
                assertTrue(row.required("reasonDetail").textValue().contains("发起采访的媒体机构"));
                assertFalse(row.has("resultPosition"));
            }
            assertTrue(first.required("resultAt").longValue() < second.required("resultAt").longValue());
            var investor = world.autonomyRoleObservation("investor");
            var visible = investor.at("/interview/invites");
            assertEquals(2, visible.size());
            assertTrue(visible.findValuesAsText("requestId").containsAll(List.of(fromSam, fromDesk)));
            assertEquals(List.of("PROPOSED", "PROPOSED"), visible.findValuesAsText("status"));
            assertTrue(investor.at("/interview/sessions").isEmpty());
            assertTrue(world.autonomyRoleObservation("nina").at("/interview/invites").isEmpty());
            assertFalse(world.autonomyRoleObservation("digital-manager").at("/engineering/deliberation")
                    .toString().contains("respond-1"));
            beforeClose = investor.at("/engineering/deliberation/recentDecisions");
            assertEquals(second.path("resultAt"), beforeClose.get(0).path("resultAt"));
            assertEquals(first.path("resultAt"), beforeClose.get(1).path("resultAt"));
        }
        try (var reopened = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            assertEquals(beforeClose,reopened.autonomyRoleObservation("investor").at("/engineering/deliberation/recentDecisions"));
        }
    }

    @Test void oldArchiveReopensWithItsOriginalUnknownResult() throws Exception {
        Path file = dir.resolve("v6.journal");
        String before;
        try (var world = ComposedIndustryScenario.openPersonalTenCompanyStudy(file)) {
            readContact(world, "sam");
            var id = invite(world, "sam", "old", 1_800_000, false);
            var args = JSON.createObjectNode().put("requestId",id).put("accept",true).put("startMillis",36_000_000);
            var row = act(world,"investor","old-response","interview.respond",args,false);
            assertEquals("UNKNOWN",row.path("execution").textValue());
            assertFalse(row.has("requestId"));
            assertFalse(row.has("businessReject"));
            before = world.autonomyRoleObservation("investor").toString();
        }
        try (var reopened=ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            assertEquals(RunManifest.PERSONAL_TEN_COMPANY_RUN_ID, reopened.profile().manifest().runId());
            assertEquals(before,reopened.autonomyRoleObservation("investor").toString());
        }
    }

    @Test void confirmedSiteConsentCancellationSpeakingAndEndKeepTheirOwnRequestAndCommands() throws Exception {
        try (var world=ComposedIndustryScenario.openInterviewTenCompanyStudy(dir.resolve("session.journal"))) {
            long slot=500_000;
            var onsite=OnsiteVisitTest.book(world,slot);
            var args=JSON.createObjectNode().put("requestId",onsite);
            var consent=act(world,"investor","site","interview.siteVisit",args.deepCopy().put("action","HOST_CONSENT"),true);
            applied(consent,onsite,PendingTask.ONSITE_ACTION_V1);
            var canceled=act(world,"rita","cancel-site","interview.cancel",args,true);
            applied(canceled,onsite,PendingTask.INTERVIEW_CANCEL_V1);

            var separate=invite(world,"rita","short",100_000,true);
            var request=JSON.createObjectNode().put("requestId",separate);
            var accepted=act(world,"investor","accept-short","interview.respond",
                    request.deepCopy().put("accept",true).put("startMillis",slot),true);
            applied(accepted,separate,PendingTask.INTERVIEW_RESPOND_V1);
            world.advanceTo(slot);
            var spoken=act(world,"rita","first-turn","interview.speak",
                    request.deepCopy().put("title","访问").put("body","哪些记录可核对？").put("readDurationMillis",1000),true);
            applied(spoken,separate,PendingTask.INTERVIEW_SPEAK_V1);
            var ended=act(world,"investor","end-short","interview.end",request,true);
            applied(ended,separate,PendingTask.INTERVIEW_END_V1);
        }
    }

    private static void applied(JsonNode result,String requestId,String kind) {
        assertEquals("APPLIED",result.required("execution").textValue(),result.toString());
        assertEquals(requestId,result.required("requestId").textValue());
        assertEquals(kind,result.required("resultKind").textValue());
        assertTrue(result.required("accepted").booleanValue());
    }

    private static void readContact(WorldRuntime world,String person) throws Exception {
        long at=world.snapshot().timeMillis()+1;
        String doc=WorldWebScenario.INTERVIEW_CONTACT_DOC;
        world.openRoleSession(person).startReading("read-"+person,"read-"+person,at,doc,1);
        world.advanceTo(at);
        var reading=world.roleView(person).readings().stream().filter(r->doc.equals(r.ref().docId())).findFirst().orElseThrow();
        world.advanceTo(reading.dueAt());
    }

    private static String invite(WorldRuntime world,String actor,String id,long duration,boolean hasReceipts) throws Exception {
        var args=JSON.createObjectNode().put("requestClientId",id).put("toPartyId","investor").put("topic","核对公开采访渠道")
                .put("windowStartMillis",300_000).put("windowEndMillis",86_400_000).put("durationMillis",duration);
        var row=act(world,actor,"invite-"+id,"interview.invite",args,hasReceipts);
        String requestId=InterviewModel.requestId(actor,id);
        if (hasReceipts) applied(row,requestId,PendingTask.INTERVIEW_INVITE_V1);
        return requestId;
    }

    private static JsonNode act(WorldRuntime world,String person,String id,String tool,ObjectNode args,boolean checkPending) throws Exception {
        var ref=PersonalMarketTest.decide(world,person,id,tool,args);
        assertNotNull(ref,tool);
        if (checkPending) assertEquals("PENDING",world.autonomyRoleObservation(person)
                .at("/engineering/deliberation/lastActionResult/execution").textValue());
        world.advanceTo(ref.dueMillis());
        return world.autonomyRoleObservation(person).at("/engineering/deliberation/lastActionResult");
    }
}
