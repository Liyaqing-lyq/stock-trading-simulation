package edu.cufe.auction.world.bank;

/**
 * 显式资金桶：一笔支付必须说清“从哪一种钱的哪一个桶出、进哪一个桶”。
 *
 * <p>三种桶互不抵补：{@link Cash} 是 {@code CashLedger} 里的真实现金账户，
 * {@link Deposit} 是某银行对某持有人的存款负债，{@link Custody} 是尚未实现的托管受益权。
 * 同一 owner 的现金与两家银行存款相加不构成付款权，也不得用 owner 总资产补足单个桶。</p>
 *
 * <p>桶身份只认规范文本 {@code CASH|accountId}、{@code DEPOSIT|bankKey|holderKey}、
 * {@code CUSTODY|custodianKey|clientKey}：字段精确匹配、不允许别名。旧写法
 * {@code owner|CASH} 之类型式一律 {@link Reasons#FUND_INVALID}，内部派生腿名
 * （{@code leg:} 前缀）一律 {@link Reasons#INTERNAL_COMMAND_NOT_CALLABLE}。</p>
 *
 * <p>托管桶在普通支付入口 {@link PaymentPort} 里给出明确拒绝：B6 纯支付接缝不得把托管受益权
 * 伪装成托管经营人的存款；托管存取款只走 {@link CustodyPort}，它按客户明细与池存款一致性
 * 在同一候选里同时改银行腿和明细负债。</p>
 */
public sealed interface Fund permits Fund.Cash, Fund.Deposit, Fund.Custody {

    /** 桶种类。 */
    enum Kind {
        /** 真实现金账户。 */ CASH,
        /** 银行存款负债。 */ DEPOSIT,
        /** 托管受益权（尚未实现）。 */ CUSTODY
    }

    /** 规范文本的字段分隔符；桶键自身不得包含它。 */
    String SEPARATOR = "|";

    /** 内部派生腿前缀；公开入口不可构造。 */
    String LEG_PREFIX = "leg:";

    /**
     * 桶种类。
     *
     * @return 种类
     */
    Kind kind();

    /**
     * 规范文本：既用于幂等比较，也用作冻结快照的键。
     *
     * @return 规范文本
     */
    String token();

    /**
     * 严格解析规范文本；不做任何别名或前缀推断。
     *
     * @param token 文本
     * @return 资金桶
     * @throws BankException 文本不是三种规范形式之一
     */
    static Fund parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BankException(Reasons.FUND_INVALID + ":empty");
        }
        if (token.startsWith(LEG_PREFIX)) {
            throw new BankException(Reasons.INTERNAL_COMMAND_NOT_CALLABLE + ":" + token);
        }
        int first = token.indexOf(SEPARATOR);
        if (first <= 0) {
            throw new BankException(Reasons.FUND_INVALID + ":form:" + token);
        }
        String head = token.substring(0, first);
        String rest = token.substring(first + 1);
        Fund parsed = switch (head) {
            case "CASH" -> new Cash(rest);
            case "DEPOSIT" -> {
                int second = rest.indexOf(SEPARATOR);
                if (second < 0) {
                    throw new BankException(Reasons.FUND_INVALID + ":deposit:" + token);
                }
                yield new Deposit(rest.substring(0, second), rest.substring(second + 1));
            }
            case "CUSTODY" -> {
                int second = rest.indexOf(SEPARATOR);
                if (second < 0) {
                    throw new BankException(Reasons.FUND_INVALID + ":custody:" + token);
                }
                yield new Custody(rest.substring(0, second), rest.substring(second + 1));
            }
            default -> throw new BankException(Reasons.FUND_INVALID + ":kind:" + head);
        };
        if (!parsed.token().equals(token)) {
            throw new BankException(Reasons.FUND_INVALID + ":canonical:" + token);
        }
        return parsed;
    }

    /**
     * 桶键校验：非空、不含分隔符。
     *
     * @param value 原值
     * @param field 字段名（错误文本用）
     * @return 原值
     */
    private static String key(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BankException(Reasons.FUND_INVALID + ":missing:" + field);
        }
        if (value.contains(SEPARATOR) || value.contains(LEG_PREFIX)) {
            throw new BankException(Reasons.FUND_INVALID + ":separator:" + field);
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new BankException(Reasons.FUND_INVALID + ":control:" + field);
            }
        }
        return value;
    }

    /**
     * 现金桶。
     *
     * @param accountId {@code CashLedger} 账户标识（不是主体 key，也不是 owner id）
     */
    record Cash(String accountId) implements Fund {
        /** 校验账户标识。 */
        public Cash {
            accountId = key(accountId, "accountId");
        }

        @Override
        public Kind kind() {
            return Kind.CASH;
        }

        @Override
        public String token() {
            return "CASH" + SEPARATOR + accountId;
        }
    }

    /**
     * 存款桶：某银行账簿里某持有人的存款负债。
     *
     * @param bankKey   档案里的银行 key
     * @param holderKey 档案里的持有人 key（不是 ownerId）
     */
    record Deposit(String bankKey, String holderKey) implements Fund {
        /** 校验两个 key。 */
        public Deposit {
            bankKey = key(bankKey, "bankKey");
            holderKey = key(holderKey, "holderKey");
        }

        @Override
        public Kind kind() {
            return Kind.DEPOSIT;
        }

        @Override
        public String token() {
            return "DEPOSIT" + SEPARATOR + bankKey + SEPARATOR + holderKey;
        }
    }

    /**
     * 托管受益权桶：B6 尚未实现，任何付款都返回 {@link Reasons#FUND_CUSTODY_UNSUPPORTED}。
     *
     * @param custodianKey 托管经营人 key
     * @param clientKey    客户 key
     */
    record Custody(String custodianKey, String clientKey) implements Fund {
        /** 校验两个 key。 */
        public Custody {
            custodianKey = key(custodianKey, "custodianKey");
            clientKey = key(clientKey, "clientKey");
        }

        @Override
        public Kind kind() {
            return Kind.CUSTODY;
        }

        @Override
        public String token() {
            return "CUSTODY" + SEPARATOR + custodianKey + SEPARATOR + clientKey;
        }
    }
}
