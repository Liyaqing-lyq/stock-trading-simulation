package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.CustodyBook;
import edu.cufe.auction.world.bank.Fund;
import edu.cufe.auction.world.bank.FundState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 两个纯模型共用的测试夹具：真实现金账户表 + 可观测的资金腿替身。
 *
 * <p>替身只做“现金桶之间转账 + 记录 commandId”，不参与任何业务判断；业务侧的主体校验、
 * 可用额校验与幂等校验仍走被测模型自己的代码路径。这样测试不会因为夹具放水而掩盖半写。</p>
 */
final class PureCompanyTestSupport {

    private PureCompanyTestSupport() {
    }

    /**
     * 造一个开局现金账户。
     *
     * @param ownerId 财产主体
     * @param amount  余额
     * @return 账户
     */
    static CashLedger.CashAccount account(String ownerId, String amount) {
        return new CashLedger.CashAccount(ownerId, money(amount), "EXP");
    }

    /**
     * 两位小数金额。
     *
     * @param value 文本
     * @return 金额
     */
    static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 用现金账户表构造资金三态（银行/托管为合法空账）。
     *
     * @param accounts 账户表
     * @return 资金三态
     */
    static FundState funds(Map<String, CashLedger.CashAccount> accounts) {
        return new FundState(accounts, BankState.empty(), CustodyBook.empty());
    }

    /**
     * 绑一条现金腿。
     *
     * @param srcAccountId 出款账户
     * @param dstAccountId 收款账户
     * @return 资金腿
     */
    static Payer payer(String srcAccountId, String dstAccountId) {
        return new Payer(srcAccountId, dstAccountId);
    }

    /** 现金桶资金腿替身。 */
    static final class Payer implements FundPayer {

        private final String src;
        private final String dst;
        private final Set<String> records = new LinkedHashSet<>();
        private final Set<String> accepted = new LinkedHashSet<>();

        private Payer(String src, String dst) {
            this.src = src;
            this.dst = dst;
        }

        @Override
        public Fund source() {
            return new Fund.Cash(src);
        }

        @Override
        public Fund target() {
            return new Fund.Cash(dst);
        }

        @Override
        public Result pay(String commandId, BigDecimal amount, FundState funds) {
            if (records.contains(commandId)) {
                return new Result(false, "FUND_ID_ALREADY_RECORDED", funds);
            }
            if (available(funds).compareTo(amount) < 0) {
                return new Result(false, "INSUFFICIENT_CASH", funds);
            }
            Map<String, CashLedger.CashAccount> next = new LinkedHashMap<>(funds.accounts());
            CashLedger.CashAccount from = next.get(src);
            CashLedger.CashAccount to = next.get(dst);
            next.put(src, new CashLedger.CashAccount(from.ownerId(),
                    from.cash().subtract(amount), from.source()));
            next.put(dst, new CashLedger.CashAccount(to.ownerId(),
                    to.cash().add(amount), to.source()));
            records.add(commandId);
            accepted.add(commandId);
            return new Result(true, "TRANSFERRED", new FundState(next, funds.bank(), funds.book()));
        }

        @Override
        public boolean hasRecord(String commandId, FundState funds) {
            return records.contains(commandId);
        }

        @Override
        public boolean hasAcceptedRecord(String commandId, FundState funds) {
            return accepted.contains(commandId);
        }

        @Override
        public BigDecimal available(FundState funds) {
            CashLedger.CashAccount from = funds.accounts().get(src);
            return from == null ? money("0.00") : from.cash();
        }
    }
}
