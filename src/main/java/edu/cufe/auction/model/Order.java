package edu.cufe.auction.model;

import java.math.BigDecimal;

/**
 * 订单：由引擎根据 {@link OrderRequest} 生成，携带引擎分配的 id 与时间优先序号。
 *
 * <p>线程安全：除 {@code filledQuantity} / {@code status} 外全部字段不可变；
 * 这两个可变字段**只在 {@link edu.cufe.auction.engine.MatchingEngine} 的锁内**被修改，
 * 外部读取请通过引擎提供的快照方法。</p>
 */
public final class Order {

    private final long orderId;
    private final String agentId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    private final BigDecimal limitPrice;
    private final long quantity;
    private final long sequence;
    private long filledQuantity;
    private OrderStatus status;

    /**
     * 构造订单（由 {@link edu.cufe.auction.engine.MatchingEngine} 在受理请求后调用）。
     *
     * @param orderId    引擎分配的订单 id
     * @param agentId    下单方 agent 标识
     * @param symbol     标的代码
     * @param side       买卖方向
     * @param type       订单类型
     * @param limitPrice 限价，市价单必须为 null
     * @param quantity   数量，必须为正
     * @param sequence   时间优先序号
     * @throws IllegalArgumentException 参数非法
     */
    public Order(long orderId, String agentId, String symbol, Side side, OrderType type,
                 BigDecimal limitPrice, long quantity, long sequence) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须为正数");
        }
        if (type == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) {
            throw new IllegalArgumentException("限价单必须提供正的 limitPrice");
        }
        if (type == OrderType.MARKET && limitPrice != null) {
            throw new IllegalArgumentException("市价单不得携带 limitPrice");
        }
        this.orderId = orderId;
        this.agentId = agentId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.limitPrice = limitPrice;
        this.quantity = quantity;
        this.sequence = sequence;
        this.filledQuantity = 0L;
        this.status = OrderStatus.NEW;
    }

    /** @return 引擎分配的订单 id */
    public long getOrderId() {
        return orderId;
    }

    /** @return 下单方 agent 标识 */
    public String getAgentId() {
        return agentId;
    }

    /** @return 标的代码 */
    public String getSymbol() {
        return symbol;
    }

    /** @return 买卖方向 */
    public Side getSide() {
        return side;
    }

    /** @return 订单类型 */
    public OrderType getType() {
        return type;
    }

    /** @return 限价；市价单为 null。用于价格优先排序。 */
    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    /** @return 原始委托数量 */
    public long getQuantity() {
        return quantity;
    }

    /** @return 已成交数量 */
    public long getFilledQuantity() {
        return filledQuantity;
    }

    /** @return 剩余未成交数量 */
    public long getRemainingQuantity() {
        return quantity - filledQuantity;
    }

    /** @return 时间优先序号（全局单调递增） */
    public long getSequence() {
        return sequence;
    }

    /** @return 当前状态 */
    public OrderStatus getStatus() {
        return status;
    }

    /**
     * 判断该订单能否与给定价格成交。
     *
     * <p>市价单永远可以；限价单买入要求 {@code price <= limitPrice}，卖出要求
     * {@code price >= limitPrice}。</p>
     *
     * @param price 对手价
     * @return 可成交返回 true
     */
    public boolean canMatchAt(BigDecimal price) {
        if (type == OrderType.MARKET) {
            return true;
        }
        return side == Side.BUY
                ? price.compareTo(limitPrice) <= 0
                : price.compareTo(limitPrice) >= 0;
    }

    /** @return 是否已完全成交 */
    public boolean isFilled() {
        return filledQuantity >= quantity;
    }

    /**
     * 记录一笔成交。
     *
     * <p><b>仅供 {@link edu.cufe.auction.engine.MatchingEngine} 在撮合临界区内调用</b>；
     * 外部代码不要调用，否则会破坏订单状态一致性。</p>
     *
     * @param filled 本次成交数量，必须为正且不超过剩余量
     */
    public void addFill(long filled) {
        if (filled <= 0) {
            throw new IllegalArgumentException("成交量必须为正");
        }
        if (filled > getRemainingQuantity()) {
            throw new IllegalStateException("成交量超过剩余量：" + filled + " > " + getRemainingQuantity());
        }
        this.filledQuantity += filled;
        this.status = isFilled() ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    /**
     * 置为撤单状态。
     *
     * <p><b>仅供 {@link edu.cufe.auction.engine.MatchingEngine} 调用</b>。</p>
     *
     * @throws IllegalStateException 已终结的订单不可再撤销
     */
    public void markCancelled() {
        if (status.isTerminal()) {
            throw new IllegalStateException("订单已是终态 " + status + "，无法撤单");
        }
        this.status = OrderStatus.CANCELLED;
    }

    @Override
    public String toString() {
        String priceText = limitPrice == null ? "MARKET" : limitPrice.toPlainString();
        return String.format("#%d[%s %s %d/%d %s @ %s %s]",
                orderId, agentId, side, filledQuantity, quantity, symbol, priceText, status);
    }
}
