package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.Fund;
import edu.cufe.auction.world.bank.FundState;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static edu.cufe.auction.world.PureCompanyTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 可用资金端口替身只检验冻结边界；真实银行结算另由最终世界测试覆盖。 */
class AgedFundBridgeTest {
    static FundPayer limited(String source,String target,String frozen) {
        var delegate=payer(source,target);
        return new FundPayer() {
            public Fund source(){return delegate.source();} public Fund target(){return delegate.target();}
            public BigDecimal available(FundState f){return delegate.available(f).subtract(money(frozen)).max(money("0.00"));}
            public Result pay(String id,BigDecimal amount,FundState f){return amount.compareTo(available(f))>0 ? new Result(false,"FROZEN_CASH",f):delegate.pay(id,amount,f);}
            public boolean hasRecord(String id,FundState f){return delegate.hasRecord(id,f);}
            public boolean hasAcceptedRecord(String id,FundState f){return delegate.hasAcceptedRecord(id,f);}
        };
    }
    @Test void fixedExpenseAccruesInFullButOnlyAvailableCashIsPaid() {
        var funds=funds(AgedIndustryConfig.accounts());var run=AgedCompanyRun.open(AgedIndustryConfig.runConfig(),funds);
        var result=AgedOperations.apply(run,funds,limited(AgedIndustryConfig.COMPANY_ACCOUNT,AgedIndustryConfig.FIXED_ACCOUNT,"19995.00"),AgedIndustryConfig.PERIOD_MILLIS,"close-0",new AgedOperations.Request(AgedOperations.Action.CLOSE_PERIOD,Map.of()));
        assertTrue(result.accepted(),result.reason());assertEquals(money("19995.00"),result.funds().cash(AgedIndustryConfig.COMPANY_ACCOUNT));
        assertEquals(money("100.00"),result.state().fixedAccrued());assertEquals(money("95.00"),result.state().fixedPayable());
        assertEquals(money("5.00"),result.funds().cash(AgedIndustryConfig.FIXED_ACCOUNT));
        assertNull(AgedInventoryModel.validate(result.state(),result.funds().accounts()));
    }
    @Test void refusedRawPaymentDoesNotCreateInventoryAndUnreceivedOrderExpiresWithTrace() {
        var funds=funds(AgedIndustryConfig.accounts());var run=AgedCompanyRun.open(AgedIndustryConfig.runConfig(),funds);
        var order=AgedOperations.apply(run,funds,0,new AgedOperations.Request(AgedOperations.Action.ORDER_RAW,Map.of("orderId","raw","units","50")));
        var ordered=run.withLedger(order.state());
        var denied=AgedOperations.apply(ordered,funds,limited(AgedIndustryConfig.COMPANY_ACCOUNT,AgedIndustryConfig.SUPPLIER_ACCOUNT,"19501.00"),AgedIndustryConfig.PERIOD_MILLIS,"receive",new AgedOperations.Request(AgedOperations.Action.RECEIVE_RAW,Map.of("orderId","raw")));
        assertFalse(denied.accepted());assertEquals("FROZEN_CASH",denied.reason());assertEquals(ordered.ledger(),denied.state());assertEquals(funds,denied.funds());
        var close0=AgedOperations.apply(ordered,funds,limited(AgedIndustryConfig.COMPANY_ACCOUNT,AgedIndustryConfig.FIXED_ACCOUNT,"0.00"),AgedIndustryConfig.PERIOD_MILLIS,"close0",new AgedOperations.Request(AgedOperations.Action.CLOSE_PERIOD,Map.of()));
        var close1=AgedOperations.apply(run.withLedger(close0.state()),close0.funds(),limited(AgedIndustryConfig.COMPANY_ACCOUNT,AgedIndustryConfig.FIXED_ACCOUNT,"0.00"),2*AgedIndustryConfig.PERIOD_MILLIS,"close1",new AgedOperations.Request(AgedOperations.Action.CLOSE_PERIOD,Map.of()));
        assertTrue(close1.accepted(),close1.reason());assertTrue(close1.state().orders().isEmpty());assertTrue(close1.state().rawLots().isEmpty());
        assertTrue(close1.state().reports().stream().anyMatch(r->r.action().equals("EXPIRED_UNRECEIVED")&&r.refId().equals("raw")));
        var reuse=AgedOperations.apply(run.withLedger(close1.state()),close1.funds(),2*AgedIndustryConfig.PERIOD_MILLIS,new AgedOperations.Request(AgedOperations.Action.ORDER_RAW,Map.of("orderId","raw","units","50")));
        assertFalse(reuse.accepted());assertEquals(AgedInventoryModel.ID_REUSED,reuse.reason());
        assertNull(AgedInventoryModel.validate(close1.state(),close1.funds().accounts()));
    }
}
