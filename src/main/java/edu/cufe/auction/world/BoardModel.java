package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * M3b.1 董事会纯核：虚构实验章程、固定席位、纯表决与预算授权、考核完整性。
 *
 * <p><b>能力边界</b>：本类只是纯规则核，不含 I/O、世界运行时、日志、角色授权、任免、任期、
 * 分红、融资、证券撮合或真实法律。它不写现金账、不产生现金或利润字段，也不声称已给 LLM 角色授信；
 * 决议通过（{@code ADOPTED}）与实施（{@code IMPLEMENTED}）是两个分开的状态，调用方不能传入
 * {@code passed=true} 伪造结果。所有参数都是小夹具，不是原型公司规则。</p>
 *
 * <p><b>时间口径</b>：所有时刻都是调用方传入的模拟毫秒，本类不读系统时钟；时序固定为
 * 创建 ≤ 材料截止 ≤ 开会 ≤ 表决窗口 {@code [opensAt, closesAt)}。</p>
 *
 * <p><b>表决口径</b>：固定 5 席、固定法定人数 {@value #QUORUM}。到会与回避是可信场景事实，
 * 冻结后不可由投票者修改；回避者不计法定人数也不行权，但法定人数<b>不会</b>因为回避而从 3 降为 1。
 * 普通事项至少 3 票赞成；本次预算各类别新上限合计超过 1000 的特别事项至少 4 票赞成。</p>
 *
 * <p><b>预算口径</b>：预算只是有限支出权限，按实验期间与类别（采购/生产人工）分账，绝不记现金、
 * 利润、库存或负债。可用额 = 上限 − 已花 − 活动承诺；新上限是绝对值，不是逐次累加。</p>
 *
 * <p><b>考核口径</b>：权重在章程冻结（经营兑现 35、现金偿债 25、风险 15、披露 15、长期投入 10），
 * 任一维度缺失即 {@code INCOMPLETE} 且总分为空，不用 0 补齐、不重新归一；真实的 0 分是有效值。</p>
 *
 * <p><b>不可变与复现</b>：{@link State} 及其全部集合在构造时深拷贝为不可修改视图；
 * {@link #toJson(State)} 输出按键排序，可被 {@link #fromJson(String)} 还原成相等状态。</p>
 */
public final class BoardModel {

    /** 自动会议召集人哨兵：只由章程自动入口与恢复重演写入，绝不冒充任何真实主席。 */
    public static final String CHARTER_AUTO = "CHARTER_AUTO";

    /** 固定有效席位数。 */
    public static final int SEAT_COUNT = 5;

    /** 固定法定人数；回避只减少有效到会，不降低该门槛。 */
    public static final int QUORUM = 3;

    /** 普通事项所需赞成票。 */
    public static final int ORDINARY_YES_VOTES = 3;

    /** 特别预算事项所需赞成票。 */
    public static final int SPECIAL_YES_VOTES = 4;

    /** 特别预算事项阈值：本次各类别新上限合计大于该值为特别事项。 */
    public static final BigDecimal SPECIAL_BUDGET_THRESHOLD = new BigDecimal("1000.00");

    /** 经营兑现维度权重。 */
    public static final BigDecimal WEIGHT_OPERATING_COMMITMENT = new BigDecimal("35");

    /** 现金偿债维度权重。 */
    public static final BigDecimal WEIGHT_CASH_SOLVENCY = new BigDecimal("25");

    /** 风险维度权重。 */
    public static final BigDecimal WEIGHT_RISK = new BigDecimal("15");

    /** 披露维度权重。 */
    public static final BigDecimal WEIGHT_DISCLOSURE = new BigDecimal("15");

    /** 长期投入维度权重。 */
    public static final BigDecimal WEIGHT_LONG_TERM_INVESTMENT = new BigDecimal("10");

    /** 动作成功。 */
    public static final String REASON_OK = "OK";
    /** 会议不存在。 */
    public static final String REASON_UNKNOWN_MEETING = "UNKNOWN_MEETING";
    /** 会议标识已存在。 */
    public static final String REASON_MEETING_EXISTS = "MEETING_EXISTS";
    /** 召集人不是章程主席。 */
    public static final String REASON_NOT_CHAIR = "NOT_CHAIR";
    /** 时点顺序不满足 创建≤材料截止≤开会&lt;表决截止。 */
    public static final String REASON_INVALID_TIMELINE = "INVALID_TIMELINE";
    /** 参数缺失或为空。 */
    public static final String REASON_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    /** 到会或回避名单不合法。 */
    public static final String REASON_INVALID_ATTENDANCE = "INVALID_ATTENDANCE";
    /** 会议或决议状态不允许该动作。 */
    public static final String REASON_INVALID_STATE = "INVALID_STATE";
    /** 材料截止已过。 */
    public static final String REASON_MATERIALS_CLOSED = "MATERIALS_CLOSED";
    /** 材料已在开会时冻结。 */
    public static final String REASON_MATERIALS_FROZEN = "MATERIALS_FROZEN";
    /** 材料尚未冻结，不能进入下一阶段。 */
    public static final String REASON_MATERIALS_NOT_FROZEN = "MATERIALS_NOT_FROZEN";
    /** 有效到会不足固定法定人数而延期。 */
    public static final String REASON_NO_QUORUM = "ADJOURNED_NO_QUORUM";
    /** 材料标识已存在（拒绝时不得覆盖旧材料）。 */
    public static final String REASON_MATERIAL_EXISTS = "MATERIAL_EXISTS";
    /** 提交了未来证据。 */
    public static final String REASON_FUTURE_EVIDENCE = "FUTURE_EVIDENCE";
    /** 预算请求已存在，不能重复提交。 */
    public static final String REASON_PROPOSAL_EXISTS = "PROPOSAL_EXISTS";
    /** 预算请求为空。 */
    public static final String REASON_EMPTY_PROPOSAL = "EMPTY_PROPOSAL";
    /** 同一期间同一类别出现重复预算行。 */
    public static final String REASON_DUPLICATE_BUDGET_LINE = "DUPLICATE_BUDGET_LINE";
    /** 议题不允许该动作。 */
    public static final String REASON_WRONG_TOPIC = "WRONG_TOPIC";
    /** 表决窗口未开始或已关闭。 */
    public static final String REASON_VOTING_CLOSED = "VOTING_CLOSED";
    /** 尚未到表决截止，不能关闭。 */
    public static final String REASON_NOT_DUE = "NOT_DUE";
    /** 该席位不是有效投票人（非成员、未到会或已回避）。 */
    public static final String REASON_NOT_ELIGIBLE_VOTER = "NOT_ELIGIBLE_VOTER";
    /** 该席位已投过票，重复票不覆盖原记录。 */
    public static final String REASON_ALREADY_VOTED = "ALREADY_VOTED";
    /** 决议未通过，不能实施。 */
    public static final String REASON_NOT_ADOPTED = "NOT_ADOPTED";
    /** 新上限低于已花加活动承诺。 */
    public static final String REASON_BUDGET_BELOW_COMMITTED = "BUDGET_BELOW_COMMITTED";
    /** 该期间类别尚未设置预算上限。 */
    public static final String REASON_BUDGET_NOT_SET = "BUDGET_NOT_SET";
    /** 超出可用预算。 */
    public static final String REASON_BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    /** 承诺标识已存在（历史唯一，释放后也不复用）。 */
    public static final String REASON_COMMITMENT_EXISTS = "COMMITMENT_EXISTS";
    /** 承诺不存在。 */
    public static final String REASON_UNKNOWN_COMMITMENT = "UNKNOWN_COMMITMENT";
    /** 承诺已结清或已释放。 */
    public static final String REASON_COMMITMENT_CLOSED = "COMMITMENT_CLOSED";
    /** 金额超出承诺未结余额。 */
    public static final String REASON_EXCEEDS_COMMITMENT = "EXCEEDS_COMMITMENT";
    /** 金额非正或精度非法。 */
    public static final String REASON_INVALID_AMOUNT = "INVALID_AMOUNT";
    /** 考核评分超出 0..100。 */
    public static final String REASON_INVALID_SCORE = "INVALID_SCORE";
    /** 考核维度重复。 */
    public static final String REASON_DUPLICATE_DIMENSION = "DUPLICATE_DIMENSION";
    /** 证据来源未在本会议材料中登记。 */
    public static final String REASON_UNKNOWN_SOURCE = "UNKNOWN_SOURCE";
    /** 该会议已有考核结果。 */
    public static final String REASON_REVIEW_EXISTS = "REVIEW_EXISTS";
    /** 该会议已有豁免提案。 */
    public static final String REASON_WAIVER_PROPOSAL_EXISTS = "WAIVER_PROPOSAL_EXISTS";
    /** 豁免授权引用不存在、字段不匹配或已失效。 */
    public static final String REASON_WAIVER_NOT_AUTHORIZED = "WAIVER_NOT_AUTHORIZED";
    /** 该豁免授权已被执行过（只能执行一次）。 */
    public static final String REASON_WAIVER_ALREADY_CONSUMED = "WAIVER_ALREADY_CONSUMED";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String AUTHORIZATION_DOMAIN = "board-dividend-v1";
    private static final String APPOINTMENT_AUTHORIZATION_DOMAIN = "manager-appointment-v1";
    private static final String WAIVER_AUTHORIZATION_DOMAIN = "debt-waiver-v1";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private BoardModel() {
    }

    /** 议题：预算、经营考核、现金分红授权、公司经理任免与具体债权豁免；各自路径互不替代。 */
    public enum Topic {
        /** 预算授权。 */
        BUDGET,
        /** 经营考核。 */
        OPERATING_REVIEW,
        /** 现金分红授权（只产生授权引用，不派息）。 */
        DIVIDEND_AUTHORIZATION,
        /** 公司经理任免授权（只产生授权引用，不写任职表）。 */
        MANAGER_APPOINTMENT,
        /** 具体债权豁免授权（只产生授权引用，不抹账；schema18 起使用）。 */
        DEBT_WAIVER
    }

    /** 预算类别，对应未来制造新承诺。 */
    public enum BudgetCategory {
        /** 采购。 */
        PROCUREMENT,
        /** 生产人工。 */
        PRODUCTION_LABOR
    }

    /** 投票选择；NO 与 ABSTAIN 同样保留为证据。 */
    public enum Vote {
        /** 赞成。 */
        YES,
        /** 反对。 */
        NO,
        /** 弃权。 */
        ABSTAIN
    }

    /** 会议与决议状态；通过不等于已实施。 */
    public enum MeetingStatus {
        /** 已召集，可接受材料与预算请求。 */
        CONVENED,
        /** 开会时材料已冻结。 */
        MATERIALS_FROZEN,
        /** 经营考核已完成评分（COMPLETE 或 INCOMPLETE），不再回到材料态。 */
        REVIEWED,
        /** 表决窗口内。 */
        VOTING,
        /** 决议通过，尚未实施。 */
        ADOPTED,
        /** 决议未通过。 */
        REJECTED,
        /** 有效到会不足法定人数而延期。 */
        ADJOURNED_NO_QUORUM,
        /** 决议已实施（预算新上限生效）。 */
        IMPLEMENTED,
        /** 实施失败，原预算不变且记录原因。 */
        IMPLEMENTATION_FAILED
    }

    /** 承诺状态。 */
    public enum CommitmentStatus {
        /** 仍有未结余额。 */
        ACTIVE,
        /** 已结清或已全额释放。 */
        CLOSED
    }

    /** 考核结果状态。 */
    public enum ReviewStatus {
        /** 五项齐全，有总分。 */
        COMPLETE,
        /** 存在缺项，总分为空。 */
        INCOMPLETE
    }

    /** 考核维度与冻结权重。 */
    public enum Dimension {
        /** 经营兑现，权重 35。 */
        OPERATING_COMMITMENT(WEIGHT_OPERATING_COMMITMENT),
        /** 现金偿债，权重 25。 */
        CASH_SOLVENCY(WEIGHT_CASH_SOLVENCY),
        /** 风险，权重 15。 */
        RISK(WEIGHT_RISK),
        /** 披露，权重 15。 */
        DISCLOSURE(WEIGHT_DISCLOSURE),
        /** 长期投入，权重 10。 */
        LONG_TERM_INVESTMENT(WEIGHT_LONG_TERM_INVESTMENT);

        private final BigDecimal weight;

        Dimension(BigDecimal weight) {
            this.weight = weight;
        }

        /**
         * 冻结权重。
         *
         * @return 权重（整数 0..100）
         */
        public BigDecimal weight() {
            return weight;
        }
    }

    /**
     * 席位：座位标识绑定一个登记人物。
     *
     * @param seatId  座位标识
     * @param partyId 绑定的登记人物 partyId
     */
    public record Seat(String seatId, String partyId) {
        /** 校验非空。 */
        public Seat {
            requireText(seatId, "座位标识");
            requireText(partyId, "座位绑定的人物");
        }
    }

    /**
     * 章程：公司组织、五席绑定、主席与固定法定人数。第一版成员固定，不模拟任免或任期。
     *
     * @param companyPartyId 公司组织 partyId（身份，不是财产 ownerId）
     * @param seats          五席绑定
     * @param chairSeatId    主席席位，必须是其中一席
     * @param quorum         法定人数，固定为 3
     */
    public record Charter(String companyPartyId, List<Seat> seats, String chairSeatId, int quorum) {

        /** 规范化席位顺序并校验五席、一人一席、主席在席与固定法定人数。 */
        public Charter {
            requireText(companyPartyId, "公司组织 partyId");
            List<Seat> seatList = new ArrayList<>(
                    Objects.requireNonNull(seats, "席位表不能为空"));
            seatList.sort(Comparator.comparing(Seat::seatId));
            if (seatList.size() != SEAT_COUNT) {
                throw new IllegalArgumentException("有效席位必须固定为 " + SEAT_COUNT
                        + " 个，实际 " + seatList.size());
            }
            List<String> seatIds = new ArrayList<>();
            List<String> partyIds = new ArrayList<>();
            for (Seat seat : seatList) {
                if (seatIds.contains(seat.seatId())) {
                    throw new IllegalArgumentException("重复座位标识：" + seat.seatId());
                }
                if (partyIds.contains(seat.partyId())) {
                    throw new IllegalArgumentException("重复席位绑定同一人物：" + seat.partyId());
                }
                seatIds.add(seat.seatId());
                partyIds.add(seat.partyId());
            }
            requireText(chairSeatId, "主席席位");
            if (!seatIds.contains(chairSeatId)) {
                throw new IllegalArgumentException("主席席位不在席位表中：" + chairSeatId);
            }
            if (quorum != QUORUM) {
                throw new IllegalArgumentException("法定人数固定为 " + QUORUM + "，不接受 " + quorum);
            }
            seats = List.copyOf(seatList);
        }

        /**
         * 按 partyId 查座位。
         *
         * @param partyId 登记人物
         * @return 座位标识；未绑定返回 null
         */
        public String seatOf(String partyId) {
            for (Seat seat : seats) {
                if (seat.partyId().equals(partyId)) {
                    return seat.seatId();
                }
            }
            return null;
        }

        /**
         * 是否为章程席位。
         *
         * @param seatId 座位标识
         * @return 是则 true
         */
        public boolean hasSeat(String seatId) {
            for (Seat seat : seats) {
                if (seat.seatId().equals(seatId)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 材料引用：明确来源、版本与信息时点，冻结后不可再提交。
     *
     * @param materialId 会议内材料标识
     * @param sourceRef  来源引用（考核证据必须对得上）
     * @param version    材料版本
     * @param asOf       信息时点（模拟毫秒），不得晚于材料截止或当前时刻
     */
    public record MaterialRef(String materialId, String sourceRef, long version, long asOf) {
        /** 校验非空、版本为正、时点非负。 */
        public MaterialRef {
            requireText(materialId, "材料标识");
            requireText(sourceRef, "材料来源引用");
            if (version <= 0) {
                throw new IllegalArgumentException("材料版本必须为正：" + version);
            }
            requireTime(asOf);
        }
    }

    /**
     * 选票：一席一票，重复投票不覆盖原记录。
     *
     * @param seatId 座位标识
     * @param vote   选择
     * @param castAt 投票时刻（模拟毫秒）
     */
    public record Ballot(String seatId, Vote vote, long castAt) {
        /** 校验字段。 */
        public Ballot {
            requireText(seatId, "选票座位");
            Objects.requireNonNull(vote, "投票选择");
            requireTime(castAt);
        }
    }

    /**
     * 预算请求行：某期间某类别的绝对新上限。
     *
     * @param period   实验期间序号
     * @param category 预算类别
     * @param newLimit 新上限（绝对值，精确到分，允许 0）
     */
    public record BudgetLine(long period, BudgetCategory category, BigDecimal newLimit) {
        /** 校验期间、类别与金额精度。 */
        public BudgetLine {
            requirePeriod(period);
            Objects.requireNonNull(category, "预算类别");
            newLimit = money2(newLimit, "预算新上限", false);
        }
    }

    /**
     * 已生效的预算上限（某期间某类别）。
     *
     * @param period   实验期间序号
     * @param category 预算类别
     * @param cap      当前上限（绝对值）
     */
    public record Budget(long period, BudgetCategory category, BigDecimal cap) {
        /** 校验期间、类别与金额精度。 */
        public Budget {
            requirePeriod(period);
            Objects.requireNonNull(category, "预算类别");
            cap = money2(cap, "预算上限", false);
        }
    }

    /**
     * 预算承诺：占用、实际花费、释放分开记录，历史标识不复用。
     *
     * @param commitmentId 业务承诺标识（运行内唯一）
     * @param period       实验期间序号
     * @param category     预算类别
     * @param reserved     已占用金额
     * @param spent        已实际花费金额
     * @param released     已释放金额
     * @param status       状态，未结余额为 0 时必须是 CLOSED
     */
    public record Commitment(String commitmentId, long period, BudgetCategory category,
                             BigDecimal reserved, BigDecimal spent, BigDecimal released,
                             CommitmentStatus status) {
        /** 校验金额非负、未结余额不越界、状态与余额一致。 */
        public Commitment {
            requireText(commitmentId, "承诺标识");
            requirePeriod(period);
            Objects.requireNonNull(category, "预算类别");
            reserved = money2(reserved, "承诺占用金额", true);
            spent = money2(spent, "承诺已花金额", false);
            released = money2(released, "承诺已释放金额", false);
            Objects.requireNonNull(status, "承诺状态");
            BigDecimal outstanding = outstandingOf(reserved, spent, released);
            CommitmentStatus expected = outstanding.signum() > 0
                    ? CommitmentStatus.ACTIVE : CommitmentStatus.CLOSED;
            if (status != expected) {
                throw new IllegalArgumentException("承诺状态与未结余额不一致：" + commitmentId
                        + " 未结 " + outstanding.toPlainString() + "，状态 " + status);
            }
        }

        /**
         * 未结余额：占用 − 已花 − 已释放。
         *
         * @return 未结金额
         */
        public BigDecimal outstanding() {
            return outstandingOf(reserved, spent, released);
        }
    }

    /**
     * 考核证据：可信已测评分 + 来源引用 + 信息时点。
     *
     * @param dimension 考核维度
     * @param score     分数 0..100，精确到分；是否越界由 {@link #calculateReview} 判定
     * @param sourceRef 来源引用，必须对得上本会议材料
     * @param asOf      信息时点（模拟毫秒）
     */
    public record EvidenceMetric(Dimension dimension, BigDecimal score, String sourceRef, long asOf) {
        /** 校验维度、评分精度与来源。 */
        public EvidenceMetric {
            Objects.requireNonNull(dimension, "考核维度");
            score = money2(score, "考核评分", false);
            requireText(sourceRef, "证据来源引用");
            requireTime(asOf);
        }
    }

    /**
     * 考核结果：只有五项齐全才有总分；缺项时总分为空并列出缺项。
     *
     * @param reviewId          考核标识（与会议一一对应）
     * @param status            COMPLETE / INCOMPLETE
     * @param total             总分 2 位；INCOMPLETE 时必须为 null
     * @param missingDimensions 缺项，按维度定义顺序排列
     * @param metrics           已采纳证据，按维度定义顺序排列
     */
    public record ReviewResult(String reviewId, ReviewStatus status, BigDecimal total,
                               List<Dimension> missingDimensions,
                               List<EvidenceMetric> metrics) {
        /**
         * 校验状态、缺项与总分的一致性，并规范化顺序。
         *
         * <p>总分不是外部真值：COMPLETE 必须五项齐全，且 {@code total} 必须等于把所附证据按冻结权重
         * 精确求和、最后只做一次 2 位 HALF_UP 的结果；INCOMPLETE 的缺项清单必须与证据实际缺项一致。
         * 因此构造与 JSON 解码都会拒绝「声明 99.00 却附 66.00 证据」这类自相矛盾数据；
         * 数据来源真实性、签名与全事件重播不在本类的信任边界内。</p>
         */
        public ReviewResult {
            requireText(reviewId, "考核标识");
            Objects.requireNonNull(status, "考核状态");
            List<Dimension> missing = new ArrayList<>(
                    Objects.requireNonNull(missingDimensions, "缺项清单"));
            missing.sort(Comparator.comparingInt(Dimension::ordinal));
            if (new LinkedHashSet<>(missing).size() != missing.size()) {
                throw new IllegalArgumentException("缺项清单重复：" + reviewId);
            }
            List<EvidenceMetric> metricList = new ArrayList<>(
                    Objects.requireNonNull(metrics, "证据清单"));
            metricList.sort(Comparator.comparingInt(metric -> metric.dimension().ordinal()));
            Set<Dimension> seen = new HashSet<>();
            for (EvidenceMetric metric : metricList) {
                if (metric.score().compareTo(HUNDRED) > 0) {
                    throw new IllegalArgumentException("考核评分不得超过100：" + reviewId
                            + " / " + metric.dimension());
                }
                if (!seen.add(metric.dimension())) {
                    throw new IllegalArgumentException("证据维度重复：" + reviewId
                            + " / " + metric.dimension());
                }
            }
            List<Dimension> actualMissing = new ArrayList<>();
            for (Dimension dimension : Dimension.values()) {
                if (!seen.contains(dimension)) {
                    actualMissing.add(dimension);
                }
            }
            if (status == ReviewStatus.INCOMPLETE) {
                if (total != null) {
                    throw new IllegalArgumentException("缺项考核不能有总分：" + reviewId);
                }
                if (actualMissing.isEmpty()) {
                    throw new IllegalArgumentException("缺项考核必须实际缺少证据：" + reviewId);
                }
                if (!missing.equals(actualMissing)) {
                    throw new IllegalArgumentException("缺项清单与证据不一致：" + reviewId
                            + " 声明缺项 " + missing + "，实际缺项 " + actualMissing);
                }
            } else {
                if (total == null || !missing.isEmpty() || !actualMissing.isEmpty()) {
                    throw new IllegalArgumentException("完整考核必须有总分、无缺项且证据齐全："
                            + reviewId + " 实际缺项 " + actualMissing);
                }
                total = total.setScale(2, RoundingMode.UNNECESSARY);
                BigDecimal expected = weightedTotal(metricList);
                if (total.compareTo(expected) != 0) {
                    throw new IllegalArgumentException("总分与所附证据不一致：" + reviewId
                            + " 声明 " + total.toPlainString()
                            + "，按权重应为 " + expected.toPlainString());
                }
            }
            missingDimensions = List.copyOf(missing);
            metrics = List.copyOf(metricList);
        }
    }

    /**
     * 已受理的分红提案：经济内容已规范，关联席位为可信场景事实。
     *
     * <p>请求本身是 D0 的原始载体；本记录构造时统一规范每股金额到 2 位、校验正值与时点顺序，
     * 因此越界或非精确金额不可能进入已受理状态。</p>
     *
     * @param request          规范化后的分红方案请求
     * @param proposerSeatId   提案席位（必须是章程席位）
     * @param relatedSeatIds   关联席位事实，按标识排序且不可变
     * @param proposedAtMillis 提案受理时点
     */
    public record DividendProposal(@JsonProperty(required = true) DividendModel.SchemeRequest request,
                                   @JsonProperty(required = true) String proposerSeatId,
                                   @JsonProperty(required = true) List<String> relatedSeatIds,
                                   @JsonProperty(required = true) long proposedAtMillis) {

        /** 规范金额、校验时点并冻结关联席位。 */
        public DividendProposal {
            request = normalizeDividendRequest(request);
            requireText(proposerSeatId, "提案席位");
            if (proposedAtMillis < 0) {
                throw new IllegalArgumentException("提案时点不能为负：" + proposerSeatId);
            }
            List<String> related = new ArrayList<>(
                    Objects.requireNonNull(relatedSeatIds, "关联席位"));
            for (String seatId : related) {
                requireText(seatId, "关联席位");
            }
            related.sort(Comparator.naturalOrder());
            for (int index = 1; index < related.size(); index++) {
                if (related.get(index - 1).equals(related.get(index))) {
                    throw new IllegalArgumentException("关联席位重复：" + related.get(index));
                }
            }
            relatedSeatIds = List.copyOf(related);
        }
    }

    /**
     * 已生成的分红授权：只表示董事会授权，不代表派息或现金腿。
     *
     * @param issuedAtMillis   授权生成时点
     * @param authorizationRef 绑定真实来源的授权引用
     */
    public record DividendResolution(@JsonProperty(required = true) long issuedAtMillis,
                                     @JsonProperty(required = true) String authorizationRef) {

        /** 校验时点与引用。 */
        public DividendResolution {
            if (issuedAtMillis < 0) {
                throw new IllegalArgumentException("授权生成时点不能为负");
            }
            requireText(authorizationRef, "授权引用");
        }
    }

    /** 任免动作：产生任职的任命，或对既有任职的撤销。 */
    public enum AppointmentAction {
        /** 任命。 */
        APPOINT,
        /** 撤销。 */
        REVOKE
    }

    /**
     * 任免请求：绑定公司、人物、任职标识、动作、精确生效时点与期限。
     *
     * <p>APPOINT 时 {@code effectiveAt} 是任职开始，{@code endsAt} 必填且大于开始；
     * REVOKE 时 {@code effectiveAt} 是撤销生效时点，{@code endsAt} 必须为空。
     * 纯核不认识真实任职表，本请求只是待董事会授权的对象。</p>
     *
     * @param appointmentId 任职标识
     * @param personPartyId 任职人物
     * @param companyPartyId 公司组织
     * @param role 岗位，只允许公司经理
     * @param action 任命或撤销
     * @param effectiveAt 任命开始或撤销生效时点
     * @param endsAt 任命结束（不含）；撤销必须为空
     */
    public record AppointmentRequest(String appointmentId, String personPartyId,
                                     String companyPartyId, RoleAccess.Role role,
                                     AppointmentAction action, long effectiveAt, Long endsAt) {

        /** 校验标识、岗位、动作与区间。 */
        public AppointmentRequest {
            requireText(appointmentId, "任职标识");
            requireText(personPartyId, "任职人物");
            requireText(companyPartyId, "公司组织");
            Objects.requireNonNull(role, "任职岗位");
            Objects.requireNonNull(action, "任免动作");
            if (role != RoleAccess.Role.COMPANY_MANAGER) {
                throw new IllegalArgumentException("任免请求只支持公司经理岗位：" + role);
            }
            if (effectiveAt < 0) {
                throw new IllegalArgumentException("生效时点不能为负");
            }
            if (action == AppointmentAction.APPOINT) {
                if (endsAt == null || endsAt <= effectiveAt) {
                    throw new IllegalArgumentException("任命期限必须为有效区间");
                }
            } else if (endsAt != null) {
                throw new IllegalArgumentException("撤销请求不得携带任命结束时刻");
            }
        }

        /** @return 是否任命；派生判断不参与 JSON 序列化 */
        @JsonIgnore
        public boolean isAppointment() {
            return action == AppointmentAction.APPOINT;
        }
    }

    /**
     * 任免提案：由真实席位在材料窗口内提出。
     *
     * @param request 任免请求
     * @param proposerSeatId 提案席位
     * @param relatedSeatIds 关联席位；候选人/被撤人若占席，由提案与状态校验自动识别，不依赖本字段
     * @param proposedAtMillis 提案时点
     */
    public record AppointmentProposal(AppointmentRequest request, String proposerSeatId,
                                      List<String> relatedSeatIds, long proposedAtMillis) {

        /** 校验请求、提案席与时间，并规范化关联席。 */
        public AppointmentProposal {
            Objects.requireNonNull(request, "任免请求");
            requireText(proposerSeatId, "提案席位");
            if (proposedAtMillis < 0) {
                throw new IllegalArgumentException("提案时点不能为负");
            }
            relatedSeatIds = sortedDistinct(relatedSeatIds == null ? List.of() : relatedSeatIds,
                    "关联席位");
        }
    }

    /**
     * 已生成的任免授权：只表示董事会授权，不代表任职已写入。
     *
     * @param issuedAtMillis 授权签发时点
     * @param authorizationRef 绑定真实来源的授权引用
     */
    public record AppointmentResolution(@JsonProperty(required = true) long issuedAtMillis,
                                        @JsonProperty(required = true) String authorizationRef) {

        /** 校验时点与引用。 */
        public AppointmentResolution {
            if (issuedAtMillis < 0) {
                throw new IllegalArgumentException("授权签发时点不能为负");
            }
            requireText(authorizationRef, "授权引用");
        }
    }

    /**
     * 具体债权豁免请求：冻结公司、合同、客户、金额与用途，董事会只能批准或否决这份原文。
     *
     * <p>本类型不解除任何债务，也不接触现金或应收；它只是待授权对象。金额精确到分且为正，
     * 用途必须写明（“一般预算许可”不构成豁免依据）。</p>
     *
     * @param waiverId       豁免标识，历史唯一
     * @param companyPartyId 公司组织
     * @param contractId     被豁免的合同
     * @param customerPartyId 被豁免的客户
     * @param amount         豁免金额，精确到分且为正
     * @param purpose        具体用途说明
     */
    public record DebtWaiverRequest(String waiverId, String companyPartyId, String contractId,
                                    String customerPartyId, BigDecimal amount, String purpose) {

        /** 校验标识、绑定对象、金额与用途。 */
        public DebtWaiverRequest {
            requireText(waiverId, "豁免标识");
            requireText(companyPartyId, "豁免公司");
            requireText(contractId, "豁免合同");
            requireText(customerPartyId, "豁免客户");
            requireText(purpose, "豁免用途");
            amount = money2(amount, "豁免金额", true);
        }
    }

    /**
     * 一条豁免决议记录：冻结的请求、会议与授权状态。
     *
     * <p>{@code issuedAtMillis} 为 null 表示董事会尚未签发授权；签发后
     * {@code authorizationRef} 可被经理一次执行，{@code consumedAtMillis} 记录唯一一次执行。</p>
     *
     * @param request            冻结请求
     * @param meetingId          决议会议
     * @param proposerSeatId     提案席位
     * @param proposedAtMillis   提案时点
     * @param issuedAtMillis     授权签发时点；未签发为 null
     * @param authorizationRef   授权引用；未签发为 null
     * @param consumedAtMillis   被经理执行时点；未执行为 null
     */
    public record DebtWaiver(DebtWaiverRequest request, String meetingId, String proposerSeatId,
                             long proposedAtMillis,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Long issuedAtMillis,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String authorizationRef,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Long consumedAtMillis) {

        /** 校验时点与授权字段一致性。 */
        public DebtWaiver {
            Objects.requireNonNull(request, "豁免请求");
            requireText(meetingId, "豁免会议");
            requireText(proposerSeatId, "豁免提案席位");
            requireTime(proposedAtMillis);
            if ((issuedAtMillis == null) != (authorizationRef == null)) {
                throw new IllegalArgumentException("授权时点与引用必须同时存在：" + meetingId);
            }
            if (issuedAtMillis != null) {
                requireTime(issuedAtMillis);
                requireText(authorizationRef, "豁免授权引用");
            }
            if (consumedAtMillis != null) {
                requireTime(consumedAtMillis);
                if (issuedAtMillis == null || consumedAtMillis < issuedAtMillis) {
                    throw new IllegalArgumentException("未被授权的豁免不能被消耗：" + meetingId);
                }
            }
        }

        /** @return 是否已签发授权 */
        @JsonIgnore
        public boolean authorized() {
            return authorizationRef != null;
        }

        /** @return 是否已被执行 */
        @JsonIgnore
        public boolean consumed() {
            return consumedAtMillis != null;
        }
    }

    /**
     * 会议：召集、材料、表决与结果原文；通过后实施状态单独推进。
     *
     * @param meetingId        会议标识
     * @param topic            议题
     * @param convenerSeatId   召集人（必须是主席）
     * @param createdAt        召集时刻
     * @param materialDeadline 材料截止
     * @param opensAt          开会时刻
     * @param closesAt         表决截止（不含）
     * @param status           状态
     * @param attendeeSeatIds  到会席位（可信场景事实）
     * @param recusedSeatIds   关联回避席位（可信场景事实）
     * @param materials        材料，按标识排序
     * @param proposedLines    预算请求行，按期间与类别排序
     * @param ballots          选票，按座位索引
     * @param failureReason    实施失败原因；只有 IMPLEMENTATION_FAILED 时非空
     * @param dividendProposal 分红提案元数据；仅分红议题使用，其余议题为 null 且不落 JSON
     * @param dividendResolution 分红授权结果；仅分红议题 IMPLEMENTED 后非 null
     */
    public record Meeting(String meetingId, Topic topic, String convenerSeatId, long createdAt,
                          long materialDeadline, long opensAt, long closesAt, MeetingStatus status,
                          List<String> attendeeSeatIds, List<String> recusedSeatIds,
                          List<MaterialRef> materials, List<BudgetLine> proposedLines,
                          Map<String, Ballot> ballots, String failureReason,
                          @JsonInclude(JsonInclude.Include.NON_NULL)
                          DividendProposal dividendProposal,
                          @JsonInclude(JsonInclude.Include.NON_NULL)
                          DividendResolution dividendResolution,
                          @JsonInclude(JsonInclude.Include.NON_NULL)
                          AppointmentProposal appointmentProposal,
                          @JsonInclude(JsonInclude.Include.NON_NULL)
                          AppointmentResolution appointmentResolution) {

        /**
         * 旧 16 参构造：保持源码兼容，两个任免元数据置空。
         *
         * @param meetingId        会议标识
         * @param topic            议题
         * @param convenerSeatId   召集席位
         * @param createdAt        召集时刻
         * @param materialDeadline 材料截止
         * @param opensAt          开会时刻
         * @param closesAt         表决截止（不含）
         * @param status           状态
         * @param attendeeSeatIds  到会席位
         * @param recusedSeatIds   回避席位
         * @param materials        材料
         * @param proposedLines    预算请求行
         * @param ballots          选票
         * @param failureReason    实施失败原因
         * @param dividendProposal 分红提案
         * @param dividendResolution 分红授权
         */
        public Meeting(String meetingId, Topic topic, String convenerSeatId, long createdAt,
                       long materialDeadline, long opensAt, long closesAt, MeetingStatus status,
                       List<String> attendeeSeatIds, List<String> recusedSeatIds,
                       List<MaterialRef> materials, List<BudgetLine> proposedLines,
                       Map<String, Ballot> ballots, String failureReason,
                       DividendProposal dividendProposal, DividendResolution dividendResolution) {
            this(meetingId, topic, convenerSeatId, createdAt, materialDeadline, opensAt, closesAt,
                    status, attendeeSeatIds, recusedSeatIds, materials, proposedLines, ballots,
                    failureReason, dividendProposal, dividendResolution, null, null);
        }

        /**
         * 旧 14 参构造：保持源码兼容，四个授权元数据置空。
         *
         * @param meetingId        会议标识
         * @param topic            议题
         * @param convenerSeatId   召集席位
         * @param createdAt        召集时刻
         * @param materialDeadline 材料截止
         * @param opensAt          开会时刻
         * @param closesAt         表决截止（不含）
         * @param status           状态
         * @param attendeeSeatIds  到会席位
         * @param recusedSeatIds   回避席位
         * @param materials        材料
         * @param proposedLines    预算请求行
         * @param ballots          选票
         * @param failureReason    实施失败原因
         */
        public Meeting(String meetingId, Topic topic, String convenerSeatId, long createdAt,
                       long materialDeadline, long opensAt, long closesAt, MeetingStatus status,
                       List<String> attendeeSeatIds, List<String> recusedSeatIds,
                       List<MaterialRef> materials, List<BudgetLine> proposedLines,
                       Map<String, Ballot> ballots, String failureReason) {
            this(meetingId, topic, convenerSeatId, createdAt, materialDeadline, opensAt, closesAt,
                    status, attendeeSeatIds, recusedSeatIds, materials, proposedLines, ballots,
                    failureReason, null, null, null, null);
        }

        /** 规范化排序、校验集合深不可变与选票索引一致性。 */
        public Meeting {
            requireText(meetingId, "会议标识");
            Objects.requireNonNull(topic, "议题");
            requireText(convenerSeatId, "召集席位");
            requireTime(createdAt);
            requireTime(materialDeadline);
            requireTime(opensAt);
            requireTime(closesAt);
            Objects.requireNonNull(status, "会议状态");
            attendeeSeatIds = sortedDistinct(attendeeSeatIds, "到会席位");
            recusedSeatIds = sortedDistinct(recusedSeatIds, "回避席位");
            List<MaterialRef> materialList = new ArrayList<>(
                    Objects.requireNonNull(materials, "材料清单"));
            materialList.sort(Comparator.comparing(MaterialRef::materialId));
            List<BudgetLine> lineList = new ArrayList<>(
                    Objects.requireNonNull(proposedLines, "预算请求行"));
            lineList.sort(Comparator.comparingLong(BudgetLine::period)
                    .thenComparing(BudgetLine::category));
            Map<String, Ballot> ballotCopy = new TreeMap<>(
                    Objects.requireNonNull(ballots, "选票"));
            for (Map.Entry<String, Ballot> entry : ballotCopy.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().seatId())) {
                    throw new IllegalArgumentException("选票索引与座位不一致：" + entry.getKey());
                }
            }
            if (status == MeetingStatus.IMPLEMENTATION_FAILED) {
                requireText(failureReason, "实施失败原因");
            } else if (failureReason != null && !failureReason.isBlank()) {
                throw new IllegalArgumentException("非失败状态不能携带失败原因：" + meetingId);
            } else {
                failureReason = null;
            }
            if (topic == Topic.DIVIDEND_AUTHORIZATION) {
                if (createdAt > materialDeadline || materialDeadline > opensAt || opensAt >= closesAt) {
                    throw new IllegalArgumentException("分红会议时序不合法：" + meetingId);
                }
                if (status == MeetingStatus.REVIEWED || status == MeetingStatus.IMPLEMENTATION_FAILED) {
                    throw new IllegalArgumentException("分红议题不支持该阶段：" + meetingId);
                }
                if (!attendeeSeatIds.containsAll(recusedSeatIds)) {
                    throw new IllegalArgumentException("回避席位必须到会：" + meetingId);
                }
                Set<String> materialIds = new HashSet<>();
                for (MaterialRef material : materialList) {
                    if (!materialIds.add(material.materialId()) || material.asOf() > materialDeadline) {
                        throw new IllegalArgumentException("分红材料重复或晚于截止：" + meetingId);
                    }
                }
                if ((status == MeetingStatus.CONVENED || status == MeetingStatus.MATERIALS_FROZEN)
                        && !ballotCopy.isEmpty()) {
                    throw new IllegalArgumentException("表决开始前不能有选票：" + meetingId);
                }
                if (!lineList.isEmpty()) {
                    throw new IllegalArgumentException("分红议题不能携带预算行：" + meetingId);
                }
                if (dividendProposal == null) {
                    if (status != MeetingStatus.CONVENED && status != MeetingStatus.MATERIALS_FROZEN
                            && status != MeetingStatus.REJECTED) {
                        throw new IllegalArgumentException("该阶段必须有分红提案：" + meetingId);
                    }
                    if (!ballotCopy.isEmpty()) {
                        throw new IllegalArgumentException("无提案不能有选票：" + meetingId);
                    }
                } else {
                    if (dividendProposal.proposedAtMillis() < createdAt
                            || dividendProposal.proposedAtMillis() > materialDeadline) {
                        throw new IllegalArgumentException("分红提案不在会议材料窗口内：" + meetingId);
                    }
                    if (closesAt > dividendProposal.request().recordAtMillis()) {
                        throw new IllegalArgumentException("表决截止晚于方案登记截止：" + meetingId);
                    }
                    for (String related : dividendProposal.relatedSeatIds()) {
                        if (attendeeSeatIds.contains(related) && !recusedSeatIds.contains(related)) {
                            throw new IllegalArgumentException("到会的关联席位未登记回避：" + related);
                        }
                    }
                }
                if (dividendResolution != null && status != MeetingStatus.IMPLEMENTED) {
                    throw new IllegalArgumentException("只有已实施会议才能携带授权：" + meetingId);
                }
                if (status == MeetingStatus.IMPLEMENTED && dividendResolution == null) {
                    throw new IllegalArgumentException("已实施会议必须有授权：" + meetingId);
                }
                if (dividendResolution != null && (dividendResolution.issuedAtMillis() < closesAt
                        || dividendResolution.issuedAtMillis() > dividendProposal.request().recordAtMillis())) {
                    throw new IllegalArgumentException("授权生成不在有效窗口内：" + meetingId);
                }
                for (Ballot ballot : ballotCopy.values()) {
                    if (!attendeeSeatIds.contains(ballot.seatId())
                            || recusedSeatIds.contains(ballot.seatId())
                            || ballot.castAt() < opensAt || ballot.castAt() >= closesAt) {
                        throw new IllegalArgumentException("分红会议出现无资格选票：" + ballot.seatId());
                    }
                }
                if (dividendProposal != null && status == MeetingStatus.REJECTED
                        && attendeeSeatIds.size() - recusedSeatIds.size() < QUORUM) {
                    throw new IllegalArgumentException("有效到会不足时应延期：" + meetingId);
                }
                checkDividendOutcome(meetingId, status, attendeeSeatIds, recusedSeatIds, ballotCopy);
            } else if (topic == Topic.MANAGER_APPOINTMENT) {
                if (createdAt > materialDeadline || materialDeadline > opensAt || opensAt >= closesAt) {
                    throw new IllegalArgumentException("任免会议时序不合法：" + meetingId);
                }
                if (status == MeetingStatus.REVIEWED || status == MeetingStatus.IMPLEMENTATION_FAILED) {
                    throw new IllegalArgumentException("任免议题不支持该阶段：" + meetingId);
                }
                if (!attendeeSeatIds.containsAll(recusedSeatIds)) {
                    throw new IllegalArgumentException("回避席位必须到会：" + meetingId);
                }
                Set<String> materialIds = new HashSet<>();
                for (MaterialRef material : materialList) {
                    if (!materialIds.add(material.materialId()) || material.asOf() > materialDeadline) {
                        throw new IllegalArgumentException("任免材料重复或晚于截止：" + meetingId);
                    }
                }
                if ((status == MeetingStatus.CONVENED || status == MeetingStatus.MATERIALS_FROZEN)
                        && !ballotCopy.isEmpty()) {
                    throw new IllegalArgumentException("表决开始前不能有选票：" + meetingId);
                }
                if (!lineList.isEmpty()) {
                    throw new IllegalArgumentException("任免议题不能携带预算行：" + meetingId);
                }
                if (dividendProposal != null || dividendResolution != null) {
                    throw new IllegalArgumentException("任免议题不能携带分红元数据：" + meetingId);
                }
                if (appointmentProposal == null) {
                    if (status != MeetingStatus.CONVENED && status != MeetingStatus.MATERIALS_FROZEN
                            && status != MeetingStatus.REJECTED) {
                        throw new IllegalArgumentException("该阶段必须有任免提案：" + meetingId);
                    }
                    if (!ballotCopy.isEmpty()) {
                        throw new IllegalArgumentException("无提案不能有选票：" + meetingId);
                    }
                } else {
                    if (appointmentProposal.proposedAtMillis() < createdAt
                            || appointmentProposal.proposedAtMillis() > materialDeadline) {
                        throw new IllegalArgumentException("任免提案不在会议材料窗口内：" + meetingId);
                    }
                    if (closesAt > appointmentProposal.request().effectiveAt()) {
                        throw new IllegalArgumentException("表决截止晚于任免生效时点：" + meetingId);
                    }
                    for (String related : appointmentProposal.relatedSeatIds()) {
                        if (attendeeSeatIds.contains(related) && !recusedSeatIds.contains(related)) {
                            throw new IllegalArgumentException("到会的关联席位未登记回避：" + related);
                        }
                    }
                }
                if (appointmentResolution != null && status != MeetingStatus.IMPLEMENTED) {
                    throw new IllegalArgumentException("只有已实施会议才能携带授权：" + meetingId);
                }
                if (status == MeetingStatus.IMPLEMENTED && appointmentResolution == null) {
                    throw new IllegalArgumentException("已实施会议必须有授权：" + meetingId);
                }
                if (appointmentResolution != null && (appointmentResolution.issuedAtMillis() < closesAt
                        || appointmentResolution.issuedAtMillis()
                        > appointmentProposal.request().effectiveAt())) {
                    throw new IllegalArgumentException("授权生成不在有效窗口内：" + meetingId);
                }
                for (Ballot ballot : ballotCopy.values()) {
                    if (!attendeeSeatIds.contains(ballot.seatId())
                            || recusedSeatIds.contains(ballot.seatId())
                            || ballot.castAt() < opensAt || ballot.castAt() >= closesAt) {
                        throw new IllegalArgumentException("任免会议出现无资格选票：" + ballot.seatId());
                    }
                }
                if (appointmentProposal != null && status == MeetingStatus.REJECTED
                        && attendeeSeatIds.size() - recusedSeatIds.size() < QUORUM) {
                    throw new IllegalArgumentException("有效到会不足时应延期：" + meetingId);
                }
                checkAppointmentOutcome(meetingId, status, attendeeSeatIds, recusedSeatIds,
                        ballotCopy);
            } else if (topic == Topic.DEBT_WAIVER) {
                if (createdAt > materialDeadline || materialDeadline > opensAt || opensAt >= closesAt) {
                    throw new IllegalArgumentException("豁免会议时序不合法：" + meetingId);
                }
                if (status == MeetingStatus.REVIEWED
                        || status == MeetingStatus.IMPLEMENTATION_FAILED) {
                    throw new IllegalArgumentException("豁免议题不支持该阶段：" + meetingId);
                }
                if (!attendeeSeatIds.containsAll(recusedSeatIds)) {
                    throw new IllegalArgumentException("回避席位必须到会：" + meetingId);
                }
                Set<String> materialIds = new HashSet<>();
                for (MaterialRef material : materialList) {
                    if (!materialIds.add(material.materialId())
                            || material.asOf() > materialDeadline) {
                        throw new IllegalArgumentException("豁免材料重复或晚于截止：" + meetingId);
                    }
                }
                if ((status == MeetingStatus.CONVENED || status == MeetingStatus.MATERIALS_FROZEN)
                        && !ballotCopy.isEmpty()) {
                    throw new IllegalArgumentException("表决开始前不能有选票：" + meetingId);
                }
                if (!lineList.isEmpty()) {
                    throw new IllegalArgumentException("豁免议题不能携带预算行：" + meetingId);
                }
                if (dividendProposal != null || dividendResolution != null
                        || appointmentProposal != null || appointmentResolution != null) {
                    throw new IllegalArgumentException("豁免议题不能携带分红/任免元数据：" + meetingId);
                }
                for (Ballot ballot : ballotCopy.values()) {
                    if (!attendeeSeatIds.contains(ballot.seatId())
                            || recusedSeatIds.contains(ballot.seatId())
                            || ballot.castAt() < opensAt || ballot.castAt() >= closesAt) {
                        throw new IllegalArgumentException("豁免会议出现无资格选票："
                                + ballot.seatId());
                    }
                }
            } else if (dividendProposal != null || dividendResolution != null) {
                throw new IllegalArgumentException("非分红议题不能携带分红元数据：" + meetingId);
            } else if (appointmentProposal != null || appointmentResolution != null) {
                throw new IllegalArgumentException("非任免议题不能携带任免元数据：" + meetingId);
            }
            materials = List.copyOf(materialList);
            proposedLines = List.copyOf(lineList);
            ballots = Map.copyOf(ballotCopy);
        }

        /**
         * 本次请求新上限合计；特别事项阈值按此判定。
         *
         * @return 合计金额
         */
        public BigDecimal requestedTotal() {
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (BudgetLine line : proposedLines) {
                total = total.add(line.newLimit());
            }
            return total;
        }

        /**
         * 有效到会席位：到会但未回避。
         *
         * @return 有效到会席位，按座位排序
         */
        public List<String> eligibleVoters() {
            List<String> eligible = new ArrayList<>();
            for (String seat : attendeeSeatIds) {
                if (!recusedSeatIds.contains(seat)) {
                    eligible.add(seat);
                }
            }
            return List.copyOf(eligible);
        }
    }

    /**
     * 不可变董事会状态；集合在构造时深拷贝为不可修改视图。
     *
     * @param charter     冻结章程
     * @param meetings    会议，按标识索引
     * @param budgets     已生效预算上限，按期间与类别排序
     * @param commitments 承诺，按标识排序
     * @param reviews     考核结果，按标识排序
     */
    public record State(Charter charter, Map<String, Meeting> meetings, List<Budget> budgets,
                        List<Commitment> commitments, List<ReviewResult> reviews) {

        /** 校验集合深不可变与各自主键唯一。 */
        public State {
            Objects.requireNonNull(charter, "章程");
            Map<String, Meeting> meetingCopy = new TreeMap<>(
                    Objects.requireNonNull(meetings, "会议"));
            for (Map.Entry<String, Meeting> entry : meetingCopy.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().meetingId())) {
                    throw new IllegalArgumentException("会议索引与标识不一致：" + entry.getKey());
                }
            }
            List<Budget> budgetList = new ArrayList<>(
                    Objects.requireNonNull(budgets, "预算上限"));
            budgetList.sort(Comparator.comparingLong(Budget::period)
                    .thenComparing(Budget::category));
            for (int i = 1; i < budgetList.size(); i++) {
                Budget previous = budgetList.get(i - 1);
                Budget current = budgetList.get(i);
                if (previous.period() == current.period()
                        && previous.category() == current.category()) {
                    throw new IllegalArgumentException("同一期间类别重复设置预算上限："
                            + current.period() + " / " + current.category());
                }
            }
            List<Commitment> commitmentList = new ArrayList<>(
                    Objects.requireNonNull(commitments, "承诺"));
            commitmentList.sort(Comparator.comparing(Commitment::commitmentId));
            for (int i = 1; i < commitmentList.size(); i++) {
                if (commitmentList.get(i - 1).commitmentId()
                        .equals(commitmentList.get(i).commitmentId())) {
                    throw new IllegalArgumentException("重复承诺标识："
                            + commitmentList.get(i).commitmentId());
                }
            }
            List<ReviewResult> reviewList = new ArrayList<>(
                    Objects.requireNonNull(reviews, "考核结果"));
            reviewList.sort(Comparator.comparing(ReviewResult::reviewId));
            for (int i = 1; i < reviewList.size(); i++) {
                if (reviewList.get(i - 1).reviewId().equals(reviewList.get(i).reviewId())) {
                    throw new IllegalArgumentException("重复考核标识："
                            + reviewList.get(i).reviewId());
                }
            }
            checkDividendState(charter, meetingCopy);
            checkAppointmentState(charter, meetingCopy);
            meetings = Map.copyOf(meetingCopy);
            budgets = List.copyOf(budgetList);
            commitments = List.copyOf(commitmentList);
            reviews = List.copyOf(reviewList);
        }

        /**
         * 会议快照。
         *
         * @param meetingId 会议标识
         * @return 会议；不存在返回 null
         */
        public Meeting meeting(String meetingId) {
            return meetings.get(meetingId);
        }

        /**
         * 预算上限。
         *
         * @param period   实验期间序号
         * @param category 预算类别
         * @return 预算；未设置返回 null
         */
        public Budget budget(long period, BudgetCategory category) {
            for (Budget budget : budgets) {
                if (budget.period() == period && budget.category() == category) {
                    return budget;
                }
            }
            return null;
        }
    }

    /**
     * 纯候选结果：拒绝时通常原样返回状态；实施失败会带回 IMPLEMENTATION_FAILED 会议。
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
     * 债权豁免决议候选结果：豁免决议不进入 {@link State}，由调用方（schema18 工程子集）持有。
     *
     * @param accepted   业务是否成功
     * @param reason     结果代码
     * @param waivers    候选豁免决议列表
     * @param boardState 需要写回会议终态时的候选董事会状态；只读查询与提案为 null
     */
    public record WaiverOutcome(boolean accepted, String reason, List<DebtWaiver> waivers,
                                State boardState) {

        /** 校验字段与列表不可变。 */
        public WaiverOutcome {
            Objects.requireNonNull(reason, "结果代码");
            waivers = waivers == null ? List.of() : List.copyOf(waivers);
        }

        /** 三参构造：不携带董事会状态写回。 */
        public WaiverOutcome(boolean accepted, String reason, List<DebtWaiver> waivers) {
            this(accepted, reason, waivers, null);
        }
    }

    /**
     * 会议召集请求；时序是否合法由 {@link #convene} 判定。
     *
     * @param meetingId        会议标识
     * @param topic            议题
     * @param convenerSeatId   召集席位
     * @param materialDeadline 材料截止
     * @param opensAt          开会时刻
     * @param closesAt         表决截止（不含）
     * @param attendeeSeatIds  到会席位
     * @param recusedSeatIds   回避席位，必须是到会席位的子集
     */
    public record MeetingRequest(String meetingId, Topic topic, String convenerSeatId,
                                 long materialDeadline, long opensAt, long closesAt,
                                 List<String> attendeeSeatIds, List<String> recusedSeatIds) {
        /** 校验标识、议题与集合可复制性。 */
        public MeetingRequest {
            requireText(meetingId, "会议标识");
            Objects.requireNonNull(topic, "议题");
            requireText(convenerSeatId, "召集席位");
            requireTime(materialDeadline);
            requireTime(opensAt);
            requireTime(closesAt);
            attendeeSeatIds = sortedDistinct(attendeeSeatIds, "到会席位");
            recusedSeatIds = sortedDistinct(recusedSeatIds, "回避席位");
        }
    }

    /**
     * 开局：校验公司是已登记组织、五席各绑定不同登记人物、主席在席。
     *
     * @param registry 开局登记（只读，不复制现金或股份）
     * @param charter  冻结章程
     * @return 空会议、空预算的初始状态
     * @throws IllegalArgumentException 公司或成员身份不合法
     */
    public static State open(OpeningRegistry registry, Charter charter) {
        Objects.requireNonNull(registry, "开局登记");
        Objects.requireNonNull(charter, "章程");
        Party company = party(registry, charter.companyPartyId());
        if (company == null || company.kind() != PartyKind.ORGANIZATION) {
            throw new IllegalArgumentException("公司必须是已登记组织：" + charter.companyPartyId());
        }
        for (Seat seat : charter.seats()) {
            Party person = party(registry, seat.partyId());
            if (person == null || person.kind() != PartyKind.PERSON) {
                throw new IllegalArgumentException("席位必须绑定已登记人物：" + seat.seatId()
                        + " -> " + seat.partyId());
            }
        }
        return new State(charter, Map.of(), List.of(), List.of(), List.of());
    }

    /**
     * 召集会议：主席发起，冻结到会与回避名单，校验时点顺序。
     *
     * @param state   当前状态
     * @param now     当前模拟毫秒（即召集时刻）
     * @param request 召集请求
     * @return 候选结果
     */
    public static Outcome convene(State state, long now, MeetingRequest request) {
        requireState(state);
        requireTime(now);
        Objects.requireNonNull(request, "召集请求");
        if (state.meeting(request.meetingId()) != null) {
            return rejected(state, REASON_MEETING_EXISTS);
        }
        if (!state.charter().chairSeatId().equals(request.convenerSeatId())) {
            return rejected(state, REASON_NOT_CHAIR);
        }
        if (now > request.materialDeadline()
                || request.materialDeadline() > request.opensAt()
                || request.opensAt() >= request.closesAt()) {
            return rejected(state, REASON_INVALID_TIMELINE);
        }
        for (String seat : request.attendeeSeatIds()) {
            if (!state.charter().hasSeat(seat)) {
                return rejected(state, REASON_INVALID_ATTENDANCE);
            }
        }
        for (String seat : request.recusedSeatIds()) {
            if (!state.charter().hasSeat(seat) || !request.attendeeSeatIds().contains(seat)) {
                return rejected(state, REASON_INVALID_ATTENDANCE);
            }
        }
        Draft draft = new Draft(state);
        draft.meetings.put(request.meetingId(), new Meeting(request.meetingId(), request.topic(),
                request.convenerSeatId(), now, request.materialDeadline(), request.opensAt(),
                request.closesAt(), MeetingStatus.CONVENED, request.attendeeSeatIds(),
                request.recusedSeatIds(), List.of(), List.of(), Map.of(), null));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 章程自动召集一次经营考核会议：召集人写专属哨兵 {@link #CHARTER_AUTO}，不冒充主席本人。
     *
     * <p>只由周期期末采样与恢复重演调用；旧人工 {@link #convene} 的主席限制原样保留。
     * 会议从 {@code CONVENED} 开始，材料为空，到会与回避由真实董事本人另行登记。</p>
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒
     * @param meetingId 会议标识
     * @param materialDeadline 材料截止
     * @param opensAt   开会时刻
     * @param closesAt  表决/评分截止（不含）
     * @return 候选结果
     */
    public static Outcome conveneByCharter(State state, long now, String meetingId,
                                           long materialDeadline, long opensAt, long closesAt) {
        requireState(state);
        requireTime(now);
        requireText(meetingId, "会议标识");
        if (meetingOf(state, meetingId) != null) {
            return rejected(state, REASON_MEETING_EXISTS);
        }
        if (now > materialDeadline || materialDeadline > opensAt || opensAt >= closesAt) {
            return rejected(state, REASON_INVALID_TIMELINE);
        }
        Draft draft = new Draft(state);
        draft.meetings.put(meetingId, new Meeting(meetingId, Topic.OPERATING_REVIEW,
                CHARTER_AUTO, now, materialDeadline, opensAt, closesAt, MeetingStatus.CONVENED,
                List.of(), List.of(), List.of(), List.of(), Map.of(), null));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 冻结周期考核材料：由经理真实准备并发布 BOARD 材料后调用，把预留的确切文书版本
     * 写入会议材料清单。已冻结且引用完全相同视为幂等；不允许改写或追加第二份。
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒，必须在 {@code [opensAt, closesAt)} 内
     * @param meetingId 会议标识
     * @param docId     材料文书
     * @param sourceRef 材料来源引用
     * @param version   材料确切版本
     * @return 候选结果
     */
    public static Outcome freezePeriodicMaterials(State state, long now, String meetingId,
                                                  String docId, String sourceRef, long version) {
        requireState(state);
        requireTime(now);
        requireText(meetingId, "会议标识");
        requireText(docId, "材料文书");
        requireText(sourceRef, "材料来源引用");
        if (version < 1) {
            throw new IllegalArgumentException("材料版本必须从 1 开始：" + version);
        }
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (meeting.topic() != Topic.OPERATING_REVIEW) {
            return rejected(state, REASON_WRONG_TOPIC);
        }
        MaterialRef ref = new MaterialRef(docId, sourceRef, version, meeting.materialDeadline());
        if (meeting.status() == MeetingStatus.MATERIALS_FROZEN) {
            return meeting.materials().size() == 1 && meeting.materials().get(0).equals(ref)
                    ? accepted(state, REASON_OK) : rejected(state, REASON_MATERIALS_FROZEN);
        }
        if (meeting.status() != MeetingStatus.CONVENED) {
            return rejected(state, REASON_INVALID_STATE);
        }
        if (now < meeting.opensAt()) {
            return rejected(state, REASON_NOT_DUE);
        }
        if (now >= meeting.closesAt()) {
            return rejected(state, REASON_VOTING_CLOSED);
        }
        Draft draft = new Draft(state);
        draft.meetings.put(meetingId, copyWith(meeting, MeetingStatus.MATERIALS_FROZEN,
                List.of(ref), meeting.proposedLines(), meeting.ballots(), null));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 异步参与登记：本人在会务窗口内确认参加本次会议，不表示整场连续在场。
     *
     * <p>只允许 {@code CONVENED} 且 {@code createdAt <= now < opensAt} 的会议；登记加入
     * attendee，{@code selfRecused} 只增不减。任免候选人/被撤人或任免、分红提案
     * {@code relatedSeatIds} 命中该席时强制回避，自主不回避不能覆盖；重复登记不重复席位、
     * 不清除既有回避，也不改材料、选票、提案/授权或其它字段。</p>
     *
     * <p>{@code seatId} 仅是纯核参数，不能证明真实人物或任务来源；真实人物绑定、任务/报告逐帧
     * 重演与授权闭环由后续世界桥验证。本方法不补记过去到会，也不建立会议表或阅读账。</p>
     *
     * @param state       董事会状态
     * @param now         当前模拟毫秒
     * @param meetingId   会议标识
     * @param seatId      登记席位，必须是章程席位
     * @param selfRecused 是否自主回避；false 不能清除既有或强制回避
     * @return 候选结果；拒绝时原状态不变
     */
    public static Outcome attend(State state, long now, String meetingId, String seatId,
                                 boolean selfRecused) {
        requireState(state);
        requireTime(now);
        requireText(meetingId, "会议标识");
        requireText(seatId, "登记席位");
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (!state.charter().hasSeat(seatId)) {
            return rejected(state, REASON_INVALID_ATTENDANCE);
        }
        if (meeting.status() != MeetingStatus.CONVENED) {
            return rejected(state, REASON_INVALID_STATE);
        }
        if (now < meeting.createdAt() || now >= meeting.opensAt()) {
            return rejected(state, REASON_NOT_DUE);
        }
        List<String> attendees = new ArrayList<>(meeting.attendeeSeatIds());
        if (!attendees.contains(seatId)) {
            attendees.add(seatId);
        }
        List<String> recused = new ArrayList<>(meeting.recusedSeatIds());
        if ((selfRecused || forcedRecusal(state.charter(), meeting, seatId))
                && !recused.contains(seatId)) {
            recused.add(seatId);
        }
        Meeting updated = new Meeting(meeting.meetingId(), meeting.topic(),
                meeting.convenerSeatId(), meeting.createdAt(), meeting.materialDeadline(),
                meeting.opensAt(), meeting.closesAt(), meeting.status(), attendees, recused,
                meeting.materials(), meeting.proposedLines(), meeting.ballots(),
                meeting.failureReason(), meeting.dividendProposal(),
                meeting.dividendResolution(), meeting.appointmentProposal(),
                meeting.appointmentResolution());
        if (updated.equals(meeting)) {
            return accepted(state, REASON_OK);
        }
        Draft draft = new Draft(state);
        draft.meetings.put(meetingId, updated);
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 任免候选人/被撤人占席或提案关联席命中时的强制回避。
     */
    private static boolean forcedRecusal(Charter charter, Meeting meeting, String seatId) {
        if (meeting.appointmentProposal() != null) {
            AppointmentRequest request = meeting.appointmentProposal().request();
            if (seatId.equals(charter.seatOf(request.personPartyId()))
                    || meeting.appointmentProposal().relatedSeatIds().contains(seatId)) {
                return true;
            }
        }
        return meeting.dividendProposal() != null
                && meeting.dividendProposal().relatedSeatIds().contains(seatId);
    }

    /**
     * 提交本次预算请求：各期间各类别的新上限，绝对值。
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒
     * @param meetingId 会议标识
     * @param lines     预算请求行
     * @return 候选结果
     */
    public static Outcome proposeBudget(State state, long now, String meetingId,
                                        List<BudgetLine> lines) {
        requireState(state);
        requireTime(now);
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (now < meeting.createdAt()) {
            return rejected(state, REASON_INVALID_TIMELINE);
        }
        if (meeting.status() != MeetingStatus.CONVENED) {
            return rejected(state, REASON_MATERIALS_FROZEN);
        }
        if (now > meeting.materialDeadline()) {
            return rejected(state, REASON_MATERIALS_CLOSED);
        }
        if (meeting.topic() != Topic.BUDGET) {
            return rejected(state, REASON_WRONG_TOPIC);
        }
        if (!meeting.proposedLines().isEmpty()) {
            return rejected(state, REASON_PROPOSAL_EXISTS);
        }
        List<BudgetLine> lineList = new ArrayList<>(
                Objects.requireNonNull(lines, "预算请求行"));
        if (lineList.isEmpty()) {
            return rejected(state, REASON_EMPTY_PROPOSAL);
        }
        for (int i = 0; i < lineList.size(); i++) {
            for (int j = i + 1; j < lineList.size(); j++) {
                if (lineList.get(i).period() == lineList.get(j).period()
                        && lineList.get(i).category() == lineList.get(j).category()) {
                    return rejected(state, REASON_DUPLICATE_BUDGET_LINE);
                }
            }
        }
        Draft draft = new Draft(state);
        Meeting current = meeting;
        draft.meetings.put(meetingId, new Meeting(current.meetingId(), current.topic(),
                current.convenerSeatId(), current.createdAt(), current.materialDeadline(),
                current.opensAt(), current.closesAt(), current.status(),
                current.attendeeSeatIds(), current.recusedSeatIds(), current.materials(),
                lineList, current.ballots(), current.failureReason()));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 增加材料：只允许材料截止前、冻结前；未来时点证据拒绝，重复标识不覆盖旧材料。
     *
     * @param state    当前状态
     * @param now      当前模拟毫秒
     * @param meetingId 会议标识
     * @param material 材料引用
     * @return 候选结果
     */
    public static Outcome addMaterial(State state, long now, String meetingId,
                                      MaterialRef material) {
        requireState(state);
        requireTime(now);
        Objects.requireNonNull(material, "材料");
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (now < meeting.createdAt()) {
            return rejected(state, REASON_INVALID_TIMELINE);
        }
        if (meeting.status() != MeetingStatus.CONVENED) {
            return rejected(state, REASON_MATERIALS_FROZEN);
        }
        if (now > meeting.materialDeadline()) {
            return rejected(state, REASON_MATERIALS_CLOSED);
        }
        if (material.asOf() > now || material.asOf() > meeting.materialDeadline()) {
            return rejected(state, REASON_FUTURE_EVIDENCE);
        }
        for (MaterialRef existing : meeting.materials()) {
            if (existing.materialId().equals(material.materialId())) {
                return rejected(state, REASON_MATERIAL_EXISTS);
            }
        }
        Draft draft = new Draft(state);
        List<MaterialRef> materials = new ArrayList<>(meeting.materials());
        materials.add(material);
        draft.meetings.put(meetingId, copyWith(meeting, meeting.status(), materials,
                meeting.proposedLines(), meeting.ballots(), meeting.failureReason()));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 开会冻结材料：开会时刻起不再接受新材料。
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒
     * @param meetingId 会议标识
     * @return 候选结果
     */
    public static Outcome freezeMaterials(State state, long now, String meetingId) {
        requireState(state);
        requireTime(now);
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (meeting.status() != MeetingStatus.CONVENED) {
            return rejected(state, REASON_MATERIALS_FROZEN);
        }
        if (now < meeting.opensAt()) {
            return rejected(state, REASON_NOT_DUE);
        }
        if (now >= meeting.closesAt()) {
            return rejected(state, REASON_VOTING_CLOSED);
        }
        Draft draft = new Draft(state);
        draft.meetings.put(meetingId, copyWith(meeting, MeetingStatus.MATERIALS_FROZEN,
                meeting.materials(), meeting.proposedLines(), meeting.ballots(), null));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 进入表决：只允许材料冻结后、表决窗口内。
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒
     * @param meetingId 会议标识
     * @return 候选结果
     */
    public static Outcome startVoting(State state, long now, String meetingId) {
        return startVoting(state, now, meetingId, List.of());
    }

    /**
     * 开始表决；豁免议题的“是否有提案”由调用方提供的豁免决议列表判定。
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒
     * @param meetingId 会议标识
     * @param waivers   本董事会豁免决议（其它议题可为空）
     * @return 候选结果
     */
    public static Outcome startVoting(State state, long now, String meetingId,
                                      List<DebtWaiver> waivers) {
        requireState(state);
        requireTime(now);
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (meeting.status() != MeetingStatus.MATERIALS_FROZEN) {
            return rejected(state, REASON_INVALID_STATE);
        }
        if (meeting.topic() == Topic.OPERATING_REVIEW) {
            return rejected(state, REASON_WRONG_TOPIC);
        }
        if (now < meeting.opensAt() || now >= meeting.closesAt()) {
            return rejected(state, REASON_VOTING_CLOSED);
        }
        boolean hasProposal = meeting.topic() == Topic.BUDGET
                ? !meeting.proposedLines().isEmpty()
                : meeting.topic() == Topic.DIVIDEND_AUTHORIZATION
                        ? meeting.dividendProposal() != null
                        : meeting.topic() == Topic.DEBT_WAIVER
                                ? debtWaiverOf(waivers, meetingId) != null
                                : meeting.appointmentProposal() != null;
        if (!hasProposal) {
            return rejected(state, REASON_EMPTY_PROPOSAL);
        }
        Draft draft = new Draft(state);
        draft.meetings.put(meetingId, copyWith(meeting, MeetingStatus.VOTING,
                meeting.materials(), meeting.proposedLines(), meeting.ballots(), null));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 投票：只有到会且未回避的章程席位可以投票，一席一票，重复票不覆盖。
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒，必须在 {@code [opensAt, closesAt)} 内
     * @param meetingId 会议标识
     * @param seatId    投票席位
     * @param vote      选择
     * @return 候选结果
     */
    public static Outcome castVote(State state, long now, String meetingId, String seatId,
                                   Vote vote) {
        requireState(state);
        requirement(vote, "投票选择");
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (meeting.status() != MeetingStatus.VOTING) {
            return rejected(state, REASON_INVALID_STATE);
        }
        if (now < meeting.opensAt() || now >= meeting.closesAt()) {
            return rejected(state, REASON_VOTING_CLOSED);
        }
        if (!state.charter().hasSeat(seatId)
                || !meeting.eligibleVoters().contains(seatId)) {
            return rejected(state, REASON_NOT_ELIGIBLE_VOTER);
        }
        if (meeting.ballots().containsKey(seatId)) {
            return rejected(state, REASON_ALREADY_VOTED);
        }
        Draft draft = new Draft(state);
        Map<String, Ballot> ballots = new LinkedHashMap<>(meeting.ballots());
        ballots.put(seatId, new Ballot(seatId, vote, now));
        draft.meetings.put(meetingId, copyWith(meeting, meeting.status(), meeting.materials(),
                meeting.proposedLines(), ballots, null));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 到期关闭表决：从真实选票计算结果，调用方不能传入通过标记。
     *
     * <p>有效到会（到会减回避）不足 3 席即延期；普通事项需 3 票赞成，本次新上限合计大于 1000
     * 的特别预算事项需 4 票赞成。通过只写 ADOPTED，不动任何预算。</p>
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒，必须已到 {@code closesAt}
     * @param meetingId 会议标识
     * @return 候选结果
     */
    public static Outcome closeVoting(State state, long now, String meetingId) {
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting != null && meeting.topic() == Topic.DEBT_WAIVER) {
            // 豁免会议的通过门槛取决于被冻结的具体金额，必须由持有豁免决议的调用方传入
            return rejected(state, REASON_INVALID_ARGUMENT);
        }
        return closeVoting(state, now, meetingId, List.of());
    }

    /**
     * 关闭表决并形成终态；豁免会议由调用方提供冻结的豁免决议以判定金额重要性门槛。
     *
     * @param state    当前状态
     * @param now      当前模拟毫秒
     * @param meetingId 会议标识
     * @param waivers  本董事会的豁免决议（其它议题可为空）
     * @return 候选结果
     */
    public static Outcome closeVoting(State state, long now, String meetingId,
                                      List<DebtWaiver> waivers) {
        requireState(state);
        requireTime(now);
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (meeting.status() == MeetingStatus.MATERIALS_FROZEN) {
            // 预算会议因空提案无法进入表决时，到期必须形成明确终态，不能永久冻结。
            if (now < meeting.closesAt()) {
                return rejected(state, REASON_NOT_DUE);
            }
            if (meeting.topic() == Topic.BUDGET && meeting.proposedLines().isEmpty()) {
                Draft rejectedDraft = new Draft(state);
                rejectedDraft.meetings.put(meetingId, copyWith(meeting, MeetingStatus.REJECTED,
                        meeting.materials(), meeting.proposedLines(), meeting.ballots(), null));
                return accepted(rejectedDraft.build(), REASON_EMPTY_PROPOSAL);
            }
            if (meeting.topic() == Topic.DIVIDEND_AUTHORIZATION
                    && meeting.dividendProposal() == null) {
                Draft rejectedDraft = new Draft(state);
                rejectedDraft.meetings.put(meetingId, copyWith(meeting, MeetingStatus.REJECTED,
                        meeting.materials(), meeting.proposedLines(), meeting.ballots(), null));
                return accepted(rejectedDraft.build(), REASON_EMPTY_PROPOSAL);
            }
            if (meeting.topic() == Topic.MANAGER_APPOINTMENT
                    && meeting.appointmentProposal() == null) {
                Draft rejectedDraft = new Draft(state);
                rejectedDraft.meetings.put(meetingId, copyWith(meeting, MeetingStatus.REJECTED,
                        meeting.materials(), meeting.proposedLines(), meeting.ballots(), null));
                return accepted(rejectedDraft.build(), REASON_EMPTY_PROPOSAL);
            }
            if (meeting.topic() == Topic.DEBT_WAIVER
                    && debtWaiverOf(waivers, meetingId) == null) {
                Draft rejectedDraft = new Draft(state);
                rejectedDraft.meetings.put(meetingId, copyWith(meeting, MeetingStatus.REJECTED,
                        meeting.materials(), meeting.proposedLines(), meeting.ballots(), null));
                return accepted(rejectedDraft.build(), REASON_EMPTY_PROPOSAL);
            }
            return rejected(state, REASON_INVALID_STATE);
        }
        if (meeting.status() != MeetingStatus.VOTING) {
            return rejected(state, REASON_INVALID_STATE);
        }
        if (now < meeting.closesAt()) {
            return rejected(state, REASON_NOT_DUE);
        }
        List<String> eligible = meeting.eligibleVoters();
        MeetingStatus result;
        if (eligible.size() < QUORUM) {
            result = MeetingStatus.ADJOURNED_NO_QUORUM;
        } else {
            int yes = 0;
            for (Ballot ballot : meeting.ballots().values()) {
                if (ballot.vote() == Vote.YES && eligible.contains(ballot.seatId())) {
                    yes++;
                }
            }
            int required = requiredYesVotes(waivers, meeting);
            result = yes >= required ? MeetingStatus.ADOPTED : MeetingStatus.REJECTED;
        }
        Draft draft = new Draft(state);
        draft.meetings.put(meetingId, copyWith(meeting, result, meeting.materials(),
                meeting.proposedLines(), meeting.ballots(), null));
        return accepted(draft.build(), result.name());
    }

    /**
     * 实施已通过的预算决议：把请求行设为绝对新上限；低于已花加活动承诺则失败并保留原上限。
     *
     * <p>只能实施一次；失败会写入 {@code IMPLEMENTATION_FAILED} 与原因，选票和决议原文不变。
     * 本方法不改现金、利润或库存，只授予有限支出权限。</p>
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒
     * @param meetingId 会议标识
     * @return 候选结果；实施失败时 {@code accepted=false} 且状态中会议为 IMPLEMENTATION_FAILED
     */
    public static Outcome executeAdoptedResolution(State state, long now, String meetingId) {
        requireState(state);
        requireTime(now);
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (meeting.status() != MeetingStatus.ADOPTED) {
            return rejected(state, REASON_NOT_ADOPTED);
        }
        if (now < meeting.closesAt()) {
            return rejected(state, REASON_NOT_DUE);
        }
        if (meeting.topic() != Topic.BUDGET) {
            return rejected(state, REASON_WRONG_TOPIC);
        }
        for (BudgetLine line : meeting.proposedLines()) {
            BigDecimal floor = spentBudget(state, line.period(), line.category())
                    .add(outstandingBudget(state, line.period(), line.category()));
            if (line.newLimit().compareTo(floor) < 0) {
                Draft failed = new Draft(state);
                failed.meetings.put(meetingId, copyWith(meeting,
                        MeetingStatus.IMPLEMENTATION_FAILED, meeting.materials(),
                        meeting.proposedLines(), meeting.ballots(),
                        REASON_BUDGET_BELOW_COMMITTED));
                return new Outcome(false, REASON_BUDGET_BELOW_COMMITTED, failed.build());
            }
        }
        Draft draft = new Draft(state);
        Map<BudgetKey, BigDecimal> caps = new LinkedHashMap<>();
        for (Budget budget : state.budgets()) {
            caps.put(new BudgetKey(budget.period(), budget.category()), budget.cap());
        }
        for (BudgetLine line : meeting.proposedLines()) {
            caps.put(new BudgetKey(line.period(), line.category()), line.newLimit());
        }
        List<Budget> budgets = new ArrayList<>();
        for (Map.Entry<BudgetKey, BigDecimal> entry : caps.entrySet()) {
            budgets.add(new Budget(entry.getKey().period(), entry.getKey().category(),
                    entry.getValue()));
        }
        draft.budgets.clear();
        draft.budgets.addAll(budgets);
        draft.meetings.put(meetingId, copyWith(meeting, MeetingStatus.IMPLEMENTED,
                meeting.materials(), meeting.proposedLines(), meeting.ballots(), null));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 受理分红提案：经济内容固定、关联事实可信，受理后不可覆盖。
     *
     * <p>请求是 D0 的原始载体；本方法先规范金额与校验时点，再核对提案人/关联席位、会议窗口、
     * 表决截止不晚于登记截止，以及同一方案的经济不可改、并行重审与重复实施约束。
     * 业务拒绝一律返回原状态。</p>
     *
     * @param state           当前状态
     * @param now             当前模拟毫秒
     * @param meetingId       会议标识
     * @param proposerSeatId  提案席位
     * @param relatedSeatIds  关联席位事实
     * @param request         原始分红方案请求
     * @return 候选结果
     */
    public static Outcome proposeDividendAuthorization(State state, long now, String meetingId,
                                                       String proposerSeatId,
                                                       List<String> relatedSeatIds,
                                                       DividendModel.SchemeRequest request) {
        requireState(state);
        requireTime(now);
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (meeting.topic() != Topic.DIVIDEND_AUTHORIZATION) {
            return rejected(state, REASON_WRONG_TOPIC);
        }
        if (meeting.status() != MeetingStatus.CONVENED) {
            return rejected(state, REASON_MATERIALS_FROZEN);
        }
        if (now < meeting.createdAt()) {
            return rejected(state, REASON_INVALID_TIMELINE);
        }
        if (now > meeting.materialDeadline()) {
            return rejected(state, REASON_MATERIALS_CLOSED);
        }
        if (meeting.dividendProposal() != null) {
            return rejected(state, REASON_PROPOSAL_EXISTS);
        }
        if (request == null) {
            return rejected(state, REASON_INVALID_ARGUMENT);
        }
        if (blank(request.schemeId()) || blank(request.symbol())
                || blank(request.issuerOwnerId()) || blank(request.companyAccountId())) {
            return rejected(state, REASON_INVALID_ARGUMENT);
        }
        if (blank(proposerSeatId) || !state.charter().hasSeat(proposerSeatId)) {
            return rejected(state, REASON_INVALID_ATTENDANCE);
        }
        List<String> related = relatedSeatIds == null ? List.of() : relatedSeatIds;
        Set<String> seenRelated = new HashSet<>();
        for (String seatId : related) {
            if (blank(seatId) || !state.charter().hasSeat(seatId) || !seenRelated.add(seatId)) {
                return rejected(state, REASON_INVALID_ATTENDANCE);
            }
            if (meeting.attendeeSeatIds().contains(seatId)
                    && !meeting.recusedSeatIds().contains(seatId)) {
                return rejected(state, REASON_INVALID_ATTENDANCE);
            }
        }
        BigDecimal perShare;
        try {
            perShare = money2(request.perShare(), "每股股息", true);
        } catch (IllegalArgumentException invalid) {
            return rejected(state, REASON_INVALID_AMOUNT);
        }
        if (perShare.signum() <= 0) {
            return rejected(state, REASON_INVALID_AMOUNT);
        }
        if (request.recordAtMillis() < 0 || request.exAtMillis() < 0
                || request.payAtMillis() < 0
                || request.recordAtMillis() >= request.exAtMillis()
                || request.exAtMillis() > request.payAtMillis()) {
            return rejected(state, REASON_INVALID_TIMELINE);
        }
        if (meeting.closesAt() > request.recordAtMillis()) {
            return rejected(state, REASON_INVALID_TIMELINE);
        }
        DividendModel.SchemeRequest normalized = new DividendModel.SchemeRequest(
                request.schemeId(), request.symbol(), request.issuerOwnerId(),
                request.companyAccountId(), perShare, request.recordAtMillis(),
                request.exAtMillis(), request.payAtMillis());
        for (Meeting other : state.meetings().values()) {
            if (other.topic() != Topic.DIVIDEND_AUTHORIZATION
                    || other.dividendProposal() == null) {
                continue;
            }
            DividendModel.SchemeRequest existing = other.dividendProposal().request();
            if (!existing.schemeId().equals(normalized.schemeId())) {
                continue;
            }
            if (other.status() == MeetingStatus.IMPLEMENTED) {
                return rejected(state, REASON_INVALID_STATE);
            }
            if (!sameEconomics(existing, normalized)) {
                return rejected(state, REASON_INVALID_ARGUMENT);
            }
            if (isActiveDividend(other.status())) {
                return rejected(state, REASON_INVALID_STATE);
            }
        }
        DividendProposal proposal = new DividendProposal(normalized, proposerSeatId, related, now);
        Draft draft = new Draft(state);
        draft.meetings.put(meetingId, withProposal(meeting, proposal));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 实施已通过的分红决议：重新核对真实章程、到会、逐席选票与通过条件后生成授权。
     *
     * <p>只产生授权引用，不改预算、现金或分红账；issuedAt 仅表示授权生成时点。</p>
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒
     * @param meetingId 会议标识
     * @return 候选结果
     */
    public static Outcome executeAdoptedDividendResolution(State state, long now, String meetingId) {
        requireState(state);
        requireTime(now);
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (meeting.topic() != Topic.DIVIDEND_AUTHORIZATION) {
            return rejected(state, REASON_WRONG_TOPIC);
        }
        if (meeting.status() != MeetingStatus.ADOPTED) {
            return rejected(state, REASON_NOT_ADOPTED);
        }
        if (now < meeting.closesAt()) {
            return rejected(state, REASON_NOT_DUE);
        }
        DividendProposal proposal = meeting.dividendProposal();
        if (proposal == null) {
            return rejected(state, REASON_INVALID_STATE);
        }
        if (now > proposal.request().recordAtMillis()) {
            return rejected(state, REASON_INVALID_TIMELINE);
        }
        List<String> eligible = meeting.eligibleVoters();
        if (eligible.size() < QUORUM) {
            return rejected(state, REASON_NO_QUORUM);
        }
        int yes = yesVotes(meeting, eligible);
        if (yes < ORDINARY_YES_VOTES) {
            return rejected(state, REASON_NOT_ADOPTED);
        }
        for (Meeting other : state.meetings().values()) {
            if (!other.meetingId().equals(meetingId)
                    && other.topic() == Topic.DIVIDEND_AUTHORIZATION
                    && other.dividendResolution() != null
                    && other.dividendProposal() != null
                    && sameEconomics(other.dividendProposal().request(), proposal.request())) {
                return rejected(state, REASON_INVALID_STATE);
            }
        }
        String authorizationRef = computeAuthorizationRef(state.charter(), meeting, now);
        DividendResolution resolution = new DividendResolution(now, authorizationRef);
        Draft draft = new Draft(state);
        draft.meetings.put(meetingId, withResolution(meeting, resolution));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 提出公司经理任免提案：只写提案，不产生授权、不改任职。
     *
     * @param state          当前董事会状态
     * @param now            当前模拟毫秒
     * @param meetingId      任免会议标识
     * @param proposerSeatId 提案席位
     * @param relatedSeatIds 关联席位事实
     * @param request        任免请求
     * @return 候选结果
     */
    public static Outcome proposeManagerAppointment(State state, long now, String meetingId,
                                                    String proposerSeatId,
                                                    List<String> relatedSeatIds,
                                                    AppointmentRequest request) {
        requireState(state);
        requireTime(now);
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (meeting.topic() != Topic.MANAGER_APPOINTMENT) {
            return rejected(state, REASON_WRONG_TOPIC);
        }
        if (meeting.status() != MeetingStatus.CONVENED) {
            return rejected(state, REASON_MATERIALS_FROZEN);
        }
        if (now < meeting.createdAt()) {
            return rejected(state, REASON_INVALID_TIMELINE);
        }
        if (now > meeting.materialDeadline()) {
            return rejected(state, REASON_MATERIALS_CLOSED);
        }
        if (meeting.appointmentProposal() != null) {
            return rejected(state, REASON_PROPOSAL_EXISTS);
        }
        if (request == null) {
            return rejected(state, REASON_INVALID_ARGUMENT);
        }
        if (!request.companyPartyId().equals(state.charter().companyPartyId())) {
            return rejected(state, REASON_INVALID_ARGUMENT);
        }
        if (blank(proposerSeatId) || !state.charter().hasSeat(proposerSeatId)) {
            return rejected(state, REASON_INVALID_ATTENDANCE);
        }
        List<String> related = relatedSeatIds == null ? List.of() : relatedSeatIds;
        Set<String> seenRelated = new HashSet<>();
        for (String seatId : related) {
            if (blank(seatId) || !state.charter().hasSeat(seatId) || !seenRelated.add(seatId)) {
                return rejected(state, REASON_INVALID_ATTENDANCE);
            }
            if (meeting.attendeeSeatIds().contains(seatId)
                    && !meeting.recusedSeatIds().contains(seatId)) {
                return rejected(state, REASON_INVALID_ATTENDANCE);
            }
        }
        String autoSeat = state.charter().seatOf(request.personPartyId());
        if (autoSeat != null && meeting.attendeeSeatIds().contains(autoSeat)
                && !meeting.recusedSeatIds().contains(autoSeat)) {
            return rejected(state, REASON_INVALID_ATTENDANCE);
        }
        if (meeting.closesAt() > request.effectiveAt()) {
            return rejected(state, REASON_INVALID_TIMELINE);
        }
        if (request.isAppointment()) {
            for (Meeting other : state.meetings().values()) {
                if (other.topic() != Topic.MANAGER_APPOINTMENT
                        || other.appointmentProposal() == null
                        || !other.appointmentProposal().request().isAppointment()
                        || !isActiveAppointment(other.status())) {
                    continue;
                }
                AppointmentRequest existing = other.appointmentProposal().request();
                if (existing.companyPartyId().equals(request.companyPartyId())
                        && existing.role() == request.role()) {
                    return rejected(state, REASON_INVALID_STATE);
                }
                if (existing.appointmentId().equals(request.appointmentId())) {
                    return rejected(state, REASON_INVALID_STATE);
                }
            }
        }
        AppointmentProposal proposal = new AppointmentProposal(request, proposerSeatId,
                related, now);
        Draft draft = new Draft(state);
        draft.meetings.put(meetingId, withAppointmentProposal(meeting, proposal));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 实施已通过的任免决议：重新核对章程、会议、选票与生效时点后生成授权引用。
     *
     * <p>只产生授权引用，不改 RoleAccess 任职表；授权不得晚于请求生效时点。</p>
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒
     * @param meetingId 任免会议标识
     * @return 候选结果
     */
    public static Outcome executeAdoptedManagerAppointment(State state, long now,
                                                           String meetingId) {
        requireState(state);
        requireTime(now);
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (meeting.topic() != Topic.MANAGER_APPOINTMENT) {
            return rejected(state, REASON_WRONG_TOPIC);
        }
        if (meeting.status() != MeetingStatus.ADOPTED) {
            return rejected(state, REASON_NOT_ADOPTED);
        }
        AppointmentProposal proposal = meeting.appointmentProposal();
        if (proposal == null) {
            return rejected(state, REASON_INVALID_STATE);
        }
        if (now < meeting.closesAt()) {
            return rejected(state, REASON_NOT_DUE);
        }
        if (now > proposal.request().effectiveAt()) {
            return rejected(state, REASON_INVALID_TIMELINE);
        }
        List<String> eligible = meeting.eligibleVoters();
        if (eligible.size() < QUORUM) {
            return rejected(state, REASON_NO_QUORUM);
        }
        if (yesVotes(meeting, eligible) < ORDINARY_YES_VOTES) {
            return rejected(state, REASON_NOT_ADOPTED);
        }
        if (!proposal.request().companyPartyId().equals(state.charter().companyPartyId())) {
            return rejected(state, REASON_INVALID_STATE);
        }
        String authorizationRef = computeAppointmentAuthorizationRef(state.charter(), meeting,
                now);
        AppointmentResolution resolution = new AppointmentResolution(now, authorizationRef);
        Draft draft = new Draft(state);
        draft.meetings.put(meetingId, withAppointmentResolution(meeting, resolution));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 核验公司经理任免授权：只有真实已实施、请求逐字段相同且来源可重算时才为真。
     *
     * @param state            当前状态
     * @param authorizationRef 待核验引用
     * @param request          待核验请求
     * @return 是否与真实来源一致
     */
    public static boolean verifiesManagerAppointmentAuthorization(State state,
                                                                  String authorizationRef,
                                                                  AppointmentRequest request) {
        requireState(state);
        if (blank(authorizationRef) || request == null) {
            return false;
        }
        for (Meeting meeting : state.meetings().values()) {
            if (meeting.topic() != Topic.MANAGER_APPOINTMENT
                    || meeting.status() != MeetingStatus.IMPLEMENTED) {
                continue;
            }
            AppointmentResolution resolution = meeting.appointmentResolution();
            if (resolution == null
                    || !resolution.authorizationRef().equals(authorizationRef)
                    || meeting.appointmentProposal() == null) {
                continue;
            }
            if (!sameAppointmentRequest(meeting.appointmentProposal().request(), request)) {
                continue;
            }
            String recomputed = computeAppointmentAuthorizationRef(state.charter(), meeting,
                    resolution.issuedAtMillis());
            if (recomputed.equals(authorizationRef)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 核验分红授权：只有真实已实施、方案逐字段相同且来源可重算时才为真。
     *
     * <p>核验不带当前时点，因此及时签发的合法授权在支付日之后仍然有效；不会因支付日
     * 晚于登记截止而被误判过期。</p>
     *
     * @param state            当前状态
     * @param authorizationRef 待核验引用
     * @param request          待核验方案请求
     * @return 是否与真实来源一致
     */
    public static boolean verifiesDividendAuthorization(State state, String authorizationRef,
                                                        DividendModel.SchemeRequest request) {
        requireState(state);
        if (blank(authorizationRef) || request == null) {
            return false;
        }
        DividendModel.SchemeRequest normalized;
        try {
            normalized = normalizeDividendRequest(request);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        for (Meeting meeting : state.meetings().values()) {
            if (meeting.topic() != Topic.DIVIDEND_AUTHORIZATION
                    || meeting.status() != MeetingStatus.IMPLEMENTED) {
                continue;
            }
            DividendResolution resolution = meeting.dividendResolution();
            if (resolution == null
                    || !resolution.authorizationRef().equals(authorizationRef)
                    || meeting.dividendProposal() == null) {
                continue;
            }
            if (!sameEconomics(meeting.dividendProposal().request(), normalized)) {
                continue;
            }
            String recomputed = computeAuthorizationRef(state.charter(), meeting,
                    resolution.issuedAtMillis());
            if (recomputed.equals(authorizationRef)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 具体债权豁免授权

    /**
     * 通过门槛：预算合计或豁免金额超过特别阈值走 4 票，其余 3 票；关联回避不降低分母。
     *
     * @param waivers 本董事会的豁免决议（其它议题可为空表）
     * @param meeting 会议
     * @return 所需赞成票数
     */
    public static int requiredYesVotes(List<DebtWaiver> waivers, Meeting meeting) {
        Objects.requireNonNull(meeting, "会议");
        if (meeting.topic() == Topic.BUDGET
                && meeting.requestedTotal().compareTo(SPECIAL_BUDGET_THRESHOLD) > 0) {
            return SPECIAL_YES_VOTES;
        }
        if (meeting.topic() == Topic.DEBT_WAIVER) {
            DebtWaiver waiver = debtWaiverOf(waivers, meeting.meetingId());
            if (waiver != null && waiver.request().amount()
                    .compareTo(SPECIAL_BUDGET_THRESHOLD) > 0) {
                return SPECIAL_YES_VOTES;
            }
        }
        return ORDINARY_YES_VOTES;
    }

    /**
     * 按会议取豁免决议记录（只读）。
     *
     * @param waivers   豁免决议列表
     * @param meetingId 会议标识
     * @return 豁免记录；不存在返回 null
     */
    public static DebtWaiver debtWaiverOf(List<DebtWaiver> waivers, String meetingId) {
        if (waivers == null || blank(meetingId)) {
            return null;
        }
        for (DebtWaiver waiver : waivers) {
            if (waiver.meetingId().equals(meetingId)) {
                return waiver;
            }
        }
        return null;
    }

    /**
     * 按授权引用取豁免决议记录，不核验有效性。
     *
     * @param waivers          豁免决议列表
     * @param authorizationRef 授权引用
     * @return 豁免记录；不存在返回 null
     */
    public static DebtWaiver debtWaiverByRef(List<DebtWaiver> waivers, String authorizationRef) {
        if (waivers == null || blank(authorizationRef)) {
            return null;
        }
        for (DebtWaiver waiver : waivers) {
            if (authorizationRef.equals(waiver.authorizationRef())) {
                return waiver;
            }
        }
        return null;
    }

    /**
     * 受理具体债权豁免提案：冻结公司/合同/客户/金额/用途，审议后不可覆盖。
     *
     * <p>“一般预算许可”“经理自填授权”都不能替代本提案：请求对象里必须写明具体合同、
     * 具体客户、具体金额与用途。同一合同存在未执行的豁免授权时拒绝新提案，避免重复授权。</p>
     *
     * @param charter        公司章程
     * @param meetings       现有会议
     * @param waivers        现有豁免决议
     * @param now            当前模拟毫秒
     * @param meetingId      会议标识
     * @param proposerSeatId 提案席位
     * @param request        豁免请求
     * @return 候选结果与新的豁免决议列表
     */
    public static WaiverOutcome proposeDebtWaiver(Charter charter, Map<String, Meeting> meetings,
            List<DebtWaiver> waivers, long now, String meetingId, String proposerSeatId,
            DebtWaiverRequest request) {
        Objects.requireNonNull(charter, "章程");
        Objects.requireNonNull(meetings, "会议");
        requireTime(now);
        List<DebtWaiver> current = waivers == null ? List.of() : waivers;
        Meeting meeting = meetings.get(meetingId);
        if (meeting == null) {
            return new WaiverOutcome(false, REASON_UNKNOWN_MEETING, current);
        }
        if (meeting.topic() != Topic.DEBT_WAIVER) {
            return new WaiverOutcome(false, REASON_WRONG_TOPIC, current);
        }
        if (meeting.status() != MeetingStatus.CONVENED) {
            return new WaiverOutcome(false, REASON_MATERIALS_FROZEN, current);
        }
        if (now < meeting.createdAt()) {
            return new WaiverOutcome(false, REASON_INVALID_TIMELINE, current);
        }
        if (now > meeting.materialDeadline()) {
            return new WaiverOutcome(false, REASON_MATERIALS_CLOSED, current);
        }
        if (debtWaiverOf(current, meetingId) != null) {
            return new WaiverOutcome(false, REASON_WAIVER_PROPOSAL_EXISTS, current);
        }
        if (request == null) {
            return new WaiverOutcome(false, REASON_INVALID_ARGUMENT, current);
        }
        if (!request.companyPartyId().equals(charter.companyPartyId())) {
            return new WaiverOutcome(false, REASON_INVALID_ARGUMENT, current);
        }
        if (blank(proposerSeatId) || !charter.hasSeat(proposerSeatId)) {
            return new WaiverOutcome(false, REASON_INVALID_ATTENDANCE, current);
        }
        for (DebtWaiver existing : current) {
            if (existing.request().companyPartyId().equals(request.companyPartyId())
                    && existing.request().contractId().equals(request.contractId())
                    && existing.request().customerPartyId().equals(request.customerPartyId())
                    && existing.authorized() && !existing.consumed()) {
                return new WaiverOutcome(false, REASON_INVALID_STATE, current);
            }
        }
        List<DebtWaiver> next = new ArrayList<>(current);
        next.add(new DebtWaiver(request, meetingId, proposerSeatId, now, null, null, null));
        return new WaiverOutcome(true, REASON_OK, next);
    }

    /**
     * 实施已通过的豁免决议：核对选票与金额门槛后签发唯一授权引用，不改任何账。
     *
     * @param charter   公司章程
     * @param state     会议状态（用于写回会议终态）
     * @param waivers   现有豁免决议
     * @param now       当前模拟毫秒
     * @param meetingId 会议标识
     * @return 候选结果与新的豁免决议列表
     */
    public static WaiverOutcome executeAdoptedDebtWaiver(Charter charter, State state,
            List<DebtWaiver> waivers, long now, String meetingId) {
        Objects.requireNonNull(state, "状态");
        requireTime(now);
        List<DebtWaiver> current = waivers == null ? List.of() : waivers;
        Meeting meeting = state.meeting(meetingId);
        if (meeting == null) {
            return new WaiverOutcome(false, REASON_UNKNOWN_MEETING, current);
        }
        if (meeting.topic() != Topic.DEBT_WAIVER) {
            return new WaiverOutcome(false, REASON_WRONG_TOPIC, current);
        }
        if (meeting.status() != MeetingStatus.ADOPTED) {
            return new WaiverOutcome(false, REASON_NOT_ADOPTED, current);
        }
        if (now < meeting.closesAt()) {
            return new WaiverOutcome(false, REASON_NOT_DUE, current);
        }
        DebtWaiver waiver = debtWaiverOf(current, meetingId);
        if (waiver == null) {
            return new WaiverOutcome(false, REASON_INVALID_STATE, current);
        }
        if (waiver.authorized()) {
            return new WaiverOutcome(false, REASON_INVALID_STATE, current);
        }
        List<String> eligible = meeting.eligibleVoters();
        if (eligible.size() < QUORUM) {
            return new WaiverOutcome(false, REASON_NO_QUORUM, current);
        }
        if (yesVotes(meeting, eligible) < requiredYesVotes(current, meeting)) {
            return new WaiverOutcome(false, REASON_NOT_ADOPTED, current);
        }
        String authorizationRef = computeDebtWaiverAuthorizationRef(charter, meeting, waiver, now);
        List<DebtWaiver> next = new ArrayList<>();
        for (DebtWaiver existing : current) {
            if (existing.request().waiverId().equals(waiver.request().waiverId())) {
                next.add(new DebtWaiver(existing.request(), existing.meetingId(),
                        existing.proposerSeatId(), existing.proposedAtMillis(), now,
                        authorizationRef, null));
            } else {
                next.add(existing);
            }
        }
        Draft draft = new Draft(state);
        draft.meetings.put(meetingId, copyWith(meeting, MeetingStatus.IMPLEMENTED,
                meeting.materials(), meeting.proposedLines(), meeting.ballots(), null));
        return new WaiverOutcome(true, REASON_OK, next, draft.build());
    }

    /**
     * 核验豁免授权：必须已签发、未执行、字段逐项相同且来源可重算。
     *
     * @param charter          公司章程
     * @param state            会议状态
     * @param waivers          豁免决议
     * @param authorizationRef 待核验引用
     * @param request          待核验请求
     * @return 是否可用于执行
     */
    public static boolean verifiesDebtWaiverAuthorization(Charter charter, State state,
            List<DebtWaiver> waivers, String authorizationRef, DebtWaiverRequest request) {
        Objects.requireNonNull(state, "状态");
        if (blank(authorizationRef) || request == null || waivers == null) {
            return false;
        }
        for (DebtWaiver waiver : waivers) {
            if (!waiver.authorized() || waiver.consumed()
                    || !authorizationRef.equals(waiver.authorizationRef())) {
                continue;
            }
            if (!sameWaiverRequest(waiver.request(), request)) {
                continue;
            }
            Meeting meeting = state.meeting(waiver.meetingId());
            if (meeting == null || meeting.topic() != Topic.DEBT_WAIVER
                    || meeting.status() != MeetingStatus.IMPLEMENTED) {
                continue;
            }
            String recomputed = computeDebtWaiverAuthorizationRef(charter, meeting, waiver,
                    waiver.issuedAtMillis());
            if (recomputed.equals(authorizationRef)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 消耗已核验的豁免授权：一次执行，重复执行被拒绝。
     *
     * @param waivers          豁免决议
     * @param now              执行时点
     * @param authorizationRef 授权引用
     * @return 候选结果与新的豁免决议列表
     */
    public static WaiverOutcome consumeDebtWaiver(List<DebtWaiver> waivers, long now,
                                                  String authorizationRef) {
        requireTime(now);
        List<DebtWaiver> current = waivers == null ? List.of() : waivers;
        DebtWaiver waiver = debtWaiverByRef(current, authorizationRef);
        if (waiver == null || !waiver.authorized()) {
            return new WaiverOutcome(false, REASON_WAIVER_NOT_AUTHORIZED, current);
        }
        if (waiver.consumed()) {
            return new WaiverOutcome(false, REASON_WAIVER_ALREADY_CONSUMED, current);
        }
        if (now < waiver.issuedAtMillis()) {
            return new WaiverOutcome(false, REASON_INVALID_TIMELINE, current);
        }
        List<DebtWaiver> next = new ArrayList<>();
        for (DebtWaiver existing : current) {
            if (existing.request().waiverId().equals(waiver.request().waiverId())) {
                next.add(new DebtWaiver(existing.request(), existing.meetingId(),
                        existing.proposerSeatId(), existing.proposedAtMillis(),
                        existing.issuedAtMillis(), existing.authorizationRef(), now));
            } else {
                next.add(existing);
            }
        }
        return new WaiverOutcome(true, REASON_OK, next);
    }

    /** 豁免请求逐字段相等（用于授权绑定）。 */
    private static boolean sameWaiverRequest(DebtWaiverRequest first, DebtWaiverRequest second) {
        return first.waiverId().equals(second.waiverId())
                && first.companyPartyId().equals(second.companyPartyId())
                && first.contractId().equals(second.contractId())
                && first.customerPartyId().equals(second.customerPartyId())
                && first.amount().compareTo(second.amount()) == 0
                && first.purpose().equals(second.purpose());
    }

    /**
     * 豁免决议自校验：授权/消耗只能出现在真实已实施会议之后，且门槛与选票一致。
     *
     * <p>由持有豁免决议的调用方（schema18 工程子集）在每批校验时调用，
     * {@code BoardModel.State} 本身不带任何豁免或金额字段。</p>
     *
     * @param charter  公司章程
     * @param meetings 该公司会议
     * @param waivers  该公司豁免决议
     * @throws IllegalArgumentException 决议与真实会议/章程不一致
     */
    public static void validateDebtWaivers(Charter charter, Map<String, Meeting> meetings,
                                           List<DebtWaiver> waivers) {
        Objects.requireNonNull(charter, "章程");
        Objects.requireNonNull(meetings, "会议");
        List<DebtWaiver> list = waivers == null ? List.of() : waivers;
        Map<String, DebtWaiver> byMeeting = new TreeMap<>();
        Set<String> seenIds = new HashSet<>();
        for (DebtWaiver waiver : list) {
            DebtWaiverRequest request = waiver.request();
            if (!seenIds.add(request.waiverId())) {
                throw new IllegalArgumentException("重复豁免标识：" + request.waiverId());
            }
            if (!request.companyPartyId().equals(charter.companyPartyId())) {
                throw new IllegalArgumentException("豁免公司必须等于章程公司："
                        + request.waiverId());
            }
            if (!charter.hasSeat(waiver.proposerSeatId())) {
                throw new IllegalArgumentException("豁免提案人不是章程席位："
                        + waiver.proposerSeatId());
            }
            Meeting meeting = meetings.get(waiver.meetingId());
            if (meeting == null || meeting.topic() != Topic.DEBT_WAIVER) {
                throw new IllegalArgumentException("豁免记录必须指向真实豁免会议："
                        + waiver.meetingId());
            }
            if (waiver.proposedAtMillis() < meeting.createdAt()
                    || waiver.proposedAtMillis() > meeting.materialDeadline()) {
                throw new IllegalArgumentException("豁免提案不在会议材料窗口内："
                        + waiver.meetingId());
            }
            if (byMeeting.put(waiver.meetingId(), waiver) != null) {
                throw new IllegalArgumentException("同一豁免会议只能有一条决议："
                        + waiver.meetingId());
            }
            if (waiver.authorized()) {
                if (meeting.status() != MeetingStatus.IMPLEMENTED) {
                    throw new IllegalArgumentException("未实施会议不能携带豁免授权："
                            + waiver.meetingId());
                }
                if (waiver.issuedAtMillis() < meeting.closesAt()) {
                    throw new IllegalArgumentException("豁免授权早于表决截止："
                            + waiver.meetingId());
                }
                String expected = computeDebtWaiverAuthorizationRef(charter, meeting, waiver,
                        waiver.issuedAtMillis());
                if (!expected.equals(waiver.authorizationRef())) {
                    throw new IllegalArgumentException("豁免授权引用与真实来源不一致："
                            + waiver.meetingId());
                }
            } else if (meeting.status() == MeetingStatus.IMPLEMENTED) {
                throw new IllegalArgumentException("已实施豁免会议必须有授权："
                        + waiver.meetingId());
            }
            if (meeting.status() == MeetingStatus.ADOPTED
                    || meeting.status() == MeetingStatus.IMPLEMENTED) {
                // 票数门槛按金额重要性由章程决定，不能靠并列状态降低
                List<String> eligible = meeting.eligibleVoters();
                int required = request.amount().compareTo(SPECIAL_BUDGET_THRESHOLD) > 0
                        ? SPECIAL_YES_VOTES : ORDINARY_YES_VOTES;
                if (eligible.size() < QUORUM || yesVotes(meeting, eligible) < required) {
                    throw new IllegalArgumentException("豁免通过状态与真实选票/门槛不一致："
                            + waiver.meetingId());
                }
            }
        }
        for (Meeting meeting : meetings.values()) {
            if (meeting.topic() == Topic.DEBT_WAIVER
                    && meeting.status() == MeetingStatus.IMPLEMENTED
                    && !byMeeting.containsKey(meeting.meetingId())) {
                throw new IllegalArgumentException("已实施豁免会议缺少决议记录："
                        + meeting.meetingId());
            }
        }
    }

    private static String computeDebtWaiverAuthorizationRef(Charter charter, Meeting meeting,
                                                            DebtWaiver waiver, long issuedAtMillis) {
        DebtWaiverRequest value = waiver.request();
        Map<String, Object> source = new TreeMap<>();
        Map<String, Object> request = new TreeMap<>();
        request.put("waiverId", value.waiverId());
        request.put("companyPartyId", value.companyPartyId());
        request.put("contractId", value.contractId());
        request.put("customerPartyId", value.customerPartyId());
        request.put("amount", value.amount().toPlainString());
        request.put("purpose", value.purpose());
        source.put("request", request);

        Map<String, Object> charterSource = new TreeMap<>();
        charterSource.put("companyPartyId", charter.companyPartyId());
        charterSource.put("chairSeatId", charter.chairSeatId());
        charterSource.put("quorum", charter.quorum());
        List<Seat> seatList = new ArrayList<>(charter.seats());
        seatList.sort(Comparator.comparing(Seat::seatId));
        List<Map<String, Object>> seats = new ArrayList<>();
        for (Seat seat : seatList) {
            Map<String, Object> row = new TreeMap<>();
            row.put("seatId", seat.seatId());
            row.put("partyId", seat.partyId());
            seats.add(row);
        }
        charterSource.put("seats", seats);
        source.put("charter", charterSource);

        Map<String, Object> meetingSource = new TreeMap<>();
        meetingSource.put("meetingId", meeting.meetingId());
        meetingSource.put("topic", meeting.topic().name());
        meetingSource.put("convenerSeatId", meeting.convenerSeatId());
        meetingSource.put("createdAt", meeting.createdAt());
        meetingSource.put("materialDeadline", meeting.materialDeadline());
        meetingSource.put("opensAt", meeting.opensAt());
        meetingSource.put("closesAt", meeting.closesAt());
        meetingSource.put("attendees", List.copyOf(meeting.attendeeSeatIds()));
        meetingSource.put("recused", List.copyOf(meeting.recusedSeatIds()));
        meetingSource.put("proposerSeatId", waiver.proposerSeatId());
        meetingSource.put("proposedAtMillis", waiver.proposedAtMillis());
        List<Map<String, Object>> materials = new ArrayList<>();
        for (MaterialRef material : meeting.materials()) {
            Map<String, Object> row = new TreeMap<>();
            row.put("materialId", material.materialId());
            row.put("sourceRef", material.sourceRef());
            row.put("version", material.version());
            row.put("asOf", material.asOf());
            materials.add(row);
        }
        meetingSource.put("materials", materials);
        List<Ballot> ballotList = new ArrayList<>(meeting.ballots().values());
        ballotList.sort(Comparator.comparing(Ballot::seatId));
        List<Map<String, Object>> ballots = new ArrayList<>();
        for (Ballot ballot : ballotList) {
            Map<String, Object> row = new TreeMap<>();
            row.put("seatId", ballot.seatId());
            row.put("vote", ballot.vote().name());
            row.put("castAt", ballot.castAt());
            ballots.add(row);
        }
        meetingSource.put("ballots", ballots);
        source.put("meeting", meetingSource);
        source.put("issuedAtMillis", issuedAtMillis);
        try {
            return WAIVER_AUTHORIZATION_DOMAIN + ":" + sha256Hex(MAPPER.writeValueAsString(source));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("豁免授权来源无法规范化", failure);
        }
    }

    /**
     * 占用预算：形成活动承诺；新承诺失败时不改变预算。
     *
     * @param state        当前状态
     * @param now          当前模拟毫秒
     * @param commitmentId 业务承诺标识，历史唯一
     * @param period       实验期间序号
     * @param category     预算类别
     * @param amount       占用金额，必须为正且不超过可用额
     * @return 候选结果
     */
    public static Outcome reserveBudget(State state, long now, String commitmentId, long period,
                                        BudgetCategory category, BigDecimal amount) {
        requireState(state);
        requireTime(now);
        requirePeriod(period);
        requirement(category, "预算类别");
        requireText(commitmentId, "承诺标识");
        BigDecimal value;
        try {
            value = money2(amount, "占用金额", true);
        } catch (IllegalArgumentException invalid) {
            return rejected(state, REASON_INVALID_AMOUNT);
        }
        for (Commitment commitment : state.commitments()) {
            if (commitment.commitmentId().equals(commitmentId)) {
                return rejected(state, REASON_COMMITMENT_EXISTS);
            }
        }
        if (state.budget(period, category) == null) {
            return rejected(state, REASON_BUDGET_NOT_SET);
        }
        if (value.compareTo(availableBudget(state, period, category)) > 0) {
            return rejected(state, REASON_BUDGET_EXCEEDED);
        }
        Draft draft = new Draft(state);
        draft.commitments.add(new Commitment(commitmentId, period, category, value,
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                CommitmentStatus.ACTIVE));
        return accepted(draft.build(), REASON_OK);
    }

    /**
     * 实际花费：只从未结余额扣减，不再重复占用预算；超出未结余额拒绝。
     *
     * @param state        当前状态
     * @param now          当前模拟毫秒
     * @param commitmentId 承诺标识
     * @param amount       花费金额，必须为正且不超过未结余额
     * @return 候选结果
     */
    public static Outcome spendBudget(State state, long now, String commitmentId,
                                      BigDecimal amount) {
        requireState(state);
        requireTime(now);
        BigDecimal value;
        try {
            value = money2(amount, "花费金额", true);
        } catch (IllegalArgumentException invalid) {
            return rejected(state, REASON_INVALID_AMOUNT);
        }
        Commitment commitment = commitmentOf(state, commitmentId);
        if (commitment == null) {
            return rejected(state, REASON_UNKNOWN_COMMITMENT);
        }
        if (commitment.status() != CommitmentStatus.ACTIVE) {
            return rejected(state, REASON_COMMITMENT_CLOSED);
        }
        if (value.compareTo(commitment.outstanding()) > 0) {
            return rejected(state, REASON_EXCEEDS_COMMITMENT);
        }
        return replaceCommitment(state, commitment, commitment.spent().add(value),
                commitment.released());
    }

    /**
     * 释放未用额度：到货失败等场景只释放未用部分；真实支出保留，不重复占预算。
     *
     * @param state        当前状态
     * @param now          当前模拟毫秒
     * @param commitmentId 承诺标识
     * @param amount       释放金额，必须为正且不超过未结余额
     * @return 候选结果
     */
    public static Outcome releaseBudget(State state, long now, String commitmentId,
                                        BigDecimal amount) {
        requireState(state);
        requireTime(now);
        BigDecimal value;
        try {
            value = money2(amount, "释放金额", true);
        } catch (IllegalArgumentException invalid) {
            return rejected(state, REASON_INVALID_AMOUNT);
        }
        Commitment commitment = commitmentOf(state, commitmentId);
        if (commitment == null) {
            return rejected(state, REASON_UNKNOWN_COMMITMENT);
        }
        if (commitment.status() != CommitmentStatus.ACTIVE) {
            return rejected(state, REASON_COMMITMENT_CLOSED);
        }
        if (value.compareTo(commitment.outstanding()) > 0) {
            return rejected(state, REASON_EXCEEDS_COMMITMENT);
        }
        return replaceCommitment(state, commitment, commitment.spent(),
                commitment.released().add(value));
    }

    /**
     * 计算考核：权重冻结，五项齐全才算总分；缺项返回 INCOMPLETE 与缺项，不用 0 补齐。
     *
     * <p>有效到会（到会减回避）不足固定法定人数时不出评分，会议转 {@code ADJOURNED_NO_QUORUM}
     * 并返回 {@code accepted=false}；成功评分（COMPLETE 或 INCOMPLETE）会把会议置 {@code REVIEWED}，
     * 不会永远停在材料冻结态。总分只做一次末次舍入，与 {@link ReviewResult} 的构造校验共用同一口径。</p>
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒，不得早于开会时刻
     * @param meetingId 考核会议
     * @param evidence  可信已测评分与来源引用
     * @return 候选结果；缺项是合法结果（accepted=true，结果为 INCOMPLETE），不足法定人数是业务拒绝
     */
    public static Outcome calculateReview(State state, long now, String meetingId,
                                          List<EvidenceMetric> evidence) {
        requireState(state);
        requireTime(now);
        Meeting meeting = meetingOf(state, meetingId);
        if (meeting == null) {
            return rejected(state, REASON_UNKNOWN_MEETING);
        }
        if (meeting.topic() != Topic.OPERATING_REVIEW) {
            return rejected(state, REASON_WRONG_TOPIC);
        }
        if (meeting.status() == MeetingStatus.CONVENED) {
            return rejected(state, REASON_MATERIALS_NOT_FROZEN);
        }
        if (now < meeting.opensAt()) {
            return rejected(state, REASON_NOT_DUE);
        }
        for (ReviewResult review : state.reviews()) {
            if (review.reviewId().equals(meetingId)) {
                return rejected(state, REASON_REVIEW_EXISTS);
            }
        }
        if (meeting.eligibleVoters().size() < QUORUM) {
            Draft adjourned = new Draft(state);
            adjourned.meetings.put(meetingId, copyWith(meeting,
                    MeetingStatus.ADJOURNED_NO_QUORUM, meeting.materials(),
                    meeting.proposedLines(), meeting.ballots(), null));
            return new Outcome(false, REASON_NO_QUORUM, adjourned.build());
        }
        List<EvidenceMetric> metrics = new ArrayList<>(
                Objects.requireNonNull(evidence, "考核证据"));
        Map<Dimension, EvidenceMetric> byDimension = new LinkedHashMap<>();
        for (EvidenceMetric metric : metrics) {
            if (byDimension.putIfAbsent(metric.dimension(), metric) != null) {
                return rejected(state, REASON_DUPLICATE_DIMENSION);
            }
            if (metric.score().compareTo(BigDecimal.ZERO) < 0
                    || metric.score().compareTo(HUNDRED) > 0) {
                return rejected(state, REASON_INVALID_SCORE);
            }
            if (metric.asOf() > now || metric.asOf() > meeting.materialDeadline()) {
                return rejected(state, REASON_FUTURE_EVIDENCE);
            }
            if (!hasSource(meeting, metric.sourceRef())) {
                return rejected(state, REASON_UNKNOWN_SOURCE);
            }
        }
        List<Dimension> missing = new ArrayList<>();
        for (Dimension dimension : Dimension.values()) {
            if (!byDimension.containsKey(dimension)) {
                missing.add(dimension);
            }
        }
        ReviewResult review = missing.isEmpty()
                ? new ReviewResult(meetingId, ReviewStatus.COMPLETE,
                        weightedTotal(metrics), List.of(), metrics)
                : new ReviewResult(meetingId, ReviewStatus.INCOMPLETE, null, missing, metrics);
        Draft draft = new Draft(state);
        draft.reviews.add(review);
        draft.meetings.put(meetingId, copyWith(meeting, MeetingStatus.REVIEWED,
                meeting.materials(), meeting.proposedLines(), meeting.ballots(), null));
        return accepted(draft.build(), review.status().name());
    }

    /**
     * 可用预算：上限 − 已花 − 活动承诺未结余额。
     *
     * @param state    当前状态
     * @param period   实验期间序号
     * @param category 预算类别
     * @return 可用额；该范围未设置上限时抛 IllegalArgumentException
     */
    public static BigDecimal availableBudget(State state, long period, BudgetCategory category) {
        requireState(state);
        Budget budget = budgetOf(state, period, category);
        return budget.cap().subtract(spentBudget(state, period, category))
                .subtract(outstandingBudget(state, period, category));
    }

    /**
     * 已实际花费金额（不再占预算）。
     *
     * @param state    当前状态
     * @param period   实验期间序号
     * @param category 预算类别
     * @return 已花金额
     */
    public static BigDecimal spentBudget(State state, long period, BudgetCategory category) {
        requireState(state);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Commitment commitment : state.commitments()) {
            if (commitment.period() == period && commitment.category() == category) {
                total = total.add(commitment.spent());
            }
        }
        return total;
    }

    /**
     * 某期间某类别的活动承诺未结余额合计。
     *
     * @param state    当前状态
     * @param period   实验期间序号
     * @param category 预算类别
     * @return 未结合计
     */
    public static BigDecimal outstandingBudget(State state, long period,
                                               BudgetCategory category) {
        requireState(state);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Commitment commitment : state.commitments()) {
            if (commitment.period() == period && commitment.category() == category) {
                total = total.add(commitment.outstanding());
            }
        }
        return total;
    }

    /**
     * 单个承诺的未结余额。
     *
     * @param state        当前状态
     * @param commitmentId 承诺标识
     * @return 未结金额；不存在时抛 IllegalArgumentException
     */
    public static BigDecimal outstandingCommitment(State state, String commitmentId) {
        requireState(state);
        Commitment commitment = commitmentOf(state, commitmentId);
        if (commitment == null) {
            throw new IllegalArgumentException("承诺不存在：" + commitmentId);
        }
        return commitment.outstanding();
    }

    /**
     * 序列化为可复现 JSON：集合已排序，映射按键排序。
     *
     * @param state 状态
     * @return JSON 文本
     */
    public static String toJson(State state) {
        requireState(state);
        try {
            return MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("董事会状态无法序列化", failure);
        }
    }

    /**
     * 从 JSON 还原状态；构造期校验仍生效（例如篡改法定人数会被拒绝）。
     *
     * @param json JSON 文本
     * @return 还原状态
     * @throws IllegalArgumentException 内容不合法
     */
    public static State fromJson(String json) {
        requireText(json, "董事会状态 JSON");
        try {
            return MAPPER.readValue(json, State.class);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("董事会状态 JSON 不合法", failure);
        }
    }

    // ---------- 内部 ----------

    private record BudgetKey(long period, BudgetCategory category) {
    }

    /**
     * 加权总分：先精确累加 Σ(分数 × 权重)，最后只做一次 2 位 HALF_UP。
     *
     * <p>这是全类唯一的加权口径，{@link #calculateReview} 与 {@link ReviewResult} 的构造校验共用它。
     * 逐项先舍入会把「五项同分」算歪（0.01 → 0.00、0.10 → 0.12），因此不允许第二份加权算法。</p>
     *
     * @param metrics 证据，缺项时只累加已有项
     * @return 2 位总分
     */
    private static BigDecimal weightedTotal(List<EvidenceMetric> metrics) {
        BigDecimal weighted = BigDecimal.ZERO;
        for (EvidenceMetric metric : metrics) {
            weighted = weighted.add(metric.score().multiply(metric.dimension().weight()));
        }
        return weighted.divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static Outcome replaceCommitment(State state, Commitment commitment,
                                             BigDecimal spent, BigDecimal released) {
        BigDecimal outstanding = outstandingOf(commitment.reserved(), spent, released);
        CommitmentStatus status = outstanding.signum() > 0
                ? CommitmentStatus.ACTIVE : CommitmentStatus.CLOSED;
        Draft draft = new Draft(state);
        draft.commitments.clear();
        for (Commitment existing : state.commitments()) {
            if (existing.commitmentId().equals(commitment.commitmentId())) {
                draft.commitments.add(new Commitment(commitment.commitmentId(),
                        commitment.period(), commitment.category(), commitment.reserved(),
                        spent, released, status));
            } else {
                draft.commitments.add(existing);
            }
        }
        return accepted(draft.build(), REASON_OK);
    }

    private static final class Draft {
        private final Charter charter;
        private final Map<String, Meeting> meetings;
        private final List<Budget> budgets;
        private final List<Commitment> commitments;
        private final List<ReviewResult> reviews;

        Draft(State state) {
            this.charter = state.charter();
            this.meetings = new LinkedHashMap<>(state.meetings());
            this.budgets = new ArrayList<>(state.budgets());
            this.commitments = new ArrayList<>(state.commitments());
            this.reviews = new ArrayList<>(state.reviews());
        }

        State build() {
            return new State(charter, meetings, budgets, commitments, reviews);
        }
    }

    private static Meeting copyWith(Meeting meeting, MeetingStatus status,
                                    List<MaterialRef> materials, List<BudgetLine> lines,
                                    Map<String, Ballot> ballots, String failureReason) {
        return new Meeting(meeting.meetingId(), meeting.topic(), meeting.convenerSeatId(),
                meeting.createdAt(), meeting.materialDeadline(), meeting.opensAt(),
                meeting.closesAt(), status, meeting.attendeeSeatIds(), meeting.recusedSeatIds(),
                materials, lines, ballots, failureReason,
                meeting.dividendProposal(), meeting.dividendResolution(),
                meeting.appointmentProposal(), meeting.appointmentResolution());
    }

    private static Meeting withProposal(Meeting meeting, DividendProposal proposal) {
        return new Meeting(meeting.meetingId(), meeting.topic(), meeting.convenerSeatId(),
                meeting.createdAt(), meeting.materialDeadline(), meeting.opensAt(),
                meeting.closesAt(), meeting.status(), meeting.attendeeSeatIds(),
                meeting.recusedSeatIds(), meeting.materials(), meeting.proposedLines(),
                meeting.ballots(), meeting.failureReason(), proposal,
                meeting.dividendResolution(), meeting.appointmentProposal(),
                meeting.appointmentResolution());
    }

    private static Meeting withResolution(Meeting meeting, DividendResolution resolution) {
        return new Meeting(meeting.meetingId(), meeting.topic(), meeting.convenerSeatId(),
                meeting.createdAt(), meeting.materialDeadline(), meeting.opensAt(),
                meeting.closesAt(), MeetingStatus.IMPLEMENTED, meeting.attendeeSeatIds(),
                meeting.recusedSeatIds(), meeting.materials(), meeting.proposedLines(),
                meeting.ballots(), meeting.failureReason(), meeting.dividendProposal(),
                resolution, meeting.appointmentProposal(), meeting.appointmentResolution());
    }

    private static Meeting withAppointmentProposal(Meeting meeting, AppointmentProposal proposal) {
        return new Meeting(meeting.meetingId(), meeting.topic(), meeting.convenerSeatId(),
                meeting.createdAt(), meeting.materialDeadline(), meeting.opensAt(),
                meeting.closesAt(), meeting.status(), meeting.attendeeSeatIds(),
                meeting.recusedSeatIds(), meeting.materials(), meeting.proposedLines(),
                meeting.ballots(), meeting.failureReason(), meeting.dividendProposal(),
                meeting.dividendResolution(), proposal, meeting.appointmentResolution());
    }

    private static Meeting withAppointmentResolution(Meeting meeting,
                                                     AppointmentResolution resolution) {
        return new Meeting(meeting.meetingId(), meeting.topic(), meeting.convenerSeatId(),
                meeting.createdAt(), meeting.materialDeadline(), meeting.opensAt(),
                meeting.closesAt(), MeetingStatus.IMPLEMENTED, meeting.attendeeSeatIds(),
                meeting.recusedSeatIds(), meeting.materials(), meeting.proposedLines(),
                meeting.ballots(), meeting.failureReason(), meeting.dividendProposal(),
                meeting.dividendResolution(), meeting.appointmentProposal(), resolution);
    }

    /**
     * 规范化分红方案请求：金额精确 2 位且为正，时点非负且严格递增。
     *
     * @param raw 原始请求
     * @return 已受理可用的规范请求
     */
    private static DividendModel.SchemeRequest normalizeDividendRequest(
            DividendModel.SchemeRequest raw) {
        Objects.requireNonNull(raw, "分红方案请求");
        requireText(raw.schemeId(), "方案标识");
        requireText(raw.symbol(), "证券代码");
        requireText(raw.issuerOwnerId(), "发行财产主体");
        requireText(raw.companyAccountId(), "公司账户");
        BigDecimal perShare = money2(raw.perShare(), "每股股息", true);
        if (perShare.signum() <= 0) {
            throw new IllegalArgumentException("每股股息必须为正：" + perShare);
        }
        if (raw.recordAtMillis() < 0 || raw.exAtMillis() < 0 || raw.payAtMillis() < 0
                || raw.recordAtMillis() >= raw.exAtMillis()
                || raw.exAtMillis() > raw.payAtMillis()) {
            throw new IllegalArgumentException("分红时点必须满足 recordAt < exAt <= payAt");
        }
        return new DividendModel.SchemeRequest(raw.schemeId(), raw.symbol(), raw.issuerOwnerId(),
                raw.companyAccountId(), perShare, raw.recordAtMillis(), raw.exAtMillis(),
                raw.payAtMillis());
    }

    private static boolean sameEconomics(DividendModel.SchemeRequest first,
                                         DividendModel.SchemeRequest second) {
        return first.schemeId().equals(second.schemeId())
                && first.symbol().equals(second.symbol())
                && first.issuerOwnerId().equals(second.issuerOwnerId())
                && first.companyAccountId().equals(second.companyAccountId())
                && first.perShare().compareTo(second.perShare()) == 0
                && first.recordAtMillis() == second.recordAtMillis()
                && first.exAtMillis() == second.exAtMillis()
                && first.payAtMillis() == second.payAtMillis();
    }

    private static boolean isActiveDividend(MeetingStatus status) {
        return status == MeetingStatus.CONVENED || status == MeetingStatus.MATERIALS_FROZEN
                || status == MeetingStatus.VOTING || status == MeetingStatus.ADOPTED;
    }

    private static int yesVotes(Meeting meeting, List<String> eligible) {
        int yes = 0;
        for (Ballot ballot : meeting.ballots().values()) {
            if (ballot.vote() == Vote.YES && eligible.contains(ballot.seatId())) {
                yes++;
            }
        }
        return yes;
    }

    private static void checkDividendOutcome(String meetingId, MeetingStatus status,
                                             List<String> attendees, List<String> recused,
                                             Map<String, Ballot> ballots) {
        List<String> eligible = new ArrayList<>(attendees);
        eligible.removeAll(recused);
        int yes = 0;
        for (Ballot ballot : ballots.values()) {
            if (ballot.vote() == Vote.YES && eligible.contains(ballot.seatId())) {
                yes++;
            }
        }
        if (status == MeetingStatus.ADJOURNED_NO_QUORUM && eligible.size() >= QUORUM) {
            throw new IllegalArgumentException("有效到会达标却标为延期：" + meetingId);
        }
        if ((status == MeetingStatus.ADOPTED || status == MeetingStatus.IMPLEMENTED)
                && (eligible.size() < QUORUM || yes < ORDINARY_YES_VOTES)) {
            throw new IllegalArgumentException("通过状态与真实选票不一致：" + meetingId);
        }
        if (status == MeetingStatus.REJECTED && eligible.size() >= QUORUM
                && yes >= ORDINARY_YES_VOTES) {
            throw new IllegalArgumentException("否决状态却满足通过条件：" + meetingId);
        }
    }

    private static void checkAppointmentOutcome(String meetingId, MeetingStatus status,
                                                List<String> attendees, List<String> recused,
                                                Map<String, Ballot> ballots) {
        List<String> eligible = new ArrayList<>(attendees);
        eligible.removeAll(recused);
        int yes = 0;
        for (Ballot ballot : ballots.values()) {
            if (ballot.vote() == Vote.YES && eligible.contains(ballot.seatId())) {
                yes++;
            }
        }
        if (status == MeetingStatus.ADJOURNED_NO_QUORUM && eligible.size() >= QUORUM) {
            throw new IllegalArgumentException("有效到会达标却标为延期：" + meetingId);
        }
        if ((status == MeetingStatus.ADOPTED || status == MeetingStatus.IMPLEMENTED)
                && (eligible.size() < QUORUM || yes < ORDINARY_YES_VOTES)) {
            throw new IllegalArgumentException("通过状态与真实选票不一致：" + meetingId);
        }
        if (status == MeetingStatus.REJECTED && eligible.size() >= QUORUM
                && yes >= ORDINARY_YES_VOTES) {
            throw new IllegalArgumentException("否决状态却满足通过条件：" + meetingId);
        }
    }

    private static void checkAppointmentState(Charter charter, Map<String, Meeting> meetings) {
        Set<String> activeAppointments = new HashSet<>();
        Map<String, AppointmentRequest> activeIds = new TreeMap<>();
        for (Meeting meeting : meetings.values()) {
            if (meeting.topic() != Topic.MANAGER_APPOINTMENT) {
                continue;
            }
            if (!charter.chairSeatId().equals(meeting.convenerSeatId())) {
                throw new IllegalArgumentException("任免会议必须由主席召集：" + meeting.meetingId());
            }
            for (String seatId : meeting.attendeeSeatIds()) {
                if (!charter.hasSeat(seatId)) {
                    throw new IllegalArgumentException("到会者不是章程席位：" + seatId);
                }
            }
            AppointmentProposal proposal = meeting.appointmentProposal();
            if (proposal == null) {
                continue;
            }
            if (!charter.hasSeat(proposal.proposerSeatId())) {
                throw new IllegalArgumentException("任免提案人不是章程席位："
                        + proposal.proposerSeatId());
            }
            AppointmentRequest request = proposal.request();
            if (!request.companyPartyId().equals(charter.companyPartyId())) {
                throw new IllegalArgumentException("任免公司必须等于章程公司："
                        + request.companyPartyId());
            }
            for (String related : proposal.relatedSeatIds()) {
                if (!charter.hasSeat(related)) {
                    throw new IllegalArgumentException("关联席位不是章程席位：" + related);
                }
            }
            String autoSeat = charter.seatOf(request.personPartyId());
            if (autoSeat != null && meeting.attendeeSeatIds().contains(autoSeat)
                    && !meeting.recusedSeatIds().contains(autoSeat)) {
                throw new IllegalArgumentException("候选人/被撤人占席未回避：" + autoSeat);
            }
            if (request.isAppointment() && isActiveAppointment(meeting.status())) {
                String key = request.companyPartyId() + "\u0000" + request.role().name();
                if (!activeAppointments.add(key)) {
                    throw new IllegalArgumentException("同一公司同岗位存在并行任命提案："
                            + request.companyPartyId());
                }
                AppointmentRequest seen = activeIds.putIfAbsent(request.appointmentId(), request);
                if (seen != null && !sameAppointmentRequest(seen, request)) {
                    throw new IllegalArgumentException("同一任职标识的任命内容不一致："
                            + request.appointmentId());
                }
            }
            if (meeting.status() == MeetingStatus.IMPLEMENTED) {
                AppointmentResolution resolution = meeting.appointmentResolution();
                if (resolution == null) {
                    throw new IllegalArgumentException("已实施任免会议缺少授权："
                            + meeting.meetingId());
                }
                String expected = computeAppointmentAuthorizationRef(charter, meeting,
                        resolution.issuedAtMillis());
                if (!expected.equals(resolution.authorizationRef())) {
                    throw new IllegalArgumentException("任免授权引用与真实来源不一致："
                            + meeting.meetingId());
                }
            }
        }
    }

    private static boolean sameAppointmentRequest(AppointmentRequest first,
                                                  AppointmentRequest second) {
        return first.appointmentId().equals(second.appointmentId())
                && first.personPartyId().equals(second.personPartyId())
                && first.companyPartyId().equals(second.companyPartyId())
                && first.role() == second.role()
                && first.action() == second.action()
                && first.effectiveAt() == second.effectiveAt()
                && Objects.equals(first.endsAt(), second.endsAt());
    }

    private static boolean isActiveAppointment(MeetingStatus status) {
        return status == MeetingStatus.CONVENED || status == MeetingStatus.MATERIALS_FROZEN
                || status == MeetingStatus.VOTING || status == MeetingStatus.ADOPTED;
    }

    private static void checkDividendState(Charter charter, Map<String, Meeting> meetings) {
        Map<String, DividendModel.SchemeRequest> economics = new TreeMap<>();
        Set<String> active = new HashSet<>();
        Set<String> implemented = new HashSet<>();
        for (Meeting meeting : meetings.values()) {
            if (meeting.topic() != Topic.DIVIDEND_AUTHORIZATION) {
                continue;
            }
            if (!charter.chairSeatId().equals(meeting.convenerSeatId())) {
                throw new IllegalArgumentException("分红会议必须由主席召集：" + meeting.meetingId());
            }
            for (String seatId : meeting.attendeeSeatIds()) {
                if (!charter.hasSeat(seatId)) {
                    throw new IllegalArgumentException("到会者不是章程席位：" + seatId);
                }
            }
            DividendProposal proposal = meeting.dividendProposal();
            if (proposal == null) {
                continue;
            }
            if (!charter.hasSeat(proposal.proposerSeatId())) {
                throw new IllegalArgumentException("提案人不是章程席位："
                        + proposal.proposerSeatId());
            }
            for (String related : proposal.relatedSeatIds()) {
                if (!charter.hasSeat(related)) {
                    throw new IllegalArgumentException("关联席位不是章程席位：" + related);
                }
            }
            String schemeId = proposal.request().schemeId();
            DividendModel.SchemeRequest seen = economics.putIfAbsent(schemeId,
                    proposal.request());
            if (seen != null && !sameEconomics(seen, proposal.request())) {
                throw new IllegalArgumentException("同一方案经济内容不一致：" + schemeId);
            }
            if (isActiveDividend(meeting.status()) && !active.add(schemeId)) {
                throw new IllegalArgumentException("同一方案存在并行提案：" + schemeId);
            }
            if (meeting.status() == MeetingStatus.IMPLEMENTED) {
                if (!implemented.add(schemeId)) {
                    throw new IllegalArgumentException("同一方案重复实施：" + schemeId);
                }
                DividendResolution resolution = meeting.dividendResolution();
                String expected = computeAuthorizationRef(charter, meeting,
                        resolution.issuedAtMillis());
                if (!expected.equals(resolution.authorizationRef())) {
                    throw new IllegalArgumentException("授权引用与真实来源不一致："
                            + meeting.meetingId());
                }
            }
        }
        for (String schemeId : implemented) {
            if (active.contains(schemeId)) {
                throw new IllegalArgumentException("已实施方案仍有活动提案：" + schemeId);
            }
        }
    }

    /**
     * 计算绑定真实来源的授权引用：完整请求、章程、会议、席位、材料、选票、关联事实与时点。
     *
     * @param charter         冻结章程
     * @param meeting         会议（可含或不含授权结果；结果本身不参与摘要）
     * @param issuedAtMillis  授权生成时点
     * @return 域前缀加 SHA-256
     */
    private static String computeAuthorizationRef(Charter charter, Meeting meeting,
                                                  long issuedAtMillis) {
        DividendProposal proposal = meeting.dividendProposal();
        if (proposal == null) {
            throw new IllegalArgumentException("缺少分红提案：" + meeting.meetingId());
        }
        DividendModel.SchemeRequest value = proposal.request();
        Map<String, Object> source = new TreeMap<>();
        Map<String, Object> request = new TreeMap<>();
        request.put("schemeId", value.schemeId());
        request.put("symbol", value.symbol());
        request.put("issuerOwnerId", value.issuerOwnerId());
        request.put("companyAccountId", value.companyAccountId());
        request.put("perShare", value.perShare().toPlainString());
        request.put("recordAtMillis", value.recordAtMillis());
        request.put("exAtMillis", value.exAtMillis());
        request.put("payAtMillis", value.payAtMillis());
        source.put("request", request);

        Map<String, Object> charterSource = new TreeMap<>();
        charterSource.put("companyPartyId", charter.companyPartyId());
        charterSource.put("chairSeatId", charter.chairSeatId());
        charterSource.put("quorum", charter.quorum());
        List<Seat> seatList = new ArrayList<>(charter.seats());
        seatList.sort(Comparator.comparing(Seat::seatId));
        List<Map<String, Object>> seats = new ArrayList<>();
        for (Seat seat : seatList) {
            Map<String, Object> row = new TreeMap<>();
            row.put("seatId", seat.seatId());
            row.put("partyId", seat.partyId());
            seats.add(row);
        }
        charterSource.put("seats", seats);
        source.put("charter", charterSource);

        Map<String, Object> meetingSource = new TreeMap<>();
        meetingSource.put("meetingId", meeting.meetingId());
        meetingSource.put("topic", meeting.topic().name());
        meetingSource.put("convenerSeatId", meeting.convenerSeatId());
        meetingSource.put("createdAt", meeting.createdAt());
        meetingSource.put("materialDeadline", meeting.materialDeadline());
        meetingSource.put("opensAt", meeting.opensAt());
        meetingSource.put("closesAt", meeting.closesAt());
        meetingSource.put("attendees", List.copyOf(meeting.attendeeSeatIds()));
        meetingSource.put("recused", List.copyOf(meeting.recusedSeatIds()));
        meetingSource.put("related", List.copyOf(proposal.relatedSeatIds()));
        meetingSource.put("proposerSeatId", proposal.proposerSeatId());
        meetingSource.put("proposedAtMillis", proposal.proposedAtMillis());
        List<Map<String, Object>> materials = new ArrayList<>();
        for (MaterialRef material : meeting.materials()) {
            Map<String, Object> row = new TreeMap<>();
            row.put("materialId", material.materialId());
            row.put("sourceRef", material.sourceRef());
            row.put("version", material.version());
            row.put("asOf", material.asOf());
            materials.add(row);
        }
        meetingSource.put("materials", materials);
        List<Ballot> ballotList = new ArrayList<>(meeting.ballots().values());
        ballotList.sort(Comparator.comparing(Ballot::seatId));
        List<Map<String, Object>> ballots = new ArrayList<>();
        for (Ballot ballot : ballotList) {
            Map<String, Object> row = new TreeMap<>();
            row.put("seatId", ballot.seatId());
            row.put("vote", ballot.vote().name());
            row.put("castAt", ballot.castAt());
            ballots.add(row);
        }
        meetingSource.put("ballots", ballots);
        source.put("meeting", meetingSource);
        source.put("issuedAtMillis", issuedAtMillis);
        try {
            return AUTHORIZATION_DOMAIN + ":" + sha256Hex(MAPPER.writeValueAsString(source));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("授权来源无法规范化", failure);
        }
    }

    private static String computeAppointmentAuthorizationRef(Charter charter, Meeting meeting,
                                                              long issuedAtMillis) {
        AppointmentProposal proposal = meeting.appointmentProposal();
        if (proposal == null) {
            throw new IllegalArgumentException("缺少任免提案：" + meeting.meetingId());
        }
        AppointmentRequest value = proposal.request();
        Map<String, Object> source = new TreeMap<>();
        Map<String, Object> request = new TreeMap<>();
        request.put("appointmentId", value.appointmentId());
        request.put("personPartyId", value.personPartyId());
        request.put("companyPartyId", value.companyPartyId());
        request.put("role", value.role().name());
        request.put("action", value.action().name());
        request.put("effectiveAt", value.effectiveAt());
        request.put("endsAt", value.endsAt());
        source.put("request", request);

        Map<String, Object> charterSource = new TreeMap<>();
        charterSource.put("companyPartyId", charter.companyPartyId());
        charterSource.put("chairSeatId", charter.chairSeatId());
        charterSource.put("quorum", charter.quorum());
        List<Seat> seatList = new ArrayList<>(charter.seats());
        seatList.sort(Comparator.comparing(Seat::seatId));
        List<Map<String, Object>> seats = new ArrayList<>();
        for (Seat seat : seatList) {
            Map<String, Object> row = new TreeMap<>();
            row.put("seatId", seat.seatId());
            row.put("partyId", seat.partyId());
            seats.add(row);
        }
        charterSource.put("seats", seats);
        source.put("charter", charterSource);

        Map<String, Object> meetingSource = new TreeMap<>();
        meetingSource.put("meetingId", meeting.meetingId());
        meetingSource.put("topic", meeting.topic().name());
        meetingSource.put("convenerSeatId", meeting.convenerSeatId());
        meetingSource.put("createdAt", meeting.createdAt());
        meetingSource.put("materialDeadline", meeting.materialDeadline());
        meetingSource.put("opensAt", meeting.opensAt());
        meetingSource.put("closesAt", meeting.closesAt());
        meetingSource.put("attendees", List.copyOf(meeting.attendeeSeatIds()));
        meetingSource.put("recused", List.copyOf(meeting.recusedSeatIds()));
        meetingSource.put("related", List.copyOf(proposal.relatedSeatIds()));
        meetingSource.put("proposerSeatId", proposal.proposerSeatId());
        meetingSource.put("proposedAtMillis", proposal.proposedAtMillis());
        List<Map<String, Object>> materials = new ArrayList<>();
        for (MaterialRef material : meeting.materials()) {
            Map<String, Object> row = new TreeMap<>();
            row.put("materialId", material.materialId());
            row.put("sourceRef", material.sourceRef());
            row.put("version", material.version());
            row.put("asOf", material.asOf());
            materials.add(row);
        }
        meetingSource.put("materials", materials);
        List<Ballot> ballotList = new ArrayList<>(meeting.ballots().values());
        ballotList.sort(Comparator.comparing(Ballot::seatId));
        List<Map<String, Object>> ballots = new ArrayList<>();
        for (Ballot ballot : ballotList) {
            Map<String, Object> row = new TreeMap<>();
            row.put("seatId", ballot.seatId());
            row.put("vote", ballot.vote().name());
            row.put("castAt", ballot.castAt());
            ballots.add(row);
        }
        meetingSource.put("ballots", ballots);
        source.put("meeting", meetingSource);
        source.put("issuedAtMillis", issuedAtMillis);
        try {
            return APPOINTMENT_AUTHORIZATION_DOMAIN + ":"
                    + sha256Hex(MAPPER.writeValueAsString(source));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("任免授权来源无法规范化", failure);
        }
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("缺少 SHA-256 实现", failure);
        }
    }

    private static boolean hasSource(Meeting meeting, String sourceRef) {
        for (MaterialRef material : meeting.materials()) {
            if (material.sourceRef().equals(sourceRef)) {
                return true;
            }
        }
        return false;
    }

    private static Budget budgetOf(State state, long period, BudgetCategory category) {
        Budget budget = state.budget(period, category);
        if (budget == null) {
            throw new IllegalArgumentException("该期间类别未设置预算上限：" + period + " / " + category);
        }
        return budget;
    }

    private static Commitment commitmentOf(State state, String commitmentId) {
        for (Commitment commitment : state.commitments()) {
            if (commitment.commitmentId().equals(commitmentId)) {
                return commitment;
            }
        }
        return null;
    }

    private static Meeting meetingOf(State state, String meetingId) {
        requireText(meetingId, "会议标识");
        return state.meeting(meetingId);
    }

    private static Party party(OpeningRegistry registry, String partyId) {
        for (Party party : registry.parties()) {
            if (party.partyId().equals(partyId)) {
                return party;
            }
        }
        return null;
    }

    private static BigDecimal outstandingOf(BigDecimal reserved, BigDecimal spent,
                                            BigDecimal released) {
        BigDecimal outstanding = reserved.subtract(spent).subtract(released);
        if (outstanding.signum() < 0) {
            throw new IllegalArgumentException("承诺已花与已释放超过占用金额："
                    + spent.toPlainString() + " + " + released.toPlainString()
                    + " > " + reserved.toPlainString());
        }
        return outstanding;
    }

    private static Outcome accepted(State state, String reason) {
        return new Outcome(true, reason, state);
    }

    private static Outcome rejected(State state, String reason) {
        return new Outcome(false, reason, state);
    }

    private static void requireState(State state) {
        Objects.requireNonNull(state, "董事会状态");
    }

    private static void requirement(Object value, String field) {
        Objects.requireNonNull(value, field);
    }

    private static void requireTime(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("模拟时刻不能为负：" + value);
        }
    }

    private static void requirePeriod(long period) {
        if (period < 0) {
            throw new IllegalArgumentException("实验期间不能为负：" + period);
        }
    }

    private static List<String> sortedDistinct(List<String> source, String field) {
        List<String> copy = new ArrayList<>(Objects.requireNonNull(source, field + "不能为空"));
        for (String value : copy) {
            requireText(value, field);
        }
        copy.sort(Comparator.naturalOrder());
        for (int i = 1; i < copy.size(); i++) {
            if (copy.get(i - 1).equals(copy.get(i))) {
                throw new IllegalArgumentException(field + "重复：" + copy.get(i));
            }
        }
        return List.copyOf(copy);
    }

    private static BigDecimal money2(BigDecimal value, String field, boolean positive) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + "必须精确到分：" + value, failure);
        }
        if (positive && scaled.signum() <= 0) {
            throw new IllegalArgumentException(field + "必须为正：" + value);
        }
        if (!positive && scaled.signum() < 0) {
            throw new IllegalArgumentException(field + "不能为负：" + value);
        }
        return scaled;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
