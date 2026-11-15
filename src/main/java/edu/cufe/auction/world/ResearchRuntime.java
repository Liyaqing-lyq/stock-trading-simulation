package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * schema22 研发世界接缝的纯操作：把 {@link ResearchModel} 的纯核动作与既有
 * {@link BoardModel} 预算、{@link InformationModel} 受限证据组合成同帧结果。
 *
 * <p>本类不持有现金/账本；只接收当前唯一账户表、该公司真实 BoardRun 与 InformationRun，
 * 返回候选状态。现金仍只由 {@link CashLedger} 承载，预算仍只在公司董事会里。</p>
 */
final class ResearchRuntime {

    /** 冻结规则标识（只用于确定性阶段结果，不对外暴露 seed）。 */
    static final String RULE_VERSION = "research-world-exp-v1";
    /** 系统证据发布身份（不是经理/董事，避免角色自报）。 */
    static final String SYSTEM_PUBLISHER = "research-system";
    /** 证据阅读固定耗时（模拟毫秒）。 */
    static final long EVIDENCE_READ_MILLIS = 1_000L;
    /** 承包方容量拒绝。 */
    static final String REASON_CONTRACTOR_CAPACITY = "CONTRACTOR_CAPACITY";
    /** 商业化未接。 */
    static final String REASON_COMMERCIALIZATION_NOT_CONNECTED =
            "COMMERCIALIZATION_NOT_CONNECTED";

    private ResearchRuntime() {
    }

    /** 一次候选结果；拒绝时原状态/账/董事会/资料原样返回。 */
    record Applied(ResearchRun run, BoardRun board, InformationRun information,
                   Map<String, CashLedger.CashAccount> accounts, boolean accepted,
                   String reason) {
        static Applied rejected(ResearchRun run, BoardRun board, InformationRun information,
                                Map<String, CashLedger.CashAccount> accounts, String reason) {
            return new Applied(run, board, information, accounts, false, reason);
        }
    }

    /** 立项：真实董事会全额预算承诺（PROCUREMENT，当前期）+ 纯核 register；不动现金。 */
    static Applied register(ResearchRun run, BoardRun board, String commandId,
            ResearchModel.ProjectSpec spec, long now, Map<String, CashLedger.CashAccount> accounts) {
        if (run == null || board == null) {
            return Applied.rejected(run, board, null, accounts, "NOT_READY");
        }
        String owner = run.state().config().companyOwnerId();
        long period = now / PeriodicModel.PERIOD_MILLIS;
        BoardModel.Outcome reserved = BoardModel.reserveBudget(board.board(), now,
                commitmentId(spec.projectId()), period, BoardModel.BudgetCategory.PROCUREMENT,
                spec.budget());
        if (!reserved.accepted()) {
            return Applied.rejected(run, board, null, accounts, reserved.reason());
        }
        ResearchModel.Outcome registered = ResearchModel.register(run.state(), commandId, owner,
                spec, now, accounts);
        if (!registered.accepted()) {
            return Applied.rejected(run, board, null, accounts, registered.reason());
        }
        BoardRun nextBoard = new BoardRun(reserved.state(), board.reports(), board.periodEvidence());
        return new Applied(run.withState(registered.state()), nextBoard, null,
                registered.accounts(), true, ResearchModel.REASON_REGISTERED);
    }

    /** 阶段开工：真实容量 + 从立项承诺 spend 当前阶段采购预算 + 纯核 begin 双边付款。 */
    static Applied begin(ResearchRun run, BoardRun board, String commandId,
            String projectId, ResearchModel.Stage stage, long now,
            Map<String, CashLedger.CashAccount> accounts) {
        if (run == null || board == null) {
            return Applied.rejected(run, board, null, accounts, "NOT_READY");
        }
        ResearchModel.ProjectSpec spec = run.project(projectId);
        if (spec == null) {
            return Applied.rejected(run, board, null, accounts,
                    ResearchModel.REASON_UNKNOWN_PROJECT);
        }
        if (run.activeStages() >= run.maxActiveStages()) {
            return Applied.rejected(run, board, null, accounts, REASON_CONTRACTOR_CAPACITY);
        }
        BigDecimal cost = spec.stages().get(stage.ordinal()).cost();
        BoardModel.Outcome spent = BoardModel.spendBudget(board.board(), now,
                commitmentId(projectId), cost);
        if (!spent.accepted()) {
            return Applied.rejected(run, board, null, accounts, spent.reason());
        }
        String owner = run.state().config().companyOwnerId();
        ResearchModel.Outcome begun = ResearchModel.begin(run.state(), commandId, owner,
                projectId, stage, now, accounts);
        if (!begun.accepted()) {
            return Applied.rejected(run, board, null, accounts, begun.reason());
        }
        BoardRun nextBoard = new BoardRun(spent.state(), board.reports(), board.periodEvidence());
        return new Applied(run.withState(begun.state()), nextBoard, null,
                begun.accounts(), true, ResearchModel.REASON_STARTED);
    }

    /**
     * 商业化批准：把已通过的真实董事授权落成可摊销事实（不移动现金、不产生收入）。
     *
     * @param run        研发状态
     * @param board      该公司董事会（原样返回）
     * @param commandId  命令标识
     * @param projectId  冻结项目
     * @param approvalRef 真实决议授权引用
     * @param now        实际执行时点（即可使用时点）
     * @param accounts   唯一账户表
     * @return 候选结果
     */
    static Applied approve(ResearchRun run, BoardRun board, String commandId, String projectId,
            String approvalRef, long now, Map<String, CashLedger.CashAccount> accounts) {
        if (run == null) {
            return Applied.rejected(run, board, null, accounts, "NOT_READY");
        }
        String owner = run.state().config().companyOwnerId();
        ResearchModel.Outcome approved = ResearchModel.approve(run.state(), commandId, owner,
                projectId, approvalRef, now, accounts);
        if (!approved.accepted()) {
            return Applied.rejected(run, board, null, accounts, approved.reason());
        }
        return new Applied(run.withState(approved.state()), board, null, approved.accounts(),
                true, approved.reason());
    }

    /**
     * 系统自动摊销到期末整点：只按累计应摊减已记推进，不移动现金，不按调用次数计提。
     *
     * @param run       研发状态
     * @param board     该公司董事会（原样返回）
     * @param commandId 系统命令标识（公司+项目+期固定）
     * @param projectId 已批准项目
     * @param asOf      摊销截止日（期末整点）
     * @param now       当前真实时点（与 asOf 相同）
     * @param accounts  唯一账户表
     * @return 候选结果
     */
    static Applied amortize(ResearchRun run, BoardRun board, String commandId, String projectId,
            long asOf, long now, Map<String, CashLedger.CashAccount> accounts) {
        if (run == null) {
            return Applied.rejected(run, board, null, accounts, "NOT_READY");
        }
        String owner = run.state().config().companyOwnerId();
        ResearchModel.Outcome amortized = ResearchModel.amortize(run.state(), commandId, owner,
                projectId, asOf, now, accounts);
        if (!amortized.accepted()) {
            return Applied.rejected(run, board, null, accounts, amortized.reason());
        }
        return new Applied(run.withState(amortized.state()), board, null, amortized.accounts(),
                true, amortized.reason());
    }

    /**
     * 阶段结果/受限证据系统动作：结果只由 runSeed+固定项目+阶段+规则版本确定，一次记录。
     * 成功/失败都由系统调用纯核 complete/fail，不接受角色自报。
     *
     * @param seed      运行种子（只在这里使用，不投影给角色）
     * @param run       研发状态
     * @param information 当前资料状态（唯一正文）
     * @param projectId 固定项目
     * @param stage     阶段
     * @param now       实际成熟时点（== completesAt 或之后）
     * @param accounts  唯一账户表
     * @return 候选结果（含受限证据文档）
     */
    static Applied result(long seed, ResearchRun run, BoardRun board, InformationRun information,
            String projectId, ResearchModel.Stage stage, long now,
            Map<String, CashLedger.CashAccount> accounts) {
        return result(seed, run, board, information, projectId, stage, now, accounts, false);
    }

    /** 显式新档的经营结果受众包含现任经理；旧档继续仅向董事会发布。 */
    static Applied result(long seed, ResearchRun run, BoardRun board, InformationRun information,
            String projectId, ResearchModel.Stage stage, long now,
            Map<String, CashLedger.CashAccount> accounts, boolean operationalEvidence) {
        if (run == null || information == null) {
            return Applied.rejected(run, board, information, accounts, "NOT_READY");
        }
        ResearchModel.ProjectSpec spec = run.project(projectId);
        if (spec == null) {
            return Applied.rejected(run, board, information, accounts,
                    ResearchModel.REASON_UNKNOWN_PROJECT);
        }
        boolean success = seededOutcome(seed, projectId, stage);
        String company = run.companyPartyId();
        BigDecimal cost = spec.stages().get(stage.ordinal()).cost();
        BoardRun nextBoard = null;
        if (!success) {
            BoardModel.Commitment commitment = commitmentOf(board, commitmentId(projectId));
            if (commitment != null && commitment.outstanding().signum() > 0) {
                BoardModel.Outcome released = BoardModel.releaseBudget(board.board(), now,
                        commitmentId(projectId), commitment.outstanding());
                if (!released.accepted()) {
                    return Applied.rejected(run, board, information, accounts, released.reason());
                }
                nextBoard = new BoardRun(released.state(), board.reports(),
                        board.periodEvidence());
            }
        }
        String docId = evidenceDocId(company, projectId, stage, success);
        String title = "研发阶段结果 " + projectId + " " + stage.name();
        String body = "项目=" + projectId + "\n阶段=" + stage.name() + "\n结果="
                + (success ? "SUCCESS" : "FAILURE") + "\n成本=" + cost.toPlainString()
                + "\n截至=" + now;
        InformationModel.State info = information.information();
        if (InformationModel.version(info, docId, 1L).isEmpty()) {
            info = InformationModel.publish(info, docId, SYSTEM_PUBLISHER,
                    operationalEvidence ? new InformationModel.Audience(
                            InformationModel.AudienceKind.COMPANY_OPERATING, company)
                            : InformationModel.Audience.board(company),
                    new InformationModel.VersionDraft(title, body, "", now,
                            EVIDENCE_READ_MILLIS, List.of("research:" + projectId)));
        }
        ResearchModel.Evidence evidence = new ResearchModel.Evidence(
                spec.stages().get(stage.ordinal()).evidenceRequirement(), docId, now);
        String owner = run.state().config().companyOwnerId();
        ResearchModel.Outcome outcome = success
                ? ResearchModel.complete(run.state(), "research-result:" + docId, owner,
                        projectId, stage, evidence, now, accounts)
                : ResearchModel.fail(run.state(), "research-result:" + docId, owner,
                        projectId, stage, evidence, now, accounts);
        if (!outcome.accepted()) {
            return Applied.rejected(run, board, information, accounts, outcome.reason());
        }
        InformationRun nextInformation = new InformationRun(info, information.reports());
        return new Applied(run.withState(outcome.state()), nextBoard, nextInformation,
                outcome.accounts(), true,
                success ? ResearchModel.REASON_COMPLETED : ResearchModel.REASON_FAILED);
    }

    /** 取该公司董事会里的研发项目承诺；不存在返回 null。 */
    private static BoardModel.Commitment commitmentOf(BoardRun board, String commitmentId) {
        if (board == null) {
            return null;
        }
        for (BoardModel.Commitment commitment : board.board().commitments()) {
            if (commitment.commitmentId().equals(commitmentId)) {
                return commitment;
            }
        }
        return null;
    }

    /** 确定性阶段结果：只含 runSeed、固定 projectId、阶段与规则版本；不含 begin 时间/命令/attempt。 */
    static boolean seededOutcome(long seed, String projectId, ResearchModel.Stage stage) {
        String key = "research|" + projectId + "|" + stage.name() + "|" + RULE_VERSION;
        String order = WorldScheduler.fairOrder(seed, key);
        return Math.floorMod(order.hashCode(), 4) != 0;
    }

    static String commitmentId(String projectId) {
        return "research:" + projectId;
    }

    static String evidenceDocId(String company, String projectId, ResearchModel.Stage stage,
            boolean success) {
        return "research:" + company + ":" + projectId + ":" + stage.name() + ":"
                + (success ? "SUCCESS" : "FAILURE");
    }
}
