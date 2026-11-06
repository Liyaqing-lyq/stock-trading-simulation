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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正式 HTTP 合同：真实 {@link WorldWebScenario} 世界 + 真实 HttpServer，不用 mock。
 *
 * <p>覆盖 Root 私有 19 项的核心语义（严格 JSON、精确字符串、旧会话、人物裁剪），
 * 并明确区分 {@code lastPrice}（无成交为 null）与 {@code referencePrice}（开局/除息参考）。</p>
 */
class WorldWebContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final String BOOTSTRAP = "formal-contract-bootstrap";

    private Path dir;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;
    private final List<String> consoleLines = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        dir = Path.of("target", "web-test", "contract", String.valueOf(System.nanoTime()));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("keys.txt"), String.join("\n",
                        "sk-aaaaaaaaaaaaaaaa", "sk-bbbbbbbbbbbbbbbb", "sk-cccccccccccccccc",
                        "sk-dddddddddddddddd", "sk-eeeeeeeeeeeeeeee"),
                StandardCharsets.UTF_8);
        host = new WorldWebHost(() -> WorldWebScenario.open(dir.resolve("world.journal")));
        server = new WorldWebServer(host, KeySlots.load(dir.resolve("keys.txt")),
                SceneRoutes.open(dir), 0, BOOTSTRAP, null, consoleLines::add);
        server.start();
        base = server.baseUrl();
        cookie = null;
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

    // ------------------------------------------------------------ 登录与身份

    @Test
    void backwardsTimeIsInvalidInputAndDoesNotChangeWorld() throws Exception {
        login();
        String first = body(get("/api/admin/view")).path("position").asText();
        assertEquals(200, post("/api/admin/advance", "{\"expectedPosition\":\"" + first
                + "\",\"targetMillis\":\"1000\"}").statusCode());
        JsonNode before = body(get("/api/admin/view"));
        assertEquals(400, post("/api/admin/advance", "{\"expectedPosition\":\""
                + before.path("position").asText() + "\",\"targetMillis\":\"999\"}").statusCode());
        assertEquals(before, body(get("/api/admin/view")));
    }

    @Test
    @DisplayName("未认证 401；错误引导凭据 401；正确凭据 200 且不下发 token 文本")
    void loginFlow() throws Exception {
        assertEquals(401, get("/api/admin/view").statusCode());

        HttpResponse<String> wrong = post("/api/session", "{\"bootstrap\":\"nope\"}");
        assertEquals(401, wrong.statusCode());

        HttpResponse<String> ok = post("/api/session", "{\"bootstrap\":\"" + BOOTSTRAP + "\"}");
        assertEquals(200, ok.statusCode());
        cookie = cookieOf(ok);
        assertEquals(200, get("/api/admin/view").statusCode());
        assertFalse(ok.body().contains("world_session"));

        HttpResponse<String> reused = post("/api/session", "{\"bootstrap\":\"" + BOOTSTRAP + "\"}");
        assertEquals(401, reused.statusCode());
    }

    @Test
    @DisplayName("GET /api/session 只给角色与人物，不含 token")
    void sessionEndpoint() throws Exception {
        login();
        JsonNode admin = body(get("/api/session"));
        assertEquals("ADMIN", admin.path("role").asText());
        assertFalse(admin.toString().contains("world_session"));

        String adminCookie = cookie;
        HttpResponse<String> switched = post("/api/session/person", "{\"personId\":\"investor\"}");
        assertEquals(200, switched.statusCode());
        cookie = cookieOf(switched);

        JsonNode person = body(get("/api/session"));
        assertEquals("PERSON", person.path("role").asText());
        assertEquals("investor", person.path("personId").asText());
        assertFalse(person.toString().contains("world_session"));

        cookie = adminCookie;
        assertEquals(401, get("/api/admin/view").statusCode(),
                "切换人物后旧管理员 token 必须失效");
    }

    @Test
    @DisplayName("logout 清 Cookie 并使 token 失效")
    void logoutClears() throws Exception {
        login();
        String token = cookie;
        HttpResponse<String> logout = post("/api/session/logout", "{}");
        assertEquals(200, logout.statusCode());
        assertTrue(logout.headers().allValues("Set-Cookie").stream()
                .anyMatch(value -> value.contains("Max-Age=0")));
        cookie = token;
        assertEquals(401, get("/api/me/view").statusCode());
    }

    @Test
    @DisplayName("再次登录管理员让此前人物会话失效")
    void reloginInvalidatesPerson() throws Exception {
        login();
        HttpResponse<String> switched = post("/api/session/person", "{\"personId\":\"investor\"}");
        String personCookie = cookieOf(switched);

        List<String> tokens = consoleTokens();
        HttpResponse<String> again = post("/api/session",
                "{\"bootstrap\":\"" + tokens.get(tokens.size() - 1) + "\"}");
        assertEquals(200, again.statusCode());
        String newAdmin = cookieOf(again);

        cookie = personCookie;
        assertEquals(401, get("/api/me/view").statusCode());
        cookie = newAdmin;
        assertEquals(200, get("/api/admin/view").statusCode());
    }

    // ------------------------------------------------------------ 严格 JSON

    @Test
    @DisplayName("小数/指数/溢出/布尔毫秒一律 400 且不推进")
    void rejectsNonIntegerMillis() throws Exception {
        login();
        String position = position();
        assertEquals(400, post("/api/admin/advance",
                "{\"expectedPosition\":\"" + position + "\",\"targetMillis\":1000.5}").statusCode());
        assertEquals(400, post("/api/admin/advance",
                "{\"expectedPosition\":\"" + position + "\",\"targetMillis\":\"1000.5\"}")
                .statusCode());
        assertEquals(400, post("/api/admin/advance",
                "{\"expectedPosition\":\"" + position + "\",\"targetMillis\":1e3}").statusCode());
        assertEquals(400, post("/api/admin/advance",
                "{\"expectedPosition\":\"" + position
                        + "\",\"targetMillis\":\"9223372036854775808\"}").statusCode());
        assertEquals(400, post("/api/admin/advance",
                "{\"expectedPosition\":\"" + position + "\",\"targetMillis\":true}").statusCode());
        assertEquals(position, position(), "失败请求不得推进世界");
    }

    @Test
    @DisplayName("null / 非对象 / 未知字段 / 重复键 / 尾随内容全部 400")
    void rejectsMalformedBodies() throws Exception {
        login();
        String position = position();
        assertEquals(400, post("/api/admin/advance", "null").statusCode());
        assertEquals(400, post("/api/admin/advance", "[]").statusCode());
        assertEquals(400, post("/api/admin/advance", "").statusCode());
        assertEquals(400, post("/api/admin/advance",
                "{\"expectedPosition\":\"" + position + "\",\"targetMillis\":\"1000\",\"x\":1}")
                .statusCode());
        assertEquals(400, post("/api/admin/advance",
                "{\"expectedPosition\":\"" + position + "\",\"targetMillis\":\"1000\"} {}")
                .statusCode());
        assertEquals(400, post("/api/admin/keys/key-1/reveal",
                "{\"confirm\":false,\"confirm\":true}").statusCode());
        assertEquals(400, post("/api/admin/keys/key-1/reveal",
                "{\"confirm\":\"true\"}").statusCode());
        assertEquals(400, post("/api/admin/keys/key-1/reveal",
                "{\"confirm\":true,\"extra\":1}").statusCode());
        assertEquals(415, request("POST", "/api/admin/advance",
                "{\"expectedPosition\":\"1\",\"targetMillis\":\"1\"}",
                "application/json-evil").statusCode());
        assertEquals(200, request("POST", "/api/admin/advance",
                "{\"expectedPosition\":\"" + position + "\",\"targetMillis\":\"1000\"}",
                "application/json; charset=utf-8").statusCode());
    }

    @Test
    @DisplayName("advance 成功与冲突都返回字符串 position")
    void advanceAndConflict() throws Exception {
        login();
        String position = position();
        HttpResponse<String> ok = post("/api/admin/advance",
                "{\"expectedPosition\":\"" + position + "\",\"targetMillis\":\"1000\"}");
        assertEquals(200, ok.statusCode());
        assertTrue(body(ok).path("position").isTextual());

        HttpResponse<String> conflict = post("/api/admin/advance",
                "{\"expectedPosition\":\"0\",\"targetMillis\":\"2000\"}");
        assertEquals(409, conflict.statusCode());
        assertTrue(body(conflict).path("position").isTextual());
        assertEquals(position(), body(conflict).path("position").asText());
    }

    // ------------------------------------------------------------ 视图与价格语义

    @Test
    @DisplayName("管理视图金额/位置/时点都是字符串，runId 来自真实世界")
    void adminViewTypes() throws Exception {
        login();
        JsonNode view = body(get("/api/admin/view"));
        assertTrue(view.path("position").isTextual());
        assertTrue(view.path("timeMillis").isTextual());
        assertTrue(view.path("accounts").get(0).path("cash").isTextual());
        assertEquals(WorldWebScenario.RUN_ID, view.path("runId").asText());
        assertFalse(view.path("runId").asText().isBlank());
    }

    @Test
    @DisplayName("无成交时 lastPrice 为 null、数量为 \"0\"，referencePrice 是真实开局参考")
    void personMarketWithoutTrade() throws Exception {
        login();
        HttpResponse<String> switched = post("/api/session/person", "{\"personId\":\"investor\"}");
        cookie = cookieOf(switched);

        JsonNode market = body(get("/api/me/view")).path("market").get(0);
        assertTrue(market.path("lastPrice").isNull(), "无成交不得用参考价冒充成交价");
        assertEquals("0", market.path("lastTradeQuantity").asText());
        assertEquals("-1", market.path("lastTradeAtMillis").asText());
        assertTrue(market.path("referencePrice").isTextual());
        assertEquals("10.00", market.path("referencePrice").asText());
        assertEquals("OPENING_REFERENCE", market.path("referenceSource").asText());
        assertFalse(market.path("referenceAgeMillis").isNull());
        assertTrue(market.path("referencePrice").asText().matches("\\d+\\.\\d{2}"));
    }

    @Test
    @DisplayName("个人视图时间字符串、无管理员字段、不能读密钥")
    void personViewTrimmed() throws Exception {
        login();
        cookie = cookieOf(post("/api/session/person", "{\"personId\":\"investor\"}"));
        JsonNode view = body(get("/api/me/view"));
        assertTrue(view.path("timeMillis").isTextual());
        assertEquals("investor", view.path("personPartyId").asText());
        assertEquals(403, get("/api/admin/view").statusCode());
        assertEquals(403, get("/api/admin/keys").statusCode());
        assertTrue(view.path("providerKind").isMissingNode());
        assertTrue(view.path("pending").isMissingNode());
        assertFalse(view.toString().contains("sk-"));
    }

    @Test
    @DisplayName("管理员切到不可见人物 404，未知路径 404，方法不对 405")
    void guards() throws Exception {
        login();
        assertEquals(404, post("/api/session/person", "{\"personId\":\"nobody\"}").statusCode());
        assertEquals(404, get("/api/nope").statusCode());
        assertEquals(405, request("PUT", "/api/admin/view", null, "application/json").statusCode());
    }

    // ------------------------------------------------------------ writer 语义

    @Test
    @DisplayName("已接受任务不因调用方中断而提前报告失败，且只执行一次")
    void interruptDoesNotFakeFailure() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicInteger effect = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                host.call(() -> {
                    running.countDown();
                    release.await();
                    effect.incrementAndGet();
                    return null;
                });
            } catch (Throwable failure) {
                error.set(failure);
            } finally {
                returned.countDown();
            }
        });
        caller.start();
        assertTrue(running.await(5, TimeUnit.SECONDS));
        caller.interrupt();
        boolean early = returned.await(150, TimeUnit.MILLISECONDS);
        release.countDown();
        caller.join(5_000L);
        assertFalse(early, "中断后不得提前返回");
        assertNull(error.get());
        assertEquals(1, effect.get());
        assertFalse(caller.isAlive());
    }

    // ------------------------------------------------------------ 工具

    private void login() throws Exception {
        HttpResponse<String> response = post("/api/session",
                "{\"bootstrap\":\"" + BOOTSTRAP + "\"}");
        if (response.statusCode() != 200) {
            List<String> tokens = consoleTokens();
            response = post("/api/session",
                    "{\"bootstrap\":\"" + tokens.get(tokens.size() - 1) + "\"}");
        }
        assertEquals(200, response.statusCode());
        cookie = cookieOf(response);
    }

    private List<String> consoleTokens() {
        List<String> tokens = new ArrayList<>();
        for (String line : consoleLines) {
            int at = line.indexOf('：');
            tokens.add(at >= 0 ? line.substring(at + 1) : line);
        }
        return tokens;
    }

    private String position() throws Exception {
        return body(get("/api/admin/view")).path("position").asText();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request("GET", path, null, null);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return request("POST", path, body, "application/json");
    }

    private HttpResponse<String> request(String method, String path, String body, String type)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if ("POST".equals(method)) {
            builder.header("Origin", base)
                    .header("X-World-Request", "1")
                    .header("Content-Type", type == null ? "application/json" : type)
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else if ("PUT".equals(method)) {
            builder.header("Origin", base)
                    .header("X-World-Request", "1")
                    .PUT(HttpRequest.BodyPublishers.ofString(""));
        } else {
            builder.GET();
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private static String cookieOf(HttpResponse<String> response) {
        String raw = response.headers().firstValue("set-cookie")
                .orElseThrow(() -> new AssertionError("缺少 Set-Cookie"));
        return raw.split(";", 2)[0];
    }
}
