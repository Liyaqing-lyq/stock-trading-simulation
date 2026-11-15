package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 本机直达、中文投影及人物会话边界的真实 HTTP 回归。 */
class WorldLocalOverviewTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final HttpClient HTTP=HttpClient.newHttpClient();

    @Test void localAccessIsExplicitAndDoesNotReplaceOriginOrRoleChecks() throws Exception {
        try(var host=host();var server=server(host)) {
            server.start();
            assertEquals(404,send(server,"POST","/api/session/local",null,true,Map.of()).statusCode());
            assertEquals(401,send(server,"GET","/api/admin/world",null,true,Map.of()).statusCode());
            assertThrows(IllegalStateException.class,server::enableLocalAccess);
        }
        try(var host=host();var server=server(host)) {
            server.enableLocalAccess();server.start();
            assertEquals(403,send(server,"POST","/api/session/local",null,false,Map.of()).statusCode());
            for(var headers:List.of(Map.of("Origin","https://other.example"),Map.of("Sec-Fetch-Site","cross-site"),
                    Map.of("Forwarded","for=1.2.3.4"),Map.of("X-Forwarded-For","1.2.3.4")))
                assertEquals(403,send(server,"POST","/api/session/local",null,true,headers).statusCode());
            var local=send(server,"POST","/api/session/local",null,true,Map.of());
            assertEquals(200,local.statusCode());
            String cookie=local.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));
            cookie=cookie.split(";",2)[0];
            assertTrue(JSON.readTree(send(server,"GET","/api/session",cookie,true,Map.of()).body()).path("localAccess").asBoolean());
            send(server,"POST","/api/session/local",null,true,Map.of());
            assertEquals(200,send(server,"GET","/api/admin/world",cookie,true,Map.of()).statusCode(),"另一个本机页面不得踢掉已有会话");
            var person=HTTP.send(HttpRequest.newBuilder(URI.create(server.baseUrl()+"/api/session/person"))
                    .header("Origin",server.baseUrl()).header("X-World-Request","1").header("Cookie",cookie)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"personId\":\"alice\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,person.statusCode());
            String personal=person.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
            assertEquals(403,send(server,"GET","/api/admin/world",personal,true,Map.of()).statusCode());
            var own=JSON.readTree(send(server,"GET","/api/me/view",personal,true,Map.of()).body());
            assertEquals("alice",own.path("personPartyId").asText());assertEquals("林知微",own.path("displayName").asText());
            assertFalse(own.has("experiment"));assertFalse(own.has("names"));
            assertEquals(200,send(server,"POST","/api/session/local",personal,true,Map.of()).statusCode());
        }
    }

    @Test void allTenStatementsBalanceAndSixtyPeopleHaveDistinctChineseNamesWithoutChangingWorld() throws Exception {
        try(var host=host()) {
            var view=JSON.valueToTree(host.worldOverview());
            assertTrue(view.path("available").asBoolean());assertEquals(10,view.path("companies").size());
            for(var company:view.path("companies")) {
                var s=company.path("statement");
                assertEquals(0,new BigDecimal(s.path("totalAssets").asText()).compareTo(new BigDecimal(s.path("totalLiabilities").asText()).add(new BigDecimal(s.path("totalEquity").asText()))));
                assertFalse(s.path("assets").isEmpty());
                assertTrue(company.path("lastPrice").isNull(),"没有成交不能用参考价填补");
            }
            Set<String> names=new HashSet<>();int people=0;
            for(var person:view.path("people")) {
                String name=person.path("name").asText();assertTrue(name.matches("[\\p{IsHan}]+"),person.toString());
                assertTrue(names.add(name),name);
                if(!person.path("system").asBoolean())people++;
            }
            assertEquals(60,people);
            assertEquals(view.path("position"),JSON.valueToTree(host.worldOverview()).path("position"),"查看不能写世界");
        }
    }

    private WorldWebHost host() {
        Path journal=dir.resolve(UUID.randomUUID()+".journal");
        return new WorldWebHost(()-> {var w=ComposedIndustryScenario.openPrototype(journal);return new WorldWebHost.Opened(w,w.profile().manifest().runId());});
    }

    @Test void connectedWebAdvanceReturnsLiveNewsAndChartsWithoutQuarterAttachment() throws Exception {
        try(var host=new WorldWebHost(()->{
            var w=ComposedIndustryScenario.openConnected(dir.resolve("connected-web.journal"));
            edu.cufe.auction.world.composed.ConnectedWorldBootstrap.grantBudgetAndAppoint(w,"mfg-tech",
                    w.boardOfCompany("mfg-tech").orElseThrow().board().charter().seats().stream().map(edu.cufe.auction.world.BoardModel.Seat::partyId).toList(),"mfgtech-mgr","web-open",List.of(0L));
            return new WorldWebHost.Opened(w,w.profile().manifest().runId());
        });var server=server(host)) {
            server.enableLocalAccess();server.start();var login=send(server,"POST","/api/session/local",null,true,Map.of());
            String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
            var before=JSON.readTree(send(server,"GET","/api/admin/world",cookie,true,Map.of()).body());
            var advance=HTTP.send(HttpRequest.newBuilder(URI.create(server.baseUrl()+"/api/admin/advance"))
                    .header("Origin",server.baseUrl()).header("X-World-Request","1").header("Cookie",cookie).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"expectedPosition\":\""+before.path("position").asText()+"\",\"targetMillis\":\"86400000\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,advance.statusCode(),advance.body());
            var after=JSON.readTree(send(server,"GET","/api/admin/world",cookie,true,Map.of()).body());
            assertTrue(after.path("connected").path("enabled").asBoolean());assertFalse(after.has("experiment"));
            assertFalse(after.path("connected").path("news").isEmpty());assertFalse(after.path("liveDetails").path("mfg-tech").path("days").isEmpty());
            assertTrue(after.path("connected").path("news").get(0).path("body").asText().contains("现金"));
            assertEquals("86400000",after.path("timeMillis").asText());
        }
    }
    private WorldWebServer server(WorldWebHost host) throws Exception {
        Path keys=dir.resolve("keys.txt");Files.writeString(keys,String.join("\n",List.of("sk-test-overview-0001","sk-test-overview-0002","sk-test-overview-0003","sk-test-overview-0004","sk-test-overview-0005")));
        return new WorldWebServer(host,KeySlots.load(keys),SceneRoutes.open(dir),0,"test-bootstrap",null,s->{});
    }
    private HttpResponse<String> send(WorldWebServer server,String method,String path,String cookie,boolean sameOrigin,Map<String,String> extra) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(server.baseUrl()+path));
        if(sameOrigin){builder.header("Origin",extra.getOrDefault("Origin",server.baseUrl()));builder.header("X-World-Request","1");}
        if(cookie!=null)builder.header("Cookie",cookie);
        extra.forEach((k,v)->{if(!k.equals("Origin"))builder.header(k,v);});
        if(method.equals("POST"))builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));else builder.GET();
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
}
