package edu.cufe.auction.world;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResearchLicenseInputOrderTest {
    private static final List<String> RING = List.of("licensee", "terms", "fee", "contracts", "durationMillis",
            "approvedProjects", "companyAccount", "totals", "payerAccount", "actions");
    private static final Map<String,String> VALUES = Map.of("fee", "\"300.00\"", "contracts", "{\"p1\":{\"a\":1,\"b\":2}}",
            "durationMillis", "1000", "terms", "\"原文,含\\\"引号\"", "approvedProjects", "[\"p1\"]");

    private static String text(List<String> keys) {
        var fields = keys.stream().map(k -> "\"" + k + "\":" + VALUES.getOrDefault(k, "\"项\"")).toList();
        return "{\"actor\":\"kate\",\"engineering\":{\"researchOperations\":{\"licensing\":{" + String.join(",", fields)
                + "}}},\"other\":{\"a\":1,\"b\":2}}";
    }

    @Test void acceptsOnlyOriginalClockwiseAndCounterclockwiseOrders() throws Exception {
        String generated = text(RING.stream().sorted().toList());
        var order = new ArrayList<>(RING);
        for (int direction = 0; direction < 2; direction++) {
            Collections.reverse(order);
            for (int start = 0; start < order.size(); start++) {
                Collections.rotate(order, 1);
                String historical = text(order);
                assertEquals(historical, ResearchLicenseInputOrder.reconstruct(generated, historical));
            }
        }
        Collections.swap(order, 2, 3);
        assertEquals(generated, ResearchLicenseInputOrder.reconstruct(generated, text(order)));
    }

    @Test void historicalBytesCannotSupplyOrRewriteValuesOrAnythingOutsideTheTenKeys() throws Exception {
        String generated = text(RING.stream().sorted().toList()), original = text(RING);
        var altered = List.of(original.replace("300.00", "300.0"), original.replace("1000", "1001"),
                original.replace("\"p1\":{\"a\":1,\"b\":2", "\"p1\":{\"b\":2,\"a\":1"),
                original.replace("\"other\":{\"a\":1,\"b\":2", "\"other\":{\"b\":2,\"a\":1"),
                original.replace("\"p1\"", "\"p2\""), original.replace("\"other\":{", "\"other\": {"),
                original.replace("\"fee\":", "\"unknown\":"),
                original.replace("\"fee\":", "\"more\":true,\"fee\":"),
                original.replace("\"fee\":\"300.00\",", ""));
        for (String forged : altered) {
            assertNotEquals(original, forged);
            assertNotEquals(forged, ResearchLicenseInputOrder.reconstruct(generated, forged));
        }
        assertThrows(IOException.class, () -> ResearchLicenseInputOrder.reconstruct(generated,
                original.replace("\"fee\":", "\"fee\":false,\"fee\":")));
    }
}
