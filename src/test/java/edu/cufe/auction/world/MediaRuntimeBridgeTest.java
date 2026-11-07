package edu.cufe.auction.world;

import edu.cufe.auction.model.Side;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4h.2 媒体运行桥：真实排程、双人采编全链、组织工时预算、单焦点互斥与恢复。
 *
 * <p>全部走 {@link WorldRuntime} 的正常入口（角色会话 + 世界推进），不手造媒体状态、
 * 不直接改待办。</p>
 */
class MediaRuntimeBridgeTest {

    private static final String COMPANY = "company";
    private static final String MEDIA = "media-a";
    private static final String MEDIA_B = "media-b";
    private static final String SYMBOL = "CO";
    private static final String INVESTOR = "investor";
    private static final String RITA = "rita";
    private static final String ED = "ed";
    private static final String NIA = "nia";
    private static final String SAM = "sam";
    private static final String BEA = "bea";

    private static final long WRITE = 1_000L;
    private static final long REVIEW = 500L;
    private static final long PUBLISH = 200L;
    private static final long DRAFT_READ = 50L;
    private static final long PUBLIC_READ = 80L;
    private static final long PROPOSE = 300L;
    private static final long CORRECTION = 300L;
    private static final long CANCEL = 100L;
    private static final long ANN_READ = 100L;
    private static final long END = 30L * 86_400_000L;

    private static final String RITA_1 = WorldRuntime.mediaStoryKey(RITA, "story-1");
    private static final String RITA_A = WorldRuntime.mediaStoryKey(RITA, "story-a");
    private static final String SAM_B = WorldRuntime.mediaStoryKey(SAM, "story-b");

    // ---------------------------------------------------------------- 夹具

    private static MediaModel.Policy policy(String organization) {
        return new MediaModel.Policy(organization,
                Map.of(MediaModel.TemplateKind.WIRE, 1,
                        MediaModel.TemplateKind.INVESTIGATIVE, 2),
                WRITE, REVIEW, PUBLISH, DRAFT_READ, PUBLIC_READ,
                new CognitionRecall.Policy(86_400_000L, 1, 100, 1_000_000L));
    }

    private static MediaRun.Config mediaConfig(long budget) {
        return new MediaRun.Config(List.of(policy(MEDIA), policy(MEDIA_B)),
                Map.of(MEDIA, budget, MEDIA_B, budget), PROPOSE, CORRECTION, CANCEL);
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions",
                "dividend", "live-decisions", "cognition-recall", "belief", "media")) {
            modules.put(name, 1);
        }
        return new RunManifest("media-runtime-test", 11L,
                RunManifest.RULES_MEDIA_EDITORIAL_EXPERIMENT_V1, modules,
                Map.of("profile", "media-runtime-v1"), RunManifest.LIVE_DECISIONS_V1);
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = new ArrayList<>();
        parties.add(new OpeningRegistry.Party(COMPANY, OpeningRegistry.PartyKind.ORGANIZATION, "实验公司"));
        parties.add(new OpeningRegistry.Party(MEDIA, OpeningRegistry.PartyKind.ORGANIZATION, "实验快讯社"));
        parties.add(new OpeningRegistry.Party(MEDIA_B, OpeningRegistry.PartyKind.ORGANIZATION, "另一家媒体"));
        for (String person : List.of("alice", "bob", "carol", "dave", "erin", RITA, ED, NIA, SAM,
                BEA, INVESTOR)) {
            parties.add(new OpeningRegistry.Party(person, OpeningRegistry.PartyKind.PERSON, person));
        }
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>();
        owners.add(new OpeningRegistry.PropertyOwner("fund-a",
                OpeningRegistry.OwnerKind.PRODUCT, null));
        for (String person : List.of("alice", "bob", "carol", "dave", "erin", RITA, ED, NIA, SAM,
                BEA, INVESTOR)) {
            owners.add(new OpeningRegistry.PropertyOwner(person,
                    OpeningRegistry.OwnerKind.PERSON, person));
        }
        owners.add(new OpeningRegistry.PropertyOwner(COMPANY,
                OpeningRegistry.OwnerKind.ORGANIZATION, COMPANY));
        for (String aggregate : List.of("supplier", "customer", "ops", "locked")) {
            owners.add(new OpeningRegistry.PropertyOwner(aggregate,
                    OpeningRegistry.OwnerKind.AGGREGATE, null));
        }
        return new OpeningRegistry(parties, owners,
                List.of(new OpeningRegistry.Security(SYMBOL, COMPANY, 200L, money("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation("INV", SYMBOL, 100L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("ACC-FUND", SYMBOL, 20L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT),
                        new OpeningRegistry.ShareAllocation("LOCK", SYMBOL, 60L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.LOCKED),
                        new OpeningRegistry.ShareAllocation("CO", SYMBOL, 20L,
                                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.TREASURY)));
    }

    private static List<CashLedger.OpeningAccount> openings() {
        return List.of(
                new CashLedger.OpeningAccount("ACC-FUND", "fund-a", money("1000.00"), "开局历史禀赋"),
                new CashLedger.OpeningAccount("INV", INVESTOR, money("0.00"), "开局历史禀赋"),
                new CashLedger.OpeningAccount("CO", COMPANY, money("1000.00"), "开局历史禀赋"),
                new CashLedger.OpeningAccount("SUP", "supplier", money("100.00"), "开局历史禀赋"),
                new CashLedger.OpeningAccount("CUS", "customer", money("2000.00"), "开局历史禀赋"),
                new CashLedger.OpeningAccount("OPS", "ops", money("0.00"), "开局历史禀赋"),
                new CashLedger.OpeningAccount("LOCK", "locked", money("0.00"), "开局历史禀赋"));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter(COMPANY, List.of(
                new BoardModel.Seat("s1", "erin"), new BoardModel.Seat("s2", "alice"),
                new BoardModel.Seat("s3", "bob"), new BoardModel.Seat("s4", "carol"),
                new BoardModel.Seat("s5", "dave")), "s1", BoardModel.QUORUM);
    }

    private static RoleRun.Config roles() {
        List<RoleAccess.Appointment> appointments = new ArrayList<>(List.of(
                new RoleAccess.Appointment("F-1", "alice", COMPANY, RoleAccess.Role.RESEARCHER, 0L, END, null),
                new RoleAccess.Appointment("F-2", "bob", COMPANY, RoleAccess.Role.PORTFOLIO_MANAGER, 0L, END, null),
                new RoleAccess.Appointment("F-3", "carol", COMPANY, RoleAccess.Role.RISK_OFFICER, 0L, END, null),
                new RoleAccess.Appointment("F-4", "dave", COMPANY, RoleAccess.Role.EXECUTION_TRADER, 0L, END, null),
                new RoleAccess.Appointment("M-1", RITA, MEDIA, RoleAccess.Role.JOURNALIST, 0L, END, null),
                new RoleAccess.Appointment("M-2", ED, MEDIA, RoleAccess.Role.EDITOR, 0L, END, null),
                new RoleAccess.Appointment("B-1", SAM, MEDIA_B, RoleAccess.Role.JOURNALIST, 0L, END, null),
                new RoleAccess.Appointment("B-2", BEA, MEDIA_B, RoleAccess.Role.EDITOR, 0L, END, null)));
        return new RoleRun.Config(
                List.of(new RoleAccess.ProductMandate(COMPANY, "fund-a", List.of("ACC-FUND"))),
                appointments);
    }

    private static InformationRun.Config information() {
        return new InformationRun.Config(
                List.of("alice", "bob", "carol", "dave", "erin", RITA, ED, NIA, SAM, BEA),
                new CognitionRecall.Policy(86_400_000L, 1, 100, 1_000_000L),
                new BeliefModel.Policy(200, 8, 30L * 86_400_000L, 20, 200, 3, 30L * 86_400_000L));
    }

    private static DecisionModel.Config decisions(MediaRun.Config media) {
        DecisionModel.Config base = edu.cufe.auction.web.WorldWebScenario.liveConfig();
        Map<String, Long> actions = new TreeMap<>(base.actionMillisByTool());
        actions.put(RoleTradingView.TOOL_MEDIA_PROPOSE, media.proposeMillis());
        actions.put(RoleTradingView.TOOL_MEDIA_SUBMIT, WRITE);
        actions.put(RoleTradingView.TOOL_MEDIA_REVIEW, REVIEW);
        actions.put(RoleTradingView.TOOL_MEDIA_PUBLISH, PUBLISH);
        actions.put(RoleTradingView.TOOL_MEDIA_CORRECTION, media.correctionMillis());
        actions.put(RoleTradingView.TOOL_MEDIA_CANCEL, media.cancelMillis());
        // schema13 继承信念工具集，配置里必须同时给出它们的耗时，否则决定入口会拒 UNKNOWN_TOOL
        actions.put(RoleTradingView.TOOL_BELIEF_ASSERT, 2_000L);
        actions.put(RoleTradingView.TOOL_FORECAST_PUBLISH, 2_000L);
        actions.put(RoleTradingView.TOOL_COMPANY_REPORT_ORDERS, 2_000L);
        Map<String, DecisionModel.Usage> personCap =
                new TreeMap<>(base.budget().personCap());
        for (String person : List.of(RITA, ED, SAM, BEA)) {
            personCap.putIfAbsent(person, DecisionModel.Usage.unknownCost(25, 250_000, 50_000));
        }
        DecisionModel.Budget budget = new DecisionModel.Budget(base.budget().runCap(), personCap);
        return new DecisionModel.Config(base.providerKind(), base.providerId(),
                base.pricingVersion(), base.thinkMillisByWake(), actions, base.limits(),
                budget, base.profile());
    }

    private static WorldRuntime open(Path journal, long budget) throws Exception {
        MediaRun.Config media = mediaConfig(budget);
        return new WorldRuntime(journal, manifest(), openings(), registry(),
                new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO", "SUP",
                        "CUS", "OPS"), SYMBOL), charter(), roles(), information(),
                decisions(media), media);
    }

    /** 私有探针入口：与正式夹具同一构造，只给本地调试脚本用。 */
    static WorldRuntime openForProbe(Path journal, long budget) throws Exception {
        return open(journal, budget);
    }

    private static Path directory() throws Exception {
        Path root = Path.of("out", "test-journals");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "media-");
    }

    private static MediaRun media(WorldRuntime world) {
        return world.snapshot().media().orElseThrow();
    }

    private static MediaModel.State state(WorldRuntime world, String organization) {
        return media(world).stateOf(organization);
    }

    private static MediaModel.Story story(WorldRuntime world, String organization) {
        return state(world, organization).stories().get(0);
    }

    private static void advance(WorldRuntime world, long to) throws Exception {
        if (to > world.snapshot().timeMillis()) {
            world.advanceTo(to);
        }
    }

    /** 公司公开资料：真实受信发布入口。 */
    private static String publishAnnouncement(WorldRuntime world, String publisher, String docId)
            throws Exception {
        long now = world.snapshot().timeMillis();
        world.schedulePublishDocument("pub-" + docId, "pub-task-" + docId, now + 1, docId, publisher,
                InformationModel.Audience.publicAudience(), "公告 " + docId, "正文 " + docId,
                "公司公告", ANN_READ, List.of(docId));
        advance(world, now + 1);
        return docId;
    }

    private static RoleView.ReadingRef readDocument(WorldRuntime world, String person, String docId)
            throws Exception {
        return readDocument(world, person, docId, 1);
    }

    private static RoleView.ReadingRef readDocument(WorldRuntime world, String person, String docId,
                                                    long version) throws Exception {
        long now = world.snapshot().timeMillis();
        WorldRoleSession session = world.openRoleSession(person);
        session.startReading("read-" + person + "-" + docId + "-" + version + "-" + now,
                "read-task-" + person + "-" + docId + "-" + version + "-" + now, now + ANN_READ,
                docId, version);
        // 开始阅读的任务在 now+ANN_READ 完成并排出自动完成子任务，再过一个阅读耗时才产生经历
        advance(world, now + 2 * ANN_READ);
        for (RoleView.Experience experience : world.roleView(person).observations()) {
            if (experience.ref().docId().equals(docId)
                    && experience.ref().version() == version) {
                return experience.ref();
            }
        }
        throw new IllegalStateException("没有读到资料：" + docId + " v" + version);
    }

    // ---------------------------------------------------------------- 全链

    @Test
    void twoPersonChainPublishesAndThirdReaderKeepsOldVersion() throws Exception {
        Path journal = directory().resolve("world.journal");
        try (WorldRuntime world = open(journal, 1_000_000L)) {
            String ann = publishAnnouncement(world, "erin", "ann-1");
            RoleView.ReadingRef ref = readDocument(world, RITA, ann);

            long now = world.snapshot().timeMillis();
            WorldRoleSession reporter = world.openRoleSession(RITA);
            reporter.proposeStory("p1", "t1", now + PROPOSE,
                    new WorldRoleSession.MediaProposeDraft("story-1", "WIRE", "公告解读"));
            advance(world, now + PROPOSE);

            now = world.snapshot().timeMillis();
            reporter = world.openRoleSession(RITA);
            reporter.submitStory("s1", "st1", now + WRITE,
                    new WorldRoleSession.MediaSubmitDraft(RITA_1, 0, "快讯", "初稿正文",
                            "据公司公告", List.of(new WorldRoleSession.MediaCitation(ref.docId(),
                                    ref.version(), ref.index()))));
            advance(world, now + WRITE);
            assertEquals(MediaModel.Status.SUBMITTED, story(world, MEDIA).status());

            String draftDoc = story(world, MEDIA).draftDocId();
            readDocument(world, ED, draftDoc);

            now = world.snapshot().timeMillis();
            world.openRoleSession(ED).reviewStory("r1", "rt1", now + REVIEW,
                    new WorldRoleSession.MediaReviewDraft(RITA_1, 1, true, ""));
            advance(world, now + REVIEW);
            assertEquals(MediaModel.Status.APPROVED, story(world, MEDIA).status());

            now = world.snapshot().timeMillis();
            world.openRoleSession(ED).publishStory("pb1", "pbt1", now + PUBLISH,
                    new WorldRoleSession.MediaStoryDraft(RITA_1, 1));
            advance(world, now + PUBLISH);
            MediaModel.Story published = story(world, MEDIA);
            assertEquals(MediaModel.Status.PUBLISHED, published.status());
            assertEquals(1, published.publicVersion());

            // 第三人真实阅读公开成稿
            RoleView.ReadingRef publicRef = readDocument(world, NIA, published.publicDocId());
            assertEquals(1, publicRef.version());

            // 更正：开启 → 重提 → 编辑重读 → 批准 → 追加版本
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).startCorrection("c1", "ct1", now + CORRECTION,
                    new WorldRoleSession.MediaStoryDraft(RITA_1, 1));
            advance(world, now + CORRECTION);

            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).submitStory("s2", "st2", now + WRITE,
                    new WorldRoleSession.MediaSubmitDraft(RITA_1, 1, "快讯", "更正后正文",
                            "据公司公告", List.of(new WorldRoleSession.MediaCitation(ref.docId(),
                                    ref.version(), ref.index()))));
            advance(world, now + WRITE);
            assertEquals(2, story(world, MEDIA).draftVersion());

            readDocument(world, ED, draftDoc, 2);
            now = world.snapshot().timeMillis();
            world.openRoleSession(ED).reviewStory("r2", "rt2", now + REVIEW,
                    new WorldRoleSession.MediaReviewDraft(RITA_1, 2, true, ""));
            advance(world, now + REVIEW);
            now = world.snapshot().timeMillis();
            world.openRoleSession(ED).publishStory("pb2", "pbt2", now + PUBLISH,
                    new WorldRoleSession.MediaStoryDraft(RITA_1, 2));
            advance(world, now + PUBLISH);

            MediaModel.Story corrected = story(world, MEDIA);
            assertEquals(2, corrected.publicVersion());
            InformationModel.State info = world.snapshot().information().orElseThrow()
                    .information();
            assertTrue(InformationModel.canCite(info, NIA, corrected.publicDocId(), 1,
                    world.snapshot().timeMillis()), "旧读者仍持有旧版本");
            assertFalse(InformationModel.canCite(info, NIA, corrected.publicDocId(), 2,
                    world.snapshot().timeMillis()));

            // 预算：六类动作全部计费，已耗等于工作记录之和
            MediaRun run = media(world);
            long spent = run.budgetOf(MEDIA).spentMillis();
            long charged = 0L;
            for (MediaRun.Work work : run.worksOf(MEDIA)) {
                charged += work.chargedMillis();
            }
            assertEquals(charged, spent);
            assertEquals(PROPOSE + WRITE + REVIEW + PUBLISH + CORRECTION + WRITE + REVIEW + PUBLISH,
                    spent);
            assertEquals(8, run.worksOf(MEDIA).size());
        }
    }

    @Test
    void budgetExhaustionIsBusinessRejectionNotTechnicalFailure() throws Exception {
        Path journal = directory().resolve("world.journal");
        long budget = PROPOSE + WRITE;
        try (WorldRuntime world = open(journal, budget)) {
            String ann = publishAnnouncement(world, "erin", "ann-1");
            RoleView.ReadingRef ref = readDocument(world, RITA, ann);
            long now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).proposeStory("p1", "t1", now + PROPOSE,
                    new WorldRoleSession.MediaProposeDraft("story-1", "WIRE", "预算"));
            advance(world, now + PROPOSE);
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).submitStory("s1", "st1", now + WRITE,
                    new WorldRoleSession.MediaSubmitDraft(RITA_1, 0, "标题", "正文", "来源",
                            List.of(new WorldRoleSession.MediaCitation(ref.docId(), ref.version(),
                                    ref.index()))));
            advance(world, now + WRITE);
            assertEquals(budget, media(world).budgetOf(MEDIA).spentMillis());

            // 额度耗尽：编辑动作照常排程，到期是 BUDGET 业务拒绝，不抛技术失败、不计费
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).startCorrection("c1", "ct1", now + CORRECTION,
                    new WorldRoleSession.MediaStoryDraft(RITA_1, 1));
            advance(world, now + CORRECTION);
            MediaRun run = media(world);
            assertEquals(budget, run.budgetOf(MEDIA).spentMillis(), "预算拒绝不得计费");
            assertEquals(MediaModel.Status.SUBMITTED, story(world, MEDIA).status(),
                    "预算拒绝不得改业务状态");
            assertTrue(run.reports().values().stream().anyMatch(report ->
                    !report.accepted() && "BUDGET".equals(report.reason())
                            && MediaRun.KIND_CORRECTION.equals(report.kind())),
                    "预算不足必须是可回放的业务拒绝");

            // 额度全被消耗时取消仍然允许（取消不占工时）
            long at = world.snapshot().timeMillis();
            world.openRoleSession(RITA).cancelMediaWork("c2", "ct2", at + CANCEL);
            advance(world, at + CANCEL);
            assertTrue(media(world).reports().values().stream().anyMatch(report ->
                    MediaRun.KIND_CANCEL.equals(report.kind())), "取消动作有回报");
        }
    }

    @Test
    void cancelChargesOnlyElapsedTimeAndReleasesTheRest() throws Exception {
        Path journal = directory().resolve("world.journal");
        long budget = PROPOSE + WRITE + CANCEL;
        try (WorldRuntime world = open(journal, budget)) {
            String ann = publishAnnouncement(world, "erin", "ann-1");
            RoleView.ReadingRef ref = readDocument(world, RITA, ann);
            long now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).proposeStory("p1", "t1", now + PROPOSE,
                    new WorldRoleSession.MediaProposeDraft("story-1", "WIRE", "取消"));
            advance(world, now + PROPOSE);
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).submitStory("s1", "st1", now + WRITE,
                    new WorldRoleSession.MediaSubmitDraft(RITA_1, 0, "标题", "正文", "来源",
                            List.of(new WorldRoleSession.MediaCitation(ref.docId(), ref.version(),
                                    ref.index()))));
            assertEquals(1, world.snapshot().pending().stream()
                    .filter(task -> MediaRun.KINDS.contains(task.kind())).count());

            world.openRoleSession(RITA).cancelMediaWork("c1", "ct1", now + CANCEL);
            advance(world, now + CANCEL);
            MediaRun run = media(world);
            // 取消动作本身不消耗组织工时：只对被取消的提交按已过 100 毫秒计费
            assertEquals(PROPOSE + 100, run.budgetOf(MEDIA).spentMillis());
            assertEquals(MediaModel.Status.DRAFT, story(world, MEDIA).status());
            assertTrue(run.worksOf(MEDIA).stream().anyMatch(work ->
                    MediaRun.OUTCOME_CANCELED.equals(work.outcome())
                            && work.chargedMillis() == 100), "取消只扣实际已过时长");
            assertTrue(run.worksOf(MEDIA).stream().anyMatch(work ->
                    MediaRun.KIND_CANCEL.equals(work.kind()) && work.chargedMillis() == 0),
                    "取消动作本身不计组织工时");
            assertEquals(0, world.snapshot().pending().stream()
                    .filter(task -> MediaRun.KINDS.contains(task.kind())).count(),
                    "被取消的任务离开待办");
        }
    }

    @Test
    void onePersonCannotRunTwoMediaWorksOrReadAtTheSameTime() throws Exception {
        Path journal = directory().resolve("world.journal");
        try (WorldRuntime world = open(journal, 1_000_000L)) {
            String ann = publishAnnouncement(world, "erin", "ann-1");
            RoleView.ReadingRef ref = readDocument(world, RITA, ann);
            long now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).proposeStory("p1", "t1", now + PROPOSE,
                    new WorldRoleSession.MediaProposeDraft("story-1", "WIRE", "互斥"));
            advance(world, now + PROPOSE);

            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).submitStory("s1", "st1", now + WRITE,
                    new WorldRoleSession.MediaSubmitDraft(RITA_1, 0, "标题", "正文", "来源",
                            List.of(new WorldRoleSession.MediaCitation(ref.docId(), ref.version(),
                                    ref.index()))));
            // 未完成时不得再开第二项媒体工作
            WorldRoleSession busy = world.openRoleSession(RITA);
            long at = world.snapshot().timeMillis();
            assertThrows(IllegalArgumentException.class, () -> busy.proposeStory("p2", "t2",
                    at + PROPOSE, new WorldRoleSession.MediaProposeDraft("story-2", "WIRE", "第二")));
            // 也不得同时阅读
            assertThrows(IllegalArgumentException.class, () -> busy.startReading("r2", "rt2",
                    at + ANN_READ, ann, 1));
            advance(world, now + WRITE);
            assertEquals(MediaModel.Status.SUBMITTED, story(world, MEDIA).status());
        }
    }

    @Test
    void crossMediaReprintInheritsRootsFromSameCut() throws Exception {
        Path journal = directory().resolve("world.journal");
        try (WorldRuntime world = open(journal, 1_000_000L)) {
            String ann = publishAnnouncement(world, "erin", "ann-1");
            RoleView.ReadingRef ref = readDocument(world, RITA, ann);
            long now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).proposeStory("p1", "t1", now + PROPOSE,
                    new WorldRoleSession.MediaProposeDraft("story-a", "WIRE", "原创"));
            advance(world, now + PROPOSE);
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).submitStory("s1", "st1", now + WRITE,
                    new WorldRoleSession.MediaSubmitDraft(RITA_A, 0, "标题", "正文", "来源",
                            List.of(new WorldRoleSession.MediaCitation(ref.docId(), ref.version(),
                                    ref.index()))));
            advance(world, now + WRITE);
            readDocument(world, ED, story(world, MEDIA).draftDocId());
            now = world.snapshot().timeMillis();
            world.openRoleSession(ED).reviewStory("r1", "rt1", now + REVIEW,
                    new WorldRoleSession.MediaReviewDraft(RITA_A, 1, true, ""));
            advance(world, now + REVIEW);
            now = world.snapshot().timeMillis();
            world.openRoleSession(ED).publishStory("pb1", "pbt1", now + PUBLISH,
                    new WorldRoleSession.MediaStoryDraft(RITA_A, 1));
            advance(world, now + PUBLISH);
            String publishedDoc = story(world, MEDIA).publicDocId();

            // 另一家媒体的记者读到这篇报道后转载，根必须继承而不是新增
            RoleView.ReadingRef reprintRef = readDocument(world, SAM, publishedDoc);
            now = world.snapshot().timeMillis();
            world.openRoleSession(SAM).proposeStory("bp1", "bt1", now + PROPOSE,
                    new WorldRoleSession.MediaProposeDraft("story-b", "WIRE", "转载"));
            advance(world, now + PROPOSE);
            now = world.snapshot().timeMillis();
            world.openRoleSession(SAM).submitStory("bs1", "bst1", now + WRITE,
                    new WorldRoleSession.MediaSubmitDraft(SAM_B, 0, "转载标题", "转载正文",
                            "据同行报道", List.of(new WorldRoleSession.MediaCitation(
                            reprintRef.docId(), reprintRef.version(), reprintRef.index()))));
            advance(world, now + WRITE);
            MediaModel.Story reprint = state(world, MEDIA_B).stories().get(0);
            assertEquals(MediaModel.Status.SUBMITTED, reprint.status());
            assertEquals(1, reprint.sourceGroups(), "转载不能因换了媒体就变成两个独立来源");
            assertEquals(1, reprint.roots().size());
            assertEquals(ref.docId(), reprint.roots().get(0).docId());
        }
    }

    @Test
    void reopeningKeepsMediaStateBudgetAndPendingWork() throws Exception {
        Path journal = directory().resolve("world.journal");
        long now;
        try (WorldRuntime world = open(journal, 1_000_000L)) {
            String ann = publishAnnouncement(world, "erin", "ann-1");
            RoleView.ReadingRef ref = readDocument(world, RITA, ann);
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).proposeStory("p1", "t1", now + PROPOSE,
                    new WorldRoleSession.MediaProposeDraft("story-1", "WIRE", "恢复"));
            advance(world, now + PROPOSE);
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).submitStory("s1", "st1", now + WRITE,
                    new WorldRoleSession.MediaSubmitDraft(RITA_1, 0, "标题", "正文", "来源",
                            List.of(new WorldRoleSession.MediaCitation(ref.docId(), ref.version(),
                                    ref.index()))));
            // 不推进：留下一个未完成媒体任务
        }
        try (WorldRuntime restored = open(journal, 1_000_000L)) {
            MediaRun run = media(restored);
            assertEquals(PROPOSE, run.budgetOf(MEDIA).spentMillis(), "已完成的选题计费保留");
            List<PendingTask> mediaPending = new ArrayList<>();
            for (PendingTask task : restored.snapshot().pending()) {
                if (MediaRun.KINDS.contains(task.kind())) {
                    mediaPending.add(task);
                }
            }
            assertEquals(1, mediaPending.size(), "未完成提交仍在待办");
            assertEquals(now + WRITE, mediaPending.get(0).dueTimeMillis());
            assertEquals(MediaRun.KIND_SUBMIT, mediaPending.get(0).kind());
            advance(restored, now + WRITE);
            assertEquals(MediaModel.Status.SUBMITTED, story(restored, MEDIA).status());
            assertEquals(PROPOSE + WRITE, media(restored).budgetOf(MEDIA).spentMillis());
        }
    }

    @Test
    void mediaToolsAppearOnlyForMediaAppointments() throws Exception {
        Path journal = directory().resolve("world.journal");
        try (WorldRuntime world = open(journal, 1_000_000L)) {
            publishAnnouncement(world, "erin", "ann-1");
            List<String> reporterTools = world.roleTradingView(world.openRoleSession(RITA)).tools();
            assertTrue(reporterTools.contains(RoleTradingView.TOOL_MEDIA_PROPOSE));
            assertTrue(reporterTools.contains(RoleTradingView.TOOL_MEDIA_SUBMIT));
            assertTrue(reporterTools.contains(RoleTradingView.TOOL_MEDIA_CORRECTION));
            assertTrue(reporterTools.contains(RoleTradingView.TOOL_MEDIA_CANCEL));
            assertFalse(reporterTools.contains(RoleTradingView.TOOL_MEDIA_REVIEW));
            List<String> editorTools = world.roleTradingView(world.openRoleSession(ED)).tools();
            assertTrue(editorTools.contains(RoleTradingView.TOOL_MEDIA_REVIEW));
            assertTrue(editorTools.contains(RoleTradingView.TOOL_MEDIA_PUBLISH));
            assertFalse(editorTools.contains(RoleTradingView.TOOL_MEDIA_PROPOSE));
            List<String> fundTools = world.roleTradingView(world.openRoleSession("alice")).tools();
            assertFalse(fundTools.contains(RoleTradingView.TOOL_MEDIA_PROPOSE));
            // 工具字段合同不含 actor/组织/策略/工时
            for (String[] pair : DecisionRun.toolParameters(RoleTradingView.TOOL_MEDIA_SUBMIT)) {
                assertFalse(pair[0].equals("personPartyId"));
                assertFalse(pair[0].equals("organizationPartyId"));
            }
        }
    }

    @Test
    void decisionToolActuallySchedulesMediaWork() throws Exception {
        Path journal = directory().resolve("world.journal");
        try (WorldRuntime world = open(journal, 1_000_000L)) {
            String ann = publishAnnouncement(world, "erin", "ann-1");
            readDocument(world, RITA, ann);
            world.beginDecisionSession("session");
            com.fasterxml.jackson.databind.ObjectMapper json =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            assertEquals("DECIDING:1", world.openDecisions("open-propose",
                    List.of(new DecisionRun.Request(RITA, "propose", "wake",
                            new DecisionModel.Route("research", "key-1", "deepseek-flash", 0, 20,
                                    100_000, 128, 1)))));
            var dispatched = world.dispatchLiveDecision("dispatch-propose", "propose");
            assertEquals("DISPATCHED", dispatched.verdict());
            var call = dispatched.call();
            String body = json.writeValueAsString(Map.of("tool",
                    RoleTradingView.TOOL_MEDIA_PROPOSE, "args", Map.of("storyId", "story-d",
                            "template", "WIRE", "topic", "决策入口")));
            var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                    call.attemptNo(), call.recoveryGeneration(), body,
                    DecisionModel.Usage.unknownCost(1, 20, 5), "test:local");
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
            assertEquals(1, world.drainDecisionCallbacks());
            DecisionModel.Decision recorded = DecisionModel.decision(
                    world.snapshot().decisions().orElseThrow().state(), "propose").orElseThrow();
            assertEquals(RoleTradingView.TOOL_MEDIA_PROPOSE, recorded.recordedTool(),
                    "outcome=" + recorded.outcome() + " reason="
                            + (recorded.lastAttempt() == null ? "-"
                            : recorded.lastAttempt().reason()) + " reply="
                            + (recorded.lastAttempt() == null ? "-"
                            : recorded.lastAttempt().replyBody()));
            PendingTask scheduled = world.snapshot().pending().stream()
                    .filter(task -> MediaRun.KINDS.contains(task.kind())).findFirst().orElseThrow();
            assertEquals(PendingTask.MEDIA_STORY_PROPOSE_V1, scheduled.kind());
            assertTrue(scheduled.payload().contains("media-a"));
            assertTrue(scheduled.payload().contains("startedAtMillis"),
                    "动作起点由运行层冻结，不在工具参数里");
            world.advanceTo(DecisionModel.actionDueMillis(
                    world.snapshot().decisions().orElseThrow().state(), "propose"));
            assertEquals(1, state(world, MEDIA).stories().size());
            assertEquals(MediaModel.Status.DRAFT, story(world, MEDIA).status());
            assertTrue(media(world).reports().values().stream()
                    .anyMatch(report -> report.accepted() && MediaRun.KIND_PROPOSE.equals(
                            report.kind())));
        }
    }

    /** 通过真实 DecisionRun + stub 回执执行一个媒体工具动作，并推进到该动作到期。 */
    private static void act(WorldRuntime world, String person, String id, String tool, Object args)
            throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("DECIDING:1", world.openDecisions("open-" + id,
                List.of(new DecisionRun.Request(person, id, "wake",
                        new DecisionModel.Route("research", "key-1", "deepseek-flash", 0, 20,
                                100_000, 128, 1)))), "打开决定：" + id);
        var dispatched = world.dispatchLiveDecision("dispatch-" + id, id);
        assertEquals("DISPATCHED", dispatched.verdict(), "派发：" + id);
        var call = dispatched.call();
        String body = json.writeValueAsString(Map.of("tool", tool, "args", args));
        var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                call.attemptNo(), call.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, 20, 5), "test:local");
        assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)),
                "回执入队：" + id);
        assertEquals(1, world.drainDecisionCallbacks(), "回执结算：" + id);
        DecisionModel.Decision recorded = DecisionModel.decision(
                world.snapshot().decisions().orElseThrow().state(), id).orElseThrow();
        assertEquals(tool, recorded.recordedTool(), "记录工具：" + id + " outcome="
                + recorded.outcome() + " reason=" + (recorded.lastAttempt() == null ? "-"
                : recorded.lastAttempt().reason()));
        world.advanceTo(DecisionModel.actionDueMillis(
                world.snapshot().decisions().orElseThrow().state(), id));
    }

    @Test
    void fullDecisionChainWithReturnAndCorrection() throws Exception {
        Path journal = directory().resolve("world.journal");
        try (WorldRuntime world = open(journal, 1_000_000L)) {
            String ann1 = publishAnnouncement(world, "erin", "ann-1");
            String ann2 = publishAnnouncement(world, "alice", "ann-2");
            RoleView.ReadingRef e1 = readDocument(world, RITA, ann1);
            RoleView.ReadingRef e2 = readDocument(world, RITA, ann2);
            world.beginDecisionSession("session");
            String sid = WorldRuntime.mediaStoryKey(RITA, "story-inv");
            List<Map<String, Object>> evidence = List.of(
                    Map.of("docId", e1.docId(), "version", e1.version(), "index", e1.index()),
                    Map.of("docId", e2.docId(), "version", e2.version(), "index", e2.index()));

            act(world, RITA, "p1", RoleTradingView.TOOL_MEDIA_PROPOSE,
                    Map.of("storyId", "story-inv", "template", "INVESTIGATIVE", "topic", "订单调查"));
            assertEquals(MediaModel.Status.DRAFT, story(world, MEDIA).status());

            act(world, RITA, "s1", RoleTradingView.TOOL_MEDIA_SUBMIT,
                    Map.of("storyId", sid, "expectedRevision", 0, "title", "调查",
                            "body", "初稿正文", "publicSourceDescription", "据两家公告",
                            "evidence", evidence));
            assertEquals(MediaModel.Status.SUBMITTED, story(world, MEDIA).status());
            assertEquals(2, story(world, MEDIA).sourceGroups(), "两个不同发布者算两组");

            String draftDoc = story(world, MEDIA).draftDocId();
            readDocument(world, ED, draftDoc);
            act(world, ED, "r1", RoleTradingView.TOOL_MEDIA_REVIEW,
                    Map.of("storyId", sid, "expectedRevision", 1, "approve", false,
                            "note", "缺少第二来源核实"));
            assertEquals(MediaModel.Status.RETURNED, story(world, MEDIA).status());

            act(world, RITA, "s2", RoleTradingView.TOOL_MEDIA_SUBMIT,
                    Map.of("storyId", sid, "expectedRevision", 1, "title", "调查",
                            "body", "补充后的正文", "publicSourceDescription", "据两家公告",
                            "evidence", evidence));
            readDocument(world, ED, draftDoc, 2);
            act(world, ED, "r2", RoleTradingView.TOOL_MEDIA_REVIEW,
                    Map.of("storyId", sid, "expectedRevision", 2, "approve", true, "note", ""));
            assertEquals(MediaModel.Status.APPROVED, story(world, MEDIA).status());
            act(world, ED, "pb1", RoleTradingView.TOOL_MEDIA_PUBLISH,
                    Map.of("storyId", sid, "expectedRevision", 2));
            MediaModel.Story first = story(world, MEDIA);
            assertEquals(MediaModel.Status.PUBLISHED, first.status());
            assertEquals(1, first.publicVersion());

            RoleView.ReadingRef readerV1 = readDocument(world, NIA, first.publicDocId());

            act(world, RITA, "c1", RoleTradingView.TOOL_MEDIA_CORRECTION,
                    Map.of("storyId", sid, "expectedRevision", 2));
            act(world, RITA, "s3", RoleTradingView.TOOL_MEDIA_SUBMIT,
                    Map.of("storyId", sid, "expectedRevision", 2, "title", "调查",
                            "body", "更正后的正文", "publicSourceDescription", "据两家公告",
                            "evidence", evidence));
            readDocument(world, ED, draftDoc, 3);
            act(world, ED, "r3", RoleTradingView.TOOL_MEDIA_REVIEW,
                    Map.of("storyId", sid, "expectedRevision", 3, "approve", true, "note", ""));
            act(world, ED, "pb2", RoleTradingView.TOOL_MEDIA_PUBLISH,
                    Map.of("storyId", sid, "expectedRevision", 3));
            MediaModel.Story corrected = story(world, MEDIA);
            assertEquals(2, corrected.publicVersion(), "更正追加公开版本");

            InformationModel.State info = world.snapshot().information().orElseThrow()
                    .information();
            assertTrue(InformationModel.canCite(info, NIA, corrected.publicDocId(), 1,
                    world.snapshot().timeMillis()), "旧读者保留旧版本");
            assertFalse(InformationModel.canCite(info, NIA, corrected.publicDocId(), 2,
                    world.snapshot().timeMillis()), "更正不自动刷新读者");

            MediaRun run = media(world);
            long charged = run.worksOf(MEDIA).stream()
                    .mapToLong(MediaRun.Work::chargedMillis).sum();
            assertEquals(charged, run.budgetOf(MEDIA).spentMillis(), "预算与工作记录守恒");
            assertEquals(10, run.worksOf(MEDIA).size(), "十次媒体动作各一条工作记录");
        }
    }

    @Test
    void mediaWorkIsRefusedWhileReadingIsInFlight() throws Exception {
        Path journal = directory().resolve("world.journal");
        try (WorldRuntime world = open(journal, 1_000_000L)) {
            String ann = publishAnnouncement(world, "erin", "ann-1");
            long now = world.snapshot().timeMillis();
            // 阅读在途：单焦点规则下不能同时开展媒体工作
            world.openRoleSession(RITA).startReading("r0", "rt0", now + ANN_READ, ann, 1);
            world.beginDecisionSession("session");
            assertEquals("DECIDING:1", world.openDecisions("open-busy",
                    List.of(new DecisionRun.Request(RITA, "busy", "wake",
                            new DecisionModel.Route("research", "key-1", "deepseek-flash", 0, 20,
                                    100_000, 128, 1)))));
            var dispatched = world.dispatchLiveDecision("dispatch-busy", "busy");
            assertEquals("DISPATCHED", dispatched.verdict());
            var call = dispatched.call();
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            String body = json.writeValueAsString(Map.of("tool",
                    RoleTradingView.TOOL_MEDIA_PROPOSE, "args", Map.of("storyId", "story-busy",
                            "template", "WIRE", "topic", "并发")));
            var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                    call.attemptNo(), call.recoveryGeneration(), body,
                    DecisionModel.Usage.unknownCost(1, 20, 5), "test:local");
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
            assertEquals(1, world.drainDecisionCallbacks());
            DecisionModel.Decision recorded = DecisionModel.decision(
                    world.snapshot().decisions().orElseThrow().state(), "busy").orElseThrow();
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, recorded.outcome(),
                    "阅读在途时媒体动作只能是技术失败，不建任务");
            assertEquals(0, world.snapshot().pending().stream()
                    .filter(task -> MediaRun.KINDS.contains(task.kind())).count());
        }
    }

    @Test
    void sameCommandRetryIsIdempotentForEveryMediaTool() throws Exception {
        Path journal = directory().resolve("world.journal");
        try (WorldRuntime world = open(journal, 1_000_000L)) {
            String ann = publishAnnouncement(world, "erin", "ann-1");
            RoleView.ReadingRef ref = readDocument(world, RITA, ann);

            // 1 提出选题：pending 重试 + 完成后重试（均用原 due；世界时钟变化不影响）
            long proposeDue = world.snapshot().timeMillis() + PROPOSE;
            var reporter = world.openRoleSession(RITA);
            String proposed = reporter.proposeStory("cp", "cpt", proposeDue,
                    new WorldRoleSession.MediaProposeDraft("retry", "WIRE", "幂等"));
            long position = world.snapshot().position();
            assertEquals(proposed, reporter.proposeStory("cp", "cpt", proposeDue,
                    new WorldRoleSession.MediaProposeDraft("retry", "WIRE", "幂等")),
                    "pending 重试必须返回原回执");
            assertEquals(position, world.snapshot().position(), "重试不得改动状态");
            advance(world, proposeDue);
            position = world.snapshot().position();
            assertEquals(proposed, world.openRoleSession(RITA).proposeStory("cp", "cpt", proposeDue,
                    new WorldRoleSession.MediaProposeDraft("retry", "WIRE", "幂等")),
                    "完成后重试仍返回原回执");
            assertEquals(position, world.snapshot().position(), "完成后重试不得改动状态");

            String storyId = WorldRuntime.mediaStoryKey(RITA, "retry");
            // 同一命令不同内容 / 不同人物任务 / 不同到期，都必须拒绝
            assertThrows(IllegalArgumentException.class, () ->
                    world.openRoleSession(RITA).proposeStory("cp", "cpt", proposeDue,
                            new WorldRoleSession.MediaProposeDraft("other", "WIRE", "幂等")));
            assertThrows(IllegalArgumentException.class, () ->
                    world.openRoleSession(RITA).proposeStory("cp", "wrong-task", proposeDue,
                            new WorldRoleSession.MediaProposeDraft("retry", "WIRE", "幂等")));
            assertThrows(IllegalArgumentException.class, () ->
                    world.openRoleSession(RITA).proposeStory("cp", "cpt", proposeDue + 1,
                            new WorldRoleSession.MediaProposeDraft("retry", "WIRE", "幂等")));

            // 2 提交：pending 重试
            var submitDraft = new WorldRoleSession.MediaSubmitDraft(storyId, 0, "标题", "正文",
                    "来源", List.of(new WorldRoleSession.MediaCitation(ref.docId(), ref.version(),
                    ref.index())));
            long now = world.snapshot().timeMillis();
            String submitted = world.openRoleSession(RITA).submitStory("cs", "cst", now + WRITE,
                    submitDraft);
            position = world.snapshot().position();
            assertEquals(submitted, world.openRoleSession(RITA).submitStory("cs", "cst", now + WRITE,
                    submitDraft), "提交 pending 重试必须返回原回执");
            assertEquals(position, world.snapshot().position());
            advance(world, now + WRITE);

            // 3 审阅：pending 重试
            readDocument(world, ED, story(world, MEDIA).draftDocId());
            now = world.snapshot().timeMillis();
            var reviewDraft = new WorldRoleSession.MediaReviewDraft(storyId, 1, true, "");
            String reviewed = world.openRoleSession(ED).reviewStory("cr", "crt", now + REVIEW,
                    reviewDraft);
            position = world.snapshot().position();
            assertEquals(reviewed, world.openRoleSession(ED).reviewStory("cr", "crt", now + REVIEW,
                    reviewDraft), "审阅 pending 重试必须返回原回执");
            assertEquals(position, world.snapshot().position());
            advance(world, now + REVIEW);

            // 4 发布：完成后重试
            now = world.snapshot().timeMillis();
            String published = world.openRoleSession(ED).publishStory("cpb", "cpbt", now + PUBLISH,
                    new WorldRoleSession.MediaStoryDraft(storyId, 1));
            advance(world, now + PUBLISH);
            position = world.snapshot().position();
            assertEquals(published, world.openRoleSession(ED).publishStory("cpb", "cpbt",
                    now + PUBLISH,
                    new WorldRoleSession.MediaStoryDraft(storyId, 1)),
                    "发布完成后重试返回原回执");
            assertEquals(position, world.snapshot().position());

            // 5 开机更正：pending 重试
            now = world.snapshot().timeMillis();
            String corrected = world.openRoleSession(RITA).startCorrection("cc", "cct",
                    now + CORRECTION, new WorldRoleSession.MediaStoryDraft(storyId, 1));
            assertEquals(corrected, world.openRoleSession(RITA).startCorrection("cc", "cct",
                    now + CORRECTION, new WorldRoleSession.MediaStoryDraft(storyId, 1)),
                    "更正 pending 重试返回原回执");
            advance(world, now + CORRECTION);

            // 6 取消：新排一项工作后 pending 重试
            now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).submitStory("cs2", "cst2", now + WRITE,
                    new WorldRoleSession.MediaSubmitDraft(storyId, 1, "标题", "正文", "来源",
                            List.of(new WorldRoleSession.MediaCitation(ref.docId(), ref.version(),
                                    ref.index()))));
            long cancelAt = now;
            String cancelled = world.openRoleSession(RITA).cancelMediaWork("ccx", "cct2",
                    cancelAt + CANCEL);
            position = world.snapshot().position();
            assertEquals(cancelled, world.openRoleSession(RITA).cancelMediaWork("ccx", "cct2",
                    cancelAt + CANCEL), "取消 pending 重试返回原回执");
            assertEquals(position, world.snapshot().position(), "取消重试不得改动状态");
            advance(world, cancelAt + CANCEL);
            assertEquals(MediaModel.Status.DRAFT, story(world, MEDIA).status());
        }
    }

    @Test
    void cancellingBudgetRejectedWorkChargesNothing() throws Exception {
        Path journal = directory().resolve("world.journal");
        try (WorldRuntime world = open(journal, 300L)) {
            String ann = publishAnnouncement(world, "erin", "ann-1");
            long now = world.snapshot().timeMillis();
            world.openRoleSession(RITA).proposeStory("p", "pt", now + PROPOSE,
                    new WorldRoleSession.MediaProposeDraft("one", "WIRE", "原始"));
            advance(world, now + PROPOSE);
            assertEquals(300L, media(world).budgetOf(MEDIA).spentMillis());

            // 第二个选题预算不足：排成"预算拒绝"，到期前不占预留、不计费
            long second = world.snapshot().timeMillis();
            world.openRoleSession(RITA).proposeStory("q", "qt", second + PROPOSE,
                    new WorldRoleSession.MediaProposeDraft("two", "WIRE", "超额"));
            assertEquals(300L, media(world).budgetOf(MEDIA).spentMillis(), "排程时不计费");

            // 取消这个被拒任务：0 费用、世界不卡住
            world.openRoleSession(RITA).cancelMediaWork("cx", "cxt", second + CANCEL);
            advance(world, second + CANCEL);
            assertEquals(300L, media(world).budgetOf(MEDIA).spentMillis(), "取消被拒任务不得计费");
            assertEquals(1, state(world, MEDIA).stories().size(), "被拒选题不能出现");
            assertTrue(media(world).worksOf(MEDIA).stream().anyMatch(work ->
                    MediaRun.OUTCOME_CANCELED.equals(work.outcome())
                            && work.chargedMillis() == 0), "取消记录计费为 0");

            // 继续推进到原到期：不抛异常，预算与稿件保持
            advance(world, second + PROPOSE + 1_000);
            assertEquals(300L, media(world).budgetOf(MEDIA).spentMillis());
            assertEquals(1, state(world, MEDIA).stories().size());
        }
        try (WorldRuntime restored = open(journal, 300L)) {
            assertEquals(300L, media(restored).budgetOf(MEDIA).spentMillis(), "重开后预算不变");
            assertEquals(1, state(restored, MEDIA).stories().size(), "重开后仍只有一篇");
        }
    }

    @Test
    void decisionPathBudgetExhaustionIsPersistedAndSurvivesReopen() throws Exception {
        Path journal = directory().resolve("world.journal");
        long budget = 300L;
        try (WorldRuntime world = open(journal, budget)) {
            publishAnnouncement(world, "erin", "ann-1");
            world.beginDecisionSession("session");
            act(world, RITA, "p1", RoleTradingView.TOOL_MEDIA_PROPOSE,
                    Map.of("storyId", "one", "template", "WIRE", "topic", "第一"));
            assertEquals(300L, media(world).budgetOf(MEDIA).spentMillis());
            // 第二次决定：预算不足，走 BUDGET 业务拒绝，不是技术失败
            act(world, RITA, "p2", RoleTradingView.TOOL_MEDIA_PROPOSE,
                    Map.of("storyId", "two", "template", "WIRE", "topic", "第二"));
            MediaRun run = media(world);
            assertEquals(300L, run.budgetOf(MEDIA).spentMillis(), "预算拒绝不得计费");
            assertEquals(1, state(world, MEDIA).stories().size(), "被拒选题不得出现");
            assertTrue(run.reports().values().stream().anyMatch(report ->
                    !report.accepted() && "BUDGET".equals(report.reason())),
                    "决定路径的预算不足必须留下业务拒绝报告");
        }
        try (WorldRuntime restored = open(journal, budget)) {
            assertEquals(300L, media(restored).budgetOf(MEDIA).spentMillis(), "重开后预算不变");
            assertEquals(1, state(restored, MEDIA).stories().size(), "重开后仍只有一篇");
            assertTrue(media(restored).reports().values().stream().anyMatch(report ->
                    !report.accepted() && "BUDGET".equals(report.reason())),
                    "重开后预算拒绝报告仍在");
        }
    }

    @Test
    void mediaConfigMustMatchManifestAndRegistry() throws Exception {
        Path journal = directory().resolve("world.journal");
        MediaRun.Config media = mediaConfig(1_000L);
        IllegalArgumentException withoutConfig = assertThrows(IllegalArgumentException.class,
                () -> new WorldRuntime(journal, manifest(), openings(), registry(),
                        new ManufacturingRun.Config(new ManufacturingModel.Opening("company", "CO",
                                "SUP", "CUS", "OPS"), SYMBOL), charter(), roles(), information(),
                        decisions(media)));
        assertTrue(withoutConfig.getMessage().contains("媒体开局配置"));

        MediaRun.Config unknownOrg = new MediaRun.Config(
                List.of(policy("no-such-media")), Map.of("no-such-media", 1_000L),
                PROPOSE, CORRECTION, CANCEL);
        IllegalArgumentException badOrg = assertThrows(IllegalArgumentException.class,
                () -> new WorldRuntime(directory().resolve("w2.journal"), manifest(), openings(),
                        registry(), new ManufacturingRun.Config(new ManufacturingModel.Opening(
                        "company", "CO", "SUP", "CUS", "OPS"), SYMBOL), charter(), roles(),
                        information(), decisions(unknownOrg), unknownOrg));
        assertTrue(badOrg.getMessage().contains("媒体组织"));
    }
}
