package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.composed.BankLoanPromotionOpening;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** 只读估值缓存在每份不同的银行三态上重新派生，持有人的现金与市价仍以调用帧为准。 */
final class OwnerBankClaims {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private BankRun source;
    private BankRun.Decoded ledger;
    private final Map<String, ValuationModel.BankClaims> owners = new HashMap<>();

    ValuationModel.BankClaims at(BankRun bank, Map<String, CashLedger.CashAccount> accounts, String owner) {
        ledgerAt(bank, accounts);
        return owners.computeIfAbsent(owner, this::calculate);
    }

    BankRun.Decoded ledgerAt(BankRun bank, Map<String, CashLedger.CashAccount> accounts) {
        if (!bank.equals(source)) {
            ledger = bank.decode(accounts);
            source = bank;
            owners.clear();
        }
        return ledger;
    }

    private ValuationModel.BankClaims calculate(String owner) {
        var chart = ledger.state().chart();
        var profile = chart.profile();
        BigDecimal deposits = ZERO, interest = ZERO, beneficial = ZERO, debt = ZERO, dueInterest = ZERO;
        for (var party : profile.parties().entrySet()) {
            if (!owner.equals(party.getValue().ownerId())) continue;
            if (party.getValue().depositGl() != null) {
                for (String bank : chart.banks()) {
                    deposits = deposits.add(ledger.state().depositOf(bank, party.getKey()));
                    var account = ledger.state().depositAccount(bank, party.getKey());
                    if (account != null) interest = interest.add(account.interestPayable());
                }
            }
            for (String custodian : profile.custodians().keySet()) {
                beneficial = beneficial.add(ledger.book().detailOf(custodian, party.getKey()));
            }
        }
        for (var item : ledger.state().loans().entrySet()) {
            String borrower = BankLoanPromotionOpening.beneficialBorrower(ledger.state(), item.getKey());
            if (borrower == null) continue;
            var party = profile.parties().get(borrower);
            if (party != null && owner.equals(party.ownerId())) {
                debt = debt.add(item.getValue().contract());
                dueInterest = dueInterest.add(item.getValue().interestReceivable());
            }
        }
        return new ValuationModel.BankClaims(deposits, interest, beneficial, debt, dueInterest);
    }
}
