package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.WorldRuntime;
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

/** 回忆管理员查询使用真实HTTP、真实世界与dummy凭据。 */
class WorldWebRecallTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private WorldWebHost host;
    private WorldWebServer server;
    private SceneRoutes routes;
    private String base;
    private String cookie;
    private WorldRuntime world;

    @AfterEach
    void close() {
        if (server != null) server.close();
        if (host != null) host.close();
        if (routes != null) routes.close();
    }

    @Test
    void queryIsAuthenticatedAndOldProfileRemainsUnavailable() throws Exception {
        start();
        assertEquals(401, post("/api/admin/recall", "{\"personId\":\"alice\"}", base, true).statusCode());
        login();
        var before = host.adminSnapshot();
        var response = post("/api/admin/recall", "{\"personId\":\"alice\"}", base, true);
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
        assertEquals(403, post("/api/admin/recall", "{\"personId\":\"alice\"}", base, true).statusCode());
    }

    @Test
    void queryRejectsCrossOriginMissingMarkerAndInvalidBodies() throws Exception {
        start(); login();
        assertEquals(403, post("/api/admin/recall", "{}", "http://example.invalid", true).statusCode());
        assertEquals(403, post("/api/admin/recall", "{}", base, false).statusCode());
        for (String body : List.of("{}", "null", "[]", "{\"personId\":null}",
                "{\"personId\":12}", "{\"personId\":\" \"}",
                "{\"personId\":\"alice\",\"policy\":{}}",
                "{\"personId\":\"alice\",\"personId\":\"bob\"}",
                "{\"personId\":\"alice\"}{}")) {
            assertEquals(400, post("/api/admin/recall", body, base, true).statusCode(), body);
        }
    }

    @Test
    void realCompletedReadDecaysAndOnlyCompletedRereadRestoresBody() throws Exception {
        start(true); login();
        var initial = JSON.readTree(post("/api/admin/recall", "{\"personId\":\"alice\"}", base, true).body());
        assertTrue(initial.path("available").asBoolean());
        assertEquals(1, initial.path("eligibleCount").asInt());
        assertEquals(1, initial.path("selected").size());
        assertEquals("实际读取的原文 <img src=x> α", initial.path("selected").get(0).path("body").asText());
        assertFalse(initial.toString().contains("BOB_ONLY_BODY"));
        assertFalse(initial.toString().contains("HIDDEN_SOURCE"));
        assertEquals("22", initial.path("selected").get(0).path("completedAt").asText());
        assertEquals(1000, initial.path("selected").get(0).path("score").asInt());
        long forgottenAt = 22 + 4 * initial.path("policy").path("halfLifeMillis").asLong();
        host.call(() -> world.scheduleStartReading("reread", "reread-task", forgottenAt + 1, "alice", "D", 1));
        host.advance(host.adminSnapshot().position(), forgottenAt);
        var forgotten = JSON.readTree(post("/api/admin/recall", "{\"personId\":\"alice\"}", base, true).body());
        assertEquals(0, forgotten.path("selected").size());
        assertEquals(1, forgotten.path("forgottenCount").asInt());
        assertEquals(2, host.informationView().observations().total(), "遗忘不删除管理端原始经历");
        host.advance(host.adminSnapshot().position(), forgottenAt + 10);
        var reading = JSON.readTree(post("/api/admin/recall", "{\"personId\":\"alice\"}", base, true).body());
        assertEquals(0, reading.path("selected").size(), "仍在阅读时不能提前恢复");
        host.advance(host.adminSnapshot().position(), forgottenAt + 11);
        var restored = JSON.readTree(post("/api/admin/recall", "{\"personId\":\"alice\"}", base, true).body());
        assertEquals(1, restored.path("selected").size());
        assertEquals(1000, restored.path("selected").get(0).path("score").asInt());
        assertEquals("22", restored.path("selected").get(0).path("completedAt").asText());
        assertEquals(Long.toString(forgottenAt + 11), restored.path("selected").get(0).path("lastReadAt").asText());
        assertEquals(Long.toString(host.adminSnapshot().position()), restored.path("position").asText());
        assertEquals(400, post("/api/admin/recall", "{\"personId\":\"ghost\"}", base, true).statusCode());
        assertEquals(400, post("/api/admin/recall", "{\"personId\":\"cufe\"}", base, true).statusCode());
    }

    private void start() throws Exception {
        start(false);
    }

    private void start(boolean cognition) throws Exception {
        Path root = Path.of("target", "web-recall-tests");
        Files.createDirectories(root);
        Path dir = Files.createTempDirectory(root, "case-");
        Path keys = dir.resolve("dummy-keys.txt");
        Files.writeString(keys, "sk-recall-fixture-00001\nsk-recall-fixture-00002\nsk-recall-fixture-00003\nsk-recall-fixture-00004\nsk-recall-fixture-00005");
        routes = SceneRoutes.open(dir);
        host = new WorldWebHost(() -> {
            var opened = cognition ? WorldWebScenario.openCognition(dir.resolve("world.journal"))
                    : WorldWebScenario.openLive(dir.resolve("world.journal"));
            world = opened.runtime();
            if (cognition) {
                world.schedulePublishDocument("pub", "pub-task", 10, "D", "alice",
                        new InformationModel.Audience(InformationModel.AudienceKind.PUBLIC, null),
                        "真实回忆测试", "实际读取的原文 <img src=x> α", "公开测试资料", 10, List.of("HIDDEN_SOURCE"));
                world.schedulePublishDocument("pub-b", "pub-b-task", 10, "B", "bob",
                        new InformationModel.Audience(InformationModel.AudienceKind.DIRECT, "bob"),
                        "另一人物的资料", "BOB_ONLY_BODY", "公开测试资料", 10, List.of());
                world.advanceTo(11);
                world.scheduleStartReading("read-a", "read-a-task", 12, "alice", "D", 1);
                world.scheduleStartReading("read-b", "read-b-task", 12, "bob", "B", 1);
                world.advanceTo(30);
            }
            return opened;
        });
        host.adminSnapshot();
        server = new WorldWebServer(host, KeySlots.load(keys), routes, 0, "recall-fixture", null, ignored -> { });
        server.start(); base = server.baseUrl();
    }

    private void login() throws Exception {
        var response = post("/api/session", "{\"bootstrap\":\"recall-fixture\"}", base, true);
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
