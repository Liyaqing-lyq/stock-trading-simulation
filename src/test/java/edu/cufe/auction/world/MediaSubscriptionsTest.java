package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class MediaSubscriptionsTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SPOKEN = "本次采访的原始口径需要逐项核对。";
    static final String PRIVATE_ANSWER = "内部休整方案仍待核签，个人目前仅在访谈中说明。";
    @TempDir Path directory;

    static JsonNode menu(WorldRuntime world, String person) {
        return JSON.valueToTree(world.mediaSubscriptionsOf(person));
    }
    static boolean subscribed(WorldRuntime w,String person) {
        for (var offer : menu(w,person).path("offers"))
            if ("wire".equals(offer.path("organization").asText())) return offer.required("subscribed").booleanValue();
        throw new AssertionError("missing wire");
    }
    static void act(WorldRuntime w,String person,String id,String tool,String target) throws Exception {
        var ref = PersonalMarketTest.decide(w,person,id,tool,JSON.createObjectNode().put("target",target));
        assertNotNull(ref,tool);
        assertEquals("PENDING",w.autonomyRoleObservation(person).at("/engineering/deliberation/lastActionResult/execution").asText());
        w.advanceTo(ref.dueMillis());
        assertTrue(w.snapshot().trading().orElseThrow().report(ref.taskId()).accepted(),tool);
        assertEquals("APPLIED",w.autonomyRoleObservation(person).at("/engineering/deliberation/lastActionResult/execution").asText());
    }
    /** 为预约两方建立两个真实话轮，返回 Rita 关会后可以授权的本人话轮。 */
    public static String closedTurn(WorldRuntime w) throws Exception {
        long slot=500_000;
        String request=OnsiteVisitTest.book(w,slot);
        w.advanceTo(slot);
        var speech=PersonalMarketTest.decide(w,"rita","test-interview-turn","interview.speak",
                JSON.createObjectNode().put("requestId",request).put("title","计量口径").put("body",SPOKEN).put("readDurationMillis",10_000));
        assertNotNull(speech);
        w.advanceTo(speech.dueMillis());
        assertEquals("APPLIED",w.autonomyRoleObservation("rita").at("/engineering/deliberation/lastActionResult/execution").asText());
        assertTrue(menu(w,"rita").path("releasableTurns").isEmpty());
        String question=InterviewDialogue.turnDocId(w.interviewView("investor").sessions().get(0).sessionId(),1);
        long open=w.snapshot().timeMillis()+1;
        w.openRoleSession("investor").startReading("participant-reads","participant-reads",open,question,1);
        w.advanceTo(open);
        w.advanceTo(w.roleView("investor").readings().get(0).dueAt());
        var answer=PersonalMarketTest.decide(w,"investor","private-reply","interview.speak",
                JSON.createObjectNode().put("requestId",request).put("title","内部安排").put("body",PRIVATE_ANSWER).put("readDurationMillis",1000));
        assertNotNull(answer);
        w.advanceTo(answer.dueMillis());
        assertEquals("APPLIED",w.autonomyRoleObservation("investor").at("/engineering/deliberation/lastActionResult/execution").asText());
        w.advanceTo(slot+120_000);
        return menu(w,"rita").required("releasableTurns").required(0).required("turnId").asText();
    }

    @Test void exactSpeakerConsentAfterCloseDeliversOnlyTheirTurnToSubscribersOfThatMoment() throws Exception {
        Path file=directory.resolve("interview.journal");
        String turn, delivered;
        JsonNode finalInput;
        try(var w=ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            var funds=w.snapshot().accounts().get("HOUSE-NINA").cash();
            act(w,"nina","reader-joins",MediaSubscriptions.JOIN,"wire");
            act(w,"sam","second-reader",MediaSubscriptions.JOIN,"wire");
            assertTrue(subscribed(w,"nina"));
            turn=closedTurn(w);
            var partner=menu(w,"investor").required("releasableTurns");
            assertEquals(1,partner.size()); assertNotEquals(turn,partner.get(0).required("turnId").asText());
            assertThrows(IllegalArgumentException.class,()->w.scheduleMediaSubscription("investor","RELEASE_TURN",turn,"not-my-turn"));
            assertTrue(menu(w,"nina").path("deliveries").isEmpty());
            assertFalse(w.autonomyRoleObservation("nina").toString().contains(SPOKEN));
            act(w,"rita","speaker-consents",MediaSubscriptions.RELEASE,turn);
            assertTrue(menu(w,"rita").path("releasableTurns").isEmpty());
            assertEquals(partner,menu(w,"investor").required("releasableTurns"));
            assertThrows(IllegalArgumentException.class,()->w.scheduleMediaSubscription("rita","RELEASE_TURN",turn,"repeat-release"));
            var delivery=menu(w,"nina").required("deliveries");
            assertEquals(1,delivery.size()); assertEquals("INTERVIEW_SUPPLEMENT",delivery.get(0).required("kind").asText());
            assertEquals("rita",delivery.get(0).required("speakerPartyId").asText()); assertFalse(delivery.get(0).has("otherParticipant"));
            delivered=delivery.get(0).required("docId").asText(); assertNotEquals(turn,delivered);
            assertFalse(w.autonomyRoleObservation("nina").toString().contains(SPOKEN));
            assertFalse(w.roleView("dave").catalog().stream().anyMatch(r->delivered.equals(r.docId())));
            act(w,"dave","joins-too-late",MediaSubscriptions.JOIN,"wire");
            assertTrue(menu(w,"dave").path("deliveries").isEmpty());
            act(w,"nina","reader-leaves",MediaSubscriptions.LEAVE,"wire");
            assertFalse(subscribed(w,"nina"));
            assertEquals(delivery,menu(w,"nina").required("deliveries"));
            long start=w.snapshot().timeMillis()+1;
            w.openRoleSession("nina").startReading("read-supplement","read-supplement",start,delivered,1L);
            w.advanceTo(start);
            long due=w.roleView("nina").readings().get(0).dueAt();
            assertTrue(due>=start+10_000);
            w.advanceTo(due-1);
            assertFalse(w.autonomyRoleObservation("nina").toString().contains(SPOKEN));
            w.advanceTo(due);
            assertTrue(w.roleView("nina").observations().stream().anyMatch(o->delivered.equals(o.ref().docId()) && SPOKEN.equals(o.body())));
            assertFalse(w.autonomyRoleObservation("nina").toString().contains(PRIVATE_ANSWER));
            assertFalse(w.autonomyRoleObservation("dave").toString().contains(SPOKEN));
            String samsCopy=menu(w,"sam").at("/deliveries/0/docId").asText();
            assertNotEquals(delivered,samsCopy);
            long samStart=w.snapshot().timeMillis()+1;
            w.openRoleSession("sam").startReading("sam-opens","sam-opens",samStart,samsCopy,1L);
            w.advanceTo(samStart);
            w.advanceTo(w.roleView("sam").readings().get(0).dueAt());
            assertTrue(w.roleView("sam").observations().stream().anyMatch(o->SPOKEN.equals(o.body())));
            long now=w.snapshot().timeMillis();
            long dueInvite=now+w.interviewRun().config().inviteMillis();
            w.openRoleSession("sam").inviteInterview("try-author","try-author",dueInvite,new WorldRoleSession.InterviewInviteDraft(
                    "invite-source","rita","核实署名",now+500_000,now+900_000,60_000));
            w.advanceTo(dueInvite);
            assertTrue(w.interviewRun().coordinations().values().stream().anyMatch(r->"sam".equals(r.personPartyId())
                    && !r.accepted() && InterviewModel.REASON_UNREACHABLE.equals(r.reason())));
            assertEquals(funds,w.snapshot().accounts().get("HOUSE-NINA").cash());
            finalInput=w.autonomyRoleObservation("nina");
        }
        byte[] bytes=Files.readAllBytes(file);
        try(var w=ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            assertEquals(finalInput,w.autonomyRoleObservation("nina"));
            assertTrue(menu(w,"rita").path("releasableTurns").isEmpty());
        }
        assertArrayEquals(bytes,Files.readAllBytes(file));
    }

    @Test void retryKeepsOriginalPurposeAndElapsedWorkAndOldArchiveKeepsItsInput() throws Exception {
        Path file=directory.resolve("work.journal");
        long start;
        try(var w=ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            start=w.snapshot().timeMillis();
            assertThrows(IllegalStateException.class,()->w.scheduleMediaSubscription("nina","JOIN","wire","new",start+1));
            assertEquals("SCHEDULED:retry-task",w.scheduleMediaSubscription("nina","JOIN","wire","retry",start));
            assertFalse(subscribed(w,"nina"));
            assertThrows(IllegalStateException.class,()->w.scheduleMediaSubscription("nina","LEAVE","wire","other",start));
            w.advanceTo(start+MediaSubscriptions.DURATION-1);
            assertFalse(subscribed(w,"nina"));
        }
        try(var w=ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            long p=w.snapshot().position();
            assertEquals("SCHEDULED:retry-task",w.scheduleMediaSubscription("nina","JOIN","wire","retry",start));
            assertEquals(p,w.snapshot().position());
            assertThrows(IllegalArgumentException.class,()->w.scheduleMediaSubscription("nina","JOIN","investigative","retry",start));
            w.advanceTo(start+MediaSubscriptions.DURATION);
            assertTrue(subscribed(w,"nina"));
            w.scheduleMediaSubscription("nina","JOIN","wire","again");
            w.advanceTo(start+2*MediaSubscriptions.DURATION);
            assertTrue(w.snapshot().trading().orElseThrow().reports().values().stream().anyMatch(r->
                    "again".equals(r.clientCommandId())&&!r.accepted()&&"ALREADY_SUBSCRIBED".equals(r.reason())));
        }
        Path old=directory.resolve("v10.journal"); JsonNode original;
        try(var w=ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(old)) {
            original=w.autonomyRoleObservation("nina");
            assertFalse(original.path("engineering").has("mediaSubscriptions")); assertFalse(original.toString().contains(MediaSubscriptions.JOIN));
            assertTrue(w.mediaSubscriptionsOf("nina").isEmpty());
            assertThrows(IllegalArgumentException.class,()->w.scheduleMediaSubscription("nina","JOIN","wire","old"));
        }
        byte[] bytes=Files.readAllBytes(old);
        try(var w=ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(old)) {assertEquals(original,w.autonomyRoleObservation("nina"));}
        assertArrayEquals(bytes,Files.readAllBytes(old));
    }

    @Test void paidAttentionCannotBeSpentTwiceAndAdjacentWorkCanStartAtTheDeadline() throws Exception {
        var file=directory.resolve("focus.journal");
        String publicDoc="engineering-opportunity:A1";
        try(var w=ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            long now=w.snapshot().timeMillis(), done=now+MediaSubscriptions.DURATION;
            var nina=w.openRoleSession("nina");
            nina.startReading("absent-version","absent-version",now+1,publicDoc,999);
            w.scheduleMediaSubscription("nina","JOIN","wire","join");
            assertTrue(w.autonomyRoleBusy("nina"));
            assertThrows(IllegalArgumentException.class,()->nina.startReading("overlap","overlap",done-1,publicDoc,1));
            assertThrows(IllegalArgumentException.class,()->w.scheduleStartReading("admin","admin",done-1,"nina",publicDoc,1));
            w.openRoleSession("dave").startReading("other-reader","other-reader",done-1,publicDoc,1);
            nina.startReading("after","after",done,publicDoc,1);
            w.advanceTo(done);
            assertTrue(subscribed(w,"nina"));
            assertEquals(1,w.roleView("nina").readings().size());
            assertEquals(1,w.roleView("dave").readings().size());
            assertThrows(IllegalStateException.class,()->w.scheduleMediaSubscription("nina","LEAVE","wire","early"));
            w.advanceTo(w.roleView("nina").readings().get(0).dueAt());
            w.scheduleMediaSubscription("nina","LEAVE","wire","free");
            w.advanceTo(w.snapshot().timeMillis()+MediaSubscriptions.DURATION);
            assertFalse(subscribed(w,"nina"));
        }
        try(var w=ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            assertFalse(subscribed(w,"nina")); assertTrue(w.roleView("nina").observations().stream().anyMatch(r->publicDoc.equals(r.ref().docId())));
        }
    }
}
