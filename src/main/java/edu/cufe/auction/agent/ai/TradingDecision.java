package edu.cufe.auction.agent.ai;

import java.math.BigDecimal;

/** LLM（或规则）给出的交易决策，对应规格 5.2 的 JSON 结构。 */
public final class TradingDecision {

    /** 决策动作。 */
    public enum Action {
        /** 买入。 */
        BUY,
        /** 卖出。 */
        SELL,
        /** 观望。 */
        HOLD
    }

    private final Action action;
    private final BigDecimal price;
    private final long quantity;
    private final String reason;

    /**
     * 构造决策。
     *
     * @param action   动作
     * @param price    限价；HOLD 或市价时为 null
     * @param quantity 数量；HOLD 时为 0
     * @param reason   决策理由（用于日志与报告分析）
     */
    public TradingDecision(Action action, BigDecimal price, long quantity, String reason) {
        this.action = action;
        this.price = price;
        this.quantity = quantity;
        this.reason = reason;
    }

    /**
     * 构造观望决策。
     *
     * @param reason 理由
     * @return HOLD 决策
     */
    public static TradingDecision hold(String reason) {
        return new TradingDecision(Action.HOLD, null, 0L, reason);
    }

    /** @return 动作 */
    public Action getAction() {
        return action;
    }

    /** @return 限价；可能为 null（市价或 HOLD） */
    public BigDecimal getPrice() {
        return price;
    }

    /** @return 数量 */
    public long getQuantity() {
        return quantity;
    }

    /** @return 决策理由 */
    public String getReason() {
        return reason;
    }

    /** @return 是否为观望 */
    public boolean isHold() {
        return action == Action.HOLD;
    }

    @Override
    public String toString() {
        return String.format("%s %s × %d%s", action, price == null ? "MARKET" : price.toPlainString(),
                quantity, reason == null || reason.isBlank() ? "" : "（" + reason + "）");
    }
}
