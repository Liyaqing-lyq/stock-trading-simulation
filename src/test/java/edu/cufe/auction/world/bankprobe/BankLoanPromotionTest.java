package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.bank.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankLoanPromotionTest {
    private static final String BANK = "BANK-A", POOL = "AGG", ISSUER = "CO-C";
    private static final long MONTH = 30L * 86_400_000;
    private final Map<String, CashLedger.CashAccount> cash = Map.of();

    private BankLoanPromotion certificate(String issuer) {
        var contract = new BankActions.Loan("working-capital", BANK, issuer, amount("200"), 20000, MONTH,
                "实验营运资金", BankActions.LoanStatus.DRAWN, true, "bank-manager", amount("200"), "L-1");
        var terms = new BankState.LoanTerms(20000, 0, MONTH, BigInteger.ZERO, 0, 0, 0, "CURRENT", false);
        var original = new BankState.Tranche("L-1", "LOAN", amount("200"), amount("200"), amount("0"), terms, null);
        return new BankLoanPromotion(contract, original, BankPersistence.digestText("测试夹具：已确认的提款原件"));
    }

    private static BigDecimal amount(String text) { return new BigDecimal(text).setScale(2); }

    private BankState pooled(BankLoanPromotion cert) {
        var draft = BankState.empty().draft();
        var original = cert.openingTranche();
        var n = InterestAccrual.add(BigInteger.ZERO, 20_000, 20_000, MONTH);
        var terms = new BankState.LoanTerms(20000, 0, MONTH, n, InterestAccrual.targetCents(n), 0,
                MONTH, "PAST_DUE", true);
        var overdue = new BankState.Tranche(original.trancheId(), original.source(), original.contract(),
                amount("180"), amount("20"), terms, null);
        draft.putLoan(BANK, POOL, new BankState.Loan(List.of(overdue)));
        draft.setGl(BANK, "LOAN.AGG", amount("180"));
        draft.setGl(BANK, "LLP.AGG", amount("-10"));
        draft.setGl(BANK, "DEPOSIT.C", amount("-200"));
        draft.setGl(BANK, BankChart.INTEREST_RECEIVABLE, overdue.interestReceivable());
        draft.setGl(BANK, "RETAINED", amount("30").subtract(overdue.interestReceivable()));
        return draft.freeze();
    }

    @Test void entireNamedTrancheChangesClassificationOnceAndKeepsPastDueInterestAndRecourse() {
        var cert = certificate(ISSUER);
        var original = pooled(cert);
        assertEquals(List.of(), BankInvariants.check(original, cash));
        var debt = cert.obligation(original);
        var after = cert.promote(original, cash);
        assertEquals(List.of(), BankInvariants.check(after, cash));
        assertEquals(debt, cert.obligation(after));
        assertTrue(debt.terms().overdue());
        assertEquals(amount("0.33"), debt.interestReceivable());
        assertEquals(amount("20"), debt.writtenOff());
        assertEquals(amount("0"), after.loan(BANK, POOL).contract());
        assertEquals(amount("200"), after.loan(BANK, ISSUER).contract());
        assertEquals(amount("0"), after.gl(BANK, "LLP.AGG"));
        assertEquals(amount("-10"), after.gl(BANK, "LLP.C"));
        assertEquals(amount("180"), after.gl(BANK, "LOAN.C"));
        assertEquals(original.depositOf(BANK, ISSUER), after.depositOf(BANK, ISSUER));
        assertEquals(original.backing(), after.backing());
        assertSame(after, cert.promote(after, cash));
    }

    @Test void wrongBorrowerAmbiguousPoolAndDuplicateDestinationCannotMoveAPenny() {
        var cert = certificate(ISSUER);
        var original = pooled(cert);
        assertThrows(IllegalArgumentException.class, () -> certificate("CO-D").promote(original, cash));
        var draft = original.draft();
        var own = cert.obligation(original);
        var other = new BankState.Tranche("L-2", "OTHER", amount("1"), amount("1"), amount("0"), null, "无息");
        draft.putLoan(BANK, POOL, new BankState.Loan(List.of(own, other)));
        var ambiguous = draft.freeze();
        assertThrows(IllegalArgumentException.class, () -> cert.promote(ambiguous, cash));
        draft = original.draft();
        draft.putLoan(BANK, ISSUER, new BankState.Loan(List.of(own)));
        var duplicate = draft.freeze();
        assertThrows(IllegalArgumentException.class, () -> cert.promote(duplicate, cash));
        assertEquals(amount("200"), original.loan(BANK, POOL).contract());
        assertEquals(amount("0"), original.loan(BANK, ISSUER).contract());
    }
}
