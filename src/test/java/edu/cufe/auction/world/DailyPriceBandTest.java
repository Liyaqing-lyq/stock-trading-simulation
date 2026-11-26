package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.MarketSessions.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyPriceBandTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    private static final String SYMBOL = "HY1";

    private static RoleView.Market quote(WorldRuntime w) {
        return w.roleView("nina").market().stream().filter(q -> SYMBOL.equals(q.symbol())).findFirst().orElseThrow();
    }
    private static WorldExchange.State book(WorldRuntime w) { return w.snapshot().exchange().orElseThrow(); }

    private static void assertRange(WorldRuntime w, String basis, String lower, String upper, String source) {
        var b = quote(w).priceBand();
        assertEquals(new BigDecimal(basis), b.basis());
        assertEquals(new BigDecimal(lower), b.lower());
        assertEquals(new BigDecimal(upper), b.upper());
        assertEquals(source, b.source());
    }
    private static WorldExchange.OrderReport submit(WorldRuntime w,String account,Side side,String price,int qty) throws Exception {
        String id = "order-" + w.snapshot().position();
        long due = w.snapshot().timeMillis()+1;
        var actor = w.snapshot().accounts().get(account).ownerId();
        w.scheduleOrder(id,id,due,actor,account,SYMBOL,side,price == null ? OrderType.MARKET : OrderType.LIMIT,
                price == null ? null : new BigDecimal(price),qty);
        w.advanceTo(due);
        return book(w).reports().get(id);
    }
    private static WorldExchange.OrderReport bid(WorldRuntime w,String person,String account,String price,int quantity) throws Exception {
        String id = "decide-"+w.snapshot().position();
        var args=JSON.createObjectNode().put("accountId",account).put("symbol",SYMBOL).put("side","BUY")
                .put("quantity",quantity).put("limitPrice",price);
        var request=PersonalMarketTest.decide(w,person,id,PersonalMarket.SUBMIT,args);
        w.advanceTo(request.dueMillis());
        return book(w).reports().get(request.taskId());
    }

    @Test void boundariesQueueByTimeAndRepriceOnlyOnTheFollowingTradingDay() throws Exception {
        var journal=dir.resolve("price-band.journal");
        try (var w=ComposedIndustryScenario.openPriceBandOperating(journal)) {
            assertRange(w,"10.00","9.00","11.00","OPENING_REFERENCE");
            w.advanceTo(OPEN);
            var initial = w.snapshot().accounts();
            assertEquals(DailyPriceBand.OUTSIDE,bid(w,"nina","HOUSE-NINA","11.01",2).reason());
            assertEquals(DailyPriceBand.OUTSIDE,submit(w,"HY-FOUNDER",Side.SELL,"8.99",1).reason());
            assertTrue(book(w).orders().isEmpty());
            assertEquals(initial,w.snapshot().accounts());
            assertEquals(0,WorldExchange.frozenCash(book(w),"HOUSE-NINA").signum());
            var first=bid(w,"nina","HOUSE-NINA","11.00",2);
            var second=bid(w,"omar","HOUSE-OMAR","11.00",2);
            assertTrue(first.accepted()); assertTrue(second.accepted());
            assertEquals(2,book(w).liveOrders().size());
            assertTrue(book(w).trades().isEmpty());
            assertEquals(new BigDecimal("22.00"),WorldExchange.frozenCash(book(w),"HOUSE-NINA"));
            assertTrue(submit(w,"HY-FOUNDER",Side.SELL,"9.00",3).accepted());
            assertEquals(List.of(first.orderId(),second.orderId()),book(w).trades().stream().map(WorldExchange.Trade::buyOrderId).toList());
            assertEquals(List.of(2L,1L),book(w).trades().stream().map(WorldExchange.Trade::quantity).toList());
            assertTrue(book(w).trades().stream().allMatch(t->t.price().equals(new BigDecimal("11.00"))));
            assertRange(w,"10.00","9.00","11.00","OPENING_REFERENCE");
            assertTrue(submit(w,"HY-FOUNDER",Side.SELL,null,1).accepted());
            assertTrue(book(w).liveOrders().isEmpty());
            assertEquals(0,WorldExchange.frozenCash(book(w),"HOUSE-OMAR").signum());
            w.advanceTo(DAY+OPEN);
            assertRange(w,"11.00","9.90","12.10","PREVIOUS_TRADED_CLOSE");
            assertEquals(DailyPriceBand.OUTSIDE,submit(w,"INV",Side.BUY,"9.89",1).reason());
            var ask1=submit(w,"HOUSE-NINA",Side.SELL,"9.90",2);
            var ask2=submit(w,"HOUSE-OMAR",Side.SELL,"9.90",2);
            assertTrue(ask1.accepted()); assertTrue(ask2.accepted());
            assertEquals(2,book(w).liveOrders().size());
            assertTrue(submit(w,"INV",Side.BUY,"12.10",3).accepted());
            var fills=book(w).trades().subList(3,5);
            assertEquals(List.of(ask1.orderId(), ask2.orderId()),fills.stream().map(WorldExchange.Trade::sellOrderId).toList());
            assertEquals(List.of(2L,1L),fills.stream().map(WorldExchange.Trade::quantity).toList());
            assertTrue(fills.stream().allMatch(t->t.price().equals(new BigDecimal("9.90"))));
            w.advanceTo(7*DAY+OPEN);
            assertRange(w,"9.90","8.91","10.89","PREVIOUS_TRADED_CLOSE");
            assertTrue(book(w).liveOrders().isEmpty());
            var ap=w.autonomyRoleObservation("nina");
            var market=ap.at("/profile/market");
            var row=java.util.stream.StreamSupport.stream(market.spliterator(),false).filter(q -> SYMBOL.equals(q.path("symbol").asText())).findFirst().orElseThrow();
            assertEquals("10.89",row.at("/priceBand/upper").textValue());
            assertFalse(row.toString().contains("HOUSE-OMAR"));
            assertTrue(submit(w,"HY-FOUNDER",Side.SELL,"10.05",1).accepted());
            assertTrue(submit(w,"INV",Side.BUY,"10.89",1).accepted());
            w.advanceTo(8*DAY+OPEN);
            assertRange(w,"10.05","9.05","11.06","PREVIOUS_TRADED_CLOSE");
        }
        try (var w=ComposedIndustryScenario.openPriceBandOperating(journal)) {
            assertEquals(6,book(w).trades().size());
            assertRange(w,"10.05","9.05","11.06","PREVIOUS_TRADED_CLOSE");
        }
        try (var old=ComposedIndustryScenario.openBankCrisisOperating(dir.resolve("old"))) {
            assertNull(quote(old).priceBand());
            old.advanceTo(OPEN);
            assertTrue(submit(old,"INV",Side.BUY,"11.01",1).accepted());
        }
    }

    @Test void restingQuoteCannotBeRestoredAtAnUnadmittedPrice() throws Exception {
        Path file=dir.resolve("tamper.journal");
        try (var w=ComposedIndustryScenario.openPriceBandOperating(file)) {
            w.advanceTo(OPEN);
            assertTrue(submit(w,"INV",Side.BUY,"9.00",1).accepted());
        }
        var forged=dir.resolve("forged.journal");
        var scan=WorldJournalScanner.scan(file);
        String previous=null;
        boolean changed=false;
        try(var out=new WorldJournal(forged,scan.runId(),0,true)) {
            for(var c:scan.commits()) {
                var events=new java.util.ArrayList<WorldEvent>();
                for(var e:c.events()) {
                    if(!StateDeltaCodec.TYPE.equals(e.type())) { events.add(e); continue; }
                    String current=StateDeltaCodec.apply(scan.runId(),c.position(),previous,e.payload());
                    if(c.position()==scan.commits().size()) {
                        var after=JSON.readTree(current);
                        var order=(com.fasterxml.jackson.databind.node.ObjectNode)after.at("/exchange/orders/0");
                        assertEquals(0,order.path("limitPrice").decimalValue().compareTo(new BigDecimal("9.00")));
                        order.put("limitPrice",new BigDecimal("8.99"));
                        String rewritten=StateDeltaCodec.encode(scan.runId(),c.position(),previous,JSON.writeValueAsString(after));
                        events.add(new WorldEvent(e.eventId(),e.type(),rewritten));
                        changed=true;
                    } else events.add(e);
                    previous=current;
                }
                out.commit(c.commandId(),c.commandContent(),c.result(),events);
            }
        }
        assertTrue(changed);
        assertEquals(scan.commits().size(),WorldJournalScanner.scan(forged).commits().size());
        assertThrows(IOException.class,()->ComposedIndustryScenario.openPriceBandOperating(forged));
    }

    @Test void authorizedInstitutionalChildStillFacesTheExchangeLimits() throws Exception {
        try (var w=ComposedIndustryScenario.openPriceBandOperating(dir.resolve("fund"))) {
            long t=OPEN;
            String source="company-governance:eng-a:1";
            w.openRoleSession("alice").startReading("read-a","reading-a",t,source,1); w.advanceTo(t);
            w.advanceTo(w.roleView("alice").readings().get(0).dueAt()); t=w.snapshot().timeMillis()+1;
            w.openRoleSession("alice").submitResearch("research","research-task",t,new WorldRoleSession.ResearchDraft("note",
                    "cufe","fund-a",SYMBOL,"fund-note","订单价格核实","核实开局证券和交易区间。",List.of(new WorldRoleSession.Citation(source,1))));
            w.advanceTo(t);
            w.openRoleSession("bob").startReading("read-b","reading-b",++t,"fund-note",1); w.advanceTo(t);
            w.advanceTo(w.roleView("bob").readings().get(0).dueAt()); t=w.snapshot().timeMillis()+1;
            var cap=new BigDecimal("12.00");
            long held=w.snapshot().securities().orElseThrow().lots().stream()
                    .filter(lot->lot.accountId().equals("ACC-FUND") && lot.symbol().equals(SYMBOL))
                    .mapToLong(SecuritiesLedger.Lot::remaining).sum();
            w.openRoleSession("bob").setTarget("target","target-task",t,new WorldRoleSession.TargetDraft("plan",0,
                    "cufe","fund-a","ACC-FUND",SYMBOL,held+5,cap,2*DAY,"note")); w.advanceTo(t);
            w.openRoleSession("carol").reviewTarget("risk","risk-task",++t,new WorldRoleSession.ReviewDraft("plan",1,true,5,2,cap,2*DAY,"产品额度"));
            w.advanceTo(t); w.openRoleSession("bob").confirmTarget("confirm","confirm-task",++t,"plan",1,1); w.advanceTo(t);
            w.openRoleSession("dave").submitChild("bad","child-bad",++t,new WorldRoleSession.ChildDraft("plan",1,1,"out",1,new BigDecimal("11.01")));
            w.advanceTo(t);
            assertTrue(book(w).liveOrders().isEmpty());
            var notices=w.roleTradingView(w.openRoleSession("dave")).notices();
            assertTrue(notices.stream().anyMatch(n->DailyPriceBand.OUTSIDE.equals(n.reason())),notices.toString());
            w.openRoleSession("dave").submitChild("good","child-good",++t,new WorldRoleSession.ChildDraft("plan",1,1,"in",1,new BigDecimal("11.00")));
            w.advanceTo(t); assertEquals(1,book(w).liveOrders().size());
            assertEquals("ACC-FUND",book(w).liveOrders().get(0).accountId());
        }
    }
}
