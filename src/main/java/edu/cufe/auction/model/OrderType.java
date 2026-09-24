package edu.cufe.auction.model;

/** 订单类型。 */
public enum OrderType {

    /** 限价单：指定价格，未成交部分进入订单簿挂单。 */
    LIMIT,

    /** 市价单：立即以对手方最优价成交，未成交部分直接取消（不挂单）。 */
    MARKET
}
