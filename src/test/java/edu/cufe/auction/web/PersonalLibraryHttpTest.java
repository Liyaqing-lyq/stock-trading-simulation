package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonalLibraryHttpTest {
    @TempDir Path dir;
    private final AtomicReference<String> entry=new AtomicReference<>("personal-library-seed");
    private static final String LIB="/api/me/library?q=library-paper";

    private WorldWebServer server(WorldWebHost host,Path keys,SceneRoutes routes)throws Exception {
        return new WorldWebServer(host,KeySlots.load(keys),routes,0,entry.get(),null,line->{
            if(line.contains("引导凭据")) entry.set(line.substring(line.indexOf('：')+1).trim());
        });
    }

    private void login(String person)throws Exception {
        cookie=null;
        var admin=post("/api/session",JSON.createObjectNode().put("bootstrap",entry.get()).toString());
        remember(admin);
        assertEquals(403,get(LIB).statusCode());
        var login=post("/api/session/person",JSON.createObjectNode().put("personId",person).toString());
        remember(login);
    }

    private JsonNode page(String path)throws Exception{
        var response=get(path);
        assertEquals(200,response.statusCode(),response.body());
        return JSON.readTree(response.body());
    }

    private String read(String id)throws Exception{
        var me=page("/api/me/engineering");
        return JSON.createObjectNode().put("expectedPosition",me.required("observationToken").asText()).put("commandId","read-"+id)
                .put("taskId","read-"+id).put("dueTimeMillis",Long.toString(me.required("timeMillis").asLong()+1000))
                .put("docId",id).put("version","1").toString();
    }

    @Test void onlyTheAuthenticatedDirectoryCanBeBrowsedAndOnlyTheChildCanRead()throws Exception{
        Path journal=dir.resolve("world.journal"),keys=dir.resolve("keys");
        Files.write(keys,List.of("sk-library-demo-00001","sk-library-demo-00002","sk-library-demo-00003","sk-library-demo-00004","sk-library-demo-00005"));
        var repo=new BranchRepository(dir.resolve("runs"));
        String child="library-child";
        BranchProfile profile;
        try(var w=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(journal)){
            for(int i=1;i<=15;i++){
                String id=String.format(java.util.Locale.ROOT,"library-paper-%02d",i);long at=w.snapshot().timeMillis()+1;
                w.schedulePublishDocument(id,id,at,id,"rita",InformationModel.Audience.publicAudience(),id,"body-secret-"+i,"资料室",500,List.of());
                w.advanceTo(at);
            }
            long at=w.snapshot().timeMillis()+1;
            w.schedulePublishDocument("private","private",at,"library-paper-private","rita",InformationModel.Audience.direct("nina"),"私人附页","private-body","资料室",500,List.of());
            w.advanceTo(34_200_000L);
            w.createBranch(child,w.snapshot().position(),BranchOrigin.Kind.GOD_TAKEOVER,"查阅本人资料");profile=w.profile();
        }
        var original=Files.readAllBytes(journal);
        try(var routes=SceneRoutes.open(dir);var host=new WorldWebHost(()->{
            var w=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(journal);
            return new WorldWebHost.Opened(w,w.profile().manifest().runId());});var web=server(host,keys,routes)){
            web.start();base=web.baseUrl();cookie=null;assertEquals(401,get(LIB).statusCode());login("investor");
            var first=page(LIB);assertEquals(12,first.required("rows").size());assertFalse(first.toString().contains("body-secret"));
            var tail=page(LIB+"&token="+first.required("token").asText()+"&after="+first.required("next").asText());
            assertEquals(3,tail.required("rows").size());assertFalse(tail.toString().contains("library-paper-private"));
            assertEquals(400,get(LIB+"&personId=nina").statusCode());
            assertEquals(403,post("/api/me/read",read("library-paper-01")).statusCode());
            login("nina");
            assertEquals(400,get(LIB+"&token="+first.required("token").asText()).statusCode());
            var mine=page("/api/me/library?q=library-paper-private");
            assertEquals(1,mine.required("rows").size());assertFalse(mine.toString().contains("private-body"));
        } finally {cookie=null;}
        try(var routes=SceneRoutes.open(dir);var host=new WorldWebHost(()->new WorldWebHost.Opened(repo.open(child,profile),child));
            var web=server(host,keys,routes)){
            web.start();base=web.baseUrl();login("investor");
            assertTrue(page("/api/me/library?q=body-secret-1").required("rows").isEmpty());
            assertEquals(400,post("/api/me/read",read("library-paper-private")).statusCode());
            var accepted=post("/api/me/read",read("library-paper-01"));assertEquals(200,accepted.statusCode(),accepted.body());
            host.advance(host.adminSnapshot().position(),34_201_000L);
            long due=host.roleView("investor").readings().get(0).dueAt();
            var reading=page("/api/me/library?q=library-paper-01").at("/rows/0");
            assertEquals(due,reading.required("readingDueMillis").asLong());assertFalse(reading.has("body"));
            host.advance(host.adminSnapshot().position(),due-1);assertTrue(page("/api/me/library?q=body-secret-1").required("rows").isEmpty());
            host.advance(host.adminSnapshot().position(),due);
            var done=page("/api/me/library?q=body-secret-1").at("/rows/0");
            assertEquals("body-secret-1",done.required("body").asText());assertEquals(due,done.required("completedAtMillis").asLong());
        } finally {cookie=null;}
        assertArrayEquals(original,Files.readAllBytes(journal));
    }
}
