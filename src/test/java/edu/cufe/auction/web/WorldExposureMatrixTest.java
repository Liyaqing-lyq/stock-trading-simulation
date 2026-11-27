package edu.cufe.auction.web;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorldExposureMatrixTest {
    @TempDir Path directory;
    static final long OPEN=570L*60_000, HALT=630L*60_000;

    static void tradeAndHalt(WorldRuntime world) throws Exception {
        world.advanceTo(OPEN);
        world.scheduleOrder("sell-aged","sell-aged",OPEN+3,"nina","HOUSE-NINA","AGED",Side.SELL,OrderType.LIMIT,new BigDecimal("10.50"),1);
        world.scheduleOrder("buy-aged","buy-aged",OPEN+4,"investor","INV","AGED",Side.BUY,OrderType.LIMIT,new BigDecimal("10.50"),1);
        world.advanceTo(HALT);
    }

    @Test void latestTradeOriginalAllocationAndMissingPriceAreAuditableAtOneCut() throws Exception {
        for (boolean missing:List.of(false,true)) {
            Path file=directory.resolve("world-"+missing+".journal");
            try (var world=missing ? ComposedIndustryScenario.openUnpricedReferenceOperating(file,UnpricedReferenceTest.DRAFT)
                    : ComposedIndustryScenario.openMeetingTenCompanyStudy(file)) {
                tradeAndHalt(world);
                long before=world.snapshot().position(), bytes=Files.size(file);
                var view=WorldExposureMatrix.of(world);
                assertTrue(view.available());
                assertEquals(Long.toString(before),view.position());
                assertEquals(Long.toString(HALT),view.timeMillis());
                var aged=view.issuers().stream().filter(i->i.symbol().equals("AGED")).findFirst().orElseThrow();
                assertEquals("TRADE",aged.source());assertEquals("10.50",aged.price());
                assertEquals(Long.toString(OPEN+4),aged.priceTime());
                var hydro=view.issuers().stream().filter(i->i.symbol().equals("HY1")).findFirst().orElseThrow();
                assertEquals("SECURITY_HALTED",hydro.marketStatus());
                assertEquals(missing?"MISSING":"OPENING_REFERENCE",hydro.source());
                var people=view.groups().stream().filter(g->g.kind().equals("PERSON")).findFirst().orElseThrow();
                var investor=people.owners().stream().filter(o->o.ownerId().equals("investor")).findFirst().orElseThrow();
                var nina=people.owners().stream().filter(o->o.ownerId().equals("nina")).findFirst().orElseThrow();
                var buy=investor.cells().get("AGED");assertNotNull(buy);
                var lot=buy.lots().stream().filter(l->l.original().get("kind").equals("TRADE")).findFirst().orElseThrow();
                assertEquals("INV",lot.accountId());assertEquals("HOUSE-NINA",lot.original().get("seller"));
                assertEquals("1",lot.original().get("quantity"));assertEquals("10.50",lot.original().get("price"));
                long initial=world.openingRegistry().orElseThrow().allocations().stream()
                        .filter(a->"INV".equals(a.accountId()) && "AGED".equals(a.symbol())).mapToLong(a->a.quantity()).sum();
                assertEquals(Long.toString(initial+1),buy.quantity());
                assertEquals(new BigDecimal(buy.quantity()).multiply(new BigDecimal("-1.05")),new BigDecimal(buy.valueDelta()));
                var held=nina.cells().get("AGED"); assertNotNull(held);
                assertEquals(new BigDecimal(held.quantity()).multiply(new BigDecimal("-1.05")),new BigDecimal(held.valueDelta()));
                assertTrue(held.lots().stream().allMatch(l->l.original().get("kind").equals("OPENING_ALLOCATION")));
                long all=people.owners().stream().map(o->o.cells().get("AGED")).filter(java.util.Objects::nonNull)
                        .mapToLong(c->Long.parseLong(c.quantity())).sum();
                assertEquals(Long.toString(all),people.cells().get("AGED").quantity());
                assertTrue(view.groups().stream().flatMap(g->g.owners().stream()).anyMatch(o->o.cells().isEmpty()&&o.valueDelta().equals("0.00")));
                if(missing) assertTrue(view.groups().stream().filter(g->g.cells().containsKey("HY1"))
                        .allMatch(g->g.cells().get("HY1").valueDelta()==null && g.valueDelta()==null));
                assertEquals(before,world.snapshot().position());assertEquals(bytes,Files.size(file));
            }
        }
    }
    @Test void confirmedHttpMatrixIsAdminOnlyAndKeepsLedgerPosition() throws Exception {
        var file=directory.resolve("api.journal");
        try (var world=ComposedIndustryScenario.openMeetingTenCompanyStudy(file)) { tradeAndHalt(world); }
        var keyfile=directory.resolve("test-keys");
        Files.write(keyfile,List.of("sk-test-only-local-1","sk-test-only-local-2","sk-test-only-local-3","sk-test-only-local-4","sk-test-only-local-5"));
        try (var routes=SceneRoutes.open(directory);
             var host=new WorldWebHost(()->{
                 var w=ComposedIndustryScenario.openMeetingTenCompanyStudy(file);
                 return new WorldWebHost.Opened(w,w.profile().manifest().runId());
             });
             var http=new WorldWebServer(host,KeySlots.load(keyfile),routes,0,"exposure-local-test",null,line->{})) {
            http.start();
            WorldWebMediaHttpTest.base=http.baseUrl();WorldWebMediaHttpTest.cookie=null;
            try {
                assertEquals(401,WorldWebMediaHttpTest.get("/api/admin/world-exposure").statusCode());
                var logged=WorldWebMediaHttpTest.post("/api/session","{\"bootstrap\":\"exposure-local-test\"}");
                assertEquals(200,logged.statusCode());WorldWebMediaHttpTest.remember(logged);
                var response=WorldWebMediaHttpTest.get("/api/admin/world-exposure");
                assertEquals(200,response.statusCode(),response.body());
                var root=WorldWebMediaHttpTest.JSON.readTree(response.body());
                assertTrue(root.path("available").booleanValue());
                assertEquals(Long.toString(host.adminSnapshot().position()),root.required("position").textValue());
                assertEquals("10.50",root.path("issuers").findValues("price").stream().filter(n->"10.50".equals(n.asText())).findFirst().orElseThrow().textValue());
                assertTrue(root.findValues("lotId").stream().anyMatch(l->"lot-fill:trade-1".equals(l.textValue())));
                var person=WorldWebMediaHttpTest.post("/api/session/person","{\"personId\":\"nina\"}");
                assertEquals(200,person.statusCode()); WorldWebMediaHttpTest.remember(person);
                var denied=WorldWebMediaHttpTest.get("/api/admin/world-exposure");
                assertEquals(403,denied.statusCode());assertFalse(denied.body().contains("lotId"));
            } finally { WorldWebMediaHttpTest.cookie=null; }
        }
    }

}
