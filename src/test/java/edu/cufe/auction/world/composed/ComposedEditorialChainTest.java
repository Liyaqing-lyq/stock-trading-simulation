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
        var decision = choose(world, person, tool, args);
        var action = world.snapshot().decisions().orElseThrow().actionRef(decision.actionId());
        assertNotNull(action, tool);
        world.advanceTo(action.dueMillis());
        return world.snapshot().media().orElseThrow().reports().get(action.taskId());
    }

    private DecisionModel.Decision choose(WorldRuntime world, String person, String tool, Map<String, ?> args) throws Exception {
        String id = person + "-" + tool + "-" + world.snapshot().decisions().orElseThrow().state().decisions().size();
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
        return world.snapshot().decisions().orElseThrow().state().decisions().stream()
                .filter(d -> d.actionId().equals(call.actionId())).findFirst().orElseThrow();
    }

    @Test void confirmedMediaWorkChangesOnlyTheActorsFeedbackOnTheNewContract() throws Exception {
        Path file = directory.resolve("business-receipts.journal");
        String storyId;
        JsonNode saved;
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            var proposed = result(world,"investor",TOOL_MEDIA_PROPOSE,Map.of("storyId","cash-review","template","MARKETS","topic","合同与现金"));
            assertTrue(proposed.accepted());
            storyId = proposed.storyId();
            var own = world.autonomyRoleObservation("investor").at("/engineering/deliberation/lastActionResult");
            assertEquals("APPLIED",own.required("execution").asText());
            assertEquals(proposed.timeMillis(),own.required("resultAt").asLong());
            assertTrue(own.required("accepted").asBoolean());
            assertEquals(storyId,own.required("storyId").asText());
            assertFalse(own.has("resultPosition"));
            assertFalse(own.has("resultTaskId"));
            assertTrue(world.autonomyRoleObservation("kate").at("/engineering/deliberation/lastActionResult").isMissingNode());
            var rejected = result(world,"investor",TOOL_MEDIA_SUBMIT,Map.of("storyId",storyId,"expectedRevision",0,
                    "title","合同与现金","body","尚无核验","publicSourceDescription","暂无来源","evidence",List.of()));
            assertFalse(rejected.accepted());
            saved = world.autonomyRoleObservation("investor").at("/engineering/deliberation");
            var last = saved.required("lastActionResult");
            assertEquals("REJECTED",last.required("execution").asText());
            assertFalse(last.required("accepted").asBoolean());
            assertEquals(rejected.reason(),last.required("businessReject").asText());
            assertFalse(last.has("storyId"));
            assertEquals("APPLIED",saved.at("/recentDecisions/1/execution").asText());
            assertTrue(world.autonomyRoleObservation("nina").at("/engineering/deliberation/recentDecisions").isEmpty());
        }
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            assertEquals(saved,w.autonomyRoleObservation("investor").at("/engineering/deliberation"));
        }
        assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(file));
        try (var w = ComposedIndustryScenario.openOriginalTenCompanyAutonomy(directory.resolve("legacy.journal"))) {
            var original = result(w,"investor",TOOL_MEDIA_PROPOSE,Map.of("storyId","prior","template","MARKETS","topic","原合同"));
            assertTrue(original.accepted());
            assertEquals("UNKNOWN",w.autonomyRoleObservation("investor").at("/engineering/deliberation/lastActionResult/execution").asText());
        }
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

    @Test void evidenceContractExplainsTheObservedMissingIndexWithoutRelaxingAdmission() throws Exception {
        for (boolean current : List.of(false, true)) {
            Path path = directory.resolve("submit-input-" + current + ".journal");
            try (var world = current ? ComposedIndustryScenario.openTenCompanyAutonomy(path)
                    : ComposedIndustryScenario.openOriginalTenCompanyAutonomy(path)) {
                JsonNode tool = null;
                for (var entry : world.autonomyRoleObservation("investor").required("tools"))
                    if (TOOL_MEDIA_SUBMIT.equals(entry.required("name").asText())) tool = entry;
                assertNotNull(tool);
                assertEquals(current, tool.has("arrayItems"));
                assertEquals(current, tool.has("evidenceSource"));
                if (current) {
                    assertEquals(JSON.readTree("{\"docId\":\"string\",\"version\":\"long\",\"index\":\"long\"}"),
                            tool.at("/arrayItems/evidence"));
                    assertTrue(tool.required("evidenceSource").asText().contains("lastReadRef"));
                }
                var docs = List.of("engineering-opportunity:A1", "engineering-opportunity:A2", "company-governance:eng-a:1");
                var actual = new ArrayList<JsonNode>();
                for (var doc : docs) actual.add(read(world, "investor", doc));
                String storyId = result(world, "investor", TOOL_MEDIA_PROPOSE,
                        Map.of("storyId", "cash-contract", "template", "MARKETS", "topic", "合同、票据与现金")).storyId();
                Map<String,Object> args = new java.util.LinkedHashMap<>();
                args.put("storyId", storyId);
                args.put("expectedRevision", 0);
                args.put("title", "合同与现金");
                args.put("body", "报价尚待交割。");
                args.put("publicSourceDescription", "两份报价和公司章程");
                // 固定为原始模型应答里三条真实的文档/版本及其顺序，均未携带 index。
                args.put("evidence", docs.stream().map(doc -> Map.of("docId", doc, "version", 1)).toList());
                var missing = choose(world, "investor", TOOL_MEDIA_SUBMIT, args);
                assertEquals("TECHNICAL_FAILED", missing.outcome().name());
                assertTrue(missing.lastAttempt().reason().startsWith("TOOL_ARGS_INVALID:"));
                var wrong = new ArrayList<>(actual);
                var fabricated = wrong.get(0).deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) fabricated).put("index", 999);
                wrong.set(0, fabricated);
                args.put("evidence", wrong);
                assertEquals(MediaModel.REASON_EVIDENCE_INDEX, result(world, "investor", TOOL_MEDIA_SUBMIT, args).reason());
                args.put("evidence", actual);
                args.put("aliases", List.of("CUFE-A"));
                var aliasFailure = choose(world, "investor", TOOL_MEDIA_SUBMIT, args);
                assertTrue(aliasFailure.lastAttempt().reason().startsWith("TOOL_ARGS_INVALID:"));
                args.remove("aliases");
                assertTrue(result(world, "investor", TOOL_MEDIA_SUBMIT, args).accepted());
            }
        }
    }

    @Test void formalVersionsAndRepostsKeepExactRootsAndDoNotGrantPrivateAccess() throws Exception {
        Path file = directory.resolve("media-origins.journal");
        String source = "engineering-opportunity:A1";
        String confidential = "sealed-editorial-source";
        String groupPost;
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            String article = publish(world, "investor", "kate", "ed", "MARKETS", List.of(source), "合同报价", "尚待交割。");
            read(world, "alice", source);
            read(world, "alice", article);
            var groups = world.groupsRun().config();
            var platform = world.groupsRun().platformConfig();
            var first = List.of(new WorldRoleSession.Citation(article, 1), new WorldRoleSession.Citation(source, 1));
            long due = world.snapshot().timeMillis() + platform.postMillis();
            assertEquals("SCHEDULED:p1", world.openRoleSession("alice").postToPlatform("p1", "p1", due,
                    new WorldRoleSession.PlatformPostDraft("转载报价", "报价尚未成交。", first)));
            world.advanceTo(due + platform.deliveryMillis());
            var post = world.groupsRun().publication().platform().posts().get(0);
            assertEquals(PlatformPostModel.PostStatus.PUBLISHED, post.status());
            assertEquals(2, post.declaredRefs().size());
            assertEquals(List.of(new PlatformPostModel.Ref(source, 1)), post.originRefs());
            read(world, "alice", post.docId());
            due = world.snapshot().timeMillis() + groups.postMillis();
            assertEquals("SCHEDULED:g1", world.openRoleSession("alice").postToGroup("g1", "g1", due,
                    new WorldRoleSession.GroupPostDraft(edu.cufe.auction.web.WorldWebScenario.GROUPS_OPEN,
                            "群内复查", "转发报价。", List.of(new WorldRoleSession.Citation(post.docId(), 1)))));
            world.advanceTo(due + groups.deliveryMillis());
            var group = world.groupsRun().state().posts().get(0);
            groupPost = group.docId();
            assertEquals(List.of(new GroupModel.Ref(source, 1)), group.originRefs());

            long now = world.snapshot().timeMillis();
            world.schedulePublishDocument("private", "private-task", now + 1, confidential, "carol",
                    InformationModel.Audience.direct("investor"), "单独交给记者", "内部尾款 137", "直接提供", 10L, List.of());
            world.advanceTo(now + 1);
            String id = story(world, "investor", "MARKETS").required("storyId").asText();
            act(world, "investor", TOOL_MEDIA_CORRECTION, Map.of("storyId", id, "expectedRevision", 1));
            act(world, "investor", TOOL_MEDIA_SUBMIT, Map.of("storyId", id, "expectedRevision", 1, "title", "更正价格",
                    "body", "先前金额已修订。", "publicSourceDescription", "另行核验", "evidence", List.of(read(world, "investor", confidential))));
            var draft = story(world, "kate", "MARKETS");
            read(world, "kate", draft.required("draftDocId").asText(), 2);
            act(world, "kate", TOOL_MEDIA_REVIEW, Map.of("storyId", id, "expectedRevision", 2, "approve", true, "note", ""));
            act(world, "kate", TOOL_MEDIA_PUBLISH, Map.of("storyId", id, "expectedRevision", 2));

            read(world, "alice", article, 2);
            due = world.snapshot().timeMillis() + platform.postMillis();
            assertEquals("NOT_CITABLE", world.openRoleSession("alice").postToPlatform("guess", "guess", due,
                    new WorldRoleSession.PlatformPostDraft("试引", "未取得原件。", List.of(new WorldRoleSession.Citation(confidential, 1)))));
            assertEquals("SCHEDULED:p2", world.openRoleSession("alice").correctPlatformPost("p2", "p2", due,
                    new WorldRoleSession.PlatformCorrectDraft(post.docId(), 1, "转载更正", "记者更正了金额。",
                            List.of(new WorldRoleSession.Citation(article, 2)))));
            world.advanceTo(due + platform.deliveryMillis());
            var corrected = world.groupsRun().publication().platform().posts().stream()
                    .filter(p -> p.version() == 2).findFirst().orElseThrow();
            assertEquals(List.of(new PlatformPostModel.Ref(article, 2)), corrected.declaredRefs());
            assertEquals(List.of(new PlatformPostModel.Ref(confidential, 1)), corrected.originRefs());
            read(world, "alice", post.docId(), 2);
            due = world.snapshot().timeMillis() + groups.postMillis();
            assertEquals("SCHEDULED:g2", world.openRoleSession("alice").correctGroupPost("g2", "g2", due,
                    new WorldRoleSession.GroupCorrectDraft(groupPost, 1, "两版记录", "保存初次报价和后续更正。",
                            List.of(new WorldRoleSession.Citation(article, 1), new WorldRoleSession.Citation(post.docId(), 2)))));
            world.advanceTo(due + groups.deliveryMillis());
            var version = world.groupsRun().state().postVersions().get(0);
            assertEquals(List.of(new GroupModel.Ref(source, 1), new GroupModel.Ref(confidential, 1)), version.originRefs());
            read(world, "bob", groupPost, 2);
            assertFalse(InformationModel.canCite(world.snapshot().information().orElseThrow().information(),
                    "bob", confidential, 1, world.snapshot().timeMillis()));
            assertFalse(world.autonomyRoleObservation("bob").toString().contains(confidential));
            assertFalse(world.autonomyRoleObservation("alice").toString().contains("内部尾款 137"));

            String forgery = "media-public:unbound";
            now = world.snapshot().timeMillis();
            world.schedulePublishDocument("fake", "fake-t", now + 1, forgery, "carol", InformationModel.Audience.publicAudience(),
                    "外观相同的文档", "没有编辑出版记录。", "自述", 10L, List.of());
            world.advanceTo(now + 1);
            read(world, "alice", forgery);
            assertEquals("NO_VERSION_SOURCE", world.openRoleSession("alice").postToPlatform("fake-post", "fake-post",
                    world.snapshot().timeMillis() + platform.postMillis(), new WorldRoleSession.PlatformPostDraft("试引", "未绑定。",
                            List.of(new WorldRoleSession.Citation(forgery, 1)))));
        }
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            assertEquals(groupPost, world.groupsRun().state().postVersions().get(0).docId());
            assertEquals(2, world.groupsRun().state().postVersions().get(0).originRefs().size());
            assertFalse(world.autonomyRoleObservation("bob").toString().contains(confidential));
            try (var host = new WorldWebHost(() -> new WorldWebHost.Opened(world, world.profile().manifest().runId()))) {
                var personal = JSON.valueToTree(host.groupPage("bob")).toString();
                assertTrue(personal.contains(groupPost));
                assertFalse(personal.contains(confidential));
                assertFalse(personal.contains("内部尾款 137"));
            }
        }
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(file));
    }

    @Test void originalTenCompanyKeepsDeclaredMediaDocumentsAsRootsAfterReopen() throws Exception {
        Path file = directory.resolve("original-origins.journal");
        String source = "engineering-opportunity:A1";
        try (var world = ComposedIndustryScenario.openOriginalTenCompanyAutonomy(file)) {
            String article = publish(world, "investor", "kate", "ed", "MARKETS", List.of(source), "原档报价", "原始报道。");
            read(world, "alice", source);
            read(world, "alice", article);
            var config = world.groupsRun().platformConfig();
            long due = world.snapshot().timeMillis() + config.postMillis();
            assertEquals("SCHEDULED:original", world.openRoleSession("alice").postToPlatform("original", "original", due,
                    new WorldRoleSession.PlatformPostDraft("原档转载", "原文和报道。", List.of(
                            new WorldRoleSession.Citation(source, 1), new WorldRoleSession.Citation(article, 1)))));
            world.advanceTo(due + config.deliveryMillis());
            var post = world.groupsRun().publication().platform().posts().get(0);
            assertEquals(2, post.originRefs().size());
            assertEquals(post.declaredRefs(), post.originRefs());
        }
        var bytes = java.nio.file.Files.readAllBytes(file);
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            assertEquals(RunManifest.ORIGINAL_TEN_COMPANY_RUN_ID, world.profile().manifest().runId());
            assertEquals(2, world.groupsRun().publication().platform().posts().get(0).originRefs().size());
        }
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(file));
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
