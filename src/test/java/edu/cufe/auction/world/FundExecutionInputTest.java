package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FundExecutionInputTest {
    @TempDir Path dir;
    static final String MANDATE="fund-mandate:cufe:fund-a", STOCK="DIGITAL", ACCOUNT="ACC-FUND";
    static final BigDecimal ASK=new BigDecimal("10.10");

    static void read(WorldRuntime world,String person,String doc) throws Exception {
        long now=world.snapshot().timeMillis()+1;
        var session=world.openRoleSession(person);
        session.startReading("read"+now,"read"+now,now,doc,1);
        world.advanceTo(now);
        world.advanceTo(world.roleView(person).readings().get(0).dueAt());
        assertTrue(world.canCite(person,doc,1));
    }

    private static JsonNode input(WorldRuntime w,String person) throws Exception {
        var view=(ObjectNode)w.autonomyRoleObservation(person);RoleInputTables.expand(view);return view;
    }

    @Test void aSecondRiskRevisionAndItsSourceAreVisibleToTheActorsWhoMustUseThem() throws Exception {
        var path=dir.resolve("world.journal");long held;BigDecimal cash;JsonNode finalTarget;
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(path)) {
            w.advanceTo(34_200_000);
            w.scheduleOrder("sell","sell",34_200_001,"omar","HOUSE-OMAR",STOCK,Side.SELL,OrderType.LIMIT,ASK,3);
            w.advanceTo(34_200_001);
            for (var person:List.of("alice","bob","carol","dave")) {
                assertFalse(w.canCite(person,MANDATE,1));
                assertTrue(input(w,person).at("/profile/catalog").findValuesAsText("docId").contains(MANDATE));
                read(w,person,MANDATE);
                var contacts=input(w,person).at("/social/contacts");
                Set<String> identities=new TreeSet<>();
                for(var c:contacts) if(c.path("channel").asText().startsWith("基金工作/")) identities.add(c.get("targetPartyId").asText());
                var expected=new TreeSet<>(List.of("alice","bob","carol","dave"));expected.remove(person);
                assertEquals(expected,identities);
            }
            assertFalse(input(w,"investor").at("/profile/catalog").findValuesAsText("docId").contains(MANDATE));
            long due=w.snapshot().timeMillis()+1;
            w.openRoleSession("alice").submitResearch("note","note",due,new WorldRoleSession.ResearchDraft("R","cufe","fund-a",STOCK,
                    "desk-observation","受控执行回归","以读过的产品委托书和现有市场检验交易权限。",List.of(new WorldRoleSession.Citation(MANDATE,1))));
            w.advanceTo(due);assertFalse(input(w,"bob").at("/research/0/read").asBoolean());
            read(w,"bob","desk-observation");
            var source=input(w,"bob").at("/research/0");assertTrue(source.path("read").asBoolean());
            held=RoleTradingModel.held(w.snapshot().securities().orElseThrow(),ACCOUNT,STOCK);
            cash=w.snapshot().accounts().get(ACCOUNT).cash();due=w.snapshot().timeMillis()+1;
            long deadline=due+1_000_000,expiry=deadline-100;
            w.openRoleSession("bob").setTarget("target","target",due,new WorldRoleSession.TargetDraft("T",0,"cufe","fund-a",ACCOUNT,
                    STOCK,held+3,ASK,deadline,source.required("researchId").asText()));
            w.advanceTo(due);
            var target=input(w,"carol").at("/targets/0");assertEquals(1,target.path("version").asInt());
            for(int i=0;i<2;i++){
                var review=new WorldRoleSession.ReviewDraft("T",target.get("version").asInt(),true,3,3-i,ASK,expiry,"已复核第"+(i+1)+"版");
                w.openRoleSession("carol").reviewTarget("review"+i,"review"+i,++due,review);w.advanceTo(due);
            }
            target=input(w,"bob").at("/targets/0");
            assertEquals("R",target.path("sourceResearchId").asText());
            var risk=target.required("review");
            assertEquals(2,risk.path("riskRevision").asInt());assertEquals(2,risk.path("maxChildQuantity").asLong());
            assertEquals(3,risk.path("maxTotalQuantity").asLong());assertEquals("10.10",risk.path("priceLimit").asText());
            assertEquals(expiry,risk.path("validUntilMillis").asLong());assertTrue(risk.path("approved").asBoolean());
            var pm=w.openRoleSession("bob");
            pm.confirmTarget("old","old",++due,"T",1,1);w.advanceTo(due);
            assertEquals("PENDING_REVIEW",input(w,"bob").at("/targets/0/status").asText());
            pm.confirmTarget("confirm","confirm",++due,target.get("targetId").asText(),target.get("version").asInt(),risk.get("riskRevision").asInt());w.advanceTo(due);
            var executable=input(w,"dave").at("/targets/0");assertEquals("ACTIVE",executable.get("status").asText());
            assertEquals(risk,executable.get("review"));assertTrue(input(w,"investor").get("targets").isEmpty());
            w.openRoleSession("dave").submitChild("order","order",++due,new WorldRoleSession.ChildDraft(executable.get("targetId").asText(),
                    executable.get("version").asInt(),executable.at("/review/riskRevision").asInt(),"C",
                    executable.at("/review/maxChildQuantity").asLong(),new BigDecimal(executable.at("/review/priceLimit").asText())));w.advanceTo(due);
            assertEquals(cash.subtract(ASK.multiply(BigDecimal.valueOf(2))),w.snapshot().accounts().get(ACCOUNT).cash());
            assertEquals(held+2,RoleTradingModel.held(w.snapshot().securities().orElseThrow(),ACCOUNT,STOCK));
            finalTarget=input(w,"dave").at("/targets/0");assertEquals(2,finalTarget.get("filled").asLong());assertEquals(1,finalTarget.get("remaining").asLong());
        }
        var bytes=Files.readAllBytes(path);
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(path)) {
            assertEquals(finalTarget,input(w,"dave").at("/targets/0"));assertEquals(0,w.snapshot().decisions().orElseThrow().state().runUsage().attempts());
            assertEquals(1,w.snapshot().exchange().orElseThrow().trades().size());
        }
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }
}
