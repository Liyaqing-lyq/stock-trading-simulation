package edu.cufe.auction.world;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/** 研发经理的有限动作与方案观察；不向模型输出随机结果或未读证据。 */
public final class ResearchDecisionTools {
    private ResearchDecisionTools() { }
    /** 研发经理工具名。 */
    public static final String ACT = "research.act";
    /** 有相应权限的药企董事可提议商业化，或执行已通过的决议。 */
    public static final String BOARD = "board.research";
    /** 实验动作工时；阶段研发时间另计。 */
    public static final long ACTION_MILLIS = 1_000;

    /** @param action REGISTER/BEGIN/APPROVE @param projectId 冻结项目 @param stage BEGIN 阶段，其余留空 @param meetingId APPROVE 的真实决议会议，其余留空 */
    public record Draft(String action, String projectId, String stage, String meetingId) {
        /** 核对动作及必要字段；人物、公司与任职不由模型自报。 */
        public Draft {
            if (action == null || !Set.of("REGISTER", "BEGIN", "APPROVE").contains(action)
                    || projectId == null || projectId.isBlank() || stage == null || meetingId == null) {
                throw new IllegalArgumentException("研发工具参数非法");
            }
            if ("BEGIN".equals(action)) ResearchModel.Stage.valueOf(stage);
            if ("APPROVE".equals(action) && meetingId.isBlank()) throw new IllegalArgumentException("必须引用商业化决议会议");
        }
    }

    /** @param base 原冻结配置 @return 显式增加研发经理工具的配置 */
    public static DecisionModel.Config config(DecisionModel.Config base) {
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        actions.put(ACT, ACTION_MILLIS);
        return new DecisionModel.Config(base.providerKind(), base.providerId(), base.pricingVersion(),
                base.thinkMillisByWake(), actions, base.limits(), base.budget(), base.profile());
    }

    /** @param run 研发运行 @param board 本公司董事会 @param now 世界时点
     * @param recalledEvidence 检查本人此时能回忆的证据，旧版投影传 null
     * @return 方案、已批准预算及当前版本可见的项目进度 */
    static Map<String, Object> observation(ResearchRun run, BoardRun board, long now,
                                           Predicate<String> recalledEvidence, boolean readerGated,
                                           Map<String, String> funds) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("companyId", run.companyPartyId());
        if (funds != null) view.put("funds", funds);
        view.put("actions", List.of("REGISTER", "BEGIN", "APPROVE"));
        view.put("projects", new TreeMap<>(run.projects()).values().stream().map(spec -> new TreeMap<String, Object>(Map.of(
                "projectId", spec.projectId(), "budget", spec.budget().toPlainString(),
                "stages", spec.stages().stream().map(plan -> new TreeMap<>(Map.of("stage", plan.stage().name(),
                        "cost", plan.cost().toPlainString(), "durationMillis", Long.toString(plan.durationMillis())))).toList()))).toList());
        var category = BoardModel.BudgetCategory.PROCUREMENT;
        long period = now / PeriodicModel.PERIOD_MILLIS;
        var budget = board.board().budget(period, category);
        if (budget != null) {
            view.put("approvedBudgetCap", budget.cap().toPlainString());
            view.put("remainingBudget", BoardModel.availableBudget(board.board(), period, category).toPlainString());
        }
        if (recalledEvidence != null) {
            view.put("registeredProjects", new TreeMap<>(run.state().projects()).values().stream()
                    .map(project -> projectView(project, board, recalledEvidence, readerGated, funds != null)).toList());
        }
        view.put("rules", "这里只列可选方案；不代表已立项或已通过。立项需真实预算，阶段支出和工时必须实际发生。"
                + "阶段结果证据必须通过资料工具阅读，不能猜测成功。商业化必须引用本公司真实决议；批准不产生销售收入。未用字段留空，可观望。");
        return view;
    }

    private static Map<String, Object> projectView(ResearchModel.Project project, BoardRun board,
                                                  Predicate<String> recalledEvidence, boolean readerGated,
                                                  boolean showPayments) {
        Map<String, Object> row = new TreeMap<>();
        row.put("projectId", project.projectId());
        row.put("registeredAt", project.registeredAt());
        row.put("committedBudget", project.budget().toPlainString());
        row.put("paid", project.paid().toPlainString());
        if (showPayments) {
            row.put("stagePayments", project.stages().stream().filter(ResearchModel.StageRecord::paid)
                    .map(payment -> new TreeMap<String, Object>(Map.of("stage", payment.stage().name(),
                            "paidAt", payment.beganAt(), "amount", payment.plan().cost().toPlainString()))).toList());
        }
        var stage = project.stages().get(0);
        for (var record : project.stages()) {
            if (record.paid()) stage = record;
        }
        row.put("currentStage", stage.stage().name());
        if (stage.paid()) row.put("beganAt", stage.beganAt());
        if (stage.status() == ResearchModel.StageStatus.IN_PROGRESS) row.put("nextResultAt", stage.completesAt());
        var evidence = stage.evidence();
        boolean unread = evidence != null && !recalledEvidence.test(evidence.reference());
        // 失败时未花的承诺会释放。人在读到报告之前，承诺余额不能替报告揭示结果。
        if (!readerGated || !unread) {
            row.put("remainingCommitment", BoardModel.outstandingCommitment(board.board(),
                    ResearchRuntime.commitmentId(project.projectId())).toPlainString());
        }
        if (unread) {
            row.put("stageStatus", "RESULT_RECORDED");
        } else {
            row.put("stageStatus", stage.status().name());
            if (evidence != null) row.put("evidenceDocId", evidence.reference());
            if (stage.status() == ResearchModel.StageStatus.COMPLETED && project.nextStage() != null)
                row.put("nextStage", project.nextStage().name());
        }
        if (project.approvedAt() >= 0) row.put("approvedAt", project.approvedAt());
        return row;
    }
}
