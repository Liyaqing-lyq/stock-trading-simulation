package edu.cufe.auction.world.bizprobe;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.EngineeringModel;
import edu.cufe.auction.world.EngineeringModel.ContractSpec;
import edu.cufe.auction.world.EngineeringModel.FundOutcome;
import edu.cufe.auction.world.EngineeringModel.FundedReport;
import edu.cufe.auction.world.EngineeringModel.Outcome;
import edu.cufe.auction.world.EngineeringModel.ReportContext;
import edu.cufe.auction.world.EngineeringModel.RetentionMode;
import edu.cufe.auction.world.EngineeringModel.Terms;
import edu.cufe.auction.world.FundPayer;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.bank.BankChart;
import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankContext;
import edu.cufe.auction.world.bank.BankInvariants;
import edu.cufe.auction.world.bank.BankOpening;
import edu.cufe.auction.world.bank.BankRules;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.BankingProfile;
import edu.cufe.auction.world.bank.CashFreeze;
import edu.cufe.auction.world.bank.CustodyBook;
import edu.cufe.auction.world.bank.Fund;
import edu.cufe.auction.world.bank.FundState;
import edu.cufe.auction.world.bank.Money;
import edu.cufe.auction.world.bank.PaymentPortFundPayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B7c 经营资金腿定向验证：真实业务链产生义务，只经真实资金桶付款/收款。
 *
 * <p>覆盖：工程预收、应收/质保回款、备查回款、履约人工付现、供应商付款；制造收货付款、
 * 投产付薪、应收回款、期末费用付款与账单结清。另含：冻结拒后释放、顺序两笔用当前态、
 * 两层分叉（资金有业务无 / 业务有资金无）、同标识重放与换桶冲突、跨行不足整动作不变、
 * 多腿不半应用、主体不符、报表平衡与初始权益/资本/分红不重算。</p>
 *
 * <p>不是生产代码；产物只落本目录。任一断言失败以退出码 1 结束。</p>
 */
public final class BizFundsSelfTest {

    private static int passed;
    private static final List<String> failed = new ArrayList<>();
    private static final long DAY = 86_400_000L;
    private static final long PERIOD = 30L * DAY;
    private static final long T0 = 1_900_000_000_000L;

    private BizFundsSelfTest() {
    }

    /**
     * 跑全部用例。
     *
     * @param args 忽略
     */
    public static void main(String[] args) {
        advanceFromCustomerDeposit();
        advanceOverCapRejected();
        payablePaidFromDeposit();
        receivableCollectedFromCustomer();
        retentionCollectedAfterDue();
        recoverWrittenOff();
        laborPaidFromDeposit();
        custodyPaymentLeg();
        freezeBlocksThenReleases();
        sequentialPaysUseCurrentState();
        orphanFundRecord();
        businessRecordWithoutFund();
        sameIdBindsBucket();
        sameIdBindsAmount();
        multiLegNoPartial();
        crossBankInsufficient();
        ownerMismatch();
        untouchedCashApi();
        manufacturingChain();
        manufacturingFreezeDerivesAvailable();
        manufacturingOrphanFundRecord();
        manufacturingBusinessWithoutFund();
        System.out.println();
        System.out.println("PASS=" + passed + " FAIL=" + failed.size());
        for (String item : failed) {
            System.out.println("  FAILED: " + item);
        }
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 工程

    private static void advanceFromCustomerDeposit() {
        Eng f = new Eng("adv", "0.00", "300.00", "0.00", false, 0, "100.00");
        f.sign("S-1", 2000, 0);
        FundOutcome in = f.advance("A-1", "100.00", f.depositPayer("cust", "co"));
        ok("预收：客户存款 100 → 公司存款 100，合同负债 100，收入 0，初始权益不因预收变化",
                in.accepted() && EngineeringModel.REASON_ADVANCE_RECEIVED.equals(in.reason())
                        && f.deposit("north", "co").compareTo(money("400.00")) == 0
                        && f.state.claims().get("C-1").contractLiability()
                        .compareTo(money("100.00")) == 0
                        && f.state.claims().get("C-1").revenue().compareTo(Money.zero()) == 0
                        && f.state.initialEquity().compareTo(money("300.00")) == 0,
                in.reason() + " co=" + f.deposit("north", "co")
                        + " initial=" + f.state.initialEquity());
        FundedReport report = f.report();
        ok("预收后报表：义务不是收入，资产 = 负债 + 权益且世界现金守恒",
                report.liabilities().compareTo(money("100.00")) == 0
                        && report.equity().compareTo(money("300.00")) == 0
                        && report.balanced(),
                "liab=" + report.liabilities() + " equity=" + report.equity()
                        + " assets=" + report.assets());
    }

    private static void advanceOverCapRejected() {
        Eng f = new Eng("adv-cap", "0.00", "0.00", "0.00", false, 0, "100.00");
        f.sign("S-1", 2000, 0);
        FundOutcome over = f.advance("A-1", "500.00", f.depositPayer("cust", "co"));
        ok("超预收上限（合同 2000×20% = 400）→ ADVANCE_EXCEEDS_TERMS，一分不动",
                !over.accepted()
                        && EngineeringModel.REASON_ADVANCE_EXCEEDS_TERMS.equals(over.reason())
                        && f.deposit("north", "cust").compareTo(money("100.00")) == 0
                        && f.deposit("north", "co").compareTo(Money.zero()) == 0,
                over.reason());
    }

    private static void payablePaidFromDeposit() {
        Eng f = new Eng("pay", "700.00", "300.00", "0.00", false, 0, "0.00");
        f.sign("S-1", 0, 0);
        Outcome work = f.invest("W-1", 5);
        FundOutcome paid = f.pay("P-1", "100.00", f.depositPayer("co", "sup"));
        ok("已接受接缝的数例：现金 700 + 存款 300 → 付已有应付 100 → 现金 700、公司存款 200、"
                        + "供应商存款 100、应付 500→400",
                work.accepted() && paid.accepted()
                        && f.cash("co-cash").compareTo(money("700.00")) == 0
                        && f.deposit("north", "co").compareTo(money("200.00")) == 0
                        && f.deposit("north", "sup").compareTo(money("100.00")) == 0
                        && f.payable().compareTo(money("400.00")) == 0,
                paid.reason() + " co=" + f.deposit("north", "co")
                        + " payable=" + f.payable());
        FundedReport report = f.report();
        ok("付款不重算权益：资产 1400 = 负债 400 + 权益 1000，利润仍 0，报表平衡",
                report.assets().compareTo(money("1400.00")) == 0
                        && report.liabilities().compareTo(money("400.00")) == 0
                        && report.equity().compareTo(money("1000.00")) == 0
                        && report.balanced(),
                "assets=" + report.assets() + " equity=" + report.equity());
    }

    private static void receivableCollectedFromCustomer() {
        Eng f = new Eng("ar", "0.00", "0.00", "0.00", false, 0, "300.00");
        f.acceptChain();
        BigDecimal revenueBefore = f.state.claims().get("C-1").revenue();
        FundOutcome collect = f.collectDue("R-1", 0, "200.00", f.depositPayer("cust", "co"));
        BigDecimal arAfter = f.state.claims().get("C-1").arCurrent();
        ok("应收（正常回款）：客户存款 200 → 公司存款 200，AR 只减不重记收入",
                collect.accepted() && EngineeringModel.REASON_COLLECTED.equals(collect.reason())
                        && f.deposit("north", "co").compareTo(money("200.00")) == 0
                        && f.deposit("north", "cust").compareTo(money("100.00")) == 0
                        && f.state.claims().get("C-1").revenue().compareTo(revenueBefore) == 0
                        && arAfter.compareTo(money("600.00")) == 0,
                collect.reason() + " ar=" + arAfter + " revenue=" + revenueBefore);
        FundedReport report = f.report();
        ok("回款后报表仍平衡：收入不变、权益只来自已确认利润（1000 收入 − 500 成本）",
                report.balanced() && report.equity().compareTo(money("500.00")) == 0,
                "equity=" + report.equity() + " assets=" + report.assets());
    }

    private static void retentionCollectedAfterDue() {
        Eng f = new Eng("ret", "0.00", "1000.00", "0.00", false, 0, "100.00");
        f.sign("S-1", 0, 1000);
        f.acceptChain();
        BigDecimal revenue = f.state.claims().get("C-1").revenue();
        FundOutcome early = f.collectRetention("RT-0", 0, "100.00", f.depositPayer("cust", "co"));
        FundOutcome late = f.collectRetentionAt("RT-1", 0, "100.00",
                f.depositPayer("cust", "co"), T0 + 40L * DAY);
        ok("质保回款：到期前拒绝（NOT_DUE）；到期后客户存款 100 → 公司存款，收入不二次确认",
                !early.accepted() && EngineeringModel.REASON_NOT_DUE.equals(early.reason())
                        && late.accepted()
                        && f.state.claims().get("C-1").revenue().compareTo(revenue) == 0
                        && f.state.claims().get("C-1").arRetention()
                        .compareTo(money("100.00")) == 0,
                early.reason() + "|" + late.reason());
    }

    private static void recoverWrittenOff() {
        Eng f = new Eng("rec", "0.00", "600.00", "0.00", false, 0, "100.00");
        f.acceptChain();
        Outcome impaired = EngineeringModel.impair(f.state, "IM-1", "co-owner", "C-1",
                money("100.00"), f.now, f.accounts);
        f.state = impaired.state();
        Outcome off = EngineeringModel.writeOff(f.state, "X-1", "co-owner", "C-1",
                money("100.00"), f.now, f.accounts);
        f.state = off.state();
        FundOutcome back = f.recover("RC-1", "40.00", f.depositPayer("cust", "co"));
        ok("备查回款：核销后回款 40 从客户存款进公司存款（600→640），追回收益 +40、备查 -40",
                impaired.accepted() && off.accepted() && back.accepted()
                        && EngineeringModel.REASON_RECOVERED.equals(back.reason())
                        && f.deposit("north", "co").compareTo(money("640.00")) == 0
                        && f.state.claims().get("C-1").recoveryIncome()
                        .compareTo(money("40.00")) == 0
                        && f.state.claims().get("C-1").memo().compareTo(money("60.00")) == 0,
                impaired.reason() + "|" + off.reason() + "|" + back.reason()
                        + " memo=" + f.state.claims().get("C-1").memo()
                        + " recovery=" + f.state.claims().get("C-1").recoveryIncome()
                        + " co=" + f.deposit("north", "co"));
    }

    private static void laborPaidFromDeposit() {
        Eng f = new Eng("labor", "0.00", "500.00", "0.00", false, 3000, "0.00");
        f.sign("S-1", 0, 0);
        FundOutcome work = f.investFunded("W-1", 5, new PaymentPortFundPayer(f.config, f.ctx(),
                CashFreeze.none(), "co", new Fund.Deposit("north", "co"),
                new Fund.Deposit("north", "ops")));
        ok("履约人工付现走存款桶：公司存款 500→350、作业方存款 0→150、WIP 500、应付 350（分包部分）",
                work.accepted() && EngineeringModel.REASON_WORK_STARTED.equals(work.reason())
                        && f.deposit("north", "co").compareTo(money("350.00")) == 0
                        && f.deposit("north", "ops").compareTo(money("150.00")) == 0
                        && f.cash("ops-cash").compareTo(money("50.00")) == 0
                        && f.state.claims().get("C-1").contractCost().compareTo(money("500.00")) == 0
                        && f.payable().compareTo(money("350.00")) == 0,
                work.reason() + " co=" + f.deposit("north", "co") + " payable=" + f.payable());
        ok("人工付现不重算初始权益：权益 500 不变，报表平衡", f.report().balanced()
                        && f.report().equity().compareTo(money("500.00")) == 0,
                "equity=" + f.report().equity());
    }

    private static void custodyPaymentLeg() {
        Eng f = new Eng("cust", "100.00", "0.00", "0.00", true, 0, "0.00");
        f.sign("S-1", 0, 0);
        f.invest("W-1", 5);
        PaymentPortFundPayer into = new PaymentPortFundPayer(f.config, f.ctx(), CashFreeze.none(),
                "co", new Fund.Cash("co-cash"), new Fund.Custody("cst", "co"));
        f.adopt(into.pay("CU-1", money("100.00"), f.funds()));
        FundOutcome paid = f.pay("P-1", "100.00", new PaymentPortFundPayer(f.config, f.ctx(),
                CashFreeze.none(), "co", new Fund.Custody("cst", "co"),
                new Fund.Deposit("north", "sup")));
        ok("托管正例：现金→受益权→供应商存款，三态自洽、应付 500→400、权益不变",
                paid.accepted()
                        && f.book.detailOf("cst", "co").compareTo(Money.zero()) == 0
                        && f.deposit("north", "sup").compareTo(money("100.00")) == 0
                        && f.payable().compareTo(money("400.00")) == 0
                        && f.funds().validate(f.config).isEmpty(),
                paid.reason() + " sup=" + f.deposit("north", "sup"));
    }

    private static void freezeBlocksThenReleases() {
        Eng f = new Eng("freeze", "0.00", "300.00", "0.00", false, 0, "0.00");
        f.sign("S-1", 0, 0);
        f.invest("W-1", 5);
        String bucket = new Fund.Deposit("north", "co").token();
        FundPayer freezing = new PaymentPortFundPayer(f.config, f.ctx(),
                new CashFreeze(Map.of(bucket, money("250.00"))), "co",
                new Fund.Deposit("north", "co"), new Fund.Deposit("north", "sup"));
        FundOutcome blocked = f.pay("F-1", "100.00", freezing);
        ok("有 250 冻结时可用额只有 50 → 付 100 被拒，存款与应付都不动",
                !blocked.accepted()
                        && f.deposit("north", "co").compareTo(money("300.00")) == 0
                        && f.payable().compareTo(money("500.00")) == 0,
                blocked.reason());
        FundOutcome released = f.pay("F-2", "100.00", f.depositPayer("co", "sup"));
        ok("同一笔在冻结释放后成功：存款 300→200、供应商 0→100、应付 500→400",
                released.accepted()
                        && f.deposit("north", "co").compareTo(money("200.00")) == 0
                        && f.deposit("north", "sup").compareTo(money("100.00")) == 0
                        && f.payable().compareTo(money("400.00")) == 0,
                released.reason() + " co=" + f.deposit("north", "co"));
    }

    private static void sequentialPaysUseCurrentState() {
        Eng f = new Eng("seq", "0.00", "300.00", "0.00", false, 0, "0.00");
        f.sign("S-1", 0, 0);
        f.invest("W-1", 5);
        PaymentPortFundPayer same = f.depositPayer("co", "sup");
        FundState input = f.funds();
        FundOutcome first = f.pay("Q-1", "100.00", same);
        FundOutcome second = f.pay("Q-2", "100.00", same);
        ok("顺序两笔用当前态：存款 300→200→100、应付 500→400→300，两笔回执都在",
                first.accepted() && second.accepted()
                        && f.deposit("north", "co").compareTo(money("100.00")) == 0
                        && f.payable().compareTo(money("300.00")) == 0
                        && f.bank.command("Q-1") != null && f.bank.command("Q-2") != null,
                "co=" + f.deposit("north", "co") + " payable=" + f.payable());
        ok("入参三态不可变：原始 FundState 仍是 300，payer 里没有可复用旧账",
                input.deposit("north", "co").compareTo(money("300.00")) == 0,
                "input=" + input.deposit("north", "co"));
    }

    private static void orphanFundRecord() {
        Eng f = new Eng("orphan-fund", "0.00", "300.00", "0.00", false, 0, "0.00");
        f.sign("S-1", 0, 0);
        f.invest("W-1", 5);
        FundPayer.Result lone = f.depositPayer("co", "sup")
                .pay("Z-1", money("50.00"), f.funds());
        f.adopt(lone.funds());
        FundOutcome out = f.pay("Z-1", "50.00", f.depositPayer("co", "sup"));
        ok("资金层有记录、业务层没有 → FUND_ID_ALREADY_RECORDED，不写业务记录、不重复扣",
                lone.accepted() && !out.accepted()
                        && EngineeringModel.REASON_FUND_ID_ALREADY_RECORDED.equals(out.reason())
                        && out.state().commandContents().get("Z-1") == null
                        && f.payable().compareTo(money("500.00")) == 0,
                out.reason());
    }

    private static void businessRecordWithoutFund() {
        Eng f = new Eng("orphan-biz", "0.00", "300.00", "0.00", false, 0, "0.00");
        f.sign("S-1", 0, 0);
        f.invest("W-1", 5);
        FundState before = f.funds();
        FundOutcome paid = f.pay("D-1", "100.00", f.depositPayer("co", "sup"));
        FundOutcome replay = f.pay("D-1", "100.00", f.depositPayer("co", "sup"));
        ok("合法迟到重放：两层一致 → 返回本次当前候选，不重复扣款",
                paid.accepted() && replay.accepted()
                        && f.deposit("north", "co").compareTo(money("200.00")) == 0,
                replay.reason() + " co=" + f.deposit("north", "co"));
        FundOutcome diverged = EngineeringModel.paySupplier(f.state, "D-1", "co-owner",
                money("100.00"), f.now, before, f.depositPayer("co", "sup"));
        ok("业务有成功记录、资金缺记录（三态回退）→ FUND_RECORD_DIVERGED，不返回成功",
                !diverged.accepted()
                        && EngineeringModel.REASON_FUND_RECORD_DIVERGED.equals(diverged.reason()),
                diverged.reason());
    }

    private static void sameIdBindsBucket() {
        Eng f = new Eng("bind", "300.00", "300.00", "0.00", false, 0, "0.00");
        f.sign("S-1", 0, 0);
        f.invest("W-1", 5);
        f.pay("B-1", "100.00", f.depositPayer("co", "sup"));
        boolean conflict = false;
        try {
            f.pay("B-1", "100.00", new PaymentPortFundPayer(f.config, f.ctx(), CashFreeze.none(),
                    "co", new Fund.Cash("co-cash"), new Fund.Cash("sup-cash")));
        } catch (IllegalArgumentException expected) {
            conflict = "COMMAND_CONTENT_CONFLICT".equals(expected.getMessage());
        }
        ok("同标识换桶（存款→现金）→ COMMAND_CONTENT_CONFLICT，账停在上一次结果",
                conflict && f.cash("co-cash").compareTo(money("300.00")) == 0
                        && f.deposit("north", "co").compareTo(money("200.00")) == 0,
                "conflict=" + conflict);
    }

    private static void multiLegNoPartial() {
        Eng f = new Eng("fold", "0.00", "300.00", "0.00", false, 0, "0.00");
        f.sign("S-1", 0, 0);
        f.invest("W-1", 5);
        FundOutcome first = f.pay("M-1", "100.00", f.depositPayer("co", "sup"));
        FundOutcome overPayable = f.pay("M-2", "1000.00", f.depositPayer("co", "sup"));
        ok("多腿：第一腿已提交，第二腿超应付上限被拒 → 第二腿不留部分款，第一腿仍在",
                first.accepted() && !overPayable.accepted()
                        && EngineeringModel.REASON_PAYMENT_EXCEEDS_PAYABLE
                        .equals(overPayable.reason())
                        && f.deposit("north", "co").compareTo(money("200.00")) == 0
                        && f.payable().compareTo(money("400.00")) == 0
                        && f.bank.command("M-2") == null,
                overPayable.reason() + " co=" + f.deposit("north", "co"));
        FundOutcome insufficient = f.pay("M-3", "300.00", f.depositPayer("co", "sup"));
        ok("多腿：第三腿资金层余额不足 → 整腿无半账，存款/应付/收款方都不动",
                !insufficient.accepted()
                        && f.deposit("north", "co").compareTo(money("200.00")) == 0
                        && f.deposit("north", "sup").compareTo(money("100.00")) == 0
                        && f.payable().compareTo(money("400.00")) == 0,
                insufficient.reason() + " co=" + f.deposit("north", "co"));
        ok("多腿后报表仍平衡、世界现金守恒", f.report().balanced(),
                "assets=" + f.report().assets());
    }

    private static void sameIdBindsAmount() {
        Eng f = new Eng("bind-amount", "0.00", "300.00", "0.00", false, 0, "0.00");
        f.sign("S-1", 0, 0);
        f.invest("W-1", 5);
        f.pay("N-1", "100.00", f.depositPayer("co", "sup"));
        boolean conflict = false;
        try {
            f.pay("N-1", "120.00", f.depositPayer("co", "sup"));
        } catch (IllegalArgumentException expected) {
            conflict = "COMMAND_CONTENT_CONFLICT".equals(expected.getMessage());
        }
        ok("同标识同桶换金额 → COMMAND_CONTENT_CONFLICT，账仍停在 100 那笔",
                conflict && f.deposit("north", "co").compareTo(money("200.00")) == 0
                        && f.payable().compareTo(money("400.00")) == 0,
                "conflict=" + conflict);
    }


    private static void crossBankInsufficient() {
        Eng f = new Eng("cross", "0.00", "100.00", "100.00", false, 0, "0.00");
        f.sign("S-1", 0, 0);
        f.invest("W-1", 5);
        BigDecimal cashBefore = f.cash("co-cash");
        FundOutcome out = f.pay("X-1", "150.00", f.depositPayerIn("north", "co", "sup"));
        ok("跨行不足：北行只有 100、南行 100，付 150 整笔拒绝且不跨桶补足，资产不变",
                !out.accepted()
                        && f.deposit("north", "co").compareTo(money("100.00")) == 0
                        && f.deposit("south", "co").compareTo(money("100.00")) == 0
                        && f.deposit("north", "sup").compareTo(Money.zero()) == 0
                        && f.cash("co-cash").compareTo(cashBefore) == 0
                        && f.payable().compareTo(money("500.00")) == 0,
                out.reason());
        ok("跨行不足后报表仍平衡（世界现金守恒、负债与权益未动）",
                f.report().balanced() && f.report().liabilities().compareTo(money("500.00")) == 0,
                "liab=" + f.report().liabilities());
    }

    private static void ownerMismatch() {
        Eng f = new Eng("owner", "0.00", "300.00", "0.00", false, 0, "0.00");
        f.sign("S-1", 0, 0);
        f.invest("W-1", 5);
        FundOutcome wrong = f.pay("O-1", "100.00", f.depositPayerRaw("north", "sup", "sup"));
        ok("出款桶的真实主体是供应商 → FUND_OWNER_MISMATCH，钱一分不动",
                !wrong.accepted()
                        && EngineeringModel.REASON_FUND_OWNER_MISMATCH.equals(wrong.reason())
                        && f.deposit("north", "co").compareTo(money("300.00")) == 0
                        && f.deposit("north", "sup").compareTo(Money.zero()) == 0,
                wrong.reason());
    }

    private static void untouchedCashApi() {
        Eng f = new Eng("legacy", "500.00", "0.00", "0.00", false, 0, "100.00");
        f.sign("S-1", 0, 0);
        Outcome work = f.invest("W-1", 5);
        Outcome paid = EngineeringModel.paySupplier(f.state, "L-1", "co-owner", money("100.00"),
                f.now, f.accounts);
        f.state = paid.state();
        f.adoptAccounts(paid.accounts());
        ok("旧现金接口行为不变：现金付应付 500→400、公司现金 500→400，命令记录内容仍是 PAY_SUPPLIER",
                work.accepted() && paid.accepted()
                        && EngineeringModel.REASON_SUPPLIER_PAID.equals(paid.reason())
                        && f.cash("co-cash").compareTo(money("400.00")) == 0
                        && f.payable().compareTo(money("400.00")) == 0
                        && f.state.commandContents().get("L-1") != null
                        && f.state.commandContents().get("L-1").startsWith("PAY_SUPPLIER"),
                paid.reason() + " cash=" + f.cash("co-cash"));
    }

    // ------------------------------------------------------------ 制造

    private static void manufacturingChain() {
        Mfg m = new Mfg("chain", "2000.00", "5000.00");
        ManufacturingModel.Outcome order = m.place("PO-1", 20);
        ok("采购下单：只形成承诺，不动现金、不入库",
                order.accepted() && m.cash("co-cash").compareTo(money("2000.00")) == 0
                        && m.state.rawMaterials().quantity() == 0,
                order.reason());
        ManufacturingModel.FundOutcome received = m.receive("PO-1");
        ok("制造收货付款：20×50 = 1000 从公司现金付给供应商，原料按全额入账",
                received.accepted()
                        && ManufacturingModel.REASON_GOODS_RECEIVED.equals(received.reason())
                        && m.cash("co-cash").compareTo(money("1000.00")) == 0
                        && m.cash("sup-cash").compareTo(money("1000.00")) == 0
                        && m.state.rawMaterials().quantity() == 20
                        && m.state.rawMaterials().amount().compareTo(money("1000.00")) == 0,
                received.reason() + " co=" + m.cash("co-cash"));
        ManufacturingModel.FundOutcome started = m.start("B-1", 10);
        ok("投产付薪：人工 300 从公司现金付给人力账户，原料 10 转在制",
                started.accepted() && ManufacturingModel.REASON_PRODUCTION_STARTED
                        .equals(started.reason())
                        && m.cash("co-cash").compareTo(money("700.00")) == 0
                        && m.cash("ops-cash").compareTo(money("350.00")) == 0
                        && m.state.workInProgress().quantity() == 10,
                started.reason() + " co=" + m.cash("co-cash"));
        ManufacturingModel.Outcome finished = m.complete("B-1");
        ManufacturingModel.Outcome signed = m.signContract("K-1", 10);
        ManufacturingModel.Outcome delivered = m.deliver("K-1", 10);
        ok("完工、签约、交付：成品 0、收入 1300、应收 1300（不确认现金）",
                finished.accepted() && signed.accepted() && delivered.accepted()
                        && m.report().revenue().compareTo(money("1300.00")) == 0
                        && m.report().receivableOutstanding().compareTo(money("1300.00")) == 0,
                delivered.reason() + " revenue=" + m.report().revenue());
        ManufacturingModel.FundOutcome collected = m.collect("AR-1");
        ok("应收回款：客户现金 5000→3700、公司现金 700→2000，应收归零、收入不重记",
                collected.accepted()
                        && ManufacturingModel.REASON_PAYMENT_RECEIVED.equals(collected.reason())
                        && m.cash("co-cash").compareTo(money("2000.00")) == 0
                        && m.cash("cust-cash").compareTo(money("3700.00")) == 0
                        && m.report().receivableOutstanding().compareTo(Money.zero()) == 0
                        && m.report().revenue().compareTo(money("1300.00")) == 0,
                collected.reason() + " co=" + m.cash("co-cash"));
        ok("制造报表平衡：资产 = 负债 + 权益、世界现金守恒、初始权益不重设",
                m.report().balanced()
                        && m.state.initialEquity().compareTo(money("3000.00")) == 0,
                "assets=" + m.report().totalAssets() + " equity=" + m.report().equity());
    }

    private static void manufacturingFreezeDerivesAvailable() {
        Mfg m = new Mfg("mfg-freeze", "400.00", "0.00");
        String bucket = new Fund.Cash("co-cash").token();
        FundPayer frozen = m.cashPayer(new CashFreeze(Map.of(bucket, money("300.00"))),
                "co-cash", "ops-cash");
        ManufacturingModel.FundOutcome closed = m.closeWith(0, "BILL-0", frozen);
        ok("期末费用按冻结后的可用额付款：现金 400 冻结 300 → 只付 100，未付 150 成应付",
                closed.accepted() && ManufacturingModel.REASON_PERIOD_CLOSED.equals(closed.reason())
                        && m.cash("co-cash").compareTo(money("300.00")) == 0
                        && m.cash("ops-cash").compareTo(money("150.00")) == 0
                        && m.report().payableOutstanding().compareTo(money("150.00")) == 0
                        && m.report().periodExpense().compareTo(money("250.00")) == 0,
                closed.reason() + " bill=" + m.report().payableOutstanding());
        ManufacturingModel.FundOutcome blocked = m.payBillWith(0, frozen);
        ok("同一冻结下结清账单：可用 100 < 未付 150 → INSUFFICIENT_CASH，现金与账单不变",
                !blocked.accepted()
                        && ManufacturingModel.REASON_INSUFFICIENT_CASH.equals(blocked.reason())
                        && m.cash("co-cash").compareTo(money("300.00")) == 0
                        && m.report().payableOutstanding().compareTo(money("150.00")) == 0,
                blocked.reason());
        ManufacturingModel.FundOutcome paid = m.payBillWith(0,
                m.cashPayer(CashFreeze.none(), "co-cash", "ops-cash"));
        ok("冻结释放后结清：现金 300→150、人力 150→300、应付归零；费用不重复计提",
                paid.accepted()
                        && ManufacturingModel.REASON_EXPENSE_BILL_PAID.equals(paid.reason())
                        && m.cash("co-cash").compareTo(money("150.00")) == 0
                        && m.cash("ops-cash").compareTo(money("300.00")) == 0
                        && m.report().payableOutstanding().compareTo(Money.zero()) == 0
                        && m.report().periodExpense().compareTo(money("250.00")) == 0
                        && m.report().balanced(),
                paid.reason() + " co=" + m.cash("co-cash"));
    }

    private static void manufacturingOrphanFundRecord() {
        Mfg m = new Mfg("mfg-orphan", "200.00", "0.00");
        m.place("PO-1", 2);
        FundPayer.Result lone = m.cashPayer(CashFreeze.none(), "co-cash", "sup-cash")
                .pay("mfg-receive:PO-1", money("100.00"), m.funds());
        m.adopt(lone.funds());
        ManufacturingModel.FundOutcome out = m.receive("PO-1");
        ok("资金层已有该采购单付款记录、业务仍 ORDERED → FUND_ID_ALREADY_RECORDED，不二次付款",
                lone.accepted() && !out.accepted()
                        && ManufacturingModel.REASON_FUND_ID_ALREADY_RECORDED.equals(out.reason())
                        && m.state.purchaseOrders().get("PO-1").status()
                        == ManufacturingModel.PurchaseOrderStatus.ORDERED
                        && m.cash("co-cash").compareTo(money("100.00")) == 0
                        && m.cash("sup-cash").compareTo(money("100.00")) == 0
                        && m.state.rawMaterials().quantity() == 0,
                out.reason() + " co=" + m.cash("co-cash"));
    }

    private static void manufacturingBusinessWithoutFund() {
        Mfg m = new Mfg("mfg-diverge", "100.00", "0.00");
        m.place("PO-1", 2);
        ManufacturingModel.FundOutcome received = m.receive("PO-1");
        ok("真实收货付款：现金 100→0、供应商 0→100、原料 2 入账",
                received.accepted() && m.cash("co-cash").compareTo(Money.zero()) == 0
                        && m.cash("sup-cash").compareTo(money("100.00")) == 0
                        && m.state.rawMaterials().quantity() == 2,
                received.reason());
        ManufacturingModel.State businessOnly = m.state;
        FundState fundsBefore = m.fundsBeforeReceive();
        ManufacturingModel.FundOutcome replay = ManufacturingModel.receivePurchaseOrder(
                businessOnly, m.now + DAY, "co-owner", "PO-1", fundsBefore,
                m.cashPayer(CashFreeze.none(), "co-cash", "sup-cash"));
        ok("业务已收货、资金三态回退成付款前 → FUND_RECORD_DIVERGED（不当已完成重放）",
                !replay.accepted()
                        && ManufacturingModel.REASON_FUND_RECORD_DIVERGED.equals(replay.reason()),
                replay.reason());
    }

    // ------------------------------------------------------------ 断言

    private static void ok(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + name + " :: " + detail);
        } else {
            failed.add(name);
            System.out.println("[FAIL] " + name + " :: " + detail);
        }
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }

    // ------------------------------------------------------------ 工程夹具

    private static final class Eng {
        private final BankingProfile profile;
        private final BankConfig config;
        private final EngineeringModel.Config eng;
        private final String runId;
        private BankState bank;
        private Map<String, CashAccount> accounts;
        private CustodyBook book;
        private EngineeringModel.State state;
        private long position;
        private long now = T0;

        private Eng(String tag, String coCash, String northCo, String southCo, boolean custody,
                    int laborBp, String custDeposit) {
            runId = "biz-" + tag;
            profile = profile(custody);
            config = config(profile, tag);
            eng = new EngineeringModel.Config("co-owner", "co-cash", "cust-cash", "sup-cash",
                    "ops-cash", DAY, laborBp, "b7c-exp");
            BankOpening.Result opened = BankOpening.openDeclared(registry(custody),
                    accounts(coCash, "0.00"), profile, config,
                    endowment(northCo, southCo, custDeposit), runId);
            bank = opened.state();
            accounts = new LinkedHashMap<>(opened.accounts());
            book = CustodyBook.openEmpty(bank, accounts, config);
            state = EngineeringModel.open(eng, accounts, new FundState(accounts, bank, book));
        }

        private BankContext ctx() {
            position++;
            return new BankContext(runId, config.fingerprint(), position, now);
        }

        private FundState funds() {
            return new FundState(accounts, bank, book);
        }

        private void adopt(FundState next) {
            accounts = new LinkedHashMap<>(next.accounts());
            bank = next.bank();
            book = next.book();
        }

        private void adoptAccounts(Map<String, CashAccount> next) {
            accounts = new LinkedHashMap<>(next);
        }

        private void adopt(FundOutcome outcome) {
            state = outcome.state();
            adopt(outcome.funds());
        }

        private void adopt(FundPayer.Result result) {
            adopt(result.funds());
        }

        private Outcome sign(String id, int advanceBp, int retentionBp) {
            Outcome outcome = EngineeringModel.sign(state, id, "co-owner",
                    spec(advanceBp, retentionBp), now, accounts);
            state = outcome.state();
            return outcome;
        }

        private Outcome invest(String id, long units) {
            Outcome outcome = EngineeringModel.investWork(state, id, "co-owner", "C-1", "W-1",
                    units, now, accounts);
            state = outcome.state();
            adoptAccounts(outcome.accounts());
            return outcome;
        }

        private FundOutcome investFunded(String id, long units, FundPayer payer) {
            FundOutcome outcome = EngineeringModel.investWork(state, id, "co-owner", "C-1", "W-1",
                    units, now, funds(), payer);
            adopt(outcome);
            return outcome;
        }

        private FundOutcome pay(String id, String amount, FundPayer payer) {
            FundOutcome outcome = EngineeringModel.paySupplier(state, id, "co-owner",
                    money(amount), now, funds(), payer);
            adopt(outcome);
            return outcome;
        }

        private FundOutcome advance(String id, String amount, FundPayer payer) {
            FundOutcome outcome = EngineeringModel.receiveAdvance(state, id, "co-owner", "C-1",
                    money(amount), now, funds(), payer);
            adopt(outcome);
            return outcome;
        }

        private FundOutcome collectDue(String id, int tranche, String amount, FundPayer payer) {
            FundOutcome outcome = EngineeringModel.collectDue(state, id, "co-owner", "C-1",
                    tranche, money(amount), dueTime(), funds(), payer);
            adopt(outcome);
            return outcome;
        }

        private FundOutcome collectRetention(String id, int tranche, String amount, FundPayer payer) {
            FundOutcome outcome = EngineeringModel.collectRetention(state, id, "co-owner", "C-1",
                    tranche, money(amount), now, funds(), payer);
            adopt(outcome);
            return outcome;
        }

        private FundOutcome collectRetentionAt(String id, int tranche, String amount,
                                               FundPayer payer, long at) {
            FundOutcome outcome = EngineeringModel.collectRetention(state, id, "co-owner", "C-1",
                    tranche, money(amount), at, funds(), payer);
            adopt(outcome);
            return outcome;
        }

        private FundOutcome recover(String id, String amount, FundPayer payer) {
            FundOutcome outcome = EngineeringModel.recover(state, id, "co-owner", "C-1",
                    money(amount), now, funds(), payer);
            adopt(outcome);
            return outcome;
        }

        /** 真实链：开工 5 → 完工 → 验收 → 开票（全程零人工，不动钱）。 */
        private void acceptChain() {
            sign("S-1", 0, 1000);
            invest("W-1", 5);
            Outcome done = EngineeringModel.completeWork(state, "W-2", "co-owner", "W-1",
                    now + 5L * DAY, accounts);
            state = done.state();
            Outcome accepted = EngineeringModel.accept(state, "AC-1", "co-owner", "C-1", 5,
                    "acceptance:real", now + 6L * DAY, accounts);
            state = accepted.state();
            Outcome invoiced = EngineeringModel.invoice(state, "IV-1", "co-owner", "C-1",
                    now + 6L * DAY, accounts);
            state = invoiced.state();
            if (!done.accepted() || !accepted.accepted() || !invoiced.accepted()) {
                throw new IllegalStateException("测试前置链未成立：" + done.reason() + "/"
                        + accepted.reason() + "/" + invoiced.reason());
            }
        }

        private long dueTime() {
            return now + 40L * DAY;
        }

        private PaymentPortFundPayer depositPayer(String fromHolder, String toHolder) {
            return depositPayerRaw("north", fromHolder, toHolder);
        }

        private PaymentPortFundPayer depositPayerIn(String bankKey, String fromHolder,
                                                    String toHolder) {
            return depositPayerRaw(bankKey, fromHolder, toHolder);
        }

        private PaymentPortFundPayer depositPayerRaw(String bankKey, String fromHolder,
                                                     String toHolder) {
            return new PaymentPortFundPayer(config, ctx(), CashFreeze.none(), fromHolder,
                    new Fund.Deposit(bankKey, fromHolder), new Fund.Deposit(bankKey, toHolder));
        }

        private BigDecimal payable() {
            return state.payable();
        }

        private BigDecimal cash(String accountId) {
            CashAccount account = accounts.get(accountId);
            return account == null ? Money.zero() : account.cash();
        }

        private BigDecimal deposit(String bankKey, String holder) {
            return bank.depositOf(bankKey, holder);
        }

        private FundedReport report() {
            return EngineeringModel.fundedReport(state, funds(), ReportContext.none());
        }
    }

    // ------------------------------------------------------------ 制造夹具

    private static final class Mfg {
        private final BankingProfile profile;
        private final BankConfig config;
        private final ManufacturingModel.Opening opening;
        private final String runId;
        private BankState bank;
        private Map<String, CashAccount> accounts;
        private CustodyBook book;
        private ManufacturingModel.State state;
        private FundState fundsBeforeReceive;
        private long position;
        private final long now = T0;

        private Mfg(String tag, String coCash, String custCash) {
            runId = "biz-mfg-" + tag;
            profile = profile(false);
            config = config(profile, "mfg-" + tag);
            opening = new ManufacturingModel.Opening("co-owner", "co-cash", "sup-cash",
                    "cust-cash", "ops-cash");
            BankOpening.Result opened = BankOpening.openDeclared(registry(false),
                    accounts(coCash, custCash), profile, config,
                    endowment("0.00", "0.00", "0.00"), runId);
            bank = opened.state();
            accounts = new LinkedHashMap<>(opened.accounts());
            book = CustodyBook.openEmpty(bank, accounts, config);
            state = ManufacturingModel.open(opening, accounts);
        }

        private BankContext ctx() {
            position++;
            return new BankContext(runId, config.fingerprint(), position, now);
        }

        private FundState funds() {
            return new FundState(accounts, bank, book);
        }

        private void adopt(FundState next) {
            accounts = new LinkedHashMap<>(next.accounts());
            bank = next.bank();
            book = next.book();
        }

        private void adopt(ManufacturingModel.FundOutcome outcome) {
            state = outcome.state();
            adopt(outcome.funds());
        }

        private void adopt(FundPayer.Result result) {
            adopt(result.funds());
        }

        private FundPayer cashPayer(CashFreeze frozen, String from, String to) {
            String payerKey = accounts.get(from).ownerId().equals(opening.companyOwnerId())
                    ? "co" : partyKeyOf(from);
            return new PaymentPortFundPayer(config, ctx(), frozen, payerKey,
                    new Fund.Cash(from), new Fund.Cash(to));
        }

        private String partyKeyOf(String accountId) {
            String owner = accounts.get(accountId).ownerId();
            for (Map.Entry<String, BankingProfile.PartyRef> entry
                    : profile.parties().entrySet()) {
                if (entry.getValue().ownerId().equals(owner)) {
                    return entry.getKey();
                }
            }
            throw new IllegalArgumentException("找不到账户主体：" + accountId);
        }

        private ManufacturingModel.Outcome place(String id, long quantity) {
            ManufacturingModel.Outcome outcome = ManufacturingModel.placePurchaseOrder(state, now,
                    "co-owner", id, quantity, accounts);
            state = outcome.state();
            return outcome;
        }

        private ManufacturingModel.FundOutcome receive(String id) {
            fundsBeforeReceive = funds();
            ManufacturingModel.FundOutcome outcome = ManufacturingModel.receivePurchaseOrder(
                    state, now + DAY, "co-owner", id, funds(),
                    cashPayer(CashFreeze.none(), "co-cash", "sup-cash"));
            adopt(outcome);
            return outcome;
        }

        private FundState fundsBeforeReceive() {
            return fundsBeforeReceive;
        }

        private ManufacturingModel.FundOutcome start(String id, long quantity) {
            ManufacturingModel.FundOutcome outcome = ManufacturingModel.startProduction(
                    state, now + DAY, "co-owner", id, quantity, funds(),
                    cashPayer(CashFreeze.none(), "co-cash", "ops-cash"));
            adopt(outcome);
            return outcome;
        }

        private ManufacturingModel.Outcome complete(String id) {
            ManufacturingModel.Outcome outcome = ManufacturingModel.completeProduction(state,
                    now + 2L * DAY, "co-owner", id, accounts);
            state = outcome.state();
            return outcome;
        }

        private ManufacturingModel.Outcome signContract(String id, long quantity) {
            ManufacturingModel.Outcome outcome = ManufacturingModel.signContract(state,
                    now + 2L * DAY, "co-owner", id, quantity, money("130.00"),
                    now + 3L * DAY, accounts);
            state = outcome.state();
            return outcome;
        }

        private ManufacturingModel.Outcome deliver(String id, long quantity) {
            ManufacturingModel.Outcome outcome = ManufacturingModel.deliver(state,
                    now + 3L * DAY, "co-owner", id, quantity, "AR-1", accounts);
            state = outcome.state();
            return outcome;
        }

        private ManufacturingModel.FundOutcome collect(String id) {
            ManufacturingModel.FundOutcome outcome = ManufacturingModel.collectReceivable(
                    state, now + 18L * DAY, "co-owner", id, funds(),
                    cashPayer(CashFreeze.none(), "cust-cash", "co-cash"));
            adopt(outcome);
            return outcome;
        }

        private ManufacturingModel.FundOutcome closeWith(long period, String billId,
                                                         FundPayer payer) {
            ManufacturingModel.FundOutcome outcome = ManufacturingModel.closePeriod(state,
                    T0 + PERIOD, "co-owner", period, billId, funds(), payer);
            adopt(outcome);
            return outcome;
        }

        private ManufacturingModel.FundOutcome payBillWith(long period, FundPayer payer) {
            ManufacturingModel.FundOutcome outcome = ManufacturingModel.payExpenseBill(state,
                    T0 + PERIOD, "co-owner", period, funds(), payer);
            adopt(outcome);
            return outcome;
        }

        private BigDecimal cash(String accountId) {
            CashAccount account = accounts.get(accountId);
            return account == null ? Money.zero() : account.cash();
        }

        private ManufacturingModel.Report report() {
            return ManufacturingModel.report(state, accounts);
        }
    }

    // ------------------------------------------------------------ 共享档案

    private static ContractSpec spec(int advanceBp, int retentionBp) {
        return new ContractSpec("C-1", money("200.00"), new BigDecimal("100.0000"),
                new Terms(advanceBp, retentionBp, 30, 30, RetentionMode.TIME_ONLY,
                        new long[]{5, 5}));
    }

    private static BankingProfile profile(boolean custody) {
        Map<String, BankingProfile.BankRef> banks = new LinkedHashMap<>();
        banks.put("north", new BankingProfile.BankRef("north", "north-org", "north-owner",
                "rsv-north"));
        banks.put("south", new BankingProfile.BankRef("south", "south-org", "south-owner",
                "rsv-south"));
        Map<String, BankingProfile.PartyRef> parties = new LinkedHashMap<>();
        parties.put("co", new BankingProfile.PartyRef("co-party", "co-owner", "co-cash",
                "dep-co"));
        parties.put("sup", new BankingProfile.PartyRef("sup-party", "sup-owner", "sup-cash",
                "dep-sup"));
        parties.put("cust", new BankingProfile.PartyRef("cust-party", "cust-owner", "cust-cash",
                "dep-cust"));
        parties.put("ops", new BankingProfile.PartyRef("ops-party", "ops-owner", "ops-cash",
                "dep-ops"));
        Map<String, BankingProfile.CustodyRef> custodians = new LinkedHashMap<>();
        List<String> deposits = new ArrayList<>(
                List.of("dep-co", "dep-sup", "dep-cust", "dep-ops"));
        if (custody) {
            custodians.put("cst", new BankingProfile.CustodyRef("cst-org", "cst-owner", "north",
                    "dep-cst", BankRules.CREDIT_NON_ACCRUAL));
            deposits.add("dep-cst");
        }
        return new BankingProfile(banks, parties, custodians, Map.of(), Map.of(),
                List.of("book-none"), List.of("impair-none"), List.copyOf(deposits),
                null, null, null);
    }

    private static BankConfig config(BankingProfile profile, String tag) {
        return new BankConfig.Builder(profile).version(22).archiveId("B7C-" + tag)
                .label("b7c-" + tag).coverageRule("DECLARED_UNIVERSE_V1").build();
    }

    private static Map<String, CashAccount> accounts(String coCash, String custCash) {
        Map<String, CashAccount> map = new LinkedHashMap<>();
        map.put("co-cash", new CashAccount("co-owner", money(coCash), "b7c"));
        map.put("cust-cash", new CashAccount("cust-owner", money(custCash), "b7c"));
        map.put("sup-cash", new CashAccount("sup-owner", money("0.00"), "b7c"));
        map.put("ops-cash", new CashAccount("ops-owner", money("50.00"), "b7c"));
        map.put("rsv-north", new CashAccount("north-owner", Money.zero(), "b7c"));
        map.put("rsv-south", new CashAccount("south-owner", Money.zero(), "b7c"));
        return map;
    }

    private static BankOpening.DeclaredEndowment endowment(String northCo, String southCo,
                                                           String custDeposit) {
        Map<String, BankOpening.BankBalance> banks = new LinkedHashMap<>();
        Map<String, BigDecimal> northGl = new LinkedHashMap<>();
        northGl.put("PREMISES", money("5000.00"));
        if (money(northCo).signum() > 0) {
            northGl.put("dep-co", money(northCo).negate());
        }
        if (money(custDeposit).signum() > 0) {
            northGl.put("dep-cust", money(custDeposit).negate());
        }
        Map<String, BigDecimal> southGl = new LinkedHashMap<>();
        southGl.put("PREMISES", money("5000.00"));
        if (money(southCo).signum() > 0) {
            southGl.put("dep-co", money(southCo).negate());
        }
        banks.put("north", new BankOpening.BankBalance(northGl, "b7c-capital"));
        banks.put("south", new BankOpening.BankBalance(southGl, "b7c-capital"));
        List<BankOpening.DepositEndowment> deposits = new ArrayList<>();
        if (money(northCo).signum() > 0) {
            deposits.add(new BankOpening.DepositEndowment("north", "co", money(northCo), null));
        }
        if (money(southCo).signum() > 0) {
            deposits.add(new BankOpening.DepositEndowment("south", "co", money(southCo), null));
        }
        if (money(custDeposit).signum() > 0) {
            deposits.add(new BankOpening.DepositEndowment("north", "cust",
                    money(custDeposit), null));
        }
        return new BankOpening.DeclaredEndowment(banks, List.of(), deposits, List.of(),
                List.of(), Map.of(), List.of(), "b7c-opening");
    }

    private static OpeningRegistry registry(boolean custody) {
        List<OpeningRegistry.Party> parties = new ArrayList<>();
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>();
        List<String> orgs = new ArrayList<>(List.of("north", "south"));
        if (custody) {
            orgs.add("cst");
        }
        for (String org : orgs) {
            parties.add(new OpeningRegistry.Party(org + "-org",
                    OpeningRegistry.PartyKind.ORGANIZATION, org));
            owners.add(new OpeningRegistry.PropertyOwner(org + "-owner",
                    OpeningRegistry.OwnerKind.ORGANIZATION, org + "-org"));
        }
        for (String person : List.of("co", "sup", "cust", "ops")) {
            parties.add(new OpeningRegistry.Party(person + "-party",
                    OpeningRegistry.PartyKind.PERSON, person));
            owners.add(new OpeningRegistry.PropertyOwner(person + "-owner",
                    OpeningRegistry.OwnerKind.PERSON, person + "-party"));
        }
        return new OpeningRegistry(parties, owners, List.of(), List.of());
    }
}
