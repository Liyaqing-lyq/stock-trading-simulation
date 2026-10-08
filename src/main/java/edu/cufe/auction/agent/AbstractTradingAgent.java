package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.market.MarketDataListener;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.model.Trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

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
    private final Map<Long, OpenOrder> openOrders = new ConcurrentHashMap<>();
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

    @Override
    public void onTrade(Trade trade) {
        if (agentId.equals(trade.getBuyAgentId())) {
            filledQuantity.addAndGet(trade.getQuantity());
            reduceOpenOrder(trade.getBuyOrderId(), trade.getQuantity());
        }
        if (agentId.equals(trade.getSellAgentId())) {
            filledQuantity.addAndGet(trade.getQuantity());
            reduceOpenOrder(trade.getSellOrderId(), trade.getQuantity());
        }
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
                registerOpenOrder(result.getOrder());
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
            boolean cancelled = gateway.cancel(orderId);
            // false 也表示订单已不在订单簿中（已成交、已撤销或不存在），本地记录同样应清理。
            openOrders.remove(orderId);
            return cancelled;
        } catch (RuntimeException ex) {
            System.err.printf("[%s] 撤单异常：%s%n", agentId, ex);
            return false;
        }
    }

    /**
     * 当前仍在订单簿中的本 agent 委托数量。
     *
     * @return 未终结委托数
     */
    protected int openOrderCount() {
        return openOrders.size();
    }

    /**
     * 撤销全部未终结委托，释放被冻结的资金和持仓。
     *
     * @return 成功撤销的委托数
     */
    protected int cancelAllOpenOrders() {
        return cancelOpenOrders(order -> true);
    }

    /**
     * 为某标的新报价腾出名额。上限包含调用方随后准备提交的新委托。
     *
     * @param symbol        标的代码
     * @param maxOpenOrders 新委托提交后允许保留的最大委托数，必须为正
     * @return 成功撤销的旧委托数
     */
    protected int trimQuotes(String symbol, int maxOpenOrders) {
        if (maxOpenOrders <= 0) {
            throw new IllegalArgumentException("maxOpenOrders 必须为正数");
        }
        List<OpenOrder> matching = openOrders().stream()
                .filter(order -> order.symbol.equals(symbol))
                .toList();
        int toCancel = Math.max(0, matching.size() - maxOpenOrders + 1);
        int cancelled = 0;
        for (int i = 0; i < toCancel; i++) {
            if (cancel(matching.get(i).orderId)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * 检查下单额度；不足时先撤销会占用同一资源的旧委托，再重新检查。
     *
     * <p>买单共享账户现金，因此会撤销所有标的的旧买单；卖单只会撤销同一标的的旧卖单。</p>
     *
     * @param symbol   标的代码
     * @param side     买卖方向
     * @param price    用于检查资金的价格
     * @param quantity 数量
     * @return 刷新旧委托后是否具备足够可用额度
     */
    protected boolean ensureCapacityFor(String symbol, Side side, BigDecimal price, long quantity) {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(side, "side");
        if (quantity <= 0) {
            return false;
        }
        if (hasCapacity(symbol, side, price, quantity)) {
            return true;
        }
        if (side == Side.BUY) {
            cancelOpenOrders(order -> order.side == Side.BUY);
        } else {
            cancelOpenOrders(order -> order.side == Side.SELL && order.symbol.equals(symbol));
        }
        return hasCapacity(symbol, side, price, quantity);
    }

    private boolean hasCapacity(String symbol, Side side, BigDecimal price, long quantity) {
        if (side == Side.BUY) {
            return price != null && price.signum() > 0 && account.canAfford(price, quantity);
        }
        return account.getAvailableQuantity(symbol) >= quantity;
    }

    private int cancelOpenOrders(Predicate<OpenOrder> predicate) {
        int cancelled = 0;
        for (OpenOrder order : openOrders()) {
            if (predicate.test(order) && cancel(order.orderId)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    private List<OpenOrder> openOrders() {
        List<OpenOrder> snapshot = new ArrayList<>(openOrders.values());
        snapshot.sort(Comparator.comparingLong(order -> order.sequence));
        return snapshot;
    }

    private void registerOpenOrder(Order order) {
        if (order == null || order.getStatus().isTerminal() || order.getRemainingQuantity() <= 0) {
            return;
        }
        openOrders.put(order.getOrderId(), new OpenOrder(order.getOrderId(), order.getSequence(),
                order.getSymbol(), order.getSide(), order.getRemainingQuantity()));
    }

    private void reduceOpenOrder(long orderId, long filled) {
        openOrders.computeIfPresent(orderId, (ignored, order) -> {
            long remaining = order.remainingQuantity - filled;
            return remaining <= 0 ? null : order.withRemainingQuantity(remaining);
        });
    }

    private static final class OpenOrder {
        private final long orderId;
        private final long sequence;
        private final String symbol;
        private final Side side;
        private final long remainingQuantity;

        private OpenOrder(long orderId, long sequence, String symbol, Side side, long remainingQuantity) {
            this.orderId = orderId;
            this.sequence = sequence;
            this.symbol = symbol;
            this.side = side;
            this.remainingQuantity = remainingQuantity;
        }

        private OpenOrder withRemainingQuantity(long quantity) {
            return new OpenOrder(orderId, sequence, symbol, side, quantity);
        }
    }

    @Override
    public String toString() {
        return String.format("%s[%s 类型=%s 下单=%d 被拒=%d 成交=%d]",
                displayName, agentId, type, getSubmittedCount(), getRejectedCount(), getFilledQuantity());
    }
}
