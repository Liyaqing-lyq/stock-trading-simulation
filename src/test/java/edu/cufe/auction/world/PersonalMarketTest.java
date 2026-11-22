package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PersonalMarketTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static WorldRuntime open(Path file) throws Exception { return ComposedIndustryScenario.openPersonalMarketOperating(file); }
    private static WorldExchange.State exchange(WorldRuntime w) { return w.snapshot().exchange().orElseThrow(); }
    private static BigDecimal cash(WorldRuntime w, String account) { return w.snapshot().accounts().get(account).cash(); }
    private static long shares(WorldRuntime w, String account, String symbol) {
        return w.snapshot().securities().orElseThrow().lots().stream()
                .filter(l -> l.accountId().equals(account) && l.symbol().equals(symbol)).mapToLong(SecuritiesLedger.Lot::remaining).sum();
    }
    private static String symbol(WorldRuntime w) {
        return w.profile().registry().securities().stream().filter(s -> s.issuerOrganizationId().equals("mfg-tech")).findFirst().orElseThrow().symbol();
    }
    private static ObjectNode limit(String account, String symbol, String side, long qty, String price) {
        return JSON.createObjectNode().put("accountId", account).put("symbol",symbol).put("side",side).put("quantity",qty).put("limitPrice",price);
    }
    private static boolean tool(JsonNode observation, String name) {
        for(var row : observation.path("tools")) if(row.path("name").asText().equals(name)) return true;
        return false;
    }
    private static DecisionRun.ActionRef decide(WorldRuntime w,String person,String action,String tool,ObjectNode args) throws Exception {
        return decide(w, person, action, tool, input -> args, "定向测试本人的交易决定");
    }

    private static DecisionRun.ActionRef decide(WorldRuntime w,String person,String action,String tool,
            java.util.function.Function<JsonNode,ObjectNode> requestedArgs, String basis) throws Exception {
        w.beginDecisionSession("session-"+action);
        w.openDecisions("open-"+action, List.of(new DecisionRun.Request(person,action,"wake",
                new DecisionModel.Route("trading","key-1","deepseek-flash",1L,25,1_000_000,2_048,1))));
        var call=w.dispatchLiveDecision("dispatch-"+action,action).call();
        assertNotNull(call, action);
        var observation=JSON.readTree(call.inputText());
        var reply=JSON.createObjectNode().put("tool",tool);
        reply.set("args",requestedArgs.apply(observation));
        reply.putObject("decision").put("objective",observation.path("engineering").path("deliberation").path("objectives").get(0).path("id").asText())
                .put("basis",basis).put("reviewAfterMillis",60000L);
        assertEquals(WorldRuntime.CompletionOffer.QUEUED,w.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(
                new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),call.attemptNo(),call.recoveryGeneration(),
                        reply.toString(),DecisionModel.Usage.unknownCost(1,100,30),"fixture:scripted"))));
        w.drainDecisionCallbacks();
        return w.snapshot().decisions().orElseThrow().actionRef(action);
    }
    private static JsonNode feedback(WorldRuntime w, String person) throws Exception {
        return w.autonomyRoleObservation(person).path("engineering").path("deliberation").path("lastActionResult");
    }
    private static JsonNode openOrders(WorldRuntime w,String person) throws Exception {
        return w.autonomyRoleObservation(person).path("engineering").path("personalMarket").path("openOrders");
    }

    @Test void onlyExplicitNewArchiveGrantsTradingAndUsesFundedOpening() throws Exception {
        var oldFile=dir.resolve("v2.journal");
        try(var old=ComposedIndustryScenario.openFundedThinkingOperating(oldFile)) {
            assertFalse(tool(old.autonomyRoleObservation("investor"),PersonalMarket.SUBMIT));
            assertFalse(old.profile().manifest().dataVersions().containsKey(PersonalMarket.POLICY));
        }
        assertThrows(Exception.class,()->open(oldFile));
        var file=dir.resolve("v3.journal");
        try(var w=open(file)) {
            assertEquals(List.of(new RoleAccess.PersonAccount("INV","investor")),w.snapshot().roles().orElseThrow().access().personAccounts());
            assertEquals(new BigDecimal("10000.00"),cash(w,"INV"));
            assertEquals(10,w.autonomyRoleObservation("investor").path("profile").path("holdings").size());
            var mine=w.autonomyRoleObservation("investor");
            assertTrue(tool(mine,PersonalMarket.SUBMIT));
            assertFalse(tool(mine,PersonalMarket.CANCEL));
            assertFalse(tool(w.autonomyRoleObservation("dave"),PersonalMarket.SUBMIT));
            assertTrue(tool(w.autonomyRoleObservation("dave"),RoleTradingView.TOOL_CHILD_SUBMIT));
            assertTrue(w.profile().manifest().dataVersions().entrySet().containsAll(Map.of(PersonalMarket.POLICY,"v1",
                    "belief-company-scope","manufacturing-orders-v1","customer-contract-policy",RunManifest.FUNDED_CONTRACT_POLICY,
                    "board-tool-scope","charter-chair-v1").entrySet()));
        }
        assertThrows(Exception.class,()->ComposedIndustryScenario.openFundedThinkingOperating(file));
    }

    @Test void firstPersonalLimitOrderNeedsOnlyOwnAvailableAssetsOnAnEmptyBook() throws Exception {
        Path file = dir.resolve("personal-limit.journal");
        WorldRuntime.Snapshot saved;
        try (var w = ComposedIndustryScenario.openPersonalLimitOperating(file)) {
            var investor = w.autonomyRoleObservation("investor");
            assertTrue(tool(investor, PersonalMarket.SUBMIT));
            assertTrue(investor.at("/engineering/personalMarket/contract").asText().contains("申报不要求已有报价、成交价或基金产品授权"));
            assertTrue(exchange(w).orders().isEmpty());
            assertTrue(exchange(w).trades().isEmpty());
            var action = decide(w, "investor", "first-order", PersonalMarket.SUBMIT, limit("INV", symbol(w), "BUY", 3, "9.50"));
            assertNotNull(action);
            w.advanceTo(action.dueMillis());
            assertEquals("ACTIVE", feedback(w,"investor").path("orderStatus").asText());
            assertEquals(new BigDecimal("28.50"), WorldExchange.frozenCash(exchange(w), "INV"));
            assertEquals(new BigDecimal("10000.00"), cash(w, "INV"));
            assertTrue(exchange(w).trades().isEmpty());
            assertEquals(1,openOrders(w,"investor").size());
            var trader = w.autonomyRoleObservation("dave");
            assertFalse(tool(trader, PersonalMarket.SUBMIT));
            assertFalse(trader.path("engineering").has("personalMarket"));
            assertTrue(tool(trader, RoleTradingView.TOOL_CHILD_SUBMIT));
            saved=w.snapshot();
        }
        try (var reopened=ComposedIndustryScenario.openPersonalLimitOperating(file)) {
            assertEquals(saved,reopened.snapshot());
        }
    }

    @Test void reservedBuyAndRejectedExecutionAndForgedRepliesHaveIndependentReceipts() throws Exception {
        var file=dir.resolve("buy.journal");
        WorldRuntime.Snapshot saved;
        try(var w=open(file)) {
            String symbol=symbol(w);
            var buy=decide(w,"investor","buy",PersonalMarket.SUBMIT,limit("INV",symbol,"BUY",3,"9.50"));assertNotNull(buy);
            assertEquals("PENDING",feedback(w,"investor").path("execution").asText());
            assertTrue(exchange(w).orders().isEmpty());assertTrue(exchange(w).reports().isEmpty());
            w.advanceTo(buy.dueMillis());
            assertEquals(new BigDecimal("28.50"),WorldExchange.frozenCash(exchange(w),"INV"));
            assertEquals(new BigDecimal("10000.00"),cash(w,"INV"));
            assertEquals(0,exchange(w).trades().size());
            var order=openOrders(w,"investor").get(0);assertEquals(3,order.path("remaining").asLong());
            assertEquals("ACTIVE",feedback(w,"investor").path("orderStatus").asText());
            assertFalse(tool(w.autonomyRoleObservation("dave"),PersonalMarket.CANCEL));
            var rejects=List.of(limit("ACC-FUND",symbol,"BUY",1,"10"),limit("unknown",symbol,"BUY",1,"10"),
                    limit("INV",symbol,"BUY",1,"10").put("actorOwnerId","fund-a"),limit("INV",symbol,"BUY",1,"10").put("fundingToken","CUSTODY|x"),
                    limit("INV",symbol,"BUY",1,"10").put("quantity",1.5));
            int seq=0;
            for(var args:rejects) assertNull(decide(w,"investor","invalid"+seq++,PersonalMarket.SUBMIT,args));
            assertNull(decide(w,"dave","foreign",PersonalMarket.SUBMIT,limit("INV",symbol,"BUY",1,"10")));
            assertNull(decide(w,"investor","wrong-ref",PersonalMarket.CANCEL,JSON.createObjectNode().put("accountId","INV").put("orderRef","ACC-FUND/1")));
            assertEquals(1,exchange(w).reports().size());assertEquals(1,exchange(w).orders().size());
            var costly=decide(w,"investor","costly",PersonalMarket.SUBMIT,limit("INV",symbol,"BUY",1_000,"10"));assertNotNull(costly);
            w.advanceTo(costly.dueMillis());var fail=feedback(w,"investor");
            assertEquals("REJECTED",fail.path("execution").asText());assertFalse(fail.path("accepted").asBoolean());
            assertTrue(fail.path("reason").asText().contains("INSUFFICIENT"));assertEquals(1,exchange(w).orders().size());
            var cancelArgs=JSON.createObjectNode().put("accountId","INV").put("orderRef",order.path("orderRef").asText());
            var cancel=decide(w,"investor","cancel",PersonalMarket.CANCEL,cancelArgs);assertNotNull(cancel);w.advanceTo(cancel.dueMillis());
            assertEquals(0,WorldExchange.frozenCash(exchange(w),"INV").signum());assertTrue(openOrders(w,"investor").isEmpty());
            assertEquals("APPLIED",feedback(w,"investor").path("execution").asText());assertFalse(tool(w.autonomyRoleObservation("investor"),PersonalMarket.CANCEL));
            assertNull(decide(w,"investor","again",PersonalMarket.CANCEL,cancelArgs));assertEquals(3,exchange(w).reports().size());
            saved=w.snapshot();
        }
        try(var w=open(file)) {assertEquals(saved,w.snapshot());}
    }

    @Test void insufficientSellAndSelfCrossCannotCreateVolume() throws Exception {
        try (var w=open(dir.resolve("self-trade.journal"))) {
            String s=symbol(w);long initial=shares(w,"INV",s);
            var invalid=decide(w,"investor","oversell",PersonalMarket.SUBMIT,limit("INV",s,"SELL",initial+1,"9.50"));
            assertNotNull(invalid);w.advanceTo(invalid.dueMillis());
            assertEquals("INSUFFICIENT_SHARES",feedback(w,"investor").path("reason").asText());
            assertTrue(exchange(w).orders().isEmpty());
            var buy=decide(w,"investor","own-bid",PersonalMarket.SUBMIT,limit("INV",s,"BUY",2,"9.50"));
            w.advanceTo(buy.dueMillis());
            var sell=decide(w,"investor","own-cross",PersonalMarket.SUBMIT,limit("INV",s,"SELL",2,"9.50"));
            w.advanceTo(sell.dueMillis());
            var result=feedback(w,"investor");
            assertEquals(WorldExchange.REASON_SELF_TRADE_PREVENTED,result.path("reason").asText());
            assertEquals("CANCELLED",result.path("orderStatus").asText());assertEquals(0,result.path("filled").asLong());
            assertTrue(exchange(w).trades().isEmpty());assertEquals(initial,shares(w,"INV",s));
            assertEquals(new BigDecimal("10000.00"),cash(w,"INV"));
            assertEquals(new BigDecimal("19.00"),WorldExchange.frozenCash(exchange(w),"INV"));
            assertEquals(1,openOrders(w,"investor").size());assertTrue(w.snapshot().securities().orElseThrow().reservations().isEmpty());
        }
    }

    @Test void registeredSecondPersonCannotUseAnotherRegisteredPersonalAccountOrItsOrderReference() throws Exception {
        BranchProfile p;
        try (var normal=open(dir.resolve("normal.journal"))) { p=normal.profile(); }
        assertTrue(p.information().readers().containsAll(List.of("nina","omar")));
        // 复用原互通场景的三户分配，原 INV 现金和股份分别守恒，仅用于两名真实个人相互越权的负例。
        var allocations=edu.cufe.auction.world.composed.ConnectedWorldOpening.accounts(p.openings());
        var registry=edu.cufe.auction.world.composed.ConnectedWorldOpening.registry(p.registry());
        var currentBank=p.bankOpening();
        var bank=JSON.readTree(currentBank.configJson());
        var parties=(ObjectNode)bank.path("profile").path("parties");
        for(String person:List.of("nina","omar")) {
            var declaration=new edu.cufe.auction.world.bank.BankingProfile.PartyRef(person,person,
                    "HOUSE-"+person.toUpperCase(java.util.Locale.ROOT),null);
            parties.set(person,JSON.valueToTree(declaration));
        }
        var declaredBank=new edu.cufe.auction.world.bank.BankOpeningRequest(bank.toString(),currentBank.endowmentJson(),
                currentBank.custodyDeclared(),currentBank.custodyOrigins());
        try (var w=new WorldRuntime(dir.resolve("separate-owners.journal"),p.manifest(),allocations,registry,p.manufacturing(),
                p.charter(),p.roles(),p.information(),p.decisions(),null,p.media(),p.interview(),p.dialogue(),p.evidence(),p.social(),
                p.socialBlockMillis(),p.contacts(),p.engineering(),p.groups(),p.platform(),p.feedLimit(),p.readMillis(),
                p.companyCharters(),declaredBank,p.manufacturingFunding())) {
            assertEquals(new BigDecimal("10000.00"),cash(w,"INV").add(cash(w,"HOUSE-NINA")).add(cash(w,"HOUSE-OMAR")));
            assertTrue(tool(w.autonomyRoleObservation("nina"),PersonalMarket.SUBMIT));
            assertTrue(tool(w.autonomyRoleObservation("omar"),PersonalMarket.SUBMIT));
            String symbol=symbol(w);
            var own=decide(w,"nina","nina-order",PersonalMarket.SUBMIT,limit("HOUSE-NINA",symbol,"BUY",1,"5.00"));
            var other=decide(w,"omar","omar-order",PersonalMarket.SUBMIT,limit("HOUSE-OMAR",symbol,"BUY",1,"6.00"));
            assertNotNull(own);assertNotNull(other);
            w.advanceTo(Math.max(own.dueMillis(),other.dueMillis()));
            var nina=openOrders(w,"nina");var omar=openOrders(w,"omar");
            assertEquals(1,nina.size());assertEquals(1,omar.size());
            assertEquals("HOUSE-NINA",nina.get(0).path("accountId").asText());
            assertEquals("HOUSE-OMAR",omar.get(0).path("accountId").asText());
            String otherRef=omar.get(0).path("orderRef").asText();
            assertNotEquals(nina.get(0).path("orderRef").asText(),otherRef);
            assertNull(decide(w,"nina","use-omar-cash",PersonalMarket.SUBMIT,limit("HOUSE-OMAR",symbol,"BUY",2,"5.00")));
            assertNull(decide(w,"nina","use-omar-shares",PersonalMarket.SUBMIT,limit("HOUSE-OMAR",symbol,"SELL",2,"5.00")));
            for(String account:List.of("HOUSE-NINA","HOUSE-OMAR")) {
                assertNull(decide(w,"nina","use-order-"+account,PersonalMarket.CANCEL,JSON.createObjectNode()
                        .put("accountId",account).put("orderRef",otherRef)));
            }
            assertEquals(2,exchange(w).reports().size());assertEquals(2,exchange(w).liveOrders().size());
            assertEquals(new BigDecimal("5.00"),WorldExchange.frozenCash(exchange(w),"HOUSE-NINA"));
            assertEquals(new BigDecimal("6.00"),WorldExchange.frozenCash(exchange(w),"HOUSE-OMAR"));
            assertTrue(exchange(w).trades().isEmpty());
        }
    }

    @Test void staleObservedCancellationGetsRealRejectionAndNeverReleasesFundsTwice() throws Exception {
        Path file=dir.resolve("cancel-race.journal");WorldRuntime.Snapshot saved;
        try(var w=open(file)) {
            var buy=decide(w,"investor","race-buy",PersonalMarket.SUBMIT,limit("INV",symbol(w),"BUY",2,"11.00"));
            w.advanceTo(buy.dueMillis());
            var mine=openOrders(w,"investor").get(0);var order=exchange(w).orders().get(0);
            var cancel=decide(w,"investor","race-cancel",PersonalMarket.CANCEL,JSON.createObjectNode()
                    .put("accountId","INV").put("orderRef",mine.path("orderRef").asText()));
            assertNotNull(cancel);
            long first=w.snapshot().timeMillis()+1;assertTrue(first<cancel.dueMillis());
            w.scheduleCancelOrder("direct-cancel","direct-cancel-task",first,"investor",order.orderId());
            w.advanceTo(first);assertEquals(0,WorldExchange.frozenCash(exchange(w),"INV").signum());
            assertFalse(exchange(w).reports().containsKey(cancel.taskId()));
            w.advanceTo(cancel.dueMillis());
            var report=exchange(w).reports().get(cancel.taskId());assertFalse(report.accepted());
            assertEquals("REJECTED",feedback(w,"investor").path("execution").asText());
            assertEquals(report.reason(),feedback(w,"investor").path("businessReject").asText());
            assertEquals(0,WorldExchange.frozenCash(exchange(w),"INV").signum());assertEquals(0,exchange(w).trades().size());
            saved=w.snapshot();
        }
        try(var w=open(file)){assertEquals(saved,w.snapshot());}
    }

    @Test void missingAndSubstitutedCompletionEvidenceFailStrictRestore() throws Exception {
        Path source=dir.resolve("original.journal");String task;
        try(var w=open(source)) {
            var ref=decide(w,"investor","original-order",PersonalMarket.SUBMIT,limit("INV",symbol(w),"BUY",2,"11.00"));
            task=ref.taskId();w.advanceTo(ref.dueMillis());
        }
        for(String mode:List.of("drop", "command", "time")) {
            Path altered=dir.resolve(mode+".journal");int alteredEvents=0;
            try(var input=new WorldJournal(source,PersonalMarket.RUN_ID);
                var copy=new WorldJournal(altered,PersonalMarket.RUN_ID,0,true)) {
                for(var batch:input.commits()) {
                    var events=new java.util.ArrayList<WorldEvent>();
                    for(var event:batch.events()) {
                        if(batch.commandId().equals("task:"+task) && event.type().equals("EXCHANGE_ORDER_REPORT_V1")) {
                            alteredEvents++;
                            if(mode.equals("drop")) continue;
                            var body=(ObjectNode)JSON.readTree(event.payload());
                            if(mode.equals("command")) body.put("commandId","another-decision");
                            else body.put("timeMillis",body.path("timeMillis").asLong()+1);
                            event=new WorldEvent(event.eventId(),event.type(),body.toString());
                        }
                        events.add(event);
                    }
                    copy.commit(batch.commandId(),batch.commandContent(),batch.result(),events);
                }
            }
            assertEquals(1,alteredEvents);assertThrows(java.io.IOException.class,()->open(altered),mode);
        }
    }

    // 四岗按既有工作流手工推动这个测试对手，产品目标、审核、确认与子单均会走真实权限及交易核。
    private static void authorizeFundPurchase(WorldRuntime w,String symbol,long target) throws Exception {
        long base=w.snapshot().timeMillis();
        long expiry=base+3*86400000L;
        w.schedulePublishDocument("publish","publish-t",base+500,"test-source","alice",InformationModel.Audience.organization("cufe"),
                "交易工作流测试资料","这是测试资料。","测试资料",60000,List.of("fixture"));
        w.advanceTo(base+500);
        w.openRoleSession("alice").startReading("read-alice","read-alice-t",base+1000,"test-source",1);
        w.advanceTo(base+61000);assertTrue(w.canCite("alice","test-source",1));
        w.openRoleSession("alice").submitResearch("research","research-t",base+62000,new WorldRoleSession.ResearchDraft(
                "test-research","cufe","fund-a",symbol,"test-note","测试研究","测试对手的授权研究",
                List.of(new WorldRoleSession.Citation("test-source",1))));
        w.advanceTo(base+62000);
        w.openRoleSession("bob").startReading("read-bob","read-bob-t",base+63000,"test-note",1);
        w.advanceTo(base+123000);assertTrue(w.canCite("bob","test-note",1));
        w.openRoleSession("bob").setTarget("target","target-t",base+124000,new WorldRoleSession.TargetDraft(
                "test-target",0,"cufe","fund-a","ACC-FUND",symbol,target,new BigDecimal("10.00"),expiry,"test-research"));
        w.advanceTo(base+124000);
        w.openRoleSession("carol").reviewTarget("review","review-t",base+125000,new WorldRoleSession.ReviewDraft(
                "test-target",1,true,5L,5L,new BigDecimal("10.00"),expiry,"测试授权限额"));
        w.advanceTo(base+125000);
        w.openRoleSession("bob").confirmTarget("confirm","confirm-t",base+126000,"test-target",1,1); w.advanceTo(base+126000);
        assertEquals(RoleTradingModel.Status.ACTIVE,w.snapshot().trading().orElseThrow().trading().targets().get(0).status());
    }

    @Test void investorReadsTheIssuerDisclosureFormsABeliefThenAnAuthorizedFundFillsThePersonalOrder() throws Exception {
        Path file=dir.resolve("informed-market.journal");
        var evidenceLog=JSON.createObjectNode().put("source","SCRIPTED_MECHANISM").put("providerHttpCalls",0);
        WorldRuntime.Snapshot ending;
        try (var w=open(file)) {
            String issuer="mfg-tech", security=symbol(w);
            String chair=edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.TECH_DIRECTORS.get(0);
            long personalShares=shares(w,"INV",security), fundShares=shares(w,"ACC-FUND",security);
            var investorCash=cash(w,"INV"); var fundCash=cash(w,"ACC-FUND");
            authorizeFundPurchase(w,security,fundShares+5);
            assertTrue(exchange(w).orders().isEmpty());
            long start=w.snapshot().timeMillis(), cutoff=start+100;
            w.openRoleSession(chair).reportOrders("company-discloses","company-discloses-t",start+2,
                    new WorldRoleSession.ReportDraft(issuer,cutoff));
            w.advanceTo(cutoff+1);
            var fact=w.beliefAudit("investor").orElseThrow().factClaims().get(0);
            assertEquals(issuer,fact.companyId()); assertEquals(0,fact.value());
            assertFalse(w.canCite("investor",fact.docId(),1));

            var read=decide(w,"investor","read-company-fact","role.reading.start",
                    JSON.createObjectNode().put("docId",fact.docId()).put("version",1));
            assertNotNull(read); w.advanceTo(read.dueMillis()+5);
            assertTrue(w.canCite("investor",fact.docId(),1));
            var readRef=w.roleView("investor").observations().stream().filter(r->r.ref().docId().equals(fact.docId()))
                    .findFirst().orElseThrow().ref();
            long target=w.snapshot().timeMillis()+3_600_000;
            var beliefArgs=JSON.createObjectNode().put("beliefId","orders-remain-zero").put("companyId",issuer)
                    .put("comparator","AT_MOST").put("threshold",0).put("targetAsOfMillis",target).putNull("adoptedClaimId")
                    .put("rationale","主席披露截至本次截止时未签订单，预计下次核验订单数仍为零。");
            beliefArgs.putArray("evidence").add(JSON.valueToTree(readRef));
            var judgment=decide(w,"investor","form-order-outlook","belief.assert",beliefArgs);
            assertNotNull(judgment); w.advanceTo(judgment.dueMillis());
            var beliefs=w.beliefAudit("investor").orElseThrow().state().beliefs();
            assertEquals(1,beliefs.size());assertEquals(chair,beliefs.get(0).verificationPublisherPartyId());
            var sale=decide(w,"investor","sell-after-reading",PersonalMarket.SUBMIT,input->{
                var card=input.at("/cognition/beliefs/0");
                assertEquals("orders-remain-zero",card.path("beliefId").asText());
                assertEquals(issuer,card.path("companyId").asText());assertEquals("AT_MOST",card.path("comparator").asText());
                assertEquals(0,card.path("threshold").asLong());assertEquals(fact.docId(),card.at("/evidence/0/docId").asText());
                assertEquals(1,card.at("/evidence/0/version").asLong());
                assertTrue(input.path("time").asLong()<card.path("targetAsOf").asLong());
                assertTrue(input.at("/profile/holdings").findValuesAsText("symbol").contains(security));
                return limit("INV",security,"SELL",5,"9.50");
            },"本人已读本公司零订单公告，形成订单数仍为零的判断，此次按本人可卖持仓减持五股。");
            assertNotNull(sale);assertTrue(exchange(w).orders().isEmpty());w.advanceTo(sale.dueMillis());
            assertEquals(1,exchange(w).liveOrders().size());assertTrue(exchange(w).trades().isEmpty());
            var child=decide(w,"dave","regulated-purchase",RoleTradingView.TOOL_CHILD_SUBMIT,input->{
                assertFalse(input.path("engineering").has("personalMarket"));
                return JSON.createObjectNode().put("parentId","test-target").put("parentVersion",1).put("riskRevision",1)
                        .put("childId","informed-child").put("quantity",5).put("limitPrice","10.00");
            },"在已经研究、批准并确认的基金目标和风控限额内购买五股。");
            assertNotNull(child);w.advanceTo(child.dueMillis());
            var trade=exchange(w).trades().get(0);
            assertEquals("INV",trade.sellAccountId()); assertEquals("ACC-FUND",trade.buyAccountId());
            assertEquals(new BigDecimal("9.50"),trade.price()); assertEquals(5,trade.quantity());
            assertEquals(investorCash.add(new BigDecimal("47.50")),cash(w,"INV"));
            assertEquals(fundCash.subtract(new BigDecimal("47.50")),cash(w,"ACC-FUND"));
            assertEquals(personalShares-5,shares(w,"INV",security));assertEquals(fundShares+5,shares(w,"ACC-FUND",security));
            assertEquals("FILLED",feedback(w,"investor").path("orderStatus").asText());assertTrue(openOrders(w,"investor").isEmpty());
            ending=w.snapshot();
            evidenceLog.put("runId",w.profile().manifest().runId()).put("position",ending.position()).put("worldTimeMillis",ending.timeMillis())
                    .put("issuer",issuer).put("symbol",security).put("disclosureCutoffMillis",cutoff).put("disclosedOrderCount",fact.value())
                    .put("docId",fact.docId()).put("readComplete",true).put("beliefId",beliefs.get(0).beliefId())
                    .put("beliefVerificationPublisher",chair).put("personalOrderDue",sale.dueMillis()).put("institutionOrderDue",child.dueMillis());
            evidenceLog.set("trade",JSON.valueToTree(trade));
            var ledger=evidenceLog.putObject("ledger");
            ledger.putObject("INV").put("cashBefore",investorCash.toPlainString()).put("cashAfter",cash(w,"INV").toPlainString())
                    .put("sharesBefore",personalShares).put("sharesAfter",shares(w,"INV",security));
            ledger.putObject("ACC-FUND").put("cashBefore",fundCash.toPlainString()).put("cashAfter",cash(w,"ACC-FUND").toPlainString())
                    .put("sharesBefore",fundShares).put("sharesAfter",shares(w,"ACC-FUND",security));
            var decisions=evidenceLog.putArray("decisions");
            for(var action:List.of("read-company-fact","form-order-outlook","sell-after-reading","regulated-purchase")) {
                var decision=DecisionModel.decision(ending.decisions().orElseThrow().state(),action).orElseThrow();
                var record=decisions.addObject().put("actionId",action).put("person",decision.personPartyId())
                        .put("scene",decision.plan().route().sceneId()).put("inputDigest",decision.inputDigest()).put("tool",decision.recordedTool());
                assertEquals("trading",decision.plan().route().sceneId());
                record.set("originalToolReply",JSON.readTree(decision.lastAttempt().replyBody()));
            }
        }
        try(var reopened=open(file)) {assertEquals(ending,reopened.snapshot());}
        evidenceLog.put("strictReplayEqual",true);
        String output=System.getenv("AUCTION_SCRIPTED_MARKET_SAMPLE");
        if(output!=null) {
            Path destination=Path.of(output);java.nio.file.Files.createDirectories(destination);
            java.nio.file.Files.copy(file,destination.resolve("world-personal-market-operating-v1.journal"));
            java.nio.file.Files.writeString(destination.resolve("evidence.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidenceLog),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }

    @Test void authorizedInstitutionBuysFiveFromPersonalSellerThenSellerCancelsRemainingTwo() throws Exception {
        var file=dir.resolve("both.journal");WorldRuntime.Snapshot finalCut;
        try(var w=open(file)) {
            String s=symbol(w);long beforeMine=shares(w,"INV",s),beforeFund=shares(w,"ACC-FUND",s);
            BigDecimal mine=cash(w,"INV"),fund=cash(w,"ACC-FUND");
            assertTrue(beforeMine>=7);assertTrue(fund.compareTo(new BigDecimal("50.00"))>=0);
            authorizeFundPurchase(w,s,beforeFund+5);
            var sell=decide(w,"investor","sell",PersonalMarket.SUBMIT,limit("INV",s,"SELL",7,"9.50"));assertNotNull(sell);
            w.advanceTo(sell.dueMillis());assertEquals(0,exchange(w).trades().size());
            assertEquals(7L,w.snapshot().securities().orElseThrow().reservations().stream().mapToLong(SecuritiesLedger.SellReservation::quantity).sum());
            var child=decide(w,"dave","fund-child",RoleTradingView.TOOL_CHILD_SUBMIT,JSON.createObjectNode()
                    .put("parentId","test-target").put("parentVersion",1).put("riskRevision",1).put("childId","child").put("quantity",5).put("limitPrice","10.00"));
            assertNotNull(child);assertEquals(0,exchange(w).trades().size());w.advanceTo(child.dueMillis());
            assertEquals(1,exchange(w).trades().size());var trade=exchange(w).trades().get(0);
            assertEquals(5,trade.quantity());assertEquals(new BigDecimal("9.50"),trade.price());
            assertEquals("INV",trade.sellAccountId());assertEquals("ACC-FUND",trade.buyAccountId());
            assertEquals(mine.add(new BigDecimal("47.50")),cash(w,"INV"));assertEquals(fund.subtract(new BigDecimal("47.50")),cash(w,"ACC-FUND"));
            assertEquals(beforeMine-5,shares(w,"INV",s));assertEquals(beforeFund+5,shares(w,"ACC-FUND",s));
            var outstanding=openOrders(w,"investor").get(0);assertEquals(5,outstanding.path("filled").asLong());assertEquals(2,outstanding.path("remaining").asLong());
            assertEquals(5,feedback(w,"investor").path("filled").asLong());assertFalse(w.autonomyRoleObservation("dave").path("engineering").has("personalMarket"));
            var cancel=decide(w,"investor","cancel-sell",PersonalMarket.CANCEL,JSON.createObjectNode().put("accountId","INV").put("orderRef",outstanding.path("orderRef").asText()));
            assertNotNull(cancel);w.advanceTo(cancel.dueMillis());assertTrue(openOrders(w,"investor").isEmpty());
            assertEquals(0,feedback(w,"investor").path("remaining").asLong());assertEquals(2,feedback(w,"investor").path("cancelledQuantity").asLong());
            assertTrue(w.snapshot().securities().orElseThrow().reservations().isEmpty());assertEquals(1,exchange(w).trades().size());
            assertEquals(mine.add(fund),cash(w,"INV").add(cash(w,"ACC-FUND")));assertEquals(beforeMine+beforeFund,shares(w,"INV",s)+shares(w,"ACC-FUND",s));
            finalCut=w.snapshot();
        }
        try(var w=open(file)) {assertEquals(finalCut,w.snapshot());}
    }
}
