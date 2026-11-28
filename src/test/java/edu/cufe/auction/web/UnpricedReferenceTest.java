package edu.cufe.auction.web;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.ValuationModel;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class UnpricedReferenceTest {
    @TempDir Path dir;
    static final Path DRAFT = Path.of("src/main/resources/world/opening-copies-example.json");
    private static final long OPEN = 570*60_000L, HALT = 630*60_000L, RESUME = 660*60_000L;

    private static WorldRuntime open(Path file, boolean unpriced) throws Exception {
        return unpriced ? ComposedIndustryScenario.openUnpricedReferenceOperating(file,DRAFT)
                : ComposedIndustryScenario.openOwnerBankValuationOperating(file,DRAFT);
    }

    @Test void actualReferenceAndUnknownReferenceSurviveTheSameHaltWithoutChangingPropertyOrMakingTrades() throws Exception {
        Path old = dir.resolve("known.journal"), file = dir.resolve("unknown.journal");
        try (var priced=open(old,false); var missing=open(file,true)) {
            var original=priced.openingRegistry().orElseThrow(); var current=missing.openingRegistry().orElseThrow();
            assertEquals(original.allocations(),current.allocations());
            assertEquals(priced.snapshot().accounts(),missing.snapshot().accounts());
            assertEquals(priced.snapshot().securities(),missing.snapshot().securities());
            assertEquals(original.security("HY1").issuedShares(),current.security("HY1").issuedShares());
            assertEquals(original.security("HY1").initialReferencePrice(),current.security("HY1").unquotedAllocationCost());
            assertNull(current.security("HY1").initialReferencePrice());
            for (var w:List.of(priced,missing)) {
                boolean unknown = w==missing;
                w.advanceTo(OPEN);
                if(unknown) {
                    w.scheduleOrder("no-limit","no-limit",OPEN+1,"investor","INV","HY1",Side.BUY,OrderType.LIMIT,new BigDecimal("10.00"),1);
                    w.scheduleOrder("no-market","no-market",OPEN+2,"investor","INV","HY1",Side.BUY,OrderType.MARKET,null,1);
                }
                w.scheduleOrder("other-sell","other-sell",OPEN+3,"nina","HOUSE-NINA","AGED",Side.SELL,OrderType.LIMIT,new BigDecimal("10.50"),1);
                w.scheduleOrder("other-buy","other-buy",OPEN+4,"investor","INV","AGED",Side.BUY,OrderType.LIMIT,new BigDecimal("10.50"),1);
                w.scheduleOrder("halt-buy","halt-buy",HALT,"nina","HOUSE-NINA","HY1",Side.BUY,OrderType.LIMIT,new BigDecimal("10.00"),1);
                w.advanceTo(HALT);
                var exchange=w.snapshot().exchange().orElseThrow();
                if(unknown) {
                    assertEquals("PRICE_BASIS_UNAVAILABLE",exchange.reports().get("no-limit").reason());
                    assertEquals("PRICE_BASIS_UNAVAILABLE",exchange.reports().get("no-market").reason());
                }
                assertEquals("SECURITY_HALTED",exchange.reports().get("halt-buy").reason());
                assertEquals(List.of("AGED"),exchange.trades().stream().map(t->t.symbol()).toList());
                var obs=w.autonomyRoleObservation("nina");
                assertTrue(obs.at("/engineering/exchangeSessions/scheduledHalt/active").asBoolean());
                var model=obs.at("/profile/portfolios/0");
                assertEquals("nina",model.path("ownerId").asText(),obs.fieldNames().next());
                assertEquals(unknown?"MISSING_PRICE":"COMPLETE",model.path("valuationStatus").asText());
                if(unknown) assertTrue(model.path("netWorth").isNull()); else assertEquals("5637.50",model.path("netWorth").asText());
            }
        }
        byte[] legacy=Files.readAllBytes(old);
        assertThrows(IllegalArgumentException.class,()->open(old,true));
        assertArrayEquals(legacy,Files.readAllBytes(old));
        checkPages(old,false); checkPages(file,true);
        try (var w=open(file,true)) {
            w.advanceTo(RESUME);
            w.scheduleOrder("resume","resume",RESUME+1,"investor","INV","HY1",Side.BUY,OrderType.LIMIT,new BigDecimal("10.00"),1);
            w.advanceTo(RESUME+1);
            assertEquals("PRICE_BASIS_UNAVAILABLE",w.snapshot().exchange().orElseThrow().reports().get("resume").reason());
            assertTrue(w.snapshot().exchange().orElseThrow().liveOrders().isEmpty());
        }
    }

    private static void checkPages(Path file, boolean missing) throws Exception {
        try(var host=new WorldWebHost(()->{var w=open(file,missing);return new WorldWebHost.Opened(w,w.profile().manifest().runId());})) {
            var page=host.personPage("nina"); var who=page.view();
            var hydro=who.market().stream().filter(q->q.symbol().equals("HY1")).findFirst().orElseThrow();
            assertNull(hydro.lastPrice());
            assertEquals(missing?ValuationModel.PriceSource.MISSING:ValuationModel.PriceSource.OPENING_REFERENCE,hydro.reference().source());
            if (missing) { assertNull(hydro.reference().price()); assertNull(hydro.priceBand().lower()); }
            else { assertEquals(new BigDecimal("10.00"),hydro.reference().price()); assertEquals(HALT,hydro.reference().ageMillis()); }
            var own=who.portfolios().get(0); assertEquals(missing?null:"5637.50",own.netWorth());
            var weights=page.holdingWeights().get(who.holdings().stream().filter(h->h.symbol().equals("HY1")).findFirst().orElseThrow());
            assertEquals("2.5000",weights.get("issuedSharePercent"));
            if (missing) assertNull(weights.get("ownerNavPercent")); else assertNotNull(weights.get("ownerNavPercent"));
            assertEquals("SECURITY_HALTED",host.marketHistory("HY1","minute").selectedSessionStatus());
            var overview = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(host.worldOverview());
            var issuer = java.util.stream.StreamSupport.stream(overview.path("companies").spliterator(),false)
                    .filter(row->"HY1".equals(row.path("symbol").asText())).findFirst().orElseThrow();
            assertTrue(issuer.path("lastPrice").isNull());
            if (missing) assertTrue(issuer.path("referencePrice").isNull()); else assertEquals("10.00",issuer.path("referencePrice").asText());
            var fundPage = host.personPage("bob").view().portfolios().stream().filter(o->o.ownerId().equals("fund-a")).findFirst().orElseThrow();
            assertEquals(missing ? "MISSING_PRICE" : "COMPLETE",fundPage.valuationStatus().name());
            assertEquals("10000.00",fundPage.cash());
            var admin=host.adminView();
            assertEquals(fundPage.netWorth(),admin.owners().stream().filter(o->o.ownerId().equals("fund-a")).findFirst().orElseThrow().netWorth());
            var same=admin.owners().stream().filter(o->o.ownerId().equals("nina")).findFirst().orElseThrow();
            assertEquals(own.netWorth(),same.netWorth()); assertEquals(own.valuationStatus().name(),same.status());
            List<Map<String,Object>> rows=new ArrayList<>(); var first=host.performanceView(); rows.addAll(first.owners().rows());
            for(int pageNumber=2;pageNumber<=first.ownerPages();pageNumber++) rows.addAll(host.performanceView(pageNumber).owners().rows());
            var report=rows.stream().filter(row->"nina".equals(row.get("ownerId"))).findFirst().orElseThrow();
            assertEquals(own.netWorth(),report.get("netWorth")); assertEquals(own.valuationStatus().name(),report.get("valuationStatus"));
            if(missing){assertTrue(report.containsKey("periodReturnRank"));assertNull(report.get("periodReturnRank"));assertNull(report.get("returnRate"));}
            else assertFalse(report.containsKey("periodReturnRank"));
            long end=Long.parseLong(admin.position()); var history=host.performanceHistory("nina",end-2,end);
            var last=history.points().get(history.points().size()-1);
            assertEquals(own.netWorth(),last.navAfterFlow()); assertEquals(same.status(),last.valuationStatus());
            if(missing) {assertNull(last.index());assertNull(last.drawdown());}
        }
    }

    @Test void sharingFramesPreservesPerOwnerResultsAndFailureIsolation() throws Exception {
        try (var world=open(dir.resolve("batch.journal"),true)) {
            long last=world.snapshot().position();
            var companyOwner=world.openingRegistry().orElseThrow().owners().stream()
                    .filter(o->o.kind()==edu.cufe.auction.world.OpeningRegistry.OwnerKind.ORGANIZATION).findFirst().orElseThrow().ownerId();
            var result=world.performances(List.of("nina","fund-a",companyOwner,"not-registered"),last-2,last);
            for (var owner:List.of("nina","fund-a",companyOwner)) {
                assertNull(result.get(owner).error());
                assertEquals(world.performance(owner,last-2,last),result.get(owner).value());
            }
            assertNull(result.get("not-registered").value());
            assertNotNull(result.get("not-registered").error());
        }
    }

    @Test void probeResolvesTheVersionedDraftAndCounterWithoutDispatchingAModel() throws Exception {
        String mode="UNPRICED_REFERENCE";
        var config=new AutonomousLifecycleProbe.Config(mode,1,1,3_600_000L,3_600_000L,null,"test",List.of("nina"));
        assertTrue(AutonomousLifecycleProbe.usesMeasuredLength(mode));
        assertTrue(AutonomousLifecycleProbe.thinkingMode(mode));
        assertEquals(LiveDecisionProvider.DECLARED_FIELDS_SYSTEM_TEXT,AutonomousLifecycleProbe.systemPrompt(config));
        Files.copy(DRAFT,dir.resolve("opening-copies.json"));
        Path journal=dir.resolve("world.journal");
        try(var world=AutonomousLifecycleProbe.openWorld(mode,journal)) {
            assertEquals(edu.cufe.auction.world.RunManifest.UNPRICED_REFERENCE_RUN_ID,world.profile().manifest().runId());
            assertEquals(0,world.snapshot().decisions().orElseThrow().state().runUsage().attempts());
        }
        Files.writeString(dir.resolve("opening-copies.json"),"{}");
        try(var world=AutonomousLifecycleProbe.openWorld(mode,journal)) {
            assertEquals(0,world.snapshot().decisions().orElseThrow().state().runUsage().attempts());
        }
    }

    @Test void returnRanksAreCalculatedForEligiblePeopleAndProductsWithCompetitionTies() {
        var rows=new ArrayList<Map<String,Object>>();
        for (String r:List.of("0.1","0.2","0.20","0.05","0.9","0.8")) {
            var row=new LinkedHashMap<String,Object>();
            row.put("kind","PERSON");row.put("valuationStatus","COMPLETE");row.put("performanceStatus","COMPLETE");
            row.put("netWorth","50.00");row.put("returnRate",r);rows.add(row);
        }
        rows.get(2).put("kind","PRODUCT");rows.get(4).put("valuationStatus","MISSING_PRICE");rows.get(5).put("kind","ORGANIZATION");
        OwnerBankValuationWeb.rankReturns(rows);
        assertEquals(java.util.Arrays.asList("3","1","1","4",null,null),rows.stream().map(r->r.get("periodReturnRank")).toList());
    }
}
