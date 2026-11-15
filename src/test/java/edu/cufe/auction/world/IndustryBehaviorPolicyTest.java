package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndustryBehaviorPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;
    private static final long HOUR = 3_600_000L;

    @Test void unpaidPowerBacklogStopsExistingDispatchAndRetriesOnlyAfterAFullDay() throws Exception {
        ObjectNode view = hydro();
        long failedAt = DAY - HOUR;
        ((ObjectNode) view.path("plant")).put("deliveredKilowattHours", "60000");
        view.putArray("reports").addObject().put("action", "COLLECT").put("accepted", false)
                .put("timeMillis", failedAt).put("reason", "BUYER_CASH_SHORT");

        // Six hours after failure, across midnight: the one-minute physics bucket must not erase it.
        var stop = IndustryBehaviorPolicy.choose(view, "hydro", failedAt + 6 * HOUR, "stop");
        assertEquals("DISPATCH_FLOW", stop.action());
        assertEquals(Map.of("cubicMetresPerHour", "0"), stop.fields());
        assertDoesNotThrow(() -> new HydroOperations.Request(HydroOperations.Action.valueOf(stop.action()), stop.fields()));

        ((ObjectNode) view.path("plant").path("dispatch")).put("cubicMetresPerHour", "0");
        assertEquals("WAIT", IndustryBehaviorPolicy.choose(view, "hydro", failedAt + 6 * HOUR, "stopped").action());
        assertEquals("WAIT", IndustryBehaviorPolicy.choose(view, "hydro", failedAt + DAY - 1, "too-early").action());
        var retry = IndustryBehaviorPolicy.choose(view, "hydro", failedAt + DAY, "retry");
        assertEquals("COLLECT", retry.action());
        assertEquals("30000.00", retry.fields().get("amount"));
    }

    @Test void zeroDemandClosesExistingFlowInsteadOfSubstitutingInstalledCapacity() throws Exception {
        ObjectNode view = hydro();
        ((ObjectNode) view.path("contract")).put("demandKilowattHoursPerHour", "0");
        var stop = IndustryBehaviorPolicy.choose(view, "hydro", HOUR, "zero-demand");
        assertEquals("DISPATCH_FLOW", stop.action());
        assertEquals(Map.of("cubicMetresPerHour", "0"), stop.fields());
        ((ObjectNode) view.path("plant").path("dispatch")).put("cubicMetresPerHour", "0");
        assertEquals("WAIT", IndustryBehaviorPolicy.choose(view, "hydro", HOUR, "closed").action());
        ((ObjectNode) view.path("contract")).put("demandKilowattHoursPerHour", "100");
        var resumed=IndustryBehaviorPolicy.choose(view, "hydro", HOUR, "new-demand");
        assertEquals("DISPATCH_FLOW",resumed.action());
        assertTrue(Long.parseLong(resumed.fields().get("cubicMetresPerHour"))>0);
        ((ObjectNode)view.path("physical")).put("maxTurbineCubicMetresPerHour",1);
        assertEquals("1",IndustryBehaviorPolicy.choose(view,"hydro",HOUR,"limited-flow").fields().get("cubicMetresPerHour"));
    }

    @Test void exhaustedSeamIsNotReplenishedFromItsOpeningReserve() throws Exception {
        ObjectNode view = (ObjectNode) JSON.readTree("""
                {"companyId":"resources","cash":"1000.00","coalKgPerMwh":"100",
                 "mineCapacityKg":"200","generatorCapacityMwh":"5","transportCapacityKg":"1000",
                 "mineCoal":{"quantity":"0"},"plantFuel":{"quantity":"0"},"periodUse":{},
                 "seams":[{"seamId":"seam-a","reserveKg":"1000","extractionCostPerKg":"1.00"}],
                 "remainingReservesKg":{"seam-a":"0"},"jobs":{},"receivables":{},"reports":[]}
                """);
        var empty = IndustryBehaviorPolicy.choose(view, "resources", HOUR, "exhausted");
        assertEquals("WAIT", empty.action());
        assertTrue(empty.reason().contains("没有剩余储量"));
        ((ObjectNode) view.path("remainingReservesKg")).put("seam-a", "10");
        var available = IndustryBehaviorPolicy.choose(view, "resources", HOUR, "remaining-ten");
        assertEquals("BEGIN_EXTRACTION", available.action());
        assertEquals("10", available.fields().get("quantityKg"));
        assertDoesNotThrow(() -> new ResourceOperations.Request(ResourceOperations.Action.valueOf(available.action()), available.fields()));
    }

    private static ObjectNode hydro() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"companyId":"hydro","cash":"5000.00",
                 "physical":{"capacityKw":"1000","kilowattHoursPerCubicMetre":"1.00",
                    "deadStorageCubicMetres":"100","maxTurbineCubicMetresPerHour":"1000"},
                 "contract":{"demandKilowattHoursPerHour":"1000","pricePerKilowattHour":"0.5000",
                    "untilMillis":"8640000000"},
                 "plant":{"deliveredKilowattHours":"0","collectedCash":"0.00","storageCubicMetres":"10000",
                    "dispatch":{"cubicMetresPerHour":"600"}},"reports":[]}
                """);
    }
}
