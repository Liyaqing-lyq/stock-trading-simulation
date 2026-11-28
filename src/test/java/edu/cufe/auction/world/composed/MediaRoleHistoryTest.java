package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.EditorialReviewFeedbackTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaRoleHistoryTest {
    @TempDir Path dir;
    static final ObjectMapper JSON = new ObjectMapper();

    private static List<String> values(JsonNode rows, String field) {
        var ids = new ArrayList<String>();
        for (var row : rows) ids.add(field == null ? row.asText() : row.path(field).asText());
        return ids;
    }

    private static DecisionRun.ActionRef reply(WorldRuntime w,DecisionRun.Call call,String who,String tool,ObjectNode args) throws Exception {
        var body = JSON.createObjectNode().put("tool",tool);
        body.set("args",args);
        body.putObject("decision").put("objective","person:"+who).put("basis","查核本人可用原件。").put("reviewAfterMillis",60_000);
        assertEquals("QUEUED",w.offerDecisionCallback(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),call.attemptNo(),
                call.recoveryGeneration(),body.toString(),DecisionModel.Usage.unknownCost(1,30,20),"fixture:media-history")));
        assertEquals(1,w.drainDecisionCallbacks());
        return w.snapshot().decisions().orElseThrow().actionRef(call.actionId());
    }

    static JsonNode look(WorldRuntime w,String who,String action,String section,String key,String value) throws Exception {
        var args = JSON.createObjectNode().put("section",section);
        if (key != null) args.put(key,value);
        var call = PharmaDecisionToolTest.open(w,who,action);
        assertTrue(call.allowedTools().contains(RunManifest.PARTICIPANT_HISTORY_TOOL));
        var ref = reply(w,call,who,RunManifest.PARTICIPANT_HISTORY_TOOL,args);
        assertNotNull(ref);
        var state = w.snapshot().decisions().orElseThrow().state();
        assertEquals(1000,ref.dueMillis()-DecisionModel.decision(state,action).orElseThrow().barrierAtMillis());
        w.advanceTo(ref.dueMillis());
        return w.autonomyRoleObservation(who);
    }

    static String issue(WorldRuntime w,WorldRoleSession.MediaCitation cite,String id,String topic) throws Exception {
        var story = propose(w,id,topic);
        submit(w,new WorldRoleSession.MediaSubmitDraft(story,0,"查验记录","归档证据须核对原合同。","公开凭证",List.of(cite)));
        review(w,story,1,true,"核验完毕。");
        work(w,"ed",MediaRun.KIND_PUBLISH,(s,c,t,d)->s.publishStory(c,t,d,new WorldRoleSession.MediaStoryDraft(story,1)));
        return story;
    }

    private static void leaveDesk(WorldRuntime w) throws Exception {
        var job = w.openRoleSession("ed").view().appointments().stream().filter(a -> a.role()==RoleAccess.Role.EDITOR
                && a.organizationPartyId().equals("wire")).findFirst().orElseThrow().appointmentId();
        long at = w.snapshot().timeMillis()+1;
        w.scheduleRevokeAppointment("leave-desk","leave-desk",at,job);
        w.advanceTo(at);
    }

    @Test void recurringPublicationWithLongTopicsCanBlockTheOldRoleAndTheNewRoleCanStillChoose() throws Exception {
        for (boolean latest : List.of(false,true)) {
            var file = dir.resolve("threshold-"+latest+".journal");
            try (var w = latest ? ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(file)
                    : ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(file)) {
                w.advanceTo(34_200_000);
                var cite = read(w,"rita","engineering-opportunity:A1",1);
                var issued = new ArrayList<String>();
                for (int i=0; i<12; i++)
                    issued.add(issue(w,cite,"press-"+i,"工程合同核对第"+i+"期：附原始条款编号 "+"1234567890".repeat(1_000)));
                var observer = w.autonomyRoleObservation("ed");
                assertEquals(12,w.openRoleSession("ed").view().media().size());
                assertEquals(latest ? 4 : 12,observer.path("media").size());
                assertEquals(!latest,w.autonomyInputLimitViolation("ed").isPresent());
                if (latest) {
                    var older = look(w,"ed","press-first","media","id",issued.get(0));
                    assertEquals(List.of(issued.get(0)),values(older.at("/engineering/historyLookup/lastResult/ids"),null));
                    assertEquals(5,older.path("media").size());
                    assertTrue(w.autonomyInputLimitViolation("ed").isEmpty());
                    var last = look(w,"ed","page-before-leaving","media",null,null).at("/engineering/historyLookup/lastResult");
                    assertTrue(last.has("nextBefore"));
                    leaveDesk(w);
                    var afterLeave = w.autonomyRoleObservation("ed").at("/engineering/historyLookup/lastResult");
                    assertTrue(afterLeave.path("ids").isEmpty());
                    assertFalse(afterLeave.has("nextBefore"));
                }
            }
        }
    }

    @Test void currentWorkSurvivesAndOldIssuesCanStillBeWithdrawnWithCurrentRights() throws Exception {
        var file = dir.resolve("wire.journal");
        JsonNode beforeClose;
        try (var w = ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(file)) {
            w.advanceTo(34_200_000);
            var cite = read(w,"rita","engineering-opportunity:A1",1);
            var published = new ArrayList<String>();
            for (int i=0; i<6; i++) {
                published.add(issue(w,cite,"wire-"+i,"工程合同核验第"+i+"期"));
            }
            var inReview = propose(w,"pending","等待编辑核对");
            submit(w,new WorldRoleSession.MediaSubmitDraft(inReview,0,"核验中","尚未经审阅的正文。","凭证",List.of(cite)));
            var returned = propose(w,"returned","退回核对");
            submit(w,new WorldRoleSession.MediaSubmitDraft(returned,0,"需核验","需补正来源。","凭证",List.of(cite)));
            review(w,returned,1,false,"补正依据。");
            var observation = w.autonomyRoleObservation("ed");
            assertEquals(6,observation.at("/engineering/historyLookup/closedMediaCount").asInt());
            var visible = values(observation.path("media"),"storyId");
            assertFalse(visible.contains(published.get(0)));
            assertTrue(visible.containsAll(List.of(inReview,returned)));
            assertFalse(observation.toString().contains("尚未经审阅的正文。"));
            assertTrue(w.autonomyInputLimitViolation("ed").isEmpty());

            var page = look(w,"ed","recent-issues","media",null,null).at("/engineering/historyLookup/lastResult");
            assertEquals(List.of(published.get(5),published.get(4),published.get(3),published.get(2)),values(page.path("ids"),null));
            var older = look(w,"ed","older-issues","media","before",page.required("nextBefore").asText());
            assertEquals(List.of(published.get(1),published.get(0)),values(older.at("/engineering/historyLookup/lastResult/ids"),null));
            assertTrue(values(older.path("media"),"storyId").contains(published.get(0)));
            var unavailable = look(w,"investor","outsider","media","id",published.get(0));
            assertTrue(unavailable.at("/engineering/historyLookup/lastResult/ids").isEmpty());
            assertTrue(unavailable.path("media").isEmpty());

            var exact = look(w,"ed","exact-issue","media","id",published.get(0));
            assertEquals(List.of(published.get(0)),values(exact.at("/engineering/historyLookup/lastResult/ids"),null));
            var call = PharmaDecisionToolTest.open(w,"ed","correct-old-publication");
            var action = reply(w,call,"ed",RoleTradingView.TOOL_MEDIA_WITHDRAW,
                    JSON.createObjectNode().put("storyId",published.get(0)).put("expectedPublicVersion",1).put("publicReason","FACTUAL_ERROR"));
            assertNotNull(action);
            w.advanceTo(action.dueMillis());
            assertEquals("WITHDRAWN",row(w,"ed",published.get(0)).status());

            var own = w.openRoleSession("ed").view().notices();
            assertTrue(own.size() > 4);
            var oldest = own.get(0).clientTaskId();
            assertFalse(values(w.autonomyRoleObservation("ed").path("notices"),"clientTaskId").contains(oldest));
            var oldNotice = look(w,"ed","old-notice","notices","id",oldest);
            assertEquals(List.of(oldest),values(oldNotice.at("/engineering/historyLookup/lastResult/ids"),null));
            assertTrue(values(oldNotice.path("notices"),"clientTaskId").contains(oldest));
            assertTrue(look(w,"investor","other-notice","notices","id",oldest).at("/engineering/historyLookup/lastResult/ids").isEmpty());
            var privateDraft = look(w,"ed","search-private-draft","media","id",inReview);
            assertFalse(privateDraft.toString().contains("尚未经审阅的正文。"));
            leaveDesk(w);
            beforeClose = w.autonomyRoleObservation("ed");
            assertTrue(beforeClose.at("/engineering/historyLookup/lastResult/ids").isEmpty());
            assertFalse(beforeClose.at("/engineering/historyLookup/lastResult").has("id"));
            assertTrue(beforeClose.path("media").isEmpty());
            assertFalse(beforeClose.toString().contains(inReview),"失岗后不再展示该内部稿号");
        }
        var original = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(file)) {
            assertEquals(beforeClose,w.autonomyRoleObservation("ed"));
        }
        assertArrayEquals(original,Files.readAllBytes(file));
    }
}
