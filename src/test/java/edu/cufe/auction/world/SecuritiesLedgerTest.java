package edu.cufe.auction.world;

import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.SecuritiesLedger.FillOutcome;
import edu.cufe.auction.world.SecuritiesLedger.Lot;
import edu.cufe.auction.world.SecuritiesLedger.LotQuantity;
import edu.cufe.auction.world.SecuritiesLedger.Outcome;
import edu.cufe.auction.world.SecuritiesLedger.State;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2.3：持仓批次、冻结与清算的纯计算。
 *
 * <p>覆盖：开局批次的稳定标识与成本精度、可卖量只减冻结一次、锁定/库存股计入发行但不可卖、
 * 当日买入次日可卖、先入先出（取得位置后批次标识）、超量与重复预留、撤单只释放未成交量、
 * 成交金额为价格×数量、实验日按固定模拟长度整除、数量累计溢出明确失败。</p>
 *
 * <p>本测试不涉及 I/O、运行日志或现金子账，只验证候选状态。</p>
 */
@DisplayName("SecuritiesLedger：批次资格、冻结与清算计算")
class SecuritiesLedgerTest {

    private static final String SYM = "ACME1";
    private static final long DAY = SecuritiesLedger.DAY_MILLIS;

    @Test
    @DisplayName("开局为每条分配生成稳定批次，成本精确 4 位并计入发行总数")
    void openingProducesStableLotsWithExactCost() {
        OpeningRegistry registry = registry();
        State first = SecuritiesLedger.opening(registry);
        State second = SecuritiesLedger.opening(registry);

        assertEquals(first, second, "同一登记必须产生完全相同的开局状态");
        assertEquals(2, first.lots().size());
        assertEquals(200L, held(first, "A") + held(first, "B"));
        assertEquals(100L, held(first, "A"));
        assertTrue(first.lots().stream().allMatch(lot -> lot.eligibleFromDay() == 0L),
                "初始流通批次在实验第 0 日即可卖");
        assertTrue(first.lots().stream().allMatch(lot -> lot.costPrice().scale() == 4),
                "成本价必须保持 4 位");
        assertTrue(first.lots().stream().allMatch(lot -> lot.classification() == ShareClass.FLOAT));
        assertEquals(2, first.lots().stream().map(Lot::lotId).distinct().count(), "批次标识不得重复");
        assertTrue(first.reservations().isEmpty());
        assertTrue(first.usedReservationIds().isEmpty());
        assertTrue(first.reports().isEmpty());
        assertEquals(100L, SecuritiesLedger.available(first, "A", SYM, 0));
    }

    @Test
    @DisplayName("可卖量等于剩余减冻结且只减一次，预留不改变批次剩余")
    void availableSubtractsOnlyFrozenFromEligibleFloatLots() {
        State state = new State(List.of(lot("l1", "A", 100, 0, 0)), List.of(), Set.of(), Map.of());
        assertEquals(100L, available(state, "A", 0));

        Outcome reserved = SecuritiesLedger.reserve(state, "res-1", "A", SYM, 30, 0);
        assertTrue(reserved.accepted(), reserved.reason());
        assertEquals(SecuritiesLedger.REASON_RESERVED, reserved.reason());
        assertEquals(100L, lotOf(reserved.state(), "l1").remaining(), "预留只登记冻结，不改剩余");
        assertEquals(30L, reserved.state().reservations().get(0).quantity());
        assertEquals(70L, available(reserved.state(), "A", 0),
                "可卖量必须是 剩余 100 - 冻结 30 = 70；若得到 40 就是把冻结算了两次");
    }

    @Test
    @DisplayName("锁定与库存股计入发行总数，但本夹具不能卖出")
    void lockedAndTreasurySharesCountTowardIssuanceButAreNotSellable() {
        State state = new State(List.of(
                lot("f", "A", 50, 0, 0),
                lot("k", "A", 40, 0, 1, ShareClass.LOCKED),
                lot("t", "acme", 10, 0, 2, ShareClass.TREASURY)), List.of(), Set.of(), Map.of());

        assertEquals(100L, held(state, "A") + held(state, "acme"), "全部批次合计等于发行总数");
        assertEquals(50L, available(state, "A", 0), "锁定股不可卖");
        assertEquals(0L, available(state, "acme", 0), "库存股不可卖");

        Outcome over = SecuritiesLedger.reserve(state, "res-1", "A", SYM, 60, 0);
        assertFalse(over.accepted());
        assertEquals(SecuritiesLedger.REASON_INSUFFICIENT_SHARES, over.reason());
        assertSame(state, over.state(), "拒绝不得产生新状态");

        Outcome ok = SecuritiesLedger.reserve(state, "res-2", "A", SYM, 50, 0);
        assertTrue(ok.accepted(), ok.reason());
        assertEquals(0L, available(ok.state(), "A", 0));
        assertEquals(100L, held(ok.state(), "A") + held(ok.state(), "acme"), "冻结不改总股份");
    }

    @Test
    @DisplayName("当日买入的批次次日才可卖，买入当天不计入可卖量")
    void sameDayPurchaseBecomesEligibleNextExperimentDay() {
        State opened = new State(List.of(
                lot("la", "A", 100, 0, 0),
                lot("lb", "B", 100, 0, 1)), List.of(), Set.of(), Map.of());

        State reserved = SecuritiesLedger.reserve(opened, "res-b", "B", SYM, 60, 0).state();
        FillOutcome fill = SecuritiesLedger.fill(reserved, "fill-1", "res-b", "A",
                new BigDecimal("10.00"), 60, 1_000L);
        assertTrue(fill.accepted(), fill.reason());

        Lot bought = lotOf(fill.state(), "lot-fill:fill-1");
        assertEquals(60L, bought.remaining());
        assertEquals(1L, bought.eligibleFromDay(), "买入当日为第 0 日，次日（第 1 日）才可卖");
        assertEquals(1_000L, bought.acquiredAtMillis());
        assertEquals(0, bought.costPrice().compareTo(new BigDecimal("10.0000")));
        assertEquals(100L, available(fill.state(), "A", 0),
                "当日买入不可卖：只有自有底仓 100 计入，买入 60 不计入");
        assertEquals(160L, available(fill.state(), "A", 1), "次实验日可卖 100 + 60");
        assertEquals(40L, available(fill.state(), "B", 0), "卖方只剩旧底仓余量");
    }

    @Test
    @DisplayName("预留按取得位置先入先出，同位置按批次标识排序")
    void reserveConsumesFifoByAcquiredPositionThenLotId() {
        State byPosition = new State(List.of(
                lot("late", "A", 40, 0, 7),
                lot("early", "A", 60, 0, 2)), List.of(), Set.of(), Map.of());
        Outcome fifo = SecuritiesLedger.reserve(byPosition, "res-1", "A", SYM, 70, 0);
        assertTrue(fifo.accepted(), fifo.reason());
        assertEquals(List.of(new LotQuantity("early", 60), new LotQuantity("late", 10)),
                fifo.state().reservations().get(0).allocations(), "先耗尽取得位置更早的批次");

        State tie = new State(List.of(
                lot("lz", "A", 10, 0, 5),
                lot("la", "A", 10, 0, 5)), List.of(), Set.of(), Map.of());
        Outcome tiebreak = SecuritiesLedger.reserve(tie, "res-2", "A", SYM, 15, 0);
        assertEquals(List.of(new LotQuantity("la", 10), new LotQuantity("lz", 5)),
                tiebreak.state().reservations().get(0).allocations(), "同位置按批次标识排序");
    }

    @Test
    @DisplayName("超量、重复预留标识与已完全冻结都按明确原因拒绝且不改状态")
    void reserveRejectsOverQuantityAndReusedReservationId() {
        State state = new State(List.of(lot("l1", "A", 100, 0, 0)), List.of(), Set.of(), Map.of());

        Outcome over = SecuritiesLedger.reserve(state, "res-1", "A", SYM, 101, 0);
        assertFalse(over.accepted());
        assertEquals(SecuritiesLedger.REASON_INSUFFICIENT_SHARES, over.reason());
        assertSame(state, over.state());

        Outcome accepted = SecuritiesLedger.reserve(state, "res-2", "A", SYM, 100, 0);
        assertTrue(accepted.accepted(), accepted.reason());
        assertEquals(100L, accepted.state().reservations().get(0).quantity());
        assertTrue(accepted.state().usedReservationIds().contains("res-2"));

        Outcome reused = SecuritiesLedger.reserve(accepted.state(), "res-2", "A", SYM, 1, 0);
        assertFalse(reused.accepted());
        assertEquals(SecuritiesLedger.REASON_RESERVATION_ID_REUSED, reused.reason());

        Outcome noFree = SecuritiesLedger.reserve(accepted.state(), "res-3", "A", SYM, 1, 0);
        assertFalse(noFree.accepted());
        assertEquals(SecuritiesLedger.REASON_INSUFFICIENT_SHARES, noFree.reason(),
                "已冻结的数量不能再被第二笔预留使用");
    }

    @Test
    @DisplayName("成交消耗预留，撤单只释放未成交部分")
    void cancelReleasesOnlyTheUnfilledQuantity() {
        State state = new State(List.of(lot("l1", "A", 100, 0, 0)), List.of(), Set.of(), Map.of());
        State reserved = SecuritiesLedger.reserve(state, "res-1", "A", SYM, 80, 0).state();
        assertEquals(20L, available(reserved, "A", 0));

        FillOutcome fill = SecuritiesLedger.fill(reserved, "fill-1", "res-1", "B",
                new BigDecimal("10.00"), 30, 1_000L);
        assertTrue(fill.accepted(), fill.reason());
        assertEquals(70L, lotOf(fill.state(), "l1").remaining());
        assertEquals(50L, fill.state().reservations().get(0).quantity(), "成交消耗预留");
        assertEquals(20L, available(fill.state(), "A", 0));

        Outcome cancelled = SecuritiesLedger.cancel(fill.state(), "res-1");
        assertTrue(cancelled.accepted(), cancelled.reason());
        assertTrue(cancelled.state().reservations().isEmpty(), "活动预留必须移除");
        assertEquals(70L, lotOf(cancelled.state(), "l1").remaining(), "撤单不恢复已成交数量");
        assertEquals(70L, available(cancelled.state(), "A", 0));
        assertTrue(cancelled.state().usedReservationIds().contains("res-1"), "已用标识不复用");

        Outcome again = SecuritiesLedger.cancel(cancelled.state(), "res-1");
        assertFalse(again.accepted());
        assertEquals(SecuritiesLedger.REASON_UNKNOWN_RESERVATION, again.reason());
    }

    @Test
    @DisplayName("成交把股份转成买方次日批次，金额为价格×数量且精度到分")
    void fillTransfersSharesAndCreatesBuyerLotAtPrice() {
        State state = new State(List.of(
                lot("la", "A", 100, 0, 0),
                lot("lb", "B", 100, 0, 1)), List.of(), Set.of(), Map.of());
        State reserved = SecuritiesLedger.reserve(state, "res-1", "A", SYM, 30, 0).state();

        FillOutcome fill = SecuritiesLedger.fill(reserved, "fill-1", "res-1", "B",
                new BigDecimal("10.37"), 30, 5L);
        assertTrue(fill.accepted(), fill.reason());
        assertEquals(SecuritiesLedger.REASON_FILLED, fill.reason());
        assertEquals(new BigDecimal("311.10"), fill.amount(), "10.37 × 30 必须用 BigDecimal 精确计算");
        assertEquals(2, fill.amount().scale());

        assertEquals(70L, lotOf(fill.state(), "la").remaining());
        Lot bought = lotOf(fill.state(), "lot-fill:fill-1");
        assertEquals(30L, bought.remaining());
        assertEquals("B", bought.accountId());
        assertEquals(0, bought.costPrice().compareTo(new BigDecimal("10.3700")), "成本价保存 4 位");
        assertEquals(200L, held(fill.state(), "A") + held(fill.state(), "B"), "双方股份总量守恒");

        FillOutcome again = SecuritiesLedger.fill(fill.state(), "fill-2", "res-1", "B",
                new BigDecimal("10.00"), 30, 6L);
        assertFalse(again.accepted());
        assertEquals(SecuritiesLedger.REASON_UNKNOWN_RESERVATION, again.reason(),
                "已完全成交的预留不再活动，不能再次成交");
        assertSame(fill.state(), again.state());

        State partial = SecuritiesLedger.reserve(fill.state(), "res-2", "A", SYM, 10, 0).state();
        FillOutcome over = SecuritiesLedger.fill(partial, "fill-3", "res-2", "B",
                new BigDecimal("10.00"), 11, 7L);
        assertFalse(over.accepted());
        assertEquals(SecuritiesLedger.REASON_FILL_EXCEEDS_RESERVATION, over.reason(),
                "成交不得超过该预留的剩余冻结");
        assertSame(partial, over.state());
    }

    @Test
    @DisplayName("实验日按固定模拟毫秒整除，不使用本机时区或自然日")
    void dayIsFloorOfExperimentMilliseconds() {
        assertEquals(0L, SecuritiesLedger.day(0));
        assertEquals(0L, SecuritiesLedger.day(DAY - 1));
        assertEquals(1L, SecuritiesLedger.day(DAY));
        assertEquals(2L, SecuritiesLedger.day(2 * DAY + 5));
        assertThrows(IllegalArgumentException.class, () -> SecuritiesLedger.day(-1));
    }

    @Test
    @DisplayName("可卖量累计溢出时明确失败，不返回错误小计")
    void availableOverflowRejected() {
        State state = new State(List.of(
                lot("a", "A", Long.MAX_VALUE, 0, 0),
                lot("b", "A", 1, 0, 1)), List.of(), Set.of(), Map.of());
        assertThrows(ArithmeticException.class, () -> available(state, "A", 0));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static long available(State state, String accountId, long day) {
        return SecuritiesLedger.available(state, accountId, SYM, day);
    }

    private static long held(State state, String accountId) {
        long total = 0L;
        for (Lot lot : state.lots()) {
            if (lot.accountId().equals(accountId) && lot.symbol().equals(SYM)) {
                total = Math.addExact(total, lot.remaining());
            }
        }
        return total;
    }

    private static Lot lotOf(State state, String lotId) {
        return state.lots().stream().filter(lot -> lot.lotId().equals(lotId)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少批次 " + lotId + "：" + state.lots()));
    }

    private static Lot lot(String lotId, String accountId, long remaining, long eligibleFromDay,
                           long acquiredPosition) {
        return lot(lotId, accountId, remaining, eligibleFromDay, acquiredPosition, ShareClass.FLOAT);
    }

    private static Lot lot(String lotId, String accountId, long remaining, long eligibleFromDay,
                           long acquiredPosition, ShareClass classification) {
        return new Lot(lotId, accountId, SYM, remaining, eligibleFromDay, 0L, acquiredPosition,
                new BigDecimal("10.0000"), classification);
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(
                List.of(new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "Alice"),
                        new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "Bob"),
                        new OpeningRegistry.Party("acme", OpeningRegistry.PartyKind.ORGANIZATION, "Acme")),
                List.of(new OpeningRegistry.PropertyOwner("alice",
                                OpeningRegistry.OwnerKind.PERSON, "alice"),
                        new OpeningRegistry.PropertyOwner("bob",
                                OpeningRegistry.OwnerKind.PERSON, "bob"),
                        new OpeningRegistry.PropertyOwner("acme",
                                OpeningRegistry.OwnerKind.ORGANIZATION, "acme")),
                List.of(new OpeningRegistry.Security(SYM, "acme", 200, new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("A", SYM, 100,
                                new BigDecimal("10.0000"), ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("B", SYM, 100,
                                new BigDecimal("10.0000"), ShareClass.FLOAT)));
    }

    /** 供运行层测试复用同一只 200 股证券登记。 */
    static OpeningRegistry sharedRegistry() {
        return registry();
    }
}
