package edu.cufe.auction.account;

import edu.cufe.auction.model.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个交易账户：现金、冻结资金、持仓与绩效。
 *
 * <p><b>线程安全</b>：所有状态访问都在内部 {@code lock} 上同步。多线程模型约定：
 * 引擎线程持有撮合锁后再调用账户方法，账户方法内部不再回调引擎，
 * 因此锁顺序固定为“引擎锁 → 账户锁”，不存在反向加锁，不会死锁。</p>
 *
 * <p><b>冻结机制</b>：下单受理时冻结资金（买入）或持仓（卖出），成交/撤单时释放。
 * 这样同一 agent 并发下多张单也不会重复使用同一笔钱，避免出现负现金。</p>
 */
public final class Account {

    private static final int MONEY_SCALE = 2;

    private final String agentId;
    private final String displayName;
    private final BigDecimal initialBalance;
    private final Object lock = new Object();
    private final Map<String, Position> positions = new HashMap<>();
    private final Map<Long, Reservation> reservations = new HashMap<>();
    private final PerformanceTracker performance = new PerformanceTracker();

    private BigDecimal cash;
    private BigDecimal frozenCash;
    private BigDecimal seededCost = BigDecimal.ZERO;

    /** 单张订单的冻结明细。 */
    private static final class Reservation {
        private final Side side;
        private final String symbol;
        private final BigDecimal perShare;
        private long remainingQuantity;

        private Reservation(Side side, String symbol, BigDecimal perShare, long quantity) {
            this.side = side;
            this.symbol = symbol;
            this.perShare = perShare;
            this.remainingQuantity = quantity;
        }
    }

    /**
     * 构造账户。
     *
     * @param agentId        所属 agent 标识
     * @param displayName    展示名
     * @param initialBalance 初始资金，必须非负
     */
    public Account(String agentId, String displayName, BigDecimal initialBalance) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        if (initialBalance == null || initialBalance.signum() < 0) {
            throw new IllegalArgumentException("initialBalance 必须非负");
        }
        this.agentId = agentId;
        this.displayName = displayName == null ? agentId : displayName;
        this.initialBalance = initialBalance.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        this.cash = this.initialBalance;
        this.frozenCash = BigDecimal.ZERO;
    }

    /** @return agent 标识 */
    public String getAgentId() {
        return agentId;
    }

    /** @return 展示名 */
    public String getDisplayName() {
        return displayName;
    }

    /** @return 初始资金 */
    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    /** @return 可用现金（不含冻结） */
    public BigDecimal getAvailableCash() {
        synchronized (lock) {
            return cash;
        }
    }

    /** @return 冻结现金（挂单占用） */
    public BigDecimal getFrozenCash() {
        synchronized (lock) {
            return frozenCash;
        }
    }

    /** @return 现金合计（可用 + 冻结） */
    public BigDecimal getTotalCash() {
        synchronized (lock) {
            return cash.add(frozenCash);
        }
    }

    /** @return 绩效率统计对象（只读使用；写入由本类驱动） */
    public PerformanceTracker getPerformance() {
        return performance;
    }

    /**
     * 查询某标的总持仓。
     *
     * @param symbol 标的代码
     * @return 持仓数量，无持仓返回 0
     */
    public long getQuantity(String symbol) {
        synchronized (lock) {
            Position p = positions.get(symbol);
            return p == null ? 0L : p.getQuantity();
        }
    }

    /**
     * 查询某标的可卖数量。
     *
     * @param symbol 标的代码
     * @return 可卖数量（总持仓 − 冻结）
     */
    public long getAvailableQuantity(String symbol) {
        synchronized (lock) {
            Position p = positions.get(symbol);
            return p == null ? 0L : p.getAvailableQuantity();
        }
    }

    /**
     * 查询某标的持仓快照。
     *
     * @param symbol 标的代码
     * @return 持仓快照；无持仓返回 null
     */
    public Position getPosition(String symbol) {
        synchronized (lock) {
            Position p = positions.get(symbol);
            return p == null ? null : p.copy();
        }
    }

    /** @return 全部持仓快照（key = 标的代码），不可变 */
    public Map<String, Position> getPositions() {
        synchronized (lock) {
            Map<String, Position> copy = new LinkedHashMap<>();
            positions.forEach((k, v) -> {
                if (v.getQuantity() != 0) {
                    copy.put(k, v.copy());
                }
            });
            return Collections.unmodifiableMap(copy);
        }
    }

    /**
     * 下单受理时冻结资金或持仓。
     *
     * @param orderId  订单 id
     * @param symbol   标的代码
     * @param side     方向
     * @param perShare 买入时每股冻结金额（限价单用限价，市价单用当时卖一价）
     * @param quantity 数量
     * @return 额度充足并完成冻结返回 true；否则 false（不做任何改动）
     */
    public boolean reserve(long orderId, String symbol, Side side, BigDecimal perShare, long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("冻结数量必须为正");
        }
        synchronized (lock) {
            if (reservations.containsKey(orderId)) {
                throw new IllegalStateException("订单 " + orderId + " 已冻结过");
            }
            if (side == Side.BUY) {
                if (perShare == null || perShare.signum() <= 0) {
                    throw new IllegalArgumentException("买入冻结必须提供正的每股金额");
                }
                BigDecimal need = perShare.multiply(BigDecimal.valueOf(quantity))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                if (cash.compareTo(need) < 0) {
                    return false;
                }
                cash = cash.subtract(need);
                frozenCash = frozenCash.add(need);
                reservations.put(orderId, new Reservation(side, symbol, perShare, quantity));
                return true;
            }
            Position p = positions.get(symbol);
            if (p == null || p.getAvailableQuantity() < quantity) {
                return false;
            }
            p.freeze(quantity);
            reservations.put(orderId, new Reservation(side, symbol, BigDecimal.ZERO, quantity));
            return true;
        }
    }

    /**
     * 查询某订单尚未释放的冻结金额（买入）。
     *
     * @param orderId 订单 id
     * @return 剩余冻结金额；无冻结记录返回 0
     */
    public BigDecimal getReservedAmount(long orderId) {
        synchronized (lock) {
            Reservation r = reservations.get(orderId);
            if (r == null || r.side != Side.BUY) {
                return BigDecimal.ZERO;
            }
            return r.perShare.multiply(BigDecimal.valueOf(r.remainingQuantity))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 撤单/收市时释放该订单剩余的全部冻结。
     *
     * @param orderId 订单 id
     */
    public void release(long orderId) {
        synchronized (lock) {
            Reservation r = reservations.remove(orderId);
            if (r == null) {
                return;
            }
            if (r.side == Side.BUY) {
                BigDecimal refund = r.perShare.multiply(BigDecimal.valueOf(r.remainingQuantity))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                frozenCash = frozenCash.subtract(refund);
                cash = cash.add(refund);
            } else {
                Position p = positions.get(r.symbol);
                if (p != null && r.remainingQuantity > 0) {
                    p.unfreeze(r.remainingQuantity);
                }
            }
        }
    }

    /**
     * 买入成交结算：释放对应冻结、扣减实际成本、增加持仓。
     *
     * @param orderId  订单 id
     * @param symbol   标的代码
     * @param price    成交价
     * @param quantity 成交量
     */
    public void settleBuy(long orderId, String symbol, BigDecimal price, long quantity) {
        synchronized (lock) {
            Reservation r = reservations.get(orderId);
            BigDecimal released = BigDecimal.ZERO;
            if (r != null && r.remainingQuantity > 0) {
                long frozenQty = Math.min(r.remainingQuantity, quantity);
                released = r.perShare.multiply(BigDecimal.valueOf(frozenQty))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                r.remainingQuantity -= frozenQty;
                if (r.remainingQuantity == 0) {
                    reservations.remove(orderId);
                }
                frozenCash = frozenCash.subtract(released);
            }
            BigDecimal cost = price.multiply(BigDecimal.valueOf(quantity))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            // 冻结部分若高于实际成本（价格改善），差额退回可用现金
            cash = cash.add(released).subtract(cost);
            Position p = positions.computeIfAbsent(symbol, Position::new);
            p.addShares(quantity, cost);
        }
    }

    /**
     * 卖出成交结算：释放冻结持仓、增加现金、累计已实现盈亏。
     *
     * @param orderId  订单 id
     * @param symbol   标的代码
     * @param price    成交价
     * @param quantity 成交量
     * @return 本次已实现盈亏
     */
    public BigDecimal settleSell(long orderId, String symbol, BigDecimal price, long quantity) {
        synchronized (lock) {
            Reservation r = reservations.get(orderId);
            if (r != null && r.remainingQuantity > 0) {
                long frozenQty = Math.min(r.remainingQuantity, quantity);
                r.remainingQuantity -= frozenQty;
                if (r.remainingQuantity == 0) {
                    reservations.remove(orderId);
                }
                Position pos = positions.get(r.symbol);
                if (pos != null && frozenQty > 0) {
                    pos.unfreeze(frozenQty);
                }
            }
            Position p = positions.get(symbol);
            if (p == null) {
                throw new IllegalStateException("账户 " + agentId + " 无 " + symbol
                        + " 持仓，无法卖出（引擎校验失效）");
            }
            BigDecimal pnl = p.removeShares(quantity, price);
            cash = cash.add(price.multiply(BigDecimal.valueOf(quantity))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            performance.recordClosedTrade(pnl);
            return pnl;
        }
    }

    /**
     * 注入初始持仓（仅在模拟初始化阶段调用一次）。
     *
     * <p><b>为什么需要它</b>：规格只定义了“初始资金”，但所有人一开始都只有现金、
     * 没有任何股票，卖出校验必然失败，市场永远无法产生第一笔成交。
     * 因此系统必须支持给 agent（尤其是做市/噪音 agent）发放初始持仓。</p>
     *
     * @param symbol    标的代码
     * @param quantity  数量，必须为正
     * @param costPrice 建仓成本价（通常取配置里的参考价）
     */
    public void seedPosition(String symbol, long quantity, BigDecimal costPrice) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("初始持仓数量必须为正");
        }
        if (costPrice == null || costPrice.signum() <= 0) {
            throw new IllegalArgumentException("建仓成本价必须为正");
        }
        synchronized (lock) {
            if (!positions.isEmpty() && positions.containsKey(symbol) && positions.get(symbol).getQuantity() > 0) {
                throw new IllegalStateException("标 " + symbol + " 已有持仓，禁止重复注入初始持仓");
            }
            Position p = positions.computeIfAbsent(symbol, Position::new);
            p.addShares(quantity, costPrice.multiply(BigDecimal.valueOf(quantity)));
            seededCost = seededCost.add(costPrice.multiply(BigDecimal.valueOf(quantity)))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 初始权益 = 初始资金 + 注入的初始持仓成本。
     *
     * <p><b>收益率的正确基准</b>：如果 agent 拿到过初始持仓，用“初始资金”做分母会把
     * 这部分股票市值误算成收益（例如做市 agent 拿到 800 股 @175.5，收益率会凭空 +175%）。
     * 因此收益率一律以“初始权益”为基准。</p>
     *
     * @return 初始权益
     */
    public BigDecimal getInitialEquity() {
        synchronized (lock) {
            return initialBalance.add(seededCost);
        }
    }

    /**
     * 判断可用资金是否足够支付一笔买入。
     *
     * @param price    价格
     * @param quantity 数量
     * @return 足够返回 true
     */
    public boolean canAfford(BigDecimal price, long quantity) {
        synchronized (lock) {
            BigDecimal need = price.multiply(BigDecimal.valueOf(quantity))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            return cash.compareTo(need) >= 0;
        }
    }

    /**
     * 计算总权益 = 现金（含冻结） + Σ(持仓 × 最新价)。
     *
     * @param lastPrices 各标的最新价；缺失时用持仓成本价兜底
     * @return 总权益
     */
    public BigDecimal equity(Map<String, BigDecimal> lastPrices) {
        synchronized (lock) {
            BigDecimal total = cash.add(frozenCash);
            for (Map.Entry<String, Position> e : positions.entrySet()) {
                Position p = e.getValue();
                if (p.getQuantity() == 0) {
                    continue;
                }
                BigDecimal price = lastPrices.get(e.getKey());
                if (price == null) {
                    price = p.getAverageCost();
                }
                total = total.add(price.multiply(BigDecimal.valueOf(p.getQuantity())));
            }
            return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * 收益率 = (总权益 − 初始权益) / 初始权益。
     *
     * @param lastPrices 各标的最新价
     * @return 收益率（0.05 表示 5%）；初始权益为 0 时返回 0
     */
    public BigDecimal returnRate(Map<String, BigDecimal> lastPrices) {
        BigDecimal base = getInitialEquity();
        if (base.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return equity(lastPrices).subtract(base)
                .divide(base, 6, RoundingMode.HALF_UP);
    }

    /**
     * 记录当前权益，用于最大回撤统计。
     *
     * @param lastPrices 各标的最新价
     */
    public void markEquity(Map<String, BigDecimal> lastPrices) {
        performance.markEquity(equity(lastPrices));
    }

    /**
     * 计算某标的浮动盈亏。
     *
     * @param symbol    标的代码
     * @param lastPrice 最新价
     * @return 浮动盈亏；无持仓返回 0
     */
    public BigDecimal unrealizedPnl(String symbol, BigDecimal lastPrice) {
        synchronized (lock) {
            Position p = positions.get(symbol);
            return p == null ? BigDecimal.ZERO : p.unrealizedPnl(lastPrice);
        }
    }

    @Override
    public String toString() {
        return String.format("Account[%s 现金 %s（冻结 %s）持仓 %d 项]",
                agentId, cash.toPlainString(), frozenCash.toPlainString(), positions.size());
    }
}
