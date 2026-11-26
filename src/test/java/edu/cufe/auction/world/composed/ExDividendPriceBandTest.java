package edu.cufe.auction.world.composed;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExDividendPriceBandTest {
    @TempDir Path dir;
    private static final long DAY = SecuritiesLedger.DAY_MILLIS, OPEN = 570L * 60_000L;
    private static final String ISSUER="eng-a", SYMBOL="CUFE-A", SCHEME="cash-scheme";

    private static DailyPriceBand band(WorldRuntime w,String symbol) {
        return w.roleView("nina").market().stream().filter(q->symbol.equals(q.symbol())).findFirst().orElseThrow().priceBand();
    }
    private static WorldExchange.State book(WorldRuntime w) { return w.snapshot().exchange().orElseThrow(); }

    private static WorldExchange.OrderReport order(WorldRuntime w,String name,String account,String symbol,Side side,
                                                   BigDecimal price,long quantity) throws Exception {
        long t=w.snapshot().timeMillis()+1;
        w.scheduleOrder(name,name,t,w.snapshot().accounts().get(account).ownerId(),account,symbol,side,
                price==null?OrderType.MARKET:OrderType.LIMIT,price,quantity);
        w.advanceTo(t);
        var result=book(w).reports().get(name);
        assertTrue(result.accepted(),name+" "+result.reason());
        return result;
    }
    private static void approve(WorldRuntime w,long now) throws Exception {
        var charter=w.boardOfCompany(ISSUER).orElseThrow().board().charter();
        var voters=charter.seats().stream().map(BoardModel.Seat::partyId).toList();
        var chair=charter.seats().stream().filter(s->charter.chairSeatId().equals(s.seatId())).findFirst().orElseThrow().partyId();
        var req=new DividendModel.SchemeRequest(SCHEME,SYMBOL,ISSUER,"ENG-A",new BigDecimal("0.50"),now+20000,now+21000,now+22000);
        var doc=CompanyOpeningDocuments.documentId(ISSUER);
        for(int i=0;i<voters.size();i++) w.scheduleStartReading("read"+i,"rt"+i,now+5,voters.get(i),doc,1);
        w.advanceTo(now+2000);
        w.openRoleSession(chair).issuerDividend("meet","meet-task",now+3000,"CONVENE","meeting",now+6000,now+7000,now+10500,null);
        for(int i=0;i<voters.size();i++) w.scheduleEngineeringAttend("attend"+i,"at"+i,now+4000,ISSUER,voters.get(i),"meeting",false);
        w.scheduleEngineeringMaterial("material","mat-task",now+4500,ISSUER,chair,"meeting","material",doc,1);
        w.openRoleSession(chair).issuerDividend("propose","propose-task",now+5000,"PROPOSE","meeting",0,0,0,req);
        w.advanceTo(now+5500);
        for(int i=0;i<voters.size();i++) w.scheduleEngineeringVote("vote"+i,"vt"+i,now+9000,ISSUER,voters.get(i),"meeting",BoardModel.Vote.YES,1000);
        w.openRoleSession(chair).issuerDividend("authorize","auth-task",now+11000,"AUTHORIZE","meeting",0,0,0,null);
        w.advanceTo(now+20000);
        assertEquals(DividendModel.Phase.REGISTERED,w.snapshot().dividends().orElseThrow().schemes().get(SCHEME).phase());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void bookedOnlyOnceWithVisibleAndExecutableRangeAndSelectiveCancellation(boolean priorTrade) throws Exception {
        Path file=dir.resolve("ex.journal");
        BigDecimal base=priorTrade?new BigDecimal("10.50"):new BigDecimal("10.00");
        BigDecimal retainedBid=base.subtract(new BigDecimal("0.20"));
        WorldRuntime.Snapshot end;
        try(var w=ComposedIndustryScenario.openBaselinePartsOperating(file)) {
            w.advanceTo(OPEN);
            if(priorTrade) {
                order(w,"sale","INV",SYMBOL,Side.SELL,base,1);
                order(w,"bid","HOUSE-NINA",SYMBOL,Side.BUY,base,1);
            }
            w.advanceTo(DAY+OPEN);
            long now=w.snapshot().timeMillis();
            approve(w,now);
            var b=band(w,SYMBOL);
            assertEquals(base,b.basis());
            assertEquals(priorTrade?"PREVIOUS_TRADED_CLOSE":"OPENING_REFERENCE",b.source());
            var last=order(w,"outside-buy","HOUSE-NINA",SYMBOL,Side.BUY,b.upper().subtract(new BigDecimal("0.01")),2);
            var sell=order(w,"outside-sell","INV",SYMBOL,Side.SELL,b.upper(),2);
            var first=order(w,"first","HOUSE-OMAR",SYMBOL,Side.BUY,retainedBid,1);
            var second=order(w,"second","HOUSE-NINA",SYMBOL,Side.BUY,retainedBid,1);
            var other=order(w,"unrelated","HOUSE-OMAR","HY1",Side.BUY,new BigDecimal("11.00"),1);
            var available=SecuritiesLedger.available(w.snapshot().securities().orElseThrow(),"INV",SYMBOL,1);
            int trades=book(w).trades().size();
            w.scheduleOrder("at-ex","at-ex",now+21000,"investor","INV",SYMBOL,Side.SELL,OrderType.LIMIT,b.upper(),1);
            w.advanceTo(now+21000);
            var after=band(w,SYMBOL);
            assertEquals(base.subtract(new BigDecimal("0.50")),after.basis());
            assertEquals("EX_DIVIDEND_REFERENCE",after.source());
            assertEquals(priorTrade?new BigDecimal("11.00"):new BigDecimal("10.45"),after.upper());
            assertEquals("PRICE_OUT_OF_LIMIT",book(w).reports().get("at-ex").reason());
            assertEquals(trades,book(w).trades().size());
            for(var out:List.of(last,sell)) assertEquals(WorldExchange.OrderStatus.CANCELLED,book(w).order(out.orderId()).status());
            for(var live:List.of(first,second,other)) assertTrue(book(w).order(live.orderId()).live());
            assertEquals(retainedBid,WorldExchange.frozenCash(book(w),"HOUSE-NINA"));
            assertEquals(available+2,SecuritiesLedger.available(w.snapshot().securities().orElseThrow(),"INV",SYMBOL,1));
            var holdings=w.snapshot().accounts();
            w.advanceTo(now+22000);
            assertEquals(DividendModel.Phase.SETTLED,w.snapshot().dividends().orElseThrow().schemes().get(SCHEME).phase());
            assertEquals(after,band(w,SYMBOL));
            assertTrue(w.snapshot().accounts().get("INV").cash().compareTo(holdings.get("INV").cash())>0);
            order(w,"consume","INV",SYMBOL,Side.SELL,null,2);
            var prints=book(w).trades().subList(trades,trades+2);
            assertEquals(List.of(first.orderId(),second.orderId()),prints.stream().map(WorldExchange.Trade::buyOrderId).toList());
            assertTrue(prints.stream().allMatch(t->retainedBid.equals(t.price())));
            assertEquals(after,band(w,SYMBOL));
            w.advanceTo(2*DAY+OPEN);
            assertEquals(retainedBid,band(w,SYMBOL).basis());
            assertEquals("PREVIOUS_TRADED_CLOSE",band(w,SYMBOL).source());
            end=w.snapshot();
        }
        var scan=WorldJournalScanner.scan(file);
        assertEquals(1,scan.commits().stream().flatMap(c->c.events().stream())
                .filter(e->e.type().equals("EX_DIVIDEND_ORDER_RELEASE_V1")).count());
        try(var w=ComposedIndustryScenario.openBaselinePartsOperating(file)) {assertEquals(end,w.snapshot());}
        if (priorTrade) {
            var forgery=dir.resolve("missing-release-proof.journal");
            try(var writer=new WorldJournal(forgery,scan.runId())) {
                for (var c:scan.commits()) {
                    var events=c.events().stream().filter(e->!e.type().equals("EX_DIVIDEND_ORDER_RELEASE_V1")).toList();
                    writer.commit(c.commandId(),c.commandContent(),c.result(),events);
                }
            }
            assertThrows(java.io.IOException.class,()->ComposedIndustryScenario.openBaselinePartsOperating(forgery));
        }
    }
}
