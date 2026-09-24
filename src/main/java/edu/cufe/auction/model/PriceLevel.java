package edu.cufe.auction.model;

import java.math.BigDecimal;

/** 盘口某一档价格上的聚合数量，用于深度展示。 */
public final class PriceLevel {

    private final BigDecimal price;
    private final long quantity;
    private final int orderCount;

    /**
     * 构造价格档位。
     *
     * @param price      档位价格
     * @param quantity   该价位挂单总量
     * @param orderCount 该价位订单笔数
     */
    public PriceLevel(BigDecimal price, long quantity, int orderCount) {
        this.price = price;
        this.quantity = quantity;
        this.orderCount = orderCount;
    }

    /** @return 档位价格 */
    public BigDecimal getPrice() {
        return price;
    }

    /** @return 该价位挂单总量 */
    public long getQuantity() {
        return quantity;
    }

    /** @return 该价位订单笔数 */
    public int getOrderCount() {
        return orderCount;
    }

    @Override
    public String toString() {
        return String.format("%s × %d（%d 笔）", price.toPlainString(), quantity, orderCount);
    }
}
