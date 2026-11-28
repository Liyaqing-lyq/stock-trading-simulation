package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ManufacturingBehaviorAuditTest {
    private static CompanyOperationsRun.ReportEntry report(String action, boolean accepted, String amount) {
        return new CompanyOperationsRun.ReportEntry("t", "c", "mfg-tech", action, accepted,
                accepted ? "OK" : "REJECTED", "ar", new BigDecimal(amount), 1, 1);
    }

    @Test void sumsOnlyAcceptedCollectionsInCentsAndKeepsMissingDistinctFromZero() {
        var rejected = report("COLLECT", false, "0.00");
        var purchase = report("ORDER_MATERIAL", true, "5.40");
        assertEquals(Optional.of("123456789012345.69"), ManufacturingBehaviorLoop.collectedAmount(List.of(
                report("COLLECT", true, "123456789012345.68"), report("COLLECT", true, "0.01"), rejected, purchase)));
        assertEquals(Optional.empty(), ManufacturingBehaviorLoop.collectedAmount(List.of(rejected, purchase)));
        assertEquals(Optional.of("0.00"), ManufacturingBehaviorLoop.collectedAmount(List.of(report("COLLECT", true, "0.00"))));
    }
}
