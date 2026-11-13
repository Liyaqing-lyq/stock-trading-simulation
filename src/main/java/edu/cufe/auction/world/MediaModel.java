package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import edu.cufe.auction.world.InformationModel.AudienceKind;
import edu.cufe.auction.world.InformationModel.Document;
import edu.cufe.auction.world.InformationModel.DocumentVersion;
import edu.cufe.auction.world.InformationModel.ReadObservation;
import edu.cufe.auction.world.RoleAccess.Action;
import edu.cufe.auction.world.RoleAccess.Decision;
import edu.cufe.auction.world.RoleAccess.Target;
import edu.cufe.auction.world.RoleView.ReadingRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * M4h.1 公开证据采编纯核：选题、组织草稿、编辑实际阅读后批准、发布固定批准版本、更正与转载谱系。
 *
 * <p><b>能力边界</b>：本类不执行订单、不写世界日志、不建待办、不调度、不读墙钟、不碰现金、
 * 不调用模型、不联网。稿件正文只存在于 {@link InformationModel} 文档里；媒体状态只保存
 * 文档标识与版本、作者/编辑、证据引用、状态与追加历史。需要同时改变媒体与资料的入口返回
 * 同一个不可变 {@link BridgeOutcome}，由未来运行桥一次提交。</p>
 *
 * <p><b>一个状态一个媒体组织</b>：{@link #open(Policy, OpeningRegistry)} 把 {@link Policy} 整体
 * 冻结进 {@link State}——媒体组织、启用模板与来源下限、四类耗时、回忆参数都在内。此后每个操作
 * 都必须提交<b>与冻结策略完全相等</b>的策略，否则返回 {@link #REASON_POLICY_MISMATCH}；
 * 不接受调用者临时换组织、换阈值或换耗时。没有无参 {@code open()}，因此不存在「先建空状态再套
 * 别的策略」的绕过路径。未来运行桥若要组合多个媒体，就为每个媒体各持一个 {@code State}。</p>
 *
 * <p><b>作者与编辑</b>：两人必须是不同的真实任职者——同一组织不允许兼任两个岗位
 * （{@link RoleAccess.State} 既有不变量），因此「作者自批」在授权阶段即被拒绝。作者/编辑标识
 * 由受信调用方传入，不来自模型工具参数；资格只看<b>真实当前任职</b>。写作、审阅、发布这类有工时
 * 的动作在<b>开始与完成两个时点</b>都要有对应岗位的当前任职，中途到期或撤职一律拒绝；查询类动作
 * 只看查询时点。</p>
 *
 * <p><b>身份边界</b>：本类只在 {@link #open(Policy, OpeningRegistry)} 核对媒体组织确实是
 * {@link OpeningRegistry} 里的 {@code ORGANIZATION}；人物真实性由 {@link InformationModel#open}
 * 与 {@link RoleAccess#open} 的开局负责。</p>
 *
 * <p><b>证据口径</b>：只接受作者本人真实完成、且仍在当前 {@link CognitionRecall} 结果里的
 * <b>公开</b>资料固定版本；{@link ReadingRef#index()} 必须与该次完成阅读的本人序号一致，
 * 不能只信 docId/version。本步不校验观察帧，运行桥后续另在冻结帧与完成帧双重核对。</p>
 *
 * <p><b>独立来源组</b>：每条证据若已有持久谱系则继承其根集合，否则以自身为根；根是
 * (docId, version) 引用，不接受调用方传入的根或组数。组数 = 根资料中不同<b>发布人物</b>的个数，
 * 因此同一发布人物的多份资料、以及同一报道的多篇转载都只算一组。</p>
 *
 * <p><b>发布口径</b>：发布入口只接受标识与时间，正文、引用与署名一律取自已批准的组织草稿版本，
 * 换稿在结构上不可能；更正走同一公开 {@code docId} 的追加版本，{@link InformationModel} 冻结的
 * 首版发布者不冒充本次编辑，本次实际编辑只记在 {@link Revision}。</p>
 *
 * <p><b>时间口径</b>：写作、审阅、发布耗时都是配置里的正毫秒，且必须与
 * {@code completedAt - startedAt} 完全相等；操作不得早于稿件上一事件，拒绝不改状态。
 * 组织工时的预留与消费属于 M4h.2 运行桥，本类不声称已执行资源限制。</p>
 */
public final class MediaModel {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** 成功。 */
    public static final String REASON_OK = "OK";
    /** 没有该岗位权限、非本人、跨组织、已撤职或已到期。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 提交的策略与状态冻结的策略不一致。 */
    public static final String REASON_POLICY_MISMATCH = "POLICY_MISMATCH";
    /** 稿件不存在。 */
    public static final String REASON_UNKNOWN_STORY = "UNKNOWN_STORY";
    /** 稿件标识或公开文档标识已被占用。 */
    public static final String REASON_DUPLICATE = "DUPLICATE";
    /** 当前状态不允许该动作。 */
    public static final String REASON_STATE = "STATE";
    /** 期望版本与当前版本不一致。 */
    public static final String REASON_VERSION = "VERSION";
    /** 耗时与配置不等、时间倒退或溢出。 */
    public static final String REASON_TIME = "TIME";
    /** 模板在本阶段未启用。 */
    public static final String REASON_TEMPLATE_NOT_ENABLED = "TEMPLATE_NOT_ENABLED";
    /** 证据清单本身非法（空、重复、他人引用、字段越界）。 */
    public static final String REASON_EVIDENCE = "EVIDENCE";
    /** 媒体来源谱系缺失、冲突或不是已确认的公开版本。 */
    public static final String REASON_EVIDENCE_PROVENANCE = "EVIDENCE_PROVENANCE";

    /** 别名指向的根不在本版证据展开结果内。 */
    public static final String REASON_ALIAS_ROOT = "ALIAS_ROOT";
    /** 证据版本本人没有真实读完。 */
    public static final String REASON_EVIDENCE_NOT_READ = "EVIDENCE_NOT_READ";
    /** 证据不是公开资料。 */
    public static final String REASON_EVIDENCE_NOT_PUBLIC = "EVIDENCE_NOT_PUBLIC";
    /** 证据序号与本人真实阅读序号不符。 */
    public static final String REASON_EVIDENCE_INDEX = "EVIDENCE_INDEX";
    /** 证据已不在当前回忆结果里（已遗忘）。 */
    public static final String REASON_EVIDENCE_NOT_RECALLABLE = "EVIDENCE_NOT_RECALLABLE";
    /** 独立来源组少于模板下限。 */
    public static final String REASON_INSUFFICIENT_SOURCES = "INSUFFICIENT_SOURCES";
    /** 编辑没有读完本次草稿版本。 */
    public static final String REASON_DRAFT_NOT_READ = "DRAFT_NOT_READ";
    /** 编辑读过本次草稿版本，但该版本已不在当前回忆结果里。 */
    public static final String REASON_DRAFT_NOT_RECALLABLE = "DRAFT_NOT_RECALLABLE";
    /** 退回必须写明理由。 */
    public static final String REASON_NOTE = "NOTE";

    /** 本阶段唯一允许启用的两类模板。 */
    private static final Set<TemplateKind> PHASE_ONE =
            Set.of(TemplateKind.WIRE, TemplateKind.INVESTIGATIVE);

    private static final String DRAFT_PREFIX = "media-draft:";
    private static final String PUBLIC_PREFIX = "media-public:";

    private MediaModel() {
    }

    /**
     * 媒体模板：按专题登记八类名称，本阶段只启用前两类。
     */
    public enum TemplateKind {
        /** 通讯／快讯。 */
        WIRE,
        /** 综合财经与调查。 */
        INVESTIGATIVE,
        /** 证券与资本市场媒体。 */
        MARKETS,
        /** 行业垂直与价格资讯。 */
        VERTICAL,
        /** 地方／区域商业媒体。 */
        LOCAL,
        /** 海外／跨市场媒体。 */
        CROSS_BORDER,
        /** 聚合平台／行情终端。 */
        AGGREGATOR,
        /** 独立作者／自媒体。 */
        INDEPENDENT
    }

    /** 稿件状态；没有「撤回」状态，未提交的稿件自然没有公开影响。 */
    public enum Status {
        /** 编辑中：记者可提交或重新提交。 */
        DRAFT,
        /** 已提交组织草稿，等待编辑审阅。 */
        SUBMITTED,
        /** 编辑退回，记者可修改后重新提交。 */
        RETURNED,
        /** 编辑已批准，等待发布。 */
        APPROVED,
        /** 已公开发布。 */
        PUBLISHED
    }

    /**
     * 冻结的实验策略：媒体组织、启用模板与来源下限、四类正耗时、回忆参数。
     *
     * @param mediaOrganizationPartyId 媒体组织标识
     * @param enabledTemplates         启用模板 → 最少独立来源组（本阶段只允许 WIRE/INVESTIGATIVE）
     * @param writeMillis              写作耗时，正毫秒
     * @param reviewMillis             审阅耗时，正毫秒
     * @param publishMillis            发布耗时，正毫秒
     * @param draftReadMillis          组织草稿的固定阅读耗时，正毫秒
     * @param publicReadMillis         公开成稿的固定阅读耗时，正毫秒
     * @param recallPolicy             回忆参数，决定证据是否仍可被记起
     */
    public record Policy(String mediaOrganizationPartyId, Map<TemplateKind, Integer> enabledTemplates,
                         long writeMillis, long reviewMillis, long publishMillis,
                         long draftReadMillis, long publicReadMillis,
                         CognitionRecall.Policy recallPolicy) {

        /** 校验组织、模板范围与全部正耗时。 */
        public Policy {
            requireText(mediaOrganizationPartyId, "媒体组织");
            Objects.requireNonNull(enabledTemplates, "启用模板");
            Map<TemplateKind, Integer> copy = new TreeMap<>(enabledTemplates);
            if (copy.isEmpty()) {
                throw new IllegalArgumentException("至少启用一个媒体模板");
            }
            for (Map.Entry<TemplateKind, Integer> entry : copy.entrySet()) {
                Objects.requireNonNull(entry.getKey(), "模板种类");
                if (!PHASE_ONE.contains(entry.getKey())) {
                    throw new IllegalArgumentException("本阶段未启用的模板：" + entry.getKey());
                }
                if (entry.getValue() == null || entry.getValue() < 1) {
                    throw new IllegalArgumentException("模板来源下限必须为正："
                            + entry.getKey() + " -> " + entry.getValue());
                }
            }
            enabledTemplates = java.util.Collections.unmodifiableMap(copy);
            requirePositive(writeMillis, "写作耗时");
            requirePositive(reviewMillis, "审阅耗时");
            requirePositive(publishMillis, "发布耗时");
            requirePositive(draftReadMillis, "草稿阅读耗时");
            requirePositive(publicReadMillis, "成稿阅读耗时");
            Objects.requireNonNull(recallPolicy, "回忆参数");
        }
    }

    /**
     * 一份资料的固定版本引用（谱系根）。
     *
     * @param docId   资料标识
     * @param version 版本
     */
    public record Ref(String docId, long version) {

        /** 校验字段。 */
        public Ref {
            requireText(docId, "资料标识");
            if (version < 1) {
                throw new IllegalArgumentException("资料版本必须从 1 开始：" + version);
            }
        }
    }

    /**
     * 一条持久谱系：某公开版本的全部原始根。
     *
     * @param docId   公开资料标识
     * @param version 公开版本
     * @param roots   原始根集合（非空、不含自身）
     */
    public record Lineage(String docId, long version, List<Ref> roots) {

        /** 冻结根集合并拒绝自引用。 */
        public Lineage {
            requireText(docId, "谱系资料");
            if (version < 1) {
                throw new IllegalArgumentException("谱系版本必须从 1 开始：" + version);
            }
            roots = canonicalRefs(Objects.requireNonNull(roots, "谱系根"));
            if (roots.isEmpty()) {
                throw new IllegalArgumentException("谱系至少需要一个根：" + docId);
            }
            for (Ref root : roots) {
                if (root.docId().equals(docId) && root.version() == version) {
                    throw new IllegalArgumentException("谱系不能以自身为根：" + docId);
                }
            }
        }
    }

    /**
     * 一篇稿件：模板、作者、状态与全部标识引用；正文不在这里。
     *
     * @param storyId            稿件标识
     * @param template           模板
     * @param authorPartyId      作者
     * @param topic              选题描述
     * @param status             状态
     * @param revisionNo         已提交次数（提出选题时为 0）
     * @param draftDocId         组织草稿文档标识
     * @param draftVersion       当前草稿版本；未提交为 0
     * @param publicDocId        公开文档标识；未发布为空串
     * @param publicVersion      公开版本；未发布为 0
     * @param evidence           作者证据（本人范围引用）
     * @param roots              本篇证据的原始根
     * @param sourceGroups       独立来源组数
     * @param approvedBy         批准编辑；未批准为空
     * @param approvedAtMillis   批准时刻；未批准为 -1
     * @param approvedRevision   被批准的提交次数；未批准为 0
     * @param createdAtMillis    创建时刻
     * @param updatedAtMillis    最近一次状态变化时刻
     */
    public record Story(String storyId, TemplateKind template, String authorPartyId, String topic,
                        Status status, int revisionNo, String draftDocId, long draftVersion,
                        String publicDocId, long publicVersion, List<ReadingRef> evidence,
                        List<Ref> roots, int sourceGroups, String approvedBy, long approvedAtMillis,
                        int approvedRevision, long createdAtMillis, long updatedAtMillis) {

        /** 校验字段一致性并冻结集合。 */
        public Story {
            requireText(storyId, "稿件标识");
            Objects.requireNonNull(template, "稿件模板");
            requireText(authorPartyId, "稿件作者");
            requireText(topic, "选题");
            Objects.requireNonNull(status, "稿件状态");
            requireText(draftDocId, "草稿文档");
            if (revisionNo < 0 || draftVersion < 0 || publicVersion < 0 || sourceGroups < 0
                    || approvedRevision < 0) {
                throw new IllegalArgumentException("稿件计数不能为负：" + storyId);
            }
            if (publicDocId == null) {
                publicDocId = "";
            }
            if (publicVersion > 0 && publicDocId.isBlank()) {
                throw new IllegalArgumentException("已发布稿件缺少公开文档：" + storyId);
            }
            if (revisionNo == 0 && draftVersion != 0) {
                throw new IllegalArgumentException("未提交稿件不能有草稿版本：" + storyId);
            }
            if (revisionNo > 0 && draftVersion < 1) {
                throw new IllegalArgumentException("已提交稿件必须有草稿版本：" + storyId);
            }
            if (status == Status.APPROVED
                    && (approvedBy == null || approvedRevision != revisionNo)) {
                throw new IllegalArgumentException("批准必须对应本次提交：" + storyId);
            }
            if (approvedBy != null && approvedRevision < 1) {
                throw new IllegalArgumentException("批准记录缺少提交次数：" + storyId);
            }
            if (approvedAtMillis < -1 || createdAtMillis < 0 || updatedAtMillis < createdAtMillis) {
                throw new IllegalArgumentException("稿件时刻非法：" + storyId);
            }
            evidence = canonicalEvidence(Objects.requireNonNull(evidence, "证据"));
            roots = canonicalRefs(Objects.requireNonNull(roots, "证据根"));
            if (evidence.isEmpty() != roots.isEmpty()) {
                throw new IllegalArgumentException("证据与根集合必须同时为空或同时非空：" + storyId);
            }
        }
    }

    /**
     * 一条不可追加的状态变更记录。
     *
     * @param storyId      稿件
     * @param revisionNo   当时的提交次数
     * @param status       变更后状态
     * @param actorPartyId 实际执行人
     * @param atMillis     变更时刻
     * @param docId        关联资料；无关联为空串
     * @param docVersion   关联版本；无关联为 0
     * @param note         结果代码或退回理由
     */
    public record Revision(String storyId, int revisionNo, Status status, String actorPartyId,
                           long atMillis, String docId, long docVersion, String note) {

        /** 校验字段。 */
        public Revision {
            requireText(storyId, "记录稿件");
            requireText(actorPartyId, "记录执行人");
            Objects.requireNonNull(status, "记录状态");
            if (revisionNo < 0 || atMillis < 0 || docVersion < 0) {
                throw new IllegalArgumentException("记录计数不能为负：" + storyId);
            }
            if (docId == null) {
                docId = "";
            }
            if (note == null) {
                note = "";
            }
        }
    }

    /**
     * 媒体状态：一个媒体组织的冻结策略、稿件、追加变更记录与持久谱系。
     *
     * @param stories   稿件，按标识排序
     * @param revisions 变更记录，按稿件/提交次数/状态排序
     * @param lineage   公开版本的开源谱系，按资料/版本排序
     * @param policy    开局冻结的策略；所有操作都必须与它完全相等
     */
    public record State(List<Story> stories, List<Revision> revisions, List<Lineage> lineage,
                        Policy policy) {

        /** 冻结并校验标识唯一、记录可解析、稿件模板在冻结策略内。 */
        public State {
            Objects.requireNonNull(policy, "冻结策略");
            List<Story> storyList = sorted(stories, Comparator.comparing(Story::storyId));
            Set<String> storyIds = new LinkedHashSet<>();
            for (Story story : storyList) {
                if (!storyIds.add(story.storyId())) {
                    throw new IllegalArgumentException("稿件标识重复：" + story.storyId());
                }
                if (!policy.enabledTemplates().containsKey(story.template())) {
                    throw new IllegalArgumentException("稿件模板未在冻结策略中启用："
                            + story.template());
                }
            }
            List<Revision> revisionList = sorted(revisions,
                    Comparator.comparing(Revision::storyId)
                            .thenComparingInt(Revision::revisionNo)
                            .thenComparing(revision -> revision.status().name()));
            Set<String> revisionKeys = new LinkedHashSet<>();
            for (Revision revision : revisionList) {
                String key = revision.storyId() + '\u0000' + revision.revisionNo() + '\u0000'
                        + revision.status();
                if (!revisionKeys.add(key)) {
                    throw new IllegalArgumentException("同一稿件同一提交次数只能有一条同名记录："
                            + revision.storyId() + " / " + revision.revisionNo());
                }
                if (!storyIds.contains(revision.storyId())) {
                    throw new IllegalArgumentException("记录指向不存在的稿件：" + revision.storyId());
                }
            }
            for (Story story : storyList) {
                if (!revisionKeys.contains(story.storyId() + '\u0000' + story.revisionNo()
                        + '\u0000' + story.status())) {
                    throw new IllegalArgumentException("稿件当前状态缺少记录："
                            + story.storyId() + " / " + story.status());
                }
            }
            List<Lineage> lineageList = sorted(lineage, Comparator.comparing(Lineage::docId)
                    .thenComparingLong(Lineage::version));
            Set<String> lineageKeys = new LinkedHashSet<>();
            for (Lineage entry : lineageList) {
                String key = entry.docId() + '\u0000' + entry.version();
                if (!lineageKeys.add(key)) {
                    throw new IllegalArgumentException("谱系重复：" + entry.docId()
                            + " v" + entry.version());
                }
            }
            stories = List.copyOf(storyList);
            revisions = List.copyOf(revisionList);
            lineage = List.copyOf(lineageList);
        }
    }

    /**
     * 只改媒体状态的结果。
     *
     * @param accepted 业务是否成功
     * @param reason   结果代码，见 {@link MediaModel} 常量
     * @param state    操作后的状态；被拒绝时等于输入状态
     */
    public record Outcome(boolean accepted, String reason, State state) {
    }

    /**
     * 同时改媒体与资料的结果；调用方必须同批提交两者，不能先后各写一半。
     *
     * @param accepted    业务是否成功
     * @param reason      结果代码
     * @param state       操作后的媒体状态；被拒绝时等于输入状态
     * @param information 操作后的信息状态；被拒绝时等于输入状态
     */
    public record BridgeOutcome(boolean accepted, String reason, State state,
                                InformationModel.State information) {
    }

    /**
     * 私有证据上下文：作者实际读过的非公开引用、既有证据状态与新别名。
     *
     * @param privateEvidence 私有证据（作者本人真实读过且仍可回忆）
     * @param evidence        既有别名与身份共享，新结果在其基础上追加
     * @param aliases         本版要绑定的别名
     */
    public record EvidenceContext(List<ReadingRef> privateEvidence, MediaEvidence.State evidence,
                                  List<MediaEvidence.AliasBinding> aliases) {

        /** 校验字段并冻结。 */
        public EvidenceContext {
            privateEvidence = List.copyOf(privateEvidence == null ? List.of() : privateEvidence);
            evidence = evidence == null ? MediaEvidence.empty() : evidence;
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
        }

        /** 无私有证据、无别名的旧路径上下文。 */
        public static EvidenceContext none() {
            return new EvidenceContext(List.of(), MediaEvidence.empty(), List.of());
        }
    }

    /**
     * 私有证据提交的三态结果。
     *
     * @param accepted    是否成功
     * @param reason      原因码
     * @param state       媒体状态
     * @param information 信息状态
     * @param evidence    追加别名后的证据状态
     */
    public record EvidenceBridgeOutcome(boolean accepted, String reason, State state,
                                        InformationModel.State information,
                                        MediaEvidence.State evidence) {
    }

    /**
     * 提出选题。
     *
     * @param storyId   新稿件标识
     * @param template  模板
     * @param topic     选题描述
     * @param nowMillis 提出时刻
     */
    public record ProposeRequest(String storyId, TemplateKind template, String topic,
                                 long nowMillis) {

        /** 校验静态字段。 */
        public ProposeRequest {
            requireText(storyId, "稿件标识");
            Objects.requireNonNull(template, "稿件模板");
            requireText(topic, "选题");
        }
    }

    /**
     * 提交或重新提交组织草稿。
     *
     * @param storyId                 稿件标识
     * @param expectedRevision        期望的提交次数
     * @param title                   草稿标题
     * @param body                    草稿正文
     * @param publicSourceDescription 对外可见的来源描述
     * @param evidence                作者本人范围的证据引用
     * @param startedAtMillis         开始写作的模拟时刻
     * @param completedAtMillis       写作完成的模拟时刻
     */
    public record SubmitRequest(String storyId, int expectedRevision, String title, String body,
                                String publicSourceDescription, List<ReadingRef> evidence,
                                long startedAtMillis, long completedAtMillis) {

        /** 校验静态字段并冻结证据。 */
        public SubmitRequest {
            requireText(storyId, "稿件标识");
            requireText(title, "稿件标题");
            requireText(body, "稿件正文");
            Objects.requireNonNull(publicSourceDescription, "公开来源描述");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "证据"));
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("期望提交次数不能为负：" + expectedRevision);
            }
        }
    }

    /**
     * 编辑审阅：批准或退回。
     *
     * @param storyId           稿件标识
     * @param expectedRevision  期望的提交次数
     * @param approve           true 批准，false 退回
     * @param note              退回理由或审阅说明
     * @param startedAtMillis   开始审阅的模拟时刻
     * @param completedAtMillis 审阅完成的模拟时刻
     */
    public record ReviewRequest(String storyId, int expectedRevision, boolean approve, String note,
                                long startedAtMillis, long completedAtMillis) {

        /** 校验字段。 */
        public ReviewRequest {
            requireText(storyId, "稿件标识");
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("审阅必须指向已提交版本：" + expectedRevision);
            }
            if (note == null) {
                note = "";
            }
        }
    }

    /**
     * 发布已批准版本。
     *
     * @param storyId           稿件标识
     * @param expectedRevision  期望的提交次数
     * @param startedAtMillis   开始发布的模拟时刻
     * @param completedAtMillis 发布完成的模拟时刻
     */
    public record PublishRequest(String storyId, int expectedRevision,
                                 long startedAtMillis, long completedAtMillis) {

        /** 校验字段。 */
        public PublishRequest {
            requireText(storyId, "稿件标识");
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("发布必须指向已批准版本：" + expectedRevision);
            }
        }
    }

    /**
     * 开启一轮更正。
     *
     * @param storyId          稿件标识
     * @param expectedRevision 期望的提交次数
     * @param nowMillis        开启时刻
     */
    public record CorrectionRequest(String storyId, int expectedRevision, long nowMillis) {

        /** 校验字段。 */
        public CorrectionRequest {
            requireText(storyId, "稿件标识");
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("更正必须指向已发布版本：" + expectedRevision);
            }
        }
    }

    /**
     * 角色投影：不含内部根标识，未读草稿没有正文。
     *
     * @param storyId        稿件
     * @param template       模板
     * @param authorPartyId  作者
     * @param topic          选题
     * @param status         状态
     * @param revisionNo     提交次数
     * @param draftDocId     草稿文档
     * @param draftVersion   草稿版本
     * @param draftRead      本人是否读完该草稿版本
     * @param draftBody      本人已读完时的正文；否则为空
     * @param sourceGroups   独立来源组数
     * @param publicDocId    公开文档
     * @param publicVersion  公开版本
     * @param approvedBy     批准编辑
     * @param updatedAtMillis 最近变化时刻
     */
    public record StoryView(String storyId, TemplateKind template, String authorPartyId,
                            String topic, Status status, int revisionNo, String draftDocId,
                            long draftVersion, boolean draftRead, String draftBody,
                            int sourceGroups, String publicDocId, long publicVersion,
                            String approvedBy, long updatedAtMillis) {
    }

    // ---------------------------------------------------------------- 入口

    /**
     * 开局：冻结策略并核对媒体组织确实是已登记组织。
     *
     * <p>没有无参 {@code open()}：一个 {@link State} 必定带一条冻结策略，否则「先用空状态再换策略」
     * 就能绕过组织与阈值。人物是否存在不在本方法核对——那由 {@code InformationModel} 与
     * {@code RoleAccess} 的开局负责；本方法只保证策略里的媒体组织不是伪造标识。</p>
     *
     * @param policy   开局冻结的策略
     * @param registry 固定开局登记
     * @return 空媒体状态（含冻结策略）
     * @throws IllegalArgumentException 策略或登记为空、媒体组织未登记或不是组织
     */
    public static State open(Policy policy, OpeningRegistry registry) {
        requirePolicy(policy);
        if (registry == null) {
            throw new IllegalArgumentException("开局登记不能为空");
        }
        OpeningRegistry.Party party = null;
        for (OpeningRegistry.Party candidate : registry.parties()) {
            if (candidate.partyId().equals(policy.mediaOrganizationPartyId())) {
                party = candidate;
                break;
            }
        }
        if (party == null || party.kind() != OpeningRegistry.PartyKind.ORGANIZATION) {
            throw new IllegalArgumentException("媒体组织未登记或不是组织："
                    + policy.mediaOrganizationPartyId());
        }
        return new State(List.of(), List.of(), List.of(), policy);
    }

    /**
     * 记者提出选题。
     *
     * @param state  当前状态
     * @param policy 冻结策略
     * @param access 当前授权状态
     * @param author 作者（受信上下文）
     * @param request 请求
     * @return 结果；成功追加一篇 DRAFT 稿件
     */
    public static Outcome propose(State state, Policy policy, RoleAccess.State access,
                                  String author, ProposeRequest request) {
        requireState(state);
        requirePolicy(policy);
        requireAccess(access);
        requireText(author, "作者");
        Objects.requireNonNull(request, "提出选题请求");
        if (!frozen(state, policy)) {
            return reject(state, REASON_POLICY_MISMATCH);
        }
        if (request.nowMillis() < watermark(state)) {
            return reject(state, REASON_TIME);
        }
        if (!authorized(access, author, Action.WRITE_STORY, policy, request.nowMillis())) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        if (!policy.enabledTemplates().containsKey(request.template())) {
            return reject(state, REASON_TEMPLATE_NOT_ENABLED);
        }
        if (findStory(state, request.storyId()) != null) {
            return reject(state, REASON_DUPLICATE);
        }
        if (request.nowMillis() < 0) {
            return reject(state, REASON_TIME);
        }
        Story story = new Story(request.storyId(), request.template(), author, request.topic(),
                Status.DRAFT, 0, draftDocId(policy, request.storyId()), 0L, "", 0L,
                List.of(), List.of(), 0, null, -1L, 0, request.nowMillis(), request.nowMillis());
        return new Outcome(true, REASON_OK,
                append(state, story, new Revision(story.storyId(), 0, Status.DRAFT, author,
                        request.nowMillis(), "", 0L, "PROPOSED")));
    }

    /**
     * 作者提交组织草稿：证据必须是本人真实读完且仍可回忆的公开固定版本。
     *
     * <p>运行层须提供同一确认时点的其他媒体状态，以继承跨机构转载的实际根来源。
     * 此上下文不是角色工具参数，也不进入角色投影；缺少媒体成稿的谱系时拒绝提交，
     * 不能把转载者当作新的独立来源。{@code media-public:} 是本模块保留的成稿标识空间。</p>
     *
     * @param state   当前媒体状态
     * @param policy  冻结策略
     * @param access  当前授权状态
     * @param information 当前信息状态
     * @param author  作者（受信上下文）
     * @param request 请求
     * @param mediaContext 受信运行层提供的其他媒体已确认状态；本机构用 state，不传过期副本
     * @return 结果；成功时媒体与资料同时产生新候选
     */
    public static BridgeOutcome submit(State state, Policy policy, RoleAccess.State access,
                                       InformationModel.State information, String author,
                                       SubmitRequest request, List<State> mediaContext) {
        return toBridge(submitWithEvidence(state, policy, access, information, author, request,
                mediaContext, EvidenceContext.none()));
    }

    /**
     * 新 schema 专用重载：允许作者以本人实际读过且仍可回忆的<b>私有</b>资料作证据，
     * 并接收每稿每版的展示别名，返回媒体/信息/证据三态。
     *
     * <p>公开证据沿用旧规则且允许为空；私有证据不经任何 PUBLIC 中间文档。根仍写入既有
     * {@code Story.roots} 与版本化 {@code Lineage}，人物与公开投影不读取它们。</p>
     *
     * @param state        当前媒体状态
     * @param policy       冻结策略
     * @param access       当前授权
     * @param information  当前信息状态
     * @param author       作者（受信上下文）
     * @param request      提交请求（公开证据）
     * @param mediaContext 其他媒体已确认状态
     * @param context      私有证据、既有证据状态与新别名
     * @return 三态结果；失败时三态与入参一致
     */
    public static EvidenceBridgeOutcome submitWithEvidence(State state, Policy policy,
                                                           RoleAccess.State access,
                                                           InformationModel.State information,
                                                           String author, SubmitRequest request,
                                                           List<State> mediaContext,
                                                           EvidenceContext context) {
        Objects.requireNonNull(context, "证据上下文");
        requireState(state);
        requirePolicy(policy);
        requireAccess(access);
        requireInformation(information);
        requireText(author, "作者");
        Objects.requireNonNull(request, "提交请求");
        if (!frozen(state, policy)) {
            return evidenceReject(state, information, context.evidence(), REASON_POLICY_MISMATCH);
        }
        if (!windowOk(request.startedAtMillis(), request.completedAtMillis(), policy.writeMillis())) {
            return evidenceReject(state, information, context.evidence(), REASON_TIME);
        }
        if (!authorized(access, author, Action.WRITE_STORY, policy, request.startedAtMillis())
                || !authorized(access, author, Action.WRITE_STORY, policy,
                request.completedAtMillis())) {
            return evidenceReject(state, information, context.evidence(), REASON_NOT_AUTHORIZED);
        }
        Story story = findStory(state, request.storyId());
        if (story == null || !story.authorPartyId().equals(author)) {
            return evidenceReject(state, information, context.evidence(), REASON_NOT_AUTHORIZED);
        }
        if (story.status() != Status.DRAFT && story.status() != Status.RETURNED) {
            return evidenceReject(state, information, context.evidence(), REASON_STATE);
        }
        if (request.expectedRevision() != story.revisionNo()) {
            return evidenceReject(state, information, context.evidence(), REASON_VERSION);
        }
        if (request.startedAtMillis() < story.updatedAtMillis()
                || request.completedAtMillis() < watermark(state)) {
            return evidenceReject(state, information, context.evidence(), REASON_TIME);
        }
        Integer minGroups = policy.enabledTemplates().get(story.template());
        if (minGroups == null) {
            return evidenceReject(state, information, context.evidence(),
                    REASON_TEMPLATE_NOT_ENABLED);
        }
        String problem = evidenceProblem(information, policy, author, request.evidence(),
                context.privateEvidence(), request.completedAtMillis());
        if (problem != null) {
            return evidenceReject(state, information, context.evidence(), problem);
        }
        List<Ref> roots;
        try {
            roots = rootsFor(state, mediaContext, information, request.evidence(),
                    context.privateEvidence(), context.evidence(), true);
        } catch (IllegalArgumentException invalidProvenance) {
            return evidenceReject(state, information, context.evidence(),
                    REASON_EVIDENCE_PROVENANCE);
        }
        int groups = sourceGroups(information, roots);
        if (groups < 1) {
            return evidenceReject(state, information, context.evidence(), REASON_EVIDENCE);
        }
        if (groups < minGroups) {
            return evidenceReject(state, information, context.evidence(),
                    REASON_INSUFFICIENT_SOURCES);
        }
        MediaEvidence.State nextEvidence;
        try {
            nextEvidence = MediaEvidence.appendAliases(context.evidence(), story.storyId(),
                    story.revisionNo() + 1, roots, context.aliases());
        } catch (IllegalArgumentException invalidAlias) {
            return evidenceReject(state, information, context.evidence(), REASON_ALIAS_ROOT);
        }

        List<ReadingRef> allEvidence = new ArrayList<>(request.evidence());
        allEvidence.addAll(context.privateEvidence());
        InformationModel.VersionDraft draft = new InformationModel.VersionDraft(
                request.title(), request.body(),
                "作者来源描述（自由文字，未经程序核验）：" + request.publicSourceDescription(),
                request.completedAtMillis(), policy.draftReadMillis(),
                sourceIdsOf(allEvidence));
        InformationModel.State next;
        long version;
        try {
            if (story.draftVersion() == 0) {
                next = InformationModel.publish(information, story.draftDocId(), author,
                        InformationModel.Audience.organization(policy.mediaOrganizationPartyId()),
                        draft);
                version = 1;
            } else {
                next = InformationModel.publishVersion(information, story.draftDocId(), draft);
                version = story.draftVersion() + 1;
            }
        } catch (IllegalArgumentException rejected) {
            return evidenceReject(state, information, context.evidence(), REASON_STATE);
        }

        Story updated = new Story(story.storyId(), story.template(), story.authorPartyId(),
                story.topic(), Status.SUBMITTED, story.revisionNo() + 1, story.draftDocId(),
                version, story.publicDocId(), story.publicVersion(), allEvidence, roots,
                groups, null, -1L, 0, story.createdAtMillis(), request.completedAtMillis());
        State nextState = append(state, updated, new Revision(story.storyId(),
                updated.revisionNo(), Status.SUBMITTED, author, request.completedAtMillis(),
                story.draftDocId(), version, "SUBMITTED"));
        return new EvidenceBridgeOutcome(true, REASON_OK, nextState, next, nextEvidence);
    }

    /** 旧入口的适配：证据分量只在私有路径使用。 */
    private static BridgeOutcome toBridge(EvidenceBridgeOutcome outcome) {
        return new BridgeOutcome(outcome.accepted(), outcome.reason(), outcome.state(),
                outcome.information());
    }

    /** 证据路径的业务拒绝：三态原样返回，不产生部分写入。 */
    private static EvidenceBridgeOutcome evidenceReject(State state, InformationModel.State information,
                                                        MediaEvidence.State evidence, String reason) {
        return new EvidenceBridgeOutcome(false, reason, state, information, evidence);
    }

    /**
     * 编辑审阅：必须先真正读完<b>本次</b>草稿版本。
     *
     * @param state       当前媒体状态
     * @param policy      冻结策略
     * @param access      当前授权状态
     * @param information 当前信息状态
     * @param editor      编辑（受信上下文）
     * @param request     请求
     * @return 结果
     */
    public static Outcome review(State state, Policy policy, RoleAccess.State access,
                                 InformationModel.State information, String editor,
                                 ReviewRequest request) {
        requireState(state);
        requirePolicy(policy);
        requireAccess(access);
        requireInformation(information);
        requireText(editor, "编辑");
        Objects.requireNonNull(request, "审阅请求");
        if (!frozen(state, policy)) {
            return reject(state, REASON_POLICY_MISMATCH);
        }
        if (!windowOk(request.startedAtMillis(), request.completedAtMillis(), policy.reviewMillis())) {
            return reject(state, REASON_TIME);
        }
        if (!authorized(access, editor, Action.REVIEW_STORY, policy, request.startedAtMillis())
                || !authorized(access, editor, Action.REVIEW_STORY, policy,
                request.completedAtMillis())
                || editor.equals(authorOf(state, request.storyId()))) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        Story story = findStory(state, request.storyId());
        if (story == null) {
            return reject(state, REASON_UNKNOWN_STORY);
        }
        if (story.status() != Status.SUBMITTED) {
            return reject(state, REASON_STATE);
        }
        if (request.expectedRevision() != story.revisionNo()) {
            return reject(state, REASON_VERSION);
        }
        if (request.startedAtMillis() < story.updatedAtMillis()
                || request.completedAtMillis() < watermark(state)) {
            return reject(state, REASON_TIME);
        }
        if (!request.approve() && request.note().isBlank()) {
            return reject(state, REASON_NOTE);
        }
        if (!InformationModel.canCite(information, editor, story.draftDocId(), story.draftVersion(),
                request.completedAtMillis())) {
            return reject(state, REASON_DRAFT_NOT_READ);
        }
        if (!recalled(information, editor, story.draftDocId(), story.draftVersion(),
                request.completedAtMillis(), policy.recallPolicy())) {
            return reject(state, REASON_DRAFT_NOT_RECALLABLE);
        }
        Status next = request.approve() ? Status.APPROVED : Status.RETURNED;
        Story updated = new Story(story.storyId(), story.template(), story.authorPartyId(),
                story.topic(), next, story.revisionNo(), story.draftDocId(), story.draftVersion(),
                story.publicDocId(), story.publicVersion(), story.evidence(), story.roots(),
                story.sourceGroups(),
                request.approve() ? editor : null,
                request.approve() ? request.completedAtMillis() : -1L,
                request.approve() ? story.revisionNo() : 0,
                story.createdAtMillis(), request.completedAtMillis());
        return new Outcome(true, REASON_OK, append(state, updated, new Revision(story.storyId(),
                story.revisionNo(), next, editor, request.completedAtMillis(), story.draftDocId(),
                story.draftVersion(), request.note().isBlank()
                ? (request.approve() ? "APPROVED" : "RETURNED") : request.note())));
    }

    /**
     * 发布已批准版本：正文、引用与署名只取自已批准的草稿版本。
     *
     * @param state       当前媒体状态
     * @param policy      冻结策略
     * @param access      当前授权状态
     * @param information 当前信息状态
     * @param editor      执行发布的编辑
     * @param request     请求
     * @return 结果；成功时媒体与资料同时产生新候选
     */
    public static BridgeOutcome publish(State state, Policy policy, RoleAccess.State access,
                                        InformationModel.State information, String editor,
                                        PublishRequest request) {
        requireState(state);
        requirePolicy(policy);
        requireAccess(access);
        requireInformation(information);
        requireText(editor, "编辑");
        Objects.requireNonNull(request, "发布请求");
        if (!frozen(state, policy)) {
            return bridgeReject(state, information, REASON_POLICY_MISMATCH);
        }
        if (!windowOk(request.startedAtMillis(), request.completedAtMillis(),
                policy.publishMillis())) {
            return bridgeReject(state, information, REASON_TIME);
        }
        if (!authorized(access, editor, Action.PUBLISH_STORY, policy, request.startedAtMillis())
                || !authorized(access, editor, Action.PUBLISH_STORY, policy,
                request.completedAtMillis())) {
            return bridgeReject(state, information, REASON_NOT_AUTHORIZED);
        }
        Story story = findStory(state, request.storyId());
        if (story == null) {
            return bridgeReject(state, information, REASON_UNKNOWN_STORY);
        }
        if (story.status() != Status.APPROVED
                || story.approvedRevision() != story.revisionNo()) {
            return bridgeReject(state, information, REASON_STATE);
        }
        if (request.expectedRevision() != story.revisionNo()) {
            return bridgeReject(state, information, REASON_VERSION);
        }
        if (request.startedAtMillis() < story.updatedAtMillis()
                || request.completedAtMillis() < watermark(state)) {
            return bridgeReject(state, information, REASON_TIME);
        }
        DocumentVersion approved = draftVersion(information, story);
        if (approved == null) {
            return bridgeReject(state, information, REASON_STATE);
        }
        String publicDocId = story.publicVersion() == 0
                ? publicDocId(policy, story.storyId()) : story.publicDocId();
        InformationModel.VersionDraft draft = new InformationModel.VersionDraft(
                approved.title(), approved.body(), approved.publicSourceDescription(),
                request.completedAtMillis(), policy.publicReadMillis(),
                approved.internalSourceIds());
        InformationModel.State next;
        long version;
        try {
            if (story.publicVersion() == 0) {
                next = InformationModel.publish(information, publicDocId, story.authorPartyId(),
                        InformationModel.Audience.publicAudience(), draft);
                version = 1;
            } else {
                next = InformationModel.publishVersion(information, publicDocId, draft);
                version = story.publicVersion() + 1;
            }
        } catch (IllegalArgumentException rejected) {
            return bridgeReject(state, information, REASON_STATE);
        }

        Story updated = new Story(story.storyId(), story.template(), story.authorPartyId(),
                story.topic(), Status.PUBLISHED, story.revisionNo(), story.draftDocId(),
                story.draftVersion(), publicDocId, version, story.evidence(), story.roots(),
                story.sourceGroups(), story.approvedBy(), story.approvedAtMillis(),
                story.approvedRevision(), story.createdAtMillis(), request.completedAtMillis());
        State withLineage = append(state, updated, new Revision(story.storyId(),
                story.revisionNo(), Status.PUBLISHED, editor, request.completedAtMillis(),
                publicDocId, version, "PUBLISHED"));
        State nextState = withLineage(withLineage, new Lineage(publicDocId, version, story.roots()));
        return new BridgeOutcome(true, REASON_OK, nextState, next);
    }

    /**
     * 作者开启一轮更正：状态回到 DRAFT，旧批准立即失效。
     *
     * @param state   当前状态
     * @param policy  冻结策略
     * @param access  当前授权状态
     * @param author  作者
     * @param request 请求
     * @return 结果
     */
    public static Outcome startCorrection(State state, Policy policy, RoleAccess.State access,
                                          String author, CorrectionRequest request) {
        requireState(state);
        requirePolicy(policy);
        requireAccess(access);
        requireText(author, "作者");
        Objects.requireNonNull(request, "更正请求");
        if (!frozen(state, policy)) {
            return reject(state, REASON_POLICY_MISMATCH);
        }
        if (!authorized(access, author, Action.WRITE_STORY, policy, request.nowMillis())) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        Story story = findStory(state, request.storyId());
        if (story == null || !story.authorPartyId().equals(author)) {
            return reject(state, REASON_NOT_AUTHORIZED);
        }
        if (story.status() != Status.PUBLISHED) {
            return reject(state, REASON_STATE);
        }
        if (request.expectedRevision() != story.revisionNo()) {
            return reject(state, REASON_VERSION);
        }
        if (request.nowMillis() < watermark(state)) {
            return reject(state, REASON_TIME);
        }
        Story updated = new Story(story.storyId(), story.template(), story.authorPartyId(),
                story.topic(), Status.DRAFT, story.revisionNo(), story.draftDocId(),
                story.draftVersion(), story.publicDocId(), story.publicVersion(), story.evidence(),
                story.roots(), story.sourceGroups(), null, -1L, 0, story.createdAtMillis(),
                request.nowMillis());
        return new Outcome(true, REASON_OK, append(state, updated, new Revision(story.storyId(),
                story.revisionNo(), Status.DRAFT, author, request.nowMillis(), story.draftDocId(),
                story.draftVersion(), "CORRECTION_OPENED")));
    }

    /**
     * 本人可见的稿件投影。
     *
     * <p>可见性只看<b>冻结媒体组织</b>：本人稿件要求当前记者任职，审阅/发布要求当前编辑任职；
     * 别的机构即使有同类岗位也不可见，作者离任后也看不到当前未公开工作流。草稿正文仅在本人真正
     * 读完该固定版本<b>且</b>它仍在当前回忆结果中时返回。</p>
     *
     * @param state       当前状态
     * @param access      当前授权状态
     * @param information 当前信息状态
     * @param person      查询人物
     * @param nowMillis   查询时点
     * @return 可见稿件
     */
    public static List<StoryView> view(State state, RoleAccess.State access,
                                       InformationModel.State information, String person,
                                       long nowMillis) {
        requireState(state);
        requireAccess(access);
        requireInformation(information);
        requireText(person, "查询人物");
        if (nowMillis < 0) {
            throw new IllegalArgumentException("查询时点不能为负：" + nowMillis);
        }
        Policy policy = state.policy();
        String organization = policy.mediaOrganizationPartyId();
        boolean editor = authorizedAt(access, person, Action.REVIEW_STORY, organization, nowMillis)
                || authorizedAt(access, person, Action.PUBLISH_STORY, organization, nowMillis);
        boolean journalist = authorizedAt(access, person, Action.WRITE_STORY, organization,
                nowMillis);
        List<StoryView> views = new ArrayList<>();
        for (Story story : state.stories()) {
            if (story.createdAtMillis() > nowMillis || story.updatedAtMillis() > nowMillis) {
                // 未来才存在的稿件不在当前时点投影，避免提前泄露未发生的工作流
                continue;
            }
            boolean visible = (journalist && story.authorPartyId().equals(person)) || editor;
            if (!visible) {
                continue;
            }
            boolean read = story.draftVersion() > 0
                    && InformationModel.canCite(information, person, story.draftDocId(),
                    story.draftVersion(), nowMillis)
                    && recalled(information, person, story.draftDocId(), story.draftVersion(),
                    nowMillis, policy.recallPolicy());
            String body = null;
            if (read) {
                DocumentVersion version = versionOf(information, story.draftDocId(),
                        story.draftVersion());
                body = version == null ? null : version.body();
            }
            views.add(new StoryView(story.storyId(), story.template(), story.authorPartyId(),
                    story.topic(), story.status(), story.revisionNo(), story.draftDocId(),
                    story.draftVersion(), read, body, story.sourceGroups(), story.publicDocId(),
                    story.publicVersion(), story.approvedBy(), story.updatedAtMillis()));
        }
        return List.copyOf(views);
    }

    /**
     * 序列化媒体状态；集合顺序已在构造期固定。
     *
     * @param state 媒体状态
     * @return JSON 文本
     */
    public static String toJson(State state) {
        requireState(state);
        try {
            return MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("媒体状态无法序列化", failure);
        }
    }

    /**
     * 从 JSON 还原媒体状态；构造期校验仍生效。
     *
     * @param json JSON 文本
     * @return 还原状态
     */
    public static State fromJson(String json) {
        requireText(json, "媒体状态 JSON");
        try {
            return MAPPER.readValue(json, State.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("媒体状态 JSON 无法还原", failure);
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 提交的策略必须与状态冻结的策略完全相等。 */
    private static boolean frozen(State state, Policy policy) {
        return state.policy().equals(policy);
    }

    private static boolean authorized(RoleAccess.State access, String person, Action action,
                                      Policy policy, long now) {
        return authorizedAt(access, person, action, policy.mediaOrganizationPartyId(), now);
    }

    private static boolean authorizedAt(RoleAccess.State access, String person, Action action,
                                        String organization, long now) {
        if (now < 0) {
            return false;
        }
        return RoleAccess.authorize(access, person, action, Target.organization(organization), now)
                == Decision.ALLOW;
    }

    private static boolean windowOk(long startedAt, long completedAt, long millis) {
        if (startedAt < 0 || completedAt < 0 || completedAt < startedAt) {
            return false;
        }
        return completedAt - startedAt == millis;
    }

    /** 该固定版本是否仍在本人当前回忆结果中。 */
    private static boolean recalled(InformationModel.State information, String person, String docId,
                                    long version, long now, CognitionRecall.Policy policy) {
        for (CognitionRecall.Item item : CognitionRecall.recall(information, person, now, policy)
                .selected()) {
            if (item.observation().docId().equals(docId)
                    && item.observation().version() == version) {
                return true;
            }
        }
        return false;
    }

    /**
     * 状态时间水位：已记录事件的最大模拟时刻。
     *
     * <p>任何改动都不得早于它，否则同一状态会被"补写过去"，让后加入的稿件看似发生在已有事件之前。</p>
     */
    static long watermark(State state) {
        long latest = 0L;
        for (Revision revision : state.revisions()) {
            latest = Math.max(latest, revision.atMillis());
        }
        for (Story story : state.stories()) {
            latest = Math.max(latest, story.updatedAtMillis());
        }
        return latest;
    }

    /** 稿件作者的只读查询；未知稿件返回 null（不泄露存在性）。 */
    private static String authorOf(State state, String storyId) {
        Story story = findStory(state, storyId);
        return story == null ? null : story.authorPartyId();
    }

    /** 证据校验：返回 null 表示通过。旧入口只允许公开证据。 */
    private static String evidenceProblem(InformationModel.State information, Policy policy,
                                          String author, List<ReadingRef> evidence, long now) {
        return evidenceProblem(information, policy, author, evidence, List.of(), now);
    }

    /**
     * 证据校验（含私有证据）：公开证据必须 PUBLIC；私有证据必须非 PUBLIC，且作者真实读过、
     * 版本一致、序号一致、当前可回忆。两者合并后不得为空。
     */
    private static String evidenceProblem(InformationModel.State information, Policy policy,
                                          String author, List<ReadingRef> evidence,
                                          List<ReadingRef> privateEvidence, long now) {
        List<ReadingRef> privateRefs = privateEvidence == null ? List.of() : privateEvidence;
        if (evidence.isEmpty() && privateRefs.isEmpty()) {
            return REASON_EVIDENCE;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ReadingRef ref : evidence) {
            if (ref == null || !author.equals(ref.personPartyId()) || ref.version() < 1
                    || ref.index() < 1) {
                return REASON_EVIDENCE;
            }
            if (!seen.add(ref.docId() + '\u0000' + ref.version())) {
                return REASON_EVIDENCE;
            }
        }
        for (ReadingRef ref : privateRefs) {
            if (ref == null || !author.equals(ref.personPartyId()) || ref.version() < 1
                    || ref.index() < 1) {
                return REASON_EVIDENCE;
            }
            if (!seen.add(ref.docId() + '\u0000' + ref.version())) {
                return REASON_EVIDENCE;
            }
        }
        Set<String> recalled = new LinkedHashSet<>();
        CognitionRecall.Result recall = CognitionRecall.recall(information, author, now,
                policy.recallPolicy());
        for (CognitionRecall.Item item : recall.selected()) {
            recalled.add(item.observation().docId() + '\u0000' + item.observation().version());
        }
        for (ReadingRef ref : evidence) {
            // 旧次序：先判公开性，再判是否读过；拒绝码会被旧档案引用，不能换
            Document document = documentOf(information, ref.docId());
            if (document == null) {
                return REASON_EVIDENCE_NOT_READ;
            }
            if (document.audience().kind() != AudienceKind.PUBLIC) {
                return REASON_EVIDENCE_NOT_PUBLIC;
            }
            String problem = citationProblem(information, author, ref, recalled, now);
            if (problem != null) {
                return problem;
            }
        }
        for (ReadingRef ref : privateRefs) {
            String problem = citationProblem(information, author, ref, recalled, now);
            if (problem != null) {
                return problem;
            }
            if (documentOf(information, ref.docId()).audience().kind() == AudienceKind.PUBLIC) {
                return REASON_EVIDENCE;
            }
        }
        return null;
    }

    /** 单条引用的通用校验：存在、已读、序号一致、可回忆。 */
    private static String citationProblem(InformationModel.State information, String author,
                                          ReadingRef ref, Set<String> recalled, long now) {
        Document document = documentOf(information, ref.docId());
        if (document == null || document.at(ref.version()) == null) {
            return REASON_EVIDENCE_NOT_READ;
        }
        if (!InformationModel.canCite(information, author, ref.docId(), ref.version(), now)) {
            return REASON_EVIDENCE_NOT_READ;
        }
        if (ordinalOf(information, author, ref.docId(), ref.version()) != ref.index()) {
            return REASON_EVIDENCE_INDEX;
        }
        if (!recalled.contains(ref.docId() + '\u0000' + ref.version())) {
            return REASON_EVIDENCE_NOT_RECALLABLE;
        }
        return null;
    }

    /** 身份共享文档到真实根的绑定视图（不新增谱系表，只读既有 shares）。 */
    private record IdentityBinding(String sharingDocId, long sharingVersion, Ref root) {
    }

    /**
     * 包内入口：沿既有绑定把引用解析成真实根，供证据侧复用，不复制解析实现。
     *
     * @param shares      证据状态（可为空）
     * @param information 当前信息状态
     * @param ref         待解析引用（可能是共享文档）
     * @return 真实根
     * @throws IllegalArgumentException 绑定缺失、版本不符、成环或时间不合法
     */
    static Ref canonicalRoot(MediaEvidence.State shares, InformationModel.State information,
                             Ref ref) {
        return resolveShared(bindingsOf(shares), information, ref);
    }

    private static Map<String, IdentityBinding> bindingsOf(MediaEvidence.State shares) {
        Map<String, IdentityBinding> map = new HashMap<>();
        if (shares == null) {
            return map;
        }
        for (MediaEvidence.IdentityShare share : shares.shares()) {
            map.put(share.sharingDocId(), new IdentityBinding(share.sharingDocId(),
                    share.sharingVersion(), share.root()));
        }
        return map;
    }

    /**
     * 沿共享绑定递归回到真实根：共享文档本身不是独立事实源。
     *
     * <p>要求绑定版本与所引版本一致、根早于共享文档、无环；遇到不一致直接判为谱系问题。</p>
     */
    private static Ref resolveShared(Map<String, IdentityBinding> bindings,
                                     InformationModel.State information, Ref ref) {
        Set<String> seen = new LinkedHashSet<>();
        Ref current = ref;
        while (true) {
            IdentityBinding binding = bindings.get(current.docId());
            if (binding == null) {
                if (MediaEvidence.isShareDocument(current.docId())) {
                    throw new IllegalArgumentException("身份共享文档缺少真实绑定");
                }
                return current;
            }
            if (binding.sharingVersion() != current.version()) {
                throw new IllegalArgumentException("共享文档版本与绑定不一致");
            }
            if (!seen.add(current.docId())) {
                throw new IllegalArgumentException("共享绑定成环");
            }
            DocumentVersion shareVersion = versionOf(information, current.docId(),
                    current.version());
            DocumentVersion rootVersion = versionOf(information, binding.root().docId(),
                    binding.root().version());
            if (shareVersion == null || rootVersion == null
                    || rootVersion.publishedAt() > shareVersion.publishedAt()) {
                throw new IllegalArgumentException("共享绑定时间不一致");
            }
            current = binding.root();
        }
    }

    /** 从受信的各媒体状态建立版本索引；公开根必须已确认。 */
    private static List<Ref> rootsFor(State state, List<State> mediaContext,
                                      InformationModel.State information, List<ReadingRef> evidence) {
        return rootsFor(state, mediaContext, information, evidence, List.of(),
                MediaEvidence.empty(), false);
    }

    /**
     * 根解析（新 schema 能力显式传入）。
     *
     * <p>公开证据仍须是公开资料或本人读过的私有引用；但从<b>已发表文章</b>谱系继承来的根
     * 是否允许非 PUBLIC，由 {@code inheritPrivate} 决定：新证据路径为 true（转述者无需读过原私证），
     * 旧 {@code submit} 为 false（严格旧行为）。身份共享文档不被当作独立事实源：引用它时沿
     * {@link MediaEvidence.State} 的真实 binding 递归回到原始根，避免两位分享者被算成两个来源。</p>
     */
    private static List<Ref> rootsFor(State state, List<State> mediaContext,
                                      InformationModel.State information, List<ReadingRef> evidence,
                                      List<ReadingRef> privateEvidence, MediaEvidence.State shares,
                                      boolean inheritPrivate) {
        List<State> contexts = new ArrayList<>(Objects.requireNonNull(mediaContext, "媒体来源上下文"));
        contexts.add(state);
        Set<String> privateKeys = new LinkedHashSet<>();
        for (ReadingRef ref : privateEvidence == null ? List.<ReadingRef>of() : privateEvidence) {
            privateKeys.add(ref.docId() + '\u0000' + ref.version());
        }
        Map<String, IdentityBinding> bindings = bindingsOf(shares);
        Set<String> organizations = new LinkedHashSet<>();
        Map<Ref, Lineage> catalog = new HashMap<>();
        for (State source : contexts) {
            if (source == null || !organizations.add(source.policy().mediaOrganizationPartyId())) {
                throw new IllegalArgumentException("媒体来源上下文重复或为空");
            }
            for (Lineage entry : source.lineage()) {
                Story publisher = source.stories().stream().filter(story ->
                        story.publicDocId().equals(entry.docId())
                                && story.publicVersion() >= entry.version()).findFirst().orElse(null);
                Document document = documentOf(information, entry.docId());
                if (publisher == null || document == null || document.at(entry.version()) == null
                        || document.audience().kind() != AudienceKind.PUBLIC
                        || !document.publisherPartyId().equals(publisher.authorPartyId())
                        || !entry.docId().equals(publicDocId(source.policy(), publisher.storyId()))
                        || catalog.putIfAbsent(new Ref(entry.docId(), entry.version()), entry) != null) {
                    throw new IllegalArgumentException("媒体来源版本未确认或冲突");
                }
            }
        }
        List<Ref> roots = new ArrayList<>();
        List<ReadingRef> allRefs = new ArrayList<>(evidence);
        allRefs.addAll(privateEvidence == null ? List.<ReadingRef>of() : privateEvidence);
        for (ReadingRef ref : allRefs) {
            boolean fromPrivate = privateKeys.contains(ref.docId() + '\u0000' + ref.version());
            Lineage lineage = catalog.get(new Ref(ref.docId(), ref.version()));
            if (lineage == null) {
                if (ref.docId().startsWith(PUBLIC_PREFIX)) {
                    throw new IllegalArgumentException("媒体成稿缺少已确认谱系");
                }
                Document document = documentOf(information, ref.docId());
                if (document == null || document.at(ref.version()) == null) {
                    throw new IllegalArgumentException("证据文档不存在");
                }
                if (!fromPrivate && document.audience().kind() != AudienceKind.PUBLIC) {
                    throw new IllegalArgumentException("公开证据必须是公开资料");
                }
                roots.add(resolveShared(bindings, information, new Ref(ref.docId(), ref.version())));
            } else {
                DocumentVersion cited = versionOf(information, ref.docId(), ref.version());
                for (Ref root : lineage.roots()) {
                    Document document = documentOf(information, root.docId());
                    DocumentVersion original = versionOf(information, root.docId(), root.version());
                    if (document == null || original == null || cited == null
                            || original.publishedAt() > cited.publishedAt()
                            || root.docId().startsWith(PUBLIC_PREFIX) || catalog.containsKey(root)) {
                        throw new IllegalArgumentException("媒体来源根未展开或晚于成稿");
                    }
                    if (!fromPrivate && !inheritPrivate
                            && document.audience().kind() != AudienceKind.PUBLIC) {
                        throw new IllegalArgumentException("媒体来源根不是公开资料");
                    }
                    roots.add(resolveShared(bindings, information, root));
                }
            }
        }
        return canonicalRefs(roots);
    }

    /** 独立来源组数：根资料的不同发布人物个数。 */
    private static int sourceGroups(InformationModel.State information, List<Ref> roots) {
        Set<String> publishers = new TreeSet<>();
        for (Ref root : roots) {
            Document document = documentOf(information, root.docId());
            if (document == null) {
                return 0;
            }
            publishers.add(document.publisherPartyId());
        }
        return publishers.size();
    }

    /** 本人阅读序号：与运行层 readingOrdinals 同一口径（READ-n 序，其后按标识）。 */
    private static int ordinalOf(InformationModel.State information, String person, String docId,
                                 long version) {
        String readId = null;
        for (ReadObservation observation : information.observations()) {
            if (observation.personPartyId().equals(person)
                    && observation.docId().equals(docId) && observation.version() == version) {
                readId = observation.readId();
                break;
            }
        }
        if (readId == null) {
            return -1;
        }
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
        ids.sort(Comparator.comparingLong(MediaModel::readSequence)
                .thenComparing(Comparator.naturalOrder()));
        return ids.indexOf(readId) + 1;
    }

    private static long readSequence(String readId) {
        if (readId == null || !readId.startsWith("READ-")) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(readId.substring("READ-".length()));
        } catch (NumberFormatException notNumeric) {
            return Long.MAX_VALUE;
        }
    }

    private static DocumentVersion draftVersion(InformationModel.State information, Story story) {
        return versionOf(information, story.draftDocId(), story.draftVersion());
    }

    private static DocumentVersion versionOf(InformationModel.State information, String docId,
                                             long version) {
        Document document = documentOf(information, docId);
        return document == null ? null : document.at(version);
    }

    private static Document documentOf(InformationModel.State information, String docId) {
        for (Document document : information.documents()) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        return null;
    }

    private static Story findStory(State state, String storyId) {
        for (Story story : state.stories()) {
            if (story.storyId().equals(storyId)) {
                return story;
            }
        }
        return null;
    }

    /** 派生草稿标识：组织长度隔开两个不透明标识，名称中的冒号不会造成碰撞。 */
    static String draftDocId(Policy policy, String storyId) {
        String organization = policy.mediaOrganizationPartyId();
        return DRAFT_PREFIX + organization.length() + ":" + organization + ':' + storyId;
    }

    /** 派生公开标识：同样带媒体组织。 */
    static String publicDocId(Policy policy, String storyId) {
        String organization = policy.mediaOrganizationPartyId();
        return PUBLIC_PREFIX + organization.length() + ":" + organization + ':' + storyId;
    }

    /** 追加或替换稿件，并追加一条记录；冻结策略原样保留。 */
    private static State append(State state, Story story, Revision revision) {
        List<Story> stories = new ArrayList<>(state.stories());
        List<Revision> revisions = new ArrayList<>(state.revisions());
        revisions.add(revision);
        for (int index = 0; index < stories.size(); index++) {
            if (stories.get(index).storyId().equals(story.storyId())) {
                stories.set(index, story);
                return new State(stories, revisions, state.lineage(), state.policy());
            }
        }
        stories.add(story);
        return new State(stories, revisions, state.lineage(), state.policy());
    }

    private static State withLineage(State state, Lineage entry) {
        List<Lineage> lineage = new ArrayList<>(state.lineage());
        for (int index = 0; index < lineage.size(); index++) {
            if (lineage.get(index).docId().equals(entry.docId())
                    && lineage.get(index).version() == entry.version()) {
                lineage.set(index, entry);
                return new State(state.stories(), state.revisions(), lineage, state.policy());
            }
        }
        lineage.add(entry);
        return new State(state.stories(), state.revisions(), lineage, state.policy());
    }

    private static List<String> sourceIdsOf(List<ReadingRef> evidence) {
        List<String> ids = new ArrayList<>();
        for (ReadingRef ref : evidence) {
            ids.add(ref.docId() + "@" + ref.version());
        }
        return ids;
    }

    private static Outcome reject(State state, String reason) {
        return new Outcome(false, reason, state);
    }

    private static BridgeOutcome bridgeReject(State state, InformationModel.State information,
                                              String reason) {
        return new BridgeOutcome(false, reason, state, information);
    }

    private static List<Ref> canonicalRefs(List<Ref> roots) {
        List<Ref> copy = new ArrayList<>(Objects.requireNonNull(roots, "根引用"));
        for (Ref root : copy) {
            Objects.requireNonNull(root, "根引用");
        }
        copy.sort(Comparator.comparing(Ref::docId).thenComparingLong(Ref::version));
        List<Ref> distinct = new ArrayList<>();
        for (Ref root : copy) {
            if (distinct.isEmpty()
                    || !distinct.get(distinct.size() - 1).equals(root)) {
                distinct.add(root);
            }
        }
        return List.copyOf(distinct);
    }

    private static List<ReadingRef> canonicalEvidence(List<ReadingRef> evidence) {
        List<ReadingRef> copy = new ArrayList<>(evidence);
        for (ReadingRef ref : copy) {
            Objects.requireNonNull(ref, "证据引用");
        }
        copy.sort(Comparator.comparing(ReadingRef::docId)
                .thenComparingLong(ReadingRef::version)
                .thenComparing(ReadingRef::personPartyId)
                .thenComparingLong(ReadingRef::index));
        return List.copyOf(copy);
    }

    private static <T> List<T> sorted(List<T> source, Comparator<T> order) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(source, "清单"));
        for (T item : copy) {
            Objects.requireNonNull(item, "清单元素");
        }
        copy.sort(order);
        return copy;
    }

    private static void requireState(State state) {
        if (state == null) {
            throw new IllegalArgumentException("媒体状态不能为空");
        }
    }

    private static void requirePolicy(Policy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("媒体策略不能为空");
        }
    }

    private static void requireAccess(RoleAccess.State access) {
        if (access == null) {
            throw new IllegalArgumentException("授权状态不能为空");
        }
    }

    private static void requireInformation(InformationModel.State information) {
        if (information == null) {
            throw new IllegalArgumentException("信息状态不能为空");
        }
    }

    private static void requirePositive(long value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + "必须为正毫秒：" + value);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
