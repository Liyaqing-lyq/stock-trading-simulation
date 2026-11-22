package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.WorldRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MeasuredLengthAncestryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODE = "ENGINEERING_CUSTOMER_OPERATING";
    @TempDir Path root;

    @Test void twoNativeSegmentsRequireAncestorRawReceiptsSealAndChargedTruncation() throws Exception {
        var parent = Files.createDirectory(root.resolve("parent"));
        var opening = new MeasuredLengthProbeTest();
        opening.dir = parent;
        opening.exerciseHost(false);
        var child = Files.createDirectory(root.resolve("child"));
        var cfg = new AutonomousLifecycleProbe.Config(MODE, 1, 1, 3600000, 3600000, parent.toString(), "test");
        for (String name : List.of("world.journal", SceneRoutes.FILE_NAME, "system-prompt.txt", ProbeProviderEvidence.POLICY_FILE))
            Files.copy(parent.resolve(name), child.resolve(name));
        JSON.writeValue(child.resolve("config.json").toFile(), cfg);
        var current = new AtomicReference<WorldRuntime>();
        var policy = new MeasuredLengthPolicy(() -> current.get().snapshot().decisions().orElseThrow());
        var evidence = new ProbeProviderEvidence(child, true, policy);
        var local = new FrozenThinkingProviderTest();
        local.root = child;
        try (var routes = SceneRoutes.open(child); var host = new WorldWebHost(() -> {
            var w = AutonomousLifecycleProbe.openWorld(MODE, child.resolve("world.journal"));
            current.set(w);
            return new WorldWebHost.Opened(w, w.profile().manifest().runId());
        }, routes, (call, timeout) -> {
            try {
                var input = JSON.readTree(call.inputText());
                var answer = JSON.createObjectNode().put("tool", "role.wait");
                answer.putObject("decision").put("objective", input.at("/engineering/deliberation/objectives/0/id").asText())
                        .put("basis", "检查本人现有资料").put("reviewAfterMillis", 3600000);
                return local.send(call, FrozenThinkingProviderTest.response("stop", answer.toString(), 13),
                        200, new AtomicReference<>(), evidence).completion();
            } catch (Exception e) { throw new IllegalStateException(e); }
        })) {
            var status = host.liveStatus();
            policy.restore(parent);
            host.startLiveRound(Long.parseLong(status.position()), routes.view().revision(), List.of("dave"));
            long until = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < until && current.get().snapshot().decisions().orElseThrow().state().decisions().stream()
                    .noneMatch(d -> d.personPartyId().equals("dave") && d.lastAttempt().settledDone())) Thread.sleep(10);
            assertEquals(3, current.get().snapshot().decisions().orElseThrow().state().runUsage().attempts());
        }
        writeResult(child, cfg, 1, evidence);
        MeasuredLengthEvidence.seal(child, parent);
        var reopened = root.resolve("read-only-replay.journal");
        Files.copy(child.resolve("world.journal"), reopened);
        try (var world = AutonomousLifecycleProbe.openWorld(MODE, reopened)) {
            var ledger = world.snapshot().decisions().orElseThrow();
            assertEquals(3,ledger.state().decisions().size());
            var failed = ledger.state().decisions().stream().filter(d -> "PROVIDER_LENGTH".equals(d.lastAttempt().reason())).findFirst().orElseThrow();
            var restored = new MeasuredLengthPolicy(() -> ledger);
            restored.restore(child);
            assertTrue(restored.confirmBefore("new-actor", 0));
            assertFalse(restored.confirmBefore(failed.actionId(), 0));

            Path parentSeal = parent.resolve(MeasuredLengthEvidence.FILE), childSeal = child.resolve(MeasuredLengthEvidence.FILE);
            byte[] ps = Files.readAllBytes(parentSeal), cs = Files.readAllBytes(childSeal);
            Files.delete(parentSeal);
            assertThrows(java.io.IOException.class, () -> new MeasuredLengthPolicy(() -> ledger).restore(child));
            Files.write(parentSeal, ps);
            int n = matches(parent, 1, failed.actionId()) ? 1 : 2;
            var meta = parent.resolve("provider-" + n + ".json");
            byte[] originalMeta = Files.readAllBytes(meta);
            var changed=(ObjectNode)JSON.readTree(originalMeta);changed.put("requestId","other-valid-identity");
            JSON.writeValue(meta.toFile(),changed);
            assertThrows(IllegalArgumentException.class, () -> new MeasuredLengthPolicy(() -> ledger).restore(child));
            Files.delete(parentSeal);
            MeasuredLengthEvidence.seal(parent,null);
            assertThrows(IllegalArgumentException.class, () -> new MeasuredLengthPolicy(() -> ledger).restore(child));
            Files.write(meta, originalMeta);Files.write(parentSeal, ps);

            var raw = parent.resolve("reply-" + n + ".json");
            byte[] originalReply=Files.readAllBytes(raw);
            var shortUse=(ObjectNode)JSON.readTree(originalReply);
            ((ObjectNode)shortUse.at("/reply/usage")).put("outputTokens",3);
            JSON.writeValue(raw.toFile(),shortUse);
            Files.delete(parentSeal);MeasuredLengthEvidence.seal(parent,null);
            Files.delete(childSeal);MeasuredLengthEvidence.seal(child,parent);
            assertThrows(IllegalArgumentException.class, () -> new MeasuredLengthPolicy(() -> ledger).restore(child));
            Files.write(raw,originalReply);Files.write(parentSeal,ps);Files.write(childSeal,cs);

            var orphan=Files.createDirectory(root.resolve("orphan"));
            for(String name:List.of("world.journal",SceneRoutes.FILE_NAME,"system-prompt.txt",ProbeProviderEvidence.POLICY_FILE))
                Files.copy(child.resolve(name),orphan.resolve(name));
            var unsupportedRoot=new AutonomousLifecycleProbe.Config(MODE,1,1,3600000,3600000,null,"test");
            JSON.writeValue(orphan.resolve("config.json").toFile(),unsupportedRoot);
            writeResult(orphan,unsupportedRoot,0,new ProbeProviderEvidence(orphan,true));
            MeasuredLengthEvidence.seal(orphan,null);
            assertThrows(IllegalArgumentException.class, () -> new MeasuredLengthPolicy(() -> ledger).restore(orphan));
        }
    }

    private static boolean matches(Path parent, int index, String action) throws Exception {
        return action.equals(JSON.readValue(parent.resolve("call-"+index+".json").toFile(),DecisionRun.Call.class).actionId());
    }

    private static void writeResult(Path dir, AutonomousLifecycleProbe.Config config, int calls, ProbeProviderEvidence evidence) throws Exception {
        var result=JSON.createObjectNode().put("httpCalls",calls);
        result.set("providerProvenance",evidence.summary(calls,0));result.set("config",JSON.valueToTree(config));
        result.putObject("live").put("physicalInFlight",0);
        JSON.writeValue(dir.resolve("result.json").toFile(),result);
    }
}
