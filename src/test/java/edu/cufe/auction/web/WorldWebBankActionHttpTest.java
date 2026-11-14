package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankConfig;
import edu.cufe.auction.world.bank.BankOpening;
import edu.cufe.auction.world.bank.BankOpeningRequest;
import edu.cufe.auction.world.bank.BankRules;
import edu.cufe.auction.world.bank.BankingProfile;
import edu.cufe.auction.world.bank.InterestAccrual;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实可操作银行 Web：合法 {@code banking-roles-experiment-v1} 世界，真实登录会话经
 * {@code POST /api/me/bank/action} 走权威 {@code WorldRoleSession.bankAction} 排程，
 * 由世界到期执行；覆盖存取、超额拒绝不动钱、双边贷款分阶段、单次放款、重试幂等与越权拒绝。
 */
class WorldWebBankActionHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "bank-action-bootstrap";
    private static final List<String> DUMMY_KEYS = List.of("sk-bankaction-000001",
            "sk-bankaction-000002", "sk-bankaction-000003", "sk-bankaction-000004",
            "sk-bankaction-000005");
    private static final String BORROWER = "borrower";
    private static final String MANAGER = "mgr";

    private Path workDir;
    private Path journal;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;
    private final List<String> consoleLines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("web.bankaction.work",
                "target/web-bank-action-tests"));
        workDir = root.resolve("bank-action-" + System.nanoTime());
        Files.createDirectories(workDir);
        journal = workDir.resolve("bank.journal");
        Path keys = workDir.resolve("keys.txt");
        Files.writeString(keys, String.join("\n", DUMMY_KEYS), StandardCharsets.UTF_8);
        host = new WorldWebHost(() -> new WorldWebHost.Opened(
                openWorld(journal), "bank-action-test"));
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
    @DisplayName("存入排程后落账；超额取出被拒且不动钱；只读不改变余额")
    void depositSettlesAndOverWithdrawRejected() throws Exception {
        personAt(BORROWER);
        JsonNode before = getJson("/api/me/bank");
        assertEquals("1000.00", ownerCash(before, BORROWER), "开局现金");

        JsonNode receipt = postJson("/api/me/bank/action", action("DEPOSIT", "100.00")
                .put("sourceToken", "CASH|BOR").toString());
        assertTrue(receipt.path("receipt").asText().startsWith("SCHEDULED"),
                "必须先返回已排程：" + receipt);
        assertEquals("SCHEDULED", receipt.path("status").asText());
        // 到期前钱不动
        assertEquals("1000.00", ownerCash(getJson("/api/me/bank"), BORROWER),
                "排程未到期不得瞬时扣款");
        advanceTo(receipt.path("dueTimeMillis").asLong());
        JsonNode after = getJson("/api/me/bank");
        assertEquals("900.00", ownerCash(after, BORROWER), "到期后现金减少");
        assertEquals("100.00", depositAmount(after), "到期后存款增加");

        // 超额取出：排程成立但完成帧拒绝，钱不动
        JsonNode over = postJson("/api/me/bank/action", action("WITHDRAW", "500.00")
                .put("sourceToken", "DEPOSIT|north|borrower").toString());
        assertTrue(over.path("receipt").asText().startsWith("SCHEDULED"));
        advanceTo(over.path("dueTimeMillis").asLong());
        JsonNode rejected = getJson("/api/me/bank");
        assertEquals("100.00", depositAmount(rejected), "超额取出不得动存款");
        assertEquals("900.00", ownerCash(rejected, BORROWER), "超额取出不得动现金");
        boolean sawReject = false;
        for (JsonNode row : rejected.path("actions")) {
            if (row.path("kind").asText().equals("WITHDRAW")
                    && !row.path("accepted").asBoolean()) {
                sawReject = true;
                assertFalse(row.path("reason").asText().isBlank(), "拒绝必须带真实原因码");
                assertFalse(row.has("position"), "本人回执不得带全局位置");
            }
        }
        assertTrue(sawReject, "必须留下真实拒绝回执");
    }

    @Test
    @DisplayName("双边贷款：申请→本人接受→本行经理批准→本人提款→还本；只放款一次且重试幂等")
    void bilateralLoanLifecycle() throws Exception {
        personAt(BORROWER);
        String requestId = "loan-web-1";
        JsonNode requested = postJson("/api/me/bank/action", loan("LOAN_REQUEST", requestId,
                "200.00", "2", "30", "周转", 0L).toString());
        advanceTo(requested.path("dueTimeMillis").asLong());
        JsonNode proposed = loanRow(getJson("/api/me/bank"), requestId);
        assertEquals("PROPOSED", proposed.path("status").asText());
        assertFalse(proposed.path("canDraw").asBoolean(), "未接受不得提款");

        // 客户不能自己批准
        HttpResponse<String> selfApprove = post("/api/me/bank/action",
                loan("LOAN_APPROVE", "ap-self", "0", "0", "0", "", 0L).put("loanId", requestId)
                        .toString(), cookie);
        assertEquals(400, selfApprove.statusCode(), selfApprove.body());

        // 本人接受冻结条款
        JsonNode accepted = postJson("/api/me/bank/action", loan("LOAN_ACCEPT", "accept-1",
                "200.00", "2", "30", "周转", 0L).put("loanId", requestId).toString());
        advanceTo(accepted.path("dueTimeMillis").asLong());
        assertTrue(loanRow(getJson("/api/me/bank"), requestId).path("accepted").asBoolean());

        // 经理不能代客户接受或提款
        personAt(MANAGER);
        HttpResponse<String> managerAccept = post("/api/me/bank/action",
                loan("LOAN_ACCEPT", "accept-mgr", "200.00", "2", "30", "周转", 0L)
                        .put("loanId", requestId).toString(), cookie);
        assertEquals(400, managerAccept.statusCode(), managerAccept.body());
        HttpResponse<String> managerDraw = post("/api/me/bank/action",
                loan("LOAN_DRAW", "draw-mgr", "200.00", "0", "0", "", 0L)
                        .put("loanId", requestId).toString(), cookie);
        assertEquals(400, managerDraw.statusCode(), managerDraw.body());

        // 经理批准本行合同
        JsonNode approved = postJson("/api/me/bank/action", loan("LOAN_APPROVE", "approve-1",
                "200.00", "2", "30", "周转", 0L).put("loanId", requestId).toString());
        advanceTo(approved.path("dueTimeMillis").asLong());
        JsonNode approvedRow = loanRow(getJson("/api/me/bank"), requestId);
        assertEquals(MANAGER, approvedRow.path("approvedBy").asText(), "批准人必须是真实经理");
        assertEquals("APPROVED", approvedRow.path("status").asText());

        // 本人提款：只放一次
        personAt(BORROWER);
        JsonNode drawn = postJson("/api/me/bank/action", loan("LOAN_DRAW", "draw-1",
                "200.00", "2", "30", "周转", 0L).put("loanId", requestId).toString());
        advanceTo(drawn.path("dueTimeMillis").asLong());
        JsonNode drawnView = getJson("/api/me/bank");
        JsonNode drawnRow = loanRow(drawnView, requestId);
        assertEquals("DRAWN", drawnRow.path("status").asText(), "提款阶段：" + drawnView);
        assertEquals("200.00", drawnRow.path("drawn").asText());
        assertEquals("1200.00", borrowerTotal(drawnView), "放款到账一次（现金或存款）");

        // 同请求重试：回执相同且不重复记账
        JsonNode retried = postJson("/api/me/bank/action", loan("LOAN_DRAW", "draw-1",
                "200.00", "2", "30", "周转", 0L).put("loanId", requestId).toString());
        assertEquals(drawn.path("receipt").asText(), retried.path("receipt").asText(),
                "同命令必须返回原回执");
        HttpResponse<String> differentTask = post("/api/me/bank/action",
                loan("LOAN_DRAW", "draw-1", "200.00", "2", "30", "周转", 0L)
                        .put("loanId", requestId).put("taskId", "changed-task").toString(), cookie);
        assertEquals(400, differentTask.statusCode(),
                "同命令改任务标识必须拒绝，不能由网页缓存冒充相同请求");
        // 同一命令标识改内容：必须拒绝，而不是当成新动作
        HttpResponse<String> conflicting = post("/api/me/bank/action",
                loan("LOAN_DRAW", "draw-1", "199.00", "2", "30", "周转", 0L)
                        .put("loanId", requestId).toString(), cookie);
        assertEquals(400, conflicting.statusCode(), conflicting.body());
        assertEquals("1200.00", borrowerTotal(getJson("/api/me/bank")),
                "重试不得重复放款");

        // 再提一次（新命令）必须被拒，钱不变
        HttpResponse<String> secondDraw = post("/api/me/bank/action", loan("LOAN_DRAW", "draw-2",
                "200.00", "0", "0", "", 0L).put("loanId", requestId).toString(), cookie);
        if (secondDraw.statusCode() == 200) {
            advanceTo(JSON.readTree(secondDraw.body()).path("dueTimeMillis").asLong());
        }
        assertEquals("1200.00", borrowerTotal(getJson("/api/me/bank")),
                "不得二次放款：" + getJson("/api/me/bank"));

        // 还本 50
        JsonNode repaid = postJson("/api/me/bank/action", loan("LOAN_REPAY", "repay-1",
                "50.00", "0", "0", "", 0L).put("loanId", requestId).toString());
        advanceTo(repaid.path("dueTimeMillis").asLong());
        assertEquals("150.00", loanRow(getJson("/api/me/bank"), requestId).path("drawn").asText(),
                "还本后合同余额下降");
    }

    @Test
    @DisplayName("越权与协议：他人账户/组织、伪造会话、缺 CSRF 都被拒")
    void tamperAndProtocolRejected() throws Exception {
        personAt(BORROWER);
        // 他人账户（经理的现金账户）不得作为出款桶
        HttpResponse<String> otherAccount = post("/api/me/bank/action", action("WITHDRAW", "10.00")
                .put("sourceToken", "CASH|MGR").toString(), cookie);
        assertEquals(400, otherAccount.statusCode(), otherAccount.body());
        // 非本人任职的组织不得作为动作主体
        HttpResponse<String> otherOrg = post("/api/me/bank/action", action("DEPOSIT", "10.00")
                .put("organizationPartyId", "co").toString(), cookie);
        assertEquals(400, otherOrg.statusCode(), otherOrg.body());
        // 未知合同：允许排程成立但完成帧拒绝；不得凭空产生贷款或动钱
        HttpResponse<String> unknownLoan = post("/api/me/bank/action", loan("LOAN_DRAW",
                "draw-x", "10.00", "0", "0", "", 0L).put("loanId", "no-such-loan").toString(),
                cookie);
        if (unknownLoan.statusCode() == 200) {
            advanceTo(JSON.readTree(unknownLoan.body()).path("dueTimeMillis").asLong());
        } else {
            assertEquals(400, unknownLoan.statusCode(), unknownLoan.body());
        }
        assertEquals("1000.00", ownerCash(getJson("/api/me/bank"), BORROWER),
                "非法合同不得产生资金");
        // 缺 CSRF 标记 / Origin
        HttpRequest bare = HttpRequest.newBuilder(URI.create(base + "/api/me/bank/action"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(action("DEPOSIT", "10.00").toString()))
                .build();
        assertEquals(403, HTTP.send(bare, HttpResponse.BodyHandlers.ofString()).statusCode(),
                "缺 CSRF 必须 403");
        // 上帝会话不得调用人物动作
        loginAdmin();
        HttpResponse<String> adminCall = post("/api/me/bank/action", action("DEPOSIT", "10.00").toString(),
                cookie);
        assertEquals(403, adminCall.statusCode(), "管理员会话不得用人物动作");
    }

    @Test
    @DisplayName("公司经理使用真实公司现金账户存取：账户名不必等于公司标识")
    void companyManagerDepositsAndWithdrawsForSelectedOrganization() throws Exception {
        personAt("ceo");
        JsonNode options = getJson("/api/me/bank").path("options");
        assertTrue(options.path("subjects").toString().contains("\"co\""));
        assertTrue(options.path("cash").toString().contains("CASH|CO"),
                "在任经理应看到已登记公司现金账户，而非猜测 CASH|co");
        JsonNode deposit = postJson("/api/me/bank/action", action("DEPOSIT", "100.00")
                .put("organizationPartyId", "co").put("sourceToken", "CASH|CO").toString());
        advanceTo(deposit.path("dueTimeMillis").asLong());
        loginAdmin();
        assertEquals("3100.00", getJson("/api/admin/bank").path("banks").get(0)
                .path("reserve").asText());
        personAt("ceo");
        JsonNode withdrawal = postJson("/api/me/bank/action", action("WITHDRAW", "10.00")
                .put("commandId", "company-withdraw").put("taskId", "company-withdraw-t")
                .put("organizationPartyId", "co").put("sourceToken", "DEPOSIT|north|co").toString());
        advanceTo(withdrawal.path("dueTimeMillis").asLong());
        loginAdmin();
        JsonNode bank = getJson("/api/admin/bank").path("banks").get(0);
        assertEquals("3090.00", bank.path("reserve").asText());
        assertEquals("90.00", bank.path("depositsTotal").asText());
    }

    @Test
    @DisplayName("批准排程时在任、完成前任期结束：不能批准或产生资金")
    void managerTermEndsBeforeApprovalCompletes() throws Exception {
        personAt(BORROWER);
        JsonNode proposed = postJson("/api/me/bank/action", loan("LOAN_REQUEST", "expiry-loan",
                "200.00", "2", "30", "周转", 0L).toString());
        advanceTo(proposed.path("dueTimeMillis").asLong());
        String loanId = "expiry-loan";
        JsonNode accepted = postJson("/api/me/bank/action", loan("LOAN_ACCEPT", "expiry-accept",
                "200.00", "2", "30", "周转", 0L).put("loanId", loanId).toString());
        advanceTo(accepted.path("dueTimeMillis").asLong());
        advanceTo(30L * 86_400_000L - 500L);
        personAt(MANAGER);
        JsonNode scheduled = postJson("/api/me/bank/action", loan("LOAN_APPROVE", "expiry-approve",
                "200.00", "2", "30", "周转", 0L).put("loanId", loanId).toString());
        advanceTo(scheduled.path("dueTimeMillis").asLong());
        assertFalse(getJson("/api/me/bank").path("entitlements").toString().contains("LOAN_APPROVE"),
                "任期结束后不得继续显示批准权限");
        personAt(BORROWER);
        JsonNode view = getJson("/api/me/bank");
        assertEquals("ACCEPTED", loanRow(view, loanId).path("status").asText(),
                "失效的经理排程不得把接受条款变成已批准");
        assertEquals("1000.00", borrowerTotal(view), "被拒批准不能产生资金");
    }

    // ------------------------------------------------------------ 请求构造

    private static com.fasterxml.jackson.databind.node.ObjectNode action(String kind,
                                                                         String amount) {
        com.fasterxml.jackson.databind.node.ObjectNode body = JSON.createObjectNode();
        String id = "web-" + kind.toLowerCase() + "-" + System.nanoTime();
        body.put("commandId", id);
        body.put("taskId", id + "-t");
        body.put("kind", kind);
        body.put("amount", amount);
        body.put("workMillis", 1000);
        return body;
    }

    /** 贷款动作：利率按百分数换算定标、期限按天换算毫秒。 */
    private static com.fasterxml.jackson.databind.node.ObjectNode loan(String kind, String id,
            String amount, String ratePercent, String tenorDays, String purpose,
            long ignored) {
        com.fasterxml.jackson.databind.node.ObjectNode body = JSON.createObjectNode();
        body.put("commandId", id);
        body.put("taskId", id + "-t");
        body.put("kind", kind);
        body.put("amount", amount);
        body.put("workMillis", 1000);
        body.put("rateU", rateUnits(ratePercent));
        body.put("tenorMs", tenorDays.isBlank() ? 0L
                : Long.parseLong(tenorDays) * 86_400_000L);
        body.put("purpose", purpose);
        return body;
    }

    private static long rateUnits(String percent) {
        if (percent == null || percent.isBlank()) {
            return 0L;
        }
        return new BigDecimal(percent).movePointRight(4).longValueExact();
    }

    private static String borrowerTotal(JsonNode view) {
        return new BigDecimal(ownerCash(view, BORROWER))
                .add(new BigDecimal(depositAmount(view))).toPlainString();
    }

    private static String ownerCash(JsonNode view, String ownerId) {
        for (JsonNode owner : view.path("owners")) {
            if (ownerId.equals(owner.path("ownerId").asText())) {
                return owner.path("cash").asText();
            }
        }
        throw new AssertionError("视图没有该 owner：" + ownerId);
    }

    private static String depositAmount(JsonNode view) {
        for (JsonNode row : view.path("deposits")) {
            if (BORROWER.equals(row.path("partyKey").asText())) {
                return row.path("amount").asText();
            }
        }
        return "0.00";
    }

    private static JsonNode loanRow(JsonNode view, String loanId) {
        for (JsonNode row : view.path("loans")) {
            if (loanId.equals(row.path("loanId").asText())) {
                return row;
            }
        }
        throw new AssertionError("视图没有该合同：" + loanId + " in " + view.path("loans"));
    }

    // ------------------------------------------------------------ 世界与开局

    private static WorldRuntime openWorld(Path journal) throws Exception {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "information", "roles",
                "banking", "custody")) {
            modules.put(name, 1);
        }
        RunManifest manifest = new RunManifest("bank-action-test", 19L,
                RunManifest.RULES_BANKING_ROLES_EXPERIMENT_V1, modules,
                Map.of("profile", "bank-action-test"), RunManifest.MODEL_NONE);
        List<CashLedger.OpeningAccount> openings = List.of(
                new CashLedger.OpeningAccount("RSV-NORTH", "north-owner", money("3000.00"),
                        "EXP 准备金"),
                new CashLedger.OpeningAccount("CO", "co", money("5000.00"), "公司现金"),
                new CashLedger.OpeningAccount("BOR", BORROWER, money("1000.00"), "借款人现金"),
                new CashLedger.OpeningAccount("MGR", MANAGER, money("0.00"), "银行经理现金"),
                new CashLedger.OpeningAccount("CEO", "ceo", money("0.00"), "公司经理现金"));
        OpeningRegistry registry = new OpeningRegistry(
                List.of(new OpeningRegistry.Party("north",
                                OpeningRegistry.PartyKind.ORGANIZATION, "North Bank（实验）"),
                        new OpeningRegistry.Party("co",
                                OpeningRegistry.PartyKind.ORGANIZATION, "借款公司（实验）"),
                        new OpeningRegistry.Party(BORROWER,
                                OpeningRegistry.PartyKind.PERSON, "借款人"),
                        new OpeningRegistry.Party(MANAGER,
                                OpeningRegistry.PartyKind.PERSON, "银行经理"),
                        new OpeningRegistry.Party("ceo",
                                OpeningRegistry.PartyKind.PERSON, "公司经理")),
                List.of(new OpeningRegistry.PropertyOwner("north-owner",
                                OpeningRegistry.OwnerKind.ORGANIZATION, "north"),
                        new OpeningRegistry.PropertyOwner("co",
                                OpeningRegistry.OwnerKind.ORGANIZATION, "co"),
                        new OpeningRegistry.PropertyOwner(BORROWER,
                                OpeningRegistry.OwnerKind.PERSON, BORROWER),
                        new OpeningRegistry.PropertyOwner(MANAGER,
                                OpeningRegistry.OwnerKind.PERSON, MANAGER),
                        new OpeningRegistry.PropertyOwner("ceo",
                                OpeningRegistry.OwnerKind.PERSON, "ceo")),
                List.of(), List.of());
        BankingProfile profile = new BankingProfile(
                Map.of("north", new BankingProfile.BankRef("north", "north", "north-owner",
                        "RSV-NORTH")),
                Map.of("co", new BankingProfile.PartyRef("co", "co", "CO", "dep-co"),
                        BORROWER, new BankingProfile.PartyRef(BORROWER, BORROWER, "BOR", "dep-b"),
                        MANAGER, new BankingProfile.PartyRef(MANAGER, MANAGER, "MGR", null),
                        "ceo", new BankingProfile.PartyRef("ceo", "ceo", "CEO", null)),
                Map.of(),
                Map.of("co", new BankingProfile.RiskBucket("b-co", null, "LOAN.CO", "LLP.CO"),
                        BORROWER, new BankingProfile.RiskBucket("b-b", null, "LOAN.B", "LLP.B")),
                Map.of(), List.of("LOAN.CO", "LOAN.B"), List.of("LLP.CO", "LLP.B"),
                List.of("dep-co", "dep-b"), null, null, null);
        BankConfig config = new BankConfig.Builder(profile).version(19)
                .archiveId("BANK-ACTION-WEB-EXP")
                .label(RunManifest.RULES_BANKING_ROLES_EXPERIMENT_V1)
                .coverageRule("DECLARED_UNIVERSE_V1")
                .interest(BankConfig.Interest.act365())
                .depositOffers(Map.of("north", new BankConfig.DepositOffer("north", "OFF-north",
                        InterestAccrual.rateU(new BigDecimal("0.02")), 365L * 86_400_000L,
                        BankRules.CREDIT_OFFER)))
                .groupMap(Map.of("co", "G-CO", BORROWER, "G-B"))
                .thresholds(Map.of("cet1_min", money("0.01"), "cet1_buffer", money("0.01"),
                        "t1_min", money("0.01"), "tcr_min", money("0.01"),
                        "lev_min", money("0.01"), "single_name", money("9.90"),
                        "group_name", money("9.90"), "lcr_min", money("0.01"),
                        "maturity_gap_min", money("-0.90"),
                        "impair_cover_min", money("0.01")))
                .build();
        BankOpening.DeclaredEndowment endowment = new BankOpening.DeclaredEndowment(
                Map.of("north", new BankOpening.BankBalance(Map.of(), "EXP 银行实收资本历史禀赋")),
                List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(),
                "bank-action-opening-endowment");
        BankOpeningRequest request = BankOpeningRequest.of(config, endowment, Map.of(), Map.of());
        long end = 30L * 86_400_000L;
        RoleRun.Config roles = new RoleRun.Config(List.of(), List.of(
                new RoleAccess.Appointment("A-MGR", MANAGER, "north",
                        RoleAccess.Role.COMPANY_MANAGER, 0L, end, null),
                new RoleAccess.Appointment("A-CEO", "ceo", "co",
                        RoleAccess.Role.COMPANY_MANAGER, 0L, end, null)));
        InformationRun.Config information = new InformationRun.Config(
                List.of(BORROWER, MANAGER, "ceo"));
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
        currentPerson = personId;
    }

    /** 世界推进：走既有管理员推进入口（不是点击瞬时完成），结束后恢复原人物会话。 */
    private void advanceTo(long targetMillis) throws Exception {
        String person = currentPerson;
        loginAdmin();
        JsonNode view = getJson("/api/admin/view");
        long now = view.path("timeMillis").asLong();
        long target = Math.max(targetMillis, now + 1);
        postJson("/api/admin/advance", "{\"expectedPosition\":\""
                + view.path("position").asText() + "\",\"targetMillis\":\"" + target + "\"}");
        if (person != null) {
            personAt(person);
        }
    }

    private String currentPerson;

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = rawGet(path, cookie);
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return JSON.readTree(response.body());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = post(path, body, cookie);
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
