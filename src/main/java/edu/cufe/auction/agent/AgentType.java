package edu.cufe.auction.agent;

/** agent 类型（排行榜区分“人类 / AI / 噪音做市 / 价值 / 逆势 / 做市”的依据）。 */
public enum AgentType {

    /** 人类 broker：由 GUI/控制台手工下单。 */
    HUMAN,

    /** LLM 驱动的 AI 交易员。 */
    AI,

    /** 规则型噪音/做市 agent：提供基础流动性，让价格在没有人类操作时也能被发现。 */
    NOISE,

    /** 价值型 agent：以昨收价为估值锚，低买高卖，抑制价格长期偏离基本面。 */
    VALUE,

    /** 逆势型 agent：短期均值回归，给动量 / AI agent 提供对手盘。 */
    CONTRARIAN,

    /** 做市商 agent：双边报价并做库存偏斜，收窄价差、提供持续流动性。 */
    MARKET_MAKER
}
