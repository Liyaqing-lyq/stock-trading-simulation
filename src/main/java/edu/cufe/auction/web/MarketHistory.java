package edu.cufe.auction.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import edu.cufe.auction.world.WorldExchange;

/**
 * 真实成交 OHLC 聚合（只读、纯函数）。
 *
 * <p>输入是当前已确认 {@link WorldExchange.Trade} 链与同一快照的截止时点；输出只包含真正有成交的桶。
 * 桶边界从模拟时点 0 起算，不是现实交易日历。金额使用 {@link BigDecimal} 精确累加，数量使用
 * {@code long} 并检测溢出；不补价、不枚举空桶、不把参考估值当成交。</p>
 *
 * <p>同一桶内按 {@code timeMillis}、再按 {@code tradeId} 升序确定 open/close；同刻成交号顺序即成交链顺序。
 * 不修改调用方传入的列表。</p>
 */
public final class MarketHistory {

    /** 单次响应最多保留的 bar 数。 */
    public static final int MAX_BARS = 500;

    private MarketHistory() {
    }

    /** 聚合周期；毫秒值从模拟时点 0 起算。 */
    public enum Period {
        /** 一分钟。 */
        MINUTE("minute", 60_000L),
        /** 一个模拟日（86400000ms，不是现实交易日历）。 */
        DAY("day", 86_400_000L),
        /** 一个模拟周（604800000ms，不是现实交易周历）。 */
        WEEK("week", 604_800_000L);

        private final String id;
        private final long millis;

        Period(String id, long millis) {
            this.id = id;
            this.millis = millis;
        }

        /** @return 协议标识 */
        public String id() {
            return id;
        }

        /** @return 周期毫秒 */
        public long millis() {
            return millis;
        }

        /**
         * 解析协议标识。
         *
         * @param text 协议标识
         * @return 周期
         * @throws IllegalArgumentException 不是 minute/day/week
         */
        public static Period fromId(String text) {
            for (Period period : values()) {
                if (period.id.equals(text)) {
                    return period;
                }
            }
            throw new IllegalArgumentException("周期必须是 minute/day/week");
        }
    }

    /**
     * 一根有成交的 OHLC 柱。
     *
     * @param startMillis       桶起点（含）
     * @param endMillis         桶终点（不含）；溢出 long 时为 null
     * @param complete          是否在截止前已结束
     * @param firstTradeId      桶内首笔成交号（按时间、成交号排序）
     * @param lastTradeId       桶内末笔成交号
     * @param count             成交笔数
     * @param open              首笔成交价
     * @param high              最高成交价
     * @param low               最低成交价
     * @param close             末笔成交价
     * @param volume            数量合计；溢出时为 null
     * @param volumeStatus      OK / OVERFLOW
     * @param amount            金额合计（价格×数量，精确到分累加）
     */
    public record Bar(long startMillis, Long endMillis, boolean complete, long firstTradeId,
                      long lastTradeId, long count, BigDecimal open, BigDecimal high,
                      BigDecimal low, BigDecimal close, Long volume, String volumeStatus,
                      BigDecimal amount) {

        /** 冻结并校验柱。 */
        public Bar {
            if (startMillis < 0) {
                throw new IllegalArgumentException("桶起点不能为负");
            }
            if (endMillis != null && endMillis <= startMillis) {
                throw new IllegalArgumentException("桶终点必须晚于起点");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("桶内成交数必须为正");
            }
            Objects.requireNonNull(open, "open");
            Objects.requireNonNull(high, "high");
            Objects.requireNonNull(low, "low");
            Objects.requireNonNull(close, "close");
            Objects.requireNonNull(volumeStatus, "volumeStatus");
            Objects.requireNonNull(amount, "amount");
            if (!"OK".equals(volumeStatus) && !"OVERFLOW".equals(volumeStatus)) {
                throw new IllegalArgumentException("成交量状态非法");
            }
            if ("OVERFLOW".equals(volumeStatus) != (volume == null)) {
                throw new IllegalArgumentException("成交量与状态不一致");
            }
        }
    }

    /**
     * 相邻保留柱之间的空桶区间。
     *
     * @param fromMillis   前一根柱的 endExclusive
     * @param toMillis     后一根柱的 start
     * @param missingBars  中间缺失的桶数
     */
    public record Gap(long fromMillis, long toMillis, long missingBars) {
        /** 校验区间。 */
        public Gap {
            if (fromMillis < 0 || toMillis <= fromMillis || missingBars <= 0) {
                throw new IllegalArgumentException("空档区间非法");
            }
        }
    }

    /**
     * 聚合结果。
     *
     * @param symbol        证券
     * @param period        周期
     * @param cutTimeMillis 截止时点
     * @param cutPosition   截止确认位置
     * @param bars          最近最多 500 根有成交柱（升序）
     * @param gaps          相邻保留柱之间的空档
     * @param truncated     是否因 500 上限裁剪
     * @param totalBars     裁剪前有成交桶总数
     */
    public record Result(String symbol, Period period, long cutTimeMillis, long cutPosition,
                         List<Bar> bars, List<Gap> gaps, boolean truncated, long totalBars) {

        /** 冻结集合。 */
        public Result {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(period, "period");
            bars = List.copyOf(bars);
            gaps = List.copyOf(gaps);
            if (totalBars < bars.size()) {
                throw new IllegalArgumentException("桶总数不能小于返回数");
            }
        }
    }

    /**
     * 按证券、周期、截止时点聚合真实成交。
     *
     * @param trades        真实成交链（不要求有序，不会被修改）
     * @param symbol        证券代码
     * @param period        聚合周期
     * @param cutTimeMillis 截止模拟毫秒（含）；晚于它的成交不进入结果
     * @param cutPosition   截止确认位置（仅回填结果）
     * @return 聚合结果
     */
    public static Result aggregate(List<WorldExchange.Trade> trades, String symbol, Period period,
                                   long cutTimeMillis, long cutPosition) {
        Objects.requireNonNull(trades, "trades");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(period, "period");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("证券代码不能为空");
        }
        if (cutTimeMillis < 0 || cutPosition < 0) {
            throw new IllegalArgumentException("截止时点或位置不能为负");
        }
        List<WorldExchange.Trade> selected = new ArrayList<>();
        for (WorldExchange.Trade trade : trades) {
            Objects.requireNonNull(trade, "trade");
            if (symbol.equals(trade.symbol()) && trade.timeMillis() <= cutTimeMillis) {
                selected.add(trade);
            }
        }
        selected.sort(Comparator.comparingLong(WorldExchange.Trade::timeMillis)
                .thenComparingLong(WorldExchange.Trade::tradeId));

        List<Bar> all = new ArrayList<>();
        Bucket bucket = null;
        for (WorldExchange.Trade trade : selected) {
            long start = Math.floorDiv(trade.timeMillis(), period.millis()) * period.millis();
            if (bucket == null || bucket.start != start) {
                if (bucket != null) {
                    all.add(bucket.toBar(period, cutTimeMillis));
                }
                bucket = new Bucket(start, trade);
            } else {
                bucket.add(trade);
            }
        }
        if (bucket != null) {
            all.add(bucket.toBar(period, cutTimeMillis));
        }

        long total = all.size();
        int from = Math.max(0, all.size() - MAX_BARS);
        List<Bar> bars = List.copyOf(all.subList(from, all.size()));
        List<Gap> gaps = new ArrayList<>();
        for (int index = 1; index < bars.size(); index++) {
            Bar previous = bars.get(index - 1);
            Bar next = bars.get(index);
            if (previous.endMillis() == null) {
                continue;
            }
            long missing = (next.startMillis() - previous.endMillis()) / period.millis();
            if (missing > 0) {
                gaps.add(new Gap(previous.endMillis(), next.startMillis(), missing));
            }
        }
        return new Result(symbol, period, cutTimeMillis, cutPosition, bars, gaps,
                total > MAX_BARS, total);
    }

    /** 聚合中的可变桶；只在方法内使用。 */
    private static final class Bucket {
        private final long start;
        private final long firstTradeId;
        private final BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private long lastTradeId;
        private long count;
        private Long volume;
        private String volumeStatus = "OK";
        private BigDecimal amount = BigDecimal.ZERO.setScale(2);

        Bucket(long start, WorldExchange.Trade first) {
            this.start = start;
            this.firstTradeId = first.tradeId();
            this.lastTradeId = first.tradeId();
            this.open = first.price();
            this.high = first.price();
            this.low = first.price();
            this.close = first.price();
            this.count = 1L;
            this.volume = first.quantity();
            this.amount = first.amount();
        }

        void add(WorldExchange.Trade trade) {
            close = trade.price();
            lastTradeId = trade.tradeId();
            count += 1L;
            if (trade.price().compareTo(high) > 0) {
                high = trade.price();
            }
            if (trade.price().compareTo(low) < 0) {
                low = trade.price();
            }
            if (volume != null) {
                try {
                    volume = Math.addExact(volume, trade.quantity());
                } catch (ArithmeticException overflow) {
                    volume = null;
                    volumeStatus = "OVERFLOW";
                }
            }
            amount = amount.add(trade.amount());
        }

        Bar toBar(Period period, long cutTimeMillis) {
            Long end = start > Long.MAX_VALUE - period.millis()
                    ? null : start + period.millis();
            boolean complete = end != null && end <= cutTimeMillis;
            return new Bar(start, end, complete, firstTradeId, lastTradeId, count, open,
                    high, low, close, volume, volumeStatus, amount);
        }
    }
}
