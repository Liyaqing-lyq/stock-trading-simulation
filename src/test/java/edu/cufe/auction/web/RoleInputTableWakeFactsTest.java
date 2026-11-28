package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.RoleInputTables;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class RoleInputTableWakeFactsTest {
    @TempDir Path dir;

    @Test void tableRepresentationPreservesWakeFactsAndNewVisibleDocumentTriggersWake() throws Exception {
        var world = new AtomicReference<WorldRuntime>();
        try (var host = new WorldWebHost(() -> {
            var runtime = ComposedIndustryScenario.openRoleTableOperating(dir.resolve("world.journal"));
            world.set(runtime);
            return new WorldWebHost.Opened(runtime, runtime.profile().manifest().runId());
        })) {
            host.adminSnapshot();
            host.call(() -> {
                var runtime = world.get();
                var before = runtime.autonomyRoleObservation("alice");
                assertTrue(before.at("/profile/market").isArray());
                // 普通公开资料使本人输入恰好超过原字符阈值；由运行时自行选择紧凑格式。
                int characters = 32_768 - before.toString().length() + 50;
                publish(runtime, "table-threshold", "A".repeat(characters));
                var packed = (ObjectNode) runtime.autonomyRoleObservation("alice");
                assertTrue(packed.at("/profile/market/rows").isArray());
                var expanded = packed.deepCopy();
                RoleInputTables.expand(expanded);
                assertTrue(expanded.at("/profile/market").isArray());
                assertTrue(expanded.toString().length() > 32_768);
                assertTrue(runtime.autonomyInputLimitViolation("alice").isEmpty());

                Method facts = WorldWebHost.class.getDeclaredMethod("wakeFacts", JsonNode.class);
                facts.setAccessible(true);
                Method wakes = WorldWebHost.class.getDeclaredMethod("newFactsRequireWake",
                        JsonNode.class, JsonNode.class, long.class, long.class);
                wakes.setAccessible(true);
                var packedFacts = (JsonNode) facts.invoke(host, packed);
                var expandedFacts = (JsonNode) facts.invoke(host, expanded);
                long last = runtime.snapshot().timeMillis();
                assertEquals(expandedFacts, packedFacts);
                assertEquals(false, wakes.invoke(host, expanded, packedFacts, last, last + 10_000));
                assertEquals(false, wakes.invoke(host, packed, expandedFacts, last, last + 10_000));

                publish(runtime, "new-public-document", "另一份实际公开的新资料");
                var changed = runtime.autonomyRoleObservation("alice");
                assertTrue(changed.at("/profile/market/rows").isArray());
                assertTrue(changed.at("/profile/catalog").toString().contains("new-public-document"));
                assertEquals(true, wakes.invoke(host, changed, packedFacts, last, last + 10_000));
                return null;
            });
        }
    }

    private static void publish(WorldRuntime runtime, String id, String title) throws Exception {
        long due = runtime.snapshot().timeMillis() + 1;
        runtime.schedulePublishDocument("publish-" + id, "task-" + id, due, id, "nina",
                InformationModel.Audience.publicAudience(), title, "公开内容", "输入表示回归", 1000L, List.of());
        runtime.advanceTo(due);
    }
}
