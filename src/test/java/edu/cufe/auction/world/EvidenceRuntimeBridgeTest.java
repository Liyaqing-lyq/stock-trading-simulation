package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4i.2c schema16 运行桥：真实私证投稿（含别名）、身份共享与二次同源、拒绝与恢复重算。
 *
 * <p>全部经真实 {@link WorldWebScenario#openEvidence} 世界、真实 {@link WorldRoleSession} 与真实
 * {@link DecisionRun} 派发回执；不手造媒体/证据状态、不直接改待办。所有被引用的资料标识都取自
 * 世界实际投影（{@code roleView().observations()}）与派发输入 {@code inputText}。</p>
 */
class EvidenceRuntimeBridgeTest {

    private static final String PRIVATE = WorldWebScenario.EVIDENCE_PRIVATE_DOC;
    private static final String RITA = "rita";
    private static final String ED = "ed";
    private static final String SAM = "sam";
    private static final String BEA = "bea";
    private static final String WAKE = "wake";
    private static final long SHARE = WorldWebScenario.EVIDENCE_SHARE_MILLIS;
    private static final DecisionModel.Route ROUTE = new DecisionModel.Route(
            "media", "key-1", "deepseek-flash", 1L, 4, 100_000, 2_000, 2);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 日志改写用映射器：保留 BigDecimal 标度，避免把 1000.00 写成 1000.0 造成假失败。 */
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature
                    .USE_BIG_DECIMAL_FOR_FLOATS);

    static {
        SNAPSHOT_MAPPER.setNodeFactory(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    }

    // ---------------------------------------------------------------- 夹具

    private static WorldRuntime open(Path journal) throws Exception {
        return WorldWebScenario.openEvidence(journal).runtime();
    }

    private static void advance(WorldRuntime world, long to) throws Exception {
        if (to > world.snapshot().timeMillis()) {
            world.advanceTo(to);
        }
    }

    /** 真实读完开局那份定向私证；返回世界投影里的实际引用。 */
    private static RoleView.ReadingRef readPrivate(WorldRuntime world, String person)
            throws Exception {
        long now = world.snapshot().timeMillis();
        long duration = 5_000L;
        world.openRoleSession(person).startReading("read-" + person, "read-task-" + person,
                now + duration, PRIVATE, 1L);
        advance(world, now + 2 * duration);
        for (RoleView.Experience experience : world.roleView(person).observations()) {
            if (experience.ref().docId().equals(PRIVATE)) {
                return experience.ref();
            }
        }
        throw new IllegalStateException("没有读到私证：" + person);
    }

    private static String storyKey(String person, String clientId) {
        return WorldRuntime.mediaStoryKey(person, clientId);
    }

    private static void propose(WorldRuntime world, String person, String clientId,
                                String template) throws Exception {
        long now = world.snapshot().timeMillis();
        long duration = world.snapshot().media().orElseThrow().config().proposeMillis();
        world.openRoleSession(person).proposeStory("p-" + clientId, "pt-" + clientId,
                now + duration, new WorldRoleSession.MediaProposeDraft(clientId, template, "私证解读"));
        advance(world, now + duration);
    }

    private static MediaModel.Story story(WorldRuntime world, String organization, String storyId) {
        for (MediaModel.Story candidate : world.snapshot().media().orElseThrow()
                .stateOf(organization).stories()) {
            if (candidate.storyId().equals(storyId)) {
                return candidate;
            }
        }
        return null;
    }

    private static MediaEvidence.State evidence(WorldRuntime world, String organization)
            throws Exception {
        return world.evidenceRun().stateOf(organization);
    }

    /** 真实决定派发 + 脚本回执（不联网）。 */
    private static String decide(WorldRuntime world, String command, String person,
                                 String actionId, String body) throws Exception {
        return decideWith(world, command, person, actionId, input -> body);
    }

    /**
     * 真实决定派发：回执正文由<b>实际派发输入</b>生成，标识不靠测试猜。
     *
     * @param bodyFromInput 输入文本 → 回执正文
     * @return 本次派发的实际 inputText
     */
    private static String decideWith(WorldRuntime world, String command, String person,
                                     String actionId,
                                     java.util.function.Function<String, String> bodyFromInput)
            throws Exception {
        world.beginDecisionSession("session-" + command);
        world.openDecisions(command, List.of(
                new DecisionRun.Request(person, actionId, WAKE, ROUTE)));
        WorldRuntime.DispatchResult dispatched = world.dispatchLiveDecision(
                "dispatch-" + command, actionId);
        assertNotNull(dispatched.call(), "派发失败：" + dispatched.verdict() + "/"
                + dispatched.reason());
        DecisionRun.Call call = dispatched.call();
        DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                bodyFromInput.apply(call.inputText()),
                DecisionModel.Usage.unknownCost(1, 900, 200), "test:scripted");
        assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
        assertTrue(world.drainDecisionCallbacks() >= 1);
        return call.inputText();
    }

    /** 从实际派发输入里取本人读过的唯一资料引用；找不到即失败，不猜标识。 */
    private static MediaModel.Ref citationFromInput(String inputText, String docPrefix)
            throws Exception {
        com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(inputText);
        for (com.fasterxml.jackson.databind.JsonNode entry
                : root.path("profile").path("readExperiences")) {
            String docId = entry.path("docId").asText();
            if (docId.startsWith(docPrefix)) {
                return new MediaModel.Ref(docId, entry.path("version").asLong());
            }
        }
        throw new IllegalStateException("派发输入里没有本人读过的资料：" + inputText);
    }

    /** 实际输入里的媒体稿件投影：按条件取第一条，找不到即失败。 */
    private static com.fasterxml.jackson.databind.JsonNode mediaFromInput(
            String inputText, java.util.function.Predicate<com.fasterxml.jackson.databind.JsonNode> filter)
            throws Exception {
        com.fasterxml.jackson.databind.JsonNode media = MAPPER.readTree(inputText).path("media");
        if (!media.isArray() || media.size() == 0) {
            throw new IllegalStateException("派发输入里没有本人可见的稿件投影：" + inputText);
        }
        for (com.fasterxml.jackson.databind.JsonNode node : media) {
            if (filter.test(node)) {
                return node;
            }
        }
        throw new IllegalStateException("派发输入里没有符合条件的稿件：" + media);
    }

    /** 从本人读过的正文里取共享收件人：只认实际输入文本中的「共享给:」标记。 */
    private static String recipientFromInput(String inputText) throws Exception {
        com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(inputText);
        for (com.fasterxml.jackson.databind.JsonNode entry
                : root.path("profile").path("readExperiences")) {
            String body = entry.path("body").asText();
            int mark = body.indexOf("共享给:");
            if (mark < 0) {
                continue;
            }
            String rest = body.substring(mark + "共享给:".length()).trim();
            int stop = rest.indexOf(' ');
            return stop < 0 ? rest : rest.substring(0, stop);
        }
        throw new IllegalStateException("派发输入里没有写明收件人的资料：" + inputText);
    }

    /** 纯取一次本轮输入（用 wait 回执），不产生业务动作。 */
    private static String observeInput(WorldRuntime world, String person, String actionId)
            throws Exception {
        return decide(world, actionId, person, actionId,
                "{\"tool\":\"" + DecisionModel.TOOL_WAIT + "\",\"args\":{}}");
    }

    /** 让人物真实读到写明收件人的工作说明，并从自己的实际输入里取收件人。 */
    private static String recipientFromWorkOrder(WorldRuntime world, String publisher, String person,
                                                 String target, String docId) throws Exception {
        publishWorkOrder(world, publisher, person, target, docId);
        readDocument(world, person, docId, 1L);
        return recipientFromInput(observeInput(world, person, "a-wo-" + docId));
    }

    /** 发布一份定向工作说明：正文写明共享给谁，人物必须真实读完才拿得到。 */
    private static void publishWorkOrder(WorldRuntime world, String publisher, String recipient,
                                         String target, String docId) throws Exception {
        long now = world.snapshot().timeMillis();
        world.schedulePublishDocument("pub-" + docId, "pub-task-" + docId, now + 1L, docId,
                publisher, InformationModel.Audience.direct(recipient),
                "工作说明 " + docId, "共享给:" + target + " 请注意核实。", "编辑部工作说明",
                500L, List.of());
        advance(world, now + 1L);
    }

    private static RoleView.ReadingRef readDocument(WorldRuntime world, String person, String docId,
                                                    long version) throws Exception {
        long now = world.snapshot().timeMillis();
        long duration = 5_000L;
        String tag = person + "-" + docId + "-" + version + "-" + now;
        world.openRoleSession(person).startReading("read-" + tag, "read-task-" + tag,
                now + duration, docId, version);
        advance(world, now + 2 * duration);
        for (RoleView.Experience experience : world.roleView(person).observations()) {
            if (experience.ref().docId().equals(docId)) {
                return experience.ref();
            }
        }
        throw new IllegalStateException("没有读到资料：" + docId + " v" + version);
    }

    private static void publishStory(WorldRuntime world, String editor, String storyId,
                                     int revision) throws Exception {
        long now = world.snapshot().timeMillis();
        world.openRoleSession(editor).publishStory("pb-" + revision + "-" + storyId,
                "pbt-" + revision + "-" + storyId, now + 2_000L,
                new WorldRoleSession.MediaStoryDraft(storyId, revision));
        advance(world, now + 2_000L);
    }

    private static void reviewStory(WorldRuntime world, String editor, String storyId,
                                    int revision) throws Exception {
        long now = world.snapshot().timeMillis();
        world.openRoleSession(editor).reviewStory("rv-" + revision + "-" + storyId,
                "rvt-" + revision + "-" + storyId, now + 3_000L,
                new WorldRoleSession.MediaReviewDraft(storyId, revision, true, ""));
        advance(world, now + 3_000L);
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void privateEvidenceSubmitWithAliasThroughRealDecisionRun(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("submit.journal"))) {
            RoleView.ReadingRef ref = readPrivate(world, RITA);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            // 创建：客户端给新稿标识是合法输入；派生标识只能从下一轮 input 的 media 投影取
            propose(world, RITA, "wire-1", "WIRE");

            String submitInput = decideWith(world, "submit-1", RITA, "a-submit", text -> {
                com.fasterxml.jackson.databind.JsonNode story;
                try {
                    story = mediaFromInput(text, node -> node.path("own").asBoolean()
                            && "DRAFT".equals(node.path("status").asText()));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                return "{\"tool\":\"media.story.submit\",\"args\":{"
                        + "\"storyId\":\"" + story.path("storyId").asText()
                        + "\",\"expectedRevision\":" + story.path("revisionNo").asInt() + ","
                        + "\"title\":\"私证快讯\",\"body\":\"据一位直接知情人士\","
                        + "\"publicSourceDescription\":\"据一位知情人士\","
                        + "\"evidence\":[{\"docId\":\"" + ref.docId() + "\",\"version\":"
                        + ref.version() + ",\"index\":" + ref.index() + "}],"
                        + "\"aliases\":[{\"rootDocId\":\"" + ref.docId() + "\",\"rootVersion\":"
                        + ref.version() + ",\"label\":\"一位直接知情人士\"}]}}";
            });
            assertTrue(submitInput.contains(ref.docId()), "输入必须含本人真实读过的资料标识");
            assertTrue(submitInput.contains("media.story.submit"), "工具表必须含提交工具");
            assertTrue(submitInput.contains("aliases"), "v4 工具参数必须声明 aliases");
            assertTrue(submitInput.contains("\"media\""), "v4 输入必须含本人可见稿件投影");
            com.fasterxml.jackson.databind.JsonNode created = mediaFromInput(submitInput,
                    node -> node.path("own").asBoolean());
            String key = created.path("storyId").asText();
            assertTrue(key.startsWith("m:"), "投稿标识必须是运行层派生值：" + key);
            assertFalse(key.equals("wire-1"), "输入给的是派生标识，不是客户端原始值");
            advance(world, DecisionModel.actionDueMillis(
                    world.snapshot().decisions().orElseThrow().state(), "a-submit"));

            MediaModel.Story submitted = story(world, "wire", key);
            assertEquals(MediaModel.Status.SUBMITTED, submitted.status());
            assertEquals(1, submitted.revisionNo());
            assertTrue(submitted.roots().contains(new MediaModel.Ref(ref.docId(), ref.version())),
                    "私证必须作为真实根进入既有谱系");
            assertEquals(List.of("一位直接知情人士"),
                    world.publicAliasLabels(key, 1), "别名只落本次真实投稿版本");
            assertTrue(world.publicAliasLabels(key, 2).isEmpty());

            // 编辑：从自己的下一轮输入取 storyId/版次/草稿标识，再实读、审阅、发布
            String edInput = observeInput(world, ED, "a-ed-observe");
            com.fasterxml.jackson.databind.JsonNode forReview = mediaFromInput(edInput,
                    node -> key.equals(node.path("storyId").asText()));
            assertEquals(submitted.draftDocId(), forReview.path("draftDocId").asText(),
                    "草稿标识取自实际输入且与真实状态一致");
            readDocument(world, ED, forReview.path("draftDocId").asText(),
                    forReview.path("draftVersion").asLong());
            reviewStory(world, ED, forReview.path("storyId").asText(),
                    forReview.path("revisionNo").asInt());
            assertEquals(MediaModel.Status.APPROVED, story(world, "wire", key).status());

            String publishInput = observeInput(world, ED, "a-ed-publish");
            com.fasterxml.jackson.databind.JsonNode forPublish = mediaFromInput(publishInput,
                    node -> key.equals(node.path("storyId").asText()));
            assertTrue(forPublish.path("canPublish").asBoolean(), "批准后输入必须标明可发布");
            publishStory(world, ED, forPublish.path("storyId").asText(),
                    forPublish.path("revisionNo").asInt());
            MediaModel.Story published = story(world, "wire", key);
            assertEquals(MediaModel.Status.PUBLISHED, published.status());
            // 公开投影不得随别名泄露真实根
            String publicBody = world.snapshot().information().orElseThrow().information()
                    .documents().stream()
                    .filter(document -> document.docId().equals(published.publicDocId()))
                    .findFirst().orElseThrow().at(1).body();
            assertFalse(publicBody.contains(ref.docId()), "公开正文不得泄露私证标识");
        }
    }

    @Test
    void mediaProjectionIsMinimalAndPerPerson(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("projection.journal"))) {
            RoleView.ReadingRef ref = readPrivate(world, RITA);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            propose(world, RITA, "wire-9", "WIRE");
            String ritaInput = observeInput(world, RITA, "a-proj-rita");
            com.fasterxml.jackson.databind.JsonNode story = mediaFromInput(ritaInput, node -> true);
            for (String field : List.of("storyId", "template", "status", "revisionNo", "topic",
                    "own", "canReview", "canPublish", "draftDocId", "draftVersion",
                    "publicDocId", "publicVersion")) {
                assertTrue(story.has(field), "投影缺字段：" + field);
            }
            // 不得出现来源、私证或策略字段
            String node = story.toString();
            for (String forbidden : List.of("roots", "evidence", "lineage", "sourceGroups",
                    "authorPartyId", ref.docId(), "recallPolicy", "budgetMillis")) {
                assertFalse(node.contains(forbidden), "投影不得含：" + forbidden);
            }
            // 无媒体岗位的人物：没有 media 键，且看不到任何稿件
            String investorInput = observeInput(world, "investor", "a-proj-investor");
            assertFalse(MAPPER.readTree(investorInput).has("media"),
                    "无媒体岗位的人不应看到稿件投影");
        }
    }

    @Test
    void shareIsReadByIdActualRecipientAndResharableToTheSameRoot(@TempDir Path dir)
            throws Exception {
        try (WorldRuntime world = open(dir.resolve("share.journal"))) {
            RoleView.ReadingRef ref = readPrivate(world, RITA);
            // 收件人只能来自本人真实读过的工作说明（正文写明共享给谁），不从测试常量猜
            String recipient = recipientFromWorkOrder(world, ED, RITA, "sam", "work:share-1");
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).shareEvidence("sh-1", "sht-1", now + SHARE,
                    new WorldRoleSession.MediaShareDraft(ref.docId(), ref.version(), recipient,
                            "供核实"));
            advance(world, now + SHARE);

            List<MediaEvidence.IdentityShare> shares = evidence(world, "wire").shares();
            assertTrue(shares.size() == 1, "共享必须真实落一条绑定；报告="
                    + world.snapshot().media().orElseThrow().reports().values());
            MediaEvidence.IdentityShare first = shares.get(0);
            assertEquals(recipient, first.recipientPartyId());
            assertEquals(SHARE, world.snapshot().media().orElseThrow()
                    .budgetOf("wire").spentMillis(), "共享按冻结工时计费");

            // 收件人真实读完共享材料后才知道身份（正文由真实 publisher 派生）
            now = world.snapshot().timeMillis();
            long duration = 5_000L;
            world.openRoleSession(SAM).startReading("read-sam-share", "read-sam-share-task",
                    now + duration, first.sharingDocId(), first.sharingVersion());
            advance(world, now + 2 * duration);
            RoleView.Experience experience = world.roleView(SAM).observations().stream()
                    .filter(item -> item.ref().docId().equals(first.sharingDocId()))
                    .findFirst().orElseThrow();
            assertTrue(experience.body().contains("身份来源：investor"),
                    "共享正文必须写真实来源，而不是中间人：" + experience.body());

            // 二次共享：以本人实际读到的共享材料为锚，归同一原根；收件人同样取自真实工作说明
            String onward = recipientFromWorkOrder(world, RITA, SAM, "bea", "work:share-2");
            now = world.snapshot().timeMillis();
            world.openRoleSession(SAM).shareEvidence("sh-2", "sht-2", now + SHARE,
                    new WorldRoleSession.MediaShareDraft(first.sharingDocId(),
                            first.sharingVersion(), onward, "转达"));
            advance(world, now + SHARE);

            List<MediaEvidence.IdentityShare> all = evidence(world, "investigative").shares();
            assertEquals(1, all.size());
            MediaEvidence.IdentityShare second = all.get(0);
            assertEquals(first.sharingDocId(), second.root().docId(),
                    "绑定保留本次实际读到的来源锚");
            InformationModel.State information = world.snapshot().information().orElseThrow()
                    .information();
            // 绑定分机构保存；解析原根时按全部分支回溯（与运行层同一口径）
            List<MediaEvidence.IdentityShare> ledger = new ArrayList<>();
            for (MediaEvidence.State state : world.evidenceRun().states().values()) {
                ledger.addAll(state.shares());
            }
            MediaEvidence.State bindings = new MediaEvidence.State(List.of(), ledger);
            MediaModel.Ref canonical = MediaModel.canonicalRoot(bindings, information,
                    second.root());
            MediaModel.Ref firstCanonical = MediaModel.canonicalRoot(bindings, information,
                    first.root());
            assertEquals(firstCanonical, canonical, "二次共享必须归同一原根，不新增来源");
            assertEquals(new MediaModel.Ref(PRIVATE, 1L), canonical,
                    "两次共享都必须落到同一份真实私证");
        }
    }

    @Test
    void shareRequiresRealReadAndWriteAuthority(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("reject.journal"))) {
            // 收件人取自本人真实读过的工作说明
            String recipient = recipientFromWorkOrder(world, ED, RITA, "sam", "work:reject-1");
            // 未读就共享：观察帧直接拒绝，仍留下真实报告且不改证据
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).shareEvidence("sh-unread", "sht-unread", now + SHARE,
                    new WorldRoleSession.MediaShareDraft(PRIVATE, 1L, recipient, ""));
            advance(world, now + SHARE);
            assertTrue(world.snapshot().media().orElseThrow().reports().values().stream()
                            .anyMatch(report -> MediaEvidence.REASON_EVIDENCE_NOT_READ
                                    .equals(report.reason())),
                    "未读必须被真实拒绝并留报告");
            assertTrue(evidence(world, "wire").shares().isEmpty());

            // 编辑没有执笔权限：共享在排程期即按既有媒体口径拒绝（NOT_AUTHORIZED），不写任何绑定
            readPrivate(world, RITA);
            now = world.snapshot().timeMillis();
            long at = now;
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> world.openRoleSession(ED).shareEvidence("sh-editor", "sht-editor",
                            at + SHARE, new WorldRoleSession.MediaShareDraft(PRIVATE, 1L, recipient, "")));
            assertEquals(MediaModel.REASON_NOT_AUTHORIZED, rejected.getMessage());
            assertTrue(evidence(world, "wire").shares().isEmpty(),
                    "无执笔权限不得产生共享绑定");
        }
    }

    @Test
    void sameCommandIsIdempotentAndNewCommandMayShareAgain(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("idem.journal"))) {
            RoleView.ReadingRef ref = readPrivate(world, RITA);
            String recipient = recipientFromWorkOrder(world, ED, RITA, "sam", "work:idem-1");
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            WorldRoleSession session = world.openRoleSession(RITA);
            String first = session.shareEvidence("sh-same", "sht-same", now + SHARE,
                    new WorldRoleSession.MediaShareDraft(ref.docId(), ref.version(), recipient, ""));
            // 同一命令同内容：返回原回执，不重复排程
            String again = world.openRoleSession(RITA).shareEvidence("sh-same", "sht-same",
                    now + SHARE, new WorldRoleSession.MediaShareDraft(ref.docId(), ref.version(),
                            recipient, ""));
            assertEquals(first, again);
            assertEquals(1, world.snapshot().pending().stream()
                    .filter(task -> PendingTask.MEDIA_EVIDENCE_SHARE_V1.equals(task.kind()))
                    .count(), "同一命令不得排两次");
            advance(world, now + SHARE);
            assertEquals(1, evidence(world, "wire").shares().size());

            // 不同命令再次共享：允许（对方可能遗忘/材料已变）
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).shareEvidence("sh-other", "sht-other", now + SHARE,
                    new WorldRoleSession.MediaShareDraft(ref.docId(), ref.version(), recipient, ""));
            advance(world, now + SHARE);
            assertEquals(2, evidence(world, "wire").shares().size());
        }
    }

    @Test
    void shareThroughRealDecisionRunUsesInputTextIdentifiers(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("tool-share.journal"))) {
            readPrivate(world, RITA);
            String expectedRecipient = recipientFromWorkOrder(world, ED, RITA, "sam",
                    "work:tool-1");
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            String input = decideWith(world, "share-tool", RITA, "a-share", text -> {
                MediaModel.Ref actual;
                String target;
                try {
                    actual = citationFromInput(text, "evidence:");
                    target = recipientFromInput(text);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                return "{\"tool\":\"media.evidence.share\",\"args\":{"
                        + "\"rootDocId\":\"" + actual.docId() + "\",\"rootVersion\":"
                        + actual.version() + ",\"recipientPartyId\":\"" + target + "\","
                        + "\"note\":\"经工具共享\"}}";
            });
            assertTrue(input.contains("media.evidence.share"),
                    "v4 工具表必须含身份共享工具");
            assertTrue(input.contains(PRIVATE), "模型必须能拿到本人真实读过的标识");
            MediaModel.Ref used = citationFromInput(input, "evidence:");
            advance(world, DecisionModel.actionDueMillis(
                    world.snapshot().decisions().orElseThrow().state(), "a-share"));

            List<MediaEvidence.IdentityShare> shares = evidence(world, "wire").shares();
            assertTrue(shares.size() == 1, "决定派发的共享必须真实落绑定；报告="
                    + world.snapshot().media().orElseThrow().reports().values());
            assertEquals(used, shares.get(0).root(), "共享根必须是 inputText 里给出的实际引用");
            assertEquals(expectedRecipient, shares.get(0).recipientPartyId(),
                    "收件人必须来自实际输入中写明的工作说明");
            assertEquals(SHARE, world.snapshot().media().orElseThrow()
                    .budgetOf("wire").spentMillis(), "工具共享按冻结工时计费");
            assertFalse(world.snapshot().media().orElseThrow().reports().values().stream()
                    .anyMatch(report -> !report.accepted()), "合法共享不得留下拒绝报告");
        }
    }

    @Test
    void compositeForgedShareTaskIsRejectedEvenWhenSelfConsistent(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("forged-base.journal");
        try (WorldRuntime world = open(journal)) {
            RoleView.ReadingRef ref = readPrivate(world, RITA);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).shareEvidence("sh-1", "sht-1", now + SHARE,
                    new WorldRoleSession.MediaShareDraft(ref.docId(), ref.version(), SAM, ""));
            advance(world, now + SHARE);
        }
        Path forged = dir.resolve("forged.journal");
        boolean changed = rewrite(journal, forged, root -> {
            // 伪造一份内部完全自洽的 share 待办：待办 + usedIds + 命令原文 + 回执
            String payload = null;
            com.fasterxml.jackson.databind.JsonNode contents = root.path("commandContents");
            var fields = contents.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getValue().asText().contains("MEDIA_EVIDENCE_SHARE_V1")) {
                    payload = entry.getValue().asText();
                    break;
                }
            }
            if (payload == null) {
                return;
            }
            long time = root.path("timeMillis").asLong();
            var pending = (com.fasterxml.jackson.databind.node.ArrayNode) root.path("pending");
            var forgedTask = pending.addObject();
            forgedTask.put("taskId", "forged-share");
            forgedTask.put("dueTimeMillis", time + SHARE);
            forgedTask.put("phase", "COMPLETION");
            forgedTask.put("kind", "MEDIA_EVIDENCE_SHARE_V1");
            forgedTask.put("payload", payload);
            ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("usedTaskIds"))
                    .add("forged-share");
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("commandContents"))
                    .put("god:forged-share", payload);
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("commandOutcomes"))
                    .put("god:forged-share", "SCHEDULED:forged");
        });
        assertTrue(changed, "夹具必须真的插入伪造待办");
        // 诊断：确认复制只改了最后一帧，开局帧逐字不变
        String baseOpening = firstSnapshot(journal);
        String forgedOpening = firstSnapshot(forged);
        assertEquals(baseOpening, forgedOpening, "复制不得改动开局帧");
        Exception rejected = assertThrows(Exception.class, () -> {
            try (WorldRuntime ignored = open(forged)) {
                // 打开即应因缺少真实排程请求而失败
            }
        }, "自洽伪造的 share 待办+命令元数据必须被拒绝");
        assertTrue(String.valueOf(rejected.getMessage()).contains("排程"),
                "拒绝必须来自真实排程核对，实际：" + rejected.getMessage());
    }

    @Test
    void tamperedEvidenceIsRejectedOnRecovery(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("tamper.journal");
        try (WorldRuntime world = open(journal)) {
            RoleView.ReadingRef ref = readPrivate(world, RITA);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).shareEvidence("sh-1", "sht-1", now + SHARE,
                    new WorldRoleSession.MediaShareDraft(ref.docId(), ref.version(), SAM, ""));
            advance(world, now + SHARE);
        }
        Path tampered = dir.resolve("tampered.journal");
        boolean changed = rewrite(journal, tampered, root -> {
            var evidence = root.path("evidence");
            var states = evidence.path("states");
            if (!states.isObject()) {
                return;
            }
            var wire = states.path("wire");
            var shares = wire.path("shares");
            if (!shares.isArray() || shares.size() == 0) {
                return;
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) shares.get(0))
                    .put("recipientPartyId", BEA);
        });
        assertTrue(changed, "夹具必须真的改到证据状态");
        Exception rejected = assertThrows(Exception.class, () -> {
            try (WorldRuntime ignored = open(tampered)) {
                // 打开即应因逐帧重算不一致失败
            }
        }, "合法重哈希的共享接收者篡改必须被拒绝");
        assertTrue(String.valueOf(rejected.getMessage()).contains("证据"),
                "拒绝必须来自证据子集重算，实际：" + rejected.getMessage());
    }

    @Test
    void shareSurvivesRealCrossProcessRecovery(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("cross.journal");
        String phase1 = runProcess(journal, "phase1");
        assertTrue(phase1.contains("PHASE1"), phase1);
        assertTrue(phase1.contains("shares=1"), phase1);
        assertTrue(phase1.contains("evidence=true"), phase1);
        String phase2 = runProcess(journal, "phase2");
        assertTrue(phase2.contains("PHASE2"), phase2);
        assertTrue(phase2.contains("investigative=1"), phase2);
        assertTrue(phase2.contains("wire=1"), phase2);
        // 本进程再打开一次：两个机构的绑定都在，且逐帧重算通过
        try (WorldRuntime world = open(journal)) {
            assertEquals(1, world.evidenceRun().stateOf("wire").shares().size());
            assertEquals(1, world.evidenceRun().stateOf("investigative").shares().size());
        }
    }

    /** 真跨进程：另起一个 JVM 跑探针，返回其标准输出。 */
    private static String runProcess(Path journal, String phase) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8",
                "-Djava.io.tmpdir=out/tmpdir",
                "-cp", System.getProperty("java.class.path"),
                "edu.cufe.auction.world.EvidenceCrossProcessProbe", journal.toString(), phase);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "子进程失败：" + output);
        return output;
    }

    /** 取日志首个 STATE_SNAPSHOT 事件原文（诊断复制是否改动开局帧）。 */
    private static String firstSnapshot(Path journal) throws Exception {
        try (WorldJournal from = new WorldJournal(journal, WorldWebScenario.EVIDENCE_RUN_ID)) {
            for (WorldCommit commit : from.commits()) {
                for (WorldEvent event : commit.events()) {
                    if ("STATE_SNAPSHOT_V1".equals(event.type())) {
                        return event.payload();
                    }
                }
            }
        }
        return null;
    }

    /** 逐提交复制日志，允许改写 STATE_SNAPSHOT；返回是否真的改到东西。 */
    private static boolean rewrite(Path source, Path target,
                                   java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutator)
            throws Exception {
        boolean[] changed = {false};
        try (WorldJournal from = new WorldJournal(source, WorldWebScenario.EVIDENCE_RUN_ID);
             WorldJournal to = new WorldJournal(target, WorldWebScenario.EVIDENCE_RUN_ID)) {
            List<WorldCommit> commits = new ArrayList<>(from.commits());
            for (int index = 0; index < commits.size(); index++) {
                WorldCommit commit = commits.get(index);
                // 只在最后一个已确认帧插入：开局帧必须保持与清单一致，否则验的是另一条规则
                boolean lastFrame = index == commits.size() - 1;
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    if (!"STATE_SNAPSHOT_V1".equals(event.type())) {
                        events.add(event);
                        continue;
                    }
                    com.fasterxml.jackson.databind.node.ObjectNode root =
                            (com.fasterxml.jackson.databind.node.ObjectNode)
                                    SNAPSHOT_MAPPER.readTree(event.payload());
                    String before = SNAPSHOT_MAPPER.writeValueAsString(root);
                    if (lastFrame) {
                        mutator.accept(root);
                    }
                    String after = SNAPSHOT_MAPPER.writeValueAsString(root);
                    if (!before.equals(after)) {
                        changed[0] = true;
                    }
                    events.add(new WorldEvent(event.eventId(), event.type(), after));
                }
                to.commit(commit.commandId(), commit.commandContent(), commit.result(), events);
            }
        }
        return changed[0];
    }
}
