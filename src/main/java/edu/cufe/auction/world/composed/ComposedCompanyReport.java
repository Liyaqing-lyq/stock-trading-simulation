package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.EngineeringModel;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公司完整经营报表：以真实子账本为权威，把银行存款与证券持仓作为派生资产补进来。
 *
 * <p><b>为什么需要它</b>：{@link ClientCompanyReport} 只覆盖银行关系，不能替代经营报表；
 * 而制造/工程子账本只记本业现金与经营资产负债。组合世界里同一笔财富可以以
 * 「经营现金 / 银行存款 / 证券持仓」三种形态存在，因此完整资产必须三者相加，A=L+E
 * 才继续成立。</p>
 *
 * <p><b>口径来源</b></p>
 * <ul>
 *   <li>制造公司：{@link WorldRuntime#manufacturingStatement()}（现金、原材料、在制、成品、
 *       应收、设备净值、应付、损益，含组合派生金融资产）。</li>
 *   <li>工程公司：{@link WorldRuntime#engineeringStatement(String)}（应收/质保/减值/
 *       合同资产与负债/应付/损益，含只读资本与分红上下文）。</li>
 *   <li>派生资产：{@link WorldRuntime#composedFinancialAssets(String)}；银行关系明细见
 *       {@link ClientCompanyReport}。</li>
 * </ul>
 *
 * <p><b>开局证券基线</b>：子账本开局权益当初不含证券持仓，因此报表在权益侧单列
 * 「开局未计入子账本权益的证券成本」，使 A=L+E 在完整口径下闭合；这一行是冻结开局登记的
 * 真实常数，不是凑数的调平项。</p>
 */
public record ComposedCompanyReport(String companyPartyId, String basis,
                                    Map<String, BigDecimal> assets,
                                    Map<String, BigDecimal> liabilities,
                                    Map<String, BigDecimal> equity,
                                    Map<String, BigDecimal> income,
                                    Map<String, BigDecimal> expense,
                                    ClientCompanyReport financial,
                                    List<String> sources,
                                    List<String> unsupported) {

    /** 校验并冻结各分节。 */
    public ComposedCompanyReport {
        assets = Map.copyOf(new LinkedHashMap<>(assets));
        liabilities = Map.copyOf(new LinkedHashMap<>(liabilities));
        equity = Map.copyOf(new LinkedHashMap<>(equity));
        income = Map.copyOf(new LinkedHashMap<>(income));
        expense = Map.copyOf(new LinkedHashMap<>(expense));
        sources = List.copyOf(sources);
        unsupported = List.copyOf(unsupported);
    }

    /**
     * 从真实运行构造完整公司报表。
     *
     * @param runtime        当前运行
     * @param companyPartyId 公司组织
     * @return 完整报表；未知公司明确报错
     */
    public static ComposedCompanyReport of(WorldRuntime runtime, String companyPartyId) {
        WorldRuntime.Snapshot snap = runtime.snapshot();
        Map<String, BigDecimal> assets = new LinkedHashMap<>();
        Map<String, BigDecimal> liabilities = new LinkedHashMap<>();
        Map<String, BigDecimal> equity = new LinkedHashMap<>();
        Map<String, BigDecimal> income = new LinkedHashMap<>();
        Map<String, BigDecimal> expense = new LinkedHashMap<>();
        List<String> sources = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        String basis;
        String cashAccountId;
        ManufacturingModel.Report manufacturing = null;
        EngineeringModel.Report engineering = null;
        if (snap.manufacturing().isPresent()
                && companyPartyId.equals(snap.governance().map(run ->
                        run.board().charter().companyPartyId()).orElse(null))) {
            manufacturing = runtime.manufacturingStatement().orElseThrow();
            basis = "制造子账本 + 组合派生金融资产";
            cashAccountId = snap.manufacturing().orElseThrow().ledger().opening()
                    .companyAccountId();
            sources.add("ManufacturingModel.Report（现金/原材料/在制/成品/应收/设备/应付/损益）");
            assets.put("经营现金", manufacturing.companyCash());
            assets.put("原材料", manufacturing.rawAmount());
            assets.put("在制", manufacturing.wipAmount());
            assets.put("成品", manufacturing.finishedAmount());
            assets.put("应收", manufacturing.receivableOutstanding());
            assets.put("设备净值", manufacturing.equipmentNet());
            liabilities.put("应付", manufacturing.payableOutstanding());
            income.put("营业收入（累计）", manufacturing.revenue());
            expense.put("销售成本（累计）", manufacturing.costOfGoodsSold());
            expense.put("期间费用（累计）", manufacturing.periodExpense());
            expense.put("折旧（累计）", manufacturing.depreciation());
            equity.put("开局权益", manufacturing.initialEquity());
            var dividends = runtime.manufacturingDividendFacts().orElseThrow();
            equity.put("已宣告分红（累计）", dividends.declared().negate());
            liabilities.put("未付分红", dividends.unpaid());
            sources.add("ManufacturingModel.DividendPosition：已宣告/未付分红（真实分红事件）");
            unsupported.add("制造子账本未含：递延税、资产减值以外的公允价值变动");
        } else if (snap.engineering().isPresent()
                && snap.engineering().orElseThrow().stateOf(companyPartyId) != null) {
            engineering = runtime.engineeringStatement(companyPartyId).orElseThrow();
            basis = "工程子账本 + 组合派生金融资产 + 只读资本/分红上下文";
            cashAccountId = snap.engineering().orElseThrow().config()
                    .specOf(companyPartyId).companyAccountId();
            sources.add("EngineeringModel.Report（应收/质保/减值/合同资产与负债/应付/损益）");
            assets.put("经营现金", engineering.companyCash());
            assets.put("应收毛额", engineering.arGross());
            assets.put("减值准备", engineering.allowance().negate());
            assets.put("合同资产", engineering.contractAsset());
            assets.put("履约成本", engineering.contractCost());
            liabilities.put("应付", engineering.payable());
            liabilities.put("合同负债", engineering.contractLiability());
            income.put("营业收入（累计）", engineering.revenue());
            expense.put("销售成本（累计）", engineering.cogs());
            expense.put("减值损失（累计）", engineering.impairmentLoss());
            income.put("收回收益（累计）", engineering.recoveryIncome());
            expense.put("终止损失（累计）", engineering.terminationLoss());
            expense.put("豁免损失（累计）", engineering.waiverLoss());
            equity.put("开局权益", snap.engineering().orElseThrow().stateOf(companyPartyId)
                    .initialEquity());
            var context = runtime.engineeringContext(companyPartyId).orElseThrow();
            equity.put("净外部资本（累计）", context.netExternalCapital());
            equity.put("已宣告分红（累计）", context.declaredDividends().negate());
            liabilities.put("未付分红", context.unpaidDividends());
            sources.add("EngineeringModel.ReportContext：净注资/已宣告/未付分红（真实资本与分红事件）");
            unsupported.add("工程子账本未含：租赁、融资租赁与所得税");
        } else {
            throw new IllegalArgumentException("该公司没有经营子账本：" + companyPartyId);
        }

        WorldRuntime.ComposedFinancialPosition position =
                runtime.composedFinancialPosition(cashAccountId);
        assets.put("银行存款", position.bankDeposits());
        assets.put("应收存款利息", position.depositInterest());
        assets.put("证券持仓成本", position.securitiesCost());
        assets.put("客户托管受益权", position.custodyBeneficial());
        assets.put("托管池存款", position.custodyPoolAssets());
        liabilities.put("借款本金", position.loanPrincipal());
        liabilities.put("应付借款利息", position.loanInterest());
        liabilities.put("托管客户负债", position.custodyClientLiabilities());
        income.put("已实现证券投资损益（累计）", position.realizedSecuritiesPnl());
        income.put("存款利息收入（累计）", position.depositInterest());
        expense.put("借款利息费用（累计）", position.loanInterest());
        sources.add("BankState/CashLedger 派生：银行存款、应收存款利息与唯一现金权威");
        sources.add("SecuritiesLedger + WorldExchange 派生：持仓按成本计值、已实现损益来自真实成交");

        ClientCompanyReport financial = bankFinancial(runtime, companyPartyId);
        if (financial != null) {
            sources.add("ClientCompanyReport：银行关系明细（存贷/利息/追索）");
        }
        unsupported.add("市值重估、外汇折算与税务不在本轮组合报表范围");
        unsupported.add("银行自身报表见 BankCompanyReport，不与客户权利双计");
        BigDecimal profit = sum(income).subtract(sum(expense));
        equity.put("本报表累计损益", Money.scale2(profit));
        equity.put("开局未计入子账本权益的证券成本", position.openingSecuritiesCost());
        equity.put("开局未计入子账本权益的托管受益权", position.openingCustodyBeneficial());
        BigDecimal totalAssets = sum(assets);
        BigDecimal totalLiabilities = sum(liabilities);
        BigDecimal totalEquity = sum(equity);
        BigDecimal residual = totalAssets.subtract(totalLiabilities).subtract(totalEquity);
        if (residual.compareTo(java.math.BigDecimal.ZERO) != 0) {
            throw new IllegalStateException("公司完整报表不平衡：" + companyPartyId
                    + " A=" + totalAssets + " L=" + totalLiabilities + " E=" + totalEquity
                    + " 差额=" + residual);
        }
        return new ComposedCompanyReport(companyPartyId, basis, assets, liabilities, equity,
                income, expense, financial, sources, unsupported);
    }

    /**
     * 客户金融子表：只有主体确实未在银行档案登记时才返回空（缺项），
     * 坏账本、owner 不符等真实错误必须抛出，不能吞成“未登记”。
     */
    private static ClientCompanyReport bankFinancial(WorldRuntime runtime, String company) {
        BankRun run = runtime.bankOf().orElse(null);
        if (run == null) {
            return null;
        }
        try {
            return ClientCompanyReport.of(run, runtime.snapshot().accounts(), company);
        } catch (IllegalArgumentException notDeclared) {
            if (notDeclared.getMessage() != null
                    && notDeclared.getMessage().startsWith("该主体未在银行档案登记：")) {
                return null;
            }
            throw notDeclared;
        }
    }

    /** @return 资产合计 */
    public BigDecimal totalAssets() {
        return sum(assets);
    }

    /** @return 负债合计 */
    public BigDecimal totalLiabilities() {
        return sum(liabilities);
    }

    /** @return 权益合计（含开局证券成本基线） */
    public BigDecimal totalEquity() {
        return sum(equity);
    }

    /** @return 累计利润（收入 − 费用，全部为累计口径，不是单期） */
    public BigDecimal profit() {
        return Money.scale2(sum(income).subtract(sum(expense)));
    }

    /** @return 资产 = 负债 + 权益（分位精确，正负一分都必须相等） */
    public boolean balanced() {
        return totalAssets().subtract(totalLiabilities()).subtract(totalEquity())
                .compareTo(java.math.BigDecimal.ZERO) == 0;
    }

    private static BigDecimal sum(Map<String, BigDecimal> section) {
        BigDecimal total = Money.zero();
        for (BigDecimal value : section.values()) {
            total = total.add(value);
        }
        return Money.scale2(total);
    }
}
