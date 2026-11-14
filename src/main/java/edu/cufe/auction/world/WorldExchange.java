package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.cufe.auction.engine.MatchPlanner;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.SecuritiesLedger.SellReservation;
import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankContext;
import edu.cufe.auction.world.bank.BankPayment;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.BankingProfile;
import edu.cufe.auction.world.bank.CashFreeze;
import edu.cufe.auction.world.bank.CustodyBook;
import edu.cufe.auction.world.bank.CustodyPort;
import edu.cufe.auction.world.bank.Fund;
import edu.cufe.auction.world.bank.Money;
import edu.cufe.auction.world.bank.PaymentPort;

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
    /** 资金桶标记不是规范文本（字段不匹配、含内部腿前缀）。 */
    public static final String REASON_FUND_TOKEN_INVALID = "FUND_TOKEN_INVALID";

    /** 资金桶不属于该证券账户的真实财产主体（卖方不能借别人的存款桶收款）。 */
    public static final String REASON_FUND_OWNER_MISMATCH = "FUND_OWNER_MISMATCH";
    /** B7a 只接 CASH/DEPOSIT 两条资金腿；托管桶成交属 B7b。 */
    public static final String REASON_FUND_CUSTODY_UNSUPPORTED = "FUND_CUSTODY_UNSUPPORTED";
    /** 需要银行三态才能结算的腿，但调用方没有提供银行上下文。 */
    public static final String REASON_FUND_BANK_REQUIRED = "FUND_BANK_REQUIRED";
    /** 付款人无法从真实档案解析（不猜、不用自报身份）。 */
    public static final String REASON_FUND_OWNER_UNRESOLVED = "FUND_OWNER_UNRESOLVED";
    /** 成交时该资金桶的可用额（余额 − 成交后冻结）不足。 */
    public static final String REASON_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    /** 出款行准备金不足（跨行/存款付现金）。 */
    public static final String REASON_RESERVE_INSUFFICIENT = "RESERVE_INSUFFICIENT";
    /** B7b：托管池存款桶（{@code DEPOSIT|行|托管人}）是客户的钱，不能作为订单资金桶。 */
    public static final String REASON_POOL_BUCKET_NOT_ORDER_FUNDABLE =
            "POOL_BUCKET_NOT_ORDER_FUNDABLE";

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
     * <p><b>资金桶</b>：{@code fundingToken} 是本单绑定到哪一个真实资金桶的规范文本
     * （{@link Fund#token()}）。可空：旧档订单没有该字段，序列化时省略、读取为 {@code null}，
     * 因此旧档 canonical 与行为逐字不变；{@code null} 的语义由
     * {@link #effectiveFunding()} 唯一解释为 {@code CASH|<accountId>}。买单它是出款桶，
     * 卖单它是收款桶；两者都必须属于本账户的真实财产主体。</p>
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
     * @param fundingToken     资金桶规范文本；旧档/纯现金为 null
     */
    public record Order(long orderId, String taskId, String commandId, String accountId,
                        String symbol, Side side, OrderType type, BigDecimal limitPrice,
                        long quantity, long filled, long sequence, String reservationId,
                        OrderStatus status, long acceptedAtMillis,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String fundingToken) {

        /** 旧 13 参构造：没有显式资金桶，语义等价于 {@code CASH|<accountId>}。 */
        public Order(long orderId, String taskId, String commandId, String accountId,
                     String symbol, Side side, OrderType type, BigDecimal limitPrice,
                     long quantity, long filled, long sequence, String reservationId,
                     OrderStatus status, long acceptedAtMillis) {
            this(orderId, taskId, commandId, accountId, symbol, side, type, limitPrice, quantity,
                    filled, sequence, reservationId, status, acceptedAtMillis, null);
        }

        /** 校验标识、数量、价格精度、状态一致性与资金桶规范文本。 */
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
            if (fundingToken != null) {
                String canonical;
                try {
                    canonical = Fund.parse(fundingToken).token();
                } catch (RuntimeException invalid) {
                    throw new IllegalArgumentException("资金桶标记不是规范文本：" + fundingToken,
                            invalid);
                }
                if (!canonical.equals(fundingToken)) {
                    throw new IllegalArgumentException("资金桶标记必须是规范文本：" + fundingToken);
                }
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

        /**
         * 本单绑定的真实资金桶：显式标记优先，否则唯一解释为 {@code CASH|<accountId>}。
         *
         * @return 资金桶
         */
        public Fund effectiveFunding() {
            return fundingToken == null ? new Fund.Cash(accountId) : Fund.parse(fundingToken);
        }

        /**
         * 资金桶规范文本（不解析；冻结派生与序列化都用它比较）。
         *
         * @return 规范文本
         */
        public String fundingKey() {
            return fundingToken == null ? Fund.Kind.CASH.name() + Fund.SEPARATOR + accountId
                    : fundingToken;
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
                    acceptedAtMillis, fundingToken);
        }

        /** @return 剩余量被撤销后的订单 */
        private Order cancelled() {
            return new Order(orderId, taskId, commandId, accountId, symbol, side, type,
                    limitPrice, quantity, filled, sequence, reservationId,
                    OrderStatus.CANCELLED, acceptedAtMillis, fundingToken);
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
     * 世界订单的资金腿上下文：银行三态 + 冻结档案 + 运行上下文。
     *
     * <p>只有需要存款腿时才由运行层提供；纯现金档案传 {@code null}。这里没有任何第二份现金：
     * 现金仍在 {@link CashLedger}，存款只是 {@link BankState} 的负债，冻结仍由活动买限价单派生。</p>
     *
     * @param bank   银行三态（含账表）
     * @param config 冻结档案（存款条款）
     * @param ctx    运行上下文（运行标识、档案指纹、位置、时点）
     */
    public record FundContext(BankState bank, BankConfig config, BankContext ctx) {

        /** 校验三项都非空。 */
        public FundContext {
            Objects.requireNonNull(bank, "银行三态");
            Objects.requireNonNull(config, "冻结档案");
            Objects.requireNonNull(ctx, "运行上下文");
        }
    }

    /**
     * 带资金腿的候选结果：订单/现金/证券/银行三态在同一候选里返回，调用方同批持久确认。
     *
     * @param accepted           业务是否受理
     * @param reason             结果代码
     * @param orderId            关联订单号：新单号或撤单目标；拒绝/收盘时为空
     * @param tradeIds           本次成交号
     * @param cancelledOrderIds  本次撤销的订单号
     * @param exchange           候选交易状态
     * @param accounts           候选现金账户
     * @param securities         候选证券台账
     * @param bank               候选银行三态；纯现金档案为 null
     */
    public record FundOutcome(boolean accepted, String reason, Long orderId, List<Long> tradeIds,
                              List<Long> cancelledOrderIds, State exchange,
                              Map<String, CashLedger.CashAccount> accounts,
                              SecuritiesLedger.State securities, BankState bank,
                              CustodyBook custody) {

        /** 防御性冻结所有集合，并禁止把业务拒绝伪装成新增订单或成交。 */
        public FundOutcome {
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

        /**
         * 退回订单子集结果（不含银行三态）；只用于纯现金调用方的旧口径。
         *
         * @return 订单结果
         */
        public Outcome outcome() {
            return new Outcome(accepted, reason, orderId, tradeIds, cancelledOrderIds, exchange,
                    accounts, securities);
        }

        /**
         * 旧 8 参构造（B7a 口径）：不带托管明细账，行为与 B7a 逐字一致。
         *
         * @param accepted           业务是否受理
         * @param reason             结果代码
         * @param orderId            关联订单号
         * @param tradeIds           本次成交号
         * @param cancelledOrderIds  本次撤销的订单号
         * @param exchange           候选交易状态
         * @param accounts           候选现金账户
         * @param securities         候选证券台账
         * @param bank               候选银行三态
         */
        public FundOutcome(boolean accepted, String reason, Long orderId, List<Long> tradeIds,
                           List<Long> cancelledOrderIds, State exchange,
                           Map<String, CashLedger.CashAccount> accounts,
                           SecuritiesLedger.State securities, BankState bank) {
            this(accepted, reason, orderId, tradeIds, cancelledOrderIds, exchange, accounts,
                    securities, bank, null);
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
     * 某现金账户的买单冻结：全部活动买限价单的 {@code 限价 × 剩余量} 之和。
     *
     * <p>只统计资金桶就是 {@code CASH|<accountId>} 的订单（旧档订单没有显式标记，全部属于
     * 这一组），因此旧口径与新存款桶订单互不混算。</p>
     *
     * @param state     交易状态
     * @param accountId 账户
     * @return 冻结金额（精确到分；无冻结为零）
     */
    public static BigDecimal frozenCash(State state, String accountId) {
        Objects.requireNonNull(state, "交易状态");
        requireText(accountId, "账户");
        String token = Fund.Kind.CASH.name() + Fund.SEPARATOR + accountId;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Order order : state.orders()) {
            if (order.live() && order.side() == Side.BUY && order.type() == OrderType.LIMIT
                    && order.fundingKey().equals(token)) {
                total = total.add(money(order.limitPrice(), order.remaining()));
            }
        }
        return total;
    }

    /**
     * 某个资金桶上的买单冻结（新名字、新参数类型，不与 {@link #frozenCash} 同名异义）。
     *
     * @param state  交易状态
     * @param bucket 资金桶
     * @return 冻结金额（精确到分；无冻结为零）
     */
    public static BigDecimal frozenIn(State state, Fund bucket) {
        Objects.requireNonNull(state, "交易状态");
        Objects.requireNonNull(bucket, "资金桶");
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Order order : state.orders()) {
            if (order.live() && order.side() == Side.BUY && order.type() == OrderType.LIMIT
                    && order.fundingKey().equals(bucket.token())) {
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
     * 某个资金桶的真实余额：现金看账户，存款看该行该持有人的存款科目（负债方为负）。
     *
     * @param accounts 现金账户
     * @param bank     银行三态；存款桶必须提供
     * @param bucket   资金桶
     * @return 余额（精确到分）
     * @throws IllegalArgumentException 托管桶、未登记账户或未登记存款持有人
     */
    public static BigDecimal balanceIn(Map<String, CashLedger.CashAccount> accounts,
                                       BankState bank, Fund bucket) {
        return balanceIn(accounts, bank, bucket, null);
    }

    /**
     * 某个资金桶的真实余额（B7b 含托管受益权）。
     *
     * <p>{@code CUSTODY|托管人|客户} 的余额是 {@link CustodyBook#detailOf} 的客户明细负债，
     * 不是池存款、不是现金、也不是托管人的账户余额；{@code custody} 为空时仍按原口径拒绝
     * （B7a 行为逐字不变）。</p>
     *
     * @param accounts 现金账户
     * @param bank     银行三态；存款/托管桶必须提供
     * @param bucket   资金桶
     * @param custody  托管明细账；B7a 口径传 null
     * @return 余额（精确到分）
     * @throws IllegalArgumentException 托管桶无托管账、未登记账户或未登记存款持有人
     */
    public static BigDecimal balanceIn(Map<String, CashLedger.CashAccount> accounts,
                                       BankState bank, Fund bucket, CustodyBook custody) {
        Objects.requireNonNull(accounts, "现金账户");
        Objects.requireNonNull(bucket, "资金桶");
        if (bucket instanceof Fund.Cash cash) {
            CashLedger.CashAccount account = accounts.get(cash.accountId());
            if (account == null) {
                throw new IllegalArgumentException("资金桶引用了未登记现金账户：" + cash.accountId());
            }
            return account.cash();
        }
        if (bucket instanceof Fund.Deposit deposit) {
            if (bank == null) {
                throw new IllegalArgumentException(REASON_FUND_BANK_REQUIRED + ":" + bucket.token());
            }
            if (!bank.chart().isBank(deposit.bankKey())) {
                throw new IllegalArgumentException("资金桶引用了未登记银行：" + deposit.bankKey());
            }
            if (!bank.chart().hasParty(deposit.holderKey())
                    && !bank.chart().isCustodian(deposit.holderKey())) {
                throw new IllegalArgumentException("资金桶引用了未登记持有人："
                        + deposit.holderKey());
            }
            return bank.gl(deposit.bankKey(), bank.chart().depositAccountOf(deposit.holderKey()))
                    .negate();
        }
        Fund.Custody beneficial = (Fund.Custody) bucket;
        if (custody == null) {
            throw new IllegalArgumentException(REASON_FUND_CUSTODY_UNSUPPORTED + ":"
                    + bucket.token());
        }
        if (!custody.chart().isCustodian(beneficial.custodianKey())) {
            throw new IllegalArgumentException("托管桶引用了未登记托管人："
                    + beneficial.custodianKey());
        }
        if (!custody.chart().hasParty(beneficial.clientKey())) {
            throw new IllegalArgumentException("托管桶引用了未登记客户："
                    + beneficial.clientKey());
        }
        return custody.detailOf(beneficial.custodianKey(), beneficial.clientKey());
    }

    /**
     * 某个资金桶的可用额：真实余额减该桶买单冻结（{@link #frozenIn}）。
     *
     * @param state    交易状态
     * @param accounts 现金账户
     * @param bank     银行三态；纯现金档案传 null
     * @param bucket   资金桶
     * @return 可用额（可能为负，表示状态本身已不成立）
     */
    public static BigDecimal freeIn(State state, Map<String, CashLedger.CashAccount> accounts,
                                    BankState bank, Fund bucket) {
        return freeIn(state, accounts, bank, bucket, null);
    }

    /**
     * 某个资金桶的可用额（B7b 含托管受益权）：余额减该桶买单冻结。
     *
     * @param state    交易状态
     * @param accounts 现金账户
     * @param bank     银行三态；纯现金档案传 null
     * @param bucket   资金桶
     * @param custody  托管明细账；B7a 口径传 null
     * @return 可用额（可能为负，表示状态本身已不成立）
     */
    public static BigDecimal freeIn(State state, Map<String, CashLedger.CashAccount> accounts,
                                    BankState bank, Fund bucket, CustodyBook custody) {
        Objects.requireNonNull(state, "交易状态");
        return balanceIn(accounts, bank, bucket, custody).subtract(frozenIn(state, bucket));
    }

    /**
     * 当前活动买限价单的逐桶冻结快照（供银行付款腿使用）。
     *
     * <p>键是资金桶规范文本，值是 {@code 限价 × 剩余量} 之和。它只是把派生量整理成
     * {@link CashFreeze} 的输入形状，不新增冻结字段。</p>
     *
     * @param state 交易状态
     * @return 冻结快照
     */
    public static CashFreeze freezeSnapshot(State state) {
        Objects.requireNonNull(state, "交易状态");
        Map<String, BigDecimal> byBucket = new TreeMap<>();
        for (Order order : state.orders()) {
            if (order.live() && order.side() == Side.BUY && order.type() == OrderType.LIMIT) {
                byBucket.merge(order.fundingKey(), money(order.limitPrice(), order.remaining()),
                        BigDecimal::add);
            }
        }
        return new CashFreeze(byBucket);
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
        return submit(exchange, accounts, securities, null, taskId, commandId, actorOwnerId,
                accountId, symbol, side, type, price, quantity, null, nowMillis).outcome();
    }

    /**
     * 受理并撮合一笔世界订单，资金腿可以绑定到显式资金桶。
     *
     * <p>顺序固定为：账户/属主/资金桶校验 → 买方该桶可用额（余额 − 该桶冻结）或卖方合格批次检查 →
     * 分配订单号与序号 → 卖方形成具体批次预留 → 用 {@link MatchPlanner} 计算 →
     * <b>先在候选里扣减已成交剩余量</b>（本笔自己的冻结因此释放）→ 按成交后的订单集合做整份计划的
     * 资金预校验 → 逐笔结算证券腿与资金腿 → 未成交剩余按限价挂簿、市价或自成交取消。
     * 业务拒绝不分配订单号、不产生成交、不改变资产、不改变银行三态。</p>
     *
     * <p><b>资金腿</b>：{@code funding} 为空表示旧口径 {@code CASH|<accountId>}；B7a 只允许
     * CASH 与 DEPOSIT，出现 {@code CUSTODY} 直接协议拒绝（托管成交属 B7b）。CASH↔CASH 沿用
     * {@link CashLedger#computeTransfer}，任一腿为 DEPOSIT 时走唯一入口
     * {@link PaymentPort#pay}，并把“成交后的冻结快照”作为受信输入传入，因此本次成交自己的额度
     * 已释放、其余订单的冻结仍然不可花。</p>
     *
     * <p><b>失败策略</b>：整个提交是原子的。任一腿失败（桶内可用不足、跨行准备金不足、
     * 证券台账拒绝）都返回拒绝结果并保留原交易/现金/证券/银行状态；不跳过失败的 maker 去成交
     * 下一档，不暗补资金。</p>
     *
     * @param exchange     当前交易状态
     * @param accounts     当前现金账户
     * @param securities   当前证券台账
     * @param funds        银行三态与冻结档案；纯现金档案为 null
     * @param taskId       到期任务
     * @param commandId    原始命令
     * @param actorOwnerId 可信调用方解析的财产主体
     * @param accountId    下单账户
     * @param symbol       证券代码
     * @param side         买卖方向
     * @param type         限价/市价
     * @param price        限价；市价必须为 null
     * @param quantity     数量
     * @param funding      资金桶；null 表示 {@code CASH|<accountId>}
     * @param nowMillis    执行模拟毫秒
     * @return 候选结果（含银行三态）；业务拒绝时四块资产都不变
     */
    public static FundOutcome submit(State exchange, Map<String, CashLedger.CashAccount> accounts,
                                     SecuritiesLedger.State securities, FundContext funds,
                                     String taskId, String commandId, String actorOwnerId,
                                     String accountId, String symbol, Side side, OrderType type,
                                     BigDecimal price, long quantity, Fund funding,
                                     long nowMillis) {
        return submit(exchange, accounts, securities, funds, null, taskId, commandId, actorOwnerId,
                accountId, symbol, side, type, price, quantity, funding, nowMillis);
    }

    /**
     * 受理并撮合一笔世界订单，资金腿可绑定到 CASH / DEPOSIT / <b>CUSTODY</b> 任意受支持组合（B7b）。
     *
     * <p>托管腿不另写一套记账：任一侧为 {@code CUSTODY} 时，逐笔调用唯一入口
     * {@link CustodyPort#pay}（同池只改客户明细、跨池/跨行/出入金走真实存款负债腿），
     * 传入的仍是成交后的冻结快照 {@code frozenAfter}。因此本笔自己的限价冻结已释放、
     * 其它活动买单的冻结不可花，托管出金与成交共用同一可用额口径。</p>
     *
     * <p>{@code custody == null}（B7a 与更早档案）时行为逐字不变：{@code CUSTODY} 桶一律
     * {@link #REASON_FUND_CUSTODY_UNSUPPORTED}。</p>
     *
     * @param exchange     当前交易状态
     * @param accounts     当前现金账户
     * @param securities   当前证券台账
     * @param funds        银行三态与冻结档案；纯现金档案为 null
     * @param custody      托管明细账；B7a 口径传 null
     * @param taskId       到期任务
     * @param commandId    原始命令
     * @param actorOwnerId 可信调用方解析的财产主体
     * @param accountId    下单账户
     * @param symbol       证券代码
     * @param side         买卖方向
     * @param type         限价/市价
     * @param price        限价；市价必须为 null
     * @param quantity     数量
     * @param funding      资金桶；null 表示 {@code CASH|<accountId>}
     * @param nowMillis    执行模拟毫秒
     * @return 候选结果（含银行三态与托管明细账）；业务拒绝时全部保持入参原样
     */
    public static FundOutcome submit(State exchange, Map<String, CashLedger.CashAccount> accounts,
                                     SecuritiesLedger.State securities, FundContext funds,
                                     CustodyBook custody, String taskId, String commandId,
                                     String actorOwnerId, String accountId, String symbol,
                                     Side side, OrderType type, BigDecimal price, long quantity,
                                     Fund funding, long nowMillis) {
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
        if (funds == null && funding != null && funding.kind() != Fund.Kind.CASH) {
            return rejectFunded(REASON_FUND_BANK_REQUIRED + ":" + funding.token(), exchange,
                    accounts, securities, funds, custody);
        }
        if (custody != null && funds == null) {
            return rejectFunded(REASON_FUND_BANK_REQUIRED + ":custody", exchange, accounts,
                    securities, funds, custody);
        }
        BigDecimal limitPrice = null;
        if (type == OrderType.LIMIT) {
            limitPrice = money2(price, "限价");
        } else if (price != null) {
            throw new IllegalArgumentException("市价单不得携带限价");
        }

        if (exchange.closed()) {
            return rejectFunded(REASON_MARKET_CLOSED, exchange, accounts, securities, funds);
        }
        CashLedger.CashAccount account = accounts.get(accountId);
        if (account == null) {
            return rejectFunded(REASON_UNKNOWN_ACCOUNT, exchange, accounts, securities, funds);
        }
        if (!account.ownerId().equals(actorOwnerId)) {
            return rejectFunded(REASON_NOT_AUTHORIZED, exchange, accounts, securities, funds);
        }
        Fund bucket = funding;
        if (bucket == null) {
            try {
                bucket = new Fund.Cash(accountId);
            } catch (RuntimeException invalid) {
                return rejectFunded(REASON_FUND_TOKEN_INVALID + ":" + accountId, exchange, accounts,
                        securities, funds);
            }
        }
        String bucketProblem = bucketProblem(funds, custody, accounts, accountId, account, bucket);
        if (bucketProblem != null) {
            return rejectFunded(bucketProblem, exchange, accounts, securities, funds);
        }
        final String fundingKey = bucket.token();

        List<MatchPlanner.RestingQuote> quotes = quotes(exchange, accounts, symbol, side);
        BigDecimal available = null;
        if (side == Side.BUY) {
            BigDecimal freezePrice;
            if (type == OrderType.LIMIT) {
                freezePrice = limitPrice;
            } else {
                if (quotes.isEmpty()) {
                    return rejectFunded(REASON_NO_LIQUIDITY, exchange, accounts, securities, funds);
                }
                freezePrice = quotes.get(0).price();
            }
            BigDecimal freeze = money(freezePrice, quantity);
            try {
                available = freeIn(exchange, accounts, funds == null ? null : funds.bank(), bucket,
                        custody);
            } catch (IllegalArgumentException invalid) {
                return rejectFunded(reasonOf(invalid), exchange, accounts, securities, funds);
            }
            if (available.compareTo(freeze) < 0) {
                return rejectFunded(REASON_INSUFFICIENT_CASH, exchange, accounts, securities, funds);
            }
        } else if (SecuritiesLedger.available(securities, accountId, symbol,
                SecuritiesLedger.day(nowMillis)) < quantity) {
            return rejectFunded(REASON_INSUFFICIENT_SHARES, exchange, accounts, securities, funds);
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

        MatchPlanner.Incoming incoming = new MatchPlanner.Incoming(side, type, limitPrice, quantity,
                account.ownerId(), side == Side.BUY ? available : null);
        MatchPlanner.Plan plan = MatchPlanner.plan(incoming, quotes);

        long filled = plan.filledQuantity();
        long remaining = plan.remaining();
        OrderStatus status;
        String reason;
        if (remaining == 0L) {
            status = OrderStatus.FILLED;
            reason = REASON_FILLED;
        } else if (type == OrderType.MARKET || plan.stopReason() == MatchPlanner.StopReason.SELF_TRADE) {
            status = OrderStatus.CANCELLED;
            reason = plan.stopReason() == MatchPlanner.StopReason.SELF_TRADE
                    ? REASON_SELF_TRADE_PREVENTED : REASON_CANCELLED;
        } else {
            status = OrderStatus.ACTIVE;
            reason = filled > 0L ? REASON_PARTIALLY_FILLED : REASON_ACCEPTED;
        }

        // 成交后的订单集合：maker 剩余量已扣减、主动单按最终形状入表。本笔自己的冻结因此释放，
        // 冻结快照（frozenAfter）才不会被本笔限价算成“不可用”。
        Map<Long, Order> orders = new LinkedHashMap<>();
        for (Order order : exchange.orders()) {
            orders.put(order.orderId(), order);
        }
        Map<Long, Long> makerFills = new LinkedHashMap<>();
        for (MatchPlanner.Fill fill : plan.fills()) {
            Order maker = orders.get(fill.makerOrderId());
            if (maker == null || !maker.live() || !maker.symbol().equals(symbol)) {
                throw new IllegalStateException("撮合计划引用了非活动挂单：" + fill.makerOrderId());
            }
            makerFills.merge(fill.makerOrderId(), fill.quantity(), Math::addExact);
        }
        for (Map.Entry<Long, Long> entry : makerFills.entrySet()) {
            Order maker = orders.get(entry.getKey());
            orders.put(entry.getKey(), maker.withFill(entry.getValue()));
        }
        Fund fundingOfNewOrder = funding;
        orders.put(orderId, new Order(orderId, taskId, commandId, accountId, symbol, side, type,
                limitPrice, quantity, filled, sequence, reservationId, status, nowMillis,
                fundingOfNewOrder == null ? null : fundingOfNewOrder.token()));

        Map<String, BigDecimal> freezeBuckets = new TreeMap<>();
        for (Order order : orders.values()) {
            if (order.live() && order.side() == Side.BUY && order.type() == OrderType.LIMIT) {
                freezeBuckets.merge(order.fundingKey(),
                        money(order.limitPrice(), order.remaining()), BigDecimal::add);
            }
        }
        CashFreeze frozenAfter = new CashFreeze(freezeBuckets);

        // 整份计划预校验：每个买方桶的成交金额合计不得超过“余额 − 成交后冻结”。
        Map<String, BigDecimal> outgoing = new TreeMap<>();
        List<Fund> fillBuyerFunds = new ArrayList<>();
        List<Fund> fillSellerFunds = new ArrayList<>();
        for (MatchPlanner.Fill fill : plan.fills()) {
            Order maker = orders.get(fill.makerOrderId());
            String buyToken = side == Side.BUY ? fundingKey : maker.fundingKey();
            String sellToken = side == Side.BUY ? maker.fundingKey() : fundingKey;
            Fund buyFund;
            Fund sellFund;
            try {
                buyFund = Fund.parse(buyToken);
                sellFund = Fund.parse(sellToken);
            } catch (RuntimeException invalid) {
                return rejectFunded(REASON_FUND_TOKEN_INVALID + ":" + invalid.getMessage(), exchange,
                        accounts, securities, funds);
            }
            if ((buyFund.kind() == Fund.Kind.CUSTODY || sellFund.kind() == Fund.Kind.CUSTODY)
                    && custody == null) {
                return rejectFunded(REASON_FUND_CUSTODY_UNSUPPORTED + ":" + buyToken, exchange,
                        accounts, securities, funds);
            }
            if ((buyFund.kind() == Fund.Kind.CUSTODY || sellFund.kind() == Fund.Kind.CUSTODY)
                    && funds == null) {
                return rejectFunded(REASON_FUND_BANK_REQUIRED + ":" + buyToken, exchange, accounts,
                        securities, funds);
            }
            if (buyFund.kind() == Fund.Kind.DEPOSIT && funds == null) {
                return rejectFunded(REASON_FUND_BANK_REQUIRED + ":" + buyToken, exchange, accounts,
                        securities, funds);
            }
            if (sellFund.kind() == Fund.Kind.DEPOSIT && funds == null) {
                return rejectFunded(REASON_FUND_BANK_REQUIRED + ":" + sellToken, exchange, accounts,
                        securities, funds);
            }
            String makerProblem = makerFundProblem(funds, custody, accounts, maker,
                    side == Side.BUY ? sellFund : buyFund);
            if (makerProblem != null) {
                return rejectFunded(makerProblem, exchange, accounts, securities, funds);
            }
            fillBuyerFunds.add(buyFund);
            fillSellerFunds.add(sellFund);
            outgoing.merge(buyToken, money(fill.price(), fill.quantity()), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : outgoing.entrySet()) {
            Fund bucketOfFill;
            try {
                bucketOfFill = Fund.parse(entry.getKey());
            } catch (RuntimeException invalid) {
                return rejectFunded(REASON_FUND_TOKEN_INVALID + ":" + entry.getKey(), exchange,
                        accounts, securities, funds);
            }
            BigDecimal balance;
            try {
                balance = balanceIn(accounts, funds == null ? null : funds.bank(), bucketOfFill,
                        custody);
            } catch (IllegalArgumentException invalid) {
                return rejectFunded(reasonOf(invalid), exchange, accounts, securities, funds);
            }
            BigDecimal availableAfterFill =
                    balance.subtract(frozenAfter.frozenOf(bucketOfFill));
            if (entry.getValue().compareTo(availableAfterFill) > 0) {
                return rejectFunded(REASON_INSUFFICIENT_FUNDS + ":" + entry.getKey(), exchange,
                        accounts, securities, funds);
            }
        }
        String reserveProblem = reserveProblem(funds, custody, accounts, plan.fills(),
                fillBuyerFunds, fillSellerFunds, frozenAfter);
        if (reserveProblem != null) {
            return rejectFunded(reserveProblem, exchange, accounts, securities, funds);
        }

        Map<String, CashLedger.CashAccount> accountsCandidate = new LinkedHashMap<>(accounts);
        BankState bankCandidate = funds == null ? null : funds.bank();
        CustodyBook custodyCandidate = custody;
        List<Trade> trades = new ArrayList<>(exchange.trades());
        List<Long> tradeIds = new ArrayList<>();
        long nextTradeId = exchange.nextTradeId();
        for (int index = 0; index < plan.fills().size(); index++) {
            MatchPlanner.Fill fill = plan.fills().get(index);
            Order maker = exchange.order(fill.makerOrderId());
            long buyOrderId = side == Side.BUY ? orderId : maker.orderId();
            long sellOrderId = side == Side.BUY ? maker.orderId() : orderId;
            String buyAccountId = side == Side.BUY ? accountId : maker.accountId();
            String sellAccountId = side == Side.BUY ? maker.accountId() : accountId;
            String sellReservationId = side == Side.BUY ? maker.reservationId() : reservationId;
            Fund buyFund = fillBuyerFunds.get(index);
            Fund sellFund = fillSellerFunds.get(index);
            BigDecimal amount = money(fill.price(), fill.quantity());

            CashLedger.CashAccount buyer = accountsCandidate.get(buyAccountId);
            if (buyer == null) {
                throw new IllegalStateException("成交引用了未登记现金账户：" + buyAccountId);
            }
            long tradeId = nextTradeId;
            String settleReason;
            if (buyFund instanceof Fund.Cash buyCash && sellFund instanceof Fund.Cash sellCash) {
                CashLedger.TransferOutcome cash = CashLedger.computeTransfer(accountsCandidate,
                        buyer.ownerId(), buyCash.accountId(), sellCash.accountId(), amount);
                if (!cash.accepted()) {
                    return rejectFunded(REASON_INSUFFICIENT_FUNDS + ":" + cash.reason(), exchange,
                            accounts, securities, funds);
                }
                accountsCandidate = new LinkedHashMap<>(cash.accounts());
                settleReason = null;
            } else if (buyFund.kind() == Fund.Kind.CUSTODY
                    || sellFund.kind() == Fund.Kind.CUSTODY) {
                // B7b：托管腿的唯一入口。同池只改客户明细；跨池/跨行/出入金由 CustodyPort
                // 派生真实存款负债腿，并用同一份成交后冻结快照判可用额。
                String payerKey = payerKeyOf(funds, buyAccountId, buyFund);
                if (payerKey == null) {
                    return rejectFunded(REASON_FUND_OWNER_UNRESOLVED + ":" + buyAccountId, exchange,
                            accounts, securities, funds);
                }
                CustodyLegResult custodyLeg = custodyLeg(funds, custodyCandidate,
                        accountsCandidate, bankCandidate, "trade-" + tradeId, payerKey, buyFund,
                        sellFund, amount, frozenAfter);
                if (custodyLeg.reason() != null) {
                    return rejectFunded(custodyLeg.reason(), exchange, accounts, securities, funds);
                }
                accountsCandidate = custodyLeg.accounts();
                bankCandidate = custodyLeg.bank();
                custodyCandidate = custodyLeg.book();
                settleReason = null;
            } else {
                String payerKey = payerKeyOf(funds, buyAccountId, buyFund);
                if (payerKey == null) {
                    return rejectFunded(REASON_FUND_OWNER_UNRESOLVED + ":" + buyAccountId, exchange,
                            accounts, securities, funds);
                }
                PaymentPortResult paid = payLeg(funds, accountsCandidate, bankCandidate,
                        "trade-" + tradeId, payerKey, buyFund, sellFund, amount, frozenAfter);
                if (paid.reason() != null) {
                    return rejectFunded(paid.reason(), exchange, accounts, securities, funds);
                }
                accountsCandidate = paid.accounts();
                bankCandidate = paid.bank();
                settleReason = null;
            }
            if (settleReason != null) {
                return rejectFunded(settleReason, exchange, accounts, securities, funds);
            }

            SecuritiesLedger.FillOutcome settled = SecuritiesLedger.fill(securitiesCandidate,
                    "trade-" + tradeId, sellReservationId, buyAccountId, fill.price(),
                    fill.quantity(), nowMillis);
            if (!settled.accepted()) {
                return rejectFunded(settled.reason(), exchange, accounts, securities, funds);
            }
            if (settled.amount().compareTo(amount) != 0) {
                throw new IllegalStateException("成交金额与现金结算金额不一致：" + tradeId);
            }
            securitiesCandidate = settled.state();
            nextTradeId = Math.incrementExact(nextTradeId);

            trades.add(new Trade(tradeId, symbol, fill.price(), fill.quantity(), buyOrderId,
                    sellOrderId, buyAccountId, sellAccountId, nowMillis));
            tradeIds.add(tradeId);
        }

        if (status == OrderStatus.CANCELLED && side == Side.SELL) {
            SecuritiesLedger.Outcome released = SecuritiesLedger.cancel(securitiesCandidate,
                    reservationId);
            if (!released.accepted()) {
                throw new IllegalStateException("撤销主动剩余时预留释放失败：" + released.reason());
            }
            securitiesCandidate = released.state();
        }

        if (custodyCandidate != null && custodyCandidate != custody) {
            // 组合校验：银行不变量 + 每池池存款资产 == Σ客户明细 + 身份/现金/非应计。
            java.util.List<String> problems = custodyCandidate.validate(bankCandidate,
                    accountsCandidate, funds.config());
            if (!problems.isEmpty()) {
                return rejectFunded("CUSTODY_TRI_STATE_INVALID:" + problems.get(0), exchange,
                        accounts, securities, funds);
            }
        }
        State candidate = new State(new ArrayList<>(orders.values()), trades,
                Math.incrementExact(exchange.nextOrderId()),
                Math.incrementExact(exchange.nextSequence()), nextTradeId,
                exchange.closed(), exchange.reports());
        return new FundOutcome(true, reason, orderId, tradeIds, List.of(), candidate,
                accountsCandidate, securitiesCandidate, bankCandidate, custodyCandidate);
    }

    /** 一次付款腿的结果：失败带原因，成功带新的现金与银行候选。 */
    private record PaymentPortResult(String reason, Map<String, CashLedger.CashAccount> accounts,
                                     BankState bank) {
    }

    /** 一次托管腿的结果：失败带原因，成功带新的银行/现金/明细账三态。 */
    private record CustodyLegResult(String reason, BankState bank,
                                    Map<String, CashLedger.CashAccount> accounts,
                                    CustodyBook book) {
    }

    /**
     * 执行一条托管成交腿：唯一入口是 {@link CustodyPort#pay}。
     *
     * <p>同池客户之间只改客户明细（池存款与准备金都不动，不虚构外部资金）；跨池/跨行/
     * 出入金由 `CustodyPort` 在同候选里派生真实存款负债腿（跨行受准备金限制），
     * 出款侧按「明细 − 该桶冻结」判可用额，池层冻结非零即拒绝。</p>
     *
     * @param funds          银行三态、冻结档案与运行上下文
     * @param custody        当前托管明细账
     * @param accounts       当前现金候选
     * @param bank           当前银行候选
     * @param paymentId      成交号派生的付款标识（重演可复算）
     * @param payerKey       买方真实档案 key（由订单账户解析，不由请求自报）
     * @param source         买方资金桶
     * @param destination    卖方资金桶
     * @param amount         成交金额（被动价 × 成交数量）
     * @param frozen         成交后的受信冻结快照
     * @return 结果；失败时三态原样返回，由调用方整单拒绝
     */
    private static CustodyLegResult custodyLeg(FundContext funds, CustodyBook custody,
                                               Map<String, CashLedger.CashAccount> accounts,
                                               BankState bank, String paymentId, String payerKey,
                                               Fund source, Fund destination, BigDecimal amount,
                                               CashFreeze frozen) {
        if (funds == null || custody == null) {
            return new CustodyLegResult(REASON_FUND_BANK_REQUIRED + ":" + source.token(), bank,
                    accounts, custody);
        }
        try {
            CustodyPort.Result result = CustodyPort.pay(bank, accounts, custody, funds.config(),
                    funds.ctx(), new BankPayment(paymentId, payerKey, source, destination, amount),
                    frozen);
            if (!result.accepted()) {
                return new CustodyLegResult(result.reason(), bank, accounts, custody);
            }
            return new CustodyLegResult(null, result.bank(),
                    new LinkedHashMap<>(result.accounts()), result.book());
        } catch (IllegalArgumentException invalid) {
            return new CustodyLegResult(REASON_FUND_TOKEN_INVALID + ":" + invalid.getMessage(), bank,
                    accounts, custody);
        }
    }

    /**
     * 执行一条存款/混合资金腿：唯一入口是 {@link PaymentPort#pay}。
     *
     * @param funds      银行三态、冻结档案与运行上下文
     * @param accounts   当前现金候选
     * @param bank       当前银行候选
     * @param paymentId  付款标识（成交号派生，重放确定性）
     * @param payerKey   付款人档案 key（由真实账户解析，不由请求自报）
     * @param source     出款桶
     * @param destination 收款桶
     * @param amount     金额
     * @param frozen     成交后的受信冻结快照
     * @return 结果
     */
    private static PaymentPortResult payLeg(FundContext funds, Map<String, CashLedger.CashAccount> accounts,
                                            BankState bank, String paymentId, String payerKey,
                                            Fund source, Fund destination, BigDecimal amount,
                                            CashFreeze frozen) {
        if (funds == null) {
            return new PaymentPortResult(REASON_FUND_BANK_REQUIRED + ":" + source.token(), accounts,
                    bank);
        }
        try {
            BankConfig config = funds.config();
            BankContext ctx = funds.ctx();
            edu.cufe.auction.world.bank.BankModel.Outcome paid = PaymentPort.pay(bank, accounts,
                    config, ctx, new BankPayment(paymentId, payerKey, source, destination, amount),
                    frozen);
            if (!paid.accepted()) {
                return new PaymentPortResult(paid.reason(), accounts, bank);
            }
            return new PaymentPortResult(null, new LinkedHashMap<>(paid.accounts()), paid.state());
        } catch (IllegalArgumentException invalid) {
            return new PaymentPortResult(REASON_FUND_TOKEN_INVALID + ":" + invalid.getMessage(),
                    accounts, bank);
        }
    }

    /**
     * 付款人档案 key：由真实账户解析，托管池的付款人只能是该池持有人。
     *
     * @return 档案 key；无法解析返回 null（调用方拒绝，不猜）
     */
    private static String payerKeyOf(FundContext funds, String accountId, Fund bucket) {
        if (funds == null) {
            return null;
        }
        if (bucket instanceof Fund.Deposit deposit) {
            return deposit.holderKey();
        }
        BankingProfile profile = funds.bank().chart().profile();
        for (Map.Entry<String, BankingProfile.PartyRef> entry : profile.parties().entrySet()) {
            if (entry.getValue().cashAccountId().equals(accountId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 资金桶是否真属于该证券账户的财产主体：B7a 的 CASH 腿只能是本账户，
     * DEPOSIT 腿必须是同一个真实 owner（托管人按档案声明绑定）。
     *
     * @return 拒绝原因；合法返回 null
     */
    private static String bucketProblem(FundContext funds, CustodyBook custody,
                                        Map<String, CashLedger.CashAccount> accounts,
                                        String accountId, CashLedger.CashAccount account,
                                        Fund bucket) {
        if (bucket.kind() == Fund.Kind.CUSTODY) {
            if (custody == null || funds == null) {
                // B7a 与更早档案：托管桶不能作为订单资金腿（逐字保留原拒绝）。
                return REASON_FUND_CUSTODY_UNSUPPORTED + ":" + bucket.token();
            }
            Fund.Custody beneficial = (Fund.Custody) bucket;
            BankingProfile profile = funds.bank().chart().profile();
            BankingProfile.CustodyRef ref =
                    profile.custodians().get(beneficial.custodianKey());
            if (ref == null) {
                return REASON_UNKNOWN_ACCOUNT + ":custodian:" + beneficial.custodianKey();
            }
            BankingProfile.PartyRef client = profile.parties().get(beneficial.clientKey());
            if (client == null) {
                return REASON_UNKNOWN_ACCOUNT + ":client:" + beneficial.clientKey();
            }
            if (client.ownerId().equals(ref.ownerId())) {
                return REASON_FUND_OWNER_MISMATCH + ":self:" + bucket.token();
            }
            if (!client.ownerId().equals(account.ownerId())) {
                // 不能用别人的托管份额下单/收款（客户资产只认该账户的真实 owner）。
                return REASON_FUND_OWNER_MISMATCH + ":custody:" + bucket.token();
            }
            if (!accounts.containsKey(client.cashAccountId())) {
                return REASON_UNKNOWN_ACCOUNT + ":cash:" + client.cashAccountId();
            }
            return null;
        }
        if (bucket instanceof Fund.Cash cash) {
            if (!cash.accountId().equals(accountId)) {
                return REASON_FUND_OWNER_MISMATCH + ":cash:" + cash.accountId();
            }
            return accounts.containsKey(cash.accountId()) ? null
                    : REASON_UNKNOWN_ACCOUNT + ":cash:" + cash.accountId();
        }
        Fund.Deposit deposit = (Fund.Deposit) bucket;
        if (funds == null) {
            return REASON_FUND_BANK_REQUIRED + ":" + bucket.token();
        }
        if (!funds.bank().chart().isBank(deposit.bankKey())) {
            return REASON_UNKNOWN_ACCOUNT + ":bank:" + deposit.bankKey();
        }
        if (!funds.bank().chart().hasParty(deposit.holderKey())
                && !funds.bank().chart().isCustodian(deposit.holderKey())) {
            return REASON_UNKNOWN_ACCOUNT + ":holder:" + deposit.holderKey();
        }
        if (custody != null && funds.bank().chart().isCustodian(deposit.holderKey())) {
            // 池存款是客户的钱：托管人有账户也不等于它可以拿池去下单。
            return REASON_POOL_BUCKET_NOT_ORDER_FUNDABLE + ":" + bucket.token();
        }
        String owner = funds.bank().chart().ownerIdOf(deposit.holderKey());
        if (!owner.equals(account.ownerId())) {
            return REASON_FUND_OWNER_MISMATCH + ":deposit:" + bucket.token();
        }
        return null;
    }

    /** 已有挂单的资金桶仍必须属于它自己的账户 owner（防止伪造库存档拿走他人钱）。 */
    private static String makerFundProblem(FundContext funds, CustodyBook custody,
                                           Map<String, CashLedger.CashAccount> accounts,
                                           Order maker, Fund bucket) {
        CashLedger.CashAccount account = accounts.get(maker.accountId());
        if (account == null) {
            return REASON_UNKNOWN_ACCOUNT + ":" + maker.accountId();
        }
        return bucketProblem(funds, custody, accounts, maker.accountId(), account, bucket);
    }

    /**
     * 资金桶的结算银行 key：现金返回 null（无准备金行），存款返回该行，
     * 托管桶返回该托管人的结算行（池存款所在行）。
     *
     * @param funds   银行三态上下文
     * @param custody 托管明细账（托管桶必须提供）
     * @param bucket  资金桶
     * @return 银行 key；现金返回 null
     */
    private static String bankOfBucket(FundContext funds, CustodyBook custody, Fund bucket) {
        if (bucket instanceof Fund.Deposit deposit) {
            return deposit.bankKey();
        }
        if (bucket instanceof Fund.Custody beneficial) {
            if (funds == null || custody == null) {
                return null;
            }
            BankingProfile.CustodyRef ref = funds.bank().chart().profile().custodians()
                    .get(beneficial.custodianKey());
            return ref == null ? null : ref.bankId();
        }
        return null;
    }

    /**
     * 跨行准备金预校验：出款行要把存款付给别行存款或付成现金时，准备金必须够。
     *
     * <p>同一档失败即整单拒绝，与执行期 {@code RESERVE_INSUFFICIENT} 同一口径；这里先算，
     * 避免“先改了存款再发现准备金不够”的中间态。</p>
     *
     * @return 拒绝原因；够用返回 null
     */
    private static String reserveProblem(FundContext funds, CustodyBook custody,
                                         Map<String, CashLedger.CashAccount> accounts,
                                         List<MatchPlanner.Fill> fills, List<Fund> buyerFunds,
                                         List<Fund> sellerFunds, CashFreeze frozenAfter) {
        if (funds == null) {
            return null;
        }
        Map<String, BigDecimal> outflow = new TreeMap<>();
        for (int index = 0; index < fills.size(); index++) {
            Fund source = buyerFunds.get(index);
            Fund destination = sellerFunds.get(index);
            String sourceBank = bankOfBucket(funds, custody, source);
            if (sourceBank == null) {
                // CASH 出款在账户内移动，没有准备金要求。
                continue;
            }
            String destinationBank = bankOfBucket(funds, custody, destination);
            boolean destinationCash = destination.kind() == Fund.Kind.CASH;
            boolean crossBank = destinationCash
                    || (destinationBank != null && !destinationBank.equals(sourceBank));
            if (!crossBank) {
                continue;
            }
            outflow.merge(sourceBank,
                    money(fills.get(index).price(), fills.get(index).quantity()), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : outflow.entrySet()) {
            String reserveAccount = funds.bank().chart().reserveAccountOf(entry.getKey());
            CashLedger.CashAccount reserve = accounts.get(reserveAccount);
            BigDecimal balance = reserve == null ? BigDecimal.ZERO.setScale(2) : reserve.cash();
            BigDecimal held;
            try {
                held = frozenAfter.frozenOf(new Fund.Cash(reserveAccount));
            } catch (RuntimeException invalid) {
                held = BigDecimal.ZERO.setScale(2);
            }
            if (balance.subtract(held).compareTo(entry.getValue()) < 0) {
                return REASON_RESERVE_INSUFFICIENT + ":" + entry.getKey();
            }
        }
        return null;
    }

    /** 从纯核异常文本里取出稳定原因码（第一个冒号之前），不吞掉细节。 */
    private static String reasonOf(IllegalArgumentException invalid) {
        String message = invalid.getMessage();
        if (message == null || message.isBlank()) {
            return REASON_FUND_TOKEN_INVALID;
        }
        int colon = message.indexOf(':');
        return colon > 0 ? message.substring(0, colon) : message;
    }

    private static FundOutcome rejectFunded(String reason, State exchange,
                                            Map<String, CashLedger.CashAccount> accounts,
                                            SecuritiesLedger.State securities, FundContext funds) {
        return rejectFunded(reason, exchange, accounts, securities, funds, null);
    }

    /** 拒绝结果：托管账与银行三态都保持入参原样（不改一本账）。 */
    private static FundOutcome rejectFunded(String reason, State exchange,
                                            Map<String, CashLedger.CashAccount> accounts,
                                            SecuritiesLedger.State securities, FundContext funds,
                                            CustodyBook custody) {
        return new FundOutcome(false, reason, null, List.of(), List.of(), exchange, accounts,
                securities, funds == null ? null : funds.bank(), custody);
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
        validate(state, accounts, securities, null);
    }

    /**
     * 校验交易子集的结构不变量，并（当提供银行上下文时）校验资金桶绑定。
     *
     * <p>资金桶约束：非空标记必须是规范文本；CASH 腿只能是本订单账户；DEPOSIT 腿必须属于
     * 同一真实财产主体；每个资金桶的派生冻结不得超过该桶真实余额。没有银行上下文时（纯现金档案，
     * 包括旧 1–18 与旧 19 档）只接受“无标记或 {@code CASH|<自己的账户>}”，
     * 因此伪造的存款腿标记会被拒。</p>
     *
     * @param state      交易状态
     * @param accounts   现金账户
     * @param securities 证券台账
     * @param funds      银行三态与冻结档案；纯现金档案传 null
     * @throws IOException 任一不变量被破坏
     */
    public static void validate(State state, Map<String, CashLedger.CashAccount> accounts,
                                SecuritiesLedger.State securities, FundContext funds)
            throws IOException {
        validate(state, accounts, securities, funds, null);
    }

    /**
     * 校验交易子集的结构不变量（B7b 含托管受益权桶）。
     *
     * <p>托管桶必须是已声明托管人 + 已登记客户 + 与该订单账户同一真实 owner；
     * 池存款桶（{@code DEPOSIT|行|托管人}）在有托管账时不能作为任何订单的资金桶。
     * 其余口径与 4 参版逐字相同。</p>
     *
     * @param state      交易状态
     * @param accounts   现金账户
     * @param securities 证券台账
     * @param funds      银行三态与冻结档案；纯现金档案传 null
     * @param custody    托管明细账；B7a 口径传 null
     * @throws IOException 任一不变量被破坏
     */
    public static void validate(State state, Map<String, CashLedger.CashAccount> accounts,
                                SecuritiesLedger.State securities, FundContext funds,
                                CustodyBook custody)
            throws IOException {
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
        validateFundingBuckets(state, accounts, funds, custody);

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

    /** 资金桶绑定与逐桶冻结上限：没有银行上下文时只接受旧口径。 */
    private static void validateFundingBuckets(State state, Map<String, CashLedger.CashAccount> accounts,
                                               FundContext funds, CustodyBook custody) throws IOException {
        Map<String, BigDecimal> frozenByBucket = new TreeMap<>();
        for (Order order : state.orders()) {
            String token = order.fundingKey();
            Fund bucket;
            try {
                bucket = order.effectiveFunding();
            } catch (RuntimeException invalid) {
                throw new IOException("订单资金桶标记不可解析：" + order.orderId(), invalid);
            }
            if (!bucket.token().equals(token)) {
                throw new IOException("订单资金桶标记不是规范文本：" + order.orderId());
            }
            CashLedger.CashAccount account = accounts.get(order.accountId());
            String problem = funds == null
                    ? (bucket instanceof Fund.Cash cash && cash.accountId().equals(order.accountId())
                    ? null : REASON_FUND_BANK_REQUIRED + ":" + token)
                    : bucketProblem(funds, custody, accounts, order.accountId(), account, bucket);
            if (problem != null) {
                throw new IOException("订单资金桶不成立：" + order.orderId() + " " + problem);
            }
            if (order.live() && order.side() == Side.BUY && order.type() == OrderType.LIMIT) {
                frozenByBucket.merge(token, money(order.limitPrice(), order.remaining()),
                        BigDecimal::add);
            }
        }
        for (Map.Entry<String, BigDecimal> entry : frozenByBucket.entrySet()) {
            Fund bucket;
            try {
                bucket = Fund.parse(entry.getKey());
            } catch (RuntimeException invalid) {
                throw new IOException("资金桶标记不可解析：" + entry.getKey(), invalid);
            }
            BigDecimal balance;
            try {
                balance = balanceIn(accounts, funds == null ? null : funds.bank(), bucket, custody);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("资金桶余额不可解析：" + entry.getKey(), invalid);
            }
            if (entry.getValue().compareTo(balance) > 0) {
                throw new IOException("买单冻结超过资金桶余额：" + entry.getKey());
            }
        }
    }

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
