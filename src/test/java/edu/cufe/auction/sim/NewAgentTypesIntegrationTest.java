package edu.cufe.auction.sim;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.agent.AbstractTradingAgent;
import edu.cufe.auction.agent.AgentType;
import edu.cufe.auction.config.SimulationConfig;
import edu.cufe.auction.model.Side;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新增三类 agent 的端到端测试：真起线程、真进撮合，验证它们被正确装配并在真实行情下出手。
 *
 * <p>做法：先让噪音 agent 建立盘口，再由人类 broker 挂一张远低于锚价的卖单把价格打下去
 * （-6%），随后价值型应买入、逆势型应逆势承接、做市商应持续双边报价。</p>
 */
class NewAgentTypesIntegrationTest {

    @Test
    @DisplayName("价值/逆势/做市三类 agent 真实参与撮合并保持账户非负")
    void newAgentTypesTradeInLiveSimulation() throws Exception {
        Path dir = Files.createTempDirectory("auction-new-agents");
        Path configFile = dir.resolve("agents.properties");
        String dataDir = dir.resolve("data").toString().replace('\\', '/');
        Files.writeString(configFile, String.join("\n",
                "simulation.tick.interval.ms=80",
                "simulation.duration=3",
                "stocks=AAPL,TSLA",
                "AAPL=100.00",
                "TSLA=50.00",
                "agent.human.count=1",
                "agent.human.initial.balance=200000.00",
                "agent.human.initial.holdings=AAPL=2000,TSLA=2000",
                "agent.ai.count=0",
                "agent.noise.count=2",
                "agent.noise.initial.balance=150000.00",
                "agent.noise.initial.holdings=AAPL=800,TSLA=800",
                "agent.value.count=1",
                "agent.value.initial.balance=150000.00",
                "agent.value.initial.holdings=AAPL=300,TSLA=300",
                "agent.contrarian.count=1",
                "agent.contrarian.initial.balance=150000.00",
                "agent.contrarian.initial.holdings=AAPL=300,TSLA=300",
                "agent.maker.count=1",
                "agent.maker.initial.balance=200000.00",
                "agent.maker.initial.holdings=AAPL=400,TSLA=400",
                "io.data.dir=" + dataDir,
                ""), StandardCharsets.UTF_8);

        SimulationConfig config = SimulationConfig.load(configFile);
        try (SimulationRunner runner = new SimulationRunner(config)) {
            Set<AgentType> types = runner.getAgents().stream()
                    .map(agent -> agent.getType()).collect(Collectors.toSet());
            assertEquals(6, runner.getAgents().size(), "1 人类 + 2 噪音 + 价值/逆势/做市各 1");
            assertTrue(types.containsAll(EnumSet.of(
                            AgentType.VALUE, AgentType.CONTRARIAN, AgentType.MARKET_MAKER)),
                    "三类新 agent 都应被装配，实际 " + types);

            runner.start();
            Thread.sleep(700L);
            // 制造一次 -6% 的价格冲击：该卖单先吃掉买盘，剩余部分成为新的卖一，随后被买家成交
            runner.firstHumanBroker().placeLimitOrder("AAPL", Side.SELL,
                    new java.math.BigDecimal("94.00"), 800L);
            Thread.sleep(2400L);
            runner.closeAndRank();

            Path tradesFile = runner.getRecorder().getDirectory().resolve("trades.csv");
            List<String> trades = Files.readAllLines(tradesFile, StandardCharsets.UTF_8);
            assertTrue(trades.size() > 1, "新增 agent 参与后市场应产生成交，实际行数 " + trades.size());

            long valueOrders = submitted(runner, AgentType.VALUE);
            long contrarianOrders = submitted(runner, AgentType.CONTRARIAN);
            long makerOrders = submitted(runner, AgentType.MARKET_MAKER);
            assertTrue(valueOrders > 0, "价格跌破估值锚后价值型 agent 应下单");
            assertTrue(contrarianOrders > 0, "价格偏离均线后逆势型 agent 应下单");
            assertTrue(makerOrders > 0, "做市商应持续双边报价");

            for (Account account : runner.getAccountManager().all()) {
                assertTrue(account.getAvailableCash().signum() >= 0,
                        account.getAgentId() + " 现金不得为负");
                assertTrue(account.getAvailableQuantity("AAPL") >= 0, "可卖数量不得为负");
            }
        }
    }

    private static long submitted(SimulationRunner runner, AgentType type) {
        return runner.getAgents().stream()
                .filter(agent -> agent.getType() == type)
                .filter(AbstractTradingAgent.class::isInstance)
                .mapToLong(agent -> ((AbstractTradingAgent) agent).getSubmittedCount())
                .sum();
    }
}
