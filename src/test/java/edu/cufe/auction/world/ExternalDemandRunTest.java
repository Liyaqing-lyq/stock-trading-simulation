package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static edu.cufe.auction.world.ManufacturingTemplateModel.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalDemandRunTest {
    private static final long DAY = DAY_MILLIS;
    private static final long PERIOD = PERIOD_MILLIS;
    private record Fixture(CompanyOperationsRun operations, Map<String, CashLedger.CashAccount> cash) {}

    @Test void contractsAloneAndImmatureDeliveriesEarnNothing() {
        Fixture signed = fixture(10, false, "1000");
        assertTrue(run(200, "20000", Set.of()).settle(signed.operations(), signed.cash(), 8 * DAY).sales().isEmpty());
        Fixture delivered = fixture(10, true, "1000");
        assertTrue(run(200, "20000", Set.of()).settle(delivered.operations(), delivered.cash(), 5 * DAY).sales().isEmpty());
    }

    @Test void actualDeliveryProducesConservedBilateralResaleAndAuditableCost() {
        Fixture f = fixture(10, true, "1000");
        var result = run(200, "20000", Set.of()).settle(f.operations(), f.cash(), 6 * DAY);
        assertEquals(1, result.sales().size());
        var sale = result.sales().get(0);
        assertEquals(10, sale.quantity());
        assertEquals(m("125"), sale.cashPaid());
        assertEquals(m("100"), sale.inputCost());
        assertEquals(m("25"), sale.profit());
        assertEquals("FINAL", sale.buyerAccountId());
        assertEquals("A-CUS", sale.recipientAccountId());
        assertEquals("A-CO", sale.sourceCompanyAccountId());
        assertEquals(m("875"), result.accounts().get("FINAL").cash());
        assertEquals(m("10125"), result.accounts().get("A-CUS").cash());
        assertEquals(total(f.cash()), total(result.accounts()));
        assertEquals(m("100"), f.operations().companies().get(0).ledger().receivables().get("r").outstanding());
        assertEquals(m("1000"), f.cash().get("FINAL").cash());
        assertThrows(UnsupportedOperationException.class, () -> result.accounts().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.run().sales().clear());
    }

    @Test void demandSpendingAndCashCapsAreSharedAcrossCompanies() {
        Fixture f = twoCompanies("1000");
        assertEquals(12, quantity(run(12, "20000", Set.of()).settle(f.operations(), f.cash(), 6 * DAY)));
        var spending = run(200, "137.50", Set.of()).settle(f.operations(), f.cash(), 6 * DAY);
        assertEquals(11, quantity(spending));
        assertEquals("A", spending.sales().get(0).companyPartyId());
        assertEquals(1, spending.sales().get(spending.sales().size() - 1).quantity());
        Fixture poor = twoCompanies("26");
        assertEquals(2, quantity(run(200, "20000", Set.of()).settle(poor.operations(), poor.cash(), 6 * DAY)));
    }

    @Test void jsonRecoveryAndRepeatDoNotResellOrRedistribute() throws Exception {
        Fixture f = fixture(10, true, "1000");
        var first = run(5, "20000", Set.of("A-SUP")).settle(f.operations(), f.cash(), PERIOD);
        ObjectMapper mapper = new ObjectMapper();
        ExternalDemandRun recovered = mapper.readValue(mapper.writeValueAsBytes(first.run()), ExternalDemandRun.class);
        assertEquals(first.run(), recovered);
        var repeated = recovered.settle(f.operations(), first.accounts(), PERIOD);
        assertTrue(repeated.sales().isEmpty());
        assertTrue(repeated.incomeDistributions().isEmpty());
        assertEquals(first.accounts(), repeated.accounts());
        assertThrows(IllegalArgumentException.class, () -> recovered.settle(f.operations(), first.accounts(), PERIOD - 1));
    }

    @Test void nextPeriodClearsLeftoversButDemandDoesNotMintCash() {
        Fixture f = fixture(10, true, "62.50");
        var first = run(5, "20000", Set.of()).settle(f.operations(), f.cash(), 6 * DAY);
        assertEquals(5, quantity(first));
        var next = first.run().settle(f.operations(), first.accounts(), PERIOD);
        assertEquals(0, quantity(next));
        Fixture funded = fixture(10, true, "125");
        var p0 = run(5, "20000", Set.of()).settle(funded.operations(), funded.cash(), 6 * DAY);
        var p1 = p0.run().settle(funded.operations(), p0.accounts(), PERIOD);
        assertEquals(5, quantity(p1));
        assertEquals(m("0"), p1.accounts().get("FINAL").cash());
    }

    @Test void realSupplierReceiptsFundDemandAndSkippedPeriodsAreNotBackfilled() {
        Fixture f = fixture(100, true, "0");
        var p0 = run(200, "20000", Set.of("A-SUP")).settle(f.operations(), f.cash(), 6 * DAY);
        assertTrue(p0.sales().isEmpty());
        var p3 = p0.run().settle(f.operations(), p0.accounts(), 3 * PERIOD);
        assertEquals(1, p3.incomeDistributions().size());
        assertEquals(3, p3.incomeDistributions().get(0).period());
        assertEquals(m("80"), p3.incomeDistributions().get(0).cashPaid());
        assertEquals(6, quantity(p3));
        assertEquals(m("5"), p3.accounts().get("FINAL").cash());
        assertEquals(total(f.cash()), total(p3.accounts()));
    }

    @Test void zeroDemandAndEmptyPayerAreCausalControls() {
        Fixture funded = fixture(10, true, "1000");
        var zero = run(0, "20000", Set.of()).settle(funded.operations(), funded.cash(), 6 * DAY);
        assertEquals(funded.cash(), zero.accounts());
        assertTrue(zero.sales().isEmpty());
        Fixture empty = fixture(10, true, "0");
        assertTrue(run(200, "20000", Set.of()).settle(empty.operations(), empty.cash(), 6 * DAY).sales().isEmpty());
    }

    @Test void invalidSourcesOwnersAliasesAndConfigurationAreRejected() {
        Fixture f = fixture(10, true, "1000");
        for (String source : List.of("missing", "A-CUS", "A-CO", "FINAL", "A-CERT")) {
            assertThrows(IllegalArgumentException.class, () -> run(200, "20000", Set.of(source)).settle(f.operations(), f.cash(), PERIOD));
        }
        var wrong = new HashMap<>(f.cash());
        wrong.put("A-SUP", new CashLedger.CashAccount("wrong-owner", m("10"), "EXP"));
        assertThrows(IllegalArgumentException.class, () -> run(200, "20000", Set.of("A-SUP")).settle(f.operations(), wrong, PERIOD));
        var alias = new HashMap<>(f.cash());
        alias.put("FINAL", new CashLedger.CashAccount("A-customer", m("1000"), "EXP"));
        var aliasedRun = ExternalDemandRun.opening(new ExternalDemandRun.Config(ExternalDemandRun.RULES, "FINAL", "A-customer", Set.of(), 200, m("20000"), 3 * DAY));
        assertThrows(IllegalArgumentException.class, () -> aliasedRun.settle(f.operations(), alias, PERIOD));
        assertThrows(IllegalArgumentException.class, () -> new ExternalDemandRun.Config("bad", "FINAL", "final", Set.of(), 1, m("1"), 0));
        assertThrows(IllegalArgumentException.class, () -> run(-1, "1", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> run(1, "-1", Set.of()));
    }

    @Test void overflowAndDuplicateReceiptIdsAreRejected() {
        Fixture f = fixture(10, true, "1000");
        var huge = ExternalDemandRun.opening(new ExternalDemandRun.Config(ExternalDemandRun.RULES, "FINAL", "final", Set.of(), 200, m("20000"), Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> huge.settle(f.operations(), f.cash(), 6 * DAY));
        var first = run(200, "20000", Set.of()).settle(f.operations(), f.cash(), 6 * DAY);
        var receipt = first.sales().get(0);
        assertThrows(IllegalArgumentException.class, () -> new ExternalDemandRun(first.run().config(), 6 * DAY, List.of(receipt, receipt), List.of()));
        assertTrue(first.run().settle(f.operations(), first.accounts(), PERIOD).sales().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ExternalDemandRun(first.run().config(), 6 * DAY - 1, List.of(receipt), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExternalDemandRun.SaleReceipt(receipt.outputId(), receipt.companyPartyId(), receipt.receivableId(),
                receipt.contractId(), receipt.customerId(), receipt.sourceCompanyAccountId(), receipt.buyerAccountId(), receipt.recipientAccountId(), Long.MAX_VALUE,
                receipt.unitPrice(), receipt.cashPaid(), receipt.inputCost(), receipt.profit(), receipt.recognizedAt(), receipt.matureAt(), receipt.settledAt(), receipt.period()));
    }

    @Test void emptyBoundaryIsRecordedOnceAndLaterActualIncomeWaitsForNextBoundary() {
        Fixture f = fixture(10, true, "0");
        var first = run(0, "20000", Set.of("A-OPS")).settle(f.operations(), f.cash(), PERIOD);
        assertEquals(1, first.incomeDistributions().size());
        assertEquals(m("0"), first.incomeDistributions().get(0).cashPaid());
        var paidExpense = CashLedger.computeTransfer(first.accounts(), "A", "A-CO", "A-OPS", m("100"));
        assertTrue(paidExpense.accepted());
        var again = first.run().settle(f.operations(), paidExpense.accounts(), PERIOD + 1);
        assertTrue(again.incomeDistributions().isEmpty());
        assertEquals(m("0"), again.accounts().get("FINAL").cash());
        var next = again.run().settle(f.operations(), again.accounts(), 2 * PERIOD);
        assertEquals(m("80"), next.accounts().get("FINAL").cash());
        assertTrue(next.sales().isEmpty());
        assertEquals(total(f.cash()), total(next.accounts()));
    }

    @Test void configurationDefensivelyCopiesSourcesAndUsesFrozenDefaults() {
        Set<String> sources = new HashSet<>(Set.of("A-SUP"));
        var config = ExternalDemandRun.Config.defaults("FINAL", "final", sources);
        sources.clear();
        assertEquals(Set.of("A-SUP"), config.incomeSourceAccountIds());
        assertEquals(200, config.demandUnitsPerPeriod());
        assertEquals(m("20000"), config.spendingPerPeriod());
        assertEquals(3 * DAY, config.processingDelayMillis());
        assertThrows(UnsupportedOperationException.class, () -> config.incomeSourceAccountIds().clear());
        assertThrows(IllegalArgumentException.class, () -> new ExternalDemandRun.Config(ExternalDemandRun.RULES, "FINAL", "final", Set.of(), 1, new BigDecimal("0.001"), 0));
    }

    private static ExternalDemandRun run(long units, String budget, Set<String> sources) {
        return ExternalDemandRun.opening(new ExternalDemandRun.Config(ExternalDemandRun.RULES, "FINAL", "final", sources, units, m(budget), 3 * DAY));
    }
    private static long quantity(ExternalDemandRun.Result result) { return result.sales().stream().mapToLong(ExternalDemandRun.SaleReceipt::quantity).sum(); }
    private static BigDecimal total(Map<String, CashLedger.CashAccount> cash) { return cash.values().stream().map(CashLedger.CashAccount::cash).reduce(m("0"), BigDecimal::add); }
    private static BigDecimal m(String value) { return new BigDecimal(value).setScale(2); }
    private static Fixture fixture(long quantity, boolean deliver, String buyerCash) { return company("A", quantity, deliver, initialCash("A", buyerCash)); }
    private static Map<String, CashLedger.CashAccount> initialCash(String id, String buyerCash) {
        var cash = new LinkedHashMap<String, CashLedger.CashAccount>();
        cash.put(id + "-CO", new CashLedger.CashAccount(id, m("10000"), "EXP opening savings"));
        for (String role : List.of("OPS", "CERT", "CON", "BUY", "SUP")) cash.put(id + "-" + role, new CashLedger.CashAccount(id + "-" + role.toLowerCase(), m("0"), "EXP opening savings"));
        cash.put(id + "-CUS", new CashLedger.CashAccount(id + "-customer", m("10000"), "EXP opening savings"));
        cash.put("FINAL", new CashLedger.CashAccount("final", m(buyerCash), "EXP opening savings"));
        return cash;
    }
    private static Fixture twoCompanies(String buyerCash) {
        var cash = new LinkedHashMap<>(initialCash("A", buyerCash));
        cash.putAll(initialCash("B", buyerCash));
        Fixture a = company("A", 10, true, cash);
        Fixture b = company("B", 10, true, a.cash());
        return new Fixture(new CompanyOperationsRun(CompanyOperationsRun.RULES, List.of(a.operations().companies().get(0), b.operations().companies().get(0)), Map.of()), b.cash());
    }
    private static Fixture company(String id, long quantity, boolean deliver, Map<String, CashLedger.CashAccount> cash) {
        var accounts = new Accounts(id, id + "-CO", id + "-OPS", id + "-CERT", id + "-CON", id + "-BUY", Map.of("CUS", id + "-CUS"), Map.of("SUP", id + "-SUP"));
        var spec = new TemplateSpec("external-demand-test", List.of(new ProductSpec("P", 1, Map.of("M", 1L), m("0"), DAY, false)),
                List.of(new CustomerSpec("CUS", 1000, null, m("100000"))), List.of(new MaterialQuote("M", "SUP", 0, 0, 30 * PERIOD, m("1"), 1000)), List.of(),
                1000, m("130"), m("0"), m("100"), m("200"), DAY, 90 * DAY, m("0.25"), 15 * DAY);
        State state = open(spec, accounts, cash);
        Outcome out = orderMaterial(state, 0, id, "order", "po", "M", quantity, cash); check(out);
        out = receiveMaterial(out.state(), DAY, id, "receive", "po", out.accounts()); check(out);
        out = startBatch(out.state(), DAY, id, "start", "batch", "P", 1, quantity, out.accounts()); check(out);
        out = completeBatch(out.state(), 2 * DAY, id, "finish", "batch", out.accounts()); check(out);
        out = signContract(out.state(), 2 * DAY, id, "sign", "contract", "CUS", "P", 1, quantity, m("10"), 3 * DAY, 10 * DAY, out.accounts()); check(out);
        if (deliver) { out = deliver(out.state(), 3 * DAY, id, "deliver", "contract", quantity, "r", out.accounts()); check(out); }
        return new Fixture(new CompanyOperationsRun(CompanyOperationsRun.RULES, List.of(new CompanyOperationsRun.Company(id, id, spec.templateId(), out.state(), null)), Map.of()), out.accounts());
    }
    private static void check(Outcome out) { assertTrue(out.accepted(), out.reason()); }
}
