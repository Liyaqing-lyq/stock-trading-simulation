package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.*;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonBusinessRelationsHttpTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private String base, cookie;

    @Test void managerReadDocumentAndBookedPaymentStaySeparateAcrossTwoPeopleAndReopen() throws Exception {
        Path file = dir.resolve("world.journal"), keys = dir.resolve("dummy-keys.txt");
        Files.writeString(keys, String.join("\n", java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> "sk-unusable-fixture-key-" + i).toList()));
        long paidAt;
        String history;
        try (var w = ComposedIndustryScenario.openPersonalProfilesOperating(file)) {
            OperationsFixture.grantBudgetAndAppoint(w, TECH, TECH_DIRECTORS, TECH_MANAGER, "term", List.of(0L));
            long now = w.snapshot().timeMillis();
            String doc = RoleEvidenceDocuments.documentId(TECH);
            w.scheduleStartReading("read-terms", "read-terms-task", now + 1, TECH_DIRECTORS.get(0), doc, 1);
            w.advanceTo(now + 10000);
            long due = w.snapshot().timeMillis() + 2000;
            w.openRoleSession(TECH_MANAGER).operationsAction("po", "po-task", due, TECH, CompanyOperationsRun.Action.ORDER_MATERIAL,
                    OperationsFixture.op().material("MAT-A", 2).build(), 1000);
            w.advanceTo(due);
            paidAt = w.operationsRun().orElseThrow().company(TECH).ledger().purchases().get("po:po").arrivalAt();
            assertTrue(w.operationsRun().orElseThrow().reports().values().stream().noneMatch(r -> "RECEIVE_MATERIAL".equals(r.action())));
            var call = PharmaDecisionToolTest.open(w, TECH_MANAGER, "asset-cut");
            var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                    call.recoveryGeneration(), JSON.writeValueAsString(java.util.Map.of("tool", DecisionModel.TOOL_WAIT)),
                    DecisionModel.Usage.unknownCost(1, 10, 3), "fixture");
            w.offerDecisionCallback(reply);
            w.drainDecisionCallbacks();
            history = w.snapshot().decisions().orElseThrow().state().decisions().stream()
                    .filter(r -> r.actionId().equals(call.actionId())).findFirst().orElseThrow().inputDigest();
        }
        String expected = null;
        for (int pass = 0; pass < 2; pass++) {
            try (var host = new WorldWebHost(() -> {
                var w = ComposedIndustryScenario.openPersonalProfilesOperating(file);
                return new WorldWebHost.Opened(w, w.profile().manifest().runId());
            }); var server = new WorldWebServer(host, KeySlots.load(keys), SceneRoutes.open(dir), 0, "fixture", null, line -> { })) {
                server.enableLocalAccess();
                server.start(); base = server.baseUrl(); cookie = null;
                person(TECH_MANAGER);
                if (pass == 0) {
                    var waiting = get("/api/me/view").path("businessRelations");
                    assertEquals(1, waiting.size(), waiting.toString());
                    assertEquals("下达采购单", waiting.get(0).path("relation").asText());
                    login();
                    var opening = get("/api/admin/operations");
                    send("/api/admin/advance", "{\"expectedPosition\":\"" + opening.path("position").asText() + "\",\"targetMillis\":\"" + paidAt + "\"}", 200);
                    person(TECH_MANAGER);
                }
                var rows = get("/api/me/view").path("businessRelations");
                if (expected == null) expected = rows.toString(); else assertEquals(expected, rows.toString());
                var payment = find(rows, "支付采购款");
                var order = find(rows, "下达采购单");
                assertEquals("资金", payment.path("layer").asText());
                assertEquals(TECH, payment.path("from").asText());
                assertEquals(order.path("to"), payment.path("to"));
                assertEquals("2", order.path("quantity").asText());
                assertEquals("24.00", payment.path("quantity").asText());
                assertEquals("元", payment.path("unit").asText());
                assertEquals(paidAt, payment.path("timeMillis").asLong());
                assertTrue(payment.path("basis").asText().contains("po:po"));
                var historical = get("/api/me/portfolio-history?digest=" + history);
                assertTrue(historical.has("accounts")); assertFalse(historical.has("businessRelations"));
                person(TECH_DIRECTORS.get(0));
                var director = get("/api/me/view").path("businessRelations");
                assertTrue(director.isArray(), director.toString());
                var quote = find(director, "材料报价");
                assertEquals(TECH, quote.path("to").asText());
                assertTrue(quote.path("basis").asText().contains(RoleEvidenceDocuments.documentId(TECH)));
                assertFalse(director.toString().contains("po:po"));
                person(WorldWebScenario.INVESTOR);
                assertFalse(get("/api/me/view").has("businessRelations"));
            }
        }
        try (var w = ComposedIndustryScenario.openPersonalProfilesOperating(file)) {
            long at = w.snapshot().timeMillis() + 1;
            w.scheduleRevokeAppointment("end-term", "end-term-task", at, "term-mgr");
            w.advanceTo(at);
            assertFalse(w.isActiveManager(TECH, TECH_MANAGER, at));
        }
        try (var host = new WorldWebHost(() -> {
            var w = ComposedIndustryScenario.openPersonalProfilesOperating(file);
            return new WorldWebHost.Opened(w, w.profile().manifest().runId());
        })) {
            assertTrue(host.personPage(TECH_MANAGER).businessRelations().isEmpty());
        }
    }

    private static JsonNode find(JsonNode rows, String label) {
        return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> label.equals(row.path("relation").asText())).findFirst().orElseThrow(() -> new AssertionError(rows.toString()));
    }
    private void login() throws Exception { send("/api/session/local", "{}", 200); }
    private void person(String id) throws Exception {
        login();
        send("/api/session/person", "{\"personId\":\"" + id + "\"}", 200);
    }
    private JsonNode get(String path) throws Exception { return send(path, null, 200); }
    private JsonNode send(String path, String body, int code) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path));
        if (cookie != null) req.header("Cookie", cookie);
        if (body != null) req.header("Origin", base).header("X-World-Request", "1").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(code, response.statusCode(), response.body());
        response.headers().firstValue("set-cookie").ifPresent(c -> cookie = c.split(";", 2)[0]);
        return JSON.readTree(response.body());
    }
}
