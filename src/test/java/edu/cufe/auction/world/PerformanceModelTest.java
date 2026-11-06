package edu.cufe.auction.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1：外部资本流与时间加权收益（测试先行）。
 *
 * <p>覆盖正式施工单 `docs/superpowers/plans/2026-11-06-valuation-performance.md`：
 * 追加不是收益、几何链接、同毫秒多流、流量前后估值、提现方向、缺价/非正权益不跨段连乘、
 * 无期间、输出精度 12 位 HALF_EVEN、指数回撤与不可变。</p>
 *
 * <p>本类只使用纯数据 {@link PerformanceModel.Point} 与 {@link PerformanceModel.Flow}；
 * 流量一律显式登记，脚本不从现金差额推断注资，股票买卖与红利收现不产生 Flow。</p>
 */
@DisplayName("PerformanceModel：外部资本流与时间加权收益")
class PerformanceModelTest {

    private static final String OWNER = "alice";
    private static final String PERIOD = "p-1";

    // ------------------------------------------------------------------
    // 无期间
    // ------------------------------------------------------------------

    @Test
    @DisplayName("零个或一个估值点没有期间")
    void noPeriodWithoutTwoPoints() {
        PerformanceModel.Performance empty = PerformanceModel.calculate(OWNER, PERIOD, List.of());
        assertEquals(PerformanceModel.Status.NO_PERIOD, empty.status());
        assertNull(empty.returnRate());
        assertNull(empty.index());
        assertNull(empty.maxDrawdown());

        PerformanceModel.Performance single = PerformanceModel.calculate(OWNER, PERIOD,
                List.of(point(1L, 1_000L, "1000.00", "1000.00")));
        assertEquals(PerformanceModel.Status.NO_PERIOD, single.status());
        assertNull(single.returnRate());
    }

    // ------------------------------------------------------------------
    // 资本流不是收益
    // ------------------------------------------------------------------

    @Test
    @DisplayName("追加 500 不是盈利：收益 0，规模变大")
    void contributionIsNotReturn() {
        PerformanceModel.Performance performance = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1000.00", "1500.00", flow("f1", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "500.00"))));

        assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
        assertEquals(0, performance.returnRate().compareTo(new BigDecimal("0.000000000000")),
                "注资不得被算成收益");
        assertEquals(0, performance.index().compareTo(new BigDecimal("1.000000000000")));
        assertEquals(0, performance.maxDrawdown().compareTo(new BigDecimal("0.000000000000")));
    }

    @Test
    @DisplayName("涨 10% → 追加 500 → 跌 10%，几何回报 −1%，回撤 10%")
    void geometricLinkAcrossAContribution() {
        PerformanceModel.Performance performance = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 1_500L, "1100.00", "1600.00", flow("f1", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "500.00")),
                point(3L, 2_000L, "1440.00", "1440.00")));

        assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
        assertEquals(0, performance.returnRate().compareTo(new BigDecimal("-0.010000000000")),
                "1.1 × 0.9 − 1 = −1%，不是 (1440/1500−1) = −4%");
        assertEquals(0, performance.index().compareTo(new BigDecimal("0.990000000000")));
        assertEquals(0, performance.maxDrawdown().compareTo(new BigDecimal("0.100000000000")),
                "(1.1 − 0.99) / 1.1 = 10%");
        assertEquals(2, performance.segments().size());
    }

    @Test
    @DisplayName("提现方向正确：规模下降但不是亏损")
    void withdrawalIsNotLoss() {
        PerformanceModel.Performance performance = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1000.00", "600.00", flow("f1", PerformanceModel.FlowKind.CAPITAL_WITHDRAWAL, "400.00"))));

        assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
        assertEquals(0, performance.returnRate().compareTo(new BigDecimal("0.000000000000")));
    }

    @Test
    @DisplayName("同一毫秒多笔流可合并进一个点，流前后估值齐备")
    void sameMillisFlowsMergeIntoOnePoint() {
        PerformanceModel.Performance performance = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1000.00", "1500.00",
                        flow("f1", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "300.00"),
                        flow("f2", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "200.00"))));

        assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
        assertEquals(0, performance.returnRate().compareTo(new BigDecimal("0.000000000000")));
    }

    @Test
    @DisplayName("同毫秒不同位置是两个合法边界，不合并也不重排")
    void sameMillisDifferentPositionsAreLegal() {
        PerformanceModel.Performance performance = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1000.00", "1300.00", flow("f1", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "300.00")),
                point(3L, 2_000L, "1300.00", "1500.00", flow("f2", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "200.00"))));

        assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
        assertEquals(0, performance.returnRate().compareTo(new BigDecimal("0.000000000000")));
        assertEquals(2, performance.segments().size());
    }

    // ------------------------------------------------------------------
    // 非法形状
    // ------------------------------------------------------------------

    @Test
    @DisplayName("首点不得携带资本流")
    void firstPointCannotCarryFlow() {
        assertThrows(IllegalArgumentException.class, () -> PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1200.00",
                        flow("f0", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "200.00")),
                point(2L, 2_000L, "1200.00", "1200.00"))));
    }

    @Test
    @DisplayName("重复 flowId 拒绝")
    void duplicateFlowIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1000.00", "1300.00", flow("dup", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "300.00")),
                point(3L, 3_000L, "1300.00", "1600.00", flow("dup", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "300.00")))));
    }

    @Test
    @DisplayName("流的所有者必须与点一致")
    void flowOwnerMustMatch() {
        assertThrows(IllegalArgumentException.class, () -> PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1000.00", "1500.00",
                        new PerformanceModel.Flow("f1", "bob",
                                PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, new BigDecimal("500.00"), "证据")))));
    }

    @Test
    @DisplayName("无流但前后估值不等、或有流但差额不符，均拒绝")
    void flowMustExplainTheGap() {
        assertThrows(IllegalArgumentException.class, () -> PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1000.00", "1100.00"))), "无流时前后必须相等");

        assertThrows(IllegalArgumentException.class, () -> PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1000.00", "1500.00",
                        flow("f1", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "400.00")))), "差额必须等于流量合计");
    }

    @Test
    @DisplayName("输入乱序直接拒绝，不排序掩盖缺失边界")
    void outOfOrderInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(2L, 2_000L, "1100.00", "1100.00"),
                point(1L, 1_000L, "1000.00", "1000.00"))));

        assertThrows(IllegalArgumentException.class, () -> PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 2_000L, "1000.00", "1000.00"),
                point(2L, 1_000L, "1000.00", "1000.00"))), "时点不得倒退");
    }

    @Test
    @DisplayName("流与点的非法字段在构造期拒绝")
    void invalidFlowAndPointShapesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Flow(
                "f", OWNER, PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, new BigDecimal("0.00"), "证据"));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Flow(
                "f", OWNER, PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, new BigDecimal("1.005"), "证据"));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Flow(
                "f", OWNER, PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, new BigDecimal("10.00"), " "));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Flow(
                " ", OWNER, PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, new BigDecimal("10.00"), "证据"));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Point(
                OWNER, 0L, 1_000L, ValuationModel.Status.COMPLETE,
                new BigDecimal("100.00"), new BigDecimal("100.00"), List.of()), "位置必须为正");
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Point(
                OWNER, 1L, 1_000L, ValuationModel.Status.MISSING_PRICE,
                new BigDecimal("100.00"), null, List.of()), "非完整点两个净值都必须为空");
    }

    // ------------------------------------------------------------------
    // 缺价与非正权益
    // ------------------------------------------------------------------

    @Test
    @DisplayName("期中缺价：整体不适用，不跨缺失点连乘")
    void missingValuationBreaksTheChain() {
        long missingPosition = 2L;
        PerformanceModel.Performance performance = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                missing(missingPosition, 2_000L),
                point(3L, 3_000L, "1200.00", "1200.00")));

        assertEquals(PerformanceModel.Status.INCOMPLETE_VALUATION, performance.status());
        assertNull(performance.returnRate(), "缺价期间不得给出收益率");
        assertNull(performance.index());
        assertNull(performance.maxDrawdown());
        assertTrue(performance.segments().stream().noneMatch(segment -> segment.returnRate() != null
                        && segment.fromPosition() < missingPosition && segment.toPosition() > missingPosition),
                "任何区间都不得跨过缺价点连乘");
    }

    @Test
    void missingValuationCanPreserveAKnownCapitalFlowWithoutInventingReturn() {
        PerformanceModel.Flow contribution = flow("known-flow", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "500.00");
        PerformanceModel.Point missing = new PerformanceModel.Point(OWNER, 2, 2_000,
                ValuationModel.Status.MISSING_PRICE, null, null, List.of(contribution));
        PerformanceModel.Performance result = PerformanceModel.calculate(OWNER, PERIOD,
                List.of(point(1, 1_000, "1000.00", "1000.00"), missing));
        assertEquals(List.of(contribution), missing.flows());
        assertEquals(PerformanceModel.Status.INCOMPLETE_VALUATION, result.status());
        assertNull(result.returnRate());
        assertNull(result.index());
        assertNull(result.maxDrawdown());
    }

    @Test
    @DisplayName("完整点保留零/负真实余额，但表现不适用")
    void nonPositiveEquityKeepsThePointButNotTheReturn() {
        PerformanceModel.Performance performance = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "0.00", "0.00")));

        assertEquals(PerformanceModel.Status.NON_POSITIVE_EQUITY, performance.status());
        assertNull(performance.returnRate());
        assertNull(performance.index());
        assertNull(performance.maxDrawdown());
    }

    @Test
    @DisplayName("优先级：缺价先于非正权益，非正权益先于无期间")
    void statusPrecedence() {
        PerformanceModel.Performance incomplete = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "0.00", "0.00"),
                missing(2L, 2_000L),
                point(3L, 3_000L, "100.00", "100.00")));
        assertEquals(PerformanceModel.Status.INCOMPLETE_VALUATION, incomplete.status());

        PerformanceModel.Performance single = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "0.00", "0.00")));
        assertEquals(PerformanceModel.Status.NON_POSITIVE_EQUITY, single.status(), "非正权益优先于无期间");
    }

    // ------------------------------------------------------------------
    // 精度、回撤与不可变
    // ------------------------------------------------------------------

    @Test
    @DisplayName("输出统一 12 位小数，中间不截断")
    void outputsUseTwelveDecimalPlaces() {
        PerformanceModel.Performance performance = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1333.33", "1333.33")));

        assertEquals(12, performance.returnRate().scale());
        assertEquals(12, performance.index().scale());
        assertEquals(12, performance.maxDrawdown().scale());
        assertEquals(0, performance.returnRate().compareTo(new BigDecimal("0.333330000000")));
    }

    @Test
    @DisplayName("回撤基于流量剔除指数，而不是原始净值")
    void maxDrawdownUsesIndexNotRawNav() {
        PerformanceModel.Performance performance = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 1_500L, "1000.00", "1500.00", flow("f1", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "500.00")),
                point(3L, 2_000L, "1800.00", "1800.00"),
                point(4L, 2_500L, "1800.00", "1200.00", flow("f2", PerformanceModel.FlowKind.CAPITAL_WITHDRAWAL, "600.00")),
                point(5L, 3_000L, "900.00", "900.00")));

        assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
        assertEquals(0, performance.maxDrawdown().compareTo(new BigDecimal("0.250000000000")),
                "指数回撤 (1.2−0.9)/1.2 = 25%；用原始净值会得 50%，是错误口径");
    }

    @Test
    @DisplayName("结果不可变且保留身份字段")
    void resultIsImmutableAndKeepsIdentity() {
        PerformanceModel.Performance performance = PerformanceModel.calculate(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1100.00", "1100.00")));

        assertEquals(OWNER, performance.ownerId());
        assertEquals(PERIOD, performance.periodId());
        assertThrows(UnsupportedOperationException.class, () -> performance.segments().clear());
    }

    @Test
    @DisplayName("清盘后重新注资另起期间，不与旧非正权益连乘")
    void newPeriodAfterLiquidationIsNotChained() {
        PerformanceModel.Performance oldPeriod = PerformanceModel.calculate(OWNER, "p-old", List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "0.00", "0.00")));
        assertEquals(PerformanceModel.Status.NON_POSITIVE_EQUITY, oldPeriod.status());

        PerformanceModel.Performance newPeriod = PerformanceModel.calculate(OWNER, "p-new", List.of(
                point(1L, 3_000L, "500.00", "500.00"),
                point(2L, 4_000L, "550.00", "550.00")));
        assertEquals(PerformanceModel.Status.COMPLETE, newPeriod.status());
        assertEquals(0, newPeriod.returnRate().compareTo(new BigDecimal("0.100000000000")));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static PerformanceModel.Flow flow(String flowId, PerformanceModel.FlowKind kind,
                                              String amount) {
        return new PerformanceModel.Flow(flowId, OWNER, kind, new BigDecimal(amount), "证据-" + flowId);
    }

    private static PerformanceModel.Point point(long position, long atMillis,
                                                String navBefore, String navAfter,
                                                PerformanceModel.Flow... flows) {
        return new PerformanceModel.Point(OWNER, position, atMillis, ValuationModel.Status.COMPLETE,
                new BigDecimal(navBefore), new BigDecimal(navAfter), List.of(flows));
    }

    private static PerformanceModel.Point missing(long position, long atMillis) {
        return new PerformanceModel.Point(OWNER, position, atMillis,
                ValuationModel.Status.MISSING_PRICE, null, null, new ArrayList<>());
    }
}
