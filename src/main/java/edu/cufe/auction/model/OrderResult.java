package edu.cufe.auction.model;

import java.util.Collections;
import java.util.List;

/**
 * 一次 {@code submit} 的结果：受理与否、拒绝原因、产生的成交、订单终态。
 *
 * <p>只读快照，供调用线程安全使用。</p>
 */
public final class OrderResult {

    private final boolean accepted;
    private final String reason;
    private final Order order;
    private final List<Trade> trades;

    private OrderResult(boolean accepted, String reason, Order order, List<Trade> trades) {
        this.accepted = accepted;
        this.reason = reason;
        this.order = order;
        this.trades = Collections.unmodifiableList(trades);
    }

    /**
     * 构造受理结果（无业务取消原因）。
     *
     * <p>等价于 {@code accepted(order, trades, null)}；全部成交或正常挂簿时使用本重载。</p>
     *
     * @param order  受理的订单
     * @param trades 本次产生的成交（可能为空）
     * @return 结果对象
     */
    public static OrderResult accepted(Order order, List<Trade> trades) {
        return new OrderResult(true, null, order, trades);
    }

    /**
     * 构造带业务取消原因的受理结果。
     *
     * <p><b>什么时候用</b>：订单已经入场受理、也可能已经与第三方部分成交，之后因交易所业务规则
     * 取消其剩余量（例如自成交防护），而不是“入场前校验失败”。这类结果
     * {@link #isAccepted()} 仍为 true、{@link #getTrades()} 保留已发生的真实成交，
     * 原因只说明剩余量为何被取消。</p>
     *
     * @param order  受理的订单（状态通常是 {@link OrderStatus#CANCELLED}）
     * @param trades 本次已产生的成交（不可回滚，可能为空）
     * @param reason 业务取消原因，约定以稳定的 ASCII 代码开头，例如 {@code SELF_TRADE_PREVENTED}
     * @return 结果对象
     */
    public static OrderResult accepted(Order order, List<Trade> trades, String reason) {
        return new OrderResult(true, reason, order, trades);
    }

    /**
     * 构造拒绝结果。
     *
     * @param reason 拒绝原因（面向用户可读）
     * @return 结果对象
     */
    public static OrderResult rejected(String reason) {
        return new OrderResult(false, reason, null, List.of());
    }

    /** @return 是否被受理 */
    public boolean isAccepted() {
        return accepted;
    }

    /**
     * 结果原因。
     *
     * <p>两种情形都可读：</p>
     * <ul>
     *   <li>未受理（{@link #isAccepted()} 为 false）：整单被拒的原因，见 {@link #rejected(String)}；</li>
     *   <li>已受理但剩余量被业务规则取消：取消原因，见 {@link #accepted(Order, List, String)}，
     *       此时 {@link #getTrades()} 里的成交仍真实有效。</li>
     * </ul>
     *
     * @return 原因文本；正常全部受理且无业务取消时为 null
     */
    public String getReason() {
        return reason;
    }

    /** @return 被受理的订单；被拒绝时为 null */
    public Order getOrder() {
        return order;
    }

    /** @return 本次产生且不可变的成交列表 */
    public List<Trade> getTrades() {
        return trades;
    }

    /** @return 本次成交量；被拒绝时为 0 */
    public long getFilledQuantity() {
        return order == null ? 0L : order.getFilledQuantity();
    }

    /** @return 剩余未成交量；被拒绝时为 0 */
    public long getRemainingQuantity() {
        return order == null ? 0L : order.getRemainingQuantity();
    }

    /** @return 订单状态；被拒绝时为 {@link OrderStatus#REJECTED} */
    public OrderStatus getStatus() {
        return order == null ? OrderStatus.REJECTED : order.getStatus();
    }

    @Override
    public String toString() {
        if (!accepted) {
            return "REJECTED: " + reason;
        }
        return String.format("ACCEPTED %s 成交 %d 笔，状态 %s", order, trades.size(), order.getStatus());
    }
}
