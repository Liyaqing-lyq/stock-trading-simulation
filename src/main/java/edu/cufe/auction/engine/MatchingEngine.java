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
import edu.cufe.auction.model.InstrumentProfile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collection;
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
 *       价格改善的差额自动退回可用现金；</li>
 *   <li>禁止同一账户自成交：撮合遇到自有可交叉挂单时取消主动单剩余量并释放冻结，
 *       此前与第三方的成交不回滚（返回 {@code SELF_TRADE_PREVENTED} 业务原因）；</li>
 *   <li>拒单通知与受理通知、成交与行情广播一样，都在释放撮合锁之后触发。</li>
 * </ul>
 */
public final class MatchingEngine implements OrderGateway {

    /** GUI/agent 默认深度档位数。 */
    public static final int DEFAULT_DEPTH = 5;

    private final Object lock = new Object();
    private final Map<String, OrderBook> books = new LinkedHashMap<>();
    private final Map<String, BigDecimal> referencePrices = new LinkedHashMap<>();
    private final Map<String, InstrumentProfile> instrumentProfiles = new LinkedHashMap<>();
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
        this(accounts, publisher, referencePrices, Map.of());
    }

    /**
     * 构造带标的交易制度的撮合引擎。
     *
     * @param accounts 账户管理器
     * @param publisher 行情发布者
     * @param profiles 标的档案，必须覆盖所有交易标的
     */
    public MatchingEngine(AccountManager accounts, MarketDataPublisher publisher,
                          Collection<InstrumentProfile> profiles) {
        this(accounts, publisher, referencePricesOf(profiles), profilesBySymbol(profiles));
    }

    private MatchingEngine(AccountManager accounts, MarketDataPublisher publisher,
                           Map<String, BigDecimal> referencePrices,
                           Map<String, InstrumentProfile> profiles) {
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
        this.instrumentProfiles.putAll(profiles);
        if (!this.instrumentProfiles.isEmpty()
                && !this.instrumentProfiles.keySet().equals(this.referencePrices.keySet())) {
            throw new IllegalArgumentException("标的档案必须与参考价标的完全一致");
        }
    }

    private static Map<String, BigDecimal> referencePricesOf(Collection<InstrumentProfile> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (InstrumentProfile profile : profiles) {
            if (profile == null || prices.put(profile.getSymbol(), profile.getPreviousClose()) != null) {
                throw new IllegalArgumentException("标的档案为空或代码重复");
            }
        }
        return prices;
    }

    private static Map<String, InstrumentProfile> profilesBySymbol(Collection<InstrumentProfile> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        Map<String, InstrumentProfile> result = new LinkedHashMap<>();
        for (InstrumentProfile profile : profiles) {
            if (profile == null || result.put(profile.getSymbol(), profile) != null) {
                throw new IllegalArgumentException("标的档案为空或代码重复");
            }
        }
        return result;
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

    /**
     * 受理订单：校验、冻结、撮合、记账、改簿都在引擎锁内完成；广播与监听者通知在锁外。
     *
     * <p>拒单（入场前校验失败）与受理（含“自成交防护”取消剩余量）是两种不同结果：
     * 前者返回 {@link OrderResult#rejected(String)}，后者返回
     * {@link OrderResult#accepted(Order, List, String)}，并保留已与第三方发生的真实成交。</p>
     *
     * @param request 下单请求
     * @return 受理或拒绝结果
     */
    @Override
    public OrderResult submit(OrderRequest request) {
        Objects.requireNonNull(request, "request");
        Submission submission;
        synchronized (lock) {
            submission = processLocked(request);
        }

        // ---- 锁外副作用：拒单通知 / 成交与行情广播 / 受理通知 ----
        // 监听者可能做 I/O（CSV 落盘）或读引擎状态，绝不能在持有撮合锁时回调。
        if (submission.rejectionReason() != null) {
            notifyRejected(request, submission.rejectionReason());
            return OrderResult.rejected(submission.rejectionReason());
        }
        for (Trade trade : submission.trades()) {
            publisher.publishTrade(trade);
        }
        publisher.publishSnapshot(submission.snapshot());
        OrderResult result = OrderResult.accepted(submission.order(), submission.trades(),
                submission.businessReason());
        for (EngineListener listener : listeners) {
            try {
                listener.onOrderAccepted(result);
            } catch (RuntimeException ex) {
                System.err.println("[MatchingEngine] 监听者 onOrderAccepted 异常：" + ex);
            }
        }
        return result;
    }

    /**
     * 一次受理尝试的中间结果：要么只带拒单原因（订单与成交为空），
     * 要么是已入场订单的完整结果（含可选的业务取消原因）。
     *
     * @param order           已入场订单；拒单时为 null
     * @param trades          本次真实成交；拒单时为空列表
     * @param snapshot        撮合后行情快照；拒单时为 null
     * @param rejectionReason 入场前拒绝原因；受理时为 null
     * @param businessReason  受理后取消剩余量的原因；无则为 null
     */
    private record Submission(Order order, List<Trade> trades, MarketSnapshot snapshot,
                              String rejectionReason, String businessReason) {

        private static Submission rejected(String reason) {
            return new Submission(null, List.of(), null, reason, null);
        }
    }

    /**
     * 在引擎锁内完成一次受理：校验 → 冻结 → 撮合 → 记账 → 生成快照。
     *
     * <p>要求调用方持有 {@link #lock}；本方法不得回调监听者或做 I/O。</p>
     *
     * @param request 下单请求
     * @return 中间结果
     */
    private Submission processLocked(OrderRequest request) {
        if (closed) {
            return Submission.rejected("市场已收盘，不再接受新订单");
        }
        OrderBook book = books.get(request.getSymbol());
        if (book == null) {
            return Submission.rejected("未知标的：" + request.getSymbol());
        }
        if (!accounts.contains(request.getAgentId())) {
            return Submission.rejected("未知账户：" + request.getAgentId());
        }
        InstrumentProfile profile = instrumentProfiles.get(request.getSymbol());
        if (request.getType() == OrderType.LIMIT && profile != null) {
            String priceError = validateLimitPrice(profile, request.getPrice());
            if (priceError != null) {
                return Submission.rejected(priceError);
            }
        }

        BigDecimal freezePrice = null;
        if (request.getSide() == Side.BUY) {
            if (request.getType() == OrderType.LIMIT) {
                freezePrice = request.getPrice();
            } else {
                Order bestAsk = book.bestOpposite(Side.BUY);
                if (bestAsk == null) {
                    return Submission.rejected("市价买单无对手方卖盘（订单簿卖盘为空），已拒绝");
                }
                freezePrice = bestAsk.getLimitPrice();
            }
        }

        Order order = new Order(orderIdSeq.incrementAndGet(), request.getAgentId(), request.getSymbol(),
                request.getSide(), request.getType(), request.getPrice(), request.getQuantity(),
                sequenceSeq.incrementAndGet());
        Account account = accounts.getAccount(request.getAgentId());
        boolean reserved = account.reserve(order.getOrderId(), request.getSymbol(),
                request.getSide(), freezePrice, request.getQuantity());
        if (!reserved) {
            if (request.getSide() == Side.BUY) {
                BigDecimal need = freezePrice.multiply(BigDecimal.valueOf(request.getQuantity()))
                        .setScale(2, RoundingMode.HALF_UP);
                return Submission.rejected(String.format("可用资金不足：需 %s，可用 %s",
                        need.toPlainString(), account.getAvailableCash().toPlainString()));
            }
            return Submission.rejected(String.format("可卖持仓不足：需 %d 股，可用 %d 股",
                    request.getQuantity(), account.getAvailableQuantity(request.getSymbol())));
        }

        List<Trade> trades = new ArrayList<>();
        String businessReason = match(order, book, trades);

        // 市价单剩余部分立即撤销并释放冻结；自成交取消已把订单置为终态，不能重复撤销。
        if (order.getRemainingQuantity() > 0 && order.getType() == OrderType.MARKET
                && !order.getStatus().isTerminal()) {
            order.markCancelled();
            account.release(order.getOrderId());
        }
        return new Submission(order, trades, buildSnapshot(book, DEFAULT_DEPTH), null, businessReason);
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
     * <p><b>自成交规则</b>：价格-时间优先不变；一旦对手方最优挂单属于同一账户，
     * 立即取消主动单的全部剩余量并释放其剩余冻结。此前与第三方的成交保留，
     * 不跳过自有的更优价去吃更差的价，也不改动原挂单。</p>
     *
     * @param taker 新来的主动单
     * @param book  该标的订单簿
     * @param trades 成交结果输出
     * @return 受理后剩余量被业务规则取消的原因；正常全部成交或挂簿时为 null
     */
    private String match(Order taker, OrderBook book, List<Trade> trades) {
        while (taker.getRemainingQuantity() > 0) {
            Order maker = book.bestOpposite(taker.getSide());
            if (maker == null) {
                break;
            }
            BigDecimal tradePrice = maker.getLimitPrice();
            if (!taker.canMatchAt(tradePrice)) {
                break;
            }
            if (maker.getAgentId().equals(taker.getAgentId())) {
                return cancelRemainderForSelfTrade(taker, maker);
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
        return null;
    }

    /**
     * 自成交防护：取消主动单剩余量并释放其剩余冻结，原挂单保持不动。
     *
     * <p>要求调用方持有 {@link #lock}。已发生的成交不回滚、不改动，因此调用方
     * 必须把本方法返回的原因随受理结果一起返回，而不是当作整单拒绝。</p>
     *
     * @param taker 主动单（可能有部分成交）
     * @param maker 触发拦截的自有挂单
     * @return 以 {@code SELF_TRADE_PREVENTED} 开头的业务取消原因
     */
    private String cancelRemainderForSelfTrade(Order taker, Order maker) {
        long remaining = taker.getRemainingQuantity();
        taker.markCancelled();
        accounts.getAccount(taker.getAgentId()).release(taker.getOrderId());
        return String.format(
                "SELF_TRADE_PREVENTED: 剩余 %d 股将与自有挂单 #%d 自成交，已取消剩余量并释放冻结",
                remaining, maker.getOrderId());
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
        InstrumentProfile profile = instrumentProfiles.get(book.getSymbol());
        BigDecimal lowerLimit = profile == null ? null : profile.getLowerLimit();
        BigDecimal upperLimit = profile == null ? null : profile.getUpperLimit();
        BigDecimal tickSize = profile == null
                ? new BigDecimal("0.01") : profile.getTickSize();
        return new MarketSnapshot(book.getSymbol(), book.getLastPrice(),
                referencePrices.get(book.getSymbol()), lowerLimit, upperLimit, tickSize,
                s.getBestBid(), s.getBestAsk(),
                book.getCumulativeVolume(), s.getBids(), s.getAsks(), System.currentTimeMillis());
    }

    private String validateLimitPrice(InstrumentProfile profile, BigDecimal price) {
        if (!profile.isOnTick(price)) {
            return String.format("INVALID_PRICE_TICK: 委托价格 %s 不符合最小变动价位 %s",
                    price.toPlainString(), profile.getTickSize().toPlainString());
        }
        if (price.compareTo(profile.getUpperLimit()) > 0) {
            return String.format("PRICE_OUT_OF_LIMIT: 委托价格 %s 超过涨停价 %s",
                    price.toPlainString(), profile.getUpperLimit().toPlainString());
        }
        if (price.compareTo(profile.getLowerLimit()) < 0) {
            return String.format("PRICE_OUT_OF_LIMIT: 委托价格 %s 低于跌停价 %s",
                    price.toPlainString(), profile.getLowerLimit().toPlainString());
        }
        return null;
    }

    /**
     * 通知监听者订单已被拒绝。要求调用方**不持有** {@link #lock}。
     *
     * @param request 原始请求
     * @param reason  拒绝原因
     */
    private void notifyRejected(OrderRequest request, String reason) {
        for (EngineListener listener : listeners) {
            try {
                listener.onOrderRejected(request, reason);
            } catch (RuntimeException ex) {
                System.err.println("[MatchingEngine] 监听者 onOrderRejected 异常：" + ex);
            }
        }
    }

    @Override
    public String toString() {
        return String.format("MatchingEngine[标的 %s，账户 %d 个，收盘=%s]",
                books.keySet(), accounts.size(), closed);
    }
}
