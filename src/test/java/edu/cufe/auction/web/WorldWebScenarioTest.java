package edu.cufe.auction.web;

import edu.cufe.auction.world.RoleView;
import edu.cufe.auction.world.WorldRuntime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正式实验世界：真实 schema 9 开局、四岗任职、五席董事会、阅读者集合、无成交空状态。
 */
class WorldWebScenarioTest {

    private static Path dir(String name) throws Exception {
        Path dir = Path.of("target", "web-test", "scenario", name + "-" + System.nanoTime());
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    @DisplayName("开局可读：runId 一致、现金有限、无成交、无分红报告")
    void opensWithFiniteEmptyState() throws Exception {
        Path file = dir("open").resolve("world.journal");
        WorldWebHost.Opened opened = WorldWebScenario.open(file);
        WorldRuntime runtime = opened.runtime();
        try (runtime) {
            assertEquals(WorldWebScenario.RUN_ID, opened.runId());
            WorldRuntime.Snapshot snapshot = runtime.snapshot();
            assertEquals(1L, snapshot.position(), "开局批即位置 1");
            assertEquals(0, snapshot.exchange().orElseThrow().trades().size());
            assertTrue(snapshot.dividends().isPresent());
            assertEquals(0, opened.runtime().dividendReports().size());
            assertTrue(snapshot.securities().isPresent());
        }
    }

    @Test
    @DisplayName("主席与投资者都可见，四岗任职真实存在")
    void rolesVisible() throws Exception {
        WorldWebHost.Opened opened = WorldWebScenario.open(dir("roles").resolve("w.journal"));
        WorldRuntime runtime = opened.runtime();
        try (runtime) {
            RoleView chair = runtime.roleView(WorldWebScenario.CHAIR);
            assertEquals(WorldWebScenario.CHAIR, chair.personPartyId());
            assertFalse(chair.appointments().isEmpty(), "主席应有真实任职");

            RoleView investor = runtime.roleView(WorldWebScenario.INVESTOR);
            assertTrue(investor.holdings().stream().anyMatch(row -> row.quantity() > 0));
        }
    }

    @Test
    @DisplayName("重复打开同一日志不会重复注入开局现金")
    void reopeningDoesNotSeedTwice() throws Exception {
        Path file = dir("reopen").resolve("w.journal");
        WorldWebHost.Opened first = WorldWebScenario.open(file);
        WorldRuntime firstRuntime = first.runtime();
        long cash;
        long position;
        try (firstRuntime) {
            cash = firstRuntime.snapshot().accounts().get("CO").cash().longValue();
            position = firstRuntime.snapshot().position();
        }
        WorldWebHost.Opened second = WorldWebScenario.open(file);
        WorldRuntime secondRuntime = second.runtime();
        try (secondRuntime) {
            assertEquals(cash, secondRuntime.snapshot().accounts().get("CO").cash().longValue());
            assertEquals(position, secondRuntime.snapshot().position(), "重开不得重复开局");
        }
    }
}
