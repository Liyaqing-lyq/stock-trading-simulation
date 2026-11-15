package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.CompanyOperationsRun;
import edu.cufe.auction.world.ManufacturingTemplateModel;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import static edu.cufe.auction.world.CompanyOperationsRun.Action.*;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实治理、资金、物量、有限需求与跨期自动任务的另外两条经营链。 */
class ManufacturingOtherChainsTest {
    private static final long DAY = OperationsFixture.DAY;
    @TempDir Path dir;

    @Test
    void structureSupplyLimitRealDeliveryAndQuotedExpansion() throws Exception {
        Path journal = dir.resolve("structure.journal");
        try (WorldRuntime world = ComposedManufacturingThreeScenario.open(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, STRUCT, STRUCT_DIRECTORS,
                    STRUCT_MANAGER, "s-govern", List.of(0L, 1L, 2L));
            var refused = act(world, STRUCT, STRUCT_MANAGER, "s-too-many", ORDER_MATERIAL,
                    OperationsFixture.op().material("MAT-C", 7).build());
            assertFalse(refused.accepted());
            assertEquals(ManufacturingTemplateModel.SUPPLIER_CAPACITY_EXCEEDED, refused.reason());
            ok(world, STRUCT, STRUCT_MANAGER, "s-po", ORDER_MATERIAL,
                    OperationsFixture.op().material("MAT-C", 6).build());
            world.advanceTo(ledger(world, STRUCT).purchases().values().stream()
                    .mapToLong(ManufacturingTemplateModel.PurchaseOrder::arrivalAt).max().orElseThrow());
            ok(world, STRUCT, STRUCT_MANAGER, "s-batch", START_BATCH,
                    OperationsFixture.op().product("P1", 1, 3).build());
            long finish = ledger(world, STRUCT).batches().values().iterator().next().completesAt();
            world.advanceTo(finish);
            long now = world.snapshot().timeMillis();
            var excessive = act(world, STRUCT, STRUCT_MANAGER, "s-over-budget", SIGN_CONTRACT,
                    OperationsFixture.op().contract("too-large", "builder-a", "P1", 1, 3,
                            new BigDecimal("400.00"), now + DAY, now + 10 * DAY).build());
            assertFalse(excessive.accepted(), "客户当期预算1000不能签1200");
            deliverCollect(world, STRUCT, STRUCT_MANAGER, "s-sale", "builder-a", "P1", 3);
            var invented = act(world, STRUCT, STRUCT_MANAGER, "s-free-capacity", START_EXPANSION,
                    OperationsFixture.op().expansion("invented", new BigDecimal("1.00"),
                            1L, 1, 999999L).build());
            assertEquals("EXPANSION_OFFER_MISMATCH", invented.reason());
            ok(world, STRUCT, STRUCT_MANAGER, "s-build", START_EXPANSION,
                    OperationsFixture.op().expansion("plant-2", new BigDecimal("2000.00"),
                            30 * DAY, 3, 50L).build());
            var expansion = ledger(world, STRUCT).expansions().get("plant-2");
            assertEquals(ManufacturingTemplateModel.ExpansionStatus.BUILDING, expansion.status());
            world.advanceTo(expansion.completesAt() - 1);
            assertEquals(ManufacturingTemplateModel.ExpansionStatus.BUILDING,
                    ledger(world, STRUCT).expansions().get("plant-2").status());
            world.advanceTo(expansion.completesAt());
            assertEquals(ManufacturingTemplateModel.ExpansionStatus.ONLINE,
                    ledger(world, STRUCT).expansions().get("plant-2").status());
            assertTrue(world.operationsStatement(STRUCT).orElseThrow().balanced());
            assertFalse(world.operationsActions(STRUCT).contains(COMPLETE_EXPANSION.name()));
        }
        try (WorldRuntime restored = ComposedManufacturingThreeScenario.open(journal)) {
            assertEquals(ManufacturingTemplateModel.ExpansionStatus.ONLINE,
                    ledger(restored, STRUCT).expansions().get("plant-2").status());
            assertTrue(restored.operationsStatement(STRUCT).orElseThrow().balanced());
        }
    }

    @Test
    void newUseNeedsActualReadingCertificationAndDemandWindow() throws Exception {
        Path journal = dir.resolve("versa.journal");
        try (WorldRuntime world = ComposedManufacturingThreeScenario.open(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, VERSA, VERSA_DIRECTORS,
                    VERSA_MANAGER, "v-govern", List.of(0L, 1L, 2L, 3L));
            OperationsFixture.publishAndRead(world, "cert:utility-new:NEW-P:1",
                    List.of(VERSA_MANAGER), "v-read");
            String read = OperationsFixture.readIdOf(world, VERSA_MANAGER, "cert:utility-new:NEW-P:1");
            OperationsFixture.publishAndRead(world, "unrelated-story", List.of(VERSA_MANAGER), "v-wrong-read");
            String unrelated = OperationsFixture.readIdOf(world, VERSA_MANAGER, "unrelated-story");
            var falseEvidence = act(world, VERSA, VERSA_MANAGER, "v-false-evidence", REQUEST_CERT,
                    OperationsFixture.op().cert("v-false", unrelated, "utility-new", "NEW-P", 1, 1).build());
            assertFalse(falseEvidence.accepted());
            assertTrue(ledger(world, VERSA).trials().isEmpty(), "其它文章不能冒充本次认证材料");
            ok(world, VERSA, VERSA_MANAGER, "v-cert", REQUEST_CERT,
                    OperationsFixture.op().cert("v-trial", read, "utility-new", "NEW-P", 1, 1).build());
            world.advanceTo(25L * DAY);
            assertEquals(ManufacturingTemplateModel.TrialStatus.PASSED,
                    ledger(world, VERSA).trials().values().iterator().next().status());
            long now = world.snapshot().timeMillis();
            var early = act(world, VERSA, VERSA_MANAGER, "v-early", SIGN_CONTRACT,
                    OperationsFixture.op().contract("too-early", "utility-new", "NEW-P", 1, 2,
                            new BigDecimal("100.00"), now + DAY, now + 4 * DAY).build());
            assertFalse(early.accepted(), "认证通过不等于当前已有需求窗口");
            world.advanceTo(60L * DAY);
            ok(world, VERSA, VERSA_MANAGER, "v-po-e", ORDER_MATERIAL,
                    OperationsFixture.op().material("MAT-E", 2).build());
            ok(world, VERSA, VERSA_MANAGER, "v-po-f", ORDER_MATERIAL,
                    OperationsFixture.op().material("MAT-F", 2).build());
            world.advanceTo(ledger(world, VERSA).purchases().values().stream()
                    .mapToLong(ManufacturingTemplateModel.PurchaseOrder::arrivalAt).max().orElseThrow());
            ok(world, VERSA, VERSA_MANAGER, "v-batch", START_BATCH,
                    OperationsFixture.op().product("NEW-P", 1, 2).build());
            world.advanceTo(ledger(world, VERSA).batches().values().iterator().next().completesAt());
            deliverCollect(world, VERSA, VERSA_MANAGER, "v-sale", "utility-new", "NEW-P", 2);
            assertTrue(world.operationsStatement(VERSA).orElseThrow().balanced());
        }
        try (WorldRuntime restored = ComposedManufacturingThreeScenario.open(journal)) {
            assertEquals(0, restored.operationsStatement(VERSA).orElseThrow().receivables().signum());
            assertTrue(restored.operationsStatement(VERSA).orElseThrow().balanced());
        }
    }

    private static ManufacturingTemplateModel.State ledger(WorldRuntime world, String company) {
        return world.operationsRun().orElseThrow().company(company).ledger();
    }

    private static CompanyOperationsRun.ReportEntry act(WorldRuntime world, String company,
            String manager, String id, CompanyOperationsRun.Action action,
            CompanyOperationsRun.Fields fields) throws Exception {
        long due = world.snapshot().timeMillis() + 2000;
        world.scheduleOperationsAction(id, id + "-task", due, company, manager, action.name(), fields, 1000);
        world.advanceTo(due);
        return world.operationsRun().orElseThrow().report(id + "-task:finish");
    }

    private static void ok(WorldRuntime world, String company, String manager, String id,
            CompanyOperationsRun.Action action, CompanyOperationsRun.Fields fields) throws Exception {
        var result = act(world, company, manager, id, action, fields);
        assertTrue(result.accepted(), () -> id + ": " + result.reason());
    }

    private static void deliverCollect(WorldRuntime world, String company, String manager,
            String id, String customer, String product, long quantity) throws Exception {
        long now = world.snapshot().timeMillis();
        ok(world, company, manager, id, SIGN_CONTRACT,
                OperationsFixture.op().contract(id, customer, product, 1, quantity,
                        new BigDecimal(company.equals(VERSA) ? "80.00" : "100.00"), now + DAY, now + 10 * DAY).build());
        world.advanceTo(now + DAY);
        ok(world, company, manager, id + "-delivery", DELIVER,
                OperationsFixture.op().delivery(id, quantity, id + "-ar").build());
        var ar = ledger(world, company).receivables().get(id + "-ar");
        world.advanceTo(ar.dueAt());
        ok(world, company, manager, id + "-collect", COLLECT,
                OperationsFixture.op().receivable(id + "-ar").build());
    }
}
