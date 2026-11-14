package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.CashTransfers;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 银行纯核唯一入口：账、规则、基础信用与冻结条款。无 I/O、无 Runtime、不持有第二份现金。
 *
 * <p>存款利率只能来自档案里银行侧冻结的 offer；贷款按期次记账，一次提款一期。
 * 任何改变余额或费率的命令都先在同一个候选内把受影响头寸的利息累计到
 * {@link BankContext#atMillis()}，再改动余额；命令被拒时整个候选丢弃，不留半笔利息。
 * 资本/流动性闸门只拦新增信用，不拦计息、还本与付息。</p>
 */
public final class BankModel {

    /** 一次调用的结果；业务拒绝时 state/accounts 只记录命令，其余不变。
     * @param accepted accepted 参数
     * @param reason reason 参数
     * @param receipt receipt 参数
     * @param state state 参数
     * @param accounts accounts 参数
     */
public record Outcome(boolean accepted, String reason, String receipt,
                          BankState state, Map<String, CashLedger.CashAccount> accounts) {
    }

    private BankModel() {
    }

    /**
     * 应用一条命令。
     *
     * @param state 当前状态（须已绑定 run 与档案指纹）
     * @param accounts 现金账户（唯一现金权威）
     * @param config 冻结档案
     * @param ctx 运行上下文
     * @param cmd 命令（完整 body）
     * @return 结果；协议错误与配置错误不消费 commandId
     */
    public static Outcome apply(BankState state, Map<String, CashLedger.CashAccount> accounts,
                                BankConfig config, BankContext ctx, BankCommand cmd) {
        return apply(state, accounts, config, ctx, cmd, null);
    }

    /**
     * 应用一条命令，并携带世界侧受信的冻结快照。
     *
     * <p>{@link BankCommand.Kind#PAYMENT} 必须由本重载提供快照；旧的五参数入口传 {@code null}，
     * 因此直接用原生命令付款会以 {@link Reasons#PAYMENT_FREEZE_REQUIRED} 拒绝，
     * 无法绕过可用额检查去花掉已冻结的买单资金。</p>
     *
     * @param state 当前状态（须已绑定 run 与档案指纹）
     * @param accounts 现金账户（唯一现金权威）
     * @param config 冻结档案
     * @param ctx 运行上下文
     * @param cmd 命令（完整 body）
     * @param frozen 受信冻结快照（非 PAYMENT 命令忽略）
     * @return 结果；协议错误与配置错误不消费 commandId
     */
    public static Outcome apply(BankState state, Map<String, CashLedger.CashAccount> accounts,
                                BankConfig config, BankContext ctx, BankCommand cmd,
                                CashFreeze frozen) {
        if (state.runId() == null) {
            return reject(Reasons.NO_ACTIVE_RUN, state, accounts);
        }
        if (!state.runId().equals(ctx.runId())
                || !state.configFingerprint().equals(ctx.configFingerprint())) {
            return reject(Reasons.STATE_FINGERPRINT_MISMATCH, state, accounts);
        }
        if (!config.fingerprint().equals(ctx.configFingerprint())) {
            return reject(Reasons.CONFIG_CHANGED_REQUIRES_NEW_RUN, state, accounts);
        }
        if (!state.chart().profile().fingerprint().equals(config.profile().fingerprint())) {
            return reject(Reasons.STATE_FINGERPRINT_MISMATCH, state, accounts);
        }
        List<String> configProblems = BankArchiveValidator.validate(config, state, accounts);
        if (!configProblems.isEmpty()) {
            return reject(Reasons.CONFIG_INVALID + ":" + configProblems.get(0), state, accounts);
        }
        try {
            cmd.validateKeys();
        } catch (BankException failure) {
            return reject(failure.reason(), state, accounts);
        }
        BankState.CommandRecord previous = state.command(cmd.commandId());
        if (previous != null) {
            if (!previous.body().equals(cmd.canonicalBody())) {
                return new Outcome(false, Reasons.COMMAND_ID_CONFLICT, previous.receipt(),
                        state, accounts);
            }
            boolean accepted = previous.accepted();
            String reason = accepted ? "ACCEPTED" : reasonOf(previous.receipt());
            return new Outcome(accepted, reason, previous.receipt(), state, accounts);
        }
        Working work = new Working(state.draft(), new LinkedHashMap<>(accounts), frozen);
        try {
            syncReserve(work);
            execute(work, config, ctx, cmd, frozen);
            syncReserve(work);
            BankState candidate = work.draft.freeze();
            List<String> problems = BankInvariants.check(candidate, work.accounts);
            if (!problems.isEmpty()) {
                throw new BankException("INVARIANT:" + problems.get(0));
            }
            String receipt = receipt(true, "ACCEPTED", work.receipt);
            work.draft.putCommand(cmd.commandId(),
                    new BankState.CommandRecord(cmd.canonicalBody(), receipt, true));
            return new Outcome(true, "ACCEPTED", receipt, work.draft.freeze(), work.accounts);
        } catch (BankException failure) {
            if (failure.reason().startsWith(Reasons.PROTOCOL_ERROR)) {
                return new Outcome(false, failure.reason(), null, state, accounts);
            }
            String receipt = receipt(false, failure.reason(), work.receipt);
            BankState.Draft rejected = state.draft();
            rejected.putCommand(cmd.commandId(),
                    new BankState.CommandRecord(cmd.canonicalBody(), receipt, false));
            return new Outcome(false, failure.reason(), receipt, rejected.freeze(), accounts);
        } catch (IllegalArgumentException invalid) {
            String reason = Reasons.INVALID_STATE_TRANSITION + ":"
                    + (invalid.getMessage() == null ? invalid.getClass().getSimpleName()
                    : invalid.getMessage());
            String receipt = receipt(false, reason, work.receipt);
            BankState.Draft rejected = state.draft();
            rejected.putCommand(cmd.commandId(),
                    new BankState.CommandRecord(cmd.canonicalBody(), receipt, false));
            return new Outcome(false, reason, receipt, rejected.freeze(), accounts);
        }
    }

    private static Outcome reject(String reason, BankState state,
                                  Map<String, CashLedger.CashAccount> accounts) {
        return new Outcome(false, reason, null, state, accounts);
    }

    private static String reasonOf(String receipt) {
        for (String part : receipt.split(";")) {
            if (part.startsWith("reason=")) {
                return part.substring("reason=".length());
            }
        }
        return "UNKNOWN";
    }

    private static String receipt(boolean accepted, String reason, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("accepted=").append(accepted).append(";reason=").append(reason);
        new TreeMap<>(fields).forEach((k, v) -> sb.append(';').append(k).append('=').append(v));
        return sb.toString();
    }

    private static void execute(Working w, BankConfig config, BankContext ctx, BankCommand cmd,
                                CashFreeze frozen) {
        long now = ctx.atMillis();
        switch (cmd.kind()) {
            case DEPOSIT -> deposit(w, config, cmd, now);
            case WITHDRAW -> withdraw(w, cmd, now);
            case TRANSFER -> transfer(w, config, cmd, now);
            case LOAN_ORIGINATE -> originate(w, config, cmd, now);
            case LOAN_REPAY -> repay(w, cmd, now);
            case IMPAIR -> impair(w, cmd);
            case WRITE_OFF -> writeOff(w, cmd, now);
            case THIRD_PARTY_PAY -> thirdPartyPay(w, cmd, now);
            case WAIVE -> waive(w, cmd, now);
            case COMMITMENT_INCREASE -> commit(w, config, cmd);
            case RELIEF_DRAW -> reliefDraw(w, config, cmd);
            case FACILITY_PAY -> facilityPay(w, config, cmd, now);
            case FACILITY_WAIVE -> facilityWaive(w, config, cmd, now);
            case FACILITY_PROVISION -> facilityProvision(w, config, cmd);
            case FACILITY_DEFAULT -> facilityDefault(w, cmd);
            case MIGRATE -> migrate(w, config, cmd, now);
            case ACCRUE_LOAN -> accrueLoanCommand(w, cmd, now);
            case ACCRUE_DEPOSIT -> accrueDepositCommand(w, cmd, now);
            case PAY_LOAN_INTEREST -> payLoanInterest(w, cmd, now);
            case PAY_DEPOSIT_INTEREST -> payDepositInterest(w, cmd, now);
            case MATURE_LOAN -> matureLoan(w, cmd, now);
            case COMMITMENT_OPEN -> commitmentOpen(w, config, cmd, now);
            case COMMITMENT_DRAW -> commitmentDraw(w, config, cmd, now);
            case FACILITY_AUTHORIZE -> facilityAuthorize(w, config, cmd, now);
            case FACILITY_DRAW -> facilityDraw(w, config, cmd, now);
            case FACILITY_ACCRUE -> facilityAccrueCommand(w, config, cmd, now);
            case FACILITY_PAY_INTEREST -> facilityPayInterest(w, config, cmd, now);
            case FACILITY_IMPAIR -> facilityImpair(w, config, cmd, now);
            case FACILITY_WRITE_OFF -> facilityWriteOff(w, config, cmd, now);
            case FACILITY_RECOVER -> facilityRecover(w, config, cmd, now);
            case LOAN_ASSIGN -> loanAssign(w, config, cmd, now);
            case ASSIGNED_RECOVER -> assignedRecover(w, config, cmd, now);
            case ASSIGNED_WAIVE -> assignedWaive(w, config, cmd, now);
            case RESOLUTION_OPEN -> resolutionOpen(w, config, cmd, now);
            case RESOLUTION_DISPOSE -> resolutionDispose(w, config, cmd, now);
            case RESOLUTION_DISTRIBUTE -> resolutionDistribute(w, config, cmd, now);
            case RESOLUTION_DISCHARGE -> resolutionDischarge(w, config, cmd, now);
            case PURCHASE_ACCEPT -> purchaseAccept(w, config, cmd, now);
            case DISCHARGE_CONSENT -> dischargeConsent(w, config, cmd, now);
            case ASSIGNED_ACCRUE -> assignedAccrue(w, config, cmd, now);
            case ASSIGNED_PAY_INTEREST -> assignedPayInterest(w, config, cmd);
            case PAYMENT -> payment(w, config, cmd, now, frozen);
            default -> throw new BankException(Reasons.PROTOCOL_ERROR + ":kind:" + cmd.kind());
        }
    }

    // ------------------------------------------------------------ 存款

    private static void deposit(Working w, BankConfig config, BankCommand cmd, long now) {
        String owner = cmd.text("owner");
        String bank = cmd.text("bank");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        String requested = cmd.optional("offer");
        requireActor(cmd, owner);
        requireBank(w, bank);
        requireCustodyLine(w.chart(), bank, owner);
        requireCash(w, owner);
        if (!config.interestEnabled()) {
            if (requested != null) {
                throw new BankException(Reasons.NO_TERMS + ":profile-disabled");
            }
            move(w, owner, w.chart().cashAccountOf(owner), w.chart().reserveAccountOf(bank), amount);
            addGl(w, bank, w.chart().depositAccountOf(owner), amount.negate());
            w.receipt.put("owner", owner);
            w.receipt.put("bank", bank);
            w.receipt.put("amount", Money.text(amount));
            return;
        }
        BankConfig.DepositOffer offer = resolveOffer(config, bank, requested);
        syncDeposit(w, bank, owner, now);
        applyDepositOffer(w, bank, owner, offer, now);
        move(w, owner, w.chart().cashAccountOf(owner), w.chart().reserveAccountOf(bank), amount);
        addGl(w, bank, w.chart().depositAccountOf(owner), amount.negate());
        w.receipt.put("owner", owner);
        w.receipt.put("bank", bank);
        w.receipt.put("amount", Money.text(amount));
        BankState.DepositAccount account = w.draft.depositAccount(bank, owner);
        w.receipt.put("offer", account.offerId());
        w.receipt.put("rate", InterestAccrual.rateText(account.rateU()));
        w.receipt.put("maturityMs", Long.toString(account.maturityMs()));
    }

    private static void withdraw(Working w, BankCommand cmd, long now) {
        String owner = cmd.text("owner");
        String bank = cmd.text("bank");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, owner);
        requireBank(w, bank);
        requireCustodyLine(w.chart(), bank, owner);
        requireCash(w, owner);
        if (w.draft.gl(bank, w.chart().depositAccountOf(owner)).negate()
                .compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        syncDeposit(w, bank, owner, now);
        requireDepositRoom(w, bank, owner, amount);
        move(w, bank, w.chart().reserveAccountOf(bank), w.chart().cashAccountOf(owner), amount);
        addGl(w, bank, w.chart().depositAccountOf(owner), amount);
        shrinkDeposit(w, bank, owner);
        w.receipt.put("owner", owner);
        w.receipt.put("amount", Money.text(amount));
    }

    private static void transfer(Working w, BankConfig config, BankCommand cmd, long now) {
        String src = cmd.text("src");
        String srcBank = cmd.text("srcBank");
        String dst = cmd.text("dst");
        String dstBank = cmd.text("dstBank");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, src);
        requireBank(w, srcBank);
        requireBank(w, dstBank);
        requireCustodyLine(w.chart(), srcBank, src);
        requireCustodyLine(w.chart(), dstBank, dst);
        if (src.equals(dst)) {
            throw new BankException(Reasons.PROTOCOL_ERROR + ":self");
        }
        if (w.draft.gl(srcBank, w.chart().depositAccountOf(src)).negate()
                .compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        syncDeposit(w, srcBank, src, now);
        syncDeposit(w, dstBank, dst, now);
        requireDepositRoom(w, srcBank, src, amount);
        if (srcBank.equals(dstBank)) {
            addGl(w, srcBank, w.chart().depositAccountOf(src), amount);
            addGl(w, dstBank, w.chart().depositAccountOf(dst), amount.negate());
        } else {
            move(w, srcBank, w.chart().reserveAccountOf(srcBank),
                    w.chart().reserveAccountOf(dstBank), amount);
            addGl(w, srcBank, w.chart().depositAccountOf(src), amount);
            addGl(w, dstBank, w.chart().depositAccountOf(dst), amount.negate());
        }
        shrinkDeposit(w, srcBank, src);
        applyCreditPolicy(w, config, dstBank, dst, now, amount);
        w.receipt.put("src", src);
        w.receipt.put("dst", dst);
        w.receipt.put("amount", Money.text(amount));
    }

    // ------------------------------------------------------------ 支付接缝

    /**
     * 显式资金桶付款：CASH→CASH、CASH→DEP、DEP→CASH、同行 DEP→DEP、跨行 DEP→DEP。
     *
     * <p>所有腿只在本候选的副本上前进；任一腿拒绝即整批丢弃，调用方拿回原 bank 与 accounts。
     * 付款人真实 owner 决定支出，收款人不签授权；内部派生腿（现金腿、存款入账腿、跨行准备金腿）
     * 都从这里派生，没有可公开构造的单腿命令。余额变动前先按既有条款计提两侧存款利息，
     * 存款入账沿用收款行 creditPolicy。冻结快照只用于算可用额，本命令从不占用或释放冻结。</p>
     */
    private static void payment(Working w, BankConfig config, BankCommand cmd, long now,
                                CashFreeze frozen) {
        String payer = cmd.text("payer");
        Fund source = Fund.parse(cmd.text("src"));
        Fund destination = Fund.parse(cmd.text("dst"));
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, payer);
        if (frozen == null) {
            throw new BankException(Reasons.PAYMENT_FREEZE_REQUIRED);
        }
        if (source.token().equals(destination.token())) {
            throw new BankException(Reasons.PAYMENT_SAME_BUCKET + ":" + source.token());
        }
        Bucket debit = resolve(w, source, payer, true);
        Bucket credit = resolve(w, destination, payer, false);
        syncBucket(w, debit, now);
        syncBucket(w, credit, now);
        BigDecimal balance = balanceOf(w, debit);
        BigDecimal held = frozen.frozenOf(source);
        if (held.compareTo(balance) > 0) {
            w.receipt.put("bucket", source.token());
            w.receipt.put("balance", Money.text(balance));
            w.receipt.put("frozen", Money.text(held));
            throw new BankException(Reasons.PAYMENT_FREEZE_EXCEEDS_BALANCE + ":" + source.token());
        }
        BigDecimal available = balance.subtract(held);
        if (amount.compareTo(available) > 0) {
            w.receipt.put("bucket", source.token());
            w.receipt.put("balance", Money.text(balance));
            w.receipt.put("frozen", Money.text(held));
            w.receipt.put("available", Money.text(available));
            throw new BankException(Reasons.PAYER_BALANCE_INSUFFICIENT + ":" + source.token());
        }
        if (debit.cash()) {
            if (credit.cash()) {
                moveOwned(w, debit.ownerId(), debit.accountId(), credit.accountId(), amount);
            } else {
                moveOwned(w, debit.ownerId(), debit.accountId(),
                        w.chart().reserveAccountOf(credit.bank()), amount);
                creditDeposit(w, config, credit, amount, now);
            }
        } else if (credit.cash()) {
            debitDeposit(w, debit, amount);
            moveReserve(w, debit.bank(), credit.accountId(), amount);
        } else {
            debitDeposit(w, debit, amount);
            if (!debit.bank().equals(credit.bank())) {
                moveReserve(w, debit.bank(), w.chart().reserveAccountOf(credit.bank()), amount);
            }
            creditDeposit(w, config, credit, amount, now);
        }
        w.receipt.put("payer", payer);
        w.receipt.put("src", source.token());
        w.receipt.put("dst", destination.token());
        w.receipt.put("amount", Money.text(amount));
    }

    /** 解析后的资金桶：真实 owner 与它的账户/银行持有人坐标。 */
    private record Bucket(Fund.Kind kind, String ownerId, String accountId, String bank,
                          String holder) {

        private boolean cash() {
            return kind == Fund.Kind.CASH;
        }
    }

    /** 把显式资金桶解析成真实坐标；出款侧还必须属于付款人的真实 owner。 */
    private static Bucket resolve(Working w, Fund fund, String payer, boolean debit) {
        if (fund.kind() == Fund.Kind.CUSTODY) {
            throw new BankException(Reasons.FUND_CUSTODY_UNSUPPORTED + ":" + fund.token());
        }
        if (fund instanceof Fund.Cash cash) {
            CashLedger.CashAccount account = w.accounts.get(cash.accountId());
            if (account == null) {
                throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":cash:" + cash.accountId());
            }
            if (debit && !account.ownerId().equals(w.chart().ownerIdOf(payer))) {
                throw new BankException(Reasons.ACTOR_NOT_AUTHORIZED + ":owner:"
                        + cash.accountId());
            }
            return new Bucket(Fund.Kind.CASH, account.ownerId(), cash.accountId(), null, null);
        }
        Fund.Deposit deposit = (Fund.Deposit) fund;
        requireBank(w, deposit.bankKey());
        boolean custodian = w.chart().isCustodian(deposit.holderKey());
        if (!w.chart().hasParty(deposit.holderKey()) && !custodian) {
            throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":holder:" + deposit.holderKey());
        }
        String owner = w.chart().ownerIdOf(deposit.holderKey());
        if (custodian) {
            // 托管经营人没有自己的现金账户：它的银行存款债权是客户资产的池，身份由档案
            // CustodyRef（已由开局登记校验）证明，不能要求一个不属于它的现金账户。
            requireCustodyLine(w.chart(), deposit.bankKey(), deposit.holderKey());
            if (debit && !owner.equals(w.chart().ownerIdOf(payer))) {
                throw new BankException(Reasons.ACTOR_NOT_AUTHORIZED + ":owner:" + fund.token());
            }
            w.chart().depositAccountOf(deposit.holderKey());
            return new Bucket(Fund.Kind.DEPOSIT, owner, null, deposit.bankKey(),
                    deposit.holderKey());
        }
        CashLedger.CashAccount holderCash =
                w.accounts.get(w.chart().cashAccountOf(deposit.holderKey()));
        if (holderCash == null || !holderCash.ownerId().equals(owner)) {
            throw new BankException(Reasons.FUND_OWNER_MISMATCH + ":holder:"
                    + deposit.holderKey());
        }
        w.chart().depositAccountOf(deposit.holderKey());
        if (debit && !owner.equals(w.chart().ownerIdOf(payer))) {
            throw new BankException(Reasons.ACTOR_NOT_AUTHORIZED + ":owner:" + fund.token());
        }
        return new Bucket(Fund.Kind.DEPOSIT, owner, null, deposit.bankKey(), deposit.holderKey());
    }

    /** 准备金账户的真实 owner；档案声明与账户不一致时拒绝，不猜、不互换 key 与 ownerId。 */
    private static String reserveOwner(Working w, String bank) {
        String accountId = w.chart().reserveAccountOf(bank);
        CashLedger.CashAccount account = w.accounts.get(accountId);
        if (account == null) {
            throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":reserve:" + bank);
        }
        if (!account.ownerId().equals(w.chart().ownerIdOf(bank))) {
            throw new BankException(Reasons.FUND_OWNER_MISMATCH + ":reserve:" + bank);
        }
        return account.ownerId();
    }

    /** 出款桶余额：现金看账户，存款看本行该持有人存款科目（负债方为负）。 */
    private static BigDecimal balanceOf(Working w, Bucket bucket) {
        if (bucket.cash()) {
            return w.accounts.get(bucket.accountId()).cash();
        }
        return w.draft.gl(bucket.bank(), w.chart().depositAccountOf(bucket.holder())).negate();
    }

    /**
     * 跨行/提现的现金腿：从该行准备金划出；准备金不足得到 {@link Reasons#RESERVE_INSUFFICIENT}。
     *
     * <p>这是候选里的一条派生腿，失败时整批丢弃（前面的存款与计提都不留），
     * 也说明为什么不能先“外部取现”再第二次提交转账。</p>
     */
    private static void moveReserve(Working w, String fromBank, String toAccount,
                                    BigDecimal amount) {
        try {
            moveOwned(w, reserveOwner(w, fromBank), w.chart().reserveAccountOf(fromBank),
                    toAccount, amount);
        } catch (BankException failure) {
            if (Reasons.INSUFFICIENT_FUNDS.equals(failure.reason())) {
                throw new BankException(Reasons.RESERVE_INSUFFICIENT + ":" + fromBank);
            }
            throw failure;
        }
    }

    /** 余额变动前把该桶的存款利息按既有条款累计到当前时点。 */
    private static void syncBucket(Working w, Bucket bucket, long now) {
        if (!bucket.cash()) {
            syncDeposit(w, bucket.bank(), bucket.holder(), now);
        }
    }

    /** 出款侧存款减少：先记负债减少，再按非应计口径封顶。 */
    private static void debitDeposit(Working w, Bucket bucket, BigDecimal amount) {
        requireCustodyLine(w.chart(), bucket.bank(), bucket.holder());
        addGl(w, bucket.bank(), w.chart().depositAccountOf(bucket.holder()), amount);
        shrinkDeposit(w, bucket.bank(), bucket.holder());
    }

    /** 收款侧存款增加：按收款行 creditPolicy 派生政策，不静默缺条款。 */
    private static void creditDeposit(Working w, BankConfig config, Bucket bucket,
                                      BigDecimal amount, long now) {
        addGl(w, bucket.bank(), w.chart().depositAccountOf(bucket.holder()), amount.negate());
        applyCreditPolicy(w, config, bucket.bank(), bucket.holder(), now, amount);
    }

    private static void accrueDepositCommand(Working w, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String owner = cmd.text("owner");
        long toMs = cmd.integer("toMs");
        requireActor(cmd, bank);
        requireBank(w, bank);
        requireCustodyLine(w.chart(), bank, owner);
        accrueDeposit(w, bank, owner, toMs, now);
        BankState.DepositAccount account = w.draft.depositAccount(bank, owner);
        w.receipt.put("owner", owner);
        w.receipt.put("toMs", Long.toString(toMs));
        w.receipt.put("accrued", Money.text(account.accruedInterest()));
        w.receipt.put("interestPayable", Money.text(account.interestPayable()));
        w.receipt.put("offer", account.offerId() == null ? "NON_ACCRUAL" : account.offerId());
    }

    private static void payDepositInterest(Working w, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String owner = cmd.text("owner");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, bank);
        requireBank(w, bank);
        requireCustodyLine(w.chart(), bank, owner);
        BankState.DepositAccount account = w.draft.depositAccount(bank, owner);
        if (account == null) {
            throw new BankException(Reasons.NO_TERMS);
        }
        if (account.nonAccrual()) {
            throw new BankException(Reasons.NON_ACCRUAL_ACCOUNT + ":" + account.nonAccrualReason());
        }
        accrueDeposit(w, bank, owner, now, now);
        account = w.draft.depositAccount(bank, owner);
        if (amount.compareTo(account.interestPayable()) > 0) {
            throw new BankException(Reasons.INTEREST_EXCEEDS_ACCRUED);
        }
        addGl(w, bank, BankChart.INTEREST_PAYABLE, amount);
        addGl(w, bank, w.chart().depositAccountOf(owner), amount.negate());
        w.draft.putDepositAccount(bank, owner,
                account.withPayment(account.paidCents() + InterestAccrual.cents(amount)));
        w.receipt.put("owner", owner);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("interestPayable",
                Money.text(account.interestPayable().subtract(amount)));
    }

    /** 冻结 offer 解析：只能用本行已声明 offer（缺省即默认 offer）。 */
    private static BankConfig.DepositOffer resolveOffer(BankConfig config, String bank,
                                                        String requested) {
        BankConfig.DepositOffer declared = config.depositOffer(bank);
        if (declared == null) {
            throw new BankException(Reasons.OFFER_MISSING + ":" + bank);
        }
        if (requested != null && !requested.equals(declared.offerId())) {
            throw new BankException(Reasons.OFFER_UNKNOWN + ":" + requested);
        }
        return declared;
    }

    /** 客户主动存款：建/沿用/转换账户政策；同 offer 追加不重置累计值。 */
    private static void applyDepositOffer(Working w, String bank, String owner,
                                          BankConfig.DepositOffer offer, long now) {
        long balanceCents = balanceCents(w, bank, owner);
        BankState.DepositAccount account = w.draft.depositAccount(bank, owner);
        if (account == null) {
            w.draft.putDepositAccount(bank, owner, new BankState.DepositAccount(offer.offerId(),
                    offer.rateU(), now, now + offer.tenorMs(), BigInteger.ZERO, 0, 0, now,
                    Math.max(0, balanceCents),
                    balanceCents > 0 ? "opening_balance_before_offer" : null, null));
            return;
        }
        if (account.nonAccrual()) {
            w.draft.putDepositAccount(bank, owner, account.withOffer(offer, now, balanceCents));
            return;
        }
        if (!account.offerId().equals(offer.offerId())) {
            throw new BankException(Reasons.REPRICE_REQUIRES_OFFER + ":" + account.offerId());
        }
    }

    /** 银行产生的存款按接收行 creditRule 派生政策（不静默 terms=null）。 */
    private static void applyCreditPolicy(Working w, BankConfig config, String bank, String owner,
                                          long now, BigDecimal credited) {
        if (!config.interestEnabled()) {
            return;
        }
        BankingProfile.CustodyRef custody = w.chart().profile().custodians().get(owner);
        if (custody != null) {
            applyCustodyPoolPolicy(w, custody, bank, owner, now);
            return;
        }
        BankConfig.DepositOffer offer = config.depositOffer(bank);
        if (offer == null) {
            throw new BankException(Reasons.OFFER_MISSING + ":" + bank);
        }
        long balanceCents = balanceCents(w, bank, owner);
        long creditedCents = InterestAccrual.cents(credited);
        long beforeCents = balanceCents - creditedCents;
        BankState.DepositAccount account = w.draft.depositAccount(bank, owner);
        if (offer.creditsAccrue()) {
            if (account == null) {
                w.draft.putDepositAccount(bank, owner,
                        new BankState.DepositAccount(offer.offerId(), offer.rateU(), now,
                                now + offer.tenorMs(), BigInteger.ZERO, 0, 0, now,
                                Math.max(0, beforeCents),
                                beforeCents > 0 ? "opening_balance_before_offer" : null, null));
            } else if (account.nonAccrual()) {
                w.draft.putDepositAccount(bank, owner,
                        account.withOffer(offer, now, Math.max(0, beforeCents)));
            }
            return;
        }
        if (account == null) {
            w.draft.putDepositAccount(bank, owner, new BankState.DepositAccount(null, 0, now, now,
                    BigInteger.ZERO, 0, 0, now, Math.max(0, balanceCents),
                    balanceCents > 0 ? "bank_credit_non_accrual" : null,
                    "bank_credit_non_accrual"));
            return;
        }
        if (account.nonAccrual()) {
            w.draft.putDepositAccount(bank, owner,
                    account.withExcluded(Math.max(0, balanceCents), "bank_credit_non_accrual"));
            return;
        }
        long nextExcluded = Math.min(balanceCents, account.excludedCents() + creditedCents);
        w.draft.putDepositAccount(bank, owner,
                account.withExcluded(nextExcluded, account.excludedReason() == null
                        ? "bank_credit_non_accrual" : account.excludedReason()));
    }

    /**
     * 托管池的独立非应计口径：按档案 {@code poolPolicy} 建/维护池的存款条款，
     * 不使用同银行的零售 offer，也不把已有应计条款静默降息。
     *
     * <p>池余额变化后 {@code excludedCents} 必须等于当前余额，这是
     * {@code BankInvariants} 的非应计不变量；本方法在每次入账/出账后重建该值。</p>
     */
    private static void applyCustodyPoolPolicy(Working w, BankingProfile.CustodyRef custody,
                                               String bank, String owner, long now) {
        requireCustodyLine(w.chart(), bank, owner);
        if (custody.creditsAccrue()) {
            throw new BankException(Reasons.CUSTODY_POOL_ACCRUAL + ":declared:" + owner);
        }
        long balanceCents = balanceCents(w, bank, owner);
        BankState.DepositAccount account = w.draft.depositAccount(bank, owner);
        if (account == null) {
            w.draft.putDepositAccount(bank, owner, new BankState.DepositAccount(null, 0L, now, now,
                    BigInteger.ZERO, 0L, 0L, now, Math.max(0, balanceCents),
                    balanceCents > 0 ? "custody_pool_opening_balance" : null,
                    "custody_pool_non_accrual"));
            return;
        }
        if (!account.nonAccrual()) {
            throw new BankException(Reasons.CUSTODY_POOL_ACCRUAL + ":existing:" + owner);
        }
        w.draft.putDepositAccount(bank, owner,
                account.withExcluded(Math.max(0, balanceCents), "custody_pool_non_accrual"));
    }

    /**
     * 托管经营人的池科目只允许在档案声明的结算行上出现；其它银行行一律拒绝，
     * 避免普通付款把池资产记到组合校验看不见的行上。
     */
    private static void requireCustodyLine(BankChart chart, String bank, String holder) {
        BankingProfile.CustodyRef custody = chart.profile().custodians().get(holder);
        if (custody != null && !custody.bankId().equals(bank)) {
            throw new BankException(Reasons.CUSTODY_BANK_MISMATCH + ":" + holder + ":" + bank);
        }
    }

    /** 提款/转出后封顶非应计部分（有息部分先出）。 */
    private static void shrinkDeposit(Working w, String bank, String owner) {
        BankState.DepositAccount account = w.draft.depositAccount(bank, owner);
        if (account == null) {
            return;
        }
        long balanceCents = balanceCents(w, bank, owner);
        if (account.nonAccrual()) {
            w.draft.putDepositAccount(bank, owner,
                    account.withExcluded(Math.max(0, balanceCents), account.nonAccrualReason()));
            return;
        }
        w.draft.putDepositAccount(bank, owner, account.withBalance(balanceCents));
    }

    private static long balanceCents(Working w, String bank, String owner) {
        return InterestAccrual.cents(
                w.draft.gl(bank, w.chart().depositAccountOf(owner)).negate());
    }

    // ------------------------------------------------------------ 贷款

    private static void originate(Working w, BankConfig config, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String borrower = cmd.text("borrower");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, bank);
        requireBank(w, bank);
        requireNoResolution(w, bank);
        String bucket = w.chart().bucketFor(borrower);
        String declared = cmd.body().getOrDefault("bucket", bucket);
        if (!bucket.equals(declared)) {
            throw new BankException(Reasons.LOAN_BUCKET_MISMATCH + ":" + declared);
        }
        syncLoan(w, bank, borrower, now);
        syncDeposit(w, bank, borrower, now);
        BankState.LoanTerms terms = loanTermsForOrigination(config, cmd, now);
        BankState.Loan loan = w.draft.loan(bank, borrower);
        String trancheId = loan.nextTrancheId("L-");
        BankState.Tranche tranche = new BankState.Tranche(trancheId, "LOAN", amount, amount,
                Money.zero(), terms, terms == null ? "profile_none" : null);
        addGl(w, bank, bucket, amount);
        addGl(w, bank, w.chart().depositAccountOf(borrower), amount.negate());
        w.draft.putLoan(bank, borrower, loan.plus(tranche));
        applyCreditPolicy(w, config, bank, borrower, now, amount);
        admit(w, config, bank);
        w.receipt.put("borrower", borrower);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("tranche", trancheId);
        if (terms != null) {
            w.receipt.put("rate", InterestAccrual.rateText(terms.rateU()));
            w.receipt.put("maturityMs", Long.toString(terms.maturityMs()));
        }
    }

    private static void repay(Working w, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String borrower = cmd.text("borrower");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, borrower);
        requireBank(w, bank);
        String bucket = w.chart().bucketFor(borrower);
        BankState.Loan loan = w.draft.loan(bank, borrower);
        String trancheId = optionalTranche(cmd);
        if (trancheId != null) {
            // 定位到具体期次：只能还这一期的本金，绝不挪用其它合同余额。
            BankState.Tranche tranche = requireTranche(loan, trancheId);
            if (amount.compareTo(tranche.onbook()) > 0) {
                throw new BankException(Reasons.REPAY_EXCEEDS_CONTRACT + ":tranche:"
                        + trancheId);
            }
            if (w.draft.gl(bank, w.chart().depositAccountOf(borrower)).negate()
                    .compareTo(amount) < 0) {
                throw new BankException(Reasons.INSUFFICIENT_FUNDS);
            }
            syncLoan(w, bank, borrower, now);
            syncDeposit(w, bank, borrower, now);
            requireDepositRoom(w, bank, borrower, amount);
            BankState.Loan current = w.draft.loan(bank, borrower);
            addGl(w, bank, bucket, amount.negate());
            addGl(w, bank, w.chart().depositAccountOf(borrower), amount);
            w.draft.putLoan(bank, borrower,
                    normalize(allocateOnbookOn(current, trancheId, amount)));
            syncPledgeOfLoan(w, bank, borrower);
            shrinkDeposit(w, bank, borrower);
            w.receipt.put("amount", Money.text(amount));
            w.receipt.put("tranche", trancheId);
            return;
        }
        if (amount.compareTo(loan.onbook()) > 0) {
            throw new BankException(Reasons.REPAY_EXCEEDS_ONBOOK);
        }
        if (amount.compareTo(loan.contract()) > 0) {
            throw new BankException(Reasons.REPAY_EXCEEDS_CONTRACT);
        }
        if (w.draft.gl(bank, w.chart().depositAccountOf(borrower)).negate()
                .compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        syncLoan(w, bank, borrower, now);
        syncDeposit(w, bank, borrower, now);
        requireDepositRoom(w, bank, borrower, amount);
        BankState.Loan current = w.draft.loan(bank, borrower);
        addGl(w, bank, bucket, amount.negate());
        addGl(w, bank, w.chart().depositAccountOf(borrower), amount);
        w.draft.putLoan(bank, borrower, normalize(allocateOnbook(current, amount)));
        syncPledgeOfLoan(w, bank, borrower);
        shrinkDeposit(w, bank, borrower);
        w.receipt.put("amount", Money.text(amount));
    }

    private static void writeOff(Working w, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String borrower = cmd.text("borrower");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, bank);
        requireBank(w, bank);
        String loanAccount = w.chart().bucketFor(borrower);
        String provisionAccount = w.chart().llpFor(borrower);
        BankState.Loan loan = w.draft.loan(bank, borrower);
        BigDecimal provision = w.draft.gl(bank, provisionAccount).negate();
        if (amount.compareTo(provision) > 0) {
            throw new BankException(Reasons.INSUFFICIENT_PROVISION);
        }
        if (amount.compareTo(loan.onbook()) > 0) {
            throw new BankException(Reasons.ONBOOK_INSUFFICIENT);
        }
        syncLoan(w, bank, borrower, now);
        BankState.Loan current = w.draft.loan(bank, borrower);
        addGl(w, bank, loanAccount, amount.negate());
        addGl(w, bank, provisionAccount, amount);
        w.draft.putLoan(bank, borrower, normalize(allocateWriteOff(current, amount)));
        syncPledgeOfLoan(w, bank, borrower);
        w.receipt.put("amount", Money.text(amount));
    }

    private static void thirdPartyPay(Working w, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String borrower = cmd.text("borrower");
        String payer = cmd.text("payer");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, payer);
        requireBank(w, bank);
        BankState.Loan loan = w.draft.loan(bank, borrower);
        if (amount.compareTo(loan.contract()) > 0) {
            throw new BankException(Reasons.EXCEEDS_CONTRACT);
        }
        if (w.cash(payer).compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        syncLoan(w, bank, borrower, now);
        BankState.Loan current = w.draft.loan(bank, borrower);
        BigDecimal againstWrittenOff = amount.min(current.writtenOff());
        BigDecimal againstOnBook = amount.subtract(againstWrittenOff);
        move(w, payer, w.chart().cashAccountOf(payer), w.chart().reserveAccountOf(bank), amount);
        if (againstWrittenOff.signum() > 0) {
            addGl(w, bank, "RECOVERY", againstWrittenOff.negate());
        }
        if (againstOnBook.signum() > 0) {
            addGl(w, bank, w.chart().bucketFor(borrower), againstOnBook.negate());
        }
        BankState.Loan after =
                allocateOnbook(allocateWrittenOff(current, againstWrittenOff), againstOnBook);
        w.draft.putLoan(bank, borrower, normalize(after));
        syncPledgeOfLoan(w, bank, borrower);
        w.receipt.put("borrower", borrower);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("recovery", Money.text(againstWrittenOff));
    }

    private static void waive(Working w, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String borrower = cmd.text("borrower");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, bank);
        requireBank(w, bank);
        BankState.Loan loan = w.draft.loan(bank, borrower);
        if (amount.compareTo(loan.writtenOff()) > 0) {
            throw new BankException(Reasons.WRITTEN_OFF_EXCEEDED);
        }
        if (amount.compareTo(loan.contract()) > 0) {
            throw new BankException(Reasons.EXCEEDS_CONTRACT);
        }
        syncLoan(w, bank, borrower, now);
        BankState.Loan current = w.draft.loan(bank, borrower);
        w.draft.putLoan(bank, borrower, normalize(allocateWrittenOff(current, amount)));
        syncPledgeOfLoan(w, bank, borrower);
        w.receipt.put("borrower", borrower);
        w.receipt.put("amount", Money.text(amount));
    }

    private static void accrueLoanCommand(Working w, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String borrower = cmd.text("borrower");
        long toMs = cmd.integer("toMs");
        requireActor(cmd, bank);
        requireBank(w, bank);
        accrueLoan(w, bank, borrower, optionalTranche(cmd), toMs, now);
        BankState.Loan loan = w.draft.loan(bank, borrower);
        w.receipt.put("borrower", borrower);
        w.receipt.put("toMs", Long.toString(toMs));
        w.receipt.put("accrued", Money.text(loan.accruedInterest()));
        w.receipt.put("interestReceivable", Money.text(loan.interestReceivable()));
        w.receipt.put("tranches", Integer.toString(loan.tranches().size()));
    }

    private static void payLoanInterest(Working w, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String borrower = cmd.text("borrower");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireBank(w, bank);
        if (!borrower.equals(cmd.actorOwnerId())) {
            throw new BankException(Reasons.ACTOR_NOT_AUTHORIZED + ":borrower-only");
        }
        BankState.Loan loan = w.draft.loan(bank, borrower);
        if (loan.tranches().isEmpty()) {
            throw new BankException(Reasons.NO_TERMS);
        }
        String trancheId = optionalTranche(cmd);
        accrueLoan(w, bank, borrower, trancheId, now, now);
        loan = w.draft.loan(bank, borrower);
        BigDecimal due = trancheId == null ? loan.interestReceivable()
                : requireTranche(loan, trancheId).terms().interestReceivable();
        if (amount.compareTo(due) > 0) {
            throw new BankException(Reasons.INTEREST_EXCEEDS_ACCRUED);
        }
        if (w.draft.gl(bank, w.chart().depositAccountOf(borrower)).negate()
                .compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        syncDeposit(w, bank, borrower, now);
        requireDepositRoom(w, bank, borrower, amount);
        addGl(w, bank, w.chart().depositAccountOf(borrower), amount);
        addGl(w, bank, BankChart.INTEREST_RECEIVABLE, amount.negate());
        w.draft.putLoan(bank, borrower, normalize(trancheId == null
                ? allocateInterest(loan, amount) : allocateInterestOn(loan, trancheId, amount)));
        shrinkDeposit(w, bank, borrower);
        w.receipt.put("borrower", borrower);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("interestReceivable",
                Money.text(loan.interestReceivable().subtract(amount)));
    }

    private static void matureLoan(Working w, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String borrower = cmd.text("borrower");
        requireActor(cmd, bank);
        requireBank(w, bank);
        BankState.Loan loan = w.draft.loan(bank, borrower);
        boolean anyAccrual = false;
        boolean matured = false;
        for (BankState.Tranche tranche : loan.tranches()) {
            if (tranche.terms() == null) {
                continue;
            }
            anyAccrual = true;
            if (now >= tranche.terms().maturityMs()) {
                matured = true;
            }
        }
        if (!anyAccrual) {
            throw new BankException(Reasons.NO_TERMS);
        }
        if (!matured) {
            throw new BankException(Reasons.NOT_YET_MATURED);
        }
        accrueLoan(w, bank, borrower, now, now);
        loan = w.draft.loan(bank, borrower);
        List<BankState.Tranche> next = new ArrayList<>();
        for (BankState.Tranche tranche : loan.tranches()) {
            if (tranche.terms() != null && now >= tranche.terms().maturityMs()) {
                boolean settled = tranche.contract().signum() == 0
                        && tranche.interestReceivable().signum() == 0;
                next.add(new BankState.Tranche(tranche.trancheId(), tranche.source(),
                        tranche.contract(), tranche.onbook(), tranche.writtenOff(),
                        tranche.terms().withStatus(
                                settled ? BankState.SETTLED : BankState.PAST_DUE, !settled),
                        null));
            } else {
                next.add(tranche);
            }
        }
        BankState.Loan updated = new BankState.Loan(next);
        w.draft.putLoan(bank, borrower, updated);
        w.receipt.put("borrower", borrower);
        w.receipt.put("status", updated.status());
        w.receipt.put("overdue", Boolean.toString(updated.overdue()));
        w.receipt.put("principal", Money.text(updated.contract()));
        w.receipt.put("interestReceivable", Money.text(updated.interestReceivable()));
    }

    // ------------------------------------------------------------ 承诺

    private static void commit(Working w, BankConfig config, BankCommand cmd) {
        String bank = cmd.text("bank");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, bank);
        requireBank(w, bank);
        requireNoResolution(w, bank);
        if (!config.legacyCommitmentEnabled()) {
            throw new BankException(Reasons.LEGACY_COMMITMENT_DISABLED);
        }
        w.draft.putCommitment(bank, w.draft.commitment(bank).add(amount));
        admit(w, config, bank);
        w.receipt.put("bank", bank);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("source", "LEGACY_SUMMARY");
    }

    private static void commitmentOpen(Working w, BankConfig config, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String id = commitmentId(cmd);
        String borrower = cmd.text("borrower");
        BigDecimal limit = Money.business(cmd.decimal("limit"));
        long expiryMs = cmd.integer("expiryMs");
        long tenorMs = cmd.integer("tenorMs");
        long rateU = InterestAccrual.rateU(cmd.decimal("rate"));
        String authorizedBy = cmd.text("authorizedBy");
        requireActor(cmd, bank);
        requireBank(w, bank);
        requireNoResolution(w, bank);
        if (!config.interestEnabled()) {
            throw new BankException(Reasons.NO_TERMS + ":profile-disabled");
        }
        w.chart().bucketFor(borrower); // 只校验借款人身份（未知借款人立即拒绝）
        if (w.draft.commitmentRecord(bank, id) != null) {
            throw new BankException(Reasons.COMMITMENT_EXISTS + ":" + id);
        }
        if (expiryMs <= now) {
            throw new BankException(Reasons.COMMITMENT_EXPIRY_INVALID);
        }
        if (tenorMs <= 0) {
            throw new BankException(Reasons.TENOR_INVALID);
        }
        if (!authorizedBy.equals(bank)
                && !w.accounts.containsKey(w.chart().cashAccountOf(authorizedBy))) {
            throw new BankException(Reasons.UNKNOWN_AUTHORIZED + ":" + authorizedBy);
        }
        w.draft.putCommitmentRecord(bank, id, new BankState.Commitment(borrower, limit,
                Money.zero(), expiryMs, tenorMs, rateU, authorizedBy,
                BankState.COMMITMENT_OPEN));
        admit(w, config, bank);
        w.receipt.put("commitment", id);
        w.receipt.put("borrower", borrower);
        w.receipt.put("limit", Money.text(limit));
        w.receipt.put("expiryMs", Long.toString(expiryMs));
        w.receipt.put("rate", InterestAccrual.rateText(rateU));
        w.receipt.put("undrawn", Money.text(limit));
        w.receipt.put("source", "DETAIL");
    }

    private static void commitmentDraw(Working w, BankConfig config, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String id = commitmentId(cmd);
        String borrower = cmd.text("borrower");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireBank(w, bank);
        requireNoResolution(w, bank);
        BankState.Commitment commitment = w.draft.commitmentRecord(bank, id);
        if (commitment == null) {
            throw new BankException(Reasons.COMMITMENT_UNKNOWN + ":" + id);
        }
        if (!commitment.borrower().equals(borrower)) {
            throw new BankException(Reasons.COMMITMENT_BORROWER_MISMATCH + ":"
                    + commitment.borrower());
        }
        if (!borrower.equals(cmd.actorOwnerId())
                && !commitment.authorizedBy().equals(cmd.actorOwnerId())) {
            throw new BankException(Reasons.ACTOR_NOT_AUTHORIZED + ":draw");
        }
        if (!BankState.COMMITMENT_OPEN.equals(commitment.status())) {
            throw new BankException(Reasons.COMMITMENT_CLOSED + ":" + commitment.status());
        }
        if (now >= commitment.expiryMs()) {
            throw new BankException(Reasons.COMMITMENT_EXPIRED + ":" + commitment.expiryMs());
        }
        if (commitment.drawnCumulative().add(amount).compareTo(commitment.limit()) > 0) {
            throw new BankException(Reasons.COMMITMENT_LIMIT);
        }
        syncLoan(w, bank, borrower, now);
        syncDeposit(w, bank, borrower, now);
        long maturityMs = saturate(now, commitment.tenorMs());
        BankState.Loan loan = w.draft.loan(bank, borrower);
        String tranchePrefix = id + "#";
        String trancheId = loan.nextTrancheId(tranchePrefix);
        String source = "COMMITMENT:" + id + "#"
                + trancheId.substring(tranchePrefix.length());
        BankState.LoanTerms terms = new BankState.LoanTerms(commitment.rateU(), now, maturityMs,
                BigInteger.ZERO, 0, 0, now, BankState.CURRENT, false);
        String bucket = w.chart().bucketFor(borrower);
        addGl(w, bank, bucket, amount);
        addGl(w, bank, w.chart().depositAccountOf(borrower), amount.negate());
        w.draft.putLoan(bank, borrower, loan.plus(new BankState.Tranche(trancheId,
                source, amount, amount, Money.zero(), terms, null)));
        w.draft.putCommitmentRecord(bank, id, commitment.withDraw(amount));
        applyCreditPolicy(w, config, bank, borrower, now, amount);
        admit(w, config, bank);
        w.receipt.put("commitment", id);
        w.receipt.put("borrower", borrower);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("tranche", trancheId);
        w.receipt.put("drawnCumulative",
                Money.text(commitment.drawnCumulative().add(amount)));
        w.receipt.put("undrawn", Money.text(commitment.undrawn().subtract(amount)));
        w.receipt.put("maturityMs", Long.toString(maturityMs));
        w.receipt.put("source", "DETAIL");
    }

    private static String commitmentId(BankCommand cmd) {
        String id = cmd.text("commitment");
        if (id.indexOf('|') >= 0) {
            throw new BankException(Reasons.PROTOCOL_ERROR + ":commitment");
        }
        return id;
    }

    private static long saturate(long now, long tenorMs) {
        try {
            return Math.addExact(now, tenorMs);
        } catch (ArithmeticException failure) {
            throw new BankException(Reasons.MATURITY_INVALID);
        }
    }

    // ------------------------------------------------------------ B3 救助设施

    private static void requireFacilityProfile(BankConfig config) {
        if (!config.facilityEnabled()) {
            throw new BankException(Reasons.FACILITY_PROFILE_INVALID + ":profile-disabled");
        }
    }

    private static BankState.FacilityAccount requireFacility(Working w, String bank,
                                                             String authorizationId) {
        BankState.FacilityAccount account = w.draft.facilityAccount(bank, authorizationId);
        if (account == null) {
            throw new BankException(Reasons.FACILITY_UNKNOWN_AUTHORIZATION + ":"
                    + authorizationId);
        }
        return account;
    }

    private static void requireNoResolution(Working w, String bank) {
        BankState.Resolution resolution = w.draft.resolution(bank);
        if (resolution != null && BankRules.RESOLUTION_ACTIVE.equals(resolution.status())) {
            throw new BankException(Reasons.RESOLUTION_ACTIVE + ":" + bank);
        }
    }

    private static String authorizationId(BankCommand cmd) {
        String id = cmd.text("authorization");
        if (id.indexOf('|') >= 0) {
            throw new BankException(Reasons.PROTOCOL_ERROR + ":authorization");
        }
        return id;
    }

    private static long freeCollateral(Working w, String bank) {
        long count = 0;
        for (BankState.Collateral collateral : w.draft.collaterals().values()) {
            if (collateral.owner().equals(bank)
                    && BankRules.COLLATERAL_FREE.equals(collateral.status())) {
                count++;
            }
        }
        return count;
    }

    /** 提供者批准额度；银行不能自授权。额度是授权总额，未提额由累计已提派生。 */
    private static void facilityAuthorize(Working w, BankConfig config, BankCommand cmd,
                                          long now) {
        String bank = cmd.text("bank");
        String provider = cmd.text("provider");
        String id = authorizationId(cmd);
        BigDecimal limit = Money.business(cmd.decimal("limit"));
        long rateU = InterestAccrual.rateU(cmd.decimal("rate"));
        long tenorMs = cmd.integer("loanTenorMs");
        long authExpiry = cmd.integer("authorizationExpiryMs");
        long maturity = cmd.integer("facilityMaturityMs");
        String policy = cmd.text("collateralPolicy");
        requireFacilityProfile(config);
        requireActor(cmd, provider);
        if (!provider.equals(w.chart().providerKey())) {
            throw new BankException(Reasons.FACILITY_UNKNOWN_PROVIDER);
        }
        requireBank(w, bank);
        requireNoResolution(w, bank);
        if (w.draft.facilityAccount(bank, id) != null) {
            throw new BankException(Reasons.FACILITY_AUTHORIZATION_DUPLICATE + ":" + id);
        }
        if (tenorMs <= 0) {
            throw new BankException(Reasons.TENOR_INVALID);
        }
        if (authExpiry <= now) {
            throw new BankException(Reasons.FACILITY_AUTHORIZATION_EXPIRED);
        }
        if (maturity <= now) {
            throw new BankException(Reasons.FACILITY_MATURITY_INVALID);
        }
        if (!BankRules.COLLATERAL_SECURED.equals(policy)
                && !BankRules.COLLATERAL_UNSECURED.equals(policy)) {
            throw new BankException(Reasons.FACILITY_COLLATERAL_POLICY_INVALID + ":" + policy);
        }
        if (BankRules.COLLATERAL_SECURED.equals(policy) && freeCollateral(w, bank) == 0) {
            throw new BankException(Reasons.COLLATERAL_SHORTFALL + ":none");
        }
        w.draft.putFacilityAccount(new BankState.FacilityAccount(bank, id, provider, limit,
                Money.zero(), Money.zero(), Money.zero(), Money.zero(), rateU, tenorMs, now,
                authExpiry, maturity, BigInteger.ZERO, 0, 0, now, 0, 0, 0, 0, policy, Set.of(),
                BankRules.FACILITY_OPEN));
        admit(w, config, bank);
        w.receipt.put("authorization", id);
        w.receipt.put("limit", Money.text(limit));
        w.receipt.put("undrawn", Money.text(limit));
        w.receipt.put("rate", InterestAccrual.rateText(rateU));
        w.receipt.put("authorizationExpiryMs", Long.toString(authExpiry));
        w.receipt.put("facilityMaturityMs", Long.toString(maturity));
        w.receipt.put("collateralPolicy", policy);
    }

    /** 银行按已批准额度提款：真实资金从提供者转到该行准备金，形成名义债务。 */
    private static void facilityDraw(Working w, BankConfig config, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String provider = cmd.text("provider");
        String id = authorizationId(cmd);
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        String collateralId = cmd.optional("collateralId");
        requireFacilityProfile(config);
        requireActor(cmd, bank);
        requireBank(w, bank);
        requireNoResolution(w, bank);
        BankState.FacilityAccount account = requireFacility(w, bank, id);
        if (!provider.equals(account.provider())) {
            throw new BankException(Reasons.FACILITY_UNKNOWN_PROVIDER);
        }
        if (account.closed()) {
            throw new BankException(Reasons.FACILITY_CLOSED);
        }
        if (BankRules.FACILITY_DEFAULT_STATUS.equals(account.status())) {
            throw new BankException(Reasons.FACILITY_IN_DEFAULT);
        }
        if (now >= account.authorizationExpiryMs()) {
            throw new BankException(Reasons.FACILITY_AUTHORIZATION_EXPIRED + ":"
                    + account.authorizationExpiryMs());
        }
        if (now >= account.facilityMaturityMs()) {
            throw new BankException(Reasons.FACILITY_MATURED + ":"
                    + account.facilityMaturityMs());
        }
        if (amount.compareTo(account.undrawn()) > 0) {
            throw new BankException(Reasons.FACILITY_OVER_DRAW + ":"
                    + Money.text(account.undrawn()));
        }
        if (w.cash(provider).compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        syncFacility(w, bank, id, now);
        account = requireFacility(w, bank, id);
        Set<String> pledged = new LinkedHashSet<>(account.collateral());
        if (account.secured()) {
            if (collateralId == null) {
                throw new BankException(Reasons.COLLATERAL_UNKNOWN + ":missing");
            }
            BankState.Collateral collateral = w.draft.collateral(collateralId);
            if (collateral == null) {
                throw new BankException(Reasons.COLLATERAL_UNKNOWN + ":" + collateralId);
            }
            if (!collateral.owner().equals(bank)) {
                throw new BankException(Reasons.COLLATERAL_OWNER_MISMATCH + ":" + collateralId);
            }
            if (BankRules.COLLATERAL_DISPOSED.equals(collateral.status())) {
                throw new BankException(Reasons.COLLATERAL_DISPOSED + ":" + collateralId);
            }
            if (!BankRules.COLLATERAL_FREE.equals(collateral.status())) {
                throw new BankException(Reasons.COLLATERAL_ENCUMBERED + ":" + collateralId);
            }
            if (account.nominal().add(amount).compareTo(collateral.usableValue()) > 0) {
                throw new BankException(Reasons.COLLATERAL_SHORTFALL + ":"
                        + Money.text(collateral.usableValue()));
            }
            w.draft.putCollateral(collateral.withPledge(
                    w.chart().facilityKeyOf(bank, id), BankRules.COLLATERAL_PLEDGED));
            pledged.add(collateralId);
        } else if (collateralId != null) {
            throw new BankException(Reasons.FACILITY_COLLATERAL_POLICY_INVALID + ":unsecured");
        }
        move(w, provider, w.chart().cashAccountOf(provider), w.chart().reserveAccountOf(bank), amount);
        addGl(w, bank, BankChart.FACILITY_DEBT, amount.negate());
        long frozenMaturity = saturate(now, account.loanTenorMs());
        w.draft.putFacilityAccount(account.withDraw(amount, frozenMaturity, pledged));
        admit(w, config, bank);
        long maturity = Math.min(account.facilityMaturityMs(), frozenMaturity);
        w.receipt.put("authorization", id);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("nominal", Money.text(account.nominal().add(amount)));
        w.receipt.put("undrawn", Money.text(account.undrawn().subtract(amount)));
        w.receipt.put("maturityMs", Long.toString(maturity));
    }

    private static void facilityAccrueCommand(Working w, BankConfig config, BankCommand cmd,
                                              long now) {
        String bank = cmd.text("bank");
        String id = authorizationId(cmd);
        long toMs = cmd.integer("toMs");
        requireFacilityProfile(config);
        requireActor(cmd, bank);
        requireBank(w, bank);
        requireFacility(w, bank, id);
        touchFacility(w, bank, id, now);
        accrueFacility(w, bank, id, toMs, now);
        BankState.FacilityAccount account = requireFacility(w, bank, id);
        w.receipt.put("authorization", id);
        w.receipt.put("toMs", Long.toString(toMs));
        w.receipt.put("accrued", Money.text(account.interestAccrued()));
        w.receipt.put("interestPayable", Money.text(account.interestPayable()));
    }

    private static void facilityPayInterest(Working w, BankConfig config, BankCommand cmd,
                                            long now) {
        String bank = cmd.text("bank");
        String provider = cmd.text("provider");
        String id = authorizationId(cmd);
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireFacilityProfile(config);
        requireActor(cmd, bank);
        requireBank(w, bank);
        BankState.FacilityAccount account = requireFacility(w, bank, id);
        if (!provider.equals(account.provider())) {
            throw new BankException(Reasons.FACILITY_UNKNOWN_PROVIDER);
        }
        if (account.closed()) {
            throw new BankException(Reasons.FACILITY_CLOSED);
        }
        touchFacility(w, bank, id, now);
        account = requireFacility(w, bank, id);
        syncFacility(w, bank, id, now);
        account = requireFacility(w, bank, id);
        if (amount.compareTo(account.interestPayable()) > 0) {
            throw new BankException(Reasons.INTEREST_EXCEEDS_ACCRUED);
        }
        if (w.cash(bank).compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        move(w, bank, w.chart().reserveAccountOf(bank), w.chart().cashAccountOf(provider), amount);
        addGl(w, bank, BankChart.FACILITY_INTEREST_PAYABLE, amount);
        w.draft.putFacilityAccount(account.with(account.limit(), account.drawnCumulative(),
                account.onbook(), account.writtenOff(), account.allowance(),
                account.paidCents() + InterestAccrual.cents(amount), account.impairCents(),
                account.reversalCents(), account.recoveryCents(), account.waiverLossCents(),
                null, account.status()));
        maybeClose(w, bank, id);
        w.receipt.put("authorization", id);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("interestPayable", Money.text(account.interestPayable().subtract(amount)));
    }

    private static void facilityImpair(Working w, BankConfig config, BankCommand cmd, long now) {
        String provider = cmd.text("provider");
        String id = authorizationId(cmd);
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireFacilityProfile(config);
        requireActor(cmd, provider);
        BankState.FacilityAccount account = requireProviderFacility(w, provider, id);
        touchFacility(w, account.bank(), id, now);
        account = requireProviderFacility(w, provider, id);
        if (account.closed()) {
            throw new BankException(Reasons.FACILITY_CLOSED);
        }
        if (amount.compareTo(account.onbook().subtract(account.allowance())) > 0) {
            throw new BankException(Reasons.PROVISION_EXCEEDS_GROSS);
        }
        w.draft.putFacilityAccount(account.with(account.limit(), account.drawnCumulative(),
                account.onbook(), account.writtenOff(), account.allowance().add(amount),
                account.paidCents(), account.impairCents() + InterestAccrual.cents(amount),
                account.reversalCents(), account.recoveryCents(), account.waiverLossCents(),
                null, account.status()));
        BankState.FacilityAccount after = requireFacility(w, account.bank(), id);
        w.receipt.put("authorization", id);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("allowance", Money.text(after.allowance()));
        w.receipt.put("netBook", Money.text(after.netBook()));
        w.receipt.put("nominal", Money.text(after.nominal()));
    }

    private static void facilityWriteOff(Working w, BankConfig config, BankCommand cmd, long now) {
        String provider = cmd.text("provider");
        String id = authorizationId(cmd);
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireFacilityProfile(config);
        requireActor(cmd, provider);
        BankState.FacilityAccount account = requireProviderFacility(w, provider, id);
        touchFacility(w, account.bank(), id, now);
        account = requireProviderFacility(w, provider, id);
        if (account.closed()) {
            throw new BankException(Reasons.FACILITY_CLOSED);
        }
        if (amount.compareTo(account.allowance()) > 0) {
            throw new BankException(Reasons.INSUFFICIENT_PROVISION);
        }
        if (amount.compareTo(account.onbook()) > 0) {
            throw new BankException(Reasons.ONBOOK_INSUFFICIENT);
        }
        w.draft.putFacilityAccount(account.with(account.limit(), account.drawnCumulative(),
                account.onbook().subtract(amount), account.writtenOff().add(amount),
                account.allowance().subtract(amount), account.paidCents(),
                account.impairCents(), account.reversalCents(), account.recoveryCents(),
                account.waiverLossCents(), null, account.status()));
        BankState.FacilityAccount after = requireFacility(w, account.bank(), id);
        w.receipt.put("authorization", id);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("nominal", Money.text(after.nominal()));
        w.receipt.put("onbook", Money.text(after.onbook()));
        w.receipt.put("writtenOff", Money.text(after.writtenOff()));
    }

    /** 核销后备查回收：真实付款方付给提供者；名义与备查同步减少，回收是已零账面部分的收入。 */
    private static void facilityRecover(Working w, BankConfig config, BankCommand cmd, long now) {
        String provider = cmd.text("provider");
        String id = authorizationId(cmd);
        String payer = cmd.text("payer");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireFacilityProfile(config);
        requireActor(cmd, payer);
        BankState.FacilityAccount account = requireProviderFacility(w, provider, id);
        touchFacility(w, account.bank(), id, now);
        account = requireProviderFacility(w, provider, id);
        if (amount.compareTo(account.writtenOff()) > 0) {
            throw new BankException(Reasons.WRITTEN_OFF_EXCEEDED);
        }
        if (w.cash(payer).compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        move(w, payer, w.chart().cashAccountOf(payer), w.chart().cashAccountOf(provider), amount);
        addGl(w, account.bank(), BankChart.FACILITY_DEBT, amount);
        addGl(w, account.bank(), "RECOVERY", amount.negate());
        w.draft.putFacilityAccount(account.with(account.limit(), account.drawnCumulative(),
                account.onbook(), account.writtenOff().subtract(amount), account.allowance(),
                account.paidCents(), account.impairCents(), account.reversalCents(),
                account.recoveryCents() + InterestAccrual.cents(amount),
                account.waiverLossCents(), null, account.status()));
        BankState.FacilityAccount after = requireFacility(w, account.bank(), id);
        w.receipt.put("authorization", id);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("nominal", Money.text(after.nominal()));
        w.receipt.put("writtenOff", Money.text(after.writtenOff()));
        w.receipt.put("recovery", Money.text(amount));
        maybeClose(w, account.bank(), id);
    }

    /** B3 豁免：只限备查（已计提部分不得重复确认损失），银行同额减债并记让步收益。 */
    private static void facilityWaiveB3(Working w, BankConfig config, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String provider = cmd.text("provider");
        String id = authorizationId(cmd);
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, provider);
        requireBank(w, bank);
        BankState.FacilityAccount account = requireFacility(w, bank, id);
        touchFacility(w, bank, id, now);
        account = requireFacility(w, bank, id);
        if (!provider.equals(account.provider())) {
            throw new BankException(Reasons.FACILITY_UNKNOWN_PROVIDER);
        }
        if (account.closed()) {
            throw new BankException(Reasons.FACILITY_CLOSED);
        }
        if (amount.compareTo(account.writtenOff()) > 0) {
            throw new BankException(Reasons.OVERWAIVE_FACILITY + ":"
                    + Money.text(account.writtenOff()));
        }
        addGl(w, bank, BankChart.FACILITY_DEBT, amount);
        addGl(w, bank, "GAIN.WAIVE", amount.negate());
        w.draft.putFacilityAccount(account.with(account.limit(), account.drawnCumulative(),
                account.onbook(), account.writtenOff().subtract(amount), account.allowance(),
                account.paidCents(), account.impairCents(), account.reversalCents(),
                account.recoveryCents(), account.waiverLossCents(), null, account.status()));
        maybeClose(w, bank, id);
        BankState.FacilityAccount after = requireFacility(w, bank, id);
        w.receipt.put("authorization", id);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("nominal", Money.text(after.nominal()));
        w.receipt.put("interestPayable", Money.text(after.interestPayable()));
    }

    private static BankState.FacilityAccount requireProviderFacility(Working w, String provider,
                                                                    String id) {
        for (BankState.FacilityAccount account : w.draft.facilityAccounts().values()) {
            if (account.authorizationId().equals(id) && account.provider().equals(provider)) {
                return account;
            }
        }
        throw new BankException(Reasons.FACILITY_UNKNOWN_AUTHORIZATION + ":" + id);
    }

    /** 本金与利息都清才算结清；结清同批释放押品，并把准备夹紧到剩余表内（超出部分转回）。 */
    private static void maybeClose(Working w, String bank, String id) {
        BankState.FacilityAccount account = w.draft.facilityAccount(bank, id);
        if (account == null || account.closed()) {
            return;
        }
        account = clampAllowance(account);
        if (account.nominal().signum() != 0 || account.postedCents() != account.paidCents()) {
            return;
        }
        Set<String> pledged = new LinkedHashSet<>(account.collateral());
        for (String collateralId : pledged) {
            BankState.Collateral collateral = w.draft.collateral(collateralId);
            if (collateral != null) {
                w.draft.putCollateral(collateral.withPledge(null, BankRules.COLLATERAL_FREE));
            }
        }
        String status = account.waiverLossCents() > 0
                ? BankRules.FACILITY_CLOSED_WITH_LOSS : BankRules.FACILITY_CLOSED_REPAID;
        w.draft.putFacilityAccount(account.with(account.limit(), account.drawnCumulative(),
                account.onbook(), account.writtenOff(), account.allowance(), account.paidCents(),
                account.impairCents(), account.reversalCents(), account.recoveryCents(),
                account.waiverLossCents(), Set.of(), status));
    }

    /** 任何入口用真实 now 检查到期：到期且有本/息未清 → PAST_DUE（禁新提款，仍计息/还债）。 */
    private static void touchFacility(Working w, String bank, String id, long now) {
        BankState.FacilityAccount account = requireFacility(w, bank, id);
        if (account.closed() || BankRules.FACILITY_DEFAULT_STATUS.equals(account.status())) {
            return;
        }
        if (now < account.facilityMaturityMs()) {
            return;
        }
        boolean outstanding = account.nominal().signum() > 0
                || account.postedCents() != account.paidCents();
        if (outstanding && !BankRules.FACILITY_PAST_DUE.equals(account.status())) {
            w.draft.putFacilityAccount(account.withStatus(BankRules.FACILITY_PAST_DUE));
        }
    }

    /** 把准备夹紧到剩余表内：超出部分记为转回并落库，避免 allowance>onbook 进构造器。 */
    private static BankState.FacilityAccount clampAllowance(BankState.FacilityAccount account) {
        BigDecimal allowance = account.allowance().min(account.onbook());
        long release = InterestAccrual.cents(account.allowance())
                - InterestAccrual.cents(allowance);
        if (release <= 0) {
            return account;
        }
        return account.with(account.limit(), account.drawnCumulative(), account.onbook(),
                account.writtenOff(), allowance, account.paidCents(), account.impairCents(),
                account.reversalCents() + release, account.recoveryCents(),
                account.waiverLossCents(), account.collateral(), account.status());
    }

    /** 余额变更前把设施利息累计到当前时点（两侧同一分子函数）。 */
    private static void syncFacility(Working w, String bank, String id, long now) {
        BankState.FacilityAccount account = requireFacility(w, bank, id);
        if (now < account.lastAccruedMs()) {
            throw new BankException(Reasons.TIME_REVERSED + ":" + account.lastAccruedMs());
        }
        accrueFacility(w, bank, id, now, now);
    }

    private static void accrueFacility(Working w, String bank, String id, long toMs, long now) {
        BankState.FacilityAccount account = requireFacility(w, bank, id);
        if (toMs > now) {
            throw new BankException(Reasons.FUTURE_ACCRUAL + ":" + toMs + ">" + now);
        }
        if (toMs < account.lastAccruedMs()) {
            throw new BankException(Reasons.TIME_REVERSED + ":" + account.lastAccruedMs());
        }
        BigInteger numerator = InterestAccrual.add(account.numerator(),
                InterestAccrual.cents(account.onbook()), account.rateU(),
                toMs - account.lastAccruedMs());
        long target = InterestAccrual.targetCents(numerator);
        long posting = target - account.postedCents();
        if (posting < 0) {
            throw new BankException(Reasons.TIME_REVERSED);
        }
        if (posting > 0) {
            BigDecimal amount = InterestAccrual.money(posting);
            addGl(w, bank, BankChart.FACILITY_INTEREST_EXPENSE, amount);
            addGl(w, bank, BankChart.FACILITY_INTEREST_PAYABLE, amount.negate());
        }
        w.draft.putFacilityAccount(account.withAccrual(numerator, target, toMs));
    }

    // ------------------------------------------------------------ B4 债权转让

    /** 有限买方凭本人冻结许可受让整笔贷款债权；应收利息随债权移交，借款人名义债务不变。 */
    private static void loanAssign(Working w, BankConfig config, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String borrower = cmd.text("borrower");
        String assignment = cmd.text("assignment");
        String purchaseId = cmd.text("purchase");
        requireFacilityProfile(config);
        requireActor(cmd, bank);
        requireBank(w, bank);
        if (assignment.indexOf('|') >= 0) {
            throw new BankException(Reasons.PROTOCOL_ERROR + ":assignment");
        }
        if (w.draft.assignedClaim(assignment) != null) {
            throw new BankException(Reasons.CLAIM_UNKNOWN + ":exists:" + assignment);
        }
        BankState.Loan loan = w.draft.loan(bank, borrower);
        if (loan.tranches().isEmpty()) {
            throw new BankException(Reasons.NO_TERMS + ":no-loan");
        }
        BankState.PurchaseConsent purchase = requirePurchase(w, purchaseId, bank,
                BankRules.SUBJECT_LOAN, w.chart().loanKeyOf(bank, borrower), now);
        String buyer = purchase.buyer();
        BigDecimal price = purchase.price();
        String declaredBuyer = cmd.optional("buyer");
        if (declaredBuyer != null && !declaredBuyer.equals(buyer)) {
            throw new BankException(Reasons.PURCHASE_MISMATCH + ":buyer");
        }
        String declaredPrice = cmd.optional("price");
        if (declaredPrice != null && !Money.business(cmd.decimal("price")).equals(price)) {
            throw new BankException(Reasons.PURCHASE_MISMATCH + ":price");
        }
        if (purchase.subject().equals("") || buyer.equals(bank)) {
            throw new BankException(Reasons.ASSIGN_SELF);
        }
        if (isPledgedLoan(w, bank, borrower)) {
            throw new BankException(Reasons.COLLATERAL_PLEDGED + ":"
                    + w.chart().loanKeyOf(bank, borrower));
        }
        if (price.compareTo(w.cash(buyer)) > 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        move(w, buyer, w.chart().cashAccountOf(buyer), w.chart().reserveAccountOf(bank), price);
        Assigned assigned = assignLoanCore(w, bank, borrower, buyer, price, assignment, now);
        w.draft.putPurchase(purchase.consumed());
        w.receipt.put("assignment", assignment);
        w.receipt.put("purchase", purchaseId);
        w.receipt.put("buyer", buyer);
        w.receipt.put("price", Money.text(price));
        w.receipt.put("nominal", Money.text(assigned.claim().nominal()));
        w.receipt.put("interestTransferred", Money.text(assigned.interest()));
        w.receipt.put("sellerPnl", Money.text(assigned.pnl()));
        w.receipt.put("borrowerDebt", Money.text(assigned.claim().nominal()));
    }

    /** 一次债权移交的结果：新受让记录、移交的应计利息、卖方损益（=价 − 含息账面）。 */
    private record Assigned(BankState.AssignedClaim claim, BigDecimal interest, BigDecimal pnl) {
    }

    /**
     * 把一笔真实贷款债权按普通转让口径移出银行并交给买方；不移动现金（由调用方处理）。
     *
     * <p>移出的是整笔债权：表内 + 备查、全部期次条款（利率/到期/应计锚）与应收利息、原借款人；
     * 银行按 `价 − (onbook − allowance + 应收利息)` 确认损益。处置贷款押品与普通转让共用本方法，
     * 保证同一经济事件同一口径（不受理“清债冒充处置”）。</p>
     */
    private static Assigned assignLoanCore(Working w, String bank, String borrower, String buyer,
                                           BigDecimal price, String claimId, long now) {
        syncLoan(w, bank, borrower, now);
        BankState.Loan loan = w.draft.loan(bank, borrower);
        if (loan.tranches().isEmpty()) {
            throw new BankException(Reasons.NO_TERMS + ":no-loan");
        }
        String bucket = w.chart().bucketFor(borrower);
        String provisionAccount = w.chart().llpFor(borrower);
        BigDecimal onbook = loan.onbook();
        BigDecimal allowance = w.draft.gl(bank, provisionAccount).negate().min(onbook);
        if (allowance.signum() < 0) {
            allowance = Money.zero();
        }
        BigDecimal receivable = loan.interestReceivable();
        // 冻结口径（EXP）：转让价 Q 含应计利息，账面成本 = onbook − allowance + 应收利息。
        BigDecimal carrying = Money.scale2(onbook.subtract(allowance).add(receivable));
        addGl(w, bank, bucket, onbook.negate());
        if (allowance.signum() > 0) {
            addGl(w, bank, provisionAccount, allowance);
        }
        if (receivable.signum() > 0) {
            addGl(w, bank, BankChart.INTEREST_RECEIVABLE, receivable.negate());
        }
        BigDecimal gain = Money.scale2(price.subtract(carrying));
        if (gain.signum() < 0) {
            addGl(w, bank, BankChart.ASSIGN_LOSS, gain.negate());
        } else if (gain.signum() > 0) {
            addGl(w, bank, BankChart.ASSIGN_GAIN, gain.negate());
        }
        w.draft.removeLoan(bank, borrower);
        BankState.AssignedClaim claim = new BankState.AssignedClaim(claimId, borrower, buyer,
                bank, loan, loan.contract(), price, Money.zero(), 0, 0,
                BankRules.CLAIM_ACTIVE);
        w.draft.putAssignedClaim(claim);
        return new Assigned(claim, receivable, gain);
    }

    private static void assignedRecover(Working w, BankConfig config, BankCommand cmd, long now) {
        String claimId = cmd.text("claim");
        String payer = cmd.text("payer");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireFacilityProfile(config);
        requireActor(cmd, payer);
        BankState.AssignedClaim claim = requireActiveClaim(w, claimId);
        if (amount.compareTo(claim.nominal()) > 0) {
            throw new BankException(Reasons.CLAIM_EXCEEDS_NOMINAL + ":"
                    + Money.text(claim.nominal()));
        }
        if (w.cash(payer).compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        move(w, payer, w.chart().cashAccountOf(payer), w.chart().cashAccountOf(claim.holder()),
                amount);
        w.draft.putAssignedClaim(settleClaim(claim, amount, Money.zero(), now));
        BankState.AssignedClaim after = w.draft.assignedClaim(claimId);
        BigDecimal released = after.costReleased().subtract(claim.costReleased());
        w.receipt.put("claim", claimId);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("costReleased", Money.text(released));
        w.receipt.put("holderGain", Money.text(Money.scale2(amount.subtract(released))));
        w.receipt.put("settled", Money.text(after.settled()));
        w.receipt.put("outstanding", Money.text(after.nominal()));
        w.receipt.put("interestReceivable", Money.text(after.interestReceivable()));
    }

    private static void assignedWaive(Working w, BankConfig config, BankCommand cmd, long now) {
        String claimId = cmd.text("claim");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireFacilityProfile(config);
        BankState.AssignedClaim claim = requireActiveClaim(w, claimId);
        if (!claim.holder().equals(cmd.actorOwnerId())) {
            throw new BankException(Reasons.CLAIM_HOLDER_MISMATCH + ":" + claim.holder());
        }
        if (amount.compareTo(claim.nominal()) > 0) {
            throw new BankException(Reasons.CLAIM_EXCEEDS_NOMINAL + ":"
                    + Money.text(claim.nominal()));
        }
        w.draft.putAssignedClaim(settleClaim(claim, Money.zero(), amount, now));
        BankState.AssignedClaim after = w.draft.assignedClaim(claimId);
        BigDecimal released = after.costReleased().subtract(claim.costReleased());
        w.receipt.put("claim", claimId);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("costReleased", Money.text(released));
        w.receipt.put("holderLoss", Money.text(released));
        w.receipt.put("settled", Money.text(after.settled()));
        w.receipt.put("outstanding", Money.text(after.nominal()));
        w.receipt.put("interestReceivable", Money.text(after.interestReceivable()));
    }

    private static BankState.AssignedClaim requireActiveClaim(Working w, String claimId) {
        BankState.AssignedClaim claim = w.draft.assignedClaim(claimId);
        if (claim == null) {
            throw new BankException(Reasons.CLAIM_UNKNOWN + ":" + claimId);
        }
        if (BankRules.CLAIM_SETTLED.equals(claim.status())) {
            throw new BankException(Reasons.CLAIM_ALREADY_SETTLED + ":" + claimId);
        }
        return claim;
    }

    /** 取任意状态的在册债权（本金已结清但仍有未收利息时，付息路径仍可用）。 */
    private static BankState.AssignedClaim requireClaim(Working w, String claimId) {
        BankState.AssignedClaim claim = w.draft.assignedClaim(claimId);
        if (claim == null) {
            throw new BankException(Reasons.CLAIM_UNKNOWN + ":" + claimId);
        }
        return claim;
    }

    /**
     * 结算受让债权本金：先把利息累计到真实 now（不丢还款前一段），再按 FIFO 减未清本金
     * （先表内、后备查），成本按**原始名义**比例释放。利息不因本金结算被抹掉或资本化。
     */
    private static BankState.AssignedClaim settleClaim(BankState.AssignedClaim claim,
                                                       BigDecimal recover, BigDecimal waive,
                                                       long now) {
        BankState.AssignedClaim accrued = accrueClaim(claim, now, now);
        long recovered = accrued.recoveredCents() + InterestAccrual.cents(recover);
        long waived = accrued.waivedCents() + InterestAccrual.cents(waive);
        long settleCents = InterestAccrual.cents(recover) + InterestAccrual.cents(waive);
        List<BankState.Tranche> next = reducePrincipal(accrued.loan().tranches(), settleCents);
        BankState.Loan loan = new BankState.Loan(next);
        BigDecimal settled = InterestAccrual.money(recovered + waived);
        BigDecimal released = BankState.AssignedClaim.releasedFor(accrued.bookCost(),
                accrued.originalNominal(), settled);
        boolean complete = loan.contract().signum() == 0;
        return new BankState.AssignedClaim(accrued.claimId(), accrued.borrower(),
                accrued.holder(), accrued.sellerBank(), loan, accrued.originalNominal(),
                accrued.bookCost(), released, recovered, waived,
                complete ? BankRules.CLAIM_SETTLED : BankRules.CLAIM_ACTIVE);
    }

    /** FIFO 减少未清本金：先表内、后备查；每期的 contract 与所选桶同步减，条款与已计利息不动。 */
    private static List<BankState.Tranche> reducePrincipal(List<BankState.Tranche> tranches,
                                                           long settleCents) {
        long remaining = settleCents;
        List<BankState.Tranche> next = new ArrayList<>();
        for (BankState.Tranche tranche : tranches) {
            BankState.Tranche current = tranche;
            if (remaining > 0 && current.onbook().signum() > 0) {
                long take = Math.min(remaining, InterestAccrual.cents(current.onbook()));
                BigDecimal amount = InterestAccrual.money(take);
                current = new BankState.Tranche(current.trancheId(), current.source(),
                        current.contract().subtract(amount), current.onbook().subtract(amount),
                        current.writtenOff(), current.terms(), current.nonAccrualReason());
                remaining -= take;
            }
            if (remaining > 0 && current.writtenOff().signum() > 0) {
                long take = Math.min(remaining, InterestAccrual.cents(current.writtenOff()));
                BigDecimal amount = InterestAccrual.money(take);
                current = new BankState.Tranche(current.trancheId(), current.source(),
                        current.contract().subtract(amount), current.onbook(),
                        current.writtenOff().subtract(amount), current.terms(),
                        current.nonAccrualReason());
                remaining -= take;
            }
            next.add(current);
        }
        if (remaining > 0) {
            throw new BankException(Reasons.CLAIM_EXCEEDS_NOMINAL);
        }
        return next;
    }

    /** 受让债权按原期次条款继续计息（持有人本人触发，外部持有人不记银行总账）。 */
    private static void assignedAccrue(Working w, BankConfig config, BankCommand cmd, long now) {
        String claimId = cmd.text("claim");
        long toMs = cmd.integer("toMs");
        requireFacilityProfile(config);
        BankState.AssignedClaim claim = requireActiveClaim(w, claimId);
        if (!claim.holder().equals(cmd.actorOwnerId())) {
            throw new BankException(Reasons.CLAIM_HOLDER_MISMATCH + ":" + claim.holder());
        }
        BankState.AssignedClaim updated = accrueClaim(claim, toMs, now);
        w.draft.putAssignedClaim(updated);
        w.receipt.put("claim", claimId);
        w.receipt.put("toMs", Long.toString(toMs));
        w.receipt.put("accrued", Money.text(updated.loan().interestReceivable()));
        w.receipt.put("interestReceivable", Money.text(updated.loan().interestReceivable()));
    }

    /**
     * 把受让债权各期次累计到 {@code toMs}（旧余额、旧费率、单次舍入，不资本化）。
     *
     * @param claim 债权
     * @param toMs 目标时点
     * @param now 当前时点（不得早于目标）
     * @return 新债权快照（其余字段不变）
     */
    private static BankState.AssignedClaim accrueClaim(BankState.AssignedClaim claim, long toMs,
                                                       long now) {
        if (toMs > now) {
            throw new BankException(Reasons.FUTURE_ACCRUAL + ":" + toMs + ">" + now);
        }
        List<BankState.Tranche> next = new ArrayList<>();
        for (BankState.Tranche tranche : claim.loan().tranches()) {
            if (tranche.terms() == null) {
                next.add(tranche);
                continue;
            }
            BankState.LoanTerms terms = tranche.terms();
            if (toMs < terms.lastAccruedMs()) {
                throw new BankException(Reasons.TIME_REVERSED + ":" + terms.lastAccruedMs());
            }
            BigInteger numerator = InterestAccrual.add(terms.numerator(),
                    InterestAccrual.cents(tranche.onbook()), terms.rateU(),
                    toMs - terms.lastAccruedMs());
            long target = InterestAccrual.targetCents(numerator);
            if (target < terms.postedCents()) {
                throw new BankException(Reasons.TIME_REVERSED);
            }
            next.add(new BankState.Tranche(tranche.trancheId(), tranche.source(),
                    tranche.contract(), tranche.onbook(), tranche.writtenOff(),
                    terms.withAccrual(numerator, target, toMs), null));
        }
        return withLoan(claim, new BankState.Loan(next));
    }

    /** 受让债权收息：真实付款方付给当前持有人，冲减期次应收；本金结清后仍可付未清利息。 */
    private static void assignedPayInterest(Working w, BankConfig config, BankCommand cmd) {
        String claimId = cmd.text("claim");
        String payer = cmd.text("payer");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireFacilityProfile(config);
        requireActor(cmd, payer);
        BankState.AssignedClaim claim = requireClaim(w, claimId);
        BigDecimal receivable = claim.loan().interestReceivable();
        if (amount.compareTo(receivable) > 0) {
            throw new BankException(Reasons.INTEREST_EXCEEDS_ACCRUED + ":"
                    + Money.text(receivable));
        }
        if (w.cash(payer).compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        move(w, payer, w.chart().cashAccountOf(payer), w.chart().cashAccountOf(claim.holder()),
                amount);
        List<BankState.Tranche> next = new ArrayList<>();
        BigDecimal remaining = amount;
        for (BankState.Tranche tranche : claim.loan().tranches()) {
            BankState.Tranche current = tranche;
            if (remaining.signum() > 0 && tranche.terms() != null
                    && tranche.terms().interestReceivable().signum() > 0) {
                BigDecimal take = remaining.min(tranche.terms().interestReceivable());
                BankState.LoanTerms terms = tranche.terms();
                current = new BankState.Tranche(tranche.trancheId(), tranche.source(),
                        tranche.contract(), tranche.onbook(), tranche.writtenOff(),
                        terms.withPayment(terms.paidCents() + InterestAccrual.cents(take)),
                        null);
                remaining = remaining.subtract(take);
            }
            next.add(current);
        }
        BankState.AssignedClaim updated = withLoan(claim, new BankState.Loan(next));
        w.draft.putAssignedClaim(updated);
        w.receipt.put("claim", claimId);
        w.receipt.put("payer", payer);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("interestReceivable",
                Money.text(updated.loan().interestReceivable()));
    }

    /** 替换债权快照（原名义/成本/已结算不变）。 */
    private static BankState.AssignedClaim withLoan(BankState.AssignedClaim claim,
                                                    BankState.Loan loan) {
        return new BankState.AssignedClaim(claim.claimId(), claim.borrower(), claim.holder(),
                claim.sellerBank(), loan, claim.originalNominal(), claim.bookCost(),
                claim.costReleased(), claim.recoveredCents(), claim.waivedCents(),
                claim.status());
    }

    // ------------------------------------------------------------ 真实对手许可

    /** 买方本人登记冻结受让许可：一次用途、绑定卖方/对象/价格/有效期。 */
    private static void purchaseAccept(Working w, BankConfig config, BankCommand cmd, long now) {
        String buyer = cmd.actorOwnerId();
        String seller = cmd.text("seller");
        String subjectType = cmd.text("subjectType");
        String subject = cmd.text("subject");
        BigDecimal price = Money.business(cmd.decimal("price"));
        long expiryMs = cmd.integer("expiryMs");
        String id = cmd.text("purchase");
        requireFacilityProfile(config);
        if (buyer.equals(seller)) {
            throw new BankException(Reasons.PURCHASE_SELF + ":" + buyer);
        }
        if ((!w.chart().isBank(buyer) && !w.chart().hasParty(buyer))
                || !w.accounts.containsKey(w.chart().cashAccountOf(buyer))) {
            throw new BankException(Reasons.ASSIGN_BUYER_UNKNOWN + ":" + buyer);
        }
        if (w.draft.purchase(id) != null) {
            throw new BankException(Reasons.PURCHASE_UNKNOWN + ":exists:" + id);
        }
        if (expiryMs <= now) {
            throw new BankException(Reasons.PURCHASE_EXPIRED + ":" + expiryMs);
        }
        if (BankRules.SUBJECT_LOAN.equals(subjectType)) {
            String bank = w.chart().bankOfLoanKey(subject);
            String borrower = w.chart().borrowerOfLoanKey(subject);
            if (bank == null || borrower == null) {
                throw new BankException(Reasons.PURCHASE_SUBJECT_MISMATCH + ":loan");
            }
            requireBank(w, bank);
            if (w.draft.loan(bank, borrower).tranches().isEmpty()) {
                throw new BankException(Reasons.PURCHASE_SUBJECT_MISMATCH + ":no-loan");
            }
        } else if (BankRules.SUBJECT_COLLATERAL.equals(subjectType)) {
            if (w.draft.collateral(subject) == null) {
                throw new BankException(Reasons.COLLATERAL_UNKNOWN + ":" + subject);
            }
        } else {
            throw new BankException(Reasons.PURCHASE_SUBJECT_MISMATCH + ":" + subjectType);
        }
        w.draft.putPurchase(new BankState.PurchaseConsent(id, buyer, seller, subjectType,
                subject, price, expiryMs, BankRules.PURCHASE_OPEN));
        w.receipt.put("purchase", id);
        w.receipt.put("buyer", buyer);
        w.receipt.put("seller", seller);
        w.receipt.put("subject", subject);
        w.receipt.put("price", Money.text(price));
        w.receipt.put("expiryMs", Long.toString(expiryMs));
    }

    /**
     * 取一份可执行的买方许可：必须本人登记、未消耗、未过期、卖方/对象/价格一致。
     */
    private static BankState.PurchaseConsent requirePurchase(Working w, String purchaseId,
                                                              String seller, String subjectType,
                                                              String subject, long now) {
        BankState.PurchaseConsent purchase = w.draft.purchase(purchaseId);
        if (purchase == null) {
            throw new BankException(Reasons.PURCHASE_UNKNOWN + ":" + purchaseId);
        }
        if (!purchase.open()) {
            throw new BankException(Reasons.PURCHASE_CONSUMED + ":" + purchaseId);
        }
        if (now >= purchase.expiryMs()) {
            throw new BankException(Reasons.PURCHASE_EXPIRED + ":" + purchase.expiryMs());
        }
        if (!purchase.seller().equals(seller)) {
            throw new BankException(Reasons.PURCHASE_MISMATCH + ":seller:"
                    + purchase.seller());
        }
        if (!purchase.subjectType().equals(subjectType)) {
            throw new BankException(Reasons.PURCHASE_SUBJECT_MISMATCH + ":"
                    + purchase.subjectType());
        }
        if (!purchase.subject().equals(subject)) {
            throw new BankException(Reasons.PURCHASE_MISMATCH + ":subject:"
                    + purchase.subject());
        }
        if ((!w.chart().isBank(purchase.buyer()) && !w.chart().hasParty(purchase.buyer()))
                || !w.accounts.containsKey(w.chart().cashAccountOf(purchase.buyer()))) {
            throw new BankException(Reasons.ASSIGN_BUYER_UNKNOWN + ":" + purchase.buyer());
        }
        return purchase;
    }

    /** 债权人本人登记解除许可：金额/账户/有效期绑定，权利人必须是账上真实归属。 */
    private static void dischargeConsent(Working w, BankConfig config, BankCommand cmd,
                                         long now) {
        String creditor = cmd.actorOwnerId();
        String bank = cmd.text("bank");
        String account = cmd.text("account");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        long expiryMs = cmd.integer("expiryMs");
        String id = cmd.text("consent");
        requireFacilityProfile(config);
        requireBank(w, bank);
        String real = creditorOf(w, bank, account);
        if (real == null) {
            throw new BankException(Reasons.CONSENT_MISMATCH + ":no-claim:" + account);
        }
        if (!real.equals(creditor)) {
            throw new BankException(Reasons.CONSENT_CREDITOR_INVALID + ":expected:" + real);
        }
        BigDecimal outstanding = Money.scale2(w.draft.gl(bank, account).negate());
        if (amount.compareTo(outstanding) > 0) {
            throw new BankException(Reasons.CONSENT_MISMATCH + ":amount:"
                    + Money.text(outstanding));
        }
        if (w.draft.consent(id) != null) {
            throw new BankException(Reasons.CONSENT_UNKNOWN + ":exists:" + id);
        }
        if (expiryMs <= now) {
            throw new BankException(Reasons.CONSENT_EXPIRED + ":" + expiryMs);
        }
        w.draft.putConsent(new BankState.DischargeConsent(id, bank, account, creditor, amount,
                expiryMs, BankRules.CONSENT_OPEN));
        w.receipt.put("consent", id);
        w.receipt.put("account", account);
        w.receipt.put("creditor", creditor);
        w.receipt.put("amount", Money.text(amount));
        w.receipt.put("expiryMs", Long.toString(expiryMs));
    }

    /**
     * 某负债科目的真实权利人；无法唯一确定时返回 null（拒绝而不是猜）。
     */
    private static String creditorOf(Working w, String bank, String account) {
        if (account == null) {
            return null;
        }
        if (w.chart().isDepositAccount(account)) {
            String owner = w.chart().ownerOfDeposit(account);
            if (owner == null) {
                return null;
            }
            return w.draft.gl(bank, account).negate().signum() > 0 ? owner : null;
        }
        if ("SUBT2".equals(account)) {
            return w.draft.gl(bank, account).negate().signum() > 0
                    ? BankChart.SUBORDINATED_OWNER : null;
        }
        if (BankChart.FACILITY_DEBT.equals(account)
                || BankChart.FACILITY_INTEREST_PAYABLE.equals(account)) {
            java.util.Set<String> providers = new java.util.LinkedHashSet<>();
            for (BankState.FacilityAccount facility : w.draft.facilityAccounts().values()) {
                if (facility.bank().equals(bank)) {
                    providers.add(facility.provider());
                }
            }
            return providers.size() == 1 ? providers.iterator().next() : null;
        }
        return null;
    }

    /** 贷款是否已被押品占用（真实担保权，不得普通转让）。 */
    private static boolean isPledgedLoan(Working w, String bank, String borrower) {
        String key = w.chart().loanKeyOf(bank, borrower);
        for (BankState.Collateral collateral : w.draft.collaterals().values()) {
            if (key.equals(collateral.assetKey())
                    && BankRules.COLLATERAL_PLEDGED.equals(collateral.status())) {
                return true;
            }
        }
        return false;
    }

    /** 贷款账面变动后同步押品账面与占用：耗尽即释放，不保留幽灵担保。 */
    private static void syncPledgeOfLoan(Working w, String bank, String borrower) {
        String key = w.chart().loanKeyOf(bank, borrower);
        BigDecimal onbook = w.draft.loan(bank, borrower).onbook();
        for (BankState.Collateral collateral
                : new ArrayList<>(w.draft.collaterals().values())) {
            if (!collateral.owner().equals(bank) || !key.equals(collateral.assetKey())) {
                continue;
            }
            if (BankRules.COLLATERAL_DISPOSED.equals(collateral.status())) {
                continue;
            }
            String pledgedTo = collateral.pledgedTo();
            if (onbook.signum() == 0) {
                if (pledgedTo != null) {
                    removeFromFacility(w, pledgedTo, collateral.collateralId());
                }
                w.draft.putCollateral(collateral.withBook(Money.zero(), null,
                        BankRules.COLLATERAL_EXHAUSTED));
            } else if (onbook.compareTo(collateral.bookValue()) < 0) {
                w.draft.putCollateral(collateral.withBook(onbook, pledgedTo,
                        collateral.status()));
            }
        }
    }

    /** 从设施押品集合中移除一项（担保随底层资产耗尽而消失）。 */
    private static void removeFromFacility(Working w, String facilityKey, String collateralId) {
        String bank = w.chart().bankOfFacilityKey(facilityKey);
        String id = w.chart().authorizationOfFacilityKey(facilityKey);
        if (bank == null || id == null) {
            return;
        }
        BankState.FacilityAccount account = w.draft.facilityAccount(bank, id);
        if (account == null) {
            return;
        }
        Set<String> next = new LinkedHashSet<>(account.collateral());
        next.remove(collateralId);
        w.draft.putFacilityAccount(account.withCollateral(next));
    }

    // ------------------------------------------------------------ B4 处置与清算

    private static void resolutionOpen(Working w, BankConfig config, BankCommand cmd, long now) {
        String bank = cmd.text("bank");
        String authority = cmd.text("authority");
        requireFacilityProfile(config);
        requireActor(cmd, authority);
        requireBank(w, bank);
        String declared = config.facilityTerms().resolutionAuthority();
        if (!declared.equals(authority)) {
            throw new BankException(Reasons.ACTOR_NOT_AUTHORIZED + ":authority:" + authority);
        }
        if (w.draft.resolution(bank) != null) {
            throw new BankException(Reasons.RESOLUTION_ALREADY_OPEN + ":" + bank);
        }
        w.draft.putResolution(new BankState.Resolution(bank, authority, now,
                BankRules.RESOLUTION_ACTIVE));
        w.receipt.put("bank", bank);
        w.receipt.put("authority", authority);
        w.receipt.put("status", BankRules.RESOLUTION_ACTIVE);
    }

    /** 独立处置权下的押品处分：须买方本人许可；出账用真实账面，款先偿该担保债权。 */
    private static void resolutionDispose(Working w, BankConfig config, BankCommand cmd,
                                          long now) {
        String bank = cmd.text("bank");
        String collateralId = cmd.text("collateral");
        String purchaseId = cmd.text("purchase");
        requireFacilityProfile(config);
        requireResolutionAuthority(w, config, cmd, bank);
        BankState.Collateral collateral = w.draft.collateral(collateralId);
        if (collateral == null) {
            throw new BankException(Reasons.COLLATERAL_UNKNOWN + ":" + collateralId);
        }
        if (!collateral.owner().equals(bank)) {
            throw new BankException(Reasons.COLLATERAL_OWNER_MISMATCH + ":" + collateralId);
        }
        if (BankRules.COLLATERAL_DISPOSED.equals(collateral.status())) {
            throw new BankException(Reasons.COLLATERAL_DISPOSED + ":" + collateralId);
        }
        if (!BankRules.COLLATERAL_PLEDGED.equals(collateral.status())) {
            throw new BankException(Reasons.COLLATERAL_NOT_PLEDGED + ":" + collateralId);
        }
        BankState.PurchaseConsent purchase = requirePurchase(w, purchaseId,
                cmd.actorOwnerId(), BankRules.SUBJECT_COLLATERAL, collateralId, now);
        String buyer = purchase.buyer();
        BigDecimal price = purchase.price();
        String declaredBuyer = cmd.optional("buyer");
        if (declaredBuyer != null && !declaredBuyer.equals(buyer)) {
            throw new BankException(Reasons.PURCHASE_MISMATCH + ":buyer");
        }
        String declaredPrice = cmd.optional("price");
        if (declaredPrice != null && !Money.business(cmd.decimal("price")).equals(price)) {
            throw new BankException(Reasons.PURCHASE_MISMATCH + ":price");
        }
        if (price.compareTo(w.cash(buyer)) > 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        String pledgedTo = collateral.pledgedTo();
        String id = w.chart().authorizationOfFacilityKey(pledgedTo);
        if (id == null) {
            throw new BankException(Reasons.COLLATERAL_UNKNOWN + ":" + collateralId);
        }
        BankState.FacilityAccount account = requireFacility(w, bank, id);

        // 处置必须有真实归属转移：PREMISES 记资产转移；LOAN 由 AssignedClaim 接管债权并留转移引用。
        BigDecimal carrying;
        Assigned assigned = null;
        move(w, buyer, w.chart().cashAccountOf(buyer), w.chart().reserveAccountOf(bank), price);
        if ("LOAN".equals(collateral.kind())) {
            int keySplit = collateral.assetKey().indexOf('|');
            String loanBorrower = collateral.assetKey().substring(keySplit + 1);
            String claimId = "disposal:" + collateralId;
            if (w.draft.assignedClaim(claimId) != null) {
                throw new BankException(Reasons.CLAIM_UNKNOWN + ":exists:" + claimId);
            }
            assigned = assignLoanCore(w, bank, loanBorrower, buyer, price, claimId, now);
            carrying = Money.scale2(price.subtract(assigned.pnl()));
            w.draft.putAssetTransfer(new BankState.AssetTransfer(collateral.assetKey(),
                    BankRules.SUBJECT_LOAN, bank, buyer, carrying, price, now, claimId));
        } else {
            carrying = collateral.bookValue();
            if (w.draft.gl(bank, "PREMISES").compareTo(carrying) < 0) {
                throw new BankException(Reasons.COLLATERAL_SHORTFALL + ":unbacked:"
                        + collateralId);
            }
            addGl(w, bank, "PREMISES", carrying.negate());
            BigDecimal disposalPnl = Money.scale2(price.subtract(carrying));
            if (disposalPnl.signum() < 0) {
                addGl(w, bank, "LOSS.DISPOSAL", disposalPnl.negate());
            } else if (disposalPnl.signum() > 0) {
                addGl(w, bank, "GAIN.DISPOSAL", disposalPnl.negate());
            }
            w.draft.putAssetTransfer(new BankState.AssetTransfer(collateral.assetKey(),
                    "PREMISES", bank, buyer, carrying, price, now,
                    "disposal:" + collateralId));
        }
        Set<String> pledged = new LinkedHashSet<>(account.collateral());
        pledged.remove(collateralId);
        w.draft.putCollateral(collateral.withPledge(null, BankRules.COLLATERAL_DISPOSED));

        BigDecimal applied = price.min(account.nominal());
        BigDecimal payOnbook = applied.min(account.onbook());
        BigDecimal payWritten = Money.scale2(applied.subtract(payOnbook));
        BigDecimal nextOnbook = Money.scale2(account.onbook().subtract(payOnbook));
        BigDecimal nextAllowance = account.allowance().min(nextOnbook);
        long allowanceRelease = InterestAccrual.cents(account.allowance())
                - InterestAccrual.cents(nextAllowance);
        if (applied.signum() > 0) {
            move(w, bank, w.chart().reserveAccountOf(bank), w.chart().cashAccountOf(account.provider()),
                    applied);
            addGl(w, bank, BankChart.FACILITY_DEBT, applied);
        }
        w.draft.putFacilityAccount(account.with(account.limit(), account.drawnCumulative(),
                nextOnbook,
                Money.scale2(account.writtenOff().subtract(payWritten)), nextAllowance,
                account.paidCents(), account.impairCents(),
                account.reversalCents() + allowanceRelease,
                account.recoveryCents() + InterestAccrual.cents(payWritten),
                account.waiverLossCents(), pledged, account.status()));
        maybeClose(w, bank, id);
        w.draft.putPurchase(purchase.consumed());
        BankState.FacilityAccount after = requireFacility(w, bank, id);
        w.receipt.put("collateral", collateralId);
        w.receipt.put("purchase", purchaseId);
        w.receipt.put("buyer", buyer);
        w.receipt.put("price", Money.text(price));
        w.receipt.put("carrying", Money.text(carrying));
        w.receipt.put("applied", Money.text(applied));
        w.receipt.put("uncovered", Money.text(after.nominal()));
        w.receipt.put("retained", Money.text(Money.scale2(price.subtract(applied))));
        if (assigned != null) {
            w.receipt.put("claim", assigned.claim().claimId());
            w.receipt.put("claimNominal", Money.text(assigned.claim().nominal()));
            w.receipt.put("interestTransferred", Money.text(assigned.interest()));
        }
    }

    private static void requireResolutionAuthority(Working w, BankConfig config, BankCommand cmd,
                                                   String bank) {
        String authority = cmd.actorOwnerId();
        if (!config.facilityTerms().resolutionAuthority().equals(authority)) {
            throw new BankException(Reasons.ACTOR_NOT_AUTHORIZED + ":authority:" + authority);
        }
        BankState.Resolution resolution = w.draft.resolution(bank);
        if (resolution == null || !BankRules.RESOLUTION_ACTIVE.equals(resolution.status())) {
            throw new BankException(Reasons.RESOLUTION_NOT_OPEN + ":" + bank);
        }
        if (!resolution.authority().equals(authority)) {
            throw new BankException(Reasons.ACTOR_NOT_AUTHORIZED + ":authority:" + authority);
        }
    }

    /** 按档案阶梯分配真实准备金；同级按 claimId 稳定升序 + 最大余数（不按输入顺序抢钱）。 */
    private static void resolutionDistribute(Working w, BankConfig config, BankCommand cmd,
                                             long now) {
        String bank = cmd.text("bank");
        requireFacilityProfile(config);
        requireResolutionAuthority(w, config, cmd, bank);
        if (w.draft.gl(bank, BankChart.INTEREST_PAYABLE).signum() != 0) {
            throw new BankException(Reasons.INTEREST_OUTSTANDING + ":deposit");
        }
        Map<String, BigDecimal> paid = new TreeMap<>();
        BigDecimal available = w.cash(bank);
        BigDecimal distributed = Money.zero();
        for (List<String> tier : config.facilityTerms().claimTiers()) {
            if (available.signum() <= 0) {
                break;
            }
            Map<String, BigDecimal> claims = tierClaims(w, bank, tier);
            Map<String, BigDecimal> allocation = allocateProRata(claims, available);
            for (Map.Entry<String, BigDecimal> entry : allocation.entrySet()) {
                BigDecimal amount = entry.getValue();
                if (amount.signum() <= 0) {
                    continue;
                }
                payClaim(w, bank, entry.getKey(), amount, now);
                paid.merge(entry.getKey(), amount, BigDecimal::add);
                available = Money.scale2(available.subtract(amount));
                distributed = distributed.add(amount);
            }
        }
        w.receipt.put("bank", bank);
        w.receipt.put("distributed", Money.text(distributed));
        w.receipt.put("reserve", Money.text(available));
        StringBuilder detail = new StringBuilder();
        paid.forEach((key, value) -> detail.append(key).append('=').append(Money.text(value))
                .append(';'));
        w.receipt.put("paid", detail.toString());
    }

    private static Map<String, BigDecimal> tierClaims(Working w, String bank, List<String> tier) {
        Map<String, BigDecimal> claims = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> entry : w.draft.glAccounts(bank).entrySet()) {
            String account = entry.getKey();
            if (!BankRules.inTier(account, tier)) {
                continue;
            }
            if (w.chart().isDepositAccount(account)) {
                BigDecimal balance = entry.getValue().negate();
                if (balance.signum() > 0) {
                    claims.put(account, Money.scale2(balance));
                }
            } else if (BankChart.FACILITY_DEBT.equals(account)
                    || BankChart.FACILITY_INTEREST_PAYABLE.equals(account)) {
                for (BankState.FacilityAccount facility : w.draft.facilityAccounts().values()) {
                    if (!facility.bank().equals(bank)) {
                        continue;
                    }
                    BigDecimal value = BankChart.FACILITY_DEBT.equals(account)
                            ? facility.nominal() : facility.interestPayable();
                    if (value.signum() > 0) {
                        claims.put(account + ":" + facility.authorizationId(),
                                Money.scale2(value));
                    }
                }
            } else {
                BigDecimal balance = entry.getValue().negate();
                if (balance.signum() > 0) {
                    claims.put(account, Money.scale2(balance));
                }
            }
        }
        return claims;
    }

    /** 逐笔支付：存款付给客户现金、设施付给提供者并减名义/利息、次级债付给其债权人。 */
    private static void payClaim(Working w, String bank, String claimId, BigDecimal amount,
                                 long now) {
        String account = claimId.startsWith(BankChart.FACILITY_DEBT + ":")
                || claimId.startsWith(BankChart.FACILITY_INTEREST_PAYABLE + ":")
                ? claimId.substring(0, claimId.indexOf(':')) : claimId;
        String id = claimId.indexOf(':') > 0 ? claimId.substring(claimId.indexOf(':') + 1) : null;
        if (w.chart().isDepositAccount(account)) {
            String owner = w.chart().ownerOfDeposit(account);
            if (owner == null) {
                throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":" + account);
            }
            if (!w.accounts.containsKey(w.chart().cashAccountOf(owner))) {
                throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":" + owner);
            }
            move(w, bank, w.chart().reserveAccountOf(bank), w.chart().cashAccountOf(owner), amount);
            addGl(w, bank, account, amount);
            shrinkDeposit(w, bank, owner);
            return;
        }
        if (BankChart.FACILITY_DEBT.equals(account)) {
            BankState.FacilityAccount facility = requireFacility(w, bank, id);
            move(w, bank, w.chart().reserveAccountOf(bank),
                    w.chart().cashAccountOf(facility.provider()), amount);
            BigDecimal payOnbook = amount.min(facility.onbook());
            BigDecimal payWritten = Money.scale2(amount.subtract(payOnbook));
            BigDecimal nextOnbook = Money.scale2(facility.onbook().subtract(payOnbook));
            BigDecimal nextAllowance = facility.allowance().min(nextOnbook);
            long allowanceRelease = InterestAccrual.cents(facility.allowance())
                    - InterestAccrual.cents(nextAllowance);
            addGl(w, bank, BankChart.FACILITY_DEBT, amount);
            w.draft.putFacilityAccount(facility.with(facility.limit(),
                    facility.drawnCumulative(), nextOnbook,
                    Money.scale2(facility.writtenOff().subtract(payWritten)),
                    nextAllowance, facility.paidCents(), facility.impairCents(),
                    facility.reversalCents() + allowanceRelease,
                    facility.recoveryCents() + InterestAccrual.cents(payWritten),
                    facility.waiverLossCents(), null, facility.status()));
            maybeClose(w, bank, id);
            return;
        }
        if (BankChart.FACILITY_INTEREST_PAYABLE.equals(account)) {
            BankState.FacilityAccount facility = requireFacility(w, bank, id);
            move(w, bank, w.chart().reserveAccountOf(bank),
                    w.chart().cashAccountOf(facility.provider()), amount);
            addGl(w, bank, BankChart.FACILITY_INTEREST_PAYABLE, amount);
            w.draft.putFacilityAccount(facility.with(facility.limit(),
                    facility.drawnCumulative(), facility.onbook(), facility.writtenOff(),
                    facility.allowance(),
                    facility.paidCents() + InterestAccrual.cents(amount), facility.impairCents(),
                    facility.reversalCents(), facility.recoveryCents(),
                    facility.waiverLossCents(), null, facility.status()));
            maybeClose(w, bank, id);
            return;
        }
        if ("SUBT2".equals(account)) {
            String owner = BankChart.SUBORDINATED_OWNER;
            if (!w.accounts.containsKey(w.chart().cashAccountOf(owner))) {
                throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":" + owner);
            }
            move(w, bank, w.chart().reserveAccountOf(bank), w.chart().cashAccountOf(owner), amount);
            addGl(w, bank, "SUBT2", amount);
            return;
        }
        throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":" + account);
    }

    /**
     * 同级按 claimId 升序 + 最大余数分配（100/100 分 101 → 51/50）。
     */
    private static Map<String, BigDecimal> allocateProRata(Map<String, BigDecimal> claims,
                                                           BigDecimal available) {
        Map<String, BigDecimal> result = new TreeMap<>();
        long total = 0;
        for (BigDecimal value : claims.values()) {
            total = Math.addExact(total, InterestAccrual.cents(value));
        }
        if (total == 0) {
            return result;
        }
        long cash = InterestAccrual.cents(available);
        if (cash >= total) {
            for (Map.Entry<String, BigDecimal> entry : claims.entrySet()) {
                result.put(entry.getKey(), Money.scale2(entry.getValue()));
            }
            return result;
        }
        long assigned = 0;
        Map<String, Long> floors = new TreeMap<>();
        Map<String, BigInteger> remainders = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> entry : claims.entrySet()) {
            long claim = InterestAccrual.cents(entry.getValue());
            BigInteger numerator = BigInteger.valueOf(cash).multiply(BigInteger.valueOf(claim));
            BigInteger denominator = BigInteger.valueOf(total);
            long floor = numerator.divide(denominator).longValueExact();
            floors.put(entry.getKey(), floor);
            remainders.put(entry.getKey(), numerator.subtract(
                    denominator.multiply(BigInteger.valueOf(floor))));
            assigned += floor;
        }
        long remainder = cash - assigned;
        List<Map.Entry<String, BigInteger>> order = new ArrayList<>(remainders.entrySet());
        order.sort((left, right) -> {
            int byRemainder = right.getValue().compareTo(left.getValue());
            return byRemainder != 0 ? byRemainder : left.getKey().compareTo(right.getKey());
        });
        for (Map.Entry<String, BigInteger> entry : order) {
            if (remainder <= 0) {
                break;
            }
            floors.merge(entry.getKey(), 1L, Long::sum);
            remainder--;
        }
        floors.forEach((key, value) -> result.put(key, InterestAccrual.money(value)));
        return result;
    }

    /** 债权人本人许可汇集的债务解除：无许可保留债，部分解除只清有许可项。 */
    private static void resolutionDischarge(Working w, BankConfig config, BankCommand cmd,
                                            long now) {
        String bank = cmd.text("bank");
        String list = cmd.text("consents");
        requireFacilityProfile(config);
        requireResolutionAuthority(w, config, cmd, bank);
        if (w.draft.gl(bank, BankChart.INTEREST_PAYABLE).signum() != 0
                || w.draft.gl(bank, BankChart.FACILITY_INTEREST_PAYABLE).signum() != 0) {
            throw new BankException(Reasons.FACILITY_INTEREST_OUTSTANDING);
        }
        Map<String, BigDecimal> remaining = remainingLiabilities(w, bank);
        if (remaining.isEmpty()) {
            throw new BankException(Reasons.RESOLUTION_CONSENT_MISMATCH + ":no-debt");
        }
        List<String> ids = new ArrayList<>();
        for (String part : list.split(";")) {
            if (!part.isBlank()) {
                ids.add(part.trim());
            }
        }
        if (ids.isEmpty()) {
            throw new BankException(Reasons.CONSENT_MISMATCH + ":empty");
        }
        BigDecimal total = Money.zero();
        Set<String> accounts = new LinkedHashSet<>();
        List<BankState.DischargeConsent> consumed = new ArrayList<>();
        for (String id : ids) {
            BankState.DischargeConsent consent = w.draft.consent(id);
            if (consent == null) {
                throw new BankException(Reasons.CONSENT_UNKNOWN + ":" + id);
            }
            if (!consent.open()) {
                throw new BankException(Reasons.CONSENT_USED + ":" + id);
            }
            if (!consent.bank().equals(bank)) {
                throw new BankException(Reasons.CONSENT_MISMATCH + ":bank:" + id);
            }
            if (now >= consent.expiryMs()) {
                throw new BankException(Reasons.CONSENT_EXPIRED + ":" + id);
            }
            BigDecimal balance = remaining.get(consent.account());
            if (balance == null) {
                throw new BankException(Reasons.CONSENT_MISMATCH + ":account:"
                        + consent.account());
            }
            if (consent.amount().compareTo(balance) != 0) {
                throw new BankException(Reasons.CONSENT_MISMATCH + ":amount:"
                        + consent.account());
            }
            if (!accounts.add(consent.account())) {
                throw new BankException(Reasons.CONSENT_MISMATCH + ":dup:" + consent.account());
            }
            String real = creditorOf(w, bank, consent.account());
            if (real == null || !real.equals(consent.creditor())) {
                throw new BankException(Reasons.CONSENT_CREDITOR_INVALID + ":"
                        + consent.creditor());
            }
            consumed.add(consent.used());
            total = total.add(balance);
        }
        for (BankState.DischargeConsent consent : consumed) {
            String account = consent.account();
            BigDecimal amount = remaining.get(account);
            addGl(w, bank, account, amount);
            if (w.chart().isDepositAccount(account)) {
                String owner = w.chart().ownerOfDeposit(account);
                w.draft.removeDepositAccount(bank, owner);
            }
            if (BankChart.FACILITY_DEBT.equals(account)) {
                dischargeFacilities(w, bank);
            }
            w.draft.putConsent(consent);
        }
        addGl(w, bank, BankChart.DISCHARGE_EQUITY, total.negate());
        BankState candidate = w.draft.freeze();
        w.receipt.put("bank", bank);
        w.receipt.put("consents", String.join(",", ids));
        w.receipt.put("discharged", Money.text(total));
        w.receipt.put("remaining", Money.text(remainingTotal(w, bank)));
        w.receipt.put("nonDistributable", Money.text(candidate.nonDistributable(bank)));
        w.receipt.put("bookEquity", Money.text(candidate.bookEquity(bank)));
    }

    /** 该行全部未清负债科目（账户→余额，只含真实正余额）。 */
    private static Map<String, BigDecimal> remainingLiabilities(Working w, String bank) {
        Map<String, BigDecimal> remaining = new TreeMap<>();
        for (String account : w.chart().liabilities()) {
            BigDecimal balance = w.draft.gl(bank, account).negate();
            if (balance.signum() > 0) {
                remaining.put(account, Money.scale2(balance));
            }
        }
        for (Map.Entry<String, BigDecimal> entry : w.draft.glAccounts(bank).entrySet()) {
            String account = entry.getKey();
            if (!w.chart().isDepositAccount(account) || remaining.containsKey(account)) {
                continue;
            }
            BigDecimal balance = entry.getValue().negate();
            if (balance.signum() > 0) {
                remaining.put(account, Money.scale2(balance));
            }
        }
        return remaining;
    }

    private static BigDecimal remainingTotal(Working w, String bank) {
        BigDecimal total = Money.zero();
        for (BigDecimal value : remainingLiabilities(w, bank).values()) {
            total = total.add(value);
        }
        return Money.scale2(total);
    }

    /** 解除设施债：释放剩余准备（转回）、按账面确认豁免损失、押品释放、状态 CLOSED_DISCHARGED。 */
    private static void dischargeFacilities(Working w, String bank) {
        for (BankState.FacilityAccount facility
                : new ArrayList<>(w.draft.facilityAccounts().values())) {
            if (!facility.bank().equals(bank) || facility.nominal().signum() <= 0) {
                continue;
            }
            for (String collateralId : new LinkedHashSet<>(facility.collateral())) {
                BankState.Collateral collateral = w.draft.collateral(collateralId);
                if (collateral != null) {
                    w.draft.putCollateral(collateral.withPledge(null,
                            BankRules.COLLATERAL_FREE));
                }
            }
            long allowanceCents = InterestAccrual.cents(facility.allowance());
            long onbookCents = InterestAccrual.cents(facility.onbook());
            w.draft.putFacilityAccount(facility.with(facility.limit(),
                    facility.drawnCumulative(), Money.zero(), Money.zero(), Money.zero(),
                    facility.paidCents(), facility.impairCents(),
                    facility.reversalCents() + allowanceCents, facility.recoveryCents(),
                    facility.waiverLossCents() + onbookCents, Set.of(),
                    BankRules.FACILITY_CLOSED_DISCHARGED));
        }
    }

    // ------------------------------------------------------------ 期次分配

    /** 命令体里的可选期次定位（空/缺省 → 旧聚合口径）。 */
    private static String optionalTranche(BankCommand cmd) {
        String value = cmd.body().get("tranche");
        return value == null || value.isBlank() ? null : value;
    }

    /** 取指定期次；不存在即 TRANCHE_UNKNOWN。 */
    private static BankState.Tranche requireTranche(BankState.Loan loan, String trancheId) {
        BankState.Tranche tranche = loan.tranche(trancheId);
        if (tranche == null) {
            throw new BankException(Reasons.TRANCHE_UNKNOWN + ":" + trancheId);
        }
        return tranche;
    }

    /** 还本：只减少指定期次的表内与合同（其余期次原样）。 */
    private static BankState.Loan allocateOnbookOn(BankState.Loan loan, String trancheId,
                                                   BigDecimal amount) {
        List<BankState.Tranche> next = new ArrayList<>();
        for (BankState.Tranche tranche : loan.tranches()) {
            if (!tranche.trancheId().equals(trancheId)) {
                next.add(tranche);
                continue;
            }
            next.add(copy(tranche, tranche.contract().subtract(amount),
                    tranche.onbook().subtract(amount), tranche.writtenOff()));
        }
        return new BankState.Loan(next);
    }

    /** 收息：只冲减指定期次的应收（其余期次原样）。 */
    private static BankState.Loan allocateInterestOn(BankState.Loan loan, String trancheId,
                                                     BigDecimal amount) {
        List<BankState.Tranche> next = new ArrayList<>();
        for (BankState.Tranche tranche : loan.tranches()) {
            if (!tranche.trancheId().equals(trancheId) || tranche.terms() == null) {
                next.add(tranche);
                continue;
            }
            BankState.LoanTerms terms = tranche.terms();
            next.add(new BankState.Tranche(tranche.trancheId(), tranche.source(),
                    tranche.contract(), tranche.onbook(), tranche.writtenOff(),
                    terms.withPayment(terms.paidCents() + InterestAccrual.cents(amount)), null));
        }
        return new BankState.Loan(next);
    }

    /** 还本：按列表顺序 FIFO 减少表内与合同。 */
    private static BankState.Loan allocateOnbook(BankState.Loan loan, BigDecimal amount) {
        BigDecimal remaining = amount;
        List<BankState.Tranche> next = new ArrayList<>();
        for (BankState.Tranche tranche : loan.tranches()) {
            BankState.Tranche current = tranche;
            if (remaining.signum() > 0 && tranche.onbook().signum() > 0) {
                BigDecimal take = remaining.min(tranche.onbook());
                current = copy(tranche, tranche.contract().subtract(take),
                        tranche.onbook().subtract(take), tranche.writtenOff());
                remaining = remaining.subtract(take);
            }
            next.add(current);
        }
        return new BankState.Loan(next);
    }

    /** 核销：按列表顺序 FIFO 把表内转备查（合同不变）。 */
    private static BankState.Loan allocateWriteOff(BankState.Loan loan, BigDecimal amount) {
        BigDecimal remaining = amount;
        List<BankState.Tranche> next = new ArrayList<>();
        for (BankState.Tranche tranche : loan.tranches()) {
            BankState.Tranche current = tranche;
            if (remaining.signum() > 0 && tranche.onbook().signum() > 0) {
                BigDecimal take = remaining.min(tranche.onbook());
                current = copy(tranche, tranche.contract(), tranche.onbook().subtract(take),
                        tranche.writtenOff().add(take));
                remaining = remaining.subtract(take);
            }
            next.add(current);
        }
        return new BankState.Loan(next);
    }

    /** 备查回收/豁免：按列表顺序 FIFO 减少备查与合同。 */
    private static BankState.Loan allocateWrittenOff(BankState.Loan loan, BigDecimal amount) {
        BigDecimal remaining = amount;
        List<BankState.Tranche> next = new ArrayList<>();
        for (BankState.Tranche tranche : loan.tranches()) {
            BankState.Tranche current = tranche;
            if (remaining.signum() > 0 && tranche.writtenOff().signum() > 0) {
                BigDecimal take = remaining.min(tranche.writtenOff());
                current = copy(tranche, tranche.contract().subtract(take), tranche.onbook(),
                        tranche.writtenOff().subtract(take));
                remaining = remaining.subtract(take);
            }
            next.add(current);
        }
        return new BankState.Loan(next);
    }

    /** 收息：按列表顺序 FIFO 冲减各期应收。 */
    private static BankState.Loan allocateInterest(BankState.Loan loan, BigDecimal amount) {
        BigDecimal remaining = amount;
        List<BankState.Tranche> next = new ArrayList<>();
        for (BankState.Tranche tranche : loan.tranches()) {
            BankState.Tranche current = tranche;
            if (remaining.signum() > 0 && tranche.terms() != null
                    && tranche.terms().interestReceivable().signum() > 0) {
                BigDecimal take = remaining.min(tranche.terms().interestReceivable());
                BankState.LoanTerms terms = tranche.terms();
                current = new BankState.Tranche(tranche.trancheId(), tranche.source(),
                        tranche.contract(), tranche.onbook(), tranche.writtenOff(),
                        terms.withPayment(terms.paidCents() + InterestAccrual.cents(take)), null);
                remaining = remaining.subtract(take);
            }
            next.add(current);
        }
        return new BankState.Loan(next);
    }

    private static BankState.Tranche copy(BankState.Tranche tranche, BigDecimal contract,
                                          BigDecimal onbook, BigDecimal writtenOff) {
        return new BankState.Tranche(tranche.trancheId(), tranche.source(), contract, onbook,
                writtenOff, tranche.terms(), tranche.nonAccrualReason());
    }

    /** 结清期次置 SETTLED（不改本金与应计）。 */
    private static BankState.Loan normalize(BankState.Loan loan) {
        List<BankState.Tranche> next = new ArrayList<>();
        for (BankState.Tranche tranche : loan.tranches()) {
            if (tranche.terms() == null) {
                next.add(tranche);
                continue;
            }
            boolean settled = tranche.contract().signum() == 0
                    && tranche.interestReceivable().signum() == 0;
            if (settled && !BankState.SETTLED.equals(tranche.terms().status())) {
                next.add(new BankState.Tranche(tranche.trancheId(), tranche.source(),
                        tranche.contract(), tranche.onbook(), tranche.writtenOff(),
                        tranche.terms().withStatus(BankState.SETTLED, false), null));
            } else {
                next.add(tranche);
            }
        }
        return new BankState.Loan(next);
    }

    // ------------------------------------------------------------ 利息

    /** 累计一笔贷款全部计息期次到指定时点（各自旧余额、旧费率；单次舍入，减已入账）。 */
    private static void accrueLoan(Working w, String bank, String borrower, long toMs, long now) {
        accrueLoan(w, bank, borrower, null, toMs, now);
    }

    /**
     * 累计计息到指定时点；给了 {@code trancheId} 时<b>只</b>累计该期次。
     *
     * <p>单合同口径：每期只用自己当期表内余额、费率与起止，互不共享；已结清的期次拒绝
     * （{@code LOAN_SETTLED}），因此旧合同的到期任务不会对新合同计息。</p>
     */
    private static void accrueLoan(Working w, String bank, String borrower, String trancheId,
                                   long toMs, long now) {
        BankState.Loan loan = w.draft.loan(bank, borrower);
        if (loan.tranches().isEmpty()) {
            throw new BankException(Reasons.NO_TERMS);
        }
        if (toMs > now) {
            throw new BankException(Reasons.FUTURE_ACCRUAL + ":" + toMs + ">" + now);
        }
        boolean accrual = false;
        List<BankState.Tranche> next = new ArrayList<>();
        long totalPosting = 0;
        for (BankState.Tranche tranche : loan.tranches()) {
            if (trancheId != null && !tranche.trancheId().equals(trancheId)) {
                next.add(tranche);
                continue;
            }
            if (tranche.terms() == null) {
                next.add(tranche);
                continue;
            }
            accrual = true;
            BankState.LoanTerms terms = tranche.terms();
            if (trancheId != null && tranche.contract().signum() == 0
                    && terms.interestReceivable().signum() == 0) {
                // 该合同本息已清：旧到期任务不得再产生利息（旧聚合口径不变）。
                throw new BankException(Reasons.LOAN_SETTLED + ":" + tranche.trancheId());
            }
            if (toMs < terms.lastAccruedMs()) {
                throw new BankException(Reasons.TIME_REVERSED + ":" + terms.lastAccruedMs());
            }
            BigInteger numerator = InterestAccrual.add(terms.numerator(),
                    InterestAccrual.cents(tranche.onbook()), terms.rateU(),
                    toMs - terms.lastAccruedMs());
            long target = InterestAccrual.targetCents(numerator);
            long posting = target - terms.postedCents();
            if (posting < 0) {
                throw new BankException(Reasons.TIME_REVERSED);
            }
            totalPosting = Math.addExact(totalPosting, posting);
            next.add(new BankState.Tranche(tranche.trancheId(), tranche.source(),
                    tranche.contract(), tranche.onbook(), tranche.writtenOff(),
                    terms.withAccrual(numerator, target, toMs), null));
        }
        if (!accrual) {
            throw new BankException(trancheId == null ? Reasons.NO_TERMS
                    : Reasons.TRANCHE_UNKNOWN + ":" + trancheId);
        }
        if (totalPosting > 0) {
            BigDecimal amount = InterestAccrual.money(totalPosting);
            addGl(w, bank, BankChart.INTEREST_RECEIVABLE, amount);
            addGl(w, bank, BankChart.INTEREST_INCOME, amount.negate());
        }
        w.draft.putLoan(bank, borrower, new BankState.Loan(next));
    }

    /** 累计一份存款到指定时点（旧余额、旧费率；单次舍入，减已入账）。 */
    private static void accrueDeposit(Working w, String bank, String owner, long toMs, long now) {
        BankState.DepositAccount account = w.draft.depositAccount(bank, owner);
        if (account == null) {
            throw new BankException(Reasons.NO_TERMS);
        }
        if (account.nonAccrual()) {
            throw new BankException(Reasons.NON_ACCRUAL_ACCOUNT + ":"
                    + account.nonAccrualReason());
        }
        if (toMs > now) {
            throw new BankException(Reasons.FUTURE_ACCRUAL + ":" + toMs + ">" + now);
        }
        if (toMs < account.lastAccruedMs()) {
            throw new BankException(Reasons.TIME_REVERSED + ":" + account.lastAccruedMs());
        }
        long base = account.accrualBaseCents(balanceCents(w, bank, owner));
        BigInteger numerator = InterestAccrual.add(account.numerator(), base, account.rateU(),
                toMs - account.lastAccruedMs());
        long target = InterestAccrual.targetCents(numerator);
        long posting = target - account.postedCents();
        if (posting < 0) {
            throw new BankException(Reasons.TIME_REVERSED);
        }
        if (posting > 0) {
            BigDecimal amount = InterestAccrual.money(posting);
            addGl(w, bank, BankChart.INTEREST_EXPENSE, amount);
            addGl(w, bank, BankChart.INTEREST_PAYABLE, amount.negate());
        }
        w.draft.putDepositAccount(bank, owner, account.withAccrual(numerator, target, toMs));
    }

    /** 余额变更前把该贷款各期次累计到当前时点（无计息期次则无事可做）。 */
    private static void syncLoan(Working w, String bank, String borrower, long now) {
        BankState.Loan loan = w.draft.loan(bank, borrower);
        boolean accrual = false;
        for (BankState.Tranche tranche : loan.tranches()) {
            if (tranche.terms() != null) {
                accrual = true;
                if (now < tranche.terms().lastAccruedMs()) {
                    throw new BankException(Reasons.TIME_REVERSED + ":"
                            + tranche.terms().lastAccruedMs());
                }
            }
        }
        if (accrual) {
            accrueLoan(w, bank, borrower, now, now);
        }
    }

    /** 余额变更前把该存款累计到当前时点（非应计/无账户则无事可做）。 */
    private static void syncDeposit(Working w, String bank, String owner, long now) {
        BankState.DepositAccount account = w.draft.depositAccount(bank, owner);
        if (account == null || account.nonAccrual()) {
            return;
        }
        if (now < account.lastAccruedMs()) {
            throw new BankException(Reasons.TIME_REVERSED + ":" + account.lastAccruedMs());
        }
        accrueDeposit(w, bank, owner, now, now);
    }

    /** 新放款期次条款；旧档案明确无条款。 */
    private static BankState.LoanTerms loanTermsForOrigination(BankConfig config, BankCommand cmd,
                                                               long now) {
        String rateText = cmd.optional("rate");
        String tenorText = cmd.optional("tenorMs");
        if (!config.interestEnabled()) {
            if (rateText != null || tenorText != null) {
                throw new BankException(Reasons.NO_TERMS + ":profile-disabled");
            }
            return null;
        }
        if (rateText == null || tenorText == null) {
            throw new BankException(Reasons.NO_TERMS + ":required");
        }
        long rateU = InterestAccrual.rateU(cmd.decimal("rate"));
        long tenorMs = cmd.integer("tenorMs");
        if (tenorMs <= 0) {
            throw new BankException(Reasons.TENOR_INVALID);
        }
        return new BankState.LoanTerms(rateU, now, saturate(now, tenorMs), BigInteger.ZERO,
                0, 0, now, BankState.CURRENT, false);
    }

    // ------------------------------------------------------------ 其余既有命令

    private static void impair(Working w, BankCommand cmd) {
        String bank = cmd.text("bank");
        String bucket = cmd.text("bucket");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, bank);
        requireBank(w, bank);
        String provisionAccount = w.chart().llpForToken(bucket);
        addGl(w, bank, "IMPAIR", amount);
        addGl(w, bank, provisionAccount, amount.negate());
        w.receipt.put("bucket", bucket);
        w.receipt.put("amount", Money.text(amount));
    }

    private static void reliefDraw(Working w, BankConfig config, BankCommand cmd) {
        if (config.facilityEnabled()) {
            throw new BankException(Reasons.FACILITY_PROFILE_INVALID + ":use-FACILITY_DRAW");
        }
        String bank = cmd.text("bank");
        String provider = cmd.text("provider");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        BigDecimal collateral = Money.valuation(cmd.decimal("collateralValue"));
        String collateralId = cmd.text("collateralId");
        requireActor(cmd, bank);
        requireBank(w, bank);
        if (!provider.equals(w.chart().providerKey())) {
            throw new BankException(Reasons.FACILITY_UNKNOWN_PROVIDER);
        }
        BankState.Facility facility = w.draft.facility(bank);
        if (facility != null && ("UNRESOLVED_DEFAULT".equals(facility.status())
                || facility.status().startsWith("CLOSED"))) {
            throw new BankException("UNRESOLVED_DEFAULT".equals(facility.status())
                    ? Reasons.FACILITY_IN_DEFAULT : Reasons.FACILITY_CLOSED);
        }
        BigDecimal limit = facility == null ? Money.scale2(new BigDecimal("800"))
                : facility.limit();
        BigDecimal outstanding = facility == null ? Money.zero() : facility.outstanding();
        if (outstanding.add(amount).compareTo(limit) > 0) {
            throw new BankException(Reasons.FACILITY_LIMIT);
        }
        if (w.cash(provider).compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        if (facility != null && facility.pledged().contains(collateralId)) {
            throw new BankException(Reasons.FACILITY_ALREADY_PLEDGED);
        }
        BigDecimal usable = Money.scale2(collateral.multiply(new BigDecimal("0.70")));
        if (amount.compareTo(usable) > 0) {
            throw new BankException(Reasons.FACILITY_COLLATERAL_SHORT);
        }
        move(w, provider, w.chart().cashAccountOf(provider), w.chart().reserveAccountOf(bank), amount);
        addGl(w, bank, "BORROW.FAC", amount.negate());
        Set<String> pledged = new LinkedHashSet<>(facility == null ? Set.of() : facility.pledged());
        pledged.add(collateralId);
        w.draft.putFacility(bank, new BankState.Facility(provider, limit,
                outstanding.add(amount), pledged,
                facility == null ? "OPEN" : facility.status()));
        w.receipt.put("amount", Money.text(amount));
    }

    private static void facilityPay(Working w, BankConfig config, BankCommand cmd, long now) {
        if (config.facilityEnabled()) {
            String bank = cmd.text("bank");
            String provider = cmd.text("provider");
            String id = authorizationId(cmd);
            BigDecimal amount = Money.business(cmd.decimal("amount"));
            requireFacilityProfile(config);
            requireActor(cmd, bank);
            requireBank(w, bank);
            BankState.FacilityAccount account = requireFacility(w, bank, id);
            if (!provider.equals(account.provider())) {
                throw new BankException(Reasons.FACILITY_UNKNOWN_PROVIDER);
            }
            if (account.closed()) {
                throw new BankException(Reasons.FACILITY_CLOSED);
            }
            touchFacility(w, bank, id, now);
            account = requireFacility(w, bank, id);
            syncFacility(w, bank, id, now);
            account = requireFacility(w, bank, id);
            if (amount.compareTo(account.nominal()) > 0) {
                throw new BankException(Reasons.OVERPAY_FACILITY);
            }
            if (w.cash(bank).compareTo(amount) < 0) {
                throw new BankException(Reasons.INSUFFICIENT_FUNDS);
            }
            move(w, bank, w.chart().reserveAccountOf(bank), w.chart().cashAccountOf(provider), amount);
            addGl(w, bank, BankChart.FACILITY_DEBT, amount);
            BigDecimal payOnbook = amount.min(account.onbook());
            BigDecimal payWritten = Money.scale2(amount.subtract(payOnbook));
            BigDecimal release = Money.zero();
            BigDecimal allowance = account.allowance();
            BigDecimal onbook = Money.scale2(account.onbook().subtract(payOnbook));
            if (allowance.compareTo(onbook) > 0) {
                release = Money.scale2(allowance.subtract(onbook));
                allowance = onbook;
            }
            w.draft.putFacilityAccount(account.with(account.limit(),
                    account.drawnCumulative(), onbook,
                    Money.scale2(account.writtenOff().subtract(payWritten)), allowance,
                    account.paidCents(), account.impairCents(),
                    account.reversalCents() + InterestAccrual.cents(release),
                    account.recoveryCents() + InterestAccrual.cents(payWritten),
                    account.waiverLossCents(), null, account.status()));
            maybeClose(w, bank, id);
            w.receipt.put("authorization", id);
            w.receipt.put("amount", Money.text(amount));
            w.receipt.put("nominal", Money.text(account.nominal().subtract(amount)));
            return;
        }
        String bank = cmd.text("bank");
        String provider = cmd.text("provider");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, bank);
        requireBank(w, bank);
        if (!provider.equals(w.chart().providerKey())) {
            throw new BankException(Reasons.FACILITY_UNKNOWN_PROVIDER);
        }
        BankState.Facility facility = w.draft.facility(bank);
        if (facility == null || facility.status().startsWith("CLOSED")) {
            throw new BankException(Reasons.FACILITY_CLOSED);
        }
        if (amount.compareTo(facility.outstanding()) > 0) {
            throw new BankException(Reasons.OVERPAY_FACILITY);
        }
        if (w.cash(bank).compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        move(w, bank, w.chart().reserveAccountOf(bank), w.chart().cashAccountOf(provider), amount);
        addGl(w, bank, "BORROW.FAC", amount);
        BigDecimal remaining = facility.outstanding().subtract(amount);
        w.draft.putFacility(bank, new BankState.Facility(provider, facility.limit(), remaining,
                facility.pledged(), remaining.signum() == 0
                ? "CLOSED_REPAID" : facility.status()));
        w.receipt.put("amount", Money.text(amount));
    }

    private static void facilityWaive(Working w, BankConfig config, BankCommand cmd, long now) {
        if (config.facilityEnabled()) {
            facilityWaiveB3(w, config, cmd, now);
            return;
        }
        String bank = cmd.text("bank");
        String provider = cmd.text("provider");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, provider);
        requireBank(w, bank);
        if (!provider.equals(w.chart().providerKey())) {
            throw new BankException(Reasons.FACILITY_UNKNOWN_PROVIDER);
        }
        BankState.Facility facility = w.draft.facility(bank);
        if (facility == null || facility.status().startsWith("CLOSED")) {
            throw new BankException(Reasons.FACILITY_CLOSED);
        }
        if (amount.compareTo(facility.outstanding()) > 0) {
            throw new BankException(Reasons.OVERWAIVE_FACILITY);
        }
        addGl(w, bank, "BORROW.FAC", amount);
        addGl(w, bank, "GAIN.WAIVE", amount.negate());
        BigDecimal remaining = facility.outstanding().subtract(amount);
        w.draft.putFacility(bank, new BankState.Facility(provider, facility.limit(), remaining,
                facility.pledged(), remaining.signum() == 0
                ? "CLOSED_WITH_LOSS" : facility.status()));
        w.receipt.put("amount", Money.text(amount));
    }

    private static void facilityProvision(Working w, BankConfig config, BankCommand cmd) {
        if (config.facilityEnabled()) {
            throw new BankException(Reasons.FACILITY_PROFILE_INVALID + ":use-FACILITY_IMPAIR");
        }
        String provider = cmd.text("provider");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireActor(cmd, provider);
        if (!provider.equals(w.chart().providerKey())) {
            throw new BankException(Reasons.FACILITY_UNKNOWN_PROVIDER);
        }
        BigDecimal gross = Money.zero();
        for (BankState.Facility facility : w.draft.facilities().values()) {
            if (provider.equals(facility.provider())) {
                gross = gross.add(facility.outstanding());
            }
        }
        BigDecimal used = w.draft.providerProvision(provider);
        if (used.add(amount).compareTo(gross) > 0) {
            throw new BankException(Reasons.PROVISION_EXCEEDS_GROSS);
        }
        w.draft.putProviderProvision(provider, used.add(amount));
        w.receipt.put("amount", Money.text(amount));
    }

    private static void facilityDefault(Working w, BankCommand cmd) {
        String bank = cmd.text("bank");
        requireBank(w, bank);
        if (!bank.equals(cmd.actorOwnerId()) && !cmd.actorOwnerId().equals(
                w.chart().providerKey())) {
            throw new BankException(Reasons.ACTOR_NOT_AUTHORIZED + ":default");
        }
        BankState.Facility facility = w.draft.facility(bank);
        if (facility == null || facility.outstanding().signum() == 0) {
            throw new BankException(Reasons.NOT_IN_DEFAULT + ":no-outstanding");
        }
        if (facility.status().startsWith("CLOSED")) {
            throw new BankException(Reasons.FACILITY_CLOSED);
        }
        if (w.cash(bank).compareTo(facility.outstanding()) >= 0) {
            throw new BankException(Reasons.NOT_IN_DEFAULT);
        }
        w.draft.putFacility(bank, new BankState.Facility(facility.provider(), facility.limit(),
                facility.outstanding(), facility.pledged(), "UNRESOLVED_DEFAULT"));
        w.receipt.put("bank", bank);
    }

    private static void migrate(Working w, BankConfig config, BankCommand cmd, long now) {
        String originId = cmd.text("origin");
        String owner = cmd.text("owner");
        String bank = cmd.text("bank");
        BigDecimal amount = Money.business(cmd.decimal("amount"));
        requireBank(w, bank);
        requireCustodyLine(w.chart(), bank, owner);
        BankState.Origin origin = w.draft.origin(originId);
        if (origin == null) {
            throw new BankException(Reasons.ORIGIN_UNKNOWN);
        }
        requireActor(cmd, origin.owner());
        if (!origin.owner().equals(owner)) {
            throw new BankException(Reasons.ORIGIN_OWNER_MISMATCH + ":" + origin.owner());
        }
        if (!origin.bank().equals(bank)) {
            throw new BankException(Reasons.ORIGIN_BANK_MISMATCH + ":" + origin.bank());
        }
        if (origin.amount().compareTo(amount) != 0) {
            throw new BankException(Reasons.ORIGIN_AMOUNT_MISMATCH);
        }
        if (origin.consumed()) {
            throw new BankException(Reasons.ORIGIN_ALREADY_CONSUMED + ":" + originId);
        }
        if (w.cash(owner).compareTo(amount) < 0) {
            throw new BankException(Reasons.INSUFFICIENT_FUNDS);
        }
        syncDeposit(w, bank, owner, now);
        move(w, owner, w.chart().cashAccountOf(owner), w.chart().reserveAccountOf(bank), amount);
        addGl(w, bank, w.chart().depositAccountOf(owner), amount.negate());
        applyCreditPolicy(w, config, bank, owner, now, amount);
        w.draft.putOrigin(originId, new BankState.Origin(origin.owner(), origin.bank(),
                origin.amount(), origin.source(), true));
        w.receipt.put("origin", originId);
        w.receipt.put("amount", Money.text(amount));
    }

    private static void admit(Working w, BankConfig config, String bank) {
        BankState candidate = w.draft.freeze();
        List<String> problems = BankInvariants.check(candidate, w.accounts);
        if (!problems.isEmpty()) {
            throw new BankException("INVARIANT:" + problems.get(0));
        }
        List<String> blocking = BankMetrics.blockingActions(candidate, w.accounts, config, bank);
        if (!blocking.isEmpty()) {
            throw new BankException(Reasons.CREDIT_BLOCKED_BY_BREACH + ":" + blocking.get(0));
        }
    }

    private static void requireActor(BankCommand cmd, String expected) {
        if (!expected.equals(cmd.actorOwnerId())) {
            throw new BankException(Reasons.ACTOR_NOT_AUTHORIZED + ":expected:" + expected);
        }
    }

    private static void move(Working w, String actor, String from, String to, BigDecimal amount) {
        CashTransfers.Move moved = CashTransfers.move(w.accounts, w.chart().ownerIdOf(actor),
                from, to, amount);
        if (!moved.accepted()) {
            throw new BankException(moved.reason());
        }
        w.accounts = new LinkedHashMap<>(moved.accounts());
        syncReserve(w);
    }

    /**
     * 以已解析的真实 owner 走现金腿，支付接缝不再把 key 二次翻译成 ownerId。
     *
     * <p>现金子账自身还会核对转出账户的 ownerId，因此收款人无法用自报身份授权扣款。
     * 出账前先过冻结闸门：该账户上被活动买单占用的金额不可花（{@link #requireCashRoom}）。</p>
     */
    private static void moveOwned(Working w, String ownerId, String from, String to,
                                  BigDecimal amount) {
        requireCashRoom(w, from, amount);
        CashTransfers.Move moved = CashTransfers.move(w.accounts, ownerId, from, to, amount);
        if (!moved.accepted()) {
            throw new BankException(moved.reason());
        }
        w.accounts = new LinkedHashMap<>(moved.accounts());
        syncReserve(w);
    }

    /**
     * 现金桶出账闸门：可用额 = 账户现金 − 该桶冻结；不足则拒绝且不改任何状态。
     *
     * <p>没有冻结快照（旧入口/旧档）时完全不介入，行为与旧实现逐字一致。</p>
     */
    private static void requireCashRoom(Working w, String accountId, BigDecimal amount) {
        if (w.frozen == null || accountId == null) {
            return;
        }
        CashLedger.CashAccount account = w.accounts.get(accountId);
        if (account == null) {
            return;
        }
        requireRoom(w, new Fund.Cash(accountId), account.cash(), amount);
    }

    /**
     * 存款桶出账闸门：可用额 = 该行该持有人存款余额 − 该桶冻结。
     *
     * @param bank   银行 key
     * @param holder 持有人 key（与冻结快照里的桶键一致）
     * @param amount 出账金额
     */
    private static void requireDepositRoom(Working w, String bank, String holder,
                                           BigDecimal amount) {
        if (w.frozen == null) {
            return;
        }
        BigDecimal balance = w.draft.gl(bank, w.chart().depositAccountOf(holder)).negate();
        requireRoom(w, new Fund.Deposit(bank, holder), balance, amount);
    }

    /** 统一口径：冻结不得超过余额（快照不可信），且本次出账不得超过“余额 − 冻结”。 */
    private static void requireRoom(Working w, Fund bucket, BigDecimal balance,
                                    BigDecimal amount) {
        BigDecimal held = w.frozen.frozenOf(bucket);
        if (held.signum() == 0) {
            return;
        }
        if (held.compareTo(balance) > 0) {
            w.receipt.put("bucket", bucket.token());
            w.receipt.put("balance", Money.text(balance));
            w.receipt.put("frozen", Money.text(held));
            throw new BankException(Reasons.PAYMENT_FREEZE_EXCEEDS_BALANCE + ":" + bucket.token());
        }
        BigDecimal available = balance.subtract(held);
        if (amount.compareTo(available) > 0) {
            w.receipt.put("bucket", bucket.token());
            w.receipt.put("balance", Money.text(balance));
            w.receipt.put("frozen", Money.text(held));
            w.receipt.put("available", Money.text(available));
            throw new BankException(Reasons.PAYER_BALANCE_INSUFFICIENT + ":" + bucket.token());
        }
    }

    private static void syncReserve(Working w) {
        for (String bank : w.chart().banks()) {
            CashLedger.CashAccount account = w.accounts.get(w.chart().reserveAccountOf(bank));
            w.draft.setGl(bank, BankChart.RESERVE, account == null ? Money.zero() : account.cash());
        }
    }

    private static void addGl(Working w, String bank, String account, BigDecimal delta) {
        w.draft.addGl(bank, account, delta);
    }

    private static void requireBank(Working w, String bank) {
        if (!w.chart().isBank(bank) || w.draft.gl(bank, "PAIDIN").signum() == 0) {
            throw new BankException(Reasons.UNKNOWN_BANK + ":" + bank);
        }
    }

    private static void requireCash(Working w, String owner) {
        if (!w.accounts.containsKey(w.chart().cashAccountOf(owner))) {
            throw new BankException(Reasons.UNKNOWN_ACCOUNT + ":" + owner);
        }
    }

    /**
     * 一次候选计算的工作副本。
     *
     * <p>{@code frozen} 是世界侧派生的受信冻结快照：所有真正从某个资金桶扣钱的路径
     * （{@link #moveOwned} 的现金腿与各处存款总账借方）都先按“余额 − 冻结”校验可用额，
     * 因此取现、还本、付息、第三方还款与其它已有提款都不能绕开买单冻结。
     * 旧入口传 null 时不做该校验，旧档行为逐字不变。</p>
     */
    private static final class Working {
        private final BankState.Draft draft;
        private Map<String, CashLedger.CashAccount> accounts;
        private final CashFreeze frozen;
        private final Map<String, String> receipt = new TreeMap<>();

        private Working(BankState.Draft draft, Map<String, CashLedger.CashAccount> accounts,
                        CashFreeze frozen) {
            this.draft = draft;
            this.accounts = accounts;
            this.frozen = frozen;
        }

        private BankChart chart() {
            return draft.chart();
        }

        private BigDecimal cash(String owner) {
            BankChart chart = chart();
            if (!chart.isBank(owner) && !chart.hasParty(owner)) {
                return Money.zero();
            }
            String accountId = chart.isBank(owner)
                    ? chart.reserveAccountOf(owner) : chart.cashAccountOf(owner);
            CashLedger.CashAccount account = accounts.get(accountId);
            return account == null ? Money.zero() : account.cash();
        }
    }
}
