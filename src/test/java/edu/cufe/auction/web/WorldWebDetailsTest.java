package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.PerformanceModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RunManifest;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正式回归候选：管理详情页（公司/表现/人物任职/信息阅读）对真实 Runtime 与真实回环 HTTP。
 *
 * <p>每个用例先在私有工作目录里用真实 {@link WorldRuntime} 排程并推进，产生真实的采购、生产、
 * 撤销、发表、阅读与资本流事实，再启动 {@link WorldWebHost} + {@link WorldWebServer} 读回页面。
 * 不使用反射改状态、不伪造 journal、不 mock 服务响应。</p>
 *
 * <p>工作目录默认 {@code target/web-details-tests}（正式集成后的构建输出），可用系统属性
 * {@code web.details.work} 覆盖；私有验收运行指向私有 {@code _work}，不写主树 target。</p>
 */
class WorldWebDetailsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "details-junit-bootstrap";
    private static final long DAY = 86_400_000L;
    private static final List<String> DUMMY_KEYS = List.of("sk-dummy-fixture-00001",
            "sk-dummy-fixture-00002", "sk-dummy-fixture-00003", "sk-dummy-fixture-00004",
            "sk-dummy-fixture-00005");

    private Path workDir;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;
    private final List<String> consoleLines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("web.details.work", "target/web-details-tests"));
        workDir = root.resolve("details-" + System.nanoTime());
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

    // ------------------------------------------------------------ 1 公司报表与业务进度

    @Test
    @DisplayName("公司页：真实采购与生产推进后，报表金额是精确 BigDecimal 字符串且账平衡")
    void companyReportFollowsRealManufacturingProgress() throws Exception {
        Path journal = workDir.resolve("company.journal");
        String[] expectedCash = new String[1];
        long[] expectedRaw = new long[1];
        long[] expectedFinished = new long[1];
        prepare(journal, runtime -> {
            // 真实治理链：召集 → 预算提案 → 三票赞成 → 通过 → 实施，之后才允许采购与投产。
            long conveneAt = 1_000L;
            long materialDeadline = conveneAt + 5_000L;
            long opensAt = conveneAt + 10_000L;
            long closesAt = conveneAt + 20_000L;
            String meetingId = "meeting:budget-1";
            runtime.scheduleBoardMeeting("cmd-convene-1", "task-convene-1", conveneAt, "s1",
                    meetingId, materialDeadline, opensAt, closesAt,
                    List.of("s1", "s2", "s3", "s4", "s5"), List.of());
            runtime.scheduleBudgetProposal("cmd-prop-1", "task-prop-1", conveneAt + 1L, "s3",
                    meetingId, List.of(
                            new BoardModel.BudgetLine(0L, BoardModel.BudgetCategory.PROCUREMENT,
                                    money("500.00")),
                            new BoardModel.BudgetLine(0L,
                                    BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                    money("300.00"))));
            for (String seat : List.of("s1", "s2", "s3")) {
                runtime.scheduleBoardVote("cmd-vote-" + seat, "task-vote-" + seat, opensAt, seat,
                        meetingId, BoardModel.Vote.YES);
            }
            runtime.advanceTo(closesAt);
            runtime.scheduleBudgetImplementation("cmd-impl-1", "task-impl-1", closesAt + 1_000L,
                    "s1", meetingId);
            runtime.advanceTo(closesAt + 1_000L);

            long purchaseAt = closesAt + 2_000L;
            runtime.schedulePurchaseOrder("cmd-po-1", "task-po-1", purchaseAt, "company",
                    "PO-1", 10);
            runtime.advanceTo(purchaseAt + DAY);
            runtime.scheduleProduction("cmd-batch-1", "task-batch-1", purchaseAt + DAY + 1L,
                    "company", "BATCH-1", 10);
            runtime.advanceTo(purchaseAt + 2 * DAY + 1L);

            WorldRuntime.Snapshot snapshot = runtime.snapshot();
            expectedCash[0] = snapshot.accounts().get("CO").cash().toPlainString();
            ManufacturingModel.State ledger = snapshot.manufacturing().orElseThrow().ledger();
            expectedRaw[0] = ledger.rawMaterials().quantity();
            expectedFinished[0] = ledger.finishedGoods().quantity();
            assertEquals(10L, expectedFinished[0], "真实生产批次应产出 10 件");
            assertEquals(2, snapshot.governance().orElseThrow().board().budgets().size(),
                    "预算实施后应存在采购与人工两类上限");
        });
        start(() -> WorldWebScenario.open(journal));
        loginAdmin();

        JsonNode company = getJson("/api/admin/company");
        assertTrue(company.path("manufacturingAvailable").asBoolean());
        JsonNode report = company.path("operatingReport");
        assertTrue(report.path("balanced").asBoolean(), "账平衡式必须成立");
        assertEquals(expectedCash[0], report.path("companyCash").asText(),
                "公司现金必须等于真实账户余额");
        assertTrue(report.path("companyCash").isTextual(), "金额必须是字符串");
        assertEquals(Long.toString(expectedRaw[0]), report.path("rawQuantity").asText());
        assertEquals(Long.toString(expectedFinished[0]), report.path("finishedQuantity").asText());

        JsonNode orders = company.path("purchaseOrders");
        assertEquals(1, orders.path("total").asInt());
        assertEquals("RECEIVED", orders.path("rows").get(0).path("status").asText());
        assertEquals("10", orders.path("rows").get(0).path("quantity").asText());
        assertEquals("50.00", orders.path("rows").get(0).path("unitPrice").asText());

        JsonNode batches = company.path("productionBatches");
        assertEquals(1, batches.path("total").asInt());
        assertEquals("FINISHED", batches.path("rows").get(0).path("status").asText());
        assertEquals("10", batches.path("rows").get(0).path("quantity").asText());
        assertEquals("300.00", batches.path("rows").get(0).path("laborCost").asText());
        assertTrue(batches.path("rows").get(0).path("totalCost").isTextual());

        JsonNode reports = company.path("managementReports");
        assertTrue(reports.path("total").asInt() >= 2, "采购与生产各自留下管理回报");
        assertTrue(reports.path("rows").get(0).path("timeMillis").isTextual());
    }

    // ------------------------------------------------------------ 2 任职撤销与个人视图隔离

    @Test
    @DisplayName("人物页：真实撤销在职记录后状态为 REVOKED，个人视图不含任何管理数据")
    void appointmentRevocationAndPersonalIsolation() throws Exception {
        Path journal = workDir.resolve("people.journal");
        prepare(journal, runtime -> {
            runtime.scheduleRevokeAppointment("cmd-revoke-1", "task-revoke-1", DAY, "A-3");
            runtime.advanceTo(DAY);
        });
        start(() -> WorldWebScenario.open(journal));
        loginAdmin();

        JsonNode people = getJson("/api/admin/people");
        JsonNode revoked = findRow(people.path("appointments").path("rows"), "appointmentId", "A-3");
        assertNotNull(revoked, "被撤销的任职仍在历史里");
        assertEquals("REVOKED", revoked.path("status").asText());
        assertEquals(Long.toString(DAY), revoked.path("revokedAt").asText());
        assertEquals(Long.toString(DAY), revoked.path("effectiveEnd").asText());
        assertFalse(revoked.path("active").asBoolean());
        JsonNode kept = findRow(people.path("appointments").path("rows"), "appointmentId", "A-1");
        assertEquals("ACTIVE", kept.path("status").asText());

        String adminCookie = cookie;
        HttpResponse<String> switched = post("/api/session/person",
                "{\"personId\":\"carol\"}");
        assertEquals(200, switched.statusCode());
        cookie = cookieOf(switched);

        JsonNode person = getJson("/api/me/view");
        Set<String> fields = new LinkedHashSet<>();
        person.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("personPartyId", "timeMillis", "tools", "accounts", "holdings",
                "market", "appointments", "catalog", "readings", "observations", "permissions"),
                fields, "个人视图字段集合不得新增管理字段");
        assertEquals(0, person.path("appointments").size(), "撤销后本人不再有有效任职");
        assertFalse(person.toString().contains("A-3"), "个人视图不得泄露任职审计标识");
        assertFalse(person.toString().contains("managementReports"));
        assertEquals(403, get("/api/admin/people").statusCode(), "个人身份不得读管理页");

        cookie = adminCookie;
        assertEquals(401, get("/api/admin/people").statusCode(), "切走后旧管理员会话失效");
    }

    // ------------------------------------------------------------ 3 信息发布与阅读经历

    @Test
    @DisplayName("信息页：真实发表与阅读完成后，版本、进行中、经历都来自 Runtime")
    void informationLifecycleFromRealRuntime() throws Exception {
        Path journal = workDir.resolve("info.journal");
        long startAt = DAY + 1_000L;
        long finishAt = startAt + 1_000L;
        prepare(journal, runtime -> {
            runtime.schedulePublishDocument("cmd-doc-1", "task-doc-1", DAY, "DOC-1", "alice",
                    InformationModel.Audience.publicAudience(), "季度经营简报",
                    "本季度完成两批生产。", "公开来源：公司简报", 1_000L, List.of());
            runtime.advanceTo(DAY);
            runtime.scheduleStartReading("cmd-read-1", "task-read-1", startAt, "bob", "DOC-1", 1L);
            runtime.advanceTo(startAt);
        });
        start(() -> WorldWebScenario.open(journal));
        loginAdmin();

        JsonNode before = getJson("/api/admin/information");
        JsonNode documents = before.path("documents");
        assertEquals(1, documents.path("total").asInt());
        JsonNode document = documents.path("rows").get(0);
        assertEquals("DOC-1", document.path("docId").asText());
        assertEquals("季度经营简报", document.path("title").asText());
        assertEquals("1", document.path("latestVersion").asText());
        assertEquals("PUBLIC", document.path("audienceKind").asText());
        assertEquals("本季度完成两批生产。", document.path("bodyPreview").asText());
        assertEquals(1, before.path("activeReadings").path("total").asInt());
        assertEquals("READING",
                before.path("activeReadings").path("rows").get(0).path("status").asText());
        assertEquals(0, before.path("observations").path("total").asInt(),
                "未完成不产生阅读经历");

        JsonNode adminView = getJson("/api/admin/view");
        HttpResponse<String> advanced = post("/api/admin/advance",
                "{\"expectedPosition\":\"" + adminView.path("position").asText()
                        + "\",\"targetMillis\":\"" + finishAt + "\"}");
        assertEquals(200, advanced.statusCode());

        JsonNode after = getJson("/api/admin/information");
        assertEquals(0, after.path("activeReadings").path("total").asInt());
        assertEquals(1, after.path("closedReadings").path("total").asInt());
        assertEquals("COMPLETED",
                after.path("closedReadings").path("rows").get(0).path("status").asText());
        JsonNode experience = after.path("observations").path("rows").get(0);
        assertEquals(1, after.path("observations").path("total").asInt());
        assertEquals("季度经营简报", experience.path("title").asText());
        assertEquals("bob", experience.path("personPartyId").asText());
        assertEquals(Long.toString(finishAt), experience.path("completedAt").asText());
    }

    // ------------------------------------------------------------ 4 表现对齐、资本流与精度

    @Test
    @DisplayName("表现页：与 Runtime performance 完全一致，资本流真实，金额 >2^53 保持精度")
    void performanceMatchesRuntimeAndKeepsHugePrecision() throws Exception {
        Path journal = workDir.resolve("perf.journal");
        BigDecimal hugeCash = new BigDecimal("9007199254740993.01");
        String[] expectedReturn = new String[1];
        String[] expectedIndex = new String[1];
        String[] expectedDrawdown = new String[1];
        long[] expectedPosition = new long[1];
        prepareCustom(journal, hugeCash, runtime -> {
            runtime.scheduleCapitalFlow("cmd-flow-1", "task-flow-1", DAY, "customer",
                    "CUS", "INV", new BigDecimal("500.00"), 0L, "fixture:capital");
            runtime.advanceTo(DAY);
            expectedPosition[0] = runtime.snapshot().position();
            PerformanceModel.Performance performance =
                    runtime.performance("investor", 1L, expectedPosition[0]);
            expectedReturn[0] = text(performance.returnRate());
            expectedIndex[0] = text(performance.index());
            expectedDrawdown[0] = text(performance.maxDrawdown());
        });
        start(() -> customOpened(journal, hugeCash));
        loginAdmin();

        JsonNode page = getJson("/api/admin/performance");
        assertEquals(Long.toString(expectedPosition[0]), page.path("position").asText());
        assertTrue(page.path("performanceAvailable").asBoolean());
        assertEquals(1, page.path("flows").path("total").asInt(), "恰好一条已确认资本流");
        JsonNode flow = page.path("flows").path("rows").get(0);
        assertEquals("CUS", flow.path("fromAccountId").asText());
        assertEquals("INV", flow.path("toAccountId").asText());
        assertEquals("500.00", flow.path("amount").asText());
        assertTrue(flow.path("position").isTextual());

        JsonNode investor = findRow(page.path("owners").path("rows"), "ownerId", "investor");
        assertNotNull(investor);
        assertEquals("1", investor.path("capitalFlowCount").asText());
        assertEquals(expectedReturn[0], nullableText(investor.path("returnRate")));
        assertEquals(expectedIndex[0], nullableText(investor.path("index")));
        assertEquals(expectedDrawdown[0], nullableText(investor.path("maxDrawdown")));
        assertEquals("9007199254741493.01", investor.path("cash").asText(),
                "超过 2^53 的金额必须逐字保留（开户额 + 资本流 500.00）");
        JsonNode account = findRow(page.path("accounts").path("rows"), "accountId", "INV");
        assertEquals("9007199254741493.01", account.path("cash").asText());

        JsonNode companyOwner = findRow(page.path("owners").path("rows"), "ownerId", "company");
        assertEquals("UNSUPPORTED_COMPANY_SCOPE",
                companyOwner.path("valuationStatus").asText());
        assertTrue(companyOwner.path("netWorth").isNull(),
                "组织完整经营净资产不支持时必须为空，不能填 0");
    }

    // ------------------------------------------------------------ 5 有界列表与 400 帧边界

    @Test
    @DisplayName("有界列表：总数为真、只返回 50 行；超过 400 确认帧时期间表现明确未计算")
    void boundedRowsAndFrameLimitAreHonest() throws Exception {
        Path journal = workDir.resolve("bounded.journal");
        int documents = 405;
        prepare(journal, runtime -> {
            for (int index = 0; index < documents; index++) {
                runtime.schedulePublishDocument("cmd-doc-" + index, "task-doc-" + index,
                        DAY + index, "DOC-" + index, "alice",
                        InformationModel.Audience.publicAudience(), "资料 " + index,
                        "正文 " + index, "公开来源", 1_000L, List.of());
            }
            runtime.advanceTo(DAY + documents - 1L);
        });
        start(() -> WorldWebScenario.open(journal));
        loginAdmin();

        JsonNode information = getJson("/api/admin/information");
        assertEquals(documents, information.path("documents").path("total").asInt());
        assertEquals(50, information.path("documents").path("rows").size(),
                "长列表只返回上限行数");
        assertTrue(information.path("documents").path("rows").get(0).path("docId").isTextual());

        JsonNode performance = getJson("/api/admin/performance");
        assertFalse(performance.path("performanceAvailable").asBoolean(),
                "超过帧上限时必须如实标记未计算");
        JsonNode investor = findRow(performance.path("owners").path("rows"), "ownerId",
                "investor");
        assertEquals("NOT_COMPUTED", investor.path("performanceStatus").asText());
        assertTrue(investor.path("returnRate").isNull(), "未计算时不得给出收益率");
        assertFalse(investor.path("performanceNote").asText().isBlank(), "必须给出未计算原因");
    }

    // ------------------------------------------------------------ 6 授权、只读与缓存

    @Test
    @DisplayName("四个管理页：未登录 401、个人 403、POST 不被接受、响应 no-store")
    void adminPagesAreAdminOnlyAndReadOnly() throws Exception {
        Path journal = workDir.resolve("auth.journal");
        prepare(journal, runtime -> { });
        start(() -> WorldWebScenario.open(journal));

        List<String> paths = List.of("/api/admin/company", "/api/admin/people",
                "/api/admin/information", "/api/admin/performance");
        cookie = null;
        for (String path : paths) {
            assertEquals(401, get(path).statusCode(), path + " 未登录必须 401");
        }
        loginAdmin();
        for (String path : paths) {
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode(), path);
            assertEquals("no-store",
                    response.headers().firstValue("cache-control").orElse(""));
            assertTrue(response.headers().firstValue("content-type").orElse("")
                    .startsWith("application/json"));
        }
        HttpResponse<String> keys = get("/api/admin/keys");
        assertEquals(200, keys.statusCode());
        assertFalse(keys.body().contains("sk-dummy-fixture"), "密钥默认遮罩不得泄露明文");

        cookie = cookieOf(post("/api/session/person", "{\"personId\":\"alice\"}"));
        for (String path : paths) {
            assertEquals(403, get(path).statusCode(), path + " 个人身份必须 403");
        }
        for (String path : paths) {
            assertEquals(404, post(path, "{}").statusCode(), path + " 只读，POST 不接受");
        }
    }

    // ------------------------------------------------------------ 7 空状态不伪造

    @Test
    @DisplayName("空世界：公司/表现/信息如实显示未发生，不生成演示成交或新闻")
    void emptyWorldStaysEmpty() throws Exception {
        Path journal = workDir.resolve("empty.journal");
        prepare(journal, runtime -> { });
        start(() -> WorldWebScenario.open(journal));
        loginAdmin();

        JsonNode company = getJson("/api/admin/company");
        assertEquals(0, company.path("purchaseOrders").path("total").asInt());
        assertEquals(0, company.path("productionBatches").path("total").asInt());
        assertEquals(0, company.path("contracts").path("total").asInt());
        assertEquals(0, company.path("meetings").path("total").asInt());
        assertEquals(0, company.path("budgets").path("total").asInt());
        assertEquals(0, company.path("managementReports").path("total").asInt());
        assertTrue(company.path("operatingReport").path("balanced").asBoolean());

        JsonNode information = getJson("/api/admin/information");
        assertEquals(0, information.path("documents").path("total").asInt());
        assertEquals(0, information.path("observations").path("total").asInt());
        assertEquals(6, information.path("readers").path("total").asInt());

        JsonNode performance = getJson("/api/admin/performance");
        assertEquals(0, performance.path("flows").path("total").asInt());

        JsonNode adminView = getJson("/api/admin/view");
        assertEquals(0, adminView.path("trades").size(), "不得伪造演示成交");
        assertEquals(0, adminView.path("dividendReports").size(), "不得伪造分红记录");
    }

    // ------------------------------------------------------------ fixture 与 HTTP 工具

    /** 可在私有工作目录执行、允许抛受检异常的世界动作。 */
    private interface WorldAction {
        void run(WorldRuntime runtime) throws Exception;
    }

    private void prepare(Path journal, WorldAction action) throws Exception {
        WorldWebHost.Opened opened = WorldWebScenario.open(journal);
        try (WorldRuntime runtime = opened.runtime()) {
            action.run(runtime);
        }
    }

    private void prepareCustom(Path journal, BigDecimal investorCash, WorldAction action)
            throws Exception {
        WorldWebHost.Opened opened = customOpened(journal, investorCash);
        try (WorldRuntime runtime = opened.runtime()) {
            action.run(runtime);
        }
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

    @Test
    @DisplayName("主体超过页面行数时总数真实，不能在第40个主体静默截断")
    void ownerTotalRemainsAccurateBeyondPageLimit() throws Exception {
        OpeningRegistry baseRegistry = registry();
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>(baseRegistry.owners());
        for (int i = 0; i < 45; i++) {
            owners.add(new OpeningRegistry.PropertyOwner("zz-fixture-" + i,
                    OpeningRegistry.OwnerKind.AGGREGATE, null));
        }
        OpeningRegistry expanded = new OpeningRegistry(baseRegistry.parties(), owners,
                baseRegistry.securities(), baseRegistry.allocations());
        Path journal = workDir.resolve("owners.journal");
        start(() -> customOpened(journal, money("0.00"), expanded));
        loginAdmin();
        JsonNode page = getJson("/api/admin/performance");
        assertEquals(57, page.path("owners").path("total").asInt());
        assertEquals(50, page.path("owners").path("rows").size());
    }

    /** 用真实 Runtime 打开自定义开局（仅现金数额不同），装配与实验档案一致的登记/章程/任职。 */
    private static WorldWebHost.Opened customOpened(Path journal, BigDecimal investorCash)
            throws Exception {
        return customOpened(journal, investorCash, registry());
    }

    private static WorldWebHost.Opened customOpened(Path journal, BigDecimal investorCash,
                                                   OpeningRegistry openingRegistry) throws Exception {
        RunManifest manifest = WorldWebScenario.manifest();
        List<CashLedger.OpeningAccount> openings = List.of(
                new CashLedger.OpeningAccount("ACC-FUND", "fund-a", money("1000.00"), "实验开局"),
                new CashLedger.OpeningAccount("INV", WorldWebScenario.INVESTOR, investorCash,
                        "实验开局"),
                new CashLedger.OpeningAccount("CO", "company", money("1000.00"), "实验开局"),
                new CashLedger.OpeningAccount("SUP", "supplier", money("100.00"), "实验开局"),
                new CashLedger.OpeningAccount("CUS", "customer", money("2000.00"), "实验开局"),
                new CashLedger.OpeningAccount("OPS", "ops", money("0.00"), "实验开局"),
                new CashLedger.OpeningAccount("LOCK", "locked", money("0.00"), "实验开局"));
        WorldRuntime runtime = new WorldRuntime(journal, manifest, openings, openingRegistry,
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                        "SUP", "CUS", "OPS"), WorldWebScenario.SYMBOL), charter(), roles(),
                information(), decisions());
        return new WorldWebHost.Opened(runtime, manifest.runId());
    }

    private static OpeningRegistry registry() {
        String symbol = WorldWebScenario.SYMBOL;
        return new OpeningRegistry(
                List.of(new OpeningRegistry.Party(WorldWebScenario.COMPANY,
                                OpeningRegistry.PartyKind.ORGANIZATION, "中央财经制造公司（实验）"),
                        new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "甲"),
                        new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "乙"),
                        new OpeningRegistry.Party("carol", OpeningRegistry.PartyKind.PERSON, "丙"),
                        new OpeningRegistry.Party("dave", OpeningRegistry.PartyKind.PERSON, "丁"),
                        new OpeningRegistry.Party("erin", OpeningRegistry.PartyKind.PERSON, "戊"),
                        new OpeningRegistry.Party(WorldWebScenario.INVESTOR,
                                OpeningRegistry.PartyKind.PERSON, "外部投资者")),
                List.of(new OpeningRegistry.PropertyOwner("fund-a",
                                OpeningRegistry.OwnerKind.PRODUCT, null),
                        new OpeningRegistry.PropertyOwner(WorldWebScenario.INVESTOR,
                                OpeningRegistry.OwnerKind.PERSON, WorldWebScenario.INVESTOR),
                        new OpeningRegistry.PropertyOwner("alice",
                                OpeningRegistry.OwnerKind.PERSON, "alice"),
                        new OpeningRegistry.PropertyOwner("bob",
                                OpeningRegistry.OwnerKind.PERSON, "bob"),
                        new OpeningRegistry.PropertyOwner("carol",
                                OpeningRegistry.OwnerKind.PERSON, "carol"),
                        new OpeningRegistry.PropertyOwner("dave",
                                OpeningRegistry.OwnerKind.PERSON, "dave"),
                        new OpeningRegistry.PropertyOwner("erin",
                                OpeningRegistry.OwnerKind.PERSON, "erin"),
                        new OpeningRegistry.PropertyOwner("company",
                                OpeningRegistry.OwnerKind.ORGANIZATION, WorldWebScenario.COMPANY),
                        new OpeningRegistry.PropertyOwner("supplier",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("customer",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("ops",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("locked",
                                OpeningRegistry.OwnerKind.AGGREGATE, null)),
                List.of(new OpeningRegistry.Security(symbol, WorldWebScenario.COMPANY, 200L,
                        money("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("INV", symbol, 100L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("ACC-FUND", symbol, 20L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("LOCK", symbol, 60L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.LOCKED),
                        new OpeningRegistry.ShareAllocation("CO", symbol, 20L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.TREASURY)));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter(WorldWebScenario.COMPANY, List.of(
                new BoardModel.Seat("s1", WorldWebScenario.CHAIR),
                new BoardModel.Seat("s2", "bob"), new BoardModel.Seat("s3", "carol"),
                new BoardModel.Seat("s4", "dave"), new BoardModel.Seat("s5", "erin")),
                "s1", BoardModel.QUORUM);
    }

    private static RoleRun.Config roles() {
        long end = 30L * DAY;
        return new RoleRun.Config(
                List.of(new RoleAccess.ProductMandate(WorldWebScenario.COMPANY, "fund-a",
                        List.of("ACC-FUND"))),
                List.of(new RoleAccess.Appointment("A-1", "alice", WorldWebScenario.COMPANY,
                                RoleAccess.Role.RESEARCHER, 0L, end, null),
                        new RoleAccess.Appointment("A-2", "bob", WorldWebScenario.COMPANY,
                                RoleAccess.Role.PORTFOLIO_MANAGER, 0L, end, null),
                        new RoleAccess.Appointment("A-3", "carol", WorldWebScenario.COMPANY,
                                RoleAccess.Role.RISK_OFFICER, 0L, end, null),
                        new RoleAccess.Appointment("A-4", "dave", WorldWebScenario.COMPANY,
                                RoleAccess.Role.EXECUTION_TRADER, 0L, end, null)));
    }

    private static InformationRun.Config information() {
        return new InformationRun.Config(List.of("alice", "bob", "carol", "dave", "erin",
                WorldWebScenario.INVESTOR));
    }

    private static DecisionModel.Config decisions() {
        DecisionModel.Usage zero = new DecisionModel.Usage(0, 0, 0, money("0.00"));
        Map<String, DecisionModel.Usage> caps = new TreeMap<>();
        for (String person : List.of("alice", "bob", "carol", "dave", "erin")) {
            caps.put(person, zero);
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED,
                "scripted:web-workbench", "scripted-v1", Map.of("wake", 1L), Map.of(),
                new DecisionModel.Limits(1, 1_000, 1_000, 200, 200, 1, 1, 1_000),
                new DecisionModel.Budget(zero, caps));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    // ------------------------------------------------------------ HTTP 与会话

    private void loginAdmin() throws Exception {
        HttpResponse<String> response = post("/api/session",
                "{\"bootstrap\":\"" + BOOTSTRAP + "\"}");
        assertEquals(200, response.statusCode(), "管理员登录应成功");
        cookie = cookieOf(response);
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertEquals(200, response.statusCode(), path + " 应返回 200");
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

    private static JsonNode findRow(JsonNode rows, String field, String value) {
        for (JsonNode row : rows) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        return null;
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String nullableText(JsonNode node) {
        return node.isNull() ? null : node.asText();
    }
}
