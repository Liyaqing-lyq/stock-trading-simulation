package edu.cufe.auction.world;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.SecuritiesLedger.Lot;
import edu.cufe.auction.world.SecuritiesLedger.SellReservation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2.4b：世界订单的纯领域候选（不触碰日志、不调用旧撮合引擎）。
 *
 * <p>覆盖被动价与价格改善、T+1 批次、买卖两侧市价预算与整单拒绝、自成交取消主动剩余而保留
 * 第三方成交、多证券隔离与价格/时间优先、部分成交后的派生冻结与卖单预留、撤单释放。</p>
 */
@DisplayName("WorldExchange：纯订单候选")
class WorldExchangeTest {

    private static final String SYM = "ACME1";
    private static final String SYM2 = "BETA1";
    private static final long DAY = SecuritiesLedger.DAY_MILLIS;

    @Test
    @DisplayName("卖 10×100 遇买限 10.20×100：100@10 成交，买方零冻结，新批次次日可卖")
    void restingAskFillsAtPassivePriceAndBuyerLotLocksUntilNextDay() {
        Sim sim = new Sim(registry(share("A", SYM, 100), share("B", SYM, 100)),
                accounts("A", "alice", "1000.00", "B", "bob", "1020.00"));

        WorldExchange.Outcome sell = sim.submit("s1", "alice", "A", SYM, Side.SELL,
                OrderType.LIMIT, "10.00", 100, 10);
        assertTrue(sell.accepted(), sell.reason());
        assertEquals(WorldExchange.REASON_ACCEPTED, sell.reason());
        assertEquals(100L, reservation(sim.securities(), "order-res:1").quantity(),
                "卖单冻结必须一一对应批次预留");

        WorldExchange.Outcome buy = sim.submit("b1", "bob", "B", SYM, Side.BUY,
                OrderType.LIMIT, "10.20", 100, 20);
        assertTrue(buy.accepted(), buy.reason());
        assertEquals(1, buy.tradeIds().size());
        WorldExchange.Trade trade = buy.exchange().trades().get(0);
        assertEquals(0, trade.price().compareTo(bd("10.00")), "按被动方价格成交");
        assertEquals(100L, trade.quantity());
        assertEquals(0, buy.accounts().get("B").cash().compareTo(bd("20.00")),
                "只花 1000，不按 10.20 扣（初始冻结 1020 已退回差额）");
        assertEquals(0, buy.accounts().get("A").cash().compareTo(bd("2000.00")));
        assertEquals(WorldExchange.OrderStatus.FILLED, buy.exchange().order(buy.orderId()).status());
        assertEquals(0, WorldExchange.frozenCash(buy.exchange(), "B").signum(), "全成交后无剩余冻结");
        assertTrue(buy.securities().reservations().isEmpty(), "全部成交后移除活动预留");

        Lot bought = buy.securities().lots().stream()
                .filter(lot -> lot.lotId().equals("lot-fill:trade-1")).findFirst().orElseThrow();
        assertEquals(100L, bought.remaining());
        assertEquals(1L, bought.eligibleFromDay(), "当日买入次实验日才可卖");
        assertEquals(0, bought.costPrice().compareTo(bd("10.0000")));
    }

    @Test
    @DisplayName("1000 现金市价买 100 扫 50@10/100@11：成交 995、剩 5 取消；1200 买 200 整单拒绝")
    void marketBuyUsesBestAskBudgetAndRejectsWholeOrderWhenFreezeTooLarge() {
        Sim sim = new Sim(registry(share("A", SYM, 50), share("B", SYM, 100)),
                accounts("A", "alice", "1000.00", "B", "bob", "1000.00",
                        "C", "carol", "1000.00", "F", "frank", "1200.00"));
        sim.submit("s1", "alice", "A", SYM, Side.SELL, OrderType.LIMIT, "10.00", 50, 10);
        sim.submit("s2", "bob", "B", SYM, Side.SELL, OrderType.LIMIT, "11.00", 100, 10);

        WorldExchange.Outcome buy = sim.submit("b1", "carol", "C", SYM, Side.BUY,
                OrderType.MARKET, null, 100, 20);
        assertTrue(buy.accepted(), buy.reason());
        assertEquals(2, buy.tradeIds().size(), "市价单扫两档");
        assertEquals(0, buy.accounts().get("C").cash().compareTo(bd("5.00")), "50×10 + 45×11 = 995");
        assertEquals(WorldExchange.OrderStatus.CANCELLED, buy.exchange().order(buy.orderId()).status(),
                "市价未成交部分立即撤销");
        assertEquals(5L, buy.exchange().order(buy.orderId()).remaining());
        assertEquals(0, WorldExchange.frozenCash(buy.exchange(), "C").signum());

        long before = buy.exchange().orders().size();
        int tradesBefore = buy.exchange().trades().size();
        WorldExchange.Outcome rejected = sim.submit("b2", "frank", "F", SYM, Side.BUY,
                OrderType.MARKET, null, 200, 30);
        assertFalse(rejected.accepted());
        assertEquals(WorldExchange.REASON_INSUFFICIENT_CASH, rejected.reason(),
                "卖一 10×200=2000 超过 1200，必须整单拒绝而不是先成交再截断");
        assertNull(rejected.orderId(), "被拒订单不得分配订单号");
        assertEquals(before, rejected.exchange().orders().size(), "不得留下半成品订单");
        assertEquals(tradesBefore, rejected.exchange().trades().size(), "不得产生成交");
        assertEquals(0, rejected.accounts().get("F").cash().compareTo(bd("1200.00")), "拒绝不动现金");
    }

    @Test
    @DisplayName("市价无卖方整单拒绝；卖市价吃买盘按被动买价成交")
    void marketSellConsumesBidsAtPassivePricesAndEmptyBookRejects() {
        Sim empty = new Sim(registry(share("A", SYM, 100)),
                accounts("A", "alice", "1000.00", "C", "carol", "1000.00"));
        WorldExchange.Outcome noSeller = empty.submit("b1", "carol", "C", SYM, Side.BUY,
                OrderType.MARKET, null, 10, 20);
        assertFalse(noSeller.accepted());
        assertEquals(WorldExchange.REASON_NO_LIQUIDITY, noSeller.reason());
        assertTrue(noSeller.exchange().orders().isEmpty());

        Sim sim = new Sim(registry(share("A", SYM, 100), share("B", SYM, 100),
                share("C", SYM, 100)),
                accounts("A", "alice", "1000.00", "B", "bob", "1000.00", "C", "carol", "1000.00"));
        sim.submit("b1", "bob", "B", SYM, Side.BUY, OrderType.LIMIT, "10.00", 60, 5);
        sim.submit("b2", "carol", "C", SYM, Side.BUY, OrderType.LIMIT, "9.50", 100, 5);

        WorldExchange.Outcome sell = sim.submit("s1", "alice", "A", SYM, Side.SELL,
                OrderType.MARKET, null, 100, 20);
        assertTrue(sell.accepted(), sell.reason());
        assertEquals(2, sell.tradeIds().size());
        assertEquals(0, sell.exchange().trades().get(0).price().compareTo(bd("10.00")));
        assertEquals(0, sell.exchange().trades().get(1).price().compareTo(bd("9.50")));
        assertEquals(0, sell.accounts().get("A").cash().compareTo(bd("1980.00")), "60×10 + 40×9.5");
        assertEquals(WorldExchange.OrderStatus.FILLED, sell.exchange().order(sell.orderId()).status());
        assertEquals(0, sell.accounts().get("B").cash().compareTo(bd("400.00")));
        assertEquals(0, sell.accounts().get("C").cash().compareTo(bd("620.00")));
    }

    @Test
    @DisplayName("同 owner 两个账户相遇：取消主动剩余，保留此前第三方成交与 maker")
    void selfTradeStopsAndCancelsActiveRemainderKeepingPriorFill() {
        Sim sim = new Sim(registry(share("A", SYM, 100), share("B", SYM, 100)),
                accounts("A", "alice", "1000.00", "B", "bob", "1000.00", "A2", "alice", "1020.00"));
        sim.submit("s1", "alice", "A", SYM, Side.SELL, OrderType.LIMIT, "10.00", 100, 10);
        sim.submit("s2", "bob", "B", SYM, Side.SELL, OrderType.LIMIT, "9.00", 40, 11);

        WorldExchange.Outcome buy = sim.submit("b1", "alice", "A2", SYM, Side.BUY,
                OrderType.LIMIT, "10.20", 100, 20);
        assertTrue(buy.accepted(), buy.reason());
        assertEquals(WorldExchange.REASON_SELF_TRADE_PREVENTED, buy.reason());
        assertEquals(1, buy.tradeIds().size(), "只保留与第三方的成交");
        assertEquals(0, buy.exchange().trades().get(0).price().compareTo(bd("9.00")));
        WorldExchange.Order incoming = buy.exchange().order(buy.orderId());
        assertEquals(WorldExchange.OrderStatus.CANCELLED, incoming.status());
        assertEquals(40L, incoming.filled());
        assertEquals(60L, incoming.remaining(), "遇到自有挂单后的剩余量被取消");
        assertEquals(0, buy.accounts().get("A2").cash().compareTo(bd("660.00")));
        assertEquals(0, buy.accounts().get("B").cash().compareTo(bd("1360.00")));
        assertEquals(WorldExchange.OrderStatus.ACTIVE,
                buy.exchange().orders().stream().filter(o -> o.accountId().equals("A"))
                        .findFirst().orElseThrow().status(), "自有被动挂单保持不变");
        assertEquals(100L, reservation(buy.securities(), "order-res:1").quantity());
    }

    @Test
    @DisplayName("多证券隔离；同价按 sequence 先进先出，价优不跳队")
    void symbolsAreIsolatedAndPricePriorityBeatsSequence() {
        Sim sim = new Sim(registry(share("A", SYM, 100), share("B", SYM, 100),
                share("C", SYM2, 100)),
                accounts("A", "alice", "1000.00", "B", "bob", "1000.00",
                        "C", "carol", "1000.00", "D", "dave", "5000.00"));
        sim.submit("s1", "alice", "A", SYM, Side.SELL, OrderType.LIMIT, "10.00", 50, 10);
        sim.submit("s2", "bob", "B", SYM, Side.SELL, OrderType.LIMIT, "10.00", 50, 10);
        sim.submit("s3", "carol", "C", SYM2, Side.SELL, OrderType.LIMIT, "8.00", 100, 10);

        WorldExchange.Outcome buy = sim.submit("b1", "dave", "D", SYM, Side.BUY,
                OrderType.LIMIT, "10.20", 60, 20);
        assertTrue(buy.accepted(), buy.reason());
        assertEquals(2, buy.tradeIds().size());
        assertEquals(sellOrder(sim.exchange(), "A"), buy.exchange().trades().get(0).sellOrderId());
        assertEquals(sellOrder(sim.exchange(), "B"), buy.exchange().trades().get(1).sellOrderId());
        assertEquals(WorldExchange.OrderStatus.FILLED,
                buy.exchange().order(sellOrder(sim.exchange(), "A")).status());
        assertEquals(40L, buy.exchange().order(sellOrder(sim.exchange(), "B")).remaining());
        WorldExchange.Order other = buy.exchange().orders().stream()
                .filter(order -> order.symbol().equals(SYM2)).findFirst().orElseThrow();
        assertEquals(WorldExchange.OrderStatus.ACTIVE, other.status(), "另一证券不受影响");
        assertEquals(100L, other.remaining());
    }

    @Test
    @DisplayName("部分成交：剩余冻结 = 限价 × 剩余，可用现金与撤单释放一致")
    void partialBuyKeepsDerivedFreezeAndCancelReleasesIt() {
        Sim sim = new Sim(registry(share("A", SYM, 100), share("B", SYM, 100)),
                accounts("A", "alice", "1000.00", "B", "bob", "1100.00"));
        sim.submit("s1", "alice", "A", SYM, Side.SELL, OrderType.LIMIT, "10.00", 60, 10);

        WorldExchange.Outcome buy = sim.submit("b1", "bob", "B", SYM, Side.BUY,
                OrderType.LIMIT, "10.20", 100, 20);
        assertTrue(buy.accepted(), buy.reason());
        WorldExchange.Order resting = buy.exchange().order(buy.orderId());
        assertEquals(WorldExchange.OrderStatus.ACTIVE, resting.status(), "剩余 40 继续挂簿");
        assertEquals(40L, resting.remaining());
        assertTrue(buy.securities().reservations().isEmpty(),
                "卖方 60 股已全部成交，活动预留应移除");
        assertEquals(0, WorldExchange.frozenCash(buy.exchange(), "B").compareTo(bd("408.00")),
                "10.20 × 40");
        assertEquals(0, WorldExchange.freeCash(buy.exchange(), buy.accounts(), "B")
                .compareTo(bd("92.00")), "500 - 408");

        WorldExchange.Outcome cancelled = WorldExchange.cancel(buy.exchange(), buy.accounts(),
                buy.securities(), "bob", buy.orderId(), 30);
        assertTrue(cancelled.accepted(), cancelled.reason());
        assertEquals(WorldExchange.REASON_CANCELLED, cancelled.reason());
        assertEquals(0, WorldExchange.frozenCash(cancelled.exchange(), "B").signum());
        assertEquals(0, WorldExchange.freeCash(cancelled.exchange(), cancelled.accounts(), "B")
                .compareTo(bd("500.00")));
        assertEquals(0, cancelled.accounts().get("B").cash().compareTo(bd("500.00")), "撤单不动现金");
        assertEquals(WorldExchange.OrderStatus.CANCELLED,
                cancelled.exchange().order(buy.orderId()).status());
    }

    @Test
    @DisplayName("当日买入不能当日卖出；次实验日才形成新的合格预留")
    void sameDayPurchaseCannotBeSoldUntilNextExperimentalDay() {
        Sim sim = new Sim(registry(share("A", SYM, 100), share("C", SYM, 100)),
                accounts("A", "alice", "1000.00", "B", "bob", "2000.00", "C", "carol", "1000.00"));
        sim.submit("s1", "alice", "A", SYM, Side.SELL, OrderType.LIMIT, "10.00", 100, 10);
        WorldExchange.Outcome buy = sim.submit("b1", "bob", "B", SYM, Side.BUY,
                OrderType.LIMIT, "10.20", 100, 20);
        assertTrue(buy.accepted(), buy.reason());
        assertEquals(WorldExchange.OrderStatus.FILLED, buy.exchange().order(buy.orderId()).status());

        WorldExchange.Outcome sameDay = sim.submit("s2", "bob", "B", SYM, Side.SELL,
                OrderType.LIMIT, "10.00", 100, 30);
        assertFalse(sameDay.accepted());
        assertEquals(WorldExchange.REASON_INSUFFICIENT_SHARES, sameDay.reason());
        assertTrue(sameDay.securities().reservations().stream()
                .noneMatch(r -> r.reservationId().equals("order-res:3")), "被拒卖单不得留预留");

        WorldExchange.Outcome nextDay = sim.submit("s3", "bob", "B", SYM, Side.SELL,
                OrderType.LIMIT, "10.00", 100, DAY + 10);
        assertTrue(nextDay.accepted(), nextDay.reason());
        SellReservation reservation = reservation(nextDay.securities(), "order-res:3");
        assertEquals(100L, reservation.quantity());
        assertEquals("lot-fill:trade-1", reservation.allocations().get(0).lotId(),
                "预留必须指向当日买入形成的具体批次");
    }

    @Test
    @DisplayName("不同产品财产主体相互独立：同价买卖正常结算，不按账户或组织合并")
    void differentProductOwnersSettleIndependently() {
        Sim sim = new Sim(registry(share("P1", SYM, 20)),
                accounts("P1", "prod-1", "1000.00", "P2", "prod-2", "1000.00"));
        WorldExchange.Outcome sell = sim.submit("s1", "prod-1", "P1", SYM, Side.SELL,
                OrderType.LIMIT, "10.00", 20, 10);
        assertTrue(sell.accepted(), sell.reason());
        WorldExchange.Outcome buy = sim.submit("b1", "prod-2", "P2", SYM, Side.BUY,
                OrderType.LIMIT, "10.20", 20, 20);
        assertTrue(buy.accepted(), buy.reason());
        assertEquals(1, buy.tradeIds().size());
        assertEquals(0, buy.accounts().get("P1").cash().compareTo(bd("1200.00")));
        assertEquals(0, buy.accounts().get("P2").cash().compareTo(bd("800.00")));
        assertEquals(0L, lotQuantity(buy.securities(), "P1"));
        assertEquals(20L, lotQuantity(buy.securities(), "P2"));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 纯候选的连续场景夹具：把每次候选结果接回下一条命令。 */
    private static final class Sim {
        private WorldExchange.State exchange = WorldExchange.opening();
        private Map<String, CashAccount> accounts;
        private SecuritiesLedger.State securities;

        Sim(OpeningRegistry registry, Map<String, CashAccount> accounts) {
            this.accounts = accounts;
            this.securities = SecuritiesLedger.opening(registry);
        }

        WorldExchange.Outcome submit(String taskId, String actor, String account, String symbol,
                                     Side side, OrderType type, String price, long quantity, long now) {
            WorldExchange.Outcome outcome = WorldExchange.submit(exchange, accounts, securities,
                    taskId, "c-" + taskId, actor, account, symbol, side, type,
                    price == null ? null : bd(price), quantity, now);
            exchange = outcome.exchange();
            accounts = outcome.accounts();
            securities = outcome.securities();
            return outcome;
        }

        WorldExchange.State exchange() {
            return exchange;
        }

        SecuritiesLedger.State securities() {
            return securities;
        }
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static Map<String, CashAccount> accounts(String... triples) {
        Map<String, CashAccount> map = new LinkedHashMap<>();
        for (int i = 0; i < triples.length; i += 3) {
            map.put(triples[i], new CashAccount(triples[i + 1], bd(triples[i + 2]), "实验开局历史禀赋"));
        }
        return map;
    }

    private static SellReservation reservation(SecuritiesLedger.State state, String reservationId) {
        SellReservation found = SecuritiesLedger.reservation(state, reservationId);
        assertNotNull(found, "缺少预留：" + reservationId);
        return found;
    }

    private static long lotQuantity(SecuritiesLedger.State state, String accountId) {
        long total = 0L;
        for (Lot lot : state.lots()) {
            if (lot.accountId().equals(accountId) && lot.symbol().equals(SYM)) {
                total = Math.addExact(total, lot.remaining());
            }
        }
        return total;
    }

    private static long sellOrder(WorldExchange.State state, String accountId) {
        return state.orders().stream()
                .filter(order -> order.accountId().equals(accountId) && order.side() == Side.SELL)
                .findFirst().orElseThrow().orderId();
    }

    private record Share(String account, String symbol, long quantity) {
    }

    private static Share share(String account, String symbol, long quantity) {
        return new Share(account, symbol, quantity);
    }

    /** 按分配自动推出各证券发行量，保证开局登记自身守恒。 */
    private static OpeningRegistry registry(Share... shares) {
        Map<String, Long> issued = new LinkedHashMap<>();
        List<OpeningRegistry.ShareAllocation> allocations = new ArrayList<>();
        for (Share share : shares) {
            issued.merge(share.symbol(), share.quantity(), Math::addExact);
            allocations.add(new OpeningRegistry.ShareAllocation(share.account(), share.symbol(),
                    share.quantity(), bd("10.0000"), ShareClass.FLOAT));
        }
        List<OpeningRegistry.Security> securities = new ArrayList<>();
        issued.forEach((symbol, quantity) -> securities.add(
                new OpeningRegistry.Security(symbol, "acme", quantity, bd("10.00"))));
        return new OpeningRegistry(parties(), owners(), securities, allocations);
    }

    private static List<OpeningRegistry.Party> parties() {
        return List.of(
                new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "Alice"),
                new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "Bob"),
                new OpeningRegistry.Party("carol", OpeningRegistry.PartyKind.PERSON, "Carol"),
                new OpeningRegistry.Party("dave", OpeningRegistry.PartyKind.PERSON, "Dave"),
                new OpeningRegistry.Party("erin", OpeningRegistry.PartyKind.PERSON, "Erin"),
                new OpeningRegistry.Party("acme", OpeningRegistry.PartyKind.ORGANIZATION, "Acme"));
    }

    private static List<OpeningRegistry.PropertyOwner> owners() {
        return List.of(
                new OpeningRegistry.PropertyOwner("alice", OpeningRegistry.OwnerKind.PERSON, "alice"),
                new OpeningRegistry.PropertyOwner("bob", OpeningRegistry.OwnerKind.PERSON, "bob"),
                new OpeningRegistry.PropertyOwner("carol", OpeningRegistry.OwnerKind.PERSON, "carol"),
                new OpeningRegistry.PropertyOwner("dave", OpeningRegistry.OwnerKind.PERSON, "dave"),
                new OpeningRegistry.PropertyOwner("erin", OpeningRegistry.OwnerKind.PERSON, "erin"),
                new OpeningRegistry.PropertyOwner("prod-1", OpeningRegistry.OwnerKind.PRODUCT, null),
                new OpeningRegistry.PropertyOwner("prod-2", OpeningRegistry.OwnerKind.PRODUCT, null),
                new OpeningRegistry.PropertyOwner("acme", OpeningRegistry.OwnerKind.ORGANIZATION, "acme"));
    }
}
