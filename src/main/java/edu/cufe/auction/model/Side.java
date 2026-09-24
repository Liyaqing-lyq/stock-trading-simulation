package edu.cufe.auction.model;

/** 订单方向。 */
public enum Side {

    /** 买入（Bid 侧）。 */
    BUY,

    /** 卖出（Ask 侧）。 */
    SELL;

    /**
     * 返回反向方向。
     *
     * @return BUY 返回 SELL，SELL 返回 BUY
     */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
