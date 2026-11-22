package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.ManufacturingTemplateModel;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

class ForwardBudgetViewTest {
    @TempDir Path dir;
    private static final long PERIOD = ManufacturingTemplateModel.PERIOD_MILLIS;

    @Test void roleWindowFollowsEnactedLimitsSpendingAndAppointmentTerm() throws Exception {
        Path journal = dir.resolve("new.journal");
        WorldRuntime.Snapshot terminal;
        try (var w = ComposedIndustryScenario.openForwardBudgetOperating(journal)) {
            assertTrue(w.autonomyRoleObservation(TECH_MANAGER).at("/engineering/managerships").isEmpty());
            JsonNode empty = window(w, TECH_DIRECTORS.get(0), "directorships", TECH);
            assertTrue(empty.at("/periods/0/lines").isArray());
            assertTrue(empty.at("/periods/0/lines").isEmpty());
            assertTrue(empty.at("/periods/1/lines").isEmpty());
            long start = OperationsFixture.grantBudgetAndAppoint(w, TECH, TECH_DIRECTORS, TECH_MANAGER,
                    "budget-window", List.of(0L, 1L));
            long due = start + 2000;
            w.scheduleOperationsAction("buy", "buy-task", due, TECH, TECH_MANAGER, "ORDER_MATERIAL",
                    OperationsFixture.op().material("MAT-A", 2L).build(), 1000);
            w.advanceTo(due + 1000);
            assertTrue(w.operationsRun().orElseThrow().report("buy-task:finish").accepted());
            JsonNode manager = window(w, TECH_MANAGER, "managerships", TECH);
            assertEquals(manager, window(w, TECH_DIRECTORS.get(0), "directorships", TECH));
            assertEquals(2, manager.path("periods").size());
            assertEquals(0, manager.at("/periods/0/startsAtMillis").longValue());
            assertEquals(PERIOD, manager.at("/periods/0/endsAtExclusiveMillis").longValue());
            assertEquals(PERIOD, manager.at("/periods/1/startsAtMillis").longValue());
            assertEquals(2 * PERIOD, manager.at("/periods/1/endsAtExclusiveMillis").longValue());
            var board = w.boardOfCompany(TECH).orElseThrow().board();
            var current = BoardModel.availableBudget(board, 0, BoardModel.BudgetCategory.PROCUREMENT);
            assertTrue(current.compareTo(new BigDecimal("1000000.00")) < 0);
            assertEquals(current.toPlainString(), line(manager.at("/periods/0"), "PROCUREMENT").path("available").asText());
            assertEquals("1000000.00", line(manager.at("/periods/0"), "PROCUREMENT").path("cap").asText());
            assertEquals("1000000.00", line(manager.at("/periods/1"), "PROCUREMENT").path("available").asText());
            assertTrue(window(w, STRUCT_DIRECTORS.get(0), "directorships", STRUCT).at("/periods/1/lines").isEmpty());
            w.advanceTo(PERIOD);
            var second = window(w, TECH_MANAGER, "managerships", TECH);
            assertEquals(1, second.at("/periods/0/period").longValue());
            assertEquals(2, second.at("/periods/1/period").longValue());
            assertTrue(second.at("/periods/1/lines").isArray());
            assertTrue(second.at("/periods/1/lines").isEmpty());
            w.advanceTo(start + 3 * PERIOD);
            assertTrue(w.autonomyRoleObservation(TECH_MANAGER).at("/engineering/managerships").isEmpty());
            terminal = w.snapshot();
        }
        try (var w = ComposedIndustryScenario.openForwardBudgetOperating(journal)) {
            assertEquals(terminal, w.snapshot());
        }
    }

    @Test void earlierModeKeepsItsManifestAndRoleInput() throws Exception {
        Path old = dir.resolve("old.journal");
        var oldData = new TreeMap<String,String>();
        String input;
        try (var w = ComposedIndustryScenario.openCompanyInformationOperating(old)) {
            oldData.putAll(w.profile().manifest().dataVersions());
            input = w.autonomyRoleObservation(TECH_DIRECTORS.get(0)).toString();
            assertFalse(input.contains("budgetWindow"));
        }
        var bytes = Files.readAllBytes(old);
        assertThrows(Exception.class, () -> ComposedIndustryScenario.openForwardBudgetOperating(old));
        try (var w = ComposedIndustryScenario.openCompanyInformationOperating(old)) {
            assertEquals(input, w.autonomyRoleObservation(TECH_DIRECTORS.get(0)).toString());
        }
        assertArrayEquals(bytes, Files.readAllBytes(old));
        try (var w = ComposedIndustryScenario.openForwardBudgetOperating(dir.resolve("new.journal"))) {
            var versions = new TreeMap<>(w.profile().manifest().dataVersions());
            assertEquals("period-window-v1", versions.remove("forward-budget-view"));
            oldData.forEach((key,value) -> assertEquals(value, versions.get(key), key));
        }
    }

    private static JsonNode window(WorldRuntime world, String person, String roles, String company) throws java.io.IOException {
        for (var role : world.autonomyRoleObservation(person).path("engineering").path(roles)) {
            if (company.equals(role.path("companyPartyId").asText())) return role.required("budgetWindow");
        }
        throw new AssertionError("Missing role for " + company);
    }

    private static JsonNode line(JsonNode period, String category) {
        for (var line : period.path("lines")) if (category.equals(line.path("category").asText())) return line;
        throw new AssertionError("Missing budget line for " + category);
    }
}
