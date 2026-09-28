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
 *   <li>短均线 &gt; 长均线 → 动量向上，主动买入；反之卖出已持有的数量；</li>
 *   <li>单标的持仓占用不超过账户可用现金的一定比例，避免一把梭。</li>
 * </ul>
 *
 * <p><b>为什么必须主动吃对手价</b>：最新价是「上一笔成交价」，它必然落在买一与卖一之间
 * （撮合后订单簿不交叉）。因此一张价格等于最新价的限价买单永远低于卖一，一张价格等于
 * 最新价的限价卖单永远高于买一——两张都只会静静挂在簿上，永远不成交。
 * 历史 bug：此前的实现正是挂「最新价」，导致动量 agent 实际上一笔都成交不了，
 * 却持续冻结资金与持仓。现在改为以对手价（买用卖一、卖用买一）报价，才能真正成交。</p>
 *
 * <p><b>报价刷新</b>：每轮 tick 开始先撤销自己上一轮未成交的挂单，回收冻结额度，
 * 避免陈旧挂单堆积并锁死账户资金。</p>
 */
public final class MomentumAgent extends AbstractTradingAgent {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 对手盘为空时的激进报价偏离比例（0.5%）。 */
    private static final BigDecimal FALLBACK_OFFSET = new BigDecimal("1.005");

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
        // 报价刷新：动量策略需要的是成交，不是排队；先撤掉上一轮残留挂单并回收冻结额度
        cancelAllOpenOrders();

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
                BigDecimal price = aggressivePrice(snapshot, Side.BUY);
                BigDecimal budget = account.getAvailableCash()
                        .multiply(positionBudgetPercent)
                        .divide(HUNDRED, 2, RoundingMode.HALF_UP);
                long affordable = budget.divide(price, 0, RoundingMode.DOWN).longValue();
                long quantity = Math.min(orderQuantity, affordable);
                if (quantity > 0) {
                    submit(OrderRequest.limit(getAgentId(), symbol, Side.BUY, price, quantity));
                }
            } else if (last.compareTo(average) < 0) {
                long quantity = Math.min(orderQuantity, account.getAvailableQuantity(symbol));
                if (quantity > 0) {
                    submit(OrderRequest.limit(getAgentId(), symbol, Side.SELL,
                            aggressivePrice(snapshot, Side.SELL), quantity));
                }
            }
        }
    }

    /**
     * 计算主动成交价：买用卖一（对手价），卖用买一；对手盘为空时退回最新价加偏移。
     *
     * @param snapshot 行情快照
     * @param side     方向
     * @return 限价
     */
    private BigDecimal aggressivePrice(MarketSnapshot snapshot, Side side) {
        BigDecimal counterParty = side == Side.BUY ? snapshot.getBestAsk() : snapshot.getBestBid();
        if (counterParty != null && counterParty.signum() > 0) {
            return counterParty.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal last = snapshot.getLastPrice();
        if (side == Side.SELL) {
            return last.divide(FALLBACK_OFFSET, 2, RoundingMode.HALF_UP);
        }
        return last.multiply(FALLBACK_OFFSET).setScale(2, RoundingMode.HALF_UP);
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
