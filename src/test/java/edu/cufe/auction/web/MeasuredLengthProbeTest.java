package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.WorldRuntime;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MeasuredLengthProbeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODE = "ENGINEERING_CUSTOMER_OPERATING";
    @TempDir Path dir;

    @Test void nativeHostChargesFirstPersonBeforeAnotherReceivesACompleteReplyAndReopens() throws Exception {
        exerciseHost(false);
    }

    @Test void previouslyAdmittedSiblingCanDrainWhileTheNextAdmissionWaitsForActualSettlement() throws Exception {
        exerciseHost(true);
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new AssertionError("未达到测试中的受控时序");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    void exerciseHost(boolean concurrent) throws Exception {
        // Bob 和 Carol 同属 trading；Alice 在另一个 research 路由中，允许与 Bob 同时获得派发。
        var truncated = new AtomicReference<String>(concurrent ? "alice" : null);
        var firstTrader = new AtomicReference<String>();
        var people = concurrent ? List.of("alice", "bob", "carol") : List.of("bob", "carol");
        var journal = dir.resolve("world.journal");
        var current = new AtomicReference<WorldRuntime>();
        var siblingInHttp = new java.util.concurrent.CountDownLatch(1);
        var lengthRecorded = new java.util.concurrent.CountDownLatch(1);
        var gateObservedUnsettled = new java.util.concurrent.CountDownLatch(1);
        var policy = new MeasuredLengthPolicy(() -> {
            var run = current.get().snapshot().decisions().orElseThrow();
            if (concurrent && lengthRecorded.getCount() == 0 && run.state().decisions().stream().anyMatch(d ->
                    d.personPartyId().equals(truncated.get()) && !d.lastAttempt().settledDone()))
                gateObservedUnsettled.countDown();
            return run;
        });
        var evidence = new ProbeProviderEvidence(dir, true, policy);
        var config = new AutonomousLifecycleProbe.Config(MODE, people.size(), 1, 3600000, 3600000, null, "test");
        var issued = new AtomicInteger();
        var physical = new AtomicInteger();
        var paidAtNextRequest = new AtomicReference<DecisionModel.Usage>();
        var httpWorkers = java.util.concurrent.Executors.newCachedThreadPool();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setExecutor(httpWorkers);
        server.createContext("/chat/completions", exchange -> {
            try {
                var request = JSON.readTree(exchange.getRequestBody());
                physical.incrementAndGet();
                var input = JSON.readTree(request.at("/messages/1/content").asText());
                String actor = input.path("person").asText();
                if (!concurrent) truncated.compareAndSet(null, actor);
                boolean length = truncated.get().equals(actor);
                boolean admittedSibling = concurrent && !length && firstTrader.compareAndSet(null, actor);
                if (concurrent && length) await(siblingInHttp);
                if (admittedSibling) {
                    siblingInHttp.countDown();
                    await(lengthRecorded);
                }
                if (!length && !admittedSibling)
                    paidAtNextRequest.set(current.get().snapshot().decisions().orElseThrow().state().decisions().stream()
                            .filter(d -> d.personPartyId().equals(truncated.get())).findFirst().orElseThrow().lastAttempt().settled());
                var answer = JSON.createObjectNode().put("tool", "role.wait");
                answer.putObject("decision").put("objective", input.at("/engineering/deliberation/objectives/0/id").asText())
                        .put("basis", "已检查本人可见的新资料").put("reviewAfterMillis", 3_600_000);
                var body = JSON.createObjectNode().put("id", "localhost-" + actor).put("model", "deepseek-flash");
                body.putArray("choices").addObject().put("index", 0).put("finish_reason", length ? "length" : "stop")
                        .putObject("message").put("role", "assistant").put("content", length ? "截断的无效动作" : answer.toString());
                int tokens = length ? 8192 : 60;
                body.putObject("usage").put("prompt_tokens", 70).put("completion_tokens", tokens).put("total_tokens", 70 + tokens);
                byte[] bytes = JSON.writeValueAsBytes(body);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var routes = SceneRoutes.open(dir)) {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
            var provider = new LiveDecisionProvider(slot -> new DeepSeekClient(() -> "sk-local-fixture-0000000000", uri));
            var single = AutonomousLifecycleProbe.probeRoutes(routes.view(), MODE).stream().map(r ->
                    new SceneRoutes.Route(r.scene(), r.slotId(), r.model(), true, r.maxCalls(), r.maxTotalTokens(),
                            r.maxOutputTokens(), 1, r.budgetPolicy(), r.providerPolicy())).toList();
            routes.replace(0, single);
            ProbeProviderEvidence.freezePolicy(dir, true, true);
            try (var host = new WorldWebHost(() -> {
                var world = AutonomousLifecycleProbe.openWorld(MODE, journal);
                current.set(world);
                return new WorldWebHost.Opened(world, world.profile().manifest().runId());
            }, routes, (call, timeout) -> {
                var receipt = AutonomousLifecycleProbe.evaluateEvidence(config, evidence, issued, call, timeout, provider, () -> { });
                if (concurrent && "PROVIDER_LENGTH".equals(receipt.failureCode())) {
                    lengthRecorded.countDown();
                    // writer 尚未收到 Alice 的候选。一个交易角色已派发，另一个交易角色需要通过新准入。
                    await(gateObservedUnsettled);
                }
                return receipt;
            })) {
                host.startLiveRound(Long.parseLong(host.liveStatus().position()), routes.view().revision(), people);
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (System.nanoTime() < deadline) {
                    var rows = current.get().snapshot().decisions().orElseThrow().state().decisions();
                    if (rows.size() == people.size() && rows.stream().allMatch(d -> d.lastAttempt().settledDone())) break;
                    Thread.sleep(10);
                }
                evidence.settleMeasuredLengths(null, 0);
                assertFalse(evidence.stopRequested(), evidence.stopReason());
                assertEquals(people.size(), physical.get());
                if (concurrent) assertEquals(0, gateObservedUnsettled.getCount());
                assertNotNull(paidAtNextRequest.get());
                assertEquals(8192, paidAtNextRequest.get().outputTokens());
            }
        } finally {
            server.stop(0);
            httpWorkers.shutdownNow();
        }

        var result = JSON.createObjectNode().put("httpCalls", people.size());
        result.set("providerProvenance", evidence.summary(people.size(), 0));
        result.set("config", JSON.valueToTree(config));
        result.putObject("live").put("physicalInFlight", 0);
        JSON.writeValue(dir.resolve("config.json").toFile(), config);
        Files.writeString(dir.resolve("system-prompt.txt"), LiveDecisionProvider.PLANNING_SYSTEM_TEXT);
        JSON.writeValue(dir.resolve("result.json").toFile(), result);
        MeasuredLengthEvidence.seal(dir, null);
        ProbeProviderEvidence.validateSeed(dir, result, true, true);
        assertThrows(IllegalArgumentException.class, () -> ProbeProviderEvidence.validateSeed(dir, result, true));
        Path copy = dir.resolve("sealed-replay.journal");
        Files.copy(journal, copy);
        try (var restored = AutonomousLifecycleProbe.openWorld(MODE, copy)) {
            var run = restored.snapshot().decisions().orElseThrow();
            var rows = run.state().decisions();
            assertEquals(people.size(), rows.size());
            var failed = rows.stream().filter(d -> d.personPartyId().equals(truncated.get())).findFirst().orElseThrow();
            assertEquals(DecisionModel.Status.EXPIRED, failed.status());
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, failed.outcome());
            assertEquals("PROVIDER_LENGTH", failed.lastAttempt().reason());
            assertEquals("", failed.lastAttempt().replyBody());
            assertNull(run.actionRef(failed.actionId()));
            assertEquals(1, failed.attempts().size());
            rows.stream().filter(d -> !d.personPartyId().equals(truncated.get()))
                    .forEach(d -> assertEquals(DecisionModel.Outcome.WAIT, d.outcome()));
            assertEquals(failed.lastAttempt().declaredUsage(), failed.lastAttempt().settled());
            assertEquals(failed.lastAttempt().settled(), run.state().personUsage().get(truncated.get()));
            assertEquals(people.size(), run.state().runUsage().attempts());
            assertEquals(8192 + (people.size()-1)*60, run.state().runUsage().outputTokens());
            var restoredPolicy = new MeasuredLengthPolicy(() -> run);
            restoredPolicy.restore(dir);
            assertTrue(restoredPolicy.confirmBefore(null, 0));
            assertFalse(restoredPolicy.confirmBefore(failed.actionId(), 0));
            for (int i = 1; i <= people.size(); i++) {
                var call = JSON.readValue(dir.resolve("call-" + i + ".json").toFile(), DecisionRun.Call.class);
                if (!call.actionId().equals(failed.actionId())) continue;
                var reply = JSON.readValue(dir.resolve("reply-" + i + ".json").toFile(), LiveCompletionInbox.Completion.class).reply();
                var mismatch = new MeasuredLengthPolicy(() -> run);
                mismatch.remember(call, new DecisionModel.Reply(reply.runId(), reply.actionId(), reply.attemptId(),
                        reply.attemptNo(), reply.recoveryGeneration(), "", DecisionModel.Usage.unknownCost(1, 71, 8192), reply.usageSource()));
                assertFalse(mismatch.confirmBefore(null, 0));
            }
        }
    }

    @Test void unknownTechnicalEvidenceAndUnwritableArchivesStillStopGlobally() throws Exception {
        var call = FrozenThinkingProviderTest.call(FrozenThinkingProviderTest.route(DeepSeekClient.THINKING_HIGH_POLICY));
        var local = new FrozenThinkingProviderTest();
        local.root = dir;
        var policy = new MeasuredLengthPolicy(() -> { throw new AssertionError("没有完整的截断回执"); });
        var missing = new ProbeProviderEvidence(dir, true, policy);
        var json = JSON.readTree(FrozenThinkingProviderTest.response("length", "", 8192));
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("usage");
        local.send(call, json.toString(), 200, new AtomicReference<>(), missing);
        assertEquals(ProbeProviderEvidence.TECHNICAL_FAILURE, missing.stopReason());
        var badWrite = new ProbeProviderEvidence(dir.resolve("absent"), true, policy);
        assertThrows(java.io.UncheckedIOException.class, () -> local.send(call,
                FrozenThinkingProviderTest.response("length", "partial", 8192), 200, new AtomicReference<>(), badWrite));
        assertEquals(ProbeProviderEvidence.WRITE_FAILURE, badWrite.stopReason());
    }

    @Test void onlyCompleteMeasuredProviderEvidenceQualifies() throws Exception {
        var call = FrozenThinkingProviderTest.call(FrozenThinkingProviderTest.route(DeepSeekClient.THINKING_HIGH_POLICY));
        var source = new FrozenThinkingProviderTest();
        var measured = source.send(call, FrozenThinkingProviderTest.response("length", "partial", 8192), 200, new AtomicReference<>());
        assertTrue(MeasuredLengthPolicy.matches(call, measured.completion(), measured.provenance()));
        for (String key : List.of("requestId", "providerReportedModel", "requestedModel", "actionId", "schema", "failureCode")) {
            var node = JSON.valueToTree(measured.provenance());
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).putNull(key);
            var different = JSON.treeToValue(node, LiveDecisionProvider.ProviderProvenance.class);
            assertFalse(MeasuredLengthPolicy.matches(call, measured.completion(), different), key);
        }
        var shortOutput = source.send(call, FrozenThinkingProviderTest.response("length", "partial", 8100), 200, new AtomicReference<>());
        assertFalse(MeasuredLengthPolicy.matches(call, shortOutput.completion(), shortOutput.provenance()));
        var filtered = source.send(call, FrozenThinkingProviderTest.response("content_filter", "", 8192), 200, new AtomicReference<>());
        assertFalse(MeasuredLengthPolicy.matches(call, filtered.completion(), filtered.provenance()));
        assertFalse(Files.exists(dir.resolve("provider-1.json")));
    }
}
