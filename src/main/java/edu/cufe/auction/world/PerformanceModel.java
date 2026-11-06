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

        if (status != Status.COMPLETE) {
            List<Segment> segments = new ArrayList<>();
            for (int i = 1; i < input.size(); i++) {
                segments.add(new Segment(input.get(i - 1).position(), input.get(i).position(),
                        status, null, null));
            }
            return new Performance(ownerId, periodId, status, null, null, null, segments);
        }

        BigDecimal index = ONE;
        BigDecimal peak = ONE;
        BigDecimal drawdown = BigDecimal.ZERO;
        List<Segment> segments = new ArrayList<>();
        for (int i = 1; i < input.size(); i++) {
            Point previous = input.get(i - 1);
            Point current = input.get(i);
            BigDecimal factor = current.navBeforeFlow()
                    .divide(previous.navAfterFlow(), MATH);
            BigDecimal segmentReturn = factor.subtract(ONE).setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN);
            index = index.multiply(factor, MATH);
            if (index.compareTo(peak) > 0) {
                peak = index;
            } else {
                BigDecimal fall = peak.subtract(index).divide(peak, MATH);
                if (fall.compareTo(drawdown) > 0) {
                    drawdown = fall;
                }
            }
            segments.add(new Segment(previous.position(), current.position(), Status.COMPLETE,
                    segmentReturn, index.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN)));
        }
        return new Performance(ownerId, periodId, Status.COMPLETE,
                index.subtract(ONE).setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN),
                index.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN),
                drawdown.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN),
                segments);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

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
