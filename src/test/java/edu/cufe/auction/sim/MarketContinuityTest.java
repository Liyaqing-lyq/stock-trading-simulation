package edu.cufe.auction.sim;

import edu.cufe.auction.agent.AbstractTradingAgent;
import edu.cufe.auction.agent.AgentType;
import edu.cufe.auction.config.SimulationConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：市场必须能「持续」成交，不允许运行一段时间后停摆。
 *
 * <p><b>被锁定的历史 bug</b>：agent 只挂单、从不撤单，限价单占用的资金/持仓被永久冻结；
 * 额度耗尽后 agent 再也下不出单，盘口只剩一堆永不成交的陈旧挂单，成交量归零。
 * 实测（tick = 1 秒）：第 40-49 秒成交量已从约 1500 股掉到 139 股，第 50-69 秒完全为 0。</p>
 *
 * <p>本测试从「行为」层面锁住修复效果，不依赖具体实现细节：</p>
 * <ol>
 *   <li>运行期间必须出现<b>主动撤单</b>——报价刷新的直接证据，旧实现撤单数恒为 0；</li>
 *   <li>模拟<b>末段窗口内仍有成交</b>——旧实现在该窗口已经停摆；</li>
 *   <li>存活挂单总量<b>有界</b>，不随运行时间无界增长。</li>
 * </ol>
 */
class MarketContinuityTest {

    /** 判定「末段窗口」的起点比例：取运行时长的后 30%。 */
    private static final double TAIL_WINDOW_START_RATIO = 0.7;

    /** 存活挂单总量的上界：报价刷新正常时远低于此值。 */
    private static final int MAX_LIVE_ORDERS = 200;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Test
    @DisplayName("市场持续成交：运行期间有主动撤单、末段仍有成交、存活挂单有界")
    void marketKeepsTrading() throws Exception {
        Path dir = Files.createTempDirectory("auction-continuity");
        Path configFile = dir.resolve("continuity.properties");
        String dataDir = dir.resolve("data").toString().replace('\\', '/');
        Files.writeString(configFile, String.join("\n",
                "simulation.tick.interval.ms=50",
                "simulation.duration=4",
                "stocks=AAPL,GOOGL,TSLA",
                "AAPL=175.50",
                "GOOGL=142.30",
                "TSLA=245.30",
                "agent.human.count=0",
                "agent.ai.count=2",
                "agent.ai.initial.balance=50000.00",
                "agent.ai.initial.holdings=AAPL=200,GOOGL=150,TSLA=100",
                "agent.ai.window.size=5",
                "agent.ai.order.quantity=100",
                "agent.ai.position.budget.percent=20",
                "agent.noise.count=2",
                "agent.noise.initial.balance=80000.00",
                "agent.noise.initial.holdings=AAPL=800,GOOGL=400,TSLA=300",
                "io.data.dir=" + dataDir,
                ""), StandardCharsets.UTF_8);

        SimulationConfig config = SimulationConfig.load(configFile);
        try (SimulationRunner runner = new SimulationRunner(config)) {
            LocalDateTime startedAt = LocalDateTime.now();
            runner.start();
            Thread.sleep(4200L);
            runner.closeAndRank();
            LocalDateTime stoppedAt = LocalDateTime.now();

            // 1) 运行期间必须出现主动撤单——旧实现只有收盘才会撤单，撤单数恒为 0
            long totalCancels = runner.getAgents().stream()
                    .filter(AbstractTradingAgent.class::isInstance)
                    .mapToLong(agent -> ((AbstractTradingAgent) agent).getCancelledCount())
                    .sum();
            assertTrue(totalCancels > 0,
                    "运行期间应出现主动撤单（报价刷新），否则冻结额度无法回收");

            long noiseCancels = runner.getAgents().stream()
                    .filter(agent -> agent.getType() == AgentType.NOISE
                            && agent instanceof AbstractTradingAgent)
                    .mapToLong(agent -> ((AbstractTradingAgent) agent).getCancelledCount())
                    .sum();
            assertTrue(noiseCancels > 0, "做市/噪音 agent 应持续撤单改价");

            // 2) 存活挂单必须收敛，不能无界增长
            int liveOrders = runner.getAgents().stream()
                    .filter(AbstractTradingAgent.class::isInstance)
                    .mapToInt(agent -> ((AbstractTradingAgent) agent).getLiveOrderCount())
                    .sum();
            assertTrue(liveOrders < MAX_LIVE_ORDERS, "存活挂单应有界，实际 " + liveOrders);

            // 3) 末段窗口内仍有成交——「市场没停摆」最直接的行为证据
            Path tradesFile = runner.getRecorder().getDirectory().resolve("trades.csv");
            List<String[]> trades = Files.readAllLines(tradesFile, StandardCharsets.UTF_8).stream()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(",", -1))
                    .collect(Collectors.toList());
            assertTrue(trades.size() > 10,
                    "样本量太小，无法判断持续性，实际成交 " + trades.size() + " 笔");

            LocalDateTime tailStart = startedAt.plusNanos(
                    (long) (Duration.between(startedAt, stoppedAt).toNanos() * TAIL_WINDOW_START_RATIO));
            Map<Boolean, Long> split = trades.stream().collect(Collectors.partitioningBy(
                    columns -> LocalDateTime.parse(columns[0], TS).isAfter(tailStart),
                    Collectors.counting()));
            long tailTrades = split.getOrDefault(Boolean.TRUE, 0L);
            assertTrue(tailTrades > 0, String.format(
                    "运行末段（%s 之后）必须仍有成交，否则市场已停摆；实际全部 %d 笔成交都集中在运行前段",
                    tailStart, trades.size()));
        }
    }
}
