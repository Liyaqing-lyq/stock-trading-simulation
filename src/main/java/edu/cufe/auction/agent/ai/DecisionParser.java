package edu.cufe.auction.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

/**
 * 解析 LLM 返回的 JSON 决策，并做 schema 校验（规格 5.2 的“JSON schema validation”）。
 *
 * <p>容错点：模型可能返回 ```json 代码块、前后多余文字、字段缺失或数量非法；
 * 这里统一剥离并校验，任何非法输入都退化为 HOLD，绝不把脏数据送进引擎。</p>
 */
public final class DecisionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DecisionParser() {
    }

    /**
     * 解析决策 JSON。
     *
     * @param raw LLM 原始返回文本
     * @return 合法决策；无法解析时返回 HOLD
     */
    public static TradingDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return TradingDecision.hold("模型返回为空");
        }
        String json = stripCodeFence(raw.trim());
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return TradingDecision.hold("未找到 JSON 对象");
        }
        json = json.substring(start, end + 1);
        try {
            JsonNode node = MAPPER.readTree(json);
            String actionText = node.path("action").asText("HOLD").trim().toUpperCase();
            TradingDecision.Action action;
            try {
                action = TradingDecision.Action.valueOf(actionText);
            } catch (IllegalArgumentException ex) {
                return TradingDecision.hold("非法 action：" + actionText);
            }
            if (action == TradingDecision.Action.HOLD) {
                return TradingDecision.hold(node.path("reason").asText("模型选择观望"));
            }
            long quantity = node.path("quantity").asLong(0L);
            if (quantity <= 0) {
                return TradingDecision.hold("quantity 非法：" + quantity);
            }
            JsonNode priceNode = node.get("price");
            BigDecimal price = null;
            if (priceNode != null && !priceNode.isNull() && !priceNode.asText().isBlank()
                    && !"market".equalsIgnoreCase(priceNode.asText())) {
                price = new BigDecimal(priceNode.asText());
                if (price.signum() <= 0) {
                    return TradingDecision.hold("price 非法：" + price);
                }
            }
            return new TradingDecision(action, price, quantity, node.path("reason").asText(""));
        } catch (Exception ex) {
            return TradingDecision.hold("JSON 解析失败：" + ex.getMessage());
        }
    }

    private static String stripCodeFence(String text) {
        String result = text;
        if (result.startsWith("```")) {
            int firstNewline = result.indexOf('\n');
            if (firstNewline > 0) {
                result = result.substring(firstNewline + 1);
            }
            int fenceEnd = result.lastIndexOf("```");
            if (fenceEnd >= 0) {
                result = result.substring(0, fenceEnd);
            }
        }
        return result.trim();
    }
}
