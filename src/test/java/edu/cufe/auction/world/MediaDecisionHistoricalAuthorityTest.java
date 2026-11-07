package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 离任只影响新的工作权限，不应使既有合法决定无法重放。 */
class MediaDecisionHistoricalAuthorityTest {
    @Test void completedDecisionRemainsRecoverableAfterReporterLeaves() throws Exception {
        Path root = Path.of("target", "media-historical-authority");
        Files.createDirectories(root);
        Path journal = Files.createTempDirectory(root, "case-").resolve("world.journal");
        try (var world = MediaRuntimeBridgeTest.openForProbe(journal, 10000)) {
            world.beginDecisionSession("session");
            assertEquals("DECIDING:1", world.openDecisions("open-proposal",
                    List.of(new DecisionRun.Request("rita", "proposal", "wake",
                            new DecisionModel.Route("research", "key-1", "deepseek-flash", 0, 20,
                                    100000, 128, 1)))));
            var dispatched = world.dispatchLiveDecision("dispatch-proposal", "proposal");
            assertEquals("DISPATCHED", dispatched.verdict());
            var call = dispatched.call();
            String body = new ObjectMapper().writeValueAsString(Map.of("tool", RoleTradingView.TOOL_MEDIA_PROPOSE,
                    "args", Map.of("storyId", "one", "template", "WIRE", "topic", "simulated")));
            var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                    call.attemptNo(), call.recoveryGeneration(), body,
                    DecisionModel.Usage.unknownCost(1, 20, 5), "test:local");
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
            assertEquals(1, world.drainDecisionCallbacks());
            long due = DecisionModel.actionDueMillis(world.snapshot().decisions().orElseThrow().state(), "proposal");
            world.advanceTo(due);
            assertEquals(1, world.snapshot().media().orElseThrow().stateOf("media-a").stories().size());
            world.scheduleRevokeAppointment("leave", "leave-task", due + 1, "M-1");
            world.advanceTo(due + 1);
        }
        try (var world = MediaRuntimeBridgeTest.openForProbe(journal, 10000)) {
            assertEquals(1, world.snapshot().media().orElseThrow().stateOf("media-a").stories().size());
            assertEquals(300, world.snapshot().media().orElseThrow().budgetOf("media-a").spentMillis());
        }
    }
}
