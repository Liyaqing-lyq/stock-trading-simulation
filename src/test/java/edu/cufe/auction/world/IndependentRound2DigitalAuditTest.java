package edu.cufe.auction.world;

import edu.cufe.auction.world.DigitalServiceModel.Config;
import edu.cufe.auction.world.DigitalServiceModel.Contract;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * round2 独立复核：只针对 candidate-3 的 D3 新语义（交付时复核当期额度）。
 *
 * <p>不变量：任何一个被接受的序列，当期「已交付 + 仍开放合同占用 + 本次新增」都不得超过
 * 冻结需求/预算；同期间交付是预约转已交付（净占用不变），跨期旧合同本次交付才新增当期占用；
 * 拒绝时合同、预收与退款义务原样保留；多次部分交付与收款都不恢复额度。</p>
 */
class IndependentRound2DigitalAuditTest {

    private static final long DAY = DigitalServiceModel.DAY_MILLIS;
    private static final String CO = "company";

    private static Config config(long demand, String budget, long capacity, String unitCost) {
        return new Config(CO, "CO", "OPS", "CUS", "CUS", "CON", "CON", "SPON", "SPON",
                new BigDecimal("1.0000"), money(unitCost), demand, money(budget), capacity,
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

        private Rig(Config config) {
            Map<String, CashLedger.CashAccount> map = new LinkedHashMap<>();
            map.put("CO", account(CO, "500.00"));
            map.put("OPS", account("ops", "0.00"));
            map.put("CUS", account("customer", "1000.00"));
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

        private Rig ready() {
            take(DigitalServiceModel.beginStage(state, 0L, CO, "p1", Stage.PILOT, funds, coToCon));
            take(DigitalServiceModel.completeStage(state, 5L * DAY, CO, "p1c", Stage.PILOT,
                    new Evidence("ev-p", "log", DAY), funds));
            take(DigitalServiceModel.beginStage(state, 5L * DAY, CO, "v1", Stage.VALIDATION,
                    funds, coToCon));
            take(DigitalServiceModel.completeStage(state, 15L * DAY, CO, "v1c", Stage.VALIDATION,
                    new Evidence("ev-v", "log", 6L * DAY), funds));
            take(DigitalServiceModel.approveCommercialization(state, 15L * DAY, CO, "ap1",
                    "AP-COMM", funds));
            return this;
        }

        private String sign(long now, String contract, long qty, String prepaid, String command) {
            return take(DigitalServiceModel.signServiceContract(state, now, CO, command, contract,
                    qty, money(prepaid), funds, cusToCo));
        }

        private String deliver(long now, String contract, long units, String ar, String command) {
            return take(DigitalServiceModel.deliverService(state, now, CO, command, contract,
                    units, ar, funds, coToOps));
        }

        private String collect(long now, String ar, String command) {
            return take(DigitalServiceModel.collect(state, now, CO, command, ar, funds, cusToCo));
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

        private void assertCommitted(long period, long expectedUnits, String expectedBudget) {
            long units = state.deliveredIn(period) + state.reservedIn(period);
            BigDecimal budget = state.deliveredRevenueIn(period)
                    .add(state.reservedBudgetIn(period));
            assertEquals(expectedUnits, units, "期间 " + period + " 承诺量");
            assertEquals(money(expectedBudget), budget, "期间 " + period + " 承诺额");
        }
    }

    // ------------------------------------------------- 同期间：预约转已交付不双计

    @Test
    void samePeriodPartialDeliveriesDoNotDoubleCountReservation() {
        Rig rig = new Rig(config(300L, "300.00", 300L, "0.60")).ready();
        assertEquals("SIGNED", rig.sign(16L * DAY, "C1", 300L, "0.00", "sg1"));
        rig.assertCommitted(0L, 300L, "300.00");
        // 若把“本次交付”再加一次，第 3 次会是 200+100+100=400 被拒；实际应三次都接受。
        assertEquals("DELIVERED", rig.deliver(17L * DAY, "C1", 100L, "AR-1", "dl1"));
        rig.assertCommitted(0L, 300L, "300.00");
        assertEquals("DELIVERED", rig.deliver(18L * DAY, "C1", 100L, "AR-2", "dl2"));
        rig.assertCommitted(0L, 300L, "300.00");
        assertEquals("DELIVERED", rig.deliver(19L * DAY, "C1", 100L, "AR-3", "dl3"));
        rig.assertCommitted(0L, 300L, "300.00");
        assertEquals(0L, rig.state.reservedIn(0L));
        assertEquals(300L, rig.state.deliveredIn(0L));
        Report r = rig.report();
        assertEquals(money("300.00"), r.revenue());
        assertEquals(r.assets(), r.liabilities().add(r.equity()));
    }

    // ------------------------------------------------- 跨期：只新增当期占用

    @Test
    void crossPeriodDeliveryAddsOnlyItsOwnUnitsAndRejectsOverrun() {
        Rig rig = new Rig(config(300L, "300.00", 300L, "0.60")).ready();
        assertEquals("SIGNED", rig.sign(16L * DAY, "C1", 300L, "300.00", "sg1"));
        assertEquals("PERIOD_CLOSED", rig.close(30L * DAY, 0L, "cl0"));
        // 期间 1：先签 150（当期额度内），再交上一期合同的 150 → 合计恰好 300。
        assertEquals("SIGNED", rig.sign(31L * DAY, "C2", 150L, "0.00", "sg2"));
        assertEquals("DELIVERED", rig.deliver(31L * DAY, "C1", 150L, "AR-1", "dl1"));
        rig.assertCommitted(1L, 300L, "300.00");

        // 再多 1 件即突破冻结需求：拒绝且合同、预收、退款义务原样保留。
        BigDecimal cashBefore = rig.cash("CO");
        BigDecimal revenueBefore = rig.report().revenue();
        assertEquals("DEMAND_EXCEEDED", rig.deliver(31L * DAY, "C1", 1L, "AR-X", "dlx"));
        Contract c1 = rig.state.contracts().get("C1");
        assertEquals(150L, c1.remaining());
        assertEquals(money("300.00"), c1.prepaid());
        assertEquals(money("150.00"), c1.unearned());
        assertEquals(cashBefore, rig.cash("CO"));
        assertEquals(revenueBefore, rig.report().revenue());
        rig.assertCommitted(1L, 300L, "300.00");
        Report r = rig.report();
        assertEquals(r.assets(), r.liabilities().add(r.equity()));
    }

    // ------------------------------------------------- 需求与预算分别触发正确原因

    @Test
    void budgetAndDemandRaiseTheirOwnReasons() {
        // 预算先紧（150）：需求 250 ≤ 300 但预算 250 > 150 → 只能报 BUDGET_EXCEEDED。
        Rig budgetBound = new Rig(config(300L, "150.00", 300L, "0.30")).ready();
        assertEquals("SIGNED", budgetBound.sign(16L * DAY, "C1", 150L, "0.00", "b-sg1"));
        assertEquals("PERIOD_CLOSED", budgetBound.close(30L * DAY, 0L, "b-cl0"));
        assertEquals("SIGNED", budgetBound.sign(31L * DAY, "C2", 100L, "0.00", "b-sg2"));
        assertEquals("BUDGET_EXCEEDED", budgetBound.deliver(31L * DAY, "C1", 150L, "b-AR", "b-dl"));

        // 需求先紧（100）：预算 150 ≤ 1000 但需求 150 > 100 → 只能报 DEMAND_EXCEEDED。
        Rig demandBound = new Rig(config(100L, "1000.00", 300L, "0.30")).ready();
        assertEquals("SIGNED", demandBound.sign(16L * DAY, "C1", 100L, "0.00", "d-sg1"));
        assertEquals("PERIOD_CLOSED", demandBound.close(30L * DAY, 0L, "d-cl0"));
        assertEquals("SIGNED", demandBound.sign(31L * DAY, "C2", 50L, "0.00", "d-sg2"));
        assertEquals("DEMAND_EXCEEDED", demandBound.deliver(31L * DAY, "C1", 100L, "d-AR", "d-dl"));
    }

    // ------------------------------------------------- 多次部分交付与收款不恢复额度

    @Test
    void repeatedPartialDeliveryAndCollectionNeverRestoreQuota() {
        Rig rig = new Rig(config(300L, "300.00", 300L, "0.30")).ready();
        assertEquals("SIGNED", rig.sign(16L * DAY, "C1", 200L, "0.00", "sg1"));
        assertEquals("PERIOD_CLOSED", rig.close(30L * DAY, 0L, "cl0"));
        assertEquals("DELIVERED", rig.deliver(31L * DAY, "C1", 50L, "AR-1", "dl1"));
        assertEquals("DELIVERED", rig.deliver(32L * DAY, "C1", 50L, "AR-2", "dl2"));
        rig.assertCommitted(1L, 100L, "100.00");

        assertEquals("PERIOD_CLOSED", rig.close(60L * DAY, 1L, "cl1"));
        // 收款只冲应收；期间 1 已交付量与已确认收入一分不减。
        assertEquals("PAYMENT_RECEIVED", rig.collect(61L * DAY, "AR-1", "c1"));
        assertEquals(100L, rig.state.deliveredIn(1L));
        assertEquals(money("100.00"), rig.state.deliveredRevenueIn(1L));
        rig.assertCommitted(1L, 100L, "100.00");
        assertEquals(money("950.00"), rig.cash("CUS"));
        Report r = rig.report();
        assertEquals(money("100.00"), r.revenue());
        assertEquals(money("50.00"), r.receivables());
        assertEquals(r.assets(), r.liabilities().add(r.equity()));
        assertTrue(r.balanced());
    }
}
