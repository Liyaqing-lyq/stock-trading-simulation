package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2 真实临时运行：父运行活着时创建子档、真正起一个本机子进程、进入/退出/再进入、双 cookie 并存、
 * 父不变、停止与恢复。
 *
 * <p>用真实 JDK 子进程与真实回环 HTTP；凭据是假 key 文件（不触真实 keys）。不冒充真浏览器：
 * 浏览器端验收留给 Root。测试只按 HTTP 语义核对服务端行为。</p>
 */
class WorldBranchRunHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String BOOTSTRAP = "branch-http-fixture";
    private static final String CHILD_A = "child-alpha";

    private Path work;
    private Path journal;
    private Path keyFile;
    private SceneRoutes routes;
    private WorldWebHost host;
    private WorldWebServer server;
    private RunLauncher launcher;
    private String base;
    private String adminCookie;

    @AfterEach
    void close() {
        if (launcher != null) {
            launcher.close();
        }
        if (server != null) {
            server.close();
        }
        if (host != null) {
            host.close();
        }
        if (routes != null) {
            routes.close();
        }
    }

    // ------------------------------------------------------------------ 主链

    @Test
    void createStartEnterTwiceStopAndRestart() throws Exception {
        startParent();
        login();

        JsonNode runs = json(get("/api/admin/runs"));
        assertEquals("web-groups", runs.path("current").path("runId").asText());
        assertEquals("", runs.path("current").path("parentRunId").asText());
        assertTrue(runs.path("launcher").asBoolean());
        assertEquals(0, runs.path("runs").size());
        String parentPositionBefore = runs.path("current").path("position").asText();

        JsonNode support = json(get("/api/admin/branches?position=1"));
        assertTrue(support.path("supported").asBoolean(), "位置 1 应当可切：" + support);
        assertEquals(0, support.path("reasons").size());
        assertEquals(runs.path("current").path("position").asText(),
                support.path("cutPosition").asText());

        String created = post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "1", "childRunId", CHILD_A, "kind", "operator", "note", "B2 临时链")))
                .body();
        assertEquals("CREATED", JSON.readTree(created).path("status").asText());
        assertEquals("2", JSON.readTree(created).path("firstPosition").asText());

        JsonNode listed = json(get("/api/admin/runs"));
        assertEquals(1, listed.path("runs").size());
        assertEquals("CREATED", listed.path("runs").get(0).path("status").asText());
        assertEquals(0, listed.path("runs").get(0).path("port").asInt());

        JsonNode started = json(post("/api/admin/runs/" + CHILD_A + "/start", ""));
        assertEquals("RUNNING", started.path("status").asText(), started.toString());
        int childPort = started.path("port").asInt();
        assertTrue(childPort >= 18100 && childPort <= 18109, "端口必须在固定区间：" + childPort);

        // 进入：父进程用自有 handoff 向子换会话，把子 cookie 原样转发，并给出本地固定跳转。
        HttpResponse<String> enter = post("/api/admin/runs/" + CHILD_A + "/enter", "");
        assertEquals(200, enter.statusCode());
        assertEquals("http://127.0.0.1:" + childPort + "/",
                JSON.readTree(enter.body()).path("location").asText());
        String childCookie = childCookieOf(enter);
        assertTrue(childCookie.contains("Path=/"), "子会话 cookie 必须限定路径");
        assertTrue(childCookie.contains("HttpOnly"), "子会话 cookie 必须 HttpOnly");

        // 两个 cookie 名字不同，可以同时存在；各自端点各自有效。
        String parentPair = adminCookie;
        String childPair = childCookie.split(";", 2)[0];
        assertNotEquals(parentPair.split("=", 2)[0], childPair.split("=", 2)[0]);
        assertEquals("ADMIN", json(childGet(childPort, "/api/session", childPair)).path("role").asText());
        assertEquals(200, get("/api/admin/view").statusCode());

        // 子进程内退出登录，再进入：必须真实成功（不是假 302），并且父控制能力不受影响。
        assertEquals(200, childPost(childPort, "/api/session/logout", childPair, "").statusCode());
        assertEquals(401, childGet(childPort, "/api/session", childPair).statusCode());
        HttpResponse<String> again = post("/api/admin/runs/" + CHILD_A + "/enter", "");
        assertEquals(200, again.statusCode());
        String second = childCookieOf(again).split(";", 2)[0];
        assertNotEquals(childPair, second, "再次进入必须换发新会话");
        assertEquals("ADMIN", json(childGet(childPort, "/api/session", second)).path("role").asText());

        // 父档没有被推进，仍在原位置；父会话继续可用。
        assertEquals(parentPositionBefore,
                json(get("/api/admin/runs")).path("current").path("position").asText());

        JsonNode stopped = json(post("/api/admin/runs/" + CHILD_A + "/stop", ""));
        assertEquals("STOPPED", stopped.path("status").asText());
        assertTrue(portClosed(childPort), "停止后子端口必须真的关闭");

        // 再次启动：子档从封存件恢复，来源与首帧位置仍与索引一致。
        JsonNode restarted = json(post("/api/admin/runs/" + CHILD_A + "/start", ""));
        assertEquals("RUNNING", restarted.path("status").asText(), restarted.toString());
        HttpResponse<String> third = post("/api/admin/runs/" + CHILD_A + "/enter", "");
        assertEquals(200, third.statusCode());
        int port = restarted.path("port").asInt();
        assertEquals("ADMIN", json(childGet(port, "/api/session",
                childCookieOf(third).split(";", 2)[0])).path("role").asText());
    }

    // ------------------------------------------------------------------ 角色与输入

    @Test
    void roleAndInputRejections() throws Exception {
        startParent();
        login();

        // 非法 ID / 路径 / 未来位置 / 未知运行：全部由后端明确拒绝，不写任何文件
        HttpResponse<String> traversal = post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "1", "childRunId", "../escape", "kind", "operator", "note", "")));
        assertEquals(409, traversal.statusCode());
        assertEquals("BRANCH_IDENTITY_INVALID", JSON.readTree(traversal.body()).path("code").asText());

        HttpResponse<String> upper = post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "1", "childRunId", "Child-A", "kind", "operator", "note", "")));
        assertEquals(409, upper.statusCode());

        HttpResponse<String> future = post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "999", "childRunId", CHILD_A, "kind", "operator", "note", "")));
        assertEquals(409, future.statusCode());
        assertEquals("BRANCH_POSITION_UNCONFIRMED",
                JSON.readTree(future.body()).path("code").asText());

        JsonNode unsupported = json(get("/api/admin/branches?position=999"));
        assertFalse(unsupported.path("supported").asBoolean());
        assertEquals("BRANCH_POSITION_UNCONFIRMED",
                unsupported.path("reasons").get(0).asText());

        assertEquals(400, get("/api/admin/branches?position=abc").statusCode());
        assertEquals(400, get("/api/admin/branches").statusCode());
        assertEquals(400, post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "1", "childRunId", CHILD_A, "kind", "magic", "note", "")))
                .statusCode());
        assertEquals(404, post("/api/admin/runs/never-registered/start", "").statusCode());
        assertFalse(get("/api/admin/runs").body().contains("sk-"), "运行清单不得出现任何 key");
        assertFalse(get("/api/admin/runs").body().contains("handoff"),
                "运行清单不得出现 handoff 凭据");

        // 人物身份：分支列表/创建/启动/进入全部 403，页面也不渲染入口
        adminCookie = cookieOf(post("/api/session/person", "{\"personId\":\"alice\"}"));
        assertEquals(403, get("/api/admin/branches?position=1").statusCode());
        assertEquals(403, get("/api/admin/runs").statusCode());
        assertEquals(403, post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "1", "childRunId", CHILD_A, "kind", "operator", "note", "")))
                .statusCode());
        assertEquals(403, post("/api/admin/runs/" + CHILD_A + "/start", "").statusCode());
        assertEquals(403, post("/api/admin/runs/" + CHILD_A + "/enter", "").statusCode());
    }

    // ------------------------------------------------------------------ 限额与端口

    @Test
    void launchLimitIsTwoAndPortTakenIsReported() throws Exception {
        startParent();
        login();
        for (String id : List.of(CHILD_A, "child-beta", "child-gamma")) {
            assertEquals(200, post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                    "position", "1", "childRunId", id, "kind", "operator", "note", "")))
                    .statusCode());
        }
        assertEquals("RUNNING", json(post("/api/admin/runs/" + CHILD_A + "/start", "")).path("status")
                .asText());
        assertEquals("RUNNING", json(post("/api/admin/runs/child-beta/start", "")).path("status")
                .asText());
        HttpResponse<String> third = post("/api/admin/runs/child-gamma/start", "");
        assertEquals(409, third.statusCode());
        assertEquals("LAUNCH_LIMIT", JSON.readTree(third.body()).path("code").asText());
        assertEquals(2, json(get("/api/admin/runs")).path("active").asInt());

        // 停掉一个后可以再启动另一个（限额是真实在跑的数量，不是历史次数）
        assertEquals("STOPPED", json(post("/api/admin/runs/" + CHILD_A + "/stop", ""))
                .path("status").asText());
        assertEquals("RUNNING", json(post("/api/admin/runs/child-gamma/start", "")).path("status")
                .asText());
    }

    @Test
    void occupiedPortRangeIsReportedWithoutKillingAnything() throws Exception {
        startParent();
        login();
        assertEquals(200, post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "1", "childRunId", CHILD_A, "kind", "operator", "note", "")))
                .statusCode());
        List<ServerSocket> squatters = new ArrayList<>();
        try {
            for (int port = 18100; port <= 18109; port++) {
                squatters.add(new ServerSocket(port, 1));
            }
            HttpResponse<String> rejected = post("/api/admin/runs/" + CHILD_A + "/start", "");
            assertEquals(409, rejected.statusCode());
            assertEquals("PORT_TAKEN", JSON.readTree(rejected.body()).path("code").asText());
            assertEquals("CREATED", json(get("/api/admin/runs")).path("runs").get(0)
                    .path("status").asText());
        } finally {
            for (ServerSocket socket : squatters) {
                socket.close();
            }
        }
    }

    // ------------------------------------------------------------------ 并发与早退

    @Test
    void concurrentCreateOfSameIdYieldsExactlyOneBranch() throws Exception {
        startParent();
        login();
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> codes = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                    codes.add(post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                            "position", "1", "childRunId", CHILD_A, "kind", "operator",
                            "note", ""))).statusCode());
                } catch (Exception failure) {
                    codes.add(-1);
                }
            });
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(30_000L);
        }
        assertEquals(2, codes.size());
        assertTrue(codes.contains(200), "必须有一条创建成功：" + codes);
        assertTrue(codes.contains(409), "另一条必须被明确拒绝：" + codes);
        assertEquals(1, json(get("/api/admin/runs")).path("runs").size());
    }

    @Test
    void missingChildJournalFailsWithChildExitedInsteadOfFakeRunning() throws Exception {
        startParent();
        login();
        assertEquals(200, post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "1", "childRunId", CHILD_A, "kind", "operator", "note", "")))
                .statusCode());
        Files.delete(work.resolve("runs").resolve(CHILD_A + ".journal"));
        HttpResponse<String> failed = post("/api/admin/runs/" + CHILD_A + "/start", "");
        assertEquals(409, failed.statusCode());
        assertEquals("CHILD_EXITED", JSON.readTree(failed.body()).path("code").asText());
        JsonNode listed = json(get("/api/admin/runs"));
        assertEquals("FAILED", listed.path("runs").get(0).path("status").asText());
        assertFalse(listed.path("runs").get(0).path("reason").asText().isBlank(),
                "失败必须带真实原因");
        // 进入必须明确失败，不能假成功
        assertEquals(409, post("/api/admin/runs/" + CHILD_A + "/enter", "").statusCode());
    }

    // ------------------------------------------------------------------ 子运行真实业务 + 重启

    @Test
    void childRealBusinessSurvivesStopAndRestart() throws Exception {
        startParent();
        login();
        JsonNode parentBefore = json(get("/api/admin/runs")).path("current");
        long parentJournalBytes = Files.size(journal);
        long parentTime = Long.parseLong(json(get("/api/admin/view")).path("timeMillis").asText());

        assertEquals(200, post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "1", "childRunId", CHILD_A, "kind", "operator", "note", "业务链")))
                .statusCode());
        JsonNode started = json(post("/api/admin/runs/" + CHILD_A + "/start", ""));
        assertEquals("RUNNING", started.path("status").asText());
        int port = started.path("port").asInt();

        // 子运行里做一次**真实业务写入**：alice 在公开群里发帖（不是只读、也不只是推进时钟）。
        String childAdmin = childCookieOf(post("/api/admin/runs/" + CHILD_A + "/enter", ""));
        String childPerson = cookieOf(childPost(port, "/api/session/person", childAdmin.split(";", 2)[0],
                "{\"personId\":\"alice\"}"));
        JsonNode page = childJson(port, "/api/me/groups", childPerson);
        long due = Long.parseLong(page.path("timeMillis").asText())
                + Long.parseLong(page.path("actionMillis").path("POST").asText());
        String command = "child-post-" + System.nanoTime();
        JsonNode posted = childJson(port, "/api/me/groups/action", childPerson,
                "{\"expectedPosition\":\"" + page.path("position").asText()
                        + "\",\"commandId\":\"" + command + "\",\"taskId\":\"" + command + "-task\","
                        + "\"dueTimeMillis\":\"" + due + "\",\"action\":\"POST\",\"groupId\":\""
                        + WorldWebScenario.GROUPS_OPEN + "\",\"title\":\"子运行发帖\","
                        + "\"body\":\"子运行里真实写入的一条群帖\"}");
        assertTrue(posted.path("receipt").asText().startsWith("SCHEDULED"), posted.toString());

        // 再进入换一个子管理员会话，把工时推到发帖完成（父进程的 admin 不能替子推进）
        String childAdmin2 = childCookieOf(post("/api/admin/runs/" + CHILD_A + "/enter", ""));
        JsonNode childView = childJson(port, "/api/admin/view", childAdmin2.split(";", 2)[0]);
        childJson(port, "/api/admin/advance", childAdmin2.split(";", 2)[0],
                "{\"expectedPosition\":\"" + childView.path("position").asText()
                        + "\",\"targetMillis\":\"" + (due + 5) + "\"}");
        JsonNode childGod = childJson(port, "/api/admin/groups", childAdmin2.split(";", 2)[0]);
        assertEquals(1, childGod.path("posts").size(), "子运行必须真的有一帖：" + childGod);
        String docId = childGod.path("posts").get(0).path("docId").asText();
        long childPosition = Long.parseLong(childGod.path("position").asText());
        assertTrue(childPosition > 2, "子运行位置必须大于首帧 2：" + childPosition);

        // 父状态与父档字节不变
        JsonNode parentAfter = json(get("/api/admin/runs")).path("current");
        assertEquals(parentBefore.path("position").asText(), parentAfter.path("position").asText());
        assertEquals(parentTime,
                Long.parseLong(json(get("/api/admin/view")).path("timeMillis").asText()));
        assertEquals(parentJournalBytes, Files.size(journal), "父档字节数不得变化");

        // 停止 → 端口关闭；再启动（health 必须接受 position > firstPosition）→ 同一帖子仍在
        assertEquals("STOPPED", json(post("/api/admin/runs/" + CHILD_A + "/stop", "")).path("status")
                .asText());
        assertTrue(portClosed(port));
        JsonNode restarted = json(post("/api/admin/runs/" + CHILD_A + "/start", ""));
        assertEquals("RUNNING", restarted.path("status").asText(), restarted.toString());
        int port2 = restarted.path("port").asInt();
        String admin3 = childCookieOf(post("/api/admin/runs/" + CHILD_A + "/enter", ""));
        JsonNode afterRestart = childJson(port2, "/api/admin/groups", admin3.split(";", 2)[0]);
        assertEquals(1, afterRestart.path("posts").size(), "重启后帖子必须还在");
        assertEquals(docId, afterRestart.path("posts").get(0).path("docId").asText());
        assertEquals(Long.toString(childPosition), afterRestart.path("position").asText(),
                "重启后确认位置必须保留在原处，不能回退到首帧");
        JsonNode identity = childJson(port2, "/api/admin/runs", admin3.split(";", 2)[0]);
        assertEquals("web-groups", identity.path("current").path("parentRunId").asText());
        assertEquals("1", identity.path("current").path("parentPosition").asText());
    }

    // ------------------------------------------------------------------ 未决决定：拒分支且不落盘

    @Test
    void pendingDecisionBlocksBranchAndWritesNothing() throws Exception {
        Path root = Path.of("target", "branch-web-tests");
        Files.createDirectories(root);
        work = Files.createTempDirectory(root, "pending-");
        keyFile = work.resolve("dummy-keys.txt");
        List<String> keys = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            keys.add("sk-fake-" + "0".repeat(20) + index);
        }
        Files.writeString(keyFile, String.join("\n", keys), StandardCharsets.UTF_8);
        KeySlots slots = KeySlots.load(keyFile);
        routes = SceneRoutes.open(work);
        // 真实工程档：开闸 → 开决定 → 推进一帧（决定仍 OPEN）；再派发一次但不回包。
        host = new WorldWebHost(() -> {
            WorldWebHost.Opened opened = WorldWebScenario.openEngineering(
                    work.resolve("world-engineering.journal"));
            WorldRuntime runtime = opened.runtime();
            runtime.beginDecisionSession("open-1");
            runtime.openDecisions("d-1", List.of(new DecisionRun.Request("alice", "a-1", "wake",
                    new DecisionModel.Route("research", "key-1", "deepseek-flash", 1L, 20,
                            1_000_000, 1_024, 2))));
            runtime.advanceTo(runtime.snapshot().timeMillis() + 1_000L);
            runtime.dispatchLiveDecision("d-1", "a-1");
            runtime.advanceTo(runtime.snapshot().timeMillis() + 1_000L);
            return opened;
        });
        server = new WorldWebServer(host, slots, routes, 0, BOOTSTRAP);
        server.start();
        base = server.baseUrl();
        login();

        JsonNode runs = json(get("/api/admin/runs"));
        String position = runs.path("current").path("position").asText();
        JsonNode support = json(get("/api/admin/branches?position=" + position));
        assertFalse(support.path("supported").asBoolean(), "未决决定处不得可切：" + support);
        assertEquals(1, support.path("reasons").size());
        String reason = support.path("reasons").get(0).asText();
        assertTrue(reason.startsWith("BRANCH_"), reason);

        HttpResponse<String> rejected = post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", position, "childRunId", CHILD_A, "kind", "operator", "note", "")));
        assertEquals(409, rejected.statusCode());
        assertEquals(reason, JSON.readTree(rejected.body()).path("code").asText(),
                "create 的拒绝码必须与 support 理由一致");

        // 没有落盘：运行目录不存在或为空，索引未改，父位置不变
        Path runsDir = work.resolve("runs");
        if (Files.exists(runsDir)) {
            try (var entries = Files.list(runsDir)) {
                assertEquals(0, entries.count(), "被拒的创建不得留下任何文件");
            }
        }
        assertEquals(0, json(get("/api/admin/runs")).path("runs").size());
        assertEquals(position, json(get("/api/admin/runs")).path("current").path("position").asText());
    }

    // ------------------------------------------------------------------ 父进程退出：子靠管道 EOF 收尾

    @Test
    void parentDeathClosesChildThroughPipeEof() throws Exception {
        startParentWorkOnly();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(javaExecutable(),
                "-Dbranch.fixture.classpath=" + classpath, "-cp", classpath,
                BranchParentFixtureMain.class.getName(), work.toAbsolutePath().toString(), "0", CHILD_A)
                .directory(work.toAbsolutePath().toFile()).redirectErrorStream(true);
        Process fixture = builder.start();
        try {
            String ready = readFirstReadyLine(fixture, 120_000L);
            JsonNode info = JSON.readTree(ready.substring(ready.indexOf('{')));
            int childPort = info.path("childPort").asInt();
            long childPid = info.path("childPid").asLong();
            assertTrue(childPort >= 18100 && childPort <= 18109);
            assertEquals(401, childGet(childPort, "/api/session", null).statusCode(),
                    "子进程必须真的在监听");

            // 只杀这个"父进程"本身（不是整棵进程树）：子进程应当靠 stdin EOF 自行收尾。
            fixture.destroy();
            assertTrue(waitUntil(() -> !ProcessHandle.of(childPid).map(ProcessHandle::isAlive)
                    .orElse(false), 30_000L), "父进程被单独终止后子进程必须自行退出");
            assertTrue(portClosed(childPort), "子端口必须关闭");

            // 档可重开：用本测试自己的宿主重新启动同一子档（父档锁已释放、封存件完整）
            WorldWebHost host2 = new WorldWebHost(() -> WorldWebScenario.openGroups(
                    work.resolve("world-groups.journal")));
            SceneRoutes routes2 = SceneRoutes.open(work);
            RunLauncher launcher2 = new RunLauncher(new RunLauncher.Config(work.toAbsolutePath(),
                    work.resolve("runs").toAbsolutePath(), keyFile.toAbsolutePath(), 18100, 18109, 2,
                    30_000L, classpath), host2::branchProfile);
            try {
                assertEquals("RUNNING", launcher2.start(CHILD_A).status(),
                        "父进程消失后子档必须能被重新启动（锁已释放、档案完整）");
            } finally {
                launcher2.close();
                routes2.close();
                host2.close();
            }
        } finally {
            fixture.destroyForcibly();
        }
    }

    // ------------------------------------------------------------------ 生产构造 / handoff 语义

    @Test
    void productionConstructorUsesRunSpecificCookieAndHandoffStaysChildOnly() throws Exception {
        // 1) 与 WorldWebApp 相同的构造（runId 非空）：父 cookie 按运行分名，且父进程没有 handoff 端点
        startParentWorkOnly();
        KeySlots slots = KeySlots.load(keyFile);
        routes = SceneRoutes.open(work);
        host = new WorldWebHost(() -> WorldWebScenario.openGroups(journal));
        server = new WorldWebServer(host, slots, routes, 0, BOOTSTRAP, null, System.out::println,
                "web-groups", null);
        server.start();
        base = server.baseUrl();
        HttpResponse<String> login = post("/api/session",
                "{\"bootstrap\":\"" + BOOTSTRAP + "\"}");
        assertEquals(200, login.statusCode(), login.body());
        String setCookie = login.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(setCookie.startsWith("world_session_web-groups="),
                "生产构造必须按运行分名：" + setCookie);
        adminCookie = setCookie.split(";", 2)[0];
        assertEquals(404, post("/api/session/handoff", "{\"token\":\"whatever\"}").statusCode(),
                "父进程必须没有 handoff 端点");
        server.close();
        host.close();
        routes.close();
        server = null;
        host = null;
        routes = null;

        // 2) 同一个 handoff 端点：probe 只回身份不建会话；正式换发才给 cookie；错 token 明确 401
        startParentWorkOnly();
        slots = KeySlots.load(keyFile);
        routes = SceneRoutes.open(work);
        host = new WorldWebHost(() -> WorldWebScenario.openGroups(journal));
        WorldWebHost bound = host;
        server = new WorldWebServer(bound, slots, routes, 0, BOOTSTRAP, null, System.out::println,
                "probe-srv", "known-handoff-token");
        server.start();
        base = server.baseUrl();
        HttpResponse<String> probe = request(base, "POST", "/api/session/handoff", null,
                "{\"token\":\"known-handoff-token\",\"probe\":true}");
        assertEquals(200, probe.statusCode(), probe.body());
        assertTrue(probe.headers().firstValue("set-cookie").isEmpty(),
                "probe 不得签发会话 cookie");
        assertEquals(401, request(base, "GET", "/api/session", null, null).statusCode(),
                "probe 之后仍然没有会话");
        assertEquals(401, request(base, "POST", "/api/session/handoff", null,
                "{\"token\":\"wrong-token\",\"probe\":true}").statusCode());
        HttpResponse<String> handoff = request(base, "POST", "/api/session/handoff", null,
                "{\"token\":\"known-handoff-token\"}");
        assertEquals(200, handoff.statusCode(), handoff.body());
        String issued = handoff.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("world_session_probe-srv="))
                .findFirst().orElseThrow();
        assertEquals("ADMIN", JSON.readTree(request(base, "GET", "/api/session",
                issued.split(";", 2)[0], null).body()).path("role").asText());
    }

    // ------------------------------------------------------------------ 失败不留半活

    @Test
    void wrongProfileAndDamagedChildFailWithoutHalfAliveRun() throws Exception {
        startParent();
        login();
        assertEquals(200, post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "1", "childRunId", CHILD_A, "kind", "operator", "note", "坏档")))
                .statusCode());
        Path index = work.resolve("runs").resolve("index.json");
        String original = Files.readString(index, StandardCharsets.UTF_8);
        // 1) 索引里的画像摘要被改成一个不存在的值 → 启动前就明确拒绝，不产生任何进程
        Files.writeString(index, original.replace(
                "\"profileDigest\" : \"", "\"profileDigest\" : \"00"),
                StandardCharsets.UTF_8);
        HttpResponse<String> wrongProfile = post("/api/admin/runs/" + CHILD_A + "/start", "");
        assertEquals(409, wrongProfile.statusCode());
        assertEquals("PROFILE_MISMATCH",
                JSON.readTree(wrongProfile.body()).path("code").asText());
        JsonNode afterWrongProfile = json(get("/api/admin/runs"));
        assertEquals(0, afterWrongProfile.path("active").asInt(), "失败不得占用并发额度");
        assertTrue(afterWrongProfile.path("runs").get(0).path("pid").asLong() == 0L,
                "被拒的启动不得留下任何进程");
        assertTrue(afterWrongProfile.path("runs").get(0).path("port").asInt() == 0,
                "被拒的启动不得占端口");
        Files.writeString(index, original, StandardCharsets.UTF_8);

        // 2) 子档字节被破坏（改坏中间一帧，不是可截掉的半尾）→ 子进程起了但立即退出，
        //    父进程如实报 CHILD_EXITED，不能留下"半活 RUNNING"。
        Path childJournal = work.resolve("runs").resolve(CHILD_A + ".journal");
        byte[] valid = Files.readAllBytes(childJournal);
        int middle = valid.length / 2;
        byte originalByte = valid[middle];
        valid[middle] = (byte) (originalByte ^ 0xFF);
        Files.write(childJournal, valid);
        HttpResponse<String> broken = post("/api/admin/runs/" + CHILD_A + "/start", "");
        assertEquals(409, broken.statusCode(), broken.body());
        assertEquals("CHILD_EXITED", JSON.readTree(broken.body()).path("code").asText());
        JsonNode afterBroken = json(get("/api/admin/runs"));
        assertEquals("FAILED", afterBroken.path("runs").get(0).path("status").asText());
        assertFalse(afterBroken.path("runs").get(0).path("reason").asText().isBlank(),
                "失败必须带真实原因");
        assertEquals(0, afterBroken.path("active").asInt());
        assertEquals(409, post("/api/admin/runs/" + CHILD_A + "/enter", "").statusCode());
    }

    // ------------------------------------------------------------------ 夹具

    private JsonNode childJson(int port, String path, String cookie) throws Exception {
        return childJson(port, path, cookie, null);
    }

    private JsonNode childJson(int port, String path, String cookie, String body) throws Exception {
        HttpResponse<String> response = body == null
                ? childGet(port, path, cookie) : childPost(port, path, cookie, body);
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return JSON.readTree(response.body());
    }

    private static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path java = bin.resolve("java.exe");
        return Files.isRegularFile(java) ? java.toAbsolutePath().toString()
                : bin.resolve("java").toString();
    }

    /** 等夹具打出 FIXTURE_READY 行（独立线程读，避免测试自己挂死）。 */
    private static String readFirstReadyLine(Process process, long timeoutMillis) throws Exception {
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String[] line = new String[1];
        Thread thread = new Thread(() -> {
            try {
                String read;
                while ((read = reader.readLine()) != null) {
                    if (read.startsWith("FIXTURE_READY")) {
                        line[0] = read;
                        return;
                    }
                }
            } catch (java.io.IOException ignored) {
                // 进程退出
            }
        }, "fixture-ready-reader");
        thread.setDaemon(true);
        thread.start();
        thread.join(timeoutMillis);
        assertNotNull(line[0], "父进程夹具没有在超时内报告就绪");
        return line[0];
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100L);
        }
        return condition.getAsBoolean();
    }

    private void startParentWorkOnly() throws Exception {
        Path root = Path.of("target", "branch-web-tests");
        Files.createDirectories(root);
        work = Files.createTempDirectory(root, "case-");
        journal = work.resolve("world-groups.journal");
        keyFile = work.resolve("dummy-keys.txt");
        List<String> keys = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            keys.add("sk-fake-" + "0".repeat(20) + index);
        }
        Files.writeString(keyFile, String.join("\n", keys), StandardCharsets.UTF_8);
    }

    private void startParent() throws Exception {
        startParentWorkOnly();
        KeySlots slots = KeySlots.load(keyFile);
        routes = SceneRoutes.open(work);
        host = new WorldWebHost(() -> WorldWebScenario.openGroups(journal));
        server = new WorldWebServer(host, slots, routes, 0, BOOTSTRAP);
        launcher = new RunLauncher(new RunLauncher.Config(work.toAbsolutePath(),
                work.resolve("runs").toAbsolutePath(), keyFile.toAbsolutePath(), 18100, 18109, 2,
                30_000L, System.getProperty("surefire.test.class.path",
                        System.getProperty("java.class.path"))), host::branchProfile);
        server.attachLauncher(launcher);
        server.start();
        base = server.baseUrl();
    }

    private void login() throws Exception {
        adminCookie = cookieOf(post("/api/session", "{\"bootstrap\":\"" + BOOTSTRAP + "\"}"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request(base, "GET", path, adminCookie, null);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return request(base, "POST", path, adminCookie, body == null ? "" : body);
    }

    private HttpResponse<String> childGet(int port, String path, String cookie) throws Exception {
        return request("http://127.0.0.1:" + port, "GET", path, cookie, null);
    }

    private HttpResponse<String> childPost(int port, String path, String cookie, String body)
            throws Exception {
        return request("http://127.0.0.1:" + port, "POST", path, cookie, body);
    }

    private HttpResponse<String> request(String origin, String method, String path, String cookie,
                                         String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origin + path))
                .timeout(Duration.ofSeconds(30));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if ("POST".equals(method)) {
            builder.header("Origin", origin).header("X-World-Request", "1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
                            StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static String cookieOf(HttpResponse<String> response) {
        String raw = response.headers().firstValue("set-cookie").orElseThrow();
        return raw.split(";", 2)[0];
    }

    private static String childCookieOf(HttpResponse<String> response) {
        String raw = response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("world_session_" + CHILD_A + "="))
                .findFirst().orElseThrow();
        assertNotNull(raw);
        return raw;
    }

    private static boolean portClosed(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                Thread.sleep(50L);
            } catch (java.io.IOException closed) {
                return true;
            }
        }
        return false;
    }
}
