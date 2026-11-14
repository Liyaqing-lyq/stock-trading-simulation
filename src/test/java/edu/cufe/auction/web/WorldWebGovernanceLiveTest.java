package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import edu.cufe.auction.world.PeriodicModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRuntime;

import java.net.InetSocketAddress;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 本轮显式场景（公司治理）的真实 HTTP 契约：回环假提供方 + dummy 凭据，0 真实项目调用。
 *
 * <p>只经应用真实默认入口 {@link WorldWebScenario#openEngineering(Path)}（schema18 工程档，决定配置
 * 为 live profile）与管理员 HTTP：选择治理场景后，attempt 必须落到治理槽位/模型/预算，模型回执的
 * {@code board.convene} 必须由真实 v6 董事召集链执行成一场真实会议；旧的按人物自动路由必须逐字保持。</p>
 */
class WorldWebGovernanceLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "gov-live-scene-bootstrap";
    private static final String COMPANY = WorldWebScenario.COMPANY;
    /** 五个槽位使用互不相同的 dummy 值，凭 Authorization 就能判断实际用了哪个 Key。 */
    private static final List<String> DUMMY_KEYS = List.of(dummy(1), dummy(2), dummy(3), dummy(4),
            dummy(5));

    /** 形如 sk- 加 16 位以上可打印字符；只用于本机夹具，不是任何真实凭据。 */
    private static String dummy(int index) {
        return "sk-govid" + String.format("%013d", index);
    }

    private Path work;
    private Path journal;
    private SceneRoutes routes;
    private WorldWebHost host;
    private WorldWebServer server;
    private HttpServer provider;
    private String base;
    private String cookie;
    private KeySlots keys;
    /** 本用例额外的开局任职（合法 world 状态，不改 JSON）；默认无。 */
    private List<RoleAccess.Appointment> extraAppointments = List.of();

    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> authorizations = new ArrayList<>();
    private volatile String providerBody;
    private volatile String reply;
    private volatile boolean hold;
    private CountDownLatch arrived = new CountDownLatch(1);
    private CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void close() {
        release.countDown();
        if (server != null) {
            server.close();
            server = null;
        }
        if (host != null) {
            host.close();
            host = null;
        }
        if (routes != null) {
            routes.close();
            routes = null;
        }
        if (provider != null) {
            provider.stop(0);
            provider = null;
        }
    }

    // ------------------------------------------------------------ 夹具

    @Test
    @DisplayName("治理轮：HTTP 选场景 → 治理槽位/模型/预算 → 真实董事召集成会")
    void governanceRoundRunsRealBoardConvene() throws Exception {
        start();
        login();
        configureRoutes("key-5", SceneRoutes.MODEL_PRO);
        JsonNode status = live();
        long cut = Long.parseLong(status.path("timeMillis").asText());
        reply = convene("gov-live-1", cut);
        JsonNode started = postJson("/api/admin/live/start",
                startBody(status, List.of("alice"), "governance", COMPANY));
        assertEquals("governance", started.path("roundScene").asText(), started.toString());
        assertEquals(COMPANY, started.path("roundCompany").asText());
        assertEquals(1, started.path("roundPersons").size());
        JsonNode settled = awaitRecorded("alice");
        JsonNode decision = settled.path("decisions").get(0);
        assertEquals("RECORDED", decision.path("status").asText(), decision.toString());
        assertEquals("INTENT", decision.path("outcome").asText(), decision.toString());
        assertEquals("governance", decision.path("sceneId").asText(), decision.toString());
        assertEquals("key-5", decision.path("slotId").asText(), decision.toString());
        assertEquals(SceneRoutes.MODEL_PRO, decision.path("model").asText(), decision.toString());
        assertTrue(decision.path("declaredInputTokens").asText().length() > 0);

        // 真实使用了治理槽位的那一把 Key，且只调用一次。
        assertEquals(1, calls.get());
        assertEquals("Bearer " + DUMMY_KEYS.get(4), authorizations.get(0));
        // 治理槽位的冻结输出上限真的下发给提供方，而不是默认值。
        JsonNode request = JSON.readTree(providerBody);
        assertEquals(256, request.path("max_tokens").asInt());
        assertEquals(SceneRoutes.MODEL_PRO, request.path("model").asText());
        // 输入仍是本人物真实观察：带着真实公司上下文与真实可用工具，不是伪造的治理参数。
        String input = request.path("messages").get(1).path("content").asText();
        assertTrue(input.contains("directorships"), "缺少真实董事观察");
        assertTrue(input.contains(COMPANY), "缺少真实公司标识");
        assertTrue(input.contains("board.convene"), "董事没有真实召集工具");
        assertTrue(input.contains("\"seatId\":\"s1\""), "主席席位必须来自真实章程：" + input.substring(0, Math.min(400, input.length())));
        JsonNode governanceUsage = sceneRow(settled, "governance");
        assertEquals("1", governanceUsage.path("usedCalls").asText(), governanceUsage.toString());
        assertEquals("0", sceneRow(settled, "research").path("usedCalls").asText(),
                "治理轮不得占用研究场景额度");

        // 排程不等于已开会：推进到 BEGIN 后才产生真实会议。
        long due = Long.parseLong(settled.path("timeMillis").asText()) + 5_000L;
        advance(due);
        JsonNode god = engineering();
        JsonNode meeting = meetingRow(god, "gov-live-1");
        assertNotNull(meeting, "治理轮必须真的建立会议：" + god.path("meetings"));
        assertEquals("CONVENED", meeting.path("status").asText(), meeting.toString());
        assertEquals("BUDGET", meeting.path("topic").asText(), meeting.toString());
        assertEquals("s1", meeting.path("convenerSeatId").asText(), meeting.toString());

        // 秘密不外泄：状态 JSON 与日志都不含任何 dummy 明文。
        assertFalse(settled.toString().contains("sk-govid"), settled.toString());
        host.close();
        String persisted = Files.readString(journal, StandardCharsets.ISO_8859_1);
        assertTrue(persisted.contains("ENGINEERING_BOARD_V1"));
        assertFalse(persisted.contains("sk-govid"));
    }

    @Test
    @DisplayName("不指定场景：旧四岗自动路由逐字保持（alice→研究、bob→交易）")
    void legacyRoundKeepsOriginalRouting() throws Exception {
        start();
        login();
        configureRoutes("key-5", SceneRoutes.MODEL_PRO, false);
        reply = "{\"tool\":\"role.wait\"}";
        JsonNode status = live();
        JsonNode started = postJson("/api/admin/live/start",
                startBody(status, List.of("alice", "bob")));
        assertTrue(started.path("roundScene").isNull(), "未指定场景时本轮场景必须为空：" + started);
        assertEquals(2, started.path("roundPersons").size(), started.toString());
        JsonNode settled = awaitRecorded("bob");
        Map<String, String> slots = new LinkedHashMap<>();
        Map<String, String> scenes = new LinkedHashMap<>();
        for (JsonNode decision : settled.path("decisions")) {
            slots.put(decision.path("personPartyId").asText(), decision.path("slotId").asText());
            scenes.put(decision.path("personPartyId").asText(), decision.path("sceneId").asText());
        }
        assertEquals("research", scenes.get("alice"), settled.toString());
        assertEquals("key-2", slots.get("alice"), settled.toString());
        assertEquals("trading", scenes.get("bob"), settled.toString());
        assertEquals("key-4", slots.get("bob"), settled.toString());
        assertEquals(2, calls.get(), "两人各一次调用");
        assertFalse(authorizations.contains("Bearer " + DUMMY_KEYS.get(4)),
                "未选治理时不得使用治理槽位：" + authorizations);
    }

    @Test
    @DisplayName("兼任人物：不选时走原交易路由，显式选治理时用治理槽位")
    void concurrentDirectorCanExplicitlyChooseGovernance() throws Exception {
        start();
        login();
        configureRoutes("key-5", SceneRoutes.MODEL_PRO);
        reply = convene("gov-live-bob", 0L);
        JsonNode status = live();
        JsonNode started = postJson("/api/admin/live/start",
                startBody(status, List.of("bob"), "governance", COMPANY));
        assertEquals("governance", started.path("roundScene").asText(), started.toString());
        JsonNode settled = awaitRecorded("bob");
        JsonNode decision = settled.path("decisions").get(0);
        assertEquals("governance", decision.path("sceneId").asText(), decision.toString());
        assertEquals("key-5", decision.path("slotId").asText(), decision.toString());
        assertEquals("bob", decision.path("personPartyId").asText(), decision.toString());
        // 兼任不改变人物自身的任职事实：他仍然是交易岗，只是本轮用治理配置与真实董事工具。
        JsonNode capabilities = GET("/api/admin/capabilities");
        JsonNode bob = person(capabilities, "bob");
        assertEquals("trading", bob.path("sceneId").asText(), bob.toString());
        assertTrue(bob.path("scenes").toString().contains("governance"), bob.toString());
        assertTrue(bob.path("governanceCompanies").toString().contains(COMPANY), bob.toString());
        assertEquals(1, calls.get());
        assertEquals("Bearer " + DUMMY_KEYS.get(4), authorizations.get(0));
    }

    @Test
    @DisplayName("能力：只列有执行器的场景，评估场景永不出现，资格与人物真实选择同源")
    void capabilitiesListOnlyWiredScenes() throws Exception {
        start();
        login();
        JsonNode capabilities = GET("/api/admin/capabilities");
        List<String> scenes = new ArrayList<>();
        capabilities.path("supportedScenes").forEach(node -> scenes.add(node.asText()));
        assertTrue(scenes.contains("governance"), capabilities.toString());
        assertFalse(scenes.contains("evaluation"), "评估场景没有执行器，不能展示：" + scenes);
        JsonNode alice = person(capabilities, "alice");
        assertEquals("research", alice.path("sceneId").asText(), alice.toString());
        assertTrue(alice.path("governanceCompanies").toString().contains(COMPANY), alice.toString());
        JsonNode omar = person(capabilities, "omar");
        assertEquals(0, omar.path("governanceCompanies").size(), "无席无任职的人不得有可治理公司");
        assertFalse(omar.path("scenes").toString().contains("governance"), omar.toString());
        assertEquals(0, calls.get(), "读取能力不得调用提供方");
    }

    @Test
    @DisplayName("拒绝集合：无资格/错误公司/场景未启用/注入字段都无副作用且不调用")
    void rejectionsHaveNoSideEffects() throws Exception {
        start();
        login();
        configureRoutes("key-5", SceneRoutes.MODEL_PRO);
        JsonNode status = live();
        String position = status.path("position").asText();
        // 治理轮缺公司
        assertRejected(startBody(status, List.of("alice"), "governance", null));
        // 非治理轮带公司
        assertRejected(startBody(status, List.of("alice"), "research", COMPANY));
        // 未知场景值
        assertRejected(startBody(status, List.of("alice"), "board-room", null));
        // 已知但无执行器的场景
        assertRejected(startBody(status, List.of("alice"), "evaluation", null));
        // 无席位也无经理任职的人
        JsonNode unqualified = assertRejected(
                startBody(status, List.of("omar"), "governance", COMPANY));
        assertTrue(unqualified.path("message").asText().contains("omar"), unqualified.toString());
        // 不存在的公司
        assertRejected(startBody(status, List.of("alice"), "governance", "eng-zz"));
        // 显式非治理场景与人物原路由不一致
        assertRejected(startBody(status, List.of("bob"), "research", null));
        // 注入人物字段：不在 allowlist
        ObjectNode injected = (ObjectNode) JSON.readTree(
                startBody(status, List.of("alice"), "governance", COMPANY));
        injected.put("personPartyId", "alice");
        assertRejected(injected.toString());
        // 场景未启用
        disable("governance");
        status = live();
        assertRejected(startBody(status, List.of("alice"), "governance", COMPANY));
        assertEquals(position, live().path("position").asText(), "拒绝不得推进世界");
        assertEquals(0, calls.get(), "拒绝不得调用提供方");
        assertEquals(0, live().path("decisions").size(), "拒绝不得留下决定");
        assertTrue(live().path("roundScene").isNull(), "拒绝不得登记本轮场景");
    }

    @Test
    @DisplayName("配置修订变动与重启重试：旧 attempt 留在原槽位，新轮用新槽位")
    void revisionChangeDoesNotCrossRoutes() throws Exception {
        start();
        login();
        configureRoutes("key-5", SceneRoutes.MODEL_PRO);
        hold = true;
        reply = convene("gov-live-rev", 0L);
        JsonNode status = live();
        POST("/api/admin/live/start", startBody(status, List.of("alice"), "governance", COMPANY));
        assertTrue(arrived.await(10, TimeUnit.SECONDS), "必须真的发出回环 HTTP");
        assertEquals("Bearer " + DUMMY_KEYS.get(4), authorizations.get(0));
        long beforeRelease = Files.size(journal);
        // 停止未派发名单并让在途请求结束；旧 attempt 仍按原槽位结算。
        POST("/api/admin/live/stop", "{\"expectedPosition\":\"" + live().path("position").asText() + "\"}");
        release.countDown();
        hold = false;
        awaitJournalGrowth(beforeRelease);

        // 关闭后用新修订重新打开：治理改到 key-1 / flash，旧 attempt 不得被改写。
        String oldPosition = live().path("position").asText();
        configureRoutes("key-1", SceneRoutes.MODEL_FLASH);
        arrived = new CountDownLatch(1);
        release = new CountDownLatch(1);
        reply = convene("gov-live-rev-2", 0L);
        server.close();
        server = null;
        host.close();
        host = null;
        restartHost();
        login();
        JsonNode reopened = live();
        JsonNode started = postJson("/api/admin/live/start",
                startBody(reopened, List.of("alice"), "governance", COMPANY));
        assertEquals("governance", started.path("roundScene").asText(), started.toString());
        JsonNode settled = awaitSettledDecisions(2);
        Map<String, String> bySlot = new LinkedHashMap<>();
        for (JsonNode decision : settled.path("decisions")) {
            bySlot.put(decision.path("actionId").asText(), decision.path("slotId").asText());
        }
        assertTrue(bySlot.containsValue("key-5"), "旧 attempt 必须保留原槽位：" + bySlot);
        assertTrue(bySlot.containsValue("key-1"), "新轮必须用新槽位：" + bySlot);
        assertEquals(2, bySlot.size(), settled.toString());
        assertTrue(authorizations.contains("Bearer " + DUMMY_KEYS.get(4)), authorizations.toString());
        assertTrue(authorizations.contains("Bearer " + DUMMY_KEYS.get(0)), authorizations.toString());
        assertNotEquals(oldPosition, settled.path("position").asText());
    }

    /** 纯董事（真实席位、无四岗任职）只在显式治理轮可用。 */
    @Test
    @DisplayName("纯董事 erin：不进自动路由；显式治理轮走治理槽位并完成真实到会")
    void pureDirectorRunsGovernanceOnlyWhenExplicitlySelected() throws Exception {
        start();
        login();
        configureRoutes("key-5", SceneRoutes.MODEL_PRO);
        // 只推进模拟时间，让章程公司按冻结周期自己采样并产生真实待开董事会。
        advance(PeriodicModel.PERIOD_MILLIS + 1L);

        JsonNode capabilities = GET("/api/admin/capabilities");
        List<String> legacy = ids(capabilities.path("persons"));
        assertTrue(legacy.contains("alice"), capabilities.toString());
        assertFalse(legacy.contains("erin"), "纯董事不得被自动路由名单偷偷纳入：" + legacy);
        assertFalse(ids(capabilities.path("governancePersons")).contains("omar"),
                "无席无任职的人不能出现在治理名单");
        JsonNode erin = person(capabilities.path("governancePersons"), "erin");
        assertTrue(erin.path("sceneId").isNull(), "纯董事没有旧自动路由：" + erin);
        assertTrue(erin.path("scenes").toString().contains("governance"), erin.toString());
        assertTrue(erin.path("governanceCompanies").toString().contains(COMPANY), erin.toString());

        // 旧三字段与显式研究/交易都明确拒绝她，不静默改道媒体（旧请求口径仍是 400）。
        JsonNode status = live();
        assertRejected(startBody(status, List.of("erin")));
        assertRejected(startBody(status, List.of("erin"), "research", null));
        assertRejected(startBody(status, List.of("erin"), "trading", null));
        assertEquals(0, calls.get(), "被拒的自动/研究/交易选择不得发出模型调用");

        // 显式治理：非主席只能做到会这类合法动作，不替她设主席、不改章程。
        reply = attend("periodic-meeting:cufe:1");
        JsonNode started = postJson("/api/admin/live/start",
                startBody(status, List.of("erin"), "governance", COMPANY));
        assertEquals("governance", started.path("roundScene").asText(), started.toString());
        JsonNode settled = awaitRecorded("erin");
        JsonNode decision = settled.path("decisions").get(0);
        assertEquals("governance", decision.path("sceneId").asText(), decision.toString());
        assertEquals("key-5", decision.path("slotId").asText(), decision.toString());
        assertEquals(1, calls.get());
        assertEquals("Bearer " + DUMMY_KEYS.get(4), authorizations.get(0));
        String input = JSON.readTree(providerBody).path("messages").get(1)
                .path("content").asText();
        assertTrue(input.contains("\"seatId\":\"s5\""), "到会必须来自她自己的真实席位");
        assertTrue(input.contains("board.attend"), "非主席的合法工具必须在观察里");

        advance(Long.parseLong(settled.path("timeMillis").asText()) + 5_000L);
        JsonNode meeting = meetingRow(engineering(), "periodic-meeting:cufe:1");
        assertNotNull(meeting, "周期董事会必须真实存在：" + engineering().path("meetings"));
        assertTrue(meeting.path("attendees").asText().contains("s5"),
                "erin 必须真的到会：" + meeting.toString());
        // 她不是主席：本场会议由章程/主席召集，召集人不是她的席位。
        assertNotEquals("s5", meeting.path("convenerSeatId").asText(), meeting.toString());
    }

    /** 经理任职到期：观测可见→BEGIN 拒绝→之后观测直接无资格。 */
    @Test
    @DisplayName("经理任期到期：BEGIN 拒绝旧资格业务，之后观测与显式选择都被拒")
    void expiredManagerLosesQualification() throws Exception {
        extraAppointments = List.of(new RoleAccess.Appointment("TMP-MGR-1", "omar", "eng-a",
                RoleAccess.Role.COMPANY_MANAGER, 0L, 2_000L, null));
        start();
        login();
        configureRoutes("key-5", SceneRoutes.MODEL_PRO);
        advance(1_000L);

        JsonNode capabilities = GET("/api/admin/capabilities");
        JsonNode omar = person(capabilities.path("governancePersons"), "omar");
        assertTrue(omar.path("governanceCompanies").toString().contains("eng-a"), omar.toString());
        JsonNode legacyOmar = person(capabilities, "omar");
        assertEquals("media", legacyOmar.path("sceneId").asText(),
                "临时经理任职不改变旧自动路由：" + legacyOmar);

        hold = true;
        reply = "{\"tool\":\"engineering.paySupplier\",\"args\":{\"companyPartyId\":\"eng-a\","
                + "\"amount\":\"10.00\",\"purpose\":\"夹具采购\"}}";
        postJson("/api/admin/live/start",
                startBody(live(), List.of("omar"), "governance", "eng-a"));
        assertTrue(arrived.await(10, TimeUnit.SECONDS), "经理轮次必须真的派发");
        String input = JSON.readTree(providerBody).path("messages").get(1)
                .path("content").asText();
        assertTrue(input.contains("engineering.paySupplier"), "真实经理工具必须在观察里");
        assertTrue(input.contains("\"companyPartyId\":\"eng-a\""), input.substring(0,
                Math.min(300, input.length())));
        release.countDown();
        hold = false;
        JsonNode settled = awaitRecorded("omar");
        assertEquals("INTENT", settled.path("decisions").get(0).path("outcome").asText(),
                settled.toString());

        // 任期在 BEGIN 之前到期：屏障复核必然拒绝，不排完成帧、不产生业务效果。
        advance(2_000L);
        JsonNode god = engineering("eng-a");
        assertTrue(god.toString().contains(WorldRuntime.REASON_NOT_AUTHORIZED),
                "BEGIN 必须拒绝已到期经理的业务动作：" + god.path("reports"));
        assertFalse(ids(GET("/api/admin/capabilities").path("governancePersons")).contains("omar"),
                "任期到期后不得再出现在治理名单");
        assertRejected(startBody(live(), List.of("omar"), "governance", "eng-a"));
        assertEquals(1, calls.get(), "拒绝不产生新的模型调用");
    }


    // ------------------------------------------------------------ 夹具实现

    private void start() throws Exception {
        Path root = Path.of("target", "governance-live-http-tests");
        Files.createDirectories(root);
        work = Files.createTempDirectory(root, "case-");
        journal = work.resolve("world-engineering.journal");
        Path keyFile = work.resolve("dummy-keys.txt");
        Files.writeString(keyFile, String.join("\n", DUMMY_KEYS), StandardCharsets.UTF_8);
        keys = KeySlots.load(keyFile);
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/chat/completions", exchange -> {
            try {
                providerBody = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                calls.incrementAndGet();
                arrived.countDown();
                if (hold && !release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("fixture release missing");
                }
                String content = reply == null ? "{\"tool\":\"role.wait\"}" : reply;
                ObjectNode envelope = JSON.createObjectNode();
                ObjectNode choice = envelope.putArray("choices").addObject();
                choice.put("index", 0);
                choice.putObject("message").put("role", "assistant").put("content", content);
                choice.put("finish_reason", "stop");
                ObjectNode usage = envelope.putObject("usage");
                usage.put("prompt_tokens", 11);
                usage.put("completion_tokens", 5);
                usage.put("total_tokens", 16);
                byte[] body = JSON.writeValueAsBytes(envelope);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        provider.start();
        routes = SceneRoutes.open(work);
        openHost();
    }

    private void openHost() throws Exception {
        URI endpoint = URI.create("http://127.0.0.1:" + provider.getAddress().getPort()
                + "/chat/completions");
        host = new WorldWebHost(() -> openJournal(), routes,
                new LiveDecisionProvider(slot -> new DeepSeekClient(() -> keys.credential(slot),
                        endpoint)));
        host.adminSnapshot();
        server = new WorldWebServer(host, keys, routes, 0, BOOTSTRAP, null, ignored -> { });
        server.start();
        base = server.baseUrl();
        cookie = null;
    }

    /** 默认走应用真实入口；只有需要临时任职的用例才用同一开关表的合法开局接缝。 */
    private WorldWebHost.Opened openJournal() throws Exception {
        if (extraAppointments.isEmpty()) {
            return WorldWebScenario.openEngineering(journal);
        }
        return new WorldWebHost.Opened(WorldWebScenario.engineeringRuntimeWithAppointments(
                journal, WorldWebScenario.engineeringConfig(), extraAppointments),
                WorldWebScenario.engineeringManifest().runId());
    }

    private void restartHost() throws Exception {
        openHost();
    }

    private void login() throws Exception {
        HttpResponse<String> response = POST("/api/session",
                "{\"bootstrap\":\"" + BOOTSTRAP + "\"}");
        assertEquals(200, response.statusCode(), response.body());
        cookie = cookieOf(response);
    }

    /** 显式配置：研究 key-2/flash、交易 key-4/flash、治理（可选是否启用）。 */
    private void configureRoutes(String governanceSlot, String governanceModel) throws Exception {
        configureRoutes(governanceSlot, governanceModel, true);
    }

    private void configureRoutes(String governanceSlot, String governanceModel, boolean enabled)
            throws Exception {
        JsonNode current = GET("/api/admin/routes");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode route : current.path("routes")) {
            String scene = route.path("scene").asText();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scene", scene);
            switch (scene) {
                case "research" -> {
                    row.put("slotId", "key-2");
                    row.put("model", SceneRoutes.MODEL_FLASH);
                    row.put("enabled", true);
                    row.put("maxOutputTokens", 128);
                }
                case "trading" -> {
                    row.put("slotId", "key-4");
                    row.put("model", SceneRoutes.MODEL_FLASH);
                    row.put("enabled", true);
                    row.put("maxOutputTokens", 128);
                }
                case "governance" -> {
                    row.put("slotId", governanceSlot);
                    row.put("model", governanceModel);
                    row.put("enabled", enabled);
                    row.put("maxOutputTokens", 256);
                }
                default -> {
                    row.put("slotId", route.path("slotId").asText());
                    row.put("model", route.path("model").asText());
                    row.put("enabled", false);
                    row.put("maxOutputTokens", 128);
                }
            }
            row.put("maxCalls", 5);
            row.put("maxTotalTokens", 100_000);
            row.put("maxConcurrency", 1);
            rows.add(row);
        }
        POST("/api/admin/routes", JSON.writeValueAsString(Map.of(
                "expectedRevision", current.path("revision").asText(), "routes", rows)));
    }

    private void disable(String scene) throws Exception {
        JsonNode current = GET("/api/admin/routes");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode route : current.path("routes")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scene", route.path("scene").asText());
            row.put("slotId", route.path("slotId").asText());
            row.put("model", route.path("model").asText());
            row.put("enabled", !scene.equals(route.path("scene").asText())
                    && route.path("enabled").asBoolean());
            row.put("maxCalls", route.path("maxCalls").asInt());
            row.put("maxTotalTokens", route.path("maxTotalTokens").asInt());
            row.put("maxOutputTokens", route.path("maxOutputTokens").asInt());
            row.put("maxConcurrency", route.path("maxConcurrency").asInt());
            rows.add(row);
        }
        POST("/api/admin/routes", JSON.writeValueAsString(Map.of(
                "expectedRevision", current.path("revision").asText(), "routes", rows)));
    }

    /** 真实董事召集回执：公司/会议/材料截止/开会/闭会与主题全部显式给出。 */
    private static String convene(String meetingId, long cut) {
        long base = Math.max(cut, 0L);
        return "{\"tool\":\"board.convene\",\"args\":{\"companyPartyId\":\"" + COMPANY
                + "\",\"meetingId\":\"" + meetingId + "\",\"materialDeadline\":\""
                + (base + 15_000L) + "\",\"opensAt\":\"" + (base + 20_000L)
                + "\",\"closesAt\":\"" + (base + 30_000L) + "\",\"topic\":\"BUDGET\"}}";
    }

    private String startBody(JsonNode status, List<String> persons) throws Exception {
        return startBody(status, persons, null, null);
    }

    private String startBody(JsonNode status, List<String> persons, String scene, String company)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedPosition", status.path("position").asText());
        body.put("expectedRevision", GET("/api/admin/routes").path("revision").asText());
        body.put("persons", persons);
        if (scene != null) {
            body.put("scene", scene);
        }
        if (company != null) {
            body.put("companyId", company);
        }
        return JSON.writeValueAsString(body);
    }

    /** 断言被拒：400 + 明确原因，且请求里没有任何自造人物/凭据字段。 */
    private JsonNode assertRejected(String body) throws Exception {
        HttpResponse<String> response = POST("/api/admin/live/start", body);
        assertEquals(400, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private JsonNode live() throws Exception {
        return GET("/api/admin/live");
    }

    private JsonNode engineering() throws Exception {
        return engineering(COMPANY);
    }

    private JsonNode engineering(String companyId) throws Exception {
        return postJson("/api/admin/engineering", "{\"companyId\":\"" + companyId + "\"}");
    }

    /** 能力数组里找人：既接受整个能力对象，也接受人物数组本身。 */
    private static JsonNode person(JsonNode capabilities, String personPartyId) {
        JsonNode rows = capabilities.isArray() ? capabilities : capabilities.path("persons");
        for (JsonNode person : rows) {
            if (personPartyId.equals(person.path("personPartyId").asText())) {
                return person;
            }
        }
        throw new AssertionError("能力列表缺少人物 " + personPartyId + "：" + capabilities);
    }

    /** 真实到会回执（非主席的合法动作，不涉及召集）。 */
    private static String attend(String meetingId) {
        return "{\"tool\":\"board.attend\",\"args\":{\"companyPartyId\":\"" + COMPANY
                + "\",\"meetingId\":\"" + meetingId + "\"}}";
    }

    /** 能力数组里的人物标识（对象取 personPartyId，文本直接取）。 */
    private static List<String> ids(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            values.add(node.isTextual() ? node.asText() : node.path("personPartyId").asText());
        }
        return values;
    }

    private static JsonNode sceneRow(JsonNode status, String sceneId) {
        for (JsonNode row : status.path("scenes")) {
            if (sceneId.equals(row.path("sceneId").asText())) {
                return row;
            }
        }
        throw new AssertionError("状态缺少场景行 " + sceneId + "：" + status.path("scenes"));
    }

    private static JsonNode meetingRow(JsonNode god, String meetingId) {
        for (JsonNode row : god.path("meetings").path("rows")) {
            if (meetingId.equals(row.path("meetingId").asText())) {
                return row;
            }
        }
        return null;
    }

    private JsonNode awaitRecorded(String person) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            JsonNode status = live();
            for (JsonNode decision : status.path("decisions")) {
                if (person.equals(decision.path("personPartyId").asText())
                        && !"OPEN".equals(decision.path("status").asText())) {
                    return status;
                }
            }
            Thread.sleep(30);
        }
        fail("决定未在期限内结算：" + live());
        return null;
    }

    /** 等到至少有 {@code atLeast} 条决定离开 OPEN（含旧代次与新一轮）。 */
    private JsonNode awaitSettledDecisions(int atLeast) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            JsonNode status = live();
            int settledCount = 0;
            for (JsonNode decision : status.path("decisions")) {
                if (!"OPEN".equals(decision.path("status").asText())) {
                    settledCount++;
                }
            }
            if (settledCount >= atLeast) {
                return status;
            }
            Thread.sleep(30);
        }
        fail("决定未在期限内达到 " + atLeast + " 条结算：" + live());
        return null;
    }

    private void advance(long targetMillis) throws Exception {
        JsonNode status = live();
        HttpResponse<String> response = POST("/api/admin/advance",
                "{\"expectedPosition\":\"" + status.path("position").asText()
                        + "\",\"targetMillis\":\"" + targetMillis + "\"}");
        assertEquals(200, response.statusCode(), response.body());
    }

    private void awaitJournalGrowth(long previousBytes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.size(journal) > previousBytes) {
                return;
            }
            Thread.sleep(25);
        }
        fail("结算未写入日志");
    }

    private JsonNode GET(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        HttpResponse<String> response = HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " 应返回 200：" + response.body());
        return JSON.readTree(response.body());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = POST(path, body);
        assertEquals(200, response.statusCode(), path + " 应返回 200：" + response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> POST(String path, String body) throws Exception {
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
