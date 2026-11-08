package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.account.AccountManager;
import edu.cufe.auction.engine.EngineListener;
import edu.cufe.auction.engine.MatchingEngine;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.market.MarketDataPublisher;
import edu.cufe.auction.model.InstrumentProfile;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新增策略 agent 的价格合规回归测试。
 *
 * <p>这里使用真实 {@link MatchingEngine} 受理订单，避免记录型网关把越界价或
 * 非 tick 网格价误判为成功。</p>
 */
class StrategyAgentPriceComplianceTest {

    private static final String SYMBOL = "V001";
    private static final BigDecimal TICK_FIVE_CENTS = new BigDecimal("0.05");

    @Test
    @DisplayName("价值型：估值锚不在 tick 网格上时仍应生成合法价格")
    void valueAgentRoundsFairValueToConfiguredTick() {
        AccountManager accounts = new AccountManager();
        Account valueAccount = accounts.createAccount(
                "value", "价值型", new BigDecimal("200000.00"));
        Account buyer = accounts.createAccount("buyer", "买方", new BigDecimal("200000.00"));
        Account seller = accounts.createAccount("seller", "卖方", new BigDecimal("200000.00"));
        seller.seedPosition(SYMBOL, 100L, new BigDecimal("100.0000"));

        MatchingEngine engine = new MatchingEngine(accounts, new MarketDataPublisher(),
                List.of(new InstrumentProfile(SYMBOL, new BigDecimal("100.01"),
                        new BigDecimal("10"), TICK_FIVE_CENTS)));
        assertAccepted(engine.submit(OrderRequest.limit(
                "buyer", SYMBOL, Side.BUY, new BigDecimal("97.500"), 10L)));
        assertAccepted(engine.submit(OrderRequest.limit(
                "seller", SYMBOL, Side.SELL, new BigDecimal("97.500"), 10L)));
        assertAccepted(engine.submit(OrderRequest.limit(
                "seller", SYMBOL, Side.SELL, new BigDecimal("100.05"), 10L)));
        assertAccepted(engine.submit(OrderRequest.limit(
                "buyer", SYMBOL, Side.BUY, new BigDecimal("100.00"), 10L)));

        MarketSnapshot snapshot = engine.marketSnapshot(SYMBOL, MatchingEngine.DEFAULT_DEPTH);
        assertEquals(new BigDecimal("97.500"), snapshot.getLastPrice());
        assertEquals(new BigDecimal("100.05"), snapshot.getBestAsk());

        ValueAgent agent = new ValueAgent("value", "价值型", valueAccount, engine,
                new BigDecimal("2"), 10L);
        agent.onMarketSnapshot(snapshot);
        agent.onTick();

        assertEquals(1L, agent.getSubmittedCount());
        assertEquals(0L, agent.getRejectedCount(),
                "昨收 100.01 不在 0.05 网格上，直接按估值锚挂单会被撮合引擎拒绝");
        assertEquals(1, agent.getLiveOrderCount());
    }

    @Test
    @DisplayName("逆势型：无对手盘时回退报价不得突破涨停")
    void contrarianAgentClampsFallbackPriceToDailyUpperLimit() {
        AccountManager accounts = new AccountManager();
        Account account = accounts.createAccount(
                "contrarian", "逆势型", new BigDecimal("200000.00"));
        MatchingEngine engine = new MatchingEngine(accounts, new MarketDataPublisher(),
                List.of(profile(SYMBOL, "100.00", "10.00", "0.01")));

        ContrarianAgent agent = new ContrarianAgent("contrarian", "逆势型", account, engine,
                100, new BigDecimal("0.1"), 10L);
        // 先以涨停价建立均线，再用略低价格触发买入；回退价 109.5 × 1.005 会超过涨停。
        for (int i = 0; i < 100; i++) {
            agent.onMarketSnapshot(snapshot("110.00"));
            agent.onTick();
        }
        agent.onMarketSnapshot(snapshot("109.50"));
        agent.onTick();

        assertEquals(1L, agent.getSubmittedCount());
        assertEquals(0L, agent.getRejectedCount(),
                "109.5 × 1.005 会突破涨停，策略必须先夹到价格边界");
        assertEquals(1, agent.getLiveOrderCount());
    }

    @Test
    @DisplayName("价值型：估值锚不在 tick 网格上时卖价不得低于估值")
    void valueAgentRoundsSellQuoteUpToConfiguredTick() {
        AccountManager accounts = new AccountManager();
        Account account = accounts.createAccount(
                "value", "价值型", new BigDecimal("200000.00"));
        account.seedPosition(SYMBOL, 100L, new BigDecimal("100.0000"));
        MatchingEngine engine = new MatchingEngine(accounts, new MarketDataPublisher(),
                List.of(profile(SYMBOL, "100.01", "10.00", "0.05")));
        List<String> rejectionReasons = recordRejections(engine);
        MarketSnapshot snapshot = new MarketSnapshot(SYMBOL, new BigDecimal("101.00"),
                new BigDecimal("100.01"), new BigDecimal("90.00"), new BigDecimal("110.00"),
                TICK_FIVE_CENTS,
                new BigDecimal("99.95"), new BigDecimal("100.05"),
                0L, List.of(), List.of(), 0L);

        ValueAgent agent = new ValueAgent("value", "价值型", account, engine,
                BigDecimal.ZERO, 10L);
        agent.onMarketSnapshot(snapshot);
        agent.onTick();

        assertEquals(1L, agent.getSubmittedCount());
        assertEquals(0L, agent.getRejectedCount(), rejectionReasons::toString);
        BigDecimal sellPrice = engine.allRestingOrders().get(0).getLimitPrice();
        assertTrue(sellPrice.compareTo(snapshot.getPreviousClose()) >= 0,
                "卖价量化后不能低于估值锚: " + sellPrice);
    }

    @Test
    @DisplayName("做市商：五分钱 tick 下双边报价应被引擎受理")
    void marketMakerQuotesStayOnConfiguredTick() {
        AccountManager accounts = new AccountManager();
        Account account = accounts.createAccount(
                "maker", "做市商", new BigDecimal("300000.00"));
        account.seedPosition(SYMBOL, 500L, new BigDecimal("100.0000"));
        MatchingEngine engine = new MatchingEngine(accounts, new MarketDataPublisher(),
                List.of(profile(SYMBOL, "100.00", "10.00", "0.05")));
        List<String> rejectionReasons = recordRejections(engine);
        MarketSnapshot snapshot = new MarketSnapshot(SYMBOL, new BigDecimal("100.00"),
                new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("110.00"),
                TICK_FIVE_CENTS,
                new BigDecimal("100.00"), new BigDecimal("100.05"),
                0L, List.of(), List.of(), 0L);

        MarketMakerAgent agent = new MarketMakerAgent("maker", "做市商", account, engine,
                new BigDecimal("0.4"), new BigDecimal("0.3"), 100L, Map.of());
        agent.onMarketSnapshot(snapshot);
        agent.onTick();

        assertEquals(2L, agent.getSubmittedCount());
        assertEquals(0L, agent.getRejectedCount(),
                () -> "99.62 / 100.43 不属于 0.05 的价位网格：" + rejectionReasons);
        assertEquals(2, agent.getLiveOrderCount());
    }

    @Test
    @DisplayName("做市商：涨停买一附近不得提交穿越买一的卖单")
    void marketMakerDoesNotCrossBestBidAtUpperLimit() {
        AccountManager accounts = new AccountManager();
        Account maker = accounts.createAccount(
                "maker", "做市商", new BigDecimal("300000.00"));
        maker.seedPosition(SYMBOL, 500L, new BigDecimal("100.0000"));
        Account buyer = accounts.createAccount(
                "buyer", "买方", new BigDecimal("300000.00"));
        MatchingEngine engine = new MatchingEngine(accounts, new MarketDataPublisher(),
                List.of(profile(SYMBOL, "100.00", "10.00", "0.01")));
        assertAccepted(engine.submit(OrderRequest.limit(
                "buyer", SYMBOL, Side.BUY, new BigDecimal("110.00"), 100L)));

        MarketSnapshot snapshot = engine.marketSnapshot(SYMBOL, MatchingEngine.DEFAULT_DEPTH);
        assertEquals(new BigDecimal("110.00"), snapshot.getBestBid());
        assertEquals(0L, snapshot.getCumulativeVolume());

        MarketMakerAgent agent = new MarketMakerAgent("maker", "做市商", maker, engine,
                new BigDecimal("0.4"), new BigDecimal("0.3"), 100L, Map.of());
        agent.onMarketSnapshot(snapshot);
        agent.onTick();

        assertEquals(0L, engine.marketSnapshot(
                        SYMBOL, MatchingEngine.DEFAULT_DEPTH).getCumulativeVolume(),
                "买一为涨停价时，卖价回夹后只有 110.00；此时应放弃卖单而不是主动成交");
    }

    @Test
    @DisplayName("价值型：非正报价数量应在构造时拒绝")
    void valueAgentRejectsNonPositiveQuantity() {
        Account account = new Account("value", "价值型", new BigDecimal("100000.00"));

        assertThrows(IllegalArgumentException.class, () -> new ValueAgent(
                "value", "价值型", account, acceptingGateway(), new BigDecimal("2"), 0L));
    }

    @Test
    @DisplayName("逆势型：非正均线窗口应在构造时拒绝")
    void contrarianAgentRejectsNonPositiveWindow() {
        Account account = new Account("contrarian", "逆势型", new BigDecimal("100000.00"));

        assertThrows(IllegalArgumentException.class, () -> new ContrarianAgent(
                "contrarian", "逆势型", account, acceptingGateway(),
                0, new BigDecimal("1.5"), 100L));
    }

    @Test
    @DisplayName("做市商：非正报价数量应在构造时拒绝")
    void marketMakerRejectsNonPositiveQuantity() {
        Account account = new Account("maker", "做市商", new BigDecimal("100000.00"));

        assertThrows(IllegalArgumentException.class, () -> new MarketMakerAgent(
                "maker", "做市商", account, acceptingGateway(),
                new BigDecimal("0.4"), new BigDecimal("0.3"), 0L, Map.of()));
    }

    @Test
    @DisplayName("价值型：空阈值应在构造时拒绝")
    void valueAgentRejectsNullThreshold() {
        Account account = new Account("value", "价值型", new BigDecimal("100000.00"));

        assertThrows(IllegalArgumentException.class, () -> new ValueAgent(
                "value", "价值型", account, acceptingGateway(), null, 100L));
    }

    @Test
    @DisplayName("逆势型：空阈值应在构造时拒绝")
    void contrarianAgentRejectsNullThreshold() {
        Account account = new Account("contrarian", "逆势型", new BigDecimal("100000.00"));

        assertThrows(IllegalArgumentException.class, () -> new ContrarianAgent(
                "contrarian", "逆势型", account, acceptingGateway(),
                2, null, 100L));
    }

    @Test
    @DisplayName("做市商：负价差应在构造时拒绝")
    void marketMakerRejectsNegativeSpread() {
        Account account = new Account("maker", "做市商", new BigDecimal("100000.00"));

        assertThrows(IllegalArgumentException.class, () -> new MarketMakerAgent(
                "maker", "做市商", account, acceptingGateway(),
                new BigDecimal("-0.1"), new BigDecimal("0.3"), 100L, Map.of()));
    }

    private static MarketSnapshot snapshot(String lastPrice) {
        return new MarketSnapshot(SYMBOL, new BigDecimal(lastPrice),
                new BigDecimal("100.00"), new BigDecimal("90.00"), new BigDecimal("110.00"),
                null, null, 0L, List.of(), List.of(), 0L);
    }

    private static InstrumentProfile profile(
            String symbol, String close, String limit, String tick) {
        return new InstrumentProfile(symbol, new BigDecimal(close), new BigDecimal(limit),
                new BigDecimal(tick));
    }

    private static void assertAccepted(OrderResult result) {
        assertTrue(result.isAccepted(), () -> "准备测试行情时订单被拒：" + result.getReason());
    }

    private static List<String> recordRejections(MatchingEngine engine) {
        List<String> reasons = new CopyOnWriteArrayList<>();
        engine.addListener(new EngineListener() {
            @Override
            public void onOrderRejected(OrderRequest request, String reason) {
                reasons.add(request.getSide() + " " + request.getPrice() + ": " + reason);
            }
        });
        return reasons;
    }

    private static OrderGateway acceptingGateway() {
        return new OrderGateway() {
            @Override
            public OrderResult submit(OrderRequest request) {
                return OrderResult.rejected("本测试不应提交订单");
            }

            @Override
            public boolean cancel(long orderId) {
                return false;
            }
        };
    }
}
