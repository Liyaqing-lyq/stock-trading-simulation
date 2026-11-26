package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.KeySlots;
import edu.cufe.auction.web.SceneRoutes;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.web.WorldWebServer;
import edu.cufe.auction.world.composed.ComposedResearchScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP验证资源经营动作、岗位隔离、精确数字、完成与恢复。 */
class ResourceOperationsWebHttpTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private String base;
    private String cookie;
    private final List<String> console = new ArrayList<>();

    @Test
    void managerRunsExtractionWithPrivateTruthAndStrictInputBoundary() throws Exception {
        Path journal = dir.resolve("world.journal");
        try (WorldRuntime world = ComposedIndustryScenario.open(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, "resources",
                    ComposedIndustryScenario.DIRECTORS,
                    ComposedIndustryScenario.MANAGER, "web-govern", List.of(0L));
        }
        Path keys = dir.resolve("dummy-keys.txt");
        Files.writeString(keys, String.join("\n", List.of("sk-research-test-000001", "sk-research-test-000002",
                "sk-research-test-000003", "sk-research-test-000004", "sk-research-test-000005")));
        try (WorldWebHost host = new WorldWebHost(() -> {
            var world = ComposedIndustryScenario.open(journal);
            return new WorldWebHost.Opened(world, world.profile().manifest().runId());
        }); WorldWebServer server = new WorldWebServer(host, KeySlots.load(keys), SceneRoutes.open(dir),
                0, "research-bootstrap", null, console::add)) {
            server.start(); base = server.baseUrl();
            assertEquals(401, request("GET", "/api/admin/operations", null).statusCode());
            admin();
            host.operationsView(null);
            var god = request("GET", "/api/admin/operations", null);
            assertEquals(200, god.statusCode(), god.body());
            assertEquals(4, JSON.readTree(god.body()).path("companies").size());
            assertTrue(god.body().contains("scenarioTruth"));
            person("frank");
            assertEquals(403, request("GET", "/api/admin/operations", null).statusCode());
            assertEquals(0, JSON.readTree(request("GET", "/api/me/operations", null).body()).path("companies").size());
            admin(); person(ComposedIndustryScenario.MANAGER);
            var before = JSON.readTree(request("GET", "/api/me/operations", null).body());
            assertEquals(1, before.path("companies").size());
            assertFalse(before.toString().contains("scenarioTruth"));
            assertFalse(before.toString().contains("\"plans\""));
            assertFalse(before.toString().contains("mfg-versa"));
            assertTrue(before.path("companies").get(0).path("financial").path("assets").path("经营现金").isTextual());
            long due = Long.parseLong(before.path("timeMillis").asText()) + 3000L;
            Map<String, Object> command = new java.util.LinkedHashMap<>(Map.of("expectedPosition", before.path("position").asText(),
                    "commandId", "web-po", "taskId", "web-po-task", "dueTimeMillis", Long.toString(due),
                    "durationMillis", "1000", "companyId", "resources", "action", "BEGIN_EXTRACTION",
                    "fields", Map.of("jobId", "web-mine", "seamId", "resource-seam-a", "quantityKg", "100")));
            command.put("fields", Map.of("jobId", "web-mine", "seamId", "resource-seam-a", "quantityKg", 1.5));
            assertEquals(400, request("POST", "/api/me/resources/action", JSON.writeValueAsString(command)).statusCode());
            command.put("fields", Map.of("jobId", "web-mine", "seamId", "resource-seam-a", "quantityKg", "100", "personPartyId", "other"));
            assertEquals(400, request("POST", "/api/me/resources/action", JSON.writeValueAsString(command)).statusCode());
            command.put("fields", Map.of("jobId", "web-mine", "seamId", "resource-seam-a", "quantityKg", "100"));
            command.put("companyId", "mfg-versa");
            assertEquals(400, request("POST", "/api/me/resources/action", JSON.writeValueAsString(command)).statusCode());
            command.put("companyId", "resources");
            var scheduled = request("POST", "/api/me/resources/action", JSON.writeValueAsString(command));
            assertEquals(200, scheduled.statusCode(), scheduled.body());
            assertEquals(409, request("POST", "/api/me/resources/action", JSON.writeValueAsString(command)).statusCode());
            admin();
            var advanced = request("POST", "/api/admin/advance", JSON.writeValueAsString(Map.of(
                    "expectedPosition", JSON.readTree(scheduled.body()).path("position").asText(),
                    "targetMillis", Long.toString(due))));
            assertEquals(200, advanced.statusCode(), advanced.body());
            var ordered = JSON.readTree(request("GET", "/api/admin/operations", null).body());
            var tech = java.util.stream.StreamSupport.stream(ordered.path("companies").spliterator(), false)
                    .filter(c -> c.path("companyId").asText().equals("resources")).findFirst().orElseThrow();
            var purchase = tech.path("jobs").elements().next();
            assertEquals("RUNNING", purchase.path("status").asText());
            var arrived = request("POST", "/api/admin/advance", JSON.writeValueAsString(Map.of(
                    "expectedPosition", ordered.path("position").asText(), "targetMillis", purchase.path("dueAt").asText())));
            assertEquals(200, arrived.statusCode(), arrived.body());
        }
        try (WorldRuntime world = ComposedIndustryScenario.open(journal)) {
            assertEquals(new java.math.BigDecimal("9900.00"), world.snapshot().accounts()
                    .get(ComposedIndustryScenario.CASH).cash());
            assertTrue(ComposedCompanyReport.of(world, "resources").balanced());
        }
    }

    @Test void namedSubscriberReceivesOnlyThePublicOfferAndTheConfirmedNewLot() throws Exception {
        Path journal=dir.resolve("equity.journal");
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(journal)) {
            OperationsFixture.grantBudgetAndAppoint(w,ComposedIndustryScenario.RESOURCE,ComposedIndustryScenario.DIRECTORS,
                    ComposedIndustryScenario.MANAGER,"http-receipts",List.of(0L));
            ResourceCessationTest.vote(w,"subscribe",BoardModel.Topic.RESOURCE_PRIMARY_OFFER,4);
        }
        Path keys=dir.resolve("fixture-keys");
        Files.writeString(keys,String.join("\n",List.of("sk-local-only-test-1","sk-local-only-test-2","sk-local-only-test-3","sk-local-only-test-4","sk-local-only-test-5")));
        try(var host=new WorldWebHost(()->{
            var w=ComposedIndustryScenario.openTenCompanyAutonomy(journal);
            return new WorldWebHost.Opened(w,w.profile().manifest().runId());
        });var routes=SceneRoutes.open(dir);var server=new WorldWebServer(host,KeySlots.load(keys),routes,0,"research-bootstrap",null,console::add)) {
            server.start();base=server.baseUrl();admin();person("investor");
            var operations=JSON.readTree(request("GET","/api/me/operations",null).body());
            var row=java.util.stream.StreamSupport.stream(operations.path("companies").spliterator(),false)
                    .filter(c->"resources".equals(c.path("companyId").asText())).findFirst().orElseThrow();
            assertEquals("SUBSCRIBE_SHARES",row.at("/actions/0").asText()); assertEquals(1,row.path("actions").size());
            assertEquals("subscribe",row.at("/primaryOffer/meetingIds/0").asText());
            assertFalse(row.has("financial"));assertFalse(row.has("jobs"));assertFalse(row.has("reports"));
            long due=operations.path("timeMillis").asLong()+2000;
            assertFalse(operations.has("position"));
            var body=JSON.writeValueAsString(Map.of("expectedPosition",operations.required("observationToken").asText(),"commandId","http-pay",
                    "taskId","http-pay-work","dueTimeMillis",Long.toString(due),"durationMillis","1000","companyId","resources",
                    "action","SUBSCRIBE_SHARES","fields",Map.of("meetingId","subscribe","shares","7")));
            admin();person("nina");
            assertFalse(request("GET","/api/me/operations",null).body().contains("SUBSCRIBE_SHARES"));
            assertNotEquals(200,request("POST","/api/me/resources/action",body).statusCode());
            admin();person("investor");
            var result=request("POST","/api/me/resources/action",body);assertEquals(200,result.statusCode(),result.body());
            assertFalse(JSON.readTree(result.body()).has("position"));
            assertEquals(409,request("POST","/api/me/resources/action",body).statusCode());
            admin();var current=JSON.readTree(request("GET","/api/admin/operations",null).body());
            var settled=request("POST","/api/admin/advance",JSON.writeValueAsString(Map.of("expectedPosition",current.path("position").asText(),"targetMillis",Long.toString(due))));
            assertEquals(200,settled.statusCode(),settled.body());
            var audited = JSON.readTree(request("GET","/api/admin/operations",null).body()).findValues("reports").stream()
                    .flatMap(rows -> java.util.stream.StreamSupport.stream(rows.spliterator(),false))
                    .filter(r -> "SUBSCRIBE_SHARES".equals(r.path("action").asText()) && r.path("amount").asDouble()>0)
                    .findFirst().orElseThrow();
            assertTrue(audited.path("position").asLong()>0);
            person(ComposedIndustryScenario.MANAGER);
            var managerView = JSON.readTree(request("GET","/api/me/operations",null).body());
            assertTrue(managerView.findValues("position").isEmpty());
            var company=managerView.path("companies").get(0);
            assertEquals(company.path("reports"),company.at("/plan/reports"));
            var paid=company.required("reports").get(company.path("reports").size()-1);
            assertEquals("70.00",paid.path("amount").asText());
            assertEquals("SUBSCRIBE_SHARES",paid.path("action").asText());
            admin();person("investor");var page=JSON.readTree(request("GET","/api/me/view",null).body());
            var owned=java.util.stream.StreamSupport.stream(page.path("holdings").spliterator(),false).filter(h->"RESOURCE".equals(h.path("symbol").asText())).findFirst().orElseThrow();
            assertEquals("67",owned.path("quantity").asText()); assertEquals("6.6534",owned.path("issuedSharePercent").asText());
        }
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(journal)) { assertEquals(1007,w.issuedSharesAt("RESOURCE",w.snapshot().position())); }
    }

    private void admin() throws Exception {
        String bootstrap = "research-bootstrap";
        for (String line : console) {
            if (line.contains("引导凭据") && line.indexOf('：') > 0) bootstrap = line.substring(line.indexOf('：') + 1).trim();
        }
        var response = request("POST", "/api/session", JSON.writeValueAsString(Map.of("bootstrap", bootstrap)));
        assertEquals(200, response.statusCode(), response.body());
        cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private void person(String id) throws Exception {
        var response = request("POST", "/api/session/person", JSON.writeValueAsString(Map.of("personId", id)));
        assertEquals(200, response.statusCode(), response.body());
        cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (cookie != null) builder.header("Cookie", cookie);
        if (body == null) builder.GET();
        else builder.header("Origin", base).header("X-World-Request", "1")
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
