package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 价值型 agent：以昨收价为合理估值锚，价格显著低于锚时买入、显著高于锚时卖出。
 *
 * <p>补齐市场结构里缺失的“锚定”角色：动量 / LLM agent 追涨杀跌，噪音 agent 只提供
 * 随机流动性，两者叠加时价格容易单边走远、逼近涨跌停。价值型 agent 用估值锚反向承接，
 * 让价格围绕基本面波动，收益分布与回撤都更接近真实市场。</p>
 *
 * <p>报价规则（限价单，价格不高于估值、不低于估值）：</p>
 * <ul>
 *   <li>偏离 ≤ -阈值：按卖一价主动买入；卖一价高于估值时改为在估值挂被动买单；</li>
 *   <li>偏离 ≥ +阈值：按买一价主动卖出；买一价低于估值时改为在估值挂被动卖单；</li>
 *   <li>价格一律夹在当日涨跌停之间，越界委托不会送到引擎。</li>
 * </ul>
 *
 * <p><b>报价刷新</b>：每轮 tick 先撤销上一轮挂单再报价，回收被冻结的资金 / 持仓；
 * 直接复用 {@link AbstractTradingAgent#cancelAllOpenOrders()}，
 * 避免出现“只挂不撤→额度耗尽→停止下单”的历史故障。</p>
 */
public final class ValueAgent extends AbstractTradingAgent {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 触发交易的估值偏离阈值（百分比）。 */
    private final BigDecimal thresholdPercent;
    /** 单笔下单股数。 */
    private final long orderQuantity;

    /**
     * 构造价值型 agent。
     *
     * @param agentId          agent 标识
     * @param displayName      展示名
     * @param account          账户
     * @param gateway          下单网关
     * @param thresholdPercent 触发交易的估值偏离阈值（%），例如 2 表示偏离昨收 2%
     * @param orderQuantity    单笔下单股数
     */
    public ValueAgent(String agentId, String displayName, Account account, OrderGateway gateway,
                      BigDecimal thresholdPercent, long orderQuantity) {
        super(agentId, displayName, AgentType.VALUE, account, gateway);
        this.thresholdPercent = thresholdPercent;
        this.orderQuantity = orderQuantity;
    }

    @Override
    public void onTick() {
        // 报价刷新：价值单每轮重挂，先撤旧单回收冻结额度
        cancelAllOpenOrders();

        for (MarketSnapshot snapshot : latestSnapshots()) {
            String symbol = snapshot.getSymbol();
            BigDecimal anchor = snapshot.getPreviousClose();
            BigDecimal last = snapshot.getLastPrice();
            if (anchor == null || last == null || anchor.signum() <= 0) {
                continue;
            }
            BigDecimal deviation = last.subtract(anchor).multiply(HUNDRED)
                    .divide(anchor, 4, RoundingMode.HALF_UP);
            if (deviation.compareTo(thresholdPercent.negate()) <= 0) {
                buy(symbol, snapshot, anchor);
            } else if (deviation.compareTo(thresholdPercent) >= 0) {
                sell(symbol, snapshot, anchor);
            }
        }
    }

    /**
     * 低估时买入：优先按卖一价主动成交，但不为高于估值的价格买单。
     *
     * @param symbol   标的
     * @param snapshot 行情快照
     * @param anchor   估值锚（昨收）
     */
    private void buy(String symbol, MarketSnapshot snapshot, BigDecimal anchor) {
        BigDecimal price = snapshot.getBestAsk();
        if (price == null || price.compareTo(anchor) > 0) {
            price = anchor;
        }
        price = clampToLimits(snapshot, price);
        if (price == null) {
            return;
        }
        long affordable = getAccount().getAvailableCash()
                .divide(price, 0, RoundingMode.DOWN).longValue();
        long quantity = Math.min(orderQuantity, affordable);
        if (quantity <= 0) {
            return;
        }
        if (!ensureCapacityFor(symbol, Side.BUY, price, quantity)) {
            return;
        }
        submit(OrderRequest.limit(getAgentId(), symbol, Side.BUY, price, quantity));
    }

    /**
     * 高估时卖出：优先按买一价主动成交，但不在低于估值的价格卖出。
     *
     * @param symbol   标的
     * @param snapshot 行情快照
     * @param anchor   估值锚（昨收）
     */
    private void sell(String symbol, MarketSnapshot snapshot, BigDecimal anchor) {
        BigDecimal price = snapshot.getBestBid();
        if (price == null || price.compareTo(anchor) < 0) {
            price = anchor;
        }
        price = clampToLimits(snapshot, price);
        if (price == null) {
            return;
        }
        long quantity = Math.min(orderQuantity, getAccount().getAvailableQuantity(symbol));
        if (quantity <= 0) {
            return;
        }
        submit(OrderRequest.limit(getAgentId(), symbol, Side.SELL, price, quantity));
    }

    /**
     * 把价格夹到当日涨跌停区间内，并按 A 股最小价格变动（0.01 元）取整。
     *
     * @param snapshot 行情快照
     * @param price    原始价格
     * @return 合法价格；非正数返回 null
     */
    private BigDecimal clampToLimits(MarketSnapshot snapshot, BigDecimal price) {
        BigDecimal value = price;
        if (snapshot.getLowerLimit() != null && value.compareTo(snapshot.getLowerLimit()) < 0) {
            value = snapshot.getLowerLimit();
        }
        if (snapshot.getUpperLimit() != null && value.compareTo(snapshot.getUpperLimit()) > 0) {
            value = snapshot.getUpperLimit();
        }
        value = value.setScale(2, RoundingMode.HALF_UP);
        return value.signum() > 0 ? value : null;
    }
}
