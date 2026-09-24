package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.market.MarketDataListener;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * agent 基类：缓存最新行情、统一处理下单异常与统计。
 *
 * <p>行情缓存用 {@link ConcurrentHashMap} 保证广播线程写入、决策线程读取时可见。</p>
 */
public abstract class AbstractTradingAgent implements TradingAgent, MarketDataListener {

    private final String agentId;
    private final String displayName;
    private final AgentType type;
    private final Account account;
    private final OrderGateway gateway;
    private final Map<String, MarketSnapshot> latestSnapshots = new ConcurrentHashMap<>();
    private final AtomicLong submittedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong filledQuantity = new AtomicLong();

    /**
     * 构造 agent。
     *
     * @param agentId     agent 标识
     * @param displayName 展示名
     * @param type        agent 类型
     * @param account     对应账户
     * @param gateway     下单网关
     */
    protected AbstractTradingAgent(String agentId, String displayName, AgentType type,
                                   Account account, OrderGateway gateway) {
        this.agentId = agentId;
        this.displayName = displayName;
        this.type = type;
        this.account = account;
        this.gateway = gateway;
    }

    @Override
    public String getAgentId() {
        return agentId;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public AgentType getType() {
        return type;
    }

    /** @return 账户 */
    public Account getAccount() {
        return account;
    }

    /** @return 下单网关 */
    protected OrderGateway getGateway() {
        return gateway;
    }

    @Override
    public void onMarketSnapshot(MarketSnapshot snapshot) {
        latestSnapshots.put(snapshot.getSymbol(), snapshot);
    }

    /**
     * 取某标的最新行情快照。
     *
     * @param symbol 标的代码
     * @return 快照；尚未收到广播时返回 null
     */
    public MarketSnapshot latestSnapshot(String symbol) {
        return latestSnapshots.get(symbol);
    }

    /** @return 已缓存的所有行情快照 */
    public Collection<MarketSnapshot> latestSnapshots() {
        return latestSnapshots.values();
    }

    /** @return 累计下单次数 */
    public long getSubmittedCount() {
        return submittedCount.get();
    }

    /** @return 累计被拒次数 */
    public long getRejectedCount() {
        return rejectedCount.get();
    }

    /** @return 累计成交股数 */
    public long getFilledQuantity() {
        return filledQuantity.get();
    }

    /**
     * 下单并记录结果；异常被吞掉并打印，避免单个 agent 的 bug 拖垮整个模拟。
     *
     * @param request 下单请求
     * @return 受理结果；发生异常时返回 null
     */
    protected OrderResult submit(OrderRequest request) {
        submittedCount.incrementAndGet();
        try {
            OrderResult result = gateway.submit(request);
            if (result.isAccepted()) {
                filledQuantity.addAndGet(result.getFilledQuantity());
            } else {
                rejectedCount.incrementAndGet();
            }
            return result;
        } catch (RuntimeException ex) {
            rejectedCount.incrementAndGet();
            System.err.printf("[%s] 下单异常：%s%n", agentId, ex);
            return null;
        }
    }

    /**
     * 撤单。
     *
     * @param orderId 订单 id
     * @return 是否撤销成功
     */
    protected boolean cancel(long orderId) {
        try {
            return gateway.cancel(orderId);
        } catch (RuntimeException ex) {
            System.err.printf("[%s] 撤单异常：%s%n", agentId, ex);
            return false;
        }
    }

    @Override
    public String toString() {
        return String.format("%s[%s 类型=%s 下单=%d 被拒=%d 成交=%d]",
                displayName, agentId, type, getSubmittedCount(), getRejectedCount(), getFilledQuantity());
    }
}
