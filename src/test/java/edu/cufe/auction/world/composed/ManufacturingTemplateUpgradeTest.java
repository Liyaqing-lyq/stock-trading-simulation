package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static edu.cufe.auction.world.CompanyOperationsRun.Action.*;
import static edu.cufe.auction.world.ManufacturingUpgrade.*;
import static edu.cufe.auction.world.ManufacturingUpgrade.Stage.*;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

class ManufacturingTemplateUpgradeTest {
    @TempDir Path dir;
    private static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;

    private static Draft draft(String batch, String... ids) {
        return new Draft(batch, ORIGINAL, EXTENDED, List.of(ids));
    }
    private static Request req(WorldRuntime w, Stage stage, Draft draft) {
        return new Request(w.snapshot().position(), stage, draft);
    }
    private static CompanyOperationsRun ops(WorldRuntime w) { return w.operationsRun().orElseThrow(); }
    private static void schedule(WorldRuntime w, String id, CompanyOperationsRun.Action act, CompanyOperationsRun.Fields f) throws Exception {
        w.openRoleSession(TECH_MANAGER).operationsAction(id,id + "-task",w.snapshot().timeMillis() + 2000,TECH,act,f,1000);
    }
    private static void finish(WorldRuntime w, String id) throws Exception {
        w.advanceTo(w.snapshot().timeMillis() + 2000);
        var report = ops(w).report(id + "-task:finish");
        assertTrue(report.accepted(),report.toString());
    }
    private static ManufacturingTemplateModel.PurchaseOrder purchase(WorldRuntime w, String name) {
        return ops(w).company(TECH).ledger().purchases().get(name + ":po");
    }

    @Test void actualManagerChangesOnlyNewPurchaseDueDatesAndOutstandingObligationsSettleOnce() throws Exception {
        Path file = dir.resolve("purchases.journal");
        var pair = draft("supplier-2026", TECH, STRUCT);
        Request apply;
        long oldDue, newDue, appliedAt;
        BigDecimal cash, oldCost, newCost;
        WorldRuntime.Snapshot afterApply, afterSettlement;
        try (var w = ComposedIndustryScenario.openManufacturingUpgradeOperating(file)) {
            OperationsFixture.grantBudgetAndAppoint(w, TECH, TECH_DIRECTORS, TECH_MANAGER, "upgrade", List.of(0L));
            long now = w.snapshot().timeMillis();
            schedule(w,"signed",SIGN_CONTRACT,OperationsFixture.op().contract("sale","auto-oem","P1",1,1,
                    new BigDecimal("100.00"),now + DAY,now + 10*DAY).build());
            finish(w,"signed");
            schedule(w,"original",ORDER_MATERIAL,OperationsFixture.op().material("MAT-A",2).build());
            var pre = w.snapshot();
            var pending = w.manufacturingUpgrade(req(w,PREVIEW,pair));
            assertEquals("WAITING_SAFE_POINT", pending.phase()); assertEquals(List.of(TECH), pending.blockers());
            assertEquals(pre, w.snapshot());
            assertEquals("WAITING_SAFE_POINT", w.manufacturingUpgrade(req(w,PREPARE,pair)).phase());
            var before = w.snapshot(); long bytes = Files.size(file);
            assertThrows(IllegalArgumentException.class, () -> w.manufacturingUpgrade(req(w,APPLY,pair)));
            assertEquals(before, w.snapshot()); assertEquals(bytes,Files.size(file));
            assertEquals(ORIGINAL,templateVersion(ops(w),STRUCT)); assertEquals(ORIGINAL,templateVersion(ops(w),TECH));
        }
        try (var w = ComposedIndustryScenario.openManufacturingUpgradeOperating(file)) {
            assertEquals(pair,ops(w).upgradeBatches().get(pair.batchId()).draft());
            w.advanceTo(w.snapshot().timeMillis() + 1000);
            assertEquals(List.of(TECH),w.manufacturingUpgrade(req(w,PREVIEW,pair)).blockers());
            assertThrows(IllegalArgumentException.class, () -> w.manufacturingUpgrade(req(w,APPLY,pair)));
            w.advanceTo(w.snapshot().timeMillis() + 1000);
            assertTrue(ops(w).report("original-task:finish").accepted());
            var original = purchase(w,"original"); oldDue = original.arrivalAt(); oldCost = original.unitPrice().multiply(BigDecimal.valueOf(original.quantity()));
            assertEquals(DAY, oldDue - original.orderedAt());
            var before = w.snapshot(); var ledger = ops(w).company(TECH).ledger();
            assertEquals("READY", w.manufacturingUpgrade(req(w,PREVIEW,pair)).phase());
            var oldObservation=w.engineeringRoleInput(TECH_MANAGER).at("/companyOperations/0");
            assertEquals(ORIGINAL,oldObservation.path("templateVersion").asText());
            assertEquals(DAY,oldObservation.path("newProcurementLeadMillis").asLong());
            apply = req(w,APPLY,pair);
            var result = w.manufacturingUpgrade(apply); appliedAt = result.position();
            assertEquals("APPLIED",result.phase()); assertEquals(before.position()+1,appliedAt);
            assertEquals(EXTENDED,templateVersion(ops(w),STRUCT)); assertEquals(EXTENDED,templateVersion(ops(w),TECH));
            assertEquals(ORIGINAL,templateVersion(ops(w),VERSA)); assertEquals(ledger,ops(w).company(TECH).ledger());
            assertEquals(before.accounts(),w.snapshot().accounts()); assertEquals(before.pending(),w.snapshot().pending());
            assertTrue(w.operationsActions(TECH).contains("SIGN_CONTRACT"));
            var obs = w.engineeringRoleInput(TECH_MANAGER).at("/companyOperations/0");
            assertEquals(EXTENDED,obs.path("templateVersion").asText());
            assertEquals(2*DAY,obs.path("newProcurementLeadMillis").asLong());
            assertFalse(obs.path("visibleQuotes").isEmpty());
            assertEquals(2*DAY,obs.at("/visibleQuotes/0/leadTimeMillis").asLong());
            schedule(w,"next",ORDER_MATERIAL,OperationsFixture.op().material("MAT-A",1).build()); finish(w,"next");
            var next = purchase(w,"next"); newDue=next.arrivalAt(); newCost=next.unitPrice();
            assertEquals(2*DAY,newDue-next.orderedAt()); assertEquals(original,purchase(w,"original"));
            cash=w.snapshot().accounts().get(TECH_CASH).cash(); afterApply=w.snapshot();
        }
        try (var w = ComposedIndustryScenario.openManufacturingUpgradeOperating(file)) {
            assertEquals(afterApply,w.snapshot());
            var historic = w.historyFrame(appliedAt-1).snapshot().companyOperations().orElseThrow();
            assertEquals(ORIGINAL,templateVersion(historic,TECH));
            assertEquals(ORIGINAL,templateVersion(historic,STRUCT));
            assertEquals(EXTENDED,templateVersion(w.historyFrame(appliedAt).snapshot().companyOperations().orElseThrow(),TECH));
            assertEquals(appliedAt,w.manufacturingUpgrade(apply).position()); assertEquals(afterApply,w.snapshot());
            w.advanceTo(oldDue);
            assertEquals("RECEIVED",purchase(w,"original").status().name());
            assertEquals("ORDERED",purchase(w,"next").status().name());
            assertEquals(cash.subtract(oldCost),w.snapshot().accounts().get(TECH_CASH).cash());
            w.advanceTo(newDue);
            assertEquals("RECEIVED",purchase(w,"next").status().name());
            assertEquals(cash.subtract(oldCost).subtract(newCost),w.snapshot().accounts().get(TECH_CASH).cash());
            assertTrue(ComposedCompanyReport.of(w,TECH).balanced());
            assertEquals(appliedAt,w.manufacturingUpgrade(apply).position());
            afterSettlement=w.snapshot();
        }
        try (var w = ComposedIndustryScenario.openManufacturingUpgradeOperating(file)) { assertEquals(afterSettlement,w.snapshot()); }
    }

    @Test void onlyPendingDecisionsOfAffectedManagersBlockWhileIndependentTargetsMayProceed() throws Exception {
        try (var w=ComposedIndustryScenario.openManufacturingUpgradeOperating(dir.resolve("manager"))) {
            OperationsFixture.grantBudgetAndAppoint(w,TECH,TECH_DIRECTORS,TECH_MANAGER,"appointment",List.of(0L));
            PharmaDecisionToolTest.open(w,TECH_MANAGER,"procurement-observation");
            var both=draft("two",STRUCT,TECH);
            assertEquals(List.of(TECH),w.manufacturingUpgrade(req(w,PREPARE,both)).blockers());
            var before=w.snapshot();
            assertThrows(IllegalArgumentException.class,()->w.manufacturingUpgrade(req(w,APPLY,both)));
            assertEquals(before,w.snapshot());
            var other=draft("other",VERSA);
            assertEquals("READY",w.manufacturingUpgrade(req(w,PREPARE,other)).phase());
            assertEquals("APPLIED",w.manufacturingUpgrade(req(w,APPLY,other)).phase());
            assertEquals(ORIGINAL,templateVersion(ops(w),TECH)); assertEquals(ORIGINAL,templateVersion(ops(w),STRUCT));
        }
    }

    @Test void incompatibleBatchUnknownVersionAndWrongProfileCannotPartiallyChangeOtherCompany() throws Exception {
        try (var w = ComposedIndustryScenario.openManufacturingUpgradeOperating(dir.resolve("conflict"))) {
            var mixed=draft("mixed",STRUCT,TECH); var one=draft("first",TECH);
            assertThrows(IllegalArgumentException.class,()->new Draft("future",ORIGINAL,"future",List.of(TECH)));
            assertThrows(IllegalArgumentException.class,()->draft("repeat",TECH,TECH));
            assertThrows(IllegalArgumentException.class,()->w.manufacturingUpgrade(req(w,PREPARE,draft("missing",STRUCT,"absent"))));
            assertThrows(IllegalArgumentException.class,()->w.manufacturingUpgrade(req(w,APPLY,mixed)));
            w.manufacturingUpgrade(req(w,PREPARE,mixed)); w.manufacturingUpgrade(req(w,PREPARE,one));
            var stale=req(w,APPLY,mixed); w.manufacturingUpgrade(req(w,APPLY,one)); var before=w.snapshot();
            assertThrows(IllegalArgumentException.class,()->w.manufacturingUpgrade(stale));
            assertThrows(IllegalArgumentException.class,()->w.manufacturingUpgrade(req(w,APPLY,mixed)));
            assertThrows(IllegalArgumentException.class,()->w.manufacturingUpgrade(req(w,PREPARE,draft("first",STRUCT))));
            assertEquals(before,w.snapshot()); assertEquals(ORIGINAL,templateVersion(ops(w),STRUCT));
            assertEquals(0,ops(w).upgradeBatches().get("mixed").appliedPosition());
            assertFalse(w.profile().manifest().dataVersions().containsKey(OpenFundUnits.KEY));
        }
        try (var w = ComposedIndustryScenario.openTenCompanyAutonomy(dir.resolve("ordinary"))) {
            var before=w.snapshot();
            assertThrows(IllegalArgumentException.class,()->w.manufacturingUpgrade(req(w,PREVIEW,draft("no",TECH))));
            assertEquals(before,w.snapshot()); assertFalse(w.profile().manifest().dataVersions().containsKey(KEY));
        }
    }

    @Test void strictReplayRejectsPartialTargetListInAnOtherwiseValidBatch() throws Exception {
        Path original=dir.resolve("valid"), modified=dir.resolve("changed");
        var both=draft("pair",STRUCT,TECH);
        try (var w=ComposedIndustryScenario.openManufacturingUpgradeOperating(original)) {
            w.manufacturingUpgrade(req(w,PREPARE,both)); w.manufacturingUpgrade(req(w,APPLY,both));
        }
        try (var source=new WorldJournal(original,RunManifest.MANUFACTURING_UPGRADE_RUN_ID);
             var target=new WorldJournal(modified,RunManifest.MANUFACTURING_UPGRADE_RUN_ID)) {
            for (var frame:source.commits()) {
                var events=new ArrayList<>(frame.events());
                for (int i=0;i<events.size();i++) {
                    var event=events.get(i);
                    if ("TEMPLATE_APPLIED_V1".equals(event.type())) {
                        var json=new ObjectMapper();
                        var shrunk=new Request(frame.position()-1,APPLY,draft("pair",TECH));
                        events.set(i,new WorldEvent(event.eventId(),event.type(),json.writeValueAsString(shrunk)));
                    }
                }
                target.commit(frame.commandId(),frame.commandContent(),frame.result(),events);
            }
        }
        var error=assertThrows(java.io.IOException.class,()->ComposedIndustryScenario.openManufacturingUpgradeOperating(modified));
        assertTrue(error.getMessage().contains("模板升级"),error.getMessage());
    }

}
