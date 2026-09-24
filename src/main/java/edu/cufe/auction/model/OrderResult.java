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
     * 构造受理结果。
     *
     * @param order  受理的订单
     * @param trades 本次产生的成交（可能为空）
     * @return 结果对象
     */
    public static OrderResult accepted(Order order, List<Trade> trades) {
        return new OrderResult(true, null, order, trades);
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

    /** @return 拒绝原因；被受理时为 null */
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
