package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.FundPayer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 用真实资金端口实现 {@link FundPayer}：按源/目标桶路由到 {@link PaymentPort} 或 {@link CustodyPort}，
 * 并且**每个候选都过一遍 {@link CustodyBook#validate}**。
 *
 * <p>本类不持有任何账簿：银行、现金、托管明细账都来自调用方每次显式传入的 {@link FundState}，
 * 因此不存在“payer 里记住旧 bank”的回退。构造期只读绑定档案、上下文、冻结快照、付款人 key
 * 与两个资金桶；这些都不能被单次调用改写。</p>
 *
 * <p>路由规则就是真实源/目标：两侧都不是托管受益权时走 {@link PaymentPort}（含同行/跨行存款、
 * 现金向量），任一侧是托管受益权时走 {@link CustodyPort}（它自己派生出池存款腿）。
 * 两条路都会在返回前用当前托管明细账校验银行候选，校验不过就整笔拒绝、不改三态。</p>
 */
public final class PaymentPortFundPayer implements FundPayer {

    private final BankConfig config;
    private final BankContext context;
    private final CashFreeze frozen;
    private final String payerKey;
    private final Fund source;
    private final Fund target;

    /**
     * 绑定一条资金腿（不绑定任何账簿状态）。
     *
     * @param config    冻结档案
     * @param context   运行上下文（run/fingerprint/position/时间）
     * @param frozen    受信冻结快照；无冻结必须显式传 {@link CashFreeze#none()}
     * @param payerKey  出款方档案 key（银行或客户）
     * @param source    出款资金桶
     * @param target    收款资金桶
     */
    public PaymentPortFundPayer(BankConfig config, BankContext context, CashFreeze frozen,
                                String payerKey, Fund source, Fund target) {
        this.config = Objects.requireNonNull(config, "档案");
        this.context = Objects.requireNonNull(context, "运行上下文");
        this.frozen = Objects.requireNonNull(frozen, "冻结快照");
        this.payerKey = Objects.requireNonNull(payerKey, "出款方 key");
        this.source = Objects.requireNonNull(source, "出款资金桶");
        this.target = Objects.requireNonNull(target, "收款资金桶");
    }

    /**
     * 同一家银行存款对存款的构造捷径：出款人把自己的存款付给收款人在该行的存款。
     *
     * @param config         冻结档案
     * @param context        运行上下文
     * @param frozen         受信冻结快照
     * @param bankKey        银行 key（收付双方同一家）
     * @param payerKey       出款方档案 key
     * @param payerHolder    出款方持有人 key
     * @param payeeHolder    收款方持有人 key
     * @return 资金腿
     */
    public static PaymentPortFundPayer depositToDeposit(BankConfig config, BankContext context,
                                                        CashFreeze frozen, String bankKey,
                                                        String payerKey, String payerHolder,
                                                        String payeeHolder) {
        return new PaymentPortFundPayer(config, context, frozen, payerKey,
                new Fund.Deposit(bankKey, payerHolder), new Fund.Deposit(bankKey, payeeHolder));
    }

    @Override
    public Fund source() {
        return source;
    }

    @Override
    public Fund target() {
        return target;
    }

    @Override
    public Result pay(String commandId, BigDecimal amount, FundState funds) {
        Objects.requireNonNull(funds, "资金三态");
        BankPayment payment = new BankPayment(commandId, payerKey, source, target, amount);
        boolean custody = source.kind() == Fund.Kind.CUSTODY
                || target.kind() == Fund.Kind.CUSTODY;
        FundState candidate;
        String reason;
        boolean accepted;
        if (custody) {
            CustodyPort.Result result = CustodyPort.pay(funds.bank(), funds.accounts(),
                    funds.book(), config, context, payment, frozen);
            candidate = new FundState(result.accounts(), result.bank(), result.book());
            accepted = result.accepted();
            reason = result.reason();
        } else {
            BankModel.Outcome outcome = PaymentPort.pay(funds.bank(), funds.accounts(), config,
                    context, payment, frozen);
            candidate = new FundState(outcome.accounts(), outcome.state(), funds.book());
            accepted = outcome.accepted();
            reason = outcome.reason();
        }
        List<String> problems = candidate.validate(config);
        if (!problems.isEmpty()) {
            // 候选自相矛盾（银行不变量或托管明细对不上）：连回执都不带出，整笔按入参拒绝
            return new Result(false, problems.get(0), funds);
        }
        return new Result(accepted, reason, candidate);
    }

    @Override
    public boolean hasRecord(String commandId, FundState funds) {
        Objects.requireNonNull(funds, "资金三态");
        return funds.bank().command(commandId) != null
                || funds.book().command(commandId) != null;
    }

    @Override
    public boolean hasAcceptedRecord(String commandId, FundState funds) {
        Objects.requireNonNull(funds, "资金三态");
        BankState.CommandRecord cashLeg = funds.bank().command(commandId);
        BankState.CommandRecord custodyLeg = funds.book().command(commandId);
        return (cashLeg != null && cashLeg.accepted())
                || (custodyLeg != null && custodyLeg.accepted());
    }

    @Override
    public BigDecimal available(FundState funds) {
        Objects.requireNonNull(funds, "资金三态");
        BigDecimal balance;
        if (source instanceof Fund.Cash cash) {
            balance = funds.cash(cash.accountId());
        } else if (source instanceof Fund.Deposit deposit) {
            balance = funds.deposit(deposit.bankKey(), deposit.holderKey());
        } else {
            Fund.Custody custody = (Fund.Custody) source;
            balance = funds.book().detailOf(custody.custodianKey(), custody.clientKey());
        }
        BigDecimal free = balance.subtract(frozen.frozenOf(source));
        return free.signum() <= 0 ? Money.zero() : Money.scale2(free);
    }
}
