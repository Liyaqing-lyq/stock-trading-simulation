package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema18b BEGIN 屏障定向：独审 begin-proof 探针的三条红案例转成 JUnit，并补跨 JVM 重开。
 *
 * <p>只使用真实 {@link WorldRuntime}/WorldJournal 与真实 BoardModel/EngineeringModel；
 * v6 回执经真实公开 dispatch 与收件箱边界注入（模拟网络边界，不产生真实 provider 调用）。</p>
 */
class EngineeringBeginBarrierTest {

    private static final String A = WorldWebScenario.ENGINE_A;

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-begin-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static EngineeringRun.ContractPlan plan(String id, String price, String cost,
            long... units) {
        List<Long> longs = new ArrayList<>();
        for (long unit : units) {
            longs.add(unit);
        }
        return new EngineeringRun.ContractPlan(id, new BigDecimal(price),
                new BigDecimal(cost).setScale(4), 0, 0, 0, 0, longs);
    }

    private static EngineeringRun.Config config(List<EngineeringRun.ContractPlan> aContracts) {
        EngineeringRun.Config base = WorldWebScenario.engineeringConfig();
        EngineeringRun.CompanySpec a = base.specOf(WorldWebScenario.ENGINE_A);
        EngineeringRun.CompanySpec b = base.specOf(WorldWebScenario.ENGINE_B);
        EngineeringRun.CompanySpec aSpec = new EngineeringRun.CompanySpec(a.companyPartyId(),
                a.companyOwnerId(), a.companyAccountId(), a.customerAccountId(),
                a.supplierAccountId(), a.opsAccountId(), a.unitMillis(), a.laborBp(),
                a.charter(), aContracts);
        EngineeringRun.CompanySpec bSpec = new EngineeringRun.CompanySpec(b.companyPartyId(),
                b.companyOwnerId(), b.companyAccountId(), b.customerAccountId(),
                b.supplierAccountId(), b.opsAccountId(), b.unitMillis(), b.laborBp(),
                b.charter(), List.of(plan("B1", "400.00", "240.00", 5, 5)));
        Map<String, List<EngineeringRun.CustomerEventPlan>> events = new TreeMap<>();
        events.put(WorldWebScenario.ENGINE_A, List.of());
        events.put(WorldWebScenario.ENGINE_B, List.of());
        return new EngineeringRun.Config("begin-barrier 定向夹具", List.of(aSpec, bSpec), events);
    }

    private static EngineeringRun.Config twoContracts() {
        return config(List.of(plan("A1", "100.00", "40.00", 10),
                plan("A2", "50.00", "20.00", 40)));
    }

    private static void publishMaterial(WorldRuntime world, String material, List<String> readers,
            long at, long readStart) throws Exception {
        world.schedulePublishDocument("pub-" + material, "pub-" + material + "-task", at, material,
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "材料 " + material, "实验材料", "实验材料", 10L, List.of());
        long due = readStart;
        for (String reader : readers) {
            world.scheduleStartReading("read-" + material + "-" + reader,
                    "read-" + material + "-" + reader + "-task", due, reader, material, 1L);
            due += 2L;
        }
    }

    private static void publishOpportunity(WorldRuntime world, String manager, String contractId,
            long at) throws Exception {
        world.schedulePublishDocument("pub-opp-" + contractId, "pub-opp-" + contractId + "-task",
                at, "engineering-opportunity:" + contractId, WorldWebScenario.INVESTOR,
                InformationModel.Audience.direct(manager), "机会 " + contractId, "冻结机会",
                "冻结机会", 10L, List.of());
        world.scheduleStartReading("read-opp-" + contractId, "read-opp-" + contractId + "-task",
                at + 80L, manager, "engineering-opportunity:" + contractId, 1L);
    }

    private static List<BoardModel.BudgetLine> lines(String labor, String procurement) {
        return List.of(new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                        new BigDecimal(labor)),
                new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                        new BigDecimal(procurement)));
    }

    private static List<String> votersA() {
        return List.of("alice", "bob", "carol", "dave", "erin");
    }

    private static void budgetMeeting(WorldRuntime world, String meetingId, String material,
            long base) throws Exception {
        world.scheduleEngineeringConvene(meetingId + "-c", meetingId + "-c-task", base, A,
                "alice", meetingId, base + 50L, base + 100L, base + 200L);
        for (int i = 0; i < 5; i++) {
            world.scheduleEngineeringAttend(meetingId + "-a-" + i, meetingId + "-a-" + i + "-task",
                    base + 10L + i, A, votersA().get(i), meetingId, false);
        }
        world.scheduleEngineeringMaterial(meetingId + "-m", meetingId + "-m-task", base + 30L, A,
                "alice", meetingId, "m1", material, 1L);
        world.scheduleEngineeringBudgetProposal(meetingId + "-p", meetingId + "-p-task",
                base + 40L, A, "alice", meetingId, lines("10000.00", "10000.00"));
        for (int i = 0; i < 4; i++) {
            world.scheduleEngineeringVote(meetingId + "-vy-" + i, meetingId + "-vy-" + i + "-task",
                    base + 120L + i, A, votersA().get(i), meetingId, BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement(meetingId + "-i", meetingId + "-i-task", base + 220L, A,
                "alice", meetingId);
    }

    private static void appointMeeting(WorldRuntime world, String meetingId, String material,
            String manager, String appointmentId, long base,
            BoardModel.AppointmentAction action, long effectiveAt, Long endsAt) throws Exception {
        world.scheduleEngineeringAppointmentConvene(meetingId + "-c", meetingId + "-c-task", base,
                A, "alice", meetingId, base + 50L, base + 100L, base + 200L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend(meetingId + "-a-" + i, meetingId + "-a-" + i + "-task",
                    base + 10L + i, A, votersA().get(i), meetingId, false);
        }
        world.scheduleEngineeringMaterial(meetingId + "-m", meetingId + "-m-task", base + 30L, A,
                "alice", meetingId, "m1", material, 1L);
        world.scheduleEngineeringAppointmentProposal(meetingId + "-p", meetingId + "-p-task",
                base + 40L, A, "alice", meetingId, List.of(),
                new BoardModel.AppointmentRequest(appointmentId, manager, A,
                        RoleAccess.Role.COMPANY_MANAGER, action, effectiveAt,
                        action == BoardModel.AppointmentAction.REVOKE ? null : endsAt));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote(meetingId + "-v-" + i, meetingId + "-v-" + i + "-task",
                    base + 120L + i, A, votersA().get(i), meetingId, BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement(meetingId + "-i", meetingId + "-i-task", base + 220L, A,
                "alice", meetingId);
    }

    /** 准备 A 公司：预算会议 + 经理任免 + 两张已读机会。 */
    private static void prepareManager(WorldRuntime world, long appointBase) throws Exception {
        publishMaterial(world, "b-material", votersA(), 10L, 20L);
        publishMaterial(world, "appt-material", votersA(), 10L, 60L);
        publishOpportunity(world, "dave", "A1", 10L);
        publishOpportunity(world, "dave", "A2", 120L);
        budgetMeeting(world, "budget-meeting", "b-material", 100L);
        appointMeeting(world, "appt-meeting", "appt-material", "dave", "mgr-a", 400L,
                BoardModel.AppointmentAction.APPOINT, 700L, 900_000L);
        world.advanceTo(appointBase);
        assertTrue(world.activeManagerForTest("dave", A), "夹具必须先真实任命经理");
    }

    /**
     * begin-proof 红案例 1：撤职 800000 / BEGIN 880000 / FINISH 900000。
     *
     * <p>修复后：880000 真的出现 BEGIN 业务拒绝（真实时点），屏障不再占焦点，
     * 880010 的合法阅读可排，900000 不再产生第二次拒绝，已开工的物理工单照常完工。</p>
     */
    @Test
    void revokedBeforeBeginRejectsAtBarrierAndFreesFocus() throws Exception {
        Path file = journal("begin-revoke");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, twoContracts());
        prepareManager(world, 1_000L);
        world.scheduleEngineeringAction("sign-a1", "sign-a1-task", 1_500L, A, "dave", "SIGN",
                "A1", "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("start-a1", "start-a1-task", 640_000L, A, "dave",
                "START_WORK", "A1", "W-A1", 0, 10, BigDecimal.ZERO, "", 0);
        // 合法入队的经理动作：工时 20000，真实 BEGIN = 880000，到期 900000
        world.scheduleEngineeringAction("sign-a2", "sign-a2-task", 900_000L, A, "dave", "SIGN",
                "A2", "", 0, 0, BigDecimal.ZERO, "", 0, 20_000L);
        // 他人任务：4001 到期，不得被后面的撤职或拒绝改写
        world.scheduleEngineeringVote("bob-vote", "bob-vote-task", 4_011L, A, "bob",
                "budget-meeting", BoardModel.Vote.YES, 10L);
        appointMeeting(world, "revoke-meeting", "appt-material", "dave", "mgr-a", 760_000L,
                BoardModel.AppointmentAction.REVOKE, 800_000L, null);

        var preBegin = world.snapshot();
        PendingTask gate = preBegin.pending().stream()
                .filter(task -> task.taskId().equals("sign-a2-task")).findFirst().orElseThrow();
        assertEquals(PendingTask.ENGINEERING_BEGIN_V1, gate.kind(), "排程只落 BEGIN 屏障");
        assertEquals(880_000L, gate.dueTimeMillis(), "BEGIN = 到期 − 真实工时");
        assertNull(preBegin.engineering().orElseThrow().reports().get("sign-a2-task"),
                "屏障之前不得有报告");

        world.advanceTo(879_999L);
        assertNull(world.snapshot().engineering().orElseThrow().reports().get("sign-a2-task"),
                "屏障前不得提前判权");
        assertFalse(world.isPaused());

        world.advanceTo(880_000L);
        EngineeringRun.Report begin = world.snapshot().engineering().orElseThrow()
                .reports().get("sign-a2-task");
        assertNotNull(begin, "880000 必须有真实 BEGIN 结果");
        assertFalse(begin.accepted(), "撤职后 BEGIN 必须业务拒绝");
        assertEquals(880_000L, begin.timeMillis(), "BEGIN 报告用真实开始时点");
        assertEquals(WorldRuntime.REASON_NOT_AUTHORIZED, begin.reason());
        assertFalse(world.isPaused(), "BEGIN 业务拒绝不得中断推进");
        assertFalse(world.snapshot().pending().stream()
                .anyMatch(task -> task.taskId().startsWith("sign-a2-task")),
                "被拒后不得留下 FINISH 幽灵");
        assertTrue(world.snapshot().engineering().orElseThrow().reports()
                .get("bob-vote-task") != null, "他人的 4001 任务必须已按期执行");

        // 焦点已释放：真实阅读区间 [880010,880020) 可以排
        assertEquals("SCHEDULED:read-at-begin-task", world.scheduleStartReading("read-at-begin",
                "read-at-begin-task", 880_010L, "dave", "b-material", 1L));

        world.advanceTo(900_000L);
        EngineeringRun.Report afterDue = world.snapshot().engineering().orElseThrow()
                .reports().get("sign-a2-task");
        assertEquals(begin, afterDue, "到期不得再产生第二条拒绝");
        assertFalse(world.engineeringStateOf(A).orElseThrow().contracts().containsKey("A2"));
        assertFalse(world.snapshot().engineering().orElseThrow().reports()
                .containsKey("sign-a2-task:finish"));

        // 已开工的物理工单不因撤职消失：完工子任务无人物、按合同物理时点继续
        world.advanceTo(1_240_100L);
        EngineeringModel.WorkOrder order = world.engineeringStateOf(A).orElseThrow()
                .workOrders().get("W-A1");
        assertNotNull(order, "已付工单必须存在");
        assertEquals(EngineeringModel.WorkStatus.COMPLETED, order.status(),
                "撤职不得抹掉已付工单的物理完工");
        world.close();

        // journal 证据：sign-a2 只有 880000 一次完成，位置早于 900000 的时钟推进
        long signEvents = 0;
        long signPosition = -1;
        try (WorldJournal scan = new WorldJournal(file,
                WorldWebScenario.engineeringManifest().runId())) {
            for (WorldCommit commit : scan.commits()) {
                for (WorldEvent event : commit.events()) {
                    if ("task-completed:sign-a2-task".equals(event.eventId())) {
                        signEvents++;
                        signPosition = commit.position();
                    }
                }
            }
        }
        assertEquals(1, signEvents, "sign-a2 只允许一次真实完成（BEGIN）");
        assertTrue(signPosition > 0);
        // 跨 JVM 重开：拒绝后的终态与位置逐字保持
        try (WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file, twoContracts())) {
            EngineeringRun.Report reopenedReport = reopened.snapshot().engineering().orElseThrow()
                    .reports().get("sign-a2-task");
            assertEquals(begin, reopenedReport, "重开后 BEGIN 报告不变");
            assertFalse(reopened.activeManagerForTest("dave", A), "撤职必须已真实生效");
        }
    }

    /** begin-proof 红案例 2：v6 早回执（1000）→ barrier 2000 → due 3000，撤职在 barrier 前生效。 */
    @Test
    void earlyReceiptWithRevokeRejectsAtBarrierWithoutIOException() throws Exception {
        Path file = journal("begin-v6");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                config(List.of(plan("A1", "100.00", "40.00", 10))));
        publishMaterial(world, "b-material", votersA(), 10L, 20L);
        publishMaterial(world, "appt-material", votersA(), 10L, 60L);
        publishOpportunity(world, "dave", "A1", 10L);
        budgetMeeting(world, "budget-meeting", "b-material", 100L);
        appointMeeting(world, "appt-meeting", "appt-material", "dave", "mgr-a", 400L,
                BoardModel.AppointmentAction.APPOINT, 700L, 900_000L);
        world.advanceTo(1_000L);
        // 真实撤职会议：执行早于 barrier、生效也早于 barrier
        appointMeeting(world, "revoke-meeting", "appt-material", "dave", "mgr-a", 1_100L,
                BoardModel.AppointmentAction.REVOKE, 1_400L, null);

        DecisionRun.Call call = openAndDispatch(world, "r-begin");
        long barrier = world.snapshot().decisions().orElseThrow().state().decisions().stream()
                .filter(d -> d.actionId().equals("r-begin")).findFirst().orElseThrow()
                .barrierAtMillis();
        injectReply(world, call, "engineering.sign", "{\"companyPartyId\":\"eng-a\","
                + "\"contractId\":\"A1\"}");
        DecisionRun.ActionRef ref = world.snapshot().decisions().orElseThrow()
                .actionRef("r-begin");
        assertNotNull(ref, "工具意图必须留下动作引用");
        PendingTask gate = world.snapshot().pending().stream()
                .filter(task -> task.taskId().equals(ref.taskId())).findFirst().orElseThrow();
        assertEquals(PendingTask.ENGINEERING_BEGIN_V1, gate.kind());
        assertEquals(barrier, gate.dueTimeMillis(), "回执只落意图，屏障才真正开始");
        long due = barrier + WorldWebScenario.ENGINEERING_ACTION_MILLIS;

        // 撤职会议提交（1300 附近）不得抛“动作引用或原命令与决定原文不一致”
        world.advanceTo(1_320L);
        assertFalse(world.isPaused(), "撤职会议提交不得暂停世界");
        world.advanceTo(barrier - 1);
        assertNull(world.snapshot().engineering().orElseThrow().reports().get(ref.taskId()),
                "屏障前不得提前判权");
        world.advanceTo(barrier);
        EngineeringRun.Report rejected = world.snapshot().engineering().orElseThrow()
                .reports().get(ref.taskId());
        assertNotNull(rejected, "barrier 必须有真实 BEGIN 结果");
        assertFalse(rejected.accepted(), "撤职后 barrier 必须业务拒绝");
        assertEquals(barrier, rejected.timeMillis());
        assertEquals(WorldRuntime.REASON_NOT_AUTHORIZED, rejected.reason());
        assertTrue(world.snapshot().pending().stream()
                .noneMatch(task -> task.taskId().equals(ref.taskId() + ":finish")),
                "拒绝后不得留 FINISH 幽灵");
        // 焦点在 barrier 后释放：合法阅读可排（不必等到 due）
        assertEquals("SCHEDULED:read-after-begin-task", world.scheduleStartReading(
                "read-after-begin", "read-after-begin-task", barrier + 2, "dave", "b-material",
                1L));
        world.advanceTo(due);
        assertEquals(rejected, world.snapshot().engineering().orElseThrow()
                .reports().get(ref.taskId()), "到期不再产生第二次拒绝");
        assertFalse(world.engineeringStateOf(A).orElseThrow().contracts().containsKey("A1"));
        world.close();

        try (WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file,
                config(List.of(plan("A1", "100.00", "40.00", 10))))) {
            assertEquals(rejected, reopened.snapshot().engineering().orElseThrow()
                    .reports().get(ref.taskId()), "跨 JVM 重开保持 TRUE 拒绝");
        }
    }

    /** v6 对照组：不撤职时 barrier 真实开始、due 真实完成。 */
    @Test
    void earlyReceiptWithoutRevokeStartsAtBarrierAndFinishesAtDue() throws Exception {
        Path file = journal("begin-v6-control");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                config(List.of(plan("A1", "100.00", "40.00", 10))));
        publishMaterial(world, "b-material", votersA(), 10L, 20L);
        publishMaterial(world, "appt-material", votersA(), 10L, 60L);
        publishOpportunity(world, "dave", "A1", 10L);
        budgetMeeting(world, "budget-meeting", "b-material", 100L);
        appointMeeting(world, "appt-meeting", "appt-material", "dave", "mgr-a", 400L,
                BoardModel.AppointmentAction.APPOINT, 700L, 900_000L);
        world.advanceTo(1_000L);

        DecisionRun.Call call = openAndDispatch(world, "r-ok");
        long barrier = world.snapshot().decisions().orElseThrow().state().decisions().stream()
                .filter(d -> d.actionId().equals("r-ok")).findFirst().orElseThrow()
                .barrierAtMillis();
        injectReply(world, call, "engineering.sign",
                "{\"companyPartyId\":\"eng-a\",\"contractId\":\"A1\"}");
        DecisionRun.ActionRef ref = world.snapshot().decisions().orElseThrow().actionRef("r-ok");
        assertNotNull(ref);
        long due = barrier + WorldWebScenario.ENGINEERING_ACTION_MILLIS;

        world.advanceTo(barrier - 1);
        assertNull(world.snapshot().engineering().orElseThrow().reports().get(ref.taskId()),
                "barrier 之前只落意图");
        world.advanceTo(barrier);
        EngineeringRun.Report begin = world.snapshot().engineering().orElseThrow()
                .reports().get(ref.taskId());
        assertNotNull(begin);
        assertTrue(begin.accepted(), "授权有效时 barrier 必须真实开始：" + begin);
        assertEquals(barrier, begin.timeMillis());
        assertFalse(world.engineeringStateOf(A).orElseThrow().contracts().containsKey("A1"),
                "开始不等于完成");
        assertTrue(world.snapshot().pending().stream()
                .anyMatch(task -> task.taskId().equals(ref.taskId() + ":finish")),
                "开始时必须排真实完成帧");
        world.advanceTo(due);
        EngineeringRun.Report finish = world.snapshot().engineering().orElseThrow()
                .reports().get(ref.taskId() + ":finish");
        assertNotNull(finish, "到期必须留下完成报告");
        assertTrue(finish.accepted(), "完成报告：" + finish);
        assertEquals(due, finish.timeMillis());
        assertTrue(world.engineeringStateOf(A).orElseThrow().contracts().containsKey("A1"),
                "到期才真正签约");
        world.close();

        try (WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file,
                config(List.of(plan("A1", "100.00", "40.00", 10))))) {
            assertEquals(finish, reopened.snapshot().engineering().orElseThrow()
                    .reports().get(ref.taskId() + ":finish"));
            assertTrue(reopened.engineeringStateOf(A).orElseThrow().contracts()
                    .containsKey("A1"));
        }
    }

    /** BEGIN 之后撤职：完成帧按业务规则拒绝，但世界继续推进。 */
    @Test
    void revokeBetweenBeginAndFinishRejectsAtFinish() throws Exception {
        Path file = journal("revoke-mid");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, twoContracts());
        prepareManager(world, 1_000L);
        // BEGIN = 2000（到期 3000 − 工时 1000）
        world.scheduleEngineeringAction("sign-late", "sign-late-task", 3_000L, A, "dave", "SIGN",
                "A2", "", 0, 0, BigDecimal.ZERO, "", 0, 1_000L);
        // 撤职会议：implement 2500（晚于 BEGIN 2000），解除在实施时点真实生效
        appointMeeting(world, "revoke-mid", "appt-material", "dave", "mgr-a", 2_280L,
                BoardModel.AppointmentAction.REVOKE, 2_500L, null);
        world.advanceTo(2_000L);
        EngineeringRun.Report begin = world.snapshot().engineering().orElseThrow()
                .reports().get("sign-late-task");
        assertNotNull(begin);
        assertTrue(begin.accepted(), "BEGIN 时经理仍有效：" + begin);
        world.advanceTo(2_600L);
        assertFalse(world.activeManagerForTest("dave", A), "撤职必须已生效");
        world.advanceTo(3_000L);
        assertFalse(world.isPaused(), "完成期业务拒绝不得暂停");
        EngineeringRun.Report finish = world.snapshot().engineering().orElseThrow()
                .reports().get("sign-late-task:finish");
        assertNotNull(finish, "完成帧必须有报告");
        assertFalse(finish.accepted(), "撤职后完成帧必须业务拒绝");
        assertEquals(WorldRuntime.REASON_NOT_AUTHORIZED, finish.reason());
        assertEquals(3_000L, finish.timeMillis());
        assertFalse(world.engineeringStateOf(A).orElseThrow().contracts().containsKey("A2"));
        world.close();
    }

    /** 阅读判权用真实动作区间：未来不重叠允许，真实相交双向拒。 */
    @Test
    void readingJudgedOnRealIntervalNotWholeFutureSpan() throws Exception {
        Path file = journal("reading-window");
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(file, twoContracts())) {
            publishMaterial(world, "doc-window", List.of("alice"), 10L, 20L);
            world.advanceTo(10L);
            // 工程窗口 [2000,3000) 与阅读 [3500,3510) 不相交 → 允许
            world.scheduleEngineeringVote("v-1", "v-1-task", 3_000L, A, "alice", "m-1",
                    BoardModel.Vote.YES, 1_000L);
            assertEquals("SCHEDULED:read-after-task", world.scheduleStartReading("read-after",
                    "read-after-task", 3_500L, "alice", "doc-window", 1L),
                    "预约之后的阅读不得被未来工程窗口挡住");
            // 真实相交 → 双向拒绝
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleStartReading("read-inside", "read-inside-task", 2_500L,
                            "alice", "doc-window", 1L), "与工程窗口真实相交必须拒绝");
            // 工程窗口 [5000,6000) 与阅读 [4000,4010) 不相交 → 允许
            world.scheduleEngineeringVote("v-2", "v-2-task", 6_000L, A, "alice", "m-2",
                    BoardModel.Vote.YES, 1_000L);
            assertEquals("SCHEDULED:read-before-task", world.scheduleStartReading("read-before",
                    "read-before-task", 4_000L, "alice", "doc-window", 1L),
                    "预约之前的阅读不得被未来工程窗口挡住");
            // 反向：已排阅读 [7000,7010) 不挡 [8000,9000) 的工程动作，但挡 [7005,7010)
            world.scheduleStartReading("read-future", "read-future-task", 7_000L, "alice",
                    "doc-window", 1L);
            assertEquals("SCHEDULED:v-3-task", world.scheduleEngineeringVote("v-3", "v-3-task",
                    9_000L, A, "alice", "m-3", BoardModel.Vote.YES, 1_000L),
                    "阅读结束之后的工程动作必须允许");
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("v-4", "v-4-task", 7_010L, A, "alice",
                            "m-4", BoardModel.Vote.YES, 1_000L),
                    "与已排阅读真实相交的工程动作必须拒绝");
        }
    }

    /** 跨 JVM 重开：BEGIN 之前、BEGIN 之后、BEGIN 拒绝之后三个位置都逐帧一致。 */
    @Test
    void reopenAcrossBeginBoundaryKeepsBarrierState() throws Exception {
        EngineeringRun.Config cfg = twoContracts();
        Path file = journal("reopen-begin");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, cfg);
        prepareManager(world, 1_000L);
        world.scheduleEngineeringAction("sign-a1", "sign-a1-task", 3_000L, A, "dave", "SIGN",
                "A1", "", 0, 0, BigDecimal.ZERO, "", 0, 1_000L);
        world.advanceTo(1_500L);
        long beforePosition = world.snapshot().position();
        world.close();

        // BEGIN 之前重开：屏障仍在，时点/时长不变
        WorldRuntime reopenedBefore = WorldWebScenario.engineeringRuntime(file, cfg);
        assertEquals(beforePosition, reopenedBefore.snapshot().position());
        PendingTask gate = reopenedBefore.snapshot().pending().stream()
                .filter(task -> task.taskId().equals("sign-a1-task")).findFirst().orElseThrow();
        assertEquals(PendingTask.ENGINEERING_BEGIN_V1, gate.kind());
        assertEquals(2_000L, gate.dueTimeMillis());
        reopenedBefore.advanceTo(2_000L);
        assertTrue(reopenedBefore.snapshot().engineering().orElseThrow()
                .reports().get("sign-a1-task").accepted(), "重开后 BEGIN 必须照常真实开始");
        reopenedBefore.advanceTo(3_000L);
        long afterPosition = reopenedBefore.snapshot().position();
        assertTrue(reopenedBefore.engineeringStateOf(A).orElseThrow().contracts()
                .containsKey("A1"));
        reopenedBefore.close();

        // BEGIN 之后重开：完成报告与合同都在，位置不回退
        WorldRuntime reopenedAfter = WorldWebScenario.engineeringRuntime(file, cfg);
        assertEquals(afterPosition, reopenedAfter.snapshot().position());
        assertEquals(3_000L, reopenedAfter.snapshot().engineering().orElseThrow()
                .reports().get("sign-a1-task:finish").timeMillis());
        reopenedAfter.close();
    }

    private static DecisionRun.Call openAndDispatch(WorldRuntime world, String requestId)
            throws Exception {
        world.beginDecisionSession("open-" + requestId);
        world.openDecisions("dec-" + requestId, List.of(new DecisionRun.Request("dave", requestId,
                "wake", new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20,
                        1_000_000, 1_024, 1))));
        return world.dispatchLiveDecision("dispatch-" + requestId, requestId).call();
    }

    /** 网络边界注入：只把确定性回执放进 live 收件箱，不产生真实 provider 调用。 */
    private static void injectReply(WorldRuntime world, DecisionRun.Call call, String tool,
            String args) throws Exception {
        String body = "{\"tool\":\"" + tool + "\",\"args\":" + args + "}";
        DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, 60, 20), "scripted:begin-barrier");
        java.lang.reflect.Field field = WorldRuntime.class.getDeclaredField("liveInbox");
        field.setAccessible(true);
        LiveCompletionInbox inbox = (LiveCompletionInbox) field.get(world);
        inbox.register(LiveCompletionInbox.AttemptKey.of(call),
                LiveCompletionInbox.Completion.business(reply));
        world.drainDecisionCallbacks();
    }
}
