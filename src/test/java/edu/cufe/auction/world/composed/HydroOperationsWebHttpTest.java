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

/** 真实HTTP验证水电经营动作、岗位隔离、精确数字、完成与恢复。 */
class HydroOperationsWebHttpTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private String base;
    private String cookie;
    private final List<String> console = new ArrayList<>();

    @Test
    void managerDispatchesThroughActualHttpAndCannotSubmitPrivateTruth() throws Exception {
        Path journal = dir.resolve("world.journal");
        try (WorldRuntime world = ComposedIndustryScenario.openWithHydro(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, "hydro",
                    HydroIndustryConfig.DIRECTORS,
                    HydroIndustryConfig.MANAGER, "web-govern", List.of(0L));
        }
        Path keys = dir.resolve("dummy-keys.txt");
        Files.writeString(keys, String.join("\n", List.of("sk-research-test-000001", "sk-research-test-000002",
                "sk-research-test-000003", "sk-research-test-000004", "sk-research-test-000005")));
        try (WorldWebHost host = new WorldWebHost(() -> {
            var world = ComposedIndustryScenario.openWithHydro(journal);
            return new WorldWebHost.Opened(world, world.profile().manifest().runId());
        }); WorldWebServer server = new WorldWebServer(host, KeySlots.load(keys), SceneRoutes.open(dir),
                0, "research-bootstrap", null, console::add)) {
            server.start(); base = server.baseUrl();
            assertEquals(401, request("GET", "/api/admin/operations", null).statusCode());
            admin();
            host.operationsView(null);
            var god = request("GET", "/api/admin/operations", null);
            assertEquals(200, god.statusCode(), god.body());
            assertEquals(5, JSON.readTree(god.body()).path("companies").size());
            assertTrue(god.body().contains("scenarioTruth"));
            person("frank");
            assertEquals(403, request("GET", "/api/admin/operations", null).statusCode());
            assertEquals(0, JSON.readTree(request("GET", "/api/me/operations", null).body()).path("companies").size());
            admin(); person(HydroIndustryConfig.MANAGER);
            var before = JSON.readTree(request("GET", "/api/me/operations", null).body());
            assertEquals(1, before.path("companies").size());
            assertFalse(before.toString().contains("scenarioTruth"));
            assertFalse(before.toString().contains("\"plans\""));
            assertFalse(before.toString().contains("mfg-versa"));
            assertTrue(before.path("companies").get(0).path("financial").path("assets").path("经营现金").isTextual());
            long due = Long.parseLong(before.path("timeMillis").asText()) + 902000L;
            Map<String, Object> command = new java.util.LinkedHashMap<>(Map.of("expectedPosition", before.path("position").asText(),
                    "commandId", "web-po", "taskId", "web-po-task", "dueTimeMillis", Long.toString(due),
                    "durationMillis", "900000", "companyId", "hydro", "action", "DISPATCH_FLOW",
                    "fields", Map.of("cubicMetresPerHour", "600")));
            command.put("fields", Map.of("cubicMetresPerHour", 1.5));
            assertEquals(400, request("POST", "/api/me/hydro/action", JSON.writeValueAsString(command)).statusCode());
            command.put("fields", Map.of("cubicMetresPerHour", "600", "personPartyId", "other"));
            assertEquals(400, request("POST", "/api/me/hydro/action", JSON.writeValueAsString(command)).statusCode());
            command.put("fields", Map.of("cubicMetresPerHour", "600"));
            command.put("companyId", "mfg-versa");
            assertEquals(400, request("POST", "/api/me/hydro/action", JSON.writeValueAsString(command)).statusCode());
            command.put("companyId", "hydro");
            var scheduled = request("POST", "/api/me/hydro/action", JSON.writeValueAsString(command));
            assertEquals(200, scheduled.statusCode(), scheduled.body());
            assertEquals(409, request("POST", "/api/me/hydro/action", JSON.writeValueAsString(command)).statusCode());
            admin();
            var advanced = request("POST", "/api/admin/advance", JSON.writeValueAsString(Map.of(
                    "expectedPosition", JSON.readTree(scheduled.body()).path("position").asText(),
                    "targetMillis", Long.toString(due))));
            assertEquals(200, advanced.statusCode(), advanced.body());
            var ordered = JSON.readTree(request("GET", "/api/admin/operations", null).body());
            var tech = java.util.stream.StreamSupport.stream(ordered.path("companies").spliterator(), false)
                    .filter(c -> c.path("companyId").asText().equals("hydro")).findFirst().orElseThrow();
            assertEquals("600", tech.path("plant").path("dispatch").path("cubicMetresPerHour").asText());
            var delivered = request("POST", "/api/admin/advance", JSON.writeValueAsString(Map.of(
                    "expectedPosition", ordered.path("position").asText(), "targetMillis", Long.toString(due+3600000))));
            assertEquals(200, delivered.statusCode(), delivered.body());

        }
        try (WorldRuntime world = ComposedIndustryScenario.openWithHydro(journal)) {
            assertTrue(HydroIndustryWorldTest.report(world).generatedKilowattHours().signum()>0);
            assertTrue(ComposedCompanyReport.of(world, "hydro").balanced());
        }
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
