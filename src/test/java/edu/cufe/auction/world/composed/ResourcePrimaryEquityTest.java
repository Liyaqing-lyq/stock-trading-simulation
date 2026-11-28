package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.ComposedIndustryScenario.*;
import static edu.cufe.auction.world.composed.ResourceWorldChainTest.request;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePrimaryEquityTest {
    @TempDir Path dir;
    static final String PERSON = ResourcePrimaryEquity.SUBSCRIBER;
    static long shares(WorldRuntime w,String account) {
        return w.snapshot().securities().orElseThrow().lots().stream().filter(l->account.equals(l.accountId()) && "RESOURCE".equals(l.symbol())).mapToLong(SecuritiesLedger.Lot::remaining).sum();
    }

    static CompanyOperationsRun.ReportEntry subscribe(WorldRuntime world,String id,long count) throws Exception {
        long due=world.snapshot().timeMillis()+2000;
        world.openRoleSession(PERSON).resourceAction(id,id+"-work",due,RESOURCE,
                request(ResourceOperations.Action.SUBSCRIBE_SHARES,"meetingId","issue","shares",Long.toString(count)),1000);
        world.advanceTo(due);
        return world.operationsRun().orElseThrow().report(id+"-work:finish");
    }

    @Test void frozenBidAndLifetimeIssueCapProtectTheTwoLedgers() throws Exception {
        try(var w=openTenCompanyAutonomy(dir.resolve("frozen.journal"))) {
            ResourceCessationTest.vote(w,"issue",BoardModel.Topic.RESOURCE_PRIMARY_OFFER,4);
            long open=570L*60_000+1;
            w.scheduleOrder("freeze","freeze-work",open,PERSON,"INV","CUFE-A",edu.cufe.auction.model.Side.BUY,
                    edu.cufe.auction.model.OrderType.LIMIT,new BigDecimal("10.00"),490);
            w.advanceTo(open);
            var order=w.snapshot().exchange().orElseThrow().reports().get("freeze-work");
            assertTrue(order.accepted(),order.toString());
            var cash=w.snapshot().accounts();
            var denied=subscribe(w,"too-little-free-cash",20);
            assertFalse(denied.accepted()); assertEquals(IntegratedResourcesModel.INSUFFICIENT_CASH,denied.reason());
            assertEquals(cash,w.snapshot().accounts()); assertEquals(1000,w.issuedSharesAt("RESOURCE",w.snapshot().position()));
            long cancel=w.snapshot().timeMillis()+1;
            w.scheduleCancelOrder("release","release-work",cancel,PERSON,order.orderId()); w.advanceTo(cancel);
            assertEquals("SUBSCRIPTION_SIZE_INVALID",subscribe(w,"ceiling",101).reason());
            assertEquals("SUBSCRIPTION_SIZE_INVALID",subscribe(w,"zero",0).reason());
            assertEquals(cash,w.snapshot().accounts());
            assertTrue(subscribe(w,"maximum",100).accepted());
            assertEquals(1100,w.issuedSharesAt("RESOURCE",w.snapshot().position()));
            assertTrue(w.primaryOfferFor(PERSON).isEmpty());
        }
    }

    @Test void realOwnerCanDeclineOrPayForNewSharesWithoutTakingExistingHoldings() throws Exception {
        var path=dir.resolve("primary.journal");
        long opening,after;
        BigDecimal oldNav;
        try(var w=openTenCompanyAutonomy(path)) {
            opening=w.snapshot().position();
            var stale=w.openRoleSession(PERSON);
            var bid=request(ResourceOperations.Action.SUBSCRIBE_SHARES,"meetingId","issue","shares","40");
            assertThrows(IllegalArgumentException.class,()->stale.resourceAction("before","before",2000,RESOURCE,bid,1000));
            ResourceCessationTest.vote(w,"issue",BoardModel.Topic.RESOURCE_PRIMARY_OFFER,4);
            var current=w.snapshot();
            assertEquals(1000,w.issuedSharesAt("RESOURCE",current.position()));
            assertEquals(60,shares(w,"INV"));
            assertFalse(w.primaryOfferFor(PERSON).isEmpty());
            assertTrue(w.primaryOfferFor("nina").isEmpty());
            assertThrows(IllegalArgumentException.class,()->stale.resourceAction("stale","stale",current.timeMillis()+2000,RESOURCE,bid,1000));
            assertThrows(IllegalArgumentException.class,()->w.openRoleSession("nina").resourceAction("cross","cross",current.timeMillis()+2000,RESOURCE,bid,1000));
            assertThrows(IllegalArgumentException.class,()->w.openRoleSession(MANAGER).resourceAction("manager","manager",current.timeMillis()+2000,RESOURCE,bid,1000));
            oldNav=ComposedCompanyReport.of(w,RESOURCE).totalEquity();
            var cutoff=w.snapshot().timeMillis();
            var ref=IssuerCreditDecisionTest.decide(w,PERSON,"subscription",ResourcePrimaryEquity.TOOL,
                    IssuerCreditDecisionTest.JSON.createObjectNode().put("meetingId","issue").put("shares",40L));
            assertNotNull(ref,w.snapshot().decisions().orElseThrow().state().decisions().toString());
            var due=ref.dueMillis()+ResourceDecisionTools.ACTION_MILLIS;
            assertTrue(due>cutoff);
            w.advanceTo(due-1);
            assertEquals(1000,w.issuedSharesAt("RESOURCE",w.snapshot().position()));
            var before=w.snapshot();
            w.advanceTo(due);
            after=w.snapshot().position();
            var subscriptions=w.operationsRun().orElseThrow().resource().subscriptions();
            assertEquals(1,subscriptions.size());
            var issue=subscriptions.get(0);
            assertEquals(40,issue.shares());
            assertEquals(new BigDecimal("400.00"),issue.paid());
            assertEquals(before.accounts().get("INV").cash().subtract(issue.paid()),w.snapshot().accounts().get("INV").cash());
            assertEquals(before.accounts().get(CASH).cash().add(issue.paid()),w.snapshot().accounts().get(CASH).cash());
            assertEquals(before.accounts().get("RES-SPONSOR"),w.snapshot().accounts().get("RES-SPONSOR"));
            assertEquals(oldNav.add(issue.paid()),ComposedCompanyReport.of(w,RESOURCE).totalEquity());
            assertEquals(List.of(issue.paid()),w.capitalFlows(RESOURCE).stream().map(WorldRuntime.CapitalFlowReport::amount).toList());
            assertTrue(w.capitalFlows(PERSON).isEmpty());
            var companyPerformance=w.performance(RESOURCE,before.position(),after);
            assertEquals(PerformanceModel.Status.INCOMPLETE_VALUATION, companyPerformance.status());
            assertNull(companyPerformance.returnRate());
            assertEquals(0,w.performance(PERSON,before.position(),after).returnRate().signum());
            assertTrue(ComposedCompanyReport.of(w,RESOURCE).balanced());
            assertEquals(1040,w.issuedSharesAt("RESOURCE",after));
            assertEquals(1000,w.issuedSharesAt("RESOURCE",opening));
            assertEquals(100,shares(w,"INV"));
            assertEquals(800,shares(w,"RES-FOUNDER"));
            assertEquals(25,shares(w,"HOUSE-NINA"));
            var lots=w.snapshot().securities().orElseThrow();
            var added=lots.lots().stream().filter(l->l.lotId().equals("resource-primary:"+issue.commandId())).findFirst().orElseThrow();
            assertEquals(new BigDecimal("10.0000"),added.costPrice());
            long day=SecuritiesLedger.day(due);
            assertEquals(60,SecuritiesLedger.available(lots,"INV","RESOURCE",day));
            assertEquals(100,SecuritiesLedger.available(lots,"INV","RESOURCE",day+1));
        }
        byte[] bytes=Files.readAllBytes(path);
        try(var w=openTenCompanyAutonomy(path)) {
            assertEquals(1040,w.issuedSharesAt("RESOURCE",after));
            assertEquals(1000,w.issuedSharesAt("RESOURCE",opening));
            assertEquals(100,shares(w,"INV"));
        }
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }
}
