package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.CompanyOperationsRun;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 经营人物工时必须进入同一世界焦点，开始与完成分开。 */
class ManufacturingWorkBoundaryTest {
    @TempDir Path dir;

    @Test
    void realBeginPrecedesCompletionWithoutEarlyPurchase() throws Exception {
        try (WorldRuntime world = open()) {
            long now = world.snapshot().timeMillis();
            schedule(world, "first", now + 5000);
            var begin = world.snapshot().pending().stream()
                    .filter(task -> task.taskId().equals("first-task")).findFirst().orElseThrow();
            assertEquals(PendingTask.ENGINEERING_BEGIN_V1, begin.kind());
            assertEquals(now + 3000, begin.dueTimeMillis());
            world.advanceTo(now + 3000);
            assertTrue(world.operationsRun().orElseThrow().company("mfg-tech").ledger().purchases().isEmpty());
            assertTrue(world.snapshot().pending().stream().anyMatch(task -> task.taskId().equals("first-task:finish")));
            world.advanceTo(now + 5000);
            assertEquals(1, world.operationsRun().orElseThrow().company("mfg-tech").ledger().purchases().size());
        }
    }

    @Test
    void onePersonCannotReserveOverlappingCompanyWork() throws Exception {
        try (WorldRuntime world = open()) {
            long now = world.snapshot().timeMillis();
            schedule(world, "first", now + 5000);
            long before = world.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> schedule(world, "overlap", now + 6000));
            assertEquals(before, world.snapshot().position(), "焦点拒绝不得留下第二份工作");
        }
    }

    @Test
    void contractualExpensesCloseWithoutInventingBoardApproval() throws Exception {
        try (WorldRuntime world = ComposedManufacturingThreeScenario.open(dir.resolve("unmanaged.journal"))) {
            world.advanceTo(2L * OperationsFixture.DAY * 30L);
            for (var company : world.operationsRun().orElseThrow().companies()) {
                assertEquals(2, company.ledger().closedPeriods(), company.companyPartyId());
                assertEquals(2, company.ledger().bills().size(), "开局固定费用必须记账");
            }
            assertTrue(world.snapshot().pending().stream().anyMatch(task ->
                    task.kind().equals(PendingTask.COMPANY_OPERATIONS_CLOSE_V1)
                    && task.dueTimeMillis() == 90L * OperationsFixture.DAY));
        }
    }

    @Test
    void expiredAppointmentCannotFinishWorkOrBecomeValidAfterReappointment() throws Exception {
        try (WorldRuntime world = open()) {
            var stale = world.openRoleSession(ComposedManufacturingThreeScenario.TECH_MANAGER);
            long end = world.snapshot().timeMillis() + 90L * OperationsFixture.DAY;
            schedule(world, "ends-after-term", end + 500L);
            world.advanceTo(end - 1500L);
            assertTrue(world.snapshot().pending().stream()
                    .anyMatch(t -> t.taskId().equals("ends-after-term-task:finish")));
            world.advanceTo(end + 500L);
            assertFalse(world.operationsRun().orElseThrow().report("ends-after-term-task:finish").accepted());
            assertTrue(world.operationsRun().orElseThrow().company("mfg-tech").ledger().purchases().isEmpty());
            long beforeRenewal = world.snapshot().position();
            OperationsFixture.grantBudgetAndAppoint(world, "mfg-tech",
                    ComposedManufacturingThreeScenario.TECH_DIRECTORS,
                    ComposedManufacturingThreeScenario.TECH_MANAGER, "second-term", List.of(3L, 4L));
            assertTrue(world.isActiveManager("mfg-tech", ComposedManufacturingThreeScenario.TECH_MANAGER,
                    world.snapshot().timeMillis()), () -> world.historyIndex(beforeRenewal, 100).stream()
                            .filter(r -> r.commandId().startsWith("task:"))
                            .map(r -> r.commandId() + ":" + r.result()).toList().toString());
            assertThrows(IllegalArgumentException.class, () -> stale.operationsAction("stale", "stale-task",
                    world.snapshot().timeMillis() + 5000L, "mfg-tech", CompanyOperationsRun.Action.ORDER_MATERIAL,
                    OperationsFixture.op().material("MAT-A", 1L).build(), 2000L));
            world.openRoleSession(ComposedManufacturingThreeScenario.TECH_MANAGER).operationsAction(
                    "fresh", "fresh-task", world.snapshot().timeMillis() + 5000L, "mfg-tech",
                    CompanyOperationsRun.Action.ORDER_MATERIAL,
                    OperationsFixture.op().material("MAT-A", 1L).build(), 2000L);
        }
    }

    private WorldRuntime open() throws Exception {
        var world = ComposedManufacturingThreeScenario.open(dir.resolve("world.journal"));
        OperationsFixture.grantBudgetAndAppoint(world, "mfg-tech",
                ComposedManufacturingThreeScenario.TECH_DIRECTORS,
                ComposedManufacturingThreeScenario.TECH_MANAGER, "work-boundary", List.of(0L));
        return world;
    }

    private static void schedule(WorldRuntime world, String id, long due) throws Exception {
        world.scheduleOperationsAction(id, id + "-task", due, "mfg-tech",
                ComposedManufacturingThreeScenario.TECH_MANAGER,
                CompanyOperationsRun.Action.ORDER_MATERIAL.name(),
                OperationsFixture.op().material("MAT-A", 10L).build(), 2000L);
    }
}
