package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.bank.BankActions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** 存款换桶不产生经营损失，期末与完整报告必须含真实银行权利。 */
class ManufacturingBankCompositionTest {
    @TempDir Path dir;
    @Test
    void depositIsCountedOnceAndDoesNotBreakPeriodCloseOrRecovery() throws Exception {
        Path journal = dir.resolve("deposit.journal");
        try (WorldRuntime world = openRunnable(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, TECH, TECH_DIRECTORS, TECH_MANAGER,
                    "bank-govern", List.of(0L, 1L));
            long due = world.snapshot().timeMillis() + 100L;
            world.openRoleSession(TECH_MANAGER).bankAction("deposit", "deposit-task", due, 1L,
                    new WorldRoleSession.BankActionDraft("deposit", BankActions.Kind.DEPOSIT,
                            "north", TECH, new BigDecimal("100.00"), 0, 0, "", "", "", ""));
            world.advanceTo(due);
            assertEquals(new BigDecimal("11900.00"), world.snapshot().accounts().get(TECH_CASH).cash(), world.bankActionAudits().toString());
            assertDoesNotThrow(() -> world.advanceTo(30L * OperationsFixture.DAY));
            var report = ComposedCompanyReport.of(world, TECH);
            assertTrue(report.balanced());
            assertEquals(new BigDecimal("11950.00"), report.totalAssets());
        }
        try (WorldRuntime restored = openRunnable(journal)) {
            assertTrue(ComposedCompanyReport.of(restored, TECH).balanced());
            assertEquals(1, restored.operationsRun().orElseThrow().company(TECH).ledger().closedPeriods());
        }
    }
}
