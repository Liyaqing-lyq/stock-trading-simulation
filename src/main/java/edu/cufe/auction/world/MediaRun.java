package edu.cufe.auction.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * M4h.2 媒体运行桥子集：冻结配置、各媒体纯核状态、组织工时预算、已完成工作与角色回报。
 *
 * <p><b>不复制任何业务真相</b>：稿件、正文、阅读经历、谱系都在 {@link MediaModel.State} 与
 * {@link InformationModel.State} 里；本类型只保存"配置 + 各媒体状态 + 预算已耗 + 已完成工作 +
 * 任务级报告"。在途预留不另存台账，一律由 {@code pending} 里的媒体任务按冻结时长推导，
 * 因此不存在可以被单独篡改的"预留余额"。</p>
 *
 * <p><b>一个媒体一个状态</b>：每个媒体组织一条 {@link MediaModel.Policy} 与一个
 * {@link MediaModel.State}；纯核冻结策略，运行层从不替换。跨媒体谱系由运行层把同一完成 cut 的
 * 全部状态一起传给 {@code MediaModel.submit(..., mediaContext)}。</p>
 *
 * <p><b>预算口径</b>：{@code totalMillis} 是整次运行的实验上限，没有日历重置；
 * {@code spentMillis} 只由 {@link Work#chargedMillis()} 累计；可用额度 =
 * 总额 − 已耗 − 在途预留。正常完成按任务时长计费，取消按实际已过时长计费并释放未用部分，
 * 业务拒绝同样计费（人已经工作了这段时间）。</p>
 *
 * <p><b>恢复口径</b>：构造期核对"已耗 == 全部工作计费之和""标识唯一""任务种类已登记""计费不超过
 * 时长"，因此候选里被改过的汇总数字会被拒绝，而不只是被 checksum 保护。</p>
 */
public record MediaRun(Config config, List<MediaModel.State> states, List<Budget> budgets,
                       List<Work> works, Map<String, Report> reports) {

    /** 正常完成。 */
    public static final String OUTCOME_COMPLETED = "COMPLETED";
    /** 到期前取消。 */
    public static final String OUTCOME_CANCELED = "CANCELED";
    /** 工时不足，业务拒绝（不计费、不改业务状态）。 */
    public static final String OUTCOME_REJECTED = "REJECTED";

    /** 提出选题。 */
    public static final String KIND_PROPOSE = PendingTask.MEDIA_STORY_PROPOSE_V1;
    /** 提交组织草稿。 */
    public static final String KIND_SUBMIT = PendingTask.MEDIA_STORY_SUBMIT_V1;
    /** 编辑审阅。 */
    public static final String KIND_REVIEW = PendingTask.MEDIA_STORY_REVIEW_V1;
    /** 发布。 */
    public static final String KIND_PUBLISH = PendingTask.MEDIA_STORY_PUBLISH_V1;
    /** 开启更正。 */
    public static final String KIND_CORRECTION = PendingTask.MEDIA_STORY_CORRECTION_V1;
    /** 取消本人未完成的媒体工作。 */
    public static final String KIND_CANCEL = PendingTask.MEDIA_WORK_CANCEL_V1;

    /** 证据共享：结构计费按真实跨度，冻结值由运行层用 EvidenceRun.Config 核对。 */
    public static final String KIND_EVIDENCE_SHARE = PendingTask.MEDIA_EVIDENCE_SHARE_V1;

    /** 本桥登记的全部媒体任务种类。 */
    public static final Set<String> KINDS = Set.of(KIND_PROPOSE, KIND_SUBMIT, KIND_REVIEW,
            KIND_PUBLISH, KIND_CORRECTION, KIND_CANCEL, KIND_EVIDENCE_SHARE);

    /** 采访协调种类（schema14 才可能出现）：只进同一工时账，不进媒体故事流。 */
    public static final Set<String> INTERVIEW_KINDS = Set.of(
            PendingTask.INTERVIEW_INVITE_V1, PendingTask.INTERVIEW_RESPOND_V1,
            PendingTask.INTERVIEW_CANCEL_V1, PendingTask.INTERVIEW_END_V1,
            PendingTask.INTERVIEW_START_GATE_V1, PendingTask.INTERVIEW_MISS_GATE_V1,
            PendingTask.INTERVIEW_END_GATE_V1, PendingTask.INTERVIEW_TTL_GATE_V1);

    /** 会话区间结算种类：一条 Work 结算一次实际同步区间。 */
    public static final String KIND_INTERVIEW_SESSION = "INTERVIEW_SESSION_V1";

    /**
     * 是否为已登记的工作种类（媒体 + 采访 + 会话结算）。
     *
     * <p>采访种类只在 schema14 的运行里出现；本方法只做种类登记，不放宽任何旧档案的权限或校验。</p>
     *
     * @param kind 任务/工作种类
     * @return 已登记返回 true
     */
    public static boolean knownKind(String kind) {
        return kind != null && (KINDS.contains(kind) || INTERVIEW_KINDS.contains(kind)
                || KIND_INTERVIEW_SESSION.equals(kind) || KIND_EVIDENCE_SHARE.equals(kind));
    }

    /**
     * 冻结配置：各媒体策略、每组织工时总额、三档动作时长。
     *
     * @param policies        每个媒体组织一条纯核策略
     * @param budgetMillis    媒体组织 → 整次运行工时总额，必须为正
     * @param proposeMillis   提出选题时长，正毫秒
     * @param correctionMillis 开启更正时长，正毫秒
     * @param cancelMillis    取消动作时长，正毫秒
     */
    public record Config(List<MediaModel.Policy> policies, Map<String, Long> budgetMillis,
                         long proposeMillis, long correctionMillis, long cancelMillis) {

        /** 校验组织唯一、预算与时长。 */
        public Config {
            List<MediaModel.Policy> list = new ArrayList<>(
                    Objects.requireNonNull(policies, "媒体策略"));
            if (list.isEmpty()) {
                throw new IllegalArgumentException("媒体运行至少需要一个媒体组织");
            }
            Set<String> organizations = new LinkedHashSet<>();
            for (MediaModel.Policy policy : list) {
                Objects.requireNonNull(policy, "媒体策略");
                if (!organizations.add(policy.mediaOrganizationPartyId())) {
                    throw new IllegalArgumentException("同一媒体组织只能有一条策略："
                            + policy.mediaOrganizationPartyId());
                }
            }
            list.sort(Comparator.comparing(MediaModel.Policy::mediaOrganizationPartyId));
            policies = List.copyOf(list);

            Map<String, Long> budgetsMap = new TreeMap<>(
                    Objects.requireNonNull(budgetMillis, "组织工时预算"));
            if (budgetsMap.size() != organizations.size()
                    || !budgetsMap.keySet().equals(organizations)) {
                throw new IllegalArgumentException("工时预算必须与媒体策略一一对应");
            }
            for (Map.Entry<String, Long> entry : budgetsMap.entrySet()) {
                if (entry.getValue() == null || entry.getValue() < 1) {
                    throw new IllegalArgumentException("组织工时总额必须为正："
                            + entry.getKey() + " -> " + entry.getValue());
                }
            }
            budgetMillis = Collections.unmodifiableMap(budgetsMap);
            if (proposeMillis < 1 || correctionMillis < 1 || cancelMillis < 1) {
                throw new IllegalArgumentException("提出选题/更正/取消时长必须为正毫秒");
            }
        }

        /** @return 该组织的冻结策略；不存在返回 null */
        public MediaModel.Policy policyOf(String organizationPartyId) {
            for (MediaModel.Policy policy : policies) {
                if (policy.mediaOrganizationPartyId().equals(organizationPartyId)) {
                    return policy;
                }
            }
            return null;
        }
    }

    /**
     * 一个组织的工时账户。
     *
     * @param organizationPartyId 媒体组织
     * @param totalMillis         整次运行上限
     * @param spentMillis         已由完成工作消耗的毫秒
     */
    public record Budget(String organizationPartyId, long totalMillis, long spentMillis) {

        /** 校验范围。 */
        public Budget {
            if (organizationPartyId == null || organizationPartyId.isBlank()) {
                throw new IllegalArgumentException("预算组织不能为空");
            }
            if (totalMillis < 1 || spentMillis < 0 || spentMillis > totalMillis) {
                throw new IllegalArgumentException("预算金额非法：" + organizationPartyId);
            }
        }

        /** @return 追加已耗后的新预算 */
        public Budget charged(long millis) {
            if (millis < 0 || spentMillis + millis > totalMillis) {
                throw new IllegalArgumentException("工时计费超出总额：" + organizationPartyId);
            }
            return new Budget(organizationPartyId, totalMillis, spentMillis + millis);
        }
    }

    /**
     * 一条已完成的媒体工作。
     *
     * @param taskId           内部任务标识
     * @param personPartyId    执行人
     * @param organizationPartyId 媒体组织
     * @param kind             任务种类
     * @param storyId          关联稿件
     * @param startedAtMillis  开始时点（到期减时长）
     * @param completedAtMillis 结束时点
     * @param chargedMillis    实际计费
     * @param outcome          {@link #OUTCOME_COMPLETED} 或 {@link #OUTCOME_CANCELED}
     */
    public record Work(String taskId, String personPartyId, String organizationPartyId, String kind,
                       String storyId, long startedAtMillis, long completedAtMillis,
                       long chargedMillis, String outcome) {

        /** 校验字段与种类。 */
        public Work {
            if (taskId == null || taskId.isBlank() || personPartyId == null
                    || personPartyId.isBlank() || organizationPartyId == null
                    || organizationPartyId.isBlank()) {
                throw new IllegalArgumentException("工作记录标识不能为空");
            }
            if (!knownKind(kind)) {
                throw new IllegalArgumentException("未登记的工作种类：" + kind);
            }
            if (storyId == null) {
                storyId = "";
            }
            if (startedAtMillis < 0 || completedAtMillis <= startedAtMillis
                    || chargedMillis < 0) {
                throw new IllegalArgumentException("工作记录时间或计费非法：" + taskId);
            }
            if (chargedMillis > completedAtMillis - startedAtMillis) {
                throw new IllegalArgumentException("计费不能超过实际占用：" + taskId);
            }
            if (!OUTCOME_COMPLETED.equals(outcome) && !OUTCOME_CANCELED.equals(outcome)
                    && !OUTCOME_REJECTED.equals(outcome)) {
                throw new IllegalArgumentException("未登记的工作结果：" + outcome);
            }
        }
    }

    /**
     * 任务级业务回报。
     *
     * @param taskId       内部任务标识
     * @param personPartyId 执行人
     * @param kind         任务种类
     * @param accepted     业务是否成功
     * @param reason       结果代码
     * @param storyId      关联稿件；无关联为空串
     * @param timeMillis   完成时点
     * @param position     提交位置
     */
    public record Report(String taskId, String personPartyId, String kind, boolean accepted,
                         String reason, String storyId, long timeMillis, long position) {

        /** 校验字段。 */
        public Report {
            if (taskId == null || taskId.isBlank() || personPartyId == null
                    || personPartyId.isBlank() || !KINDS.contains(kind)) {
                throw new IllegalArgumentException("媒体报告任务或种类非法：" + taskId);
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("媒体报告缺少结果代码：" + taskId);
            }
            if (storyId == null) {
                storyId = "";
            }
            if (timeMillis < 0 || position < 1) {
                throw new IllegalArgumentException("媒体报告时点或位置非法：" + taskId);
            }
        }
    }

    /** 深拷贝、排序并校验配置与预算/工作/报告的一致性。 */
    public MediaRun {
        Objects.requireNonNull(config, "媒体配置");
        List<MediaModel.State> stateList = new ArrayList<>(Objects.requireNonNull(states, "媒体状态"));
        stateList.sort(Comparator.comparing(state -> state.policy().mediaOrganizationPartyId()));
        Map<String, MediaModel.State> byOrganization = new LinkedHashMap<>();
        for (MediaModel.State state : stateList) {
            String organization = state.policy().mediaOrganizationPartyId();
            if (byOrganization.put(organization, state) != null) {
                throw new IllegalArgumentException("同一媒体组织只能有一个状态：" + organization);
            }
            if (config.policyOf(organization) == null) {
                throw new IllegalArgumentException("媒体状态没有对应冻结策略：" + organization);
            }
            if (!config.policyOf(organization).equals(state.policy())) {
                throw new IllegalArgumentException("媒体状态策略与冻结配置不一致：" + organization);
            }
        }
        if (byOrganization.size() != config.policies().size()) {
            throw new IllegalArgumentException("媒体状态必须覆盖全部配置组织");
        }

        List<Budget> budgetList = new ArrayList<>(Objects.requireNonNull(budgets, "工时预算"));
        budgetList.sort(Comparator.comparing(Budget::organizationPartyId));
        Set<String> budgetOrganizations = new LinkedHashSet<>();
        for (Budget budget : budgetList) {
            if (!budgetOrganizations.add(budget.organizationPartyId())) {
                throw new IllegalArgumentException("重复的工时预算：" + budget.organizationPartyId());
            }
            Long total = config.budgetMillis().get(budget.organizationPartyId());
            if (total == null || total != budget.totalMillis()) {
                throw new IllegalArgumentException("工时总额与冻结配置不一致："
                        + budget.organizationPartyId());
            }
        }
        if (!budgetOrganizations.equals(byOrganization.keySet())) {
            throw new IllegalArgumentException("工时预算必须覆盖全部媒体组织");
        }

        List<Work> workList = new ArrayList<>(Objects.requireNonNull(works, "已完成工作"));
        workList.sort(Comparator.comparing(Work::taskId));
        Map<String, Long> chargedByOrganization = new LinkedHashMap<>();
        Set<String> taskIds = new LinkedHashSet<>();
        for (Work work : workList) {
            if (!taskIds.add(work.taskId())) {
                throw new IllegalArgumentException("重复的工作记录：" + work.taskId());
            }
            if (config.policyOf(work.organizationPartyId()) == null) {
                throw new IllegalArgumentException("工作记录指向未配置的媒体组织："
                        + work.organizationPartyId());
            }
            long span = work.completedAtMillis() - work.startedAtMillis();
            boolean interview = INTERVIEW_KINDS.contains(work.kind())
                    || KIND_INTERVIEW_SESSION.equals(work.kind())
                    || KIND_EVIDENCE_SHARE.equals(work.kind());
            // 采访种类不接受冻结时长查表：计费上界就是它自己占用的真实跨度，
            // 由运行层用原命令/会话事实重算，Work 不能自报一个更短的时长。
            long allowed = interview ? span
                    : durationOf(config, work.kind(), work.organizationPartyId());
            boolean control = KIND_CANCEL.equals(work.kind())
                    || PendingTask.INTERVIEW_CANCEL_V1.equals(work.kind())
                    || PendingTask.INTERVIEW_END_V1.equals(work.kind());
            if (control) {
                // 控制动作：有模拟耗时，但不预留、不消耗组织采编工时，撤职/无预算也能执行
                if (work.chargedMillis() != 0) {
                    throw new IllegalArgumentException("控制动作不得消耗组织工时：" + work.taskId());
                }
            } else if (OUTCOME_REJECTED.equals(work.outcome())) {
                if (work.chargedMillis() != 0) {
                    throw new IllegalArgumentException("预算拒绝不得计费：" + work.taskId());
                }
            } else if (work.chargedMillis() > allowed) {
                throw new IllegalArgumentException("工作计费超过允许时长：" + work.taskId());
            } else if (OUTCOME_COMPLETED.equals(work.outcome())
                    && work.chargedMillis() != allowed) {
                throw new IllegalArgumentException("正常完成必须按实际区间计费：" + work.taskId());
            }
            chargedByOrganization.merge(work.organizationPartyId(), work.chargedMillis(), Long::sum);
        }
        for (Budget budget : budgetList) {
            long charged = chargedByOrganization.getOrDefault(budget.organizationPartyId(), 0L);
            if (charged != budget.spentMillis()) {
                throw new IllegalArgumentException("已耗工时与工作记录不一致："
                        + budget.organizationPartyId() + " budget=" + budget.spentMillis()
                        + " works=" + charged);
            }
        }

        Map<String, Report> reportMap = new LinkedHashMap<>(Objects.requireNonNull(reports, "媒体报告"));
        Map<String, Report> sortedReports = new TreeMap<>(reportMap);
        for (Map.Entry<String, Report> entry : sortedReports.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().taskId())) {
                throw new IllegalArgumentException("媒体报告索引键与任务标识不一致："
                        + entry.getKey());
            }
        }

        states = List.copyOf(stateList);
        budgets = List.copyOf(budgetList);
        works = List.copyOf(workList);
        reports = Collections.unmodifiableMap(sortedReports);
    }

    /** 该组织的任务时长（与冻结策略一致）。 */
    public static long durationOf(Config config, String kind, String organizationPartyId) {
        MediaModel.Policy policy = config.policyOf(organizationPartyId);
        if (policy == null) {
            throw new IllegalArgumentException("未配置的媒体组织：" + organizationPartyId);
        }
        return switch (kind) {
            case KIND_PROPOSE -> config.proposeMillis();
            case KIND_SUBMIT -> policy.writeMillis();
            case KIND_REVIEW -> policy.reviewMillis();
            case KIND_PUBLISH -> policy.publishMillis();
            case KIND_CORRECTION -> config.correctionMillis();
            case KIND_CANCEL -> config.cancelMillis();
            default -> throw new IllegalArgumentException("未登记的媒体任务种类：" + kind);
        };
    }

    /**
     * 开局：按冻结配置建立各媒体纯核状态。
     *
     * @param config   冻结配置
     * @param registry 固定开局登记
     * @return 空媒体运行子集
     * @throws IllegalArgumentException 配置非法、组织未登记或预算与策略不一致
     */
    public static MediaRun open(Config config, OpeningRegistry registry) {
        Objects.requireNonNull(config, "媒体配置");
        List<MediaModel.State> states = new ArrayList<>();
        List<Budget> budgets = new ArrayList<>();
        for (MediaModel.Policy policy : config.policies()) {
            states.add(MediaModel.open(policy, registry));
            budgets.add(new Budget(policy.mediaOrganizationPartyId(),
                    config.budgetMillis().get(policy.mediaOrganizationPartyId()), 0L));
        }
        return new MediaRun(config, states, budgets, List.of(), Map.of());
    }

    /** @return 该组织纯核状态；不存在返回 null */
    public MediaModel.State stateOf(String organizationPartyId) {
        for (MediaModel.State state : states) {
            if (state.policy().mediaOrganizationPartyId().equals(organizationPartyId)) {
                return state;
            }
        }
        return null;
    }

    /** @return 该组织预算；不存在返回 null */
    public Budget budgetOf(String organizationPartyId) {
        for (Budget budget : budgets) {
            if (budget.organizationPartyId().equals(organizationPartyId)) {
                return budget;
            }
        }
        return null;
    }

    /** @return 该组织在途预留：由运行层按真实待办解码后的清单推导；控制动作与采访种类不在此处计价 */
    public long reservedMillis(String organizationPartyId, List<PendingWork> inFlight) {
        long reserved = 0L;
        for (PendingWork work : Objects.requireNonNull(inFlight, "在途媒体工作")) {
            if (!knownKind(work.kind())) {
                throw new IllegalArgumentException("未登记的工作种类：" + work.kind());
            }
            if (INTERVIEW_KINDS.contains(work.kind())) {
                // 采访的预留按"完整预约区间"由运行层另算并显式传入，避免这里按动作时长低估
                continue;
            }
            if (KIND_CANCEL.equals(work.kind())) {
                continue;
            }
            if (organizationPartyId.equals(work.organizationPartyId())) {
                reserved += durationOf(config, work.kind(), work.organizationPartyId());
            }
        }
        return reserved;
    }

    /** @return 该组织可用工时；不含在途预留时传空表 */
    public long availableMillis(String organizationPartyId, List<PendingWork> inFlight) {
        return availableMillis(organizationPartyId, inFlight, 0L);
    }

    /**
     * 该组织可用工时，并显式扣除采访预约的完整在途预留。
     *
     * <p>采访的预留由运行层按"接受后整段预约区间"另算：已消耗但尚未结算的工时不能提前释放，
     * 因此这里只做一次减法，不引进第二份余额。{@code extraReservedMillis} 为负按 0 处理。</p>
     *
     * @param organizationPartyId  媒体组织
     * @param inFlight             媒体在途工作
     * @param extraReservedMillis  采访在途预留（完整预约区间之和）
     * @return 可用工时，可能为负（表示已透支，调用方按预算不足处理）
     */
    public long availableMillis(String organizationPartyId, List<PendingWork> inFlight,
                                long extraReservedMillis) {
        Budget budget = budgetOf(organizationPartyId);
        if (budget == null) {
            return 0L;
        }
        return budget.totalMillis() - budget.spentMillis()
                - reservedMillis(organizationPartyId, inFlight)
                - Math.max(0L, extraReservedMillis);
    }

    /**
     * 一条在途媒体工作：由运行层从真实待办解码得到，不另存台账。
     *
     * @param taskId              内部任务标识
     * @param organizationPartyId 媒体组织
     * @param kind                任务种类
     */
    public record PendingWork(String taskId, String organizationPartyId, String kind) {

        /** 校验字段与种类。 */
        public PendingWork {
            if (taskId == null || taskId.isBlank() || organizationPartyId == null
                    || organizationPartyId.isBlank() || !KINDS.contains(kind)) {
                throw new IllegalArgumentException("在途媒体工作字段非法：" + taskId);
            }
        }
    }

    /** @return 替换某组织状态后的新子集 */
    public MediaRun withState(String organizationPartyId, MediaModel.State next) {
        List<MediaModel.State> copy = new ArrayList<>(states);
        for (int index = 0; index < copy.size(); index++) {
            if (copy.get(index).policy().mediaOrganizationPartyId().equals(organizationPartyId)) {
                copy.set(index, Objects.requireNonNull(next, "新媒体状态"));
                return new MediaRun(config, copy, budgets, works, reports);
            }
        }
        throw new IllegalArgumentException("未配置的媒体组织：" + organizationPartyId);
    }

    /** 一次性记入计费、工作记录与报告，保证"已耗 == 工作计费之和"始终成立。 */
    public MediaRun withOutcome(String organizationPartyId, long chargeMillis,
                                List<Work> finished, Report report) {
        Objects.requireNonNull(finished, "工作记录");
        List<Budget> budgetCopy = new ArrayList<>(budgets);
        boolean charged = false;
        for (int index = 0; index < budgetCopy.size(); index++) {
            if (budgetCopy.get(index).organizationPartyId().equals(organizationPartyId)) {
                budgetCopy.set(index, budgetCopy.get(index).charged(chargeMillis));
                charged = true;
            }
        }
        if (!charged) {
            throw new IllegalArgumentException("未配置的媒体组织：" + organizationPartyId);
        }
        List<Work> workCopy = new ArrayList<>(works);
        workCopy.addAll(finished);
        Map<String, Report> reportCopy = new TreeMap<>(reports);
        if (report != null) {
            Report existing = reportCopy.get(report.taskId());
            if (existing != null && !existing.equals(report)) {
                throw new IllegalArgumentException("同一任务只能有一条媒体报告："
                        + report.taskId());
            }
            reportCopy.put(report.taskId(), report);
        }
        return new MediaRun(config, states, budgetCopy, workCopy, reportCopy);
    }

    /** @return 追加一条工作记录后的新子集 */
    public MediaRun withWork(Work work) {
        List<Work> copy = new ArrayList<>(works);
        copy.add(Objects.requireNonNull(work, "工作记录"));
        return new MediaRun(config, states, budgets, copy, reports);
    }

    /** @return 追加一条报告后的新子集；同一任务重复且不同内容直接拒绝 */
    public MediaRun withReport(Report report) {
        Objects.requireNonNull(report, "媒体报告");
        Report existing = reports.get(report.taskId());
        if (existing != null) {
            if (!existing.equals(report)) {
                throw new IllegalArgumentException("同一任务只能有一条媒体报告："
                        + report.taskId());
            }
            return this;
        }
        Map<String, Report> copy = new TreeMap<>(reports);
        copy.put(report.taskId(), report);
        return new MediaRun(config, states, budgets, works, copy);
    }

    /** @return 该组织已完成的媒体工作（按任务标识排序） */
    public List<Work> worksOf(String organizationPartyId) {
        List<Work> mine = new ArrayList<>();
        for (Work work : works) {
            if (work.organizationPartyId().equals(organizationPartyId)) {
                mine.add(work);
            }
        }
        return List.copyOf(mine);
    }
}
