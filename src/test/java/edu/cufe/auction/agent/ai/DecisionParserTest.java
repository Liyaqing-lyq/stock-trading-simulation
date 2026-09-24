package edu.cufe.auction.agent.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LLM 决策 JSON 解析与 schema 校验测试（脏数据必须退化为 HOLD，绝不能进引擎）。 */
class DecisionParserTest {

    @Test
    @DisplayName("标准 JSON 正常解析")
    void parsesValidJson() {
        TradingDecision decision = DecisionParser.parse(
                "{\"action\":\"BUY\",\"price\":175.5,\"quantity\":100,\"reason\":\"趋势向上\"}");
        assertEquals(TradingDecision.Action.BUY, decision.getAction());
        assertEquals(0, new BigDecimal("175.5").compareTo(decision.getPrice()));
        assertEquals(100L, decision.getQuantity());
        assertEquals("趋势向上", decision.getReason());
    }

    @Test
    @DisplayName("剥离 Markdown 代码块与多余文字")
    void stripsCodeFenceAndNoise() {
        String raw = "好的，我的决策如下：\n```json\n"
                + "{\"action\":\"SELL\",\"price\":170,\"quantity\":50,\"reason\":\"止盈\"}\n```\n请执行。";
        TradingDecision decision = DecisionParser.parse(raw);
        assertEquals(TradingDecision.Action.SELL, decision.getAction());
        assertEquals(50L, decision.getQuantity());
    }

    @Test
    @DisplayName("HOLD 决策不带价格与数量")
    void parsesHold() {
        TradingDecision decision = DecisionParser.parse("{\"action\":\"HOLD\",\"reason\":\"方向不明\"}");
        assertTrue(decision.isHold());
        assertNull(decision.getPrice());
        assertEquals(0L, decision.getQuantity());
    }

    @Test
    @DisplayName("price 为 market 字符串时视为市价单")
    void marketPriceTextMeansMarketOrder() {
        TradingDecision decision = DecisionParser.parse(
                "{\"action\":\"BUY\",\"price\":\"market\",\"quantity\":10}");
        assertEquals(TradingDecision.Action.BUY, decision.getAction());
        assertNull(decision.getPrice());
    }

    @Test
    @DisplayName("非法 action / 数量 / 价格 / 非 JSON 一律退化为 HOLD")
    void invalidInputsFallBackToHold() {
        assertTrue(DecisionParser.parse("{\"action\":\"YOLO\",\"quantity\":10}").isHold());
        assertTrue(DecisionParser.parse("{\"action\":\"BUY\",\"price\":10,\"quantity\":0}").isHold());
        assertTrue(DecisionParser.parse("{\"action\":\"BUY\",\"price\":-5,\"quantity\":10}").isHold());
        assertTrue(DecisionParser.parse("{\"action\":\"BUY\",\"quantity\":-3}").isHold());
        assertTrue(DecisionParser.parse("今天行情不错，我决定买入").isHold());
        assertTrue(DecisionParser.parse("").isHold());
        assertTrue(DecisionParser.parse(null).isHold());
    }

    @Test
    @DisplayName("提示词包含行情、账户与 JSON 约束")
    void promptContainsContext() {
        var account = new edu.cufe.auction.account.Account("ai-1", "AI交易员-1", new BigDecimal("50000"));
        account.seedPosition("AAPL", 100, new BigDecimal("170.00"));
        var snapshot = new edu.cufe.auction.model.MarketSnapshot("AAPL", new BigDecimal("176.00"),
                new BigDecimal("175.50"), new BigDecimal("175.90"), new BigDecimal("176.10"),
                1200L, java.util.List.of(), java.util.List.of(), System.currentTimeMillis());

        String user = PromptBuilder.buildUserPrompt(snapshot, account, 5);
        assertTrue(user.contains("AAPL"));
        assertTrue(user.contains("176.00"));
        assertTrue(user.contains("可用现金"));
        assertTrue(user.contains("可卖数量: 100".replace(':', '：')) || user.contains("可卖数量：100"));
        assertTrue(PromptBuilder.systemPrompt().contains("JSON"));
    }
}
