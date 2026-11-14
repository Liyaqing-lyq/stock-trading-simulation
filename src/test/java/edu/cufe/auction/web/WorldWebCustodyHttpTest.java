package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankOpening;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import edu.cufe.auction.world.bank.BankRules;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.BankingProfile;
import edu.cufe.auction.world.bank.InterestAccrual;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
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
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 托管权属的真实 HTTP 证据：合法 {@code banking-roles-experiment-v1} 非零托管开局
 * （复用 {@code BankRolesCustodyOpeningTest} 的公开构造：准备金 3300 / 池存款 300（整户非应计）/
 * 资本 3000，cust-a 120 + cust-b 180），真实登录 God / A / B 三个会话。
 *
 * <p>断言：A 只看到自己的 120、B 只看到自己的 180、God 全池 300；伪造 owner/party/custodian/query
 * 参数不扩权；普通身份访问 God 接口 403；本人响应不含别人的标识、池合计或全局位置。</p>
 */
class WorldWebCustodyHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "custody-http-bootstrap";
    private static final List<String> DUMMY_KEYS = List.of("sk-custodyhttp-000001",
            "sk-custodyhttp-000002", "sk-custodyhttp-000003", "sk-custodyhttp-000004",
            "sk-custodyhttp-000005");
    private static final String BANK = "north";
    private static final String CUSTODIAN = "custodian";
    private static final String A = "cust-a";
    private static final String B = "cust-b";

    private Path workDir;
    private Path journal;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;
    private final List<String> consoleLines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("web.custodyhttp.work",
                "target/web-custody-http-tests"));
        workDir = root.resolve("custody-http-" + System.nanoTime());
        Files.createDirectories(workDir);
        journal = workDir.resolve("custody.journal");
        Path keys = workDir.resolve("keys.txt");
        Files.writeString(keys, String.join("\n", DUMMY_KEYS), StandardCharsets.UTF_8);
        host = new WorldWebHost(() -> new WorldWebHost.Opened(
                openCustodyWorld(journal), "custody-web-test"));
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
    @DisplayName("真实三会话：A=120、B=180、God=300，伪造参数不扩权且人物视图不含全局位置")
    void perPersonCustodyOverHttp() throws Exception {
        // God：全池 300，且仍带全局位置
        loginAdmin();
        JsonNode god = getJson("/api/admin/bank");
        assertTrue(god.path("available").asBoolean());
        assertEquals(1, god.path("custody").path("pools").size());
        assertEquals("300.00",
                god.path("custody").path("pools").get(0).path("poolAsset").asText());
        assertEquals(2, god.path("custody").path("beneficial").size());
        assertTrue(god.has("position"), "God 审计必须保留全局位置");

        // A：只有自己的 120，且不带全局位置/池合计/另一位客户
        personAt(A);
        String rawA = rawGet("/api/me/bank", cookie).body();
        JsonNode a = JSON.readTree(rawA);
        assertEquals(A, a.path("personPartyId").asText());
        assertFalse(a.has("position"), "本人顶层不得有全局位置");
        for (JsonNode action : a.path("actions")) {
            assertFalse(action.has("position"), "本人动作回执不得带全局位置");
        }
        JsonNode aCustody = a.path("custody");
        assertEquals(1, aCustody.size(), "A 只能看到自己一条托管明细");
        assertEquals(A, aCustody.get(0).path("ownerId").asText());
        assertEquals("120.00", aCustody.get(0).path("amount").asText());
        assertFalse(rawA.contains("180.00"), "A 不得看到 B 的金额");
        assertFalse(rawA.contains("300.00"), "A 不得看到池合计");
        assertFalse(rawA.contains("\"" + B + "\""), "A 不得看到 B 的标识");
        assertEquals("120.00", a.path("owners").get(0).path("beneficial").asText(),
                "本人受益权投影必须与自己的明细一致");

        // 伪造 owner/party/custodian/query 参数：不得扩权
        JsonNode tampered = getJson("/api/me/bank?ownerId=" + B + "&partyKey=" + B
                + "&custodian=" + CUSTODIAN + "&position=99");
        assertEquals(A, tampered.path("personPartyId").asText(), "参数不得换人");
        assertEquals(1, tampered.path("custody").size());
        assertEquals("120.00", tampered.path("custody").get(0).path("amount").asText(),
                "参数不得换成别人的金额");
        assertFalse(tampered.has("position"), "参数不得注入位置字段");

        // 普通身份访问 God 接口：403
        assertEquals(403, rawGet("/api/admin/bank", cookie).statusCode());

        // B：只有自己的 180
        personAt(B);
        String rawB = rawGet("/api/me/bank", cookie).body();
        JsonNode b = JSON.readTree(rawB);
        assertEquals(B, b.path("personPartyId").asText());
        assertEquals(1, b.path("custody").size());
        assertEquals(B, b.path("custody").get(0).path("ownerId").asText());
        assertEquals("180.00", b.path("custody").get(0).path("amount").asText());
        assertFalse(rawB.contains("120.00"), "B 不得看到 A 的金额");
        assertFalse(rawB.contains("\"" + A + "\""), "B 不得看到 A 的标识");
        assertEquals("180.00", b.path("owners").get(0).path("beneficial").asText());
        assertEquals(403, rawGet("/api/admin/bank", cookie).statusCode(),
                "普通身份不能读 God 审计");

        // 登录轮换后旧的引导凭据不能再用（沿既有 HTTP 规范）
        HttpResponse<String> reuse = post("/api/session",
                "{\"bootstrap\":\"" + BOOTSTRAP + "\"}", null);
        assertEquals(401, reuse.statusCode(), "一次性引导凭据不得复用");
    }

    // ------------------------------------------------------------ 真实开局（复用 bankprobe 契约）

    private static WorldRuntime openCustodyWorld(Path journal) throws Exception {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "information", "roles",
                "banking", "custody")) {
            modules.put(name, 1);
        }
        RunManifest manifest = new RunManifest("custody-web-test", 19L,
                RunManifest.RULES_BANKING_ROLES_EXPERIMENT_V1, modules,
                Map.of("profile", "custody-web-test"), RunManifest.MODEL_NONE);
        List<CashLedger.OpeningAccount> openings = List.of(
                new CashLedger.OpeningAccount("RSV-NORTH", "north-owner", money("3300.00"),
                        "EXP 准备金历史禀赋"),
                new CashLedger.OpeningAccount("CUST-A", A, money("0.00"), "客户甲现金"),
                new CashLedger.OpeningAccount("CUST-B", B, money("0.00"), "客户乙现金"));
        OpeningRegistry registry = new OpeningRegistry(
                List.of(new OpeningRegistry.Party(BANK, OpeningRegistry.PartyKind.ORGANIZATION,
                                "North Bank（实验）"),
                        new OpeningRegistry.Party(CUSTODIAN,
                                OpeningRegistry.PartyKind.ORGANIZATION, "托管人（实验）"),
                        new OpeningRegistry.Party(A, OpeningRegistry.PartyKind.PERSON, "客户甲"),
                        new OpeningRegistry.Party(B, OpeningRegistry.PartyKind.PERSON, "客户乙")),
                List.of(new OpeningRegistry.PropertyOwner("north-owner",
                                OpeningRegistry.OwnerKind.ORGANIZATION, BANK),
                        new OpeningRegistry.PropertyOwner("custodian-owner",
                                OpeningRegistry.OwnerKind.ORGANIZATION, CUSTODIAN),
                        new OpeningRegistry.PropertyOwner(A, OpeningRegistry.OwnerKind.PERSON, A),
                        new OpeningRegistry.PropertyOwner(B, OpeningRegistry.OwnerKind.PERSON, B)),
                List.of(), List.of());
        BankingProfile profile = new BankingProfile(
                Map.of(BANK, new BankingProfile.BankRef(BANK, BANK, "north-owner", "RSV-NORTH")),
                Map.of(A, new BankingProfile.PartyRef(A, A, "CUST-A", null),
                        B, new BankingProfile.PartyRef(B, B, "CUST-B", null)),
                Map.of(CUSTODIAN, new BankingProfile.CustodyRef(CUSTODIAN, "custodian-owner",
                        BANK, "dep-cust", BankRules.CREDIT_NON_ACCRUAL)),
                Map.of(), Map.of(), List.of(), List.of(), List.of("dep-cust"), null, null, null);
        BankConfig config = new BankConfig.Builder(profile).version(19)
                .archiveId("CUSTODY-WEB-EXP").label(RunManifest.RULES_BANKING_ROLES_EXPERIMENT_V1)
                .coverageRule("DECLARED_UNIVERSE_V1")
                .interest(BankConfig.Interest.act365())
                .depositOffers(Map.of(BANK, new BankConfig.DepositOffer(BANK, "OFF-north",
                        InterestAccrual.rateU(new BigDecimal("0.02")), 365L * 86_400_000L,
                        BankRules.CREDIT_OFFER)))
                .groupMap(Map.of())
                .thresholds(Map.of("cet1_min", money("0.01"), "cet1_buffer", money("0.01"),
                        "t1_min", money("0.01"), "tcr_min", money("0.01"),
                        "lev_min", money("0.01"), "single_name", money("9.90"),
                        "group_name", money("9.90"), "lcr_min", money("0.01"),
                        "maturity_gap_min", money("-0.90"),
                        "impair_cover_min", money("0.01")))
                .build();
        BankState.DepositAccount nonAccrual = new BankState.DepositAccount(null, 0L, 0L, 0L,
                BigInteger.ZERO, 0L, 0L, 0L, 30_000L, "EXP 托管池整户非应计",
                "EXP 托管池整户非应计");
        BankOpening.DeclaredEndowment endowment = new BankOpening.DeclaredEndowment(
                Map.of(BANK, new BankOpening.BankBalance(
                        Map.of("dep-cust", money("-300.00")), "EXP 托管池存款历史禀赋")),
                List.of(),
                List.of(new BankOpening.DepositEndowment(BANK, CUSTODIAN, money("300.00"),
                        nonAccrual)),
                List.of(), List.of(), Map.of(), List.of(), "custody-web-opening-endowment");
        BankOpeningRequest request = BankOpeningRequest.of(config, endowment,
                Map.of(CUSTODIAN, Map.of(A, money("120.00"), B, money("180.00"))),
                Map.of(CUSTODIAN, "EXP 托管客户明细历史来源"));
        RoleRun.Config roles = new RoleRun.Config(List.of(), List.of());
        InformationRun.Config information = new InformationRun.Config(List.of(A, B));
        return new WorldRuntime(journal, manifest, openings, registry, roles, information, request);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }

    // ------------------------------------------------------------ HTTP 辅助

    private void loginAdmin() throws Exception {
        HttpResponse<String> response = post("/api/session",
                "{\"bootstrap\":\"" + currentBootstrap() + "\"}", null);
        assertEquals(200, response.statusCode(), response.body());
        cookie = cookieOf(response);
    }

    private String currentBootstrap() {
        for (int index = consoleLines.size() - 1; index >= 0; index--) {
            String line = consoleLines.get(index);
            int mark = line.indexOf('：');
            if (line.contains("引导凭据") && mark > 0) {
                return line.substring(mark + 1).trim();
            }
        }
        return BOOTSTRAP;
    }

    private void personAt(String personId) throws Exception {
        loginAdmin();
        HttpResponse<String> response = post("/api/session/person",
                "{\"personId\":\"" + personId + "\"}", cookie);
        assertEquals(200, response.statusCode(), response.body());
        cookie = cookieOf(response);
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = rawGet(path, cookie);
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> rawGet(String path, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String sessionCookie)
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
