package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema20 群组 Web 定向链：真实回环 HTTP、真实会话 cookie/CSRF/expectedPosition，
 * 邀请→接受→发帖→他人读完→公开转述→退群隐藏，并以 God 审计与 401/403/no-leak 对照。
 */
class GroupsWorldWebHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "groups-web-bootstrap";
    private static final List<String> DUMMY_KEYS = List.of("sk-00000000000000000000000000000001",
            "sk-00000000000000000000000000000002", "sk-00000000000000000000000000000003", "sk-00000000000000000000000000000004", "sk-00000000000000000000000000000005");

    private Path workDir;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;
    private String adminBootstrap = BOOTSTRAP;
    private final List<String> consoleLines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("web.groups.work", "target/web-groups-tests"));
        workDir = root.resolve("groups-" + System.nanoTime());
        Files.createDirectories(workDir);
        Path journal = workDir.resolve("groups.journal");
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

    // ------------------------------------------------------------ 主链

    @Test
    @DisplayName("HTTP 群链：邀请→接受→发帖→他人读→公开转述→退群隐藏，God 与 no-leak 对照")
    void inviteJoinPostReadRepostLeave() throws Exception {
        loginAdmin();
        JsonNode god = getJson("/api/admin/groups");
        assertTrue(god.path("enabled").asBoolean());
        assertEquals(2, god.path("groups").size(), "预置两个三人群");

        // bob（仅邀请群主）邀请 dave：真实正工时
        personAt("bob");
        JsonNode bobPage = getJson("/api/me/groups");
        long dueInvite = dueOf(bobPage, "INVITE");
        JsonNode inviteReceipt = postJson("/api/me/groups/action", groupBody(bobPage,
                "INVITE", "{\"groupId\":\"" + WorldWebScenario.GROUPS_INVITE_ONLY
                        + "\",\"inviteePartyId\":\"dave\",\"dueTimeMillis\":\"" + dueInvite + "\"}"));
        assertTrue(inviteReceipt.path("receipt").asText().startsWith("SCHEDULED"));
        // 异步：排程后还没有邀请记录
        assertEquals(0, godJson().path("invitations").size(), "排程不等于已完成");
        advanceTo(Long.toString(dueInvite));

        // dave 看到发给自己的邀请并接受
        personAt("dave");
        JsonNode davePage = getJson("/api/me/groups");
        assertEquals(1, davePage.path("invitations").size(), "受邀人可见真实邀请");
        String inviteId = davePage.path("invitations").get(0).path("inviteId").asText();
        long dueRespond = dueOf(davePage, "RESPOND");
        postJson("/api/me/groups/action", groupBody(davePage, "RESPOND",
                "{\"inviteId\":\"" + inviteId + "\",\"accept\":true,\"dueTimeMillis\":\""
                        + dueRespond + "\"}"));
        advanceTo(Long.toString(dueRespond));
        assertTrue(membershipOf("dave"), "接受后建立成员段");

        // dave 发帖；完成 + 投递等待后才生成正文
        personAt("dave");
        JsonNode page = getJson("/api/me/groups");
        long duePost = dueOf(page, "POST");
        postJson("/api/me/groups/action", groupBody(page, "POST",
                "{\"groupId\":\"" + WorldWebScenario.GROUPS_INVITE_ONLY
                        + "\",\"title\":\"群内线索\",\"body\":\"只应留在群内的正文\","
                        + "\"dueTimeMillis\":\"" + duePost + "\"}"));
        assertEquals(0, godJson().path("posts").size(), "SCHEDULED 不得冒充已发布");
        advanceTo(Long.toString(duePost));
        advanceTo(Long.toString(duePost + 5));
        JsonNode published = godJson();
        assertEquals(1, published.path("posts").size());
        String groupDoc = published.path("posts").get(0).path("docId").asText();

        // bob（同群）真实读完；正文只在读完后出现
        personAt("bob");
        JsonNode bobBefore = getJson("/api/me/groups");
        JsonNode postBefore = postOf(bobBefore, groupDoc);
        assertTrue(postBefore.path("body").isNull(), "未读不得下发正文");
        long readDue = Long.parseLong(bobBefore.path("timeMillis").asText()) + 1;
        postJson("/api/me/read", "{\"expectedPosition\":\"" + bobBefore.path("position").asText()
                + "\",\"commandId\":\"web-read-g-" + System.nanoTime()
                + "\",\"taskId\":\"web-read-g-task-" + System.nanoTime()
                + "\",\"dueTimeMillis\":\"" + readDue + "\",\"docId\":\"" + groupDoc
                + "\",\"version\":\"1\"}");
        advanceTo(Long.toString(readDue + 20));
        personAt("bob");
        JsonNode bobAfter = getJson("/api/me/groups");
        assertEquals("只应留在群内的正文", postOf(bobAfter, groupDoc).path("body").asText(),
                "读完且可回忆才给正文");

        // bob 公开转述：PUBLIC 平台帖；alice（非群成员）只能看到公开文
        personAt("bob");
        JsonNode repostPage = getJson("/api/me/groups");
        long dueRepost = dueOf(repostPage, "PLATFORM_POST");
        postJson("/api/me/groups/action", groupBody(repostPage, "PLATFORM_POST",
                "{\"title\":\"公开转述\",\"body\":\"公开可见的转述文本\",\"cites\":[{\"docId\":\""
                        + groupDoc + "\",\"version\":\"1\"}],\"dueTimeMillis\":\""
                        + dueRepost + "\"}"));
        advanceTo(Long.toString(dueRepost));
        advanceTo(Long.toString(dueRepost + 5));
        String platformDoc = godJson().path("platformPosts").get(0).path("docId").asText();

        personAt("erin");
        JsonNode outsider = getJson("/api/me/groups");
        assertEquals(0, outsider.path("groups").size(), "非成员不得看到该群");
        assertEquals(0, outsider.path("invitations").size());
        assertEquals(0, outsider.path("posts").size(), "非成员不得看到群帖");
        assertTrue(outsider.toString().contains(platformDoc), "公开转述必须可见");
        assertFalse(outsider.toString().contains(groupDoc), "公开页不得出现群帖标识");
        assertFalse(outsider.toString().contains("只应留在群内的正文"), "公开页不得出现私正文");

        // 非成员更正他人群帖：与不存在帖同一不可用，且不泄露存在性
        long dueCorrect = dueOf(outsider, "CORRECTION");
        String existing = postJson("/api/me/groups/action", groupBody(outsider, "CORRECTION",
                "{\"docId\":\"" + groupDoc + "\",\"basedOnVersion\":\"1\",\"title\":\"x\","
                        + "\"body\":\"y\",\"dueTimeMillis\":\"" + dueCorrect + "\"}"))
                .path("receipt").asText();
        long dueUnknown = Long.parseLong(outsider.path("timeMillis").asText())
                + Long.parseLong(outsider.path("actionMillis").path("CORRECTION").asText());
        JsonNode outsider2 = getJson("/api/me/groups");
        String unknown = postJson("/api/me/groups/action", groupBody(outsider2, "CORRECTION",
                "{\"docId\":\"GDOC-forged-guess\",\"basedOnVersion\":\"1\",\"title\":\"x\","
                        + "\"body\":\"y\",\"dueTimeMillis\":\"" + dueUnknown + "\"}"))
                .path("receipt").asText();
        assertEquals("NOT_AVAILABLE", existing);
        assertEquals(existing, unknown, "存在与不存在同一结果");

        // 退群：dave 离开后本人页不再有群/帖；God 保留成员段与已发帖
        personAt("dave");
        JsonNode daveBeforeLeave = getJson("/api/me/groups");
        long dueLeave = dueOf(daveBeforeLeave, "LEAVE");
        postJson("/api/me/groups/action", groupBody(daveBeforeLeave, "LEAVE",
                "{\"groupId\":\"" + WorldWebScenario.GROUPS_INVITE_ONLY
                        + "\",\"dueTimeMillis\":\"" + dueLeave + "\"}"));
        advanceTo(Long.toString(dueLeave));
        personAt("dave");
        JsonNode daveAfter = getJson("/api/me/groups");
        assertEquals(0, daveAfter.path("groups").size(), "退群后不再显示该群");
        assertEquals(0, daveAfter.path("posts").size(), "退群后不再新访问群帖");
        JsonNode finalGod = godJson();
        assertTrue(finalGod.path("memberships").toString().contains("\"leftAt\""),
                "God 必须保留关闭的成员段");
        assertEquals(1, finalGod.path("posts").size(), "已合法投递的帖子保留");

        // God 与 no-leak：God 可见真实来源根与报告，人物页没有
        assertTrue(finalGod.toString().contains(groupDoc), "God 审计保留真实根");
        assertFalse(outsider.toString().contains("\"originRefs\""), "人物页无来源根字段");
    }

    @Test
    @DisplayName("鉴权与协议：401/403、GET 不推进、同命令重放与异体拒绝")
    void authAndProtocolBoundaries() throws Exception {
        loginAdmin();
        long position = Long.parseLong(getJson("/api/admin/groups").path("position").asText());
        for (int i = 0; i < 3; i++) {
            getJson("/api/admin/groups");
            getJson("/api/admin/view");
        }
        assertEquals(position, Long.parseLong(getJson("/api/admin/groups")
                .path("position").asText()), "只读 GET 不得改变日志位置");

        // 无 cookie：401
        HttpResponse<String> anon = rawGet("/api/me/groups", null);
        assertEquals(401, anon.statusCode());
        // 人物访问 God 路由：403
        personAt("alice");
        assertEquals(403, rawGet("/api/admin/groups", cookie).statusCode());
        // 人物 body 自填身份字段：400（不接受人物）
        JsonNode page = getJson("/api/me/groups");
        HttpResponse<String> forged = rawPost("/api/me/groups/action",
                "{\"expectedPosition\":\"" + page.path("position").asText()
                        + "\",\"commandId\":\"x\",\"taskId\":\"xt\",\"dueTimeMillis\":\"1\","
                        + "\"action\":\"JOIN\",\"groupId\":\"g\",\"personPartyId\":\"bob\"}", cookie);
        assertEquals(400, forged.statusCode(), "不得接受自填身份");

        // 同命令同内容幂等，异体拒绝
        long due = dueOf(page, "JOIN");
        String body = groupBody(page, "JOIN", "{\"groupId\":\""
                + WorldWebScenario.GROUPS_OPEN + "\",\"dueTimeMillis\":\"" + due + "\"}");
        JsonNode first = postJson("/api/me/groups/action", body);
        JsonNode replayPage = getJson("/api/me/groups");
        String replayBody = body.replaceFirst("\"expectedPosition\":\"[0-9]+\"",
                "\"expectedPosition\":\"" + replayPage.path("position").asText() + "\"");
        JsonNode replay = postJson("/api/me/groups/action", replayBody);
        assertEquals(first.path("receipt").asText(), replay.path("receipt").asText());
        HttpResponse<String> conflict = rawPost("/api/me/groups/action", replayBody
                .replace("\"group:" + "open\"", "\"group:other\""), cookie);
        assertEquals(400, conflict.statusCode(), "同命令异体必须拒绝");
    }

    // ------------------------------------------------------------ 辅助

    private JsonNode godJson() throws Exception {
        loginAdmin();
        return getJson("/api/admin/groups");
    }

    private boolean membershipOf(String person) throws Exception {
        for (JsonNode row : godJson().path("memberships")) {
            if (row.path("person").asText().equals(person) && row.path("leftAt").asText().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private JsonNode postOf(JsonNode page, String docId) {
        for (JsonNode row : page.path("posts")) {
            if (row.path("docId").asText().equals(docId)) {
                return row;
            }
        }
        throw new AssertionError("页面没有群帖：" + docId);
    }

    private long dueOf(JsonNode page, String action) {
        return Long.parseLong(page.path("timeMillis").asText())
                + Long.parseLong(page.path("actionMillis").path(action).asText());
    }

    private String groupBody(JsonNode page, String action, String fields) {
        String suffix = "web-g-" + action + "-" + System.nanoTime();
        return "{\"expectedPosition\":\"" + page.path("position").asText()
                + "\",\"commandId\":\"" + suffix + "\",\"taskId\":\"" + suffix + "-task\","
                + "\"action\":\"" + action + "\"," + fields.substring(1);
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

    private void advanceTo(String target) throws Exception {
        loginAdmin();
        JsonNode view = getJson("/api/admin/view");
        long current = Long.parseLong(view.path("timeMillis").asText());
        long wanted = Long.parseLong(target);
        long effective = Math.max(wanted, current + 1);
        postJson("/api/admin/advance", "{\"expectedPosition\":\""
                + view.path("position").asText() + "\",\"targetMillis\":\"" + effective + "\"}");
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = rawGet(path, cookie);
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

    private HttpResponse<String> rawGet(String path, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
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
