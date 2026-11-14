package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.EngineeringRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRuntime;

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

/**
 * 真实 HTTP 证据探针（手动运行，不参与回归）：在 127.0.0.1:0 起临时服务，打印每个请求的状态与片段。
 *
 * <p>用法：{@code mvn -o test-compile exec:java -Dexec.classpathScope=test
 * -Dexec.mainClass=edu.cufe.auction.web.EngineeringWebProbe}。只为交接留真实报文日志，
 * 不 mock 后端、不写持久服务。</p>
 */
public final class EngineeringWebProbe {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "engineering-probe-bootstrap";
    private static final long DAY = 86_400_000L;

    private static String base;
    private static String cookie;
    private static String bootstrap = BOOTSTRAP;
    private static final List<String> console = new ArrayList<>();

    private EngineeringWebProbe() {
    }

    /**
     * 入口。
     *
     * @param args 忽略
     * @throws Exception 任意失败
     */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of("target", "engineering-probe-" + System.nanoTime());
        Files.createDirectories(dir);
        Path journal = dir.resolve("probe.journal");
        prepare(journal);
        Path keys = dir.resolve("keys.txt");
        Files.writeString(keys, String.join("\n", List.of("sk-probe-key-value-0001",
                "sk-probe-key-value-0002", "sk-probe-key-value-0003",
                "sk-probe-key-value-0004", "sk-probe-key-value-0005")), StandardCharsets.UTF_8);
        WorldWebHost host = new WorldWebHost(() -> new WorldWebHost.Opened(
                WorldWebScenario.engineeringRuntime(journal, config()),
                WorldWebScenario.engineeringManifest().runId()));
        try (WorldWebServer server = new WorldWebServer(host, KeySlots.load(keys),
                SceneRoutes.open(dir), 0, BOOTSTRAP, null, console::add)) {
            server.start();
            base = server.baseUrl();
            System.out.println("== base " + base);
            loginAdmin();
            callEngineering("");
            callEngineering("eng-a");
            callEngineering("eng-b");
            String position = JSON.readTree(get("/api/admin/view").body()).path("position").asText();
            person("dave");
            callPersonal();
            call("GET", "/api/admin/keys", null);
            call("GET", "/api/admin/routes", null);
            String body = "{\"expectedPosition\":\"" + position + "\",\"commandId\":\"probe-sign\","
                    + "\"taskId\":\"probe-sign-task\",\"dueTimeMillis\":\"900\","
                    + "\"companyId\":\"eng-a\",\"tool\":\"engineering.sign\",\"contractId\":\"B-1\"}";
            call("POST", "/api/me/engineering/action", body);
            call("POST", "/api/me/engineering/action",
                    body.replace("\"companyId\":\"eng-a\"", "\"companyId\":\"eng-b\""));
            loginAdmin();
            String now = JSON.readTree(get("/api/admin/view").body()).path("position").asText();
            call("POST", "/api/admin/advance",
                    "{\"expectedPosition\":\"" + now + "\",\"targetMillis\":\"1200\"}");
            callEngineering("eng-a");
        } finally {
            host.close();
            System.out.println("== closed");
        }
    }

    private static void loginAdmin() throws Exception {
        HttpResponse<String> response = post("/api/session",
                "{\"bootstrap\":\"" + bootstrap + "\"}");
        trace("POST", "/api/session", response);
        cookie = cookieOf(response);
        bootstrap = rotated();
    }

    private static void person(String personId) throws Exception {
        HttpResponse<String> response = post("/api/session/person",
                "{\"personId\":\"" + personId + "\"}");
        trace("POST", "/api/session/person", response);
        cookie = cookieOf(response);
    }

    private static void call(String method, String path, String body) throws Exception {
        HttpResponse<String> response = "GET".equals(method) ? get(path) : post(path, body);
        trace(method, path, response);
    }

    /** 个人工程视图：只打印岗位事实与隐私边界标志，不打印整段 JSON。 */
    private static void callPersonal() throws Exception {
        HttpResponse<String> response = get("/api/me/engineering");
        if (response.statusCode() != 200) {
            trace("GET", "/api/me/engineering", response);
            return;
        }
        String raw = response.body();
        JsonNode view = JSON.readTree(raw);
        JsonNode company = view.path("companies").size() > 0
                ? view.path("companies").get(0) : JSON.createObjectNode();
        System.out.println("GET /api/me/engineering -> 200 | person="
                + view.path("personPartyId").asText()
                + " position=" + view.path("position").asText()
                + " companies=" + view.path("companies").size()
                + " company=" + company.path("companyId").asText()
                + " manager=" + company.path("manager").asBoolean()
                + " payments=" + company.path("customer").path("payments").size()
                + " hasPeriodBudget=" + raw.contains("periodBudgetLeft")
                + " hasPeriodClosed=" + raw.contains("periodClosed")
                + " hasOpportunity=" + raw.contains("opportunity")
                + " hasPoolAccount=" + raw.contains("poolAccountId"));
    }

    private static void callEngineering(String companyId) throws Exception {
        HttpResponse<String> response = post("/api/admin/engineering",
                "{\"companyId\":\"" + companyId + "\"}");
        if (response.statusCode() != 200) {
            trace("POST", "/api/admin/engineering?company=" + companyId, response);
            return;
        }
        JsonNode view = JSON.readTree(response.body());
        System.out.println("POST /api/admin/engineering?company=" + companyId + " -> 200"
                + " | position=" + view.path("position").asText()
                + " time=" + view.path("timeMillis").asText()
                + " enabled=" + view.path("enabled").asBoolean()
                + " selected=" + view.path("companyId").asText()
                + " template=" + view.path("template").asText()
                + " cash=" + view.path("report").path("companyCash").asText()
                + " arCurrent=" + view.path("report").path("arCurrent").asText()
                + " arRetention=" + view.path("report").path("arRetention").asText()
                + " payable=" + view.path("report").path("payable").asText()
                + " revenue=" + view.path("report").path("revenue").asText()
                + " balanced=" + view.path("report").path("balanced").asText()
                + " contracts=" + view.path("contracts").path("total").asInt()
                + " works=" + view.path("workOrders").path("total").asInt()
                + " requests=" + view.path("requests").path("total").asInt()
                + " responses=" + view.path("responses").path("total").asInt()
                + " payments=" + view.path("payments").path("total").asInt()
                + " tasks=" + view.path("tasks").path("total").asInt()
                + " reports=" + view.path("reports").path("total").asInt());
    }

    private static void trace(String method, String path, HttpResponse<String> response) {
        String text = response.body() == null ? "" : response.body().replaceAll("\\s+", " ");
        System.out.println(method + " " + path + " -> " + response.statusCode()
                + " | " + (text.length() > 220 ? text.substring(0, 220) + "…" : text));
    }

    private static String rotated() {
        for (int index = console.size() - 1; index >= 0; index--) {
            String line = console.get(index);
            int mark = line.indexOf('：');
            if (line.contains("引导凭据") && mark > 0) {
                return line.substring(mark + 1).trim();
            }
        }
        return bootstrap;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
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
        String raw = response.headers().firstValue("set-cookie").orElse("");
        return raw.split(";", 2)[0];
    }

    private static EngineeringRun.Config config() {
        EngineeringRun.CompanySpec a = WorldWebScenario.engineeringConfig()
                .specOf(WorldWebScenario.ENGINE_A);
        EngineeringRun.CompanySpec b = WorldWebScenario.engineeringConfig()
                .specOf(WorldWebScenario.ENGINE_B);
        return new EngineeringRun.Config("18c Web 探针参数", List.of(
                new EngineeringRun.CompanySpec(a.companyPartyId(), a.companyOwnerId(),
                        a.companyAccountId(), a.customerAccountId(), a.supplierAccountId(),
                        a.opsAccountId(), 1_000L, 6_000, a.charter(),
                        List.of(new EngineeringRun.ContractPlan("B-1",
                                new BigDecimal("100.00"), new BigDecimal("60.0000"),
                                2000, 1000, 0, 0, List.of(2L, 3L))),
                        new EngineeringRun.CustomerPool("ENG-A-CUS", 30L * DAY,
                                new BigDecimal("5000.00"), 6)),
                new EngineeringRun.CompanySpec(b.companyPartyId(), b.companyOwnerId(),
                        b.companyAccountId(), b.customerAccountId(), b.supplierAccountId(),
                        b.opsAccountId(), 1_000L, 5_000, b.charter(), b.contracts(),
                        new EngineeringRun.CustomerPool("ENG-B-CUS", 30L * DAY,
                                new BigDecimal("5000.00"), 6))),
                Map.of(WorldWebScenario.ENGINE_A, List.of(
                        new EngineeringRun.CustomerEventPlan("adv-b1", WorldWebScenario.ENGINE_A,
                                "B-1", 0, "ADVANCE", 1_000L, 0, new BigDecimal("100.00"),
                                "ENG-A-CUS", "探针证据", "")),
                        WorldWebScenario.ENGINE_B, List.of()));
    }

    private static void prepare(Path journal) throws Exception {
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(journal, config())) {
            world.schedulePublishDocument("pub-budget", "pub-budget-task", 10L, "b-material",
                    WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                    "预算材料", "实验预算材料", "实验材料", 10L, List.of());
            world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                    WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                    "任免材料", "实验任免材料", "实验材料", 10L, List.of());
            world.schedulePublishDocument("pub-opp", "pub-opp-task", 10L,
                    "engineering-opportunity:B-1", WorldWebScenario.INVESTOR,
                    InformationModel.Audience.direct("dave"), "B-1 机会", "实验机会 B-1",
                    "冻结机会", 10L, List.of());
            List<String> persons = List.of("alice", "bob", "carol", "dave", "erin");
            for (int index = 0; index < persons.size(); index++) {
                String reader = persons.get(index);
                world.scheduleStartReading("read-mat-" + reader, "read-mat-" + reader + "-task",
                        20L + index * 2L, reader, "b-material", 1L);
                world.scheduleStartReading("read-appt-" + reader, "read-appt-" + reader + "-task",
                        60L + index * 2L, reader, "appt-material", 1L);
            }
            world.scheduleStartReading("read-opp", "read-opp-task", 100L, "dave",
                    "engineering-opportunity:B-1", 1L);
            world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L,
                    WorldWebScenario.ENGINE_A, "alice", "budget-meeting", 150L, 200L, 300L);
            for (int index = 0; index < persons.size(); index++) {
                world.scheduleEngineeringAttend("budget-a-" + index,
                        "budget-a-" + index + "-task", 110L + index, WorldWebScenario.ENGINE_A,
                        persons.get(index), "budget-meeting", false);
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
                world.scheduleEngineeringVote("budget-v-" + index,
                        "budget-v-" + index + "-task", 220L + index, WorldWebScenario.ENGINE_A,
                        persons.get(index), "budget-meeting", BoardModel.Vote.YES);
            }
            world.scheduleEngineeringVote("budget-v-4", "budget-v-4-task", 224L,
                    WorldWebScenario.ENGINE_A, "erin", "budget-meeting", BoardModel.Vote.NO);
            world.scheduleEngineeringImplement("budget-i", "budget-i-task", 320L,
                    WorldWebScenario.ENGINE_A, "alice", "budget-meeting");
            world.scheduleEngineeringAppointmentConvene("mgr-c", "mgr-c-task", 400L,
                    WorldWebScenario.ENGINE_A, "alice", "mgr-meeting", 450L, 500L, 600L);
            for (int index = 0; index < 3; index++) {
                world.scheduleEngineeringAttend("mgr-a-" + index, "mgr-a-" + index + "-task",
                        410L + index, WorldWebScenario.ENGINE_A, persons.get(index),
                        "mgr-meeting", false);
            }
            world.scheduleEngineeringMaterial("mgr-m", "mgr-m-task", 430L,
                    WorldWebScenario.ENGINE_A, "alice", "mgr-meeting", "m1", "appt-material", 1L);
            world.scheduleEngineeringAppointmentProposal("mgr-p", "mgr-p-task", 440L,
                    WorldWebScenario.ENGINE_A, "alice", "mgr-meeting", List.of(),
                    new BoardModel.AppointmentRequest("mgr-a", "dave",
                            WorldWebScenario.ENGINE_A, RoleAccess.Role.COMPANY_MANAGER,
                            BoardModel.AppointmentAction.APPOINT, 700L, 900L * DAY));
            for (int index = 0; index < 3; index++) {
                world.scheduleEngineeringVote("mgr-v-" + index, "mgr-v-" + index + "-task",
                        520L + index, WorldWebScenario.ENGINE_A, persons.get(index),
                        "mgr-meeting", BoardModel.Vote.YES);
            }
            world.scheduleEngineeringImplement("mgr-i", "mgr-i-task", 620L,
                    WorldWebScenario.ENGINE_A, "alice", "mgr-meeting");
            world.advanceTo(700L);
        }
    }
}
