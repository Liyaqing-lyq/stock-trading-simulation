package edu.cufe.auction.app;

import edu.cufe.auction.account.PerformanceRank;
import edu.cufe.auction.agent.HumanBrokerAgent;
import edu.cufe.auction.agent.TradingAgent;
import edu.cufe.auction.config.SimulationConfig;
import edu.cufe.auction.engine.MatchingEngine;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.OrderBookSnapshot;
import edu.cufe.auction.model.PriceLevel;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.sim.SimulationRunner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 控制台演示入口：无 GUI 跑一段模拟，打印行情、成交与期末排行榜。
 *
 * <p>运行：{@code mvn exec:java -Dexec.mainClass=edu.cufe.auction.app.ConsoleApp -Dexec.args="15"}</p>
 */
public final class ConsoleApp {

    private ConsoleApp() {
    }

    /**
     * 主入口。
     *
     * @param args 可选：第一个参数为运行秒数（默认 20）
     * @throws Exception 初始化或运行失败
     */
    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        SimulationConfig config = SimulationConfig.loadDefault();

        try (SimulationRunner runner = new SimulationRunner(config)) {
            System.out.println("=== 多智能体连续竞价模拟（控制台版） ===");
            System.out.println("标的：" + config.getStocks());
            System.out.println("运行时长：" + seconds + " 秒；LLM Key "
                    + (config.getLlmApiKey().isBlank() ? "未配置（AI 走规则回退）" : "已配置"));
            runner.start();

            HumanBrokerAgent brokerA = runner.firstHumanBroker();
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            int step = 0;
            while (System.currentTimeMillis() < deadline) {
                step++;
                scriptedManualOrders(brokerA, runner, step);
                Thread.sleep(1000L);
                if (step % 2 == 0) {
                    printMarket(runner);
                }
            }

            List<PerformanceRank> ranks = runner.closeAndRank();
            printRanking(ranks);
            printAgentStats(runner);
            System.out.println("CSV 输出目录：" + runner.getRecorder().getDirectory().toAbsolutePath());
        }
    }

    /**
     * 演示脚本：模拟人类 broker 手工下单，展示“人类订单与噪音/AI 订单碰撞产生价格”的过程。
     */
    private static void scriptedManualOrders(HumanBrokerAgent broker, SimulationRunner runner, int step) {
        if (broker == null) {
            return;
        }
        String symbol = runner.getEngine().symbols().get(0);
        BigDecimal reference = runner.getConfig().getStocks().get(symbol);
        switch (step) {
            case 1:
                BigDecimal chase = reference.multiply(new BigDecimal("1.01")).setScale(2, RoundingMode.HALF_UP);
                broker.placeLimitOrder(symbol, Side.BUY, chase, 50L);
                System.out.printf("[演示] 人类 broker 追价买入 %s 50 股 @ %s%n", symbol, chase.toPlainString());
                break;
            case 3:
                BigDecimal passive = reference.multiply(new BigDecimal("0.99")).setScale(2, RoundingMode.HALF_UP);
                broker.placeLimitOrder(symbol, Side.BUY, passive, 50L);
                System.out.printf("[演示] 人类 broker 挂低价买单 %s 50 股 @ %s%n", symbol, passive.toPlainString());
                break;
            case 5:
                long holding = broker.getAccount().getAvailableQuantity(symbol);
                if (holding > 0) {
                    BigDecimal ask = reference.multiply(new BigDecimal("1.02")).setScale(2, RoundingMode.HALF_UP);
                    broker.placeLimitOrder(symbol, Side.SELL, ask, Math.min(20L, holding));
                    System.out.printf("[演示] 人类 broker 卖出 %s %d 股 @ %s%n",
                            symbol, Math.min(20L, holding), ask.toPlainString());
                }
                break;
            default:
                break;
        }
    }

    private static void printMarket(SimulationRunner runner) {
        MatchingEngine engine = runner.getEngine();
        for (String symbol : engine.symbols()) {
            MarketSnapshot snapshot = engine.marketSnapshot(symbol, MatchingEngine.DEFAULT_DEPTH);
            OrderBookSnapshot book = engine.snapshot(symbol, MatchingEngine.DEFAULT_DEPTH);
            System.out.printf("%-6s 最新 %-8s 涨跌 %6s%%  量 %-6d  买一 %-8s 卖一 %-8s%n",
                    symbol,
                    snapshot.getLastPrice() == null ? "—" : snapshot.getLastPrice().toPlainString(),
                    snapshot.getChangePercent().setScale(2, RoundingMode.HALF_UP),
                    snapshot.getCumulativeVolume(),
                    value(book == null ? null : book.getBestBid()),
                    value(book == null ? null : book.getBestAsk()));
        }
        System.out.println("  " + depthLine(engine, engine.symbols().get(0)));
    }

    private static String depthLine(MatchingEngine engine, String symbol) {
        OrderBookSnapshot book = engine.snapshot(symbol, 3);
        if (book == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(symbol).append(" 买盘 ");
        for (PriceLevel level : book.getBids()) {
            sb.append(level.getPrice().toPlainString()).append('×').append(level.getQuantity()).append(' ');
        }
        sb.append("| 卖盘 ");
        for (PriceLevel level : book.getAsks()) {
            sb.append(level.getPrice().toPlainString()).append('×').append(level.getQuantity()).append(' ');
        }
        return sb.toString();
    }

    private static String value(BigDecimal price) {
        return price == null ? "—" : price.toPlainString();
    }

    private static void printRanking(List<PerformanceRank> ranks) {
        System.out.println();
        System.out.println("=== 期末排行榜（按收益率） ===");
        ranks.forEach(r -> System.out.println("  " + r));
    }

    private static void printAgentStats(SimulationRunner runner) {
        System.out.println();
        System.out.println("=== agent 统计 ===");
        for (TradingAgent agent : runner.getAgents()) {
            System.out.println("  " + agent);
        }
    }
}
