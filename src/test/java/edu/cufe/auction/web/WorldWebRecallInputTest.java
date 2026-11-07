package edu.cufe.auction.web;

import edu.cufe.auction.world.InformationModel;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 完整输入超限必须在Host留下安全诊断，且不进入提供方或自动重试。 */
class WorldWebRecallInputTest {
    @Test
    void oversizedCatalogIsReportedWithoutSendingOrOpeningDecision() throws Exception {
        Path root = Path.of("target", "web-recall-input-tests");
        Files.createDirectories(root);
        Path dir = Files.createTempDirectory(root, "case-");
        AtomicInteger providerAccess = new AtomicInteger();
        try (var routes = SceneRoutes.open(dir)) {
            routes.replace(0, routes.view().routes().stream().map(r -> new SceneRoutes.Route(
                    r.scene(), r.slotId(), r.model(), r.scene().id().equals("research"),
                    20, 100000, 128, 1)).toList());
            try (var host = new WorldWebHost(() -> {
                var opened = WorldWebScenario.openCognition(dir.resolve("world.journal"));
                var world = opened.runtime();
                world.schedulePublishDocument("p", "pt", 1, "D", "alice",
                        new InformationModel.Audience(InformationModel.AudienceKind.PUBLIC, null),
                        "超".repeat(40000), "正文", "公开测试来源", 10, List.of());
                world.advanceTo(2);
                return opened;
            }, routes, new LiveDecisionProvider(slot -> {
                providerAccess.incrementAndGet();
                throw new AssertionError("超限输入不得触及提供方");
            }))) {
                long position = host.adminSnapshot().position();
                host.startLiveRound(position, 1, List.of("alice"));
                var status = host.liveStatus();
                assertEquals(1, status.skips().size());
                var skipped = status.skips().get(0);
                assertEquals("INPUT_TOO_LARGE", skipped.reason());
                assertNotNull(skipped.inputSize());
                assertTrue(Long.parseLong(skipped.inputSize().actualChars()) > 40000);
                assertTrue(Long.parseLong(skipped.inputSize().actualBytes()) > 120000);
                assertEquals("32768", skipped.inputSize().maxChars());
                assertEquals(0, status.decisions().size());
                assertEquals("0", status.runBudget().reservedCalls());
                assertEquals("0", status.runBudget().usedCalls());
                assertEquals(0, status.physicalInFlight());
                assertEquals(0, status.completionSlots());
                assertEquals(0, providerAccess.get());
                var encoded = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(status);
                assertFalse(encoded.contains("超"));
                assertFalse(encoded.contains("正文"));
                assertTrue(encoded.contains("INPUT_TOO_LARGE"));
                long after = host.adminSnapshot().position();
                host.call(() -> null); // 再次由writer处理，不能悄悄重试失败人物。
                assertEquals(after, host.adminSnapshot().position());
                assertEquals(0, providerAccess.get());
            }
        }
    }
}
