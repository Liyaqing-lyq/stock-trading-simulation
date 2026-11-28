package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.ResearchModel;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ResearchManagerProjectViewTest {
    private static final String COMPANY = ComposedPharmaScenario.PHARMA;
    private static final String MANAGER = ComposedPharmaScenario.MANAGER_CANDIDATE;
    private static final long DAY = 86_400_000L;
    @TempDir Path dir;

    private static JsonNode researchView(WorldRuntime world, String person) throws Exception {
        return world.autonomyRoleObservation(person).path("engineering").path("researchOperations");
    }

    private static JsonNode registeredProject(WorldRuntime world) throws Exception {
        JsonNode projects = researchView(world, MANAGER).path("registeredProjects");
        assertEquals(1, projects.size(), "经理的决定观察必须区分已立项与可选方案");
        return projects.get(0);
    }

    private static void register(WorldRuntime world) throws Exception {
        ComposedPharmaWorldTest.grantBudgetAndAppointManager(world, MANAGER);
        world.openRoleSession(MANAGER).registerResearch("register-p1", "register-p1-task",
                14_000L, COMPANY, "p1", 1_000L);
        world.advanceTo(14_000L);
        assertTrue(world.researchRun().orElseThrow().state().projects().containsKey("p1"));
    }

    @Test void observesRegisteredCommitmentAndActualStagePaymentsAcrossCloseAndReplay() throws Exception {
        Path file = dir.resolve("research-manager.journal");
        long begin = 16_000L;
        long due = begin + 45L * DAY;
        JsonNode saved;
        String durableInput;
        try (var world = ComposedIndustryScenario.openPersonalMarketOperating(file)) {
            assertTrue(researchView(world, MANAGER).isMissingNode());
            register(world);
            assertTrue(researchView(world, "frank").isMissingNode());
            assertTrue(researchView(world, "mfgtech-mgr").isMissingNode());
            JsonNode registered = registeredProject(world);
            assertEquals("p1", registered.path("projectId").asText());
            assertEquals(14_000L, registered.path("registeredAt").asLong());
            assertEquals("900.00", registered.path("committedBudget").asText());
            assertEquals("900.00", registered.path("remainingCommitment").asText());
            assertEquals("0.00", registered.path("paid").asText());
            assertEquals("RESEARCH", registered.path("currentStage").asText());
            assertEquals("PLANNED", registered.path("stageStatus").asText());
            assertFalse(registered.has("nextResultAt"));

            world.openRoleSession(MANAGER).beginResearch("research", "research-task", begin, COMPANY, "p1", ResearchModel.Stage.RESEARCH, 1_000L);
            world.advanceTo(begin);
            JsonNode started = registeredProject(world);
            assertEquals("200.00", started.path("paid").asText());
            assertEquals("700.00", started.path("remainingCommitment").asText());
            assertEquals("IN_PROGRESS", started.path("stageStatus").asText());
            assertEquals(due, started.path("nextResultAt").asLong());
            assertFalse(started.has("evidenceDocId"));
            assertEquals(new BigDecimal("1800.00"), world.snapshot().accounts().get("RND-PHARMA").cash());
            assertEquals(new BigDecimal("700.00"), world.snapshot().accounts().get("RESEARCH-CONTRACTOR").cash());
            world.advanceTo(30L * DAY + 1);
            assertEquals(started, registeredProject(world), "关期不重置立项时的承诺");
            world.advanceTo(due);
            var result = world.researchRun().orElseThrow().state().projects().get("p1").stage(ResearchModel.Stage.RESEARCH);
            assertEquals(ResearchModel.StageStatus.COMPLETED, result.status());
            assertEquals("RESULT_RECORDED", registeredProject(world).path("stageStatus").asText());
            assertFalse(registeredProject(world).has("evidenceDocId"));
            String doc = result.evidence().reference();
            world.scheduleStartReading("read-result", "read-result-task", due + 1, MANAGER, doc, 1);
            world.advanceTo(due + 1_001);
            JsonNode known = registeredProject(world);
            assertEquals("COMPLETED", known.path("stageStatus").asText());
            assertEquals(doc, known.path("evidenceDocId").asText());
            assertEquals("DEVELOPMENT", known.path("nextStage").asText());
            long dev = due + 3_000;
            world.openRoleSession(MANAGER).beginResearch("development", "development-task", dev, COMPANY, "p1",
                    ResearchModel.Stage.DEVELOPMENT, 1_000L);
            world.advanceTo(dev);
            saved = registeredProject(world);
            assertEquals("DEVELOPMENT", saved.path("currentStage").asText());
            assertEquals("500.00", saved.path("paid").asText());
            assertEquals("400.00", saved.path("remainingCommitment").asText());
            assertEquals(dev + 180L * DAY, saved.path("nextResultAt").asLong());
            assertFalse(saved.has("evidenceDocId"));
            assertFalse(saved.toString().contains("seed"));
            var call = PharmaDecisionToolTest.open(world, MANAGER, "observe-registration");
            assertTrue(call.allowedTools().contains("research.act"));
            durableInput = call.inputText();
            var observed = new ObjectMapper().readTree(durableInput).path("engineering").path("researchOperations");
            assertEquals(saved, observed.path("registeredProjects").get(0));
        }
        try (var reopened = ComposedIndustryScenario.openPersonalMarketOperating(file)) {
            assertEquals(saved, registeredProject(reopened));
            var original = reopened.snapshot().decisions().orElseThrow().state().decisions().stream()
                    .filter(d -> d.actionId().equals("observe-registration")).findFirst().orElseThrow();
            assertEquals(durableInput, original.inputText());
            assertEquals(new BigDecimal("1500.00"), reopened.snapshot().accounts().get("RND-PHARMA").cash());
            assertEquals(new BigDecimal("1000.00"), reopened.snapshot().accounts().get("RESEARCH-CONTRACTOR").cash());
        }
    }

    @Test void historicalFundedWorldReplaysOriginalResearchProjection() throws Exception {
        Path file = dir.resolve("funded.journal");
        JsonNode original;
        try (var world = ComposedIndustryScenario.openFundedThinkingOperating(file)) {
            register(world);
            original = researchView(world, MANAGER);
            assertTrue(original.has("projects"));
            assertFalse(original.has("registeredProjects"));
        }
        try (var reopened = ComposedIndustryScenario.openFundedThinkingOperating(file)) {
            assertEquals(original, researchView(reopened, MANAGER));
        }
    }
}
