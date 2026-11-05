package edu.cufe.auction.world;

import edu.cufe.auction.world.OpeningRegistry.ShareClass;

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
import java.util.TreeSet;

/**
 * 有限库存的证券清算夹具：持仓批次、卖出冻结与候选状态计算。
 *
 * <p><b>能力边界</b>：这里只有批次台账与同批清算，不是订单簿、撮合引擎或角色交易授权的实现。
 * 成交只能由管理端的系统夹具排程；本类不判断谁有权下单，也不实现费用、价格限制、停牌、分红或结算周期。
 * 现金仍由 {@link CashLedger} 记账，本类不保存第二份资金。</p>
 *
 * <p><b>数量口径</b>：{@link Lot#remaining()} 是未售总量并**包含冻结部分**；冻结的唯一权威是活动
 * {@link SellReservation} 的批次分配，不再另存一份 frozen 数量。可卖量因此是
 * {@code Σ(remaining − 该批次被预留的数量)}，且只统计 {@link ShareClass#FLOAT} 中
 * {@code eligibleFromDay <= 当前实验日} 的批次。锁定股与库存股计入已发行总数但不能被本夹具卖出。</p>
 *
 * <p><b>时间口径</b>：实验日固定为 {@code floor(模拟毫秒 / 86_400_000)}，从运行起点起算，
 * 不是 UTC 自然日、节假日或真实交易日历。当日买入的批次以 {@code 当日 + 1} 为最早可卖日。</p>
 *
 * <p><b>纯计算</b>：所有方法都不做 I/O、不修改传入状态，只返回候选结果；调用方必须在
 * 世界日志持久确认之后才发布新状态。</p>
 */
public final class SecuritiesLedger {

    /** 实验日长度（模拟毫秒）。固定常量，不读取本机时区或系统日历。 */
    public static final long DAY_MILLIS = 86_400_000L;

    /** 预留成功。 */
    public static final String REASON_RESERVED = "RESERVED";
    /** 撤单成功。 */
    public static final String REASON_CANCELLED = "CANCELLED";
    /** 清算成交成功。 */
    public static final String REASON_FILLED = "FILLED";
    /** 合格可卖数量不足。 */
    public static final String REASON_INSUFFICIENT_SHARES = "INSUFFICIENT_SHARES";
    /** 预留标识已被使用（运行内不复用）。 */
    public static final String REASON_RESERVATION_ID_REUSED = "RESERVATION_ID_REUSED";
    /** 没有该活动预留（未知或已撤/已全部成交）。 */
    public static final String REASON_UNKNOWN_RESERVATION = "UNKNOWN_RESERVATION";
    /** 成交数量超过该预留剩余冻结。 */
    public static final String REASON_FILL_EXCEEDS_RESERVATION = "FILL_EXCEEDS_RESERVATION";

    private SecuritiesLedger() {
    }

    /**
     * 持仓批次：一次取得形成的不可变数量与成本。
     *
     * @param lotId            批次标识；开局批次为稳定标识，买入批次由任务标识派生
     * @param accountId        持股账户
     * @param symbol           证券代码
     * @param remaining        未售总量，**含冻结部分**
     * @param eligibleFromDay  最早可卖实验日
     * @param acquiredAtMillis 取得时的模拟毫秒
     * @param acquiredPosition 取得顺序（先入先出主键，其次比较批次标识）
     * @param costPrice        成本价，精确 4 位
     * @param classification   股份分类
     */
    public record Lot(String lotId, String accountId, String symbol, long remaining,
                      long eligibleFromDay, long acquiredAtMillis, long acquiredPosition,
                      BigDecimal costPrice, ShareClass classification) {

        /** 校验标识、数量、时间与成本精度。 */
        public Lot {
            requireText(lotId, "批次标识");
            requireText(accountId, "持股账户");
            requireText(symbol, "证券代码");
            if (remaining <= 0) {
                throw new IllegalArgumentException("批次剩余数量必须为正：" + lotId + "=" + remaining);
            }
            if (eligibleFromDay < 0) {
                throw new IllegalArgumentException("最早可卖实验日不能为负：" + lotId);
            }
            if (acquiredAtMillis < 0) {
                throw new IllegalArgumentException("取得时间不能为负：" + lotId);
            }
            if (acquiredPosition < 0) {
                throw new IllegalArgumentException("取得顺序不能为负：" + lotId);
            }
            costPrice = cost4(costPrice, "成本价");
            Objects.requireNonNull(classification, "股份分类");
        }
    }

    /**
     * 预留中的一个批次分配。
     *
     * @param lotId    批次标识
     * @param quantity 冻结数量，必须为正
     */
    public record LotQuantity(String lotId, long quantity) {

        /** 校验批次标识与数量。 */
        public LotQuantity {
            requireText(lotId, "批次标识");
            if (quantity <= 0) {
                throw new IllegalArgumentException("冻结数量必须为正：" + lotId + "=" + quantity);
            }
        }
    }

    /**
     * 卖出预留：冻结的唯一权威。
     *
     * <p>数量由 {@link #quantity()} 对各批次分配求和得到；完全成交或撤单后移除活动预留，
     * 其标识登记到 {@link State#usedReservationIds()} 且运行内不再复用。</p>
     *
     * @param reservationId 预留标识
     * @param accountId     卖方账户
     * @param symbol        证券代码
     * @param allocations   批次分配，按先入先出顺序
     */
    public record SellReservation(String reservationId, String accountId, String symbol,
                                  List<LotQuantity> allocations) {

        /** 校验标识并冻结分配列表。 */
        public SellReservation {
            requireText(reservationId, "预留标识");
            requireText(accountId, "卖方账户");
            requireText(symbol, "证券代码");
            List<LotQuantity> copy = new ArrayList<>(Objects.requireNonNull(allocations, "批次分配"));
            if (copy.isEmpty()) {
                throw new IllegalArgumentException("预留必须至少分配一个批次：" + reservationId);
            }
            Set<String> lotIds = new LinkedHashSet<>();
            for (LotQuantity allocation : copy) {
                if (!lotIds.add(allocation.lotId())) {
                    throw new IllegalArgumentException("同一预留内批次重复：" + allocation.lotId());
                }
            }
            allocations = List.copyOf(copy);
        }

        /** @return 冻结总量（各批次分配之和，溢出明确失败） */
        public long quantity() {
            long total = 0L;
            for (LotQuantity allocation : allocations) {
                total = Math.addExact(total, allocation.quantity());
            }
            return total;
        }
    }

    /**
     * 一次任务的业务结果，按 {@code taskId} 保存为管理审计视图。
     *
     * <p>这不是已向角色投递的消息，也不是命令层的受理回报；命令层重发仍返回原
     * {@code SCHEDULED:...} 文本。</p>
     *
     * @param taskId     产生结果的任务
     * @param commandId  原始命令
     * @param kind       任务种类
     * @param accepted   业务是否成功
     * @param reason     结果代码
     * @param timeMillis 执行时的模拟毫秒
     * @param position   结果所在提交位置
     */
    public record ActionReport(String taskId, String commandId, String kind, boolean accepted,
                               String reason, long timeMillis, long position) {

        /** 校验结果字段。 */
        public ActionReport {
            requireText(taskId, "任务标识");
            requireText(commandId, "命令标识");
            requireText(kind, "任务种类");
            requireText(reason, "结果代码");
            if (timeMillis < 0) {
                throw new IllegalArgumentException("结果时间不能为负：" + taskId);
            }
            if (position <= 0) {
                throw new IllegalArgumentException("结果提交位置必须为正：" + taskId);
            }
        }
    }

    /**
     * 完整不可变的证券子集状态。
     *
     * <p>集合深度不可变并按稳定键排序；{@code reports} 以任务标识为键，旧结果不可改写。</p>
     *
     * @param lots               全部持仓批次
     * @param reservations       活动预留
     * @param usedReservationIds 已用预留标识（含活动与已结束）
     * @param reports            按任务标识保存的业务结果
     */
    public record State(List<Lot> lots, List<SellReservation> reservations,
                        Set<String> usedReservationIds, Map<String, ActionReport> reports) {

        /** 规范化并校验批次、预留、冻结与结果。 */
        public State {
            List<Lot> lotList = new ArrayList<>(Objects.requireNonNull(lots, "持仓批次"));
            lotList.sort(Comparator.comparingLong(Lot::acquiredPosition).thenComparing(Lot::lotId));
            Map<String, Lot> lotById = new LinkedHashMap<>();
            for (Lot lot : lotList) {
                if (lotById.putIfAbsent(lot.lotId(), lot) != null) {
                    throw new IllegalArgumentException("重复批次标识：" + lot.lotId());
                }
            }

            List<SellReservation> reservationList =
                    new ArrayList<>(Objects.requireNonNull(reservations, "活动预留"));
            reservationList.sort(Comparator.comparing(SellReservation::reservationId));
            Set<String> activeIds = new LinkedHashSet<>();
            for (SellReservation reservation : reservationList) {
                if (!activeIds.add(reservation.reservationId())) {
                    throw new IllegalArgumentException("重复活动预留：" + reservation.reservationId());
                }
            }

            Set<String> used = new TreeSet<>();
            for (String id : Objects.requireNonNull(usedReservationIds, "已用预留标识")) {
                requireText(id, "已用预留标识");
                used.add(id);
            }
            for (SellReservation reservation : reservationList) {
                if (!used.contains(reservation.reservationId())) {
                    throw new IllegalArgumentException("活动预留未登记到已用标识："
                            + reservation.reservationId());
                }
            }

            Map<String, ActionReport> reportMap = new TreeMap<>();
            for (Map.Entry<String, ActionReport> entry
                    : Objects.requireNonNull(reports, "业务结果").entrySet()) {
                requireText(entry.getKey(), "结果主键");
                ActionReport report = Objects.requireNonNull(entry.getValue(), "业务结果");
                if (!entry.getKey().equals(report.taskId())) {
                    throw new IllegalArgumentException("结果主键与任务标识不一致：" + entry.getKey());
                }
                reportMap.put(entry.getKey(), report);
            }

            Map<String, Long> frozen = new LinkedHashMap<>();
            for (SellReservation reservation : reservationList) {
                for (LotQuantity allocation : reservation.allocations()) {
                    Lot lot = lotById.get(allocation.lotId());
                    if (lot == null) {
                        throw new IllegalArgumentException("预留引用了未登记批次：" + allocation.lotId());
                    }
                    if (!lot.accountId().equals(reservation.accountId())
                            || !lot.symbol().equals(reservation.symbol())) {
                        throw new IllegalArgumentException("预留与批次的账户或证券不一致："
                                + reservation.reservationId());
                    }
                    Long previous = frozen.get(allocation.lotId());
                    frozen.put(allocation.lotId(), previous == null ? allocation.quantity()
                            : Math.addExact(previous, allocation.quantity()));
                }
            }
            for (Map.Entry<String, Long> entry : frozen.entrySet()) {
                if (entry.getValue() > lotById.get(entry.getKey()).remaining()) {
                    throw new IllegalArgumentException("冻结数量超过批次剩余：" + entry.getKey());
                }
            }

            lots = List.copyOf(lotList);
            reservations = List.copyOf(reservationList);
            usedReservationIds = Collections.unmodifiableSet(used);
            reports = Collections.unmodifiableMap(reportMap);
        }

        /**
         * 追加一条业务结果；同一任务已有结果时拒绝，不允许原地改写。
         *
         * @param report 新结果
         * @return 追加后的候选状态
         */
        public State withReport(ActionReport report) {
            Objects.requireNonNull(report, "业务结果");
            if (reports.containsKey(report.taskId())) {
                throw new IllegalStateException("同一任务已有业务结果，不得改写：" + report.taskId());
            }
            Map<String, ActionReport> next = new TreeMap<>(reports);
            next.put(report.taskId(), report);
            return new State(lots, reservations, usedReservationIds, next);
        }

        /** @param taskId 任务标识 @return 已保存的业务结果；没有返回 null */
        public ActionReport report(String taskId) {
            return reports.get(taskId);
        }
    }

    /**
     * 不改变资产的候选结果。
     *
     * @param accepted 业务是否成功
     * @param reason   结果代码
     * @param state    候选状态；拒绝时与原状态相同
     */
    public record Outcome(boolean accepted, String reason, State state) {
    }

    /**
     * 清算成交的候选结果。
     *
     * @param accepted 业务是否成功
     * @param reason   结果代码
     * @param amount   成交金额（价格 × 数量）；拒绝时为 null
     * @param state    候选状态；拒绝时与原状态相同
     */
    public record FillOutcome(boolean accepted, String reason, BigDecimal amount, State state) {
    }

    /**
     * 由开局登记生成初始批次状态。
     *
     * <p>每条分配形成一个批次，标识由规范化顺序生成因而在同一登记下稳定；取得顺序等于该顺序，
     * 初始 {@code FLOAT} 批次在第 0 实验日即可卖。锁定与库存股同样占用已发行股数。</p>
     *
     * @param registry 开局登记
     * @return 初始证券状态
     * @throws IllegalArgumentException 登记缺失或批次合计与发行不一致
     */
    public static State opening(OpeningRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        List<Lot> lots = new ArrayList<>();
        long position = 0L;
        for (OpeningRegistry.ShareAllocation allocation : registry.allocations()) {
            lots.add(new Lot("lot-open-" + position, allocation.accountId(), allocation.symbol(),
                    allocation.quantity(), 0L, 0L, position, allocation.costPrice(),
                    allocation.classification()));
            position = Math.addExact(position, 1L);
        }
        State state = new State(lots, List.of(), Set.of(), Map.of());
        for (OpeningRegistry.Security security : registry.securities()) {
            long total = 0L;
            for (Lot lot : state.lots()) {
                if (lot.symbol().equals(security.symbol())) {
                    total = Math.addExact(total, lot.remaining());
                }
            }
            if (total != security.issuedShares()) {
                throw new IllegalArgumentException("开局批次合计与已发行股数不一致："
                        + security.symbol() + " 发行 " + security.issuedShares() + "，批次 " + total);
            }
        }
        return state;
    }

    /**
     * 实验日：模拟毫秒整除固定日长，从运行起点起算。
     *
     * @param timeMillis 模拟毫秒
     * @return 实验日序号
     */
    public static long day(long timeMillis) {
        if (timeMillis < 0) {
            throw new IllegalArgumentException("模拟时间不能为负：" + timeMillis);
        }
        return timeMillis / DAY_MILLIS;
    }

    /**
     * 合格可卖数量：{@code Σ(remaining − 冻结)}，只统计当日合格的流通批次。
     *
     * @param state     证券状态
     * @param accountId 账户
     * @param symbol    证券代码
     * @param day       当前实验日
     * @return 可卖数量
     * @throws ArithmeticException 数量累计溢出
     */
    public static long available(State state, String accountId, String symbol, long day) {
        Objects.requireNonNull(state, "state");
        requireText(accountId, "账户");
        requireText(symbol, "证券代码");
        if (day < 0) {
            throw new IllegalArgumentException("实验日不能为负：" + day);
        }
        Map<String, Long> frozen = frozenByLot(state);
        long total = 0L;
        for (Lot lot : state.lots()) {
            if (!lot.accountId().equals(accountId) || !lot.symbol().equals(symbol)
                    || lot.classification() != ShareClass.FLOAT || lot.eligibleFromDay() > day) {
                continue;
            }
            Long held = frozen.get(lot.lotId());
            total = Math.addExact(total, lot.remaining() - (held == null ? 0L : held));
        }
        return total;
    }

    /**
     * 预留卖出：在合格流通批次中按取得顺序先入先出冻结数量。
     *
     * @param state         证券状态
     * @param reservationId 预留标识，运行内不复用
     * @param accountId     卖方账户
     * @param symbol        证券代码
     * @param quantity      预留数量
     * @param day           当前实验日
     * @return 候选结果；数量不足或标识重复时返回原因且状态不变
     */
    public static Outcome reserve(State state, String reservationId, String accountId,
                                  String symbol, long quantity, long day) {
        Objects.requireNonNull(state, "state");
        requireText(reservationId, "预留标识");
        requireText(accountId, "账户");
        requireText(symbol, "证券代码");
        if (quantity <= 0) {
            throw new IllegalArgumentException("预留数量必须为正：" + quantity);
        }
        if (day < 0) {
            throw new IllegalArgumentException("实验日不能为负：" + day);
        }
        if (state.usedReservationIds().contains(reservationId)) {
            return reject(REASON_RESERVATION_ID_REUSED, state);
        }
        if (available(state, accountId, symbol, day) < quantity) {
            return reject(REASON_INSUFFICIENT_SHARES, state);
        }
        Map<String, Long> frozen = frozenByLot(state);
        List<LotQuantity> allocations = new ArrayList<>();
        long needed = quantity;
        for (Lot lot : state.lots()) {
            if (needed == 0L) {
                break;
            }
            if (!lot.accountId().equals(accountId) || !lot.symbol().equals(symbol)
                    || lot.classification() != ShareClass.FLOAT || lot.eligibleFromDay() > day) {
                continue;
            }
            Long held = frozen.get(lot.lotId());
            long free = lot.remaining() - (held == null ? 0L : held);
            if (free <= 0L) {
                continue;
            }
            long take = Math.min(free, needed);
            allocations.add(new LotQuantity(lot.lotId(), take));
            needed -= take;
        }
        if (needed != 0L) {
            // available 已核对总量；走到这里说明冻结计算与批次不一致，不产生部分预留
            throw new IllegalStateException("可卖量与批次冻结不一致，无法完成预留：" + reservationId);
        }
        List<SellReservation> reservations = new ArrayList<>(state.reservations());
        reservations.add(new SellReservation(reservationId, accountId, symbol, allocations));
        Set<String> used = new TreeSet<>(state.usedReservationIds());
        used.add(reservationId);
        return new Outcome(true, REASON_RESERVED,
                new State(state.lots(), reservations, used, state.reports()));
    }

    /**
     * 撤销预留：只释放尚未成交的数量。
     *
     * @param state         证券状态
     * @param reservationId 预留标识
     * @return 候选结果；没有该活动预留时返回原因且状态不变
     */
    public static Outcome cancel(State state, String reservationId) {
        Objects.requireNonNull(state, "state");
        requireText(reservationId, "预留标识");
        SellReservation found = reservation(state, reservationId);
        if (found == null) {
            return reject(REASON_UNKNOWN_RESERVATION, state);
        }
        List<SellReservation> reservations = new ArrayList<>(state.reservations());
        reservations.remove(found);
        return new Outcome(true, REASON_CANCELLED,
                new State(state.lots(), reservations, state.usedReservationIds(), state.reports()));
    }

    /**
     * 同批清算成交：消耗预留冻结、减少卖方批次、生成买方次日可卖批次。
     *
     * <p>成交金额为 {@code BigDecimal 价格 × long 数量}，不做浮点运算。买卖双方是否为同一
     * 受益主体由运行层按账户属主判断，本方法只处理证券台账。</p>
     *
     * @param state          证券状态
     * @param taskId         产生本次成交的任务标识，用于派生买入批次标识
     * @param reservationId  卖方预留标识
     * @param buyerAccountId 买方账户
     * @param price          成交价，精确到分
     * @param quantity       成交数量
     * @param nowMillis      成交时的模拟毫秒
     * @return 候选结果；拒绝时状态不变且金额为 null
     */
    public static FillOutcome fill(State state, String taskId, String reservationId,
                                   String buyerAccountId, BigDecimal price, long quantity,
                                   long nowMillis) {
        Objects.requireNonNull(state, "state");
        requireText(taskId, "任务标识");
        requireText(reservationId, "预留标识");
        requireText(buyerAccountId, "买方账户");
        BigDecimal normalized = money2(price, "成交价");
        if (quantity <= 0) {
            throw new IllegalArgumentException("成交数量必须为正：" + quantity);
        }
        long day = day(nowMillis);

        SellReservation reservation = reservation(state, reservationId);
        if (reservation == null) {
            return new FillOutcome(false, REASON_UNKNOWN_RESERVATION, null, state);
        }
        if (quantity > reservation.quantity()) {
            return new FillOutcome(false, REASON_FILL_EXCEEDS_RESERVATION, null, state);
        }
        String buyerLotId = "lot-fill:" + taskId;
        if (state.lots().stream().anyMatch(lot -> lot.lotId().equals(buyerLotId))) {
            throw new IllegalStateException("买入批次标识重复：" + buyerLotId);
        }
        BigDecimal amount = normalized.multiply(BigDecimal.valueOf(quantity));

        Map<String, Long> consumed = new LinkedHashMap<>();
        long needed = quantity;
        for (LotQuantity allocation : reservation.allocations()) {
            if (needed == 0L) {
                break;
            }
            long take = Math.min(allocation.quantity(), needed);
            Long previous = consumed.get(allocation.lotId());
            consumed.put(allocation.lotId(),
                    previous == null ? take : Math.addExact(previous, take));
            needed -= take;
        }
        if (needed != 0L) {
            throw new IllegalStateException("预留分配不足以覆盖成交数量：" + reservationId);
        }

        List<Lot> lots = new ArrayList<>();
        for (Lot lot : state.lots()) {
            Long take = consumed.get(lot.lotId());
            if (take == null) {
                lots.add(lot);
                continue;
            }
            long left = lot.remaining() - take;
            if (left < 0L) {
                throw new IllegalStateException("成交数量超过批次剩余：" + lot.lotId());
            }
            if (left > 0L) {
                lots.add(new Lot(lot.lotId(), lot.accountId(), lot.symbol(), left,
                        lot.eligibleFromDay(), lot.acquiredAtMillis(), lot.acquiredPosition(),
                        lot.costPrice(), lot.classification()));
            }
        }
        lots.add(new Lot(buyerLotId, buyerAccountId, reservation.symbol(), quantity,
                Math.addExact(day, 1L), nowMillis, nextPosition(state),
                normalized.setScale(4, RoundingMode.UNNECESSARY), ShareClass.FLOAT));

        List<SellReservation> reservations = new ArrayList<>();
        for (SellReservation other : state.reservations()) {
            if (!other.reservationId().equals(reservationId)) {
                reservations.add(other);
                continue;
            }
            List<LotQuantity> left = new ArrayList<>();
            for (LotQuantity allocation : other.allocations()) {
                Long take = consumed.getOrDefault(allocation.lotId(), 0L);
                long rest = allocation.quantity() - take;
                if (rest > 0L) {
                    left.add(new LotQuantity(allocation.lotId(), rest));
                }
            }
            if (!left.isEmpty()) {
                reservations.add(new SellReservation(other.reservationId(), other.accountId(),
                        other.symbol(), left));
            }
        }
        return new FillOutcome(true, REASON_FILLED, amount,
                new State(lots, reservations, state.usedReservationIds(), state.reports()));
    }

    /** @param state 证券状态 @param reservationId 预留标识 @return 活动预留；没有返回 null */
    public static SellReservation reservation(State state, String reservationId) {
        Objects.requireNonNull(state, "state");
        for (SellReservation reservation : state.reservations()) {
            if (reservation.reservationId().equals(reservationId)) {
                return reservation;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Map<String, Long> frozenByLot(State state) {
        Map<String, Long> frozen = new LinkedHashMap<>();
        for (SellReservation reservation : state.reservations()) {
            for (LotQuantity allocation : reservation.allocations()) {
                Long previous = frozen.get(allocation.lotId());
                frozen.put(allocation.lotId(), previous == null ? allocation.quantity()
                        : Math.addExact(previous, allocation.quantity()));
            }
        }
        return frozen;
    }

    private static long nextPosition(State state) {
        long max = 0L;
        for (Lot lot : state.lots()) {
            max = Math.max(max, lot.acquiredPosition());
        }
        return Math.addExact(max, 1L);
    }

    private static Outcome reject(String reason, State state) {
        return new Outcome(false, reason, state);
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

    private static BigDecimal cost4(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + "必须精确到 4 位小数：" + value, failure);
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
