package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.bank.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 同一十公司实验内的出资专户与独立处置办公室。资本来自资源出资人原有禀赋。 */
final class BankCrisisOpening {
    private static final BigDecimal CAPITAL = new BigDecimal("1000.00");
    static final String PROVIDER_CASH = "BANK-RESCUE-CASH", AUTHORITY_CASH = "BANK-RESOLUTION-CASH";
    private BankCrisisOpening() { }

    static OpeningRegistry allocate(List<CashLedger.OpeningAccount> cash, OpeningRegistry registry) {
        var source = cash.stream().filter(a -> a.accountId().equals("RES-SPONSOR")).findFirst().orElseThrow();
        cash.set(cash.indexOf(source), new CashLedger.OpeningAccount(source.accountId(), source.ownerId(),
                source.cash().subtract(CAPITAL), source.source()+"；划出1000元作银行应急资金开局资本"));
        cash.add(new CashLedger.OpeningAccount(PROVIDER_CASH, BankCrisis.PROVIDER, CAPITAL, "EXP 从 RES-SPONSOR 开局拆入1000元"));
        cash.add(new CashLedger.OpeningAccount(AUTHORITY_CASH, BankCrisis.AUTHORITY, BigDecimal.ZERO.setScale(2), "EXP 处置办公室无自有资金"));
        var parties = new ArrayList<>(registry.parties()); var owners = new ArrayList<>(registry.owners());
        for (String id : List.of(BankCrisis.PROVIDER, BankCrisis.AUTHORITY)) {
            parties.add(new OpeningRegistry.Party(id, OpeningRegistry.PartyKind.ORGANIZATION,
                    BankCrisis.PROVIDER.equals(id) ? "实验应急融资出资机构" : "实验银行处置办公室"));
            owners.add(new OpeningRegistry.PropertyOwner(id, OpeningRegistry.OwnerKind.ORGANIZATION,id));
        }
        return new OpeningRegistry(parties,owners,registry.securities(),registry.allocations());
    }

    static BankOpening.DeclaredEndowment endowment() {
        var original = ComposedWorldScenario.endowment();
        var capital = new BankState.ProviderOpening(BankCrisis.PROVIDER,CAPITAL,BigDecimal.ZERO,CAPITAL,"RES-SPONSOR出资");
        return new BankOpening.DeclaredEndowment(original.banks(),original.loans(),original.deposits(),
                original.facilities(),original.collaterals(),original.origins(),List.of(capital),original.source());
    }
}
