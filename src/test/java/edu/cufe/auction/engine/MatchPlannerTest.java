package edu.cufe.auction.engine;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.OrderStatus;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.model.Trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2.4a：共享无副作用撮合计划器 {@link MatchPlanner} 与订单簿报价视图的测试。
 *
 * <p>覆盖两类证据：</p>
 * <ul>
 *   <li><b>纯计划</b>——价格优先/时间优先、被动价成交、不交叉停止、部分成交、
 *       预算递减（含巨大预算不溢出）、自成交停止、输入为不可变副本；</li>
 *   <li><b>旧引擎委托后行为不变</b>——市价扫簿的真实资金口径、自成交前已发生的
 *       第三方成交保留、旧预算换算的溢出回归。</li>
 * </ul>
 *
 * <p>本类不改动既有 {@code MatchingEngineTest} 的断言，只新增证据。</p>
 */
@DisplayName("M2.4a：无副作用撮合计划器")
class MatchPlannerTest {

    private static final String AAPL = EngineFixture.AAPL;
    private static final BigDecimal TEN = new BigDecimal("10.00");
    private static final BigDecimal ELEVEN = new BigDecimal("11.00");

    // ------------------------------------------------------------------
    // 纯计划器
    // ------------------------------------------------------------------

    @Test
    @DisplayName("买侧计划按价格优先、同价 FIFO 取对手卖盘")
    void buyPlanWalksAsksInPriceThenFifoOrder() {
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(
                Side.BUY, OrderType.MARKET, null, 70, "buyer", new BigDecimal("100000.00"));
        List<MatchPlanner.RestingQuote> quotes = List.of(
                new MatchPlanner.RestingQuote(11, "s1", TEN, 40),
                new MatchPlanner.RestingQuote(12, "s2", TEN, 30),
                new MatchPlanner.RestingQuote(13, "s3", ELEVEN, 100));

        MatchPlanner.Plan plan = MatchPlanner.plan(incoming, quotes);

        assertEquals(2, plan.fills().size(), "70 股由两笔卖单满足");
        assertEquals(11, plan.fills().get(0).makerOrderId());
        assertEquals(40, plan.fills().get(0).quantity());
        assertEquals(12, plan.fills().get(1).makerOrderId(), "同价必须按 FIFO 接着吃第二笔");
        assertEquals(30, plan.fills().get(1).quantity());
        assertEquals(0, plan.remaining());
        assertEquals(MatchPlanner.StopReason.FILLED, plan.stopReason());
        assertNull(plan.selfMakerId());
    }

    @Test
    @DisplayName("卖侧计划按价格优先取对手买盘，成交价为被动方（买方）价")
    void sellPlanUsesPassivePrice() {
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(
                Side.SELL, OrderType.LIMIT, new BigDecimal("9.00"), 100, "seller", null);
        List<MatchPlanner.RestingQuote> quotes = List.of(
                new MatchPlanner.RestingQuote(21, "b1", TEN, 60),
                new MatchPlanner.RestingQuote(22, "b2", new BigDecimal("9.50"), 100));

        MatchPlanner.Plan plan = MatchPlanner.plan(incoming, quotes);

        assertEquals(2, plan.fills().size());
        assertEquals(TEN, plan.fills().get(0).price(), "主动卖单在买方挂单价 10.00 成交");
        assertEquals(60, plan.fills().get(0).quantity());
        assertEquals(new BigDecimal("9.50"), plan.fills().get(1).price());
        assertEquals(40, plan.fills().get(1).quantity());
        assertEquals(0, plan.remaining());
    }

    @Test
    @DisplayName("限价不交叉时不产生成交，全部数量留给挂簿")
    void limitNotCrossingProducesNoFill() {
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(
                Side.BUY, OrderType.LIMIT, new BigDecimal("9.50"), 100, "buyer", new BigDecimal("950.00"));

        MatchPlanner.Plan plan = MatchPlanner.plan(incoming,
                List.of(new MatchPlanner.RestingQuote(31, "s1", TEN, 100)));

        assertTrue(plan.fills().isEmpty());
        assertEquals(100, plan.remaining());
        assertEquals(MatchPlanner.StopReason.LIMIT_NOT_CROSSING, plan.stopReason());
    }

    @Test
    @DisplayName("对手盘为空时停止原因为 NO_OPPOSITE")
    void emptyBookStopsWithNoOpposite() {
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(
                Side.BUY, OrderType.LIMIT, TEN, 100, "buyer", new BigDecimal("1000.00"));

        MatchPlanner.Plan plan = MatchPlanner.plan(incoming, List.of());

        assertTrue(plan.fills().isEmpty());
        assertEquals(100, plan.remaining());
        assertEquals(MatchPlanner.StopReason.NO_OPPOSITE, plan.stopReason());
    }

    @Test
    @DisplayName("主动量小于对手量时只成交主动量，计划剩余为 0")
    void partialFillOnIncomingSide() {
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(
                Side.BUY, OrderType.LIMIT, TEN, 30, "buyer", new BigDecimal("300.00"));

        MatchPlanner.Plan plan = MatchPlanner.plan(incoming,
                List.of(new MatchPlanner.RestingQuote(41, "s1", TEN, 100)));

        assertEquals(1, plan.fills().size());
        assertEquals(30, plan.fills().get(0).quantity());
        assertEquals(0, plan.remaining());
    }

    @Test
    @DisplayName("1000 现金市价买 100：卖一 10×50、卖二 11×100 时成交 50+45，花 995 剩 5")
    void budgetCapStopsAtAffordableQuantity() {
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(
                Side.BUY, OrderType.MARKET, null, 100, "buyer", new BigDecimal("1000.00"));
        List<MatchPlanner.RestingQuote> quotes = List.of(
                new MatchPlanner.RestingQuote(51, "s1", TEN, 50),
                new MatchPlanner.RestingQuote(52, "s2", ELEVEN, 100));

        MatchPlanner.Plan plan = MatchPlanner.plan(incoming, quotes);

        assertEquals(2, plan.fills().size());
        assertEquals(50, plan.fills().get(0).quantity());
        assertEquals(45, plan.fills().get(1).quantity(), "剩余 500 按 11 元只能买 45 股");
        assertEquals(5, plan.remaining());
        assertEquals(MatchPlanner.StopReason.BUDGET_EXHAUSTED, plan.stopReason(),
                "第二档仍剩 55 股未成交，停因是预算而不是簿空了");

        BigDecimal spent = BigDecimal.ZERO;
        for (MatchPlanner.Fill fill : plan.fills()) {
            spent = spent.add(fill.price().multiply(BigDecimal.valueOf(fill.quantity())));
        }
        assertEquals(0, new BigDecimal("995").compareTo(spent));
    }

    @Test
    @DisplayName("预算连一股都买不起时停止原因为 BUDGET_EXHAUSTED")
    void budgetExhaustedStopsPlan() {
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(
                Side.BUY, OrderType.MARKET, null, 100, "buyer", new BigDecimal("5.00"));

        MatchPlanner.Plan plan = MatchPlanner.plan(incoming,
                List.of(new MatchPlanner.RestingQuote(53, "s1", ELEVEN, 100)));

        assertTrue(plan.fills().isEmpty());
        assertEquals(100, plan.remaining());
        assertEquals(MatchPlanner.StopReason.BUDGET_EXHAUSTED, plan.stopReason());
    }

    @Test
    @DisplayName("预算只够部分吃掉最优卖单时立即以预算停止，不得继续前进到后面的自有报价")
    void budgetStopOnPartiallyFilledMaker() {
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(
                Side.BUY, OrderType.MARKET, null, 100, "buyer", new BigDecimal("1000.00"));
        List<MatchPlanner.RestingQuote> quotes = List.of(
                new MatchPlanner.RestingQuote(54, "seller-1", TEN, 50),
                new MatchPlanner.RestingQuote(55, "seller-2", ELEVEN, 100),
                new MatchPlanner.RestingQuote(56, "buyer", new BigDecimal("12.00"), 10));

        MatchPlanner.Plan plan = MatchPlanner.plan(incoming, quotes);

        assertEquals(2, plan.fills().size());
        assertEquals(50, plan.fills().get(0).quantity());
        assertEquals(45, plan.fills().get(1).quantity());
        assertEquals(5, plan.remaining());
        assertEquals(MatchPlanner.StopReason.BUDGET_EXHAUSTED, plan.stopReason(),
                "第二档还挂着 55 股，停因必须是预算，不能报成自成交");
        assertNull(plan.selfMakerId(), "预算停止时不应给出自成交挂单");
    }

    @Test
    @DisplayName("十亿级巨大预算不会转 long 溢出为负，成交量仍按剩余量封顶")
    void hugeBudgetDoesNotOverflowToNegative() {
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(
                Side.BUY, OrderType.MARKET, null, 1000, "buyer", new BigDecimal("100000000000000000.00"));

        MatchPlanner.Plan plan = MatchPlanner.plan(incoming,
                List.of(new MatchPlanner.RestingQuote(61, "s1", new BigDecimal("0.01"), 1000)));

        assertEquals(1, plan.fills().size());
        assertEquals(1000, plan.fills().get(0).quantity());
        assertEquals(0, plan.remaining());
        assertEquals(MatchPlanner.StopReason.FILLED, plan.stopReason());
    }

    @Test
    @DisplayName("预算与数量都达到 long 上限时按剩余量封顶，不做超大整数转换")
    void hugeBudgetAndHugeQuantityStayBounded() {
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(
                Side.BUY, OrderType.MARKET, null, Long.MAX_VALUE, "buyer", new BigDecimal("1E+20"));

        MatchPlanner.Plan plan = MatchPlanner.plan(incoming,
                List.of(new MatchPlanner.RestingQuote(71, "s1", new BigDecimal("0.01"), Long.MAX_VALUE)));

        assertEquals(1, plan.fills().size());
        assertEquals(Long.MAX_VALUE, plan.fills().get(0).quantity());
        assertEquals(0, plan.remaining());
    }

    @Test
    @DisplayName("遇到同主体更优挂单时停止计划并给出该挂单 id，不产生自成交")
    void selfQuoteStopsPlanWithMakerId() {
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(
                Side.BUY, OrderType.MARKET, null, 100, "owner-a", new BigDecimal("100000.00"));
        List<MatchPlanner.RestingQuote> quotes = List.of(
                new MatchPlanner.RestingQuote(81, "owner-b", TEN, 40),
                new MatchPlanner.RestingQuote(82, "owner-a", TEN, 50));

        MatchPlanner.Plan plan = MatchPlanner.plan(incoming, quotes);

        assertEquals(1, plan.fills().size(), "只允许与第三方的 40 股成交");
        assertEquals(81, plan.fills().get(0).makerOrderId());
        assertEquals(60, plan.remaining());
        assertEquals(MatchPlanner.StopReason.SELF_TRADE, plan.stopReason());
        assertEquals(82L, plan.selfMakerId());
    }

    @Test
    @DisplayName("计划结果不可变，重复读取不会改变")
    void planIsImmutable() {
        MatchPlanner.Plan plan = MatchPlanner.plan(
                new MatchPlanner.Incoming(Side.BUY, OrderType.MARKET, null, 10, "buyer", new BigDecimal("100.00")),
                List.of(new MatchPlanner.RestingQuote(91, "s1", TEN, 10)));

        assertThrows(UnsupportedOperationException.class,
                () -> plan.fills().add(new MatchPlanner.Fill(92, TEN, 1)));
        assertEquals(1, plan.fills().size());
    }

    // ------------------------------------------------------------------
    // 订单簿报价视图
    // ------------------------------------------------------------------

    @Test
    @DisplayName("报价视图按价格优先、同价 FIFO，并且是不可变副本")
    void quoteViewIsOrderedAndImmutable() {
        OrderBook book = new OrderBook(AAPL);
        book.post(new Order(101, "o1", AAPL, Side.BUY, OrderType.LIMIT, TEN, 50, 1));
        book.post(new Order(102, "o2", AAPL, Side.BUY, OrderType.LIMIT, new BigDecimal("10.50"), 30, 2));
        book.post(new Order(103, "o3", AAPL, Side.BUY, OrderType.LIMIT, TEN, 20, 3));

        List<MatchPlanner.RestingQuote> quotes = book.oppositeQuotes(Side.SELL);

        assertEquals(3, quotes.size());
        assertEquals(102, quotes.get(0).orderId(), "高价买单先出");
        assertEquals(101, quotes.get(1).orderId(), "同价按 FIFO");
        assertEquals(103, quotes.get(2).orderId());
        assertThrows(UnsupportedOperationException.class,
                () -> quotes.add(new MatchPlanner.RestingQuote(104, "o4", TEN, 1)));
    }

    @Test
    @DisplayName("报价视图是值副本：原 Order 后续成交不回写已取出的报价")
    void quoteViewDoesNotFollowMutableOrder() {
        OrderBook book = new OrderBook(AAPL);
        Order maker = new Order(111, "o1", AAPL, Side.BUY, OrderType.LIMIT, TEN, 50, 1);
        book.post(maker);
        List<MatchPlanner.RestingQuote> quotes = book.oppositeQuotes(Side.SELL);

        maker.addFill(20);

        assertEquals(50, quotes.get(0).remaining(), "视图取的是取值时的快照");
        assertEquals(30, maker.getRemainingQuantity());
        MatchPlanner.Plan plan = MatchPlanner.plan(new MatchPlanner.Incoming(
                Side.SELL, OrderType.LIMIT, TEN, 50, "seller", null), quotes);
        assertEquals(50, plan.fills().get(0).quantity(), "计划只依赖传入的不可变报价");
    }

    // ------------------------------------------------------------------
    // 旧引擎委托后的行为
    // ------------------------------------------------------------------

    @Test
    @DisplayName("引擎：1000 现金市价买 100，实际成交 50@10 与 45@11，可用现金剩 5")
    void engineAppliesPlanBudgetAcrossPriceLevels() {
        EngineFixture fixture = new EngineFixture();
        Account buyer = fixture.account("buyer", "1000.00");
        Account seller1 = fixture.account("seller-1", "100000.00");
        Account seller2 = fixture.account("seller-2", "100000.00");
        BigDecimal cost = new BigDecimal("9.00");
        seller1.seedPosition(AAPL, 50, cost);
        seller2.seedPosition(AAPL, 100, cost);
        assertTrue(fixture.sellLimit("seller-1", AAPL, "10.00", 50).isAccepted());
        assertTrue(fixture.sellLimit("seller-2", AAPL, "11.00", 100).isAccepted());

        OrderResult result = fixture.buyMarket("buyer", AAPL, 100);

        assertTrue(result.isAccepted());
        List<Trade> trades = result.getTrades();
        assertEquals(2, trades.size());
        assertEquals(new BigDecimal("10.00"), trades.get(0).getPrice());
        assertEquals(50, trades.get(0).getQuantity());
        assertEquals(new BigDecimal("11.00"), trades.get(1).getPrice());
        assertEquals(45, trades.get(1).getQuantity(), "冻结 1000 在第二档只够 45 股");
        assertEquals(OrderStatus.CANCELLED, result.getStatus(), "市价单剩余 5 股由原逻辑撤销");
        assertEquals(5, result.getRemainingQuantity());
        assertEquals(0, new BigDecimal("995").compareTo(
                trades.get(0).getNotional().add(trades.get(1).getNotional())));
        assertEquals(0, new BigDecimal("5.00").compareTo(buyer.getAvailableCash()));
        assertEquals(0, buyer.getFrozenCash().signum());
        assertEquals(95, buyer.getQuantity(AAPL));
        List<Order> resting = fixture.engine.allRestingOrders();
        assertEquals(1, resting.size(), "买方市价单不入簿，簿中只剩第二档未成交卖单");
        assertEquals(55, resting.get(0).getRemainingQuantity());
    }

    @Test
    @DisplayName("引擎：自成交前已完成的第三方成交保留，同价自有挂单不跳队也不改单")
    void engineKeepsThirdPartyFillBeforeSelfTrade() {
        EngineFixture fixture = new EngineFixture();
        Account taker = fixture.account("acc-a", "100000.00");
        Account other = fixture.account("acc-b", "100000.00");
        BigDecimal cost = new BigDecimal("9.00");
        taker.seedPosition(AAPL, 500, cost);
        other.seedPosition(AAPL, 500, cost);
        OrderResult thirdParty = fixture.sellLimit("acc-b", AAPL, "10.00", 40);
        OrderResult own = fixture.sellLimit("acc-a", AAPL, "10.00", 50);
        assertTrue(thirdParty.isAccepted());
        assertTrue(own.isAccepted());

        OrderResult buy = fixture.buyLimit("acc-a", AAPL, "10.00", 100);

        assertTrue(buy.isAccepted(), "自成交防护属于受理后的业务取消");
        assertEquals(1, buy.getTrades().size(), "只保留自成交之前的第三方成交");
        assertEquals(40, buy.getTrades().get(0).getQuantity());
        assertEquals(new BigDecimal("10.00"), buy.getTrades().get(0).getPrice());
        assertTrue(buy.getReason().startsWith("SELF_TRADE_PREVENTED"), "实际：" + buy.getReason());
        assertEquals(OrderStatus.CANCELLED, buy.getStatus());
        assertEquals(60, buy.getRemainingQuantity());
        assertEquals(50, own.getOrder().getRemainingQuantity(), "自有挂单不动");
        assertEquals(OrderStatus.NEW, own.getOrder().getStatus());
        assertEquals(540, taker.getQuantity(AAPL), "自有卖单 50 股仍冻结，另买入 40 股");
        assertEquals(490, taker.getAvailableQuantity(AAPL), "540 股中 50 股被自有卖单冻结");
        assertEquals(0, new BigDecimal("99600.00").compareTo(taker.getAvailableCash()),
                "只花掉 40 股 × 10.00，剩余冻结全部释放");
        assertEquals(0, taker.getFrozenCash().signum());
    }

    @Test
    @DisplayName("引擎：自有人在更差价位时也停止，不跳过自有挂单去吃别处")
    void engineStopsAtSelfQuoteEvenWhenThirdPartyIsCheaper() {
        EngineFixture fixture = new EngineFixture();
        Account taker = fixture.account("acc-a", "100000.00");
        Account other = fixture.account("acc-b", "100000.00");
        BigDecimal cost = new BigDecimal("9.00");
        taker.seedPosition(AAPL, 500, cost);
        other.seedPosition(AAPL, 500, cost);
        OrderResult cheaper = fixture.sellLimit("acc-b", AAPL, "10.00", 10);
        OrderResult own = fixture.sellLimit("acc-a", AAPL, "10.50", 50);
        assertTrue(cheaper.isAccepted());
        assertTrue(own.isAccepted());

        OrderResult buy = fixture.buyLimit("acc-a", AAPL, "10.50", 100);

        assertTrue(buy.getReason().startsWith("SELF_TRADE_PREVENTED"), "实际：" + buy.getReason());
        assertEquals(1, buy.getTrades().size());
        assertEquals(10, buy.getTrades().get(0).getQuantity());
        assertEquals(90, buy.getRemainingQuantity());
        assertEquals(50, own.getOrder().getRemainingQuantity());
        assertEquals(0, new BigDecimal("99900.00").compareTo(taker.getAvailableCash()));
        assertTrue(fixture.engine.allRestingOrders().contains(own.getOrder()));
    }

    @Test
    @DisplayName("引擎：极大可用资金下的市价买不再因旧 longValue 溢出而不成交")
    void engineHandlesHugeBudgetWithoutOverflow() {
        EngineFixture fixture = new EngineFixture();
        Account buyer = fixture.account("big-buyer", "100000000000000000.00");
        Account seller = fixture.account("penny-seller", "1000.00");
        seller.seedPosition(AAPL, 1000, new BigDecimal("0.01"));
        assertTrue(fixture.sellLimit("penny-seller", AAPL, "0.01", 1000).isAccepted());

        OrderResult buy = fixture.buyMarket("big-buyer", AAPL, 1000);

        assertTrue(buy.isAccepted());
        assertEquals(1, buy.getTrades().size());
        assertEquals(1000, buy.getFilledQuantity(),
                "旧实现把 1e19 股的可承受量截断成 long 后为负，导致一股都不成交");
        assertEquals(0, buy.getRemainingQuantity());
        assertEquals(1000, buyer.getQuantity(AAPL));
    }

    @Test
    @DisplayName("引擎：非整分报价下按账户逐笔分位舍入演化预算，与旧引擎成交笔数一致")
    void engineMatchesLegacyBudgetWhenPricesAreSubCent() {
        EngineFixture fixture = new EngineFixture();
        Account buyer = fixture.account("fraction-buyer", "0.03");
        Account seller1 = fixture.account("fraction-s1", "1.00");
        Account seller2 = fixture.account("fraction-s2", "1.00");
        BigDecimal cost = new BigDecimal("0.014");
        seller1.seedPosition(AAPL, 1, cost);
        seller2.seedPosition(AAPL, 1, cost);
        assertTrue(fixture.sellLimit("fraction-s1", AAPL, "0.014", 1).isAccepted());
        assertTrue(fixture.sellLimit("fraction-s2", AAPL, "0.014", 1).isAccepted());

        OrderResult buy = fixture.buyMarket("fraction-buyer", AAPL, 2);

        assertTrue(buy.isAccepted());
        assertEquals(1, buy.getTrades().size(),
                "旧引擎在这里只成交 1 股：冻结 0.03 结算后账户预算只剩 0.01，买不起第二股");
        assertEquals(1, buy.getFilledQuantity());
        assertEquals(OrderStatus.CANCELLED, buy.getStatus());
        assertEquals(0, new BigDecimal("0.01").compareTo(buyer.getAvailableCash()));
        assertEquals(0, new BigDecimal("0.01").compareTo(buyer.getFrozenCash()),
                "非整分冻结的既有分位尾差保持原样，不在本步修");
    }

    @Test
    @DisplayName("引擎：现金 1200 请求市价 200（卖一 10 需冻结 2000）由原受理层拒绝，不截断成交")
    void engineRejectsUnaffordableMarketBuyAtAcceptance() {
        EngineFixture fixture = new EngineFixture();
        Account buyer = fixture.account("small-buyer", "1200.00");
        Account seller = fixture.account("seller", "100000.00");
        seller.seedPosition(AAPL, 500, new BigDecimal("9.00"));
        assertTrue(fixture.sellLimit("seller", AAPL, "10.00", 500).isAccepted());

        OrderResult buy = fixture.buyMarket("small-buyer", AAPL, 200);

        assertTrue(!buy.isAccepted(), "冻结 2000 高于可用 1200，必须在受理层拒绝");
        assertTrue(buy.getReason().startsWith("可用资金不足"), "实际：" + buy.getReason());
        assertTrue(buy.getTrades().isEmpty());
        assertEquals(0, new BigDecimal("1200.00").compareTo(buyer.getAvailableCash()));
        assertEquals(0, buyer.getFrozenCash().signum());
    }

    @Test
    @DisplayName("引擎：卖出方向沿用被动买单价，主动卖单不再查预算")
    void engineSellUsesPassiveBidPrice() {
        EngineFixture fixture = new EngineFixture();
        Account seller = fixture.account("acc-s", "1000.00");
        Account buyer = fixture.account("acc-b", "100000.00");
        seller.seedPosition(AAPL, 100, new BigDecimal("9.00"));
        assertTrue(fixture.buyLimit("acc-b", AAPL, "10.00", 60).isAccepted());

        OrderResult sell = fixture.sellLimit("acc-s", AAPL, "9.00", 100);

        assertTrue(sell.isAccepted());
        assertEquals(1, sell.getTrades().size());
        assertEquals(new BigDecimal("10.00"), sell.getTrades().get(0).getPrice());
        assertEquals(60, sell.getTrades().get(0).getQuantity());
        assertEquals(40, sell.getRemainingQuantity(), "限价剩余挂簿");
        assertNull(sell.getReason());
        assertEquals(1, fixture.engine.allRestingOrders().size());
    }
}
