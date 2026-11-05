package edu.cufe.auction.engine;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 纯撮合计划器：只计算“该与谁、按什么价、成交多少”，不改任何状态。
 *
 * <h2>为什么要有它</h2>
 * <p>{@link MatchingEngine} 的旧撮合循环在读订单簿的同时直接改 {@code Order}、{@code Account}
 * 和订单簿，是“计算”和“提交”混在一起。把可提交的成交计划先算出来，才能让同一份算法既服务
 * 课程侧的旧入口，又服务需要先落可靠日志再提交的世界订单。本类只做纯函数：</p>
 * <ul>
 *   <li>不读 {@link edu.cufe.auction.model.Order} / {@code Account} 等可变对象，只吃值快照；</li>
 *   <li>不读系统时钟、不做 I/O、不回调调用方；</li>
 *   <li>入参列表与其中元素都不会被修改，返回的计划不可变。</li>
 * </ul>
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>报价按调用方给定的顺序依次消费；订单簿给出的顺序即价格优先、同价 FIFO；</li>
 *   <li>成交价取被动方（报价方）价格，主动方获得价格改善；</li>
 *   <li>限价单只有在对手价跨过其限价时才成交，市价单总是尝试成交；</li>
 *   <li>买方的可用预算是“本单剩余冻结 + 账户可用现金”，按每笔实际成交金额逐笔递减，
 *       因此扫多档抬价也不会把现金打成负数；卖方被动买单已持有正确冻结，不需要新预算；</li>
 *   <li>遇到同一受益主体的报价立即停止（{@link StopReason#SELF_TRADE}），不跳过它去吃后面的报价；
 *       此前已计划与第三方的成交仍保留。</li>
 * </ul>
 *
 * <p>停止原因只是计划层的诊断信息，不直接作为对外的拒单/业务原因文本；对外文本仍由
 * {@link MatchingEngine} 决定，避免改变既有对外口径。</p>
 */
public final class MatchPlanner {

    private MatchPlanner() {
        // 纯静态工具类
    }

    /**
     * 撮合停止原因。
     *
     * <p>仅描述“计划为什么停下”，其中只有 {@link #SELF_TRADE} 会让调用方取消主动单剩余量；
     * 其余情况由调用方按原有规则处理（限价挂簿、市价撤销剩余）。</p>
     */
    public enum StopReason {

        /** 主动量已全部成交。 */
        FILLED,

        /** 对手盘已无可用报价（含根本没有报价）。 */
        NO_OPPOSITE,

        /** 下一档对手价的报价未跨过主动限价，无法成交。 */
        LIMIT_NOT_CROSSING,

        /** 下一档最优报价属于同一受益主体，按禁止自成交规则停止。 */
        SELF_TRADE,

        /** 买方预算不足以再买一股，无法继续成交。 */
        BUDGET_EXHAUSTED
    }

    /**
     * 主动单的值快照。
     *
     * @param side       买卖方向
     * @param type       订单类型
     * @param limitPrice 限价；市价单必须为 {@code null}
     * @param quantity   本次待撮合数量（剩余量），必须为正
     * @param ownerId    受益主体标识；课程侧传 agentId，扩展侧传财产 ownerId
     * @param buyBudget  买方可用预算（本单剩余冻结 + 可用现金）；卖方可为 {@code null}
     */
    public record Incoming(Side side, OrderType type, BigDecimal limitPrice, long quantity,
                           String ownerId, BigDecimal buyBudget) {

        /**
         * 校验并构造主动单快照。
         *
         * @throws IllegalArgumentException 方向/类型为空、数量非正、限价与类型不匹配、
         *                                  受益主体为空，或买单缺少非负预算
         */
        public Incoming {
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(type, "type");
            if (ownerId == null || ownerId.isBlank()) {
                throw new IllegalArgumentException("受益主体标识不能为空");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("待撮合数量必须为正：" + quantity);
            }
            if (type == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) {
                throw new IllegalArgumentException("限价单必须提供正的限价");
            }
            if (type == OrderType.MARKET && limitPrice != null) {
                throw new IllegalArgumentException("市价单不得携带限价");
            }
            if (side == Side.BUY && (buyBudget == null || buyBudget.signum() < 0)) {
                throw new IllegalArgumentException("买方必须提供非负可用预算");
            }
        }
    }

    /**
     * 被动方报价的值快照（订单簿某一档的一笔挂单）。
     *
     * @param orderId  挂单 id，用于调用方回找原对象
     * @param ownerId  受益主体标识
     * @param price    挂单限价，即成交价
     * @param remaining 该挂单剩余可成交量，必须为正
     */
    public record RestingQuote(long orderId, String ownerId, BigDecimal price, long remaining) {

        /**
         * 校验并构造报价快照。
         *
         * @throws IllegalArgumentException 受益主体为空、价格非正或剩余量非正
         */
        public RestingQuote {
            if (ownerId == null || ownerId.isBlank()) {
                throw new IllegalArgumentException("报价的受益主体标识不能为空");
            }
            if (price == null || price.signum() <= 0) {
                throw new IllegalArgumentException("报价价格必须为正：" + price);
            }
            if (remaining <= 0) {
                throw new IllegalArgumentException("报价剩余量必须为正：" + remaining);
            }
        }
    }

    /**
     * 一笔计划成交。
     *
     * @param makerOrderId 被动方挂单 id
     * @param price        成交价（被动方价格）
     * @param quantity     成交数量，必须为正
     */
    public record Fill(long makerOrderId, BigDecimal price, long quantity) {

        /**
         * 校验并构造计划成交。
         *
         * @throws IllegalArgumentException 价格非正或数量非正
         */
        public Fill {
            if (price == null || price.signum() <= 0) {
                throw new IllegalArgumentException("成交价必须为正：" + price);
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("成交数量必须为正：" + quantity);
            }
        }
    }

    /**
     * 不可变成交计划。
     *
     * @param fills      按消费顺序排列的成交；不可变列表
     * @param remaining  计划执行后主动单的未成交量
     * @param stopReason 停止原因
     * @param selfMakerId 触发自成交拦截的挂单 id；仅当 {@code stopReason == SELF_TRADE} 时非空
     */
    public record Plan(List<Fill> fills, long remaining, StopReason stopReason, Long selfMakerId) {

        /**
         * 校验并构造计划，并复制成交列表。
         *
         * @throws IllegalArgumentException 列表为空引用、剩余量为负、停止原因为空，
         *                                  或自成交 id 与停止原因不匹配
         */
        public Plan {
            Objects.requireNonNull(fills, "fills");
            Objects.requireNonNull(stopReason, "stopReason");
            if (remaining < 0) {
                throw new IllegalArgumentException("计划剩余量不能为负：" + remaining);
            }
            fills = List.copyOf(fills);
            if ((stopReason == StopReason.SELF_TRADE) != (selfMakerId != null)) {
                throw new IllegalArgumentException("自成交 id 必须且只能随 SELF_TRADE 出现");
            }
        }

        /** @return 计划总成交量 */
        public long filledQuantity() {
            long total = 0L;
            for (Fill fill : fills) {
                total = Math.addExact(total, fill.quantity());
            }
            return total;
        }
    }

    /**
     * 计算一次撮合计划。
     *
     * <p>要求 {@code quotes} 已按价格优先、同价 FIFO 排好（{@link OrderBook} 的对手报价视图给出的
     * 就是该顺序）。本方法不修改 {@code incoming}、{@code quotes} 或其中的任何对象。</p>
     *
     * @param incoming 主动单快照
     * @param quotes   对手方报价快照，按价格优先 / FIFO 排列；可为空列表
     * @return 不可变成交计划
     * @throws IllegalArgumentException 入参为空或报价快照非法
     */
    public static Plan plan(Incoming incoming, List<RestingQuote> quotes) {
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(quotes, "quotes");

        List<Fill> fills = new ArrayList<>();
        long remaining = incoming.quantity();
        boolean isBuy = incoming.side() == Side.BUY;
        BigDecimal budget = isBuy ? incoming.buyBudget() : null;
        BigDecimal freezePrice = isBuy ? freezePriceOf(incoming, quotes) : null;
        StopReason reason = StopReason.NO_OPPOSITE;
        Long selfMakerId = null;

        for (RestingQuote quote : quotes) {
            Objects.requireNonNull(quote, "报价快照不能为 null");
            if (!crosses(incoming, quote)) {
                reason = StopReason.LIMIT_NOT_CROSSING;
                break;
            }
            if (quote.ownerId().equals(incoming.ownerId())) {
                reason = StopReason.SELF_TRADE;
                selfMakerId = quote.orderId();
                break;
            }
            long quoteRemaining = quote.remaining();
            while (quoteRemaining > 0) {
                long remainingBefore = remaining;
                long quantity = Math.min(remaining, quoteRemaining);
                if (budget != null) {
                    quantity = Math.min(quantity, affordableQuantity(budget, quote.price(), quantity));
                    if (quantity <= 0) {
                        reason = StopReason.BUDGET_EXHAUSTED;
                        break;
                    }
                }
                fills.add(new Fill(quote.orderId(), quote.price(), quantity));
                quoteRemaining -= quantity;
                remaining -= quantity;
                if (budget != null) {
                    budget = budgetAfterFill(budget, freezePrice, remainingBefore, remaining,
                            quote.price(), quantity);
                }
                if (remaining == 0) {
                    reason = StopReason.FILLED;
                    break;
                }
            }
            if (reason != StopReason.NO_OPPOSITE) {
                break;
            }
        }

        return new Plan(fills, remaining, remaining == 0 ? StopReason.FILLED : reason, selfMakerId);
    }

    /**
     * 受理时的每股冻结价：限价买用限价，市价买用当时卖一价（即最优报价）。
     *
     * <p>这是<b>受理层</b>的口径，计划层必须与之相同，否则逐笔释放的冻结金额与账户实际演化不一致。</p>
     *
     * @param incoming 主动单快照
     * @param quotes   已排序的对手报价
     * @return 每股冻结价；市价买且无报价时为 null
     */
    private static BigDecimal freezePriceOf(Incoming incoming, List<RestingQuote> quotes) {
        if (incoming.type() == OrderType.LIMIT) {
            return incoming.limitPrice();
        }
        return quotes.isEmpty() ? null : quotes.get(0).price();
    }

    /**
     * 一笔成交后的买方预算，精度对齐账户：每笔金额单独按 2 位小数 HALF_UP。
     *
     * <p>账户在受理时按冻结价预扣、成交时按 {@code round2(冻结价 × 成交股数)} 释放、
     * 按 {@code round2(成交价 × 成交股数)} 扣成本，剩余冻结按 {@code round2(冻结价 × 剩余股数)} 计算。
     * 于是“剩余冻结 + 可用现金”的变化量为：</p>
     * <pre>
     * -round2(成交价×qty) - round2(冻结价×qty前剩余) + round2(冻结价×qty后剩余) + round2(冻结价×qty)
     * </pre>
     * <p>在整分价格下它自然退化为 {@code -成交价×qty}；在分位以下报价下则与账户逐笔舍入一致，
     * 不会因为少算几分钱而多吃一股。</p>
     *
     * @param budget         成交前预算
     * @param freezePrice    每股冻结价
     * @param remainingBefore 成交前主动单剩余量
     * @param remainingAfter  成交后主动单剩余量
     * @param tradePrice     成交价
     * @param quantity       成交数量
     * @return 成交后预算
     */
    private static BigDecimal budgetAfterFill(BigDecimal budget, BigDecimal freezePrice,
                                              long remainingBefore, long remainingAfter,
                                              BigDecimal tradePrice, long quantity) {
        return budget.subtract(money(freezePrice, remainingBefore))
                .add(money(freezePrice, remainingAfter))
                .add(money(freezePrice, quantity))
                .subtract(money(tradePrice, quantity));
    }

    /**
     * 金额口径：与账户一致，2 位小数、HALF_UP。
     *
     * @param price    单价
     * @param quantity 数量
     * @return 金额
     */
    private static BigDecimal money(BigDecimal price, long quantity) {
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 判断主动单能否与该报价成交：市价单总能；限价买要求报价不高于限价，限价卖要求报价不低于限价。
     *
     * @param incoming 主动单快照
     * @param quote    报价快照
     * @return 可成交返回 true
     */
    private static boolean crosses(Incoming incoming, RestingQuote quote) {
        if (incoming.type() == OrderType.MARKET) {
            return true;
        }
        return incoming.side() == Side.BUY
                ? quote.price().compareTo(incoming.limitPrice()) <= 0
                : quote.price().compareTo(incoming.limitPrice()) >= 0;
    }

    /**
     * 给定预算与价格下可承受的最大股数，且不超过当前待成交量。
     *
     * <p>先用 {@link BigDecimal} 除法得到整数股数，再与当前待成交量取小，最后才转 {@code long}；
     * 这样即使预算极大（例如 1e17 元买 0.01 元的股票，商为 1e19），也不会先截断成 {@code long}
     * 而溢出成负数、导致一股都成交不了。</p>
     *
     * @param budget 剩余可用预算
     * @param price  成交价
     * @param cap    当前待成交量上限
     * @return 可成交股数，可能为 0
     */
    private static long affordableQuantity(BigDecimal budget, BigDecimal price, long cap) {
        BigDecimal affordable = budget.divide(price, 0, RoundingMode.DOWN);
        if (affordable.compareTo(BigDecimal.valueOf(cap)) >= 0) {
            return cap;
        }
        return affordable.longValueExact();
    }
}
