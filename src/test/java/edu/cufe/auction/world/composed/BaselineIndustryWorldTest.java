package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BaselineIndustryWorldTest {
    @TempDir Path dir;
    static final long DAY=86_400_000L;
    static final long PERIOD=30*DAY;
    static BigDecimal cash(WorldRuntime w,String account) {return w.snapshot().accounts().get(account).cash();}
    static BigDecimal total(WorldRuntime w) {return w.snapshot().accounts().values().stream().map(CashLedger.CashAccount::cash).reduce(BigDecimal.ZERO,BigDecimal::add);}
    static Map<String,String> fields(String... values) {var m=new TreeMap<String,String>();for(int i=0;i<values.length;i+=2)m.put(values[i],values[i+1]);return m;}
    static void digital(WorldRuntime w,String id,DigitalOperations.Action action,String... values) throws Exception {
        long due=w.snapshot().timeMillis()+2000;
        w.openRoleSession(DigitalIndustryConfig.MANAGER_PERSON).digitalAction(id,id+"-task",due,DigitalIndustryConfig.COMPANY,new DigitalOperations.Request(action,fields(values)),1000);
        w.advanceTo(due);var result=w.operationsRun().orElseThrow().report(id+"-task:finish");assertNotNull(result);assertTrue(result.accepted(),result.reason());
    }
    static void aged(WorldRuntime w,String id,AgedOperations.Action action,String... values) throws Exception {
        long due=w.snapshot().timeMillis()+2000;
        w.openRoleSession(AgedIndustryConfig.MANAGER_PERSON).agedAction(id,id+"-task",due,AgedIndustryConfig.COMPANY,new AgedOperations.Request(action,fields(values)),1000);
        w.advanceTo(due);var result=w.operationsRun().orElseThrow().report(id+"-task:finish");assertNotNull(result);assertTrue(result.accepted(),result.reason());
    }
    static void appoint(WorldRuntime w,String company,String manager,String prefix,List<Long> periods) throws Exception {
        OperationsFixture.grantBudgetAndAppoint(w,company,BaselineIndustryConfig.directors(company),manager,prefix,periods);
    }
    @Test void exactlyTenIndependentIssuersAndUnstaffedWorldCrossesTwoPeriodsAndRestores() throws Exception {
        var path=dir.resolve("ten.journal");BigDecimal opening;long pos;
        try(var w=ComposedIndustryScenario.openBaseline(path)) {
            var securities=w.openingRegistry().orElseThrow().securities();
            assertEquals(10,securities.size());assertEquals(10,securities.stream().map(OpeningRegistry.Security::issuerOrganizationId).distinct().count());
            assertFalse(securities.stream().anyMatch(x->List.of("cufe","eng-b").contains(x.issuerOrganizationId())));
            assertTrue(w.snapshot().manufacturing().isEmpty());assertTrue(w.snapshot().governance().isEmpty());
            for(var security:securities) {var board=w.boardOfCompany(security.issuerOrganizationId()).orElseThrow();assertEquals(5,board.board().charter().seats().size());}
            assertNull(w.operationsRun().orElseThrow().digital().mandateOf(DigitalIndustryConfig.MANDATE_CAPITAL));
            opening=total(w);w.advanceTo(2*PERIOD+1);
            assertEquals(2,w.operationsRun().orElseThrow().digital().ledger().closedPeriods());
            assertEquals(2,w.operationsRun().orElseThrow().aged().ledger().closedPeriods());
            assertEquals(new BigDecimal("19400.00"),cash(w,DigitalIndustryConfig.COMPANY_ACCOUNT));
            assertEquals(new BigDecimal("19800.00"),cash(w,AgedIndustryConfig.COMPANY_ACCOUNT));
            assertTrue(ComposedCompanyReport.of(w,DigitalIndustryConfig.COMPANY).balanced());
            assertTrue(ComposedCompanyReport.of(w,AgedIndustryConfig.COMPANY).balanced());
            assertEquals(0,opening.compareTo(total(w)));pos=w.snapshot().position();
        }
        try(var w=ComposedIndustryScenario.openBaseline(path)) {
            assertEquals(pos,w.snapshot().position());assertEquals(0,opening.compareTo(total(w)));
            assertTrue(ComposedCompanyReport.of(w,DigitalIndustryConfig.COMPANY).balanced());
            assertTrue(ComposedCompanyReport.of(w,AgedIndustryConfig.COMPANY).balanced());
        }
    }
    @Test void digitalLateStartCrossesPeriodThenRealDeliveryAndRefundWithoutCreatingRevenueAtApproval() throws Exception {
        var path=dir.resolve("digital.journal");
        try(var w=ComposedIndustryScenario.openBaseline(path)) {
            appoint(w,DigitalIndustryConfig.COMPANY,DigitalIndustryConfig.MANAGER_PERSON,"digital",List.of(0L,1L,2L));
            w.advanceTo(20*DAY);
            digital(w,"pilot",DigitalOperations.Action.BEGIN_PILOT);
            long due=w.operationsRun().orElseThrow().digital().ledger().stageOf(DigitalServiceModel.Stage.PILOT).completesAt();
            w.advanceTo(due-1);assertEquals(DigitalServiceModel.StageStatus.RUNNING,w.operationsRun().orElseThrow().digital().ledger().stageOf(DigitalServiceModel.Stage.PILOT).status());
            w.advanceTo(due);digital(w,"validation",DigitalOperations.Action.BEGIN_VALIDATION);
            due=w.operationsRun().orElseThrow().digital().ledger().stageOf(DigitalServiceModel.Stage.VALIDATION).completesAt();
            w.advanceTo(due);
            assertEquals(DigitalServiceModel.StageStatus.PASSED,w.operationsRun().orElseThrow().digital().ledger().stageOf(DigitalServiceModel.Stage.VALIDATION).status());
            digital(w,"permit",DigitalOperations.Action.APPROVE_COMMERCIAL,"approvalId",DigitalIndustryConfig.APPROVAL_COMMERCIAL);
            assertEquals(0,ComposedCompanyReport.of(w,DigitalIndustryConfig.COMPANY).income().get("服务收入（累计）").signum());
            digital(w,"contract",DigitalOperations.Action.SIGN_CONTRACT,"contractId","service-1","quantityUnits","100","prepaidCents","500000");
            digital(w,"deliver",DigitalOperations.Action.DELIVER,"contractId","service-1","units","40","receivableId","service-ar");
            digital(w,"terminate",DigitalOperations.Action.TERMINATE,"contractId","service-1");
            digital(w,"refund",DigitalOperations.Action.REFUND,"contractId","service-1","refundCents","100000");
            assertEquals(new BigDecimal("4000.00"),ComposedCompanyReport.of(w,DigitalIndustryConfig.COMPANY).income().get("服务收入（累计）"));
            assertEquals(new BigDecimal("19400.00"),cash(w,DigitalIndustryConfig.COMPANY_ACCOUNT));
            assertTrue(ComposedCompanyReport.of(w,DigitalIndustryConfig.COMPANY).balanced());
        }
        try(var w=ComposedIndustryScenario.openBaseline(path)) {assertTrue(ComposedCompanyReport.of(w,DigitalIndustryConfig.COMPANY).balanced());}
    }
    @Test void agedBankDepositProductionMaturityActualSellThroughAndCollectionShareOneWorld() throws Exception {
        var path=dir.resolve("aged.journal");
        try(var w=ComposedIndustryScenario.openBaseline(path)) {
            appoint(w,AgedIndustryConfig.COMPANY,AgedIndustryConfig.MANAGER_PERSON,"aged",List.of(0L,1L,2L,3L,4L));
            long due=w.snapshot().timeMillis()+100;
            w.openRoleSession(AgedIndustryConfig.MANAGER_PERSON).bankAction("aged-deposit","aged-deposit-task",due,1,
                    new WorldRoleSession.BankActionDraft("aged-deposit",edu.cufe.auction.world.bank.BankActions.Kind.DEPOSIT,"north",AgedIndustryConfig.COMPANY,new BigDecimal("1000.00"),0,0,"","","",""));
            w.advanceTo(due);
            aged(w,"order",AgedOperations.Action.ORDER_RAW,"orderId","raw-1","units","50");
            w.advanceTo(PERIOD+1);
            aged(w,"receive",AgedOperations.Action.RECEIVE_RAW,"orderId","raw-1");
            aged(w,"produce",AgedOperations.Action.PRODUCE,"batchId","batch-1","units","50");
            assertEquals(50,w.operationsRun().orElseThrow().aged().ledger().batches().get(0).remaining());
            w.advanceTo(3*PERIOD+1);
            aged(w,"ship",AgedOperations.Action.SHIP,"shipmentId","ship-1","channelId",AgedIndustryConfig.CHANNEL,"units","50");
            assertEquals(0,ComposedCompanyReport.of(w,AgedIndustryConfig.COMPANY).income().get("终端售出收入（累计）").signum());
            w.advanceTo(4*PERIOD+1);
            assertEquals(new BigDecimal("2000.00"),ComposedCompanyReport.of(w,AgedIndustryConfig.COMPANY).income().get("终端售出收入（累计）"));
            assertThrows(IllegalArgumentException.class,()->aged(w,"expired-collect",AgedOperations.Action.COLLECT,"channelId",AgedIndustryConfig.CHANNEL,"amountCents","160000"));
            appoint(w,AgedIndustryConfig.COMPANY,AgedIndustryConfig.MANAGER_PERSON,"aged-renew",List.of(5L));
            aged(w,"collect",AgedOperations.Action.COLLECT,"channelId",AgedIndustryConfig.CHANNEL,"amountCents","160000");
            var report=ComposedCompanyReport.of(w,AgedIndustryConfig.COMPANY);assertTrue(report.balanced());
            assertEquals(new BigDecimal("1000.00"),report.assets().get("银行存款"));assertEquals(new BigDecimal("19850.00"),cash(w,AgedIndustryConfig.COMPANY_ACCOUNT));
        }
        try(var w=ComposedIndustryScenario.openBaseline(path)) {assertTrue(ComposedCompanyReport.of(w,AgedIndustryConfig.COMPANY).balanced());}
    }
}
