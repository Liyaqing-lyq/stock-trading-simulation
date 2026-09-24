package edu.cufe.auction.agent;

import edu.cufe.auction.model.MarketSnapshot;

/**
 * 交易 agent 抽象：只依赖 {@link edu.cufe.auction.engine.OrderGateway} 下单，
 * 不直接持有撮合引擎，便于单元测试与替换实现。
 */
public interface TradingAgent {

    /** @return agent 唯一标识（与账户 id 一致） */
    String getAgentId();

    /** @return 展示名（GUI/排行榜用） */
    String getDisplayName();

    /** @return agent 类型 */
    AgentType getType();

    /**
     * 收到行情快照（在行情广播线程中调用）。
     *
     * <p>实现必须快速返回，不得做阻塞 I/O；LLM 调用等耗时操作放在 {@link #onTick()}。</p>
     *
     * @param snapshot 行情快照
     */
    void onMarketSnapshot(MarketSnapshot snapshot);

    /**
     * 决策周期回调（由定时器线程调用）：在这里决定是否下单。
     */
    void onTick();
}
