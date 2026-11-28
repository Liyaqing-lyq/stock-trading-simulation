package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.WorldRuntime;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.IssuerCreditDecisionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class OwnerBankDecisionTest {
    @TempDir Path dir;
    static final Path DRAFT = Path.of("src/main/resources/world/opening-copies-example.json");
    static final String PERSON = "investor", PRODUCT = "fund-a", MANAGER = "bob", MOVE = "bank.cash.move";
    static final String INV = "CASH|INV", FUND = "CASH|ACC-FUND";
    static final String PERSONAL_DEPOSIT = "DEPOSIT|north|investor", FUND_DEPOSIT = "DEPOSIT|north|fund-a";
    static final String PERSONAL_CUSTODY = "CUSTODY|cst|investor", FUND_CUSTODY = "CUSTODY|cst|fund-a";

    private static ObjectNode cash(String customer, String amount) {
        return JSON.createObjectNode().put("bank", BANK).put("borrower", customer).put("amount", amount);
    }
    private static ObjectNode move(String customer, String source, String target, String amount) {
        return JSON.createObjectNode().put("bank", BANK).put("customer", customer)
                .put("sourceToken", source).put("payeeToken", target).put("amount", amount);
    }
    private static JsonNode account(WorldRuntime w, String person, String customer) throws Exception {
        var rows = credit(w, person).path("ownerAccounts");
        assertEquals(1, rows.size(), rows.toString());
        var row = rows.get(0);
        assertEquals(customer, row.path("customer").asText());
        assertEquals(BANK, row.path("bank").asText());
        assertEquals(3, row.path("buckets").size());
        return row;
    }

    @Test void visibleCashBucketsMoveRealBalancesAndMandateExpiresDuringWork() throws Exception {
        var journal = dir.resolve("owner-cash.journal");
        long lastPosition;
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(journal, DRAFT)) {
            var personal = account(w, PERSON, PERSON);
            assertEquals("PERSON", personal.path("authority").asText());
            assertTrue(personal.path("canBorrow").asBoolean());
            assertFalse(personal.has("appointmentId"));
            assertFalse(personal.path("buckets").has(FUND_CUSTODY));
            var delegated = account(w, MANAGER, PRODUCT);
            assertEquals("PORTFOLIO_MANAGER", delegated.path("authority").asText());
            assertFalse(delegated.path("canBorrow").asBoolean());
            assertFalse(delegated.path("buckets").has(INV));
            assertFalse(tool(view(w, MANAGER), "bank.loan.request"));
            for (String who : List.of("alice", "carol", "dave")) {
                assertFalse(tool(view(w, who), MOVE));
                assertFalse(credit(w, who).has("ownerAccounts"));
            }
            for (String who : List.of(PERSON, MANAGER)) assertTrue(w.autonomyInputLimitViolation(who).isEmpty());

            applied(w, PERSON, "my-deposit", "bank.cash.deposit", cash(PERSON, "25.00"));
            applied(w, PERSON, "my-custody", MOVE, move(PERSON, PERSONAL_DEPOSIT, PERSONAL_CUSTODY, "10.00"));
            applied(w, PERSON, "my-withdraw", "bank.cash.withdraw", cash(PERSON, "5.00"));
            assertEquals("4980.00", w.valuation(PERSON).cash().toPlainString());
            assertEquals("10.00", w.valuation(PERSON).bankClaims().deposits().toPlainString());
            assertEquals("10.00", w.valuation(PERSON).bankClaims().custodyBeneficial().toPlainString());
            assertEquals("10.00", account(w, PERSON, PERSON).path("buckets").path(PERSONAL_CUSTODY).asText());

            applied(w, MANAGER, "fund-custody", MOVE, move(PRODUCT, FUND, FUND_CUSTODY, "70.00"));
            applied(w, MANAGER, "fund-to-deposit", MOVE, move(PRODUCT, FUND_CUSTODY, FUND_DEPOSIT, "40.00"));
            applied(w, MANAGER, "fund-withdraw", "bank.cash.withdraw", cash(PRODUCT, "10.00"));
            assertEquals("9940.00", w.valuation(PRODUCT).cash().toPlainString());
            assertEquals("30.00", w.valuation(PRODUCT).bankClaims().deposits().toPlainString());
            assertEquals("30.00", w.valuation(PRODUCT).bankClaims().custodyBeneficial().toPlainString());
            assertEquals("15750.00", w.valuation(PRODUCT).netWorth().toPlainString());

            assertNull(decide(w, MANAGER, "wrong-owner", MOVE, move(PERSON, INV, PERSONAL_CUSTODY, "1.00")));
            assertNull(decide(w, MANAGER, "external-pay", MOVE, move(PRODUCT, FUND, INV, "1.00")));
            assertNull(decide(w, MANAGER, "wrong-bank", MOVE, move(PRODUCT, FUND, FUND_CUSTODY, "1.00").put("bank", "other-bank")));
            assertNull(decide(w, PERSON, "foreign-target", MOVE, move(PERSON, INV, FUND_CUSTODY, "1.00")));
            assertNull(decide(w, PERSON, "guessed-bucket", MOVE, move(PERSON, "CASH|UNREGISTERED", PERSONAL_CUSTODY, "1.00")));
            var shortfall = execute(w, MANAGER, "shortfall", MOVE, move(PRODUCT, FUND_CUSTODY, FUND, "99.00"));
            assertEquals("REJECTED", shortfall.path("execution").asText(), shortfall.toString());
            var ref = decide(w, MANAGER, "leave-during-transfer", MOVE, move(PRODUCT, FUND_CUSTODY, FUND, "1.00"));
            assertNotNull(ref);
            w.advanceTo(ref.dueMillis());
            w.scheduleRevokeAppointment("leave-fund", "leave-fund-work", ref.dueMillis()+1, delegated.path("appointmentId").asText());
            w.advanceTo(ref.dueMillis()+60_000);
            assertFalse(tool(view(w, MANAGER), MOVE));
            assertFalse(credit(w, MANAGER).has("ownerAccounts"));
            assertEquals("REJECTED", view(w, MANAGER).at("/engineering/deliberation/lastActionResult/execution").asText());
            lastPosition = w.snapshot().position();
        }
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(journal, null)) {
            assertEquals(lastPosition, w.snapshot().position());
            assertEquals("9940.00", w.valuation(PRODUCT).cash().toPlainString());
            assertEquals("30.00", w.valuation(PRODUCT).bankClaims().custodyBeneficial().toPlainString());
            assertEquals("4980.00", account(w, PERSON, PERSON).path("buckets").path(INV).asText());
            assertFalse(tool(view(w, MANAGER), MOVE));
        }
    }

    @Test void personalContractRequiresSeparateBankApprovalAndOnlyOwnerSeesTheirDebt() throws Exception {
        var journal = dir.resolve("personal-credit.journal");
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(journal, DRAFT)) {
            appoint(w, BANK, BANKER);
            assertFalse(tool(view(w, PERSON), "bank.loan.approve"));
            assertFalse(tool(view(w, BANKER), MOVE));
            assertNull(decide(w, PERSON, "different-borrower", "bank.loan.request", terms("foreign", "100.00").put("borrower", PRODUCT)));
            applied(w, PERSON, "personal-application", "bank.loan.request", terms("personal", "200.00").put("borrower", PERSON));
            assertEquals("PROPOSED", w.bankBook().loan("personal").status().name());
            assertNull(decide(w, PERSON, "unseen-contract", "bank.loan.draw", loan("not-seen")));
            assertFalse(credit(w, MANAGER).path("contracts").toString().contains("personal"));
            var early = execute(w, BANKER, "before-accept", "bank.loan.approve", loan("personal"));
            assertEquals("REJECTED", early.path("execution").asText(), early.toString());
            applied(w, PERSON, "own-accept", "bank.loan.accept", loan("personal"));
            assertEquals("0.00", w.valuation(PERSON).bankClaims().loanPrincipal().toPlainString());
            applied(w, BANKER, "bank-approves", "bank.loan.approve", loan("personal"));
            applied(w, PERSON, "personal-draw", "bank.loan.draw", loan("personal"));
            assertEquals("200.00", w.valuation(PERSON).bankClaims().loanPrincipal().toPlainString());
            assertEquals("200.00", account(w, PERSON, PERSON).path("buckets").path(PERSONAL_DEPOSIT).asText());
            applied(w, PERSON, "personal-repay", "bank.loan.repay", loan("personal").put("amount", "70.00"));
            assertEquals("130.00", w.valuation(PERSON).bankClaims().loanPrincipal().toPlainString());
        }
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(journal, null)) {
            assertEquals("130.00", w.valuation(PERSON).bankClaims().deposits().toPlainString());
            assertEquals("0.00", w.valuation(PRODUCT).bankClaims().loanPrincipal().toPlainString());
            assertTrue(w.autonomyInputLimitViolation(PERSON).isEmpty());
        }
    }

    @Test void modelSeesOnlyCashLeftAfterTheOpenBuyOrderAndCannotSpendItsFreeze() throws Exception {
        var file = dir.resolve("held.journal");
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(file, DRAFT)) {
            long open = 570L * 60_000L + 1;
            w.scheduleOrder("hold", "hold-work", open, PERSON, "INV", "CUFE-A", Side.BUY, OrderType.LIMIT, new BigDecimal("10.00"), 10);
            w.advanceTo(open);
            var order = w.snapshot().exchange().orElseThrow().reports().get("hold-work");
            assertTrue(order.accepted(), order.reason());
            assertEquals("4900.00", account(w, PERSON, PERSON).path("buckets").path(INV).asText());
            assertEquals("5000.00", w.valuation(PERSON).cash().toPlainString());
            var refused = execute(w, PERSON, "overspend-held-cash", MOVE, move(PERSON, INV, PERSONAL_CUSTODY, "4901.00"));
            assertEquals("REJECTED", refused.path("execution").asText(), refused.toString());
            assertEquals("0.00", w.valuation(PERSON).bankClaims().custodyBeneficial().toPlainString());
            long cancel = w.snapshot().timeMillis()+1;
            w.scheduleCancelOrder("release", "release-work", cancel, PERSON, order.orderId());
            w.advanceTo(cancel);
            assertEquals("5000.00", account(w, PERSON, PERSON).path("buckets").path(INV).asText());
        }
    }

    @Test void previousArchiveHasNoOwnerBankDecisionContract() throws Exception {
        var file = dir.resolve("previous.journal");
        try (var w = ComposedIndustryScenario.openOpeningCopiesOperating(file, DRAFT)) {
            for (var person : List.of(PERSON, MANAGER)) {
                assertFalse(credit(w, person).has("ownerAccounts"));
                assertFalse(tool(view(w, person), MOVE));
                assertFalse(tool(view(w, person), "bank.cash.deposit"));
                assertFalse(tool(view(w, person), "bank.loan.request"));
            }
        }
        try (var previous = ComposedIndustryScenario.openOpeningCopiesOperating(file, null)) {
            assertFalse(tool(view(previous, PERSON), MOVE));
        }
    }
}
