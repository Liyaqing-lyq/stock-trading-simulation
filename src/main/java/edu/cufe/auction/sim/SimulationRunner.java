package edu.cufe.auction.sim;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.account.AccountManager;
import edu.cufe.auction.account.PerformanceRank;
import edu.cufe.auction.agent.AgentType;
import edu.cufe.auction.agent.HumanBrokerAgent;
import edu.cufe.auction.agent.MomentumAgent;
import edu.cufe.auction.agent.NoiseAgent;
import edu.cufe.auction.agent.TradingAgent;
import edu.cufe.auction.agent.ai.DeepSeekClient;
import edu.cufe.auction.agent.ai.LlmTradingAgent;
import edu.cufe.auction.config.SimulationConfig;
import edu.cufe.auction.engine.MatchingEngine;
import edu.cufe.auction.io.CsvHistoryRecorder;
import edu.cufe.auction.market.MarketDataListener;
import edu.cufe.auction.market.MarketDataPublisher;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 模拟运行器：装配引擎、账户、agent 与定时器，驱动整个交易周期。
 *
 * <p>线程模型（对应规格 4.4）：</p>
 * <ul>
 *   <li>调度线程池按各自间隔调用 {@code agent.onTick()}，多个 agent 真并发；
 *       撮合与账户变更的线程安全由 {@link MatchingEngine} 的锁保证；</li>
 *   <li>每秒一次权益标记线程，更新最大回撤曲线；</li>
 *   <li>行情广播在业务线程内同步派发，GUI 监听者自行切回 JavaFX 线程。</li>
 * </ul>
 *
 * <p>用法：{@code try (SimulationRunner runner = new SimulationRunner(config)) { runner.start(); ... runner.closeAndRank(); }}</p>
 */
public final class SimulationRunner implements AutoCloseable {

    private final SimulationConfig config;
    private final AccountManager accountManager = new AccountManager();
    private final MarketDataPublisher publisher = new MarketDataPublisher();
    private final MatchingEngine engine;
    private final CsvHistoryRecorder recorder;
    private final List<TradingAgent> agents = new ArrayList<>();
    private final ScheduledExecutorService scheduler;

    private volatile boolean running;

    /**
     * 按配置装配整个模拟。
     *
     * @param config 模拟配置
     * @throws IOException 初始化 CSV 记录器失败
     */
    public SimulationRunner(SimulationConfig config) throws IOException {
        this.config = config;
        this.recorder = new CsvHistoryRecorder(config.getDataDir());
        this.engine = new MatchingEngine(accountManager, publisher, config.getStocks());
        this.engine.addListener(recorder);
        this.publisher.subscribe(recorder);

        createHumanAgents();
        createAiAgents();
        createNoiseAgents();

        // agent 必须订阅行情总线，否则拿不到行情快照就永远不下单（价格发现无从谈起）
        for (TradingAgent agent : agents) {
            if (agent instanceof MarketDataListener listener) {
                publisher.subscribe(listener);
            }
        }

        this.scheduler = Executors.newScheduledThreadPool(
                Math.max(2, Math.min(8, agents.size())),
                r -> {
                    Thread t = new Thread(r, "auction-agent");
                    t.setDaemon(true);
                    return t;
                });
    }

    private void createHumanAgents() {
        for (int i = 1; i <= config.getHumanCount(); i++) {
            String id = "human-" + i;
            String name = "人类Broker-" + (char) ('A' + i - 1);
            Account account = accountManager.createAccount(id, name, config.getHumanInitialBalance());
            seedHoldings(account, "agent.human.initial.holdings");
            agents.add(new HumanBrokerAgent(id, name, account, engine));
        }
    }

    /**
     * 按配置给账户注入初始持仓。
     *
     * <p>规格只定义了初始资金：若所有人一开始都只有现金，卖出校验必然失败，
     * 市场永远无法产生第一笔成交，因此必须支持初始持仓（见 config.properties 说明）。</p>
     */
    private void seedHoldings(Account account, String configKey) {
        config.getInitialHoldings(configKey).forEach((symbol, quantity) -> {
            BigDecimal reference = config.getStocks().get(symbol);
            if (reference == null) {
                throw new IllegalStateException("初始持仓的标的未在 stocks 中配置：" + symbol);
            }
            account.seedPosition(symbol, quantity, reference);
        });
    }

    private void createAiAgents() {
        String apiKey = config.getLlmApiKey();
        boolean llmAvailable = apiKey != null && !apiKey.isBlank();
        if (config.getAiCount() > 0 && !llmAvailable) {
            System.err.println("[SimulationRunner] 未检测到 LLM_API_KEY，AI agent 回退为规则型动量策略；"
                    + "设置环境变量后重跑即可启用真正的 LLM 决策。");
        }
        for (int i = 1; i <= config.getAiCount(); i++) {
            String id = "ai-" + i;
            String name = "AI交易员-" + i;
            Account account = accountManager.createAccount(id, name, config.getAiInitialBalance());
            seedHoldings(account, "agent.ai.initial.holdings");
            if (llmAvailable) {
                LlmTradingAgent agent = new LlmTradingAgent(id, name, account, engine,
                        new DeepSeekClient(config.getLlmEndpoint(), apiKey, config.getLlmModel(),
                                config.getLlmRateLimitMillis(), config.getLlmTimeoutMillis()),
                        config.getLlmPriceBandPercent(), 100L, MatchingEngine.DEFAULT_DEPTH);
                agents.add(agent);
            } else {
                agents.add(new MomentumAgent(id, name, account, engine, 5, 100L, new BigDecimal("20")));
            }
        }
    }

    private void createNoiseAgents() {
        for (int i = 1; i <= config.getNoiseCount(); i++) {
            String id = "noise-" + i;
            String name = "噪音做市-" + i;
            Account account = accountManager.createAccount(id, name, config.getNoiseInitialBalance());
            seedHoldings(account, "agent.noise.initial.holdings");
            agents.add(new NoiseAgent(id, name, account, engine, 20260924L + i,
                    new BigDecimal("1.5"), 50L, 300L));
        }
    }

    /** 启动定时器，开始交易周期。 */
    public void start() {
        if (running) {
            throw new IllegalStateException("模拟已在运行");
        }
        running = true;
        publishInitialSnapshots();
        long baseTick = Math.max(50L, config.getTickIntervalMillis());
        for (TradingAgent agent : agents) {
            long interval = agent.getType() == AgentType.AI && agent instanceof LlmTradingAgent
                    ? Math.max(baseTick, config.getAiDecisionIntervalSeconds() * 1000L)
                    : baseTick;
            scheduler.scheduleWithFixedDelay(guard(agent), interval, interval, TimeUnit.MILLISECONDS);
        }
        scheduler.scheduleWithFixedDelay(
                () -> accountManager.markAllEquity(engine.lastPrices()), 1, 1, TimeUnit.SECONDS);
    }

    /**
     * 开盘前把初始行情快照推给所有 agent。
     *
     * <p><b>为什么必须做</b>：行情快照原本只在“有订单受理/撤单”后才推送，
     * 而 agent 的决策又依赖最新快照（拿不到就不下单）——形成死锁：没人下单 →
     * 没有行情 → 没人下单。开盘先推一次开盘行情即可打破该循环。</p>
     */
    private void publishInitialSnapshots() {
        for (String symbol : engine.symbols()) {
            publisher.publishSnapshot(engine.marketSnapshot(symbol, MatchingEngine.DEFAULT_DEPTH));
        }
    }

    private Runnable guard(TradingAgent agent) {
        return () -> {
            try {
                agent.onTick();
            } catch (RuntimeException ex) {
                System.err.printf("[SimulationRunner] agent %s tick 异常：%s%n", agent.getAgentId(), ex);
            }
        };
    }

    /**
     * 收盘：停调度、撤挂单、写排行榜。
     *
     * @return 期末排行榜（按收益率降序）
     * @throws IOException 写 ranking.csv 失败
     */
    public List<PerformanceRank> closeAndRank() throws IOException {
        running = false;
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        int cancelled = engine.closeMarket();
        Map<String, BigDecimal> prices = engine.lastPrices();
        accountManager.markAllEquity(prices);
        List<PerformanceRank> ranks = accountManager.ranking(prices);
        recorder.writeRanking(ranks);
        System.out.printf("[SimulationRunner] 收盘：撤销挂单 %d 笔，排行榜已写入 %s%n",
                cancelled, recorder.getDirectory().resolve("ranking.csv").toAbsolutePath());
        return ranks;
    }

    /** @return 撮合引擎 */
    public MatchingEngine getEngine() {
        return engine;
    }

    /** @return 行情发布者 */
    public MarketDataPublisher getPublisher() {
        return publisher;
    }

    /** @return 账户管理器 */
    public AccountManager getAccountManager() {
        return accountManager;
    }

    /** @return CSV 记录器 */
    public CsvHistoryRecorder getRecorder() {
        return recorder;
    }

    /** @return 全部 agent（只读） */
    public List<TradingAgent> getAgents() {
        return Collections.unmodifiableList(agents);
    }

    /**
     * 取第一个人类 broker（GUI 下单入口）。
     *
     * @return 人类 broker；未配置时返回 null
     */
    public HumanBrokerAgent firstHumanBroker() {
        for (TradingAgent agent : agents) {
            if (agent instanceof HumanBrokerAgent) {
                return (HumanBrokerAgent) agent;
            }
        }
        return null;
    }

    /** @return 配置 */
    public SimulationConfig getConfig() {
        return config;
    }

    @Override
    public void close() {
        running = false;
        scheduler.shutdownNow();
        recorder.close();
    }
}
