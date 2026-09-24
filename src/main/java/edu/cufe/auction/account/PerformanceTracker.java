package edu.cufe.auction.account;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 绩效统计：胜率、已实现盈亏、最大回撤（对应规格 5.3 的指标表）。
 *
 * <p>非线程安全，由所属 {@link Account} 在锁内驱动。</p>
 */
public final class PerformanceTracker {

    private BigDecimal realizedPnl = BigDecimal.ZERO;
    private int closedTradeCount;
    private int winningTradeCount;
    private BigDecimal peakEquity;
    private BigDecimal maxDrawdown = BigDecimal.ZERO;
    private BigDecimal lastEquity;

    /**
     * 记录一笔平仓（卖出）的已实现盈亏。
     *
     * @param pnlDelta 本笔盈亏
     */
    void recordClosedTrade(BigDecimal pnlDelta) {
        realizedPnl = realizedPnl.add(pnlDelta);
        closedTradeCount++;
        if (pnlDelta.signum() > 0) {
            winningTradeCount++;
        }
    }

    /**
     * 记录当前总权益，用于计算最大回撤。
     *
     * @param equity 当前总权益
     */
    void markEquity(BigDecimal equity) {
        lastEquity = equity;
        if (peakEquity == null || equity.compareTo(peakEquity) > 0) {
            peakEquity = equity;
        }
        if (peakEquity != null && peakEquity.signum() > 0) {
            BigDecimal drawdown = peakEquity.subtract(equity)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(peakEquity, 4, RoundingMode.HALF_UP);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }
    }

    /** @return 已实现盈亏合计 */
    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    /** @return 已平仓笔数 */
    public int getClosedTradeCount() {
        return closedTradeCount;
    }

    /** @return 盈利笔数 */
    public int getWinningTradeCount() {
        return winningTradeCount;
    }

    /** @return 胜率（0~1）；无平仓记录时返回 0 */
    public BigDecimal getWinRate() {
        if (closedTradeCount == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(winningTradeCount)
                .divide(BigDecimal.valueOf(closedTradeCount), 4, RoundingMode.HALF_UP);
    }

    /** @return 历史最高权益；未记录时 null */
    public BigDecimal getPeakEquity() {
        return peakEquity;
    }

    /** @return 最近一次记录的权益；未记录时 null */
    public BigDecimal getLastEquity() {
        return lastEquity;
    }

    /** @return 最大回撤（百分比，正数） */
    public BigDecimal getMaxDrawdown() {
        return maxDrawdown;
    }
}
