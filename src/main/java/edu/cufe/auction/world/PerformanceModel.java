package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * D1 时间加权收益：外部资本流、几何链接与流量剔除回撤。
 *
 * <p><b>能力边界</b>：纯计算，不产生新的表现余额、份额登记或可变收益账。外部资本流必须由宿主
 * 显式登记为 {@link Flow}；股票买卖、红利收现、费用支付都不是注资，本类绝不从现金差额推断流量。</p>
 *
 * <p><b>口径</b>：每个 {@link Point} 同时给出该截止资本流发生前与发生后的估值；相邻完整正权益
 * 点的区间因子为 {@code current.navBeforeFlow / previous.navAfterFlow}，逐因子几何链接得到指数与
 * 收益率，回撤只在流量剔除指数上计算。中间计算使用 {@link MathContext#DECIMAL128}，输出统一
 * 12 位小数、{@link RoundingMode#HALF_EVEN}。</p>
 *
 * <p><b>不适用</b>：任一估值点缺完整估值则整体 {@link Status#INCOMPLETE_VALUATION}；完整点出现
 * 零或负权益则 {@link Status#NON_POSITIVE_EQUITY}；不足两点则 {@link Status#NO_PERIOD}。
 * 三种不适用都不输出收益率、指数或回撤，也绝不跨缺口连乘。</p>
 *
 * <p><b>逐点历史</b>：{@link #history(String, String, List)} 与
 * {@link #calculate(String, String, List)} 共用同一遍链接，因此逐点指数、当前回撤与期间汇总
 * 不可能来自两套公式。</p>
 */
public final class PerformanceModel {

    /** 外部资本流方向。 */
    public enum FlowKind {
        /** 追加资本。 */
        CAPITAL_CONTRIBUTION,
        /** 提取资本。 */
        CAPITAL_WITHDRAWAL
    }

    /** 期间表现状态。 */
    public enum Status {
        /** 完整可用。 */
        COMPLETE,
        /** 期间内存在缺价或组织不支持的估值点。 */
        INCOMPLETE_VALUATION,
        /** 期间内存在零或负权益。 */
        NON_POSITIVE_EQUITY,
        /** 不足两个估值点。 */
        NO_PERIOD
    }

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final MathContext MATH = MathContext.DECIMAL128;
    private static final int OUTPUT_SCALE = 12;

    private PerformanceModel() {
    }

    /**
     * 一次外部资本流：只在真实权益转移时点登记，金额恒为正，方向由种类表达。
     *
     * @param flowId      运行内唯一标识
     * @param ownerId     财产主体
     * @param kind        流方向
     * @param amount      金额，正数且精确 2 位
     * @param evidenceRef 证据引用，非空
     */
    public record Flow(String flowId, String ownerId, FlowKind kind,
                       BigDecimal amount, String evidenceRef) {

        /** 校验标识、种类、正金额与证据。 */
        public Flow {
            requireText(flowId, "资本流标识");
            requireText(ownerId, "财产主体");
            Objects.requireNonNull(kind, "资本流方向");
            amount = money(amount, "资本流金额");
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("资本流金额必须为正：" + flowId);
            }
            requireText(evidenceRef, "资本流证据");
        }
    }

    /**
     * 一个估值点：资本流前后的估值都必须齐备。
     *
     * @param ownerId         财产主体
     * @param position        世界提交位置
     * @param atMillis        模拟时点
     * @param valuationStatus 该点的估值状态
     * @param navBeforeFlow   资本流发生前净值；非完整点为空
     * @param navAfterFlow    资本流发生后净值；非完整点为空
     * @param flows           该点登记的资本流
     */
    public record Point(String ownerId, long position, long atMillis,
                        ValuationModel.Status valuationStatus,
                        BigDecimal navBeforeFlow, BigDecimal navAfterFlow, List<Flow> flows) {

        /** 校验位置时点、完整点净值齐备、非完整点净值为空、集合深不可变。 */
        public Point {
            requireText(ownerId, "财产主体");
            if (position <= 0) {
                throw new IllegalArgumentException("估值点位置必须为正：" + ownerId);
            }
            if (atMillis < 0) {
                throw new IllegalArgumentException("估值点时点不能为负：" + ownerId);
            }
            Objects.requireNonNull(valuationStatus, "估值状态");
            List<Flow> copy = new ArrayList<>(Objects.requireNonNull(flows, "资本流"));
            if (valuationStatus == ValuationModel.Status.COMPLETE) {
                navBeforeFlow = money(navBeforeFlow, "流前净值");
                navAfterFlow = money(navAfterFlow, "流后净值");
            } else {
                if (navBeforeFlow != null || navAfterFlow != null) {
                    throw new IllegalArgumentException("非完整估值点的两个净值都必须为空：" + ownerId);
                }
            }
            flows = Collections.unmodifiableList(copy);
        }
    }

    /**
     * 一个相邻区间。
     *
     * @param fromPosition 起点位置
     * @param toPosition   终点位置
     * @param status       区间状态
     * @param returnRate   区间收益率；不可用时为空
     * @param index        该区间结束时的累计指数；不可用时为空
     */
    public record Segment(long fromPosition, long toPosition, Status status,
                          BigDecimal returnRate, BigDecimal index) {
    }

    /**
     * 一次期间表现。
     *
     * @param ownerId      财产主体
     * @param periodId     表现期间标识
     * @param status       期间状态
     * @param returnRate   期间收益率；不可用时为空
     * @param index        期间指数，基数 1；不可用时为空
     * @param maxDrawdown  流量剔除指数上的最大回撤；不可用时为空
     * @param segments     相邻区间
     */
    public record Performance(String ownerId, String periodId, Status status,
                              BigDecimal returnRate, BigDecimal index, BigDecimal maxDrawdown,
                              List<Segment> segments) {

        /** 校验身份字段、状态与空值组合，并冻结集合。 */
        public Performance {
            requireText(ownerId, "财产主体");
            requireText(periodId, "表现期间");
            Objects.requireNonNull(status, "期间状态");
            if (status != Status.COMPLETE
                    && (returnRate != null || index != null || maxDrawdown != null)) {
                throw new IllegalArgumentException("非完整期间不得输出收益、指数或回撤：" + periodId);
            }
            if (status == Status.COMPLETE
                    && (returnRate == null || index == null || maxDrawdown == null)) {
                throw new IllegalArgumentException("完整期间必须输出收益、指数与回撤：" + periodId);
            }
            segments = Collections.unmodifiableList(
                    new ArrayList<>(Objects.requireNonNull(segments, "区间")));
        }
    }

    /**
     * 一个确认点的展示采样：原样保留该 {@link Point}，并给出该点时的高精度指数与当前回撤。
     *
     * <p>指数与回撤要么同时给出、要么同时为空：不完整估值、非正权益与无期间一律为空，
     * 不许用别处的指数补一个画点。回撤是<b>该点时</b>的当前回撤（相对此前峰值），不是累计最大值。</p>
     *
     * <p><b>这是 12 位舍入后的显示值，不是权益状态</b>：{@code index = 0.000000000000} 只表示
     * 该点正指数舍入为零，不能据此判断权益为零（例如合法正权益从 1e42 降到 0.01 时，精确指数约 1e-44）；
     * {@code drawdown = 1.000000000000} 同样只表示 {@code (峰值 − 指数) / 峰值 ≥ 0.9999999999995}。
     * 零或负权益由 {@link Status#NON_POSITIVE_EQUITY} 表达，且那种期间不输出任何曲线点，
     * 两种情形不得混用。</p>
     *
     * @param point    该确认点的估值点
     * @param index    该点时的累计表现指数（scale 12，≥ 0）；不可用为空
     * @param drawdown 该点时的当前回撤（scale 12，0 ≤ d ≤ 1）；不可用为空
     */
    public record Series(Point point, BigDecimal index, BigDecimal drawdown) {

        /** 校验点非空、精度、缺失组合与显示值边界（0 与 1 是舍入结果，不是权益状态）。 */
        public Series {
            Objects.requireNonNull(point, "采样点");
            boolean hasCurve = index != null;
            if (hasCurve != (drawdown != null)) {
                throw new IllegalArgumentException("指数与回撤必须同时给出："
                        + point.position());
            }
            if (hasCurve) {
                if (point.valuationStatus() != ValuationModel.Status.COMPLETE) {
                    throw new IllegalArgumentException("非完整估值点不得携带指数："
                            + point.position());
                }
                if (index.scale() != OUTPUT_SCALE || drawdown.scale() != OUTPUT_SCALE) {
                    throw new IllegalArgumentException("指数与回撤必须精确到 12 位小数："
                            + point.position());
                }
                if (index.signum() < 0) {
                    throw new IllegalArgumentException("指数不得为负：" + point.position());
                }
                if (drawdown.signum() < 0 || drawdown.compareTo(ONE) > 0) {
                    throw new IllegalArgumentException("回撤必须落在 [0,1]：" + point.position());
                }
            }
        }
    }

    /**
     * 一次期间的逐点历史：既有 {@link Performance} 与逐点采样共用同一遍几何链接。
     *
     * @param performance 与 {@code calculate} 同构的期间结果
     * @param series      与输入估值点一一对应、顺序相同、长度相同的逐点采样
     */
    public record History(Performance performance, List<Series> series) {

        /** 校验身份一致、状态与空值组合一致，并冻结集合。 */
        public History {
            Objects.requireNonNull(performance, "期间表现");
            series = Collections.unmodifiableList(
                    new ArrayList<>(Objects.requireNonNull(series, "逐点采样")));
            boolean complete = performance.status() == Status.COMPLETE;
            for (Series item : series) {
                if (!item.point().ownerId().equals(performance.ownerId())) {
                    throw new IllegalArgumentException("采样点主体与期间不一致："
                            + item.point().position());
                }
                if ((item.index() != null) != complete) {
                    throw new IllegalArgumentException("采样点指数可用性与期间状态不一致："
                            + item.point().position());
                }
            }
        }
    }

    /**
     * 计算一个期间的流量剔除表现。
     *
     * <p>先完成全部结构校验（主体一致、位置严格递增、时点不倒退、流标识唯一、首点无流、
     * 流前后差额等于流量合计），再按"缺完整估值 → 非正权益 → 无期间"的顺序归因，
     * 避免过早返回掩盖后续非法输入。</p>
     *
     * @param ownerId  财产主体
     * @param periodId 表现期间标识
     * @param points   按位置递增的估值点
     * @return 期间表现
     */
    public static Performance calculate(String ownerId, String periodId, List<Point> points) {
        Linked linked = link(ownerId, periodId, points);
        return new Performance(ownerId, periodId, linked.status(), linked.returnRate(),
                linked.index(), linked.maxDrawdown(), linked.segments());
    }

    /**
     * 计算一个期间的逐点历史：期间汇总与逐点指数/当前回撤来自同一遍链接。
     *
     * <p>首点在完整期间下指数恰为 {@code 1.000000000000}、回撤恰为 {@code 0.000000000000}；
     * 其余点为前缀几何链接与"相对该点之前高精度峰值"的当前回撤。期间不完整时逐点指数与回撤
     * 全部为空，但已知的资金规模仍随 {@link Point} 返回，不填 0、不连成假曲线。</p>
     *
     * <p>逐点指数与回撤是 12 位舍入后的<b>显示值</b>：合法正权益的极端下跌可以让指数显示为
     * {@code 0.000000000000}、回撤显示为 {@code 1.000000000000}；这表示"小于显示精度"，
     * 不是零/负权益状态（那种期间根本没有曲线点）。</p>
     *
     * @param ownerId  财产主体
     * @param periodId 表现期间标识
     * @param points   按位置递增的估值点
     * @return 期间表现与逐点采样
     */
    public static History history(String ownerId, String periodId, List<Point> points) {
        Linked linked = link(ownerId, periodId, points);
        List<Series> series = new ArrayList<>(linked.points().size());
        for (int index = 0; index < linked.points().size(); index++) {
            series.add(new Series(linked.points().get(index),
                    linked.indexByPoint().get(index), linked.drawdownByPoint().get(index)));
        }
        return new History(new Performance(ownerId, periodId, linked.status(),
                linked.returnRate(), linked.index(), linked.maxDrawdown(), linked.segments()),
                series);
    }

    // ------------------------------------------------------------------
    // 内部：单遍链接
    // ------------------------------------------------------------------

    /**
     * 一次链接的中间结果：既有区间列表，以及逐点指数与逐点当前回撤（不可用状态时为 null）。
     * 它让 {@code calculate} 与 {@code history} 共用同一套校验、归因与舍入。
     */
    private record Linked(List<Point> points, List<Segment> segments,
                          List<BigDecimal> indexByPoint, List<BigDecimal> drawdownByPoint,
                          BigDecimal returnRate, BigDecimal index, BigDecimal maxDrawdown,
                          Status status) {
    }

    private static Linked link(String ownerId, String periodId, List<Point> points) {
        requireText(ownerId, "财产主体");
        requireText(periodId, "表现期间");
        List<Point> input = new ArrayList<>(Objects.requireNonNull(points, "估值点"));
        validateStructure(ownerId, input);

        boolean incomplete = input.stream()
                .anyMatch(point -> point.valuationStatus() != ValuationModel.Status.COMPLETE);
        boolean nonPositive = input.stream()
                .filter(point -> point.valuationStatus() == ValuationModel.Status.COMPLETE)
                .anyMatch(point -> point.navBeforeFlow().signum() <= 0
                        || point.navAfterFlow().signum() <= 0);

        Status status;
        if (incomplete) {
            status = Status.INCOMPLETE_VALUATION;
        } else if (nonPositive) {
            status = Status.NON_POSITIVE_EQUITY;
        } else if (input.size() < 2) {
            status = Status.NO_PERIOD;
        } else {
            status = Status.COMPLETE;
        }

        List<BigDecimal> indexByPoint = new ArrayList<>(Collections.nCopies(input.size(), null));
        List<BigDecimal> drawdownByPoint = new ArrayList<>(Collections.nCopies(input.size(), null));
        List<Segment> segments = new ArrayList<>();

        if (status != Status.COMPLETE) {
            for (int i = 1; i < input.size(); i++) {
                segments.add(new Segment(input.get(i - 1).position(), input.get(i).position(),
                        status, null, null));
            }
            return new Linked(Collections.unmodifiableList(input),
                    Collections.unmodifiableList(segments),
                    Collections.unmodifiableList(indexByPoint),
                    Collections.unmodifiableList(drawdownByPoint), null, null, null, status);
        }

        BigDecimal index = ONE;
        BigDecimal peak = ONE;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        indexByPoint.set(0, ONE.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN));
        drawdownByPoint.set(0, BigDecimal.ZERO.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN));
        for (int i = 1; i < input.size(); i++) {
            Point previous = input.get(i - 1);
            Point current = input.get(i);
            BigDecimal factor = current.navBeforeFlow()
                    .divide(previous.navAfterFlow(), MATH);
            BigDecimal segmentReturn = factor.subtract(ONE).setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN);
            index = index.multiply(factor, MATH);
            BigDecimal currentDrawdown = BigDecimal.ZERO;
            if (index.compareTo(peak) > 0) {
                peak = index;
            } else {
                currentDrawdown = peak.subtract(index).divide(peak, MATH);
                if (currentDrawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = currentDrawdown;
                }
            }
            segments.add(new Segment(previous.position(), current.position(), Status.COMPLETE,
                    segmentReturn, index.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN)));
            indexByPoint.set(i, index.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN));
            drawdownByPoint.set(i, currentDrawdown.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN));
        }
        return new Linked(Collections.unmodifiableList(input),
                Collections.unmodifiableList(segments),
                Collections.unmodifiableList(indexByPoint),
                Collections.unmodifiableList(drawdownByPoint),
                index.subtract(ONE).setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN),
                index.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN),
                maxDrawdown.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN),
                Status.COMPLETE);
    }

    private static void validateStructure(String ownerId, List<Point> points) {
        Set<String> flowIds = new HashSet<>();
        long previousPosition = 0L;
        long previousAt = 0L;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (!point.ownerId().equals(ownerId)) {
                throw new IllegalArgumentException("估值点主体与期间不一致：" + point.position());
            }
            if (point.position() <= previousPosition) {
                throw new IllegalArgumentException("估值点位置必须严格递增：" + point.position());
            }
            if (point.atMillis() < previousAt) {
                throw new IllegalArgumentException("估值点时点不得倒退：" + point.position());
            }
            previousPosition = point.position();
            previousAt = point.atMillis();
            for (Flow flow : point.flows()) {
                if (!flow.ownerId().equals(ownerId)) {
                    throw new IllegalArgumentException("资本流主体与期间不一致：" + flow.flowId());
                }
                if (!flowIds.add(flow.flowId())) {
                    throw new IllegalArgumentException("重复资本流标识：" + flow.flowId());
                }
            }
            if (i == 0 && !point.flows().isEmpty()) {
                throw new IllegalArgumentException("首个估值点不得携带资本流");
            }
            if (point.valuationStatus() == ValuationModel.Status.COMPLETE) {
                checkFlowRelation(point);
            }
        }
    }

    private static void checkFlowRelation(Point point) {
        BigDecimal signed = BigDecimal.ZERO.setScale(2);
        for (Flow flow : point.flows()) {
            signed = flow.kind() == FlowKind.CAPITAL_CONTRIBUTION
                    ? signed.add(flow.amount())
                    : signed.subtract(flow.amount());
        }
        BigDecimal gap = point.navAfterFlow().subtract(point.navBeforeFlow());
        if (gap.compareTo(signed) != 0) {
            throw new IllegalArgumentException("流前后差额与资本流合计不一致：" + point.position());
        }
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + "必须精确到分：" + value, failure);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
