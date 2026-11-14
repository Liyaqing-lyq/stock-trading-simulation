package edu.cufe.auction.world.bank;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 银行债权/负债子账的不可变状态（现金不在本类，唯一权威是 CashLedger）。
 *
 * <p>总账符号：资产/费用为正，负债/权益/收入为负；每行 Σ 科目 = 0。
 * 贷款按 {@code bank|borrower} 绑定，内部分「期次」{@link Tranche}（各自 rate/起息/到期/分子），
 * 聚合 contract/onbook/writtenOff 由期次派生；存款按 {@code bank|owner} 保存 {@link DepositAccount}
 * （冻结 offer 或明确非应计）。准备金余额不在此保存；状态自带运行身份与档案指纹，apply 必须三者一致。</p>
 */
public final class BankState {

    /** 贷款条款状态：正常计息。 */
    public static final String CURRENT = "CURRENT";
    /** 贷款条款状态：已到期且有未偿本金或未付利息。 */
    public static final String PAST_DUE = "PAST_DUE";
    /** 贷款条款状态：本金与应计利息均已结清。 */
    public static final String SETTLED = "SETTLED";
    /** 承诺状态：未用额度可提。 */
    public static final String COMMITMENT_OPEN = "OPEN";
    /** 承诺状态：已提满（非循环，不再恢复）。 */
    public static final String COMMITMENT_FULLY_DRAWN = "FULLY_DRAWN";
    /** 承诺状态：已关闭。 */
    public static final String COMMITMENT_CLOSED = "CLOSED";

    /**
     * 贷款期次利息条款：开立时冻结，之后只能由累计/付息改变分子与已入账/已付。
     *
     * @param rateU         定标费率（1e6=100%，开立时冻结）
     * @param startMs       计息起点（该期次提款时点）
     * @param maturityMs    该期次合同到期时点
     * @param numerator     累计分子（美分×定标费率×毫秒）
     * @param postedCents   累计已入账应计利息（美分）
     * @param paidCents     累计已收利息（美分）
     * @param lastAccruedMs 已累计到的时点（不设 maturity 上界）
     * @param status        条款状态（CURRENT/PAST_DUE/SETTLED）
     * @param overdue       是否逾期（仅由到期判定置位）
     */
    public record LoanTerms(long rateU, long startMs, long maturityMs, BigInteger numerator,
                            long postedCents, long paidCents, long lastAccruedMs,
                            String status, boolean overdue) {

        /** 校验冻结条款的自洽性（posted 必须等于分子目标）。 */
        public LoanTerms {
            if (rateU < 0 || rateU > InterestAccrual.RATE_ONE) {
                throw new IllegalArgumentException(Reasons.RATE_INVALID + ":" + rateU);
            }
            if (startMs < 0 || maturityMs <= startMs) {
                throw new IllegalArgumentException(Reasons.MATURITY_INVALID);
            }
            Objects.requireNonNull(numerator, "计息分子");
            if (numerator.signum() < 0) {
                throw new IllegalArgumentException(Reasons.TIME_REVERSED);
            }
            if (postedCents < 0 || paidCents < 0 || paidCents > postedCents) {
                throw new IllegalArgumentException(Reasons.INTEREST_EXCEEDS_ACCRUED);
            }
            if (postedCents != InterestAccrual.targetCents(numerator)) {
                throw new IllegalArgumentException("INTEREST_POSTED_MISMATCH");
            }
            if (lastAccruedMs < startMs) {
                throw new IllegalArgumentException(Reasons.TIME_REVERSED);
            }
            if (status == null || !Set.of(CURRENT, PAST_DUE, SETTLED).contains(status)) {
                throw new IllegalArgumentException("LOAN_STATUS_INVALID:" + status);
            }
        }

        /**
         * 未收应计利息（posted−paid，派生视图）。
         *
         * @return 应计未收利息
         */
        public BigDecimal interestReceivable() {
            return InterestAccrual.money(postedCents - paidCents);
        }

        /**
         * 累计已收利息（派生视图）。
         *
         * @return 累计已收利息
         */
        public BigDecimal interestPaid() {
            return InterestAccrual.money(paidCents);
        }

        /**
         * 累计已入账应计利息（派生视图）。
         *
         * @return 累计已入账利息
         */
        public BigDecimal accruedInterest() {
            return InterestAccrual.money(postedCents);
        }

        /**
         * 追加一段累计结果。
         *
         * @param nextNumerator 新分子
         * @param nextPostedCents 新累计已入账
         * @param atMs 累计到的时点
         * @return 新条款
         */
        public LoanTerms withAccrual(BigInteger nextNumerator, long nextPostedCents, long atMs) {
            if (nextPostedCents < postedCents || atMs < lastAccruedMs) {
                throw new IllegalArgumentException(Reasons.TIME_REVERSED);
            }
            return new LoanTerms(rateU, startMs, maturityMs, nextNumerator, nextPostedCents,
                    paidCents, atMs, status, overdue);
        }

        /**
         * 付息后的条款（本金与费率不变）。
         *
         * @param nextPaidCents 新累计已收
         * @return 新条款
         */
        public LoanTerms withPayment(long nextPaidCents) {
            return new LoanTerms(rateU, startMs, maturityMs, numerator, postedCents,
                    nextPaidCents, lastAccruedMs, status, overdue);
        }

        /**
         * 改状态/逾期标记。
         *
         * @param nextStatus 新状态
         * @param nextOverdue 是否逾期
         * @return 新条款
         */
        public LoanTerms withStatus(String nextStatus, boolean nextOverdue) {
            return new LoanTerms(rateU, startMs, maturityMs, numerator, postedCents, paidCents,
                    lastAccruedMs, nextStatus, nextOverdue);
        }
    }

    /**
     * 存款账户政策：冻结 offer（可计息）或明确非应计，另有「余额中非应计部分」。
     *
     * <p>{@code excludedCents} 是账户余额里明确不计息的部分（历史存量或银行非应计入账）；
     * 计息基数 = 余额 − excluded。提款先减少有息部分，excluded 只被新余额封顶，
     * 故历史非应计余额不会被一次小额新增静默变成有息本金。</p>
     *
     * @param offerId          冻结 offer 标识（null=该账户明确非应计）
     * @param rateU            冻结费率（非应计为 0）
     * @param startMs          计息起点
     * @param maturityMs       约定到期（非应计=startMs）
     * @param numerator        累计分子
     * @param postedCents      累计已入账应付利息
     * @param paidCents        累计已付利息
     * @param lastAccruedMs    已累计到的时点（不设 maturity 上界）
     * @param excludedCents    余额中非应计部分
     * @param excludedReason   非应计部分原因（excluded&gt;0 时非空）
     * @param nonAccrualReason 整户非应计原因（offerId=null 时非空）
     */
    public record DepositAccount(String offerId, long rateU, long startMs, long maturityMs,
                                 BigInteger numerator, long postedCents, long paidCents,
                                 long lastAccruedMs, long excludedCents, String excludedReason,
                                 String nonAccrualReason) {

        /** 校验两种形态（offer 计息 / 明确非应计）互斥且自洽。 */
        public DepositAccount {
            Objects.requireNonNull(numerator, "计息分子");
            if (numerator.signum() < 0) {
                throw new IllegalArgumentException(Reasons.TIME_REVERSED);
            }
            if (postedCents < 0 || paidCents < 0 || paidCents > postedCents) {
                throw new IllegalArgumentException(Reasons.INTEREST_EXCEEDS_ACCRUED);
            }
            if (postedCents != InterestAccrual.targetCents(numerator)) {
                throw new IllegalArgumentException("INTEREST_POSTED_MISMATCH");
            }
            if (excludedCents < 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (excludedCents > 0) {
                if (excludedReason == null || excludedReason.isBlank()) {
                    throw new IllegalArgumentException("EXCLUDED_REASON_MISSING");
                }
            } else if (excludedReason != null) {
                throw new IllegalArgumentException("EXCLUDED_REASON_ORPHAN");
            }
            if (offerId == null) {
                if (nonAccrualReason == null || nonAccrualReason.isBlank()) {
                    throw new IllegalArgumentException("NON_ACCRUAL_REASON_MISSING");
                }
                if (rateU != 0 || maturityMs != startMs || postedCents != 0 || paidCents != 0
                        || numerator.signum() != 0) {
                    throw new IllegalArgumentException("NON_ACCRUAL_ACCOUNT:" + rateU);
                }
            } else {
                if (offerId.isBlank() || nonAccrualReason != null) {
                    throw new IllegalArgumentException(Reasons.OFFER_UNKNOWN + ":" + offerId);
                }
                if (rateU < 0 || rateU > InterestAccrual.RATE_ONE) {
                    throw new IllegalArgumentException(Reasons.RATE_INVALID + ":" + rateU);
                }
                if (startMs < 0 || maturityMs <= startMs) {
                    throw new IllegalArgumentException(Reasons.MATURITY_INVALID);
                }
            }
            if (lastAccruedMs < startMs) {
                throw new IllegalArgumentException(Reasons.TIME_REVERSED);
            }
        }

        /**
         * 整户明确非应计。
         *
         * @return 非应计返回 true
         */
        public boolean nonAccrual() {
            return offerId == null;
        }

        /**
         * 计息基数（美分）：余额 − 非应计部分；非应计账户恒为 0。
         *
         * @param balanceCents 当前存款余额（美分）
         * @return 计息基数
         */
        public long accrualBaseCents(long balanceCents) {
            if (nonAccrual() || balanceCents <= 0) {
                return 0;
            }
            return Math.max(0, balanceCents - excludedCents);
        }

        /**
         * 未付应计利息（posted−paid，派生视图）。
         *
         * @return 应计未付利息
         */
        public BigDecimal interestPayable() {
            return InterestAccrual.money(postedCents - paidCents);
        }

        /**
         * 累计已付利息（派生视图）。
         *
         * @return 累计已付利息
         */
        public BigDecimal interestPaid() {
            return InterestAccrual.money(paidCents);
        }

        /**
         * 累计已入账应付利息（派生视图）。
         *
         * @return 累计已入账利息
         */
        public BigDecimal accruedInterest() {
            return InterestAccrual.money(postedCents);
        }

        /**
         * 追加一段累计结果。
         *
         * @param nextNumerator 新分子
         * @param nextPostedCents 新累计已入账
         * @param atMs 累计到的时点
         * @return 新政策
         */
        public DepositAccount withAccrual(BigInteger nextNumerator, long nextPostedCents,
                                          long atMs) {
            if (nextPostedCents < postedCents || atMs < lastAccruedMs) {
                throw new IllegalArgumentException(Reasons.TIME_REVERSED);
            }
            return new DepositAccount(offerId, rateU, startMs, maturityMs, nextNumerator,
                    nextPostedCents, paidCents, atMs, excludedCents, excludedReason,
                    nonAccrualReason);
        }

        /**
         * 付息后的政策（存款余额在总账里同步增加）。
         *
         * @param nextPaidCents 新累计已付
         * @return 新政策
         */
        public DepositAccount withPayment(long nextPaidCents) {
            return new DepositAccount(offerId, rateU, startMs, maturityMs, numerator,
                    postedCents, nextPaidCents, lastAccruedMs, excludedCents, excludedReason,
                    nonAccrualReason);
        }

        /**
         * 提款/转出后的政策：有息部分先出，非应计部分只被新余额封顶。
         *
         * @param newBalanceCents 变更后的余额
         * @return 新政策
         */
        public DepositAccount withBalance(long newBalanceCents) {
            long nextExcluded = Math.min(excludedCents, Math.max(0, newBalanceCents));
            if (nextExcluded == excludedCents) {
                return this;
            }
            return new DepositAccount(offerId, rateU, startMs, maturityMs, numerator, postedCents,
                    paidCents, lastAccruedMs, nextExcluded,
                    nextExcluded > 0 ? excludedReason : null, nonAccrualReason);
        }

        /**
         * 调整非应计部分（银行非应计入账/支取封顶）。
         *
         * @param nextExcludedCents 新的非应计部分
         * @param reason 原因（&gt;0 时必填）
         * @return 新政策
         */
        public DepositAccount withExcluded(long nextExcludedCents, String reason) {
            long next = Math.max(0, nextExcludedCents);
            return new DepositAccount(offerId, rateU, startMs, maturityMs, numerator, postedCents,
                    paidCents, lastAccruedMs, next, next > 0 ? reason : null, nonAccrualReason);
        }

        /**
         * 非应计账户转入计息 offer：既有余额全部保留为非应计部分（不追溯计息）。
         *
         * @param offer 冻结 offer
         * @param atMs 开立时点
         * @param balanceCents 当前余额
         * @return 新政策
         */
        public DepositAccount withOffer(BankConfig.DepositOffer offer, long atMs,
                                        long balanceCents) {
            long excluded = Math.max(excludedCents, Math.max(0, balanceCents));
            return new DepositAccount(offer.offerId(), offer.rateU(), atMs,
                    atMs + offer.tenorMs(), BigInteger.ZERO, 0, 0, atMs, excluded,
                    excluded > 0 ? "opening_balance_before_offer" : null, null);
        }

        /**
         * 是否计息账户。
         *
         * @return 有 offer 返回 true
         */
        public boolean accrual() {
            return offerId != null;
        }
    }

    /**
     * 贷款期次：一次提款形成一期，各自冻结条款；聚合由期次派生（单一权威）。
     *
     * @param trancheId        期次标识（行内稳定，不含分隔符）
     * @param source           来源（OPENING / LOAN / COMMITMENT:id#n）
     * @param contract         本合同期次口径
     * @param onbook           表内
     * @param writtenOff       核销
     * @param terms            利息条款（null=明确非应计）
     * @param nonAccrualReason 非应计原因（terms=null 时非空）
     */
    public record Tranche(String trancheId, String source, BigDecimal contract,
                          BigDecimal onbook, BigDecimal writtenOff, LoanTerms terms,
                          String nonAccrualReason) {

        /** 校验期次自洽与非应计标记互斥。 */
        public Tranche {
            if (trancheId == null || trancheId.isBlank() || trancheId.indexOf('|') >= 0) {
                throw new IllegalArgumentException(Reasons.TRANCHE_UNKNOWN + ":" + trancheId);
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("TRANCHE_SOURCE_INVALID");
            }
            contract = Money.scale2(Objects.requireNonNull(contract, "合同"));
            onbook = Money.scale2(Objects.requireNonNull(onbook, "表内"));
            writtenOff = Money.scale2(Objects.requireNonNull(writtenOff, "核销"));
            if (contract.signum() < 0 || onbook.signum() < 0 || writtenOff.signum() < 0) {
                throw new IllegalArgumentException("期次金额不能为负");
            }
            if (onbook.add(writtenOff).compareTo(contract) != 0) {
                throw new IllegalArgumentException(Reasons.LOAN_OBLIGATION_MISMATCH);
            }
            if (terms == null) {
                if (nonAccrualReason == null || nonAccrualReason.isBlank()) {
                    throw new IllegalArgumentException("TRANCHE_REASON_MISSING");
                }
            } else if (nonAccrualReason != null) {
                throw new IllegalArgumentException("TRANCHE_REASON_ORPHAN");
            }
        }

        /**
         * 无条款的期次（旧存量或旧档案硬构造）。
         *
         * @param contract 合同额
         * @param onbook 表内
         * @param writtenOff 核销
         * @return 期次
         */
        public static Tranche legacy(BigDecimal contract, BigDecimal onbook,
                                     BigDecimal writtenOff) {
            return new Tranche("T0", "LEGACY", contract, onbook, writtenOff, null,
                    "legacy_no_terms");
        }

        /**
         * 是否计息期次。
         *
         * @return 计息返回 true
         */
        public boolean accrual() {
            return terms != null;
        }

        /**
         * 未收应计利息（非应计期次为 0）。
         *
         * @return 应计未收利息
         */
        public BigDecimal interestReceivable() {
            return terms == null ? Money.zero() : terms.interestReceivable();
        }
    }

    /** 单笔贷款（按期次记账，聚合口径全部派生）。
     * @param tranches 期次列表（顺序即稳定 FIFO 分配顺序）
     */
    public record Loan(List<Tranche> tranches) {

        /** 深拷贝并校验期次标识唯一。 */
        public Loan {
            List<Tranche> copy = List.copyOf(Objects.requireNonNull(tranches, "期次"));
            Set<String> ids = new LinkedHashSet<>();
            for (Tranche tranche : copy) {
                if (!ids.add(tranche.trancheId())) {
                    throw new IllegalArgumentException(Reasons.TRANCHE_UNKNOWN + ":dup:"
                            + tranche.trancheId());
                }
            }
            tranches = copy;
        }

        /**
         * 单期旧口径便利构造（仅用于无条款旧存量与篡改探测）。
         *
         * @param contract 合同额
         * @param onbook 表内
         * @param writtenOff 核销
         */
        public Loan(BigDecimal contract, BigDecimal onbook, BigDecimal writtenOff) {
            this(List.of(Tranche.legacy(contract, onbook, writtenOff)));
        }

        /**
         * 合同口径合计。
         *
         * @return 合同合计
         */
        public BigDecimal contract() {
            return sum(Tranche::contract);
        }

        /**
         * 表内合计。
         *
         * @return 表内合计
         */
        public BigDecimal onbook() {
            return sum(Tranche::onbook);
        }

        /**
         * 核销合计。
         *
         * @return 核销合计
         */
        public BigDecimal writtenOff() {
            return sum(Tranche::writtenOff);
        }

        /**
         * 应计未收利息合计。
         *
         * @return 应收利息合计
         */
        public BigDecimal interestReceivable() {
            BigDecimal total = Money.zero();
            for (Tranche tranche : tranches) {
                total = total.add(tranche.interestReceivable());
            }
            return Money.scale2(total);
        }

        /**
         * 累计已收利息合计。
         *
         * @return 已收利息合计
         */
        public BigDecimal interestPaid() {
            BigDecimal total = Money.zero();
            for (Tranche tranche : tranches) {
                if (tranche.terms() != null) {
                    total = total.add(tranche.terms().interestPaid());
                }
            }
            return Money.scale2(total);
        }

        /**
         * 累计已入账利息合计。
         *
         * @return 已入账利息合计
         */
        public BigDecimal accruedInterest() {
            BigDecimal total = Money.zero();
            for (Tranche tranche : tranches) {
                if (tranche.terms() != null) {
                    total = total.add(tranche.terms().accruedInterest());
                }
            }
            return Money.scale2(total);
        }

        /**
         * 是否存在逾期期次。
         *
         * @return 任一期逾期返回 true
         */
        public boolean overdue() {
            for (Tranche tranche : tranches) {
                if (tranche.terms() != null && tranche.terms().overdue()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 聚合状态：任一期逾期→PAST_DUE；全部结清→SETTLED；否则 CURRENT。
         *
         * @return 聚合状态
         */
        public String status() {
            boolean open = false;
            for (Tranche tranche : tranches) {
                if (tranche.terms() != null && tranche.terms().overdue()) {
                    return PAST_DUE;
                }
                if (tranche.contract().signum() > 0
                        || tranche.interestReceivable().signum() > 0) {
                    open = true;
                }
            }
            return open ? CURRENT : SETTLED;
        }

        /**
         * 按期次标识取期次。
         *
         * @param id 期次标识
         * @return 期次（不存在返回 null）
         */
        public Tranche tranche(String id) {
            for (Tranche tranche : tranches) {
                if (tranche.trancheId().equals(id)) {
                    return tranche;
                }
            }
            return null;
        }

        /**
         * 下一个稳定期次标识（不与现有期次冲突）。
         *
         * @param prefix 前缀
         * @return 期次标识
         */
        public String nextTrancheId(String prefix) {
            int seq = 1;
            while (tranche(prefix + seq) != null) {
                seq++;
            }
            return prefix + seq;
        }

        private BigDecimal sum(Function<Tranche, BigDecimal> field) {
            BigDecimal total = Money.zero();
            for (Tranche tranche : tranches) {
                total = total.add(field.apply(tranche));
            }
            return Money.scale2(total);
        }

        /**
         * 追加一期。
         *
         * @param tranche 新期次
         * @return 新贷款
         */
        public Loan plus(Tranche tranche) {
            List<Tranche> next = new ArrayList<>(tranches);
            next.add(tranche);
            return new Loan(next);
        }

        /**
         * 替换某一期。
         *
         * @param id 期次标识
         * @param replacement 新期次
         * @return 新贷款
         */
        public Loan replace(String id, Tranche replacement) {
            List<Tranche> next = new ArrayList<>(tranches);
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i).trancheId().equals(id)) {
                    next.set(i, replacement);
                    return new Loan(next);
                }
            }
            throw new IllegalArgumentException(Reasons.TRANCHE_UNKNOWN + ":" + id);
        }
    }

    /**
     * 明细贷款承诺（非循环）：额度、已提、到期与授权主体单份冻结。
     *
     * @param borrower       借款人（提款不可换人）
     * @param limit          承诺额度
     * @param drawnCumulative 累计已提（只增不减，已偿不恢复）
     * @param expiryMs       提款到期时点
     * @param tenorMs        每次提款的贷款期限
     * @param rateU          提款冻结费率
     * @param authorizedBy   除借款人外被授权提款的主体
     * @param status         状态（OPEN/FULLY_DRAWN/CLOSED）
     */
    public record Commitment(String borrower, BigDecimal limit, BigDecimal drawnCumulative,
                             long expiryMs, long tenorMs, long rateU, String authorizedBy,
                             String status) {

        /** 校验额度、到期、费率与状态。 */
        public Commitment {
            if (borrower == null || borrower.isBlank()) {
                throw new IllegalArgumentException(Reasons.UNKNOWN_BORROWER);
            }
            limit = Money.scale2(Objects.requireNonNull(limit, "额度"));
            drawnCumulative = Money.scale2(Objects.requireNonNull(drawnCumulative, "已提"));
            if (limit.signum() <= 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (drawnCumulative.signum() < 0 || drawnCumulative.compareTo(limit) > 0) {
                throw new IllegalArgumentException(Reasons.COMMITMENT_LIMIT);
            }
            if (expiryMs < 0 || tenorMs <= 0) {
                throw new IllegalArgumentException(Reasons.COMMITMENT_EXPIRY_INVALID);
            }
            if (rateU < 0 || rateU > InterestAccrual.RATE_ONE) {
                throw new IllegalArgumentException(Reasons.RATE_INVALID + ":" + rateU);
            }
            if (authorizedBy == null || authorizedBy.isBlank()) {
                throw new IllegalArgumentException(Reasons.UNKNOWN_AUTHORIZED);
            }
            if (status == null || !Set.of(COMMITMENT_OPEN, COMMITMENT_FULLY_DRAWN,
                    COMMITMENT_CLOSED).contains(status)) {
                throw new IllegalArgumentException("COMMITMENT_STATUS_INVALID:" + status);
            }
        }

        /**
         * 未提额度（派生，不另存）。
         *
         * @return 未提额度
         */
        public BigDecimal undrawn() {
            return Money.scale2(limit.subtract(drawnCumulative));
        }

        /**
         * 是否仍可提款（状态 + 未提额度，不含时点判定）。
         *
         * @return 可提返回 true
         */
        public boolean drawable() {
            return COMMITMENT_OPEN.equals(status) && undrawn().signum() > 0;
        }

        /**
         * 提款后的记录。
         *
         * @param amount 本次提款
         * @return 新记录（已提累计只增不减）
         */
        public Commitment withDraw(BigDecimal amount) {
            BigDecimal drawn = Money.scale2(drawnCumulative.add(amount));
            String next = drawn.compareTo(limit) == 0 ? COMMITMENT_FULLY_DRAWN : status;
            return new Commitment(borrower, limit, drawn, expiryMs, tenorMs, rateU, authorizedBy,
                    next);
        }
    }

    /** 救助设施：提供者、额度、已提款、押品占用与状态。
     * @param provider provider 参数
     * @param limit limit 参数
     * @param outstanding outstanding 参数
     * @param pledged pledged 参数
     * @param status status 参数
     */
public record Facility(String provider, BigDecimal limit, BigDecimal outstanding,
                           Set<String> pledged, String status) {
        /** 深拷贝押品集合。 */
        public Facility {
            Objects.requireNonNull(provider, "提供者");
            limit = Money.scale2(limit);
            outstanding = Money.scale2(outstanding);
            pledged = Collections.unmodifiableSet(new LinkedHashSet<>(pledged));
            status = status == null ? "OPEN" : status;
        }
    }

    /**
     * B3 救助设施账户：提供者批准的额度、已提、名义、表内/备查、准备、利息与押品，单份权威。
     *
     * <p>{@code drawnCumulative} 是累计已用（非循环，只增不减），故回收/豁免减少名义时不会
     * 重新长出可提额度；{@code nominal = onbook + writtenOff}，且 {@code nominal ≤ drawnCumulative}。
     * 利息分子 {@link BigInteger}、按 onbook 时间积分、单次舍入；posted/paid 成对。</p>
     *
     * @param bank                 银行
     * @param authorizationId      授权标识（行内唯一）
     * @param provider             提供者
     * @param limit                授权总额
     * @param drawnCumulative      累计已提（只增）
     * @param onbook               表内本金
     * @param writtenOff           备查（已核销）本金
     * @param allowance            减值准备（≤ onbook）
     * @param rateU                冻结费率
     * @param loanTenorMs          每次提款的贷款期限
     * @param authorizedAtMs       授权时点
     * @param authorizationExpiryMs 授权有效期终点（过期只禁新提款）
     * @param facilityMaturityMs   设施到期时点（与授权有效期分开）
     * @param numerator            累计利息分子
     * @param postedCents          累计已入账利息
     * @param paidCents            累计已付利息
     * @param lastAccruedMs        已累计到的时点
     * @param impairCents          累计计提
     * @param reversalCents        累计准备转回
     * @param recoveryCents        累计核销后回收
     * @param waiverLossCents      累计豁免/解除损失
     * @param collateralPolicy     SECURED / UNSECURED
     * @param collateral           占用押品标识（唯一占用，按序稳定）
     * @param status               状态
     */
    public record FacilityAccount(String bank, String authorizationId, String provider,
                                  BigDecimal limit, BigDecimal drawnCumulative,
                                  BigDecimal onbook, BigDecimal writtenOff,
                                  BigDecimal allowance, long rateU, long loanTenorMs,
                                  long authorizedAtMs, long authorizationExpiryMs,
                                  long facilityMaturityMs, BigInteger numerator,
                                  long postedCents, long paidCents, long lastAccruedMs,
                                  long impairCents, long reversalCents, long recoveryCents,
                                  long waiverLossCents, String collateralPolicy,
                                  Set<String> collateral, String status) {

        /** 校验额度/名义/准备/利息/押品政策/状态的自洽性。 */
        public FacilityAccount {
            if (bank == null || bank.isBlank()) {
                throw new IllegalArgumentException(Reasons.UNKNOWN_BANK + ":" + bank);
            }
            if (authorizationId == null || authorizationId.isBlank()
                    || authorizationId.indexOf('|') >= 0) {
                throw new IllegalArgumentException(Reasons.FACILITY_UNKNOWN_AUTHORIZATION + ":"
                        + authorizationId);
            }
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException(Reasons.FACILITY_UNKNOWN_PROVIDER);
            }
            limit = Money.scale2(Objects.requireNonNull(limit, "额度"));
            drawnCumulative = Money.scale2(Objects.requireNonNull(drawnCumulative, "已提"));
            onbook = Money.scale2(Objects.requireNonNull(onbook, "表内"));
            writtenOff = Money.scale2(Objects.requireNonNull(writtenOff, "备查"));
            allowance = Money.scale2(Objects.requireNonNull(allowance, "准备"));
            if (limit.signum() <= 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (drawnCumulative.signum() < 0 || drawnCumulative.compareTo(limit) > 0) {
                throw new IllegalArgumentException(Reasons.FACILITY_OVER_DRAW);
            }
            if (onbook.signum() < 0 || writtenOff.signum() < 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            BigDecimal nominal = onbook.add(writtenOff);
            if (nominal.compareTo(drawnCumulative) > 0) {
                throw new IllegalArgumentException(Reasons.LOAN_OBLIGATION_MISMATCH);
            }
            if (allowance.signum() < 0 || allowance.compareTo(onbook) > 0) {
                throw new IllegalArgumentException(Reasons.PROVISION_EXCEEDS_GROSS);
            }
            if (rateU < 0 || rateU > InterestAccrual.RATE_ONE) {
                throw new IllegalArgumentException(Reasons.RATE_INVALID + ":" + rateU);
            }
            if (loanTenorMs <= 0) {
                throw new IllegalArgumentException(Reasons.TENOR_INVALID);
            }
            if (authorizedAtMs < 0 || authorizationExpiryMs <= authorizedAtMs
                    || facilityMaturityMs <= authorizedAtMs) {
                throw new IllegalArgumentException(Reasons.FACILITY_MATURITY_INVALID);
            }
            Objects.requireNonNull(numerator, "计息分子");
            if (numerator.signum() < 0) {
                throw new IllegalArgumentException(Reasons.TIME_REVERSED);
            }
            if (postedCents < 0 || paidCents < 0 || paidCents > postedCents) {
                throw new IllegalArgumentException(Reasons.INTEREST_EXCEEDS_ACCRUED);
            }
            if (postedCents != InterestAccrual.targetCents(numerator)) {
                throw new IllegalArgumentException("INTEREST_POSTED_MISMATCH");
            }
            if (lastAccruedMs < authorizedAtMs) {
                throw new IllegalArgumentException(Reasons.TIME_REVERSED);
            }
            if (impairCents < 0 || reversalCents < 0 || recoveryCents < 0
                    || waiverLossCents < 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (!BankRules.COLLATERAL_SECURED.equals(collateralPolicy)
                    && !BankRules.COLLATERAL_UNSECURED.equals(collateralPolicy)) {
                throw new IllegalArgumentException(
                        Reasons.FACILITY_COLLATERAL_POLICY_INVALID + ":" + collateralPolicy);
            }
            Set<String> pledged = new LinkedHashSet<>(
                    collateral == null ? Set.of() : collateral);
            for (String id : pledged) {
                if (id == null || id.isBlank() || id.indexOf('|') >= 0) {
                    throw new IllegalArgumentException(Reasons.COLLATERAL_UNKNOWN + ":" + id);
                }
            }
            if (BankRules.COLLATERAL_UNSECURED.equals(collateralPolicy) && !pledged.isEmpty()) {
                throw new IllegalArgumentException(
                        Reasons.FACILITY_COLLATERAL_POLICY_INVALID + ":unsecured-pledged");
            }
            collateral = Collections.unmodifiableSet(pledged);
            if (status == null || !Set.of(BankRules.FACILITY_OPEN,
                    BankRules.FACILITY_PAST_DUE,
                    BankRules.FACILITY_CLOSED_REPAID, BankRules.FACILITY_CLOSED_WITH_LOSS,
                    BankRules.FACILITY_CLOSED_DISCHARGED,
                    BankRules.FACILITY_DEFAULT_STATUS).contains(status)) {
                throw new IllegalArgumentException("FACILITY_STATUS_INVALID:" + status);
            }
            boolean closed = status.startsWith("CLOSED");
            if (closed) {
                if (nominal.signum() != 0 || postedCents != paidCents) {
                    throw new IllegalArgumentException(Reasons.FACILITY_NOT_SETTLED);
                }
                if (!pledged.isEmpty()) {
                    throw new IllegalArgumentException(Reasons.COLLATERAL_ENCUMBERED);
                }
            }
        }

        /**
         * 名义本金（{@code onbook + writtenOff}）。
         *
         * @return 名义本金
         */
        public BigDecimal nominal() {
            return Money.scale2(onbook.add(writtenOff));
        }

        /**
         * 未提额度（非循环：授权总额 − 累计已提）。
         *
         * @return 未提额度
         */
        public BigDecimal undrawn() {
            return Money.scale2(limit.subtract(drawnCumulative));
        }

        /**
         * 净账面（{@code onbook − allowance}）。
         *
         * @return 净账面
         */
        public BigDecimal netBook() {
            return Money.scale2(onbook.subtract(allowance));
        }

        /**
         * 应计未付利息（posted−paid）。
         *
         * @return 应付利息
         */
        public BigDecimal interestPayable() {
            return InterestAccrual.money(postedCents - paidCents);
        }

        /**
         * 累计已入账利息。
         *
         * @return 累计已入账利息
         */
        public BigDecimal interestAccrued() {
            return InterestAccrual.money(postedCents);
        }

        /**
         * 是否已关闭。
         *
         * @return 关闭返回 true
         */
        public boolean closed() {
            return status != null && status.startsWith("CLOSED");
        }

        /**
         * 是否要求押品。
         *
         * @return SECURED 返回 true
         */
        public boolean secured() {
            return BankRules.COLLATERAL_SECURED.equals(collateralPolicy);
        }

        /**
         * 追加一段累计结果（名义不变）。
         *
         * @param nextNumerator 新分子
         * @param nextPostedCents 新累计已入账
         * @param atMs 累计到的时点
         * @return 新账户
         */
        public FacilityAccount withAccrual(BigInteger nextNumerator, long nextPostedCents,
                                           long atMs) {
            if (nextPostedCents < postedCents || atMs < lastAccruedMs) {
                throw new IllegalArgumentException(Reasons.TIME_REVERSED);
            }
            return new FacilityAccount(bank, authorizationId, provider, limit, drawnCumulative,
                    onbook, writtenOff, allowance, rateU, loanTenorMs, authorizedAtMs,
                    authorizationExpiryMs, facilityMaturityMs, nextNumerator, nextPostedCents,
                    paidCents, atMs, impairCents, reversalCents, recoveryCents,
                    waiverLossCents, collateralPolicy, collateral, status);
        }

        /**
         * 复制一份并替换指定字段。
         *
         * @param nextLimit 新额度
         * @param nextDrawn 新累计已提
         * @param nextOnbook 新表内
         * @param nextWrittenOff 新备查
         * @param nextAllowance 新准备
         * @param nextPaid 新已付利息
         * @param nextImpair 新累计计提
         * @param nextReversal 新累计转回
         * @param nextRecovery 新累计回收
         * @param nextWaiverLoss 新累计豁免损失
         * @param nextCollateral 新押品集合
         * @param nextStatus 新状态
         * @return 新账户
         */
        public FacilityAccount with(BigDecimal nextLimit, BigDecimal nextDrawn,
                                    BigDecimal nextOnbook, BigDecimal nextWrittenOff,
                                    BigDecimal nextAllowance, long nextPaid, long nextImpair,
                                    long nextReversal, long nextRecovery, long nextWaiverLoss,
                                    Set<String> nextCollateral, String nextStatus) {
            return new FacilityAccount(bank, authorizationId, provider, nextLimit, nextDrawn,
                    nextOnbook, nextWrittenOff, nextAllowance, rateU, loanTenorMs,
                    authorizedAtMs, authorizationExpiryMs, facilityMaturityMs, numerator,
                    postedCents, nextPaid, lastAccruedMs, nextImpair, nextReversal,
                    nextRecovery, nextWaiverLoss, collateralPolicy,
                    nextCollateral == null ? collateral : nextCollateral, nextStatus);
        }

        /**
         * 仅改状态的复制（利息与名义不变）。
         *
         * @param nextStatus 新状态
         * @return 新账户
         */
        public FacilityAccount withStatus(String nextStatus) {
            return new FacilityAccount(bank, authorizationId, provider, limit, drawnCumulative,
                    onbook, writtenOff, allowance, rateU, loanTenorMs, authorizedAtMs,
                    authorizationExpiryMs, facilityMaturityMs, numerator, postedCents, paidCents,
                    lastAccruedMs, impairCents, reversalCents, recoveryCents, waiverLossCents,
                    collateralPolicy, collateral, nextStatus);
        }

        /**
         * 提款后的账户：名义与已提增加，到期日取与冻结值的较早者（后续提款不展期）。
         *
         * @param amount 本次提款
         * @param freezeMaturityMs 本次提款隐含的到期（首次 draw + tenor）
         * @param nextCollateral 新押品集合
         * @return 新账户
         */
        public FacilityAccount withDraw(BigDecimal amount, long freezeMaturityMs,
                                        Set<String> nextCollateral) {
            long effective = Math.min(facilityMaturityMs, freezeMaturityMs);
            return new FacilityAccount(bank, authorizationId, provider, limit,
                    drawnCumulative.add(amount), onbook.add(amount), writtenOff, allowance, rateU,
                    loanTenorMs, authorizedAtMs, authorizationExpiryMs, effective, numerator,
                    postedCents, paidCents, lastAccruedMs, impairCents, reversalCents,
                    recoveryCents, waiverLossCents, collateralPolicy, nextCollateral, status);
        }

        /**
         * 仅改押品集合的复制。
         *
         * @param nextCollateral 新押品集合
         * @return 新账户
         */
        public FacilityAccount withCollateral(Set<String> nextCollateral) {
            return new FacilityAccount(bank, authorizationId, provider, limit, drawnCumulative,
                    onbook, writtenOff, allowance, rateU, loanTenorMs, authorizedAtMs,
                    authorizationExpiryMs, facilityMaturityMs, numerator, postedCents, paidCents,
                    lastAccruedMs, impairCents, reversalCents, recoveryCents, waiverLossCents,
                    collateralPolicy, nextCollateral, status);
        }
    }

    /**
     * 押品：绑定真实资产键与账面价值、档案估值、折扣与唯一占用。
     *
     * @param collateralId   押品标识
     * @param owner          所有权银行（不得把别人资产拿来担保）
     * @param kind           PREMISES / LOAN
     * @param assetKey       真实资产键：PREMISES 账户或 {@code bank|borrower} 贷款键（全局唯一绑定）
     * @param bookValue      开局时的真实账面价值（PREMISES 余额 / 贷款 onbook）；出账用它，不用估值
     * @param appraisedValue 档案估值（只用于授信折扣，且不得超过账面）
     * @param haircutU       折扣（定标 1e6）
     * @param pledgedTo      占用的设施键（唯一，未占用为 null）
     * @param status         FREE / PLEDGED / DISPOSED / EXHAUSTED
     */
    public record Collateral(String collateralId, String owner, String kind, String assetKey,
                             BigDecimal bookValue, BigDecimal appraisedValue, long haircutU,
                             String pledgedTo, String status) {

        /** 校验所有权、资产键、账面/估值、折扣与占用状态。 */
        public Collateral {
            if (collateralId == null || collateralId.isBlank() || collateralId.indexOf('|') >= 0) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_UNKNOWN + ":"
                        + collateralId);
            }
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_OWNER_MISMATCH + ":"
                        + owner);
            }
            if (!BankRules.COLLATERAL_KINDS.contains(kind)) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_UNKNOWN + ":kind:" + kind);
            }
            if (assetKey == null || assetKey.isBlank()) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_UNKNOWN + ":assetKey");
            }
            bookValue = Money.valuation(Objects.requireNonNull(bookValue, "账面"));
            appraisedValue = Money.valuation(Objects.requireNonNull(appraisedValue, "估值"));
            if (appraisedValue.signum() <= 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (bookValue.signum() < 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (haircutU < 0 || haircutU >= InterestAccrual.RATE_ONE) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_INVALID + ":haircut");
            }
            if (status == null || !Set.of(BankRules.COLLATERAL_FREE,
                    BankRules.COLLATERAL_PLEDGED, BankRules.COLLATERAL_DISPOSED,
                    BankRules.COLLATERAL_EXHAUSTED).contains(status)) {
                throw new IllegalArgumentException("COLLATERAL_STATUS_INVALID:" + status);
            }
            boolean pledged = BankRules.COLLATERAL_PLEDGED.equals(status);
            if (pledged != (pledgedTo != null)) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_ENCUMBERED + ":"
                        + collateralId);
            }
            if (BankRules.COLLATERAL_EXHAUSTED.equals(status) && bookValue.signum() != 0) {
                throw new IllegalArgumentException(Reasons.COLLATERAL_EXHAUSTED + ":book");
            }
        }

        /**
         * 可用价值：min(估值, 账面) × (1−折扣)，向下取整到分。
         *
         * @return 可用价值
         */
        public BigDecimal usableValue() {
            BigDecimal base = appraisedValue.min(bookValue);
            long cents = InterestAccrual.cents(base);
            BigInteger usable = BigInteger.valueOf(cents)
                    .multiply(BigInteger.valueOf(InterestAccrual.RATE_ONE - haircutU))
                    .divide(BigInteger.valueOf(InterestAccrual.RATE_ONE));
            return InterestAccrual.money(usable.longValueExact());
        }

        /**
         * 复制并替换占用与状态。
         *
         * @param nextPledgedTo 新占用键（null=释放）
         * @param nextStatus 新状态
         * @return 新押品
         */
        public Collateral withPledge(String nextPledgedTo, String nextStatus) {
            return new Collateral(collateralId, owner, kind, assetKey, bookValue, appraisedValue,
                    haircutU, nextPledgedTo, nextStatus);
        }

        /**
         * 底层资产变动后下调账面（不改变档案估值上限）。
         *
         * @param nextBook 新账面
         * @param nextPledgedTo 新占用键（null=释放）
         * @param nextStatus 新状态
         * @return 新押品
         */
        public Collateral withBook(BigDecimal nextBook, String nextPledgedTo, String nextStatus) {
            return new Collateral(collateralId, owner, kind, assetKey, nextBook, appraisedValue,
                    haircutU, nextPledgedTo, nextStatus);
        }
    }

    /**
     * 买方真实受让许可：买方本人登记、一次用途、冻结卖方/对象/价格/有效期。
     *
     * @param purchaseId  许可标识
     * @param buyer       买方（登记人本人）
     * @param seller      卖方（银行或独立处置权）
     * @param subjectType LOAN / COLLATERAL
     * @param subject     LOAN 用 {@code bank|borrower}，COLLATERAL 用 collateralId
     * @param price       冻结价格
     * @param expiryMs    有效期终点
     * @param status      OPEN / CONSUMED
     */
    public record PurchaseConsent(String purchaseId, String buyer, String seller,
                                  String subjectType, String subject, BigDecimal price,
                                  long expiryMs, String status) {

        /** 校验主体、对象、价格与状态。 */
        public PurchaseConsent {
            if (purchaseId == null || purchaseId.isBlank() || purchaseId.indexOf('|') >= 0) {
                throw new IllegalArgumentException(Reasons.PURCHASE_UNKNOWN + ":" + purchaseId);
            }
            if (buyer == null || buyer.isBlank() || seller == null || seller.isBlank()) {
                throw new IllegalArgumentException(Reasons.PURCHASE_UNKNOWN + ":" + purchaseId);
            }
            if (buyer.equals(seller)) {
                throw new IllegalArgumentException(Reasons.PURCHASE_SELF);
            }
            if (!Set.of(BankRules.SUBJECT_LOAN, BankRules.SUBJECT_COLLATERAL)
                    .contains(subjectType)) {
                throw new IllegalArgumentException(Reasons.PURCHASE_UNKNOWN + ":subjectType");
            }
            if (subject == null || subject.isBlank()) {
                throw new IllegalArgumentException(Reasons.PURCHASE_UNKNOWN + ":subject");
            }
            if (BankRules.SUBJECT_LOAN.equals(subjectType) && subject.indexOf('|') < 0) {
                throw new IllegalArgumentException(Reasons.PURCHASE_SUBJECT_MISMATCH + ":loan");
            }
            price = Money.scale2(Objects.requireNonNull(price, "价格"));
            if (price.signum() <= 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (expiryMs <= 0) {
                throw new IllegalArgumentException(Reasons.PURCHASE_EXPIRED + ":expiry");
            }
            if (!Set.of(BankRules.PURCHASE_OPEN, BankRules.PURCHASE_CONSUMED).contains(status)) {
                throw new IllegalArgumentException("PURCHASE_STATUS_INVALID:" + status);
            }
        }

        /**
         * 是否可执行。
         *
         * @return OPEN 返回 true
         */
        public boolean open() {
            return BankRules.PURCHASE_OPEN.equals(status);
        }

        /**
         * 消耗后的记录。
         *
         * @return 新记录
         */
        public PurchaseConsent consumed() {
            return new PurchaseConsent(purchaseId, buyer, seller, subjectType, subject, price,
                    expiryMs, BankRules.PURCHASE_CONSUMED);
        }
    }

    /**
     * 债权人真实解除许可：本人登记、金额/对象/有效期绑定、一次用途。
     *
     * @param consentId 许可标识
     * @param bank      银行
     * @param account   负债科目
     * @param creditor  真实权利人（必须与账上权利归属一致）
     * @param amount    同意解除的金额
     * @param expiryMs  有效期终点
     * @param status    OPEN / USED
     */
    public record DischargeConsent(String consentId, String bank, String account,
                                   String creditor, BigDecimal amount, long expiryMs,
                                   String status) {

        /** 校验银行、科目、权利人、金额与状态。 */
        public DischargeConsent {
            if (consentId == null || consentId.isBlank() || consentId.indexOf('|') >= 0) {
                throw new IllegalArgumentException(Reasons.CONSENT_UNKNOWN + ":" + consentId);
            }
            if (bank == null || bank.isBlank()) {
                throw new IllegalArgumentException(Reasons.UNKNOWN_BANK + ":" + bank);
            }
            if (account == null || account.isBlank()) {
                throw new IllegalArgumentException(Reasons.CONSENT_UNKNOWN + ":account");
            }
            if (creditor == null || creditor.isBlank()) {
                throw new IllegalArgumentException(Reasons.CONSENT_CREDITOR_INVALID + ":"
                        + creditor);
            }
            amount = Money.scale2(Objects.requireNonNull(amount, "金额"));
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (expiryMs <= 0) {
                throw new IllegalArgumentException(Reasons.CONSENT_EXPIRED + ":expiry");
            }
            if (!Set.of(BankRules.CONSENT_OPEN, BankRules.CONSENT_USED).contains(status)) {
                throw new IllegalArgumentException("CONSENT_STATUS_INVALID:" + status);
            }
        }

        /**
         * 是否可用。
         *
         * @return OPEN 返回 true
         */
        public boolean open() {
            return BankRules.CONSENT_OPEN.equals(status);
        }

        /**
         * 使用后的记录。
         *
         * @return 新记录
         */
        public DischargeConsent used() {
            return new DischargeConsent(consentId, bank, account, creditor, amount, expiryMs,
                    BankRules.CONSENT_USED);
        }
    }

    /**
     * 提供者开局事实：现金、既有债权与初始资本，冻结后不得用当前值反推。
     *
     * @param provider       提供者
     * @param initialCash    开局现金
     * @param initialClaims  开局既有债权（已提设施名义）
     * @param initialCapital 初始资本 = 现金 + 债权
     * @param source         来源说明
     */
    public record ProviderOpening(String provider, BigDecimal initialCash,
                                  BigDecimal initialClaims, BigDecimal initialCapital,
                                  String source) {

        /** 校验三者自洽与来源。 */
        public ProviderOpening {
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException(Reasons.FACILITY_UNKNOWN_PROVIDER);
            }
            initialCash = Money.valuation(Objects.requireNonNull(initialCash, "初始现金"));
            initialClaims = Money.valuation(Objects.requireNonNull(initialClaims, "初始债权"));
            initialCapital = Money.valuation(Objects.requireNonNull(initialCapital, "初始资本"));
            if (Money.scale2(initialCash.add(initialClaims)).compareTo(initialCapital) != 0) {
                throw new IllegalArgumentException(Reasons.PROVIDER_CAPITAL_MISMATCH);
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("PROVIDER_SOURCE_MISSING");
            }
        }
    }

    /**
     * 有限买方受让的债权：唯一持有人、原名义/未清本金/成本释放分别保存。
     *
     * <p>{@code originalNominal} 是受让时的原始名义（成本分摊的固定分母，不随结算缩小）；
     * {@code loan.contract()} 是**当前未清本金**（结算按 FIFO 同时减少期次的 onbook/writtenOff 与 contract）；
     * {@code settled} 是累计已收本 + 已豁免，且恒等于 {@code originalNominal − loan.contract()}。
     * 已计利息只在期次里（posted/paid），结算本金不减少它，也不把利息资本化。</p>
     *
     * @param claimId        转让标识
     * @param borrower       借款人（不变）
     * @param holder         当前持有人（只他可回收/豁免）
     * @param sellerBank     原银行
     * @param loan           期次快照（条款/备查/来源保留；contract 为未清本金）
     * @param originalNominal 受让时的原始名义（固定成本分母）
     * @param bookCost       买方成本 Q
     * @param costReleased   累计已释放成本
     * @param recoveredCents 累计回收本金
     * @param waivedCents    累计豁免本金
     * @param status         ACTIVE / SETTLED（SETTLED 只表示本金结清，利息可仍待收）
     */
    public record AssignedClaim(String claimId, String borrower, String holder, String sellerBank,
                                Loan loan, BigDecimal originalNominal, BigDecimal bookCost,
                                BigDecimal costReleased, long recoveredCents, long waivedCents,
                                String status) {

        /** 校验名义守恒、成本释放上限与状态。 */
        public AssignedClaim {
            if (claimId == null || claimId.isBlank() || claimId.indexOf('|') >= 0) {
                throw new IllegalArgumentException(Reasons.CLAIM_UNKNOWN + ":" + claimId);
            }
            if (borrower == null || borrower.isBlank() || holder == null || holder.isBlank()
                    || sellerBank == null || sellerBank.isBlank()) {
                throw new IllegalArgumentException(Reasons.CLAIM_UNKNOWN + ":" + claimId);
            }
            Objects.requireNonNull(loan, "债权");
            originalNominal = Money.scale2(Objects.requireNonNull(originalNominal, "原名义"));
            bookCost = Money.scale2(Objects.requireNonNull(bookCost, "成本"));
            costReleased = Money.scale2(Objects.requireNonNull(costReleased, "已释放成本"));
            if (originalNominal.signum() <= 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (bookCost.signum() <= 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (costReleased.signum() < 0 || costReleased.compareTo(bookCost) > 0) {
                throw new IllegalArgumentException(Reasons.CLAIM_EXCEEDS_NOMINAL + ":cost");
            }
            if (recoveredCents < 0 || waivedCents < 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            BigDecimal settled = InterestAccrual.money(recoveredCents + waivedCents);
            if (settled.compareTo(originalNominal) > 0) {
                throw new IllegalArgumentException(Reasons.CLAIM_EXCEEDS_NOMINAL);
            }
            if (loan.contract().compareTo(originalNominal) > 0) {
                throw new IllegalArgumentException(Reasons.CLAIM_EXCEEDS_NOMINAL + ":contract");
            }
            if (settled.compareTo(Money.scale2(originalNominal.subtract(loan.contract()))) != 0) {
                throw new IllegalArgumentException("CLAIM_SETTLED_MISMATCH:" + claimId);
            }
            if (status == null || !Set.of(BankRules.CLAIM_ACTIVE,
                    BankRules.CLAIM_SETTLED).contains(status)) {
                throw new IllegalArgumentException("CLAIM_STATUS_INVALID:" + status);
            }
            if (BankRules.CLAIM_SETTLED.equals(status)) {
                if (loan.contract().signum() != 0 || costReleased.compareTo(bookCost) != 0) {
                    throw new IllegalArgumentException(Reasons.CLAIM_ALREADY_SETTLED);
                }
            }
        }

        /**
         * 当前未清本金（期次合同合计）。
         *
         * @return 未清本金
         */
        public BigDecimal nominal() {
            return loan.contract();
        }

        /**
         * 应计未收利息（期次派生，与本金结算独立）。
         *
         * @return 应收利息
         */
        public BigDecimal interestReceivable() {
            return loan.interestReceivable();
        }

        /**
         * 累计已结算名义（回收+豁免）。
         *
         * @return 已结算名义
         */
        public BigDecimal settled() {
            return InterestAccrual.money(recoveredCents + waivedCents);
        }

        /**
         * 未释放成本。
         *
         * @return 未释放成本
         */
        public BigDecimal costOutstanding() {
            return Money.scale2(bookCost.subtract(costReleased));
        }

        /**
         * 按累计已结算名义占**原始名义**的比例释放成本（单次 HALF_UP，S=原名义时恰为 Q）。
         *
         * @param bookCost 成本 Q
         * @param originalNominal 受让时的原始名义 C（固定分母，不随未清本金变化）
         * @param settled  累计已结算名义
         * @return 应释放成本
         */
        public static BigDecimal releasedFor(BigDecimal bookCost, BigDecimal originalNominal,
                                             BigDecimal settled) {
            long costCents = InterestAccrual.cents(bookCost);
            long nominalCents = InterestAccrual.cents(originalNominal);
            long settledCents = InterestAccrual.cents(settled);
            if (nominalCents <= 0) {
                throw new IllegalArgumentException(Reasons.CLAIM_EXCEEDS_NOMINAL);
            }
            if (settledCents >= nominalCents) {
                return Money.scale2(bookCost);
            }
            BigInteger numerator = BigInteger.valueOf(costCents)
                    .multiply(BigInteger.valueOf(settledCents));
            BigInteger denominator = BigInteger.valueOf(nominalCents);
            BigInteger rounded = numerator.shiftLeft(1).add(denominator)
                    .divide(denominator.shiftLeft(1));
            return InterestAccrual.money(rounded.longValueExact());
        }
    }

    /**
     * 独立处置流程状态。
     *
     * @param bank       银行
     * @param authority  独立处置权主体
     * @param openedAtMs 开启时点
     * @param status     NONE / ACTIVE
     */
    public record Resolution(String bank, String authority, long openedAtMs, String status) {

        /** 校验银行、权限主体与状态。 */
        public Resolution {
            if (bank == null || bank.isBlank()) {
                throw new IllegalArgumentException(Reasons.UNKNOWN_BANK + ":" + bank);
            }
            if (authority == null || authority.isBlank()) {
                throw new IllegalArgumentException(Reasons.RESOLUTION_NOT_OPEN);
            }
            if (!BankRules.RESOLUTION_ACTIVE.equals(status)) {
                throw new IllegalArgumentException("RESOLUTION_STATUS_INVALID:" + status);
            }
        }
    }

    /**
     * 资产所有权转移记录：同一 assetKey 只能有一条，作为“谁在何时把哪项真实资产转给谁”的权威审计记录。
     *
     * <p>它不参与计价、不生成第二套总账，也不支持转售/折旧（后继）。PREMISES 用它证明归属已真实转移；
     * LOAN 用 {@code reference} 指向接管的 {@link AssignedClaim}。同一物权不得同时属于卖方与买方，
     * 因此 {@code assetKey} 是主键、重复转移在建记录时即被拒。</p>
     *
     * @param assetKey  真实资产键（PREMISES 账户或 {@code bank|borrower} 贷款键）
     * @param kind      PREMISES / LOAN
     * @param fromOwner 原所有权人（银行）
     * @param toOwner   买方
     * @param bookValue 出账时的真实账面（LOAN 含 allowance 与应收利息后的净额）
     * @param price     实际成交价
     * @param atMs      转移时点
     * @param reference 交易引用（处置命令的 claimId 或 collateralId）
     */
    public record AssetTransfer(String assetKey, String kind, String fromOwner, String toOwner,
                                BigDecimal bookValue, BigDecimal price, long atMs,
                                String reference) {

        /** 校验资产键、种类、双方、金额与引用。 */
        public AssetTransfer {
            if (assetKey == null || assetKey.isBlank()) {
                throw new IllegalArgumentException(Reasons.TRANSFER_INVALID + ":assetKey");
            }
            if (!BankRules.COLLATERAL_KINDS.contains(kind)) {
                throw new IllegalArgumentException(Reasons.TRANSFER_INVALID + ":kind:" + kind);
            }
            if (fromOwner == null || fromOwner.isBlank()) {
                throw new IllegalArgumentException(Reasons.TRANSFER_INVALID + ":from");
            }
            if (toOwner == null || toOwner.isBlank() || toOwner.equals(fromOwner)) {
                throw new IllegalArgumentException(Reasons.TRANSFER_INVALID + ":to");
            }
            bookValue = Money.scale2(Objects.requireNonNull(bookValue, "账面"));
            price = Money.scale2(Objects.requireNonNull(price, "成交价"));
            if (bookValue.signum() < 0 || price.signum() <= 0) {
                throw new IllegalArgumentException(Reasons.AMOUNT_NOT_POSITIVE);
            }
            if (atMs < 0) {
                throw new IllegalArgumentException(Reasons.TIME_REVERSED);
            }
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException(Reasons.TRANSFER_INVALID + ":reference");
            }
        }
    }

    /**
     * 命令记录：规范 body、原回执与是否接受。
     * @param body body 参数
     * @param receipt receipt 参数
     * @param accepted accepted 参数
     */
public record CommandRecord(String body, String receipt, boolean accepted) {
    }
    /** 一次性迁移来源。
     * @param owner owner 参数
     * @param bank bank 参数
     * @param amount amount 参数
     * @param source source 参数
     * @param consumed consumed 参数
     */
public record Origin(String owner, String bank, BigDecimal amount, String source,
                         boolean consumed) {
    }

    private final String runId;
    private final String configFingerprint;
    private final Map<String, Map<String, BigDecimal>> glByBank;
    private final Map<String, Loan> loans;
    private final Map<String, Facility> facilities;
    private final Map<String, BigDecimal> commitments;
    private final Map<String, Commitment> commitmentRecords;
    private final Map<String, DepositAccount> depositAccounts;
    private final Map<String, CommandRecord> commands;
    private final Map<String, Origin> origins;
    private final Map<String, BigDecimal> providerProvisions;
    private final Map<String, FacilityAccount> facilityAccounts;
    private final Map<String, Collateral> collaterals;
    private final Map<String, AssignedClaim> assignedClaims;
    private final Map<String, Resolution> resolutions;
    private final Map<String, PurchaseConsent> purchases;
    private final Map<String, DischargeConsent> consents;
    private final Map<String, ProviderOpening> providerOpenings;
    private final Map<String, AssetTransfer> assetTransfers;
    private final BigDecimal backing;
    private final BankChart chart;

    private BankState(String runId, String configFingerprint,
                      Map<String, Map<String, BigDecimal>> glByBank, Map<String, Loan> loans,
                      Map<String, Facility> facilities, Map<String, BigDecimal> commitments,
                      Map<String, Commitment> commitmentRecords,
                      Map<String, DepositAccount> depositAccounts,
                      Map<String, CommandRecord> commands, Map<String, Origin> origins,
                      Map<String, BigDecimal> providerProvisions,
                      Map<String, FacilityAccount> facilityAccounts,
                      Map<String, Collateral> collaterals,
                      Map<String, AssignedClaim> assignedClaims,
                      Map<String, Resolution> resolutions,
                      Map<String, PurchaseConsent> purchases,
                      Map<String, DischargeConsent> consents,
                      Map<String, ProviderOpening> providerOpenings,
                      Map<String, AssetTransfer> assetTransfers, BigDecimal backing,
                      BankChart chart) {
        this.runId = runId;
        this.configFingerprint = configFingerprint;
        this.glByBank = glByBank;
        this.loans = loans;
        this.facilities = facilities;
        this.commitments = commitments;
        this.commitmentRecords = commitmentRecords;
        this.depositAccounts = depositAccounts;
        this.commands = commands;
        this.origins = origins;
        this.providerProvisions = providerProvisions;
        this.facilityAccounts = facilityAccounts;
        this.collaterals = collaterals;
        this.assignedClaims = assignedClaims;
        this.resolutions = resolutions;
        this.purchases = purchases;
        this.consents = consents;
        this.providerOpenings = providerOpenings;
        this.assetTransfers = assetTransfers;
        this.backing = backing;
        this.chart = chart;
    }

    /**
     * 从可信快照恢复：chart 必须由可信 config 重建，不接受 JSON 自报 chart。
     *
     * @param runId 运行标识
     * @param configFingerprint 配置指纹
     * @param glByBank 各行总账
     * @param loans 贷款
     * @param facilities 旧设施
     * @param commitments 旧承诺汇总
     * @param commitmentRecords 明细承诺
     * @param depositAccounts 存款条款
     * @param commands 命令回执
     * @param origins 来源
     * @param providerProvisions 提供者准备
     * @param facilityAccounts 设施账户
     * @param collaterals 押品
     * @param assignedClaims 受让债权
     * @param resolutions 处置
     * @param purchases 买方许可
     * @param consents 解除许可
     * @param providerOpenings 提供者开局
     * @param assetTransfers 资产移交
     * @param backing 现金背书
     * @param chart 可信账表
     * @return 不可变状态
     */
    static BankState restore(String runId, String configFingerprint,
                             Map<String, Map<String, BigDecimal>> glByBank,
                             Map<String, Loan> loans, Map<String, Facility> facilities,
                             Map<String, BigDecimal> commitments,
                             Map<String, Commitment> commitmentRecords,
                             Map<String, DepositAccount> depositAccounts,
                             Map<String, CommandRecord> commands, Map<String, Origin> origins,
                             Map<String, BigDecimal> providerProvisions,
                             Map<String, FacilityAccount> facilityAccounts,
                             Map<String, Collateral> collaterals,
                             Map<String, AssignedClaim> assignedClaims,
                             Map<String, Resolution> resolutions,
                             Map<String, PurchaseConsent> purchases,
                             Map<String, DischargeConsent> consents,
                             Map<String, ProviderOpening> providerOpenings,
                             Map<String, AssetTransfer> assetTransfers,
                             BigDecimal backing, BankChart chart) {
        Map<String, Map<String, BigDecimal>> gl = new TreeMap<>();
        glByBank.forEach((bank, book) ->
                gl.put(bank, Collections.unmodifiableMap(new TreeMap<>(book))));
        return new BankState(runId, configFingerprint, Collections.unmodifiableMap(gl),
                sorted(loans), sorted(facilities), sorted(commitments), sorted(commitmentRecords),
                sorted(depositAccounts), sorted(commands), sorted(origins),
                sorted(providerProvisions), sorted(facilityAccounts), sorted(collaterals),
                sorted(assignedClaims), sorted(resolutions), sorted(purchases), sorted(consents),
                sorted(providerOpenings), sorted(assetTransfers), backing, chart);
    }

    private static <K, V> Map<K, V> sorted(Map<K, V> source) {
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }

    /**
     * 空白状态（未绑定运行，apply 会拒绝），使用参考账表。
     *
     * @return 空白状态
     */
    public static BankState empty() {
        return empty(BankChart.reference());
    }

    /**
     * 空白状态（未绑定运行，apply 会拒绝）。
     *
     * @param chart 账表实例
     * @return 空白状态
     */
    public static BankState empty(BankChart chart) {
        return new BankState(null, null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Money.zero(), chart);
    }

    /**
     * 账表实例。
     *
     * @return 账表
     */
    public BankChart chart() {
        return chart;
    }

    /**
     * 运行标识（未开局为 null）。
     * @return 运行标识（未开局为 null）
     */
    public String runId() {
        return runId;
    }

    /**
     * 绑定的档案指纹（未开局为 null）。
     * @return 绑定的档案指纹（未开局为 null）
     */
    public String configFingerprint() {
        return configFingerprint;
    }

    /**

     * 银行。
     * @param bank 银行
     * @param account 科目
     * @return 科目余额（缺省零）

     */
    public BigDecimal gl(String bank, String account) {
        Map<String, BigDecimal> book = glByBank.get(bank);
        if (book == null) {
            return Money.zero();
        }
        BigDecimal value = book.get(account);
        return value == null ? Money.zero() : value;
    }

    /**

     * 银行。
     * @param bank 银行
     * @param owner 客户
     * @return 客户存款正余额（债权的派生视图）

     */
    public BigDecimal depositOf(String bank, String owner) {
        return gl(bank, chart.depositAccountOf(owner)).negate();
    }

    /**
     * 各银行总账（只读）。
     * @return 各银行总账（只读）
     */
    public Map<String, Map<String, BigDecimal>> glByBank() {
        return glByBank;
    }

    /**

     * 银行。
     * @param bank 银行
     * @param borrower 借款人
     * @return 贷款（缺省空贷款）

     */
    public Loan loan(String bank, String borrower) {
        Loan loan = loans.get(chart.loanKeyOf(bank, borrower));
        return loan == null ? new Loan(List.of()) : loan;
    }

    /**
     * 贷款映射（键为 bank|borrower，只读）。
     * @return 贷款映射（键为 bank|borrower，只读）
     */
    public Map<String, Loan> loans() {
        return loans;
    }

    /**

     * 银行。
     * @param bank 银行
     * @return 救助设施（可能为 null）

     */
    public Facility facility(String bank) {
        return facilities.get(bank);
    }

    /**
     * 设施映射（只读）。
     * @return 设施映射（只读）
     */
    public Map<String, Facility> facilities() {
        return facilities;
    }

    /**

     * 银行。
     * @param bank 银行
     * @return 表外承诺余额（旧 SET_COMMITMENT 汇总，不含明细承诺）

     */
    public BigDecimal commitment(String bank) {
        BigDecimal value = commitments.get(bank);
        return value == null ? Money.zero() : value;
    }

    /**
     * 承诺映射（只读）。
     * @return 承诺映射（只读）
     */
    public Map<String, BigDecimal> commitments() {
        return commitments;
    }

    /**
     * 明细承诺映射（键为 bank|承诺号，只读）。
     * @return 明细承诺映射
     */
    public Map<String, Commitment> commitmentRecords() {
        return commitmentRecords;
    }

    /**
     * 单份明细承诺。
     *
     * @param bank 银行
     * @param commitment 承诺号
     * @return 记录（可能为 null）
     */
    public Commitment commitmentRecord(String bank, String commitment) {
        return commitmentRecords.get(chart.commitmentKeyOf(bank, commitment));
    }

    /**
     * 该行明细承诺的未提额度合计（派生，不含旧 SET_COMMITMENT 汇总）。
     *
     * @param bank 银行
     * @return 未提额度合计
     */
    public BigDecimal undrawnCommitments(String bank) {
        BigDecimal total = Money.zero();
        for (Map.Entry<String, Commitment> entry : commitmentRecords.entrySet()) {
            if (bank.equals(chart.bankOfCommitmentKey(entry.getKey()))
                    && COMMITMENT_OPEN.equals(entry.getValue().status())) {
                total = total.add(entry.getValue().undrawn());
            }
        }
        return Money.scale2(total);
    }

    /**
     * 存款账户政策映射（键为 bank|owner，只读）。
     * @return 存款账户政策映射
     */
    public Map<String, DepositAccount> depositAccounts() {
        return depositAccounts;
    }

    /**
     * 单份存款账户政策。
     *
     * @param bank 银行
     * @param owner 客户
     * @return 政策（可能为 null=尚未发生余额）
     */
    public DepositAccount depositAccount(String bank, String owner) {
        return depositAccounts.get(chart.depositTermsKeyOf(bank, owner));
    }

    /**
     * 借款人在各行应计未收利息合计（从同一状态派生，不复制债务账）。
     *
     * @param borrower 借款人
     * @return 应计未收利息
     */
    public BigDecimal accruedInterestOfBorrower(String borrower) {
        BigDecimal total = Money.zero();
        String suffix = "|" + borrower;
        for (Map.Entry<String, Loan> entry : loans.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                total = total.add(entry.getValue().interestReceivable());
            }
        }
        return Money.scale2(total);
    }

    /**

     * 命令。
     * @param commandId 命令
     * @return 记录（可能为 null）

     */
    public CommandRecord command(String commandId) {
        return commands.get(commandId);
    }

    /**
     * 命令映射（只读）。
     * @return 命令映射（只读）
     */
    public Map<String, CommandRecord> commands() {
        return commands;
    }

    /**

     * 来源。
     * @param originId 来源
     * @return 记录（可能为 null）

     */
    public Origin origin(String originId) {
        return origins.get(originId);
    }

    /**
     * 来源映射（只读）。
     * @return 来源映射（只读）
     */
    public Map<String, Origin> origins() {
        return origins;
    }

    /**

     * 提供者。
     * @param provider 提供者
     * @return 已计提准备

     */
    public BigDecimal providerProvision(String provider) {
        BigDecimal value = providerProvisions.get(provider);
        return value == null ? Money.zero() : value;
    }

    /**
     * 提供者准备映射（只读）。
     * @return 提供者准备映射（只读）
     */
    public Map<String, BigDecimal> providerProvisions() {
        return providerProvisions;
    }

    /**
     * 设施账户映射（键 {@code bank|authorizationId}，只读）。
     *
     * @return 设施账户映射
     */
    public Map<String, FacilityAccount> facilityAccounts() {
        return facilityAccounts;
    }

    /**
     * 单份设施账户。
     *
     * @param bank 银行
     * @param authorizationId 授权标识
     * @return 账户（可能为 null）
     */
    public FacilityAccount facilityAccount(String bank, String authorizationId) {
        return facilityAccounts.get(chart.facilityKeyOf(bank, authorizationId));
    }

    /**
     * 某行全部设施账户（只读视图）。
     *
     * @param bank 银行
     * @return 按授权标识排序的账户
     */
    public Map<String, FacilityAccount> facilityAccountsOf(String bank) {
        Map<String, FacilityAccount> result = new TreeMap<>();
        for (Map.Entry<String, FacilityAccount> entry : facilityAccounts.entrySet()) {
            if (bank.equals(chart.bankOfFacilityKey(entry.getKey()))) {
                result.put(entry.getValue().authorizationId(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 押品映射（键为押品标识，只读）。
     *
     * @return 押品映射
     */
    public Map<String, Collateral> collaterals() {
        return collaterals;
    }

    /**
     * 单份押品。
     *
     * @param collateralId 押品标识
     * @return 押品（可能为 null）
     */
    public Collateral collateral(String collateralId) {
        return collaterals.get(collateralId);
    }

    /**
     * 受让债权映射（键为转让标识，只读）。
     *
     * @return 受让债权映射
     */
    public Map<String, AssignedClaim> assignedClaims() {
        return assignedClaims;
    }

    /**
     * 单份受让债权。
     *
     * @param claimId 转让标识
     * @return 债权（可能为 null）
     */
    public AssignedClaim assignedClaim(String claimId) {
        return assignedClaims.get(claimId);
    }

    /**
     * 处置流程映射（键为银行，只读）。
     *
     * @return 处置流程映射
     */
    public Map<String, Resolution> resolutions() {
        return resolutions;
    }

    /**
     * 某行处置流程状态（未开启返回 null）。
     *
     * @param bank 银行
     * @return 处置流程
     */
    public Resolution resolution(String bank) {
        return resolutions.get(bank);
    }

    /**
     * 买方受让许可映射（只读）。
     *
     * @return 许可映射
     */
    public Map<String, PurchaseConsent> purchases() {
        return purchases;
    }

    /**
     * 单份买方受让许可。
     *
     * @param purchaseId 许可标识
     * @return 记录（可能为 null）
     */
    public PurchaseConsent purchase(String purchaseId) {
        return purchases.get(purchaseId);
    }

    /**
     * 债权人解除许可映射（只读）。
     *
     * @return 许可映射
     */
    public Map<String, DischargeConsent> consents() {
        return consents;
    }

    /**
     * 单份解除许可。
     *
     * @param consentId 许可标识
     * @return 记录（可能为 null）
     */
    public DischargeConsent consent(String consentId) {
        return consents.get(consentId);
    }

    /**
     * 提供者开局事实映射（只读）。
     *
     * @return 提供者→开局事实
     */
    public Map<String, ProviderOpening> providerOpenings() {
        return providerOpenings;
    }

    /**
     * 单份提供者开局事实。
     *
     * @param provider 提供者
     * @return 记录（可能为 null=未声明）
     */
    public ProviderOpening providerOpening(String provider) {
        return providerOpenings.get(provider);
    }

    /**
     * 资产所有权转移记录（键为 assetKey，只读）。
     *
     * @return 转移记录映射
     */
    public Map<String, AssetTransfer> assetTransfers() {
        return assetTransfers;
    }

    /**
     * 单份资产转移记录。
     *
     * @param assetKey 真实资产键
     * @return 记录（可能为 null=未转移）
     */
    public AssetTransfer assetTransfer(String assetKey) {
        return assetTransfers.get(assetKey);
    }

    /**
     * 借款人**当前未清债务**：银行在账贷款合同额 + 受让债权未清本金（不按原始面值汇总）。
     *
     * <p>受让记录的历史原始名义在 {@link AssignedClaim#originalNominal()}；终止状态与利息未清分开：
     * 本金结清的 claim 仍可能有应收利息（{@link AssignedClaim#interestReceivable()}）。</p>
     *
     * @param borrower 借款人
     * @return 未清债合计
     */
    public BigDecimal outstandingDebtOf(String borrower) {
        BigDecimal total = Money.zero();
        String suffix = "|" + borrower;
        for (Map.Entry<String, Loan> entry : loans.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                total = total.add(entry.getValue().contract());
            }
        }
        for (AssignedClaim claim : assignedClaims.values()) {
            if (claim.borrower().equals(borrower)) {
                total = total.add(claim.nominal());
            }
        }
        return Money.scale2(total);
    }

    /**
     * 借款人受让债权的原始名义合计（历史面值，仅用于报告；不等于未清债）。
     *
     * @param borrower 借款人
     * @return 受让原始名义合计
     */
    public BigDecimal assignedOriginalNominalOf(String borrower) {
        BigDecimal total = Money.zero();
        for (AssignedClaim claim : assignedClaims.values()) {
            if (claim.borrower().equals(borrower)) {
                total = total.add(claim.originalNominal());
            }
        }
        return Money.scale2(total);
    }

    /**
     * 账面权益（含当期损益与处置专用权益，按总账符号推导）。
     *
     * @param bank 银行
     * @return 账面权益
     */
    public BigDecimal bookEquity(String bank) {
        BigDecimal total = Money.zero();
        for (String account : chart.equity()) {
            total = total.add(gl(bank, account));
        }
        for (String account : chart.income()) {
            total = total.add(gl(bank, account));
        }
        for (String account : chart.expense()) {
            total = total.add(gl(bank, account));
        }
        return Money.scale2(total.negate());
    }

    /**
     * 处置专用权益（不可分配，不并入 CET1）。
     *
     * @param bank 银行
     * @return 不可分配额
     */
    public BigDecimal nonDistributable(String bank) {
        return Money.scale2(gl(bank, BankChart.DISCHARGE_EQUITY).negate());
    }

    /**
     * 结算部门外部背书记录。
     * @return 结算部门外部背书记录
     */
    public BigDecimal backing() {
        return backing;
    }

    /**
     * 可写草稿（深拷贝，供纯核候选计算）。
     * @return 可写草稿（深拷贝，供纯核候选计算）
     */
    public Draft draft() {
        Map<String, Map<String, BigDecimal>> gl = new LinkedHashMap<>();
        glByBank.forEach((bank, book) -> gl.put(bank, new LinkedHashMap<>(book)));
        return new Draft(runId, configFingerprint, gl, new LinkedHashMap<>(loans),
                new LinkedHashMap<>(facilities), new LinkedHashMap<>(commitments),
                new LinkedHashMap<>(commitmentRecords), new LinkedHashMap<>(depositAccounts),
                new LinkedHashMap<>(commands), new LinkedHashMap<>(origins),
                new LinkedHashMap<>(providerProvisions), new LinkedHashMap<>(facilityAccounts),
                new LinkedHashMap<>(collaterals), new LinkedHashMap<>(assignedClaims),
                new LinkedHashMap<>(resolutions), new LinkedHashMap<>(purchases),
                new LinkedHashMap<>(consents), new LinkedHashMap<>(providerOpenings),
                new LinkedHashMap<>(assetTransfers), backing, chart);
    }

    /** 可变草稿：只在纯核内部使用，freeze 后不可再改。 */
    public static final class Draft {
        private String runId;
        private String configFingerprint;
        private final Map<String, Map<String, BigDecimal>> gl;
        private final Map<String, Loan> loans;
        private final Map<String, Facility> facilities;
        private final Map<String, BigDecimal> commitments;
        private final Map<String, Commitment> commitmentRecords;
        private final Map<String, DepositAccount> depositAccounts;
        private final Map<String, CommandRecord> commands;
        private final Map<String, Origin> origins;
        private final Map<String, BigDecimal> providerProvisions;
        private final Map<String, FacilityAccount> facilityAccounts;
        private final Map<String, Collateral> collaterals;
        private final Map<String, AssignedClaim> assignedClaims;
        private final Map<String, Resolution> resolutions;
        private final Map<String, PurchaseConsent> purchases;
        private final Map<String, DischargeConsent> consents;
        private final Map<String, ProviderOpening> providerOpenings;
        private final Map<String, AssetTransfer> assetTransfers;
        private final BankChart chart;
        private BigDecimal backing;

        private Draft(String runId, String configFingerprint,
                      Map<String, Map<String, BigDecimal>> gl, Map<String, Loan> loans,
                      Map<String, Facility> facilities, Map<String, BigDecimal> commitments,
                      Map<String, Commitment> commitmentRecords,
                      Map<String, DepositAccount> depositAccounts,
                      Map<String, CommandRecord> commands, Map<String, Origin> origins,
                      Map<String, BigDecimal> providerProvisions,
                      Map<String, FacilityAccount> facilityAccounts,
                      Map<String, Collateral> collaterals,
                      Map<String, AssignedClaim> assignedClaims,
                      Map<String, Resolution> resolutions,
                      Map<String, PurchaseConsent> purchases,
                      Map<String, DischargeConsent> consents,
                      Map<String, ProviderOpening> providerOpenings,
                      Map<String, AssetTransfer> assetTransfers, BigDecimal backing,
                      BankChart chart) {
            this.runId = runId;
            this.configFingerprint = configFingerprint;
            this.chart = chart;
            this.gl = gl;
            this.loans = loans;
            this.facilities = facilities;
            this.commitments = commitments;
            this.commitmentRecords = commitmentRecords;
            this.depositAccounts = depositAccounts;
            this.commands = commands;
            this.origins = origins;
            this.providerProvisions = providerProvisions;
            this.facilityAccounts = facilityAccounts;
            this.collaterals = collaterals;
            this.assignedClaims = assignedClaims;
            this.resolutions = resolutions;
            this.purchases = purchases;
            this.consents = consents;
            this.providerOpenings = providerOpenings;
            this.assetTransfers = assetTransfers;
            this.backing = backing;
        }

        /**

     * 运行标识。
         * @param value 运行标识
         * @param fingerprint 档案指纹

     */
        public void bindRun(String value, String fingerprint) {
            if (value == null || value.isBlank() || fingerprint == null || fingerprint.isBlank()) {
                throw new BankException(Reasons.RUN_BINDING_INVALID);
            }
            if (runId != null) {
                if (runId.equals(value) && configFingerprint != null
                        && configFingerprint.equals(fingerprint)) {
                    return;
                }
                throw new BankException(Reasons.RUN_ALREADY_BOUND + ":" + runId);
            }
            runId = value;
            configFingerprint = fingerprint;
        }

        /**

     * 银行。
         * @param bank 银行
         * @return 该行可变总账（不存在则新建）

     */
        public Map<String, BigDecimal> book(String bank) {
            return gl.computeIfAbsent(bank, key -> new LinkedHashMap<>());
        }

        /**

     * 银行。
         * @param bank 银行
         * @param borrower 借款人
         * @return 贷款口径

     */
        public Loan loan(String bank, String borrower) {
            Loan loan = loans.get(chart.loanKeyOf(bank, borrower));
            return loan == null ? new Loan(List.of()) : loan;
        }

        /**

     * 银行。
         * @param bank 银行
         * @param borrower 借款人
         * @param loan 新的贷款（期次列表）

     */
        public void putLoan(String bank, String borrower, Loan loan) {
            loans.put(chart.loanKeyOf(bank, borrower), loan);
        }

        /**

     * 银行。
         * @param bank 银行
         * @return 承诺余额

     */
        public BigDecimal commitment(String bank) {
            BigDecimal value = commitments.get(bank);
            return value == null ? Money.zero() : value;
        }

        /**
         * 明细承诺（只读）。
         *
         * @param bank 银行
         * @param commitment 承诺号
         * @return 记录（可能为 null）
         */
        public Commitment commitmentRecord(String bank, String commitment) {
            return commitmentRecords.get(chart.commitmentKeyOf(bank, commitment));
        }

        /**
         * 写入明细承诺。
         *
         * @param bank 银行
         * @param commitment 承诺号
         * @param record 记录
         */
        public void putCommitmentRecord(String bank, String commitment, Commitment record) {
            commitmentRecords.put(chart.commitmentKeyOf(bank, commitment), record);
        }

        /**
         * 明细承诺映射（只读视图）。
         *
         * @return 明细承诺映射
         */
        public Map<String, Commitment> commitmentRecords() {
            return Collections.unmodifiableMap(commitmentRecords);
        }

        /**
         * 存款账户政策（只读）。
         *
         * @param bank 银行
         * @param owner 客户
         * @return 政策（可能为 null）
         */
        public DepositAccount depositAccount(String bank, String owner) {
            return depositAccounts.get(chart.depositTermsKeyOf(bank, owner));
        }

        /**
         * 写入存款账户政策。
         *
         * @param bank 银行
         * @param owner 客户
         * @param account 政策
         */
        public void putDepositAccount(String bank, String owner, DepositAccount account) {
            depositAccounts.put(chart.depositTermsKeyOf(bank, owner), account);
        }

        /**
         * 移除存款账户政策（仅用于负债被合法解除后清理标记）。
         *
         * @param bank 银行
         * @param owner 客户
         */
        public void removeDepositAccount(String bank, String owner) {
            depositAccounts.remove(chart.depositTermsKeyOf(bank, owner));
        }

        /**
         * 某行全部总账科目余额（只读视图，用于清算分配）。
         *
         * @param bank 银行
         * @return 科目→余额
         */
        public Map<String, BigDecimal> glAccounts(String bank) {
            Map<String, BigDecimal> book = gl.get(bank);
            return book == null ? Map.of() : Collections.unmodifiableMap(book);
        }

        /**
         * 存款账户政策映射（只读视图）。
         *
         * @return 存款账户政策映射
         */
        public Map<String, DepositAccount> depositAccounts() {
            return Collections.unmodifiableMap(depositAccounts);
        }

        /**
         * 设施映射（只读视图）。
         * @return 设施映射（只读视图）
         */
        public Map<String, Facility> facilities() {
            return Collections.unmodifiableMap(facilities);
        }

        /**

     * 银行。
         * @param bank 银行
         * @return 救助设施（可能为 null）

     */
        public Facility facility(String bank) {
            return facilities.get(bank);
        }

        /**

     * 来源。
         * @param originId 来源
         * @return 记录（可能为 null）

     */
        public Origin origin(String originId) {
            return origins.get(originId);
        }

        /**

     * 银行。
         * @param bank 银行
         * @param account 科目
         * @param delta 增量（借正贷负）

     */
        public void addGl(String bank, String account, BigDecimal delta) {
            book(bank).merge(account, delta, BigDecimal::add);
        }

        /**

     * 银行。
         * @param bank 银行
         * @param account 科目
         * @return 余额

     */
        public BigDecimal gl(String bank, String account) {
            Map<String, BigDecimal> book = gl.get(bank);
            BigDecimal value = book == null ? null : book.get(account);
            return value == null ? Money.zero() : value;
        }

        /**

     * 银行。
         * @param bank 银行
         * @param account 科目
         * @param value 绝对余额（借正贷负）

     */
        public void setGl(String bank, String account, BigDecimal value) {
            book(bank).put(account, Money.scale2(value));
        }

        /**

     * 提供者。
         * @param provider 提供者
         * @param value 累计计提

     */
        public void putProviderProvision(String provider, BigDecimal value) {
            providerProvisions.put(provider, Money.scale2(value));
        }

        /**

     * 提供者。
         * @param provider 提供者
         * @return 累计计提

     */
        public BigDecimal providerProvision(String provider) {
            BigDecimal value = providerProvisions.get(provider);
            return value == null ? Money.zero() : value;
        }

        /**
         * 单份设施账户。
         *
         * @param bank 银行
         * @param authorizationId 授权标识
         * @return 账户（可能为 null）
         */
        public FacilityAccount facilityAccount(String bank, String authorizationId) {
            return facilityAccounts.get(chart.facilityKeyOf(bank, authorizationId));
        }

        /**
         * 写入设施账户。
         *
         * @param account 账户
         */
        public void putFacilityAccount(FacilityAccount account) {
            facilityAccounts.put(chart.facilityKeyOf(account.bank(),
                    account.authorizationId()), account);
        }

        /**
         * 设施账户映射（只读视图）。
         *
         * @return 设施账户映射
         */
        public Map<String, FacilityAccount> facilityAccounts() {
            return Collections.unmodifiableMap(facilityAccounts);
        }

        /**
         * 单份押品。
         *
         * @param collateralId 押品标识
         * @return 押品（可能为 null）
         */
        public Collateral collateral(String collateralId) {
            return collaterals.get(collateralId);
        }

        /**
         * 写入押品。
         *
         * @param collateral 押品
         */
        public void putCollateral(Collateral collateral) {
            collaterals.put(collateral.collateralId(), collateral);
        }

        /**
         * 押品映射（只读视图）。
         *
         * @return 押品映射
         */
        public Map<String, Collateral> collaterals() {
            return Collections.unmodifiableMap(collaterals);
        }

        /**
         * 单份受让债权。
         *
         * @param claimId 转让标识
         * @return 债权（可能为 null）
         */
        public AssignedClaim assignedClaim(String claimId) {
            return assignedClaims.get(claimId);
        }

        /**
         * 写入受让债权。
         *
         * @param claim 债权
         */
        public void putAssignedClaim(AssignedClaim claim) {
            assignedClaims.put(claim.claimId(), claim);
        }

        /**
         * 受让债权映射（只读视图）。
         *
         * @return 受让债权映射
         */
        public Map<String, AssignedClaim> assignedClaims() {
            return Collections.unmodifiableMap(assignedClaims);
        }

        /**
         * 移除贷款（转让后不再由银行持有）。
         *
         * @param bank 银行
         * @param borrower 借款人
         */
        public void removeLoan(String bank, String borrower) {
            loans.remove(chart.loanKeyOf(bank, borrower));
        }

        /**
         * 处置流程（可能为 null）。
         *
         * @param bank 银行
         * @return 处置流程
         */
        public Resolution resolution(String bank) {
            return resolutions.get(bank);
        }

        /**
         * 写入处置流程。
         *
         * @param resolution 处置流程
         */
        public void putResolution(Resolution resolution) {
            resolutions.put(resolution.bank(), resolution);
        }

        /**
         * 单份买方许可。
         *
         * @param purchaseId 许可标识
         * @return 记录（可能为 null）
         */
        public PurchaseConsent purchase(String purchaseId) {
            return purchases.get(purchaseId);
        }

        /**
         * 写入买方许可。
         *
         * @param purchase 记录
         */
        public void putPurchase(PurchaseConsent purchase) {
            purchases.put(purchase.purchaseId(), purchase);
        }

        /**
         * 买方许可映射（只读视图）。
         *
         * @return 许可映射
         */
        public Map<String, PurchaseConsent> purchases() {
            return Collections.unmodifiableMap(purchases);
        }

        /**
         * 单份解除许可。
         *
         * @param consentId 许可标识
         * @return 记录（可能为 null）
         */
        public DischargeConsent consent(String consentId) {
            return consents.get(consentId);
        }

        /**
         * 写入解除许可。
         *
         * @param consent 记录
         */
        public void putConsent(DischargeConsent consent) {
            consents.put(consent.consentId(), consent);
        }

        /**
         * 提供者开局事实（只读视图）。
         *
         * @return 提供者→开局事实
         */
        public Map<String, ProviderOpening> providerOpenings() {
            return Collections.unmodifiableMap(providerOpenings);
        }

        /**
         * 写入提供者开局事实。
         *
         * @param opening 记录
         */
        public void putProviderOpening(ProviderOpening opening) {
            providerOpenings.put(opening.provider(), opening);
        }

        /**
         * 单份资产转移记录。
         *
         * @param assetKey 真实资产键
         * @return 记录（可能为 null）
         */
        public AssetTransfer assetTransfer(String assetKey) {
            return assetTransfers.get(assetKey);
        }

        /**
         * 写入资产转移记录（同一 assetKey 只能一次，重复即业务拒绝）。
         *
         * @param transfer 记录
         */
        public void putAssetTransfer(AssetTransfer transfer) {
            if (assetTransfers.containsKey(transfer.assetKey())) {
                throw new BankException(Reasons.TRANSFER_DUPLICATE + ":"
                        + transfer.assetKey());
            }
            assetTransfers.put(transfer.assetKey(), transfer);
        }

        /**
         * 资产转移记录（只读视图）。
         *
         * @return 记录映射
         */
        public Map<String, AssetTransfer> assetTransfers() {
            return Collections.unmodifiableMap(assetTransfers);
        }

        /**

     * 银行。
         * @param bank 银行
         * @param facility 设施

     */
        public void putFacility(String bank, Facility facility) {
            facilities.put(bank, facility);
        }

        /**

     * 银行。
         * @param bank 银行
         * @param value 承诺余额

     */
        public void putCommitment(String bank, BigDecimal value) {
            commitments.put(bank, value);
        }

        /**

     * 命令。
         * @param commandId 命令
         * @param record 记录

     */
        public void putCommand(String commandId, CommandRecord record) {
            commands.put(commandId, record);
        }

        /**

     * 来源。
         * @param originId 来源
         * @param origin 记录

     */
        public void putOrigin(String originId, Origin origin) {
            origins.put(originId, origin);
        }

        /**

     * 背书。
         * @param value 背书

     */
        public void backing(BigDecimal value) {
            backing = value;
        }

        /**
         * 冻结为不可变状态（深拷贝）。
         * @return 冻结为不可变状态（深拷贝）
         */
        public BankState freeze() {
            Map<String, Map<String, BigDecimal>> frozen = new TreeMap<>();
            gl.forEach((bank, book) -> {
                Map<String, BigDecimal> copy = new TreeMap<>();
                book.forEach((account, value) -> {
                    if (value != null && value.signum() != 0) {
                        copy.put(account, Money.scale2(value));
                    }
                });
                frozen.put(bank, Collections.unmodifiableMap(copy));
            });
            return new BankState(runId, configFingerprint, Collections.unmodifiableMap(frozen),
                    Collections.unmodifiableMap(new TreeMap<>(loans)),
                    Collections.unmodifiableMap(new TreeMap<>(facilities)),
                    Collections.unmodifiableMap(new TreeMap<>(commitments)),
                    Collections.unmodifiableMap(new TreeMap<>(commitmentRecords)),
                    Collections.unmodifiableMap(new TreeMap<>(depositAccounts)),
                    Collections.unmodifiableMap(new TreeMap<>(commands)),
                    Collections.unmodifiableMap(new TreeMap<>(origins)),
                    Collections.unmodifiableMap(new TreeMap<>(providerProvisions)),
                    Collections.unmodifiableMap(new TreeMap<>(facilityAccounts)),
                    Collections.unmodifiableMap(new TreeMap<>(collaterals)),
                    Collections.unmodifiableMap(new TreeMap<>(assignedClaims)),
                    Collections.unmodifiableMap(new TreeMap<>(resolutions)),
                    Collections.unmodifiableMap(new TreeMap<>(purchases)),
                    Collections.unmodifiableMap(new TreeMap<>(consents)),
                    Collections.unmodifiableMap(new TreeMap<>(providerOpenings)),
                    Collections.unmodifiableMap(new TreeMap<>(assetTransfers)),
                    Money.scale2(backing), chart);
        }

        /**
         * 账表实例。
         *
         * @return 账表
         */
        public BankChart chart() {
            return chart;
        }
    }
}
