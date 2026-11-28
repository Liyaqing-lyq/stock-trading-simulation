package edu.cufe.auction.world.composed;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.bank.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.bank.BankActions.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class IssuerResolutionMarketTest {
    @TempDir Path dir;
    static final Path DRAFT = Path.of("src/main/resources/world/opening-copies-example.json");
    static final long OPEN = 570*60_000L;

    @Test void activeResolutionFreezesOnlyItsSecuritiesInTheSameCommitAndKeepsClaims() throws Exception {
        exercise(true);
    }
    @Test void earlierBankProfileKeepsHistoricalExchangeTreatment() throws Exception {
        exercise(false);
    }
    private void exercise(boolean linked) throws Exception {
        Path file = dir.resolve("bank.journal");
        WorldRuntime.Snapshot end;
        try (var w = linked ? ComposedIndustryScenario.openIssuerResolutionOperating(file, DRAFT)
                : ComposedIndustryScenario.openOwnerBankValuationOperating(file, DRAFT)) {
            if (linked) {
                assertNotNull(w.resolutionStatus("BNK1").rule());
                assertNull(w.resolutionStatus("BNK1").openedAtMillis());
                assertNull(w.resolutionStatus("HY1"));
                assertNotNull(w.roleView("nina").market().stream().filter(m->m.symbol().equals("HY1")).findFirst().orElseThrow().reference().price());
            }
            String officer = IssuerCreditDecisionTest.BORROWER;
            IssuerCreditDecisionTest.appoint(w, "mfg-tech", officer);
            IssuerCreditDecisionTest.appoint(w, "north", "erin");
            assertEquals("RESOLUTION_TRIGGER_ABSENT", bank(w,"kate",BankCrisis.AUTHORITY,RESOLUTION_OPEN,"0"));
            for (var kind : List.of(LOAN_REQUEST,LOAN_ACCEPT,LOAN_APPROVE,LOAN_DRAW))
                assertEquals("OK",bank(w,kind == LOAN_APPROVE ? "erin" : officer, "mfg-tech",kind,"4000"));
            assertEquals("OK",bank(w,officer,"mfg-tech",WITHDRAW,"3000"));
            assertEquals("0.00", w.snapshot().accounts().get("RSV-NORTH").cash().toPlainString());
            w.advanceTo(OPEN);
            String seller=w.snapshot().securities().orElseThrow().lots().stream()
                    .filter(l->l.symbol().equals("BNK1") && l.classification()==OpeningRegistry.ShareClass.FLOAT)
                    .findFirst().orElseThrow().accountId();
            String bidder=seller.equals("INV") ? "HOUSE-NINA" : "INV";
            var sell=order(w,seller,"BNK1",Side.SELL,"16.00");
            var buy=order(w,bidder,"BNK1",Side.BUY,"14.00");
            var other=order(w,bidder,"HY1",Side.BUY,"9.50");
            assertTrue(sell.accepted() && buy.accepted() && other.accepted());
            var before=w.snapshot();
            var b=state(w);
            var deposits=b.depositOf("north","mfg-tech");
            var debt=b.outstandingDebtOf("mfg-tech");
            var mandate=w.roleView("kate").appointments();
            assertFalse(mandate.isEmpty());
            assertThrows(IllegalArgumentException.class,()->bank(w,"erin",BankCrisis.AUTHORITY,RESOLUTION_OPEN,"0"));
            String inFlight="pending-credit";
            long finish=w.snapshot().timeMillis()+12;
            var request=new WorldRoleSession.BankActionDraft(inFlight,LOAN_REQUEST,"north","mfg-tech",BigDecimal.ONE,
                    20000,86_400_000L,"采购","","",inFlight);
            w.openRoleSession(officer).bankAction(inFlight,inFlight,finish,11,request);
            assertEquals("OK",bank(w,"kate",BankCrisis.AUTHORITY,RESOLUTION_OPEN,"0"));
            var after=w.snapshot();
            var ex=after.exchange().orElseThrow();
            assertEquals(before.accounts(), after.accounts());
            assertEquals(before.securities().orElseThrow().lots(),after.securities().orElseThrow().lots());
            assertEquals(deposits, state(w).depositOf("north","mfg-tech"));
            assertEquals(debt, state(w).outstandingDebtOf("mfg-tech"));
            assertEquals(200L, w.profile().registry().security("BNK1").issuedShares());
            assertEquals(200L, after.securities().orElseThrow().lots().stream().filter(l->l.symbol().equals("BNK1")).mapToLong(SecuritiesLedger.Lot::remaining).sum());
            assertTrue(ex.order(other.orderId()).live());
            assertEquals(!linked,ex.order(buy.orderId()).live());
            assertEquals(!linked,ex.order(sell.orderId()).live());
            if (linked) {
                assertEquals(new BigDecimal("9.50"),WorldExchange.frozenCash(ex,bidder));
                assertEquals(IssuerResolution.HALTED,w.resolutionStatus("BNK1").trading());
                assertEquals("REGISTERED",w.resolutionStatus("BNK1").listing());
                assertEquals("PENDING_RESOLUTION",w.resolutionStatus("BNK1").proceeding());
                assertTrue(w.autonomyRoleObservation("nina").toString().contains(IssuerResolution.HALTED));
                assertEquals(IssuerResolution.HALTED,order(w,bidder,"BNK1",Side.BUY,null).reason());
                assertEquals(IssuerResolution.HALTED,order(w,seller,"BNK1",Side.SELL,"15.00").reason());
                assertThrows(IllegalArgumentException.class,()->bank(w,officer,"mfg-tech",LOAN_REQUEST,"1"));
                assertTrue(order(w,bidder,"HY1",Side.BUY,"10.00").accepted());
            } else assertTrue(order(w,bidder,"BNK1",Side.BUY,"14.00").accepted());
            w.advanceTo(finish);
            if (linked) {
                assertEquals("ISSUER_RESOLUTION_NO_NEW_CREDIT",w.taskCommitResult(WorldRuntime.roleTaskId(officer,inFlight)+":finish"));
                assertNull(w.bankBook().loan(inFlight));
            }
            assertEquals("OK",bank(w,"kate",BankCrisis.AUTHORITY,RESOLUTION_DISTRIBUTE,"0"));
            assertEquals(deposits,state(w).depositOf("north","mfg-tech"));
            assertEquals(debt,state(w).outstandingDebtOf("mfg-tech"));
            assertTrue(w.snapshot().exchange().orElseThrow().trades().isEmpty());
            end=w.snapshot();
        }
        try (var w=linked ? ComposedIndustryScenario.openIssuerResolutionOperating(file,null)
                : ComposedIndustryScenario.openOwnerBankValuationOperating(file,null)) {
            assertEquals(end,w.snapshot());
        }
        if (!linked) return;
        var scan=WorldJournalScanner.scan(file);
        var halted=scan.commits().stream().filter(c->c.events().stream().anyMatch(e->e.type().equals("ISSUER_RESOLUTION_HALT_V1"))).toList();
        assertEquals(1,halted.size());
        assertTrue(halted.get(0).events().stream().anyMatch(e->e.type().equals("BANK_ACTION_V1")));
        Path forged=dir.resolve("lost-halt.journal");
        try(var writer=new WorldJournal(forged,scan.runId())) {
            for(var c:scan.commits()) writer.commit(c.commandId(),c.commandContent(),c.result(),
                    c.events().stream().filter(e->!e.type().equals("ISSUER_RESOLUTION_HALT_V1")).toList());
        }
        assertThrows(IOException.class,()->ComposedIndustryScenario.openIssuerResolutionOperating(forged,null));
    }
    private static BankState state(WorldRuntime w) {return w.bankOf().orElseThrow().decode(w.snapshot().accounts()).state();}
    private static String bank(WorldRuntime w,String who,String customer,BankActions.Kind kind,String amount) throws Exception {
        String id="bank-"+kind+"-"+w.snapshot().position();
        long due=w.snapshot().timeMillis()+3;
        var draft=new WorldRoleSession.BankActionDraft(id,kind,"north",customer,new BigDecimal(amount),20000,30L*86_400_000,
                "采购","","",kind==LOAN_REQUEST && amount.equals("1") ? "extra-credit" : "new-credit");
        w.openRoleSession(who).bankAction(id,id,due,1,draft);w.advanceTo(due);
        return w.taskCommitResult(WorldRuntime.roleTaskId(who,id)+":finish");
    }
    private static WorldExchange.OrderReport order(WorldRuntime w,String account,String symbol,Side side,String price) throws Exception {
        long due=w.snapshot().timeMillis()+1;
        String id="order-"+w.snapshot().position();
        w.scheduleOrder(id,id,due,w.snapshot().accounts().get(account).ownerId(),account,symbol,side,
                price==null?OrderType.MARKET:OrderType.LIMIT,price==null?null:new BigDecimal(price),1);
        w.advanceTo(due); return w.snapshot().exchange().orElseThrow().reports().get(id);
    }
}
