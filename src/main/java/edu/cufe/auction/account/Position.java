package edu.cufe.auction.account;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 单一标的持仓。
 *
 * <p>可变对象，**所有字段只在所属 {@link Account} 的锁内修改**；
 * 外部只能拿到 {@link #copy()} 返回的快照。</p>
 */
public final class Position {

    private static final int COST_SCALE = 4;

    private final String symbol;
    private long quantity;
    private long frozenQuantity;
    private BigDecimal averageCost;
    private BigDecimal realizedPnl;

    Position(String symbol) {
        this.symbol = symbol;
        this.quantity = 0L;
        this.frozenQuantity = 0L;
        this.averageCost = BigDecimal.ZERO;
        this.realizedPnl = BigDecimal.ZERO;
    }

    private Position(Position other) {
        this.symbol = other.symbol;
        this.quantity = other.quantity;
        this.frozenQuantity = other.frozenQuantity;
        this.averageCost = other.averageCost;
        this.realizedPnl = other.realizedPnl;
    }

    /** @return 持仓快照 */
    public Position copy() {
        return new Position(this);
    }

    /** @return 标的代码 */
    public String getSymbol() {
        return symbol;
    }

    /** @return 总持仓数量 */
    public long getQuantity() {
        return quantity;
    }

    /** @return 被挂单冻结（待成交/待撤）的数量 */
    public long getFrozenQuantity() {
        return frozenQuantity;
    }

    /** @return 可卖数量（总持仓 − 冻结） */
    public long getAvailableQuantity() {
        return quantity - frozenQuantity;
    }

    /** @return 摊薄持仓成本；空仓时为 0 */
    public BigDecimal getAverageCost() {
        return averageCost;
    }

    /** @return 该标的已实现盈亏 */
    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    /**
     * 计算浮动盈亏。
     *
     * @param lastPrice 最新价
     * @return (最新价 − 成本价) × 持仓量
     */
    public BigDecimal unrealizedPnl(BigDecimal lastPrice) {
        if (quantity == 0 || averageCost.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return lastPrice.subtract(averageCost).multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP);
    }

    void freeze(long qty) {
        if (qty <= 0 || qty > getAvailableQuantity()) {
            throw new IllegalStateException("非法冻结数量 " + qty + "（可用 " + getAvailableQuantity() + "）");
        }
        frozenQuantity += qty;
    }

    void unfreeze(long qty) {
        if (qty <= 0 || qty > frozenQuantity) {
            throw new IllegalStateException("非法解冻数量 " + qty + "（冻结 " + frozenQuantity + "）");
        }
        frozenQuantity -= qty;
    }

    void addShares(long qty, BigDecimal cost) {
        long newQty = quantity + qty;
        BigDecimal newCost = averageCost.multiply(BigDecimal.valueOf(quantity)).add(cost)
                .divide(BigDecimal.valueOf(newQty), COST_SCALE, RoundingMode.HALF_UP);
        this.quantity = newQty;
        this.averageCost = newCost;
    }

    /**
     * 卖出：扣减持仓并累计已实现盈亏。
     *
     * @param qty   卖出数量
     * @param price 卖出价
     * @return 本次已实现盈亏
     */
    BigDecimal removeShares(long qty, BigDecimal price) {
        BigDecimal pnl = price.subtract(averageCost).multiply(BigDecimal.valueOf(qty))
                .setScale(2, RoundingMode.HALF_UP);
        this.quantity -= qty;
        this.realizedPnl = this.realizedPnl.add(pnl);
        if (this.quantity == 0) {
            this.averageCost = BigDecimal.ZERO;
        }
        return pnl;
    }

    @Override
    public String toString() {
        return String.format("%s × %d（冻结 %d，成本 %s，已实现盈亏 %s）",
                symbol, quantity, frozenQuantity, averageCost.toPlainString(),
                realizedPnl.toPlainString());
    }
}
