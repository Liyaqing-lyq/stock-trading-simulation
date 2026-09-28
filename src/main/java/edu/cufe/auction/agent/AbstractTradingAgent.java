package edu.cufe.auction.agent;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.engine.OrderGateway;
import edu.cufe.auction.market.MarketDataListener;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.Side;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * agent 基类：缓存最新行情、统一处理下单异常与统计，并**跟踪本 agent 的存活挂单**。
 *
 * <p>行情缓存用 {@link ConcurrentHashMap} 保证广播线程写入、决策线程读取时可见。</p>
 *
 * <p><b>为什么必须跟踪存活挂单</b>：限价单一旦挂入订单簿，其占用的资金（买入）或持仓（卖出）
 * 就被 {@link Account} 冻结，只有「成交」或「撤单」才会释放。若 agent 只挂单、从不撤单，
 * 冻结额度会单调累积直至耗尽——此后 agent 再也下不出任何一张单，
 * 盘口只剩一堆永远不成交的陈旧挂单，市场成交量归零。
 * 真实市场的参与者（尤其做市商）会持续撤单改价（quote refresh）来回收额度，
 * 本类提供 {@link #cancelOpenOrders(Predicate)} / {@link #cancelOldest(int)} 实现同样的能力。</p>
 */
public abstract class AbstractTradingAgent implements TradingAgent, MarketDataListener {

    /** 单次「腾额度」最多连续撤单的次数，防止在极端情况下空转。 */
    protected static final int MAX_EVICT_ATTEMPTS = 8;

    private final String agentId;
    private final String displayName;
    private final AgentType type;
    private final Account account;
    private final OrderGateway gateway;
    private final Map<String, MarketSnapshot> latestSnapshots = new ConcurrentHashMap<>();
    private final AtomicLong submittedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong filledQuantity = new AtomicLong();
    private final AtomicLong cancelledCount = new AtomicLong();
    /** 存活挂单：key = 订单 id，value = 该单的本地快照。 */
    private final Map<Long, LiveOrder> liveOrders = new ConcurrentHashMap<>();
    /** 本地提交序号，用于「从旧到新」的撤单顺序。 */
    private final AtomicLong submitSequence = new AtomicLong();

    /**
     * 存活挂单的本地快照。
     *
     * <p>只用于决定「撤哪一张」，不参与资金/持仓口径计算——
     * 额度是否充足一律以 {@link Account} 的可用现金与可卖数量为准（那边才是权威口径）。</p>
     *
     * @param orderId  引擎分配的订单 id
     * @param symbol   标的代码
     * @param side     买卖方向
     * @param price    限价；市价单为 null
     * @param sequence 本地提交序号（越小越旧）
     */
    public record LiveOrder(long orderId, String symbol, Side side, BigDecimal price, long sequence) {
    }

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

    /**
     * 累计成交股数。
     *
     * <p><b>口径提示</b>：该值只统计「提交瞬间立即成交」的部分；挂单后被对手方吃掉的成交
     * 不计入，因此会系统性低估真实成交量。做严谨对比请以 {@code trades.csv} 为准。</p>
     *
     * @return 累计立即成交股数
     */
    public long getFilledQuantity() {
        return filledQuantity.get();
    }

    /** @return 累计主动撤单笔数 */
    public long getCancelledCount() {
        return cancelledCount.get();
    }

    /** @return 当前仍未终结的挂单数量 */
    public int getLiveOrderCount() {
        return liveOrders.size();
    }

    /**
     * 取存活挂单快照，按「从旧到新」排序。
     *
     * @return 不可变的挂单列表
     */
    protected List<LiveOrder> liveOrders() {
        List<LiveOrder> list = new ArrayList<>(liveOrders.values());
        list.sort(Comparator.comparingLong(LiveOrder::sequence));
        return List.copyOf(list);
    }

    /**
     * 下单并记录结果；异常被吞掉并打印，避免单个 agent 的 bug 拖垮整个模拟。
     *
     * <p>若订单未被立即全部成交（状态为 NEW / PARTIALLY_FILLED），说明它有剩余部分
     * 挂在簿上并占用了冻结额度，这里会登记进存活挂单表，供后续撤单回收。</p>
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
                trackIfResting(result.getOrder());
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

    /** 把仍有剩余、尚未终结的订单登记为存活挂单。 */
    private void trackIfResting(Order order) {
        if (order == null || order.getStatus().isTerminal()) {
            return;
        }
        liveOrders.put(order.getOrderId(), new LiveOrder(order.getOrderId(), order.getSymbol(),
                order.getSide(), order.getLimitPrice(), submitSequence.incrementAndGet()));
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

    /**
     * 按条件撤销存活挂单，回收被冻结的资金 / 持仓。
     *
     * <p>无论引擎返回撤单成功与否，本地记录都会被移除：撤单失败只可能是该单
     * 已经成交或被收盘撤销（已是终态），继续保留记录只会让后续判断失真。</p>
     *
     * @param filter 需要撤销的挂单条件
     * @return 实际撤销成功的笔数
     */
    protected int cancelOpenOrders(Predicate<LiveOrder> filter) {
        int cancelled = 0;
        for (LiveOrder order : liveOrders()) {
            if (!filter.test(order)) {
                continue;
            }
            liveOrders.remove(order.orderId());
            if (cancel(order.orderId())) {
                cancelled++;
                cancelledCount.incrementAndGet();
            }
        }
        return cancelled;
    }

    /**
     * 撤销本 agent 的全部存活挂单。
     *
     * @return 实际撤销成功的笔数
     */
    protected int cancelAllOpenOrders() {
        return cancelOpenOrders(order -> true);
    }

    /**
     * 取指定标的上的存活挂单，按「从旧到新」排序。
     *
     * @param symbol 标的代码
     * @return 该标的的存活挂单列表
     */
    protected List<LiveOrder> liveOrdersOf(String symbol) {
        List<LiveOrder> mine = new ArrayList<>();
        for (LiveOrder order : liveOrders()) {
            if (order.symbol().equals(symbol)) {
                mine.add(order);
            }
        }
        return mine;
    }

    /**
     * 报价刷新：把指定标的的存活挂单压到上限以内（撤掉最旧的那些），
     * 同时回收它们占用的冻结资金 / 持仓。
     *
     * @param symbol   标的代码
     * @param maxQuotes 该标的允许保留的挂单数量上限
     * @return 实际撤销成功的笔数
     */
    protected int trimQuotes(String symbol, int maxQuotes) {
        List<LiveOrder> mine = liveOrdersOf(symbol);
        int excess = mine.size() - maxQuotes;
        if (excess <= 0) {
            return 0;
        }
        List<Long> victims = new ArrayList<>();
        for (int i = 0; i < excess && i < mine.size(); i++) {
            victims.add(mine.get(i).orderId());
        }
        return cancelOpenOrders(order -> victims.contains(order.orderId()));
    }

    /**
     * 判断账户额度是否足够放下这笔单（买入看可用现金，卖出看可卖数量）。
     *
     * @param symbol   标的代码
     * @param side     方向
     * @param price    价格
     * @param quantity 数量
     * @return 足够返回 true
     */
    protected boolean hasCapacityFor(String symbol, Side side, BigDecimal price, long quantity) {
        return side == Side.BUY
                ? account.canAfford(price, quantity)
                : account.getAvailableQuantity(symbol) >= quantity;
    }

    /**
     * 确保额度充足：不足时按「从旧到新」撤掉本标的自己的挂单腾出额度。
     *
     * <p><b>为什么需要它</b>：不做这一步的话，agent 一旦把资金/持仓冻结光，
     * 就只能永久放弃下单（历史 bug：模拟约 40 秒后全场停摆）。
     * 撤单会立即释放冻结额度，因此这是「额度自恢复」的关键。
     * 循环次数有上限，撤无可撤时返回 false，不会空转。</p>
     *
     * @param symbol   标的代码
     * @param side     方向
     * @param price    价格
     * @param quantity 数量
     * @return 腾出足够额度返回 true
     */
    protected boolean ensureCapacityFor(String symbol, Side side, BigDecimal price, long quantity) {
        for (int attempt = 0; attempt <= MAX_EVICT_ATTEMPTS; attempt++) {
            if (hasCapacityFor(symbol, side, price, quantity)) {
                return true;
            }
            // 买入缺的是现金，而现金是全标的共用的 → 可以撤任意标的的挂单来腾；
            // 卖出缺的是持仓，持仓按标的记账 → 只能撤同一标的的挂单。
            List<LiveOrder> mine = side == Side.BUY ? liveOrders() : liveOrdersOf(symbol);
            if (mine.isEmpty()) {
                return false;
            }
            long oldest = mine.get(0).orderId();
            cancelOpenOrders(order -> order.orderId() == oldest);
        }
        return false;
    }

    /**
     * 按「从旧到新」的顺序撤销挂单，用于在额度不足时腾出资金 / 持仓。
     *
     * <p>调用方通常配合额度判断循环使用：
     * {@code while (!hasCapacityFor(...) && cancelOldest(1) > 0) { }}</p>
     *
     * @param maxCount 最多撤销的笔数
     * @return 实际撤销成功的笔数
     */
    protected int cancelOldest(int maxCount) {
        int cancelled = 0;
        for (LiveOrder order : liveOrders()) {
            if (cancelled >= maxCount) {
                break;
            }
            liveOrders.remove(order.orderId());
            if (cancel(order.orderId())) {
                cancelled++;
                cancelledCount.incrementAndGet();
            }
        }
        return cancelled;
    }

    @Override
    public String toString() {
        return String.format("%s[%s 类型=%s 下单=%d 被拒=%d 撤单=%d 存活挂单=%d]",
                displayName, agentId, type, getSubmittedCount(), getRejectedCount(),
                getCancelledCount(), getLiveOrderCount());
    }
}
