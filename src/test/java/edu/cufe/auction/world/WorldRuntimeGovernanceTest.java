package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.BoardModel.BudgetCategory;
import edu.cufe.auction.world.BoardModel.BudgetLine;
import edu.cufe.auction.world.BoardModel.Charter;
import edu.cufe.auction.world.BoardModel.Dimension;
import edu.cufe.auction.world.BoardModel.Meeting;
import edu.cufe.auction.world.BoardModel.MeetingStatus;
import edu.cufe.auction.world.BoardModel.ReviewResult;
import edu.cufe.auction.world.BoardModel.Seat;
import edu.cufe.auction.world.BoardModel.Vote;
import edu.cufe.auction.world.BoardRun.PeriodEvidence;
import edu.cufe.auction.world.BoardRun.Report;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.ManufacturingModel.PurchaseOrderStatus;
import edu.cufe.auction.world.ManufacturingRun.ReportEntry;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3b.2：董事会治理接入经营世界（governed-stock-company-experiment-v1 / schema5）。
 *
 * <p>只验证受信管理夹具：显式 seatId 是身份声明，不是 LLM 身份认证；五席默认到会也是合成假设。
 * 覆盖合同十组：开局与档案边界；无预算/通过未实施/重复实施；普通与特别表决、回避；主线预算与
 * 现金流；预算不足与现金不足释放；跨期采购；月底真实证据与未来回款不回写；真实到货帧中断/半帧；
 * 治理报告篡改反证；受信席位边界。</p>
 */
@DisplayName("WorldRuntime：治理接入经营世界")
class WorldRuntimeGovernanceTest {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-gov";
    private static final String SYM = "CUFE1";
    private static final long DAY = ManufacturingModel.DAY_MILLIS;

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), SYM);

    private static final List<OpeningAccount> OPENINGS = List.of(
            new OpeningAccount("CO", "company", bd("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount("SUP", "supplier", bd("100.00"), "实验开局历史禀赋"),
            new OpeningAccount("CUS", "customer", bd("2000.00"), "实验开局历史禀赋"),
            new OpeningAccount("OPS", "ops", bd("0.00"), "实验开局历史禀赋"),
            new OpeningAccount("INV", "investor", bd("500.00"), "实验开局历史禀赋"));

    @TempDir
    Path tempDir;

    private static Charter charter() {
        return new Charter("cufe", List.of(
                new Seat("s1", "p1"), new Seat("s2", "p2"), new Seat("s3", "p3"),
                new Seat("s4", "p4"), new Seat("s5", "p5")), "s1", BoardModel.QUORUM);
    }

    private static List<String> allSeats() {
        return List.of("s1", "s2", "s3", "s4", "s5");
    }

    // ------------------------------------------------------------------
    // 组 1：档案、章程与写盘前拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("开局：治理档案缺章程/旧档案多章程/公司组织不符/未知人物席位均在写盘前拒绝")
    void openingRequiresExactlyOneGovernedCharterBeforeAnyFileExists() throws IOException {
        Path missing = tempDir.resolve("missing-charter.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(missing,
                governedManifest(), OPENINGS, registry(), MFG), "治理档案必须给章程");
        assertFalse(Files.exists(missing));

        Path extra = tempDir.resolve("extra-charter.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(extra,
                manufacturingManifest(), OPENINGS, registry(), MFG, charter()),
                "未启用治理的档案不得携带章程");
        assertFalse(Files.exists(extra));

        Charter wrongCompany = new Charter("investor-org", charter().seats(), "s1",
                BoardModel.QUORUM);
        Path company = tempDir.resolve("wrong-company.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(company,
                governedManifest(), OPENINGS, registry(), MFG,
                wrongCompany), "章程公司必须就是制造公司所属组织");
        assertFalse(Files.exists(company));

        Charter unknownPerson = new Charter("cufe", List.of(
                new Seat("s1", "p1"), new Seat("s2", "p2"), new Seat("s3", "p3"),
                new Seat("s4", "p4"), new Seat("s5", "ghost")), "s1", BoardModel.QUORUM);
        Path person = tempDir.resolve("unknown-person.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(person,
                governedManifest(), OPENINGS, registry(), MFG, unknownPerson),
                "席位必须绑定已登记人物");
        assertFalse(Files.exists(person));

        Path duplicate = tempDir.resolve("duplicate-person.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(duplicate,
                governedManifest(), OPENINGS, registry(), MFG,
                new Charter("cufe", List.of(
                        new Seat("s1", "p1"), new Seat("s2", "p2"), new Seat("s3", "p3"),
                        new Seat("s4", "p4"), new Seat("s5", "p4")), "s1",
                        BoardModel.QUORUM)));
        assertFalse(Files.exists(duplicate));
        Path file = tempDir.resolve("valid.journal");
        try (WorldRuntime runtime = open(file)) {
            BoardRun run = governance(runtime);
            assertEquals(charter(), run.board().charter());
            assertTrue(run.board().meetings().isEmpty());
            assertTrue(run.board().budgets().isEmpty(), "新档案开局没有预算");
            assertTrue(run.reports().isEmpty());
            assertTrue(run.periodEvidence().isEmpty());
            assertTrue(runtime.snapshot().manufacturing().isPresent());
        }

        Charter other = new Charter("cufe", List.of(
                new Seat("a1", "p1"), new Seat("a2", "p2"), new Seat("a3", "p3"),
                new Seat("a4", "p4"), new Seat("a5", "p5")), "a1", BoardModel.QUORUM);
        assertThrows(IOException.class, () -> new WorldRuntime(file, governedManifest(), OPENINGS,
                registry(), MFG, other), "换一套章程重开同一日志必须拒绝");
    }

    // ------------------------------------------------------------------
    // 组 2：无预算、通过≠实施、重复实施
    // ------------------------------------------------------------------

    @Test
    @DisplayName("无预算采购/投产明确拒绝；ADOPTED 不生效；实施一次；同命令/换命令都不重复加 cap")
    void budgetMustBeImplementedOnceBeforeOperations() throws IOException {
        Path file = tempDir.resolve("budget-gate.journal");
        try (WorldRuntime runtime = open(file)) {
            runtime.schedulePurchaseOrder("c-nb-po", "t-nb-po", 2 * DAY, "company", "nb-po", 10);
            runtime.scheduleProduction("c-nb-batch", "t-nb-batch", 2 * DAY + 1, "company",
                    "nb-batch", 10);
            runtime.advanceTo(2 * DAY + 1);
            assertEquals(WorldRuntime.REASON_BUDGET_NOT_SET, report(runtime, "t-nb-po").reason());
            assertEquals(WorldRuntime.REASON_BUDGET_NOT_SET,
                    report(runtime, "t-nb-batch").reason());
            assertTrue(manufacturing(runtime).ledger().purchaseOrders().isEmpty(), "不得留下 PO");
            assertTrue(manufacturing(runtime).ledger().productionBatches().isEmpty());
            assertEquals(0, cash(runtime, "CO").compareTo(bd("1000.00")));
            assertTrue(pending(runtime, PendingTask.MANUFACTURING_GOODS_RECEIPT_V1).isEmpty());
            assertTrue(pending(runtime, PendingTask.MANUFACTURING_BATCH_COMPLETION_V1).isEmpty());

            MeetingScript budget = scheduleBudget(runtime, "m-gate", 3 * DAY,
                    budgetLines("500.00", "300.00"), allSeats(), List.of());
            vote(runtime, budget, List.of("s1", "s2", "s3"), List.of("s4"), List.of("s5"),
                    budget.opensAt());
            // 实施排在关会之后 1ms；先只推进到关会时点
            runtime.advanceTo(budget.closesAt());
            assertEquals(MeetingStatus.ADOPTED, meeting(runtime, budget.meetingId()).status());
            assertTrue(governance(runtime).board().budgets().isEmpty(), "通过不等于实施");

            runtime.schedulePurchaseOrder("c-adopted-po", "t-adopted-po", budget.closesAt() + 500,
                    "company", "adopted-po", 10);
            runtime.advanceTo(budget.closesAt() + 500);
            assertEquals(WorldRuntime.REASON_BUDGET_NOT_SET,
                    report(runtime, "t-adopted-po").reason(), "ADOPTED 不得授权采购");

            String implCommand = "c-impl-" + budget.meetingId();
            String impl = runtime.scheduleBudgetImplementation(implCommand,
                    "t-impl-" + budget.meetingId(), budget.closesAt() + 1000, "s1",
                    budget.meetingId());
            runtime.advanceTo(budget.closesAt() + 1000);
            assertTrue(governance(runtime).reports().get("t-impl-" + budget.meetingId()).accepted());
            assertEquals(MeetingStatus.IMPLEMENTED, meeting(runtime, budget.meetingId()).status());
            assertEquals(0, cap(runtime, 0, BudgetCategory.PROCUREMENT).compareTo(bd("500.00")));
            assertEquals(0, cap(runtime, 0, BudgetCategory.PRODUCTION_LABOR).compareTo(bd("300.00")));

            runtime.schedulePurchaseOrder("c-after-impl", "t-after-impl", budget.closesAt() + 2000,
                    "company", "after-impl", 10);
            runtime.advanceTo(budget.closesAt() + 2000);
            assertTrue(report(runtime, "t-after-impl").accepted());
            assertEquals(0, commitment(runtime, "po:after-impl").reserved()
                    .compareTo(bd("500.00")));

            long reportsBefore = governance(runtime).reports().size();
            assertEquals(impl, runtime.scheduleBudgetImplementation(implCommand,
                    "t-impl-" + budget.meetingId(), budget.closesAt() + 1000, "s1",
                    budget.meetingId()), "同一命令重发返回原 SCHEDULED");
            runtime.advanceTo(budget.closesAt() + 3000);
            assertEquals(reportsBefore, governance(runtime).reports().size(),
                    "重发不得产生第二条治理回报");
            assertEquals(0, cap(runtime, 0, BudgetCategory.PROCUREMENT).compareTo(bd("500.00")));

            runtime.scheduleBudgetImplementation("c-impl-again", "t-impl-again",
                    budget.closesAt() + 4000, "s1", budget.meetingId());
            runtime.advanceTo(budget.closesAt() + 4000);
            Report again = governance(runtime).reports().get("t-impl-again");
            assertFalse(again.accepted());
            assertEquals(BoardModel.REASON_NOT_ADOPTED, again.reason());
            assertEquals(0, cap(runtime, 0, BudgetCategory.PROCUREMENT).compareTo(bd("500.00")),
                    "换命令不得重复加 cap");
            assertEquals(0, commitment(runtime, "po:after-impl").reserved()
                    .compareTo(bd("500.00")));
        }
    }

    // ------------------------------------------------------------------
    // 组 3：普通/特别表决、反对弃权与回避
    // ------------------------------------------------------------------

    @Test
    @DisplayName("普通800三赞成；特别1100三赞成拒绝；特别1200四赞成通过；回避剩2席延期；反对弃权保留")
    void ordinarySpecialVotesAndRecusalAreKeptAsEvidence() throws IOException {
        Path file = tempDir.resolve("votes.journal");
        try (WorldRuntime runtime = open(file)) {
            MeetingScript rejected = scheduleBudget(runtime, "m-special-reject", DAY,
                    budgetLines("1100.00", "0.00"), allSeats(), List.of());
            vote(runtime, rejected, List.of("s1", "s2", "s3"), List.of("s4"), List.of("s5"),
                    rejected.opensAt());
            runtime.scheduleBudgetImplementation("c-impl-reject", "t-impl-reject",
                    rejected.closesAt() + 1, "s1", rejected.meetingId());
            runtime.advanceTo(rejected.closesAt() + 1);
            Meeting rejectedMeeting = meeting(runtime, rejected.meetingId());
            assertEquals(MeetingStatus.REJECTED, rejectedMeeting.status());
            assertEquals(5, rejectedMeeting.ballots().size(), "反对与弃权必须保留");
            assertEquals(Vote.NO, rejectedMeeting.ballots().get("s4").vote());
            assertEquals(Vote.ABSTAIN, rejectedMeeting.ballots().get("s5").vote());
            assertTrue(governance(runtime).board().budgets().isEmpty());
            Report impl = governance(runtime).reports().get("t-impl-reject");
            assertFalse(impl.accepted());
            assertEquals(BoardModel.REASON_NOT_ADOPTED, impl.reason());

            MeetingScript adopted = scheduleBudget(runtime, "m-special-pass", 2 * DAY,
                    budgetLines("1200.00", "0.00"), allSeats(), List.of());
            vote(runtime, adopted, List.of("s1", "s2", "s3", "s4"), List.of("s5"), List.of(),
                    adopted.opensAt());
            runtime.scheduleBudgetImplementation("c-impl-pass", "t-impl-pass",
                    adopted.closesAt() + 1, "s1", adopted.meetingId());
            runtime.advanceTo(adopted.closesAt() + 1);
            assertEquals(MeetingStatus.IMPLEMENTED, meeting(runtime, adopted.meetingId()).status());
            assertEquals(0, cap(runtime, 0, BudgetCategory.PROCUREMENT).compareTo(bd("1200.00")),
                    "特别事项四赞成才通过");

            MeetingScript recused = scheduleBudget(runtime, "m-recused", 3 * DAY,
                    budgetLines("400.00", "0.00"), allSeats(), List.of("s2", "s3", "s4"), "s1");
            vote(runtime, recused, List.of("s1", "s5"), List.of(), List.of(), recused.opensAt());
            runtime.advanceTo(recused.closesAt());
            Meeting recusedMeeting = meeting(runtime, recused.meetingId());
            assertEquals(MeetingStatus.ADJOURNED_NO_QUORUM, recusedMeeting.status());
            assertEquals(List.of("s2", "s3", "s4"), recusedMeeting.recusedSeatIds(),
                    "回避名单必须保留");
            assertEquals(List.of("s1", "s5"), recusedMeeting.eligibleVoters());

            // closesAt 当刻的迟到票必须拒绝：无论关会先跑（INVALID_STATE）还是票先跑（VOTING_CLOSED）
            MeetingScript late = scheduleBudget(runtime, "m-late", 5 * DAY,
                    budgetLines("400.00", "0.00"), allSeats(), List.of());
            vote(runtime, late, List.of("s1", "s2", "s3"), List.of(), List.of(), late.opensAt());
            runtime.scheduleBoardVote("c-late-vote", "t-late-vote", late.closesAt(), "s4",
                    late.meetingId(), Vote.YES);
            runtime.advanceTo(late.closesAt() + 1);
            BoardRun.Report lateVote = governance(runtime).reports().get("t-late-vote");
            assertFalse(lateVote.accepted(), "closesAt 当刻不得再投票");
            assertTrue(BoardModel.REASON_VOTING_CLOSED.equals(lateVote.reason())
                            || BoardModel.REASON_INVALID_STATE.equals(lateVote.reason()),
                    lateVote.reason());
            assertEquals(3, meeting(runtime, late.meetingId()).ballots().size(),
                    "迟到票不得写入选票");
            assertEquals(MeetingStatus.ADOPTED, meeting(runtime, late.meetingId()).status());
        }
    }

    // ------------------------------------------------------------------
    // 组 4：主线预算、现金流与复盘
    // ------------------------------------------------------------------

    @Test
    @DisplayName("period0 采购500+人工300：占用→到货已花→人工已花；35日现金1150/权益2050；30日复盘100/0/100")
    void mainLineWithBudgetKeepsCashFlowAndPeriodReview() throws IOException {
        Path file = tempDir.resolve("main.journal");
        try (WorldRuntime runtime = open(file)) {
            MeetingScript budget = scheduleBudget(runtime, "m-main", DAY,
                    budgetLines("500.00", "300.00"), allSeats(), List.of());
            vote(runtime, budget, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    budget.opensAt());
            runtime.scheduleBudgetImplementation("c-main-impl", "t-main-impl",
                    budget.closesAt() + 1, "s1", budget.meetingId());
            runtime.advanceTo(budget.closesAt() + 1);

            runtime.scheduleSalesContract("c-contract", "contract-1", 16 * DAY, "company",
                    "contract-1", 10, bd("120.00"), 20 * DAY);
            runtime.schedulePurchaseOrder("c-po", "po-1", 17 * DAY, "company", "po-1", 10);
            runtime.scheduleProduction("c-batch", "batch-1", 18 * DAY + 1, "company",
                    "batch-1", 10);
            runtime.scheduleDelivery("c-deliver", "delivery-1", 20 * DAY, "company",
                    "contract-1", 10, "ar-1");
            runtime.schedulePayExpenseBill("c-bill", "bill-0", 35 * DAY + 1, "company", 0L);

            runtime.advanceTo(17 * DAY);
            assertTrue(report(runtime, "po-1").accepted());
            assertEquals(0, commitment(runtime, "po:po-1").reserved().compareTo(bd("500.00")));
            assertEquals(0, commitment(runtime, "po:po-1").spent().compareTo(bd("0.00")));
            assertEquals(0, cash(runtime, "CO").compareTo(bd("1000.00")), "下单不动现金");

            runtime.advanceTo(18 * DAY);
            assertEquals(0, commitment(runtime, "po:po-1").spent().compareTo(bd("500.00")),
                    "到货付款同批把原承诺转已花");
            assertEquals(0, commitment(runtime, "po:po-1").outstanding().compareTo(bd("0.00")));
            assertEquals(0, cash(runtime, "CO").compareTo(bd("500.00")));

            runtime.advanceTo(18 * DAY + 1);
            assertEquals(0, commitment(runtime, "labor:batch-1").reserved()
                    .compareTo(bd("300.00")));
            assertEquals(0, commitment(runtime, "labor:batch-1").spent()
                    .compareTo(bd("300.00")), "投产人工付款同批已花");
            assertEquals(0, cash(runtime, "CO").compareTo(bd("200.00")));

            runtime.advanceTo(20 * DAY);
            ManufacturingModel.Report day20 = book(runtime);
            assertEquals(0, day20.receivableOutstanding().compareTo(bd("1200.00")));
            assertEquals(0, day20.costOfGoodsSold().compareTo(bd("800.00")),
                    "COGS 不得重复占预算");

            runtime.advanceTo(30 * DAY + 1);
            PeriodEvidence period0 = governance(runtime).evidence(0L).orElseThrow();
            assertEquals(0, period0.cashSolvency().compareTo(bd("0.00")),
                    "关期后公司现金 0 / AP 50");
            assertEquals(0, period0.operatingCommitment().compareTo(bd("100.00")),
                    "到期合同 10/10 已交付");
            assertEquals(0, period0.risk().compareTo(bd("100.00")), "应收未到期");
            ReviewResult review = review(runtime, period0.reviewMeetingId());
            assertEquals(BoardModel.ReviewStatus.INCOMPLETE, review.status());
            assertNull(review.total(), "披露/长期投入缺失时总分必须为 null");
            assertEquals(List.of(Dimension.DISCLOSURE, Dimension.LONG_TERM_INVESTMENT),
                    review.missingDimensions());
            assertEquals(MeetingStatus.REVIEWED, meeting(runtime,
                    period0.reviewMeetingId()).status());
            assertTrue(pending(runtime, PendingTask.BOARD_CLOSE_V1).isEmpty(),
                    "复盘会议不应排自动关会；评分本身就是终态");

            runtime.advanceTo(35 * DAY + 1);
            ManufacturingModel.Report report = book(runtime);
            assertEquals(0, report.companyCash().compareTo(bd("1150.00")));
            assertEquals(0, report.equipmentNet().compareTo(bd("900.00")));
            assertEquals(0, report.cumulativeProfit().compareTo(bd("50.00")));
            assertEquals(0, report.equity().compareTo(bd("2050.00")));
            assertEquals(0, report.worldCash().compareTo(bd("3600.00")));
            assertEquals(0, report.payableOutstanding().compareTo(bd("0.00")));
            assertEquals(0, paidBudget(runtime, 0, BudgetCategory.PROCUREMENT)
                    .compareTo(bd("500.00")));
            assertEquals(0, paidBudget(runtime, 0, BudgetCategory.PRODUCTION_LABOR)
                    .compareTo(bd("300.00")));
            assertEquals(period0, governance(runtime).evidence(0L).orElseThrow(),
                    "35日回款不得回写30日证据");
        }
    }

    // ------------------------------------------------------------------
    // 组 5：预算不足、现金不足释放、cap 下穿
    // ------------------------------------------------------------------

    @Test
    @DisplayName("预算不足不下单；现金不足到货释放原承诺；跨期到货仍计原期；cap 低于已花+活动失败")
    void budgetAndCashFailuresReleaseWithoutLeftovers() throws IOException {
        Path file = tempDir.resolve("failures.journal");
        try (WorldRuntime runtime = open(file)) {
            MeetingScript budget = scheduleBudget(runtime, "m-cap", DAY,
                    budgetLines("1500.00", "0.00"), allSeats(), List.of());
            vote(runtime, budget, List.of("s1", "s2", "s3", "s4"), List.of(), List.of(),
                    budget.opensAt());
            runtime.scheduleBudgetImplementation("c-cap-impl", "t-cap-impl",
                    budget.closesAt() + 1, "s1", budget.meetingId());
            runtime.advanceTo(budget.closesAt() + 1);

            runtime.schedulePurchaseOrder("c-po-a", "po-a", 17 * DAY, "company", "po-a", 20);
            runtime.schedulePurchaseOrder("c-po-b", "po-b", 18 * DAY + 1, "company", "po-b", 10);
            runtime.advanceTo(17 * DAY);
            assertEquals(0, commitment(runtime, "po:po-a").reserved().compareTo(bd("1000.00")));
            runtime.advanceTo(18 * DAY);
            assertEquals(0, cash(runtime, "CO").compareTo(bd("0.00")));
            assertEquals(20L, manufacturing(runtime).ledger().rawMaterials().quantity());
            runtime.advanceTo(18 * DAY + 1);
            assertEquals(0, commitment(runtime, "po:po-b").reserved().compareTo(bd("500.00")));
            runtime.advanceTo(19 * DAY + 1);
            ManufacturingModel.PurchaseOrder failed = manufacturing(runtime).ledger()
                    .purchaseOrders().get("po-b");
            assertEquals(PurchaseOrderStatus.FAILED_NO_CASH, failed.status());
            assertEquals(0, commitment(runtime, "po:po-b").released().compareTo(bd("500.00")));
            assertEquals(0, commitment(runtime, "po:po-b").outstanding().compareTo(bd("0.00")));
            assertEquals(0, cash(runtime, "CO").compareTo(bd("0.00")), "失败到货不得扣款");
            assertEquals(20L, manufacturing(runtime).ledger().rawMaterials().quantity(),
                    "失败到货不得入库");

            runtime.schedulePurchaseOrder("c-po-c", "po-c", 20 * DAY, "company", "po-c", 20);
            runtime.advanceTo(20 * DAY);
            assertEquals(WorldRuntime.REASON_BUDGET_EXCEEDED, report(runtime, "po-c").reason());
            assertNull(manufacturing(runtime).ledger().purchaseOrders().get("po-c"), "预算不足不下单");
            assertTrue(pending(runtime, PendingTask.MANUFACTURING_GOODS_RECEIPT_V1).isEmpty());

            MeetingScript lower = scheduleBudget(runtime, "m-lower", 21 * DAY,
                    budgetLines("900.00", "0.00"), allSeats(), List.of());
            vote(runtime, lower, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    lower.opensAt());
            runtime.scheduleBudgetImplementation("c-lower-impl", "t-lower-impl",
                    lower.closesAt() + 1, "s1", lower.meetingId());
            runtime.advanceTo(lower.closesAt() + 1);
            Report lowerReport = governance(runtime).reports().get("t-lower-impl");
            assertFalse(lowerReport.accepted());
            assertEquals(BoardModel.REASON_BUDGET_BELOW_COMMITTED, lowerReport.reason());
            assertEquals(MeetingStatus.IMPLEMENTATION_FAILED,
                    meeting(runtime, lower.meetingId()).status());
            assertEquals(0, cap(runtime, 0, BudgetCategory.PROCUREMENT).compareTo(bd("1500.00")),
                    "失败实施不得改 cap");
        }
    }

    // ------------------------------------------------------------------
    // 组 6：跨期采购
    // ------------------------------------------------------------------

    @Test
    @DisplayName("29日下单、30日到货仍计入下单期0预算，复盘现金口径取关期截止")
    void crossPeriodReceiptKeepsOriginalCommitmentPeriod() throws IOException {
        Path file = tempDir.resolve("cross.journal");
        try (WorldRuntime runtime = open(file)) {
            MeetingScript budget = scheduleBudget(runtime, "m-cross", DAY,
                    budgetLines("500.00", "0.00"), allSeats(), List.of());
            vote(runtime, budget, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    budget.opensAt());
            runtime.scheduleBudgetImplementation("c-cross-impl", "t-cross-impl",
                    budget.closesAt() + 1, "s1", budget.meetingId());
            runtime.advanceTo(budget.closesAt() + 1);

            runtime.schedulePurchaseOrder("c-cross-po", "po-cross", 29 * DAY, "company",
                    "po-cross", 10);
            runtime.advanceTo(30 * DAY);
            assertEquals(0, commitment(runtime, "po:po-cross").period());
            assertEquals(0, commitment(runtime, "po:po-cross").spent().compareTo(bd("500.00")),
                    "跨期到货仍结算原承诺");
            assertEquals(0, paidBudget(runtime, 0, BudgetCategory.PROCUREMENT)
                    .compareTo(bd("500.00")));
            PeriodEvidence evidence = governance(runtime).evidence(0L).orElseThrow();
            assertNull(evidence.operatingCommitment(), "无到期合同必须缺失，不能补100");
            assertEquals(0, evidence.cashSolvency().compareTo(bd("100.00")),
                    "关期截止时 AP=0，现金750");
        }
    }

    // ------------------------------------------------------------------
    // 组 7：月底真实证据、下一期连续、未来不回写
    // ------------------------------------------------------------------

    @Test
    @DisplayName("30日复盘引用真实合同/应收/报告；35日回款不改旧证据；60日第二期复盘连续且无重复")
    void periodEvidenceIsFrozenAtItsOwnCutoff() throws IOException {
        Path file = tempDir.resolve("evidence.journal");
        try (WorldRuntime runtime = open(file)) {
            // 外部先占名 "review:0"，强制关期的复盘会议必须换空闲标识而不是卡死
            runtime.scheduleBoardMeeting("c-occupy", "t-occupy", DAY, "s1", "review:0",
                    DAY + 5_000L, DAY + 10_000L, DAY + 20_000L, allSeats(), List.of());
            runtime.scheduleCloseMarket("c-market-close", "t-market-close", 29 * DAY);
            MeetingScript budget = scheduleBudget(runtime, "m-evidence", DAY,
                    budgetLines("500.00", "300.00"), allSeats(), List.of());
            vote(runtime, budget, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    budget.opensAt());
            runtime.scheduleBudgetImplementation("c-evidence-impl", "t-evidence-impl",
                    budget.closesAt() + 1, "s1", budget.meetingId());
            runtime.advanceTo(budget.closesAt() + 1);

            runtime.scheduleSalesContract("c-e-contract", "contract-e", 16 * DAY, "company",
                    "contract-e", 10, bd("120.00"), 20 * DAY);
            runtime.schedulePurchaseOrder("c-e-po", "po-e", 17 * DAY, "company", "po-e", 10);
            runtime.scheduleProduction("c-e-batch", "batch-e", 18 * DAY + 1, "company",
                    "batch-e", 10);
            runtime.scheduleDelivery("c-e-delivery", "delivery-e", 20 * DAY, "company",
                    "contract-e", 10, "ar-e");
            runtime.schedulePayExpenseBill("c-e-bill", "bill-e", 35 * DAY + 1, "company", 0L);

            runtime.advanceTo(30 * DAY + 1);
            PeriodEvidence frozen = governance(runtime).evidence(0L).orElseThrow();
            assertTrue(runtime.snapshot().exchange().orElseThrow().closed(), "证券已收盘");
            assertFalse("review:0".equals(frozen.reviewMeetingId()), "同名外部会议不得占用复盘标识");
            assertTrue(frozen.reviewMeetingId().startsWith("review:0-"),
                    "复盘会议应从空闲后缀选取：" + frozen.reviewMeetingId());
            List<PendingTask> nextCloses =
                    pending(runtime, PendingTask.MANUFACTURING_PERIOD_CLOSE_V1);
            assertEquals(1, nextCloses.size(), "关期同批必须排下一期关期");
            assertEquals(60 * DAY, nextCloses.get(0).dueTimeMillis());
            assertTrue(frozen.contractIds().contains("contract-e"));
            assertTrue(frozen.receivableIds().contains("ar-e"));
            assertTrue(frozen.sourceRef().contains("period:0"));
            assertTrue(frozen.sourceRef().contains(":" + frozen.position()));

            runtime.advanceTo(35 * DAY + 1);
            assertEquals(frozen, governance(runtime).evidence(0L).orElseThrow(),
                    "回款后不得重算或回写旧证据");

            runtime.advanceTo(60 * DAY + 1);
            assertEquals(2, governance(runtime).periodEvidence().size());
            PeriodEvidence second = governance(runtime).evidence(1L).orElseThrow();
            assertNull(second.operatingCommitment(), "期间1无到期合同，必须缺失");
            assertEquals(0, second.cashSolvency().compareTo(bd("100.00")), "AP=0 时为100");
            assertEquals(0, second.risk().compareTo(bd("100.00")), "无未收AR时为100");
            assertFalse(second.reviewMeetingId().equals(frozen.reviewMeetingId()), "复盘会议唯一");
            assertEquals(2, governance(runtime).board().reviews().size());
            assertEquals(MeetingStatus.REVIEWED,
                    meeting(runtime, second.reviewMeetingId()).status());
        }
    }

    // ------------------------------------------------------------------
    // 组 8：真实到货经济帧中断 / 半帧
    // ------------------------------------------------------------------

    @Test
    @DisplayName("到货完成帧中断与半帧都回到旧版：现金/库存/预算/待办/报告一起恢复且只结算一次")
    void interruptedReceiptFrameRollsBackCashInventoryBudgetAndChildTogether() throws IOException {
        Path source = tempDir.resolve("receipt-source.journal");
        String receiptTask;
        try (WorldRuntime runtime = open(source)) {
            MeetingScript budget = scheduleBudget(runtime, "m-frame", DAY,
                    budgetLines("500.00", "300.00"), allSeats(), List.of());
            vote(runtime, budget, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    budget.opensAt());
            runtime.scheduleBudgetImplementation("c-frame-impl", "t-frame-impl",
                    budget.closesAt() + 1, "s1", budget.meetingId());
            runtime.advanceTo(budget.closesAt() + 1);

            runtime.schedulePurchaseOrder("c-frame-po", "po-frame", 17 * DAY, "company",
                    "po-frame", 10);
            runtime.advanceTo(17 * DAY);
            receiptTask = pending(runtime, PendingTask.MANUFACTURING_GOODS_RECEIPT_V1).get(0)
                    .taskId();
            runtime.advanceTo(18 * DAY);
            assertTrue(report(runtime, receiptTask).accepted());
        }

        byte[] bytes = Files.readAllBytes(source);
        long[] frame = frameOf(bytes, "task:" + receiptTask);
        assertTrue(frame[1] > 0, "必须找到真实到货提交帧");

        Path interrupted = tempDir.resolve("receipt-interrupted.journal");
        Files.write(interrupted, java.util.Arrays.copyOf(bytes, (int) frame[0]));
        Path half = tempDir.resolve("receipt-half.journal");
        int halfEnd = (int) (frame[0] + 8 + frame[1] / 2);
        Files.write(half, java.util.Arrays.copyOf(bytes, halfEnd));

        for (Path copy : List.of(interrupted, half)) {
            try (WorldRuntime runtime = open(copy)) {
                assertEquals(0, cash(runtime, "CO").compareTo(bd("1000.00")),
                        "到货付款必须整体未发生");
                assertEquals(0L, manufacturing(runtime).ledger().rawMaterials().quantity());
                assertEquals(0, commitment(runtime, "po:po-frame").spent()
                        .compareTo(bd("0.00")));
                assertEquals(0, commitment(runtime, "po:po-frame").reserved()
                        .compareTo(bd("500.00")));
                assertEquals(PurchaseOrderStatus.ORDERED,
                        manufacturing(runtime).ledger().purchaseOrders().get("po-frame").status());
                assertEquals(1, pending(runtime,
                        PendingTask.MANUFACTURING_GOODS_RECEIPT_V1).size(),
                        "到货待办不得丢失或重复");
                assertNull(manufacturing(runtime).reports().get(receiptTask));

                runtime.advanceTo(18 * DAY);
                assertEquals(0, cash(runtime, "CO").compareTo(bd("500.00")));
                assertEquals(10L, manufacturing(runtime).ledger().rawMaterials().quantity());
                assertEquals(0, commitment(runtime, "po:po-frame").spent()
                        .compareTo(bd("500.00")));
                assertEquals(0, cash(runtime, "SUP").compareTo(bd("600.00")));
                assertTrue(report(runtime, receiptTask).accepted());
                assertTrue(pending(runtime,
                        PendingTask.MANUFACTURING_GOODS_RECEIPT_V1).isEmpty());
            }
        }
    }

    // ------------------------------------------------------------------
    // 组 9：治理报告篡改反证
    // ------------------------------------------------------------------

    @Test
    @DisplayName("篡改治理报告事件但保留状态快照，恢复必须拒绝")
    void governanceReportMustMatchItsCommitSnapshot() throws IOException {
        Path source = tempDir.resolve("gov-report-source.journal");
        Path broken = tempDir.resolve("gov-report-broken.journal");
        try (WorldRuntime runtime = open(source)) {
            MeetingScript budget = scheduleBudget(runtime, "m-tamper", DAY,
                    budgetLines("500.00", "0.00"), allSeats(), List.of());
            vote(runtime, budget, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    budget.opensAt());
            runtime.advanceTo(budget.closesAt());
        }
        ObjectMapper json = new ObjectMapper();
        boolean changed = false;
        try (WorldJournal original = new WorldJournal(source, RUN);
             WorldJournal rewritten = new WorldJournal(broken, RUN)) {
            for (WorldCommit commit : original.commits()) {
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    if ("GOVERNANCE_REPORT_V1".equals(event.type())) {
                        Report report = json.readValue(event.payload(), Report.class);
                        Report mismatch = new Report(report.taskId(), report.commandId(),
                                report.kind(), report.meetingId(), report.accepted(),
                                "DIFFERENT_REASON", report.timeMillis(), report.position());
                        events.add(new WorldEvent(event.eventId(), event.type(),
                                json.writeValueAsString(mismatch)));
                        changed = true;
                    } else {
                        events.add(event);
                    }
                }
                rewritten.commit(commit.commandId(), commit.commandContent(), commit.result(),
                        events);
            }
        }
        assertTrue(changed, "必须实际改到治理报告，而非生成同一份存档");
        IOException failure = assertThrows(IOException.class, () -> open(broken).close());
        assertTrue(failure.getMessage().contains("治理结果与状态快照不一致"), failure.getMessage());
    }

    // ------------------------------------------------------------------
    // 组 10：显式 seatId 受信夹具边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("材料/提案只接受到会未回避席位；非成员投票拒绝；这是声明式夹具不是身份认证")
    void seatIdsAreTrustedFixtureDeclarations() throws IOException {
        Path file = tempDir.resolve("seats.journal");
        try (WorldRuntime runtime = open(file)) {
            MeetingScript script = scheduleBudget(runtime, "m-seat", DAY,
                    budgetLines("500.00", "0.00"), List.of("s1", "s2", "s3"), List.of("s2"));
            // 非到会席位 s4 提交材料
            runtime.scheduleBoardMaterial("c-mat-outsider", "t-mat-outsider",
                    script.conveneAt() + 1, "s4", script.meetingId(), "mat-outsider",
                    "src-outsider", 1L);
            // 回避席位 s2 提交材料
            runtime.scheduleBoardMaterial("c-mat-recused", "t-mat-recused",
                    script.conveneAt() + 1, "s2", script.meetingId(), "mat-recused",
                    "src-recused", 1L);
            // 到会未回避席位 s3 提交材料（预算请求由 scheduleBudget 夹具提交）
            runtime.scheduleBoardMaterial("c-mat-ok", "t-mat-ok", script.conveneAt() + 2, "s3",
                    script.meetingId(), "mat-ok", "src-ok", 1L);
            runtime.scheduleBoardVote("c-vote-s1", "t-vote-s1", script.opensAt(), "s1",
                    script.meetingId(), Vote.YES);
            runtime.scheduleBoardVote("c-vote-s4", "t-vote-s4", script.opensAt(), "s4",
                    script.meetingId(), Vote.NO);
            runtime.advanceTo(script.opensAt() + 1);

            assertEquals(WorldRuntime.REASON_NOT_ELIGIBLE_SEAT,
                    governance(runtime).reports().get("t-mat-outsider").reason());
            assertEquals(WorldRuntime.REASON_NOT_ELIGIBLE_SEAT,
                    governance(runtime).reports().get("t-mat-recused").reason());
            assertTrue(governance(runtime).reports().get("t-mat-ok").accepted());
            assertTrue(governance(runtime).reports().get("t-prop-m-seat").accepted());
            assertTrue(governance(runtime).reports().get("t-vote-s1").accepted(),
                    governance(runtime).reports().get("t-vote-s1").reason());
            assertFalse(governance(runtime).reports().get("t-vote-s4").accepted());
            assertEquals(BoardModel.REASON_NOT_ELIGIBLE_VOTER,
                    governance(runtime).reports().get("t-vote-s4").reason());
            assertEquals(1, meeting(runtime, script.meetingId()).materials().size(),
                    "被拒绝的材料不得登记");
        }
    }

    // ------------------------------------------------------------------
    // 组 11：空提案预算会议必须形成明确终态；主席以外不能召集
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空提案预算会议到截止为 REJECTED/EMPTY_PROPOSAL，不留永久冻结；非主席召集拒绝")
    void emptyProposalMeetingReachesRejectedTerminal() throws IOException {
        Path file = tempDir.resolve("empty-proposal.journal");
        try (WorldRuntime runtime = open(file)) {
            long convene = DAY;
            long opens = DAY + 10_000L;
            long closes = DAY + 20_000L;
            runtime.scheduleBoardMeeting("c-empty", "t-empty", convene, "s1", "empty-meeting",
                    DAY + 5_000L, opens, closes, allSeats(), List.of());
            runtime.advanceTo(opens);
            assertEquals(MeetingStatus.MATERIALS_FROZEN,
                    meeting(runtime, "empty-meeting").status(), "空提案不能 startVoting");
            runtime.advanceTo(closes);
            Meeting closed = meeting(runtime, "empty-meeting");
            assertEquals(MeetingStatus.REJECTED, closed.status(), "到期必须是明确终态");
            BoardRun.Report close = null;
            for (BoardRun.Report report : governance(runtime).reports().values()) {
                if (PendingTask.BOARD_CLOSE_V1.equals(report.kind())) {
                    close = report;
                }
            }
            assertNotNull(close);
            assertTrue(close.accepted());
            assertEquals(BoardModel.REASON_EMPTY_PROPOSAL, close.reason());
            assertTrue(governance(runtime).board().budgets().isEmpty());
            assertTrue(pending(runtime, PendingTask.BOARD_CLOSE_V1).isEmpty());

            long position = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleBoardMeeting(
                    "c-bad-chair", "t-bad-chair", closes + 1000, "s2", "bad-chair",
                    closes + 2000, closes + 3000, closes + 4000, allSeats(), List.of()),
                    "只有章程主席可以召集");
            assertEquals(position, runtime.snapshot().position(), "拒绝不得写盘");
        }
    }

    // ------------------------------------------------------------------
    // 组 12：私有错误副本反证——快照漏掉预算承诺必须被交叉核对拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("私有错误副本：从快照删掉采购预算承诺，恢复必须因缺承诺拒绝")
    void snapshotWithoutBudgetCommitmentIsRejected() throws IOException {
        Path source = tempDir.resolve("missing-commitment-source.journal");
        Path broken = tempDir.resolve("missing-commitment-broken.journal");
        try (WorldRuntime runtime = open(source)) {
            MeetingScript budget = scheduleBudget(runtime, "m-gap", DAY,
                    budgetLines("500.00", "0.00"), allSeats(), List.of());
            vote(runtime, budget, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    budget.opensAt());
            runtime.scheduleBudgetImplementation("c-gap-impl", "t-gap-impl",
                    budget.closesAt() + 1, "s1", budget.meetingId());
            runtime.advanceTo(budget.closesAt() + 1);
            runtime.schedulePurchaseOrder("c-gap-po", "po-gap", 17 * DAY, "company",
                    "po-gap", 10);
            runtime.advanceTo(17 * DAY);
            assertEquals(1, governance(runtime).board().commitments().size());
        }
        ObjectMapper json = new ObjectMapper();
        boolean changed = false;
        try (WorldJournal original = new WorldJournal(source, RUN);
             WorldJournal rewritten = new WorldJournal(broken, RUN)) {
            for (WorldCommit commit : original.commits()) {
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    if ("STATE_SNAPSHOT_V1".equals(event.type())
                            && event.payload().contains("\"commitmentId\":\"po:po-gap\"")) {
                        String payload = event.payload();
                        int start = payload.indexOf("{\"commitmentId\":\"po:po-gap\"");
                        int end = payload.indexOf('}', start) + 1;
                        String tampered = payload.substring(0, start) + payload.substring(end);
                        events.add(new WorldEvent(event.eventId(), event.type(), tampered));
                        changed = true;
                    } else {
                        events.add(event);
                    }
                }
                rewritten.commit(commit.commandId(), commit.commandContent(), commit.result(),
                        events);
            }
        }
        assertTrue(changed, "必须实际删到预算承诺副本");
        IOException failure = assertThrows(IOException.class, () -> open(broken).close());
        assertTrue(failure.getMessage().contains("采购单没有预算承诺"), failure.getMessage());
    }

    // ------------------------------------------------------------------
    // 组 13：恢复期旧材料/提案/选票/failureReason 不可改删
    // ------------------------------------------------------------------

    @Test
    @DisplayName("五席通过的会议不能改写为不足法定人数")
    void adoptedMeetingCannotBeRelabelledAsNoQuorum() throws IOException {
        Path source = adoptedMeetingJournal("false-quorum");
        String id = "meeting:false-quorum";
        Path rewritten = tamperSnapshots(source, "false-quorum-rewritten.journal",
                root -> "ADOPTED".equals(root.path("governance").path("board")
                        .path("meetings").path(id).path("status").asText()),
                root -> meetingNode(root, id).put("status", "ADJOURNED_NO_QUORUM"));
        IOException failure = assertThrows(IOException.class, () -> open(rewritten).close());
        assertTrue(failure.getMessage().contains("法定人数"), failure.getMessage());
    }

    @Test
    @DisplayName("通过后未实施的会议不能改写为已实施")
    void adoptedMeetingNeedsAnActualImplementationReport() throws IOException {
        Path source = adoptedMeetingJournal("false-implemented");
        String id = "meeting:false-implemented";
        Path rewritten = tamperSnapshots(source, "false-implemented-rewritten.journal",
                root -> root.path("timeMillis").asLong() > DAY + 20_000L
                        && "ADOPTED".equals(root.path("governance").path("board")
                        .path("meetings").path(id).path("status").asText()),
                root -> meetingNode(root, id).put("status", "IMPLEMENTED"));
        IOException failure = assertThrows(IOException.class, () -> open(rewritten).close());
        assertTrue(failure.getMessage().contains("实施回报"), failure.getMessage());
    }

    /** 生成只有通过决议、没有实施的真实存档。 */
    private Path adoptedMeetingJournal(String tag) throws IOException {
        Path source = tempDir.resolve(tag + ".journal");
        try (WorldRuntime runtime = open(source)) {
            MeetingScript script = scheduleBudget(runtime, tag, DAY,
                    budgetLines("500.00", "0.00"), allSeats(), List.of());
            vote(runtime, script, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    script.opensAt());
            runtime.advanceTo(script.closesAt());
            assertEquals(MeetingStatus.ADOPTED, meeting(runtime, script.meetingId()).status());
            assertTrue(governance(runtime).board().budgets().isEmpty());
            runtime.advanceTo(script.closesAt() + 1);
        }
        return source;
    }

    @Test
    @DisplayName("从选票首次出现起一致改写全部快照，仍须因表决终态矛盾拒绝")
    void consistentlyRewrittenBallotsCannotContradictDecision() throws IOException {
        String tag = "consistent-ballot";
        String meetingId = "meeting:" + tag;
        Path source = tempDir.resolve("consistent-ballot-source.journal");
        try (WorldRuntime runtime = open(source)) {
            MeetingScript script = scheduleBudget(runtime, tag, DAY,
                    budgetLines("1100.00", "0.00"), allSeats(), List.of());
            vote(runtime, script, List.of("s1", "s2", "s3"), List.of("s4"), List.of(),
                    script.opensAt());
            runtime.advanceTo(script.closesAt());
            assertEquals(MeetingStatus.REJECTED, meeting(runtime, meetingId).status());
        }
        Path rewritten = tamperSnapshots(source, "consistent-ballot-rewritten.journal",
                root -> root.path("governance").path("board").path("meetings")
                        .path(meetingId).path("ballots").has("s4"),
                root -> ((ObjectNode) meetingNode(root, meetingId).path("ballots").path("s4"))
                        .put("vote", "YES"));
        IOException failure = assertThrows(IOException.class, () -> open(rewritten).close());
        assertTrue(failure.getMessage().contains("表决终态与选票不一致"), failure.getMessage());
    }

    @Test
    @DisplayName("恢复期改写或删除旧选票必须 IOException")
    void tamperedOldBallotsAreRejectedOnRestore() throws IOException {
        String tag = "ballot";
        String meetingId = "meeting:" + tag;
        long closesAt = DAY + 20_000L;
        Path source = tempDir.resolve("ballot-source.journal");
        try (WorldRuntime runtime = open(source)) {
            MeetingScript script = scheduleBudget(runtime, tag, DAY,
                    budgetLines("1100.00", "0.00"), allSeats(), List.of());
            vote(runtime, script, List.of("s1", "s2", "s3"), List.of("s4"), List.of("s5"),
                    script.opensAt());
            runtime.advanceTo(closesAt);
            assertEquals(MeetingStatus.REJECTED, meeting(runtime, meetingId).status());
        }
        Predicate<ObjectNode> terminalFrame = root -> root.path("timeMillis").asLong() >= closesAt
                && meetingNode(root, meetingId).path("ballots").has("s4");
        Path changed = tamperSnapshots(source, "ballot-changed.journal", terminalFrame, root ->
                ((ObjectNode) ((ObjectNode) meetingNode(root, meetingId).get("ballots")).get("s4"))
                        .put("vote", "YES"));
        IOException changedFailure = assertThrows(IOException.class, () -> open(changed).close());
        assertTrue(changedFailure.getMessage().contains("旧选票"), changedFailure.getMessage());

        Path removed = tamperSnapshots(source, "ballot-removed.journal", terminalFrame, root ->
                ((ObjectNode) meetingNode(root, meetingId).get("ballots")).remove("s4"));
        IOException removedFailure = assertThrows(IOException.class, () -> open(removed).close());
        assertTrue(removedFailure.getMessage().contains("旧选票"), removedFailure.getMessage());
    }

    @Test
    @DisplayName("冻结材料被改写或追加必须 IOException；CONVENED 正常追加可恢复")
    void tamperedFrozenMaterialsAreRejectedButNormalAppendRestores() throws IOException {
        String tag = "materials";
        String meetingId = "meeting:" + tag;
        long closesAt = DAY + 20_000L;
        Path source = tempDir.resolve("materials-source.journal");
        try (WorldRuntime runtime = open(source)) {
            runtime.scheduleBoardMeeting("c-convene-" + tag, "t-convene-" + tag, DAY, "s1",
                    meetingId, DAY + 5_000L, DAY + 10_000L, closesAt, allSeats(), List.of());
            runtime.scheduleBoardMaterial("c-mat-1", "t-mat-1", DAY + 1, "s3", meetingId,
                    "mat-1", "src-1", 1L);
            runtime.scheduleBoardMaterial("c-mat-2", "t-mat-2", DAY + 2, "s3", meetingId,
                    "mat-2", "src-2", 1L);
            runtime.scheduleBudgetProposal("c-prop-" + tag, "t-prop-" + tag, DAY + 3, "s3",
                    meetingId, budgetLines("500.00", "0.00"));
            vote(runtime, new MeetingScript(meetingId, DAY, DAY + 10_000L, closesAt),
                    List.of("s1", "s2", "s3"), List.of(), List.of(), DAY + 10_000L);
            runtime.advanceTo(closesAt);
            assertEquals(MeetingStatus.ADOPTED, meeting(runtime, meetingId).status());
        }
        // 正常追加（2 份材料）与 CONVENED→VOTING 同批冻结后恢复必须成功
        try (WorldRuntime restored = open(source)) {
            assertEquals(2, meeting(restored, meetingId).materials().size());
            assertEquals(MeetingStatus.ADOPTED, meeting(restored, meetingId).status());
        }
        Predicate<ObjectNode> terminalFrame = root -> root.path("timeMillis").asLong() >= closesAt;

        Path changed = tamperSnapshots(source, "materials-changed.journal", terminalFrame, root ->
                ((ObjectNode) ((ArrayNode) meetingNode(root, meetingId).get("materials")).get(0))
                        .put("sourceRef", "evil"));
        IOException changedFailure = assertThrows(IOException.class, () -> open(changed).close());
        assertTrue(changedFailure.getMessage().contains("冻结后材料"), changedFailure.getMessage());

        Path appended = tamperSnapshots(source, "materials-appended.journal", terminalFrame, root -> {
            ObjectNode extra = root.objectNode();
            extra.put("materialId", "mat-9");
            extra.put("sourceRef", "src-9");
            extra.put("version", 1);
            extra.put("asOf", DAY);
            ((ArrayNode) meetingNode(root, meetingId).get("materials")).add(extra);
        });
        IOException appendedFailure = assertThrows(IOException.class, () -> open(appended).close());
        assertTrue(appendedFailure.getMessage().contains("冻结后材料"), appendedFailure.getMessage());
    }

    @Test
    @DisplayName("已登记提案被改写或删除必须 IOException")
    void tamperedProposalIsRejectedOnRestore() throws IOException {
        String tag = "proposal";
        String meetingId = "meeting:" + tag;
        long closesAt = DAY + 20_000L;
        Path source = tempDir.resolve("proposal-source.journal");
        try (WorldRuntime runtime = open(source)) {
            MeetingScript script = scheduleBudget(runtime, tag, DAY,
                    budgetLines("500.00", "0.00"), allSeats(), List.of());
            vote(runtime, script, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    script.opensAt());
            runtime.advanceTo(closesAt);
            assertEquals(MeetingStatus.ADOPTED, meeting(runtime, meetingId).status());
        }
        Predicate<ObjectNode> terminalFrame = root -> root.path("timeMillis").asLong() >= closesAt
                && meetingNode(root, meetingId).path("proposedLines").size() > 0;

        Path changed = tamperSnapshots(source, "proposal-changed.journal", terminalFrame, root ->
                ((ObjectNode) ((ArrayNode) meetingNode(root, meetingId).get("proposedLines")).get(0))
                        .put("newLimit", new BigDecimal("900.00")));
        IOException changedFailure = assertThrows(IOException.class, () -> open(changed).close());
        assertTrue(changedFailure.getMessage().contains("已提案"), changedFailure.getMessage());

        Path removed = tamperSnapshots(source, "proposal-removed.journal", terminalFrame, root ->
                ((ArrayNode) meetingNode(root, meetingId).get("proposedLines")).removeAll());
        IOException removedFailure = assertThrows(IOException.class, () -> open(removed).close());
        assertTrue(removedFailure.getMessage().contains("已提案"), removedFailure.getMessage());
    }

    @Test
    @DisplayName("实施失败原因不可改写，且与治理回报一致")
    void tamperedFailureReasonIsRejectedOnRestore() throws IOException {
        String tag = "failure";
        String meetingId = "meeting:" + tag;
        Path source = tempDir.resolve("failure-source.journal");
        long lowerConvene = 3 * DAY;
        long implementAt = lowerConvene + 20_001L;
        try (WorldRuntime runtime = open(source)) {
            MeetingScript base = scheduleBudget(runtime, "failure-base", DAY,
                    budgetLines("500.00", "0.00"), allSeats(), List.of());
            vote(runtime, base, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    base.opensAt());
            runtime.scheduleBudgetImplementation("c-fb-impl", "t-fb-impl",
                    base.closesAt() + 1, "s1", base.meetingId());
            runtime.advanceTo(base.closesAt() + 1);
            // 形成 500 活动承诺，之后把 period0 上限降到 400 必然失败
            runtime.schedulePurchaseOrder("c-fail-po", "po-failure", 2 * DAY, "company",
                    "po-failure", 10);
            runtime.advanceTo(2 * DAY);

            MeetingScript lower = scheduleBudget(runtime, tag, lowerConvene,
                    budgetLines("400.00", "0.00"), allSeats(), List.of());
            vote(runtime, lower, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    lower.opensAt());
            runtime.scheduleBudgetImplementation("c-fi-impl", "t-fi-impl", implementAt, "s1",
                    lower.meetingId());
            runtime.advanceTo(implementAt);
            Meeting failed = meeting(runtime, meetingId);
            assertEquals(MeetingStatus.IMPLEMENTATION_FAILED, failed.status());
            assertEquals(BoardModel.REASON_BUDGET_BELOW_COMMITTED, failed.failureReason());
        }
        // 同一日志原样恢复是正例
        try (WorldRuntime restored = open(source)) {
            assertEquals(BoardModel.REASON_BUDGET_BELOW_COMMITTED,
                    meeting(restored, meetingId).failureReason());
        }
        Path tampered = tamperSnapshots(source, "failure-tampered.journal",
                root -> root.path("timeMillis").asLong() == implementAt,
                root -> meetingNode(root, meetingId).put("failureReason", "OTHER"));
        IOException failure = assertThrows(IOException.class, () -> open(tampered).close());
        assertTrue(failure.getMessage().contains("失败原因"), failure.getMessage());
    }

    @Test
    @DisplayName("已关期间预算上限不能在恢复跨帧中被事后改写")
    void closedPeriodBudgetCapCannotBeRewrittenOnRestore() throws IOException {
        String tag = "closed-cap";
        Path source = tempDir.resolve("closed-cap-source.journal");
        try (WorldRuntime runtime = open(source)) {
            MeetingScript base = scheduleBudget(runtime, tag, DAY,
                    budgetLines("500.00", "0.00"), allSeats(), List.of());
            vote(runtime, base, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    base.opensAt());
            runtime.scheduleBudgetImplementation("c-cc-impl", "t-cc-impl",
                    base.closesAt() + 1, "s1", base.meetingId());
            runtime.advanceTo(base.closesAt() + 1);
            runtime.advanceTo(30 * DAY + 1);
            assertEquals(1L, manufacturing(runtime).ledger().closedPeriods());
        }
        Path tampered = tamperSnapshots(source, "closed-cap-tampered.journal",
                root -> root.path("timeMillis").asLong() >= 30 * DAY,
                root -> budgetNode(root, 0L, "PROCUREMENT")
                        .put("cap", new BigDecimal("900.00")));
        IOException failure = assertThrows(IOException.class, () -> open(tampered).close());
        assertTrue(failure.getMessage().contains("已关期间预算上限"), failure.getMessage());
    }

    // ------------------------------------------------------------------
    // 组 14：已关期间不得事后提案/跨期实施；未来期间仍可授权
    // ------------------------------------------------------------------

    @Test
    @DisplayName("关期后提案 period0 被 BUDGET_PERIOD_CLOSED 拒绝，钱/库存/承诺/证据不变")
    void proposalForClosedPeriodIsRejectedWithNoSideEffects() throws IOException {
        Path file = tempDir.resolve("closed-proposal.journal");
        try (WorldRuntime runtime = open(file)) {
            MeetingScript base = scheduleBudget(runtime, "closed-proposal-base", DAY,
                    budgetLines("500.00", "0.00"), allSeats(), List.of());
            vote(runtime, base, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    base.opensAt());
            runtime.scheduleBudgetImplementation("c-cp-impl", "t-cp-impl",
                    base.closesAt() + 1, "s1", base.meetingId());
            runtime.advanceTo(base.closesAt() + 1);
            runtime.advanceTo(30 * DAY + 1);

            BigDecimal cashBefore = cash(runtime, "CO");
            long rawBefore = manufacturing(runtime).ledger().rawMaterials().quantity();
            List<BoardModel.Commitment> commitmentsBefore =
                    List.copyOf(governance(runtime).board().commitments());
            Map<Long, PeriodEvidence> evidenceBefore =
                    Map.copyOf(governance(runtime).periodEvidence());

            MeetingScript late = scheduleBudget(runtime, "closed-proposal", 31 * DAY,
                    budgetLines("900.00", "0.00"), allSeats(), List.of());
            runtime.advanceTo(late.opensAt());

            BoardRun.Report proposal = governance(runtime).reports()
                    .get("t-prop-closed-proposal");
            assertFalse(proposal.accepted());
            assertEquals(WorldRuntime.REASON_BUDGET_PERIOD_CLOSED, proposal.reason());
            assertTrue(meeting(runtime, late.meetingId()).proposedLines().isEmpty());
            assertEquals(0, cap(runtime, 0L, BudgetCategory.PROCUREMENT)
                    .compareTo(bd("500.00")));
            assertEquals(0, cashBefore.compareTo(cash(runtime, "CO")));
            assertEquals(rawBefore, manufacturing(runtime).ledger().rawMaterials().quantity());
            assertEquals(commitmentsBefore, governance(runtime).board().commitments());
            assertEquals(evidenceBefore, governance(runtime).periodEvidence());
        }
    }

    @Test
    @DisplayName("提案未关但实施跨过关期，实施被 BUDGET_PERIOD_CLOSED 拒绝且预算不变")
    void implementationAcrossPeriodCloseIsRejectedWithNoSideEffects() throws IOException {
        Path file = tempDir.resolve("closed-implement.journal");
        try (WorldRuntime runtime = open(file)) {
            MeetingScript base = scheduleBudget(runtime, "closed-impl-base", DAY,
                    budgetLines("500.00", "0.00"), allSeats(), List.of());
            vote(runtime, base, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    base.opensAt());
            runtime.scheduleBudgetImplementation("c-ci-impl", "t-ci-impl", 31 * DAY, "s1",
                    base.meetingId());
            runtime.advanceTo(30 * DAY + 1);
            assertEquals(MeetingStatus.ADOPTED, meeting(runtime, base.meetingId()).status());
            assertTrue(governance(runtime).board().budgets().isEmpty());
            BigDecimal cashBefore = cash(runtime, "CO");
            List<BoardModel.Commitment> commitmentsBefore =
                    List.copyOf(governance(runtime).board().commitments());
            Map<Long, PeriodEvidence> evidenceBefore =
                    Map.copyOf(governance(runtime).periodEvidence());

            runtime.advanceTo(31 * DAY);
            BoardRun.Report report = governance(runtime).reports().get("t-ci-impl");
            assertFalse(report.accepted());
            assertEquals(WorldRuntime.REASON_BUDGET_PERIOD_CLOSED, report.reason());
            assertEquals(MeetingStatus.ADOPTED, meeting(runtime, base.meetingId()).status());
            assertTrue(governance(runtime).board().budgets().isEmpty());
            assertEquals(0, cashBefore.compareTo(cash(runtime, "CO")));
            assertEquals(commitmentsBefore, governance(runtime).board().commitments());
            assertEquals(evidenceBefore, governance(runtime).periodEvidence());
        }
    }

    @Test
    @DisplayName("关期后仍可授权未来期间，且已关期间 cap 不被改动")
    void futurePeriodBudgetAfterCloseIsAllowed() throws IOException {
        Path file = tempDir.resolve("future-budget.journal");
        try (WorldRuntime runtime = open(file)) {
            MeetingScript base = scheduleBudget(runtime, "future-base", DAY,
                    budgetLines("500.00", "0.00"), allSeats(), List.of());
            vote(runtime, base, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    base.opensAt());
            runtime.scheduleBudgetImplementation("c-fb-base", "t-fb-base",
                    base.closesAt() + 1, "s1", base.meetingId());
            runtime.advanceTo(base.closesAt() + 1);
            runtime.advanceTo(30 * DAY + 1);

            MeetingScript future = scheduleBudget(runtime, "future", 31 * DAY,
                    List.of(new BudgetLine(1L, BudgetCategory.PROCUREMENT, bd("600.00")),
                            new BudgetLine(2L, BudgetCategory.PROCUREMENT, bd("300.00"))),
                    allSeats(), List.of());
            vote(runtime, future, List.of("s1", "s2", "s3"), List.of(), List.of(),
                    future.opensAt());
            runtime.scheduleBudgetImplementation("c-future-impl", "t-future-impl",
                    future.closesAt() + 1, "s1", future.meetingId());
            runtime.advanceTo(future.closesAt() + 1);

            assertEquals(MeetingStatus.IMPLEMENTED,
                    meeting(runtime, future.meetingId()).status());
            assertEquals(0, cap(runtime, 0L, BudgetCategory.PROCUREMENT)
                    .compareTo(bd("500.00")), "已关期间 cap 不得改动");
            assertEquals(0, cap(runtime, 1L, BudgetCategory.PROCUREMENT)
                    .compareTo(bd("600.00")));
            assertEquals(0, cap(runtime, 2L, BudgetCategory.PROCUREMENT)
                    .compareTo(bd("300.00")));
            assertTrue(governance(runtime).reports().get("t-future-impl").accepted());
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 一个预算会议的时间表：召集、提案、投票都排在已知时点，实施在关会后 1ms。 */
    private record MeetingScript(String meetingId, long conveneAt, long opensAt, long closesAt) {
    }

    private static MeetingScript scheduleBudget(WorldRuntime runtime, String tag, long conveneAt,
                                                List<BudgetLine> lines, List<String> attendees,
                                                List<String> recused) throws IOException {
        return scheduleBudget(runtime, tag, conveneAt, lines, attendees, recused, "s3");
    }

    private static MeetingScript scheduleBudget(WorldRuntime runtime, String tag, long conveneAt,
                                                List<BudgetLine> lines, List<String> attendees,
                                                List<String> recused, String proposalSeat)
            throws IOException {
        long materialDeadline = conveneAt + 5_000;
        long opensAt = conveneAt + 10_000;
        long closesAt = conveneAt + 20_000;
        String meetingId = "meeting:" + tag;
        runtime.scheduleBoardMeeting("c-convene-" + tag, "t-convene-" + tag, conveneAt, "s1",
                meetingId, materialDeadline, opensAt, closesAt, attendees, recused);
        runtime.scheduleBudgetProposal("c-prop-" + tag, "t-prop-" + tag, conveneAt + 1,
                proposalSeat, meetingId, lines);
        return new MeetingScript(meetingId, conveneAt, opensAt, closesAt);
    }

    private static void vote(WorldRuntime runtime, MeetingScript script, List<String> yes,
                             List<String> no, List<String> abstain, long at) throws IOException {
        List<String> all = new ArrayList<>(yes);
        all.addAll(no);
        all.addAll(abstain);
        for (String seat : all) {
            Vote vote = yes.contains(seat) ? Vote.YES
                    : no.contains(seat) ? Vote.NO : Vote.ABSTAIN;
            String tag = script.meetingId() + "-" + seat;
            runtime.scheduleBoardVote("c-vote-" + tag, "t-vote-" + tag, at, seat,
                    script.meetingId(), vote);
        }
    }

    private static List<BudgetLine> budgetLines(String procurement, String labor) {
        return List.of(
                new BudgetLine(0L, BudgetCategory.PROCUREMENT, bd(procurement)),
                new BudgetLine(0L, BudgetCategory.PRODUCTION_LABOR, bd(labor)));
    }

    @Test
    void legacyProfileCannotRestoreDividendMeetingEvenWithValidFrameHash() throws IOException {
        Path source = tempDir.resolve("legacy-dividend-source.journal");
        try (WorldRuntime runtime = open(source)) {
            runtime.scheduleBoardMeeting("legacy-dividend-c", "legacy-dividend-t", 1,
                    "s1", "legacy-meeting", 2, 3, 4, allSeats(), List.of());
            runtime.advanceTo(1);
            assertEquals(BoardModel.Topic.BUDGET, meeting(runtime, "legacy-meeting").topic());
        }
        try (WorldRuntime legal = open(source)) {
            assertEquals(MeetingStatus.CONVENED, meeting(legal, "legacy-meeting").status());
        }
        Path forged = tamperSnapshots(source, "legacy-dividend-forged.journal",
                root -> root.path("governance").path("board").path("meetings").has("legacy-meeting"),
                root -> meetingNode(root, "legacy-meeting").put("topic", "DIVIDEND_AUTHORIZATION"));
        IOException failure = assertThrows(IOException.class, () -> open(forged).close());
        assertTrue(failure.getMessage().contains("分红"), failure.getMessage());
    }

    private WorldRuntime open(Path file) throws IOException {
        return new WorldRuntime(file, governedManifest(), OPENINGS, registry(), MFG, charter());
    }

    private static BoardRun governance(WorldRuntime runtime) {
        return runtime.snapshot().governance()
                .orElseThrow(() -> new AssertionError("缺少治理子集"));
    }

    private static ManufacturingRun manufacturing(WorldRuntime runtime) {
        return runtime.snapshot().manufacturing().orElseThrow();
    }

    private static ManufacturingModel.Report book(WorldRuntime runtime) {
        return ManufacturingModel.report(manufacturing(runtime).ledger(),
                runtime.snapshot().accounts());
    }

    private static Meeting meeting(WorldRuntime runtime, String meetingId) {
        Meeting found = governance(runtime).board().meeting(meetingId);
        assertNotNull(found, "缺少会议：" + meetingId);
        return found;
    }

    private static ReviewResult review(WorldRuntime runtime, String meetingId) {
        for (ReviewResult review : governance(runtime).board().reviews()) {
            if (review.reviewId().equals(meetingId)) {
                return review;
            }
        }
        throw new AssertionError("缺少考核结果：" + meetingId);
    }

    private static ReportEntry report(WorldRuntime runtime, String taskId) {
        ReportEntry found = manufacturing(runtime).reports().get(taskId);
        assertNotNull(found, "缺少制造报告：" + taskId + "，已有 "
                + manufacturing(runtime).reports().keySet());
        return found;
    }

    private static BoardModel.Commitment commitment(WorldRuntime runtime, String id) {
        for (BoardModel.Commitment commitment : governance(runtime).board().commitments()) {
            if (commitment.commitmentId().equals(id)) {
                return commitment;
            }
        }
        throw new AssertionError("缺少预算承诺：" + id);
    }

    private static BigDecimal cap(WorldRuntime runtime, long period, BudgetCategory category) {
        BoardModel.Budget budget = governance(runtime).board().budget(period, category);
        assertNotNull(budget, "缺少预算上限：" + period + "/" + category);
        return budget.cap();
    }

    private static BigDecimal paidBudget(WorldRuntime runtime, long period,
                                         BudgetCategory category) {
        return BoardModel.spentBudget(governance(runtime).board(), period, category);
    }

    private static List<PendingTask> pending(WorldRuntime runtime, String kind) {
        List<PendingTask> found = new ArrayList<>();
        for (PendingTask task : runtime.snapshot().pending()) {
            if (task.kind().equals(kind)) {
                found.add(task);
            }
        }
        return found;
    }

    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** 完整重写每个状态快照的 JSON（WorldJournal 重算帧摘要），用于恢复期篡改反证。 */
    private Path tamperSnapshots(Path source, String name, Predicate<ObjectNode> when,
                                 Consumer<ObjectNode> change) throws IOException {
        Path target = tempDir.resolve(name);
        ObjectMapper json = new ObjectMapper();
        try (WorldJournal original = new WorldJournal(source, RUN);
             WorldJournal rewritten = new WorldJournal(target, RUN)) {
            for (WorldCommit commit : original.commits()) {
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    if ("STATE_SNAPSHOT_V1".equals(event.type())) {
                        ObjectNode root = (ObjectNode) json.readTree(event.payload());
                        if (when == null || when.test(root)) {
                            change.accept(root);
                            events.add(new WorldEvent(event.eventId(), event.type(),
                                    json.writeValueAsString(root)));
                        } else {
                            events.add(event);
                        }
                    } else {
                        events.add(event);
                    }
                }
                rewritten.commit(commit.commandId(), commit.commandContent(), commit.result(),
                        events);
            }
        }
        return target;
    }

    private static ObjectNode meetingNode(ObjectNode root, String meetingId) {
        return (ObjectNode) root.path("governance").path("board").path("meetings")
                .path(meetingId);
    }

    private static ObjectNode budgetNode(ObjectNode root, long period, String category) {
        for (JsonNode node : root.path("governance").path("board").path("budgets")) {
            if (node.path("period").asLong() == period
                    && category.equals(node.path("category").asText())) {
                return (ObjectNode) node;
            }
        }
        throw new IllegalStateException("缺少预算行：" + period + "/" + category);
    }

    /**
     * 在世界日志字节里定位某个命令的帧，返回 {起始偏移, 载荷长度}。
     * 帧格式：int 长度、int 反码、JSON 载荷、32 字节 SHA-256。
     */
    private static long[] frameOf(byte[] bytes, String commandId) throws IOException {
        ObjectMapper json = new ObjectMapper();
        int offset = 0;
        while (offset + 8 <= bytes.length) {
            int length = readInt(bytes, offset);
            int complement = readInt(bytes, offset + 4);
            if (complement != ~length || length <= 0) {
                break;
            }
            byte[] payload = java.util.Arrays.copyOfRange(bytes, offset + 8,
                    offset + 8 + length);
            WorldCommit commit = json.readValue(payload, WorldCommit.class);
            if (commandId.equals(commit.commandId())) {
                return new long[]{offset, length};
            }
            offset += 8 + length + 32;
        }
        return new long[]{-1, -1};
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    // ------------------------------------------------------------------
    // 登记与清单
    // ------------------------------------------------------------------

    private static OpeningRegistry registry() {
        return registryWithCompanyOrg("cufe");
    }

    /** 把公司财产主体绑到另一个组织，用来验证章程公司必须就是制造公司组织。 */
    private static OpeningRegistry registryWithCompanyOrg(String organization) {
        List<OpeningRegistry.Party> parties = new ArrayList<>(List.of(
                new OpeningRegistry.Party("cufe", PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party("investor-org", PartyKind.ORGANIZATION, "投资者组织"),
                new OpeningRegistry.Party("p1", PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party("p2", PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party("p3", PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party("p4", PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party("p5", PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party("investor", PartyKind.PERSON, "投资者")));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner("company", OwnerKind.ORGANIZATION, organization),
                new OpeningRegistry.PropertyOwner("supplier", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("customer", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("ops", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("investor", OwnerKind.PERSON, "investor"));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation("INV", SYM, 200,
                        new BigDecimal("10.0000"), ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners,
                List.of(new OpeningRegistry.Security(SYM, organization, 200, bd("10.00"))),
                allocations);
    }

    private static RunManifest governedManifest() {
        return manifest(RunManifest.RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1, true);
    }

    private static RunManifest manufacturingManifest() {
        return manifest(RunManifest.RULES_STOCK_COMPANY_EXPERIMENT_V1, false);
    }

    private static RunManifest manifest(String rules, boolean governance) {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        modules.put("manufacturing", 1);
        if (governance) {
            modules.put("governance", 1);
        }
        return new RunManifest(RUN, SEED, rules, modules, Map.of(), RunManifest.MODEL_NONE);
    }
}
