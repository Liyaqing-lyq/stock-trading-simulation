package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v5 定向：核销后追索只能是「请求」，客户回款必须来自冻结客户回应。
 *
 * <p>覆盖客户有钱但不回应、REJECT 后不得强扣、合法回应 150、共享期间预算不足、
 * 客户现金不足（部分付款）、重复请求/响应不二扣、未来客户策略不进角色投影、
 * 自洽篡改响应来源被逐帧重演拒绝。</p>
 */
class EngineeringRecoveryClaimTest {

    private static final long DAY = EngineeringModel.DAY_MILLIS;
    private static final long WORK_DONE = 1_450L + 100L * DAY;
    private static final long ACCEPT_AT = WORK_DONE + 250L;
    private static final long INVOICE_AT = WORK_DONE + 600L;
    private static final long DUE_AT = INVOICE_AT + 15L * DAY;
    private static final long PAY_AT = DUE_AT + 1_200L;
    private static final long IMPAIR_AT = 120L * DAY;
    private static final long WRITE_OFF_AT = IMPAIR_AT + 1_000L;
    private static final long REC_REQ_AT = IMPAIR_AT + 5_000L;
    private static final long REC_AT = IMPAIR_AT + 6_000L;
    private static final String CONTRACT = "R-1";
    private static final String A = WorldWebScenario.ENGINE_A;

    private static Path journal(String name) throws IOException {
        Path dir = Path.of("target", "engineering-recovery-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static EngineeringRun.ContractPlan contract() {
        return new EngineeringRun.ContractPlan(CONTRACT, new BigDecimal("100.00"),
                new BigDecimal("60.0000"), 2000, 1000, 15, 60, List.of(40L, 60L));
    }

    private static EngineeringRun.CompanySpec aSpec(int periods, String budget) {
        EngineeringRun.CompanySpec base = WorldWebScenario.engineeringConfig().specOf(A);
        return new EngineeringRun.CompanySpec(base.companyPartyId(), base.companyOwnerId(),
                base.companyAccountId(), base.customerAccountId(), base.supplierAccountId(),
                base.opsAccountId(), DAY, 6_000, base.charter(), List.of(contract()),
                new EngineeringRun.CustomerPool("ENG-A-CUS", 30L * DAY, new BigDecimal(budget),
                        periods));
    }

    private static EngineeringRun.CustomerEventPlan event(String id, String kind, long at,
            long units, String amount, String requestKind) {
        boolean payment = List.of("ADVANCE", "PAY", "RETENTION_PAY", "RECOVERY_PAY")
                .contains(kind);
        return new EngineeringRun.CustomerEventPlan(id, A, CONTRACT, 0, kind, at, units,
                amount == null ? BigDecimal.ZERO : new BigDecimal(amount),
                payment ? "ENG-A-CUS" : "", "冻结客户证据：" + id, requestKind);
    }

    private static EngineeringRun.Config config(int periods, String budget,
            List<EngineeringRun.CustomerEventPlan> tail) {
        List<EngineeringRun.CustomerEventPlan> events = new ArrayList<>(List.of(
                event("adv", "ADVANCE", 1_400L, 0, "2000.00", ""),
                event("acc", "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"),
                event("pay", "PAY", PAY_AT, 0, "400.00", "PAYMENT")));
        events.addAll(tail);
        return new EngineeringRun.Config("v5 追索实验参数", List.of(aSpec(periods, budget)),
                Map.of(A, events));
    }

    /** 可指定客户期间长度与**完整**事件表的接缝（用于把两笔付款放进同一期间）。 */
    private static EngineeringRun.Config configWith(long periodLength, int periods, String budget,
            List<EngineeringRun.CustomerEventPlan> events) {
        EngineeringRun.CompanySpec base = WorldWebScenario.engineeringConfig().specOf(A);
        EngineeringRun.CompanySpec spec = new EngineeringRun.CompanySpec(base.companyPartyId(),
                base.companyOwnerId(), base.companyAccountId(), base.customerAccountId(),
                base.supplierAccountId(), base.opsAccountId(), DAY, 6_000, base.charter(),
                List.of(contract()),
                new EngineeringRun.CustomerPool("ENG-A-CUS", periodLength,
                        new BigDecimal(budget), periods));
        return new EngineeringRun.Config("v5 追索实验参数", List.of(spec), Map.of(A, events));
    }

    private static WorldRuntime open(Path file, EngineeringRun.Config config,
            List<CashLedger.OpeningAccount> openings) throws Exception {
        return WorldWebScenario.engineeringRuntime(file, config, openings);
    }

    /** 默认开局：客户现金充足。 */
    private static List<CashLedger.OpeningAccount> richCustomer() {
        return WorldWebScenario.engineeringOpenings();
    }

    /** 客户现金 2450：预收 2000 + 当期 400 后只剩 50，用于部分付款。 */
    private static List<CashLedger.OpeningAccount> poorCustomer(String cash) {
        List<CashLedger.OpeningAccount> list = new ArrayList<>();
        for (CashLedger.OpeningAccount account : WorldWebScenario.engineeringOpenings()) {
            list.add("ENG-A-CUS".equals(account.accountId())
                    ? new CashLedger.OpeningAccount(account.accountId(), account.ownerId(),
                            new BigDecimal(cash), account.source())
                    : account);
        }
        return list;
    }

    private static void publish(WorldRuntime world, String docId, long at, String reader)
            throws Exception {
        world.schedulePublishDocument("pub-" + docId, "pub-" + docId + "-task", at, docId,
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "资料 " + docId, "实验资料 " + docId, "实验材料", 10L, List.of());
    }

    /** 真实五席预算 + 任免（dave 为经理），推进到任职生效。 */
    private static void governance(WorldRuntime world) throws Exception {
        world.schedulePublishDocument("pub-mat", "pub-mat-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        publish(world, "engineering-opportunity:" + CONTRACT, 10L, "dave");
        int index = 0;
        for (String reader : List.of("alice", "bob", "carol", "dave", "erin")) {
            world.scheduleStartReading("read-mat-" + reader, "read-mat-" + reader + "-task",
                    20L + index * 2L, reader, "b-material", 1L);
            world.scheduleStartReading("read-appt-" + reader, "read-appt-" + reader + "-task",
                    60L + index * 2L, reader, "appt-material", 1L);
            index++;
        }
        world.scheduleStartReading("read-opp", "read-opp-task", 100L, "dave",
                "engineering-opportunity:" + CONTRACT, 1L);
        world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L, A, "alice",
                "budget-meeting", 150L, 200L, 300L);
        String[] persons = {"alice", "bob", "carol", "dave", "erin"};
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
        String meetingId = "mgr-meeting";
        world.scheduleEngineeringAppointmentConvene(meetingId + "-c", meetingId + "-c-task",
                400L, A, "alice", meetingId, 450L, 500L, 600L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend(meetingId + "-a-" + i,
                    meetingId + "-a-" + i + "-task", 410L + i, A, persons[i], meetingId, false);
        }
        world.scheduleEngineeringMaterial(meetingId + "-m", meetingId + "-m-task", 430L, A,
                "alice", meetingId, "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal(meetingId + "-p", meetingId + "-p-task",
                440L, A, "alice", meetingId, List.of(),
                new BoardModel.AppointmentRequest("mgr-a", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        700L, 900L * DAY));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote(meetingId + "-v-" + i,
                    meetingId + "-v-" + i + "-task", 520L + i, A, persons[i], meetingId,
                    BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement(meetingId + "-i", meetingId + "-i-task", 620L, A,
                "alice", meetingId);
        world.advanceTo(700L);
    }

    /** 签约 → 预收 → 开工 → 验收 → 开票 → 付 400 → 计提/核销 600：备查 600。 */
    private static void runToWriteOff(WorldRuntime world) throws Exception {
        world.scheduleEngineeringAction("sign", "sign-task", 1_350L, A, "dave", "SIGN",
                CONTRACT, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("start", "start-task", 1_450L, A, "dave",
                "START_WORK", CONTRACT, "W-1", 0, 100, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("req-acc", "req-acc-task", WORK_DONE + 150L, A, "dave",
                "REQUEST_ACCEPTANCE", CONTRACT, "", 0, 40, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("invoice", "invoice-task", INVOICE_AT, A, "dave",
                "INVOICE", CONTRACT, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("req-pay", "req-pay-task", DUE_AT + 100L, A, "dave",
                "REQUEST_PAYMENT", CONTRACT, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("impair", "impair-task", IMPAIR_AT, A, "dave",
                "IMPAIR", CONTRACT, "", 0, 0, new BigDecimal("600.00"), "", 0);
        world.scheduleEngineeringAction("write-off", "write-off-task", WRITE_OFF_AT, A, "dave",
                "WRITE_OFF", CONTRACT, "", 0, 0, new BigDecimal("600.00"), "", 0);
        world.advanceTo(WRITE_OFF_AT + 100L);
    }

    private static EngineeringRun.Report report(WorldRuntime world, String taskId) {
        return world.snapshot().engineering().orElseThrow().reports().get(taskId);
    }

    private static BigDecimal memo(WorldRuntime world) {
        return world.engineeringStateOf(A).orElseThrow().claims().get(CONTRACT).memo();
    }

    private static BigDecimal cash(WorldRuntime world, String account) {
        return world.snapshot().accounts().get(account).cash();
    }

    @Test
    void managerRecoverOnlyRequestsAndNeverDeducts() throws Exception {
        Path file = journal("request-only");
        WorldRuntime world = open(file, config(6, "5000.00", List.of()), richCustomer());
        governance(world);
        runToWriteOff(world);
        BigDecimal before = cash(world, "ENG-A-CUS");
        world.scheduleEngineeringAction("claim", "claim-task", REC_REQ_AT, A, "dave", "RECOVER",
                CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_REQ_AT + 1_000L);
        assertEquals("STARTED", report(world, "claim-task").reason(), "屏障只代表真实开始");
        EngineeringRun.Report claim = report(world, "claim-task:finish");
        assertEquals("RECOVERY_REQUESTED", claim.reason(), "经理只能提出追索请求");
        assertTrue(claim.accepted());
        assertEquals(before, cash(world, "ENG-A-CUS"), "请求不能动客户现金");
        assertEquals(new BigDecimal("600.00"), memo(world), "请求不能改备查");
        WorldRuntime.CustomerAudit audit = world.customerAudit(A).orElseThrow();
        assertTrue(audit.payments().stream().noneMatch(p -> "RECOVERY".equals(p.kind())),
                "没有客户回款");
        EngineeringRun.Customer.Request request = audit.requests().stream()
                .filter(r -> "RECOVERY".equals(r.kind())).findFirst().orElseThrow();
        assertFalse(request.answered(), "客户未回应，请求保持等待");
        assertEquals(new BigDecimal("150.00"), request.amount());
        world.close();
    }

    @Test
    void rejectThenNewRequestStillDoesNotDeduct() throws Exception {
        Path file = journal("reject");
        WorldRuntime world = open(file, config(6, "5000.00", List.of(
                event("rec-reject", "REJECT", REC_AT, 0, null, "RECOVERY"))), richCustomer());
        governance(world);
        runToWriteOff(world);
        BigDecimal before = cash(world, "ENG-A-CUS");
        world.scheduleEngineeringAction("claim-1", "claim-1-task", REC_REQ_AT, A, "dave",
                "RECOVER", CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_AT + 100L);
        assertEquals(new BigDecimal("600.00"), memo(world), "客户拒付不能消除备查");
        assertEquals(before, cash(world, "ENG-A-CUS"));
        assertTrue(world.customerAudit(A).orElseThrow().requests().stream()
                .anyMatch(r -> "RECOVERY".equals(r.kind()) && r.answered()), "拒付已回答请求");
        // 经理再发一次追索请求也绝不立即扣款
        world.scheduleEngineeringAction("claim-2", "claim-2-task", REC_AT + 500L, A, "dave",
                "RECOVER", CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_AT + 600L);
        assertEquals(before, cash(world, "ENG-A-CUS"), "换用追索工具也不能强收");
        assertEquals(new BigDecimal("600.00"), memo(world));
        world.close();
    }

    @Test
    void customerResponseAtDueRecoversExactlyOnce() throws Exception {
        Path file = journal("recovered");
        WorldRuntime world = open(file, config(6, "5000.00", List.of(
                event("rec-ok", "RECOVERY_PAY", REC_AT, 0, "150.00", "RECOVERY"))),
                richCustomer());
        governance(world);
        runToWriteOff(world);
        BigDecimal customerBefore = cash(world, "ENG-A-CUS");
        BigDecimal companyBefore = cash(world, "ENG-A");
        world.scheduleEngineeringAction("claim", "claim-task", REC_REQ_AT, A, "dave", "RECOVER",
                CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_AT + 100L);
        EngineeringRun.Report response = report(world, "customer:rec-ok");
        assertNotNull(response);
        assertTrue(response.accepted());
        assertEquals("RECOVERED", response.reason());
        assertEquals(new BigDecimal("450.00"), memo(world), "备查 600 − 150");
        assertEquals(customerBefore.subtract(new BigDecimal("150.00")),
                cash(world, "ENG-A-CUS"));
        assertEquals(companyBefore.add(new BigDecimal("150.00")), cash(world, "ENG-A"));
        WorldRuntime.CustomerAudit audit = world.customerAudit(A).orElseThrow();
        assertTrue(audit.payments().stream().anyMatch(p -> "RECOVERY".equals(p.kind())
                && p.amount().compareTo(new BigDecimal("150.00")) == 0));
        assertTrue(audit.responses().stream().anyMatch(r -> "rec-ok".equals(r.eventId())
                && "PAID".equals(r.outcome())));
        world.close();
    }

    @Test
    void sharedPeriodBudgetExhaustedKeepsClaim() throws Exception {
        Path file = journal("budget");
        // 冻结期间数 4：回收发生在第 4 期（已关闭），该期没有预算
        WorldRuntime world = open(file, config(4, "5000.00", List.of(
                event("rec-budget", "RECOVERY_PAY", REC_AT, 0, "150.00", "RECOVERY"))),
                richCustomer());
        governance(world);
        runToWriteOff(world);
        BigDecimal before = cash(world, "ENG-A-CUS");
        world.scheduleEngineeringAction("claim", "claim-task", REC_REQ_AT, A, "dave", "RECOVER",
                CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_AT + 100L);
        assertEquals("CUSTOMER_BUDGET_EXHAUSTED", report(world, "customer:rec-budget").reason(),
                "共享期间预算不足必须拒绝");
        assertEquals(before, cash(world, "ENG-A-CUS"));
        assertEquals(new BigDecimal("600.00"), memo(world), "未清债继续留在备查");
        world.close();
    }

    @Test
    void poolCashShortfallPaysPartialAndKeepsRemainder() throws Exception {
        Path file = journal("cash");
        WorldRuntime world = open(file, config(6, "5000.00", List.of(
                event("rec-cash", "RECOVERY_PAY", REC_AT, 0, "150.00", "RECOVERY"))),
                poorCustomer("2450.00"));
        governance(world);
        runToWriteOff(world);
        assertEquals(new BigDecimal("50.00"), cash(world, "ENG-A-CUS"), "预收+当期后仅剩 50");
        world.scheduleEngineeringAction("claim", "claim-task", REC_REQ_AT, A, "dave", "RECOVER",
                CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_AT + 100L);
        assertEquals(new BigDecimal("0.00"), cash(world, "ENG-A-CUS"), "只能收到客户真实现金");
        assertEquals(new BigDecimal("550.00"), memo(world), "备查 600 − 50 部分付款");
        WorldRuntime.CustomerAudit audit = world.customerAudit(A).orElseThrow();
        assertTrue(audit.responses().stream().anyMatch(r -> "rec-cash".equals(r.eventId())
                && "PARTIAL".equals(r.outcome())), audit.responses().toString());
        assertTrue(audit.payments().stream().anyMatch(p -> "RECOVERY".equals(p.kind())
                && p.amount().compareTo(new BigDecimal("50.00")) == 0));
        world.close();
    }

    @Test
    void duplicateRequestAndResponseNeverDoubleCharge() throws Exception {
        Path file = journal("duplicate");
        WorldRuntime world = open(file, config(6, "5000.00", List.of(
                event("rec-dup", "RECOVERY_PAY", REC_AT, 0, "150.00", "RECOVERY"))),
                richCustomer());
        governance(world);
        runToWriteOff(world);
        BigDecimal before = cash(world, "ENG-A-CUS");
        world.scheduleEngineeringAction("claim-1", "claim-1-task", REC_REQ_AT, A, "dave",
                "RECOVER", CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.scheduleEngineeringAction("claim-dup", "claim-dup-task", REC_REQ_AT + 100L, A,
                "dave", "RECOVER", CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_AT + 100L);
        assertEquals("REQUEST_PENDING", report(world, "claim-dup-task:finish").reason(),
                "同一合同只能有一条未回答追索请求");
        assertEquals(1, world.customerAudit(A).orElseThrow().payments().stream()
                .filter(p -> "RECOVERY".equals(p.kind())).count(), "响应只记一次");
        assertEquals(before.subtract(new BigDecimal("150.00")), cash(world, "ENG-A-CUS"));
        long position = world.snapshot().position();
        world.close();
        try (WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file,
                config(6, "5000.00", List.of(
                        event("rec-dup", "RECOVERY_PAY", REC_AT, 0, "150.00", "RECOVERY"))))) {
            assertEquals(position, reopened.snapshot().position());
            assertEquals(before.subtract(new BigDecimal("150.00")),
                    cash(reopened, "ENG-A-CUS"), "重开不得二次扣款");
            assertEquals(new BigDecimal("450.00"), memo(reopened));
        }
    }

    @Test
    void futureRecoveryPolicyIsNotInRoleInput() throws Exception {
        Path file = journal("policy");
        WorldRuntime world = open(file, config(6, "5000.00", List.of(
                event("rec-future", "RECOVERY_PAY", REC_AT, 0, "150.00", "RECOVERY"))),
                richCustomer());
        governance(world);
        runToWriteOff(world);
        world.scheduleEngineeringAction("claim", "claim-task", REC_REQ_AT, A, "dave", "RECOVER",
                CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_REQ_AT + 10L);
        String input = world.engineeringRoleInput("dave").toString();
        assertFalse(input.contains("rec-future"), "未来客户响应事件不得进入角色投影：" + input);
        assertFalse(input.contains("RECOVERY_PAY"), "不得泄露客户未来动作类型：" + input);
        assertTrue(input.contains("\"recoveryRequests\""), "本人追索请求应可见");
        assertTrue(input.contains("\"memo\":\"600.00\""), "备查余额应可见：" + input);
        world.close();
    }

    @Test
    void customerInitiatedRepaymentNeedsNoManagerRequest() throws Exception {
        Path file = journal("self-initiated");
        // 客户自发的冻结还款事实（未绑定任何请求）：仍必须核备查/预算/现金
        WorldRuntime world = open(file, config(6, "5000.00", List.of(
                event("rec-self", "RECOVERY_PAY", REC_AT, 0, "100.00", ""))), richCustomer());
        governance(world);
        runToWriteOff(world);
        BigDecimal before = cash(world, "ENG-A-CUS");
        world.advanceTo(REC_AT + 100L);
        assertEquals("RECOVERED", report(world, "customer:rec-self").reason());
        assertEquals(new BigDecimal("500.00"), memo(world), "备查 600 − 客户自发还款 100");
        assertEquals(before.subtract(new BigDecimal("100.00")), cash(world, "ENG-A-CUS"));
        assertTrue(world.customerAudit(A).orElseThrow().requests().stream()
                .noneMatch(r -> "RECOVERY".equals(r.kind())), "自发还款不需要经理请求");
        world.close();
    }

    @Test
    void cashShortfallResponseAnswersClaimAndAllowsNewClaim() throws Exception {
        Path file = journal("answer-cash");
        WorldRuntime world = open(file, configWith(30L * DAY, 6, "5000.00", List.of(
                event("adv", "ADVANCE", 1_400L, 0, "2000.00", ""),
                event("acc", "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"),
                event("rec-poor", "RECOVERY_PAY", REC_AT, 0, "150.00", "RECOVERY"))),
                poorCustomer("0.00"));
        governance(world);
        runToWriteOff(world);
        BigDecimal memoBefore = memo(world);
        assertTrue(memoBefore.signum() > 0, "核销后备查必须存在");
        assertEquals(0, cash(world, "ENG-A-CUS").signum(), "客户账户没有现金");

        world.scheduleEngineeringAction("claim-1", "claim-1-task", REC_REQ_AT, A, "dave",
                "RECOVER", CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_AT + 100L);
        assertEquals("INSUFFICIENT_CASH", report(world, "customer:rec-poor").reason(),
                "现金不足也是客户的真实回应");
        WorldRuntime.CustomerAudit audit = world.customerAudit(A).orElseThrow();
        List<EngineeringRun.Customer.Request> recovery = new ArrayList<>();
        for (EngineeringRun.Customer.Request request : audit.requests()) {
            if ("RECOVERY".equals(request.kind())) {
                recovery.add(request);
            }
        }
        assertEquals(1, recovery.size());
        assertTrue(recovery.get(0).answered(), "真实（含失败）回应必须回答已匹配请求");
        assertEquals("rec-poor", recovery.get(0).answeredByEventId());
        assertEquals(memoBefore, memo(world), "被拒回应不得改备查");
        assertEquals(0, cash(world, "ENG-A-CUS").signum(), "被拒回应不得动客户现金");
        assertTrue(audit.payments().stream().noneMatch(p -> "RECOVERY".equals(p.kind())),
                "被拒回应不得产生付款");

        // 关键回归：该键不能因为“客户现在没钱”被永久占住
        world.scheduleEngineeringAction("claim-2", "claim-2-task", REC_REQ_AT + 2_000L, A, "dave",
                "RECOVER", CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_REQ_AT + 2_100L);
        assertEquals("RECOVERY_REQUESTED", report(world, "claim-2-task:finish").reason(),
                "被回应后可用新命令再次追索");
        WorldRuntime.CustomerAudit after = world.customerAudit(A).orElseThrow();
        List<EngineeringRun.Customer.Request> retried = new ArrayList<>();
        for (EngineeringRun.Customer.Request request : after.requests()) {
            if ("RECOVERY".equals(request.kind())) {
                retried.add(request);
            }
        }
        assertEquals(2, retried.size(), "旧请求保留，新请求另立");
        assertTrue(retried.get(0).answered(), "旧请求仍保持已答");
        assertFalse(retried.get(1).answered(), "新请求只能等待");
        assertTrue(!retried.get(0).requestId().equals(retried.get(1).requestId()));
        boolean keptOldResponse = after.responses().stream().anyMatch(r ->
                "rec-poor".equals(r.eventId()) && "INSUFFICIENT_CASH".equals(r.reason()));
        assertTrue(keptOldResponse, "旧响应必须原样留存");
        world.close();
    }

    @Test
    void budgetExhaustedPaymentAnswersRequestAndAllowsRetryNextPeriod() throws Exception {
        Path file = journal("answer-budget");
        // 客户期间 200 天：预收 2000 与到期付款落在同一期，把期预算 2000 用完
        WorldRuntime world = open(file, configWith(200L * DAY, 6, "2000.00", List.of(
                event("adv", "ADVANCE", 1_400L, 0, "2000.00", ""),
                event("acc", "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"),
                event("pay", "PAY", PAY_AT, 0, "400.00", "PAYMENT"))), richCustomer());
        governance(world);
        runToWriteOff(world);
        assertEquals("CUSTOMER_BUDGET_EXHAUSTED", report(world, "customer:pay").reason(),
                "预算耗尽时客户付款只能被拒");
        assertEquals("CUSTOMER_REQUESTED", report(world, "req-pay-task:finish").reason(),
                "付款请求本身是合法意图");
        WorldRuntime.CustomerAudit audit = world.customerAudit(A).orElseThrow();
        EngineeringRun.Customer.Request payRequest = null;
        for (EngineeringRun.Customer.Request request : audit.requests()) {
            if ("PAYMENT".equals(request.kind())) {
                payRequest = request;
            }
        }
        assertNotNull(payRequest);
        assertTrue(payRequest.answered(), "预算不足的真实回应也必须回答请求");
        assertEquals("pay", payRequest.answeredByEventId());
        assertTrue(audit.payments().stream().noneMatch(p -> "CURRENT".equals(p.kind())),
                "被拒回应不得产生当期付款");

        // 下一期预算恢复后：新请求真实受理，不再 REQUEST_PENDING
        long nextPeriod = PAY_AT + 201L * DAY;
        world.advanceTo(nextPeriod);
        WorldRuntime.CustomerAudit refreshed = world.customerAudit(A).orElseThrow();
        assertTrue(refreshed.periodBudgetLeft() != null
                && refreshed.periodBudgetLeft().signum() > 0, "新一期预算已恢复");
        world.scheduleEngineeringAction("req-pay-2", "req-pay-2-task", nextPeriod + 100L, A, "dave",
                "REQUEST_PAYMENT", CONTRACT, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.advanceTo(nextPeriod + 200L);
        assertEquals("CUSTOMER_REQUESTED", report(world, "req-pay-2-task:finish").reason(),
                "预算恢复后新命令可再请求");
        List<EngineeringRun.Customer.Request> payments = new ArrayList<>();
        for (EngineeringRun.Customer.Request request
                : world.customerAudit(A).orElseThrow().requests()) {
            if ("PAYMENT".equals(request.kind())) {
                payments.add(request);
            }
        }
        assertEquals(2, payments.size(), "旧请求保留，新请求另立");
        assertTrue(payments.get(0).answered());
        assertFalse(payments.get(1).answered(), "新请求没有对应回应只能等待");
        world.close();
    }

    @Test
    void unmatchedOrEarlyResponseNeverAnswersFutureRequest() throws Exception {
        Path file = journal("answer-unmatched");
        // 客户付款事件在经理提出请求之前到达：不得绑定或回答后来的请求
        WorldRuntime world = open(file, configWith(30L * DAY, 6, "5000.00", List.of(
                event("adv", "ADVANCE", 1_400L, 0, "2000.00", ""),
                event("acc", "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"),
                event("pay-early", "PAY", DUE_AT + 50L, 0, "400.00", "PAYMENT"))),
                richCustomer());
        governance(world);
        runToWriteOff(world);
        assertEquals("UNMATCHED_REQUEST", report(world, "customer:pay-early").reason(),
                "没有未回答请求就没有匹配");
        EngineeringRun.Customer.Request payRequest = null;
        for (EngineeringRun.Customer.Request request
                : world.customerAudit(A).orElseThrow().requests()) {
            if ("PAYMENT".equals(request.kind())) {
                payRequest = request;
            }
        }
        assertNotNull(payRequest, "请求在事件之后才真实建立");
        assertFalse(payRequest.answered(), "早于请求的回应不得误答未来请求");
        assertTrue(payRequest.atMillis() > DUE_AT + 50L, "请求确实晚于该回应");
        world.close();
    }

    @Test
    void replayAfterRejectedResponseIsIdempotent() throws Exception {
        Path file = journal("answer-replay");
        WorldRuntime world = open(file, configWith(30L * DAY, 6, "5000.00", List.of(
                event("adv", "ADVANCE", 1_400L, 0, "2000.00", ""),
                event("acc", "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"),
                event("rec-poor", "RECOVERY_PAY", REC_AT, 0, "150.00", "RECOVERY"))),
                poorCustomer("0.00"));
        governance(world);
        runToWriteOff(world);
        world.scheduleEngineeringAction("claim-1", "claim-1-task", REC_REQ_AT, A, "dave",
                "RECOVER", CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_AT + 100L);
        assertEquals("SCHEDULED:claim-1-task", world.scheduleEngineeringAction("claim-1",
                "claim-1-task", REC_REQ_AT, A, "dave", "RECOVER", CONTRACT, "", 0, 0,
                new BigDecimal("150.00"), "", 0), "同命令重放按原结果返回");
        WorldRuntime.CustomerAudit audit = world.customerAudit(A).orElseThrow();
        assertEquals(1, audit.requests().stream()
                .filter(r -> "RECOVERY".equals(r.kind())).count(), "重放不得产生第二条请求");
        assertEquals(1, audit.responses().stream()
                .filter(r -> "rec-poor".equals(r.eventId())).count(), "重放不得重复回应");
        assertEquals("INSUFFICIENT_CASH", report(world, "customer:rec-poor").reason(),
                "重放不得改写原回应结论");
        world.close();
    }

    @Test
    void tamperedRecoveryResponseIsRejected() throws Exception {
        Path base = journal("tamper-base");
        WorldRuntime world = open(base, config(6, "5000.00", List.of(
                event("rec-tamper", "RECOVERY_PAY", REC_AT, 0, "150.00", "RECOVERY"))),
                richCustomer());
        governance(world);
        runToWriteOff(world);
        world.scheduleEngineeringAction("claim", "claim-task", REC_REQ_AT, A, "dave", "RECOVER",
                CONTRACT, "", 0, 0, new BigDecimal("150.00"), "", 0);
        world.advanceTo(REC_AT + 100L);
        world.close();

        Path variant = journal("tamper");
        Files.copy(base, variant);
        tamperRecoveryPayment(variant);
        assertThrows(IOException.class, () -> WorldWebScenario.engineeringRuntime(variant,
                config(6, "5000.00", List.of(
                        event("rec-tamper", "RECOVERY_PAY", REC_AT, 0, "150.00",
                                "RECOVERY")))), "自洽篡改客户回收来源必须被逐帧重演拒绝");
    }

    private static void tamperRecoveryPayment(Path file) throws IOException {
        List<byte[]> frames = readFrames(file);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        int index = frames.size() - 1;
        com.fasterxml.jackson.databind.node.ObjectNode batch =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(frames.get(index));
        com.fasterxml.jackson.databind.node.ArrayNode events =
                (com.fasterxml.jackson.databind.node.ArrayNode) batch.get("events");
        boolean touched = false;
        for (com.fasterxml.jackson.databind.JsonNode event : events) {
            if (!"STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                continue;
            }
            com.fasterxml.jackson.databind.node.ObjectNode snapshot =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                            event.get("payload").asText());
            com.fasterxml.jackson.databind.node.ArrayNode payments =
                    (com.fasterxml.jackson.databind.node.ArrayNode) snapshot.get("engineering")
                            .get("customer").get("payments");
            if (payments.isEmpty()) {
                throw new IllegalArgumentException("该帧没有客户付款");
            }
            for (com.fasterxml.jackson.databind.JsonNode payment : payments) {
                if ("RECOVERY".equals(payment.get("kind").asText())) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) payment)
                            .put("amount", new BigDecimal("900.00"));
                    touched = true;
                }
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) event)
                    .put("payload", mapper.writeValueAsString(snapshot));
        }
        if (!touched) {
            throw new IllegalArgumentException("该帧没有回收付款");
        }
        frames.set(index, mapper.writeValueAsBytes(batch));
        Files.write(file, frameBytes(frames));
    }

    private static List<byte[]> readFrames(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            int length = ByteBuffer.wrap(bytes, offset, 4).getInt();
            int inverse = ByteBuffer.wrap(bytes, offset + 4, 4).getInt();
            if (length <= 0 || inverse != ~length || offset + 8 + length + 32 > bytes.length) {
                throw new IOException("帧格式不合法：" + offset);
            }
            frames.add(Arrays.copyOfRange(bytes, offset + 8, offset + 8 + length));
            offset += 8 + length + 32;
        }
        return frames;
    }

    private static byte[] frameBytes(List<byte[]> frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] payload : frames) {
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length)
                    .array());
            out.write(payload);
            out.write(sha256(payload));
        }
        return out.toByteArray();
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("缺少 SHA-256", impossible);
        }
    }
}
