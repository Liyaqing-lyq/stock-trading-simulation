package edu.cufe.auction.world.bank;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * BankConfig 的完整 Jackson 快照：所有影响指纹与行为的字段都显式保存。
 *
 * <p>本快照可作为可信配置的 JSON 载体；恢复 BankState 时仍以调用方提供的
 * {@link BankConfig}（或其快照还原结果）为信任根，不读取 state 自带指纹作授权。</p>
 *
 * @param archiveId 档案标识
 * @param version 版本
 * @param label 标签
 * @param required 必须指标
 * @param implemented 已实现指标
 * @param omitted 省略项
 * @param ccf CCF
 * @param commitmentWeight 承诺权重
 * @param runoffDemand 存款流失率
 * @param hqla HQLA
 * @param riskWeights 风险权重
 * @param buckets 期限桶
 * @param nonMaturing 无到期科目
 * @param nonMaturingReasons 无到期理由
 * @param maturity 期限分布
 * @param lcrClass LCR 分类
 * @param groupMap 集团映射
 * @param groupExclusionReasons 集团排除理由
 * @param thresholds 阈值
 * @param enforcementProfile 执行档
 * @param enforcement 执行动作
 * @param acceptedNa 可接受 N/A
 * @param at1 AT1
 * @param interest 利息条款
 * @param depositOffers 存款 offer
 * @param facilityTerms 设施条款
 * @param legacyCommitmentAttribution 旧承诺归属
 * @param coverageRule 覆盖规则
 * @param referenceClues 来源线索
 * @param provenanceStatus 来源状态
 * @param profile 冻结档案
 */
public record BankConfigSnapshot(
        String archiveId,
        int version,
        String label,
        List<String> required,
        List<String> implemented,
        List<String> omitted,
        BankConfig.Spec ccf,
        BankConfig.Spec commitmentWeight,
        BankConfig.Spec runoffDemand,
        List<String> hqla,
        Map<String, BankConfig.Spec> riskWeights,
        List<String> buckets,
        List<String> nonMaturing,
        Map<String, String> nonMaturingReasons,
        Map<String, Map<String, BigDecimal>> maturity,
        Map<String, String> lcrClass,
        Map<String, String> groupMap,
        Map<String, String> groupExclusionReasons,
        Map<String, BigDecimal> thresholds,
        String enforcementProfile,
        Map<String, String> enforcement,
        Map<String, List<String>> acceptedNa,
        BigDecimal at1,
        BankConfig.Interest interest,
        Map<String, BankConfig.DepositOffer> depositOffers,
        BankConfig.FacilityTerms facilityTerms,
        String legacyCommitmentAttribution,
        String coverageRule,
        List<BankConfig.Clue> referenceClues,
        String provenanceStatus,
        BankingProfile profile) {

    /** 校验非空并做确定性复制。 */
    public BankConfigSnapshot {
        Objects.requireNonNull(archiveId, "archiveId");
        Objects.requireNonNull(label, "label");
        required = List.copyOf(Objects.requireNonNull(required, "required"));
        implemented = List.copyOf(Objects.requireNonNull(implemented, "implemented"));
        omitted = List.copyOf(Objects.requireNonNull(omitted, "omitted"));
        Objects.requireNonNull(ccf, "ccf");
        Objects.requireNonNull(commitmentWeight, "commitmentWeight");
        Objects.requireNonNull(runoffDemand, "runoffDemand");
        hqla = List.copyOf(Objects.requireNonNull(hqla, "hqla"));
        riskWeights = sorted(riskWeights);
        buckets = List.copyOf(Objects.requireNonNull(buckets, "buckets"));
        nonMaturing = List.copyOf(Objects.requireNonNull(nonMaturing, "nonMaturing"));
        nonMaturingReasons = sorted(nonMaturingReasons);
        Map<String, Map<String, BigDecimal>> mat = new TreeMap<>();
        Objects.requireNonNull(maturity, "maturity").forEach((key, value) ->
                mat.put(key, Collections.unmodifiableMap(new TreeMap<>(value))));
        maturity = Collections.unmodifiableMap(mat);
        lcrClass = sorted(lcrClass);
        groupMap = sorted(groupMap);
        groupExclusionReasons = sorted(groupExclusionReasons);
        thresholds = sorted(thresholds);
        Objects.requireNonNull(enforcementProfile, "enforcementProfile");
        enforcement = sorted(enforcement);
        Map<String, List<String>> na = new TreeMap<>();
        Objects.requireNonNull(acceptedNa, "acceptedNa").forEach((key, value) ->
                na.put(key, List.copyOf(value)));
        acceptedNa = Collections.unmodifiableMap(na);
        Objects.requireNonNull(at1, "at1");
        Objects.requireNonNull(interest, "interest");
        depositOffers = sorted(depositOffers);
        Objects.requireNonNull(facilityTerms, "facilityTerms");
        Objects.requireNonNull(legacyCommitmentAttribution, "legacyCommitmentAttribution");
        Objects.requireNonNull(coverageRule, "coverageRule");
        referenceClues = List.copyOf(Objects.requireNonNull(referenceClues, "referenceClues"));
        Objects.requireNonNull(provenanceStatus, "provenanceStatus");
        Objects.requireNonNull(profile, "profile");
    }

    /**
     * 从冻结档案生成快照。
     *
     * @param config 档案
     * @return 快照
     */
    public static BankConfigSnapshot of(BankConfig config) {
        Objects.requireNonNull(config, "config");
        return new BankConfigSnapshot(config.archiveId(), config.version(), config.label(),
                config.required(), config.implemented(), config.omitted(), config.ccf(),
                config.commitmentWeight(), config.runoffDemand(), config.hqla(),
                config.riskWeights(), config.buckets(), config.nonMaturing(),
                config.nonMaturingReasons(), config.maturity(), config.lcrClass(),
                config.groupMap(), config.groupExclusionReasons(), config.thresholds(),
                config.enforcementProfile(), config.enforcement(), config.acceptedNa(),
                config.at1(), config.interest(), config.depositOffers(), config.facilityTerms(),
                config.legacyCommitmentAttribution(), config.coverageRule(),
                config.referenceClues(), config.provenanceStatus(), config.profile());
    }

    /**
     * 还原不可变档案；调用方仍需自行决定是否信任该 JSON。
     *
     * @return 档案
     */
    public BankConfig toConfig() {
        return new BankConfig.Builder()
                .archiveId(archiveId)
                .version(version)
                .label(label)
                .required(required)
                .implemented(implemented)
                .omitted(omitted)
                .ccf(ccf)
                .commitmentWeight(commitmentWeight)
                .runoffDemand(runoffDemand)
                .hqla(hqla)
                .riskWeights(riskWeights)
                .buckets(buckets)
                .nonMaturing(nonMaturing)
                .nonMaturingReasons(nonMaturingReasons)
                .maturity(maturity)
                .lcrClass(lcrClass)
                .groupMap(groupMap)
                .groupExclusionReasons(groupExclusionReasons)
                .thresholds(thresholds)
                .enforcementProfile(enforcementProfile)
                .enforcement(enforcement)
                .acceptedNa(acceptedNa)
                .at1(at1)
                .interest(interest)
                .depositOffers(depositOffers)
                .facilityTerms(facilityTerms)
                .legacyCommitmentAttribution(legacyCommitmentAttribution)
                .coverageRule(coverageRule)
                .referenceClues(new ArrayList<>(referenceClues))
                .provenanceStatus(provenanceStatus)
                .profile(profile)
                .build();
    }

    private static <K, V> Map<K, V> sorted(Map<K, V> source) {
        Objects.requireNonNull(source, "map");
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }
}
