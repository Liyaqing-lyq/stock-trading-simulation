package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实派发、回执与持久动作检验认知工具，另核对写入失败的可见边界。 */
class WorldRuntimeBeliefDecisionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void liveToolsProduceReadLearningAndTheNextActualDecisionInput() throws Exception {
        Path path = directory().resolve("world.journal");
        try (var world = WorldWebScenario.openBelief(path).runtime()) {
            world.beginDecisionSession("session");
            act(world, "bob", "publish", RoleTradingView.TOOL_FORECAST_PUBLISH,
                    new WorldRoleSession.ForecastDraft("forecast", "document", "预测", "至少一单",
                            "本人预测", 1, WorldWebScenario.COMPANY, BeliefModel.Comparator.AT_LEAST, 1, 100_000));
            assertEquals(1, world.beliefAudit("bob").orElseThrow().sourceClaims().size());
            act(world, "alice", "read", RoleTradingView.TOOL_READING_START,
                    java.util.Map.of("docId", "document", "version", 1));
            world.advanceTo(world.snapshot().timeMillis() + 2);
            var ref = world.roleView("alice").observations().get(0).ref();
            act(world, "alice", "assert", RoleTradingView.TOOL_BELIEF_ASSERT,
                    new WorldRoleSession.BeliefDraft("belief", WorldWebScenario.COMPANY,
                            BeliefModel.Comparator.AT_LEAST, 1, 100_000, "forecast", List.of(ref), "采用已读预测"));
            assertEquals(1, world.beliefAudit("alice").orElseThrow().state().beliefs().size());
            act(world, "alice", "report", RoleTradingView.TOOL_COMPANY_REPORT_ORDERS,
                    new WorldRoleSession.ReportDraft(WorldWebScenario.COMPANY, 100_000));
            world.advanceTo(100_001);
            var audit = world.beliefAudit("alice").orElseThrow();
            assertTrue(audit.state().evaluations().isEmpty(), "已发布但未读不能学习");
            act(world, "alice", "verify", RoleTradingView.TOOL_READING_START,
                    java.util.Map.of("docId", audit.factClaims().get(0).docId(), "version", 1));
            world.advanceTo(world.snapshot().timeMillis() + 2);
            assertEquals(BeliefModel.Verdict.MISS,
                    BeliefModel.verdict(world.beliefAudit("alice").orElseThrow().state(), "belief"));
            world.openDecisions("next-input", List.of(request("alice", "next")));
            String input = decision(world, "next").inputText();
            assertTrue(input.contains("belief"));
            assertTrue(input.contains("MISS"));
            assertFalse(input.contains("confidencePermille"));
            assertFalse(input.contains("competencePermille"));
            assertFalse(input.contains("observedPosition"));
            Files.writeString(path.resolveSibling("next-input.txt"), input);
        }
        try (var restored = WorldWebScenario.openBelief(path).runtime()) {
            assertEquals(Files.readString(path.resolveSibling("next-input.txt")), decision(restored, "next").inputText());
            assertEquals(BeliefModel.Verdict.MISS,
                    BeliefModel.verdict(restored.beliefAudit("alice").orElseThrow().state(), "belief"));
        }
    }

    @Test
    void failedFactPublicationDoesNotExposeDocumentOrFact() throws Exception {
        Path path = directory().resolve("world.journal");
        WorldRuntime.Snapshot checkpoint;
        WorldRuntime.BeliefAudit before;
        var world = WorldWebScenario.openBelief(path).runtime();
        try {
            world.openRoleSession("alice").reportOrders("report", "report-task", 10,
                    new WorldRoleSession.ReportDraft(WorldWebScenario.COMPANY, 100));
            world.advanceTo(100);
            checkpoint = world.snapshot();
            before = world.beliefAudit("alice").orElseThrow();
            Thread.currentThread().interrupt();
            try { assertThrows(IOException.class, () -> world.advanceTo(101)); }
            finally { Thread.interrupted(); }
            assertTrue(world.isPaused());
            assertEquals(checkpoint, world.snapshot(), "写入失败不能发布半份公告");
            assertEquals(before, world.beliefAudit("alice").orElseThrow());
        } finally { world.close(); }
        try (var restored = WorldWebScenario.openBelief(path).runtime()) {
            assertEquals(checkpoint, restored.snapshot());
            assertEquals(before, restored.beliefAudit("alice").orElseThrow());
            restored.advanceTo(101);
            assertEquals(1, restored.beliefAudit("alice").orElseThrow().factClaims().size());
        }
    }

    private void act(WorldRuntime world, String person, String id, String tool, Object args) throws Exception {
        assertEquals("DECIDING:1", world.openDecisions("open-" + id, List.of(request(person, id))));
        var dispatched = world.dispatchLiveDecision("dispatch-" + id, id);
        assertEquals("DISPATCHED", dispatched.verdict());
        var call = dispatched.call();
        String body = json.writeValueAsString(java.util.Map.of("tool", tool, "args", args));
        var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                call.recoveryGeneration(), body, DecisionModel.Usage.unknownCost(1, 20, 5), "test:local");
        assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
        assertEquals(1, world.drainDecisionCallbacks());
        assertEquals(tool, decision(world, id).recordedTool(), world.decisionAudits().toString());
        world.advanceTo(DecisionModel.actionDueMillis(world.snapshot().decisions().orElseThrow().state(), id));
    }

    private static DecisionRun.Request request(String person, String id) {
        return new DecisionRun.Request(person, id, "wake",
                new DecisionModel.Route("research", "key-1", "deepseek-flash", 0, 20, 100_000, 128, 1));
    }

    private static DecisionModel.Decision decision(WorldRuntime world, String id) {
        return DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(), id).orElseThrow();
    }

    private static Path directory() throws IOException {
        Path root = Path.of("target", "belief-decision-tests");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "case-");
    }
}
