package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.bank.BankState;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.IssuerCreditDecisionTest.*;
import static org.junit.jupiter.api.Assertions.*;

public class BankLoanLossDecisionTest {
    @TempDir Path dir;
    static final String HIST = "working-capital", IMPAIR = "bank.loan.impair", WRITE_OFF = "bank.loan.writeOff";

    /** 以公司章程、合格董事表决和实施帧产生真实的银行经理任职。 */
    public static void appointBanker(WorldRuntime w) throws Exception {
        CrisisOfficeObjectivesTest.appoint(w, BANK, BANKER);
    }

    private static List<BigDecimal> cashLegs(WorldRuntime w) {
        var accounts = w.snapshot().accounts();
        var chart = ledger(w).chart();
        return List.of(accounts.get(chart.reserveAccountOf(BANK)).cash(), accounts.get(chart.cashAccountOf(TECH)).cash(),
                accounts.values().stream().map(a -> a.cash()).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BankState ledger(WorldRuntime w) {
        return w.bankOf().orElseThrow().decode(w.snapshot().accounts()).state();
    }

    private static JsonNode loss(WorldRuntime w, String id, String tool, String amount) throws Exception {
        return execute(w, BANKER, id, tool, loan(HIST).put("amount", amount));
    }

    private static void amount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> expected + " vs " + actual);
    }

    @Test void managerSignsLossesNamedLoanSurvivesAndBorrowerRepaysFromOwnDeposit() throws Exception {
        var file = dir.resolve("bank-loss.journal");
        var seed = dir.resolve("banker-ready.journal");
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(seed)) {
            assertFalse(tool(view(w, BANKER), IMPAIR));
            CrisisOfficeObjectivesTest.appoint(w, BANK, BANKER);
            CrisisOfficeObjectivesTest.appoint(w, TECH, BORROWER);
            w.advanceTo(9 * 3_600_000L);
            assertTrue(tool(view(w, BANKER), IMPAIR));
            assertTrue(tool(view(w, BANKER), WRITE_OFF));
            assertFalse(tool(view(w, BORROWER), WRITE_OFF));
            assertTrue(credit(w, BORROWER).path("lossExposures").isMissingNode());
            var exposure = credit(w, BANKER).at("/lossExposures/0");
            assertEquals(HIST, exposure.path("loanId").asText());
            amount("200", new BigDecimal(exposure.required("maxImpair").asText()));
        }
        Files.copy(seed, file);
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(file)) {
            var accounts = cashLegs(w);
            var initial = ledger(w);
            var original = BankLoanPromotionOpening.certificate().obligation(initial);
            var initialDeposit = initial.depositOf(BANK, TECH);
            var initialEquity = initial.bookEquity(BANK);
            assertEquals("EXCEEDS_UNPROVIDED_EXPOSURE", loss(w, "too-large", IMPAIR, "200.01").path("businessReject").asText());
            assertEquals("APPLIED", loss(w, "provide", IMPAIR, "70.00").path("execution").asText());
            var after = ledger(w);
            amount("70", initialEquity.subtract(after.bookEquity(BANK)));
            amount("70", after.gl(BANK, after.chart().llpFor(TECH)).negate());
            assertEquals(accounts, cashLegs(w));
            assertTrue(loss(w, "insufficient-provision", WRITE_OFF, "70.01").path("businessReject").asText().contains("INSUFFICIENT_PROVISION"));
            assertEquals("APPLIED", loss(w, "write-off", WRITE_OFF, "50.00").path("execution").asText());
            var marked = ledger(w);
            assertEquals(after.bookEquity(BANK), marked.bookEquity(BANK));
            amount("20", marked.gl(BANK, marked.chart().llpFor(TECH)).negate());
            var t = marked.loan(BANK, TECH).tranche(original.trancheId());
            amount("200", t.contract()); amount("150", t.onbook()); amount("50", t.writtenOff());
            var borrowerReport = ComposedCompanyReport.of(w, TECH);
            assertTrue(borrowerReport.balanced());
            amount("200", borrowerReport.liabilities().get("借款本金"));
            assertEquals(initialDeposit, marked.depositOf(BANK, TECH));
            assertEquals(accounts, cashLegs(w));
            applied(w, BORROWER, "pay-65", "bank.loan.repay", loan(HIST).put("amount","65.00"));
            var paid = ledger(w);
            t = paid.loan(BANK, TECH).tranche(original.trancheId());
            amount("135", t.contract()); amount("135", t.onbook()); amount("0", t.writtenOff());
            amount("135", ComposedCompanyReport.of(w, TECH).liabilities().get("借款本金"));
            amount("65", initialDeposit.subtract(paid.depositOf(BANK, TECH)));
            amount("50", after.gl(BANK, "RECOVERY").subtract(paid.gl(BANK, "RECOVERY")));
            assertEquals(accounts, cashLegs(w));
            var rejected = execute(w, BORROWER, "overpay", "bank.loan.repay", loan(HIST).put("amount","135.01"));
            assertEquals("REJECTED", rejected.path("execution").asText());
            applied(w, BORROWER, "repay-rest", "bank.loan.repay", loan(HIST).put("amount","135.00"));
            var end = ledger(w);
            amount("0", end.gl(BANK, end.chart().llpFor(TECH)));
            amount("0", end.loan(BANK, TECH).tranche(original.trancheId()).contract());
            amount("200", initialDeposit.subtract(end.depositOf(BANK, TECH)));
        }
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(file)) {
            amount("0", w.bankBook().loan(HIST).drawn());
            assertTrue(w.bankActionAudits().stream().anyMatch(a -> "LOAN_REPAY".equals(a.kind())
                    && a.accepted() && a.receipt().contains("recovery=50.00")));
        }
    }

    private static WorldRuntime.BankActionAudit signed(WorldRuntime w, String actor, BankActions.Kind kind,
                                                        String clientId, String businessId, String contract, String amount) throws Exception {
        long due = w.snapshot().timeMillis() + 60_001;
        var draft = new WorldRoleSession.BankActionDraft(businessId, kind, BANK, "", new BigDecimal(amount),
                0, 0, "", "", "", contract);
        w.openRoleSession(actor).bankAction(clientId, clientId, due, 60_000, draft);
        w.advanceTo(due);
        return w.bankActionAudits().stream().filter(a -> a.atMillis() == due && a.personPartyId().equals(actor)
                && a.kind().equals(kind.name())).findFirst().orElseThrow();
    }

    @Test void changedBalancesDoNotChangeTheOriginalSignedReceiptOrRepeatCashLegs() throws Exception {
        var file = dir.resolve("retries.journal");
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(file)) {
            appointBanker(w);
            CrisisOfficeObjectivesTest.appoint(w, TECH, BORROWER);
            var denied = signed(w, BANKER, BankActions.Kind.LOAN_WRITE_OFF, "early", "deny", HIST, "5.00");
            assertFalse(denied.accepted());
            var p = signed(w, BANKER, BankActions.Kind.LOAN_IMPAIR, "first", "X", HIST, "200.00");
            assertTrue(p.accepted());
            var retry = signed(w, BANKER, BankActions.Kind.LOAN_IMPAIR, "again", "X", HIST, "200.00");
            assertTrue(retry.accepted()); assertEquals(p.receipt() + ";replayed=true", retry.receipt());
            var stillDenied = signed(w, BANKER, BankActions.Kind.LOAN_WRITE_OFF, "denied-again", "deny", HIST, "5.00");
            assertFalse(stillDenied.accepted()); assertEquals(denied.receipt() + ";replayed=true", stillDenied.receipt());
            var conflict = signed(w, BANKER, BankActions.Kind.LOAN_IMPAIR, "replace", "X", HIST, "1.00");
            assertEquals("COMMAND_ID_CONFLICT", conflict.reason()); assertFalse(conflict.accepted());
            var off = signed(w, BANKER, BankActions.Kind.LOAN_WRITE_OFF, "write", "Y", HIST, "200.00");
            assertTrue(off.accepted());
            var offAgain = signed(w, BANKER, BankActions.Kind.LOAN_WRITE_OFF, "write-again", "Y", HIST, "200.00");
            assertTrue(offAgain.accepted()); assertEquals(off.receipt() + ";replayed=true", offAgain.receipt());
            var deposit = ledger(w).depositOf(BANK, TECH);
            var paid = signed(w, BORROWER, BankActions.Kind.LOAN_REPAY, "pay", "Z", HIST, "200.00");
            assertTrue(paid.accepted()); assertTrue(paid.receipt().contains("recovery=200.00"));
            var paidAgain = signed(w, BORROWER, BankActions.Kind.LOAN_REPAY, "pay-again", "Z", HIST, "200.00");
            assertTrue(paidAgain.accepted()); assertEquals(paid.receipt() + ";replayed=true", paidAgain.receipt());
            amount("200", deposit.subtract(ledger(w).depositOf(BANK, TECH)));
        }
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(file)) {
            var retry = signed(w, BANKER, BankActions.Kind.LOAN_WRITE_OFF, "after-restart", "Y", HIST, "200.00");
            assertTrue(retry.accepted()); assertTrue(retry.receipt().endsWith(";replayed=true"));
            var t = BankLoanPromotionOpening.certificate().obligation(ledger(w));
            amount("0", t.contract()); amount("0", t.writtenOff());
        }
    }

    @Test void pooledProvisionCannotWriteDownAnotherTrancheOrAControlledBorrower() throws Exception {
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(dir.resolve("second-contract.journal"))) {
            appointBanker(w);
            CrisisOfficeObjectivesTest.appoint(w, TECH, BORROWER);
            String second = "separate-20";
            applied(w, BORROWER, "ask-second", "bank.loan.request", terms(second, "20.00"));
            applied(w, BORROWER, "accept-second", "bank.loan.accept", loan(second));
            applied(w, BANKER, "approve-second", "bank.loan.approve", loan(second));
            applied(w, BORROWER, "draw-second", "bank.loan.draw", loan(second));
            assertTrue(signed(w, BANKER, BankActions.Kind.LOAN_IMPAIR, "one-contract", "two-contracts", HIST, "1.00").accepted());
            assertEquals("COMMAND_ID_CONFLICT", signed(w, BANKER, BankActions.Kind.LOAN_IMPAIR,
                    "another-contract", "two-contracts", second, "1.00").reason());
            applied(w, BANKER, "provide-40", IMPAIR, loan(HIST).put("amount", "40.00"));
            var denied = execute(w, BANKER, "wrong-amount", WRITE_OFF, loan(second).put("amount", "20.01"));
            assertTrue(denied.path("businessReject").asText().contains("ONBOOK_INSUFFICIENT"));
            applied(w, BANKER, "write-off-second", WRITE_OFF, loan(second).put("amount", "20.00"));
            applied(w, BORROWER, "pay-second", "bank.loan.repay", loan(second).put("amount", "20.00"));
            amount("0", w.bankBook().loan(second).drawn());
            var first = BankLoanPromotionOpening.certificate().obligation(ledger(w));
            amount("200", first.contract()); amount("200", first.onbook()); amount("0", first.writtenOff());
        }
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(dir.resolve("conflicted.journal"))) {
            appointBanker(w);
            CrisisOfficeObjectivesTest.appoint(w, TECH, BANKER);
            assertTrue(tool(view(w, BANKER), IMPAIR));
            var exposure = credit(w, BANKER).at("/lossExposures/0");
            amount("0", new BigDecimal(exposure.required("maxImpair").asText()));
            assertNull(decide(w, BANKER, "conflicted", IMPAIR, loan(HIST).put("amount", "1.00")));
            assertTrue(w.bankActionAudits().stream().noneMatch(a -> "LOAN_IMPAIR".equals(a.kind())));
        }
    }

    @Test void priorWorldCannotSignAndRevokedOriginalManagerCannotFinish() throws Exception {
        var old = dir.resolve("old.journal");
        try (var w = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(old)) {
            CrisisOfficeObjectivesTest.appoint(w, BANK, BANKER);
            assertFalse(tool(view(w, BANKER), IMPAIR));
            assertTrue(credit(w, BANKER).path("lossExposures").isMissingNode());
            var request = new WorldRoleSession.BankActionDraft("old", BankActions.Kind.LOAN_IMPAIR,
                    BANK, "", BigDecimal.ONE, 0, 0, "", "", "", HIST);
            assertThrows(IllegalArgumentException.class, () -> w.openRoleSession(BANKER).bankAction(
                    "old", "old", w.snapshot().timeMillis() + 60_001, 60_000, request));
        }
        try (var w = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(old)) {
            assertTrue(credit(w, BANKER).path("lossProcedure").isMissingNode());
        }
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(dir.resolve("revoked.journal"))) {
            CrisisOfficeObjectivesTest.appoint(w, BANK, BANKER);
            var work = decide(w, BANKER, "former-manager", IMPAIR, loan(HIST).put("amount","10.00"));
            assertNotNull(work);
            w.advanceTo(work.dueMillis());
            w.scheduleRevokeAppointment("end-job", "end-job", w.snapshot().timeMillis() + 1, "appoint-" + BANK + "-term");
            w.advanceTo(work.dueMillis() + 60_000);
            assertFalse(tool(view(w, BANKER), IMPAIR));
            assertEquals("REJECTED", view(w, BANKER).at("/engineering/deliberation/lastActionResult/execution").asText());
            assertTrue(w.bankActionAudits().stream().noneMatch(a -> "LOAN_IMPAIR".equals(a.kind())
                    && a.accepted() && !"STARTED".equals(a.reason())));
        }
    }
}
