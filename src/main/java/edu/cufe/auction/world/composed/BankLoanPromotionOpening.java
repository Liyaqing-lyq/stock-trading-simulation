package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.bank.*;
import java.io.IOException;
import java.math.BigDecimal;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** 独立实验开局。原档的四次已确认回执和合同收录在 disclosed-loan-v1.json。 */
public final class BankLoanPromotionOpening {
    /** 专属的外部历史贷款汇总户。 */
    public static final String POOL = "historic-credit-pool";
    private static final String ACCOUNT = "HISTORIC-CREDIT-POOL", GL = "book-historic-credit", LLP = "impair-historic-credit";
    private record Evidence(BankLoanPromotion certificate, BankState.DepositAccount deposit, long position) { }
    private static class Source { private static final Evidence VALUE = load(); }
    private BankLoanPromotionOpening() { }

    private static Evidence load() {
        var mapper = new ObjectMapper();
        try (var input = BankLoanPromotionOpening.class.getResourceAsStream("/world/disclosed-loan-v1.json")) {
            if (input == null) throw new IllegalStateException("DISCLOSED_OPENING_MISSING");
            var evidence = mapper.readTree(input);
            var cert = new BankLoanPromotion(mapper.treeToValue(evidence.required("contract"), BankActions.Loan.class),
                    mapper.treeToValue(evidence.required("draw"), BankState.Tranche.class), evidence.required("originDigest").textValue());
            var savings = mapper.treeToValue(evidence.required("depositTerms"), BankState.DepositAccount.class);
            var receipts = evidence.required("acceptedReceipts");
            var kinds = List.of("LOAN_REQUEST","LOAN_ACCEPT","LOAN_APPROVE","LOAN_DRAW");
            long last = 0;
            if (receipts.size() != kinds.size()) throw new IllegalStateException("DISCLOSED_RECEIPTS_MISSING");
            for (int i=0; i<kinds.size(); i++) {
                var r = receipts.get(i);
                if (!r.path("accepted").asBoolean() || !kinds.get(i).equals(r.path("kind").asText())
                        || !cert.contract().borrowerKey().equals(r.path("customerKey").asText())
                        || !cert.contract().bankKey().equals(r.path("bankKey").asText())
                        || r.path("position").asLong() <= last)
                    throw new IllegalStateException("DISCLOSED_RECEIPT_MISMATCH");
                last = r.path("position").asLong();
            }
            if (last != evidence.required("sourcePosition").longValue()
                    || !cert.contract().approvedBy().equals(receipts.get(2).path("personPartyId").asText())
                    || cert.draw().terms().startMs() != receipts.get(3).path("atMillis").longValue()
                    || savings.startMs() != cert.draw().terms().startMs())
                throw new IllegalStateException("DISCLOSED_DRAW_RECEIPT_MISMATCH");
            return new Evidence(cert,savings,last);
        } catch (IOException e) { throw new IllegalStateException("DISCLOSED_OPENING_INVALID", e); }
    }

    /** 原贷款的银行、债务人、原始期限和已确认提款凭据。 */
    public static BankLoanPromotion certificate() { return Source.VALUE.certificate(); }

    /** 来源世界最后一张已确认回执的位置。 */
    public static long sourcePosition() { return Source.VALUE.position(); }

    /** 贷款子账的经济债务人。旧档沿用银行账键，只识别本实验的专属汇总桶和来源期次。 */
    public static String beneficialBorrower(BankState state, String loanKey) {
        var chart = state.chart(); String borrower = chart.borrowerOfLoanKey(loanKey);
        if (!POOL.equals(borrower) || !POOL.equals(chart.backgroundBorrowerKey())) return borrower;
        if (state.loans().get(loanKey).tranches().isEmpty()) return null;
        if (!certificate().contract().bankKey().equals(chart.bankOfLoanKey(loanKey)))
            throw new IllegalStateException("DISCLOSED_BANK_MISMATCH");
        certificate().obligation(state);
        return certificate().contract().borrowerKey();
    }

    static BankingProfile profile(BankingProfile base) {
        var parties = new TreeMap<>(base.parties());
        parties.put(POOL, new BankingProfile.PartyRef(null, POOL, ACCOUNT, null, BankingProfile.PartyKind.AGGREGATE));
        var buckets = new TreeMap<>(base.buckets());
        buckets.put(POOL, new BankingProfile.RiskBucket(GL, buckets.get(certificate().contract().borrowerKey()).group(), GL, LLP));
        var loans = new ArrayList<>(base.loanAccounts()); loans.add(GL);
        var allowances = new ArrayList<>(base.llpAccounts()); allowances.add(LLP);
        return new BankingProfile(base.banks(), parties, base.custodians(), buckets, base.groups(), loans,
                allowances, base.depositAccounts(), base.providerKey(), base.subordinatedOwnerKey(), POOL);
    }

    static OpeningRegistry register(List<CashLedger.OpeningAccount> accounts, OpeningRegistry registry) {
        accounts.add(new CashLedger.OpeningAccount(ACCOUNT, POOL, BigDecimal.ZERO.setScale(2), "EXP 历史合同登记专户，无现金"));
        var owners = new ArrayList<>(registry.owners());
        owners.add(new OpeningRegistry.PropertyOwner(POOL, OpeningRegistry.OwnerKind.AGGREGATE, null));
        return new OpeningRegistry(registry.parties(), owners, registry.securities(), registry.allocations());
    }

    static BankOpening.DeclaredEndowment endowment(BankOpening.DeclaredEndowment base) {
        var contract = certificate().contract(); var draw = certificate().openingTranche(); var terms = draw.terms();
        var banks = new TreeMap<>(base.banks()); var old = banks.get(contract.bankKey());
        var gl = new TreeMap<>(old.gl());
        if (gl.putIfAbsent(GL, draw.onbook()) != null || gl.putIfAbsent("DEPOSIT-" + contract.borrowerKey(), draw.contract().negate()) != null)
            throw new IllegalStateException("DISCLOSED_OPENING_GL_OCCUPIED");
        banks.put(contract.bankKey(), new BankOpening.BankBalance(gl, old.capitalSource()));
        // 来源确认时点正是提款时点。实验自零开始，所有期限作相同平移。
        var shifted = new BankState.LoanTerms(terms.rateU(), 0, terms.maturityMs() - terms.startMs(), terms.numerator(),
                terms.postedCents(), terms.paidCents(), terms.lastAccruedMs() - terms.startMs(), terms.status(), terms.overdue());
        var pooled = new BankState.Tranche(draw.trancheId(), draw.source(), draw.contract(), draw.onbook(),
                draw.writtenOff(), shifted, draw.nonAccrualReason());
        var loans = new ArrayList<>(base.loans());
        loans.add(new BankOpening.LoanEndowment(contract.bankKey(), POOL, new BankState.Loan(List.of(pooled))));
        var deposits = new ArrayList<>(base.deposits());
        var oldSavings = Source.VALUE.deposit();
        long cut = terms.startMs();
        var savings = new BankState.DepositAccount(oldSavings.offerId(), oldSavings.rateU(),oldSavings.startMs()-cut,
                oldSavings.maturityMs()-cut,oldSavings.numerator(),oldSavings.postedCents(),oldSavings.paidCents(),
                oldSavings.lastAccruedMs()-cut,oldSavings.excludedCents(),oldSavings.excludedReason(),oldSavings.nonAccrualReason());
        deposits.add(new BankOpening.DepositEndowment(contract.bankKey(), contract.borrowerKey(), draw.contract(), savings));
        return new BankOpening.DeclaredEndowment(banks, loans, deposits, base.facilities(), base.collaterals(),
                base.origins(), base.providerOpenings(), base.source() + "/" + certificate().originDigest());
    }
}
