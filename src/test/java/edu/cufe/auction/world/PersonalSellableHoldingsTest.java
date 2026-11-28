package edu.cufe.auction.world;

import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PersonalSellableHoldingsTest {
    @TempDir Path directory;

    private static void observe(WorldRuntime w, long quantity, long frozen, long available) throws Exception {
        var actual = w.roleView("nina").holdings().stream().filter(h -> h.symbol().equals("AGED")).findFirst().orElseThrow();
        assertEquals(quantity, actual.quantity());
        assertEquals(frozen, actual.frozen());
        assertEquals(available, actual.available());
        var input = w.autonomyRoleObservation("nina").path("profile").path("holdings");
        var aged = java.util.stream.StreamSupport.stream(input.spliterator(), false)
                .filter(h -> "AGED".equals(h.path("symbol").asText())).findFirst().orElseThrow();
        assertEquals(available, aged.required("available").asLong());
    }

    private static String order(WorldRuntime w, String id, String person, String account, String side, long shares) throws Exception {
        var args = new ObjectMapper().createObjectNode().put("accountId", account).put("symbol", "AGED")
                .put("side", side).put("quantity", shares).put("limitPrice", "10.00");
        var ref = PersonalMarketTest.decide(w, person, id, PersonalMarket.SUBMIT, args);
        w.advanceTo(ref.dueMillis());
        var report = w.snapshot().exchange().orElseThrow().reports().get(ref.taskId());
        return report.accepted() ? "ACCEPTED" : report.reason();
    }

    @Test void sameDayRepurchaseIsVisibleButOnlySettledLotsMayBeSold() throws Exception {
        var file = directory.resolve("repurchase.journal");
        try (var w = ComposedIndustryScenario.openBankCrisisOperating(file)) {
            w.advanceTo(MarketSessions.OPEN);
            observe(w, 25, 0, 25);
            assertEquals("ACCEPTED", order(w, "sell", "nina", "HOUSE-NINA", "SELL", 15));
            assertEquals("ACCEPTED", order(w, "take", "investor", "INV", "BUY", 15));
            assertEquals("ACCEPTED", order(w, "offer", "investor", "INV", "SELL", 15));
            assertEquals("ACCEPTED", order(w, "rebuy", "nina", "HOUSE-NINA", "BUY", 15));
            observe(w, 25, 0, 10);
            assertEquals("INSUFFICIENT_SHARES", order(w, "excess", "nina", "HOUSE-NINA", "SELL", 11));
            assertEquals("ACCEPTED", order(w, "resting", "nina", "HOUSE-NINA", "SELL", 7));
            observe(w, 25, 7, 3);
        }
        try (var reopened = ComposedIndustryScenario.openBankCrisisOperating(file)) {
            observe(reopened, 25, 7, 3);
            reopened.advanceTo(MarketSessions.CLOSE);
            observe(reopened, 25, 0, 10);
            reopened.advanceTo(SecuritiesLedger.DAY_MILLIS + MarketSessions.OPEN);
            observe(reopened, 25, 0, 25);
        }
    }
}
