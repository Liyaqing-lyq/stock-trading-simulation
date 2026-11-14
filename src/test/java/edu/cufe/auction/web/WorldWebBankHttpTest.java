package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
 * 组合档银行 Web 第一切片：真实回环 HTTP + 真实会话，覆盖 God 审计、本人受限读取、
 * 三方权限（God/未登录/人物）、旧档缺银行与位置绑定。
 *
 * <p>全部测试只用测试假 key，不启动真实模型；服务端口 0（系统分配），不占用 18080。</p>
 */
class WorldWebBankHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "bank-web-bootstrap";
    private static final List<String> DUMMY_KEYS = List.of("sk-webbank-00000001",
            "sk-webbank-00000002", "sk-webbank-00000003", "sk-webbank-00000004",
            "sk-webbank-00000005");

    private Path workDir;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;
    private String adminBootstrap = BOOTSTRAP;
    private final List<String> consoleLines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("web.bank.work", "target/web-bank-tests"));
        workDir = root.resolve("composed-" + System.nanoTime());
        Files.createDirectories(workDir);
        Path keys = workDir.resolve("keys.txt");
        Files.writeString(keys, String.join("\n", DUMMY_KEYS), StandardCharsets.UTF_8);
        host = new WorldWebHost(() -> WorldWebScenario.openComposed(
                workDir.resolve("world-composed.journal")));
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
    @DisplayName("God 银行审计：同一快照的准备金/存款/权益/门槛/托管/回执，且不泄露 key")
    void godAuditUsesComposedBank() throws Exception {
        loginAdmin();
        JsonNode view = getJson("/api/admin/view");
        String position = view.path("position").asText();

        HttpResponse<String> response = rawGet("/api/admin/bank", cookie);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode bank = JSON.readTree(response.body());
        assertTrue(bank.path("available").asBoolean(), "组合档必须启用银行");
        assertEquals("web-composed", bank.path("runId").asText());
        assertEquals(position, bank.path("position").asText(), "银行页必须绑定同一确认位置");
        assertEquals(view.path("timeMillis").asText(), bank.path("timeMillis").asText(),
                "银行页必须绑定同一世界时点");

        JsonNode banks = bank.path("banks");
        assertEquals(1, banks.size(), "组合档只有一家银行");
        JsonNode north = banks.get(0);
        assertEquals("north", north.path("bankKey").asText());
        assertEquals("3000.00", north.path("reserve").asText(), "准备金=真实 RSV-NORTH");
        assertEquals("0.00", north.path("depositsTotal").asText(), "开局没有存款负债");
        assertEquals("3000.00", north.path("equity").path("bookEquity").asText(),
                "银行 A=L+E：3000 = 0 + 3000");
        assertEquals(10, north.path("gate").path("metrics").size(), "十项门槛指标齐全");
        assertEquals(10, bank.path("archive").path("required").size(), "声明宇宙十个指标");
        assertEquals("names_and_links_only_unverified",
                bank.path("archive").path("provenanceStatus").asText());
        assertTrue(bank.path("archive").path("experimentOnly").asBoolean(), "门槛是 EXP 实验");

        JsonNode custody = bank.path("custody");
        assertFalse(custody.path("enabled").asBoolean(), "组合档未启用托管人");
        assertFalse(custody.path("reason").asText().isBlank(), "未启用托管必须说明原因");
        assertEquals(0, custody.path("pools").size(), "不得伪造托管池");
        assertTrue(bank.path("actions").isArray(), "动作回执必须是数组");
        assertEquals(0, bank.path("actions").size(), "开局还没有银行动作");

        // 任何响应都不得包含真实风格 key 值
        for (String key : DUMMY_KEYS) {
            assertFalse(response.body().contains(key), "银行审计不得泄露 key");
        }
    }

    @Test
    @DisplayName("符号与单位规则：贷方取正但绝不取绝对值，利率/期限保留原值可审计")
    void displayRulesKeepSignsAndUnits() {
        // 正常方向：负债/权益/收入按贷方取正
        assertEquals("0.56", WorldWebHost.normalAmount("LIABILITY",
                new BigDecimal("-0.56")).toPlainString());
        assertEquals("3000.00", WorldWebHost.normalAmount("EQUITY",
                new BigDecimal("-3000.00")).toPlainString());
        // 异常/反向余额必须保持负数，不能被 abs 抹掉
        assertEquals("-0.10", WorldWebHost.normalAmount("LIABILITY",
                new BigDecimal("0.10")).toPlainString(), "负债出现借方余额必须显示负数");
        assertEquals("-0.33", WorldWebHost.normalAmount("INCOME",
                new BigDecimal("0.33")).toPlainString(), "收入冲回必须显示负收入");
        assertEquals("-100.00", WorldWebHost.normalAmount("EQUITY",
                new BigDecimal("100.00")).toPlainString(), "负权益必须保持负数");
        assertEquals("0.33", WorldWebHost.normalAmount("ASSET",
                new BigDecimal("0.33")).toPlainString());
        assertEquals("0.33", WorldWebHost.normalAmount("EXPENSE",
                new BigDecimal("0.33")).toPlainString());

        // 单位：定标 1e6 利率与毫秒期限
        assertEquals("2%", WorldWebHost.rateText(20_000L));
        assertEquals("5%", WorldWebHost.rateText(50_000L));
        assertEquals("1.25%", WorldWebHost.rateText(12_500L));
        assertEquals("0.01%", WorldWebHost.rateText(100L));
        assertEquals("30天", WorldWebHost.tenorText(2_592_000_000L));
        assertEquals("1天", WorldWebHost.tenorText(86_400_000L));
        assertEquals("1小时", WorldWebHost.tenorText(3_600_000L));
        assertEquals("—", WorldWebHost.tenorText(0L));

        // 阶段：STARTED 只是开始；批准不等于放款；拒绝独立
        assertEquals("STARTED", WorldWebHost.actionStage("DEPOSIT", true, "STARTED"));
        assertEquals("已开始，待完成", WorldWebHost.actionStageLabel("STARTED"));
        assertEquals("PROPOSED",
                WorldWebHost.actionStage("LOAN_REQUEST", true, "LOAN_PROPOSED"));
        assertEquals("ACCEPTED", WorldWebHost.actionStage("LOAN_ACCEPT", true, "LOAN_ACCEPTED"));
        assertEquals("APPROVED",
                WorldWebHost.actionStage("LOAN_APPROVE", true, "LOAN_APPROVED"));
        assertEquals("银行已批准（未放款）",
                WorldWebHost.actionStageLabel("APPROVED"));
        assertEquals("DRAWN", WorldWebHost.actionStage("LOAN_DRAW", true, "LOAN_DRAWN"));
        assertEquals("已放款", WorldWebHost.actionStageLabel("DRAWN"));
        assertEquals("REJECTED", WorldWebHost.actionStage("LOAN_DRAW", false, "NOT_AUTHORIZED"));
        assertEquals("拒绝", WorldWebHost.actionStageLabel("REJECTED"));
        // 未登记的成功原因码：保留原码，不推断放款/完成
        assertEquals("UNMAPPED", WorldWebHost.actionStage("LOAN_DRAW", true, "SOME_NEW_CODE"));
        assertEquals("UNMAPPED", WorldWebHost.actionStage("LOAN_APPROVE", true, "ACCEPTED"));
        assertEquals("该帧成立（阶段未映射，按原码核对）",
                WorldWebHost.actionStageLabel("UNMAPPED"));
        // 非贷款普通成立动作仍可记为完成（核心回执已建立结果）
        assertEquals("DONE", WorldWebHost.actionStage("DEPOSIT", true, "ACCEPTED"));
    }

    @Test
    @DisplayName("BigDecimal 汇总：超过 2^53 分的合法金额仍返回精确十进制字符串")
    void onbookSumKeepsDecimalPrecision() {
        // 2^53 分 = 90071992547409.92 元；JS Number 的整分累加在这里就会丢精度
        java.util.Map<String, Object> big = new java.util.HashMap<>();
        big.put("onbook", "90071992547409.92");
        java.util.Map<String, Object> cent = new java.util.HashMap<>();
        cent.put("onbook", "0.01");
        java.util.Map<String, Object> zero = new java.util.HashMap<>();
        zero.put("onbook", null);
        assertEquals("90071992547409.92", WorldWebHost.sumOnbookColumn(List.of(big)),
                "单笔大额必须原样精确");
        assertEquals("90071992547409.93", WorldWebHost.sumOnbookColumn(List.of(big, cent)),
                "跨 2^53 分的累加必须精确，不能变成 …410 或科学计数法");
        assertEquals("0.00", WorldWebHost.sumOnbookColumn(List.of()),
                "空集合计为零，不是缺项");
        assertEquals("90071992547409.93",
                WorldWebHost.sumOnbookColumn(List.of(zero, big, cent)),
                "空行不影响合计");
        assertEquals("180143985094819.84",
                WorldWebHost.sumOnbookColumn(List.of(big, big)),
                "双倍大额仍精确");
    }

    @Test
    @DisplayName("未登录：两个银行入口都 401，不返回任何账")
    void unauthenticatedIsRejected() throws Exception {
        assertEquals(401, rawGet("/api/admin/bank", null).statusCode());
        assertEquals(401, rawGet("/api/me/bank", null).statusCode());
    }

    @Test
    @DisplayName("人物只读本人：owner 由真实账户绑定派生，跨权限 403")
    void personSeesOnlyOwnBankView() throws Exception {
        loginAdmin();
        personAt("investor");
        HttpResponse<String> response = rawGet("/api/me/bank", cookie);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode view = JSON.readTree(response.body());
        assertEquals("investor", view.path("personPartyId").asText());
        assertTrue(view.path("available").asBoolean());
        assertFalse(view.path("owners").isEmpty(), "investor 有真实绑定的财产主体");
        boolean hasInvestor = false;
        boolean hasProductOwner = false;
        for (JsonNode owner : view.path("owners")) {
            if ("investor".equals(owner.path("ownerId").asText())) {
                hasInvestor = true;
            }
            if ("fund-a".equals(owner.path("ownerId").asText())) {
                hasProductOwner = true;
            }
        }
        assertTrue(hasInvestor, "investor 的投影必须来自真实 owner investor");
        assertFalse(hasProductOwner, "investor 不得凭空看到产品账户 fund-a");
        for (JsonNode action : view.path("actions")) {
            assertEquals("investor", action.path("personPartyId").asText(),
                    "本人视图不得包含别人的动作");
        }
        assertTrue(view.path("actionsEnabled").asBoolean(), "本人银行动作已接到真实角色会话");
        assertFalse(view.path("actionsReason").asText().isBlank(), "动作语义必须说明");
        assertFalse(view.path("entitlements").isEmpty(), "必须列出真实可用动作");
        assertFalse(view.has("position"), "本人响应不得带全局批次位置");
        for (JsonNode action : view.path("actions")) {
            assertFalse(action.has("position"), "本人动作回执不得带全局位置");
        }
        assertTrue(view.has("timeMillis"), "本人响应仍给世界时点用于显示新鲜度");

        // 带 owner/person 参数的篡改请求仍只返回本人（参数被忽略，不构成换人入口）
        JsonNode tampered = getJson("/api/me/bank?ownerId=fund-a&personId=bob&owner=north-owner");
        assertEquals("investor", tampered.path("personPartyId").asText(),
                "URL 参数不得改变会话绑定的人物");
        for (JsonNode owner : tampered.path("owners")) {
            assertNotEquals("fund-a", owner.path("ownerId").asText(),
                    "URL 参数不得把别人的账户塞进本人视图");
        }
        // 带 body 的 POST 不被接受（只读 GET）
        assertEquals(404, rawPost("/api/me/bank",
                "{\"personId\":\"bob\",\"ownerId\":\"fund-a\"}", cookie).statusCode());

        // 人物会话访问上帝审计必须 403
        assertEquals(403, rawGet("/api/admin/bank", cookie).statusCode());
    }

    @Test
    @DisplayName("产品管理授权：owner 来自 roleView 授权的产品账户，而不是银行档案里的名字")
    void productManagerSeesAuthorizedProductOwner() throws Exception {
        loginAdmin();
        personAt("bob");
        JsonNode view = getJson("/api/me/bank");
        assertEquals("bob", view.path("personPartyId").asText());
        boolean hasProductOwner = false;
        boolean hasInvestor = false;
        for (JsonNode owner : view.path("owners")) {
            if ("fund-a".equals(owner.path("ownerId").asText())) {
                hasProductOwner = true;
                assertEquals("1000.00", owner.path("cash").asText(),
                        "fund-a 现金必须来自真实现金账户");
            }
            if ("investor".equals(owner.path("ownerId").asText())) {
                hasInvestor = true;
            }
        }
        assertTrue(hasProductOwner, "bob 的任职授权 fund-a 产品账户，必须出现在本人视图");
        assertFalse(hasInvestor, "bob 不得看到与本人无关的 investor");
    }

    @Test
    @DisplayName("上帝会话访问本人入口：403（身份不通用）")
    void adminCannotUsePersonEndpoint() throws Exception {
        loginAdmin();
        assertEquals(403, rawGet("/api/me/bank", cookie).statusCode());
    }

    @Test
    @DisplayName("旧档缺银行：available=false 且不返回零余额银行")
    void legacyArchiveHasNoBank() throws Exception {
        Path legacy = workDir.resolve("legacy");
        Files.createDirectories(legacy);
        Path keys = workDir.resolve("keys-legacy.txt");
        Files.writeString(keys, String.join("\n", DUMMY_KEYS), StandardCharsets.UTF_8);
        try (WorldWebHost legacyHost = new WorldWebHost(
                () -> WorldWebScenario.open(legacy.resolve("world.journal")))) {
            try (WorldWebServer legacyServer = new WorldWebServer(legacyHost,
                    KeySlots.load(keys), SceneRoutes.open(legacy), 0, BOOTSTRAP, null,
                    consoleLines::add)) {
                legacyServer.start();
                String legacyBase = legacyServer.baseUrl();
                HttpResponse<String> login = rawPostAt(legacyBase, "/api/session",
                        "{\"bootstrap\":\"" + BOOTSTRAP + "\"}", null);
                assertEquals(200, login.statusCode(), login.body());
                String legacyCookie = cookieOf(login);
                HttpResponse<String> response = rawGetAt(legacyBase, "/api/admin/bank",
                        legacyCookie);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode view = JSON.readTree(response.body());
                assertFalse(view.path("available").asBoolean(), "旧档没有银行");
                assertFalse(view.path("reason").asText().isBlank(), "旧档必须说明未启用");
                assertEquals(0, view.path("banks").size(), "不得显示零余额银行");
                assertEquals(0, view.path("actions").size());
            }
        }
    }

    @Test
    @DisplayName("银行端点只读：POST 不被接受")
    void bankEndpointIsReadOnly() throws Exception {
        loginAdmin();
        assertEquals(404, rawPost("/api/admin/bank", "{}", cookie).statusCode());
    }

    // ------------------------------------------------------------ 辅助

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
        HttpResponse<String> response = rawGet(path, cookie);
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return rawPost(path, body, cookie);
    }

    private HttpResponse<String> rawGet(String path, String sessionCookie) throws Exception {
        return rawGetAt(base, path, sessionCookie);
    }

    private HttpResponse<String> rawGetAt(String target, String path, String sessionCookie)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> rawPost(String path, String body, String sessionCookie)
            throws Exception {
        return rawPostAt(base, path, body, sessionCookie);
    }

    private HttpResponse<String> rawPostAt(String target, String path, String body,
                                           String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target + path))
                .timeout(Duration.ofSeconds(20))
                .header("Origin", target)
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
