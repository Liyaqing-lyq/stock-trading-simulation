package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.bank.BankState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class IssuerCreditTest {
    @TempDir Path dir;
    private static final String BANK = ComposedWorldScenario.BANK;
    private static final String TECH = ComposedManufacturingThreeScenario.TECH;
    private static final String AGED = AgedIndustryConfig.COMPANY;
    private static final long TENOR = ManufacturingTemplateModel.PERIOD_MILLIS;
    private static final BigDecimal PRINCIPAL = new BigDecimal("200.00");

    @Test void nineNamedIssuersHaveSeparateCreditLedgersWithoutOpeningLoans() throws Exception {
        Path prior = dir.resolve("prior.journal");
        try (var old = ComposedIndustryScenario.openContinuingRolesOperating(prior);
             var world = ComposedIndustryScenario.openIssuerCreditOperating(dir.resolve("expanded.journal"))) {
            var config = world.profile().bankOpening().config();
            var issuers = world.profile().registry().securities().stream().map(OpeningRegistry.Security::issuerOrganizationId)
                    .filter(issuer -> !issuer.equals(BANK)).collect(Collectors.toSet());
            assertEquals(9, issuers.size());
            assertEquals(issuers, config.profile().buckets().keySet());
            assertEquals(issuers, config.groupMap().keySet());
            assertEquals(9, Set.copyOf(config.groupMap().values()).size());
            assertEquals(9, config.profile().loanAccounts().size());
            assertTrue(world.bankBook().loans().isEmpty());
            assertEquals(Set.of("eng-a"), old.profile().bankOpening().config().profile().buckets().keySet());
            assertEquals(old.snapshot().accounts(), world.snapshot().accounts());
        }
        assertThrows(Exception.class, () -> ComposedIndustryScenario.openIssuerCreditOperating(prior));
    }

    @Test void managersBorrowWithIndependentApprovalAndKeepMaturedDebtUntilRepayment() throws Exception {
        Path file = dir.resolve("loans.journal");
        String manager = ComposedManufacturingThreeScenario.TECH_MANAGER;
        String secondManager = AgedIndustryConfig.MANAGER_PERSON;
        try (var w = ComposedIndustryScenario.openIssuerCreditOperating(file)) {
            appoint(w, TECH, manager);
            appoint(w, AGED, secondManager);
            appoint(w, BANK, "erin");
            var reserve = bank(w).gl(BANK, "RESERVE");
            BigDecimal oversized = new BigDecimal("100000000.00");
            for (var kind : java.util.List.of(BankActions.Kind.LOAN_REQUEST, BankActions.Kind.LOAN_ACCEPT, BankActions.Kind.LOAN_APPROVE)) {
                String person = kind == BankActions.Kind.LOAN_APPROVE ? "erin" : manager;
                assertEquals("OK", apply(w, person, TECH, "large", kind, oversized));
            }
            assertTrue(apply(w, manager, TECH, "large", BankActions.Kind.LOAN_DRAW, oversized)
                    .startsWith("CREDIT_BLOCKED_BY_BREACH"));
            assertEquals(BigDecimal.ZERO.setScale(2), bank(w).depositOf(BANK, TECH));
            assertThrows(IllegalArgumentException.class, () -> act(w, "investor", TECH, "fraud", BankActions.Kind.LOAN_REQUEST));
            act(w, manager, TECH, "tech", BankActions.Kind.LOAN_REQUEST);
            assertThrows(IllegalArgumentException.class, () -> act(w, manager, TECH, "tech", BankActions.Kind.LOAN_APPROVE));
            act(w, manager, TECH, "tech", BankActions.Kind.LOAN_ACCEPT);
            act(w, "erin", TECH, "tech", BankActions.Kind.LOAN_APPROVE);
            assertEquals(BigDecimal.ZERO.setScale(2), bank(w).depositOf(BANK, TECH));
            act(w, manager, TECH, "tech", BankActions.Kind.LOAN_DRAW);
            act(w, secondManager, AGED, "aged", BankActions.Kind.LOAN_REQUEST);
            act(w, secondManager, AGED, "aged", BankActions.Kind.LOAN_ACCEPT);
            act(w, "erin", AGED, "aged", BankActions.Kind.LOAN_APPROVE);
            act(w, secondManager, AGED, "aged", BankActions.Kind.LOAN_DRAW);
            assertEquals(reserve, bank(w).gl(BANK, "RESERVE"));
            for (String company : Set.of(TECH, AGED)) {
                assertEquals(PRINCIPAL, bank(w).depositOf(BANK, company));
                assertEquals(PRINCIPAL, bank(w).loan(BANK, company).contract());
                assertTrue(ComposedCompanyReport.of(w, company).balanced());
            }
            w.advanceTo(w.snapshot().timeMillis() + TENOR + 1);
            assertTrue(bank(w).loan(BANK, TECH).overdue());
            assertTrue(bank(w).loan(BANK, AGED).overdue());
            assertEquals(PRINCIPAL, bank(w).loan(BANK, TECH).contract());
            assertEquals(new BigDecimal("0.33"), bank(w).loan(BANK, TECH).interestReceivable());
            act(w, manager, TECH, "tech", BankActions.Kind.LOAN_REPAY);
            assertEquals(BigDecimal.ZERO.setScale(2), bank(w).loan(BANK, TECH).contract());
            assertEquals(new BigDecimal("0.33"), bank(w).loan(BANK, TECH).interestReceivable());
            assertTrue(bank(w).loan(BANK, TECH).overdue(), "只还本金，利息仍然逾期");
            assertEquals(PRINCIPAL, bank(w).loan(BANK, AGED).contract());
        }
        try (var w = ComposedIndustryScenario.openIssuerCreditOperating(file)) {
            assertEquals(BigDecimal.ZERO.setScale(2), bank(w).loan(BANK, TECH).contract());
            assertEquals(new BigDecimal("0.33"), bank(w).loan(BANK, TECH).interestReceivable());
            assertTrue(bank(w).loan(BANK, TECH).overdue(), "只还本金，利息仍然逾期");
            assertEquals(PRINCIPAL, bank(w).loan(BANK, AGED).contract());
            assertTrue(bank(w).loan(BANK, AGED).overdue());
        }
    }

    private static void appoint(WorldRuntime w, String company, String manager) throws Exception {
        ComposedWorldSelfTest.appoint(w, w.snapshot().timeMillis() + 1, company, manager, company, "appoint-doc-" + company);
        assertTrue(w.isActiveManager(company, manager, w.snapshot().timeMillis()));
    }

    private static BankState bank(WorldRuntime w) {
        return w.bankOf().orElseThrow().decode(w.snapshot().accounts()).state();
    }

    private static void act(WorldRuntime w, String person, String company, String loan, BankActions.Kind kind) throws Exception {
        assertEquals("OK", apply(w, person, company, loan, kind, PRINCIPAL));
    }

    private static String apply(WorldRuntime w, String person, String company, String loan, BankActions.Kind kind,
                                BigDecimal amount) throws Exception {
        long due = w.snapshot().timeMillis() + 2;
        String id = loan + "-" + kind;
        var draft = new WorldRoleSession.BankActionDraft(id, kind, BANK, company, amount, 20_000L,
                TENOR, "跨期营运资金", "", "", loan);
        w.openRoleSession(person).bankAction(id, id, due, 1, draft);
        w.advanceTo(due);
        return w.taskCommitResult(WorldRuntime.roleTaskId(person, id) + ":finish");
    }
}
