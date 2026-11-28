package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 真实角色资料边界与恢复，不把测试指定动作当作模型行为。 */
class RoleEvidenceTest {
    @TempDir Path dir;

    @Test void authorityComesFromCharterWithoutGrantingEmployment() throws Exception {
        try (var world = ComposedIndustryScenario.openEvidenceAutonomous(dir.resolve("authority.journal"), true)) {
            var input = world.autonomyRoleObservation("mfgstruct-d1");
            assertEquals("authority-v1", input.path("engineering").path("roleContextVersion").asText());
            assertTrue(input.path("appointments").isEmpty());
            assertTrue(input.path("permissions").isEmpty());
            assertTrue(input.path("engineering").path("authorityBasis").asText().contains("章程"));
            assertTrue(input.path("engineering").path("directorships").get(0).path("chair").asBoolean());
            assertFalse(input.path("tools").toString().contains("company.operations.act"));
            assertFalse(input.path("profile").path("catalog").toString().contains("role-evidence:"));
        }
    }

    @Test void evidenceIsCompanyScopedRequiresReadingAndDoesNotPreapprove() throws Exception {
        try (var world = ComposedIndustryScenario.openEvidenceAutonomous(dir.resolve("evidence.journal"), false)) {
            var own = world.autonomyRoleObservation("mfgstruct-d1");
            String doc = "role-evidence:mfg-struct:1";
            assertTrue(own.path("profile").path("catalog").toString().contains(doc));
            assertFalse(own.path("profile").path("readExperiences").toString().contains("candidateDossiers"));
            assertFalse(world.autonomyRoleObservation("mfgtech-d1").toString().contains(doc));
            assertFalse(world.autonomyRoleObservation("mfgstruct-mgr").toString().contains(doc));
            assertTrue(own.path("engineering").path("directorships").get(0).path("managerVacant").asBoolean());
            assertTrue(world.boardOfCompany("mfg-struct").orElseThrow().board().budgets().isEmpty());
            assertTrue(world.boardOfCompany("mfg-struct").orElseThrow().board().meetings().isEmpty());
            var call = PharmaDecisionToolTest.open(world, "mfgstruct-d1", "read-evidence");
            long finish = PharmaDecisionToolTest.reply(world, call, "role.reading.start", java.util.Map.of("docId", doc, "version", 1));
            world.advanceTo(finish + 10_000L);
            var after = world.autonomyRoleObservation("mfgstruct-d1");
            String body = after.path("profile").path("readExperiences").toString();
            assertTrue(body.contains("candidateDossiers"), body);
            assertTrue(body.contains("mfgstruct-mgr"));
            assertTrue(body.contains("9000.00"), body);
            assertTrue(body.contains("customerOffers"));
            assertFalse(body.contains("certPlans"));
            assertFalse(body.contains("EXT-CUST-AUTO"), "公司资料不能泄露客户账户与余额");
            assertTrue(world.boardOfCompany("mfg-struct").orElseThrow().board().budgets().isEmpty());
        }
    }

    @Test void legacyAndNewJournalsKeepTheirOwnInputOnReopen() throws Exception {
        String oldInput, newInput;
        Path old = dir.resolve("old.journal"), fresh = dir.resolve("new.journal");
        try (var world = ComposedIndustryScenario.openEventAutonomous(old)) {
            oldInput = world.autonomyRoleObservation("mfgstruct-d1").toString();
            assertFalse(oldInput.contains("roleContextVersion"));
        }
        try (var world = ComposedIndustryScenario.openEvidenceAutonomous(fresh, false)) {
            newInput = world.autonomyRoleObservation("mfgstruct-d1").toString();
            assertTrue(newInput.contains("evidence-v1"));
        }
        try (var world = ComposedIndustryScenario.openEventAutonomous(old)) {
            assertEquals(oldInput, world.autonomyRoleObservation("mfgstruct-d1").toString());
        }
        try (var world = ComposedIndustryScenario.openEvidenceAutonomous(fresh, false)) {
            assertEquals(newInput, world.autonomyRoleObservation("mfgstruct-d1").toString());
        }
        assertThrows(Exception.class, () -> ComposedIndustryScenario.openEvidenceAutonomous(old, false));
    }

    @Test void digitalAndHydroDossiersContainActualTermsAndFrozenDocsCannotBeRewritten() throws Exception {
        try (var world = ComposedIndustryScenario.openEvidenceAutonomous(dir.resolve("industries.journal"), false)) {
            for (String company : java.util.List.of("digital-company", "hydro")) {
                String person = company.equals("hydro") ? "hydro-director-1" : "digital-company-director-1";
                world.scheduleStartReading("read-" + company, "read-task-" + company,
                        world.snapshot().timeMillis() + 1, person, "role-evidence:" + company + ":1", 1);
                world.advanceTo(world.snapshot().timeMillis() + 10_001L);
                String body = world.autonomyRoleObservation(person).path("profile").path("readExperiences").get(0).path("body").asText();
                var document = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                assertTrue(document.path("openingOperations").has(company.equals("hydro") ? "contract" : "serviceUnitCost"), body);
            }
            assertThrows(IllegalArgumentException.class, () -> world.schedulePublishDocumentVersion(
                    "forged", "forged-task", world.snapshot().timeMillis() + 1,
                    "role-evidence:mfg-struct:1", "fake", "fake", "fake", 1, java.util.List.of()));
        }
    }
}
