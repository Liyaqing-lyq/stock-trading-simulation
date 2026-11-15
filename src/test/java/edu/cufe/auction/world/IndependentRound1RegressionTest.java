package edu.cufe.auction.world;

import edu.cufe.auction.world.DigitalServiceModel.Config;
import edu.cufe.auction.world.DigitalServiceModel.Evidence;
import edu.cufe.auction.world.DigitalServiceModel.Outcome;
import edu.cufe.auction.world.DigitalServiceModel.Report;
import edu.cufe.auction.world.DigitalServiceModel.Stage;
import edu.cufe.auction.world.DigitalServiceModel.State;
import edu.cufe.auction.world.IntegratedResourcesModel.Seam;
import edu.cufe.auction.world.bank.FundState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static edu.cufe.auction.world.DigitalServiceModel.APPROVAL_COMMERCIAL;
import static edu.cufe.auction.world.PureCompanyTestSupport.account;
import static edu.cufe.auction.world.PureCompanyTestSupport.funds;
import static edu.cufe.auction.world.PureCompanyTestSupport.money;
import static edu.cufe.auction.world.PureCompanyTestSupport.payer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独审 round1 三个缺陷的永久回归（Root 裁定后的正确契约版本）。
 *
 * <p>原探针（{@code IndependentDefectProbeTest}）保留在
 * {@code logs/evidence-independent-round1-probe.java}，它在 candidate-2 上 3 红 1 绿。
 * 本文件按 Root 裁定改写期望：D1 由“关期后 finish 回写旧期”改为“关期被在制交付拒绝、
 * 迟完成只写未关期间”；D3 由“跨期交付被接受”改为“按当期额度明确拒绝且不动合同/义务”。</p>
 */
class IndependentRound1RegressionTest {

    private static final long DAY = IntegratedResourcesModel.DAY_MILLIS;
    private static final String CO = "company";

    // ======================================================= 资源

    private static final class Res {

        private IntegratedResourcesModel.State state;
        private FundState funds;
        private final PureCompanyTestSupport.Payer coToOps = payer("CO", "OPS");
        private final PureCompanyTestSupport.Payer coToConv = payer("CO", "CONV");
        private final PureCompanyTestSupport.Payer cusToCo = payer("CUS", "CO");

        private Res(String internalPrice) {
            Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
            map.put("CO", account(CO, "50000.00"));
            map.put("OPS", account("ops", "0.00"));
            map.put("CUS", account("customer", "20000.00"));
            map.put("GRID", account("grid", "20000.00"));
            map.put("CONV", account("converter", "0.00"));
            map.put("CON", account("contractor", "0.00"));
            map.put("BUY", account("buyer", "3000.00"));
            map.put("SPON", account("sponsor", "1000.00"));
            IntegratedResourcesModel.Config config = new IntegratedResourcesModel.Config(CO,
                    "CO", "OPS", "CUS", "CUS", "GRID", "GRID", "CONV", "CONV", "CON", "CON",
                    "BUY", "BUY", "SPON", "SPON", money("20000.00"), new BigDecimal(internalPrice),
                    new BigDecimal("0.10"), money("80.00"), 400L, money("5.00"), 400_000L, 200L,
                    500_000L, 200_000L, money("100000.00"), 100L, money("20000.00"),
                    money("1000.00"), money("500.00"), new BigDecimal("0.25"),
                    List.of(new Seam("SEAM-A", 2_000_000L, new BigDecimal("0.03"))), Map.of());
            this.funds = funds(map);
            this.state = IntegratedResourcesModel.open(config, funds);
        }

        private String take(IntegratedResourcesModel.Outcome outcome) {
            state = outcome.state();
            funds = outcome.funds();
            return outcome.reason();
        }

        private void mine(long now, String job, long kg) {
            assertEquals("EXTRACTION_BEGUN", take(IntegratedResourcesModel.beginExtraction(state,
                    now, CO, "b-" + job, job, "SEAM-A", kg, funds, coToOps)));
            assertEquals("EXTRACTED", take(IntegratedResourcesModel.finishExtraction(state,
                    now + DAY, CO, "f-" + job, job, funds)));
        }

        private String beginPower(long now, String job, long mwh, String ar) {
            return take(IntegratedResourcesModel.beginPowerDelivery(state, now, CO, "bp-" + job,
                    job, mwh, ar, funds, coToConv));
        }

        private String finishPower(long now, String job) {
            return take(IntegratedResourcesModel.finishPowerDelivery(state, now, CO,
                    "fp-" + job, job, funds));
        }

        private String abort(long now, String job) {
            return take(IntegratedResourcesModel.abortJob(state, now, CO, "ab-" + job, job,
                    funds));
        }

        private String close(long now, long period, String command) {
            return take(IntegratedResourcesModel.closePeriod(state, now, CO, command, period,
                    funds, coToOps));
        }

        private edu.cufe.auction.world.IntegratedResourcesModel.Report report() {
            return IntegratedResourcesModel.validate(state, funds);
        }

        private void m1UntilBegin() {
            mine(0L, "E1", 400_000L);
            assertEquals("COAL_DELIVERED", take(IntegratedResourcesModel.sellCoal(state,
                    2L * DAY, CO, "s1", "d-s1", 200_000L, "AR-COAL", funds)));
            assertEquals("TRANSFERRED", take(IntegratedResourcesModel.transferToPlant(state,
                    3L * DAY, CO, "t1", "T1", 100_000L, funds)));
        }
    }

    @Test
    void closedPeriodIsNeverRewrittenAndLateFinishStaysInOpenPeriod() {
        Res res = new Res("0.06");
        res.m1UntilBegin();
        assertEquals("POWER_BEGUN", res.beginPower(4L * DAY, "P1", 100L, "AR-POWER"));

        String cash = res.report().companyCash().toPlainString();
        String wip = res.report().wip().toPlainString();
        assertEquals("PERIOD_HAS_RUNNING_DELIVERY", res.close(30L * DAY, 0L, "cl0"));
        assertEquals(cash, res.report().companyCash().toPlainString());
        assertEquals(wip, res.report().wip().toPlainString());
        assertEquals(100L, res.state.useOf(0L).powerReservedMwh());

        assertEquals("POWER_DELIVERED", res.finishPower(30L * DAY, "P1"));
        assertEquals(100L, res.state.useOf(0L).powerDeliveredMwh());
        assertEquals("8000.00", res.state.useOf(0L).powerRevenue().toPlainString());
        assertEquals(0L, res.state.useOf(1L).powerDeliveredMwh());
        assertEquals("PERIOD_CLOSED", res.close(30L * DAY, 0L, "cl0b"));
        assertTrue(res.report().balanced());
    }

    @Test
    void inFlightFuelKeepsSegmentProfitEqualToReportProfitAtEveryPrice() {
        for (String price : List.of("0.06", "0.09", "1.00")) {
            Res res = new Res(price);
            res.m1UntilBegin();
            res.beginPower(4L * DAY, "P1", 100L, "AR-POWER");
            edu.cufe.auction.world.IntegratedResourcesModel.Report during = res.report();
            assertEquals(money("14000.00"), during.revenue().subtract(during.cogs()));
            assertEquals(during.revenue().subtract(during.cogs()),
                    during.segments().consolidatedGrossProfit(), "在制价 " + price);
            res.finishPower(5L * DAY, "P1");
            edu.cufe.auction.world.IntegratedResourcesModel.Report after = res.report();
            assertEquals(money("20300.00"), after.revenue().subtract(after.cogs()));
            assertEquals(after.revenue().subtract(after.cogs()),
                    after.segments().consolidatedGrossProfit(), "完成价 " + price);
            assertTrue(after.balanced());
        }
    }

    // ======================================================= 数字

    private static final class Dig {

        private DigitalServiceModel.State state;
        private FundState funds;
        private final PureCompanyTestSupport.Payer coToCon = payer("CO", "CON");
        private final PureCompanyTestSupport.Payer coToOps = payer("CO", "OPS");
        private final PureCompanyTestSupport.Payer cusToCo = payer("CUS", "CO");

        private Dig(long demand, String budget, long capacity) {
            Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
            map.put("CO", account(CO, "300.00"));
            map.put("OPS", account("ops", "0.00"));
            map.put("CUS", account("customer", "1000.00"));
            map.put("CON", account("contractor", "0.00"));
            map.put("SPON", account("sponsor", "400.00"));
            Config config = new Config(CO, "CO", "OPS", "CUS", "CUS", "CON", "CON", "SPON",
                    "SPON", new BigDecimal("1.0000"), money("0.60"), demand, money(budget),
                    capacity, 5L * DAY, money("50.00"), 10L * DAY, money("100.00"), true, true,
                    money("10.00"), money("25.00"),
                    Map.of("AP-COMM", new DigitalServiceModel.Approval("AP-COMM",
                            APPROVAL_COMMERCIAL, 0L)));
            this.funds = funds(map);
            this.state = DigitalServiceModel.open(config, funds);
        }

        private String take(Outcome outcome) {
            state = outcome.state();
            funds = outcome.funds();
            return outcome.reason();
        }

        private void ready() {
            take(DigitalServiceModel.beginStage(state, 0L, CO, "p1", Stage.PILOT, funds, coToCon));
            take(DigitalServiceModel.completeStage(state, 5L * DAY, CO, "p1c", Stage.PILOT,
                    new Evidence("ev-p", "log", DAY), funds));
            take(DigitalServiceModel.beginStage(state, 5L * DAY, CO, "v1", Stage.VALIDATION,
                    funds, coToCon));
            take(DigitalServiceModel.completeStage(state, 15L * DAY, CO, "v1c", Stage.VALIDATION,
                    new Evidence("ev-v", "log", 6L * DAY), funds));
            take(DigitalServiceModel.approveCommercialization(state, 15L * DAY, CO, "ap1",
                    "AP-COMM", funds));
        }

        private String sign(long now, String contract, long qty, String command) {
            return take(DigitalServiceModel.signServiceContract(state, now, CO, command, contract,
                    qty, money("0.00"), funds, cusToCo));
        }

        private String deliver(long now, String contract, long units, String ar, String command) {
            return take(DigitalServiceModel.deliverService(state, now, CO, command, contract,
                    units, ar, funds, coToOps));
        }

        private String close(long now, long period, String command) {
            return take(DigitalServiceModel.closePeriod(state, now, CO, command, period, funds,
                    coToOps));
        }

        private Report report() {
            return DigitalServiceModel.validate(state, funds);
        }

        private long committedDemand(long period) {
            return state.deliveredIn(period) + state.reservedIn(period);
        }

        private BigDecimal committedBudget(long period) {
            return state.deliveredRevenueIn(period).add(state.reservedBudgetIn(period));
        }
    }

    @Test
    void crossPeriodDeliveryIsCheckedAgainstCurrentPeriodLimits() {
        Dig dig = new Dig(300L, "300.00", 200L);
        dig.ready();
        assertEquals("SIGNED", dig.sign(16L * DAY, "C1", 300L, "sg1"));
        assertEquals("PERIOD_CLOSED", dig.close(30L * DAY, 0L, "cl0"));
        assertEquals("SIGNED", dig.sign(31L * DAY, "C2", 300L, "sg2"));

        assertEquals("DEMAND_EXCEEDED", dig.deliver(31L * DAY, "C1", 200L, "AR-1", "dl1"));
        assertEquals(0L, dig.state.contracts().get("C1").delivered());
        assertEquals(300L, dig.committedDemand(1L));
        assertEquals(money("300.00"), dig.committedBudget(1L));
        assertEquals("0.00", dig.report().revenue().toPlainString());
        assertTrue(dig.report().balanced());
    }

    @Test
    void orderChangesWhichActionIsRejectedButNeverBreaksTheLimit() {
        Dig deliverFirst = new Dig(300L, "300.00", 200L);
        deliverFirst.ready();
        deliverFirst.sign(16L * DAY, "C1", 300L, "sg1");
        deliverFirst.close(30L * DAY, 0L, "cl0");
        assertEquals("DELIVERED", deliverFirst.deliver(31L * DAY, "C1", 200L, "AR-1", "dl1"));
        assertEquals("DEMAND_EXCEEDED", deliverFirst.sign(31L * DAY, "C2", 300L, "sg2"));
        assertTrue(deliverFirst.committedDemand(1L) <= 300L);
        assertTrue(deliverFirst.committedBudget(1L).compareTo(money("300.00")) <= 0);

        Dig signFirst = new Dig(300L, "300.00", 200L);
        signFirst.ready();
        signFirst.sign(16L * DAY, "C1", 300L, "sg1");
        signFirst.close(30L * DAY, 0L, "cl0");
        assertEquals("SIGNED", signFirst.sign(31L * DAY, "C2", 300L, "sg2"));
        assertEquals("DEMAND_EXCEEDED", signFirst.deliver(31L * DAY, "C1", 200L, "AR-1", "dl1"));
        assertTrue(signFirst.committedDemand(1L) <= 300L);
        assertTrue(signFirst.committedBudget(1L).compareTo(money("300.00")) <= 0);
    }
}
