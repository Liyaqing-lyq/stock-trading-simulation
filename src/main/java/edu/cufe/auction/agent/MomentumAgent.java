package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 规则型动量 agent（不依赖 LLM，用作 AI 交易的对照组）：
 *
 * <ul>
 *   <li>维护每个标的最近 N 个最新价；</li>
 *   <li>短均线 &gt; 长均线 → 动量向上，挂买单；反之挂卖单（仅卖出已持有的数量）；</li>
 *   <li>单标的持仓占用不超过账户可用现金的一定比例，避免一把梭。</li>
 * </ul>
 */
public final class MomentumAgent extends AbstractTradingAgent {

    private final int windowSize;
    private final long orderQuantity;
    private final BigDecimal positionBudgetPercent;
    private final Map<String, Deque<BigDecimal>> priceHistory = new HashMap<>();

    /**
     * 构造动量 agent。
     *
     * @param agentId               agent 标识
     * @param displayName           展示名
     * @param account               账户
     * @param gateway               下单网关
     * @param windowSize            价格窗口长度
     * @param orderQuantity         每次下单数量
     * @param positionBudgetPercent 单笔下单允许占用的可用现金比例（0~100）
     */
    public MomentumAgent(String agentId, String displayName, Account account, OrderGateway gateway,
                         int windowSize, long orderQuantity, BigDecimal positionBudgetPercent) {
        super(agentId, displayName, AgentType.AI, account, gateway);
        this.windowSize = windowSize;
        this.orderQuantity = orderQuantity;
        this.positionBudgetPercent = positionBudgetPercent;
    }

    @Override
    public void onMarketSnapshot(MarketSnapshot snapshot) {
        super.onMarketSnapshot(snapshot);
        if (snapshot.getLastPrice() == null) {
            return;
        }
        synchronized (priceHistory) {
            Deque<BigDecimal> history = priceHistory.computeIfAbsent(
                    snapshot.getSymbol(), k -> new ArrayDeque<>());
            history.addLast(snapshot.getLastPrice());
            while (history.size() > windowSize) {
                history.removeFirst();
            }
        }
    }

    @Override
    public void onTick() {
        for (MarketSnapshot snapshot : latestSnapshots()) {
            String symbol = snapshot.getSymbol();
            BigDecimal last = snapshot.getLastPrice();
            if (last == null) {
                continue;
            }
            BigDecimal average = movingAverage(symbol);
            if (average == null) {
                continue;
            }
            Account account = getAccount();
            if (last.compareTo(average) > 0) {
                BigDecimal budget = account.getAvailableCash()
                        .multiply(positionBudgetPercent)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                long affordable = budget.divide(last, 0, RoundingMode.DOWN).longValue();
                long quantity = Math.min(orderQuantity, affordable);
                if (quantity > 0) {
                    submit(OrderRequest.limit(getAgentId(), symbol, Side.BUY,
                            last.setScale(2, RoundingMode.HALF_UP), quantity));
                }
            } else if (last.compareTo(average) < 0) {
                long quantity = Math.min(orderQuantity, account.getAvailableQuantity(symbol));
                if (quantity > 0) {
                    submit(OrderRequest.limit(getAgentId(), symbol, Side.SELL,
                            last.setScale(2, RoundingMode.HALF_UP), quantity));
                }
            }
        }
    }

    private BigDecimal movingAverage(String symbol) {
        synchronized (priceHistory) {
            Deque<BigDecimal> history = priceHistory.get(symbol);
            if (history == null || history.isEmpty()) {
                return null;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal price : history) {
                sum = sum.add(price);
            }
            return sum.divide(BigDecimal.valueOf(history.size()), 4, RoundingMode.HALF_UP);
        }
    }
}
