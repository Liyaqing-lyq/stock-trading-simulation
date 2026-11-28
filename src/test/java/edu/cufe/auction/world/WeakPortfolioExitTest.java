package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 指定本人决定的压力案例：出资来自既存账户，无买方时保留原仓，有对手时才确认退出。 */
class WeakPortfolioExitTest {
    @TempDir Path dir;
    static final String WEAK = "independent-reader", ACCOUNT = "COPY-PERSON-independent-reader", SYMBOL = "HY1";
    static final ObjectMapper JSON = new ObjectMapper();
    @Test void finiteInvestorLeavesTheMarketAtALossOnlyWhenABidArrives() throws Exception {
        Path file=dir.resolve("exit.journal");
        Path draft=Path.of("src/main/resources/world/opening-copies-example.json");
        long funded, finalCut;
        try(var w=ComposedIndustryScenario.openIssuerResolutionOperating(file,draft)) {
            BigDecimal initialCash=w.snapshot().accounts().get("INV").cash();
            assertEquals(0,w.snapshot().accounts().get(ACCOUNT).cash().signum());
            assertEquals(0,shares(w,ACCOUNT));
            long now=w.snapshot().timeMillis()+1;
            w.scheduleCapitalFlow("seed","seed-transfer",now,"investor","INV",ACCOUNT,new BigDecimal("10.00"),1,"controlled-capital-from-investor");
            w.advanceTo(now+1);
            assertEquals(initialCash.subtract(new BigDecimal("10.00")),w.snapshot().accounts().get("INV").cash());
            assertEquals("10.00",w.valuation(WEAK).netWorth().toPlainString());
            funded=w.snapshot().position();
            w.advanceTo(MarketSessions.OPEN);
            var seller=w.snapshot().securities().orElseThrow().lots().stream()
                    .filter(l->l.symbol().equals(SYMBOL) && List.of("INV","HOUSE-NINA","HOUSE-OMAR").contains(l.accountId()))
                    .findFirst().orElseThrow();
            String sellerAccount=seller.accountId(), sellerOwner=w.snapshot().accounts().get(sellerAccount).ownerId();
            order(w,sellerOwner,sellerAccount,"SELL","10.00","provide-one");
            order(w,WEAK,ACCOUNT,"BUY","10.00","risk-all-ten");
            assertEquals(0,w.snapshot().accounts().get(ACCOUNT).cash().signum());
            assertEquals(1,shares(w,ACCOUNT));
            w.advanceTo(MarketSessions.DAY+MarketSessions.OPEN);
            var waiting=order(w,WEAK,ACCOUNT,"SELL","9.00","offer-entire-position");
            assertTrue(w.snapshot().exchange().orElseThrow().order(waiting.orderId()).live());
            assertEquals(1,w.snapshot().exchange().orElseThrow().trades().size());
            assertEquals(1,shares(w,ACCOUNT));
            assertEquals(0,w.snapshot().accounts().get(ACCOUNT).cash().signum());
            String buyer=sellerOwner.equals("nina") ? "omar" : "nina";
            order(w,buyer,buyer.equals("nina")?"HOUSE-NINA":"HOUSE-OMAR","BUY","9.50","accept-existing-offer");
            assertEquals(0,shares(w,ACCOUNT));
            assertEquals("9.00",w.snapshot().accounts().get(ACCOUNT).cash().toPlainString());
            assertEquals("9.00",w.valuation(WEAK).netWorth().toPlainString());
            var trades=w.snapshot().exchange().orElseThrow().trades();
            assertEquals(2,trades.size());
            assertEquals("9.00",trades.get(1).price().toPlainString());
            assertTrue(w.snapshot().exchange().orElseThrow().liveOrders().isEmpty());
            finalCut=w.snapshot().position();
        }
        try (var w=ComposedIndustryScenario.openIssuerResolutionOperating(file,null)) {
            assertEquals(finalCut,w.snapshot().position());
            assertEquals(0,shares(w,ACCOUNT));
            assertEquals(0,new BigDecimal("-0.10").compareTo(w.performanceHistory(WEAK,funded,finalCut).performance().returnRate()));
            assertTrue(w.performanceHistory(WEAK,funded,finalCut).series().stream().allMatch(p->p.point().flows().isEmpty()));
        }
    }
    private static long shares(WorldRuntime w,String account) {
        return w.snapshot().securities().orElseThrow().lots().stream().filter(l->l.accountId().equals(account))
                .mapToLong(SecuritiesLedger.Lot::remaining).sum();
    }
    private static WorldExchange.OrderReport order(WorldRuntime w,String person,String account,String side,String price,String id) throws Exception {
        var args=JSON.createObjectNode().put("accountId",account).put("symbol",SYMBOL).put("side",side)
                .put("quantity",1).put("limitPrice",price);
        var placed=PersonalMarketTest.decide(w,person,id,PersonalMarket.SUBMIT,args);
        w.advanceTo(placed.dueMillis());
        var report=w.snapshot().exchange().orElseThrow().reports().get(placed.taskId());
        assertTrue(report.accepted(),report.reason());
        return report;
    }
}
