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
 * 逆势型 agent（短期均值回归）：价格明显偏离近期均线时反向交易。
 *
 * <p>与 {@link MomentumAgent} 互为镜像：动量 agent 在价格高于均线时追买、低于均线时杀跌，
 * 一旦市场里只有趋势交易者，价格就会单边放大波动。逆势型 agent 在价格高于均线一定幅度时卖出、
 * 低于均线一定幅度时买入，为趋势单提供对手盘，让价格围绕均值震荡而不是无限外推。</p>
 *
 * <p>与动量 agent 的差异有两处：</p>
 * <ol>
 *   <li>方向相反：跌多了买、涨多了卖；</li>
 *   <li>带偏离阈值：只有偏离超过 {@code deviationPercent} 才出手，否则观望，
 *       避免在正常波动里来回摩擦。</li>
 * </ol>
 *
 * <p><b>采样口径</b>：均线每轮 tick 采样一次最新价（而不是每个行情快照都采样）。
 * 行情广播的频率远高于决策频率，若按快照采样，一个「6 个样本」的窗口在几毫秒内就会被
 * 同价快照填满——价格跳变后均线立刻追平，逆势信号还没被决策线程看到就消失了。
 * 按决策周期采样后，窗口真正对应「最近 6 次决策」，均值回归才有可观测的时间跨度。</p>
 *
 * <p><b>为什么必须主动吃对手价</b>：最新价必然落在买一与卖一之间，挂“最新价”的限价单
 * 永远不会成交（历史 bug，见 {@link MomentumAgent} 注释）。因此这里买用卖一价、卖用买一价；
 * 对手盘为空时按最新价加固定偏移报价。</p>
 *
 * <p><b>报价刷新</b>：每轮 tick 先撤销本 agent 的旧挂单，回收冻结额度后再决策。</p>
 */
public final class ContrarianAgent extends AbstractTradingAgent {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 对手盘为空时的激进报价偏离比例（0.5%）。 */
    private static final BigDecimal FALLBACK_OFFSET = new BigDecimal("1.005");

    private final int windowSize;
    private final BigDecimal deviationPercent;
    private final long orderQuantity;
    private final Map<String, Deque<BigDecimal>> priceHistory = new HashMap<>();

    /**
     * 构造逆势型 agent。
     *
     * @param agentId          agent 标识
     * @param displayName      展示名
     * @param account          账户
     * @param gateway          下单网关
     * @param windowSize       均线窗口长度
     * @param deviationPercent 触发反向交易的偏离阈值（%）
     * @param orderQuantity    单笔下单股数
     */
    public ContrarianAgent(String agentId, String displayName, Account account, OrderGateway gateway,
                           int windowSize, BigDecimal deviationPercent, long orderQuantity) {
        super(agentId, displayName, AgentType.CONTRARIAN, account, gateway);
        this.windowSize = windowSize;
        this.deviationPercent = deviationPercent;
        this.orderQuantity = orderQuantity;
    }

    @Override
    public void onTick() {
        cancelAllOpenOrders();

        for (MarketSnapshot snapshot : latestSnapshots()) {
            String symbol = snapshot.getSymbol();
            BigDecimal last = snapshot.getLastPrice();
            if (last == null) {
                continue;
            }
            // 每个决策周期采样一次，窗口对应「最近 windowSize 次决策」
            synchronized (priceHistory) {
                Deque<BigDecimal> history = priceHistory.computeIfAbsent(
                        symbol, k -> new ArrayDeque<>());
                history.addLast(last);
                while (history.size() > windowSize) {
                    history.removeFirst();
                }
            }
            BigDecimal average = movingAverage(symbol);
            if (average == null || average.signum() <= 0) {
                continue;
            }
            BigDecimal gap = last.subtract(average).multiply(HUNDRED)
                    .divide(average, 4, RoundingMode.HALF_UP);
            if (gap.compareTo(deviationPercent.negate()) <= 0) {
                BigDecimal price = aggressivePrice(snapshot, Side.BUY);
                long quantity = Math.min(orderQuantity, affordable(symbol, price));
                if (quantity > 0) {
                    submit(OrderRequest.limit(getAgentId(), symbol, Side.BUY, price, quantity));
                }
            } else if (gap.compareTo(deviationPercent) >= 0) {
                BigDecimal price = aggressivePrice(snapshot, Side.SELL);
                long quantity = Math.min(orderQuantity, getAccount().getAvailableQuantity(symbol));
                if (quantity > 0) {
                    submit(OrderRequest.limit(getAgentId(), symbol, Side.SELL, price, quantity));
                }
            }
        }
    }

    /**
     * 计算主动成交价：买用卖一、卖用买一；对手盘为空时退回最新价加偏移。
     *
     * @param snapshot 行情快照
     * @param side     方向
     * @return 限价（两位小数）
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

    /**
     * 按可用现金计算最多能买多少股。
     *
     * @param symbol 标的
     * @param price  限价
     * @return 可买股数
     */
    private long affordable(String symbol, BigDecimal price) {
        return getAccount().getAvailableCash().divide(price, 0, RoundingMode.DOWN).longValue();
    }

    /**
     * 计算价格窗口内的算术均线。
     *
     * @param symbol 标的
     * @return 均线；无历史数据返回 null
     */
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
