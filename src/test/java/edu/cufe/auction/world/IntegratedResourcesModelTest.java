package edu.cufe.auction.world;

import edu.cufe.auction.world.IntegratedResourcesModel.Config;
import edu.cufe.auction.world.IntegratedResourcesModel.JobStatus;
import edu.cufe.auction.world.IntegratedResourcesModel.Outcome;
import edu.cufe.auction.world.IntegratedResourcesModel.Report;
import edu.cufe.auction.world.IntegratedResourcesModel.Seam;
import edu.cufe.auction.world.IntegratedResourcesModel.State;
import edu.cufe.auction.world.bank.FundState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static edu.cufe.auction.world.PureCompanyTestSupport.account;
import static edu.cufe.auction.world.PureCompanyTestSupport.funds;
import static edu.cufe.auction.world.PureCompanyTestSupport.money;
import static edu.cufe.auction.world.PureCompanyTestSupport.payer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一体化资源模型的最小验收：两段作业（begin/finish）的普通流、失败/缺钱、时间/幂等/篡参数、
 * 有限需求预算运力、负权益、非整除分摊、long 溢出、错主体与资金半写、部分燃料多批次成本、
 * CIP/爬坡/退出、失败残值真实付款，以及“begin 之后拿不到未来煤/电”的永久反例。
 */
class IntegratedResourcesModelTest {

    private static final long DAY = IntegratedResourcesModel.DAY_MILLIS;
    private static final long PERIOD = IntegratedResourcesModel.PERIOD_MILLIS;
    private static final String CO = "company";

    /** 配置构造器：只在本测试里用，参数与合同 EXP 一致。 */
    private static final class Cfg {

        private BigDecimal internalPrice = new BigDecimal("0.06");
        private BigDecimal coalCostA = new BigDecimal("0.03");
        private BigDecimal coalCostB = new BigDecimal("0.07");
        private long seamA = 600_000L;
        private long seamB = 0L;
        private long mineCapacity = 400_000L;
        private long generatorCapacity = 200L;
        private long transport = 500_000L;
        private long coalDemand = 200_000L;
        private BigDecimal coalBudget = money("100000.00");
        private long gridDemand = 100L;
        private BigDecimal gridBudget = money("20000.00");
        private long coalKgPerMwh = 400L;
        private BigDecimal coalPrice = new BigDecimal("0.10");
        private BigDecimal powerPrice = money("80.00");
        private BigDecimal conversion = money("5.00");
        private BigDecimal fixedExpense = money("1000.00");
        private BigDecimal depreciation = money("500.00");
        private BigDecimal residualRate = new BigDecimal("0.25");
        private BigDecimal equipment = money("20000.00");

        private Cfg() {
        }

        private Config build() {
            List<Seam> seams = seamB > 0
                    ? List.of(new Seam("SEAM-A", seamA, coalCostA),
                              new Seam("SEAM-B", seamB, coalCostB))
                    : List.of(new Seam("SEAM-A", seamA, coalCostA));
            return new Config(CO, "CO", "OPS", "CUS", "CUS", "GRID", "GRID", "CONV", "CONV",
                    "CON", "CON", "BUY", "BUY", "SPON", "SPON", equipment, internalPrice,
                    coalPrice, powerPrice, coalKgPerMwh, conversion, mineCapacity,
                    generatorCapacity, transport, coalDemand, coalBudget, gridDemand, gridBudget,
                    fixedExpense, depreciation, residualRate, seams,
                    Map.of("AP-CAPEX", new IntegratedResourcesModel.Approval("AP-CAPEX",
                                    IntegratedResourcesModel.APPROVAL_CAPEX, 0L),
                            "AP-EXIT", new IntegratedResourcesModel.Approval("AP-EXIT",
                                    IntegratedResourcesModel.APPROVAL_EXIT, 0L)));
        }
    }

    /** 全局现金与固定资金腿。 */
    private static final class World {

        private final Config config;
        private State state;
        private FundState funds;
        private final PureCompanyTestSupport.Payer coToOps = payer("CO", "OPS");
        private final PureCompanyTestSupport.Payer coToConv = payer("CO", "CONV");
        private final PureCompanyTestSupport.Payer cusToCo = payer("CUS", "CO");
        private final PureCompanyTestSupport.Payer gridToCo = payer("GRID", "CO");
        private final PureCompanyTestSupport.Payer buyToCo = payer("BUY", "CO");

        private World(Config config, Map<String, CashLedger.CashAccount> cash) {
            this.config = config;
            this.funds = funds(cash);
            this.state = IntegratedResourcesModel.open(config, funds);
        }

        private Outcome apply(Outcome outcome) {
            state = outcome.state();
            funds = outcome.funds();
            return outcome;
        }

        private Outcome beginExtract(long now, String job, String seam, long kg, String command) {
            return beginExtract(now, job, seam, kg, command, coToOps);
        }

        private Outcome beginExtract(long now, String job, String seam, long kg, String command,
                                     FundPayer custom) {
            return apply(IntegratedResourcesModel.beginExtraction(state, now, CO, command, job,
                    seam, kg, funds, custom));
        }

        private Outcome finishExtract(long now, String job, String command) {
            return apply(IntegratedResourcesModel.finishExtraction(state, now, CO, command, job,
                    funds));
        }

        private Outcome beginPower(long now, String job, long mwh, String receivable,
                                   String command) {
            return apply(IntegratedResourcesModel.beginPowerDelivery(state, now, CO, command, job,
                    mwh, receivable, funds, coToConv));
        }

        private Outcome finishPower(long now, String job, String command) {
            return apply(IntegratedResourcesModel.finishPowerDelivery(state, now, CO, command,
                    job, funds));
        }

        private Outcome abort(long now, String job, String command) {
            return apply(IntegratedResourcesModel.abortJob(state, now, CO, command, job, funds));
        }

        private Outcome sellCoal(long now, long kg, String receivable) {
            return apply(IntegratedResourcesModel.sellCoal(state, now, CO, "sell-" + receivable,
                    "d-" + receivable, kg, receivable, funds));
        }

        private Outcome transfer(long now, long kg, String id) {
            return apply(IntegratedResourcesModel.transferToPlant(state, now, CO, "tr-" + id,
                    id, kg, funds));
        }

        private Outcome collect(long now, String receivable) {
            return collect(now, receivable, "co-" + receivable);
        }

        private Outcome collect(long now, String receivable, String command) {
            return apply(IntegratedResourcesModel.collect(state, now, CO, command,
                    receivable, funds, receivable.contains("POWER") ? gridToCo : cusToCo));
        }

        private Outcome close(long now, long period) {
            return close(now, period, "cl-" + period);
        }

        private Outcome close(long now, long period, String command) {
            return apply(IntegratedResourcesModel.closePeriod(state, now, CO, command, period,
                    funds, coToOps));
        }

        private Outcome exit(long now, String command) {
            return apply(IntegratedResourcesModel.exitBusiness(state, now, CO, command, "AP-EXIT",
                    funds));
        }

        private Report report() {
            return IntegratedResourcesModel.validate(state, funds);
        }

        /** 开工 + 到期完成。 */
        private void mine(long now, String job, String seam, long kg) {
            assertEquals("EXTRACTION_BEGUN",
                    beginExtract(now, job, seam, kg, "b-" + job).reason());
            assertEquals("EXTRACTED",
                    finishExtract(now + DAY, job, "f-" + job).reason());
        }
    }

    private static Map<String, CashLedger.CashAccount> cash(String company, String buyer) {
        Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
        map.put("CO", account(CO, company));
        map.put("OPS", account("ops", "0.00"));
        map.put("CUS", account("customer", "20000.00"));
        map.put("GRID", account("grid", "20000.00"));
        map.put("CONV", account("converter", "0.00"));
        map.put("CON", account("contractor", "0.00"));
        map.put("BUY", account("buyer", buyer));
        map.put("SPON", account("sponsor", "1000.00"));
        return map;
    }

    private static World world(Cfg cfg) {
        return new World(cfg.build(), cash("50000.00", "3000.00"));
    }

    /** 跑完合同 M1 的四个动作（两段作业），返回停在工作状态。 */
    private static World runM1(Cfg cfg) {
        World w = world(cfg);
        w.mine(0L, "E1", "SEAM-A", 400_000L);
        assertEquals("COAL_DELIVERED", w.sellCoal(DAY + 1L, 200_000L, "AR-COAL").reason());
        assertEquals("TRANSFERRED", w.transfer(DAY + 2L, 100_000L, "T1").reason());
        assertEquals("POWER_BEGUN", w.beginPower(DAY + 3L, "P1", 100L, "AR-POWER",
                "bp1").reason());
        assertEquals("POWER_DELIVERED", w.finishPower(2L * DAY + 3L, "P1", "fp1").reason());
        return w;
    }

    // ---------------------------------------------------------------- 1 普通

    @Test
    void normalFlowKeepsMineAndPlantSeparateAndBalances() {
        World w = runM1(new Cfg());
        Report r = w.report();
        assertEquals("37500.00", r.companyCash().toPlainString());
        assertEquals(100_000L, r.mineCoalKg());
        assertEquals("3000.00", r.mineCoal().toPlainString());
        assertEquals(60_000L, r.plantFuelKg());
        assertEquals("1800.00", r.plantFuel().toPlainString());
        assertEquals("28000.00", r.receivables().toPlainString());
        assertEquals("20000.00", r.equipmentNet().toPlainString());
        assertEquals("0.00", r.wip().toPlainString());
        assertEquals(0L, r.openJobs());
        assertEquals("90300.00", r.assets().toPlainString());
        assertEquals("90300.00", r.equity().toPlainString());
        assertEquals("28000.00", r.revenue().toPlainString());
        assertEquals("7700.00", r.cogs().toPlainString());
        assertEquals(200_000L, r.coalDeliveredKg());
        assertEquals(100L, r.powerDeliveredMwh());
        assertEquals(300_000L, r.transportUsedKg());
        assertTrue(r.balanced());

        w.close(PERIOD, 0L);
        Report closed = w.report();
        assertEquals("36500.00", closed.companyCash().toPlainString());
        assertEquals("19500.00", closed.equipmentNet().toPlainString());
        assertEquals("88800.00", closed.assets().toPlainString());
        assertEquals("88800.00", closed.equity().toPlainString());
        assertEquals("1000.00", closed.fixedExpense().toPlainString());
        assertEquals("500.00", closed.depreciation().toPlainString());
        w.collect(PERIOD, "AR-COAL");
        w.collect(PERIOD, "AR-POWER");
        Report collected = w.report();
        assertEquals("64500.00", collected.companyCash().toPlainString());
        assertEquals("0.00", collected.receivables().toPlainString());
        assertEquals("28000.00", collected.revenue().toPlainString());
        assertEquals("88800.00", collected.assets().toPlainString());
        assertTrue(collected.balanced());
    }

    // ---------------------------------------------------------------- 2 内部价只动段

    @Test
    void internalPriceMovesSegmentProfitOnly() {
        Cfg low = new Cfg();
        Cfg mid = new Cfg();
        mid.internalPrice = new BigDecimal("0.09");
        Cfg high = new Cfg();
        high.internalPrice = BigDecimal.ONE;

        Report a = runM1(low).report();
        Report b = runM1(mid).report();
        Report c = runM1(high).report();

        assertEquals("17000.00", a.segments().coalSegmentProfit().toPlainString());
        assertEquals("5100.00", a.segments().powerSegmentProfit().toPlainString());
        assertEquals("1800.00", a.segments().unrealizedInternalProfit().toPlainString());
        assertEquals("20000.00", b.segments().coalSegmentProfit().toPlainString());
        assertEquals("3900.00", b.segments().powerSegmentProfit().toPlainString());
        assertEquals("3600.00", b.segments().unrealizedInternalProfit().toPlainString());
        assertEquals("111000.00", c.segments().coalSegmentProfit().toPlainString());
        assertEquals("-32500.00", c.segments().powerSegmentProfit().toPlainString());
        assertEquals("58200.00", c.segments().unrealizedInternalProfit().toPlainString());

        for (Report report : List.of(a, b, c)) {
            assertEquals("20300.00", report.segments().consolidatedGrossProfit()
                    .toPlainString());
            assertEquals("20300.00", report.revenue().subtract(report.cogs()).toPlainString());
            assertEquals("37500.00", report.companyCash().toPlainString());
            assertEquals("90300.00", report.assets().toPlainString());
            assertEquals("90300.00", report.equity().toPlainString());
            assertEquals("1800.00", report.plantFuel().toPlainString());
            assertTrue(report.balanced());
        }
    }

    // ---------------------------------------------------------------- 3 部分燃烧

    @Test
    void partialBurnCarriesOriginalCostAndOffsetsUnrealizedExactly() {
        World w = runM1(new Cfg());
        State s = w.state;
        assertEquals(60_000L, s.plantFuel().quantity());
        assertEquals("1800.00", s.plantFuel().amount().toPlainString());
        assertEquals("3000.00", s.mineCoal().amount().toPlainString());
        Report r = w.report();
        assertEquals("1200.00", s.totals().burnedFuelCost().toPlainString());
        assertEquals("6000.00", s.totals().coalCogs().toPlainString());
        assertEquals("500.00", s.totals().conversionCost().toPlainString());
        assertEquals("0.00", r.segments().consolidatedGrossProfit()
                .subtract(r.segments().coalSegmentProfit())
                .subtract(r.segments().powerSegmentProfit())
                .add(r.segments().unrealizedInternalProfit()).toPlainString());
    }

    // ---------------------------------------------------------------- 4 有限需求/预算/运力

    @Test
    void demandBudgetAndTransportNeverRevive() {
        Cfg cfg = new Cfg();
        cfg.coalDemand = 400_000L;
        cfg.coalBudget = money("20000.00");
        World w = world(cfg);
        w.mine(0L, "E1", "SEAM-A", 400_000L);
        assertEquals("COAL_DELIVERED", w.sellCoal(DAY + 1L, 200_000L, "AR-COAL").reason());
        w.collect(16L * DAY + 2L, "AR-COAL");
        Outcome budget = w.sellCoal(16L * DAY + 3L, 1L, "AR-COAL2");
        assertEquals("BUDGET_EXCEEDED", budget.reason());
        assertEquals(200_000L, w.report().coalDeliveredKg());
        assertEquals("RECEIVABLE_CLOSED", w.collect(16L * DAY + 4L, "AR-COAL", "co2-AR-COAL")
                .reason());

        Cfg narrow = new Cfg();
        narrow.transport = 300_000L;
        World t = world(narrow);
        t.mine(0L, "E1", "SEAM-A", 400_000L);
        t.sellCoal(DAY + 1L, 200_000L, "AR-COAL");
        t.transfer(DAY + 2L, 100_000L, "T1");
        Outcome over = t.transfer(DAY + 3L, 1L, "T2");
        assertEquals("TRANSPORT_CAPACITY", over.reason());
        assertEquals(300_000L, t.report().transportUsedKg());
    }

    // ---------------------------------------------------------------- 5 需求不足不先烧

    @Test
    void powerDemandShortDoesNotBurnFuelOrCash() {
        Cfg cfg = new Cfg();
        cfg.gridDemand = 0L;
        World w = world(cfg);
        w.mine(0L, "E1", "SEAM-A", 400_000L);
        w.transfer(DAY + 1L, 100_000L, "T1");
        String cashBefore = w.funds.cash("CO").toPlainString();
        Outcome o = w.beginPower(DAY + 2L, "P1", 1L, "AR-POWER", "bp1");
        assertEquals("DEMAND_EXCEEDED", o.reason());
        assertEquals(100_000L, w.state.plantFuel().quantity());
        assertEquals("3000.00", w.state.plantFuel().amount().toPlainString());
        assertEquals(cashBefore, w.funds.cash("CO").toPlainString());
        assertTrue(w.state.receivables().isEmpty());
        assertEquals("0.00", w.report().wip().toPlainString());
        assertNull(w.state.runningJob());
    }

    // ---------------------------------------------------------------- 6 批准与缺钱

    @Test
    void missingApprovalAndShortCashLeaveNoCip() {
        World w = world(new Cfg());
        Outcome noApproval = w.apply(IntegratedResourcesModel.startExpansion(w.state, 0L, CO,
                "e1", "PRJ1", money("10000.00"), 5L * DAY, 2, 200_000L, "", w.funds,
                payer("CO", "CON")));
        assertEquals("NO_APPROVAL", noApproval.reason());
        assertTrue(w.state.expansions().isEmpty());

        Outcome deniedCapex = w.apply(IntegratedResourcesModel.startExpansion(w.state, 0L, CO,
                "e2", "PRJ1", money("1000000.00"), 5L * DAY, 2, 200_000L, "AP-CAPEX",
                w.funds, payer("CO", "CON")));
        assertEquals("INSUFFICIENT_CASH", deniedCapex.reason());
        assertTrue(w.state.expansions().isEmpty());
        assertEquals("50000.00", w.report().companyCash().toPlainString());
        assertEquals("0.00", w.report().cip().toPlainString());

        Outcome badRef = w.apply(IntegratedResourcesModel.startExpansion(w.state, 0L, CO,
                "e3", "PRJ1", money("10000.00"), 5L * DAY, 2, 200_000L, "NOPE",
                w.funds, payer("CO", "CON")));
        assertEquals("APPROVAL_INVALID", badRef.reason());

        World broke = new World(new Cfg().build(), cash("0.00", "0.00"));
        Outcome shortBegin = broke.beginExtract(0L, "E1", "SEAM-A", 1000L, "b1");
        assertEquals("INSUFFICIENT_CASH", shortBegin.reason());
        assertNull(broke.state.runningJob());
        assertEquals("0.00", broke.report().wip().toPlainString());
        assertTrue(broke.report().balanced());
    }

    // ---------------------------------------------------------------- 7 时间/幂等/篡参数

    @Test
    void twoPhaseTimingIdempotencyAndTamper() {
        World w = world(new Cfg());
        Outcome begun = w.beginExtract(0L, "E1", "SEAM-A", 1000L, "b1");
        assertEquals("EXTRACTION_BEGUN", begun.reason());
        assertEquals(0L, w.state.mineCoal().quantity());
        assertEquals("30.00", w.report().wip().toPlainString());
        assertEquals("49970.00", w.report().companyCash().toPlainString());

        assertEquals("REPLAYED:EXTRACTION_BEGUN",
                w.beginExtract(0L, "E1", "SEAM-A", 1000L, "b1").reason());
        assertEquals("49970.00", w.report().companyCash().toPlainString());
        assertEquals("COMMAND_CONTENT_CONFLICT",
                w.beginExtract(0L, "E1", "SEAM-A", 900L, "b1").reason());
        assertEquals("ID_REUSED", w.beginExtract(DAY, "E1", "SEAM-A", 500L, "b4").reason());
        assertEquals("ACTION_BUSY", w.beginExtract(0L, "E2", "SEAM-A", 500L, "b2").reason());
        assertEquals("TIME_REWIND", w.beginExtract(-1L, "E3", "SEAM-A", 500L, "b3").reason());

        Outcome early = w.finishExtract(DAY - 1L, "E1", "f1");
        assertEquals("JOB_NOT_DUE", early.reason());
        assertEquals(0L, w.state.mineCoal().quantity());
        assertEquals("30.00", w.report().wip().toPlainString());
        assertEquals("49970.00", w.report().companyCash().toPlainString());

        Outcome done = w.finishExtract(DAY, "E1", "f1");
        assertEquals("EXTRACTED", done.reason());
        assertEquals(1000L, w.state.mineCoal().quantity());
        assertEquals("30.00", w.state.mineCoal().amount().toPlainString());
        assertEquals("0.00", w.report().wip().toPlainString());
        assertEquals(JobStatus.DONE, w.state.jobOf("E1").status());

        assertEquals("REPLAYED:EXTRACTED", w.finishExtract(DAY, "E1", "f1").reason());
        assertEquals("JOB_STATE_INVALID", w.finishExtract(DAY, "E1", "f2").reason());
        assertEquals("UNKNOWN_JOB", w.finishExtract(DAY, "NOPE", "f3").reason());
        assertEquals("JOB_KIND_MISMATCH", w.finishPower(DAY, "E1", "f4").reason());
        assertEquals(1000L, w.state.mineCoal().quantity());
    }

    // ---------------------------------------------------------------- 8 错主体/资金

    @Test
    void wrongOwnerPayerCannotHalfWrite() {
        World w = world(new Cfg());
        String cashBefore = w.funds.cash("CO").toPlainString();
        String gridBefore = w.funds.cash("GRID").toPlainString();
        Outcome wrong = w.beginExtract(0L, "E1", "SEAM-A", 1000L, "b1", payer("GRID", "OPS"));
        assertEquals("FUND_OWNER_MISMATCH", wrong.reason());
        assertNull(w.state.runningJob());
        assertEquals(cashBefore, w.funds.cash("CO").toPlainString());
        assertEquals(gridBefore, w.funds.cash("GRID").toPlainString());
        assertTrue(w.state.jobs().isEmpty());
        assertTrue(w.state.commandContents().isEmpty());
    }

    // ---------------------------------------------------------------- 9 long 溢出

    @Test
    void longOverflowIsRejectedControlled() {
        World w = world(new Cfg());
        long mwh = Long.MAX_VALUE / 400L + 1L;
        Outcome overflow = w.beginPower(DAY, "P1", mwh, "AR-POWER", "bp1");
        assertEquals("QUANTITY_OVERFLOW", overflow.reason());
        assertNull(w.state.runningJob());
        assertEquals("50000.00", w.report().companyCash().toPlainString());

        Cfg wide = new Cfg();
        wide.mineCapacity = Long.MAX_VALUE;
        World capped = world(wide);
        Outcome expansion = capped.apply(IntegratedResourcesModel.startExpansion(capped.state,
                0L, CO, "e1", "PRJ1", money("10000.00"), 5L * DAY, 2, 1L, "AP-CAPEX",
                capped.funds, payer("CO", "CON")));
        assertEquals("CAPACITY_OVERFLOW", expansion.reason());
        assertTrue(capped.state.expansions().isEmpty());
    }

    // ---------------------------------------------------------------- 10 begin 不产出

    @Test
    void beginYieldsNoSellableCoalOrPowerRevenue() {
        World w = world(new Cfg());
        assertEquals("EXTRACTION_BEGUN",
                w.beginExtract(0L, "E1", "SEAM-A", 400_000L, "b1").reason());
        assertEquals("COAL_SHORT", w.sellCoal(0L, 1L, "AR-COAL").reason());
        assertEquals("COAL_SHORT", w.transfer(0L, 1L, "T1").reason());
        Report during = w.report();
        assertEquals(0L, during.mineCoalKg());
        assertEquals("0.00", during.revenue().toPlainString());
        assertEquals("0.00", during.receivables().toPlainString());
        assertEquals("12000.00", during.wip().toPlainString());
        assertEquals(1L, during.openJobs());
        assertEquals("38000.00", during.companyCash().toPlainString());
        assertTrue(during.balanced());

        w.finishExtract(DAY, "E1", "f1");
        w.transfer(DAY + 1L, 100_000L, "T1");
        assertEquals("POWER_BEGUN",
                w.beginPower(DAY + 2L, "P1", 100L, "AR-POWER", "bp1").reason());
        Report powerDuring = w.report();
        assertEquals("0.00", powerDuring.revenue().toPlainString());
        assertEquals("0.00", powerDuring.receivables().toPlainString());
        assertEquals(0L, powerDuring.powerDeliveredMwh());
        assertEquals("1700.00", powerDuring.wip().toPlainString());
        assertEquals(1L, powerDuring.openJobs());
        assertTrue(powerDuring.balanced());

        assertEquals("POWER_DELIVERED", w.finishPower(2L * DAY + 3L, "P1", "fp1").reason());
        assertEquals("8000.00", w.report().revenue().toPlainString());
        assertEquals("8000.00", w.report().receivables().toPlainString());
    }

    // ---------------------------------------------------------------- 11 跨期/WIP

    @Test
    void crossPeriodWipSurvivesCloseAndBelongsToFrozenPeriods() {
        World w = world(new Cfg());
        assertEquals("EXTRACTION_BEGUN",
                w.beginExtract(29L * DAY, "E1", "SEAM-A", 400_000L, "b1").reason());
        assertEquals("12000.00", w.report().wip().toPlainString());
        assertEquals("38000.00", w.report().companyCash().toPlainString());

        assertEquals("PERIOD_CLOSED", w.close(30L * DAY, 0L).reason());
        Report afterClose = w.report();
        assertEquals("12000.00", afterClose.wip().toPlainString());
        assertEquals(1L, afterClose.openJobs());
        assertEquals("37000.00", afterClose.companyCash().toPlainString());
        assertEquals("68500.00", afterClose.assets().toPlainString());
        assertEquals("68500.00", afterClose.equity().toPlainString());
        assertTrue(afterClose.balanced());
        assertEquals(400_000L, w.state.useOf(0L).extractedKg());
        assertEquals(0L, w.state.useOf(1L).extractedKg());

        assertEquals("EXTRACTED", w.finishExtract(30L * DAY, "E1", "f1").reason());
        Report afterFinish = w.report();
        assertEquals(400_000L, afterFinish.mineCoalKg());
        assertEquals("12000.00", afterFinish.mineCoal().toPlainString());
        assertEquals("0.00", afterFinish.wip().toPlainString());
        assertEquals(0L, afterFinish.openJobs());
        assertEquals("68500.00", afterFinish.assets().toPlainString());
        assertTrue(afterFinish.balanced());

        assertEquals("EXTRACTION_BEGUN",
                w.beginExtract(30L * DAY, "E2", "SEAM-A", 200_000L, "b2").reason());
    }

    // ---------------------------------------------------------------- 12 退出与失败终态

    @Test
    void exitBlockedByRunningJobAndFailureIsTerminal() {
        World w = world(new Cfg());
        w.beginExtract(0L, "E1", "SEAM-A", 1000L, "b1");
        assertEquals("JOB_IN_PROGRESS", w.exit(0L, "x1").reason());

        Outcome failed = w.abort(0L, "E1", "ab1");
        assertEquals("JOB_FAILED", failed.reason());
        assertEquals(JobStatus.FAILED, w.state.jobOf("E1").status());
        assertEquals("30.00", w.report().jobLoss().toPlainString());
        assertEquals(0L, w.state.mineCoal().quantity());
        assertEquals("0.00", w.report().wip().toPlainString());
        assertEquals("69970.00", w.report().equity().toPlainString());
        assertEquals("69970.00", w.report().assets().toPlainString());
        assertTrue(w.report().balanced());

        assertEquals("JOB_STATE_INVALID", w.abort(0L, "E1", "ab2").reason());
        assertEquals("ID_REUSED", w.beginExtract(DAY, "E1", "SEAM-A", 1000L, "b2").reason());

        assertEquals("EXITED", w.exit(DAY, "x2").reason());
        assertEquals("49970.00", w.report().assets().toPlainString());
        assertEquals("49970.00", w.report().equity().toPlainString());
        assertTrue(w.report().balanced());
        assertEquals("CLOSED", w.beginExtract(DAY, "E2", "SEAM-A", 100L, "b3").reason());
    }

    // ---------------------------------------------------------------- 13 发电失败退料

    @Test
    void powerFailureReturnsFuelAndReleasesReservation() {
        World w = world(new Cfg());
        w.mine(0L, "E1", "SEAM-A", 400_000L);
        w.transfer(DAY + 1L, 100_000L, "T1");
        assertEquals("POWER_BEGUN",
                w.beginPower(DAY + 2L, "P1", 100L, "AR-POWER", "bp1").reason());
        assertEquals(60_000L, w.state.plantFuel().quantity());
        assertEquals("1800.00", w.state.plantFuel().amount().toPlainString());
        assertEquals("1700.00", w.report().wip().toPlainString());
        assertEquals(100L, w.state.useOf(0L).powerReservedMwh());

        Outcome failed = w.abort(DAY + 3L, "P1", "ab1");
        assertEquals("JOB_FAILED", failed.reason());
        assertEquals(100_000L, w.state.plantFuel().quantity());
        assertEquals("3000.00", w.state.plantFuel().amount().toPlainString());
        assertEquals(0L, w.state.useOf(0L).powerReservedMwh());
        assertEquals("0.00", w.state.useOf(0L).powerReservedRevenue().toPlainString());
        assertEquals("500.00", w.report().jobLoss().toPlainString());
        assertEquals("0.00", w.report().revenue().toPlainString());
        assertEquals("0.00", w.report().receivables().toPlainString());
        assertEquals("0.00", w.report().wip().toPlainString());
        assertEquals("69500.00", w.report().equity().toPlainString());
        assertEquals("69500.00", w.report().assets().toPlainString());
        assertTrue(w.report().balanced());

        assertEquals("REPLAYED:JOB_FAILED", w.abort(DAY + 3L, "P1", "ab1").reason());
        assertEquals("ID_REUSED", w.beginPower(DAY + 3L, "P1", 100L, "AR-POWER2",
                "bp2").reason());
    }

    // ---------------------------------------------------------------- 14 扩产/退出

    @Test
    void expansionCipRampDepreciationAndExit() {
        Cfg cfg = new Cfg();
        cfg.seamA = 2_000_000L;
        World w = world(cfg);
        Outcome start = w.apply(IntegratedResourcesModel.startExpansion(w.state, 0L, CO, "e1",
                "PRJ1", money("10000.00"), 5L * DAY, 2, 200_000L, "AP-CAPEX", w.funds,
                payer("CO", "CON")));
        assertEquals("BUILDING", start.reason());
        assertEquals("10000.00", w.report().cip().toPlainString());
        assertEquals("40000.00", w.report().companyCash().toPlainString());

        Outcome early = w.apply(IntegratedResourcesModel.completeExpansion(w.state, 4L * DAY, CO,
                "e1b", "PRJ1", false, w.funds));
        assertEquals("PROJECT_NOT_DUE", early.reason());

        Outcome done = w.apply(IntegratedResourcesModel.completeExpansion(w.state, 5L * DAY, CO,
                "e1c", "PRJ1", false, w.funds));
        assertEquals("ONLINE", done.reason());
        assertEquals("0.00", w.report().cip().toPlainString());
        assertEquals("30000.00", w.report().equipmentNet().toPlainString());

        w.mine(5L * DAY, "E1", "SEAM-A", 400_000L);
        Outcome over = w.beginExtract(6L * DAY + 1L, "E2", "SEAM-A", 100_000L, "b2");
        assertEquals("MINE_CAPACITY", over.reason());

        w.close(30L * DAY, 0L);
        w.mine(PERIOD, "E2", "SEAM-A", 500_000L);
        Outcome stillOver = w.beginExtract(PERIOD + 2L * DAY, "E3", "SEAM-A", 1L, "b3");
        assertEquals("MINE_CAPACITY", stillOver.reason());

        String inventoryBefore = w.state.mineCoal().amount().toPlainString();
        Outcome exit = w.exit(PERIOD + 2L * DAY, "x1");
        assertEquals("EXITED", exit.reason());
        Report afterExit = w.report();
        assertEquals("0.00", afterExit.equipmentNet().toPlainString());
        assertEquals("29500.00", afterExit.exitLoss().toPlainString());
        assertEquals(inventoryBefore, afterExit.mineCoal().toPlainString());
        assertTrue(afterExit.balanced());

        Outcome closed = w.beginExtract(PERIOD + 2L * DAY, "E4", "SEAM-A", 1000L, "b4");
        assertEquals("CLOSED", closed.reason());
        Outcome twice = w.exit(PERIOD + 2L * DAY, "x2");
        assertEquals("ALREADY_EXITED", twice.reason());
    }

    // ---------------------------------------------------------------- 15 失败残值

    @Test
    void failedExpansionResidualNeedsRealBuyerCash() {
        World w = world(new Cfg());
        w.apply(IntegratedResourcesModel.startExpansion(w.state, 0L, CO, "e1", "PRJ1",
                money("10000.00"), 5L * DAY, 2, 200_000L, "AP-CAPEX", w.funds,
                payer("CO", "CON")));
        Outcome failed = w.apply(IntegratedResourcesModel.completeExpansion(w.state, 5L * DAY, CO,
                "e2", "PRJ1", true, w.funds));
        assertEquals("BUILD_FAILED", failed.reason());
        assertEquals("2500.00", w.report().residual().toPlainString());
        assertEquals("62500.00", w.report().assets().toPlainString());
        assertEquals("62500.00", w.report().equity().toPlainString());
        assertEquals("0.00", w.report().cip().toPlainString());

        Outcome sold = w.apply(IntegratedResourcesModel.sellResidual(w.state, 6L * DAY, CO, "s1",
                "PRJ1", money("2500.00"), w.funds, w.buyToCo));
        assertEquals("RESIDUAL_SOLD", sold.reason());
        assertEquals("42500.00", w.report().companyCash().toPlainString());
        assertEquals("0.00", w.report().residual().toPlainString());
        assertTrue(w.report().balanced());

        Map<String, CashLedger.CashAccount> poor = cash("50000.00", "100.00");
        World poorWorld = new World(new Cfg().build(), poor);
        poorWorld.apply(IntegratedResourcesModel.startExpansion(poorWorld.state, 0L, CO, "e1",
                "PRJ1", money("10000.00"), 5L * DAY, 2, 200_000L, "AP-CAPEX",
                poorWorld.funds, payer("CO", "CON")));
        poorWorld.apply(IntegratedResourcesModel.completeExpansion(poorWorld.state, 5L * DAY, CO,
                "e2", "PRJ1", true, poorWorld.funds));
        Outcome denied = poorWorld.apply(IntegratedResourcesModel.sellResidual(poorWorld.state,
                6L * DAY, CO, "s1", "PRJ1", money("2500.00"), poorWorld.funds,
                payer("BUY", "CO")));
        assertEquals("BUYER_CASH_SHORT", denied.reason());
        assertEquals("2500.00", poorWorld.report().residual().toPlainString());
    }

    // ---------------------------------------------------------------- 16 非整除分摊

    @Test
    void nonDivisibleAllocationAcrossSeamsKeepsExactTotal() {
        Cfg cfg = new Cfg();
        cfg.seamA = 7L;
        cfg.seamB = 3L;
        cfg.mineCapacity = 100L;
        cfg.transport = 100L;
        cfg.coalKgPerMwh = 1L;
        cfg.powerPrice = money("10.00");
        cfg.conversion = money("1.00");
        cfg.gridDemand = 10L;
        cfg.gridBudget = money("1000.00");
        cfg.coalDemand = 10L;
        cfg.coalBudget = money("1000.00");
        cfg.fixedExpense = money("0.00");
        cfg.depreciation = money("0.00");
        cfg.equipment = money("0.00");
        World w = new World(cfg.build(), cash("100.00", "0.00"));

        w.mine(0L, "E1", "SEAM-A", 7L);
        w.mine(DAY, "E2", "SEAM-B", 3L);
        assertEquals("0.42", w.state.mineCoal().amount().toPlainString());
        assertEquals("TRANSFERRED", w.transfer(2L * DAY, 7L, "T1").reason());
        assertEquals("0.29", w.state.plantFuel().amount().toPlainString());
        assertEquals("0.13", w.state.mineCoal().amount().toPlainString());

        assertEquals("POWER_BEGUN",
                w.beginPower(2L * DAY + 1L, "P1", 3L, "AR-POWER", "bp1").reason());
        assertEquals(4L, w.state.plantFuel().quantity());
        assertEquals("0.17", w.state.plantFuel().amount().toPlainString());
        Outcome power = w.finishPower(3L * DAY + 1L, "P1", "fp1");
        assertEquals("POWER_DELIVERED", power.reason());
        assertEquals("0.12", w.state.totals().burnedFuelCost().toPlainString());
        assertEquals("0.29", w.state.totals().burnedFuelCost()
                .add(w.state.plantFuel().amount()).toPlainString());
        Report r = w.report();
        assertEquals("3.12", r.cogs().toPlainString());
        assertEquals("126.88", r.equity().toPlainString());
        assertTrue(r.balanced());
    }

    // ---------------------------------------------------------------- 17 负权益

    @Test
    void negativeEquityStaysBalanced() {
        Cfg cfg = new Cfg();
        cfg.equipment = money("0.00");
        cfg.fixedExpense = money("1000.00");
        cfg.depreciation = money("0.00");
        World w = new World(cfg.build(), cash("100.00", "0.00"));
        Outcome close = w.close(30L * DAY, 0L);
        assertEquals("PERIOD_CLOSED", close.reason());
        Report r = w.report();
        assertEquals("0.00", r.companyCash().toPlainString());
        assertEquals("900.00", r.payables().toPlainString());
        assertEquals("-900.00", r.equity().toPlainString());
        assertEquals("0.00", r.assets().toPlainString());
        assertEquals(0L, r.openJobs());
        assertTrue(r.balanced());
    }

    // ---------------------------------------------------------------- 18 封账：不写已关期间

    @Test
    void closeRejectedWhileDeliveryPeriodStillOpenThenLateFinishStaysInOpenPeriod() {
        World w = world(new Cfg());
        w.mine(0L, "E1", "SEAM-A", 400_000L);
        w.transfer(2L * DAY, 100_000L, "T1");
        assertEquals("POWER_BEGUN",
                w.beginPower(4L * DAY, "P1", 100L, "AR-POWER", "bp1").reason());

        String cashBefore = w.report().companyCash().toPlainString();
        String wipBefore = w.report().wip().toPlainString();
        Outcome rejected = w.close(30L * DAY, 0L);
        assertEquals("PERIOD_HAS_RUNNING_DELIVERY", rejected.reason());
        assertEquals(cashBefore, w.report().companyCash().toPlainString());
        assertEquals(wipBefore, w.report().wip().toPlainString());
        assertEquals(100L, w.state.useOf(0L).powerReservedMwh());
        assertEquals(0L, w.state.useOf(0L).powerDeliveredMwh());

        // 迟完成：完成期间 0 仍未关账，可以写回该期间；不会写到“当前”的期间 1。
        assertEquals("POWER_DELIVERED", w.finishPower(30L * DAY, "P1", "fp1").reason());
        assertEquals(100L, w.state.useOf(0L).powerDeliveredMwh());
        assertEquals("8000.00", w.state.useOf(0L).powerRevenue().toPlainString());
        assertEquals(0L, w.state.useOf(0L).powerReservedMwh());
        assertEquals(0L, w.state.useOf(1L).powerDeliveredMwh());

        assertEquals("PERIOD_CLOSED", w.close(30L * DAY, 0L).reason());
        assertEquals(100L, w.state.useOf(0L).powerDeliveredMwh());
        assertEquals(100L, w.report().powerDeliveredMwh());
        assertTrue(w.report().balanced());
    }

    @Test
    void crossPeriodJobClosesPriorPeriodThenFinishesInFrozenNextPeriod() {
        World w = world(new Cfg());
        w.mine(0L, "E1", "SEAM-A", 400_000L);
        w.transfer(2L * DAY, 100_000L, "T1");
        assertEquals("POWER_BEGUN",
                w.beginPower(29L * DAY, "P1", 100L, "AR-POWER", "bp1").reason());
        assertEquals(1L, w.state.jobOf("P1").deliveryPeriod());

        assertEquals("PERIOD_CLOSED", w.close(30L * DAY, 0L).reason());
        assertEquals(0L, w.state.useOf(0L).powerDeliveredMwh());
        assertEquals(100L, w.state.useOf(1L).powerReservedMwh());

        assertEquals("POWER_DELIVERED", w.finishPower(30L * DAY, "P1", "fp1").reason());
        assertEquals(100L, w.state.useOf(1L).powerDeliveredMwh());
        assertEquals("8000.00", w.state.useOf(1L).powerRevenue().toPlainString());
        assertEquals(0L, w.state.useOf(1L).powerReservedMwh());
        assertEquals(0L, w.state.useOf(0L).powerDeliveredMwh());
        assertEquals("PERIOD_CLOSED", w.close(60L * DAY, 1L).reason());
        assertTrue(w.report().balanced());
    }

    @Test
    void overdueDeliveryBlocksCloseUntilAbortedAndRejectionMovesNothing() {
        World w = world(new Cfg());
        w.mine(0L, "E1", "SEAM-A", 400_000L);
        w.transfer(2L * DAY, 100_000L, "T1");
        w.beginPower(4L * DAY, "P1", 100L, "AR-POWER", "bp1");

        String cashBefore = w.report().companyCash().toPlainString();
        String wipBefore = w.report().wip().toPlainString();
        assertEquals("PERIOD_HAS_RUNNING_DELIVERY",
                w.close(40L * DAY, 0L).reason());
        assertEquals(cashBefore, w.report().companyCash().toPlainString());
        assertEquals(wipBefore, w.report().wip().toPlainString());
        assertEquals(100L, w.state.useOf(0L).powerReservedMwh());

        assertEquals("JOB_FAILED", w.abort(40L * DAY, "P1", "ab1").reason());
        assertEquals(100_000L, w.state.plantFuel().quantity());
        assertEquals("PERIOD_CLOSED", w.close(40L * DAY, 0L, "cl0b").reason());
        assertTrue(w.report().balanced());
    }

    @Test
    void inFlightFuelIsOffsetInSegmentsAtEveryInternalPrice() {
        for (String price : List.of("0.06", "0.09", "1.00")) {
            Cfg cfg = new Cfg();
            cfg.internalPrice = new BigDecimal(price);
            World w = world(cfg);
            w.mine(0L, "E1", "SEAM-A", 400_000L);
            w.sellCoal(DAY + 1L, 200_000L, "AR-COAL");
            w.transfer(DAY + 2L, 100_000L, "T1");
            w.beginPower(DAY + 3L, "P1", 100L, "AR-POWER", "bp1");

            Report during = w.report();
            assertEquals("14000.00", during.segments().consolidatedGrossProfit()
                    .toPlainString(), "在制价 " + price);
            assertEquals(during.revenue().subtract(during.cogs()),
                    during.segments().consolidatedGrossProfit(), "在制价 " + price);
            assertEquals("14000.00", during.revenue().subtract(during.cogs()).toPlainString());

            w.finishPower(2L * DAY + 3L, "P1", "fp1");
            Report done = w.report();
            assertEquals("20300.00", done.segments().consolidatedGrossProfit().toPlainString());
            assertEquals(done.revenue().subtract(done.cogs()),
                    done.segments().consolidatedGrossProfit());

            Cfg failCfg = new Cfg();
            failCfg.internalPrice = new BigDecimal(price);
            World f = world(failCfg);
            f.mine(0L, "E1", "SEAM-A", 400_000L);
            f.sellCoal(DAY + 1L, 200_000L, "AR-COAL");
            f.transfer(DAY + 2L, 100_000L, "T1");
            f.beginPower(DAY + 3L, "P1", 100L, "AR-POWER", "bp1");
            f.abort(2L * DAY + 3L, "P1", "ab1");
            Report failed = f.report();
            assertEquals("14000.00", failed.segments().consolidatedGrossProfit()
                    .toPlainString());
            assertEquals(failed.revenue().subtract(failed.cogs()),
                    failed.segments().consolidatedGrossProfit());
            assertTrue(failed.balanced());
        }
    }
}
