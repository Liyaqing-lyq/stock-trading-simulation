package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;

import java.util.Map;
import java.util.Objects;

/**
 * 支付接缝的唯一公共入口：一次付款的全部腿在同一个候选里完成。
 *
 * <p>支持的向量是 CASH→CASH、CASH→DEP、DEP→CASH、同银行 DEP→DEP 与跨银行 DEP→DEP；
 * 付款人与收款人可以是不同主体，同一持有人在一家银行的钱转到另一家也合法。任一腿失败时
 * 调用方拿回原 {@code bank} 与 {@code accounts} —— 经济字段（总账、存款条款、现金余额）
 * 逐字段不变，只在 {@code bank} 的命令记录里追加一条拒绝幂等回执（与成功回执同一结构，
 * {@code accepted=false}），因此不存在“先外部提现、再第二次提交转账”的中间态，也不存在
 * 无审计的失败。协议/配置错误不消费 commandId，其余拒绝按标识幂等重放。</p>
 *
 * <p>可用额只按出款桶算：该桶余额减该桶已有的合法冻结。现金与别家银行存款不能互相补足，
 * 同一 owner 的总资产也不是付款权。冻结快照是受信输入（{@link CashFreeze}），本类不实现
 * 订单或 session 权限。</p>
 *
 * <p>内部派生腿（现金腿、存款入账腿、跨行准备金腿）没有公开命令：外部不能自己拼一条腿，
 * 也不能伪造收款人的 actor 去授权扣款。交易结算必须等真实 {@code WorldExchange} 桥；
 * {@link #tradeSettlement} 对任何字符串凭据一律拒绝。</p>
 */
public final class PaymentPort {

    private PaymentPort() {
    }

    /**
     * 提交一次付款。
     *
     * @param state    当前银行状态（须已绑定 run 与档案指纹）
     * @param accounts 现金账户（唯一现金权威）
     * @param config   冻结档案
     * @param ctx      运行上下文
     * @param payment  付款请求
     * @param frozen   受信冻结快照；无冻结必须显式传 {@link CashFreeze#none()}
     * @return 结果；同标识同 payload 重放返回本次当前 state/accounts 与首次回执
     */
    public static BankModel.Outcome pay(BankState state, Map<String, CashLedger.CashAccount> accounts,
                                        BankConfig config, BankContext ctx, BankPayment payment,
                                        CashFreeze frozen) {
        Objects.requireNonNull(payment, "付款请求不能为空");
        Objects.requireNonNull(frozen, "受信冻结快照不能为空；无冻结请显式传 CashFreeze.none()");
        BankCommand command = new BankCommand(payment.paymentId(), BankCommand.Kind.PAYMENT,
                payment.body(), payment.payerKey());
        return BankModel.apply(state, accounts, config, ctx, command, frozen);
    }

    /**
     * 交易结算/成交扣款：纯支付接缝不提供这个能力。
     *
     * <p>真实实现必须由 {@code WorldExchange} 的成交记录给出成交价×成交量与 fill 标识，
     * 并在同一候选里解冻。任何自报的订单号、fill 字符串都不能当凭据花掉冻结资金，
     * 因此这里无条件拒绝且不改动任何状态、不消费标识。</p>
     *
     * @param state                当前银行状态
     * @param accounts             现金账户
     * @param claimedSettlementId  调用方自称的结算/成交凭据
     * @return 拒绝结果（state/accounts 原样）
     */
    public static BankModel.Outcome tradeSettlement(BankState state,
                                                    Map<String, CashLedger.CashAccount> accounts,
                                                    String claimedSettlementId) {
        return new BankModel.Outcome(false,
                Reasons.SETTLEMENT_UNSUPPORTED + ":requires-world-exchange-fill",
                null, state, accounts);
    }
}
