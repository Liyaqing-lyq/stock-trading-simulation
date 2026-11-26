package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.bank.*;
import edu.cufe.auction.world.composed.ClientCompanyReport;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static edu.cufe.auction.world.bank.BankCommand.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class BankCrisisAccountingTest {
    private static final String RUN = "crisis-independent-exp3", PROVIDER = BankRules.PROVIDER;
    private final BankConfig config = BankConfig.experimentalExp3();
    private BankState bank;
    private Map<String,CashLedger.CashAccount> cash;
    private long clock;

    private void open(boolean crisis) {
        var parties = new ArrayList<OpeningRegistry.Party>();
        var owners = new ArrayList<OpeningRegistry.PropertyOwner>();
        for (String id : List.of("BANK-A","BANK-B","BANK-C","CUST-X","CUST-Y","ALICE","PAYER","BIG",
                "BG-DEP","CO-C","CO-D","CO-E","AGG","SUPPLIER",PROVIDER,BankChart.SUBORDINATED_OWNER)) {
            parties.add(new OpeningRegistry.Party(id,OpeningRegistry.PartyKind.ORGANIZATION,id));
            owners.add(new OpeningRegistry.PropertyOwner(id,OpeningRegistry.OwnerKind.ORGANIZATION,id));
        }
        var registry = new OpeningRegistry(parties,owners,List.of(),List.of());
        var opening = crisis ? BankOpening.resolution850(registry,RUN,config) : BankOpening.standard(registry,RUN,config);
        bank=opening.state(); cash=opening.accounts();
    }
    private BankModel.Outcome call(BankCommand.Kind kind,String actor,Map<String,String> body,CashFreeze freeze) {
        clock++;
        var result=BankModel.apply(bank,cash,config,new BankContext(RUN,config.fingerprint(),clock,clock),
                new BankCommand("step-"+clock,kind,body,actor),freeze);
        bank=result.state(); cash=result.accounts();
        return result;
    }
    private void ok(BankCommand.Kind kind,String actor,Map<String,String> body) {
        var result=call(kind,actor,body,CashFreeze.none()); assertTrue(result.accepted(),result.reason());
    }
    private void restore() {
        var restored=BankPersistence.readState(BankPersistence.writeState(bank,config),config,cash,RUN);
        assertEquals(BankPersistence.digestState(bank,config),BankPersistence.digestState(restored,config)); bank=restored;
    }
    private BigDecimal money(String key) { return cash.get(config.chart().cashAccountOf(key)).cash(); }
    private void eq(String expected,BigDecimal actual) { assertEquals(new BigDecimal(expected).setScale(2), actual); }

    @Test void assetSaleAtHalfFaceAndCashWaterfallPreserveBothDebtorsAfterRestore() {
        open(true);
        String c="BANK-C", debtor="CO-C", buyer="BIG", saver="BG-DEP", authority=BankRules.RESOLUTION_AUTHORITY;
        var pre=cash; String purchase="offer", claim="sold";
        eq("100",bank.bookEquity(c));
        ok(PURCHASE_ACCEPT,buyer,Map.of("seller",c,"subjectType",BankRules.SUBJECT_LOAN,
                "subject",config.chart().loanKeyOf(c,debtor),"price","400","expiryMs","1000","purchase",purchase));
        ok(LOAN_ASSIGN,c,Map.of("bank",c,"borrower",debtor,"buyer",buyer,"price","400","assignment",claim,"purchase",purchase));
        eq("400",pre.get(config.chart().cashAccountOf(buyer)).cash().subtract(money(buyer)));
        eq("450",money(c)); eq("800",bank.assignedClaim(claim).nominal()); eq("800",bank.outstandingDebtOf(debtor));
        var unentitled=call(RESOLUTION_OPEN,c,Map.of("bank",c,"authority",c),CashFreeze.none());
        assertFalse(unentitled.accepted()); eq("450",money(c));
        ok(RESOLUTION_OPEN,authority,Map.of("bank",c,"authority",authority));
        ok(RESOLUTION_DISTRIBUTE,authority,Map.of("bank",c));
        eq("0",money(c)); eq("400",money(saver)); eq("600",money(PROVIDER)); eq("0",money(BankChart.SUBORDINATED_OWNER));
        eq("-200",bank.gl(c,BankChart.FACILITY_DEBT)); eq("-100",bank.gl(c,"SUBT2")); eq("-300",bank.bookEquity(c));
        assertFalse(call(RESOLUTION_DISCHARGE,authority,Map.of("bank",c,"consents","absent"),CashFreeze.none()).accepted());
        restore();
        eq("800",bank.outstandingDebtOf(debtor)); eq("200",bank.facilityAccount(c,"F-C-1").nominal());
        eq("5600",money(buyer)); eq("-300",bank.bookEquity(c));
        var reporting=BankRun.of(config,bank,CustodyBook.openEmpty(bank,cash,config));
        var debtorReport=ClientCompanyReport.of(reporting,cash,debtor);
        eq("800",debtorReport.loanPrincipal());
        assertEquals(buyer,debtorReport.assignedClaims().get(0).holder());
        var buyerReport=ClientCompanyReport.of(reporting,cash,buyer);
        eq("400",buyerReport.assignedClaims().get(0).remainingCost());
        eq("6000",buyerReport.netFinancialAssets());
        assertNull(debtorReport.assignedClaims().get(0).remainingCost());
        var lenderConsent=Map.of("bank",c,"account",BankChart.FACILITY_DEBT,"amount","200","expiryMs","1000","consent","lender-consent");
        var subConsent=Map.of("bank",c,"account","SUBT2","amount","100","expiryMs","1000","consent","sub-consent");
        assertFalse(call(DISCHARGE_CONSENT,c,lenderConsent,CashFreeze.none()).accepted());
        ok(DISCHARGE_CONSENT,PROVIDER,lenderConsent);
        ok(RESOLUTION_DISCHARGE,authority,Map.of("bank",c,"consents","lender-consent"));
        eq("0",bank.facilityAccount(c,"F-C-1").nominal());
        eq("-100",bank.gl(c,"SUBT2")); eq("-100",bank.bookEquity(c));
        assertFalse(call(RESOLUTION_DISCHARGE,authority,Map.of("bank",c,"consents","lender-consent"),CashFreeze.none()).accepted());
        ok(DISCHARGE_CONSENT,BankChart.SUBORDINATED_OWNER,subConsent);
        ok(RESOLUTION_DISCHARGE,authority,Map.of("bank",c,"consents","sub-consent"));
        restore();
        eq("0",bank.gl(c,"SUBT2")); eq("0",bank.facilityAccount(c,"F-C-1").nominal());
        eq("0",bank.bookEquity(c)); eq("600",money(PROVIDER)); eq("400",money(saver));
        eq("800",bank.outstandingDebtOf(debtor));
    }

    @Test void facilityCannotExceedProviderCashAndWriteOffCannotEraseDebt() {
        open(false); String c="BANK-A", auth="credit"; BigDecimal reserve=money(c), initial=money(PROVIDER);
        var offer=Map.of("bank",c,"provider",PROVIDER,"authorization",auth,"limit","1000","rate","0",
                "loanTenorMs","10000","authorizationExpiryMs","9000","facilityMaturityMs","20000","collateralPolicy",BankRules.COLLATERAL_UNSECURED);
        assertFalse(call(FACILITY_AUTHORIZE,c,offer,CashFreeze.none()).accepted());
        ok(FACILITY_AUTHORIZE,PROVIDER,offer);
        var draw=Map.of("bank",c,"provider",PROVIDER,"authorization",auth,"amount","200");
        var tooLarge=new HashMap<>(draw);
        tooLarge.put("amount","900");
        var denied=call(FACILITY_DRAW,c,tooLarge,CashFreeze.none());
        assertFalse(denied.accepted(),"提供人原账只有800，不能提款900："+denied.reason());
        assertEquals(initial,money(PROVIDER)); assertEquals(reserve,money(c)); eq("0",bank.facilityAccount(c,auth).nominal());
        ok(FACILITY_DRAW,c,draw);
        eq("200",money(c).subtract(reserve)); eq("200",initial.subtract(money(PROVIDER)));
        var loss=Map.of("provider",PROVIDER,"authorization",auth,"amount","80");
        ok(FACILITY_IMPAIR,PROVIDER,loss); ok(FACILITY_WRITE_OFF,PROVIDER,loss);
        var account=bank.facilityAccount(c,auth);
        eq("200",account.nominal()); eq("120",account.onbook()); eq("80",account.writtenOff());
        eq("-200",bank.gl(c,BankChart.FACILITY_DEBT));
        eq("200",money(c).subtract(reserve)); eq("200",initial.subtract(money(PROVIDER)));
        restore(); eq("200",bank.facilityAccount(c,auth).nominal());
    }
}
