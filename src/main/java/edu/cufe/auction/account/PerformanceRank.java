package edu.cufe.auction.account;

import java.math.BigDecimal;

/** 排行榜一行：期末排名结果（对应规格第 10 节）。 */
public final class PerformanceRank {

    private final int rank;
    private final String agentId;
    private final String displayName;
    private final BigDecimal initialBalance;
    private final BigDecimal equity;
    private final BigDecimal returnRate;
    private final BigDecimal realizedPnl;
    private final BigDecimal maxDrawdown;
    private final int closedTradeCount;
    private final BigDecimal winRate;

    /**
     * 构造排名行。
     *
     * @param rank            名次（从 1 开始）
     * @param agentId         agent 标识
     * @param displayName     展示名
     * @param initialBalance  初始资金
     * @param equity          期末总权益
     * @param returnRate      收益率（0.05 表示 5%）
     * @param realizedPnl     已实现盈亏
     * @param maxDrawdown     最大回撤（百分比，正数）
     * @param closedTradeCount 已平仓笔数
     * @param winRate         胜率（0~1）
     */
    public PerformanceRank(int rank, String agentId, String displayName, BigDecimal initialBalance,
                           BigDecimal equity, BigDecimal returnRate, BigDecimal realizedPnl,
                           BigDecimal maxDrawdown, int closedTradeCount, BigDecimal winRate) {
        this.rank = rank;
        this.agentId = agentId;
        this.displayName = displayName;
        this.initialBalance = initialBalance;
        this.equity = equity;
        this.returnRate = returnRate;
        this.realizedPnl = realizedPnl;
        this.maxDrawdown = maxDrawdown;
        this.closedTradeCount = closedTradeCount;
        this.winRate = winRate;
    }

    /** @return 名次（从 1 开始） */
    public int getRank() {
        return rank;
    }

    /** @return agent 标识 */
    public String getAgentId() {
        return agentId;
    }

    /** @return 展示名 */
    public String getDisplayName() {
        return displayName;
    }

    /** @return 初始资金 */
    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    /** @return 期末总权益 */
    public BigDecimal getEquity() {
        return equity;
    }

    /** @return 收益率（0.05 = 5%） */
    public BigDecimal getReturnRate() {
        return returnRate;
    }

    /** @return 已实现盈亏 */
    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    /** @return 最大回撤（百分比） */
    public BigDecimal getMaxDrawdown() {
        return maxDrawdown;
    }

    /** @return 已平仓笔数 */
    public int getClosedTradeCount() {
        return closedTradeCount;
    }

    /** @return 胜率（0~1） */
    public BigDecimal getWinRate() {
        return winRate;
    }

    @Override
    public String toString() {
        return String.format("%d. %-12s 权益 %12s 收益率 %8s%% 回撤 %7s%% 胜率 %6s%%",
                rank, displayName, equity.toPlainString(),
                returnRate.multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP),
                maxDrawdown.setScale(2, java.math.RoundingMode.HALF_UP),
                winRate.multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP));
    }
}
