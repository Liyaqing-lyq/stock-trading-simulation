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
        boolean researchCompanyBranch = false;
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
        } else if (runtime.operationsRun().isPresent()
                && runtime.operationsRun().orElseThrow().company(companyPartyId) != null) {
            var company = runtime.operationsRun().orElseThrow().company(companyPartyId);
            var report = runtime.operationsStatement(companyPartyId).orElseThrow();
            basis = "差异制造：同一帧物料/生产/合同/设备台账 + 唯一现金 + 真实金融权利";
            cashAccountId = company.cashAccountId();
            assets.put("经营现金", report.companyCash());
            assets.put("原材料", report.materials());
            assets.put("在制", report.wip());
            assets.put("成品", report.products());
            assets.put("应收", report.receivables());
            assets.put("设备净值", report.equipmentNet());
            assets.put("在建工程", report.cip());
            assets.put("残余资产", report.residual());
            liabilities.put("经营应付", report.payables());
            income.put("营业收入（累计）", report.revenue());
            income.put("处置损益（累计）", report.disposalResult());
            expense.put("销售成本（累计）", report.cogs());
            expense.put("期间费用（累计）", report.expense());
            expense.put("认证费用（累计）", report.certificationExpense());
            expense.put("折旧（累计）", report.depreciation());
            expense.put("坏账（累计）", report.badDebtLoss());
            equity.put("资本（EXP 历史资本）", company.ledger().initialEquity());
            sources.add("ManufacturingTemplateModel：实际经营台账，金融资产另按权利合并一次");
            unsupported.add("本模板未接所得税、租赁与重估；参数是EXP，不是现实校准");
        } else if (runtime.operationsRun().isPresent() && runtime.operationsRun().orElseThrow().resource() != null
                && companyPartyId.equals(runtime.operationsRun().orElseThrow().resource().companyPartyId())) {
            var resource = runtime.operationsRun().orElseThrow().resource();
            var report = runtime.resourceStatement().orElseThrow();
            cashAccountId = resource.ledger().config().companyAccountId();
            basis = "资源一体化：真实外部销售、内部转移抵销、库存/在制/设备与唯一资金账";
            assets.put("经营现金", report.companyCash()); assets.put("矿端库存煤", report.mineCoal());
            assets.put("厂内燃料", report.plantFuel()); assets.put("在制品", report.wip());
            assets.put("应收", report.receivables()); assets.put("设备净值", report.equipmentNet());
            assets.put("在建工程", report.cip()); assets.put("残余资产", report.residual());
            liabilities.put("经营应付", report.payables());
            income.put("外部营业收入（累计）", report.revenue()); income.put("处置损益（累计）", report.disposalResult());
            expense.put("销售成本（累计）", report.cogs()); expense.put("期间费用（累计）", report.fixedExpense());
            expense.put("折旧（累计）", report.depreciation()); expense.put("退出损失（累计）", report.exitLoss());
            expense.put("作业损失（累计）", report.jobLoss()); expense.put("坏账（累计）", report.badDebtLoss());
            equity.put("资本（EXP历史资本及真实注资）", resource.ledger().initialEquity().add(resource.ledger().capitalContributions()));
            sources.add("IntegratedResourcesModel：有限煤炭/运输/发电及真实外部回款；内部结算不重复形成收入");
            unsupported.add("资源参数为EXP，未作现实校准；所得税与环境治理费用尚未建模");
        } else if (runtime.operationsRun().isPresent() && runtime.operationsRun().orElseThrow().hydro() != null
                && companyPartyId.equals(runtime.operationsRun().orElseThrow().hydro().companyPartyId())) {
            var hydro=runtime.operationsRun().orElseThrow().hydro();
            var report=edu.cufe.auction.world.HydroModel.report(hydro.ledger(),snap.accounts());
            cashAccountId=hydro.config().model().companyAccountId();
            basis="水电：实际水量守恒、受约束发电与交付、应收和真实回款";
            assets.put("经营现金",report.companyCash()); assets.put("设备净值",report.equipmentNet()); assets.put("应收电费",report.receivable());
            liabilities.put("应付运维",report.payable()); income.put("售电收入（累计）",report.revenue());
            expense.put("运维费用（累计）",report.operatingCost()); expense.put("检修费用（累计）",report.maintenanceCost()); expense.put("折旧（累计）",report.depreciation());
            equity.put("资本（EXP历史禀赋）",hydro.ledger().initialEquity());
            sources.add("HydroModel：有限来水、生态/指定下泄、真实发电交付与有限购电方；银行/证券同帧合并");
            unsupported.add("物理与价格参数为EXP；未模拟多站梯级、税务与完整电网；有限来水结束即暂停要求新情景");
        } else if (runtime.operationsRun().isPresent() && runtime.operationsRun().orElseThrow().digital()!=null && companyPartyId.equals(runtime.operationsRun().orElseThrow().digital().companyPartyId())) {
            var company=runtime.operationsRun().orElseThrow().digital();var bank=runtime.bankOf().orElseThrow().decode(snap.accounts());
            var report=edu.cufe.auction.world.DigitalServiceModel.report(company.ledger(),new edu.cufe.auction.world.bank.FundState(snap.accounts(),bank.state(),bank.book()));
            cashAccountId=company.config().model().companyAccountId();basis="数字服务：阶段试验、有限需求和真实服务交付；预收不是收入";
            assets.put("经营现金",report.companyCash());assets.put("应收服务款",report.receivables());assets.put("平台净值",report.platformNet());assets.put("在研",report.wip());
            liabilities.put("应付费用",report.payables());liabilities.put("未赚预收及退款义务",report.refundObligation());
            income.put("服务收入（累计）",report.revenue());expense.put("服务成本（累计）",report.serviceCogs());expense.put("试点费用（累计）",report.pilotExpense());expense.put("摊销（累计）",report.amortization());expense.put("减值（累计）",report.impairment());expense.put("坏账（累计）",report.badDebtLoss());expense.put("固定费用（累计）",report.fixedExpense());
            equity.put("资本（EXP历史禀赋）",company.ledger().initialEquity().add(report.capitalContributions()));
            sources.add("DigitalServiceModel真实阶段、预收、交付与回款；完整金融头寸同帧合并");
            unsupported.add("技术准入属于EXP客户许可，不是董事会决议；世界不开放无真实决议的新注资");
        } else if (runtime.operationsRun().isPresent() && runtime.operationsRun().orElseThrow().aged()!=null && companyPartyId.equals(runtime.operationsRun().orElseThrow().aged().companyPartyId())) {
            var company=runtime.operationsRun().orElseThrow().aged();var report=edu.cufe.auction.world.AgedInventoryModel.books(company.ledger(),snap.accounts());
            cashAccountId=company.config().model().companyAccountId();basis="陈酿：原料、成熟库存、寄售和终端售出分开；发货不确认收入";
            assets.put("经营现金",report.companyCash());assets.put("存货成本",report.inventoryCost());assets.put("应收渠道款",report.receivable());
            liabilities.put("未抵预收",report.contractLiability());liabilities.put("应付退款",report.refundPayable());liabilities.put("应付固定费",report.fixedPayable());
            income.put("终端售出收入（累计）",report.revenue());expense.put("销售成本（累计）",report.cogs());expense.put("损耗过期（累计）",report.spoilageLoss());expense.put("固定费用（累计）",report.fixedAccrued());
            equity.put("资本（EXP历史禀赋）",company.ledger().openingEquity());
            sources.add("AgedInventoryModel逐批数量/成本/预收闭合；世界资金端口与完整金融头寸");
            unsupported.add("无实际品牌/需求校准；未付款原料在到货期末失效并记录，已存在义务不会因缺预算抹去");
        } else if (runtime.researchRun().isPresent()
                && companyPartyId.equals(runtime.researchRun().orElseThrow().companyPartyId())) {
            /*
             * 独立研发公司：没有制造/工程子账本，只有唯一现金账户、研发资产与自己的 EXP 历史资本。
             * 这里不借用工程或制造的开局权益，也不把银行报表套过来；资本与损益全部由真实
             * ResearchModel.Report 给出，收入侧为空（本切片没有产品销售/许可）。
             */
            var research = runtime.researchRun().orElseThrow();
            var report = edu.cufe.auction.world.ResearchModel.report(research.state(),
                    snap.accounts());
            basis = "独立研发公司：唯一现金账户 + 研发资产 + 组合派生金融资产 + EXP 历史资本";
            cashAccountId = research.state().config().companyAccountId();
            assets.put("经营现金", report.companyCash());
            assets.put("既有非研发资产", report.otherAssets());
            assets.put("研发在研资产", report.workInProgress());
            assets.put("研发可用资产净额", report.availableNet());
            expense.put("研发费用（累计）", report.expense());
            expense.put("研发摊销（累计）", report.accumulatedAmortization());
            expense.put("研发减值（累计）", report.impairment());
            equity.put("资本（EXP 历史资本）", research.state().initialEquity());
            sources.add("ResearchModel.Report（现金/在研/可用净额/费用/摊销/减值/权益）");
            sources.add("OpeningRegistry：PH1 发行与持股 + pharma-owner 历史资本（EXP）");
            unsupported.add("研发公司尚无产品销售或许可收入：商业化批准不等于已取得收入");
            unsupported.add("研发公司未接分红、递延税、租赁与金融资产公允价值变动");
            researchCompanyBranch = true;
        } else {
            throw new IllegalArgumentException("该公司没有经营子账本：" + companyPartyId);
        }

        var research = runtime.researchRun().orElse(null);
        if (!researchCompanyBranch && research != null
                && companyPartyId.equals(research.companyPartyId())) {
            var report = edu.cufe.auction.world.ResearchModel.report(research.state(), snap.accounts());
            assets.put("研发在研资产", report.workInProgress());
            assets.put("研发可用资产净额", report.availableNet());
            expense.put("研发费用（累计）", report.expense());
            expense.put("研发减值（累计）", report.impairment());
            expense.put("研发摊销（累计）", report.accumulatedAmortization());
            sources.add("ResearchModel：原始阶段付款、失败、批准与摊销；不叠加研发模型期初权益或现金");
            unsupported.add("研发批准不等于商业收入；尚未产生产品销售或许可收益");
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
        expense.put("借款利息费用（累计）", position.loanInterestExpense());
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
