package edu.cufe.auction.engine;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.OrderStatus;
import edu.cufe.auction.model.Trade;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自成交防护（V06）测试。
 *
 * <p>语义：新单仍按价格-时间优先撮合；一旦对手方最优挂单属于同一账户，
 * 立即取消新单的全部剩余量并释放其剩余冻结。此前与第三方的真实成交保留，
 * 不回滚、不跳过自有更优价去吃更差的价，也不改动原挂单。</p>
 */
@DisplayName("自成交防护：遇自有可交叉挂单时取消主动单剩余量")
class SelfTradePreventionTest {

    private static final String AAPL = EngineFixture.AAPL;
    private static final BigDecimal COST = new BigDecimal("10.00");

    private EngineFixture fixture;
    private Account accountA;
    private Account accountB;
    private Account accountC;

    @BeforeEach
    void setUp() {
        fixture = new EngineFixture();
        accountA = fixture.account("acc-a", "100000.00");
        accountB = fixture.account("acc-b", "100000.00");
        accountC = fixture.account("acc-c", "100000.00");
        accountA.seedPosition(AAPL, 1000, COST);
        accountB.seedPosition(AAPL, 1000, COST);
        accountC.seedPosition(AAPL, 1000, COST);
    }

    @Test
    @DisplayName("买侧：自有卖单是唯一对手时，新买单不成交、被取消并释放全部冻结资金")
    void selfCrossOnBuySideCancelsRemainderAndReleasesCash() {
        OrderResult restingSell = fixture.sellLimit("acc-a", AAPL, "10.00", 100);
        assertTrue(restingSell.isAccepted());
        assertEquals(OrderStatus.NEW, restingSell.getStatus());

        OrderResult buy = fixture.buyLimit("acc-a", AAPL, "10.00", 100);

        assertTrue(buy.isAccepted(), "受理后因自成交取消仍属于已受理结果");
        assertTrue(buy.getReason().startsWith("SELF_TRADE_PREVENTED"),
                "业务取消原因应以 SELF_TRADE_PREVENTED 开头，实际：" + buy.getReason());
        assertEquals(OrderStatus.CANCELLED, buy.getStatus());
        assertEquals(0L, buy.getFilledQuantity());
        assertEquals(100L, buy.getRemainingQuantity(), "未成交的 100 股即被取消的剩余量");
        assertTrue(buy.getTrades().isEmpty());
        assertEquals(0, fixture.observedTrades.size(), "不得产生自成交量");

        // 原挂单保持不变，且仍在簿中
        assertEquals(100L, restingSell.getOrder().getRemainingQuantity());
        assertEquals(OrderStatus.NEW, restingSell.getOrder().getStatus());
        assertEquals(1, fixture.engine.allRestingOrders().size());

        // 现金与冻结守恒：既没有买入也没有卖出
        assertEquals(0, accountA.getFrozenCash().signum(), "新单冻结必须全部释放");
        assertEquals(0, new BigDecimal("100000.00").compareTo(accountA.getTotalCash()));
        assertEquals(0, new BigDecimal("100000.00").compareTo(accountA.getAvailableCash()));
        assertEquals(1000L, accountA.getQuantity(AAPL));
        // 原卖单仍挂在簿中，它冻结的 100 股必须保留；新买单没有冻结任何股票
        assertEquals(900L, accountA.getAvailableQuantity(AAPL));
    }

    @Test
    @DisplayName("卖侧：自有买单是唯一对手时，新卖单被取消且冻结股数释放、原买单冻结不动")
    void selfCrossOnSellSideReleasesFrozenShares() {
        OrderResult restingBuy = fixture.buyLimit("acc-a", AAPL, "9.00", 50);
        assertEquals(OrderStatus.NEW, restingBuy.getStatus());
        assertEquals(0, new BigDecimal("450.00").compareTo(accountA.getFrozenCash()));

        OrderResult sell = fixture.sellLimit("acc-a", AAPL, "9.00", 50);

        assertTrue(sell.isAccepted());
        assertTrue(sell.getReason().startsWith("SELF_TRADE_PREVENTED"),
                "实际原因：" + sell.getReason());
        assertEquals(OrderStatus.CANCELLED, sell.getStatus());
        assertTrue(sell.getTrades().isEmpty());
        assertEquals(0, fixture.observedTrades.size());

        // 股数守恒、卖单冻结释放；原买单的 450 元冻结必须原样保留
        assertEquals(1000L, accountA.getQuantity(AAPL));
        assertEquals(1000L, accountA.getAvailableQuantity(AAPL));
        assertEquals(0, new BigDecimal("450.00").compareTo(accountA.getFrozenCash()));
        assertEquals(0, new BigDecimal("99550.00").compareTo(accountA.getAvailableCash()));
        assertEquals(OrderStatus.NEW, restingBuy.getOrder().getStatus());
        assertEquals(1, fixture.engine.allRestingOrders().size());
    }

    @Test
    @DisplayName("同一账户的非交叉买卖单可以同时挂簿，不触发自成交取消")
    void nonCrossingOwnOrdersBothRest() {
        OrderResult sell = fixture.sellLimit("acc-a", AAPL, "11.00", 100);
        OrderResult buy = fixture.buyLimit("acc-a", AAPL, "10.00", 100);

        assertEquals(OrderStatus.NEW, sell.getStatus());
        assertEquals(OrderStatus.NEW, buy.getStatus());
        assertNull(buy.getReason(), "非交叉受理不应带业务取消原因");
        assertEquals(2, fixture.engine.allRestingOrders().size());
        assertEquals(0, fixture.observedTrades.size());
    }

    @Test
    @DisplayName("先与第三方成交的部分保留，随后遇自有挂单即停，不跳过自有更优价去吃更差价")
    void thirdPartyFillKeptThenOwnRestingOrderStopsMatching() {
        OrderResult thirdPartySell = fixture.sellLimit("acc-b", AAPL, "10.00", 50);
        OrderResult ownSell = fixture.sellLimit("acc-a", AAPL, "10.10", 100);
        OrderResult worseSell = fixture.sellLimit("acc-c", AAPL, "10.20", 100);

        OrderResult buy = fixture.buyLimit("acc-a", AAPL, "10.20", 200);

        // 与第三方的 50 股成交保留
        assertTrue(buy.isAccepted());
        assertEquals(1, buy.getTrades().size(), "只应保留与第三方的成交");
        Trade trade = buy.getTrades().get(0);
        assertEquals(0, new BigDecimal("10.00").compareTo(trade.getPrice()));
        assertEquals(50L, trade.getQuantity());
        assertEquals("acc-b", trade.getSellAgentId());
        assertEquals(buy.getOrder().getOrderId(), trade.getBuyOrderId());

        // 自有更优价处停止，剩余量取消
        assertTrue(buy.getReason().startsWith("SELF_TRADE_PREVENTED"),
                "实际原因：" + buy.getReason());
        assertEquals(50L, buy.getFilledQuantity());
        assertEquals(150L, buy.getRemainingQuantity(), "与自有挂单相遇后取消的剩余量是 150 股");
        assertEquals(OrderStatus.CANCELLED, buy.getStatus());
        assertEquals(1, fixture.observedTrades.size(), "成交事件不得被回滚或重复广播");

        // 自己的挂单与更差的第三方挂单都还在，未被吃掉
        assertEquals(100L, ownSell.getOrder().getRemainingQuantity());
        assertEquals(OrderStatus.NEW, ownSell.getOrder().getStatus());
        assertEquals(100L, worseSell.getOrder().getRemainingQuantity());
        assertEquals(OrderStatus.NEW, worseSell.getOrder().getStatus());
        assertEquals(2, fixture.engine.allRestingOrders().size());

        // 现金守恒：只付出 50 × 10.00 = 500，冻结归零
        assertEquals(0, new BigDecimal("99500.00").compareTo(accountA.getTotalCash()));
        assertEquals(0, accountA.getFrozenCash().signum());
        assertEquals(1050L, accountA.getQuantity(AAPL));

        // 累计成交量只计真实成交
        assertEquals(50L, fixture.engine.marketSnapshot(AAPL, 1).getCumulativeVolume());
    }

    @Test
    @DisplayName("市价单遇自有卖一即停：不成交、剩余取消、不重复置终态")
    void marketOrderStopsAtOwnAsk() {
        OrderResult ownSell = fixture.sellLimit("acc-a", AAPL, "10.00", 50);
        OrderResult otherSell = fixture.sellLimit("acc-b", AAPL, "10.05", 100);

        OrderResult buy = fixture.buyMarket("acc-a", AAPL, 80);

        assertTrue(buy.isAccepted());
        assertTrue(buy.getReason().startsWith("SELF_TRADE_PREVENTED"),
                "实际原因：" + buy.getReason());
        assertEquals(OrderStatus.CANCELLED, buy.getStatus());
        assertEquals(0L, buy.getFilledQuantity());
        assertTrue(buy.getTrades().isEmpty());
        assertEquals(0, fixture.observedTrades.size());

        assertEquals(50L, ownSell.getOrder().getRemainingQuantity());
        assertEquals(100L, otherSell.getOrder().getRemainingQuantity());
        assertEquals(0, accountA.getFrozenCash().signum(), "市价单冻结必须释放");
        assertEquals(0, new BigDecimal("100000.00").compareTo(accountA.getTotalCash()));
    }

    @Test
    @DisplayName("不同账户之间同价对撞仍正常成交（防止过度拦截）")
    void differentAccountsStillTrade() {
        fixture.sellLimit("acc-b", AAPL, "10.00", 100);
        OrderResult buy = fixture.buyLimit("acc-a", AAPL, "10.00", 100);

        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertNull(buy.getReason(), "正常全部成交不应带业务取消原因");
        assertEquals(1, buy.getTrades().size());
        assertEquals("acc-a", buy.getTrades().get(0).getBuyAgentId());
        assertEquals("acc-b", buy.getTrades().get(0).getSellAgentId());
        assertEquals(0, new BigDecimal("10.00").compareTo(buy.getTrades().get(0).getPrice()));
    }
}
