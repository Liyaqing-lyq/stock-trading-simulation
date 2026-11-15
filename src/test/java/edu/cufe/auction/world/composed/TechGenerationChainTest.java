package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CompanyOperationsRun;
import edu.cufe.auction.world.ManufacturingTemplateModel;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公司一（技术换代 / 客户认证）的完整闭环：真实预算与任免 → 认证第一轮失败、第二轮通过 →
 * 采购到货 → 生产完工 → 合同交付 → 回款；旧代库存与已签合同不因换代消失。
 */
class TechGenerationChainTest {

    private static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;

    @TempDir
    Path dir;

    @Test
    void certificationFailsThenPassesAndOldGenerationStillShips() throws Exception {
        Path journal = dir.resolve("tech.journal");
        try (WorldRuntime world = ComposedManufacturingThreeScenario.open(journal)) {
            String company = ComposedManufacturingThreeScenario.TECH;
            String manager = ComposedManufacturingThreeScenario.TECH_MANAGER;
            OperationsFixture.grantBudgetAndAppoint(world, company,
                    ComposedManufacturingThreeScenario.TECH_DIRECTORS, manager, "tech",
                    List.of(0L, 1L, 2L));
            assertTrue(world.isActiveManager(company, manager, world.snapshot().timeMillis()));

            // 认证依据必须来自本人真实阅读。
            long readAt = OperationsFixture.publishAndRead(world, "cert:auto-oem:P1:2",
                    List.of(manager), "tech-cert");
            String readId = OperationsFixture.readIdOf(world, manager, "cert:auto-oem:P1:2");

            // 第一轮：冻结计划 FAIL（不重抽）。
            schedule(world, "t-cert1", company, manager, CompanyOperationsRun.Action.REQUEST_CERT,
                    OperationsFixture.op().cert("trial-1", readId, "auto-oem", "P1", 2, 1)
                            .build());
            CompanyOperationsRun.ReportEntry certRequested =
                    world.operationsRun().orElseThrow().report("t-cert1-task:finish");
            assertTrue(certRequested.accepted(), "认证申请必须真实付款成功："
                    + certRequested.reason());
            assertTrue(certRequested.amount().compareTo(new BigDecimal("200.00")) == 0,
                    "认证费必须真实支付 200.00：" + certRequested.amount());
            world.advanceTo(readAt + 2000L + DAY);
            System.out.println("DEBUG reports=" + world.operationsRun().orElseThrow().reports()
                    .values().stream().map(r -> r.taskId() + ":" + r.accepted() + ":" + r.reason())
                    .toList());
            world.advanceTo(20L * DAY + 1L);
            CompanyOperationsRun run = world.operationsRun().orElseThrow();
            assertEquals(ManufacturingTemplateModel.TrialStatus.FAILED,
                    run.company(company).ledger().trials()
                            .get(OperationsFixture.trialKey("auto-oem", "P1", 2, 1)).status());
            assertTrue(run.company(company).ledger().certs().isEmpty(), "第一轮失败不得生成认证");

            // 同轮不能重抽：真实入口接受排程，但到期帧必须被纯核拒绝并留下真实回报。
            long retryDue = world.snapshot().timeMillis() + 2000L;
            world.scheduleOperationsAction("t-cert1-retry", "t-cert1-retry-task", retryDue,
                    company, manager, CompanyOperationsRun.Action.REQUEST_CERT.name(),
                    OperationsFixture.op().cert("trial-1b", readId, "auto-oem", "P1", 2, 1)
                            .build(), 1000L);
            world.advanceTo(retryDue);
            CompanyOperationsRun.ReportEntry retried = world.operationsRun().orElseThrow()
                    .report("t-cert1-retry-task:finish");
            assertFalse(retried.accepted(), "同一客户/产品/代际/轮次不得重抽");
            assertEquals(ManufacturingTemplateModel.TRIAL_ALREADY_REQUESTED, retried.reason());
            assertEquals(0, retried.amount().signum(), "被拒绝的试验不得再扣认证费");

            // 旧代（gen 1）不需要认证：先生产 5 件并真交付。
            produce(world, company, manager, "t-a", 10L, 5L);
            long now = world.snapshot().timeMillis();
            signAndDeliver(world, company, manager, "t-old", "P1", 1, 5L, new BigDecimal("100.00"),
                    now);
            BigDecimal cashAfterOld = world.snapshot().accounts()
                    .get(ComposedManufacturingThreeScenario.TECH_CASH).cash();

            // 第二轮认证通过（计划 readyAt = 第 40 日）。
            schedule(world, "t-cert2", company, manager, CompanyOperationsRun.Action.REQUEST_CERT,
                    OperationsFixture.op().cert("trial-2", readId, "auto-oem", "P1", 2, 2)
                            .build());
            var secondTrial = world.operationsRun().orElseThrow().company(company).ledger()
                    .trials().get(OperationsFixture.trialKey("auto-oem", "P1", 2, 2));
            long resultAt = Math.max(40L * DAY, secondTrial.finishAt());
            world.advanceTo(resultAt - 1);
            assertEquals(ManufacturingTemplateModel.TrialStatus.IN_PROGRESS,
                    world.operationsRun().orElseThrow().company(company).ledger().trials()
                            .get(secondTrial.key()).status(), "实际工时和计划就绪两者满足前不得提前完成");
            world.advanceTo(resultAt);
            CompanyOperationsRun afterCert = world.operationsRun().orElseThrow();
            assertEquals(ManufacturingTemplateModel.TrialStatus.PASSED,
                    afterCert.company(company).ledger().trials()
                            .get(OperationsFixture.trialKey("auto-oem", "P1", 2, 2)).status(),
                    () -> afterCert.reports().values().stream().filter(row -> row.action().contains("CERT")).toList().toString());
            assertFalse(afterCert.company(company).ledger().certs().isEmpty(),
                    "第二轮通过必须生成真实认证");

            // 换代不等于销毁旧库存：gen 1 合同与交付历史仍在账上。
            ManufacturingTemplateModel.Contract oldContract =
                    afterCert.company(company).ledger().contracts().get("t-old-contract");
            assertEquals(ManufacturingTemplateModel.ContractStatus.FULFILLED, oldContract.status());
            assertEquals(5L, oldContract.delivered());

            // 现金：付认证费 2×200、人工与材料真实流出，回款真实流入。
            ManufacturingTemplateModel.Report report =
                    world.operationsStatement(company).orElseThrow();
            assertTrue(report.balanced(), "A=L+E 必须闭合");
            assertEquals(0, report.certificationExpense().compareTo(new BigDecimal("400.00")),
                    "两轮认证费各 200.00");
            assertTrue(report.revenue().signum() > 0, "交付必须确认收入");
            assertTrue(cashAfterOld.compareTo(world.snapshot().accounts()
                    .get(ComposedManufacturingThreeScenario.TECH_CASH).cash()) >= 0,
                    "认证与采购必须真实减少经营现金");
            assertEquals(0, report.receivables().compareTo(new BigDecimal("0.00")),
                    "回款后不得留下应收");
        }
    }

    /** 采购 → 到货（系统子任务）→ 投产 → 完工（系统子任务）。 */
    private static void produce(WorldRuntime world, String company, String manager, String prefix,
            long materialQty, long batchQty) throws Exception {
        String poCommand = prefix + "-po";
        schedule(world, poCommand, company, manager, CompanyOperationsRun.Action.ORDER_MATERIAL,
                OperationsFixture.op().material("MAT-A", materialQty).build());
        String poId = OperationsFixture.poId(poCommand);
        ManufacturingTemplateModel.PurchaseOrder order = world.operationsRun().orElseThrow()
                .company(company).ledger().purchases().get(poId);
        assertNotNull(order, "采购单必须已建立");
        world.advanceTo(order.arrivalAt());
        assertEquals(ManufacturingTemplateModel.PurchaseStatus.RECEIVED,
                world.operationsRun().orElseThrow().company(company).ledger().purchases()
                        .get(poId).status(), "到货必须由系统子任务完成");
        schedule(world, prefix + "-batch", company, manager,
                CompanyOperationsRun.Action.START_BATCH,
                OperationsFixture.op().product("P1", 1, batchQty).build());
        String batchId = OperationsFixture.batchId(prefix + "-batch");
        ManufacturingTemplateModel.Batch planned = world.operationsRun().orElseThrow()
                .company(company).ledger().batches().get(batchId);
        assertNotNull(planned, "批次必须已开工");
        world.advanceTo(planned.completesAt());
        assertEquals(ManufacturingTemplateModel.BatchStatus.FINISHED,
                world.operationsRun().orElseThrow().company(company).ledger().batches()
                        .get(batchId).status(), "完工必须由系统子任务完成");
    }

    private static boolean hasPurchaseStatus(WorldRuntime world, String company, String poId,
            ManufacturingTemplateModel.PurchaseStatus status) {
        ManufacturingTemplateModel.PurchaseOrder order = world.operationsRun().orElseThrow()
                .company(company).ledger().purchases().get(poId);
        return order != null && order.status() == status;
    }

    private static boolean hasBatchStatus(WorldRuntime world, String company, String batchId,
            ManufacturingTemplateModel.BatchStatus status) {
        ManufacturingTemplateModel.Batch batch = world.operationsRun().orElseThrow()
                .company(company).ledger().batches().get(batchId);
        return batch != null && batch.status() == status;
    }

    private static void signAndDeliver(WorldRuntime world, String company, String manager,
            String prefix, String product, int generation, long quantity, BigDecimal price,
            long at) throws Exception {
        schedule(world, prefix + "-sign", company, manager,
                CompanyOperationsRun.Action.SIGN_CONTRACT,
                OperationsFixture.op().contract(prefix + "-contract", "auto-oem", product,
                        generation, quantity, price, at + DAY, at + 10L * DAY).build());
        world.advanceTo(at + DAY);
        schedule(world, prefix + "-deliver", company, manager,
                CompanyOperationsRun.Action.DELIVER,
                OperationsFixture.op().delivery(prefix + "-contract", quantity,
                        prefix + "-recv-1").build());
        var receivable = world.operationsRun().orElseThrow().company(company).ledger()
                .receivables().get(prefix + "-recv-1");
        assertNotNull(receivable, "实际交付必须形成应收");
        world.advanceTo(receivable.dueAt());
        schedule(world, prefix + "-collect", company, manager,
                CompanyOperationsRun.Action.COLLECT,
                OperationsFixture.op().receivable(prefix + "-recv-1").build());
    }

    private static void schedule(WorldRuntime world, String commandId, String company,
            String person, CompanyOperationsRun.Action action, CompanyOperationsRun.Fields fields)
            throws Exception {
        long now = world.snapshot().timeMillis();
        long due = now + 2000L;
        world.scheduleOperationsAction(commandId, commandId + "-task", due, company, person,
                action.name(), fields, 1000L);
        world.advanceTo(due);
        var result = world.operationsRun().orElseThrow().reports().values().stream()
                .filter(report -> report.commandId().equals(commandId))
                .max(java.util.Comparator.comparingLong(CompanyOperationsRun.ReportEntry::position))
                .orElseThrow();
        assertTrue(result.accepted(), () -> commandId + " -> " + result.reason());
    }

    private static void nowAdvance(WorldRuntime world, long millis) throws Exception {
        world.advanceTo(world.snapshot().timeMillis() + millis);
    }
}
