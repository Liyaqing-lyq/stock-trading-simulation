package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.Side;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三类新增 agent 的决策口径测试（不依赖撮合引擎与线程，直接检查下单请求）。
 *
 * <p>锁定三条容易被改坏的口径：价值型不追高、逆势型与动量反向、做市商双边报价
 * 不穿越对手价且随库存下移。</p>
 */
class StrategyAgentsTest {

    private static final String SYMBOL = "AAPL";

    @Test
    @DisplayName("价值型：跌破估值锚 2% 时按卖一价买入")
    void valueAgentBuysBelowFairValue() {
        RecordingGateway gateway = new RecordingGateway();
        ValueAgent agent = new ValueAgent("value-1", "价值型-1", account(200000L), gateway,
                new BigDecimal("2.0"), 200L);

        agent.onMarketSnapshot(snapshot("97.80", "97.78", "97.82"));
        agent.onTick();

        OrderRequest request = gateway.single();
        assertEquals(Side.BUY, request.getSide());
        assertEquals(new BigDecimal("97.82"), request.getPrice());
        assertEquals(200L, request.getQuantity());
    }

    @Test
    @DisplayName("价值型：高于估值锚 2% 时按买一价卖出")
    void valueAgentSellsAboveFairValue() {
        RecordingGateway gateway = new RecordingGateway();
        Account account = account(200000L);
        account.seedPosition(SYMBOL, 500L, new BigDecimal("100.00"));
        ValueAgent agent = new ValueAgent("value-1", "价值型-1", account, gateway,
                new BigDecimal("2.0"), 200L);

        agent.onMarketSnapshot(snapshot("102.50", "102.48", "102.52"));
        agent.onTick();

        OrderRequest request = gateway.single();
        assertEquals(Side.SELL, request.getSide());
        assertEquals(new BigDecimal("102.48"), request.getPrice());
        assertEquals(200L, request.getQuantity());
    }

    @Test
    @DisplayName("价值型：估值锚附近不交易")
    void valueAgentStaysInsideFairValueBand() {
        RecordingGateway gateway = new RecordingGateway();
        ValueAgent agent = new ValueAgent("value-1", "价值型-1", account(200000L), gateway,
                new BigDecimal("2.0"), 200L);

        agent.onMarketSnapshot(snapshot("100.50", "100.48", "100.52"));
        agent.onTick();

        assertTrue(gateway.requests.isEmpty(), "偏离不足阈值时不应下单，实际 " + gateway.requests);
    }

    @Test
    @DisplayName("逆势型：价格跌破均线阈值时买入")
    void contrarianBuysAfterDrop() {
        RecordingGateway gateway = new RecordingGateway();
        ContrarianAgent agent = new ContrarianAgent("contrarian-1", "逆势型-1", account(200000L), gateway,
                5, new BigDecimal("1.5"), 100L);

        for (String price : List.of("100.00", "100.00", "100.00", "100.00", "97.00")) {
            agent.onMarketSnapshot(snapshot(price, "96.98", "97.02"));
            agent.onTick();
        }

        OrderRequest request = gateway.single();
        assertEquals(Side.BUY, request.getSide());
        assertEquals(new BigDecimal("97.02"), request.getPrice());
        assertEquals(100L, request.getQuantity());
    }

    @Test
    @DisplayName("逆势型：价格高于均线阈值时卖出")
    void contrarianSellsAfterRally() {
        RecordingGateway gateway = new RecordingGateway();
        Account account = account(200000L);
        account.seedPosition(SYMBOL, 500L, new BigDecimal("100.00"));
        ContrarianAgent agent = new ContrarianAgent("contrarian-1", "逆势型-1", account, gateway,
                5, new BigDecimal("1.5"), 100L);

        for (String price : List.of("100.00", "100.00", "100.00", "100.00", "103.00")) {
            agent.onMarketSnapshot(snapshot(price, "102.98", "103.02"));
            agent.onTick();
        }

        OrderRequest request = gateway.single();
        assertEquals(Side.SELL, request.getSide());
        assertEquals(new BigDecimal("102.98"), request.getPrice());
    }

    @Test
    @DisplayName("做市商：双边报价不穿越对手价，持仓偏高时整体下移报价")
    void marketMakerQuotesBothSidesWithInventorySkew() {
        RecordingGateway gateway = new RecordingGateway();
        Account account = account(300000L);
        account.seedPosition(SYMBOL, 500L, new BigDecimal("100.00"));
        MarketMakerAgent agent = new MarketMakerAgent("maker-1", "做市商-1", account, gateway,
                new BigDecimal("0.4"), new BigDecimal("0.3"), 200L, Map.of(SYMBOL, 400L));

        agent.onMarketSnapshot(snapshot("100.00", "99.98", "100.02"));
        agent.onTick();

        assertEquals(2, gateway.requests.size(), "做市商应同时挂出买卖两侧报价");
        OrderRequest buy = gateway.requests.stream()
                .filter(request -> request.getSide() == Side.BUY).findFirst().orElseThrow();
        OrderRequest sell = gateway.requests.stream()
                .filter(request -> request.getSide() == Side.SELL).findFirst().orElseThrow();

        assertTrue(buy.getPrice().compareTo(new BigDecimal("100.00")) < 0, "买单必须在中间价下方");
        assertTrue(sell.getPrice().compareTo(new BigDecimal("100.00")) > 0, "卖单必须在中间价上方");
        assertTrue(buy.getPrice().compareTo(sell.getPrice()) < 0, "买价必须低于卖价");

        // 持仓 500 > 目标 400 → 偏斜为正，两侧报价都应低于无偏斜时的 99.60 / 100.40
        assertTrue(buy.getPrice().compareTo(new BigDecimal("99.60")) < 0,
                "库存偏高时买单应下移，实际 " + buy.getPrice());
        assertTrue(sell.getPrice().compareTo(new BigDecimal("100.40")) < 0,
                "库存偏高时卖单应下移，实际 " + sell.getPrice());
    }

    private static Account account(long balance) {
        return new Account("agent", "Agent", new BigDecimal(balance).setScale(2));
    }

    private static MarketSnapshot snapshot(String last, String bid, String ask) {
        return new MarketSnapshot(SYMBOL, new BigDecimal(last), new BigDecimal("100.00"),
                new BigDecimal(bid), new BigDecimal(ask), 0L, List.of(), List.of(), 0L);
    }

    /** 记录下单请求的网关桩：只验证“发了什么单”，不进入撮合。 */
    private static final class RecordingGateway implements OrderGateway {

        private final List<OrderRequest> requests = new ArrayList<>();
        private long nextOrderId = 1L;

        @Override
        public OrderResult submit(OrderRequest request) {
            requests.add(request);
            Order order = new Order(nextOrderId++, request.getAgentId(), request.getSymbol(),
                    request.getSide(), request.getType(), request.getPrice(), request.getQuantity(), 0L);
            return OrderResult.accepted(order, List.of());
        }

        @Override
        public boolean cancel(long orderId) {
            return true;
        }

        private OrderRequest single() {
            assertEquals(1, requests.size(), "应只提交一笔订单，实际 " + requests);
            return requests.get(0);
        }
    }
}
