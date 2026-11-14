package edu.cufe.auction.world.bank;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次付款的完整请求：付款人、两个显式资金桶与金额。
 *
 * <p>付款人只给一个档案 key；支出权来自该 key 对应的真实财产主体，而不是请求里再报一个
 * ownerId，也不由收款人签授权。源桶与目标桶必须不同 —— 同一桶自付自收是无效操作。</p>
 *
 * <p>金额保留原始 {@link BigDecimal}：规范化与精度校验发生在 {@link BankModel} 里，
 * 因而非法金额得到稳定原因码而不是构造期异常。</p>
 *
 * @param paymentId 付款标识（幂等与冲突判定用；失败重试需换新标识）
 * @param payerKey  付款人档案 key（银行或其客户）
 * @param source    出款资金桶
 * @param destination 收款资金桶
 * @param amount    付款金额
 */
public record BankPayment(String paymentId, String payerKey, Fund source, Fund destination,
                          BigDecimal amount) {

    /** 校验标识与桶，不做金额业务校验。 */
    public BankPayment {
        paymentId = text(paymentId, "paymentId");
        payerKey = text(payerKey, "payerKey");
        if (source == null || destination == null) {
            throw new IllegalArgumentException("资金桶不能为空");
        }
        if (amount == null) {
            throw new IllegalArgumentException("付款金额不能为空");
        }
    }

    /**
     * 命令 body：键排序后即幂等比较的规范文本。
     *
     * @return 不可变 body
     */
    public Map<String, String> body() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("payer", payerKey);
        body.put("src", source.token());
        body.put("dst", destination.token());
        body.put("amount", amount.toPlainString());
        return Map.copyOf(body);
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (value.contains(Fund.SEPARATOR) || value.startsWith(Fund.LEG_PREFIX)) {
            throw new IllegalArgumentException(field + " 含非法字符：" + value);
        }
        return value;
    }
}
