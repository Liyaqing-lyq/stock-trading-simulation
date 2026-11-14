package edu.cufe.auction.world;

import edu.cufe.auction.world.BoardModel.Charter;
import edu.cufe.auction.world.BoardModel.Seat;
import edu.cufe.auction.world.BoardModel.Topic;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.RoleAccess.Role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 旧 schema（≤17）门禁：新角色与新议题不得从通用入口或恢复路径进入。 */
class WorldRuntimeManagerGateTest {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-manager-gate";
    private static final String SYM = "CUFE1";
    private static final long DAY = ManufacturingModel.DAY_MILLIS;

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), SYM);

    private static final List<OpeningAccount> OPENINGS = List.of(
            new OpeningAccount("CO", "company", bd("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount("SUP", "supplier", bd("100.00"), "实验开局历史禀赋"),
            new OpeningAccount("CUS", "customer", bd("2000.00"), "实验开局历史禀赋"),
            new OpeningAccount("OPS", "ops", bd("0.00"), "实验开局历史禀赋"),
            new OpeningAccount("INV", "investor", bd("500.00"), "实验开局历史禀赋"));

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("任免议题被排程入口拒绝；预算议题不受影响")
    void managerTopicRejectedAtScheduling() throws IOException {
        try (WorldRuntime runtime = new WorldRuntime(tempDir.resolve("gate.journal"),
                manifest(), OPENINGS, registry(), MFG, charter())) {
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleBoardMeeting(
                    "c-manager", "t-manager", 10 * DAY, "s1", "m-manager", 20 * DAY,
                    21 * DAY, 22 * DAY, allSeats(), List.of(), Topic.MANAGER_APPOINTMENT));
            String scheduled = runtime.scheduleBoardMeeting("c-budget", "t-budget", 10 * DAY,
                    "s1", "m-budget", 20 * DAY, 21 * DAY, 22 * DAY, allSeats(), List.of(),
                    Topic.BUDGET);
            assertTrue(scheduled.startsWith("SCHEDULED:"), scheduled);
        }
    }

    @Test
    @DisplayName("旧档通用角色门禁拒绝 COMPANY_MANAGER，旧四岗仍允许")
    void legacyRoleGate() throws Exception {
        try (WorldRuntime runtime = new WorldRuntime(tempDir.resolve("roles.journal"),
                manifest(), OPENINGS, registry(), MFG, charter())) {
            Method gate = WorldRuntime.class.getDeclaredMethod("requireLegacyRole", Role.class);
            gate.setAccessible(true);
            InvocationTargetException manager = assertThrows(InvocationTargetException.class,
                    () -> gate.invoke(runtime, Role.COMPANY_MANAGER));
            assertTrue(manager.getCause() instanceof IllegalArgumentException);
            gate.invoke(runtime, Role.RESEARCHER);
            gate.invoke(runtime, Role.PORTFOLIO_MANAGER);
        }
    }

    private static List<String> allSeats() {
        return List.of("s1", "s2", "s3", "s4", "s5");
    }

    private static Charter charter() {
        return new Charter("cufe", List.of(
                new Seat("s1", "p1"), new Seat("s2", "p2"), new Seat("s3", "p3"),
                new Seat("s4", "p4"), new Seat("s5", "p5")), "s1", BoardModel.QUORUM);
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        modules.put("manufacturing", 1);
        modules.put("governance", 1);
        return new RunManifest(RUN, SEED, RunManifest.RULES_GOVERNED_STOCK_COMPANY_EXPERIMENT_V1,
                modules, Map.of(), RunManifest.MODEL_NONE);
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = new ArrayList<>(List.of(
                new OpeningRegistry.Party("cufe", PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party("investor-org", PartyKind.ORGANIZATION, "投资者组织"),
                new OpeningRegistry.Party("p1", PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party("p2", PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party("p3", PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party("p4", PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party("p5", PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party("investor", PartyKind.PERSON, "投资者")));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner("company", OwnerKind.ORGANIZATION, "cufe"),
                new OpeningRegistry.PropertyOwner("supplier", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("customer", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("ops", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("investor", OwnerKind.PERSON, "investor"));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation("INV", SYM, 200,
                        new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners,
                List.of(new OpeningRegistry.Security(SYM, "cufe", 200, bd("10.00"))), allocations);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
