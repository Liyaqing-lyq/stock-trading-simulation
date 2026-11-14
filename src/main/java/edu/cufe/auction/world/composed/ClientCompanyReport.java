package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.bank.BankChart;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.BankingProfile;
import edu.cufe.auction.world.bank.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 客户公司金融子表：把同一主体在银行关系里的现金、存款、贷款与利息纳入唯一财富口径。
 *
 * <p><b>口径（按真实档案派生，不接受调用方自填账户）</b></p>
 * <ul>
 *   <li>现金：该财产主体名下<b>全部</b>现金账户合计（账户表按 ownerId 过滤），调用方不能指定
 *       某个账户读取别人的钱；同一笔钱只计一次。</li>
 *   <li>存款：该主体在<b>各银行</b>的存款债权合计（按档案 {@code depositGl} 声明派生）；
 *       未声明存款科目即“未开户”，由 {@link #depositOpened()} 明确标识，不冒充 0 余额。</li>
 *   <li>贷款：按 {@code BankState.Loan.contract()} 计<b>名义本金（含追索部分）</b>，
 *       另列 {@code onBook}（账面本金）、{@code writtenOff}（已核销仍有追索）与
 *       {@code interestReceivable}（应计未付利息）。<b>不使用</b>银行资产侧贷款 GL：
 *       核销会让 GL 减少，但客户债务并不因此消失。</li>
 *   <li>净金融资产 = 现金 + 存款 + 托管受益权 − 名义本金 − 应计未付利息。</li>
 * </ul>
 *
 * <p><b>不是完整经营报表</b>：证券持仓、存货/在制、应收应付与设备不在本类内，
 * 见 {@link ComposedCompanyReport}；本类只回答“银行关系有没有让客户凭空变富”。</p>
 */
public record ClientCompanyReport(String partyKey, String ownerId,
                                  List<String> cashAccountIds, BigDecimal cash,
                                  boolean depositDeclared, List<String> depositBanks,
                                  BigDecimal deposits,
                                  BigDecimal depositInterest,
                                  BigDecimal custodyBeneficial,
                                  List<LoanLine> loans,
                                  BigDecimal loanPrincipal, BigDecimal loanOnBook,
                                  BigDecimal writtenOff, BigDecimal interestReceivable,
                                  BigDecimal netFinancialAssets,
                                  List<String> notes, List<String> unsupported) {

    /** 校验、排序并冻结金额与清单。 */
    public ClientCompanyReport {
        cashAccountIds = List.copyOf(cashAccountIds);
        depositBanks = List.copyOf(depositBanks);
        loans = List.copyOf(loans);
        notes = List.copyOf(notes);
        unsupported = List.copyOf(unsupported);
        cash = Money.scale2(cash);
        deposits = Money.scale2(deposits);
        depositInterest = Money.scale2(depositInterest);
        custodyBeneficial = Money.scale2(custodyBeneficial);
        loanPrincipal = Money.scale2(loanPrincipal);
        loanOnBook = Money.scale2(loanOnBook);
        writtenOff = Money.scale2(writtenOff);
        interestReceivable = Money.scale2(interestReceivable);
        netFinancialAssets = Money.scale2(netFinancialAssets);
    }

    /**
     * 存款是否真实开户：档案声明了存款科目<b>且</b>至少一家银行存在该主体的存款账户。
     *
     * @return 真实开户返回 true；只声明科目但无账户返回 false（缺项，不是 0 余额）
     */
    public boolean depositOpened() {
        return depositDeclared && !depositBanks.isEmpty();
    }

    /**
     * 单笔借款合同视图（每笔合同只出现一次，不重复计本金）。
     *
     * @param bank                银行 key
     * @param borrower            借款人 key（档案内主体）
     * @param contract            名义本金（含已核销但仍有追索的部分）
     * @param onBook              银行账面本金
     * @param writtenOff          已核销金额（仍可追索）
     * @param interestReceivable  应计未付利息
     * @param status              贷款状态
     * @param overdue             是否逾期
     */
    public record LoanLine(String bank, String borrower, BigDecimal contract, BigDecimal onBook,
                           BigDecimal writtenOff, BigDecimal interestReceivable, String status,
                           boolean overdue) {
        /** 校验并冻结金额。 */
        public LoanLine {
            contract = Money.scale2(contract);
            onBook = Money.scale2(onBook);
            writtenOff = Money.scale2(writtenOff);
            interestReceivable = Money.scale2(interestReceivable);
        }
    }

    /**
     * 构造客户金融子表。
     *
     * @param run      银行运行（档案权威）
     * @param accounts 唯一现金账
     * @param partyKey 客户主体 key，必须在银行档案中已声明
     * @return 客户金融子表
     * @throws IllegalArgumentException 主体未登记（不静默补 0）
     */
    public static ClientCompanyReport of(BankRun run, Map<String, CashLedger.CashAccount> accounts,
            String partyKey) {
        BankRun.Decoded decoded = run.decode(accounts);
        BankState state = decoded.state();
        BankChart chart = state.chart();
        BankingProfile profile = chart.profile();
        BankingProfile.PartyRef ref = profile.parties().get(partyKey);
        if (ref == null) {
            throw new IllegalArgumentException("该主体未在银行档案登记：" + partyKey);
        }
        String ownerId = ref.ownerId();
        List<String> cashAccounts = new ArrayList<>();
        for (Map.Entry<String, CashLedger.CashAccount> entry
                : new TreeMap<>(accounts).entrySet()) {
            if (entry.getValue().ownerId().equals(ownerId)) {
                cashAccounts.add(entry.getKey());
            }
        }
        BigDecimal cash = Money.zero();
        for (String accountId : cashAccounts) {
            cash = cash.add(accounts.get(accountId).cash());
        }
        boolean depositDeclared = ref.depositGl() != null;
        List<String> depositBanks = new ArrayList<>();
        BigDecimal deposits = Money.zero();
        BigDecimal depositInterest = Money.zero();
        if (depositDeclared) {
            for (String bank : chart.banks()) {
                var account = state.depositAccount(bank, partyKey);
                BigDecimal balance = state.depositOf(bank, partyKey);
                if (account != null || balance.signum() != 0) {
                    depositBanks.add(bank);
                    deposits = deposits.add(balance);
                    if (account != null) {
                        depositInterest = depositInterest.add(account.interestPayable());
                    }
                }
            }
        }
        BigDecimal beneficial = Money.zero();
        for (String custodian : new TreeMap<>(profile.custodians()).keySet()) {
            beneficial = beneficial.add(decoded.book().detailOf(custodian, partyKey));
        }
        List<LoanLine> loans = new ArrayList<>();
        BigDecimal principal = Money.zero();
        BigDecimal onBook = Money.zero();
        BigDecimal writtenOff = Money.zero();
        BigDecimal interest = Money.zero();
        for (Map.Entry<String, BankState.Loan> entry
                : new TreeMap<>(state.loans()).entrySet()) {
            String borrower = chart.borrowerOfLoanKey(entry.getKey());
            if (borrower == null) {
                continue;
            }
            BankingProfile.PartyRef borrowerRef = profile.parties().get(borrower);
            if (borrowerRef == null || !borrowerRef.ownerId().equals(ownerId)) {
                continue;
            }
            BankState.Loan loan = entry.getValue();
            String bank = chart.bankOfLoanKey(entry.getKey());
            loans.add(new LoanLine(bank, borrower, loan.contract(), loan.onbook(),
                    loan.writtenOff(), loan.interestReceivable(), loan.status(),
                    loan.overdue()));
            principal = principal.add(loan.contract());
            onBook = onBook.add(loan.onbook());
            writtenOff = writtenOff.add(loan.writtenOff());
            interest = interest.add(loan.interestReceivable());
        }
        BigDecimal net = cash.add(deposits).add(depositInterest).add(beneficial)
                .subtract(principal).subtract(interest);
        List<String> notes = new ArrayList<>();
        notes.add("名义本金含已核销但仍可追索部分；核销不等于客户免债");
        notes.add("应计未付利息单列并从净金融资产扣除；应收存款利息计入客户权利");
        if (!depositDeclared) {
            notes.add("该主体未在银行档案声明存款科目：存款账户未开立（不是 0 余额）");
        } else if (depositBanks.isEmpty()) {
            notes.add("已声明存款科目但各银行均无该主体存款账户：缺项（不是 0 余额）");
        }
        if (cashAccounts.isEmpty()) {
            notes.add("该主体名下没有现金账户（不是 0 余额）");
        }
        List<String> unsupported = List.of(
                "证券持仓与经营性资产（存货/在制/应收/设备）不在本金融子表，见公司完整经营报表",
                "未接：多币种折算与减值后预期信用损失重估");
        return new ClientCompanyReport(partyKey, ownerId, cashAccounts, cash, depositDeclared,
                depositBanks, deposits, depositInterest, beneficial, loans, principal, onBook,
                writtenOff, interest, net, notes, unsupported);
    }

    /** @return 便于断言的固定文本 */
    public String summary() {
        return partyKey + " cash=" + cash.toPlainString()
                + " deposits=" + (depositOpened() ? deposits.toPlainString() : "UNOPENED")
                + " banks=" + depositBanks
                + " depositInterest=" + depositInterest.toPlainString()
                + " principal=" + loanPrincipal.toPlainString()
                + " interest=" + interestReceivable.toPlainString()
                + " net=" + netFinancialAssets.toPlainString();
    }

    /** @return 本视图使用的主体与现金账户（不跨主体相加） */
    public Map<String, String> keys() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("party", partyKey);
        map.put("owner", ownerId);
        for (String accountId : cashAccountIds) {
            map.put("cash", accountId);
        }
        return map;
    }
}
