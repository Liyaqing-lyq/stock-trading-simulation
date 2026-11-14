package edu.cufe.auction.world.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.TreeMap;

/**
 * 受信冻结快照：某个真实资金桶上已被合法占用的金额，由 World 侧派生后传入。
 *
 * <p>本类只做只读输入，不重算、不释放、也不占用冻结：普通 PAYMENT 只用它算可用额
 * （余额减本桶冻结），从不挪用被冻结的买单资金。它不是 session 权限或订单权限的实现 ——
 * 谁有权发起支付由真实身份层决定；订单冻结的真实派生必须来自 {@code WorldExchange}。</p>
 *
 * <p>键必须是 {@link Fund#token()} 规范文本，且同一资金桶的多个证券账户由其合计值体现；
 * 现金、不同银行存款、托管受益权之间不互相抵补。负值、非规范键、超出两位小数一律
 * 非法输入。</p>
 *
 * @param frozenByBucket 资金桶规范文本 → 冻结金额（非负、两位小数）
 */
public record CashFreeze(Map<String, BigDecimal> frozenByBucket) {

    /** 校验并深拷贝快照。 */
    public CashFreeze {
        Map<String, BigDecimal> copy = new TreeMap<>();
        Map<String, BigDecimal> source = frozenByBucket == null ? Map.of() : frozenByBucket;
        source.forEach((token, value) -> {
            String canonical;
            try {
                canonical = Fund.parse(token).token();
            } catch (BankException failure) {
                throw new IllegalArgumentException(
                        Reasons.PAYMENT_FREEZE_INVALID + ":bucket:" + token, failure);
            }
            if (value == null) {
                throw new IllegalArgumentException(
                        Reasons.PAYMENT_FREEZE_INVALID + ":amount:" + canonical);
            }
            BigDecimal scaled;
            try {
                scaled = value.setScale(2, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException(
                        Reasons.PAYMENT_FREEZE_INVALID + ":precision:" + canonical, failure);
            }
            if (scaled.signum() < 0) {
                throw new IllegalArgumentException(
                        Reasons.PAYMENT_FREEZE_INVALID + ":negative:" + canonical);
            }
            if (scaled.signum() != 0) {
                copy.put(canonical, scaled);
            }
        });
        frozenByBucket = Map.copyOf(copy);
    }

    /**
     * 显式“无冻结”：只应在调用方确知该运行没有任何合法买单冻结时使用。
     *
     * @return 空快照
     */
    public static CashFreeze none() {
        return new CashFreeze(Map.of());
    }

    /**
     * 某个桶的冻结额。
     *
     * @param bucket 资金桶
     * @return 冻结金额（无记录为零）
     */
    public BigDecimal frozenOf(Fund bucket) {
        BigDecimal frozen = frozenByBucket.get(bucket.token());
        return frozen == null ? Money.zero() : frozen;
    }
}
