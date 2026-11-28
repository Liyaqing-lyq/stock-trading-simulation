package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.InterviewModel;
import edu.cufe.auction.world.OnsiteVisit;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 对照本人可见菜单、固定思考时间和分配到正式任务的时点。 */
class OnsiteDecisionClockTest {
    @TempDir Path dir;
    private static final long START = 32_640_200L;
    private static final String REQUEST = InterviewModel.requestId("rita", "onsite-clock");
    private static final ObjectMapper JSON = new ObjectMapper();

    static void arrange(WorldRuntime w) throws Exception {
        long read = w.snapshot().timeMillis() + 1;
        w.openRoleSession("rita").startReading("read", "read", read, "contact:investor-public", 1);
        w.advanceTo(read);
        w.advanceTo(w.roleView("rita").readings().get(0).dueAt());
        long invited = w.snapshot().timeMillis() + w.interviewRun().config().inviteMillis();
        w.openRoleSession("rita").inviteInterview("invite", "invite", invited, new WorldRoleSession.InterviewInviteDraft(
                "onsite-clock", "investor", "现场时间核对", START, START + 60_001, 60_000));
        w.advanceTo(invited);
        var inviteReceipt=w.interviewRun().coordinations().get(WorldRuntime.roleTaskId("rita","invite"));
        assertTrue(inviteReceipt.accepted(),inviteReceipt.toString());
        long accepted = invited + w.interviewRun().config().respondMillis();
        w.openRoleSession("investor").respondInterview("yes", "yes", accepted, new WorldRoleSession.InterviewRespondDraft(REQUEST, true, START));
        w.advanceTo(accepted);
        assertEquals("ACCEPTED", w.interviewView("investor").invites().stream()
                .filter(i -> i.requestId().equals(REQUEST)).findFirst().orElseThrow().status(),
                w.interviewRun().coordinations().toString());
    }

    private static JsonNode actions(JsonNode input) {
        return input.at("/interview/onsiteVisits/appointments/0/actions");
    }

    @Test void modelMustFinishBeforeOpeningButManualConsentHasItsOwnClock() throws Exception {
        for (long remaining : new long[]{2_001,2_000,1_200,1_000}) {
            Path file=dir.resolve("boundary-"+remaining+".journal");
            try (var w=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(file)) {
                arrange(w);
                w.advanceTo(START-remaining);
                var preview=w.autonomyRoleObservation("investor");
                var call=AutonomousIssuerDividendTest.call(w,"investor","boundary");
                assertEquals(actions(preview),actions(JSON.readTree(call.inputText())));
                assertEquals(remaining>2_000,actions(preview).toString().contains("HOST_CONSENT"));
                assertEquals(remaining>2_000,call.allowedTools().contains(OnsiteVisit.TOOL));
                assertEquals(remaining>1_000,w.siteVisitsFor("investor").appointments().get(0).actions().contains("HOST_CONSENT"));
                if (remaining>2_000) {
                    var ref=AutonomousIssuerDividendTest.reply(w,call,"investor",OnsiteVisit.TOOL,
                            JSON.createObjectNode().put("requestId",REQUEST).put("action","HOST_CONSENT"));
                    assertNotNull(ref);
                    assertEquals(START-1,ref.dueMillis());
                    w.advanceTo(ref.dueMillis());
                    assertEquals("SITE_CONSENTED",w.interviewRun().coordinations().get(ref.taskId()).reason());
                } else {
                    assertNull(AutonomousIssuerDividendTest.reply(w,call,"investor","WAIT",JSON.createObjectNode()));
                    if (remaining==1_200) {
                        w.openRoleSession("investor").siteVisit("immediate","immediate",START-200,
                                new OnsiteVisit.Draft(REQUEST,"HOST_CONSENT"));
                        w.advanceTo(START-200);
                        assertTrue(w.interviewRun().coordinations().get(WorldRuntime.roleTaskId("investor","immediate")).accepted());
                    }
                }
            }
            try (var restored=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(file)) {
                assertTrue(restored.snapshot().timeMillis()>=START-remaining);
            }
        }
    }

    @Test void changedAppointmentAfterCutoffSettlesAsBusinessRefusal() throws Exception {
        Path file=dir.resolve("cancel-after-cutoff.journal");
        try (var w=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(file)) {
            arrange(w);
            w.advanceTo(START-30_000);
            long cancelled=w.snapshot().timeMillis()+w.interviewRun().config().cancelMillis();
            w.openRoleSession("rita").cancelInterview("withdraw","withdraw",cancelled,
                    new WorldRoleSession.InterviewRequestDraft(REQUEST));
            w.advanceTo(cancelled-300);
            var call=AutonomousIssuerDividendTest.call(w,"investor","respond-to-observed");
            assertEquals("[\"HOST_CONSENT\"]",actions(JSON.readTree(call.inputText())).toString());
            w.advanceTo(cancelled);
            assertTrue(w.interviewRun().coordinations().get(WorldRuntime.roleTaskId("rita","withdraw")).accepted());
            var ref=AutonomousIssuerDividendTest.reply(w,call,"investor",OnsiteVisit.TOOL,
                    JSON.createObjectNode().put("requestId",REQUEST).put("action","HOST_CONSENT"));
            assertNotNull(ref);
            w.advanceTo(ref.dueMillis());
            var refusal=w.interviewRun().coordinations().get(ref.taskId());
            assertFalse(refusal.accepted());
            assertEquals("SITE_NOT_AUTHORIZED",refusal.reason());
            var feedback=w.autonomyRoleObservation("investor").at("/engineering/deliberation/lastActionResult");
            assertEquals("REJECTED",feedback.required("execution").asText());
            assertEquals("SITE_NOT_AUTHORIZED",feedback.required("businessReject").asText());
            assertEquals(REQUEST,feedback.required("requestId").asText());
            assertTrue(w.interviewRun().visits().isEmpty());
            assertEquals(0, w.snapshot().accounts().get("VISIT-CARRIER").cash().signum());
        }
        try (var w=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(file)) {
            assertTrue(w.interviewRun().visits().isEmpty());
        }
    }

    @Test void visibleAppointmentCannotAuthorizeAnotherRequestOrParticipantsAction() throws Exception {
        for(boolean wrongId:new boolean[]{true,false}) {
            try(var w=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(dir.resolve("unknown-"+wrongId+".journal"))) {
                arrange(w);
                w.advanceTo(START-30_000);
                var call=AutonomousIssuerDividendTest.call(w,"investor","invalid");
                assertNull(AutonomousIssuerDividendTest.reply(w,call,"investor",OnsiteVisit.TOOL,
                        JSON.createObjectNode().put("requestId",wrongId ? "another" : REQUEST).put("action",wrongId ? "HOST_CONSENT" : "DEPART")));
                assertEquals("TECHNICAL_FAILED",w.autonomyRoleObservation("investor")
                        .at("/engineering/deliberation/lastActionResult/outcome").asText());
                assertTrue(w.interviewRun().visits().isEmpty());
            }
        }
    }

    @Test void oldOpeningRetainsTheActual109Mismatch() throws Exception {
        try (var w=ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(dir.resolve("old.journal"))) {
            arrange(w);
            w.advanceTo(START - 1_200);
            var call=AutonomousIssuerDividendTest.call(w,"investor","before-start");
            assertTrue(call.allowedTools().contains(OnsiteVisit.TOOL));
            assertEquals("[\"HOST_CONSENT\"]",actions(JSON.readTree(call.inputText())).toString());
            assertNull(AutonomousIssuerDividendTest.reply(w,call,"investor",OnsiteVisit.TOOL,
                    JSON.createObjectNode().put("requestId",REQUEST).put("action","HOST_CONSENT")));
            var result=w.autonomyRoleObservation("investor").at("/engineering/deliberation/lastActionResult");
            assertEquals("TECHNICAL_FAILED",result.path("outcome").asText());
            assertEquals("TOOL_ARGS_INVALID:本人与当前预约尚无该现场接待权限",result.path("protocolReason").asText());
        }
    }
}
