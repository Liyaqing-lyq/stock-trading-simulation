package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2 生命周期链（作者侧，针对独审 L1）：子进程被外部结束后不得继续报 RUNNING，
 * 必须转 {@code EXITED} 并给真实退出码；再次启动要保留原来的业务结果；正常停止仍是 STOPPED。
 *
 * <p>并发窗口用 {@link RunLauncher#beforeHealthCheck} 这个 package-private 接缝做**确定性**交错：
 * 测试把 start 停在"子进程已起、尚未提交 RUNNING"的窗口里，再在窗口内停止，靠 latch 同步而不是
 * 轮询/sleep 撞概率。</p>
 */
class BranchLifecycleChainTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String BOOTSTRAP = "lifecycle-fixture";
    private static final String CHILD = "child-life";

    private Path work;
    private Path keyFile;
    private SceneRoutes routes;
    private WorldWebHost host;
    private WorldWebServer server;
    private RunLauncher launcher;
    private String base;
    private String cookie;

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
    void externalKillBecomesExitedThenRestartKeepsBusinessAndStopIsStopped() throws Exception {
        startParent();
        login();
        assertEquals(200, post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "1", "childRunId", CHILD, "kind", "operator", "note", "生命周期")))
                .statusCode());
        JsonNode started = json(post("/api/admin/runs/" + CHILD + "/start", ""));
        assertEquals("RUNNING", started.path("status").asText());
        long pid = started.path("pid").asLong();
        assertTrue(pid > 0, "自有子进程必须有真实 PID");
        int port = started.path("port").asInt();

        // 子运行里先做一次真实业务写入，稍后验证"重启保留原结果"。
        String admin = childCookieOf(post("/api/admin/runs/" + CHILD + "/enter", ""));
        String person = cookieOf(childPost(port, "/api/session/person", admin.split(";", 2)[0],
                "{\"personId\":\"alice\"}"));
        JsonNode page = childJson(port, "/api/me/groups", person);
        long due = Long.parseLong(page.path("timeMillis").asText())
                + Long.parseLong(page.path("actionMillis").path("POST").asText());
        String command = "life-post-" + System.nanoTime();
        childJson(port, "/api/me/groups/action", person,
                "{\"expectedPosition\":\"" + page.path("position").asText()
                        + "\",\"commandId\":\"" + command + "\",\"taskId\":\"" + command + "-task\","
                        + "\"dueTimeMillis\":\"" + due + "\",\"action\":\"POST\",\"groupId\":\""
                        + WorldWebScenario.GROUPS_OPEN + "\",\"title\":\"生命周期帖\","
                        + "\"body\":\"外部结束后重启必须还在\"}");
        String admin2 = childCookieOf(post("/api/admin/runs/" + CHILD + "/enter", ""));
        JsonNode childView = childJson(port, "/api/admin/view", admin2.split(";", 2)[0]);
        childJson(port, "/api/admin/advance", admin2.split(";", 2)[0],
                "{\"expectedPosition\":\"" + childView.path("position").asText()
                        + "\",\"targetMillis\":\"" + (due + 5) + "\"}");
        JsonNode god = childJson(port, "/api/admin/groups", admin2.split(";", 2)[0]);
        assertEquals(1, god.path("posts").size(), "子运行必须真的有一帖：" + god);
        String docId = god.path("posts").get(0).path("docId").asText();
        String childPosition = god.path("position").asText();

        // 外部结束这条自有进程（不是 stop，也不碰别的进程）
        ProcessHandle handle = ProcessHandle.of(pid).orElseThrow();
        handle.destroyForcibly();
        assertTrue(waitUntil(() -> !handle.isAlive(), 20_000L), "测试子进程必须真的退出");

        // 清单必须转 EXITED 并给真实退出码；active 不再占额度；进入要明确失败
        JsonNode listed = json(get("/api/admin/runs"));
        JsonNode row = listed.path("runs").get(0);
        assertEquals("EXITED", row.path("status").asText(), "外部结束必须显示 EXITED：" + row);
        assertTrue(row.path("reason").asText().contains("退出码"),
                "必须给出真实退出码：" + row.path("reason").asText());
        assertEquals(0, listed.path("active").asInt(), "死进程不得继续占用并发额度");
        assertNotEquals("STOPPED", row.path("status").asText(), "异常退出不得伪造成 STOPPED");
        HttpResponse<String> enterAfterExit = post("/api/admin/runs/" + CHILD + "/enter", "");
        assertEquals(409, enterAfterExit.statusCode());
        assertEquals("RUN_NOT_RUNNING",
                JSON.readTree(enterAfterExit.body()).path("code").asText());

        // 同一 id 可以直接重启，业务结果与位置保留
        JsonNode restarted = json(post("/api/admin/runs/" + CHILD + "/start", ""));
        assertEquals("RUNNING", restarted.path("status").asText(), restarted.toString());
        int port2 = restarted.path("port").asInt();
        String admin3 = childCookieOf(post("/api/admin/runs/" + CHILD + "/enter", ""));
        JsonNode afterRestart = childJson(port2, "/api/admin/groups", admin3.split(";", 2)[0]);
        assertEquals(1, afterRestart.path("posts").size(), "重启后帖子必须还在");
        assertEquals(docId, afterRestart.path("posts").get(0).path("docId").asText());
        assertEquals(childPosition, afterRestart.path("position").asText(), "重启后位置必须保留");

        // 正常停止仍然是 STOPPED
        JsonNode stopped = json(post("/api/admin/runs/" + CHILD + "/stop", ""));
        assertEquals("STOPPED", stopped.path("status").asText());
        assertTrue(waitUntil(() -> !ProcessHandle.of(restarted.path("pid").asLong())
                .map(ProcessHandle::isAlive).orElse(false), 20_000L));
    }

    // ------------------------------------------------------------------ 并发窗口（确定性）

    @Test
    void concurrentStopDuringStartNeverLeavesRunning() throws Exception {
        startParent();
        login();
        assertEquals(200, post("/api/admin/branches", JSON.writeValueAsString(Map.of(
                "position", "1", "childRunId", CHILD, "kind", "operator", "note", "并发")))
                .statusCode());
        // 基线：正常启动 → 正常停止仍可用
        assertEquals("RUNNING", json(post("/api/admin/runs/" + CHILD + "/start", ""))
                .path("status").asText());
        assertEquals("STOPPED", json(post("/api/admin/runs/" + CHILD + "/stop", ""))
                .path("status").asText());

        // 确定性交错 A：把 start 停在"子进程已起、尚未提交 RUNNING"的窗口里；stop 清理完、写最终状态前
        // 再把旧 start 放出去，让它的失败收尾正好落在"清理完、未提交"的窗口内。
        CountDownLatch inWindow = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch startSettled = new CountDownLatch(1);
        launcher.beforeHealthCheck = () -> {
            inWindow.countDown();
            awaitQuietly(release, 60_000L);
        };
        launcher.afterStopCleanup = () -> {
            release.countDown();                 // 让旧 start 在这里失败收尾
            awaitQuietly(startSettled, 60_000L); // 等它真的收尾完，再由 stop 提交状态
        };
        AtomicReference<String> outcome = new AtomicReference<>("");
        Thread starter = new Thread(() -> {
            try {
                outcome.set("VIEW:" + launcher.start(CHILD).status());
            } catch (RunLauncher.Rejected rejected) {
                outcome.set("REJECTED:" + rejected.code());
            } catch (Exception failure) {
                outcome.set("ERROR:" + failure);
            } finally {
                startSettled.countDown();
            }
        }, "lifecycle-start-window");
        starter.start();
        assertTrue(inWindow.await(60, TimeUnit.SECONDS), "start 必须到达健康核验前的窗口");
        RunLauncher.View duringWindow = launcher.list().stream()
                .filter(view -> view.runId().equals(CHILD)).findFirst().orElseThrow();
        assertEquals("STARTING", duringWindow.status(), "窗口内 start 还没提交 RUNNING");
        assertEquals(1, launcher.activeCount(), "清理完成前仍应占用并发额度");

        RunLauncher.View stopped = launcher.stop(CHILD);
        assertEquals("STOPPED", stopped.status(), "用户 stop 必须得到 STOPPED");
        starter.join(60_000L);
        assertFalse(starter.isAlive(), "并发窗口内 start 必须结束");
        assertTrue(outcome.get().startsWith("REJECTED"),
                "被 stop 的 start 只能是明确拒绝，实际：" + outcome.get());
        launcher.beforeHealthCheck = null;
        launcher.afterStopCleanup = null;

        JsonNode listed = json(get("/api/admin/runs"));
        assertEquals("STOPPED", listed.path("runs").get(0).path("status").asText(),
                "stop 之后旧 start 不得把条目改回 RUNNING 或 FAILED（" + outcome.get() + "）");
        assertEquals(0, listed.path("active").asInt(), "不许留下占额度的死条目");

        // 确定性交错 B：旧 start 的失败收尾不得碰"新一代"条目。
        CountDownLatch firstWindow = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        launcher.beforeHealthCheck = () -> {
            if (calls.incrementAndGet() == 1) {
                firstWindow.countDown();
                awaitQuietly(releaseFirst, 60_000L);
            }
        };
        AtomicReference<String> oldOutcome = new AtomicReference<>("");
        Thread oldStart = new Thread(() -> {
            try {
                oldOutcome.set("VIEW:" + launcher.start(CHILD).status());
            } catch (RunLauncher.Rejected rejected) {
                oldOutcome.set("REJECTED:" + rejected.code());
            } catch (Exception failure) {
                oldOutcome.set("ERROR:" + failure);
            }
        }, "lifecycle-start-old");
        oldStart.start();
        assertTrue(firstWindow.await(60, TimeUnit.SECONDS), "旧 start 必须停在窗口里");
        assertEquals("STOPPED", launcher.stop(CHILD).status());
        RunLauncher.View second = launcher.start(CHILD);
        assertEquals("RUNNING", second.status(), "停止后应能立即重新启动新一代");
        long secondPid = second.pid();
        assertTrue(secondPid > 0);

        releaseFirst.countDown();
        oldStart.join(60_000L);
        assertFalse(oldStart.isAlive());
        assertTrue(oldOutcome.get().startsWith("REJECTED"),
                "旧 start 应被明确拒绝：" + oldOutcome.get());

        JsonNode afterOld = json(get("/api/admin/runs"));
        assertEquals("RUNNING", afterOld.path("runs").get(0).path("status").asText(),
                "旧 start 的收尾不得把新一代改掉");
        assertEquals(secondPid, afterOld.path("runs").get(0).path("pid").asLong(),
                "旧 start 的收尾不得杀掉新一代进程");
        assertEquals(1, afterOld.path("active").asInt());
        assertEquals("STOPPED", json(post("/api/admin/runs/" + CHILD + "/stop", ""))
                .path("status").asText());
    }

    /** 在限定时间内等待闸门；超时后继续，由测试断言兜底。 */
    private static void awaitQuietly(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ 夹具

    private void startParent() throws Exception {
        Path root = Path.of("target", "branch-lifecycle-tests");
        Files.createDirectories(root);
        work = Files.createTempDirectory(root, "case-");
        keyFile = work.resolve("dummy-keys.txt");
        List<String> keys = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            keys.add("sk-fake-" + "0".repeat(20) + index);
        }
        Files.writeString(keyFile, String.join("\n", keys), StandardCharsets.UTF_8);
        KeySlots slots = KeySlots.load(keyFile);
        routes = SceneRoutes.open(work);
        host = new WorldWebHost(() -> WorldWebScenario.openGroups(work.resolve("world-groups.journal")));
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
        cookie = cookieOf(post("/api/session", "{\"bootstrap\":\"" + BOOTSTRAP + "\"}"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request(base, "GET", path, cookie, null);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return request(base, "POST", path, cookie, body == null ? "" : body);
    }

    private HttpResponse<String> childGet(int port, String path, String cookieValue)
            throws Exception {
        return request("http://127.0.0.1:" + port, "GET", path, cookieValue, null);
    }

    private HttpResponse<String> childPost(int port, String path, String cookieValue, String body)
            throws Exception {
        return request("http://127.0.0.1:" + port, "POST", path, cookieValue, body);
    }

    private HttpResponse<String> request(String origin, String method, String path, String cookieValue,
                                         String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origin + path))
                .timeout(java.time.Duration.ofSeconds(60));
        if (cookieValue != null) {
            builder.header("Cookie", cookieValue);
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

    private JsonNode childJson(int port, String path, String cookieValue) throws Exception {
        return childJson(port, path, cookieValue, null);
    }

    private JsonNode childJson(int port, String path, String cookieValue, String body)
            throws Exception {
        HttpResponse<String> response = body == null
                ? childGet(port, path, cookieValue) : childPost(port, path, cookieValue, body);
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return JSON.readTree(response.body());
    }

    private static String cookieOf(HttpResponse<String> response) {
        return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private static String childCookieOf(HttpResponse<String> response) {
        String raw = response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("world_session_" + CHILD + "="))
                .findFirst().orElseThrow();
        assertNotNull(raw);
        return raw;
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50L);
        }
        return condition.getAsBoolean();
    }
}
