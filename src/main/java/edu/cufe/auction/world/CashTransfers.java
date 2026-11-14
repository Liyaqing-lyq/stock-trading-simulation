package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 只读适配器：把同包的 {@link CashLedger#computeTransfer} 暴露给 bank 包。
 *
 * <p>不新增任何现金逻辑、不修改 {@link CashLedger}；仅用于隔离编译的纯核，
 * 世界桥接入时应由 Runtime 直接调用原方法。</p>
 */
public final class CashTransfers {
    private CashTransfers() {
    }

    /**
     * 一次转移的结果（与 {@link CashLedger} 内部结果一一对应）。
     *
     * @param accepted 是否允许
     * @param reason   结果码
     * @param accounts 候选账户表（未接受时与原表相同）
     */
    public record Move(boolean accepted, String reason,
                       Map<String, CashLedger.CashAccount> accounts) {
    }

    /**
     * 计算一次双边现金转移的候选结果（纯函数）。
     *
     * @param accounts      当前账户表
     * @param actorOwnerId  可信调用方解析的财产主体
     * @param fromAccountId 付款账户
     * @param toAccountId   收款账户
     * @param amount        正数、两位小数
     * @return 候选结果
     */
    public static Move move(Map<String, CashLedger.CashAccount> accounts, String actorOwnerId,
                            String fromAccountId, String toAccountId, BigDecimal amount) {
        CashLedger.TransferOutcome outcome =
                CashLedger.computeTransfer(accounts, actorOwnerId, fromAccountId, toAccountId, amount);
        String reason = outcome.reason();
        if ("INSUFFICIENT_CASH".equals(reason)) {
            reason = "INSUFFICIENT_FUNDS";
        }
        return new Move(outcome.accepted(), reason, outcome.accounts());
    }
}
