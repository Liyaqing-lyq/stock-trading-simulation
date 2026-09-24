package edu.cufe.auction.engine;

import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderBookSnapshot;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.PriceLevel;
import edu.cufe.auction.model.Side;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 单标的订单簿：买盘（Bids）与卖盘（Asks）两个优先队列。
 *
 * <p><b>数据结构选择</b>：规格建议“两个优先队列”。这里用
 * {@link TreeMap}{@code <价格, 该价位的 FIFO 队列>} 实现等价语义，但比
 * {@code PriorityQueue} 更适合本场景：</p>
 * <ul>
 *   <li>价格优先：TreeMap 有序键，买盘价格降序、卖盘价格升序，{@code firstEntry()} 即最优价；</li>
 *   <li>时间优先：同价位用 {@link ArrayDeque} 先进先出；</li>
 *   <li>撤单：{@code PriorityQueue} 删除任意元素是 O(n) 且需要“惰性删除”标记，
 *       TreeMap + id 索引可以做到 O(log n) 直删，逻辑更干净。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：本类**不是**线程安全的，所有方法都要求调用方持有
 * {@link MatchingEngine} 的锁（即只在撮合临界区内访问）。</p>
 */
public final class OrderBook {

    private final String symbol;
    private final NavigableMap<BigDecimal, Deque<Order>> bids =
            new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<BigDecimal, Deque<Order>> asks = new TreeMap<>();
    private final Map<Long, Order> restingOrders = new HashMap<>();

    private BigDecimal lastPrice;
    private long cumulativeVolume;

    OrderBook(String symbol) {
        this.symbol = symbol;
    }

    /** @return 标的代码 */
    public String getSymbol() {
        return symbol;
    }

    /** @return 最新成交价；尚无成交返回 null */
    public BigDecimal getLastPrice() {
        return lastPrice;
    }

    /** @return 累计成交量 */
    public long getCumulativeVolume() {
        return cumulativeVolume;
    }

    /** @return 当前挂单笔数（不含已成交/已撤） */
    public int getRestingOrderCount() {
        return restingOrders.size();
    }

    /** @return 是否没有任何挂单 */
    public boolean isEmpty() {
        return restingOrders.isEmpty();
    }

    /**
     * 取下单方向对应的订单簿一侧。
     *
     * @param side 方向
     * @return side == BUY 时返回买盘，否则返回卖盘
     */
    private NavigableMap<BigDecimal, Deque<Order>> bookOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /**
     * 挂单入簿（仅限限价单，市价单不挂单）。
     *
     * @param order 限价订单
     * @throws IllegalArgumentException 传入市价单
     */
    void post(Order order) {
        if (order.getType() != OrderType.LIMIT) {
            throw new IllegalArgumentException("市价单不入簿：" + order);
        }
        Deque<Order> queue = bookOf(order.getSide())
                .computeIfAbsent(order.getLimitPrice(), k -> new ArrayDeque<>());
        queue.addLast(order);
        restingOrders.put(order.getOrderId(), order);
    }

    /**
     * 取对手方最优挂单（买单手看卖盘，卖单手看买盘）。
     *
     * @param incomingSide 新来订单的方向
     * @return 对手方最优订单；对手盘为空返回 null
     */
    Order bestOpposite(Side incomingSide) {
        NavigableMap<BigDecimal, Deque<Order>> opposite = bookOf(incomingSide.opposite());
        while (!opposite.isEmpty()) {
            Map.Entry<BigDecimal, Deque<Order>> first = opposite.firstEntry();
            if (first.getValue().isEmpty()) {
                opposite.remove(first.getKey());
                continue;
            }
            return first.getValue().peekFirst();
        }
        return null;
    }

    /**
     * 从簿中移除订单（全部成交后调用）。
     *
     * @param order 订单
     */
    void remove(Order order) {
        BigDecimal price = order.getLimitPrice();
        if (price != null) {
            Deque<Order> queue = bookOf(order.getSide()).get(price);
            if (queue != null) {
                queue.remove(order);
                if (queue.isEmpty()) {
                    bookOf(order.getSide()).remove(price);
                }
            }
        }
        restingOrders.remove(order.getOrderId());
    }

    /**
     * 按订单 id 查挂单。
     *
     * @param orderId 订单 id
     * @return 挂单；不存在返回 null
     */
    Order findResting(long orderId) {
        return restingOrders.get(orderId);
    }

    /**
     * 当前全部挂单（顺序无语义）。
     *
     * @return 挂单列表副本，供收盘清理/统计使用
     */
    List<Order> restingOrders() {
        return new ArrayList<>(restingOrders.values());
    }

    /** 清空订单簿（收盘时调用，调用方需自行处理挂单的资金释放）。 */
    void clear() {
        bids.clear();
        asks.clear();
        restingOrders.clear();
    }

    /**
     * 更新最新成交价（仅引擎调用）。
     *
     * @param price 成交价
     */
    void setLastPrice(BigDecimal price) {
        this.lastPrice = price;
    }

    /**
     * 累计成交量（仅引擎调用）。
     *
     * @param quantity 本次成交量
     */
    void addVolume(long quantity) {
        this.cumulativeVolume += quantity;
    }

    /**
     * 生成深度快照。
     *
     * @param depth 每侧展示档位数（&lt;= 0 表示不限）
     * @return 只读快照
     */
    public OrderBookSnapshot snapshot(int depth) {
        long now = System.currentTimeMillis();
        return new OrderBookSnapshot(symbol, levels(bids, depth), levels(asks, depth), lastPrice, now);
    }

    private List<PriceLevel> levels(NavigableMap<BigDecimal, Deque<Order>> book, int depth) {
        List<PriceLevel> result = new ArrayList<>();
        for (Map.Entry<BigDecimal, Deque<Order>> entry : book.entrySet()) {
            if (depth > 0 && result.size() >= depth) {
                break;
            }
            long qty = 0L;
            for (Order o : entry.getValue()) {
                qty += o.getRemainingQuantity();
            }
            if (qty > 0) {
                result.add(new PriceLevel(entry.getKey(), qty, entry.getValue().size()));
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return String.format("OrderBook[%s 挂单 %d 笔 last=%s vol=%d]",
                symbol, restingOrders.size(), lastPrice, cumulativeVolume);
    }
}
