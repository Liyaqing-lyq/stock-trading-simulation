package edu.cufe.auction.agent.ai;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.agent.AbstractTradingAgent;
import edu.cufe.auction.agent.AgentType;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.Side;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 驱动的 AI 交易员（规格 5.2 的落地实现）。
 *
 * <p>决策周期：取行情 → 拼提示词 → 调 LLM → 解析校验 → 边界与额度裁剪 → 下单。
 * 每次 tick 只处理一个标的（轮换），避免一个 agent 每轮消耗多次 API 额度。</p>
 *
 * <p>安全机制：</p>
 * <ol>
 *   <li>限流在 {@link DeepSeekClient} 内部完成；</li>
 *   <li>价格边界：模型给出的价格被裁剪到最新价 ±{@code priceBandPercent}% 以内；</li>
 *   <li>失败回退：API 异常/超时/解析失败一律本 tick 观望（HOLD），并计入失败统计；</li>
 *   <li>数量裁剪：买入不超过可用现金可承受的数量，卖出不超过可卖数量。</li>
 * </ol>
 */
public final class LlmTradingAgent extends AbstractTradingAgent {

    private final LlmClient client;
    private final BigDecimal priceBandPercent;
    private final long defaultQuantity;
    private final int depth;
    private final AtomicInteger rotation = new AtomicInteger();
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong llmFailures = new AtomicLong();
    private final AtomicLong llmHolds = new AtomicLong();
    private final AtomicLong llmOrdersPlaced = new AtomicLong();

    /**
     * 构造 AI 交易员。
     *
     * @param agentId          agent 标识
     * @param displayName      展示名
     * @param account          账户
     * @param gateway          下单网关
     * @param client           LLM 客户端
     * @param priceBandPercent 报价允许偏离最新价的比例上限（百分比）
     * @param defaultQuantity  模型未给出合理数量时的默认数量
     * @param depth            提示词中展示的盘口档位数
     */
    public LlmTradingAgent(String agentId, String displayName, Account account, OrderGateway gateway,
                           LlmClient client, BigDecimal priceBandPercent, long defaultQuantity, int depth) {
        super(agentId, displayName, AgentType.AI, account, gateway);
        this.client = client;
        this.priceBandPercent = priceBandPercent;
        this.defaultQuantity = defaultQuantity;
        this.depth = depth;
    }

    /** @return 累计 LLM 调用次数 */
    public long getLlmCalls() {
        return llmCalls.get();
    }

    /** @return 累计 LLM 调用失败次数 */
    public long getLlmFailures() {
        return llmFailures.get();
    }

    /** @return 模型选择观望的次数 */
    public long getLlmHolds() {
        return llmHolds.get();
    }

    /** @return 因模型决策而下单的次数 */
    public long getLlmOrdersPlaced() {
        return llmOrdersPlaced.get();
    }

    @Override
    public void onTick() {
        List<MarketSnapshot> snapshots = new ArrayList<>(latestSnapshots());
        if (snapshots.isEmpty()) {
            return;
        }
        MarketSnapshot snapshot = snapshots.get(Math.abs(rotation.getAndIncrement()) % snapshots.size());
        String symbol = snapshot.getSymbol();
        Account account = getAccount();

        String raw;
        llmCalls.incrementAndGet();
        try {
            raw = client.complete(PromptBuilder.systemPrompt(),
                    PromptBuilder.buildUserPrompt(snapshot, account, depth));
        } catch (IOException | RuntimeException ex) {
            llmFailures.incrementAndGet();
            System.err.printf("[%s] LLM 调用失败，本 tick 回退观望：%s%n", getAgentId(), ex.getMessage());
            return;
        }

        TradingDecision decision = DecisionParser.parse(raw);
        if (decision.isHold()) {
            llmHolds.incrementAndGet();
            return;
        }

        BigDecimal anchor = anchorPrice(snapshot);
        if (anchor == null || anchor.signum() <= 0) {
            return;
        }

        Side side = decision.getAction() == TradingDecision.Action.BUY ? Side.BUY : Side.SELL;
        long quantity = decision.getQuantity() > 0 ? decision.getQuantity() : defaultQuantity;
        BigDecimal limit = clampPrice(decision.getPrice(), anchor);

        if (side == Side.BUY) {
            BigDecimal unitPrice = limit == null ? anchor : limit;
            long affordable = account.getAvailableCash()
                    .divide(unitPrice, 0, RoundingMode.DOWN).longValue();
            quantity = Math.min(quantity, affordable);
            if (quantity <= 0) {
                return;
            }
        } else {
            quantity = Math.min(quantity, account.getAvailableQuantity(symbol));
            if (quantity <= 0) {
                return;
            }
        }

        OrderRequest request = limit == null
                ? OrderRequest.market(getAgentId(), symbol, side, quantity)
                : OrderRequest.limit(getAgentId(), symbol, side, limit, quantity);
        OrderResult result = submit(request);
        llmOrdersPlaced.incrementAndGet();
        System.out.printf("[%s] LLM 决策 %s -> %s%n", getAgentId(), decision,
                result == null ? "提交异常" : (result.isAccepted()
                        ? String.format("受理，成交 %d 股", result.getFilledQuantity())
                        : "被拒：" + result.getReason()));
    }

    /**
     * 把模型给出的价格裁剪到最新价的 ±band% 区间内（价格边界保护）。
     *
     * @param proposed 模型给出的价格，可为 null
     * @param anchor   锚定价格
     * @return 裁剪后的价格；proposed 为 null 时返回 null（表示市价）
     */
    private BigDecimal clampPrice(BigDecimal proposed, BigDecimal anchor) {
        if (proposed == null) {
            return null;
        }
        BigDecimal band = priceBandPercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal upper = anchor.multiply(BigDecimal.ONE.add(band)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lower = anchor.multiply(BigDecimal.ONE.subtract(band)).setScale(2, RoundingMode.HALF_UP);
        if (proposed.compareTo(upper) > 0) {
            return upper;
        }
        if (proposed.compareTo(lower) < 0) {
            return lower;
        }
        return proposed.setScale(2, RoundingMode.HALF_UP);
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

    @Override
    public String toString() {
        return super.toString() + String.format(" LLM[调用=%d 失败=%d 观望=%d]",
                llmCalls.get(), llmFailures.get(), llmHolds.get());
    }
}
