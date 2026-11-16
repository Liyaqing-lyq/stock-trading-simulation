package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static edu.cufe.auction.world.CompanyOperationsRun.Action.*;
import static org.junit.jupiter.api.Assertions.*;

/** 当前经理信息合同；真实任职、阅读、预算和经营事件，不调用模型。 */
class ManagerOperatingInformationTest {
    @TempDir Path dir;
    static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;
    // 独立新工厂显式开启当前信息；旧v4合同另有冻结断言。
    private WorldRuntime informed(Path file) throws Exception { return ComposedIndustryScenario.openInformedOperating(file); }
    private void appoint(WorldRuntime w) throws Exception {
        OperationsFixture.grantBudgetAndAppoint(w, TECH, TECH_DIRECTORS, TECH_MANAGER, "manager-info", List.of(0L, 1L));
    }
    private JsonNode view(WorldRuntime w) throws Exception {
        return w.autonomyRoleObservation(TECH_MANAGER).path("engineering").path("companyOperations").get(0);
    }
    private ManufacturingTemplateModel.State ledger(WorldRuntime w) { return w.operationsRun().orElseThrow().company(TECH).ledger(); }
    private JsonNode offer(JsonNode view) { for(var o:view.path("customerOffers")) if(o.path("customerId").asText().equals("auto-oem")) return o; throw new AssertionError("missing current auto-oem offer"); }
    private BigDecimal total(WorldRuntime w) { return w.snapshot().accounts().values().stream().map(CashLedger.CashAccount::cash).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private String hash(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    private void act(WorldRuntime w, String id, CompanyOperationsRun.Action action, CompanyOperationsRun.Fields fields) throws Exception {
        long due=w.snapshot().timeMillis()+2000;
        w.scheduleOperationsAction(id,id+"-task",due,TECH,TECH_MANAGER,action.name(),fields,1000);
        w.advanceTo(due);
        var report=w.operationsRun().orElseThrow().report(id+"-task:finish");
        assertNotNull(report,id); assertTrue(report.accepted(),report.reason());
    }

    @Test void freezesLegacyV4ManagerInputBeforeProjectionChangeAndReopensWithoutWrites() throws Exception {
        Path journal=dir.resolve("legacy.journal"); String input; WorldRuntime.Snapshot snapshot;
        try(var w=ComposedIndustryScenario.openPlanningOperating(journal)) {
            appoint(w); input=w.autonomyRoleObservation(TECH_MANAGER).toString(); snapshot=w.snapshot();
            assertEquals("4b6223ac6faa631107f02e28726356baf7c62659b431026c2141dfbbb10879ec",hash(input));
            assertFalse(view(w).has("customerOffers"));
            assertFalse(view(w).path("products").get(0).has("cycleMillis"));
        }
        byte[] bytes=Files.readAllBytes(journal);
        try(var w=ComposedIndustryScenario.openPlanningOperating(journal)) {
            assertEquals(input,w.autonomyRoleObservation(TECH_MANAGER).toString()); assertEquals(snapshot,w.snapshot());
        }
        assertThrows(Exception.class,()->informed(journal));
        assertArrayEquals(bytes,Files.readAllBytes(journal));
    }

    @Test void activeManagerGetsCurrentFiniteOffersPublicTimingAndOwnOpeningDocumentOnly() throws Exception {
        try(var w=informed(dir.resolve("current.journal"))) {
            assertTrue(w.autonomyRoleObservation(TECH_MANAGER).path("engineering").path("companyOperations").isEmpty());
            appoint(w); var v=view(w); var spec=ledger(w).spec();
            assertTrue(v.has("customerOffers"),"Actual v4 appointed manager lacks current customerOffers");
            assertEquals("manager-v1",v.path("operatingInformationVersion").asText());
            assertEquals(w.snapshot().timeMillis(),v.path("asOfMillis").asLong(-1));
            assertEquals(spec.periodExpense().toPlainString(),v.path("periodExpense").asText());
            assertEquals(spec.depreciationPerPeriod().toPlainString(),v.path("depreciationPerPeriod").asText());
            for(var p:v.path("products")) assertEquals(spec.product(p.path("productId").asText(),p.path("generation").asInt()).cycleMillis(),p.path("cycleMillis").asLong(-1));
            for(var q:v.path("visibleQuotes")) { assertTrue(q.path("remainingSupply").asLong(-1)>=0); assertEquals(DAY,q.path("leadTimeMillis").asLong(-1)); }
            var o=offer(v); assertTrue(o.path("quantity").asLong()>0);
            assertEquals(spec.priceCap().toPlainString(),o.path("unitPrice").asText());
            assertEquals(spec.priceCap().multiply(BigDecimal.valueOf(o.path("quantity").asLong())).toPlainString(),o.path("amount").asText());
            assertEquals(15*DAY,v.path("contractTerms").path("paymentDueAfterDeliveryMillis").asLong(-1));
            assertEquals("UNKNOWN",v.path("contractTerms").path("paymentOutcome").asText());
            assertEquals("UNKNOWN",v.path("contractTerms").path("futureCertificationOutcome").asText());
            var manager=w.autonomyRoleObservation(TECH_MANAGER).path("engineering").path("managerships").get(0);
            assertEquals(RoleEvidenceDocuments.documentId(TECH),manager.path("decisionMaterialDocId").asText());
            assertEquals(1,manager.path("decisionMaterialVersion").asLong()); assertEquals(0,manager.path("decisionMaterialAsOfMillis").asLong(-1));
            assertTrue(manager.path("decisionMaterialScope").asText().contains("开局"));
            assertFalse(InformationModel.observations(w.snapshot().information().orElseThrow().information(),TECH_MANAGER).stream().anyMatch(r->r.docId().equals(RoleEvidenceDocuments.documentId(TECH))),"pointer must not read document automatically");
            String input=w.autonomyRoleObservation(TECH_MANAGER).toString();
            for(String secret:List.of("budgetPerPeriod","customerAccounts","CUST-AUTO","EXT-FINAL-DEMAND","certPlans","residualRate","role-evidence:"+STRUCT,"role-evidence:pharma")) assertFalse(input.contains(secret),secret);
            assertTrue(w.autonomyRoleObservation(TECH_DIRECTORS.get(0)).path("engineering").path("companyOperations").isEmpty());
            assertTrue(w.autonomyRoleObservation(STRUCT_MANAGER).path("engineering").path("companyOperations").isEmpty());
            assertFalse(w.profile().manifest().dataVersions().containsKey("behavior-policy"));
            OperationsFixture.grantBudgetAndAppoint(w,STRUCT,STRUCT_DIRECTORS,STRUCT_MANAGER,"other-manager",List.of(0L));
            var other=w.autonomyRoleObservation(STRUCT_MANAGER).path("engineering").path("companyOperations");
            assertEquals(1,other.size()); assertEquals(STRUCT,other.get(0).path("companyId").asText());
            assertFalse(other.toString().contains("auto-oem"));
            var own=w.autonomyRoleObservation(TECH_MANAGER).path("engineering").path("companyOperations");
            assertEquals(1,own.size()); assertFalse(own.toString().contains("builder-a"));
        }
    }

    @Test void offersAndTimingFollowRealCommitmentsDeliveryPaymentAndPeriodTransition() throws Exception {
        Path journal=dir.resolve("lifecycle.journal"); String input; WorldRuntime.Snapshot snapshot;
        try(var w=informed(journal)) {
            appoint(w); BigDecimal openingTotal=total(w); var before=view(w); long quantity=offer(before).path("quantity").asLong();
            long supply=before.path("visibleQuotes").get(0).path("remainingSupply").asLong();
            act(w,"order",ORDER_MATERIAL,OperationsFixture.op().material("MAT-A",40).build());
            var po=ledger(w).purchases().get("order:po");
            assertEquals(before.path("visibleQuotes").get(0).path("leadTimeMillis").asLong(),po.arrivalAt()-po.orderedAt());
            assertEquals(supply-40,view(w).path("visibleQuotes").get(0).path("remainingSupply").asLong());
            assertTrue(w.snapshot().pending().stream().anyMatch(t->t.dueTimeMillis()==po.arrivalAt()));
            w.advanceTo(po.arrivalAt()); assertEquals(ManufacturingTemplateModel.PurchaseStatus.RECEIVED,ledger(w).purchases().get("order:po").status());
            act(w,"batch",START_BATCH,OperationsFixture.op().product("P1",1,20).build());
            var batch=ledger(w).batches().get("batch:batch");
            assertEquals(ledger(w).spec().product("P1",1).cycleMillis(),batch.completesAt()-batch.startedAt());
            assertTrue(w.snapshot().pending().stream().anyMatch(t->t.dueTimeMillis()==batch.completesAt()));
            w.advanceTo(batch.completesAt());
            long promised=w.snapshot().timeMillis()+DAY,expires=promised+10*DAY;
            act(w,"sign",SIGN_CONTRACT,OperationsFixture.op().contract("sale","auto-oem","P1",1,10,ledger(w).spec().priceCap(),promised,expires).build());
            assertEquals(quantity-10,offer(view(w)).path("quantity").asLong());
            String doc=RoleEvidenceDocuments.documentId(TECH);
            long readAt=w.snapshot().timeMillis()+1;
            w.scheduleStartReading("read-own","read-own-task",readAt,TECH_MANAGER,doc,1);
            w.advanceTo(readAt+10_000);
            var read=InformationModel.observations(w.snapshot().information().orElseThrow().information(),TECH_MANAGER).stream().filter(r->r.docId().equals(doc)).findFirst().orElseThrow();
            assertNotNull(read.readId());
            var experiences=w.autonomyRoleObservation(TECH_MANAGER).path("profile").path("readExperiences");
            JsonNode opening=null;
            for(var e:experiences) if(e.path("docId").asText().equals(doc)) opening=new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.path("body").asText());
            assertNotNull(opening); assertEquals(0,opening.path("asOfMillis").asLong(-1));
            assertEquals(quantity,offer(opening.path("openingOperations")).path("quantity").asLong());
            assertEquals(quantity-10,offer(view(w)).path("quantity").asLong());
            assertEquals(promised,view(w).path("contracts").path("sale").path("promisedAt").asLong());
            assertEquals(expires,view(w).path("contracts").path("sale").path("expiresAt").asLong());
            w.advanceTo(promised); act(w,"delivery",DELIVER,OperationsFixture.op().delivery("sale",10,"ar").build());
            var ar=ledger(w).receivables().get("ar");
            assertEquals(view(w).path("contractTerms").path("paymentDueAfterDeliveryMillis").asLong(),ar.dueAt()-ar.recognizedAt());
            assertEquals(quantity-10,offer(view(w)).path("quantity").asLong());
            assertEquals(ar.dueAt(),view(w).path("receivables").path("ar").path("dueAt").asLong());
            w.advanceTo(ar.dueAt()); BigDecimal cash=w.snapshot().accounts().get(TECH_CASH).cash();
            act(w,"collect",COLLECT,OperationsFixture.op().receivable("ar").build());
            assertEquals(cash.add(ar.amount()),w.snapshot().accounts().get(TECH_CASH).cash());
            assertEquals("COLLECTED",view(w).path("receivables").path("ar").path("status").asText());
            assertEquals(quantity-10,offer(view(w)).path("quantity").asLong());
            w.advanceTo(ManufacturingTemplateModel.PERIOD_MILLIS+1);
            assertEquals(quantity,offer(view(w)).path("quantity").asLong());
            assertEquals(supply,view(w).path("visibleQuotes").get(0).path("remainingSupply").asLong());
            assertEquals(openingTotal,total(w));
            input=w.autonomyRoleObservation(TECH_MANAGER).toString(); snapshot=w.snapshot();
        }
        byte[] bytes=Files.readAllBytes(journal);
        try(var w=informed(journal)) { assertEquals(snapshot,w.snapshot()); assertEquals(input,w.autonomyRoleObservation(TECH_MANAGER).toString()); }
        assertThrows(Exception.class,()->ComposedIndustryScenario.openPlanningOperating(journal));
        assertArrayEquals(bytes,Files.readAllBytes(journal));
    }

    @Test void manifestRequiresExplicitCompatiblePairingAndRejectsMalformedVersions() throws Exception {
        try(var w=informed(dir.resolve("manifest.journal"))) {
            var m=w.profile().manifest(); assertEquals("manager-v1",m.dataVersions().get("operating-information"));
            assertEquals("web-informed-operating-v1",m.runId());
            assertEquals("deliberation-v4",m.dataVersions().get("role-context")); assertEquals("delta-v1",m.dataVersions().get("state-storage"));
            assertEquals("v1",m.dataVersions().get("external-demand"));
            for(String key:List.of("operating-information","role-context","state-storage","external-demand","autonomy-driver","profile")) {
                var data=new TreeMap<>(m.dataVersions()); data.put(key,"invalid"); assertThrows(IllegalArgumentException.class,()->new RunManifest(m.runId(),m.seed(),m.rulesVersion(),m.moduleVersions(),data,m.modelConfigVersion()),key);
                if(!key.equals("operating-information")) { data.remove(key); assertThrows(IllegalArgumentException.class,()->new RunManifest(m.runId(),m.seed(),m.rulesVersion(),m.moduleVersions(),data,m.modelConfigVersion()),key); }
            }
            var v3=new TreeMap<>(m.dataVersions());v3.put("role-context","deliberation-v3");assertThrows(IllegalArgumentException.class,()->new RunManifest(m.runId(),m.seed(),m.rulesVersion(),m.moduleVersions(),v3,m.modelConfigVersion()));
            var script=new TreeMap<>(m.dataVersions());script.put("behavior-policy",ManufacturingBehaviorPolicy.VERSION);assertThrows(IllegalArgumentException.class,()->new RunManifest(m.runId(),m.seed(),m.rulesVersion(),m.moduleVersions(),script,m.modelConfigVersion()));
        }
        assertThrows(IllegalArgumentException.class,()->new RunManifest("cash",1,RunManifest.RULES_CASH_WORLD_V1,Map.of("cash",1,"scheduler",1),Map.of("operating-information","manager-v1"),RunManifest.MODEL_NONE));
    }
}
