package edu.cufe.auction.world;

import edu.cufe.auction.world.IntegratedResourcesModel.Config;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * round2 独立复核：只针对 candidate-3 的**新语义**（封账闸门 / 在制抵销），
 * 每条都由本类按量成本自行复算，不复述作者回归。
 *
 * <p>D1：关期拒绝不吞在制且不半写；逾期作业在未关期间内可完成/失败；真跨期 WIP（29 日开工、
 * 30 日到期 → 完成期间 1）先关旧期再在新期间完成；结束后可继续关期；已关期间永不被回写。</p>
 * <p>D2：三种内部价 × 在制/完成/失败，`合并毛利 == 收入 − COGS`，未实现额按
 * 「(厂内 + 在制) × 内部价 − (厂内成本 + 在制成本)」独立复算。</p>
 */
class IndependentRound2ResourcesAuditTest {

    private static final long DAY = IntegratedResourcesModel.DAY_MILLIS;
    private static final String CO = "company";

    private static Config config(BigDecimal internalPrice) {
        return new Config(CO, "CO", "OPS", "CUS", "CUS", "GRID", "GRID", "CONV", "CONV",
                "CON", "CON", "BUY", "BUY", "SPON", "SPON", money("20000.00"), internalPrice,
                new BigDecimal("0.10"), money("80.00"), 400L, money("5.00"), 400_000L, 200L,
                500_000L, 200_000L, money("100000.00"), 100L, money("20000.00"), money("1000.00"),
                money("500.00"), new BigDecimal("0.25"),
                List.of(new Seam("SEAM-A", 2_000_000L, new BigDecimal("0.03"))),
                Map.of("AP-EXIT", new IntegratedResourcesModel.Approval("AP-EXIT",
                        IntegratedResourcesModel.APPROVAL_EXIT, 0L)));
    }

    private static final class Rig {

        private State state;
        private FundState funds;
        private final PureCompanyTestSupport.Payer coToOps = payer("CO", "OPS");
        private final PureCompanyTestSupport.Payer coToConv = payer("CO", "CONV");
        private final PureCompanyTestSupport.Payer cusToCo = payer("CUS", "CO");

        private Rig(BigDecimal internalPrice) {
            Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
            map.put("CO", account(CO, "50000.00"));
            map.put("OPS", account("ops", "0.00"));
            map.put("CUS", account("customer", "20000.00"));
            map.put("GRID", account("grid", "20000.00"));
            map.put("CONV", account("converter", "0.00"));
            map.put("CON", account("contractor", "0.00"));
            map.put("BUY", account("buyer", "3000.00"));
            map.put("SPON", account("sponsor", "1000.00"));
            this.funds = funds(map);
            this.state = IntegratedResourcesModel.open(config(internalPrice), funds);
        }

        private String take(Outcome outcome) {
            state = outcome.state();
            funds = outcome.funds();
            return outcome.reason();
        }

        private String beginExtract(long now, String job, long kg, String command) {
            return take(IntegratedResourcesModel.beginExtraction(state, now, CO, command, job,
                    "SEAM-A", kg, funds, coToOps));
        }

        private String finishExtract(long now, String job, String command) {
            return take(IntegratedResourcesModel.finishExtraction(state, now, CO, command, job,
                    funds));
        }

        private String sell(long now, long kg, String ar, String command) {
            return take(IntegratedResourcesModel.sellCoal(state, now, CO, command, "d-" + command,
                    kg, ar, funds));
        }

        private String transfer(long now, long kg, String command) {
            return take(IntegratedResourcesModel.transferToPlant(state, now, CO, command,
                    "t-" + command, kg, funds));
        }

        private String beginPower(long now, String job, long mwh, String ar, String command) {
            return take(IntegratedResourcesModel.beginPowerDelivery(state, now, CO, command, job,
                    mwh, ar, funds, coToConv));
        }

        private String finishPower(long now, String job, String command) {
            return take(IntegratedResourcesModel.finishPowerDelivery(state, now, CO, command,
                    job, funds));
        }

        private String abort(long now, String job, String command) {
            return take(IntegratedResourcesModel.abortJob(state, now, CO, command, job, funds));
        }

        private String close(long now, long period, String command) {
            return take(IntegratedResourcesModel.closePeriod(state, now, CO, command, period,
                    funds, coToOps));
        }

        private Report report() {
            return IntegratedResourcesModel.validate(state, funds);
        }

        private BigDecimal cash(String accountId) {
            return funds.cash(accountId);
        }
    }

    /** 采 400t → 卖 200t → 转 100t → 指定时点开工 100MWh。 */
    private Rig upTo(long beginAt) {
        return upTo(beginAt, "0.06");
    }

    private Rig upTo(long beginAt, String internalPrice) {
        Rig rig = new Rig(new BigDecimal(internalPrice));
        assertEquals("POWER_BEGUN", chain(rig, beginAt));
        return rig;
    }

    private String chain(Rig rig, long beginAt) {
        rig.beginExtract(0L, "E1", 400_000L, "b1");
        rig.finishExtract(DAY, "E1", "b1f");
        assertEquals("COAL_DELIVERED", rig.sell(2L * DAY, 200_000L, "AR-COAL", "s1"));
        assertEquals("TRANSFERRED", rig.transfer(3L * DAY, 100_000L, "t1"));
        return rig.beginPower(beginAt, "P1", 100L, "AR-POWER", "bp1");
    }

    // ------------------------------------------------- D1 封账闸门

    @Test
    void expiredDeliveryBlocksCloseWithoutHalfWriteThenFinishesInOpenPeriod() {
        // 第 4 日开工、第 5 日到期 → 完成期间 0；到第 30 日仍未结算。
        Rig rig = upTo(4L * DAY);
        BigDecimal cashBefore = rig.cash("CO");
        assertEquals("PERIOD_HAS_RUNNING_DELIVERY", rig.close(30L * DAY, 0L, "cl0-blocked"));
        // 拒绝不得半写：现金、费用账单、关期数、期间账全部不动。
        assertEquals(cashBefore, rig.cash("CO"));
        assertTrue(rig.state.bills().isEmpty());
        assertEquals(0L, rig.state.closedPeriods());
        assertEquals(0L, rig.state.useOf(0L).powerDeliveredMwh());
        assertEquals(100L, rig.state.useOf(0L).powerReservedMwh());
        rig.report();

        // 期间 0 仍未关，所以逾期完成仍写回这个未关期间（不迁移、不锁死）。
        assertEquals("POWER_DELIVERED", rig.finishPower(30L * DAY, "P1", "fp1"));
        assertEquals(100L, rig.state.useOf(0L).powerDeliveredMwh());
        assertEquals(money("8000.00"), rig.state.useOf(0L).powerRevenue());
        assertEquals(0L, rig.state.useOf(0L).powerReservedMwh());

        // 结束后关期放行，且关期后已关期间不再变动。
        assertEquals("PERIOD_CLOSED", rig.close(30L * DAY, 0L, "cl0"));
        assertEquals(100L, rig.state.useOf(0L).powerDeliveredMwh());
        assertEquals("PERIOD_CLOSED", rig.close(31L * DAY, 0L, "cl0-again"));
        assertEquals(100L, rig.state.useOf(0L).powerDeliveredMwh());
        Report r = rig.report();
        assertEquals(money("28000.00"), r.revenue());
        assertEquals(money("7700.00"), r.cogs());
        assertEquals(r.assets(), r.liabilities().add(r.equity()));
        assertEquals(money("94000.00"), r.worldCash());
    }

    @Test
    void genuineCrossPeriodWipClosesOldPeriodFirstThenFinishesInNewPeriod() {
        // 第 29 日开工、第 30 日到期 → 完成期间 1：关 0 期必须仍然允许（真跨期在制）。
        Rig rig = upTo(29L * DAY);
        assertEquals(100L, rig.state.useOf(1L).powerReservedMwh());
        assertEquals("PERIOD_CLOSED", rig.close(30L * DAY, 0L, "cl0"));
        // 关 0 期不吞在制：WIP 仍在，期间 1 的预留仍在。
        assertEquals(money("1700.00"), rig.report().wip());
        assertEquals(1L, rig.report().openJobs());
        assertEquals(100L, rig.state.useOf(1L).powerReservedMwh());
        assertEquals(0L, rig.state.useOf(0L).powerDeliveredMwh());

        assertEquals("POWER_DELIVERED", rig.finishPower(30L * DAY, "P1", "fp1"));
        assertEquals(100L, rig.state.useOf(1L).powerDeliveredMwh());
        assertEquals(money("8000.00"), rig.state.useOf(1L).powerRevenue());
        assertEquals(0L, rig.state.useOf(0L).powerDeliveredMwh());
        assertEquals("PERIOD_CLOSED", rig.close(60L * DAY, 1L, "cl1"));
        Report r = rig.report();
        assertEquals(money("87300.00"), r.equity());
        assertEquals(r.assets(), r.liabilities().add(r.equity()));
    }

    @Test
    void abortAfterExpiryReleasesReservationThenCloseSucceeds() {
        Rig rig = upTo(4L * DAY);
        BigDecimal cashBefore = rig.cash("CO");
        assertEquals("PERIOD_HAS_RUNNING_DELIVERY", rig.close(30L * DAY, 0L, "cl0-blocked"));
        assertEquals("JOB_FAILED", rig.abort(30L * DAY, "P1", "ab1"));
        // 燃料按原成本退回厂内，转换成本计损失，期间 0 预留清零，无收入/应收。
        assertEquals(100_000L, rig.state.plantFuel().quantity());
        assertEquals(money("3000.00"), rig.state.plantFuel().amount());
        assertEquals(0L, rig.state.useOf(0L).powerReservedMwh());
        assertEquals(money("500.00"), rig.state.jobLoss());
        // 失败不产生售电应收：只剩第 2 日外销煤那一条。
        assertEquals(1, rig.state.receivables().size());
        assertEquals("COAL_KG", rig.state.receivables().get("AR-COAL").basis());
        assertEquals(cashBefore, rig.cash("CO"));
        assertEquals("PERIOD_CLOSED", rig.close(30L * DAY, 0L, "cl0"));
        Report r = rig.report();
        assertEquals(money("20000.00"), r.revenue());
        assertEquals(money("6000.00"), r.cogs());
        assertEquals(r.assets(), r.liabilities().add(r.equity()));
    }

    // ------------------------------------------------- D2 在制燃料抵销

    @Test
    void inFlightFuelOffsetHoldsForThreeInternalPrices() {
        for (String price : List.of("0.06", "0.09", "1.00")) {
            Rig rig = upTo(4L * DAY, price);
            Report mid = rig.report();
            // 独立复算：未实现 = 内部价 × (厂内 60000 + 在制 40000) − (1800 + 1200)。
            BigDecimal p = new BigDecimal(price);
            BigDecimal expectedUnrealized = money(p.multiply(BigDecimal.valueOf(100_000L))
                    .subtract(money("3000.00")).toPlainString());
            assertEquals(expectedUnrealized, mid.segments().unrealizedInternalProfit(), price);
            // 在制：合并毛利必须等于报表自身 收入 − COGS（14000），与内部价无关。
            assertEquals(money("14000.00"), mid.segments().consolidatedGrossProfit(), price);
            assertEquals(mid.revenue().subtract(mid.cogs()),
                    mid.segments().consolidatedGrossProfit(), price);
            // 在制燃料不进 COGS，仍在 WIP 资产里。
            assertEquals(money("6000.00"), mid.cogs(), price);
            assertEquals(money("1700.00"), mid.wip(), price);

            assertEquals("POWER_DELIVERED", rig.finishPower(5L * DAY, "P1", "fp1"));
            Report done = rig.report();
            assertEquals(money("20300.00"), done.segments().consolidatedGrossProfit(), price);
            assertEquals(done.revenue().subtract(done.cogs()),
                    done.segments().consolidatedGrossProfit(), price);
            // 完成后未实现只按厂内余额（60000 × 内部价 − 1800）。
            assertEquals(money(p.multiply(BigDecimal.valueOf(60_000L)).subtract(money("1800.00"))
                            .toPlainString()),
                    done.segments().unrealizedInternalProfit(), price);
        }
    }

    @Test
    void failedJobReturnsFuelSoNothingIsTreatedAsBurned() {
        for (String price : List.of("0.06", "0.09", "1.00")) {
            Rig rig = upTo(4L * DAY, price);
            assertEquals("JOB_FAILED", rig.abort(4L * DAY, "P1", "ab1"));
            Report r = rig.report();
            BigDecimal p = new BigDecimal(price);
            assertEquals(money(p.multiply(BigDecimal.valueOf(100_000L)).subtract(money("3000.00"))
                            .toPlainString()),
                    r.segments().unrealizedInternalProfit(), price);
            assertEquals(money("14000.00"), r.segments().consolidatedGrossProfit(), price);
            assertEquals(r.revenue().subtract(r.cogs()),
                    r.segments().consolidatedGrossProfit(), price);
            assertEquals(money("6000.00"), r.cogs(), price);
        }
    }
}
