package edu.cufe.auction.world;

import edu.cufe.auction.engine.MatchPlanner;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.SecuritiesLedger.SellReservation;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 世界订单的纯领域模块：不可变订单表、逐笔成交、派生订单簿与候选状态计算。
 *
 * <p><b>唯一权威</b>：活动订单表是订单状态的唯一事实，订单簿由活动限价订单按
 * 「价格优先、同价 sequence 先入先出」即时派生，不保存第二份可漂移的队列。成交量只由
 * {@link Trade} 明细累计，订单的 {@code filled} 必须等于其成交明细之和。</p>
 *
 * <p><b>资金与股份</b>：现金总余额仍只在 {@link CashLedger}；买方冻结唯一由活动买限价单的
 * {@code 限价 × 剩余量} 派生（{@link #frozenCash}），可用现金为总余额减该冻结
 * （{@link #freeCash}）。卖方冻结唯一来自 {@link SecuritiesLedger} 的具体批次预留，每个活动卖单
 * 必须一一对应一个数量等于其剩余量的预留。</p>
 *
 * <p><b>撮合</b>：复用唯一 {@link MatchPlanner}；成交价取被动方价格，买方获得价格改善；
 * 同一财产 ownerId（取自现金账户，不比较账户 ID）不能自成交，遇到自有报价立即停止并取消主动
 * 剩余量，已与第三方形成的成交保留。买方受理前按「限价 × 全量」或「当时卖一 × 全量」检查可用
 * 现金，不足则整单拒绝，不先成交再截断。</p>
 *
 * <p><b>能力边界</b>：这是受信管理夹具的确定性交易核，model=none。不实现费用、涨跌停、
 * 手数限制、真实交易日历、停牌、出金或角色授权；{@code day} 只是从运行起点计算的实验日，
 * 当日买入批次次实验日才可卖。本类不做 I/O、不读系统时钟，调用方必须在世界日志持久确认后
 * 才发布候选状态。</p>
 */
public final class WorldExchange {

    /** 订单业务受理且未产生成交（挂簿）。 */
    public static final String REASON_ACCEPTED = "ACCEPTED";
    /** 订单全部成交。 */
    public static final String REASON_FILLED = "FILLED";
    /** 限价单部分成交后剩余挂簿。 */
    public static final String REASON_PARTIALLY_FILLED = "PARTIALLY_FILLED";
    /** 主动剩余被撤销（市价单未成交部分或收盘/撤单）。 */
    public static final String REASON_CANCELLED = "CANCELLED";
    /** 遇到同一财产主体的挂单，主动剩余被取消，此前第三方成交保留。 */
    public static final String REASON_SELF_TRADE_PREVENTED = "SELF_TRADE_PREVENTED";
    /** 初始冻结超过可用现金，整单拒绝。 */
    public static final String REASON_INSUFFICIENT_CASH = "INSUFFICIENT_CASH";
    /** 合格可卖批次不足，整单拒绝。 */
    public static final String REASON_INSUFFICIENT_SHARES = "INSUFFICIENT_SHARES";
    /** 市场已收盘，拒绝新申报与后续已排任务。 */
    public static final String REASON_MARKET_CLOSED = "MARKET_CLOSED";
    /** 市价买单没有任何对手方，无法确定初始冻结价。 */
    public static final String REASON_NO_LIQUIDITY = "NO_LIQUIDITY";
    /** 未知订单。 */
    public static final String REASON_UNKNOWN_ORDER = "UNKNOWN_ORDER";
    /** 调用方不是该订单账户的财产主体。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 订单已处于终态（全部成交或已撤销）。 */
    public static final String REASON_TERMINAL_ORDER = "TERMINAL_ORDER";
    /** 订单引用的账户未登记。 */
    public static final String REASON_UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
    /** 订单引用的证券未登记；由持有开局登记的 WorldRuntime 校验并报告。 */
    public static final String REASON_UNKNOWN_SYMBOL = "UNKNOWN_SYMBOL";

    private WorldExchange() {
        // 纯静态工具类
    }

    /** 订单状态；被拒订单不进入订单表，因此没有 REJECTED 状态。 */
    public enum OrderStatus {
        /** 活动：尚未全部成交，限价单剩余仍在簿中。 */
        ACTIVE,
        /** 全部成交。 */
        FILLED,
        /** 剩余量已撤销（部分成交的成交明细保留）。 */
        CANCELLED
    }

    /**
     * 不可变订单。
     *
     * @param orderId          持久订单号，运行内不复用
     * @param taskId           产生本订单的到期任务
     * @param commandId        原始外部命令
     * @param accountId        下单账户
     * @param symbol           证券代码
     * @param side             买卖方向
     * @param type             限价/市价
     * @param limitPrice       限价；市价单为 null
     * @param quantity         原数量
     * @param filled           累计成交数量
     * @param sequence         入簿序号（同价时间优先），运行内不复用
     * @param reservationId    卖单对应的批次预留；买单为 null
     * @param status           订单状态
     * @param acceptedAtMillis 受理时的模拟毫秒
     */
    public record Order(long orderId, String taskId, String commandId, String accountId,
                        String symbol, Side side, OrderType type, BigDecimal limitPrice,
                        long quantity, long filled, long sequence, String reservationId,
                        OrderStatus status, long acceptedAtMillis) {

        /** 校验标识、数量、价格精度与状态一致性。 */
        public Order {
            if (orderId <= 0) {
                throw new IllegalArgumentException("订单号必须为正：" + orderId);
            }
            requireText(taskId, "任务标识");
            requireText(commandId, "命令标识");
            requireText(accountId, "账户");
            requireText(symbol, "证券代码");
            Objects.requireNonNull(side, "买卖方向");
            Objects.requireNonNull(type, "订单类型");
            Objects.requireNonNull(status, "订单状态");
            if (quantity <= 0) {
                throw new IllegalArgumentException("订单数量必须为正：" + quantity);
            }
            if (filled < 0 || filled > quantity) {
                throw new IllegalArgumentException("成交数量越界：" + filled + "/" + quantity);
            }
            if (sequence <= 0) {
                throw new IllegalArgumentException("入簿序号必须为正：" + sequence);
            }
            if (acceptedAtMillis < 0) {
                throw new IllegalArgumentException("受理时间不能为负：" + acceptedAtMillis);
            }
            if (type == OrderType.LIMIT) {
                limitPrice = money2(limitPrice, "限价");
            } else if (limitPrice != null) {
                throw new IllegalArgumentException("市价单不得携带限价");
            }
            if (side == Side.SELL) {
                requireText(reservationId, "卖单预留标识");
            } else if (reservationId != null) {
                throw new IllegalArgumentException("买单不得携带卖出预留");
            }
            if (status == OrderStatus.FILLED && filled != quantity) {
                throw new IllegalArgumentException("全部成交状态要求 filled == quantity");
            }
            if (status == OrderStatus.ACTIVE && filled == quantity) {
                throw new IllegalArgumentException("活动订单不能已经全部成交");
            }
        }

        /** @return 未成交数量（不判断状态；终态订单的剩余量不再生效） */
        public long remaining() {
            return quantity - filled;
        }

        /** @return 是否仍在订单簿中参与撮合 */
        public boolean live() {
            return status == OrderStatus.ACTIVE;
        }

        /** @return 同状态但累计成交增加后的订单 */
        private Order withFill(long quantityFilled) {
            long next = Math.addExact(filled, quantityFilled);
            if (next > quantity) {
                throw new IllegalStateException("成交数量超过订单原数量：" + orderId);
            }
            OrderStatus nextStatus = next == quantity ? OrderStatus.FILLED : status;
            return new Order(orderId, taskId, commandId, accountId, symbol, side, type,
                    limitPrice, quantity, next, sequence, reservationId, nextStatus,
                    acceptedAtMillis);
        }

        /** @return 剩余量被撤销后的订单 */
        private Order cancelled() {
            return new Order(orderId, taskId, commandId, accountId, symbol, side, type,
                    limitPrice, quantity, filled, sequence, reservationId,
                    OrderStatus.CANCELLED, acceptedAtMillis);
        }
    }

    /**
     * 逐笔成交：订单表之外的唯一成交量来源。
     *
     * @param tradeId      持久成交号，运行内不复用
     * @param symbol       证券代码
     * @param price        成交价（被动方价格）
     * @param quantity     成交数量
     * @param buyOrderId   买方订单
     * @param sellOrderId  卖方订单
     * @param buyAccountId 买方账户
     * @param sellAccountId 卖方账户
     * @param timeMillis   成交模拟毫秒
     */
    public record Trade(long tradeId, String symbol, BigDecimal price, long quantity,
                        long buyOrderId, long sellOrderId, String buyAccountId,
                        String sellAccountId, long timeMillis) {

        /** 校验成交标识、价格精度与数量。 */
        public Trade {
            if (tradeId <= 0) {
                throw new IllegalArgumentException("成交号必须为正：" + tradeId);
            }
            requireText(symbol, "证券代码");
            requireText(buyAccountId, "买方账户");
            requireText(sellAccountId, "卖方账户");
            price = money2(price, "成交价");
            if (quantity <= 0) {
                throw new IllegalArgumentException("成交数量必须为正：" + quantity);
            }
            if (buyOrderId <= 0 || sellOrderId <= 0 || buyOrderId == sellOrderId) {
                throw new IllegalArgumentException("成交必须引用两张不同订单");
            }
            if (timeMillis < 0) {
                throw new IllegalArgumentException("成交时间不能为负：" + timeMillis);
            }
        }

        /** @return 成交金额（价格 × 数量，精确到分） */
        public BigDecimal amount() {
            return money(price, quantity);
        }
    }

    /**
     * 一次订单任务的业务结果（管理审计视图，不是已向角色投递的消息）。
     *
     * @param taskId             产生结果的任务
     * @param commandId          原始命令
     * @param kind               任务种类
     * @param accepted           业务是否受理
     * @param reason             结果代码
     * @param orderId            关联订单号：新单号或撤单目标；拒绝/收盘时为空
     * @param tradeIds           本次成交流水号
     * @param cancelledOrderIds  单笔撤单或收盘时撤销的订单号
     * @param timeMillis         执行模拟毫秒
     * @param position           结果所在提交位置
     */
    public record OrderReport(String taskId, String commandId, String kind, boolean accepted,
                              String reason, Long orderId, List<Long> tradeIds,
                              List<Long> cancelledOrderIds, long timeMillis, long position) {

        /** 校验结果字段，并禁止把业务拒绝伪装成新增订单或成交。 */
        public OrderReport {
            requireText(taskId, "任务标识");
            requireText(commandId, "命令标识");
            requireText(kind, "任务种类");
            requireText(reason, "结果代码");
            tradeIds = List.copyOf(Objects.requireNonNull(tradeIds, "成交流水号"));
            cancelledOrderIds = List.copyOf(
                    Objects.requireNonNull(cancelledOrderIds, "撤销订单号"));
            if (timeMillis < 0) {
                throw new IllegalArgumentException("结果时间不能为负：" + taskId);
            }
            if (position <= 0) {
                throw new IllegalArgumentException("结果提交位置必须为正：" + taskId);
            }
            if (!accepted && (orderId != null || !tradeIds.isEmpty())) {
                throw new IllegalArgumentException("业务拒绝不能携带新增订单或成交：" + taskId);
            }
        }
    }

    /**
     * 完整不可变的交易子集状态。
     *
     * @param orders       全部订单（含终态），按订单号排序
     * @param trades       全部成交，按成交号排序
     * @param nextOrderId  下一个订单号（已持久，不从进程计数器恢复）
     * @param nextSequence 下一个入簿序号
     * @param nextTradeId  下一个成交号
     * @param closed       是否已收盘
     * @param reports      按任务标识保存的业务结果，旧结果不可改写
     */
    public record State(List<Order> orders, List<Trade> trades, long nextOrderId,
                        long nextSequence, long nextTradeId, boolean closed,
                        Map<String, OrderReport> reports) {

        /** 规范化并校验订单、成交、计数器与结果。 */
        public State {
            List<Order> orderList = new ArrayList<>(Objects.requireNonNull(orders, "订单表"));
            orderList.sort(Comparator.comparingLong(Order::orderId));
            Set<Long> orderIds = new LinkedHashSet<>();
            Set<Long> sequences = new LinkedHashSet<>();
            long maxOrderId = 0L;
            long maxSequence = 0L;
            for (Order order : orderList) {
                if (!orderIds.add(order.orderId())) {
                    throw new IllegalArgumentException("重复订单号：" + order.orderId());
                }
                if (!sequences.add(order.sequence())) {
                    throw new IllegalArgumentException("重复入簿序号：" + order.sequence());
                }
                maxOrderId = Math.max(maxOrderId, order.orderId());
                maxSequence = Math.max(maxSequence, order.sequence());
            }
            List<Trade> tradeList = new ArrayList<>(Objects.requireNonNull(trades, "成交表"));
            tradeList.sort(Comparator.comparingLong(Trade::tradeId));
            Set<Long> tradeIds = new LinkedHashSet<>();
            long maxTradeId = 0L;
            for (Trade trade : tradeList) {
                if (!tradeIds.add(trade.tradeId())) {
                    throw new IllegalArgumentException("重复成交号：" + trade.tradeId());
                }
                if (!orderIds.contains(trade.buyOrderId()) || !orderIds.contains(trade.sellOrderId())) {
                    throw new IllegalArgumentException("成交引用了未登记订单：" + trade.tradeId());
                }
                maxTradeId = Math.max(maxTradeId, trade.tradeId());
            }
            if (nextOrderId <= maxOrderId) {
                throw new IllegalArgumentException("下一订单号必须大于已用订单号");
            }
            if (nextSequence <= maxSequence) {
                throw new IllegalArgumentException("下一入簿序号必须大于已用序号");
            }
            if (nextTradeId <= maxTradeId) {
                throw new IllegalArgumentException("下一成交号必须大于已用成交号");
            }

            Map<String, OrderReport> reportMap = new TreeMap<>();
            for (Map.Entry<String, OrderReport> entry
                    : Objects.requireNonNull(reports, "订单结果").entrySet()) {
                requireText(entry.getKey(), "结果主键");
                OrderReport report = Objects.requireNonNull(entry.getValue(), "订单结果");
                if (!entry.getKey().equals(report.taskId())) {
                    throw new IllegalArgumentException("结果主键与任务标识不一致：" + entry.getKey());
                }
                if (report.orderId() != null && !orderIds.contains(report.orderId())) {
                    throw new IllegalArgumentException("结果引用了未登记订单：" + report.taskId());
                }
                for (Long tradeId : report.tradeIds()) {
                    if (!tradeIds.contains(tradeId)) {
                        throw new IllegalArgumentException("结果引用了未登记成交：" + tradeId);
                    }
                }
                reportMap.put(entry.getKey(), report);
            }

            orders = List.copyOf(orderList);
            trades = List.copyOf(tradeList);
            reports = Collections.unmodifiableMap(reportMap);
        }

        /**
         * 按订单号查订单。
         *
         * @param orderId 订单号
         * @return 订单；没有返回 null
         */
        public Order order(long orderId) {
            for (Order order : orders) {
                if (order.orderId() == orderId) {
                    return order;
                }
            }
            return null;
        }

        /** @return 全部活动订单（不可变视图） */
        public List<Order> liveOrders() {
            List<Order> live = new ArrayList<>();
            for (Order order : orders) {
                if (order.live()) {
                    live.add(order);
                }
            }
            return List.copyOf(live);
        }

        /**
         * 按任务标识取业务结果。
         *
         * @param taskId 任务标识
         * @return 结果；没有返回 null
         */
        public OrderReport report(String taskId) {
            return reports.get(taskId);
        }

        /**
         * 追加一条业务结果；同一任务已有结果时拒绝改写。
         *
         * @param report 新结果
         * @return 追加后的候选状态
         */
        public State withReport(OrderReport report) {
            Objects.requireNonNull(report, "订单结果");
            if (reports.containsKey(report.taskId())) {
                throw new IllegalStateException("同一任务已有订单结果，不得改写：" + report.taskId());
            }
            Map<String, OrderReport> next = new TreeMap<>(reports);
            next.put(report.taskId(), report);
            return new State(orders, trades, nextOrderId, nextSequence, nextTradeId, closed, next);
        }
    }

    /**
     * 一次候选操作的结果：资产三块与业务结果同时返回，调用方在同一批次持久确认。
     *
     * @param accepted           业务是否受理
     * @param reason             结果代码
     * @param orderId            关联订单号：新单号或撤单目标；拒绝/收盘时为空
     * @param tradeIds           本次成交号
     * @param cancelledOrderIds  本次撤销的订单号
     * @param exchange           候选交易状态
     * @param accounts           候选现金账户
     * @param securities         候选证券台账
     */
    public record Outcome(boolean accepted, String reason, Long orderId, List<Long> tradeIds,
                          List<Long> cancelledOrderIds, State exchange,
                          Map<String, CashLedger.CashAccount> accounts,
                          SecuritiesLedger.State securities) {

        /** 防御性冻结所有集合。 */
        public Outcome {
            requireText(reason, "结果代码");
            tradeIds = List.copyOf(Objects.requireNonNull(tradeIds, "成交号"));
            cancelledOrderIds = List.copyOf(
                    Objects.requireNonNull(cancelledOrderIds, "撤销订单号"));
            Objects.requireNonNull(exchange, "交易状态");
            accounts = Collections.unmodifiableMap(new TreeMap<>(
                    Objects.requireNonNull(accounts, "现金账户")));
            Objects.requireNonNull(securities, "证券状态");
            if (!accepted && (orderId != null || !tradeIds.isEmpty())) {
                throw new IllegalArgumentException("业务拒绝不能携带新增订单或成交");
            }
        }
    }

    /**
     * 初始交易状态：无订单、无成交、计数器从 1 开始、未收盘。
     *
     * @return 空状态
     */
    public static State opening() {
        return new State(List.of(), List.of(), 1L, 1L, 1L, false, Map.of());
    }

    /**
     * 某账户的买单冻结：全部活动买限价单的 {@code 限价 × 剩余量} 之和。
     *
     * @param state     交易状态
     * @param accountId 账户
     * @return 冻结金额（精确到分；无冻结为零）
     */
    public static BigDecimal frozenCash(State state, String accountId) {
        Objects.requireNonNull(state, "交易状态");
        requireText(accountId, "账户");
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Order order : state.orders()) {
            if (order.live() && order.side() == Side.BUY && order.type() == OrderType.LIMIT
                    && order.accountId().equals(accountId)) {
                total = total.add(money(order.limitPrice(), order.remaining()));
            }
        }
        return total;
    }

    /**
     * 某账户的可用现金：现金总余额减买单冻结。现金总余额不在本类保存。
     *
     * @param state     交易状态
     * @param accounts  现金账户表
     * @param accountId 账户
     * @return 可用现金；账户不存在时返回零
     */
    public static BigDecimal freeCash(State state, Map<String, CashLedger.CashAccount> accounts,
                                      String accountId) {
        Objects.requireNonNull(accounts, "现金账户");
        CashLedger.CashAccount account = accounts.get(accountId);
        if (account == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return account.cash().subtract(frozenCash(state, accountId));
    }

    /**
     * 受理并撮合一笔世界订单。
     *
     * <p>顺序固定为：账户/属主/参数校验 → 买方可用现金或卖方合格批次检查 → 分配订单号与序号 →
     * 卖方形成具体批次预留 → 用 {@link MatchPlanner} 计算 → 逐笔按被动价结算现金与股份 →
     * 未成交剩余按限价挂簿、市价或自成交取消。业务拒绝不分配订单号、不产生成交、不改变资产。</p>
     *
     * @param exchange     当前交易状态
     * @param accounts     当前现金账户
     * @param securities   当前证券台账
     * @param taskId       到期任务
     * @param commandId    原始命令
     * @param actorOwnerId 可信调用方解析的财产主体
     * @param accountId    下单账户
     * @param symbol       证券代码
     * @param side         买卖方向
     * @param type         限价/市价
     * @param price        限价；市价必须为 null
     * @param quantity     数量
     * @param nowMillis    执行模拟毫秒
     * @return 候选结果；业务拒绝时资产与状态不变
     */
    public static Outcome submit(State exchange, Map<String, CashLedger.CashAccount> accounts,
                                 SecuritiesLedger.State securities, String taskId,
                                 String commandId, String actorOwnerId, String accountId,
                                 String symbol, Side side, OrderType type, BigDecimal price,
                                 long quantity, long nowMillis) {
        Objects.requireNonNull(exchange, "交易状态");
        Objects.requireNonNull(accounts, "现金账户");
        Objects.requireNonNull(securities, "证券状态");
        requireText(taskId, "任务标识");
        requireText(commandId, "命令标识");
        requireText(actorOwnerId, "财产主体");
        requireText(accountId, "账户");
        requireText(symbol, "证券代码");
        Objects.requireNonNull(side, "买卖方向");
        Objects.requireNonNull(type, "订单类型");
        if (quantity <= 0) {
            throw new IllegalArgumentException("订单数量必须为正：" + quantity);
        }
        if (nowMillis < 0) {
            throw new IllegalArgumentException("执行时间不能为负：" + nowMillis);
        }
        BigDecimal limitPrice = null;
        if (type == OrderType.LIMIT) {
            limitPrice = money2(price, "限价");
        } else if (price != null) {
            throw new IllegalArgumentException("市价单不得携带限价");
        }

        if (exchange.closed()) {
            return reject(REASON_MARKET_CLOSED, exchange, accounts, securities);
        }
        CashLedger.CashAccount account = accounts.get(accountId);
        if (account == null) {
            return reject(REASON_UNKNOWN_ACCOUNT, exchange, accounts, securities);
        }
        if (!account.ownerId().equals(actorOwnerId)) {
            return reject(REASON_NOT_AUTHORIZED, exchange, accounts, securities);
        }

        List<MatchPlanner.RestingQuote> quotes = quotes(exchange, accounts, symbol, side);
        if (side == Side.BUY) {
            BigDecimal freezePrice;
            if (type == OrderType.LIMIT) {
                freezePrice = limitPrice;
            } else {
                if (quotes.isEmpty()) {
                    return reject(REASON_NO_LIQUIDITY, exchange, accounts, securities);
                }
                freezePrice = quotes.get(0).price();
            }
            BigDecimal freeze = money(freezePrice, quantity);
            if (freeCash(exchange, accounts, accountId).compareTo(freeze) < 0) {
                return reject(REASON_INSUFFICIENT_CASH, exchange, accounts, securities);
            }
        } else if (SecuritiesLedger.available(securities, accountId, symbol,
                SecuritiesLedger.day(nowMillis)) < quantity) {
            return reject(REASON_INSUFFICIENT_SHARES, exchange, accounts, securities);
        }

        long orderId = exchange.nextOrderId();
        long sequence = exchange.nextSequence();
        SecuritiesLedger.State securitiesCandidate = securities;
        String reservationId = null;
        if (side == Side.SELL) {
            reservationId = "order-res:" + orderId;
            SecuritiesLedger.Outcome reserved = SecuritiesLedger.reserve(securitiesCandidate,
                    reservationId, accountId, symbol, quantity,
                    SecuritiesLedger.day(nowMillis));
            if (!reserved.accepted()) {
                throw new IllegalStateException("可卖量预检与批次预留不一致：" + reserved.reason());
            }
            securitiesCandidate = reserved.state();
        }

        BigDecimal budget = side == Side.BUY ? freeCash(exchange, accounts, accountId) : null;
        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(side, type, limitPrice,
                quantity, account.ownerId(), budget);
        MatchPlanner.Plan plan = MatchPlanner.plan(incoming, quotes);

        Map<Long, Order> orders = new LinkedHashMap<>();
        for (Order order : exchange.orders()) {
            orders.put(order.orderId(), order);
        }
        Map<String, CashLedger.CashAccount> accountsCandidate = new LinkedHashMap<>(accounts);
        List<Trade> trades = new ArrayList<>(exchange.trades());
        List<Long> tradeIds = new ArrayList<>();
        long nextTradeId = exchange.nextTradeId();
        long filled = 0L;
        for (MatchPlanner.Fill fill : plan.fills()) {
            Order maker = orders.get(fill.makerOrderId());
            if (maker == null || !maker.live() || !maker.symbol().equals(symbol)) {
                throw new IllegalStateException("撮合计划引用了非活动挂单：" + fill.makerOrderId());
            }
            long buyOrderId = side == Side.BUY ? orderId : maker.orderId();
            long sellOrderId = side == Side.BUY ? maker.orderId() : orderId;
            String buyAccountId = side == Side.BUY ? accountId : maker.accountId();
            String sellAccountId = side == Side.BUY ? maker.accountId() : accountId;
            String sellReservationId = side == Side.BUY ? maker.reservationId() : reservationId;

            CashLedger.CashAccount buyer = accountsCandidate.get(buyAccountId);
            CashLedger.CashAccount seller = accountsCandidate.get(sellAccountId);
            if (buyer == null || seller == null) {
                throw new IllegalStateException("成交引用了未登记现金账户");
            }
            BigDecimal amount = money(fill.price(), fill.quantity());
            CashLedger.TransferOutcome cash = CashLedger.computeTransfer(accountsCandidate,
                    buyer.ownerId(), buyAccountId, sellAccountId, amount);
            if (!cash.accepted()) {
                throw new IllegalStateException("成交价与受理冻结不一致，现金无法结算："
                        + cash.reason());
            }
            accountsCandidate = new LinkedHashMap<>(cash.accounts());

            long tradeId = nextTradeId;
            SecuritiesLedger.FillOutcome settled = SecuritiesLedger.fill(securitiesCandidate,
                    "trade-" + tradeId, sellReservationId, buyAccountId, fill.price(),
                    fill.quantity(), nowMillis);
            if (!settled.accepted()) {
                throw new IllegalStateException("成交无法在证券台账结算：" + settled.reason());
            }
            if (settled.amount().compareTo(amount) != 0) {
                throw new IllegalStateException("成交金额与现金结算金额不一致：" + tradeId);
            }
            securitiesCandidate = settled.state();
            nextTradeId = Math.incrementExact(nextTradeId);

            orders.put(maker.orderId(), maker.withFill(fill.quantity()));
            filled = Math.addExact(filled, fill.quantity());
            trades.add(new Trade(tradeId, symbol, fill.price(), fill.quantity(), buyOrderId,
                    sellOrderId, buyAccountId, sellAccountId, nowMillis));
            tradeIds.add(tradeId);
        }

        long remaining = quantity - filled;
        OrderStatus status;
        String reason;
        if (remaining == 0L) {
            status = OrderStatus.FILLED;
            reason = REASON_FILLED;
        } else if (type == OrderType.MARKET || plan.stopReason() == MatchPlanner.StopReason.SELF_TRADE) {
            status = OrderStatus.CANCELLED;
            reason = plan.stopReason() == MatchPlanner.StopReason.SELF_TRADE
                    ? REASON_SELF_TRADE_PREVENTED : REASON_CANCELLED;
            if (side == Side.SELL) {
                SecuritiesLedger.Outcome released = SecuritiesLedger.cancel(securitiesCandidate,
                        reservationId);
                if (!released.accepted()) {
                    throw new IllegalStateException("撤销主动剩余时预留释放失败：" + released.reason());
                }
                securitiesCandidate = released.state();
            }
        } else {
            status = OrderStatus.ACTIVE;
            reason = filled > 0L ? REASON_PARTIALLY_FILLED : REASON_ACCEPTED;
        }

        orders.put(orderId, new Order(orderId, taskId, commandId, accountId, symbol, side, type,
                limitPrice, quantity, filled, sequence, reservationId, status, nowMillis));
        State candidate = new State(new ArrayList<>(orders.values()), trades,
                Math.incrementExact(exchange.nextOrderId()),
                Math.incrementExact(exchange.nextSequence()), nextTradeId,
                exchange.closed(), exchange.reports());
        return new Outcome(true, reason, orderId, tradeIds, List.of(), candidate,
                accountsCandidate, securitiesCandidate);
    }

    /**
     * 撤销一笔活动订单：只释放剩余量，历史成交与被动挂单不变。
     *
     * @param exchange     交易状态
     * @param accounts     现金账户
     * @param securities   证券台账
     * @param actorOwnerId 可信调用方解析的财产主体
     * @param orderId      订单号
     * @param nowMillis    执行模拟毫秒
     * @return 候选结果；未知/越权/终态为业务拒绝且不改变资产
     */
    public static Outcome cancel(State exchange, Map<String, CashLedger.CashAccount> accounts,
                                 SecuritiesLedger.State securities, String actorOwnerId,
                                 long orderId, long nowMillis) {
        Objects.requireNonNull(exchange, "交易状态");
        Objects.requireNonNull(accounts, "现金账户");
        Objects.requireNonNull(securities, "证券状态");
        requireText(actorOwnerId, "财产主体");
        if (orderId <= 0) {
            throw new IllegalArgumentException("订单号必须为正：" + orderId);
        }
        if (nowMillis < 0) {
            throw new IllegalArgumentException("执行时间不能为负：" + nowMillis);
        }
        Order order = exchange.order(orderId);
        if (order == null) {
            return reject(REASON_UNKNOWN_ORDER, exchange, accounts, securities);
        }
        CashLedger.CashAccount account = accounts.get(order.accountId());
        if (account == null) {
            return reject(REASON_UNKNOWN_ACCOUNT, exchange, accounts, securities);
        }
        if (!account.ownerId().equals(actorOwnerId)) {
            return reject(REASON_NOT_AUTHORIZED, exchange, accounts, securities);
        }
        if (!order.live()) {
            return reject(REASON_TERMINAL_ORDER, exchange, accounts, securities);
        }
        SecuritiesLedger.State securitiesCandidate = securities;
        if (order.side() == Side.SELL) {
            SecuritiesLedger.Outcome released = SecuritiesLedger.cancel(securitiesCandidate,
                    order.reservationId());
            if (!released.accepted()) {
                throw new IllegalStateException("活动卖单缺少可释放的预留：" + released.reason());
            }
            securitiesCandidate = released.state();
        }
        List<Order> orders = new ArrayList<>();
        for (Order current : exchange.orders()) {
            orders.add(current.orderId() == orderId ? current.cancelled() : current);
        }
        State candidate = new State(orders, exchange.trades(), exchange.nextOrderId(),
                exchange.nextSequence(), exchange.nextTradeId(), exchange.closed(),
                exchange.reports());
        return new Outcome(true, REASON_CANCELLED, orderId, List.of(), List.of(orderId),
                candidate, accounts, securitiesCandidate);
    }

    /**
     * 收盘：撤销全部活动订单并释放预留与派生冻结，保留历史成交与订单。
     *
     * @param exchange   交易状态
     * @param accounts   现金账户
     * @param securities 证券台账
     * @param nowMillis  执行模拟毫秒
     * @return 候选结果；已收盘时为业务拒绝
     */
    public static Outcome close(State exchange, Map<String, CashLedger.CashAccount> accounts,
                                SecuritiesLedger.State securities, long nowMillis) {
        Objects.requireNonNull(exchange, "交易状态");
        Objects.requireNonNull(accounts, "现金账户");
        Objects.requireNonNull(securities, "证券状态");
        if (nowMillis < 0) {
            throw new IllegalArgumentException("执行时间不能为负：" + nowMillis);
        }
        if (exchange.closed()) {
            return reject(REASON_MARKET_CLOSED, exchange, accounts, securities);
        }
        List<Order> orders = new ArrayList<>();
        List<Long> cancelled = new ArrayList<>();
        SecuritiesLedger.State securitiesCandidate = securities;
        for (Order order : exchange.orders()) {
            if (!order.live()) {
                orders.add(order);
                continue;
            }
            orders.add(order.cancelled());
            cancelled.add(order.orderId());
            if (order.side() == Side.SELL) {
                SecuritiesLedger.Outcome released = SecuritiesLedger.cancel(securitiesCandidate,
                        order.reservationId());
                if (!released.accepted()) {
                    throw new IllegalStateException("收盘时活动卖单缺少可释放的预留："
                            + released.reason());
                }
                securitiesCandidate = released.state();
            }
        }
        State candidate = new State(orders, exchange.trades(), exchange.nextOrderId(),
                exchange.nextSequence(), exchange.nextTradeId(), true, exchange.reports());
        return new Outcome(true, REASON_MARKET_CLOSED, null, List.of(), cancelled, candidate,
                accounts, securitiesCandidate);
    }

    /**
     * 校验交易子集的结构不变量：引用、成交量累计、活动卖单与预留一一对应、冻结不超过现金、
     * 活动限价簿不交叉、收盘后无活动订单。
     *
     * <p>旧结果不可改写与序号只增由运行层按提交链另行校验；本方法只保证当前快照自洽。</p>
     *
     * @param state      交易状态
     * @param accounts   现金账户
     * @param securities 证券台账
     * @throws IOException 任一不变量被破坏
     */
    public static void validate(State state, Map<String, CashLedger.CashAccount> accounts,
                                SecuritiesLedger.State securities) throws IOException {
        Objects.requireNonNull(state, "交易状态");
        Objects.requireNonNull(accounts, "现金账户");
        Objects.requireNonNull(securities, "证券状态");
        Map<Long, Order> orders = new LinkedHashMap<>();
        for (Order order : state.orders()) {
            if (!accounts.containsKey(order.accountId())) {
                throw new IOException("订单引用了未登记账户：" + order.orderId());
            }
            orders.put(order.orderId(), order);
        }
        Set<String> referencedReservations = new LinkedHashSet<>();
        for (Order order : state.orders()) {
            if (order.side() == Side.SELL) {
                SellReservation reservation = SecuritiesLedger.reservation(securities,
                        order.reservationId());
                if (order.live()) {
                    if (reservation == null) {
                        throw new IOException("活动卖单缺少一一对应的预留：" + order.orderId());
                    }
                    if (reservation.quantity() != order.remaining()) {
                        throw new IOException("活动卖单剩余量与预留数量不一致：" + order.orderId());
                    }
                    if (!reservation.accountId().equals(order.accountId())
                            || !reservation.symbol().equals(order.symbol())) {
                        throw new IOException("活动卖单与预留的账户或证券不一致：" + order.orderId());
                    }
                    referencedReservations.add(reservation.reservationId());
                } else if (reservation != null) {
                    throw new IOException("终态卖单仍保留活动预留：" + order.orderId());
                }
            }
        }
        for (SellReservation reservation : securities.reservations()) {
            if (!referencedReservations.contains(reservation.reservationId())) {
                throw new IOException("活动预留没有对应的活动卖单："
                        + reservation.reservationId());
            }
        }

        Map<Long, Long> traded = new LinkedHashMap<>();
        for (Trade trade : state.trades()) {
            Order buy = orders.get(trade.buyOrderId());
            Order sell = orders.get(trade.sellOrderId());
            if (buy == null || sell == null) {
                throw new IOException("成交引用了未登记订单：" + trade.tradeId());
            }
            if (buy.side() != Side.BUY || sell.side() != Side.SELL) {
                throw new IOException("成交的买卖订单方向不一致：" + trade.tradeId());
            }
            if (!buy.symbol().equals(trade.symbol()) || !sell.symbol().equals(trade.symbol())
                    || !buy.accountId().equals(trade.buyAccountId())
                    || !sell.accountId().equals(trade.sellAccountId())) {
                throw new IOException("成交与订单的证券或账户不一致：" + trade.tradeId());
            }
            addTraded(traded, trade.buyOrderId(), trade.quantity());
            addTraded(traded, trade.sellOrderId(), trade.quantity());
        }
        for (Order order : state.orders()) {
            long total = traded.getOrDefault(order.orderId(), 0L);
            if (total != order.filled()) {
                throw new IOException("订单成交数量与成交明细不一致：" + order.orderId()
                        + " 累计 " + order.filled() + "，明细 " + total);
            }
        }

        for (Map.Entry<String, CashLedger.CashAccount> entry : accounts.entrySet()) {
            BigDecimal frozen = frozenCash(state, entry.getKey());
            if (frozen.compareTo(entry.getValue().cash()) > 0) {
                throw new IOException("买单冻结超过账户现金：" + entry.getKey());
            }
        }

        Map<String, BigDecimal> bestBid = new TreeMap<>();
        Map<String, BigDecimal> bestAsk = new TreeMap<>();
        for (Order order : state.orders()) {
            if (!order.live()) {
                continue;
            }
            if (state.closed()) {
                throw new IOException("收盘状态仍存在活动订单：" + order.orderId());
            }
            if (order.type() != OrderType.LIMIT) {
                continue;
            }
            if (order.side() == Side.BUY) {
                bestBid.merge(order.symbol(), order.limitPrice(),
                        (left, right) -> left.compareTo(right) >= 0 ? left : right);
            } else {
                bestAsk.merge(order.symbol(), order.limitPrice(),
                        (left, right) -> left.compareTo(right) <= 0 ? left : right);
            }
        }
        for (Map.Entry<String, BigDecimal> entry : bestBid.entrySet()) {
            BigDecimal ask = bestAsk.get(entry.getKey());
            if (ask != null && entry.getValue().compareTo(ask) >= 0) {
                throw new IOException("活动限价簿交叉：" + entry.getKey());
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static List<MatchPlanner.RestingQuote> quotes(State exchange,
                                                         Map<String, CashLedger.CashAccount> accounts,
                                                         String symbol, Side incomingSide) {
        Side opposite = incomingSide == Side.BUY ? Side.SELL : Side.BUY;
        List<Order> resting = new ArrayList<>();
        for (Order order : exchange.orders()) {
            if (order.live() && order.type() == OrderType.LIMIT
                    && order.side() == opposite && order.symbol().equals(symbol)) {
                resting.add(order);
            }
        }
        Comparator<Order> byPrice = Comparator.comparing(Order::limitPrice);
        if (opposite == Side.BUY) {
            byPrice = byPrice.reversed();
        }
        resting.sort(byPrice.thenComparingLong(Order::sequence));
        List<MatchPlanner.RestingQuote> quotes = new ArrayList<>();
        for (Order order : resting) {
            CashLedger.CashAccount account = accounts.get(order.accountId());
            if (account == null) {
                throw new IllegalStateException("挂单引用了未登记现金账户：" + order.orderId());
            }
            quotes.add(new MatchPlanner.RestingQuote(order.orderId(), account.ownerId(),
                    order.limitPrice(), order.remaining()));
        }
        return List.copyOf(quotes);
    }

    private static Outcome reject(String reason, State exchange,
                                  Map<String, CashLedger.CashAccount> accounts,
                                  SecuritiesLedger.State securities) {
        return new Outcome(false, reason, null, List.of(), List.of(), exchange, accounts,
                securities);
    }

    private static void addTraded(Map<Long, Long> traded, long orderId, long quantity)
            throws IOException {
        try {
            traded.merge(orderId, quantity, Math::addExact);
        } catch (ArithmeticException overflow) {
            throw new IOException("成交数量累计溢出：" + orderId, overflow);
        }
    }

    private static BigDecimal money(BigDecimal price, long quantity) {
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal money2(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + "必须精确到分：" + value, failure);
        }
        if (scaled.signum() <= 0) {
            throw new IllegalArgumentException(field + "必须为正：" + value);
        }
        return scaled;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
