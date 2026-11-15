package edu.cufe.auction.web;

import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 调度边界测试，注入回执不构成真实模型行为或经济自主性证据。 */
class WorldAutonomyTest {
    @TempDir Path dir;

    private SceneRoutes routes(int calls) throws Exception {
        var routes = SceneRoutes.open(dir);
        routes.replace(routes.view().revision(), routes.view().routes().stream().map(r ->
                new SceneRoutes.Route(r.scene(), r.slotId(), r.model(), true,
                        calls, 1_000_000, 1024, Math.min(4, calls))).toList());
        return routes;
    }

    private WorldWebHost host(SceneRoutes routes, WorldWebHost.DecisionProvider provider) {
        return new WorldWebHost(() -> {
            var world = ComposedIndustryScenario.openAutonomous(dir.resolve("world.journal"));
            return new WorldWebHost.Opened(world, world.profile().manifest().runId());
        }, routes, provider);
    }

    private static LiveCompletionInbox.Completion waitReply(DecisionRun.Call c) {
        return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(c.runId(), c.actionId(),
                c.attemptId(), c.attemptNo(), c.recoveryGeneration(), "{\"tool\":\"role.wait\"}",
                DecisionModel.Usage.unknownCost(1, 10, 6), "TEST"));
    }

    private static void start(WorldWebHost host, SceneRoutes routes, long target) throws Exception {
        var status = host.autonomyStatus();
        host.startAutonomy(Long.parseLong(status.position()), routes.view().revision(), target, 3_600_000L);
    }

    private static WorldWebHost.AutonomyStatus finished(WorldWebHost host) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        WorldWebHost.AutonomyStatus status;
        do {
            status = host.autonomyStatus();
            if (!status.running()) return status;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("运行没有停止：" + status);
        return status;
    }

    @Test void allTenCompaniesAndActualRolePopulationWakeWithoutBrowser() throws Exception {
        Set<String> people = ConcurrentHashMap.newKeySet();
        Set<String> scenes = ConcurrentHashMap.newKeySet();
        var wakeCounts = new ConcurrentHashMap<String, AtomicInteger>();
        try (var routes = routes(100); var host = host(routes, (call, timeout) -> {
            String person = call.actionId().substring(call.actionId().lastIndexOf(":act:") + 5);
            people.add(person);
            int count = wakeCounts.computeIfAbsent(person, key -> new AtomicInteger()).incrementAndGet();
            scenes.add(call.plan().route().sceneId());
            if (person.equals("alice") && count == 1) {
                // 研究路由不抹去其真实董事身份；必须实际落成会议，而非仅记录模型文本。
                return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(), call.actionId(),
                        call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                        "{\"tool\":\"board.convene\",\"args\":{\"companyPartyId\":\"north\",\"meetingId\":\"autonomy-board\",\"materialDeadline\":\"15000\",\"opensAt\":\"20000\",\"closesAt\":\"30000\",\"topic\":\"MANAGER_APPOINTMENT\"}}",
                        DecisionModel.Usage.unknownCost(1, 10, 60), "TEST"));
            }
            if (person.equals("investor")) return LiveCompletionInbox.Completion.business(
                    new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                            call.recoveryGeneration(), "{\"tool\":\"role.wait\",\"args\":{}}",
                            DecisionModel.Usage.unknownCost(1, 10, 12), "TEST"));
            return waitReply(call);
        })) {
            start(host, routes, 7_200_000L);
            var status = finished(host);
            assertEquals("TARGET_REACHED", status.reason(), host.liveStatus().decisions().stream()
                    .map(d -> d.personPartyId() + ":" + d.outcome() + ":" + d.reason()).toList().toString());
            assertEquals("7200000", status.timeMillis());
            assertEquals(10, status.companies());
            assertEquals(status.persons(), people.size());
            assertTrue(people.size() >= 50, people.toString());
            assertTrue(people.containsAll(Set.of("alice", "bob", "carol", "dave")));
            assertTrue(scenes.containsAll(Set.of("governance", "research", "trading", "media")), scenes.toString());
            assertEquals(2, status.cycles());
            assertEquals(2, status.failures(), "非法回执仍记失败，但不能冻结别人的合法动作");
            wakeCounts.forEach((person, count) -> assertEquals(2, count.get(), "兼任多岗位也不能重复唤醒：" + person));
            assertTrue(host.engineeringView("north").meetings().rows().stream()
                    .anyMatch(row -> row.containsValue("autonomy-board")), "模型意图须经真实任务落成会议");
        }
        // 重开保留真实世界时点，自动控制没有偷偷重开付费运行。
        try (var routes = SceneRoutes.open(dir); var host = host(routes, (c, t) -> {
            fail("未显式开始，不能调用提供方"); return waitReply(c);
        })) {
            assertEquals("7200000", host.autonomyStatus().timeMillis());
            assertEquals("NOT_STARTED", host.autonomyStatus().reason());
        }
    }

    @Test void stoppedInFlightDoesNotAdvanceOrDispatchMoreCalls() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        Set<String> beforeRestart = ConcurrentHashMap.newKeySet();
        try (var routes = routes(100); var host = host(routes, (c, t) -> {
            beforeRestart.add(c.actionId().substring(c.actionId().lastIndexOf(":act:") + 5));
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            return waitReply(c);
        })) {
            try {
                String initialTime = host.autonomyStatus().timeMillis();
                start(host, routes, 3_600_000L);
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(initialTime, host.autonomyStatus().timeMillis());
                host.stopAutonomy();
                int stoppedCalls = calls.get();
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!"IDLE".equals(host.liveStatus().phase()) && System.nanoTime() < deadline) Thread.sleep(25);
                assertEquals("IDLE", host.liveStatus().phase());
                assertEquals(stoppedCalls, calls.get());
                assertEquals(initialTime, host.autonomyStatus().timeMillis());
                assertEquals("STOPPED", host.autonomyStatus().reason());
            } finally { release.countDown(); }
        }
        Set<String> afterRestart = ConcurrentHashMap.newKeySet();
        try (var routes = SceneRoutes.open(dir); var host = host(routes, (c, t) -> {
            afterRestart.add(c.actionId().substring(c.actionId().lastIndexOf(":act:") + 5));
            return waitReply(c);
        })) {
            assertFalse(host.autonomyStatus().running());
            start(host, routes, 3_600_000L);
            var result = finished(host);
            assertEquals("TARGET_REACHED", result.reason());
            assertTrue(java.util.Collections.disjoint(beforeRestart, afterRestart), "同一时点重开不可重复调用已决定人物");
            assertEquals(result.persons(), beforeRestart.size() + afterRestart.size());
        }
    }

    @Test void finiteBudgetStopsExplicitlyWithoutMakingUpWaitsOrAdvancing() throws Exception {
        var calls = new AtomicInteger();
        try (var routes = routes(1); var host = host(routes, (c, t) -> {
            calls.incrementAndGet(); return waitReply(c);
        })) {
            String initialTime = host.autonomyStatus().timeMillis();
            start(host, routes, 3_600_000L);
            var status = finished(host);
            assertEquals("BUDGET_EXHAUSTED", status.reason());
            assertEquals(initialTime, status.timeMillis());
            assertTrue(calls.get() <= 4);
            assertEquals(0, status.cycles());
        }
    }

    @Test void completeProviderFailureStopsBeforeClockAdvances() throws Exception {
        try (var routes = routes(100); var host = host(routes, (c, t) ->
                LiveCompletionInbox.Completion.failure("HTTP_UNAVAILABLE", new DecisionModel.Reply(c.runId(),
                        c.actionId(), c.attemptId(), c.attemptNo(), c.recoveryGeneration(), "", null, "TEST")))) {
            String initial = host.autonomyStatus().timeMillis();
            start(host, routes, 3_600_000L);
            var result = finished(host);
            assertEquals("ALL_DECISIONS_FAILED", result.reason());
            assertEquals(initial, result.timeMillis());
            assertEquals(result.persons(), result.failures());
        }
    }

    @Test void disabledScenesAndRuleDrivenProfilesCannotSilentlySubstituteForModels() throws Exception {
        try (var routes = SceneRoutes.open(dir); var host = host(routes, (c, t) -> {
            fail("场景关闭不应调用"); return waitReply(c);
        })) {
            String before = host.autonomyStatus().position();
            assertThrows(WorldWebHost.SceneRejected.class, () -> start(host, routes, 3_600_000L));
            assertEquals(before, host.autonomyStatus().position());
        }
        try (var routes = SceneRoutes.open(dir); var host = new WorldWebHost(() -> {
            var world = ComposedIndustryScenario.openConnected(dir.resolve("rule.journal"));
            return new WorldWebHost.Opened(world, world.profile().manifest().runId());
        }, routes, (WorldWebHost.DecisionProvider) (c, t) -> waitReply(c))) {
            assertFalse(host.autonomyStatus().available());
            assertThrows(IllegalStateException.class, () -> start(host, routes, 3_600_000L));
        }
    }

    @Test void localHttpControlRetainsSessionOriginAndInputValidation() throws Exception {
        var keyFile = dir.resolve("dummy-keys.txt");
        Files.writeString(keyFile, String.join("\n", List.of("sk-autonomy-test-key-0001", "sk-autonomy-test-key-0002",
                "sk-autonomy-test-key-0003", "sk-autonomy-test-key-0004", "sk-autonomy-test-key-0005")));
        try (var routes = routes(1); var host = host(routes, (c, t) -> waitReply(c));
             var server = new WorldWebServer(host, KeySlots.load(keyFile), routes, 0, "dummy-bootstrap", null, s -> {})) {
            var client = HttpClient.newHttpClient();
            server.enableLocalAccess(); server.start();
            String url = server.baseUrl();
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(url + "/api/admin/autonomy")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var login = client.send(HttpRequest.newBuilder(URI.create(url + "/api/session/local"))
                    .header("Origin", url).header("X-World-Request", "1").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            for (String body : List.of("{", "{\"action\":\"start\"}", "{\"action\":\"stop\",\"unexpected\":true}")) {
                var response = client.send(HttpRequest.newBuilder(URI.create(url + "/api/admin/autonomy"))
                        .header("Origin", url).header("X-World-Request", "1").header("Cookie", cookie)
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(400, response.statusCode(), response.body());
            }
            var crossSite = client.send(HttpRequest.newBuilder(URI.create(url + "/api/admin/autonomy"))
                    .header("Origin", "https://elsewhere.example").header("X-World-Request", "1").header("Cookie", cookie)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"stop\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, crossSite.statusCode());
            var status = host.autonomyStatus();
            String body = "{\"action\":\"start\",\"expectedPosition\":\"" + status.position()
                    + "\",\"expectedRevision\":\"" + routes.view().revision()
                    + "\",\"targetMillis\":\"3600000\",\"stepMillis\":\"3600000\"}";
            var started = client.send(HttpRequest.newBuilder(URI.create(url + "/api/admin/autonomy"))
                    .header("Origin", url).header("X-World-Request", "1").header("Cookie", cookie)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, started.statusCode(), started.body());
            assertEquals("BUDGET_EXHAUSTED", finished(host).reason());
        }
    }
}
