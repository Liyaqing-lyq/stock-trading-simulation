package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.WorldRuntime;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 私信/直投源→群帖→公开转述的真实引用例：目标群成员无原私源权限时，
 * 人物 API 不得泄私源标识/身份/正文；God 审计保留唯一真实根。
 */
class GroupsPrivateSourceWebTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "groups-private-bootstrap";
    private static final List<String> KEYS = List.of("sk-10000000000000000000000000000001",
            "sk-10000000000000000000000000000002", "sk-10000000000000000000000000000003",
            "sk-10000000000000000000000000000004", "sk-10000000000000000000000000000005");

    private Path workDir;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;
    private String adminBootstrap = BOOTSTRAP;
    private final List<String> consoleLines = new ArrayList<>();
    private String directDoc;
    private String groupDoc;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("web.groups.private.work",
                "target/web-groups-private"));
        workDir = root.resolve("private-" + System.nanoTime());
        Files.createDirectories(workDir);
        Path journal = workDir.resolve("private.journal");
        // 世界层先造真实链路：DIRECT 资料 → alice 读完 → alice 群帖引用 → bob 读群帖 → bob 公开转述
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(journal)) {
            long now = world.snapshot().timeMillis();
            world.schedulePublishDocument("direct-1", "direct-1-task", now + 1, "direct-doc",
                    WorldWebScenario.INVESTOR,
                    InformationModel.Audience.direct("alice"),
                    "私信正文标题", "只应给 alice 的私信正文", "直投说明", 10L, List.of());
            world.advanceTo(now + 1);
            long t = world.snapshot().timeMillis();
            world.scheduleStartReading("read-direct", "read-direct-task", t + 10, "alice",
                    "direct-doc", 1L);
            world.advanceTo(t + 20);
            directDoc = "direct-doc";
            long g = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToGroup("gpost", "gpost-task", g + 20,
                    new edu.cufe.auction.world.WorldRoleSession.GroupPostDraft(
                            WorldWebScenario.GROUPS_OPEN, "转述私信", "群内转述（不含私信原文）",
                            List.of(new edu.cufe.auction.world.WorldRoleSession.Citation(
                                    directDoc, 1L))));
            world.advanceTo(g + 20);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            groupDoc = world.groupsRun().state().posts().get(0).docId();
            long r = world.snapshot().timeMillis();
            world.scheduleStartReading("read-group", "read-group-task", r + 10, "bob",
                    groupDoc, 1L);
            world.advanceTo(r + 20);
            long p = world.snapshot().timeMillis();
            world.openRoleSession("bob").postToPlatform("repost", "repost-task", p + 15,
                    new edu.cufe.auction.world.WorldRoleSession.PlatformPostDraft(
                            "公开转述", "公开文本，不含私信原文",
                            List.of(new edu.cufe.auction.world.WorldRoleSession.Citation(
                                    groupDoc, 1L))));
            world.advanceTo(p + 15);
            world.advanceTo(world.snapshot().timeMillis() + 5);
        }
        Path keys = workDir.resolve("keys.txt");
        Files.writeString(keys, String.join("\n", KEYS), StandardCharsets.UTF_8);
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
    @DisplayName("私源转述：人物页不泄私源 doc/身份/正文，God 审计只有一份真实根")
    void privateSourceIsNotLeakedToGroupMembers() throws Exception {
        loginAdmin();
        JsonNode god = getJson("/api/admin/groups");
        JsonNode post = postRow(god, groupDoc);
        assertTrue(post.path("declaredRefs").toString().contains(directDoc),
                "God 必须保留真实直接来源：" + post);
        JsonNode platform = god.path("platformPosts").get(0);
        assertEquals(1, platform.path("originRefs").size(),
                "公开转述只能有唯一原始根：" + platform);
        assertTrue(platform.path("originRefs").toString().contains(directDoc),
                "唯一根就是私源本身（审计），不是转述者");

        // bob（群成员、转述者，但无 DIRECT 权限）：页面不得出现私源标识或正文
        personAt("bob");
        String bobPage = getJson("/api/me/groups").toString();
        assertFalse(bobPage.contains(directDoc), "群成员页面不得出现私源 docId");
        assertFalse(bobPage.contains("只应给 alice 的私信正文"), "不得出现私源正文");
        JsonNode bobPost = null;
        for (JsonNode row : getJson("/api/me/groups").path("posts")) {
            if (row.path("docId").asText().equals(groupDoc)) {
                bobPost = row;
            }
        }
        assertTrue(bobPost != null && bobPost.path("refs").isEmpty(),
                "无原私源权限的成员页面不得给出任何来源表示");

        // carol（同群成员，未读私源）：同样看不到
        personAt("carol");
        String carolPage = getJson("/api/me/groups").toString();
        assertFalse(carolPage.contains(directDoc));
        assertFalse(carolPage.contains("只应给 alice 的私信正文"));
    }

    private static JsonNode postRow(JsonNode god, String docId) {
        for (JsonNode row : god.path("posts")) {
            if (row.path("docId").asText().equals(docId)) {
                return row;
            }
        }
        throw new AssertionError("God 没有该群帖：" + docId);
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        HttpResponse<String> response = HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Origin", base)
                .header("X-World-Request", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookieOf(HttpResponse<String> response) {
        String raw = response.headers().firstValue("set-cookie").orElseThrow(
                () -> new AssertionError("缺少 set-cookie"));
        return raw.split(";", 2)[0];
    }
}
