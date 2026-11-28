package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoardProcedureViewTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void noMeetingAndSingleMentionKeepTheirOriginalShape() throws Exception {
        for (String data : new String[]{"{}", "{\"directorships\":[]}",
                "{\"directorships\":[{\"procedure\":\"唯一条文\",\"meetings\":[]}]}",
                "{\"directorships\":[{\"procedure\":\"甲\",\"meetings\":[{\"procedure\":\"乙\",\"planningPrerequisites\":{\"completionWindow\":\"明天\"}}]}]}"}) {
            ObjectNode input = JSON.createObjectNode();
            input.set("engineering", JSON.readTree(data));
            JsonNode before = input.deepCopy();
            BoardProcedureView.project(input);
            assertEquals(before, input);
        }
    }

    @Test void differentDirectorsRetainTheirOwnRulesAndUnrelatedText() throws Exception {
        ObjectNode input = (ObjectNode) JSON.readTree("""
                {"engineering":{"directorships":[
                {"procedure":"甲","meetings":[{"procedure":"甲","material":{"procedure":"甲"},"planningPrerequisites":{"completionWindow":"到会前","deadline":10}}]},
                {"procedure":"乙","meetings":[{"procedure":"乙","planningPrerequisites":{"completionWindow":"到会前","deadline":20}},
                {"procedure":"仅此一次","planningPrerequisites":{"completionWindow":"仅此截止"}}]}]},"profile":{"procedure":"甲"}}
                """);
        JsonNode before = input.deepCopy();
        BoardProcedureView.project(input);
        var rules = input.at("/engineering/boardRules");
        assertEquals(3, rules.size());
        assertEquals("甲", input.at("/engineering/directorships/0/meetings/0/material/procedure").textValue());
        assertEquals("仅此一次", input.at("/engineering/directorships/1/meetings/1/procedure").textValue());
        assertEquals("仅此截止", input.at("/engineering/directorships/1/meetings/1/planningPrerequisites/completionWindow").textValue());
        assertEquals("甲", input.at("/profile/procedure").textValue());
        assertEquals(before, expand(input.deepCopy()));
        JsonNode projected = input.deepCopy();
        BoardProcedureView.project(input);
        assertEquals(projected, input);
    }

    @Test void freshProfileProjectsTheSameTermsAtObservationDispatchAndStrictReopen(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var previous = new ArrayList<JsonNode>();
        for (boolean shared : new boolean[]{false, true}) {
            var path = dir.resolve(shared ? "new.journal" : "prior.journal");
            String chair = "mfgtech-d1";
            String dispatched;
            try (var w = openProfile(path, shared)) {
                assertEquals(shared, BoardProcedureView.VERSION.equals(
                        w.profile().manifest().dataVersions().get("board-procedure-view")));
                long start = w.snapshot().timeMillis();
                for (int i = 0; i <= 4; i++) {
                    if (i > 0) {
                        long due = start + i * 2000L;
                        w.scheduleCompanyBoardConvene("convene-" + i, "convene-task-" + i, due,
                                "mfg-tech", chair, "meeting-" + i, due + 20000L,
                                due + 30000L, due + 40000L, i % 2 == 0);
                        w.advanceTo(due);
                    }
                    var own = (ObjectNode)w.autonomyRoleObservation(chair);
                    assertEquals(i, own.at("/engineering/directorships/0/meetings").size());
                    assertEquals(shared && i > 0, own.at("/engineering/boardRules").isObject());
                    if (!shared) previous.add(own);
                    else assertEquals(previous.get(i), expand(own.deepCopy()));
                    assertTrue(w.autonomyRoleObservation("investor").at("/engineering/boardRules").isMissingNode());
                }
                w.beginDecisionSession("rule-session");
                w.openDecisions("rule-open", java.util.List.of(new DecisionRun.Request(chair, "rule-call", "wake",
                        new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20, 1_000_000, 1024, 1))));
                var call = w.dispatchLiveDecision("rule-dispatch", "rule-call").call();
                assertNotNull(call);
                dispatched = call.inputText();
                assertEquals(shared, JSON.readTree(dispatched).at("/engineering/boardRules").isObject());
                String body = "{\"tool\":\"role.wait\",\"decision\":{\"objective\":\"board:mfg-tech\","
                        + "\"basis\":\"离线夹具记录输入\",\"reviewAfterMillis\":1000}}";
                assertEquals("QUEUED", w.offerDecisionCallback(new DecisionModel.Reply(call.runId(), call.actionId(),
                        call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                        DecisionModel.Usage.unknownCost(1, 20, 10), "scripted:board-procedure-test")));
                assertEquals(1, w.drainDecisionCallbacks());
            }
            byte[] history = java.nio.file.Files.readAllBytes(path);
            try (var w = openProfile(path, shared)) {
                var decisions = w.snapshot().decisions().orElseThrow().state().decisions();
                assertEquals(1, decisions.size());
                assertEquals(dispatched, decisions.get(0).inputText());
            }
            assertArrayEquals(history, java.nio.file.Files.readAllBytes(path));
        }
    }

    private static WorldRuntime openProfile(java.nio.file.Path journal, boolean shared) throws Exception {
        return shared ? ComposedIndustryScenario.openBoardProcedureOperating(journal)
                : ComposedIndustryScenario.openAgedOrderOperating(journal);
    }


    private static ObjectNode expand(ObjectNode input) {
        var nodes = new ArrayList<JsonNode>();
        for (var director : input.at("/engineering/directorships")) {
            nodes.add(director);
            for (var meeting : director.path("meetings")) {
                nodes.add(meeting);
                nodes.add(meeting.path("planningPrerequisites"));
            }
        }
        for (var node : nodes) {
            for (var field : new String[]{"procedure", "completionWindow"}) {
                JsonNode pointer = node.path(field).path("$ref");
                if (!pointer.isTextual()) continue;
                assertTrue(pointer.textValue().startsWith("#/engineering/boardRules/"));
                JsonNode restored = input.at(pointer.textValue().substring(1));
                assertTrue(restored.isTextual());
                ((ObjectNode) node).set(field, restored);
            }
        }
        ((ObjectNode)input.path("engineering")).remove("boardRules");
        return input;
    }
}
