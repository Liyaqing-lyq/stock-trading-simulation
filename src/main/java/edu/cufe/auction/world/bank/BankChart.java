package edu.cufe.auction.world.bank;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 银行账表结构：既可表达冻结参考夹具，也可按 {@link BankingProfile} 实例化。
 *
 * <p>静态成员是 candidate-4 参考夹具的兼容面，只供旧测试与旧参考开局使用；
 * 生产命令必须使用 {@link #of(BankingProfile)} 返回的实例。实例的键、
 * 准备金、现金、存款、贷款桶与声明宇宙全部来自档案，不从字符串猜测。</p>
 */
public final class BankChart {
    /** 参考银行主体（仅旧夹具）。 */
    public static final List<String> BANKS = List.of("BANK-A", "BANK-B", "BANK-C");

    /** 参考资产类科目（准备金是现金账户，不在总账里另存余额）。 */
    public static final List<String> ASSETS =
            List.of("LOAN.BG", "LOAN.C", "LOAN.D", "LOAN.AGG", "LOAN.E", "PREMISES",
                    "INT.RECV");

    /** 参考减值准备（负资产）。 */
    public static final List<String> CONTRA =
            List.of("LLP.BG", "LLP.C", "LLP.D", "LLP.AGG", "LLP.E");

    /** 参考负债类科目。 */
    public static final List<String> LIABILITIES =
            List.of("DEPOSIT.BG", "DEPOSIT.X", "DEPOSIT.Y", "DEPOSIT.C", "DEPOSIT.SUP",
                    "DEPOSIT.D", "DEPOSIT.E", "DEPOSIT.ALICE", "DEPOSIT.AGG", "DEPOSIT.BIG",
                    "DEPOSIT.PAYER", "BORROW.FAC", "INT.PAYABLE.FAC", "SUBT2", "INT.PAYABLE");

    /** 参考权益类科目（DISCHARGE.EQUITY 是处置专用、不可分配、不并入 CET1）。 */
    public static final List<String> EQUITY =
            List.of("PAIDIN", "RETAINED", "AT1", "DISCHARGE.EQUITY");

    /** 参考收入类科目（贷方）。 */
    public static final List<String> INCOME =
            List.of("INT.INC", "FEE.INC", "RECOVERY", "GAIN.WAIVE", "GAIN.ASSIGN",
                    "GAIN.DISPOSAL");

    /** 参考费用类科目（借方）。 */
    public static final List<String> EXPENSE =
            List.of("INT.EXP", "INT.EXP.FAC", "FEE.EXP", "IMPAIR", "LOSS.ASSIGN",
                    "LOSS.DISPOSAL");

    /** 参考借款人 → 贷款桶。 */
    public static final Map<String, String> BORROWER_BUCKET = Map.of(
            "CO-C", "LOAN.C", "CO-D", "LOAN.D", "AGG", "LOAN.AGG", "CO-E", "LOAN.E");

    /** 参考主体 → 存款科目后缀。 */
    public static final Map<String, String> DEPOSIT_TAG = Map.ofEntries(
            Map.entry("CUST-X", "X"), Map.entry("CUST-Y", "Y"),
            Map.entry("CO-C", "C"), Map.entry("CO-D", "D"), Map.entry("CO-E", "E"),
            Map.entry("AGG", "AGG"), Map.entry("ALICE", "ALICE"), Map.entry("BIG", "BIG"),
            Map.entry("BG-DEP", "BG"), Map.entry("PAYER", "PAYER"),
            Map.entry("SUPPLIER", "SUP"));

    /** 参考背景聚合借款人（唯一允许做背景集团排除的主体）。 */
    public static final String BACKGROUND_BORROWER = "AGG";

    /** 现金科目名（准备金，唯一现金权威在 CashLedger）。 */
    public static final String RESERVE = "RESERVE";

    /** 应计贷款利息（资产）。 */
    public static final String INTEREST_RECEIVABLE = "INT.RECV";

    /** 应计存款利息（负债）。 */
    public static final String INTEREST_PAYABLE = "INT.PAYABLE";

    /** 贷款利息收入（收入，贷方）。 */
    public static final String INTEREST_INCOME = "INT.INC";

    /** 存款利息费用（费用，借方）。 */
    public static final String INTEREST_EXPENSE = "INT.EXP";

    /** 救助设施表内负债（名义本金，贷方）。 */
    public static final String FACILITY_DEBT = "BORROW.FAC";

    /** 救助设施应计未付利息（负债，贷方）。 */
    public static final String FACILITY_INTEREST_PAYABLE = "INT.PAYABLE.FAC";

    /** 设施利息费用（费用，借方）。 */
    public static final String FACILITY_INTEREST_EXPENSE = "INT.EXP.FAC";

    /** 处置专用权益（不可分配、不并入 CET1）。 */
    public static final String DISCHARGE_EQUITY = "DISCHARGE.EQUITY";

    /** 债权转让已实现损失（费用）。 */
    public static final String ASSIGN_LOSS = "LOSS.ASSIGN";

    /** 债权转让已实现收益（收入）。 */
    public static final String ASSIGN_GAIN = "GAIN.ASSIGN";

    /** 次级债债权人现金主体（参考夹具）。 */
    public static final String SUBORDINATED_OWNER = "SUB";

    private final BankingProfile profile;
    private final boolean reference;

    private BankChart(BankingProfile profile) {
        this.profile = profile;
        this.reference = profile == BankingProfile.reference();
    }

    /**
     * 参考账表（仅旧夹具）。
     *
     * @return 参考实例
     */
    public static BankChart reference() {
        return Holder.REFERENCE;
    }

    /**
     * 按档案实例化。
     *
     * @param profile 冻结档案
     * @return 实例
     */
    public static BankChart of(BankingProfile profile) {
        if (profile == BankingProfile.reference()) {
            return reference();
        }
        return new BankChart(profile);
    }

    /**
     * 档案。
     *
     * @return 档案
     */
    public BankingProfile profile() {
        return profile;
    }

    /**
     * 银行 key 列表（字典序）。
     *
     * @return 银行 key
     */
    public List<String> banks() {
        List<String> keys = new ArrayList<>(profile.banks().keySet());
        keys.sort(String::compareTo);
        return List.copyOf(keys);
    }

    /**
     * 是否银行 key。
     *
     * @param key 命令 key
     * @return 是返回 true
     */
    public boolean isBank(String key) {
        return profile.banks().containsKey(key);
    }

    /**
     * 资产科目。
     *
     * @return 资产科目
     */
    public List<String> assets() {
        if (reference) {
            return ASSETS;
        }
        List<String> names = new ArrayList<>(profile.loanAccounts());
        names.add("PREMISES");
        names.add(INTEREST_RECEIVABLE);
        return List.copyOf(names);
    }

    /**
     * 减值准备科目。
     *
     * @return 准备科目
     */
    public List<String> contra() {
        return reference ? CONTRA : profile.llpAccounts();
    }

    /**
     * 负债科目。
     *
     * @return 负债科目
     */
    public List<String> liabilities() {
        if (reference) {
            return LIABILITIES;
        }
        List<String> names = new ArrayList<>(profile.depositAccounts());
        names.add(FACILITY_DEBT);
        names.add(FACILITY_INTEREST_PAYABLE);
        names.add("SUBT2");
        names.add(INTEREST_PAYABLE);
        return List.copyOf(names);
    }

    /**
     * 权益科目。
     *
     * @return 权益科目
     */
    public List<String> equity() {
        return EQUITY;
    }

    /**
     * 收入科目。
     *
     * @return 收入科目
     */
    public List<String> income() {
        return INCOME;
    }

    /**
     * 费用科目。
     *
     * @return 费用科目
     */
    public List<String> expense() {
        return EXPENSE;
    }

    /**
     * 全部总账科目（不含准备金）。
     *
     * @return 全部总账科目
     */
    public List<String> glAccounts() {
        if (reference) {
            return declaredGlAccounts();
        }
        Set<String> names = new LinkedHashSet<>();
        names.addAll(assets());
        names.addAll(contra());
        names.addAll(liabilities());
        names.addAll(equity());
        names.addAll(income());
        names.addAll(expense());
        return List.copyOf(names);
    }

    /**
     * 声明宇宙：资产、准备、负债加准备金现金。
     *
     * @return 宇宙科目
     */
    public List<String> universe() {
        if (reference) {
            return declaredUniverse();
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(RESERVE);
        names.addAll(assets());
        names.addAll(contra());
        names.addAll(liabilities());
        return List.copyOf(names);
    }

    /**
     * 存款科目 → 主体 key。
     *
     * @return 只读映射
     */
    public Map<String, String> depositTags() {
        Map<String, String> map = new LinkedHashMap<>();
        new TreeMap<>(profile.parties()).forEach((key, value) -> {
            if (value.depositGl() != null) {
                map.put(key, value.depositGl());
            }
        });
        return Map.copyOf(map);
    }

    /**
     * 借款人 key 集合。
     *
     * @return 借款人 key
     */
    public List<String> borrowerKeys() {
        List<String> keys = new ArrayList<>(profile.buckets().keySet());
        keys.sort(String::compareTo);
        return List.copyOf(keys);
    }

    /**
     * 全部贷款桶科目。
     *
     * @return 贷款桶科目
     */
    public List<String> bucketValues() {
        List<String> values = new ArrayList<>();
        for (BankingProfile.RiskBucket bucket : profile.buckets().values()) {
            values.add(bucket.loanGl());
        }
        values.sort(String::compareTo);
        return List.copyOf(values);
    }

    /**
     * 是否有贷款桶。
     *
     * @param borrower 借款人 key
     * @return 有返回 true
     */
    public boolean hasBucket(String borrower) {
        return profile.buckets().containsKey(borrower);
    }

    /**
     * 是否有主体 key。
     *
     * @param key 命令 key
     * @return 有返回 true
     */
    public boolean hasParty(String key) {
        return profile.parties().containsKey(key);
    }

    /**
     * 是否托管经营人 key。
     *
     * <p>托管经营人不是普通主体：它有银行存款债权（池存款）但没有自己的现金账户；
     * 是否认它只由冻结档案的 {@link BankingProfile.CustodyRef} 决定，不由字符串推断。</p>
     *
     * @param key 命令 key
     * @return 是托管经营人返回 true
     */
    public boolean isCustodian(String key) {
        return profile.custodians().containsKey(key);
    }

    /**
     * 允许无到期口径的科目：固定资产、准备、权益与次级债。
     *
     * @return 科目
     */
    public List<String> nonMaturingCandidates() {
        if (reference) {
            return List.copyOf(BankRules.NON_MATURING_KIND.keySet());
        }
        Set<String> names = new LinkedHashSet<>();
        names.add("PREMISES");
        names.addAll(profile.llpAccounts());
        names.add("PAIDIN");
        names.add("RETAINED");
        names.add("AT1");
        names.add("SUBT2");
        return List.copyOf(names);
    }

    /**
     * 主体 key → 真实财产主体 id；未知 key 原样返回（供内部审计/权限层再拒）。
     *
     * @param key 命令 key
     * @return 财产主体 id
     */
    public String ownerIdOf(String key) {
        BankingProfile.BankRef bank = profile.banks().get(key);
        if (bank != null) {
            return bank.ownerId();
        }
        BankingProfile.PartyRef party = profile.parties().get(key);
        if (party != null) {
            return party.ownerId();
        }
        BankingProfile.CustodyRef custody = profile.custodians().get(key);
        if (custody != null) {
            return custody.ownerId();
        }
        return key;
    }

    /**
     * 准备金现金账户 id。
     *
     * @param bank 银行 key
     * @return 账户 id
     */
    public String reserveAccountOf(String bank) {
        BankingProfile.BankRef ref = profile.banks().get(bank);
        if (ref == null) {
            throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":bank:" + bank);
        }
        return ref.reserveAccountId();
    }

    /**
     * 主体现金账户 id；银行返回准备金账户。
     *
     * @param key 主体 key
     * @return 账户 id
     */
    public String cashAccountOf(String key) {
        BankingProfile.BankRef bank = profile.banks().get(key);
        if (bank != null) {
            return bank.reserveAccountId();
        }
        BankingProfile.PartyRef party = profile.parties().get(key);
        if (party == null) {
            throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":" + key);
        }
        return party.cashAccountId();
    }

    /**
     * 主体存款总账科目；托管经营人返回其池存款负债科目。
     *
     * <p>托管经营人只有一条负债科目（{@link BankingProfile.CustodyRef#liabilityId()}），
     * 且必须已在档案 {@code depositAccounts} 中声明，因此银行账簿里只有一份池存款。</p>
     *
     * @param key 主体 key 或托管经营人 key
     * @return 科目
     */
    public String depositAccountOf(String key) {
        BankingProfile.PartyRef party = profile.parties().get(key);
        if (party != null && party.depositGl() != null) {
            return party.depositGl();
        }
        BankingProfile.CustodyRef custody = profile.custodians().get(key);
        if (custody != null) {
            return custody.liabilityId();
        }
        throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":deposit:" + key);
    }

    /**
     * 借款人 → 贷款总账科目。
     *
     * @param borrower 借款人 key
     * @return 贷款科目
     */
    public String bucketFor(String borrower) {
        BankingProfile.RiskBucket bucket = profile.buckets().get(borrower);
        if (bucket == null) {
            throw new BankException(Reasons.UNKNOWN_BORROWER + ":" + borrower);
        }
        return bucket.loanGl();
    }

    /**
     * 借款人 → 准备总账科目。
     *
     * @param borrower 借款人 key
     * @return 准备科目
     */
    public String llpFor(String borrower) {
        BankingProfile.RiskBucket bucket = profile.buckets().get(borrower);
        if (bucket == null) {
            throw new BankException(Reasons.UNKNOWN_BORROWER + ":" + borrower);
        }
        return bucket.llpGl();
    }

    /**
     * 贷款总账科目（不含固定资产/应收利息）。
     *
     * @return 贷款科目
     */
    public List<String> loanAccounts() {
        if (reference) {
            return List.of("LOAN.BG", "LOAN.C", "LOAN.D", "LOAN.AGG", "LOAN.E");
        }
        return profile.loanAccounts();
    }

    /**
     * 存款总账科目。
     *
     * @return 存款科目
     */
    public List<String> depositAccounts() {
        if (reference) {
            return List.of("DEPOSIT.BG", "DEPOSIT.X", "DEPOSIT.Y", "DEPOSIT.C", "DEPOSIT.SUP",
                    "DEPOSIT.D", "DEPOSIT.E", "DEPOSIT.ALICE", "DEPOSIT.AGG", "DEPOSIT.BIG",
                    "DEPOSIT.PAYER");
        }
        return profile.depositAccounts();
    }

    /**
     * 是否存款科目。
     *
     * @param account 科目
     * @return 是返回 true
     */
    public boolean isDepositAccount(String account) {
        return depositAccounts().contains(account);
    }

    /**
     * 把 IMPAIR 的 bucket 参数解析为准备科目：支持借款人 key、桶名、loanGl/llpGl；
     * 参考档案额外保留 C/D/E/AGG/BG 的显式旧映射，不做任意前缀截断。
     *
     * @param token 命令参数
     * @return 准备科目
     */
    public String llpForToken(String token) {
        BankingProfile.RiskBucket byBorrower = profile.buckets().get(token);
        if (byBorrower != null) {
            return byBorrower.llpGl();
        }
        for (BankingProfile.RiskBucket bucket : profile.buckets().values()) {
            if (bucket.bucket().equals(token) || bucket.loanGl().equals(token)
                    || bucket.llpGl().equals(token)) {
                return bucket.llpGl();
            }
        }
        String legacyLoan = "LOAN." + token;
        String legacyLlp = "LLP." + token;
        for (BankingProfile.RiskBucket bucket : profile.buckets().values()) {
            if (bucket.loanGl().equals(legacyLoan) && bucket.llpGl().equals(legacyLlp)) {
                return bucket.llpGl();
            }
        }
        if (reference) {
            switch (token) {
                case "BG":
                    return "LLP.BG";
                case "C":
                    return "LLP.C";
                case "D":
                    return "LLP.D";
                case "AGG":
                    return "LLP.AGG";
                case "E":
                    return "LLP.E";
                default:
                    break;
            }
        }
        throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":bucket:" + token);
    }

    /**
     * 债权键。
     *
     * @param bank 银行 key
     * @param borrower 借款人 key
     * @return 键
     */
    public String loanKeyOf(String bank, String borrower) {
        return key("L", bank, borrower);
    }

    /**
     * 债权键中的银行 key。
     *
     * @param key 债权键
     * @return 银行 key；非法键返回 null
     */
    public String bankOfLoanKey(String key) {
        String[] parts = splitKey("L", key);
        return parts == null ? null : parts[0];
    }

    /**
     * 债权键中的借款人 key。
     *
     * @param key 债权键
     * @return 借款人 key；非法键返回 null
     */
    public String borrowerOfLoanKey(String key) {
        String[] parts = splitKey("L", key);
        return parts == null ? null : parts[1];
    }

    /**
     * 存款条款键中的银行 key。
     *
     * @param key 存款条款键
     * @return 银行 key；非法键返回 null
     */
    public String bankOfDepositTermsKey(String key) {
        String[] parts = splitKey("D", key);
        return parts == null ? null : parts[0];
    }

    /**
     * 存款条款键中的持有人 key。
     *
     * @param key 存款条款键
     * @return 持有人 key；非法键返回 null
     */
    public String holderOfDepositTermsKey(String key) {
        String[] parts = splitKey("D", key);
        return parts == null ? null : parts[1];
    }

    /**
     * 设施账户键。
     *
     * @param bank 银行 key
     * @param authorizationId 授权标识
     * @return 键
     */
    public String facilityKeyOf(String bank, String authorizationId) {
        return key("F", bank, authorizationId);
    }

    /**
     * 设施键中的银行 key。
     *
     * @param key 设施键
     * @return 银行 key；非法键返回 null
     */
    public String bankOfFacilityKey(String key) {
        String[] parts = splitKey("F", key);
        return parts == null ? null : parts[0];
    }

    /**
     * 设施键中的授权标识。
     *
     * @param key 设施键
     * @return 授权标识；非法键返回 null
     */
    public String authorizationOfFacilityKey(String key) {
        String[] parts = splitKey("F", key);
        return parts == null ? null : parts[1];
    }

    /**
     * 明细承诺键。
     *
     * @param bank 银行 key
     * @param commitment 承诺编号
     * @return 键
     */
    public String commitmentKeyOf(String bank, String commitment) {
        return key("C", bank, commitment);
    }

    /**
     * 明细承诺键中的银行 key。
     *
     * @param key 承诺键
     * @return 银行 key；非法键返回 null
     */
    public String bankOfCommitmentKey(String key) {
        String[] parts = splitKey("C", key);
        return parts == null ? null : parts[0];
    }

    /**
     * 存款条款键。
     *
     * @param bank 银行 key
     * @param owner 主体 key
     * @return 键
     */
    public String depositTermsKeyOf(String bank, String owner) {
        return key("D", bank, owner);
    }

    /**
     * 存款科目 → 主体 key。
     *
     * @param account 科目
     * @return 主体 key；非本档案存款返回 null
     */
    public String ownerOfDeposit(String account) {
        if (account == null) {
            return null;
        }
        for (Map.Entry<String, BankingProfile.PartyRef> entry : profile.parties().entrySet()) {
            if (account.equals(entry.getValue().depositGl())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 救助提供者 key。
     *
     * @return key 或 null
     */
    public String providerKey() {
        return profile.providerKey();
    }

    /**
     * 次级债债权人 key。
     *
     * @return key 或 null
     */
    public String subordinatedOwnerKey() {
        return profile.subordinatedOwnerKey();
    }

    /**
     * 背景借款人 key。
     *
     * @return key 或 null
     */
    public String backgroundBorrowerKey() {
        return profile.backgroundBorrowerKey();
    }

    private String key(String prefix, String first, String second) {
        if (reference) {
            return first + "|" + second;
        }
        return prefix + "|" + first.length() + ":" + first + "|"
                + second.length() + ":" + second;
    }

    private String[] splitKey(String prefix, String key) {
        if (key == null) {
            return null;
        }
        if (reference) {
            int split = key.indexOf('|');
            if (split <= 0 || split == key.length() - 1) {
                return null;
            }
            return new String[]{key.substring(0, split), key.substring(split + 1)};
        }
        String head = prefix + "|";
        if (!key.startsWith(head)) {
            return null;
        }
        try {
            int first = key.indexOf(':', head.length());
            int bankLength = Integer.parseInt(key.substring(head.length(), first));
            int bankStart = first + 1;
            int bankEnd = bankStart + bankLength;
            String bank = key.substring(bankStart, bankEnd);
            int second = key.indexOf(':', bankEnd + 1);
            int borrowerLength = Integer.parseInt(key.substring(bankEnd + 1, second));
            int borrowerStart = second + 1;
            String borrower = key.substring(borrowerStart, borrowerStart + borrowerLength);
            if (borrowerStart + borrowerLength != key.length()) {
                return null;
            }
            return new String[]{bank, borrower};
        } catch (RuntimeException failure) {
            return null;
        }
    }

    // ------------------------------------------------------------ 参考静态面

    /**
     * 参考存款科目 → 客户主体。
     *
     * @param account 科目名
     * @return 客户主体；非存款科目或未登记返回 null
     */
    public static String ownerOfDepositAccount(String account) {
        if (account == null || !account.startsWith("DEPOSIT.")) {
            return null;
        }
        String tag = account.substring("DEPOSIT.".length());
        for (Map.Entry<String, String> entry : DEPOSIT_TAG.entrySet()) {
            if (entry.getValue().equals(tag)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 参考设施账户键。
     *
     * @param bank 银行
     * @param authorizationId 授权标识
     * @return 键
     */
    public static String facilityKey(String bank, String authorizationId) {
        return bank + "|" + authorizationId;
    }

    /**
     * 参考准备金现金账户 id。
     *
     * @param bank 银行
     * @return 账户 id
     */
    public static String reserveAccount(String bank) {
        return bank + "|RESERVE";
    }

    /**
     * 参考公众现金账户 id。
     *
     * @param owner 主体
     * @return 账户 id
     */
    public static String cashAccount(String owner) {
        return owner + "|CASH";
    }

    /**
     * 参考存款键。
     *
     * @param bank 银行
     * @param owner 客户
     * @return 键
     */
    public static String depositKey(String bank, String owner) {
        return bank + "|DEPOSIT|" + owner;
    }

    /**
     * 参考存款科目。
     *
     * @param owner 客户
     * @return 科目
     */
    public static String depositAccount(String owner) {
        String tag = DEPOSIT_TAG.get(owner);
        if (tag == null) {
            throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":deposit:" + owner);
        }
        return "DEPOSIT." + tag;
    }

    /**
     * 参考借款人贷款桶。
     *
     * @param borrower 借款人
     * @return 科目
     */
    public static String bucketOf(String borrower) {
        String bucket = BORROWER_BUCKET.get(borrower);
        if (bucket == null) {
            throw new BankException(Reasons.UNKNOWN_BORROWER + ":" + borrower);
        }
        return bucket;
    }

    /**
     * 参考债权键。
     *
     * @param bank 银行
     * @param borrower 借款人
     * @return 键
     */
    public static String loanKey(String bank, String borrower) {
        return bank + "|" + borrower;
    }

    /**
     * 参考全部总账科目。
     *
     * @return 科目
     */
    public static List<String> declaredGlAccounts() {
        return List.of(
                "LOAN.BG", "LOAN.C", "LOAN.D", "LOAN.AGG", "LOAN.E", "PREMISES",
                "LLP.BG", "LLP.C", "LLP.D", "LLP.AGG", "LLP.E",
                "DEPOSIT.BG", "DEPOSIT.X", "DEPOSIT.Y", "DEPOSIT.C", "DEPOSIT.SUP",
                "DEPOSIT.D", "DEPOSIT.E", "DEPOSIT.ALICE", "DEPOSIT.AGG", "DEPOSIT.BIG",
                "DEPOSIT.PAYER", "BORROW.FAC", "INT.PAYABLE.FAC", "SUBT2",
                "PAIDIN", "RETAINED", "AT1", "DISCHARGE.EQUITY",
                "INT.INC", "FEE.INC", "RECOVERY", "GAIN.WAIVE", "GAIN.ASSIGN",
                "GAIN.DISPOSAL",
                "INT.EXP", "INT.EXP.FAC", "FEE.EXP", "IMPAIR", "LOSS.ASSIGN",
                "LOSS.DISPOSAL",
                "INT.RECV", "INT.PAYABLE");
    }

    /**
     * 参考存款条款键。
     *
     * @param bank 银行
     * @param owner 客户
     * @return 键
     */
    public static String depositTermsKey(String bank, String owner) {
        return bank + "|" + owner;
    }

    /**
     * 参考明细承诺键。
     *
     * @param bank 银行
     * @param commitment 承诺编号
     * @return 键
     */
    public static String commitmentKey(String bank, String commitment) {
        return bank + "|" + commitment;
    }

    /**
     * 参考声明宇宙。
     *
     * @return 宇宙科目
     */
    public static List<String> declaredUniverse() {
        List<String> names = new ArrayList<>();
        names.add(RESERVE);
        names.addAll(ASSETS);
        names.addAll(CONTRA);
        names.addAll(LIABILITIES);
        return List.copyOf(names);
    }

    private static final class Holder {
        private static final BankChart REFERENCE = new BankChart(BankingProfile.reference());
    }
}
