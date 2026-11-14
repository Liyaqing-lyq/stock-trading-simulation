package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正式工程入口（{@link WorldWebScenario#openEngineering(Path)}）发布的机会资料必须写清真实条款：
 * 经理读完能核对自己将签什么，结构化机会输入不得出现资料里没有的数。
 *
 * <p>只读真实发布的资料正文与真实阅读→签约链；不引入新参数、不改既有公开机会口径。</p>
 */
class EngineeringOpportunityDocumentTest {

    private static final long DAY = EngineeringModel.DAY_MILLIS;
    private static final String A = WorldWebScenario.ENGINE_A;
    private static final String CONTRACT = "A1";

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-opportunity-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static InformationModel.Document document(WorldRuntime world, String docId) {
        InformationModel.State info = world.snapshot().information().orElseThrow().information();
        for (InformationModel.Document document : info.documents()) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        throw new AssertionError("缺少资料：" + docId);
    }

    @Test
    void productionOpportunityBodyCarriesRealTerms() throws Exception {
        Path file = journal("body");
        WorldWebHost.Opened opened = WorldWebScenario.openEngineering(file);
        WorldRuntime world = opened.runtime();
        try {
            world.advanceTo(200L); // 机会资料在开局 100 到期的任务里真实发布
            EngineeringRun.Config config = WorldWebScenario.engineeringConfig();
            for (EngineeringRun.CompanySpec spec : config.companies()) {
                for (EngineeringRun.ContractPlan plan : spec.contracts()) {
                    String body = document(world, "engineering-opportunity:" + plan.contractId())
                            .at(1L).body();
                    assertTrue(body.contains("实验预置"), "必须写明实验预置来源：" + body);
                    assertTrue(body.contains(spec.companyPartyId()), "必须写明所属公司：" + body);
                    assertTrue(body.contains(plan.contractId()), "必须写明合同标识：" + body);
                    assertTrue(body.contains(plan.unitPrice().setScale(2,
                                    java.math.RoundingMode.HALF_UP).toPlainString()),
                            "正文单价必须与结构化输入一致：" + body);
                    assertTrue(body.contains(plan.unitCost().toPlainString()),
                            "正文单位成本必须与结构化输入一致：" + body);
                    for (Long units : plan.trancheUnits()) {
                        assertTrue(body.contains(units + " 件"),
                                "阶段数量必须以单位写明：" + body);
                    }
                    assertTrue(body.contains("预收比例") && body.contains("质保扣留比例"),
                            "必须写清付款/质保条款：" + body);
                    assertTrue(body.contains(plan.netDays() + " 天")
                                    && body.contains(plan.retentionNetDays() + " 天"),
                            "必须写清付款期与质保期：" + body);
                    assertTrue(body.contains("阅读本资料才能签约"), "必须写清合法动作前提：" + body);

                    // 私有客户事实与未来计划不得写进公开机会正文
                    assertFalse(body.contains(spec.customerAccountId()),
                            "客户账户不得出现在机会正文：" + body);
                    if (spec.customerPool() != null) {
                        assertFalse(body.contains(spec.customerPool().budgetPerPeriod()
                                        .toPlainString()),
                                "客户期间预算不得出现在机会正文：" + body);
                    }
                    assertFalse(body.contains("ADVANCE") || body.contains("CUSTOMER_EVENT"),
                            "未来客户回应计划不得出现在机会正文：" + body);
                    assertFalse(body.contains("客户现金") && body.contains("20000.00"),
                            "客户现金不得出现在机会正文：" + body);
                }
            }
        } finally {
            world.close();
        }
    }

    @Test
    void readingProductionOpportunityUnlocksRealSign() throws Exception {
        Path file = journal("read-sign");
        WorldWebHost.Opened opened = WorldWebScenario.openEngineering(file);
        WorldRuntime world = opened.runtime();
        try {
            governance(world);

            // 未读机会：签约必须被真实拒绝
            world.scheduleEngineeringAction("sign-unread", "sign-unread-task", 1_200L, A, "dave",
                    "SIGN", CONTRACT, "", 0, 0, BigDecimal.ZERO, "", 0);
            world.advanceTo(1_300L);
            assertEquals("OPPORTUNITY_NOT_READ",
                    world.snapshot().engineering().orElseThrow()
                            .reports().get("sign-unread-task:finish").reason(),
                    "没实际读资料不能签约");
            assertFalse(world.engineeringStateOf(A).orElseThrow().contracts()
                    .containsKey(CONTRACT));

            // 真实阅读正式机会资料（发布在 100，阅读耗时 1000ms）
            world.scheduleStartReading("read-a1", "read-a1-task", 1_400L, "dave",
                    "engineering-opportunity:" + CONTRACT, 1L);
            world.advanceTo(2_500L);

            // 结构化机会输入与正文一致：只出现资料里的真实条款
            String input = world.engineeringRoleInput("dave").toString();
            assertTrue(input.contains("\"contractId\":\"" + CONTRACT + "\""), input);
            assertTrue(input.contains("\"unitPrice\":\"100.00\""), input);
            assertTrue(input.contains("\"unitCost\":\"40.0000\""), input);
            String body = document(world, "engineering-opportunity:" + CONTRACT).at(1L).body();
            assertTrue(body.contains("100.00") && body.contains("40.0000"),
                    "结构化数字必须都能在正文里核对：" + body);
            assertFalse(input.contains("periodBudgetLeft") || input.contains("poolDeclared"),
                    "机会输入不得夹带客户预算：" + input);

            world.scheduleEngineeringAction("sign-a1", "sign-a1-task", 2_600L, A, "dave",
                    "SIGN", CONTRACT, "", 0, 0, BigDecimal.ZERO, "", 0);
            world.advanceTo(2_700L);
            assertEquals("SIGNED", world.snapshot().engineering().orElseThrow()
                    .reports().get("sign-a1-task:finish").reason(), "读完后必须能真实签约");
            EngineeringModel.Contract signed = world.engineeringStateOf(A).orElseThrow()
                    .contracts().get(CONTRACT);
            assertNotNull(signed);
            assertEquals(new BigDecimal("100.00"), signed.unitPrice(), "签约沿用资料里的单价");
        } finally {
            world.close();
        }
    }

    /** 真实预算 + 任免治理：dave 自 700 起任 ENG-A 经理。 */
    private static void governance(WorldRuntime world) throws Exception {
        world.schedulePublishDocument("pub-mat", "pub-mat-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        String[] persons = {"alice", "bob", "carol", "dave", "erin"};
        for (int i = 0; i < persons.length; i++) {
            world.scheduleStartReading("read-mat-" + persons[i],
                    "read-mat-" + persons[i] + "-task", 20L + i * 2L, persons[i],
                    "b-material", 1L);
            world.scheduleStartReading("read-appt-" + persons[i],
                    "read-appt-" + persons[i] + "-task", 60L + i * 2L, persons[i],
                    "appt-material", 1L);
        }
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
}
