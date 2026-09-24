package edu.cufe.auction.engine;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderBookSnapshot;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.OrderStatus;
import edu.cufe.auction.model.PriceLevel;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.model.Trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 撮合引擎核心行为测试：价格-时间优先、部分成交、市价单、撤单、资金校验、并发安全。 */
class MatchingEngineTest {

    private static final BigDecimal AAPL_REF = new BigDecimal("175.50");

    @Test
    @DisplayName("限价单按被动方价格成交，买方获得价格改善")
    void matchesAtRestingPrice() {
        EngineFixture f = new EngineFixture();
        Account buyer = f.account("buyer", "100000");
        Account seller = f.account("seller", "0");
        seller.seedPosition("AAPL", 100, new BigDecimal("170.00"));

        OrderResult ask = f.sellLimit("seller", "AAPL", "150.00", 100);
        assertEquals(OrderStatus.NEW, ask.getStatus(), "卖单应先挂在簿上");

        OrderResult bid = f.buyLimit("buyer", "AAPL", "152.00", 100);
        assertEquals(OrderStatus.FILLED, bid.getStatus());
        assertEquals(1, bid.getTrades().size());

        Trade trade = bid.getTrades().get(0);
        assertEquals(0, new BigDecimal("150.00").compareTo(trade.getPrice()),
                "成交价必须是挂在簿上的卖价，而不是买方的 152");
        assertEquals(100L, trade.getQuantity());
        assertEquals("buyer", trade.getBuyAgentId());
        assertEquals("seller", trade.getSellAgentId());

        assertEquals(0, new BigDecimal("85000.00").compareTo(buyer.getAvailableCash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(buyer.getFrozenCash()));
        assertEquals(0, new BigDecimal("15000.00").compareTo(seller.getAvailableCash()));
        assertEquals(100L, buyer.getQuantity("AAPL"));
        assertEquals(0L, seller.getQuantity("AAPL"));

        OrderBookSnapshot book = f.engine.snapshot("AAPL", 5);
        assertTrue(book.getBids().isEmpty());
        assertTrue(book.getAsks().isEmpty());
        assertEquals(0, new BigDecimal("150.00").compareTo(book.getLastPrice()));
    }

    @Test
    @DisplayName("部分成交后剩余数量挂入订单簿，冻结资金按剩余量保留")
    void partiallyFilledBuyRestsInBook() {
        EngineFixture f = new EngineFixture();
        Account buyer = f.account("buyer", "100000");
        Account seller = f.account("seller", "0");
        seller.seedPosition("AAPL", 60, new BigDecimal("170.00"));

        f.sellLimit("seller", "AAPL", "150.00", 60);
        OrderResult bid = f.buyLimit("buyer", "AAPL", "152.00", 100);

        assertEquals(OrderStatus.PARTIALLY_FILLED, bid.getStatus());
        assertEquals(60L, bid.getFilledQuantity());
        assertEquals(40L, bid.getRemainingQuantity());

        OrderBookSnapshot book = f.engine.snapshot("AAPL", 5);
        assertEquals(1, book.getBids().size());
        assertEquals(0, new BigDecimal("152.00").compareTo(book.getBestBid()));
        assertEquals(40L, book.getBids().get(0).getQuantity());
        assertNull(book.getBestAsk());

        assertEquals(0, new BigDecimal("84920.00").compareTo(buyer.getAvailableCash()));
        assertEquals(0, new BigDecimal("6080.00").compareTo(buyer.getFrozenCash()));
        assertEquals(60L, buyer.getQuantity("AAPL"), "已成交的 60 股应计入持仓");
    }

    @Test
    @DisplayName("价格不交叉时两张单都留在簿中，不吃单")
    void nonCrossingOrdersBothRest() {
        EngineFixture f = new EngineFixture();
        f.account("buyer", "100000");
        Account seller = f.account("seller", "0");
        seller.seedPosition("AAPL", 100, AAPL_REF);

        f.sellLimit("seller", "AAPL", "180.00", 100);
        OrderResult bid = f.buyLimit("buyer", "AAPL", "170.00", 100);

        assertEquals(OrderStatus.NEW, bid.getStatus());
        assertTrue(bid.getTrades().isEmpty());
        OrderBookSnapshot book = f.engine.snapshot("AAPL", 5);
        assertEquals(0, new BigDecimal("170.00").compareTo(book.getBestBid()));
        assertEquals(0, new BigDecimal("180.00").compareTo(book.getBestAsk()));
        assertEquals(0, new BigDecimal("10.00").compareTo(book.getSpread()));
    }

    @Test
    @DisplayName("同价按时间优先：先挂的单先成交")
    void samePriceRespectsTimePriority() {
        EngineFixture f = new EngineFixture();
        f.account("buyer", "100000");
        Account first = f.account("seller-1", "0");
        Account second = f.account("seller-2", "0");
        first.seedPosition("AAPL", 50, AAPL_REF);
        second.seedPosition("AAPL", 50, AAPL_REF);

        OrderResult firstAsk = f.sellLimit("seller-1", "AAPL", "150.00", 50);
        OrderResult secondAsk = f.sellLimit("seller-2", "AAPL", "150.00", 50);
        OrderResult bid = f.buyLimit("buyer", "AAPL", "150.00", 50);

        assertEquals(1, bid.getTrades().size());
        assertEquals(EngineFixture.order(firstAsk).getOrderId(), bid.getTrades().get(0).getSellOrderId());
        assertFalse(EngineFixture.order(secondAsk).getOrderId()
                == bid.getTrades().get(0).getSellOrderId());
    }

    @Test
    @DisplayName("价格优先：更优价的卖单先被吃")
    void betterPriceTakesPrecedence() {
        EngineFixture f = new EngineFixture();
        f.account("buyer", "100000");
        Account high = f.account("seller-high", "0");
        Account low = f.account("seller-low", "0");
        high.seedPosition("AAPL", 50, AAPL_REF);
        low.seedPosition("AAPL", 50, AAPL_REF);

        OrderResult highAsk = f.sellLimit("seller-high", "AAPL", "151.00", 50);
        OrderResult lowAsk = f.sellLimit("seller-low", "AAPL", "150.00", 50);
        OrderResult bid = f.buyLimit("buyer", "AAPL", "152.00", 50);

        assertEquals(1, bid.getTrades().size());
        assertEquals(EngineFixture.order(lowAsk).getOrderId(), bid.getTrades().get(0).getSellOrderId());
        assertEquals(0, new BigDecimal("150.00").compareTo(bid.getTrades().get(0).getPrice()));
        assertNotNull(f.engine.snapshot("AAPL", 5).getBestAsk(), "高价卖单应仍挂在簿上");
        assertEquals(0, new BigDecimal("151.00").compareTo(f.engine.snapshot("AAPL", 5).getBestAsk()));
        assertFalse(EngineFixture.order(highAsk).getOrderId() == 0L);
    }

    @Test
    @DisplayName("多标的订单簿互相隔离")
    void multiSymbolBooksAreIsolated() {
        EngineFixture f = new EngineFixture();
        f.account("buyer", "100000");
        Account seller = f.account("seller", "0");
        seller.seedPosition("TSLA", 100, new BigDecimal("245.30"));
        seller.seedPosition("AAPL", 100, AAPL_REF);

        OrderResult bid = f.buyLimit("buyer", "TSLA", "250.00", 100);
        assertEquals(OrderStatus.NEW, bid.getStatus(), "TSLA 没有对应卖单，不应成交");

        OrderResult ask = f.sellLimit("seller", "AAPL", "150.00", 100);
        assertEquals(OrderStatus.NEW, ask.getStatus(), "AAPL 卖单不应被 TSLA 买单击中");

        assertTrue(bid.getTrades().isEmpty());
        assertEquals(0, new BigDecimal("250.00").compareTo(f.engine.snapshot("TSLA", 5).getBestBid()));
        assertEquals(0, new BigDecimal("150.00").compareTo(f.engine.snapshot("AAPL", 5).getBestAsk()));
    }

    @Test
    @DisplayName("市价单连续吃档，剩余部分自动撤销并释放冻结")
    void marketOrderSweepsBookAndCancelsRemainder() {
        EngineFixture f = new EngineFixture();
        Account buyer = f.account("buyer", "100000");
        Account seller = f.account("seller", "0");
        seller.seedPosition("AAPL", 200, AAPL_REF);

        f.sellLimit("seller", "AAPL", "150.00", 100);
        f.sellLimit("seller", "AAPL", "151.00", 100);
        OrderResult market = f.buyMarket("buyer", "AAPL", 250);

        assertEquals(OrderStatus.CANCELLED, market.getStatus());
        assertEquals(200L, market.getFilledQuantity());
        assertEquals(2, market.getTrades().size());
        assertEquals(0, new BigDecimal("150.00").compareTo(market.getTrades().get(0).getPrice()));
        assertEquals(0, new BigDecimal("151.00").compareTo(market.getTrades().get(1).getPrice()));

        assertEquals(0, new BigDecimal("69900.00").compareTo(buyer.getAvailableCash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(buyer.getFrozenCash()));
        assertTrue(f.engine.snapshot("AAPL", 5).getAsks().isEmpty());
    }

    @Test
    @DisplayName("卖盘为空时市价买单被拒绝")
    void marketBuyRejectedWithoutCounterparty() {
        EngineFixture f = new EngineFixture();
        f.account("buyer", "100000");
        OrderResult market = f.buyMarket("buyer", "AAPL", 10);
        assertFalse(market.isAccepted());
        assertTrue(market.getReason().contains("无对手方"));
    }

    @Test
    @DisplayName("可用资金不足时买单被拒绝且不冻结资金")
    void buyRejectedWhenInsufficientCash() {
        EngineFixture f = new EngineFixture();
        Account poor = f.account("poor", "1000");
        OrderResult bid = f.buyLimit("poor", "AAPL", "150.00", 100);
        assertFalse(bid.isAccepted());
        assertTrue(bid.getReason().contains("可用资金不足"));
        assertEquals(0, new BigDecimal("1000.00").compareTo(poor.getAvailableCash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(poor.getFrozenCash()));
    }

    @Test
    @DisplayName("可卖持仓不足时卖单被拒绝")
    void sellRejectedWhenInsufficientShares() {
        EngineFixture f = new EngineFixture();
        Account seller = f.account("seller", "0");
        seller.seedPosition("AAPL", 10, AAPL_REF);
        OrderResult ask = f.sellLimit("seller", "AAPL", "150.00", 50);
        assertFalse(ask.isAccepted());
        assertTrue(ask.getReason().contains("可卖持仓不足"));
        assertEquals(10L, seller.getAvailableQuantity("AAPL"));
    }

    @Test
    @DisplayName("撤单释放冻结资金并移除挂单")
    void cancelReleasesFrozenCash() {
        EngineFixture f = new EngineFixture();
        Account buyer = f.account("buyer", "20000");
        OrderResult bid = f.buyLimit("buyer", "AAPL", "150.00", 100);
        assertEquals(OrderStatus.NEW, bid.getStatus());
        assertEquals(0, new BigDecimal("5000.00").compareTo(buyer.getAvailableCash()));
        assertEquals(0, new BigDecimal("15000.00").compareTo(buyer.getFrozenCash()));

        long orderId = bid.getOrder().getOrderId();
        assertTrue(f.engine.cancel(orderId));
        assertEquals(OrderStatus.CANCELLED, bid.getOrder().getStatus());
        assertEquals(0, new BigDecimal("20000.00").compareTo(buyer.getAvailableCash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(buyer.getFrozenCash()));
        assertFalse(f.engine.cancel(orderId), "重复撤单应失败");
        assertTrue(f.engine.snapshot("AAPL", 5).getBids().isEmpty());
    }

    @Test
    @DisplayName("收盘后拒绝新订单，并释放所有挂单的冻结")
    void closedMarketRejectsOrders() {
        EngineFixture f = new EngineFixture();
        Account buyer = f.account("buyer", "20000");
        f.buyLimit("buyer", "AAPL", "150.00", 100);

        assertEquals(1, f.engine.closeMarket());
        assertEquals(0, new BigDecimal("20000.00").compareTo(buyer.getAvailableCash()));

        OrderResult afterClose = f.buyLimit("buyer", "AAPL", "150.00", 10);
        assertFalse(afterClose.isAccepted());
        assertTrue(afterClose.getReason().contains("收盘"));
    }

    @Test
    @DisplayName("成交不创造也不消灭资金（现金 + 成本基础守恒）")
    void cashAndCostBasisAreConserved() {
        EngineFixture f = new EngineFixture();
        Account a = f.account("a", "100000");
        Account b = f.account("b", "100000");
        a.seedPosition("AAPL", 200, AAPL_REF);
        b.seedPosition("AAPL", 200, AAPL_REF);

        BigDecimal initialTotal = a.getTotalCash().add(b.getTotalCash());
        BigDecimal initialCost = new BigDecimal("200").multiply(AAPL_REF).multiply(BigDecimal.valueOf(2));

        f.sellLimit("a", "AAPL", "174.00", 50);
        f.buyLimit("b", "AAPL", "176.00", 50);
        f.buyLimit("a", "AAPL", "174.50", 30);
        f.sellLimit("b", "AAPL", "174.20", 30);
        f.buyMarket("b", "AAPL", 20);

        BigDecimal cash = a.getTotalCash().add(b.getTotalCash());
        BigDecimal basis = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        for (Account account : List.of(a, b)) {
            realized = realized.add(account.getPerformance().getRealizedPnl());
            for (var position : account.getPositions().values()) {
                basis = basis.add(position.getAverageCost()
                        .multiply(BigDecimal.valueOf(position.getQuantity())));
            }
        }
        // 不变量：现金 + 持仓成本基础 = 期初资金 + 期初成本基础 + 已实现盈亏
        // （卖出时现金按卖价增加、成本基础按成本价减少，差额正好进入已实现盈亏）
        BigDecimal expected = initialTotal.add(initialCost).add(realized);
        BigDecimal actual = cash.add(basis);
        assertTrue(actual.subtract(expected).abs().compareTo(new BigDecimal("1.00")) < 0,
                "资金应守恒，实际 " + actual + " vs 期望 " + expected
                        + "（已实现盈亏 " + realized + "）");
    }

    @Test
    @DisplayName("多线程并发下单后：账户不为负、订单簿不交叉、无重复订单号")
    void concurrentSubmissionsKeepInvariants() throws Exception {
        EngineFixture f = new EngineFixture();
        int threads = 4;
        int ordersPerThread = 40;
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Account account = f.account("agent-" + i, "50000");
            account.seedPosition("AAPL", 200, AAPL_REF);
            accounts.add(account);
        }
        BigDecimal initialTotal = accounts.stream()
                .map(Account::getTotalCash).reduce(BigDecimal.ZERO, BigDecimal::add);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<Long> orderIds = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    java.util.Random random = new java.util.Random(index);
                    for (int n = 0; n < ordersPerThread; n++) {
                        int priceOffset = random.nextInt(7) - 3;
                        BigDecimal price = AAPL_REF.add(BigDecimal.valueOf(priceOffset));
                        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                        OrderResult result = f.engine.submit(edu.cufe.auction.model.OrderRequest.limit(
                                "agent-" + index, "AAPL", side, price, 10L));
                        if (result.isAccepted()) {
                            orderIds.add(result.getOrder().getOrderId());
                        }
                    }
                } catch (Exception ex) {
                    failures.incrementAndGet();
                    System.err.println("并发下单异常：" + ex);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在 30 秒内结束");
        assertEquals(0, failures.get(), "并发下单不应抛异常");
        assertEquals(orderIds.size(), orderIds.stream().distinct().count(), "订单号不得重复");

        for (Account account : accounts) {
            assertTrue(account.getAvailableCash().signum() >= 0,
                    account.getAgentId() + " 可用现金不得为负：" + account.getAvailableCash());
            assertTrue(account.getFrozenCash().signum() >= 0, "冻结现金不得为负");
            assertTrue(account.getAvailableQuantity("AAPL") >= 0, "可卖持仓不得为负");
        }

        OrderBookSnapshot book = f.engine.snapshot("AAPL", 0);
        if (book.getBestBid() != null && book.getBestAsk() != null) {
            assertTrue(book.getBestBid().compareTo(book.getBestAsk()) < 0,
                    "撮合完成后订单簿不应交叉：" + book.getBestBid() + " / " + book.getBestAsk());
        }

        BigDecimal cash = accounts.stream()
                .map(Account::getTotalCash).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal basis = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        for (Account account : accounts) {
            realized = realized.add(account.getPerformance().getRealizedPnl());
            for (var position : account.getPositions().values()) {
                basis = basis.add(position.getAverageCost()
                        .multiply(BigDecimal.valueOf(position.getQuantity())));
            }
        }
        BigDecimal initialCost = new BigDecimal("200").multiply(AAPL_REF)
                .multiply(BigDecimal.valueOf(threads));
        assertTrue(cash.add(basis).subtract(initialTotal.add(initialCost).add(realized)).abs()
                        .compareTo(new BigDecimal("5.00")) < 0,
                "并发成交后资金仍应守恒（已实现盈亏 " + realized + "）");
    }

    @Test
    @DisplayName("订单簿快照按价格排序并聚合同价数量")
    void snapshotAggregatesAndSortsLevels() {
        EngineFixture f = new EngineFixture();
        f.account("buyer", "1000000");
        Account seller = f.account("seller", "0");
        seller.seedPosition("AAPL", 500, AAPL_REF);

        f.buyLimit("buyer", "AAPL", "170.00", 10);
        f.buyLimit("buyer", "AAPL", "172.00", 20);
        f.buyLimit("buyer", "AAPL", "170.00", 30);
        f.sellLimit("seller", "AAPL", "180.00", 40);
        f.sellLimit("seller", "AAPL", "178.00", 20);

        OrderBookSnapshot book = f.engine.snapshot("AAPL", 5);
        List<PriceLevel> bids = book.getBids();
        assertEquals(2, bids.size());
        assertEquals(0, new BigDecimal("172.00").compareTo(bids.get(0).getPrice()), "买盘应价格降序");
        assertEquals(0, new BigDecimal("170.00").compareTo(bids.get(1).getPrice()));
        assertEquals(40L, bids.get(1).getQuantity(), "同价买盘应聚合为 10+30");
        assertEquals(2, bids.get(1).getOrderCount());

        assertEquals(0, new BigDecimal("178.00").compareTo(book.getBestAsk()), "卖盘应价格升序");
        assertEquals(20L, book.getAsks().get(0).getQuantity());
    }

    @Test
    @DisplayName("未知标的与未知账户被拒绝")
    void unknownSymbolAndAccountRejected() {
        EngineFixture f = new EngineFixture();
        f.account("buyer", "100000");
        OrderResult unknownSymbol = f.buyLimit("buyer", "MSFT", "100.00", 10);
        assertFalse(unknownSymbol.isAccepted());
        assertTrue(unknownSymbol.getReason().contains("未知标的"));

        OrderResult unknownAccount = f.buyLimit("ghost", "AAPL", "100.00", 10);
        assertFalse(unknownAccount.isAccepted());
        assertTrue(unknownAccount.getReason().contains("未知账户"));
    }

    @Test
    @DisplayName("订单字段非法时构造 OrderRequest 直接抛异常")
    void invalidOrderRequestRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> edu.cufe.auction.model.OrderRequest.limit("a", "AAPL", Side.BUY,
                        new BigDecimal("-1"), 10));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> edu.cufe.auction.model.OrderRequest.limit("a", "AAPL", Side.BUY,
                        new BigDecimal("10"), 0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> edu.cufe.auction.model.OrderRequest.market("a", "AAPL", Side.SELL, 0));
    }

    @Test
    @DisplayName("行情广播把成交与快照推送给监听者")
    void publisherReceivesTrades() {
        EngineFixture f = new EngineFixture();
        f.account("buyer", "100000");
        Account seller = f.account("seller", "0");
        seller.seedPosition("AAPL", 10, AAPL_REF);
        f.sellLimit("seller", "AAPL", "150.00", 10);
        f.buyLimit("buyer", "AAPL", "151.00", 10);
        assertEquals(1, f.observedTrades.size());
        assertEquals(0, new BigDecimal("150.00").compareTo(f.observedTrades.get(0).getPrice()));
    }

    @Test
    @DisplayName("Order 对象在成交后状态与数量正确更新")
    void orderStateUpdatedAfterFill() {
        EngineFixture f = new EngineFixture();
        f.account("buyer", "100000");
        Account seller = f.account("seller", "0");
        seller.seedPosition("AAPL", 30, AAPL_REF);
        f.sellLimit("seller", "AAPL", "150.00", 30);
        OrderResult bid = f.buyLimit("buyer", "AAPL", "151.00", 30);
        Order order = EngineFixture.order(bid);
        assertEquals(30L, order.getFilledQuantity());
        assertEquals(0L, order.getRemainingQuantity());
        assertTrue(order.isFilled());
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertTrue(order.canMatchAt(new BigDecimal("150.00")), "买价 150 不高于限价 151，可成交");
        assertFalse(order.canMatchAt(new BigDecimal("152.00")), "买价 152 高于限价 151，不可成交");
    }
}
