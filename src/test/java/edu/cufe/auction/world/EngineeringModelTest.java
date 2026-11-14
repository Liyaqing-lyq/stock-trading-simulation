package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.EngineeringModel.Claim;
import edu.cufe.auction.world.EngineeringModel.Config;
import edu.cufe.auction.world.EngineeringModel.Contract;
import edu.cufe.auction.world.EngineeringModel.ContractSpec;
import edu.cufe.auction.world.EngineeringModel.Outcome;
import edu.cufe.auction.world.EngineeringModel.Report;
import edu.cufe.auction.world.EngineeringModel.RetentionMode;
import edu.cufe.auction.world.EngineeringModel.State;
import edu.cufe.auction.world.EngineeringModel.Terms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5a 工程回款纯核：与独立 Decimal 数例 A/B/B′/C 逐步对账，并覆盖冻结条款与失败边界。
 *
 * <p>期望值来自 `company-extension-closure-review/recompute-v6.py` 的独立 Decimal 复算，
 * 不是把实现自己的输出抄回来：每个断言的数字都能在复算表对应步骤找到。</p>
 */
class EngineeringModelTest {

    private static final BigDecimal ZERO_BD = new BigDecimal("0.00");

    private static final long DAY = EngineeringModel.DAY_MILLIS;
    private static final Config CONFIG = new Config("company", "CO", "CUS", "SUP", "OPS",
            DAY, 6000, "实验参数：标准工日、人工比例，非行业事实");

    // ------------------------------------------------------------------ 独立数例

    @Test
    @DisplayName("A：预收 20%/质保 10%，两阶段履约，终局收入 12000、权益 11000、质保到期收回")
    void exampleA() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        r.sign("c-sign", "A-1", "120.00", "60.00", terms(2000, 1000, 15, 60, 50, 50));
        assertEquals("0.00", r.report().contractLiability().toPlainString());
        assertEquals("0.00", r.report().obligation().toPlainString());
        r.advance("c-adv", "A-1", "2400.00");
        assertEquals("2400.00", r.report().contractLiability().toPlainString());
        assertEquals("7400.00", r.report().companyCash().toPlainString());

        r.work("w-1", "A-1", 50, 0);
        r.complete("w-1", 50 * DAY);
        r.accept("a-1", "A-1", 50, "客户验收单-1", 50 * DAY);
        assertEquals("6000.00", r.report().revenue().toPlainString());
        assertEquals("3600.00", r.report().contractAsset().toPlainString());
        assertEquals("0.00", r.report().contractLiability().toPlainString(),
                "验收时已冲减该期预收");
        assertEquals("3600.00", r.report().obligation().toPlainString());

        r.invoice("i-1", "A-1", 50 * DAY);
        assertEquals("2400.00", r.report().arCurrent().toPlainString());
        assertEquals("1200.00", r.report().arRetention().toPlainString());
        assertEquals("3600.00", r.report().obligation().toPlainString());

        r.collectDue("d-1", "A-1", 0, "2400.00", 50 * DAY + 15 * DAY);
        assertEquals("0.00", r.report().arCurrent().toPlainString());

        r.work("w-2", "A-1", 50, 50 * DAY);
        r.complete("w-2", 100 * DAY);
        r.accept("a-2", "A-1", 50, "客户验收单-2", 100 * DAY);
        r.invoice("i-2", "A-1", 100 * DAY);
        r.collectDue("d-2", "A-1", 1, "6000.00", 100 * DAY + 15 * DAY);
        r.paySupplier("p-1", "2400.00");
        r.collectRetention("r-1", "A-1", 0, "1200.00", 100 * DAY + 60 * DAY);

        Report report = r.report();
        assertEquals("12000.00", report.revenue().toPlainString());
        assertEquals("6000.00", report.cogs().toPlainString());
        assertEquals("6000.00", report.profit().toPlainString());
        assertEquals("11000.00", report.equity().toPlainString());
        assertEquals("11000.00", report.companyCash().toPlainString());
        assertEquals("35000.00", report.worldCash().toPlainString());
        assertEquals("0.00", report.arGross().toPlainString());
        assertEquals("0.00", report.memo().toPlainString());
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("B：减值/核销不解除客户义务，未到期质保留在应收毛额，真实回收 150，终局义务 1450")
    void exampleB() {
        Result r = bPath();
        Report report = r.report();
        assertEquals("600.00", report.impairmentLoss().toPlainString());
        assertEquals("150.00", report.recoveryIncome().toPlainString());
        assertEquals("3600.00", report.terminationLoss().toPlainString());
        assertEquals("-2450.00", report.profit().toPlainString());
        assertEquals("2550.00", report.equity().toPlainString());
        assertEquals("1550.00", report.companyCash().toPlainString());
        assertEquals("1000.00", report.arRetention().toPlainString(), "未到期质保不得被搬走");
        assertEquals("1000.00", report.arGross().toPlainString());
        assertEquals("450.00", report.memo().toPlainString());
        assertEquals("1450.00", report.obligation().toPlainString(), "客户义务未被核销解除");
        assertEquals("25000.00", report.worldCash().toPlainString());
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("B′：显式豁免 1450 = 备查 450（无损益）+ 应收账面 1000（损失 1000）")
    void exampleBWaived() {
        Result r = bPath();
        r.waive("wv-1", "B-1", "1450.00", "董事会批准放弃剩余债权");
        Report report = r.report();
        assertEquals("1000.00", report.waiverLoss().toPlainString());
        assertEquals("-3450.00", report.profit().toPlainString());
        assertEquals("1550.00", report.equity().toPlainString());
        assertEquals("1550.00", report.companyCash().toPlainString());
        assertEquals("0.00", report.arGross().toPlainString());
        assertEquals("0.00", report.memo().toPlainString());
        assertEquals("0.00", report.obligation().toPlainString());
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("C：999.90 小额尾差；首期被预收全覆盖，开票无条件 0、不产生负应收")
    void exampleCSmallRounding() {
        Config config = new Config("company", "CO", "CUS", "SUP", "OPS", DAY, 3333,
                "小额尾差实验参数");
        Result r = new Result(opening("1000.00", "5000.00"), config);
        r.sign("c-sign", "C-1", "33.33", "19.99", terms(3333, 777, 15, 60, 7, 11, 12));
        r.advance("c-adv", "C-1", "333.27");
        r.work("w-1", "C-1", 7, 0);
        r.complete("w-1", 7 * DAY);
        r.accept("a-1", "C-1", 7, "验收-1", 7 * DAY);
        r.invoice("i-1", "C-1", 7 * DAY);
        assertEquals("0.00", r.report().arCurrent().toPlainString());
        assertEquals("0.00", r.report().arRetention().toPlainString());

        r.work("w-2", "C-1", 11, 7 * DAY);
        r.complete("w-2", 18 * DAY);
        r.accept("a-2", "C-1", 11, "验收-2", 18 * DAY);
        r.invoice("i-2", "C-1", 18 * DAY);
        assertEquals("188.98", r.report().arCurrent().toPlainString());
        assertEquals("77.69", r.report().arRetention().toPlainString());

        r.collectDue("d-2", "C-1", 1, "188.98", 18 * DAY + 15 * DAY);
        r.work("w-3", "C-1", 12, 18 * DAY);
        r.complete("w-3", 30 * DAY);
        r.accept("a-3", "C-1", 12, "验收-3", 30 * DAY);
        r.invoice("i-3", "C-1", 30 * DAY);
        r.collectDue("d-3", "C-1", 2, "399.96", 30 * DAY + 15 * DAY);
        r.paySupplier("p-1", "399.82");
        r.collectRetention("r-1", "C-1", 1, "77.69", 30 * DAY + 60 * DAY);

        Report report = r.report();
        assertEquals("999.90", report.revenue().toPlainString());
        assertEquals("599.70", report.cogs().toPlainString());
        assertEquals("400.20", report.profit().toPlainString());
        assertEquals("1400.20", report.equity().toPlainString());
        assertEquals("1400.20", report.companyCash().toPlainString());
        assertTrue(report.balanced());
    }

    // ------------------------------------------------------------------ 冻结条款与边界

    @Test
    @DisplayName("条款：附加质量条件模式显式拒绝；比例越界与阶段表非法在构造期拒绝")
    void termsRejectUnsupportedAndInvalid() {
        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> new Terms(2000, 1000, 15, 60,
                        RetentionMode.FINAL_ACCEPTANCE_AND_TIME, new long[]{50, 50}));
        assertEquals("RETENTION_CONDITION_UNSUPPORTED", unsupported.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new Terms(6000, 5000, 15, 60, RetentionMode.TIME_ONLY, new long[]{50}));
        assertThrows(IllegalArgumentException.class,
                () -> new Terms(-1, 0, 15, 60, RetentionMode.TIME_ONLY, new long[]{50}));
        assertThrows(IllegalArgumentException.class,
                () -> new Terms(0, 0, 15, 60, RetentionMode.TIME_ONLY, new long[]{}));
        assertThrows(IllegalArgumentException.class,
                () -> new Terms(0, 0, 15, 60, RetentionMode.TIME_ONLY, new long[]{0}));
    }

    @Test
    @DisplayName("阶段表防御性复制：外部修改数组不影响条款与合同")
    void trancheArrayIsDefensive() {
        long[] plan = {50, 50};
        Terms terms = new Terms(2000, 1000, 15, 60, RetentionMode.TIME_ONLY, plan);
        plan[0] = 99;
        assertEquals(50, terms.trancheUnits()[0]);
        assertEquals(100, terms.totalUnits());
        long[] copy = terms.trancheUnits();
        copy[0] = 1;
        assertEquals(50, terms.trancheUnits()[0]);
    }

    @Test
    @DisplayName("授权先于存在性：非公司所有者对已知/未知合同都得到 NOT_AUTHORIZED，无法探测")
    void unauthorizedCannotProbe() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        r.sign("c-sign", "A-1", "120.00", "60.00", terms(2000, 1000, 15, 60, 50, 50));
        Outcome known = EngineeringModel.receiveAdvance(r.state(), "x-1", "other", "A-1",
                bd("100.00"), 0, r.accounts());
        Outcome unknown = EngineeringModel.receiveAdvance(r.state(), "x-2", "other", "NOPE",
                bd("100.00"), 0, r.accounts());
        assertEquals("NOT_AUTHORIZED", known.reason());
        assertEquals("NOT_AUTHORIZED", unknown.reason());
        assertEquals("UNKNOWN_CONTRACT", EngineeringModel.receiveAdvance(r.state(), "x-3",
                "company", "NOPE", bd("100.00"), 0, r.accounts()).reason());
    }

    @Test
    @DisplayName("commandId 幂等：同 ID 同内容不重复记账；同 ID 异内容抛冲突")
    void commandIdempotency() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        r.sign("c-sign", "A-1", "120.00", "60.00", terms(2000, 1000, 15, 60, 50, 50));
        r.advance("c-adv", "A-1", "1000.00");
        Map<String, CashAccount> before = r.accounts();
        Outcome replay = EngineeringModel.receiveAdvance(r.state(), "c-adv", "company", "A-1",
                bd("1000.00"), 0, before);
        assertTrue(replay.accepted());
        assertEquals("ADVANCE_RECEIVED", replay.reason());
        assertEquals(before, replay.accounts(), "重放不得重复转移现金");
        assertThrows(IllegalArgumentException.class,
                () -> EngineeringModel.receiveAdvance(r.state(), "c-adv", "company", "A-1",
                        bd("2000.00"), 0, before));
    }

    @Test
    @DisplayName("失败整笔不变：人工现金不足时账户与业务状态都不变")
    void failedPaymentLeavesBothSidesUnchanged() {
        Config poor = new Config("company", "CO", "CUS", "SUP", "OPS", DAY, 6000, "实验参数");
        Result r = new Result(opening("0.00", "5000.00"), poor);
        r.sign("c-sign", "A-1", "120.00", "60.00", terms(2000, 1000, 15, 60, 50));
        Map<String, CashAccount> before = r.accounts();
        Outcome outcome = EngineeringModel.investWork(r.state(), "w-cmd", "company", "A-1",
                "w-1", 10, 0, before);
        assertFalse(outcome.accepted());
        assertEquals("INSUFFICIENT_CASH", outcome.reason());
        assertEquals(before, outcome.accounts());
        assertEquals("0.00", outcome.state().claims().get("A-1").contractCost().toPlainString());
        assertTrue(outcome.state().workOrders().isEmpty());
    }

    @Test
    @DisplayName("完工与验收门：未到完工时点、超完工量、阶段不匹配都拒绝")
    void workAndAcceptanceGates() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        r.sign("c-sign", "A-1", "120.00", "60.00", terms(2000, 1000, 15, 60, 50, 50));
        r.work("w-1", "A-1", 50, 0);
        assertEquals("WORK_NOT_DUE", r.tryComplete("w-1", 50 * DAY - 1).reason());
        assertEquals("EXCEEDS_COMPLETED", r.tryAccept("a-0", "A-1", 50, "验收", 50 * DAY).reason());
        r.complete("w-1", 50 * DAY);
        assertEquals("TRANCHE_MISMATCH", r.tryAccept("a-1", "A-1", 20, "验收", 50 * DAY).reason());
        assertEquals("NOT_ACCEPTED", r.tryInvoice("i-0", "A-1", 50 * DAY).reason());
    }

    @Test
    @DisplayName("回款门：未到期、超额、质保未到期都拒绝且账户不变")
    void collectionGates() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        r.sign("c-sign", "A-1", "120.00", "60.00", terms(2000, 1000, 15, 60, 50, 50));
        r.advance("c-adv", "A-1", "2400.00");
        r.work("w-1", "A-1", 50, 0);
        r.complete("w-1", 50 * DAY);
        r.accept("a-1", "A-1", 50, "验收", 50 * DAY);
        r.invoice("i-1", "A-1", 50 * DAY);
        Map<String, CashAccount> before = r.accounts();
        assertEquals("NOT_DUE", r.tryCollectDue("d-0", "A-1", 0, "100.00", 50 * DAY).reason());
        assertEquals("OVER_COLLECT", r.tryCollectDue("d-1", "A-1", 0, "2400.01",
                50 * DAY + 15 * DAY).reason());
        assertEquals("NOT_DUE", r.tryCollectRetention("r-0", "A-1", 0, "100.00",
                50 * DAY + 15 * DAY).reason());
        assertEquals(before, r.accounts(), "拒绝的动作不得改变账户");
    }

    @Test
    @DisplayName("减值/核销/回收边界：核销不得超已计提；回收不得超备查")
    void impairmentBounds() {
        Result r = arReady();
        assertEquals("IMPAIR_EXCEEDS_GROSS", r.tryImpair("im-0", "A-1", "5000.00").reason());
        r.impair("im-1", "A-1", "200.00");
        assertEquals("WRITE_OFF_EXCEEDS_ALLOWANCE",
                r.tryWriteOff("wo-0", "A-1", "200.01").reason());
        r.writeOff("wo-1", "A-1", "80.00");
        assertEquals("120.00", r.report().allowance().toPlainString());
        assertEquals("80.00", r.report().memo().toPlainString());
        assertEquals("RECOVERY_EXCEEDS_MEMO", r.tryRecover("rc-0", "A-1", "80.01").reason());
        r.recover("rc-1", "A-1", "30.00");
        assertEquals("50.00", r.report().memo().toPlainString());
        assertEquals("30.00", r.report().recoveryIncome().toPlainString());
        assertEquals("4000.00", r.report().revenue().toPlainString(), "回收不得再确认收入");
    }

    @Test
    @DisplayName("豁免不得重复损失：已有准备先释放，只对账面净值计损失")
    void waiverReleasesAllowance() {
        Result r = arReady();
        r.impair("im-1", "A-1", "300.00");
        r.waive("wv-1", "A-1", "1000.00", "董事会批准");
        Report report = r.report();
        assertEquals("700.00", report.waiverLoss().toPlainString());
        assertEquals("0.00", report.allowance().toPlainString());
        assertEquals("1000.00", report.arGross().toPlainString(),
                "豁免 1000 只解除当期 1000，未到期质保 1000 仍在");
        assertEquals("1000.00", report.obligation().toPlainString());
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("豁免未开票合同资产：按账面确认损失，保持平衡")
    void waiverContractAssetRecognizesLoss() {
        Result r = new Result(opening("5000.00", "20000.00"), CONFIG);
        r.sign("c-sign", "A-1", "100.00", "60.00", terms(2000, 1000, 15, 60, 40, 60));
        r.advance("c-adv", "A-1", "2000.00");
        r.work("w-1", "A-1", 40, 0);
        r.complete("w-1", 40 * DAY);
        r.accept("a-1", "A-1", 40, "验收", 40 * DAY);
        assertEquals("2000.00", r.report().contractAsset().toPlainString());
        r.waive("wv-1", "A-1", "2000.00", "董事会批准解除未开票义务");
        Report report = r.report();
        assertEquals("0.00", report.contractAsset().toPlainString());
        assertEquals("2000.00", report.waiverLoss().toPlainString());
        assertEquals("0.00", report.obligation().toPlainString());
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("终止保留已验收应收与未到期质保，不搬入备查")
    void terminationKeepsAcceptedClaims() {
        Result r = bPathBeforeWaiver();
        assertEquals("1000.00", r.report().arRetention().toPlainString());
        assertEquals("450.00", r.report().memo().toPlainString());
        assertEquals("1450.00", r.report().obligation().toPlainString());
    }

    @Test
    @DisplayName("溢出：单位数×unitMillis 与时间相加溢出都被拒绝")
    void overflowRejected() {
        Config huge = new Config("company", "CO", "CUS", "SUP", "OPS", Long.MAX_VALUE, 0,
                "溢出探针");
        Result r = new Result(opening("5000.00", "30000.00"), huge);
        r.sign("c-sign", "A-1", "1.00", "1.00", terms(0, 0, 15, 60, 50));
        assertEquals("OVERFLOW", EngineeringModel.investWork(r.state(), "w-cmd", "company",
                "A-1", "w-1", 2, 0, r.accounts()).reason());
    }

    @Test
    @DisplayName("不可变：State 集合只读、Outcome 账户表只读")
    void immutableViews() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        r.sign("c-sign", "A-1", "120.00", "60.00", terms(2000, 1000, 15, 60, 50, 50));
        assertThrows(UnsupportedOperationException.class,
                () -> r.state().contracts().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> r.accounts().clear());
    }

    @Test
    @DisplayName("预收上限：超过条款比例拒绝；未签约合同拒绝预收")
    void advanceCaps() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        assertEquals("UNKNOWN_CONTRACT",
                r.tryAdvance("x-0", "NOPE", "100.00").reason());
        r.sign("c-sign", "A-1", "120.00", "60.00", terms(2000, 1000, 15, 60, 50, 50));
        assertEquals("ADVANCE_EXCEEDS_TERMS",
                r.tryAdvance("x-1", "A-1", "2400.01").reason());
        r.advance("x-2", "A-1", "2000.00");
        assertEquals("ADVANCE_EXCEEDS_TERMS", r.tryAdvance("x-3", "A-1", "400.01").reason());
    }

    @Test
    @DisplayName("签约不动任何金额科目")
    void signLeavesMoneyUntouched() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        Map<String, CashAccount> before = r.accounts();
        r.sign("c-sign", "A-1", "120.00", "60.00", terms(2000, 1000, 15, 60, 50, 50));
        Report report = r.report();
        assertEquals(before, r.accounts());
        assertEquals("0.00", report.revenue().toPlainString());
        assertEquals("0.00", report.contractLiability().toPlainString());
        assertEquals("0.00", report.obligation().toPlainString());
        assertEquals("0.00", report.payable().toPlainString());
    }

    @Test
    @DisplayName("回款时付款方现金不足：双方账户与应收都不变")
    void collectWithInsufficientCustomerCashUnchanged() {
        Config poorCustomer = new Config("company", "CO", "CUS", "SUP", "OPS", DAY, 6000,
                "客户现金不足探针");
        Result r = new Result(opening("5000.00", "0.00"), poorCustomer);
        r.sign("c-sign", "A-1", "120.00", "60.00", terms(2000, 1000, 15, 60, 50, 50));
        r.work("w-1", "A-1", 50, 0);
        r.complete("w-1", 50 * DAY);
        r.accept("a-1", "A-1", 50, "验收", 50 * DAY);
        r.invoice("i-1", "A-1", 50 * DAY);
        Map<String, CashAccount> before = r.accounts();
        Outcome outcome = EngineeringModel.collectDue(r.state(), "d-1", "company", "A-1", 0,
                bd("4800.00"), 50 * DAY + 15 * DAY, before);
        assertFalse(outcome.accepted());
        assertEquals("INSUFFICIENT_CASH", outcome.reason());
        assertEquals(before, outcome.accounts());
        assertEquals("4800.00", outcome.state().claims().get("A-1").arCurrent().toPlainString());
    }

    @Test
    @DisplayName("支付供应商不再计费用：COGS 与减值不变")
    void supplierPaymentDoesNotRecordExpense() {
        Result r = arReady();
        String cogs = r.report().cogs().toPlainString();
        String impairment = r.report().impairmentLoss().toPlainString();
        assertTrue(new BigDecimal(r.report().payable().toPlainString()).signum() > 0);
        r.paySupplier("p-1", r.report().payable().toPlainString());
        assertEquals(cogs, r.report().cogs().toPlainString());
        assertEquals(impairment, r.report().impairmentLoss().toPlainString());
        assertEquals("0.00", r.report().payable().toPlainString());
    }

    @Test
    @DisplayName("F1 回归：验收游标独立于开票，[10,20] 重复验收 10 被拒，随后 20 可推进")
    void acceptanceCursorIndependentOfInvoicing() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        r.sign("c-sign", "A-1", "100.00", "60.00", terms(0, 0, 15, 60, 10, 20));
        r.work("w-1", "A-1", 30, 0);
        r.complete("w-1", 30 * DAY);
        r.accept("a-1", "A-1", 10, "bridge-ref-1", 30 * DAY);
        assertEquals("TRANCHE_MISMATCH",
                r.tryAccept("a-2", "A-1", 10, "bridge-ref-2", 30 * DAY).reason());
        assertEquals("1000.00", r.report().revenue().toPlainString());
        r.accept("a-3", "A-1", 20, "bridge-ref-3", 30 * DAY);
        assertEquals("3000.00", r.report().revenue().toPlainString());
        r.invoice("i-1", "A-1", 30 * DAY);
        r.invoice("i-2", "A-1", 30 * DAY);
        assertEquals("3000.00", r.report().obligation().toPlainString());
        assertEquals("3000.00", r.report().arCurrent().toPlainString());
        assertEquals("0.00", r.report().contractAsset().toPlainString());
    }

    @Test
    @DisplayName("F2 回归：0.005×10 拆十单不因四舍五入被拒，累计成本/人工与整单一致")
    void splitOrdersUseCumulativeTargets() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        r.sign("c-sign", "A-1", "1.00", "0.005",
                terms(0, 0, 15, 60, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
        String cashBefore = r.report().companyCash().toPlainString();
        String cashAfterFirst = null;
        String cashAfterSecond = null;
        for (int i = 1; i <= 10; i++) {
            r.work("w-" + i, "A-1", 1, 0);
            if (i == 1) {
                cashAfterFirst = r.report().companyCash().toPlainString();
            }
            if (i == 2) {
                cashAfterSecond = r.report().companyCash().toPlainString();
            }
        }
        assertFalse(cashBefore.equals(cashAfterFirst), "首单人工应真实付现");
        assertEquals(cashAfterFirst, cashAfterSecond, "0 人工工单不得转移现金");
        for (int i = 1; i <= 10; i++) {
            r.complete("w-" + i, DAY);
        }
        for (int i = 0; i < 10; i++) {
            r.accept("a-" + i, "A-1", 1, "bridge-ref-" + i, 2 * DAY);
        }
        Report report = r.report();
        assertEquals("0.05", report.cogs().toPlainString(), "累计成本必须等于整单计划 0.05");
        assertEquals("0.02", report.payable().toPlainString());
        assertEquals("4999.97", report.companyCash().toPlainString());
        assertEquals("10.00", report.revenue().toPlainString());
        assertTrue(report.balanced());
    }

    @Test
    @DisplayName("F3 回归：终止关闭在途工单，之后不可完工，损失只计一次")
    void terminationClosesInProgressWork() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        r.sign("c-sign", "A-1", "100.00", "60.00", terms(0, 0, 15, 60, 100));
        r.work("w-1", "A-1", 100, 0);
        r.terminate("tm-1", "A-1", "客户违约");
        assertEquals("6000.00", r.report().terminationLoss().toPlainString());
        assertEquals("0.00", r.report().contractCost().toPlainString());
        Outcome complete = EngineeringModel.completeWork(r.state(), "cw-1", "company", "w-1",
                100 * DAY, r.accounts());
        assertFalse(complete.accepted());
        assertEquals("CONTRACT_NOT_ACTIVE", complete.reason());
        for (var order : r.state().workOrders().values()) {
            assertFalse(order.status() == EngineeringModel.WorkStatus.IN_PROGRESS,
                    "终止后不得留下在途任务");
        }
        assertTrue(r.report().balanced());
    }

    @Test
    @DisplayName("构造状态校验：已验收前缀/开票未收/终止在途三类不一致都被拒绝")
    void constructedStateValidationRejectsInconsistency() {
        Result r = new Result(opening("5000.00", "30000.00"), CONFIG);
        r.sign("c-sign", "A-1", "100.00", "60.00", terms(0, 0, 15, 60, 10, 20));
        Contract contract = r.state().contracts().get("A-1");
        assertThrows(IllegalArgumentException.class, () -> new EngineeringModel.Contract(
                contract.contractId(), contract.unitPrice(), contract.unitCost(),
                contract.terms(), contract.signedAt(), 0, 10, 0, ZERO_BD, ZERO_BD,
                List.of(), List.of(), EngineeringModel.ContractStatus.ACTIVE));

        Claim claim = r.state().claims().get("A-1");
        Map<String, Claim> claims = new LinkedHashMap<>(r.state().claims());
        claims.put("A-1", new Claim(claim.contractAsset(), claim.contractLiability(),
                claim.contractCost(), bd("1.00"), claim.arRetention(), claim.allowance(),
                claim.memo(), claim.revenue(), claim.cogs(), claim.impairmentLoss(),
                claim.recoveryIncome(), claim.terminationLoss(), claim.waiverLoss()));
        assertThrows(IllegalArgumentException.class, () -> new State(r.state().config(),
                r.state().contracts(), r.state().workOrders(), claims, r.state().payable(),
                r.state().initialEquity(), r.state().initialWorldCash(), r.state().usedIds(),
                r.state().commandContents(), r.state().commandOutcomes()));

        r.work("w-1", "A-1", 10, 0);
        Map<String, EngineeringModel.WorkOrder> orders =
                new LinkedHashMap<>(r.state().workOrders());
        EngineeringModel.WorkOrder order = orders.get("w-1");
        orders.put("w-1", new EngineeringModel.WorkOrder(order.workId(), order.contractId(),
                order.units(), order.startedAt(), order.completesAt(), order.totalCost(),
                order.laborCost(), 0, order.remainingCost(),
                EngineeringModel.WorkStatus.IN_PROGRESS));
        Contract terminated = new EngineeringModel.Contract(contract.contractId(),
                contract.unitPrice(), contract.unitCost(), contract.terms(),
                contract.signedAt(), 0, 0, 0, ZERO_BD, ZERO_BD, List.of(), List.of(),
                EngineeringModel.ContractStatus.TERMINATED);
        Map<String, Contract> contracts = new LinkedHashMap<>(r.state().contracts());
        contracts.put("A-1", terminated);
        assertThrows(IllegalArgumentException.class, () -> new State(r.state().config(),
                contracts, orders, r.state().claims(), r.state().payable(),
                r.state().initialEquity(), r.state().initialWorldCash(), r.state().usedIds(),
                r.state().commandContents(), r.state().commandOutcomes()));
    }

    // ------------------------------------------------------------------ 夹具

    private static Result bPath() {
        Result r = new Result(opening("5000.00", "20000.00"), CONFIG);
        r.sign("c-sign", "B-1", "100.00", "60.00", terms(2000, 1000, 15, 60, 40, 60));
        r.advance("c-adv", "B-1", "2000.00");
        r.work("w-1", "B-1", 100, 0);
        r.complete("w-1", 100 * DAY);
        r.accept("a-1", "B-1", 40, "客户验收单", 100 * DAY);
        r.invoice("i-1", "B-1", 100 * DAY);
        assertEquals("1000.00", r.report().arCurrent().toPlainString());
        assertEquals("1000.00", r.report().arRetention().toPlainString());
        r.collectDue("d-1", "B-1", 0, "400.00", 100 * DAY + 15 * DAY);
        assertEquals("600.00", r.report().arCurrent().toPlainString());
        r.impair("im-1", "B-1", "600.00");
        assertEquals("1600.00", r.report().obligation().toPlainString());
        r.writeOff("wo-1", "B-1", "600.00");
        assertEquals("1600.00", r.report().obligation().toPlainString(), "核销不解除义务");
        assertEquals("600.00", r.report().memo().toPlainString());
        r.recover("rc-1", "B-1", "150.00");
        assertEquals("1450.00", r.report().obligation().toPlainString());
        r.terminate("tm-1", "B-1", "客户不验收且不付款");
        r.paySupplier("p-1", "2400.00");
        return r;
    }

    private static Result bPathBeforeWaiver() {
        return bPath();
    }

    /** 已开票 1000、质保留 1000 的合同，用于减值/核销/豁免边界。 */
    private static Result arReady() {
        Result r = new Result(opening("5000.00", "20000.00"), CONFIG);
        r.sign("c-sign", "A-1", "100.00", "60.00", terms(2000, 1000, 15, 60, 40, 60));
        r.advance("c-adv", "A-1", "2000.00");
        r.work("w-1", "A-1", 40, 0);
        r.complete("w-1", 40 * DAY);
        r.accept("a-1", "A-1", 40, "验收", 40 * DAY);
        r.invoice("i-1", "A-1", 40 * DAY);
        return r;
    }

    private static Map<String, CashAccount> opening(String company, String customer) {
        Map<String, CashAccount> accounts = new LinkedHashMap<>();
        accounts.put("CO", new CashAccount("company", bd(company), "开局禀赋"));
        accounts.put("CUS", new CashAccount("customer", bd(customer), "开局禀赋"));
        accounts.put("SUP", new CashAccount("supplier", bd("0.00"), "开局禀赋"));
        accounts.put("OPS", new CashAccount("ops", bd("0.00"), "开局禀赋"));
        return accounts;
    }

    private static Terms terms(int advanceBp, int retentionBp, int netDays, int retentionDays,
                               long... tranches) {
        return new Terms(advanceBp, retentionBp, netDays, retentionDays,
                RetentionMode.TIME_ONLY, tranches);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static final class Result {
        private State state;
        private Map<String, CashAccount> accounts;

        Result(Map<String, CashAccount> accounts, Config config) {
            this.accounts = accounts;
            this.state = EngineeringModel.open(config, accounts);
        }

        State state() {
            return state;
        }

        Map<String, CashAccount> accounts() {
            return accounts;
        }

        Report report() {
            return EngineeringModel.validate(state, accounts);
        }

        void sign(String command, String id, String price, String cost, Terms terms) {
            apply(EngineeringModel.sign(state, command, "company",
                    new ContractSpec(id, bd(price), bd(cost), terms), 0, accounts));
        }

        void advance(String command, String id, String amount) {
            apply(EngineeringModel.receiveAdvance(state, command, "company", id, bd(amount), 0,
                    accounts));
        }

        void work(String id, String contract, long units, long now) {
            apply(EngineeringModel.investWork(state, "cmd-" + id, "company", contract, id, units,
                    now, accounts));
        }

        void complete(String id, long now) {
            apply(EngineeringModel.completeWork(state, "cmd-complete-" + id, "company", id, now,
                    accounts));
        }

        void accept(String command, String contract, long units, String ref, long now) {
            apply(EngineeringModel.accept(state, command, "company", contract, units, ref, now,
                    accounts));
        }

        void invoice(String command, String contract, long now) {
            apply(EngineeringModel.invoice(state, command, "company", contract, now, accounts));
        }

        void collectDue(String command, String contract, int index, String amount, long now) {
            apply(EngineeringModel.collectDue(state, command, "company", contract, index,
                    bd(amount), now, accounts));
        }

        void collectRetention(String command, String contract, int index, String amount,
                              long now) {
            apply(EngineeringModel.collectRetention(state, command, "company", contract, index,
                    bd(amount), now, accounts));
        }

        void impair(String command, String contract, String amount) {
            apply(EngineeringModel.impair(state, command, "company", contract, bd(amount), 0,
                    accounts));
        }

        void writeOff(String command, String contract, String amount) {
            apply(EngineeringModel.writeOff(state, command, "company", contract, bd(amount), 0,
                    accounts));
        }

        void recover(String command, String contract, String amount) {
            apply(EngineeringModel.recover(state, command, "company", contract, bd(amount), 0,
                    accounts));
        }

        void waive(String command, String contract, String amount, String reason) {
            apply(EngineeringModel.waive(state, command, "company", contract, bd(amount),
                    reason, 0, accounts));
        }

        void terminate(String command, String contract, String reason) {
            apply(EngineeringModel.terminate(state, command, "company", contract, reason, 0,
                    accounts));
        }

        void paySupplier(String command, String amount) {
            apply(EngineeringModel.paySupplier(state, command, "company", bd(amount), 0,
                    accounts));
        }

        Outcome tryAdvance(String command, String id, String amount) {
            return EngineeringModel.receiveAdvance(state, command, "company", id, bd(amount), 0,
                    accounts);
        }

        Outcome tryComplete(String id, long now) {
            return EngineeringModel.completeWork(state, "cmd-c-" + id, "company", id, now,
                    accounts);
        }

        Outcome tryAccept(String command, String contract, long units, String ref, long now) {
            return EngineeringModel.accept(state, command, "company", contract, units, ref, now,
                    accounts);
        }

        Outcome tryInvoice(String command, String contract, long now) {
            return EngineeringModel.invoice(state, command, "company", contract, now, accounts);
        }

        Outcome tryCollectDue(String command, String contract, int index, String amount,
                              long now) {
            return EngineeringModel.collectDue(state, command, "company", contract, index,
                    bd(amount), now, accounts);
        }

        Outcome tryCollectRetention(String command, String contract, int index, String amount,
                                    long now) {
            return EngineeringModel.collectRetention(state, command, "company", contract, index,
                    bd(amount), now, accounts);
        }

        Outcome tryImpair(String command, String contract, String amount) {
            return EngineeringModel.impair(state, command, "company", contract, bd(amount), 0,
                    accounts);
        }

        Outcome tryWriteOff(String command, String contract, String amount) {
            return EngineeringModel.writeOff(state, command, "company", contract, bd(amount), 0,
                    accounts);
        }

        Outcome tryRecover(String command, String contract, String amount) {
            return EngineeringModel.recover(state, command, "company", contract, bd(amount), 0,
                    accounts);
        }

        private void apply(Outcome outcome) {
            assertTrue(outcome.accepted(), outcome.reason());
            state = outcome.state();
            accounts = outcome.accounts();
            EngineeringModel.validate(state, accounts);
        }
    }
}
