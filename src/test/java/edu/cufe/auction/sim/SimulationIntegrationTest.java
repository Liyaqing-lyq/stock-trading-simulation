package edu.cufe.auction.sim;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.account.PerformanceRank;
import edu.cufe.auction.config.SimulationConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端集成测试：真起线程跑一段时间，验证产生成交、CSV 落盘、收盘状态与资金不变为负。 */
class SimulationIntegrationTest {

    @Test
    @DisplayName("多线程模拟端到端：产生成交、写 CSV、收盘清空订单簿且现金非负")
    void endToEndSimulation() throws Exception {
        Path dir = Files.createTempDirectory("auction-it");
        Path configFile = dir.resolve("test-config.properties");
        String dataDir = dir.resolve("data").toString().replace('\\', '/');
        Files.writeString(configFile, String.join("\n",
                "simulation.tick.interval.ms=80",
                "simulation.duration=2",
                "stocks=AAPL,TSLA",
                "AAPL=175.50",
                "TSLA=245.30",
                "agent.human.count=1",
                "agent.human.initial.balance=100000.00",
                "agent.human.initial.holdings=AAPL=100,TSLA=50",
                "agent.ai.count=0",
                "agent.noise.count=3",
                "agent.noise.initial.balance=80000.00",
                "agent.noise.initial.holdings=AAPL=300,TSLA=200",
                "io.data.dir=" + dataDir,
                ""), StandardCharsets.UTF_8);

        SimulationConfig config = SimulationConfig.load(configFile);
        List<PerformanceRank> ranks;
        try (SimulationRunner runner = new SimulationRunner(config)) {
            assertEquals(4, runner.getAgents().size());
            runner.start();
            Thread.sleep(2000L);
            ranks = runner.closeAndRank();

            assertEquals(4, ranks.size(), "排行榜应覆盖全部 agent");
            assertTrue(runner.getEngine().isClosed(), "收盘后引擎应处于 closed 状态");

            Path tradesFile = runner.getRecorder().getDirectory().resolve("trades.csv");
            Path ordersFile = runner.getRecorder().getDirectory().resolve("orders.csv");
            Path rankingFile = runner.getRecorder().getDirectory().resolve("ranking.csv");
            assertTrue(Files.exists(tradesFile) && Files.exists(ordersFile) && Files.exists(rankingFile));

            List<String> tradeLines = Files.readAllLines(tradesFile, StandardCharsets.UTF_8);
            assertTrue(tradeLines.size() > 1, "模拟应至少产生一笔成交，实际行数 " + tradeLines.size());
            assertEquals(5, Files.readAllLines(rankingFile, StandardCharsets.UTF_8).size(),
                    "ranking.csv = 表头 + 4 个 agent");

            long tradedQuantity = tradeLines.subList(1, tradeLines.size()).stream()
                    .mapToLong(line -> Long.parseLong(line.split(",")[4]))
                    .sum();
            assertTrue(tradedQuantity > 0, "累计成交量应大于 0");

            for (Account account : runner.getAccountManager().all()) {
                assertTrue(account.getAvailableCash().signum() >= 0,
                        account.getAgentId() + " 现金不得为负：" + account.getAvailableCash());
                assertTrue(account.getAvailableQuantity("AAPL") >= 0, "可卖数量不得为负");
                assertNotNull(account.getPerformance());
            }
            for (String symbol : runner.getEngine().symbols()) {
                assertTrue(runner.getEngine().snapshot(symbol, 5).getBids().isEmpty(),
                        symbol + " 收盘后买盘应清空");
                assertTrue(runner.getEngine().snapshot(symbol, 5).getAsks().isEmpty(),
                        symbol + " 收盘后卖盘应清空");
            }
        }
        assertTrue(ranks.get(0).getRank() == 1);
    }
}
