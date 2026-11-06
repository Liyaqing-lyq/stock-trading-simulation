package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3a：制造报表接纳公司分红义务（测试先行）。
 *
 * <p>覆盖正式施工单 `docs/superpowers/plans/2026-11-06-dividend-financial-seams.md`：
 * 旧两参入口与显式 {@code none()} 逐字等价、Report 形状不变、宣告未付增加合同负债并减少权益、
 * 真实现金支付只减现金与未付、利润口径不变、世界现金守恒、部分付款算术、负权益合法、
 * 金额精度与非法形状拒绝、真实失衡仍抛异常。</p>
 *
 * <p>现金变动一律用 {@link CashLedger#computeTransfer} 的真实语义计算，不另存第二份分红账；
 * 测试中的账户表是明确夹具，不代表已接入世界运行桥。</p>
 */
@DisplayName("ManufacturingModel：分红义务进入制造报表")
class ManufacturingDividendTest {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    // ------------------------------------------------------------------
    // 旧入口与形状
    // ------------------------------------------------------------------

    @Test
    @DisplayName("旧两参 report 等于显式 none，金额与平衡位逐字段相同")
    void legacyReportEqualsExplicitNone() {
        ManufacturingModel.State state = openedState();
        Map<String, CashAccount> accounts = accounts("1000.00");

        ManufacturingModel.Report legacy = ManufacturingModel.report(state, accounts);
        ManufacturingModel.Report explicit = ManufacturingModel.report(state, accounts,
                ManufacturingModel.DividendPosition.none());

        assertEquals(legacy, explicit, "旧入口必须等价于显式没有分红义务");
        assertTrue(legacy.balanced());
        assertEquals(0, legacy.totalLiabilities().compareTo(ZERO));
        assertEquals(0, legacy.equity().compareTo(new BigDecimal("2000.00")),
                "初始权益 = 现金 1000 + 设备 1000");
        assertEquals(legacy, ManufacturingModel.validate(state, accounts));
    }

    @Test
    @DisplayName("Report 仍是原来的 record 形状，没有新增分红字段")
    void reportShapeUnchanged() {
        RecordComponent[] components = ManufacturingModel.Report.class.getRecordComponents();
        assertEquals(23, components.length, "Report 不得新增分量");
        List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
        assertTrue(names.containsAll(List.of("totalAssets", "totalLiabilities", "equity",
                "worldCash", "balanced", "payableOutstanding", "cumulativeProfit")));
        assertFalse(names.stream().anyMatch(name -> name.toLowerCase().contains("dividend")),
                "分红义务不得以字段形式进入 Report，只在负债与权益体现");
    }

    @Test
    @DisplayName("none 精确为 0.00/0.00 且不可变")
    void noneIsZeroWithTwoDecimals() {
        ManufacturingModel.DividendPosition none = ManufacturingModel.DividendPosition.none();
        assertEquals(2, none.declaredFace().scale());
        assertEquals(2, none.unpaidFace().scale());
        assertEquals(0, none.declaredFace().signum());
        assertEquals(0, none.unpaidFace().signum());
        assertEquals(none, new ManufacturingModel.DividendPosition(
                new BigDecimal("0.00"), new BigDecimal("0.00")));
    }

    // ------------------------------------------------------------------
    // 宣告与支付
    // ------------------------------------------------------------------

    @Test
    @DisplayName("宣告未付 100：资产 2000、负债 100、权益 1900，利润不变")
    void declaredUnpaidSplitsLiabilityAndEquity() {
        ManufacturingModel.State state = openedState();
        ManufacturingModel.Report report = ManufacturingModel.report(state, accounts("1000.00"),
                new ManufacturingModel.DividendPosition(
                        new BigDecimal("100.00"), new BigDecimal("100.00")));

        assertEquals(0, report.totalAssets().compareTo(new BigDecimal("2000.00")));
        assertEquals(0, report.totalLiabilities().compareTo(new BigDecimal("100.00")));
        assertEquals(0, report.equity().compareTo(new BigDecimal("1900.00")));
        assertEquals(0, report.cumulativeProfit().compareTo(ZERO), "利润口径不变");
        assertEquals(0, report.payableOutstanding().signum(),
                "expensePayables 仍只含经营应付");
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("真实现金付 100：资产 1900、负债 0、权益 1900，世界现金守恒")
    void realPaymentReducesCashAndUnpaidOnly() {
        ManufacturingModel.State state = openedState();
        Map<String, CashAccount> before = accounts("1000.00");
        CashLedger.TransferOutcome paid = CashLedger.computeTransfer(before, "acme",
                "ACME-CASH", "INV", new BigDecimal("100.00"));
        assertTrue(paid.accepted(), "夹具前提：公司向投资者真实转账必须成功");

        ManufacturingModel.Report report = ManufacturingModel.report(state, paid.accounts(),
                new ManufacturingModel.DividendPosition(
                        new BigDecimal("100.00"), new BigDecimal("0.00")));

        assertEquals(0, report.companyCash().compareTo(new BigDecimal("900.00")));
        assertEquals(0, report.totalAssets().compareTo(new BigDecimal("1900.00")));
        assertEquals(0, report.totalLiabilities().signum());
        assertEquals(0, report.equity().compareTo(new BigDecimal("1900.00")));
        assertEquals(0, report.cumulativeProfit().compareTo(ZERO), "付款不是费用，利润不变");
        assertEquals(0, report.worldCash().compareTo(new BigDecimal("1000.00")),
                "公司付款只在世界内转移现金");
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("部分付款：宣告 100 已付 60，负债 40、权益 1900、资产 1940")
    void partialPaymentKeepsArithmetic() {
        ManufacturingModel.State state = openedState();
        CashLedger.TransferOutcome paid = CashLedger.computeTransfer(accounts("1000.00"),
                "acme", "ACME-CASH", "INV", new BigDecimal("60.00"));
        assertTrue(paid.accepted());

        ManufacturingModel.Report report = ManufacturingModel.report(state, paid.accounts(),
                new ManufacturingModel.DividendPosition(
                        new BigDecimal("100.00"), new BigDecimal("40.00")));

        assertEquals(0, report.companyCash().compareTo(new BigDecimal("940.00")));
        assertEquals(0, report.totalAssets().compareTo(new BigDecimal("1940.00")));
        assertEquals(0, report.totalLiabilities().compareTo(new BigDecimal("40.00")));
        assertEquals(0, report.equity().compareTo(new BigDecimal("1900.00")));
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("宣告超过初始权益时允许负权益，仍按真实金额平衡")
    void negativeEquityIsExpressed() {
        ManufacturingModel.State state = openedState();
        CashLedger.TransferOutcome paid = CashLedger.computeTransfer(accounts("1000.00"),
                "acme", "ACME-CASH", "INV", new BigDecimal("500.00"));
        assertTrue(paid.accepted());

        ManufacturingModel.Report report = ManufacturingModel.report(state, paid.accounts(),
                new ManufacturingModel.DividendPosition(
                        new BigDecimal("2500.00"), new BigDecimal("2000.00")));

        assertEquals(0, report.totalAssets().compareTo(new BigDecimal("1500.00")));
        assertEquals(0, report.totalLiabilities().compareTo(new BigDecimal("2000.00")));
        assertEquals(0, report.equity().compareTo(new BigDecimal("-500.00")));
        assertTrue(report.balanced(), "负权益本身不是失衡");
        assertEquals(report, ManufacturingModel.validate(state, paid.accounts(),
                new ManufacturingModel.DividendPosition(
                        new BigDecimal("2500.00"), new BigDecimal("2000.00"))));
    }

    // ------------------------------------------------------------------
    // 非法形状与真实异常
    // ------------------------------------------------------------------

    @Test
    @DisplayName("金额必须精确 2 位、非负，且未付不得超过宣告")
    void invalidPositionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ManufacturingModel.DividendPosition(
                null, new BigDecimal("0.00")));
        assertThrows(IllegalArgumentException.class, () -> new ManufacturingModel.DividendPosition(
                new BigDecimal("1.005"), new BigDecimal("0.00")), "三位小数必须拒绝");
        assertThrows(IllegalArgumentException.class, () -> new ManufacturingModel.DividendPosition(
                new BigDecimal("-1.00"), new BigDecimal("0.00")), "负宣告必须拒绝");
        assertThrows(IllegalArgumentException.class, () -> new ManufacturingModel.DividendPosition(
                new BigDecimal("100.00"), new BigDecimal("100.01")), "未付大于宣告必须拒绝");
    }

    @Test
    @DisplayName("缺少分红位置时拒绝，不默认当作零")
    void nullPositionIsRejected() {
        ManufacturingModel.State state = openedState();
        assertThrows(IllegalArgumentException.class,
                () -> ManufacturingModel.report(state, accounts("1000.00"), null));
    }

    @Test
    @DisplayName("世界现金被外部改动时 validate 仍然抛出，不放宽守恒")
    void imbalanceStillThrows() {
        ManufacturingModel.State state = openedState();
        Map<String, CashAccount> tampered = accounts("1000.00");
        tampered.put("SUP", new CashAccount("supplier", new BigDecimal("50.00"), "夹具注入"));

        ManufacturingModel.Report report = ManufacturingModel.report(state, tampered,
                new ManufacturingModel.DividendPosition(
                        new BigDecimal("100.00"), new BigDecimal("100.00")));
        assertFalse(report.balanced(), "世界现金不再守恒");
        assertThrows(IllegalStateException.class, () -> ManufacturingModel.validate(state, tampered,
                new ManufacturingModel.DividendPosition(
                        new BigDecimal("100.00"), new BigDecimal("100.00"))));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static ManufacturingModel.Opening opening() {
        return new ManufacturingModel.Opening("acme", "ACME-CASH", "SUP", "CUS", "OPS");
    }

    private static ManufacturingModel.State openedState() {
        return ManufacturingModel.open(opening(), accounts("1000.00"));
    }

    private static Map<String, CashAccount> accounts(String companyCash) {
        Map<String, CashAccount> accounts = new LinkedHashMap<>();
        accounts.put("ACME-CASH", new CashAccount("acme", new BigDecimal(companyCash), "夹具"));
        accounts.put("SUP", new CashAccount("supplier", ZERO, "夹具"));
        accounts.put("CUS", new CashAccount("customer", ZERO, "夹具"));
        accounts.put("OPS", new CashAccount("ops", ZERO, "夹具"));
        accounts.put("INV", new CashAccount("alice", ZERO, "夹具投资者"));
        assertNotNull(accounts.get("ACME-CASH"));
        return accounts;
    }
}
