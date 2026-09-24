package edu.cufe.auction.agent;

/** agent 类型（排行榜区分“人类 / AI / 噪音做市”的依据）。 */
public enum AgentType {

    /** 人类 broker：由 GUI/控制台手工下单。 */
    HUMAN,

    /** LLM 驱动的 AI 交易员。 */
    AI,

    /** 规则型噪音/做市 agent：提供基础流动性，让价格在没有人类操作时也能被发现。 */
    NOISE
}
