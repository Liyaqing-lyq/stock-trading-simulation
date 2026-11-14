package edu.cufe.auction.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 独审探针：只测作者 7 测之外的边界。纯模型 + 真实 CashLedger；0 网络。
 *
 * <p>重点：报损后再发货的累计成本、每期累计产能、过期退款债务与每前缀 A=L+E/现金守恒。</p>
 */
class AgedInventoryIndependentProbeTest {

    private static final long PERIOD = 30L * 24L * 3_600_000L;

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static long at(long period) {
        return period * PERIOD + 1L;
    }

    private static Map<String, CashLedger.CashAccount> accounts(String company, String ch1,
                                                               String terminal) {
        Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
        map.put("CO", new CashLedger.CashAccount("company", money(company), "EXP-AGED-2 probe"));
        map.put("SUP", new CashLedger.CashAccount("supplier", money("0.00"), "EXP-AGED-2 probe"));
        map.put("CONV", new CashLedger.CashAccount("conversion", money("0.00"), "EXP-AGED-2 probe"));
        map.put("FIX", new CashLedger.CashAccount("fixed", money("0.00"), "EXP-AGED-2 probe"));
        map.put("TERM", new CashLedger.CashAccount("terminal", money(terminal), "EXP-AGED-2 probe"));
        map.put("CH1", new CashLedger.CashAccount("channel-1", money(ch1), "EXP-AGED-2 probe"));
        return map;
    }

    private static AgedInventoryModel.Config config(long capacity, long aging, long shelf,
                                                    int spoilageBp, int prepayBp,
                                                    String fixedPerPeriod) {
        return new AgedInventoryModel.Config("cufe-company", "mgr", "CO", "SUP", "CONV", "FIX",
                "TERM", money("2.00"), money("1.00"), money(fixedPerPeriod), PERIOD, capacity,
                aging, shelf, spoilageBp, prepayBp,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", 1000L,
                        money("20.00"), money("18.00"))),
                List.of(new AgedInventoryModel.DemandQuote(0L, "C1", 1000L),
                        new AgedInventoryModel.DemandQuote(1L, "C1", 1000L),
                        new AgedInventoryModel.DemandQuote(2L, "C1", 1000L)),
                AgedInventoryModel.EXP_LABEL);
    }

    private static AgedInventoryModel.Outcome accepted(AgedInventoryModel.Outcome outcome) {
        assertTrue(outcome.accepted(), () -> "期望接受，实际 " + outcome.reason());
        return outcome;
    }

    private static AgedInventoryModel.State open(AgedInventoryModel.Config config,
                                                 Map<String, CashLedger.CashAccount> accounts,
                                                 long rawUnits, String rawCost) {
        AgedInventoryModel.Outcome opened = AgedInventoryModel.open(config, accounts,
                List.of(new AgedInventoryModel.RawLot("R0", rawUnits, money(rawCost),
                        money(rawCost), -1L, rawUnits)));
        accepted(opened);
        return opened.state();
    }

    @Test
    @DisplayName("报损后再发货按“已发”而非“非剩余”累计：成本不得被多摊")
    void shipAfterSpoilageKeepsCumulativeCost() {
        Map<String, CashLedger.CashAccount> accounts = accounts("1000.00", "1000.00", "1000.00");
        AgedInventoryModel.Config config = config(100L, 1L, 5L, 1000, 0, "0.00");
        AgedInventoryModel.State state = open(config, accounts, 100L, "200.00");
        AgedInventoryModel.Outcome produced = AgedInventoryModel.produce(state, "mgr", 100L, "B1",
                at(0), accounts);
        accepted(produced);
        state = produced.state();
        accounts = produced.accounts();
        // P0 close：未成熟，不报损。
        AgedInventoryModel.Outcome closed0 = AgedInventoryModel.closePeriod(state, PERIOD, accounts);
        accepted(closed0);
        state = closed0.state();
        accounts = closed0.accounts();
        // P1 close：成熟且 10% 报损（10 单位，成本 30.00）。
        AgedInventoryModel.Outcome closed1 = AgedInventoryModel.closePeriod(state, 2L * PERIOD,
                accounts);
        accepted(closed1);
        state = closed1.state();
        accounts = closed1.accounts();
        AgedInventoryModel.BatchLot afterSpoil = state.batches().get(0);
        assertEquals(10L, afterSpoil.spoiled());
        assertEquals(money("30.00"), afterSpoil.costSpoiled());

        // P2 再发 10 单位：累计已发 10，应结转 C(10)=30.00；当前实现按 units-remaining=20 累计。
        AgedInventoryModel.Outcome ship = AgedInventoryModel.ship(state, "mgr", "C1", 10L, "S1",
                at(2), accounts);
        System.out.println("[ship-after-spoil] reason=" + ship.reason() + " books="
                + AgedInventoryModel.books(ship.state(), ship.accounts())
                + " validate=" + AgedInventoryModel.validate(ship.state(), ship.accounts()));
        accepted(ship);
        AgedInventoryModel.BatchLot afterShip = ship.state().batches().get(0);
        assertEquals(money("30.00"), afterShip.costOut(),
                "发货 10 的累计成本应为 C(10)=30.00；实际 " + afterShip.costOut());
        assertEquals(money("240.00"), afterShip.remainingCost(),
                "剩余 80 单位成本应为 300−C(20)=240.00；实际 " + afterShip.remainingCost());
    }

    @Test
    @DisplayName("报损后把剩余全部发出不应抛异常（成本分摊必须封顶）")
    void shipAllRemainingAfterSpoilageDoesNotThrow() {
        Map<String, CashLedger.CashAccount> accounts = accounts("1000.00", "1000.00", "1000.00");
        AgedInventoryModel.Config config = config(100L, 1L, 5L, 1000, 0, "0.00");
        AgedInventoryModel.State state = open(config, accounts, 100L, "200.00");
        AgedInventoryModel.Outcome produced = AgedInventoryModel.produce(state, "mgr", 100L, "B1",
                at(0), accounts);
        accepted(produced);
        state = produced.state();
        accounts = produced.accounts();
        for (long p = 0; p < 2; p++) {
            AgedInventoryModel.Outcome closed = AgedInventoryModel.closePeriod(state,
                    (p + 1) * PERIOD, accounts);
            accepted(closed);
            state = closed.state();
            accounts = closed.accounts();
        }
        // 报损 10，剩余 90；全部发出应结转 270，不超出总成本 300。
        AgedInventoryModel.Outcome ship;
        try {
            ship = AgedInventoryModel.ship(state, "mgr", "C1", 90L, "S1", at(2), accounts);
        } catch (RuntimeException failure) {
            fail("报损后全量发货抛异常：" + failure);
            return;
        }
        accepted(ship);
        AgedInventoryModel.BatchLot batch = ship.state().batches().get(0);
        assertEquals(money("270.00"), batch.costOut(),
                "全量发出 90 的累计成本应为 C(90)−C(10)=270.00；实际 " + batch.costOut());
        assertEquals(0L, batch.remaining());
    }

    @Test
    @DisplayName("每期产能是累计上限：同期间第二笔超出剩余产能应整笔拒绝")
    void capacityPerPeriodIsCumulative() {
        Map<String, CashLedger.CashAccount> accounts = accounts("1000.00", "1000.00", "1000.00");
        AgedInventoryModel.Config config = config(100L, 0L, 3L, 0, 0, "0.00");
        AgedInventoryModel.State state = open(config, accounts, 200L, "400.00");
        AgedInventoryModel.Outcome first = AgedInventoryModel.produce(state, "mgr", 60L, "B1",
                at(0), accounts);
        accepted(first);
        state = first.state();
        accounts = first.accounts();
        AgedInventoryModel.Outcome second = AgedInventoryModel.produce(state, "mgr", 60L, "B2",
                at(0), accounts);
        assertFalse(second.accepted(),
                "同一期已产 60/100，再产 60 超过每期累计产能，应整笔拒绝；实际 "
                        + second.reason());
        assertEquals(AgedInventoryModel.CAPACITY_EXCEEDED, second.reason());
        assertEquals(state, second.state(), "拒绝不得改变状态");
        assertEquals(accounts, second.accounts(), "拒绝不得改变账户");
    }

    @Test
    @DisplayName("每个前缀都满足 A=L+E、全主体现金守恒、开局权益不重置")
    void prefixInvariantsHold() {
        Map<String, CashLedger.CashAccount> accounts = accounts("12000.00", "5000.00", "30000.00");
        accounts.put("CH2", new CashLedger.CashAccount("channel-2", money("5000.00"), "probe"));
        AgedInventoryModel.Config config = new AgedInventoryModel.Config("cufe-company", "mgr",
                "CO", "SUP", "CONV", "FIX", "TERM", money("2.00"), money("3.00"),
                money("1500.00"), PERIOD, 1000L, 2L, 2L, 200, 5000,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", 300L, money("20.00"),
                                money("18.00")),
                        new AgedInventoryModel.ChannelSpec("C2", "CH2", 200L, money("22.00"),
                                money("20.00"))),
                List.of(new AgedInventoryModel.DemandQuote(2L, "C1", 300L),
                        new AgedInventoryModel.DemandQuote(2L, "C2", 200L)),
                AgedInventoryModel.EXP_LABEL);
        AgedInventoryModel.Outcome opened = AgedInventoryModel.open(config, accounts,
                List.of(new AgedInventoryModel.RawLot("OPEN", 500L, money("1000.00"),
                        money("1000.00"), -1L, 500L)));
        accepted(opened);
        AgedInventoryModel.State state = opened.state();
        accounts = opened.accounts();
        BigDecimal openingCash = state.openingTotalCash();
        BigDecimal openingEquity = state.openingEquity();
        assertPrefix(state, accounts, openingCash, openingEquity);

        state = step(AgedInventoryModel.orderRaw(state, "mgr", 1500L, "O1", at(0), accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);
        state = step(AgedInventoryModel.produce(state, "mgr", 500L, "B1", at(0), accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);
        state = step(AgedInventoryModel.closePeriod(state, PERIOD, accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);
        state = step(AgedInventoryModel.receiveOrder(state, "mgr", "O1", at(1), accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);
        state = step(AgedInventoryModel.produce(state, "mgr", 900L, "B2", at(1), accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);
        state = step(AgedInventoryModel.closePeriod(state, 2L * PERIOD, accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);
        state = step(AgedInventoryModel.produce(state, "mgr", 600L, "B3", at(2), accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);
        state = step(AgedInventoryModel.ship(state, "mgr", "C1", 300L, "S1", at(2), accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);
        state = step(AgedInventoryModel.ship(state, "mgr", "C2", 100L, "S2", at(2), accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);
        state = step(AgedInventoryModel.sellThrough(state, "C1", 250L, "T1", at(2), accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);
        state = step(AgedInventoryModel.sellThrough(state, "C2", 40L, "T2", at(2), accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);
        state = step(AgedInventoryModel.closePeriod(state, 3L * PERIOD, accounts));
        accounts = stepAccounts;
        assertPrefix(state, accounts, openingCash, openingEquity);

        AgedInventoryModel.Books books = AgedInventoryModel.books(state, accounts);
        assertEquals(0, books.assets().subtract(books.liabilities()).compareTo(books.equity()),
                "A−L 必须等于 E");
        assertEquals(0, books.totalCash().compareTo(openingCash), "全主体现金必须守恒");
        assertEquals(0, state.openingEquity().compareTo(openingEquity), "开局权益不得重置");
    }

    private Map<String, CashLedger.CashAccount> stepAccounts;

    private AgedInventoryModel.State step(AgedInventoryModel.Outcome outcome) {
        accepted(outcome);
        stepAccounts = outcome.accounts();
        String violation = AgedInventoryModel.validate(outcome.state(), outcome.accounts());
        assertNull(violation, "前缀不变量失败：" + violation);
        return outcome.state();
    }

    private static void assertPrefix(AgedInventoryModel.State state,
            Map<String, CashLedger.CashAccount> accounts, BigDecimal openingCash,
            BigDecimal openingEquity) {
        String violation = AgedInventoryModel.validate(state, accounts);
        assertNull(violation, "前缀不变量失败：" + violation);
        AgedInventoryModel.Books books = AgedInventoryModel.books(state, accounts);
        assertEquals(0, books.assets().subtract(books.liabilities()).compareTo(books.equity()),
                "前缀 A−L 必须等于 E");
        assertEquals(0, books.totalCash().compareTo(openingCash), "前缀现金必须守恒");
        assertTrue(books.companyCash().signum() >= 0, "公司现金不得为负");
        assertEquals(0, state.openingEquity().compareTo(openingEquity), "开局权益不得重置");
    }


    @Test
    @DisplayName("已售 AR 与未售 CL 分开：过期只把剩余未抵预收转退款应付，AR 不消失")
    void expiryRefundKeepsDebt() {
        Map<String, CashLedger.CashAccount> accounts = accounts("1000.00", "100.00", "1000.00");
        AgedInventoryModel.Config config = config(100L, 0L, 1L, 0, 5000, "0.00");
        AgedInventoryModel.State state = open(config, accounts, 10L, "20.00");
        AgedInventoryModel.Outcome produced = AgedInventoryModel.produce(state, "mgr", 10L, "B1",
                at(0), accounts);
        accepted(produced);
        state = produced.state();
        accounts = produced.accounts();
        AgedInventoryModel.Outcome ship = AgedInventoryModel.ship(state, "mgr", "C1", 10L, "S1",
                at(0), accounts);
        accepted(ship);
        state = ship.state();
        accounts = ship.accounts();
        assertEquals(money("0.00"), state.channelLedgers().get(0).receivable(), "未售无 AR");
        assertEquals(money("100.00"), state.channelLedgers().get(0).contractLiability());
        // 售出 4：收入 80，抵预收 40 → AR 40、CL 60。
        AgedInventoryModel.Outcome sale = AgedInventoryModel.sellThrough(state, "C1", 4L, "T1",
                at(0), accounts);
        accepted(sale);
        state = sale.state();
        accounts = sale.accounts();
        assertEquals(money("40.00"), state.channelLedgers().get(0).receivable());
        assertEquals(money("60.00"), state.channelLedgers().get(0).contractLiability());
        // P0 close（未过期）。
        AgedInventoryModel.Outcome closed0 = AgedInventoryModel.closePeriod(state, PERIOD, accounts);
        accepted(closed0);
        state = closed0.state();
        accounts = closed0.accounts();
        // P1 close：货架届满，剩余 6 全额过期；未抵预收 60 转退款应付。
        AgedInventoryModel.Outcome closed1 = AgedInventoryModel.closePeriod(state, 2L * PERIOD,
                accounts);
        accepted(closed1);
        state = closed1.state();
        accounts = closed1.accounts();
        AgedInventoryModel.Books books = AgedInventoryModel.books(state, accounts);
        assertEquals(money("40.00"), books.receivable(), "已确认 AR 不因剩余货物坏掉消失");
        assertEquals(money("0.00"), books.contractLiability());
        assertEquals(money("60.00"), books.refundPayable());
        assertEquals(money("50.00"), books.profit(), "收入 80 − COGS 12 − 报损 18");
        assertEquals(0, books.assets().subtract(books.liabilities()).compareTo(books.equity()));
        // 分次退款 40：现金真实减少，债务减 40，利润不变。
        AgedInventoryModel.Outcome refund1 = AgedInventoryModel.payRefund(state, "mgr", "C1",
                money("40.00"), at(2), accounts);
        accepted(refund1);
        state = refund1.state();
        accounts = refund1.accounts();
        assertEquals(money("20.00"), state.channelLedgers().get(0).refundPayable());
        assertEquals(money("50.00"), AgedInventoryModel.books(state, accounts).profit());
        // 再退 100（>应付 20）：只退 20，债务清零。
        AgedInventoryModel.Outcome refund2 = AgedInventoryModel.payRefund(state, "mgr", "C1",
                money("100.00"), at(2), accounts);
        accepted(refund2);
        state = refund2.state();
        accounts = refund2.accounts();
        assertEquals(money("0.00"), state.channelLedgers().get(0).refundPayable());
        AgedInventoryModel.Books after = AgedInventoryModel.books(state, accounts);
        assertEquals(money("50.00"), after.profit());
        assertEquals(0, after.assets().subtract(after.liabilities()).compareTo(after.equity()));
        assertEquals(0, after.totalCash().compareTo(state.openingTotalCash()), "现金守恒");
    }

    @Test
    @DisplayName("构造/同对手账户边界：负参数拒绝；同公司/供应商配置是否在开局拒绝")
    void constructorAndSameCounterpartyBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new AgedInventoryModel.Config(
                "c", "m", "CO", "SUP", "CONV", "FIX", "TERM", money("-1.00"), money("1.00"),
                money("0.00"), PERIOD, 1L, 0L, 1L, 0, 0,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", 1L, money("20.00"),
                        money("18.00"))), List.of(), AgedInventoryModel.EXP_LABEL));
        assertThrows(IllegalArgumentException.class, () -> new AgedInventoryModel.ChannelSpec(
                "C1", "CH1", -1L, money("20.00"), money("18.00")));
        assertThrows(IllegalArgumentException.class,
                () -> new AgedInventoryModel.DemandQuote(-1L, "C1", 1L));
        assertThrows(IllegalArgumentException.class, () -> new AgedInventoryModel.BatchLot(
                "B", 10L, 0L, 0L, money("0.00"), money("10.00"), money("0.00"), money("0.00"),
                10L, 1L, 0L));

        // 同对手账户：公司账户与供应商账户相同。
        Map<String, CashLedger.CashAccount> same = accounts("1000.00", "1000.00", "1000.00");
        AgedInventoryModel.Config config = new AgedInventoryModel.Config("c", "mgr", "CO", "CO",
                "CONV", "FIX", "TERM", money("2.00"), money("1.00"), money("0.00"), PERIOD,
                100L, 0L, 1L, 0, 0,
                List.of(new AgedInventoryModel.ChannelSpec("C1", "CH1", 10L, money("20.00"),
                        money("18.00"))), List.of(), AgedInventoryModel.EXP_LABEL);
        AgedInventoryModel.Outcome opened = AgedInventoryModel.open(config, same, List.of());
        System.out.println("[same-counterparty] open accepted=" + opened.accepted()
                + " reason=" + opened.reason());
        if (opened.accepted()) {
            AgedInventoryModel.Outcome order = AgedInventoryModel.orderRaw(opened.state(), "mgr",
                    10L, "O1", at(0), opened.accounts());
            accepted(order);
            AgedInventoryModel.Outcome receive = AgedInventoryModel.receiveOrder(order.state(),
                    "mgr", "O1", at(1), order.accounts());
            System.out.println("[same-counterparty] receive accepted=" + receive.accepted()
                    + " reason=" + receive.reason());
            assertFalse(receive.accepted() && AgedInventoryModel.validate(receive.state(),
                            receive.accounts()) != null,
                    "同对手账户不能静默通过账平校验");
        }
    }
}
