package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次业务候选里的资金三态：现金账户、银行账簿、托管明细账的不可变快照值。
 *
 * <p>它只是**同一次候选的视图**：既不持久化，也不替代任何一本账。现金权威仍是
 * {@link CashLedger} 账户表，银行存款在 {@link BankState} 总账里，客户受益权在
 * {@link CustodyBook} 明细里；本类型只把三者绑在一起传递，避免任何一方把旧状态
 * 捕获在对象里。</p>
 *
 * <p>结算方法必须显式接收当前 {@code FundState} 并返回新的 {@code FundState}：调用方
 * 每次都要把上一次的结果传进去，因此「复用旧状态再付一笔」在类型上就是显式的传参，
 * 而不是藏在 payer 里的隐式回退。</p>
 *
 * @param accounts 现金账户（唯一现金权威）
 * @param bank     银行状态（须已绑定 run 与档案指纹）
 * @param book     托管明细账（无托管经营人时应为真实合法空账，不是伪造的 EMPTY）
 */
public record FundState(Map<String, CashLedger.CashAccount> accounts, BankState bank,
                        CustodyBook book) {

    /** 校验三态都非空并防御性复制账户表。 */
    public FundState {
        Objects.requireNonNull(accounts, "现金账户表");
        Objects.requireNonNull(bank, "银行状态");
        Objects.requireNonNull(book, "托管明细账");
        accounts = Map.copyOf(accounts);
    }

    /**
     * 某现金账户余额。
     *
     * @param accountId 账户标识
     * @return 余额；账户不存在时为零
     */
    public BigDecimal cash(String accountId) {
        CashLedger.CashAccount account = accounts.get(accountId);
        return account == null ? Money.zero() : account.cash();
    }

    /**
     * 某银行对某持有人的存款余额（负债方取正）。
     *
     * @param bankKey   银行 key
     * @param holderKey 持有人 key
     * @return 存款余额
     */
    public BigDecimal deposit(String bankKey, String holderKey) {
        return bank.depositOf(bankKey, holderKey);
    }

    /**
     * 从当前三态派生某个资金桶的真实财产主体；解析不出来时返回 {@code null}，由调用方拒绝。
     *
     * <p>现金桶只认当前 {@link CashLedger} 账户上的 ownerId；存款桶与托管桶认档案里
     * 持有人/客户的 ownerId。任何调用方自报的 owner 都不参与判断。</p>
     *
     * @param fund 资金桶
     * @return 真实财产主体，或 null（未知桶）
     */
    public String ownerIdOf(Fund fund) {
        Objects.requireNonNull(fund, "资金桶");
        if (fund instanceof Fund.Cash cash) {
            CashLedger.CashAccount account = accounts.get(cash.accountId());
            return account == null ? null : account.ownerId();
        }
        BankingProfile profile = bank.chart().profile();
        String key = fund instanceof Fund.Deposit deposit
                ? deposit.holderKey()
                : ((Fund.Custody) fund).clientKey();
        BankingProfile.PartyRef party = profile.parties().get(key);
        return party == null ? null : party.ownerId();
    }

    /**
     * 组合校验：银行不变量 + 池存款与客户明细相等 + 身份/现金/档案口径。
     *
     * @param config 冻结档案
     * @return 违反项（空表示通过）
     */
    public List<String> validate(BankConfig config) {
        return book.validate(bank, accounts, config);
    }
}
