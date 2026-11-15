package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class PrototypeCompanyTest {
    @TempDir Path dir;
    @Test void engineeringBoardWorksWithoutLegacyManufacturingSingleton() throws Exception {
        for (boolean prototype : java.util.List.of(false, true)) {
            try (var world = prototype ? ComposedIndustryScenario.openPrototype(dir.resolve("engineering-new.journal"))
                    : ComposedIndustryScenario.openBaseline(dir.resolve("engineering-baseline.journal"))) {
                world.scheduleEngineeringConvene("engineering-convene", "engineering-convene-task", 100L,
                        "eng-a", "alice", "engineering-meeting", 200L, 300L, 400L);
                world.advanceTo(100L);
                assertTrue(world.boardOfCompany("eng-a").orElseThrow().board().meetings().containsKey("engineering-meeting"));
                assertThrows(IllegalArgumentException.class, () -> world.scheduleEngineeringConvene(
                        "unknown", "unknown", 101L, "missing-company", "alice", "meeting", 200L, 300L, 400L));
            }
        }
    }
    @Test void newIdentitiesKeepTenIssuersTheirOwnershipAndOldArchiveUnchanged() throws Exception {
        Map<String,String> original;
        try (var old = ComposedIndustryScenario.openBaseline(dir.resolve("old.journal"));
             var world = ComposedIndustryScenario.openPrototype(dir.resolve("new.journal"))) {
            original = old.openingRegistry().orElseThrow().parties().stream().collect(Collectors.toMap(OpeningRegistry.Party::partyId, OpeningRegistry.Party::name));
            var registry = world.openingRegistry().orElseThrow();
            assertEquals(10, registry.securities().size());
            assertEquals(old.openingRegistry().orElseThrow().allocations(), registry.allocations());
            assertEquals(old.openingRegistry().orElseThrow().owners(), registry.owners());
            assertEquals(old.snapshot().accounts(), world.snapshot().accounts());
            for (var company : PrototypeCompanies.all()) {
                assertTrue(registry.securities().stream().anyMatch(s -> s.issuerOrganizationId().equals(company.companyId())));
                assertEquals(company.name(), registry.parties().stream().filter(p -> p.partyId().equals(company.companyId())).findFirst().orElseThrow().name());
                assertNotEquals(original.get(company.companyId()), company.name());
            }
            world.advanceTo(86_400_001L);
        }
        try (var restored = ComposedIndustryScenario.openPrototype(dir.resolve("new.journal"));
             var old = ComposedIndustryScenario.openBaseline(dir.resolve("old.journal"))) {
            assertEquals(86_400_001L, restored.snapshot().timeMillis());
            assertEquals(PrototypeCompanies.PROFILE, restored.profile().manifest().dataVersions().get("profile"));
            assertEquals(original, old.openingRegistry().orElseThrow().parties().stream().collect(Collectors.toMap(OpeningRegistry.Party::partyId, OpeningRegistry.Party::name)));
        }
    }

    @Test void administratorMappingDoesNotEnterPublicWorldDocuments() throws Exception {
        try (var host = new WorldWebHost(() -> {
            var w = ComposedIndustryScenario.openPrototype(dir.resolve("web.journal"));
            return new WorldWebHost.Opened(w, w.profile().manifest().runId());
        })) {
            var view = new ObjectMapper().valueToTree(host.peopleView());
            assertEquals(10, java.util.stream.StreamSupport.stream(view.path("parties").path("rows").spliterator(), false).filter(n -> n.has("prototypeName")).count());
            assertTrue(view.toString().contains("中际旭创"));
        }
        try (var world = ComposedIndustryScenario.openPrototype(dir.resolve("web.journal"))) {
            String materials = new ObjectMapper().writeValueAsString(world.snapshot().information().orElseThrow());
            for (var company : PrototypeCompanies.all()) {
                assertTrue(materials.contains(company.name()));
                assertFalse(materials.contains(company.prototype()));
                assertFalse(materials.contains(company.source()));
                for (var other : PrototypeCompanies.all()) assertFalse(company.publicDescription().contains(other.prototype()));
            }
        }
    }

    @Test void decisionGuideIncludesOnlyOwnCurrentProductAuthorityAndSurvivesReplay() throws Exception {
        Path path = dir.resolve("decisions.journal");
        String input;
        try (var world = ComposedIndustryScenario.openPrototype(path)) {
            var alice = world.engineeringRoleInput("alice");
            assertEquals("prototype-v1", alice.path("decisionGuideVersion").asText());
            assertEquals("fund-a", alice.path("authorizedProducts").get(0).path("productOwnerId").asText());
            assertTrue(world.engineeringRoleInput("digital-company-director-1").path("authorizedProducts").isEmpty());
            world.beginDecisionSession("probe-session");
            world.openDecisions("probe-open", java.util.List.of(new DecisionRun.Request("alice", "probe-read", "wake",
                    new DecisionModel.Route("research", "key-2", "deepseek-flash", 1, 2, 100_000, 1024, 1))));
            input = DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(), "probe-read").orElseThrow().inputText();
            assertTrue(input.contains("authorizedProducts"));
            for (var company : PrototypeCompanies.all()) assertFalse(input.contains(company.prototype()));
        }
        try (var restored = ComposedIndustryScenario.openPrototype(path)) {
            assertEquals(input, DecisionModel.decision(restored.snapshot().decisions().orElseThrow().state(), "probe-read").orElseThrow().inputText());
        }
    }
}
