package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.account.AccountManager;
import edu.cufe.auction.engine.MatchingEngine;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.market.MarketDataPublisher;
import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.model.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractTradingAgentTradeStatsTest {

    private static final String AGENT_ID = "agent-a";

    @Test
    void countsPassiveFillFromMarketBroadcast() {
        TestAgent agent = new TestAgent(noOpGateway());
        MarketDataPublisher publisher = new MarketDataPublisher();
        publisher.subscribe(agent);

        publisher.publishTrade(trade(1L, "other-buyer", AGENT_ID, 20L));
        publisher.publishTrade(trade(2L, "other-buyer", "other-seller", 30L));

        assertEquals(20L, agent.getFilledQuantity());
    }

    @Test
    void countsTradeFromBroadcastAfterSubmit() {
        Trade trade = trade(3L, AGENT_ID, "other-seller", 12L);
        Order order = new Order(10L, AGENT_ID, "AAPL", Side.BUY, OrderType.LIMIT,
                new BigDecimal("100.00"), 12L, 1L);
        order.addFill(12L);
        TestAgent agent = new TestAgent(new OrderGateway() {
            @Override
            public OrderResult submit(OrderRequest request) {
                return OrderResult.accepted(order, List.of(trade));
            }

            @Override
            public boolean cancel(long orderId) {
                return false;
            }
        });

        agent.place(OrderRequest.limit(AGENT_ID, "AAPL", Side.BUY,
                new BigDecimal("100.00"), 12L));
        agent.onTrade(trade);

        assertEquals(12L, agent.getFilledQuantity());
    }

    @Test
    void countsBothSidesOfSelfTradeThroughMatchingEngineBroadcast() {
        AccountManager accounts = new AccountManager();
        Account account = accounts.createAccount(AGENT_ID, "Agent A", new BigDecimal("10000.00"));
        account.seedPosition("AAPL", 20L, new BigDecimal("100.00"));
        MarketDataPublisher publisher = new MarketDataPublisher();
        MatchingEngine engine = new MatchingEngine(accounts, publisher,
                java.util.Map.of("AAPL", new BigDecimal("100.00")));
        TestAgent agent = new TestAgent(account, engine);
        publisher.subscribe(agent);

        agent.place(OrderRequest.limit(AGENT_ID, "AAPL", Side.SELL,
                new BigDecimal("100.00"), 20L));
        agent.place(OrderRequest.limit(AGENT_ID, "AAPL", Side.BUY,
                new BigDecimal("100.00"), 20L));

        assertEquals(40L, agent.getFilledQuantity(),
                "自成交应分别计入买方和卖方成交量，且广播与 submit 返回不能重复计数");
    }

    private static Trade trade(long tradeId, String buyer, String seller, long quantity) {
        return new Trade(tradeId, "AAPL", new BigDecimal("100.00"), quantity,
                10L, buyer, 11L, seller, 1_000L);
    }

    private static OrderGateway noOpGateway() {
        return new OrderGateway() {
            @Override
            public OrderResult submit(OrderRequest request) {
                return OrderResult.rejected("unused");
            }

            @Override
            public boolean cancel(long orderId) {
                return false;
            }
        };
    }

    private static final class TestAgent extends AbstractTradingAgent {
        private TestAgent(OrderGateway gateway) {
            this(new Account(AGENT_ID, "Agent A", new BigDecimal("1000.00")), gateway);
        }

        private TestAgent(Account account, OrderGateway gateway) {
            super(AGENT_ID, "Agent A", AgentType.AI, account, gateway);
        }

        private OrderResult place(OrderRequest request) {
            return submit(request);
        }

        @Override
        public void onTick() {
        }
    }
}
