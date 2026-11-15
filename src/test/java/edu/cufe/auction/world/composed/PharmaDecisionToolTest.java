package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 独立公司治理与经营均经真实模型回执；本测试不调用外部模型。 */
class PharmaDecisionToolTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void newDirectorsSeeOnlyTheirCompanyAndCanConveneThroughDecision() throws Exception {
        Path file = dir.resolve("director.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(file)) {
            var call = open(world, "frank", "convene");
            assertTrue(call.allowedTools().contains(RoleTradingView.TOOL_BOARD_CONVENE));
            var input = world.engineeringRoleInput("frank");
            assertEquals("pharma", input.path("directorships").get(0).path("companyPartyId").asText());
            assertEquals(1, input.path("directorships").size());
            assertTrue(input.path("managerships").isEmpty());
            long due = reply(world, call, RoleTradingView.TOOL_BOARD_CONVENE, Map.of(
                    "companyPartyId", "pharma", "meetingId", "model-appointment",
                    "topic", "MANAGER_APPOINTMENT", "materialDeadline", 20_000,
                    "opensAt", 30_000, "closesAt", 60_000));
            world.advanceTo(due);
            assertTrue(world.boardOfCompany("pharma").orElseThrow().board().meetings().containsKey("model-appointment"));
            assertTrue(world.boardOfCompany("north").orElseThrow().board().meetings().isEmpty());
        }
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(file)) {
            assertTrue(world.boardOfCompany("pharma").orElseThrow().board().meetings().containsKey("model-appointment"));
        }
    }

    @Test void oldProfileDoesNotGainNewCompanyDecisionInputs() throws Exception {
        try (WorldRuntime world = ComposedPharmaScenario.open(dir.resolve("old.journal"))) {
            assertFalse(world.openRoleSession("frank").view().tools().contains(RoleTradingView.TOOL_BOARD_CONVENE));
            assertTrue(world.engineeringRoleInput("frank").path("directorships").isEmpty());
        }
    }

    @Test void foreignChairCannotConveneAnotherCompany() throws Exception {
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(dir.resolve("foreign.journal"))) {
            long due = reply(world, open(world, "frank", "foreign"), RoleTradingView.TOOL_BOARD_CONVENE,
                    Map.of("companyPartyId", "north", "meetingId", "foreign-meeting",
                            "topic", "MANAGER_APPOINTMENT", "materialDeadline", 20_000,
                            "opensAt", 30_000, "closesAt", 60_000));
            world.advanceTo(due);
            assertTrue(world.boardOfCompany("north").orElseThrow().board().meetings().isEmpty());
            assertTrue(world.boardOfCompany("pharma").orElseThrow().board().meetings().isEmpty());
        }
    }

    @Test void managerResearchDecisionDebitsOnlyIndependentCash() throws Exception {
        Path file = dir.resolve("research.journal");
        long finish;
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(file)) {
            ComposedPharmaWorldTest.grantBudgetAndAppointManager(world);
            var call = open(world, "ivan", "register");
            assertTrue(call.allowedTools().contains(ResearchDecisionTools.ACT));
            assertFalse(call.inputText().contains("rollSeed"));
            world.advanceTo(reply(world, call, ResearchDecisionTools.ACT,
                    Map.of("action", "REGISTER", "projectId", "p1", "stage", "", "meetingId", "")));
            finish = reply(world, open(world, "ivan", "start"), ResearchDecisionTools.ACT,
                    Map.of("action", "BEGIN", "projectId", "p1", "stage", "RESEARCH", "meetingId", ""));
            world.advanceTo(finish - 500);
        }
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(file)) {
            world.advanceTo(finish + 86_400_000);
            assertEquals(new java.math.BigDecimal("1800.00"), world.snapshot().accounts().get("RND-PHARMA").cash());
            assertEquals(new java.math.BigDecimal("5000.00"), world.snapshot().accounts().get("ENG-A").cash());
            assertEquals(ResearchModel.StageStatus.COMPLETED,
                    world.researchRun().orElseThrow().state().projects().get("p1").stages().get(0).status());
            assertTrue(ComposedCompanyReport.of(world, "pharma").balanced());
        }
    }

    @Test void commercializationDecisionRequiresReadEvidenceAndRealVotes() throws Exception {
        Path file = dir.resolve("commercial.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(file)) {
            ComposedPharmaWorldTest.grantBudgetAndAppointManager(world);
            long at = ComposedPharmaWorldTest.completeP1(world);
            List<String> voters = List.of("frank", "grace", "heidi", "ivan");
            world.advanceTo(reply(world, open(world, "frank", "commercial-convene"), RoleTradingView.TOOL_BOARD_CONVENE,
                    Map.of("companyPartyId", "pharma", "meetingId", "model-commercial",
                            "topic", "COMMERCIALIZATION_APPROVAL", "materialDeadline", at + 60_000,
                            "opensAt", at + 70_000, "closesAt", at + 80_000)));
            long start = world.snapshot().timeMillis();
            for (int i = 0; i < voters.size(); i++) world.scheduleEngineeringAttend("att-" + i, "att-task-" + i,
                    start + 100 + i, "pharma", voters.get(i), "model-commercial", false);
            for (int i = 0; i < 3; i++) world.scheduleEngineeringMaterial("mat-" + i, "mat-task-" + i,
                    start + 200 + i, "pharma", "frank", "model-commercial", "m" + i,
                    ComposedPharmaWorldTest.evidenceDoc(i), 1);
            world.advanceTo(start + 300);
            var evidence = java.util.stream.IntStream.range(0, 3).mapToObj(i ->
                    new BoardModel.MaterialRef("m" + i, ComposedPharmaWorldTest.evidenceDoc(i), 1, at)).toList();
            Map<String, ?> proposal = Map.of("companyPartyId", "pharma", "meetingId", "model-commercial",
                    "action", "PROPOSE", "projectId", "p1", "evidence", evidence);
            world.advanceTo(reply(world, open(world, "frank", "unread"), ResearchDecisionTools.BOARD, proposal));
            assertNull(world.boardOfCompany("pharma").orElseThrow().board().meeting("model-commercial").commercializationProposal());
            ComposedPharmaWorldTest.readEvidence(world, world.snapshot().timeMillis(), voters);
            world.advanceTo(reply(world, open(world, "frank", "propose"), ResearchDecisionTools.BOARD, proposal));
            assertNotNull(world.boardOfCompany("pharma").orElseThrow().board().meeting("model-commercial").commercializationProposal());
            for (int i = 0; i < voters.size(); i++) world.scheduleEngineeringVote("vote-" + i, "vote-task-" + i,
                    at + 72_000 + i, "pharma", voters.get(i), "model-commercial", BoardModel.Vote.YES, 1_000);
            world.advanceTo(at + 80_001);
            world.advanceTo(reply(world, open(world, "frank", "implement"), ResearchDecisionTools.BOARD,
                    Map.of("companyPartyId", "pharma", "meetingId", "model-commercial",
                            "action", "IMPLEMENT", "projectId", "p1", "evidence", List.of())));
            world.advanceTo(reply(world, open(world, "ivan", "approve"), ResearchDecisionTools.ACT,
                    Map.of("action", "APPROVE", "projectId", "p1", "stage", "", "meetingId", "model-commercial")));
            assertEquals(ResearchModel.ProjectStatus.APPROVED,
                    world.researchRun().orElseThrow().state().projects().get("p1").status());
            assertEquals(new java.math.BigDecimal("1100.00"), world.snapshot().accounts().get("RND-PHARMA").cash());
        }
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(file)) {
            assertEquals(ResearchModel.ProjectStatus.APPROVED,
                    world.researchRun().orElseThrow().state().projects().get("p1").status());
            assertTrue(ComposedCompanyReport.of(world, "pharma").balanced());
        }
    }

    @Test void nonDirectorManagerCanReadOwnResearchResultWithoutBoardMembership() throws Exception {
        Path file = dir.resolve("non-director.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(file)) {
            ComposedPharmaWorldTest.grantBudgetAndAppointManager(world, "kate");
            assertNull(world.boardOfCompany("pharma").orElseThrow().board().charter().seatOf("kate"));
            world.advanceTo(reply(world, open(world, "kate", "register"), ResearchDecisionTools.ACT,
                    Map.of("action", "REGISTER", "projectId", "p1", "stage", "", "meetingId", "")));
            long finish = reply(world, open(world, "kate", "start"), ResearchDecisionTools.ACT,
                    Map.of("action", "BEGIN", "projectId", "p1", "stage", "RESEARCH", "meetingId", ""));
            world.advanceTo(finish + 86_400_000);
            long start = world.snapshot().timeMillis() + 1_000;
            world.scheduleStartReading("kate-read", "kate-read-task", start, "kate", ComposedPharmaWorldTest.evidenceDoc(0), 1);
            world.advanceTo(start + 2_000);
            var info = world.snapshot().information().orElseThrow().information();
            assertTrue(InformationModel.canCite(info, "kate", ComposedPharmaWorldTest.evidenceDoc(0), 1, start + 2_000));
            assertFalse(InformationModel.canCite(info, "alice", ComposedPharmaWorldTest.evidenceDoc(0), 1, start + 2_000));
            long observations = info.observations().size();
            world.scheduleStartReading("foreign-read", "foreign-read-task", start + 2_100, "alice", ComposedPharmaWorldTest.evidenceDoc(0), 1);
            world.scheduleStartReading("kate-reread", "kate-reread-task", start + 2_100, "kate", ComposedPharmaWorldTest.evidenceDoc(0), 1);
            world.scheduleRevokeAppointment("revoke-kate", "revoke-kate-task", start + 2_500, "mgr-kate");
            world.advanceTo(start + 4_000);
            assertEquals(observations, world.snapshot().information().orElseThrow().information().observations().size(),
                    "外人和阅读途中撤权者都不能新增完成经历；此前已读经历仍保留");
        }
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(file)) {
            assertTrue(InformationModel.canCite(world.snapshot().information().orElseThrow().information(),
                    "kate", ComposedPharmaWorldTest.evidenceDoc(0), 1, world.snapshot().timeMillis()));
        }
    }

    static DecisionRun.Call open(WorldRuntime world, String person, String id) throws Exception {
        world.beginDecisionSession("session-" + id);
        world.openDecisions("open-" + id, List.of(new DecisionRun.Request(person, id, "wake",
                new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20, 1_000_000, 1_024, 1))));
        var dispatch = world.dispatchLiveDecision("dispatch-" + id, id);
        assertNotNull(dispatch.call(), dispatch.toString());
        return dispatch.call();
    }

    static long reply(WorldRuntime world, DecisionRun.Call call, String tool, Map<String, ?> args) throws Exception {
        String body = JSON.writeValueAsString(Map.of("tool", tool, "args", args));
        var response = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                call.recoveryGeneration(), body, DecisionModel.Usage.unknownCost(1, 60, 20), "scripted:pharma");
        assertEquals("QUEUED", world.offerDecisionCallback(response));
        assertEquals(1, world.drainDecisionCallbacks());
        var action = world.snapshot().decisions().orElseThrow().actionRef(call.actionId());
        assertNotNull(action);
        return action.dueMillis() + 1_000L;
    }
}
