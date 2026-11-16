package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static edu.cufe.auction.world.composed.HydroIndustryConfig.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实管理动作、拒绝与物理结算产生窗口；不删账或注入报告。 */
class BoundedOperatingReportsTest {
    @TempDir Path dir;
    private JsonNode hydro(WorldRuntime w) throws Exception { return w.autonomyRoleObservation(MANAGER).path("engineering").path("hydroOperations"); }
    private void act(WorldRuntime w, String id, HydroOperations.Action action, Map<String,String> args, boolean accepted) throws Exception {
        long due=w.snapshot().timeMillis()+HydroDecisionTools.ACTION_MILLIS+1000;
        w.openRoleSession(MANAGER).hydroAction(id,id+"-task",due,COMPANY,new HydroOperations.Request(action,args),HydroDecisionTools.ACTION_MILLIS);
        w.advanceTo(due);
        var row=w.operationsRun().orElseThrow().report(id+"-task:finish");
        assertNotNull(row); assertEquals(accepted,row.accepted(),row.reason());
    }
    @Test void realHydroHistoryHasExactBoundedUnionWithoutChangingBooksAndReopensStrictly() throws Exception {
        Path file=dir.resolve("history.journal");String input;WorldRuntime.Snapshot snapshot;
        try(var w=ComposedIndustryScenario.openInformedOperating(file)) {
            OperationsFixture.grantBudgetAndAppoint(w,COMPANY,DIRECTORS,MANAGER,"history",List.of(0L));
            for(int i=0;i<7;i++) act(w,"reject-"+i,HydroOperations.Action.COLLECT,Map.of("amount","999999.00"),false);
            for(int i=0;i<7;i++) act(w,"dispatch-"+i,HydroOperations.Action.DISPATCH_FLOW,Map.of("cubicMetresPerHour","600"),true);
            for(int i=0;i<65;i++) w.advanceTo(w.snapshot().timeMillis()+HydroModel.PHYSICS_TICK_MILLIS);
            var before=w.snapshot(); long journalSize=Files.size(file); var run=w.operationsRun().orElseThrow();
            var full=run.reports().values().stream().filter(r->r.companyPartyId().equals(COMPANY)).sorted(Comparator.comparingLong(CompanyOperationsRun.ReportEntry::position)).toList();
            assertTrue(full.size()>65);var v=hydro(w);var rows=v.path("reports");
            assertEquals(24,rows.size(),"real full history must be projected into at most 24 relevant rows");
            var ids=new LinkedHashSet<String>(); long position=-1;
            for(var r:rows) {assertTrue(ids.add(r.path("taskId").asText()));assertTrue(r.path("position").asLong()>position);position=r.path("position").asLong();assertEquals(COMPANY,r.path("companyPartyId").asText());}
            for(int i=1;i<7;i++) {assertTrue(ids.contains("reject-"+i+"-task:finish"));assertTrue(ids.contains("dispatch-"+i+"-task:finish"));}
            assertFalse(ids.contains("reject-0-task:finish"));assertFalse(ids.contains("dispatch-0-task:finish"));
            for(var r:full.subList(full.size()-12,full.size())) assertTrue(ids.contains(r.taskId()));
            var meta=v.path("reportsHistory");assertEquals("bounded-history-v1",meta.path("policy").asText());
            assertEquals(full.size(),meta.path("fullTotal").asLong());assertEquals(24,meta.path("shown").asLong());assertEquals(full.size()-24,meta.path("omitted").asLong());
            assertEquals(rows.get(0).path("position"),meta.path("retainedMinPosition"));assertEquals(rows.get(23).path("position"),meta.path("retainedMaxPosition"));
            assertEquals(rows.get(0).path("timeMillis"),meta.path("retainedMinTimeMillis"));assertEquals(rows.get(23).path("timeMillis"),meta.path("retainedMaxTimeMillis"));
            assertTrue(w.autonomyInputLimitViolation(MANAGER).isEmpty(), "bounded real report history must fit the unchanged input limit");
            assertFalse(meta.has("amount"));assertTrue(meta.path("scope").asText().contains("遗漏"));
            assertEquals(w.snapshot().accounts().get(run.hydro().config().model().companyAccountId()).cash().toPlainString(),v.path("cash").asText());
            assertEquals(run.hydro().ledger().watermark(),v.path("physicalAsOf").asLong());
            assertEquals(before,w.snapshot());assertEquals(run,w.operationsRun().orElseThrow());assertEquals(journalSize,Files.size(file));
            assertTrue(w.autonomyRoleObservation(DIRECTORS.get(0)).path("engineering").path("hydroOperations").isMissingNode());
            assertTrue(w.autonomyRoleObservation(ComposedManufacturingThreeScenario.TECH_MANAGER).path("engineering").path("hydroOperations").isMissingNode());
            snapshot=w.snapshot();input=w.autonomyRoleObservation(MANAGER).toString();
        }
        byte[] bytes=Files.readAllBytes(file);
        try(var w=ComposedIndustryScenario.openInformedOperating(file)){assertEquals(snapshot,w.snapshot());assertEquals(input,w.autonomyRoleObservation(MANAGER).toString());}
        assertArrayEquals(bytes,Files.readAllBytes(file));
    }

    @Test void allFiveManagerViewsUseScopedWindowsAndManufacturingKeepsCurrentOffers() throws Exception {
        try(var w=ComposedIndustryScenario.openInformedOperating(dir.resolve("scopes.journal"))) {
            var companies=List.of(ComposedManufacturingThreeScenario.TECH,ComposedIndustryScenario.RESOURCE,DigitalIndustryConfig.COMPANY,AgedIndustryConfig.COMPANY,COMPANY);
            var people=List.of(ComposedManufacturingThreeScenario.TECH_MANAGER,ComposedIndustryScenario.MANAGER,DigitalIndustryConfig.MANAGER_PERSON,AgedIndustryConfig.MANAGER_PERSON,MANAGER);
            var fields=List.of("companyOperations","resourceOperations","digitalOperations","agedOperations","hydroOperations");
            var directors=List.of(ComposedManufacturingThreeScenario.TECH_DIRECTORS,ComposedIndustryScenario.DIRECTORS,BaselineIndustryConfig.directors(DigitalIndustryConfig.COMPANY),BaselineIndustryConfig.directors(AgedIndustryConfig.COMPANY),DIRECTORS);
            for(int i=0;i<companies.size();i++) OperationsFixture.grantBudgetAndAppoint(w,companies.get(i),directors.get(i),people.get(i),"scope-"+i,List.of(0L));
            for(int i=0;i<companies.size();i++) {
                var eng=w.autonomyRoleObservation(people.get(i)).path("engineering");var view=eng.path(fields.get(i));if(i==0)view=view.get(0);
                assertEquals(companies.get(i),view.path("companyId").asText());var meta=view.path("reportsHistory");
                String expectedCompany=companies.get(i);
                long full=w.operationsRun().orElseThrow().reports().values().stream().filter(r->r.companyPartyId().equals(expectedCompany)).count();
                assertEquals(full,meta.path("fullTotal").asLong(-1));assertEquals(view.path("reports").size(),meta.path("shown").asInt(-1));
                if(full==0)assertTrue(meta.path("retainedMinPosition").isNull());
                for(var row:view.path("reports"))assertEquals(companies.get(i),row.path("companyPartyId").asText());
                for(int j=0;j<fields.size();j++) if(j!=i)assertTrue(eng.path(fields.get(j)).isMissingNode()||eng.path(fields.get(j)).isEmpty());
                if(i==0){assertFalse(view.path("customerOffers").isEmpty());assertTrue(view.path("products").get(0).has("cycleMillis"));}
            }
        }
    }

    @Test void policiesRejectMissingWrongOrLegacyPairings() throws Exception {
        try(var w=ComposedIndustryScenario.openInformedOperating(dir.resolve("flags.journal"))) {
            var m=w.profile().manifest();assertEquals("bounded-history-v1",m.dataVersions().get("role-input-policy"));assertEquals("local-input-v1",m.dataVersions().get("dispatch-policy"));
            for(String key:List.of("role-input-policy","dispatch-policy","operating-report-order","operating-information","role-context","state-storage","external-demand")) {
                var d=new TreeMap<>(m.dataVersions());d.remove(key);assertThrows(IllegalArgumentException.class,()->new RunManifest(m.runId(),m.seed(),m.rulesVersion(),m.moduleVersions(),d,m.modelConfigVersion()),key);
                d.put(key,"unknown");assertThrows(IllegalArgumentException.class,()->new RunManifest(m.runId(),m.seed(),m.rulesVersion(),m.moduleVersions(),d,m.modelConfigVersion()),key);
            }
        }
        try(var w=ComposedIndustryScenario.openPlanningOperating(dir.resolve("oldflags.journal"))) {
            assertFalse(w.profile().manifest().dataVersions().containsKey("role-input-policy"));assertFalse(w.profile().manifest().dataVersions().containsKey("dispatch-policy"));
        }
        assertThrows(IllegalArgumentException.class,()->new RunManifest("old",1,RunManifest.RULES_CASH_WORLD_V1,Map.of("cash",1,"scheduler",1),Map.of("role-input-policy","bounded-history-v1","dispatch-policy","local-input-v1"),RunManifest.MODEL_NONE));
    }

    @Test void readonlyPreflightSharesExactInputLimitsWithOpenAndChangesNoState() throws Exception {
        Path file=dir.resolve("preflight.journal");
        try(var w=ComposedIndustryScenario.openInformedOperating(file)) {
            String person=ComposedManufacturingThreeScenario.TECH_DIRECTORS.get(1);
            w.schedulePublishDocument("large","large-task",3,"oversize","alice",InformationModel.Audience.direct(person),"超".repeat(40000),"短","fixture",10,List.of());w.advanceTo(4);
            w.beginDecisionSession("test-preflight");var before=w.snapshot();long bytes=Files.size(file);
            var free=w.autonomyInputLimitViolation(person).orElseThrow();
            var actual=assertThrows(WorldRuntime.DecisionInputTooLargeException.class,()->w.openDecisions("open-too-large",List.of(new DecisionRun.Request(person,"too-large","wake",new DecisionModel.Route("governance","key-1","deepseek-flash",1,20,1000000,1024,1)))));
            assertEquals(actual.actualChars(),free.actualChars());assertEquals(actual.actualBytes(),free.actualBytes());assertEquals(actual.maxChars(),free.maxChars());assertEquals(actual.maxBytes(),free.maxBytes());
            assertEquals(before,w.snapshot());assertEquals(bytes,Files.size(file));assertTrue(w.autonomyInputLimitViolation(ComposedManufacturingThreeScenario.STRUCT_DIRECTORS.get(0)).isEmpty());
        }
    }
}
