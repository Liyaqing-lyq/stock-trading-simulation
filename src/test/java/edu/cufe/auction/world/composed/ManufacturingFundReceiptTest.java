package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.CompanyOperationsRun;
import edu.cufe.auction.world.ManufacturingTemplateModel;
import edu.cufe.auction.world.WorldRuntime;
import java.nio.file.Path;
import java.util.List;

/** 独立核对真实世界采购付款的现金和银行命令是否同批保存。 */
class ManufacturingFundReceiptTest {
    @org.junit.jupiter.api.io.TempDir Path dir;
    @org.junit.jupiter.api.Test
    void actualPaymentAndBankReceiptPersistTogether() throws Exception {
        Path path = dir.resolve("world.journal");
        int before;
        try (WorldRuntime world = ComposedManufacturingThreeScenario.open(path)) {
            String company = ComposedManufacturingThreeScenario.TECH;
            String manager = ComposedManufacturingThreeScenario.TECH_MANAGER;
            OperationsFixture.grantBudgetAndAppoint(world, company,
                    ComposedManufacturingThreeScenario.TECH_DIRECTORS, manager,
                    "root-fund-probe", List.of(0L));
            before = world.bankOf().orElseThrow().decode(world.snapshot().accounts()).state().commands().size();
            var cashBefore = world.snapshot().accounts().get(ComposedManufacturingThreeScenario.TECH_CASH).cash();
            long due = world.snapshot().timeMillis() + 2000;
            world.scheduleOperationsAction("root-po", "root-po-task", due, company, manager,
                    CompanyOperationsRun.Action.ORDER_MATERIAL.name(),
                    OperationsFixture.op().material("MAT-A", 10L).build(), 1000L);
            world.advanceTo(due + 2 * ManufacturingTemplateModel.DAY_MILLIS + 10);
            var ledger = world.operationsRun().orElseThrow().company(company).ledger();
            var cashAfter = world.snapshot().accounts().get(ComposedManufacturingThreeScenario.TECH_CASH).cash();
            int after = world.bankOf().orElseThrow().decode(world.snapshot().accounts()).state().commands().size();
            System.out.println("bankCommandsBefore=" + before + " bankCommandsAfter=" + after);
            System.out.println("companyCashBefore=" + cashBefore + " companyCashAfter=" + cashAfter);
            System.out.println("purchaseStatus=" + ledger.purchases().get(OperationsFixture.poId("root-po")).status());
            if (cashAfter.compareTo(cashBefore) >= 0) throw new AssertionError("No actual procurement payment");
            if (after <= before) throw new AssertionError("Actual payment lost its bank command receipt");
        }
        try (WorldRuntime world = ComposedManufacturingThreeScenario.open(path)) {
            int restored = world.bankOf().orElseThrow().decode(world.snapshot().accounts()).state().commands().size();
            if (restored <= before) throw new AssertionError("Reopened world lost payment receipt");
            System.out.println("restoredBankCommands=" + restored);
        }
    }
}
