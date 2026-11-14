package edu.cufe.auction.world.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 业务金额校验：有界、两位小数、正/非负。
 *
 * <p>先看指数/精度元数据再 {@code setScale}，避免极端指数先分配巨量数字。
 * 上限 10^15 是明确声明的表示域，不是经济实证。</p>
 */
public final class Money {
    /** 表示上限：|v| &lt; 10^15。 */
    public static final BigDecimal LIMIT = new BigDecimal("1000000000000000");
    private static final int MAX_META = 40;

    private Money() {
    }

    /**
     * 业务金额：必须为正。
     *
     * @param value 原值
     * @return 两位小数的金额
     */
    public static BigDecimal business(BigDecimal value) {
        return check(value, false);
    }

    /**
     * 估值/押品金额：允许 0，不允许负。
     *
     * @param value 原值
     * @return 两位小数的金额
     */
    public static BigDecimal valuation(BigDecimal value) {
        return check(value, true);
    }

    private static BigDecimal check(BigDecimal value, boolean allowZero) {
        if (value == null) {
            throw new BankException(allowZero ? Reasons.COLLATERAL_INVALID
                    : Reasons.AMOUNT_INVALID);
        }
        boolean outOfRange = value.precision() > MAX_META
                || Math.abs(value.scale()) > MAX_META
                || value.abs().compareTo(LIMIT) >= 0;
        if (outOfRange) {
            throw new BankException(allowZero ? Reasons.COLLATERAL_OUT_OF_RANGE
                    : Reasons.AMOUNT_OUT_OF_RANGE);
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new BankException(allowZero ? Reasons.COLLATERAL_PRECISION
                    : Reasons.AMOUNT_PRECISION);
        }
        if (scaled.signum() < 0) {
            throw new BankException(allowZero ? Reasons.COLLATERAL_NEGATIVE
                    : Reasons.AMOUNT_NOT_POSITIVE);
        }
        if (scaled.signum() == 0 && !allowZero) {
            throw new BankException(Reasons.AMOUNT_NOT_POSITIVE);
        }
        return scaled;
    }

    /**

     * 任意金额。
     * @param value 任意金额
     * @return 两位小数

     */
    public static BigDecimal scale2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 两位小数的零。
     * @return 两位小数的零
     */
    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    /**

     * 金额。
     * @param value 金额
     * @return 规范文本（两位小数）

     */
    public static String text(BigDecimal value) {
        return scale2(value).toPlainString();
    }

    /**
     * 规范化请求中的金额文本：仅在可精确表示为两位小数时归一（10.0 与 10.00 同一请求）。
     *
     * @param raw 原始文本
     * @return 规范文本；不可精确表示时原样返回
     */
    public static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(raw);
            if (parsed.precision() > MAX_META || Math.abs(parsed.scale()) > MAX_META
                    || parsed.abs().compareTo(LIMIT) >= 0) {
                return raw;
            }
            return parsed.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (RuntimeException failure) {
            return raw;
        }
    }
}
