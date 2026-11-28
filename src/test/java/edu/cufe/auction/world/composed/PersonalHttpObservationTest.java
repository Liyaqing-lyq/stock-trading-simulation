package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import edu.cufe.auction.web.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 本人通过真实 HTTP 切换身份、读取私有页面及提交业务时的可见边界。 */
class PersonalHttpObservationTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final List<String> SCOPED = List.of("groups", "engineering", "manufacturing", "research", "operations");
    private static final List<String> OTHER = List.of("view", "bank", "dialogue", "interview", "social", "evidence");
    private final List<String> console = new ArrayList<>();
    private String base, cookie;
    private WorldRuntime runtime;

    @Test void onlyVisibleChangesInvalidateTheRelevantPersonsCommands() throws Exception {
        Path file = dir.resolve("v2.journal");
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            OperationsFixture.grantBudgetAndAppoint(world, "resources", ComposedIndustryScenario.DIRECTORS,
                    ComposedIndustryScenario.MANAGER, "scope-appt", List.of(0L));
            long due = world.snapshot().timeMillis() + 1;
            world.schedulePublishDocument("pub", "pub-work", due, "scope-material", "alice",
                    InformationModel.Audience.direct("nina"), "私发资料", "第一版", "直接投递", 10, List.of());
            world.advanceTo(due);
        }
        String firstRuntimeToken;
        try (var host = new WorldWebHost(() -> open(file, false)); var routes = SceneRoutes.open(dir);
             var server = server(host, routes)) {
            server.start(); base = server.baseUrl(); admin(); person("nina");
            var ordinary = capture();
            String groups = token(ordinary, "groups");
            long now = ordinary.get("groups").required("timeMillis").asLong();
            admin(); person(ComposedIndustryScenario.MANAGER);
            var manager = capture();
            long beforeHidden = host.call(() -> runtime.snapshot().position());
            host.call(() -> runtime.schedulePublishDocument("secret", "secret-work", now+100,
                    "private-omar", "alice", InformationModel.Audience.direct("omar"), "给Omar", "私人正文", "定向", 10, List.of()));
            assertTrue(host.call(() -> runtime.snapshot().position()) > beforeHidden);
            assertEquals(manager, capture());
            admin(); person("nina"); assertEquals(ordinary, capture());
            var read = new LinkedHashMap<String,Object>(Map.of("docId", "scope-material", "version", "1",
                    "commandId", "my-read", "taskId", "my-read-work", "dueTimeMillis", now+1));
            send("read", read, token(manager, "groups"), 409);
            send("read", read, token(ordinary, "research"), 409);
            var post = Map.<String,Object>of("commandId", "publish-local", "taskId", "publish-local-work",
                    "action", "PLATFORM_POST", "dueTimeMillis", now + ordinary.get("groups").at("/actionMillis/PLATFORM_POST").asLong(),
                    "title", "本人发布", "body", "公开文本", "cites", List.of());
            send("groups/action", post, Long.toString(beforeHidden+1), 409);
            send("engineering/action", Map.of("commandId", "attend", "taskId", "attend-work", "dueTimeMillis", now+1,
                    "companyId", "resources", "tool", "board.attend", "meetingId", "meeting"), groups, 409);
            var denied = new LinkedHashMap<>(read); denied.put("docId","private-omar");
            var unknown = new LinkedHashMap<>(read); unknown.put("docId","absent");
            assertEquals(send("read", denied, groups, 400), send("read", unknown, groups, 400));
            long beforeRead = host.call(() -> runtime.snapshot().position());
            send("read", read, groups, 200);
            long afterRead = host.call(() -> runtime.snapshot().position());
            assertTrue(afterRead > beforeRead);
            send("read", read, groups, 409);
            assertEquals(afterRead, (long) host.call(() -> runtime.snapshot().position()));
            admin(); person(ComposedIndustryScenario.MANAGER);
            var extraction = Map.<String,Object>of("commandId", "extract", "taskId", "extract-work",
                    "dueTimeMillis", now+2000, "durationMillis", "1000", "companyId", "resources",
                    "action", "BEGIN_EXTRACTION", "fields", Map.of("jobId", "scope-job", "seamId", "resource-seam-a", "quantityKg", "100"));
            send("resources/action", extraction, token(manager, "operations"), 200);
            long beforeRepeat = host.call(() -> runtime.snapshot().position());
            send("resources/action", extraction, token(manager, "operations"), 409);
            assertEquals(beforeRepeat, (long) host.call(() -> runtime.snapshot().position()));
            String appointed = get("operations").required("observationToken").asText();
            // 资源命令包含原观察帧，换观察后沿用原 ID 是不同内容，必须拒绝。
            assertEquals("同一命令标识不能对应不同内容", send("resources/action", extraction, appointed, 400).required("message").asText());
            assertEquals(beforeRepeat, (long) host.call(() -> runtime.snapshot().position()));
            host.call(() -> {
                var world = runtime;
                world.schedulePublishDocumentVersion("rev", "rev-work", now+20, "scope-material", "修订本", "第二版", "本人修订", 10, List.of());
                world.scheduleRevokeAppointment("revoke", "revoke-work", now+21, "scope-appt-mgr");
                world.advanceTo(now+21);
                assertTrue(world.canCite("nina", "scope-material", 1));
                return null;
            });
            send("resources/action", extraction, appointed, 409);
            var revoked = get("operations");
            assertTrue(revoked.path("companies").isEmpty());
            send("resources/action", extraction, revoked.required("observationToken").asText(), 400);
            admin(); person("nina");
            send("read", read, token(ordinary,"engineering"), 409);
            var page = get("groups");
            var current = new LinkedHashMap<>(post);
            current.put("dueTimeMillis", page.required("timeMillis").asLong() + page.at("/actionMillis/PLATFORM_POST").asLong());
            var originalPost = send("groups/action", current, page.required("observationToken").asText(), 200);
            var afterPost = host.call(() -> runtime.snapshot().position());
            var afterRefresh = get("groups").required("observationToken").asText();
            assertEquals(originalPost, send("groups/action", current, afterRefresh, 200));
            assertEquals(afterPost, host.call(() -> runtime.snapshot().position()));
            firstRuntimeToken = get("groups").required("observationToken").asText();
        }
        try (var host = new WorldWebHost(() -> open(file, false)); var routes = SceneRoutes.open(dir);
             var server = server(host,routes)) {
            server.start(); base = server.baseUrl(); admin(); person("nina");
            assertNotEquals(firstRuntimeToken, get("groups").required("observationToken").asText());
            send("read", Map.of("docId","scope-material","version","2","commandId","new-read","taskId","new-work",
                    "dueTimeMillis", get("groups").required("timeMillis").asLong()+1), firstRuntimeToken, 409);
        }
    }

    @Test void deliveredPrivateMaterialChangesOnlyItsRecipientsViewsAtTheSamePublicTime() throws Exception {
        Path original = dir.resolve("prefix.journal");
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(original)) {
            OperationsFixture.grantBudgetAndAppoint(world, "resources", ComposedIndustryScenario.DIRECTORS,
                    ComposedIndustryScenario.MANAGER, "two-worlds", List.of(0L));
        }
        Map<String,Map<String,JsonNode>> control = new LinkedHashMap<>();
        List<Long> positions = new ArrayList<>();
        for (boolean hidden : List.of(false, true)) {
            Path branch = Files.copy(original, dir.resolve("world-"+hidden+".journal"));
            try (var world=ComposedIndustryScenario.openTenCompanyAutonomy(branch)) {
                long due=world.snapshot().timeMillis()+30;
                if (hidden) world.schedulePublishDocument("other-letter","other-letter-work",due,"private-for-omar",
                        "alice",InformationModel.Audience.direct("omar"),"给Omar的资料","私信正文","私人投递",10,List.of());
                world.advanceTo(due);
                positions.add(world.snapshot().position());
            }
            try (var host = new WorldWebHost(() -> open(branch,false)); var routes = SceneRoutes.open(dir);
                 var server = server(host,routes)) {
                server.start(); base=server.baseUrl();
                for (String id : List.of("nina",ComposedIndustryScenario.MANAGER,"omar")) {
                    admin(); person(id);
                    var views=capture();
                    for (var scope:SCOPED)
                        ((com.fasterxml.jackson.databind.node.ObjectNode)views.get(scope)).remove("observationToken");
                    if (!hidden) control.put(id,views);
                    else if (id.equals("omar")) assertEquals(control.get(id).get("view").required("catalog").asInt()+1,
                            views.get("view").required("catalog").asInt());
                    else assertEquals(control.get(id),views,id);
                }
            }
        }
        assertTrue(positions.get(1)>positions.get(0));
    }
    @Test void originalTenCompanyHttpRetainsItsJournalCursorProtocol() throws Exception {
        Path file = dir.resolve("legacy.journal");
        try (var host = new WorldWebHost(() -> open(file, true)); var routes = SceneRoutes.open(dir);
             var server = server(host, routes)) {
            server.start(); base=server.baseUrl(); admin(); person("nina");
            long position = host.call(() -> runtime.snapshot().position());
            for (String page : SCOPED) {
                var result = get(page);
                assertEquals(position, result.required("position").asLong(), page);
                assertFalse(result.has("observationToken"), page);
            }
            var response=request("POST","/api/me/read", JSON.writeValueAsString(Map.of("expectedPosition","0",
                    "commandId","read","taskId","read-work","dueTimeMillis","1000","version","1","docId","old")));
            assertEquals(409,response.statusCode(),response.body());
            assertEquals(position,JSON.readTree(response.body()).required("position").asLong());
        }
    }

    private Map<String,JsonNode> capture() throws Exception {
        var pages = new LinkedHashMap<String,JsonNode>();
        var tokens = new HashSet<String>();
        for (var page : SCOPED) {
            var view=get(page); noFrame(view,page); pages.put(page,view);
            var token=view.required("observationToken");
            assertTrue(token.isTextual() && Long.parseLong(token.asText())>(1L<<53),page);
            assertTrue(tokens.add(token.asText()));
        }
        for (var page:OTHER) { var view=get(page); noFrame(view,page); pages.put(page,view); }
        var missing=request("GET","/api/me/portfolio-history?digest="+"0".repeat(64),null);
        assertEquals(404,missing.statusCode(),missing.body()); noFrame(JSON.readTree(missing.body()),"portfolio-history");
        return pages;
    }

    private static String token(Map<String,JsonNode> pages,String page) { return pages.get(page).required("observationToken").asText(); }
    private static void noFrame(JsonNode node,String page) {
        for (var key:List.of("position","cutoffPosition","resultPosition","retainedMinPosition","retainedMaxPosition"))
            assertTrue(node.findValues(key).isEmpty(), page+" exposed "+key);
    }
    private JsonNode send(String route,Map<String,Object> command,String token,int expected) throws Exception {
        var body = new LinkedHashMap<>(command); body.put("expectedPosition",token);
        var response=request("POST","/api/me/"+route,JSON.writeValueAsString(body));
        assertEquals(expected,response.statusCode(),route+" "+response.body());
        var json=JSON.readTree(response.body()); noFrame(json,route); return json;
    }
    private JsonNode get(String page) throws Exception {
        var result=request("GET","/api/me/"+page,null);
        assertEquals(200,result.statusCode(),page+" "+result.body());return JSON.readTree(result.body());
    }
    private WorldWebHost.Opened open(Path path,boolean legacy) throws Exception {
        var world=legacy?ComposedIndustryScenario.openOriginalTenCompanyAutonomy(path):ComposedIndustryScenario.openTenCompanyAutonomy(path);
        runtime=world;
        return new WorldWebHost.Opened(world,world.profile().manifest().runId());
    }
    private WorldWebServer server(WorldWebHost host,SceneRoutes routes) throws Exception {
        console.clear(); cookie = null;
        var keys=dir.resolve("keys"); Files.writeString(keys,String.join("\n",List.of("sk-local-fixture-test-1",
                "sk-local-fixture-test-2","sk-local-fixture-test-3","sk-local-fixture-test-4","sk-local-fixture-test-5")));
        return new WorldWebServer(host,KeySlots.load(keys),routes,0,"research-bootstrap",null,console::add);
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
