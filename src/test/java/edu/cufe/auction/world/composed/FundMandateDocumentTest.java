package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.WorldRuntime;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FundMandateDocumentTest {
    @TempDir Path dir;
    private static final String DOC = "fund-mandate:cufe:fund-a";

    private static boolean visible(WorldRuntime world, String person) throws Exception {
        return world.roleView(person).catalog().stream().anyMatch(d -> d.docId().equals(DOC));
    }

    @Test void mandateReflectsRealAssetsRequiresReadingAndFollowsAppointmentsAcrossReopen() throws Exception {
        var file = dir.resolve("institution.journal");
        try (var prior = ComposedIndustryScenario.openPersonalProfilesOperating(dir.resolve("previous.journal"));
             var world = ComposedIndustryScenario.openFundMandateOperating(file)) {
            assertFalse(visible(prior, "bob"));
            assertEquals(prior.snapshot().accounts(), world.snapshot().accounts());
            for (String owner : List.of("alice", "bob", "carol", "dave")) {
                assertTrue(visible(world, owner));
                assertFalse(world.canCite(owner, DOC, 1));
                assertEquals(prior.autonomyRoleObservation(owner).at("/engineering/deliberation/objectives"),
                        world.autonomyRoleObservation(owner).at("/engineering/deliberation/objectives"));
            }
            assertFalse(visible(world, "investor"));
            assertFalse(visible(world, "erin"));
            assertFalse(visible(world, "rita"));
            world.openRoleSession("dave").startReading("read", "read-task", 2, DOC, 1);
            world.advanceTo(2);
            assertFalse(world.canCite("dave", DOC, 1));
            world.advanceTo(world.roleView("dave").readings().getFirst().dueAt());
            assertTrue(world.canCite("dave", DOC, 1));
            var body = world.roleView("dave").observations().stream().filter(item -> item.ref().docId().equals(DOC))
                    .findFirst().orElseThrow().body();
            assertTrue(body.contains("开局账户=ACC-FUND；财产主体=fund-a；账面现金="
                    + world.snapshot().accounts().get("ACC-FUND").cash().toPlainString()));
            for (var allocation : world.openingRegistry().orElseThrow().allocations())
                if (allocation.accountId().equals("ACC-FUND"))
                    assertTrue(body.contains(allocation.symbol() + "，" + allocation.quantity() + "股"));
            assertTrue(world.snapshot().trading().orElseThrow().trading().targets().isEmpty());
            long at = world.snapshot().timeMillis()+1;
            world.openRoleSession("alice").startReading("interrupted", "interrupted-read", at, DOC, 1);
            world.scheduleRevokeAppointment("revoke-research", "rev-research-task", at+1, "A-1");
            world.scheduleRevokeAppointment("revoke", "rev-task", at, "A-4");
            world.advanceTo(at+CompanyOpeningDocuments.READ_MILLIS);
            assertFalse(visible(world, "dave"));
            assertFalse(visible(world, "alice"));
            assertFalse(world.canCite("alice", DOC, 1));
            assertTrue(visible(world, "carol"));
            assertFalse(world.autonomyRoleObservation("dave").at("/tools").findValuesAsText("name").contains("role.child.submit"));
        }
        try (var world = ComposedIndustryScenario.openFundMandateOperating(file)) {
            assertFalse(visible(world, "dave"));
            assertFalse(visible(world, "alice"));
            assertTrue(visible(world, "bob"));
        }
        assertThrows(Exception.class, () -> ComposedIndustryScenario.openPersonalProfilesOperating(file));
    }
}
