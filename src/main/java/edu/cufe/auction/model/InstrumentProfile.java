package edu.cufe.auction.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 标的交易档案：保存昨收价、日涨跌幅限制和最小价格变动单位。
 *
 * <p>涨跌停价以昨收价为基准，并按最小价格变动单位四舍五入。当前默认配置使用
 * A 股常见的 0.01 元价位。</p>
 */
public final class InstrumentProfile {

    private final String symbol;
    private final BigDecimal previousClose;
    private final BigDecimal priceLimitPercent;
    private final BigDecimal tickSize;
    private final BigDecimal lowerLimit;
    private final BigDecimal upperLimit;

    /**
     * @param symbol            标的代码
     * @param previousClose     昨收价，必须大于 0
     * @param priceLimitPercent 日涨跌幅百分比，例如 10 表示正负 10%
     * @param tickSize          最小价格变动单位，例如 0.01
     */
    public InstrumentProfile(String symbol, BigDecimal previousClose,
                             BigDecimal priceLimitPercent, BigDecimal tickSize) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 不能为空");
        }
        this.symbol = symbol;
        this.previousClose = requirePositive(previousClose, "previousClose");
        this.priceLimitPercent = requirePositive(priceLimitPercent, "priceLimitPercent");
        if (priceLimitPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("priceLimitPercent 不能超过 100");
        }
        this.tickSize = requirePositive(tickSize, "tickSize");

        BigDecimal rate = priceLimitPercent.movePointLeft(2);
        this.lowerLimit = roundToTick(previousClose.multiply(BigDecimal.ONE.subtract(rate)));
        this.upperLimit = roundToTick(previousClose.multiply(BigDecimal.ONE.add(rate)));
    }

    private static BigDecimal requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return value;
    }

    private BigDecimal roundToTick(BigDecimal price) {
        return price.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getPreviousClose() {
        return previousClose;
    }

    public BigDecimal getPriceLimitPercent() {
        return priceLimitPercent;
    }

    public BigDecimal getTickSize() {
        return tickSize;
    }

    public BigDecimal getLowerLimit() {
        return lowerLimit;
    }

    public BigDecimal getUpperLimit() {
        return upperLimit;
    }

    /** @return 价格是否落在涨跌停区间内（包含边界） */
    public boolean isWithinDailyLimit(BigDecimal price) {
        return price != null
                && price.compareTo(lowerLimit) >= 0
                && price.compareTo(upperLimit) <= 0;
    }

    /** @return 价格是否符合最小变动价位 */
    public boolean isOnTick(BigDecimal price) {
        return price != null && price.remainder(tickSize).compareTo(BigDecimal.ZERO) == 0;
    }

    @Override
    public String toString() {
        return String.format("%s[昨收=%s, 跌停=%s, 涨停=%s, 价位=%s]", symbol,
                previousClose.toPlainString(), lowerLimit.toPlainString(),
                upperLimit.toPlainString(), tickSize.toPlainString());
    }
}
