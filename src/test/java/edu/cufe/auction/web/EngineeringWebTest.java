package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.EngineeringRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRuntime;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 18c Web 第一切片：God 多公司视图、本人岗位投影与真实排程入口，全部走真实回环 HTTP。
 *
 * <p>每个用例先在私有目录里用真实 {@link WorldRuntime} 排程并推进，产生真实的董事议决、
 * 任职、签约、开工、客户回应与付款，再由 {@link WorldWebHost}+{@link WorldWebServer} 读回。
 * 不使用反射改状态、不 mock 服务响应、不写主树 target。</p>
 */
class EngineeringWebTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "engineering-junit-bootstrap";
    private static final long DAY = 86_400_000L;
    private static final String CONTRACT = "B-1";
    private static final List<String> DUMMY_KEYS = List.of("sk-dummy-key-value-0001",
            "sk-dummy-key-value-0002", "sk-dummy-key-value-0003",
            "sk-dummy-key-value-0004", "sk-dummy-key-value-0005");

    private Path workDir;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;
    private String adminBootstrap = BOOTSTRAP;
    private final List<String> consoleLines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("web.engineering.work",
                "target/web-engineering-tests"));
        workDir = root.resolve("engineering-" + System.nanoTime());
        Files.createDirectories(workDir);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (host != null) {
            host.close();
            host = null;
        }
    }

    // ------------------------------------------------------------ 夹具

    private static EngineeringRun.ContractPlan contractPlan() {
        return new EngineeringRun.ContractPlan(CONTRACT, new BigDecimal("100.00"),
                new BigDecimal("60.0000"), 2000, 1000, 0, 0, List.of(2L, 3L));
    }

    private static EngineeringRun.CompanySpec aSpec() {
        EngineeringRun.CompanySpec base = WorldWebScenario.engineeringConfig()
                .specOf(WorldWebScenario.ENGINE_A);
        return new EngineeringRun.CompanySpec(base.companyPartyId(), base.companyOwnerId(),
                base.companyAccountId(), base.customerAccountId(), base.supplierAccountId(),
                base.opsAccountId(), 1_000L, 6_000, base.charter(), List.of(contractPlan()),
                new EngineeringRun.CustomerPool("ENG-A-CUS", 30L * DAY,
                        new BigDecimal("5000.00"), 6));
    }

    private static EngineeringRun.CompanySpec bSpec() {
        EngineeringRun.CompanySpec base = WorldWebScenario.engineeringConfig()
                .specOf(WorldWebScenario.ENGINE_B);
        return new EngineeringRun.CompanySpec(base.companyPartyId(), base.companyOwnerId(),
                base.companyAccountId(), base.customerAccountId(), base.supplierAccountId(),
                base.opsAccountId(), 1_000L, 5_000, base.charter(),
                List.of(new EngineeringRun.ContractPlan("E-B1", new BigDecimal("200.00"),
                        new BigDecimal("100.0000"), 2000, 1000, 15, 60, List.of(5L))),
                new EngineeringRun.CustomerPool("ENG-B-CUS", 30L * DAY,
                        new BigDecimal("5000.00"), 6));
    }

    private static EngineeringRun.CustomerEventPlan event(String id, String kind, long at,
            long units, String amount, String requestKind) {
        boolean payment = List.of("ADVANCE", "PAY", "RETENTION_PAY").contains(kind);
        return new EngineeringRun.CustomerEventPlan(id, WorldWebScenario.ENGINE_A, CONTRACT, 0,
                kind, at, units, amount == null ? BigDecimal.ZERO : new BigDecimal(amount),
                payment ? "ENG-A-CUS" : "", "冻结客户证据：" + id, requestKind);
    }

    private static EngineeringRun.Config config() {
        return new EngineeringRun.Config("18c Web 小链夹具（冻结条款：单价100/成本60，阶段2+3）",
                List.of(aSpec(), bSpec()),
                Map.of(WorldWebScenario.ENGINE_A, List.of(
                        event("adv-b1", "ADVANCE", 1_400L, 0, "100.00", ""),
                        event("acc-b1", "ACCEPT", 3_700L, 2L, null, "ACCEPTANCE"),
                        event("pay-b1", "PAY", 3_950L, 0, "180.00", "")),
                        WorldWebScenario.ENGINE_B, List.of()));
    }

    private static void prepareReading(WorldRuntime world) throws Exception {
        prepareReading(world, "dave");
    }

    /** 机会只让指定人物实际读完；其余人即使被任命为经理也未读过，不能签约。 */
    private static void prepareReading(WorldRuntime world, String opportunityReader)
            throws Exception {
        world.schedulePublishDocument("pub-budget", "pub-budget-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-opp", "pub-opp-task", 10L,
                "engineering-opportunity:" + CONTRACT, WorldWebScenario.INVESTOR,
                InformationModel.Audience.direct(opportunityReader), "B-1 机会", "实验机会 B-1",
                "冻结机会", 10L, List.of());
        List<String> persons = List.of("alice", "bob", "carol", "dave", "erin");
        for (int index = 0; index < persons.size(); index++) {
            String reader = persons.get(index);
            world.scheduleStartReading("read-mat-" + reader, "read-mat-" + reader + "-task",
                    20L + index * 2L, reader, "b-material", 1L);
            world.scheduleStartReading("read-appt-" + reader, "read-appt-" + reader + "-task",
                    60L + index * 2L, reader, "appt-material", 1L);
        }
        world.scheduleStartReading("read-opp", "read-opp-task", 100L, opportunityReader,
                "engineering-opportunity:" + CONTRACT, 1L);
    }

    private static void prepareGovernance(WorldRuntime world) throws Exception {
        world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L,
                WorldWebScenario.ENGINE_A, "alice", "budget-meeting", 150L, 200L, 300L);
        List<String> persons = List.of("alice", "bob", "carol", "dave", "erin");
        for (int index = 0; index < persons.size(); index++) {
            world.scheduleEngineeringAttend("budget-a-" + index, "budget-a-" + index + "-task",
                    110L + index, WorldWebScenario.ENGINE_A, persons.get(index),
                    "budget-meeting", false);
        }
        world.scheduleEngineeringMaterial("budget-m", "budget-m-task", 130L,
                WorldWebScenario.ENGINE_A, "alice", "budget-meeting", "m1", "b-material", 1L);
        world.scheduleEngineeringBudgetProposal("budget-p", "budget-p-task", 140L,
                WorldWebScenario.ENGINE_A, "alice", "budget-meeting", List.of(
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                new BigDecimal("10000.00")),
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                new BigDecimal("10000.00"))));
        for (int index = 0; index < 4; index++) {
            world.scheduleEngineeringVote("budget-v-" + index, "budget-v-" + index + "-task",
                    220L + index, WorldWebScenario.ENGINE_A, persons.get(index),
                    "budget-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringVote("budget-v-4", "budget-v-4-task", 224L,
                WorldWebScenario.ENGINE_A, "erin", "budget-meeting", BoardModel.Vote.NO);
        world.scheduleEngineeringImplement("budget-i", "budget-i-task", 320L,
                WorldWebScenario.ENGINE_A, "alice", "budget-meeting");
        appointment(world, "mgr-meeting", 400L, 700L, 900L * DAY);
        world.advanceTo(700L);
    }

    private static void appointment(WorldRuntime world, String meetingId, long base,
            long effectiveAt, long endsAt) throws Exception {
        world.scheduleEngineeringAppointmentConvene(meetingId + "-c", meetingId + "-c-task",
                base, WorldWebScenario.ENGINE_A, "alice", meetingId, base + 50, base + 100,
                base + 200);
        List<String> voters = List.of("alice", "bob", "carol");
        for (int index = 0; index < voters.size(); index++) {
            world.scheduleEngineeringAttend(meetingId + "-a-" + index,
                    meetingId + "-a-" + index + "-task", base + 10 + index,
                    WorldWebScenario.ENGINE_A, voters.get(index), meetingId, false);
        }
        world.scheduleEngineeringMaterial(meetingId + "-m", meetingId + "-m-task", base + 30,
                WorldWebScenario.ENGINE_A, "alice", meetingId, "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal(meetingId + "-p", meetingId + "-p-task",
                base + 40, WorldWebScenario.ENGINE_A, "alice", meetingId, List.of(),
                new BoardModel.AppointmentRequest("mgr-a", "dave", WorldWebScenario.ENGINE_A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        effectiveAt, endsAt));
        for (int index = 0; index < voters.size(); index++) {
            world.scheduleEngineeringVote(meetingId + "-v-" + index,
                    meetingId + "-v-" + index + "-task", base + 120 + index,
                    WorldWebScenario.ENGINE_A, voters.get(index), meetingId,
                    BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement(meetingId + "-i", meetingId + "-i-task", base + 220,
                WorldWebScenario.ENGINE_A, "alice", meetingId);
    }

    /** 富状态夹具：五席预算/任免 → 签约 → 开工 → 请求验收 → 开票 → 客户验收/付款。 */
    private void prepareRich(Path journal) throws Exception {
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(journal, config())) {
            prepareReading(world);
            prepareGovernance(world);
            world.scheduleEngineeringAction("sign-b1", "sign-b1-task", 1_350L,
                    WorldWebScenario.ENGINE_A, "dave", "SIGN", CONTRACT, "", 0, 0,
                    BigDecimal.ZERO, "", 0);
            world.scheduleEngineeringAction("start-w", "start-w-task", 1_450L,
                    WorldWebScenario.ENGINE_A, "dave", "START_WORK", CONTRACT, "W-B1", 0, 2,
                    BigDecimal.ZERO, "", 0);
            world.scheduleEngineeringAction("req-acc", "req-acc-task", 3_500L,
                    WorldWebScenario.ENGINE_A, "dave", "REQUEST_ACCEPTANCE", CONTRACT, "", 0, 2,
                    BigDecimal.ZERO, "", 0);
            world.scheduleEngineeringAction("invoice-b1", "invoice-b1-task", 3_800L,
                    WorldWebScenario.ENGINE_A, "dave", "INVOICE", CONTRACT, "", 0, 0,
                    BigDecimal.ZERO, "", 0);
            world.advanceTo(4_000L);
            assertTrue(world.snapshot().engineering().isPresent(), "工程子集必须存在");
        }
    }

    /** 起始夹具：只完成治理与任职，经营动作留给 Web 排程。 */
    private void prepareGovernanceOnly(Path journal) throws Exception {
        prepareGovernanceOnly(journal, "dave");
    }

    /** 同上，但机会只让指定人物读完（用于「未读机会不可行动」边界）。 */
    private void prepareGovernanceOnly(Path journal, String opportunityReader) throws Exception {
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(journal, config())) {
            prepareReading(world, opportunityReader);
            prepareGovernance(world);
        }
    }

    private static WorldWebHost.Opened openEngineering(Path journal) throws Exception {
        return new WorldWebHost.Opened(
                WorldWebScenario.engineeringRuntime(journal, config()),
                WorldWebScenario.engineeringManifest().runId());
    }

    private interface Opener {
        WorldWebHost.Opened open() throws Exception;
    }

    private void start(Opener opener) throws Exception {
        Path keys = workDir.resolve("keys-" + System.nanoTime() + ".txt");
        Files.writeString(keys, String.join("\n", DUMMY_KEYS), StandardCharsets.UTF_8);
        host = new WorldWebHost(opener::open);
        server = new WorldWebServer(host, KeySlots.load(keys), SceneRoutes.open(workDir),
                0, BOOTSTRAP, null, consoleLines::add);
        server.start();
        base = server.baseUrl();
        cookie = null;
    }

    // ------------------------------------------------------------ 1 God 多公司

    @Test
    @DisplayName("God 视图：三家可选、同一快照、切公司不串账，金额与账户表一致")
    void godViewKeepsOneSnapshotAndSeparatesCompanies() throws Exception {
        Path journal = workDir.resolve("rich.journal");
        prepareRich(journal);
        start(() -> openEngineering(journal));
        loginAdmin();

        JsonNode adminView = getJson("/api/admin/view");
        String position = adminView.path("position").asText();
        String cashA = accountCash(adminView, "ENG-A");
        String cashB = accountCash(adminView, "ENG-B");

        JsonNode first = postJson("/api/admin/engineering", "{\"companyId\":\"\"}");
        JsonNode companies = first.path("companies");
        assertEquals(3, companies.size(), "制造公司 + 两家工程公司：" + companies);
        assertEquals(position, first.path("position").asText(), "必须与总览同一确认位置");
        assertEquals("MANUFACTURING", first.path("template").asText());
        assertEquals("NOT_APPLICABLE", first.path("report").path("workOrderScope").asText(),
                "制造模板没有工程工单，不能显示 0");

        JsonNode a = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        assertEquals("ENGINEERING", a.path("template").asText());
        assertEquals(position, a.path("position").asText());
        assertEquals(adminView.path("timeMillis").asText(), a.path("timeMillis").asText(),
                "同一屏必须同一世界时点");
        assertEquals(cashA, a.path("report").path("companyCash").asText(),
                "公司现金必须等于真实账户余额");
        assertTrue(a.path("report").path("balanced").asBoolean(), "工程账平衡式必须成立");
        assertTrue(a.path("report").has("periodBudgetLeft"),
                "God 仍可看客户期间预算余量（人物视图才删除）");
        assertEquals("200.00", a.path("report").path("revenue").asText(),
                "收入只取合同 claims：2 单位 × 100");
        assertEquals(1, a.path("contracts").path("total").asInt());
        JsonNode contract = a.path("contracts").path("rows").get(0);
        assertEquals("2", contract.path("completedUnits").asText());
        assertEquals("1", contract.path("invoicedTranches").asText());
        assertEquals(1, a.path("workOrders").path("total").asInt());
        assertEquals("COMPLETED", a.path("workOrders").path("rows").get(0).path("status").asText());
        assertEquals(1, a.path("requests").path("total").asInt(), "经理请求必须单独成区");
        assertTrue(a.path("requests").path("rows").get(0).path("answered").asBoolean());
        assertEquals(3, a.path("responses").path("total").asInt(),
                "预收、验收、付款各自留下客户回应");
        JsonNode acceptRow = rowOf(a.path("responses"), "kind", "ACCEPT");
        assertEquals("ACCEPTED", acceptRow.path("outcome").asText());
        assertTrue(hasRow(a.path("responses"), "kind", "PAY"), "客户付款另有一条回应");
        assertEquals(2, a.path("payments").path("total").asInt(),
                "只有真实付款进 payments：预收 + 当期/部分：" + a.path("payments")
                        + " responses=" + a.path("responses"));
        assertEquals(0, a.path("waivers").path("total").asInt());
        assertEquals(0, a.path("tasks").path("total").asInt(), "到期后不应残留待办");
        assertTrue(a.path("reports").path("total").asInt() >= 3, "真实回报按公司过滤");

        JsonNode b = postJson("/api/admin/engineering", "{\"companyId\":\"eng-b\"}");
        assertEquals(cashB, b.path("report").path("companyCash").asText());
        assertEquals("4000.00", b.path("report").path("companyCash").asText());
        assertEquals(0, b.path("contracts").path("total").asInt(), "B 公司不能看到 A 的合同");
        assertEquals(0, b.path("payments").path("total").asInt());
        assertEquals(0, b.path("requests").path("total").asInt());
    }

    @Test
    @DisplayName("旧运行（无工程子集）：明确「未启用」，不给样例数字")
    void legacyRunShowsDisabledState() throws Exception {
        Path journal = workDir.resolve("legacy.journal");
        WorldWebHost.Opened opened = WorldWebScenario.open(journal);
        opened.runtime().close();
        start(() -> WorldWebScenario.open(journal));
        loginAdmin();

        JsonNode view = postJson("/api/admin/engineering", "{\"companyId\":\"\"}");
        assertFalse(view.path("enabled").asBoolean());
        assertTrue(view.path("note").asText().contains("未启用"), view.path("note").asText());
        JsonNode report = view.path("report");
        assertTrue(report.path("arRetention").isMissingNode(), "旧档不补工程质保应收");
        assertTrue(report.path("obligation").isMissingNode(), "旧档不补客户义务");
        assertTrue(report.path("periodBudgetLeft").isMissingNode(), "旧档不补客户期间预算");
        assertEquals(0, view.path("payments").path("total").asInt());
        assertEquals(0, view.path("contracts").path("total").asInt());

        HttpResponse<String> switched = post("/api/session/person",
                "{\"personId\":\"investor\"}");
        assertEquals(200, switched.statusCode());
        cookie = cookieOf(switched);
        JsonNode me = getJson("/api/me/engineering");
        assertFalse(me.path("enabled").asBoolean());
        assertEquals(0, me.path("companies").size());
        assertTrue(me.path("note").asText().contains("未启用"));
    }

    // ------------------------------------------------------------ 2 本人视角与权限

    @Test
    @DisplayName("本人视角由会话派生：只见在任公司，董事不看他人选票，God 接口无权限")
    void personalViewIsServerDerivedAndScoped() throws Exception {
        Path journal = workDir.resolve("scoped.journal");
        prepareRich(journal);
        start(() -> openEngineering(journal));
        loginAdmin();

        String position = positionNow();
        personAt("dave");
        JsonNode mine = getJson("/api/me/engineering");
        assertTrue(mine.path("enabled").asBoolean());
        assertEquals(2, mine.path("companies").size(),
                "经理在工程公司在任，同时是制造章程董事：两家都必须出现");
        JsonNode card = null;
        boolean sawManufacturing = false;
        for (JsonNode candidate : mine.path("companies")) {
            if ("eng-a".equals(candidate.path("companyId").asText())) {
                card = candidate;
            }
            if ("MANUFACTURING".equals(candidate.path("template").asText())) {
                sawManufacturing = true;
                assertTrue(candidate.path("periodic").path("available").asBoolean(),
                        "制造卡片必须带周期投影");
            }
        }
        assertNotNull(card, "必须返回在任的 eng-a");
        assertTrue(sawManufacturing, "制造 cufe 必须按真实董事席位出现");
        assertTrue(card.path("manager").asBoolean());
        assertTrue(card.path("customer").path("arCurrent").isTextual(), "金额必须是精确字符串");
        assertTrue(card.path("customer").path("requests").isArray());
        assertTrue(card.path("customer").path("payments").isArray(), "本公司真实到账必须可查");
        assertFalse(mine.toString().contains("periodBudgetLeft"),
                "外部客户的期间预算余量不得发给本人");
        assertFalse(mine.toString().contains("periodClosed"),
                "外部客户的闭期状态不得发给本人");
        assertFalse(mine.toString().contains("poolAccountId"),
                "客户池账户标识不得进入人物 JSON");
        assertFalse(mine.toString().contains("customerAccountId"),
                "外部客户账户标识不得进入人物 JSON");
        assertFalse(card.path("customer").has("period"),
                "由隐藏预算策略导出的期间序号不得进入人物 JSON");
        assertFalse(card.path("customer").has("poolDeclared"),
                "是否声明客户预算也来自隐藏策略，不得进入人物 JSON");
        assertFalse(card.path("customer").has("periodBudgetLeft")
                || card.path("customer").has("periodClosed"),
                "客户内部预算/闭期字段必须服务端不产出");
        for (JsonNode row : card.path("customer").path("payments")) {
            assertFalse(row.has("period"), "到账记录不得携带客户期间序号：" + row);
        }
        assertFalse(mine.toString().contains("opportunity"),
                "未读机会的标题与条款不得进入人物 JSON");
        assertFalse(mine.toString().contains("apiKey") || mine.toString().contains("key-1"),
                "Key 槽位不得进入人物 JSON");
        assertFalse(mine.toString().contains("eng-b"), "别家公司不得出现");

        assertEquals(403, post("/api/admin/engineering", "{\"companyId\":\"\"}").statusCode(),
                "个人会话不能用 God 接口");
        assertEquals(403, get("/api/admin/keys").statusCode(), "个人会话不能读 Key 元数据");
        assertEquals(403, get("/api/admin/routes").statusCode(), "个人会话不能读场景配置");

        assertEquals(400, post("/api/me/engineering/action",
                bodyAt(position, "eng-b", "engineering.sign", "900")).statusCode(),
                "别家公司没有任职必须拒绝");
        assertEquals(400, post("/api/me/engineering/action",
                bodyAt(position, "eng-a", "engineering.sign", "900")
                        .replace("{\"expectedPosition\"",
                                "{\"personPartyId\":\"dave\",\"expectedPosition")).statusCode(),
                "不允许 body 自填身份");
        assertEquals(409, post("/api/me/engineering/action",
                bodyAt("1", "eng-a", "engineering.sign", "900")).statusCode(),
                "过期位置必须先刷新");
        loginAdmin();
        HttpResponse<String> outsider = post("/api/session/person", "{\"personId\":\"investor\"}");
        assertEquals(200, outsider.statusCode());
        cookie = cookieOf(outsider);
        JsonNode none = getJson("/api/me/engineering");
        assertEquals(0, none.path("companies").size(), "无任职无席位不返回任何公司");
        assertEquals(400, post("/api/me/engineering/action",
                bodyAt(position, "eng-a", "engineering.sign", "900")).statusCode(),
                "外人猜公司也不能拿到或改动别家账");
    }

    // ------------------------------------------------------------ 3 真实动作与拒绝

    @Test
    @DisplayName("Web 排程真实动作：签约→开工→客户验收→开票→到账，拒绝可见且请求不等于付款")
    void actionSchedulesRealWorkAndRefusalsAreVisible() throws Exception {
        Path journal = workDir.resolve("action.journal");
        prepareGovernanceOnly(journal);
        start(() -> openEngineering(journal));
        loginAdmin();

        JsonNode before = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        assertEquals(0, before.path("contracts").path("total").asInt(), "起始没有合同");
        String position = positionNow();

        personAt("dave");
        JsonNode receipt = postJson("/api/me/engineering/action",
                bodyAt(position, "eng-a", "engineering.sign", "1350"));
        assertEquals("eng-a", receipt.path("companyId").asText());
        assertEquals("engineering.sign", receipt.path("tool").asText());

        loginAdmin();
        advanceTo("1400");
        JsonNode signed = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        assertEquals(1, signed.path("contracts").path("total").asInt(), "签约必须真实落账");
        assertEquals(1, signed.path("payments").path("total").asInt(),
                "客户预收是真实付款，单独成区");
        assertEquals("ADVANCE", signed.path("payments").path("rows").get(0).path("kind").asText());

        position = positionNow();
        personAt("dave");
        postJson("/api/me/engineering/action",
                bodyAt(position, "eng-a", "engineering.startWork", "1450")
                        .replace("}", ",\"workId\":\"W-B1\",\"units\":\"2\"}"));

        loginAdmin();
        advanceTo("3600");
        JsonNode working = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        assertEquals("COMPLETED", working.path("workOrders").path("rows").get(0)
                .path("status").asText(), "开工在真实工时后完工");
        assertEquals(0, working.path("requests").path("total").asInt());

        position = positionNow();
        personAt("dave");
        postJson("/api/me/engineering/action",
                bodyAt(position, "eng-a", "engineering.requestAcceptance", "3650")
                        .replace("}", ",\"units\":\"2\"}"));

        loginAdmin();
        advanceTo("3660");
        JsonNode requested = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        assertEquals(1, requested.path("requests").path("total").asInt(), "请求只是意图");
        assertFalse(requested.path("requests").path("rows").get(0).path("answered").asBoolean());
        assertEquals(1, requested.path("payments").path("total").asInt(),
                "请求不是付款：付款条数不能增加");
        assertFalse(hasRow(requested.path("responses"), "kind", "ACCEPT"),
                "客户还没验收回应");

        position = positionNow();
        personAt("dave");
        postJson("/api/me/engineering/action",
                bodyAt(position, "eng-a", "engineering.invoice", "3665"));

        loginAdmin();
        advanceTo("3670");
        JsonNode rejected = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        boolean sawRejection = false;
        for (JsonNode row : rejected.path("reports").path("rows")) {
            if (!row.path("accepted").asBoolean()) {
                sawRejection = true;
                assertTrue(row.path("reason").asText().length() > 0, "拒绝必须带原因代码");
            }
        }
        assertTrue(sawRejection, "未验收就开票必须留下真实业务拒绝");

        advanceTo("3800");
        JsonNode accepted = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        JsonNode acceptedRow = rowOf(accepted.path("responses"), "kind", "ACCEPT");
        assertEquals("ACCEPTED", acceptedRow.path("outcome").asText(), "客户必须给出验收回应");
        assertTrue(accepted.path("requests").path("rows").get(0).path("answered").asBoolean());
        assertEquals(1, accepted.path("payments").path("total").asInt(),
                "验收不等于到账：payments 仍只有预收");
        assertEquals("200.00", accepted.path("report").path("revenue").asText(),
                "验收 2 单位才确认收入");

        position = positionNow();
        personAt("dave");
        postJson("/api/me/engineering/action",
                bodyAt(position, "eng-a", "engineering.invoice", "3850"));

        loginAdmin();
        advanceTo("3900");
        JsonNode invoiced = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        assertTrue(new BigDecimal(invoiced.path("report").path("arCurrent").asText()).signum() > 0,
                "验收后开票才形成应收：" + invoiced.path("report"));

        advanceTo("4000");
        JsonNode paid = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        assertEquals(2, paid.path("payments").path("total").asInt(),
                "客户付款单独记账：" + paid.path("payments"));
        assertTrue(paid.path("report").path("balanced").asBoolean(), "全链后账平衡式必须成立");
    }

    // ------------------------------------------------------------ HTTP 与会话

    @Test
    @DisplayName("未读机会：本人公司经理也不能签约，真实拒绝原因为 OPPORTUNITY_NOT_READ")
    void unreadOpportunityCannotSign() throws Exception {
        Path journal = workDir.resolve("unread.journal");
        prepareGovernanceOnly(journal, "erin");
        start(() -> openEngineering(journal));
        loginAdmin();
        String position = positionNow();

        personAt("dave");
        JsonNode mine = getJson("/api/me/engineering");
        assertEquals(2, mine.path("companies").size(),
                "dave 的工程经理与制造董事席位都必须出现");
        assertFalse(mine.toString().contains("opportunity"), "未读机会的标题/条款不得出现在人物 JSON 里");

        postJson("/api/me/engineering/action",
                bodyAt(position, "eng-a", "engineering.sign", "900"));

        loginAdmin();
        advanceTo("1000");
        JsonNode view = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        assertEquals(0, view.path("contracts").path("total").asInt(), "未读机会不能签成合同");
        JsonNode rejected = rowOf(view.path("reports"), "reason", "OPPORTUNITY_NOT_READ");
        assertFalse(rejected.path("accepted").asBoolean(), "该回报必须是拒绝");
    }

    @Test
    @DisplayName("豁免会议召集：非主席经理被拒、跨公司主席被拒、本公司主席且持召集权才受理")
    void conveneWaiverRequiresOwnChairAndConveneTool() throws Exception {
        Path journal = workDir.resolve("convene.journal");
        prepareGovernanceOnly(journal);
        start(() -> openEngineering(journal));
        loginAdmin();
        String position = positionNow();

        personAt("dave");
        assertEquals(400, post("/api/me/engineering/action", bodyAt(position, "eng-a",
                "board.conveneWaiver", "900").replace("}", ",\"meetingId\":\"w1\","
                + "\"materialDeadline\":\"950\",\"opensAt\":\"960\",\"closesAt\":\"980\"}"))
                .statusCode(), "现任经理不是主席，不能召集豁免会议");

        loginAdmin();
        personAt("alice");
        assertEquals(400, post("/api/me/engineering/action", bodyAt(position, "eng-b",
                "board.conveneWaiver", "900").replace("}", ",\"meetingId\":\"w2\","
                + "\"materialDeadline\":\"950\",\"opensAt\":\"960\",\"closesAt\":\"980\"}"))
                .statusCode(), "A 公司主席不能召集 B 公司会议");

        JsonNode receipt = postJson("/api/me/engineering/action", bodyAt(position, "eng-a",
                "board.conveneWaiver", "900").replace("}", ",\"meetingId\":\"w1\","
                + "\"materialDeadline\":\"950\",\"opensAt\":\"960\",\"closesAt\":\"980\"}"));
        assertEquals("board.conveneWaiver", receipt.path("tool").asText(),
                "本公司主席持召集权时才受理");
    }

    @Test
    @DisplayName("真实 BEGIN 屏障与 finish 完成帧分开：开始不等于签约/到账")
    void beginBarrierAndFinishStaySeparate() throws Exception {
        Path journal = workDir.resolve("begin.journal");
        prepareGovernanceOnly(journal);
        start(() -> openEngineering(journal));
        loginAdmin();

        String position = positionNow();
        personAt("dave");
        // 声明工时 100ms：屏障在 1250 真实开始，完成帧仍在 1350
        postJson("/api/me/engineering/action",
                bodyAt(position, "eng-a", "engineering.sign", "1350")
                        .replace("}", ",\"durationMillis\":100}"));

        loginAdmin();
        JsonNode scheduled = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        JsonNode beginRow = null;
        int finishRows = 0;
        for (JsonNode row : scheduled.path("tasks").path("rows")) {
            if ("ENGINEERING_BEGIN_V1".equals(row.path("kind").asText())) {
                beginRow = row;
            }
            if (row.path("taskId").asText().endsWith(":finish")) {
                finishRows += 1;
            }
        }
        assertNotNull(beginRow, "排程后必须先落真实 BEGIN 屏障");
        assertEquals("SCHEDULED", beginRow.path("state").asText(), "屏障只是已排程");
        assertEquals("ENGINEERING_ACTION_V1", beginRow.path("wrappedKind").asText(),
                "包装内层必须是原动作种类");
        assertEquals(0, finishRows, "屏障未到期时没有完成帧");
        assertEquals(0, scheduled.path("contracts").path("total").asInt(), "开始之前没有合同");

        advanceTo("1250");
        JsonNode started = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        boolean sawBeginReport = false;
        for (JsonNode row : started.path("reports").path("rows")) {
            if ("ENGINEERING_BEGIN_V1".equals(row.path("kind").asText())) {
                sawBeginReport = true;
                assertEquals("STARTED", row.path("reason").asText());
                assertTrue(row.path("accepted").asBoolean(), "BEGIN 报告按真实 accepted 显示");
            }
        }
        assertTrue(sawBeginReport, "屏障到期必须留下真实开始报告");
        assertEquals(0, started.path("contracts").path("total").asInt(), "开始不等于签约");
        assertEquals(0, started.path("payments").path("total").asInt(), "开始不等于到账");
        boolean finishPending = false;
        for (JsonNode row : started.path("tasks").path("rows")) {
            if (row.path("taskId").asText().endsWith(":finish")) {
                finishPending = true;
            }
        }
        assertTrue(finishPending, "屏障通过后必须留下独立的完成帧");

        advanceTo("1350");
        JsonNode done = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        assertEquals(1, done.path("contracts").path("total").asInt(), "完成帧到期才真实签约");
        boolean signedFinish = false;
        for (JsonNode row : done.path("reports").path("rows")) {
            if (row.path("taskId").asText().endsWith(":finish")
                    && "SIGNED".equals(row.path("reason").asText())) {
                signedFinish = true;
            }
        }
        assertTrue(signedFinish, "业务结论必须落在原种类的完成帧报告里");
    }

    @Test
    @DisplayName("BEGIN 包装：三字段齐全才识别，缺字段/类型不符一律未知，不当已完成")
    void beginWrapperIsStrictlyRead() {
        String inner = "{\"companyPartyId\":\"eng-a\",\"action\":\"SIGN\",\"contractId\":\"B-1\"}";
        WorldWebHost.BeginWrapper wrapper = WorldWebHost.beginWrapper(
                "{\"targetKind\":\"ENGINEERING_ACTION_V1\",\"dueMillis\":1200,\"payload\":\""
                        + inner.replace("\"", "\\\"") + "\"}");
        assertNotNull(wrapper);
        assertEquals("ENGINEERING_ACTION_V1", wrapper.targetKind());
        assertEquals(1200L, wrapper.dueMillis());

        assertEquals(null, WorldWebHost.beginWrapper(null), "空载荷是未知");
        assertEquals(null, WorldWebHost.beginWrapper("{}"), "缺三字段是未知");
        assertEquals(null, WorldWebHost.beginWrapper(
                "{\"targetKind\":\"X\",\"dueMillis\":\"1200\",\"payload\":\"{}\"}"),
                "dueMillis 必须是整数，字符串不接受");
        assertEquals(null, WorldWebHost.beginWrapper(
                "{\"targetKind\":\"X\",\"dueMillis\":1200}"), "缺 payload 是未知");
        assertEquals(null, WorldWebHost.beginWrapper(
                "{\"targetKind\":\"X\",\"dueMillis\":1200,\"payload\":\"not-json\"}"),
                "内层不是 JSON 对象也是未知");
    }

    private void loginAdmin() throws Exception {
        HttpResponse<String> response = post("/api/session",
                "{\"bootstrap\":\"" + adminBootstrap + "\"}");
        assertEquals(200, response.statusCode(), "管理员登录应成功：" + response.body());
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

    private void advanceTo(String target) throws Exception {
        JsonNode view = getJson("/api/admin/view");
        HttpResponse<String> response = post("/api/admin/advance",
                "{\"expectedPosition\":\"" + view.path("position").asText()
                        + "\",\"targetMillis\":\"" + target + "\"}");
        assertEquals(200, response.statusCode(), "推进应成功：" + response.body());
    }

    private void personAt(String personId) throws Exception {
        HttpResponse<String> response = post("/api/session/person",
                "{\"personId\":\"" + personId + "\"}");
        assertEquals(200, response.statusCode(), "切换到人物 " + personId + " 应成功");
        cookie = cookieOf(response);
    }

    private String positionNow() throws Exception {
        return getJson("/api/admin/view").path("position").asText();
    }

    private static String bodyAt(String position, String companyId, String tool, String due) {
        String suffix = tool.replace('.', '-') + "-" + System.nanoTime();
        return "{\"expectedPosition\":\"" + position + "\",\"commandId\":\"web-" + suffix
                + "\",\"taskId\":\"web-" + suffix + "-task\",\"dueTimeMillis\":\"" + due
                + "\",\"companyId\":\"" + companyId + "\",\"tool\":\"" + tool
                + "\",\"contractId\":\"" + CONTRACT + "\"}";
    }

    private static boolean hasRow(JsonNode section, String field, String value) {
        for (JsonNode row : section.path("rows")) {
            if (value.equals(row.path(field).asText())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode rowOf(JsonNode section, String field, String value) {
        for (JsonNode row : section.path("rows")) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        throw new AssertionError("缺少 " + field + "=" + value + " 的行：" + section);
    }

    private static String accountCash(JsonNode adminView, String accountId) {
        for (JsonNode account : adminView.path("accounts")) {
            if (accountId.equals(account.path("accountId").asText())) {
                return account.path("cash").asText();
            }
        }
        return "";
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertEquals(200, response.statusCode(), path + " 应返回 200：" + response.body());
        return JSON.readTree(response.body());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = post(path, body);
        assertEquals(200, response.statusCode(), path + " 应返回 200：" + response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
