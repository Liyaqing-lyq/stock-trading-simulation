package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PersonalLibrarySearchTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL = "role.library.search";
    private static final String PERSON = "investor";

    private static long search(WorldRuntime world, String id, String query) throws Exception {
        var call = PharmaDecisionToolTest.open(world, PERSON, id);
        var input = JSON.readTree(call.inputText());
        assertTrue(call.allowedTools().contains(TOOL));
        var reply = JSON.createObjectNode().put("tool", TOOL);
        reply.putObject("args").put("query", query);
        reply.putObject("decision").put("objective", input.at("/engineering/deliberation/objectives/0/id").asText())
                .put("basis", "核对客户是否付款，找本人有权查的往来资料。").put("reviewAfterMillis", 60000);
        assertEquals("QUEUED", world.offerDecisionCallback(new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), reply.toString(),
                DecisionModel.Usage.unknownCost(1, 50, 30), "fixture")));
        assertEquals(1, world.drainDecisionCallbacks());
        var ref = world.snapshot().decisions().orElseThrow().actionRef(call.actionId());
        assertNotNull(ref);
        assertEquals(input.at("/engineering/planning/actions/role.library.search/earliestCompletionMillis").longValue(),
                ref.dueMillis());
        assertTrue(ref.dueMillis() > world.snapshot().timeMillis());
        return ref.dueMillis();
    }

    private static JsonNode results(WorldRuntime world, String person) throws Exception {
        return world.autonomyRoleObservation(person).at("/engineering/librarySearch/lastResult");
    }

    private static void publish(WorldRuntime world, String id, InformationModel.Audience audience,
                                String title, String body) throws Exception {
        long at = world.snapshot().timeMillis() + 1;
        world.schedulePublishDocument("publish-" + id, "task-" + id, at, id, "rita", audience,
                title, body, "往来档案", 500, List.of());
        world.advanceTo(at);
    }

    @Test void timedSearchIndexesOnlyVisibleTitlesAndActualRecalledTextAndSurvivesReopen() throws Exception {
        Path file = dir.resolve("search.journal");
        long due;
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(file)) {
            publish(world, "receipt-visible", InformationModel.Audience.direct(PERSON), "A1 到账往来", "凭证编号 JZ672");
            publish(world, "receipt-private", InformationModel.Audience.direct("nina"), "A1 到账往来", "凭证编号 JZ672");
            due = search(world, "title", "A1 到账");
            assertTrue(results(world, PERSON).isMissingNode());
            world.advanceTo(due - 1);
            assertTrue(results(world, PERSON).isMissingNode());
        }
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(file)) {
            world.advanceTo(due);
            var result = results(world, PERSON);
            assertEquals(1, result.required("matches").size());
            assertEquals("receipt-visible", result.at("/matches/0/docId").asText());
            assertEquals("TITLE", result.at("/matches/0/matchedIn").asText());
            assertFalse(result.toString().contains("JZ672"));
            assertTrue(results(world, "nina").isMissingNode());
            assertFalse(InformationModel.canCite(world.snapshot().information().orElseThrow().information(),
                    PERSON, "receipt-visible", 1, due));
            world.advanceTo(search(world, "before-read", "JZ672"));
            assertEquals(0, results(world, PERSON).required("matches").size());
            long start = world.snapshot().timeMillis()+1;
            world.openRoleSession(PERSON).startReading("read", "read-task", start, "receipt-visible", 1);
            world.advanceTo(start);
            world.advanceTo(world.roleView(PERSON).readings().get(0).dueAt());
            world.advanceTo(search(world, "after-read", "jz672"));
            assertEquals("OWN_READING", results(world, PERSON).at("/matches/0/matchedIn").asText());
        }
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(file)) {
            assertEquals("receipt-visible", results(world, PERSON).at("/matches/0/docId").asText());
            assertEquals(1, results(world, PERSON).required("matches").size());
        }
        try (var world = ComposedIndustryScenario.openContinuingRolesOperating(dir.resolve("older.journal"))) {
            assertFalse(world.openRoleSession(PERSON).view().tools().contains(TOOL));
            assertTrue(world.autonomyRoleObservation(PERSON).at("/engineering/librarySearch").isMissingNode());
        }
    }
}
