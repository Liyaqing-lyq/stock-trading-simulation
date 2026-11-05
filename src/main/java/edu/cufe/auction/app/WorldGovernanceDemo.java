package edu.cufe.auction.app;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.BoardRun;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 治理接入经营世界的正式可复跑演示：不需要测试框架，也不写课程 {@code data} 目录。
 *
 * <p>用法：{@code WorldGovernanceDemo <dir> [prepare|full]}。{@code dir} 省略时使用
 * {@code logs/world-governance-demo}；模式省略时按 {@code full} 恢复。</p>
 *
 * <ul>
 *   <li>{@code prepare}：通过并实施 period0 采购 500 + 人工 300 预算（合计 800 属普通事项，
 *       三票赞成），再跑 16 日签约、17 日采购、18 日投产、20 日交付，推进到 20 日退出。</li>
 *   <li>{@code full}：恢复同一日志，推进到 35 日 + 1 毫秒，验证 30 日复盘（兑现 100 / 现金 0 /
 *       风险 100，披露与长期投入缺失、总分 null）、35 日回款不回写 30 日证据，以及公司现金
 *       1150 / 设备 900 / 利润 50 / 权益 2050 / 世界现金 3600；随后推进到次月 61 日，验证下一期
 *       关期与复盘连续。</li>
 * </ul>
 *
 * <p>这是 {@code model=none} 的确定性受信管理夹具：显式 seatId 与五席默认到会都是合成假设，
 * 不是 LLM/真实用户身份认证；自动登记材料不等于人物已读。预算只是支出权限，不改现金或资产；
 * 没有新世界 GUI，也不调用真实模型。</p>
 */
public final class WorldGovernanceDemo {

    private static final String RUN = "world-governance-demo";
    private static final long SEED = 20261106L;
    private static final String SYMBOL = "CUFE1";
    private static final long DAY = ManufacturingModel.DAY_MILLIS;
    private static final String OWNER = "company";
    private static final String COMPANY = "CO";
    private static final String SUPPLIER = "SUP";
    private static final String CUSTOMER = "CUS";
    private static final String OPS = "OPS";
    private static final String INVESTOR = "INV";

    private static final long CONVENE_AT = DAY;
    private static final long OPEN_AT = CONVENE_AT + 10_000L;
    private static final long CLOSE_AT = CONVENE_AT + 20_000L;
    private static final String MEETING = "demo-budget-p0";

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening(OWNER, COMPANY, SUPPLIER, CUSTOMER, OPS), SYMBOL);

    private static final List<OpeningAccount> OPENINGS = List.of(
            new OpeningAccount(COMPANY, OWNER, money("1000.00"), "演示开局历史禀赋"),
            new OpeningAccount(SUPPLIER, "supplier", money("100.00"), "演示开局历史禀赋"),
            new OpeningAccount(CUSTOMER, "customer", money("2000.00"), "演示开局历史禀赋"),
            new OpeningAccount(OPS, "ops", money("0.00"), "演示开局历史禀赋"),
            new OpeningAccount(INVESTOR, "investor", money("500.00"), "演示开局历史禀赋"));

    private static final BoardModel.Charter CHARTER = new BoardModel.Charter("cufe", List.of(
            new BoardModel.Seat("s1", "p1"), new BoardModel.Seat("s2", "p2"),
            new BoardModel.Seat("s3", "p3"), new BoardModel.Seat("s4", "p4"),
            new BoardModel.Seat("s5", "p5")), "s1", BoardModel.QUORUM);

    private WorldGovernanceDemo() {
    }

    /**
     * 运行演示。
     *
     * @param args 可选目录与模式（{@code prepare} / {@code full}）
     * @throws Exception 日志不可用、世界状态校验失败或演示数字与合同不符
     */
    public static void main(String[] args) throws Exception {
        Path dir = args.length > 0 ? Path.of(args[0])
                : Path.of("logs", "world-governance-demo");
        String mode = args.length > 1 ? args[1] : "full";
        if (!"prepare".equals(mode) && !"full".equals(mode)) {
            System.out.println("[WorldGovernanceDemo] 用法: WorldGovernanceDemo <dir> "
                    + "[prepare|full]");
            return;
        }
        Files.createDirectories(dir);
        Path file = dir.resolve("company.journal");
        System.out.println("[WorldGovernanceDemo] mode=" + mode + " journal=" + file);

        try (WorldRuntime runtime = new WorldRuntime(file, manifest(), OPENINGS, registry(), MFG,
                CHARTER)) {
            if (fresh(runtime)) {
                schedulePlan(runtime);
            } else {
                // 恢复进程重发同一批命令：全部命中内容幂等，返回原 SCHEDULED
                replayPlan(runtime);
            }
            if ("prepare".equals(mode)) {
                runtime.advanceTo(20 * DAY);
                check(runtime);
                System.out.println("[WorldGovernanceDemo] prepare 退出于 time="
                        + runtime.snapshot().timeMillis()
                        + "，应收/预算承诺/强制关期/复盘待办已持久");
                report(runtime, mode);
            } else {
                long start = runtime.snapshot().timeMillis();
                if (start < 30 * DAY + 1) {
                    runtime.advanceTo(30 * DAY + 1);
                }
                BoardRun.PeriodEvidence frozen = runtime.snapshot().governance().orElseThrow()
                        .evidence(0L).orElseThrow();
                if (start < 35 * DAY + 1) {
                    runtime.advanceTo(35 * DAY + 1);
                    check(runtime);
                    System.out.println("[WorldGovernanceDemo] period0 证据在 35 日回款后保持: "
                            + frozen.equals(runtime.snapshot().governance().orElseThrow()
                            .evidence(0L).orElseThrow()));
                    report(runtime, "full@35d");
                } else {
                    System.out.println("[WorldGovernanceDemo] 恢复时已过 35 日（time=" + start
                            + "），不重算历史报表");
                }
                checkStable(runtime);
                if (runtime.snapshot().timeMillis() < 61 * DAY + 1) {
                    runtime.advanceTo(61 * DAY + 1);
                    checkNextPeriod(runtime);
                    System.out.println("[WorldGovernanceDemo] full@61d 是第二期延续性检查"
                            + "（现金900/设备800/利润-300/权益1700），不是35日合同数字");
                    report(runtime, "full@61d");
                } else {
                    System.out.println("[WorldGovernanceDemo] 恢复时已过 61 日（time="
                            + runtime.snapshot().timeMillis() + "），下一期复盘已存在");
                }
            }
        }
    }

    /** 只在全新日志上排一次计划；已有运行只恢复并重发同命令。 */
    private static boolean fresh(WorldRuntime runtime) {
        ManufacturingRun run = runtime.snapshot().manufacturing().orElseThrow();
        return runtime.snapshot().timeMillis() == 0L && run.reports().isEmpty()
                && run.ledger().purchaseOrders().isEmpty();
    }

    private static void schedulePlan(WorldRuntime runtime) throws Exception {
        System.out.println("[WorldGovernanceDemo] " + runtime.scheduleBoardMeeting(
                "demo:convene-p0", "demo-convene-p0", CONVENE_AT, "s1", MEETING,
                CONVENE_AT + 5_000L, OPEN_AT, CLOSE_AT, seats(), List.of()));
        System.out.println("[WorldGovernanceDemo] " + runtime.scheduleBudgetProposal(
                "demo:proposal-p0", "demo-proposal-p0", CONVENE_AT + 1, "s3", MEETING,
                List.of(new BoardModel.BudgetLine(0L, BoardModel.BudgetCategory.PROCUREMENT,
                                money("500.00")),
                        new BoardModel.BudgetLine(0L, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                money("300.00")))));
        vote(runtime, "s1", BoardModel.Vote.YES);
        vote(runtime, "s2", BoardModel.Vote.YES);
        vote(runtime, "s3", BoardModel.Vote.YES);
        vote(runtime, "s4", BoardModel.Vote.NO);
        vote(runtime, "s5", BoardModel.Vote.ABSTAIN);
        System.out.println("[WorldGovernanceDemo] " + runtime.scheduleBudgetImplementation(
                "demo:implement-p0", "demo-implement-p0", CLOSE_AT + 1, "s1", MEETING));
        System.out.println("[WorldGovernanceDemo] " + runtime.scheduleSalesContract(
                "demo:contract-1", "demo-contract-1", 16 * DAY, OWNER, "demo-contract-1", 10,
                money("120.00"), 20 * DAY));
        System.out.println("[WorldGovernanceDemo] " + runtime.schedulePurchaseOrder(
                "demo:po-1", "demo-po-1", 17 * DAY, OWNER, "demo-po-1", 10));
        System.out.println("[WorldGovernanceDemo] " + runtime.scheduleProduction(
                "demo:batch-1", "demo-batch-1", 18 * DAY + 1, OWNER, "demo-batch-1", 10));
        System.out.println("[WorldGovernanceDemo] " + runtime.scheduleDelivery(
                "demo:delivery-1", "demo-delivery-1", 20 * DAY, OWNER, "demo-contract-1", 10,
                "demo-ar-1"));
        System.out.println("[WorldGovernanceDemo] " + runtime.schedulePayExpenseBill(
                "demo:pay-bill-0", "demo-pay-bill-0", 35 * DAY + 1, OWNER, 0L));
    }

    /** 恢复进程的同命令重发；时点已过也返回原回报，不重复建会议或加预算。 */
    private static void replayPlan(WorldRuntime runtime) throws Exception {
        System.out.println("[WorldGovernanceDemo] replay "
                + runtime.scheduleBoardMeeting("demo:convene-p0", "demo-convene-p0", CONVENE_AT,
                        "s1", MEETING, CONVENE_AT + 5_000L, OPEN_AT, CLOSE_AT, seats(),
                        List.of()));
        System.out.println("[WorldGovernanceDemo] replay "
                + runtime.scheduleBudgetImplementation("demo:implement-p0", "demo-implement-p0",
                        CLOSE_AT + 1, "s1", MEETING));
        System.out.println("[WorldGovernanceDemo] replay "
                + runtime.schedulePurchaseOrder("demo:po-1", "demo-po-1", 17 * DAY, OWNER,
                        "demo-po-1", 10));
        System.out.println("[WorldGovernanceDemo] replay "
                + runtime.scheduleProduction("demo:batch-1", "demo-batch-1", 18 * DAY + 1, OWNER,
                        "demo-batch-1", 10));
        System.out.println("[WorldGovernanceDemo] replay "
                + runtime.schedulePayExpenseBill("demo:pay-bill-0", "demo-pay-bill-0",
                        35 * DAY + 1, OWNER, 0L));
    }

    private static void vote(WorldRuntime runtime, String seat, BoardModel.Vote vote)
            throws Exception {
        System.out.println("[WorldGovernanceDemo] " + runtime.scheduleBoardVote(
                "demo:vote-" + seat, "demo-vote-" + seat, OPEN_AT, seat, MEETING, vote));
    }

    /** 合同数字的硬校验：任何偏差以失败退出，而不是打印一个好看的结果。 */
    private static void check(WorldRuntime runtime) {
        WorldRuntime.Snapshot snapshot = runtime.snapshot();
        BoardRun governance = snapshot.governance().orElseThrow();
        ManufacturingModel.Report book = ManufacturingModel.report(
                snapshot.manufacturing().orElseThrow().ledger(), snapshot.accounts());
        long closed = snapshot.manufacturing().orElseThrow().ledger().closedPeriods();
        if (closed == 0) {
            expect("现金", book.companyCash(), "200.00");
            expect("应收", book.receivableOutstanding(), "1200.00");
            expect("采购已花", BoardModel.spentBudget(governance.board(), 0L,
                    BoardModel.BudgetCategory.PROCUREMENT), "500.00");
            expect("人工已花", BoardModel.spentBudget(governance.board(), 0L,
                    BoardModel.BudgetCategory.PRODUCTION_LABOR), "300.00");
            return;
        }
        expect("现金", book.companyCash(), "1150.00");
        expect("设备净值", book.equipmentNet(), "900.00");
        expect("累计利润", book.cumulativeProfit(), "50.00");
        expect("权益", book.equity(), "2050.00");
        expect("世界现金", book.worldCash(), "3600.00");
        expect("应付", book.payableOutstanding(), "0.00");
        BoardRun.PeriodEvidence evidence = governance.evidence(0L).orElseThrow();
        expect("经营兑现", evidence.operatingCommitment(), "100.00");
        expect("现金偿债", evidence.cashSolvency(), "0.00");
        expect("风险", evidence.risk(), "100.00");
        BoardModel.ReviewResult review = review(governance, evidence.reviewMeetingId());
        if (review.status() != BoardModel.ReviewStatus.INCOMPLETE || review.total() != null) {
            throw new IllegalStateException("复盘必须是 INCOMPLETE 且 total=null，实际 "
                    + review.status() + " / " + review.total());
        }
        if (!review.missingDimensions().equals(List.of(BoardModel.Dimension.DISCLOSURE,
                BoardModel.Dimension.LONG_TERM_INVESTMENT))) {
            throw new IllegalStateException("缺项必须只有披露与长期投入："
                    + review.missingDimensions());
        }
    }

    /** 与当前时点无关的稳定不变量：复盘证据、预算与重复性。 */
    private static void checkStable(WorldRuntime runtime) {
        BoardRun governance = runtime.snapshot().governance().orElseThrow();
        BoardRun.PeriodEvidence first = governance.evidence(0L).orElseThrow();
        expect("经营兑现", first.operatingCommitment(), "100.00");
        expect("现金偿债", first.cashSolvency(), "0.00");
        expect("风险", first.risk(), "100.00");
        BoardModel.ReviewResult review = review(governance, first.reviewMeetingId());
        if (review.status() != BoardModel.ReviewStatus.INCOMPLETE || review.total() != null) {
            throw new IllegalStateException("复盘必须是 INCOMPLETE 且 total=null，实际 "
                    + review.status() + " / " + review.total());
        }
        expect("预算上限", governance.board().budget(0L,
                BoardModel.BudgetCategory.PROCUREMENT).cap(), "500.00");
        expect("采购已花", BoardModel.spentBudget(governance.board(), 0L,
                BoardModel.BudgetCategory.PROCUREMENT), "500.00");
        expect("人工已花", BoardModel.spentBudget(governance.board(), 0L,
                BoardModel.BudgetCategory.PRODUCTION_LABOR), "300.00");
    }

    /** 次月连续性：第二次关期与复盘必须出现，且会议/证据不覆盖第一期。 */
    private static void checkNextPeriod(WorldRuntime runtime) {
        BoardRun governance = runtime.snapshot().governance().orElseThrow();
        if (governance.periodEvidence().size() != 2) {
            throw new IllegalStateException("期望两期证据，实际 "
                    + governance.periodEvidence().keySet());
        }
        BoardRun.PeriodEvidence second = governance.evidence(1L).orElseThrow();
        if (second.operatingCommitment() != null) {
            throw new IllegalStateException("次月无到期合同，经营兑现必须缺失");
        }
        expect("次月现金偿债", second.cashSolvency(), "100.00");
        expect("次月风险", second.risk(), "100.00");
        if (governance.board().reviews().size() != 2) {
            throw new IllegalStateException("期望两次复盘，实际 "
                    + governance.board().reviews().size());
        }
    }

    private static void report(WorldRuntime runtime, String mode) {
        WorldRuntime.Snapshot snapshot = runtime.snapshot();
        ManufacturingRun run = snapshot.manufacturing().orElseThrow();
        BoardRun governance = snapshot.governance().orElseThrow();
        ManufacturingModel.Report book = ManufacturingModel.report(run.ledger(),
                snapshot.accounts());
        System.out.println("[WorldGovernanceDemo] mode=" + mode
                + " time=" + snapshot.timeMillis()
                + " position=" + snapshot.position()
                + " pending=" + snapshot.pending().size()
                + " mfgReports=" + run.reports().size()
                + " govReports=" + governance.reports().size()
                + " closedPeriods=" + run.ledger().closedPeriods()
                + " paused=" + runtime.isPaused());
        System.out.println("[WorldGovernanceDemo] 公司表 现金=" + book.companyCash()
                + " 原料=" + book.rawQuantity() + "/" + book.rawAmount()
                + " 产成品=" + book.finishedQuantity() + "/" + book.finishedAmount()
                + " 应收=" + book.receivableOutstanding()
                + " 设备净值=" + book.equipmentNet()
                + " 应付=" + book.payableOutstanding());
        System.out.println("[WorldGovernanceDemo] 利润表 收入=" + book.revenue()
                + " 成本=" + book.costOfGoodsSold()
                + " 期间费用=" + book.periodExpense()
                + " 折旧=" + book.depreciation()
                + " 累计利润=" + book.cumulativeProfit());
        System.out.println("[WorldGovernanceDemo] 平衡 资产=" + book.totalAssets()
                + " 负债=" + book.totalLiabilities()
                + " 权益=" + book.equity()
                + " 世界现金=" + book.worldCash()
                + " 平衡=" + book.balanced());
        for (BoardModel.Budget budget : governance.board().budgets()) {
            System.out.println("[WorldGovernanceDemo] 预算 period=" + budget.period()
                    + " category=" + budget.category() + " cap=" + budget.cap()
                    + " spent=" + BoardModel.spentBudget(governance.board(), budget.period(),
                    budget.category())
                    + " active=" + BoardModel.outstandingBudget(governance.board(),
                    budget.period(), budget.category()));
        }
        for (BoardModel.Commitment commitment : governance.board().commitments()) {
            System.out.println("[WorldGovernanceDemo] 承诺 " + commitment.commitmentId()
                    + " reserved=" + commitment.reserved() + " spent=" + commitment.spent()
                    + " released=" + commitment.released() + " status=" + commitment.status());
        }
        for (BoardRun.Report entry : governance.reports().values()) {
            System.out.println("[WorldGovernanceDemo] govReport " + entry.taskId()
                    + " kind=" + entry.kind()
                    + " accepted=" + entry.accepted()
                    + " reason=" + entry.reason()
                    + " meeting=" + entry.meetingId()
                    + " time=" + entry.timeMillis()
                    + " at=" + entry.position());
        }
        for (BoardRun.PeriodEvidence evidence : governance.periodEvidence().values()) {
            System.out.println("[WorldGovernanceDemo] 期间证据 period=" + evidence.period()
                    + " 兑现=" + evidence.operatingCommitment()
                    + " 现金=" + evidence.cashSolvency()
                    + " 风险=" + evidence.risk()
                    + " 复盘=" + evidence.reviewMeetingId()
                    + " 来源=" + evidence.sourceRef());
        }
        for (BoardModel.ReviewResult review : governance.board().reviews()) {
            System.out.println("[WorldGovernanceDemo] 复盘 " + review.reviewId()
                    + " status=" + review.status() + " total=" + review.total()
                    + " missing=" + review.missingDimensions());
        }
        for (PendingTask task : snapshot.pending()) {
            System.out.println("[WorldGovernanceDemo] pending " + task.taskId()
                    + " kind=" + task.kind() + " due=" + task.dueTimeMillis()
                    + " phase=" + task.phase());
        }
    }

    private static BoardModel.ReviewResult review(BoardRun governance, String meetingId) {
        for (BoardModel.ReviewResult review : governance.board().reviews()) {
            if (review.reviewId().equals(meetingId)) {
                return review;
            }
        }
        throw new IllegalStateException("缺少复盘结果：" + meetingId);
    }

    private static void expect(String field, BigDecimal actual, String expected) {
        if (actual == null || actual.compareTo(money(expected)) != 0) {
            throw new IllegalStateException(field + " 期望 " + expected + "，实际 " + actual);
        }
    }

    private static List<String> seats() {
        return List.of("s1", "s2", "s3", "s4", "s5");
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        modules.put("manufacturing", 1);
        modules.put("governance", 1);
        return new RunManifest(RUN, SEED,
                RunManifest.RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1, modules,
                Map.of("fixture", "world-governance-demo-v1"), RunManifest.MODEL_NONE);
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = new ArrayList<>(List.of(
                new OpeningRegistry.Party("cufe", OpeningRegistry.PartyKind.ORGANIZATION,
                        "中央财经制造公司"),
                new OpeningRegistry.Party("p1", OpeningRegistry.PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party("p2", OpeningRegistry.PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party("p3", OpeningRegistry.PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party("p4", OpeningRegistry.PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party("p5", OpeningRegistry.PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party("investor", OpeningRegistry.PartyKind.PERSON, "投资者")));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner(OWNER,
                        OpeningRegistry.OwnerKind.ORGANIZATION, "cufe"),
                new OpeningRegistry.PropertyOwner("supplier",
                        OpeningRegistry.OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("customer",
                        OpeningRegistry.OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("ops",
                        OpeningRegistry.OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("investor",
                        OpeningRegistry.OwnerKind.PERSON, "investor"));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYMBOL, "cufe", 200, money("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation(INVESTOR, SYMBOL, 200,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }
}
