package edu.cufe.auction.sim;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.agent.AbstractTradingAgent;
import edu.cufe.auction.agent.AgentType;
import edu.cufe.auction.agent.HumanBrokerAgent;
import edu.cufe.auction.config.SimulationConfig;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.Side;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 *
 * <p><b>为什么需要一个受控对手</b>：noise/momentum 都只挂被动限价单。实测证据
 * （logs/implementation-20261106/governance/continuity-investigation/）显示，纯随机场景下
 * 两家 noise 的库存偏移与自成交拦截会让盘口在每个标的上都收敛到同一侧
 * （收盘：AAPL 全卖、GOOGL/TSLA 全买），此时撮合在数学上不可能发生；
 * 而动量策略只在「最新价 ≠ 窗口均价」时出手，没有成交就没有价差，于是全场静默。
 * 这与「冻结额度被耗尽」是两件事：那一轮里 agent 仍在成功下单、仍在撤单
 * （末笔成交之后 1.98 秒内 NEW 110 笔、CANCEL 101 笔、0 笔容量拒绝）。</p>
 *
 * <p>因此测试引入一个有<b>有限真实资金/库存</b>的人类 broker 作为主动对手：
 * 它用很小的市价单持续吃掉盘口上真实存在的 noise 挂单，产生真实撮合、真实成交、
 * 真实价格变化。这样 {@code tailTrades > 0} 检验的是「报价刷新是否持续支撑交易」，
 * 而不是「随机策略必须永远成交」。对手不制造成交、不无限供资、不与自身成交。</p>
 */
class MarketContinuityTest {

    /** 判定「末段窗口」的起点比例：取运行时长的后 30%。 */
    private static final double TAIL_WINDOW_START_RATIO = 0.7;

    /** 存活挂单总量的上界：报价刷新正常时远低于此值。 */
    private static final int MAX_LIVE_ORDERS = 200;

    /** 受控对手每笔市价单的数量：远小于 noise 的单笔报价量，只做「消耗」不做「扫盘」。 */
    private static final long COUNTERPARTY_LOT = 10L;

    /** 受控对手的下单周期与起始延迟（毫秒）。 */
    private static final long COUNTERPARTY_PERIOD_MS = 120L;
    private static final long COUNTERPARTY_INITIAL_DELAY_MS = 100L;

    /** 受控对手轮换的标的。 */
    private static final String[] SYMBOLS = {"AAPL", "GOOGL", "TSLA"};

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
                // 受控主动对手：有限资金 + 有限库存，只做小额市价单
                "agent.human.count=1",
                "agent.human.initial.balance=100000.00",
                "agent.human.initial.holdings=AAPL=200,GOOGL=200,TSLA=200",
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
            HumanBrokerAgent counterparty = runner.firstHumanBroker();
            assertNotNull(counterparty, "配置了 agent.human.count=1，必须存在人类 broker 作为受控对手");

            // 受控对手的定时器：只入队市价单，真正提交发生在 broker 自己的 tick 里。
            // 异常必须显式记录并在断言阶段报出，不能被调度线程静默吞掉。
            AtomicReference<Throwable> counterpartyFailure = new AtomicReference<>();
            AtomicInteger counterpartyRound = new AtomicInteger();
            ScheduledExecutorService counterpartyScheduler =
                    Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "continuity-counterparty");
                        thread.setDaemon(true);
                        return thread;
                    });

            LocalDateTime startedAt = LocalDateTime.now();
            runner.start();
            counterpartyScheduler.scheduleAtFixedRate(
                    counterpartyProbe(counterparty, counterpartyRound, counterpartyFailure),
                    COUNTERPARTY_INITIAL_DELAY_MS, COUNTERPARTY_PERIOD_MS, TimeUnit.MILLISECONDS);
            try {
                Thread.sleep(4200L);
            } finally {
                // 自己的定时器先停，再收盘；否则人工下单会穿插进收盘撤单
                counterpartyScheduler.shutdownNow();
            }
            boolean counterpartyStopped = counterpartyScheduler.awaitTermination(2, TimeUnit.SECONDS);
            runner.closeAndRank();
            LocalDateTime stoppedAt = LocalDateTime.now();

            assertTrue(counterpartyStopped, "受控对手定时器未能在收盘前停止");
            Throwable counterpartyError = counterpartyFailure.get();
            assertTrue(counterpartyError == null, "受控对手定时任务异常：" + counterpartyError);
            assertTrue(counterparty.getFilledQuantity() > 0,
                    "受控对手必须产生真实成交，否则末段成交断言无法证明市场仍在服务交易");

            Account counterpartyAccount = counterparty.getAccount();
            assertTrue(counterpartyAccount.getAvailableCash().signum() >= 0,
                    "受控对手的有限资金被透支，实际可用现金 " + counterpartyAccount.getAvailableCash());
            assertTrue(counterpartyAccount.getFrozenCash().signum() == 0,
                    "收盘后受控对手不应残留冻结资金，实际 " + counterpartyAccount.getFrozenCash());

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

    /**
     * 构造受控对手的一轮动作：轮换标的，优先吃卖一、没有卖一则吃买一；两侧都空就跳过本轮。
     *
     * <p>只读取公开行情快照，不碰订单簿内部结构；下的是真实市价单，
     * 由撮合引擎真实成交，不会为了「凑成交」伪造任何数据。</p>
     *
     * @param broker  受控人类 broker
     * @param round   轮次计数器（决定标的与方向）
     * @param failure 异常记录点
     * @return 可被定时器执行的任务
     */
    private static Runnable counterpartyProbe(HumanBrokerAgent broker, AtomicInteger round,
                                              AtomicReference<Throwable> failure) {
        return () -> {
            try {
                int tick = round.getAndIncrement();
                String symbol = SYMBOLS[Math.floorMod(tick, SYMBOLS.length)];
                MarketSnapshot snapshot = broker.latestSnapshot(symbol);
                if (snapshot == null) {
                    return;
                }
                Side preferred = (tick % 2 == 0) ? Side.BUY : Side.SELL;
                Side side;
                if (hasLiquidity(snapshot, preferred)) {
                    side = preferred;
                } else if (hasLiquidity(snapshot, opposite(preferred))) {
                    side = opposite(preferred);
                } else {
                    return;
                }
                broker.placeMarketOrder(symbol, side, COUNTERPARTY_LOT);
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        };
    }

    private static boolean hasLiquidity(MarketSnapshot snapshot, Side side) {
        return side == Side.BUY ? snapshot.getBestAsk() != null : snapshot.getBestBid() != null;
    }

    private static Side opposite(Side side) {
        return side == Side.BUY ? Side.SELL : Side.BUY;
    }
}
