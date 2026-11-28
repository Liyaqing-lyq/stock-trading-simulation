package edu.cufe.auction.world;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import static edu.cufe.auction.world.MarketSessions.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementCashTest {
    @TempDir Path dir;
    private static final String WALLET="INV", OTHER="HOUSE-NINA", FOUNDER="HY-FOUNDER", CASH="CASH|INV";

    private static WorldExchange.OrderReport order(WorldRuntime w,String account,Side side,String price,long qty,String funding) throws Exception {
        long at=w.snapshot().timeMillis()+1; String id="order-"+w.snapshot().position();
        var owner=w.snapshot().accounts().get(account).ownerId();
        w.scheduleOrder(id,id,at,owner,account,"HY1",side,OrderType.LIMIT,new BigDecimal(price),qty,funding);
        w.advanceTo(at);
        var result=w.snapshot().exchange().orElseThrow().reports().get(id);
        assertTrue(result.accepted(),result.toString());
        return result;
    }
    private static void cancel(WorldRuntime w,long id) throws Exception {
        long at=w.snapshot().timeMillis()+1;String command="cancel-"+w.snapshot().position();
        w.scheduleCancelOrder(command,command,at,"investor",id);w.advanceTo(at);
        assertTrue(w.snapshot().exchange().orElseThrow().reports().get(command).accepted());
    }
    private static String bank(WorldRuntime w,BankActions.Kind action,String amount,String source,String target) throws Exception {
        long at=w.snapshot().timeMillis()+5;String id="bank-"+w.snapshot().position();
        w.openRoleSession("investor").bankAction(id,id,at,3,new WorldRoleSession.BankActionDraft(id,action,
                "north","",new BigDecimal(amount),0,0,"",source,target,""));
        w.advanceTo(at);
        return w.taskCommitResult(WorldRuntime.roleTaskId("investor",id)+":finish");
    }
    private static SettlementCash.Amounts values(String trading,String frozen,String pending,String withdrawable) {
        return new SettlementCash.Amounts(new BigDecimal(trading),new BigDecimal(frozen),new BigDecimal(pending),
                new BigDecimal(withdrawable),new BigDecimal("0.00"));
    }
    private static SettlementCash.Amounts wallet(WorldRuntime w) throws Exception {
        var value=w.cashSettlementAt(WALLET,w.snapshot().position());
        assertEquals(value,w.cashSettlementsAt(w.snapshot().position()).get(WALLET));
        assertEquals(value,w.roleView("investor").accounts().stream().filter(a->WALLET.equals(a.accountId())).findFirst().orElseThrow().settlement());
        return value;
    }
    static void prepareFriday(Path journal) throws Exception { prepareFriday(journal,CASH); }
    private static void prepareFriday(Path journal,String saleBucket) throws Exception {
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(journal)) {
            prepareFriday(w,saleBucket);
        }
    }
    private static void prepareFriday(WorldRuntime w,String saleBucket) throws Exception {
            w.advanceTo(OPEN);
            order(w,FOUNDER,Side.SELL,"10.00",10,null);
            order(w,WALLET,Side.BUY,"10.00",10,null);
            var balance=w.snapshot().accounts().get(WALLET).cash();
            assertEquals("OK",bank(w,BankActions.Kind.DEPOSIT,balance.subtract(new BigDecimal("20.00")).toPlainString(),"",""));
            assertEquals(values("20.00","0.00","0.00","20.00"),wallet(w));
            w.advanceTo(4*DAY+OPEN);
            order(w,OTHER,Side.BUY,"10.00",10,null);
            order(w,WALLET,Side.SELL,"10.00",10,saleBucket);
    }
    @Test void fridaySoldFundsTradeImmediatelyWithdrawOnMondayAndDoNotDoubleCountBuyReserve() throws Exception {
        Path file=dir.resolve("cash.journal"); prepareFriday(file);
        long friday,weekend;
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            friday=w.snapshot().position();
            assertEquals(values("120.00","0.00","100.00","20.00"),wallet(w));
            var observation=w.engineeringRoleInput("investor");
            assertTrue(observation.at("/personalMarket/cashSettlement").asText().contains("周一零时"));
            var personalPlan=java.util.stream.StreamSupport.stream(observation.at("/deliberation/objectives").spliterator(),false)
                    .filter(goal -> "person:investor".equals(goal.path("id").asText())).findFirst().orElseThrow().path("personalPlan");
            assertEquals("120.00",personalPlan.path("tradingAvailableCash").asText());
            assertEquals("20.00",personalPlan.path("availableCash").asText());
            assertEquals("4380.00",personalPlan.path("cashShortfall").asText());
            assertTrue(java.util.stream.StreamSupport.stream(observation.at("/issuerCredit/ownerAccounts").spliterator(),false)
                    .anyMatch(row -> "20.00".equals(row.path("buckets").path(CASH).asText())));
            assertNotEquals("OK",bank(w,BankActions.Kind.DEPOSIT,"21.00","",""));
            var hold=order(w,WALLET,Side.BUY,"9.00",5,null);
            assertEquals(values("75.00","45.00","100.00","20.00"),wallet(w));cancel(w,hold.orderId());
            assertEquals(values("120.00","0.00","100.00","20.00"),wallet(w));
            order(w,FOUNDER,Side.SELL,"10.00",5,null);
            order(w,WALLET,Side.BUY,"10.00",5,null);
            assertEquals(values("70.00","0.00","50.00","20.00"),wallet(w));
            hold=order(w,WALLET,Side.BUY,"9.00",7,null);
            assertEquals(values("7.00","63.00","50.00","7.00"),wallet(w));
            assertNotEquals("OK",bank(w,BankActions.Kind.DEPOSIT,"8.00","",""));
            cancel(w,hold.orderId());
            assertEquals("OK",bank(w,BankActions.Kind.DEPOSIT,"20.00","",""));
            assertEquals(values("50.00","0.00","50.00","0.00"),wallet(w));
            w.advanceTo(6*DAY+OPEN);
            assertEquals(values("50.00","0.00","50.00","0.00"),wallet(w));weekend=w.snapshot().position();
            assertNotEquals("OK",bank(w,BankActions.Kind.DEPOSIT,"0.01","",""));
        }
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            assertEquals(values("120.00","0.00","100.00","20.00"),w.cashSettlementAt(WALLET,friday));
            assertEquals(values("50.00","0.00","50.00","0.00"),w.cashSettlementsAt(weekend).get(WALLET));
            long at=7*DAY;
            w.openRoleSession("investor").bankAction("on-monday","on-monday",at,3,
                    new WorldRoleSession.BankActionDraft("monday-deposit",BankActions.Kind.DEPOSIT,"north","",
                            new BigDecimal("10.00"),0,0,"","","",""));
            w.advanceTo(at-1);
            assertEquals(values("50.00","0.00","50.00","0.00"),wallet(w));
            w.advanceTo(at);
            assertEquals("OK",w.taskCommitResult(WorldRuntime.roleTaskId("investor","on-monday")+":finish"));
            assertEquals(values("40.00","0.00","0.00","40.00"),wallet(w));
            assertEquals("OK",bank(w,BankActions.Kind.DEPOSIT,"40.00","",""));
            assertEquals(values("0.00","0.00","0.00","0.00"),wallet(w));
        }
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {assertEquals(values("0.00","0.00","0.00","0.00"),wallet(w));}
    }

    @Test void separateCustodyClaimCannotBeWiredOutOrOffsetByOwnCashBuy() throws Exception {
        String custody="CUSTODY|cst|investor";
        var file=dir.resolve("custody.journal");
        prepareFriday(file,custody);
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            assertEquals(values("100.00","0.00","100.00","0.00"),w.bucketSettlementAt(custody,w.snapshot().position()));
            assertEquals(values("20.00","0.00","0.00","20.00"),wallet(w));
            assertNotEquals("OK",bank(w,BankActions.Kind.PAY,"1.00",custody,CASH));
            order(w,FOUNDER,Side.SELL,"10.00",2,null);order(w,WALLET,Side.BUY,"10.00",2,null);
            assertEquals(values("100.00","0.00","100.00","0.00"),w.bucketSettlementAt(custody,w.snapshot().position()));
            order(w,FOUNDER,Side.SELL,"10.00",2,null);order(w,WALLET,Side.BUY,"10.00",2,custody);
            assertEquals(values("80.00","0.00","80.00","0.00"),w.bucketSettlementAt(custody,w.snapshot().position()));
            w.advanceTo(7*DAY);
            assertEquals("OK",bank(w,BankActions.Kind.PAY,"80.00",custody,CASH));
            assertEquals(values("80.00","0.00","0.00","80.00"),wallet(w));
        }
    }

    @Test void bankDepositSaleRestrictsOnlyThatDepositAndSameBucketBuyConsumesActualFill() throws Exception {
        String deposit="DEPOSIT|north|investor";
        var file=dir.resolve("deposit.journal");prepareFriday(file,deposit);
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            var before=w.bucketSettlementAt(deposit,w.snapshot().position());
            assertEquals(new BigDecimal("100.00"),before.unsettledSaleCash());
            assertEquals(before.tradingAvailable().subtract(new BigDecimal("100.00")),before.withdrawableCash());
            assertNotEquals("OK",bank(w,BankActions.Kind.WITHDRAW,before.withdrawableCash().add(new BigDecimal("0.01")).toPlainString(),"",""));
            order(w,FOUNDER,Side.SELL,"9.50",3,null);
            var partial = order(w,WALLET,Side.BUY,"10.00",5,deposit);
            var after=w.bucketSettlementAt(deposit,w.snapshot().position());
            assertEquals(new BigDecimal("71.50"),after.unsettledSaleCash());
            assertEquals(new BigDecimal("20.00"),after.orderFrozen());
            assertEquals(before.withdrawableCash(),after.withdrawableCash());
            assertEquals(values("20.00","0.00","0.00","20.00"),wallet(w));
            w.advanceTo(7*DAY);
            assertEquals(after.tradingAvailable().add(new BigDecimal("20.00")),w.bucketSettlementAt(deposit,w.snapshot().position()).withdrawableCash());
            assertFalse(w.snapshot().exchange().orElseThrow().orders().stream().filter(o -> o.orderId() == partial.orderId()).findFirst().orElseThrow().live());
        }
    }

    @Test void fundSubscriptionRequiresWithdrawableCustomerCashAndCanProceedAfterSettlement() throws Exception {
        try (var w=ComposedIndustryScenario.openOpenFundOperating(dir.resolve("open-fund"))) {
            prepareFriday(w,CASH);
            var before=w.snapshot();
            assertThrows(IllegalArgumentException.class,()->w.requestFundUnits("investor","too-much",OpenFundUnits.Kind.SUBSCRIBE,
                    new BigDecimal("21.00"),before.position()));
            assertEquals(before,w.snapshot());
            w.requestFundUnits("investor","within",OpenFundUnits.Kind.SUBSCRIBE,new BigDecimal("20.00"),before.position());
            assertEquals(values("100.00","0.00","100.00","0.00"),wallet(w));
            var clearing = w.roleView("bob").accounts().stream().filter(a -> a.accountId().equals(OpenFundUnits.CLEARING)).findFirst().orElseThrow();
            assertEquals(new BigDecimal("20.00"),clearing.cash());
            assertEquals(values("0.00","0.00","0.00","0.00"),clearing.settlement());
            assertEquals(clearing.settlement(),w.cashSettlementAt(OpenFundUnits.CLEARING,w.snapshot().position()));
            w.advanceTo(7*DAY);
            w.requestFundUnits("investor","after",OpenFundUnits.Kind.SUBSCRIBE,new BigDecimal("100.00"),w.snapshot().position());
            assertEquals(values("0.00","0.00","0.00","0.00"),wallet(w));
        }
    }

    @Test void approvedPrimaryOfferCannotTakeUnsettledCashFromTheNamedSubscriber() throws Exception {
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(dir.resolve("primary"))) {
            prepareFriday(w,CASH);
            edu.cufe.auction.world.composed.ResourceCessationTest.vote(w,"issue",BoardModel.Topic.RESOURCE_PRIMARY_OFFER,4);
            var request=new ResourceOperations.Request(ResourceOperations.Action.SUBSCRIBE_SHARES,java.util.Map.of("meetingId","issue","shares","3"));
            for (long at : new long[]{w.snapshot().timeMillis()+2000,7*DAY+2000}) {
                String id="subscribe-"+at;
                w.openRoleSession("investor").resourceAction(id,id+"-work",at,"resources",request,1000);
                w.advanceTo(at);
                var report=w.operationsRun().orElseThrow().report(id+"-work:finish");
                if (at<7*DAY) {
                    assertFalse(report.accepted());assertEquals(IntegratedResourcesModel.INSUFFICIENT_CASH,report.reason());
                    assertEquals(values("120.00","0.00","100.00","20.00"),wallet(w));
                } else {
                    assertTrue(report.accepted(),report.toString());assertEquals(values("90.00","0.00","0.00","90.00"),wallet(w));
                }
            }
        }
    }
}
