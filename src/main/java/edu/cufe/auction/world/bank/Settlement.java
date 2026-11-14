package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * 结算部门视图：由现金账户**派生**的镜像，不持久化第二份余额。
 *
 * <p>口径：公众现金与银行准备金都是对结算部门的结算货币权利；对每个持有者 x，
 * `DUE(x) = −cash(x)`。外部背书必须等于全部现金之和。</p>
 */
public final class Settlement {
    private Settlement() {
    }

    /**
     * 派生每个持有者的 DUE（负数表示结算部门欠该持有者）。
     *
     * @param accounts 现金账户表
     * @return 持有者 → DUE
     */
    public static Map<String, BigDecimal> mirror(Map<String, CashLedger.CashAccount> accounts) {
        Map<String, BigDecimal> owed = new TreeMap<>();
        for (CashLedger.CashAccount account : accounts.values()) {
            owed.merge(account.ownerId(), account.cash().negate(), BigDecimal::add);
        }
        owed.replaceAll((owner, value) -> Money.scale2(value));
        return owed;
    }

    /**
     * 核对镜像与背书：Σ现金 = backing。
     *
     * @param state    银行状态
     * @param accounts 现金账户表
     * @return 是否成立
     */
    public static boolean balanced(BankState state, Map<String, CashLedger.CashAccount> accounts) {
        BigDecimal total = Money.zero();
        for (CashLedger.CashAccount account : accounts.values()) {
            total = total.add(account.cash());
        }
        return Money.scale2(total).compareTo(state.backing()) == 0;
    }
}
