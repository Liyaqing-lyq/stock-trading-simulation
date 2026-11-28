package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.HydroOperations;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static edu.cufe.auction.world.composed.HydroIndustryConfig.*;
import static org.junit.jupiter.api.Assertions.*;

class HydroSettlementViewTest {
    @TempDir Path dir;

    private static String observed(WorldRuntime world, boolean enabled) throws Exception {
        var root = (ObjectNode) world.autonomyRoleObservation(MANAGER);
        var operations = (ObjectNode) root.at("/engineering/hydroOperations");
        var settlement = operations.remove("settlement");
        if (!enabled) {
            assertNull(settlement);
            return root.toString();
        }
        assertNotNull(settlement, "在任经理缺少水量计量区间与已结电费");
        var ledger = world.operationsRun().orElseThrow().hydro().ledger();
        var report = HydroIndustryWorldTest.report(world);
        assertEquals("0", settlement.path("fromMillis").textValue());
        assertEquals(Long.toString(ledger.watermark()), settlement.path("throughMillis").textValue());
        assertEquals(Long.toString(report.inflowCubicMetres()), settlement.path("inflowCubicMetres").textValue());
        assertEquals(report.deliveredKilowattHours().toPlainString(), settlement.path("deliveredKilowattHours").textValue());
        assertEquals(report.revenue().toPlainString(), settlement.path("revenue").textValue());
        assertEquals(report.collectedCash().toPlainString(), settlement.path("collectedCash").textValue());
        assertEquals(report.receivable().toPlainString(), settlement.path("receivable").textValue());
        var fields = new TreeSet<String>();
        settlement.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("fromMillis", "throughMillis", "inflowCubicMetres", "deliveredKilowattHours",
                "revenue", "collectedCash", "receivable", "basis"), fields);
        assertTrue(settlement.path("basis").asText().contains("未另设到期日"));
        assertFalse(operations.toString().contains("segments"));
        return root.toString();
    }

    private List<String> run(boolean enabled) throws Exception {
        Path file = dir.resolve(enabled ? "new.journal" : "old.journal");
        List<String> snapshots = new ArrayList<>();
        try (var w = enabled ? ComposedIndustryScenario.openHydroSettlementOperating(file)
                : ComposedIndustryScenario.openRoleTableOperating(file)) {
            assertTrue(w.autonomyRoleObservation(MANAGER).at("/engineering/hydroOperations").isMissingNode());
            OperationsFixture.grantBudgetAndAppoint(w, COMPANY, DIRECTORS, MANAGER, "hydro", List.of(0L));
            w.advanceTo(83_000);
            assertEquals(60_000, w.operationsRun().orElseThrow().hydro().ledger().watermark());
            assertEquals(10, HydroIndustryWorldTest.report(w).inflowCubicMetres());
            snapshots.add(observed(w, enabled));
            for (String outsider : List.of(DIRECTORS.get(0), "investor", "aged-manager"))
                assertTrue(w.autonomyRoleObservation(outsider).at("/engineering/hydroOperations").isMissingNode());
            HydroIndustryWorldTest.act(w, "flow", new HydroOperations.Request(HydroOperations.Action.DISPATCH_FLOW,
                    Map.of("cubicMetresPerHour", "100")));
            w.advanceTo(w.snapshot().timeMillis() + HydroIndustryWorldTest.HOUR);
            assertTrue(HydroIndustryWorldTest.report(w).receivable().signum() > 0);
            snapshots.add(observed(w, enabled));
            HydroIndustryWorldTest.act(w, "collect", new HydroOperations.Request(HydroOperations.Action.COLLECT,
                    Map.of("amount", "1.00")));
            assertEquals("1.00", HydroIndustryWorldTest.report(w).collectedCash().toPlainString());
            snapshots.add(observed(w, enabled));
        }
        try (var w = enabled ? ComposedIndustryScenario.openHydroSettlementOperating(file)
                : ComposedIndustryScenario.openRoleTableOperating(file)) {
            assertEquals(snapshots.get(snapshots.size() - 1), observed(w, enabled));
        }
        return snapshots;
    }

    @Test void twoVersionsFollowIdenticalRightsWaterAndCashThroughDeliveryCollectionAndReopen() throws Exception {
        assertEquals(run(false), run(true));
    }
}
