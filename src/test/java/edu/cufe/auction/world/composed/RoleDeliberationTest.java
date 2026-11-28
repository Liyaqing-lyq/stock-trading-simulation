package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 解释合同与实际业务接线；注入回执仅作协议验证，不冒充真实模型行为。 */
class RoleDeliberationTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();

    private String reply(String tool, Map<String, ?> args, String objective, String basis, long review) throws Exception {
        var node = JSON.createObjectNode().put("tool", tool);
        if (args != null) node.set("args", JSON.valueToTree(args));
        node.putObject("decision").put("objective", objective).put("basis", basis).put("nextReviewAt", review);
        return node.toString();
    }

    private DecisionModel.Decision submit(WorldRuntime world, DecisionRun.Call call, String body) throws Exception {
        assertEquals("QUEUED", world.offerDecisionCallback(new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, 500, 80), "TEST:deliberation")));
        world.drainDecisionCallbacks();
        return world.snapshot().decisions().orElseThrow().state().decisions().stream()
                .filter(d -> d.actionId().equals(call.actionId())).findFirst().orElseThrow();
    }

    @Test void newVersionStatesObjectivesAndActualMeetingWindowsWithoutGrantingRights() throws Exception {
        try (var world = ComposedIndustryScenario.openDeliberativeAutonomous(dir.resolve("input.journal"))) {
            var input = world.autonomyRoleObservation("mfgtech-d2");
            assertEquals("deliberation-v2", input.path("engineering").path("roleContextVersion").asText());
            assertEquals("director:mfg-tech", input.path("engineering").path("deliberation").path("objectives").get(0).path("id").asText());
            assertFalse(input.path("engineering").path("directorships").get(0).path("chair").asBoolean());
            assertTrue(input.path("engineering").path("managerships").isEmpty());
            assertTrue(input.path("profile").path("catalog").toString().contains("role-evidence:mfg-tech:1"));
            assertFalse(input.toString().contains("EXT-CUST-AUTO"));
        }
    }

    @Test void explainedWaitIsRetainedAndReplayedExactly() throws Exception {
        Path journal = dir.resolve("wait.journal");
        String content = reply("role.wait", null, "director:mfg-tech", "尚未读取候选资料，暂不作任命判断。", 60_001);
        try (var world = ComposedIndustryScenario.openDeliberativeAutonomous(journal)) {
            var decision = submit(world, PharmaDecisionToolTest.open(world, "mfgtech-d1", "wait"), content);
            assertEquals(DecisionModel.Outcome.WAIT, decision.outcome());
            assertEquals(content, decision.attempts().get(0).replyBody());
        }
        try (var world = ComposedIndustryScenario.openDeliberativeAutonomous(journal)) {
            var input = world.autonomyRoleObservation("mfgtech-d1");
            assertTrue(input.path("engineering").path("deliberation").path("recentDecisions").toString().contains("尚未读取候选资料"));
        }
    }

    @Test void oldVersionStillRejectsNewShellAndNewVersionRejectsFabricatedObjective() throws Exception {
        try (var world = ComposedIndustryScenario.openEvidenceAutonomous(dir.resolve("old.journal"), false)) {
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, submit(world,
                    PharmaDecisionToolTest.open(world, "mfgtech-d1", "old"),
                    reply("role.wait", null, "director:mfg-tech", "等待", 60_001)).outcome());
        }
        try (var world = ComposedIndustryScenario.openDeliberativeAutonomous(dir.resolve("invalid.journal"))) {
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, submit(world,
                    PharmaDecisionToolTest.open(world, "mfgtech-d1", "invalid"),
                    reply("role.wait", null, "director:pharma", "我是另一公司的董事", 60_001)).outcome());
        }
    }

    @Test void explainedConveneStillExecutesAndShowsArrivalDeadline() throws Exception {
        try (var world = ComposedIndustryScenario.openDeliberativeAutonomous(dir.resolve("action.journal"))) {
            var call = PharmaDecisionToolTest.open(world, "mfgtech-d1", "convene");
            assertEquals(DecisionModel.Outcome.INTENT, submit(world, call,
                    reply("board.convene", Map.of("companyPartyId", "mfg-tech", "meetingId", "m1",
                                    "topic", "MANAGER_APPOINTMENT", "materialDeadline", 100_000,
                                    "opensAt", 120_000, "closesAt", 180_000),
                            "director:mfg-tech", "提出任命讨论，董事仍各自决定是否批准。", 60_001)).outcome());
            world.advanceTo(3_001);
            assertTrue(world.boardOfCompany("mfg-tech").orElseThrow().board().meetings().containsKey("m1"));
            var meeting = world.autonomyRoleObservation("mfgtech-d2").path("engineering").path("directorships").get(0).path("meetings").get(0);
            assertEquals("BEFORE_OPEN", meeting.path("window").asText());
            assertEquals(120_000, meeting.path("attendanceDeadlineExclusive").asLong());
            assertFalse(meeting.path("proposalPresent").asBoolean());
            world.advanceTo(120_000);
            assertEquals("VOTING", world.autonomyRoleObservation("mfgtech-d2").path("engineering").path("directorships").get(0).path("meetings").get(0).path("window").asText());
        }
    }
}
