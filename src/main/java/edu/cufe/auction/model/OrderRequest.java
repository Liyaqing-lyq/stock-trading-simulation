package edu.cufe.auction.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 下单请求：由 agent 构造，交给 {@link edu.cufe.auction.engine.OrderGateway}。
 *
 * <p>不可变对象，可在多线程间自由传递。所有字段在构造时完成校验，
 * 因此引擎内部只需再做资金/持仓层面的业务校验（见
 * {@link edu.cufe.auction.engine.MatchingEngine#submit(OrderRequest)}）。</p>
 */
public final class OrderRequest {

    private final String agentId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    private final BigDecimal price;
    private final long quantity;

    private OrderRequest(String agentId, String symbol, Side side, OrderType type,
                         BigDecimal price, long quantity) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 不能为空");
        }
        Objects.requireNonNull(side, "side 不能为 null");
        Objects.requireNonNull(type, "type 不能为 null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须为正数，实际=" + quantity);
        }
        if (type == OrderType.LIMIT) {
            if (price == null) {
                throw new IllegalArgumentException("限价单必须提供 price");
            }
            if (price.signum() <= 0) {
                throw new IllegalArgumentException("限价单价格必须为正数，实际=" + price);
            }
        } else if (price != null) {
            throw new IllegalArgumentException("市价单不得指定 price");
        }
        this.agentId = agentId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
    }

    /**
     * 构造限价单请求。
     *
     * @param agentId  下单方 agent 标识
     * @param symbol   标的代码
     * @param side     买卖方向
     * @param price    限价，必须为正
     * @param quantity 数量，必须为正
     * @return 下单请求
     */
    public static OrderRequest limit(String agentId, String symbol, Side side,
                                     BigDecimal price, long quantity) {
        return new OrderRequest(agentId, symbol, side, OrderType.LIMIT, price, quantity);
    }

    /**
     * 构造市价单请求。
     *
     * @param agentId  下单方 agent 标识
     * @param symbol   标的代码
     * @param side     买卖方向
     * @param quantity 数量，必须为正
     * @return 下单请求
     */
    public static OrderRequest market(String agentId, String symbol, Side side, long quantity) {
        return new OrderRequest(agentId, symbol, side, OrderType.MARKET, null, quantity);
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

    /** @return 限价；市价单返回 null */
    public BigDecimal getPrice() {
        return price;
    }

    /** @return 数量 */
    public long getQuantity() {
        return quantity;
    }

    @Override
    public String toString() {
        return type == OrderType.LIMIT
                ? String.format("%s %s %s %s @ %s", agentId, side, quantity, symbol, price.toPlainString())
                : String.format("%s %s %s %s @ MARKET", agentId, side, quantity, symbol);
    }
}
