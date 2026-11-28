package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.PersonalMarket;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersonalMarketRoutingTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode request(java.net.http.HttpClient client,String base,String path,
            com.fasterxml.jackson.databind.node.ObjectNode body) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+path));
        if (body!=null) request.header("Content-Type","application/json").header("X-World-Request","1").header("Origin",base)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()));
        var result=client.send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,result.statusCode(),result.body());
        return JSON.readTree(result.body());
    }

    private static void checkInvestorCall(DecisionRun.Call call) throws Exception {
        assertEquals("trading",call.plan().route().sceneId());
        assertTrue(call.allowedTools().contains(PersonalMarket.SUBMIT));
        var observed=JSON.readTree(call.inputText());
        assertEquals("investor",observed.path("person").asText());
        assertEquals("INV",observed.at("/profile/accounts/0/accountId").asText());
        assertEquals("10000.00",observed.at("/profile/accounts/0/availableCash").asText());
        assertEquals(10,observed.at("/profile/holdings").size());
    }

    private static LiveCompletionInbox.Completion scriptedWait(DecisionRun.Call call,ConcurrentLinkedQueue<DecisionRun.Call> calls) {
        try {
            var input=JSON.readTree(call.inputText());
            calls.add(call);
            var reply=JSON.createObjectNode().put("tool","role.wait");
            reply.putObject("decision").put("objective",input.at("/engineering/deliberation/objectives/0/id").asText())
                    .put("basis","离线路由测试").put("reviewAfterMillis",3_600_000);
            return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),
                    call.attemptId(),call.attemptNo(),call.recoveryGeneration(),reply.toString(),
                    DecisionModel.Usage.unknownCost(1,9,21),"fixture:scripted"));
        } catch(Exception e){throw new AssertionError(e);}
    }

    @Test void autonomousWorldWithEverySceneEnabledFreezesInvestorInputUnderTrading() throws Exception {
        var calls=new ConcurrentLinkedQueue<DecisionRun.Call>();
        Path journal=dir.resolve("all-scenes.journal");
        try(var routes=SceneRoutes.open(dir)) {
            routes.replace(routes.view().revision(),routes.view().routes().stream().map(r->
                    new SceneRoutes.Route(r.scene(),r.slotId(),r.model(),true,250,35_000_000,8192,4,
                            "lifecycle-v1","deepseek-thinking-high-v1")).toList());
            try(var host=new WorldWebHost(()->{
                var w=ComposedIndustryScenario.openPersonalMarketOperating(journal);
                return new WorldWebHost.Opened(w,w.profile().manifest().runId());
            },routes,(call,timeout)->scriptedWait(call,calls))) {
                var initial=host.autonomyStatus();
                host.startAutonomy(Long.parseLong(initial.position()),routes.view().revision(),12_000,3_600_000);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
                while(host.autonomyStatus().running() && System.nanoTime()<deadline) Thread.sleep(20);
                assertFalse(host.autonomyStatus().running());
                var investorCalls=calls.stream().filter(c->c.allowedTools().contains(PersonalMarket.SUBMIT)).toList();
                assertFalse(investorCalls.isEmpty(),host.autonomyStatus().toString());
                for(var call:investorCalls) checkInvestorCall(call);
                assertTrue(calls.stream().anyMatch(c->c.plan().route().sceneId().equals("governance")));
                assertTrue(calls.stream().anyMatch(c->c.plan().route().sceneId().equals("media")));
            }
        }
    }

    @Test void onlyTradingSceneDispatchesOwnedInvestorOnceWithoutChangingFundedV2Routing() throws Exception {
        for (boolean personal : new boolean[]{false, true}) {
            Path journal=dir.resolve(personal ? "personal.journal" : "funded.journal");
            var calls=new ConcurrentLinkedQueue<DecisionRun.Call>();
            try(var routes=SceneRoutes.open(dir)) {
                routes.replace(routes.view().revision(),routes.view().routes().stream().map(r->
                        new SceneRoutes.Route(r.scene(),r.slotId(),r.model(),r.scene()==SceneRoutes.Scene.TRADING,
                                60,5_000_000,8192,4,"lifecycle-v1","deepseek-thinking-high-v1")).toList());
                try(var host=new WorldWebHost(()->{
                    var w=personal ? ComposedIndustryScenario.openPersonalMarketOperating(journal)
                            : ComposedIndustryScenario.openFundedThinkingOperating(journal);
                    return new WorldWebHost.Opened(w,w.profile().manifest().runId());
                },routes,(call,timeout)->scriptedWait(call,calls))) {
                    var people=host.liveCapabilities().persons();
                    var mine=people.stream().filter(row->row.personPartyId().equals("investor")).toList();
                    assertEquals(1,mine.size());
                    assertEquals(personal ? "trading" : "media",mine.get(0).sceneId());
                    assertEquals("research",people.stream().filter(row->row.personPartyId().equals("alice")).findFirst().orElseThrow().sceneId());
                    if (!personal) continue;
                    var status=host.autonomyStatus();
                    assertThrows(WorldWebHost.SceneRejected.class,()->host.startAutonomy(Long.parseLong(status.position()),
                            routes.view().revision(),12_000,3_600_000));
                    Path keys=dir.resolve("local-fake-keys");
                    java.nio.file.Files.writeString(keys,String.join("\n","sk-fixture-aaaaaaaaaaa","sk-fixture-bbbbbbbbbbb",
                            "sk-fixture-ccccccccccc","sk-fixture-ddddddddddd","sk-fixture-eeeeeeeeeee"));
                    try(var server=new WorldWebServer(host,KeySlots.load(keys),routes,0,"personal-routing-fixture",null,ignored->{})) {
                        server.enableLocalAccess();
                        server.start();
                        var cookies=new java.net.CookieManager();
                        cookies.setCookiePolicy(java.net.CookiePolicy.ACCEPT_ALL);
                        var client=java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build();
                        String base="http://127.0.0.1:"+server.port();
                        assertEquals("ADMIN",request(client,base,"/api/session/local",JSON.createObjectNode()).path("role").asText());
                        var capabilities=request(client,base,"/api/admin/capabilities",null);
                        assertTrue(capabilities.path("persons").findValuesAsText("personPartyId").contains("investor"));
                        for(var actor:capabilities.path("persons")) if(actor.path("personPartyId").asText().equals("investor"))
                            assertEquals("trading",actor.path("sceneId").asText());
                        var payload=JSON.createObjectNode().put("expectedPosition",status.position()).put("expectedRevision",routes.view().revision())
                                .put("scene","trading");
                        payload.putArray("persons").add("investor");
                        request(client,base,"/api/admin/live/start",payload);
                        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                        do {
                            var round=request(client,base,"/api/admin/live",null);
                            if (!calls.isEmpty() && round.path("decisions").size()==1 && !round.at("/decisions/0/status").asText().equals("OPEN")) break;
                            Thread.sleep(20);
                        } while(System.nanoTime()<deadline);
                        assertEquals(1,calls.size());
                        checkInvestorCall(calls.element());
                    }
                }
            }
        }
    }
}
