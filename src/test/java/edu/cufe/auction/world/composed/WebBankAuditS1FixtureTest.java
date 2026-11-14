package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cufe.auction.web.KeySlots;
import edu.cufe.auction.web.SceneRoutes;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.web.WorldWebServer;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 银行 Web 读切片的真实非零夹具：先跑组合档既有 S1 真实调用链（任免 → 存款/取现 →
 * 存款资金桶成交 → 真实借贷 → 工期与群组），再用 Web 入口重开同一日志，读取
 * {@code /api/admin/bank}。
 *
 * <p>为什么这样做：全部为零的银行只能证明"表能渲染"，不能证明字段来自正确的那本账。
 * 本测试不写任何 JSON 内部态，只复用 {@link ComposedWorldSelfTest#run(Path)} 的真实动作；
 * 非零存款/贷款/回执都是这些动作的真实结果。</p>
 */
class WebBankAuditS1FixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "bank-web-s1-bootstrap";
    private static final List<String> DUMMY_KEYS = List.of("sk-s1fixture-0000001",
            "sk-s1fixture-0000002", "sk-s1fixture-0000003", "sk-s1fixture-0000004",
            "sk-s1fixture-0000005");

    @Test
    @DisplayName("S1 真实链后重开：God 审计显示非零存款/贷款/回执，且与同一快照一致")
    void godAuditShowsRealNonZeroBankBooks() throws Exception {
        Path dir = Files.createTempDirectory("bank-web-s1").resolve("world.log");
        List<String> failures = ComposedWorldSelfTest.run(dir);
        assertTrue(failures.isEmpty(), "组合档 S1 未通过：" + failures);

        Path workDir = dir.getParent().resolve("web");
        Files.createDirectories(workDir);
        Path keys = workDir.resolve("keys.txt");
        Files.writeString(keys, String.join("\n", DUMMY_KEYS), StandardCharsets.UTF_8);

        List<String> consoleLines = new ArrayList<>();
        try (WorldWebHost host = new WorldWebHost(
                () -> WorldWebScenario.openComposed(dir))) {
            try (WorldWebServer server = new WorldWebServer(host, KeySlots.load(keys),
                    SceneRoutes.open(workDir), 0, BOOTSTRAP, null, consoleLines::add)) {
                server.start();
                String base = server.baseUrl();
                String cookie = login(base, consoleLines);

                JsonNode admin = getJson(base, "/api/admin/view", cookie);
                JsonNode bank = getJson(base, "/api/admin/bank", cookie);
                assertTrue(bank.path("available").asBoolean(), "S1 档必须启用银行");
                assertEquals(admin.path("position").asText(), bank.path("position").asText(),
                        "银行审计必须与同一确认位置一致");
                assertEquals(admin.path("timeMillis").asText(), bank.path("timeMillis").asText());

                JsonNode north = bank.path("banks").get(0);
                BigDecimal reserve = new BigDecimal(north.path("reserve").asText());
                assertTrue(reserve.compareTo(new BigDecimal("3000.00")) > 0,
                        "S1 真实存款后准备金必须高于开局 3000，实际 " + reserve);
                BigDecimal deposits = new BigDecimal(north.path("depositsTotal").asText());
                assertTrue(deposits.signum() > 0,
                        "S1 真实存款后存款负债必须非零，实际 " + deposits);

                boolean nonZeroDeposit = false;
                for (JsonNode row : north.path("customerDeposits")) {
                    if (new BigDecimal(row.path("amount").asText()).signum() > 0) {
                        nonZeroDeposit = true;
                        assertFalse(row.path("depositGl").asText().isBlank(),
                                "非零存款必须有真实总账科目");
                    }
                }
                assertTrue(nonZeroDeposit, "至少一个客户必须有真实非零存款");

                boolean nonZeroLoan = false;
                for (JsonNode row : north.path("loans")) {
                    if (new BigDecimal(row.path("terms").path("amount").asText()).signum() > 0) {
                        nonZeroLoan = true;
                        assertFalse(row.path("borrowerKey").asText().isBlank(),
                                "贷款行必须有真实借款人");
                        assertTrue(new BigDecimal(row.path("terms").path("rateU").asText())
                                .signum() > 0, "贷款条款必须保留真实利率");
                    }
                }
                assertTrue(nonZeroLoan, "S1 真实借贷后必须有非零合同金额的贷款行");

                // S1 已含五条经营资金腿：准备金增加净额 2040，工程存款 100→2140；
                // 时间推进至第五期，真实逐日应付利息累计 2.97，贷款应收利息仍为 0.33。
                assertEquals("5390.00", north.path("reserve").asText(),
                        "准备金必须等于真实账户余额");
                assertEquals("2590.00", north.path("depositsTotal").asText(),
                        "客户存款负债必须等于 cufe 450 + eng-a 2140");
                JsonNode totals = north.path("totals");
                assertEquals("5590.33", totals.path("assets").asText(), "资产合计含准备金与贷款");
                assertEquals("2592.97", totals.path("liabilities").asText(), "负债合计含应付利息");
                assertEquals("2997.36", totals.path("equity").asText(), "权益合计含未结转损益");
                assertEquals("-2.64", totals.path("pnl").asText(), "亏损必须保留负号");
                assertTrue(totals.path("balanced").asBoolean(), "A=L+E 必须平衡");
                assertFalse(totals.path("complete").asBoolean(),
                        "存在未覆盖项时不得称口径完整");
                assertFalse(totals.path("unsupported").isEmpty(), "必须列出未覆盖项");

                // 正常方向：贷方科目为正；原始总账（借正贷负）另存
                JsonNode interest = north.path("interest");
                assertEquals("0.33", interest.path("interestReceivable").asText());
                assertEquals("2.97", interest.path("interestPayable").asText(),
                        "应付利息必须按贷方正常方向显示 +2.97");
                assertEquals("-2.97", north.path("glRaw").path("INT.PAYABLE").asText(),
                        "原始总账必须保留 -2.97");
                JsonNode equityView = north.path("equity");
                assertEquals("3000.00", equityView.path("paidIn").asText(),
                        "实收资本必须显示 +3000.00");
                assertEquals("-3000.00", north.path("glRaw").path("PAIDIN").asText(),
                        "原始实收资本总账必须保留 -3000.00");
                BigDecimal paidIn = new BigDecimal(equityView.path("paidIn").asText());
                BigDecimal retained = new BigDecimal(equityView.path("retained").asText());
                BigDecimal nonDist = new BigDecimal(
                        equityView.path("nonDistributable").asText());
                BigDecimal pnl = new BigDecimal(equityView.path("unbookedPnl").asText());
                BigDecimal totalEquity = new BigDecimal(equityView.path("totalEquity").asText());
                assertEquals(0, paidIn.add(retained).add(nonDist).add(pnl)
                                .compareTo(totalEquity),
                        "权益合计必须等于实收+留存+处置权益+未结转损益");

                // 账面值合计必须等于该银行返回行的独立 BigDecimal 汇总（不串行）
                BigDecimal rowSum = BigDecimal.ZERO.setScale(2);
                for (JsonNode loan : north.path("loans")) {
                    rowSum = rowSum.add(new BigDecimal(loan.path("onbook").asText()));
                }
                assertEquals(rowSum.toPlainString(), north.path("loansOnbookTotal").asText(),
                        "服务端合计必须等于本银行 onbook 行之和");
                assertEquals(1, bank.path("banks").size(), "组合档只有一家银行，不得串加别家");

                JsonNode actions = bank.path("actions");
                assertTrue(actions.size() > 0, "S1 真实动作必须留下回执");
                boolean anyAccepted = false;
                java.util.Set<String> stages = new java.util.TreeSet<>();
                for (JsonNode action : actions) {
                    assertFalse(action.path("commandId").asText().isBlank(), "回执必须有命令标识");
                    assertFalse(action.path("personPartyId").asText().isBlank(), "回执必须有人物");
                    String reason = action.path("reason").asText();
                    String stage = action.path("stage").asText();
                    stages.add(stage);
                    assertEquals(expectedStageLabel(stage), action.path("stageLabel").asText(),
                            "阶段中文说明必须与阶段码一致：" + stage);
                    if (!action.path("accepted").asBoolean()) {
                        assertEquals("REJECTED", stage, "不成立的帧必须是拒绝阶段");
                        assertFalse(action.path("completed").asBoolean());
                    } else if ("STARTED".equals(reason)) {
                        assertEquals("STARTED", stage,
                                "STARTED 只能是已开始，不能当完成");
                        assertFalse(action.path("completed").asBoolean(),
                                "STARTED 不得标记为已完成");
                    }
                    if (action.path("accepted").asBoolean()) {
                        anyAccepted = true;
                    }
                }
                assertTrue(anyAccepted, "至少一条动作必须真实成立");
                assertTrue(stages.contains("STARTED"), "S1 里必须有已开始待完成的帧");
                assertTrue(stages.contains("DRAWN"), "S1 真实放款必须出现已放款阶段");
                assertTrue(stages.contains("APPROVED"), "S1 真实批准必须出现已批准阶段");

                for (String key : DUMMY_KEYS) {
                    assertFalse(bank.toString().contains(key), "银行审计不得泄露 key");
                }

                // 本人视图：owner 必须与 /api/me/view 真实授权账户的属主集合一致，
                // 且绝不把 north 的组织准备金（north-owner）当成本人资金。
                String personCookie = loginPerson(base, consoleLines, "erin");
                JsonNode meView = getJson(base, "/api/me/view", personCookie);
                java.util.Set<String> authorized = new java.util.TreeSet<>();
                for (JsonNode account : meView.path("accounts")) {
                    authorized.add(account.path("ownerId").asText());
                }
                JsonNode me = getJson(base, "/api/me/bank", personCookie);
                assertEquals("erin", me.path("personPartyId").asText());
                assertTrue(me.path("available").asBoolean());
                java.util.Set<String> projected = new java.util.TreeSet<>();
                for (JsonNode owner : me.path("owners")) {
                    projected.add(owner.path("ownerId").asText());
                }
                assertEquals(authorized, projected,
                        "本人银行投影必须与真实授权账户属主一致");
                assertFalse(projected.contains("north-owner"),
                        "经理本人视图不得凭组织准备金越权");
                int erinInGod = 0;
                for (JsonNode action : actions) {
                    if ("erin".equals(action.path("personPartyId").asText())) {
                        erinInGod += 1;
                    }
                }
                assertEquals(erinInGod, me.path("actions").size(),
                        "本人回执条数必须等于上帝审计中本人的条数");
                for (JsonNode action : me.path("actions")) {
                    assertEquals("erin", action.path("personPartyId").asText(),
                            "本人视图只能含自己的动作");
                }
                assertTrue(me.path("actionsEnabled").asBoolean(),
                        "已接入真实人物银行动作，权限仍由动作入口独立判定");
            }
        }
    }

    // ------------------------------------------------------------ 辅助

    /** 阶段码 → 期望的中文说明（与页面/接口约定一致，不读实现）。 */
    private static String expectedStageLabel(String stage) {
        return switch (stage) {
            case "REJECTED" -> "拒绝";
            case "STARTED" -> "已开始，待完成";
            case "PROPOSED" -> "已提案（待借款人接受）";
            case "ACCEPTED" -> "借款人已接受（待银行批准）";
            case "APPROVED" -> "银行已批准（未放款）";
            case "DRAWN" -> "已放款";
            case "UNMAPPED" -> "该帧成立（阶段未映射，按原码核对）";
            default -> "已完成";
        };
    }

    private static String login(String base, List<String> consoleLines) throws Exception {
        String current = currentBootstrap(consoleLines);
        HttpResponse<String> response = post(base, "/api/session",
                "{\"bootstrap\":\"" + current + "\"}", null);
        assertEquals(200, response.statusCode(), response.body());
        return cookieOf(response);
    }

    /** 服务端每次登录都轮换引导凭据，只能从真实控制台输出取最新值。 */
    private static String currentBootstrap(List<String> consoleLines) {
        for (int index = consoleLines.size() - 1; index >= 0; index--) {
            String line = consoleLines.get(index);
            int mark = line.indexOf('：');
            if (line.contains("引导凭据") && mark > 0) {
                return line.substring(mark + 1).trim();
            }
        }
        return BOOTSTRAP;
    }

    private static String loginPerson(String base, List<String> consoleLines, String person)
            throws Exception {
        String admin = login(base, consoleLines);
        HttpResponse<String> response = post(base, "/api/session/person",
                "{\"personId\":\"" + person + "\"}", admin);
        assertEquals(200, response.statusCode(), response.body());
        return cookieOf(response);
    }

    private static JsonNode getJson(String base, String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        HttpResponse<String> response = HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> post(String base, String path, String body,
                                             String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
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
