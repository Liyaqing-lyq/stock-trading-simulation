package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Root独立反例：重发、换任期与开始前后的人物焦点，均用真实外部动作。 */
class ManufacturingFundingRootBoundaryTest {
    @TempDir Path dir;

    private static String sign(WorldRuntime world, String command, long due, long duration) throws Exception {
        return world.scheduleFundedManufacturing(command, command + "-task", due,
                ManufacturingFundingWorld.MANAGER, "SIGN", command + "-contract", "", 1,
                new BigDecimal("100.00"), 10L * 86_400_000, -1,
                ManufacturingFunding.POLICY_FROZEN_MAPPING_V1, "CASH|CO", "CASH|SUP", duration);
    }

    @Test
    void purchaseAndLaborSpendActualDepositWithoutTouchingOperatingCash() throws Exception {
        try (WorldRuntime world = ManufacturingFundingWorld.open(dir.resolve("deposit.journal"))) {
            ManufacturingFundingChainTest.budgetMeeting(world, WorldWebScenario.COMPANY,
                    "deposit-budget", "10000.00", "10000.00");
            long now = world.snapshot().timeMillis();
            world.openRoleSession(ManufacturingFundingWorld.MANAGER).bankAction("deposit", "deposit-task",
                    now + 100, 1, new WorldRoleSession.BankActionDraft("deposit",
                            edu.cufe.auction.world.bank.BankActions.Kind.DEPOSIT, "north",
                            WorldWebScenario.COMPANY, new BigDecimal("800.00"), 0, 0, "", "", "", ""));
            world.advanceTo(now + 100);
            assertEquals(new BigDecimal("200.00"), world.snapshot().accounts().get("CO").cash());
            long due = now + 200;
            world.scheduleFundedManufacturing("dep-po", "dep-po-task", due,
                    ManufacturingFundingWorld.MANAGER, "PURCHASE_ORDER", "DEP-PO", "", 10,
                    null, 0, -1, ManufacturingFunding.POLICY_FROZEN_MAPPING_V1,
                    "DEPOSIT|north|cufe", "CASH|SUP", 1);
            world.advanceTo(due + 86_400_000);
            assertEquals("GOODS_RECEIVED", ManufacturingFundingChainTest.reason(world, "mfg-receipt:DEP-PO"));
            assertEquals(new BigDecimal("300.00"), world.bankOf().orElseThrow()
                    .decode(world.snapshot().accounts()).state().depositOf("north", "cufe"));
            long labor = world.snapshot().timeMillis() + 100;
            world.scheduleFundedManufacturing("dep-labor", "dep-labor-task", labor,
                    ManufacturingFundingWorld.MANAGER, "PRODUCTION", "DEP-BATCH", "", 10,
                    null, 0, -1, ManufacturingFunding.POLICY_FROZEN_MAPPING_V1,
                    "DEPOSIT|north|cufe", "CASH|OPS", 1);
            world.advanceTo(labor);
            assertEquals("PRODUCTION_STARTED", ManufacturingFundingChainTest.reason(world, "dep-labor-task:finish"));
            assertEquals(new BigDecimal("0.00"), world.bankOf().orElseThrow()
                    .decode(world.snapshot().accounts()).state().depositOf("north", "cufe"));
            assertEquals(new BigDecimal("200.00"), world.snapshot().accounts().get("CO").cash());
            assertEquals(new BigDecimal("600.00"), world.snapshot().accounts().get("SUP").cash());
            assertEquals(new BigDecimal("300.00"), world.snapshot().accounts().get("OPS").cash());
            var report = ComposedCompanyReport.of(world, "cufe");
            assertEquals(report.totalAssets(), report.totalLiabilities().add(report.totalEquity()));
        }
    }

    @Test
    void retryAfterExecutionAndReopenReturnsOriginalReceipt() throws Exception {
        Path file = dir.resolve("retry.journal");
        String receipt;
        try (WorldRuntime world = ManufacturingFundingWorld.open(file)) {
            receipt = sign(world, "first", 2_000, 1_000);
            world.advanceTo(2_000);
            assertEquals("CONTRACT_SIGNED", ManufacturingFundingChainTest.reason(world, "first-task:finish"));
        }
        try (WorldRuntime world = ManufacturingFundingWorld.open(file)) {
            long position = world.snapshot().position();
            assertEquals(receipt, sign(world, "first", 2_000, 1_000));
            assertEquals(position, world.snapshot().position());
            assertThrows(IllegalArgumentException.class, () -> sign(world, "first", 2_001, 1_000));
        }
    }

    @Test
    void renewedAppointmentCannotFinishAnOldAppointmentCommand() throws Exception {
        try (WorldRuntime world = ManufacturingFundingWorld.open(dir.resolve("renew.journal"))) {
            sign(world, "old", 2_000, 1_000);
            world.scheduleRevokeAppointment("revoke", "revoke-task", 1_500,
                    ManufacturingFundingWorld.MANAGER_APPOINTMENT);
            world.advanceTo(1_500);
            appointWhileCandidateIsBusy(world, 1_501);
            world.advanceTo(2_000);
            assertEquals("NOT_AUTHORIZED", ManufacturingFundingChainTest.reason(world, "old-task:finish"));
            assertFalse(world.snapshot().manufacturing().orElseThrow().ledger().contracts()
                    .containsKey("old-contract"));
        }
    }

    private static void appointWhileCandidateIsBusy(WorldRuntime world, long start) throws Exception {
        String company = WorldWebScenario.COMPANY;
        world.schedulePublishDocument("renew-doc", "renew-doc-task", start + 1, "renew-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "续任材料", "董事会讨论经理续任", "实验材料", 10, List.of());
        world.scheduleCompanyBoardConvene("renew-convene", "renew-convene-task", start + 2,
                company, "alice", "renew-meeting", start + 12, start + 14, start + 40, true);
        world.advanceTo(start + 3);
        for (String person : List.of("alice", "bob", "carol", "dave")) {
            world.scheduleCompanyBoardAttend("renew-attend-" + person, "renew-attend-task-" + person,
                    start + 5, company, person, "renew-meeting", false);
        }
        world.scheduleCompanyBoardMaterial("renew-material", "renew-material-task", start + 8,
                company, "alice", "renew-meeting", "m", "renew-material", 1);
        for (String person : List.of("bob", "carol", "dave")) {
            world.scheduleStartReading("renew-read-" + person, "renew-read-task-" + person,
                    start + 7, person, "renew-material", 1);
        }
        world.scheduleCompanyBoardAppointmentProposal("renew-propose", "renew-propose-task", start + 10,
                company, "alice", "renew-meeting", new BoardModel.AppointmentRequest("MFG-MGR-2",
                        ManufacturingFundingWorld.MANAGER, company, RoleAccess.Role.COMPANY_MANAGER,
                        BoardModel.AppointmentAction.APPOINT, start + 43, 400L * 86_400_000));
        world.advanceTo(start + 17);
        for (String person : List.of("bob", "carol", "dave")) {
            world.scheduleCompanyBoardVote("renew-vote-" + person, "renew-vote-task-" + person,
                    start + 19, company, person, "renew-meeting", BoardModel.Vote.YES, 1);
        }
        world.scheduleCompanyBoardImplement("renew-implement", "renew-implement-task", start + 42,
                company, "alice", "renew-meeting");
        world.advanceTo(start + 43);
        assertTrue(world.isActiveManager(company, ManufacturingFundingWorld.MANAGER, start + 43));
    }

    @Test
    void pendingBeginOccupiesPersonFocus() throws Exception {
        try (WorldRuntime world = ManufacturingFundingWorld.open(dir.resolve("pending.journal"))) {
            sign(world, "first", 2_000, 1_000);
            assertThrows(IllegalArgumentException.class, () -> sign(world, "overlap", 2_100, 1_000));
        }
    }

    @Test
    void pendingFinishStillOccupiesPersonFocus() throws Exception {
        try (WorldRuntime world = ManufacturingFundingWorld.open(dir.resolve("started.journal"))) {
            sign(world, "first", 2_000, 1_000);
            world.advanceTo(1_500);
            assertThrows(IllegalArgumentException.class, () -> sign(world, "overlap", 2_100, 599));
            world.advanceTo(2_000);
            sign(world, "later", 3_001, 1_000);
            world.advanceTo(3_001);
            assertEquals("CONTRACT_SIGNED", ManufacturingFundingChainTest.reason(world, "later-task:finish"));
        }
    }
}
