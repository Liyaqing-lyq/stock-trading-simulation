package edu.cufe.auction.world.bank;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 冻结的银行规则档案（EXP-1），不可变；用 {@link Builder} 构造变体。
 *
 * <p>阈值与权重都是合成实验参数，不是监管校准；来源线索未验证原文。
 * 指纹精确序列化所有行为与声明字段（比率 6 位、金额 2 位、文本原样）。</p>
 */
public final class BankConfig {

    /** 元数据上限：precision 与 |scale| 都不超过该值。 */
    private static final int MAX_META = 40;

    /** 比率量级上限（实验参数远小于此值）。 */
    private static final BigDecimal RATIO_LIMIT = new BigDecimal("1000000");

    /** 带定义域的数值参数（比率 6 位小数）。
     * @param value value 参数
     * @param min min 参数
     * @param max max 参数
     * @param allowZero allowZero 参数
     */
public record Spec(BigDecimal value, BigDecimal min, BigDecimal max, boolean allowZero) {
        /** 深拷贝并校验。 */
        public Spec {
            value = ratio(value);
            min = min == null ? null : ratio(min);
            max = max == null ? null : ratio(max);
        }
    }

    /** 来源线索：只有名称与链接，未核原文。
     * @param name name 参数
     * @param url url 参数
     * @param doc doc 参数
     */
public record Clue(String name, String url, String doc) {
    }

    /** 利息条款 profile：只有两种冻结组合，避免“半个条款”。
     * @param profile profile 标识
     * @param rateExponent 费率定标指数
     * @param dayBasisDays 年基准天数
     */
    public record Interest(String profile, int rateExponent, int dayBasisDays) {
        /** 校验组合：NONE 必须 0/0，ACT365_MS_U1E6 必须 6/365。 */
        public Interest {
            if (profile == null) {
                throw new IllegalArgumentException("INTEREST_PROFILE_INVALID:null");
            }
            if (BankRules.INTEREST_NONE.equals(profile)) {
                if (rateExponent != 0 || dayBasisDays != 0) {
                    throw new IllegalArgumentException(Reasons.INTEREST_PROFILE_INVALID
                            + ":NONE");
                }
            } else if (BankRules.INTEREST_ACT365.equals(profile)) {
                if (rateExponent != InterestAccrual.RATE_EXPONENT
                        || dayBasisDays != InterestAccrual.DAY_BASIS_DAYS) {
                    throw new IllegalArgumentException(Reasons.INTEREST_PROFILE_INVALID
                            + ":ACT365");
                }
            } else {
                throw new IllegalArgumentException(Reasons.INTEREST_PROFILE_INVALID + ":"
                        + profile);
            }
        }

        /**
         * 是否启用完整利息条款。
         *
         * @return 启用返回 true
         */
        public boolean enabled() {
            return BankRules.INTEREST_ACT365.equals(profile);
        }

        /**
         * 旧档语义（无息、无条款）。
         *
         * @return NONE 组合
         */
        public static Interest none() {
            return new Interest(BankRules.INTEREST_NONE, 0, 0);
        }

        /**
         * EXP 启用档：ACT/365 + 1e6 定标费率。
         *
         * @return ACT365_MS_U1E6 组合
         */
        public static Interest act365() {
            return new Interest(BankRules.INTEREST_ACT365, InterestAccrual.RATE_EXPONENT,
                    InterestAccrual.DAY_BASIS_DAYS);
        }
    }

    /**
     * 银行侧冻结的存款 offer（每行一份 rate sheet）：客户只能引用，不能自选利率/期限。
     *
     * @param bank      银行
     * @param offerId   产品标识（行内唯一，不含分隔符）
     * @param rateU     定标费率（1e6=100%）
     * @param tenorMs   期限（毫秒）
     * @param creditRule 银行产生的存款如何处理：OFFER 挂本 offer / NON_ACCRUAL 明确非应计
     */
    public record DepositOffer(String bank, String offerId, long rateU, long tenorMs,
                               String creditRule) {
        /** 校验银行、标识、费率、期限与 creditRule。 */
        public DepositOffer {
            if (bank == null || bank.isBlank()) {
                throw new IllegalArgumentException(Reasons.UNKNOWN_BANK + ":" + bank);
            }
            if (offerId == null || offerId.isBlank() || offerId.indexOf('|') >= 0) {
                throw new IllegalArgumentException(Reasons.OFFER_UNKNOWN + ":" + offerId);
            }
            if (rateU < 0 || rateU > InterestAccrual.RATE_ONE) {
                throw new IllegalArgumentException(Reasons.RATE_INVALID + ":" + rateU);
            }
            if (tenorMs <= 0) {
                throw new IllegalArgumentException(Reasons.TENOR_INVALID);
            }
            if (!BankRules.CREDIT_OFFER.equals(creditRule)
                    && !BankRules.CREDIT_NON_ACCRUAL.equals(creditRule)) {
                throw new IllegalArgumentException(Reasons.OFFER_UNKNOWN + ":rule:" + creditRule);
            }
        }

        /**
         * 银行产生的存款是否按本 offer 计息。
         *
         * @return 计息返回 true
         */
        public boolean creditsAccrue() {
            return BankRules.CREDIT_OFFER.equals(creditRule);
        }
    }

    /**
     * 押品估值声明：必须绑定真实资产键；价值与折扣只来自冻结档案（EXP），命令不得自报。
     *
     * @param collateralId   押品标识（全局唯一，不含分隔符）
     * @param ownerBank      所有权人（必须是银行主体）
     * @param kind           押品类（PREMISES / LOAN）
     * @param assetKey       真实资产键：PREMISES 账户，或 {@code bank|borrower} 贷款键
     * @param appraisedValue 档案估值（两位小数，不得超过开局真实账面）
     * @param haircutU       折扣（定标 1e6，0 表示不打折）
     */
    public record CollateralDecl(String collateralId, String ownerBank, String kind,
                                 String assetKey, BigDecimal appraisedValue, long haircutU) {
        /** 校验标识、所有权、类别、资产键、估值与折扣。 */
        public CollateralDecl {
            if (collateralId == null || collateralId.isBlank() || collateralId.indexOf('|') >= 0) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_INVALID + ":" + collateralId);
            }
            if (ownerBank == null || ownerBank.isBlank()) {
                throw new IllegalArgumentException(Reasons.UNKNOWN_BANK + ":" + ownerBank);
            }
            if (!BankRules.COLLATERAL_KINDS.contains(kind)) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_INVALID + ":kind:" + kind);
            }
            if (assetKey == null || assetKey.isBlank()) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_INVALID + ":assetKey");
            }
            if ("PREMISES".equals(kind)) {
                if (!assetKey.equals(ownerBank + "|PREMISES")) {
                    throw new IllegalArgumentException(Reasons.COLLATERAL_ASSET_UNKNOWN + ":"
                            + assetKey);
                }
            } else if (!assetKey.startsWith(ownerBank + "|")) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_ASSET_UNKNOWN + ":"
                        + assetKey);
            }
            appraisedValue = Money.valuation(appraisedValue);
            if (appraisedValue.signum() <= 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (haircutU < 0 || haircutU >= InterestAccrual.RATE_ONE) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_INVALID + ":haircut");
            }
        }

        /**
         * 可用押品价值（估值 × (1−折扣)，向下取整到分；开局会再受真实账面约束）。
         *
         * @return 可用价值
         */
        public BigDecimal usableValue() {
            long cents = InterestAccrual.cents(appraisedValue);
            BigInteger usable = BigInteger.valueOf(cents)
                    .multiply(BigInteger.valueOf(InterestAccrual.RATE_ONE - haircutU))
                    .divide(BigInteger.valueOf(InterestAccrual.RATE_ONE));
            return InterestAccrual.money(usable.longValueExact());
        }
    }

    /**
     * B3/B4 条款声明：救助设施、押品估值簿、处置权限与无担保债权阶梯。
     *
     * <p>{@code profile=NONE} 表示旧档案：设施走旧记忆账、无押品与处置。启用档的一切参数都是
     * EXP 实验条款，随指纹冻结；换档必须新 run，旧状态不得热重绑。</p>
     *
     * @param profile             NONE / EXP-B3B4
     * @param resolutionAuthority 独立处置权主体（不得是银行自身）
     * @param collaterals         押品估值声明
     * @param claimTiers          无担保债权阶梯（每级为科目通配，按序先偿）
     */
    public record FacilityTerms(String profile, String resolutionAuthority,
                                List<CollateralDecl> collaterals, List<List<String>> claimTiers) {

        /** 深拷贝并校验组合。 */
        public FacilityTerms {
            if (profile == null || (!BankRules.FACILITY_NONE.equals(profile)
                    && !BankRules.FACILITY_EXP.equals(profile))) {
                throw new IllegalArgumentException(Reasons.FACILITY_PROFILE_INVALID + ":" + profile);
            }
            List<CollateralDecl> collateralCopy = List.copyOf(
                    collaterals == null ? List.of() : collaterals);
            List<List<String>> tierCopy = new ArrayList<>();
            for (List<String> tier : claimTiers == null ? List.<List<String>>of() : claimTiers) {
                tierCopy.add(List.copyOf(tier));
            }
            if (BankRules.FACILITY_EXP.equals(profile)) {
                if (resolutionAuthority == null || resolutionAuthority.isBlank()) {
                    throw new IllegalArgumentException(
                            Reasons.FACILITY_PROFILE_INVALID + ":authority");
                }
                if (resolutionAuthority.indexOf('|') >= 0) {
                    throw new IllegalArgumentException(
                            Reasons.FACILITY_PROFILE_INVALID + ":authority-delimiter");
                }
                if (tierCopy.isEmpty()) {
                    throw new IllegalArgumentException(Reasons.CLAIM_TIERS_INVALID);
                }
                Set<String> ids = new java.util.LinkedHashSet<>();
                Set<String> assets = new java.util.LinkedHashSet<>();
                for (CollateralDecl decl : collateralCopy) {
                    if (!ids.add(decl.collateralId())) {
                        throw new IllegalArgumentException(Reasons.COLLATERAL_DUPLICATE + ":"
                                + decl.collateralId());
                    }
                    if (!assets.add(decl.assetKey())) {
                        throw new IllegalArgumentException(Reasons.COLLATERAL_ASSET_TAKEN + ":"
                                + decl.assetKey());
                    }
                }
            } else {
                if (resolutionAuthority == null ? false : !resolutionAuthority.isBlank()) {
                    throw new IllegalArgumentException(Reasons.FACILITY_PROFILE_INVALID
                            + ":authority-without-profile");
                }
                if (!collateralCopy.isEmpty() || !tierCopy.isEmpty()) {
                    throw new IllegalArgumentException(Reasons.FACILITY_PROFILE_INVALID
                            + ":declarations-without-profile");
                }
            }
            collaterals = collateralCopy;
            claimTiers = List.copyOf(tierCopy);
        }

        /**
         * 是否启用 B3/B4 条款。
         *
         * @return 启用返回 true
         */
        public boolean enabled() {
            return BankRules.FACILITY_EXP.equals(profile);
        }

        /**
         * 旧档案语义（无设施条款、无押品、无处置）。
         *
         * @return NONE 组合
         */
        public static FacilityTerms none() {
            return new FacilityTerms(BankRules.FACILITY_NONE, null, List.of(), List.of());
        }
    }

    private static final List<String> ALL_ACTIONS = BankRules.ENFORCEMENT_ACTIONS;
    private final String archiveId;
    private final int version;
    private final String label;
    private final List<String> required;
    private final List<String> implemented;
    private final List<String> omitted;
    private final Spec ccf;
    private final Spec commitmentWeight;
    private final Spec runoffDemand;
    private final List<String> hqla;
    private final Map<String, Spec> riskWeights;
    private final List<String> buckets;
    private final List<String> nonMaturing;
    private final Map<String, String> nonMaturingReasons;
    private final Map<String, Map<String, BigDecimal>> maturity;
    private final Map<String, String> lcrClass;
    private final Map<String, String> groupMap;
    private final Map<String, String> groupExclusionReasons;
    private final Map<String, BigDecimal> thresholds;
    private final String enforcementProfile;
    private final Map<String, String> enforcement;
    private final Map<String, List<String>> acceptedNa;
    private final BigDecimal at1;
    private final Interest interest;
    private final Map<String, DepositOffer> depositOffers;
    private final FacilityTerms facilityTerms;
    private final String legacyCommitmentAttribution;
    private final String coverageRule;
    private final List<Clue> referenceClues;
    private final String provenanceStatus;
    private final BankingProfile profile;
    private final BankChart chart;

    private BankConfig(Builder b) {
        archiveId = b.archiveId;
        version = b.version;
        label = b.label;
        required = List.copyOf(b.required);
        implemented = List.copyOf(b.implemented);
        omitted = List.copyOf(b.omitted);
        ccf = b.ccf;
        commitmentWeight = b.commitmentWeight;
        runoffDemand = b.runoffDemand;
        hqla = List.copyOf(b.hqla);
        riskWeights = copySpecs(b.riskWeights);
        buckets = List.copyOf(b.buckets);
        nonMaturing = List.copyOf(b.nonMaturing);
        nonMaturingReasons = Map.copyOf(b.nonMaturingReasons);
        maturity = copyMaturity(b.maturity);
        lcrClass = Map.copyOf(b.lcrClass);
        groupMap = Map.copyOf(b.groupMap);
        groupExclusionReasons = Map.copyOf(b.groupExclusionReasons);
        thresholds = copyRatios(b.thresholds);
        enforcementProfile = b.enforcementProfile;
        enforcement = Map.copyOf(b.enforcement);
        acceptedNa = copyAccepted(b.acceptedNa);
        at1 = Money.valuation(b.at1);
        if (b.interest == null) {
            throw new IllegalArgumentException(Reasons.INTEREST_PROFILE_INVALID + ":null");
        }
        interest = b.interest;
        depositOffers = copyOffers(b.depositOffers);
        if (b.facilityTerms == null) {
            throw new IllegalArgumentException(Reasons.FACILITY_PROFILE_INVALID + ":null");
        }
        facilityTerms = b.facilityTerms;
        if (b.legacyCommitmentAttribution == null) {
            throw new IllegalArgumentException(Reasons.LEGACY_COMMITMENT_INVALID + ":null");
        }
        legacyCommitmentAttribution = b.legacyCommitmentAttribution;
        coverageRule = b.coverageRule;
        referenceClues = List.copyOf(b.referenceClues);
        provenanceStatus = b.provenanceStatus;
        profile = b.profile;
        chart = BankChart.of(profile);
        if (facilityTerms.enabled() && chart.isBank(facilityTerms.resolutionAuthority())) {
            throw new IllegalArgumentException(
                    Reasons.FACILITY_PROFILE_INVALID + ":authority-is-bank");
        }
    }

    /**

     * 原值。
     * @param value 原值
     * @return 6 位小数的比率

     */
    static BigDecimal ratio(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("RATIO_INVALID:null");
        }
        if (value.precision() > MAX_META || Math.abs(value.scale()) > MAX_META
                || value.abs().compareTo(RATIO_LIMIT) > 0) {
            throw new IllegalArgumentException("RATIO_OUT_OF_RANGE");
        }
        try {
            return value.setScale(6, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("RATIO_PRECISION:最多 6 位有效小数");
        }
    }

    private static Map<String, Spec> copySpecs(Map<String, Spec> source) {
        Map<String, Spec> copy = new TreeMap<>();
        source.forEach(copy::put);
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Map<String, BigDecimal>> copyMaturity(
            Map<String, Map<String, BigDecimal>> source) {
        Map<String, Map<String, BigDecimal>> copy = new TreeMap<>();
        source.forEach((k, v) -> {
            Map<String, BigDecimal> inner = new TreeMap<>();
            v.forEach((bucket, r) -> inner.put(bucket, ratio(r)));
            copy.put(k, Collections.unmodifiableMap(inner));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, BigDecimal> copyRatios(Map<String, BigDecimal> source) {
        Map<String, BigDecimal> copy = new TreeMap<>();
        source.forEach((k, v) -> copy.put(k, ratio(v)));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<String>> copyAccepted(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new TreeMap<>();
        source.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, DepositOffer> copyOffers(Map<String, DepositOffer> source) {
        Map<String, DepositOffer> copy = new TreeMap<>();
        source.forEach(copy::put);
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 档案标识。
     * @return 档案标识
     */
    public String archiveId() { return archiveId; }
    /**
     * 档案版本。
     * @return 档案版本
     */
    public int version() { return version; }
    /**
     * 展示标签。
     * @return 展示标签
     */
    public String label() { return label; }
    /**
     * 必须实现的指标。
     * @return 必须实现的指标
     */
    public List<String> required() { return required; }
    /**
     * 已实现的指标。
     * @return 已实现的指标
     */
    public List<String> implemented() { return implemented; }
    /**
     * 明确省略的指标。
     * @return 明确省略的指标
     */
    public List<String> omitted() { return omitted; }
    /**
     * 承诺信用转换系数。
     * @return 承诺信用转换系数
     */
    public Spec ccf() { return ccf; }
    /**
     * 承诺风险权重。
     * @return 承诺风险权重
     */
    public Spec commitmentWeight() { return commitmentWeight; }
    /**
     * 活期存款压力流失率。
     * @return 活期存款压力流失率
     */
    public Spec runoffDemand() { return runoffDemand; }
    /**
     * 合格流动性资产科目。
     * @return 合格流动性资产科目
     */
    public List<String> hqla() { return hqla; }
    /**
     * 各科目风险权重。
     * @return 各科目风险权重
     */
    public Map<String, Spec> riskWeights() { return riskWeights; }
    /**
     * 期限桶。
     * @return 期限桶
     */
    public List<String> buckets() { return buckets; }
    /**
     * 无到期日豁免科目。
     * @return 无到期日豁免科目
     */
    public List<String> nonMaturing() { return nonMaturing; }
    /**
     * 无到期日逐科目理由。
     * @return 无到期日逐科目理由
     */
    public Map<String, String> nonMaturingReasons() { return nonMaturingReasons; }
    /**
     * 各科目期限分布。
     * @return 各科目期限分布
     */
    public Map<String, Map<String, BigDecimal>> maturity() { return maturity; }
    /**
     * 存款流动性分类。
     * @return 存款流动性分类
     */
    public Map<String, String> lcrClass() { return lcrClass; }
    /**
     * 借款人→集团。
     * @return 借款人→集团
     */
    public Map<String, String> groupMap() { return groupMap; }
    /**
     * 集团排除逐主体理由。
     * @return 集团排除逐主体理由
     */
    public Map<String, String> groupExclusionReasons() { return groupExclusionReasons; }
    /**
     * 阈值。
     * @return 阈值
     */
    public Map<String, BigDecimal> thresholds() { return thresholds; }
    /**
     * 执行 profile 标识。
     * @return 执行 profile 标识
     */
    public String enforcementProfile() { return enforcementProfile; }
    /**
     * 动作→blocking/advisory。
     * @return 动作→blocking/advisory
     */
    public Map<String, String> enforcement() { return enforcement; }
    /**
     * 可接受的 N/A 白名单。
     * @return 可接受的 N/A 白名单
     */
    public Map<String, List<String>> acceptedNa() { return acceptedNa; }
    /**
     * 声明 AT1。
     * @return 声明 AT1
     */
    public BigDecimal at1() { return at1; }
    /**
     * 利息条款 profile。
     * @return 利息条款 profile
     */
    public Interest interest() { return interest; }
    /**
     * 是否启用完整利息条款（新开立必须带 rate 与 tenorMs）。
     * @return 是否启用
     */
    public boolean interestEnabled() { return interest.enabled(); }
    /**
     * 该行冻结的存款 offer（可能为 null=旧档案不提供）。
     *
     * @param bank 银行
     * @return offer
     */
    public DepositOffer depositOffer(String bank) { return depositOffers.get(bank); }
    /**
     * 全部冻结存款 offer（只读）。
     *
     * @return 银行→offer
     */
    public Map<String, DepositOffer> depositOffers() { return depositOffers; }
    /**
     * B3/B4 条款声明。
     *
     * @return 条款声明（NONE 表示旧档案）
     */
    public FacilityTerms facilityTerms() { return facilityTerms; }
    /**
     * 是否启用 B3/B4 设施条款。
     *
     * @return 启用返回 true
     */
    public boolean facilityEnabled() { return facilityTerms.enabled(); }
    /**
     * 旧汇总承诺来源归属声明。
     *
     * @return SEPARATE_DECLARED 或 DISABLED
     */
    public String legacyCommitmentAttribution() { return legacyCommitmentAttribution; }
    /**
     * 是否允许旧汇总承诺来源。
     *
     * @return 允许返回 true
     */
    public boolean legacyCommitmentEnabled() {
        return !BankRules.LEGACY_DISABLED.equals(legacyCommitmentAttribution);
    }
    /**
     * 覆盖规则标识。
     * @return 覆盖规则标识
     */
    public String coverageRule() { return coverageRule; }
    /**
     * 来源线索。
     * @return 来源线索
     */
    public List<Clue> referenceClues() { return referenceClues; }
    /**
     * 来源状态。
     * @return 来源状态
     */
    public String provenanceStatus() { return provenanceStatus; }

    /**
     * 冻结档案。
     *
     * @return 档案
     */
    public BankingProfile profile() { return profile; }

    /**
     * 实例账表。
     *
     * @return 账表
     */
    public BankChart chart() { return chart; }

    /**
     * 规范化指纹：所有影响行为的字段都以精确文本参与。
     *
     * @return SHA-256 十六进制
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(archiveId).append("|v=").append(version)
                .append("|label=").append(label).append('\n');
        sb.append("required=").append(String.join(",", required)).append('\n');
        sb.append("implemented=").append(String.join(",", implemented)).append('\n');
        sb.append("omitted=").append(String.join(",", omitted)).append('\n');
        sb.append("ccf=").append(specText(ccf)).append('\n');
        sb.append("commitment=").append(specText(commitmentWeight)).append('\n');
        sb.append("runoff=").append(specText(runoffDemand)).append('\n');
        sb.append("hqla=").append(String.join(",", hqla)).append('\n');
        new TreeMap<>(riskWeights).forEach((k, v) ->
                sb.append("rw.").append(k).append('=').append(specText(v)).append('\n'));
        sb.append("buckets=").append(String.join(",", buckets)).append('\n');
        sb.append("nonMaturing=").append(String.join(",", new TreeSet<>(nonMaturing)))
                .append('\n');
        new TreeMap<>(nonMaturingReasons).forEach((k, v) ->
                sb.append("nm.").append(k).append('=').append(v).append('\n'));
        new TreeMap<>(maturity).forEach((k, v) ->
                sb.append("mat.").append(k).append('=')
                        .append(new TreeMap<>(v)).append('\n'));
        new TreeMap<>(lcrClass).forEach((k, v) ->
                sb.append("lcr.").append(k).append('=').append(v).append('\n'));
        new TreeMap<>(groupMap).forEach((k, v) ->
                sb.append("grp.").append(k).append('=').append(v).append('\n'));
        new TreeMap<>(groupExclusionReasons).forEach((k, v) ->
                sb.append("grpx.").append(k).append('=').append(v).append('\n'));
        new TreeMap<>(thresholds).forEach((k, v) ->
                sb.append("th.").append(k).append('=').append(v.toPlainString()).append('\n'));
        sb.append("enfProfile=").append(enforcementProfile).append('\n');
        new TreeMap<>(enforcement).forEach((k, v) ->
                sb.append("enf.").append(k).append('=').append(v).append('\n'));
        new TreeMap<>(acceptedNa).forEach((k, v) ->
                sb.append("na.").append(k).append('=').append(String.join(",", v)).append('\n'));
        sb.append("at1=").append(at1.toPlainString()).append('\n');
        sb.append("interest=").append(interest.profile()).append('/')
                .append(interest.rateExponent()).append('/')
                .append(interest.dayBasisDays()).append('\n');
        new TreeMap<>(depositOffers).forEach((bank, offer) ->
                sb.append("offer.").append(bank).append('=').append(offer.offerId()).append('/')
                        .append(offer.rateU()).append('/').append(offer.tenorMs()).append('/')
                        .append(offer.creditRule()).append('\n'));
        sb.append("facility=").append(facilityTerms.profile()).append('/')
                .append(facilityTerms.resolutionAuthority() == null ? "-"
                        : facilityTerms.resolutionAuthority()).append('\n');
        for (CollateralDecl decl : facilityTerms.collaterals()) {
            sb.append("col.").append(decl.collateralId()).append('=')
                    .append(decl.ownerBank()).append('/').append(decl.kind()).append('/')
                    .append(decl.assetKey()).append('/')
                    .append(decl.appraisedValue().toPlainString()).append('/')
                    .append(decl.haircutU()).append('\n');
        }
        int tierIndex = 0;
        for (List<String> tier : facilityTerms.claimTiers()) {
            sb.append("tier.").append(tierIndex++).append('=')
                    .append(String.join(",", tier)).append('\n');
        }
        sb.append("legacyCommit=").append(legacyCommitmentAttribution).append('\n');
        sb.append("coverage=").append(coverageRule).append('\n');
        sb.append("prov=").append(provenanceStatus).append('\n');
        sb.append("profile=").append(profile.canonical()).append('\n');
        for (Clue clue : referenceClues) {
            sb.append("clue=").append(clue.name()).append('|').append(clue.url())
                    .append('|').append(clue.doc()).append('\n');
        }
        return sha256(sb.toString());
    }

    private static String specText(Spec spec) {
        return spec.value().toPlainString() + "/"
                + (spec.min() == null ? "-" : spec.min().toPlainString()) + "/"
                + (spec.max() == null ? "-" : spec.max().toPlainString()) + "/"
                + spec.allowZero();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("缺少 SHA-256", failure);
        }
    }

    /**
     * EXP-1 默认档案。
     * @return EXP-1 默认档案
     */
    public static BankConfig experimentalExp1() {
        return new Builder().build();
    }

    /**
     * EXP-2 完整实验档：ACT/365 利息 profile + 每行一份冻结存款 offer（2%/365 天，银行产生的存款明确非应计）。
     * 阈值、权重与旧档相同，数值仍是实验参数，不是监管校准。
     *
     * @return EXP-2 档案
     */
    public static BankConfig experimentalExp2() {
        Map<String, DepositOffer> offers = new LinkedHashMap<>();
        for (String bank : BankChart.BANKS) {
            offers.put(bank, new DepositOffer(bank, "DEP-EXP-2", InterestAccrual.RATE_ONE / 50,
                    365L * InterestAccrual.DAY_MS, BankRules.CREDIT_NON_ACCRUAL));
        }
        return new Builder().version(8).interest(Interest.act365()).depositOffers(offers).build();
    }

    /**
     * EXP-3 档案：EXP-2 + B3/B4 条款（设施、押品估值簿、独立处置权、债权阶梯）。
     *
     * <p>押品估值与折扣是实验声明，随指纹冻结；命令不能自报价值。数值不是市场校准。</p>
     *
     * @return EXP-3 档案
     */
    public static BankConfig experimentalExp3() {
        Map<String, DepositOffer> offers = new LinkedHashMap<>();
        for (String bank : BankChart.BANKS) {
            offers.put(bank, new DepositOffer(bank, "DEP-EXP-2", InterestAccrual.RATE_ONE / 50,
                    365L * InterestAccrual.DAY_MS, BankRules.CREDIT_NON_ACCRUAL));
        }
        List<CollateralDecl> collaterals = List.of(
                new CollateralDecl("COL-A-PREMISES-1", "BANK-A", "PREMISES", "BANK-A|PREMISES",
                        new BigDecimal("10000.00"), 300_000L),
                new CollateralDecl("COL-A-LOAN-1", "BANK-A", "LOAN", "BANK-A|AGG",
                        new BigDecimal("500.00"), 400_000L));
        List<List<String>> tiers = List.of(
                List.of("DEPOSIT.*", "INT.PAYABLE"),
                List.of(BankChart.FACILITY_DEBT, BankChart.FACILITY_INTEREST_PAYABLE),
                List.of("SUBT2"));
        return new Builder().version(9).interest(Interest.act365()).depositOffers(offers)
                .facilityTerms(new FacilityTerms(BankRules.FACILITY_EXP,
                        BankRules.RESOLUTION_AUTHORITY, collaterals, tiers))
                .build();
    }

    /** 可重建的构造器：所有集合在 build 时深拷贝并冻结。 */
    public static final class Builder {
        private String archiveId = "BANK-EXP-1";
        private int version = 7;
        private String label = "实验规则（非监管合规）";
        private List<String> required = new ArrayList<>(BankRules.CORE_REQUIRED);
        private List<String> implemented = new ArrayList<>(BankRules.CORE_REQUIRED);
        private List<String> omitted = new ArrayList<>(List.of("NSFR", "日间流动性", "行业集中度"));
        private Spec ccf = new Spec(new BigDecimal("0.40"), BigDecimal.ZERO,
                BigDecimal.ONE, true);
        private Spec commitmentWeight = new Spec(BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ONE, false);
        private Spec runoffDemand = new Spec(new BigDecimal("0.30"), BigDecimal.ZERO,
                BigDecimal.ONE, false);
        private List<String> hqla = new ArrayList<>(List.of("RESERVE"));
        private Map<String, Spec> riskWeights = new LinkedHashMap<>();
        private List<String> buckets = new ArrayList<>(List.of("1D", "30D", "90D", "1Y+"));
        private List<String> nonMaturing = new ArrayList<>(BankRules.NON_MATURING_KIND.keySet());
        private Map<String, String> nonMaturingReasons = new LinkedHashMap<>();
        private Map<String, Map<String, BigDecimal>> maturity = new LinkedHashMap<>();
        private Map<String, String> lcrClass = new LinkedHashMap<>();
        private Map<String, String> groupMap = new LinkedHashMap<>();
        private Map<String, String> groupExclusionReasons = new LinkedHashMap<>();
        private Map<String, BigDecimal> thresholds = new LinkedHashMap<>();
        private String enforcementProfile = "EXP-1";
        private Map<String, String> enforcement = new LinkedHashMap<>();
        private Map<String, List<String>> acceptedNa = new LinkedHashMap<>();
        private BigDecimal at1 = BigDecimal.ZERO;
        private Interest interest = Interest.none();
        private Map<String, DepositOffer> depositOffers = new LinkedHashMap<>();
        private FacilityTerms facilityTerms = FacilityTerms.none();
        private String legacyCommitmentAttribution = BankRules.LEGACY_SEPARATE;
        private String coverageRule = "DECLARED_UNIVERSE_V1";
        private List<Clue> referenceClues = new ArrayList<>();
        private String provenanceStatus = "names_and_links_only_unverified";
        private BankingProfile profile = BankingProfile.reference();

        /** 默认构造器：写入 EXP-1 权重、期限、分类、集团与阈值。 */
        public Builder() {
            riskWeights.put("RESERVE", new Spec(BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ONE, true));
            riskWeights.put("PREMISES", new Spec(BigDecimal.ONE, BigDecimal.ZERO,
                    BigDecimal.ONE, false));
            for (String loan : List.of("LOAN.BG", "LOAN.C", "LOAN.D", "LOAN.AGG", "LOAN.E")) {
                riskWeights.put(loan, new Spec(BigDecimal.ONE, BigDecimal.ZERO,
                        BigDecimal.ONE, false));
            }
            riskWeights.put(BankChart.INTEREST_RECEIVABLE, new Spec(BigDecimal.ONE,
                    BigDecimal.ZERO, BigDecimal.ONE, false));
            nonMaturing.forEach(name ->
                    nonMaturingReasons.put(name, BankRules.NON_MATURING_REASON.get(name)));
            maturity.put("RESERVE", Map.of("1D", BigDecimal.ONE));
            maturity.put("LOAN.BG", Map.of("90D", new BigDecimal("0.05"),
                    "1Y+", new BigDecimal("0.95")));
            maturity.put("LOAN.C", Map.of("90D", new BigDecimal("0.50"),
                    "1Y+", new BigDecimal("0.50")));
            maturity.put("LOAN.D", Map.of("1Y+", BigDecimal.ONE));
            maturity.put("LOAN.AGG", Map.of("1Y+", BigDecimal.ONE));
            maturity.put("LOAN.E", Map.of("1Y+", BigDecimal.ONE));
            maturity.put(BankChart.INTEREST_RECEIVABLE, Map.of("30D", BigDecimal.ONE));
            maturity.put(BankChart.INTEREST_PAYABLE, Map.of("1D", BigDecimal.ONE));
            maturity.put("DEPOSIT.BG", Map.of("1D", new BigDecimal("0.20"),
                    "30D", new BigDecimal("0.20"), "1Y+", new BigDecimal("0.60")));
            lcrClass.put("DEPOSIT.BG", "DEMAND");
            for (String demand : List.of("DEPOSIT.X", "DEPOSIT.Y", "DEPOSIT.C", "DEPOSIT.D",
                    "DEPOSIT.E", "DEPOSIT.ALICE", "DEPOSIT.AGG", "DEPOSIT.SUP",
                    "DEPOSIT.BIG", "DEPOSIT.PAYER")) {
                maturity.put(demand, Map.of("1D", BigDecimal.ONE));
                lcrClass.put(demand, "DEMAND");
            }
            lcrClass.put("BORROW.FAC", "TERM30");
            maturity.put("BORROW.FAC", Map.of("30D", BigDecimal.ONE));
            lcrClass.put(BankChart.FACILITY_INTEREST_PAYABLE, "TERM30");
            maturity.put(BankChart.FACILITY_INTEREST_PAYABLE, Map.of("30D", BigDecimal.ONE));
            groupMap.put("CO-C", "G1");
            groupMap.put("CO-D", "G1");
            groupMap.put("CO-E", "G2");
            groupMap.put("AGG", "@BACKGROUND_EXCLUDED");
            groupExclusionReasons.put("AGG", BankRules.BACKGROUND_MEMBERS.get("AGG"));
            thresholds.put("cet1_min", new BigDecimal("0.045"));
            thresholds.put("cet1_buffer", new BigDecimal("0.025"));
            thresholds.put("t1_min", new BigDecimal("0.06"));
            thresholds.put("tcr_min", new BigDecimal("0.08"));
            thresholds.put("lev_min", new BigDecimal("0.03"));
            thresholds.put("single_name", new BigDecimal("0.10"));
            thresholds.put("group_name", new BigDecimal("0.15"));
            thresholds.put("lcr_min", BigDecimal.ONE);
            thresholds.put("maturity_gap_min", new BigDecimal("-0.25"));
            thresholds.put("impair_cover_min", new BigDecimal("0.02"));
            ALL_ACTIONS.forEach(action ->
                    enforcement.put(action, BankRules.FROZEN_ENFORCEMENT.get(action)));
            acceptedNa.put("OFFBS", List.of("NA_NO_OFFBALANCE"));
            acceptedNa.put("IMPAIR_COVER", List.of("NA_NO_LOANS"));
            acceptedNa.put("MATURITY_GAP", List.of("NA_NO_LIABILITIES"));
            acceptedNa.put("LCR_EXP", List.of("NA_NO_OUTFLOW"));
            referenceClues.add(new Clue("BoE money creation",
                    "https://www.bankofengland.co.uk/explainers/how-is-money-created",
                    "docs/research/2026-11-05-虚拟银行约束与详情工作区设计.md"));
            referenceClues.add(new Clue("IFRS 9",
                    "https://www.ifrs.org/content/dam/ifrs/publications/pdf-standards/english/2022/issued/part-a/ifrs-9-financial-instruments.pdf",
                    "docs/research/2026-11-05-虚拟银行约束与详情工作区设计.md"));
        }

        /**
         * 按档案构造：账表相关默认值全部由档案派生，避免固定参考科目混入。
         *
         * @param profile 冻结档案
         */
        public Builder(BankingProfile profile) {
            this();
            if (profile == BankingProfile.reference()) {
                return;
            }
            this.profile = profile;
            BankChart chart = BankChart.of(profile);
            riskWeights.clear();
            riskWeights.put(BankChart.RESERVE, new Spec(BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ONE, true));
            riskWeights.put("PREMISES", new Spec(BigDecimal.ONE, BigDecimal.ZERO,
                    BigDecimal.ONE, false));
            for (String loan : profile.loanAccounts()) {
                riskWeights.put(loan, new Spec(BigDecimal.ONE, BigDecimal.ZERO,
                        BigDecimal.ONE, false));
            }
            riskWeights.put(BankChart.INTEREST_RECEIVABLE, new Spec(BigDecimal.ONE,
                    BigDecimal.ZERO, BigDecimal.ONE, false));
            maturity.clear();
            lcrClass.clear();
            groupMap.clear();
            groupExclusionReasons.clear();
            nonMaturing.clear();
            nonMaturingReasons.clear();
            maturity.put(BankChart.RESERVE, Map.of("1D", BigDecimal.ONE));
            for (String loan : profile.loanAccounts()) {
                maturity.put(loan, Map.of("1Y+", BigDecimal.ONE));
            }
            for (String deposit : profile.depositAccounts()) {
                maturity.put(deposit, Map.of("1D", BigDecimal.ONE));
                lcrClass.put(deposit, "DEMAND");
            }
            maturity.put(BankChart.INTEREST_RECEIVABLE, Map.of("30D", BigDecimal.ONE));
            maturity.put(BankChart.INTEREST_PAYABLE, Map.of("1D", BigDecimal.ONE));
            maturity.put(BankChart.FACILITY_DEBT, Map.of("30D", BigDecimal.ONE));
            lcrClass.put(BankChart.FACILITY_DEBT, "TERM30");
            maturity.put(BankChart.FACILITY_INTEREST_PAYABLE, Map.of("30D", BigDecimal.ONE));
            lcrClass.put(BankChart.FACILITY_INTEREST_PAYABLE, "TERM30");
            nonMaturing.add("PREMISES");
            nonMaturingReasons.put("PREMISES", "real_asset_no_contractual_maturity");
            for (String llp : profile.llpAccounts()) {
                nonMaturing.add(llp);
                nonMaturingReasons.put(llp, "valuation_contra_follows_loan");
            }
            for (String equity : List.of("PAIDIN", "RETAINED", "AT1")) {
                nonMaturing.add(equity);
                nonMaturingReasons.put(equity, "equity_no_maturity");
            }
            nonMaturing.add("SUBT2");
            nonMaturingReasons.put("SUBT2", "subordinated_debt_without_modelled_maturity_gap");
            for (Map.Entry<String, BankingProfile.RiskBucket> entry
                    : profile.buckets().entrySet()) {
                if (entry.getValue().group() != null) {
                    groupMap.put(entry.getKey(), entry.getValue().group());
                }
            }
            if (chart.backgroundBorrowerKey() != null) {
                groupMap.put(chart.backgroundBorrowerKey(), "@BACKGROUND_EXCLUDED");
                groupExclusionReasons.put(chart.backgroundBorrowerKey(),
                        "aggregate_background_book_no_single_name");
            }
        }

        /**
         * 绑定档案；账表相关默认值需在新实例上重建，故建议使用 {@link #Builder(BankingProfile)}。
         *
         * @param value 档案
         * @return 本构造器
         */
        public Builder profile(BankingProfile value) {
            profile = value;
            return this;
        }

        /**

     * 版本。
         * @param value 版本
         * @return 本构造器

     */
        public Builder version(int value) { version = value; return this; }

        /**

     * 必须指标。
         * @param value 必须指标
         * @return 本构造器

     */
        public Builder required(List<String> value) { required = new ArrayList<>(value); return this; }

        /**

     * 已实现指标。
         * @param value 已实现指标
         * @return 本构造器

     */
        public Builder implemented(List<String> value) { implemented = new ArrayList<>(value); return this; }

        /**

     * CCF。
         * @param value CCF
         * @return 本构造器

     */
        public Builder ccf(Spec value) { ccf = value; return this; }

        /**

     * 风险权重。
         * @param value 风险权重
         * @return 本构造器

     */
        public Builder riskWeights(Map<String, Spec> value) { riskWeights = new LinkedHashMap<>(value); return this; }

        /**

     * 期限分布。
         * @param value 期限分布
         * @return 本构造器

     */
        public Builder maturity(Map<String, Map<String, BigDecimal>> value) { maturity = new LinkedHashMap<>(value); return this; }

        /**

     * 期限桶。
         * @param value 期限桶
         * @return 本构造器

     */
        public Builder buckets(List<String> value) { buckets = new ArrayList<>(value); return this; }

        /**

     * 无到期豁免。
         * @param value 无到期豁免
         * @return 本构造器

     */
        public Builder nonMaturing(List<String> value) { nonMaturing = new ArrayList<>(value); return this; }

        /**

     * 豁免理由。
         * @param value 豁免理由
         * @return 本构造器

     */
        public Builder nonMaturingReasons(Map<String, String> value) { nonMaturingReasons = new LinkedHashMap<>(value); return this; }

        /**

     * 流水分类。
         * @param value 流水分类
         * @return 本构造器

     */
        public Builder lcrClass(Map<String, String> value) { lcrClass = new LinkedHashMap<>(value); return this; }

        /**

     * 集团映射。
         * @param value 集团映射
         * @return 本构造器

     */
        public Builder groupMap(Map<String, String> value) { groupMap = new LinkedHashMap<>(value); return this; }

        /**

     * 集团排除理由。
         * @param value 集团排除理由
         * @return 本构造器

     */
        public Builder groupExclusionReasons(Map<String, String> value) { groupExclusionReasons = new LinkedHashMap<>(value); return this; }

        /**

     * 阈值。
         * @param value 阈值
         * @return 本构造器

     */
        public Builder thresholds(Map<String, BigDecimal> value) { thresholds = new LinkedHashMap<>(value); return this; }

        /**

     * 执行分级。
         * @param value 执行分级
         * @return 本构造器

     */
        public Builder enforcement(Map<String, String> value) { enforcement = new LinkedHashMap<>(value); return this; }

        /**

     * 可接受 N/A。
         * @param value 可接受 N/A
         * @return 本构造器

     */
        public Builder acceptedNa(Map<String, List<String>> value) { acceptedNa = new LinkedHashMap<>(value); return this; }

        /**

     * HQLA 科目。
         * @param value HQLA 科目
         * @return 本构造器

     */
        public Builder hqla(List<String> value) { hqla = new ArrayList<>(value); return this; }

        /**

     * 声明 AT1。
         * @param value 声明 AT1
         * @return 本构造器

     */
        public Builder at1(BigDecimal value) { at1 = value; return this; }

        /**

     * 利息条款。
         * @param value 利息条款 profile
         * @return 本构造器

     */
        public Builder interest(Interest value) { interest = value; return this; }

        /**
         * 存款 offer 构造器。
         * @param value 银行→offer
         * @return 本构造器
         */
        public Builder depositOffers(Map<String, DepositOffer> value) {
            depositOffers = new LinkedHashMap<>(value);
            return this;
        }

        /**
         * B3/B4 条款声明。
         *
         * @param value 条款声明
         * @return 本构造器
         */
        public Builder facilityTerms(FacilityTerms value) {
            facilityTerms = value;
            return this;
        }

        /**

     * 旧汇总承诺来源归属。
         * @param value SEPARATE_DECLARED 或 DISABLED
         * @return 本构造器

     */
        public Builder legacyCommitmentAttribution(String value) {
            legacyCommitmentAttribution = value;
            return this;
        }

        /**

     * profile 标识。
         * @param value profile 标识
         * @return 本构造器

     */
        public Builder enforcementProfile(String value) { enforcementProfile = value; return this; }

        /**

     * 省略项。
         * @param value 省略项
         * @return 本构造器

     */
        public Builder omitted(List<String> value) { omitted = new ArrayList<>(value); return this; }

        /**

     * 承诺权重。
         * @param value 承诺权重
         * @return 本构造器

     */
        public Builder commitmentWeight(Spec value) { commitmentWeight = value; return this; }

        /**

     * 流失率。
         * @param value 流失率
         * @return 本构造器

     */
        public Builder runoffDemand(Spec value) { runoffDemand = value; return this; }

        /**

     * 来源状态。
         * @param value 来源状态
         * @return 本构造器

     */
        public Builder provenanceStatus(String value) { provenanceStatus = value; return this; }

        /**
         * 档案标识。
         *
         * @param value 档案标识
         * @return 本构造器
         */
        public Builder archiveId(String value) { archiveId = value; return this; }

        /**
         * 档案标签。
         *
         * @param value 标签
         * @return 本构造器
         */
        public Builder label(String value) { label = value; return this; }

        /**
         * 覆盖规则标识。
         *
         * @param value 覆盖规则
         * @return 本构造器
         */
        public Builder coverageRule(String value) { coverageRule = value; return this; }

        /**
         * 来源线索。
         *
         * @param value 线索列表
         * @return 本构造器
         */
        public Builder referenceClues(List<Clue> value) {
            referenceClues = new ArrayList<>(value);
            return this;
        }

        /**
         * 不可变档案。
         * @return 不可变档案
         */
        public BankConfig build() {
            return new BankConfig(this);
        }
    }
}
