package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.BankActions;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static edu.cufe.auction.world.bank.BankActions.Kind.*;

class OwnerBankValuationTest {
    @TempDir Path dir;
    private static final String BANK = "north", PERSON = "investor", PRODUCT = "fund-a", MANAGER = "bob";
    private static final Path DRAFT = Path.of("src/main/resources/world/opening-copies-example.json");

    @Test void internalConversionsKeepOwnerNavAndHistoryThroughReopen() throws Exception {
        Path file = dir.resolve("nav.journal");
        long start, end;
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(file, DRAFT)) {
            start = w.snapshot().position();
            var inv = w.valuation(PERSON);
            var fund = w.valuation(PRODUCT);
            assertEquals("11350.00", inv.netWorth().toPlainString());
            assertEquals("15750.00", fund.netWorth().toPlainString());
            String reserve = w.bankOf().orElseThrow().decode(w.snapshot().accounts()).config().profile()
                    .banks().get(BANK).reserveAccountId();
            BigDecimal initialReserve = w.snapshot().accounts().get(reserve).cash();
            apply(w, PERSON, DEPOSIT, "", "250.00", "", "", "deposit");
            assertEquals(new BigDecimal("4750.00"), w.valuation(PERSON).cash());
            assertEquals(new BigDecimal("250.00"), w.valuation(PERSON).bankClaims().deposits());
            assertEquals(inv.netWorth(), w.valuation(PERSON).netWorth());
            apply(w, PERSON, PAY, "", "100.00", "DEPOSIT|north|investor", "CUSTODY|cst|investor", "custody");
            var mixed = w.valuation(PERSON);
            assertEquals(new BigDecimal("150.00"), mixed.bankClaims().deposits());
            assertEquals(new BigDecimal("100.00"), mixed.bankClaims().custodyBeneficial());
            assertEquals(inv.netWorth(), mixed.netWorth());
            assertEquals(initialReserve.add(new BigDecimal("250.00")), w.snapshot().accounts().get(reserve).cash());
            apply(w, MANAGER, DEPOSIT, PRODUCT, "200.00", "", "", "fund-deposit");
            apply(w, MANAGER, PAY, PRODUCT, "110.00", "DEPOSIT|north|fund-a", "CUSTODY|cst|fund-a", "fund-custody");
            var managed = w.valuation(PRODUCT);
            assertEquals(new BigDecimal("90.00"), managed.bankClaims().deposits());
            assertEquals(new BigDecimal("110.00"), managed.bankClaims().custodyBeneficial());
            assertEquals(fund.netWorth(), managed.netWorth());
            assertEquals(BigDecimal.ZERO.setScale(2), w.valuation(MANAGER).bankClaims().custodyBeneficial());
            assertEquals(BigDecimal.ZERO.setScale(2), w.valuation("cst-owner").bankClaims().custodyBeneficial());
            assertNull(w.valuation("cst-owner").netWorth());
            for (String person : List.of("carol", "dave", PERSON))
                assertThrows(IllegalArgumentException.class, () -> schedule(w, person, WITHDRAW, PRODUCT, "10.00", "", "", "refused"));
            assertThrows(IllegalArgumentException.class, () -> schedule(w, MANAGER, PAY, PRODUCT, "10.00",
                    "CUSTODY|cst|fund-a", "CASH|INV", "withdraw-to-other"));
            assertThrows(IllegalArgumentException.class, () -> schedule(w, PERSON, PAY, "", "10.00",
                    "CASH|INV", "CUSTODY|cst|fund-a", "external-subscribe"));
            long now = w.snapshot().timeMillis();
            w.openRoleSession(MANAGER).bankAction("in-flight", "in-flight", now + 10, 5,
                    draft("in-flight", WITHDRAW, PRODUCT, "10.00", "", ""));
            w.advanceTo(now + 5);
            w.scheduleRevokeAppointment("revoke", "revoke-task", now + 7, "A-2");
            w.advanceTo(now + 10);
            assertTrue(w.taskCommitResult(WorldRuntime.roleTaskId(MANAGER, "in-flight") + ":finish").contains("NOT_AUTHORIZED"));
            assertEquals(managed.bankClaims(), w.valuation(PRODUCT).bankClaims());
            assertEquals(mixed.bankClaims(), w.valuation(PERSON).bankClaims());
            assertThrows(IllegalArgumentException.class, () -> schedule(w, MANAGER, WITHDRAW, PRODUCT, "10.00", "", "", "after-revoke"));
            end = w.snapshot().position();
        }
        try (var reopened = ComposedIndustryScenario.openOwnerBankValuationOperating(file, null)) {
            assertEquals(end, reopened.snapshot().position());
            for (var owner : List.of(PERSON, PRODUCT)) {
                var nav = reopened.valuation(owner).netWorth();
                var history = reopened.performanceHistory(owner, start, end);
                assertTrue(history.series().stream().allMatch(s -> s.point().navAfterFlow().equals(nav) && s.point().flows().isEmpty()));
                assertEquals(0, history.performance().returnRate().signum());
            }
        }
        try (var legacy = ComposedIndustryScenario.openOpeningCopiesOperating(dir.resolve("legacy.journal"), DRAFT)) {
            assertNull(legacy.valuation(PERSON).bankClaims());
        }
    }

    @Test void drawnPersonalLoanAndPostedInterestArePersonalLiabilities() throws Exception {
        Path file = dir.resolve("borrow.journal");
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(file, DRAFT)) {
            IssuerCreditDecisionTest.appoint(w, BANK, "erin");
            BigDecimal wealth = w.valuation(PERSON).netWorth();
            for (var kind : List.of(LOAN_REQUEST, LOAN_ACCEPT, LOAN_APPROVE, LOAN_DRAW)) {
                String person = kind == LOAN_APPROVE ? "erin" : PERSON;
                apply(w, person, kind, PERSON, "1000.00", "", "", "credit-" + kind);
                var balance = w.valuation(PERSON);
                assertEquals(wealth, balance.netWorth());
                assertEquals(kind == LOAN_DRAW ? "1000.00" : "0.00", balance.bankClaims().loanPrincipal().toPlainString());
                assertEquals(balance.bankClaims().loanPrincipal(), balance.bankClaims().deposits());
            }
            var due = w.snapshot().pending().stream().filter(t -> t.kind().equals(PendingTask.BANK_ACTION_V1)).findFirst().orElseThrow().dueTimeMillis();
            long cut = w.snapshot().position();
            w.advanceTo(due);
            var afterInterest = w.valuation(PERSON);
            assertTrue(afterInterest.bankClaims().loanInterestPayable().signum() > 0);
            assertEquals(wealth.subtract(afterInterest.bankClaims().loanInterestPayable()), afterInterest.netWorth());
            var history = w.performanceHistory(PERSON, cut, w.snapshot().position());
            assertEquals(afterInterest.netWorth(), history.series().get(history.series().size()-1).point().navAfterFlow());
            assertTrue(history.performance().returnRate().signum() < 0);
            assertEquals("0.00", w.valuation(PRODUCT).bankClaims().loanPrincipal().toPlainString());
        }
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(file, null)) {
            assertEquals("1000.00", w.valuation(PERSON).bankClaims().loanPrincipal().toPlainString());
            var earned = w.valuation(PERSON);
            assertEquals("1.00", earned.bankClaims().loanInterestPayable().toPlainString());
            apply(w, PERSON, LOAN_PAY_INTEREST, PERSON, "1.00", "", "", "pay-interest");
            var paid = w.valuation(PERSON);
            assertEquals("0.00", paid.bankClaims().loanInterestPayable().toPlainString());
            assertEquals("999.00", paid.bankClaims().deposits().toPlainString());
            assertEquals(earned.netWorth().add(paid.bankClaims().depositInterestReceivable()
                    .subtract(earned.bankClaims().depositInterestReceivable())), paid.netWorth());
            apply(w, PERSON, LOAN_REPAY, PERSON, "200.00", "", "", "pay-principal");
            assertEquals("800.00", w.valuation(PERSON).bankClaims().loanPrincipal().toPlainString());
            assertEquals("799.00", w.valuation(PERSON).bankClaims().deposits().toPlainString());
            assertEquals(paid.netWorth(), w.valuation(PERSON).netWorth());
        }
    }

    @Test void individualBorrowerWhoBecomesBankManagerCannotApproveOwnContract() throws Exception {
        Path file = dir.resolve("own-loan.journal");
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(file, DRAFT)) {
            apply(w, PERSON, LOAN_REQUEST, PERSON, "100.00", "", "", "own-request");
            apply(w, PERSON, LOAN_ACCEPT, PERSON, "100.00", "", "", "own-accept");
            IssuerCreditDecisionTest.appoint(w, BANK, PERSON);
            long position = w.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> schedule(w, PERSON, LOAN_APPROVE,
                    "eng-a", "100.00", "", "", "approve-own-under-other-subject"));
            assertEquals(position, w.snapshot().position());
            assertEquals(BankActions.LoanStatus.ACCEPTED, w.bankBook().loan("personal-credit").status());
        }
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(file, null)) {
            assertEquals(BankActions.LoanStatus.ACCEPTED, w.bankBook().loan("personal-credit").status());
        }
    }

    @Test void depositInterestEntersHistoryOnlyAfterBankPostsIt() throws Exception {
        Path file = dir.resolve("interest.journal");
        long from, to;
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(file, DRAFT)) {
            apply(w, PERSON, DEPOSIT, "", "1000.00", "", "", "earn");
            w.advanceTo(w.snapshot().timeMillis() + 86_400_000L);
            var pending = w.valuation(PERSON);
            assertEquals("0.00", pending.bankClaims().depositInterestReceivable().toPlainString());
            from = w.snapshot().position();
            apply(w, PERSON, WITHDRAW, "", "20.00", "", "", "savings-withdraw");
            var posted = w.valuation(PERSON);
            assertEquals("0.05", posted.bankClaims().depositInterestReceivable().toPlainString());
            assertEquals("980.00", posted.bankClaims().deposits().toPlainString());
            assertEquals(pending.netWorth().add(new BigDecimal("0.05")), posted.netWorth());
            to = w.snapshot().position();
        }
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(file, null)) {
            var history = w.performanceHistory(PERSON, from, to);
            assertEquals("11350.00", history.series().get(0).point().navAfterFlow().toPlainString());
            assertEquals("11350.05", history.series().get(history.series().size()-1).point().navAfterFlow().toPlainString());
            assertEquals("11350.05", w.valuation(PERSON).netWorth().toPlainString());
        }
    }

    private static WorldRoleSession.BankActionDraft draft(String id, BankActions.Kind kind, String target, String amount, String from, String to) {
        boolean loan = kind.name().startsWith("LOAN_");
        return new WorldRoleSession.BankActionDraft(id, kind, BANK, target, new BigDecimal(amount),
                loan ? 365000 : 0, loan ? 86_400_000L : 0, loan ? "个人周转" : "", from, to, loan ? "personal-credit" : "");
    }

    private static long schedule(WorldRuntime w, String who, BankActions.Kind kind, String target, String amount,
                                 String from, String to, String id) throws Exception {
        long due = w.snapshot().timeMillis() + 3;
        w.openRoleSession(who).bankAction(id, id, due, 1, draft(id, kind, target, amount, from, to));
        return due;
    }

    private static void apply(WorldRuntime w, String who, BankActions.Kind kind, String target, String amount,
                              String from, String to, String id) throws Exception {
        w.advanceTo(schedule(w, who, kind, target, amount, from, to, id));
        assertEquals("OK", w.taskCommitResult(WorldRuntime.roleTaskId(who, id) + ":finish"), id);
    }
}
