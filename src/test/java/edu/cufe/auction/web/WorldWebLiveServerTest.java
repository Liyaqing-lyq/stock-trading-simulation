package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Web管理协议真实回环；凭据均为dummy，无供应商网络。 */
class WorldWebLiveServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch arrived = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean hold;
    private volatile String authorization;
    private volatile String providerBody;
    private Path work;
    private Path journal;
    private SceneRoutes routes;
    private WorldWebHost host;
    private WorldWebServer server;
    private HttpServer provider;
    private String base;
    private String cookie;

    @AfterEach
    void close() {
        release.countDown();
        if (server != null) server.close();
        if (host != null) host.close();
        if (routes != null) routes.close();
        if (provider != null) provider.stop(0);
    }

    @Test
    void startupReadAndConfigSaveDoNotCallProvider() throws Exception {
        start(true); login();
        var status = live();
        assertEquals("IDLE", status.path("phase").asText());
        assertEquals(0, status.path("decisions").size());
        assertEquals(0, status.path("completionSlots").asInt(-1));
        assertEquals("0", status.path("runBudget").path("usedCalls").asText());
        assertEquals("100", status.path("runBudget").path("capCalls").asText());
        assertEquals(4, status.path("personBudgets").size());
        assertEquals("no-store", get("/api/admin/live").headers().firstValue("cache-control").orElseThrow());
        enable("research");
        assertTrue(json(get("/api/admin/routes")).path("connected").asBoolean());
        assertEquals(0, calls.get());
        assertFalse(get("/api/admin/keys").body().contains("sk-live-web-fixture"));
        assertEquals(status.path("position"), live().path("position"));
    }

    @Test
    void realHttpCompletionPersistsWithoutBrowserRefresh() throws Exception {
        hold = true;
        start(true); login(); enable("research");
        var before = live();
        assertEquals(200, post("/api/admin/live/start", startBody(before, List.of("alice"))).statusCode());
        assertTrue(arrived.await(5, TimeUnit.SECONDS), "必须实际发送回环HTTP");
        // 不再访问Host或Web；只看文件元数据增长，不读取Windows独占锁定的正文。
        long beforeReplyBytes = Files.size(journal);
        release.countDown();
        awaitJournalGrowth(beforeReplyBytes);
        assertEquals(1, calls.get());
        var actual = live();
        assertEquals(1, actual.path("decisions").size());
        var result = actual.path("decisions").get(0);
        assertEquals("WAIT", result.path("outcome").asText());
        assertEquals("UNKNOWN", result.path("costStatus").asText());
        assertEquals("7", result.path("declaredInputTokens").asText());
        assertEquals("3", result.path("declaredOutputTokens").asText());
        assertEquals("key-4", result.path("slotId").asText());
        assertEquals("deepseek-flash", result.path("model").asText());
        assertEquals("Bearer sk-live-web-fixture-00004", authorization);
        var request = JSON.readTree(providerBody);
        assertEquals(128, request.path("max_tokens").asInt());
        assertEquals("deepseek-flash", request.path("model").asText());
        String actualInput = request.path("messages").get(1).path("content").asText();
        assertEquals(Integer.toString(actualInput.length()), result.path("inputLength").asText());
        assertEquals(actualInput.substring(0, Math.min(4000, actualInput.length())), result.path("inputPreview").asText());
        assertEquals(actualInput.length() > 4000, result.path("inputTruncated").asBoolean());
        assertFalse(actual.toString().contains("sk-live-web-fixture"));
        assertEquals(0, actual.path("completionSlots").asInt(-1));
        var budget = actual.path("runBudget");
        assertEquals("1", budget.path("usedCalls").asText());
        assertEquals("7", budget.path("usedInputTokens").asText());
        assertEquals("3", budget.path("usedOutputTokens").asText());
        assertEquals("0", budget.path("reservedCalls").asText());
        assertEquals("0", budget.path("reservedInputTokens").asText());
        assertEquals("0", budget.path("reservedOutputTokens").asText());
        var alice = java.util.stream.StreamSupport.stream(actual.path("personBudgets").spliterator(), false)
                .filter(row -> "alice".equals(row.path("personPartyId").asText())).findFirst().orElseThrow();
        assertEquals("1", alice.path("budget").path("usedCalls").asText());
        assertEquals("7", alice.path("budget").path("usedInputTokens").asText());
        assertEquals("25", alice.path("budget").path("capCalls").asText());
        host.close(); // 关闭后才读正文，避免把独占锁误当产品读取失败。
        String persisted = Files.readString(journal, StandardCharsets.ISO_8859_1);
        assertTrue(persisted.contains("DECISION_REPLIED_V1"));
        assertFalse(persisted.contains("sk-live-web-fixture"));
    }

    @Test
    void liveEndpointsRequireAdminAndOrigin() throws Exception {
        start(true);
        assertEquals(401, get("/api/admin/live").statusCode());
        for (String action : List.of("start", "stop", "pause", "resume")) {
            assertEquals(401, post("/api/admin/live/" + action, "{}").statusCode());
        }
        login();
        var foreign = HttpRequest.newBuilder(URI.create(base + "/api/admin/live/start"))
                .header("Cookie", cookie).header("Origin", "http://example.invalid")
                .header("X-World-Request", "1").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403, HTTP.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode());
        cookie = cookieOf(post("/api/session/person", "{\"personId\":\"alice\"}"));
        assertEquals(403, get("/api/admin/live").statusCode());
        for (String action : List.of("start", "stop", "pause", "resume")) {
            assertEquals(403, post("/api/admin/live/" + action, "{}").statusCode());
        }
        assertEquals(0, calls.get());
    }

    @Test
    void invalidOrStaleStartHasNoWorldOrNetworkEffects() throws Exception {
        start(true); login(); enable("research");
        var current = live();
        var good = (ObjectNode) JSON.readTree(startBody(current, List.of("alice")));
        var stalePosition = good.deepCopy(); stalePosition.put("expectedPosition", "0");
        assertEquals(409, post("/api/admin/live/start", stalePosition.toString()).statusCode());
        var staleRevision = good.deepCopy(); staleRevision.put("expectedRevision", "0");
        assertEquals(409, post("/api/admin/live/start", staleRevision.toString()).statusCode());
        var invalidPerson = good.deepCopy(); invalidPerson.putArray("persons").add("investor");
        assertEquals(400, post("/api/admin/live/start", invalidPerson.toString()).statusCode());
        var duplicate = good.deepCopy(); duplicate.putArray("persons").add("alice").add("alice");
        assertEquals(400, post("/api/admin/live/start", duplicate.toString()).statusCode());
        var extra = good.deepCopy(); extra.put("endpoint", "http://example.invalid");
        assertEquals(400, post("/api/admin/live/start", extra.toString()).statusCode());
        var floating = good.deepCopy(); floating.put("expectedPosition", 1.5);
        assertEquals(400, post("/api/admin/live/start", floating.toString()).statusCode());
        assertEquals(400, post("/api/admin/live/start", "{\"expectedPosition\":\"1\",\"expectedPosition\":\"2\"}").statusCode());
        assertEquals(current.path("position"), live().path("position"));
        assertEquals(0, calls.get());
    }

    @Test
    void pauseResumeRequireCurrentPosition() throws Exception {
        start(true); login();
        var current = live();
        assertEquals(409, post("/api/admin/live/pause", "{\"expectedPosition\":\"0\"}").statusCode());
        assertFalse(live().path("paused").asBoolean());
        assertEquals(200, post("/api/admin/live/pause", controlBody(current)).statusCode());
        assertTrue(live().path("paused").asBoolean());
        assertEquals(409, post("/api/admin/live/resume", "{\"expectedPosition\":\"0\"}").statusCode());
        assertTrue(live().path("paused").asBoolean());
        assertEquals(200, post("/api/admin/live/resume", controlBody(live())).statusCode());
        assertFalse(live().path("paused").asBoolean());
        assertEquals(0, calls.get());
    }

    @Test
    void stopKeepsPhysicalRequestButDoesNotDispatchPendingPerson() throws Exception {
        hold = true;
        start(true); login(); enable("trading");
        assertEquals(200, post("/api/admin/live/start", startBody(live(), List.of("bob", "carol"))).statusCode());
        assertTrue(arrived.await(5, TimeUnit.SECONDS));
        var active = live();
        assertEquals(1, active.path("physicalInFlight").asInt());
        assertEquals(1, active.path("pendingPersons").size());
        assertEquals(200, post("/api/admin/live/stop", controlBody(active)).statusCode());
        long beforeReplyBytes = Files.size(journal);
        release.countDown();
        awaitJournalGrowth(beforeReplyBytes);
        var done = live();
        assertEquals(0, done.path("pendingPersons").size());
        assertEquals(1, done.path("decisions").size());
        assertEquals(1, calls.get());
    }

    @Test
    void scriptedEntryRemainsAvailableAndCannotStartNetwork() throws Exception {
        start(false); login();
        var current = live();
        assertEquals("SCRIPTED", current.path("phase").asText());
        assertFalse(json(get("/api/admin/routes")).path("connected").asBoolean());
        assertEquals(409, post("/api/admin/live/start", startBody(current, List.of("alice"))).statusCode());
        assertEquals(current.path("position"), live().path("position"));
        assertEquals(0, calls.get());
    }

    @Test
    void restartRequiresExplicitStartAndCanActuallyContinue() throws Exception {
        hold = true;
        start(true); login(); enable("research");
        assertEquals(200, post("/api/admin/live/start", startBody(live(), List.of("alice"))).statusCode());
        assertTrue(arrived.await(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        server.close(); host.close();
        release.countDown(); hold = false;
        restartHost();
        var recovered = live();
        Thread.sleep(300);
        assertEquals(1, calls.get(), "重启和读取不能自动发请求");
        assertTrue(recovered.path("decisions").toString().contains("OPEN"), "旧尝试须等显式接续");
        assertEquals(200, post("/api/admin/live/start", startBody(recovered, List.of("alice"))).statusCode(),
                "显式接续不能被旧未结算决定的屏障永久挡住");
        awaitWaitDecision();
        assertEquals(2, calls.get(), "第二次显式开始必须实际只发送一次新请求");
        var records = live().path("decisions");
        assertEquals(2, records.size());
        assertEquals(1, java.util.stream.StreamSupport.stream(records.spliterator(), false)
                .filter(record -> "WAIT".equals(record.path("outcome").asText())).count());
        assertEquals(1, java.util.stream.StreamSupport.stream(records.spliterator(), false)
                .filter(record -> "EXPIRED".equals(record.path("status").asText())).count());
    }

    @Test
    void pauseRetainsReceivedCompletionUntilExplicitResume() throws Exception {
        hold = true;
        start(true); login(); enable("research");
        assertEquals(200, post("/api/admin/live/start", startBody(live(), List.of("alice"))).statusCode());
        assertTrue(arrived.await(5, TimeUnit.SECONDS));
        assertEquals(200, post("/api/admin/live/pause", controlBody(live())).statusCode());
        long pausedBytes = Files.size(journal);
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        JsonNode paused = live();
        while (paused.path("physicalInFlight").asInt() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(25); paused = live();
        }
        assertEquals("PAUSED", paused.path("phase").asText());
        assertEquals(0, paused.path("physicalInFlight").asInt());
        assertEquals(1, paused.path("completionSlots").asInt(-1));
        assertEquals("OPEN", paused.path("decisions").get(0).path("status").asText());
        assertEquals("1", paused.path("runBudget").path("reservedCalls").asText());
        assertEquals("0", paused.path("runBudget").path("usedCalls").asText());
        assertEquals(pausedBytes, Files.size(journal), "暂停时读取不得提前应用回执");
        assertEquals(200, post("/api/admin/live/resume", controlBody(paused)).statusCode());
        awaitWaitDecision();
        var done = live();
        assertEquals(0, done.path("completionSlots").asInt(-1));
        assertEquals("1", done.path("runBudget").path("usedCalls").asText());
        assertEquals("0", done.path("runBudget").path("reservedCalls").asText());
        assertEquals(1, calls.get());
    }

    @Test
    void insufficientSceneBudgetIsVisibleWithoutSendingOrLeavingBarrier() throws Exception {
        start(true); login(); enable("research");
        var current = json(get("/api/admin/routes"));
        for (JsonNode route : current.path("routes")) {
            ((ObjectNode) route).remove("sceneLabel");
            ((ObjectNode) route).remove("connected");
            ((ObjectNode) route).put("maxTotalTokens", 256);
        }
        assertEquals(200, post("/api/admin/routes", JSON.writeValueAsString(Map.of(
                "expectedRevision", current.path("revision").asText(), "routes", current.path("routes")))).statusCode());
        assertEquals(200, post("/api/admin/live/start", startBody(live(), List.of("alice"))).statusCode());
        var status = live();
        assertEquals(1, status.path("decisions").size());
        var decision = status.path("decisions").get(0);
        assertEquals("EXPIRED", decision.path("status").asText());
        assertEquals("BUDGET_EXHAUSTED", decision.path("outcome").asText());
        assertEquals("BUDGET_EXHAUSTED", decision.path("reason").asText());
        assertEquals("BUDGET_EXHAUSTED", status.path("skips").get(0).path("reason").asText(),
                "未发起列表也必须显示实际预算拒绝，不能只写EMPTY");
        assertEquals(0, status.path("physicalInFlight").asInt());
        assertEquals(0, status.path("completionSlots").asInt(-1));
        assertEquals("0", status.path("runBudget").path("usedCalls").asText());
        assertEquals("0", status.path("runBudget").path("reservedCalls").asText());
        assertEquals(0, calls.get());
        server.close(); host.close(); restartHost();
        var reopened = live();
        assertEquals("BUDGET_EXHAUSTED", reopened.path("decisions").get(0).path("reason").asText(),
                "重启只读状态也必须保留预算拒绝原因，不能只放在Host临时内存");
        assertEquals(0, calls.get());
        enable("research");
        assertEquals(200, post("/api/admin/live/start", startBody(live(), List.of("alice"))).statusCode(),
                "修订预算后新的明确开始必须能执行，不能遗留未派发OPEN屏障");
        awaitWaitDecision();
        assertEquals(1, calls.get());
    }

    private void restartHost() throws Exception {
        KeySlots keys = KeySlots.load(work.resolve("dummy-keys.txt"));
        URI endpoint = URI.create("http://127.0.0.1:" + provider.getAddress().getPort() + "/chat/completions");
        host = new WorldWebHost(() -> WorldWebScenario.openLive(journal), routes,
                new LiveDecisionProvider(slot -> new DeepSeekClient(() -> keys.credential(slot), endpoint)));
        host.adminSnapshot();
        server = new WorldWebServer(host, keys, routes, 0, "live-http-fixture", null, ignored -> { });
        server.start(); base = server.baseUrl(); cookie = null; login();
    }

    private void start(boolean live) throws Exception {
        Path root = Path.of("target", "live-web-http-tests"); Files.createDirectories(root);
        work = Files.createTempDirectory(root, "case-"); journal = work.resolve("world.journal");
        Path keyFile = work.resolve("dummy-keys.txt");
        Files.writeString(keyFile, String.join("\n", List.of("sk-live-web-fixture-00001", "sk-live-web-fixture-00002",
                "sk-live-web-fixture-00003", "sk-live-web-fixture-00004", "sk-live-web-fixture-00005")));
        KeySlots keys = KeySlots.load(keyFile);
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/chat/completions", exchange -> {
            try {
                providerBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                calls.incrementAndGet(); arrived.countDown();
                if (hold && !release.await(8, TimeUnit.SECONDS)) throw new AssertionError("fixture release missing");
                byte[] response = ("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"tool\\\":\\\"role.wait\\\"}\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        provider.start();
        URI endpoint = URI.create("http://127.0.0.1:" + provider.getAddress().getPort() + "/chat/completions");
        routes = SceneRoutes.open(work);
        host = live ? new WorldWebHost(() -> WorldWebScenario.openLive(journal), routes,
                new LiveDecisionProvider(slot -> new DeepSeekClient(() -> keys.credential(slot), endpoint)))
                : new WorldWebHost(() -> WorldWebScenario.open(journal));
        host.adminSnapshot();
        server = new WorldWebServer(host, keys, routes, 0, "live-http-fixture", null, ignored -> { });
        server.start(); base = server.baseUrl();
    }

    private void login() throws Exception {
        var response = post("/api/session", "{\"bootstrap\":\"live-http-fixture\"}");
        assertEquals(200, response.statusCode()); cookie = cookieOf(response);
    }

    private void enable(String scene) throws Exception {
        var current = json(get("/api/admin/routes"));
        for (JsonNode route : current.path("routes")) {
            var node = (ObjectNode) route;
            node.remove("sceneLabel"); node.remove("connected");
            node.put("enabled", scene.equals(node.path("scene").asText()));
            node.put("slotId", "key-4"); node.put("model", "deepseek-flash");
            node.put("maxCalls", 5); node.put("maxTotalTokens", 100_000);
            node.put("maxOutputTokens", 128); node.put("maxConcurrency", 1);
        }
        String body = JSON.writeValueAsString(Map.of("expectedRevision", current.path("revision").asText(), "routes", current.path("routes")));
        assertEquals(200, post("/api/admin/routes", body).statusCode());
    }

    private String startBody(JsonNode status, List<String> persons) throws Exception {
        return JSON.writeValueAsString(Map.of("expectedPosition", status.path("position").asText(),
                "expectedRevision", json(get("/api/admin/routes")).path("revision").asText(), "persons", persons));
    }
    private String controlBody(JsonNode status) throws Exception {
        return JSON.writeValueAsString(Map.of("expectedPosition", status.path("position").asText()));
    }
    private JsonNode live() throws Exception { return json(get("/api/admin/live")); }
    private JsonNode json(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body()); return JSON.readTree(response.body());
    }
    private HttpResponse<String> get(String path) throws Exception { return request("GET", path, null); }
    private HttpResponse<String> post(String path, String body) throws Exception { return request("POST", path, body); }
    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (cookie != null) request.header("Cookie", cookie);
        if (method.equals("POST")) request.header("Origin", base).header("X-World-Request", "1")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        else request.GET();
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String cookieOf(HttpResponse<String> response) {
        return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }
    private void awaitJournalGrowth(long previousBytes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadline) {
            if (Files.size(journal) > previousBytes) return;
            Thread.sleep(25);
        }
        fail("没有浏览器请求时writer未自主追加完成记录");
    }
    private void awaitWaitDecision() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadline) {
            for (JsonNode decision : live().path("decisions")) {
                if ("WAIT".equals(decision.path("outcome").asText())) return;
            }
            Thread.sleep(25);
        }
        fail("显式接续后的新请求未结算为WAIT");
    }
}
