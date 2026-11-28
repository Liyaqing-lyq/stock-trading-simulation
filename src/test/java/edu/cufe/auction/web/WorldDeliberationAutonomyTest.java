package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 只验证调度时序，注入的等待不能作为真实模型经营证据。 */
class WorldDeliberationAutonomyTest {
    @TempDir Path dir;
    @Test void explainedWaitWakesAtItsReviewTimeAndRestartDoesNotPollAgain() throws Exception {
        var json = new ObjectMapper();
        var times = new ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>();
        for (int restart = 0; restart < 2; restart++) {
            try (var routes = SceneRoutes.open(dir)) {
                if (restart == 0) routes.replace(routes.view().revision(), routes.view().routes().stream()
                        .map(r -> new SceneRoutes.Route(r.scene(), r.slotId(), r.model(), true,
                                500, 5_000_000, 512, 4, "lifecycle-v1")).toList());
                try (var host = new WorldWebHost(() -> {
                    var world = ComposedIndustryScenario.openDeliberativeAutonomous(dir.resolve("wait.journal"));
                    return new WorldWebHost.Opened(world, world.profile().manifest().runId());
                }, routes, (call, timeout) -> {
                    var input = assertDoesNotThrow(() -> json.readTree(call.inputText()));
                    long now = input.path("time").asLong();
                    times.computeIfAbsent(input.path("person").asText(), p -> new CopyOnWriteArrayList<>()).add(now);
                    var reply = json.createObjectNode().put("tool", "role.wait");
                    reply.putObject("decision").put("objective", input.path("engineering").path("deliberation")
                                    .path("objectives").get(0).path("id").asText())
                            .put("basis", "当前没有新事实，十秒后复查。仅调度测试。")
                            .put("nextReviewAt", now + 10_000);
                    return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(), call.actionId(),
                            call.attemptId(), call.attemptNo(), call.recoveryGeneration(), reply.toString(),
                            DecisionModel.Usage.unknownCost(1, 10, 30), "TEST"));
                })) {
                    var initial = host.autonomyStatus();
                    host.startAutonomy(Long.parseLong(initial.position()), routes.view().revision(),
                            restart == 0 ? 15_000 : 16_000, 3_600_000);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    while (host.autonomyStatus().running() && System.nanoTime() < deadline) Thread.sleep(25);
                    assertEquals("TARGET_REACHED", host.autonomyStatus().reason(), host.liveStatus().decisions().toString());
                    assertEquals(0, host.autonomyStatus().failures());
                    assertEquals(host.autonomyStatus().persons(), times.size());
                    for (var person : times.entrySet()) {
                        assertEquals(2, person.getValue().size(), person.toString());
                        assertTrue(person.getValue().get(1) >= person.getValue().get(0) + 10_000, person.toString());
                    }
                    if (restart == 1) assertEquals(0, host.autonomyStatus().calls(), "恢复不能把自己的等待说明误当新事件");
                }
            }
        }
    }
}
