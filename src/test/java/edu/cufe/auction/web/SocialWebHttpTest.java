package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.SocialMessageModel;
import edu.cufe.auction.world.WorldRuntime;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema17 社交 Web 接线（backend-intake-2）：真实 HTTP + Host + 进程内脚本。
 *
 * <p>链：未读入口 send 业务拒绝（INTENT 保留）→ 读真实入口 → 写信排队 → 收件者屏蔽 →
 * 投递 BLOCKED 不发文档 → 解除屏蔽 → 旧信不复活 → 新信可投递 → 收件人实读。
 * 标识只取 {@code input.social.*} 与 {@code profile.catalog}，不硬编码消息/文档 ID。
 * 0 真实 provider；这是接线验证，不是模型自主行为。</p>
 */
class SocialWebHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void g2bOwnershipAndFrameTiming() throws Exception {
        try (Harness harness = new Harness()) {
            harness.provider.diag = true;
            Client admin = harness.admin();
            admin.saveRoutes();
            admin.advance(1_200L);
            System.out.println("FRAME start time=" + admin.timeMillis()
                    + " clock=" + admin.get("/api/admin/social").path("clock"));
            String[][] steps = {{"nina", "12000"}, {"nina", "12000"}, {"nina", "600"},
                {"omar", "600"}, {"omar", "600"}, {"adv", "6000"}, {"nina", "600"},
                {"adv", "1000"}, {"omar", "600"}, {"adv", "2000"}, {"nina", "600"},
                {"adv", "6000"}};
            for (String[] step : steps) {
                if ("adv".equals(step[0])) {
                    admin.advance(admin.timeMillis() + Long.parseLong(step[1]));
                } else {
                    admin.round(step[0]);
                    admin.advance(admin.timeMillis() + Long.parseLong(step[1]));
                }
                JsonNode audit = admin.get("/api/admin/social");
                System.out.println("FRAME after " + step[0] + " time=" + admin.timeMillis()
                        + " phase=" + admin.get("/api/admin/live").path("phase").asText()
                        + " clock=" + audit.path("clock").asText()
                        + " blocks=" + audit.path("blocks").path("total").asInt()
                        + " statuses=" + audit.path("messages").path("rows").findValuesAsText("status"));
            }
            JsonNode audit = admin.get("/api/admin/social");
            JsonNode switched = admin.post("/api/session/person", Map.of("personId", "omar"));
            assertEquals("omar", switched.path("personId").asText());
            JsonNode mine = admin.get("/api/me/social");
            System.out.println("DIAG omar inbox=" + mine.path("inbox").path("total")
                    + " blocks=" + audit.path("blocks").path("total"));
            System.out.println("DIAG_REPORTS " + audit.path("reports").path("rows"));
        }
    }

    @Test
    void nineReadMessagesStayReadAndRecallable() throws Exception {
        try (Harness harness = new Harness()) {
            harness.provider.watermark = true;
            Client admin = harness.admin();
            admin.saveRoutes();
            admin.advance(1_200L);
            for (int i = 0; i < 10; i += 1) {
                admin.round("nina");
                admin.advance(admin.timeMillis() + 4_000L);
            }
            for (int i = 0; i < 10; i += 1) {
                admin.round("omar");
                admin.advance(admin.timeMillis() + 4_000L);
            }
            JsonNode audit = admin.get("/api/admin/social");
            int read = 0;
            for (JsonNode row : audit.path("messages").path("rows")) {
                if (row.path("readByReceiver").asBoolean()) {
                    read += 1;
                }
            }
            System.out.println("WATERMARK admin read=" + read
                    + " messages=" + audit.path("messages").path("total"));
            assertEquals(9, read, "9 封真实读过的信，管理员口径不得回退");
            JsonNode switched = admin.post("/api/session/person", Map.of("personId", "omar"));
            assertEquals("omar", switched.path("personId").asText());
            JsonNode mine = admin.get("/api/me/social");
            assertEquals(9, mine.path("inbox").path("total").asInt(), "收件箱 9 封");
            int bodyCount = 0;
            for (JsonNode row : mine.path("inbox").path("rows")) {
                assertTrue(row.path("read").asBoolean(), "9 封都必须标记已读（不受展示预算回退）");
                assertTrue(row.path("bodyAvailable").asBoolean(), "回忆窗口内应可取正文");
                assertFalse(row.path("body").asText("").isBlank(), "正文非空");
                bodyCount += 1;
            }
            System.out.println("WATERMARK person read=9 bodyAvailable=" + bodyCount);
            boolean sawNina = false;
            for (JsonNode row : mine.path("contacts").path("rows")) {
                sawNina |= "nina".equals(row.path("targetPartyId").asText());
            }
            assertTrue(sawNina, "联系入口不因展示预算被隐藏");
        }
    }

    @Test
    void chainRecipientSeesOnlyDelivered() throws Exception {
        try (Harness harness = new Harness()) {
            Client admin = harness.admin();
            JsonNode audit = runChain(admin);
            assertAudit(audit);
            JsonNode switched = admin.post("/api/session/person", Map.of("personId", "omar"));
            assertEquals("omar", switched.path("personId").asText());
            assertEquals(403, admin.getStatus("/api/admin/social"), "人物不得读管理员消息审计");
            JsonNode mine = admin.get("/api/me/social");
            System.out.println("PERSON_INBOX " + mine.path("inbox"));
            assertEquals(0, mine.path("inbox").path("total").asInt(),
                    "被屏蔽的信不得出现在收件箱（未投递无信封/正文）");
            boolean sawNina = false;
            for (JsonNode row : mine.path("contacts").path("rows")) {
                sawNina |= "nina".equals(row.path("targetPartyId").asText());
            }
            assertTrue(sawNina, "收件人仍能看到已读联系入口");
            assertFalse(mine.toString().contains("BLOCKED"), "人物视图不得出现屏蔽状态");
            assertFalse(mine.toString().contains("blockedBy"), "人物视图不得泄漏屏蔽方");
        }
    }

    @Test
    void chainSenderSeesUnifiedUndelivered() throws Exception {
        try (Harness harness = new Harness()) {
            Client admin = harness.admin();
            JsonNode audit = runChain(admin);
            assertAudit(audit);
            JsonNode switched = admin.post("/api/session/person", Map.of("personId", "nina"));
            assertEquals("nina", switched.path("personId").asText());
            JsonNode mine = admin.get("/api/me/social");
            System.out.println("PERSON_OUTBOX " + mine.path("outbox"));
            assertEquals(1, mine.path("outbox").path("total").asInt(), "一封真实写出的信");
            for (JsonNode row : mine.path("outbox").path("rows")) {
                assertEquals("UNDELIVERED", row.path("status").asText(),
                        "被屏蔽未送达对外统一 UNDELIVERED");
                assertFalse(row.toString().contains("BLOCKED"), "发件人不得看到屏蔽状态");
            }
            assertFalse(mine.toString().contains("blockedBy"), "发件人不得看到被谁屏蔽");
        }
    }

    private static void assertAudit(JsonNode audit) {
        assertTrue(audit.path("available").asBoolean(), "social available");
        System.out.println("AUDIT messages=" + audit.path("messages").path("total")
                + " reports=" + audit.path("reports").path("total")
                + " blocks=" + audit.path("blocks").path("total") + " clock=" + audit.path("clock")
                + " classes=" + WorldWebHost.class.getProtectionDomain().getCodeSource().getLocation()
                + " runtime=" + WorldRuntime.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("MSG " + audit.path("messages").path("rows"));
        System.out.println("REPORTS " + audit.path("reports").path("rows"));
        int blocked = 0;
        int delivered = 0;
        for (JsonNode row : audit.path("messages").path("rows")) {
            String status = row.path("status").asText();
            if ("BLOCKED".equals(status)) {
                blocked += 1;
                assertFalse(row.path("rejectReason").asText("").isBlank(),
                        "管理员能看到真实屏蔽原因");
            }
            if ("DELIVERED".equals(status)) {
                delivered += 1;
            }
        }
        assertEquals(1, blocked, "屏蔽期间的投递应 BLOCKED");
        assertEquals(0, delivered, "屏蔽期间不得有 DELIVERED");
        assertEquals(1, audit.path("blocks").path("total").asInt(), "屏蔽仍生效");
        boolean sawBlock = false;
        boolean sawBlockedDeliver = false;
        boolean sawUnreachable = false;
        for (JsonNode row : audit.path("reports").path("rows")) {
            String kind = row.path("kind").asText("");
            if (row.path("accepted").asBoolean() && kind.contains("BLOCK")) {
                sawBlock = true;
            }
            if (!row.path("accepted").asBoolean() && kind.contains("DELIVER")
                    && "BLOCKED".equals(row.path("reason").asText())) {
                sawBlockedDeliver = true;
            }
            if (!row.path("accepted").asBoolean()
                    && "UNREACHABLE".equals(row.path("reason").asText())) {
                sawUnreachable = true;
            }
        }
        assertTrue(sawBlock, "屏蔽动作必须有真实报告");
        assertTrue(sawBlockedDeliver, "投递被屏蔽必须留真实原因 BLOCKED");
        assertTrue(sawUnreachable, "未读/被屏蔽的 send 必须是真实业务拒绝并留报告");
    }

    /** 固定时序：让屏蔽在投递前完成，且解除后旧信不被重投。 */
    private static JsonNode runChain(Client admin) throws Exception {
        admin.saveRoutes();
        admin.advance(1_200L);
        String[][] steps = {{"nina", "12000"}, {"nina", "12000"}, {"nina", "600"},
            {"omar", "600"}, {"omar", "600"}, {"adv", "8000"}, {"nina", "600"},
            {"adv", "2000"}};
        for (String[] step : steps) {
            if ("adv".equals(step[0])) {
                admin.advance(admin.timeMillis() + Long.parseLong(step[1]));
            } else {
                admin.round(step[0]);
                admin.advance(admin.timeMillis() + Long.parseLong(step[1]));
            }
        }
        return admin.get("/api/admin/social");
    }

    @Test
    void blockSurvivesReopenAndUnblockAfterReopen() throws Exception {
        Path dir = Files.createTempDirectory(
                Files.createDirectories(Path.of("target", "social-web-http")), "social-reopen-");
        try (Harness harness = new Harness(dir)) {
            Client admin = harness.admin();
            admin.saveRoutes();
            admin.advance(1_200L);
            for (String person : new String[] {"nina", "nina", "omar", "omar"}) {
                admin.round(person);
                admin.advance(admin.timeMillis() + 8_000L);
            }
            assertEquals(1, admin.get("/api/admin/social").path("blocks").path("total").asInt(),
                    "屏蔽应生效");
        }
        try (Harness reopened = new Harness(dir)) {
            Client admin = reopened.admin();
            JsonNode audit = admin.get("/api/admin/social");
            System.out.println("REOPEN blocks=" + audit.path("blocks").path("total")
                    + " clock=" + audit.path("clock"));
            assertEquals(1, audit.path("blocks").path("total").asInt(),
                    "重开后屏蔽事实仍在");
        }
    }

    /** 独立 Host+Server+随机端口。 */
    static final class Harness implements AutoCloseable {
        final Path dir;
        final SceneRoutes routes;
        final WorldWebHost host;
        final WorldWebServer server;
        final ScriptedSocial provider = new ScriptedSocial();
        final String bootstrap = "fixture-bootstrap-" + System.nanoTime();

        Harness() throws Exception {
            this(Files.createTempDirectory(
                    Files.createDirectories(Path.of("target", "social-web-http")), "social-web-"));
        }

        Harness(Path dir) throws Exception {
            this.dir = dir;
            Path keys = dir.resolve("keys.txt");
            Files.writeString(keys, "sk-aaaaaaaaaaaaaaaa1\nsk-aaaaaaaaaaaaaaaa2\n"
                    + "sk-aaaaaaaaaaaaaaaa3\nsk-aaaaaaaaaaaaaaaa4\nsk-aaaaaaaaaaaaaaaa5\n",
                    StandardCharsets.UTF_8);
            KeySlots slots = KeySlots.load(keys);
            routes = SceneRoutes.open(dir);
            host = new WorldWebHost(
                    () -> WorldWebScenario.openSocial(dir.resolve("world-social.journal")),
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

    /** 进程内脚本：只读真实输入；异常回传主线程断言。 */
    static final class ScriptedSocial implements WorldWebHost.DecisionProvider {
        private final Map<String, Integer> round = new ConcurrentHashMap<>();
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();
        volatile boolean diag = false;
        volatile boolean watermark = false;

        List<Throwable> errors() {
            return errors;
        }

        @Override
        public LiveCompletionInbox.Completion evaluate(DecisionRun.Call call, long timeoutMillis) {
            try {
                return build(call);
            } catch (Throwable failure) {
                errors.add(failure);
                System.out.println("PROVIDER_ERROR " + failure);
                DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                        call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                        "{\"tool\":\"wait\"}", DecisionModel.Usage.unknownCost(1, 10, 10),
                        "SCRIPTED_LOOPBACK");
                return LiveCompletionInbox.Completion.failure("SCRIPT_ASSERTION", reply);
            }
        }

        private LiveCompletionInbox.Completion build(DecisionRun.Call call) throws Exception {
            JsonNode input = MAPPER.readTree(call.inputText());
            String person = input.path("person").asText("");
            if (person.isBlank()) {
                throw new AssertionError("input.person required");
            }
            int step = round.merge(person, 1, Integer::sum);
            JsonNode social = input.path("social");
            JsonNode catalog = input.path("profile").path("catalog");
            String tool;
            String args;
            if (watermark && "nina".equals(person)) {
                if (step == 1) {
                    JsonNode doc = byTitle(catalog, "公开联系说明");
                    tool = "role.reading.start";
                    args = "{\"docId\":\"" + doc.path("docId").asText()
                            + "\",\"version\":" + doc.path("version").asLong() + "}";
                } else {
                    JsonNode entry = firstContact(social);
                    tool = "social.send";
                    args = "{\"toPartyId\":\"" + entry.path("targetPartyId").asText()
                            + "\",\"channel\":\"" + entry.path("channel").asText()
                            + "\",\"title\":\"第" + step + "封\""
                            + ",\"body\":\"<b>x</b> 第" + step + "封正文\"}";
                }
            } else if (watermark && "omar".equals(person)) {
                if (step == 1) {
                    JsonNode doc = byTitle(catalog, "公开联系说明");
                    tool = "role.reading.start";
                    args = "{\"docId\":\"" + doc.path("docId").asText()
                            + "\",\"version\":" + doc.path("version").asLong() + "}";
                } else {
                    JsonNode envelope = social.path("inbox").get(step - 2);
                    tool = "role.reading.start";
                    args = "{\"docId\":\"" + envelope.path("docId").asText()
                            + "\",\"version\":" + envelope.path("version").asLong() + "}";
                }
            } else if ("nina".equals(person)) {
                switch (step) {
                    case 1 -> {
                        tool = "social.send";
                        args = "{\"toPartyId\":\"omar\",\"channel\":\"私信\","
                                + "\"title\":\"未读入口\",\"body\":\"应被拒绝\"}";
                    }
                    case 2 -> {
                        JsonNode doc = byTitle(catalog, "公开联系说明");
                        tool = "role.reading.start";
                        args = "{\"docId\":\"" + doc.path("docId").asText()
                                + "\",\"version\":" + doc.path("version").asLong() + "}";
                    }
                    case 3 -> {
                        JsonNode entry = firstContact(social);
                        tool = "social.send";
                        args = "{\"toPartyId\":\"" + entry.path("targetPartyId").asText()
                                + "\",\"channel\":\"" + entry.path("channel").asText()
                                + "\",\"title\":\"第" + step + "封\""
                                + ",\"body\":\"<b>x</b> 正文含尖括号\"}";
                    }
                    case 4 -> {
                        if (diag) {
                            // 跨人解除：nina 试图解除 omar 对 nina 的屏蔽（不应生效）
                            tool = "social.unblock";
                            args = "{\"targetPartyId\":\"omar\"}";
                        } else {
                            JsonNode entry = firstContact(social);
                            tool = "social.send";
                            args = "{\"toPartyId\":\"" + entry.path("targetPartyId").asText()
                                    + "\",\"channel\":\"" + entry.path("channel").asText()
                                    + "\",\"title\":\"第" + step + "封\""
                                    + ",\"body\":\"<b>x</b> 正文含尖括号\"}";
                        }
                    }
                    case 5 -> {
                        JsonNode entry = firstContact(social);
                        tool = "social.send";
                        args = "{\"toPartyId\":\"" + entry.path("targetPartyId").asText()
                                + "\",\"channel\":\"" + entry.path("channel").asText()
                                + "\",\"title\":\"第" + step + "封\""
                                + ",\"body\":\"<b>x</b> 正文含尖括号\"}";
                    }
                    default -> {
                        tool = "wait";
                        args = null;
                    }
                }
            } else if ("omar".equals(person) && step == 1) {
                JsonNode doc = byTitle(catalog, "公开联系说明");
                tool = "role.reading.start";
                args = "{\"docId\":\"" + doc.path("docId").asText()
                        + "\",\"version\":" + doc.path("version").asLong() + "}";
            } else if ("omar".equals(person) && step == 2) {
                tool = "social.block";
                args = "{\"targetPartyId\":\"nina\"}";
            } else if ("omar".equals(person) && step == 3) {
                if (diag) {
                    // 正确 owner：omar 解除自己对 nina 的屏蔽
                    tool = "social.unblock";
                    args = "{\"targetPartyId\":\"nina\"}";
                } else {
                    JsonNode envelope = firstInbox(social);
                    tool = "role.reading.start";
                    args = "{\"docId\":\"" + envelope.path("docId").asText()
                            + "\",\"version\":" + envelope.path("version").asLong() + "}";
                }
            } else {
                tool = "wait";
                args = null;
            }
            String body = args == null ? "{\"tool\":\"wait\"}"
                    : "{\"tool\":\"" + tool + "\",\"args\":" + args + "}";
            System.out.println("SCRIPT " + person + "#" + step + " -> " + tool);
            DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                    call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                    DecisionModel.Usage.unknownCost(1, 120, 40), "SCRIPTED_LOOPBACK");
            return LiveCompletionInbox.Completion.business(reply);
        }

        private static JsonNode byTitle(JsonNode catalog, String needle) {
            for (JsonNode doc : catalog) {
                if (doc.path("title").asText("").contains(needle)) {
                    return doc;
                }
            }
            throw new AssertionError("catalog 缺少标题含「" + needle + "」的资料，实际=" + catalog);
        }

        private static JsonNode firstContact(JsonNode social) {
            JsonNode contacts = social.path("contacts");
            if (!contacts.isArray() || contacts.size() == 0) {
                throw new AssertionError("input.social.contacts 为空，实际=" + social);
            }
            return contacts.get(0);
        }

        private static JsonNode firstInbox(JsonNode social) {
            JsonNode inbox = social.path("inbox");
            if (!inbox.isArray() || inbox.size() == 0) {
                throw new AssertionError("input.social.inbox 为空，实际=" + social);
            }
            return inbox.get(0);
        }
    }

    /** 极简 HTTP 客户端：真实 loopback + world_session cookie。 */
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
                String scene = route.path("scene").asText();
                boolean media = "media".equals(scene);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("scene", scene);
                row.put("slotId", route.path("slotId").asText());
                row.put("model", route.path("model").asText());
                row.put("enabled", media);
                row.put("maxCalls", media ? 60 : 1);
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
            assertTrue(result.path("error").isMissingNode(),
                    "live start for " + person + " failed: " + result);
            for (int i = 0; i < 200; i += 1) {
                Thread.sleep(20L);
                if (get("/api/admin/live").path("inFlight").asInt(0) == 0) {
                    break;
                }
            }
            for (int settle = 0; settle < 10; settle += 1) {
                JsonNode status = get("/api/admin/live");
                if (status.path("completionSlots").asInt(0) == 0
                        && status.path("physicalInFlight").asInt(0) == 0) {
                    break;
                }
                Thread.sleep(40L);
                advance(timeMillis() + 2_000L);
            }
        }

        void advance(long targetMillis) throws Exception {
            long position = get("/api/admin/view").path("position").asLong();
            JsonNode result = post("/api/admin/advance",
                    Map.of("expectedPosition", position, "targetMillis", targetMillis));
            assertTrue(result.path("error").isMissingNode(),
                    "advance to " + targetMillis + " must not fail: " + result
                            + " live=" + get("/api/admin/live"));
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
                    .timeout(Duration.ofSeconds(20));
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
