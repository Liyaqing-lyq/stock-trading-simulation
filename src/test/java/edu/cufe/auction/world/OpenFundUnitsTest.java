package edu.cufe.auction.world;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.OpenFundUnits.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenFundUnitsTest {
    @TempDir Path dir;
    static BigDecimal n(String s) { return new BigDecimal(s); }
    static void eq(String expected, Object actual) {
        assertNotNull(actual);
        assertEquals(0, n(expected).compareTo(new BigDecimal(actual.toString())), () -> expected + " != " + actual);
    }
    static void balance(WorldRuntime w, String person, String field, String expected) { eq(expected,w.openFundView(person).get(field)); }
    static long request(WorldRuntime w,String person,String id,Kind kind,String amount) throws Exception {
        return w.requestFundUnits(person,id,kind,n(amount),w.snapshot().position());
    }
    static BigDecimal allCash(WorldRuntime w) { return w.snapshot().accounts().values().stream().map(CashLedger.CashAccount::cash).reduce(BigDecimal.ZERO,BigDecimal::add); }

    @Test void twoHoldersAndManagerSeeOnlyTheirClaimsAndRealCashSettlesOnce() throws Exception {
        var path=dir.resolve("units.journal");
        long end, confirmation;
        BigDecimal total, investorCash, omarCash, productCash, initial;
        Map<String,Object> restored;
        try (var w=ComposedIndustryScenario.openOpenFundOperating(path)) {
            assertTrue(w.hasOpenFund());
            assertTrue(w.openFundView("alice").isEmpty());
            assertTrue((boolean)w.openFundView("bob").get("manager"));
            assertFalse(w.openFundView("bob").containsKey("ownUnits"));
            assertFalse(w.openFundView("nina").containsKey("settlementCash"));
            initial=w.valuation(PRODUCT).netWorth();
            eq("1",w.openFundView("nina").get("unitNav"));
            eq(initial.toPlainString(),w.valuation(FOUNDER).netWorth());
            investorCash=w.snapshot().accounts().get("HOUSE-NINA").cash();
            omarCash=w.snapshot().accounts().get("HOUSE-OMAR").cash();
            productCash=w.snapshot().accounts().get(OPERATING).cash();
            total=allCash(w);
            assertEquals(0,w.snapshot().accounts().get(CLEARING).cash().signum());
            BigDecimal ninaWorth=w.valuation("nina").netWorth();
            // 公开排程接口允许自定编号；一个已用的同名普通任务不得使份额申请漏掉确认。
            long nextCut=nextCutoff(w.snapshot().timeMillis());
            w.scheduleOrder("ordinary-same-id","open-fund-cycle:"+nextCut,w.snapshot().timeMillis()+1,
                    "investor","INV","AGED",Side.BUY,OrderType.LIMIT,n("1.00"),1);
            long start=w.snapshot().position();
            long pos=request(w,"nina","buy",Kind.SUBSCRIBE,"120");
            assertEquals(pos,w.requestFundUnits("nina","buy",Kind.SUBSCRIBE,n("120.00"),start));
            assertThrows(IllegalArgumentException.class,()->request(w,"nina","buy",Kind.SUBSCRIBE,"121"));
            assertThrows(IllegalStateException.class,()->w.requestFundUnits("omar","stale",Kind.SUBSCRIBE,n("1"),start));
            request(w,"omar","private-omar",Kind.SUBSCRIBE,"80");
            assertFalse(w.openFundView("nina").toString().contains("private-omar"));
            assertEquals(ninaWorth,w.valuation("nina").netWorth());
            assertEquals(initial,w.valuation(PRODUCT).netWorth());
            balance(w,"bob","totalPendingSubscriptions","200");
            assertEquals(productCash,w.snapshot().accounts().get(OPERATING).cash());
            assertEquals(investorCash.subtract(n("120")),w.snapshot().accounts().get("HOUSE-NINA").cash());
            var restricted=w.roleView("bob").accounts().stream().filter(a->a.accountId().equals(CLEARING)).findFirst().orElseThrow();
            eq("0",restricted.availableCash()); eq("200",restricted.frozenCash());
            long now=w.snapshot().timeMillis(), due=OpenFundUnits.nextCutoff(now);
            assertThrows(IllegalArgumentException.class,()->w.scheduleTransfer("drain","drain",now+1,PRODUCT,CLEARING,OPERATING,n("1"),1));
            assertThrows(IllegalArgumentException.class,()->w.scheduleOrder("buy-clear","buy-clear",now+1,PRODUCT,CLEARING,"HY1",Side.BUY,OrderType.LIMIT,n("10.00"),1));
            assertThrows(IllegalArgumentException.class,()->request(w,"bob","no-holder",Kind.SUBSCRIBE,"1"));
            assertThrows(IllegalArgumentException.class,()->request(w,"nina","too-many",Kind.REDEEM,"1"));
            assertThrows(IllegalArgumentException.class,()->w.cancelFundUnits("nina","private-omar",w.snapshot().position()));
            request(w,"nina","cancel",Kind.SUBSCRIBE,"15");
            long cancel=w.cancelFundUnits("nina","cancel",w.snapshot().position());
            assertEquals(cancel,w.cancelFundUnits("nina","cancel",start));
            w.advanceTo(due);
            balance(w,"nina","ownUnits","120"); balance(w,"omar","ownUnits","80");
            assertEquals(productCash.add(n("200")),w.snapshot().accounts().get(OPERATING).cash());
            assertEquals(initial.add(n("200")),w.valuation(PRODUCT).netWorth());
            assertEquals(ninaWorth,w.valuation("nina").netWorth());
            assertEquals(2,w.capitalFlows(PRODUCT).size());
            assertTrue(w.capitalFlows("nina").isEmpty());
            eq("0",w.performance(PRODUCT,start,w.snapshot().position()).returnRate());
            eq("0",w.performance("nina",start,w.snapshot().position()).returnRate());
            request(w,"nina","red",Kind.REDEEM,"50");
            balance(w,"nina","availableUnits","70");
            assertThrows(IllegalArgumentException.class,()->request(w,"nina","overlap",Kind.REDEEM,"71"));
            long close2=OpenFundUnits.nextCutoff(due);
            w.advanceTo(close2);
            confirmation=w.snapshot().position();
            balance(w,"nina","ownUnits","70"); balance(w,"nina","receivable","50");
            balance(w,"bob","settlementCash","50");
            assertEquals(ninaWorth,w.valuation("nina").netWorth());
            assertEquals(n("50.00"),w.valuation("nina").fundClaims().redemptionReceivable());
            assertThrows(IllegalStateException.class,()->w.cancelFundUnits("nina","red",confirmation));
            assertEquals(total,allCash(w));
        }
        try (var w=ComposedIndustryScenario.openOpenFundOperating(path)) {
            assertEquals(confirmation,w.snapshot().position());
            balance(w,"nina","receivable","50");
            long pay=(long)w.openFundView("nina").get("nextPaymentMillis");
            w.advanceTo(pay-1);
            balance(w,"nina","receivable","50");
            w.advanceTo(pay); w.advanceTo(pay+1);
            balance(w,"nina","ownUnits","70"); balance(w,"nina","receivable","0");
            assertEquals(investorCash.subtract(n("70")),w.snapshot().accounts().get("HOUSE-NINA").cash());
            assertEquals(omarCash.subtract(n("80")),w.snapshot().accounts().get("HOUSE-OMAR").cash());
            assertEquals(productCash.add(n("150")),w.snapshot().accounts().get(OPERATING).cash());
            assertEquals(total,allCash(w));
            assertEquals(3,w.capitalFlows(PRODUCT).size());
            restored=w.openFundView("nina");end=w.snapshot().position();
            System.out.printf("fund-closed issuedOpening=%s cashTotal=%s openingOperating=%s finalOperating=%s ninaUnits=%s omarUnits=%s capitalFlows=%d reopensAt=%d%n",
                    initial,total,productCash,w.snapshot().accounts().get(OPERATING).cash(),restored.get("ownUnits"),
                    w.openFundView("omar").get("ownUnits"),w.capitalFlows(PRODUCT).size(),end);
        }
        try (var w=ComposedIndustryScenario.openOpenFundOperating(path)) {
            assertEquals(end,w.snapshot().position());
            assertEquals(restored,w.openFundView("nina"));
        }
    }

    @Test void liveQuoteAtCutoffPricesNewSharesAndBuyReservationCutsSubscriberSpendableCash() throws Exception {
        var path=dir.resolve("quoted.journal");
        try (var w=ComposedIndustryScenario.openOpenFundOperating(path)) {
            var registry=w.openingRegistry().orElseThrow();
            var position=w.valuation(PRODUCT);
            var lot=registry.allocations().stream().filter(l->l.accountId().equals("HOUSE-NINA") && l.classification()==OpeningRegistry.ShareClass.FLOAT
                    && position.lines().stream().anyMatch(h->h.symbol().equals(l.symbol()))).findFirst().orElseThrow();
            var quote=registry.securities().stream().filter(sec->sec.symbol().equals(lot.symbol())).findFirst().orElseThrow().initialReferencePrice();
            var higher=quote.multiply(new BigDecimal("1.05")).setScale(2,java.math.RoundingMode.HALF_EVEN);
            var lower=quote.multiply(new BigDecimal("0.96")).setScale(2,java.math.RoundingMode.HALF_EVEN);
            String symbol=lot.symbol(); long at=MarketSessions.OPEN;
            w.scheduleOrder("check-frozen","check-frozen",++at,"nina","HOUSE-NINA",symbol,Side.BUY,OrderType.LIMIT,lower,1);
            w.advanceTo(at);
            var role=w.roleView("nina").accounts().stream().filter(x->x.accountId().equals("HOUSE-NINA")).findFirst().orElseThrow();
            eq(lower.toPlainString(),role.frozenCash()); long before=w.snapshot().position();
            assertThrows(IllegalArgumentException.class,()->request(w,"nina","blocked",Kind.SUBSCRIBE,role.cash().toPlainString()));
            assertEquals(before,w.snapshot().position());
            request(w,"omar","before-trade",Kind.SUBSCRIBE,"100");
            w.scheduleOrder("offer","offer",++at,"nina","HOUSE-NINA",symbol,Side.SELL,OrderType.LIMIT,higher,1);
            w.scheduleOrder("cross","cross",++at,"investor","INV",symbol,Side.BUY,OrderType.LIMIT,higher,1);
            w.advanceTo(at);
            var nav=w.valuation(PRODUCT).netWorth();
            assertTrue(nav.compareTo(position.netWorth())>0);
            var quantity=n("100").multiply(position.netWorth()).divide(nav,4,java.math.RoundingMode.DOWN);
            w.advanceTo(nextCutoff(at));
            eq(quantity.toPlainString(),w.openFundView("omar").get("ownUnits"));
            assertTrue(quantity.compareTo(n("100"))<0);
            var paid=w.capitalFlows(PRODUCT).get(0).amount();
            assertEquals(nav.add(paid),w.valuation(PRODUCT).netWorth());
            System.out.printf("fund-market symbol=%s reference=%s trade=%s originalNAV=%s preConfirmNAV=%s paid=%s issued=%s%n",
                    symbol,quote,higher,position.netWorth(),nav,paid,quantity);
        }
    }

    @Test void registeredManagerDepositLimitsTwoRedemptionsAtSameCutoffAndPaymentSurvivesRestart() throws Exception {
        var path=dir.resolve("pro-rata.journal"); long payAt;
        try (var w=ComposedIndustryScenario.openOpenFundOperating(path)) {
            long close=nextCutoff(w.snapshot().timeMillis());
            request(w,"nina","paid-a",Kind.SUBSCRIBE,"100"); request(w,"omar","paid-b",Kind.SUBSCRIBE,"100");
            w.advanceTo(close);
            var cash=w.snapshot().accounts().get(OPERATING).cash();
            var draft=new WorldRoleSession.BankActionDraft("reserve-deposit", edu.cufe.auction.world.bank.BankActions.Kind.DEPOSIT,
                    "north",PRODUCT,cash.subtract(n("25")),0,0,"","CASH|"+OPERATING,"","");
            long finish=close+1001;
            w.openRoleSession("bob").bankAction("reserve-deposit","reserve-deposit-task",finish,1000,draft);
            w.advanceTo(finish);
            balance(w,"bob","operatingCash","25");
            assertEquals(cash.subtract(n("25")),w.valuation(PRODUCT).bankClaims().deposits());
            request(w,"nina","red-a",Kind.REDEEM,"80"); request(w,"omar","red-b",Kind.REDEEM,"20");
            request(w,"investor","new-cash",Kind.SUBSCRIBE,"300");
            w.advanceTo(nextCutoff(close));
            balance(w,"nina","pendingRedemption","60"); balance(w,"omar","pendingRedemption","15");
            balance(w,"nina","receivable","20"); balance(w,"omar","receivable","5");
            balance(w,"bob","operatingCash","300"); balance(w,"bob","settlementCash","25");
            balance(w,"nina","availableUnits","20"); balance(w,"omar","availableUnits","80");
            w.cancelFundUnits("nina","red-a",w.snapshot().position());
            balance(w,"nina","availableUnits","80");
            var row = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(w.openFundView("nina")).path("orders").get(1);
            assertEquals("red-a",row.path("id").asText());
            eq("20",row.path("confirmedUnits").asText()); eq("60",row.path("cancelledRemainder").asText());
            eq("20",row.path("receivable").asText()); eq("0",row.path("paidAmount").asText());
            assertTrue(row.at("/confirmations/0/paidAt").isNull());
            assertEquals(5,w.capitalFlows(PRODUCT).size());
            System.out.printf("fund-partial oldFree=25 requests=80,20 newSubscription=300 confirmed=%s,%s unfundedBeforeCancel=60,15 escrow=%s%n",
                    w.openFundView("nina").get("receivable"),w.openFundView("omar").get("receivable"),w.openFundView("bob").get("settlementCash"));
            payAt=(long)w.openFundView("nina").get("nextPaymentMillis");
        }
        try (var w=ComposedIndustryScenario.openOpenFundOperating(path)) {
            balance(w,"nina","receivable","20"); balance(w,"omar","receivable","5");
            w.advanceTo(payAt); balance(w,"nina","receivable","0"); balance(w,"omar","receivable","0");
            var paid = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(w.openFundView("nina")).path("orders").get(1);
            eq("20",paid.path("paidAmount").asText()); assertEquals(payAt,paid.at("/confirmations/0/paidAt").asLong());
            w.advanceTo(nextCutoff(payAt)); balance(w,"omar","receivable","15");
            balance(w,"omar","ownUnits","80"); balance(w,"bob","operatingCash","285");
            assertEquals(6,w.capitalFlows(PRODUCT).size());
        }
    }

    @Test void alteredConfirmationAtSameFrameChecksumIsRejectedAndInputFileStaysUntouched() throws Exception {
        var path=dir.resolve("changed.journal");
        try (var w=ComposedIndustryScenario.openOpenFundOperating(path)) {
            request(w,"nina","sub",Kind.SUBSCRIBE,"12.34");
            w.advanceTo(nextCutoff(w.snapshot().timeMillis()));
        }
        var attack=dir.resolve("forged.journal");
        boolean changed=false;
        try (var source=new WorldJournal(path,RunManifest.OPEN_FUND_RUN_ID);
             var destination=new WorldJournal(attack,RunManifest.OPEN_FUND_RUN_ID,0,true)) {
            for (var frame : source.commits()) {
                String result=frame.result();
                if ("FUND_CONFIRMED".equals(result)) { result="FUND_PAID"; changed=true; }
                destination.commit(frame.commandId(),frame.commandContent(),result,frame.events());
            }
        }
        assertTrue(changed);
        byte[] bad=Files.readAllBytes(attack);
        var rejection=assertThrows(java.io.IOException.class,()->ComposedIndustryScenario.openOpenFundOperating(attack));
        assertTrue(rejection.getMessage().contains("与原任务执行不一致：open-fund-cycle:"),rejection::getMessage);
        assertArrayEquals(bad,Files.readAllBytes(attack));
    }

    @Test void historicalFixedArchiveRejectsOpenModeWithoutWritingIt() throws Exception {
        var path=dir.resolve("fixed.journal");
        try (var w=ComposedIndustryScenario.openTenCompanyAutonomy(path)) {
            assertFalse(w.hasOpenFund()); assertTrue(w.openFundView("nina").isEmpty());
            assertFalse(w.snapshot().accounts().containsKey(CLEARING));
            assertThrows(IllegalStateException.class,()->request(w,"nina","x",Kind.SUBSCRIBE,"1"));
        }
        byte[] before=Files.readAllBytes(path);
        assertThrows(Exception.class,()->ComposedIndustryScenario.openOpenFundOperating(path));
        assertArrayEquals(before,Files.readAllBytes(path));
        try (var w=ComposedIndustryScenario.openTenCompanyAutonomy(path)) { assertFalse(w.hasOpenFund()); }
        assertArrayEquals(before,Files.readAllBytes(path));
    }

    @Test void modelAndCashLedgerUseTheSameBoundedDecimalBeforeExpandingExponent() {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var observed=mapper.createObjectNode();
        observed.putObject("engineering").putObject("openFund").put("holder",true).put("person","nina");
        for (String text : List.of("1E+999999999","+7","-8","9".repeat(21),"9".repeat(3000),"1.234","0.0000")) {
            var args=mapper.createObjectNode().put("requestId","bound").put("amount",text);
            assertThrows(IllegalArgumentException.class,()->OpenFundTools.command(observed,"nina","fund.subscribe",args),text);
            assertThrows(IllegalArgumentException.class,()->parseAmount(Kind.SUBSCRIBE,text),text);
        }
        var largest="99999999999999999999.9999";
        var accepted=OpenFundTools.command(observed,"nina","fund.redeem",mapper.createObjectNode().put("requestId","large").put("amount",largest));
        assertEquals(new BigDecimal(largest),accepted.amount());
        assertEquals(n("7.10"),parseAmount(Kind.SUBSCRIBE,"7.1000"));
        assertThrows(IllegalArgumentException.class,()->apply(Book.opening(n("30")),"e","nina","INV",Kind.SUBSCRIBE,new BigDecimal("1E+999999999"),0));
    }

    @Test void sameCutoffUsesOneNavAndProRataCashWithUnconfirmedRemainder() {
        var b=Book.opening(n("100"));
        for (String person : List.of("a","b")) b=apply(b,"s",person,person,Kind.SUBSCRIBE,n("100"),1).book();
        var c=confirm(b,MarketSessions.CLOSE,n("200"),n("5"));
        assertEquals(2,c.book().batches().get(0).fills().size());
        b=c.book(); eq("50",b.availableUnits("a")); eq("50",b.availableUnits("b"));
        for (String person : List.of("b","a")) b=apply(b,"r",person,person,Kind.REDEEM,n("30"),MarketSessions.CLOSE).book();
        b=apply(b,"later","c","c",Kind.SUBSCRIBE,n("1000"),MarketSessions.CLOSE).book();
        long two=nextCutoff(MarketSessions.CLOSE);
        var limited=confirm(b,two,n("400"),n("6"));
        b=limited.book(); eq("28.5",b.pending("a",Kind.REDEEM));eq("28.5",b.pending("b",Kind.REDEEM));
        eq("3",b.payable("a"));eq("3",b.payable("b"));eq("500",b.availableUnits("c"));
        var afterCancel=cancel(b,"a","r",two); eq("0",afterCancel.book().pending("a",Kind.REDEEM));
        eq("3",afterCancel.book().payable("a"));
        long payment=nextPayment(two);
        var paid=pay(afterCancel.book(),payment);eq("0",paid.book().payable(null));
        assertEquals(2,paid.legs().size());
        assertThrows(IllegalStateException.class,()->pay(paid.book(),payment));
        var missing=confirm(paid.book(),nextCutoff(two),null,n("100"));
        eq("28.5",missing.book().pending("b",Kind.REDEEM));
        assertTrue(missing.legs().isEmpty());
        eq("0",missing.book().restrictedCash());
        assertEquals(7*MarketSessions.DAY+MarketSessions.OPEN,nextPayment(4*MarketSessions.DAY+MarketSessions.CLOSE));
    }
}
