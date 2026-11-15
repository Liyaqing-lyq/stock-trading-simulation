package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import static edu.cufe.auction.world.composed.HydroIndustryConfig.*;
import static org.junit.jupiter.api.Assertions.*;

class HydroIndustryWorldTest {
    @TempDir Path dir;
    static final long HOUR=3_600_000L;
    static HydroModel.Report report(WorldRuntime world) {
        return HydroModel.report(world.operationsRun().orElseThrow().hydro().ledger(),world.snapshot().accounts());
    }
    static void act(WorldRuntime w,String id,HydroOperations.Request request) throws Exception {
        long due=w.snapshot().timeMillis()+HydroDecisionTools.ACTION_MILLIS+1000;
        w.openRoleSession(MANAGER).hydroAction(id,id+"-task",due,COMPANY,request,HydroDecisionTools.ACTION_MILLIS);
        w.advanceTo(due);
        var result=w.operationsRun().orElseThrow().report(id+"-task:finish");
        assertNotNull(result);assertTrue(result.accepted(),result.reason());
    }
    @Test void unstaffedPhysicalWorldContinuesAndReopensWithoutFabricatedGeneration() throws Exception {
        var file=dir.resolve("passive.journal");
        try(var w=ComposedIndustryScenario.openWithHydro(file)) {
            assertEquals(10,w.openingRegistry().orElseThrow().securities().size());
            w.advanceTo(HOUR);
            var r=report(w);assertEquals(600,r.inflowCubicMetres());assertEquals(60,r.mandatoryReleaseCubicMetres());
            assertEquals(12540,r.storageCubicMetres());assertEquals(0,r.generatedKilowattHours().signum());
            assertEquals(new BigDecimal("4999.00"),r.companyCash());assertTrue(ComposedCompanyReport.of(w,COMPANY).balanced());
        }
        try(var w=ComposedIndustryScenario.openWithHydro(file)) {
            assertEquals(HOUR,w.operationsRun().orElseThrow().hydro().ledger().watermark());
            w.advanceTo(2*HOUR);assertEquals(new BigDecimal("4998.00"),report(w).companyCash());
            assertEquals(1200,report(w).inflowCubicMetres());assertTrue(ComposedCompanyReport.of(w,COMPANY).balanced());
            w.advanceTo(IntegratedResourcesModel.PERIOD_MILLIS+7);
            assertEquals(IntegratedResourcesModel.PERIOD_MILLIS,w.operationsRun().orElseThrow().hydro().ledger().watermark());
            assertTrue(ComposedCompanyReport.of(w,COMPANY).balanced());
        }
    }
    @Test void managerWorkThenConstrainedGenerationActualCollectionAndOutage() throws Exception {
        var file=dir.resolve("operation.journal");
        try(var w=ComposedIndustryScenario.openWithHydro(file)) {
            OperationsFixture.grantBudgetAndAppoint(w,COMPANY,DIRECTORS,MANAGER,"hydro",List.of(0L));
            act(w,"flow",new HydroOperations.Request(HydroOperations.Action.DISPATCH_FLOW,Map.of("cubicMetresPerHour","600")));
            w.advanceTo(w.snapshot().timeMillis()+HOUR);
            assertTrue(report(w).revenue().signum()>0); assertTrue(report(w).receivable().signum()>0);
            BigDecimal beforePaid=report(w).collectedCash();
            act(w,"collect",new HydroOperations.Request(HydroOperations.Action.COLLECT,Map.of("amount","10.00")));
            assertEquals(beforePaid.add(new BigDecimal("10.00")),report(w).collectedCash());
            act(w,"maintenance",new HydroOperations.Request(HydroOperations.Action.MAINTENANCE,Map.of()));
            BigDecimal beforeEnergy=report(w).generatedKilowattHours();
            w.advanceTo(w.snapshot().timeMillis()+HOUR);
            assertEquals(beforeEnergy,report(w).generatedKilowattHours());
            assertEquals(new BigDecimal("60.00"),report(w).maintenanceCost());
            assertTrue(ComposedCompanyReport.of(w,COMPANY).balanced());
        }
        try(var w=ComposedIndustryScenario.openWithHydro(file)) {assertTrue(ComposedCompanyReport.of(w,COMPANY).balanced());}
    }
    @Test void bankDepositIsCountedOnceAndContinuousSettlementPreservesIt() throws Exception {
        try(var w=ComposedIndustryScenario.openWithHydro(dir.resolve("bank.journal"))) {
            OperationsFixture.grantBudgetAndAppoint(w,COMPANY,DIRECTORS,MANAGER,"bank",List.of(0L));
            long due=w.snapshot().timeMillis()+100;
            w.openRoleSession(MANAGER).bankAction("hydro-deposit","hydro-deposit-task",due,1,
                    new WorldRoleSession.BankActionDraft("hydro-deposit",edu.cufe.auction.world.bank.BankActions.Kind.DEPOSIT,
                            "north",COMPANY,new BigDecimal("100.00"),0,0,"","","",""));
            w.advanceTo(due); w.advanceTo(HOUR);
            assertEquals(new BigDecimal("4899.00"),report(w).companyCash());
            assertTrue(ComposedCompanyReport.of(w,COMPANY).balanced());
            assertEquals(new BigDecimal("100.00"),ComposedCompanyReport.of(w,COMPANY).assets().get("银行存款"));
        }
    }
    @Test void futureInflowIsNotManagerKnowledgeAndOriginalIndustryHasNoHydro() throws Exception {
        try(var w=ComposedIndustryScenario.openWithHydro(dir.resolve("view.journal"))) {
            var hydro=w.operationsRun().orElseThrow().hydro();
            var view=HydroDecisionTools.observation(hydro,w.companyGovernance().orElseThrow().boardOf(COMPANY),new BigDecimal("5000.00"),List.of()).toString();
            assertFalse(view.contains("segments"));assertFalse(view.contains("cubicMetresPerHour\":\"900"));
            assertThrows(IllegalArgumentException.class,()->w.openRoleSession(MANAGER).hydroAction("fake","fake-task",1000000,COMPANY,
                    new HydroOperations.Request(HydroOperations.Action.DISPATCH_FLOW,Map.of("cubicMetresPerHour","600")),HydroDecisionTools.ACTION_MILLIS));
        }
        try(var w=ComposedIndustryScenario.open(dir.resolve("old.journal"))) {assertNull(w.operationsRun().orElseThrow().hydro());}
    }
}
