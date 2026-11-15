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

/** 真实HTTP验证研发动作、会话权限、位置冲突及God到个人的真值隔离。 */
class PharmaWebHttpTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private String base;
    private String cookie;
    private final List<String> console = new ArrayList<>();

    @Test
    void realHttpKeepsTruthPrivateAndExecutesOnlyBoundManagerCommand() throws Exception {
        Path journal = dir.resolve("world.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            ComposedPharmaWorldTest.grantBudgetAndAppointManager(world);
            ComposedPharmaWorldTest.completeP1(world);
        }
        Path keys = dir.resolve("dummy-keys.txt");
        Files.writeString(keys, String.join("\n", List.of("sk-research-test-000001",
                "sk-research-test-000002", "sk-research-test-000003", "sk-research-test-000004",
                "sk-research-test-000005")));
        try (WorldWebHost host = new WorldWebHost(() -> {
                var runtime = ComposedPharmaScenario.openDebug(journal);
                return new WorldWebHost.Opened(runtime, runtime.profile().manifest().runId());
            });
             WorldWebServer server = new WorldWebServer(host, KeySlots.load(keys),
                     SceneRoutes.open(dir), 0, "research-bootstrap", null, console::add)) {
            server.start();
            base = server.baseUrl();
            assertEquals(401, request("GET", "/api/admin/research", null).statusCode());
            admin();
            HttpResponse<String> god = request("GET", "/api/admin/research", null);
            assertEquals(200, god.statusCode(), god.body());
            assertTrue(god.body().contains("COMPLETED"));
            assertTrue(god.body().contains("companyReport"));
            assertEquals(200, request("GET", "/research.js", null).statusCode());

            person("ivan");
            assertEquals(403, request("GET", "/api/admin/research", null).statusCode());
            var personal = request("GET", "/api/me/research", null);
            assertEquals(200, personal.statusCode(), personal.body());
            assertFalse(personal.body().contains("outcomes"));
            assertFalse(personal.body().contains("approvalRef"));
            assertFalse(personal.body().contains("COMPLETED"));
            assertFalse(personal.body().contains("companyReport"));
            JsonNode view = JSON.readTree(personal.body());
            long at = Long.parseLong(view.path("timeMillis").asText());
            Map<String, Object> command = new java.util.LinkedHashMap<>(Map.of(
                    "expectedPosition", view.path("position").asText(), "commandId", "web-register",
                    "taskId", "web-register-task", "companyId", "pharma", "action", "REGISTER",
                    "projectId", "p7", "dueTimeMillis", Long.toString(at + 2_000), "durationMillis", "1000"));
            command.put("personPartyId", "alice");
            assertEquals(400, request("POST", "/api/me/research/action", JSON.writeValueAsString(command)).statusCode());
            command.remove("personPartyId");
            command.put("dueTimeMillis", 1.25);
            assertEquals(400, request("POST", "/api/me/research/action", JSON.writeValueAsString(command)).statusCode());
            command.put("dueTimeMillis", Long.toString(at + 2_000));
            var scheduled = request("POST", "/api/me/research/action", JSON.writeValueAsString(command));
            assertEquals(200, scheduled.statusCode(), scheduled.body());
            assertTrue(scheduled.body().contains("SCHEDULED"));
            assertEquals(409, request("POST", "/api/me/research/action", JSON.writeValueAsString(command)).statusCode());
            JsonNode pending = JSON.valueToTree(host.researchView(null));
            for (JsonNode project : pending.path("projects")) {
                if (project.path("projectId").asText().equals("p7")) {
                    assertEquals("UNREGISTERED", project.path("status").asText(),
                            "已排程时尚未执行立项");
                }
            }

            admin();
            var advance = request("POST", "/api/admin/advance", JSON.writeValueAsString(Map.of(
                    "expectedPosition", JSON.readTree(scheduled.body()).path("position").asText(),
                    "targetMillis", Long.toString(at + 2_000))));
            assertEquals(200, advance.statusCode(), advance.body());
            JsonNode after = JSON.readTree(request("GET", "/api/admin/research", null).body());
            boolean registered = false;
            for (JsonNode project : after.path("projects")) {
                if (project.path("projectId").asText().equals("p7")) {
                    registered = project.path("status").asText().equals("ACTIVE");
                }
            }
            assertTrue(registered, after.toPrettyString());

            person(WorldWebScenario.MEDIA_PERSONS.get(0));
            var denied = request("GET", "/api/me/research", null);
            assertFalse(JSON.readTree(denied.body()).path("enabled").asBoolean());
            assertFalse(denied.body().contains("projects"));
            command.put("expectedPosition", JSON.readTree(denied.body()).path("position").asText());
            assertEquals(400, request("POST", "/api/me/research/action", JSON.writeValueAsString(command)).statusCode());
        }
        try (WorldRuntime recovered = ComposedPharmaScenario.openDebug(journal)) {
            assertNotNull(recovered.researchRun().orElseThrow().state().projects().get("p7"));
        }
    }

    @Test
    void newCompanyIsSelectableAndOwnDirectorCanConveneThroughHttp() throws Exception {
        Path journal = dir.resolve("navigation.journal");
        Path keys = dir.resolve("navigation-keys.txt");
        Files.writeString(keys, String.join("\n", List.of("sk-navigation-test-000001",
                "sk-navigation-test-000002", "sk-navigation-test-000003",
                "sk-navigation-test-000004", "sk-navigation-test-000005")));
        try (WorldWebHost host = new WorldWebHost(() -> {
                var runtime = ComposedPharmaScenario.openDebug(journal);
                return new WorldWebHost.Opened(runtime, runtime.profile().manifest().runId());
            });
             WorldWebServer server = new WorldWebServer(host, KeySlots.load(keys),
                     SceneRoutes.open(dir), 0, "research-bootstrap", null, console::add)) {
            server.start();
            base = server.baseUrl();
            admin();
            var response = request("POST", "/api/admin/engineering", JSON.writeValueAsString(Map.of("companyId", "pharma")));
            assertEquals(200, response.statusCode(), response.body());
            JsonNode god = JSON.readTree(response.body());
            assertEquals("pharma", god.path("companyId").asText());
            assertEquals("RESEARCH", god.path("template").asText());
            assertEquals(5, god.path("companies").size());
            assertEquals("2000.00", god.path("report").path("companyCash").asText());
            assertEquals(5, god.path("seats").path("rows").size());
            assertTrue(god.path("contracts").path("rows").isEmpty());
            person("frank");
            var own = JSON.readTree(request("GET", "/api/me/engineering", null).body());
            assertEquals(1, own.path("companies").size(), own.toPrettyString());
            assertEquals("pharma", own.path("companies").get(0).path("companyId").asText());
            Map<String, Object> command = new java.util.LinkedHashMap<>(Map.of(
                    "expectedPosition", own.path("position").asText(), "commandId", "web-convene",
                    "taskId", "web-convene-task", "companyId", "north", "tool", "board.convene",
                    "meetingId", "web-meeting", "dueTimeMillis", "1000",
                    "materialDeadline", "3000", "opensAt", "4000", "closesAt", "6000"));
            assertEquals(400, request("POST", "/api/me/engineering/action", JSON.writeValueAsString(command)).statusCode());
            command.put("companyId", "pharma");
            var scheduled = request("POST", "/api/me/engineering/action", JSON.writeValueAsString(command));
            assertEquals(200, scheduled.statusCode(), scheduled.body());
            admin();
            var advanced = request("POST", "/api/admin/advance", JSON.writeValueAsString(Map.of(
                    "expectedPosition", JSON.readTree(scheduled.body()).path("position").asText(),
                    "targetMillis", "1000")));
            assertEquals(200, advanced.statusCode(), advanced.body());
            person("frank");
            var after = JSON.readTree(request("GET", "/api/me/engineering", null).body());
            assertEquals("web-meeting", after.path("companies").get(0).path("meetings").get(0).path("meetingId").asText());
        }
        try (WorldRuntime reopened = ComposedPharmaScenario.openDebug(journal)) {
            assertTrue(reopened.boardOfCompany("pharma").orElseThrow().board().meetings().containsKey("web-meeting"));
            assertTrue(reopened.boardOfCompany("north").orElseThrow().board().meetings().isEmpty());
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
