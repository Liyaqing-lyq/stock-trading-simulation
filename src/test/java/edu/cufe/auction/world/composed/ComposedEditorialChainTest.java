package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static edu.cufe.auction.world.RoleTradingView.*;
import static org.junit.jupiter.api.Assertions.*;

/** 同一十公司世界中的受控采编回执，引用、岗位、组织工时与阅读均由运行层实际结算。 */
class ComposedEditorialChainTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode office(JsonNode input) {
        for (var goal : input.at("/engineering/deliberation/objectives"))
            if (goal.has("minimumIndependentSources")) return goal;
        return JSON.missingNode();
    }

    private void act(WorldRuntime world, String person, String tool, Map<String, ?> args) throws Exception {
        var call = PharmaDecisionToolTest.open(world, person, person + "-" + tool);
        var input = JSON.readTree(call.inputText());
        var body = JSON.createObjectNode().put("tool", tool);
        body.set("args", JSON.valueToTree(args));
        body.putObject("decision").put("objective", office(input).required("id").asText())
                .put("basis", "受控验证本岗位采编流程").put("reviewAfterMillis", 60_000);
        var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                call.attemptNo(), call.recoveryGeneration(), body.toString(),
                DecisionModel.Usage.unknownCost(1, 60, 20), "fixture:editorial");
        assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
        assertEquals(1, world.drainDecisionCallbacks());
        var action = world.snapshot().decisions().orElseThrow().actionRef(call.actionId());
        assertNotNull(action, tool);
        world.advanceTo(action.dueMillis());
        var outcome = world.snapshot().media().orElseThrow().reports().get(action.taskId());
        assertTrue(outcome.accepted(), outcome.reason());
    }

    private JsonNode read(WorldRuntime world, String person, String document) throws Exception {
        long at = world.snapshot().timeMillis() + 1;
        var session = world.openRoleSession(person);
        session.startReading("read-" + at, "task-read-" + at, at, document, 1);
        world.advanceTo(at);
        var started = world.roleView(person).readings().stream()
                .filter(r -> r.ref().docId().equals(document)).findFirst().orElseThrow();
        world.advanceTo(started.dueAt());
        for (JsonNode item : world.autonomyRoleObservation(person).at("/profile/readExperiences"))
            if (document.equals(item.path("docId").asText())) return item.required("lastReadRef");
        throw new AssertionError("读完后缺少引用 " + document);
    }

    private String publish(WorldRuntime world, String author, String editor, String otherEditor,
                           String template, List<String> sources, String title, String text) throws Exception {
        var goal = office(world.autonomyRoleObservation(author));
        assertEquals(sources.size(), goal.path("minimumIndependentSources").required(template).asInt());
        List<JsonNode> refs = new ArrayList<>();
        for (String source : sources) refs.add(read(world, author, source));
        act(world, author, TOOL_MEDIA_PROPOSE, Map.of("storyId", "one", "template", template, "topic", title));
        var story = world.autonomyRoleObservation(author).required("media").required(0);
        assertTrue(story.path("own").asBoolean());
        String id = story.required("storyId").asText();
        act(world, author, TOOL_MEDIA_SUBMIT, Map.of("storyId", id, "expectedRevision", story.required("revisionNo").asInt(),
                "title", title, "body", text, "publicSourceDescription", "据文中所引的公开资料", "evidence", refs));
        var draft = world.autonomyRoleObservation(editor).required("media").required(0);
        assertTrue(draft.path("canReview").asBoolean());
        assertFalse(world.autonomyRoleObservation(otherEditor).toString().contains(draft.required("draftDocId").asText()));
        read(world, editor, draft.required("draftDocId").asText());
        act(world, editor, TOOL_MEDIA_REVIEW, Map.of("storyId", id, "expectedRevision", draft.required("revisionNo").asInt(),
                "approve", true, "note", ""));
        var approved = world.autonomyRoleObservation(editor).required("media").required(0);
        assertTrue(approved.path("canPublish").asBoolean());
        act(world, editor, TOOL_MEDIA_PUBLISH, Map.of("storyId", id, "expectedRevision", approved.required("revisionNo").asInt()));
        return world.autonomyRoleObservation(editor).required("media").required(0).required("publicDocId").asText();
    }

    @Test void twoEditorialTeamsAndSelectiveReadingSurviveReopen() throws Exception {
        var file = directory.resolve("two-outlets.journal");
        String wire;
        String investigation;
        try (var world = ComposedIndustryScenario.openContinuingRolesOperating(file)) {
            assertTrue(office(world.autonomyRoleObservation("investor")).isMissingNode());
            wire = publish(world, "rita", "ed", "bea", "WIRE", List.of("engineering-opportunity:A1"),
                    "工程机会快讯", "客户列出了 A1 的条款，是否签约还要查后续记录。");
            investigation = publish(world, "sam", "bea", "ed", "INVESTIGATIVE",
                    List.of(wire, CompanyOpeningDocuments.documentId("eng-a")),
                    "工程机会与董事会权限", "快讯所引客户报价与公司章程各有出处，二者都不能证明合同已生效。");
            var media = world.snapshot().media().orElseThrow();
            assertEquals(2, media.stateOf("investigative").stories().get(0).sourceGroups());
            assertTrue(media.budgetOf("wire").spentMillis() > 0 && media.budgetOf("investigative").spentMillis() > 0);
            read(world, "investor", investigation);
        }
        try (var reopened = ComposedIndustryScenario.openContinuingRolesOperating(file);
             var host = new WorldWebHost(() -> new WorldWebHost.Opened(reopened, reopened.profile().manifest().runId()))) {
            var information = reopened.snapshot().information().orElseThrow().information();
            long now = reopened.snapshot().timeMillis();
            assertTrue(InformationModel.canCite(information, "investor", investigation, 1, now));
            assertFalse(InformationModel.canCite(information, "investor", wire, 1, now));
            assertFalse(InformationModel.canCite(information, "dave", investigation, 1, now));
            assertEquals(List.of(investigation, wire), host.groupPage("investor").feed().stream()
                    .filter(entry -> entry.kind().equals("MEDIA_STORY"))
                    .map(WorldWebHost.GroupFeedView::docId).toList());
        }
    }
}
