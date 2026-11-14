package edu.cufe.auction.world.bank;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 冻结的银行世界档案：真实主体、账户与账表命名。
 *
 * <p>命令只使用本档案的 key；所有 id 都指向真实 OpeningRegistry / CashLedger 对象，
 * 不得从字符串回退或猜测。档案的规范文本参与 {@link BankConfig#fingerprint()}，
 * 换一套档案必须换新运行。</p>
 *
 * <p>本类只声明与校验，不持有现金；现金唯一权威仍是 {@code CashLedger} 账户表。</p>
 *
 * @param banks 银行 key → 银行引用
 * @param parties 主体 key → 主体引用
 * @param custodians 托管 key → 托管引用
 * @param buckets 借款人 key → 风险桶
 * @param groups 主体 key → 集团
 * @param loanAccounts 全部贷款总账科目
 * @param llpAccounts 全部准备总账科目
 * @param depositAccounts 全部存款总账科目
 * @param providerKey 救助提供者 key
 * @param subordinatedOwnerKey 次级债债权人 key
 * @param backgroundBorrowerKey 背景借款人 key
 */
public record BankingProfile(
        Map<String, BankRef> banks,
        Map<String, PartyRef> parties,
        Map<String, CustodyRef> custodians,
        Map<String, RiskBucket> buckets,
        Map<String, String> groups,
        List<String> loanAccounts,
        List<String> llpAccounts,
        List<String> depositAccounts,
        String providerKey,
        String subordinatedOwnerKey,
        String backgroundBorrowerKey) {

    /**
     * 银行：命令 key → 真实组织、财产主体与准备金现金账户。
     *
     * @param bankId 银行标识
     * @param partyId 真实组织 partyId
     * @param ownerId 真实财产主体
     * @param reserveAccountId 准备金现金账户
     */
    public record BankRef(String bankId, String partyId, String ownerId,
                          String reserveAccountId) {
        /** 校验必填文本。 */
        public BankRef {
            bankId = text(bankId, "bankId");
            partyId = text(partyId, "partyId");
            ownerId = text(ownerId, "ownerId");
            reserveAccountId = text(reserveAccountId, "reserveAccountId");
        }
    }

    /**
     * 客户/企业：命令 key → 真实主体、现金账户与存款总账科目。
     *
     * @param partyId 真实 partyId
     * @param ownerId 真实财产主体
     * @param cashAccountId 现金账户
     * @param depositGl 存款总账科目（可为 null）
     */
    /**
     * 主体引用：银行客户可以是自然/组织，也可以是被规则显式承认的产品或聚合财产主体。
     *
     * <p>旧四参口径（{@code ownerKind == null}）保持原严格语义：{@code partyId} 必填，
     * 必须等于 registry 中该 owner 的真实 partyId。只有声明了
     * {@link PartyKind#PRODUCT}/{@link PartyKind#AGGREGATE} 的新分支才允许 {@code partyId == null}，
     * 并要求 registry owner 的 kind/partyId 完全一致；空白假 party、错 owner、错 kind 一律拒绝。</p>
     *
     * <p>{@code ownerKind == null} 时 JSON 不输出该字段，旧档案规范文本与指纹逐字不变；
     * 非空时参加规范文本与指纹，旧世界规则不能激活新引用。</p>
     *
     * @param partyId 真实 partyId（旧口径必填；产品/聚合新口径必须为 null）
     * @param ownerId 真实财产主体
     * @param cashAccountId 现金账户
     * @param depositGl 存款总账科目；无存款为 null
     * @param ownerKind 新口径的显式财产主体类型；旧口径为 null
     */
    public record PartyRef(String partyId, String ownerId, String cashAccountId,
                           String depositGl,
                           @JsonInclude(JsonInclude.Include.NON_NULL)
                           PartyKind ownerKind) {

        /** 旧四参：严格自然人/组织口径（partyId 必填），JSON 与指纹保持旧字节。 */
        public PartyRef(String partyId, String ownerId, String cashAccountId, String depositGl) {
            this(partyId, ownerId, cashAccountId, depositGl, null);
        }

        /** 校验必填文本与 ownerKind 分支；depositGl 可为 null（无存款账户）。 */
        public PartyRef {
            ownerId = text(ownerId, "ownerId");
            cashAccountId = text(cashAccountId, "cashAccountId");
            if (depositGl != null && depositGl.isBlank()) {
                throw new IllegalArgumentException("depositGl 不能为空白");
            }
            if (ownerKind == null) {
                partyId = text(partyId, "partyId");
            } else if (ownerKind == PartyKind.PRODUCT || ownerKind == PartyKind.AGGREGATE) {
                if (partyId != null && !partyId.isBlank()) {
                    throw new IllegalArgumentException(
                            "产品/聚合财产主体不得伪造 partyId：" + ownerKind);
                }
                partyId = null;
            } else {
                throw new IllegalArgumentException(
                        "旧口径以外的 kind 只接受 PRODUCT/AGGREGATE：" + ownerKind);
            }
        }
    }

    /** 显式财产主体类型：只用于产品/聚合的新分支；PERSON/ORGANIZATION 仍走旧四参严格口径。 */
    public enum PartyKind {
        /** 产品财产主体（如 fund-a），registry 中 partyId 为 null。 */
        PRODUCT,
        /** 聚合外部部门/对手方，registry 中 partyId 为 null。 */
        AGGREGATE
    }

    /**
     * 托管经营人：真实主体与结算银行。
     *
     * <p>托管经营人是池存款（银行存款债权）的法定持有人，因此 {@code liabilityId} 必须是
     * 已在档案 {@code depositAccounts} 中声明的专用负债科目；客户明细负债不进银行总账，
     * 只存在 {@link CustodyBook}。池只在 {@code bankId} 这一行持有资产；非声明结算行持有
     * 该科目非零余额一律视为错行并拒绝。</p>
     *
     * <p>{@code poolPolicy} 是该池独立的计息口径声明（EXP）：目前只接受
     * {@link BankRules#CREDIT_NON_ACCRUAL}，即托管池明确非应计。它与同银行的零售存款 offer
     * 互不影响 —— 银行可以照常给普通客户计息，托管池仍不计息。声明计入档案指纹。</p>
     *
     * @param partyId 真实 partyId
     * @param ownerId 真实财产主体
     * @param bankId 结算银行 key（唯一可持有池资产的行）
     * @param liabilityId 池存款负债科目（=托管经营人的银行存款科目，须专用且已声明）
     * @param poolPolicy 池计息口径：只接受 {@code NON_ACCRUAL}
     */
    public record CustodyRef(String partyId, String ownerId, String bankId,
                             String liabilityId, String poolPolicy) {
        /** 校验必填文本与池口径。 */
        public CustodyRef {
            partyId = text(partyId, "partyId");
            ownerId = text(ownerId, "ownerId");
            bankId = text(bankId, "bankId");
            liabilityId = text(liabilityId, "liabilityId");
            if (!BankRules.CREDIT_NON_ACCRUAL.equals(poolPolicy)) {
                throw new IllegalArgumentException("PROFILE_CUSTODY_POOL_POLICY:" + poolPolicy);
            }
        }

        /**
         * 池是否按计息口径入账；当前只支持明确非应计。
         *
         * @return 恒为 false（非应计）
         */
        public boolean creditsAccrue() {
            return BankRules.CREDIT_OFFER.equals(poolPolicy);
        }
    }

    /**
     * 借款人 → 贷款桶、准备科目与集团。
     *
     * @param bucket 桶名
     * @param group 集团（可为 null）
     * @param loanGl 贷款总账科目
     * @param llpGl 准备总账科目
     */
    public record RiskBucket(String bucket, String group, String loanGl, String llpGl) {
        /** 校验必填文本；group 可为 null。 */
        public RiskBucket {
            bucket = text(bucket, "bucket");
            if (group != null && group.isBlank()) {
                throw new IllegalArgumentException("group 不能为空白");
            }
            loanGl = text(loanGl, "loanGl");
            llpGl = text(llpGl, "llpGl");
        }
    }

    /** 校验档案自洽性：唯一主体、唯一账户、桶与科目闭合。 */
    public BankingProfile {
        banks = Map.copyOf(Objects.requireNonNull(banks, "banks"));
        parties = Map.copyOf(Objects.requireNonNull(parties, "parties"));
        custodians = Map.copyOf(Objects.requireNonNull(custodians, "custodians"));
        buckets = Map.copyOf(Objects.requireNonNull(buckets, "buckets"));
        groups = Map.copyOf(Objects.requireNonNull(groups, "groups"));
        loanAccounts = List.copyOf(Objects.requireNonNull(loanAccounts, "loanAccounts"));
        llpAccounts = List.copyOf(Objects.requireNonNull(llpAccounts, "llpAccounts"));
        depositAccounts = List.copyOf(Objects.requireNonNull(depositAccounts, "depositAccounts"));
        if (banks.isEmpty()) {
            throw new IllegalArgumentException("银行档案不能为空");
        }
        if (parties.isEmpty()) {
            throw new IllegalArgumentException("主体档案不能为空");
        }
        Set<String> owners = new LinkedHashSet<>();
        Set<String> reserves = new LinkedHashSet<>();
        for (Map.Entry<String, BankRef> entry : banks.entrySet()) {
            text(entry.getKey(), "bankKey");
            BankRef bank = entry.getValue();
            if (!owners.add(bank.ownerId())) {
                throw new IllegalArgumentException("PROFILE_DUPLICATE_OWNER:" + bank.ownerId());
            }
            if (!reserves.add(bank.reserveAccountId())) {
                throw new IllegalArgumentException("PROFILE_DUPLICATE_ACCOUNT:"
                        + bank.reserveAccountId());
            }
        }
        Set<String> cash = new LinkedHashSet<>();
        Set<String> deposits = new LinkedHashSet<>();
        for (Map.Entry<String, PartyRef> entry : parties.entrySet()) {
            text(entry.getKey(), "partyKey");
            PartyRef party = entry.getValue();
            if (!owners.add(party.ownerId())) {
                throw new IllegalArgumentException("PROFILE_DUPLICATE_OWNER:" + party.ownerId());
            }
            if (!cash.add(party.cashAccountId())) {
                throw new IllegalArgumentException("PROFILE_DUPLICATE_ACCOUNT:"
                        + party.cashAccountId());
            }
            if (party.depositGl() != null && !deposits.add(party.depositGl())) {
                throw new IllegalArgumentException("PROFILE_DUPLICATE_ACCOUNT:"
                        + party.depositGl());
            }
        }
        for (Map.Entry<String, CustodyRef> entry : custodians.entrySet()) {
            text(entry.getKey(), "custodyKey");
            CustodyRef custody = entry.getValue();
            if (!owners.add(custody.ownerId())) {
                throw new IllegalArgumentException("PROFILE_DUPLICATE_OWNER:"
                        + custody.ownerId());
            }
            if (!deposits.add(custody.liabilityId())) {
                throw new IllegalArgumentException("PROFILE_DUPLICATE_ACCOUNT:"
                        + custody.liabilityId());
            }
        }
        for (Map.Entry<String, RiskBucket> entry : buckets.entrySet()) {
            if (!parties.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("PROFILE_UNKNOWN_BORROWER:" + entry.getKey());
            }
        }
        accounts("loanAccounts", loanAccounts);
        accounts("llpAccounts", llpAccounts);
        accounts("depositAccounts", depositAccounts);
        Set<String> all = new LinkedHashSet<>();
        all.addAll(loanAccounts);
        all.addAll(llpAccounts);
        all.addAll(depositAccounts);
        for (RiskBucket bucket : buckets.values()) {
            if (!loanAccounts.contains(bucket.loanGl())) {
                throw new IllegalArgumentException("PROFILE_BUCKET_NOT_DECLARED:"
                        + bucket.loanGl());
            }
            if (!llpAccounts.contains(bucket.llpGl())) {
                throw new IllegalArgumentException("PROFILE_BUCKET_NOT_DECLARED:"
                        + bucket.llpGl());
            }
        }
        for (PartyRef party : parties.values()) {
            if (party.depositGl() != null && !depositAccounts.contains(party.depositGl())) {
                throw new IllegalArgumentException("PROFILE_DEPOSIT_NOT_DECLARED:"
                        + party.depositGl());
            }
        }
        for (String deposit : depositAccounts) {
            long count = parties.values().stream()
                    .filter(party -> deposit.equals(party.depositGl())).count()
                    + custodians.values().stream()
                    .filter(custody -> deposit.equals(custody.liabilityId())).count();
            if (count != 1) {
                throw new IllegalArgumentException("PROFILE_DEPOSIT_OWNER_AMBIGUOUS:"
                        + deposit);
            }
        }
        for (CustodyRef custody : custodians.values()) {
            if (!depositAccounts.contains(custody.liabilityId())) {
                throw new IllegalArgumentException("PROFILE_CUSTODY_LIABILITY_NOT_DECLARED:"
                        + custody.liabilityId());
            }
            if (!banks.containsKey(custody.bankId())) {
                throw new IllegalArgumentException("PROFILE_CUSTODY_BANK_UNKNOWN:"
                        + custody.bankId());
            }
        }
        if (providerKey != null && !providerKey.isBlank() && !parties.containsKey(providerKey)) {
            throw new IllegalArgumentException("PROFILE_UNKNOWN_PROVIDER:" + providerKey);
        }
        if (subordinatedOwnerKey != null && !subordinatedOwnerKey.isBlank()
                && !parties.containsKey(subordinatedOwnerKey)) {
            throw new IllegalArgumentException("PROFILE_UNKNOWN_SUBORDINATED:"
                    + subordinatedOwnerKey);
        }
        if (backgroundBorrowerKey != null && !backgroundBorrowerKey.isBlank()
                && !parties.containsKey(backgroundBorrowerKey)
                && !buckets.containsKey(backgroundBorrowerKey)) {
            throw new IllegalArgumentException("PROFILE_UNKNOWN_BACKGROUND:"
                    + backgroundBorrowerKey);
        }
    }

    /**
     * 参考档案：与 candidate-4 旧夹具逐字同名的三行、公众与聚合借款人。
     *
     * @return 冻结的参考档案
     */
    public static BankingProfile reference() {
        return Reference.HOLDER;
    }

    /**
     * 规范文本：键排序、长度前缀，避免分隔符碰撞。
     *
     * @return 稳定文本
     */
    public String canonical() {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(banks).forEach((key, value) -> {
            line(sb, "bank", key);
            line(sb, "bankId", value.bankId());
            line(sb, "bankParty", value.partyId());
            line(sb, "bankOwner", value.ownerId());
            line(sb, "bankReserve", value.reserveAccountId());
        });
        new TreeMap<>(parties).forEach((key, value) -> {
            line(sb, "party", key);
            line(sb, "partyId", value.partyId());
            line(sb, "partyOwner", value.ownerId());
            line(sb, "partyCash", value.cashAccountId());
            line(sb, "partyDeposit", value.depositGl());
            if (value.ownerKind() != null) {
                line(sb, "partyKind", value.ownerKind().name());
            }
        });
        new TreeMap<>(custodians).forEach((key, value) -> {
            line(sb, "custody", key);
            line(sb, "custodyParty", value.partyId());
            line(sb, "custodyOwner", value.ownerId());
            line(sb, "custodyBank", value.bankId());
            line(sb, "custodyLiability", value.liabilityId());
            line(sb, "custodyPoolPolicy", value.poolPolicy());
        });
        new TreeMap<>(buckets).forEach((key, value) -> {
            line(sb, "bucket", key);
            line(sb, "bucketName", value.bucket());
            line(sb, "bucketGroup", value.group());
            line(sb, "bucketLoan", value.loanGl());
            line(sb, "bucketLlp", value.llpGl());
        });
        new TreeMap<>(groups).forEach((key, value) -> {
            line(sb, "groupKey", key);
            line(sb, "groupValue", value);
        });
        loanAccounts.forEach(value -> line(sb, "loanAccount", value));
        llpAccounts.forEach(value -> line(sb, "llpAccount", value));
        depositAccounts.forEach(value -> line(sb, "depositAccount", value));
        line(sb, "provider", providerKey);
        line(sb, "subordinated", subordinatedOwnerKey);
        line(sb, "background", backgroundBorrowerKey);
        return sb.toString();
    }

    /**
     * 档案指纹。
     *
     * @return SHA-256 十六进制
     */
    public String fingerprint() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("缺少 SHA-256", failure);
        }
    }

    private static void line(StringBuilder sb, String label, String value) {
        sb.append(label).append('=');
        if (value == null) {
            sb.append("-\n");
            return;
        }
        sb.append(value.length()).append(':').append(value).append('\n');
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static void accounts(String name, List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            text(value, name);
            if (!seen.add(value)) {
                throw new IllegalArgumentException("PROFILE_DUPLICATE_ACCOUNT:" + value);
            }
        }
    }

    /** 参考档案的惰性持有者，避免每次构造重复校验。 */
    private static final class Reference {
        private static final BankingProfile HOLDER = build();

        private static BankingProfile build() {
            Map<String, BankRef> banks = new LinkedHashMap<>();
            for (String bank : List.of("BANK-A", "BANK-B", "BANK-C")) {
                banks.put(bank, new BankRef(bank, bank, bank, bank + "|RESERVE"));
            }
            Map<String, PartyRef> parties = new LinkedHashMap<>();
            parties.put("CUST-X", new PartyRef("CUST-X", "CUST-X", "CUST-X|CASH", "DEPOSIT.X"));
            parties.put("CUST-Y", new PartyRef("CUST-Y", "CUST-Y", "CUST-Y|CASH", "DEPOSIT.Y"));
            parties.put("CO-C", new PartyRef("CO-C", "CO-C", "CO-C|CASH", "DEPOSIT.C"));
            parties.put("CO-D", new PartyRef("CO-D", "CO-D", "CO-D|CASH", "DEPOSIT.D"));
            parties.put("CO-E", new PartyRef("CO-E", "CO-E", "CO-E|CASH", "DEPOSIT.E"));
            parties.put("AGG", new PartyRef("AGG", "AGG", "AGG|CASH", "DEPOSIT.AGG"));
            parties.put("ALICE", new PartyRef("ALICE", "ALICE", "ALICE|CASH", "DEPOSIT.ALICE"));
            parties.put("BIG", new PartyRef("BIG", "BIG", "BIG|CASH", "DEPOSIT.BIG"));
            parties.put("BG-DEP", new PartyRef("BG-DEP", "BG-DEP", "BG-DEP|CASH", "DEPOSIT.BG"));
            parties.put("PAYER", new PartyRef("PAYER", "PAYER", "PAYER|CASH", "DEPOSIT.PAYER"));
            parties.put("SUPPLIER", new PartyRef("SUPPLIER", "SUPPLIER", "SUPPLIER|CASH",
                    "DEPOSIT.SUP"));
            parties.put("FACILITY", new PartyRef("FACILITY", "FACILITY", "FACILITY|CASH", null));
            parties.put("SUB", new PartyRef("SUB", "SUB", "SUB|CASH", null));
            Map<String, RiskBucket> buckets = new LinkedHashMap<>();
            buckets.put("CO-C", new RiskBucket("LOAN.C", "G1", "LOAN.C", "LLP.C"));
            buckets.put("CO-D", new RiskBucket("LOAN.D", "G1", "LOAN.D", "LLP.D"));
            buckets.put("CO-E", new RiskBucket("LOAN.E", "G2", "LOAN.E", "LLP.E"));
            buckets.put("AGG", new RiskBucket("LOAN.AGG", "@BACKGROUND_EXCLUDED",
                    "LOAN.AGG", "LLP.AGG"));
            Map<String, String> groups = new LinkedHashMap<>();
            groups.put("CO-C", "G1");
            groups.put("CO-D", "G1");
            groups.put("CO-E", "G2");
            groups.put("AGG", "@BACKGROUND_EXCLUDED");
            return new BankingProfile(banks, parties, Map.of(), buckets, groups,
                    List.of("LOAN.BG", "LOAN.C", "LOAN.D", "LOAN.AGG", "LOAN.E"),
                    List.of("LLP.BG", "LLP.C", "LLP.D", "LLP.AGG", "LLP.E"),
                    List.of("DEPOSIT.BG", "DEPOSIT.X", "DEPOSIT.Y", "DEPOSIT.C", "DEPOSIT.SUP",
                            "DEPOSIT.D", "DEPOSIT.E", "DEPOSIT.ALICE", "DEPOSIT.AGG",
                            "DEPOSIT.BIG", "DEPOSIT.PAYER"),
                    "FACILITY", "SUB", "AGG");
        }
    }
}
