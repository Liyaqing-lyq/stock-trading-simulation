package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class CatalogDiscoveryTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WHO = "investor";
    private static final String QUERY = "预算阅览";
    private static final long OPEN = 34_200_000L;

    static void publish(WorldRuntime world, String id, InformationModel.Audience audience, String title) throws Exception {
        long at = world.snapshot().timeMillis()+1;
        world.schedulePublishDocument("p-"+id,"t-"+id,at,id,"rita",audience,title,
                "已归档的核对正文 凭证编号Z471","资料室",500,List.of());
        world.advanceTo(at);
    }

    private static void fixtures(WorldRuntime world) throws Exception {
        for (int i=1; i<=27; i++) {
            String id=String.format(java.util.Locale.ROOT,"study-%03d",i);
            publish(world,id,InformationModel.Audience.publicAudience(),QUERY+" 第"+i+"期");
        }
        publish(world,"study-private",InformationModel.Audience.direct("nina"),QUERY+" 私有文号");
        publish(world,"study-long",InformationModel.Audience.publicAudience(),QUERY+"题".repeat(92_000));
        world.advanceTo(OPEN);
    }

    private static JsonNode decide(WorldRuntime world,String action,String tool,Map<String,?> args) throws Exception {
        var call=PharmaDecisionToolTest.open(world,WHO,action);
        var obs=JSON.readTree(call.inputText());
        var reply=JSON.createObjectNode().put("tool",tool);
        reply.set("args",JSON.valueToTree(args));
        reply.putObject("decision").put("objective",obs.at("/engineering/deliberation/objectives/0/id").asText())
                .put("basis","核对个人可用的资料入口。").put("reviewAfterMillis",60000);
        assertEquals("QUEUED",world.offerDecisionCallback(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),
                call.attemptNo(),call.recoveryGeneration(),reply.toString(),DecisionModel.Usage.unknownCost(1,80,30),"fixture")));
        assertEquals(1,world.drainDecisionCallbacks());
        var scheduled=world.snapshot().decisions().orElseThrow().actionRef(action);
        if (scheduled != null) world.advanceTo(scheduled.dueMillis());
        return world.autonomyRoleObservation(WHO);
    }

    private static JsonNode search(WorldRuntime world,String action,String query,String cursor) throws Exception {
        Map<String,String> args=cursor==null ? Map.of("query",query) : Map.of("query",query,"cursor",cursor);
        return decide(world,action,"role.library.search",args).at("/engineering/librarySearch/lastResult");
    }

    @Test void originalCatalogStillTriggersItsFrozenLimitAndFullPayloadIsUnchangedOnReopen() throws Exception {
        Path path=dir.resolve("previous.journal");
        String previous;
        try(var world=ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(path)) {
            fixtures(world);
            assertTrue(world.autonomyInputLimitViolation(WHO).isPresent());
            previous=world.autonomyRoleObservation(WHO).toString();
            assertFalse(previous.contains("catalogPolicy"));
        }
        byte[] bytes=Files.readAllBytes(path);
        try(var world=ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(path)) {
            assertEquals(previous,world.autonomyRoleObservation(WHO).toString());
        }
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }

    @Test void normalDecisionsPageTheOriginalAuthorizedCatalogAndOnlyActualReadingGrantsEvidence() throws Exception {
        Path path=dir.resolve("new.journal");
        long firstPosition;
        String firstCursor;
        var ids=new ArrayList<String>();
        try(var world=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(path)) {
            fixtures(world);
            assertTrue(world.autonomyInputLimitViolation(WHO).isEmpty());
            var ready=world.autonomyRoleObservation(WHO);
            assertEquals(24,ready.at("/profile/catalog").size());
            assertTrue(ready.at("/engineering/librarySearch/totalVisible").asInt()>24);
            assertFalse(ready.toString().contains("study-private"));
            var blank=search(world,"browse","","cursor-from-other-person");
            assertTrue(blank.isMissingNode()); // invalid cursor never executes a search
            var page=search(world,"search-first",QUERY,null);
            assertEquals(12,page.required("matches").size());
            firstPosition=page.required("anchorPosition").longValue();
            firstCursor=page.required("nextCursor").asText();
            page.required("matches").forEach(hit -> ids.add(hit.required("docId").asText()));
            assertEquals("study-001",ids.get(0));
            assertFalse(world.canCite(WHO,"study-001",1));
            publish(world,"study-015-extra",InformationModel.Audience.publicAudience(),QUERY+" 插入的新材料");
        }
        try(var world=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(path)) {
            var middle=search(world,"search-next",QUERY,firstCursor);
            assertEquals(firstPosition,middle.required("anchorPosition").longValue());
            middle.required("matches").forEach(hit -> ids.add(hit.required("docId").asText()));
            var end=search(world,"search-end",QUERY,middle.required("nextCursor").asText());
            assertEquals(firstPosition,end.required("anchorPosition").longValue());
            assertFalse(end.has("nextCursor"));
            end.required("matches").forEach(hit -> ids.add(hit.required("docId").asText()));
            assertEquals(28,ids.size());assertEquals(ids,new ArrayList<>(new TreeSet<>(ids)));
            assertFalse(ids.contains("study-private"));assertFalse(ids.contains("study-015-extra"));
            assertEquals("study-long",ids.get(ids.size()-1));
            assertTrue(end.toString().length()<2000);
            assertEquals(0,search(world,"no-unread-body","Z471",null).required("matches").size());
            assertTrue(search(world,"new-index","study-015-extra",null).toString().contains("study-015-extra"));
            assertEquals(12,search(world,"browse-all","",null).required("matches").size());
            decide(world,"read-first","role.reading.start",Map.of("docId","study-001","version",1));
            world.advanceTo(world.roleView(WHO).readings().get(0).dueAt());
            assertTrue(world.canCite(WHO,"study-001",1));
            var own=search(world,"own-only","Z471",null).required("matches");
            assertEquals(1,own.size());assertEquals("OWN_READING",own.get(0).required("matchedIn").asText());
            assertFalse(own.toString().contains("Z471"));
        }
        byte[] bytes=Files.readAllBytes(path);
        try(var world=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(path)) {
            assertEquals("study-001",world.autonomyRoleObservation(WHO).at("/engineering/librarySearch/lastResult/matches/0/docId").asText());
        }
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }

    @Test void aRevokedOfficeCannotRetrieveItsTitlesFromAnEarlierPage() throws Exception {
        try (var world=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(dir.resolve("revoked.journal"))) {
            for (int n=1;n<=15;n++) {
                String id=String.format(java.util.Locale.ROOT,"office-%02d",n);
                publish(world,id,InformationModel.Audience.organization("markets-desk"),"部门保管的附件 "+n);
            }
            world.advanceTo(OPEN);
            var first=search(world,"office-search","部门保管",null);
            assertEquals(12,first.required("matches").size());
            String cursor=first.required("nextCursor").asText();
            String appointment=world.roleView(WHO).appointments().stream()
                    .filter(a -> a.organizationPartyId().equals("markets-desk")).findFirst().orElseThrow().appointmentId();
            long now=world.snapshot().timeMillis()+1;
            world.scheduleRevokeAppointment("revoke-office","finish-revoke-office",now,appointment);
            world.advanceTo(now);
            var prior=world.autonomyRoleObservation(WHO).at("/engineering/librarySearch/lastResult");
            assertEquals(0,prior.required("matches").size());
            assertEquals(0,search(world,"office-next","部门保管",cursor).required("matches").size());
            assertFalse(world.canReadDocumentVersion(WHO,"office-15",1));
        }
    }
}
