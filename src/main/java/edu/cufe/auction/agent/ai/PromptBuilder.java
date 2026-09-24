package edu.cufe.auction.agent.ai;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.PriceLevel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 提示词构造器（规格 5.2 的 prompt 模板落地）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>把行情、账户、持仓、盘口都压成短文本，控制 token；</li>
 *   <li>明确要求“只回 JSON、不要解释”，配合 {@code response_format=json_object}；</li>
 *   <li>把可用现金与可卖数量写进提示词，减少模型给出必然被拒的订单。</li>
 * </ul>
 */
public final class PromptBuilder {

    private static final String SYSTEM_PROMPT = String.join("\n",
            "你是一名在连续竞价市场中交易的量化交易员。",
            "你只能看到给出的行情与账户信息，目标是在控制风险的前提下提高账户总权益。",
            "只输出一个 JSON 对象，不要输出任何解释文字或 Markdown 代码块。",
            "JSON 结构：{\"action\":\"BUY|SELL|HOLD\",\"price\":数字,\"quantity\":整数,\"reason\":\"简短理由\"}",
            "规则：HOLD 时 price 可为 null、quantity 为 0；买入不得指定高于对手卖一价过多的价格；",
            "卖出数量不得超过可卖数量；无法判断时选择 HOLD。");

    private PromptBuilder() {
    }

    /**
     * 构造用户提示词。
     *
     * @param snapshot   当前行情
     * @param account    账户
     * @param depth      盘口档位数
     * @return 用户提示词
     */
    public static String buildUserPrompt(MarketSnapshot snapshot, Account account, int depth) {
        String symbol = snapshot.getSymbol();
        StringBuilder sb = new StringBuilder();
        sb.append("标的：").append(symbol).append('\n');
        sb.append("最新价：").append(price(snapshot.getLastPrice())).append('\n');
        sb.append("参考价(昨收)：").append(price(snapshot.getPreviousClose())).append('\n');
        sb.append("涨跌幅：").append(snapshot.getChangePercent().setScale(2, RoundingMode.HALF_UP))
                .append("%\n");
        sb.append("买一：").append(price(snapshot.getBestBid()))
                .append("  卖一：").append(price(snapshot.getBestAsk())).append('\n');
        sb.append("累计成交量：").append(snapshot.getCumulativeVolume()).append(" 股\n");
        sb.append("买盘：").append(formatLevels(snapshot.getBids(), depth)).append('\n');
        sb.append("卖盘：").append(formatLevels(snapshot.getAsks(), depth)).append('\n');
        sb.append("--- 账户 ---\n");
        sb.append("可用现金：").append(account.getAvailableCash().toPlainString()).append('\n');
        sb.append("冻结现金：").append(account.getFrozenCash().toPlainString()).append('\n');
        sb.append("持仓：").append(formatPositions(account.getPositions())).append('\n');
        sb.append("可卖数量：").append(account.getAvailableQuantity(symbol)).append(" 股\n");
        sb.append("总权益：").append(account.equity(Map.of(symbol, snapshot.getLastPrice() == null
                ? snapshot.getPreviousClose() : snapshot.getLastPrice())).toPlainString()).append('\n');
        sb.append("请给出你的下单决策（JSON）。");
        return sb.toString();
    }

    /** @return 系统提示词 */
    public static String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static String price(BigDecimal value) {
        return value == null ? "无" : value.toPlainString();
    }

    private static String formatLevels(List<PriceLevel> levels, int depth) {
        if (levels.isEmpty()) {
            return "空";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(depth, levels.size()); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            PriceLevel level = levels.get(i);
            sb.append(level.getPrice().toPlainString()).append("×").append(level.getQuantity());
        }
        return sb.toString();
    }

    private static String formatPositions(Map<String, edu.cufe.auction.account.Position> positions) {
        if (positions.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, edu.cufe.auction.account.Position> e : positions.entrySet()) {
            if (!first) {
                sb.append("，");
            }
            first = false;
            sb.append(e.getKey()).append(" ").append(e.getValue().getQuantity()).append(" 股")
                    .append("（成本 ").append(e.getValue().getAverageCost().toPlainString()).append("）");
        }
        return sb.toString();
    }
}
