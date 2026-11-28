package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersonalDutyWindowsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    @Test void midnightDeliveryWaitsForPersonsFirstEligibleTimeAndPaidInput() throws Exception {
        Path file=dir.resolve("night"); String letter;
        try (var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            long readAt=w.snapshot().timeMillis()+1;
            w.openRoleSession("omar").startReading("directory","directory",readAt,WorldWebScenario.SOCIAL_CONTACT_DOC,1);
            w.advanceTo(readAt);
            w.advanceTo(w.roleView("omar").readings().get(0).dueAt());
            var contact=w.socialView("omar").contacts().stream().filter(c->c.targetPartyId().equals("nina")).findFirst().orElseThrow();
            long send=w.autonomyRoleObservation("omar").at("/engineering/planning/actions/social.send/actionMillis").longValue();
            w.openRoleSession("omar").sendMessage("midnight","midnight-task",w.snapshot().timeMillis()+send,
                    new WorldRoleSession.SocialSendDraft("nina", contact.channel(), "夜间来信", "次日核对来源"));
            w.advanceTo(w.snapshot().timeMillis()+send+WorldWebScenario.SOCIAL_DELIVERY_MILLIS);
            letter=w.socialView("nina").inbox().stream().findFirst().orElseThrow().messageId();
            assertFalse(w.autonomyDutyAvailable("nina"));
        }
        List<JsonNode> calls = new CopyOnWriteArrayList<>();
        try (var routes=SceneRoutes.open(dir)) {
            routes.replace(0, AutonomousLifecycleProbe.probeRoutes(routes.view(),"TEN_COMPANY_MEDIA_WITHDRAWAL_STUDY"));
            try (var h=new WorldWebHost(()-> {
                var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file);
                return new WorldWebHost.Opened(w,w.profile().manifest().runId());
            },routes,(call,timeout)->{
                var i=assertDoesNotThrow(()->JSON.readTree(call.inputText())); calls.add(i);
                var b=JSON.createObjectNode().put("tool","role.wait");
                b.putObject("decision").put("objective",i.at("/engineering/deliberation/objectives/0/id").textValue())
                        .put("basis","下周复查来信").put("reviewAfterMillis",604800000L);
                return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),
                        call.attemptNo(),call.recoveryGeneration(),b.toString(),DecisionModel.Usage.unknownCost(1,2,2),"test"));
            })) {
                h.startAutonomy(Long.parseLong(h.liveCapabilities().position()),routes.view().revision(),32_402_000,3_600_000,Set.of("nina"));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(70);
                while(h.autonomyStatus().running() && System.nanoTime()<until) Thread.sleep(10);
                assertEquals("TARGET_REACHED",h.autonomyStatus().reason());
            }
        }
        assertEquals(1,calls.size()); var first=calls.get(0);
        assertEquals(32_400_000,first.path("time").longValue());
        assertTrue(first.path("social").path("inbox").toString().contains(letter));
        assertEquals(300,first.at("/engineering/personalDuty/minutesPerWeekday").intValue());
        assertTrue(first.at("/engineering/personalDuty/routineOpen").booleanValue());
    }

    @Test void confirmedNightInterviewGetsItsTurnAndReadingCanFinishOutsideDaytime() throws Exception {
        Path file=dir.resolve("interview");
        long start=3_600_000;
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            long readAt=w.snapshot().timeMillis()+1;
            w.openRoleSession("rita").startReading("contact","contact",readAt,"contact:investor-public",1);
            w.advanceTo(readAt);
            w.advanceTo(w.roleView("rita").readings().get(0).dueAt());
            long due=w.snapshot().timeMillis()+w.interviewRun().config().inviteMillis();
            w.openRoleSession("rita").inviteInterview("invite","invite",due,
                    new WorldRoleSession.InterviewInviteDraft("night","investor","采访",start,start+120_001,120_000));
            w.advanceTo(due);
            due+=w.interviewRun().config().respondMillis();
            w.openRoleSession("investor").respondInterview("accept","accept",due,
                    new WorldRoleSession.InterviewRespondDraft(InterviewModel.requestId("rita","night"),true,start));
            w.advanceTo(due);
            assertFalse(w.autonomyDutyAvailable("rita"));
            w.advanceTo(start);
            assertTrue(w.autonomyDutyAvailable("rita"));
            assertFalse(w.autonomyRoleBusy("rita"));
            assertFalse(w.autonomyDutyAvailable("nina"));
            var input=w.autonomyRoleObservation("rita");
            assertFalse(input.at("/engineering/personalDuty/routineOpen").asBoolean());
            assertTrue(input.at("/interview/activeTurn").isObject());
            w.advanceTo(start+120_001);
            assertFalse(w.autonomyDutyAvailable("rita"));

            long beforeClose=900*60_000L-2;
            w.advanceTo(beforeClose);
            assertTrue(w.autonomyDutyAvailable("nina"));
            w.openRoleSession("nina").startReading("late","late",beforeClose+1,"contact:investor-public",1);
            w.advanceTo(beforeClose+1);
            long finish=w.roleView("nina").readings().get(0).dueAt();
            w.advanceTo(finish);
            assertTrue(w.roleView("nina").observations().stream().anyMatch(r->r.ref().docId().equals("contact:investor-public")));
            assertFalse(w.autonomyDutyAvailable("nina"));
        }
    }

    @Test void outOfWindowToolsCarryTheExactMeetingsAndPublishedDocumentVersions() throws Exception {
        var file=dir.resolve("night-scope");
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            w.scheduleEngineeringConvene("first","first",20_002,"eng-a","alice","one",500_000,600_000,700_000);
            w.advanceTo(20_002);
            w.scheduleEngineeringConvene("second","second",30_002,"eng-a","alice","two",500_000,600_000,700_000);
            w.advanceTo(30_002);
            w.scheduleEngineeringAttend("enrol","enrol",36_002,"eng-a","bob","one",false);
            w.advanceTo(36_002);
            w.scheduleEngineeringMaterial("attach","attach",45_002,"eng-a","bob","one","line-one","engineering-opportunity:A1",1);
            w.advanceTo(45_002);
            var packet=AutonomousIssuerDividendTest.call(w,"alice","wrong-read");
            var input=JSON.readTree(packet.inputText());
            var options=input.at("/engineering/personalDuty/dutyActions");
            assertEquals(Set.of("role.reading.start","board.attend","role.wait"), Set.copyOf(packet.allowedTools()),options::toString);
            assertEquals(2, java.util.stream.StreamSupport.stream(options.spliterator(),false)
                    .filter(a->"board.attend".equals(a.path("tool").asText())).count());
            assertNull(AutonomousIssuerDividendTest.reply(w,packet,"alice","role.reading.start",
                    JSON.createObjectNode().put("docId","engineering-opportunity:A1").put("version",2)));
            var wrongDoc=AutonomousIssuerDividendTest.call(w,"alice","wrong-document");
            assertNull(AutonomousIssuerDividendTest.reply(w,wrongDoc,"alice","role.reading.start",
                    JSON.createObjectNode().put("docId","engineering-opportunity:A2").put("version",1)));
            var wrongMeeting=AutonomousIssuerDividendTest.call(w,"alice","wrong-meeting");
            assertNull(AutonomousIssuerDividendTest.reply(w,wrongMeeting,"alice","board.attend",
                    JSON.createObjectNode().put("companyPartyId","eng-a").put("meetingId","other")));
            for (var refused : List.of(packet,wrongDoc,wrongMeeting))
                assertEquals("DUTY_SCOPE",w.snapshot().decisions().orElseThrow().dispatch(refused.attemptId()).businessReject());
            var read=AutonomousIssuerDividendTest.call(w,"alice","correct-read");
            var accepted=AutonomousIssuerDividendTest.reply(w,read,"alice","role.reading.start",
                    JSON.createObjectNode().put("docId","engineering-opportunity:A1").put("version",1));
            assertNotNull(accepted);
            w.advanceTo(accepted.dueMillis());
            w.advanceTo(w.roleView("alice").readings().get(0).dueAt());
            var attend=AutonomousIssuerDividendTest.call(w,"alice","second-meeting");
            var placed=AutonomousIssuerDividendTest.reply(w,attend,"alice","board.attend",
                    JSON.createObjectNode().put("companyPartyId","eng-a").put("meetingId","two"));
            assertNotNull(placed);
            w.advanceTo(placed.dueMillis());
            var finish=w.snapshot().pending().stream().filter(t->t.taskId().equals(placed.taskId()+":finish")).findFirst().orElseThrow();
            w.advanceTo(finish.dueTimeMillis());
            assertEquals("OK", w.snapshot().engineering().orElseThrow().reports().get(finish.taskId()).reason());
        }
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            assertTrue(w.roleView("alice").observations().stream().anyMatch(r->r.ref().docId().equals("engineering-opportunity:A1")));
            var remaining=w.autonomyRoleObservation("alice").at("/engineering/personalDuty/dutyActions");
            var pendingMeetings=java.util.stream.StreamSupport.stream(remaining.spliterator(), false)
                    .filter(a->"board.attend".equals(a.path("tool").asText())).map(a->a.at("/target/meetingId").asText()).toList();
            assertEquals(List.of("one"),pendingMeetings,remaining::toString);
            w.advanceTo(32_400_000);
            assertTrue(w.autonomyRoleObservation("alice").path("tools").toString().contains("platform.post"));
        }
    }

    @Test void distinctInterviewAndBusyRefusalsRetainTheirOriginalReceipts() throws Exception {
        var file=dir.resolve("focus");
        long meeting=32_900_000;
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            w.advanceTo(32_400_000);
            long readAt=w.snapshot().timeMillis()+1;
            w.openRoleSession("rita").startReading("contact","contact",readAt,"contact:investor-public",1);
            w.advanceTo(readAt);
            var finish=w.roleView("rita").readings().get(0).dueAt();
            var menu=w.autonomyRoleObservation("rita").at("/engineering/mediaSubscriptions/offers");
            var target=JSON.createObjectNode().put("target",menu.get(0).path("organization").textValue());
            for(var id:List.of("busy-1","busy-2")){
                var call=AutonomousIssuerDividendTest.call(w,"rita",id);
                assertNull(AutonomousIssuerDividendTest.reply(w,call,"rita","media.subscription.join",target));
                assertEquals("BUSY",w.snapshot().decisions().orElseThrow().dispatch(call.attemptId()).businessReject());
            }
            w.advanceTo(finish);
            var free=AutonomousIssuerDividendTest.call(w,"rita","subscribe");
            var subscription=AutonomousIssuerDividendTest.reply(w,free,"rita","media.subscription.join",target);
            assertNotNull(subscription);
            w.advanceTo(subscription.dueMillis());

            long due=w.snapshot().timeMillis()+w.interviewRun().config().inviteMillis();
            w.openRoleSession("rita").inviteInterview("invite","invite",due,
                    new WorldRoleSession.InterviewInviteDraft("focus","investor","采访",meeting,meeting+120_001,120_000));
            w.advanceTo(due);due+=w.interviewRun().config().respondMillis();
            String request=InterviewModel.requestId("rita","focus");
            w.openRoleSession("investor").respondInterview("accept","accept",due,
                    new WorldRoleSession.InterviewRespondDraft(request,true,meeting));
            w.advanceTo(due);w.advanceTo(meeting);
            var speech=JSON.createObjectNode().put("title","提问").put("body","何时核定本期数量？").put("readDurationMillis",1_000);
            for(var id:List.of("focus-1","focus-2")){
                var call=AutonomousIssuerDividendTest.call(w,"rita",id);
                assertNull(AutonomousIssuerDividendTest.reply(w,call,"rita","interview.speak",speech.put("requestId",id)));
                assertEquals("INTERVIEW_FOCUS",w.snapshot().decisions().orElseThrow().dispatch(call.attemptId()).businessReject());
            }
            var proper=AutonomousIssuerDividendTest.call(w,"rita","own-session");
            var ref=AutonomousIssuerDividendTest.reply(w,proper,"rita","interview.speak",speech.put("requestId",request));
            assertNotNull(ref);
            w.advanceTo(ref.dueMillis());
        }
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            assertEquals(1,w.interviewRun().dialoguesOf("wire").turnsOf("SESSION-"+InterviewModel.requestId("rita","focus")).size());
        }
    }

    @Test void oneTierForEachPersonAndMeetingWindowSurvivesStrictReopen() throws Exception {
        Path file=dir.resolve("board");
        String director;
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            var info=w.autonomyRoleObservation("investor").at("/engineering/personalDuty");
            assertEquals("EMPLOYED",info.path("tier").textValue());
            director="alice";
            assertFalse(w.autonomyDutyAvailable(director));
            w.scheduleEngineeringConvene("cv","cvt",20_002,"eng-a",director,"night",70_002,90_002,190_002);
            w.advanceTo(20_002);
            assertTrue(w.autonomyDutyAvailable(director));
            assertFalse(w.autonomyRoleObservation(director).at("/engineering/personalDuty/duty").asText().isEmpty());
            w.advanceTo(190_003);
            assertFalse(w.autonomyDutyAvailable(director));
        }
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            assertFalse(w.autonomyDutyAvailable(director));
            var present=new HashMap<String,String>();
            for (var person:w.snapshot().information().orElseThrow().information().persons()) {
                present.putIfAbsent(w.autonomyRoleObservation(person).at("/engineering/personalDuty/tier").asText(),person);
            }
            assertEquals(Set.of("EMPLOYED","ACTIVE","PART_TIME"),present.keySet());
            w.advanceTo(570L*60000);
            for(var p:present.values())assertTrue(w.autonomyDutyAvailable(p));
            w.advanceTo(590L*60000);
            assertFalse(w.autonomyDutyAvailable(present.get("PART_TIME")));
            assertTrue(w.autonomyDutyAvailable(present.get("ACTIVE")));
            w.advanceTo(660L*60000);
            assertFalse(w.autonomyDutyAvailable(present.get("ACTIVE")));
        }
        try(var old=ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(dir.resolve("v13"))) {
            assertTrue(old.autonomyDutyAvailable("investor"));
            assertTrue(old.autonomyRoleObservation("investor").at("/engineering/personalDuty").isMissingNode());
        }
    }
}