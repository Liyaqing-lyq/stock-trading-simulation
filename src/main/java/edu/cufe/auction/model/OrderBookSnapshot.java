package edu.cufe.auction.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 订单簿只读快照：买盘由高到低、卖盘由低到高，均按价格-时间优先排序。
 *
 * <p>GUI 的深度展示、AI agent 的决策上下文都用它，避免把可变订单簿暴露给外部线程。</p>
 */
public final class OrderBookSnapshot {

    private final String symbol;
    private final List<PriceLevel> bids;
    private final List<PriceLevel> asks;
    private final BigDecimal lastPrice;
    private final long timestampMillis;

    /**
     * 构造快照。
     *
     * @param symbol          标的代码
     * @param bids            买盘（价格降序）
     * @param asks            卖盘（价格升序）
     * @param lastPrice       最新价，可为 null（尚无成交）
     * @param timestampMillis 快照时间戳
     */
    public OrderBookSnapshot(String symbol, List<PriceLevel> bids, List<PriceLevel> asks,
                             BigDecimal lastPrice, long timestampMillis) {
        this.symbol = symbol;
        this.bids = Collections.unmodifiableList(bids);
        this.asks = Collections.unmodifiableList(asks);
        this.lastPrice = lastPrice;
        this.timestampMillis = timestampMillis;
    }

    /** @return 标的代码 */
    public String getSymbol() {
        return symbol;
    }

    /** @return 买盘档位（价格降序） */
    public List<PriceLevel> getBids() {
        return bids;
    }

    /** @return 卖盘档位（价格升序） */
    public List<PriceLevel> getAsks() {
        return asks;
    }

    /** @return 最新成交价；无成交时为 null */
    public BigDecimal getLastPrice() {
        return lastPrice;
    }

    /** @return 快照时间戳（毫秒） */
    public long getTimestampMillis() {
        return timestampMillis;
    }

    /** @return 最优买价（买一）；买盘为空时返回 null */
    public BigDecimal getBestBid() {
        return bids.isEmpty() ? null : bids.get(0).getPrice();
    }

    /** @return 最优卖价（卖一）；卖盘为空时返回 null */
    public BigDecimal getBestAsk() {
        return asks.isEmpty() ? null : asks.get(0).getPrice();
    }

    /** @return 买卖价差；任一侧为空时返回 null */
    public BigDecimal getSpread() {
        BigDecimal bid = getBestBid();
        BigDecimal ask = getBestAsk();
        return (bid == null || ask == null) ? null : ask.subtract(bid);
    }

    @Override
    public String toString() {
        return String.format("OrderBookSnapshot[%s last=%s bid=%s ask=%s]",
                symbol, lastPrice, getBestBid(), getBestAsk());
    }
}
