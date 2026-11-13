package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.EngineeringModel.Config;
import edu.cufe.auction.world.EngineeringModel.ContractSpec;
import edu.cufe.auction.world.EngineeringModel.Outcome;
import edu.cufe.auction.world.EngineeringModel.Report;
import edu.cufe.auction.world.EngineeringModel.ReportContext;
import edu.cufe.auction.world.EngineeringModel.RetentionMode;
import edu.cufe.auction.world.EngineeringModel.State;
import edu.cufe.auction.world.EngineeringModel.Terms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 外部资本/分红只读 Context：旧零 Context、净资本、宣告/付款与世界现金守恒。 */
class EngineeringCapitalContextTest {

    private static final long DAY = EngineeringModel.DAY_MILLIS;
    private static final Config CONFIG = new Config("company", "CO", "CUS", "SUP", "OPS",
            DAY, 6000, "实验参数");

    @Test
    @DisplayName("旧签名等价零 Context；旧 25 测试的报表字段与 balanced 不变")
    void zeroContextDelegates() {
        State state = EngineeringModel.open(CONFIG, accounts());
        Report old = EngineeringModel.report(state, accounts());
        Report zero = EngineeringModel.report(state, accounts(), ReportContext.none());
        assertEquals(old, zero);
        assertEquals("5000.00", zero.equity().toPlainString());
        assertEquals("0.00", zero.profit().toPlainString());
        assertTrue(zero.balanced());
    }

    @Test
    @DisplayName("净注资：现金双边转移，只增权益不增利润")
    void injectionIncreasesEquityNotProfit() {
        Map<String, CashAccount> accounts = accounts();
        State state = EngineeringModel.open(CONFIG, accounts);
        transfer(accounts, "INV", "CO", "1000.00");
        Report report = EngineeringModel.validate(state, accounts,
                new ReportContext(bd("1000.00"), bd("0.00"), bd("0.00")));
        assertEquals("6000.00", report.companyCash().toPlainString());
        assertEquals("6000.00", report.equity().toPlainString());
        assertEquals("0.00", report.profit().toPlainString());
        assertEquals("45000.00", report.worldCash().toPlainString());
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("净退还资本：现金与权益同减，利润不变")
    void capitalReturnReducesEquity() {
        Map<String, CashAccount> accounts = accounts();
        State state = EngineeringModel.open(CONFIG, accounts);
        transfer(accounts, "CO", "INV", "500.00");
        Report report = EngineeringModel.validate(state, accounts,
                new ReportContext(bd("-500.00"), bd("0.00"), bd("0.00")));
        assertEquals("4500.00", report.companyCash().toPlainString());
        assertEquals("4500.00", report.equity().toPlainString());
        assertEquals("0.00", report.profit().toPlainString());
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("宣告未付：权益减一次、负债加未付；payable 字段不混入分红")
    void declaredUnpaidIsLiabilityNotPayable() {
        State state = EngineeringModel.open(CONFIG, accounts());
        Report report = EngineeringModel.validate(state, accounts(),
                new ReportContext(bd("0.00"), bd("1000.00"), bd("1000.00")));
        assertEquals("4000.00", report.equity().toPlainString());
        assertEquals("0.00", report.payable().toPlainString());
        assertEquals("5000.00", report.companyCash().toPlainString());
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("部分付款/付清：只结义务，不再减权益；利润始终为零")
    void partialAndFullDividendPayment() {
        Map<String, CashAccount> partialAccounts = accounts();
        State partialState = EngineeringModel.open(CONFIG, partialAccounts);
        transfer(partialAccounts, "CO", "INV", "400.00");
        Report partial = EngineeringModel.validate(partialState, partialAccounts,
                new ReportContext(bd("0.00"), bd("1000.00"), bd("600.00")));
        assertEquals("4600.00", partial.companyCash().toPlainString());
        assertEquals("4000.00", partial.equity().toPlainString());
        assertEquals("0.00", partial.profit().toPlainString());
        assertTrue(partial.balanced());

        Map<String, CashAccount> paidAccounts = accounts();
        State paidState = EngineeringModel.open(CONFIG, paidAccounts);
        transfer(paidAccounts, "CO", "INV", "1000.00");
        Report paid = EngineeringModel.validate(paidState, paidAccounts,
                new ReportContext(bd("0.00"), bd("1000.00"), bd("0.00")));
        assertEquals("4000.00", paid.companyCash().toPlainString());
        assertEquals("4000.00", paid.equity().toPlainString());
        assertTrue(paid.balanced());
    }

    @Test
    @DisplayName("组合注资与分红、实际开工后仍平衡；Context 不改变经营利润")
    void combinedAfterOperations() {
        Map<String, CashAccount> accounts = accounts();
        State state = EngineeringModel.open(CONFIG, accounts);
        transfer(accounts, "INV", "CO", "2000.00");
        state = ok(EngineeringModel.sign(state, "s1", "company", new ContractSpec("C-1",
                bd("100.00"), bd("60.00"), terms()), 0, accounts), accounts);
        state = ok(EngineeringModel.receiveAdvance(state, "a1", "company", "C-1",
                bd("1000.00"), 0, accounts), accounts);
        state = ok(EngineeringModel.investWork(state, "w1", "company", "C-1", "W-1", 10, 0,
                accounts), accounts);
        state = ok(EngineeringModel.completeWork(state, "c1", "company", "W-1", 10 * DAY,
                accounts), accounts);
        state = ok(EngineeringModel.accept(state, "ac1", "company", "C-1", 10, "bridge-ref",
                10 * DAY, accounts), accounts);
        transfer(accounts, "CO", "INV", "300.00");
        Report zero = EngineeringModel.report(state, accounts, ReportContext.none());
        Report with = EngineeringModel.validate(state, accounts,
                new ReportContext(bd("2000.00"), bd("500.00"), bd("200.00")));
        assertEquals(zero.profit(), with.profit(), "资本/分红不得进入经营利润");
        assertEquals("1500.00", with.equity().subtract(zero.equity()).toPlainString());
        assertTrue(with.balanced());
    }

    @Test
    @DisplayName("现金已注资但零 Context、或 Context 有资本但现金未动：validate 拒绝")
    void mismatchedContextRejected() {
        Map<String, CashAccount> injected = accounts();
        State state = EngineeringModel.open(CONFIG, accounts());
        transfer(injected, "INV", "CO", "1000.00");
        assertThrows(IllegalStateException.class,
                () -> EngineeringModel.validate(state, injected, ReportContext.none()));
        assertThrows(IllegalStateException.class,
                () -> EngineeringModel.validate(state, accounts(),
                        new ReportContext(bd("1000.00"), bd("0.00"), bd("0.00"))));
    }

    @Test
    @DisplayName("世界单边造现金：公司侧 Context 匹配、worldCash 不守恒仍拒绝")
    void unilateralWorldCashRejected() {
        Map<String, CashAccount> accounts = accounts();
        State state = EngineeringModel.open(CONFIG, accounts);
        accounts.put("CO", new CashAccount("company", bd("6000.00"), "开局"));
        assertFalse(EngineeringModel.report(state, accounts,
                new ReportContext(bd("1000.00"), bd("0.00"), bd("0.00"))).balanced());
        assertThrows(IllegalStateException.class,
                () -> EngineeringModel.validate(state, accounts,
                        new ReportContext(bd("1000.00"), bd("0.00"), bd("0.00"))));
    }

    @Test
    @DisplayName("非法 Context：精度、负宣告、未付超宣告、null")
    void invalidContextRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReportContext(bd("1.001"), bd("0.00"), bd("0.00")));
        assertThrows(IllegalArgumentException.class,
                () -> new ReportContext(bd("0.00"), bd("-1.00"), bd("0.00")));
        assertThrows(IllegalArgumentException.class,
                () -> new ReportContext(bd("0.00"), bd("100.00"), bd("100.01")));
        State state = EngineeringModel.open(CONFIG, accounts());
        assertThrows(IllegalArgumentException.class,
                () -> EngineeringModel.report(state, accounts(), null));
        assertThrows(IllegalArgumentException.class,
                () -> EngineeringModel.validate(state, accounts(), null));
    }

    @Test
    @DisplayName("宣告超过权益：如实负权益，不夹到 0")
    void negativeEquityNotClamped() {
        State state = EngineeringModel.open(CONFIG, accounts());
        Report report = EngineeringModel.validate(state, accounts(),
                new ReportContext(bd("0.00"), bd("8000.00"), bd("8000.00")));
        assertEquals("-3000.00", report.equity().toPlainString());
        assertTrue(report.balanced());
    }

    // ---------------- 夹具 ----------------

    private static Map<String, CashAccount> accounts() {
        Map<String, CashAccount> accounts = new LinkedHashMap<>();
        accounts.put("CO", new CashAccount("company", bd("5000.00"), "开局"));
        accounts.put("CUS", new CashAccount("customer", bd("30000.00"), "开局"));
        accounts.put("SUP", new CashAccount("supplier", bd("0.00"), "开局"));
        accounts.put("OPS", new CashAccount("ops", bd("0.00"), "开局"));
        accounts.put("INV", new CashAccount("investor", bd("10000.00"), "开局"));
        return accounts;
    }

    private static void transfer(Map<String, CashAccount> accounts, String from, String to,
                                 String amount) {
        BigDecimal value = bd(amount);
        CashAccount source = accounts.get(from);
        CashAccount target = accounts.get(to);
        accounts.put(from, new CashAccount(source.ownerId(),
                source.cash().subtract(value), source.source()));
        accounts.put(to, new CashAccount(target.ownerId(),
                target.cash().add(value), target.source()));
    }

    private static Terms terms() {
        return new Terms(2000, 1000, 15, 60, RetentionMode.TIME_ONLY, new long[]{10, 90});
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static State ok(Outcome outcome) {
        assertTrue(outcome.accepted(), outcome.reason());
        return outcome.state();
    }

    private static State ok(Outcome outcome, Map<String, CashAccount> accounts) {
        assertTrue(outcome.accepted(), outcome.reason());
        accounts.clear();
        accounts.putAll(outcome.accounts());
        return outcome.state();
    }
}
