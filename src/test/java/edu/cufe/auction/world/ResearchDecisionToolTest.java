package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.composed.ComposedResearchScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 研发模型回执的实际预算、权限、时间与恢复；没有外部 API 调用。 */
class ResearchDecisionToolTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void managerRegistersAndStartsWithActualCashAndRecovery() throws Exception {
        Path journal = dir.resolve("research.journal");
        long finish;
        try (WorldRuntime world = ComposedResearchScenario.openDebug(journal)) {
            assertFalse(world.openRoleSession("dave").view().tools().contains(ResearchDecisionTools.ACT));
            ResearchCommercialWorldTest.prepared(world);
            DecisionRun.Call call = open(world, "register");
            assertTrue(call.allowedTools().contains(ResearchDecisionTools.ACT));
            assertTrue(call.inputText().contains("researchOperations"));
            assertFalse(call.inputText().contains("successProbability"));
            assertFalse(call.inputText().contains("rollSeed"));
            assertFalse(call.inputText().contains("approvalRef"));
            long registered = reply(world, call, "REGISTER", "p1", "", "");
            world.advanceTo(registered);
            assertNotNull(world.researchRun().orElseThrow().state().projects().get("p1"));
            assertEquals(new BigDecimal("5000.00"), world.snapshot().accounts().get("ENG-A").cash());
            finish = reply(world, open(world, "begin"), "BEGIN", "p1", "RESEARCH", "");
            world.advanceTo(finish - 500);
            assertEquals(new BigDecimal("5000.00"), world.snapshot().accounts().get("ENG-A").cash());
        }
        try (WorldRuntime world = ComposedResearchScenario.openDebug(journal)) {
            world.advanceTo(finish);
            assertEquals(new BigDecimal("4800.00"), world.snapshot().accounts().get("ENG-A").cash());
            assertEquals(ResearchModel.StageStatus.IN_PROGRESS,
                    world.researchRun().orElseThrow().state().projects().get("p1").stages().get(0).status());
            world.advanceTo(finish + 86_400_000L);
            var call = open(world, "after-result");
            assertFalse(call.inputText().contains("evidenceRef"), "模型观察不得泄露尚未阅读的系统结果");
        }
        try (WorldRuntime world = ComposedResearchScenario.openDebug(journal)) {
            assertEquals(new BigDecimal("4800.00"), world.snapshot().accounts().get("ENG-A").cash());
        }
    }

    @Test
    void oldScenarioAndOtherManagerDoNotGainResearchTools() throws Exception {
        try (WorldRuntime world = ComposedResearchScenario.openFunded(dir.resolve("old.journal"))) {
            ResearchCommercialWorldTest.prepared(world);
            assertFalse(world.openRoleSession("dave").view().tools().contains(ResearchDecisionTools.ACT));
        }
        try (WorldRuntime world = ComposedResearchScenario.openDebug(dir.resolve("other.journal"))) {
            edu.cufe.auction.world.composed.ComposedWorldSelfTest.appoint(world, 10, "cufe", "erin", "manager", "material");
            assertFalse(world.openRoleSession("erin").view().tools().contains(ResearchDecisionTools.ACT));
            assertFalse(ManufacturingDecisionToolTest.open(world, "wrong-company").inputText().contains("researchOperations"));
        }
    }

    @Test
    void inventedApprovalCannotMakeRevenueOrResearchAssets() throws Exception {
        try (WorldRuntime world = ComposedResearchScenario.openDebug(dir.resolve("approval.journal"))) {
            ResearchCommercialWorldTest.prepared(world);
            var before = world.researchRun().orElseThrow();
            var cash = world.snapshot().accounts();
            world.advanceTo(reply(world, open(world, "fake-approval"), "APPROVE", "p1", "", "not-a-meeting"));
            assertEquals(before, world.researchRun().orElseThrow());
            assertEquals(cash, world.snapshot().accounts());
            assertTrue(world.taskResultForTest(DecisionRun.actionTaskId("fake-approval"))
                    .contains(BoardModel.REASON_UNKNOWN_MEETING));
        }
    }

    @Test
    void revocationBetweenBeginAndFinishRejectsModelRegistration() throws Exception {
        Path file = dir.resolve("revoked.journal");
        try (WorldRuntime world = ComposedResearchScenario.openDebug(file)) {
            ResearchCommercialWorldTest.prepared(world);
            String appointment = world.openRoleSession("dave").appointments().get("eng-a");
            long finish = reply(world, open(world, "revoke"), "REGISTER", "p1", "", "");
            world.advanceTo(finish - 500);
            world.scheduleRevokeAppointment("revoke", "revoke-task", finish - 100, appointment);
            world.advanceTo(finish);
            assertFalse(world.researchRun().orElseThrow().state().projects().containsKey("p1"));
        }
        try (WorldRuntime world = ComposedResearchScenario.openDebug(file)) {
            assertFalse(world.openRoleSession("dave").view().tools().contains(ResearchDecisionTools.ACT));
        }
    }

    @Test
    void modelActionReservesFocusUntilItsFinish() throws Exception {
        try (WorldRuntime world = ComposedResearchScenario.openDebug(dir.resolve("focus.journal"))) {
            ResearchCommercialWorldTest.prepared(world);
            long finish = reply(world, open(world, "focus"), "REGISTER", "p1", "", "");
            world.advanceTo(finish - 500);
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    world.openRoleSession("dave").registerResearch("overlap", "overlap-task", finish + 100,
                            "eng-a", "p2", 500));
            assertTrue(failure.getMessage().contains("工时") || failure.getMessage().contains("焦点"), failure.getMessage());
        }
    }

    @Test
    void independentProcessesPreserveInputOrderAndBranchIsolation() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (int step = 0; step < 3; step++) {
            Path log = dir.resolve("research-process-" + step + ".log");
            Process process = new ProcessBuilder(executable, "-Xmx768m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                    "-cp", cp, ResearchDecisionReopenProbe.class.getName(), dir.toString(), Integer.toString(step))
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean done = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) process.destroyForcibly();
            assertTrue(done);
            assertEquals(0, process.exitValue(), java.nio.file.Files.readString(log));
            assertTrue(java.nio.file.Files.readString(log).contains("RESEARCH_DECISION_JVM_OK"));
        }
    }

    static DecisionRun.Call open(WorldRuntime world, String id) throws Exception {
        world.beginDecisionSession("session-" + id);
        world.openDecisions("open-" + id, List.of(new DecisionRun.Request("dave", id, "wake",
                new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20, 1_000_000, 1_024, 1))));
        return world.dispatchLiveDecision("dispatch-" + id, id).call();
    }

    static long reply(WorldRuntime world, DecisionRun.Call call, String action, String project, String stage, String meeting) throws Exception {
        String body = JSON.writeValueAsString(Map.of("tool", ResearchDecisionTools.ACT, "args", Map.of(
                "action", action, "projectId", project, "stage", stage, "meetingId", meeting)));
        var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                call.recoveryGeneration(), body, DecisionModel.Usage.unknownCost(1, 60, 20), "scripted:research");
        assertEquals("QUEUED", world.offerDecisionCallback(reply));
        assertEquals(1, world.drainDecisionCallbacks());
        var ref = world.snapshot().decisions().orElseThrow().actionRef(call.actionId());
        assertNotNull(ref);
        return ref.dueMillis() + ResearchDecisionTools.ACTION_MILLIS;
    }
}
