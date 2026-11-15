package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static edu.cufe.auction.world.composed.ComposedIndustryScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** 资源公司的真实世界链；内部转移不增现金，发电完成才形成外部收入。 */
class ResourceWorldChainTest {
    @TempDir Path dir;

    @Test void extractionCoalSalePowerDeliveryAndCollectionsCloseTogether() throws Exception {
        Path journal = dir.resolve("resource.journal");
        try (var world = ComposedIndustryScenario.open(journal)) {
            assertEquals(9, world.openingRegistry().orElseThrow().securities().size());
            assertTrue(ComposedCompanyReport.of(world, RESOURCE).balanced());
            assertThrows(IllegalArgumentException.class, () -> world.openRoleSession(MANAGER).resourceAction("no-role", "no-role", 2000,
                    RESOURCE, request(ResourceOperations.Action.BEGIN_EXTRACTION, "jobId", "mine", "seamId", "resource-seam-a", "quantityKg", "400"), 1000));
            OperationsFixture.grantBudgetAndAppoint(world, RESOURCE, DIRECTORS, MANAGER, "resource", List.of(0L, 1L));
            act(world, "extract", request(ResourceOperations.Action.BEGIN_EXTRACTION, "jobId", "mine", "seamId", "resource-seam-a", "quantityKg", "400"));
            assertEquals(0L, resource(world).mineCoal().quantity());
            assertEquals(new BigDecimal("400.00"), world.resourceStatement().orElseThrow().wip());
        }
        try (var world = ComposedIndustryScenario.open(journal)) {
            world.advanceTo(resource(world).jobs().get("mine").dueAt());
            assertEquals(400L, resource(world).mineCoal().quantity());
            act(world, "coal", request(ResourceOperations.Action.SELL_COAL, "deliveryId", "sale", "quantityKg", "100", "receivableId", "coal-ar"));
            BigDecimal before = world.snapshot().accounts().get(CASH).cash();
            act(world, "transfer", request(ResourceOperations.Action.TRANSFER_TO_PLANT, "transferId", "transfer", "quantityKg", "200"));
            assertEquals(before, world.snapshot().accounts().get(CASH).cash());
            assertEquals(new BigDecimal("300.00"), world.resourceStatement().orElseThrow().revenue());
            act(world, "power", request(ResourceOperations.Action.BEGIN_POWER, "jobId", "power", "mwh", "1", "receivableId", "power-ar"));
            assertEquals(new BigDecimal("250.00"), world.resourceStatement().orElseThrow().wip());
            world.advanceTo(resource(world).jobs().get("power").dueAt());
            assertEquals(new BigDecimal("1100.00"), world.resourceStatement().orElseThrow().revenue());
            long due = resource(world).receivables().values().stream().mapToLong(IntegratedResourcesModel.Receivable::dueAt).max().orElseThrow();
            world.advanceTo(due);
            act(world, "collect-coal", request(ResourceOperations.Action.COLLECT, "receivableId", "coal-ar"));
            act(world, "collect-power", request(ResourceOperations.Action.COLLECT, "receivableId", "power-ar"));
            assertEquals(new BigDecimal("10650.00"), world.snapshot().accounts().get(CASH).cash());
            assertTrue(ComposedCompanyReport.of(world, RESOURCE).balanced());
            world.advanceTo(IntegratedResourcesModel.PERIOD_MILLIS);
            assertEquals(1L, resource(world).closedPeriods());
            assertEquals(new BigDecimal("10550.00"), world.snapshot().accounts().get(CASH).cash());
            assertTrue(ComposedCompanyReport.of(world, RESOURCE).balanced());
        }
        try (var world = ComposedIndustryScenario.open(journal)) {
            assertEquals(1L, resource(world).closedPeriods());
            assertEquals(new BigDecimal("10550.00"), world.snapshot().accounts().get(CASH).cash());
            assertTrue(ComposedCompanyReport.of(world, RESOURCE).balanced());
        }
    }

    @Test void noManagerStillAccruesExistingEquipmentAndExpense() throws Exception {
        try (var world = ComposedIndustryScenario.open(dir.resolve("unmanned.journal"))) {
            world.advanceTo(2 * IntegratedResourcesModel.PERIOD_MILLIS);
            assertEquals(2L, resource(world).closedPeriods());
            assertEquals(new BigDecimal("9800.00"), world.snapshot().accounts().get(CASH).cash());
            assertEquals(new BigDecimal("19900.00"), world.resourceStatement().orElseThrow().equipmentNet());
            assertTrue(ComposedCompanyReport.of(world, RESOURCE).balanced());
        }
    }

    @Test void depositAndRejectedBudgetCannotCreateMoneyOrLoseBankReceipts() throws Exception {
        Path journal = dir.resolve("bank-budget.journal");
        try (var world = ComposedIndustryScenario.open(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, RESOURCE, DIRECTORS, MANAGER, "bank", List.of(0L));
            long due = world.snapshot().timeMillis() + 100;
            world.openRoleSession(MANAGER).bankAction("resource-deposit", "resource-deposit-task", due, 1,
                    new WorldRoleSession.BankActionDraft("resource-deposit", edu.cufe.auction.world.bank.BankActions.Kind.DEPOSIT,
                            "north", RESOURCE, new BigDecimal("100.00"), 0, 0, "", "", "", ""));
            world.advanceTo(due);
            assertEquals(new BigDecimal("9900.00"), world.snapshot().accounts().get(CASH).cash());
            assertEquals(new BigDecimal("30000.00"), ComposedCompanyReport.of(world, RESOURCE).totalAssets());
            world.advanceTo(IntegratedResourcesModel.PERIOD_MILLIS);
            var ledger = resource(world); var bank = world.bankOf().orElseThrow(); var cash = world.snapshot().accounts();
            due = world.snapshot().timeMillis() + 2000;
            world.openRoleSession(MANAGER).resourceAction("unbudgeted", "unbudgeted-task", due, RESOURCE,
                    request(ResourceOperations.Action.BEGIN_EXTRACTION, "jobId", "denied", "seamId", "resource-seam-a", "quantityKg", "10"), 1000);
            world.advanceTo(due);
            assertFalse(world.operationsRun().orElseThrow().report("unbudgeted-task:finish").accepted());
            assertEquals(ledger, resource(world)); assertEquals(bank, world.bankOf().orElseThrow()); assertEquals(cash, world.snapshot().accounts());
            assertTrue(ComposedCompanyReport.of(world, RESOURCE).balanced());
        }
        try (var world = ComposedIndustryScenario.open(journal)) { assertTrue(ComposedCompanyReport.of(world, RESOURCE).balanced()); }
    }

    @Test void modelSeesOnlyOwnStateAndStartsActualExtraction() throws Exception {
        Path journal = dir.resolve("model.journal");
        try (var world = ComposedIndustryScenario.open(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, RESOURCE, DIRECTORS, MANAGER, "model", List.of(0L));
            var call = PharmaDecisionToolTest.open(world, MANAGER, "resource-model");
            assertTrue(call.allowedTools().contains(ResourceDecisionTools.ACT));
            assertFalse(call.allowedTools().contains(CompanyOperationsDecisionTools.ACT));
            var view = world.engineeringRoleInput(MANAGER).path("resourceOperations");
            assertEquals(RESOURCE, view.path("companyId").asText());
            assertFalse(view.toString().contains("gridBudget")); assertFalse(view.toString().contains("mfg-tech"));
            long finish = PharmaDecisionToolTest.reply(world, call, ResourceDecisionTools.ACT,
                    Map.of("action", "BEGIN_EXTRACTION", "arguments", Map.of("jobId", "model-mine", "seamId", "resource-seam-a", "quantityKg", "100")));
            world.advanceTo(finish - 500); assertTrue(resource(world).jobs().isEmpty());
            world.advanceTo(finish); assertEquals(new BigDecimal("9900.00"), world.snapshot().accounts().get(CASH).cash());
            assertEquals(0L, resource(world).mineCoal().quantity());
        }
        try (var world = ComposedIndustryScenario.open(journal)) {
            world.advanceTo(resource(world).jobs().get("model-mine").dueAt());
            assertEquals(100L, resource(world).mineCoal().quantity());
            assertTrue(ComposedCompanyReport.of(world, RESOURCE).balanced());
        }
    }

    static IntegratedResourcesModel.State resource(WorldRuntime world) { return world.operationsRun().orElseThrow().resource().ledger(); }
    static ResourceOperations.Request request(ResourceOperations.Action action, String... fields) {
        Map<String, String> args = new java.util.TreeMap<>();
        for (int i = 0; i < fields.length; i += 2) args.put(fields[i], fields[i + 1]);
        return new ResourceOperations.Request(action, args);
    }
    static void act(WorldRuntime world, String id, ResourceOperations.Request request) throws Exception {
        long due = world.snapshot().timeMillis() + 2000;
        world.openRoleSession(MANAGER).resourceAction(id, id + "-task", due, RESOURCE, request, 1000);
        world.advanceTo(due);
        var report = world.operationsRun().orElseThrow().report(id + "-task:finish");
        assertNotNull(report);
        assertTrue(report.accepted(), report.reason());
    }
}
