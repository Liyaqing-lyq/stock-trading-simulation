package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 旧诊断额度不迁移，长期实验必须显式冻结新预算政策。 */
class LifecycleBudgetTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();

    @Test void extendedBudgetRequiresExplicitPolicyInPersistedRouteAndDecision() throws Exception {
        String config = "{\"scene\":\"GOVERNANCE\",\"slotId\":\"key-1\",\"model\":\"deepseek-flash\",\"enabled\":true,\"maxCalls\":2500,\"maxTotalTokens\":35000000,\"maxOutputTokens\":2048,\"maxConcurrency\":4,\"budgetPolicy\":\"lifecycle-v1\"}";
        var route = json.readValue(config, SceneRoutes.Route.class);
        try (var routes = SceneRoutes.open(dir)) {
            routes.replace(routes.view().revision(), routes.view().routes().stream()
                    .map(r -> r.scene() == SceneRoutes.Scene.GOVERNANCE ? route : r).toList());
        }
        try (var routes = SceneRoutes.open(dir)) { assertEquals(2500, routes.view().routes().get(0).maxCalls()); }
        var frozen = json.readValue(config.replace("\"scene\":\"GOVERNANCE\"", "\"sceneId\":\"governance\"")
                .replace("\"enabled\":true", "\"revision\":1"), DecisionModel.Route.class);
        assertEquals(2500, frozen.maxCalls());
        assertThrows(Exception.class, () -> json.readValue(config.replace(",\"budgetPolicy\":\"lifecycle-v1\"", ""), SceneRoutes.Route.class));
        assertThrows(Exception.class, () -> json.readValue(config.replace("lifecycle-v1", "typo"), SceneRoutes.Route.class));
    }

    @Test void oldConstructorAndSerializedShapeRemainStrict() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new SceneRoutes.Route(SceneRoutes.Scene.GOVERNANCE,
                "key-1", "deepseek-flash", true, 101, 1_000_000, 2048, 4));
        var old = new DecisionModel.Route("governance", "key-1", "deepseek-flash", 0, 100, 1_000_000, 2048, 4);
        assertFalse(json.writeValueAsString(old).contains("budgetPolicy"));
    }
}
