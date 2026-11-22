package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自主档行情页的真实 Java 撮合回归：逐日成交与委托必须来自同一 snapshot.exchange，
 * 空白日、延续价和整日交界不得被参考价或零值填充。
 */
class WorldOverviewMarketTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    @Test
    @SuppressWarnings("unchecked")
    void autonomousWorldShowsRealTradesOrdersAndBlankDaysFromOneSnapshot() throws Exception {
        Path journal = dir.resolve("autonomous-market.journal");
        try (WorldRuntime world = ComposedIndustryScenario.openAutonomous(journal)) {
            assertTrue(world.profile().manifest().dataVersions().containsKey("autonomy-driver"),
                    "夹具必须真的是带 autonomy-driver 的自主档");

            // 两方向令：卖方 10 股挂 9.50，买方 5 股以 9.50 吃单，成交 5 股；卖方剩余 5 股随后撤销。
            world.scheduleOrder("mkt-sell", "mkt-sell-task", 60_000L, "mfg-tech-founder",
                    "MFG-TECH-FOUNDER-SEC", "MT1", Side.SELL, OrderType.LIMIT,
                    new BigDecimal("9.50"), 10L);
            world.scheduleOrder("mkt-buy", "mkt-buy-task", 120_000L, "auto-oem",
                    "EXT-CUST-AUTO", "MT1", Side.BUY, OrderType.LIMIT,
                    new BigDecimal("9.50"), 5L);
            world.advanceTo(120_000L);

            var exchange = world.snapshot().exchange().orElseThrow();
            assertEquals(1, exchange.trades().size(), "真实撮合必须只产生一笔成交");
            var trade = exchange.trades().get(0);
            assertEquals("MT1", trade.symbol());
            assertEquals(5L, trade.quantity());
            assertEquals(0, new BigDecimal("9.50").compareTo(trade.price()));
            var restingSell = exchange.orders().stream()
                    .filter(o -> "mkt-sell".equals(o.commandId())).findFirst().orElseThrow();
            assertEquals(5L, restingSell.filled());
            assertEquals(10L, restingSell.quantity());
            assertEquals("ACTIVE", restingSell.status().name());

            world.scheduleCancelOrder("mkt-cancel", "mkt-cancel-task", 180_000L,
                    "mfg-tech-founder", restingSell.orderId());
            world.advanceTo(180_000L);
            exchange = world.snapshot().exchange().orElseThrow();
            assertEquals("CANCELLED", exchange.orders().stream()
                    .filter(o -> "mkt-sell".equals(o.commandId())).findFirst().orElseThrow()
                    .status().name(), "剩余 5 股必须真实撤销");

            // 整日交界：世界时点恰为 86,400,000，撮合日 = floor(t/DAY)+1 = 第 2 日。
            world.advanceTo(86_400_000L);
            var view = WorldOverviewView.of(world);
            assertTrue((Boolean) view.get("available"));
            assertFalse(view.containsKey("connected"), "自主档不冒充 connected 规则");

            var details = (Map<String, Object>) view.get("liveDetails");
            assertNotNull(details, "自主档必须从同一快照生成行情明细");
            var tech = (Map<String, Object>) details.get("mfg-tech");
            var days = (List<Map<String, Object>>) tech.get("days");
            assertEquals(2, days.size(), "整日交界必须进入第 2 个撮合日");
            var first = days.get(0);
            assertEquals("9.50", first.get("close"), "当日末笔价就是成交价");
            assertEquals("9.50", first.get("lastPrice"));
            assertEquals(1L, ((Number) first.get("trades")).longValue());
            assertEquals(5L, ((Number) first.get("volume")).longValue());
            assertEquals("47.50", first.get("turnover"), "5 股 × 9.50 = 47.50");
            assertNull(first.get("cash"), "未知逐日现金不得补零");
            var second = days.get(1);
            assertNull(second.get("close"), "无成交日不得把延续价写成收盘价");
            assertEquals("9.50", second.get("lastPrice"), "延续价只用于连线");
            assertEquals(0L, ((Number) second.get("trades")).longValue());
            assertEquals(0L, ((Number) second.get("volume")).longValue());
            assertEquals("0.00", second.get("turnover"));
            assertNull(second.get("cash"));

            // 另一只股票没有任何成交：曲线必须保持空白，参考价不得变成收盘价。
            var untouched = (Map<String, Object>) details.get("mfg-struct");
            for (var row : (List<Map<String, Object>>) untouched.get("days")) {
                assertNull(row.get("close"));
                assertNull(row.get("lastPrice"));
                assertEquals(0L, ((Number) row.get("trades")).longValue());
            }

            var market = (Map<String, Object>) view.get("marketOrders");
            assertNotNull(market, "自主档必须从同一快照暴露委托");
            var techOrders = (Map<String, Object>) market.get("mfg-tech");
            assertEquals(2, ((Number) techOrders.get("total")).intValue());
            var rows = (List<Map<String, Object>>) techOrders.get("rows");
            assertEquals(2, rows.size());
            var cancelled = rows.stream().filter(r -> "CANCELLED".equals(r.get("status")))
                    .findFirst().orElseThrow();
            assertEquals("SELL", cancelled.get("side"));
            assertEquals("9.50", cancelled.get("price"));
            assertEquals(10L, ((Number) cancelled.get("quantity")).longValue());
            assertEquals(5L, ((Number) cancelled.get("filled")).longValue());
            var filled = rows.stream().filter(r -> "FILLED".equals(r.get("status")))
                    .findFirst().orElseThrow();
            assertEquals("BUY", filled.get("side"));
            assertEquals(5L, ((Number) filled.get("quantity")).longValue());

            // 管理员投影必须能安全序列化 null 市价；角色入口不会读到这个对象。
            var json = JSON.valueToTree(view);
            assertEquals("9.50", json.path("liveDetails").path("mfg-tech").path("days").get(0)
                    .path("close").asText());
            assertEquals("47.50", json.path("liveDetails").path("mfg-tech").path("days").get(0)
                    .path("turnover").asText());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void marketOrderWithoutLimitPriceIsSafeOnTheMarketPage() throws Exception {
        Path journal = dir.resolve("autonomous-market-order.journal");
        try (WorldRuntime world = ComposedIndustryScenario.openAutonomous(journal)) {
            world.scheduleOrder("mo-sell", "mo-sell-task", 60_000L, "mfg-tech-founder",
                    "MFG-TECH-FOUNDER-SEC", "MT1", Side.SELL, OrderType.LIMIT,
                    new BigDecimal("9.50"), 3L);
            world.scheduleOrder("mo-buy", "mo-buy-task", 120_000L, "auto-oem",
                    "EXT-CUST-AUTO", "MT1", Side.BUY, OrderType.MARKET, null, 5L);
            world.advanceTo(120_000L);

            var view = WorldOverviewView.of(world);
            var market = (Map<String, Object>) view.get("marketOrders");
            var techOrders = (Map<String, Object>) market.get("mfg-tech");
            var rows = (List<Map<String, Object>>) techOrders.get("rows");
            var marketRow = rows.stream().filter(r -> "MARKET".equals(r.get("type")))
                    .findFirst().orElseThrow();
            assertNull(marketRow.get("price"), "市价单没有限价，必须是 null 而不是空串或 0");
            assertEquals(5L, ((Number) marketRow.get("quantity")).longValue());
            assertEquals(3L, ((Number) marketRow.get("filled")).longValue());
            assertEquals("CANCELLED", marketRow.get("status"));

            var json = JSON.valueToTree(view);
            var serialized = json.path("marketOrders").path("mfg-tech").path("rows");
            boolean found = false;
            for (var node : serialized) {
                if ("MARKET".equals(node.path("type").asText())) {
                    assertTrue(node.path("price").isNull(), "JSON 中的市价必须保持 null");
                    found = true;
                }
            }
            assertTrue(found);
        }
    }

    @Test
    void adminHttpOverviewPublishesRealTurnoverAndPersonSessionIsForbidden() throws Exception {
        Path journal = dir.resolve("http-autonomous-market.journal");
        var holder = new AtomicReference<WorldRuntime>();
        try (var host = new WorldWebHost(() -> {
            var world = ComposedIndustryScenario.openAutonomous(journal);
            holder.set(world);
            return new WorldWebHost.Opened(world, world.profile().manifest().runId());
        })) {
            host.adminSnapshot();
            host.call(() -> {
                WorldRuntime world = holder.get();
                world.scheduleOrder("http-sell", "http-sell-task", 60_000L, "mfg-tech-founder",
                        "MFG-TECH-FOUNDER-SEC", "MT1", Side.SELL, OrderType.LIMIT,
                        new BigDecimal("9.50"), 10L);
                world.scheduleOrder("http-buy", "http-buy-task", 120_000L, "auto-oem",
                        "EXT-CUST-AUTO", "MT1", Side.BUY, OrderType.LIMIT,
                        new BigDecimal("9.50"), 5L);
                world.advanceTo(120_000L);
                long sell = world.snapshot().exchange().orElseThrow().orders().stream()
                        .filter(o -> "http-sell".equals(o.commandId())).findFirst().orElseThrow()
                        .orderId();
                world.scheduleCancelOrder("http-cancel", "http-cancel-task", 180_000L,
                        "mfg-tech-founder", sell);
                world.advanceTo(180_000L);
                return null;
            });

            Path keys = dir.resolve("http-market-keys.txt");
            var offlineSlots = new java.util.ArrayList<String>();
            for (int slot = 1; slot <= KeySlots.SLOT_COUNT; slot++) {
                offlineSlots.add("sk-offline-test-slot-" + slot);
            }
            Files.write(keys, offlineSlots);
            try (var routes = SceneRoutes.open(dir);
                 var server = new WorldWebServer(host, KeySlots.load(keys), routes, 0,
                         "market-http-bootstrap", null, ignored -> { })) {
                server.enableLocalAccess();
                server.start();
                var login = post(server, "/api/session/local", "{}", null);
                assertEquals(200, login.statusCode(), login.body());
                String cookie = login.headers().firstValue("set-cookie").orElseThrow()
                        .split(";", 2)[0];

                var response = get(server, "/api/admin/world", cookie);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode view = JSON.readTree(response.body());
                assertTrue(view.path("available").asBoolean());
                assertTrue(view.path("connected").isMissingNode(), "自主档不冒充 connected 规则");
                assertEquals("47.50", view.path("liveDetails").path("mfg-tech")
                        .path("days").get(0).path("turnover").asText(),
                        "管理员行情页必须显示真实的 5 × 9.50");
                assertEquals("9.50", view.path("liveDetails").path("mfg-tech")
                        .path("days").get(0).path("close").asText());
                assertEquals(2, view.path("marketOrders").path("mfg-tech")
                        .path("total").asInt());
                assertEquals("CANCELLED", view.path("marketOrders").path("mfg-tech")
                        .path("rows").get(0).path("status").asText());

                // 角色会话只能看本人入口，不能借这次改动读到管理员行情或别人委托。
                var switched = post(server, "/api/session/person", "{\"personId\":\"alice\"}", cookie);
                assertEquals(200, switched.statusCode(), switched.body());
                String personal = switched.headers().firstValue("set-cookie").orElseThrow()
                        .split(";", 2)[0];
                assertEquals(403, get(server, "/api/admin/world", personal).statusCode());
                var own = get(server, "/api/me/view", personal);
                assertEquals(200, own.statusCode(), own.body());
                JsonNode ownView = JSON.readTree(own.body());
                assertFalse(ownView.has("marketOrders"), "角色入口不得获得他人委托");
                assertFalse(ownView.has("liveDetails"), "角色入口不得获得管理员行情明细");
                assertFalse(ownView.has("names"), "角色入口不得获得全部账户显示名");
            }
        }
    }

    private HttpResponse<String> get(WorldWebServer server, String path, String cookie)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Origin", server.baseUrl()).header("X-World-Request", "1");
        if (cookie != null) builder.header("Cookie", cookie);
        return HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(WorldWebServer server, String path, String body,
                                      String cookie) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Origin", server.baseUrl()).header("X-World-Request", "1")
                .header("Content-Type", "application/json");
        if (cookie != null) builder.header("Cookie", cookie);
        return HTTP.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
