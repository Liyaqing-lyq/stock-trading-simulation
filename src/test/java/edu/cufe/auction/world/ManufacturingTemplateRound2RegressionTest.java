package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.ManufacturingTemplateModel.Accounts;
import edu.cufe.auction.world.ManufacturingTemplateModel.CustomerSpec;
import edu.cufe.auction.world.ManufacturingTemplateModel.MaterialQuote;
import edu.cufe.auction.world.ManufacturingTemplateModel.Outcome;
import edu.cufe.auction.world.ManufacturingTemplateModel.ProductSpec;
import edu.cufe.auction.world.ManufacturingTemplateModel.State;
import edu.cufe.auction.world.ManufacturingTemplateModel.TemplateSpec;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独审 round2 的累计需求/预算与产能溢出永久回归（candidate-3）。
 *
 * <p>基线：当期有效占用 = 每合同 delivered + reserved；取消/逾期只释放未交量，
 * 已交部分仍占本期额度；预算按同一数量乘合同锁价。产能用 BigInteger 精确计算。</p>
 */
class ManufacturingTemplateRound2RegressionTest {

    private static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;
    private static final long P = ManufacturingTemplateModel.PERIOD_MILLIS;
    private static final String CO = "co";

    // ---------------------------------------------------------------- R1-R3

    @Test
    void r1DeliveryDoesNotRestoreDemand() {
        Holder h = stocked(spec(10, m("500.00")));
        Outcome c = sign(h, 13 * DAY, "r1", 10, m("50.00"));
        assertEquals("SIGNED", c.reason());
        Outcome d = ManufacturingTemplateModel.deliver(c.state(), 14 * DAY, CO, "r1d", "r1", 10,
                "rc1", c.accounts());
        assertEquals("DELIVERED", d.reason());
        Outcome again = sign(d.state(), d.accounts(), 15 * DAY, "r2", 1, m("50.00"));
        assertEquals("DEMAND_EXCEEDED", again.reason());
    }

    @Test
    void r2SplitDeliveryDoesNotRestoreDemand() {
        Holder h = stocked(spec(10, m("500.00")));
        Outcome c = sign(h, 13 * DAY, "r3", 10, m("50.00"));
        Outcome d1 = ManufacturingTemplateModel.deliver(c.state(), 14 * DAY, CO, "r3d1", "r3", 6,
                "rc2", c.accounts());
        assertEquals("DELIVERED", d1.reason());
        Outcome d2 = ManufacturingTemplateModel.deliver(d1.state(), 15 * DAY, CO, "r3d2", "r3", 4,
                "rc3", d1.accounts());
        assertEquals("DELIVERED", d2.reason());
        Outcome again = sign(d2.state(), d2.accounts(), 16 * DAY, "r4", 1, m("50.00"));
        assertEquals("DEMAND_EXCEEDED", again.reason());
    }

    @Test
    void r3BudgetIsCumulativeAfterDelivery() {
        Holder h = stocked(spec(100, m("500.00")));
        Outcome c = sign(h, 13 * DAY, "r5", 10, m("50.00"));
        Outcome d = ManufacturingTemplateModel.deliver(c.state(), 14 * DAY, CO, "r5d", "r5", 10,
                "rc4", c.accounts());
        assertEquals("DELIVERED", d.reason());
        Outcome again = sign(d.state(), d.accounts(), 15 * DAY, "r6", 1, m("50.00"));
        assertEquals("BUDGET_EXCEEDED", again.reason());
    }

    // ---------------------------------------------------------------- R4-R6

    @Test
    void r4CancelReleasesOnlyUndeliveredOnce() {
        Holder h = stocked(spec(10, m("500.00")));
        Outcome c = sign(h, 13 * DAY, "r7", 10, m("50.00"));
        Outcome d = ManufacturingTemplateModel.deliver(c.state(), 14 * DAY, CO, "r7d", "r7", 4,
                "rc5", c.accounts());
        assertEquals("DELIVERED", d.reason());
        Outcome cn = ManufacturingTemplateModel.cancelContract(d.state(), 15 * DAY, CO, "r7c", "r7",
                d.accounts());
        assertEquals("CANCELLED", cn.reason());
        assertEquals(4, cn.state().contracts().get("r7").delivered());
        assertEquals(0, cn.state().contracts().get("r7").reserved());
        Outcome six = sign(cn.state(), cn.accounts(), 16 * DAY, "r8", 6, m("50.00"));
        assertEquals("SIGNED", six.reason());
        Outcome seven = sign(six.state(), six.accounts(), 17 * DAY, "r9", 1, m("50.00"));
        assertEquals("DEMAND_EXCEEDED", seven.reason());
    }

    @Test
    void r5OverdueReleasesOnlyUndeliveredOnce() {
        Holder h = stocked(spec(10, m("500.00")));
        Outcome c = sign(h, 13 * DAY, "ra", 10, m("50.00"));
        Outcome d = ManufacturingTemplateModel.deliver(c.state(), 14 * DAY, CO, "rad", "ra", 4,
                "rc6", c.accounts());
        Outcome ov = ManufacturingTemplateModel.markOverdue(d.state(), 21 * DAY, CO, "rao", "ra",
                d.accounts());
        assertEquals("OVERDUE_MARKED", ov.reason());
        assertEquals(0, ov.state().contracts().get("ra").reserved());
        Outcome six = sign(ov.state(), ov.accounts(), 22 * DAY, "rb", 6, m("50.00"));
        assertEquals("SIGNED", six.reason());
        Outcome seven = sign(six.state(), six.accounts(), 23 * DAY, "rc", 1, m("50.00"));
        assertEquals("DEMAND_EXCEEDED", seven.reason());
    }

    @Test
    void r6CollectionDoesNotRestoreDemand() {
        Holder h = stocked(spec(10, m("500.00")));
        Outcome c = sign(h, 13 * DAY, "rd", 10, m("50.00"));
        Outcome d = ManufacturingTemplateModel.deliver(c.state(), 14 * DAY, CO, "rdd", "rd", 10,
                "rc7", c.accounts());
        assertEquals("DELIVERED", d.reason());
        Outcome col = ManufacturingTemplateModel.collect(d.state(), 29 * DAY, CO, "rdc", "rc7",
                d.accounts());
        assertEquals("PAYMENT_RECEIVED", col.reason());
        Outcome again = sign(col.state(), col.accounts(), 29 * DAY, "re", 1, m("50.00"));
        assertEquals("DEMAND_EXCEEDED", again.reason());
    }

    // ---------------------------------------------------------------- 跨期/跨产品/价格/坏账

    @Test
    void newPeriodGetsFreshQuota() {
        Holder h = stocked(spec(10, m("500.00")));
        Outcome first = sign(h, 13 * DAY, "p1", 10, m("50.00"));
        assertEquals("SIGNED", first.reason());
        State s = close(first.state(), first.accounts(), 0, 30 * DAY);
        Map<String, CashAccount> cash = lastCash;
        Outcome second = sign(s, cash, 31 * DAY, "p2", 10, m("50.00"));
        assertEquals("SIGNED", second.reason());
    }

    @Test
    void twoProductsShareCustomerQuota() {
        Holder h = stocked(spec(10, m("1000.00")));
        Outcome p1 = ManufacturingTemplateModel.signContract(h.s, 13 * DAY, CO, "t1", "t1", "CUS",
                "P1", 1, 6, m("50.00"), 14 * DAY, 20 * DAY, h.cash);
        assertEquals("SIGNED", p1.reason());
        Outcome p2 = ManufacturingTemplateModel.signContract(p1.state(), 13 * DAY, CO, "t2", "t2",
                "CUS", "P2", 1, 5, m("50.00"), 14 * DAY, 20 * DAY, p1.accounts());
        assertEquals("DEMAND_EXCEEDED", p2.reason());
    }

    @Test
    void budgetUsesEachContractLockedPrice() {
        Holder h = stocked(spec(100, m("500.00")));
        Outcome a = sign(h, 13 * DAY, "b1", 5, m("60.00"));
        assertEquals("SIGNED", a.reason());
        Outcome b = sign(a.state(), a.accounts(), 13 * DAY, "b2", 5, m("40.00"));
        assertEquals("SIGNED", b.reason());
        Outcome c = sign(b.state(), b.accounts(), 13 * DAY, "b3", 1, m("40.00"));
        assertEquals("BUDGET_EXCEEDED", c.reason());
    }

    @Test
    void badDebtWriteOffDoesNotRestoreDemand() {
        Holder h = stocked(spec(10, m("500.00"), 0L));
        Outcome c = sign(h, 13 * DAY, "w1", 10, m("50.00"));
        Outcome d = ManufacturingTemplateModel.deliver(c.state(), 14 * DAY, CO, "w1d", "w1", 10,
                "rw1", c.accounts());
        Outcome w = ManufacturingTemplateModel.writeOffReceivable(d.state(), 29 * DAY, CO, "w1w",
                "rw1", d.accounts());
        assertEquals("WRITTEN_OFF", w.reason());
        Outcome again = sign(w.state(), w.accounts(), 29 * DAY, "w2", 1, m("50.00"));
        assertEquals("DEMAND_EXCEEDED", again.reason());
    }

    // ---------------------------------------------------------------- S2/S3

    @Test
    void s2CapacitySumOverflowRejectedBeforePayment() {
        Holder h = new Holder(ManufacturingTemplateModel.open(spec(100, m("1000000.00")),
                accounts(), cash()), cash());
        Outcome a = ManufacturingTemplateModel.startExpansion(h.s, 1, CO, "s2a", "A", m("100.00"),
                5 * DAY, 1, 5_000_000_000_000_000_000L, h.cash);
        assertEquals("BUILDING", a.reason());
        Outcome b = ManufacturingTemplateModel.startExpansion(a.state(), 1, CO, "s2b", "B",
                m("100.00"), 5 * DAY, 1, 5_000_000_000_000_000_000L, a.accounts());
        assertEquals(ManufacturingTemplateModel.CAPACITY_OVERFLOW, b.reason());
        assertEquals(1, b.state().expansions().size());
        assertEquals("49900.00", b.accounts().get("CO").cash().toPlainString());
        Outcome on = ManufacturingTemplateModel.completeExpansion(b.state(), 6 * DAY, CO, "s2c",
                "A", false, b.accounts());
        assertEquals("ONLINE", on.reason());
        State s = close(on.state(), on.accounts(), 0, 30 * DAY);
        Map<String, CashAccount> cash = lastCash;
        Outcome batch = ManufacturingTemplateModel.startBatch(s, 31 * DAY, CO, "s2e", "s2e",
                "P1", 1, 4_900_000_000_000_000_000L, cash);
        assertEquals("MATERIAL_SHORT", batch.reason());
    }

    @Test
    void s3RampUsesExactBigInteger() {
        Holder h = new Holder(ManufacturingTemplateModel.open(spec(100, m("1000000.00")),
                accounts(), cash()), cash());
        Outcome a = ManufacturingTemplateModel.startExpansion(h.s, 1, CO, "s3a", "H", m("100.00"),
                5 * DAY, 7, 4_000_000_000_000_000_000L, h.cash);
        assertEquals("BUILDING", a.reason());
        Outcome on = ManufacturingTemplateModel.completeExpansion(a.state(), 6 * DAY, CO, "s3b",
                "H", false, a.accounts());
        State s = on.state();
        Map<String, CashAccount> cash = on.accounts();
        for (long p = 0; p < 6; p++) {
            s = close(s, cash, p, (p + 1) * 30 * DAY);
            cash = lastCash;
        }
        // k=6 精确容量 20 + floor(4e18*6/7) = 3_428_571_428_571_428_591
        Outcome over = ManufacturingTemplateModel.startBatch(s, 181 * DAY, CO, "s3c", "s3c",
                "P1", 1, 3_500_000_000_000_000_000L, cash);
        assertEquals("CAPACITY_EXCEEDED", over.reason());
        Outcome fits = ManufacturingTemplateModel.startBatch(s, 181 * DAY, CO, "s3d", "s3d",
                "P1", 1, 3_400_000_000_000_000_000L, cash);
        assertEquals("MATERIAL_SHORT", fits.reason());
    }

    @Test
    void bomMultiplyOverflowControlledWithoutHalfWrite() {
        TemplateSpec huge = new TemplateSpec("huge-base",
                List.of(new ProductSpec("P1", 1, Map.of("M1", 2L), m("0.00"), 10 * DAY, false)),
                List.of(new CustomerSpec("CUS", Long.MAX_VALUE, null, m("1000000.00"))),
                List.of(new MaterialQuote("M1", "SUP", 0, 0, 30 * P, m("1.00"), Long.MAX_VALUE)),
                List.of(), Long.MAX_VALUE, m("130.00"), m("250.00"), m("100.00"), m("0.00"),
                0L, 0L, m("0.25"), 15 * DAY);
        State s = ManufacturingTemplateModel.open(huge, accounts(), cash());
        Map<String, CashAccount> cash = cash();
        Outcome o = ManufacturingTemplateModel.startBatch(s, 1, CO, "huge", "huge", "P1", 1,
                Long.MAX_VALUE, cash);
        assertEquals("INVALID_QUANTITY", o.reason());
        assertEquals(s, o.state());
        assertEquals("50000.00", o.accounts().get("CO").cash().toPlainString());
    }

    // ---------------------------------------------------------------- 夹具

    private record Holder(State s, Map<String, CashAccount> cash) {
    }

    private static Map<String, CashAccount> lastCash = Map.of();

    private static BigDecimal m(String v) {
        return new BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static Map<String, CashAccount> cash() {
        Map<String, CashAccount> cash = new LinkedHashMap<>();
        cash.put("CO", new CashAccount(CO, m("50000.00"), "EXP"));
        cash.put("OPS", new CashAccount("ops", m("5000.00"), "EXP"));
        cash.put("CERT", new CashAccount("certifier", m("0.00"), "EXP"));
        cash.put("CON", new CashAccount("contractor", m("0.00"), "EXP"));
        cash.put("BUY", new CashAccount("buyer", m("0.00"), "EXP"));
        cash.put("CUS", new CashAccount("customer", m("100000.00"), "EXP"));
        cash.put("CUS2", new CashAccount("customer2", m("50000.00"), "EXP"));
        cash.put("SUP", new CashAccount("supplier", m("0.00"), "EXP"));
        return cash;
    }

    private static Accounts accounts() {
        return new Accounts(CO, "CO", "OPS", "CERT", "CON", "BUY",
                Map.of("CUS", "CUS", "CUS2", "CUS2"), Map.of("SUP", "SUP"));
    }

    private static TemplateSpec spec(long demand, BigDecimal budget) {
        return spec(demand, budget, 15 * DAY);
    }

    private static TemplateSpec spec(long demand, BigDecimal budget, long grace) {
        return new TemplateSpec("round2",
                List.of(new ProductSpec("P1", 1, Map.of("M1", 1L), m("0.00"), 10 * DAY, false),
                        new ProductSpec("P2", 1, Map.of("M1", 1L), m("0.00"), 10 * DAY, false)),
                List.of(new CustomerSpec("CUS", demand, null, budget),
                        new CustomerSpec("CUS2", demand, null, budget)),
                List.of(new MaterialQuote("M1", "SUP", 0, 0, 30 * P, m("1.00"), Long.MAX_VALUE)),
                List.of(),
                100, m("130.00"), m("250.00"), m("100.00"), m("0.00"), 0L, 0L,
                m("0.25"), grace);
    }

    private static Holder stocked(TemplateSpec spec) {
        State s = ManufacturingTemplateModel.open(spec, accounts(), cash());
        Map<String, CashAccount> cash = cash();
        Outcome order = ManufacturingTemplateModel.orderMaterial(s, 1, CO, "po", "po", "M1", 20,
                cash);
        assertEquals("ORDERED", order.reason());
        Outcome rec = ManufacturingTemplateModel.receiveMaterial(order.state(), 2 * DAY, CO, "rec",
                "po", order.accounts());
        assertEquals("GOODS_RECEIVED", rec.reason());
        Outcome start = ManufacturingTemplateModel.startBatch(rec.state(), 3 * DAY, CO, "b", "b",
                "P1", 1, 20, rec.accounts());
        assertEquals("PRODUCTION_STARTED", start.reason());
        Outcome done = ManufacturingTemplateModel.completeBatch(start.state(), 13 * DAY, CO,
                "bd", "b", start.accounts());
        assertEquals("PRODUCTION_COMPLETED", done.reason());
        return new Holder(done.state(), done.accounts());
    }

    private static Outcome sign(Holder h, long now, String id, long qty, BigDecimal price) {
        return sign(h.s, h.cash, now, id, qty, price);
    }

    private static Outcome sign(State s, Map<String, CashAccount> cash, long now, String id,
                                long qty, BigDecimal price) {
        return ManufacturingTemplateModel.signContract(s, now, CO, id, id, "CUS", "P1", 1, qty,
                price, now + DAY, now + 2 * DAY, cash);
    }

    private static State close(State s, Map<String, CashAccount> cash, long period, long now) {
        Outcome cl = ManufacturingTemplateModel.closePeriod(s, now, CO, "cl" + period, period,
                cash);
        assertEquals("PERIOD_CLOSED", cl.reason());
        lastCash = cl.accounts();
        return cl.state();
    }
}
