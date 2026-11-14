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
 * 18b 客户/经营闭环：经理请求 → 冻结客户响应 → 真实付款/拒付/逾期 →
 * 唯一客户池期间预算 → 独立董事精确豁免，全部在世界日志里逐帧推进。
 *
 * <p>精确 B 数例直接复用主树已验 {@code EngineeringModelTest} 的冻结参数
 * （单价 100.00/成本 60.00、预收 20%/质保 10%、15/60 天、阶段 40+60、人工 60%），
 * 不改任何数字凑平衡。</p>
 */
class EngineeringCustomerChainTest {

    private static final long DAY = EngineeringModel.DAY_MILLIS;
    /** 期初到工期完工：100 单位 × 1 天。 */
    private static final long WORK_DONE = 1_450L + 100L * DAY;
    private static final long ACCEPT_AT = WORK_DONE + 250L;
    private static final long INVOICE_AT = WORK_DONE + 600L;
    private static final long DUE_AT = INVOICE_AT + 15L * DAY;
    private static final long PAY_AT = DUE_AT + 1_200L;
    private static final long IMPAIR_AT = 120L * DAY;
    private static final long WRITE_OFF_AT = IMPAIR_AT + 1_000L;
    private static final long RECOVER_AT = IMPAIR_AT + 2_000L;
    private static final long TERMINATE_AT = IMPAIR_AT + 3_000L;
    private static final long SUPPLIER_AT = IMPAIR_AT + 4_000L;
    private static final long WAIVER_PUBLISH_AT = IMPAIR_AT + 60_000L;
    private static final long WAIVER_READ_AT = IMPAIR_AT + 61_000L;
    private static final long WAIVER_CONVENE_AT = IMPAIR_AT + 62_000L;
    private static final long WAIVER_MATERIAL_DEADLINE = IMPAIR_AT + 64_000L;
    private static final long WAIVER_OPEN_AT = IMPAIR_AT + 66_000L;
    private static final long WAIVER_CLOSE_AT = IMPAIR_AT + 68_000L;
    private static final long WAIVER_IMPLEMENT_AT = IMPAIR_AT + 70_000L;
    private static final long WAIVE_AT = IMPAIR_AT + 72_000L;
    private static final String CONTRACT = "B-1";
    private static final String WAIVER_PURPOSE = "董事会批准放弃剩余债权";
    private static final String WAIVER_ID = "waiver-b1";

    private static Path journal(String name) throws IOException {
        Path dir = Path.of("target", "engineering-customer-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static EngineeringRun.ContractPlan bContract() {
        return new EngineeringRun.ContractPlan(CONTRACT, new BigDecimal("100.00"),
                new BigDecimal("60.0000"), 2000, 1000, 15, 60, List.of(40L, 60L));
    }

    private static EngineeringRun.CustomerPool pool(String budget, int periods) {
        return new EngineeringRun.CustomerPool("ENG-A-CUS", 30L * DAY, new BigDecimal(budget),
                periods);
    }

    private static EngineeringRun.CompanySpec aSpec(EngineeringRun.CustomerPool pool,
            List<EngineeringRun.ContractPlan> contracts) {
        EngineeringRun.CompanySpec base = WorldWebScenario.engineeringConfig()
                .specOf(WorldWebScenario.ENGINE_A);
        return new EngineeringRun.CompanySpec(base.companyPartyId(), base.companyOwnerId(),
                base.companyAccountId(), base.customerAccountId(), base.supplierAccountId(),
                base.opsAccountId(), DAY, 6_000, base.charter(), contracts, pool);
    }

    private static EngineeringRun.CustomerEventPlan event(String id, String kind, long at,
            long units, String amount, String requestKind) {
        boolean payment = List.of("ADVANCE", "PAY", "RETENTION_PAY", "RECOVERY_PAY")
                .contains(kind);
        return new EngineeringRun.CustomerEventPlan(id, WorldWebScenario.ENGINE_A, CONTRACT, 0,
                kind, at, units, amount == null ? BigDecimal.ZERO : new BigDecimal(amount),
                payment ? "ENG-A-CUS" : "", "冻结客户证据：" + id, requestKind);
    }

    private static EngineeringRun.Config bConfig(String budget, int periods,
            List<EngineeringRun.CustomerEventPlan> events) {
        return new EngineeringRun.Config("18b 客户闭环实验参数（复用 B 精确数例）",
                List.of(aSpec(pool(budget, periods), List.of(bContract()))),
                Map.of(WorldWebScenario.ENGINE_A, events));
    }

    private static List<EngineeringRun.CustomerEventPlan> bEvents() {
        return List.of(
                event("adv-b1", "ADVANCE", 1_400L, 0, "2000.00", ""),
                event("acc-b1", "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"),
                event("pay-b1", "PAY", PAY_AT, 0, "400.00", "PAYMENT"),
                // 回收 150 必须来自冻结客户回应（到期执行），不是经理强扣
                event("rec-b1", "RECOVERY_PAY", RECOVER_AT, 0, "150.00", "RECOVERY"));
    }

    /** 真实材料与机会：董事读材料、经理读机会（不做任何代读）。 */
    private static void prepareReading(WorldRuntime world) throws Exception {
        world.schedulePublishDocument("pub-budget", "pub-budget-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-waiver", "pub-waiver-task", WAIVER_PUBLISH_AT,
                "waiver-material", WorldWebScenario.INVESTOR,
                InformationModel.Audience.publicAudience(), "豁免材料",
                "实验豁免材料：具体债权、客户与金额", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-opp", "pub-opp-task", 10L,
                "engineering-opportunity:" + CONTRACT, WorldWebScenario.INVESTOR,
                InformationModel.Audience.direct("dave"),
                "B-1 机会", "实验机会 B-1", "冻结机会", 10L, List.of());
        int index = 0;
        for (String reader : List.of("alice", "bob", "carol", "dave", "erin")) {
            world.scheduleStartReading("read-mat-" + reader, "read-mat-" + reader + "-task",
                    20L + index * 2L, reader, "b-material", 1L);
            world.scheduleStartReading("read-appt-" + reader, "read-appt-" + reader + "-task",
                    60L + index * 2L, reader, "appt-material", 1L);
            world.scheduleStartReading("read-waiver-" + reader,
                    "read-waiver-" + reader + "-task", WAIVER_READ_AT + index * 100L, reader,
                    "waiver-material", 1L);
            index++;
        }
        world.scheduleStartReading("read-opp", "read-opp-task", 100L, "dave",
                "engineering-opportunity:" + CONTRACT, 1L);
    }

    /** 五席预算 → 五席任免（real RoleAccess），推进到经理任职生效。 */
    private static void prepareGovernance(WorldRuntime world) throws Exception {
        world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L,
                WorldWebScenario.ENGINE_A, "alice", "budget-meeting", 150L, 200L, 300L);
        String[] persons = {"alice", "bob", "carol", "dave", "erin"};
        for (int i = 0; i < persons.length; i++) {
            world.scheduleEngineeringAttend("budget-a-" + i, "budget-a-" + i + "-task",
                    110L + i, WorldWebScenario.ENGINE_A, persons[i], "budget-meeting", false);
        }
        world.scheduleEngineeringMaterial("budget-m", "budget-m-task", 130L,
                WorldWebScenario.ENGINE_A, "alice", "budget-meeting", "m1", "b-material", 1L);
        world.scheduleEngineeringBudgetProposal("budget-p", "budget-p-task", 140L,
                WorldWebScenario.ENGINE_A, "alice", "budget-meeting", List.of(
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                new BigDecimal("10000.00")),
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                new BigDecimal("10000.00"))));
        for (int i = 0; i < 4; i++) {
            world.scheduleEngineeringVote("budget-v-" + i, "budget-v-" + i + "-task",
                    220L + i, WorldWebScenario.ENGINE_A, persons[i], "budget-meeting",
                    BoardModel.Vote.YES);
        }
        world.scheduleEngineeringVote("budget-v-4", "budget-v-4-task", 224L,
                WorldWebScenario.ENGINE_A, "erin", "budget-meeting", BoardModel.Vote.NO);
        world.scheduleEngineeringImplement("budget-i", "budget-i-task", 320L,
                WorldWebScenario.ENGINE_A, "alice", "budget-meeting");
        appointment(world, "mgr-meeting", 400L, "appt-material", 700L, 900L * DAY,
                BoardModel.AppointmentAction.APPOINT);
        // 真实任免必须先落地，经理才能在 BEGIN 通过任职门后提交经营动作
        world.advanceTo(700L);
    }

    /** 经理真实签约 → 客户预收事件 → 开工。 */
    private static void prepareOperating(WorldRuntime world) throws Exception {
        prepareGovernance(world);
        world.scheduleEngineeringAction("sign-b1", "sign-b1-task", 1_350L,
                WorldWebScenario.ENGINE_A, "dave", "SIGN", CONTRACT, "", 0, 0,
                BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("start-w", "start-w-task", 1_450L,
                WorldWebScenario.ENGINE_A, "dave", "START_WORK", CONTRACT, "W-B1", 0, 100,
                BigDecimal.ZERO, "", 0);
    }

    private static void appointment(WorldRuntime world, String meetingId, long base,
            String material, long effectiveAt, long endsAt,
            BoardModel.AppointmentAction action) throws Exception {
        world.scheduleEngineeringAppointmentConvene(meetingId + "-c", meetingId + "-c-task",
                base, WorldWebScenario.ENGINE_A, "alice", meetingId, base + 50, base + 100,
                base + 200);
        // 被任免人 dave 自己占席，到会即构成关联席：本会议只由另外三位董事到会表决
        String[] persons = {"alice", "bob", "carol"};
        for (int i = 0; i < persons.length; i++) {
            world.scheduleEngineeringAttend(meetingId + "-a-" + i,
                    meetingId + "-a-" + i + "-task", base + 10 + i, WorldWebScenario.ENGINE_A,
                    persons[i], meetingId, false);
        }
        world.scheduleEngineeringMaterial(meetingId + "-m", meetingId + "-m-task", base + 30,
                WorldWebScenario.ENGINE_A, "alice", meetingId, "m1", material, 1L);
        world.scheduleEngineeringAppointmentProposal(meetingId + "-p", meetingId + "-p-task",
                base + 40, WorldWebScenario.ENGINE_A, "alice", meetingId, List.of(),
                new BoardModel.AppointmentRequest("mgr-a", "dave", WorldWebScenario.ENGINE_A,
                        RoleAccess.Role.COMPANY_MANAGER, action, effectiveAt,
                        action == BoardModel.AppointmentAction.REVOKE ? null : endsAt));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote(meetingId + "-v-" + i, meetingId + "-v-" + i + "-task",
                    base + 120 + i, WorldWebScenario.ENGINE_A, persons[i], meetingId,
                    BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement(meetingId + "-i", meetingId + "-i-task", base + 220,
                WorldWebScenario.ENGINE_A, "alice", meetingId);
    }

    /** 经理请求：验收 → 开票 → 付款请求；每一步都是真实任务而不是直接改账。 */
    private static void scheduleManagerRequests(WorldRuntime world) throws Exception {
        world.scheduleEngineeringAction("req-acc", "req-acc-task", WORK_DONE + 150L,
                WorldWebScenario.ENGINE_A, "dave", "REQUEST_ACCEPTANCE", CONTRACT, "", 0, 40,
                BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("invoice-b1", "invoice-b1-task", INVOICE_AT,
                WorldWebScenario.ENGINE_A, "dave", "INVOICE", CONTRACT, "", 0, 0,
                BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("req-pay", "req-pay-task", DUE_AT + 100L,
                WorldWebScenario.ENGINE_A, "dave", "REQUEST_PAYMENT", CONTRACT, "", 0, 0,
                BigDecimal.ZERO, "", 0);
    }

    /** 核销链：计提 600 → 核销 600 → 回收 150 → 终止 → 付供应商 2400。 */
    private static void scheduleWorkOutChain(WorldRuntime world) throws Exception {
        world.scheduleEngineeringAction("impair-b1", "impair-b1-task", IMPAIR_AT,
                WorldWebScenario.ENGINE_A, "dave", "IMPAIR", CONTRACT, "", 0, 0,
                new BigDecimal("600.00"), "", 0);
        world.scheduleEngineeringAction("wo-b1", "wo-b1-task", WRITE_OFF_AT,
                WorldWebScenario.ENGINE_A, "dave", "WRITE_OFF", CONTRACT, "", 0, 0,
                new BigDecimal("600.00"), "", 0);
        // 追索只是请求：经理登记意图，客户 150 回款由冻结回应在 RECOVER_AT 触发
        world.scheduleEngineeringAction("recover-b1", "recover-b1-task", RECOVER_AT - 100L,
                WorldWebScenario.ENGINE_A, "dave", "RECOVER", CONTRACT, "", 0, 0,
                new BigDecimal("150.00"), "", 0);
        world.scheduleEngineeringAction("terminate-b1", "terminate-b1-task", TERMINATE_AT,
                WorldWebScenario.ENGINE_A, "dave", "TERMINATE", CONTRACT, "", 0, 0,
                BigDecimal.ZERO, "客户不验收且不付款", 0);
        world.scheduleEngineeringAction("pay-supplier", "pay-supplier-task", SUPPLIER_AT,
                WorldWebScenario.ENGINE_A, "dave", "PAY_SUPPLIER", "", "", 0, 0,
                new BigDecimal("2400.00"), "", 0);
    }

    /** 独立董事豁免：真实五席、4 票重大（1450 > 1000），签发授权但不改账。 */
    private static String scheduleWaiverVote(WorldRuntime world) throws Exception {
        String meetingId = "waiver-meeting";
        world.scheduleEngineeringWaiverConvene("waiver-c", "waiver-c-task", WAIVER_CONVENE_AT,
                WorldWebScenario.ENGINE_A, "alice", meetingId, WAIVER_MATERIAL_DEADLINE,
                WAIVER_OPEN_AT, WAIVER_CLOSE_AT);
        String[] persons = {"alice", "bob", "carol", "dave", "erin"};
        for (int i = 0; i < persons.length; i++) {
            world.scheduleEngineeringAttend("waiver-a-" + i, "waiver-a-" + i + "-task",
                    WAIVER_CONVENE_AT + 500L + i, WorldWebScenario.ENGINE_A, persons[i],
                    meetingId, false);
        }
        world.scheduleEngineeringMaterial("waiver-m", "waiver-m-task", WAIVER_CONVENE_AT + 600L,
                WorldWebScenario.ENGINE_A, "alice", meetingId, "m1", "waiver-material", 1L);
        world.scheduleEngineeringWaiverProposal("waiver-p", "waiver-p-task",
                WAIVER_CONVENE_AT + 700L, WorldWebScenario.ENGINE_A, "alice", meetingId,
                new BoardModel.DebtWaiverRequest(WAIVER_ID, WorldWebScenario.ENGINE_A, CONTRACT,
                        "ENG-A-CUS", new BigDecimal("1450.00"), WAIVER_PURPOSE));
        for (int i = 0; i < 4; i++) {
            world.scheduleEngineeringVote("waiver-v-" + i, "waiver-v-" + i + "-task",
                    WAIVER_OPEN_AT + 500L + i, WorldWebScenario.ENGINE_A, persons[i], meetingId,
                    BoardModel.Vote.YES);
        }
        world.scheduleEngineeringVote("waiver-v-4", "waiver-v-4-task", WAIVER_OPEN_AT + 504L,
                WorldWebScenario.ENGINE_A, "erin", meetingId, BoardModel.Vote.NO);
        world.scheduleEngineeringImplement("waiver-i", "waiver-i-task", WAIVER_IMPLEMENT_AT,
                WorldWebScenario.ENGINE_A, "alice", meetingId);
        return meetingId;
    }

    private static String authorizationRef(WorldRuntime world) {
        BoardModel.DebtWaiver waiver = BoardModel.debtWaiverOf(
                world.snapshot().engineering().orElseThrow()
                        .waiversOf(WorldWebScenario.ENGINE_A),
                "waiver-meeting");
        assertNotNull(waiver, "豁免会议必须有决议记录");
        assertTrue(waiver.authorized(), "实施后必须签发授权，reports=" + world.snapshot()
                .engineering().orElseThrow().reports().values().stream()
                .filter(r -> r.taskId().startsWith("waiver-")).toList()
                + " meetings=" + world.boardOfCompany(WorldWebScenario.ENGINE_A)
                .orElseThrow().board().meetings());
        return waiver.authorizationRef();
    }

    @Test
    void exactBChainThroughTheWorld() throws Exception {
        Path file = journal("b-chain");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                bConfig("5000.00", 6, bEvents()));
        prepareReading(world);
        prepareOperating(world);
        scheduleManagerRequests(world);
        scheduleWorkOutChain(world);
        scheduleWaiverVote(world);
        world.advanceTo(1_600L);

        EngineeringModel.State state = world.engineeringStateOf(WorldWebScenario.ENGINE_A)
                .orElseThrow();
        assertEquals(1, state.contracts().size(), "签约必须成功：" + world.snapshot()
                .engineering().orElseThrow().reports().get("sign-b1-task"));
        // 客户预收：ADVANCE 事件（客户自发，合同签约后按条款支付 2000）
        assertEquals(new BigDecimal("2000.00"),
                state.contracts().get(CONTRACT).advanceReceived(),
                "预收必须来自真实客户付款事件：" + world.snapshot().engineering().orElseThrow()
                        .reports().get("customer:adv-b1"));
        assertEquals(new BigDecimal("3400.00"), world.snapshot().accounts().get("ENG-A").cash(),
                "5000 开局 + 2000 预收 − 3600 已付人工");
        assertEquals(new BigDecimal("18000.00"),
                world.snapshot().accounts().get("ENG-A-CUS").cash());
        assertEquals(1, state.workOrders().size());
        assertEquals(new BigDecimal("2400.00"), state.payable(), "分包 2400 计入应付");

        world.advanceTo(ACCEPT_AT + 100L);
        state = world.engineeringStateOf(WorldWebScenario.ENGINE_A).orElseThrow();
        assertEquals(new BigDecimal("4000.00"), state.claims().get(CONTRACT).revenue(),
                "客户验收 40 单位才确认收入：" + world.snapshot().engineering().orElseThrow()
                        .reports().get("customer:acc-b1"));
        assertEquals(new BigDecimal("2400.00"), state.claims().get(CONTRACT).cogs());
        assertEquals(new BigDecimal("2000.00"),
                state.claims().get(CONTRACT).contractAsset(),
                "验收 4000 − 冲抵预收 2000（质保在开票时才扣留）");

        world.advanceTo(INVOICE_AT + 100L);
        state = world.engineeringStateOf(WorldWebScenario.ENGINE_A).orElseThrow();
        assertEquals(new BigDecimal("1000.00"), state.claims().get(CONTRACT).arCurrent());
        assertEquals(new BigDecimal("1000.00"), state.claims().get(CONTRACT).arRetention());

        // 到期门：未到期不能请求付款（经理请求在真实到期前执行 → 业务拒绝）
        world.scheduleEngineeringAction("req-pay-early", "req-pay-early-task", DUE_AT - 1_000L,
                WorldWebScenario.ENGINE_A, "dave", "REQUEST_PAYMENT", CONTRACT, "", 0, 0,
                BigDecimal.ZERO, "", 0);
        world.advanceTo(DUE_AT - 500L);
        // BEGIN 只复核授权/焦点，早回执不落业务结论；到期门在 FINISH 的真实执行时判定
        assertEquals("STARTED", world.snapshot().engineering().orElseThrow().reports()
                .get("req-pay-early-task").reason(), "屏障只代表真实开始");
        assertEquals("NOT_DUE", world.snapshot().engineering().orElseThrow().reports()
                .get("req-pay-early-task:finish").reason(), "请求不能提前把未到期债权变成可收");

        world.advanceTo(PAY_AT + 100L);
        state = world.engineeringStateOf(WorldWebScenario.ENGINE_A).orElseThrow();
        assertEquals(new BigDecimal("600.00"), state.claims().get(CONTRACT).arCurrent(),
                "客户只付 400，余款 600 保留：" + world.snapshot().engineering().orElseThrow()
                        .reports().get("customer:pay-b1"));

        world.advanceTo(RECOVER_AT + 100L);
        state = world.engineeringStateOf(WorldWebScenario.ENGINE_A).orElseThrow();
        assertEquals(new BigDecimal("600.00"), state.claims().get(CONTRACT).impairmentLoss());
        assertEquals(new BigDecimal("450.00"), state.claims().get(CONTRACT).memo(),
                "核销 600 − 回收 150 = 备查 450");
        assertEquals(new BigDecimal("150.00"), state.claims().get(CONTRACT).recoveryIncome());
        // 经理的 RECOVER 只是追索请求；150 到账来自冻结客户回应
        assertEquals("STARTED", world.snapshot().engineering().orElseThrow()
                .reports().get("recover-b1-task").reason(), "屏障只代表真实开始");
        assertEquals("RECOVERY_REQUESTED", world.snapshot().engineering().orElseThrow()
                .reports().get("recover-b1-task:finish").reason(), "经理动作不能直接扣客户钱");
        EngineeringRun.Report recoveryEvent = world.snapshot().engineering().orElseThrow()
                .reports().get("customer:rec-b1");
        assertNotNull(recoveryEvent, "客户回应必须留下报告");
        assertEquals("RECOVERED", recoveryEvent.reason(), "只有客户回应才真正回收");
        assertTrue(world.customerAudit(WorldWebScenario.ENGINE_A).orElseThrow().payments()
                .stream().anyMatch(payment -> "RECOVERY".equals(payment.kind())
                        && "rec-b1".equals(payment.eventId())), "回收必须单独记账");

        world.advanceTo(SUPPLIER_AT + 200L);
        EngineeringModel.State afterTerminate = world.engineeringStateOf(
                WorldWebScenario.ENGINE_A).orElseThrow();
        assertEquals(new BigDecimal("3600.00"),
                afterTerminate.claims().get(CONTRACT).terminationLoss());
        assertEquals(new BigDecimal("0.00"), afterTerminate.payable(), "供应商已付 2400");
        WorldRuntime.CompanyAudit audit = world.companyAudit(WorldWebScenario.ENGINE_A)
                .orElseThrow();
        assertEquals(new BigDecimal("1550.00"), audit.companyCash());
        assertEquals(new BigDecimal("2550.00"), audit.equity());
        assertEquals(new BigDecimal("1000.00"), audit.arRetention(), "未到期质保不得被搬走");
        assertEquals(new BigDecimal("450.00"), world.customerAudit(WorldWebScenario.ENGINE_A)
                .orElseThrow().memo(), "核销 600 − 回收 150 = 备查 450");
        assertEquals(new BigDecimal("1450.00"), audit.obligation(), "核销不解除客户义务");
        assertTrue(audit.balanced());

        // 独立董事授权：签发后仍未改账
        world.advanceTo(WAIVER_IMPLEMENT_AT + 100L);
        String ref = authorizationRef(world);
        audit = world.companyAudit(WorldWebScenario.ENGINE_A).orElseThrow();
        assertEquals(new BigDecimal("1450.00"), audit.obligation(), "授权 ≠ 执行");
        assertEquals(new BigDecimal("2550.00"), audit.equity());

        // 经理带精确授权执行豁免
        world.scheduleEngineeringWaive("waive-b1", "waive-b1-task", WAIVE_AT,
                WorldWebScenario.ENGINE_A, "dave", CONTRACT, new BigDecimal("1450.00"),
                WAIVER_PURPOSE, ref, 0, 100L);
        world.advanceTo(WAIVE_AT + 200L);
        audit = world.companyAudit(WorldWebScenario.ENGINE_A).orElseThrow();
        assertEquals(new BigDecimal("0.00"), audit.obligation());
        assertEquals(new BigDecimal("1550.00"), audit.equity(), "豁免 1450 后权益 1550");
        assertEquals(new BigDecimal("1550.00"), audit.companyCash());
        WorldRuntime.CustomerAudit customer = world.customerAudit(WorldWebScenario.ENGINE_A)
                .orElseThrow();
        assertEquals(3, customer.requests().size(), "验收/付款/追索请求：" + customer.requests());
        assertTrue(customer.requests().stream().anyMatch(r -> "RECOVERY".equals(r.kind())
                && r.amount().compareTo(new BigDecimal("150.00")) == 0), "追索请求金额必须冻结");
        assertEquals(3, customer.payments().size(), "预收 2000/当期 400/回收 150："
                + customer.payments());
        assertEquals(new BigDecimal("2000.00"), customer.payments().get(0).amount());
        assertEquals("ADVANCE", customer.payments().get(0).kind());
        assertTrue(customer.responses().stream()
                .anyMatch(r -> "ACCEPTED".equals(r.outcome())), customer.responses().toString());
        assertEquals(1, customer.waivers().size());
        assertTrue(customer.waivers().get(0).consumed(), "授权只能执行一次");

        long position = world.snapshot().position();
        world.close();
        try (WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file,
                bConfig("5000.00", 6, bEvents()))) {
            assertEquals(position, reopened.snapshot().position(), "合法客户链必须能跨 JVM 重开");
            WorldRuntime.CustomerAudit again = reopened.customerAudit(
                    WorldWebScenario.ENGINE_A).orElseThrow();
            assertEquals(3, again.payments().size(), "重开后客户付款仍只有三笔真实付款");
            assertEquals(new BigDecimal("1550.00"), reopened.companyAudit(
                    WorldWebScenario.ENGINE_A).orElseThrow().equity());
            assertTrue(again.waivers().get(0).consumed(), "授权消耗状态必须随日志恢复");
        }
    }

    @Test
    void responseWithoutRequestIsRejectedAndKeepsClaim() throws Exception {
        Path file = journal("unmatched");
        List<EngineeringRun.CustomerEventPlan> events = List.of(
                event("acc-early", "ACCEPT", 700_000L, 40L, null, "ACCEPTANCE"));
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                bConfig("5000.00", 6, events));
        prepareReading(world);
        prepareOperating(world);
        world.advanceTo(1_600L);
        world.advanceTo(760_000L);
        EngineeringRun.Report report = world.snapshot().engineering().orElseThrow().reports()
                .get("customer:acc-early");
        assertNotNull(report, "未回答的事件也必须留下报告");
        assertEquals("UNMATCHED_REQUEST", report.reason());
        assertFalse(report.accepted());
        EngineeringModel.State state = world.engineeringStateOf(WorldWebScenario.ENGINE_A)
                .orElseThrow();
        assertEquals(new BigDecimal("0.00"), state.claims().get(CONTRACT).revenue(),
                "没有请求就没有验收");
        assertEquals(new BigDecimal("0.00"), state.claims().get(CONTRACT).arCurrent());
        assertEquals(1, world.customerAudit(WorldWebScenario.ENGINE_A).orElseThrow()
                .responses().size(), "被拒绝的事件只留证据");
        assertEquals("UNMATCHED_REQUEST", world.customerAudit(WorldWebScenario.ENGINE_A)
                .orElseThrow().responses().get(0).outcome());
        assertEquals(0, world.customerAudit(WorldWebScenario.ENGINE_A).orElseThrow()
                .requests().size(), "没有人提出请求，也没有请求被回答");
        world.close();
    }

    @Test
    void overdueWithoutRealDueInvoiceIsRejected() throws Exception {
        Path file = journal("overdue");
        List<EngineeringRun.CustomerEventPlan> events = List.of(
                event("od-early", "OVERDUE", 800_000L, 0, null, ""));
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                bConfig("5000.00", 6, events));
        prepareReading(world);
        prepareOperating(world);
        world.advanceTo(1_600L);
        world.advanceTo(860_000L);
        EngineeringRun.Report report = world.snapshot().engineering().orElseThrow().reports()
                .get("customer:od-early");
        assertEquals("NO_DUE_RECEIVABLE", report.reason(), "不凭计划造债");
        assertEquals(new BigDecimal("0.00"), world.companyAudit(WorldWebScenario.ENGINE_A)
                .orElseThrow().arCurrent());
        world.close();
    }

    @Test
    void periodBudgetLimitsCustomerPaymentAndKeepsRemainder() throws Exception {
        Path file = journal("budget");
        List<EngineeringRun.CustomerEventPlan> events = List.of(
                event("adv-b1", "ADVANCE", 1_400L, 0, "2000.00", ""));
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                bConfig("700.00", 6, events));
        prepareReading(world);
        prepareOperating(world);
        world.advanceTo(1_600L);
        EngineeringModel.State state = world.engineeringStateOf(WorldWebScenario.ENGINE_A)
                .orElseThrow();
        assertEquals(new BigDecimal("700.00"),
                state.contracts().get(CONTRACT).advanceReceived(),
                "期间预算 700 只允许部分预收：" + world.snapshot().engineering().orElseThrow()
                        .reports().get("customer:adv-b1"));
        EngineeringRun.Customer.Response response = world.customerAudit(
                WorldWebScenario.ENGINE_A).orElseThrow().responses().get(0);
        assertEquals("PARTIAL", response.outcome(), response.toString());
        assertEquals(new BigDecimal("700.00"), response.amount());
        assertEquals(new BigDecimal("2100.00"), world.snapshot().accounts().get("ENG-A").cash(),
                "5000 开局 + 700 部分预收 − 3600 人工");
        assertEquals(new BigDecimal("19300.00"),
                world.snapshot().accounts().get("ENG-A-CUS").cash());

        // 同期间剩余预算已用完：再来一笔预付事件被拒，且不补钱
        List<EngineeringRun.CustomerEventPlan> second = List.of(
                event("adv-b1", "ADVANCE", 1_400L, 0, "2000.00", ""),
                event("adv-b1b", "ADVANCE", 1_500L, 0, "500.00", ""));
        Path file2 = journal("budget-second");
        WorldRuntime world2 = WorldWebScenario.engineeringRuntime(file2,
                bConfig("700.00", 6, second));
        prepareReading(world2);
        prepareOperating(world2);
        world2.advanceTo(1_600L);
        assertEquals("CUSTOMER_BUDGET_EXHAUSTED", world2.snapshot().engineering().orElseThrow()
                .reports().get("customer:adv-b1b").reason());
        assertEquals(new BigDecimal("700.00"), world2.engineeringStateOf(
                WorldWebScenario.ENGINE_A).orElseThrow().contracts().get(CONTRACT)
                .advanceReceived());
        world.close();
        world2.close();
    }

    @Test
    void conflictingCustomerPoolsAreRejectedBeforeOpening() throws Exception {
        EngineeringRun.CompanySpec a = aSpec(pool("5000.00", 6), List.of(bContract()));
        EngineeringRun.CompanySpec base = WorldWebScenario.engineeringConfig()
                .specOf(WorldWebScenario.ENGINE_A);
        EngineeringRun.CompanySpec b = new EngineeringRun.CompanySpec(
                WorldWebScenario.ENGINE_B, base.companyOwnerId().replace("eng-a", "eng-b"),
                "ENG-B", "ENG-A-CUS", "ENG-B-SUP", "ENG-B-OPS", DAY, 6_000,
                WorldWebScenario.engineeringConfig().specOf(WorldWebScenario.ENGINE_B).charter(),
                List.of(new EngineeringRun.ContractPlan("B9", new BigDecimal("10.00"),
                        new BigDecimal("5.0000"), 0, 0, 0, 0, List.of(1L))),
                new EngineeringRun.CustomerPool("ENG-A-CUS", 30L * DAY,
                        new BigDecimal("900.00"), 6));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> new EngineeringRun.Config("冲突客户池", List.of(a, b)));
        assertTrue(rejected.getMessage().contains("同一客户账户"), rejected.getMessage());
    }

    @Test
    void waiverAuthorizationMustMatchExactlyAndIsSingleUse() throws Exception {
        Path file = journal("waiver");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                bConfig("5000.00", 6, bEvents()));
        prepareReading(world);
        prepareOperating(world);
        scheduleManagerRequests(world);
        scheduleWorkOutChain(world);
        scheduleWaiverVote(world);
        world.advanceTo(WAIVER_IMPLEMENT_AT + 100L);
        String ref = authorizationRef(world);

        // 异金额 / 异合同 → 拒绝，账不变
        world.scheduleEngineeringWaive("waive-wrong", "waive-wrong-task", WAIVE_AT,
                WorldWebScenario.ENGINE_A, "dave", CONTRACT, new BigDecimal("1400.00"),
                WAIVER_PURPOSE, ref, 0, 100L);
        world.advanceTo(WAIVE_AT + 100L);
        assertEquals("STARTED", world.snapshot().engineering().orElseThrow().reports()
                .get("waive-wrong-task").reason(), "授权引用核对属于真实执行，不在开始屏障");
        assertEquals("WAIVER_MISMATCH", world.snapshot().engineering().orElseThrow().reports()
                .get("waive-wrong-task:finish").reason());
        assertEquals(new BigDecimal("1450.00"), world.companyAudit(WorldWebScenario.ENGINE_A)
                .orElseThrow().obligation());

        // 自填伪造引用 → 拒绝（声明工时 50ms：真实开始时点必须严格晚于当前时点）
        world.scheduleEngineeringWaive("waive-fake", "waive-fake-task", WAIVE_AT + 200L,
                WorldWebScenario.ENGINE_A, "dave", CONTRACT, new BigDecimal("1450.00"),
                WAIVER_PURPOSE, "debt-waiver-v1:forged", 0, 50L);
        world.advanceTo(WAIVE_AT + 300L);
        assertEquals("STARTED", world.snapshot().engineering().orElseThrow().reports()
                .get("waive-fake-task").reason(), "屏障只代表真实开始");
        assertEquals(BoardModel.REASON_WAIVER_NOT_AUTHORIZED,
                world.snapshot().engineering().orElseThrow().reports()
                        .get("waive-fake-task:finish").reason());

        // 一次合法执行
        world.scheduleEngineeringWaive("waive-ok", "waive-ok-task", WAIVE_AT + 400L,
                WorldWebScenario.ENGINE_A, "dave", CONTRACT, new BigDecimal("1450.00"),
                WAIVER_PURPOSE, ref, 0, 50L);
        world.advanceTo(WAIVE_AT + 500L);
        assertEquals(new BigDecimal("0.00"), world.companyAudit(WorldWebScenario.ENGINE_A)
                .orElseThrow().obligation());

        // 同一授权复用 → 拒绝，不二次豁免
        world.scheduleEngineeringWaive("waive-again", "waive-again-task", WAIVE_AT + 600L,
                WorldWebScenario.ENGINE_A, "dave", CONTRACT, new BigDecimal("1450.00"),
                WAIVER_PURPOSE, ref, 0, 50L);
        world.advanceTo(WAIVE_AT + 700L);
        assertEquals("STARTED", world.snapshot().engineering().orElseThrow().reports()
                .get("waive-again-task").reason(), "屏障只代表真实开始");
        assertEquals(BoardModel.REASON_WAIVER_NOT_AUTHORIZED,
                world.snapshot().engineering().orElseThrow().reports()
                        .get("waive-again-task:finish").reason());
        assertEquals(new BigDecimal("1550.00"), world.companyAudit(WorldWebScenario.ENGINE_A)
                .orElseThrow().equity(), "权益只被豁免一次");
        assertEquals(new BigDecimal("0.00"), world.companyAudit(WorldWebScenario.ENGINE_A)
                .orElseThrow().obligation());
        world.close();
    }

    @Test
    void managerOpportunityUnreadThenReadThenForgotten() throws Exception {
        Path file = journal("opportunity");
        EngineeringRun.ContractPlan second = new EngineeringRun.ContractPlan("B-2",
                new BigDecimal("100.00"), new BigDecimal("60.0000"), 2000, 1000, 15, 60,
                List.of(40L, 60L));
        EngineeringRun.Config config = new EngineeringRun.Config("机会链实验参数",
                List.of(aSpec(pool("5000.00", 6), List.of(bContract(), second))), Map.of());
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config);
        world.schedulePublishDocument("pub-budget", "pub-budget-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        for (String contract : List.of(CONTRACT, "B-2")) {
            world.schedulePublishDocument("pub-opp-" + contract,
                    "pub-opp-" + contract + "-task", 4_000L,
                    "engineering-opportunity:" + contract, WorldWebScenario.INVESTOR,
                    InformationModel.Audience.direct("dave"), contract + " 机会",
                    "实验机会 " + contract, "冻结机会", 10L, List.of());
        }
        int index = 0;
        for (String reader : List.of("alice", "bob", "carol", "dave", "erin")) {
            world.scheduleStartReading("read-mat-" + reader, "read-mat-" + reader + "-task",
                    20L + index * 2L, reader, "b-material", 1L);
            world.scheduleStartReading("read-appt-" + reader, "read-appt-" + reader + "-task",
                    60L + index * 2L, reader, "appt-material", 1L);
            world.scheduleStartReading("read-opp-a-" + reader,
                    "read-opp-a-" + reader + "-task", 4_100L + index * 2L, reader,
                    "engineering-opportunity:" + CONTRACT, 1L);
            world.scheduleStartReading("read-opp-b-" + reader,
                    "read-opp-b-" + reader + "-task", 4_200L + index * 2L, reader,
                    "engineering-opportunity:B-2", 1L);
            index++;
        }
        prepareGovernance(world);
        // 未读机会：经理已真实任职，但还没读机会 → 不得签约；角色输入也不得泄露机会
        assertTrue(world.activeManagerForTest("dave", WorldWebScenario.ENGINE_A));
        assertTrue(world.companyView("dave", WorldWebScenario.ENGINE_A).orElseThrow().tools()
                .contains(RoleTradingView.TOOL_ENGINEERING_REQUEST_ACCEPTANCE),
                "经理工具表必须包含客户请求");
        assertTrue(world.companyView("dave", WorldWebScenario.ENGINE_A).orElseThrow().tools()
                .contains(RoleTradingView.TOOL_ENGINEERING_WAIVE), "经理工具表必须包含豁免执行");
        assertTrue(world.companyView("alice", WorldWebScenario.ENGINE_A).orElseThrow().tools()
                .contains(RoleTradingView.TOOL_BOARD_PROPOSE_WAIVER),
                "董事工具表必须包含豁免提案");
        String unread = world.engineeringRoleInput("dave").toString();
        assertFalse(unread.contains("engineering-opportunity"),
                "未读机会不得进入角色输入：" + unread);
        assertFalse(unread.contains("B-2"), "未读机会标识不得泄露：" + unread);
        world.scheduleEngineeringAction("sign-b1-unread", "sign-b1-unread-task", 800L,
                WorldWebScenario.ENGINE_A, "dave", "SIGN", CONTRACT, "", 0, 0,
                BigDecimal.ZERO, "", 0);
        world.advanceTo(900L);
        assertEquals("STARTED", world.snapshot().engineering().orElseThrow()
                .reports().get("sign-b1-unread-task").reason(), "屏障只代表真实开始");
        assertEquals("OPPORTUNITY_NOT_READ", world.snapshot().engineering().orElseThrow()
                .reports().get("sign-b1-unread-task:finish").reason());

        // 真实读完 → 可以签约；输入里出现真实读过的机会
        world.advanceTo(4_300L);
        assertTrue(world.engineeringRoleInput("dave").toString().contains(CONTRACT),
                "读完后机会应可见");
        world.scheduleEngineeringAction("sign-b1", "sign-b1-task", 4_400L,
                WorldWebScenario.ENGINE_A, "dave", "SIGN", CONTRACT, "", 0, 0,
                BigDecimal.ZERO, "", 0);
        world.advanceTo(4_500L);
        assertEquals(1, world.engineeringStateOf(WorldWebScenario.ENGINE_A).orElseThrow()
                .contracts().size(), "读完后可以真实签约");

        // 遗忘：超过回忆半衰期后不得再用该机会签约
        world.advanceTo(60L * DAY);
        assertFalse(world.engineeringRoleInput("dave").toString().contains("B-2"),
                "遗忘的机会不得再出现在角色输入：" + world.engineeringRoleInput("dave"));
        world.scheduleEngineeringAction("sign-b2-forgotten", "sign-b2-forgotten-task",
                60L * DAY + 1_000L, WorldWebScenario.ENGINE_A, "dave", "SIGN", "B-2", "", 0, 0,
                BigDecimal.ZERO, "", 0);
        world.advanceTo(60L * DAY + 2_000L);
        assertEquals("STARTED", world.snapshot().engineering().orElseThrow()
                .reports().get("sign-b2-forgotten-task").reason(), "屏障只代表真实开始");
        assertEquals("OPPORTUNITY_NOT_READ", world.snapshot().engineering().orElseThrow()
                .reports().get("sign-b2-forgotten-task:finish").reason(), "忘记的机会不能签约");
        assertFalse(world.engineeringStateOf(WorldWebScenario.ENGINE_A).orElseThrow()
                .contracts().containsKey("B-2"));
        world.close();
    }

    @Test
    void retentionPaymentRespectsItsOwnDueDate() throws Exception {
        Path file = journal("retention");
        long retentionDue = INVOICE_AT + 60L * DAY;
        List<EngineeringRun.CustomerEventPlan> events = List.of(
                event("adv-b1", "ADVANCE", 1_400L, 0, "2000.00", ""),
                event("acc-b1", "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"),
                event("ret-early", "RETENTION_PAY", INVOICE_AT + 1_000L, 0, "1000.00", ""),
                event("ret-late", "RETENTION_PAY", retentionDue + 1_000L, 0, "1000.00", ""));
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                bConfig("5000.00", 6, events));
        prepareReading(world);
        prepareOperating(world);
        scheduleManagerRequests(world);
        world.advanceTo(ACCEPT_AT + 100L);
        world.advanceTo(INVOICE_AT + 2_000L);
        EngineeringModel.State state = world.engineeringStateOf(WorldWebScenario.ENGINE_A)
                .orElseThrow();
        assertEquals(new BigDecimal("1000.00"), state.claims().get(CONTRACT).arRetention(),
                "质保未到期不得提前收回：");
        assertEquals("NOT_DUE", world.snapshot().engineering().orElseThrow().reports()
                .get("customer:ret-early").reason(), "质保到期日早于当期到期日，必须显式拒绝");

        world.advanceTo(retentionDue + 2_000L);
        state = world.engineeringStateOf(WorldWebScenario.ENGINE_A).orElseThrow();
        assertEquals(new BigDecimal("0.00"), state.claims().get(CONTRACT).arRetention(),
                "质保到期后客户真实付款：" + world.snapshot().engineering().orElseThrow()
                        .reports().get("customer:ret-late"));
        WorldRuntime.CustomerAudit customer = world.customerAudit(WorldWebScenario.ENGINE_A)
                .orElseThrow();
        assertTrue(customer.payments().stream().anyMatch(p -> "RETENTION".equals(p.kind())
                && p.amount().compareTo(new BigDecimal("1000.00")) == 0),
                "质保付款必须单独记账：" + customer.payments());
        world.close();
    }

    @Test
    void v6DeclaresCustomerRequestAndWaiverToolsWithoutOldVersions() {
        for (String tool : List.of(RoleTradingView.TOOL_BOARD_PROPOSE_WAIVER,
                RoleTradingView.TOOL_ENGINEERING_REQUEST_ACCEPTANCE,
                RoleTradingView.TOOL_ENGINEERING_REQUEST_PAYMENT,
                RoleTradingView.TOOL_ENGINEERING_REQUEST_RETENTION,
                RoleTradingView.TOOL_ENGINEERING_WAIVE)) {
            assertFalse(DecisionRun.toolParameters(tool, DecisionRun.TOOL_CONTRACT_VERSION_V6)
                    .isEmpty(), "v6 必须声明参数：" + tool);
            assertTrue(DecisionRun.toolParameters(tool, DecisionRun.TOOL_CONTRACT_VERSION_V5)
                    .isEmpty(), "旧工具合同不得出现新工具：" + tool);
            Long millis = WorldWebScenario.engineeringDecisionConfig().actionMillisByTool()
                    .get(tool);
            assertNotNull(millis, "工程决定配置必须声明工时：" + tool);
            assertTrue(millis > 0, "工程动作必须有正工时：" + tool);
        }
        // 新工具与既有工程工具同一场景/同一工时声明，不新增第六场景
        assertEquals(WorldWebScenario.ENGINEERING_ACTION_MILLIS,
                WorldWebScenario.engineeringDecisionConfig().actionMillisByTool()
                        .get(RoleTradingView.TOOL_ENGINEERING_WAIVE));
    }

    @Test
    void tamperedCustomerPaymentInSelfConsistentFrameIsRejected() throws Exception {
        Path base = journal("customer-tamper-base");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(base,
                bConfig("5000.00", 6, bEvents()));
        prepareReading(world);
        prepareOperating(world);
        world.advanceTo(1_600L);
        world.close();

        Path variant = journal("customer-tamper");
        Files.copy(base, variant);
        tamperLastCustomerPayment(variant);
        assertThrows(IOException.class, () -> WorldWebScenario.engineeringRuntime(variant,
                bConfig("5000.00", 6, bEvents())), "自洽篡改客户付款必须被逐帧重演拒绝");
    }

    private static void tamperLastCustomerPayment(Path file) throws IOException {
        List<byte[]> frames = readFrames(file);
        ObjectMapperHolder holder = new ObjectMapperHolder();
        int index = frames.size() - 1;
        com.fasterxml.jackson.databind.node.ObjectNode batch =
                (com.fasterxml.jackson.databind.node.ObjectNode) holder.mapper.readTree(
                        frames.get(index));
        com.fasterxml.jackson.databind.node.ArrayNode events =
                (com.fasterxml.jackson.databind.node.ArrayNode) batch.get("events");
        boolean touched = false;
        for (com.fasterxml.jackson.databind.JsonNode event : events) {
            if (!"STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                continue;
            }
            com.fasterxml.jackson.databind.node.ObjectNode snapshot =
                    (com.fasterxml.jackson.databind.node.ObjectNode) holder.mapper.readTree(
                            event.get("payload").asText());
            com.fasterxml.jackson.databind.node.ObjectNode engineering =
                    (com.fasterxml.jackson.databind.node.ObjectNode) snapshot.get("engineering");
            if (engineering == null) {
                throw new IllegalArgumentException("该帧没有工程子集");
            }
            com.fasterxml.jackson.databind.node.ArrayNode payments =
                    (com.fasterxml.jackson.databind.node.ArrayNode) engineering.get("customer")
                            .get("payments");
            if (payments.isEmpty()) {
                throw new IllegalArgumentException("该帧没有客户付款");
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) payments.get(0))
                    .put("amount", new BigDecimal("1.00"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) event)
                    .put("payload", holder.mapper.writeValueAsString(snapshot));
            touched = true;
        }
        if (!touched) {
            throw new IllegalArgumentException("该帧没有状态快照");
        }
        frames.set(index, holder.mapper.writeValueAsBytes(batch));
        Files.write(file, frameBytes(frames));
    }

    /** 便携的 mapper 持有者，避免在测试方法签名里重复引入 Jackson。 */
    private static final class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
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
