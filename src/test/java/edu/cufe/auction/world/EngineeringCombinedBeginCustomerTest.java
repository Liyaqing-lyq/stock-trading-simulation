package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三方合并定向：BEGIN 屏障（真实开始）与客户请求/回应（真实付款）在同一世界日志里共存。
 *
 * <p>本轮只验证两侧语义的接缝，不重复各自候选的完整套件：</p>
 * <ul>
 *   <li>经理请求先落 BEGIN 屏障：回执/开始<b>只代表意图</b>，不生成客户请求、不动应收/现金；</li>
 *   <li>FINISH 才真正生成客户请求，金额与拒绝原因仍按原冻结计划；</li>
 *   <li>客户请求在 BEGIN 之后、FINISH 之前跨 JVM 重开必须保持原屏障与完成帧，不重复请求/付款；</li>
 *   <li>撤职后 BEGIN 拒绝（不留完成帧），同命令重放仍幂等；</li>
 *   <li>同人两个真实区间重叠时，后开始的动作在 BEGIN 被焦点拒绝，先排的动作不受影响；</li>
 *   <li>客户回应事件与该请求的完成帧同批到期时两者共存，重开逐帧重演不误拒。</li>
 * </ul>
 *
 * <p>金额数字沿用主树已验的冻结 B 参数（单价 100.00/成本 60.00、预收 20%/质保 10%、
 * 人工 60%、阶段 40），不新增或改动任何数字。</p>
 */
class EngineeringCombinedBeginCustomerTest {

    private static final long DAY = EngineeringModel.DAY_MILLIS;
    private static final long WORK_DONE = 1_450L + 100L * DAY;
    private static final long ACCEPT_AT = WORK_DONE + 250L;
    private static final long INVOICE_AT = WORK_DONE + 600L;
    private static final long DUE_AT = INVOICE_AT + 15L * DAY;
    private static final long PAY_AT = DUE_AT + 1_200L;
    private static final String C1 = "T-1";
    private static final String A = WorldWebScenario.ENGINE_A;
    private static final String CUSTOMER = "ENG-A-CUS";

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-combined-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static EngineeringRun.ContractPlan contract(String id) {
        return new EngineeringRun.ContractPlan(id, new BigDecimal("100.00"),
                new BigDecimal("60.0000"), 2000, 1000, 15, 60, List.of(40L));
    }

    private static EngineeringRun.CompanySpec aSpec() {
        EngineeringRun.CompanySpec base = WorldWebScenario.engineeringConfig().specOf(A);
        return new EngineeringRun.CompanySpec(base.companyPartyId(), base.companyOwnerId(),
                base.companyAccountId(), base.customerAccountId(), base.supplierAccountId(),
                base.opsAccountId(), DAY, 6_000, base.charter(), List.of(contract(C1)),
                new EngineeringRun.CustomerPool(CUSTOMER, 30L * DAY,
                        new BigDecimal("5000.00"), 6));
    }

    private static EngineeringRun.CustomerEventPlan event(String id, String kind, long at,
            long units, String amount, String requestKind) {
        boolean payment = List.of("ADVANCE", "PAY", "RETENTION_PAY", "RECOVERY_PAY")
                .contains(kind);
        return new EngineeringRun.CustomerEventPlan(id, A, C1, 0, kind, at, units,
                amount == null ? BigDecimal.ZERO : new BigDecimal(amount),
                payment ? CUSTOMER : "", "冻结客户证据：" + id, requestKind);
    }

    private static EngineeringRun.Config config(List<EngineeringRun.CustomerEventPlan> events) {
        return new EngineeringRun.Config("合并候选组合实验参数", List.of(aSpec()),
                Map.of(A, events));
    }

    private static List<EngineeringRun.CustomerEventPlan> baseEvents() {
        return List.of(
                event("adv-1", "ADVANCE", 1_400L, 0, "2000.00", ""),
                event("acc-1", "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"),
                event("pay-1", "PAY", PAY_AT, 0, "400.00", "PAYMENT"));
    }

    /** 真实预算/任免治理：dave 自 700 起任经理，此后才有工程权。 */
    private static void governance(WorldRuntime world) throws Exception {
        world.schedulePublishDocument("pub-mat", "pub-mat-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-opp", "pub-opp-task", 10L,
                "engineering-opportunity:" + C1, WorldWebScenario.INVESTOR,
                InformationModel.Audience.direct("dave"), C1 + " 机会",
                "实验机会 " + C1, "冻结机会", 10L, List.of());
        String[] persons = {"alice", "bob", "carol", "dave", "erin"};
        for (int i = 0; i < persons.length; i++) {
            world.scheduleStartReading("read-mat-" + persons[i],
                    "read-mat-" + persons[i] + "-task", 20L + i * 2L, persons[i],
                    "b-material", 1L);
            world.scheduleStartReading("read-appt-" + persons[i],
                    "read-appt-" + persons[i] + "-task", 60L + i * 2L, persons[i],
                    "appt-material", 1L);
        }
        world.scheduleStartReading("read-opp", "read-opp-task", 100L, "dave",
                "engineering-opportunity:" + C1, 1L);
        world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L, A, "alice",
                "budget-meeting", 150L, 200L, 300L);
        for (int i = 0; i < persons.length; i++) {
            world.scheduleEngineeringAttend("budget-a-" + i, "budget-a-" + i + "-task",
                    110L + i, A, persons[i], "budget-meeting", false);
        }
        world.scheduleEngineeringMaterial("budget-m", "budget-m-task", 130L, A, "alice",
                "budget-meeting", "m1", "b-material", 1L);
        world.scheduleEngineeringBudgetProposal("budget-p", "budget-p-task", 140L, A, "alice",
                "budget-meeting", List.of(
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                new BigDecimal("10000.00")),
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                new BigDecimal("10000.00"))));
        for (int i = 0; i < 4; i++) {
            world.scheduleEngineeringVote("budget-v-" + i, "budget-v-" + i + "-task",
                    220L + i, A, persons[i], "budget-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringVote("budget-v-4", "budget-v-4-task", 224L, A, "erin",
                "budget-meeting", BoardModel.Vote.NO);
        world.scheduleEngineeringImplement("budget-i", "budget-i-task", 320L, A, "alice",
                "budget-meeting");
        world.scheduleEngineeringAppointmentConvene("mgr-c", "mgr-c-task", 400L, A, "alice",
                "mgr-meeting", 450L, 500L, 600L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("mgr-a-" + i, "mgr-a-" + i + "-task",
                    410L + i, A, persons[i], "mgr-meeting", false);
        }
        world.scheduleEngineeringMaterial("mgr-m", "mgr-m-task", 430L, A, "alice",
                "mgr-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("mgr-p", "mgr-p-task", 440L, A, "alice",
                "mgr-meeting", List.of(),
                new BoardModel.AppointmentRequest("mgr-appoint", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        700L, 900L * DAY));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("mgr-v-" + i, "mgr-v-" + i + "-task",
                    520L + i, A, persons[i], "mgr-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("mgr-i", "mgr-i-task", 620L, A, "alice",
                "mgr-meeting");
        world.advanceTo(700L);
    }

    /** 真实签约 + 开工（40 单位，正好是第一段验收量）。 */
    private static void signAndWork(WorldRuntime world) throws Exception {
        world.scheduleEngineeringAction("sign-1", "sign-1-task", 1_350L, A, "dave", "SIGN",
                C1, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("work-1", "work-1-task", 1_400L, A, "dave",
                "START_WORK", C1, "W-1", 0, 40, BigDecimal.ZERO, "", 0);
    }

    private static EngineeringRun.Report report(WorldRuntime world, String taskId) {
        return world.snapshot().engineering().orElseThrow().reports().get(taskId);
    }

    private static List<PendingTask> pending(WorldRuntime world, String taskId) {
        List<PendingTask> out = new ArrayList<>();
        for (PendingTask task : world.snapshot().pending()) {
            if (task.taskId().equals(taskId)) {
                out.add(task);
            }
        }
        return out;
    }

    private static List<EngineeringRun.Customer.Request> requests(WorldRuntime world,
            String kind) {
        List<EngineeringRun.Customer.Request> out = new ArrayList<>();
        for (EngineeringRun.Customer.Request request : world.customerAudit(A).orElseThrow()
                .requests()) {
            if (request.kind().equals(kind)) {
                out.add(request);
            }
        }
        return out;
    }

    private static BigDecimal cash(WorldRuntime world) {
        return world.snapshot().accounts().get(CUSTOMER).cash();
    }

    /** 合同债权快照：未验收时可能还没有 claim 记录，按零口径读。 */
    private static BigDecimal revenue(WorldRuntime world) {
        EngineeringModel.Claim claim = world.engineeringStateOf(A).orElseThrow()
                .claims().get(C1);
        return claim == null ? new BigDecimal("0.00") : claim.revenue();
    }

    @Test
    void customerRequestOnlyTakesEffectAtFinish() throws Exception {
        Path file = journal("begin-customer");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(baseEvents()));
        governance(world);
        signAndWork(world);
        // 声明工时 200ms：屏障在验收到期前 300ms 真实开始，完成帧才在回应前 100ms 生成请求
        world.scheduleEngineeringAction("req-acc", "req-acc-task", ACCEPT_AT - 100L, A, "dave",
                "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0, 200L);
        long barrier = ACCEPT_AT - 300L;

        world.advanceTo(barrier);
        assertEquals(PendingTask.ENGINEERING_BEGIN_V1, report(world, "req-acc-task").kind(),
                "经理请求先落真实 BEGIN 屏障");
        assertEquals("STARTED", report(world, "req-acc-task").reason(), "屏障只代表真实开始");
        assertTrue(requests(world, "ACCEPTANCE").isEmpty(), "开始不得提前生成客户请求");
        assertEquals(new BigDecimal("0.00"), revenue(world), "开始不得提前确认收入");
        BigDecimal customerCash = cash(world);
        assertEquals(1, pending(world, "req-acc-task:finish").size(), "屏障后必须留完成帧");
        assertNull(report(world, "req-acc-task:finish"), "完成帧还没到期，没有业务报告");

        world.advanceTo(ACCEPT_AT - 100L);
        EngineeringRun.Report finish = report(world, "req-acc-task:finish");
        assertEquals("CUSTOMER_REQUESTED", finish.reason(), "FINISH 才生成客户请求");
        assertTrue(finish.accepted());
        List<EngineeringRun.Customer.Request> acceptance = requests(world, "ACCEPTANCE");
        assertEquals(1, acceptance.size(), "只能有一条验收请求");
        assertFalse(acceptance.get(0).answered(), "冻结客户尚未回应，请求只能等待");
        assertEquals(customerCash, cash(world), "请求本身不动现金");

        // 冻结客户回应到期才真正验收并确认 4000 收入
        world.advanceTo(ACCEPT_AT);
        assertEquals(new BigDecimal("4000.00"), revenue(world), "客户回应才确认收入");
        assertTrue(requests(world, "ACCEPTANCE").get(0).answered(), "回应必须回答该请求");
        assertEquals("acc-1", requests(world, "ACCEPTANCE").get(0).answeredByEventId());
        world.close();
    }

    @Test
    void requestBeginSurvivesReopenBeforeFinish() throws Exception {
        Path file = journal("cross-jvm");
        List<EngineeringRun.CustomerEventPlan> events = baseEvents();
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(events));
        governance(world);
        signAndWork(world);
        // 先真实完成一次验收请求，客户回应后才可能开票
        world.scheduleEngineeringAction("req-acc", "req-acc-task", ACCEPT_AT - 100L, A, "dave",
                "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0, 200L);
        world.advanceTo(ACCEPT_AT - 100L);
        assertEquals("CUSTOMER_REQUESTED", report(world, "req-acc-task:finish").reason(),
                "验收请求必须真实生成");
        world.advanceTo(ACCEPT_AT);
        assertEquals(new BigDecimal("4000.00"), revenue(world), "客户回应后才有收入");
        BigDecimal customerCash = cash(world);
        world.scheduleEngineeringAction("invoice-1", "invoice-1-task", INVOICE_AT, A, "dave",
                "INVOICE", C1, "", 0, 0, BigDecimal.ZERO, "", 0, 100L);
        world.advanceTo(INVOICE_AT + 10L);
        assertEquals("INVOICED", report(world, "invoice-1-task:finish").reason());
        world.scheduleEngineeringAction("req-pay", "req-pay-task", DUE_AT + 100L, A, "dave",
                "REQUEST_PAYMENT", C1, "", 0, 0, BigDecimal.ZERO, "", 0, 500L);
        long barrier = DUE_AT - 400L;
        world.advanceTo(barrier);
        assertEquals("STARTED", report(world, "req-pay-task").reason());
        assertTrue(requests(world, "PAYMENT").isEmpty(), "屏障时还没有付款请求");
        long position = world.snapshot().position();
        world.close();

        // 屏障已过、完成帧未到：跨 JVM 重开必须原样恢复两者
        try (WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file, config(events))) {
            assertEquals(position, reopened.snapshot().position(), "重开不得改写日志位置");
            assertEquals("STARTED", report(reopened, "req-pay-task").reason(),
                    "已开始的事实必须恢复");
            assertTrue(requests(reopened, "PAYMENT").isEmpty(), "重开不得凭空补出请求");
            assertEquals(1, pending(reopened, "req-pay-task:finish").size(),
                    "完成帧必须原样恢复");
            assertEquals(DUE_AT + 100L,
                    pending(reopened, "req-pay-task:finish").get(0).dueTimeMillis(),
                    "完成帧到期不得被改写");
            reopened.advanceTo(DUE_AT + 200L);
            assertEquals("CUSTOMER_REQUESTED", report(reopened, "req-pay-task:finish").reason(),
                    "重开后到期才生成付款请求");
            assertEquals(1, requests(reopened, "PAYMENT").size(), "只生成一条请求");
            long afterFinish = reopened.snapshot().position();
            reopened.close();

            try (WorldRuntime again = WorldWebScenario.engineeringRuntime(file,
                    config(events))) {
                assertEquals(afterFinish, again.snapshot().position());
                assertEquals(1, requests(again, "PAYMENT").size(), "重开不得重复生成请求");
                assertFalse(requests(again, "PAYMENT").get(0).answered(), "没有回应就只能等待");
                assertEquals(customerCash, cash(again), "请求不动客户现金");
            }
        }
    }

    @Test
    void revokedManagerIsRejectedAtBeginAndReplayStaysIdempotent() throws Exception {
        Path file = journal("revoked");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(baseEvents()));
        governance(world);
        signAndWork(world);
        // 先在任职有效期内排程：真实开始时刻 1400 晚于即将生效的撤职时点
        world.scheduleEngineeringAction("late-request", "late-request-task", 1_600L, A, "dave",
                "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0, 200L);
        // 真实撤职：第二次任免会议以 REVOKE 在 1300 生效
        world.scheduleEngineeringAppointmentConvene("rev-c", "rev-c-task", 760L, A, "alice",
                "rev-meeting", 780L, 800L, 900L);
        String[] voters = {"alice", "bob", "carol"};
        for (int i = 0; i < voters.length; i++) {
            world.scheduleEngineeringAttend("rev-a-" + i, "rev-a-" + i + "-task",
                    770L + i, A, voters[i], "rev-meeting", false);
        }
        world.scheduleEngineeringMaterial("rev-m", "rev-m-task", 775L, A, "alice",
                "rev-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("rev-p", "rev-p-task", 780L, A, "alice",
                "rev-meeting", List.of(),
                new BoardModel.AppointmentRequest("mgr-appoint", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.REVOKE,
                        1_300L, null));
        for (int i = 0; i < voters.length; i++) {
            world.scheduleEngineeringVote("rev-v-" + i, "rev-v-" + i + "-task",
                    810L + i, A, voters[i], "rev-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("rev-i", "rev-i-task", 950L, A, "alice",
                "rev-meeting");
        world.advanceTo(950L);
        assertFalse(world.activeManagerForTest("dave", A), "撤职已生效");

        BigDecimal before = cash(world);
        // 屏障在撤职后 1400 真实开始：必须拒绝，且不留完成帧
        world.advanceTo(1_600L);
        EngineeringRun.Report denied = report(world, "late-request-task");
        assertEquals("NOT_AUTHORIZED", denied.reason(), "被撤职的经理不能在真实开始时动手");
        assertFalse(denied.accepted());
        assertNull(report(world, "late-request-task:finish"), "被拒绝不得留完成帧");
        assertTrue(requests(world, "ACCEPTANCE").isEmpty(), "被拒绝不得生成客户请求");
        assertEquals(before, cash(world), "被拒绝不得动客户现金");

        // 同命令重放：幂等，不产生第二个任务/报告，也不改变拒绝结论
        assertEquals("SCHEDULED:late-request-task", world.scheduleEngineeringAction(
                "late-request", "late-request-task", 1_600L, A, "dave",
                "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0, 200L),
                "同命令重放必须返回原排程结果");
        assertTrue(pending(world, "late-request-task").isEmpty(), "重放不得再排屏障");
        assertEquals("NOT_AUTHORIZED", report(world, "late-request-task").reason(),
                "重放不得改写原拒绝结论");
        world.close();
    }

    @Test
    void overlappingManagerActionIsRejectedWithoutSideEffect() throws Exception {
        Path file = journal("focus");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(baseEvents()));
        governance(world);
        signAndWork(world);
        // 同一经理的真实区间 [base+800, base+1000)：后一个重叠动作在任何入口都必须被焦点拒绝
        long base = 1_450L + 40L * DAY;
        world.scheduleEngineeringAction("focus-a", "focus-a-task", base + 1_000L, A, "dave",
                "INVOICE", C1, "", 0, 0, BigDecimal.ZERO, "", 0, 200L);
        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
                () -> world.scheduleEngineeringAction("focus-b", "focus-b-task", base + 950L, A,
                        "dave", "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0, 200L),
                "重叠的正工时动作不得排程成功");
        assertTrue(conflict.getMessage().contains("焦点"), conflict.getMessage());
        assertTrue(pending(world, "focus-b-task").isEmpty(), "被拒绝不得留下任务");
        assertNull(report(world, "focus-b-task"), "被拒绝不得留下报告");
        assertTrue(requests(world, "ACCEPTANCE").isEmpty(), "被拒绝不得生成客户请求");

        // 无重叠窗口里重新发起同一业务请求：真实开始、真实完成
        world.scheduleEngineeringAction("focus-b2", "focus-b2-task", base + 1_200L, A, "dave",
                "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0, 200L);
        world.advanceTo(base + 1_200L);
        assertEquals("STARTED", report(world, "focus-a-task").reason(), "先排动作不受影响");
        assertEquals(base + 1_000L, report(world, "focus-a-task:finish").timeMillis(),
                "先排动作仍在原到期完成");
        assertEquals("CUSTOMER_REQUESTED", report(world, "focus-b2-task:finish").reason(),
                "无重叠后可以真实请求");
        assertEquals(1, requests(world, "ACCEPTANCE").size(), "只生成一条验收请求");
        world.close();
    }

    @Test
    void customerResponseAndBeginFinishCoexistInOneBatch() throws Exception {
        Path file = journal("same-batch");
        List<EngineeringRun.CustomerEventPlan> events = baseEvents();
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(events));
        governance(world);
        signAndWork(world);
        // 验收请求先真实生成，客户回应才在 ACCEPT_AT 回答它
        world.scheduleEngineeringAction("req-acc", "req-acc-task", ACCEPT_AT - 100L, A, "dave",
                "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0, 200L);
        // 同一批次里还有另一条 BEGIN 子任务（付款请求），到 ACCEPT_AT 与客户回应一起触发
        world.scheduleEngineeringAction("req-pay", "req-pay-task", ACCEPT_AT, A, "dave",
                "REQUEST_PAYMENT", C1, "", 0, 0, BigDecimal.ZERO, "", 0, 50L);
        world.advanceTo(ACCEPT_AT - 100L);
        assertEquals("CUSTOMER_REQUESTED", report(world, "req-acc-task:finish").reason(),
                "验收请求必须真实生成");
        // 下一批：客户回应与该 BEGIN 子任务的完成帧同批到期
        world.advanceTo(ACCEPT_AT);
        assertEquals(new BigDecimal("4000.00"), revenue(world), "同批里客户回应必须真实验收");
        List<EngineeringRun.Customer.Request> acceptance = requests(world, "ACCEPTANCE");
        assertEquals(1, acceptance.size(), "同批不得生成两条验收请求");
        assertTrue(acceptance.get(0).answered(), "同批回应必须回答该请求");
        assertEquals("acc-1", acceptance.get(0).answeredByEventId());
        // 未开票就请求付款：完成帧必须留下可解释的业务拒绝，而不是静默吞掉或误拒整批
        EngineeringRun.Report pay = report(world, "req-pay-task:finish");
        assertNotNull(pay, "同批里 BEGIN 子任务必须真实落地");
        assertFalse(pay.accepted(), "未开票不能请求付款");
        assertNotNull(pay.reason(), "业务拒绝必须有原因码");
        long position = world.snapshot().position();
        world.close();

        // 重开逐帧重演同一批：客户事件与 BEGIN 子任务共存不得被误判为伪造
        try (WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file, config(events))) {
            assertEquals(position, reopened.snapshot().position(), "重开位置保持");
            assertEquals(new BigDecimal("4000.00"), revenue(reopened), "重开金额保持");
            assertEquals(1, requests(reopened, "ACCEPTANCE").size(), "重开不重复请求");
            assertTrue(report(reopened, "req-acc-task:finish").accepted(),
                    "重开保持完成帧结论");
            assertEquals(pay.reason(), report(reopened, "req-pay-task:finish").reason(),
                    "重开保持业务拒绝原因");
        }
    }
}
