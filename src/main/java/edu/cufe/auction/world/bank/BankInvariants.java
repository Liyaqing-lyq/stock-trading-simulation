package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 每批末不变量：任何失败都应让候选整批不被接受。
 *
 * <p>贷款按期次核对（Σ期次 = 聚合 = 总账桶），存款按账户政策核对（应付 = Σposted−paid，
 * 非应计部分不超过余额），承诺额度与状态自洽。</p>
 */
public final class BankInvariants {
    private BankInvariants() {
    }

    /**
     * 检查一份候选状态与现金账户。
     *
     * @param state 银行状态
     * @param accounts 现金账户
     * @return 违反项（空表示通过）
     */
    public static List<String> check(BankState state, Map<String, CashLedger.CashAccount> accounts) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal>> entry : state.glByBank().entrySet()) {
            String bank = entry.getKey();
            BigDecimal sum = Money.zero();
            for (BigDecimal value : entry.getValue().values()) {
                sum = sum.add(value);
            }
            if (Money.scale2(sum).signum() != 0) {
                problems.add("GL_NOT_BALANCED:" + bank + ":" + Money.text(sum));
            }
            for (String loan : state.chart().loanAccounts()) {
                if (state.gl(bank, loan).signum() < 0) {
                    problems.add("NEGATIVE_LOAN:" + bank + ":" + loan);
                }
            }
            for (String llp : state.chart().contra()) {
                if (state.gl(bank, llp).signum() > 0) {
                    problems.add("POSITIVE_PROVISION:" + bank + ":" + llp);
                }
            }
            for (String deposit : state.chart().depositAccounts()) {
                if (state.gl(bank, deposit).signum() > 0) {
                    problems.add("POSITIVE_DEPOSIT:" + bank + ":" + deposit);
                }
            }
            BigDecimal reserveCash = reserve(state.chart(), accounts, bank);
            if (state.gl(bank, BankChart.RESERVE).compareTo(reserveCash) != 0) {
                problems.add("RESERVE_GL_MISMATCH:" + bank);
            }
            BankState.Facility facility = state.facility(bank);
            if (facility != null) {
                BigDecimal due = state.gl(bank, "BORROW.FAC").negate();
                if (Money.scale2(due).compareTo(facility.outstanding()) != 0) {
                    problems.add("FACILITY_MEMO_MISMATCH:" + bank);
                }
                if (!state.facilityAccountsOf(bank).isEmpty()) {
                    problems.add("FACILITY_MEMO_DOUBLE:" + bank);
                }
            }
            reconcileFacilities(state, bank, problems);
            if (state.gl(bank, BankChart.INTEREST_RECEIVABLE).signum() < 0) {
                problems.add("NEGATIVE_INTEREST_RECEIVABLE:" + bank);
            }
            if (state.gl(bank, BankChart.INTEREST_PAYABLE).signum() > 0) {
                problems.add("POSITIVE_INTEREST_PAYABLE:" + bank);
            }
            reconcileLoans(state, bank, problems);
            reconcileInterest(state, bank, problems);
        }
        for (Map.Entry<String, BankState.Loan> entry : state.loans().entrySet()) {
            BankState.Loan loan = entry.getValue();
            for (BankState.Tranche tranche : loan.tranches()) {
                if (tranche.onbook().add(tranche.writtenOff())
                        .compareTo(tranche.contract()) != 0) {
                    problems.add("TRANCHE_OBLIGATION_MISMATCH:" + entry.getKey() + ":"
                            + tranche.trancheId());
                }
                if (tranche.terms() != null && tranche.terms().postedCents()
                        != InterestAccrual.targetCents(tranche.terms().numerator())) {
                    problems.add("TRANCHE_POSTED_MISMATCH:" + entry.getKey() + ":"
                            + tranche.trancheId());
                }
            }
        }
        reconcileAssignments(state, problems);
        reconcileTransfers(state, problems);
        reconcileProviderCapitals(state, accounts, problems);
        for (Map.Entry<String, BankState.DepositAccount> entry : state.depositAccounts().entrySet()) {
            BankState.DepositAccount account = entry.getValue();
            if (account.postedCents() != InterestAccrual.targetCents(account.numerator())) {
                problems.add("DEPOSIT_POSTED_MISMATCH:" + entry.getKey());
            }
            if (account.accrual() && account.excludedCents() > 0) {
                String owner = state.chart().holderOfDepositTermsKey(entry.getKey());
                String bank = state.chart().bankOfDepositTermsKey(entry.getKey());
                long balance = InterestAccrual.cents(
                        state.gl(bank, state.chart().depositAccountOf(owner)).negate());
                if (account.excludedCents() > Math.max(0, balance)) {
                    problems.add("DEPOSIT_EXCLUDED_EXCEEDS_BALANCE:" + entry.getKey());
                }
            }
            if (account.nonAccrual()) {
                String owner = state.chart().holderOfDepositTermsKey(entry.getKey());
                String bank = state.chart().bankOfDepositTermsKey(entry.getKey());
                long balance = InterestAccrual.cents(
                        state.gl(bank, state.chart().depositAccountOf(owner)).negate());
                if (account.excludedCents() != Math.max(0, balance)) {
                    problems.add("NON_ACCRUAL_BALANCE_MISMATCH:" + entry.getKey());
                }
            }
        }
        for (Map.Entry<String, BankState.Commitment> entry : state.commitmentRecords().entrySet()) {
            BankState.Commitment commitment = entry.getValue();
            if (commitment.drawnCumulative().compareTo(commitment.limit()) > 0) {
                problems.add("COMMITMENT_OVERDRAWN:" + entry.getKey());
            }
            if (BankState.COMMITMENT_FULLY_DRAWN.equals(commitment.status())
                    && commitment.undrawn().signum() != 0) {
                problems.add("COMMITMENT_STATUS_MISMATCH:" + entry.getKey());
            }
        }
        for (CashLedger.CashAccount account : accounts.values()) {
            if (account.cash().signum() < 0) {
                problems.add("NEGATIVE_CASH:" + account.ownerId());
            }
        }
        if (!Settlement.balanced(state, accounts)) {
            problems.add("SETTLEMENT_BACKING_MISMATCH");
        }
        return problems;
    }

    private static void reconcileLoans(BankState state, String bank, List<String> problems) {
        Map<String, BigDecimal> byBucket = new TreeMap<>();
        String prefix = bank + "|";
        for (Map.Entry<String, BankState.Loan> entry : state.loans().entrySet()) {
            if (!bank.equals(state.chart().bankOfLoanKey(entry.getKey()))) {
                continue;
            }
            String borrower = state.chart().borrowerOfLoanKey(entry.getKey());
            if (borrower == null) {
                problems.add("UNKNOWN_LOAN_KEY:" + entry.getKey());
                continue;
            }
            String bucket = state.chart().bucketFor(borrower);
            if (bucket == null) {
                problems.add("UNKNOWN_BORROWER_KEY:" + entry.getKey());
                continue;
            }
            byBucket.merge(bucket, entry.getValue().onbook(), BigDecimal::add);
        }
        for (String bucket : state.chart().bucketValues()) {
            BigDecimal memo = Money.scale2(byBucket.getOrDefault(bucket, Money.zero()));
            if (memo.compareTo(state.gl(bank, bucket)) != 0) {
                problems.add("LOAN_GL_MISMATCH:" + bank + ":" + bucket);
            }
        }
    }

    /** 逐行核对：应计应收/应付只在期次/存款政策各存一份，总额必须等于对应总账科目。 */
    private static void reconcileInterest(BankState state, String bank, List<String> problems) {
        String prefix = bank + "|";
        BigDecimal receivable = Money.zero();
        for (Map.Entry<String, BankState.Loan> entry : state.loans().entrySet()) {
            if (!bank.equals(state.chart().bankOfLoanKey(entry.getKey()))) {
                continue;
            }
            if (entry.getValue().interestReceivable().signum() < 0) {
                problems.add("NEGATIVE_ACCRUED_INTEREST:" + entry.getKey());
            }
            receivable = receivable.add(entry.getValue().interestReceivable());
        }
        if (Money.scale2(receivable).compareTo(
                state.gl(bank, BankChart.INTEREST_RECEIVABLE)) != 0) {
            problems.add("INTEREST_RECEIVABLE_MISMATCH:" + bank + ":"
                    + Money.text(receivable) + "!="
                    + Money.text(state.gl(bank, BankChart.INTEREST_RECEIVABLE)));
        }
        BigDecimal payable = Money.zero();
        for (Map.Entry<String, BankState.DepositAccount> entry : state.depositAccounts().entrySet()) {
            if (!bank.equals(state.chart().bankOfDepositTermsKey(entry.getKey()))) {
                continue;
            }
            if (entry.getValue().interestPayable().signum() < 0) {
                problems.add("NEGATIVE_ACCRUED_PAYABLE:" + entry.getKey());
            }
            payable = payable.add(entry.getValue().interestPayable());
        }
        if (Money.scale2(payable).negate().compareTo(
                state.gl(bank, BankChart.INTEREST_PAYABLE)) != 0) {
            problems.add("INTEREST_PAYABLE_MISMATCH:" + bank + ":"
                    + Money.text(payable) + "!="
                    + Money.text(state.gl(bank, BankChart.INTEREST_PAYABLE).negate()));
        }
    }

    /** 逐行核对设施：名义 = Σ(BORROW.FAC)、应付利息 = Σ(posted−paid)、押品唯一且属本行。 */
    private static void reconcileFacilities(BankState state, String bank, List<String> problems) {
        BigDecimal nominal = Money.zero();
        BigDecimal payable = Money.zero();
        for (Map.Entry<String, BankState.FacilityAccount> entry
                : state.facilityAccountsOf(bank).entrySet()) {
            BankState.FacilityAccount account = entry.getValue();
            nominal = nominal.add(account.nominal());
            payable = payable.add(account.interestPayable());
            if (account.nominal().compareTo(account.drawnCumulative()) > 0) {
                problems.add("FACILITY_NOMINAL_OVER_DRAWN:" + bank + ":" + entry.getKey());
            }
            if (account.closed() && account.nominal().signum() != 0) {
                problems.add("FACILITY_CLOSED_WITH_DEBT:" + bank + ":" + entry.getKey());
            }
            for (String id : account.collateral()) {
                BankState.Collateral collateral = state.collateral(id);
                if (collateral == null) {
                    problems.add("COLLATERAL_MISSING:" + bank + ":" + id);
                } else if (!collateral.owner().equals(bank)) {
                    problems.add("COLLATERAL_FOREIGN:" + id);
                } else if (!state.chart().facilityKeyOf(bank, entry.getKey())
                        .equals(collateral.pledgedTo())) {
                    problems.add("COLLATERAL_PLEDGE_MISMATCH:" + id);
                } else if (!collateralBacked(state, collateral)) {
                    problems.add("COLLATERAL_UNBACKED:" + id);
                }
            }
        }
        BankState.Facility legacyFacility = state.facility(bank);
        if (legacyFacility != null) {
            nominal = nominal.add(legacyFacility.outstanding());
        }
        if (Money.scale2(nominal).compareTo(state.gl(bank, BankChart.FACILITY_DEBT).negate()) != 0) {
            problems.add("FACILITY_GL_MISMATCH:" + bank + ":" + Money.text(nominal));
        }
        if (Money.scale2(payable).compareTo(
                state.gl(bank, BankChart.FACILITY_INTEREST_PAYABLE).negate()) != 0) {
            problems.add("FACILITY_INTEREST_MISMATCH:" + bank + ":" + Money.text(payable));
        }
        for (BankState.Collateral collateral : state.collaterals().values()) {
            if (!BankRules.COLLATERAL_PLEDGED.equals(collateral.status())) {
                continue;
            }
            String pledgedTo = collateral.pledgedTo();
            String pledgeBank = state.chart().bankOfFacilityKey(pledgedTo);
            String pledgeId = state.chart().authorizationOfFacilityKey(pledgedTo);
            if (pledgeBank == null || pledgeId == null) {
                problems.add("COLLATERAL_PLEDGE_KEY_INVALID:" + collateral.collateralId());
                continue;
            }
            BankState.FacilityAccount account = state.facilityAccount(pledgeBank, pledgeId);
            if (account == null || !account.collateral().contains(collateral.collateralId())) {
                problems.add("COLLATERAL_PLEDGE_ORPHAN:" + collateral.collateralId());
            }
        }
    }

    /** 已处置资产必须有真实归属转移：贷款由 AssignedClaim 接管，不动产不得仍记在原所有人账上。 */
    private static void reconcileTransfers(BankState state, List<String> problems) {
        for (BankState.Collateral collateral : state.collaterals().values()) {
            if (!BankRules.COLLATERAL_DISPOSED.equals(collateral.status())) {
                continue;
            }
            BankState.AssetTransfer transfer = state.assetTransfer(collateral.assetKey());
            if (transfer == null) {
                problems.add(Reasons.TRANSFER_MISSING + ":" + collateral.collateralId());
                continue;
            }
            if (!transfer.fromOwner().equals(collateral.owner())) {
                problems.add("TRANSFER_OWNER_MISMATCH:" + collateral.collateralId());
            }
            if ("LOAN".equals(collateral.kind())) {
                BankState.AssignedClaim claim = state.assignedClaim(transfer.reference());
                if (claim == null) {
                    problems.add("TRANSFER_CLAIM_MISSING:" + collateral.collateralId());
                } else if (!claim.holder().equals(transfer.toOwner())
                        || !claim.borrower().equals(collateral.assetKey().substring(
                                collateral.assetKey().indexOf('|') + 1))) {
                    problems.add("TRANSFER_CLAIM_MISMATCH:" + collateral.collateralId());
                }
            } else if (state.gl(collateral.owner(), "PREMISES").signum() != 0) {
                problems.add("TRANSFER_DUAL_OWNERSHIP:" + collateral.assetKey());
            }
        }
    }

    /** 押品是否仍绑定真实资产：PREMISES 余额或该贷款 onbook 等于押品账面。 */
    private static boolean collateralBacked(BankState state, BankState.Collateral collateral) {
        if ("PREMISES".equals(collateral.kind())) {
            return state.gl(collateral.owner(), "PREMISES")
                    .compareTo(collateral.bookValue()) == 0;
        }
        int split = collateral.assetKey().indexOf('|');
        if (split <= 0) {
            return false;
        }
        String borrower = collateral.assetKey().substring(split + 1);
        BigDecimal onbook = state.loan(collateral.owner(), borrower).onbook();
        // 押品账面是该项贷款的“已抵押金额”：贷款可因新增提款变大，但不得小于已抵押金额或消失。
        return onbook.signum() > 0
                && onbook.compareTo(collateral.bookValue()) >= 0;
    }

    /** 提供者权益：冻结初始资本 + 已实现损益 必须等于当前净资产（漏记即失败）。 */
    private static void reconcileProviderCapitals(BankState state,
                                                  Map<String, CashLedger.CashAccount> accounts,
                                                  List<String> problems) {
        java.util.Set<String> providers = new java.util.TreeSet<>(
                state.providerOpenings().keySet());
        for (BankState.FacilityAccount facility : state.facilityAccounts().values()) {
            providers.add(facility.provider());
        }
        for (String provider : providers) {
            if (state.providerOpening(provider) == null) {
                problems.add(Reasons.PROVIDER_CAPITAL_MISSING + ":" + provider);
                continue;
            }
            FacilityBook.Position position = FacilityBook.position(state, accounts, provider);
            if (!position.reconciles()) {
                problems.add(Reasons.PROVIDER_CAPITAL_BROKEN + ":" + provider + ":"
                        + Money.text(position.equity()));
            }
        }
    }

    /** 逐行核对受让债权：唯一持有人、名义守恒、成本释放不超成本、同一期次不被银行再次持有。 */
    private static void reconcileAssignments(BankState state, List<String> problems) {
        for (BankState.AssignedClaim claim : state.assignedClaims().values()) {
            if (claim.costReleased().compareTo(claim.bookCost()) > 0) {
                problems.add("CLAIM_COST_OVER_RELEASED:" + claim.claimId());
            }
            if (claim.settled().compareTo(claim.originalNominal()) > 0) {
                problems.add("CLAIM_SETTLED_OVER_NOMINAL:" + claim.claimId());
            }
            if (claim.nominal().compareTo(claim.originalNominal()) > 0) {
                problems.add("CLAIM_OUTSTANDING_OVER_ORIGINAL:" + claim.claimId());
            }
            BigDecimal expectedSettled = Money.scale2(
                    claim.originalNominal().subtract(claim.nominal()));
            if (claim.settled().compareTo(expectedSettled) != 0) {
                problems.add("CLAIM_SETTLED_MISMATCH:" + claim.claimId());
            }
            long onbookCents = 0;
            long writtenCents = 0;
            for (BankState.Tranche tranche : claim.loan().tranches()) {
                onbookCents += InterestAccrual.cents(tranche.onbook());
                writtenCents += InterestAccrual.cents(tranche.writtenOff());
            }
            if (onbookCents + writtenCents != InterestAccrual.cents(claim.nominal())) {
                problems.add("CLAIM_PRINCIPAL_MISMATCH:" + claim.claimId());
            }
            BankState.Loan held = state.loans().get(
                    state.chart().loanKeyOf(claim.sellerBank(), claim.borrower()));
            if (held != null) {
                for (BankState.Tranche tranche : claim.loan().tranches()) {
                    if (held.tranche(tranche.trancheId()) != null) {
                        problems.add("ASSIGNED_LOAN_DOUBLE:" + claim.claimId());
                        break;
                    }
                }
            }
        }
    }

    private static BigDecimal reserve(BankChart chart,
                                      Map<String, CashLedger.CashAccount> accounts,
                                      String bank) {
        CashLedger.CashAccount account = accounts.get(chart.reserveAccountOf(bank));
        return account == null ? Money.zero() : account.cash();
    }

    /**

     * 银行状态。
     * @param state 银行状态
     * @param accounts 现金账户
     * @return 若不变量成立则为 true

     */
    public static boolean holds(BankState state, Map<String, CashLedger.CashAccount> accounts) {
        return check(state, accounts).isEmpty();
    }
}
