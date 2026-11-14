package edu.cufe.auction.world.bank;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.cufe.auction.world.CashLedger;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 银行存款/取现/支付与双边借贷的动作层：只产出候选，不提交、不持久、不持有余额。
 *
 * <p>全部资金动作复用已接受的 {@link BankModel}（DEPOSIT/WITHDRAW/TRANSFER/LOAN_ORIGINATE/
 * LOAN_REPAY/ACCRUE_LOAN/PAY_LOAN_INTEREST）与 {@link PaymentPort}，本类只做三件事：
 * 身份与双边授权检查、贷款请求元数据的纯转换、把结果打包成不可变候选。</p>
 *
 * <p>动作方必须由调用方从真实任职解析后传入 {@link Actor}；本类不接受"任意 actor 字符串"，
 * 也不把任何报告文字当作资金变动依据。贷款条款只在双方逐字段一致时成立，银行不能单方改价。</p>
 */
public final class BankActions {

    private BankActions() {
    }

    /** 动作种类。 */
    public enum Kind {
        /** 现金存入自己的存款。 */ DEPOSIT,
        /** 从自己的存款取现。 */ WITHDRAW,
        /** 支付：现金或存款到对方现金或存款。 */ PAY,
        /** 借款人提出贷款条款。 */ LOAN_REQUEST,
        /** 借款人接受自己提出的条款（或银行批准的同一条款）。 */ LOAN_ACCEPT,
        /** 银行经理按同一条款批准。 */ LOAN_APPROVE,
        /** 借款人在双方一致后提款。 */ LOAN_DRAW,
        /** 借款人还本。 */ LOAN_REPAY,
        /** 按合同到期累计利息（系统任务）。 */ LOAN_ACCRUE,
        /** 借款人付息。 */ LOAN_PAY_INTEREST
    }

    /** 贷款请求状态。 */
    public enum LoanStatus {
        /** 已提出。 */ PROPOSED,
        /** 借款人已接受条款。 */ ACCEPTED,
        /** 银行经理已按同一条款批准。 */ APPROVED,
        /** 已提款。 */ DRAWN,
        /** 已全部还清。 */ CLOSED
    }

    /**
     * 动作方：由 World 从真实任职解析后传入，不由动作请求自报。
     *
     * @param partyKey   真实 party key（客户或银行经理）
     * @param ownerId    该 party 的真实财产主体
     * @param bankManager 是否是银行经理
     * @param managedBank 若是经理，其所属银行 key；否则 null
     */
    public record Actor(String partyKey, String ownerId, boolean bankManager, String managedBank) {

        /** 校验必填。 */
        public Actor {
            Objects.requireNonNull(partyKey, "partyKey");
            Objects.requireNonNull(ownerId, "ownerId");
            if (bankManager && (managedBank == null || managedBank.isBlank())) {
                throw new IllegalArgumentException("银行经理必须绑定银行");
            }
        }

        /**
         * 客户动作方。
         *
         * @param partyKey party key
         * @param ownerId  真实财产主体
         * @return 客户动作方
         */
        public static Actor customer(String partyKey, String ownerId) {
            return new Actor(partyKey, ownerId, false, null);
        }

        /**
         * 银行经理动作方。
         *
         * @param partyKey party key
         * @param ownerId  真实财产主体
         * @param bankKey  其所属银行
         * @return 经理动作方
         */
        public static Actor manager(String partyKey, String ownerId, String bankKey) {
            return new Actor(partyKey, ownerId, true, bankKey);
        }

        /**
         * 系统动作方（只用于到期计息/合同日程，World 只从合同到期时间创建）。
         *
         * @return 系统动作方
         */
        public static Actor system() {
            return new Actor("__system__", "__system__", false, null);
        }
    }

    /**
     * 一条银行动作请求（元数据；不含余额）。
     *
     * @param requestId      请求标识（幂等）
     * @param kind           动作种类
     * @param bankKey        银行 key
     * @param customerKey    客户/借款人 party key
     * @param payeeToken     支付收款桶（PAY）
     * @param sourceToken    支付出款桶（PAY）
     * @param amount         金额（正、两位小数）
     * @param rateU          贷款利率（定标 1e6；其他动作为 0）
     * @param tenorMs        贷款期限（毫秒；其他动作为 0）
     * @param purpose        贷款用途（贷款类必填）
     * @param loanId         贷款请求标识（非 LOAN_REQUEST 时指向已有请求；可等于 requestId）
     * @param toMs           计息时点（LOAN_ACCRUE）
     */
    public record Request(String requestId, Kind kind, String bankKey, String customerKey,
                          String sourceToken, String payeeToken, BigDecimal amount, long rateU,
                          long tenorMs, String purpose, String loanId, long toMs) {

        /** 校验必要字段与符号。 */
        public Request {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(bankKey, "bankKey");
            if (requestId.isBlank() || bankKey.isBlank()) {
                throw new IllegalArgumentException("请求标识与银行不能为空");
            }
            if (amount == null || amount.signum() <= 0) {
                throw new IllegalArgumentException("金额必须为正");
            }
            amount = Money.business(amount);
            if (rateU < 0 || tenorMs < 0 || toMs < 0) {
                throw new IllegalArgumentException("利率/期限/时点不能为负");
            }
            sourceToken = sourceToken == null ? "" : sourceToken;
            payeeToken = payeeToken == null ? "" : payeeToken;
            purpose = purpose == null ? "" : purpose;
            loanId = loanId == null || loanId.isBlank() ? requestId : loanId;
            if (kind == Kind.LOAN_REQUEST && purpose.isBlank()) {
                throw new IllegalArgumentException("贷款请求必须写明用途");
            }
        }
    }

    /**
     * 贷款请求元数据：条款冻结后不许被银行改写。
     *
     * <p><b>不是第二本金账</b>：金额与条款以 {@link BankState.Tranche} 为唯一权威；
     * 这里的 {@code amount/rateU/tenorMs/purpose} 只是合同提出时冻结的<b>条款事实</b>，
     * {@code drawn} 与 {@code status} 每帧从真实期次派生（{@code contract()} 与
     * {@code interestReceivable()}），不得独立维护余额。</p>
     *
     * @param loanId        请求标识
     * @param bankKey       银行
     * @param borrowerKey   借款人 party key
     * @param amount        合同金额（冻结条款）
     * @param rateU         利率（冻结条款）
     * @param tenorMs       期限（冻结条款）
     * @param purpose       用途（冻结条款）
     * @param status        状态（由期次派生）
     * @param accepted      借款人是否已接受该条款
     * @param approvedBy    批准经理 party key（null 表示未批准）
     * @param drawn         该合同期次当前未还本金（由期次派生）
     * @param trancheId     绑定的真实期次标识；未提款时为 null
     */
    public record Loan(String loanId, String bankKey, String borrowerKey, BigDecimal amount,
                       long rateU, long tenorMs, String purpose, LoanStatus status,
                       boolean accepted, String approvedBy, BigDecimal drawn,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String trancheId) {

        /**
         * 兼容旧档的十一参构造：没有期次定位（读取时按唯一期次解析或拒绝）。
         */
        public Loan(String loanId, String bankKey, String borrowerKey, BigDecimal amount,
                    long rateU, long tenorMs, String purpose, LoanStatus status,
                    boolean accepted, String approvedBy, BigDecimal drawn) {
            this(loanId, bankKey, borrowerKey, amount, rateU, tenorMs, purpose, status, accepted,
                    approvedBy, drawn, null);
        }

        /**
         * 条款是否与另一条请求逐字段相同（银行不得单方改价；期次与状态不参与）。
         *
         * @param other 另一条贷款
         * @return 完全相同返回 true
         */
        public boolean sameTerms(Loan other) {
            return other != null && bankKey.equals(other.bankKey)
                    && borrowerKey.equals(other.borrowerKey)
                    && amount.compareTo(other.amount) == 0
                    && rateU == other.rateU && tenorMs == other.tenorMs
                    && purpose.equals(other.purpose);
        }

        Loan withStatus(LoanStatus next, boolean nextAccepted, String nextApprovedBy,
                        BigDecimal nextDrawn) {
            return new Loan(loanId, bankKey, borrowerKey, amount, rateU, tenorMs, purpose, next,
                    nextAccepted, nextApprovedBy, nextDrawn, trancheId);
        }

        /** 绑定真实期次并派生本金。 */
        Loan withTranche(String nextTrancheId, BigDecimal nextDrawn, LoanStatus nextStatus) {
            return new Loan(loanId, bankKey, borrowerKey, amount, rateU, tenorMs, purpose,
                    nextStatus, accepted, approvedBy, nextDrawn, nextTrancheId);
        }
    }

    /**
     * 贷款请求台账（只存元数据；资金只在银行三态与现金账户里）。
     *
     * @param loans 贷款请求：标识 → 条款与状态
     */
    public record Book(Map<String, Loan> loans) {

        /** 校验并冻结。 */
        public Book {
            Map<String, Loan> copy = new TreeMap<>();
            if (loans != null) {
                copy.putAll(loans);
            }
            loans = Map.copyOf(copy);
        }

        /** @return 空台账 */
        public static Book empty() {
            return new Book(Map.of());
        }

        /**
         * 某条贷款。
         *
         * @param loanId 标识
         * @return 贷款或 null
         */
        public Loan loan(String loanId) {
            return loans.get(loanId);
        }

        Book put(Loan loan) {
            Map<String, Loan> next = new LinkedHashMap<>(loans);
            next.put(loan.loanId(), loan);
            return new Book(next);
        }
    }

    /**
     * 一次动作的结果；拒绝时经济字段与入参相同。
     *
     * @param accepted 是否成立
     * @param reason   结果码
     * @param bank     候选银行状态
     * @param accounts 候选现金账户
     * @param book     候选托管明细账
     * @param loans    候选贷款台账
     * @param receipt  回执（拒绝也有）
     */
    public record Outcome(boolean accepted, String reason, BankState bank,
                          Map<String, CashLedger.CashAccount> accounts, CustodyBook book,
                          Book loans, String receipt) {
    }

    /**
     * 执行一条动作。
     *
     * <p>没有世界侧冻结快照时按 {@link CashFreeze#none()} 执行（旧口径）：该运行确知没有任何
     * 合法买单冻结时才是正确输入。</p>
     *
     * @param bank     当前银行状态
     * @param accounts 当前现金账户
     * @param book     当前托管明细账
     * @param loans    当前贷款台账
     * @param config   冻结档案
     * @param ctx      运行上下文
     * @param request  动作请求
     * @param actor    真实动作方
     * @return 候选结果
     */
    public static Outcome apply(BankState bank, Map<String, CashLedger.CashAccount> accounts,
                                CustodyBook book, Book loans, BankConfig config, BankContext ctx,
                                Request request, Actor actor) {
        return apply(bank, accounts, book, loans, config, ctx, request, actor, CashFreeze.none(),
                false);
    }

    /**
     * 执行一条动作，并携带世界侧派生的真实冻结快照（B7a 口径，托管付款仍拒绝）。
     *
     * @param bank     当前银行状态
     * @param accounts 当前现金账户
     * @param book     当前托管明细账
     * @param loans    当前贷款台账
     * @param config   冻结档案
     * @param ctx      运行上下文
     * @param request  动作请求
     * @param actor    真实动作方
     * @param frozen   受信冻结快照（无冻结传 {@link CashFreeze#none()}）
     * @return 候选结果
     */
    public static Outcome apply(BankState bank, Map<String, CashLedger.CashAccount> accounts,
                                CustodyBook book, Book loans, BankConfig config, BankContext ctx,
                                Request request, Actor actor, CashFreeze frozen) {
        return apply(bank, accounts, book, loans, config, ctx, request, actor, frozen, false);
    }

    /**
     * 执行一条动作，并显式声明是否允许<b>托管桶付款</b>（B7b）。
     *
     * <p>{@code custodyTrading} 为真时，{@code PAY} 的源或目标为
     * {@code CUSTODY|托管人|客户} 才走 {@link CustodyPort#pay}；为假时与 B7a 逐字一致
     * （由 {@link PaymentPort} 明确拒绝 {@code FUND_CUSTODY_UNSUPPORTED}）。</p>
     *
     * @param bank           当前银行状态
     * @param accounts       当前现金账户
     * @param book           当前托管明细账
     * @param loans          当前贷款台账
     * @param config         冻结档案
     * @param ctx            运行上下文
     * @param request        动作请求
     * @param actor          真实动作方
     * @param frozen         受信冻结快照（无冻结传 {@link CashFreeze#none()}）
     * @param custodyTrading 是否启用托管受益权付款（B7b 档案为真）
     * @return 候选结果
     */
    public static Outcome apply(BankState bank, Map<String, CashLedger.CashAccount> accounts,
                                CustodyBook book, Book loans, BankConfig config, BankContext ctx,
                                Request request, Actor actor, CashFreeze frozen,
                                boolean custodyTrading) {
        Objects.requireNonNull(frozen, "受信冻结快照不能为空；无冻结请显式传 CashFreeze.none()");
        BankChart chart = bank.chart();
        Map<String, CashLedger.CashAccount> cash = accounts == null ? Map.of() : accounts;
        switch (request.kind()) {
            case DEPOSIT, WITHDRAW, PAY -> {
                return payment(bank, cash, book, loans, config, ctx, request, actor, chart, frozen,
                        custodyTrading);
            }
            case LOAN_REQUEST, LOAN_ACCEPT, LOAN_APPROVE -> {
                return loanTerms(bank, cash, book, loans, config, ctx, request, actor);
            }
            case LOAN_DRAW -> {
                return loanDraw(bank, cash, book, loans, config, ctx, request, actor, frozen);
            }
            case LOAN_REPAY, LOAN_PAY_INTEREST, LOAN_ACCRUE -> {
                return loanMoney(bank, cash, book, loans, config, ctx, request, actor, frozen);
            }
            default -> {
                return reject(Reasons.PROTOCOL_ERROR + ":kind:" + request.kind(), bank, cash, book,
                        loans);
            }
        }
    }

    // ------------------------------------------------------------------ 存款/取现/支付

    private static Outcome payment(BankState bank, Map<String, CashLedger.CashAccount> cash,
                                   CustodyBook book, Book loans, BankConfig config,
                                   BankContext ctx, Request request, Actor actor, BankChart chart,
                                   CashFreeze frozen, boolean custodyTrading) {
        if (actor.bankManager()) {
            return reject(Reasons.ACTOR_NOT_AUTHORIZED + ":manager-cannot-move-customer",
                    bank, cash, book, loans);
        }
        if (!chart.hasParty(request.customerKey())) {
            return reject(Reasons.UNKNOWN_ACCOUNT + ":party:" + request.customerKey(), bank, cash,
                    book, loans);
        }
        String owner = chart.ownerIdOf(request.customerKey());
        if (!owner.equals(actor.ownerId()) || !request.customerKey().equals(actor.partyKey())) {
            return reject(Reasons.ACTOR_NOT_AUTHORIZED + ":owner", bank, cash, book, loans);
        }
        BankModel.Outcome outcome;
        switch (request.kind()) {
            case DEPOSIT -> {
                Map<String, String> body = new LinkedHashMap<>();
                body.put("owner", request.customerKey());
                body.put("bank", request.bankKey());
                body.put("amount", Money.text(request.amount()));
                outcome = BankModel.apply(bank, cash, config, ctx,
                        new BankCommand(request.requestId(), BankCommand.Kind.DEPOSIT, body,
                                request.customerKey()), frozen);
            }
            case WITHDRAW -> {
                Map<String, String> body = new LinkedHashMap<>();
                body.put("owner", request.customerKey());
                body.put("bank", request.bankKey());
                body.put("amount", Money.text(request.amount()));
                outcome = BankModel.apply(bank, cash, config, ctx,
                        new BankCommand(request.requestId(), BankCommand.Kind.WITHDRAW, body,
                                request.customerKey()), frozen);
            }
            default -> {
                Fund source;
                Fund target;
                try {
                    source = Fund.parse(request.sourceToken());
                    target = Fund.parse(request.payeeToken());
                } catch (RuntimeException invalid) {
                    return reject(Reasons.FUND_INVALID + ":" + invalid.getMessage(), bank, cash,
                            book, loans);
                }
                if (source.token().equals(target.token())) {
                    return reject(Reasons.PAYMENT_SAME_BUCKET + ":" + source.token(), bank, cash,
                            book, loans);
                }
                boolean custodyVector = source.kind() == Fund.Kind.CUSTODY
                        || target.kind() == Fund.Kind.CUSTODY;
                if (custodyVector && custodyTrading) {
                    // B7b：托管出金/入金与成交共用同一入口与同一份冻结快照。
                    CustodyPort.Result moved = CustodyPort.pay(bank, cash, book, config, ctx,
                            new BankPayment(request.requestId(), request.customerKey(), source,
                                    target, request.amount()), frozen);
                    return new Outcome(moved.accepted(), moved.reason(), moved.bank(),
                            moved.accounts(), moved.book(), loans, moved.receipt());
                }
                boolean bothDeposits = source.kind() == Fund.Kind.DEPOSIT
                        && target.kind() == Fund.Kind.DEPOSIT;
                if (bothDeposits) {
                    Fund.Deposit from = (Fund.Deposit) source;
                    Fund.Deposit to = (Fund.Deposit) target;
                    Map<String, String> body = new LinkedHashMap<>();
                    body.put("src", from.holderKey());
                    body.put("srcBank", from.bankKey());
                    body.put("dst", to.holderKey());
                    body.put("dstBank", to.bankKey());
                    body.put("amount", Money.text(request.amount()));
                    outcome = BankModel.apply(bank, cash, config, ctx,
                            new BankCommand(request.requestId(), BankCommand.Kind.TRANSFER, body,
                                    request.customerKey()), frozen);
                } else {
                    outcome = PaymentPort.pay(bank, cash, config, ctx,
                            new BankPayment(request.requestId(), request.customerKey(), source,
                                    target, request.amount()), frozen);
                }
            }
        }
        if (!outcome.accepted()) {
            return new Outcome(false, outcome.reason(), outcome.state(), outcome.accounts(), book,
                    loans, outcome.receipt());
        }
        CustodyBook candidate = book;
        try {
            candidate = book.runId() == null ? book : book;
            java.util.List<String> problems = candidate.validate(outcome.state(),
                    outcome.accounts(), config);
            if (!problems.isEmpty()) {
                return reject(problems.get(0), bank, cash, book, loans);
            }
        } catch (RuntimeException invalid) {
            return reject(Reasons.INVALID_STATE_TRANSITION + ":" + invalid.getMessage(), bank,
                    cash, book, loans);
        }
        return new Outcome(true, outcome.reason(), outcome.state(), outcome.accounts(), candidate,
                loans, outcome.receipt());
    }

    // ------------------------------------------------------------------ 借贷条款

    private static Outcome loanTerms(BankState bank, Map<String, CashLedger.CashAccount> cash,
                                     CustodyBook book, Book loans, BankConfig config,
                                     BankContext ctx, Request request, Actor actor) {
        switch (request.kind()) {
            case LOAN_REQUEST -> {
                if (actor.bankManager()
                        || !actor.partyKey().equals(request.customerKey())) {
                    return reject(Reasons.ACTOR_NOT_AUTHORIZED + ":borrower", bank, cash, book,
                            loans);
                }
                Loan existing = loans.loan(request.loanId());
                if (existing != null) {
                    return reject(Reasons.COMMAND_ID_CONFLICT + ":loan-exists:" + request.loanId(),
                            bank, cash, book, loans);
                }
                Loan loan = new Loan(request.loanId(), request.bankKey(), request.customerKey(),
                        request.amount(), request.rateU(), request.tenorMs(), request.purpose(),
                        LoanStatus.PROPOSED, false, null, Money.zero());
                return new Outcome(true, "LOAN_PROPOSED", bank, cash, book, loans.put(loan),
                        "accepted=true;reason=LOAN_PROPOSED;loan=" + request.loanId());
            }
            case LOAN_ACCEPT -> {
                Loan loan = loans.loan(request.loanId());
                if (loan == null) {
                    return reject(Reasons.UNKNOWN_BORROWER + ":loan:" + request.loanId(), bank,
                            cash, book, loans);
                }
                if (!actor.partyKey().equals(loan.borrowerKey())) {
                    return reject(Reasons.ACTOR_NOT_AUTHORIZED + ":borrower", bank, cash, book,
                            loans);
                }
                if (!loan.sameTerms(new Loan(loan.loanId(), loan.bankKey(), loan.borrowerKey(),
                        request.amount(), request.rateU(), request.tenorMs(), request.purpose(),
                        loan.status(), loan.accepted(), loan.approvedBy(), loan.drawn()))) {
                    return reject(Reasons.TERMS_CONFLICT + ":borrower", bank, cash, book, loans);
                }
                if (loan.status() == LoanStatus.DRAWN || loan.status() == LoanStatus.CLOSED) {
                    return reject(Reasons.INVALID_STATE_TRANSITION + ":drawn", bank, cash, book,
                            loans);
                }
                Loan next = loan.withStatus(LoanStatus.ACCEPTED, true, loan.approvedBy(),
                        loan.drawn());
                return new Outcome(true, "LOAN_ACCEPTED", bank, cash, book, loans.put(next),
                        "accepted=true;reason=LOAN_ACCEPTED;loan=" + loan.loanId());
            }
            case LOAN_APPROVE -> {
                if (!actor.bankManager() || !request.bankKey().equals(actor.managedBank())) {
                    return reject(Reasons.ACTOR_NOT_AUTHORIZED + ":bank-manager", bank, cash, book,
                            loans);
                }
                Loan loan = loans.loan(request.loanId());
                if (loan == null) {
                    return reject(Reasons.UNKNOWN_BORROWER + ":loan:" + request.loanId(), bank,
                            cash, book, loans);
                }
                if (!loan.sameTerms(new Loan(loan.loanId(), loan.bankKey(), loan.borrowerKey(),
                        request.amount(), request.rateU(), request.tenorMs(), request.purpose(),
                        loan.status(), loan.accepted(), loan.approvedBy(), loan.drawn()))) {
                    return reject(Reasons.TERMS_CONFLICT + ":manager", bank, cash, book, loans);
                }
                if (!loan.accepted()) {
                    return reject(Reasons.ACTOR_NOT_AUTHORIZED + ":borrower-not-accepted", bank,
                            cash, book, loans);
                }
                Loan next = loan.withStatus(LoanStatus.APPROVED, true, actor.partyKey(),
                        loan.drawn());
                return new Outcome(true, "LOAN_APPROVED", bank, cash, book, loans.put(next),
                        "accepted=true;reason=LOAN_APPROVED;loan=" + loan.loanId());
            }
            default -> {
                return reject(Reasons.PROTOCOL_ERROR + ":kind", bank, cash, book, loans);
            }
        }
    }

    // ------------------------------------------------------------------ 借贷资金

    private static Outcome loanDraw(BankState bank, Map<String, CashLedger.CashAccount> cash,
                                    CustodyBook book, Book loans, BankConfig config,
                                    BankContext ctx, Request request, Actor actor,
                                    CashFreeze frozen) {
        Loan loan = loans.loan(request.loanId());
        if (loan == null) {
            return reject(Reasons.UNKNOWN_BORROWER + ":loan:" + request.loanId(), bank, cash,
                    book, loans);
        }
        if (!actor.partyKey().equals(loan.borrowerKey()) || actor.bankManager()) {
            return reject(Reasons.ACTOR_NOT_AUTHORIZED + ":borrower", bank, cash, book, loans);
        }
        if (loan.status() != LoanStatus.APPROVED || !loan.accepted()
                || loan.approvedBy() == null) {
            return reject(Reasons.INVALID_STATE_TRANSITION + ":not-approved", bank, cash, book,
                    loans);
        }
        if (!loan.sameTerms(new Loan(loan.loanId(), loan.bankKey(), loan.borrowerKey(),
                request.amount(), request.rateU(), request.tenorMs(), request.purpose(),
                loan.status(), loan.accepted(), loan.approvedBy(), loan.drawn()))) {
            return reject(Reasons.TERMS_CONFLICT + ":draw", bank, cash, book, loans);
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("bank", loan.bankKey());
        body.put("borrower", loan.borrowerKey());
        body.put("amount", Money.text(loan.amount()));
        body.put("rate", InterestAccrual.rateText(loan.rateU()));
        body.put("tenorMs", Long.toString(loan.tenorMs()));
        body.put("bucket", bank.chart().bucketFor(loan.borrowerKey()));
        BankModel.Outcome outcome = BankModel.apply(bank, cash, config, ctx,
                new BankCommand(request.requestId(), BankCommand.Kind.LOAN_ORIGINATE, body,
                        loan.bankKey()), frozen);
        if (!outcome.accepted()) {
            return new Outcome(false, outcome.reason(), outcome.state(), outcome.accounts(), book,
                    loans, outcome.receipt());
        }
        BankState.Loan before = bank.loan(loan.bankKey(), loan.borrowerKey());
        BankState.Loan after = outcome.state().loan(loan.bankKey(), loan.borrowerKey());
        String trancheId = uniqueNewTranche(before, after);
        if (trancheId == null) {
            return new Outcome(false, Reasons.TRANCHE_BIND_FAILED + ":" + loan.loanId(),
                    outcome.state(), outcome.accounts(), book, loans, outcome.receipt());
        }
        BankState.Tranche tranche = after.tranche(trancheId);
        if (!trancheMatchesTerms(tranche, loan)) {
            return new Outcome(false, Reasons.TERMS_CONFLICT + ":tranche:" + trancheId,
                    outcome.state(), outcome.accounts(), book, loans, outcome.receipt());
        }
        Loan next = loan.withTranche(trancheId, tranche.contract(), LoanStatus.DRAWN);
        return new Outcome(true, "LOAN_DRAWN", outcome.state(), outcome.accounts(), book,
                loans.put(next), "accepted=true;reason=LOAN_DRAWN;loan=" + loan.loanId()
                + ";tranche=" + trancheId);
    }

    /** 提款前后唯一新增的期次标识；不是恰好一个就返回 null（不猜编号）。 */
    private static String uniqueNewTranche(BankState.Loan before, BankState.Loan after) {
        String found = null;
        for (BankState.Tranche tranche : after.tranches()) {
            boolean existed = before != null && before.tranche(tranche.trancheId()) != null;
            if (existed) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = tranche.trancheId();
        }
        return found;
    }

    /** 真实期次必须与冻结条款一致（金额/利率/期限），否则不绑定。 */
    private static boolean trancheMatchesTerms(BankState.Tranche tranche, Loan loan) {
        if (tranche == null || tranche.terms() == null) {
            return false;
        }
        BankState.LoanTerms terms = tranche.terms();
        return tranche.contract().compareTo(loan.amount()) == 0
                && terms.rateU() == loan.rateU()
                && terms.maturityMs() - terms.startMs() == loan.tenorMs();
    }

    /** 期次的条款事实仍与台账一致（本金已还时只比利率与期限）。 */
    private static boolean termsStillMatch(BankState.Tranche tranche, Loan loan) {
        if (tranche == null || tranche.terms() == null) {
            return false;
        }
        BankState.LoanTerms terms = tranche.terms();
        return terms.rateU() == loan.rateU()
                && terms.maturityMs() - terms.startMs() == loan.tenorMs();
    }

    /**
     * 从真实银行状态派生台账（本金与状态），并校验期次绑定仍然成立。
     *
     * <p>这是“台账不是第二本金账”的执行点：{@code drawn} 永远等于该期次当前
     * {@code contract()}，{@code CLOSED} 要求本金与利息都已清零；期次缺失、条款或
     * 定位无法解释时抛 {@code LOAN_TRANCHE_UNRESOLVED}，不做静默迁移。</p>
     *
     * @param loans 台账（可为 null）
     * @param bank  当前银行状态
     * @return 派生后的台账
     */
    public static Book derive(Book loans, BankState bank) {
        if (loans == null || loans.loans().isEmpty()) {
            return Book.empty();
        }
        Map<String, Loan> next = new TreeMap<>();
        for (Map.Entry<String, Loan> entry : loans.loans().entrySet()) {
            Loan loan = entry.getValue();
            Loan derived = loan;
            if (loan.trancheId() != null) {
                BankState.Loan book = bank.loan(loan.bankKey(), loan.borrowerKey());
                BankState.Tranche tranche = book == null ? null : book.tranche(loan.trancheId());
                if (!termsStillMatch(tranche, loan)) {
                    throw new IllegalArgumentException(
                            Reasons.LOAN_TRANCHE_UNRESOLVED + ":" + loan.loanId());
                }
                boolean settled = tranche.contract().signum() == 0
                        && tranche.terms().interestReceivable().signum() == 0;
                derived = loan.withTranche(loan.trancheId(), tranche.contract(),
                        settled ? LoanStatus.CLOSED : LoanStatus.DRAWN);
            } else if (loan.status() == LoanStatus.DRAWN || loan.status() == LoanStatus.CLOSED) {
                throw new IllegalArgumentException(
                        Reasons.LOAN_TRANCHE_UNRESOLVED + ":" + loan.loanId());
            }
            next.put(entry.getKey(), derived);
        }
        return new Book(next);
    }

    private static Outcome loanMoney(BankState bank, Map<String, CashLedger.CashAccount> cash,
                                     CustodyBook book, Book loans, BankConfig config,
                                     BankContext ctx, Request request, Actor actor,
                                     CashFreeze frozen) {
        Loan loan = loans.loan(request.loanId());
        if (loan == null) {
            return reject(Reasons.UNKNOWN_BORROWER + ":loan:" + request.loanId(), bank, cash,
                    book, loans);
        }
        BankCommand.Kind kind;
        switch (request.kind()) {
            case LOAN_REPAY -> {
                if (!actor.partyKey().equals(loan.borrowerKey()) || actor.bankManager()) {
                    return reject(Reasons.ACTOR_NOT_AUTHORIZED + ":borrower", bank, cash, book,
                            loans);
                }
                kind = BankCommand.Kind.LOAN_REPAY;
            }
            case LOAN_PAY_INTEREST -> {
                if (!actor.partyKey().equals(loan.borrowerKey()) || actor.bankManager()) {
                    return reject(Reasons.ACTOR_NOT_AUTHORIZED + ":borrower", bank, cash, book,
                            loans);
                }
                kind = BankCommand.Kind.PAY_LOAN_INTEREST;
            }
            default -> kind = BankCommand.Kind.ACCRUE_LOAN;
        }
        if (loan.trancheId() == null) {
            return reject(Reasons.LOAN_TRANCHE_UNRESOLVED + ":" + loan.loanId(), bank, cash, book,
                    loans);
        }
        if (loan.status() != LoanStatus.DRAWN && loan.status() != LoanStatus.CLOSED) {
            return reject(Reasons.INVALID_STATE_TRANSITION + ":not-drawn", bank, cash, book,
                    loans);
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("bank", loan.bankKey());
        body.put("borrower", loan.borrowerKey());
        // 期次定位：只影响本合同的期次，旧聚合命令（无该字段）行为不变。
        body.put("tranche", loan.trancheId());
        if (kind == BankCommand.Kind.ACCRUE_LOAN) {
            body.put("toMs", Long.toString(request.toMs()));
        } else {
            body.put("amount", Money.text(request.amount()));
        }
        String actorKey = kind == BankCommand.Kind.ACCRUE_LOAN ? loan.bankKey()
                : loan.borrowerKey();
        BankModel.Outcome outcome = BankModel.apply(bank, cash, config, ctx,
                new BankCommand(request.requestId(), kind, body, actorKey), frozen);
        if (!outcome.accepted()) {
            return new Outcome(false, outcome.reason(), outcome.state(), outcome.accounts(), book,
                    loans, outcome.receipt());
        }
        BankState.Tranche tranche = outcome.state().loan(loan.bankKey(), loan.borrowerKey())
                .tranche(loan.trancheId());
        if (tranche == null) {
            return new Outcome(false, Reasons.TRANCHE_UNKNOWN + ":" + loan.trancheId(),
                    outcome.state(), outcome.accounts(), book, loans, outcome.receipt());
        }
        boolean settled = tranche.contract().signum() == 0 && tranche.terms() != null
                && tranche.terms().interestReceivable().signum() == 0;
        Book next = loans.put(loan.withTranche(loan.trancheId(), tranche.contract(),
                settled ? LoanStatus.CLOSED : LoanStatus.DRAWN));
        return new Outcome(true, outcome.reason(), outcome.state(), outcome.accounts(), book, next,
                outcome.receipt());
    }

    private static Outcome reject(String reason, BankState bank,
                                  Map<String, CashLedger.CashAccount> cash, CustodyBook book,
                                  Book loans) {
        return new Outcome(false, reason, bank, cash, book, loans, null);
    }
}
