package edu.cufe.auction.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 陈酿纯核（EXP-AGED-2）真实行为：只验纯函数与真实 {@link CashLedger} 账户表，
 * 不接 World/Web、不联网、不读 keys。所有数字都是 EXP 参数下的内部一致性，不是现实校准。
 */
class AgedInventoryModelTest {

    private static final long PERIOD = 30L * 24L * 3_600_000L;
    private static final long DAY = 86_400_000L;

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static Map<String, CashLedger.CashAccount> accounts(String companyCash, String ch1,
                                                               String ch2, String terminal) {
        Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
        map.put("CO", new CashLedger.CashAccount("company", money(companyCash), "EXP-AGED-2 开局"));
        map.put("SUP", new CashLedger.CashAccount("supplier", money("0.00"), "EXP-AGED-2 开局"));
        map.put("CONV", new CashLedger.CashAccount("conversion", money("0.00"), "EXP-AGED-2 开局"));
        map.put("FIX", new CashLedger.CashAccount("fixed", money("0.00"), "EXP-AGED-2 开局"));
        map.put("TERM", new CashLedger.CashAccount("terminal", money(terminal), "EXP-AGED-2 开局"));
        map.put("CH1", new CashLedger.CashAccount("channel-1", money(ch1), "EXP-AGED-2 开局"));
        map.put("CH2", new CashLedger.CashAccount("channel-2", money(ch2), "EXP-AGED-2 开局"));
        return map;
    }

    private static AgedInventoryModel.Config config() {
        return new AgedInventoryModel.Config("cufe-company", "manager-1", "CO", "SUP", "CONV",
                "FIX", "TERM", money("2.00"), money("3.00"), money("1500.00"), PERIOD,
                1000L, 2L, 2L, 200, 5000,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", 300L, money("20.00"),
                                money("18.00")),
                        new AgedInventoryModel.ChannelSpec("C2", "CH2", 200L, money("22.00"),
                                money("20.00"))),
                List.of(new AgedInventoryModel.DemandQuote(2L, "C1", 300L),
                        new AgedInventoryModel.DemandQuote(2L, "C2", 200L)),
                AgedInventoryModel.EXP_LABEL);
    }

    private static AgedInventoryModel.RawLot openingRaw() {
        return new AgedInventoryModel.RawLot("OPEN-RAW", 500L, money("1000.00"), money("1000.00"),
                -1L, 500L);
    }

    private static long at(long period) {
        return period * PERIOD + 1L;
    }

    /** 取接受结果，顺带断言 reason 与状态推进。 */
    private static <T> T ok(AgedInventoryModel.Outcome outcome) {
        assertTrue(outcome.accepted(), () -> "期望接受，实际 " + outcome.reason());
        assertEquals(AgedInventoryModel.ACCEPTED, outcome.reason());
        return null;
    }

    @Test
    @DisplayName("三个期间基线：数量/成本/现金/AR-CL/平衡表全部闭合，且利润为负")
    void baselineClosesAllIdentities() {
        Map<String, CashLedger.CashAccount> accounts = accounts("12000.00", "5000.00", "5000.00",
                "30000.00");
        AgedInventoryModel.Outcome opened = AgedInventoryModel.open(config(), accounts,
                List.of(openingRaw()));
        ok(opened);
        AgedInventoryModel.State state = opened.state();
        accounts = opened.accounts();

        AgedInventoryModel.Outcome step = AgedInventoryModel.orderRaw(state, "manager-1", 1500L,
                "O1", at(0), accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.produce(state, "manager-1", 500L, "B1", at(0), accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.closePeriod(state, PERIOD, accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();

        step = AgedInventoryModel.receiveOrder(state, "manager-1", "O1", at(1), accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.produce(state, "manager-1", 900L, "B2", at(1), accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.closePeriod(state, 2L * PERIOD, accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();

        step = AgedInventoryModel.produce(state, "manager-1", 600L, "B3", at(2), accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.ship(state, "manager-1", "C1", 300L, "S1", at(2), accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.ship(state, "manager-1", "C2", 100L, "S2", at(2), accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.sellThrough(state, "C1", 250L, "T1", at(2), accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.sellThrough(state, "C2", 40L, "T2", at(2), accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();
        step = AgedInventoryModel.closePeriod(state, 3L * PERIOD, accounts);
        ok(step);
        state = step.state();
        accounts = step.accounts();

        assertNull(AgedInventoryModel.validate(state, accounts), "候选必须通过全部不变量");
        AgedInventoryModel.Books books = AgedInventoryModel.books(state, accounts);
        assertEquals(money("5880.00"), books.revenue());
        assertEquals(money("1450.00"), books.cogs());
        assertEquals(money("20.00"), books.spoilageLoss());
        assertEquals(money("4500.00"), books.fixedAccrued());
        assertEquals(money("-90.00"), books.profit());
        assertEquals(money("2600.00"), books.companyCash());
        assertEquals(money("8530.00"), books.inventoryCost());
        assertEquals(money("2940.00"), books.receivable());
        assertEquals(money("1160.00"), books.contractLiability());
        assertEquals(money("1780.00"), books.netReceivableMemo());
        assertEquals(money("14070.00"), books.assets());
        assertEquals(money("1160.00"), books.liabilities());
        assertEquals(money("12910.00"), books.equity());
        assertEquals(money("52000.00"), books.totalCash(), "全主体现金守恒");
        // gross 分渠道：C1 预收 3000→抵 2500、AR 2500/CL 500；C2 预收 1100→抵 440、AR 440/CL 660
        assertEquals(money("2500.00"), state.channelLedgers().get(0).receivable());
        assertEquals(money("500.00"), state.channelLedgers().get(0).contractLiability());
        assertEquals(money("440.00"), state.channelLedgers().get(1).receivable());
        assertEquals(money("660.00"), state.channelLedgers().get(1).contractLiability());
        System.out.println("SUMMARY revenue=" + books.revenue().toPlainString()
                + " cogs=" + books.cogs().toPlainString()
                + " spoilage=" + books.spoilageLoss().toPlainString()
                + " fixed=" + books.fixedAccrued().toPlainString()
                + " profit=" + books.profit().toPlainString()
                + " cash=" + books.companyCash().toPlainString()
                + " inventory=" + books.inventoryCost().toPlainString()
                + " ar=" + books.receivable().toPlainString()
                + " cl=" + books.contractLiability().toPlainString()
                + " net=" + books.netReceivableMemo().toPlainString()
                + " assets=" + books.assets().toPlainString()
                + " liabilities=" + books.liabilities().toPlainString()
                + " equity=" + books.equity().toPlainString()
                + " totalCash=" + books.totalCash().toPlainString());
    }

    @Test
    @DisplayName("多 lot FIFO 与分币尾差：预收 30.02 分三次卖出按 10.01/10.00/10.01 分摊")
    void fifoAndCentRemainderOnPrepaid() {
        AgedInventoryModel.Config config = new AgedInventoryModel.Config("cufe-company",
                "manager-1", "CO", "SUP", "CONV", "FIX", "TERM", money("0.50"), money("3.00"),
                money("0.00"), PERIOD, 10L, 0L, 6L, 0, 5000,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", 10L, money("20.01"),
                        money("18.00"))),
                List.of(new AgedInventoryModel.DemandQuote(0L, "C1", 10L)),
                AgedInventoryModel.EXP_LABEL);
        Map<String, CashLedger.CashAccount> accounts = accounts("100.00", "100.00", "0.00",
                "100.00");
        AgedInventoryModel.Outcome outcome = AgedInventoryModel.open(config, accounts,
                List.of(new AgedInventoryModel.RawLot("R1", 10L, money("5.00"), money("5.00"), 0L,
                        10L)));
        ok(outcome);
        AgedInventoryModel.State state = outcome.state();
        accounts = outcome.accounts();
        outcome = AgedInventoryModel.produce(state, "manager-1", 3L, "B1", at(0), accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        // 3 × 20.01 = 60.03；预收 50% = 30.015 → 30.02（分币进位后不可整除）
        outcome = AgedInventoryModel.ship(state, "manager-1", "C1", 3L, "S1", at(0), accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        AgedInventoryModel.ShipmentLot lot = state.shipmentLots().get(0);
        assertEquals(money("30.02"), lot.totalPrepaid());
        List<String> applied = new java.util.ArrayList<>();
        for (int index = 0; index < 3; index++) {
            outcome = AgedInventoryModel.sellThrough(state, "C1", 1L, "T" + index, at(0), accounts);
            ok(outcome);
            state = outcome.state();
            accounts = outcome.accounts();
            applied.add(state.sales().get(index).prepaidApplied().toPlainString());
        }
        assertEquals(List.of("10.01", "10.00", "10.01"), applied);
        AgedInventoryModel.Books books = AgedInventoryModel.books(state, accounts);
        assertEquals(money("60.03"), books.revenue());
        assertEquals(money("30.01"), books.receivable(), "收入 60.03 − 抵扣 30.02");
        assertEquals(money("0.00"), books.contractLiability(), "预收必须精确抵完，不留尾差");
        assertNull(AgedInventoryModel.validate(state, accounts));
    }

    @Test
    @DisplayName("发货价冻结：改价只影响新发货，旧 lot 成交仍按原价")
    void setPriceDoesNotRewriteHistory() {
        AgedInventoryModel.Config config = new AgedInventoryModel.Config("cufe-company",
                "manager-1", "CO", "SUP", "CONV", "FIX", "TERM", money("1.00"), money("1.00"),
                money("0.00"), PERIOD, 100L, 0L, 6L, 0, 0,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", 100L, money("20.00"),
                        money("18.00"))),
                List.of(new AgedInventoryModel.DemandQuote(0L, "C1", 100L)),
                AgedInventoryModel.EXP_LABEL);
        Map<String, CashLedger.CashAccount> accounts = accounts("100.00", "100.00", "0.00",
                "1000.00");
        AgedInventoryModel.Outcome outcome = AgedInventoryModel.open(config, accounts,
                List.of(new AgedInventoryModel.RawLot("R1", 10L, money("10.00"), money("10.00"), 0L,
                        10L)));
        ok(outcome);
        AgedInventoryModel.State state = outcome.state();
        accounts = outcome.accounts();
        outcome = AgedInventoryModel.produce(state, "manager-1", 10L, "B1", at(0), accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        outcome = AgedInventoryModel.ship(state, "manager-1", "C1", 3L, "S1", at(0), accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        outcome = AgedInventoryModel.setPrice(state, "manager-1", "C1", money("22.00"), at(0),
                accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        outcome = AgedInventoryModel.sellThrough(state, "C1", 3L, "T1", at(0), accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        assertEquals(money("60.00"), AgedInventoryModel.books(state, accounts).revenue(),
                "旧 lot 必须按发货价 20.00 成交，不得被 22.00 回写");
        outcome = AgedInventoryModel.ship(state, "manager-1", "C1", 2L, "S2", at(0), accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        assertEquals(money("22.00"), state.shipmentLots().get(1).consignmentPrice());
        outcome = AgedInventoryModel.sellThrough(state, "C1", 2L, "T2", at(0), accounts);
        ok(outcome);
        assertEquals(money("104.00"), AgedInventoryModel.books(outcome.state(),
                outcome.accounts()).revenue(), "新发货按 22.00：60 + 44");
    }

    @Test
    @DisplayName("渠道现金不足/终端现金不足：整笔拒绝、不占需求、不产生幽灵现金")
    void cashShortRejectsWholeRequestWithoutConsumingDemand() {
        Map<String, CashLedger.CashAccount> accounts = accounts("12000.00", "1000.00", "5000.00",
                "500.00");
        AgedInventoryModel.Outcome outcome = AgedInventoryModel.open(config(), accounts,
                List.of(openingRaw()));
        ok(outcome);
        AgedInventoryModel.State state = outcome.state();
        accounts = outcome.accounts();
        outcome = AgedInventoryModel.produce(state, "manager-1", 500L, "B1", at(0), accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        // 渠道 C1 只有 1000，应预收 3000 → 整笔拒绝，不建寄售 lot、不动现金
        AgedInventoryModel.Outcome short1 = AgedInventoryModel.ship(state, "manager-1", "C1", 300L,
                "X1", at(2), accounts);
        assertFalse(short1.accepted());
        assertEquals(AgedInventoryModel.CHANNEL_CASH_SHORT, short1.reason());
        assertTrue(short1.state().shipmentLots().isEmpty());
        assertEquals(state.batches().get(0).remaining(), short1.state().batches().get(0).remaining());
        // 成熟期未到（period 0 发 period-2 批次）→ NO_CHANNEL_STOCK，同样整笔拒绝
        AgedInventoryModel.Outcome notMature = AgedInventoryModel.ship(state, "manager-1", "C2", 1L,
                "X2", at(0), accounts);
        assertFalse(notMature.accepted());
        assertEquals(AgedInventoryModel.NO_CHANNEL_STOCK, notMature.reason());
        // 终端现金 500 < 成交 1000 → 整笔拒绝，需求额度不被消耗：随后合法的 20 单位仍可成交
        outcome = AgedInventoryModel.ship(state, "manager-1", "C2", 50L, "S1", at(2), accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        AgedInventoryModel.Outcome short2 = AgedInventoryModel.sellThrough(state, "C2", 50L, "T1",
                at(2), accounts);
        assertFalse(short2.accepted());
        assertEquals(AgedInventoryModel.TERMINAL_CASH_SHORT, short2.reason());
        assertEquals(0, short2.state().sales().size(), "拒绝不得留下成交");
        AgedInventoryModel.Outcome small = AgedInventoryModel.sellThrough(state, "C2", 20L, "T2",
                at(2), accounts);
        assertTrue(small.accepted(), () -> "需求额度不得被拒单消耗：" + small.reason());
        assertEquals(money("440.00"), AgedInventoryModel.books(small.state(), small.accounts())
                .revenue());
    }

    @Test
    @DisplayName("固定费现金不足：实付=可用现金，余款留应付，不得负现金、不回滚")
    void fixedFeeBecomesPayable() {
        AgedInventoryModel.Config config = new AgedInventoryModel.Config("cufe-company",
                "manager-1", "CO", "SUP", "CONV", "FIX", "TERM", money("2.00"), money("3.00"),
                money("1500.00"), PERIOD, 10L, 0L, 6L, 0, 0,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", 10L, money("20.00"),
                        money("18.00"))), List.of(), AgedInventoryModel.EXP_LABEL);
        Map<String, CashLedger.CashAccount> accounts = accounts("500.00", "100.00", "0.00",
                "0.00");
        AgedInventoryModel.Outcome outcome = AgedInventoryModel.open(config, accounts, List.of());
        ok(outcome);
        outcome = AgedInventoryModel.closePeriod(outcome.state(), PERIOD, outcome.accounts());
        ok(outcome);
        assertEquals(money("0.00"), outcome.accounts().get("CO").cash(), "不得负现金");
        assertEquals(money("500.00"), outcome.accounts().get("FIX").cash());
        assertEquals(money("1500.00"), outcome.state().fixedAccrued());
        assertEquals(money("1000.00"), outcome.state().fixedPayable());
        assertEquals(money("-1500.00"), AgedInventoryModel.books(outcome.state(),
                outcome.accounts()).profit(), "应计全额进损益");
        assertNull(AgedInventoryModel.validate(outcome.state(), outcome.accounts()));
        // 余款仍是真实负债，不会因现金不足消失；后续用真实流入（回款/退款前）才有钱付，
        // 本测试只确认负债保留与现金不为负。
        assertEquals(money("0.00"), outcome.accounts().get("CO").cash());
    }

    @Test
    @DisplayName("寄售过期：全额报废、未抵预收转退款应付、AR 不消失；退款可分次")
    void expiredConsignmentBecomesRefundableDebt() {
        AgedInventoryModel.Config config = new AgedInventoryModel.Config("cufe-company",
                "manager-1", "CO", "SUP", "CONV", "FIX", "TERM", money("1.00"), money("1.00"),
                money("0.00"), PERIOD, 100L, 0L, 2L, 0, 5000,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", 100L, money("20.00"),
                        money("18.00"))),
                List.of(new AgedInventoryModel.DemandQuote(0L, "C1", 100L)),
                AgedInventoryModel.EXP_LABEL);
        Map<String, CashLedger.CashAccount> accounts = accounts("1000.00", "1000.00", "0.00",
                "1000.00");
        AgedInventoryModel.Outcome outcome = AgedInventoryModel.open(config, accounts,
                List.of(new AgedInventoryModel.RawLot("R1", 10L, money("10.00"), money("10.00"), 0L,
                        10L)));
        ok(outcome);
        AgedInventoryModel.State state = outcome.state();
        accounts = outcome.accounts();
        outcome = AgedInventoryModel.produce(state, "manager-1", 10L, "B1", at(0), accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        outcome = AgedInventoryModel.ship(state, "manager-1", "C1", 10L, "S1", at(0), accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        outcome = AgedInventoryModel.sellThrough(state, "C1", 4L, "T1", at(0), accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        // 货架期 2 期：period 2 届满 → 剩余 6 单位全额 EXPIRED
        outcome = AgedInventoryModel.closePeriod(state, PERIOD, accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        outcome = AgedInventoryModel.closePeriod(state, 2L * PERIOD, accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        outcome = AgedInventoryModel.closePeriod(state, 3L * PERIOD, accounts);
        ok(outcome);
        state = outcome.state();
        accounts = outcome.accounts();
        AgedInventoryModel.Books books = AgedInventoryModel.books(state, accounts);
        assertEquals(money("0.00"), books.inventoryCost(), "过期后不得留寄售存货");
        assertTrue(state.spoilage().stream().anyMatch(item -> "EXPIRED".equals(item.reason())));
        // 已成交 4 单位：收入 80、AR 80；未售 6 单位预收 60 转退款应付
        assertEquals(money("80.00"), books.revenue());
        assertEquals(money("40.00"), books.receivable(), "收入 80 − 已抵预收 40；已确认 AR 不因剩余货物坏掉消失");
        assertEquals(money("0.00"), books.contractLiability());
        assertEquals(money("60.00"), books.refundPayable());
        assertNull(AgedInventoryModel.validate(state, accounts));
        // 退款：公司现金足够则真实现金退给渠道
        AgedInventoryModel.Outcome refund = AgedInventoryModel.payRefund(state, "manager-1", "C1",
                money("60.00"), 3L * PERIOD + DAY, accounts);
        ok(refund);
        assertEquals(money("0.00"), AgedInventoryModel.books(refund.state(), refund.accounts())
                .refundPayable());
        assertEquals(money("0.00"), AgedInventoryModel.books(refund.state(), refund.accounts())
                .liabilities());
        assertEquals(AgedInventoryModel.books(refund.state(),
                refund.accounts()).totalCash(), state.openingTotalCash(),
                "退款只是账户间转移，总额守恒");
    }

    @Test
    @DisplayName("产能/原料/现金/权限/关期：拒绝都不改变状态，也不产生半成品")
    void rejectionsLeaveStateUntouched() {
        Map<String, CashLedger.CashAccount> accounts = accounts("12000.00", "5000.00", "5000.00",
                "30000.00");
        AgedInventoryModel.Outcome opened = AgedInventoryModel.open(config(), accounts,
                List.of(openingRaw()));
        ok(opened);
        AgedInventoryModel.State state = opened.state();
        accounts = opened.accounts();
        AgedInventoryModel.Outcome tooBig = AgedInventoryModel.produce(state, "manager-1", 1200L,
                "B9", at(0), accounts);
        assertEquals(AgedInventoryModel.CAPACITY_EXCEEDED, tooBig.reason());
        assertEquals(0, tooBig.state().batches().size());
        assertEquals(state.rawLots().get(0).remaining(), tooBig.state().rawLots().get(0).remaining());
        AgedInventoryModel.Outcome noMaterial = AgedInventoryModel.produce(state, "manager-1", 900L,
                "B8", at(0), accounts);
        assertEquals(AgedInventoryModel.MATERIAL_SHORT, noMaterial.reason());
        assertEquals(0, noMaterial.state().batches().size());
        AgedInventoryModel.Outcome otherActor = AgedInventoryModel.produce(state, "someone-else",
                10L, "B7", at(0), accounts);
        assertEquals(AgedInventoryModel.NOT_AUTHORIZED, otherActor.reason());
        AgedInventoryModel.Outcome early = AgedInventoryModel.closePeriod(state, PERIOD - 1L,
                accounts);
        assertEquals(AgedInventoryModel.PERIOD_NOT_ENDED, early.reason());
        AgedInventoryModel.Outcome bound = AgedInventoryModel.orderRaw(state, "manager-1", 1500L,
                "O1", at(0), accounts);
        ok(bound);
        AgedInventoryModel.Outcome firstClose = AgedInventoryModel.closePeriod(bound.state(),
                PERIOD, bound.accounts());
        ok(firstClose);
        AgedInventoryModel.Outcome unsettled = AgedInventoryModel.closePeriod(firstClose.state(),
                2L * PERIOD, firstClose.accounts());
        assertEquals(AgedInventoryModel.UNSETTLED_ACTIONS, unsettled.reason(),
                "到期未结算的采购不得被关期跳过");
        AgedInventoryModel.Outcome reuse = AgedInventoryModel.orderRaw(bound.state(), "manager-1",
                10L, "O1", at(0), bound.accounts());
        assertEquals(AgedInventoryModel.ID_REUSED, reuse.reason());
        assertNull(AgedInventoryModel.validate(bound.state(), bound.accounts()));
        assertNotNull(state);
    }
}
