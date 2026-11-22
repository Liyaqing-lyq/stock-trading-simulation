package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.web.SceneRoutes;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EngineeringCustomerOperatingTest {
    private static final String COMPANY = "eng-a", ACCOUNT = "ENG-A-CUS", OWNER = "eng-a-cus";
    private static final String REPRESENTATIVE = "eng-a-customer-representative", MANAGER = "erin";
    private static final long DAY = 86_400_000L;
    @TempDir Path directory;

    private Path journal(String name) {
        return Path.of(System.getProperty("engineering.auditDir", directory.toString())).resolve(name + ".journal");
    }

    private static WorldRuntime open(Path file) throws Exception {
        return ComposedIndustryScenario.openEngineeringCustomerOperating(file);
    }

    private static EngineeringRun run(WorldRuntime world) {
        return world.snapshot().engineering().orElseThrow();
    }

    private static void appoint(WorldRuntime world) throws Exception {
        ConnectedWorldBootstrap.grantBudgetAndAppoint(world, COMPANY,
                List.of("alice", "bob", "carol", "dave", MANAGER), MANAGER, "engineering-review", List.of(0L, 1L, 2L));
    }

    private static void read(WorldRuntime world, String contract) throws Exception {
        long at = world.snapshot().timeMillis() + 10;
        world.openRoleSession(MANAGER).startReading("read-" + contract, "read-t-" + contract, at,
                "engineering-opportunity:" + contract, 1);
        world.advanceTo(at + 1000);
    }

    private static EngineeringRun.Report action(WorldRuntime world, String id, String action,
            String contract, long units) throws Exception {
        long at = world.snapshot().timeMillis() + 2000;
        world.scheduleEngineeringAction(id, id + "-t", at, COMPANY, MANAGER, action, contract, id + "-work", 0,
                units, BigDecimal.ZERO, "", at / (30 * DAY), 1000);
        world.advanceTo(at);
        return run(world).reports().get(id + "-t:finish");
    }

    private static void ok(WorldRuntime w, String id, String act, String contract, long units) throws Exception {
        var report = action(w, id, act, contract, units);
        assertTrue(report.accepted(), report.toString());
    }

    private static EngineeringModel.Contract contract(WorldRuntime w, String id) {
        return run(w).stateOf(COMPANY).contracts().get(id);
    }

    private static BigDecimal cash(WorldRuntime w) { return w.snapshot().accounts().get(ACCOUNT).cash(); }
    private static EngineeringRun.Customer.Response last(WorldRuntime w) {
        var responses = run(w).customer().responsesOf(COMPANY);
        assertFalse(responses.isEmpty(), "客户在经理申请后尚未作答");
        return responses.get(responses.size()-1);
    }

    private static void start(WorldRuntime w, String contract, long units) throws Exception {
        read(w, contract);
        ok(w, "sign-" + contract, "SIGN", contract, 0);
        ok(w, "start-" + contract, "START_WORK", contract, units);
    }

    private static void acceptAndInvoice(WorldRuntime w, String id) throws Exception {
        ok(w, "request-" + id, "REQUEST_ACCEPTANCE", id, 0);
        assertEquals("REQUEST_PENDING", action(w, "duplicate-" + id, "REQUEST_ACCEPTANCE", id, 0).reason());
        assertEquals(0, contract(w, id).acceptedUnits());
        w.advanceTo(w.snapshot().timeMillis() + DAY);
        assertEquals("ACCEPTED", last(w).outcome());
        ok(w, "invoice-" + id, "INVOICE", id, 0);
    }

    @Test void onlyTwoFrozenCustomerOffersHaveAnAuthorizedPublisher() throws Exception {
        Path file = journal("opening");
        try (var w = open(file)) {
            var docs = w.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(d -> d.docId().startsWith("engineering-opportunity:")).toList();
            assertEquals(List.of("engineering-opportunity:A1", "engineering-opportunity:A2"),
                    docs.stream().map(d -> d.docId()).sorted().toList());
            assertTrue(docs.stream().allMatch(d -> REPRESENTATIVE.equals(d.publisherPartyId())));
            var registry = w.openingRegistry().orElseThrow();
            var owner = registry.owners().stream().filter(o -> o.ownerId().equals(OWNER)).findFirst().orElseThrow();
            assertEquals(OpeningRegistry.OwnerKind.ORGANIZATION, owner.kind());
            assertEquals("eng-a-customer", owner.partyId());
            assertTrue(registry.parties().stream().anyMatch(p -> p.partyId().equals(REPRESENTATIVE)
                    && p.kind() == OpeningRegistry.PartyKind.PERSON));
            assertTrue(run(w).config().customerEvents().isEmpty());
            JsonNode frozenMandate = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(run(w).config());
            assertEquals(REPRESENTATIVE, frozenMandate.at("/customerMandate/representativePartyId").asText());
            assertEquals("eng-a-customer", frozenMandate.at("/customerMandate/organizationPartyId").asText());
            assertEquals(ACCOUNT, frozenMandate.at("/customerMandate/accountId").asText());
            assertEquals(new BigDecimal("20000.00"), cash(w));
            assertEquals("obligation-review-v1", w.profile().manifest().dataVersions().get("engineering-customer-policy"));
            var rep = w.openRoleSession(REPRESENTATIVE);
            assertTrue(w.autonomyRoleObservation(REPRESENTATIVE).at("/engineering/customerMandate").isObject());
            assertFalse(rep.view().tools().contains(RoleTradingView.TOOL_FORECAST_PUBLISH));
            assertFalse(rep.view().tools().contains(RoleTradingView.TOOL_ENGINEERING_SIGN));
            var forged = new WorldRoleSession.ForecastDraft("claim","engineering-opportunity:A3","fake","fake","fake",
                    1,"mfg-tech",BeliefModel.Comparator.AT_LEAST,0,10000);
            assertThrows(IllegalArgumentException.class, ()-> w.openRoleSession("investor").publishForecast("fake-forecast","fake-f-t",2000,forged));
            assertThrows(IllegalArgumentException.class, ()-> rep.publishForecast("fake-rep","fake-r-t",2000,
                    new WorldRoleSession.ForecastDraft("claim","unsanctioned","fake","fake","fake",1,
                            "mfg-tech",BeliefModel.Comparator.AT_LEAST,0,10000)));
            assertTrue(w.autonomyRoleObservation("investor").at("/engineering/customerMandate").isMissingNode());
            assertThrows(IllegalArgumentException.class, () -> w.schedulePublishDocument("fake","fake-t",10000,
                    "engineering-opportunity:A3", "investor", InformationModel.Audience.publicAudience(),
                    "fake","fake","fake",1,List.of()));
            assertThrows(IllegalArgumentException.class, () -> w.schedulePublishDocument("fake2","fake2-t",10000,
                    "other-offer", REPRESENTATIVE, InformationModel.Audience.publicAudience(),"fake","fake","fake",1,List.of()));
            assertThrows(IllegalArgumentException.class, () -> w.schedulePublishDocumentVersion("rewrite","rewrite-t",10000,
                    docs.get(0).docId(),"fake","fake","fake",1,List.of()));
        }
    }

    @Test void twoContractsReceiveTheirOwnTranchePaymentsFromOneCustomerAccount() throws Exception {
        Path file = journal("two-contracts");
        WorldRuntime.Snapshot saved;
        try (var w = open(file)) {
            appoint(w);
            assertFalse(action(w, "unread", "SIGN", "A1", 0).accepted());
            start(w, "A1", 10);
            assertEquals("NOT_COMPLETED", action(w, "early-accept", "REQUEST_ACCEPTANCE", "A1", 0).reason());
            w.advanceTo(12*DAY);
            acceptAndInvoice(w,"A1");
            var invoice = contract(w,"A1").invoices().get(0);
            assertEquals("NOT_DUE", action(w,"early-payment","REQUEST_PAYMENT","A1",0).reason());
            w.advanceTo(invoice.dueAt());
            assertTrue(w.autonomyInputLimitViolation(MANAGER).isEmpty());
            var boardView = w.autonomyRoleObservation(MANAGER).at("/engineering/directorships").get(0);
            assertTrue(boardView.path("closedMeetingRowsOmitted").asInt()>0);
            ok(w,"first-payment","REQUEST_PAYMENT","A1",0);
            w.advanceTo(w.snapshot().timeMillis()+DAY);
            assertEquals("PAID",last(w).outcome());
            assertEquals(new BigDecimal("400.00"),last(w).amount());
            assertEquals("NO_OUTSTANDING", action(w,"duplicate-paid","REQUEST_PAYMENT","A1",0).reason());
            assertEquals("NOT_DUE", action(w,"early-retention","REQUEST_RETENTION","A1",0).reason());
            start(w,"A2",40);
            w.advanceTo(w.snapshot().timeMillis()+41*DAY);
            acceptAndInvoice(w,"A2");
            var second = contract(w,"A2").invoices().get(0);
            w.advanceTo(91*DAY);
            ConnectedWorldBootstrap.grantBudgetAndAppoint(w,COMPANY,List.of("alice","bob","carol","dave",MANAGER),
                    MANAGER,"engineering-renewal",List.of(3L,4L,5L));
            w.advanceTo(Math.max(second.dueAt(),invoice.retentionDueAt()));
            ok(w,"second-payment","REQUEST_PAYMENT","A2",0);
            ok(w,"first-retention","REQUEST_RETENTION","A1",0);
            w.advanceTo(w.snapshot().timeMillis()+DAY);
            var payments=run(w).customer().paymentsOf(COMPANY);
            assertEquals(3,payments.size());
            assertTrue(payments.stream().allMatch(p -> ACCOUNT.equals(p.poolAccountId())));
            assertEquals(new BigDecimal("600.00"),payments.stream().filter(p -> p.kind().equals("RETENTION")).findFirst().orElseThrow().amount());
            assertEquals(new BigDecimal("20000.00").subtract(cash(w)),payments.stream().map(EngineeringRun.Customer.Payment::amount).reduce(BigDecimal.ZERO,BigDecimal::add));
            JsonNode customer = w.autonomyRoleObservation(REPRESENTATIVE).at("/engineering/customerMandate");
            assertEquals(3,customer.path("payments").size());
            assertFalse(customer.has("companyCash"));
            saved=w.snapshot();
        }
        try(var w=open(file)){assertEquals(saved,w.snapshot());}
    }
    @Test void customerLiquidityAtReviewCanDelayPayPartAndRefuseTheOutstandingClaim() throws Exception {
        Path file = journal("liquidity");
        WorldRuntime.Snapshot pending;
        try (var w = open(file)) {
            appoint(w);
            start(w,"A1",10);
            w.advanceTo(12*DAY);
            acceptAndInvoice(w,"A1");
            var invoice = contract(w,"A1").invoices().get(0);
            w.advanceTo(invoice.dueAt());
            long sameTime=w.snapshot().timeMillis()+2000;
            String first=w.scheduleEngineeringAction("first-demand","first-demand-t",sameTime,COMPANY,MANAGER,
                    "REQUEST_PAYMENT","A1","",0,0,BigDecimal.ZERO,"",sameTime/(30*DAY),1000);
            assertEquals(first,w.scheduleEngineeringAction("first-demand","first-demand-t",sameTime,COMPANY,MANAGER,
                    "REQUEST_PAYMENT","A1","",0,0,BigDecimal.ZERO,"",sameTime/(30*DAY),1000));
            var overlap=assertThrows(IllegalArgumentException.class,()->w.scheduleEngineeringAction("overlapping-demand","overlapping-t",sameTime,COMPANY,MANAGER,
                    "REQUEST_PAYMENT","A1","",0,0,BigDecimal.ZERO,"",sameTime/(30*DAY),1000));
            assertTrue(overlap.getMessage().contains("焦点"));
            w.advanceTo(sameTime);
            assertEquals(1,run(w).customer().requestsOf(COMPANY).stream().filter(r->r.kind().equals("PAYMENT")).count());
            assertEquals(1,w.snapshot().pending().stream().filter(t->t.taskId().startsWith("engineering-review:")).count());
            long at = w.snapshot().timeMillis()+1;
            w.scheduleOrder("reserve-cash","reserve-cash-t",at,OWNER,ACCOUNT,ComposedManufacturingThreeScenario.TECH_SYMBOL,
                    Side.BUY,OrderType.LIMIT,new BigDecimal("1.00"),20000);
            w.advanceTo(at);
            assertEquals(cash(w),WorldExchange.frozenCash(w.snapshot().exchange().orElseThrow(),ACCOUNT));
            pending=w.snapshot();
        }
        WorldRuntime.Snapshot saved;
        try (var w=open(file)) {
            assertEquals(pending,w.snapshot());
            w.advanceTo(w.snapshot().timeMillis()+DAY);
            assertEquals("DELAYED",last(w).outcome());
            assertEquals("CUSTOMER_CASH_FROZEN",last(w).reason());
            assertEquals(new BigDecimal("400.00"),last(w).currentOutstanding());
            var order=w.snapshot().exchange().orElseThrow().orders().stream().filter(o->o.accountId().equals(ACCOUNT)).findFirst().orElseThrow();
            long at=w.snapshot().timeMillis()+1;
            w.scheduleCancelOrder("release-reserve","release-reserve-t",at,OWNER,order.orderId());
            w.advanceTo(at);
            assertEquals(0, WorldExchange.frozenCash(w.snapshot().exchange().orElseThrow(),ACCOUNT).signum());
            ok(w,"second-demand","REQUEST_PAYMENT","A1",0);
            at=w.snapshot().timeMillis()+1;
            w.scheduleTransfer("actual-spending","actual-spending-t",at,OWNER,ACCOUNT,"INV",new BigDecimal("19877.00"),1);
            w.advanceTo(at);
            assertEquals(new BigDecimal("123.00"),cash(w));
            w.advanceTo(at+DAY);
            assertEquals("PARTIAL",last(w).outcome());
            assertEquals(new BigDecimal("123.00"),last(w).amount());
            assertEquals(new BigDecimal("277.00"),last(w).currentOutstanding());
            assertEquals(new BigDecimal("0.00"),cash(w));
            ok(w,"third-demand","REQUEST_PAYMENT","A1",0);
            w.advanceTo(w.snapshot().timeMillis()+DAY);
            assertEquals("REJECTED_PAYMENT",last(w).outcome());
            assertEquals("CUSTOMER_CASH_EXHAUSTED",last(w).reason());
            assertEquals(new BigDecimal("277.00"),last(w).currentOutstanding());
            assertEquals(1,run(w).customer().paymentsOf(COMPANY).size());
            assertTrue(run(w).customer().requestsOf(COMPANY).stream().allMatch(EngineeringRun.Customer.Request::answered));
            var mine=w.autonomyRoleObservation(MANAGER).at("/engineering/managerships").get(0);
            assertTrue(mine.path("customer").path("stages").toString().contains("CUSTOMER_CASH_EXHAUSTED"));
            assertFalse(mine.path("customer").has("freeCash"));
            assertTrue(w.autonomyRoleObservation("investor").at("/engineering/customerMandate").isMissingNode());
            assertTrue(w.companyCustomerView("investor",COMPANY).isEmpty());
            assertEquals(1,w.companyCustomerView(MANAGER,COMPANY).orElseThrow().responses().stream()
                    .filter(r->r.contractId().equals("A1") && r.amount().equals(new BigDecimal("123.00"))).count());
            saved=w.snapshot();
        }
        try (var w=open(file)) {assertEquals(saved,w.snapshot());}
    }

    @Test void customerMandateDoesNotEnrollTheRepresentativeInAutomaticPaidScenes() throws Exception {
        Path file=journal("paid-roster");
        try(var routes=SceneRoutes.open(directory)) {
            routes.replace(routes.view().revision(),routes.view().routes().stream().map(r->new SceneRoutes.Route(
                    r.scene(),r.slotId(),r.model(),true,250,35_000_000,8192,4,"lifecycle-v1","deepseek-thinking-high-v1")).toList());
            try(var host=new WorldWebHost(()->{var w=open(file);return new WorldWebHost.Opened(w,w.profile().manifest().runId());},
                    routes,(call,timeout)->{throw new AssertionError("只读授权检查不应触发模型调用");})) {
                var caps=host.liveCapabilities();
                assertTrue(caps.persons().stream().anyMatch(p->"media".equals(p.sceneId())));
                assertTrue(caps.persons().stream().anyMatch(p->"investor".equals(p.personPartyId())));
                assertTrue(caps.persons().stream().noneMatch(p->REPRESENTATIVE.equals(p.personPartyId())));
                assertTrue(caps.governancePersons().stream().noneMatch(p->REPRESENTATIVE.equals(p.personPartyId())));
                assertTrue(host.personEngineering(REPRESENTATIVE).companies().stream().anyMatch(c->c.containsKey("customerMandate")));
                assertTrue(host.personEngineering("investor").companies().stream().noneMatch(c->c.containsKey("customerMandate")));
            }
        }
    }

    @Test void personalMarketArchiveRetainsItsOriginalEngineeringCalendarAndPublisher() throws Exception {
        Path file=journal("previous-v3");
        WorldRuntime.Snapshot saved;
        try(var w=ComposedIndustryScenario.openPersonalMarketOperating(file)) {
            assertFalse(w.profile().manifest().dataVersions().containsKey("engineering-customer-policy"));
            assertEquals(2,run(w).config().customerEvents().get(COMPANY).size());
            assertTrue(w.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(d -> d.docId().startsWith("engineering-opportunity:")).allMatch(d->"investor".equals(d.publisherPartyId())));
            assertEquals(OpeningRegistry.OwnerKind.AGGREGATE,w.openingRegistry().orElseThrow().owner(OWNER).kind());
            saved=w.snapshot();
        }
        assertThrows(Exception.class,()->{try(var ignored=open(file)){}});
        try(var w=ComposedIndustryScenario.openPersonalMarketOperating(file)){assertEquals(saved,w.snapshot());}
    }
    @Test void completedWorkNeedsARequestAndTerminationBeforeReviewCanPreventAcceptance() throws Exception {
        Path file=journal("no-request-then-terminated");
        WorldRuntime.Snapshot saved;
        try (var w=open(file)) {
            appoint(w);
            start(w,"A1",10);
            w.advanceTo(80*DAY+1);
            assertEquals(10,contract(w,"A1").completedUnits());
            assertEquals(0,contract(w,"A1").acceptedUnits());
            assertTrue(run(w).customer().responsesOf(COMPANY).isEmpty());
            ok(w,"request-then-terminate","REQUEST_ACCEPTANCE","A1",0);
            long at=w.snapshot().timeMillis()+2000;
            w.scheduleEngineeringAction("terminate","terminate-t",at,COMPANY,MANAGER,"TERMINATE","A1","",0,
                    0,BigDecimal.ZERO,"委托人收到终止通知",at/(30*DAY),1000);
            w.advanceTo(at);
            assertTrue(run(w).reports().get("terminate-t:finish").accepted());
            w.advanceTo(at+DAY);
            assertEquals("REJECTED",last(w).outcome());
            assertEquals("CONTRACT_NOT_ACTIVE",last(w).reason());
            assertTrue(run(w).customer().paymentsOf(COMPANY).isEmpty());
            assertEquals(new BigDecimal("20000.00"),cash(w));
            saved=w.snapshot();
        }
        try(var w=open(file)){assertEquals(saved,w.snapshot());}
    }
}
