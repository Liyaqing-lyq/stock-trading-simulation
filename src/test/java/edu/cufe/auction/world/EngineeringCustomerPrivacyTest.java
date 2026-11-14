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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部客户预算不是经理的当前已知事实（Root 裁定）：人物投影与 v6 模型输入都不得含
 * 客户内部期间/预算余量/闭期状态，God 审计照旧保留真实值；不补 0、不在客户端隐藏。
 *
 * <p>覆盖两条人物入口（{@code companyCustomerView} 与 {@code engineeringRoleInput}）与 God 入口
 * （{@code customerAudit}），并验证本人自己的请求/已收到回应/实际到账仍然可见。</p>
 */
class EngineeringCustomerPrivacyTest {

    private static final long DAY = EngineeringModel.DAY_MILLIS;
    private static final long WORK_DONE = 1_450L + 40L * DAY;
    private static final long ACCEPT_AT = WORK_DONE + 250L;
    private static final String C1 = "P-1";
    private static final String A = WorldWebScenario.ENGINE_A;
    private static final String CUSTOMER = "ENG-A-CUS";

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-privacy-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static EngineeringRun.ContractPlan contract(String id) {
        return new EngineeringRun.ContractPlan(id, new BigDecimal("100.00"),
                new BigDecimal("60.0000"), 2000, 1000, 15, 60, List.of(40L));
    }

    private static EngineeringRun.CustomerPool pool() {
        return new EngineeringRun.CustomerPool(CUSTOMER, 30L * DAY,
                new BigDecimal("5000.00"), 6);
    }

    private static EngineeringRun.CompanySpec aSpec() {
        EngineeringRun.CompanySpec base = WorldWebScenario.engineeringConfig().specOf(A);
        return new EngineeringRun.CompanySpec(base.companyPartyId(), base.companyOwnerId(),
                base.companyAccountId(), base.customerAccountId(), base.supplierAccountId(),
                base.opsAccountId(), DAY, 6_000, base.charter(), List.of(contract(C1)), pool());
    }

    /** 第二家公司声明**同一个**客户池（声明必须逐字一致，否则开局就被拒）。 */
    private static EngineeringRun.CompanySpec bSpecSharingPool() {
        EngineeringRun.CompanySpec base = WorldWebScenario.engineeringConfig()
                .specOf(WorldWebScenario.ENGINE_B);
        return new EngineeringRun.CompanySpec(base.companyPartyId(), base.companyOwnerId(),
                base.companyAccountId(), CUSTOMER, "ENG-B-SUP", "ENG-B-OPS", DAY, 6_000,
                base.charter(), List.of(contract("Q-1")), pool());
    }

    private static EngineeringRun.CustomerEventPlan event(String id, String kind, long at,
            long units, String amount, String requestKind) {
        boolean payment = List.of("ADVANCE", "PAY", "RETENTION_PAY", "RECOVERY_PAY")
                .contains(kind);
        return new EngineeringRun.CustomerEventPlan(id, A, C1, 0, kind, at, units,
                amount == null ? BigDecimal.ZERO : new BigDecimal(amount),
                payment ? CUSTOMER : "", "冻结客户证据：" + id, requestKind);
    }

    private static EngineeringRun.Config config(boolean sharePool,
            List<EngineeringRun.CustomerEventPlan> events) {
        List<EngineeringRun.CompanySpec> companies = sharePool
                ? List.of(aSpec(), bSpecSharingPool()) : List.of(aSpec());
        return new EngineeringRun.Config("隐私裁定实验参数", companies, Map.of(A, events));
    }

    private static List<EngineeringRun.CustomerEventPlan> baseEvents() {
        return List.of(
                event("adv-1", "ADVANCE", 1_400L, 0, "2000.00", ""),
                event("acc-1", "ACCEPT", ACCEPT_AT, 40L, null, "ACCEPTANCE"),
                event("rej-1", "REJECT", ACCEPT_AT + 500L, 0, null, "PAYMENT"));
    }

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

    private static void signAndWork(WorldRuntime world) throws Exception {
        world.scheduleEngineeringAction("sign-1", "sign-1-task", 1_350L, A, "dave", "SIGN",
                C1, "", 0, 0, BigDecimal.ZERO, "", 0);
        world.scheduleEngineeringAction("work-1", "work-1-task", 1_400L, A, "dave",
                "START_WORK", C1, "W-1", 0, 40, BigDecimal.ZERO, "", 0);
    }

    /** 客户子集里禁止出现的字段（由隐藏预算策略导出，本人没有披露依据）。 */
    private static final List<String> FORBIDDEN = List.of("period", "periodBudgetLeft",
            "periodClosed", "poolDeclared", "poolAccountId", "customerAccountId");

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 只检查 customer 节点：董事会自己的预算期间等真实字段不属于客户内部信息。 */
    private static void assertCustomerNodeClean(String input, String where) throws Exception {
        com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(input);
        com.fasterxml.jackson.databind.JsonNode managerships = root.path("managerships");
        assertTrue(managerships.isArray() && managerships.size() > 0, where + " 缺少经理区块");
        for (com.fasterxml.jackson.databind.JsonNode node : managerships) {
            com.fasterxml.jackson.databind.JsonNode customer = node.path("customer");
            assertTrue(customer.isObject(), where + " 缺少 customer 区块");
            for (String field : FORBIDDEN) {
                assertFalse(customer.has(field),
                        where + " 的 customer 不得含外部客户内部字段 " + field + "：" + customer);
            }
            assertTrue(customer.has("stages"), where + " 仍应保留本公司合同阶段");
        }
    }

    private static void assertRoleViewClean(WorldRuntime.CustomerRoleView view, String where) {
        List<String> names = new ArrayList<>();
        for (java.lang.reflect.RecordComponent component
                : WorldRuntime.CustomerRoleView.class.getRecordComponents()) {
            names.add(component.getName());
        }
        for (String field : FORBIDDEN) {
            assertFalse(names.contains(field), where + " 的 CustomerRoleView 不得再有字段 " + field);
        }
        String text = view.toString();
        for (String field : FORBIDDEN) {
            assertFalse(text.contains(field), where + " 的视图文本不得含 " + field);
        }
    }

    @Test
    void personViewAndModelInputCarryNoCounterpartyBudgetFields() throws Exception {
        Path file = journal("no-fields");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(false, baseEvents()));
        governance(world);
        signAndWork(world);
        world.advanceTo(1_500L);

        WorldRuntime.CustomerRoleView view = world.companyCustomerView("dave", A).orElseThrow();
        assertRoleViewClean(view, "普通档案");
        assertTrue(view.companyCash() != null, "本公司账必须保留");
        assertTrue(view.requests().isEmpty(), "本档案还没有经理请求");

        String input = world.engineeringRoleInput("dave").toString();
        assertCustomerNodeClean(input, "v6 模型输入");
        assertTrue(input.contains("\"customer\""), "客户请求/回应区块仍然存在");
        world.close();

        // 跨 JVM 重开：新 v6 输入形状必须稳定（不得因恢复而补回被移除的字段）
        try (WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file,
                config(false, baseEvents()))) {
            assertCustomerNodeClean(reopened.engineeringRoleInput("dave").toString(),
                    "重开后的 v6 模型输入");
        }
    }

    @Test
    void godAuditKeepsRealPoolWhilePersonViewHasNoSuchField() throws Exception {
        Path file = journal("god-keeps");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(false, baseEvents()));
        governance(world);
        BigDecimal before = world.customerAudit(A).orElseThrow().periodBudgetLeft();
        assertNotNull(before, "God 必须看到真实期间预算余量");
        signAndWork(world);
        world.advanceTo(1_500L);
        WorldRuntime.CustomerAudit audit = world.customerAudit(A).orElseThrow();
        assertNotNull(audit.periodBudgetLeft(), "God 仍看得到预算余量");
        assertTrue(audit.periodBudgetLeft().compareTo(before) < 0,
                "真实预收 2000 必须减少 God 看到的余量：" + audit.periodBudgetLeft());
        assertEquals(audit.period(), 0L, "God 保留真实期间序号");
        String input = world.engineeringRoleInput("dave").toString();
        assertCustomerNodeClean(input, "v6 模型输入");
        world.close();
    }

    @Test
    void twoCompaniesMayShareOnePoolButPersonViewStaysPerCompany() throws Exception {
        Path file = journal("shared-pool");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file,
                config(true, baseEvents()));
        governance(world);
        signAndWork(world);
        world.advanceTo(1_500L);
        // 同一客户池被两家公司声明：开局合法，God 只有一个真实余量
        WorldRuntime.CustomerAudit audit = world.customerAudit(A).orElseThrow();
        assertEquals(CUSTOMER, audit.customerAccountId(), "两家公司指向同一个真实客户账户");
        assertTrue(audit.periodBudgetLeft().compareTo(new BigDecimal("5000.00")) < 0,
                "共享池余量已因真实付款下降");
        // 人物视图仍是“本人公司”的切片，且没有任何池字段
        String input = world.engineeringRoleInput("dave").toString();
        assertCustomerNodeClean(input, "共享池下的 v6 模型输入");
        WorldRuntime.CustomerRoleView view = world.companyCustomerView("dave", A).orElseThrow();
        assertEquals(A, view.companyPartyId());
        assertRoleViewClean(view, "共享池下的人物客户视图");
        world.close();
    }

    @Test
    void ownResponseBecomesVisibleOnlyAfterItArrives() throws Exception {
        Path file = journal("own-response");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config(false, baseEvents()));
        governance(world);
        signAndWork(world);
        world.scheduleEngineeringAction("req-acc", "req-acc-task", ACCEPT_AT - 100L, A, "dave",
                "REQUEST_ACCEPTANCE", C1, "", 0, 40, BigDecimal.ZERO, "", 0, 200L);
        world.advanceTo(ACCEPT_AT - 100L);
        WorldRuntime.CustomerRoleView pending = world.companyCustomerView("dave", A)
                .orElseThrow();
        assertEquals(1, pending.requests().size(), "自己的请求可见");
        assertFalse(pending.requests().get(0).answered(), "客户还没回应");
        assertTrue(pending.responses().stream()
                .noneMatch(r -> "ACCEPT".equals(r.kind())), "验收回应还没发生");

        world.advanceTo(ACCEPT_AT);
        WorldRuntime.CustomerRoleView answered = world.companyCustomerView("dave", A)
                .orElseThrow();
        assertTrue(answered.requests().get(0).answered(), "回应到达后自己的请求变为已答");
        List<EngineeringRun.Customer.Response> acceptance = new ArrayList<>();
        for (EngineeringRun.Customer.Response response : answered.responses()) {
            if ("ACCEPT".equals(response.kind())) {
                acceptance.add(response);
            }
        }
        assertEquals(1, acceptance.size(), "只保留真实收到的验收回应");
        assertEquals("ACCEPTED", acceptance.get(0).outcome());
        String input = world.engineeringRoleInput("dave").toString();
        assertCustomerNodeClean(input, "收到回应后的 v6 模型输入");
        world.close();
    }
}
