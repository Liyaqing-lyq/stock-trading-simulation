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
 */
public final class NoiseAgent extends AbstractTradingAgent {

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
