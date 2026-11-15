package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.ManufacturingModel.Inventory;
import edu.cufe.auction.world.ManufacturingTemplateModel.Accounts;
import edu.cufe.auction.world.ManufacturingTemplateModel.CertEvidence;
import edu.cufe.auction.world.ManufacturingTemplateModel.CertPlan;
import edu.cufe.auction.world.ManufacturingTemplateModel.CustomerSpec;
import edu.cufe.auction.world.ManufacturingTemplateModel.MaterialQuote;
import edu.cufe.auction.world.ManufacturingTemplateModel.Outcome;
import edu.cufe.auction.world.ManufacturingTemplateModel.ProductSpec;
import edu.cufe.auction.world.ManufacturingTemplateModel.State;
import edu.cufe.auction.world.ManufacturingTemplateModel.TemplateSpec;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.CustodyBook;
import edu.cufe.auction.world.bank.Fund;
import edu.cufe.auction.world.bank.FundState;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模板 v2 自检：无 JUnit 依赖，直接 main 运行。覆盖认证/换代/产能/报价/扩产/
 * 失败残值/坏账/资金腿/幂等/错主体/溢出。退出码 0 表示全部通过。
 */
public final class TemplateSelfCheck {

    private static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;
    private static final long P = ManufacturingTemplateModel.PERIOD_MILLIS;
    private static int checks = 0;

    private TemplateSelfCheck() {
    }

    /** @return 本次 main 已执行的项目数（每次 main 开始重置）。 */
    static int checks() {
        return checks;
    }

    /** @param args 忽略 */
    public static void main(String[] args) {
        checks = 0;
        certifiedFlow();
        certFailureAndNoReroll();
        structureMaterialScale();
        capacityContention();
        quoteFreezeAndBatchCost();
        allocationTail();
        expansionRamp();
        buildFailureAndResidual();
        fundedAndIdempotent();
        wrongOwner();
        overdueRelease();
        badDebtWriteOff();
        overflow();
        System.out.println("TEMPLATE_SELF_CHECK OK checks=" + checks);
        if (checks < 20) {
            throw new IllegalStateException("自检项过少：" + checks);
        }
    }

    // ---------------------------------------------------------------- 例：认证与换代

    private static void certifiedFlow() {
        State s = openA();
        Map<String, CashAccount> cash = cashA();
        Outcome o = ManufacturingTemplateModel.signContract(s, 1, "co", "c0", "c0", "CUS",
                "P1", 1, 10, m("130.00"), 10 * DAY, 20 * DAY, cash);
        check("未认证拒单", "NOT_CERTIFIED", o.reason());
        o = ManufacturingTemplateModel.requestCert(s, 1, "co", "req1", "t1", "CUS", "P1", 1, 1,
                new CertEvidence("ev1", "CUS", "P1", 1, 0, 0, "reader"), cash);
        check("认证申请", "CERT_REQUESTED", o.reason());
        s = o.state();
        cash = o.accounts();
        check("认证费支出", "49800.00", cash.get("CO").cash().toPlainString());
        o = ManufacturingTemplateModel.finishCert(s, 1 + DAY, "co", "fin1", "t1", cash);
        check("认证通过", "CERTIFIED", o.reason());
        s = o.state();
        cash = o.accounts();
        o = ManufacturingTemplateModel.signContract(s, 2 * DAY, "co", "c1", "c1", "CUS", "P1", 1,
                10, m("130.00"), 12 * DAY, 20 * DAY, cash);
        check("认证后接单", "SIGNED", o.reason());
        s = o.state();
        cash = o.accounts();
        o = ManufacturingTemplateModel.requestCert(s, 2 * DAY, "co", "req-again", "t1b", "CUS",
                "P1", 1, 1, new CertEvidence("ev1", "CUS", "P1", 1, 0, 0, "reader"), cash);
        check("同轮不可重抽", "TRIAL_ALREADY_REQUESTED", o.reason());
        o = ManufacturingTemplateModel.signContract(s, 2 * DAY, "co", "c2", "c2", "CUS", "P1", 2,
                5, m("130.00"), 12 * DAY, 20 * DAY, cash);
        check("换代不借旧认证", "NOT_CERTIFIED", o.reason());
        balanced("认证流", s, cash);
    }

    private static void certFailureAndNoReroll() {
        State s = openA();
        Map<String, CashAccount> cash = cashA();
        Outcome o = ManufacturingTemplateModel.requestCert(s, 1, "co", "reqf", "tf", "CUS2", "P1",
                1, 1, new CertEvidence("ev2", "CUS2", "P1", 1, 0, 0, "reader"), cash);
        check("失败轮申请", "CERT_REQUESTED", o.reason());
        s = o.state();
        cash = o.accounts();
        o = ManufacturingTemplateModel.finishCert(s, 1 + DAY, "co", "finf", "tf", cash);
        check("失败轮结果", "CERT_FAILED", o.reason());
        s = o.state();
        cash = o.accounts();
        o = ManufacturingTemplateModel.signContract(s, 2 * DAY, "co", "cf", "cf", "CUS2", "P1", 1,
                5, m("130.00"), 10 * DAY, 20 * DAY, cash);
        check("失败后不能签约", "NOT_CERTIFIED", o.reason());
        o = ManufacturingTemplateModel.finishCert(s, 2 * DAY, "co", "finf2", "tf", cash);
        check("同一试验不可重复完成", "TRIAL_ALREADY_REQUESTED", o.reason());
        balanced("认证失败", s, cash);
    }

    // ---------------------------------------------------------------- 模板B：供应/预算约束

    private static void structureMaterialScale() {
        State s = ManufacturingTemplateModel.open(specB(), accountsA(), cashA());
        Map<String, CashAccount> cash = cashA();
        Outcome o = ManufacturingTemplateModel.orderMaterial(s, 1, "co", "b-ord1", "b1", "M1",
                7, cash);
        check("B供应商容量约束", "SUPPLIER_CAPACITY_EXCEEDED", o.reason());
        s = orderReceive(s, cash, "b2", "M1", 6, 1);
        cash = lastCash;
        o = ManufacturingTemplateModel.startBatch(s, 1 + DAY + 1, "co", "bb1", "bb1", "P1", 1,
                3, cash);
        check("B按BOM耗料", "PRODUCTION_STARTED", o.reason());
        s = o.state();
        cash = o.accounts();
        o = ManufacturingTemplateModel.startBatch(s, 1 + DAY + 2, "co", "bb2", "bb2", "P2", 1,
                1, cash);
        check("B物料不足", "MATERIAL_SHORT", o.reason());
        o = ManufacturingTemplateModel.signContract(s, 1 + DAY + 3, "co", "b-c1", "b-c1", "CUS",
                "P1", 1, 8, m("130.00"), 5 * DAY, 20 * DAY, cash);
        check("B预算约束", "BUDGET_EXCEEDED", o.reason());
        o = ManufacturingTemplateModel.signContract(s, 1 + DAY + 3, "co", "b-c2", "b-c2", "CUS",
                "P1", 1, 7, m("130.00"), 5 * DAY, 20 * DAY, cash);
        check("B预算内可签", "SIGNED", o.reason());
        balanced("模板B", o.state(), o.accounts());
    }

    // ---------------------------------------------------------------- 产能竞争

    private static void capacityContention() {
        State s = openA();
        Map<String, CashAccount> cash = cashA();
        s = certify(s, cash, "CUS", "P1", 1, "tc1");
        cash = lastCash;
        long t = 2 * DAY;
        s = orderReceive(s, cash, "po1", "M1", 80, t);
        cash = lastCash;
        long start = t + DAY + 1;
        Outcome o = ManufacturingTemplateModel.startBatch(s, start, "co", "b1", "b1", "P1", 1,
                10, cash);
        check("第一产品投10", "PRODUCTION_STARTED", o.reason());
        s = o.state();
        cash = o.accounts();
        o = ManufacturingTemplateModel.startBatch(s, start, "co", "b2", "b2", "P1", 1, 10, cash);
        check("同产品再投10", "PRODUCTION_STARTED", o.reason());
        s = o.state();
        cash = o.accounts();
        o = ManufacturingTemplateModel.startBatch(s, start + 1, "co", "b3", "b3", "P1", 1, 5, cash);
        check("超产能拒绝", "CAPACITY_EXCEEDED", o.reason());
        long done = start + 10 * DAY;
        o = ManufacturingTemplateModel.completeBatch(s, done, "co", "done1", "b1", cash);
        check("完工", "PRODUCTION_COMPLETED", o.reason());
        s = o.state();
        cash = o.accounts();
        o = ManufacturingTemplateModel.completeBatch(s, done, "co", "done1b", "b1", cash);
        check("重复完工不增产", "BATCH_STATE_INVALID", o.reason());
        o = ManufacturingTemplateModel.startBatch(s, done, "co", "b4", "b4", "P1", 1, 5, cash);
        check("完工不释放当期间", "CAPACITY_EXCEEDED", o.reason());
        balanced("产能竞争", s, cash);
    }

    // ---------------------------------------------------------------- 报价与批次成本

    private static void quoteFreezeAndBatchCost() {
        State s = openA();
        Map<String, CashAccount> cash = cashA();
        s = certify(s, cash, "CUS", "P1", 1, "tq");
        cash = lastCash;
        long t = 2 * DAY;
        s = orderReceive(s, cash, "po50", "M1", 20, t);
        cash = lastCash;
        check("未来报价不屏蔽当前", "50.00",
                s.purchases().get("po50").unitPrice().toPlainString());
        long start = t + DAY + 1;
        Outcome o = ManufacturingTemplateModel.startBatch(s, start, "co", "q1", "q1", "P1", 1, 10,
                cash);
        check("旧价投产", "PRODUCTION_STARTED", o.reason());
        s = o.state();
        cash = o.accounts();
        check("旧批次材料成本", "1000.00", s.batches().get("q1").materialCost().toPlainString());
        o = ManufacturingTemplateModel.closePeriod(s, 30 * DAY, "co", "cl0", 0, cash);
        check("关期0", "PERIOD_CLOSED", o.reason());
        s = o.state();
        cash = o.accounts();
        s = orderReceive(s, cash, "po60", "M1", 20, 31 * DAY);
        cash = lastCash;
        check("新报价生效", "60.00", s.purchases().get("po60").unitPrice().toPlainString());
        o = ManufacturingTemplateModel.startBatch(s, 32 * DAY + 1, "co", "q2", "q2", "P1", 1, 10,
                cash);
        check("新价投产", "PRODUCTION_STARTED", o.reason());
        s = o.state();
        cash = o.accounts();
        check("新批次材料成本", "1200.00", s.batches().get("q2").materialCost().toPlainString());
        check("历史批次不变", "1000.00", s.batches().get("q1").materialCost().toPlainString());
        balanced("报价冻结", s, cash);
    }

    private static void allocationTail() {
        Inventory a = new Inventory(3, m("100.00"));
        BigDecimal first = ManufacturingModel.allocate(a, 1);
        BigDecimal second = ManufacturingModel.allocate(new Inventory(2, m("66.67")), 1);
        BigDecimal last = ManufacturingModel.allocate(new Inventory(1, m("33.33")), 1);
        check("分配1", "33.33", first.toPlainString());
        check("分配2", "33.34", second.toPlainString());
        check("末批结尽", "33.33", last.toPlainString());
        check("尾差合计", "100.00",
                first.add(second).add(last).toPlainString());
    }

    // ---------------------------------------------------------------- 扩产 ramp

    private static void expansionRamp() {
        State s = openA();
        Map<String, CashAccount> cash = cashA();
        Outcome o = ManufacturingTemplateModel.startExpansion(s, 1, "co", "ex1", "e1",
                m("3000.00"), 60 * DAY, 2, 40, cash);
        check("扩产开工", "BUILDING", o.reason());
        s = o.state();
        cash = o.accounts();
        check("CIP不入费用", "3000.00",
                ManufacturingTemplateModel.report(s, cash).cip().toPlainString());
        s = close(s, cash, 0, 30 * DAY);
        cash = lastCash;
        check("建期无折旧", "0.00",
                ManufacturingTemplateModel.report(s, cash).depreciation().toPlainString());
        s = close(s, cash, 1, 60 * DAY);
        cash = lastCash;
        o = ManufacturingTemplateModel.completeExpansion(s, 63 * DAY, "co", "ex1d", "e1", false,
                cash);
        check("扩产建成", "ONLINE", o.reason());
        s = o.state();
        cash = o.accounts();
        s = orderReceive(s, cash, "poe", "M1", 80, 64 * DAY);
        cash = lastCash;
        o = ManufacturingTemplateModel.startBatch(s, 65 * DAY + 1, "co", "eb1", "eb1", "P1", 1, 25,
                cash);
        check("ramp首期不追溯", "CAPACITY_EXCEEDED", o.reason());
        s = close(s, cash, 2, 90 * DAY);
        cash = lastCash;
        o = ManufacturingTemplateModel.startBatch(s, 91 * DAY, "co", "eb2", "eb2", "P1", 1, 20, cash);
        check("ramp第1期+20", "PRODUCTION_STARTED", o.reason());
        s = o.state();
        cash = o.accounts();
        s = close(s, cash, 3, 120 * DAY);
        cash = lastCash;
        s = orderReceive(s, cash, "poe2", "M1", 40, 121 * DAY);
        cash = lastCash;
        o = ManufacturingTemplateModel.startBatch(s, 122 * DAY + 1, "co", "eb3", "eb3", "P1", 1,
                20, cash);
        check("ramp满产", "PRODUCTION_STARTED", o.reason());
        s = o.state();
        cash = o.accounts();
        balanced("扩产", s, cash);
    }

    // ---------------------------------------------------------------- 失败与残值

    private static void buildFailureAndResidual() {
        State poor = openA("100.00");
        Map<String, CashAccount> cashPoor = cashA("100.00");
        Outcome o = ManufacturingTemplateModel.startExpansion(poor, 1, "co", "fx", "f1",
                m("2000.00"), 20 * DAY, 1, 30, cashPoor);
        check("失败项目开工", "BUILDING", o.reason());
        poor = o.state();
        cashPoor = o.accounts();
        o = ManufacturingTemplateModel.completeExpansion(poor, 21 * DAY, "co", "fxd", "f1", true,
                cashPoor);
        check("建设失败", "BUILD_FAILED", o.reason());
        poor = o.state();
        cashPoor = o.accounts();
        check("残值资产", "500.00", poor.residualAssets().get("f1").toPlainString());
        check("失败不发现金", "48000.00", cashPoor.get("CO").cash().toPlainString());
        o = ManufacturingTemplateModel.sellResidual(poor, 22 * DAY, "co", "sell1", "f1",
                m("500.00"), cashPoor);
        check("买方缺钱保留残值", "INSUFFICIENT_CASH", o.reason());
        check("残值仍在", "500.00", poor.residualAssets().get("f1").toPlainString());
        balanced("失败残值", poor, cashPoor);

        State rich = openA("5000.00");
        Map<String, CashAccount> cashRich = cashA("5000.00");
        o = ManufacturingTemplateModel.startExpansion(rich, 1, "co", "gx", "g1", m("2000.00"),
                20 * DAY, 1, 30, cashRich);
        rich = o.state();
        cashRich = o.accounts();
        o = ManufacturingTemplateModel.completeExpansion(rich, 21 * DAY, "co", "gxd", "g1", true,
                cashRich);
        rich = o.state();
        cashRich = o.accounts();
        o = ManufacturingTemplateModel.sellResidual(rich, 22 * DAY, "co", "sell2", "g1",
                m("500.00"), cashRich);
        check("真实买方处置", "RESIDUAL_SOLD", o.reason());
        rich = o.state();
        cashRich = o.accounts();
        check("处置后无残值", 0, rich.residualAssets().size());
        balanced("残值处置", rich, cashRich);
    }

    // ---------------------------------------------------------------- 资金腿

    private static void fundedAndIdempotent() {
        State s = openA();
        Map<String, CashAccount> cash = cashA();
        TestPayer payer = new TestPayer("CO", "CERT", Map.of());
        FundState funds = new FundState(cash, BankState.empty(), CustodyBook.empty());
        ManufacturingTemplateModel.FundOutcome f = ManufacturingTemplateModel.requestCert(s, 1,
                "co", "freq", "ft", "CUS", "P1", 1, 1,
                new CertEvidence("ev", "CUS", "P1", 1, 0, 0, "reader"), funds, payer);
        check("资金腿认证", "CERT_REQUESTED", f.reason());
        check("资金腿扣款", "49800.00", f.funds().cash("CO").toPlainString());
        ManufacturingTemplateModel.FundOutcome again = ManufacturingTemplateModel.requestCert(
                f.state(), 1, "co", "freq", "ft", "CUS", "P1", 1, 1,
                new CertEvidence("ev", "CUS", "P1", 1, 0, 0, "reader"), f.funds(), payer);
        check("同命令重放", true, again.reason().startsWith("REPLAYED"));
        check("重放不再扣款", "49800.00", again.funds().cash("CO").toPlainString());
        boolean conflict = false;
        try {
            ManufacturingTemplateModel.requestCert(f.state(), 1, "co", "freq", "ft2", "CUS",
                    "P1", 1, 1, new CertEvidence("ev", "CUS", "P1", 1, 0, 0, "reader"),
                    f.funds(), payer);
        } catch (IllegalArgumentException expected) {
            conflict = true;
        }
        check("换内容拒绝", true, conflict);
        TestPayer frozen = new TestPayer("CO", "CERT", Map.of("CO", m("49800.00")));
        ManufacturingTemplateModel.FundOutcome blocked = ManufacturingTemplateModel.requestCert(
                f.state(), 2, "co", "freq2", "ft2", "CUS", "P1", 1, 2,
                new CertEvidence("ev2", "CUS", "P1", 1, 0, 0, "reader"), f.funds(), frozen);
        check("冻结后不足", "INSUFFICIENT_CASH", blocked.reason());
        check("冻结不扣款", "49800.00", blocked.funds().cash("CO").toPlainString());
    }

    private static void wrongOwner() {
        State s = openA();
        Map<String, CashAccount> cash = cashA();
        Outcome o = ManufacturingTemplateModel.signContract(s, 1, "intruder", "x", "x", "CUS",
                "P1", 1, 1, m("130.00"), 10 * DAY, 20 * DAY, cash);
        check("错主体拒绝", "NOT_AUTHORIZED", o.reason());
    }

    // ---------------------------------------------------------------- 逾期与坏账

    private static void overdueRelease() {
        State s = openC();
        Map<String, CashAccount> cash = cashC();
        Outcome o = ManufacturingTemplateModel.signContract(s, 1, "co", "oc", "oc", "CUS",
                "MAT", 1, 10, m("130.00"), 10 * DAY, 20 * DAY, cash);
        check("成熟品免认证接单", "SIGNED", o.reason());
        s = o.state();
        cash = o.accounts();
        o = ManufacturingTemplateModel.signContract(s, 1, "co", "oc-new", "oc-new", "CUS2",
                "NEW", 1, 5, m("130.00"), 10 * DAY, 20 * DAY, cash);
        check("新用途需求窗口外拒单", "DELIVERY_WINDOW", o.reason());
        o = ManufacturingTemplateModel.markOverdue(s, 21 * DAY, "co", "ov", "oc", cash);
        check("逾期释放", "OVERDUE_MARKED", o.reason());
        s = o.state();
        cash = o.accounts();
        check("释放标记", true, s.contracts().get("oc").released());
        o = ManufacturingTemplateModel.deliver(s, 22 * DAY, "co", "dv", "oc", 10, "r0", cash);
        check("逾期不得交付", "CONTRACT_OVERDUE", o.reason());
        o = ManufacturingTemplateModel.markOverdue(s, 22 * DAY, "co", "ov2", "oc", cash);
        check("不重复释放", "CONTRACT_STATE_INVALID", o.reason());
        o = ManufacturingTemplateModel.signContract(s, 22 * DAY, "co", "oc2", "oc2", "CUS",
                "MAT", 1, 10, m("130.00"), 25 * DAY, 29 * DAY, cash);
        check("释放后可再接单", "SIGNED", o.reason());
    }

    private static void badDebtWriteOff() {
        State s = openC();
        Map<String, CashAccount> cash = cashC();
        Outcome o = ManufacturingTemplateModel.signContract(s, 1, "co", "bc", "bc", "CUS",
                "MAT", 1, 10, m("130.00"), 5 * DAY, 20 * DAY, cash);
        if (!o.accepted()) {
            throw new IllegalStateException("坏账例签约失败：" + o.reason());
        }
        s = o.state();
        cash = o.accounts();
        s = orderReceive(s, cash, "bm", "M1", 10, 1);
        cash = lastCash;
        o = ManufacturingTemplateModel.startBatch(s, 1 + DAY + 1, "co", "bb", "bb", "MAT", 1, 10,
                cash);
        s = o.state();
        cash = o.accounts();
        o = ManufacturingTemplateModel.completeBatch(s, 1 + DAY + 1 + 10 * DAY, "co", "cbb", "bb",
                cash);
        s = o.state();
        cash = o.accounts();
        o = ManufacturingTemplateModel.deliver(s, 20 * DAY, "co", "dv2", "bc", 10, "rb", cash);
        check("交付确认", "DELIVERED", o.reason());
        s = o.state();
        cash = o.accounts();
        s = close(s, cash, 0, 30 * DAY);
        cash = lastCash;
        o = ManufacturingTemplateModel.writeOffReceivable(s, 40 * DAY, "co", "wo1", "rb", cash);
        check("宽限期内不核销", "BAD_DEBT_NOT_DUE", o.reason());
        o = ManufacturingTemplateModel.writeOffReceivable(s, 51 * DAY, "co", "wo2", "rb", cash);
        check("坏账核销", "WRITTEN_OFF", o.reason());
        s = o.state();
        cash = o.accounts();
        check("坏账损失", "1300.00",
                ManufacturingTemplateModel.report(s, cash).badDebtLoss().toPlainString());
        balanced("坏账", s, cash);
    }

    private static void overflow() {
        State s = openA();
        Map<String, CashAccount> cash = cashA();
        s = certify(s, cash, "CUS", "P1", 1, "to");
        cash = lastCash;
        long t = 2 * DAY;
        s = orderReceive(s, cash, "poo", "M1", 100, t);
        cash = lastCash;
        long start = t + DAY + 1;
        Outcome first = ManufacturingTemplateModel.startBatch(s, start, "co", "ob1", "ob1",
                "P1", 1, 10, cash);
        s = first.state();
        cash = first.accounts();
        Outcome second = ManufacturingTemplateModel.startBatch(s, start, "co", "ob2", "ob2",
                "P1", 1, 10, cash);
        s = second.state();
        cash = second.accounts();
        Outcome huge = ManufacturingTemplateModel.startBatch(s, start + 1, "co", "ob3", "ob3",
                "P1", 1, Long.MAX_VALUE, cash);
        check("巨量受控拒绝", "CAPACITY_EXCEEDED", huge.reason());
    }

    // ---------------------------------------------------------------- 夹具

    private static Map<String, CashAccount> lastCash;

    private static Map<String, CashAccount> cashA() {
        return cashA("0.00");
    }

    private static Map<String, CashAccount> cashA(String buyer) {
        Map<String, CashAccount> cash = new LinkedHashMap<>();
        cash.put("CO", new CashAccount("co", m("50000.00"), "EXP"));
        cash.put("OPS", new CashAccount("ops", m("5000.00"), "EXP"));
        cash.put("CERT", new CashAccount("certifier", m("0.00"), "EXP"));
        cash.put("CON", new CashAccount("contractor", m("0.00"), "EXP"));
        cash.put("BUY", new CashAccount("buyer", m(buyer), "EXP"));
        cash.put("CUS", new CashAccount("customer", m("100000.00"), "EXP"));
        cash.put("CUS2", new CashAccount("customer2", m("50000.00"), "EXP"));
        cash.put("SUP", new CashAccount("supplier", m("0.00"), "EXP"));
        return cash;
    }

    private static Map<String, CashAccount> cashC() {
        Map<String, CashAccount> cash = cashA();
        cash.put("CUS", new CashAccount("customer", m("100000.00"), "EXP"));
        return cash;
    }

    private static Accounts accountsA() {
        return new Accounts("co", "CO", "OPS", "CERT", "CON", "BUY",
                Map.of("CUS", "CUS", "CUS2", "CUS2"), Map.of("SUP", "SUP"));
    }

    private static TemplateSpec specA() {
        return new TemplateSpec("cert-tech-ramp-v1",
                List.of(new ProductSpec("P1", 1, Map.of("M1", 2L), m("30.00"), 10 * DAY, true),
                        new ProductSpec("P1", 2, Map.of("M1", 2L, "M2", 1L), m("40.00"),
                                10 * DAY, true)),
                List.of(new CustomerSpec("CUS", 50, null, m("20000.00")),
                        new CustomerSpec("CUS2", 50, null, m("20000.00"))),
                List.of(new MaterialQuote("M1", "SUP", 0, 0, 10 * P, m("50.00"), 100),
                        new MaterialQuote("M1", "SUP", 30 * DAY, 30 * DAY, 10 * P, m("60.00"), 100),
                        new MaterialQuote("M2", "SUP", 0, 0, 10 * P, m("80.00"), 100)),
                List.of(new CertPlan("CUS", "P1", 1, 1, true, 0),
                        new CertPlan("CUS", "P1", 1, 2, false, 0),
                        new CertPlan("CUS", "P1", 2, 1, true, 0),
                        new CertPlan("CUS2", "P1", 1, 1, false, 0)),
                20, m("130.00"), m("250.00"), m("100.00"), m("200.00"), DAY, 90 * DAY,
                m("0.25"), 15 * DAY);
    }

    private static TemplateSpec specB() {
        return new TemplateSpec("structure-material-scale-v1",
                List.of(new ProductSpec("P1", 1, Map.of("M1", 2L), m("10.00"), 10 * DAY, false),
                        new ProductSpec("P2", 1, Map.of("M1", 1L), m("5.00"), 10 * DAY, false)),
                List.of(new CustomerSpec("CUS", 100, null, m("1000.00")),
                        new CustomerSpec("CUS2", 100, null, m("1000.00"))),
                List.of(new MaterialQuote("M1", "SUP", 0, 0, 10 * P, m("50.00"), 6)),
                List.of(),
                100, m("130.00"), m("250.00"), m("100.00"), m("0.00"), 0L, 0L,
                m("0.25"), 15 * DAY);
    }

    private static TemplateSpec specC() {
        return new TemplateSpec("dual-product-legacy-new-v1",
                List.of(new ProductSpec("MAT", 1, Map.of("M1", 1L), m("5.00"), 10 * DAY, false),
                        new ProductSpec("NEW", 1, Map.of("M1", 2L), m("40.00"), 10 * DAY, true)),
                List.of(new CustomerSpec("CUS", 100, null, m("20000.00")),
                        new CustomerSpec("CUS2", 20, Set.of(2L, 3L), m("5000.00"))),
                List.of(new MaterialQuote("M1", "SUP", 0, 0, 10 * P, m("50.00"), 100)),
                List.of(new CertPlan("CUS2", "NEW", 1, 1, true, 60 * DAY)),
                30, m("130.00"), m("250.00"), m("100.00"), m("200.00"), DAY, 90 * DAY,
                m("0.25"), 15 * DAY);
    }

    private static State openA() {
        return openA("0.00");
    }

    private static State openA(String buyer) {
        return ManufacturingTemplateModel.open(specA(), accountsA(), cashA(buyer));
    }

    private static State openC() {
        Accounts accounts = new Accounts("co", "CO", "OPS", "CERT", "CON", "BUY",
                Map.of("CUS", "CUS", "CUS2", "CUS2"), Map.of("SUP", "SUP"));
        return ManufacturingTemplateModel.open(specC(), accounts, cashC());
    }

    private static final Map<String, Map<String, CashAccount>> LAST = new LinkedHashMap<>();

    private static State certify(State state, Map<String, CashAccount> cash, String customer,
                                 String product, int generation, String trial) {
        Outcome request = ManufacturingTemplateModel.requestCert(state, 1, "co", "req-" + trial,
                trial, customer, product, generation, 1,
                new CertEvidence("ev-" + trial, customer, product, generation, 0, 0, "reader"),
                cash);
        check("认证申请-" + trial, "CERT_REQUESTED", request.reason());
        Outcome finish = ManufacturingTemplateModel.finishCert(request.state(), 1 + DAY, "co",
                "fin-" + trial, trial, request.accounts());
        check("认证通过-" + trial, "CERTIFIED", finish.reason());
        LAST.put("cash", finish.accounts());
        lastCash = finish.accounts();
        return finish.state();
    }

    private static State orderReceive(State state, Map<String, CashAccount> cash, String po,
                                      String material, long qty, long now) {
        Outcome order = ManufacturingTemplateModel.orderMaterial(state, now, "co", "ord-" + po,
                po, material, qty, cash);
        if (!order.accepted()) {
            throw new IllegalStateException("下单失败：" + po + " " + order.reason());
        }
        Outcome receive = ManufacturingTemplateModel.receiveMaterial(order.state(), now + DAY,
                "co", "rec-" + po, po, order.accounts());
        if (!receive.accepted()) {
            throw new IllegalStateException("到货失败：" + po + " " + receive.reason());
        }
        lastCash = receive.accounts();
        return receive.state();
    }

    private static State close(State state, Map<String, CashAccount> cash, long period, long now) {
        Outcome o = ManufacturingTemplateModel.closePeriod(state, now, "co", "cl" + period,
                period, cash);
        if (!o.accepted()) {
            throw new IllegalStateException("关期失败：" + period + " " + o.reason());
        }
        lastCash = o.accounts();
        return o.state();
    }

    private static State replenish(State state, Map<String, CashAccount> cash) {
        Map<String, CashAccount> next = new LinkedHashMap<>(cash);
        next.put("BUY", new CashAccount("buyer", m("5000.00"), "EXP"));
        return state;
    }

    private static void balanced(String tag, State state, Map<String, CashAccount> cash) {
        ManufacturingTemplateModel.Report report = ManufacturingTemplateModel.validate(state, cash);
        check("平衡-" + tag, true, report.balanced());
    }

    private static void check(String label, Object expected, Object actual) {
        checks++;
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new IllegalStateException("[FAIL] " + label + " 期望 " + expected
                    + " 实际 " + actual);
        }
        System.out.println("[OK] " + label + " = " + actual);
    }

    private static BigDecimal m(String value) {
        return new BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 测试用资金腿：现金桶之间付款，冻结按桶扣减。
     */
    private static final class TestPayer implements FundPayer {

        private final String src;
        private final String dst;
        private final Map<String, BigDecimal> frozen;
        private final Set<String> records = new java.util.LinkedHashSet<>();
        private final Set<String> accepted = new java.util.LinkedHashSet<>();

        private TestPayer(String src, String dst, Map<String, BigDecimal> frozen) {
            this.src = src;
            this.dst = dst;
            this.frozen = frozen;
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
        public FundPayer.Result pay(String commandId, BigDecimal amount, FundState funds) {
            if (records.contains(commandId)) {
                return new FundPayer.Result(false, "FUND_ID_ALREADY_RECORDED", funds);
            }
            if (available(funds).compareTo(amount) < 0) {
                records.add(commandId);
                return new FundPayer.Result(false, "INSUFFICIENT_CASH", funds);
            }
            Map<String, CashAccount> next = new LinkedHashMap<>(funds.accounts());
            CashAccount from = next.get(src);
            CashAccount to = next.get(dst);
            next.put(src, new CashAccount(from.ownerId(), from.cash().subtract(amount),
                    from.source()));
            next.put(dst, new CashAccount(to.ownerId(), to.cash().add(amount), to.source()));
            records.add(commandId);
            accepted.add(commandId);
            return new FundPayer.Result(true, "TRANSFERRED",
                    new FundState(next, funds.bank(), funds.book()));
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
            BigDecimal cash = funds.cash(src);
            BigDecimal hold = frozen.getOrDefault(src, m("0.00"));
            return cash.subtract(hold).max(m("0.00"));
        }
    }
}
