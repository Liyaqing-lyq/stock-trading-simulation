package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6-A：历史回看与检查点（真实回环 HTTP、真实会话）。
 *
 * <p>覆盖：God 可读索引/视图/导出/核验；人物与未登录一律拒绝；非法位置与未知参数明确 400；
 * 导出只回给当前管理员且带附件头；同一位置的历史账户与实时账户逐行一致；
 * 历史调用不推进世界；篡改检查点被拒；响应不泄露 Key。</p>
 */
class WorldHistoryWebHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "history-web-bootstrap";
    private static final List<String> DUMMY_KEYS = List.of("sk-test0000000000001",
            "sk-test0000000000002", "sk-test0000000000003", "sk-test0000000000004",
            "sk-test0000000000005");

    private Path workDir;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;
    private String adminBootstrap = BOOTSTRAP;
    private final List<String> consoleLines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("web.history.work", "target/web-history-tests"));
        workDir = root.resolve("history-" + System.nanoTime());
        Files.createDirectories(workDir);
        Path journal = workDir.resolve("history.journal");
        Path keys = workDir.resolve("keys.txt");
        Files.writeString(keys, String.join("\n", DUMMY_KEYS), StandardCharsets.UTF_8);
        host = new WorldWebHost(() -> WorldWebScenario.openGroups(journal));

        server = new WorldWebServer(host, KeySlots.load(keys), SceneRoutes.open(workDir),
                0, BOOTSTRAP, null, consoleLines::add);
        server.start();
        base = server.baseUrl();
        cookie = null;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        if (host != null) {
            host.close();
        }
    }

    @Test
    @DisplayName("God 历史链：索引→固定位置视图→导出→核验，且不推进世界")
    void adminHistoryChain() throws Exception {
        loginAdmin();
        long positionBefore = Long.parseLong(getJson("/api/admin/view").path("position").asText());

        JsonNode index = getJson("/api/admin/history?after=0&limit=2");
        assertEquals(host.runId(), index.path("runId").asText());
        assertEquals(2, index.path("entries").size());
        assertEquals("1", index.path("entries").get(0).path("position").asText());
        assertEquals("2", index.path("entries").get(1).path("position").asText());
        long cut = Long.parseLong(index.path("cutPosition").asText());
        assertTrue(cut >= positionBefore);
        assertEquals("2", index.path("nextAfter").asText());

        JsonNode view = getJson("/api/admin/history/view?position=1");
        assertEquals("1", view.path("position").asText());
        assertEquals(index.path("cutPosition").asText(), view.path("cutPosition").asText());
        assertTrue(view.path("historical").asBoolean());
        assertTrue(view.path("accounts").path("rows").size() > 0, "位置 1 必须有现金账户");
        assertTrue(view.path("overview").path("rows").size() == 1);
        assertTrue(view.path("groups").isObject(), "群组分区必须是对象（即使空）");
        assertTrue(view.path("notes").isArray());
        assertTrue(view.path("unsupported").isArray());

        // 同一位置的历史账户必须与实时账户逐行一致
        JsonNode live = getJson("/api/admin/view");
        JsonNode viewAtCut = getJson("/api/admin/history/view?position=" + cut);
        List<String> liveRows = accountLines(live.path("accounts"));
        List<String> historyRows = accountLines(viewAtCut.path("accounts").path("rows"));
        assertEquals(liveRows, historyRows, "同一位置的历史账户与实时账户必须一致");
        assertEquals(live.path("position").asText(), viewAtCut.path("position").asText());

        HttpResponse<String> checkpoint = rawGet("/api/admin/history/checkpoint?position=" + cut);
        assertEquals(200, checkpoint.statusCode(), checkpoint.body());
        assertTrue(checkpoint.headers().firstValue("content-disposition").orElse("")
                .startsWith("attachment;"), "检查点必须以附件下载");
        JsonNode envelope = JSON.readTree(checkpoint.body());
        assertEquals("world-checkpoint-v1", envelope.path("checkpointVersion").asText());
        assertEquals(host.runId(), envelope.path("runId").asText());
        assertEquals(cut, envelope.path("position").asLong());
        assertTrue(envelope.path("stateJson").asText().length() > 0);
        assertTrue(envelope.path("opening").has("manifest"));
        assertFalse(envelope.path("claims").path("independentResume").asBoolean(true));
        assertFalse(checkpoint.body().contains(DUMMY_KEYS.get(0)),
                "导出不得包含任何 Key");

        JsonNode verdict = postJson("/api/admin/history/verify", checkpoint.body());
        assertTrue(verdict.path("ok").asBoolean(), verdict.toString());
        assertEquals("OK", verdict.path("reason").asText());

        // 历史读取不推进世界
        long positionAfter = Long.parseLong(getJson("/api/admin/view").path("position").asText());
        assertEquals(positionBefore, positionAfter, "历史读取不得推进世界");
    }

    @Test
    @DisplayName("非法位置与未知参数明确 400，不夹取也不读服务器文件")
    void invalidRequestsRejected() throws Exception {
        loginAdmin();
        long cut = Long.parseLong(getJson("/api/admin/history?limit=1").path("cutPosition").asText());

        assertEquals(400, rawGet("/api/admin/history/view").statusCode(), "缺少 position 必须 400");
        assertEquals(400, rawGet("/api/admin/history/view?position=0").statusCode());
        assertEquals(400, rawGet("/api/admin/history/view?position=" + (cut + 1)).statusCode());
        assertEquals(400, rawGet("/api/admin/history/view?position=abc").statusCode());
        assertEquals(400, rawGet("/api/admin/history?limit=0").statusCode());
        assertEquals(400, rawGet("/api/admin/history?limit="
                + (edu.cufe.auction.world.WorldRuntime.MAX_HISTORY_PAGE + 1)).statusCode());
        assertEquals(400, rawGet("/api/admin/history?after=-1").statusCode());
        assertEquals(400, rawGet("/api/admin/history?limit=1&path=/etc/passwd").statusCode());
        assertEquals(400, rawGet("/api/admin/history/checkpoint").statusCode());
        assertEquals(400, rawGet("/api/admin/history/checkpoint?position=" + (cut + 1)).statusCode());
    }

    @Test
    @DisplayName("未登录 401、人物 403：普通身份拿不到 God 历史与核验")
    void personCannotUseHistory() throws Exception {
        assertEquals(401, rawGet("/api/admin/history?limit=1").statusCode());
        assertEquals(401, rawGet("/api/admin/history/view?position=1").statusCode());
        assertEquals(401, rawGet("/api/admin/history/checkpoint?position=1").statusCode());
        assertEquals(401, rawPost("/api/admin/history/verify", "{}", null).statusCode());

        personAt("bob");
        assertEquals(403, rawGet("/api/admin/history?limit=1").statusCode());
        assertEquals(403, rawGet("/api/admin/history/view?position=1").statusCode());
        assertEquals(403, rawGet("/api/admin/history/checkpoint?position=1").statusCode());
        assertEquals(403, rawPost("/api/admin/history/verify", "{}", cookie).statusCode());
    }

    @Test
    @DisplayName("篡改检查点：改金额或改位置都被拒，且不让摘要自洽成为证明")
    void tamperedCheckpointRejected() throws Exception {
        loginAdmin();
        long cut = Long.parseLong(getJson("/api/admin/history?limit=1").path("cutPosition").asText());
        JsonNode envelope = JSON.readTree(
                rawGet("/api/admin/history/checkpoint?position=" + cut).body());

        ObjectNode tampered = (ObjectNode) envelope.deepCopy();
        ObjectNode state = (ObjectNode) JSON.readTree(tampered.get("stateJson").asText());
        ObjectNode accounts = (ObjectNode) state.get("accounts");
        String accountId = accounts.fieldNames().next();
        ((ObjectNode) accounts.get(accountId)).put("cash", new java.math.BigDecimal("123456.78"));
        tampered.put("stateJson", JSON.writeValueAsString(state));
        JsonNode verdict = postJson("/api/admin/history/verify", JSON.writeValueAsString(tampered));
        assertFalse(verdict.path("ok").asBoolean());
        assertEquals("CHECKPOINT_STATE_MISMATCH", verdict.path("reason").asText());

        ObjectNode otherPosition = (ObjectNode) envelope.deepCopy();
        otherPosition.put("position", cut + 5);
        JsonNode second = postJson("/api/admin/history/verify",
                JSON.writeValueAsString(otherPosition));
        assertFalse(second.path("ok").asBoolean());
        assertEquals("CHECKPOINT_POSITION_UNCONFIRMED", second.path("reason").asText());

        ObjectNode otherRun = (ObjectNode) envelope.deepCopy();
        otherRun.put("runId", "someone-else");
        JsonNode third = postJson("/api/admin/history/verify", JSON.writeValueAsString(otherRun));
        assertFalse(third.path("ok").asBoolean());
        assertEquals("CHECKPOINT_RUN_MISMATCH", third.path("reason").asText());
    }

    @Test
    @DisplayName("检查点只回给当前管理员：换人物后同一文件不再被接受")
    void checkpointBelongsToCurrentAdmin() throws Exception {
        loginAdmin();
        long cut = Long.parseLong(getJson("/api/admin/history?limit=1").path("cutPosition").asText());
        String body = rawGet("/api/admin/history/checkpoint?position=" + cut).body();

        personAt("bob");
        assertEquals(403, rawPost("/api/admin/history/verify", body, cookie).statusCode());
        assertEquals(403, rawGet("/api/admin/history/checkpoint?position=" + cut).statusCode());
        // 人物会话不得因为拿到过文件就获得历史能力
        assertEquals(403, rawGet("/api/admin/history/view?position=1").statusCode());
    }

    private static List<String> accountLines(JsonNode rows) {
        List<String> lines = new ArrayList<>();
        for (JsonNode row : rows) {
            lines.add(row.path("accountId").asText() + "|" + row.path("ownerId").asText()
                    + "|" + row.path("cash").asText());
        }
        lines.sort(String::compareTo);
        return lines;
    }

    private void loginAdmin() throws Exception {
        HttpResponse<String> response = post("/api/session",
                "{\"bootstrap\":\"" + adminBootstrap + "\"}");
        assertEquals(200, response.statusCode(), response.body());
        cookie = cookieOf(response);
        adminBootstrap = rotatedBootstrap();
    }

    private String rotatedBootstrap() {
        for (int index = consoleLines.size() - 1; index >= 0; index--) {
            String line = consoleLines.get(index);
            int mark = line.indexOf('：');
            if (line.contains("引导凭据") && mark > 0) {
                return line.substring(mark + 1).trim();
            }
        }
        return adminBootstrap;
    }

    private void personAt(String personId) throws Exception {
        loginAdmin();
        HttpResponse<String> response = post("/api/session/person",
                "{\"personId\":\"" + personId + "\"}");
        assertEquals(200, response.statusCode(), response.body());
        cookie = cookieOf(response);
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = rawGet(path);
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return JSON.readTree(response.body());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = rawPost(path, body, cookie);
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return rawPost(path, body, cookie);
    }

    private HttpResponse<String> rawGet(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> rawPost(String path, String body, String sessionCookie)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Origin", base)
                .header("X-World-Request", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookieOf(HttpResponse<String> response) {
        String raw = response.headers().firstValue("set-cookie").orElseThrow(
                () -> new AssertionError("缺少 set-cookie"));
        return raw.split(";", 2)[0];
    }
}
