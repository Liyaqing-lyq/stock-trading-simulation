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
        var outcome = result(world, person, tool, args);
        assertTrue(outcome.accepted(), outcome.reason());
    }

    private MediaRun.Report result(WorldRuntime world, String person, String tool, Map<String, ?> args) throws Exception {
        String id = person + "-" + tool + "-" + world.snapshot().timeMillis();
        world.beginDecisionSession("session-" + id);
        var route = new DecisionModel.Route("media", "key-1", "deepseek-flash", 1, 60, 1_000_000, 1024, 1, "lifecycle-v1", null);
        world.openDecisions("open-" + id, List.of(new DecisionRun.Request(person, id, "wake", route)));
        var dispatch = world.dispatchLiveDecision("dispatch-" + id, id);
        var call = dispatch.call();
        assertNotNull(call, dispatch.toString());
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
        return world.snapshot().media().orElseThrow().reports().get(action.taskId());
    }

    private JsonNode read(WorldRuntime world, String person, String document) throws Exception {
        return read(world, person, document, 1);
    }

    private JsonNode read(WorldRuntime world, String person, String document, long version) throws Exception {
        long at = world.snapshot().timeMillis() + 1;
        var session = world.openRoleSession(person);
        session.startReading("read-" + at, "task-read-" + at, at, document, version);
        world.advanceTo(at);
        var started = world.roleView(person).readings().stream()
                .filter(r -> r.ref().docId().equals(document)).findFirst().orElseThrow();
        world.advanceTo(started.dueAt());
        for (JsonNode item : world.autonomyRoleObservation(person).at("/profile/readExperiences"))
            if (document.equals(item.path("docId").asText()) && item.path("version").asLong() == version) return item.required("lastReadRef");
        throw new AssertionError("读完后缺少引用 " + document);
    }

    private String publish(WorldRuntime world, String author, String editor, String otherEditor,
                           String template, List<String> sources, String title, String text) throws Exception {
        var goal = office(world.autonomyRoleObservation(author));
        assertEquals(sources.size(), goal.path("minimumIndependentSources").required(template).asInt());
        List<JsonNode> refs = new ArrayList<>();
        for (String source : sources) refs.add(read(world, author, source));
        act(world, author, TOOL_MEDIA_PROPOSE, Map.of("storyId", "one", "template", template, "topic", title));
        var story = story(world, author, template);
        assertTrue(story.path("own").asBoolean());
        String id = story.required("storyId").asText();
        act(world, author, TOOL_MEDIA_SUBMIT, Map.of("storyId", id, "expectedRevision", story.required("revisionNo").asInt(),
                "title", title, "body", text, "publicSourceDescription", "据文中所引的公开资料", "evidence", refs));
        var draft = story(world, editor, template);
        assertTrue(draft.path("canReview").asBoolean());
        assertFalse(world.autonomyRoleObservation(otherEditor).toString().contains(draft.required("draftDocId").asText()));
        read(world, editor, draft.required("draftDocId").asText());
        act(world, editor, TOOL_MEDIA_REVIEW, Map.of("storyId", id, "expectedRevision", draft.required("revisionNo").asInt(),
                "approve", true, "note", ""));
        var approved = story(world, editor, template);
        assertTrue(approved.path("canPublish").asBoolean());
        act(world, editor, TOOL_MEDIA_PUBLISH, Map.of("storyId", id, "expectedRevision", approved.required("revisionNo").asInt()));
        return story(world, editor, template).required("publicDocId").asText();
    }

    private JsonNode story(WorldRuntime world, String person, String template) throws Exception {
        for (var item : world.autonomyRoleObservation(person).required("media"))
            if (template.equals(item.path("template").asText())) return item;
        throw new AssertionError("未见该模板的稿件 " + template);
    }

    @Test void eightDesksKeepEvidenceEditorialAndReaderBoundariesAcrossCorrections() throws Exception {
        var file = directory.resolve("eight-desks.journal");
        String original = "engineering-opportunity:A1", second = CompanyOpeningDocuments.documentId("eng-a");
        String wire, aggregate, markets;
        try (var world = ComposedIndustryScenario.openMediaDesksOperating(file)) {
            var media = world.snapshot().media().orElseThrow();
            assertEquals(8, media.config().policies().size());
            assertEquals(61, world.profile().registry().parties().stream().filter(p -> p.kind() == OpeningRegistry.PartyKind.PERSON
                    && !p.partyId().equals(edu.cufe.auction.web.WorldWebScenario.RESEARCH_SYSTEM_PUBLISHER)).count());
            var roles = world.snapshot().roles().orElseThrow().access().appointments();
            var audience = new java.util.HashSet<String>();
            var templates = java.util.EnumSet.noneOf(MediaModel.TemplateKind.class);
            for (var policy : media.config().policies()) {
                var team = roles.stream().filter(a -> a.organizationPartyId().equals(policy.mediaOrganizationPartyId())).toList();
                assertEquals(2, team.size());
                assertNotEquals(team.get(0).personPartyId(), team.get(1).personPartyId());
                var goal = office(world.autonomyRoleObservation(team.get(0).personPartyId()));
                assertTrue(audience.add(goal.required("intendedAudience").asText()));
                assertEquals(1, policy.enabledTemplates().size());
                templates.addAll(policy.enabledTemplates().keySet());
            }
            assertEquals(java.util.EnumSet.allOf(MediaModel.TemplateKind.class), templates);
            var wrongDesk = result(world, "nina", TOOL_MEDIA_PROPOSE,
                    Map.of("storyId", "other", "template", "WIRE", "topic", "快讯"));
            assertFalse(wrongDesk.accepted());
            assertEquals(MediaModel.REASON_TEMPLATE_NOT_ENABLED, wrongDesk.reason());
            wire = publish(world, "rita", "ed", "bea", "WIRE", List.of(original), "客户开价", "本期报价，尚未签约。");
            aggregate = publish(world, "nina", "omar", "ed", "AGGREGATOR", List.of(wire), "报价摘要", "原始报道记录的是报价。");
            var singleton = List.of(original);
            var pair = List.of(original, second);
            markets = publish(world, "investor", "kate", "ed", "MARKETS", singleton, "证券报道", "公告口径。");
            publish(world, "mfgtech-mgr", "mfgstruct-mgr", "ed", "VERTICAL", pair, "样本口径", "报价源与章程来源不同。");
            publish(world, "hydro-manager", "resource-manager", "ed", "LOCAL", singleton, "项目进展", "尚待本地核对。");
            publish(world, "digital-manager", "aged-manager", "ed", "CROSS_BORDER", pair, "口径对照", "尚未得到境外客户的确认。");
            publish(world, "mfgversa-mgr", "erin", "ed", "INDEPENDENT", singleton, "作者观察", "我目前只看到客户报价。");
            publish(world, "sam", "bea", "ed", "INVESTIGATIVE", pair, "合同调查", "一次报价不能证明履约。");
            for (var state : world.snapshot().media().orElseThrow().states()) {
                assertEquals(MediaModel.Status.PUBLISHED, state.stories().get(0).status());
                assertTrue(world.snapshot().media().orElseThrow().budgetOf(state.policy().mediaOrganizationPartyId()).spentMillis() > 0);
            }
            read(world, "dave", aggregate);
            read(world, "carol", markets);
            String aggregateId = story(world, "nina", "AGGREGATOR").required("storyId").asText();
            act(world, "nina", TOOL_MEDIA_CORRECTION, Map.of("storyId", aggregateId, "expectedRevision", 1));
            var raw = result(world, "nina", TOOL_MEDIA_SUBMIT, Map.of("storyId", aggregateId, "expectedRevision", 1,
                    "title", "自行报道", "body", "只收到原始报价。", "publicSourceDescription", "客户报价",
                    "evidence", List.of(read(world, "nina", original))));
            assertFalse(raw.accepted());
            assertEquals("AGGREGATOR_REQUIRES_PUBLISHED_STORY", raw.reason());
            var wanted = story(world, "sam", "INVESTIGATIVE");
            String id = wanted.required("storyId").asText();
            act(world, "sam", TOOL_MEDIA_CORRECTION, Map.of("storyId", id, "expectedRevision", 1));
            var copied = List.of(read(world, "sam", wire), read(world, "sam", aggregate));
            var rejected = result(world, "sam", TOOL_MEDIA_SUBMIT, Map.of("storyId", id, "expectedRevision", 1,
                    "title", "两个转载", "body", "尝试交叉核对", "publicSourceDescription", "两篇转述", "evidence", copied));
            assertFalse(rejected.accepted());
            assertEquals(MediaModel.REASON_INSUFFICIENT_SOURCES, rejected.reason());
            act(world, "sam", TOOL_MEDIA_SUBMIT, Map.of("storyId", id, "expectedRevision", 1,
                    "title", "更正合同口径", "body", "报价期限需按客户条件解释。", "publicSourceDescription", "两项来源",
                    "evidence", List.of(read(world, "sam", aggregate), read(world, "sam", second))));
            var draft = story(world, "bea", "INVESTIGATIVE");
            read(world, "bea", draft.required("draftDocId").asText(), draft.required("draftVersion").asLong());
            act(world, "bea", TOOL_MEDIA_REVIEW, Map.of("storyId", id, "expectedRevision", 2, "approve", true, "note", ""));
            act(world, "bea", TOOL_MEDIA_PUBLISH, Map.of("storyId", id, "expectedRevision", 2));
            assertEquals(2, story(world, "bea", "INVESTIGATIVE").required("publicVersion").asLong());
        }
        try (var world = ComposedIndustryScenario.openMediaDesksOperating(file);
             var host = new WorldWebHost(() -> new WorldWebHost.Opened(world, world.profile().manifest().runId()))) {
            var info = world.snapshot().information().orElseThrow().information();
            long now = world.snapshot().timeMillis();
            assertTrue(InformationModel.canCite(info, "dave", aggregate, 1, now));
            assertFalse(InformationModel.canCite(info, "dave", wire, 1, now));
            assertFalse(InformationModel.canCite(info, "carol", aggregate, 1, now));
            assertTrue(InformationModel.canCite(info, "carol", markets, 1, now));
            assertFalse(InformationModel.canCite(info, "dave", markets, 1, now));
            var directory = host.groupPage("dave").feed();
            assertTrue(directory.stream().anyMatch(item -> item.docId().equals(markets)));
            assertEquals(2, world.snapshot().media().orElseThrow().stateOf("investigative").stories().get(0).publicVersion());
        }
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
