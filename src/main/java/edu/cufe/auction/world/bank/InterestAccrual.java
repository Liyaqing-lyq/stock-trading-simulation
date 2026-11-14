package edu.cufe.auction.world.bank;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 利息算术：ACT/365 毫秒时轴、1e6 定标费率与累计分子。
 *
 * <p>分子 {@code N = Σ balance_cents × rate_u × elapsed_ms}，分母
 * {@code D = 1e6 × 365 × 86400000}。中间量用 {@link BigInteger} 避免溢出；
 * 累计美元分只舍入一次（HALF_UP），各时间切片之和与整段计算相等。</p>
 *
 * <p>费率 1e6 表示 100%；本类只做精确换算与换算拒绝，不决定任何计息政策
 * （基数、起止、逾期归属由条款与状态决定）。</p>
 */
public final class InterestAccrual {

    /** 费率定标指数：rate_u = rate × 10^6。 */
    public static final int RATE_EXPONENT = 6;

    /** 定标费率 100%（即 1.0）。 */
    public static final long RATE_ONE = 1_000_000L;

    /** 年基准天数（固定 ACT/365，不随闰年变化）。 */
    public static final int DAY_BASIS_DAYS = 365;

    /** 一天毫秒数（不承诺闰秒）。 */
    public static final long DAY_MS = 86_400_000L;

    /** 分母 D = 1e6 × 365 × 86400000 = 31536000000000000。 */
    public static final BigInteger D = BigInteger.valueOf(RATE_ONE)
            .multiply(BigInteger.valueOf(DAY_BASIS_DAYS))
            .multiply(BigInteger.valueOf(DAY_MS));

    private static final BigDecimal RATE_SCALE = BigDecimal.valueOf(RATE_ONE);

    private InterestAccrual() {
    }

    /**
     * 把十进制费率精确换算为定标费率（最多 6 位小数，0 到 1 闭区间）。
     *
     * @param rate 十进制费率（如 0.05）
     * @return rate_u 整数（如 50000）
     */
    public static long rateU(BigDecimal rate) {
        if (rate == null) {
            throw new BankException(Reasons.RATE_INVALID);
        }
        if (rate.precision() > 40 || Math.abs(rate.scale()) > 40) {
            throw new BankException(Reasons.RATE_OUT_OF_RANGE);
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new BankException(Reasons.RATE_INVALID);
        }
        BigDecimal scaled;
        try {
            scaled = rate.setScale(RATE_EXPONENT, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new BankException(Reasons.RATE_PRECISION);
        }
        return scaled.multiply(RATE_SCALE).longValueExact();
    }

    /**
     * 定标费率还原为规范十进制文本（6 位小数），用于回执。
     *
     * @param rateU 定标费率
     * @return 6 位小数文本
     */
    public static String rateText(long rateU) {
        return BigDecimal.valueOf(rateU, RATE_EXPONENT).setScale(RATE_EXPONENT).toPlainString();
    }

    /**
     * 规范化请求费率文本：仅在可精确表示为 6 位小数时归一（0.05 与 0.050000 同一请求）。
     *
     * @param raw 原始文本
     * @return 规范文本；不可精确表示时原样返回
     */
    public static String canonicalRate(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(raw.trim());
            if (parsed.precision() > 40 || Math.abs(parsed.scale()) > 40) {
                return raw;
            }
            return parsed.setScale(RATE_EXPONENT, RoundingMode.UNNECESSARY).toPlainString();
        } catch (RuntimeException failure) {
            return raw;
        }
    }

    /**
     * 规范化请求整数文本：去掉前导零与正号（1000 与 01000 同一请求）。
     *
     * @param raw 原始文本
     * @return 规范文本；不可解析时原样返回
     */
    public static String canonicalInteger(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.toString(Long.parseLong(raw.trim()));
        } catch (RuntimeException failure) {
            return raw;
        }
    }

    /**
     * 金额精确换算为美分（要求已是两位小数且在表示域内）。
     *
     * @param money 两位小数金额
     * @return 美分
     */
    public static long cents(BigDecimal money) {
        if (money == null) {
            throw new BankException(Reasons.AMOUNT_INVALID);
        }
        try {
            return money.movePointRight(2).longValueExact();
        } catch (ArithmeticException failure) {
            throw new BankException(Reasons.AMOUNT_PRECISION);
        }
    }

    /**
     * 美分换算为金额（两位小数）。
     *
     * @param cents 美分
     * @return 两位小数金额
     */
    public static BigDecimal money(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    /**
     * 追加一段余额时间积分。
     *
     * @param numerator 已有分子
     * @param balanceCents 该段余额（美分，非负）
     * @param rateU 该段定标费率
     * @param elapsedMs 该段毫秒（非负）
     * @return 新的分子
     */
    public static BigInteger add(BigInteger numerator, long balanceCents, long rateU, long elapsedMs) {
        if (balanceCents < 0 || rateU < 0 || elapsedMs < 0) {
            throw new BankException(Reasons.TIME_REVERSED);
        }
        if (balanceCents == 0 || rateU == 0 || elapsedMs == 0) {
            return numerator;
        }
        return numerator.add(BigInteger.valueOf(balanceCents)
                .multiply(BigInteger.valueOf(rateU))
                .multiply(BigInteger.valueOf(elapsedMs)));
    }

    /**
     * 由累计分子求累计应收/应付美分（HALF_UP，只舍入一次）。
     *
     * @param numerator 累计分子
     * @return 累计美分
     */
    public static long targetCents(BigInteger numerator) {
        if (numerator.signum() < 0) {
            throw new BankException(Reasons.TIME_REVERSED);
        }
        BigInteger rounded = numerator.shiftLeft(1).add(D).divide(D.shiftLeft(1));
        try {
            return rounded.longValueExact();
        } catch (ArithmeticException failure) {
            throw new BankException(Reasons.INTEREST_OVERFLOW);
        }
    }
}
