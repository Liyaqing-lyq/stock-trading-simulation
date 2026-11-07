package edu.cufe.auction.web;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual Host -> loopback provider -> DecisionRun -> media task, no real API. */
class WorldWebMediaLiveTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static int checks;
    static void check(boolean good,String name){if(!good)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    @org.junit.jupiter.api.Test
    void mediaActorsUseSceneRouteAndCommitActualTools() throws Exception {
        Path root=Path.of(System.getProperty("web.media.test.root", "target/web-media-tests"));
        Files.createDirectories(root);
        Path dir=Files.createTempDirectory(root,"mediaActorsUseSceneRouteAndCommitActualTools-");
        var inputs=new ConcurrentHashMap<String,JsonNode>();
        var errors=new ConcurrentLinkedQueue<String>();
        var provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/chat/completions",exchange->{
            try {
                var request=JSON.readTree(exchange.getRequestBody().readAllBytes());
                var input=JSON.readTree(request.path("messages").get(1).path("content").asText());
                String person=input.path("person").asText();inputs.put(person,input);
                if(!"Bearer sk-media-live-fixture-00004".equals(exchange.getRequestHeaders().getFirst("Authorization")))errors.add("wrong slot");
                String content=person.equals("rita")||person.equals("sam")
                        ? JSON.writeValueAsString(Map.of("tool","media.story.propose","args",Map.of("storyId","live-topic",
                            "template",person.equals("rita")?"WIRE":"INVESTIGATIVE","topic","Live simulated proposal")))
                        : "{\"tool\":\"role.wait\"}";
                byte[] body=JSON.writeValueAsBytes(Map.of("id","fixture","model","deepseek-flash",
                        "choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",content),"finish_reason","stop")),
                        "usage",Map.of("prompt_tokens",11,"completion_tokens",5,"total_tokens",16)));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);exchange.close();
            }catch(Exception failure){errors.add(failure.getClass().getSimpleName());exchange.close();}
        });provider.start();
        try(var routes=SceneRoutes.open(dir)) {
            var snapshot=routes.view();var updated=new ArrayList<SceneRoutes.Route>();
            for(var route:snapshot.routes())updated.add(new SceneRoutes.Route(route.scene(),route.scene()==SceneRoutes.Scene.MEDIA?"key-4":route.slotId(),
                    route.model(),route.scene()==SceneRoutes.Scene.MEDIA,10,1000000,512,1));
            routes.replace(snapshot.revision(),updated);
            URI uri=URI.create("http://127.0.0.1:"+provider.getAddress().getPort()+"/chat/completions");
            try(var host=new WorldWebHost(()->WorldWebScenario.openMedia(dir.resolve("world.journal")),routes,
                    new LiveDecisionProvider(slot->new DeepSeekClient(()->"sk-media-live-fixture-00004",uri)))) {
                check(inputs.isEmpty(),"opening makes no request");
                host.startLiveRound(host.adminSnapshot().position(),routes.view().revision(),List.of("rita","ed","sam","bea"));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                WorldWebHost.LiveStatus status;
                do {
                    status=host.liveStatus();
                    if(inputs.size()==4&&status.physicalInFlight()==0&&status.completionSlots()==0
                            &&status.decisions().size()==4&&status.decisions().stream().noneMatch(d->d.status().equals("OPEN")))break;
                    Thread.sleep(25);
                }while(System.nanoTime()<until);
                check(inputs.keySet().equals(Set.of("rita","ed","sam","bea")),"four registered media actors routed");
                check(errors.isEmpty(),"all simulated requests use selected media slot");
                check(status.decisions().size()==4&&status.decisions().stream().noneMatch(d->d.status().equals("OPEN")),"all four actual decisions settled");
                var current=host.adminSnapshot();host.advance(current.position(),current.timeMillis()+2000);
                var audit=host.mediaAudit();
                check(audit.stories().total()==2,"two provider proposals become actual stories");
                check(audit.works().total()==2,"editor waits invent no work");
                check(audit.reports().rows().stream().allMatch(row->Boolean.TRUE.equals(row.get("accepted"))),"media action reports accepted");
                for(var entry:inputs.entrySet()) {
                    String input=entry.getValue().toString();
                    check(!input.contains("sk-media-live")&&!input.contains("managementReports"),"bounded input no secrets or god audit for "+entry.getKey());
                }
                Files.writeString(dir.resolve("live-status.json"),JSON.writeValueAsString(status));
                Files.writeString(dir.resolve("media-audit.json"),JSON.writeValueAsString(audit));
                System.out.println("TOTAL PASS "+checks);
            }
        } finally {provider.stop(0);}
    }
}
