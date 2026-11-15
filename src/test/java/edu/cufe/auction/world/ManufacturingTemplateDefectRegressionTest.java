package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;
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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独审 round1 八项缺陷的永久回归（candidate-2 修复后）。
 *
 * <p>本测试不复用独审 probe 的 DEFECT 断言作为通过条件；每项直接断言修复后的合同行为。</p>
 */
class ManufacturingTemplateDefectRegressionTest {

    private static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;
    private static final long P = ManufacturingTemplateModel.PERIOD_MILLIS;
    private static final String CO = "co";
    private static final int P1 = 1;

    // ---------------------------------------------------------------- 1 ramp

    @Test
    void rampCapacityIsNonDecreasingAcrossRampPeriods() {
        State s = ManufacturingTemplateModel.open(spec(90 * DAY, m("250.00"), 1000,
                m("1000000000.00"), 20, false, false), accounts(), cash());
        Map<String, CashAccount> cash = cash();
        s = receive(s, cash, "po", "M1", 100, 1);
        cash = lastCash;
        Outcome ex = ManufacturingTemplateModel.startExpansion(s, 3 * DAY, CO, "e0", "E",
                m("1000.00"), 5 * DAY, 4, 10, cash);
        assertEquals("BUILDING", ex.reason());
        Outcome on = ManufacturingTemplateModel.completeExpansion(ex.state(), 9 * DAY, CO, "e1",
                "E", false, ex.accounts());
        assertEquals("ONLINE", on.reason());
        s = on.state();
        cash = on.accounts();

        s = close(s, cash, 0, 30 * DAY);
        cash = lastCash;
        // k=1: 20+10*1/4=22, 23 拒
        Outcome k1 = ManufacturingTemplateModel.startBatch(s, 31 * DAY, CO, "b1", "b1", "P1", P1,
                23, cash);
        assertEquals("CAPACITY_EXCEEDED", k1.reason());
        s = close(s, cash, 1, 60 * DAY);
        cash = lastCash;
        // k=2: 25，23 可投；k=3: 27 仍可投；k=4: 30
        Outcome k2 = ManufacturingTemplateModel.startBatch(s, 61 * DAY, CO, "b2", "b2", "P1", P1,
                23, cash);
        assertEquals("PRODUCTION_STARTED", k2.reason());
        s = k2.state();
        cash = k2.accounts();
        s = close(s, cash, 2, 90 * DAY);
        cash = lastCash;
        Outcome k3 = ManufacturingTemplateModel.startBatch(s, 91 * DAY, CO, "b3", "b3", "P1", P1,
                23, cash);
        assertEquals("PRODUCTION_STARTED", k3.reason());
        s = k3.state();
        cash = k3.accounts();
        s = close(s, cash, 3, 120 * DAY);
        cash = lastCash;
        Outcome k4 = ManufacturingTemplateModel.startBatch(s, 121 * DAY, CO, "b4", "b4", "P1", P1,
                23, cash);
        assertEquals("PRODUCTION_STARTED", k4.reason());
    }

    // ---------------------------------------------------------------- 2 需求溢出

    @Test
    void demandOverflowRejectedAndNoPoison() {
        State s = ManufacturingTemplateModel.open(spec(90 * DAY, m("250.00"), 10,
                m("1000000000000000000000000000000.00"), 20, false, false), accounts(), cash());
        Map<String, CashAccount> cash = cash();
        Outcome base = sign(s, 1, "h0", 5, m("1.00"), cash);
        assertEquals("SIGNED", base.reason());
        Outcome huge = sign(base.state(), 1, "h1", Long.MAX_VALUE, m("1.00"), base.accounts());
        assertEquals("DEMAND_EXCEEDED", huge.reason());
        Outcome next = sign(huge.state(), 2, "h2", 1, m("1.00"), huge.accounts());
        assertEquals("SIGNED", next.reason());
        assertEquals(2, next.state().contracts().size());
    }

    @Test
    void overdueReleasesUndeliveredReservationOnce() {
        State s = ManufacturingTemplateModel.open(spec(90 * DAY, m("250.00"), 10,
                m("1000000000.00"), 20, false, false), accounts(), cash());
        Map<String, CashAccount> cash = cash();
        Outcome first = ManufacturingTemplateModel.signContract(s, 1, CO, "c1", "c1", "CUS",
                "P1", P1, 10, m("1.00"), 2 * DAY, 3 * DAY, cash);
        assertEquals("SIGNED", first.reason());
        assertEquals(10, first.state().contracts().get("c1").reserved());
        Outcome over = ManufacturingTemplateModel.markOverdue(first.state(), 4 * DAY, CO, "ov1",
                "c1", first.accounts());
        assertEquals("OVERDUE_MARKED", over.reason());
        assertEquals(0, over.state().contracts().get("c1").reserved());
        Outcome again = ManufacturingTemplateModel.markOverdue(over.state(), 4 * DAY, CO, "ov2",
                "c1", over.accounts());
        assertEquals("CONTRACT_STATE_INVALID", again.reason());
        Outcome resign = ManufacturingTemplateModel.signContract(over.state(), 4 * DAY, CO, "c2",
                "c2", "CUS", "P1", P1, 10, m("1.00"), 5 * DAY, 6 * DAY, over.accounts());
        assertEquals("SIGNED", resign.reason());
        assertEquals(10, resign.state().contracts().get("c2").reserved());
    }

    // ---------------------------------------------------------------- 3 现金主体

    @Test
    void cashSourceOwnerComesFromOpeningBusinessSubject() {
        State s = ManufacturingTemplateModel.open(spec(90 * DAY, m("250.00"), 50,
                m("1000000.00"), 20, false, false), accounts(), cash());
        Map<String, CashAccount> cash = cash();
        Outcome order = ManufacturingTemplateModel.orderMaterial(s, 1, CO, "o", "o", "M1", 1,
                cash);
        assertEquals("ORDERED", order.reason());
        Map<String, CashAccount> hacked = new LinkedHashMap<>(order.accounts());
        hacked.put("CO", new CashAccount("HACK", m("50000.00"), "EXP"));
        Outcome rec = ManufacturingTemplateModel.receiveMaterial(order.state(), 2 * DAY, CO,
                "r", "o", hacked);
        assertEquals("TRANSFER_NOT_ALLOWED", rec.reason());
        assertEquals("50000.00", hacked.get("CO").cash().toPlainString());
    }

    // ---------------------------------------------------------------- 4 认证有效期0

    @Test
    void zeroCertValidityRejectedAtSpecConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> spec(0L, m("250.00"), 50, m("1000000.00"), 20, true, true));
    }

    // ---------------------------------------------------------------- 5 费用0

    @Test
    void zeroPeriodExpenseClosesWithoutZeroBill() {
        State s = ManufacturingTemplateModel.open(spec(90 * DAY, m("0.00"), 50,
                m("1000000.00"), 20, false, false), accounts(), cash());
        Map<String, CashAccount> cash = cash();
        Outcome cl = ManufacturingTemplateModel.closePeriod(s, 30 * DAY, CO, "c0", 0, cash);
        assertEquals("PERIOD_CLOSED", cl.reason());
        assertEquals(0, cl.state().bills().size());
        assertEquals("0.00", ManufacturingTemplateModel.report(cl.state(), cl.accounts())
                .expense().toPlainString());
        assertEquals(1, cl.state().closedPeriods());
    }

    // ---------------------------------------------------------------- 6 客户开户

    @Test
    void openRejectsSpecCustomerWithoutAccount() {
        Accounts missing = new Accounts(CO, "CO", "OPS", "CERT", "CON", "BUY",
                Map.of("CUS2", "CUS2"), Map.of("SUP", "SUP"));
        assertThrows(IllegalArgumentException.class,
                () -> ManufacturingTemplateModel.open(spec(90 * DAY, m("250.00"), 50,
                        m("1000000.00"), 20, false, false), missing, cash()));
    }

    // ---------------------------------------------------------------- 7 资金分叉

    @Test
    void replayWithMissingFundRecordDiverges() {
        State s = ManufacturingTemplateModel.open(spec(90 * DAY, m("250.00"), 50,
                m("1000000.00"), 20, true, true), accounts(), cash());
        Map<String, CashAccount> cash = cash();
        FundState f0 = new FundState(cash, BankState.empty(), CustodyBook.empty());
        Payer payer = new Payer("CO", "CERT", Map.of());
        ManufacturingTemplateModel.FundOutcome first = ManufacturingTemplateModel.requestCert(s,
                1, CO, "x", "t", "CUS", "P1", P1, 1, evidence(), f0, payer);
        assertEquals("CERT_REQUESTED", first.reason());
        ManufacturingTemplateModel.FundOutcome good = ManufacturingTemplateModel.requestCert(
                first.state(), 1, CO, "x", "t", "CUS", "P1", P1, 1, evidence(), first.funds(),
                payer);
        assertTrue(good.reason().startsWith("REPLAYED"));
        Payer fresh = new Payer("CO", "CERT", Map.of());
        ManufacturingTemplateModel.FundOutcome bad = ManufacturingTemplateModel.requestCert(
                first.state(), 1, CO, "x", "t", "CUS", "P1", P1, 1, evidence(), f0, fresh);
        assertEquals(ManufacturingTemplateModel.FUND_RECORD_DIVERGED, bad.reason());
        assertFalse(bad.accepted());
    }

    // ---------------------------------------------------------------- 8 合法重试/无半写

    @Test
    void legalRetryAfterFailureAndWrongActorNoHalfWrite() {
        State s = ManufacturingTemplateModel.open(spec(90 * DAY, m("250.00"), 50,
                m("1000000.00"), 20, true, false), accounts(), cash());
        Map<String, CashAccount> cash = cash();
        Outcome wrong = ManufacturingTemplateModel.signContract(s, 1, "intruder", "w", "w", "CUS",
                "P1", P1, 1, m("1.00"), 2 * DAY, 3 * DAY, cash);
        assertEquals("NOT_AUTHORIZED", wrong.reason());
        assertEquals(s, wrong.state());

        Outcome r1 = ManufacturingTemplateModel.requestCert(s, 1, CO, "q1", "t1", "CUS", "P1",
                P1, 1, evidence(), cash);
        assertEquals("CERT_REQUESTED", r1.reason());
        Outcome f1 = ManufacturingTemplateModel.finishCert(r1.state(), 2 * DAY, CO, "f1", "t1",
                r1.accounts());
        assertEquals("CERT_FAILED", f1.reason());
        assertFalse(hasCert(f1.state()));

        Outcome r2 = ManufacturingTemplateModel.requestCert(f1.state(), 2 * DAY, CO, "q2", "t2",
                "CUS", "P1", P1, 2, evidence(), f1.accounts());
        assertEquals("CERT_REQUESTED", r2.reason());
        Outcome f2 = ManufacturingTemplateModel.finishCert(r2.state(), 3 * DAY, CO, "f2", "t2",
                r2.accounts());
        assertEquals("CERTIFIED", f2.reason());
        assertTrue(hasCert(f2.state()));
        assertEquals("49600.00", f2.accounts().get("CO").cash().toPlainString());
    }

    // ---------------------------------------------------------------- 夹具

    private static Map<String, CashAccount> lastCash = Map.of();

    private static boolean hasCert(State state) {
        return !state.certs().isEmpty();
    }

    private static CertEvidence evidence() {
        return new CertEvidence("ev", "CUS", "P1", P1, 0, 0, "reader");
    }

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

    private static TemplateSpec spec(long validity, BigDecimal expense, long demand,
                                     BigDecimal budget, long base, boolean cert,
                                     boolean firstPass) {
        return new TemplateSpec("regression",
                List.of(new ProductSpec("P1", P1, Map.of("M1", 1L), m("0.00"), 10 * DAY, cert)),
                List.of(new CustomerSpec("CUS", demand, null, budget),
                        new CustomerSpec("CUS2", demand, null, budget)),
                List.of(new MaterialQuote("M1", "SUP", 0, 0, 30 * P, m("1.00"), 1000)),
                List.of(new CertPlan("CUS", "P1", P1, 1, firstPass, 0),
                        new CertPlan("CUS", "P1", P1, 2, true, 0),
                        new CertPlan("CUS2", "P1", P1, 1, true, 0)),
                base, m("130.00"), expense, m("100.00"), m("200.00"), DAY, validity,
                m("0.25"), 15 * DAY);
    }

    private static State receive(State state, Map<String, CashAccount> cash, String po,
                                 String material, long qty, long now) {
        Outcome order = ManufacturingTemplateModel.orderMaterial(state, now, CO, "ord-" + po, po,
                material, qty, cash);
        assertEquals("ORDERED", order.reason());
        Outcome rec = ManufacturingTemplateModel.receiveMaterial(order.state(), now + DAY, CO,
                "rec-" + po, po, order.accounts());
        assertEquals("GOODS_RECEIVED", rec.reason());
        lastCash = rec.accounts();
        return rec.state();
    }

    private static State close(State state, Map<String, CashAccount> cash, long period, long now) {
        Outcome cl = ManufacturingTemplateModel.closePeriod(state, now, CO, "cl" + period, period,
                cash);
        if (!cl.accepted() && !"PERIOD_ALREADY_CLOSED".equals(cl.reason())) {
            throw new IllegalStateException("关期失败：" + cl.reason());
        }
        lastCash = cl.accounts();
        return cl.state();
    }

    private static Outcome sign(State state, long now, String id, long qty, BigDecimal price,
                                Map<String, CashAccount> cash) {
        return ManufacturingTemplateModel.signContract(state, now, CO, "sig-" + id, id, "CUS",
                "P1", P1, qty, price, now + DAY, now + 2 * DAY, cash);
    }

    /**
     * 测试用资金腿：现金桶付款，冻结按桶扣减。
     */
    private static final class Payer implements FundPayer {

        private final String src;
        private final String dst;
        private final Map<String, BigDecimal> frozen;
        private final Set<String> records = new java.util.LinkedHashSet<>();
        private final Set<String> accepted = new java.util.LinkedHashSet<>();

        private Payer(String src, String dst, Map<String, BigDecimal> frozen) {
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
            return funds.cash(src).subtract(frozen.getOrDefault(src, m("0.00"))).max(m("0.00"));
        }
    }
}
