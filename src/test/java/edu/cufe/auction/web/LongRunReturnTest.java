package edu.cufe.auction.web;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LongRunReturnTest {
    @TempDir Path dir;

    static void prepare(Path file) throws Exception {
        try (var w = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            long open = 570 * 60_000L;
            w.advanceTo(open);
            w.scheduleCapitalFlow("owner-in", "owner-in-task", open+1, "investor", "INV", "HOUSE-NINA",
                    new BigDecimal("500.00"), 1, "登记追加");
            w.advanceTo(open+1);
            w.scheduleOrder("ask", "ask-task", open+2, "nina", "HOUSE-NINA", "AGED", Side.SELL,
                    OrderType.LIMIT, new BigDecimal("10.50"), 1);
            w.scheduleOrder("buy", "buy-task", open+3, "investor", "INV", "AGED", Side.BUY,
                    OrderType.LIMIT, new BigDecimal("10.50"), 1);
            w.advanceTo(open+3);
            assertEquals(1,w.snapshot().exchange().orElseThrow().trades().size());
            while (w.snapshot().position() < 402) w.advanceTo(w.snapshot().timeMillis()+1);
        }
    }

    @Test void allFramesContributeAfterFourHundredAndColdOpenRebuildsTheSameRanks() throws Exception {
        var file = dir.resolve("ten.journal");
        prepare(file);
        try (var host = new WorldWebHost(() -> {
            var w = ComposedIndustryScenario.openTenCompanyAutonomy(file);
            return new WorldWebHost.Opened(w,w.profile().manifest().runId());
        })) {
            var first = host.performanceView();
            assertTrue(first.performanceAvailable());
            var owners = new java.util.ArrayList<>(first.owners().rows());
            for (int page=2; page<=first.ownerPages(); page++) owners.addAll(host.performanceView(page).owners().rows());
            var nina = owners.stream().filter(row -> "nina".equals(row.get("ownerId"))).findFirst().orElseThrow();
            assertNotNull(nina.get("periodReturnRank"));
            assertEquals("401", nina.get("segmentCount"));
            var privateReturns = host.personPage("nina").portfolioReturns();
            assertEquals(java.util.Set.of("nina"), privateReturns.keySet());
            assertEquals(nina.get("returnRate"),privateReturns.get("nina").get("returnRate"));
            assertFalse(privateReturns.get("nina").containsKey("periodReturnRank"));
        }
        try (var w = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            for (String owner : List.of("nina","investor","fund-a")) {
                var total = w.openingReturns().get(owner).value();
                var each = w.performance(owner,1,w.snapshot().position());
                assertEquals(each.status(),total.status());
                assertEquals(each.returnRate(),total.returnRate());
                assertEquals(each.maxDrawdown(),total.maxDrawdown());
                assertEquals(each.index(),total.index());
                assertEquals(each.segments().size(),total.segmentCount());
            }
            assertTrue(w.openingReturns().get("nina").value().returnRate().signum()>0);
            var before = w.openingReturns().get("nina").value();
            w.advanceTo(w.snapshot().timeMillis()+1);
            var after = w.openingReturns().get("nina").value();
            assertEquals(before.returnRate(),after.returnRate());
            assertEquals(before.segmentCount()+1, after.segmentCount());
        }
    }
}
