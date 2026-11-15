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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立复核（不是作者测试的复述）：一体化资源模型的账目由本类按合同口径<b>另行复算</b>，
 * 不读实现的 totals 累加值，只用作业记录、库存记录与资金三态独立重建资产/负债/权益、
 * COGS 与跨期归属，再与实现报表比对。
 *
 * <p>覆盖：两段作业中间态与完成态的独立 A=L+E、COGS 只含已燃燃料、多批次成本与尾差结尽、
 * 关期次序与折旧/费用、退出与库存留存、世界现金守恒。缺陷另见 {@code IndependentDefectProbeTest}。</p>
 */
class IndependentResourcesAuditTest {

    private static final long DAY = IntegratedResourcesModel.DAY_MILLIS;
    private static final long PERIOD = IntegratedResourcesModel.PERIOD_MILLIS;
    private static final String CO = "company";

    /** EXP 配置：默认与合同算例同量级，但由本类自己冻结。 */
    private static Config config(BigDecimal internalPrice, long seamAKg, BigDecimal seamACost,
                                 long seamBKg, BigDecimal seamBCost, long equipment) {
        List<Seam> seams = seamBKg > 0
                ? List.of(new Seam("SEAM-A", seamAKg, seamACost),
                          new Seam("SEAM-B", seamBKg, seamBCost))
                : List.of(new Seam("SEAM-A", seamAKg, seamACost));
        return new Config(CO, "CO", "OPS", "CUS", "CUS", "GRID", "GRID", "CONV", "CONV",
                "CON", "CON", "BUY", "BUY", "SPON", "SPON", money(equipment + ".00"),
                internalPrice, new BigDecimal("0.10"), money("80.00"), 400L, money("5.00"),
                400_000L, 200L, 500_000L, 200_000L, money("100000.00"), 100L, money("20000.00"),
                money("1000.00"), money("500.00"), new BigDecimal("0.25"), seams,
                Map.of("AP-CAPEX", new IntegratedResourcesModel.Approval("AP-CAPEX",
                                IntegratedResourcesModel.APPROVAL_CAPEX, 0L),
                        "AP-EXIT", new IntegratedResourcesModel.Approval("AP-EXIT",
                                IntegratedResourcesModel.APPROVAL_EXIT, 0L)));
    }

    private static Config defaultConfig() {
        return config(new BigDecimal("0.06"), 2_000_000L, new BigDecimal("0.03"), 0L,
                BigDecimal.ZERO, 20_000L);
    }

    /** 真实现金账户表 + 固定资金腿。 */
    private static final class Rig {

        private State state;
        private FundState funds;
        private final PureCompanyTestSupport.Payer coToOps = payer("CO", "OPS");
        private final PureCompanyTestSupport.Payer coToConv = payer("CO", "CONV");
        private final PureCompanyTestSupport.Payer cusToCo = payer("CUS", "CO");
        private final PureCompanyTestSupport.Payer gridToCo = payer("GRID", "CO");
        private final PureCompanyTestSupport.Payer buyToCo = payer("BUY", "CO");
        private final PureCompanyTestSupport.Payer sponToCo = payer("SPON", "CO");
        private final PureCompanyTestSupport.Payer coToCon = payer("CO", "CON");

        private Rig(Config config, String companyCash, String buyerCash, String customerCash) {
            Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
            map.put("CO", account(CO, companyCash));
            map.put("OPS", account("ops", "0.00"));
            map.put("CUS", account("customer", customerCash));
            map.put("GRID", account("grid", "20000.00"));
            map.put("CONV", account("converter", "0.00"));
            map.put("CON", account("contractor", "0.00"));
            map.put("BUY", account("buyer", buyerCash));
            map.put("SPON", account("sponsor", "1000.00"));
            this.funds = funds(map);
            this.state = IntegratedResourcesModel.open(config, funds);
        }

        private String take(Outcome outcome) {
            state = outcome.state();
            funds = outcome.funds();
            return outcome.reason();
        }

        private String beginExtract(long now, String job, String seam, long kg, String command) {
            return take(IntegratedResourcesModel.beginExtraction(state, now, CO, command, job,
                    seam, kg, funds, coToOps));
        }

        private String finishExtract(long now, String job, String command) {
            return take(IntegratedResourcesModel.finishExtraction(state, now, CO, command, job,
                    funds));
        }

        private String beginPower(long now, String job, long mwh, String receivable,
                                  String command) {
            return take(IntegratedResourcesModel.beginPowerDelivery(state, now, CO, command, job,
                    mwh, receivable, funds, coToConv));
        }

        private String finishPower(long now, String job, String command) {
            return take(IntegratedResourcesModel.finishPowerDelivery(state, now, CO, command,
                    job, funds));
        }

        private String sellCoal(long now, long kg, String receivable, String command) {
            return take(IntegratedResourcesModel.sellCoal(state, now, CO, command, "d-" + command,
                    kg, receivable, funds));
        }

        private String transfer(long now, long kg, String id, String command) {
            return take(IntegratedResourcesModel.transferToPlant(state, now, CO, command, id, kg,
                    funds));
        }

        private String collect(long now, String receivable, String command) {
            FundPayer leg = receivable.contains("POWER") ? gridToCo : cusToCo;
            return take(IntegratedResourcesModel.collect(state, now, CO, command, receivable,
                    funds, leg));
        }

        private String close(long now, long period, String command) {
            return take(IntegratedResourcesModel.closePeriod(state, now, CO, command, period,
                    funds, coToOps));
        }

        private String exit(long now, String command) {
            return take(IntegratedResourcesModel.exitBusiness(state, now, CO, command, "AP-EXIT",
                    funds));
        }

        private String startExpansion(long now, String project, String capex, long build,
                                      String command) {
            return take(IntegratedResourcesModel.startExpansion(state, now, CO, command, project,
                    money(capex), build, 2, 100_000L, "AP-CAPEX", funds, coToCon));
        }

        private String completeExpansion(long now, String project, String command) {
            return take(IntegratedResourcesModel.completeExpansion(state, now, CO, command,
                    project, false, funds));
        }

        private Report report() {
            return IntegratedResourcesModel.validate(state, funds);
        }

        private BigDecimal cash(String accountId) {
            return funds.cash(accountId);
        }
    }

    private Rig rig(Config config) {
        return new Rig(config, "50000.00", "3000.00", "20000.00");
    }

    /** 合同 M1：采 400t → 外销 200t → 内部转 100t → 发电交付 100MWh（烧 40t）。 */
    private Rig m1() {
        Rig rig = rig(defaultConfig());
        assertEquals("EXTRACTION_BEGUN", rig.beginExtract(0L, "E1", "SEAM-A", 400_000L, "b-E1"));
        assertEquals("EXTRACTED", rig.finishExtract(DAY, "E1", "f-E1"));
        assertEquals("COAL_DELIVERED", rig.sellCoal(2L * DAY, 200_000L, "AR-COAL", "s1"));
        assertEquals("TRANSFERRED", rig.transfer(3L * DAY, 100_000L, "T1", "t1"));
        assertEquals("POWER_BEGUN", rig.beginPower(4L * DAY, "P1", 100L, "AR-POWER", "bp1"));
        return rig;
    }

    // ------------------------------------------------- 1 中间态：本节独立复算，不看实现累加值

    @Test
    void runningJobBalanceAndCogsRecomputedIndependently() {
        Rig rig = m1();
        // 在制作业没有给矿端加煤：库存仍是 100000kg，多要 1kg 就拒绝。
        assertEquals("COAL_SHORT", rig.sellCoal(7L * DAY, 100_001L, "AR-X", "sx"));
        assertEquals("COAL_SHORT", rig.transfer(7L * DAY, 100_001L, "TX", "tx"));

        Report r = rig.report();
        // 本类自己的复算：现金 50000 − 开采 12000 − 转换 500 = 37500；
        // 矿端 100000kg/3000（400000−200000−100000）；厂内 60000kg/1800（100000−40000）；
        // 在制 = 预留燃料原成本 1200 + 已付转换 500 = 1700；应收 20000；设备 20000。
        BigDecimal cash = money("37500.00");
        BigDecimal mine = money("3000.00");
        BigDecimal fuel = money("1800.00");
        BigDecimal wip = money("1700.00");
        BigDecimal ar = money("20000.00");
        BigDecimal equip = money("20000.00");
        assertEquals(cash, rig.cash("CO"));
        assertEquals(mine, r.mineCoal());
        assertEquals(100_000L, r.mineCoalKg());
        assertEquals(fuel, r.plantFuel());
        assertEquals(60_000L, r.plantFuelKg());
        assertEquals(wip, r.wip());
        assertEquals(ar, r.receivables());
        assertEquals(equip, r.equipmentNet());
        assertEquals(1L, r.openJobs());

        BigDecimal assets = cash.add(mine).add(fuel).add(wip).add(ar).add(equip);
        assertEquals(money("84000.00"), assets);
        assertEquals(assets, r.assets());
        assertEquals(r.liabilities().add(r.equity()), r.assets());

        // COGS 只含已燃燃料：燃烧尚未发生，故 = 外销煤成本 6000，不含在制燃料 1200。
        assertEquals(money("6000.00"), r.cogs());
        assertEquals(money("20000.00"), r.revenue());
        // 独立权益：初始 70000 + 收入 20000 − COGS 6000。
        assertEquals(money("84000.00"), r.equity());

        // 完成：COGS 增加燃料 1200 + 转换 500，收入 +8000；在制归零，应收 +8000。
        assertEquals("POWER_DELIVERED", rig.finishPower(5L * DAY, "P1", "fp1"));
        Report done = rig.report();
        assertEquals(ZERO(), done.wip());
        assertEquals(0L, done.openJobs());
        assertEquals(money("28000.00"), done.revenue());
        assertEquals(money("7700.00"), done.cogs());
        assertEquals(money("28000.00"), done.receivables());
        assertEquals(money("90300.00"), done.assets());
        assertEquals(money("90300.00"), done.equity());
    }

    private static BigDecimal ZERO() {
        return money("0.00");
    }

    // ------------------------------------------------- 2 多批次成本与尾差

    @Test
    void multiBatchTransferAndBurnCarryOriginalCostWithExactTail() {
        Rig rig = rig(config(new BigDecimal("0.06"), 600_000L, new BigDecimal("0.03"),
                200_000L, new BigDecimal("0.07"), 20_000L));
        assertEquals("EXTRACTION_BEGUN", rig.beginExtract(0L, "EA", "SEAM-A", 300_000L, "b-EA"));
        assertEquals("EXTRACTED", rig.finishExtract(DAY, "EA", "f-EA"));
        assertEquals("EXTRACTION_BEGUN", rig.beginExtract(2L * DAY, "EB", "SEAM-B", 100_000L,
                "b-EB"));
        assertEquals("EXTRACTED", rig.finishExtract(3L * DAY, "EB", "f-EB"));

        // 矿端：400000kg，成本 9000（A 0.03×300000）+ 7000（B 0.07×100000）= 16000。
        assertEquals(money("16000.00"), rig.report().mineCoal());
        // 转 250000kg → 16000×250000/400000 = 10000。
        assertEquals("TRANSFERRED", rig.transfer(4L * DAY, 250_000L, "T1", "t1"));
        assertEquals(money("10000.00"), rig.state.plantFuel().amount());
        assertEquals(money("6000.00"), rig.state.mineCoal().amount());

        // 发电 100MWh 烧 40000kg → 10000×40000/250000 = 1600。
        assertEquals("POWER_BEGUN", rig.beginPower(5L * DAY, "P1", 100L, "AR-POWER", "bp1"));
        assertEquals(money("1600.00"), rig.state.jobOf("P1").fuelCost());
        assertEquals(money("8400.00"), rig.state.plantFuel().amount());
        assertEquals("POWER_DELIVERED", rig.finishPower(6L * DAY, "P1", "fp1"));

        // 独立恒等式：厂内燃料账面 = 累计转入原成本 − 已燃原成本。
        BigDecimal transferredCost = money("10000.00");
        BigDecimal burnedCost = rig.state.jobOf("P1").fuelCost();
        assertEquals(transferredCost.subtract(burnedCost), rig.state.plantFuel().amount());
        assertEquals(money("2100.00"), rig.report().cogs());
        assertEquals(money("8000.00"), rig.report().revenue());
        // 现金 50000−9000−7000−500 = 33500；矿端 150000/6000；厂内 210000/8400；应收 8000；设备 20000。
        assertEquals(money("75900.00"), rig.report().assets());
        assertEquals(money("75900.00"), rig.report().equity());
    }

    @Test
    void indivisibleTransferChainClosesToZeroWithNoLostCents() {
        // 3kg / 10.00 的极端尾差：每次转 1kg，末批必须结尽，转出总额恰好 10.00。
        Rig rig = rig(config(new BigDecimal("0.06"), 3L, new BigDecimal("3.3333"), 0L,
                BigDecimal.ZERO, 20_000L));
        assertEquals("EXTRACTION_BEGUN", rig.beginExtract(0L, "E1", "SEAM-A", 3L, "b-E1"));
        assertEquals("EXTRACTED", rig.finishExtract(DAY, "E1", "f-E1"));
        assertEquals(money("10.00"), rig.state.mineCoal().amount());
        assertEquals("TRANSFERRED", rig.transfer(2L * DAY, 1L, "T1", "t1"));
        assertEquals("TRANSFERRED", rig.transfer(3L * DAY, 1L, "T2", "t2"));
        assertEquals("TRANSFERRED", rig.transfer(4L * DAY, 1L, "T3", "t3"));
        assertEquals(0L, rig.state.mineCoal().quantity());
        assertEquals(money("0.00"), rig.state.mineCoal().amount());
        assertEquals(3L, rig.state.plantFuel().quantity());
        assertEquals(money("10.00"), rig.state.plantFuel().amount());
        assertEquals(money("70000.00"), rig.report().assets());
        assertEquals(money("70000.00"), rig.report().equity());
    }

    // ------------------------------------------------- 3 关期次序、折旧与费用

    @Test
    void closingSequenceDepreciationAndExpenseAreClosedOut() {
        Rig rig = m1();
        assertEquals("POWER_DELIVERED", rig.finishPower(5L * DAY, "P1", "fp1"));
        assertEquals("PERIOD_NOT_ENDED", rig.close(29L * DAY, 0L, "cl0-early"));
        assertEquals("PERIOD_NOT_CONTINUOUS", rig.close(30L * DAY, 1L, "cl1-first"));
        assertEquals("PERIOD_CLOSED", rig.close(30L * DAY, 0L, "cl0"));
        Report after0 = rig.report();
        assertEquals("PERIOD_CLOSED", rig.close(31L * DAY, 0L, "cl0-again"));
        // 拒绝不得产生任何副作用。
        assertEquals(after0.assets(), rig.report().assets());
        assertEquals(after0.equity(), rig.report().equity());
        assertEquals(money("1000.00"), after0.fixedExpense());
        assertEquals(money("500.00"), after0.depreciation());
        assertEquals(money("19500.00"), after0.equipmentNet());
        assertEquals(money("88800.00"), after0.equity());
        assertEquals(after0.assets(), after0.liabilities().add(after0.equity()));

        assertEquals("PERIOD_CLOSED", rig.close(60L * DAY, 1L, "cl1"));
        Report after1 = rig.report();
        assertEquals(money("2000.00"), after1.fixedExpense());
        assertEquals(money("1000.00"), after1.depreciation());
        assertEquals(money("19000.00"), after1.equipmentNet());
        assertEquals(money("87300.00"), after1.equity());
        assertEquals(after1.assets(), after1.liabilities().add(after1.equity()));
        // 世界现金自开局起守恒：只有账户之间的转移。
        assertEquals(money("94000.00"), after1.worldCash());
        assertTrue(after1.balanced());
    }

    // ------------------------------------------------- 4 收入确认与退出边界

    @Test
    void collectAndExitDoNotTouchRecognisedRevenueOrEquityBase() {
        Rig rig = m1();
        assertEquals("POWER_DELIVERED", rig.finishPower(5L * DAY, "P1", "fp1"));
        assertEquals("RECEIVABLE_NOT_DUE", rig.collect(10L * DAY, "AR-COAL", "c-early"));
        assertEquals("PAYMENT_RECEIVED", rig.collect(2L * DAY + 15L * DAY, "AR-COAL", "c1"));
        Report r = rig.report();
        assertEquals(money("28000.00"), r.revenue());
        assertEquals(money("8000.00"), r.receivables());
        assertEquals(money("0.00"), rig.cash("CUS"));

        // 退出：库存保留为资产，设备净值转退出损失，退出后不得再经营。
        assertEquals("EXITED", rig.exit(20L * DAY, "x1"));
        Report after = rig.report();
        assertEquals(money("0.00"), after.equipmentNet());
        assertEquals(money("20000.00"), after.exitLoss());
        assertEquals(money("3000.00"), after.mineCoal());
        assertEquals(money("1800.00"), after.plantFuel());
        // 现金 37500 + 收款 20000 = 57500；+ 矿端 3000 + 厂内 1800 + 应收 8000 = 70300。
        assertEquals(money("70300.00"), after.assets());
        assertEquals(money("70300.00"), after.equity());
        assertEquals(after.assets(), after.liabilities().add(after.equity()));
        assertEquals("CLOSED", rig.beginExtract(21L * DAY, "E9", "SEAM-A", 1000L, "b-E9"));
        assertEquals("ALREADY_EXITED", rig.exit(22L * DAY, "x2"));
    }

    // ------------------------------------------------- 5 扩产：CIP→设备→折旧，退出后残值

    @Test
    void expansionCipBecomesEquipmentAndDepreciatesOnRealCash() {
        Rig rig = rig(defaultConfig());
        assertEquals("BUILDING", rig.startExpansion(0L, "X1", "6000.00", 10L * DAY, "x1"));
        Report cip = rig.report();
        assertEquals(money("6000.00"), cip.cip());
        assertEquals(money("20000.00"), cip.equipmentNet());
        assertEquals(money("44000.00"), rig.cash("CO"));
        assertEquals(cip.assets(), cip.liabilities().add(cip.equity()));

        assertEquals("PROJECT_NOT_DUE", rig.completeExpansion(9L * DAY, "X1", "x1c-early"));
        assertEquals("ONLINE", rig.completeExpansion(10L * DAY, "X1", "x1c"));
        Report online = rig.report();
        assertEquals(money("0.00"), online.cip());
        assertEquals(money("26000.00"), online.equipmentNet());
        // 上线当期只有基础产能 400000kg：占满后再要 1kg 即超能力。
        assertEquals("EXTRACTION_BEGUN", rig.beginExtract(10L * DAY, "E1", "SEAM-A", 400_000L,
                "b-E1"));
        assertEquals("EXTRACTED", rig.finishExtract(11L * DAY, "E1", "f-E1"));
        assertEquals("MINE_CAPACITY", rig.beginExtract(12L * DAY, "E2", "SEAM-A", 1L, "b-E2"));
        // 关掉第 0 期后进入第 1 期：ramp 第 1 段 = 100000×1/2 = 50000 → 450000 可用。
        assertEquals("PERIOD_CLOSED", rig.close(30L * DAY, 0L, "cl0"));
        assertEquals("EXTRACTION_BEGUN", rig.beginExtract(30L * DAY, "E3", "SEAM-A", 450_000L,
                "b-E3"));
        assertEquals("EXTRACTED", rig.finishExtract(31L * DAY, "E3", "f-E3"));
        assertEquals("MINE_CAPACITY", rig.beginExtract(32L * DAY, "E4", "SEAM-A", 1L, "b-E4"));
    }
}
