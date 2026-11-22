package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.PersonalMarket;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.*;

class PersonalLimitObservationTest {
    @TempDir Path root;

    @Test void onlyTheOwnerWithPersonalSubmitSeesTheNewRuleInTheOrdinaryRoster() throws Exception {
        Path oldFile = root.resolve("v11.journal");
        Path newFile = root.resolve("v12.journal");
        try (var oldWorld = ComposedIndustryScenario.openHydroSettlementOperating(oldFile);
             var newWorld = ComposedIndustryScenario.openPersonalLimitOperating(newFile);
             var routes = SceneRoutes.open(root);
             var host = new WorldWebHost(() -> new WorldWebHost.Opened(newWorld, newWorld.profile().manifest().runId()),
                     routes, (call, timeout) -> { throw new AssertionError("只读角色列表不应派发模型"); })) {
            var cap = host.liveCapabilities();
            var roster = new TreeSet<String>();
            cap.persons().stream().filter(p -> p.sceneId() != null).forEach(p -> roster.add(p.personPartyId()));
            cap.governancePersons().forEach(p -> roster.add(p.personPartyId()));
            assertEquals(52,roster.size());
            var changed = new TreeSet<String>();
            for (String person : roster) {
                var old = oldWorld.autonomyRoleObservation(person);
                var actual = newWorld.autonomyRoleObservation(person);
                if (old.equals(actual)) {
                    assertEquals(old.toString(), actual.toString(), person);
                    continue;
                }
                changed.add(person);
                var originalRule = old.at("/engineering/personalMarket/contract");
                assertTrue(originalRule.isTextual());
                var patch = (ObjectNode) actual.deepCopy();
                ((ObjectNode) patch.at("/engineering/personalMarket")).set("contract", originalRule);
                assertEquals(old.toString(),patch.toString(),person);
                assertFalse(originalRule.asText().contains("基金产品授权"));
                assertTrue(actual.at("/engineering/personalMarket/contract").asText().contains("也可等待"));
            }
            assertEquals(java.util.Set.of("investor"),changed);
            var oldData=oldWorld.profile().manifest().dataVersions();
            var newData=new java.util.TreeMap<>(newWorld.profile().manifest().dataVersions());
            assertEquals(PersonalMarket.LIMIT_RULES_VERSION,newData.remove(PersonalMarket.LIMIT_RULES));
            assertEquals(oldData,newData);
        }
        assertThrows(Exception.class,()->ComposedIndustryScenario.openPersonalLimitOperating(oldFile));
    }
}
