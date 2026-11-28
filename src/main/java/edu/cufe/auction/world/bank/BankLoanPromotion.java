package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 已披露最终借款人的历史贷款。实验开局冻结原合同、实际提款期次和来源档摘要；
 * 操作者在提升时只能选择已登记的合同，不能另填借款人、金额或减值。
 * @param contract 已经借款人接受、银行批准并实际提款的原合同
 * @param draw 与原合同核对的历史提款期次
 * @param originDigest 来源世界证据的 SHA-256
 */
public record BankLoanPromotion(BankActions.Loan contract, BankState.Tranche draw, String originDigest) {
    /** 核对来源合同与提款，尚未签署或从未实际提款的申请不能成为贷款。 */
    public BankLoanPromotion {
        Objects.requireNonNull(contract);
        Objects.requireNonNull(draw);
        if (originDigest == null || !originDigest.matches("[a-f0-9]{64}")
                || !contract.accepted() || contract.approvedBy() == null || contract.approvedBy().isBlank()
                || contract.status() != BankActions.LoanStatus.DRAWN
                || !draw.trancheId().equals(contract.trancheId()) || draw.terms() == null
                || draw.terms().rateU() != contract.rateU()
                || draw.terms().maturityMs() - draw.terms().startMs() != contract.tenorMs()
                || contract.drawn().signum() <= 0 || draw.contract().compareTo(contract.drawn()) != 0) {
            throw new IllegalArgumentException("DISCLOSED_DRAW_MISMATCH");
        }
    }

    /** 供新实验开局入池的非现金期次，原来源、批准人及借款人保留在冻结的证据中。 */
    public BankState.Tranche openingTranche() {
        return new BankState.Tranche(draw.trancheId(), source(), draw.contract(), draw.onbook(),
                draw.writtenOff(), draw.terms(), draw.nonAccrualReason());
    }

    private String source() {
        var fields = List.of(originDigest, contract.bankKey(), contract.borrowerKey(), contract.loanId(),
                contract.trancheId(), contract.approvedBy());
        var identity = new StringBuilder();
        for (String field : fields) identity.append(field.length()).append(':').append(field);
        return "DISCLOSED:" + BankPersistence.digestText(identity.toString());
    }

    /** 实际债务人名下的合同期次，汇总或明细只能有一份，期次的利息和核销追索均保留。 */
    public BankState.Tranche obligation(BankState state) {
        String bank = contract.bankKey();
        String pool = state.chart().backgroundBorrowerKey();
        if (pool == null || pool.equals(contract.borrowerKey())) throw new IllegalArgumentException("DISCLOSED_POOL_MISSING");
        var pooled = state.loan(bank, pool).tranche(contract.trancheId());
        var direct = state.loan(bank, contract.borrowerKey()).tranche(contract.trancheId());
        if ((pooled == null) == (direct == null)) throw new IllegalArgumentException("DISCLOSED_CLAIM_NOT_UNIQUE");
        var current = pooled != null ? pooled : direct;
        if (!source().equals(current.source())) throw new IllegalArgumentException("DISCLOSED_ORIGIN_MISMATCH");
        return current;
    }

    /**
     * 在同一银行内将专属汇总桶的一份合同转入原借款人的明细。候选通过银行不变量后返回；
     * 已提升的同一合同返回原状态。利息应收为银行级科目，其总额和原计息分子保持不动。
     * @param state 同一世界的当前银行三态中的银行账
     * @param accounts 同一确认时点的现金账，仅供校验
     * @return 同一次转分类后的完整银行账
     */
    public BankState promote(BankState state, Map<String, CashLedger.CashAccount> accounts) {
        var current = obligation(state);
        var chart = state.chart();
        String bank = contract.bankKey(), borrower = contract.borrowerKey(), pool = chart.backgroundBorrowerKey();
        if (state.loan(bank, pool).tranche(current.trancheId()) == null) return state;
        String poolGl = chart.bucketFor(pool), detailGl = chart.bucketFor(borrower);
        String poolLlp = chart.llpFor(pool), detailLlp = chart.llpFor(borrower);
        var allowance = state.gl(bank, poolLlp);
        if (state.loan(bank, pool).tranches().size() != 1
                || chart.profile().buckets().values().stream().filter(b -> b.loanGl().equals(poolGl)
                    || b.llpGl().equals(poolLlp)).count() != 1
                || state.gl(bank, poolGl).compareTo(current.onbook()) != 0
                || allowance.signum() > 0 || allowance.negate().compareTo(current.onbook()) > 0) {
            throw new IllegalArgumentException("DISCLOSED_POOL_NOT_SEPARABLE");
        }
        var next = state.draft();
        var details = new ArrayList<>(state.loan(bank, borrower).tranches());
        details.add(current);
        next.putLoan(bank, pool, new BankState.Loan(List.of()));
        next.putLoan(bank, borrower, new BankState.Loan(details));
        next.setGl(bank, poolGl, Money.zero());
        next.setGl(bank, detailGl, state.gl(bank, detailGl).add(current.onbook()));
        next.setGl(bank, poolLlp, Money.zero());
        next.setGl(bank, detailLlp, state.gl(bank, detailLlp).add(allowance));
        var result = next.freeze();
        var problems = BankInvariants.check(result, accounts);
        if (!problems.isEmpty()) throw new IllegalArgumentException("DISCLOSED_MIGRATION_INVALID:" + problems.get(0));
        return result;
    }
}
