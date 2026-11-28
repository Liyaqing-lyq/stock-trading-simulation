package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实经理签约完成时复核客户资金，保留已承诺款项和旧版重演。 */
class FundedManufacturingContractTest {
    @TempDir Path dir;
    private static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;

    @Test void buyerSpendsAfterManagerSchedulesOnlyOldContractCanStillSign() throws Exception {
        for (boolean funded : List.of(true, false)) {
            Path journal = dir.resolve(funded ? "funded.journal" : "old.journal");
            WorldRuntime.Snapshot end;
            try (var world = open(journal, funded)) {
                appoint(world);
                var initial = total(world);
                long now = world.snapshot().timeMillis();
                var offer = world.autonomyRoleObservation(TECH_MANAGER).at("/engineering/companyOperations/0/customerOffers/0");
                assertTrue(offer.path("quantity").asLong() > 0);
                scheduleSign(world, "sale", "160.00", now + 2000);
                leaveBuyerCash(world, "0.00");
                assertEquals(0, world.autonomyRoleObservation(TECH_MANAGER).at("/engineering/companyOperations/0/customerOffers/0/quantity").longValue());
                world.advanceTo(now + 2000);
                var run = world.operationsRun().orElseThrow();
                assertEquals(funded ? "CUSTOMER_AVAILABLE_CASH_INSUFFICIENT" : "SIGNED", run.report("sale-task:finish").reason());
                assertEquals(!funded, run.company(TECH).ledger().contracts().containsKey("sale"));
                assertEquals(initial, total(world));
                end = world.snapshot();
            }
            try (var world = open(journal, funded)) {
                assertEquals(end, world.snapshot());
                assertEquals(!funded, world.operationsRun().orElseThrow().company(TECH).ledger().contracts().containsKey("sale"));
            }
            assertThrows(Exception.class, () -> open(journal, !funded));
        }
    }

    @Test void secondContractCanUseOnlyTheUncommittedBalanceAtItsActualPrice() throws Exception {
        Path journal = dir.resolve("competing.journal");
        try (var world = open(journal, true)) {
            appoint(world);
            leaveBuyerCash(world, "180.00");
            assertEquals("SIGNED", sign(world, "first", "100.00"));
            assertEquals("CUSTOMER_AVAILABLE_CASH_INSUFFICIENT", sign(world, "over", "80.01"));
            assertEquals("SIGNED", sign(world, "exact", "80.00"));
            assertEquals(new BigDecimal("180.00"), world.snapshot().accounts().get(CUST_AUTO).cash());
            assertEquals(2, world.operationsRun().orElseThrow().company(TECH).ledger().contracts().size());
        }
        try (var world = open(journal, true)) {
            assertEquals("CUSTOMER_AVAILABLE_CASH_INSUFFICIENT", sign(world, "after-reopen", "0.01"));
        }
    }

    @Test void fundedPolicyAndRunIdMustBeFrozenTogether() throws Exception {
        try(var world = open(dir.resolve("profile.journal"), true)) {
            var manifest = world.profile().manifest();
            assertEquals(RunManifest.FUNDED_CONTRACT_POLICY, manifest.dataVersions().get("customer-contract-policy"));
            assertThrows(IllegalArgumentException.class, () -> new RunManifest("web-thinking-operating-v1", manifest.seed(),
                    manifest.rulesVersion(), manifest.moduleVersions(), manifest.dataVersions(), manifest.modelConfigVersion()));
            var data = new TreeMap<>(manifest.dataVersions());
            data.remove("customer-contract-policy");
            assertThrows(IllegalArgumentException.class, () -> new RunManifest(manifest.runId(), manifest.seed(),
                    manifest.rulesVersion(), manifest.moduleVersions(), data, manifest.modelConfigVersion()));
        }
    }

    @Test void pastPeriodObligationSurvivesDeliveryAndCollectionStillDependsOnActualCash() throws Exception {
        Path file = dir.resolve("receivable.journal");
        try (var w = open(file, true)) {
            appoint(w);
            BigDecimal initial = total(w);
            leaveBuyerCash(w, "200.00");
            assertTrue(act(w, "po", "ORDER_MATERIAL", OperationsFixture.op().material("MAT-A", 2).build()).accepted());
            var company = w.operationsRun().orElseThrow().company(TECH);
            w.advanceTo(company.ledger().purchases().get("po:po").arrivalAt());
            assertTrue(act(w, "batch", "START_BATCH", OperationsFixture.op().product("P1", 1, 1).build()).accepted());
            w.advanceTo(29 * DAY);
            assertEquals("SIGNED", sign(w, "before-close", "100.00"));
            w.advanceTo(30 * DAY + 2000);
            assertEquals("CUSTOMER_AVAILABLE_CASH_INSUFFICIENT", sign(w, "over-before-delivery", "100.01"));
            assertTrue(act(w, "delivery", "DELIVER", OperationsFixture.op().delivery("before-close", 1, "invoice").build()).accepted());
            assertEquals("CUSTOMER_AVAILABLE_CASH_INSUFFICIENT", sign(w, "over-after-delivery", "100.01"));
            assertEquals("SIGNED", sign(w, "exact-after-delivery", "100.00"));
            var invoice = w.operationsRun().orElseThrow().company(TECH).ledger().receivables().get("invoice");
            w.advanceTo(invoice.dueAt());
            leaveBuyerCash(w, "0.00");
            var companyCash = w.snapshot().accounts().get(TECH_CASH).cash();
            assertFalse(act(w, "collect", "COLLECT", OperationsFixture.op().receivable("invoice").build()).accepted());
            assertEquals(invoice, w.operationsRun().orElseThrow().company(TECH).ledger().receivables().get("invoice"));
            assertEquals(companyCash, w.snapshot().accounts().get(TECH_CASH).cash());
            assertEquals(initial, total(w));
        }
    }

    private static WorldRuntime open(Path journal, boolean funded) throws Exception {
        return funded ? ComposedIndustryScenario.openFundedThinkingOperating(journal) : ComposedIndustryScenario.openThinkingInformedOperating(journal);
    }
    private static void appoint(WorldRuntime world) throws Exception {
        OperationsFixture.grantBudgetAndAppoint(world, TECH, TECH_DIRECTORS, TECH_MANAGER, "terms", List.of(0L));
    }
    private static void leaveBuyerCash(WorldRuntime world, String amount) throws Exception {
        var account = world.snapshot().accounts().get(CUST_AUTO);
        long due = world.snapshot().timeMillis() + 10;
        String id = "customer-purchase-" + due;
        world.scheduleTransfer(id, id + "-task", due, account.ownerId(), CUST_AUTO,
                SUP_METAL, account.cash().subtract(new BigDecimal(amount)), 1);
        world.advanceTo(due + 1);
    }
    private static void scheduleSign(WorldRuntime world, String id, String price, long due) throws Exception {
        world.scheduleOperationsAction(id, id + "-task", due, TECH, TECH_MANAGER, "SIGN_CONTRACT",
                OperationsFixture.op().contract(id, "auto-oem", "P1", 1, 1, new BigDecimal(price), due + DAY, due + 2 * DAY).build(), 1000);
    }
    private static String sign(WorldRuntime world, String id, String price) throws Exception {
        long due = world.snapshot().timeMillis() + 2000;
        scheduleSign(world, id, price, due);
        world.advanceTo(due);
        return world.operationsRun().orElseThrow().report(id + "-task:finish").reason();
    }
    private static CompanyOperationsRun.ReportEntry act(WorldRuntime world, String id, String action, CompanyOperationsRun.Fields fields) throws Exception {
        long due = world.snapshot().timeMillis() + 2000;
        world.scheduleOperationsAction(id, id + "-task", due, TECH, TECH_MANAGER, action, fields, 1000);
        world.advanceTo(due);
        return world.operationsRun().orElseThrow().report(id + "-task:finish");
    }
    private static BigDecimal total(WorldRuntime world) {
        return world.snapshot().accounts().values().stream().map(CashLedger.CashAccount::cash).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
