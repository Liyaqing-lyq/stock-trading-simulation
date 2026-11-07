package edu.cufe.auction.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6b：逐点表现历史（测试先行）。
 *
 * <p>覆盖正式施工单 `docs/superpowers/plans/2026-11-07-performance-history.md` §2：
 * 历史结果与 {@code calculate} 共享同一遍几何链接与同一高精度峰值（不另算、不复刻公式）、
 * 首点指数 1/回撤 0、逐点指数序列、当前回撤小于最大回撤、缺价与单点不连假曲线、
 * 一分钱与大整数精度、不可变。</p>
 *
 * <p>本类的期望值全部由手算的因子写出（1.1 × 0.9 = 0.99 一类），不经被测代码派生。</p>
 */
@DisplayName("PerformanceModel.history：逐点指数与当前回撤")
class PerformanceHistoryTest {

    private static final String OWNER = "alice";
    private static final String PERIOD = "history-1";

    // ------------------------------------------------------------------
    // 与旧 calculate 的一致性（同一遍计算）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("history.performance() 与 calculate 逐字段相等：同一遍计算，不另算")
    void historySharesTheSinglePassWithCalculate() {
        List<PerformanceModel.Point> points = List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 1_500L, "1100.00", "1600.00",
                        flow("f1", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "500.00")),
                point(3L, 2_000L, "1440.00", "1440.00"),
                point(4L, 2_500L, "1200.00", "1200.00"));

        PerformanceModel.Performance legacy = PerformanceModel.calculate(OWNER, PERIOD, points);
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD, points);

        assertEquals(legacy, history.performance(), "整个 Performance 记录必须逐字段相等");
        assertEquals(legacy.segments(), history.performance().segments());
        assertEquals(legacy.periodId(), history.performance().periodId());
        assertEquals(legacy.maxDrawdown(), history.performance().maxDrawdown());
        assertEquals(4, history.series().size(), "每个确认点都有一个采样点");
    }

    @Test
    @DisplayName("首点指数恰为 1.000000000000、回撤恰为 0.000000000000")
    void firstPointStartsAtOneWithoutDrawdown() {
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1100.00", "1100.00")));

        PerformanceModel.Series first = history.series().get(0);
        assertEquals(new BigDecimal("1.000000000000"), first.index());
        assertEquals(new BigDecimal("0.000000000000"), first.drawdown());
        assertEquals(12, first.index().scale());
        assertEquals(12, first.drawdown().scale());
    }

    @Test
    @DisplayName("逐点指数 = 前缀因子连乘：涨 10% → 注资 → 跌 10% 得 1.1、0.99")
    void perPointIndexIsTheGeometricPrefix() {
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 1_500L, "1100.00", "1600.00",
                        flow("f1", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "500.00")),
                point(3L, 2_000L, "1440.00", "1440.00")));

        assertEquals(BigDecimal.ONE.setScale(12), history.series().get(0).index());
        assertEquals(new BigDecimal("1.100000000000"), history.series().get(1).index(),
                "1600/1000 的流前估值 1100 与上一帧 1000 之比");
        assertEquals(new BigDecimal("0.990000000000"), history.series().get(2).index(),
                "1440/1600 = 0.9，乘上 1.1 得 0.99");
        assertEquals(history.series().get(2).index(), history.performance().index(),
                "最后一个点的指数必须等于期间指数");
    }

    @Test
    @DisplayName("逐点回撤是当时的当前回撤，不是累计最大回撤")
    void drawdownIsPointwiseNotMonotone() {
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1100.00", "1100.00"),
                point(3L, 3_000L, "880.00", "880.00"),
                point(4L, 4_000L, "1045.00", "1045.00")));

        // 1.1 → 0.88 → 1.045；峰值 1.1。
        assertEquals(new BigDecimal("0.200000000000"), history.series().get(2).drawdown(),
                "(1.1 − 0.88) / 1.1 = 20%");
        assertEquals(new BigDecimal("0.050000000000"), history.series().get(3).drawdown(),
                "回升到 1.045 后当前回撤是 (1.1 − 1.045) / 1.1 = 5%，不是仍然 20%");
        assertEquals(new BigDecimal("0.200000000000"), history.performance().maxDrawdown(),
                "汇总仍是期间最大回撤 20%");
    }

    @Test
    @DisplayName("逐点回撤的最大值恰等于性能汇总的最大回撤（同一高精度峰值）")
    void maxSeriesDrawdownEqualsSummary() {
        List<PerformanceModel.Point> points = List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 1_500L, "1000.00", "1500.00",
                        flow("f1", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "500.00")),
                point(3L, 2_000L, "1800.00", "1800.00"),
                point(4L, 2_500L, "1800.00", "1200.00",
                        flow("f2", PerformanceModel.FlowKind.CAPITAL_WITHDRAWAL, "600.00")),
                point(5L, 3_000L, "900.00", "900.00"),
                point(6L, 3_500L, "999.99", "999.99"));

        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD, points);
        BigDecimal worst = BigDecimal.ZERO.setScale(12);
        for (PerformanceModel.Series series : history.series()) {
            if (series.drawdown() != null && series.drawdown().compareTo(worst) > 0) {
                worst = series.drawdown();
            }
        }
        assertEquals(history.performance().maxDrawdown(), worst,
                "汇总回撤不得由已舍入指数另算而与逐点不一致");
        assertEquals(new BigDecimal("0.250000000000"), worst);
    }

    // ------------------------------------------------------------------
    // 不可用状态：不填 0、不连假曲线
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缺价：状态沿用旧语义，指数与回撤全为空，已知规模仍保留")
    void incompleteValuationKeepsScaleButNotTheCurve() {
        PerformanceModel.Point missing = new PerformanceModel.Point(OWNER, 2L, 2_000L,
                ValuationModel.Status.MISSING_PRICE, null, null, new ArrayList<>());
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                missing,
                point(3L, 3_000L, "1200.00", "1200.00")));

        assertEquals(PerformanceModel.Status.INCOMPLETE_VALUATION, history.performance().status());
        assertNull(history.performance().returnRate());
        assertNull(history.performance().index());
        assertNull(history.performance().maxDrawdown());
        for (PerformanceModel.Series series : history.series()) {
            assertNull(series.index(), "缺价期间不得给出任何指数点");
            assertNull(series.drawdown(), "缺价期间不得给出回撤点");
        }
        assertEquals(new BigDecimal("1000.00"), history.series().get(0).point().navAfterFlow(),
                "已知的资金规模仍然可展示");
        assertNull(history.series().get(1).point().navAfterFlow(), "缺价点的规模为空，不填 0");
        assertEquals(new BigDecimal("1200.00"), history.series().get(2).point().navAfterFlow());
    }

    @Test
    @DisplayName("单点：NO_PERIOD，一个采样点但指数/回撤为空")
    void singlePointHasNoPeriod() {
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD,
                List.of(point(7L, 1_000L, "1000.00", "1000.00")));

        assertEquals(PerformanceModel.Status.NO_PERIOD, history.performance().status());
        assertEquals(1, history.series().size());
        assertNull(history.series().get(0).index(), "不足两点时首点也不冒充指数 1");
        assertNull(history.series().get(0).drawdown());
        assertEquals(new BigDecimal("1000.00"), history.series().get(0).point().navAfterFlow());
    }

    @Test
    @DisplayName("零权益：NON_POSITIVE_EQUITY，规模保留 0 但不产生指数")
    void nonPositiveEquityKeepsTheRealZero() {
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "0.00", "0.00")));

        assertEquals(PerformanceModel.Status.NON_POSITIVE_EQUITY, history.performance().status());
        assertEquals(new BigDecimal("0.00"), history.series().get(1).point().navAfterFlow());
        assertNull(history.series().get(1).index());
    }

    @Test
    @DisplayName("组织主体不支持估值：状态与空曲线一致")
    void unsupportedScopeIsCarriedThrough() {
        PerformanceModel.Point unsupported = new PerformanceModel.Point(OWNER, 2L, 2_000L,
                ValuationModel.Status.UNSUPPORTED_COMPANY_SCOPE, null, null, new ArrayList<>());
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"), unsupported));

        assertEquals(PerformanceModel.Status.INCOMPLETE_VALUATION, history.performance().status());
        assertNull(history.series().get(1).index());
    }

    // ------------------------------------------------------------------
    // 精度：一分钱、极大值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("一分钱跨度在高净值上：规模字符串不丢一分钱，指数按 12 位口径舍入")
    void onePennyOnAHugeNavKeepsTheAmountButRoundsTheIndex() {
        BigDecimal huge = new BigDecimal("92233720368547758.08");
        BigDecimal next = new BigDecimal("92233720368547758.09");
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD, List.of(
                point(1L, 1_000L, huge.toPlainString(), huge.toPlainString()),
                point(2L, 2_000L, next.toPlainString(), next.toPlainString())));

        PerformanceModel.Series second = history.series().get(1);
        assertEquals(huge, history.series().get(0).point().navAfterFlow());
        assertEquals(next, second.point().navAfterFlow(),
                "金额必须逐分保留：画图的 BigInt 差值依赖这两个字符串");
        assertEquals(2, second.point().navAfterFlow().scale());
        assertEquals(12, second.index().scale());
        // 相对变化约 1.08e-19，12 位小数的项目口径无法表达；这与旧输出的固定精度一致，
        // 不是精度回退：一分钱差异由资金规模曲线的精确字符串承担，而不是让指数伪造小数位。
        assertEquals(new BigDecimal("1.000000000000"), second.index());
        assertEquals(0, history.performance().returnRate().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("大整数与十二位小数的输出 scale 固定，不被压缩")
    void outputScaleIsFixed() {
        BigDecimal start = new BigDecimal("10000000000000000000.00");
        BigDecimal end = new BigDecimal("10000000000000000001.23");
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD, List.of(
                point(1L, 1_000L, start.toPlainString(), start.toPlainString()),
                point(2L, 2_000L, end.toPlainString(), end.toPlainString())));

        assertEquals(12, history.performance().returnRate().scale());
        assertEquals(12, history.series().get(1).index().scale());
        assertEquals(12, history.series().get(1).drawdown().scale());
        assertEquals(2, history.series().get(1).point().navAfterFlow().scale());
    }

    // ------------------------------------------------------------------
    // 不可变与结构校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("结果不可变，采样点原样引用输入 Point")
    void resultIsImmutableAndKeepsThePoint() {
        PerformanceModel.Point first = point(1L, 1_000L, "1000.00", "1000.00");
        PerformanceModel.Point second = point(2L, 2_000L, "1100.00", "1100.00");
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD,
                List.of(first, second));

        assertSame(first, history.series().get(0).point());
        assertThrows(UnsupportedOperationException.class, () -> history.series().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> history.performance().segments().clear());
    }

    @Test
    @DisplayName("非法结构沿用 calculate 的拒绝，不因新增接口放宽")
    void invalidShapesAreStillRejected() {
        assertThrows(IllegalArgumentException.class, () -> PerformanceModel.history(OWNER, PERIOD,
                List.of(point(2L, 2_000L, "1100.00", "1100.00"),
                        point(1L, 1_000L, "1000.00", "1000.00"))), "乱序仍拒绝");
        assertThrows(IllegalArgumentException.class, () -> PerformanceModel.history(OWNER, PERIOD,
                List.of(point(1L, 2_000L, "1000.00", "1000.00"),
                        point(2L, 1_000L, "1000.00", "1000.00"))), "时点倒退仍拒绝");
        assertThrows(IllegalArgumentException.class, () -> PerformanceModel.history(OWNER, PERIOD,
                List.of(point(1L, 1_000L, "1000.00", "1200.00",
                        flow("f0", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "200.00")),
                        point(2L, 2_000L, "1200.00", "1200.00"))), "首点不得带资本流");
        assertThrows(NullPointerException.class,
                () -> new PerformanceModel.Series(null, BigDecimal.ONE, BigDecimal.ZERO),
                "空采样点沿用本项目的 requireNonNull 惯例（与 Point 的 capital 流一致）");
    }

    @Test
    @DisplayName("Series 拒绝不一致组合，但接受 12 位舍入得到的 0 指数与 1 回撤")
    void seriesRejectsInconsistentValues() {
        PerformanceModel.Point complete = point(1L, 1_000L, "1000.00", "1000.00");
        assertThrows(IllegalArgumentException.class,
                () -> new PerformanceModel.Series(complete, BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PerformanceModel.Series(complete, null, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Series(complete,
                new BigDecimal("1.0"), BigDecimal.ZERO), "指数必须 scale 12");
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Series(complete,
                BigDecimal.ONE.setScale(12), new BigDecimal("1.00")), "回撤必须 scale 12");

        // 12 位舍入的表示边界：合法正权益的极端下跌会给出 0 指数与 1 回撤，
        // 这两个值是“小于显示精度”的表示，不是零/负权益状态，必须可构造。
        PerformanceModel.Series rounded = new PerformanceModel.Series(complete,
                BigDecimal.ZERO.setScale(12), BigDecimal.ONE.setScale(12));
        assertEquals(new BigDecimal("0.000000000000"), rounded.index());
        assertEquals(new BigDecimal("1.000000000000"), rounded.drawdown());
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Series(complete,
                new BigDecimal("-0.000000000001"), BigDecimal.ZERO.setScale(12)),
                "负指数仍拒绝");
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Series(complete,
                BigDecimal.ONE.setScale(12), new BigDecimal("1.000000000001")),
                "回撤超过 1 仍拒绝");
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Series(complete,
                BigDecimal.ONE.setScale(12), new BigDecimal("-0.000000000001")),
                "负回撤仍拒绝");

        PerformanceModel.Point missing = new PerformanceModel.Point(OWNER, 2L, 2_000L,
                ValuationModel.Status.MISSING_PRICE, null, null, new ArrayList<>());
        assertThrows(IllegalArgumentException.class, () -> new PerformanceModel.Series(missing,
                BigDecimal.ONE.setScale(12), BigDecimal.ZERO.setScale(12)),
                "缺价点不得携带指数");
    }

    @Test
    @DisplayName("资本流逐点保留，方向与金额照抄，不复制成第二条收益")
    void flowsStayOnTheirPoint() {
        PerformanceModel.History history = PerformanceModel.history(OWNER, PERIOD, List.of(
                point(1L, 1_000L, "1000.00", "1000.00"),
                point(2L, 2_000L, "1000.00", "1500.00",
                        flow("f1", PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION, "500.00")),
                point(3L, 3_000L, "1500.00", "1450.00",
                        flow("f2", PerformanceModel.FlowKind.CAPITAL_WITHDRAWAL, "50.00"))));

        assertEquals(1, history.series().get(1).point().flows().size());
        assertEquals(PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION,
                history.series().get(1).point().flows().get(0).kind());
        assertEquals(0, history.series().get(2).point().flows().get(0).amount()
                .compareTo(new BigDecimal("50.00")));
        assertEquals(0, history.performance().returnRate().compareTo(BigDecimal.ZERO),
                "注资与提取都不是收益");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static PerformanceModel.Flow flow(String flowId, PerformanceModel.FlowKind kind,
                                              String amount) {
        return new PerformanceModel.Flow(flowId, OWNER, kind, new BigDecimal(amount),
                "证据-" + flowId);
    }

    private static PerformanceModel.Point point(long position, long atMillis,
                                                String navBefore, String navAfter,
                                                PerformanceModel.Flow... flows) {
        return new PerformanceModel.Point(OWNER, position, atMillis,
                ValuationModel.Status.COMPLETE, new BigDecimal(navBefore),
                new BigDecimal(navAfter), List.of(flows));
    }
}
