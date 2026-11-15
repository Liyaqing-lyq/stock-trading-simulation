package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证开局检查只反映已发生条件，读页面不生成任职、预算或阅读。 */
class WorldStartupViewTest {
    @TempDir Path dir;

    @Test
    void publishedOpeningIsUnreadAndViewDoesNotAdvanceWorld() throws Exception {
        try (var host = host(dir.resolve("new.journal"), true)) {
            var first = host.adminView();
            var pharma = company(first, "pharma");
            assertEquals("PUBLISHED", pharma.get("openingDocument"));
            assertEquals("0", pharma.get("directorsRead"));
            assertEquals(List.of(), pharma.get("managers"));
            assertEquals("0", pharma.get("budgetLines"));
            assertEquals("2000.00", pharma.get("cash"));
            assertEquals("5", pharma.get("boardSeats"));
            assertEquals(first.position(), first.startup().get("position"));
            assertEquals(first, host.adminView(), "观察开局检查不可修改世界");
        }
    }

    @Test
    void actualCompletedReadAppearsAfterRestartAndOldProfileStaysUnpublished() throws Exception {
        Path journal = dir.resolve("read.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openRunnable(journal)) {
            world.scheduleStartReading("read", "read-task", 20L, "frank",
                    CompanyOpeningDocuments.documentId("pharma"), 1L);
            world.advanceTo(1020L);
        }
        try (var host = host(journal, true)) {
            assertEquals("1", company(host.adminView(), "pharma").get("directorsRead"));
            assertEquals("0", host.adminView().startup().get("activeReadings"));
        }
        try (var host = host(dir.resolve("legacy.journal"), false)) {
            var row = company(host.adminView(), "pharma");
            assertEquals("UNPUBLISHED", row.get("openingDocument"));
            assertNull(row.get("directorsRead"), "没有资料不能伪装成已统计的零人阅读");
        }
    }

    private WorldWebHost host(Path journal, boolean runnable) throws Exception {
        return new WorldWebHost(() -> {
            var world = runnable ? ComposedPharmaScenario.openRunnable(journal)
                    : ComposedPharmaScenario.openDebug(journal);
            return new WorldWebHost.Opened(world, world.profile().manifest().runId());
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> company(WorldWebHost.AdminView view, String id) {
        return ((List<Map<String, Object>>) view.startup().get("companies")).stream()
                .filter(row -> id.equals(row.get("companyId"))).findFirst().orElseThrow();
    }
}
