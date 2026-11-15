package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectedWorldTest {
    @TempDir Path dir;
    static final long DAY=86_400_000L;
    static void appoint(WorldRuntime w)throws Exception{
        for(var e:ConnectedWorldLoop.managers().entrySet())ConnectedWorldBootstrap.grantBudgetAndAppoint(w,e.getKey(),
                w.boardOfCompany(e.getKey()).orElseThrow().board().charter().seats().stream().map(BoardModel.Seat::partyId).toList(),e.getValue(),"connected-init-"+e.getKey(),List.of(0L,1L,2L));
    }
    static BigDecimal cash(WorldRuntime w){return w.snapshot().accounts().values().stream().map(CashLedger.CashAccount::cash).reduce(BigDecimal.ZERO,BigDecimal::add);}
    @Test void pendingDecisionSurvivesRestartAndSmallAdvancesDoNotDuplicateRoleTasks()throws Exception{
        Path path=dir.resolve("interrupted.journal");
        try(var w=ComposedIndustryScenario.openConnected(path)){
            String actor="omar",id=ConnectedWorldLoop.PREFIX+actor+":0";var input=ConnectedWorldLoop.observation(w,actor);
            long now=w.snapshot().timeMillis();
            w.recordConnectedDecision(new ConnectedWorldLoop.Decision(ConnectedWorldLoop.VERSION,id,actor,actor,"",now,ConnectedWorldLoop.inputReceipt(input),ConnectedWorldLoop.choose(input,actor,now,id)));
        }
        try(var w=ComposedIndustryScenario.openConnected(path)){
            long now=w.snapshot().timeMillis();
            ConnectedWorldLoop.advanceTo(w,now+1);ConnectedWorldLoop.advanceTo(w,now+2);ConnectedWorldLoop.advanceTo(w,now+2_000);ConnectedWorldLoop.advanceTo(w,now+3_000);
            assertEquals(1,w.snapshot().exchange().orElseThrow().orders().stream().filter(o->o.commandId().equals("connected-note:omar:0:act")).count());
        }
    }
    @Test void existingUnfilledOrderDoesNotBlockOtherApprovedTargets()throws Exception{
        var json=new ObjectMapper();var input=json.readTree("{\"role\":{\"profile\":{\"readings\":[]},\"targets\":[{\"status\":\"ACTIVE\",\"leaves\":2,\"remaining\":3},{\"status\":\"ACTIVE\",\"targetId\":\"second\",\"leaves\":0,\"remaining\":5,\"current\":{\"version\":1,\"priceLimit\":\"10\"},\"review\":{\"revision\":1,\"maxChild\":2}}]}}");
        var choice=ConnectedMarketPolicy.choose(input,"dave",100,"x");assertEquals("CHILD",choice.action());assertEquals("second",choice.fields().get("targetId"));
    }
    @Test void actualWorldConnectsBusinessInformationPeopleAndExchangeAndRecovers()throws Exception{
        Path file=dir.resolve("connected.journal");BigDecimal total;long count;
        try(var w=ComposedIndustryScenario.openConnected(file)){
            appoint(w);total=cash(w);
            for(int day=1;day<=10;day++){ConnectedWorldLoop.advanceTo(w,day*DAY);System.out.println("CONNECTED_DAY="+day+" position="+w.snapshot().position()+" trades="+w.snapshot().exchange().orElseThrow().trades().size());}
            var audit=ConnectedWorldLoop.audit(w);var json=new ObjectMapper();
            Files.createDirectories(Path.of("logs/connected-world-20261115/test-results"));
            Files.writeString(Path.of("logs/connected-world-20261115/test-results/audit.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(audit));
            Files.writeString(Path.of("logs/connected-world-20261115/test-results/trading.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(w.snapshot().trading().orElseThrow()));
            Files.writeString(Path.of("logs/connected-world-20261115/test-results/operations.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(w.operationsRun().orElseThrow()));
            for(String company:List.of("mfg-tech","mfg-struct","mfg-versa"))assertFalse(w.operationsRun().orElseThrow().company(company).ledger().batches().isEmpty(),company);
            assertTrue(audit.stream().anyMatch(r->r.get("action").equals("PUBLISH")));
            assertTrue(w.snapshot().information().orElseThrow().information().observations().stream().anyMatch(o->o.personPartyId().equals("alice")&&o.docId().startsWith("connected-news:")));
            assertFalse(w.snapshot().trading().orElseThrow().trading().research().isEmpty(),"应有真实阅读后的机构研究");
            assertFalse(w.snapshot().trading().orElseThrow().trading().targets().isEmpty(),"应有机构目标");
            assertFalse(w.snapshot().exchange().orElseThrow().trades().isEmpty(),"独立库存减持与资金配置应可在本开局产生真实成交");
            assertEquals(0,cash(w).compareTo(total));
            for(var c:PrototypeCompanies.all())if(!c.companyId().equals("north"))assertTrue(ComposedCompanyReport.of(w,c.companyId()).balanced(),c.companyId());
            count=w.snapshot().exchange().orElseThrow().trades().size();
        }
        try(var w=ComposedIndustryScenario.openConnected(file)){
            assertEquals(10*DAY,w.snapshot().timeMillis());assertEquals(count,w.snapshot().exchange().orElseThrow().trades().size());assertEquals(0,cash(w).compareTo(total));
            ConnectedWorldLoop.advanceTo(w,11*DAY);assertTrue(w.snapshot().timeMillis()==11*DAY);
        }
    }
    @Test void privateBooksAreNotTradingInputsAndUnreadFactsCannotProduceResearch()throws Exception{
        try(var w=ComposedIndustryScenario.openConnected(dir.resolve("permissions.journal"))){
            var input=ConnectedWorldLoop.observation(w,"alice");assertFalse(input.has("financial"));assertFalse(input.has("operating"));
            assertEquals("WAIT",ConnectedMarketPolicy.choose(input,"alice",0,"test").action());
            assertTrue(ConnectedWorldLoop.choose(ConnectedWorldLoop.observation(w,"mfg-tech"),"mfg-tech",0,"test").reason().contains("授权"));
            assertThrows(IllegalArgumentException.class,()->w.schedulePublishDocument("fake","fake",1,"connected-note:alice:0","alice",InformationModel.Audience.direct("alice"),"伪造","假决定","",1,List.of()));
        }
    }
    @Test void householdSplitPreservesExistingEndowmentAndMarketChoiceChangesWithCash()throws Exception{
        try(var old=ComposedIndustryScenario.openPrototype(dir.resolve("old.journal"));var w=ComposedIndustryScenario.openConnected(dir.resolve("new.journal"))){
            assertEquals(0,cash(old).compareTo(cash(w)));
            for(var security:w.openingRegistry().orElseThrow().securities())assertEquals(security.issuedShares(),w.openingRegistry().orElseThrow().allocations().stream().filter(a->a.symbol().equals(security.symbol())).mapToLong(OpeningRegistry.ShareAllocation::quantity).sum());
            var input=(com.fasterxml.jackson.databind.node.ObjectNode)ConnectedWorldLoop.observation(w,"omar");
            var choice=ConnectedMarketPolicy.choose(input,"omar",0,"policy");assertEquals("ORDER",choice.action());assertEquals("SELL",choice.fields().get("side"));
            var empty=input.deepCopy();for(var a:empty.path("role").path("profile").path("holdings")){((com.fasterxml.jackson.databind.node.ObjectNode)a).put("quantity",0).put("available",0);}
            assertNotEquals("ORDER",ConnectedMarketPolicy.choose(empty,"omar",0,"policy").action(),"没有库存不得照抄减仓单");
            assertFalse(ConnectedWorldLoop.enabled(old));
        }
    }
}
