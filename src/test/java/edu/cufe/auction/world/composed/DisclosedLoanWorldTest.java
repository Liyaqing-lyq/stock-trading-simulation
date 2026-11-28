package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static edu.cufe.auction.world.composed.IssuerCreditDecisionTest.*;

class DisclosedLoanWorldTest {
    @TempDir Path dir;
    private static final String ID="working-capital";

    private static void oneClaim(WorldRuntime w,String status,String nominal) {
        var amounts=w.disclosedLoan(); var bank=w.bankOf().orElseThrow();
        assertEquals(status, amounts.get("status"));
        assertEquals(new BigDecimal(nominal), amounts.get("nominal"));
        var accounts=w.snapshot().accounts(); var state=bank.decode(accounts).state();
        var held=state.loan(BANK, BankLoanPromotionOpening.POOL).contract().add(state.loan(BANK,TECH).contract());
        assertEquals(new BigDecimal(nominal),held);
        var client=ClientCompanyReport.of(bank,accounts,TECH);
        assertEquals(new BigDecimal(nominal),client.loanPrincipal());
        assertEquals(client.loanPrincipal(),ComposedCompanyReport.of(w,TECH).liabilities().get("借款本金"));
        assertEquals(new BigDecimal("0.00"),ClientCompanyReport.of(bank,accounts,BankLoanPromotionOpening.POOL).loanPrincipal());
        assertEquals(List.of(),BankInvariants.check(state,accounts));
        assertEquals(new BigDecimal("447500.00"),accounts.values().stream()
                .map(CashLedger.CashAccount::cash).reduce(BigDecimal.ZERO,BigDecimal::add));
    }

    @Test void oneHistoricalLoanAndItsDepositAreVisibleAcrossClassificationReopenAndRepayment() throws Exception {
        Path journal=dir.resolve("bound.journal");
        long at;
        try(var w=ComposedIndustryScenario.openLoanPromotionOperating(journal)) {
            oneClaim(w,"AGGREGATE","200.00");
            appoint(w,TECH,BORROWER); appoint(w,BANK,BANKER);
            // 普通调用方的同名请求不能占用独立的系统转分类批次。
            w.scheduleTransfer("loan-promotion:"+ID,"ordinary-transfer",w.snapshot().timeMillis()+86400000,
                    "nina","HOUSE-NINA","HOUSE-OMAR",new BigDecimal("1.00"),0);
            at=w.snapshot().position();
            assertEquals("200.00",credit(w,BORROWER).at("/managedBorrowers/0/deposit").asText());
            for (var who:List.of(BORROWER,BANKER)) {
                var row=credit(w,who).at("/contracts/0");
                assertEquals("AGGREGATE",row.path("classification").asText());
                assertEquals(ID,row.path("loanId").asText()); assertEquals(185,row.path("sourcePosition").intValue());
            }
            assertTrue(credit(w,"alice").isMissingNode());
            assertThrows(IllegalArgumentException.class,()->w.promoteDisclosedLoan("other",at));
            assertThrows(IllegalStateException.class,()->w.promoteDisclosedLoan(ID,at-1));
            assertEquals(at,w.snapshot().position());
        }
        try(var w=ComposedIndustryScenario.openLoanPromotionOperating(journal)) {
            oneClaim(w,"AGGREGATE","200.00");
            var accounts=w.snapshot().accounts();
            var before=w.bankOf().orElseThrow().decode(accounts);
            var risks=BankMetrics.gate(before.state(),accounts,before.config(),BANK).metrics();
            assertEquals(at+1,w.promoteDisclosedLoan(ID,at));
            var after=w.bankOf().orElseThrow().decode(accounts);
            assertEquals(risks,BankMetrics.gate(after.state(),accounts,after.config(),BANK).metrics());
            assertEquals(at+1,w.promoteDisclosedLoan(ID,at));
            assertEquals(at+1,w.snapshot().position());
            assertEquals(accounts,w.snapshot().accounts());
            oneClaim(w,"DETAILED","200.00");
            assertEquals(TERM,credit(w,BANKER).at("/contracts/0/maturityAt").longValue());
            applied(w,BORROWER,"repay-original","bank.loan.repay",loan(ID).put("amount","100.00"));
            oneClaim(w,"DETAILED","100.00");
            assertEquals("100.00",credit(w,BORROWER).at("/managedBorrowers/0/deposit").asText());
        }
        try(var w=ComposedIndustryScenario.openLoanPromotionOperating(journal)) {
            oneClaim(w,"DETAILED","100.00");
            assertEquals("100.00",credit(w,BANKER).at("/contracts/0/principalDue").asText());
            assertEquals(at+1,w.promoteDisclosedLoan(ID,at));
        }
    }

    @Test void borrowerCanServiceOriginalDebtBeforeManualPromotionAndEventEvidenceCannotBeReplaced() throws Exception {
        Path real=dir.resolve("real.journal"), changed=dir.resolve("different.journal");
        try(var w=ComposedIndustryScenario.openLoanPromotionOperating(real)) {
            appoint(w,TECH,BORROWER);
            appoint(w,AgedIndustryConfig.COMPANY,AgedIndustryConfig.MANAGER_PERSON);
            var other = execute(w,AgedIndustryConfig.MANAGER_PERSON,"collision","bank.loan.request",
                    terms(ID,"200.00").put("borrower",AgedIndustryConfig.COMPANY));
            assertEquals("REJECTED",other.path("execution").asText());
            assertTrue(other.path("businessReject").asText().contains("DISCLOSED_LOAN_ALREADY_DRAWN"));
            oneClaim(w,"AGGREGATE","200.00");
            applied(w,BORROWER,"repay-automatic","bank.loan.repay",loan(ID).put("amount","200.00"));
            oneClaim(w,"DETAILED","0.00");
            assertEquals("CLOSED",credit(w,BORROWER).at("/contracts/0/status").asText());
        }
        try(var from=new WorldJournal(real, RunManifest.LOAN_PROMOTION_RUN_ID);
            var to=new WorldJournal(changed,RunManifest.LOAN_PROMOTION_RUN_ID)) {
            for(var frame:from.commits()){
                var events=new ArrayList<>(frame.events());
                for(int i=0;i<events.size();i++){
                    var e=events.get(i);
                    if(e.type().equals("DISCLOSED_LOAN_PROMOTED_V1"))
                        events.set(i,new WorldEvent(e.eventId(),e.type(),e.payload().replace(ID,"other-loan")));
                }
                to.commit(frame.commandId(),frame.commandContent(),frame.result(),events);
            }
        }
        assertThrows(java.io.IOException.class,()->ComposedIndustryScenario.openLoanPromotionOperating(changed));
    }

    @Test void originalMaturitySchedulesAccrualWithoutAManagerAction() throws Exception {
        Path real=dir.resolve("maturity.journal");
        try(var w=ComposedIndustryScenario.openLoanPromotionOperating(real)) {
            w.advanceTo(TERM-1);
            oneClaim(w,"AGGREGATE","200.00");
            w.advanceTo(TERM);
            oneClaim(w,"DETAILED","200.00");
            assertEquals(new BigDecimal("0.33"),w.disclosedLoan().get("interestDue"));
        }
        try(var w=ComposedIndustryScenario.openLoanPromotionOperating(real)) {
            assertEquals(new BigDecimal("0.33"),w.disclosedLoan().get("interestDue"));
        }
    }
}
