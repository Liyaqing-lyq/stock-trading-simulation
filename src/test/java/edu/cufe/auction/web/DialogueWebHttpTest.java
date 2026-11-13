package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.LiveCompletionInbox;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 真实 Web HTTP + Host 的逐轮访谈接线验证（进程内脚本提供方，随机本机端口）。
 *
 * <p><b>口径</b>：这证明 Web/Host/Server 与服务端采访投影的接线，不是官方模型自主行为，
 * 也不是模型网络传输测试（DeepSeek HTTP 适配未改，不在本轮范围）。</p>
 *
 * <p><b>标识来源</b>：脚本提供方只从真实 {@link DecisionRun.Call#inputText()} 解析
 * {@code interview.invites[].requestId}、{@code interview.sessions[].turns[].docId/docVersion}、
 * {@code profile.catalog[].docId}；观察不到所需标识即立即失败，不从内部命名约定猜。
 * 仅 {@code clientRequestId}（本人命令命名空间）是固定的。</p>
 */
class DialogueWebHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void dialogueLoopbackOverRealHttp() throws Exception {
        try (Harness harness = new Harness()) {
            Client admin = harness.admin();
            admin.saveRoutes();
            admin.advance(1_000L); // 真实发布任务：联系说明落地

            // 10 个真实动作，一步一轮；每一步的标识都取自该轮真实输入
            String[] people = {"rita", "rita", "investor", "rita", "investor",
                "investor", "rita", "rita", "investor", "investor"};
            for (int index = 0; index < people.length; index += 1) {
                admin.round(people[index]);
                if (index == 0) {
                    admin.advance(admin.timeMillis() + 8_000L);
                } else if (index == 2) {
                    admin.advance(harness.provider.sessionStart() + 2_000L);
                } else {
                    admin.advance(admin.timeMillis() + 4_000L);
                }
            }

            JsonNode audit = admin.get("/api/admin/dialogue");
            System.out.println("AUDIT sessions=" + audit.path("sessions").path("total")
                    + " turns=" + audit.path("turns").path("total")
                    + " closes=" + audit.path("closes").path("total")
                    + " reports=" + audit.path("reports").path("total"));
            assertTrue(audit.path("available").asBoolean(), "dialogue available");
            JsonNode turns = audit.path("turns");
            assertEquals(3, turns.path("total").asInt(), "three real turns");
            assertEquals("rita", turns.path("rows").get(0).path("speakerPartyId").asText());
            assertTrue(turns.path("rows").get(0).path("peerRead").asBoolean(),
                    "investor actually read turn 1");
            assertTrue(turns.path("rows").get(1).path("peerRead").asBoolean(),
                    "rita actually read turn 2");
            assertTrue(turns.path("rows").get(2).path("peerRead").asBoolean(),
                    "investor read turn 3 before ending");
            assertEquals(1, audit.path("closes").path("total").asInt(), "one real close");
            assertEquals("CLOSED_BY_PARTY",
                    audit.path("closes").path("rows").get(0).path("reason").asText());

            // 身份与未读边界
            Client anonymous = new Client(harness.baseUrl());
            assertNotEquals(200, anonymous.getStatus("/api/admin/dialogue"),
                    "no session must be refused");
            JsonNode switched = admin.post("/api/session/person", Map.of("personId", "rita"));
            assertEquals("rita", switched.path("personId").asText());
            assertEquals(403, admin.getStatus("/api/admin/dialogue"),
                    "person session must not read admin audit");
            JsonNode mine = admin.get("/api/me/dialogue");
            System.out.println("PERSON sessions=" + mine.path("sessions").path("total")
                    + " turns=" + mine.path("turns").path("total"));
            assertEquals(1, mine.path("sessions").path("total").asInt(), "only own sessions");
            assertEquals(3, mine.path("turns").path("total").asInt());
            boolean sawRead = false;
            boolean sawUnread = false;
            for (JsonNode row : mine.path("turns").path("rows")) {
                boolean read = row.path("readByMe").asBoolean();
                sawRead |= read;
                sawUnread |= !read;
                assertEquals(read, !row.path("body").asText().isEmpty(),
                        "body only for turns actually read: " + row.path("docId").asText());
            }
            assertTrue(sawRead, "rita read the peer turn");
            assertTrue(sawUnread, "rita's own turns are not auto-read");
        }
    }

    /**
     * 无人回应时推进必须跨过真实回复截止并正常结束会话，不得出现 WORLD_IO/FAULT。
     *
     * <p>截止时点取自真实输入 {@code sessions[].replyDeadlineMillis}，不按配置常量推算。</p>
     */
    @Test
    void deadlineCrossingClosesWithoutWorldIo() throws Exception {
        try (Harness harness = new Harness()) {
            Client admin = harness.admin();
            admin.saveRoutes();
            admin.advance(1_000L);
            String[] people = {"rita", "rita", "investor", "rita"};
            for (int index = 0; index < people.length; index += 1) {
                admin.round(people[index]);
                if (index == 0) {
                    admin.advance(admin.timeMillis() + 8_000L);
                } else if (index == 2) {
                    admin.advance(harness.provider.sessionStart() + 2_000L);
                } else {
                    admin.advance(admin.timeMillis() + 4_000L);
                }
            }
            long deadline = harness.provider.lastDeadline();
            assertTrue(deadline > 0, "real input must expose replyDeadlineMillis");
            // 从真实截止起步小步推进，直到会话被真实规则关闭；此前精确跨截止会 503 WORLD_IO
            int closes = 0;
            long target = deadline + 1_000L;
            for (int step = 0; step < 20 && closes == 0; step += 1) {
                target = Math.max(target, admin.timeMillis() + 4_000L);
                admin.advance(target);
                closes = admin.get("/api/admin/dialogue").path("closes").path("total").asInt();
            }
            assertEquals(1, closes, "deadline must close exactly one session");
            JsonNode audit = admin.get("/api/admin/dialogue");
            assertEquals("DEADLINE_NO_REPLY",
                    audit.path("closes").path("rows").get(0).path("reason").asText());
            assertEquals("IDLE", admin.get("/api/admin/live").path("phase").asText(),
                    "world must stay usable after crossing the deadline");
        }
    }

    /** 一套独立的 Host+Server+随机端口；close 时全部释放，不留持久服务。 */
    static final class Harness implements AutoCloseable {
        final Path dir;
        final SceneRoutes routes;
        final WorldWebHost host;
        final WorldWebServer server;
        final ScriptedDialogue provider = new ScriptedDialogue();
        final String bootstrap = "fixture-bootstrap-" + System.nanoTime();

        Harness() throws Exception {
            dir = Files.createTempDirectory(Files.createDirectories(Path.of("target", "dialogue-test-tmp")), "dialogue-web-");
            Path keys = dir.resolve("keys.txt");
            Files.writeString(keys, "sk-aaaaaaaaaaaaaaaa1\nsk-aaaaaaaaaaaaaaaa2\n"
                    + "sk-aaaaaaaaaaaaaaaa3\nsk-aaaaaaaaaaaaaaaa4\nsk-aaaaaaaaaaaaaaaa5\n",
                    StandardCharsets.UTF_8);
            KeySlots slots = KeySlots.load(keys);
            routes = SceneRoutes.open(dir);
            host = new WorldWebHost(
                    () -> WorldWebScenario.openInterviewDialogue(dir.resolve("world-dialogue.journal")),
                    routes, provider);
            server = new WorldWebServer(host, slots, routes, 0, bootstrap, null, text -> { });
            server.start();
        }

        String baseUrl() {
            return server.baseUrl();
        }

        Client admin() throws Exception {
            Client client = new Client(baseUrl());
            JsonNode login = client.post("/api/session", Map.of("bootstrap", bootstrap));
            assertTrue(login.path("error").isMissingNode(), "admin login must succeed");
            return client;
        }

        @Override
        public void close() throws Exception {
            server.close();
            host.close();
            routes.close();
        }
    }

    /**
     * 进程内脚本提供方：只读真实输入 JSON；缺所需标识即抛断言失败（测试立刻红）。
     */
    static final class ScriptedDialogue implements WorldWebHost.DecisionProvider {
        private static final String CLIENT_REQUEST = "q1"; // 本人命令命名空间，允许固定
        private final Map<String, Integer> round = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile long sessionStart = -1L;
        private volatile long lastDeadline = -1L;

        long sessionStart() {
            return sessionStart;
        }

        long lastDeadline() {
            return lastDeadline;
        }

        @Override
        public LiveCompletionInbox.Completion evaluate(DecisionRun.Call call, long timeoutMillis) {
            calls.incrementAndGet();
            JsonNode input = read(call);
            String person = requireText(input, "person");
            long now = input.path("time").asLong(-1);
            assertTrue(now >= 0, "input.time required");
            JsonNode interview = input.get("interview");
            assertNotNull(interview, "input.interview projection required for schema15");
            JsonNode invite = first(interview.path("invites"));
            JsonNode session = first(interview.path("sessions"));
            lastDeadline = session == null ? -1L : session.path("replyDeadlineMillis").asLong(-1L);

            int step = round.merge(person, 1, Integer::sum);
            String tool;
            String args = null;
            if ("rita".equals(person)) {
                switch (step) {
                    case 1 -> {
                        JsonNode contact = catalogDoc(input, "contact:");
                        tool = "role.reading.start";
                        args = readArgs(contact);
                    }
                    case 2 -> {
                        long windowStart = now + 120_000L;
                        sessionStart = windowStart;
                        tool = "interview.invite";
                        args = "{\"requestClientId\":\"" + CLIENT_REQUEST
                                + "\",\"toPartyId\":\"investor\",\"topic\":\"实验采访\""
                                + ",\"windowStartMillis\":" + windowStart
                                + ",\"windowEndMillis\":" + (windowStart + 1_000_000L)
                                + ",\"durationMillis\":120000}";
                    }
                    case 3 -> {
                        tool = "interview.speak";
                        args = speakArgs(requireRequest(invite), "Q1", "问题 1");
                    }
                    case 4 -> {
                        tool = "role.reading.start";
                        args = readArgs(requireTurn(session, 1, person));
                    }
                    default -> {
                        tool = "interview.speak";
                        args = speakArgs(requireRequest(invite), "Q2", "问题 2");
                    }
                }
            } else {
                switch (step) {
                    case 1 -> {
                        tool = "interview.respond";
                        args = "{\"requestId\":\"" + requireRequest(invite) + "\",\"accept\":true"
                                + ",\"startMillis\":" + (sessionStart > 0 ? sessionStart
                                : now + 120_000L) + "}";
                    }
                    case 2 -> {
                        tool = "role.reading.start";
                        args = readArgs(requireTurn(session, 0, person));
                    }
                    case 3 -> {
                        tool = "interview.speak";
                        args = speakArgs(requireRequest(invite), "A1", "回答 1");
                    }
                    case 4 -> {
                        tool = "role.reading.start";
                        args = readArgs(requireTurn(session, 2, person));
                    }
                    default -> {
                        tool = "interview.end";
                        args = "{\"requestId\":\"" + requireRequest(invite) + "\"}";
                    }
                }
            }
            String body = args == null ? "{\"tool\":\"wait\"}"
                    : "{\"tool\":\"" + tool + "\",\"args\":" + args + "}";
            System.out.println("SCRIPT " + person + "#" + step + " -> " + tool);
            DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                    call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                    DecisionModel.Usage.unknownCost(1, 120, 40), "SCRIPTED_LOOPBACK");
            return LiveCompletionInbox.Completion.business(reply);
        }

        private static JsonNode read(DecisionRun.Call call) {
            String text = call.inputText();
            try {
                return MAPPER.readTree(text);
            } catch (Exception failure) {
                fail("inputText is not valid JSON: " + failure);
                return null;
            }
        }

        private static String requireText(JsonNode input, String field) {
            String value = input.path(field).asText("");
            if (value.isBlank()) {
                fail("input." + field + " required");
            }
            return value;
        }

        private static JsonNode first(JsonNode array) {
            return array != null && array.isArray() && array.size() > 0 ? array.get(0) : null;
        }

        /** 本人可见目录里唯一的公开联系资料（真实观察，非常量）。 */
        private static JsonNode catalogDoc(JsonNode input, String prefix) {
            for (JsonNode doc : input.path("profile").path("catalog")) {
                String docId = doc.path("docId").asText("");
                if (docId.startsWith(prefix)) {
                    return doc;
                }
            }
            fail("profile.catalog must expose a " + prefix + "* document");
            return null;
        }

        private static String requireRequest(JsonNode invite) {
            if (invite == null || invite.path("requestId").asText("").isBlank()) {
                fail("input.interview.invites[].requestId required for this action");
            }
            return invite.path("requestId").asText();
        }

        private static JsonNode requireTurn(JsonNode session, int index, String person) {
            if (session == null) {
                fail("input.interview.sessions[] required for " + person);
            }
            JsonNode turns = session.path("turns");
            if (!turns.isArray() || turns.size() <= index) {
                fail("input.interview.sessions[].turns[" + index + "] required for " + person
                        + ", actual=" + turns.size());
            }
            JsonNode turn = turns.get(index);
            if (turn.path("docId").asText("").isBlank() || turn.path("docVersion").asLong(0) < 1) {
                fail("turn[" + index + "] must expose docId/docVersion");
            }
            return turn;
        }

        private static String readArgs(JsonNode doc) {
            return "{\"docId\":\"" + doc.path("docId").asText()
                    + "\",\"version\":" + doc.path("version").asLong(doc.path("docVersion").asLong())
                    + "}";
        }

        private static String speakArgs(String requestId, String title, String body) {
            return "{\"requestId\":\"" + requestId + "\",\"title\":\"" + title
                    + "\",\"body\":\"" + body + "\",\"readDurationMillis\":1000}";
        }
    }

    /** 极简 HTTP 客户端：保存 world_session cookie，全部走真实 loopback。 */
    static final class Client {
        private final String base;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        private String cookie = "";

        Client(String base) {
            this.base = base;
        }

        void saveRoutes() throws Exception {
            JsonNode view = get("/api/admin/routes");
            long revision = view.path("revision").asLong();
            List<Map<String, Object>> routes = new ArrayList<>();
            for (JsonNode route : view.path("routes")) {
                boolean media = "media".equals(route.path("scene").asText());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("scene", route.path("scene").asText());
                row.put("slotId", route.path("slotId").asText());
                row.put("model", route.path("model").asText());
                row.put("enabled", media);
                row.put("maxCalls", media ? 40 : 1);
                row.put("maxTotalTokens", 1_000_000);
                row.put("maxOutputTokens", 8192);
                row.put("maxConcurrency", 1);
                routes.add(row);
            }
            JsonNode saved = post("/api/admin/routes",
                    Map.of("expectedRevision", Long.toString(revision), "routes", routes));
            assertTrue(saved.path("error").isMissingNode(), "routes save must succeed: " + saved);
        }

        void round(String person) throws Exception {
            long position = get("/api/admin/view").path("position").asLong();
            long revision = get("/api/admin/routes").path("revision").asLong();
            JsonNode result = post("/api/admin/live/start",
                    Map.of("expectedPosition", position, "expectedRevision",
                            Long.toString(revision), "persons", List.of(person)));
            assertTrue(result.path("error").isMissingNode(), "live start failed: " + result);
            for (int i = 0; i < 100; i += 1) {
                Thread.sleep(20L);
                if (get("/api/admin/live").path("inFlight").asInt(0) == 0) {
                    break;
                }
            }
        }

        void advance(long targetMillis) throws Exception {
            long position = get("/api/admin/view").path("position").asLong();
            JsonNode result = post("/api/admin/advance",
                    Map.of("expectedPosition", position, "targetMillis", targetMillis));
            assertTrue(result.path("error").isMissingNode(),
                    "advance to " + targetMillis + " must not fail: " + result);
        }

        long timeMillis() throws Exception {
            return Long.parseLong(get("/api/admin/view").path("timeMillis").asText());
        }

        JsonNode post(String path, Object body) throws Exception {
            return send("POST", path, body);
        }

        JsonNode get(String path) throws Exception {
            return send("GET", path, null);
        }

        int getStatus(String path) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .GET().timeout(Duration.ofSeconds(10));
            if (!cookie.isEmpty()) {
                builder.header("Cookie", cookie);
            }
            return client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode();
        }

        private JsonNode send(String method, String path, Object body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(15));
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json");
                builder.header("X-World-Request", "1");
                builder.header("Origin", base);
                builder.POST(HttpRequest.BodyPublishers.ofString(
                        body == null ? "{}" : MAPPER.writeValueAsString(body),
                        StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            if (!cookie.isEmpty()) {
                builder.header("Cookie", cookie);
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String setCookie = response.headers().firstValue("Set-Cookie").orElse("");
            if (setCookie.startsWith("world_session=") && setCookie.length() > 20) {
                cookie = setCookie.split(";")[0];
            }
            if (response.statusCode() >= 400) {
                return MAPPER.readTree("{\"error\":\"" + response.statusCode() + "\","
                        + "\"body\":" + MAPPER.writeValueAsString(response.body()) + "}");
            }
            String text = response.body();
            return text == null || text.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(text);
        }
    }
}
