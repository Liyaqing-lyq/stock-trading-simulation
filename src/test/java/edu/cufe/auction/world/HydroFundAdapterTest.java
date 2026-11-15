package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.Fund;
import edu.cufe.auction.world.bank.FundState;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static edu.cufe.auction.world.PureCompanyTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class HydroFundAdapterTest {
    static final long HOUR = 3_600_000L;
    static HydroCompanyRun.Config config() {
        return new HydroCompanyRun.Config("hydro", "HY1", new HydroModel.Config("hydro", "CO", "BUY", "OPS",
                new HydroModel.PhysicalSpec(100, new BigDecimal("0.10"), 600, 100, 5000, 2000, 60, 0),
                new HydroModel.InflowSeries(List.of(new HydroModel.InflowSegment(0, 48 * HOUR, 600))),
                new HydroModel.SalesContract(500, money("1.00"), 48 * HOUR),
                new HydroModel.OperatingTerms(2000, money("3650.00"), 365)), HOUR, money("30.00"));
    }
    static FundState opening() { return funds(Map.of("CO", account("hydro", "100.00"),
            "BUY", account("grid", "10.00"), "OPS", account("ops", "0.00"))); }
    static HydroOperations.Request request(HydroOperations.Action action, String... fields) {
        var args = new TreeMap<String,String>(); for(int i=0;i<fields.length;i+=2) args.put(fields[i], fields[i+1]);
        return new HydroOperations.Request(action,args);
    }
    static FundPayer limited(String source, String target, String frozen) {
        var delegate = payer(source,target);
        return new FundPayer() {
            public Fund source() {return delegate.source();} public Fund target(){return delegate.target();}
            public Result pay(String id, BigDecimal amount, FundState f) {
                assertTrue(amount.compareTo(available(f)) <= 0, "cannot spend frozen cash");
                return delegate.pay(id,amount,f);
            }
            public boolean hasRecord(String id,FundState f){return delegate.hasRecord(id,f);}
            public boolean hasAcceptedRecord(String id,FundState f){return delegate.hasAcceptedRecord(id,f);}
            public BigDecimal available(FundState f){return delegate.available(f).subtract(money(frozen)).max(money("0.00"));}
        };
    }
    @Test void physicsAndExpenseContinueWhileFrozenCashLeavesRealPayable() {
        var funds = opening(); var run = HydroCompanyRun.open(config(),funds);
        var dispatch = HydroOperations.apply(run,funds,null,0,"dispatch",request(HydroOperations.Action.DISPATCH_FLOW,"cubicMetresPerHour","600"));
        assertTrue(dispatch.accepted());
        var settled = HydroOperations.apply(dispatch.run(),funds,limited("CO","OPS","95.00"),HOUR,"hour-1",request(HydroOperations.Action.SETTLE));
        assertTrue(settled.accepted()); assertEquals(money("5.00"),settled.moved());
        var report = HydroModel.report(settled.run().ledger(),settled.funds().accounts());
        assertEquals(money("95.00"),report.companyCash()); assertEquals(money("15.00"),report.payable());
        assertEquals(money("20.00"),report.operatingCost()); assertEquals(600,report.inflowCubicMetres());
        assertEquals(60,report.mandatoryReleaseCubicMetres()); assertTrue(report.revenue().signum()>0);
        assertTrue(report.balanced());
    }
    @Test void buyerFrozenCashLimitsActualCollectionWithoutErasingReceivable() {
        var funds=opening(); var run=HydroCompanyRun.open(config(),funds);
        var dispatch=HydroOperations.apply(run,funds,null,0,"dispatch",request(HydroOperations.Action.DISPATCH_FLOW,"cubicMetresPerHour","600"));
        var settle=HydroOperations.apply(dispatch.run(),funds,payer("CO","OPS"),HOUR,"hour-1",request(HydroOperations.Action.SETTLE));
        var before=HydroModel.report(settle.run().ledger(),settle.funds().accounts());
        var collected=HydroOperations.apply(settle.run(),settle.funds(),limited("BUY","CO","7.00"),HOUR,"collect",request(HydroOperations.Action.COLLECT,"amount","100.00"));
        assertTrue(collected.accepted()); assertEquals(money("3.00"),collected.moved());
        var after=HydroModel.report(collected.run().ledger(),collected.funds().accounts());
        assertEquals(before.receivable().subtract(money("3.00")),after.receivable());
        assertEquals(before.revenue(),after.revenue()); assertEquals(money("7.00"),collected.funds().accounts().get("BUY").cash());
        assertTrue(after.balanced());
    }
    @Test void maintenanceCannotSpendFrozenCashOrAcceptRoleSuppliedCost() throws Exception {
        var funds=opening(); var run=HydroCompanyRun.open(config(),funds);
        var applied=HydroOperations.apply(run,funds,limited("CO","OPS","95.00"),0,"maint",request(HydroOperations.Action.MAINTENANCE));
        assertFalse(applied.accepted()); assertEquals(run,applied.run()); assertEquals(funds,applied.funds());
        assertThrows(IllegalArgumentException.class,()-> request(HydroOperations.Action.MAINTENANCE,"cost","0.01"));
        assertThrows(IllegalArgumentException.class,()-> request(HydroOperations.Action.DISPATCH_FLOW,"cubicMetresPerHour","1.5"));
        assertThrows(IllegalArgumentException.class,()-> request(HydroOperations.Action.COLLECT,"amount","1e9"));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(run,json.readValue(json.writeValueAsString(run),HydroCompanyRun.class));
    }
}
