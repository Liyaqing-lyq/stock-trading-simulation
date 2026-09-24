package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;

import java.math.BigDecimal;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 人类 broker：下单动作来自 GUI 或控制台，本类只负责把请求转给引擎。
 *
 * <p>线程安全：GUI 线程调用 {@link #placeLimitOrder}/{@link #placeMarketOrder} 入队，
 * 定时器线程在 {@link #onTick()} 中出队提交，避免在 JavaFX 线程里做撮合。</p>
 */
public final class HumanBrokerAgent extends AbstractTradingAgent {

    private final Queue<OrderRequest> pendingOrders = new ConcurrentLinkedQueue<>();
    private final Queue<Long> pendingCancels = new ConcurrentLinkedQueue<>();
    private final AtomicLong manualOrderCount = new AtomicLong();

    /**
     * 构造人类 broker。
     *
     * @param agentId     agent 标识
     * @param displayName 展示名
     * @param account     账户
     * @param gateway     下单网关
     */
    public HumanBrokerAgent(String agentId, String displayName, Account account, OrderGateway gateway) {
        super(agentId, displayName, AgentType.HUMAN, account, gateway);
    }

    /**
     * 提交限价单（线程安全，可在 GUI 线程调用）。
     *
     * @param symbol   标的
     * @param side     {@code BUY}/{@code SELL}
     * @param price    限价
     * @param quantity 数量
     * @return 已入队返回 true；参数非法抛 {@link IllegalArgumentException}
     */
    public boolean placeLimitOrder(String symbol, edu.cufe.auction.model.Side side,
                                   BigDecimal price, long quantity) {
        boolean added = pendingOrders.add(
                OrderRequest.limit(getAgentId(), symbol, side, price, quantity));
        if (added) {
            manualOrderCount.incrementAndGet();
        }
        return added;
    }

    /**
     * 提交市价单（线程安全）。
     *
     * @param symbol   标的
     * @param side     方向
     * @param quantity 数量
     * @return 已入队返回 true
     */
    public boolean placeMarketOrder(String symbol, edu.cufe.auction.model.Side side, long quantity) {
        boolean added = pendingOrders.add(
                OrderRequest.market(getAgentId(), symbol, side, quantity));
        if (added) {
            manualOrderCount.incrementAndGet();
        }
        return added;
    }

    /**
     * 请求撤单（线程安全）。
     *
     * @param orderId 订单 id
     */
    public void requestCancel(long orderId) {
        pendingCancels.add(orderId);
    }

    /** @return 累计人工下单次数 */
    public long getManualOrderCount() {
        return manualOrderCount.get();
    }

    @Override
    public void onTick() {
        Long cancelId;
        while ((cancelId = pendingCancels.poll()) != null) {
            cancel(cancelId);
        }
        OrderRequest request;
        while ((request = pendingOrders.poll()) != null) {
            OrderResult result = submit(request);
            if (result != null && !result.isAccepted()) {
                System.err.printf("[%s] 人工订单被拒：%s -> %s%n",
                        getAgentId(), request, result.getReason());
            }
        }
    }
}
