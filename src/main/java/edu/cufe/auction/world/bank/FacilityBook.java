package edu.cufe.auction.world.bank;

import edu.cufe.auction.world.CashLedger;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 救助提供者的全面权益核对：现金只从 {@link CashLedger} 取，债权/利息/损益只从设施账户派生。
 *
 * <p>恒等式（EXP）：{@code 冻结初始资本 + 累计利息收入 + 回收 + 准备转回 − 计提 − 豁免/解除损失
 * = 现金 + 净账面 + 应收利息}。初始资本不是用当前值反推，而是开局写入
 * {@link BankState.ProviderOpening} 的冻结事实；本类只回答“它是否仍然成立”。</p>
 *
 * <p>若某笔现金、债权、利息或损益被漏记/重复记，等式两边就会不等，
 * {@link Position#reconciles()} 为 false，{@link BankInvariants} 让整批候选失败。</p>
 */
public final class FacilityBook {

    /**
     * 提供者头寸（全部为派生视图）。
     *
     * @param provider           提供者
     * @param cash               现金余额（唯一来自 CashLedger）
     * @param netBook            净账面（onbook − allowance 合计）
     * @param interestReceivable 应收利息（posted − paid 合计）
     * @param interestIncome     累计已入账利息收入
     * @param impairment         累计计提
     * @param reversal           累计准备转回
     * @param recovery           累计核销后回收
     * @param waiverLoss         累计豁免/解除损失
     * @param equity             当前净资产（现金 + 净账面 + 应收利息）
     * @param declaredCapital    冻结的初始资本（来自开局声明；未声明为 null）
     * @param reconciles         冻结初始资本 + 已实现损益 是否等于当前净资产
     */
    public record Position(String provider, BigDecimal cash, BigDecimal netBook,
                           BigDecimal interestReceivable, BigDecimal interestIncome,
                           BigDecimal impairment, BigDecimal reversal, BigDecimal recovery,
                           BigDecimal waiverLoss, BigDecimal equity, BigDecimal declaredCapital,
                           boolean reconciles) {
    }

    private FacilityBook() {
    }

    /**
     * 计算某提供者的头寸并与冻结初始资本核对。
     *
     * @param state    银行状态
     * @param accounts 现金账户（唯一现金权威）
     * @param provider 提供者
     * @return 头寸；未声明开局事实时 declaredCapital 为 null 且 reconciles 为 false
     */
    public static Position position(BankState state, Map<String, CashLedger.CashAccount> accounts,
                                    String provider) {
        BigDecimal cash = Money.zero();
        CashLedger.CashAccount account = accounts.get(state.chart().cashAccountOf(provider));
        if (account != null) {
            cash = account.cash();
        }
        BigDecimal netBook = Money.zero();
        BigDecimal receivable = Money.zero();
        BigDecimal income = Money.zero();
        BigDecimal impairment = Money.zero();
        BigDecimal reversal = Money.zero();
        BigDecimal recovery = Money.zero();
        BigDecimal waiverLoss = Money.zero();
        for (BankState.FacilityAccount facility : state.facilityAccounts().values()) {
            if (!facility.provider().equals(provider)) {
                continue;
            }
            netBook = netBook.add(facility.netBook());
            receivable = receivable.add(facility.interestPayable());
            income = income.add(facility.interestAccrued());
            impairment = impairment.add(InterestAccrual.money(facility.impairCents()));
            reversal = reversal.add(InterestAccrual.money(facility.reversalCents()));
            recovery = recovery.add(InterestAccrual.money(facility.recoveryCents()));
            waiverLoss = waiverLoss.add(InterestAccrual.money(facility.waiverLossCents()));
        }
        BigDecimal assets = Money.scale2(cash.add(netBook).add(receivable));
        BigDecimal realized = Money.scale2(income.add(recovery).add(reversal)
                .subtract(impairment).subtract(waiverLoss));
        BankState.ProviderOpening opening = state.providerOpening(provider);
        BigDecimal declared = opening == null ? null : opening.initialCapital();
        boolean reconciles = declared != null
                && Money.scale2(assets.subtract(realized)).compareTo(declared) == 0;
        return new Position(provider, cash, Money.scale2(netBook), Money.scale2(receivable),
                Money.scale2(income), Money.scale2(impairment), Money.scale2(reversal),
                Money.scale2(recovery), Money.scale2(waiverLoss), assets, declared, reconciles);
    }
}
