package edu.cufe.auction.model;

/** 订单生命周期状态。 */
public enum OrderStatus {

    /** 已受理，尚未成交（限价单已挂入订单簿）。 */
    NEW,

    /** 部分成交，剩余部分仍在订单簿中。 */
    PARTIALLY_FILLED,

    /** 全部成交。 */
    FILLED,

    /** 被撤单（含市价单剩余部分）。 */
    CANCELLED,

    /** 校验失败被拒绝，从未进入订单簿。 */
    REJECTED;

    /**
     * 是否为终态（不再变化）。
     *
     * @return 终态返回 true
     */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }
}
