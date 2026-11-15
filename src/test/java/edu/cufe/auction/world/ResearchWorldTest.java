package edu.cufe.auction.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** schema22 研发世界首切片：真实预算/任免、三阶段/失败、容量、报告不双计、重开。 */
class ResearchWorldTest {

    private static final String A = WorldWebScenario.ENGINE_A;
    private static final long DAY = 86_400_000L;
    private static final long MANAGER_END = 30L * DAY;

    @TempDir
    Path dir;

    @Test
    void p1SuccessThenP7StageThreeFailureThenEarlyFailures() throws Exception {
        Path file = dir.resolve("research.journal");
        WorldRuntime world = prepared(file);
        long t = 1_001L;

        t = chain(world, "p1", 3, t);
        ResearchModel.Project p1 = project(world, "p1");
        assertEquals(ResearchModel.ProjectStatus.COMPLETED, p1.status());
        for (ResearchModel.StageRecord stage : p1.stages()) {
            assertEquals(ResearchModel.StageStatus.COMPLETED, stage.status());
        }
        ResearchModel.Report afterP1 = report(world, "eng-a", "4100.00", "1400.00");
        assertEquals(0, afterP1.paid().compareTo(new BigDecimal("900.00")));
        assertEquals(0, afterP1.workInProgress().compareTo(new BigDecimal("700.00")));
        assertEquals(0, afterP1.expense().compareTo(new BigDecimal("200.00")));
        assertEquals(0, afterP1.impairment().compareTo(BigDecimal.ZERO));
        assertEquals(0, afterP1.equity().compareTo(new BigDecimal("4800.00")));
        assertTrue(afterP1.balanced());

        t = chain(world, "p7", 3, t + 1L);
        ResearchModel.Project p7 = project(world, "p7");
        assertEquals(ResearchModel.ProjectStatus.FAILED, p7.status());
        assertEquals(ResearchModel.StageStatus.COMPLETED, p7.stages().get(0).status());
        assertEquals(ResearchModel.StageStatus.COMPLETED, p7.stages().get(1).status());
        assertEquals(ResearchModel.StageStatus.FAILED, p7.stages().get(2).status());
        ResearchModel.Report afterP7 = report(world, "eng-a", "3200.00", "2300.00");
        assertEquals(0, afterP7.paid().compareTo(new BigDecimal("1800.00")));
        assertEquals(0, afterP7.workInProgress().compareTo(new BigDecimal("700.00")));
        assertEquals(0, afterP7.expense().compareTo(new BigDecimal("400.00")));
        assertEquals(0, afterP7.impairment().compareTo(new BigDecimal("700.00")));
        assertEquals(0, afterP7.equity().compareTo(new BigDecimal("3900.00")));
        assertTrue(afterP7.balanced());

        t = chain(world, "p2", 2, t + 1L);
        ResearchModel.Project p2 = project(world, "p2");
        assertEquals(ResearchModel.ProjectStatus.FAILED, p2.status());
        assertEquals(ResearchModel.StageStatus.COMPLETED, p2.stages().get(0).status());
        assertEquals(ResearchModel.StageStatus.FAILED, p2.stages().get(1).status());
        assertEquals(ResearchModel.StageStatus.PLANNED, p2.stages().get(2).status());
        ResearchModel.Report afterP2 = report(world, "eng-a", "2900.00", "2600.00");
        assertEquals(0, afterP2.impairment().compareTo(new BigDecimal("900.00")));
        assertEquals(0, afterP2.equity().compareTo(new BigDecimal("3600.00")));

        t = chain(world, "p10", 1, t + 1L);
        ResearchModel.Project p10 = project(world, "p10");
        assertEquals(ResearchModel.ProjectStatus.FAILED, p10.status());
        assertEquals(ResearchModel.StageStatus.FAILED, p10.stages().get(0).status());
        assertEquals(ResearchModel.StageStatus.PLANNED, p10.stages().get(1).status());
        ResearchModel.Report afterP10 = report(world, "eng-a", "2800.00", "2700.00");
        assertEquals(0, afterP10.paid().compareTo(new BigDecimal("2200.00")));
        assertEquals(0, afterP10.expense().compareTo(new BigDecimal("600.00")));
        assertEquals(0, afterP10.impairment().compareTo(new BigDecimal("900.00")));
        assertEquals(0, afterP10.equity().compareTo(new BigDecimal("3500.00")));
        assertTrue(afterP10.balanced());

        // 公司报表：工程权益 - 研发费用/减值/摊销 = 公司权益；绝不把两份 equity/initialEquity 相加。
        EngineeringModel.State engState = world.engineeringStateOf("eng-a").orElseThrow();
        Map<String, CashLedger.CashAccount> accounts = accounts(world, "2800.00", "2700.00");
        EngineeringModel.Report engReport = EngineeringModel.report(engState, accounts);
        BigDecimal companyEquity = engReport.equity().subtract(afterP10.expense())
                .subtract(afterP10.impairment()).subtract(afterP10.accumulatedAmortization());
        assertEquals(0, companyEquity.compareTo(new BigDecimal("3500.00")));
        assertEquals(0, companyEquity.compareTo(afterP10.equity()));
        assertFalse(companyEquity.compareTo(engReport.equity().add(afterP10.equity())) == 0,
                "不得把工程权益与研发权益相加");

        // 重开：真实新进程/新实例从 journal 恢复，研发状态与结果不重抽。
        world.close();
        WorldRuntime reopened = WorldWebScenario.openResearch(file).runtime();
        ResearchModel.Project reopenedP1 = project(reopened, "p1");
        assertEquals(ResearchModel.ProjectStatus.COMPLETED, reopenedP1.status());
        assertEquals(ResearchModel.ProjectStatus.FAILED, project(reopened, "p7").status());
        reopened.close();
    }

    @Test
    void capacityAndManagerAreEnforced() throws Exception {
        WorldRuntime world = prepared(dir.resolve("capacity.journal"));
        world.scheduleResearchRegister("reg-p1", "reg-p1-task", 3_000L, A, "dave", "p1", 1_000L);
        world.advanceTo(3_000L);
        world.scheduleResearchBegin("b-p1", "b-p1-task", 5_000L, A, "dave", "p1",
                ResearchModel.Stage.RESEARCH, 1_000L);
        world.advanceTo(5_000L);
        assertEquals(1, world.researchRun().orElseThrow().activeStages());

        // 非经理不能发起。
        assertThrows(IllegalArgumentException.class, () -> world.scheduleResearchRegister(
                "reg-alice", "reg-alice-task", 7_000L, A, "alice", "p7", 1_000L));

        // 真实容量 1：p7 在 p1 在途时开工被拒，阶段保持 PLANNED。
        world.scheduleResearchRegister("reg-p7", "reg-p7-task", 7_000L, A, "dave", "p7", 1_000L);
        world.advanceTo(7_000L);
        world.scheduleResearchBegin("b-p7", "b-p7-task", 9_000L, A, "dave", "p7",
                ResearchModel.Stage.RESEARCH, 1_000L);
        world.advanceTo(9_000L);
        ResearchModel.Project p7 = project(world, "p7");
        assertEquals(ResearchModel.StageStatus.PLANNED, p7.stages().get(0).status());
        world.close();
    }

    private static ResearchModel.Project project(WorldRuntime world, String projectId) {
        return world.researchRun().orElseThrow().state().projects().get(projectId);
    }

    private static ResearchModel.Report report(WorldRuntime world, String company, String engA,
            String contractor) {
        ResearchRun run = world.researchRun().orElseThrow();
        return ResearchModel.report(run.state(), accounts(run, engA, contractor));
    }

    private static Map<String, CashLedger.CashAccount> accounts(WorldRuntime world, String engA,
            String contractor) {
        return accounts(world.researchRun().orElseThrow(), engA, contractor);
    }

    private static Map<String, CashLedger.CashAccount> accounts(ResearchRun run, String engA,
            String contractor) {
        Map<String, CashLedger.CashAccount> accounts = new TreeMap<>();
        for (CashLedger.OpeningAccount opening : WorldWebScenario.researchOpenings()) {
            accounts.put(opening.accountId(), new CashLedger.CashAccount(opening.ownerId(),
                    opening.cash(), opening.source()));
        }
        accounts.put("ENG-A", new CashLedger.CashAccount("eng-a", new BigDecimal(engA), "工程A"));
        accounts.put(WorldWebScenario.RESEARCH_CONTRACTOR, new CashLedger.CashAccount(
                "research-contractor", new BigDecimal(contractor), "研发承包方"));
        return accounts;
    }

    private static long chain(WorldRuntime world, String projectId, int stages, long start)
            throws Exception {
        long t = start;
        long finish = t + 2_000L;
        world.scheduleResearchRegister("reg:" + projectId, "reg-task:" + projectId, finish, A,
                "dave", projectId, 1_000L);
        t = finish;
        world.advanceTo(t);
        for (int i = 0; i < stages; i++) {
            ResearchModel.Stage stage = ResearchModel.Stage.values()[i];
            finish = t + 2_000L;
            world.scheduleResearchBegin("b:" + projectId + ":" + i,
                    "b-task:" + projectId + ":" + i, finish, A, "dave", projectId, stage,
                    1_000L);
            t = finish;
            world.advanceTo(t);
            t += DAY;
            world.advanceTo(t);
        }
        return t;
    }

    private static WorldRuntime prepared(Path file) throws Exception {
        return prepared(file, "10000.00", false);
    }

    private static WorldRuntime prepared(Path file, String procurement) throws Exception {
        return prepared(file, procurement, false);
    }

    /** 真实默认新档：预算会议 + 任免 dave；不预塞预算/经理。 */
    private static WorldRuntime prepared(Path file, String procurement, boolean noBudget)
            throws Exception {
        WorldRuntime world = WorldWebScenario.openResearch(file).runtime();
        world.schedulePublishDocument("pub-material", "pub-material-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "材料", "实验材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        List<String> voters = List.of("alice", "bob", "carol", "dave", "erin");
        int index = 0;
        for (String reader : voters) {
            world.scheduleStartReading("read-mat-" + reader, "read-mat-" + reader + "-task",
                    20L + index * 2L, reader, "b-material", 1L);
            world.scheduleStartReading("read-appt-" + reader, "read-appt-" + reader + "-task",
                    60L + index * 2L, reader, "appt-material", 1L);
            index++;
        }
        world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L, A, "alice",
                "budget-meeting", 150L, 200L, 300L);
        for (int i = 0; i < 5; i++) {
            world.scheduleEngineeringAttend("budget-a-" + i, "budget-a-" + i + "-task",
                    110L + i, A, voters.get(i), "budget-meeting", false);
        }
        world.scheduleEngineeringMaterial("budget-m", "budget-m-task", 130L, A, "alice",
                "budget-meeting", "m1", "b-material", 1L);
        world.scheduleEngineeringBudgetProposal("budget-p", "budget-p-task", 140L, A, "alice",
                "budget-meeting", noBudget ? List.of() : List.of(
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                new BigDecimal(procurement)),
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                new BigDecimal(procurement))));
        for (int i = 0; i < 4; i++) {
            world.scheduleEngineeringVote("budget-v-" + i, "budget-v-" + i + "-task",
                    220L + i, A, voters.get(i), "budget-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("budget-i", "budget-i-task", 320L, A, "alice",
                "budget-meeting");
        world.scheduleEngineeringAppointmentConvene("appt-c", "appt-c-task", 400L, A, "alice",
                "appt-meeting", 450L, 500L, 600L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("appt-a-" + i, "appt-a-" + i + "-task", 410L + i, A,
                    voters.get(i), "appt-meeting", false);
        }
        world.scheduleEngineeringMaterial("appt-m", "appt-m-task", 430L, A, "alice",
                "appt-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("appt-p", "appt-p-task", 440L, A, "alice",
                "appt-meeting", List.of(),
                new BoardModel.AppointmentRequest("mgr-a", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        700L, MANAGER_END));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("appt-v-" + i, "appt-v-" + i + "-task", 520L + i, A,
                    voters.get(i), "appt-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("appt-i", "appt-i-task", 620L, A, "alice",
                "appt-meeting");
        world.advanceTo(1_000L);
        assertTrue(world.activeManagerForTest("dave", A), "夹具必须先真实任命经理");
        return world;
    }

    // ------------------------------------------------------------ 负例：预算不足/重复立项/到期撤权/不造水账

    @Test
    void budgetInsufficientDuplicateExpiryAndNoWaterAccounts() throws Exception {
        WorldRuntime low = prepared(dir.resolve("low-budget.journal"), "500.00");
        low.scheduleResearchRegister("low-reg", "low-reg-task", 3_001L, A, "dave", "p1", 1_000L);
        low.advanceTo(3_001L);
        assertFalse(low.researchRun().orElseThrow().state().projects().containsKey("p1"),
                "预算 500 不得批准预算 900 的项目");
        low.close();

        WorldRuntime world = prepared(dir.resolve("dup-expiry.journal"));
        world.scheduleResearchRegister("dup-1", "dup-1-task", 3_001L, A, "dave", "p1", 1_000L);
        world.advanceTo(3_001L);
        world.scheduleResearchRegister("dup-2", "dup-2-task", 5_001L, A, "dave", "p1", 1_000L);
        world.advanceTo(5_001L);
        assertEquals(1, world.researchRun().orElseThrow().state().projects().size(),
                "重复项目标识不得登记两次");
        assertTrue(project(world, "p1") != null);

        world.advanceTo(30L * DAY + 1L);
        assertThrows(IllegalArgumentException.class, () -> world.scheduleResearchBegin(
                "expired-begin", "expired-begin-task", 30L * DAY + 2_000L, A, "dave", "p2",
                ResearchModel.Stage.RESEARCH, 1_000L), "经理到期后不得再开工");

        assertTrue(WorldWebScenario.researchOpenings().stream()
                .noneMatch(opening -> opening.accountId().contains("WATER")
                        || opening.accountId().contains("BANK")), "研发档不得造水/银行账户");
        world.close();
    }

    // ------------------------------------------------------------ 真实共享预算 / 会话任职 / 同ID重放

    @Test
    void budgetIsSharedAcrossProjectsAndEngineeringProcurement() throws Exception {
        WorldRuntime world = prepared(dir.resolve("shared-budget.journal"), "1000.00");
        world.scheduleStartReading("read-opp", "read-opp-task", 1_100L, "dave",
                "engineering-opportunity:A1", 1L);
        world.advanceTo(1_200L);
        world.scheduleResearchRegister("p1-reg", "p1-reg-task", 9_001L, A, "dave", "p1", 1_000L);
        world.advanceTo(9_001L);
        assertTrue(project(world, "p1") != null);
        assertTrue(world.researchBudgetForTest("eng-a").commitments().stream()
                .anyMatch(c -> c.commitmentId().equals("research:p1")
                        && c.outstanding().compareTo(new BigDecimal("900.00")) == 0));

        // 第二项目 600 复用同 1000 上限：只剩 100，必须拒。
        world.scheduleResearchRegister("p2-reg", "p2-reg-task", 11_002L, A, "dave", "p2",
                1_000L);
        world.scheduleEngineeringAction("sign-a1", "sign-a1-task", 13_001L, A, "dave", "SIGN",
                "A1", "", 0, 0, BigDecimal.ZERO, "", 0, 1_000L);
        world.advanceTo(11_002L);
        assertFalse(world.researchRun().orElseThrow().state().projects().containsKey("p2"),
                "第二项目不得复用已承诺的采购上限");

        // 工程采购与研发共享 PROCUREMENT：剩余 100，START_WORK 的 200 分包必须拒。
        world.scheduleEngineeringAction("start-a1", "start-a1-task", 15_001L, A, "dave",
                "START_WORK", "A1", "w1", 1, 10, BigDecimal.ZERO, "", 0, 1_000L);
        world.advanceTo(15_001L);
        assertTrue(world.engineeringStateOf("eng-a").orElseThrow().workOrders().isEmpty(),
                "工程采购不得复用已被研发占用的采购预算");
        world.close();
    }

    @Test
    void registerNeedsRealBudgetLineAndRemaining() throws Exception {
        WorldRuntime noBudget = prepared(dir.resolve("no-budget.journal"), "10000.00", true);
        noBudget.scheduleResearchRegister("reg", "reg-task", 3_001L, A, "dave", "p1", 1_000L);
        noBudget.advanceTo(3_001L);
        assertFalse(noBudget.researchRun().orElseThrow().state().projects().containsKey("p1"));
        assertTrue(noBudget.researchBudgetForTest("eng-a")
                .budget(0, BoardModel.BudgetCategory.PROCUREMENT) == null);
        noBudget.close();

        WorldRuntime low = prepared(dir.resolve("remaining.journal"), "100.00");
        low.scheduleResearchRegister("reg", "reg-task", 3_001L, A, "dave", "p1", 1_000L);
        low.advanceTo(3_001L);
        assertFalse(low.researchRun().orElseThrow().state().projects().containsKey("p1"));
        low.close();
    }

    @Test
    void sameCommandAfterCompletionIsIdempotentButChangedScheduleRejected() throws Exception {
        WorldRuntime world = prepared(dir.resolve("order.journal"));
        String first = world.scheduleResearchRegister("order-reg", "order-reg-task", 3_001L, A,
                "dave", "p1", 1_000L);
        world.advanceTo(3_001L);
        assertEquals(first, world.scheduleResearchRegister("order-reg", "order-reg-task", 3_001L,
                A, "dave", "p1", 1_000L));
        assertThrows(IllegalArgumentException.class, () -> world.scheduleResearchRegister(
                "order-reg", "order-reg-task", 3_001L, A, "dave", "p1", 2_000L));
        assertThrows(IllegalArgumentException.class, () -> world.scheduleResearchRegister(
                "order-reg", "order-reg-task", 4_001L, A, "dave", "p1", 1_000L));
        world.close();
    }

    @Test
    void sessionBindsObservedAppointmentAndRevocationInvalidatesOldTask() throws Exception {
        WorldRuntime world = prepared(dir.resolve("session.journal"));
        WorldRoleSession oldSession = world.openRoleSession("dave");
        oldSession.registerResearch("s-p1", "s-p1-task", 3_001L, A, "p1", 1_000L);
        oldSession.registerResearch("s-p7", "s-p7-task", 3_001L, A, "p7", 1_000L);
        world.advanceTo(3_001L);
        assertTrue(project(world, "p1") != null);
        assertTrue(project(world, "p7") != null);
        // 20 天时撤任并重任命：撤任必须落在旧任职有效期内（旧任职预定 30 天到期）。
        long base = 20L * DAY;
        oldSession.beginResearch("old-b7", "old-b7-task", base + 2_000L, A, "p7",
                ResearchModel.Stage.RESEARCH, 1_000L);

        revokeAndReappoint(world, "mgr-a", "mgr-b", base);
        world.advanceTo(base + 1_500L);
        WorldRoleSession newSession = world.openRoleSession("dave");
        assertEquals("mgr-a", oldSession.appointments().get(A),
                "旧会话冻结的是观察时任职，不随后续任命改写");
        assertEquals("mgr-b", newSession.appointments().get(A),
                "重任命后新会话观察到的是新任职");
        newSession.beginResearch("new-b7", "new-b7-task", base + 4_000L, A, "p7",
                ResearchModel.Stage.RESEARCH, 1_000L);
        world.advanceTo(base + 2_000L);
        assertEquals(ResearchModel.StageStatus.PLANNED, project(world, "p7").stages().get(0)
                .status(), "旧任职在 BEGIN 失效后旧任务必须拒");
        world.advanceTo(base + 4_000L);
        assertEquals(ResearchModel.StageStatus.IN_PROGRESS, project(world, "p7").stages().get(0)
                .status(), "新任职的真实会话必须能开工");
        world.close();
    }

    private static void revokeAndReappoint(WorldRuntime world, String oldId, String newId,
            long base) throws Exception {
        List<String> voters = List.of("alice", "bob", "carol");
        // 撤任：closesAt=base+500，实施在 base+520（严格晚于表决截止），生效时刻同为 base+520。
        world.scheduleEngineeringAppointmentConvene("rev-c", "rev-c-task", base, A, "alice",
                "rev-meeting", base + 50, base + 100, base + 500);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("rev-a-" + i, "rev-a-" + i + "-task", base + 10 + i,
                    A, voters.get(i), "rev-meeting", false);
        }
        world.scheduleEngineeringMaterial("rev-m", "rev-m-task", base + 30, A, "alice",
                "rev-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("rev-p", "rev-p-task", base + 40, A,
                "alice", "rev-meeting", List.of(),
                new BoardModel.AppointmentRequest(oldId, "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.REVOKE,
                        base + 520, null));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("rev-v-" + i, "rev-v-" + i + "-task", base + 200 + i,
                    A, voters.get(i), "rev-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("rev-i", "rev-i-task", base + 520, A, "alice",
                "rev-meeting");

        // 重任命：closesAt=base+1100，实施在 base+1120，任职自 base+1200 生效。
        world.scheduleEngineeringAppointmentConvene("app2-c", "app2-c-task", base + 600, A,
                "alice", "app2-meeting", base + 650, base + 700, base + 1_100);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("app2-a-" + i, "app2-a-" + i + "-task",
                    base + 610 + i, A, voters.get(i), "app2-meeting", false);
        }
        world.scheduleEngineeringMaterial("app2-m", "app2-m-task", base + 630, A, "alice",
                "app2-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("app2-p", "app2-p-task", base + 640, A,
                "alice", "app2-meeting", List.of(),
                new BoardModel.AppointmentRequest(newId, "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        base + 1_200, 60L * DAY));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("app2-v-" + i, "app2-v-" + i + "-task", base + 800 + i,
                    A, voters.get(i), "app2-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("app2-i", "app2-i-task", base + 1_120, A, "alice",
                "app2-meeting");
    }

    // ------------------------------------------------------------ 期末真实长期投入证据

    @Test
    void periodicLongTermEvidence() throws Exception {
        WorldRuntime world = prepared(dir.resolve("periodic.journal"));
        chain(world, "p1", 3, 1_001L);
        world.advanceTo(30L * DAY + 1L);
        String statement = world.periodicStatementForTest("eng-a", 1);
        assertTrue(statement.contains("研发在研=700.00"), statement);
        assertTrue(statement.contains("研发费用=200.00"), statement);
        String refs = world.periodicBaseRefsForTest("eng-a", 1);
        assertTrue(refs.contains("longterm:research:p1"), refs);
        world.close();
    }

    // ------------------------------------------------------------ 自洽篡改（重签 SHA）

    @Test
    void tamperLastSnapshotRejected() throws Exception {
        Path base = dir.resolve("tamper-base.journal");
        WorldRuntime world = prepared(base);
        chain(world, "p1", 3, 1_001L);
        world.close();
        WorldRuntime control = WorldWebScenario.openResearch(base).runtime();
        assertEquals(ResearchModel.ProjectStatus.COMPLETED, project(control, "p1").status());
        control.close();

        Path status = dir.resolve("tamper-status.journal");
        tamper(base, status, (snapshot, mapper) -> {
            JsonNode stage = snapshot.path("research").path("state").path("projects").path("p1")
                    .path("stages").get(2);
            if (stage == null || !stage.isObject()) {
                return false;
            }
            ((ObjectNode) stage).put("status", "FAILED");
            return true;
        });
        assertThrows(Exception.class, () -> WorldWebScenario.openResearch(status));

        Path cash = dir.resolve("tamper-cash.journal");
        tamper(base, cash, (snapshot, mapper) -> {
            JsonNode account = snapshot.path("accounts").get("ENG-A");
            if (account == null || !account.isObject()) {
                return false;
            }
            ((ObjectNode) account).put("cash", 9999.99);
            return true;
        });
        assertThrows(Exception.class, () -> WorldWebScenario.openResearch(cash));

        Path pending = dir.resolve("tamper-pending.journal");
        tamper(base, pending, (snapshot, mapper) -> {
            JsonNode tasks = snapshot.get("pending");
            if (!(tasks instanceof ArrayNode array) || array.isEmpty()) {
                return false;
            }
            array.remove(0);
            return true;
        });
        assertThrows(Exception.class, () -> WorldWebScenario.openResearch(pending));

        Path budget = dir.resolve("tamper-engineering.journal");
        tamper(base, budget, (snapshot, mapper) -> {
            JsonNode company = snapshot.path("engineering").path("companies").get("eng-a");
            if (company == null || !company.isObject()) {
                return false;
            }
            ((ObjectNode) company).put("initialEquity", 7777.77);
            return true;
        });
        assertThrows(Exception.class, () -> WorldWebScenario.openResearch(budget));

        Path evidence = dir.resolve("tamper-evidence.journal");
        tamper(base, evidence, (snapshot, mapper) -> {
            JsonNode documents = snapshot.path("information").path("information").path("documents");
            if (!(documents instanceof ArrayNode array)) {
                return false;
            }
            for (JsonNode document : array) {
                if (!document.path("docId").asText().startsWith("research:")) {
                    continue;
                }
                JsonNode versions = document.get("versions");
                if (!(versions instanceof ArrayNode list) || list.isEmpty()) {
                    continue;
                }
                ((ObjectNode) list.get(0)).put("body", "被篡改的正文");
                return true;
            }
            return false;
        });
        assertThrows(Exception.class, () -> WorldWebScenario.openResearch(evidence));
    }

    // ------------------------------------------------------------ 跨 JVM 续跑（3 个 JVM）

    @Test
    void threeJvmContinuation() throws Exception {
        Path file = dir.resolve("joint.journal");
        WorldRuntime base = prepared(file);
        long finish = 1_001L + 2_000L;
        base.scheduleResearchRegister("joint-reg", "joint-reg-task", finish, A, "dave", "p1",
                1_000L);
        base.advanceTo(finish);
        finish += 2_000L;
        base.scheduleResearchBegin("joint-b0", "joint-b0-task", finish, A, "dave", "p1",
                ResearchModel.Stage.RESEARCH, 1_000L);
        base.advanceTo(finish);
        base.close();

        String classpath = System.getProperty("java.class.path");
        String javaBin = System.getProperty("java.home") + "/bin/java";
        runProbe(javaBin, classpath, file, 2);
        runProbe(javaBin, classpath, file, 3);

        WorldRuntime reopened = WorldWebScenario.openResearch(file).runtime();
        assertEquals(ResearchModel.ProjectStatus.COMPLETED, project(reopened, "p1").status());
        reopened.close();
    }

    private static void runProbe(String javaBin, String classpath, Path file, int stages)
            throws Exception {
        Process process = new ProcessBuilder(javaBin, "-cp", classpath,
                "edu.cufe.auction.world.ResearchJointProbe", file.toString(),
                String.valueOf(stages)).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        System.out.println("PROBE stages=" + stages + " exit=" + exit + " out=" + output.trim());
        assertEquals(0, exit, "跨 JVM 续跑失败：" + output);
        assertTrue(output.contains("P1="));
    }

    private interface SnapshotMutation {
        boolean apply(ObjectNode snapshot, ObjectMapper mapper) throws Exception;
    }

    private static void tamper(Path source, Path target, SnapshotMutation mutation) throws Exception {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        List<byte[]> frames = readFrames(target);
        int index = frames.size() - 1;
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode batch = (ObjectNode) mapper.readTree(frames.get(index));
        ArrayNode events = (ArrayNode) batch.get("events");
        boolean touched = false;
        for (JsonNode event : events) {
            if (!"STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                continue;
            }
            ObjectNode snapshot = (ObjectNode) mapper.readTree(event.get("payload").asText());
            if (!mutation.apply(snapshot, mapper)) {
                continue;
            }
            ((ObjectNode) event).put("payload", mapper.writeValueAsString(snapshot));
            touched = true;
        }
        if (!touched) {
            throw new IllegalArgumentException("该帧没有可篡改的快照内容");
        }
        frames.set(index, mapper.writeValueAsBytes(batch));
        Files.write(target, frameBytes(frames));
    }

    private static List<byte[]> readFrames(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            int length = ByteBuffer.wrap(bytes, offset, 4).getInt();
            int inverse = ByteBuffer.wrap(bytes, offset + 4, 4).getInt();
            if (length <= 0 || inverse != ~length || offset + 8 + length + 32 > bytes.length) {
                throw new IllegalStateException("帧格式不合法：" + offset);
            }
            frames.add(Arrays.copyOfRange(bytes, offset + 8, offset + 8 + length));
            offset += 8 + length + 32;
        }
        return frames;
    }

    private static byte[] frameBytes(List<byte[]> frames) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] payload : frames) {
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length).array());
            out.write(payload);
            out.write(MessageDigest.getInstance("SHA-256").digest(payload));
        }
        return out.toByteArray();
    }
}
