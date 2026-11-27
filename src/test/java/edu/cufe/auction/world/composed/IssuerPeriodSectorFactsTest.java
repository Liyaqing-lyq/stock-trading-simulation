package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static edu.cufe.auction.world.composed.BaselineIndustryWorldTest.aged;
import static edu.cufe.auction.world.composed.BaselineIndustryWorldTest.digital;
import static edu.cufe.auction.world.composed.ResourceWorldChainTest.act;
import static edu.cufe.auction.world.composed.ResourceWorldChainTest.request;
import static org.junit.jupiter.api.Assertions.*;

/** 用实际经营入口、现金往来和两期冻结报表复核五类行业取数。 */
class IssuerPeriodSectorFactsTest {
    @TempDir Path dir;
    private static final long DAY = 86_400_000L;
    private static final long PERIOD = 30 * DAY;

    @Test void resourceDeliveryAndPaidExpansionComeFromSeparateAccounts() throws Exception {
        String company = ComposedIndustryScenario.RESOURCE;
        var file = dir.resolve("resource.journal");
        try (var w = ComposedIndustryScenario.openCurrentTenCompanyStudy(file)) {
            govern(w, company, ComposedIndustryScenario.DIRECTORS, ComposedIndustryScenario.MANAGER);
            var before = cash(w, "RES-CONTRACTOR");
            act(w, "expansion", request(ResourceOperations.Action.START_EXPANSION, "projectId", "expansion"));
            var capex = cash(w, "RES-CONTRACTOR").subtract(before);
            assertEquals(new BigDecimal("2000.00"), capex);
            act(w, "mine", request(ResourceOperations.Action.BEGIN_EXTRACTION, "jobId", "mine", "seamId", "resource-seam-a", "quantityKg", "100"));
            w.advanceTo(ResourceWorldChainTest.resource(w).jobs().get("mine").dueAt());
            act(w, "sale", request(ResourceOperations.Action.SELL_COAL, "deliveryId", "sale", "quantityKg", "100", "receivableId", "sale-ar"));
            var invoice = ResourceWorldChainTest.resource(w).receivables().get("sale-ar");
            assertEquals(new BigDecimal("300.00"), invoice.amount());
            w.advanceTo(PERIOD);
            value(w, company, 1, "DELIVERED_REVENUE", invoice.amount());
            value(w, company, 1, "EXPANSION_SPENDING", capex);
            value(w, company, 1, "RECEIVABLES", invoice.amount());
            var wallet = cash(w, ComposedIndustryScenario.CASH);
            act(w, "collect", request(ResourceOperations.Action.COLLECT, "receivableId", "sale-ar"));
            assertEquals(invoice.amount(), cash(w, ComposedIndustryScenario.CASH).subtract(wallet));
            w.advanceTo(2 * PERIOD);
            for (String kind : List.of("DELIVERED_REVENUE", "EXPANSION_SPENDING", "RECEIVABLES")) absent(w, company, 2, kind);
        }
        try (var w = ComposedIndustryScenario.openCurrentTenCompanyStudy(file)) {
            value(w, company, 1, "DELIVERED_REVENUE", new BigDecimal("300.00"));
        }
    }

    @Test void digitalStagesAndDeliveryUsePaymentAndInvoiceDates() throws Exception {
        String company = DigitalIndustryConfig.COMPANY;
        try (var w = ComposedIndustryScenario.openCurrentTenCompanyStudy(dir.resolve("digital.journal"))) {
            govern(w, company, BaselineIndustryConfig.directors(company), DigitalIndustryConfig.MANAGER_PERSON);
            w.advanceTo(20 * DAY);
            var before = cash(w, DigitalIndustryConfig.CONTRACTOR_ACCOUNT);
            digital(w, "pilot", DigitalOperations.Action.BEGIN_PILOT);
            var stage1 = cash(w, DigitalIndustryConfig.CONTRACTOR_ACCOUNT).subtract(before);
            w.advanceTo(w.operationsRun().orElseThrow().digital().ledger().stageOf(DigitalServiceModel.Stage.PILOT).completesAt());
            before = cash(w, DigitalIndustryConfig.CONTRACTOR_ACCOUNT);
            digital(w, "validation", DigitalOperations.Action.BEGIN_VALIDATION);
            var stage2 = cash(w, DigitalIndustryConfig.CONTRACTOR_ACCOUNT).subtract(before);
            assertEquals(new BigDecimal("3500.00"), stage1.add(stage2));
            w.advanceTo(PERIOD);
            value(w, company, 1, "STAGE_SPENDING", stage1.add(stage2));
            absent(w, company, 1, "DELIVERED_REVENUE");
            w.advanceTo(w.operationsRun().orElseThrow().digital().ledger().stageOf(DigitalServiceModel.Stage.VALIDATION).completesAt());
            digital(w, "approval", DigitalOperations.Action.APPROVE_COMMERCIAL, "approvalId", DigitalIndustryConfig.APPROVAL_COMMERCIAL);
            digital(w, "contract", DigitalOperations.Action.SIGN_CONTRACT, "contractId", "subscription", "quantityUnits", "100", "prepaidCents", "100000");
            digital(w, "delivery", DigitalOperations.Action.DELIVER, "contractId", "subscription", "units", "40", "receivableId", "invoice");
            var ar = w.operationsRun().orElseThrow().digital().ledger().receivables().get("invoice");
            assertEquals(new BigDecimal("4000.00"), ar.revenue());
            var contract = w.operationsRun().orElseThrow().digital().ledger().contracts().get("subscription");
            assertEquals(new BigDecimal("400.00"), contract.prepaidApplied());
            assertEquals(ar.revenue().subtract(contract.prepaidApplied()), ar.outstanding());
            w.advanceTo(2 * PERIOD);
            value(w, company, 2, "DELIVERED_REVENUE", ar.revenue());
            value(w, company, 2, "RECEIVABLES", ar.outstanding());
            absent(w, company, 2, "STAGE_SPENDING");
            w.advanceTo(3 * PERIOD);
            absent(w, company, 3, "DELIVERED_REVENUE");
            value(w, company, 3, "RECEIVABLES", ar.outstanding());
        }
    }

    @Test void meteredPowerStopsMovingAfterDispatchStops() throws Exception {
        String company = HydroIndustryConfig.COMPANY;
        try (var w = ComposedIndustryScenario.openCurrentTenCompanyStudy(dir.resolve("hydro.journal"))) {
            govern(w, company, HydroIndustryConfig.DIRECTORS, HydroIndustryConfig.MANAGER);
            HydroIndustryWorldTest.act(w, "start", new HydroOperations.Request(HydroOperations.Action.DISPATCH_FLOW, Map.of("cubicMetresPerHour", "600")));
            w.advanceTo(w.snapshot().timeMillis() + HydroIndustryWorldTest.HOUR);
            HydroIndustryWorldTest.act(w, "stop", new HydroOperations.Request(HydroOperations.Action.DISPATCH_FLOW, Map.of("cubicMetresPerHour", "0")));
            var stopped = HydroIndustryWorldTest.report(w);
            assertTrue(stopped.deliveredKilowattHours().signum() > 0);
            assertTrue(stopped.receivable().compareTo(BigDecimal.TEN) > 0);
            w.advanceTo(PERIOD);
            value(w, company, 1, "DELIVERED_POWER", stopped.deliveredKilowattHours());
            value(w, company, 1, "RECEIVABLES", stopped.receivable());
            HydroIndustryWorldTest.act(w, "received", new HydroOperations.Request(HydroOperations.Action.COLLECT, Map.of("amount", "10.00")));
            assertEquals(new BigDecimal("10.00"), HydroIndustryWorldTest.report(w).collectedCash().subtract(stopped.collectedCash()));
            w.advanceTo(2 * PERIOD);
            absent(w, company, 2, "DELIVERED_POWER");
            value(w, company, 2, "RECEIVABLES", stopped.receivable().subtract(BigDecimal.TEN));
        }
    }

    @Test void terminalSalesUseTheSaleNotShipmentPeriod() throws Exception {
        String company = AgedIndustryConfig.COMPANY;
        try (var w = ComposedIndustryScenario.openCurrentTenCompanyStudy(dir.resolve("aged.journal"))) {
            govern(w, company, BaselineIndustryConfig.directors(company), AgedIndustryConfig.MANAGER_PERSON);
            aged(w, "order", AgedOperations.Action.ORDER_RAW, "orderId", "raw", "units", "50");
            w.advanceTo(PERIOD + 1);
            aged(w, "receive", AgedOperations.Action.RECEIVE_RAW, "orderId", "raw");
            aged(w, "produce", AgedOperations.Action.PRODUCE, "batchId", "batch", "units", "50");
            w.advanceTo(3 * PERIOD + 1);
            aged(w, "ship", AgedOperations.Action.SHIP, "shipmentId", "shipment", "channelId", AgedIndustryConfig.CHANNEL, "units", "50");
            assertTrue(w.operationsRun().orElseThrow().aged().ledger().sales().isEmpty());
            absent(w, company, 3, "SELL_THROUGH");
            w.advanceTo(4 * PERIOD);
            var ledger = w.operationsRun().orElseThrow().aged().ledger();
            assertEquals(50L, ledger.sales().stream().mapToLong(AgedInventoryModel.Sale::units).sum());
            value(w, company, 4, "SELL_THROUGH", BigDecimal.valueOf(50));
            BigDecimal ar = ledger.channelLedgers().stream().map(AgedInventoryModel.ChannelLedger::receivable).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertTrue(ar.signum() > 0);
            value(w, company, 4, "RECEIVABLES", ar);
            w.advanceTo(5 * PERIOD);
            absent(w, company, 5, "SELL_THROUGH");
            value(w, company, 5, "RECEIVABLES", ar);
        }
    }

    @Test void pharmaRegistrationAloneDoesNotCreateAPaidStage() throws Exception {
        String company = ComposedPharmaScenario.PHARMA, manager = ComposedPharmaScenario.MANAGER_CANDIDATE;
        try (var w = ComposedIndustryScenario.openCurrentTenCompanyStudy(dir.resolve("pharma.journal"))) {
            govern(w, company, ComposedPharmaScenario.DIRECTORS, manager);
            long at = w.snapshot().timeMillis() + 2000;
            w.scheduleResearchRegister("register", "register-t", at, company, manager, "p1", 1000);
            w.advanceTo(at);
            assertTrue(w.researchRun().orElseThrow().registeredProjectIds().contains("p1"));
            w.advanceTo(PERIOD);
            absent(w, company, 1, "STAGE_SPENDING");
            var before = cash(w, ComposedPharmaScenario.PHARMA_CASH);
            at = w.snapshot().timeMillis() + 2000;
            w.scheduleResearchBegin("begin", "begin-t", at, company, manager, "p1", ResearchModel.Stage.values()[0], 1000);
            w.advanceTo(at);
            var paid = before.subtract(cash(w, ComposedPharmaScenario.PHARMA_CASH));
            assertTrue(paid.signum() > 0);
            w.advanceTo(2 * PERIOD);
            value(w, company, 2, "STAGE_SPENDING", paid);
            w.advanceTo(3 * PERIOD);
            absent(w, company, 3, "STAGE_SPENDING");
        }
    }

    private static BigDecimal cash(WorldRuntime w, String id) {
        return w.snapshot().accounts().get(id).cash();
    }

    private static void value(WorldRuntime w, String company, int period, String kind, BigDecimal expected) throws Exception {
        String ref = "issuer-fact:" + kind + ":" + company + ":" + period;
        var sample = w.periodicOf(company).orElseThrow().snapshots().get(period - 1);
        assertTrue(sample.evidenceRefs().contains(ref), ref);
        String line = w.periodicStatementForTest(company, period).lines().filter(s -> s.startsWith(ref + " ")).findFirst().orElseThrow();
        assertEquals(0, expected.compareTo(new BigDecimal(line.substring(line.lastIndexOf('=') + 1))), line);
    }

    private static void absent(WorldRuntime w, String company, int period, String kind) throws Exception {
        String ref = "issuer-fact:" + kind + ":" + company + ":" + period;
        assertFalse(w.periodicOf(company).orElseThrow().snapshots().get(period - 1).evidenceRefs().contains(ref), ref);
        assertFalse(w.periodicStatementForTest(company, period).contains(ref + " "), ref);
    }

    private static void govern(WorldRuntime w, String company, List<String> directors, String manager) throws Exception {
        String doc = CompanyOpeningDocuments.documentId(company), chair = directors.get(0);
        for (int i=0; i<4; i++) w.scheduleStartReading("charter-" + i, "charter-t-" + i, 100 + i, directors.get(i), doc, 1);
        w.advanceTo(DAY);
        for (boolean appoint : List.of(false, true)) {
            String id = appoint ? "appoint" : "budget";
            long t = w.snapshot().timeMillis() + 10;
            w.scheduleCompanyBoardConvene(id, id+"-t", t, company, chair, id, t+50, t+100, t+3000, appoint);
            for (int i=0; i<4; i++) w.scheduleCompanyBoardAttend(id+"-a"+i, id+"-at"+i, t+10+i, company, directors.get(i), id);
            w.scheduleCompanyBoardMaterial(id+"-m", id+"-mt", t+30, company, chair, id, "material", doc, 1);
            if (appoint) w.scheduleCompanyBoardAppointmentProposal(id+"-proposal", id+"-pt", t+40, company, chair, id,
                    new BoardModel.AppointmentRequest("manager", manager, company, RoleAccess.Role.COMPANY_MANAGER,
                            BoardModel.AppointmentAction.APPOINT, t+6000, 180 * DAY));
            else {
                var lines = new ArrayList<BoardModel.BudgetLine>();
                for (long period : List.of(0L,1L,2L,3L))
                    for (var category : List.of(BoardModel.BudgetCategory.PROCUREMENT, BoardModel.BudgetCategory.PRODUCTION_LABOR))
                        lines.add(new BoardModel.BudgetLine(period, category, new BigDecimal("10000.00")));
                w.scheduleCompanyBoardBudgetProposal(id+"-proposal", id+"-pt", t+40, company, chair, id, lines);
            }
            for (int i=0; i<4; i++) w.scheduleCompanyBoardVote(id+"-v"+i, id+"-vt"+i, t+2000+i, company, directors.get(i), id, BoardModel.Vote.YES, 1000);
            w.scheduleCompanyBoardImplement(id+"-impl", id+"-it", t+4000, company, chair, id);
            w.advanceTo(t+6000);
        }
        assertTrue(w.isActiveManager(company, manager, w.snapshot().timeMillis()));
    }
}
