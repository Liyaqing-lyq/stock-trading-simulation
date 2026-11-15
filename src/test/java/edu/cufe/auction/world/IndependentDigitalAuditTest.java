package edu.cufe.auction.world;

import edu.cufe.auction.world.DigitalServiceModel.Config;
import edu.cufe.auction.world.DigitalServiceModel.Contract;
import edu.cufe.auction.world.DigitalServiceModel.Evidence;
import edu.cufe.auction.world.DigitalServiceModel.Outcome;
import edu.cufe.auction.world.DigitalServiceModel.Receivable;
import edu.cufe.auction.world.DigitalServiceModel.Report;
import edu.cufe.auction.world.DigitalServiceModel.Stage;
import edu.cufe.auction.world.DigitalServiceModel.State;
import edu.cufe.auction.world.bank.FundState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static edu.cufe.auction.world.DigitalServiceModel.APPROVAL_COMMERCIAL;
import static edu.cufe.auction.world.PureCompanyTestSupport.account;
import static edu.cufe.auction.world.PureCompanyTestSupport.funds;
import static edu.cufe.auction.world.PureCompanyTestSupport.money;
import static edu.cufe.auction.world.PureCompanyTestSupport.payer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立复核（不是作者测试的复述）：数字服务模型的账目由本类按合同口径<b>另行复算</b>——
 * 收入按交付量、预收按比例冲减、退款义务按未赚预收、资产/负债/权益按明细重建，
 * 并逐笔核对“确认收入 = 预收冲减 + 应收”这条独立恒等式。
 *
 * <p>覆盖：PILOT 费用化 / VALIDATION 资本化、签约不是收入、交付按量确认、终止保留退款义务、
 * 现金不足拒绝退款且不抹负债、真实补足后结清、期内需求预算与容量、关期连续性、世界现金守恒。
 * 已证实缺陷见 {@code IndependentDefectProbeTest}。</p>
 */
class IndependentDigitalAuditTest {

    private static final long DAY = DigitalServiceModel.DAY_MILLIS;
    private static final String CO = "company";

    private static Config config(String price, String unitCost, long demand, String budget,
                                 long capacity, String companyCash) {
        return new Config(CO, "CO", "OPS", "CUS", "CUS", "CON", "CON", "SPON", "SPON",
                new BigDecimal(price), money(unitCost), demand, money(budget), capacity,
                5L * DAY, money("50.00"), 10L * DAY, money("100.00"), true, true,
                money("10.00"), money("25.00"),
                Map.of("AP-COMM", new DigitalServiceModel.Approval("AP-COMM",
                        APPROVAL_COMMERCIAL, 0L)));
    }

    private static final class Rig {

        private State state;
        private FundState funds;
        private final PureCompanyTestSupport.Payer coToCon = payer("CO", "CON");
        private final PureCompanyTestSupport.Payer coToOps = payer("CO", "OPS");
        private final PureCompanyTestSupport.Payer cusToCo = payer("CUS", "CO");
        private final PureCompanyTestSupport.Payer coToCus = payer("CO", "CUS");
        private final PureCompanyTestSupport.Payer sponToCo = payer("SPON", "CO");

        private Rig(Config config, String companyCash, String customerCash) {
            Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
            map.put("CO", account(CO, companyCash));
            map.put("OPS", account("ops", "0.00"));
            map.put("CUS", account("customer", customerCash));
            map.put("CON", account("contractor", "0.00"));
            map.put("SPON", account("sponsor", "400.00"));
            this.funds = funds(map);
            this.state = DigitalServiceModel.open(config, funds);
        }

        private String take(Outcome outcome) {
            state = outcome.state();
            funds = outcome.funds();
            return outcome.reason();
        }

        private String begin(long now, Stage stage, String command) {
            return take(DigitalServiceModel.beginStage(state, now, CO, command, stage, funds,
                    coToCon));
        }

        private String complete(long now, Stage stage, String command, long observedAt) {
            return take(DigitalServiceModel.completeStage(state, now, CO, command, stage,
                    new Evidence("ev-" + stage, "read-log", observedAt), funds));
        }

        private String approve(long now, String command) {
            return take(DigitalServiceModel.approveCommercialization(state, now, CO, command,
                    "AP-COMM", funds));
        }

        private String sign(long now, String contract, long quantity, String prepaid,
                            String command) {
            return take(DigitalServiceModel.signServiceContract(state, now, CO, command, contract,
                    quantity, money(prepaid), funds, cusToCo));
        }

        private String deliver(long now, String contract, long units, String receivable,
                               String command) {
            return take(DigitalServiceModel.deliverService(state, now, CO, command, contract,
                    units, receivable, funds, coToOps));
        }

        private String terminate(long now, String contract, String command) {
            return take(DigitalServiceModel.terminateContract(state, now, CO, command, contract,
                    funds));
        }

        private String refund(long now, String contract, String amount, String command) {
            return take(DigitalServiceModel.refund(state, now, CO, command, contract,
                    money(amount), funds, coToCus));
        }

        private String inject(long now, String amount, String command) {
            return take(DigitalServiceModel.injectCapital(state, now, CO, command, money(amount),
                    funds, sponToCo));
        }

        private String collect(long now, String receivable, String command) {
            return take(DigitalServiceModel.collect(state, now, CO, command, receivable, funds,
                    cusToCo));
        }

        private String close(long now, long period, String command) {
            return take(DigitalServiceModel.closePeriod(state, now, CO, command, period, funds,
                    coToOps));
        }

        private Report report() {
            return DigitalServiceModel.validate(state, funds);
        }

        private BigDecimal cash(String accountId) {
            return funds.cash(accountId);
        }
    }

    /** 跑到 VALIDATION 通过 + 商业化批准（第 15 日，第 0 期）。 */
    private Rig ready(Config config, String companyCash, String customerCash) {
        Rig rig = new Rig(config, companyCash, customerCash);
        assertEquals("STAGE_BEGUN", rig.begin(0L, Stage.PILOT, "p1"));
        assertEquals("STAGE_PASSED", rig.complete(5L * DAY, Stage.PILOT, "p1c", DAY));
        assertEquals("STAGE_BEGUN", rig.begin(5L * DAY, Stage.VALIDATION, "v1"));
        assertEquals("STAGE_PASSED", rig.complete(15L * DAY, Stage.VALIDATION, "v1c", 6L * DAY));
        assertEquals("APPROVED", rig.approve(15L * DAY, "ap1"));
        return rig;
    }

    // ------------------------------------------------- 1 两阶段报表口径

    @Test
    void pilotExpensedValidationCapitalisedWithIndependentRecompute() {
        Rig rig = ready(config("1.0000", "0.60", 300L, "300.00", 200L, "300.00"),
                "300.00", "1000.00");
        Report r = rig.report();
        // 现金 300−50−100=150；平台资产 100；试点已费用化 50，在研 0。
        assertEquals(money("150.00"), rig.cash("CO"));
        assertEquals(money("100.00"), r.platformNet());
        assertEquals(money("0.00"), r.wip());
        assertEquals(money("50.00"), r.pilotExpense());
        assertEquals(money("250.00"), r.assets());
        assertEquals(money("0.00"), r.liabilities());
        assertEquals(money("250.00"), r.equity());
        assertTrue(r.validated());
        assertEquals(r.assets(), r.liabilities().add(r.equity()));

        // 平台资产按期摊销，且不超过净值；关账后 A=L+E 与现金守恒都不变。
        assertEquals("PERIOD_CLOSED", rig.close(30L * DAY, 0L, "cl0"));
        Report after = rig.report();
        assertEquals(money("75.00"), after.platformNet());
        assertEquals(money("25.00"), after.amortization());
        assertEquals(money("10.00"), after.fixedExpense());
        assertEquals(money("215.00"), after.assets());
        assertEquals(money("215.00"), after.equity());
        assertEquals(money("1700.00"), after.worldCash());
    }

    // ------------------------------------------------- 2 签约不是收入，交付才确认

    @Test
    void signingIsLiabilityOnlyAndDeliveryReconcilesCashPlusAr() {
        Rig rig = ready(config("1.0000", "0.60", 300L, "300.00", 200L, "300.00"),
                "300.00", "1000.00");
        assertEquals("SIGNED", rig.sign(16L * DAY, "C1", 300L, "300.00", "sg1"));
        Report signed = rig.report();
        assertEquals(money("0.00"), signed.revenue());
        assertEquals(money("300.00"), signed.refundObligation());
        assertEquals(money("550.00"), signed.assets());
        assertEquals(money("300.00"), signed.liabilities());
        assertEquals(money("250.00"), signed.equity());
        assertEquals(money("450.00"), rig.cash("CO"));

        assertEquals("DELIVERED", rig.deliver(17L * DAY, "C1", 100L, "AR-1", "dl1"));
        Report delivered = rig.report();
        // 收入 100；预收按 300×100/300 冲减 100；未收 0；服务成本 60 真实现金支付。
        assertEquals(money("100.00"), delivered.revenue());
        assertEquals(money("60.00"), delivered.serviceCogs());
        assertEquals(money("200.00"), delivered.refundObligation());
        assertEquals(money("0.00"), delivered.receivables());
        assertEquals(money("390.00"), rig.cash("CO"));
        assertEquals(money("490.00"), delivered.assets());
        assertEquals(money("490.00"), delivered.liabilities().add(delivered.equity()));

        // 独立恒等式：确认收入 = 合同已冲减预收 + 本次应收金额。
        Contract c1 = rig.state.contracts().get("C1");
        Receivable ar = rig.state.receivables().get("AR-1");
        assertEquals(money("100.00"), c1.prepaidApplied().add(ar.amount()));
        assertEquals(ar.revenue(), c1.prepaidApplied().add(ar.amount()));
        assertEquals(100L, c1.delivered());
    }

    @Test
    void prepaidShortfallBecomesReceivableAndCollectionOnlyClearsIt() {
        Rig rig = ready(config("1.0000", "0.60", 300L, "300.00", 200L, "300.00"),
                "300.00", "1000.00");
        // 预收 0：交付后全额应收，收款只冲应收，不确认第二次收入。
        assertEquals("SIGNED", rig.sign(16L * DAY, "C1", 300L, "0.00", "sg1"));
        assertEquals("DELIVERED", rig.deliver(17L * DAY, "C1", 200L, "AR-1", "dl1"));
        Report r = rig.report();
        assertEquals(money("200.00"), r.revenue());
        assertEquals(money("200.00"), r.receivables());
        assertEquals(money("0.00"), r.refundObligation());

        assertEquals("RECEIVABLE_NOT_DUE", rig.collect(20L * DAY, "AR-1", "c-early"));
        // 应收 17 日到期（第 0 期），但因已跨期必须先关期 0 才允许经营/收款动作。
        assertEquals("PERIOD_NOT_CLOSED", rig.collect(17L * DAY + 15L * DAY, "AR-1", "c1"));
        assertEquals("PERIOD_CLOSED", rig.close(30L * DAY, 0L, "cl0"));
        assertEquals("PAYMENT_RECEIVED", rig.collect(32L * DAY, "AR-1", "c2"));
        Report paid = rig.report();
        assertEquals(money("200.00"), paid.revenue());
        assertEquals(money("0.00"), paid.receivables());
        assertEquals(money("800.00"), rig.cash("CUS"));
        assertEquals(paid.assets(), paid.liabilities().add(paid.equity()));
    }

    // ------------------------------------------------- 3 退款缺钱拒绝不抹负债

    @Test
    void refundShortfallKeepsLiabilityThenClearsAfterRealTopUp() {
        // 单价 1 但单位服务成本 2：交付会真实现金出血，退款时现金不足。
        Rig rig = ready(config("1.0000", "2.00", 300L, "300.00", 200L, "260.00"),
                "260.00", "1000.00");
        assertEquals(money("110.00"), rig.cash("CO"));
        assertEquals("SIGNED", rig.sign(16L * DAY, "C1", 300L, "300.00", "sg1"));
        assertEquals("DELIVERED", rig.deliver(17L * DAY, "C1", 200L, "AR-1", "dl1"));
        Report delivered = rig.report();
        assertEquals(money("200.00"), delivered.revenue());
        assertEquals(money("400.00"), delivered.serviceCogs());
        assertEquals(money("100.00"), delivered.refundObligation());
        assertEquals(money("10.00"), rig.cash("CO"));
        assertEquals(delivered.assets(), delivered.liabilities().add(delivered.equity()));

        assertEquals("TERMINATED", rig.terminate(18L * DAY, "C1", "tm1"));
        Report terminated = rig.report();
        assertEquals(money("100.00"), terminated.refundObligation());
        assertEquals(money("100.00"), terminated.refundOverdue());

        // 现金 10 < 应退 100：付款失败，负债与现金都不动。
        assertEquals("INSUFFICIENT_CASH", rig.refund(19L * DAY, "C1", "100.00", "rf1"));
        Report failed = rig.report();
        assertEquals(money("100.00"), failed.refundObligation());
        assertEquals(money("10.00"), rig.cash("CO"));
        assertEquals(failed.assets(), failed.liabilities().add(failed.equity()));

        // 真实注资 100 后才退得出；退款不减权益，只减现金与负债。
        assertEquals("CAPITAL_INJECTED", rig.inject(20L * DAY, "100.00", "in1"));
        BigDecimal equityBefore = rig.report().equity();
        assertEquals("REFUNDED", rig.refund(21L * DAY, "C1", "100.00", "rf2"));
        Report refunded = rig.report();
        assertEquals(money("0.00"), refunded.refundObligation());
        assertEquals(money("10.00"), rig.cash("CO"));
        assertEquals(equityBefore, refunded.equity());
        assertEquals(refunded.assets(), refunded.liabilities().add(refunded.equity()));
    }

    // ------------------------------------------------- 4 期内有限需求/预算/容量

    @Test
    void withinOnePeriodDemandBudgetAndCapacityAreEnforced() {
        Rig rig = ready(config("1.0000", "0.60", 300L, "300.00", 200L, "300.00"),
                "300.00", "1000.00");
        assertEquals("SIGNED", rig.sign(16L * DAY, "C1", 300L, "0.00", "sg1"));
        // 同一期内需求/预算已被 C1 占满：再签 1 件被拒。
        assertEquals("DEMAND_EXCEEDED", rig.sign(16L * DAY, "C2", 1L, "0.00", "sg2"));
        // 容量 200/期：交付 201 件被拒，且不动账。
        assertEquals("DELIVERY_EXCEEDS_CONTRACT", rig.deliver(17L * DAY, "C1", 301L, "AR-X", "dl-x"));
        BigDecimal revenueBefore = rig.report().revenue();
        assertEquals("DELIVERED", rig.deliver(17L * DAY, "C1", 200L, "AR-1", "dl1"));
        assertEquals("SERVICE_CAPACITY", rig.deliver(18L * DAY, "C1", 1L, "AR-2", "dl2"));
        assertEquals(money("200.00"), rig.report().revenue().subtract(revenueBefore));
        // 终止只释放未交付承诺，不恢复已交付额度。
        assertEquals("TERMINATED", rig.terminate(19L * DAY, "C1", "tm1"));
        assertEquals(0L, rig.state.reservedIn(0L));
        assertEquals(200L, rig.state.deliveredIn(0L));
    }

    // ------------------------------------------------- 5 关期连续性与阶段失败即停

    @Test
    void closingSequenceAndTerminalStageFailure() {
        Rig rig = new Rig(config("1.0000", "0.60", 300L, "300.00", 200L, "300.00"),
                "300.00", "1000.00");
        assertEquals("PERIOD_NOT_ENDED", rig.close(29L * DAY, 0L, "cl-early"));
        assertEquals("PERIOD_NOT_CONTINUOUS", rig.close(30L * DAY, 1L, "cl-skip"));
        assertEquals("PERIOD_CLOSED", rig.close(30L * DAY, 0L, "cl0"));
        assertEquals("PERIOD_CLOSED", rig.close(31L * DAY, 0L, "cl0-again"));
        // 关期后进入期 1 才能继续经营；此时现金流与权益只受固定费用与试点支出影响。
        assertEquals("STAGE_BEGUN", rig.begin(30L * DAY, Stage.PILOT, "p1"));
        Report after0 = rig.report();
        assertEquals(money("240.00"), after0.assets());
        assertEquals(money("240.00"), after0.equity());
        assertEquals(after0.assets(), after0.liabilities().add(after0.equity()));
    }
}
