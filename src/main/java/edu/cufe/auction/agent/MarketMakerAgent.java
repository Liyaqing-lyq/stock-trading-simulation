package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/**
 * 做市商 agent：围绕中间价双边报价，赚取买卖价差，并为市场提供持续流动性。
 *
 * <p>与 {@link NoiseAgent} 的随机报价不同，做市商是“有纪律”的流动性提供者：</p>
 * <ul>
 *   <li>报价以买一 / 卖一的中间价为锚，买卖两侧同时挂限价单，价差由
 *       {@code halfSpreadPercent} 控制；</li>
 *   <li><b>不穿越对手价</b>：买单不高于卖一、卖单不低于买一，保证报价始终是被动单，
 *       不会自己吃掉自己；</li>
 *   <li><b>库存偏斜</b>：持仓高于目标库存时整体下移报价（更愿意卖出），
 *       低于目标时整体上移，避免库存单边累积；</li>
 *   <li><b>报价刷新</b>：每轮 tick 先撤掉本标的的全部旧报价，再重新双边报价，
 *       回收冻结资金 / 持仓——做市 agent 若只挂不撤，冻结额会单调累积直至停摆。</li>
 * </ul>
 *
 * <p>报价还要夹在当日涨跌停区间内，越界委托不会送到引擎。</p>
 */
public final class MarketMakerAgent extends AbstractTradingAgent {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 围绕中间价报价时使用的单边价差百分比。 */
    private final BigDecimal halfSpreadPercent;
    private final BigDecimal maxSkewPercent;
    private final long quoteQuantity;
    private final Map<String, Long> inventoryTargets;

    /**
     * 构造做市商 agent。
     *
     * @param agentId           agent 标识
     * @param displayName       展示名
     * @param account           账户
     * @param gateway           下单网关
     * @param halfSpreadPercent 单边价差（%），例如 0.4 表示买卖价分别偏离中间价 0.4%
     * @param maxSkewPercent    库存偏斜的最大幅度（%）
     * @param quoteQuantity     单边报价股数
     * @param inventoryTargets  各标的目标库存（通常取初始持仓）
     */
    public MarketMakerAgent(String agentId, String displayName, Account account, OrderGateway gateway,
                            BigDecimal halfSpreadPercent, BigDecimal maxSkewPercent, long quoteQuantity,
                            Map<String, Long> inventoryTargets) {
        super(agentId, displayName, AgentType.MARKET_MAKER, account, gateway);
        this.halfSpreadPercent = requireNonNegative(halfSpreadPercent, "halfSpreadPercent");
        this.maxSkewPercent = requireNonNegative(maxSkewPercent, "maxSkewPercent");
        this.quoteQuantity = requirePositive(quoteQuantity, "quoteQuantity");
        Objects.requireNonNull(inventoryTargets, "inventoryTargets");
        inventoryTargets.forEach((symbol, target) -> {
            if (symbol == null || symbol.isBlank() || target == null || target < 0) {
                throw new IllegalArgumentException(
                        "inventoryTargets 的标的不能为空，目标库存不能为负数");
            }
        });
        this.inventoryTargets = Map.copyOf(inventoryTargets);
    }

    @Override
    public void onTick() {
        for (MarketSnapshot snapshot : latestSnapshots()) {
            String symbol = snapshot.getSymbol();
            BigDecimal mid = midPrice(snapshot);
            if (mid == null || mid.signum() <= 0) {
                continue;
            }
            // 报价刷新：只撤本标的的旧报价，再重新双边报价
            cancelOpenOrders(order -> order.symbol().equals(symbol));

            BigDecimal skew = inventorySkewPercent(symbol);
            BigDecimal bid = mid.multiply(HUNDRED.subtract(halfSpreadPercent.add(skew)))
                    .divide(HUNDRED, 8, RoundingMode.HALF_UP);
            BigDecimal ask = mid.multiply(HUNDRED.add(halfSpreadPercent.subtract(skew)))
                    .divide(HUNDRED, 8, RoundingMode.HALF_UP);
            bid = normalizePrice(snapshot, bid, RoundingMode.HALF_UP);
            ask = normalizePrice(snapshot, ask, RoundingMode.HALF_UP);
            if (bid == null || ask == null) {
                continue;
            }

            // 不穿越对手价：做市报价必须是被动单
            if (snapshot.getBestAsk() != null) {
                BigDecimal cap = snapshot.getBestAsk().subtract(snapshot.getTickSize());
                if (bid.compareTo(cap) > 0) {
                    bid = cap;
                }
            }
            if (snapshot.getBestBid() != null) {
                BigDecimal floor = snapshot.getBestBid().add(snapshot.getTickSize());
                if (ask.compareTo(floor) < 0) {
                    ask = floor;
                }
            }
            bid = normalizePrice(snapshot, bid, RoundingMode.HALF_UP);
            ask = normalizePrice(snapshot, ask, RoundingMode.HALF_UP);
            boolean passiveBid = bid != null
                    && (snapshot.getBestAsk() == null || bid.compareTo(snapshot.getBestAsk()) < 0);
            boolean passiveAsk = ask != null
                    && (snapshot.getBestBid() == null || ask.compareTo(snapshot.getBestBid()) > 0);
            if (passiveBid && passiveAsk && bid.compareTo(ask) >= 0) {
                continue;
            }

            long target = inventoryTargets.getOrDefault(symbol, 0L);
            long position = getAccount().getQuantity(symbol);
            // 库存上限：目标库存的 1.5 倍，避免单边囤货
            if (passiveBid && (target <= 0 || position < target + target / 2)) {
                quote(symbol, Side.BUY, bid);
            }
            if (passiveAsk && getAccount().getAvailableQuantity(symbol) >= quoteQuantity) {
                quote(symbol, Side.SELL, ask);
            }
        }
    }

    /**
     * 提交一张做市限价单；额度不足时先撤旧单腾额度。
     *
     * @param symbol 标的
     * @param side   方向
     * @param price  限价
     */
    private void quote(String symbol, Side side, BigDecimal price) {
        if (!ensureCapacityFor(symbol, side, price, quoteQuantity)) {
            return;
        }
        submit(OrderRequest.limit(getAgentId(), symbol, side, price, quoteQuantity));
    }

    /**
     * 计算中间价：优先用买卖一的中点，其次最新价，最后昨收。
     *
     * @param snapshot 行情快照
     * @return 中间价；无法确定时返回 null
     */
    private BigDecimal midPrice(MarketSnapshot snapshot) {
        if (snapshot.getBestBid() != null && snapshot.getBestAsk() != null) {
            return snapshot.getBestBid().add(snapshot.getBestAsk())
                    .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        }
        if (snapshot.getLastPrice() != null) {
            return snapshot.getLastPrice();
        }
        return snapshot.getPreviousClose();
    }

    /**
     * 计算库存偏斜百分比：持仓高于目标时为正（整体下移报价，倾向卖出）。
     *
     * @param symbol 标的
     * @return 偏斜百分比，范围 [-maxSkewPercent, +maxSkewPercent]
     */
    private BigDecimal inventorySkewPercent(String symbol) {
        long target = inventoryTargets.getOrDefault(symbol, 0L);
        if (target <= 0) {
            return BigDecimal.ZERO;
        }
        long position = getAccount().getQuantity(symbol);
        BigDecimal ratio = BigDecimal.valueOf(position - target)
                .divide(BigDecimal.valueOf(target), 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            ratio = BigDecimal.ONE;
        }
        if (ratio.compareTo(BigDecimal.ONE.negate()) < 0) {
            ratio = BigDecimal.ONE.negate();
        }
        return ratio.multiply(maxSkewPercent).setScale(4, RoundingMode.HALF_UP);
    }
}
