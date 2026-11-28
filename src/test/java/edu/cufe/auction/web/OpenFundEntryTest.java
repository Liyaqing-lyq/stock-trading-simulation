package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenFundEntryTest {
    @TempDir Path dir;
    private static final String API="/api/me/fund-units";
    private final AtomicReference<String> bootstrap=new AtomicReference<>("fund-portal-test");

    private static void model(WorldRuntime w,String id,String tool,String request,String amount) throws Exception {
        w.beginDecisionSession("session-"+id);
        var route=new DecisionModel.Route("trading","key-1","deepseek-flash",1,30,1_000_000,2048,1);
        w.openDecisions("open-"+id,List.of(new DecisionRun.Request("investor",id,"wake",route)));
        var call=w.dispatchLiveDecision("dispatch-"+id,id).call();
        assertNotNull(call);
        assertTrue(call.allowedTools().contains(tool),call.allowedTools().toString());
        var input=JSON.readTree(call.inputText());
        assertEquals("investor",input.at("/engineering/openFund/person").asText());
        assertFalse(input.at("/engineering/openFund").has("position"));
        var body=JSON.createObjectNode().put("tool",tool);
        var args=body.putObject("args").put("requestId",request);
        if(amount!=null)args.put("amount",amount);
        body.putObject("decision").put("objective",input.at("/engineering/deliberation/objectives/0/id").asText())
                .put("basis","核对本人基金申请").put("reviewAfterMillis",60000);
        var reply=new DecisionModel.Reply(call.runId(),id,call.attemptId(),call.attemptNo(),call.recoveryGeneration(),
                body.toString(),DecisionModel.Usage.unknownCost(1,31,19),"fixture");
        assertEquals("QUEUED",w.offerDecisionCallback(reply));
        assertEquals(1,w.drainDecisionCallbacks());
        var ref=w.snapshot().decisions().orElseThrow().actionRef(id);
        assertNotNull(ref);w.advanceTo(ref.dueMillis());
    }

    @Test void confirmedProviderIntentUsesTheSameFundBookAndNeverSpendsForAnotherPerson() throws Exception {
        Path file=dir.resolve("open.journal");
        long position;
        try(var w=ComposedIndustryScenario.openOpenFundOperating(file)) {
            assertTrue(w.autonomyRoleObservation("nina").at("/engineering/openFund/holder").booleanValue());
            assertFalse(w.autonomyRoleObservation("sam").path("engineering").has("openFund"));
            model(w,"apply-a","fund.subscribe","order-a","112.30");
            assertEquals("112.30",w.openFundView("investor").get("pendingSubscription"));
            assertEquals("0.00",w.openFundView("nina").get("pendingSubscription"));
            model(w,"cancel-a","fund.cancel","order-a",null);
            assertEquals("0.00",w.openFundView("investor").get("pendingSubscription"));
            model(w,"apply-b","fund.subscribe","order-b","125.00");
            long cutoff=(long)w.openFundView("investor").get("nextCutoffMillis");w.advanceTo(cutoff);
            assertEquals("125.0000",w.openFundView("investor").get("ownUnits"));
            model(w,"redeem-b","fund.redeem","sale-b","3.5000");
            assertEquals("121.5000",w.openFundView("investor").get("availableUnits"));
            position=w.snapshot().position();
        }
        byte[] bytes=Files.readAllBytes(file);
        try(var w=ComposedIndustryScenario.openOpenFundOperating(file)) {
            assertEquals(position,w.snapshot().position());
            assertEquals("3.5000",w.openFundView("investor").get("pendingRedemption"));
            assertEquals(4,w.snapshot().decisions().orElseThrow().actionRefs().size());
        }
        assertArrayEquals(bytes,Files.readAllBytes(file));
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(dir.resolve("fixed.journal"))) {
            assertFalse(w.autonomyRoleObservation("investor").path("engineering").has("openFund"));
            assertTrue(w.openRoleSession("investor").view().tools().stream().noneMatch(OpenFundTools.TOOLS::contains));
        }
    }

    private void as(String person) throws Exception {
        cookie=null;
        var admin=post("/api/session",JSON.createObjectNode().put("bootstrap",bootstrap.get()).toString());
        assertEquals(200,admin.statusCode(),admin.body());remember(admin);
        if(person!=null) {var switched=post("/api/session/person",JSON.createObjectNode().put("personId",person).toString());
            assertEquals(200,switched.statusCode(),switched.body());remember(switched);}
    }
    private static JsonNode current() throws Exception {
        var r=get("/api/me/view");assertEquals(200,r.statusCode(),r.body());return JSON.readTree(r.body()).path("fundUnits");
    }
    @Test void personalHttpUsesOnlyVisibleRevisionAndManagerSeesTotalsWithoutInvestorOrderIds() throws Exception {
        Path file=dir.resolve("http.journal"),keys=dir.resolve("dummy");
        Files.write(keys,List.of("sk-openfund-dummy-111","sk-openfund-dummy-222","sk-openfund-dummy-333","sk-openfund-dummy-444","sk-openfund-dummy-555"));
        String manager;
        try(var w=ComposedIndustryScenario.openOpenFundOperating(file)){
            manager=w.openingRegistry().orElseThrow().parties().stream().filter(p->p.kind()==OpeningRegistry.PartyKind.PERSON)
                    .map(OpeningRegistry.Party::partyId).filter(id->Boolean.TRUE.equals(w.openFundView(id).get("manager"))).findFirst().orElseThrow();
        }
        try(var routes=SceneRoutes.open(dir);var host=new WorldWebHost(()->new WorldWebHost.Opened(
                ComposedIndustryScenario.openOpenFundOperating(file),RunManifest.OPEN_FUND_RUN_ID));
            var server=new WorldWebServer(host,KeySlots.load(keys),routes,0,bootstrap.get(),null,line->{
                if(line.contains("引导凭据"))bootstrap.set(line.substring(line.indexOf('：')+1).trim());
            })) {
            server.start();base=server.baseUrl();cookie=null;
            assertEquals(401,post(API,"{}").statusCode());as(null);assertEquals(403,post(API,"{}").statusCode());
            as("nina");var view=current();assertFalse(view.has("position"));assertFalse(view.has("settlementCash"));
            var order=JSON.createObjectNode().put("revision",view.path("revision").asText()).put("action","SUBSCRIBE")
                    .put("requestId","private-nina").put("amount","72.35");
            assertEquals(400,post(API,order.deepCopy().put("person","omar").toString()).statusCode());
            assertEquals(400,post(API,order.deepCopy().put("amount","72.355").toString()).statusCode());
            var accepted=post(API,order.toString());assertEquals(200,accepted.statusCode(),accepted.body());
            assertEquals("72.35",current().path("pendingSubscription").asText());
            assertEquals(409,post(API,order.toString()).statusCode());
            as("omar");assertFalse(current().toString().contains("private-nina"));
            as(manager);var product=current();assertEquals("72.35",product.path("totalPendingSubscriptions").asText());
            assertFalse(product.toString().contains("private-nina"));
            assertEquals(400,post(API,order.deepCopy().put("revision",product.path("revision").asText()).toString()).statusCode());
            as("nina");order.put("revision",current().path("revision").asText()).put("action","CANCEL");order.remove("amount");
            assertEquals(200,post(API,order.toString()).statusCode());
            assertEquals("0.00",current().path("pendingSubscription").asText());
            as("sam");assertTrue(current().isMissingNode());
        } finally {cookie=null;}
    }
}
