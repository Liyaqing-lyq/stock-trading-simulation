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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v6 定向：客户请求「同时最多一条未回答」，但被回应（含 REJECT/DELAY/PARTIAL）后
 * 真实人物可用新命令再请求，新请求得到确定性新序号，不覆盖旧请求/旧响应。
 *
 * <p>有限冻结计划不变：响应事件仍然有限、不自动重试、不生成新客户事件；
 * 没有对应冻结响应时新请求只能真实等待，绝不假装客户同意。</p>
 */
class EngineeringRequestRetryTest {

    private static final long DAY = EngineeringModel.DAY_MILLIS;
    private static final long WORK_DONE = 1_450L + 100L * DAY;
    private static final long ACCEPT_AT = WORK_DONE + 250L;
    private static final long INVOICE_AT = WORK_DONE + 600L;
    private static final long DUE_AT = INVOICE_AT + 15L * DAY;
    private static final long PAY_AT = DUE_AT + 1_200L;
    private static final String C1 = "T-1";
    private static final String C2 = "T-2";
    private static final String A = WorldWebScenario.ENGINE_A;

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-retry-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static EngineeringRun.ContractPlan contract(String id) {
        return new EngineeringRun.ContractPlan(id, new BigDecimal("100.00"),
                new BigDecimal("60.0000"), 2000, 1000, 15, 60, List.of(40L, 60L));
    }

    private static EngineeringRun.CompanySpec aSpec(List<String> contractIds) {
        EngineeringRun.CompanySpec base = WorldWebScenario.engineeringConfig().specOf(A);
        List<EngineeringRun.ContractPlan> contracts = new ArrayList<>();
        for (String id : contractIds) {
            contracts.add(contract(id));
        }
        return new EngineeringRun.CompanySpec(base.companyPartyId(), base.companyOwnerId(),
                base.companyAccountId(), base.customerAccountId(), base.supplierAccountId(),
                base.opsAccountId(), DAY, 6_000, base.charter(), contracts,
                new EngineeringRun.CustomerPool("ENG-A-CUS", 30L * DAY,
                        new BigDecimal("5000.00"), 6));
    }

    private static EngineeringRun.CustomerEventPlan event(String id, String contract,
            String kind, long at, long units, String amount, String requestKind) {
        boolean payment = List.of("ADVANCE", "PAY", "RETENTION_PAY", "RECOVERY_PAY")
                .contains(kind);
        return new EngineeringRun.CustomerEventPlan(id, A, contract, 0, kind, at, units,
                amount == null ? BigDecimal.ZERO : new BigDecimal(amount),
                payment ? "ENG-A-CUS" : "", "冻结客户证据：" + id, requestKind);
    }

    private static EngineeringRun.Config config(List<String> contracts,
            List<EngineeringRun.CustomerEventPlan> events) {
        return new EngineeringRun.Config("v6 请求重试实验参数", List.of(aSpec(contracts)),
                Map.of(A, events));
    }

    /** 真实五席预算 + 任免（dave 任经理），同时发布/阅读各合同机会。 */
    private static void governance(WorldRuntime world, List<String> contracts) throws Exception {
        world.schedulePublishDocument("pub-mat", "pub-mat-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        for (String contract : contracts) {
            world.schedulePublishDocument("pub-opp-" + contract, "pub-opp-" + contract + "-task",
                    10L, "engineering-opportunity:" + contract, WorldWebScenario.INVESTOR,
                    InformationModel.Audience.direct("dave"), contract + " 机会",
                    "实验机会 " + contract, "冻结机会", 10L, List.of());
        }
        int index = 0;
        for (String reader : List.of("alice", "bob", "carol", "dave", "erin")) {
            world.scheduleStartReading("read-mat-" + reader, "read-mat-" + reader + "-task",
                    20L + index * 2L, reader, "b-material", 1L);
            world.scheduleStartReading("read-appt-" + reader, "read-appt-" + reader + "-task",
                    60L + index * 2L, reader, "appt-material", 1L);
            index++;
        }
        int readAt = 100;
        for (String contract : contracts) {
            world.scheduleStartReading("read-opp-" + contract, "read-opp-" + contract + "-task",
                    readAt, "dave", "engineering-opportunity:" + contract, 1L);
            readAt += 20;
        }
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

    /** 签约 + 开工（40 单位，够第一段验收）。 */
    private static void signAndWork(WorldRuntime world, String contract, long signAt)
            throws Exception {
        world.scheduleEngineeringAction("sign-" + contract, "sign-" + contract + "-task",
                signAt, A, "dave", "SIGN", contract, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("work-" + contract, "work-" + contract + "-task",
                signAt + 50L, A, "dave", "START_WORK", contract, "W-" + contract, 0, 100,
                BigDecimal.ZERO, "", 0);
    }

    private static EngineeringRun.Report report(WorldRuntime world, String taskId) {
        return world.snapshot().engineering().orElseThrow().reports().get(taskId);
    }

    private static EngineeringModel.Contract contractState(WorldRuntime world, String id) {
        return world.engineeringStateOf(A).orElseThrow().contracts().get(id);
    }

    private static BigDecimal cash(WorldRuntime world) {
        return world.snapshot().accounts().get("ENG-A-CUS").cash();
    }

    /** 提高公司开局现金：两份合同都要付 3600 人工。 */
    private static List<CashLedger.OpeningAccount> richerCompany(String companyCash) {
        List<CashLedger.OpeningAccount> list = new ArrayList<>();
        for (CashLedger.OpeningAccount account : WorldWebScenario.engineeringOpenings()) {
            list.add("ENG-A".equals(account.accountId())
                    ? new CashLedger.OpeningAccount(account.accountId(), account.ownerId(),
                            new BigDecimal(companyCash), account.source())
                    : account);
        }
        return list;
    }

    private static List<EngineeringRun.Customer.Request> requests(WorldRuntime world, String kind) {
        List<EngineeringRun.Customer.Request> out = new ArrayList<>();
        for (EngineeringRun.Customer.Request request : world.customerAudit(A).orElseThrow()
                .requests()) {
            if (request.kind().equals(kind)) {
                out.add(request);
            }
        }
        return out;
    }

    @Test
    void rejectedPaymentCanBeRequestedAgainWithoutCash() throws Exception {
        Path file = journal("reject-retry");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(List.of(C1),
                List.of(event("adv", C1, "ADVANCE", 1_400L, 0, "2000.00", ""),
                        event("acc", C1, "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"),
                        event("pay-reject", C1, "REJECT", PAY_AT, 0, null, "PAYMENT"))));
        governance(world, List.of(C1));
        signAndWork(world, C1, 1_350L);
        world.scheduleEngineeringAction("req-acc", "req-acc-task", ACCEPT_AT - 100L, A, "dave",
                "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("invoice", "invoice-task", INVOICE_AT, A, "dave",
                "INVOICE", C1, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("req-pay-1", "req-pay-1-task", DUE_AT + 100L, A, "dave",
                "REQUEST_PAYMENT", C1, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("req-pay-2", "req-pay-2-task", PAY_AT + 500L, A, "dave",
                "REQUEST_PAYMENT", C1, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.advanceTo(PAY_AT + 600L);

        EngineeringRun.Customer.Response reject = world.customerAudit(A).orElseThrow()
                .responses().stream().filter(r -> "pay-reject".equals(r.eventId()))
                .findFirst().orElseThrow();
        assertEquals("REJECTED", reject.outcome(), "客户真实拒绝：" + reject);
        List<EngineeringRun.Customer.Request> payments = requests(world, "PAYMENT");
        assertEquals(2, payments.size(), "拒绝后允许用新命令再次请求：" + payments);
        assertTrue(payments.get(0).answered(), "第一条已被客户回应");
        assertEquals("pay-reject", payments.get(0).answeredByEventId());
        assertFalse(payments.get(1).answered(), "新请求只能真实等待");
        assertNotEquals(payments.get(0).requestId(), payments.get(1).requestId());
        assertTrue(payments.get(1).requestId().startsWith(payments.get(0).requestId() + ":")
                || payments.get(1).requestId().endsWith(":2"), payments.get(1).requestId());
        assertEquals("STARTED", report(world, "req-pay-2-task").reason(), "屏障只代表真实开始");
        assertEquals("CUSTOMER_REQUESTED", report(world, "req-pay-2-task:finish").reason());
        // 没有对应冻结响应 → 不扣款、不验收、应收不变
        assertEquals(new BigDecimal("1000.00"), contractState(world, C1).invoices().get(0)
                .currentOutstanding(), "拒绝后再请求不得动应收");
        assertEquals(new BigDecimal("2000.00"), contractState(world, C1).advanceReceived());
        world.close();
    }

    private static void assertNotEquals(Object first, Object second) {
        assertFalse(first.equals(second), "请求标识必须不同：" + first + " / " + second);
    }

    @Test
    void delayedAcceptanceSecondRequestMatchesNextResponse() throws Exception {
        Path file = journal("delay-accept");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(List.of(C1),
                List.of(event("adv", C1, "ADVANCE", 1_400L, 0, "2000.00", ""),
                        event("delay", C1, "DELAY", ACCEPT_AT, 0, null, "ACCEPTANCE"),
                        event("acc2", C1, "ACCEPT", ACCEPT_AT + 500L, 40L, null,
                                "ACCEPTANCE"))));
        governance(world, List.of(C1));
        signAndWork(world, C1, 1_350L);
        world.scheduleEngineeringAction("req-acc-1", "req-acc-1-task", ACCEPT_AT - 100L, A,
                "dave", "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("req-acc-2", "req-acc-2-task", ACCEPT_AT + 100L, A,
                "dave", "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0);
        world.advanceTo(ACCEPT_AT + 600L);
        List<EngineeringRun.Customer.Request> accepts = requests(world, "ACCEPTANCE");
        assertEquals(2, accepts.size(), accepts.toString());
        assertEquals("delay", accepts.get(0).answeredByEventId());
        assertEquals("acc2", accepts.get(1).answeredByEventId(), "第二条请求匹配下一条冻结回应");
        assertEquals(new BigDecimal("4000.00"), world.engineeringStateOf(A).orElseThrow()
                .claims().get(C1).revenue(), "只有一次真实验收，收入不重复");
        assertEquals(1, world.customerAudit(A).orElseThrow().responses().stream()
                .filter(r -> "ACCEPTED".equals(r.outcome())).count());
        world.close();
    }

    @Test
    void partialPaymentSecondRequestUsesNextResponseWithoutDoubleBudget() throws Exception {
        Path file = journal("partial-retry");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(List.of(C1),
                List.of(event("adv", C1, "ADVANCE", 1_400L, 0, "2000.00", ""),
                        event("acc", C1, "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"),
                        event("pay-1", C1, "PAY", PAY_AT, 0, "100.00", "PAYMENT"),
                        event("pay-2", C1, "PAY", PAY_AT + 300L, 0, "300.00", "PAYMENT"))));
        governance(world, List.of(C1));
        signAndWork(world, C1, 1_350L);
        world.scheduleEngineeringAction("req-acc", "req-acc-task", ACCEPT_AT - 100L, A, "dave",
                "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("invoice", "invoice-task", INVOICE_AT, A, "dave",
                "INVOICE", C1, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("req-pay-1", "req-pay-1-task", DUE_AT + 100L, A, "dave",
                "REQUEST_PAYMENT", C1, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("req-pay-2", "req-pay-2-task", PAY_AT + 150L, A, "dave",
                "REQUEST_PAYMENT", C1, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.advanceTo(PAY_AT + 400L);
        assertEquals(new BigDecimal("600.00"), contractState(world, C1).invoices().get(0)
                .currentOutstanding(), "1000 − 100 − 300");
        WorldRuntime.CustomerAudit audit = world.customerAudit(A).orElseThrow();
        assertEquals(2, audit.payments().stream().filter(p -> "CURRENT".equals(p.kind())).count(),
                "每笔付款只记一次：" + audit.payments());
        assertEquals(new BigDecimal("400.00"), audit.payments().stream()
                .filter(p -> "CURRENT".equals(p.kind()))
                .map(EngineeringRun.Customer.Payment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add), "同期间预算按真实付款累计");
        assertEquals(2, requests(world, "PAYMENT").size());
        assertTrue(requests(world, "PAYMENT").stream().allMatch(
                EngineeringRun.Customer.Request::answered), "两条请求各自被一条回应回答");
        world.close();
    }

    @Test
    void unansweredDuplicateIsStillBlocked() throws Exception {
        Path file = journal("pending");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(List.of(C1),
                List.of(event("adv", C1, "ADVANCE", 1_400L, 0, "2000.00", ""),
                        event("acc", C1, "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"))));
        governance(world, List.of(C1));
        signAndWork(world, C1, 1_350L);
        world.scheduleEngineeringAction("req-acc-1", "req-acc-1-task", ACCEPT_AT - 200L, A,
                "dave", "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("req-acc-dup", "req-acc-dup-task", ACCEPT_AT - 150L,
                A, "dave", "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0);
        world.advanceTo(ACCEPT_AT - 100L);
        assertEquals("REQUEST_PENDING", report(world, "req-acc-dup-task:finish").reason(),
                "已有未回答请求时仍受控");
        assertEquals(1, requests(world, "ACCEPTANCE").size());
        world.close();
    }

    @Test
    void sameCommandReplayIsIdempotentAndDifferentBodyConflicts() throws Exception {
        Path file = journal("command");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(List.of(C1),
                List.of(event("adv", C1, "ADVANCE", 1_400L, 0, "2000.00", ""),
                        event("acc", C1, "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"))));
        governance(world, List.of(C1));
        signAndWork(world, C1, 1_350L);
        String first = world.scheduleEngineeringAction("req-acc", "req-acc-task",
                ACCEPT_AT - 200L, A, "dave", "REQUEST_ACCEPTANCE", C1, "", 0, 40,
                BigDecimal.ZERO, "", 0);
        String replay = world.scheduleEngineeringAction("req-acc", "req-acc-task",
                ACCEPT_AT - 200L, A, "dave", "REQUEST_ACCEPTANCE", C1, "", 0, 40,
                BigDecimal.ZERO, "", 0);
        assertEquals(first, replay, "同 command 同体必须幂等");
        assertThrows(IllegalArgumentException.class,
                () -> world.scheduleEngineeringAction("req-acc", "req-acc-task",
                        ACCEPT_AT - 200L, A, "dave", "REQUEST_ACCEPTANCE", C1, "", 0, 40,
                        new BigDecimal("1.00"), "", 0), "同 command 异体必须冲突");
        world.advanceTo(ACCEPT_AT + 100L);
        assertEquals(1, requests(world, "ACCEPTANCE").size(), "重放不产生新请求");
        assertEquals("acc", requests(world, "ACCEPTANCE").get(0).answeredByEventId());
        world.close();
    }

    @Test
    void twoContractsSameCustomerDoNotCrossTalk() throws Exception {
        Path file = journal("two-contracts");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(List.of(C1, C2),
                List.of(event("acc-c1", C1, "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"))),
                richerCompany("12000.00"));
        governance(world, List.of(C1, C2));
        signAndWork(world, C1, 1_350L);
        signAndWork(world, C2, 1_360L);
        world.scheduleEngineeringAction("req-c1", "req-c1-task", ACCEPT_AT - 200L, A, "dave",
                "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("req-c2", "req-c2-task", ACCEPT_AT - 150L, A, "dave",
                "REQUEST_ACCEPTANCE", C2, "", 0, 40, BigDecimal.ZERO, "", 0);
        world.advanceTo(ACCEPT_AT + 100L);
        List<EngineeringRun.Customer.Request> accepts = requests(world, "ACCEPTANCE");
        assertEquals(2, accepts.size(), "同客户不同合同各自一条请求：" + accepts + " reports="
                + world.snapshot().engineering().orElseThrow().reports().values().stream()
                .filter(r -> r.taskId().startsWith("req-") || r.taskId().startsWith("sign-")
                        || r.taskId().startsWith("work-")).toList());
        EngineeringRun.Customer.Request c1 = accepts.stream()
                .filter(r -> r.contractId().equals(C1)).findFirst().orElseThrow();
        EngineeringRun.Customer.Request c2 = accepts.stream()
                .filter(r -> r.contractId().equals(C2)).findFirst().orElseThrow();
        assertEquals("acc-c1", c1.answeredByEventId(), "回应只回答自己合同");
        assertFalse(c2.answered(), "另一合同仍等待");
        assertEquals(0, contractState(world, C2).invoices().size());
        assertEquals(new BigDecimal("4000.00"), world.engineeringStateOf(A).orElseThrow()
                .claims().get(C1).revenue(), "只有 C1 被验收");
        assertEquals(0, world.engineeringStateOf(A).orElseThrow().claims().get(C2).revenue()
                .signum());
        world.close();
    }
}
