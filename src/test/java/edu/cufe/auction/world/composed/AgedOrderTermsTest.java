package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.AgedIndustryConfig;
import edu.cufe.auction.world.AgedOperations;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CompanyOperationsRun;
import edu.cufe.auction.world.RoleEvidenceDocuments;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static edu.cufe.auction.world.composed.BaselineIndustryWorldTest.cash;
import static edu.cufe.auction.world.composed.BaselineIndustryWorldTest.total;
import static org.junit.jupiter.api.Assertions.*;

class AgedOrderTermsTest {
    @TempDir Path dir;
    private static final String COMPANY = AgedIndustryConfig.COMPANY;
    private static final String MANAGER = AgedIndustryConfig.MANAGER_PERSON;
    private static final String CHAIR = BaselineIndustryConfig.directors(COMPANY).get(0);
    private static final String DOC = RoleEvidenceDocuments.documentId(COMPANY);
    private static final String TERMS = "orderTerms";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void directorsReadOwnTermsBeforeBudgetingAndOtherCompaniesDoNotReceiveThem() throws Exception {
        var path = dir.resolve("terms.journal");
        String readInput;
        try (var w = ComposedIndustryScenario.openAgedOrderOperating(path)) {
            var chair = w.autonomyRoleObservation(CHAIR);
            assertTrue(chair.path("profile").path("catalog").toString().contains(DOC));
            assertFalse(chair.toString().contains(TERMS));
            assertFalse(w.autonomyRoleObservation(MANAGER).toString().contains(DOC));
            assertFalse(w.autonomyRoleObservation("mfgtech-d1").toString().contains(DOC));
            var evidence = read(w, CHAIR);
            var terms = evidence.required(TERMS);
            checkTerms(terms);
            assertFalse(evidence.has("orders"));
            for (String secret : List.of("demand", "terminalAccountId", "supplierAccountId",
                    AgedIndustryConfig.TERMINAL_ACCOUNT, AgedIndustryConfig.CHANNEL_ACCOUNT))
                assertFalse(evidence.toString().contains(secret), secret);
            assertTrue(w.boardOfCompany(COMPANY).orElseThrow().board().budgets().isEmpty());
            readInput = w.autonomyRoleObservation(CHAIR).toString();
        }
        byte[] sealed = Files.readAllBytes(path);
        try (var w = ComposedIndustryScenario.openAgedOrderOperating(path)) {
            assertEquals(readInput, w.autonomyRoleObservation(CHAIR).toString());
        }
        assertArrayEquals(sealed, Files.readAllBytes(path));
        try (var w = ComposedIndustryScenario.openForwardBudgetOperating(dir.resolve("v6.journal"))) {
            assertFalse(w.profile().manifest().dataVersions().containsKey("aged-order-terms"));
            assertFalse(read(w, CHAIR).has(TERMS));
        }
    }

    @Test void receiptUsesItsExecutionPeriodAndMovesRealSupplierCashOnlyOnce() throws Exception {
        for (boolean nextPeriodApproved : List.of(false, true)) {
            Path path = dir.resolve("receipt-" + nextPeriodApproved + ".journal");
            WorldRuntime.Snapshot end;
            String input;
            try (var w = ComposedIndustryScenario.openAgedOrderOperating(path)) {
                var lines = new ArrayList<BoardModel.BudgetLine>();
                lines.add(new BoardModel.BudgetLine(0L, BoardModel.BudgetCategory.PROCUREMENT, new BigDecimal("100.00")));
                if (nextPeriodApproved)
                    lines.add(new BoardModel.BudgetLine(1L, BoardModel.BudgetCategory.PROCUREMENT, new BigDecimal("70.00")));
                OperationsFixture.grantLinesAndAppoint(w, COMPANY, BaselineIndustryConfig.directors(COMPANY),
                        MANAGER, "aged", lines);
                var profile = w.autonomyRoleObservation(MANAGER);
                checkTerms(profile.at("/engineering/agedOperations").required(TERMS));
                var periods = profile.at("/engineering/managerships/0/budgetWindow/periods");
                assertEquals("PROCUREMENT", periods.at("/0/lines/0/category").asText());
                assertEquals(nextPeriodApproved ? 1 : 0, periods.at("/1/lines").size());
                var initialCash = cash(w, AgedIndustryConfig.COMPANY_ACCOUNT);
                var initialTotal = total(w);
                assertTrue(act(w, "order", AgedOperations.Action.ORDER_RAW, Map.of("orderId","raw", "units","5")).accepted());
                var order = w.autonomyRoleObservation(MANAGER).at("/engineering/agedOperations/orders/0");
                assertEquals("raw", order.path("orderId").asText());
                assertEquals("0", order.path("placedAtPeriod").asText());
                assertEquals("1", order.path("duePeriod").asText());
                assertEquals("PERIOD_NOT_ENDED", act(w, "early", AgedOperations.Action.RECEIVE_RAW,
                        Map.of("orderId", "raw")).reason());
                assertEquals(initialCash, cash(w, AgedIndustryConfig.COMPANY_ACCOUNT));
                w.advanceTo(AgedIndustryConfig.PERIOD_MILLIS);
                var before = cash(w, AgedIndustryConfig.COMPANY_ACCOUNT);
                var supplier = cash(w, AgedIndustryConfig.SUPPLIER_ACCOUNT);
                var result = act(w, "receive", AgedOperations.Action.RECEIVE_RAW, Map.of("orderId","raw"));
                assertEquals(nextPeriodApproved, result.accepted(), result.reason());
                var ledger = w.operationsRun().orElseThrow().aged().ledger();
                if (nextPeriodApproved) {
                    assertEquals(before.subtract(new BigDecimal("50.00")), cash(w, AgedIndustryConfig.COMPANY_ACCOUNT));
                    assertEquals(supplier.add(new BigDecimal("50.00")), cash(w, AgedIndustryConfig.SUPPLIER_ACCOUNT));
                    assertEquals(5L, ledger.rawLots().get(0).remaining());
                    assertTrue(ledger.orders().isEmpty());
                    assertEquals("20.00", w.autonomyRoleObservation(MANAGER)
                            .at("/engineering/managerships/0/budgetWindow/periods/0/lines/0/available").asText());
                    assertEquals("UNKNOWN_ORDER", act(w, "repeat", AgedOperations.Action.RECEIVE_RAW,
                            Map.of("orderId","raw")).reason());
                    assertEquals("BUDGET_NOT_SET", act(w, "produce", AgedOperations.Action.PRODUCE,
                            Map.of("batchId","batch", "units","5")).reason());
                } else {
                    assertEquals("BUDGET_NOT_SET", result.reason());
                    assertEquals(before, cash(w, AgedIndustryConfig.COMPANY_ACCOUNT));
                    assertEquals(supplier, cash(w, AgedIndustryConfig.SUPPLIER_ACCOUNT));
                    assertEquals(1, ledger.orders().size());
                    assertTrue(ledger.rawLots().isEmpty());
                    w.advanceTo(2 * AgedIndustryConfig.PERIOD_MILLIS - 1);
                    assertEquals(1, w.operationsRun().orElseThrow().aged().ledger().orders().size());
                    w.advanceTo(2 * AgedIndustryConfig.PERIOD_MILLIS);
                    var closed = w.operationsRun().orElseThrow().aged().ledger();
                    assertTrue(closed.orders().isEmpty());
                    assertTrue(closed.reports().stream().anyMatch(r -> r.atPeriod() == 1
                            && "EXPIRED_UNRECEIVED".equals(r.action()) && "raw".equals(r.refId())));
                    assertEquals("UNKNOWN_ORDER", act(w, "late", AgedOperations.Action.RECEIVE_RAW,
                            Map.of("orderId","raw")).reason());
                    assertEquals(supplier, cash(w, AgedIndustryConfig.SUPPLIER_ACCOUNT));
                }
                assertEquals(initialTotal, total(w));
                end = w.snapshot();
                input = w.autonomyRoleObservation(MANAGER).toString();
            }
            try (var w = ComposedIndustryScenario.openAgedOrderOperating(path)) {
                assertEquals(end, w.snapshot());
                assertEquals(input, w.autonomyRoleObservation(MANAGER).toString());
            }
        }
    }

    private static void checkTerms(JsonNode terms) {
        assertEquals("下单不付现", terms.required("orderPayment").asText());
        assertEquals(1, terms.required("earliestReceiptAfterPeriods").intValue());
        assertTrue(terms.required("receiptPayment").asText().contains("实际收货期的 PROCUREMENT"));
        assertTrue(terms.required("conversionPayment").asText().contains("实际投产期的 PRODUCTION_LABOR"));
        assertEquals("到期期间关账时，尚未收货的订单失效", terms.required("receiptDeadline").asText());
        assertEquals(2, terms.required("agingPeriodsBeforeShipment").intValue());
    }

    private static JsonNode read(WorldRuntime w, String person) throws Exception {
        long due = w.snapshot().timeMillis() + 1;
        w.scheduleStartReading("read-terms", "read-terms-task", due, person, DOC, 1);
        w.advanceTo(due + 10_000);
        var reads = w.autonomyRoleObservation(person).at("/profile/readExperiences");
        for (var row : reads)
            if (DOC.equals(row.path("docId").asText())) return JSON.readTree(row.required("body").asText());
        throw new AssertionError("未找到本次完成的公司资料阅读");
    }

    private static CompanyOperationsRun.ReportEntry act(WorldRuntime w, String id, AgedOperations.Action action,
                                                         Map<String,String> fields) throws Exception {
        long due = w.snapshot().timeMillis() + 2_000;
        w.openRoleSession(MANAGER).agedAction(id, id + "-task", due, COMPANY,
                new AgedOperations.Request(action, fields), 1_000);
        w.advanceTo(due);
        return w.operationsRun().orElseThrow().report(id + "-task:finish");
    }
}
