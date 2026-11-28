package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import static edu.cufe.auction.world.bank.BankActions.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankCrisisOfficeTest {
    @TempDir Path dir;
    private static String submit(WorldRuntime w,String person,String org,BankActions.Kind kind,String amount,String line) throws Exception {
        String id = "case-" + w.snapshot().position();
        long due = w.snapshot().timeMillis()+3;
        var draft = new WorldRoleSession.BankActionDraft(id,kind,"north",org,new BigDecimal(amount),0,0,"","","",line);
        w.openRoleSession(person).bankAction(id,id,due,1,draft);
        w.advanceTo(due);
        return w.taskCommitResult(WorldRuntime.roleTaskId(person,id)+":finish");
    }
    private static BigDecimal value(WorldRuntime w,String id) { return w.snapshot().accounts().get(id).cash(); }
    private static BankState state(WorldRuntime w) { return w.bankOf().orElseThrow().decode(w.snapshot().accounts()).state(); }

    @Test void onlyAnAppointedProviderCanGrantTheBanksSeparateDrawAndCashStaysFinite() throws Exception {
        Path file = dir.resolve("crisis.journal");
        try (var w = ComposedIndustryScenario.openBankCrisisOperating(file);
             var original = ComposedIndustryScenario.openIntegratedOperating(dir.resolve("control.journal"))) {
            assertEquals(new BigDecimal("1000.00"),value(w,BankCrisisOpening.PROVIDER_CASH));
            assertEquals(value(original,"RES-SPONSOR"),value(w,"RES-SPONSOR").add(value(w,BankCrisisOpening.PROVIDER_CASH)));
            assertEquals(value(original,"ACC-FUND"),value(w,"ACC-FUND"));
            assertEquals(original.snapshot().accounts().values().stream().map(a->a.cash()).reduce(BigDecimal.ZERO,BigDecimal::add),
                    w.snapshot().accounts().values().stream().map(a->a.cash()).reduce(BigDecimal.ZERO,BigDecimal::add));
            assertTrue(state(w).facilityAccounts().isEmpty());
            IssuerCreditDecisionTest.appoint(w,"north","erin");
            String borrower = IssuerCreditDecisionTest.BORROWER;
            IssuerCreditDecisionTest.appoint(w,"mfg-tech",borrower);
            for (var kind : java.util.List.of(LOAN_REQUEST,LOAN_ACCEPT,LOAN_APPROVE,LOAN_DRAW)) {
                String person = kind == LOAN_APPROVE ? "erin" : borrower;
                String id = "client-" + kind;
                long due = w.snapshot().timeMillis()+3;
                var draft = new WorldRoleSession.BankActionDraft(id,kind,"north","mfg-tech",new BigDecimal("4000.00"),20000,
                        30L*86_400_000,"采购","","","client-line");
                w.openRoleSession(person).bankAction(id,id,due,1,draft); w.advanceTo(due);
                assertEquals("OK",w.taskCommitResult(WorldRuntime.roleTaskId(person,id)+":finish"),id);
            }
            assertThrows(IllegalArgumentException.class,()->submit(w,"erin",BankCrisis.PROVIDER,FACILITY_AUTHORIZE,"1600","line"));
            assertThrows(IllegalArgumentException.class,()->submit(w,"aged-manager",BankCrisis.AUTHORITY,RESOLUTION_OPEN,"0",""));
            assertFalse(IssuerCreditDecisionTest.tool(IssuerCreditDecisionTest.view(w,"nina"),"bank.crisis.act"));
            var offer = IssuerCreditDecisionTest.JSON.createObjectNode().put("kind","FACILITY_AUTHORIZE")
                    .put("bank","north").put("lineId","line").put("amount","1600.00");
            IssuerCreditDecisionTest.applied(w,"aged-manager","offer","bank.crisis.act",offer);
            assertEquals(0,state(w).facilityAccount("north","line").nominal().signum());
            assertNotEquals("OK",submit(w,"erin","north",FACILITY_DRAW,"1100","line"));
            assertEquals(new BigDecimal("1000.00"),value(w,BankCrisisOpening.PROVIDER_CASH));
            assertEquals("OK",submit(w,"erin","north",FACILITY_DRAW,"200","line"));
            assertEquals(new BigDecimal("800.00"),value(w,BankCrisisOpening.PROVIDER_CASH));
            assertEquals("OK",submit(w,"erin","north",FACILITY_PAY,"50","line"));
            assertEquals(new BigDecimal("850.00"),value(w,BankCrisisOpening.PROVIDER_CASH));
            assertEquals("RESOLUTION_TRIGGER_ABSENT",submit(w,"kate",BankCrisis.AUTHORITY,RESOLUTION_OPEN,"0",""));
            long now = w.snapshot().timeMillis(); String id="old-office";
            var draft=new WorldRoleSession.BankActionDraft(id,FACILITY_AUTHORIZE,"north",BankCrisis.PROVIDER,
                    new BigDecimal("100.00"),0,0,"","","","line-two");
            w.openRoleSession("aged-manager").bankAction(id,id,now+4,2,draft);
            w.scheduleRevokeAppointment("revoke","revoke-office",now+3,"rescue-representative");
            w.advanceTo(now+4);
            assertNull(state(w).facilityAccount("north","line-two"));
            var bs=state(w);
            String reserve=bs.chart().reserveAccountOf("north");
            var cashOut=value(w,reserve).subtract(new BigDecimal("30.00"));
            assertEquals("OK",submit(w,borrower,"mfg-tech",WITHDRAW,cashOut.toPlainString(),""));
            assertEquals(new BigDecimal("30.00"),value(w,reserve));
            w.advanceTo(bs.facilityAccount("north","line").facilityMaturityMs());
            assertEquals("OK",submit(w,"kate",BankCrisis.AUTHORITY,RESOLUTION_OPEN,"0",""));
            var beforeDeposit=state(w).depositOf("north","mfg-tech");
            var customer=bs.chart().cashAccountOf("mfg-tech");
            var beforeCash=value(w,customer);
            assertEquals("OK",submit(w,"kate",BankCrisis.AUTHORITY,RESOLUTION_DISTRIBUTE,"0",""));
            assertEquals(new BigDecimal("30.00"),value(w,customer).subtract(beforeCash));
            assertEquals(new BigDecimal("30.00"),beforeDeposit.subtract(state(w).depositOf("north","mfg-tech")));
            assertEquals(0,value(w,reserve).signum());
        }
        try (var w=ComposedIndustryScenario.openBankCrisisOperating(file)) {
            assertEquals(new BigDecimal("150.00"),state(w).facilityAccount("north","line").nominal());
            assertEquals(new BigDecimal("850.00"),value(w,BankCrisisOpening.PROVIDER_CASH));
            assertEquals(0,value(w,BankCrisisOpening.AUTHORITY_CASH).signum());
            assertEquals(1,w.snapshot().decisions().orElseThrow().state().decisions().size());
        }
    }
}
