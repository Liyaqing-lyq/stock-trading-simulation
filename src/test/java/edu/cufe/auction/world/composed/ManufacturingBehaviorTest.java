package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManufacturingBehaviorTest {
    @TempDir Path dir;
    static final long DAY = ManufacturingTemplateModel.DAY_MILLIS;
    static final String COMPANY = ComposedManufacturingThreeScenario.TECH;
    static final String MANAGER = ComposedManufacturingThreeScenario.TECH_MANAGER;

    static void appoint(WorldRuntime w) throws Exception {
        OperationsFixture.grantBudgetAndAppoint(w, COMPANY, ComposedManufacturingThreeScenario.TECH_DIRECTORS,
                MANAGER, "behavior-setup", List.of(0L, 1L, 2L));
    }
    static ObjectNode view(WorldRuntime w) {
        return (ObjectNode) w.engineeringRoleInput(MANAGER).path("companyOperations").get(0);
    }

    @Test void demandCashAndNegativeMarginChangeTheChoiceWithoutChangingTheCalendar() throws Exception {
        try (var w = ComposedIndustryScenario.openBehavior(dir.resolve("policy.journal"))) {
            appoint(w); var v = view(w); long now = w.snapshot().timeMillis();
            var base = ManufacturingBehaviorPolicy.choose(v, now, "test");
            assertEquals("SIGN_CONTRACT", base.action());
            var lower = v.deepCopy(); ((ObjectNode) lower.path("customerOffers").get(0)).put("quantity", 2);
            assertEquals(2L, ManufacturingBehaviorPolicy.choose(lower, now, "test").fields().get("quantity"));
            var noDemand = v.deepCopy(); ((ObjectNode) noDemand.path("customerOffers").get(0)).put("quantity", 0);
            assertEquals("WAIT", ManufacturingBehaviorPolicy.choose(noDemand, now, "test").action());
            var noCash = v.deepCopy(); noCash.put("cash", "49.00");
            assertEquals("WAIT", ManufacturingBehaviorPolicy.choose(noCash, now, "test").action());
            var loss = v.deepCopy(); ((ObjectNode) loss.path("customerOffers").get(0)).put("unitPrice", "1.00");
            assertEquals("WAIT", ManufacturingBehaviorPolicy.choose(loss, now, "test").action());
            assertFalse(v.toString().contains("EXT-CUST-AUTO"), "经理只见报价，不见客户私账");
        }
    }

    @Test void webRunnerCompletesOrderProductionDeliveryAndActualCollectionAfterRestart() throws Exception {
        Path path = dir.resolve("cycle.journal");
        BigDecimal initialCash;
        try (var w = ComposedIndustryScenario.openBehavior(path)) {
            appoint(w); initialCash = total(w);
            ManufacturingBehaviorLoop.advanceTo(w, 2 * DAY);
            var ledger = w.operationsRun().orElseThrow().company(COMPANY).ledger();
            assertEquals(1, ledger.contracts().size());
            assertEquals(1, ledger.purchases().size());
            assertEquals(1, ledger.batches().size());
            assertTrue(ledger.receivables().isEmpty());
        }
        try (var w = ComposedIndustryScenario.openBehavior(path)) {
            ManufacturingBehaviorLoop.advanceTo(w, 23 * DAY);
            var ledger = w.operationsRun().orElseThrow().company(COMPANY).ledger();
            assertEquals(1, ledger.contracts().size(), "完成本期产能后不能机械继续接单");
            assertEquals(1, ledger.purchases().size(), "停机恢复不得重复采购");
            assertEquals(1, ledger.batches().size());
            assertEquals(1, ledger.receivables().size());
            assertEquals(ManufacturingTemplateModel.ReceivableStatus.COLLECTED, ledger.receivables().values().iterator().next().status());
            assertEquals(initialCash, total(w));
            assertTrue(ComposedCompanyReport.of(w, COMPANY).balanced());
            assertTrue(ManufacturingBehaviorLoop.audit(w).stream().anyMatch(r -> r.get("action").equals("WAIT")));
            assertEquals(0, w.snapshot().exchange().orElseThrow().trades().size(), "经营修复不能暗造成交");
        }
        try (var w = ComposedIndustryScenario.openBehavior(path)) {
            assertEquals(1, w.operationsRun().orElseThrow().company(COMPANY).ledger().receivables().size());
        }
    }

    @Test void missingAppointmentDoesNotGrantAuthorityAndOldProfileStaysUnchanged() throws Exception {
        try (var w = ComposedIndustryScenario.openBehavior(dir.resolve("unappointed.journal"))) {
            ManufacturingBehaviorLoop.advanceTo(w, DAY);
            assertTrue(w.operationsRun().orElseThrow().company(COMPANY).ledger().contracts().isEmpty());
        }
        try (var w = ComposedIndustryScenario.openPrototype(dir.resolve("old.journal"))) {
            appoint(w); assertFalse(ManufacturingBehaviorLoop.enabled(w));
            assertFalse(view(w).has("customerOffers"));
            assertThrows(IllegalArgumentException.class, () -> ManufacturingBehaviorLoop.advanceTo(w, DAY));
        }
    }

    @Test void customerSpendingElsewhereWithdrawsUnfundedDemandThroughRealWorldCash() throws Exception {
        try (var w = ComposedIndustryScenario.openBehavior(dir.resolve("customer-shock.journal"))) {
            appoint(w);
            assertEquals("SIGN_CONTRACT", ManufacturingBehaviorPolicy.choose(view(w), w.snapshot().timeMillis(), "before").action());
            var cash = w.snapshot().accounts().get(ComposedManufacturingThreeScenario.CUST_AUTO);
            BigDecimal initial = total(w);
            long due = w.snapshot().timeMillis() + 10;
            w.scheduleTransfer("customer-other-purchase", "customer-other-purchase", due, cash.ownerId(),
                    ComposedManufacturingThreeScenario.CUST_AUTO, ComposedManufacturingThreeScenario.SUP_METAL, cash.cash(), 1);
            w.advanceTo(due + 1);
            assertEquals(0, view(w).path("customerOffers").get(0).path("quantity").asLong());
            ManufacturingBehaviorLoop.advanceTo(w, 2 * DAY);
            var ledger = w.operationsRun().orElseThrow().company(COMPANY).ledger();
            assertTrue(ledger.contracts().isEmpty());
            assertTrue(ledger.purchases().isEmpty());
            assertTrue(ledger.batches().isEmpty());
            assertEquals(initial, total(w), "客户资金确实转给其他主体，而非删除或注入");
        }
    }

    @Test void delayedReceivableIsNotTreatedAsAvailableCash() throws Exception {
        try (var w = ComposedIndustryScenario.openBehavior(dir.resolve("delay.journal"))) {
            appoint(w); var v = view(w); long now = w.snapshot().timeMillis();
            v.put("cash", "49.00");
            ObjectNode ar = v.withObject("receivables").putObject("ar");
            ar.put("receivableId", "ar").put("status", "OPEN").put("outstanding", "3200.00").put("dueAt", now + DAY);
            assertEquals("WAIT", ManufacturingBehaviorPolicy.choose(v, now, "delay").action());
            ar.put("dueAt", now);
            assertEquals("COLLECT", ManufacturingBehaviorPolicy.choose(v, now, "due").action());
        }
    }

    @Test void storedDecisionSurvivesInterruptionBeforeBusinessActionAndCannotBeForged() throws Exception {
        Path path = dir.resolve("intent.journal");
        try (var w = ComposedIndustryScenario.openBehavior(path)) {
            appoint(w); long now = w.snapshot().timeMillis();
            String id = "behavior-v1:" + COMPANY + ":" + now / ManufacturingBehaviorLoop.INTERVAL;
            JsonNode own = view(w);
            w.recordManufacturingDecision(new ManufacturingBehaviorLoop.Decision(ManufacturingBehaviorPolicy.VERSION,
                    id, MANAGER, COMPANY, now, own, ManufacturingBehaviorPolicy.choose(own, now, id)));
            assertThrows(IllegalArgumentException.class, () -> w.schedulePublishDocument("fake", "fake", now + 1,
                    id, MANAGER, InformationModel.Audience.direct(MANAGER), "fake", "fake", "fake", 1, List.of()));
        }
        try (var w = ComposedIndustryScenario.openBehavior(path)) {
            ManufacturingBehaviorLoop.advanceTo(w, 2 * DAY);
            assertEquals(1, w.operationsRun().orElseThrow().company(COMPANY).ledger().contracts().size());
            assertEquals(1, w.operationsRun().orElseThrow().company(COMPANY).ledger().purchases().size());
        }
    }

    @Test void tinyTimeStepsDoNotSkipTheWakeOrRepeatItsBusinessAction() throws Exception {
        Path path = dir.resolve("small-step.journal");
        try (var w = ComposedIndustryScenario.openBehavior(path)) {
            appoint(w); long now = w.snapshot().timeMillis();
            ManufacturingBehaviorLoop.advanceTo(w, now + 1);
            assertEquals(now + 1, w.snapshot().timeMillis(), "不能越过调用方请求的时点");
            assertEquals(1, ManufacturingBehaviorLoop.audit(w).size());
            ManufacturingBehaviorLoop.advanceTo(w, now + 2);
            assertEquals(1, ManufacturingBehaviorLoop.audit(w).size());
        }
        try (var w = ComposedIndustryScenario.openBehavior(path)) {
            ManufacturingBehaviorLoop.advanceTo(w, 2 * DAY);
            assertEquals(1, w.operationsRun().orElseThrow().company(COMPANY).ledger().contracts().size());
            assertEquals(1, w.operationsRun().orElseThrow().company(COMPANY).ledger().purchases().size());
        }
    }

    static BigDecimal total(WorldRuntime w) {
        return w.snapshot().accounts().values().stream().map(CashLedger.CashAccount::cash).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    @Test void localWebAdvanceActuallyRunsBehaviorAndRejectsStaleRepeat() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path journal = dir.resolve("http.journal"), keys = dir.resolve("keys.txt");
        java.nio.file.Files.writeString(keys, String.join("\n", java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> "sk-test-behavior-000" + i).toList()));
        try (var host = new edu.cufe.auction.web.WorldWebHost(() -> {
            var w = ComposedIndustryScenario.openBehavior(journal); appoint(w);
            return new edu.cufe.auction.web.WorldWebHost.Opened(w, w.profile().manifest().runId());
        }); var routes = edu.cufe.auction.web.SceneRoutes.open(dir);
             var server = new edu.cufe.auction.web.WorldWebServer(host, edu.cufe.auction.web.KeySlots.load(keys), routes, 0, "test-bootstrap", null, s -> { })) {
            server.enableLocalAccess(); server.start();
            var http = java.net.http.HttpClient.newHttpClient();
            var local = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(server.baseUrl() + "/api/session/local"))
                    .header("Origin", server.baseUrl()).header("X-World-Request", "1").header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, local.statusCode());
            String cookie = local.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            JsonNode before = json.valueToTree(host.worldOverview());
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(server.baseUrl() + "/api/admin/advance"))
                    .header("Origin", server.baseUrl()).header("X-World-Request", "1").header("Cookie", cookie).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of(
                            "expectedPosition", before.path("position").asText(), "targetMillis", Long.toString(2 * DAY))))).build();
            assertEquals(200, http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            JsonNode after = json.valueToTree(host.worldOverview());
            assertTrue(after.path("behavior").path("enabled").asBoolean());
            assertTrue(after.path("behavior").path("decisions").size() > 0);
            assertEquals(409, http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(after.path("position"), json.valueToTree(host.worldOverview()).path("position"));
        }
    }
}
