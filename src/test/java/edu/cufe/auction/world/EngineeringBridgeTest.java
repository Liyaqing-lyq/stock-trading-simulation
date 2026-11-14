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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema18b 真实治理/经营桥：五席预算与任免 → 经理实读机会 → 签约 → 开工双预算 →
 * 客户验收/开票/回款 → 终止 → 撤职后新动作拒绝。
 *
 * <p>只使用真实 WorldRuntime/WorldJournal、真实 BoardModel/EngineeringModel 与同一 accounts；
 * 客户响应来自冻结计划，经理请求不是必定成功。</p>
 */
class EngineeringBridgeTest {

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-bridge-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static EngineeringRun.ContractPlan plan(String id, String price, String cost,
            long... tranches) {
        List<Long> units = new ArrayList<>();
        for (long unit : tranches) {
            units.add(unit);
        }
        return new EngineeringRun.ContractPlan(id, new BigDecimal(price),
                new BigDecimal(cost).setScale(4), 0, 0, 0, 0, units);
    }

    private static EngineeringRun.Config bridgeConfig() {
        EngineeringRun.Config base = WorldWebScenario.engineeringConfig();
        EngineeringRun.CompanySpec a = base.specOf(WorldWebScenario.ENGINE_A);
        EngineeringRun.CompanySpec b = base.specOf(WorldWebScenario.ENGINE_B);
        EngineeringRun.CompanySpec aSpec = new EngineeringRun.CompanySpec(
                a.companyPartyId(), a.companyOwnerId(), a.companyAccountId(),
                a.customerAccountId(), a.supplierAccountId(), a.opsAccountId(),
                a.unitMillis(), a.laborBp(), a.charter(),
                List.of(plan("A1", "100.00", "40.00", 10), plan("A2", "50.00", "20.00", 40)));
        EngineeringRun.CompanySpec bSpec = new EngineeringRun.CompanySpec(
                b.companyPartyId(), b.companyOwnerId(), b.companyAccountId(),
                b.customerAccountId(), b.supplierAccountId(), b.opsAccountId(),
                b.unitMillis(), b.laborBp(), b.charter(),
                List.of(plan("B1", "400.00", "240.00", 5, 5)));
        List<EngineeringRun.CustomerEventPlan> events = List.of(
                new EngineeringRun.CustomerEventPlan("a1-accept", WorldWebScenario.ENGINE_A,
                        "A1", 0, "ACCEPT", 700_000L, 10L, BigDecimal.ZERO, "", "客户验收"),
                new EngineeringRun.CustomerEventPlan("a1-pay", WorldWebScenario.ENGINE_A,
                        "A1", 0, "PAY", 720_000L, 0L, new BigDecimal("1000.00"), "ENG-A-CUS",
                        "客户付款"),
                new EngineeringRun.CustomerEventPlan("b1-accept", WorldWebScenario.ENGINE_B,
                        "B1", 0, "ACCEPT", 700_000L, 5L, BigDecimal.ZERO, "", "客户验收"));
        return new EngineeringRun.Config("18b 实验参数", List.of(aSpec, bSpec),
                Map.of(WorldWebScenario.ENGINE_A, events.subList(0, 2),
                        WorldWebScenario.ENGINE_B, events.subList(2, 3)));
    }

    /** 准备 A 公司的材料与两张机会：五席董事读材料、经理读机会（真实阅读任务）。 */
    private static void prepareA(WorldRuntime world) throws Exception {
        world.schedulePublishDocument("pub-material", "pub-material-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt-material", "pub-appt-material-task", 10L,
                "appt-material", WorldWebScenario.INVESTOR,
                InformationModel.Audience.publicAudience(), "任免材料", "实验任免材料",
                "实验材料", 10L, List.of());
        for (String contract : List.of("A1", "A2")) {
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
        world.scheduleStartReading("read-opp-a1", "read-opp-a1-task", 100L, "dave",
                "engineering-opportunity:A1", 1L);
        world.scheduleStartReading("read-opp-a2", "read-opp-a2-task", 120L, "dave",
                "engineering-opportunity:A2", 1L);
    }

    @Test
    void twoContractsSignedInReverseOrderReopenCleanly() throws Exception {
        Path file = journal("two-contracts");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, bridgeConfig());
        prepareA(world);
        budgetAndAppoint(world, "eng-a", "alice", "b-material", "appt-material", "dave");
        // 反序签约：先 A2（字典序在后）再 A1，已用标识集合顺序与哈希序不同
        world.scheduleEngineeringAction("sign-a2", "sign-a2-task", 1_450L, "eng-a", "dave",
                "SIGN", "A2", "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("sign-a1", "sign-a1-task", 1_500L, "eng-a", "dave",
                "SIGN", "A1", "", 0, 0, BigDecimal.ZERO, "", 0);
        world.advanceTo(1_600L);
        assertEquals(2, world.engineeringStateOf("eng-a").orElseThrow().contracts().size(),
                "reports=" + world.snapshot().engineering().orElseThrow().reports().values()
                        .stream().filter(r -> r.taskId().startsWith("sign-")).toList());
        long position = world.snapshot().position();
        world.close();
        try (WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file, bridgeConfig())) {
            assertEquals(position, reopened.snapshot().position(), "合法多合同档必须能重开");
            assertEquals(2, reopened.engineeringStateOf("eng-a").orElseThrow()
                    .contracts().size());
        }
    }

    @Test
    void queuedManagerActionRevokedBeforeBeginIsRejectedAndWorldContinues() throws Exception {
        Path file = journal("begin-revoke");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, bridgeConfig());
        prepareA(world);
        budgetAndAppoint(world, "eng-a", "alice", "b-material", "appt-material", "dave");
        // 先签 A1（真实读过机会），再开工：物理施工 10 单位 × 60000 → completesAt 600700
        world.scheduleEngineeringAction("sign-a1", "sign-a1-task", 1_400L, "eng-a", "dave",
                "SIGN", "A1", "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("start-w", "start-w-task", 1_450L, "eng-a", "dave",
                "START_WORK", "A1", "W-A1", 0, 10, BigDecimal.ZERO, "", 0);
        // 合法入队的经理动作，到期晚于撤职
        world.scheduleEngineeringAction("sign-late", "sign-late-task", 2_000L, "eng-a", "dave",
                "SIGN", "A2", "", 0, 0, BigDecimal.ZERO, "", 0);
        // 撤职会议（真实五席、真实授权引用），生效 1700
        String meetingId = "rv";
        world.scheduleEngineeringAppointmentConvene(meetingId + "-c", meetingId + "-c-task",
                1_600L, "eng-a", "alice", meetingId, 1_645L, 1_650L, 1_720L);
        String[] persons = votersOf(world, "eng-a");
        for (int i = 0; i < persons.length; i++) {
            world.scheduleEngineeringAttend(meetingId + "-a-" + i,
                    meetingId + "-a-" + i + "-task", 1_610L + i, "eng-a", persons[i],
                    meetingId, false);
        }
        world.scheduleEngineeringMaterial(meetingId + "-m", meetingId + "-m-task", 1_620L,
                "eng-a", "alice", meetingId, "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal(meetingId + "-p", meetingId + "-p-task",
                1_630L, "eng-a", "alice", meetingId, List.of(),
                new BoardModel.AppointmentRequest("mgr-a", "dave", "eng-a",
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.REVOKE,
                        1_750L, null));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote(meetingId + "-v-" + i, meetingId + "-v-" + i + "-task",
                    1_700L + i, "eng-a", persons[i], meetingId, BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement(meetingId + "-i", meetingId + "-i-task", 1_750L,
                "eng-a", "alice", meetingId);
        world.advanceTo(1_900L);
        assertFalse(world.activeManagerForTest("dave", "eng-a"),
                "rv reports=" + world.snapshot().engineering().orElseThrow().reports().values()
                        .stream().filter(r -> r.taskId().startsWith("rv")).toList());
        // 到期在撤职之后的在途意图：BEGIN 业务拒绝，不抛出、不暂停、不留合同
        world.advanceTo(2_100L);
        assertFalse(world.isPaused(), "BEGIN 业务拒绝不得中断推进");
        EngineeringRun.Report rejected = world.snapshot().engineering().orElseThrow()
                .reports().get("sign-late-task");
        assertTrue(rejected != null && !rejected.accepted(),
                "在途意图必须留下拒绝报告："
                        + world.snapshot().engineering().orElseThrow().reports().keySet());
        assertFalse(world.engineeringStateOf("eng-a").orElseThrow().contracts()
                .containsKey("A2"));
        // 已付物理施工继续到完工
        world.advanceTo(601_500L);
        EngineeringModel.WorkOrder order = world.engineeringStateOf("eng-a").orElseThrow()
                .workOrders().get("W-A1");
        assertTrue(order != null, "workOrders=" + world.engineeringStateOf("eng-a").orElseThrow()
                .workOrders().keySet());
        assertEquals(EngineeringModel.WorkStatus.COMPLETED, order.status(),
                "撤职不得抹掉已付工单的物理完工");
        world.close();
    }

    @Test
    void legacyManagerAppointmentEntryIsForbiddenIn18() throws Exception {
        Path file = journal("legacy-appoint");
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(file, bridgeConfig())) {
            IllegalArgumentException rejected = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> world.scheduleAppointment("legacy-mgr", "legacy-mgr-task", 1_000L,
                            "mgr-bypass", "dave", "eng-a", RoleAccess.Role.COMPANY_MANAGER,
                            1_000L, 900_000L),
                    "18 档不得用 legacy 入口直接任免经理");
            assertTrue(rejected.getMessage().contains("董事会任免"), rejected.getMessage());
            assertFalse(world.activeManagerForTest("dave", "eng-a"));
        }
    }

    @Test
    void customerPayerMustBeTheCompanyCustomerAccount() throws Exception {
        Path file = journal("payer-mismatch");
        EngineeringRun.Config base = bridgeConfig();
        EngineeringRun.Config mismatched = new EngineeringRun.Config(base.paramSource(),
                base.companies(), Map.of(WorldWebScenario.ENGINE_A, List.of(
                        new EngineeringRun.CustomerEventPlan("bad-pay", WorldWebScenario.ENGINE_A,
                                "A1", 0, "PAY", 720_000L, 0L, new BigDecimal("1000.00"),
                                "ENG-B-CUS", "错池付款")),
                WorldWebScenario.ENGINE_B, List.of()));
        IllegalArgumentException rejected = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> WorldWebScenario.engineeringRuntime(file, mismatched),
                "付款账户与公司客户账户不一致必须在开局前拒绝");
        assertTrue(rejected.getMessage().contains("付款账户"), rejected.getMessage());
        assertFalse(Files.exists(file), "拒绝必须发生在写盘前");
    }

    @Test
    void fullGovernanceAndOperatingChain() throws Exception {
        Path file = journal("chain");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, bridgeConfig());
        // 材料与机会都是真实 Information 文档；不发消息、不代读。
        world.schedulePublishDocument("pub-material", "pub-material-task", 10L,
                "b-material", WorldWebScenario.INVESTOR,
                InformationModel.Audience.publicAudience(), "预算材料", "实验预算材料",
                "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt-material", "pub-appt-material-task", 10L,
                "appt-material", WorldWebScenario.INVESTOR,
                InformationModel.Audience.publicAudience(), "任免材料", "实验任免材料",
                "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-opp-a1", "pub-opp-a1-task", 10L,
                "engineering-opportunity:A1", WorldWebScenario.INVESTOR,
                InformationModel.Audience.direct("dave"), "A1 机会", "实验机会 A1",
                "冻结机会", 10L, List.of());
        world.schedulePublishDocument("pub-opp-a2", "pub-opp-a2-task", 10L,
                "engineering-opportunity:A2", WorldWebScenario.INVESTOR,
                InformationModel.Audience.direct("dave"), "A2 机会", "实验机会 A2",
                "冻结机会", 10L, List.of());
        world.schedulePublishDocument("pub-opp-b1", "pub-opp-b1-task", 10L,
                "engineering-opportunity:B1", WorldWebScenario.INVESTOR,
                InformationModel.Audience.direct("nina"), "B1 机会", "实验机会 B1",
                "冻结机会", 10L, List.of());
        int index = 0;
        for (String reader : List.of("alice", "bob", "carol", "dave", "erin")) {
            world.scheduleStartReading("read-mat-" + reader, "read-mat-" + reader + "-task",
                    20L + index * 2L, reader, "b-material", 1L);
            world.scheduleStartReading("read-appt-" + reader, "read-appt-" + reader + "-task",
                    60L + index * 2L, reader, "appt-material", 1L);
            index++;
        }
        int index2 = 0;
        for (String reader : List.of("rita", "ed", "sam", "bea", "nina")) {
            world.scheduleStartReading("read-b-appt-" + reader,
                    "read-b-appt-" + reader + "-task", 70L + index2 * 2L, reader,
                    "appt-material", 1L);
            index2++;
        }
        world.scheduleStartReading("read-opp-a1", "read-opp-a1-task", 100L, "dave",
                "engineering-opportunity:A1", 1L);
        world.scheduleStartReading("read-opp-a2", "read-opp-a2-task", 120L, "dave",
                "engineering-opportunity:A2", 1L);
        world.scheduleStartReading("read-opp-b1", "read-opp-b1-task", 100L, "nina",
                "engineering-opportunity:B1", 1L);

        budgetAndAppoint(world, "eng-a", "alice", "b-material", "appt-material", "dave");
        // 经理在 A 真实读机会后签约；B 由 alice 任经理另开一轮会。
        appoint(world, "eng-b", "rita", "appt-material", "nina", "mgr-b", 1_400L, 900_000L,
                BoardModel.AppointmentAction.APPOINT, "appt-b", 700L);
        world.scheduleEngineeringAction("sign-a1", "sign-a1-task", 1_450L, "eng-a", "dave",
                "SIGN", "A1", "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("sign-b1", "sign-b1-task", 1_500L, "eng-b", "nina",
                "SIGN", "B1", "", 0, 0, BigDecimal.ZERO, "", 0);
        world.advanceTo(1_600L);
        assertEquals(1, world.engineeringStateOf("eng-a").orElseThrow().contracts().size(),
                "reports=" + world.snapshot().engineering().orElseThrow().reports()
                        + " obs=" + world.snapshot().information().orElseThrow().information()
                        .observations().stream().filter(o -> o.docId().equals("b-material"))
                        .map(o -> o.personPartyId() + "@" + o.completedAt()).toList()
                        + " roles=" + world.snapshot().roles().orElseThrow().access().appointments());
        assertEquals(1, world.engineeringStateOf("eng-b").orElseThrow().contracts().size(),
                "b-report=" + world.snapshot().engineering().orElseThrow()
                        .reports().get("sign-b1-task")
                        + " b-impl=" + world.snapshot().engineering().orElseThrow()
                        .reports().get("appt-b-i-task")
                        + " b-votes=" + world.snapshot().engineering().orElseThrow()
                        .reports().values().stream()
                        .filter(r -> r.taskId().startsWith("appt-b-v-")).toList()
                        + " roles=" + world.snapshot().roles().orElseThrow().access().appointments());

        world.scheduleEngineeringAction("start-a1", "start-a1-task", 1_650L, "eng-a", "dave",
                "START_WORK", "A1", "W-A1", 0, 10, BigDecimal.ZERO, "", 0);
        world.advanceTo(1_700L);
        EngineeringModel.State aState = world.engineeringStateOf("eng-a").orElseThrow();
        assertEquals(1, aState.workOrders().size());
        assertEquals(new BigDecimal("160.00"), aState.payable());
        assertEquals(new BigDecimal("4760.00"),
                world.snapshot().accounts().get("ENG-A").cash());

        world.scheduleEngineeringAction("invoice-a1", "invoice-a1-task", 710_000L, "eng-a",
                "dave", "INVOICE", "A1", "", 0, 0, BigDecimal.ZERO, "", 0);
        world.advanceTo(730_000L);
        aState = world.engineeringStateOf("eng-a").orElseThrow();
        assertTrue(aState.claims().get("A1").revenue().compareTo(new BigDecimal("1000.00")) == 0);
        assertEquals(new BigDecimal("0.00"), aState.claims().get("A1").arCurrent());
        assertEquals(new BigDecimal("5760.00"),
                world.snapshot().accounts().get("ENG-A").cash());

        // 撤职：真实会议 → RoleAccess.revoke；之后新的签约请求在排程准入即被拒绝
        // （这是排程准入拒绝，不是 BEGIN 业务拒绝；在途意图的 BEGIN 拒绝见
        //  queuedManagerActionRevokedBeforeBeginIsRejectedAndWorldContinues）。
        revoke(world, "eng-a", "alice", "appt-material", "mgr-a", "dave", 800_000L);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> world.scheduleEngineeringAction("sign-a2-after-revoke",
                        "sign-a2-after-revoke-task", 900_000L, "eng-a", "dave", "SIGN", "A2",
                        "", 0, 0, BigDecimal.ZERO, "", 0),
                "撤职后新动作必须在排程准入被拒绝");
        world.advanceTo(920_000L);
        assertFalse(world.engineeringStateOf("eng-a").orElseThrow().contracts()
                .containsKey("A2"));
        assertFalse(world.snapshot().engineering().orElseThrow().reports()
                .containsKey("sign-a2-after-revoke-task"), "被拒绝的排程不得留下成功报告");
        assertFalse(world.activeManagerForTest("dave", "eng-a"),
                "revoke=" + world.snapshot().engineering().orElseThrow()
                        .reports().get("revoke-meeting-i-task")
                        + " votes=" + world.snapshot().engineering().orElseThrow().reports()
                        .values().stream().filter(r -> r.taskId().startsWith("revoke-meeting-v-"))
                        .toList());
        world.close();

        WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file, bridgeConfig());
        assertEquals(world.snapshot().position(), reopened.snapshot().position());
        assertTrue(reopened.snapshot().engineering().isPresent());
        reopened.close();
    }

    private static String[] votersOf(WorldRuntime world, String company) {
        var seats = world.boardOfCompany(company).orElseThrow().board().charter().seats();
        return new String[]{seats.get(0).partyId(), seats.get(1).partyId(),
                seats.get(2).partyId()};
    }

    private void budgetAndAppoint(WorldRuntime world, String company, String chair,
            String material, String apptMaterial, String manager) throws Exception {
        world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L, company, chair,
                "budget-meeting", 150L, 200L, 300L);
        for (int i = 0; i < 5; i++) {
            String[] persons = {"alice", "bob", "carol", "dave", "erin"};
            world.scheduleEngineeringAttend("budget-a-" + i, "budget-a-" + i + "-task",
                    110L + i, company, persons[i], "budget-meeting", false);
        }
        world.scheduleEngineeringMaterial("budget-m", "budget-m-task", 130L, company, chair,
                "budget-meeting", "m1", material, 1L);
        world.scheduleEngineeringBudgetProposal("budget-p", "budget-p-task", 140L, company,
                chair, "budget-meeting", List.of(
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                new BigDecimal("10000.00")),
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                new BigDecimal("10000.00"))));
        String[] persons = {"alice", "bob", "carol", "dave", "erin"};
        for (int i = 0; i < 4; i++) {
            world.scheduleEngineeringVote("budget-v-" + i, "budget-v-" + i + "-task",
                    220L + i, company, persons[i], "budget-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringVote("budget-v-4", "budget-v-4-task", 224L, company, "erin",
                "budget-meeting", BoardModel.Vote.NO);
        world.scheduleEngineeringImplement("budget-i", "budget-i-task", 320L, company, chair,
                "budget-meeting");
        appoint(world, company, chair, apptMaterial, manager, "mgr-a", 700L, 900_000L,
                BoardModel.AppointmentAction.APPOINT, "appt-meeting", 0L);
    }

    private void appoint(WorldRuntime world, String company, String chair, String material,
            String manager, String appointmentId, long effectiveAt, long endsAt,
            BoardModel.AppointmentAction action, String meetingId) throws Exception {
        appoint(world, company, chair, material, manager, appointmentId, effectiveAt, endsAt,
                action, meetingId, 0L);
    }

    private void appoint(WorldRuntime world, String company, String chair, String material,
            String manager, String appointmentId, long effectiveAt, long endsAt,
            BoardModel.AppointmentAction action, String meetingId, long base) throws Exception {
        world.scheduleEngineeringAppointmentConvene(meetingId + "-c", meetingId + "-c-task",
                400L + base, company, chair, meetingId, 450L + base, 500L + base, 600L + base);
        String[] persons = votersOf(world, company);
        for (int i = 0; i < persons.length; i++) {
            world.scheduleEngineeringAttend(meetingId + "-a-" + i,
                    meetingId + "-a-" + i + "-task", 410L + base + i, company, persons[i],
                    meetingId, false);
        }
        world.scheduleEngineeringMaterial(meetingId + "-m", meetingId + "-m-task", 430L + base,
                company, chair, meetingId, "m1", material, 1L);
        world.scheduleEngineeringAppointmentProposal(meetingId + "-p", meetingId + "-p-task",
                440L + base, company, chair, meetingId, List.of(),
                new BoardModel.AppointmentRequest(appointmentId, manager, company,
                        RoleAccess.Role.COMPANY_MANAGER, action, effectiveAt,
                        action == BoardModel.AppointmentAction.REVOKE ? null : endsAt));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote(meetingId + "-v-" + i, meetingId + "-v-" + i + "-task",
                    520L + base + i, company, persons[i], meetingId, BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement(meetingId + "-i", meetingId + "-i-task", 620L + base,
                company, chair, meetingId);
        world.advanceTo(700L + base);
    }

    private void revoke(WorldRuntime world, String company, String chair, String material,
            String appointmentId, String manager, long effectiveAt) throws Exception {
        String meetingId = "revoke-meeting";
        world.scheduleEngineeringAppointmentConvene(meetingId + "-c", meetingId + "-c-task",
                760_000L, company, chair, meetingId, 770_000L, 780_000L, 790_000L);
        String[] persons = votersOf(world, company);
        for (int i = 0; i < persons.length; i++) {
            world.scheduleEngineeringAttend(meetingId + "-a-" + i,
                    meetingId + "-a-" + i + "-task", 762_000L + i, company, persons[i],
                    meetingId, false);
        }
        world.scheduleEngineeringMaterial(meetingId + "-m", meetingId + "-m-task", 765_000L,
                company, chair, meetingId, "m1", material, 1L);
        world.scheduleEngineeringAppointmentProposal(meetingId + "-p", meetingId + "-p-task",
                766_000L, company, chair, meetingId, List.of(),
                new BoardModel.AppointmentRequest(appointmentId, manager, company,
                        RoleAccess.Role.COMPANY_MANAGER,
                        BoardModel.AppointmentAction.REVOKE, effectiveAt, null));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote(meetingId + "-v-" + i, meetingId + "-v-" + i + "-task",
                    785_000L + i, company, persons[i], meetingId, BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement(meetingId + "-i", meetingId + "-i-task", 800_000L,
                company, chair, meetingId);
        world.advanceTo(810_000L);
    }
}
