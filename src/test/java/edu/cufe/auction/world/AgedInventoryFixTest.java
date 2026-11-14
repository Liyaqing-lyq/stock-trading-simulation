package edu.cufe.auction.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1–F4 修复的定向回归（EXP-AGED-2）：报损后发货成本、分次退款恒等、累计产能、
 * 自成交配置拒绝、未关期卖过期。只验纯核与真实 {@link CashLedger} 账户表。
 */
class AgedInventoryFixTest {

    private static final long PERIOD = 30L * 24L * 3_600_000L;

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, CashLedger.CashAccount> accounts(String company, String ch1,
                                                               String ch2, String terminal) {
        Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
        map.put("CO", new CashLedger.CashAccount("company", money(company), "EXP-AGED-2 开局"));
        map.put("SUP", new CashLedger.CashAccount("supplier", money("0.00"), "EXP-AGED-2 开局"));
        map.put("CONV", new CashLedger.CashAccount("conversion", money("0.00"), "EXP-AGED-2 开局"));
        map.put("FIX", new CashLedger.CashAccount("fixed", money("0.00"), "EXP-AGED-2 开局"));
        map.put("TERM", new CashLedger.CashAccount("terminal", money(terminal), "EXP-AGED-2 开局"));
        map.put("CH1", new CashLedger.CashAccount("channel-1", money(ch1), "EXP-AGED-2 开局"));
        map.put("CH2", new CashLedger.CashAccount("channel-2", money(ch2), "EXP-AGED-2 开局"));
        return map;
    }

    private static AgedInventoryModel.Config config(long capacity, long aging, long shelf,
                                                    int spoilageBp, int prepayBp, long demandUnits) {
        return new AgedInventoryModel.Config("cufe-company", "mgr", "CO", "SUP", "CONV", "FIX",
                "TERM", money("1.00"), money("2.00"), money("0.00"), PERIOD, capacity, aging, shelf,
                spoilageBp, prepayBp,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", demandUnits,
                                money("10.00"), money("5.00")),
                        new AgedInventoryModel.ChannelSpec("C2", "CH2", demandUnits,
                                money("10.00"), money("5.00"))),
                List.of(new AgedInventoryModel.DemandQuote(0L, "C1", demandUnits),
                        new AgedInventoryModel.DemandQuote(1L, "C1", demandUnits),
                        new AgedInventoryModel.DemandQuote(2L, "C1", demandUnits),
                        new AgedInventoryModel.DemandQuote(0L, "C2", demandUnits),
                        new AgedInventoryModel.DemandQuote(1L, "C2", demandUnits),
                        new AgedInventoryModel.DemandQuote(2L, "C2", demandUnits)),
                AgedInventoryModel.EXP_LABEL);
    }

    private static void accepted(AgedInventoryModel.Outcome outcome) {
        assertTrue(outcome.accepted(), () -> "期望接受，实际 " + outcome.reason());
    }

    private static long at(long period) {
        return period * PERIOD + 1L;
    }

    @Test
    @DisplayName("F1 报损后再发货：本次成本含已报损累计，全部发完不抛异常")
    void spoilageThenShipKeepsCostAllocationConsistent() {
        AgedInventoryModel.Config config = config(200L, 0L, 6L, 1000, 5000, 200L);
        Map<String, CashLedger.CashAccount> accounts = accounts("1000.00", "1000.00", "1000.00",
                "10000.00");
        AgedInventoryModel.Outcome opened = AgedInventoryModel.open(config, accounts,
                List.of(new AgedInventoryModel.RawLot("R1", 100L, money("100.00"),
                        money("100.00"), 0L, 100L)));
        accepted(opened);
        AgedInventoryModel.State state = opened.state();
        accounts = opened.accounts();
        AgedInventoryModel.Outcome step = AgedInventoryModel.produce(state, "mgr", 100L, "B1",
                at(0), accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        // 期末报损 10%（10 单位，成本 30.00）
        step = AgedInventoryModel.closePeriod(state, PERIOD, accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        AgedInventoryModel.BatchLot batch = state.batches().get(0);
        assertEquals(90L, batch.remaining());
        assertEquals(money("30.00"), batch.costSpoiled());
        assertEquals(money("270.00"), batch.remainingCost());
        // 部分发 10：本次成本必须是 C(20) − C(10) = 30.00（不是 60.00）
        step = AgedInventoryModel.ship(state, "mgr", "C1", 10L, "S1", at(1), accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        assertEquals(money("30.00"), state.shipmentLots().get(0).totalCost());
        assertEquals(money("240.00"), state.batches().get(0).remainingCost());
        // 全部余量 80：成本 240.00，costOut+costSpoiled = 300.00 恰好等于总成本，不得抛异常
        step = AgedInventoryModel.ship(state, "mgr", "C2", 80L, "S2", at(1), accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        AgedInventoryModel.BatchLot drained = state.batches().get(0);
        assertEquals(0L, drained.remaining());
        assertEquals(money("270.00"), drained.costOut());
        assertEquals(money("30.00"), drained.costSpoiled());
        assertEquals(money("0.00"), drained.remainingCost());
        // 全部卖完：COGS 合计 270.00，报损 30.00，成本合计 300.00
        step = AgedInventoryModel.sellThrough(state, "C1", 10L, "T1", at(1), accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.sellThrough(state, "C2", 80L, "T2", at(1), accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        AgedInventoryModel.Books books = AgedInventoryModel.books(state, accounts);
        assertEquals(money("270.00"), books.cogs());
        assertEquals(money("30.00"), books.spoilageLoss());
        assertEquals(money("0.00"), books.inventoryCost());
        assertEquals(money("900.00"), books.revenue());
        assertNull(AgedInventoryModel.validate(state, accounts));
        // 分币尾差：3 单位 × 10.01 的预收 30.02，先报损 1 单位再卖 2 单位，预收必须精确闭合
        AgedInventoryModel.Config cents = new AgedInventoryModel.Config("cufe-company", "mgr",
                "CO", "SUP", "CONV", "FIX", "TERM", money("1.00"), money("2.00"), money("0.00"),
                PERIOD, 10L, 0L, 6L, 5000, 5000,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", 10L, money("10.01"),
                        money("5.00"))),
                List.of(new AgedInventoryModel.DemandQuote(0L, "C1", 10L),
                        new AgedInventoryModel.DemandQuote(1L, "C1", 10L)),
                AgedInventoryModel.EXP_LABEL);
        Map<String, CashLedger.CashAccount> small = accounts("100.00", "100.00", "0.00",
                "100.00");
        AgedInventoryModel.Outcome tiny = AgedInventoryModel.open(cents, small,
                List.of(new AgedInventoryModel.RawLot("R2", 10L, money("10.00"), money("10.00"), 0L,
                        10L)));
        accepted(tiny);
        AgedInventoryModel.State smallState = tiny.state();
        Map<String, CashLedger.CashAccount> smallAccounts = tiny.accounts();
        tiny = AgedInventoryModel.produce(smallState, "mgr", 3L, "B2", at(0), smallAccounts);
        accepted(tiny);
        smallState = tiny.state();
        smallAccounts = tiny.accounts();
        tiny = AgedInventoryModel.ship(smallState, "mgr", "C1", 3L, "S3", at(0), smallAccounts);
        accepted(tiny);
        smallState = tiny.state();
        smallAccounts = tiny.accounts();
        tiny = AgedInventoryModel.closePeriod(smallState, PERIOD, smallAccounts);
        accepted(tiny);
        smallState = tiny.state();
        smallAccounts = tiny.accounts();
        assertEquals(1L, smallState.spoilage().stream().mapToLong(item -> item.units()).sum(),
                "3 单位的 10% 向下取整为 1 个单位报损");
        BigDecimal prepaidBefore = smallState.shipmentLots().get(0).totalPrepaid();
        BigDecimal appliedBefore = smallState.shipmentLots().get(0).prepaidApplied();
        tiny = AgedInventoryModel.sellThrough(smallState, "C1", 2L, "T3", at(1), smallAccounts);
        accepted(tiny);
        smallState = tiny.state();
        smallAccounts = tiny.accounts();
        AgedInventoryModel.ShipmentLot sold = smallState.shipmentLots().get(0);
        // 已售抵扣 + 剩余未抵（最终转退款）= 预收总额，分币不丢
        BigDecimal appliedTotal = BigDecimal.ZERO.setScale(2);
        for (AgedInventoryModel.Sale sale : smallState.sales()) {
            appliedTotal = appliedTotal.add(sale.prepaidApplied());
        }
        assertEquals(prepaidBefore, appliedTotal.add(sold.unappliedPrepaid()),
                "预收在抵扣与剩余之间必须精确闭合");
        assertTrue(appliedBefore.signum() >= 0);
    }

    @Test
    @DisplayName("F2 分次退款：累计转退款 = 累计已退 + 剩余应付（40/20、缺钱部分退、已售抵扣不混）")
    void partialRefundsUseCumulativeIdentity() {
        AgedInventoryModel.Config config = config(100L, 0L, 1L, 0, 5000, 100L);
        Map<String, CashLedger.CashAccount> accounts = accounts("1000.00", "1000.00", "0.00",
                "1000.00");
        AgedInventoryModel.Outcome opened = AgedInventoryModel.open(config, accounts,
                List.of(new AgedInventoryModel.RawLot("R1", 10L, money("10.00"), money("10.00"), 0L,
                        10L)));
        accepted(opened);
        AgedInventoryModel.State state = opened.state();
        accounts = opened.accounts();
        AgedInventoryModel.Outcome step = AgedInventoryModel.produce(state, "mgr", 10L, "B1",
                at(0), accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.ship(state, "mgr", "C1", 10L, "S1", at(0), accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.sellThrough(state, "C1", 4L, "T1", at(0), accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        // 货架 1 期：period 1 届满 → 剩余 6 单位过期，未抵预收 30.00 转退款应付
        step = AgedInventoryModel.closePeriod(state, PERIOD, accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.closePeriod(state, 2L * PERIOD, accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        assertEquals(money("30.00"), state.channelLedgers().get(0).refundPayable());
        assertEquals(money("0.00"), state.channelLedgers().get(0).contractLiability());
        // 退 20：累计已退 20 + 剩余 10 == 累计转退 30
        step = AgedInventoryModel.payRefund(state, "mgr", "C1", money("20.00"),
                2L * PERIOD + 1L, accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        assertEquals(money("20.00"), state.channelLedgers().get(0).refundPaid());
        assertEquals(money("10.00"), state.channelLedgers().get(0).refundPayable());
        assertNull(AgedInventoryModel.validate(state, accounts), "分次退款不得被判 REFUND");
        // 再退 10（余款清零）
        step = AgedInventoryModel.payRefund(state, "mgr", "C1", money("10.00"),
                2L * PERIOD + 2L, accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        assertEquals(money("0.00"), state.channelLedgers().get(0).refundPayable());
        assertEquals(money("30.00"), state.channelLedgers().get(0).refundPaid());
        // 已售预收抵扣不得混进退款口径：预收 50 = 已抵 20 + 已退 30 + 剩余 CL 0
        BigDecimal appliedSum = BigDecimal.ZERO.setScale(2);
        for (AgedInventoryModel.Sale sale : state.sales()) {
            appliedSum = appliedSum.add(sale.prepaidApplied());
        }
        AgedInventoryModel.ChannelLedger ledger = state.channelLedgers().get(0);
        assertEquals(ledger.prepaidReceived(),
                appliedSum.add(ledger.refundPaid()).add(ledger.refundPayable())
                        .add(ledger.contractLiability()),
                "预收 = 已抵 + 已退 + 剩余应付 + 剩余合同负债");
        assertEquals(money("20.00"), appliedSum);
        assertNull(AgedInventoryModel.validate(state, accounts));
    }

    @Test
    @DisplayName("F3 每期产能是累计上限：同期限额内第二笔整笔拒，随后恰好补足；跨期重置")
    void capacityIsCumulativeWithinPeriod() {
        AgedInventoryModel.Config config = config(100L, 0L, 6L, 0, 0, 200L);
        Map<String, CashLedger.CashAccount> accounts = accounts("10000.00", "1000.00", "0.00",
                "1000.00");
        AgedInventoryModel.Outcome opened = AgedInventoryModel.open(config, accounts,
                List.of(new AgedInventoryModel.RawLot("R1", 300L, money("300.00"),
                        money("300.00"), 0L, 300L)));
        accepted(opened);
        AgedInventoryModel.State state = opened.state();
        accounts = opened.accounts();
        AgedInventoryModel.Outcome step = AgedInventoryModel.produce(state, "mgr", 60L, "B1",
                at(0), accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        AgedInventoryModel.Outcome second = AgedInventoryModel.produce(state, "mgr", 60L, "B2",
                at(0), accounts);
        assertFalse(second.accepted());
        assertEquals(AgedInventoryModel.CAPACITY_EXCEEDED, second.reason());
        assertEquals(1, second.state().batches().size(), "被拒不得留下批次");
        assertEquals(state.rawLots().get(0).remaining(), second.state().rawLots().get(0).remaining(),
                "被拒不得消耗原料");
        assertEquals(accounts.get("CO").cash(), second.accounts().get("CO").cash(),
                "被拒不得付转产费");
        // 恰好补足 40
        AgedInventoryModel.Outcome top = AgedInventoryModel.produce(second.state(), "mgr", 40L,
                "B3", at(0), second.accounts());
        accepted(top);
        assertEquals(2, top.state().batches().size());
        // 跨期重置：下一期再产 100 合法
        AgedInventoryModel.Outcome next = AgedInventoryModel.produce(top.state(), "mgr", 100L,
                "B4", at(1), top.accounts());
        accepted(next);
        assertEquals(100L, next.state().batches().get(2).units());
        assertNull(AgedInventoryModel.validate(next.state(), next.accounts()));
    }

    @Test
    @DisplayName("F4 自付自收配置开局即拒；两个外部收款共用账户仍允许")
    void openingRejectsSelfPayingAccounts() {
        AgedInventoryModel.Config base = config(100L, 0L, 6L, 0, 0, 100L);
        Map<String, CashLedger.CashAccount> accounts = accounts("1000.00", "1000.00", "0.00",
                "1000.00");
        AgedInventoryModel.Outcome ok = AgedInventoryModel.open(base, accounts, List.of());
        accepted(ok);
        // 公司 == 供应商：开局拒绝，而不是等到到货才 CASH_SHORT
        AgedInventoryModel.Config sameSupplier = new AgedInventoryModel.Config("cufe-company",
                "mgr", "CO", "CO", "CONV", "FIX", "TERM", money("1.00"), money("2.00"),
                money("0.00"), PERIOD, 100L, 0L, 6L, 0, 0,
                base.channels(), base.demand(), AgedInventoryModel.EXP_LABEL);
        assertEquals(AgedInventoryModel.COUNTERPARTY_INVALID,
                AgedInventoryModel.open(sameSupplier, accounts, List.of()).reason());
        // 渠道 == 终端：开局拒绝（否则终端付款腿会自己付自己）
        AgedInventoryModel.Config sameTerminal = new AgedInventoryModel.Config("cufe-company",
                "mgr", "CO", "SUP", "CONV", "FIX", "TERM", money("1.00"), money("2.00"),
                money("0.00"), PERIOD, 100L, 0L, 6L, 0, 0,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "TERM", 100L, money("10.00"),
                        money("5.00"))),
                base.demand(), AgedInventoryModel.EXP_LABEL);
        assertEquals(AgedInventoryModel.COUNTERPARTY_INVALID,
                AgedInventoryModel.open(sameTerminal, accounts, List.of()).reason());
        // 两个渠道共用同一外部账户：允许（每条腿两端仍不同），且开局总现金按账户去重
        AgedInventoryModel.Config sharedChannel = new AgedInventoryModel.Config("cufe-company",
                "mgr", "CO", "SUP", "CONV", "FIX", "TERM", money("1.00"), money("2.00"),
                money("0.00"), PERIOD, 100L, 0L, 6L, 0, 0,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", 100L, money("10.00"),
                                money("5.00")),
                        new AgedInventoryModel.ChannelSpec("C2", "CH1", 100L, money("10.00"),
                                money("5.00"))),
                base.demand(), AgedInventoryModel.EXP_LABEL);
        AgedInventoryModel.Outcome shared = AgedInventoryModel.open(sharedChannel, accounts,
                List.of());
        accepted(shared);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (CashLedger.CashAccount account : shared.accounts().values()) {
            total = total.add(account.cash());
        }
        assertEquals(total, shared.state().openingTotalCash(), "开局总现金按账户去重");
    }

    @Test
    @DisplayName("货架届满但未关期：sellThrough 拒绝（EXPIRED_LOT），不能靠不关期卖过期货")
    void sellThroughRejectsExpiredStockBeforeClose() {
        AgedInventoryModel.Config config = config(100L, 0L, 1L, 0, 0, 100L);
        Map<String, CashLedger.CashAccount> accounts = accounts("1000.00", "1000.00", "0.00",
                "1000.00");
        AgedInventoryModel.Outcome opened = AgedInventoryModel.open(config, accounts,
                List.of(new AgedInventoryModel.RawLot("R1", 10L, money("10.00"), money("10.00"), 0L,
                        10L)));
        accepted(opened);
        AgedInventoryModel.State state = opened.state();
        accounts = opened.accounts();
        AgedInventoryModel.Outcome step = AgedInventoryModel.produce(state, "mgr", 10L, "B1",
                at(0), accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.ship(state, "mgr", "C1", 10L, "S1", at(0), accounts);
        accepted(step);
        state = step.state();
        accounts = step.accounts();
        // 不关期，直接把世界时间推到货架届满之后（period 1，matureAt 0 + shelf 1）
        AgedInventoryModel.Outcome expired = AgedInventoryModel.sellThrough(state, "C1", 1L, "T9",
                at(1), accounts);
        assertFalse(expired.accepted(), "过期库存未关期也不得成交");
        assertEquals(AgedInventoryModel.EXPIRED_LOT, expired.reason());
        assertEquals(0, expired.state().sales().size());
        assertNull(AgedInventoryModel.validate(state, accounts));
    }
}
