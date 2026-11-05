package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 治理子集：唯一世界日志里的不可变董事会运行包装。
 *
 * <p>本类型只承载三样东西：纯核 {@link BoardModel.State}、按 taskId 索引的管理审计回报，以及按
 * 期间索引的<b>截止快照</b>考核证据。它<b>不</b>保存现金、账户余额、库存或第二份公司账；唯一现金
 * 仍然只保存在 {@link CashLedger} 的账户表里，预算只是有限支出权限而不是资产。</p>
 *
 * <p><b>管理视图边界</b>：本对象是管理端只读状态，不面向不可信角色直接暴露。自动登记的会议材料
 * 不等于任何人物已经阅读；显式 seatId 只是受信管理夹具的身份声明，不是 LLM 或真实用户身份认证。</p>
 *
 * <p><b>不可改写</b>：治理回报一经提交不可删改，同一 taskId 只有一条；期间证据按期间唯一关联，
 * 已存在的期间不得被后续快照覆盖。这样 35 日回款不会回写 30 日复盘。</p>
 *
 * @param board          纯核董事会状态
 * @param reports        管理审计回报，按 taskId 索引
 * @param periodEvidence 期间考核证据，按期间索引
 */
public record BoardRun(BoardModel.State board, Map<String, Report> reports,
                       Map<Long, PeriodEvidence> periodEvidence) {

    /** 校验非空与集合深不可变。 */
    public BoardRun {
        Objects.requireNonNull(board, "董事会状态");
        reports = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(reports, "治理回报")));
        for (Map.Entry<String, Report> entry : reports.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().taskId())) {
                throw new IllegalArgumentException("治理回报索引键与任务标识不一致："
                        + entry.getKey() + " / " + entry.getValue().taskId());
            }
        }
        Map<Long, PeriodEvidence> evidence = new LinkedHashMap<>(
                Objects.requireNonNull(periodEvidence, "期间证据"));
        for (Map.Entry<Long, PeriodEvidence> entry : evidence.entrySet()) {
            if (entry.getKey() != entry.getValue().period()) {
                throw new IllegalArgumentException("期间证据索引与期间不一致："
                        + entry.getKey() + " / " + entry.getValue().period());
            }
        }
        periodEvidence = Collections.unmodifiableMap(evidence);
    }

    /**
     * 单条治理管理回报。
     *
     * @param taskId     产生该回报的待办（已登记到世界已用任务表）
     * @param commandId  外部原始命令；系统自动开/关会与期间评分为 {@code system:} 前缀
     * @param kind       待办种类
     * @param meetingId  关联会议；无关联时为空串
     * @param accepted   业务是否成功
     * @param reason     结果代码
     * @param timeMillis 执行时点（取待办到期时间）
     * @param position   结果所在的提交位置
     */
    public record Report(String taskId, String commandId, String kind, String meetingId,
                         boolean accepted, String reason, long timeMillis, long position) {

        /** 校验标识、时点与提交位置。 */
        public Report {
            requireText(taskId, "治理回报任务标识");
            requireText(commandId, "治理回报命令标识");
            requireText(kind, "治理回报任务种类");
            if (meetingId == null) {
                meetingId = "";
            }
            if (timeMillis < 0 || position <= 0) {
                throw new IllegalArgumentException("治理回报时点或提交位置非法："
                        + timeMillis + " / " + position);
            }
        }
    }

    /**
     * 某期间的截止考核证据：只在强制关期时从当时真实台账与现金计算一次。
     *
     * <p>{@code operatingCommitment} 允许为空（该期间没有到期承诺），表示<b>缺失</b>而不是 0；
     * {@code cashSolvency} 与 {@code risk} 按档案冻结规则总是有值。披露与长期投入当前无实现，
     * 因此不会出现在证据里，总分恒为 null。</p>
     *
     * @param period               期间序号
     * @param capturedAt           捕捉时点（强制关期到期时间）
     * @param position             捕捉所在提交位置
     * @param reviewMeetingId      对应复盘会议
     * @param sourceRef            期间证据唯一来源引用（含期间与提交位置）
     * @param reportSource         公司报告材料来源引用
     * @param contractSource       合同材料来源引用
     * @param receivableSource     应收材料来源引用
     * @param operatingCommitment  到期合同兑现率；无到期承诺时为 null
     * @param cashSolvency         现金偿债分
     * @param risk                 未收 AR 非逾期占比
     * @param contractIds          本次证据引用的合同标识
     * @param receivableIds        本次证据引用的应收标识
     */
    public record PeriodEvidence(long period, long capturedAt, long position,
                                 String reviewMeetingId, String sourceRef, String reportSource,
                                 String contractSource, String receivableSource,
                                 BigDecimal operatingCommitment, BigDecimal cashSolvency,
                                 BigDecimal risk, List<String> contractIds,
                                 List<String> receivableIds) {

        /** 校验期间、时点、来源与分值范围。 */
        public PeriodEvidence {
            if (period < 0) {
                throw new IllegalArgumentException("证据期间不能为负：" + period);
            }
            if (capturedAt < 0 || position <= 0) {
                throw new IllegalArgumentException("证据时点或提交位置非法："
                        + capturedAt + " / " + position);
            }
            requireText(reviewMeetingId, "证据复盘会议");
            requireText(sourceRef, "证据来源引用");
            requireText(reportSource, "公司报告来源");
            requireText(contractSource, "合同来源");
            requireText(receivableSource, "应收来源");
            if (!sourceRef.contains("period:" + period) || !sourceRef.contains(":" + position)) {
                throw new IllegalArgumentException("证据来源必须绑定期间与截止位置：" + sourceRef);
            }
            operatingCommitment = optionalScore(operatingCommitment, "经营兑现");
            cashSolvency = score(cashSolvency, "现金偿债");
            risk = score(risk, "风险");
            contractIds = List.copyOf(Objects.requireNonNull(contractIds, "合同引用"));
            receivableIds = List.copyOf(Objects.requireNonNull(receivableIds, "应收引用"));
        }

        private static BigDecimal score(BigDecimal value, String field) {
            BigDecimal checked = optionalScore(value, field);
            if (checked == null) {
                throw new IllegalArgumentException(field + "分值不能为空");
            }
            return checked;
        }

        private static BigDecimal optionalScore(BigDecimal value, String field) {
            if (value == null) {
                return null;
            }
            BigDecimal scaled = CashLedger.money(value);
            if (scaled.signum() < 0 || scaled.compareTo(new BigDecimal("100.00")) > 0) {
                throw new IllegalArgumentException(field + "分值必须在 0..100：" + value);
            }
            return scaled;
        }
    }

    /** @return 该待办的治理回报；不存在返回 null */
    public Report report(String taskId) {
        return reports.get(taskId);
    }

    /** @return 该期间的证据；不存在返回空 */
    public Optional<PeriodEvidence> evidence(long period) {
        return Optional.ofNullable(periodEvidence.get(period));
    }

    /**
     * 追加一条治理回报，不改动已有条目。
     *
     * @param entry 新回报
     * @return 追加后的新子集
     * @throws IllegalArgumentException 同一 taskId 已有不同回报
     */
    public BoardRun withReport(Report entry) {
        Objects.requireNonNull(entry, "治理回报");
        Report existing = reports.get(entry.taskId());
        if (existing != null) {
            if (!existing.equals(entry)) {
                throw new IllegalArgumentException("同一任务只能有一条治理回报：" + entry.taskId());
            }
            return this;
        }
        Map<String, Report> next = new LinkedHashMap<>(reports);
        next.put(entry.taskId(), entry);
        return new BoardRun(board, next, periodEvidence);
    }

    /**
     * 替换董事会候选状态。
     *
     * @param next 候选董事会状态
     * @return 替换后的新子集
     */
    public BoardRun withBoard(BoardModel.State next) {
        return new BoardRun(Objects.requireNonNull(next, "候选董事会状态"), reports,
                periodEvidence);
    }

    /**
     * 登记期间证据；同一期间只能有一条，后续快照不得覆盖。
     *
     * @param evidence 新证据
     * @return 登记后的新子集
     * @throws IllegalArgumentException 该期间已有证据
     */
    public BoardRun withPeriodEvidence(PeriodEvidence evidence) {
        Objects.requireNonNull(evidence, "期间证据");
        if (periodEvidence.containsKey(evidence.period())) {
            throw new IllegalArgumentException("期间证据已存在，不得覆盖：" + evidence.period());
        }
        Map<Long, PeriodEvidence> next = new LinkedHashMap<>(periodEvidence);
        next.put(evidence.period(), evidence);
        return new BoardRun(board, reports, next);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
