package edu.cufe.auction.world.composed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.BoardRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.ResearchModel;
import edu.cufe.auction.world.SecuritiesLedger;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.WorldScheduler;
import edu.cufe.auction.world.WorldRoleSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 独立研发公司（pharma）世界桥：真实董事预算/任免 → 阶段付款与结果 → 商业化批准 → 期末摊销与
 * 周期报告；工程公司现金与董事会一字未动。
 *
 * <p>全部断言读真实 journal/董事会/研发状态；不 mock、不替换命令、不放宽比较。数值口径：
 * pharma 历史资本 2,000.00（EXP），p1 三阶段 200/300/400 = 900.00，摊销 700×整日数/365。</p>
 */
class ComposedPharmaWorldTest {

    private static final String P = ComposedPharmaScenario.PHARMA;
    private static final String CASH = ComposedPharmaScenario.PHARMA_CASH;
    private static final String ENG_A_ACCOUNT = "ENG-A";
    private static final long DAY = 86_400_000L;

    @TempDir
    Path dir;

    // ------------------------------------------------------------------ 开局

    @Test
    void openingIsAnIndependentCompanyWithOwnCashSharesAndNoManagerOrBudget() throws Exception {
        try (WorldRuntime world = ComposedPharmaScenario.open(dir.resolve("open.journal"))) {
            assertEquals(P, world.researchCompanyId(), "研发必须唯一绑定独立研发公司");
            assertEquals(P, world.researchRun().orElseThrow().companyPartyId());
            // 独立财产主体 + 唯一现金账户 + EXP 历史资本 2000
            assertEquals(new BigDecimal("2000.00"),
                    world.snapshot().accounts().get(CASH).cash());
            assertEquals(ComposedPharmaScenario.PHARMA_OWNER,
                    world.snapshot().accounts().get(CASH).ownerId());
            assertEquals(new BigDecimal("2000.00"),
                    world.researchRun().orElseThrow().state().initialEquity(),
                    "研发初始权益必须等于本公司历史资本，不是工程权益");
            // 工程公司现金与初始权益没有被复制或改写
            assertEquals(new BigDecimal("5000.00"),
                    world.snapshot().accounts().get(ENG_A_ACCOUNT).cash());
            // 发行/持股：PH1 200 股真实登记并由发起人持有
            SecuritiesLedger.Lot lot = null;
            for (SecuritiesLedger.Lot candidate
                    : world.snapshot().securities().orElseThrow().lots()) {
                if (candidate.symbol().equals(ComposedPharmaScenario.PH1)) {
                    lot = candidate;
                }
            }
            assertNotNull(lot, "必须存在 PH1 持股批次");
            assertEquals(200L, lot.remaining());
            assertEquals(ComposedPharmaScenario.PHARMA_SEC, lot.accountId());
            assertEquals(0, lot.costPrice().compareTo(new BigDecimal("10.0000")));
            // 证券不得借用：PH1 只可能落在研发公司发起人账户，其它公司账户不得持有
            for (SecuritiesLedger.Lot candidate
                    : world.snapshot().securities().orElseThrow().lots()) {
                if (!ComposedPharmaScenario.PH1.equals(candidate.symbol())) {
                    continue;
                }
                assertEquals(ComposedPharmaScenario.PHARMA_SEC, candidate.accountId(),
                        "PH1 不得出现在其它公司账户：" + candidate.accountId());
            }
            // 真实董事会只有一份，且不是工程董事会
            BoardRun pharma = world.boardOfCompany(P).orElseThrow();
            assertTrue(pharma.board().charter().companyPartyId().equals(P));
            assertNotEquals(world.boardOfCompany(WorldWebScenario.ENGINE_A).orElseThrow(), pharma);
            // 开局没有经理、没有预算、没有批准
            assertFalse(world.isActiveManager(P, "kate", world.snapshot().timeMillis()));
            assertTrue(pharma.board().budgets().isEmpty(), "开局不得预批预算");
            assertTrue(pharma.board().commitments().isEmpty(), "开局不得预占预算");
            assertTrue(world.researchRun().orElseThrow().state().projects().isEmpty(),
                    "开局不得预置立项或完成态");
            // 同帧正确报告：A = L + E
            ComposedCompanyReport report = ComposedCompanyReport.of(world, P);
            assertTrue(report.balanced(), "公司报表必须平衡");
            assertEquals(0, report.totalAssets().compareTo(new BigDecimal("2000.00")));
            assertEquals(0, report.totalEquity().compareTo(new BigDecimal("2000.00")));
            assertEquals(0, report.totalLiabilities().compareTo(new BigDecimal("0.00")));
            assertEquals(0, report.profit().compareTo(new BigDecimal("0.00")));
        }
    }

    // ------------------------------------------------------------------ 治理 → 研发 → 批准 → 摊销

    @Test
    void realGovernanceBudgetAndAppointmentThenResearchApprovalAndPeriodicReport() throws Exception {
        Path journal = dir.resolve("chain.journal");
        try (WorldRuntime world = ComposedPharmaScenario.open(journal)) {
            grantBudgetAndAppointManager(world);
            assertTrue(world.isActiveManager(P, "ivan", world.snapshot().timeMillis()),
                    "真实任免必须产生有效经理");
            assertFalse(world.boardOfCompany(P).orElseThrow().board().budgets().isEmpty(),
                    "真实预算决议必须写入预算表");
            long docsAt = completeP1(world);

            // 研发现金只从 pharma 账户付给有限承包方；工程现金一字未动。
            ResearchModel.Report report = ResearchModel.report(world.researchRun().orElseThrow()
                    .state(), world.snapshot().accounts());
            assertEquals(0, report.companyCash().compareTo(new BigDecimal("1100.00")),
                    "2000 − 900 = 1100");
            assertEquals(0, report.expense().compareTo(new BigDecimal("200.00")));
            assertEquals(0, report.workInProgress().compareTo(new BigDecimal("700.00")));
            assertEquals(new BigDecimal("5000.00"),
                    world.snapshot().accounts().get(ENG_A_ACCOUNT).cash(),
                    "工程公司现金不得被研发借用");
            assertEquals(0, world.snapshot().accounts().get("RESEARCH-CONTRACTOR").cash()
                    .compareTo(new BigDecimal("1400.00")), "承包方 500 + 900");

            // 第 1 期周期采样（尚未批准）：在研 700、摊销 0，报表来自研发口径且 A=L+E。
            world.advanceTo(30L * DAY);
            String firstStatement = world.periodicStatementForTest(P, 1);
            assertTrue(firstStatement.contains("独立研发公司期末报表"), firstStatement);
            assertTrue(firstStatement.contains("  研发在研=700.00"), firstStatement);
            assertTrue(firstStatement.contains("  累计摊销=0.00"), firstStatement);
            assertTrue(firstStatement.contains("balanced=true"), firstStatement);
            assertFalse(firstStatement.contains("银行期末报表"), "不得把银行报表套给研发公司");
            String refs = world.periodicBaseRefsForTest(P, 1);
            assertTrue(refs.contains("account:" + CASH), refs);
            assertFalse(refs.contains("account:RSV-NORTH"), "不得借用银行准备金账户：" + refs);
            assertTrue(refs.contains("longterm:research:p1"), refs);

            // 真实商业化：读证据（提案董事与执行经理都必须本人读完）→ 董事会议 → 实施 → 经理执行授权
            readEvidence(world, docsAt, List.of("frank", "grace", "heidi", "ivan"));
            long meetingAt = world.snapshot().timeMillis() + 1_000L;
            runCommercializationMeeting(world, meetingAt, List.of("frank", "grace", "heidi"));
            long approveDue = meetingAt + 5_200L;
            WorldRoleSession manager = world.openRoleSession("ivan");
            manager.approveCommercialization("ph-ap", "ph-ap-task", approveDue, P, "p1",
                    "ph-cm-1", 1_000L);
            world.advanceTo(approveDue);

            ResearchModel.Project p1 = world.researchRun().orElseThrow().state().projects().get("p1");
            assertEquals(ResearchModel.ProjectStatus.APPROVED, p1.status());
            assertNotNull(p1.approvalRef());
            assertEquals(approveDue, p1.approvedAt(), "可用起点=真实 FINISH 时点");

            // 期末：先摊后报，同帧提交；摊销只按累计应摊减已记推进
            world.advanceTo(60L * DAY);
            long days = (60L * DAY - approveDue) / DAY;
            BigDecimal expected = amortizedFor(days);
            assertEquals(0, world.researchRun().orElseThrow().state().projects().get("p1")
                    .amortized().compareTo(expected), "累计摊销=700×整日数/365");
            String statement = world.periodicStatementForTest(P, 2);
            assertTrue(statement.contains("  累计摊销=" + expected.toPlainString()), statement);
            assertTrue(statement.contains("  累计费用=200.00"), statement);
            assertTrue(statement.contains("balanced=true"), statement);
            String refs2 = world.periodicBaseRefsForTest(P, 2);
            assertTrue(refs2.contains("approval:research:pharma:p1"), refs2);

            // God 报表：现金/研究资产/资本/损益一致，A=L+E，不使用余额差补利润
            ComposedCompanyReport report2 = ComposedCompanyReport.of(world, P);
            assertTrue(report2.balanced(), "公司报表必须平衡：" + report2.assets()
                    + " / " + report2.liabilities() + " / " + report2.equity());
            assertEquals(0, report2.assets().get("经营现金").compareTo(new BigDecimal("1100.00")));
            assertEquals(0, report2.assets().get("研发在研资产")
                    .compareTo(new BigDecimal("0.00")));
            assertEquals(0, report2.assets().get("研发可用资产净额").compareTo(
                    new BigDecimal("700.00").subtract(expected)));
            assertEquals(0, report2.equity().get("资本（EXP 历史资本）")
                    .compareTo(new BigDecimal("2000.00")));
            assertEquals(0, report2.profit().compareTo(new BigDecimal("200.00").add(expected)
                    .negate()), "利润=−费用−摊销（没有虚构收入）");
            assertTrue(report2.unsupported().stream()
                    .anyMatch(each -> each.contains("尚无产品销售")), report2.unsupported().toString());
            // 报表与同一期末冻结帧的报表正文数值一致（不是两套口径）
            assertTrue(statement.contains("  累计损益=" + report2.profit().toPlainString()),
                    statement);
        }

        // 恢复：现金/摊销/批准/报表来自真实日志，重开不重算、不重复
        try (WorldRuntime reopened = ComposedPharmaScenario.open(journal)) {
            ResearchModel.Project p1 = reopened.researchRun().orElseThrow().state()
                    .projects().get("p1");
            assertEquals(ResearchModel.ProjectStatus.APPROVED, p1.status());
            assertEquals(0, reopened.snapshot().accounts().get(CASH).cash()
                    .compareTo(new BigDecimal("1100.00")));
            assertEquals(0, reopened.snapshot().accounts().get(ENG_A_ACCOUNT).cash()
                    .compareTo(new BigDecimal("5000.00")));
            long position = reopened.snapshot().position();
            reopened.advanceTo(60L * DAY);
            assertEquals(position, reopened.snapshot().position(), "同一时点不得追加摊销");
            assertTrue(ComposedCompanyReport.of(reopened, P).balanced());
        }
    }

    // ------------------------------------------------------------------ 跨公司拒绝

    @Test
    void crossCompanyGovernanceAndResearchAreRejected() throws Exception {
        try (WorldRuntime world = ComposedPharmaScenario.open(dir.resolve("cross.journal"))) {
            // 研发公司不是工程公司：不能把研发任务排到 eng-a 上
            assertThrows(IllegalArgumentException.class, () -> world.scheduleResearchRegister(
                    "x-reg", "x-reg-task", 1_000L, WorldWebScenario.ENGINE_A, "carol", "p1",
                    1_000L));
            // 也不能把研发排到银行上
            assertThrows(IllegalArgumentException.class, () -> world.scheduleResearchRegister(
                    "x-reg-b", "x-reg-b-task", 1_000L, ComposedWorldScenario.BANK, "alice", "p1",
                    1_000L));
            // 银行没有预算议题入口（保持原显式拒绝），研发公司才有
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleCompanyBoardBudgetProposal("x-bud", "x-bud-task", 1_000L,
                            ComposedWorldScenario.BANK, "alice", "b-1",
                            List.of(new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                    new BigDecimal("100.00")))));
            // 研发公司由真实章程主席召集；north 主席 alice 不是 pharma 董事
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleCompanyBoardConvene("x-con", "x-con-task", 1_000L, P,
                            "alice", "ph-1", 1_200L, 1_300L, 1_400L, false));
            // 研发公司董事不能去给 eng-a 开会
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringConvene("x-con2", "x-con2-task", 1_000L,
                            WorldWebScenario.ENGINE_A, "frank", "ea-1", 1_200L, 1_300L, 1_400L));
        }
    }

    /** 未任命的经理不能发起研发动作（无默认经理）。 */
    @Test
    void unappointedPersonCannotStartResearch() throws Exception {
        try (WorldRuntime world = ComposedPharmaScenario.open(dir.resolve("no-mgr.journal"))) {
            assertThrows(IllegalArgumentException.class, () -> world.scheduleResearchRegister(
                    "n-reg", "n-reg-task", 2_000L, P, "kate", "p1", 1_000L));
        }
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 真实董事预算会议 + 真实经理任免会议；预算 10,000、任命 kate（90 天）。
     *
     * @param world 世界
     * @throws Exception 排程或推进失败
     */
    static void grantBudgetAndAppointManager(WorldRuntime world) throws Exception {
        grantBudgetAndAppointManager(world, "ivan");
    }

    static void grantBudgetAndAppointManager(WorldRuntime world, String manager) throws Exception {
        world.schedulePublishDocument("ph-pub-budget", "ph-pub-budget-task", 10L, "ph-budget-doc",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "研发预算实验材料", "研发预算实验材料", 10L, List.of());
        world.schedulePublishDocument("ph-pub-appt", "ph-pub-appt-task", 10L, "ph-appt-doc",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "研发任免实验材料", "研发任免实验材料", 10L, List.of());
        List<String> voters = List.of("frank", "grace", "heidi", "ivan");
        int index = 0;
        for (String reader : List.of("frank", "grace", "heidi", "ivan", "judy", "kate")) {
            world.scheduleStartReading("pr-b-" + reader, "pr-b-" + reader + "-task",
                    20L + index * 2L, reader, "ph-budget-doc", 1L);
            world.scheduleStartReading("pr-a-" + reader, "pr-a-" + reader + "-task",
                    60L + index * 2L, reader, "ph-appt-doc", 1L);
            index++;
        }
        world.scheduleCompanyBoardConvene("ph-budget-c", "ph-budget-c-task", 100L, P, "frank",
                "ph-budget-meeting", 150L, 200L, 4_000L, false);
        for (int i = 0; i < voters.size(); i++) {
            world.scheduleCompanyBoardAttend("ph-budget-a-" + i, "ph-budget-a-" + i + "-task",
                    110L + i, P, voters.get(i), "ph-budget-meeting");
        }
        world.scheduleCompanyBoardMaterial("ph-budget-m", "ph-budget-m-task", 130L, P, "frank",
                "ph-budget-meeting", "m1", "ph-budget-doc", 1L);
        world.scheduleCompanyBoardBudgetProposal("ph-budget-p", "ph-budget-p-task", 140L, P,
                "frank", "ph-budget-meeting", List.of(new BoardModel.BudgetLine(0,
                        BoardModel.BudgetCategory.PROCUREMENT, new BigDecimal("10000.00"))));
        for (int i = 0; i < voters.size(); i++) {
            world.scheduleCompanyBoardVote("ph-budget-v-" + i, "ph-budget-v-" + i + "-task",
                    3_000L + i, P, voters.get(i), "ph-budget-meeting", BoardModel.Vote.YES, 1_000L);
        }
        world.scheduleCompanyBoardImplement("ph-budget-i", "ph-budget-i-task", 5_000L, P, "frank",
                "ph-budget-meeting");

        world.scheduleCompanyBoardConvene("ph-appt-c", "ph-appt-c-task", 6_000L, P, "frank",
                "ph-appt-meeting", 6_500L, 7_000L, 10_000L, true);
        for (int i = 0; i < voters.size(); i++) {
            // 被任免人本人是董事时必须在到会时登记回避（与工程公司同一语义）。
            world.scheduleCompanyBoardAttend("ph-appt-a-" + i, "ph-appt-a-" + i + "-task",
                    6_100L + i, P, voters.get(i), "ph-appt-meeting",
                    manager.equals(voters.get(i)));
        }
        world.scheduleCompanyBoardMaterial("ph-appt-m", "ph-appt-m-task", 6_300L, P, "frank",
                "ph-appt-meeting", "m1", "ph-appt-doc", 1L);
        world.scheduleCompanyBoardAppointmentProposal("ph-appt-p", "ph-appt-p-task", 6_400L, P,
                "frank", "ph-appt-meeting", new BoardModel.AppointmentRequest("mgr-" + manager, manager,
                        P, edu.cufe.auction.world.RoleAccess.Role.COMPANY_MANAGER,
                        BoardModel.AppointmentAction.APPOINT, 12_000L, 90L * DAY));
        for (int i = 0; i < voters.size(); i++) {
            if (manager.equals(voters.get(i))) {
                continue;
            }
            world.scheduleCompanyBoardVote("ph-appt-v-" + i, "ph-appt-v-" + i + "-task",
                    9_000L + i, P, voters.get(i), "ph-appt-meeting", BoardModel.Vote.YES, 1_000L);
        }
        world.scheduleCompanyBoardImplement("ph-appt-i", "ph-appt-i-task", 11_000L, P, "frank",
                "ph-appt-meeting");
        world.advanceTo(12_000L);
    }

    /**
     * 真实经理完成 p1 三阶段（立项 → 三阶段开工/结果）。
     *
     * @param world 世界
     * @return 最后一份结果证据的发布时间
     * @throws Exception 排程或推进失败
     */
    static long completeP1(WorldRuntime world) throws Exception {
        world.scheduleResearchRegister("ph-reg-p1", "ph-reg-p1-task", 13_001L, P, "ivan", "p1",
                1_000L);
        world.advanceTo(13_001L);
        for (int i = 0; i < 3; i++) {
            ResearchModel.Stage stage = ResearchModel.Stage.values()[i];
            long finish = world.snapshot().timeMillis() + 2_000L;
            world.scheduleResearchBegin("ph-b-" + i, "ph-b-task-" + i, finish, P, "ivan", "p1",
                    stage, 1_000L);
            world.advanceTo(finish);
            world.advanceTo(finish + DAY);
        }
        assertEquals(ResearchModel.ProjectStatus.COMPLETED, world.researchRun().orElseThrow()
                .state().projects().get("p1").status());
        return world.snapshot().timeMillis();
    }

    static void readEvidence(WorldRuntime world, long docsAt, List<String> readers)
            throws Exception {
        long start = Math.max(docsAt + 1_000L, world.snapshot().timeMillis() + 1_000L);
        for (String reader : readers) {
            for (int i = 0; i < 3; i++) {
                world.scheduleStartReading("ph-rd-" + reader + "-" + i,
                        "ph-rd-task-" + reader + "-" + i, start + 2_000L + i * 1_100L, reader,
                        evidenceDoc(i), 1L);
            }
        }
        world.advanceTo(start + 2_000L + 3L * 1_100L + 500L);
    }

    static String evidenceDoc(int index) {
        return "research:pharma:p1:" + ResearchModel.Stage.values()[index].name() + ":SUCCESS";
    }

    /** 真实商业化会议：召集 → 到会 → 三份证据材料 → 提案 → 三票 → 实施。 */
    static void runCommercializationMeeting(WorldRuntime world, long at, List<String> attendees)
            throws Exception {
        world.scheduleCommercializationConvene("ph-cv", "ph-cv-task", at, P, "frank", "ph-cm-1",
                at + 2_500L, at + 2_700L, at + 3_500L);
        for (int i = 0; i < attendees.size(); i++) {
            world.scheduleEngineeringAttend("ph-at-" + i, "ph-at-task-" + i, at + 50L + i, P,
                    attendees.get(i), "ph-cm-1", false);
        }
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringMaterial("ph-mat-" + i, "ph-mat-task-" + i, at + 100L + i,
                    P, "frank", "ph-cm-1", "m" + (i + 1), evidenceDoc(i), 1L);
        }
        List<BoardModel.MaterialRef> materials = List.of(
                new BoardModel.MaterialRef("m1", evidenceDoc(0), 1L, at),
                new BoardModel.MaterialRef("m2", evidenceDoc(1), 1L, at),
                new BoardModel.MaterialRef("m3", evidenceDoc(2), 1L, at));
        world.scheduleCommercializationProposal("ph-pp", "ph-pp-task", at + 2_000L, P, "frank",
                "ph-cm-1", List.of(),
                new BoardModel.CommercializationRequest(P, "p1", materials), 1_000L);
        for (int i = 0; i < attendees.size(); i++) {
            world.scheduleEngineeringVote("ph-v-" + i, "ph-v-task-" + i, at + 3_000L + i, P,
                    attendees.get(i), "ph-cm-1", BoardModel.Vote.YES, 1_000L);
        }
        world.scheduleCommercializationImplement("ph-im", "ph-im-task", at + 3_600L, P, "frank",
                "ph-cm-1");
        world.advanceTo(at + 3_600L);
    }

    /**
     * 建好「真实治理 → 三阶段 → 商业化批准 → 期末摊销」的完整链，供篡改/故障/跨进程测试复用。
     *
     * @param journal 目标日志
     * @throws Exception 排程或推进失败
     */
    static void buildApprovedChain(Path journal) throws Exception {
        try (WorldRuntime world = ComposedPharmaScenario.open(journal)) {
            grantBudgetAndAppointManager(world);
            long docsAt = completeP1(world);
            world.advanceTo(30L * DAY);
            readEvidence(world, docsAt, List.of("frank", "grace", "heidi", "ivan"));
            long meetingAt = world.snapshot().timeMillis() + 1_000L;
            runCommercializationMeeting(world, meetingAt, List.of("frank", "grace", "heidi"));
            long approveDue = meetingAt + 5_200L;
            world.openRoleSession("ivan").approveCommercialization("ph-ap", "ph-ap-task",
                    approveDue, P, "p1", "ph-cm-1", 1_000L);
            world.advanceTo(approveDue);
            world.advanceTo(60L * DAY);
            assertEquals(ResearchModel.ProjectStatus.APPROVED, world.researchRun().orElseThrow()
                    .state().projects().get("p1").status());
        }
    }

    static BigDecimal amortizedFor(long days) {
        BigDecimal cost = new BigDecimal("700.00");
        BigDecimal target = cost.multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
        return target.min(cost);
    }

    /** 供其它测试复用：当前研发公司现金。 */
    static BigDecimal cash(WorldRuntime world) {
        return world.snapshot().accounts().get(CASH).cash();
    }

    /** 供其它测试复用：世界摘要（逐分量比较用）。 */
    static Map<String, String> summary(WorldRuntime world) throws Exception {
        return FundedProbes.components(world);
    }

    /** 供其它测试复用：当前世界状态规范摘要。 */
    static String stateDigest(WorldRuntime world) throws Exception {
        return FundedProbes.stateDigest(world);
    }
}
