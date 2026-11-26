package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.bank.BankActions;
import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FixedFundCapitalTest {
    @TempDir Path dir;

    @Test void ordinaryTransfersRespectTheProductBoundaryAndOtherHouseholdsCanStillPay() throws Exception {
        var file = dir.resolve("cash.journal");
        try (var world = ComposedIndustryScenario.openManufacturingLifecycleOperating(file)) {
            var before = world.snapshot();
            var nina = before.accounts().get("HOUSE-NINA");
            long due = before.timeMillis() + 1;
            var amount = new BigDecimal("3.00");
            var deposit = assertThrows(IllegalArgumentException.class, () -> world.scheduleTransfer(
                    "in", "in-task", due, nina.ownerId(), "HOUSE-NINA", "ACC-FUND", amount, 1));
            var withdraw = assertThrows(IllegalArgumentException.class, () -> world.scheduleTransfer(
                    "out", "out-task", due, "fund-a", "ACC-FUND", "HOUSE-NINA", amount, 1));
            assertTrue(deposit.getMessage().contains("固定资本基金"));
            assertTrue(withdraw.getMessage().contains("固定资本基金"));
            assertEquals(before.position(), world.snapshot().position());
            world.scheduleTransfer("ordinary", "ordinary-task", due, nina.ownerId(), "HOUSE-NINA", "HOUSE-OMAR", amount, 1);
            world.advanceTo(due + 1);
            assertEquals(nina.cash().subtract(amount), world.snapshot().accounts().get("HOUSE-NINA").cash());
            assertEquals(before.accounts().get("ACC-FUND"), world.snapshot().accounts().get("ACC-FUND"));
        }
        try (var restored = ComposedIndustryScenario.openManufacturingLifecycleOperating(file)) {
            assertEquals(new BigDecimal("10000.00"), restored.snapshot().accounts().get("ACC-FUND").cash());
        }
        try (var previous = ComposedIndustryScenario.openIntegratedOperating(dir.resolve("old.journal"))) {
            long due = previous.snapshot().timeMillis() + 1;
            previous.scheduleTransfer("legacy", "legacy-task", due, previous.snapshot().accounts().get("HOUSE-NINA").ownerId(),
                    "HOUSE-NINA", "ACC-FUND", BigDecimal.ONE, 1);
            previous.advanceTo(due + 1);
            assertEquals(new BigDecimal("10001.00"), previous.snapshot().accounts().get("ACC-FUND").cash());
        }
    }

    @Test void rolePaymentCannotPutPersonalMoneyIntoAFixedFund() throws Exception {
        var file = dir.resolve("bank-pay.journal");
        try (var world = ComposedIndustryScenario.openManufacturingLifecycleOperating(file)) {
            var before = world.snapshot();
            var amount = new BigDecimal("3.00");
            var gift = new WorldRoleSession.BankActionDraft("pay-fund", BankActions.Kind.PAY, "north",
                    "", amount, 0L, 0L, "", "CASH|HOUSE-NINA", "CASH|ACC-FUND", "");
            long due = before.timeMillis() + 1001;
            world.openRoleSession("nina").bankAction("pay-in", "pay-task", due, 1000, gift);
            world.advanceTo(due);
            assertEquals(before.accounts(), world.snapshot().accounts());
            assertTrue(world.bankActionAudits().stream().anyMatch(a -> !a.accepted()
                    && "PAY".equals(a.kind()) && "FIXED_FUND_CAPITAL".equals(a.reason())));
            var payment = new WorldRoleSession.BankActionDraft("pay-other", BankActions.Kind.PAY, "north",
                    "", amount, 0L, 0L, "", "CASH|HOUSE-NINA", "CASH|HOUSE-OMAR", "");
            world.openRoleSession("nina").bankAction("pay-household", "pay-other-task", due + 1001, 1000, payment);
            world.advanceTo(due + 1001);
            assertEquals(before.accounts().get("HOUSE-OMAR").cash().add(amount),
                    world.snapshot().accounts().get("HOUSE-OMAR").cash());
        }
        try (var restored = ComposedIndustryScenario.openManufacturingLifecycleOperating(file)) {
            assertEquals(new BigDecimal("10000.00"), restored.snapshot().accounts().get("ACC-FUND").cash());
        }
    }

    @Test void rejectsBothCapitalDirectionsAndReopensWithOriginalProductCash() throws Exception {
        var journal = dir.resolve("fixed.journal");
        long position;
        try (var world = ComposedIndustryScenario.openManufacturingLifecycleOperating(journal)) {
            var before = world.snapshot();
            var accounts = before.accounts();
            var one = new BigDecimal("1.00");
            var household = accounts.get("HOUSE-NINA").ownerId();
            assertEquals(RunManifest.FIXED_FUND_CAPITAL, world.profile().manifest().dataVersions().get("fund-capital-policy"));
            var incoming = assertThrows(IllegalArgumentException.class, () -> world.scheduleCapitalFlow(
                    "in", "flow-in", before.timeMillis() + 1, household, "HOUSE-NINA", "ACC-FUND", one, 1L, "追加"));
            assertTrue(incoming.getMessage().contains("固定资本"));
            var outgoing = assertThrows(IllegalArgumentException.class, () -> world.scheduleCapitalFlow(
                    "out", "flow-out", before.timeMillis() + 1, "fund-a", "ACC-FUND", "HOUSE-NINA", one, 1L, "赎回"));
            assertTrue(outgoing.getMessage().contains("固定资本"));
            assertEquals(before.position(), world.snapshot().position());
            assertEquals(accounts, world.snapshot().accounts());
            var doc = "fund-mandate:cufe:fund-a";
            world.openRoleSession("bob").startReading("read", "read-task", before.timeMillis()+1, doc, 1);
            world.advanceTo(before.timeMillis()+1);
            world.advanceTo(world.roleView("bob").readings().get(0).dueAt());
            assertTrue(world.roleView("bob").observations().stream()
                    .anyMatch(o -> o.ref().docId().equals(doc) && o.body().contains("不受理投资者申购、赎回")));
            position = world.snapshot().position();
        }
        try (var reopened = ComposedIndustryScenario.openManufacturingLifecycleOperating(journal);
             var prior = ComposedIndustryScenario.openIntegratedOperating(dir.resolve("prior.journal"))) {
            assertFalse(prior.profile().manifest().dataVersions().containsKey("fund-capital-policy"));
            assertEquals(position, reopened.snapshot().position());
            assertEquals(prior.snapshot().accounts().get("ACC-FUND"), reopened.snapshot().accounts().get("ACC-FUND"));
            assertTrue(reopened.snapshot().decisions().orElseThrow().state().decisions().isEmpty());
        }
    }
}
