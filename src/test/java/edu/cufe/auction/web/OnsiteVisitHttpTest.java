package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class OnsiteVisitHttpTest {
    @TempDir Path dir;
    private static final String INTERVIEW = "/api/me/interview", ACTION = INTERVIEW + "/site-visit";
    private final AtomicReference<String> bootstrap = new AtomicReference<>("onsite-visit-fixture");

    static String prepareAppointment(WorldRuntime w) throws Exception {
        String doc = WorldWebScenario.INTERVIEW_CONTACT_DOC;
        long t = w.snapshot().timeMillis()+1;
        if (w.roleView("rita").catalog().stream().noneMatch(it -> it.docId().equals(doc))) {
            w.schedulePublishDocument("public-contact","public-contact",t,doc,"investor",InformationModel.Audience.publicAudience(),
                    "公开接待预约","可通过公开投关热线预约现场采访。","投资者本人",1000,List.of());
            w.advanceTo(t);
        }
        t=w.snapshot().timeMillis()+1000;
        w.openRoleSession("rita").startReading("learn-contact","learn-contact",t,doc,1);
        w.advanceTo(t);
        w.advanceTo(w.roleView("rita").readings().get(0).dueAt());
        long now=w.snapshot().timeMillis(), start=now+90_000;
        w.openRoleSession("rita").inviteInterview("invite","invite",now+1000,
                new WorldRoleSession.InterviewInviteDraft("onsite","investor","现场查阅工作安排",start-2000,start+150_000,120_000));
        w.advanceTo(now+1000);
        String request=w.interviewView("investor").invites().get(0).requestId();
        w.openRoleSession("investor").respondInterview("accept","accept",now+2000,
                new WorldRoleSession.InterviewRespondDraft(request,true,start));
        w.advanceTo(start-15_000);
        assertTrue(w.siteVisitsFor("investor").appointments().get(0).actions().contains("HOST_CONSENT"));
        return request;
    }

    private void as(String person) throws Exception {
        cookie=null;
        var admin=post("/api/session",JSON.createObjectNode().put("bootstrap",bootstrap.get()).toString());
        assertEquals(200,admin.statusCode(),admin.body());remember(admin);
        if(person!=null){
            var role=post("/api/session/person",JSON.createObjectNode().put("personId",person).toString());
            assertEquals(200,role.statusCode(),role.body());remember(role);
        }
    }
    private static JsonNode own() throws Exception {
        var r=get(INTERVIEW);assertEquals(200,r.statusCode(),r.body());return JSON.readTree(r.body());
    }
    private static ObjectNode action(JsonNode view,String request,String verb) {
        return JSON.createObjectNode().put("observedMillis",view.path("timeMillis").asText())
                .put("expectedStage",view.at("/siteVisits/appointments/0/stage").asText("APPOINTMENT"))
                .put("commandId","web-"+verb).put("requestId",request).put("action",verb);
    }
    private static void acceptAndAdvance(WorldWebHost host,ObjectNode command) throws Exception {
        var result=post(ACTION,command.toString());
        assertEquals(200,result.statusCode(),result.body());
        host.advance(host.adminSnapshot().position(),JSON.readTree(result.body()).path("dueTimeMillis").asLong());
    }

    @Test void onlyParticipantsCanConsentSpendAndRevokeThroughTheirOwnSessions() throws Exception {
        Path journal=dir.resolve("world.journal"), keys=dir.resolve("dummy");
        Files.write(keys,List.of("sk-onsite-dummy-11111","sk-onsite-dummy-22222","sk-onsite-dummy-33333","sk-onsite-dummy-44444","sk-onsite-dummy-55555"));
        String request;
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(journal)) { request=prepareAppointment(w); }
        try(var routes=SceneRoutes.open(dir);
            var host=new WorldWebHost(()->new WorldWebHost.Opened(ComposedIndustryScenario.openTenCompanyAutonomy(journal),RunManifest.TEN_COMPANY_AUTONOMY_RUN_ID));
            var server=new WorldWebServer(host,KeySlots.load(keys),routes,0,bootstrap.get(),null,line->{
                if(line.contains("引导凭据"))bootstrap.set(line.substring(line.indexOf('：')+1).trim());
            })) {
            server.start();base=server.baseUrl();cookie=null;
            assertEquals(401,post(ACTION,"{}").statusCode());
            as(null);assertEquals(403,post(ACTION,"{}").statusCode());
            as("nina");var stranger=own();
            assertTrue(stranger.at("/siteVisits/appointments").isEmpty());
            assertFalse(stranger.has("position"));assertFalse(stranger.toString().contains(request));
            var guessed=post(ACTION,action(stranger,request,"HOST_CONSENT").toString());
            assertEquals(400,guessed.statusCode());
            assertEquals(guessed.body(),post(ACTION,action(stranger,"nonexistent","HOST_CONSENT").toString()).body());
            as("investor");var visitor=own();
            assertEquals("12.00",visitor.at("/siteVisits/appointments/0/fare").asText());
            var consent=action(visitor,request,"HOST_CONSENT");
            assertEquals(400,post(ACTION,consent.deepCopy().put("personId","rita").toString()).statusCode());
            acceptAndAdvance(host,consent);assertEquals(409,post(ACTION,consent.toString()).statusCode());
            as("rita");acceptAndAdvance(host,action(own(),request,"DEPART"));
            var afterBooking=own().at("/siteVisits/appointments/0");
            if (afterBooking.path("stage").asText().equals("BOOKED"))
                host.advance(host.adminSnapshot().position(),afterBooking.path("startMillis").asLong()-10000);
            as("investor");var outbound=own();
            assertEquals("OUTBOUND",outbound.at("/siteVisits/appointments/0/stage").asText());
            assertEquals("12.00",outbound.at("/siteVisits/appointments/0/farePaid").asText());
            acceptAndAdvance(host,action(outbound,request,"REVOKE"));
            long cut=host.adminSnapshot().position(),time=host.adminSnapshot().timeMillis();
            host.advance(cut,time+30000);
            as("rita");var returned=own().at("/siteVisits/appointments/0");
            assertEquals("HOME",returned.path("stage").asText());assertFalse(returned.path("observed").asBoolean(true));
            assertEquals("12.00",returned.path("farePaid").asText());
        } finally {cookie=null;}
    }
}
