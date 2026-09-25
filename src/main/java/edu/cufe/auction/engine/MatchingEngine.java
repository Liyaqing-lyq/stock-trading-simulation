package edu.cufe.auction.engine;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.account.AccountManager;
import edu.cufe.auction.market.MarketDataPublisher;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderBookSnapshot;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.model.Trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 撮合引擎：受理订单、按价格-时间优先撮合、维护账户与行情广播。
 *
 * <h2>线程安全模型</h2>
 * <p>整个撮合过程（校验 → 冻结 → 撮合 → 记账 → 更新订单簿）都在单一 {@link #lock}
 * 临界区内完成，等价于规格要求的 “{@code submitOrder()} 必须 synchronized”，
 * 保证任何时刻只有一个线程能改变市场状态。</p>
 * <p>锁顺序约定：<b>引擎锁 → 账户锁</b>（账户内部另有自己的锁），账户层永不回调引擎，
 * 因此不存在反向加锁路径，不会死锁。</p>
 * <p>行情广播、CSV 落盘等副作用**在锁外**执行，避免监听者的慢 I/O 拖住撮合。</p>
 *
 * <h2>价格与结算口径</h2>
 * <ul>
 *   <li>成交价取**被动方（先挂在簿上的那一方）的价格**，与真实交易所连续竞价一致；</li>
 *   <li>限价单未成交部分挂入订单簿；市价单未成交部分立即撤销并释放冻结；</li>
 *   <li>买入在下单时按限价（市价单按当时卖一价）冻结资金，成交时按实际价结算，
 *       价格改善的差额自动退回可用现金。</li>
 * </ul>
 */
public final class MatchingEngine implements OrderGateway {

    /** GUI/agent 默认深度档位数。 */
    public static final int DEFAULT_DEPTH = 5;

    private final Object lock = new Object();
    private final Map<String, OrderBook> books = new LinkedHashMap<>();
    private final Map<String, BigDecimal> referencePrices = new LinkedHashMap<>();
    private final AccountManager accounts;
    private final MarketDataPublisher publisher;
    private final List<EngineListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong orderIdSeq = new AtomicLong();
    private final AtomicLong tradeIdSeq = new AtomicLong();
    private final AtomicLong sequenceSeq = new AtomicLong();

    private volatile boolean closed;

    /**
     * 构造引擎。
     *
     * @param accounts        账户管理器（须已创建好各 agent 账户）
     * @param publisher       行情发布者
     * @param referencePrices 各标的参考价（配置中的初始价，用于涨跌幅与估值兜底）
     * @throws IllegalArgumentException 标的为空或存在 null 值
     */
    public MatchingEngine(AccountManager accounts, MarketDataPublisher publisher,
                          Map<String, BigDecimal> referencePrices) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(referencePrices, "referencePrices");
        if (referencePrices.isEmpty()) {
            throw new IllegalArgumentException("至少需要配置一个标的");
        }
        referencePrices.forEach((symbol, price) -> {
            if (symbol == null || symbol.isBlank() || price == null || price.signum() <= 0) {
                throw new IllegalArgumentException("标的价格配置非法：" + symbol + "=" + price);
            }
            this.books.put(symbol, new OrderBook(symbol));
            this.referencePrices.put(symbol, price);
        });
    }

    /**
     * 注册引擎事件监听者（CSV 记录器、GUI 等）。
     *
     * @param listener 监听者
     */
    public void addListener(EngineListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** @return 已配置的标的代码（保持配置顺序） */
    public List<String> symbols() {
        return Collections.unmodifiableList(new ArrayList<>(books.keySet()));
    }

    /** @return 市场是否已收盘 */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public OrderResult submit(OrderRequest request) {
        Objects.requireNonNull(request, "request");
        List<Trade> trades = new ArrayList<>();
        Order order;
        MarketSnapshot snapshot;

        synchronized (lock) {
            if (closed) {
                return reject(request, "市场已收盘，不再接受新订单");
            }
            OrderBook book = books.get(request.getSymbol());
            if (book == null) {
                return reject(request, "未知标的：" + request.getSymbol());
            }
            if (!accounts.contains(request.getAgentId())) {
                return reject(request, "未知账户：" + request.getAgentId());
            }

            BigDecimal freezePrice = null;
            if (request.getSide() == Side.BUY) {
                if (request.getType() == OrderType.LIMIT) {
                    freezePrice = request.getPrice();
                } else {
                    Order bestAsk = book.bestOpposite(Side.BUY);
                    if (bestAsk == null) {
                        return reject(request, "市价买单无对手方卖盘（订单簿卖盘为空），已拒绝");
                    }
                    freezePrice = bestAsk.getLimitPrice();
                }
            }

            order = new Order(orderIdSeq.incrementAndGet(), request.getAgentId(), request.getSymbol(),
                    request.getSide(), request.getType(), request.getPrice(), request.getQuantity(),
                    sequenceSeq.incrementAndGet());
            Account account = accounts.getAccount(request.getAgentId());
            boolean reserved = account.reserve(order.getOrderId(), request.getSymbol(),
                    request.getSide(), freezePrice, request.getQuantity());
            if (!reserved) {
                if (request.getSide() == Side.BUY) {
                    BigDecimal need = freezePrice.multiply(BigDecimal.valueOf(request.getQuantity()))
                            .setScale(2, RoundingMode.HALF_UP);
                    return reject(request, String.format("可用资金不足：需 %s，可用 %s",
                            need.toPlainString(), account.getAvailableCash().toPlainString()));
                }
                return reject(request, String.format("可卖持仓不足：需 %d 股，可用 %d 股",
                        request.getQuantity(), account.getAvailableQuantity(request.getSymbol())));
            }

            match(order, book, trades);

            if (order.getRemainingQuantity() > 0 && order.getType() == OrderType.MARKET) {
                order.markCancelled();
                account.release(order.getOrderId());
            }
            snapshot = buildSnapshot(book, DEFAULT_DEPTH);
        }

        // ---- 锁外副作用：广播 + 通知监听者 ----
        for (Trade trade : trades) {
            publisher.publishTrade(trade);
        }
        publisher.publishSnapshot(snapshot);
        for (EngineListener listener : listeners) {
            try {
                listener.onOrderAccepted(order, trades);
            } catch (RuntimeException ex) {
                System.err.println("[MatchingEngine] 监听者 onOrderAccepted 异常：" + ex);
            }
        }
        return OrderResult.accepted(order, trades);
    }

    @Override
    public boolean cancel(long orderId) {
        Order cancelled = null;
        MarketSnapshot snapshot = null;
        synchronized (lock) {
            for (OrderBook book : books.values()) {
                Order found = book.findResting(orderId);
                if (found == null) {
                    continue;
                }
                book.remove(found);
                found.markCancelled();
                accounts.getAccount(found.getAgentId()).release(orderId);
                cancelled = found;
                snapshot = buildSnapshot(book, DEFAULT_DEPTH);
                break;
            }
        }
        if (cancelled == null) {
            return false;
        }
        final Order forLog = cancelled;
        for (EngineListener listener : listeners) {
            try {
                listener.onOrderCancelled(forLog);
            } catch (RuntimeException ex) {
                System.err.println("[MatchingEngine] 监听者 onOrderCancelled 异常：" + ex);
            }
        }
        publisher.publishSnapshot(snapshot);
        return true;
    }

    /**
     * 收盘：停止受理新订单，撤销全部挂单并释放冻结资金/持仓。
     *
     * @return 被撤销的挂单数量
     */
    public int closeMarket() {
        List<Order> cancelled = new ArrayList<>();
        Map<String, MarketSnapshot> snapshots = new LinkedHashMap<>();
        synchronized (lock) {
            closed = true;
            for (OrderBook book : books.values()) {
                for (Order order : book.restingOrders()) {
                    order.markCancelled();
                    accounts.getAccount(order.getAgentId()).release(order.getOrderId());
                    cancelled.add(order);
                }
                book.clear();
                snapshots.put(book.getSymbol(), buildSnapshot(book, DEFAULT_DEPTH));
            }
        }
        for (Order order : cancelled) {
            for (EngineListener listener : listeners) {
                try {
                    listener.onOrderCancelled(order);
                } catch (RuntimeException ex) {
                    System.err.println("[MatchingEngine] 监听者 onOrderCancelled 异常：" + ex);
                }
            }
        }
        snapshots.values().forEach(publisher::publishSnapshot);
        return cancelled.size();
    }

    /**
     * 取某标的订单簿快照。
     *
     * @param symbol 标的代码
     * @param depth  每侧档位数（&lt;=0 表示全部）
     * @return 快照；标的不存在返回 null
     */
    public OrderBookSnapshot snapshot(String symbol, int depth) {
        synchronized (lock) {
            OrderBook book = books.get(symbol);
            return book == null ? null : book.snapshot(depth);
        }
    }

    /**
     * 取某标的行情快照。
     *
     * @param symbol 标的代码
     * @param depth  每侧档位数
     * @return 行情快照；标的不存在返回 null
     */
    public MarketSnapshot marketSnapshot(String symbol, int depth) {
        synchronized (lock) {
            OrderBook book = books.get(symbol);
            return book == null ? null : buildSnapshot(book, depth);
        }
    }

    /**
     * 全部标的的挂单只读快照（用于界面展示"我的挂单"、撤单入口）。
     *
     * <p>返回列表本身不可变，但元素是挂单对象引用，其成交量/状态会随后续撮合变化；
     * 因此只用于展示，不要据此做业务判断。</p>
     *
     * @return 当前挂单（按标的与价格-时间优先顺序拼接）
     */
    public List<Order> allRestingOrders() {
        synchronized (lock) {
            List<Order> all = new ArrayList<>();
            books.values().forEach(book -> all.addAll(book.restingOrders()));
            return List.copyOf(all);
        }
    }

    /**
     * 各标的最新价（无成交时用配置参考价兜底）。
     *
     * @return 标的 → 价格
     */
    public Map<String, BigDecimal> lastPrices() {
        synchronized (lock) {
            Map<String, BigDecimal> prices = new LinkedHashMap<>();
            books.forEach((symbol, book) -> prices.put(symbol,
                    book.getLastPrice() != null ? book.getLastPrice() : referencePrices.get(symbol)));
            return prices;
        }
    }

    // ------------------------------------------------------------------
    // 内部：均要求持有 lock
    // ------------------------------------------------------------------

    /**
     * 撮合主循环。要求调用方持有 {@link #lock}。
     *
     * @param taker 新来的主动单
     * @param book  该标的订单簿
     * @param trades 成交结果输出
     */
    private void match(Order taker, OrderBook book, List<Trade> trades) {
        while (taker.getRemainingQuantity() > 0) {
            Order maker = book.bestOpposite(taker.getSide());
            if (maker == null) {
                break;
            }
            BigDecimal tradePrice = maker.getLimitPrice();
            if (!taker.canMatchAt(tradePrice)) {
                break;
            }
            long quantity = Math.min(taker.getRemainingQuantity(), maker.getRemainingQuantity());
            if (taker.getSide() == Side.BUY) {
                quantity = Math.min(quantity, affordableQuantity(taker, tradePrice));
                if (quantity <= 0) {
                    break;
                }
            }
            executeTrade(book, taker, maker, tradePrice, quantity, trades);
            if (maker.isFilled()) {
                book.remove(maker);
            }
        }
        if (taker.getRemainingQuantity() > 0 && taker.getType() == OrderType.LIMIT) {
            book.post(taker);
        }
    }

    /**
     * 买单当前可承受的最大成交量 = 该单剩余冻结 + 账户可用现金。
     * 这样即使市价买单在扫簿过程中价格逐步抬高，也不会把账户打成负现金。
     */
    private long affordableQuantity(Order taker, BigDecimal price) {
        Account buyer = accounts.getAccount(taker.getAgentId());
        BigDecimal budget = buyer.getReservedAmount(taker.getOrderId()).add(buyer.getAvailableCash());
        return budget.divide(price, 0, RoundingMode.DOWN).longValue();
    }

    private void executeTrade(OrderBook book, Order taker, Order maker, BigDecimal price,
                              long quantity, List<Trade> trades) {
        Order buy = taker.getSide() == Side.BUY ? taker : maker;
        Order sell = taker.getSide() == Side.BUY ? maker : taker;

        taker.addFill(quantity);
        maker.addFill(quantity);

        String symbol = book.getSymbol();
        accounts.getAccount(buy.getAgentId()).settleBuy(buy.getOrderId(), symbol, price, quantity);
        accounts.getAccount(sell.getAgentId()).settleSell(sell.getOrderId(), symbol, price, quantity);

        Trade trade = new Trade(tradeIdSeq.incrementAndGet(), symbol, price, quantity,
                buy.getOrderId(), buy.getAgentId(), sell.getOrderId(), sell.getAgentId(),
                System.currentTimeMillis());
        book.setLastPrice(price);
        book.addVolume(quantity);
        trades.add(trade);
    }

    private MarketSnapshot buildSnapshot(OrderBook book, int depth) {
        OrderBookSnapshot s = book.snapshot(depth);
        return new MarketSnapshot(book.getSymbol(), book.getLastPrice(),
                referencePrices.get(book.getSymbol()), s.getBestBid(), s.getBestAsk(),
                book.getCumulativeVolume(), s.getBids(), s.getAsks(), System.currentTimeMillis());
    }

    private OrderResult reject(OrderRequest request, String reason) {
        for (EngineListener listener : listeners) {
            try {
                listener.onOrderRejected(request, reason);
            } catch (RuntimeException ex) {
                System.err.println("[MatchingEngine] 监听者 onOrderRejected 异常：" + ex);
            }
        }
        return OrderResult.rejected(reason);
    }

    @Override
    public String toString() {
        return String.format("MatchingEngine[标的 %s，账户 %d 个，收盘=%s]",
                books.keySet(), accounts.size(), closed);
    }
}
