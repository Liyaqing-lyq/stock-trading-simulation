package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景配置端点：五槽位只读视图、全量替换、revision 冲突、真实写失败、权限与持久化。
 */
class WorldWebRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final String BOOTSTRAP = "formal-routes-bootstrap";

    private Path dir;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;

    @BeforeEach
    void start() throws Exception {
        dir = Path.of("target", "web-test", "routes-api", String.valueOf(System.nanoTime()));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("keys.txt"), String.join("\n",
                        "sk-aaaaaaaaaaaaaaaa", "sk-bbbbbbbbbbbbbbbb", "sk-cccccccccccccccc",
                        "sk-dddddddddddddddd", "sk-eeeeeeeeeeeeeeee"),
                StandardCharsets.UTF_8);
        host = new WorldWebHost(() -> WorldWebScenario.open(dir.resolve("world.journal")));
        server = new WorldWebServer(host, KeySlots.load(dir.resolve("keys.txt")),
                SceneRoutes.open(dir), 0, BOOTSTRAP, null, line -> { });
        server.start();
        base = server.baseUrl();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        if (host != null) {
            host.close();
        }
    }

    @Test
    @DisplayName("GET 返回 revision 字符串、固定提供方/地址、connected=false 与五场景")
    void readRoutes() throws Exception {
        login();
        JsonNode view = body(get("/api/admin/routes"));
        assertEquals("0", view.path("revision").asText());
        assertEquals("deepseek", view.path("provider").asText());
        assertEquals("https://api.deepseek.com", view.path("baseUrl").asText());
        assertFalse(view.path("connected").asBoolean());
        JsonNode routes = view.path("routes");
        assertEquals(5, routes.size());
        String[] scenes = {"governance", "research", "trading", "media", "evaluation"};
        String[] labels = {"公司治理", "机构研究", "交易执行", "媒体社交", "评估实验"};
        for (int index = 0; index < 5; index++) {
            JsonNode route = routes.get(index);
            assertEquals(scenes[index], route.path("scene").asText());
            assertEquals(labels[index], route.path("sceneLabel").asText());
            assertEquals("key-" + (index + 1), route.path("slotId").asText());
            assertEquals("deepseek-flash", route.path("model").asText());
            assertFalse(route.path("enabled").asBoolean());
            assertEquals(5, route.path("maxCalls").asInt());
            assertEquals(8192, route.path("maxTotalTokens").asInt());
            assertEquals(512, route.path("maxOutputTokens").asInt());
            assertEquals(1, route.path("maxConcurrency").asInt());
        }
        assertFalse(view.toString().contains("sk-"));
    }

    @Test
    @DisplayName("POST 全量替换：revision 前进、重开文件可见、返回 GET 形状")
    void replaceRoutes() throws Exception {
        login();
        JsonNode current = body(get("/api/admin/routes"));
        String body = replaceBody("0", routesWith(true, "key-5", "deepseek-v4-pro", 7));

        HttpResponse<String> replaced = post("/api/admin/routes", body);
        assertEquals(200, replaced.statusCode(), replaced.body());
        JsonNode after = body(replaced);
        assertEquals("1", after.path("revision").asText());
        assertTrue(after.path("routes").get(0).path("enabled").asBoolean());
        assertEquals("key-5", after.path("routes").get(0).path("slotId").asText());
        assertEquals("deepseek-v4-pro", after.path("routes").get(0).path("model").asText());
        assertEquals(7, after.path("routes").get(0).path("maxCalls").asInt());

        try (SceneRoutes reopened = SceneRoutes.open(dir)) {
            assertEquals(1L, reopened.view().revision());
            assertEquals("key-5", reopened.view().routes().get(0).slotId());
        }
        assertEquals(current.path("revision").asText(), "0");
    }

    @Test
    @DisplayName("冲突 409 返回当前 revision 字符串且不覆盖")
    void conflict() throws Exception {
        login();
        assertEquals(200, post("/api/admin/routes",
                replaceBody("0", routesWith(false, "key-2", "deepseek-flash", 5))).statusCode());

        HttpResponse<String> stale = post("/api/admin/routes",
                replaceBody("0", routesWith(true, "key-3", "deepseek-v4-pro", 9)));
        assertEquals(409, stale.statusCode());
        assertEquals("1", body(stale).path("revision").asText());
        assertTrue(body(stale).path("revision").isTextual());

        try (SceneRoutes reopened = SceneRoutes.open(dir)) {
            assertEquals(1L, reopened.view().revision());
            assertEquals("key-2", reopened.view().routes().get(0).slotId());
        }
    }

    @Test
    @DisplayName("每项不允许 label/provider/url；未知字段与错误类型 400")
    void rejectsForbiddenFields() throws Exception {
        login();
        String baseBody = "{\"expectedRevision\":\"0\",\"routes\":["
                + route("governance", "key-1", "deepseek-flash", false, 5, 8192, 512, 1)
                + "," + route("research", "key-2", "deepseek-flash", false, 5, 8192, 512, 1)
                + "," + route("trading", "key-3", "deepseek-flash", false, 5, 8192, 512, 1)
                + "," + route("media", "key-4", "deepseek-flash", false, 5, 8192, 512, 1)
                + "," + route("evaluation", "key-5", "deepseek-flash", false, 5, 8192, 512, 1)
                + "]}";

        assertEquals(400, post("/api/admin/routes", baseBody.replace(
                "\"scene\":\"governance\"",
                "\"scene\":\"governance\",\"label\":\"治理\"")).statusCode());
        assertEquals(400, post("/api/admin/routes", baseBody.replace(
                "\"scene\":\"governance\"",
                "\"scene\":\"governance\",\"provider\":\"openai\"")).statusCode());
        assertEquals(400, post("/api/admin/routes", baseBody.replace(
                "\"scene\":\"governance\"",
                "\"scene\":\"governance\",\"url\":\"https://evil\"")).statusCode());
        assertEquals(400, post("/api/admin/routes", baseBody.replace(
                "\"maxCalls\":5", "\"maxCalls\":\"5\"")).statusCode());
        assertEquals(400, post("/api/admin/routes", baseBody.replace(
                "\"maxCalls\":5", "\"maxCalls\":0")).statusCode());
        assertEquals(400, post("/api/admin/routes", baseBody.replace(
                "\"model\":\"deepseek-flash\"", "\"model\":\"gpt-4o\"")).statusCode());
        assertEquals(400, post("/api/admin/routes", "{\"expectedRevision\":\"0\"}").statusCode());
        assertEquals(400, post("/api/admin/routes", "{\"expectedRevision\":0,\"routes\":[]}")
                .statusCode(), "expectedRevision 必须是字符串");
        try (SceneRoutes reopened = SceneRoutes.open(dir)) {
            assertEquals(0L, reopened.view().revision(), "非法请求不得改变配置");
        }
    }

    @Test
    @DisplayName("真实写失败返回 503，旧 revision 与旧文件保持")
    void writeFailure() throws Exception {
        login();
        Path file = dir.resolve(SceneRoutes.FILE_NAME);
        byte[] before = Files.readAllBytes(file);
        boolean locked = file.toFile().setWritable(false);
        try {
            assertTrue(locked, "需要能设置只读");
            HttpResponse<String> failed = post("/api/admin/routes",
                    replaceBody("0", routesWith(true, "key-1", "deepseek-flash", 6)));
            assertEquals(503, failed.statusCode());
            assertEquals(before.length, Files.readAllBytes(file).length);
            assertEquals(0L, SceneRoutes.open(dir).view().revision());
        } finally {
            file.toFile().setWritable(true);
        }
    }

    @Test
    @DisplayName("个人会话 403，退出后 401")
    void accessControl() throws Exception {
        login();
        cookie = cookieOf(post("/api/session/person", "{\"personId\":\"investor\"}"));
        assertEquals(403, get("/api/admin/routes").statusCode());
        assertEquals(403, post("/api/admin/routes",
                replaceBody("0", routesWith(false, "key-1", "deepseek-flash", 5))).statusCode());

        cookie = null;
        assertEquals(401, get("/api/admin/routes").statusCode());
    }

    // ------------------------------------------------------------ 工具

    private void login() throws Exception {
        HttpResponse<String> response = post("/api/session",
                "{\"bootstrap\":\"" + BOOTSTRAP + "\"}");
        assertEquals(200, response.statusCode());
        cookie = cookieOf(response);
    }

    private static String replaceBody(String revision, String routes) {
        return "{\"expectedRevision\":\"" + revision + "\",\"routes\":[" + routes + "]}";
    }

    private static String routesWith(boolean enabled, String slotId, String model, int calls) {
        SceneRoutes.Scene[] scenes = SceneRoutes.Scene.values();
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < scenes.length; index++) {
            boolean first = index == 0;
            parts.add(route(scenes[index].id(), first ? slotId : "key-" + (index + 1),
                    first ? model : "deepseek-flash", first && enabled, first ? calls : 5,
                    8192, 512, 1));
        }
        return String.join(",", parts);
    }

    private static String route(String scene, String slotId, String model, boolean enabled,
                                int calls, int total, int output, int concurrency) {
        return "{\"scene\":\"" + scene + "\",\"slotId\":\"" + slotId + "\",\"model\":\"" + model
                + "\",\"enabled\":" + enabled + ",\"maxCalls\":" + calls
                + ",\"maxTotalTokens\":" + total + ",\"maxOutputTokens\":" + output
                + ",\"maxConcurrency\":" + concurrency + "}";
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request("GET", path, null);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return request("POST", path, body);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if ("POST".equals(method)) {
            builder.header("Origin", base)
                    .header("X-World-Request", "1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            builder.GET();
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private static String cookieOf(HttpResponse<String> response) {
        return response.headers().firstValue("set-cookie")
                .orElseThrow(() -> new AssertionError("缺少 Set-Cookie")).split(";", 2)[0];
    }
}
