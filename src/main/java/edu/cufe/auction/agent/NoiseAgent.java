package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

/**
 * 噪音/做市 agent：在最新价附近随机挂单，为市场提供基础流动性。
 *
 * <p>没有它，模拟开始时会因为“双边都没人挂单”而无法产生第一笔成交，
 * 价格发现也就无从谈起。随机种子固定，方便复现实验。</p>
 *
 * <p><b>报价刷新（quote refresh）</b>：每轮 tick 都会撤掉该标的过旧的报价，
 * 再按最新价重新报价。这一步不是装饰——限价单会冻结资金（买入）或持仓（卖出），
 * 而冻结只有成交或撤单才释放。若只挂不撤，冻结额度单调累积直至耗尽，
 * agent 再也下不出单，市场成交量归零（历史 bug：模拟运行约 40 秒后成交量掉到 0，
 * 盘口只剩一堆永不成交的陈旧挂单）。</p>
 *
 * <p><b>额度自恢复</b>：额度不足时不再直接放弃本标的，而是先撤掉自己最旧的挂单
 * 腾出额度再报价，从而保证报价行为不会因为额度耗尽而永久停摆。</p>
 */
public final class NoiseAgent extends AbstractTradingAgent {

    /** 单标的允许同时存在的报价数量上限（超出则撤最旧的）。 */
    private static final int MAX_QUOTES_PER_SYMBOL = 2;

    private final Random random;
    private final BigDecimal priceOffsetPercent;
    private final long minQuantity;
    private final long maxQuantity;

    /**
     * 构造噪音 agent。
     *
     * @param agentId            agent 标识
     * @param displayName        展示名
     * @param account            账户
     * @param gateway            下单网关
     * @param seed               随机种子（固定以保证可复现）
     * @param priceOffsetPercent 报价相对最新价的最大偏离百分比
     * @param minQuantity        单笔最小数量
     * @param maxQuantity        单笔最大数量
     */
    public NoiseAgent(String agentId, String displayName, Account account, OrderGateway gateway,
                      long seed, BigDecimal priceOffsetPercent, long minQuantity, long maxQuantity) {
        super(agentId, displayName, AgentType.NOISE, account, gateway);
        this.random = new Random(seed);
        this.priceOffsetPercent = priceOffsetPercent;
        this.minQuantity = minQuantity;
        this.maxQuantity = maxQuantity;
    }

    @Override
    public void onTick() {
        Account account = getAccount();
        for (MarketSnapshot snapshot : latestSnapshots()) {
            String symbol = snapshot.getSymbol();
            BigDecimal anchor = anchorPrice(snapshot);
            if (anchor == null) {
                continue;
            }

            // 报价刷新：把该标的的挂单压到上限以内（撤最旧的），回收被冻结的现金/持仓
            trimQuotes(symbol, MAX_QUOTES_PER_SYMBOL - 1);

            double offset = random.nextDouble() * 2 * priceOffsetPercent.doubleValue()
                    - priceOffsetPercent.doubleValue();
            BigDecimal price = anchor.multiply(BigDecimal.valueOf(1 + offset / 100))
                    .setScale(2, RoundingMode.HALF_UP);
            if (price.signum() <= 0) {
                continue;
            }
            long quantity = minQuantity + (long) random.nextInt((int) Math.max(1, maxQuantity - minQuantity + 1));
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;

            if (side == Side.BUY && !account.canAfford(price, quantity)) {
                side = Side.SELL;
            }
            if (side == Side.SELL && account.getAvailableQuantity(symbol) < quantity) {
                if (!account.canAfford(price, quantity)) {
                    continue;
                }
                side = Side.BUY;
            }
            // 额度仍不足时撤旧单腾额度；腾不出才放弃本标的本轮报价
            if (!ensureCapacityFor(symbol, side, price, quantity)) {
                continue;
            }
            submit(OrderRequest.limit(getAgentId(), symbol, side, price, quantity));
        }
    }

    private BigDecimal anchorPrice(MarketSnapshot snapshot) {
        if (snapshot.getLastPrice() != null) {
            return snapshot.getLastPrice();
        }
        if (snapshot.getBestBid() != null && snapshot.getBestAsk() != null) {
            return snapshot.getBestBid().add(snapshot.getBestAsk())
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }
        return snapshot.getPreviousClose();
    }
}
