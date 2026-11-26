package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonFormat;
import edu.cufe.auction.model.InstrumentProfile;
import java.math.BigDecimal;
import java.util.List;

/**
 * EXP 档公开日价格带，首日或从未成交采用发行登记估值。
 * 有历史成交时取日初之前最后一笔实际成交，无成交日和周末沿用最近实收。
 * @param basis 基准价格
 * @param source 开局参考、前次收盘、已确认除息参考，或缺少有效基准
 * @param basisAtMillis 基准时点
 * @param lower 可申报最低价（含）
 * @param upper 可申报最高价（含）
 */
public record DailyPriceBand(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal basis,
                             String source, long basisAtMillis,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lower,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal upper) {
    static final String VERSION = "previous-close-10pct-exp-v1";
    static final String OUTSIDE = "PRICE_OUT_OF_LIMIT";
    private static final BigDecimal TICK = new BigDecimal("0.01");

    static DailyPriceBand at(OpeningRegistry registry, WorldExchange.State exchange, long position,
                             long now, String symbol, DividendModel.State dividends) {
        // 交易日第零日零点亦未开市。ValuationModel 用成交号裁决同刻的最后一笔。
        long cutoff = Math.max(0, now - now % SecuritiesLedger.DAY_MILLIS - 1);
        var price = ValuationModel.priceOf(ValuationModel.prices(position, cutoff, exchange, registry, List.of(symbol)), symbol);
        if (price.source() == ValuationModel.PriceSource.MISSING) return null;
        if (dividends != null) price = ValuationModel.withExDividend(price, symbol, now, dividends);
        if (price.price() == null || price.price().signum() <= 0)
            return new DailyPriceBand(price.price(), "MISSING", price.asOfMillis(), null, null);
        var limits = new InstrumentProfile(symbol, price.price(), BigDecimal.TEN, TICK);
        String source = price.source() == ValuationModel.PriceSource.TRADE ? "PREVIOUS_TRADED_CLOSE" : price.source().name();
        return new DailyPriceBand(price.price(), source, price.asOfMillis(), limits.getLowerLimit(), limits.getUpperLimit());
    }

    boolean contains(BigDecimal price) {
        return lower != null && price.compareTo(lower) >= 0 && price.compareTo(upper) <= 0;
    }
}
