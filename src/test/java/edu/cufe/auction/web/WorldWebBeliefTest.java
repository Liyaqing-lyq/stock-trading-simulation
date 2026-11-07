package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.BeliefModel;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.WorldRoleSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 信念管理员查询使用真实HTTP、真实世界与dummy凭据。 */
class WorldWebBeliefTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private WorldWebHost host;
    private WorldWebServer server;
    private SceneRoutes routes;
    private String base;
    private String cookie;
    private WorldRuntime world;
    private Path directory;

    @AfterEach
    void close() {
        if (server != null) server.close();
        if (host != null) host.close();
        if (routes != null) routes.close();
    }

    @Test
    void queryIsAuthenticatedAndOldProfileRemainsUnavailable() throws Exception {
        start();
        assertEquals(401, post("/api/admin/beliefs", "{\"personId\":\"alice\"}", base, true).statusCode());
        login();
        var before = host.adminSnapshot();
        var response = post("/api/admin/beliefs", "{\"personId\":\"alice\"}", base, true);
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
        var body = JSON.readTree(response.body());
        assertFalse(body.path("available").asBoolean(true));
        assertEquals("alice", body.path("personPartyId").asText());
        assertEquals(Long.toString(before.position()), body.path("position").asText());
        assertEquals(before, host.adminSnapshot());
        var switched = post("/api/session/person", "{\"personId\":\"alice\"}", base, true);
        assertEquals(200, switched.statusCode());
        cookie = switched.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        assertEquals(403, post("/api/admin/beliefs", "{\"personId\":\"alice\"}", base, true).statusCode());
    }

    @Test
    void queryRejectsCrossOriginMissingMarkerAndInvalidBodies() throws Exception {
        start(); login();
        assertEquals(403, post("/api/admin/beliefs", "{}", "http://example.invalid", true).statusCode());
        assertEquals(403, post("/api/admin/beliefs", "{}", base, false).statusCode());
        for (String body : List.of("{}", "null", "[]", "{\"personId\":null}",
                "{\"personId\":12}", "{\"personId\":\" \"}",
                "{\"personId\":\"alice\",\"policy\":{}}",
                "{\"personId\":\"alice\",\"personId\":\"bob\"}",
                "{\"personId\":\"alice\"}{}")) {
            assertEquals(400, post("/api/admin/beliefs", body, base, true).statusCode(), body);
        }
    }

    @Test
    void actualPublicationReadingAndLearningRemainVisibleAfterRestart() throws Exception {
        start(true); login();
        host.call(() -> {
            world.openRoleSession("bob").publishForecast("forecast", "forecast-task", 10,
                    new WorldRoleSession.ForecastDraft("claim-one", "forecast-document", "实际预测", "预计至少一份合同",
                            "本人预测", 1, WorldWebScenario.COMPANY, BeliefModel.Comparator.AT_LEAST, 1, 100));
            world.advanceTo(11);
            world.scheduleStartReading("read", "read-task", 12, "alice", "forecast-document", 1);
            world.advanceTo(14);
            var profile = world.roleView("alice");
            var claim = profile.cognition().claims().stream().filter(item -> item.docId().equals("forecast-document")).findFirst().orElseThrow();
            world.openRoleSession("alice").assertBelief("belief", "belief-task", 20,
                    new WorldRoleSession.BeliefDraft("my-belief", WorldWebScenario.COMPANY,
                            BeliefModel.Comparator.AT_LEAST, 1, 100, claim.claimId(),
                            List.of(profile.observations().get(0).ref()), "本人理由 <img src=x>"));
            world.advanceTo(21);
            world.openRoleSession(WorldWebScenario.CHAIR).reportOrders("fact", "fact-task", 30,
                    new WorldRoleSession.ReportDraft(WorldWebScenario.COMPANY, 100));
            world.advanceTo(101);
            return null;
        });
        var beforeRead = query("alice");
        assertTrue(beforeRead.path("available").asBoolean());
        assertEquals(1, beforeRead.path("beliefs").path("total").asInt());
        assertEquals("OPEN", beforeRead.path("beliefs").path("rows").get(0).path("verdict").asText());
        assertEquals(0, beforeRead.path("evaluations").path("total").asInt(), "公告已发布不等于本人已读");
        assertEquals(400, post("/api/admin/beliefs", JSON.writeValueAsString(java.util.Map.of("personId", "ghost")), base, true).statusCode());
        String factDocument = host.call(() -> world.roleView("alice").catalog().stream()
                .filter(item -> !item.docId().equals("forecast-document")).findFirst().orElseThrow().docId());
        long readDone = host.call(() -> {
            world.scheduleStartReading("fact-read", "fact-read-task", 102, "alice", factDocument, 1);
            world.advanceTo(102);
            return world.snapshot().information().orElseThrow().information().active().stream()
                    .filter(item -> item.docId().equals(factDocument)).findFirst().orElseThrow().dueAt();
        });
        assertEquals(0, query("alice").path("evaluations").path("total").asInt(), "读到一半仍不能学习");
        host.advance(host.adminSnapshot().position(), readDone);
        var learned = query("alice");
        assertEquals(1, learned.path("evaluations").path("total").asInt());
        assertEquals("MISS", learned.path("beliefs").path("rows").get(0).path("verdict").asText());
        assertEquals("0", learned.path("evaluations").path("rows").get(0).path("factValue").asText(), "实际公司没有合同，值应由台账产生");
        assertEquals(480, learned.path("sources").get(0).path("competencePermille").asInt());
        assertEquals("bob", learned.path("sources").get(0).path("sourcePartyId").asText());
        assertEquals(0, query("bob").path("beliefs").path("total").asInt(), "另一人未形成判断");
        var stable = host.adminSnapshot();
        assertEquals(learned, query("alice"));
        assertEquals(stable, host.adminSnapshot(), "查询不推进/学习");
        close(); cookie = null;
        openServer(true); login();
        assertEquals(learned, query("alice"), "真实同档恢复的审计必须保持");
    }

    private com.fasterxml.jackson.databind.JsonNode query(String person) throws Exception {
        var response = post("/api/admin/beliefs", JSON.writeValueAsString(java.util.Map.of("personId", person)), base, true);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private void start() throws Exception { start(false); }

    private void start(boolean belief) throws Exception {
        Path root = Path.of("target", "web-belief-tests");
        Files.createDirectories(root);
        directory = Files.createTempDirectory(root, "case-");
        Path keys = directory.resolve("dummy-keys.txt");
        Files.writeString(keys, "sk-belief-fixture-00001\nsk-belief-fixture-00002\nsk-belief-fixture-00003\nsk-belief-fixture-00004\nsk-belief-fixture-00005");
        openServer(belief);
    }

    private void openServer(boolean belief) throws Exception {
        Path keys = directory.resolve("dummy-keys.txt");
        routes = SceneRoutes.open(directory);
        host = new WorldWebHost(() -> {
            var opened = belief ? WorldWebScenario.openBelief(directory.resolve("world.journal"))
                    : WorldWebScenario.openLive(directory.resolve("world.journal"));
            world = opened.runtime(); return opened;
        });
        host.adminSnapshot();
        server = new WorldWebServer(host, KeySlots.load(keys), routes, 0, "belief-fixture", null, ignored -> { });
        server.start(); base = server.baseUrl();
    }

    private void login() throws Exception {
        var response = post("/api/session", "{\"bootstrap\":\"belief-fixture\"}", base, true);
        assertEquals(200, response.statusCode());
        cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> post(String path, String body, String origin, boolean marker) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Origin", origin);
        if (cookie != null) builder.header("Cookie", cookie);
        if (marker) builder.header("X-World-Request", "1");
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
