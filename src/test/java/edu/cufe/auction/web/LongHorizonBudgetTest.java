package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LongHorizonBudgetTest {
    @TempDir Path dir;
    private static final String MODE = "TEN_COMPANY_AUTONOMY";
    private static final String POLICY = DecisionModel.Route.LONG_HORIZON;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void onePersonsMeteredDecisionSurvivesRouteAndArchiveReopen() throws Exception {
        Path journal = dir.resolve("ten.journal");
        DecisionModel.Route frozen;
        try (var routes = SceneRoutes.open(dir)) {
            routes.replace(0, AutonomousLifecycleProbe.probeRoutes(routes.view(), MODE));
        }
        try (var routes = SceneRoutes.open(dir); var world = ComposedIndustryScenario.openTenCompanyAutonomy(journal)) {
            var route = routes.view().routes().stream().filter(r -> r.scene() == SceneRoutes.Scene.TRADING).findFirst().orElseThrow();
            var cap = world.snapshot().decisions().orElseThrow().state().config().budget();
            assertEquals(POLICY, world.profile().manifest().dataVersions().get("model-budget"));
            assertEquals(Integer.MAX_VALUE, cap.runCap().attempts());
            assertEquals(61, cap.personCap().size());
            assertEquals(cap.runCap(), cap.personCap().get("eng-a-customer-representative"));
            assertTrue(cap.personCap().values().stream().allMatch(cap.runCap()::equals));
            assertFalse(cap.personCap().containsKey(WorldWebScenario.RESEARCH_SYSTEM_PUBLISHER));
            assertEquals(9_007_199_254_740_991L, route.maxTotalTokens());
            assertEquals(8192, route.maxOutputTokens());
            assertEquals(4, route.maxConcurrency());
            frozen = new DecisionModel.Route(route.scene().id(), route.slotId(), route.model(), routes.view().revision(),
                    route.maxCalls(),route.maxTotalTokens(),route.maxOutputTokens(),route.maxConcurrency(),route.budgetPolicy(),route.providerPolicy());
            world.beginDecisionSession("long-start");
            world.openDecisions("long-open", List.of(new DecisionRun.Request("investor","review","wake",frozen)));
            var call = world.dispatchLiveDecision("long-review","review").call();
            assertEquals(frozen, call.plan().route());
            var input = JSON.readTree(call.inputText());
            var body = JSON.createObjectNode().put("tool","role.wait");
            body.putObject("decision").put("objective",input.at("/engineering/deliberation/objectives/0/id").asText())
                    .put("basis","等待下次复查").put("reviewAfterMillis",60000);
            var usage = DecisionModel.Usage.unknownCost(1,1234,56);
            assertEquals("QUEUED",world.offerDecisionCallback(new DecisionModel.Reply(call.runId(),call.actionId(),
                    call.attemptId(),call.attemptNo(),call.recoveryGeneration(),body.toString(),usage,"fixture")));
            assertEquals(1,world.drainDecisionCallbacks());
            world.advanceTo(world.snapshot().timeMillis()+1100);
            assertEquals(usage, world.snapshot().decisions().orElseThrow().state().runUsage());
        }
        var bytes = Files.readAllBytes(journal);
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(journal)) {
            var state = world.snapshot().decisions().orElseThrow().state();
            assertEquals(DecisionModel.Usage.unknownCost(1,1234,56),state.runUsage());
            assertEquals(state.runUsage(),state.personUsage().get("investor"));
            assertEquals(frozen,DecisionModel.decision(state,"review").orElseThrow().plan().route());
        }
        assertArrayEquals(bytes,Files.readAllBytes(journal));
    }

    @Test void legacyArchivesKeepTheirCapsAndExplicitSmallerAndPerBatchLimitsStillApply() throws Exception {
        var route = new DecisionModel.Route("trading","key-1","deepseek-flash",1,2,4_000_000_000L,128,1,POLICY);
        try (var w = ComposedIndustryScenario.openContinuingRolesOperating(dir.resolve("old.journal"))) {
            assertEquals(3000,w.snapshot().decisions().orElseThrow().state().config().budget().runCap().attempts());
            assertEquals(300,w.snapshot().decisions().orElseThrow().state().config().budget().personCap().get("investor").attempts());
            w.beginDecisionSession("old-session");
            long pos = w.snapshot().position();
            assertThrows(IllegalArgumentException.class,()->w.openDecisions("wrong",List.of(new DecisionRun.Request("investor","x","wake",route))));
            assertEquals(pos,w.snapshot().position());
            var old = new DecisionModel.Route("trading","key-1","deepseek-flash",1,2,1_000_000,2048,1,"lifecycle-v1");
            w.openDecisions("legacy",List.of(new DecisionRun.Request("investor","y","wake",old)));
            assertEquals(old,w.dispatchLiveDecision("legacy-dispatch","y").call().plan().route());
        }
        assertEquals(2,route.maxCalls());
        assertEquals(4_000_000_000L,JSON.readValue(JSON.writeValueAsBytes(route),DecisionModel.Route.class).maxTotalTokens());
        assertThrows(IllegalArgumentException.class,()->new SceneRoutes.Route(SceneRoutes.Scene.TRADING,"key-1",
                "deepseek-flash",true,2,DecisionModel.Route.tokenCeiling(POLICY)+1,128,1,POLICY));
        assertThrows(IllegalArgumentException.class,()->new AutonomousLifecycleProbe.Config(MODE,3001,1,3600000,3600000,null,"test"));
    }
}
