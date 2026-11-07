package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6b：管理端表现历史 HTTP 契约（真实服务、真实世界、真实会话）。
 *
 * <p>覆盖施工单 §Web 部分：401/403/400 边界、严格字段与精度、no-store、当前 cut 与所选区间终点
 * 分离、400 帧允许 / 401 帧拒绝、真实资本流、真实成交带来的规模与指数。</p>
 */
@DisplayName("Web：表现历史查询（HTTP）")
class WorldWebPerformanceHistoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/api/admin/performance-history";
    private static final String OWNER = "investor";
    private static final String SCENARIO_OWNER_ACCOUNT = "INV";

    private final HttpClient http = HttpClient.newHttpClient();
    private WorldWebHost host;
    private WorldWebServer server;
    private SceneRoutes routes;
    private WorldRuntime world;
    private Path directory;
    private String base;
    private String cookie;

    @AfterEach
    void close() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (host != null) {
            host.close();
            host = null;
        }
        if (routes != null) {
            routes.close();
            routes = null;
        }
    }

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实注资与成交后：规模、指数、回撤与资本流都来自同一确认区间")
    void historyReportsRealScaleIndexDrawdownAndFlows() throws Exception {
        start();
        login();
        host.call(() -> {
            world.advanceTo(1_000L);
            world.scheduleCapitalFlow("c-in", "t-in", 1_010L, "customer", "CUS",
                    SCENARIO_OWNER_ACCOUNT, new BigDecimal("500.00"), 1, "追加资本");
            world.advanceTo(1_010L);
            order("p-sell", 1_020L, Side.SELL, "12.00", 20L);
            order("p-buy", 1_021L, Side.BUY, "12.00", 20L);
            world.advanceTo(1_021L);
            return null;
        });
        var before = host.adminSnapshot();
        assertEquals(1, host.call(() -> world.snapshot().exchange().orElseThrow().trades().size()),
                "20 股在 12 元一次性成交");

        var response = post(PATH, body(OWNER, "1", before.position()), true);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());

        JsonNode view = JSON.readTree(response.body());
        assertEquals(host.runId(), view.path("runId").asText());
        assertEquals(Long.toString(before.position()), view.path("position").asText());
        assertEquals(Long.toString(before.timeMillis()), view.path("timeMillis").asText());
        assertEquals(OWNER, view.path("ownerId").asText());
        assertFalse(view.path("ownerName").asText().isBlank());
        assertEquals("1", view.path("startPosition").asText());
        assertEquals(Long.toString(before.position()), view.path("endPosition").asText());
        assertEquals(Long.toString(before.position()), view.path("totalPoints").asText());
        assertEquals("400", view.path("framesLimit").asText());
        assertEquals("COMPLETE", view.path("performance").path("status").asText());
        assertEquals("0.133333333333", view.path("performance").path("returnRate").asText(),
                "初始 1000 + 100 股 × 10；注资 500 剥离；20 股在 12 元成交后 740 + 80 × 12 = 1700，1700/1500 − 1");
        assertEquals("1.133333333333", view.path("performance").path("index").asText());
        assertEquals("0.000000000000",
                view.path("performance").path("maxDrawdown").asText());

        JsonNode points = view.path("points");
        assertTrue(points.size() > 2);
        JsonNode first = points.get(0);
        assertEquals("1", first.path("position").asText());
        assertEquals("1.000000000000", first.path("index").asText());
        assertEquals("0.000000000000", first.path("drawdown").asText());
        assertEquals("1000.00", first.path("navAfterFlow").asText(), "初始净资产按字符串精确给出");
        long previousPosition = 0;
        for (JsonNode point : points) {
            long position = Long.parseLong(point.path("position").asText());
            assertTrue(position > previousPosition, "位置必须严格递增");
            previousPosition = position;
            assertFalse(point.path("timeMillis").asText().isBlank(), "每点都要带模拟时刻");
        }
        assertEquals(view.path("flows").path("total").asText(), "1");
        JsonNode flow = view.path("flows").path("rows").get(0);
        assertEquals("CAPITAL_CONTRIBUTION", flow.path("kind").asText());
        assertEquals("500.00", flow.path("amount").asText());
        assertEquals("追加资本", flow.path("evidenceRef").asText());
        assertEquals(before, host.adminSnapshot(), "查询不得推进世界或产生成交");
    }

    @Test
    @DisplayName("所选区间终点不是实时世界时间：当前 cut 更大时两个字段分开")
    void intervalEndIsNotTheLiveCut() throws Exception {
        start();
        login();
        host.call(() -> {
            world.advanceTo(1_000L);
            return null;
        });
        long end = host.adminSnapshot().position();
        host.call(() -> {
            world.advanceTo(2_000L);
            order("later-sell", 2_010L, Side.SELL, "10.00", 5L);
            order("later-buy", 2_011L, Side.BUY, "10.00", 5L);
            world.advanceTo(2_011L);
            return null;
        });
        var live = host.adminSnapshot();
        assertTrue(live.position() > end);

        var response = post(PATH, body(OWNER, "1", end), true);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode view = JSON.readTree(response.body());
        assertEquals(Long.toString(live.position()), view.path("position").asText(),
                "position 是实时截止");
        assertEquals(Long.toString(live.timeMillis()), view.path("timeMillis").asText());
        assertEquals(Long.toString(end), view.path("endPosition").asText(),
                "endPosition 是所选区间终点，不得被实时时间顶替");
        assertNotEquals(view.path("timeMillis").asText(),
                view.path("endTimeMillis").asText());
        assertEquals(Long.toString(end), view.path("totalPoints").asText());
        assertEquals(end, (long) view.path("points").size(), "起点为 1 时点数等于终点位置");
    }

    @Test
    @DisplayName("单帧区间：200 但期间 NO_PERIOD，指数/回撤为空而不是 0")
    void singleFrameHasNoPeriod() throws Exception {
        start();
        login();
        host.call(() -> {
            world.advanceTo(1_000L);
            return null;
        });
        long cut = host.adminSnapshot().position();
        var response = post(PATH, body(OWNER, Long.toString(cut), Long.toString(cut)), true);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode view = JSON.readTree(response.body());
        assertEquals("1", view.path("totalPoints").asText());
        assertEquals("NO_PERIOD", view.path("performance").path("status").asText());
        assertTrue(view.path("performance").path("index").isNull(),
                "不足两点时不得输出指数 1，更不能填 0");
        assertTrue(view.path("performance").path("returnRate").isNull());
        assertTrue(view.path("performance").path("maxDrawdown").isNull());
        JsonNode point = view.path("points").get(0);
        assertTrue(point.path("index").isNull());
        assertTrue(point.path("drawdown").isNull());
        assertEquals(view.path("startPosition").asText(), view.path("endPosition").asText());
        assertEquals(view.path("startTimeMillis").asText(), view.path("endTimeMillis").asText());
    }

    // ------------------------------------------------------------------
    // 身份与传输边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("401 未登录；403 跨源、缺请求标记、人物会话；400 严格字段")
    void identityOriginAndStrictFieldsAreEnforced() throws Exception {
        start();
        String valid = body(OWNER, "1", "1");
        assertEquals(401, post(PATH, valid, true).statusCode());
        login();
        assertEquals(403, post(PATH, valid, "http://example.invalid", true).statusCode());
        assertEquals(403, post(PATH, valid, base, false).statusCode());

        for (String bad : List.of(
                "{}",
                "null",
                "[]",
                "{\"ownerId\":null,\"startPosition\":\"1\",\"endPosition\":\"1\"}",
                "{\"ownerId\":\"investor\",\"startPosition\":1.0,\"endPosition\":\"1\"}",
                "{\"ownerId\":\"investor\",\"startPosition\":\"1.0\",\"endPosition\":\"1\"}",
                "{\"ownerId\":\"investor\",\"startPosition\":\"1e3\",\"endPosition\":\"1\"}",
                "{\"ownerId\":\"investor\",\"startPosition\":\"99999999999999999999999\",\"endPosition\":\"1\"}",
                "{\"ownerId\":\"investor\",\"startPosition\":\"1\",\"endPosition\":\"1\",\"cut\":1}",
                "{\"ownerId\":\"investor\",\"ownerId\":\"investor\",\"startPosition\":\"1\",\"endPosition\":\"1\"}",
                valid + "{}")) {
            assertEquals(400, post(PATH, bad, true).statusCode(), bad);
        }

        var switched = post("/api/session/person", "{\"personId\":\"alice\"}", true);
        assertEquals(200, switched.statusCode());
        cookie = switched.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        assertEquals(403, post(PATH, valid, true).statusCode(), "人物会话不可调用管理查询");
    }

    @Test
    @DisplayName("未知主体、倒置区间、未确认终点、起点 0 都是 400 且消息可读")
    void invalidRangesAreRejectedWithAReason() throws Exception {
        start();
        login();
        host.call(() -> {
            world.advanceTo(1_000L);
            return null;
        });
        long position = host.adminSnapshot().position();

        assertEquals(400, post(PATH, body("nobody", "1", position), true).statusCode());
        var unknown = post(PATH, body("nobody", "1", position), true);
        assertTrue(unknown.body().contains("未知财产主体"), unknown.body());

        var inverted = post(PATH, body(OWNER, Long.toString(position), "1"), true);
        assertEquals(400, inverted.statusCode());
        assertTrue(inverted.body().contains("已确认位置内"), inverted.body());

        assertEquals(400, post(PATH, body(OWNER, "1", position + 5), true).statusCode());
        assertEquals(400, post(PATH, body(OWNER, "0", position), true).statusCode());
        assertEquals(400, post(PATH, body(" ", Long.toString(position),
                Long.toString(position)), true).statusCode());
    }

    // ------------------------------------------------------------------
    // 帧数上限：400 允许、401 拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("400 帧允许、401 帧拒绝（不静默截断、不改起点）")
    void frameLimitIsExactlyFourHundred() throws Exception {
        start();
        login();
        host.call(() -> {
            for (int step = 0; step < 405; step++) {
                world.advanceTo(1_000L + step * 1_000L);
            }
            return null;
        });
        long cut = host.adminSnapshot().position();
        assertTrue(cut > 401, "需要足够帧才能同时验证 400 与 401");

        long start400 = cut - 399;
        var ok = post(PATH, body(OWNER, Long.toString(start400), Long.toString(cut)), true);
        assertEquals(200, ok.statusCode(), ok.body());
        JsonNode view = JSON.readTree(ok.body());
        assertEquals("400", view.path("totalPoints").asText());
        assertEquals(Long.toString(start400), view.path("points").get(0).path("position").asText(),
                "响应必须报告实际起点，不能冒称全期");
        assertEquals(Long.toString(cut),
                view.path("points").get(view.path("points").size() - 1).path("position").asText());

        long start401 = cut - 400;
        var rejected = post(PATH, body(OWNER, Long.toString(start401), Long.toString(cut)), true);
        assertEquals(400, rejected.statusCode(), rejected.body());
        assertTrue(rejected.body().contains("400"), rejected.body());
        assertTrue(rejected.body().contains("超过上限"), rejected.body());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private String body(String ownerId, String start, long end) {
        return body(ownerId, start, Long.toString(end));
    }

    private String body(String ownerId, String start, String end) {
        return "{\"ownerId\":\"" + ownerId + "\",\"startPosition\":\"" + start
                + "\",\"endPosition\":\"" + end + "\"}";
    }

    private void order(String id, long due, Side side, String price, long quantity)
            throws java.io.IOException {
        String owner = side == Side.SELL ? OWNER : "customer";
        String account = side == Side.SELL ? SCENARIO_OWNER_ACCOUNT : "CUS";
        world.scheduleOrder(id, id + "-task", due, owner, account, WorldWebScenario.SYMBOL,
                side, OrderType.LIMIT, new BigDecimal(price), quantity);
    }

    private void start() throws Exception {
        Path root = Path.of("build", "web-performance-history-tests");
        Files.createDirectories(root);
        directory = Files.createDirectory(root.resolve("case-" + System.nanoTime()));
        Files.writeString(directory.resolve("dummy-keys.txt"),
                "sk-performance-fixture-00001\nsk-performance-fixture-00002\n"
                        + "sk-performance-fixture-00003\nsk-performance-fixture-00004\n"
                        + "sk-performance-fixture-00005");
        routes = SceneRoutes.open(directory);
        host = new WorldWebHost(() -> {
            var opened = WorldWebScenario.open(directory.resolve("world.journal"));
            world = opened.runtime();
            return opened;
        });
        host.adminSnapshot();
        server = new WorldWebServer(host, KeySlots.load(directory.resolve("dummy-keys.txt")),
                routes, 0, "performance-fixture", null, ignored -> { });
        server.start();
        base = server.baseUrl();
    }

    private void login() throws Exception {
        var response = post("/api/session", "{\"bootstrap\":\"performance-fixture\"}", true);
        assertEquals(200, response.statusCode());
        cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> post(String path, String payload, boolean marker)
            throws Exception {
        return post(path, payload, base, marker);
    }

    private HttpResponse<String> post(String path, String payload, String origin, boolean marker)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(java.net.URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (marker) {
            builder.header("X-World-Request", "1");
        }
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
