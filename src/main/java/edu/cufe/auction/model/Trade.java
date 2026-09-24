package edu.cufe.auction.model;

import java.math.BigDecimal;

/**
 * 成交记录：一次撮合产生的一条不可变成交。
 *
 * <p>价格口径：按**被动方（挂在订单簿上的那一方）的价格**成交，与真实交易所一致。
 * 例如买价 150 与挂着的卖价 149 相遇，成交价为 149（买方获得 1 元价格改善）。</p>
 */
public final class Trade {

    private final long tradeId;
    private final String symbol;
    private final BigDecimal price;
    private final long quantity;
    private final long buyOrderId;
    private final String buyAgentId;
    private final long sellOrderId;
    private final String sellAgentId;
    private final long timestampMillis;

    /**
     * 构造成交记录。
     *
     * @param tradeId       成交序号
     * @param symbol        标的代码
     * @param price         成交价
     * @param quantity      成交量
     * @param buyOrderId    买方订单 id
     * @param buyAgentId    买方 agent 标识
     * @param sellOrderId   卖方订单 id
     * @param sellAgentId   卖方 agent 标识
     * @param timestampMillis 成交时间戳（毫秒）
     */
    public Trade(long tradeId, String symbol, BigDecimal price, long quantity,
                 long buyOrderId, String buyAgentId, long sellOrderId, String sellAgentId,
                 long timestampMillis) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.price = price;
        this.quantity = quantity;
        this.buyOrderId = buyOrderId;
        this.buyAgentId = buyAgentId;
        this.sellOrderId = sellOrderId;
        this.sellAgentId = sellAgentId;
        this.timestampMillis = timestampMillis;
    }

    /** @return 成交序号 */
    public long getTradeId() {
        return tradeId;
    }

    /** @return 标的代码 */
    public String getSymbol() {
        return symbol;
    }

    /** @return 成交价 */
    public BigDecimal getPrice() {
        return price;
    }

    /** @return 成交量（股数） */
    public long getQuantity() {
        return quantity;
    }

    /** @return 买方订单 id */
    public long getBuyOrderId() {
        return buyOrderId;
    }

    /** @return 买方 agent 标识 */
    public String getBuyAgentId() {
        return buyAgentId;
    }

    /** @return 卖方订单 id */
    public long getSellOrderId() {
        return sellOrderId;
    }

    /** @return 卖方 agent 标识 */
    public String getSellAgentId() {
        return sellAgentId;
    }

    /** @return 成交时间戳（毫秒） */
    public long getTimestampMillis() {
        return timestampMillis;
    }

    /** @return 成交金额（价格 × 数量） */
    public BigDecimal getNotional() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }

    @Override
    public String toString() {
        return String.format("T#%d %s %d 股 @ %s （买 %s / 卖 %s）",
                tradeId, symbol, quantity, price.toPlainString(), buyAgentId, sellAgentId);
    }
}
