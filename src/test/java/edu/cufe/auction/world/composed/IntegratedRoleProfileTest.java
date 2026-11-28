package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RunManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class IntegratedRoleProfileTest {
    @TempDir Path directory;

    @Test void investorRetainsHerPrivateGoalAndOnlyHerOwnDeskAndAssets() throws Exception {
        var file = directory.resolve("combined.journal");
        long cut;
        try (var world = ComposedIndustryScenario.openIntegratedOperating(file)) {
            var view = world.autonomyRoleObservation("nina");
            assertEquals(62, world.profile().registry().parties().stream()
                    .filter(p -> p.kind() == OpeningRegistry.PartyKind.PERSON).count());
            assertEquals(8, world.snapshot().media().orElseThrow().states().size());
            assertEquals(9, world.profile().bankOpening().config().profile().buckets().size());
            assertEquals("v2", view.at("/engineering/researchNotes/version").asText());
            assertTrue(view.at("/engineering/exchangeSessions/sessionOpen").isBoolean());
            var goals = view.at("/engineering/deliberation/objectives");
            assertTrue(goals.findValuesAsText("id").contains("person:nina"));
            assertTrue(goals.findValuesAsText("intendedAudience").size() == 1);
            assertEquals("HOUSE-NINA", view.at("/profile/accounts/0/accountId").asText());
            assertEquals(1, view.at("/profile/accounts").size());
            assertTrue(world.roleView("nina").catalog().stream().noneMatch(d -> d.docId().startsWith("fund-mandate:")));
            assertTrue(world.roleView("bob").catalog().stream().anyMatch(d -> d.docId().startsWith("fund-mandate:")));
            assertTrue(world.snapshot().decisions().orElseThrow().state().decisions().isEmpty());
            cut = world.snapshot().position();
        }
        try (var restored = ComposedIndustryScenario.openIntegratedOperating(file)) {
            assertEquals(RunManifest.INTEGRATED_RUN_ID, restored.profile().manifest().runId());
            assertEquals(cut, restored.snapshot().position());
        }
    }
}
