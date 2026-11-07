package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

/** Independent actual HTTP checks; all credentials and stories are test fixtures. */
class WorldWebMediaHttpTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static String base,cookie; static int count;
    static void check(boolean good,String name) { if(!good)throw new AssertionError(name); count++;System.out.println("PASS "+name); }
    static HttpResponse<String> request(String method,String path,String body,String origin,boolean marker)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(10));
        if(cookie!=null)b.header("Cookie",cookie);
        if(origin!=null)b.header("Origin",origin);
        if(marker)b.header("X-World-Request","1");
        if(method.equals("GET"))b.GET(); else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(String path,String body)throws Exception {return request("POST",path,body,base,true);}
    static HttpResponse<String> get(String path)throws Exception {return request("GET",path,null,null,false);}
    static void remember(HttpResponse<String> r){cookie=r.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];}
    @org.junit.jupiter.api.Test
    void actualMediaDetailsAreBoundedAndPermissionScoped() throws Exception {
        Path root=Path.of(System.getProperty("web.media.test.root", "target/web-media-tests"));
        Files.createDirectories(root);
        Path dir=Files.createTempDirectory(root,"actualMediaDetailsAreBoundedAndPermissionScoped-");
        Path keys=dir.resolve("dummy-keys.txt");
        Files.writeString(keys,"sk-media-http-fixture-00001\nsk-media-http-fixture-00002\nsk-media-http-fixture-00003\nsk-media-http-fixture-00004\nsk-media-http-fixture-00005");
        try(var routes=SceneRoutes.open(dir);
            var host=new WorldWebHost(()->MediaWebFixture.prepare(dir.resolve("world.journal")),routes,
                new LiveDecisionProvider(slot->{throw new AssertionError("No provider calls allowed");}));
            var server=new WorldWebServer(host,KeySlots.load(keys),routes,0,"http-media-fixture",null,ignored->{})) {
            server.start();base=server.baseUrl();
            check(get("/api/admin/media").statusCode()==401,"anonymous media denied");
            check(get("/api/admin/capabilities").statusCode()==401,"anonymous capabilities denied");
            var login=post("/api/session","{\"bootstrap\":\"http-media-fixture\"}");
            check(login.statusCode()==200,"administrator login");remember(login);
            long position=host.adminSnapshot().position();
            var auditResponse=get("/api/admin/media");var audit=JSON.readTree(auditResponse.body());
            check(auditResponse.statusCode()==200,"admin actual media query");
            check(auditResponse.headers().firstValue("cache-control").orElseThrow().equals("no-store"),"media no-store");
            check(audit.path("position").asText().equals(Long.toString(position)),"audit exact cut");
            check(audit.path("organizations").path("total").asInt()==2,"two real media organizations");
            var caps=JSON.readTree(get("/api/admin/capabilities").body());
            check(caps.path("persons").size()==8,"eight actually configured model actors");
            check(caps.path("supportedScenes").toString().contains("media"),"new profile media supported");
            var story=audit.path("stories").path("rows").get(0);
            String body=JSON.writeValueAsString(Map.of("organizationPartyId",story.path("organizationPartyId").asText(),
                    "storyId",story.path("storyId").asText(),"expectedPosition",position));
            var details=post("/api/admin/media-story",body);
            check(details.statusCode()==200,"story detail query");
            var detail=JSON.readTree(details.body());
            check(detail.path("versions").path("total").asInt()==4,"two drafts and two public original versions");
            check(detail.path("citations").path("total").asInt()==2,"two actual reading citations");
            check(detail.path("roots").path("total").asInt()==2,"two independent original sources");
            check(detail.path("position").asText().equals(audit.path("position").asText()),"detail and list same cut");
            check(request("POST","/api/admin/media-story",body,"http://example.invalid",true).statusCode()==403,"foreign origin rejected");
            check(request("POST","/api/admin/media-story",body,base,false).statusCode()==403,"missing request marker rejected");
            for(String bad:List.of("{}","null","[]",body.substring(0,body.length()-1)+",\"extra\":true}",
                    body.replace("\"expectedPosition\":"+position,"\"expectedPosition\":1.5"),
                    body.replace("\"expectedPosition\":"+position,"\"expectedPosition\":null"),body+"{}")) {
                check(post("/api/admin/media-story",bad).statusCode()==400,"invalid detail body rejected "+count);
            }
            check(host.adminSnapshot().position()==position,"all detail reads preserve world position");
            host.advance(position,host.adminSnapshot().timeMillis()+1);
            check(post("/api/admin/media-story",body).statusCode()==409,"stale list cut rejected");
            var switched=post("/api/session/person","{\"personId\":\"investor\"}");
            check(switched.statusCode()==200,"switch to reader");remember(switched);
            check(get("/api/admin/media").statusCode()==403,"person denied admin media");
            check(get("/api/admin/capabilities").statusCode()==403,"person denied admin capabilities");
            check(post("/api/admin/media-story",body).statusCode()==403,"person denied original draft details");
            var ownResponse=get("/api/me/view");var own=JSON.readTree(ownResponse.body());
            check(ownResponse.statusCode()==200,"reader personal view");
            check(!own.has("position")&&!own.path("media").has("position"),"personal page has no administrator cut");
            check(own.path("media").path("stories").path("total").asInt()==0,"reader cannot see private workflow");
            var docs=own.path("media").path("documents");
            check(docs.path("total").asInt()==1&&docs.path("rows").get(0).path("version").asText().equals("1"),"reader keeps only read version one");
            check(!ownResponse.body().contains("第二版正文")&&!ownResponse.body().contains("media-draft:"),"personal response contains neither unread correction nor draft");
            Files.writeString(dir.resolve("personal.json"),ownResponse.body());
            Files.writeString(dir.resolve("audit.json"),auditResponse.body());
            System.out.println("TOTAL PASS "+count);
        }
    }
}
