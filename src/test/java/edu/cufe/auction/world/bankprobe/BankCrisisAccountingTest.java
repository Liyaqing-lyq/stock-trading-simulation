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
    private BankConfig config = BankConfig.experimentalExp3();
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

    @Test void assetSaleAtHalfFaceAndCashWaterfallPreserveBothDebtorsAfterRestore() throws Exception {
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
        var saverReport=ClientCompanyReport.of(reporting,cash,saver);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        String oldReport=mapper.writeValueAsString(saverReport);
        assertFalse(oldReport.contains("assignedClaims"));
        assertEquals(saverReport,mapper.readValue(oldReport,ClientCompanyReport.class));
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

    @Test void rescueCanSpendOnlyUnencumberedProviderCash() {
        var base=BankWorldFixture.profile(false);
        String lender="firm", recipient=BankWorldFixture.BANK;
        var profile=new BankingProfile(base.banks(),base.parties(),base.custodians(),base.buckets(),base.groups(),
                base.loanAccounts(),base.llpAccounts(),base.depositAccounts(),lender,null,null);
        var tiers=List.of(List.of("dep-acme","dep-firm","INT.PAYABLE"),
                List.of(BankChart.FACILITY_DEBT,BankChart.FACILITY_INTEREST_PAYABLE),List.of("SUBT2"));
        config=new BankConfig.Builder(profile).version(24).coverageRule("DECLARED_UNIVERSE_V1")
                .facilityTerms(new BankConfig.FacilityTerms(BankRules.FACILITY_EXP,"outside",List.of(),tiers)).build();
        var funds=new LinkedHashMap<String,CashLedger.CashAccount>();
        for (var row : BankWorldFixture.openings()) {
            funds.put(row.accountId(),new CashLedger.CashAccount(row.ownerId(),row.cash(),row.source()));
        }
        var endowment=BankWorldFixture.endowment();
        var provider=new BankState.ProviderOpening(lender,funds.get("firm-cash").cash(),BigDecimal.ZERO,
                funds.get("firm-cash").cash(),"b5-opening-firm");
        var opening=BankOpening.openDeclared(BankWorldFixture.registry(),funds,profile,config,
                new BankOpening.DeclaredEndowment(endowment.banks(),endowment.loans(),endowment.deposits(),
                        endowment.facilities(),endowment.collaterals(),endowment.origins(),List.of(provider),endowment.source()),RUN);
        bank=opening.state(); cash=opening.accounts();
        ok(FACILITY_AUTHORIZE,lender,Map.of("bank",recipient,"provider",lender,"authorization","credit","limit","400",
                "rate","0","loanTenorMs","1000","authorizationExpiryMs","900","facilityMaturityMs","2000",
                "collateralPolicy",BankRules.COLLATERAL_UNSECURED));
        var frozen=new CashFreeze(Map.of(new Fund.Cash("firm-cash").token(),new BigDecimal("300")));
        var draw=new HashMap<>(Map.of("bank",recipient,"provider",lender,"authorization","credit","amount","200"));
        var failed=call(FACILITY_DRAW,recipient,draw,frozen);
        assertFalse(failed.accepted(),"已冻结300的400元账户不能出资200");
        assertEquals(Reasons.PAYER_BALANCE_INSUFFICIENT + ":CASH|firm-cash",failed.reason());
        eq("400",money(lender)); eq("500",cash.get("rsv-north").cash());
        eq("0",bank.facilityAccount(recipient,"credit").nominal());
        draw.put("amount","100");
        var funded=call(FACILITY_DRAW,recipient,draw,frozen);
        assertTrue(funded.accepted(),funded.reason());
        eq("300",money(lender)); eq("600",cash.get("rsv-north").cash());
        eq("100",bank.facilityAccount(recipient,"credit").nominal()); restore();
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
