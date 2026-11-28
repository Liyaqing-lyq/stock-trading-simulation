package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.BankActions;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.ComposedIndustryScenario.*;
import static edu.cufe.auction.world.composed.ResourceWorldChainTest.*;
import static org.junit.jupiter.api.Assertions.*;

public class ResourceCessationTest {
    @TempDir Path dir;

    public static String captureObservation(WorldRuntime world,String person,String id) throws Exception {
        IssuerCreditDecisionTest.decide(world,person,id,"role.wait",IssuerCreditDecisionTest.JSON.createObjectNode());
        var cut=world.snapshot().decisions().orElseThrow().state().decisions().stream().filter(d->d.actionId().equals(id)).findFirst().orElseThrow();
        assertEquals(DecisionModel.Outcome.WAIT,cut.outcome());
        return cut.inputDigest();
    }

    public static void vote(WorldRuntime world, String id, BoardModel.Topic topic, int yes) throws Exception {
        long now = world.snapshot().timeMillis();
        String doc = id+"-terms";
        world.schedulePublishDocument(id+"-pub",id+"-pub",now+11,doc,DIRECTORS.get(0),InformationModel.Audience.publicAudience(),
                "资源公司会议议题",topic.name(),"按章程表决本次议题 "+topic.name(),10,List.of());
        for(int i=0;i<4;i++) world.scheduleStartReading(id+"-read"+i,id+"-read"+i,now+40+i,DIRECTORS.get(i),doc,1);
        long starts = now + 2000;
        world.scheduleCompanyBoardConvene(id+"-call",id+"-call",starts,RESOURCE,DIRECTORS.get(0),id,
                starts+200,starts+500,starts+4000,topic);
        for (int i=0;i<4;i++) {
            String member = DIRECTORS.get(i);
            world.scheduleCompanyBoardAttend(id+"-attend"+i,id+"-attend"+i,starts+50+i,RESOURCE,member,id);
            world.scheduleCompanyBoardVote(id+"-vote"+i,id+"-vote"+i,starts+2000+i,RESOURCE,member,id,
                    i<yes ? BoardModel.Vote.YES : BoardModel.Vote.NO,1000);
        }
        world.scheduleCompanyBoardMaterial(id+"-material",id+"-material",starts+140,RESOURCE,DIRECTORS.get(0),id,"terms",doc,1);
        world.advanceTo(starts+4000);
        var meeting=world.boardOfCompany(RESOURCE).orElseThrow().board().meeting(id);
        assertEquals(4,meeting.ballots().size(),meeting.toString());
    }

    static String denied(WorldRuntime world,String id, ResourceOperations.Request request) throws Exception {
        long due=world.snapshot().timeMillis()+2000;
        world.openRoleSession(MANAGER).resourceAction(id,id+"-task",due,RESOURCE,request,1000);
        world.advanceTo(due);
        var result=world.operationsRun().orElseThrow().report(id+"-task:finish");
        assertFalse(result.accepted());
        return result.reason();
    }

    static void moveBank(WorldRuntime w,String id,BankActions.Kind kind,BigDecimal amount) throws Exception {
        long due=w.snapshot().timeMillis()+100;
        w.openRoleSession(MANAGER).bankAction(id,id+"-work",due,1,
                new WorldRoleSession.BankActionDraft(id,kind,"north",RESOURCE,amount,0,0,"","","",""));
        w.advanceTo(due);
    }

    @Test void accruedBillsSurviveCessationAndSettleInPartialPaymentsToTheOriginalCreditor() throws Exception {
        var path=dir.resolve("bills.journal");
        try(var w=openTenCompanyAutonomy(path)) {
            OperationsFixture.grantBudgetAndAppoint(w,RESOURCE,DIRECTORS,MANAGER,"bills",List.of(0L));
            moveBank(w,"save",BankActions.Kind.DEPOSIT,w.snapshot().accounts().get(CASH).cash());
            assertEquals(new BigDecimal("0.00"),w.snapshot().accounts().get(CASH).cash());
            vote(w,"wind-down",BoardModel.Topic.RESOURCE_CESSATION,4);
            act(w,"cease",request(ResourceOperations.Action.EXIT_BUSINESS,"meetingId","wind-down"));
            w.advanceTo(IntegratedResourcesModel.PERIOD_MILLIS);
            assertEquals(new BigDecimal("100.00"),resource(w).bills().get(0L).outstanding());
            assertEquals(IntegratedResourcesModel.INSUFFICIENT_CASH,denied(w,"cash-short",request(ResourceOperations.Action.SETTLE_BILL,"period","0")));
            moveBank(w,"part",BankActions.Kind.WITHDRAW,new BigDecimal("40.00"));
            act(w,"pay-part",request(ResourceOperations.Action.SETTLE_BILL,"period","0"));
            assertEquals(new BigDecimal("60.00"),resource(w).bills().get(0L).outstanding());
            assertEquals(new BigDecimal("40.00"),w.snapshot().accounts().get("RES-OPS").cash());
        }
        try(var w=openTenCompanyAutonomy(path)) {
            moveBank(w,"remainder",BankActions.Kind.WITHDRAW,new BigDecimal("60.00"));
            act(w,"pay-last",request(ResourceOperations.Action.SETTLE_BILL,"period","0"));
            assertEquals(new BigDecimal("100.00"),w.snapshot().accounts().get("RES-OPS").cash());
            assertEquals("NO_OUTSTANDING_BILL",denied(w,"twice",request(ResourceOperations.Action.SETTLE_BILL,"period","0")));
            w.advanceTo(2*IntegratedResourcesModel.PERIOD_MILLIS);
            assertEquals(1,resource(w).bills().size());
            assertEquals(new BigDecimal("0.00"),resource(w).bills().get(0L).outstanding());
            assertTrue(ComposedCompanyReport.of(w,RESOURCE).balanced());
        }
    }

    @Test void fourDirectorsCloseOnlyProductionAndKeepOriginalClaimsCashAndHoldings() throws Exception {
        var path=dir.resolve("wind-down.journal");
        long due;
        List<SecuritiesLedger.Lot> original;
        try(var world=openTenCompanyAutonomy(path)) {
            OperationsFixture.grantBudgetAndAppoint(world,RESOURCE,DIRECTORS,MANAGER,"owner",List.of(0L));
            original=world.snapshot().securities().orElseThrow().lots().stream().filter(l->l.symbol().equals("RESOURCE")).toList();
            assertEquals(1000,original.stream().mapToLong(SecuritiesLedger.Lot::remaining).sum());
            var exit=request(ResourceOperations.Action.EXIT_BUSINESS,"meetingId","close-for-real");
            assertEquals(IntegratedResourcesModel.APPROVAL_INVALID,denied(world,"no-vote",exit));
            assertThrows(IllegalArgumentException.class,()->world.scheduleCompanyBoardConvene("not-chair","not-chair",
                    world.snapshot().timeMillis()+2000,RESOURCE,DIRECTORS.get(1),"x",1000,2000,3000,BoardModel.Topic.RESOURCE_CESSATION));
            vote(world,"three-only",BoardModel.Topic.RESOURCE_CESSATION,3);
            assertEquals(BoardModel.MeetingStatus.REJECTED,world.boardOfCompany(RESOURCE).orElseThrow().board().meeting("three-only").status());
            assertEquals(IntegratedResourcesModel.APPROVAL_INVALID,denied(world,"insufficient-votes",request(
                    ResourceOperations.Action.EXIT_BUSINESS,"meetingId","three-only")));
            vote(world,"close-for-real",BoardModel.Topic.RESOURCE_CESSATION,4);
            act(world,"extract",request(ResourceOperations.Action.BEGIN_EXTRACTION,"jobId","mine","seamId","resource-seam-a","quantityKg","400"));
            assertEquals(IntegratedResourcesModel.JOB_IN_PROGRESS,denied(world,"running",exit));
            world.advanceTo(resource(world).jobs().get("mine").dueAt());
            act(world,"sale",request(ResourceOperations.Action.SELL_COAL,"deliveryId","sale","quantityKg","100","receivableId","customer-debt"));
            var before=world.snapshot().accounts();
            act(world,"wind-down",exit);
            assertEquals(before,world.snapshot().accounts());
            assertTrue(resource(world).exited());
            assertEquals("close-for-real",world.operationsRun().orElseThrow().resource().cessation().meetingId());
            assertEquals(300,resource(world).mineCoal().quantity());
            assertEquals(new BigDecimal("20000.00"),resource(world).exitLoss());
            assertEquals(new BigDecimal("300.00"),resource(world).receivables().get("customer-debt").outstanding());
            assertEquals(IntegratedResourcesModel.CLOSED,denied(world,"new-production",request(ResourceOperations.Action.BEGIN_EXTRACTION,
                    "jobId","new","seamId","resource-seam-a","quantityKg","1")));
            assertTrue(ComposedCompanyReport.of(world,RESOURCE).balanced());
            due=resource(world).receivables().get("customer-debt").dueAt();
        }
        byte[] committed=Files.readAllBytes(path);
        try(var world=openTenCompanyAutonomy(path)) { assertTrue(resource(world).exited()); }
        assertArrayEquals(committed,Files.readAllBytes(path));
        try(var world=openTenCompanyAutonomy(path)) {
            var before=world.snapshot().accounts();
            world.advanceTo(due);
            act(world,"honor",request(ResourceOperations.Action.COLLECT,"receivableId","customer-debt"));
            assertEquals(before.get(CASH).cash().add(new BigDecimal("300.00")),world.snapshot().accounts().get(CASH).cash());
            assertEquals(before.get("RES-COAL-CUSTOMER").cash().subtract(new BigDecimal("300.00")),world.snapshot().accounts().get("RES-COAL-CUSTOMER").cash());
            assertTrue(ComposedCompanyReport.of(world,RESOURCE).balanced());
            assertEquals(original,world.snapshot().securities().orElseThrow().lots().stream().filter(l->l.symbol().equals("RESOURCE")).toList());
        }
        try(var world=open(dir.resolve("legacy.journal"))) {
            assertFalse(world.profile().manifest().dataVersions().containsKey("resource-cessation"));
            assertThrows(IllegalArgumentException.class,()->world.scheduleCompanyBoardConvene("old","old",100,RESOURCE,DIRECTORS.get(0),
                    "x",200,300,400,BoardModel.Topic.RESOURCE_CESSATION));
        }
    }
}
