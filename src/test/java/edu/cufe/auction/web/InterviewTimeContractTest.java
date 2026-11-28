package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.InterviewDialogue;
import edu.cufe.auction.world.InterviewModel;
import edu.cufe.auction.world.OnsiteVisit;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/** 第十七版本人访谈时钟；读稿、发言与闭场均由真实待办完成。 */
class InterviewTimeContractTest {
    private static final long START = 32_820_000;
    private static final String REQUEST = InterviewModel.requestId("rita","timed-interview");
    private static final String SESSION = "SESSION-" + REQUEST;
    private static final String BODY = "访".repeat(477) + "ABC123!";
    @TempDir Path dir;

    private static void arrange(WorldRuntime w, long duration) throws Exception {
        long readAt = w.snapshot().timeMillis() + 1;
        w.openRoleSession("rita").startReading("contact", "contact", readAt, "contact:investor-public", 1);
        w.advanceTo(readAt);
        w.advanceTo(w.roleView("rita").readings().get(0).dueAt());
        long inviteAt = w.snapshot().timeMillis() + w.interviewRun().config().inviteMillis();
        w.openRoleSession("rita").inviteInterview("invite","invite",inviteAt,
                new WorldRoleSession.InterviewInviteDraft("timed-interview","investor","出处核实",
                        START, START + duration + 1, duration));
        w.advanceTo(inviteAt);
        long acceptAt = inviteAt + w.interviewRun().config().respondMillis();
        w.openRoleSession("investor").respondInterview("accept","accept",acceptAt,
                new WorldRoleSession.InterviewRespondDraft(REQUEST,true,START));
    }

    private static void openSession(WorldRuntime w, long duration) throws Exception {
        arrange(w,duration);
        w.advanceTo(START);
        assertEquals("ACTIVE", w.interviewView("rita").invites().get(0).status());
    }

    private static void publishQuestion(WorldRuntime w) throws Exception {
        w.advanceTo(START + 1_000);
        w.openRoleSession("rita").speakInterview("question","question",START + 2_000,
                new WorldRoleSession.DialogueSpeakDraft(REQUEST,"核对",BODY,6_000));
        w.advanceTo(START + 2_000);
        assertTrue(w.interviewRun().coordinations().get(WorldRuntime.roleTaskId("rita","question")).accepted());
    }

    private static JsonNode tool(JsonNode input, String name) {
        return StreamSupport.stream(input.required("tools").spliterator(),false)
                .filter(row -> name.equals(row.path("name").asText())).findFirst().orElseThrow();
    }

    private static void assertWindow(JsonNode row, long scheduled, long silence, long effective) {
        assertEquals(scheduled,row.required("scheduledEndMillis").longValue());
        assertEquals(silence,row.required("replyDeadlineMillis").longValue());
        assertEquals(effective,row.required("deadlineMillis").longValue());
    }

    @Test void timedQuestionCanFinishReadingAfterMeetingButCannotBecomeAnInSessionAnswer() throws Exception {
        Path file = dir.resolve("timed.journal");
        long end = START + 60_000;
        long readComplete = START + 100_600;
        String doc;
        try (var w=ComposedIndustryScenario.openInterviewTimeTenCompanyStudy(file)) {
            openSession(w, 60_000);
            JsonNode speaker = w.autonomyRoleObservation("rita");
            assertWindow(speaker.at("/interview/activeTurn"),end,end,end);
            assertWindow(speaker.at("/interview/sessions/0"),end,end,end);
            String terms = tool(speaker,"interview.speak").required("readingTime").asText();
            for (String word : new String[]{"下限","汉字","200","读完","等待"}) assertTrue(terms.contains(word));
            assertTrue(speaker.at("/interview/deadlineTerms").asText().contains("静默"));
            publishQuestion(w);
            JsonNode reader = w.autonomyRoleObservation("investor");
            assertWindow(reader.at("/interview/activeTurn"),end,end+2_000,end);
            assertWindow(reader.at("/interview/sessions/0"),end,end+2_000,end);
            doc=reader.at("/interview/activeTurn/docId").textValue();
            assertFalse(doc.isBlank());
            var catalog=StreamSupport.stream(reader.at("/profile/catalog").spliterator(),false)
                    .filter(row->doc.equals(row.path("docId").asText())).findFirst().orElseThrow();
            assertEquals(95_600, catalog.required("readDurationMillis").longValue());
            assertFalse(reader.toString().contains(BODY));
            assertFalse(w.autonomyRoleObservation("nina").toString().contains(SESSION));
            assertTrue(w.autonomyRoleObservation("nina").at("/interview/deadlineTerms").isMissingNode());
            w.openRoleSession("investor").speakInterview("unread","unread",START+3_000,
                    new WorldRoleSession.DialogueSpeakDraft(REQUEST,"答复","尚未读",1_000));
            w.advanceTo(START+3_000);
            assertEquals(InterviewDialogue.REASON_NOT_READ,w.interviewRun().coordinations()
                    .get(WorldRuntime.roleTaskId("investor","unread")).reason());
            w.openRoleSession("investor").startReading("read","read",START+5_000,doc,1);
            w.advanceTo(START+5_000);
            assertEquals(readComplete,w.roleView("investor").readings().get(0).dueAt());
            w.advanceTo(end);
            var closed=w.autonomyRoleObservation("investor");
            assertWindow(closed.at("/interview/sessions/0"),end,-1,-1);
            assertTrue(closed.at("/interview/activeTurn").isMissingNode());
            assertFalse(closed.at("/interview/sessions/0/turns/0/readByMe").booleanValue());
            assertEquals(end,closed.at("/interview/sessions/0/endedAtMillis").longValue());
            w.advanceTo(readComplete-1);
            assertFalse(w.autonomyRoleObservation("investor").toString().contains(BODY));
            w.advanceTo(readComplete);
            assertThrows(IllegalArgumentException.class,()->w.openRoleSession("investor").speakInterview(
                    "late","late",readComplete+1_000,new WorldRoleSession.DialogueSpeakDraft(REQUEST,"答复","晚到",1_000)));
        }
        try (var w=ComposedIndustryScenario.openInterviewTimeTenCompanyStudy(file)) {
            var view=w.autonomyRoleObservation("investor");
            assertTrue(view.at("/interview/sessions/0/turns/0/readByMe").booleanValue());
            assertTrue(view.toString().contains(BODY));
            assertEquals(1,w.interviewRun().dialoguesOf("wire").turnsOf(SESSION).size());
            assertEquals(InterviewDialogue.CloseReason.SCHEDULE_END,
                    w.interviewRun().dialoguesOf("wire").closeOf(SESSION).orElseThrow().reason());
        }
    }

    @Test void silenceCanExpireBeforeTheBookedEnd() throws Exception {
        try (var w=ComposedIndustryScenario.openInterviewTimeTenCompanyStudy(dir.resolve("silence.journal"))) {
            openSession(w,120_000);
            publishQuestion(w);
            var view=w.autonomyRoleObservation("investor");
            assertWindow(view.at("/interview/activeTurn"),START+120_000,START+62_000,START+62_000);
            assertWindow(view.at("/interview/sessions/0"),START+120_000,START+62_000,START+62_000);
            w.advanceTo(START+62_000);
            assertEquals(InterviewDialogue.CloseReason.DEADLINE_NO_REPLY,
                    w.interviewRun().dialoguesOf("wire").closeOf(SESSION).orElseThrow().reason());
        }
    }

    @Test void bookedTravelShowsOnlyCurrentActionsAndIssuesOneTicketAtDeparture() throws Exception {
        Path file=dir.resolve("travel.journal");
        try (var w=ComposedIndustryScenario.openInterviewTimeTenCompanyStudy(file)) {
            arrange(w,60_000);
            w.advanceTo(START-30_000);
            w.openRoleSession("investor").siteVisit("consent","consent",START-29_000,
                    new OnsiteVisit.Draft(REQUEST,"HOST_CONSENT"));
            w.advanceTo(START-29_000);
            w.openRoleSession("rita").siteVisit("book","book",START-28_000,
                    new OnsiteVisit.Draft(REQUEST,"DEPART"));
            w.advanceTo(START-28_000);
            var call=AutonomousIssuerDividendTest.call(w,"rita","repeat-departure");
            var input=new ObjectMapper().readTree(call.inputText()).required("interview");
            var row=input.at("/onsiteVisits/appointments/0");
            assertEquals("BOOKED",row.required("stage").asText());
            assertEquals(-1,row.required("departMillis").longValue());
            assertEquals("0.00",row.required("farePaid").asText());
            assertEquals("[\"RETURN\"]",row.required("actions").toString());
            String terms=input.required("onsiteTerms").asText();
            assertTrue(terms.contains("BOOKED") && terms.contains("actions") && terms.contains("无需再次申请 DEPART"));
            var args=new ObjectMapper().createObjectNode().put("requestId",REQUEST).put("action","DEPART");
            assertNull(AutonomousIssuerDividendTest.reply(w,call,"rita","interview.siteVisit",args));
            var feedback=w.autonomyRoleObservation("rita").at("/engineering/deliberation/lastActionResult");
            assertTrue(feedback.required("protocolReason").asText().startsWith("TOOL_ARGS_INVALID"));
            w.advanceTo(START-10_001);
            assertEquals("0.00",w.siteVisitsFor("rita").appointments().get(0).farePaid());
            w.advanceTo(START-10_000);
            var departed=w.siteVisitsFor("rita").appointments().get(0);
            assertEquals("OUTBOUND",departed.stage());
            assertEquals(START-10_000,departed.departMillis());
            assertEquals("12.00",departed.farePaid());
        }
        try (var w=ComposedIndustryScenario.openInterviewTimeTenCompanyStudy(file)) {
            assertEquals("12.00",w.snapshot().accounts().get("VISIT-CARRIER").cash().toPlainString());
            w.advanceTo(START);
            assertEquals("ONSITE",w.siteVisitsFor("rita").appointments().get(0).stage());
            assertEquals("12.00",w.snapshot().accounts().get("VISIT-CARRIER").cash().toPlainString());
        }
    }

    @Test void previousOpeningPreservesItsFrozenSpeakerAndReaderInputs() throws Exception {
        // 在正式 v16 (5bbff7b) 的独立 JVM 上用同一组业务指令取得的两份完整 UTF-8 输入。
        String speakerHash="9ec0e91d32026901b8cf5974dfb19d20e5ea12e0fefd02cc07e6a66374a765e1";
        String readerHash="9002bb0e6a4bc20650f97a5e08a480e227eb92024ecf0bf19fc180517346142b";
        Path file=dir.resolve("previous.journal");
        try (var w=ComposedIndustryScenario.openPersonalBeliefTenCompanyStudy(file)) {
            openSession(w,60_000);
            JsonNode speaker=w.autonomyRoleObservation("rita");
            assertEquals(speakerHash,inputHash(speaker));
            assertFalse(tool(speaker,"interview.speak").has("readingTime"));
            publishQuestion(w);
            var reader=w.autonomyRoleObservation("investor");
            assertEquals(readerHash,inputHash(reader));
            assertEquals(START+62_000,reader.at("/interview/sessions/0/replyDeadlineMillis").longValue());
            assertEquals(START+60_000,reader.at("/interview/activeTurn/deadlineMillis").longValue());
        }
        try (var w=ComposedIndustryScenario.openPersonalBeliefTenCompanyStudy(file)) {
            assertEquals(readerHash,inputHash(w.autonomyRoleObservation("investor")));
        }
    }

    private static String inputHash(JsonNode input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
