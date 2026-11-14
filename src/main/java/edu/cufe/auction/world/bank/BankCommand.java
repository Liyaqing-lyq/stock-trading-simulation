package edu.cufe.auction.world.bank;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 一条银行命令：id 只是引用，语义由 kind 与完整 body 共同决定。
 *
 * <p>body 必须包含全部参与者；金额文本在可精确表示时归一为两位小数，
 * 未知键视为协议错误。</p>
     * @param commandId commandId 参数
     * @param kind kind 参数
     * @param body body 参数
     * @param actorOwnerId actorOwnerId 参数
     */
public record BankCommand(String commandId, Kind kind, Map<String, String> body,
                          String actorOwnerId) {

    /** 支持的银行命令种类。 */
    public enum Kind {
        /** 客户存现。 */ DEPOSIT,
        /** 客户取现。 */ WITHDRAW,
        /** 行内或跨行转账。 */ TRANSFER,
        /** 新放款。 */ LOAN_ORIGINATE,
        /** 还本。 */ LOAN_REPAY,
        /** 计提减值。 */ IMPAIR,
        /** 核销。 */ WRITE_OFF,
        /** 第三人代付。 */ THIRD_PARTY_PAY,
        /** 豁免。 */ WAIVE,
        /** 增加表外承诺。 */ COMMITMENT_INCREASE,
        /** 救助提款。 */ RELIEF_DRAW,
        /** 救助偿付。 */ FACILITY_PAY,
        /** 救助豁免。 */ FACILITY_WAIVE,
        /** 提供者计提。 */ FACILITY_PROVISION,
        /** 违约状态转换。 */ FACILITY_DEFAULT,
        /** 一次性期初迁移。 */ MIGRATE,
        /** 贷款利息计提（条款已冻结）。 */ ACCRUE_LOAN,
        /** 存款利息计提（条款已冻结）。 */ ACCRUE_DEPOSIT,
        /** 收贷款利息（从借款人存款）。 */ PAY_LOAN_INTEREST,
        /** 付存款利息（入客户存款）。 */ PAY_DEPOSIT_INTEREST,
        /** 到期判定（本金与利息）。 */ MATURE_LOAN,
        /** 开立明细承诺。 */ COMMITMENT_OPEN,
        /** 承诺提款。 */ COMMITMENT_DRAW,
        /** 提供者批准救助设施额度（B3）。 */ FACILITY_AUTHORIZE,
        /** 银行按授权提款（B3）。 */ FACILITY_DRAW,
        /** 设施利息累计（B3，两侧同一函数）。 */ FACILITY_ACCRUE,
        /** 银行支付设施利息（B3）。 */ FACILITY_PAY_INTEREST,
        /** 提供者计提减值（B3）。 */ FACILITY_IMPAIR,
        /** 提供者核销表内（B3）。 */ FACILITY_WRITE_OFF,
        /** 核销后备查回收（B3，真实付款方）。 */ FACILITY_RECOVER,
        /** 有限买方受让贷款债权（B4）。 */ LOAN_ASSIGN,
        /** 受让债权回收（B4）。 */ ASSIGNED_RECOVER,
        /** 受让债权豁免（B4）。 */ ASSIGNED_WAIVE,
        /** 开启独立处置流程（B4）。 */ RESOLUTION_OPEN,
        /** 处置押品（B4，真实买方付款）。 */ RESOLUTION_DISPOSE,
        /** 按债权阶梯分配可分配现金（B4）。 */ RESOLUTION_DISTRIBUTE,
        /** 有债权人同意的债务解除（B4）。 */ RESOLUTION_DISCHARGE,
        /** 买方登记冻结受让许可（B4/B3 真实对手同意）。 */ PURCHASE_ACCEPT,
        /** 债权人登记解除许可（B4）。 */ DISCHARGE_CONSENT,
        /** 受让债权计提利息（B4）。 */ ASSIGNED_ACCRUE,
        /** 受让债权收息（B4，真实付款方）。 */ ASSIGNED_PAY_INTEREST,
        /** 显式资金桶之间的原子付款（B6 支付接缝）。 */ PAYMENT
    }

    private static final Map<Kind, List<String>> ALLOWED = Map.ofEntries(
            Map.entry(Kind.DEPOSIT, List.of("owner", "bank", "amount", "offer")),
            Map.entry(Kind.WITHDRAW, List.of("owner", "bank", "amount")),
            Map.entry(Kind.TRANSFER, List.of("src", "srcBank", "dst", "dstBank", "amount")),
            Map.entry(Kind.LOAN_ORIGINATE, List.of("bank", "borrower", "amount", "bucket",
                    "rate", "tenorMs")),
            Map.entry(Kind.LOAN_REPAY, List.of("bank", "borrower", "amount", "tranche")),
            Map.entry(Kind.IMPAIR, List.of("bank", "bucket", "amount")),
            Map.entry(Kind.WRITE_OFF, List.of("bank", "borrower", "amount")),
            Map.entry(Kind.THIRD_PARTY_PAY, List.of("bank", "borrower", "payer", "amount")),
            Map.entry(Kind.WAIVE, List.of("bank", "borrower", "amount")),
            Map.entry(Kind.COMMITMENT_INCREASE, List.of("bank", "amount")),
            Map.entry(Kind.RELIEF_DRAW,
                    List.of("bank", "provider", "amount", "collateralId", "collateralValue")),
            Map.entry(Kind.FACILITY_PAY, List.of("bank", "provider", "amount", "authorization")),
            Map.entry(Kind.FACILITY_WAIVE, List.of("bank", "provider", "amount",
                    "authorization")),
            Map.entry(Kind.FACILITY_PROVISION, List.of("provider", "amount")),
            Map.entry(Kind.FACILITY_DEFAULT, List.of("bank")),
            Map.entry(Kind.MIGRATE, List.of("origin", "owner", "bank", "amount")),
            Map.entry(Kind.ACCRUE_LOAN, List.of("bank", "borrower", "toMs", "tranche")),
            Map.entry(Kind.ACCRUE_DEPOSIT, List.of("bank", "owner", "toMs")),
            Map.entry(Kind.PAY_LOAN_INTEREST, List.of("bank", "borrower", "amount", "tranche")),
            Map.entry(Kind.PAY_DEPOSIT_INTEREST, List.of("bank", "owner", "amount")),
            Map.entry(Kind.MATURE_LOAN, List.of("bank", "borrower")),
            Map.entry(Kind.COMMITMENT_OPEN, List.of("bank", "commitment", "borrower", "limit",
                    "expiryMs", "tenorMs", "rate", "authorizedBy")),
            Map.entry(Kind.COMMITMENT_DRAW, List.of("bank", "commitment", "borrower", "amount")),
            Map.entry(Kind.FACILITY_AUTHORIZE, List.of("bank", "provider", "authorization",
                    "limit", "rate", "loanTenorMs", "authorizationExpiryMs",
                    "facilityMaturityMs", "collateralPolicy")),
            Map.entry(Kind.FACILITY_DRAW, List.of("bank", "provider", "authorization", "amount",
                    "collateralId")),
            Map.entry(Kind.FACILITY_ACCRUE, List.of("bank", "authorization", "toMs")),
            Map.entry(Kind.FACILITY_PAY_INTEREST, List.of("bank", "provider", "authorization",
                    "amount")),
            Map.entry(Kind.FACILITY_IMPAIR, List.of("provider", "authorization", "amount")),
            Map.entry(Kind.FACILITY_WRITE_OFF, List.of("provider", "authorization", "amount")),
            Map.entry(Kind.FACILITY_RECOVER, List.of("provider", "authorization", "payer",
                    "amount")),
            Map.entry(Kind.LOAN_ASSIGN, List.of("bank", "borrower", "buyer", "price",
                    "assignment", "purchase")),
            Map.entry(Kind.ASSIGNED_RECOVER, List.of("claim", "payer", "amount")),
            Map.entry(Kind.ASSIGNED_WAIVE, List.of("claim", "amount")),
            Map.entry(Kind.ASSIGNED_ACCRUE, List.of("claim", "toMs")),
            Map.entry(Kind.ASSIGNED_PAY_INTEREST, List.of("claim", "payer", "amount")),
            Map.entry(Kind.RESOLUTION_OPEN, List.of("bank", "authority")),
            Map.entry(Kind.RESOLUTION_DISPOSE, List.of("bank", "collateral", "buyer", "price",
                    "purchase")),
            Map.entry(Kind.RESOLUTION_DISTRIBUTE, List.of("bank")),
            Map.entry(Kind.RESOLUTION_DISCHARGE, List.of("bank", "consents")),
            Map.entry(Kind.PURCHASE_ACCEPT, List.of("seller", "subjectType", "subject", "price",
                    "expiryMs", "purchase")),
            Map.entry(Kind.DISCHARGE_CONSENT, List.of("bank", "account", "amount", "expiryMs",
                    "consent")),
            Map.entry(Kind.PAYMENT, List.of("payer", "src", "dst", "amount")));

    private static final Set<String> AMOUNT_KEYS = Set.of("amount", "collateralValue", "limit",
            "price");

    private static final Set<String> RATE_KEYS = Set.of("rate");

    private static final Set<String> INTEGER_KEYS = Set.of("tenorMs", "toMs", "expiryMs",
            "loanTenorMs", "authorizationExpiryMs", "facilityMaturityMs");

    /** 深拷贝并校验必要字段。 */
    public BankCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("命令标识不能为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("命令种类不能为空");
        }
        if (actorOwnerId == null || actorOwnerId.isBlank()) {
            throw new IllegalArgumentException("发起主体不能为空");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        (body == null ? Map.<String, String>of() : body).forEach((key, value) -> {
            String canonical = value;
            if (AMOUNT_KEYS.contains(key)) {
                canonical = Money.canonical(value);
            } else if (RATE_KEYS.contains(key)) {
                canonical = InterestAccrual.canonicalRate(value);
            } else if (INTEGER_KEYS.contains(key)) {
                canonical = InterestAccrual.canonicalInteger(value);
            }
            normalized.put(key, canonical);
        });
        body = java.util.Collections.unmodifiableMap(new TreeMap<>(normalized));
    }

    private static final Set<String> OPTIONAL = Set.of("bucket", "rate", "tenorMs", "offer",
            "collateralId", "authorization", "buyer", "price", "tranche");

    /**
     * 校验 body 键：缺必需键或出现未知键都属协议错误。
     */
    public void validateKeys() {
        List<String> allowed = ALLOWED.get(kind);
        if (allowed == null) {
            throw new BankException(Reasons.PROTOCOL_ERROR + ":kind:" + kind);
        }
        for (String key : body.keySet()) {
            if (key.startsWith("leg")) {
                throw new BankException(Reasons.INTERNAL_COMMAND_NOT_CALLABLE + ":" + key);
            }
            if (!allowed.contains(key)) {
                throw new BankException(Reasons.PROTOCOL_ERROR + ":unknown:" + key);
            }
        }
        for (String key : allowed) {
            if (OPTIONAL.contains(key)) {
                continue;
            }
            String value = body.get(key);
            if (value == null) {
                throw new BankException(Reasons.PROTOCOL_ERROR + ":missing:" + key);
            }
        }
    }

    /**
     * 取必需文本字段。
     *
     * @param key 字段名
     * @return 非空文本
     */
    public String text(String key) {
        String value = body.get(key);
        if (value == null || value.isBlank()) {
            throw new BankException(Reasons.PROTOCOL_ERROR + ":missing:" + key);
        }
        return value;
    }

    /**
     * 取必需金额字段（解析失败属协议错误）。
     *
     * @param key 字段名
     * @return 原值，尚未做 business/valuation 校验
     */
    public BigDecimal decimal(String key) {
        try {
            return new BigDecimal(text(key));
        } catch (NumberFormatException failure) {
            throw new BankException(Reasons.PROTOCOL_ERROR + ":number:" + key);
        }
    }

    /**
     * 取必需整数文本字段（解析失败属协议错误）。
     *
     * @param key 字段名
     * @return long 值
     */
    public long integer(String key) {
        String value = text(key);
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException failure) {
            throw new BankException(Reasons.PROTOCOL_ERROR + ":integer:" + key);
        }
    }

    /**
     * 取可选文本字段。
     *
     * @param key 字段名
     * @return 原值（可能为 null）
     */
    public String optional(String key) {
        return body.get(key);
    }

    /**
     * 规范化 body 文本：键排序、`k=v` 换行连接。
     *
     * @return 可比较的规范文本
     */
    public String canonicalBody() {
        StringBuilder sb = new StringBuilder(kind.name());
        for (Map.Entry<String, String> entry : body.entrySet()) {
            sb.append('\n').append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }
}
