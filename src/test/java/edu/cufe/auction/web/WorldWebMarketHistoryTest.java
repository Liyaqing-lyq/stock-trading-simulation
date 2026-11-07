package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实撮合、HTTP及原日志重开检验市场图表数据。 */
class WorldWebMarketHistoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
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
        if (server != null) server.close();
        if (host != null) host.close();
        if (routes != null) routes.close();
    }

    @Test
    void actualTradesAndGapRemainExactAfterJournalRecovery() throws Exception {
        start(); login();
        host.call(() -> {
            order("sell1", 1000, "investor", "INV", Side.SELL, "10.00", 10);
            order("buy1", 2000, "fund-a", "ACC-FUND", Side.BUY, "10.00", 10);
            order("sell2", 181000, "investor", "INV", Side.SELL, "11.00", 5);
            order("buy2", 182000, "fund-a", "ACC-FUND", Side.BUY, "11.00", 5);
            world.advanceTo(182000);
            return null;
        });
        var before = host.adminSnapshot();
        assertEquals(2, host.call(() -> world.snapshot().exchange().orElseThrow().trades().size()));
        var response = request("{\"symbol\":\"CUFE1\",\"period\":\"minute\"}", base, true);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
        var minute = JSON.readTree(response.body());
        assertEquals(Long.toString(before.position()), minute.path("position").asText());
        assertEquals("182000", minute.path("timeMillis").asText());
        var bars = minute.path("bars");
        assertEquals(2, bars.size());
        assertEquals("10.00", bars.get(0).path("open").asText());
        assertEquals("10", bars.get(0).path("volume").asText());
        assertEquals("100.00", bars.get(0).path("amount").asText());
        assertTrue(bars.get(0).path("complete").asBoolean());
        assertEquals("11.00", bars.get(1).path("close").asText());
        assertEquals("55.00", bars.get(1).path("amount").asText());
        assertFalse(bars.get(1).path("complete").asBoolean());
        assertEquals("60000", minute.path("gaps").get(0).path("fromMillis").asText());
        assertEquals("180000", minute.path("gaps").get(0).path("toMillis").asText());
        var day = query("day");
        assertEquals(1, day.path("bars").size());
        assertEquals("15", day.path("bars").get(0).path("volume").asText());
        assertEquals("155.00", day.path("bars").get(0).path("amount").asText());
        assertEquals(before, host.adminSnapshot(), "图表请求不得推进或产生交易");
        close(); cookie = null;
        openServer(); login();
        assertEquals(minute, query("minute"), "重开同一日志不能重复开局或丢失成交");
    }

    @Test
    void marketQueriesEnforceIdentityOriginAndStrictFields() throws Exception {
        start();
        String valid = "{\"symbol\":\"\",\"period\":\"minute\"}";
        assertEquals(401, request(valid, base, true).statusCode());
        login();
        assertEquals(403, request(valid, "http://example.invalid", true).statusCode());
        assertEquals(403, request(valid, base, false).statusCode());
        for (String body : List.of("{}", "null", "[]", "{\"symbol\":null,\"period\":\"minute\"}",
                "{\"symbol\":\"\",\"period\":4}", "{\"symbol\":\"missing\",\"period\":\"minute\"}",
                "{\"symbol\":\"\",\"period\":\"year\"}", "{\"symbol\":\"\",\"period\":\"day\",\"cut\":1}",
                "{\"symbol\":\"\",\"period\":\"day\",\"period\":\"week\"}", valid + "{}")) {
            assertEquals(400, request(body, base, true).statusCode(), body);
        }
        var switched = post("/api/session/person", "{\"personId\":\"alice\"}", base, true);
        assertEquals(200, switched.statusCode());
        cookie = switched.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        assertEquals(403, request(valid, base, true).statusCode());
    }

    private void order(String id, long due, String owner, String account, Side side, String price, long quantity)
            throws Exception {
        world.scheduleOrder(id, id + "-task", due, owner, account, WorldWebScenario.SYMBOL,
                side, OrderType.LIMIT, new BigDecimal(price), quantity);
    }

    private JsonNode query(String period) throws Exception {
        var response = request(JSON.writeValueAsString(java.util.Map.of("symbol", "CUFE1", "period", period)), base, true);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private void start() throws Exception {
        Path root = Path.of("target", "web-market-history-tests");
        Files.createDirectories(root);
        directory = Files.createTempDirectory(root, "case-");
        Files.writeString(directory.resolve("dummy-keys.txt"),
                "sk-market-fixture-00001\nsk-market-fixture-00002\nsk-market-fixture-00003\nsk-market-fixture-00004\nsk-market-fixture-00005");
        openServer();
    }

    private void openServer() throws Exception {
        routes = SceneRoutes.open(directory);
        host = new WorldWebHost(() -> {
            var opened = WorldWebScenario.open(directory.resolve("world.journal"));
            world = opened.runtime(); return opened;
        });
        host.adminSnapshot();
        server = new WorldWebServer(host, KeySlots.load(directory.resolve("dummy-keys.txt")), routes,
                0, "market-fixture", null, ignored -> { });
        server.start(); base = server.baseUrl();
    }

    private void login() throws Exception {
        var response = post("/api/session", "{\"bootstrap\":\"market-fixture\"}", base, true);
        assertEquals(200, response.statusCode());
        cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> request(String body, String origin, boolean marker) throws Exception {
        return post("/api/admin/market-history", body, origin, marker);
    }

    private HttpResponse<String> post(String path, String body, String origin, boolean marker) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Origin", origin);
        if (cookie != null) builder.header("Cookie", cookie);
        if (marker) builder.header("X-World-Request", "1");
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
