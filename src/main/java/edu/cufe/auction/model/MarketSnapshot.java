package edu.cufe.auction.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 广播给所有 agent 与 GUI 的行情快照：最新价 + 深度 + 累计成交量。
 *
 * <p>不可变；由 {@link edu.cufe.auction.market.MarketDataPublisher} 在引擎锁外发布，
 * 监听者实现必须自行保证线程安全（GUI 端需切回 JavaFX 线程）。</p>
 */
public final class MarketSnapshot {

    private final String symbol;
    private final BigDecimal lastPrice;
    private final BigDecimal previousClose;
    private final BigDecimal bestBid;
    private final BigDecimal bestAsk;
    private final long cumulativeVolume;
    private final List<PriceLevel> bids;
    private final List<PriceLevel> asks;
    private final long timestampMillis;

    /**
     * 构造行情快照。
     *
     * @param symbol           标的代码
     * @param lastPrice        最新价；无成交时为 null
     * @param previousClose    参考价（配置里的初始价），用于计算涨跌幅
     * @param bestBid          买一价，可为 null
     * @param bestAsk          卖一价，可为 null
     * @param cumulativeVolume 累计成交量
     * @param bids             买盘档位（价格降序）
     * @param asks             卖盘档位（价格升序）
     * @param timestampMillis  时间戳
     */
    public MarketSnapshot(String symbol, BigDecimal lastPrice, BigDecimal previousClose,
                          BigDecimal bestBid, BigDecimal bestAsk, long cumulativeVolume,
                          List<PriceLevel> bids, List<PriceLevel> asks, long timestampMillis) {
        this.symbol = symbol;
        this.lastPrice = lastPrice;
        this.previousClose = previousClose;
        this.bestBid = bestBid;
        this.bestAsk = bestAsk;
        this.cumulativeVolume = cumulativeVolume;
        this.bids = Collections.unmodifiableList(bids);
        this.asks = Collections.unmodifiableList(asks);
        this.timestampMillis = timestampMillis;
    }

    /** @return 标的代码 */
    public String getSymbol() {
        return symbol;
    }

    /** @return 最新成交价；无成交时 null */
    public BigDecimal getLastPrice() {
        return lastPrice;
    }

    /** @return 参考价/昨收 */
    public BigDecimal getPreviousClose() {
        return previousClose;
    }

    /** @return 买一价 */
    public BigDecimal getBestBid() {
        return bestBid;
    }

    /** @return 卖一价 */
    public BigDecimal getBestAsk() {
        return bestAsk;
    }

    /** @return 累计成交量 */
    public long getCumulativeVolume() {
        return cumulativeVolume;
    }

    /** @return 买盘档位（价格降序） */
    public List<PriceLevel> getBids() {
        return bids;
    }

    /** @return 卖盘档位（价格升序） */
    public List<PriceLevel> getAsks() {
        return asks;
    }

    /** @return 时间戳（毫秒） */
    public long getTimestampMillis() {
        return timestampMillis;
    }

    /**
     * 涨跌幅百分比（相对参考价）。
     *
     * @return 例如 1.25 表示 +1.25%；无最新价或参考价非正时返回 0
     */
    public BigDecimal getChangePercent() {
        if (lastPrice == null || previousClose == null || previousClose.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return lastPrice.subtract(previousClose)
                .multiply(BigDecimal.valueOf(100))
                .divide(previousClose, 4, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public String toString() {
        return String.format("MarketSnapshot[%s last=%s chg=%s%% vol=%d bid=%s ask=%s]",
                symbol, lastPrice, getChangePercent(), cumulativeVolume, bestBid, bestAsk);
    }
}
