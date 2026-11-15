package edu.cufe.auction.world;

import edu.cufe.auction.web.SceneRoutes;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.world.composed.ComposedResearchScenario;
import edu.cufe.auction.world.composed.ComposedWorldSelfTest;
import edu.cufe.auction.world.composed.ManufacturingFundingChainTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 网页宿主的有界治理轮真正调用制造工具；注入确定性提供方，不调用外部服务。 */
class ManufacturingWebRoundTest {
    @TempDir Path dir;

    @Test
    void governanceRoundRunsOneManagerPurchaseAndPersistsIt() throws Exception {
        Path file = dir.resolve("world.journal");
        try (WorldRuntime world = ComposedResearchScenario.openDebug(file)) {
            ComposedWorldSelfTest.appoint(world, 10, "cufe", "erin", "manager", "material");
            ManufacturingFundingChainTest.budgetMeeting(world, "cufe", "budget", "10000.00", "10000.00");
        }
        AtomicInteger calls = new AtomicInteger();
        try (SceneRoutes routes = SceneRoutes.open(dir);
             WorldWebHost host = new WorldWebHost(() -> {
                 var world = ComposedResearchScenario.openDebug(file);
                 return new WorldWebHost.Opened(world, world.profile().manifest().runId());
             }, routes, (call, timeout) -> {
                 calls.incrementAndGet();
                 assertTrue(call.allowedTools().contains(ManufacturingDecisionTools.ACT));
                 assertTrue(call.inputText().contains("remainingBudgets"));
                 String body = "{\"tool\":\"manufacturing.act\",\"args\":{\"action\":\"PURCHASE_ORDER\","
                         + "\"businessId\":\"WEB-MODEL-PO\",\"resultId\":\"\",\"quantity\":10,"
                         + "\"unitPrice\":\"0.00\",\"promisedDeliveryAt\":0,\"period\":0,\"fundSource\":\"CASH|CO\"}}";
                 return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(), call.actionId(),
                         call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                         DecisionModel.Usage.unknownCost(1, 60, 20), "scripted:web-manufacturing"));
             })) {
            List<SceneRoutes.Route> configured = new ArrayList<>();
            for (var route : routes.view().routes()) configured.add(new SceneRoutes.Route(route.scene(),
                    route.slotId(), route.model(), route.scene() == SceneRoutes.Scene.GOVERNANCE,
                    1, 1_000_000, 1_024, 1));
            host.replaceRoutes(routes.view().revision(), configured);
            var before = host.liveStatus();
            host.startLiveRound(Long.parseLong(before.position()), routes.view().revision(),
                    List.of("erin"), "governance", "cufe");
            long deadline = System.nanoTime() + 20_000_000_000L;
            WorldWebHost.LiveStatus after;
            do {
                after = host.liveStatus();
                if (!after.decisions().isEmpty() && after.pendingPersons().isEmpty()
                        && after.physicalInFlight() == 0 && after.completionSlots() == 0) break;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            assertEquals(1, calls.get());
            assertTrue(after.pendingPersons().isEmpty(), after.toString());
            assertEquals(0, after.physicalInFlight(), after.toString());
            // 有界模型轮完成只登记意图；世界时间仍由同一网页宿主明确推进。
            host.advance(Long.parseLong(after.position()), Long.parseLong(after.timeMillis()) + 10_000);
            assertTrue(host.manufacturingView("erin").toString().contains("WEB-MODEL-PO"), after.toString());
        }
        try (WorldRuntime world = ComposedResearchScenario.openDebug(file)) {
            assertTrue(world.snapshot().manufacturing().orElseThrow().ledger().purchaseOrders().containsKey("WEB-MODEL-PO"));
        }
    }
}
