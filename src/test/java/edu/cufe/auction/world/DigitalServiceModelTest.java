package edu.cufe.auction.world;

import edu.cufe.auction.world.DigitalServiceModel.Config;
import edu.cufe.auction.world.DigitalServiceModel.Evidence;
import edu.cufe.auction.world.DigitalServiceModel.Outcome;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数字服务模型的最小验收：两阶段（费用化/资本化）、失败即停、签约不是收入、
 * 预收→退款义务、交付→收入/成本/应收、终止保留未赚预收、现金不足退款失败、
 * 真实补足后成功、有限需求预算容量、非整除分摊、long 溢出、幂等与错主体。
 */
class DigitalServiceModelTest {

    private static final long DAY = DigitalServiceModel.DAY_MILLIS;
    private static final long PERIOD = DigitalServiceModel.PERIOD_MILLIS;
    private static final String CO = "company";

    /** 配置构造器（EXP 参数）。 */
    private static final class Cfg {

        private BigDecimal price = new BigDecimal("1.0000");
        private BigDecimal unitCost = money("0.60");
        private long demand = 300L;
        private BigDecimal budget = money("300.00");
        private long capacity = 200L;
        private long pilotDuration = 5L * DAY;
        private BigDecimal pilotCost = money("50.00");
        private long validationDuration = 10L * DAY;
        private BigDecimal validationCost = money("100.00");
        private boolean pilotPasses = true;
        private boolean validationPasses = true;
        private BigDecimal fixedExpense = money("10.00");
        private BigDecimal amortization = money("25.00");

        private Cfg() {
        }

        private Config build() {
            return new Config(CO, "CO", "OPS", "CUS", "CUS", "CON", "CON", "SPON", "SPON",
                    price, unitCost, demand, budget, capacity, pilotDuration, pilotCost,
                    validationDuration, validationCost, pilotPasses, validationPasses,
                    fixedExpense, amortization,
                    Map.of("AP-COMM", new DigitalServiceModel.Approval("AP-COMM",
                            APPROVAL_COMMERCIAL, 0L)));
        }
    }

    /** 全局现金与固定资金腿。 */
    private static final class World {

        private final Config config;
        private State state;
        private FundState funds;
        private final PureCompanyTestSupport.Payer coToCon = payer("CO", "CON");
        private final PureCompanyTestSupport.Payer coToOps = payer("CO", "OPS");
        private final PureCompanyTestSupport.Payer cusToCo = payer("CUS", "CO");
        private final PureCompanyTestSupport.Payer coToCus = payer("CO", "CUS");
        private final PureCompanyTestSupport.Payer sponToCo = payer("SPON", "CO");

        private World(Config config, Map<String, CashLedger.CashAccount> cash) {
            this.config = config;
            this.funds = funds(cash);
            this.state = DigitalServiceModel.open(config, funds);
        }

        private Outcome apply(Outcome outcome) {
            state = outcome.state();
            funds = outcome.funds();
            return outcome;
        }

        private Outcome begin(long now, Stage stage, String command) {
            return apply(DigitalServiceModel.beginStage(state, now, CO, command, stage, funds,
                    coToCon));
        }

        private Outcome complete(long now, Stage stage, String command, long observedAt) {
            return apply(DigitalServiceModel.completeStage(state, now, CO, command, stage,
                    new Evidence("ev-" + stage + "-" + command, "read-log", observedAt), funds));
        }

        private Outcome approve(long now, String command, String ref) {
            return apply(DigitalServiceModel.approveCommercialization(state, now, CO, command,
                    ref, funds));
        }

        private Outcome sign(long now, String contract, long quantity, String prepaid) {
            return apply(DigitalServiceModel.signServiceContract(state, now, CO,
                    "sign-" + contract, contract, quantity, money(prepaid), funds, cusToCo));
        }

        private Outcome deliver(long now, String contract, long units, String receivable) {
            return apply(DigitalServiceModel.deliverService(state, now, CO, "dl-" + receivable,
                    contract, units, receivable, funds, coToOps));
        }

        private Outcome terminate(long now, String contract) {
            return apply(DigitalServiceModel.terminateContract(state, now, CO,
                    "term-" + contract, contract, funds));
        }

        private Outcome refund(long now, String contract, String amount, String command) {
            return apply(DigitalServiceModel.refund(state, now, CO, command, contract,
                    money(amount), funds, coToCus));
        }

        private Outcome inject(long now, String amount, String command) {
            return apply(DigitalServiceModel.injectCapital(state, now, CO, command,
                    money(amount), funds, sponToCo));
        }

        private Outcome collect(long now, String receivable) {
            return apply(DigitalServiceModel.collect(state, now, CO, "co-" + receivable,
                    receivable, funds, cusToCo));
        }

        private Outcome close(long now, long period) {
            return apply(DigitalServiceModel.closePeriod(state, now, CO, "cl-" + period, period,
                    funds, coToOps));
        }

        private Report report() {
            return DigitalServiceModel.validate(state, funds);
        }
    }

    private static Map<String, CashLedger.CashAccount> cash(String company, String customer,
                                                            String sponsor) {
        Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
        map.put("CO", account(CO, company));
        map.put("OPS", account("ops", "0.00"));
        map.put("CUS", account("customer", customer));
        map.put("CON", account("contractor", "0.00"));
        map.put("SPON", account("sponsor", sponsor));
        return map;
    }

    private static World world(Cfg cfg) {
        return new World(cfg.build(), cash("300.00", "1000.00", "400.00"));
    }

    /** 跑到 VALIDATION 通过 + 商业化批准。 */
    private static World ready(Cfg cfg) {
        World w = world(cfg);
        assertEquals("STAGE_BEGUN", w.begin(0L, Stage.PILOT, "p1").reason());
        assertEquals("STAGE_PASSED", w.complete(5L * DAY, Stage.PILOT, "p1c", DAY).reason());
        assertEquals("STAGE_BEGUN", w.begin(5L * DAY, Stage.VALIDATION, "v1").reason());
        assertEquals("STAGE_PASSED", w.complete(15L * DAY, Stage.VALIDATION, "v1c",
                6L * DAY).reason());
        assertEquals("APPROVED", w.approve(15L * DAY, "ap1", "AP-COMM").reason());
        return w;
    }

    // ---------------------------------------------------------------- 1 普通

    @Test
    void deliveryRoomReflectsSharedCashBudgetCapacityAndOutstandingCurrentPeriodDemand() {
        World w = ready(new Cfg());
        assertTrue(w.sign(15 * DAY, "K1", 100, "0").accepted());
        assertTrue(w.sign(15 * DAY, "K2", 95, "0").accepted());
        assertTrue(w.deliver(16 * DAY, "K1", 25, "one").accepted());
        var room = DigitalDecisionTools.deliveryRoom(w.state, 16 * DAY, money("15.59"), money("8.99"));
        assertEquals(175,room.path("remainingCapacityUnits").asLong());
        assertEquals(105,room.path("uncommittedCurrentDemandUnits").asLong());
        assertEquals(14,room.path("cashBudgetCapacityUnits").asLong());
        assertEquals("15.59",room.path("freeCompanyCash").asText());
        assertEquals(2,DigitalDecisionTools.deliveryRoom(w.state,16 * DAY,money("1.21"),money("125")).path("cashBudgetCapacityUnits").asLong());
        assertEquals(0,DigitalDecisionTools.deliveryRoom(w.state,16 * DAY,money("100"),null).path("cashBudgetCapacityUnits").asLong());
        assertEquals(175,DigitalDecisionTools.deliveryRoom(w.state,16 * DAY,money("1E+25"),money("1E+24")).path("cashBudgetCapacityUnits").asLong());
        assertEquals(300,DigitalDecisionTools.deliveryRoom(w.state,31 * DAY,money("10"),money("10")).path("uncommittedCurrentDemandUnits").asLong());
        Cfg noCost=new Cfg();
        noCost.unitCost=money("0");
        assertEquals(200,DigitalDecisionTools.deliveryRoom(world(noCost).state,0,money("0"),null).path("cashBudgetCapacityUnits").asLong());
    }

    @Test
    void pilotExpensedValidationCapitalisedSignedThenDelivered() {
        World w = world(new Cfg());
        Report opening = w.report();
        assertEquals("300.00", opening.equity().toPlainString());
        assertTrue(opening.balanced());

        assertEquals("STAGE_BEGUN", w.begin(0L, Stage.PILOT, "p1").reason());
        assertEquals("250.00", w.report().companyCash().toPlainString());
        assertEquals("50.00", w.report().pilotExpense().toPlainString());
        assertEquals("0.00", w.report().wip().toPlainString());

        assertEquals("STAGE_PASSED", w.complete(5L * DAY, Stage.PILOT, "p1c", DAY).reason());
        assertEquals("STAGE_BEGUN", w.begin(5L * DAY, Stage.VALIDATION, "v1").reason());
        assertEquals("100.00", w.report().wip().toPlainString());
        assertEquals("0.00", w.report().platformNet().toPlainString());

        assertEquals("STAGE_PASSED", w.complete(15L * DAY, Stage.VALIDATION, "v1c",
                6L * DAY).reason());
        assertEquals("0.00", w.report().wip().toPlainString());
        assertEquals("100.00", w.report().platformNet().toPlainString());
        assertTrue(w.report().validated());

        assertEquals("APPROVED", w.approve(15L * DAY, "ap1", "AP-COMM").reason());
        assertEquals("SIGNED", w.sign(15L * DAY, "K1", 300L, "300.00").reason());
        Report signed = w.report();
        assertEquals("0.00", signed.revenue().toPlainString());
        assertEquals("300.00", signed.refundObligation().toPlainString());
        assertEquals("450.00", signed.companyCash().toPlainString());
        assertEquals("250.00", signed.equity().toPlainString());
        assertTrue(signed.balanced());

        assertEquals("DELIVERED", w.deliver(15L * DAY, "K1", 100L, "R1").reason());
        Report delivered = w.report();
        assertEquals("100.00", delivered.revenue().toPlainString());
        assertEquals("60.00", delivered.serviceCogs().toPlainString());
        assertEquals("200.00", delivered.refundObligation().toPlainString());
        assertEquals("0.00", delivered.receivables().toPlainString());
        assertEquals("390.00", delivered.companyCash().toPlainString());
        assertEquals("290.00", delivered.equity().toPlainString());
        assertEquals("490.00", delivered.assets().toPlainString());
        assertTrue(delivered.balanced());

        w.terminate(15L * DAY, "K1");
        assertEquals("200.00", w.report().refundOverdue().toPlainString());
        assertEquals("100.00", w.report().revenue().toPlainString());
    }

    // ---------------------------------------------------------------- 2 签约不是收入

    @Test
    void signingCreatesLiabilityNotRevenue() {
        World w = ready(new Cfg());
        assertEquals("SIGNED", w.sign(15L * DAY, "K1", 300L, "300.00").reason());
        Report r = w.report();
        assertEquals("0.00", r.revenue().toPlainString());
        assertEquals("300.00", r.refundObligation().toPlainString());
        assertEquals("0.00", r.serviceCogs().toPlainString());
        assertEquals("450.00", r.companyCash().toPlainString());
        assertEquals("250.00", r.equity().toPlainString());
        assertTrue(r.balanced());
    }

    // ---------------------------------------------------------------- 3 阶段缺钱

    @Test
    void validationShortOfCashLeavesNothing() {
        World w = new World(new Cfg().build(), cash("120.00", "1000.00", "400.00"));
        assertEquals("STAGE_BEGUN", w.begin(0L, Stage.PILOT, "p1").reason());
        assertEquals("STAGE_PASSED", w.complete(5L * DAY, Stage.PILOT, "p1c", DAY).reason());
        Outcome deniedValidation = w.begin(5L * DAY, Stage.VALIDATION, "v1");
        assertEquals("INSUFFICIENT_CASH", deniedValidation.reason());
        assertNull(w.state.stageOf(Stage.VALIDATION));
        assertEquals("70.00", w.report().companyCash().toPlainString());
        assertEquals("0.00", w.report().wip().toPlainString());
        assertTrue(w.report().balanced());
    }

    // ---------------------------------------------------------------- 4 退款失败与补足

    @Test
    void refundFailsAtOneFiftyThenSucceedsAfterRealTopUp() {
        Cfg cfg = new Cfg();
        cfg.unitCost = money("3.00");
        World w = ready(cfg);
        w.sign(15L * DAY, "K1", 300L, "300.00");
        w.deliver(15L * DAY, "K1", 100L, "R1");
        w.terminate(15L * DAY, "K1");
        assertEquals("150.00", w.report().companyCash().toPlainString());
        assertEquals("200.00", w.report().refundObligation().toPlainString());
        assertEquals("50.00", w.report().equity().toPlainString());

        Outcome denied = w.refund(15L * DAY, "K1", "200.00", "rf1");
        assertEquals("INSUFFICIENT_CASH", denied.reason());
        assertEquals("200.00", w.report().refundObligation().toPlainString());
        assertEquals("200.00", w.report().refundOverdue().toPlainString());
        assertEquals("150.00", w.report().companyCash().toPlainString());

        assertEquals("CAPITAL_INJECTED", w.inject(15L * DAY, "100.00", "in1").reason());
        assertEquals("250.00", w.report().companyCash().toPlainString());
        assertEquals("150.00", w.report().equity().toPlainString());

        Outcome refunded = w.refund(15L * DAY, "K1", "200.00", "rf2");
        assertEquals("REFUNDED", refunded.reason());
        Report after = w.report();
        assertEquals("0.00", after.refundObligation().toPlainString());
        assertEquals("0.00", after.refundOverdue().toPlainString());
        assertEquals("50.00", after.companyCash().toPlainString());
        assertEquals("100.00", after.revenue().toPlainString());
        assertEquals("300.00", after.serviceCogs().toPlainString());
        assertEquals("150.00", after.equity().toPlainString());
        assertTrue(after.balanced());
        assertEquals("REFUND_EXCEEDS_LIABILITY", w.refund(15L * DAY, "K1", "1.00", "rf3")
                .reason());
    }

    // ---------------------------------------------------------------- 5 终止只释放未交付

    @Test
    void terminationReleasesOnlyUndeliveredDemand() {
        World w = ready(new Cfg());
        w.sign(15L * DAY, "K1", 300L, "300.00");
        w.deliver(15L * DAY, "K1", 100L, "R1");
        w.terminate(15L * DAY, "K1");
        assertEquals(100L, w.state.deliveredIn(0L));
        assertEquals(0L, w.state.reservedIn(0L));

        Outcome tooMuch = w.sign(15L * DAY, "K2", 201L, "0.00");
        assertEquals("DEMAND_EXCEEDED", tooMuch.reason());
        Outcome fits = w.sign(15L * DAY, "K2", 200L, "0.00");
        assertEquals("SIGNED", fits.reason());
        assertEquals("200.00", w.report().refundObligation().toPlainString());
        assertEquals("0.00", w.state.contracts().get("K2").unearned().toPlainString());
    }

    // ---------------------------------------------------------------- 6 容量有限

    @Test
    void serviceCapacityAndPeriodAreFinite() {
        World w = ready(new Cfg());
        w.sign(15L * DAY, "K1", 300L, "300.00");
        assertEquals("DELIVERED", w.deliver(15L * DAY, "K1", 200L, "R1").reason());
        assertEquals("SERVICE_CAPACITY", w.deliver(15L * DAY, "K1", 1L, "R2").reason());
        assertEquals(200L, w.state.deliveredIn(0L));
        assertEquals("200.00", w.report().revenue().toPlainString());
    }

    // ---------------------------------------------------------------- 7 阶段失败即停

    @Test
    void stageFailureIsTerminalWithoutRetry() {
        Cfg cfg = new Cfg();
        cfg.pilotPasses = false;
        World w = world(cfg);
        w.begin(0L, Stage.PILOT, "p1");
        assertEquals("STAGE_FAILED", w.complete(5L * DAY, Stage.PILOT, "p1c", DAY).reason());
        assertTrue(w.state.researchFailed());
        assertEquals("RESEARCH_FAILED", w.begin(6L * DAY, Stage.PILOT, "p2").reason());
        assertEquals("RESEARCH_FAILED", w.begin(6L * DAY, Stage.VALIDATION, "v1").reason());
        assertEquals("50.00", w.report().pilotExpense().toPlainString());
        assertTrue(w.report().balanced());

        Cfg failing = new Cfg();
        failing.validationPasses = false;
        World v = world(failing);
        v.begin(0L, Stage.PILOT, "p1");
        v.complete(5L * DAY, Stage.PILOT, "p1c", DAY);
        v.begin(5L * DAY, Stage.VALIDATION, "v1");
        assertEquals("STAGE_FAILED", v.complete(15L * DAY, Stage.VALIDATION, "v1c",
                6L * DAY).reason());
        assertEquals("0.00", v.report().wip().toPlainString());
        assertEquals("0.00", v.report().platformNet().toPlainString());
        assertEquals("100.00", v.report().impairment().toPlainString());
        assertFalse(v.report().validated());
        assertTrue(v.report().balanced());
    }

    // ---------------------------------------------------------------- 8 幂等/篡参数/时间

    @Test
    void idempotencyTamperAndTimeRewind() {
        World w = world(new Cfg());
        assertTrue(w.begin(0L, Stage.PILOT, "p1").accepted());
        assertEquals("REPLAYED:STAGE_BEGUN", w.begin(0L, Stage.PILOT, "p1").reason());
        assertEquals("250.00", w.report().companyCash().toPlainString());
        assertEquals("COMMAND_CONTENT_CONFLICT",
                w.begin(0L, Stage.VALIDATION, "p1").reason());
        assertEquals("TIME_REWIND", w.begin(-1L, Stage.PILOT, "p9").reason());

        Outcome wrongActor = w.apply(DigitalServiceModel.beginStage(w.state, 0L, "intruder",
                "p2", Stage.PILOT, w.funds, payer("CO", "CON")));
        assertEquals("NOT_AUTHORIZED", wrongActor.reason());
        assertEquals("250.00", w.report().companyCash().toPlainString());

        Outcome early = w.complete(DAY, Stage.PILOT, "p1e", 0L);
        assertEquals("STAGE_NOT_DUE", early.reason());
        Outcome badEvidence = w.complete(5L * DAY, Stage.PILOT, "p1f", 6L * DAY);
        assertEquals("EVIDENCE_INVALID", badEvidence.reason());
    }

    // ---------------------------------------------------------------- 9 审批顺序

    @Test
    void signingNeedsValidationAndApproval() {
        World w = world(new Cfg());
        assertEquals("NOT_VALIDATED", w.sign(0L, "K1", 10L, "10.00").reason());
        w.begin(0L, Stage.PILOT, "p1");
        w.complete(5L * DAY, Stage.PILOT, "p1c", DAY);
        w.begin(5L * DAY, Stage.VALIDATION, "v1");
        w.complete(15L * DAY, Stage.VALIDATION, "v1c", 6L * DAY);
        assertEquals("NO_APPROVAL", w.sign(15L * DAY, "K1", 10L, "10.00").reason());
        assertEquals("APPROVAL_INVALID", w.approve(15L * DAY, "ap0", "NOPE").reason());
        assertEquals("APPROVED", w.approve(15L * DAY, "ap1", "AP-COMM").reason());
        assertEquals("ID_REUSED", w.approve(15L * DAY, "ap2", "AP-COMM").reason());
        assertEquals("SIGNED", w.sign(15L * DAY, "K1", 10L, "10.00").reason());
        assertEquals("PREPAID_EXCEEDS_CONTRACT",
                w.sign(15L * DAY, "K2", 10L, "11.00").reason());
    }

    // ---------------------------------------------------------------- 10 long 溢出

    @Test
    void longOverflowIsRejectedControlled() {
        Cfg cfg = new Cfg();
        cfg.price = new BigDecimal("0.0001");
        cfg.demand = Long.MAX_VALUE;
        cfg.capacity = Long.MAX_VALUE;
        cfg.budget = money("999999999999999999.99");
        World w = new World(cfg.build(), cash("300.00", "1000000000000000000.00", "400.00"));
        w.begin(0L, Stage.PILOT, "p1");
        w.complete(5L * DAY, Stage.PILOT, "p1c", DAY);
        w.begin(5L * DAY, Stage.VALIDATION, "v1");
        w.complete(15L * DAY, Stage.VALIDATION, "v1c", 6L * DAY);
        w.approve(15L * DAY, "ap1", "AP-COMM");
        assertEquals("SIGNED", w.sign(15L * DAY, "K1", Long.MAX_VALUE,
                "922337203685477.58").reason());
        Outcome overflow = w.sign(15L * DAY, "K2", 1L, "0.00");
        assertEquals("QUANTITY_OVERFLOW", overflow.reason());
    }

    // ---------------------------------------------------------------- 11 非整除分摊

    @Test
    void nonDivisiblePrepaidAllocationClosesExactly() {
        Cfg cfg = new Cfg();
        cfg.price = new BigDecimal("0.43");
        cfg.unitCost = money("0.20");
        cfg.demand = 10L;
        cfg.budget = money("10.00");
        cfg.capacity = 10L;
        World w = ready(cfg);
        assertEquals("SIGNED", w.sign(15L * DAY, "K1", 7L, "2.00").reason());
        assertEquals("DELIVERED", w.deliver(15L * DAY, "K1", 3L, "R1").reason());
        assertEquals("0.43", w.report().receivables().toPlainString());
        assertEquals("1.29", w.report().revenue().toPlainString());
        assertEquals("DELIVERED", w.deliver(15L * DAY, "K1", 4L, "R2").reason());
        Report done = w.report();
        assertEquals("0.00", done.refundObligation().toPlainString());
        assertEquals("3.01", done.revenue().toPlainString());
        assertEquals("1.01", done.receivables().toPlainString());
        assertEquals("1.40", done.serviceCogs().toPlainString());
        assertEquals("0.00", w.state.contracts().get("K1").unearned().toPlainString());
        assertTrue(done.balanced());

        assertEquals("RECEIVABLE_NOT_DUE", w.collect(15L * DAY, "R1").reason());
        w.close(30L * DAY, 0L);
        assertEquals("PAYMENT_RECEIVED", w.collect(31L * DAY, "R1").reason());
        assertEquals("0.58", w.report().receivables().toPlainString());
        assertTrue(w.report().balanced());
    }

    // ---------------------------------------------------------------- 12 负权益

    @Test
    void negativeEquityStaysBalanced() {
        World w = new World(new Cfg().build(), cash("40.00", "1000.00", "400.00"));
        Outcome denied = w.begin(0L, Stage.PILOT, "p1");
        assertEquals("INSUFFICIENT_CASH", denied.reason());
        assertEquals("40.00", w.report().equity().toPlainString());
        assertTrue(w.report().balanced());

        Cfg heavy = new Cfg();
        heavy.fixedExpense = money("200.00");
        World rich = world(heavy);
        rich.begin(0L, Stage.PILOT, "p1");
        rich.complete(5L * DAY, Stage.PILOT, "p1c", DAY);
        assertEquals("PERIOD_CLOSED", rich.close(30L * DAY, 0L).reason());
        assertEquals("PERIOD_CLOSED", rich.close(60L * DAY, 1L).reason());
        Report r = rich.report();
        assertEquals("-150.00", r.equity().toPlainString());
        assertEquals("150.00", r.payables().toPlainString());
        assertEquals("0.00", r.companyCash().toPlainString());
        assertTrue(r.balanced());
    }

    // ---------------------------------------------------------------- 13 跨期交付仍受当期额度

    @Test
    void crossPeriodDeliveryCannotExceedCurrentPeriodDemand() {
        World w = ready(new Cfg());
        assertEquals("SIGNED", w.sign(16L * DAY, "C1", 300L, "0.00").reason());
        assertEquals("PERIOD_CLOSED", w.close(30L * DAY, 0L).reason());
        assertEquals("SIGNED", w.sign(31L * DAY, "C2", 300L, "0.00").reason());

        Outcome denied = w.deliver(31L * DAY, "C1", 200L, "R1");
        assertEquals("DEMAND_EXCEEDED", denied.reason());
        assertEquals(0L, w.state.contracts().get("C1").delivered());
        assertEquals(0L, w.state.deliveredIn(1L));
        assertEquals(300L, w.state.reservedIn(1L));
        assertEquals("0.00", w.report().revenue().toPlainString());
        assertEquals("0.00", w.report().refundObligation().toPlainString());
        assertTrue(w.report().balanced());

        // 释放当期预约后，同一笔跨期交付在额度内即可成功（不擅自改合同或清债）。
        assertEquals("TERMINATED", w.terminate(31L * DAY, "C2").reason());
        assertEquals("DELIVERED", w.deliver(31L * DAY, "C1", 200L, "R2").reason());
        assertEquals(200L, w.state.deliveredIn(1L));
        assertEquals("200.00", w.report().revenue().toPlainString());
        assertTrue(w.report().balanced());
    }

    @Test
    void crossPeriodDeliveryCannotExceedCurrentPeriodBudget() {
        Cfg cfg = new Cfg();
        cfg.demand = 1000L;
        cfg.budget = money("300.00");
        cfg.capacity = 1000L;
        World w = ready(cfg);
        assertEquals("SIGNED", w.sign(16L * DAY, "C1", 200L, "0.00").reason());
        assertEquals("PERIOD_CLOSED", w.close(30L * DAY, 0L).reason());
        assertEquals("SIGNED", w.sign(31L * DAY, "C2", 200L, "0.00").reason());

        assertEquals("BUDGET_EXCEEDED", w.deliver(31L * DAY, "C1", 200L, "R1").reason());
        assertEquals(0L, w.state.deliveredIn(1L));
        assertEquals("0.00", w.report().revenue().toPlainString());

        Cfg roomy = new Cfg();
        roomy.demand = 1000L;
        roomy.budget = money("500.00");
        roomy.capacity = 1000L;
        World ok = ready(roomy);
        ok.sign(16L * DAY, "C1", 200L, "0.00");
        ok.close(30L * DAY, 0L);
        ok.sign(31L * DAY, "C2", 200L, "0.00");
        assertEquals("DELIVERED", ok.deliver(31L * DAY, "C1", 200L, "R1").reason());
        assertEquals("200.00", ok.report().revenue().toPlainString());
    }

    @Test
    void samePeriodDeliveryConvertsReservationWithoutDoubleCounting() {
        Cfg cfg = new Cfg();
        cfg.demand = 300L;
        cfg.budget = money("300.00");
        cfg.capacity = 300L;
        World w = ready(cfg);
        assertEquals("SIGNED", w.sign(15L * DAY, "K1", 300L, "300.00").reason());
        assertEquals("DELIVERED", w.deliver(15L * DAY, "K1", 200L, "R1").reason());
        assertEquals(300L, w.state.deliveredIn(0L) + w.state.reservedIn(0L));
        assertEquals("DELIVERED", w.deliver(15L * DAY, "K1", 100L, "R2").reason());
        assertEquals(300L, w.state.deliveredIn(0L));
        assertEquals(0L, w.state.reservedIn(0L));
        assertEquals("300.00", w.report().revenue().toPlainString());
        assertEquals("DEMAND_EXCEEDED", w.sign(15L * DAY, "K2", 1L, "0.00").reason());
        assertTrue(w.report().balanced());
    }
}
