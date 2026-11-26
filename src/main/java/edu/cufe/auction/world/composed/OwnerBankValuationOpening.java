package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.bank.BankRules;
import edu.cufe.auction.world.bank.BankingProfile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** 独立估值档的零余额个人、产品存款与非计息客户托管池。 */
final class OwnerBankValuationOpening {
    private OwnerBankValuationOpening() { }

    static BankingProfile profile(BankingProfile original, String copiedInvestor) {
        var parties = new TreeMap<>(original.parties());
        var deposits = new ArrayList<>(original.depositAccounts());
        for (String key : List.of("investor", "fund-a", copiedInvestor)) {
            var old = parties.get(key);
            String gl = "dep-personal-" + key;
            parties.put(key, new BankingProfile.PartyRef(old.partyId(), old.ownerId(), old.cashAccountId(), gl, old.ownerKind()));
            deposits.add(gl);
        }
        var custodians = new TreeMap<>(original.custodians());
        custodians.put(ComposedCustodyScenario.CST, new BankingProfile.CustodyRef(ComposedCustodyScenario.CST_ORG,
                ComposedCustodyScenario.CST_OWNER, ComposedWorldScenario.BANK, ComposedCustodyScenario.POOL, BankRules.CREDIT_NON_ACCRUAL));
        deposits.add(ComposedCustodyScenario.POOL);
        var buckets = new TreeMap<>(original.buckets());
        buckets.put("investor", new BankingProfile.RiskBucket("book-household-investor", "household-investor",
                "book-household-investor", "impair-household-investor"));
        var loans = new ArrayList<>(original.loanAccounts()); loans.add("book-household-investor");
        var allowances = new ArrayList<>(original.llpAccounts()); allowances.add("impair-household-investor");
        return new BankingProfile(original.banks(), parties, custodians, buckets, original.groups(), loans, allowances,
                deposits, original.providerKey(), original.subordinatedOwnerKey(), original.backgroundBorrowerKey());
    }

    static OpeningRegistry register(List<CashLedger.OpeningAccount> accounts, OpeningRegistry registry) {
        var parties = new ArrayList<>(registry.parties());
        var owners = new ArrayList<>(registry.owners());
        parties.add(new OpeningRegistry.Party(ComposedCustodyScenario.CST_ORG, OpeningRegistry.PartyKind.ORGANIZATION, "客户资金托管人"));
        owners.add(new OpeningRegistry.PropertyOwner(ComposedCustodyScenario.CST_OWNER, OpeningRegistry.OwnerKind.ORGANIZATION, ComposedCustodyScenario.CST_ORG));
        accounts.add(new CashLedger.OpeningAccount("CST-CASH", ComposedCustodyScenario.CST_OWNER, BigDecimal.ZERO.setScale(2), "托管人自有现金零余额开立"));
        return new OpeningRegistry(parties, owners, registry.securities(), registry.allocations());
    }
}
