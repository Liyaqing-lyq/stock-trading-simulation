package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DigitalServiceModel.Report;
import edu.cufe.auction.world.DigitalServiceModel.Stage;
import edu.cufe.auction.world.bank.FundState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static edu.cufe.auction.world.PureCompanyTestSupport.money;
import static edu.cufe.auction.world.PureCompanyTestSupport.payer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数字服务适配层的实质断言：严格字段与身份夹带拒绝、冻结证据与治理决议接点、
 * 真实现金链逐步对账、报表平衡与经理投影的私源隔离。
 */
class DigitalIndustryAdapterTest {

    private static final long DAY = DigitalServiceModel.DAY_MILLIS;
    private static final long PERIOD = DigitalServiceModel.PERIOD_MILLIS;
    private static final String CO = DigitalIndustryConfig.COMPANY_ACCOUNT;
    private static final String OPS = DigitalIndustryConfig.OPS_ACCOUNT;
    private static final String CUS = DigitalIndustryConfig.CUSTOMER_ACCOUNT;
    private static final String LAB = DigitalIndustryConfig.CONTRACTOR_ACCOUNT;
    private static final String SPON = DigitalIndustryConfig.SPONSOR_ACCOUNT;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一条命令在真实现金上的执行器：接受才提交候选。 */
    private static final class World {

        private DigitalCompanyRun run;
        private FundState cash;
        private final PureCompanyTestSupport.Payer coToLab =
                payer(CO, LAB);
        private final PureCompanyTestSupport.Payer coToOps =
                payer(CO, OPS);
        private final PureCompanyTestSupport.Payer customerToCo =
                payer(CUS, CO);
        private final PureCompanyTestSupport.Payer coToCustomer =
                payer(CO, CUS);
        private final PureCompanyTestSupport.Payer sponsorToCo =
                payer(SPON, CO);

        private World() {
            this.cash = PureCompanyTestSupport.funds(DigitalIndustryConfig.accounts());
            this.run = DigitalCompanyRun.open(DigitalIndustryConfig.runConfig(), cash);
        }

        private DigitalOperations.Outcome apply(DigitalOperations.Action action,
                                                Map<String, String> arguments,
                                                FundPayer fundsLeg, long now, String commandId) {
            DigitalOperations.Outcome outcome = DigitalOperations.apply(run, cash, fundsLeg, now,
                    commandId, new DigitalOperations.Request(action, arguments));
            if (outcome.accepted()) {
                run = run.withLedger(outcome.state());
                cash = outcome.funds();
            }
            return outcome;
        }

        private BigDecimal at(String accountId) {
            return cash.cash(accountId);
        }

        /** 跑到 VALIDATION 通过且已登记商业化批准。 */
        private void openForContracts() {
            assertTrue(apply(DigitalOperations.Action.BEGIN_PILOT, Map.of(), coToLab, 0L,
                    "cmd-pilot-begin").accepted());
            assertTrue(apply(DigitalOperations.Action.COMPLETE_PILOT,
                    Map.of("evidenceId", DigitalIndustryConfig.EVIDENCE_PILOT), null, 5L * DAY,
                    "cmd-pilot-done").accepted());
            assertTrue(apply(DigitalOperations.Action.BEGIN_VALIDATION, Map.of(), coToLab,
                    5L * DAY, "cmd-validation-begin").accepted());
            assertTrue(apply(DigitalOperations.Action.COMPLETE_VALIDATION,
                    Map.of("evidenceId", DigitalIndustryConfig.EVIDENCE_VALIDATION), null,
                    15L * DAY, "cmd-validation-done").accepted());
            assertTrue(apply(DigitalOperations.Action.APPROVE_COMMERCIAL,
                    Map.of("approvalId", DigitalIndustryConfig.APPROVAL_COMMERCIAL), null,
                    15L * DAY, "cmd-approve").accepted());
        }
    }

    @Test
    void requestRejectsIdentitySmugglingExtraFieldsAndFloatMoney() {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("contractId", "CT-1");
        fields.put("quantityUnits", "100");
        fields.put("prepaidCents", "4000");
        assertThrows(IllegalArgumentException.class, () -> new DigitalOperations.Request(
                DigitalOperations.Action.SIGN_CONTRACT, withExtra(fields, "actor", "another-company")));
        assertThrows(IllegalArgumentException.class, () -> new DigitalOperations.Request(
                DigitalOperations.Action.SIGN_CONTRACT, withExtra(fields, "companyOwnerId", "x")));
        for (String bad : new String[]{"1.5", "1e3", "-1", " 4000", "9223372036854775808", "04000"}) {
            Map<String, String> malformed = new java.util.LinkedHashMap<>(fields);
            malformed.put("prepaidCents", bad);
            assertThrows(IllegalArgumentException.class, () -> new DigitalOperations.Request(
                    DigitalOperations.Action.SIGN_CONTRACT, malformed), bad);
        }
        Map<String, String> missing = new java.util.LinkedHashMap<>(fields);
        missing.remove("quantityUnits");
        assertThrows(IllegalArgumentException.class, () -> new DigitalOperations.Request(
                DigitalOperations.Action.SIGN_CONTRACT, missing));
        assertThrows(UnsupportedOperationException.class,
                () -> new DigitalOperations.Request(DigitalOperations.Action.TERMINATE,
                        Map.of("contractId", "CT-1")).arguments().put("actor", "x"));
    }

    @Test
    void strictDraftParsingKeepsCentsAndRefusesSystemOrGovernanceTools() throws Exception {
        DigitalOperations.Request request = DigitalDecisionTools.request(
                new DigitalDecisionTools.Draft("SIGN_CONTRACT",
                        json("{\"contractId\":\"CT-1\",\"quantityUnits\":\"100\",\"prepaidCents\":\"4000\"}")));
        assertEquals(DigitalOperations.Action.SIGN_CONTRACT, request.action());
        assertEquals(new BigDecimal("40.00"), request.cents("prepaidCents"));
        assertEquals(100L, request.integer("quantityUnits"));

        assertThrows(IllegalArgumentException.class, () -> DigitalDecisionTools.request(
                new DigitalDecisionTools.Draft("COMPLETE_PILOT", json("{\"evidenceId\":\"e\"}"))));
        assertThrows(IllegalArgumentException.class, () -> DigitalDecisionTools.request(
                new DigitalDecisionTools.Draft("CLOSE_PERIOD", json("{\"period\":\"0\"}"))));
        assertThrows(IllegalArgumentException.class, () -> DigitalDecisionTools.request(
                new DigitalDecisionTools.Draft("INJECT_CAPITAL",
                        json("{\"amountCents\":\"100\",\"mandateId\":\"m\"}"))));
        assertThrows(IllegalArgumentException.class, () -> DigitalDecisionTools.request(
                new DigitalDecisionTools.Draft("SIGN_CONTRACT",
                        json("{\"contractId\":\"CT-1\",\"quantityUnits\":100,\"prepaidCents\":\"4000\"}"))));
        assertThrows(IllegalArgumentException.class, () -> DigitalDecisionTools.request(
                new DigitalDecisionTools.Draft("NOT_AN_ACTION", json("{}"))));
        assertThrows(IllegalArgumentException.class, () -> DigitalDecisionTools.request(
                new DigitalDecisionTools.Draft("BEGIN_PILOT", json("[]"))));
    }

    @Test
    void fullChainMovesRealCashAndKeepsTheBooksBalanced() {
        World world = new World();
        assertEquals(money("82000.00"), world.cash.cash(CO).add(world.at(OPS))
                .add(world.at(CUS)).add(world.at(LAB)).add(world.at(SPON)));

        assertTrue(world.apply(DigitalOperations.Action.BEGIN_PILOT, Map.of(), world.coToLab,
                0L, "cmd-pilot-begin").accepted());
        assertEquals(money("19000.00"), world.at(CO));
        assertEquals(money("1000.00"), world.at(LAB));
        assertEquals(money("1000.00"), world.run.ledger().pilotExpense());
        assertEquals(5L * DAY, DigitalOperations.dueAt(world.run.ledger(),
                DigitalOperations.Action.COMPLETE_PILOT));

        assertTrue(world.apply(DigitalOperations.Action.COMPLETE_PILOT,
                Map.of("evidenceId", DigitalIndustryConfig.EVIDENCE_PILOT), null, 5L * DAY,
                "cmd-pilot-done").accepted());
        assertEquals(DigitalServiceModel.StageStatus.PASSED,
                world.run.ledger().stageOf(Stage.PILOT).status());

        assertTrue(world.apply(DigitalOperations.Action.BEGIN_VALIDATION, Map.of(),
                world.coToLab, 5L * DAY, "cmd-validation-begin").accepted());
        assertEquals(money("16500.00"), world.at(CO));
        assertEquals(money("3500.00"), world.at(LAB));
        assertEquals(money("2500.00"), world.run.ledger().workInProgress());

        assertTrue(world.apply(DigitalOperations.Action.COMPLETE_VALIDATION,
                Map.of("evidenceId", DigitalIndustryConfig.EVIDENCE_VALIDATION), null, 15L * DAY,
                "cmd-validation-done").accepted());
        assertEquals(money("2500.00"), world.run.ledger().platformNet());
        assertEquals(money("0.00"), world.run.ledger().workInProgress());

        assertTrue(world.apply(DigitalOperations.Action.APPROVE_COMMERCIAL,
                Map.of("approvalId", DigitalIndustryConfig.APPROVAL_COMMERCIAL), null, 15L * DAY,
                "cmd-approve").accepted());

        assertTrue(world.apply(DigitalOperations.Action.SIGN_CONTRACT,
                Map.of("contractId", "CT-1", "quantityUnits", "100", "prepaidCents", "400000"),
                world.customerToCo, 15L * DAY, "cmd-sign").accepted());
        assertEquals(money("20500.00"), world.at(CO));
        assertEquals(money("8000.00"), world.at(CUS));
        assertEquals(money("4000.00"), world.run.ledger().contracts().get("CT-1").unearned());

        assertTrue(world.apply(DigitalOperations.Action.DELIVER,
                Map.of("contractId", "CT-1", "units", "60", "receivableId", "AR-1"),
                world.coToOps, 16L * DAY, "cmd-deliver").accepted());
        assertEquals(money("19300.00"), world.at(CO));
        assertEquals(money("1200.00"), world.at(OPS));
        assertEquals(money("3600.00"),
                world.run.ledger().receivables().get("AR-1").outstanding());
        assertEquals(money("1600.00"), world.run.ledger().refundObligation());

        assertTrue(world.apply(DigitalOperations.Action.CLOSE_PERIOD, Map.of("period", "0"),
                world.coToOps, 30L * DAY, "cmd-close-0").accepted());
        assertEquals(money("19000.00"), world.at(CO));
        assertEquals(money("1500.00"), world.at(OPS));
        assertEquals(money("250.00"), world.run.ledger().amortized());

        assertTrue(world.apply(DigitalOperations.Action.COLLECT, Map.of("receivableId", "AR-1"),
                world.customerToCo, 31L * DAY, "cmd-collect").accepted());
        assertEquals(money("22600.00"), world.at(CO));
        assertEquals(money("4400.00"), world.at(CUS));

        assertTrue(world.apply(DigitalOperations.Action.CLOSE_PERIOD, Map.of("period", "1"),
                world.coToOps, 61L * DAY, "cmd-close-1").accepted());
        assertEquals(money("22300.00"), world.at(CO));
        assertEquals(money("1800.00"), world.at(OPS));

        Report report = DigitalServiceModel.validate(world.run.ledger(), world.cash);
        assertTrue(report.balanced());
        assertEquals(money("24300.00"), report.assets());
        assertEquals(money("1600.00"), report.liabilities());
        assertEquals(money("22700.00"), report.equity());
        assertEquals(money("6000.00"), report.revenue());
        assertEquals(money("1200.00"), report.serviceCogs());
        assertEquals(money("82000.00"), report.worldCash());
        assertEquals(money("82000.00"), world.at(CO).add(world.at(OPS)).add(world.at(CUS))
                .add(world.at(LAB)).add(world.at(SPON)));
    }

    @Test
    void customerCashShortRejectsTheWholeContract() {
        World world = new World();
        world.openForContracts();
        BigDecimal companyBefore = world.at(CO);
        BigDecimal customerBefore = world.at(CUS);
        DigitalOperations.Outcome outcome = world.apply(DigitalOperations.Action.SIGN_CONTRACT,
                Map.of("contractId", "CT-2", "quantityUnits", "300", "prepaidCents", "1300000"),
                world.customerToCo, 15L * DAY, "cmd-sign-short");
        assertFalse(outcome.accepted());
        assertEquals(DigitalServiceModel.CUSTOMER_CASH_SHORT, outcome.reason());
        assertTrue(world.run.ledger().contracts().isEmpty());
        assertEquals(companyBefore, world.at(CO));
        assertEquals(customerBefore, world.at(CUS));
    }

    @Test
    void capitalInjectionNeedsARealGovernanceMandate() {
        World world = new World();
        BigDecimal companyBefore = world.at(CO);
        BigDecimal sponsorBefore = world.at(SPON);

        DigitalOperations.Outcome denied = world.apply(DigitalOperations.Action.INJECT_CAPITAL,
                Map.of("amountCents", "500000", "mandateId", "GV-NOT-DECIDED"),
                world.sponsorToCo, 0L, "cmd-capital-denied");
        assertFalse(denied.accepted());
        assertEquals(DigitalOperations.NO_MANDATE, denied.reason());
        assertEquals(companyBefore, world.at(CO));
        assertNull(world.run.mandateOf("GV-NOT-DECIDED"));

        DigitalOperations.Outcome accepted = world.apply(DigitalOperations.Action.INJECT_CAPITAL,
                Map.of("amountCents", "500000", "mandateId", DigitalIndustryConfig.MANDATE_CAPITAL),
                world.sponsorToCo, 0L, "cmd-capital-ok");
        assertTrue(accepted.accepted());
        assertEquals(money("25000.00"), world.at(CO));
        assertEquals(money("45000.00"), world.at(SPON));
        assertEquals(money("5000.00"), world.run.ledger().capitalContributions());
        assertEquals(sponsorBefore.subtract(money("5000.00")), world.at(SPON));
    }

    @Test
    void observationHidesCustomerBudgetAndFrozenStageResults() throws Exception {
        World world = new World();
        JsonNode view = JSON.readTree(DigitalDecisionTools.observation(world.run, 0L,
                world.at(CO), List.of(), null).toString());
        assertFalse(view.has("customerBudgetPerPeriod"));
        assertFalse(view.has("pilotPasses"));
        assertFalse(view.has("validationPasses"));
        assertFalse(view.has("approvals"));
        assertFalse(view.toString().contains("digital-customer-cash"));
        assertFalse(view.toString().contains("digital-lab-cash"));
        assertEquals("100.0000", view.get("servicePricePerUnit").asText());
        assertEquals("20.00", view.get("serviceUnitCost").asText());
        assertEquals(0, view.get("closedPeriods").asLong());
        assertTrue(view.get("actionFields").has("SIGN_CONTRACT"));
        assertFalse(view.get("actionFields").has("COMPLETE_PILOT"));
        assertFalse(view.get("actionFields").has("CLOSE_PERIOD"));
        assertFalse(view.get("actionFields").has("INJECT_CAPITAL"));
        assertNotNull(view.get("rules"));
        assertTrue(view.get("rules").asText().contains("整数分"));
    }

    private static Map<String, String> withExtra(Map<String, String> base, String key, String value) {
        Map<String, String> copy = new java.util.LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }
}
