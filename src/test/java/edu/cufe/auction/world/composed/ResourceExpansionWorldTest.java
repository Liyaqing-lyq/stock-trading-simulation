package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static edu.cufe.auction.world.composed.ComposedIndustryScenario.*;
import static edu.cufe.auction.world.composed.ResourceWorldChainTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceExpansionWorldTest {
    @TempDir Path dir;

    @Test void realBudgetPaysFrozenQuoteThenReopensIntoActualRamp() throws Exception {
        Path file = dir.resolve("expand.journal"); long completes;
        try (var world = openExpanded(file)) {
            OperationsFixture.grantBudgetAndAppoint(world, RESOURCE, DIRECTORS, MANAGER, "exp", List.of(0L));
            act(world, "build", request(ResourceOperations.Action.START_EXPANSION, "projectId", "mine-2"));
            assertEquals(new BigDecimal("8000.00"), world.snapshot().accounts().get(CASH).cash());
            assertEquals(new BigDecimal("2000.00"), world.snapshot().accounts().get("RES-CONTRACTOR").cash());
            assertEquals(new BigDecimal("2000.00"), world.resourceStatement().orElseThrow().cip());
            assertEquals(1000L, IntegratedResourcesModel.miningCapacityAt(resource(world), 0));
            completes = resource(world).expansions().get("mine-2").completesAt();
            String input = ResourceDecisionTools.observation(world.operationsRun().orElseThrow().resource(),
                    world.companyGovernance().orElseThrow().boardOf(RESOURCE), world.snapshot().timeMillis(),
                    world.snapshot().accounts().get(CASH).cash(), List.of()).toString();
            assertFalse(input.contains("failed")); assertFalse(input.contains("coalBudgetPerPeriod"));
            assertTrue(ComposedCompanyReport.of(world, RESOURCE).balanced());
        }
        try (var world = openExpanded(file)) {
            world.advanceTo(completes - 1);
            assertEquals(IntegratedResourcesModel.ExpansionStatus.BUILDING, resource(world).expansions().get("mine-2").status());
            world.advanceTo(completes);
            assertEquals(IntegratedResourcesModel.ExpansionStatus.ONLINE, resource(world).expansions().get("mine-2").status());
            assertEquals(new BigDecimal("0.00"), world.resourceStatement().orElseThrow().cip());
            assertEquals(1000L, IntegratedResourcesModel.miningCapacityAt(resource(world), 1));
            assertEquals(1200L, IntegratedResourcesModel.miningCapacityAt(resource(world), 2));
            assertEquals(1400L, IntegratedResourcesModel.miningCapacityAt(resource(world), 3));
            assertEquals(1600L, IntegratedResourcesModel.miningCapacityAt(resource(world), 4));
            assertTrue(ComposedCompanyReport.of(world, RESOURCE).balanced());
        }
        try (var world = openExpanded(file)) { assertTrue(ComposedCompanyReport.of(world, RESOURCE).balanced()); }
    }

    @Test void quoteIsNotBudgetAndRejectionLeavesBankAndCashUntouched() throws Exception {
        try (var world = openExpanded(dir.resolve("no-budget.journal"))) {
            OperationsFixture.grantBudgetAndAppoint(world, RESOURCE, DIRECTORS, MANAGER, "exp-no-budget", List.of(0L));
            world.advanceTo(IntegratedResourcesModel.PERIOD_MILLIS);
            var before = world.snapshot().accounts(); var bank = world.bankOf().orElseThrow(); var ledger = resource(world);
            long due = world.snapshot().timeMillis() + 2000;
            world.openRoleSession(MANAGER).resourceAction("build", "build-task", due, RESOURCE,
                    request(ResourceOperations.Action.START_EXPANSION, "projectId", "mine-2"), 1000);
            world.advanceTo(due);
            assertFalse(world.operationsRun().orElseThrow().report("build-task:finish").accepted());
            assertEquals(before, world.snapshot().accounts()); assertEquals(bank, world.bankOf().orElseThrow());
            assertEquals(ledger, resource(world));
        }
    }

    @Test void originalIndustryProfileStaysWithoutExpansionOrSelfReportedResult() throws Exception {
        try (var world = open(dir.resolve("old.journal"))) {
            assertNull(world.operationsRun().orElseThrow().resource().expansionOffer());
            OperationsFixture.grantBudgetAndAppoint(world, RESOURCE, DIRECTORS, MANAGER, "old-exp", List.of(0L));
            long due = world.snapshot().timeMillis() + 2000;
            world.openRoleSession(MANAGER).resourceAction("build", "build-task", due, RESOURCE,
                    request(ResourceOperations.Action.START_EXPANSION, "projectId", "mine-2"), 1000);
            world.advanceTo(due);
            assertEquals("EXPANSION_NOT_ENABLED", world.operationsRun().orElseThrow().report("build-task:finish").reason());
            assertEquals(new BigDecimal("10000.00"), world.snapshot().accounts().get(CASH).cash());
        }
        assertThrows(IllegalArgumentException.class, () -> new ResourceOperations.Request(ResourceOperations.Action.START_EXPANSION,
                Map.of("projectId", "fake", "capex", "0.01", "failed", "false")));
        assertThrows(IllegalArgumentException.class, () -> ResourceDecisionTools.request(new ResourceDecisionTools.Draft("COMPLETE_EXPANSION",
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("projectId", "fake")))));
    }
}
