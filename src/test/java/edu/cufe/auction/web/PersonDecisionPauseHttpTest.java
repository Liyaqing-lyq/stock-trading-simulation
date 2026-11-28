package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonDecisionPauseHttpTest {
    @TempDir Path dir;
    private final AtomicReference<String> bootstrap=new AtomicReference<>("person-pause-fixture");
    private static final String PATH="/api/admin/person-decision-pause";
    @Test void officialControlsAndLocalSchedulerRespectSinglePersonAcrossRestarts() throws Exception {
        Path keys=dir.resolve("keys-fixture"),journal=dir.resolve("world.journal");
        Files.write(keys,List.of("sk-pause-fixture-0001","sk-pause-fixture-0002","sk-pause-fixture-0003","sk-pause-fixture-0004","sk-pause-fixture-0005"));
        List<String> calls=new CopyOnWriteArrayList<>();
        try(var routes=SceneRoutes.open(dir)) {
            routes.replace(0,routes.view().routes().stream().map(r->new SceneRoutes.Route(r.scene(),r.slotId(),r.model(),true,
                    500,5_000_000,512,2,"lifecycle-v1")).toList());
            for(int round=0;round<2;round++) {
                try(var host=new WorldWebHost(()->new WorldWebHost.Opened(ComposedIndustryScenario.openPersonPauseOperating(
                        journal,Path.of("src/main/resources/world/opening-copies-example.json")),RunManifest.PERSON_PAUSE_RUN_ID),routes,(call,timeout)->{
                    var input=assertDoesNotThrow(()->JSON.readTree(call.inputText()));
                    calls.add(input.path("person").asText());
                    var reply=JSON.createObjectNode().put("tool","role.wait");
                    reply.putObject("args");reply.putObject("decision").put("objective",input.path("engineering").path("deliberation")
                            .path("objectives").get(0).path("id").asText()).put("basis","单人调度受控核验").put("reviewAfterMillis",60000);
                    return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),
                            call.attemptNo(),call.recoveryGeneration(),reply.toString(),DecisionModel.Usage.unknownCost(1,15,10),"fixture"));
                });var server=new WorldWebServer(host,KeySlots.load(keys),routes,0,bootstrap.get(),null,line->{
                    if(line.contains("引导凭据"))bootstrap.set(line.substring(line.indexOf('：')+1).trim());
                })) {
                    server.start();base=server.baseUrl();admin();
                    if(round==0){
                        long cut=host.adminSnapshot().position();
                        change("investor",true,cut,200);
                        change("nina",true,cut,409);
                        person("investor");
                        assertTrue(own().path("decisionPaused").asBoolean());
                        change("investor",false,host.adminSnapshot().position(),403);
                        admin();person("nina");
                        assertFalse(own().path("decisionPaused").asBoolean(true));
                        assertFalse(own().has("pausedPersons"));
                        runSelected(host,routes,Set.of("investor"));
                        assertTrue(calls.isEmpty());
                    } else {
                        person("investor");assertTrue(own().path("decisionPaused").asBoolean());admin();
                    }
                    var start=host.adminSnapshot();
                    if(round==1)change("investor",false,start.position(),200);
                    runSelected(host,routes,round==0 ? Set.of("investor","nina") : Set.of("investor"));
                    assertTrue(calls.contains("nina"));
                    assertEquals(round==1,calls.contains("investor"),calls.toString());
                } finally {cookie=null;}
            }
        }
    }
    private static void runSelected(WorldWebHost host,SceneRoutes routes,Set<String> people) throws Exception {
        var start=host.adminSnapshot();
        host.startAutonomy(start.position(),routes.view().revision(),start.timeMillis()+100,3_600_000L,people);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(host.autonomyStatus().running() && System.nanoTime()<deadline)Thread.sleep(35);
        assertEquals("TARGET_REACHED",host.autonomyStatus().reason());
    }
    private void admin() throws Exception {
        cookie=null;
        var login=post("/api/session",JSON.createObjectNode().put("bootstrap",bootstrap.get()).toString());
        assertEquals(200,login.statusCode(),login.body());remember(login);
    }
    private static void person(String id) throws Exception {
        var login=post("/api/session/person",JSON.createObjectNode().put("personId",id).toString());
        assertEquals(200,login.statusCode(),login.body());remember(login);
    }
    private static JsonNode own() throws Exception {
        var view=get("/api/me/view");assertEquals(200,view.statusCode(),view.body());return JSON.readTree(view.body());
    }
    private static void change(String person,boolean paused,long position,int status) throws Exception {
        var res=post(PATH,JSON.createObjectNode().put("expectedPosition",Long.toString(position)).put("person",person).put("paused",paused).toString());
        assertEquals(status,res.statusCode(),res.body());
    }
}
