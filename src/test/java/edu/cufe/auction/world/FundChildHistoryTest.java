package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FundChildHistoryTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET = "execution-target", EXECUTOR = "dave";
    private static final BigDecimal PRICE = new BigDecimal("10.10");

    static void authorize(WorldRuntime world) throws Exception {
        world.advanceTo(34_200_000);
        long at = world.snapshot().timeMillis() + 1;
        world.scheduleOrder("offer","offer",at,"omar","HOUSE-OMAR","DIGITAL",Side.SELL,OrderType.LIMIT,PRICE,2);
        world.advanceTo(at);
        for (String person : List.of("alice","bob","carol",EXECUTOR))
            FundExecutionInputTest.read(world,person,FundExecutionInputTest.MANDATE);
        at = world.snapshot().timeMillis() + 1;
        world.openRoleSession("alice").submitResearch("note","note",at,new WorldRoleSession.ResearchDraft("R","cufe",
                "fund-a","DIGITAL","market-note","公开报价","检查实际报价及产品委托。",
                List.of(new WorldRoleSession.Citation(FundExecutionInputTest.MANDATE,1))));
        world.advanceTo(at);
        FundExecutionInputTest.read(world,"bob","market-note");
        at = world.snapshot().timeMillis() + 1;
        long held = RoleTradingModel.held(world.snapshot().securities().orElseThrow(), "ACC-FUND", "DIGITAL");
        long deadline = at + 4_000_000;
        world.openRoleSession("bob").setTarget("target","target",at,new WorldRoleSession.TargetDraft(TARGET,0,
                "cufe","fund-a","ACC-FUND","DIGITAL",held+8,PRICE,deadline,"R"));
        world.advanceTo(at);
        world.openRoleSession("carol").reviewTarget("risk","risk",++at,new WorldRoleSession.ReviewDraft(TARGET,1,
                true,8,2,PRICE,deadline-1,"已检查授权限额"));
        world.advanceTo(at);
        world.openRoleSession("bob").confirmTarget("confirm","confirm",++at,TARGET,1,1);
        world.advanceTo(at);
    }

    static void order(WorldRuntime world, String child, long quantity, boolean cancel) throws Exception {
        var session = world.openRoleSession(EXECUTOR);
        long at = world.snapshot().timeMillis() + 2_000;
        String task = "buy-" + child;
        session.submitChild(task,task,at,new WorldRoleSession.ChildDraft(TARGET,1,1,child,quantity,PRICE));
        world.advanceTo(at);
        assertTrue(world.snapshot().trading().orElseThrow().reports().get(WorldRuntime.roleTaskId(EXECUTOR,task)).accepted());
        if (cancel) {
            task = "cancel-" + child;
            world.openRoleSession(EXECUTOR).cancelChild(task,task,at+2_000,new WorldRoleSession.ChildRef(TARGET,EXECUTOR,child));
            world.advanceTo(at+2_000);
            assertTrue(world.snapshot().trading().orElseThrow().reports().get(WorldRuntime.roleTaskId(EXECUTOR,task)).accepted());
        }
    }

    private static JsonNode act(WorldRuntime world,String person,String action,String tool,ObjectNode args) throws Exception {
        var ref = PersonalMarketTest.decide(world,person,action,tool,args);
        assertNotNull(ref);
        world.advanceTo(ref.dueMillis());
        return world.autonomyRoleObservation(person);
    }

    static JsonNode lookup(WorldRuntime world,String person,String action,String key,String id) throws Exception {
        var args = JSON.createObjectNode().put("section","fund-children");
        if (key != null) args.put(key,id);
        return act(world,person,action,RunManifest.PARTICIPANT_HISTORY_TOOL,args);
    }

    static JsonNode cancelLive(WorldRuntime world,String child) throws Exception {
        return act(world,EXECUTOR,"cancel-live-"+child,RoleTradingView.TOOL_CHILD_CANCEL,
                JSON.createObjectNode().put("parentId",TARGET).put("childId",child));
    }

    @Test void onlyOriginalExecutorLooksUpTerminalChildrenAndCancelsStillWorkingOrders() throws Exception {
        for (boolean current : List.of(false,true)) {
            Path file = dir.resolve(current ? "new.journal" : "old.journal");
            JsonNode last;
            try (var w = current ? ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(file)
                    : ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(file)) {
                authorize(w);
                order(w,"filled",1,false);
                order(w,"partial",2,false);
                order(w,"working",1,false);
                for (int n=0;n<6;n++) order(w,"old-"+n,1,true);
                var before = w.autonomyRoleObservation(EXECUTOR);
                var children = before.required("children");
                assertEquals(current ? 6 : 9,children.size());
                var partial = children.findParents("childId").stream().filter(c -> c.get("childId").asText().equals("partial")).findFirst().orElseThrow();
                assertEquals(1,partial.required("filled").asInt());
                assertEquals(1,partial.required("leaves").asInt());
                if (current) {
                    assertEquals(7,before.at("/engineering/historyLookup/closedFundChildCount").asInt());
                    var page = lookup(w,EXECUTOR,"first",null,null).at("/engineering/historyLookup/lastResult");
                    assertEquals(List.of("old-5","old-4","old-3","old-2"),JSON.convertValue(page.required("ids"),List.class));
                    var next = lookup(w,EXECUTOR,"next","before",page.required("nextBefore").asText());
                    assertEquals(List.of("old-1","old-0","filled"),JSON.convertValue(next.at("/engineering/historyLookup/lastResult/ids"),List.class));
                    assertTrue(next.required("children").findValuesAsText("childId").containsAll(List.of("working","partial","filled")));
                    var done = lookup(w,EXECUTOR,"exact","id","filled");
                    assertEquals("filled",done.at("/engineering/historyLookup/lastResult/ids/0").asText());
                    assertEquals("partial",lookup(w,EXECUTOR,"live-by-id","id","partial")
                            .at("/engineering/historyLookup/lastResult/ids/0").asText());
                    for (String outsider : List.of("alice","bob","investor")) {
                        var denied = lookup(w,outsider,"try-"+outsider,"id","filled");
                        assertTrue(denied.required("children").isEmpty());
                        assertTrue(denied.at("/engineering/historyLookup/lastResult/ids").isEmpty());
                        assertFalse(denied.at("/engineering/historyLookup/lastResult").has("id"));
                    }
                    cancelLive(w,"partial");
                    assertEquals(2,w.snapshot().exchange().orElseThrow().trades().size());
                    assertEquals(new BigDecimal("10.10"),WorldExchange.frozenCash(w.snapshot().exchange().orElseThrow(),"ACC-FUND"));
                    cancelLive(w,"working");
                    assertEquals(0,WorldExchange.frozenCash(w.snapshot().exchange().orElseThrow(),"ACC-FUND").signum());
                } else {
                    assertTrue(before.at("/engineering/historyLookup").isMissingNode());
                }
                last = w.autonomyRoleObservation(EXECUTOR);
            }
            byte[] bytes=Files.readAllBytes(file);
            try (var w = current ? ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(file)
                    : ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(file)) {
                assertEquals(last,w.autonomyRoleObservation(EXECUTOR));
            }
            assertArrayEquals(bytes,Files.readAllBytes(file));
        }
    }
}
