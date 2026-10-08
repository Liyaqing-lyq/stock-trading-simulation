package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.account.AccountManager;
import edu.cufe.auction.engine.MatchingEngine;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.market.MarketDataPublisher;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.Side;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** agent 挂单刷新与持续交易回归测试。 */
class MarketContinuityTest {

    @Test
    @DisplayName("被动成交会更新 agent 挂单统计，撤单后释放剩余冻结资金")
    void passiveFillUpdatesOpenOrderAndCancelReleasesCash() {
        AccountManager accounts = new AccountManager();
        Account buyer = accounts.createAccount("buyer", "买方", new BigDecimal("1000.00"));
        Account seller = accounts.createAccount("seller", "卖方", BigDecimal.ZERO);
        seller.seedPosition("AAPL", 100, new BigDecimal("9.00"));
        MarketDataPublisher publisher = new MarketDataPublisher();
        MatchingEngine engine = new MatchingEngine(accounts, publisher,
                Map.of("AAPL", new BigDecimal("9.00")));
        ProbeAgent agent = new ProbeAgent(buyer, engine);
        publisher.subscribe(agent);

        agent.placeLimit("AAPL", Side.BUY, new BigDecimal("9.00"), 50);
        assertEquals(1, agent.openOrders());
        assertEquals(new BigDecimal("450.00"), buyer.getFrozenCash());

        engine.submit(OrderRequest.limit("seller", "AAPL", Side.SELL,
                new BigDecimal("9.00"), 20));
        assertEquals(20, agent.getFilledQuantity());
        assertEquals(1, agent.openOrders(), "部分成交后的剩余订单仍应被跟踪");

        assertEquals(1, agent.cancelEverything());
        assertEquals(0, agent.openOrders());
        assertEquals(new BigDecimal("0.00"), buyer.getFrozenCash());
        assertEquals(new BigDecimal("820.00"), buyer.getAvailableCash());
    }

    @Test
    @DisplayName("噪音 agent 每轮撤旧换新，不会无限累积冻结委托")
    void noiseAgentRefreshesQuotesInsteadOfAccumulatingThem() {
        AccountManager accounts = new AccountManager();
        Account noiseAccount = accounts.createAccount("noise", "噪音", new BigDecimal("100000.00"));
        noiseAccount.seedPosition("AAPL", 1000, new BigDecimal("100.00"));
        MarketDataPublisher publisher = new MarketDataPublisher();
        MatchingEngine engine = new MatchingEngine(accounts, publisher,
                Map.of("AAPL", new BigDecimal("100.00")));
        NoiseAgent noise = new NoiseAgent("noise", "噪音", noiseAccount, engine,
                7L, new BigDecimal("1.5"), 10, 20);
        publisher.subscribe(noise);
        noise.onMarketSnapshot(engine.marketSnapshot("AAPL", MatchingEngine.DEFAULT_DEPTH));

        for (int i = 0; i < 30; i++) {
            noise.onTick();
            long resting = engine.allRestingOrders().stream()
                    .filter(order -> order.getAgentId().equals("noise"))
                    .count();
            assertTrue(resting <= 1, "单标的只应保留最新一张报价");
        }

        assertEquals(30, noise.getSubmittedCount());
        assertEquals(0, noise.getRejectedCount());
        assertTrue(noiseAccount.getAvailableCash().signum() > 0);
        assertTrue(noiseAccount.getAvailableQuantity("AAPL") > 0);
    }

    @Test
    @DisplayName("动量向上时按卖一价主动成交")
    void momentumAgentBuysAtBestAsk() {
        AccountManager accounts = new AccountManager();
        Account aiAccount = accounts.createAccount("ai", "AI", new BigDecimal("1000.00"));
        Account seller = accounts.createAccount("seller", "卖方", BigDecimal.ZERO);
        seller.seedPosition("AAPL", 100, new BigDecimal("10.00"));
        MarketDataPublisher publisher = new MarketDataPublisher();
        MatchingEngine engine = new MatchingEngine(accounts, publisher,
                Map.of("AAPL", new BigDecimal("10.00")));
        MomentumAgent momentum = new MomentumAgent("ai", "AI", aiAccount, engine,
                2, 5, new BigDecimal("20"));
        publisher.subscribe(momentum);
        engine.submit(OrderRequest.limit("seller", "AAPL", Side.SELL,
                new BigDecimal("10.00"), 20));

        momentum.onMarketSnapshot(snapshot(new BigDecimal("9.00"), null, new BigDecimal("10.00")));
        momentum.onMarketSnapshot(snapshot(new BigDecimal("11.00"), null, new BigDecimal("10.00")));
        momentum.onTick();

        assertEquals(5, aiAccount.getQuantity("AAPL"));
        assertEquals(5, momentum.getFilledQuantity());
        assertEquals(new BigDecimal("950.00"), aiAccount.getAvailableCash());
    }

    private static MarketSnapshot snapshot(BigDecimal last, BigDecimal bid, BigDecimal ask) {
        return new MarketSnapshot("AAPL", last, new BigDecimal("10.00"), bid, ask,
                0, List.of(), List.of(), System.currentTimeMillis());
    }

    private static final class ProbeAgent extends AbstractTradingAgent {

        private ProbeAgent(Account account, OrderGateway gateway) {
            super("buyer", "买方", AgentType.AI, account, gateway);
        }

        @Override
        public void onTick() {
        }

        private void placeLimit(String symbol, Side side, BigDecimal price, long quantity) {
            submit(OrderRequest.limit(getAgentId(), symbol, side, price, quantity));
        }

        private int openOrders() {
            return openOrderCount();
        }

        private int cancelEverything() {
            return cancelAllOpenOrders();
        }
    }
}
