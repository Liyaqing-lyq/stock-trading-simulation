package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.RunManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** 普通试跑入口须选择独立的原料交期版本，旧经营档按原规则重开。 */
class AgedOrderProfileTest {
    @TempDir Path dir;

    @Test void newProfileInheritsBudgetRulesAndCannotReopenAnOldJournal() throws Exception {
        Path previous = dir.resolve("previous.journal");
        Map<String, String> previousRules;
        String previousObservation;
        try (var world = AutonomousLifecycleProbe.openWorld("FORWARD_BUDGET_OPERATING", previous)) {
            previousRules = world.profile().manifest().dataVersions();
            previousObservation = world.autonomyRoleObservation("aged-company-director-1").toString();
        }
        byte[] original = Files.readAllBytes(previous);
        assertThrows(Exception.class, () -> AutonomousLifecycleProbe.openWorld("AGED_ORDER_OPERATING", previous));
        try (var world = AutonomousLifecycleProbe.openWorld("FORWARD_BUDGET_OPERATING", previous)) {
            assertEquals(previousObservation, world.autonomyRoleObservation("aged-company-director-1").toString());
        }
        assertArrayEquals(original, Files.readAllBytes(previous));

        Path current = dir.resolve("current.journal");
        try (var world = AutonomousLifecycleProbe.openWorld("AGED_ORDER_OPERATING", current)) {
            var manifest = world.profile().manifest();
            assertEquals(RunManifest.AGED_ORDER_RUN_ID, manifest.runId());
            var rules = new TreeMap<>(manifest.dataVersions());
            assertEquals("next-period-v1", rules.remove("aged-order-terms"));
            assertEquals(previousRules, rules);
            var mapper = new ObjectMapper();
            var companies = mapper.valueToTree(OperationsWeb.view(world, null)).required("companies");
            var aged = java.util.stream.StreamSupport.stream(companies.spliterator(), false)
                    .filter(row -> row.path("kind").asText().equals("AGED")).findFirst().orElseThrow();
            assertEquals(1, aged.at("/plan/orderTerms/earliestReceiptAfterPeriods").intValue());
            assertFalse(mapper.valueToTree(OperationsWeb.view(world, "investor")).toString().contains("orderTerms"));
        }
        byte[] created = Files.readAllBytes(current);
        assertThrows(Exception.class, () -> AutonomousLifecycleProbe.openWorld("FORWARD_BUDGET_OPERATING", current));
        assertArrayEquals(created, Files.readAllBytes(current));
    }
}
