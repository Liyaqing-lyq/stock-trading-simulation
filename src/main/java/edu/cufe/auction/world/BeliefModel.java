package edu.cufe.auction.world;

import edu.cufe.auction.world.InformationModel.ReadObservation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * M4g.1 信念、来源可靠性与慢习惯纯核。
 *
 * <p>本类只读取真实 {@link InformationModel.State}、真实 {@link CognitionRecall} 结果与本类状态；
 * 不读余额、利润、股价、墙钟或未来账本，不写日志，不联网，不修改任何输入状态。所有时刻都是调用方
 * 显式给出的模拟毫秒。</p>
 *
 * <p><b>可信边界</b>：结构化声明表 {@link Claims} 由可信适配层提供（后续运行桥负责把它持久绑定到
 * 真实文档与公司账）。本类只核对“哪个发布者在哪个已发布版本上声明了什么、在什么时点”，
 * <b>不证明</b>账本数值本身。自由正文永远不会被解析成声明。</p>
 */
public final class BeliefModel {

    /** 首批唯一支持的预测指标。 */
    public static final String METRIC_ORDERS_CONFIRMED = "ORDERS_CONFIRMED";
    /** 单条判断最多证据数。 */
    public static final int MAX_EVIDENCE = 3;
    /** 判断自述上限（UTF-16 字符）。 */
    public static final int MAX_RATIONALE_CHARS = 500;
    /** 习惯最高等级。 */
    public static final int MAX_HABIT_LEVEL = 3;
    /** 中性可靠性与中性置信。 */
    public static final int NEUTRAL_PERMILLE = 500;

    /** 形成成功。 */
    public static final String REASON_OK = "OK";
    /** 同 beliefId 同形成内容，返回原状态。 */
    public static final String REASON_DUPLICATE = "DUPLICATE";
    /** 同 beliefId 不同形成内容。 */
    public static final String REASON_ID_CONFLICT = "ID_CONFLICT";
    /** 证据不真实、不属于本人、序号不符或未读完。 */
    public static final String REASON_EVIDENCE = "EVIDENCE";
    /** 证据不在当前回忆结果中。 */
    public static final String REASON_NOT_RECALLABLE = "NOT_RECALLABLE";
    /** 目标时点不在未来或超过上限。 */
    public static final String REASON_TARGET = "TARGET";
    /** 采纳声明不存在、字段不一致、发布者不符或未读。 */
    public static final String REASON_ADOPTION = "ADOPTION";
    /** 同人同目标/比较/阈值的重复自拟判断。 */
    public static final String REASON_DUPLICATE_PREDICTION = "DUPLICATE_PREDICTION";
    /** 达到本人容量上限。 */
    public static final String REASON_LIMIT = "LIMIT";

    /** 比较方向。 */
    public enum Comparator {
        /** 实际值不低于阈值。 */
        AT_LEAST,
        /** 实际值不高于阈值。 */
        AT_MOST
    }

    /** 评估结论。 */
    public enum Verdict {
        /** 预测成立。 */
        HIT,
        /** 预测落空。 */
        MISS
    }

    /** 慢习惯种类。 */
    public enum HabitKind {
        /** 行动前先核实。 */
        VERIFY_BEFORE_ACT,
        /** 主动寻找第二个公开来源。 */
        SEEK_SECOND_SOURCE
    }

    /**
     * 冻结的实验参数，全部由调用方显式给出，不设现实默认值。
     *
     * @param maxBeliefsPerPerson 每人判断总量上限，1..10000
     * @param maxOpenBeliefs      每人未决判断上限，1..maxBeliefsPerPerson
     * @param maxHorizonMillis    目标时点距形成时刻的上限毫秒，必须为正
     * @param trustStep           单条成熟采纳对来源可靠性的步长，1..1000
     * @param confidenceWeight    来源可靠性对形成置信的最大权重，0..500
     * @param habitThreshold      每条习惯升一级所需支持经历数，2..100
     * @param habitWindowMillis   习惯支持的有效窗口毫秒，必须为正
     */
    public record Policy(long maxBeliefsPerPerson, int maxOpenBeliefs, long maxHorizonMillis,
                         int trustStep, int confidenceWeight, int habitThreshold,
                         long habitWindowMillis) {

        /** 校验全部参数范围。 */
        public Policy {
            if (maxBeliefsPerPerson < 1 || maxBeliefsPerPerson > 10_000) {
                throw new IllegalArgumentException("每人判断上限必须在 1..10000：" + maxBeliefsPerPerson);
            }
            if (maxOpenBeliefs < 1 || maxOpenBeliefs > maxBeliefsPerPerson) {
                throw new IllegalArgumentException("未决判断上限必须在 1.." + maxBeliefsPerPerson
                        + "：" + maxOpenBeliefs);
            }
            if (maxHorizonMillis < 1) {
                throw new IllegalArgumentException("预测期限上限必须为正：" + maxHorizonMillis);
            }
            if (trustStep < 1 || trustStep > 1_000) {
                throw new IllegalArgumentException("信任步长必须在 1..1000：" + trustStep);
            }
            if (confidenceWeight < 0 || confidenceWeight > 500) {
                throw new IllegalArgumentException("置信权重必须在 0..500：" + confidenceWeight);
            }
            if (habitThreshold < 2 || habitThreshold > 100) {
                throw new IllegalArgumentException("习惯阈值必须在 2..100：" + habitThreshold);
            }
            if (habitWindowMillis < 1) {
                throw new IllegalArgumentException("习惯窗口必须为正：" + habitWindowMillis);
            }
        }
    }

    /**
     * 结构化来源预测：某作者在某个已发布版本上公开提出的可核对预测。
     *
     * @param claimId           稳定标识，运行内唯一
     * @param docId             承载该主张的文档
     * @param version           文档版本
     * @param authorPartyId     作者，必须等于该版本发布者
     * @param companyId         目标公司
     * @param metric            指标，首批仅 {@link #METRIC_ORDERS_CONFIRMED}
     * @param comparator        比较方向
     * @param threshold         阈值
     * @param targetAsOfMillis  精确目标时点
     * @param verificationPublisherPartyId 该主张声明的核验发布者，采纳时必须精确匹配
     */
    public record SourceClaim(String claimId, String docId, long version, String authorPartyId,
                              String companyId, String metric, Comparator comparator,
                              long threshold, long targetAsOfMillis,
                              String verificationPublisherPartyId) {

        /** 校验字段。 */
        public SourceClaim {
            requireText(claimId, "来源主张标识");
            requireText(docId, "来源主张文档");
            requireText(authorPartyId, "来源主张作者");
            requireText(companyId, "来源主张公司");
            requireText(metric, "来源主张指标");
            requireText(verificationPublisherPartyId, "来源主张核验发布者");
            Objects.requireNonNull(comparator, "来源主张比较");
            if (version < 1) {
                throw new IllegalArgumentException("来源主张版本必须从 1 开始：" + version);
            }
            if (targetAsOfMillis <= 0) {
                throw new IllegalArgumentException("来源主张目标时点必须为正：" + targetAsOfMillis);
            }
        }
    }

    /**
     * 程序派生的事实声明：某验证发布者在某个已发布版本上给出的、冻结于 asOf 的数值。
     *
     * @param factId           稳定标识，运行内唯一
     * @param docId            承载该事实的文档
     * @param version          文档版本
     * @param companyId        公司
     * @param metric           指标
     * @param asOfMillis       数值对应的事实时点（精确）
     * @param value            事实值
     * @param verifierPartyId  验证发布者，必须等于该版本发布者
     * @param frozenPosition   生成该值时冻结的提交位置（仅审计）
     * @param supersedesFactId 该事实明确更正的旧事实；无更正为空
     */
    public record FactClaim(String factId, String docId, long version, String companyId,
                            String metric, long asOfMillis, long value, String verifierPartyId,
                            long frozenPosition, String supersedesFactId) {

        /** 校验字段。 */
        public FactClaim {
            requireText(factId, "事实标识");
            requireText(docId, "事实文档");
            requireText(companyId, "事实公司");
            requireText(metric, "事实指标");
            requireText(verifierPartyId, "事实验证发布者");
            if (version < 1) {
                throw new IllegalArgumentException("事实版本必须从 1 开始：" + version);
            }
            if (asOfMillis <= 0) {
                throw new IllegalArgumentException("事实时点必须为正：" + asOfMillis);
            }
            if (value < 0) {
                throw new IllegalArgumentException("订单计数事实不能为负：" + value);
            }
            if (frozenPosition < 0) {
                throw new IllegalArgumentException("冻结位置不能为负：" + frozenPosition);
            }
            if (supersedesFactId != null && supersedesFactId.isBlank()) {
                supersedesFactId = null;
            }
        }
    }

    /**
     * 可信适配层提供的结构化声明表：同一 (docId, version) 最多一条声明。
     *
     * @param sourceClaims 来源预测声明
     * @param factClaims   事实声明
     */
    public record Claims(List<SourceClaim> sourceClaims, List<FactClaim> factClaims) {

        /** 深拷贝并校验标识唯一与 doc/version 单一。 */
        public Claims {
            List<SourceClaim> sources = sourceClaims == null ? List.of() : List.copyOf(sourceClaims);
            List<FactClaim> facts = factClaims == null ? List.of() : List.copyOf(factClaims);
            Set<String> ids = new LinkedHashSet<>();
            Set<String> versions = new LinkedHashSet<>();
            for (SourceClaim claim : sources) {
                Objects.requireNonNull(claim, "来源主张");
                if (!ids.add("S:" + claim.claimId())) {
                    throw new IllegalArgumentException("来源主张标识重复：" + claim.claimId());
                }
                if (!versions.add(key(claim.docId(), claim.version()))) {
                    throw new IllegalArgumentException("同一版本只能有一条声明："
                            + claim.docId() + "#" + claim.version());
                }
            }
            for (FactClaim fact : facts) {
                Objects.requireNonNull(fact, "事实声明");
                if (!ids.add("F:" + fact.factId())) {
                    throw new IllegalArgumentException("事实标识重复：" + fact.factId());
                }
                if (!versions.add(key(fact.docId(), fact.version()))) {
                    throw new IllegalArgumentException("同一版本只能有一条声明："
                            + fact.docId() + "#" + fact.version());
                }
            }
            sourceClaims = sources;
            factClaims = facts;
        }

        /** @param claimId 来源主张标识 @return 主张；不存在返回 null */
        public SourceClaim source(String claimId) {
            for (SourceClaim claim : sourceClaims) {
                if (claim.claimId().equals(claimId)) {
                    return claim;
                }
            }
            return null;
        }

        /** @param docId 文档 @param version 版本 @return 该版本的事实声明；不存在返回 null */
        public FactClaim fact(String docId, long version) {
            for (FactClaim fact : factClaims) {
                if (fact.docId().equals(docId) && fact.version() == version) {
                    return fact;
                }
            }
            return null;
        }
    }

    /**
     * 一条主观判断。
     *
     * @param beliefId                     调用方给出的稳定标识
     * @param personPartyId                判断者
     * @param companyId                    目标公司
     * @param metric                       指标
     * @param comparator                   比较方向
     * @param threshold                    阈值
     * @param formedAtMillis               形成时刻
     * @param targetAsOfMillis             精确目标时点
     * @param verificationPublisherPartyId 形成时冻结的验证发布者
     * @param evidence                     本人 scoped 证据引用，1..3 条
     * @param publicSourceGroups           形成时冻结的公开来源组（已去空、去重、排序）
     * @param adoptedClaimId               明确采纳的来源主张；自拟为空
     * @param sourcePartyId                被采纳来源的作者；自拟为空
     * @param confidencePermille           形成时冻结的置信，0..1000
     * @param rationale                    本人自述，可空，≤500 字符
     */
    public record Belief(String beliefId, String personPartyId, String companyId, String metric,
                         Comparator comparator, long threshold, long formedAtMillis,
                         long targetAsOfMillis, String verificationPublisherPartyId,
                         List<RoleView.ReadingRef> evidence, List<String> publicSourceGroups,
                         String adoptedClaimId, String sourcePartyId, int confidencePermille,
                         String rationale) {

        /** 校验字段与冻结集合。 */
        public Belief {
            requireText(beliefId, "判断标识");
            requireText(personPartyId, "判断人物");
            requireText(companyId, "判断公司");
            requireText(metric, "判断指标");
            Objects.requireNonNull(comparator, "判断比较");
            requireText(verificationPublisherPartyId, "验证发布者");
            if (formedAtMillis < 0 || targetAsOfMillis <= formedAtMillis) {
                throw new IllegalArgumentException("判断时点非法：" + formedAtMillis + " -> "
                        + targetAsOfMillis);
            }
            evidence = List.copyOf(Objects.requireNonNull(evidence, "判断证据"));
            if (evidence.isEmpty() || evidence.size() > MAX_EVIDENCE) {
                throw new IllegalArgumentException("判断证据必须在 1.." + MAX_EVIDENCE + "："
                        + evidence.size());
            }
            publicSourceGroups = List.copyOf(Objects.requireNonNull(publicSourceGroups, "公开来源组"));
            if (confidencePermille < 0 || confidencePermille > 1_000) {
                throw new IllegalArgumentException("置信必须在 0..1000：" + confidencePermille);
            }
            if (rationale != null && rationale.length() > MAX_RATIONALE_CHARS) {
                throw new IllegalArgumentException("判断自述超过上限：" + rationale.length());
            }
            if ((adoptedClaimId == null) != (sourcePartyId == null)) {
                throw new IllegalArgumentException("采纳标识与来源作者必须同时存在或同时为空");
            }
        }
    }

    /**
     * 一次评估。
     *
     * @param evaluationId          评估标识
     * @param beliefId              判断
     * @param personPartyId         判断者
     * @param factId                事实声明
     * @param sourcePartyId         被采纳来源；自拟为空
     * @param adoptedClaimId        被采纳主张；自拟为空
     * @param verdict               结论
     * @param factValue             当时事实值
     * @param maturedAtMillis       本人读完核验材料的时刻
     * @param supersedesEvaluationId 被本评估取代的旧评估；无为空
     */
    public record Evaluation(String evaluationId, String beliefId, String personPartyId,
                             String factId, String sourcePartyId, String adoptedClaimId,
                             Verdict verdict, long factValue, long maturedAtMillis,
                             String supersedesEvaluationId) {

        /** 校验字段。 */
        public Evaluation {
            requireText(evaluationId, "评估标识");
            requireText(beliefId, "评估判断");
            requireText(personPartyId, "评估人物");
            requireText(factId, "评估事实");
            Objects.requireNonNull(verdict, "评估结论");
            if (maturedAtMillis < 0) {
                throw new IllegalArgumentException("评估时点不能为负：" + maturedAtMillis);
            }
            if ((sourcePartyId == null) != (adoptedClaimId == null)) {
                throw new IllegalArgumentException("评估来源与采纳主张必须同时存在或同时为空");
            }
        }
    }

    /**
     * 习惯修订记录。
     *
     * @param revisionId       修订标识
     * @param personPartyId    人物
     * @param kind             习惯种类
     * @param fromLevel        旧等级
     * @param toLevel          新等级
     * @param supportBeliefIds 变更后的完整支持集合（排序）
     * @param atMillis         变更时点
     */
    public record HabitRevision(String revisionId, String personPartyId, HabitKind kind,
                                int fromLevel, int toLevel, List<String> supportBeliefIds,
                                long atMillis) {

        /** 校验字段。 */
        public HabitRevision {
            requireText(revisionId, "修订标识");
            requireText(personPartyId, "修订人物");
            Objects.requireNonNull(kind, "修订习惯");
            if (fromLevel < 0 || fromLevel > MAX_HABIT_LEVEL || toLevel < 0
                    || toLevel > MAX_HABIT_LEVEL) {
                throw new IllegalArgumentException("习惯等级越界：" + fromLevel + " -> " + toLevel);
            }
            supportBeliefIds = List.copyOf(Objects.requireNonNull(supportBeliefIds, "支持集合"));
            if (atMillis < 0) {
                throw new IllegalArgumentException("修订时点不能为负：" + atMillis);
            }
        }
    }

    /**
     * 不可变状态。
     *
     * @param beliefs                 全部判断
     * @param evaluations             全部评估（含已被取代的历史）
     * @param habitRevisions          习惯修订历史
     * @param nextEvaluationSequence  下一个评估序号，从 1 开始
     */
    public record State(List<Belief> beliefs, List<Evaluation> evaluations,
                        List<HabitRevision> habitRevisions, long nextEvaluationSequence) {

        /** 深拷贝并校验标识唯一与评估引用可解析。 */
        public State {
            beliefs = List.copyOf(Objects.requireNonNull(beliefs, "判断列表"));
            evaluations = List.copyOf(Objects.requireNonNull(evaluations, "评估列表"));
            habitRevisions = List.copyOf(Objects.requireNonNull(habitRevisions, "习惯修订"));
            if (nextEvaluationSequence < 1) {
                throw new IllegalArgumentException("下一评估序号必须为正：" + nextEvaluationSequence);
            }
            Set<String> beliefIds = new LinkedHashSet<>();
            for (Belief belief : beliefs) {
                if (!beliefIds.add(belief.beliefId())) {
                    throw new IllegalArgumentException("判断标识重复：" + belief.beliefId());
                }
            }
            Set<String> evaluationIds = new LinkedHashSet<>();
            for (Evaluation evaluation : evaluations) {
                if (!evaluationIds.add(evaluation.evaluationId())) {
                    throw new IllegalArgumentException("评估标识重复：" + evaluation.evaluationId());
                }
                if (!beliefIds.contains(evaluation.beliefId())) {
                    throw new IllegalArgumentException("评估指向不存在的判断：" + evaluation.beliefId());
                }
                if (evaluation.supersedesEvaluationId() != null
                        && !evaluationIds.contains(evaluation.supersedesEvaluationId())) {
                    throw new IllegalArgumentException("取代评估必须引用已存在的更早评估："
                            + evaluation.evaluationId());
                }
            }
            for (Belief belief : beliefs) {
                int count = 0;
                for (Evaluation evaluation : evaluations) {
                    if (!evaluation.beliefId().equals(belief.beliefId())) {
                        continue;
                    }
                    boolean superseded = false;
                    for (Evaluation later : evaluations) {
                        if (evaluation.evaluationId().equals(later.supersedesEvaluationId())) {
                            superseded = true;
                            break;
                        }
                    }
                    if (!superseded) {
                        count++;
                    }
                }
                if (count > 1) {
                    throw new IllegalArgumentException("同一判断不能有多个有效评估："
                            + belief.beliefId());
                }
            }
        }
    }

    /** 形成请求（全部由可信调用方给出，模型不能自填置信）。 */
    public record FormRequest(String beliefId, String personPartyId, String companyId,
                              Comparator comparator, long threshold, long targetAsOfMillis,
                              String verificationPublisherPartyId,
                              List<RoleView.ReadingRef> evidence,
                              String adoptedClaimId, String rationale) {
    }

    /** 形成结果。 */
    public record Outcome(boolean accepted, String reason, State state) {
    }

    /** 评估结果。 */
    public record EvaluationOutcome(State state, int appended, int superseded) {
    }

    private BeliefModel() {
    }

    // ------------------------------------------------------------------ 形成

    /**
     * 形成一条判断。证据必须来自本人真实完成阅读、且仍在当前回忆结果中。
     *
     * @param state       当前信念状态
     * @param information 真实信息状态
     * @param claims      可信结构化声明表
     * @param policy      冻结参数
     * @param recallPolicy 回忆参数
     * @param nowMillis   形成时刻
     * @param request     请求
     * @return 结果；拒绝时返回原状态
     */
    public static Outcome form(State state, InformationModel.State information, Claims claims,
                               Policy policy, CognitionRecall.Policy recallPolicy,
                               long nowMillis, FormRequest request) {
        Objects.requireNonNull(state, "信念状态");
        Objects.requireNonNull(information, "信息状态");
        Objects.requireNonNull(claims, "声明表");
        Objects.requireNonNull(policy, "参数");
        Objects.requireNonNull(recallPolicy, "回忆参数");
        Objects.requireNonNull(request, "形成请求");
        if (nowMillis < 0) {
            throw new IllegalArgumentException("形成时刻不能为负：" + nowMillis);
        }

        Belief existing = belief(state, request.beliefId());
        if (existing != null) {
            boolean same = existing.personPartyId().equals(request.personPartyId())
                    && existing.companyId().equals(request.companyId())
                    && existing.comparator() == request.comparator()
                    && existing.threshold() == request.threshold()
                    && existing.targetAsOfMillis() == request.targetAsOfMillis()
                    && existing.verificationPublisherPartyId()
                    .equals(request.verificationPublisherPartyId())
                    && existing.formedAtMillis() == nowMillis
                    && sameRefs(existing.evidence(), canonical(request.evidence()))
                    && Objects.equals(existing.adoptedClaimId(), blankToNull(request.adoptedClaimId()))
                    && Objects.equals(existing.rationale(), blankToNull(request.rationale()));
            return same
                    ? new Outcome(false, REASON_DUPLICATE, state)
                    : new Outcome(false, REASON_ID_CONFLICT, state);
        }

        requireCut(state, request.personPartyId(), nowMillis);
        long target = request.targetAsOfMillis();
        if (target <= nowMillis || target - nowMillis > policy.maxHorizonMillis()) {
            return new Outcome(false, REASON_TARGET, state);
        }
        requireText(request.personPartyId(), "判断人物");
        requireText(request.companyId(), "判断公司");
        requireText(request.verificationPublisherPartyId(), "验证发布者");
        Objects.requireNonNull(request.comparator(), "比较方向");
        if (!information.persons().contains(request.personPartyId())) {
            throw new IllegalArgumentException("判断人物未登记：" + request.personPartyId());
        }

        List<RoleView.ReadingRef> evidence = request.evidence() == null ? List.of()
                : List.copyOf(request.evidence());
        if (evidence.isEmpty() || evidence.size() > MAX_EVIDENCE) {
            return new Outcome(false, REASON_EVIDENCE, state);
        }
        Map<String, ReadObservation> observations = new LinkedHashMap<>();
        for (ReadObservation observation : information.observations()) {
            // 只保留本人经历：他人后读同一版本不得覆盖本人首次引用
            observations.put(key(observation.personPartyId(), observation.docId(),
                    observation.version()), observation);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ReadObservation> read = new ArrayList<>();
        for (RoleView.ReadingRef ref : evidence) {
            if (ref == null || !ref.personPartyId().equals(request.personPartyId())
                    || ref.version() < 1 || ref.index() < 1) {
                return new Outcome(false, REASON_EVIDENCE, state);
            }
            if (!seen.add(key(ref.docId(), ref.version()))) {
                return new Outcome(false, REASON_EVIDENCE, state);
            }
            ReadObservation observation = observations.get(key(request.personPartyId(),
                    ref.docId(), ref.version()));
            if (observation == null) {
                return new Outcome(false, REASON_EVIDENCE, state);
            }
            if (ordinal(information, request.personPartyId(), observation.readId()) != ref.index()) {
                return new Outcome(false, REASON_EVIDENCE, state);
            }
            read.add(observation);
        }
        evidence = canonical(evidence);
        Set<String> selected = new LinkedHashSet<>();
        CognitionRecall.Result recall = CognitionRecall.recall(information,
                request.personPartyId(), nowMillis, recallPolicy);
        for (CognitionRecall.Item item : recall.selected()) {
            selected.add(key(item.observation().docId(), item.observation().version()));
        }
        for (RoleView.ReadingRef ref : evidence) {
            if (!selected.contains(key(ref.docId(), ref.version()))) {
                return new Outcome(false, REASON_NOT_RECALLABLE, state);
            }
        }

        String adoptedClaimId = blankToNull(request.adoptedClaimId());
        String sourcePartyId = null;
        if (adoptedClaimId != null) {
            SourceClaim claim = claims.source(adoptedClaimId);
            if (claim == null
                    || !claim.companyId().equals(request.companyId())
                    || !claim.metric().equals(METRIC_ORDERS_CONFIRMED)
                    || claim.comparator() != request.comparator()
                    || claim.threshold() != request.threshold()
                    || claim.targetAsOfMillis() != target
                    || !seen.contains(key(claim.docId(), claim.version()))) {
                return new Outcome(false, REASON_ADOPTION, state);
            }
            InformationModel.DocumentVersion version = versionOf(information, claim.docId(),
                    claim.version());
            if (version == null || version.publishedAt() > nowMillis) {
                return new Outcome(false, REASON_ADOPTION, state);
            }
            InformationModel.Document document = documentOf(information, claim.docId());
            if (document == null || !document.publisherPartyId().equals(claim.authorPartyId())) {
                return new Outcome(false, REASON_ADOPTION, state);
            }
            if (!claim.verificationPublisherPartyId().equals(
                    request.verificationPublisherPartyId())) {
                return new Outcome(false, REASON_ADOPTION, state);
            }
            if (claim.authorPartyId().equals(request.personPartyId())) {
                // 自己的预测走自拟入口，不能绕成“采纳自己”再给自己的来源评分
                return new Outcome(false, REASON_ADOPTION, state);
            }
            for (Belief other : state.beliefs()) {
                if (other.personPartyId().equals(request.personPartyId())
                        && adoptedClaimId.equals(other.adoptedClaimId())) {
                    return new Outcome(false, REASON_ADOPTION, state);
                }
            }
            sourcePartyId = claim.authorPartyId();
        }
        // 语义去重：同人同 company/metric/target/comparator/threshold 只允许一条，
        // 与自拟/采纳、claimId、作者、证据、核验人无关
        for (Belief other : state.beliefs()) {
            if (other.personPartyId().equals(request.personPartyId())
                    && other.companyId().equals(request.companyId())
                    && other.metric().equals(METRIC_ORDERS_CONFIRMED)
                    && other.comparator() == request.comparator()
                    && other.threshold() == request.threshold()
                    && other.targetAsOfMillis() == target) {
                return new Outcome(false, REASON_DUPLICATE_PREDICTION, state);
            }
        }

        int total = 0;
        int open = 0;
        for (Belief other : state.beliefs()) {
            if (!other.personPartyId().equals(request.personPartyId())) {
                continue;
            }
            total++;
            if (effectiveEvaluation(state, other.beliefId()) == null) {
                open++;
            }
        }
        if (total >= policy.maxBeliefsPerPerson() || open >= policy.maxOpenBeliefs()) {
            return new Outcome(false, REASON_LIMIT, state);
        }

        int confidence = NEUTRAL_PERMILLE;
        if (sourcePartyId != null) {
            int competence = competencePermille(state, request.personPartyId(), sourcePartyId, policy);
            long delta = (long) policy.confidenceWeight() * (competence - NEUTRAL_PERMILLE) / 500L;
            confidence = clamp(NEUTRAL_PERMILLE + (int) delta, 0, 1_000);
        }
        Set<String> groups = new TreeSet<>();
        for (ReadObservation observation : read) {
            String source = observation.publicSourceDescription().trim();
            if (!source.isEmpty()) {
                groups.add(source);
            }
        }
        Belief created = new Belief(request.beliefId().trim(), request.personPartyId(),
                request.companyId(), METRIC_ORDERS_CONFIRMED, request.comparator(),
                request.threshold(), nowMillis, target, request.verificationPublisherPartyId(),
                evidence, List.copyOf(groups), adoptedClaimId, sourcePartyId, confidence,
                blankToNull(request.rationale()));
        List<Belief> beliefs = new ArrayList<>(state.beliefs());
        beliefs.add(created);
        return new Outcome(true, REASON_OK,
                new State(beliefs, state.evaluations(), state.habitRevisions(),
                        state.nextEvaluationSequence()));
    }

    // ------------------------------------------------------------------ 评估

    /**
     * 用“本次真实完成的阅读”评估本人判断；未读完该版本或该版本无匹配事实时不改变状态。
     *
     * @param state        当前信念状态
     * @param information  真实信息状态
     * @param claims       可信结构化声明表
     * @param policy       冻结参数
     * @param personPartyId 读者
     * @param docId        刚读完的文档
     * @param version      刚读完的版本
     * @param currentPosition 当前已确认提交位置；事实的 frozenPosition 不得超过它
     * @param nowMillis    该次完成阅读的 closedAt
     * @return 结果；无变化时返回同一实例
     */
    public static EvaluationOutcome evaluate(State state, InformationModel.State information,
                                             Claims claims, Policy policy, String personPartyId,
                                             String docId, long version, long currentPosition,
                                             long nowMillis) {
        Objects.requireNonNull(state, "信念状态");
        Objects.requireNonNull(information, "信息状态");
        Objects.requireNonNull(claims, "声明表");
        Objects.requireNonNull(policy, "参数");
        requireText(personPartyId, "评估人物");
        requireText(docId, "评估文档");
        if (nowMillis < 0) {
            throw new IllegalArgumentException("评估时刻不能为负：" + nowMillis);
        }
        if (currentPosition < 0) {
            throw new IllegalArgumentException("确认位置不能为负：" + currentPosition);
        }
        boolean completed = false;
        for (InformationModel.ClosedReading closed : information.closed()) {
            if (closed.personPartyId().equals(personPartyId) && closed.docId().equals(docId)
                    && closed.version() == version
                    && closed.reason() == InformationModel.CloseReason.COMPLETED
                    && closed.closedAt() == nowMillis) {
                completed = true;
                break;
            }
        }
        if (!completed) {
            return new EvaluationOutcome(state, 0, 0);
        }
        FactClaim fact = claims.fact(docId, version);
        if (fact == null) {
            return new EvaluationOutcome(state, 0, 0);
        }
        if (fact.frozenPosition() > currentPosition) {
            // 事实来自尚未确认的未来位置：不得用于评估
            return new EvaluationOutcome(state, 0, 0);
        }
        InformationModel.Document document = documentOf(information, docId);
        InformationModel.DocumentVersion published = versionOf(information, docId, version);
        if (document == null || published == null
                || !document.publisherPartyId().equals(fact.verifierPartyId())
                || published.publishedAt() > nowMillis
                || published.publishedAt() < fact.asOfMillis()) {
            return new EvaluationOutcome(state, 0, 0);
        }

        List<Belief> ordered = new ArrayList<>();
        for (Belief belief : state.beliefs()) {
            if (!belief.personPartyId().equals(personPartyId)) {
                continue;
            }
            if (!belief.companyId().equals(fact.companyId())
                    || !belief.metric().equals(fact.metric())
                    || belief.targetAsOfMillis() != fact.asOfMillis()
                    || !belief.verificationPublisherPartyId().equals(fact.verifierPartyId())) {
                continue;
            }
            if (containsEvidence(belief, docId)) {
                continue;
            }
            if (duplicateFact(state, belief.beliefId(), fact.factId())) {
                continue;
            }
            ordered.add(belief);
        }
        ordered.sort(java.util.Comparator.comparing(Belief::beliefId));

        List<Evaluation> evaluations = new ArrayList<>(state.evaluations());
        long sequence = state.nextEvaluationSequence();
        int appended = 0;
        int superseded = 0;
        for (Belief belief : ordered) {
            Evaluation effective = effectiveEvaluation(state, belief.beliefId());
            if (effective != null) {
                if (fact.supersedesFactId() == null
                        || !fact.supersedesFactId().equals(effective.factId())) {
                    continue;
                }
                superseded++;
            }
            requireCut(state, personPartyId, nowMillis);
            String evaluationId = "EV-" + sequence++;
            evaluations.add(new Evaluation(evaluationId, belief.beliefId(), personPartyId,
                    fact.factId(), belief.sourcePartyId(), belief.adoptedClaimId(),
                    verdictOf(fact, belief), fact.value(), nowMillis,
                    effective == null ? null : effective.evaluationId()));
            appended++;
        }
        if (appended == 0) {
            return new EvaluationOutcome(state, 0, 0);
        }
        State next = new State(state.beliefs(), evaluations, state.habitRevisions(), sequence);
        State withHabits = refreshHabits(next, personPartyId, policy, nowMillis);
        return new EvaluationOutcome(withHabits, appended, superseded);
    }

    // ------------------------------------------------------------------ 派生查询

    /**
     * 当前有效结论。
     *
     * @param state    状态
     * @param beliefId 判断
     * @return 结论；未评估返回 null
     */
    public static Verdict verdict(State state, String beliefId) {
        Evaluation effective = effectiveEvaluation(state, beliefId);
        return effective == null ? null : effective.verdict();
    }

    /**
     * 某来源对某人的当前预测可靠性（管理员/桥使用，不进角色投影）。
     *
     * @param state         状态
     * @param personPartyId 人物
     * @param sourcePartyId 来源
     * @param policy        参数
     * @return 0..1000
     */
    public static int competencePermille(State state, String personPartyId, String sourcePartyId,
                                         Policy policy) {
        Objects.requireNonNull(state, "信念状态");
        Objects.requireNonNull(policy, "参数");
        long contribution = 0;
        for (Belief belief : state.beliefs()) {
            if (!belief.personPartyId().equals(personPartyId)
                    || belief.adoptedClaimId() == null
                    || !belief.sourcePartyId().equals(sourcePartyId)) {
                continue;
            }
            Evaluation effective = effectiveEvaluation(state, belief.beliefId());
            if (effective == null) {
                continue;
            }
            contribution += effective.verdict() == Verdict.HIT ? 1 : -1;
        }
        long score = NEUTRAL_PERMILLE + (long) policy.trustStep() * contribution;
        return clamp((int) score, 0, 1_000);
    }

    /**
     * 已提交的习惯等级：取该人物该习惯最近一条 HabitRevision 的等级，查询时点不得早于已提交状态。
     *
     * <p>不会按查询时间自行降级；支持到期只在实际核验批次里重算并追加版本。</p>
     *
     * @param state         状态
     * @param personPartyId 人物
     * @param kind          习惯
     * @param policy        参数（保留一致性，等级本身来自已提交版本）
     * @param nowMillis     查询时点
     * @return 0..3；无版本返回 0
     */
    public static int habitLevel(State state, String personPartyId, HabitKind kind, Policy policy,
                                 long nowMillis) {
        Objects.requireNonNull(policy, "参数");
        requireCut(state, personPartyId, nowMillis);
        return committedLevel(state, personPartyId, kind, nowMillis);
    }

    /**
     * 按给定时间**预计算**的支持集合（不是已提交等级）。
     *
     * <p>窗口为 {@code 0 <= now - 该判断第一条评估的 maturedAt <= habitWindowMillis}，含两端；
     * 更正不延长该年龄。</p>
     *
     * @param state         状态
     * @param personPartyId 人物
     * @param kind          习惯
     * @param policy        参数
     * @param nowMillis     计算时点
     * @return 支持该习惯的判断（排序）
     */
    public static List<String> habitSupportAt(State state, String personPartyId, HabitKind kind,
                                              Policy policy, long nowMillis) {
        Objects.requireNonNull(kind, "习惯");
        List<String> support = new ArrayList<>();
        for (Belief belief : state.beliefs()) {
            if (!belief.personPartyId().equals(personPartyId)) {
                continue;
            }
            Evaluation effective = effectiveEvaluation(state, belief.beliefId());
            if (effective == null || effective.verdict() != Verdict.MISS) {
                continue;
            }
            if (kind == HabitKind.SEEK_SECOND_SOURCE && belief.publicSourceGroups().size() > 1) {
                continue;
            }
            long first = firstMatured(state, belief.beliefId());
            if (first < 0) {
                continue;
            }
            if (nowMillis < first || nowMillis - first > policy.habitWindowMillis()) {
                continue;
            }
            support.add(belief.beliefId());
        }
        support.sort(java.util.Comparator.naturalOrder());
        return List.copyOf(support);
    }

    /**
     * 当前有效评估。
     *
     * @param state    状态
     * @param beliefId 判断
     * @return 未被取代的最新评估；未评估返回 null
     */
    public static Evaluation effectiveEvaluation(State state, String beliefId) {
        Objects.requireNonNull(state, "信念状态");
        requireText(beliefId, "判断");
        Evaluation latest = null;
        for (Evaluation evaluation : state.evaluations()) {
            if (!evaluation.beliefId().equals(beliefId)) {
                continue;
            }
            boolean superseded = false;
            for (Evaluation later : state.evaluations()) {
                if (evaluation.evaluationId().equals(later.supersedesEvaluationId())) {
                    superseded = true;
                    break;
                }
            }
            if (!superseded) {
                latest = evaluation;
            }
        }
        return latest;
    }

    // ------------------------------------------------------------------ 投影

    /** 单条判断的本人投影。 */
    public record BeliefView(String beliefId, String companyId, String metric,
                             Comparator comparator, long threshold, long formedAtMillis,
                             long targetAsOfMillis, boolean open, String verdict,
                             String confidenceBand, String rationale,
                             List<RoleView.ReadingRef> evidence, long evaluatedAtMillis) {
        /** 冻结证据引用，不保留调用方可修改的列表。 */
        public BeliefView {
            evidence = List.copyOf(Objects.requireNonNull(evidence, "投影证据"));
        }
    }

    /** 相关来源的 band。 */
    public record SourceBand(String sourcePartyId, String competenceBand) {
    }

    /**
     * 习惯等级投影。
     *
     * @param kind              习惯
     * @param level             已提交等级
     * @param lastUpdatedMillis 上次实际核验导致等级/支持变化的时点；从未有版本为 -1
     */
    public record HabitLevel(HabitKind kind, int level, long lastUpdatedMillis) {
    }

    /** 本人有界投影。 */
    public record Projection(List<BeliefView> beliefs, List<SourceBand> sources,
                             List<HabitLevel> habits, boolean truncated) {
        /** 冻结本人投影的所有列表。 */
        public Projection {
            beliefs = List.copyOf(Objects.requireNonNull(beliefs, "判断投影"));
            sources = List.copyOf(Objects.requireNonNull(sources, "来源投影"));
            habits = List.copyOf(Objects.requireNonNull(habits, "习惯投影"));
        }
    }

    /**
     * 生成本人有界投影：OPEN 优先，其后形成时间降序、标识升序。
     *
     * @param state         状态
     * @param policy        参数
     * @param personPartyId 人物
     * @param nowMillis     当前时点
     * @param maxItems      条数上限 1..100
     * @return 投影
     */
    public static Projection project(State state, Policy policy, String personPartyId,
                                     long nowMillis, int maxItems) {
        Objects.requireNonNull(state, "信念状态");
        Objects.requireNonNull(policy, "参数");
        requireText(personPartyId, "人物");
        if (maxItems < 1 || maxItems > 100) {
            throw new IllegalArgumentException("投影条数上限必须在 1..100：" + maxItems);
        }
        requireCut(state, personPartyId, nowMillis);
        List<Belief> mine = new ArrayList<>();
        for (Belief belief : state.beliefs()) {
            if (belief.personPartyId().equals(personPartyId)) {
                mine.add(belief);
            }
        }
        mine.sort((left, right) -> {
            boolean leftOpen = effectiveEvaluation(state, left.beliefId()) == null;
            boolean rightOpen = effectiveEvaluation(state, right.beliefId()) == null;
            if (leftOpen != rightOpen) {
                return leftOpen ? -1 : 1;
            }
            int byTime = Long.compare(right.formedAtMillis(), left.formedAtMillis());
            return byTime != 0 ? byTime : left.beliefId().compareTo(right.beliefId());
        });
        boolean truncated = mine.size() > maxItems;
        List<BeliefView> views = new ArrayList<>();
        Set<String> sources = new TreeSet<>();
        for (int index = 0; index < Math.min(maxItems, mine.size()); index++) {
            Belief belief = mine.get(index);
            Evaluation effective = effectiveEvaluation(state, belief.beliefId());
            views.add(new BeliefView(belief.beliefId(), belief.companyId(), belief.metric(),
                    belief.comparator(), belief.threshold(), belief.formedAtMillis(),
                    belief.targetAsOfMillis(), effective == null,
                    effective == null ? "OPEN" : effective.verdict().name(),
                    band(belief.confidencePermille()), belief.rationale(), belief.evidence(),
                    effective == null ? -1L : effective.maturedAtMillis()));
            if (belief.sourcePartyId() != null) {
                sources.add(belief.sourcePartyId());
            }
        }
        List<SourceBand> bands = new ArrayList<>();
        for (String source : sources) {
            bands.add(new SourceBand(source,
                    band(competencePermille(state, personPartyId, source, policy))));
        }
        List<HabitLevel> habits = List.of(
                new HabitLevel(HabitKind.VERIFY_BEFORE_ACT,
                        committedLevel(state, personPartyId, HabitKind.VERIFY_BEFORE_ACT, nowMillis),
                        lastHabitAt(state, personPartyId, HabitKind.VERIFY_BEFORE_ACT, nowMillis)),
                new HabitLevel(HabitKind.SEEK_SECOND_SOURCE,
                        committedLevel(state, personPartyId, HabitKind.SEEK_SECOND_SOURCE, nowMillis),
                        lastHabitAt(state, personPartyId, HabitKind.SEEK_SECOND_SOURCE, nowMillis)));
        return new Projection(views, bands, habits, truncated);
    }

    // ------------------------------------------------------------------ 内部

    private static State refreshHabits(State state, String personPartyId, Policy policy,
                                       long nowMillis) {
        List<HabitRevision> revisions = new ArrayList<>(state.habitRevisions());
        long sequence = revisions.size() + 1L;
        for (HabitKind kind : HabitKind.values()) {
            List<String> support = habitSupportAt(state, personPartyId, kind, policy, nowMillis);
            int level = Math.min(MAX_HABIT_LEVEL, support.size() / policy.habitThreshold());
            HabitRevision last = null;
            for (HabitRevision revision : revisions) {
                if (revision.personPartyId().equals(personPartyId) && revision.kind() == kind) {
                    last = revision;
                }
            }
            int previousLevel = last == null ? 0 : last.toLevel();
            List<String> previousSupport = last == null ? List.of() : last.supportBeliefIds();
            if (level != previousLevel || !support.equals(previousSupport)) {
                revisions.add(new HabitRevision("HR-" + sequence++, personPartyId, kind,
                        previousLevel, level, support, nowMillis));
            }
        }
        if (revisions.size() == state.habitRevisions().size()) {
            return state;
        }
        return new State(state.beliefs(), state.evaluations(), revisions,
                state.nextEvaluationSequence());
    }

    /** 该人物最近一条已提交习惯版本的等级（不晚于查询时点）。 */
    private static int committedLevel(State state, String personPartyId, HabitKind kind,
                                      long nowMillis) {
        int level = 0;
        for (HabitRevision revision : state.habitRevisions()) {
            if (revision.personPartyId().equals(personPartyId) && revision.kind() == kind
                    && revision.atMillis() <= nowMillis) {
                level = revision.toLevel();
            }
        }
        return level;
    }

    /** 该人物最近一条已提交习惯版本的时点；无版本为 -1。 */
    private static long lastHabitAt(State state, String personPartyId, HabitKind kind,
                                    long nowMillis) {
        long at = -1;
        for (HabitRevision revision : state.habitRevisions()) {
            if (revision.personPartyId().equals(personPartyId) && revision.kind() == kind
                    && revision.atMillis() <= nowMillis) {
                at = revision.atMillis();
            }
        }
        return at;
    }

    /** 拒绝“用未来状态生成更早切点”：查询时点不得早于该人物任何已提交时点。 */
    private static void requireCut(State state, String personPartyId, long nowMillis) {
        if (nowMillis < 0) {
            throw new IllegalArgumentException("查询时点不能为负：" + nowMillis);
        }
        long latest = -1;
        for (Belief belief : state.beliefs()) {
            if (belief.personPartyId().equals(personPartyId)) {
                latest = Math.max(latest, belief.formedAtMillis());
            }
        }
        for (Evaluation evaluation : state.evaluations()) {
            if (evaluation.personPartyId().equals(personPartyId)) {
                latest = Math.max(latest, evaluation.maturedAtMillis());
            }
        }
        for (HabitRevision revision : state.habitRevisions()) {
            if (revision.personPartyId().equals(personPartyId)) {
                latest = Math.max(latest, revision.atMillis());
            }
        }
        if (latest >= 0 && nowMillis < latest) {
            throw new IllegalArgumentException("不能用未来状态生成更早切点：now=" + nowMillis
                    + " < " + latest);
        }
    }

    private static long firstMatured(State state, String beliefId) {
        long first = -1;
        for (Evaluation evaluation : state.evaluations()) {
            if (!evaluation.beliefId().equals(beliefId)) {
                continue;
            }
            first = first < 0 ? evaluation.maturedAtMillis()
                    : Math.min(first, evaluation.maturedAtMillis());
        }
        return first;
    }

    private static Verdict verdictOf(FactClaim fact, Belief belief) {
        boolean atLeast = belief != null ? belief.comparator() == Comparator.AT_LEAST : true;
        long threshold = belief != null ? belief.threshold() : 0L;
        boolean hit = atLeast ? fact.value() >= threshold : fact.value() <= threshold;
        return hit ? Verdict.HIT : Verdict.MISS;
    }

    private static boolean duplicateFact(State state, String beliefId, String factId) {
        for (Evaluation evaluation : state.evaluations()) {
            if (evaluation.beliefId().equals(beliefId) && evaluation.factId().equals(factId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsEvidence(Belief belief, String docId) {
        for (RoleView.ReadingRef ref : belief.evidence()) {
            // 同 docId 跨版本也禁止自证
            if (ref.docId().equals(docId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameRefs(List<RoleView.ReadingRef> left, List<RoleView.ReadingRef> right) {
        if (right == null) {
            return left.isEmpty();
        }
        return left.equals(List.copyOf(right));
    }

    private static int ordinal(InformationModel.State information, String person, String readId) {
        List<String> ids = new ArrayList<>();
        for (InformationModel.Reading reading : information.active()) {
            if (reading.personPartyId().equals(person)) {
                ids.add(reading.readId());
            }
        }
        for (InformationModel.ClosedReading closed : information.closed()) {
            if (closed.personPartyId().equals(person)) {
                ids.add(closed.readId());
            }
        }
        ids.sort(java.util.Comparator.comparingLong(BeliefModel::sequence)
                .thenComparing(java.util.Comparator.naturalOrder()));
        return ids.indexOf(readId) + 1;
    }

    private static long sequence(String readId) {
        if (readId == null || !readId.startsWith("READ-")) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(readId.substring("READ-".length()));
        } catch (NumberFormatException notNumeric) {
            return Long.MAX_VALUE;
        }
    }

    private static InformationModel.Document documentOf(InformationModel.State information,
                                                        String docId) {
        for (InformationModel.Document document : information.documents()) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        return null;
    }

    private static InformationModel.DocumentVersion versionOf(InformationModel.State information,
                                                               String docId, long version) {
        InformationModel.Document document = documentOf(information, docId);
        return document == null ? null : document.at(version);
    }

    private static Belief belief(State state, String beliefId) {
        for (Belief belief : state.beliefs()) {
            if (belief.beliefId().equals(beliefId)) {
                return belief;
            }
        }
        return null;
    }

    private static String band(int permille) {
        if (permille < 400) {
            return "LOW";
        }
        return permille < 700 ? "MID" : "HIGH";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String key(String docId, long version) {
        return docId + '\u0000' + version;
    }

    private static String key(String person, String docId, long version) {
        return person + '\u0000' + docId + '\u0000' + version;
    }

    /** 证据按 docId/version/index 规范排序；顺序不是新内容。 */
    private static List<RoleView.ReadingRef> canonical(List<RoleView.ReadingRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<RoleView.ReadingRef> copy = new ArrayList<>(refs);
        for (RoleView.ReadingRef ref : copy) {
            Objects.requireNonNull(ref, "判断证据");
        }
        copy.sort(java.util.Comparator.comparing(RoleView.ReadingRef::docId)
                .thenComparingLong(RoleView.ReadingRef::version)
                .thenComparingLong(RoleView.ReadingRef::index)
                .thenComparing(RoleView.ReadingRef::personPartyId));
        return List.copyOf(copy);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
