package edu.cufe.auction.world;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BestQuoteDepthTest {
    @TempDir Path dir;
    private static WorldExchange.Order buy(long id,long amount,String price) {
        return new WorldExchange.Order(id,"t"+id,"c"+id,"INV","S",Side.BUY,OrderType.LIMIT,new BigDecimal(price),
                amount,0,id,null,WorldExchange.OrderStatus.ACTIVE,0);
    }
    @Test void bestLevelSumsRemaindersWithoutLongOverflowOrCountingWorsePrices() {
        var level=BestQuoteDepth.of(List.of(buy(1,5,"9.00"),buy(2,Long.MAX_VALUE,"10.00"),buy(3,Long.MAX_VALUE,"10.00")),"S");
        assertEquals("18446744073709551614",level.bidQuantity().toString());
        assertEquals("0",level.askQuantity().toString());assertNull(level.ask());
    }

    @Test void fundStaffObserveTheSameUntouchedBestDepthAfterPartialFillAndReopen() throws Exception {
        var path=dir.resolve("depth.journal");long open=MarketSessions.OPEN;
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(path)){
            w.scheduleOrder("s1","s1",open+1,"nina","HOUSE-NINA","AGED",Side.SELL,OrderType.LIMIT,new BigDecimal("10.50"),6);
            w.scheduleOrder("s2","s2",open+2,"omar","HOUSE-OMAR","AGED",Side.SELL,OrderType.LIMIT,new BigDecimal("10.50"),4);
            w.scheduleOrder("worse","worse",open+3,"nina","HOUSE-NINA","AGED",Side.SELL,OrderType.LIMIT,new BigDecimal("10.80"),3);
            w.scheduleOrder("cross","cross",open+4,"investor","INV","AGED",Side.BUY,OrderType.LIMIT,new BigDecimal("10.50"),1);
            w.advanceTo(open+4);
            assertEquals(1,w.snapshot().exchange().orElseThrow().trades().size());
            for(String person:List.of("alice","bob","carol","dave")){
                var quote=w.roleView(person).market().stream().filter(q->q.symbol().equals("AGED")).findFirst().orElseThrow();
                assertEquals("9",quote.bestAskQuantity());assertEquals("0",quote.bestBidQuantity());assertNull(quote.bestBid());
                var market=w.autonomyRoleObservation(person).at("/profile/market");
                if(market.isArray()){
                    var row=java.util.stream.StreamSupport.stream(market.spliterator(),false).filter(x->"AGED".equals(x.path("symbol").asText())).findFirst().orElseThrow();
                    assertEquals("9",row.path("bestAskQuantity").asText());
                }else{
                    var columns=new java.util.ArrayList<String>();market.path("columns").forEach(c->columns.add(c.asText()));
                    var row=java.util.stream.StreamSupport.stream(market.path("rows").spliterator(),false).filter(x->"AGED".equals(x.get(columns.indexOf("symbol")).asText())).findFirst().orElseThrow();
                    assertEquals("9",row.get(columns.indexOf("bestAskQuantity")).asText());
                }
            }
        }
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(path)) {
            assertEquals("9",w.roleView("dave").market().stream().filter(q->q.symbol().equals("AGED")).findFirst().orElseThrow().bestAskQuantity());
        }
        try(var old=ComposedIndustryScenario.openFundOfficeRoutingOperating(dir.resolve("old"))){
            assertNull(old.roleView("dave").market().get(0).bestAskQuantity());
            assertFalse(old.autonomyRoleObservation("dave").at("/profile/market").toString().contains("bestAskQuantity"));
        }
    }
}
