package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 开放式产品份额登记、交易日统一确认与下一交易日付款。所有金额和份额由同一份账派生。 */
public final class OpenFundUnits {
    public static final String KEY = "fund-units", VERSION = "weekday-nav-v1", CAPITAL_POLICY = "open-registered-v1";
    public static final String PRODUCT = "fund-a", OPERATING = "ACC-FUND", CLEARING = "FUND-A-SETTLEMENT";
    public static final String FOUNDER = "fund-a-opening-sponsor";
    private static final BigDecimal ZERO_CASH = new BigDecimal("0.00"), ZERO_UNITS = new BigDecimal("0.0000");
    private OpenFundUnits() { }

    /** 新档以零余额登记结算专户与持有原有权益的历史发起人，不复制可用资金或证券。 */
    public static OpeningRegistry registerOpening(List<CashLedger.OpeningAccount> accounts, OpeningRegistry registry) {
        accounts.add(new CashLedger.OpeningAccount(CLEARING, PRODUCT, ZERO_CASH, "开放产品受限结算款，独立于投资账户"));
        var owners = new ArrayList<>(registry.owners());
        owners.add(new OpeningRegistry.PropertyOwner(FOUNDER, OpeningRegistry.OwnerKind.AGGREGATE, null));
        return new OpeningRegistry(registry.parties(), owners, registry.securities(), registry.allocations());
    }

    /** 受理角色的身份由本人会话绑定，金额在申购时为现金、赎回时为份额，撤回时留空。 */
    public record Command(String person, String requestId, String action, BigDecimal amount) { }

    /** 待确认现金申购或冻结已有份额的赎回。 */
    public enum Kind { SUBSCRIBE, REDEEM }

    /** 原始申请及尚未确认的余额。赎回以份额记，申购以现金记。 */
    public record Order(String id, String holder, String account, Kind kind, long submittedAt,
                        BigDecimal requested, BigDecimal remaining, Long cancelledAt) {
        Order withRemaining(BigDecimal rest, Long at) {
            return new Order(id, holder, account, kind, submittedAt, requested, rest, at);
        }
    }

    /** 确认时的实际发行/注销、申购尾差退款或固定应付款。 */
    public record Fill(String orderKey, Kind kind, long confirmedAt, BigDecimal units, BigDecimal amount,
                       BigDecimal refund, long payableAt) { }

    /** 统一估值截止上的真实资产、份额、资金上限及逐笔确认。价格缺失时保留待确认请求。 */
    public record Batch(long cutoff, BigDecimal priorNetAssets, BigDecimal priorUnits, BigDecimal freeCash,
                        List<Fill> fills) {
        public Batch { fills = List.copyOf(fills); }
    }

    /** 独立于证券登记的四位小数基金份额；待付赎回的清偿进度以已过的统一付款点记录。 */
    public record Book(Map<String,BigDecimal> units, Map<String,Order> orders, List<Batch> batches, long paidThrough) {
        public Book {
            units = Collections.unmodifiableMap(new TreeMap<>(units));
            orders = Collections.unmodifiableMap(new TreeMap<>(orders));
            batches = List.copyOf(batches);
        }
        /** 开局用已有产品资本折成每份一元的发起份额。 */
        public static Book opening(BigDecimal capital) {
            if (capital == null || capital.signum() <= 0) throw new IllegalArgumentException("开放产品开局需要完整的正净资产");
            return new Book(Map.of(FOUNDER, capital.setScale(4)), Map.of(), List.of(), -1);
        }
        /** 已发行的总份额，包括等待赎回且尚未注销的部分。 */
        public BigDecimal totalUnits() { return units.values().stream().reduce(ZERO_UNITS, BigDecimal::add); }
        /** 本人持有份额扣除未确认赎回。 */
        public BigDecimal availableUnits(String holder) {
            return units.getOrDefault(holder, ZERO_UNITS).subtract(pending(holder, Kind.REDEEM));
        }
        /** 指定持有人或全产品的待确认申购款/赎回份额。 */
        public BigDecimal pending(String holder, Kind kind) {
            return orders.values().stream().filter(o -> o.kind() == kind && (holder == null || holder.equals(o.holder())))
                    .map(Order::remaining).reduce(kind == Kind.SUBSCRIBE ? ZERO_CASH : ZERO_UNITS, BigDecimal::add);
        }
        /** 截止当前已确认而未支付的赎回款。 */
        public BigDecimal payable(String holder) {
            BigDecimal sum = ZERO_CASH;
            for (Batch batch : batches) for (Fill f : batch.fills()) {
                if (f.kind() == Kind.REDEEM && f.payableAt() > paidThrough
                        && (holder == null || orders.get(f.orderKey()).holder().equals(holder))) sum = sum.add(f.amount());
            }
            return sum;
        }
        /** 应由结算专户实际保有的全部受限现金。 */
        public BigDecimal restrictedCash() { return pending(null, Kind.SUBSCRIBE).add(payable(null)); }
        /** 下一次有实际待处理申请的确认点或已确认赎回付款点。 */
        public long nextEvent() {
            long result = Long.MAX_VALUE;
            long last = batches.isEmpty() ? -1 : batches.get(batches.size()-1).cutoff();
            for (Order o : orders.values()) if (o.remaining().signum() > 0)
                result = Math.min(result, nextCutoff(Math.max(last, o.submittedAt())));
            for (Batch batch : batches) for (Fill f : batch.fills())
                if (f.kind() == Kind.REDEEM && f.payableAt() > paidThrough) result = Math.min(result, f.payableAt());
            return result;
        }
    }

    /** 原子更新内的真实现金腿。 */
    public record Leg(String fromAccount, String toAccount, BigDecimal amount) { }
    /** 份额账和全部现金腿的同一候选。 */
    public record Change(Book book, List<Leg> legs) {
        public Change { legs = List.copyOf(legs); }
    }

    /** 申请立即预收资金或冻结对应份额；原请求重试幂等。 */
    public static Change apply(Book book, String id, String holder, String account, Kind kind, BigDecimal amount, long now) {
        String key = key(holder, id);
        Objects.requireNonNull(kind);
        BigDecimal size = precise(amount, kind == Kind.SUBSCRIBE ? 2 : 4);
        if (size.signum() <= 0) throw new IllegalArgumentException("申请数量必须为正");
        Order existing = book.orders().get(key);
        if (existing != null) {
            if (existing.kind() != kind || !existing.account().equals(account) || !existing.requested().equals(size))
                throw new IllegalArgumentException("同一申请编号不能对应不同内容");
            return new Change(book, List.of());
        }
        if (kind == Kind.REDEEM && size.compareTo(book.availableUnits(holder)) > 0)
            throw new IllegalArgumentException("可赎回份额不足");
        var orders = new TreeMap<>(book.orders());
        orders.put(key, new Order(id, holder, account, kind, now, size, size, null));
        return new Change(new Book(book.units(), orders, book.batches(), book.paidThrough()),
                kind == Kind.SUBSCRIBE ? List.of(new Leg(account, CLEARING, size)) : List.of());
    }

    /** 撤回尚未确认余额，已确认的赎回仍按原确认金额清偿。 */
    public static Change cancel(Book book, String holder, String id, long now) {
        String key = key(holder, id);
        Order order = book.orders().get(key);
        if (order == null) throw new IllegalArgumentException("本人没有该申请");
        if (order.cancelledAt() != null) return new Change(book, List.of());
        if (order.remaining().signum() == 0) throw new IllegalStateException("该申请已全部确认");
        var orders = new TreeMap<>(book.orders());
        orders.put(key, order.withRemaining(order.kind() == Kind.SUBSCRIBE ? ZERO_CASH : ZERO_UNITS, now));
        return new Change(new Book(book.units(), orders, book.batches(), book.paidThrough()),
                order.kind() == Kind.SUBSCRIBE ? List.of(new Leg(CLEARING, order.account(), order.remaining())) : List.of());
    }

    /** 以同一确认前净资产和份额定价。赎回的现金上限只取原有可支付现金，所有申请按相同比例处理。 */
    public static Change confirm(Book book, long at, BigDecimal netAssets, BigDecimal freeCash) {
        if (at != book.nextEvent() || at % MarketSessions.DAY != MarketSessions.CLOSE)
            throw new IllegalStateException("未到该批确认时点");
        var units = new TreeMap<>(book.units());
        var orders = new TreeMap<>(book.orders());
        var legs = new ArrayList<Leg>();
        var fills = new ArrayList<Fill>();
        BigDecimal total = book.totalUnits();
        if (netAssets != null && netAssets.signum() > 0 && total.signum() > 0) {
            BigDecimal redeem = ZERO_UNITS;
            for (Order o : orders.values())
                if (o.kind() == Kind.REDEEM && o.submittedAt() < at) redeem = redeem.add(o.remaining());
            BigDecimal capacity = freeCash.multiply(total).divide(netAssets, 4, RoundingMode.DOWN).min(redeem);
            for (var entry : book.orders().entrySet()) {
                String key = entry.getKey(); Order o = entry.getValue();
                if (o.remaining().signum() == 0 || o.submittedAt() >= at) continue;
                BigDecimal qty, money, refund = ZERO_CASH;
                long payAt = -1;
                if (o.kind() == Kind.SUBSCRIBE) {
                    qty = o.remaining().multiply(total).divide(netAssets, 4, RoundingMode.DOWN);
                    money = qty.multiply(netAssets).divide(total, 2, RoundingMode.HALF_EVEN);
                    refund = o.remaining().subtract(money);
                    leg(legs, CLEARING, OPERATING, money);
                    leg(legs, CLEARING, o.account(), refund);
                    units.merge(o.holder(), qty, BigDecimal::add);
                    orders.put(key, o.withRemaining(ZERO_CASH, null));
                } else {
                    qty = o.remaining().multiply(capacity).divide(redeem, 4, RoundingMode.DOWN);
                    money = qty.multiply(netAssets).divide(total, 2, RoundingMode.DOWN);
                    if (money.signum() == 0) continue;
                    payAt = nextPayment(at);
                    leg(legs, OPERATING, CLEARING, money);
                    units.merge(o.holder(), qty.negate(), BigDecimal::add);
                    orders.put(key, o.withRemaining(o.remaining().subtract(qty), null));
                }
                fills.add(new Fill(key, o.kind(), at, qty, money, refund, payAt));
            }
        }
        var batches = new ArrayList<>(book.batches());
        batches.add(new Batch(at, netAssets, total, freeCash, fills));
        return new Change(new Book(units, orders, batches, book.paidThrough()), legs);
    }

    /** 只清偿该付款点已确定的金额，不重算净值或重复注销份额。 */
    public static Change pay(Book book, long at) {
        if (at != book.nextEvent() || at % MarketSessions.DAY != MarketSessions.OPEN)
            throw new IllegalStateException("未到该批付款时点");
        var legs = new ArrayList<Leg>();
        for (Batch batch : book.batches()) for (Fill f : batch.fills())
            if (f.kind() == Kind.REDEEM && f.payableAt() == at)
                leg(legs, CLEARING, book.orders().get(f.orderKey()).account(), f.amount());
        return new Change(new Book(book.units(), book.orders(), book.batches(), at), legs);
    }

    /** 本人逐笔确认、撤回与实付记录。只取本人订单的确认记录，待付赎回始终使用原确认金额。 */
    static List<Map<String,Object>> orderHistory(Book book, String person) {
        record Posting(Batch batch, Fill fill) { }
        var history = new TreeMap<String,List<Posting>>();
        for (Batch batch : book.batches()) for (Fill f : batch.fills())
            if (person.equals(book.orders().get(f.orderKey()).holder()))
                history.computeIfAbsent(f.orderKey(), ignored -> new ArrayList<>()).add(new Posting(batch, f));
        var orders = new ArrayList<Map<String,Object>>();
        for (var entry : book.orders().entrySet()) {
            Order o = entry.getValue();
            if (!person.equals(o.holder())) continue;
            var row = new java.util.LinkedHashMap<String,Object>();
            row.put("id", o.id()); row.put("kind", o.kind().name()); row.put("submittedAt", o.submittedAt());
            row.put("requested", o.requested().toPlainString()); row.put("remaining", o.remaining().toPlainString()); row.put("cancelledAt", o.cancelledAt());
            BigDecimal qty = ZERO_UNITS, money = ZERO_CASH, paid = ZERO_CASH, refund = ZERO_CASH;
            var confirmations = new ArrayList<Map<String,Object>>();
            for (var posting : history.getOrDefault(entry.getKey(), List.of())) {
                var f = posting.fill(); var batch = posting.batch();
                qty = qty.add(f.units()); money = money.add(f.amount()); refund = refund.add(f.refund());
                boolean settled = f.kind() == Kind.REDEEM && f.payableAt() <= book.paidThrough();
                if (settled) paid = paid.add(f.amount());
                var detail = new java.util.LinkedHashMap<String,Object>();
                detail.put("cutoff", batch.cutoff());
                detail.put("unitNav", batch.priorNetAssets().divide(batch.priorUnits(), 8, RoundingMode.HALF_EVEN).toPlainString());
                detail.put("units", f.units().toPlainString()); detail.put("amount", f.amount().toPlainString());
                detail.put("refund", f.refund().toPlainString()); detail.put("payableAt", f.payableAt() < 0 ? null : f.payableAt());
                detail.put("paidAt", settled ? f.payableAt() : null);
                confirmations.add(Collections.unmodifiableMap(detail));
            }
            BigDecimal booked = o.kind() == Kind.SUBSCRIBE ? money.add(refund) : qty;
            row.put("confirmedUnits", qty.toPlainString()); row.put("confirmedAmount", money.toPlainString());
            row.put("cancelledRemainder", o.requested().subtract(booked).subtract(o.remaining()).toPlainString());
            row.put("roundingRefund", refund.toPlainString()); row.put("paidAmount", paid.toPlainString());
            row.put("receivable", (o.kind() == Kind.REDEEM ? money.subtract(paid) : ZERO_CASH).toPlainString());
            row.put("confirmations", List.copyOf(confirmations));
            orders.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(orders);
    }

    static long nextCutoff(long after) {
        long day = after / MarketSessions.DAY;
        if (after % MarketSessions.DAY >= MarketSessions.CLOSE) day++;
        return weekday(day) * MarketSessions.DAY + MarketSessions.CLOSE;
    }

    static long nextPayment(long cutoff) {
        return weekday(cutoff / MarketSessions.DAY + 1) * MarketSessions.DAY + MarketSessions.OPEN;
    }

    private static long weekday(long day) { return day % 7 >= 5 ? day + 7 - day % 7 : day; }
    private static String key(String holder, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("申请编号须为1至64位字母、数字、横线或下划线");
        return holder + "/" + id;
    }
    /** 网页和模型共用的十进制申请格式；整数最多20位，申购精确到分、赎回精确到万分之一份。 */
    public static BigDecimal parseAmount(Kind kind, String text) {
        if (text == null || !text.matches("[0-9]{1,20}([.][0-9]{1,4})?"))
            throw new IllegalArgumentException("金额或份额须为普通十进制数，整数部分最多20位");
        var amount = precise(new BigDecimal(text), kind == Kind.SUBSCRIBE ? 2 : 4);
        if (amount.signum() <= 0) throw new IllegalArgumentException("金额或份额必须为正");
        return amount;
    }

    private static BigDecimal precise(BigDecimal value, int scale) {
        if (value == null || value.precision() > 24 || (long)value.precision() - value.scale() > 20 || value.scale() > 28) throw new IllegalArgumentException("申请数值不合法");
        try { return value.setScale(scale, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("申请小数位数超过产品精度", invalid); }
    }
    private static void leg(List<Leg> legs, String from, String to, BigDecimal money) {
        if (money.signum() > 0) legs.add(new Leg(from, to, money));
    }
}
