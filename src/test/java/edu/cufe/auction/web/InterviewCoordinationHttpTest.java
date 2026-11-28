package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchRepository;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class InterviewCoordinationHttpTest {
    @TempDir Path dir;
    private static final String PAGE="/api/me/interview", ACTION=PAGE+"/coordination", REPORTER="digital-manager";
    private final AtomicReference<String> bootstrap=new AtomicReference<>("interview-clock-local");

    private void as(String person) throws Exception {
        cookie=null;
        var admin=post("/api/session",JSON.createObjectNode().put("bootstrap",bootstrap.get()).toString());
        assertEquals(200,admin.statusCode(),admin.body()); remember(admin);
        if (person!=null) {
            var role=post("/api/session/person",JSON.createObjectNode().put("personId",person).toString());
            assertEquals(200,role.statusCode(),role.body()); remember(role);
        }
    }
    private static JsonNode own() throws Exception {
        var r=get(PAGE); assertEquals(200,r.statusCode());return JSON.readTree(r.body());
    }
    private static ObjectNode action(JsonNode page,String verb,String id) {
        return JSON.createObjectNode().put("commandId",id).put("action",verb).put("observedMillis",page.at("/coordination/observedMillis").longValue());
    }
    private static long apply(WorldWebHost host,ObjectNode command) throws Exception {
        var r=post(ACTION,command.toString());
        assertEquals(200,r.statusCode(),r.body());
        var q=JSON.readTree(r.body());assertTrue(q.required("receipt").asText().startsWith("SCHEDULED:"));
        var scheduledPosition=host.adminSnapshot().position();
        var duplicate=post(ACTION,command.toString());
        assertEquals(200,duplicate.statusCode(),duplicate.body());
        assertEquals(q,JSON.readTree(duplicate.body()));assertEquals(scheduledPosition,host.adminSnapshot().position());
        long due=q.required("dueTimeMillis").asLong();
        host.advance(host.adminSnapshot().position(),due);
        var finalPosition=host.adminSnapshot().position();
        var completed=post(ACTION,command.toString());
        assertEquals(200,completed.statusCode(),completed.body());
        assertEquals(q,JSON.readTree(completed.body()));assertEquals(finalPosition,host.adminSnapshot().position());
        return due;
    }

    @Test void visibleContactAndOnlyRecipientCanUseRealAcceptAndDecline() throws Exception {
        Path journal=dir.resolve("world.journal"),keys=dir.resolve("keys");
        Files.write(keys,List.of("sk-interview-fixture-001","sk-interview-fixture-002","sk-interview-fixture-003","sk-interview-fixture-004","sk-interview-fixture-005"));
        String childId="interview-human";
        BranchProfile profile;
        try(var w=ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(journal)) {
            var clock=w.personalInterviewControls(REPORTER);
            long now=w.snapshot().timeMillis();
            assertEquals(now,clock.observedMillis());assertEquals(now+61000L,clock.earliestWindowStartMillis());
            assertTrue(w.interviewView(REPORTER).contacts().isEmpty());
            w.openRoleSession(REPORTER).startReading("actual-read","read-contact",now+1,WorldWebScenario.INTERVIEW_CONTACT_DOC,1);
            w.advanceTo(now+1);w.advanceTo(w.roleView(REPORTER).readings().get(0).dueAt());
            w.createBranch(childId,w.snapshot().position(),BranchOrigin.Kind.GOD_TAKEOVER,"人工接管人物 "+REPORTER);
            profile=w.profile();
        }
        byte[] parentOriginal=Files.readAllBytes(journal);
        try(var routes=SceneRoutes.open(dir);
            var host=new WorldWebHost(()->new WorldWebHost.Opened(new BranchRepository(dir.resolve("runs")).open(childId,profile),childId));
            var server=new WorldWebServer(host,KeySlots.load(keys),routes,0,bootstrap.get(),null,line->{
                if(line.contains("引导凭据"))bootstrap.set(line.substring(line.indexOf('：')+1).trim());
            })) {
            server.start();base=server.baseUrl();cookie=null;
            assertEquals(401,post(ACTION,"{}").statusCode());as(null);assertEquals(403,post(ACTION,"{}").statusCode());
            as(REPORTER);
            var earlySender=own(); long bound=earlySender.at("/coordination/earliestWindowStartMillis").longValue();
            var earlyInvite=action(earlySender,"INVITE","early-invite").put("toPartyId","investor").put("topic","时间边界")
                    .put("windowStartMillis",bound-1).put("windowEndMillis",bound+600000).put("durationMillis",60000);
            long executed=apply(host,earlyInvite);
            var rejected=own().at("/coordination/receipts/0");
            assertEquals("WINDOW",rejected.required("reason").asText());
            assertEquals(1,rejected.at("/windowTiming/outsideByMillis").longValue());
            assertEquals(executed,rejected.at("/windowTiming/executionMillis").longValue());
            assertEquals(bound,rejected.at("/windowTiming/earliestStartMillis").longValue());
            assertEquals(400,post(ACTION,earlyInvite.deepCopy().put("windowStartMillis",bound).toString()).statusCode());
            for (String response : List.of("DECLINE","ACCEPT")) {
                as(REPORTER);
                var sender=own();
                long first=sender.at("/coordination/earliestWindowStartMillis").longValue();
                var invite=action(sender,"INVITE","invite-"+response).put("toPartyId","investor").put("topic","核实原始口径")
                        .put("windowStartMillis",first).put("windowEndMillis",first+600000).put("durationMillis",60000);
                assertEquals(400,post(ACTION,invite.deepCopy().put("toPartyId","nina").toString()).statusCode());
                long due=apply(host,invite);
                var sent=own().at("/coordination/receipts/0");assertTrue(sent.required("accepted").booleanValue());
                assertEquals(due,sent.required("timeMillis").longValue());String request=sent.required("requestId").asText();
                as("alice");
                var outsider=own();assertTrue(outsider.at("/coordination/inviteOffice").isNull());
                assertFalse(outsider.toString().contains(request));
                var forged=action(outsider,"DECLINE","name-another").put("requestId",request);
                assertEquals(400,post(ACTION,forged.toString()).statusCode());
                as("investor");
                var recipient=own();
                if ("ACCEPT".equals(response)) {
                    var earlyAnswer=action(recipient,"ACCEPT","early-accept").put("requestId",request).put("startMillis",first-1);
                    long responded=apply(host,earlyAnswer);
                    recipient=own();
                    var failed=recipient.at("/coordination/receipts/0");
                    assertEquals("WINDOW",failed.required("reason").asText());
                    assertEquals(responded,failed.at("/windowTiming/executionMillis").longValue());
                    assertEquals(1,failed.at("/windowTiming/outsideByMillis").longValue());
                    assertEquals(first-1,failed.at("/windowTiming/requestedStartMillis").longValue());
                }
                var answer=action(recipient,response,"answer-"+response).put("requestId",request);
                if ("ACCEPT".equals(response)) answer.put("startMillis",first);
                apply(host,answer);
                var observed=own();
                assertTrue(observed.at("/coordination/receipts/0/accepted").booleanValue());
                var statuses=new java.util.HashMap<String,String>();
                observed.required("invites").forEach(i -> statuses.put(i.required("requestId").asText(),i.required("status").asText()));
                assertEquals("ACCEPT".equals(response)?"ACCEPTED":"DECLINED",statuses.get(request));
            }
        } finally {cookie=null;}
        assertArrayEquals(parentOriginal,Files.readAllBytes(journal));
    }
}
