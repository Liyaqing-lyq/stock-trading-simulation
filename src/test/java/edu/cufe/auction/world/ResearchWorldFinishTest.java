package edu.cufe.auction.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cufe.auction.web.WorldWebScenario;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * schema22 研发世界收尾边界：跨期未执行阶段、观察任职在完成期复核、原任务恢复派生、
 * 真实写故障前缀恢复。
 *
 * <p>所有断言只读真实 journal/状态/账：不 mock 日志，不替换命令，不删既有测试。</p>
 */
class ResearchWorldFinishTest {

    private static final String A = WorldWebScenario.ENGINE_A;
    private static final long DAY = 86_400_000L;
    private static final long MANAGER_END_30 = 30L * DAY;
    private static final long MANAGER_END_90 = 90L * DAY;
    private static final String COMPANY_ACCOUNT = "ENG-A";
    private static final String CONTRACTOR_ACCOUNT = WorldWebScenario.RESEARCH_CONTRACTOR;

    @TempDir
    Path dir;

    // ---------------------------------------------------------------- 跨期未执行阶段

    @Test
    void crossPeriodStageSpendsFrozenCommitmentAndFailureReleasesRemainingOnce() throws Exception {
        WorldRuntime world = prepared(dir.resolve("cross-period.journal"), MANAGER_END_90);
        world.scheduleResearchRegister("reg-p2", "reg-p2-task", 3_001L, A, "dave", "p2", 1_000L);
        world.advanceTo(3_001L);
        BoardModel.Commitment registered = commitment(world, "research:p2");
        assertNotNull(registered, "立项必须形成真实预算承诺");
        assertEquals(0L, registered.period(), "承诺冻结在立项期");
        assertEquals(0, registered.outstanding().compareTo(new BigDecimal("600.00")));
        assertEquals(0, BoardModel.availableBudget(world.researchBudgetForTest(A), 0,
                BoardModel.BudgetCategory.PROCUREMENT).compareTo(new BigDecimal("9400.00")));
        assertEquals(0, cash(world, COMPANY_ACCOUNT).compareTo(new BigDecimal("5000.00")));

        // 跨过一个期末边界：阶段一仍未开工，承诺不得被自动花掉或自动开工。
        world.advanceTo(MANAGER_END_30 + 1_000L);
        assertNotNull(commitment(world, "research:p2"), "跨期后承诺仍在");
        assertEquals(0, commitment(world, "research:p2").outstanding()
                .compareTo(new BigDecimal("600.00")), "未执行阶段不自动花费");
        assertEquals(ResearchModel.StageStatus.PLANNED,
                stage(world, "p2", 0).status(), "跨期不得自动开工");
        assertEquals(0, cash(world, COMPANY_ACCOUNT).compareTo(new BigDecimal("5000.00")),
                "未执行阶段不得自动付款");

        // 新期立项需要当期真实预算行：旧期上限与旧承诺都不得被新项目复用/冒充。
        world.scheduleResearchRegister("reg-p10", "reg-p10-task", MANAGER_END_30 + 4_000L, A,
                "dave", "p10", 1_000L);
        world.advanceTo(MANAGER_END_30 + 4_000L);
        assertTrue(!world.researchRun().orElseThrow().state().projects().containsKey("p10"),
                "新期没有当期预算行，不得继续用旧期上限立项");
        assertTrue(commitment(world, "research:p10") == null, "被拒立项不得形成承诺");

        // 新期里开工：从立项期的冻结承诺花费，不再重新占用当前期预算。
        long begin0 = MANAGER_END_30 + 6_000L;
        world.scheduleResearchBegin("beg-p2-0", "beg-p2-0-task", begin0, A, "dave", "p2",
                ResearchModel.Stage.RESEARCH, 1_000L);
        world.advanceTo(begin0);
        assertEquals(ResearchModel.StageStatus.IN_PROGRESS, stage(world, "p2", 0).status());
        assertEquals(0, commitment(world, "research:p2").outstanding()
                .compareTo(new BigDecimal("500.00")), "花费从冻结承诺扣减");
        assertEquals(0, cash(world, COMPANY_ACCOUNT).compareTo(new BigDecimal("4900.00")));
        assertEquals(0, cash(world, CONTRACTOR_ACCOUNT).compareTo(new BigDecimal("600.00")));

        world.advanceTo(begin0 + DAY);
        assertEquals(ResearchModel.StageStatus.COMPLETED, stage(world, "p2", 0).status());

        // 第二阶段失败：未用余额必须释放回原立项期，且只释放一次。
        long begin1 = begin0 + DAY + 5_000L;
        world.scheduleResearchBegin("beg-p2-1", "beg-p2-1-task", begin1, A, "dave", "p2",
                ResearchModel.Stage.DEVELOPMENT, 1_000L);
        world.advanceTo(begin1);
        assertEquals(0, commitment(world, "research:p2").outstanding()
                .compareTo(new BigDecimal("300.00")));
        world.advanceTo(begin1 + DAY);
        assertEquals(ResearchModel.ProjectStatus.FAILED, project(world, "p2").status());
        assertEquals(ResearchModel.StageStatus.FAILED, stage(world, "p2", 1).status());
        assertEquals(ResearchModel.StageStatus.PLANNED, stage(world, "p2", 2).status(),
                "失败后不自动开下一阶段");
        BoardModel.Commitment closed = commitment(world, "research:p2");
        assertEquals(0, closed.outstanding().compareTo(BigDecimal.ZERO), "剩余余额已释放");
        assertEquals(0, closed.released().compareTo(new BigDecimal("300.00")), "只释放剩余一次");
        assertEquals(0L, closed.period(), "释放回到立项期");
        assertEquals(0, BoardModel.availableBudget(world.researchBudgetForTest(A), 0,
                BoardModel.BudgetCategory.PROCUREMENT).compareTo(new BigDecimal("9700.00")),
                "可再用额度 = 上限 − 已花 300（失败损失不退现金）");
        assertEquals(0, cash(world, COMPANY_ACCOUNT).compareTo(new BigDecimal("4700.00")),
                "失败不退款、不自动补偿");
        assertEquals(0, cash(world, CONTRACTOR_ACCOUNT).compareTo(new BigDecimal("800.00")));
        world.close();

        // 重开：跨期承诺/已花/已释放/阶段全部由真实 journal 恢复，不重算也不重放。
        try (WorldRuntime reopened = WorldWebScenario.openResearch(
                dir.resolve("cross-period.journal")).runtime()) {
            BoardModel.Commitment restored = commitment(reopened, "research:p2");
            assertEquals(0, restored.spent().compareTo(new BigDecimal("300.00")));
            assertEquals(0, restored.released().compareTo(new BigDecimal("300.00")));
            assertEquals(ResearchModel.ProjectStatus.FAILED, project(reopened, "p2").status());
            assertEquals(0, cash(reopened, COMPANY_ACCOUNT).compareTo(new BigDecimal("4700.00")));
        }
    }

    // ---------------------------------------------------------------- 观察任职在完成期复核

    @Test
    void appointmentExpiredBeforeBeginBarrierRejectsLateSchedule() throws Exception {
        WorldRuntime world = prepared(dir.resolve("expiry.journal"), MANAGER_END_30);
        world.scheduleResearchRegister("reg-p1", "reg-p1-task", 3_001L, A, "dave", "p1", 1_000L);
        world.advanceTo(3_001L);
        long begin = MANAGER_END_30 + 5_000L;
        // 排程时经理仍在任（观察任职有效），因此可以排；BEGIN 屏障必须按真实时点复核。
        world.scheduleResearchBegin("beg-late", "beg-late-task", begin, A, "dave", "p1",
                ResearchModel.Stage.RESEARCH, 1_000L);
        world.advanceTo(begin);
        assertEquals(ResearchModel.StageStatus.PLANNED, stage(world, "p1", 0).status(),
                "BEGIN 屏障任职已到期，动作必须拒");
        assertEquals(0, commitment(world, "research:p1").outstanding()
                .compareTo(new BigDecimal("900.00")), "被拒的 BEGIN 不得花费预算");
        assertEquals(0, cash(world, COMPANY_ACCOUNT).compareTo(new BigDecimal("5000.00")),
                "被拒的 BEGIN 不得付款");
        assertEquals(-1L, stage(world, "p1", 0).beganAt(), "被拒的 BEGIN 不得留下开工时刻");
        world.close();
    }

    @Test
    void revocationBetweenBeginBarrierAndFinishRejectsAtFinish() throws Exception {
        WorldRuntime world = prepared(dir.resolve("finish.journal"), MANAGER_END_30);
        world.scheduleResearchRegister("reg-p1", "reg-p1-task", 3_001L, A, "dave", "p1", 1_000L);
        world.advanceTo(3_001L);
        // 真实工时 1000：BEGIN 屏障 4000，FINISH 5000。
        world.scheduleResearchBegin("beg-fin", "beg-fin-task", 5_000L, A, "dave", "p1",
                ResearchModel.Stage.RESEARCH, 1_000L);
        revokeAt(world, "rev-fin", 3_100L, 4_400L, 4_500L);

        world.advanceTo(4_000L);
        assertTrue(world.snapshot().pending().stream()
                        .anyMatch(task -> PendingTask.RESEARCH_BEGIN_V1.equals(task.kind())
                                && task.dueTimeMillis() == 5_000L),
                "BEGIN 屏障通过后应留下真实完成动作");
        assertEquals(ResearchModel.StageStatus.PLANNED, stage(world, "p1", 0).status());
        world.advanceTo(4_500L);
        assertFalse(world.activeManagerForTest("dave", A), "撤任必须真实生效");
        world.advanceTo(5_000L);
        assertEquals(ResearchModel.StageStatus.PLANNED, stage(world, "p1", 0).status(),
                "FINISH 时任职已撤，已通过的 BEGIN 不得放行完成");
        assertEquals(0, cash(world, COMPANY_ACCOUNT).compareTo(new BigDecimal("5000.00")),
                "被拒的 FINISH 不得付款");
        assertEquals(0, commitment(world, "research:p1").outstanding()
                .compareTo(new BigDecimal("900.00")), "被拒的 FINISH 不得花费预算");
        assertTrue(world.snapshot().pending().stream()
                        .noneMatch(task -> task.taskId().startsWith("beg-fin-task")),
                "被拒的完成动作必须移除，不得残留");
        world.close();
    }

    // ---------------------------------------------------------------- 原任务完整恢复派生

    @Test
    void recoveryRedrivesResultChildExactlyOnce() throws Exception {
        Path file = dir.resolve("recover.journal");
        WorldRuntime world = prepared(file, MANAGER_END_30);
        world.scheduleResearchRegister("reg-p1", "reg-p1-task", 3_001L, A, "dave", "p1", 1_000L);
        world.advanceTo(3_001L);
        world.scheduleResearchBegin("beg-p1", "beg-p1-task", 5_000L, A, "dave", "p1",
                ResearchModel.Stage.RESEARCH, 1_000L);
        world.advanceTo(5_000L);
        assertEquals(ResearchModel.StageStatus.IN_PROGRESS, stage(world, "p1", 0).status());
        long position = world.snapshot().position();
        long resultDue = 5_000L + DAY;
        world.close();

        // 新进程：派生结果任务必须按原 BEGIN 任务重建，恰好一条、同一到期时刻。
        List<PendingTask> pending;
        try (WorldRuntime reopened = WorldWebScenario.openResearch(file).runtime()) {
            pending = reopened.snapshot().pending().stream()
                    .filter(task -> PendingTask.RESEARCH_RESULT_V1.equals(task.kind()))
                    .toList();
            assertEquals(1, pending.size(), "恢复派生必须恰好一条结果任务");
            assertEquals("research-result:eng-a:p1:RESEARCH", pending.get(0).taskId());
            assertEquals(resultDue, pending.get(0).dueTimeMillis());
            assertEquals(position, reopened.snapshot().position(), "重开读取不凭空推进位置");
            reopened.advanceTo(resultDue);
            assertEquals(ResearchModel.StageStatus.COMPLETED,
                    stage(reopened, "p1", 0).status());
            assertEquals(0, cash(reopened, COMPANY_ACCOUNT).compareTo(new BigDecimal("4800.00")),
                    "阶段付款只发生一次");
            assertEquals(0, reopened.snapshot().pending().stream()
                    .filter(task -> PendingTask.RESEARCH_RESULT_V1.equals(task.kind()))
                    .count(), "结果任务完成后必须移除");
            long afterResult = reopened.snapshot().position();
            assertEquals(1, evidenceCount(reopened, "research:eng-a:p1:RESEARCH:SUCCESS"));
            reopened.close();

            // 再次重启：结果/付款/证据都不得重复。
            try (WorldRuntime again = WorldWebScenario.openResearch(file).runtime()) {
                assertEquals(afterResult, again.snapshot().position());
                again.advanceTo(resultDue + 1L);
                assertEquals(0, cash(again, COMPANY_ACCOUNT)
                        .compareTo(new BigDecimal("4800.00")), "重启后不得二次付款");
                assertEquals(1, evidenceCount(again, "research:eng-a:p1:RESEARCH:SUCCESS"));
            }
        }
    }

    // ---------------------------------------------------------------- 真实写故障前缀恢复

    @Test
    void shortWriteOnBeginFrameKeepsNoHalfStateThenRedoOnce() throws Exception {
        Path file = dir.resolve("write-fault.journal");
        WorldRuntime world = prepared(file, MANAGER_END_30);
        world.scheduleResearchRegister("reg-p1", "reg-p1-task", 3_001L, A, "dave", "p1", 1_000L);
        world.advanceTo(3_001L);
        long boundary = Files.size(file);
        world.scheduleResearchBegin("beg-p1", "beg-p1-task", 5_000L, A, "dave", "p1",
                ResearchModel.Stage.RESEARCH, 1_000L);
        assertTrue(Files.size(file) > boundary + 20, "排程批次必须真实落盘");
        world.close();

        truncateMidFrame(file, boundary);
        try (WorldRuntime recovered = WorldWebScenario.openResearch(file).runtime()) {
            assertEquals(ResearchModel.StageStatus.PLANNED, stage(recovered, "p1", 0).status(),
                    "未确认的排程帧不得半应用");
            assertTrue(recovered.snapshot().pending().stream()
                    .noneMatch(task -> task.taskId().equals("beg-p1-task")),
                    "未确认任务不得残留");
            assertEquals(0, commitment(recovered, "research:p1").outstanding()
                    .compareTo(new BigDecimal("900.00")), "未确认批次不得花费预算");
            assertEquals(0, cash(recovered, COMPANY_ACCOUNT).compareTo(new BigDecimal("5000.00")));
            // 同命令在新进程重新执行：真实动作只发生一次。
            recovered.scheduleResearchBegin("beg-p1", "beg-p1-task", 5_000L, A, "dave", "p1",
                    ResearchModel.Stage.RESEARCH, 1_000L);
            recovered.advanceTo(5_000L);
            assertEquals(ResearchModel.StageStatus.IN_PROGRESS, stage(recovered, "p1", 0).status());
            assertEquals(0, cash(recovered, COMPANY_ACCOUNT)
                    .compareTo(new BigDecimal("4800.00")), "付款恰好一次");
            assertEquals(1, recovered.snapshot().pending().stream()
                    .filter(task -> PendingTask.RESEARCH_RESULT_V1.equals(task.kind()))
                    .count(), "派生结果任务恰好一条");
        }
    }

    /**
     * force 失败后的磁盘状态：完整帧已写入、但确认从未返回。JDK 无法让 force 真实抛错，
     * 因此这里按文件级真实复现该状态（旧前缀 + 已写入的完整帧），验证恢复确认该帧，
     * 且调用方以同命令重试时不会二次记账。
     */
    @Test
    void completeFrameWithoutForceConfirmationIsConfirmedOnceAndRetryIsIdempotent()
            throws Exception {
        Path base = dir.resolve("force-base.journal");
        WorldRuntime world = prepared(base, MANAGER_END_30);
        world.scheduleResearchRegister("reg-p1", "reg-p1-task", 3_001L, A, "dave", "p1", 1_000L);
        world.advanceTo(3_001L);
        long boundary = Files.size(base);
        world.close();

        // 在副本上真实提交该 BEGIN 批次，取回 force 前写入的完整帧字节。
        Path committed = dir.resolve("force-committed.journal");
        Files.copy(base, committed);
        long committedPosition;
        try (WorldRuntime writer = WorldWebScenario.openResearch(committed).runtime()) {
            writer.scheduleResearchBegin("beg-p1", "beg-p1-task", 5_000L, A, "dave", "p1",
                    ResearchModel.Stage.RESEARCH, 1_000L);
            committedPosition = writer.snapshot().position();
        }
        byte[] frame = Files.readAllBytes(committed);
        assertTrue(frame.length > boundary + 20);

        // force 未确认状态：旧前缀 + 完整帧（与真实崩溃后可能留下的字节完全一致）。
        Path unconfirmed = dir.resolve("force-unconfirmed.journal");
        Files.copy(base, unconfirmed);
        try (FileChannel channel = FileChannel.open(unconfirmed, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            channel.write(java.nio.ByteBuffer.wrap(frame, (int) boundary,
                    frame.length - (int) boundary));
            // 不 force：确认从未返回。
        }
        try (WorldRuntime recovered = WorldWebScenario.openResearch(unconfirmed).runtime()) {
            assertEquals(committedPosition, recovered.snapshot().position(),
                    "完整帧在恢复时按真实日志确认");
            assertEquals(ResearchModel.StageStatus.PLANNED, stage(recovered, "p1", 0).status(),
                    "确认的是排程帧，开工仍须到期完成");
            long before = recovered.snapshot().position();
            assertEquals("SCHEDULED:beg-p1-task", recovered.scheduleResearchBegin("beg-p1",
                    "beg-p1-task", 5_000L, A, "dave", "p1", ResearchModel.Stage.RESEARCH,
                    1_000L), "同命令重试返回原回执");
            assertEquals(before, recovered.snapshot().position(), "同命令重试不得再追加批次");
            assertThrows(IllegalArgumentException.class, () -> recovered.scheduleResearchBegin(
                    "beg-p1", "beg-p1-task", 5_000L, A, "dave", "p1",
                    ResearchModel.Stage.RESEARCH, 2_000L), "同命令改工时必须拒");
            recovered.advanceTo(5_000L);
            assertEquals(ResearchModel.StageStatus.IN_PROGRESS, stage(recovered, "p1", 0).status());
            assertEquals(0, cash(recovered, COMPANY_ACCOUNT)
                    .compareTo(new BigDecimal("4800.00")), "付款恰好一次");
        }
    }

    // ---------------------------------------------------------------- 夹具与只读工具

    /** 真实默认新档：预算会议 + 任免 dave（任期由参数给定），不预塞预算/经理。 */
    private static WorldRuntime prepared(Path file, long managerEnd) throws Exception {
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
                "budget-meeting", List.of(
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                new BigDecimal("10000.00")),
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                new BigDecimal("10000.00"))));
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
                        700L, managerEnd));
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

    /**
     * 真实撤任会议：表决截止 {@code closesAt}，实施并在 {@code implementAt} 生效。
     * 只撤 dave 的 mgr-a，不新建任职。
     */
    private static void revokeAt(WorldRuntime world, String prefix, long conveneAt, long closesAt,
            long implementAt) throws Exception {
        List<String> voters = List.of("alice", "bob", "carol");
        world.scheduleEngineeringAppointmentConvene(prefix + "-c", prefix + "-c-task", conveneAt,
                A, "alice", prefix + "-meeting", conveneAt + 50, conveneAt + 100, closesAt);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend(prefix + "-a-" + i, prefix + "-a-" + i + "-task",
                    conveneAt + 10 + i, A, voters.get(i), prefix + "-meeting", false);
        }
        world.scheduleEngineeringMaterial(prefix + "-m", prefix + "-m-task", conveneAt + 30, A,
                "alice", prefix + "-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal(prefix + "-p", prefix + "-p-task",
                conveneAt + 40, A, "alice", prefix + "-meeting", List.of(),
                new BoardModel.AppointmentRequest("mgr-a", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.REVOKE,
                        implementAt, null));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote(prefix + "-v-" + i, prefix + "-v-" + i + "-task",
                    closesAt - 100 + i, A, voters.get(i), prefix + "-meeting",
                    BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement(prefix + "-i", prefix + "-i-task", implementAt, A,
                "alice", prefix + "-meeting");
    }

    private static void truncateMidFrame(Path file, long boundary) throws Exception {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            assertTrue(channel.size() > boundary + 20, "必须有足够字节可截半");
            channel.truncate(boundary + 20);
            channel.force(true);
        }
    }

    private static ResearchModel.Project project(WorldRuntime world, String projectId) {
        return world.researchRun().orElseThrow().state().projects().get(projectId);
    }

    private static ResearchModel.StageRecord stage(WorldRuntime world, String projectId,
            int index) {
        return project(world, projectId).stages().get(index);
    }

    private static BoardModel.Commitment commitment(WorldRuntime world, String commitmentId) {
        BoardModel.State board = world.researchBudgetForTest(A);
        return board == null ? null : board.commitments().stream()
                .filter(each -> each.commitmentId().equals(commitmentId))
                .findFirst().orElse(null);
    }

    private static BigDecimal cash(WorldRuntime world, String accountId) {
        CashLedger.CashAccount account = world.snapshot().accounts().get(accountId);
        assertNotNull(account, "账户必须存在：" + accountId);
        return account.cash();
    }

    private static long evidenceCount(WorldRuntime world, String docId) {
        InformationModel.State info = world.snapshot().information().orElseThrow().information();
        return info.documents().stream().filter(doc -> doc.docId().equals(docId)).count();
    }
}
