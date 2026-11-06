package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import edu.cufe.auction.world.WorldRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正式回归候选：live Web 角色轮次 Host 合同。
 *
 * <p>真实 schema10 {@link WorldRuntime} + 真实回环 HTTP + dummy 五 key；不读真实凭据、
 * 不访问官方端点、不使用 Temp。工作目录可用 {@code web.live.work} 覆盖。</p>
 */
class WorldWebLiveHostTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> KEYS = new LinkedHashMap<>();

    static {
        for (int index = 1; index <= 5; index++) {
            KEYS.put("key-" + index, "sk-final-fixture-0000" + index);
        }
    }

    private Path dir;
    private SceneRoutes routes;
    private Provider provider;
    private WorldWebHost host;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("web.live.work",
                "target/web-live-host-tests"));
        dir = root.resolve("live-" + System.nanoTime());
        Files.createDirectories(dir);
        provider = new Provider();
        routes = SceneRoutes.open(dir);
        configureRoutes("key-1", "deepseek-flash", 512, "key-2", "deepseek-v4-pro", 640);
    }

    @AfterEach
    void tearDown() {
        provider.releaseAll();
        if (host != null) {
            host.close();
            host = null;
        }
        if (routes != null) {
            routes.close();
        }
        provider.close();
    }

    // ------------------------------------------------------------ 构造与显式开始

    @Test
    @DisplayName("构造与读取不发请求也不自动 begin：IDLE、无决定、槽为 0")
    void constructionAndReadsDoNotCallProvider() throws Exception {
        openHost("idle");
        WorldWebHost.LiveStatus status = host.liveStatus();
        assertEquals("IDLE", status.phase());
        assertTrue(status.decisions().isEmpty());
        assertEquals(0, status.completionSlots());
        assertEquals(0, status.physicalInFlight());
        assertEquals(0, provider.calls());
        assertEquals("100", status.runBudget().capCalls());
        assertEquals("25", status.personBudgets().get(0).budget().capCalls());
    }

    @Test
    @DisplayName("显式开始：四岗各一次，无人刷新也持久派发，重复开始不产生额外请求")
    void explicitStartDispatchesEachPersonOnce() throws Exception {
        openHost("start");
        provider.setDelayMillis(120L);
        host.startLiveRound(1L, routes.view().revision(),
                List.of("alice", "bob", "carol", "dave"));
        awaitRequests(1, 10_000L);
        WorldWebHost.LiveStatus current = host.liveStatus();
        assertThrows(IllegalStateException.class, () -> host.startLiveRound(
                parseLong(current.position()), routes.view().revision(), List.of("alice")),
                "同一时刻只允许一个轮次");
        awaitRequests(4, 10_000L);
        assertEquals(4, provider.calls(), "四岗各一次");
    }

    @Test
    @DisplayName("公平顺序：与客户端数组顺序无关，按 seed+人物稳定序派出")
    void fairOrderIgnoresClientArrayOrder() throws Exception {
        openHost("fair");
        long seed = liveSeed();
        List<String> persons = List.of("bob", "carol", "dave");
        List<String> expected = new ArrayList<>(persons);
        expected.sort(java.util.Comparator.comparing(
                (String person) -> edu.cufe.auction.world.WorldScheduler.fairOrder(seed, person)));
        provider.hold();
        host.startLiveRound(1L, routes.view().revision(), List.of("dave", "carol", "bob"));
        awaitRequests(1, 10_000L);
        WorldWebHost.LiveStatus status = host.liveStatus();
        assertEquals(expected.subList(1, expected.size()), status.pendingPersons(),
                "待派名单必须按稳定公平序，而不是客户端顺序");
        provider.releaseAll();
        awaitSettled(15_000L);
    }

    // ------------------------------------------------------------ 输入与场景冻结

    @Test
    @DisplayName("四岗受限输入与场景：研究用研究路由，其余用交易路由")
    void routesAreFrozenPerPersonAndScene() throws Exception {
        openHost("scenes");
        host.startLiveRound(1L, routes.view().revision(),
                List.of("alice", "bob", "carol", "dave"));
        awaitRequests(4, 10_000L);
        awaitSettled(10_000L);
        int research = 0;
        int trading = 0;
        for (JsonNode request : provider.requestJsonList()) {
            String model = request.path("model").asText();
            int maxTokens = request.path("max_tokens").asInt();
            String system = request.path("messages").get(0).path("content").asText();
            String user = request.path("messages").get(1).path("content").asText();
            assertTrue(system.getBytes(StandardCharsets.UTF_8).length <= 1024);
            assertFalse(user.contains("managementReports"));
            assertFalse(user.contains("sk-final-fixture"));
            if ("deepseek-flash".equals(model)) {
                research++;
                assertEquals(512, maxTokens);
            } else {
                trading++;
                assertEquals(640, maxTokens);
            }
        }
        assertEquals(1, research, "只有研究员走 research");
        assertEquals(3, trading, "其余三岗走 trading");
    }

    @Test
    @DisplayName("配置切换不改变已派发调用：慢响应期间换路由，请求仍用旧 model/output")
    void routeSwitchDoesNotAffectDispatchedCall() throws Exception {
        openHost("switch");
        provider.setDelayMillis(200L);
        host.startLiveRound(1L, routes.view().revision(), List.of("bob"));
        awaitRequests(1, 10_000L);
        configureRoutes("key-3", "deepseek-v4-pro", 1024, "key-4", "deepseek-flash", 768);
        awaitSettled(10_000L);
        assertEquals(1, provider.requestJsonList().size());
        JsonNode request = provider.requestJsonList().get(0);
        assertEquals("deepseek-v4-pro", request.path("model").asText());
        assertEquals(640, request.path("max_tokens").asInt());
    }

    // ------------------------------------------------------------ 容量与停止

    @Test
    @DisplayName("并发：运行最多 2 个物理请求，同 scene 最多 1 个")
    void boundedConcurrencyPerRunAndScene() throws Exception {
        openHost("concurrency");
        provider.setDelayMillis(250L);
        host.startLiveRound(1L, routes.view().revision(),
                List.of("alice", "bob", "carol", "dave"));
        awaitRequests(4, 15_000L);
        assertTrue(provider.maxConcurrent() <= 2, "运行并发上限 2，实际 "
                + provider.maxConcurrent());
        assertTrue(provider.maxConcurrentTrading() <= 1,
                "交易场景物理并发上限 1，实际 " + provider.maxConcurrentTrading());
    }

    @Test
    @DisplayName("停止：不再为未开始人物创建决定，已派发继续结算")
    void stopDoesNotDispatchRemainingPersons() throws Exception {
        openHost("stop");
        provider.hold();
        host.startLiveRound(1L, routes.view().revision(), List.of("bob", "carol"));
        awaitRequests(1, 10_000L);
        WorldWebHost.LiveStatus active = host.liveStatus();
        assertEquals(1, active.physicalInFlight());
        assertEquals(1, active.pendingPersons().size());
        host.stopLiveRound(parseLong(active.position()));
        assertTrue(host.liveStatus().pendingPersons().isEmpty(), "停止清空未开始名单");
        provider.releaseAll();
        WorldWebHost.LiveStatus done = awaitSettled(10_000L);
        assertEquals(1, provider.calls(), "第二人不得被派发");
        assertEquals(1, done.decisions().size());
    }

    @Test
    @DisplayName("暂停：worker 仍登记完成但槽仍占；恢复后才归档")
    void pauseKeepsRegisteredCompletionInSlot() throws Exception {
        openHost("pause");
        provider.setDelayMillis(250L);
        host.startLiveRound(1L, routes.view().revision(), List.of("alice"));
        awaitRequests(1, 10_000L);
        long position = parseLong(host.liveStatus().position());
        host.pauseLive(position);
        Thread.sleep(700L);
        WorldWebHost.LiveStatus paused = host.liveStatus();
        assertEquals("PAUSED", paused.phase());
        assertEquals(0, paused.physicalInFlight(), "HTTP 已结束，物理在途归零");
        assertEquals(1, paused.completionSlots(), "已登记未归档仍占完成槽");
        assertEquals("OPEN", paused.decisions().get(0).status(), "暂停不得应用回执");
        assertThrows(WorldWebHost.PositionMismatch.class,
                () -> host.resumeLive(position + 1));
        host.resumeLive(parseLong(host.liveStatus().position()));
        WorldWebHost.LiveStatus resumed = awaitSettled(10_000L);
        assertNotEquals("OPEN", resumed.decisions().get(0).status());
        assertEquals(0, resumed.completionSlots());
        assertEquals(1, provider.calls(), "恢复不重发请求");
    }

    @Test
    @DisplayName("暂停同时阻止推进：位置校验原子生效，恢复后才允许 advance")
    void pauseBlocksWorldAdvance() throws Exception {
        openHost("advance");
        long position = parseLong(host.liveStatus().position());
        assertThrows(WorldWebHost.PositionMismatch.class,
                () -> host.pauseLive(position + 5));
        assertFalse(host.liveStatus().paused());
        host.pauseLive(position);
        assertThrows(IllegalStateException.class, () -> host.advance(position, 5_000L));
        host.resumeLive(position);
        assertTrue(host.advance(position, 5_000L) > position);
    }

    // ------------------------------------------------------------ 失败与预算

    @Test
    @DisplayName("传输失败：固定技术失败与未知 usage，不产生观望")
    void transportFailureBecomesTechnicalFailure() throws Exception {
        openHost("failure");
        provider.enqueue(500, "{\"error\":\"boom\"}");
        host.startLiveRound(1L, routes.view().revision(), List.of("alice"));
        awaitRequests(1, 10_000L);
        WorldWebHost.LiveStatus status = awaitSettled(10_000L);
        WorldWebHost.DecisionView decision = status.decisions().get(0);
        assertEquals("TECHNICAL_FAILED", decision.outcome());
        assertTrue(decision.reason() != null
                        && decision.reason().contains("HTTP_SERVICE_UNAVAILABLE"),
                "固定失败码，实际 " + decision.reason());
        assertFalse(decision.replyPreview().contains("role.wait"));
        assertEquals("0", status.runBudget().reservedCalls(), "结算后不得残留预留");
    }

    @Test
    @DisplayName("预算触顶：真实预算终态与原因，无新 HTTP、无新未派发 OPEN")
    void budgetCapStopsNewRequests() throws Exception {
        openHost("budget");
        widenSceneCaps();
        long time = 0L;
        for (int round = 0; round < 25; round++) {
            WorldWebHost.LiveStatus status = host.liveStatus();
            host.startLiveRound(parseLong(status.position()), routes.view().revision(),
                    List.of("alice"));
            awaitSettled(20_000L);
            WorldWebHost.LiveStatus after = host.liveStatus();
            time = parseLong(after.timeMillis()) + 2_000L;
            host.advance(parseLong(after.position()), time);
        }
        assertEquals(25, provider.calls(), "逐人上限 25 次调用");
        WorldWebHost.LiveStatus status = host.liveStatus();
        host.startLiveRound(parseLong(status.position()), routes.view().revision(),
                List.of("alice"));
        WorldWebHost.LiveStatus blocked = awaitPhase("IDLE", 8_000L);
        assertEquals(25, provider.calls(), "触顶后不得再请求");
        WorldWebHost.DecisionView terminal = blocked.decisions().get(0);
        assertEquals("EXPIRED", terminal.status(), "预算拒绝必须留下真实终态");
        assertEquals("BUDGET_EXHAUSTED", terminal.outcome());
        assertTrue(blocked.decisions().stream()
                        .noneMatch(view -> "OPEN".equals(view.status())),
                "不得留下未派发的 OPEN 屏障");
        assertNotNull(terminal.reason(), "必须带具体原拒绝原因");
        assertEquals("BUDGET_EXHAUSTED", terminal.reason(), "保留模型实际预算拒绝原因");
        assertEquals(0, blocked.completionSlots());
        assertEquals(0, blocked.physicalInFlight());
        assertEquals("0", host.liveStatus().runBudget().reservedCalls());
        assertTrue(host.liveStatus().skips().stream()
                        .anyMatch(skip -> "alice".equals(skip.personPartyId())),
                "跳过原因必须指向 alice");
    }

    // ------------------------------------------------------------ 拒绝与旧构造

    @Test
    @DisplayName("非法/过期开始：typed 位置与修订冲突，均无副作用")
    void invalidStartHasNoSideEffects() throws Exception {
        openHost("reject");
        long revision = routes.view().revision();
        assertThrows(WorldWebHost.PositionMismatch.class,
                () -> host.startLiveRound(999L, revision, List.of("alice")));
        assertThrows(SceneRoutes.RevisionMismatch.class,
                () -> host.startLiveRound(1L, revision + 5, List.of("alice")));
        assertThrows(IllegalArgumentException.class,
                () -> host.startLiveRound(1L, revision, List.of("investor")));
        assertThrows(IllegalArgumentException.class,
                () -> host.startLiveRound(1L, revision, List.of()));
        assertEquals(0, provider.calls());
        assertEquals("IDLE", host.liveStatus().phase());
    }

    @Test
    @DisplayName("路由修订冲突：旧修订写入被拒绝，配置与轮次均无副作用")
    void staleRouteRevisionRejected() throws Exception {
        openHost("revision");
        long revision = routes.view().revision();
        List<SceneRoutes.Route> updated = new ArrayList<>();
        for (SceneRoutes.Route route : routes.view().routes()) {
            updated.add(new SceneRoutes.Route(route.scene(), route.slotId(), route.model(),
                    route.enabled(), route.maxCalls(), route.maxTotalTokens(),
                    route.maxOutputTokens(), route.maxConcurrency()));
        }
        assertThrows(SceneRoutes.RevisionMismatch.class,
                () -> host.replaceRoutes(revision + 3, updated));
        assertEquals(revision, routes.view().revision());
        assertEquals(0, provider.calls());
    }

    @Test
    @DisplayName("旧构造保持：无 SceneRoutes/provider 时只有 scripted 行为")
    void legacyConstructorStaysScripted() throws Exception {
        Path journal = dir.resolve("legacy.journal");
        host = new WorldWebHost(() -> WorldWebScenario.open(journal));
        assertTrue(host.adminSnapshot().accounts().size() > 0);
        assertEquals("SCRIPTED", host.liveStatus().phase());
        assertThrows(IllegalStateException.class,
                () -> host.startLiveRound(1L, 0L, List.of("alice")));
        assertEquals(0, provider.calls());
    }

    @Test
    @DisplayName("重启：读取不自动请求；显式 start 先保守结算旧代次，再实际发送一次新请求")
    void restartRequiresExplicitStartAndActuallyContinues() throws Exception {
        Path journal = dir.resolve("restart.journal");
        openHostAt(journal);
        provider.hold();
        host.startLiveRound(1L, routes.view().revision(), List.of("alice"));
        awaitRequests(1, 10_000L);
        assertEquals(1, provider.calls());

        host.close();
        host = null;
        provider.releaseAll();
        provider.holdOff();

        openHostAt(journal);
        WorldWebHost.LiveStatus recovered = host.liveStatus();
        assertEquals("OPEN", recovered.decisions().get(0).status(),
                "旧尝试在显式接续前保持 OPEN");
        Thread.sleep(300L);
        assertEquals(1, provider.calls(), "重启与读取绝不自动请求");

        long position = parseLong(recovered.position());
        host.startLiveRound(position, routes.view().revision(), List.of("alice"));
        awaitRequests(2, 15_000L);
        WorldWebHost.LiveStatus after = awaitSettled(20_000L);
        assertEquals(2, provider.calls(), "显式接续必须实际再发一次请求");
        assertEquals(2, after.decisions().size());
        long expired = after.decisions().stream()
                .filter(view -> "EXPIRED".equals(view.status())).count();
        long business = after.decisions().stream()
                .filter(view -> "WAIT".equals(view.outcome())).count();
        assertEquals(1L, expired, "旧代次尝试必须保守结算为 EXPIRED");
        assertEquals(1L, business, "新尝试产生一次真实业务回执");
    }

    // ------------------------------------------------------------ fixture

    private void openHost(String name) throws Exception {
        openHostAt(dir.resolve(name + ".journal"));
    }

    private void openHostAt(Path journal) {
        host = new WorldWebHost(() -> WorldWebScenario.openLive(journal), routes,
                new LiveDecisionProvider(slot -> new DeepSeekClient(
                        () -> KEYS.get(slot), provider.uri())));
    }

    private long liveSeed() throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openLive(
                dir.resolve("seed-probe-" + System.nanoTime() + ".journal")).runtime()) {
            return runtime.snapshot().decisions().orElseThrow().state().seed();
        }
    }

    private void configureRoutes(String researchSlot, String researchModel, int researchOutput,
                                 String tradingSlot, String tradingModel, int tradingOutput)
            throws Exception {
        SceneRoutes.Snapshot snapshot = routes.view();
        List<SceneRoutes.Route> updated = new ArrayList<>();
        for (SceneRoutes.Route route : snapshot.routes()) {
            if (route.scene() == SceneRoutes.Scene.RESEARCH) {
                updated.add(new SceneRoutes.Route(route.scene(), researchSlot, researchModel, true,
                        5, 1_000_000, researchOutput, 1));
            } else if (route.scene() == SceneRoutes.Scene.TRADING) {
                updated.add(new SceneRoutes.Route(route.scene(), tradingSlot, tradingModel, true,
                        5, 1_000_000, tradingOutput, 1));
            } else {
                updated.add(new SceneRoutes.Route(route.scene(), route.slotId(), route.model(),
                        false, route.maxCalls(), route.maxTotalTokens(),
                        route.maxOutputTokens(), route.maxConcurrency()));
            }
        }
        routes.replace(snapshot.revision(), updated);
    }

    private void widenSceneCaps() throws Exception {
        SceneRoutes.Snapshot snapshot = routes.view();
        List<SceneRoutes.Route> wide = new ArrayList<>();
        for (SceneRoutes.Route route : snapshot.routes()) {
            boolean live = route.scene() == SceneRoutes.Scene.RESEARCH
                    || route.scene() == SceneRoutes.Scene.TRADING;
            wide.add(new SceneRoutes.Route(route.scene(), route.slotId(), route.model(), live,
                    100, 1_000_000, route.maxOutputTokens(), 1));
        }
        routes.replace(snapshot.revision(), wide);
    }

    private WorldWebHost.LiveStatus awaitPhase(String phase, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        WorldWebHost.LiveStatus last = host.liveStatus();
        while (System.currentTimeMillis() < deadline) {
            last = host.liveStatus();
            if (phase.equals(last.phase())) {
                return last;
            }
            Thread.sleep(50L);
        }
        return last;
    }

    private WorldWebHost.LiveStatus awaitSettled(long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        WorldWebHost.LiveStatus last = host.liveStatus();
        while (System.currentTimeMillis() < deadline) {
            last = host.liveStatus();
            boolean idleTransport = last.completionSlots() == 0 && last.physicalInFlight() == 0;
            if (idleTransport && !last.decisions().isEmpty()
                    && last.decisions().stream().noneMatch(view -> "OPEN".equals(view.status()))) {
                return last;
            }
            Thread.sleep(50L);
        }
        return last;
    }

    private void awaitRequests(int count, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline && provider.calls() < count) {
            Thread.sleep(25L);
        }
        assertTrue(provider.calls() >= count,
                "期望至少 " + count + " 次请求，实际 " + provider.calls());
    }

    private static long parseLong(String value) {
        return Long.parseLong(value);
    }

    /** 真实回环提供方：可延迟、可阻塞、按队列返回；记录请求与并发。 */
    private static final class Provider implements AutoCloseable {

        private record Reply(int status, String body) {
        }

        private final HttpServer server;
        private final List<String> bodies = new ArrayList<>();
        private final List<Reply> replies = new ArrayList<>();
        private final AtomicInteger counter = new AtomicInteger();
        private final AtomicInteger concurrent = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final AtomicInteger concurrentTrading = new AtomicInteger();
        private final AtomicInteger maxConcurrentTrading = new AtomicInteger();
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile long delayMillis;
        private volatile boolean holding;

        private Provider() throws Exception {
            server = HttpServer.create(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/chat/completions", this::handle);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
        }

        private void enqueue(int status, String body) {
            synchronized (replies) {
                replies.add(new Reply(status, body));
            }
        }

        private void hold() {
            holding = true;
        }

        private void holdOff() {
            holding = false;
        }

        private void releaseAll() {
            holding = false;
            while (release.getCount() > 0) {
                release.countDown();
            }
        }

        private void setDelayMillis(long delay) {
            this.delayMillis = delay;
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] request = exchange.getRequestBody().readAllBytes();
            String body = new String(request, StandardCharsets.UTF_8);
            boolean trading = body.contains("deepseek-v4-pro");
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            if (trading) {
                maxConcurrentTrading.accumulateAndGet(concurrentTrading.incrementAndGet(),
                        Math::max);
            }
            try {
                synchronized (this) {
                    bodies.add(body);
                    counter.incrementAndGet();
                }
                if (holding) {
                    try {
                        release.await(8, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                Reply reply;
                synchronized (replies) {
                    reply = replies.isEmpty()
                            ? new Reply(200, successBody("{\"tool\":\"role.wait\"}"))
                            : replies.remove(0);
                }
                byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), payload.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(payload);
                } catch (IOException ignored) {
                    // 客户端超时后连接可能已关闭。
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
                if (trading) {
                    concurrentTrading.decrementAndGet();
                }
            }
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/chat/completions");
        }

        private int calls() {
            return counter.get();
        }

        private int maxConcurrent() {
            return maxConcurrent.get();
        }

        private int maxConcurrentTrading() {
            return maxConcurrentTrading.get();
        }

        private synchronized List<JsonNode> requestJsonList() throws IOException {
            List<JsonNode> parsed = new ArrayList<>();
            for (String body : bodies) {
                parsed.add(JSON.readTree(body));
            }
            return parsed;
        }

        @Override
        public void close() {
            releaseAll();
            server.stop(0);
        }

        private static String successBody(String content) {
            return "{\"id\":\"req-fixture\",\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":"
                    + JSON.valueToTree(content).toString()
                    + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,"
                    + "\"completion_tokens\":5,\"total_tokens\":16}}";
        }
    }
}
