package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import edu.cufe.auction.world.BoardModel.Dimension;
import edu.cufe.auction.world.BoardModel.EvidenceMetric;
import edu.cufe.auction.world.BoardModel.Meeting;
import edu.cufe.auction.world.BoardModel.ReviewStatus;
import edu.cufe.auction.world.BoardModel.Topic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * schema18 周期治理纯核：开局冻结政策、期末快照引用、单席评分、等权聚合与财报披露链。
 *
 * <p><b>能力边界</b>：本类只有不可变值对象与纯校验/聚合，不含 I/O、世界日志、账户、调度器、
 * 角色授权或真实法规。它不持账本、不持资料正文、不发事件、不写现金；快照只保存可重算的
 * 引用与摘要，正文与已读历史唯一留在 {@link InformationModel}。评分只形成评分，不产生预算、
 * 任免或披露批准；披露必须另走“草稿→实读→三名董事批准→授权→发布”。</p>
 *
 * <p><b>信任边界</b>：席位、实读、回忆资格、经理身份与回避都来自调用方注入的 {@link Access}。
 * 本类只验契约一致性，不证明世界人物权限；真实 World 桥必须重新绑定这些回调并单独验收。
 * {@code new State(...)} 或任意 JSON 都不是网络身份。</p>
 *
 * <p><b>口径</b>：新周期为完成期间的 1 基序号，period=n 截止 n×30 模拟日且连续不跳号；
 * 固定 3 期/季，五维权重只用 {@link Dimension#weight()}；证据 {@code asOf} 上界为
 * {@code snapshot.capturedAt}，历史计划可更早但必须在冻结引用集合内。提交/批准/签发/发布的
 * 实际行为时间必须等于唯一 {@code now}，证据时点是另一字段。聚合先精确累加各完整席分数，
 * 再除以 {@code 100 × 完整席数}，末次一次 HALF_UP 到 2 位，不把舍入后的均值塞回
 * {@link EvidenceMetric} 造成二次舍入。缺维席不补 0、不改权重。合法提交后即使遗忘或卸任，
 * 已发生的有效选票不被关会抹掉。</p>
 */
public final class PeriodicModel {

    /** 固定期间长度：30 模拟日。 */
    public static final long PERIOD_MILLIS = 30L * 86_400_000L;

    /** 固定每期考核。 */
    public static final int REVIEW_EVERY_PERIODS = 1;

    /** 固定每 3 期一次正式（季度）考核。 */
    public static final int FORMAL_EVERY_PERIODS = 3;

    /** 聚合所需的完整有效席下限。 */
    public static final int MIN_COMPLETE_SEATS = 3;

    /** 披露授权的唯一用途。 */
    public static final String PURPOSE_DISCLOSE = "DISCLOSE";

    /** 成功。 */
    public static final String REASON_OK = "OK";
    /** 公司不在本状态。 */
    public static final String REASON_UNKNOWN_COMPANY = "UNKNOWN_COMPANY";
    /** 该期尚未捕获快照。 */
    public static final String REASON_NOT_CAPTURED = "NOT_CAPTURED";
    /** 同期快照已存在且内容不同。 */
    public static final String REASON_PERIOD_ALREADY_CAPTURED = "PERIOD_ALREADY_CAPTURED";
    /** 新期间早于已有期间或时间倒退。 */
    public static final String REASON_PERIOD_NOT_MONOTONIC = "PERIOD_NOT_MONOTONIC";
    /** 期间必须从 1 开始且连续，不接受 0 或跳号。 */
    public static final String REASON_PERIOD_NOT_SEQUENTIAL = "PERIOD_NOT_SEQUENTIAL";
    /** capturedAt 必须等于 period × 30 日。 */
    public static final String REASON_CAPTURED_AT_MISMATCH = "CAPTURED_AT_MISMATCH";
    /** 期间乘 30 日溢出。 */
    public static final String REASON_PERIOD_OVERFLOW = "PERIOD_OVERFLOW";
    /** 会议缺失或与快照不一致。 */
    public static final String REASON_NOT_MEETING = "NOT_MEETING";
    /** 评分所指会议与快照会议不一致。 */
    public static final String REASON_MEETING_MISMATCH = "MEETING_MISMATCH";
    /** 议题不是经营考核。 */
    public static final String REASON_WRONG_TOPIC = "WRONG_TOPIC";
    /** 材料尚未冻结。 */
    public static final String REASON_MATERIALS_NOT_FROZEN = "MATERIALS_NOT_FROZEN";
    /** 未到开会时刻。 */
    public static final String REASON_NOT_DUE = "NOT_DUE";
    /** 已到/晚于表决截止。 */
    public static final String REASON_REVIEW_WINDOW_CLOSED = "REVIEW_WINDOW_CLOSED";
    /** 不是当时真实章程席位。 */
    public static final String REASON_NOT_SEAT_HOLDER = "NOT_SEAT_HOLDER";
    /** 声明席位与本人实际席位不一致。 */
    public static final String REASON_SEAT_MISMATCH = "SEAT_MISMATCH";
    /** 未到会。 */
    public static final String REASON_NOT_ATTENDING = "NOT_ATTENDING";
    /** 已回避。 */
    public static final String REASON_SEAT_RECUSED = "SEAT_RECUSED";
    /** 材料确切版本不一致。 */
    public static final String REASON_MATERIAL_VERSION_MISMATCH = "MATERIAL_VERSION_MISMATCH";
    /** 未真正读完材料确切版本。 */
    public static final String REASON_NOT_READ_EXACT_VERSION = "NOT_READ_EXACT_VERSION";
    /** 无回忆资格。 */
    public static final String REASON_NOT_RECALL_ELIGIBLE = "NOT_RECALL_ELIGIBLE";
    /** 证据时点晚于允许截止。 */
    public static final String REASON_FUTURE_EVIDENCE = "FUTURE_EVIDENCE";
    /** 提交/关会/已存票发生在期末采样之前，不能消费未来快照。 */
    public static final String REASON_BEFORE_CAPTURE = "BEFORE_CAPTURE";
    /** 证据来源不在本期冻结引用内。 */
    public static final String REASON_UNKNOWN_SOURCE = "UNKNOWN_SOURCE";
    /** 本席已有不同内容的评分。 */
    public static final String REASON_BALLOT_EXISTS = "BALLOT_EXISTS";
    /** 已关会计分，不能再提交评分。 */
    public static final String REASON_BALLOTS_CLOSED = "BALLOTS_CLOSED";
    /** 评分已存在且内容不同。 */
    public static final String REASON_SCORE_EXISTS = "SCORE_EXISTS";
    /** 完整席不足法定人数。 */
    public static final String REASON_NO_QUORUM = "NO_QUORUM";
    /** 不是公司经理。 */
    public static final String REASON_NOT_MANAGER = "NOT_MANAGER";
    /** 草稿快照位置早于期末快照。 */
    public static final String REASON_POSITION_BEFORE_SNAPSHOT = "POSITION_BEFORE_SNAPSHOT";
    /** 草稿绑定摘要与期末快照不一致。 */
    public static final String REASON_BASIS_MISMATCH = "BASIS_MISMATCH";
    /** 草稿标识或版本重复且内容不同。 */
    public static final String REASON_DRAFT_EXISTS = "DRAFT_EXISTS";
    /** 草稿版本必须连续递增。 */
    public static final String REASON_DRAFT_VERSION_STALE = "DRAFT_VERSION_STALE";
    /** 更正草稿正文与上一发布版完全相同。 */
    public static final String REASON_DRAFT_UNCHANGED = "DRAFT_UNCHANGED";
    /** 草稿不存在。 */
    public static final String REASON_UNKNOWN_DRAFT = "UNKNOWN_DRAFT";
    /** 草稿版本/正文摘要与审批绑定不一致。 */
    public static final String REASON_DRAFT_DIGEST_MISMATCH = "DRAFT_DIGEST_MISMATCH";
    /** 提交/批准/签发/发布的实际行为时间必须等于传入的唯一 now。 */
    public static final String REASON_BEHAVIOUR_TIME_MISMATCH = "BEHAVIOUR_TIME_MISMATCH";
    /** 授权签发早于其所引用的真实批准。 */
    public static final String REASON_ISSUED_BEFORE_APPROVAL = "ISSUED_BEFORE_APPROVAL";
    /** 新授权必须未消费。 */
    public static final String REASON_AUTHORIZATION_PRECONSUMED = "AUTHORIZATION_PRECONSUMED";
    /** 准备人不能自批。 */
    public static final String REASON_APPROVER_IS_PREPARER = "APPROVER_IS_PREPARER";
    /** 本席已有不同内容的批准。 */
    public static final String REASON_ALREADY_APPROVED = "ALREADY_APPROVED";
    /** 已生成授权，审批集合冻结。 */
    public static final String REASON_APPROVALS_CLOSED = "APPROVALS_CLOSED";
    /** 有效批准不足三名。 */
    public static final String REASON_DRAFT_NOT_APPROVED = "DRAFT_NOT_APPROVED";
    /** 授权列出的批准人与已存批准集合不一致。 */
    public static final String REASON_APPROVAL_SET_MISMATCH = "APPROVAL_SET_MISMATCH";
    /** 授权标识重复且内容不同。 */
    public static final String REASON_AUTHORIZATION_EXISTS = "AUTHORIZATION_EXISTS";
    /** 授权不存在。 */
    public static final String REASON_APPROVAL_MISSING = "APPROVAL_MISSING";
    /** 授权已过期。 */
    public static final String REASON_APPROVAL_EXPIRED = "APPROVAL_EXPIRED";
    /** 授权已被消费。 */
    public static final String REASON_APPROVAL_CONSUMED = "APPROVAL_CONSUMED";
    /** 指定发布人不是当时真实公司行为者。 */
    public static final String REASON_NOT_PUBLISHER = "NOT_PUBLISHER";
    /** 迟发标记与 MISSED 事实不一致。 */
    public static final String REASON_LATE_FLAG_MISMATCH = "LATE_FLAG_MISMATCH";
    /** 同一草稿已发布且内容不同。 */
    public static final String REASON_ALREADY_PUBLISHED = "ALREADY_PUBLISHED";
    /** 未到披露截止，不能记 MISSED。 */
    public static final String REASON_DISCLOSURE_NOT_DUE = "DISCLOSURE_NOT_DUE";
    /** 已记录 MISSED 且不能回写。 */
    public static final String REASON_MISSED_EXISTS = "MISSED_EXISTS";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private PeriodicModel() {
    }

    /**
     * 受信周期上下文：席位、实读、回忆、经理与回避。
     *
     * <p>这些回答必须来自真实桥接层；纯核只校验调用结果的一致性。{@link #NONE} 对一切询问
     * 返回否定，旧/空场景不会意外获得董事或经理权力。</p>
     */
    public interface Access {

        /** 恒定拒绝；无解析器或纯核测试的默认值。 */
        Access NONE = new Access() {
            @Override
            public String seatOf(String companyPartyId, String personPartyId, long at) {
                return null;
            }

            @Override
            public boolean hasRead(String personPartyId, String docId, long version, long at) {
                return false;
            }

            @Override
            public boolean recallEligible(String personPartyId, String docId, long version, long at) {
                return false;
            }

            @Override
            public boolean isManager(String companyPartyId, String personPartyId, long at) {
                return false;
            }

            @Override
            public boolean isRecused(String companyPartyId, String seatId, long at) {
                return false;
            }
        };

        /**
         * 该人物在 at 时刻的真实章程席位。
         *
         * @param companyPartyId 公司
         * @param personPartyId  人物
         * @param at             判定时刻
         * @return 席位标识；不是当时董事返回 null
         */
        String seatOf(String companyPartyId, String personPartyId, long at);

        /**
         * 是否在 at 之前真正读完某资料的确切版本。
         *
         * @param personPartyId 人物
         * @param docId         资料标识
         * @param version       版本
         * @param at            判定时刻
         * @return 已读完返回 true
         */
        boolean hasRead(String personPartyId, String docId, long version, long at);

        /**
         * 该人物此刻是否有回忆资格（与已读历史分开）。
         *
         * @param personPartyId 人物
         * @param docId         资料标识
         * @param version       版本
         * @param at            判定时刻
         * @return 有资格返回 true
         */
        boolean recallEligible(String personPartyId, String docId, long version, long at);

        /**
         * 该人物此刻是否该公司经理。
         *
         * @param companyPartyId 公司
         * @param personPartyId  人物
         * @param at             判定时刻
         * @return 是经理返回 true
         */
        boolean isManager(String companyPartyId, String personPartyId, long at);

        /**
         * 该席此刻是否须回避。
         *
         * @param companyPartyId 公司
         * @param seatId         席位
         * @param at             判定时刻
         * @return 须回避返回 true
         */
        boolean isRecused(String companyPartyId, String seatId, long at);
    }

    /**
     * 纯候选结果：拒绝时返回原状态，调用方不能据 accepted=false 读写半成品。
     *
     * @param accepted 是否成功
     * @param reason   结果代码
     * @param state    候选状态
     */
    public record Outcome(boolean accepted, String reason, State state) {
        /** 校验字段。 */
        public Outcome {
            Objects.requireNonNull(reason, "结果代码");
            Objects.requireNonNull(state, "候选状态");
        }
    }

    /**
     * 开局冻结的周期政策。
     *
     * @param policyId               政策标识
     * @param periodMillis           期间长度，固定 30 日
     * @param reviewEveryPeriods     考核频率，固定 1
     * @param formalEveryPeriods     正式考核频率，固定 3
     * @param materialLeadMillis     材料提前冻结时长，必须为正
     * @param reviewWindowMillis     评分窗口时长，必须为正
     * @param disclosureWindowMillis 披露截止窗口时长，必须为正
     * @param longTermPlanRef        长期投入计划引用；没有机制时仍如实记录引用
     * @param digest                 政策摘要，构造期重算校验
     */
    public record Policy(String policyId, long periodMillis, int reviewEveryPeriods,
                         int formalEveryPeriods, long materialLeadMillis, long reviewWindowMillis,
                         long disclosureWindowMillis, String longTermPlanRef, String digest) {

        /** 校验固定值、正时长与摘要。 */
        public Policy {
            requireText(policyId, "政策标识");
            if (periodMillis != PERIOD_MILLIS) {
                throw new IllegalArgumentException("期间长度固定为 " + PERIOD_MILLIS + "，不接受 " + periodMillis);
            }
            if (reviewEveryPeriods != REVIEW_EVERY_PERIODS) {
                throw new IllegalArgumentException("考核频率固定为 " + REVIEW_EVERY_PERIODS);
            }
            if (formalEveryPeriods != FORMAL_EVERY_PERIODS) {
                throw new IllegalArgumentException("正式考核频率固定为 " + FORMAL_EVERY_PERIODS);
            }
            requirePositive(materialLeadMillis, "材料提前时长");
            requirePositive(reviewWindowMillis, "评分窗口时长");
            requirePositive(disclosureWindowMillis, "披露窗口时长");
            requireText(longTermPlanRef, "长期投入计划引用");
            String expected = policyDigest(policyId, periodMillis, reviewEveryPeriods,
                    formalEveryPeriods, materialLeadMillis, reviewWindowMillis,
                    disclosureWindowMillis, longTermPlanRef);
            if (!expected.equals(digest)) {
                throw new IllegalArgumentException("政策摘要不一致");
            }
        }

        /**
         * 生成冻结政策并计算摘要。
         *
         * @param policyId               政策标识
         * @param materialLeadMillis     材料提前冻结时长
         * @param reviewWindowMillis     评分窗口时长
         * @param disclosureWindowMillis 披露截止窗口时长
         * @param longTermPlanRef        长期投入计划引用
         * @return 冻结政策
         */
        public static Policy of(String policyId, long materialLeadMillis, long reviewWindowMillis,
                                long disclosureWindowMillis, String longTermPlanRef) {
            return new Policy(policyId, PERIOD_MILLIS, REVIEW_EVERY_PERIODS, FORMAL_EVERY_PERIODS,
                    materialLeadMillis, reviewWindowMillis, disclosureWindowMillis, longTermPlanRef,
                    policyDigest(policyId, PERIOD_MILLIS, REVIEW_EVERY_PERIODS, FORMAL_EVERY_PERIODS,
                            materialLeadMillis, reviewWindowMillis, disclosureWindowMillis,
                            longTermPlanRef));
        }
    }

    /**
     * 期末快照：同一帧捕获的权威账引用与摘要，不复制账正文。
     *
     * @param companyPartyId   公司
     * @param period           期间序号
     * @param meetingId        绑定的考核会议
     * @param capturedAt       捕获时刻，必须精确等于 period×30 日
     * @param position         世界位置
     * @param basisDigest      期间基准摘要
     * @param statementDigest  报表摘要
     * @param materialDocId    冻结材料文档
     * @param materialVersion  材料确切版本
     * @param materialSourceRef 材料来源引用
     * @param evidenceRefs     本期允许引用的冻结证据引用，按字典序
     */
    public record Snapshot(String companyPartyId, long period, String meetingId, long capturedAt,
                           long position, String basisDigest, String statementDigest,
                           String materialDocId, long materialVersion, String materialSourceRef,
                           List<String> evidenceRefs) {

        /** 校验标识、时间、摘要与引用集合。 */
        public Snapshot {
            requireText(companyPartyId, "公司");
            requirePeriod(period);
            requireText(meetingId, "考核会议");
            requireTime(capturedAt);
            if (position < 0) {
                throw new IllegalArgumentException("世界位置不能为负：" + position);
            }
            requireDigest(basisDigest, "期间基准摘要");
            requireDigest(statementDigest, "报表摘要");
            requireText(materialDocId, "材料文档");
            if (materialVersion < 1) {
                throw new IllegalArgumentException("材料版本必须从 1 开始：" + materialVersion);
            }
            requireText(materialSourceRef, "材料来源引用");
            evidenceRefs = sortedDistinct(evidenceRefs, "证据引用");
            if (evidenceRefs.isEmpty()) {
                throw new IllegalArgumentException("证据引用不能为空");
            }
            if (!evidenceRefs.contains(materialSourceRef)) {
                throw new IllegalArgumentException("证据引用必须包含材料来源：" + materialSourceRef);
            }
        }
    }

    /**
     * 单席评分：每席最多一份、不可覆写，保留原分与理由。
     *
     * @param companyPartyId  公司
     * @param period          期间
     * @param meetingId       会议
     * @param seatId          评分席位
     * @param personPartyId   评分人物
     * @param submittedAt     提交时刻
     * @param position        世界位置
     * @param readId          实际阅读标识
     * @param materialDocId   所读材料文档
     * @param materialVersion 所读材料版本
     * @param metrics         五维评分与各自证据引用
     * @param rationale       理由/异议，可为空串
     */
    public record Ballot(String companyPartyId, long period, String meetingId, String seatId,
                         String personPartyId, long submittedAt, long position, String readId,
                         String materialDocId, long materialVersion,
                         List<EvidenceMetric> metrics, String rationale) {

        /** 校验字段、维度唯一与分数范围，并规范化证据顺序。 */
        public Ballot {
            requireText(companyPartyId, "公司");
            requirePeriod(period);
            requireText(meetingId, "会议");
            requireText(seatId, "席位");
            requireText(personPartyId, "评分人物");
            requireTime(submittedAt);
            if (position < 0) {
                throw new IllegalArgumentException("世界位置不能为负：" + position);
            }
            requireText(readId, "阅读标识");
            requireText(materialDocId, "材料文档");
            if (materialVersion < 1) {
                throw new IllegalArgumentException("材料版本必须从 1 开始：" + materialVersion);
            }
            List<EvidenceMetric> list = new ArrayList<>(Objects.requireNonNull(metrics, "评分证据"));
            list.sort(Comparator.comparingInt(metric -> metric.dimension().ordinal()));
            Set<Dimension> seen = new LinkedHashSet<>();
            for (EvidenceMetric metric : list) {
                Objects.requireNonNull(metric, "评分证据");
                if (!seen.add(metric.dimension())) {
                    throw new IllegalArgumentException("评分维度重复：" + metric.dimension());
                }
                if (metric.score().compareTo(HUNDRED) > 0) {
                    throw new IllegalArgumentException("评分不得超过100：" + metric.dimension());
                }
            }
            Objects.requireNonNull(rationale, "评分理由");
            metrics = List.copyOf(list);
        }

        /**
         * 本席状态：五维齐全为 COMPLETE，否则 INCOMPLETE（不补 0）。
         *
         * @return 状态
         */
        @JsonIgnore
        public ReviewStatus reviewStatus() {
            return metrics.size() == Dimension.values().length ? ReviewStatus.COMPLETE : ReviewStatus.INCOMPLETE;
        }
    }

    /**
     * 聚合评分：保留各维精确和与完整席数，总分由唯一公式一次舍入。
     *
     * @param companyPartyId 公司
     * @param period         期间
     * @param meetingId      会议
     * @param seatCount      参与聚合的完整有效席数
     * @param sums           各维精确和，按维度定义顺序
     * @param total          总分；INCOMPLETE 时必须为 null
     * @param status         COMPLETE / INCOMPLETE
     * @param reason         OK / NO_QUORUM
     */
    public record Score(String companyPartyId, long period, String meetingId, int seatCount,
                        List<BigDecimal> sums, BigDecimal total, ReviewStatus status, String reason) {

        /** 校验席位、精确和与总分重算。 */
        public Score {
            requireText(companyPartyId, "公司");
            requirePeriod(period);
            requireText(meetingId, "会议");
            Objects.requireNonNull(status, "评分状态");
            List<BigDecimal> sumList = new ArrayList<>(Objects.requireNonNull(sums, "各维精确和"));
            if (sumList.size() != Dimension.values().length) {
                throw new IllegalArgumentException("各维精确和必须为 " + Dimension.values().length + " 项");
            }
            for (int index = 0; index < sumList.size(); index++) {
                sumList.set(index, money2(sumList.get(index), "各维精确和"));
            }
            if (status == ReviewStatus.COMPLETE) {
                if (seatCount < MIN_COMPLETE_SEATS) {
                    throw new IllegalArgumentException("完整评分不足 " + MIN_COMPLETE_SEATS + " 席");
                }
                if (!REASON_OK.equals(reason)) {
                    throw new IllegalArgumentException("完整评分原因必须为 OK");
                }
                if (total == null) {
                    throw new IllegalArgumentException("完整评分必须有总分");
                }
                BigDecimal expected = aggregateTotal(sumList, seatCount);
                if (total.compareTo(expected) != 0) {
                    throw new IllegalArgumentException("总分与各维精确和不一致：声明 "
                            + total.toPlainString() + "，应为 " + expected.toPlainString());
                }
                total = total.setScale(2, RoundingMode.UNNECESSARY);
            } else {
                if (seatCount >= MIN_COMPLETE_SEATS) {
                    throw new IllegalArgumentException("达到法定人数的评分必须为 COMPLETE");
                }
                if (!REASON_NO_QUORUM.equals(reason)) {
                    throw new IllegalArgumentException("不完整评分原因必须为 NO_QUORUM");
                }
                if (total != null) {
                    throw new IllegalArgumentException("不完整评分不能有总分");
                }
            }
            sums = List.copyOf(sumList);
        }
    }

    /**
     * 财报草稿：绑定公司/期间/截至位置/版本/正文摘要/准备人，不含正文。
     *
     * @param draftId          草稿标识
     * @param companyPartyId   公司
     * @param period           期间
     * @param docId            董事会可读草稿文档
     * @param version          草稿确切版本
     * @param bodyDigest       正文摘要
     * @param preparedBy       准备人（经理）
     * @param preparedAt       准备时刻
     * @param position         准备时世界位置
     * @param snapshotPosition 期末快照位置
     * @param basisDigest      期末快照期间基准摘要
     */
    public record Draft(String draftId, String companyPartyId, long period, String docId,
                        long version, String bodyDigest, String preparedBy, long preparedAt,
                        long position, long snapshotPosition, String basisDigest) {

        /** 校验字段、摘要与位置。 */
        public Draft {
            requireText(draftId, "草稿标识");
            requireText(companyPartyId, "公司");
            requirePeriod(period);
            requireText(docId, "草稿文档");
            if (version < 1) {
                throw new IllegalArgumentException("草稿版本必须从 1 开始：" + version);
            }
            requireDigest(bodyDigest, "正文摘要");
            requireText(preparedBy, "准备人");
            requireTime(preparedAt);
            if (position < 0 || snapshotPosition < 0) {
                throw new IllegalArgumentException("世界位置不能为负");
            }
            requireDigest(basisDigest, "期间基准摘要");
        }
    }

    /**
     * 董事批准：绑定确切草稿版本与正文摘要，一名董事一席一份。
     *
     * @param companyPartyId 公司
     * @param period         期间
     * @param draftId        草稿
     * @param draftVersion   草稿版本
     * @param bodyDigest     正文摘要
     * @param approverPersonId 批准人
     * @param approverSeatId   批准席
     * @param approvedAt     批准时刻
     * @param position       世界位置
     */
    public record Approval(String companyPartyId, long period, String draftId, long draftVersion,
                           String bodyDigest, String approverPersonId, String approverSeatId,
                           long approvedAt, long position) {

        /** 校验字段与摘要。 */
        public Approval {
            requireText(companyPartyId, "公司");
            requirePeriod(period);
            requireText(draftId, "草稿");
            if (draftVersion < 1) {
                throw new IllegalArgumentException("草稿版本必须从 1 开始：" + draftVersion);
            }
            requireDigest(bodyDigest, "正文摘要");
            requireText(approverPersonId, "批准人");
            requireText(approverSeatId, "批准席");
            requireTime(approvedAt);
            if (position < 0) {
                throw new IllegalArgumentException("世界位置不能为负：" + position);
            }
        }
    }

    /**
     * 披露授权：一次用途、绑定确切草稿与正文摘要、期限与指定发布人。
     *
     * @param authorizationId  授权标识
     * @param companyPartyId   公司
     * @param period           期间
     * @param draftId          草稿
     * @param draftVersion     草稿版本
     * @param bodyDigest       正文摘要
     * @param approverPersonIds 参与授权的批准人，按字典序
     * @param publisherPersonId 指定实际发布人
     * @param purpose          唯一用途
     * @param issuedAt         授权时刻
     * @param expiresAt        失效时刻
     * @param consumedAt       消费时刻；0 表示未消费
     */
    public record Authorization(String authorizationId, String companyPartyId, long period,
                                String draftId, long draftVersion, String bodyDigest,
                                List<String> approverPersonIds, String publisherPersonId,
                                String purpose, long issuedAt, long expiresAt, long consumedAt) {

        /** 校验用途、批准集合、期限与消费时点。 */
        public Authorization {
            requireText(authorizationId, "授权标识");
            requireText(companyPartyId, "公司");
            requirePeriod(period);
            requireText(draftId, "草稿");
            if (draftVersion < 1) {
                throw new IllegalArgumentException("草稿版本必须从 1 开始：" + draftVersion);
            }
            requireDigest(bodyDigest, "正文摘要");
            approverPersonIds = sortedDistinct(approverPersonIds, "批准人");
            if (approverPersonIds.size() < MIN_COMPLETE_SEATS) {
                throw new IllegalArgumentException("授权至少需要 " + MIN_COMPLETE_SEATS + " 名批准人");
            }
            requireText(publisherPersonId, "指定发布人");
            if (!PURPOSE_DISCLOSE.equals(purpose)) {
                throw new IllegalArgumentException("授权用途必须为 " + PURPOSE_DISCLOSE);
            }
            requireTime(issuedAt);
            if (expiresAt <= issuedAt) {
                throw new IllegalArgumentException("授权失效时刻必须晚于授权时刻");
            }
            if (consumedAt != 0 && (consumedAt < issuedAt || consumedAt > expiresAt)) {
                throw new IllegalArgumentException("授权消费时刻不在有效期内");
            }
        }
    }

    /**
     * 已发布版本：消费一次授权后的真实披露记录。
     *
     * @param authorizationId  被消费的授权
     * @param companyPartyId   公司
     * @param period           期间
     * @param draftId          草稿
     * @param draftVersion     草稿版本
     * @param docId            草稿文档
     * @param version          发布版本
     * @param bodyDigest       正文摘要
     * @param publisherPersonId 实际发布人
     * @param publishedAt      实际发布时间
     * @param late             是否迟发（保留 MISSED）
     */
    public record Release(String authorizationId, String companyPartyId, long period,
                          String draftId, long draftVersion, String docId, long version,
                          String bodyDigest, String publisherPersonId, long publishedAt,
                          boolean late) {

        /** 校验字段与版本。 */
        public Release {
            requireText(authorizationId, "授权标识");
            requireText(companyPartyId, "公司");
            requirePeriod(period);
            requireText(draftId, "草稿");
            if (draftVersion < 1 || version < 1) {
                throw new IllegalArgumentException("发布版本必须从 1 开始");
            }
            requireText(docId, "发布文档");
            requireDigest(bodyDigest, "正文摘要");
            requireText(publisherPersonId, "实际发布人");
            requireTime(publishedAt);
        }
    }

    /**
     * 错过披露截止的不可回写标记。
     *
     * @param period   期间
     * @param missedAt 记录 MISSED 的时刻
     */
    public record Missed(long period, long missedAt) {
        /** 校验期间与时刻。 */
        public Missed {
            requirePeriod(period);
            requireTime(missedAt);
            if (missedAt == 0) {
                throw new IllegalArgumentException("MISSED 时刻必须为正");
            }
        }
    }

    /**
     * 单公司周期状态：政策冻结，其余为按标识排序的不可变记录。
     *
     * @param companyPartyId 公司
     * @param policy         开局冻结政策
     * @param snapshots      期末快照
     * @param ballots        单席评分
     * @param scores         聚合评分
     * @param drafts         财报草稿
     * @param approvals      董事批准
     * @param authorizations 披露授权
     * @param releases       已发布版本
     * @param missed         MISSED 标记
     */
    public record CompanyPeriods(String companyPartyId, Policy policy, List<Snapshot> snapshots,
                                 List<Ballot> ballots, List<Score> scores, List<Draft> drafts,
                                 List<Approval> approvals, List<Authorization> authorizations,
                                 List<Release> releases, List<Missed> missed) {

        /** 排序、去重并校验跨记录绑定。 */
        public CompanyPeriods {
            requireText(companyPartyId, "公司");
            Objects.requireNonNull(policy, "周期政策");
            List<Snapshot> snapshotList = sorted(Objects.requireNonNull(snapshots, "期末快照"),
                    Comparator.comparingLong(Snapshot::period));
            requireUnique(snapshotList, Snapshot::period, "同期快照重复");
            List<Ballot> ballotList = sorted(Objects.requireNonNull(ballots, "单席评分"),
                    Comparator.comparingLong(Ballot::period).thenComparing(Ballot::seatId));
            requireUnique(ballotList, ballot -> ballot.period() + "\u0000" + ballot.seatId(),
                    "同席重复评分");
            List<Score> scoreList = sorted(Objects.requireNonNull(scores, "聚合评分"),
                    Comparator.comparingLong(Score::period));
            requireUnique(scoreList, Score::period, "同一期间重复聚合评分");
            List<Draft> draftList = sorted(Objects.requireNonNull(drafts, "财报草稿"),
                    Comparator.comparing(Draft::docId).thenComparingLong(Draft::version));
            requireUnique(draftList, Draft::draftId, "草稿标识重复");
            requireUnique(draftList, draft -> draft.docId() + "\u0000" + draft.version(),
                    "草稿文档版本重复");
            List<Approval> approvalList = sorted(Objects.requireNonNull(approvals, "董事批准"),
                    Comparator.comparing(Approval::draftId).thenComparing(Approval::approverSeatId));
            requireUnique(approvalList,
                    approval -> approval.draftId() + "\u0000" + approval.draftVersion()
                            + "\u0000" + approval.approverSeatId(), "同一草稿同席重复批准");
            List<Authorization> authorizationList = sorted(
                    Objects.requireNonNull(authorizations, "披露授权"),
                    Comparator.comparing(Authorization::authorizationId));
            requireUnique(authorizationList, Authorization::authorizationId, "授权标识重复");
            List<Release> releaseList = sorted(Objects.requireNonNull(releases, "已发布版本"),
                    Comparator.comparing(Release::draftId).thenComparingLong(Release::version));
            requireUnique(releaseList, Release::draftId, "同一草稿重复发布");
            List<Missed> missedList = sorted(Objects.requireNonNull(missed, "MISSED 标记"),
                    Comparator.comparingLong(Missed::period));
            requireUnique(missedList, Missed::period, "同一期间重复 MISSED");

            for (int index = 0; index < snapshotList.size(); index++) {
                Snapshot snapshot = snapshotList.get(index);
                if (!snapshot.companyPartyId().equals(companyPartyId)) {
                    throw new IllegalArgumentException("快照公司不一致：" + snapshot.companyPartyId());
                }
                if (snapshot.period() != index + 1L) {
                    throw new IllegalArgumentException("期末快照期间必须从 1 连续：" + snapshot.period());
                }
                long expectedCapturedAt;
                try {
                    expectedCapturedAt = Math.multiplyExact(snapshot.period(), policy.periodMillis());
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("期间截止溢出：" + snapshot.period(), overflow);
                }
                if (snapshot.capturedAt() != expectedCapturedAt) {
                    throw new IllegalArgumentException("capturedAt 必须等于 period×30日："
                            + snapshot.period());
                }
            }
            for (Ballot ballot : ballotList) {
                Snapshot snapshot = snapshotOf(snapshotList, ballot.period());
                if (!ballot.companyPartyId().equals(companyPartyId) || snapshot == null) {
                    throw new IllegalArgumentException("评分缺少同公司同期快照：" + ballot.seatId());
                }
                if (!ballot.meetingId().equals(snapshot.meetingId())
                        || !ballot.materialDocId().equals(snapshot.materialDocId())
                        || ballot.materialVersion() != snapshot.materialVersion()) {
                    throw new IllegalArgumentException("评分材料与期末快照不一致：" + ballot.seatId());
                }
                if (ballot.submittedAt() < snapshot.capturedAt()) {
                    throw new IllegalArgumentException("评分提交早于期末快照：" + ballot.seatId());
                }
                for (EvidenceMetric metric : ballot.metrics()) {
                    if (!snapshot.evidenceRefs().contains(metric.sourceRef())) {
                        throw new IllegalArgumentException("评分证据引用不在本期快照内："
                                + ballot.seatId() + " / " + metric.sourceRef());
                    }
                    if (metric.asOf() > snapshot.capturedAt() || metric.asOf() > ballot.submittedAt()) {
                        throw new IllegalArgumentException("评分证据时点晚于期末快照或提交："
                                + ballot.seatId() + " / " + metric.asOf());
                    }
                }
            }
            for (Score score : scoreList) {
                Snapshot snapshot = snapshotOf(snapshotList, score.period());
                if (!score.companyPartyId().equals(companyPartyId) || snapshot == null
                        || !score.meetingId().equals(snapshot.meetingId())) {
                    throw new IllegalArgumentException("聚合评分缺少同公司同期快照");
                }
                int completeSeats = 0;
                List<BigDecimal> exactSums = new ArrayList<>();
                for (Dimension dimension : Dimension.values()) {
                    exactSums.add(BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY));
                }
                for (Ballot ballot : ballotList) {
                    if (ballot.period() == score.period()
                            && ballot.reviewStatus() == ReviewStatus.COMPLETE) {
                        completeSeats++;
                        for (EvidenceMetric metric : ballot.metrics()) {
                            int index = metric.dimension().ordinal();
                            exactSums.set(index, exactSums.get(index).add(metric.score()));
                        }
                    }
                }
                if (score.seatCount() != completeSeats) {
                    throw new IllegalArgumentException("聚合席数与完整评分不一致：" + score.period());
                }
                for (int index = 0; index < exactSums.size(); index++) {
                    if (score.sums().get(index).compareTo(exactSums.get(index)) != 0) {
                        throw new IllegalArgumentException("聚合各维精确和与单席评分不一致："
                                + score.period());
                    }
                }
            }
            for (Draft draft : draftList) {
                Snapshot snapshot = snapshotOf(snapshotList, draft.period());
                if (!draft.companyPartyId().equals(companyPartyId) || snapshot == null) {
                    throw new IllegalArgumentException("草稿缺少同公司同期快照：" + draft.draftId());
                }
                if (draft.snapshotPosition() < snapshot.position()) {
                    throw new IllegalArgumentException("草稿快照位置早于期末快照：" + draft.draftId());
                }
                if (!draft.basisDigest().equals(snapshot.basisDigest())) {
                    throw new IllegalArgumentException("草稿期间基准与快照不一致：" + draft.draftId());
                }
                if (draft.preparedAt() < snapshot.capturedAt()) {
                    throw new IllegalArgumentException("草稿准备早于期末快照：" + draft.draftId());
                }
            }
            for (Approval approval : approvalList) {
                Draft draft = draftOf(draftList, approval.draftId(), approval.draftVersion());
                if (!approval.companyPartyId().equals(companyPartyId) || draft == null) {
                    throw new IllegalArgumentException("批准缺少同公司同版本草稿：" + approval.draftId());
                }
                if (approval.period() != draft.period()
                        || !approval.bodyDigest().equals(draft.bodyDigest())) {
                    throw new IllegalArgumentException("批准与草稿期间/摘要不一致：" + approval.draftId());
                }
                if (approval.approverPersonId().equals(draft.preparedBy())) {
                    throw new IllegalArgumentException("准备人不能批准自己的草稿：" + draft.draftId());
                }
            }
            for (Authorization authorization : authorizationList) {
                Draft draft = draftOf(draftList, authorization.draftId(), authorization.draftVersion());
                if (draft == null || !authorization.companyPartyId().equals(companyPartyId)
                        || authorization.period() != draft.period()
                        || !authorization.bodyDigest().equals(draft.bodyDigest())) {
                    throw new IllegalArgumentException("授权与草稿不一致：" + authorization.authorizationId());
                }
                List<String> stored = new ArrayList<>();
                Set<String> storedPersons = new java.util.TreeSet<>();
                for (Approval approval : approvalList) {
                    if (approval.draftId().equals(draft.draftId())
                            && approval.draftVersion() == draft.version()
                            && approval.bodyDigest().equals(draft.bodyDigest())) {
                        storedPersons.add(approval.approverPersonId());
                    }
                }
                stored.addAll(storedPersons);
                stored.sort(Comparator.naturalOrder());
                if (!stored.equals(authorization.approverPersonIds())) {
                    throw new IllegalArgumentException("授权批准集合与已存批准不一致："
                            + authorization.authorizationId());
                }
                long latestApprovedAt = Long.MIN_VALUE;
                for (Approval approval : approvalList) {
                    if (approval.draftId().equals(draft.draftId())
                            && approval.draftVersion() == draft.version()
                            && approval.bodyDigest().equals(draft.bodyDigest())) {
                        latestApprovedAt = Math.max(latestApprovedAt, approval.approvedAt());
                    }
                }
                if (latestApprovedAt != Long.MIN_VALUE
                        && authorization.issuedAt() < latestApprovedAt) {
                    throw new IllegalArgumentException("授权签发早于被引用批准："
                            + authorization.authorizationId());
                }
                if (authorization.consumedAt() != 0) {
                    boolean matched = false;
                    for (Release release : releaseList) {
                        if (release.authorizationId().equals(authorization.authorizationId())
                                && release.companyPartyId().equals(authorization.companyPartyId())
                                && release.period() == authorization.period()
                                && release.draftId().equals(authorization.draftId())
                                && release.draftVersion() == authorization.draftVersion()
                                && release.bodyDigest().equals(authorization.bodyDigest())
                                && release.publisherPersonId().equals(authorization.publisherPersonId())
                                && release.publishedAt() == authorization.consumedAt()) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        throw new IllegalArgumentException("已消费授权缺少匹配发布记录："
                                + authorization.authorizationId());
                    }
                }
            }
            for (Release release : releaseList) {
                Draft draft = draftOf(draftList, release.draftId(), release.draftVersion());
                Authorization authorization = authorizationOf(authorizationList, release.authorizationId());
                if (draft == null || authorization == null
                        || !release.companyPartyId().equals(companyPartyId)
                        || release.period() != draft.period()
                        || !release.docId().equals(draft.docId())
                        || release.version() != draft.version()
                        || !release.bodyDigest().equals(draft.bodyDigest())
                        || !release.publisherPersonId().equals(authorization.publisherPersonId())
                        || authorization.consumedAt() != release.publishedAt()
                        || release.publishedAt() >= authorization.expiresAt()) {
                    throw new IllegalArgumentException("发布记录与授权/草稿不一致：" + release.draftId());
                }
                long releaseDeadline;
                try {
                    releaseDeadline = Math.addExact(Math.multiplyExact(release.period(),
                            policy.periodMillis()), policy.disclosureWindowMillis());
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("披露截止溢出：" + release.period(), overflow);
                }
                if (release.late() != (release.publishedAt() >= releaseDeadline)) {
                    throw new IllegalArgumentException("迟发标记与真实披露截止不一致："
                            + release.draftId());
                }
                if (missedOf(missedList, release.period()).isPresent() && !release.late()) {
                    throw new IllegalArgumentException("存在 MISSED 却标记准时发布：" + release.draftId());
                }
            }
            for (Missed marker : missedList) {
                if (snapshotOf(snapshotList, marker.period()) == null) {
                    throw new IllegalArgumentException("MISSED 缺少同期快照：" + marker.period());
                }
                long missedDeadline;
                try {
                    missedDeadline = Math.addExact(Math.multiplyExact(marker.period(),
                            policy.periodMillis()), policy.disclosureWindowMillis());
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("披露截止溢出：" + marker.period(), overflow);
                }
                if (marker.missedAt() < missedDeadline) {
                    throw new IllegalArgumentException("MISSED 早于披露截止：" + marker.period());
                }
            }

            snapshots = List.copyOf(snapshotList);
            ballots = List.copyOf(ballotList);
            scores = List.copyOf(scoreList);
            drafts = List.copyOf(draftList);
            approvals = List.copyOf(approvalList);
            authorizations = List.copyOf(authorizationList);
            releases = List.copyOf(releaseList);
            missed = List.copyOf(missedList);
        }

        CompanyPeriods withSnapshots(List<Snapshot> next) {
            return new CompanyPeriods(companyPartyId, policy, next, ballots, scores, drafts,
                    approvals, authorizations, releases, missed);
        }

        CompanyPeriods withBallots(List<Ballot> next) {
            return new CompanyPeriods(companyPartyId, policy, snapshots, next, scores, drafts,
                    approvals, authorizations, releases, missed);
        }

        CompanyPeriods withScores(List<Score> next) {
            return new CompanyPeriods(companyPartyId, policy, snapshots, ballots, next, drafts,
                    approvals, authorizations, releases, missed);
        }

        CompanyPeriods withDrafts(List<Draft> next) {
            return new CompanyPeriods(companyPartyId, policy, snapshots, ballots, scores, next,
                    approvals, authorizations, releases, missed);
        }

        CompanyPeriods withApprovals(List<Approval> next) {
            return new CompanyPeriods(companyPartyId, policy, snapshots, ballots, scores, drafts,
                    next, authorizations, releases, missed);
        }

        CompanyPeriods withAuthorizations(List<Authorization> next) {
            return new CompanyPeriods(companyPartyId, policy, snapshots, ballots, scores, drafts,
                    approvals, next, releases, missed);
        }

        CompanyPeriods withReleases(List<Release> next) {
            return new CompanyPeriods(companyPartyId, policy, snapshots, ballots, scores, drafts,
                    approvals, authorizations, next, missed);
        }

        CompanyPeriods withReleaseAndAuthorizations(List<Release> nextReleases,
                                                   List<Authorization> nextAuthorizations) {
            return new CompanyPeriods(companyPartyId, policy, snapshots, ballots, scores, drafts,
                    approvals, nextAuthorizations, nextReleases, missed);
        }

        CompanyPeriods withMissed(List<Missed> next) {
            return new CompanyPeriods(companyPartyId, policy, snapshots, ballots, scores, drafts,
                    approvals, authorizations, releases, next);
        }
    }

    /**
     * 纯核总状态：每公司一份独立周期状态。
     *
     * @param companies 公司周期状态，按公司标识排序
     */
    public record State(List<CompanyPeriods> companies) {

        /** 排序并校验公司唯一。 */
        public State {
            List<CompanyPeriods> list = sorted(Objects.requireNonNull(companies, "公司周期状态"),
                    Comparator.comparing(CompanyPeriods::companyPartyId));
            requireUnique(list, CompanyPeriods::companyPartyId, "公司周期状态重复");
            companies = List.copyOf(list);
        }

        /**
         * 按公司取周期状态。
         *
         * @param companyPartyId 公司
         * @return 周期状态；不存在返回 null
         */
        public CompanyPeriods company(String companyPartyId) {
            for (CompanyPeriods periods : companies) {
                if (periods.companyPartyId().equals(companyPartyId)) {
                    return periods;
                }
            }
            return null;
        }
    }

    /**
     * 开局：为每家公司冻结同一份政策，建立空的周期状态。
     *
     * @param companyPartyIds 公司列表
     * @param policy          冻结政策
     * @return 初始状态
     */
    public static State open(List<String> companyPartyIds, Policy policy) {
        Objects.requireNonNull(policy, "周期政策");
        List<String> ids = sortedDistinct(companyPartyIds, "公司");
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("公司列表不能为空");
        }
        List<CompanyPeriods> companies = new ArrayList<>();
        for (String company : ids) {
            companies.add(new CompanyPeriods(company, policy, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of()));
        }
        return new State(companies);
    }

    /**
     * 期末捕获：新周期编号为完成期间的 1 基序号，period=n 截止 n×30 日，期间连续不跳号。
     *
     * @param state    当前状态
     * @param snapshot 期末快照
     * @return 候选结果；同期同内容重复为幂等
     */
    public static Outcome capture(State state, Snapshot snapshot) {
        requireState(state);
        Objects.requireNonNull(snapshot, "期末快照");
        CompanyPeriods periods = state.company(snapshot.companyPartyId());
        if (periods == null) {
            return rejected(state, REASON_UNKNOWN_COMPANY);
        }
        Snapshot existing = snapshotOf(periods.snapshots(), snapshot.period());
        if (existing != null) {
            return existing.equals(snapshot)
                    ? accepted(state) : rejected(state, REASON_PERIOD_ALREADY_CAPTURED);
        }
        long expectedCapturedAt;
        try {
            expectedCapturedAt = Math.multiplyExact(snapshot.period(),
                    periods.policy().periodMillis());
        } catch (ArithmeticException overflow) {
            return rejected(state, REASON_PERIOD_OVERFLOW);
        }
        long expectedPeriod = periods.snapshots().isEmpty()
                ? 1L : periods.snapshots().get(periods.snapshots().size() - 1).period() + 1L;
        if (snapshot.period() != expectedPeriod) {
            return rejected(state, REASON_PERIOD_NOT_SEQUENTIAL);
        }
        if (snapshot.capturedAt() != expectedCapturedAt) {
            return rejected(state, REASON_CAPTURED_AT_MISMATCH);
        }
        List<Snapshot> snapshots = new ArrayList<>(periods.snapshots());
        snapshots.add(snapshot);
        return accepted(withPeriods(state, periods.withSnapshots(snapshots)));
    }

    /**
     * 提交单席评分：窗口内、真实席位、到会、会议回避或 {@link Access#isRecused} 未命中、
     * 材料确切版本已读且有回忆资格；实际提交时间必须等于 {@code now}，且
     * {@code now >= snapshot.capturedAt}（不能消费未来采样）。
     *
     * <p>{@code meeting} 只按 {@link BoardModel.Meeting} 的公开字段校验窗口/到会/回避/材料；
     * 传入的会议对象是受信场景事实，本方法不证明它来自真实世界。证据 {@code asOf} 只受
     * {@code snapshot.capturedAt} 上界约束，不因早于本期起点判伪造。</p>
     *
     * @param state   当前状态
     * @param ballot  评分
     * @param meeting 考核会议
     * @param access  受信周期上下文
     * @param now     当前模拟毫秒
     * @return 候选结果；同席同内容重复为幂等
     */
    public static Outcome submitBallot(State state, Ballot ballot, Meeting meeting, Access access, long now) {
        requireState(state);
        Objects.requireNonNull(ballot, "单席评分");
        requireTime(now);
        Access context = access == null ? Access.NONE : access;
        CompanyPeriods periods = state.company(ballot.companyPartyId());
        if (periods == null) {
            return rejected(state, REASON_UNKNOWN_COMPANY);
        }
        Snapshot snapshot = snapshotOf(periods.snapshots(), ballot.period());
        if (snapshot == null) {
            return rejected(state, REASON_NOT_CAPTURED);
        }
        if (now < snapshot.capturedAt()) {
            return rejected(state, REASON_BEFORE_CAPTURE);
        }
        if (scoreOf(periods.scores(), ballot.period()) != null) {
            return rejected(state, REASON_BALLOTS_CLOSED);
        }
        if (meeting == null || !meeting.meetingId().equals(snapshot.meetingId())) {
            return rejected(state, REASON_NOT_MEETING);
        }
        if (!ballot.meetingId().equals(snapshot.meetingId())) {
            return rejected(state, REASON_MEETING_MISMATCH);
        }
        if (meeting.topic() != Topic.OPERATING_REVIEW) {
            return rejected(state, REASON_WRONG_TOPIC);
        }
        if (meeting.status() != BoardModel.MeetingStatus.MATERIALS_FROZEN) {
            return rejected(state, REASON_MATERIALS_NOT_FROZEN);
        }
        if (now < meeting.opensAt()) {
            return rejected(state, REASON_NOT_DUE);
        }
        if (now >= meeting.closesAt()) {
            return rejected(state, REASON_REVIEW_WINDOW_CLOSED);
        }
        String seat = context.seatOf(ballot.companyPartyId(), ballot.personPartyId(), now);
        if (seat == null) {
            return rejected(state, REASON_NOT_SEAT_HOLDER);
        }
        if (!seat.equals(ballot.seatId())) {
            return rejected(state, REASON_SEAT_MISMATCH);
        }
        if (!meeting.attendeeSeatIds().contains(seat)) {
            return rejected(state, REASON_NOT_ATTENDING);
        }
        if (meeting.recusedSeatIds().contains(seat)
                || context.isRecused(ballot.companyPartyId(), seat, now)) {
            return rejected(state, REASON_SEAT_RECUSED);
        }
        if (!materialBound(meeting, snapshot)) {
            return rejected(state, REASON_MATERIAL_VERSION_MISMATCH);
        }
        if (!ballot.materialDocId().equals(snapshot.materialDocId())
                || ballot.materialVersion() != snapshot.materialVersion()) {
            return rejected(state, REASON_MATERIAL_VERSION_MISMATCH);
        }
        if (ballot.submittedAt() != now) {
            return rejected(state, REASON_BEHAVIOUR_TIME_MISMATCH);
        }
        if (!context.hasRead(ballot.personPartyId(), snapshot.materialDocId(),
                snapshot.materialVersion(), now)) {
            return rejected(state, REASON_NOT_READ_EXACT_VERSION);
        }
        if (!context.recallEligible(ballot.personPartyId(), snapshot.materialDocId(),
                snapshot.materialVersion(), now)) {
            return rejected(state, REASON_NOT_RECALL_ELIGIBLE);
        }
        for (EvidenceMetric metric : ballot.metrics()) {
            if (metric.asOf() > snapshot.capturedAt() || metric.asOf() > ballot.submittedAt()) {
                return rejected(state, REASON_FUTURE_EVIDENCE);
            }
            if (!snapshot.evidenceRefs().contains(metric.sourceRef())) {
                return rejected(state, REASON_UNKNOWN_SOURCE);
            }
        }
        Ballot existing = ballotOf(periods.ballots(), ballot.period(), ballot.seatId());
        if (existing != null) {
            return existing.equals(ballot) ? accepted(state) : rejected(state, REASON_BALLOT_EXISTS);
        }
        List<Ballot> ballots = new ArrayList<>(periods.ballots());
        ballots.add(ballot);
        return accepted(withPeriods(state, periods.withBallots(ballots)));
    }

    /**
     * 关闭考核并聚合：只使用已存完整有效席，等权精确累计、末次一次舍入。
     *
     * <p>关会时点必须 {@code now >= snapshot.capturedAt} 且已到表决截止，不能凭期前会议窗口
     * 消费未来采样。本方法<b>不</b>接收 {@link Access}，也不在关会时重核当前回忆/席位：
     * 合法提交是已发生行为，之后遗忘或卸任不抹票。</p>
     *
     * @param state   当前状态
     * @param company 公司
     * @param period  期间
     * @param meeting 考核会议
     * @param now     当前模拟毫秒，必须已到表决截止
     * @return 候选结果；重复聚合同内容为幂等
     */
    public static Outcome closeReview(State state, String company, long period, Meeting meeting, long now) {
        requireState(state);
        requireText(company, "公司");
        requirePeriod(period);
        requireTime(now);
        CompanyPeriods periods = state.company(company);
        if (periods == null) {
            return rejected(state, REASON_UNKNOWN_COMPANY);
        }
        Snapshot snapshot = snapshotOf(periods.snapshots(), period);
        if (snapshot == null) {
            return rejected(state, REASON_NOT_CAPTURED);
        }
        if (now < snapshot.capturedAt()) {
            return rejected(state, REASON_BEFORE_CAPTURE);
        }
        if (meeting == null || !meeting.meetingId().equals(snapshot.meetingId())) {
            return rejected(state, REASON_NOT_MEETING);
        }
        if (meeting.status() != BoardModel.MeetingStatus.MATERIALS_FROZEN) {
            return rejected(state, REASON_MATERIALS_NOT_FROZEN);
        }
        if (now < meeting.closesAt()) {
            return rejected(state, REASON_NOT_DUE);
        }
        List<Ballot> complete = new ArrayList<>();
        for (Ballot ballot : periods.ballots()) {
            if (ballot.period() == period && ballot.reviewStatus() == ReviewStatus.COMPLETE) {
                complete.add(ballot);
            }
        }
        List<BigDecimal> sums = new ArrayList<>();
        for (Dimension dimension : Dimension.values()) {
            BigDecimal sum = ZERO;
            for (Ballot ballot : complete) {
                for (EvidenceMetric metric : ballot.metrics()) {
                    if (metric.dimension() == dimension) {
                        sum = sum.add(metric.score());
                    }
                }
            }
            sums.add(sum);
        }
        Score computed = complete.size() < MIN_COMPLETE_SEATS
                ? new Score(company, period, snapshot.meetingId(), complete.size(), sums, null,
                ReviewStatus.INCOMPLETE, REASON_NO_QUORUM)
                : new Score(company, period, snapshot.meetingId(), complete.size(), sums,
                aggregateTotal(sums, complete.size()), ReviewStatus.COMPLETE, REASON_OK);
        Score existing = scoreOf(periods.scores(), period);
        if (existing != null) {
            return existing.equals(computed) ? accepted(state) : rejected(state, REASON_SCORE_EXISTS);
        }
        List<Score> scores = new ArrayList<>(periods.scores());
        scores.add(computed);
        return accepted(withPeriods(state, periods.withScores(scores)));
    }

    /**
     * 准备财报草稿：经理本人、绑定期末快照与连续版本；{@code preparedAt} 必须等于 {@code now}。
     *
     * @param state  当前状态
     * @param draft  草稿
     * @param access 受信周期上下文
     * @param now    当前模拟毫秒
     * @return 候选结果；同草稿同内容重复为幂等
     */
    public static Outcome prepareDraft(State state, Draft draft, Access access, long now) {
        requireState(state);
        Objects.requireNonNull(draft, "财报草稿");
        requireTime(now);
        Access context = access == null ? Access.NONE : access;
        CompanyPeriods periods = state.company(draft.companyPartyId());
        if (periods == null) {
            return rejected(state, REASON_UNKNOWN_COMPANY);
        }
        Snapshot snapshot = snapshotOf(periods.snapshots(), draft.period());
        if (snapshot == null) {
            return rejected(state, REASON_NOT_CAPTURED);
        }
        if (!context.isManager(draft.companyPartyId(), draft.preparedBy(), now)) {
            return rejected(state, REASON_NOT_MANAGER);
        }
        if (draft.preparedAt() != now) {
            return rejected(state, REASON_BEHAVIOUR_TIME_MISMATCH);
        }
        if (draft.preparedAt() < snapshot.capturedAt()) {
            return rejected(state, REASON_FUTURE_EVIDENCE);
        }
        if (draft.position() < snapshot.position() || draft.snapshotPosition() < snapshot.position()) {
            return rejected(state, REASON_POSITION_BEFORE_SNAPSHOT);
        }
        if (!draft.basisDigest().equals(snapshot.basisDigest())) {
            return rejected(state, REASON_BASIS_MISMATCH);
        }
        Draft sameId = draftOf(periods.drafts(), draft.draftId());
        if (sameId != null) {
            return sameId.equals(draft) ? accepted(state) : rejected(state, REASON_DRAFT_EXISTS);
        }
        Draft sameVersion = draftOfDoc(periods.drafts(), draft.docId(), draft.version());
        if (sameVersion != null) {
            return sameVersion.equals(draft) ? accepted(state) : rejected(state, REASON_DRAFT_EXISTS);
        }
        long previous = 0;
        for (Draft existing : periods.drafts()) {
            if (existing.docId().equals(draft.docId())) {
                previous = Math.max(previous, existing.version());
            }
        }
        if (draft.version() != previous + 1) {
            return rejected(state, REASON_DRAFT_VERSION_STALE);
        }
        for (Release release : periods.releases()) {
            if (release.docId().equals(draft.docId())
                    && release.bodyDigest().equals(draft.bodyDigest())) {
                return rejected(state, REASON_DRAFT_UNCHANGED);
            }
        }
        List<Draft> drafts = new ArrayList<>(periods.drafts());
        drafts.add(draft);
        return accepted(withPeriods(state, periods.withDrafts(drafts)));
    }

    /**
     * 董事批准：真实席位、未回避、已读确切草稿版本、<b>当下</b>仍有回忆资格；准备人不能自批；
     * {@code approvedAt} 必须等于 {@code now}。{@code hasRead} 历史不等于当前
     * {@link Access#recallEligible}，审批时遗忘必须拒绝；已完成的合法批准不会被之后遗忘抹掉。
     *
     * @param state    当前状态
     * @param approval 批准
     * @param access   受信周期上下文
     * @param now      当前模拟毫秒
     * @return 候选结果；同席同内容重复为幂等
     */
    public static Outcome approve(State state, Approval approval, Access access, long now) {
        requireState(state);
        Objects.requireNonNull(approval, "董事批准");
        requireTime(now);
        Access context = access == null ? Access.NONE : access;
        CompanyPeriods periods = state.company(approval.companyPartyId());
        if (periods == null) {
            return rejected(state, REASON_UNKNOWN_COMPANY);
        }
        Draft draft = draftOf(periods.drafts(), approval.draftId(), approval.draftVersion());
        if (draft == null || !draft.companyPartyId().equals(approval.companyPartyId())
                || draft.period() != approval.period()) {
            return rejected(state, REASON_UNKNOWN_DRAFT);
        }
        if (!draft.bodyDigest().equals(approval.bodyDigest())) {
            return rejected(state, REASON_DRAFT_DIGEST_MISMATCH);
        }
        if (approval.approvedAt() != now) {
            return rejected(state, REASON_BEHAVIOUR_TIME_MISMATCH);
        }
        if (approval.approvedAt() < draft.preparedAt()) {
            return rejected(state, REASON_FUTURE_EVIDENCE);
        }
        if (approval.approverPersonId().equals(draft.preparedBy())) {
            return rejected(state, REASON_APPROVER_IS_PREPARER);
        }
        if (authorizationOfDraft(periods.authorizations(), draft.draftId()) != null) {
            return rejected(state, REASON_APPROVALS_CLOSED);
        }
        String seat = context.seatOf(approval.companyPartyId(), approval.approverPersonId(), now);
        if (seat == null || !seat.equals(approval.approverSeatId())) {
            return rejected(state, REASON_NOT_SEAT_HOLDER);
        }
        if (context.isRecused(approval.companyPartyId(), seat, now)) {
            return rejected(state, REASON_SEAT_RECUSED);
        }
        if (!context.hasRead(approval.approverPersonId(), draft.docId(), draft.version(), now)) {
            return rejected(state, REASON_NOT_READ_EXACT_VERSION);
        }
        if (!context.recallEligible(approval.approverPersonId(), draft.docId(), draft.version(), now)) {
            return rejected(state, REASON_NOT_RECALL_ELIGIBLE);
        }
        Approval existing = approvalOf(periods.approvals(), draft.draftId(), draft.version(), seat);
        if (existing != null) {
            return existing.equals(approval) ? accepted(state) : rejected(state, REASON_ALREADY_APPROVED);
        }
        List<Approval> approvals = new ArrayList<>(periods.approvals());
        approvals.add(approval);
        return accepted(withPeriods(state, periods.withApprovals(approvals)));
    }

    /**
     * 生成披露授权：至少三名已存批准、绑定确切草稿与期限、指定发布人。
     *
     * <p>{@code issuedAt} 必须等于 {@code now} 且不早于所引用批准；新授权必须未消费；
     * 有效期右开 {@code [issuedAt, expiresAt)}。</p>
     *
     * @param state         当前状态
     * @param authorization 授权
     * @param access        受信周期上下文
     * @param now           当前模拟毫秒
     * @return 候选结果；同授权同内容重复为幂等
     */
    public static Outcome authorize(State state, Authorization authorization, Access access, long now) {
        requireState(state);
        Objects.requireNonNull(authorization, "披露授权");
        requireTime(now);
        Access context = access == null ? Access.NONE : access;
        CompanyPeriods periods = state.company(authorization.companyPartyId());
        if (periods == null) {
            return rejected(state, REASON_UNKNOWN_COMPANY);
        }
        Draft draft = draftOf(periods.drafts(), authorization.draftId(), authorization.draftVersion());
        if (draft == null || !draft.companyPartyId().equals(authorization.companyPartyId())
                || draft.period() != authorization.period()) {
            return rejected(state, REASON_UNKNOWN_DRAFT);
        }
        if (!draft.bodyDigest().equals(authorization.bodyDigest())) {
            return rejected(state, REASON_DRAFT_DIGEST_MISMATCH);
        }
        if (authorization.issuedAt() != now) {
            return rejected(state, REASON_BEHAVIOUR_TIME_MISMATCH);
        }
        if (authorization.consumedAt() != 0) {
            return rejected(state, REASON_AUTHORIZATION_PRECONSUMED);
        }
        if (authorization.expiresAt() <= authorization.issuedAt()
                || authorization.expiresAt() - authorization.issuedAt()
                > periods.policy().disclosureWindowMillis()
                || now >= authorization.expiresAt()) {
            return rejected(state, REASON_APPROVAL_EXPIRED);
        }
        String publisherSeat = context.seatOf(authorization.companyPartyId(),
                authorization.publisherPersonId(), now);
        if (publisherSeat == null
                && !context.isManager(authorization.companyPartyId(),
                authorization.publisherPersonId(), now)) {
            return rejected(state, REASON_NOT_PUBLISHER);
        }
        Set<String> approverPersons = new java.util.TreeSet<>();
        long latestApprovedAt = Long.MIN_VALUE;
        for (Approval approval : periods.approvals()) {
            if (approval.draftId().equals(draft.draftId())
                    && approval.draftVersion() == draft.version()
                    && approval.bodyDigest().equals(draft.bodyDigest())) {
                approverPersons.add(approval.approverPersonId());
                latestApprovedAt = Math.max(latestApprovedAt, approval.approvedAt());
            }
        }
        List<String> approvers = new ArrayList<>(approverPersons);
        approvers.sort(Comparator.naturalOrder());
        if (approvers.size() < MIN_COMPLETE_SEATS) {
            return rejected(state, REASON_DRAFT_NOT_APPROVED);
        }
        if (authorization.issuedAt() < latestApprovedAt) {
            return rejected(state, REASON_ISSUED_BEFORE_APPROVAL);
        }
        if (!approvers.equals(authorization.approverPersonIds())) {
            return rejected(state, REASON_APPROVAL_SET_MISMATCH);
        }
        Authorization existing = authorizationOf(periods.authorizations(),
                authorization.authorizationId());
        if (existing != null) {
            return existing.equals(authorization) ? accepted(state) : rejected(state, REASON_AUTHORIZATION_EXISTS);
        }
        Authorization live = authorizationOfDraft(periods.authorizations(), draft.draftId());
        if (live != null && live.consumedAt() == 0 && now < live.expiresAt()) {
            return rejected(state, REASON_AUTHORIZATION_EXISTS);
        }
        List<Authorization> authorizations = new ArrayList<>(periods.authorizations());
        authorizations.add(authorization);
        return accepted(withPeriods(state, periods.withAuthorizations(authorizations)));
    }

    /**
     * 发布：消费一次授权，校验草稿/摘要/期限/指定发布人，{@code publishedAt} 必须等于 {@code now}。
     *
     * <p>迟报由本方法按公司期间截止加披露窗口自行判定，不依赖调用方先写 MISSED；直接迟发允许，
     * 但不伪造 MISSED 事件；已有 MISSED 保留。迟发仍需有效授权。</p>
     *
     * @param state   当前状态
     * @param release 发布记录
     * @param access  受信周期上下文
     * @param now     当前模拟毫秒
     * @return 候选结果；同草稿同内容重复为幂等
     */
    public static Outcome publish(State state, Release release, Access access, long now) {
        requireState(state);
        Objects.requireNonNull(release, "发布记录");
        requireTime(now);
        Access context = access == null ? Access.NONE : access;
        CompanyPeriods periods = state.company(release.companyPartyId());
        if (periods == null) {
            return rejected(state, REASON_UNKNOWN_COMPANY);
        }
        Authorization authorization = authorizationOf(periods.authorizations(),
                release.authorizationId());
        if (authorization == null) {
            return rejected(state, REASON_APPROVAL_MISSING);
        }
        Draft draft = draftOf(periods.drafts(), authorization.draftId(), authorization.draftVersion());
        if (draft == null
                || !release.companyPartyId().equals(authorization.companyPartyId())
                || release.period() != authorization.period()
                || !release.draftId().equals(authorization.draftId())
                || release.draftVersion() != authorization.draftVersion()
                || !release.docId().equals(draft.docId())
                || release.version() != draft.version()
                || !release.bodyDigest().equals(authorization.bodyDigest())
                || !release.publisherPersonId().equals(authorization.publisherPersonId())) {
            return rejected(state, REASON_DRAFT_DIGEST_MISMATCH);
        }
        if (release.publishedAt() != now) {
            return rejected(state, REASON_FUTURE_EVIDENCE);
        }
        if (now < authorization.issuedAt()) {
            return rejected(state, REASON_NOT_DUE);
        }
        if (now >= authorization.expiresAt()) {
            return rejected(state, REASON_APPROVAL_EXPIRED);
        }
        String publisherSeat = context.seatOf(release.companyPartyId(),
                release.publisherPersonId(), now);
        if (publisherSeat == null
                && !context.isManager(release.companyPartyId(), release.publisherPersonId(), now)) {
            return rejected(state, REASON_NOT_PUBLISHER);
        }
        long deadline;
        try {
            deadline = Math.addExact(Math.multiplyExact(release.period(),
                    periods.policy().periodMillis()), periods.policy().disclosureWindowMillis());
        } catch (ArithmeticException overflow) {
            return rejected(state, REASON_PERIOD_OVERFLOW);
        }
        boolean lateByDeadline = now >= deadline;
        if (release.late() != lateByDeadline) {
            return rejected(state, REASON_LATE_FLAG_MISMATCH);
        }
        Missed marker = missedOf(periods.missed(), release.period()).orElse(null);
        if (marker != null && marker.missedAt() > now) {
            return rejected(state, REASON_LATE_FLAG_MISMATCH);
        }
        Release existing = releaseOf(periods.releases(), release.draftId());
        if (existing != null) {
            return existing.equals(release) ? accepted(state) : rejected(state, REASON_ALREADY_PUBLISHED);
        }
        if (authorization.consumedAt() != 0) {
            return rejected(state, REASON_APPROVAL_CONSUMED);
        }
        List<Authorization> authorizations = new ArrayList<>();
        for (Authorization each : periods.authorizations()) {
            authorizations.add(each.authorizationId().equals(authorization.authorizationId())
                    ? new Authorization(each.authorizationId(), each.companyPartyId(), each.period(),
                    each.draftId(), each.draftVersion(), each.bodyDigest(),
                    each.approverPersonIds(), each.publisherPersonId(), each.purpose(),
                    each.issuedAt(), each.expiresAt(), now)
                    : each);
        }
        List<Release> releases = new ArrayList<>(periods.releases());
        releases.add(release);
        return accepted(withPeriods(state,
                periods.withReleaseAndAuthorizations(releases, authorizations)));
    }

    /**
     * 记录错过披露截止：MISSED 不可回写，之后真实批准仍可迟发。
     *
     * @param state   当前状态
     * @param company 公司
     * @param period  期间
     * @param now     当前模拟毫秒
     * @return 候选结果；同一期间同一时刻重复为幂等
     */
    public static Outcome markMissed(State state, String company, long period, long now) {
        requireState(state);
        requireText(company, "公司");
        requirePeriod(period);
        requireTime(now);
        CompanyPeriods periods = state.company(company);
        if (periods == null) {
            return rejected(state, REASON_UNKNOWN_COMPANY);
        }
        Snapshot snapshot = snapshotOf(periods.snapshots(), period);
        if (snapshot == null) {
            return rejected(state, REASON_NOT_CAPTURED);
        }
        for (Release release : periods.releases()) {
            if (release.period() == period) {
                return rejected(state, REASON_ALREADY_PUBLISHED);
            }
        }
        long deadline;
        try {
            deadline = Math.addExact(Math.multiplyExact(period, periods.policy().periodMillis()),
                    periods.policy().disclosureWindowMillis());
        } catch (ArithmeticException overflow) {
            return rejected(state, REASON_PERIOD_OVERFLOW);
        }
        if (now < deadline) {
            return rejected(state, REASON_DISCLOSURE_NOT_DUE);
        }
        Missed existing = missedOf(periods.missed(), period).orElse(null);
        if (existing != null) {
            return existing.missedAt() == now ? accepted(state) : rejected(state, REASON_MISSED_EXISTS);
        }
        List<Missed> missed = new ArrayList<>(periods.missed());
        missed.add(new Missed(period, now));
        return accepted(withPeriods(state, periods.withMissed(missed)));
    }

    /**
     * 序列化为严格 JSON：集合顺序固定、键排序。
     *
     * @param state 状态
     * @return JSON 文本
     */
    public static String toJson(State state) {
        requireState(state);
        try {
            return MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("周期状态无法序列化", failure);
        }
    }

    /**
     * 从严格 JSON 还原；未知字段、重复字段、不一致摘要与跨记录绑定都会拒绝。
     *
     * @param json JSON 文本
     * @return 还原状态
     * @throws IllegalArgumentException 内容不合法
     */
    public static State fromJson(String json) {
        requireText(json, "周期状态 JSON");
        try {
            return MAPPER.readValue(json, State.class);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("周期状态 JSON 不合法", failure);
        }
    }

    // ---------- 内部 ----------

    private static BigDecimal aggregateTotal(List<BigDecimal> sums, int seatCount) {
        BigDecimal weighted = BigDecimal.ZERO;
        for (Dimension dimension : Dimension.values()) {
            weighted = weighted.add(sums.get(dimension.ordinal()).multiply(dimension.weight()));
        }
        return weighted.divide(HUNDRED.multiply(BigDecimal.valueOf(seatCount)), 2, RoundingMode.HALF_UP);
    }

    private static boolean materialBound(Meeting meeting, Snapshot snapshot) {
        for (BoardModel.MaterialRef material : meeting.materials()) {
            if (material.materialId().equals(snapshot.materialDocId())
                    && material.version() == snapshot.materialVersion()
                    && material.sourceRef().equals(snapshot.materialSourceRef())) {
                return true;
            }
        }
        return false;
    }

    private static Snapshot snapshotOf(List<Snapshot> snapshots, long period) {
        for (Snapshot snapshot : snapshots) {
            if (snapshot.period() == period) {
                return snapshot;
            }
        }
        return null;
    }

    private static Ballot ballotOf(List<Ballot> ballots, long period, String seatId) {
        for (Ballot ballot : ballots) {
            if (ballot.period() == period && ballot.seatId().equals(seatId)) {
                return ballot;
            }
        }
        return null;
    }

    private static Score scoreOf(List<Score> scores, long period) {
        for (Score score : scores) {
            if (score.period() == period) {
                return score;
            }
        }
        return null;
    }

    private static Draft draftOf(List<Draft> drafts, String draftId) {
        for (Draft draft : drafts) {
            if (draft.draftId().equals(draftId)) {
                return draft;
            }
        }
        return null;
    }

    private static Draft draftOf(List<Draft> drafts, String draftId, long version) {
        for (Draft draft : drafts) {
            if (draft.draftId().equals(draftId) && draft.version() == version) {
                return draft;
            }
        }
        return null;
    }

    private static Draft draftOfDoc(List<Draft> drafts, String docId, long version) {
        for (Draft draft : drafts) {
            if (draft.docId().equals(docId) && draft.version() == version) {
                return draft;
            }
        }
        return null;
    }

    private static Approval approvalOf(List<Approval> approvals, String draftId, long version,
                                       String seatId) {
        for (Approval approval : approvals) {
            if (approval.draftId().equals(draftId) && approval.draftVersion() == version
                    && approval.approverSeatId().equals(seatId)) {
                return approval;
            }
        }
        return null;
    }

    private static Authorization authorizationOf(List<Authorization> authorizations, String id) {
        for (Authorization authorization : authorizations) {
            if (authorization.authorizationId().equals(id)) {
                return authorization;
            }
        }
        return null;
    }

    private static Authorization authorizationOfDraft(List<Authorization> authorizations,
                                                      String draftId) {
        for (Authorization authorization : authorizations) {
            if (authorization.draftId().equals(draftId)) {
                return authorization;
            }
        }
        return null;
    }

    private static Release releaseOf(List<Release> releases, String draftId) {
        for (Release release : releases) {
            if (release.draftId().equals(draftId)) {
                return release;
            }
        }
        return null;
    }

    private static java.util.Optional<Missed> missedOf(List<Missed> missed, long period) {
        for (Missed marker : missed) {
            if (marker.period() == period) {
                return java.util.Optional.of(marker);
            }
        }
        return java.util.Optional.empty();
    }

    private static State withPeriods(State state, CompanyPeriods periods) {
        List<CompanyPeriods> companies = new ArrayList<>();
        for (CompanyPeriods each : state.companies()) {
            companies.add(each.companyPartyId().equals(periods.companyPartyId()) ? periods : each);
        }
        return new State(companies);
    }

    private static Outcome accepted(State state) {
        return new Outcome(true, REASON_OK, state);
    }

    private static Outcome rejected(State state, String reason) {
        return new Outcome(false, reason, state);
    }

    private static String policyDigest(String policyId, long periodMillis, int reviewEveryPeriods,
                                       int formalEveryPeriods, long materialLeadMillis,
                                       long reviewWindowMillis, long disclosureWindowMillis,
                                       String longTermPlanRef) {
        return sha256Hex(String.join("\n", policyId, Long.toString(periodMillis),
                Integer.toString(reviewEveryPeriods), Integer.toString(formalEveryPeriods),
                Long.toString(materialLeadMillis), Long.toString(reviewWindowMillis),
                Long.toString(disclosureWindowMillis), longTermPlanRef));
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private static void requireDigest(String value, String field) {
        requireText(value, field);
        if (value.length() != 64) {
            throw new IllegalArgumentException(field + "必须是 64 位十六进制摘要：" + value);
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException(field + "必须是小写十六进制：" + value);
            }
        }
    }

    private static BigDecimal money2(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + "必须精确到分：" + value, failure);
        }
        if (scaled.signum() < 0) {
            throw new IllegalArgumentException(field + "不能为负：" + value);
        }
        return scaled;
    }

    private static <T> List<T> sorted(List<T> source, Comparator<T> comparator) {
        List<T> copy = new ArrayList<>(source);
        copy.sort(comparator);
        return copy;
    }

    private static <T> void requireUnique(List<T> source,
                                          java.util.function.Function<T, Object> key,
                                          String message) {
        Set<Object> seen = new LinkedHashSet<>();
        for (T item : source) {
            if (!seen.add(key.apply(item))) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    private static List<String> sortedDistinct(List<String> source, String field) {
        List<String> copy = new ArrayList<>(Objects.requireNonNull(source, field + "不能为空"));
        for (String value : copy) {
            requireText(value, field);
        }
        copy.sort(Comparator.naturalOrder());
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).equals(copy.get(index))) {
                throw new IllegalArgumentException(field + "重复：" + copy.get(index));
            }
        }
        return List.copyOf(copy);
    }

    private static void requireState(State state) {
        Objects.requireNonNull(state, "周期状态");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }

    private static void requireTime(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("模拟时刻不能为负：" + value);
        }
    }

    private static void requirePeriod(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("期间不能为负：" + value);
        }
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + "必须为正：" + value);
        }
    }
}
