package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.KeySlots;
import edu.cufe.auction.web.SceneRoutes;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.web.WorldWebServer;
import edu.cufe.auction.world.composed.ComposedResearchScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP验证两个行业、本人权限、系统动作拒绝与实际执行。 */
class BaselineIndustryWebHttpTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private String base;
    private String cookie;
    private final List<String> console = new ArrayList<>();

    @Test void rolesUseExactIndustryRoutesAndCannotSubmitSystemOrPrivateFields() throws Exception {
        Path journal=dir.resolve("world.journal");
        try(var world=ComposedIndustryScenario.openBaseline(journal)) {
            BaselineIndustryWorldTest.appoint(world,DigitalIndustryConfig.COMPANY,DigitalIndustryConfig.MANAGER_PERSON,"web-digital",List.of(0L));
            BaselineIndustryWorldTest.appoint(world,AgedIndustryConfig.COMPANY,AgedIndustryConfig.MANAGER_PERSON,"web-aged",List.of(0L,1L));
        }
        Path keys=dir.resolve("dummy-keys.txt");Files.writeString(keys,String.join("\n",List.of("sk-research-test-000001","sk-research-test-000002","sk-research-test-000003","sk-research-test-000004","sk-research-test-000005")));
        try(var host=new WorldWebHost(()->{var world=ComposedIndustryScenario.openBaseline(journal);return new WorldWebHost.Opened(world,world.profile().manifest().runId());});
            var server=new WorldWebServer(host,KeySlots.load(keys),SceneRoutes.open(dir),0,"research-bootstrap",null,console::add)) {
            server.start();base=server.baseUrl();admin();
            var god=JSON.readTree(request("GET","/api/admin/operations",null).body());assertEquals(7,god.path("companies").size());assertTrue(god.toString().contains("scenarioTruth"));
            assertEquals(403,request("POST","/api/me/digital/action","{}").statusCode());
            person("frank");assertEquals(0,JSON.readTree(request("GET","/api/me/operations",null).body()).path("companies").size());
            for(String kind:List.of("digital","aged")) {
                String company=kind+"-company",manager=kind+"-manager",route="/api/me/"+kind+"/action";
                admin();person(manager);var view=JSON.readTree(request("GET","/api/me/operations",null).body());
                assertEquals(1,view.path("companies").size());assertEquals(company,view.path("companies").get(0).path("companyId").asText());
                assertFalse(view.toString().contains("scenarioTruth"));assertFalse(view.toString().contains("pilotPasses"));assertFalse(view.toString().contains("customerBudgetPerPeriod"));assertFalse(view.toString().contains("\"demand\""));
                long due=view.path("timeMillis").asLong()+2000;
                var command=new java.util.LinkedHashMap<String,Object>(Map.of("expectedPosition",view.path("position").asText(),"commandId","web-"+kind,"taskId","web-"+kind+"-task","dueTimeMillis",Long.toString(due),"durationMillis","1000","companyId",company,"action",kind.equals("digital")?"BEGIN_PILOT":"ORDER_RAW","fields",kind.equals("digital")?Map.of():Map.of("orderId","raw-1","units","50")));
                var validFields=command.get("fields");command.put("fields",Map.of("actor","admin"));assertEquals(400,request("POST",route,JSON.writeValueAsString(command)).statusCode());
                command.put("fields",validFields);command.put("companyId",kind.equals("digital")?"aged-company":"digital-company");assertEquals(400,request("POST",route,JSON.writeValueAsString(command)).statusCode());
                command.put("companyId",company);var validAction=command.get("action");command.put("action","CLOSE_PERIOD");command.put("fields",kind.equals("digital")?Map.of("period","0"):Map.of());assertEquals(400,request("POST",route,JSON.writeValueAsString(command)).statusCode());
                command.put("action",validAction);command.put("fields",validFields);
                var scheduled=request("POST",route,JSON.writeValueAsString(command));assertEquals(200,scheduled.statusCode(),scheduled.body());assertEquals(409,request("POST",route,JSON.writeValueAsString(command)).statusCode());
                admin();var advanced=request("POST","/api/admin/advance",JSON.writeValueAsString(Map.of("expectedPosition",JSON.readTree(scheduled.body()).path("position").asText(),"targetMillis",Long.toString(due))));assertEquals(200,advanced.statusCode(),advanced.body());
            }
        }
        try(var world=ComposedIndustryScenario.openBaseline(journal)) {
            assertEquals(DigitalServiceModel.StageStatus.RUNNING,world.operationsRun().orElseThrow().digital().ledger().stageOf(DigitalServiceModel.Stage.PILOT).status());
            assertEquals(50,world.operationsRun().orElseThrow().aged().ledger().orders().get(0).units());
            assertTrue(ComposedCompanyReport.of(world,DigitalIndustryConfig.COMPANY).balanced());assertTrue(ComposedCompanyReport.of(world,AgedIndustryConfig.COMPANY).balanced());
        }
    }

    private void admin() throws Exception {
        String bootstrap = "research-bootstrap";
        for (String line : console) {
            if (line.contains("引导凭据") && line.indexOf('：') > 0) bootstrap = line.substring(line.indexOf('：') + 1).trim();
        }
        var response = request("POST", "/api/session", JSON.writeValueAsString(Map.of("bootstrap", bootstrap)));
        assertEquals(200, response.statusCode(), response.body());
        cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private void person(String id) throws Exception {
        var response = request("POST", "/api/session/person", JSON.writeValueAsString(Map.of("personId", id)));
        assertEquals(200, response.statusCode(), response.body());
        cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (cookie != null) builder.header("Cookie", cookie);
        if (body == null) builder.GET();
        else builder.header("Origin", base).header("X-World-Request", "1")
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
