package edu.cufe.auction.world;

import edu.cufe.auction.world.InformationModel.Audience;
import edu.cufe.auction.world.InformationModel.Document;
import edu.cufe.auction.world.InformationModel.DocumentVersion;
import edu.cufe.auction.world.InformationModel.ReadObservation;
import edu.cufe.auction.world.InformationModel.Status;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.RoleAccess.Action;
import edu.cufe.auction.world.RoleAccess.Appointment;
import edu.cufe.auction.world.RoleAccess.Decision;
import edu.cufe.auction.world.RoleAccess.Role;
import edu.cufe.auction.world.RoleAccess.Target;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4h.1 公开证据采编纯核：
 * 双人快讯/两来源调查全链、更正与转载谱系、以及未读/错序号/遗忘/同源/时间/状态等拒绝。
 *
 * <p>全部使用真实 {@link InformationModel} 发布与阅读、真实 {@link RoleAccess} 任职，
 * 不手造 {@link ReadObservation}，也不直接改媒体状态。</p>
 */
class MediaModelTest {

    private static final String MEDIA = "media-a";
    private static final String OTHER_MEDIA = "media-b";
    private static final String COMPANY = "company";
    private static final String REPORTER = "reporter";
    private static final String EDITOR = "editor";
    private static final String EDITOR2 = "editor2";
    private static final String OTHER_EDITOR = "other-editor";
    private static final String OUTSIDER = "outsider";
    private static final String CHAIR = "chair";
    private static final String CFO = "cfo";

    private static final long WRITE = 1_000L;
    private static final long REVIEW = 2_000L;
    private static final long PUBLISH = 3_000L;
    private static final long DRAFT_READ = 100L;
    private static final long PUBLIC_READ = 200L;
    private static final long PRIMARY_READ = 100L;
    private static final long END = 10_000_000L;

    // ---------------------------------------------------------------- 夹具

    private static OpeningRegistry registry() {
        return new OpeningRegistry(List.of(
                new Party(MEDIA, PartyKind.ORGANIZATION, "实验快讯社"),
                new Party(OTHER_MEDIA, PartyKind.ORGANIZATION, "另一家媒体"),
                new Party(COMPANY, PartyKind.ORGANIZATION, "实验公司"),
                new Party(REPORTER, PartyKind.PERSON, "记者"),
                new Party(EDITOR, PartyKind.PERSON, "编辑甲"),
                new Party(EDITOR2, PartyKind.PERSON, "编辑乙"),
                new Party(OTHER_EDITOR, PartyKind.PERSON, "外部编辑"),
                new Party(OUTSIDER, PartyKind.PERSON, "无关读者"),
                new Party(CHAIR, PartyKind.PERSON, "公司董事长"),
                new Party(CFO, PartyKind.PERSON, "公司财务负责人")),
                List.of(), List.of(), List.of());
    }

    private static RoleAccess.State roles() {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-REPORTER", REPORTER, MEDIA, Role.JOURNALIST, 0L, END, null),
                new Appointment("AP-EDITOR", EDITOR, MEDIA, Role.EDITOR, 0L, END, null),
                new Appointment("AP-OTHER", OTHER_EDITOR, OTHER_MEDIA, Role.EDITOR, 0L, END, null)));
    }

    private static CognitionRecall.Policy recall() {
        return new CognitionRecall.Policy(1_000_000L, 1, 100, 1_000_000L);
    }

    private static MediaModel.Policy policy() {
        return new MediaModel.Policy(MEDIA,
                Map.of(MediaModel.TemplateKind.WIRE, 1,
                        MediaModel.TemplateKind.INVESTIGATIVE, 2),
                WRITE, REVIEW, PUBLISH, DRAFT_READ, PUBLIC_READ, recall());
    }

    /** 记忆很快衰减的档案，用于「引用已遗忘」。 */
    private static MediaModel.Policy forgetfulPolicy() {
        return new MediaModel.Policy(MEDIA,
                Map.of(MediaModel.TemplateKind.WIRE, 1,
                        MediaModel.TemplateKind.INVESTIGATIVE, 2),
                WRITE, REVIEW, PUBLISH, DRAFT_READ, PUBLIC_READ,
                new CognitionRecall.Policy(1L, 1_000, 100, 1_000_000L));
    }

    private static InformationModel.State information() {
        return InformationModel.open(registry(),
                List.of(REPORTER, EDITOR, EDITOR2, OTHER_EDITOR, OUTSIDER, CHAIR, CFO));
    }

    /** 公司公开资料：真实 InformationModel 文档，发布者为真实人物。 */
    private static InformationModel.State primary(InformationModel.State info, String docId,
                                                  String publisher, long publishedAt) {
        return InformationModel.publish(info, docId, publisher, Audience.publicAudience(),
                new InformationModel.VersionDraft("公告 " + docId, "正文 " + docId, "公司公告",
                        publishedAt, PRIMARY_READ, List.of(docId)));
    }

    /** 真实读完某文档某版本（走 startRead/completeRead，不手造经历）。 */
    private static InformationModel.State read(InformationModel.State info, RoleAccess.State access,
                                               String person, String docId, long version, long start) {
        InformationModel.Outcome started = InformationModel.startRead(info, access, person, docId,
                version, start);
        assertEquals(Status.STARTED, started.status(), "开始阅读应成功");
        InformationModel.Reading reading = null;
        for (InformationModel.Reading candidate : started.state().active()) {
            if (candidate.personPartyId().equals(person)) {
                reading = candidate;
            }
        }
        assertNotNull(reading, "应存在活动阅读");
        InformationModel.Outcome done = InformationModel.completeRead(started.state(), access, person,
                reading.readId(), reading.dueAt());
        assertEquals(Status.COMPLETED, done.status(), "到期完成应成功");
        return done.state();
    }

    /** 本人阅读序号：与 WorldRuntime.readingOrdinals 同一口径。 */
    private static int ordinal(InformationModel.State info, String person, String readId) {
        List<String> ids = new ArrayList<>();
        for (InformationModel.Reading reading : info.active()) {
            if (reading.personPartyId().equals(person)) {
                ids.add(reading.readId());
            }
        }
        for (InformationModel.ClosedReading closed : info.closed()) {
            if (closed.personPartyId().equals(person)) {
                ids.add(closed.readId());
            }
        }
        ids.sort(Comparator.comparingLong(MediaModelTest::sequence)
                .thenComparing(Comparator.naturalOrder()));
        return ids.indexOf(readId) + 1;
    }

    private static long sequence(String readId) {
        if (readId == null || !readId.startsWith("READ-")) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(readId.substring("READ-".length()));
        } catch (NumberFormatException notNumeric) {
            return Long.MAX_VALUE;
        }
    }

    /** 本人对某固定版本的真实引用（序号来自真实阅读记录）。 */
    private static RoleView.ReadingRef ref(InformationModel.State info, String person,
                                           String docId, long version) {
        for (ReadObservation observation : info.observations()) {
            if (observation.personPartyId().equals(person)
                    && observation.docId().equals(docId)
                    && observation.version() == version) {
                return new RoleView.ReadingRef(person, docId, version,
                        ordinal(info, person, observation.readId()));
            }
        }
        throw new IllegalStateException("没有该人物的真实阅读经历：" + person + " -> " + docId
                + " v" + version);
    }

    private static DocumentVersion version(InformationModel.State info, String docId, long version) {
        for (Document document : info.documents()) {
            if (document.docId().equals(docId)) {
                return document.at(version);
            }
        }
        throw new IllegalStateException("资料不存在：" + docId);
    }

    private static long versionCount(InformationModel.State info, String docId) {
        for (Document document : info.documents()) {
            if (document.docId().equals(docId)) {
                return document.versions().size();
            }
        }
        throw new IllegalStateException("资料不存在：" + docId);
    }

    private static MediaModel.Story story(MediaModel.State state, String storyId) {
        for (MediaModel.Story candidate : state.stories()) {
            if (candidate.storyId().equals(storyId)) {
                return candidate;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 正常链

    @Test
    @DisplayName("双人快讯全链：记者证据→组织草稿→编辑读完→批准→公开成稿")
    void wireFlowPublishesApprovedDraft() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);

        MediaModel.State media = MediaModel.open(policy(), registry());
        MediaModel.Outcome proposed = MediaModel.propose(media, policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-WIRE", MediaModel.TemplateKind.WIRE,
                        "公司公告解读", 3_000L));
        assertTrue(proposed.accepted(), proposed.reason());
        media = proposed.state();
        assertEquals(MediaModel.Status.DRAFT, story(media, "S-WIRE").status());

        RoleView.ReadingRef evidence = ref(info, REPORTER, "D-CO", 1);
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-WIRE", 0, "快讯标题", "快讯正文",
                        "据公司公告", List.of(evidence), 4_000L, 4_000L + WRITE), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        media = submitted.state();
        info = submitted.information();

        MediaModel.Story submittedStory = story(media, "S-WIRE");
        assertEquals(MediaModel.Status.SUBMITTED, submittedStory.status());
        assertEquals(1, submittedStory.revisionNo());
        assertEquals(1, submittedStory.sourceGroups(), "单一发布人物的公告算 1 组");
        Document draft = document(info, submittedStory.draftDocId());
        assertEquals(Audience.organization(MEDIA), draft.audience(), "草稿只投给媒体组织");
        assertEquals(REPORTER, draft.publisherPartyId(), "草稿署名为记者本人");

        // 编辑未读草稿时看不到正文
        List<MediaModel.StoryView> editorViews = MediaModel.view(media, access, info, EDITOR, 5_000L);
        assertEquals(1, editorViews.size());
        assertFalse(editorViews.get(0).draftRead(), "编辑尚未读完草稿");
        assertNull(editorViews.get(0).draftBody(), "未读草稿不能拿到全文");

        // 未读就批准必须拒绝
        MediaModel.Outcome tooEarly = MediaModel.review(media, policy(), access, info, EDITOR,
                new MediaModel.ReviewRequest("S-WIRE", 1, true, "", 5_000L, 5_000L + REVIEW));
        assertFalse(tooEarly.accepted());
        assertEquals(MediaModel.REASON_DRAFT_NOT_READ, tooEarly.reason());

        info = read(info, access, EDITOR, submittedStory.draftDocId(), submittedStory.draftVersion(),
                5_100L);
        MediaModel.Outcome approved = MediaModel.review(media, policy(), access, info, EDITOR,
                new MediaModel.ReviewRequest("S-WIRE", 1, true, "", 6_000L, 6_000L + REVIEW));
        assertTrue(approved.accepted(), approved.reason());
        media = approved.state();
        assertEquals(MediaModel.Status.APPROVED, story(media, "S-WIRE").status());
        assertEquals(EDITOR, story(media, "S-WIRE").approvedBy());

        MediaModel.BridgeOutcome published = MediaModel.publish(media, policy(), access, info, EDITOR,
                new MediaModel.PublishRequest("S-WIRE", 1, 9_000L, 9_000L + PUBLISH));
        assertTrue(published.accepted(), published.reason());
        media = published.state();
        info = published.information();

        MediaModel.Story publishedStory = story(media, "S-WIRE");
        assertEquals(MediaModel.Status.PUBLISHED, publishedStory.status());
        assertEquals(1, publishedStory.publicVersion());
        Document publicDoc = document(info, publishedStory.publicDocId());
        assertEquals(Audience.publicAudience(), publicDoc.audience(), "成稿公开");
        assertEquals(REPORTER, publicDoc.publisherPartyId(), "公开署名是记者，不是编辑");
        assertEquals("快讯正文", publicDoc.versions().get(0).body(), "公开正文取自已批准草稿");
        assertEquals(PUBLIC_READ, publicDoc.versions().get(0).readDurationMillis());
        assertEquals(1, publishedStory.roots().size());

        // 读者按真实阅读获得公开成稿（成稿发布时刻 = 发布任务完成时刻 12_000）
        InformationModel.State after = read(info, access, OUTSIDER, publishedStory.publicDocId(), 1,
                13_000L);
        assertTrue(InformationModel.canCite(after, OUTSIDER, publishedStory.publicDocId(), 1, 20_000L));
    }

    @Test
    @DisplayName("两来源调查：两个不同发布人物才算两组")
    void investigativeNeedsTwoSourceGroups() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = primary(info, "D-CFO", CFO, 1_100L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        info = read(info, access, REPORTER, "D-CFO", 1, 3_000L);

        MediaModel.State media = MediaModel.open(policy(), registry());
        media = MediaModel.propose(media, policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-INV", MediaModel.TemplateKind.INVESTIGATIVE,
                        "订单调查", 3_500L)).state();

        List<RoleView.ReadingRef> evidence = List.of(ref(info, REPORTER, "D-CO", 1),
                ref(info, REPORTER, "D-CFO", 1));
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-INV", 0, "调查标题", "调查正文",
                        "据公司公告与财务口径", evidence, 4_000L, 4_000L + WRITE), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        assertEquals(2, story(submitted.state(), "S-INV").sourceGroups());
    }

    @Test
    @DisplayName("调查只有一个发布人物的两份资料：不能凑两组")
    void samePublisherTwoDocsCountAsOneGroup() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO-1", CHAIR, 1_000L);
        info = primary(info, "D-CO-2", CHAIR, 1_100L);
        info = read(info, access, REPORTER, "D-CO-1", 1, 2_000L);
        info = read(info, access, REPORTER, "D-CO-2", 1, 3_000L);

        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-SAME", MediaModel.TemplateKind.INVESTIGATIVE,
                        "同源调查", 3_500L)).state();
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-SAME", 0, "标题", "正文", "据公司公告",
                        List.of(ref(info, REPORTER, "D-CO-1", 1),
                                ref(info, REPORTER, "D-CO-2", 1)),
                        4_000L, 4_000L + WRITE), List.of());
        assertFalse(submitted.accepted(), "同一发布人物的两份资料不是两组独立来源");
        assertEquals(MediaModel.REASON_INSUFFICIENT_SOURCES, submitted.reason());
        assertEquals(media, submitted.state(), "拒绝不改状态");
    }

    // ---------------------------------------------------------------- 证据负例

    @Test
    @DisplayName("未读证据：只用标题或他人经历都不能提交")
    void unreadEvidenceRejected() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        RoleView.ReadingRef otherPersonRef = ref(info, REPORTER, "D-CO", 1);

        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-UNREAD", MediaModel.TemplateKind.WIRE, "未读",
                        3_000L)).state();
        // 把引用伪造成别人
        RoleView.ReadingRef forged = new RoleView.ReadingRef(EDITOR, otherPersonRef.docId(),
                otherPersonRef.version(), otherPersonRef.index());
        MediaModel.BridgeOutcome rejected = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-UNREAD", 0, "标题", "正文", "来源",
                        List.of(forged), 4_000L, 4_000L + WRITE), List.of());
        assertFalse(rejected.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE, rejected.reason());
    }

    @Test
    @DisplayName("错序号与未来版本：序号必须是本人真实阅读序号")
    void wrongOrdinalAndFutureVersionRejected() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        RoleView.ReadingRef good = ref(info, REPORTER, "D-CO", 1);

        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-ORD", MediaModel.TemplateKind.WIRE, "序号",
                        3_000L)).state();
        MediaModel.BridgeOutcome wrongOrdinal = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-ORD", 0, "标题", "正文", "来源",
                        List.of(new RoleView.ReadingRef(REPORTER, good.docId(), good.version(),
                                good.index() + 1)), 4_000L, 4_000L + WRITE), List.of());
        assertFalse(wrongOrdinal.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_INDEX, wrongOrdinal.reason());

        // 未来版本：资料 v2 在 9_000 才发布，4_000 时不能引用
        InformationModel.State future = InformationModel.publishVersion(info, "D-CO",
                new InformationModel.VersionDraft("公告 v2", "正文 v2", "公司公告", 9_000L,
                        PRIMARY_READ, List.of()));
        MediaModel.BridgeOutcome futureRef = MediaModel.submit(media, policy(), access, future,
                REPORTER, new MediaModel.SubmitRequest("S-ORD", 0, "标题", "正文", "来源",
                        List.of(new RoleView.ReadingRef(REPORTER, "D-CO", 2, 1)),
                        4_000L, 4_000L + WRITE), List.of());
        assertFalse(futureRef.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_NOT_READ, futureRef.reason());
    }

    @Test
    @DisplayName("已遗忘的经历不能当证据")
    void forgottenEvidenceRejected() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);

        MediaModel.State media = MediaModel.propose(MediaModel.open(forgetfulPolicy(), registry()),
                forgetfulPolicy(), access,
                REPORTER, new MediaModel.ProposeRequest("S-FORGET", MediaModel.TemplateKind.WIRE,
                        "遗忘", 3_000L)).state();
        MediaModel.BridgeOutcome rejected = MediaModel.submit(media, forgetfulPolicy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-FORGET", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, "D-CO", 1)), 60_000L, 60_000L + WRITE), List.of());
        assertFalse(rejected.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_NOT_RECALLABLE, rejected.reason());
    }

    @Test
    @DisplayName("非公开证据：组织资料与直投资料不能经媒体入口公开")
    void nonPublicEvidenceRejected() {
        RoleAccess.State access = roles();
        InformationModel.State info = InformationModel.publish(information(), "D-ORG", CHAIR,
                Audience.organization(MEDIA),
                new InformationModel.VersionDraft("内部纪要", "内部正文", "内部", 1_000L,
                        PRIMARY_READ, List.of()));
        info = read(info, access, REPORTER, "D-ORG", 1, 2_000L);

        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-PRIV", MediaModel.TemplateKind.WIRE, "内部",
                        3_000L)).state();
        MediaModel.BridgeOutcome rejected = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-PRIV", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, "D-ORG", 1)), 4_000L, 4_000L + WRITE), List.of());
        assertFalse(rejected.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_NOT_PUBLIC, rejected.reason());
    }

    // ---------------------------------------------------------------- 权限负例

    @Test
    @DisplayName("作者不能自批：同机构不可兼岗，审阅阶段直接无权限")
    void authorCannotApproveOwnDraft() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-SELF", MediaModel.TemplateKind.WIRE, "自批",
                        3_000L)).state();
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-SELF", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, "D-CO", 1)), 4_000L, 4_000L + WRITE), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        MediaModel.Story current = story(submitted.state(), "S-SELF");
        info = read(submitted.information(), access, REPORTER, current.draftDocId(),
                current.draftVersion(), 5_100L);

        MediaModel.Outcome selfApprove = MediaModel.review(submitted.state(), policy(), access, info,
                REPORTER, new MediaModel.ReviewRequest("S-SELF", 1, true, "", 6_000L,
                        6_000L + REVIEW));
        assertFalse(selfApprove.accepted());
        assertEquals(MediaModel.REASON_NOT_AUTHORIZED, selfApprove.reason());
    }

    @Test
    @DisplayName("撤职后不能批准；跨媒体编辑也不能批准")
    void revokedAndCrossOrganizationEditorRejected() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-REVOKE", MediaModel.TemplateKind.WIRE, "撤职",
                        3_000L)).state();
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-REVOKE", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, "D-CO", 1)), 4_000L, 4_000L + WRITE), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        MediaModel.Story current = story(submitted.state(), "S-REVOKE");
        info = read(submitted.information(), access, EDITOR, current.draftDocId(),
                current.draftVersion(), 5_100L);

        RoleAccess.State revoked = RoleAccess.revoke(access, "AP-EDITOR", 5_500L);
        MediaModel.Outcome afterRevoke = MediaModel.review(submitted.state(), policy(), revoked, info,
                EDITOR, new MediaModel.ReviewRequest("S-REVOKE", 1, true, "", 6_000L,
                        6_000L + REVIEW));
        assertFalse(afterRevoke.accepted());
        assertEquals(MediaModel.REASON_NOT_AUTHORIZED, afterRevoke.reason());

        MediaModel.Outcome crossOrg = MediaModel.review(submitted.state(), policy(), access, info,
                OTHER_EDITOR, new MediaModel.ReviewRequest("S-REVOKE", 1, true, "", 6_000L,
                        6_000L + REVIEW));
        assertFalse(crossOrg.accepted());
        assertEquals(MediaModel.REASON_NOT_AUTHORIZED, crossOrg.reason());
    }

    @Test
    @DisplayName("职务到期后批准被拒，但已读经历仍可用于查看")
    void expiredAppointmentRejected() {
        RoleAccess.State base = roles();
        RoleAccess.State expiring = new RoleAccess.State(base.accounts(), base.personAccounts(),
                base.mandates(), List.of(
                new Appointment("AP-REPORTER", REPORTER, MEDIA, Role.JOURNALIST, 0L, END, null),
                new Appointment("AP-EDITOR", EDITOR, MEDIA, Role.EDITOR, 0L, 5_200L, null),
                new Appointment("AP-EDITOR2", EDITOR2, MEDIA, Role.EDITOR, 5_200L, END, null)));
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, expiring, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), expiring, REPORTER,
                new MediaModel.ProposeRequest("S-EXPIRE", MediaModel.TemplateKind.WIRE, "到期",
                        3_000L)).state();
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), expiring, info,
                REPORTER, new MediaModel.SubmitRequest("S-EXPIRE", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, "D-CO", 1)), 4_000L, 4_000L + WRITE), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        MediaModel.Story current = story(submitted.state(), "S-EXPIRE");
        info = read(submitted.information(), expiring, EDITOR, current.draftDocId(),
                current.draftVersion(), 5_000L);

        // 5_200 到期（右开），6_000 时无任职
        MediaModel.Outcome expired = MediaModel.review(submitted.state(), policy(), expiring, info,
                EDITOR, new MediaModel.ReviewRequest("S-EXPIRE", 1, true, "", 6_000L,
                        6_000L + REVIEW));
        assertFalse(expired.accepted());
        assertEquals(MediaModel.REASON_NOT_AUTHORIZED, expired.reason());
        // 已完成的阅读经历不因离任消失
        assertTrue(InformationModel.canCite(info, EDITOR, current.draftDocId(),
                current.draftVersion(), 6_000L));
    }

    // ---------------------------------------------------------------- 状态与时间

    @Test
    @DisplayName("重复提交/重复发布/非法状态：业务拒绝不抛异常")
    void duplicateAndIllegalStateRejected() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-DUP", MediaModel.TemplateKind.WIRE, "重复",
                        3_000L)).state();

        MediaModel.Outcome duplicateStory = MediaModel.propose(media, policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-DUP", MediaModel.TemplateKind.WIRE, "重复",
                        3_100L));
        assertFalse(duplicateStory.accepted());
        assertEquals(MediaModel.REASON_DUPLICATE, duplicateStory.reason());

        MediaModel.Outcome reviewDraft = MediaModel.review(media, policy(), access, info, EDITOR,
                new MediaModel.ReviewRequest("S-DUP", 1, true, "", 4_000L, 4_000L + REVIEW));
        assertFalse(reviewDraft.accepted());
        assertEquals(MediaModel.REASON_STATE, reviewDraft.reason());

        RoleView.ReadingRef evidence = ref(info, REPORTER, "D-CO", 1);
        MediaModel.BridgeOutcome first = MediaModel.submit(media, policy(), access, info, REPORTER,
                new MediaModel.SubmitRequest("S-DUP", 0, "标题", "正文", "来源", List.of(evidence),
                        4_000L, 4_000L + WRITE), List.of());
        assertTrue(first.accepted(), first.reason());
        MediaModel.BridgeOutcome again = MediaModel.submit(first.state(), policy(), access,
                first.information(), REPORTER,
                new MediaModel.SubmitRequest("S-DUP", 0, "标题", "正文", "来源", List.of(evidence),
                        5_000L, 5_000L + WRITE), List.of());
        assertFalse(again.accepted());
        assertEquals(MediaModel.REASON_STATE, again.reason());

        MediaModel.Outcome unknown = MediaModel.review(first.state(), policy(), access,
                first.information(), EDITOR,
                new MediaModel.ReviewRequest("S-NOPE", 1, true, "", 6_000L, 6_000L + REVIEW));
        assertFalse(unknown.accepted());
        assertEquals(MediaModel.REASON_UNKNOWN_STORY, unknown.reason());
    }

    @Test
    @DisplayName("时间：耗时必须等于配置、不能倒退、不能溢出")
    void timeRulesEnforced() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-TIME", MediaModel.TemplateKind.WIRE, "时间",
                        3_000L)).state();
        RoleView.ReadingRef evidence = ref(info, REPORTER, "D-CO", 1);

        MediaModel.BridgeOutcome tooFast = MediaModel.submit(media, policy(), access, info, REPORTER,
                new MediaModel.SubmitRequest("S-TIME", 0, "标题", "正文", "来源", List.of(evidence),
                        4_000L, 4_500L), List.of());
        assertFalse(tooFast.accepted());
        assertEquals(MediaModel.REASON_TIME, tooFast.reason(), "耗时必须等于配置的写作耗时");

        MediaModel.BridgeOutcome backwards = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-TIME", 0, "标题", "正文", "来源",
                        List.of(evidence), 2_000L, 2_000L + WRITE), List.of());
        assertFalse(backwards.accepted());
        assertEquals(MediaModel.REASON_TIME, backwards.reason(), "不能早于稿件上一事件");

        MediaModel.BridgeOutcome overflow = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-TIME", 0, "标题", "正文", "来源",
                        List.of(evidence), Long.MAX_VALUE - 1, Long.MAX_VALUE), List.of());
        assertFalse(overflow.accepted());
        assertEquals(MediaModel.REASON_TIME, overflow.reason());
    }

    @Test
    @DisplayName("非法模板：未启用模板不能建稿，配置也不能启用")
    void templateGateEnforced() {
        RoleAccess.State access = roles();
        assertThrows(IllegalArgumentException.class, () -> new MediaModel.Policy(MEDIA,
                Map.of(MediaModel.TemplateKind.MARKETS, 1), WRITE, REVIEW, PUBLISH,
                DRAFT_READ, PUBLIC_READ, recall()), "本阶段只能启用 WIRE/INVESTIGATIVE");

        MediaModel.Outcome refused = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access,
                REPORTER, new MediaModel.ProposeRequest("S-MKT", MediaModel.TemplateKind.MARKETS,
                        "行情", 3_000L));
        assertFalse(refused.accepted());
        assertEquals(MediaModel.REASON_TEMPLATE_NOT_ENABLED, refused.reason());
    }

    @Test
    @DisplayName("换稿与旧批准：批准后不能再提交；发布不接受正文参数")
    void approvalCannotBeSwapped() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-SWAP", MediaModel.TemplateKind.WIRE, "换稿",
                        3_000L)).state();
        RoleView.ReadingRef evidence = ref(info, REPORTER, "D-CO", 1);
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-SWAP", 0, "原题", "原文", "来源",
                        List.of(evidence), 4_000L, 4_000L + WRITE), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        MediaModel.Story current = story(submitted.state(), "S-SWAP");
        info = read(submitted.information(), access, EDITOR, current.draftDocId(),
                current.draftVersion(), 5_100L);
        MediaModel.Outcome approved = MediaModel.review(submitted.state(), policy(), access, info,
                EDITOR, new MediaModel.ReviewRequest("S-SWAP", 1, true, "", 6_000L,
                        6_000L + REVIEW));
        assertTrue(approved.accepted(), approved.reason());

        MediaModel.BridgeOutcome resubmit = MediaModel.submit(approved.state(), policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-SWAP", 1, "原题", "改过的正文", "来源",
                        List.of(evidence), 7_000L, 7_000L + WRITE), List.of());
        assertFalse(resubmit.accepted(), "批准后不能再提交，必须走更正轮");
        assertEquals(MediaModel.REASON_STATE, resubmit.reason());

        MediaModel.BridgeOutcome published = MediaModel.publish(approved.state(), policy(), access,
                info, EDITOR, new MediaModel.PublishRequest("S-SWAP", 1, 9_000L, 9_000L + PUBLISH));
        assertTrue(published.accepted(), published.reason());
        String publicDocId = story(published.state(), "S-SWAP").publicDocId();
        assertEquals("原文", version(published.information(), publicDocId, 1).body(),
                "公开正文只能来自已批准草稿");

        MediaModel.BridgeOutcome staleApproval = MediaModel.publish(published.state(), policy(), access,
                published.information(), EDITOR,
                new MediaModel.PublishRequest("S-SWAP", 2, 12_000L, 12_000L + PUBLISH));
        assertFalse(staleApproval.accepted());
        assertEquals(MediaModel.REASON_STATE, staleApproval.reason());
    }

    @Test
    @DisplayName("退回：必须给理由，重新提交形成新版本，旧批准失效")
    void returnThenResubmitCreatesNewDraftVersion() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-RET", MediaModel.TemplateKind.INVESTIGATIVE,
                        "退回", 3_000L)).state();
        info = primary(info, "D-CFO", CFO, 1_100L);
        info = read(info, access, REPORTER, "D-CFO", 1, 3_000L);
        List<RoleView.ReadingRef> evidence = List.of(ref(info, REPORTER, "D-CO", 1),
                ref(info, REPORTER, "D-CFO", 1));

        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-RET", 0, "初稿", "初稿正文", "来源",
                        evidence, 4_000L, 4_000L + WRITE), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        MediaModel.Story first = story(submitted.state(), "S-RET");
        info = read(submitted.information(), access, EDITOR, first.draftDocId(),
                first.draftVersion(), 5_100L);

        MediaModel.Outcome blankReturn = MediaModel.review(submitted.state(), policy(), access, info,
                EDITOR, new MediaModel.ReviewRequest("S-RET", 1, false, "  ", 6_000L,
                        6_000L + REVIEW));
        assertFalse(blankReturn.accepted(), "退回必须写明理由");

        MediaModel.Outcome returned = MediaModel.review(submitted.state(), policy(), access, info,
                EDITOR, new MediaModel.ReviewRequest("S-RET", 1, false, "缺少第二来源核实",
                        6_000L, 6_000L + REVIEW));
        assertTrue(returned.accepted(), returned.reason());
        assertEquals(MediaModel.Status.RETURNED, story(returned.state(), "S-RET").status());

        MediaModel.BridgeOutcome resubmitted = MediaModel.submit(returned.state(), policy(), access,
                info, REPORTER, new MediaModel.SubmitRequest("S-RET", 1, "二稿", "二稿正文", "来源",
                        evidence, 8_000L, 8_000L + WRITE), List.of());
        assertTrue(resubmitted.accepted(), resubmitted.reason());
        MediaModel.Story second = story(resubmitted.state(), "S-RET");
        assertEquals(2, second.revisionNo());
        assertEquals(2, second.draftVersion(), "组织草稿追加版本，不覆盖旧稿");
        assertEquals(0, second.approvedRevision(), "旧批准被清除");
        assertNull(second.approvedBy());

        // 编辑只读过 v1，不能凭旧经历批准 v2
        MediaModel.Outcome stale = MediaModel.review(resubmitted.state(), policy(), access,
                resubmitted.information(), EDITOR,
                new MediaModel.ReviewRequest("S-RET", 2, true, "", 9_000L, 9_000L + REVIEW));
        assertFalse(stale.accepted());
        assertEquals(MediaModel.REASON_DRAFT_NOT_READ, stale.reason());
    }

    // ---------------------------------------------------------------- 更正与谱系

    @Test
    @DisplayName("更正：同一公开 docId 追加版本，旧读者经历不变")
    void correctionAppendsVersionWithoutTouchingOldReaders() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-COR", MediaModel.TemplateKind.WIRE, "更正",
                        3_000L)).state();
        RoleView.ReadingRef evidence = ref(info, REPORTER, "D-CO", 1);
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-COR", 0, "首版", "首版正文", "来源",
                        List.of(evidence), 4_000L, 4_000L + WRITE), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        MediaModel.Story current = story(submitted.state(), "S-COR");
        info = read(submitted.information(), access, EDITOR, current.draftDocId(),
                current.draftVersion(), 5_100L);
        MediaModel.Outcome approved = MediaModel.review(submitted.state(), policy(), access, info,
                EDITOR, new MediaModel.ReviewRequest("S-COR", 1, true, "", 6_000L,
                        6_000L + REVIEW));
        assertTrue(approved.accepted(), approved.reason());
        MediaModel.BridgeOutcome published = MediaModel.publish(approved.state(), policy(), access,
                info, EDITOR, new MediaModel.PublishRequest("S-COR", 1, 9_000L, 9_000L + PUBLISH));
        assertTrue(published.accepted(), published.reason());
        media = published.state();
        info = published.information();
        String publicDocId = story(media, "S-COR").publicDocId();

        // 读者先读公开 v1
        info = read(info, access, OUTSIDER, publicDocId, 1, 13_000L);

        MediaModel.Outcome correction = MediaModel.startCorrection(media, policy(), access, REPORTER,
                new MediaModel.CorrectionRequest("S-COR", 1, 20_000L));
        assertTrue(correction.accepted(), correction.reason());
        assertEquals(MediaModel.Status.DRAFT, story(correction.state(), "S-COR").status());

        MediaModel.BridgeOutcome resubmitted = MediaModel.submit(correction.state(), policy(), access,
                info, REPORTER, new MediaModel.SubmitRequest("S-COR", 1, "更正", "更正后正文", "来源",
                        List.of(evidence), 21_000L, 21_000L + WRITE), List.of());
        assertTrue(resubmitted.accepted(), resubmitted.reason());
        info = resubmitted.information();
        MediaModel.Story corrected = story(resubmitted.state(), "S-COR");
        assertEquals(2, corrected.revisionNo());
        assertEquals(2, corrected.draftVersion());
        info = read(info, access, EDITOR, corrected.draftDocId(), corrected.draftVersion(), 22_100L);
        MediaModel.Outcome approved2 = MediaModel.review(resubmitted.state(), policy(), access, info,
                EDITOR, new MediaModel.ReviewRequest("S-COR", 2, true, "", 23_000L,
                        23_000L + REVIEW));
        assertTrue(approved2.accepted(), approved2.reason());
        MediaModel.BridgeOutcome published2 = MediaModel.publish(approved2.state(), policy(), access,
                info, EDITOR, new MediaModel.PublishRequest("S-COR", 2, 26_000L,
                        26_000L + PUBLISH));
        assertTrue(published2.accepted(), published2.reason());
        assertEquals(2, story(published2.state(), "S-COR").publicVersion());
        assertEquals(2, versionCount(published2.information(), publicDocId), "同一公开 docId 追加版本");
        assertEquals("首版正文", version(published2.information(), publicDocId, 1).body(),
                "旧版保留");
        assertEquals("更正后正文", version(published2.information(), publicDocId, 2).body());
        assertEquals(REPORTER, document(published2.information(), publicDocId).publisherPartyId(),
                "InformationModel 首版发布者不冒充本次编辑");
        MediaModel.Revision correctionPublish = null;
        for (MediaModel.Revision revision : published2.state().revisions()) {
            if (revision.storyId().equals("S-COR") && revision.revisionNo() == 2
                    && revision.status() == MediaModel.Status.PUBLISHED) {
                correctionPublish = revision;
            }
        }
        assertNotNull(correctionPublish, "更正发布必须留记录");
        assertEquals(EDITOR, correctionPublish.actorPartyId(), "本次实际编辑写在发布记录");
        // 旧读者仍只拥有 v1
        assertTrue(InformationModel.canCite(published2.information(), OUTSIDER, publicDocId, 1,
                30_000L));
        assertFalse(InformationModel.canCite(published2.information(), OUTSIDER, publicDocId, 2,
                30_000L), "更正发布不自动刷新别人的已读版本");
    }

    @Test
    @DisplayName("转载谱系：同源转载不增加组，多源转载保留全部根")
    void reprintLineageKeepsRootsWithoutInflatingGroups() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = primary(info, "D-CFO", CFO, 1_100L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        info = read(info, access, REPORTER, "D-CFO", 1, 3_000L);

        // 单源快讯：只有一个发布人物的公告
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-ORIG", MediaModel.TemplateKind.WIRE,
                        "原始快讯", 3_500L)).state();
        Published originPublished = publishWith(media, info, access, "S-ORIG", 4_000L,
                List.of(ref(info, REPORTER, "D-CO", 1)));
        media = originPublished.media();
        info = originPublished.information();
        MediaModel.Story origin = story(media, "S-ORIG");
        assertEquals(1, origin.sourceGroups());
        assertEquals(1, origin.roots().size());
        String originDocId = origin.publicDocId();

        // 转载：引用已发布报道，根组继承（仍是 1 个原始根，不是新的独立来源）
        info = read(info, access, REPORTER, originDocId, 1, 30_000L);
        MediaModel.State reprint = MediaModel.propose(media, policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-REPRINT", MediaModel.TemplateKind.WIRE,
                        "转载", 31_000L)).state();
        Published second = publishWith(reprint, info, access, "S-REPRINT", 32_000L,
                List.of(ref(info, REPORTER, originDocId, 1)));
        reprint = second.media();
        info = second.information();
        MediaModel.Story reprintStory = story(reprint, "S-REPRINT");
        assertEquals(1, reprintStory.sourceGroups(), "转载继承原始根，不新增来源");
        assertEquals(origin.roots(), reprintStory.roots());

        // 原报道 + 它的转载仍只有一组：调查模板要求两组，必须拒绝
        info = read(info, access, REPORTER, reprintStory.publicDocId(), 1, 40_000L);
        MediaModel.State third = MediaModel.propose(reprint, policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-DOUBLE", MediaModel.TemplateKind.INVESTIGATIVE,
                        "同源双证", 41_000L)).state();
        MediaModel.BridgeOutcome rejected = MediaModel.submit(third, policy(), access, info, REPORTER,
                new MediaModel.SubmitRequest("S-DOUBLE", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, originDocId, 1),
                                ref(info, REPORTER, reprintStory.publicDocId(), 1)),
                        42_000L, 42_000L + WRITE), List.of());
        assertFalse(rejected.accepted(), "原报道与它的转载不能凑两组");
        assertEquals(MediaModel.REASON_INSUFFICIENT_SOURCES, rejected.reason());

        // 多源：转载报道 + 另一个发布人物的原始资料，两个根都保留
        MediaModel.State multi = MediaModel.propose(reprint, policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-MULTI", MediaModel.TemplateKind.INVESTIGATIVE,
                        "多源", 43_000L)).state();
        MediaModel.BridgeOutcome accepted = MediaModel.submit(multi, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-MULTI", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, originDocId, 1),
                                ref(info, REPORTER, "D-CFO", 1)), 44_000L, 44_000L + WRITE), List.of());
        assertTrue(accepted.accepted(), accepted.reason());
        MediaModel.Story multiStory = story(accepted.state(), "S-MULTI");
        assertEquals(2, multiStory.sourceGroups());
        assertEquals(2, multiStory.roots().size(), "多源转载保留全部根");
    }

    @Test
    @DisplayName("视图：未任职者看不到稿件，编辑只看题目直到真正读完")
    void viewPrunesByReadership() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-VIEW", MediaModel.TemplateKind.WIRE, "视图",
                        3_000L)).state();
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-VIEW", 0, "视图标题", "视图正文", "来源",
                        List.of(ref(info, REPORTER, "D-CO", 1)), 4_000L, 4_000L + WRITE), List.of());
        assertTrue(submitted.accepted(), submitted.reason());

        assertTrue(MediaModel.view(submitted.state(), access, submitted.information(), OUTSIDER,
                5_000L).isEmpty(), "无关人物看不到媒体稿件");

        MediaModel.Story current = story(submitted.state(), "S-VIEW");
        InformationModel.State readInfo = read(submitted.information(), access, EDITOR,
                current.draftDocId(), current.draftVersion(), 5_100L);
        List<MediaModel.StoryView> views = MediaModel.view(submitted.state(), access, readInfo,
                EDITOR, 6_000L);
        assertEquals(1, views.size());
        assertTrue(views.get(0).draftRead());
        assertEquals("视图正文", views.get(0).draftBody());
        assertEquals(1, views.get(0).sourceGroups());
    }

    @Test
    @DisplayName("开局核对：媒体组织必须是已登记组织，作者/编辑不在开局登记")
    void openingChecksMediaOrganizationAgainstRegistry() {
        assertEquals(MediaModel.open(policy(), registry()).stories(), List.of());
        assertEquals(policy(), MediaModel.open(policy(), registry()).policy(),
                "开局必须冻结整条策略");
        assertThrows(IllegalArgumentException.class, () -> MediaModel.open(
                new MediaModel.Policy("no-such-org", Map.of(MediaModel.TemplateKind.WIRE, 1),
                        WRITE, REVIEW, PUBLISH, DRAFT_READ, PUBLIC_READ, recall()),
                registry()), "未登记的组织不能当媒体组织");
        assertThrows(IllegalArgumentException.class, () -> MediaModel.open(
                new MediaModel.Policy(REPORTER, Map.of(MediaModel.TemplateKind.WIRE, 1),
                        WRITE, REVIEW, PUBLISH, DRAFT_READ, PUBLIC_READ, recall()),
                registry()), "人物不能当媒体组织");
    }

    @Test
    @DisplayName("编辑继任：按真实任职审阅，但必须自己读完本次草稿")
    void successorEditorMustReadTheDraftHerself() {
        RoleAccess.State base = roles();
        RoleAccess.State handover = new RoleAccess.State(base.accounts(), base.personAccounts(),
                base.mandates(), List.of(
                new Appointment("AP-REPORTER", REPORTER, MEDIA, Role.JOURNALIST, 0L, END, null),
                new Appointment("AP-EDITOR", EDITOR, MEDIA, Role.EDITOR, 0L, 5_000L, null),
                new Appointment("AP-EDITOR2", EDITOR2, MEDIA, Role.EDITOR, 5_000L, END, null)));

        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, handover, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), handover, REPORTER,
                new MediaModel.ProposeRequest("S-SUCC", MediaModel.TemplateKind.WIRE, "继任",
                        3_000L)).state();
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), handover, info,
                REPORTER, new MediaModel.SubmitRequest("S-SUCC", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, "D-CO", 1)), 4_000L, 5_000L), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        MediaModel.Story current = story(submitted.state(), "S-SUCC");

        // 前任已到期的编辑不能再审
        MediaModel.Outcome expiredEditor = MediaModel.review(submitted.state(), policy(), handover,
                submitted.information(), EDITOR,
                new MediaModel.ReviewRequest("S-SUCC", 1, true, "", 5_400L, 5_400L + REVIEW));
        assertFalse(expiredEditor.accepted());
        assertEquals(MediaModel.REASON_NOT_AUTHORIZED, expiredEditor.reason());

        // 继任者没读草稿也不能批
        MediaModel.Outcome unread = MediaModel.review(submitted.state(), policy(), handover,
                submitted.information(), EDITOR2,
                new MediaModel.ReviewRequest("S-SUCC", 1, true, "", 5_400L, 5_400L + REVIEW));
        assertFalse(unread.accepted());
        assertEquals(MediaModel.REASON_DRAFT_NOT_READ, unread.reason());

        InformationModel.State readInfo = read(submitted.information(), handover, EDITOR2,
                current.draftDocId(), current.draftVersion(), 5_100L);
        MediaModel.Outcome approved = MediaModel.review(submitted.state(), policy(), handover, readInfo,
                EDITOR2, new MediaModel.ReviewRequest("S-SUCC", 1, true, "", 5_400L,
                        5_400L + REVIEW));
        assertTrue(approved.accepted(), approved.reason());
        assertEquals(EDITOR2, story(approved.state(), "S-SUCC").approvedBy(),
                "批准记录写继任者，不继承前任的私人经历");
    }

    // ---------------------------------------------------------------- 冻结策略与双时点

    /** 同一条策略只换媒体组织。 */
    private static MediaModel.Policy policyOf(String organization) {
        return new MediaModel.Policy(organization,
                Map.of(MediaModel.TemplateKind.WIRE, 1,
                        MediaModel.TemplateKind.INVESTIGATIVE, 2),
                WRITE, REVIEW, PUBLISH, DRAFT_READ, PUBLIC_READ, recall());
    }

    /** 长任期授权，用于"很久以后"的查询。 */
    private static RoleAccess.State longTerm() {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-REPORTER", REPORTER, MEDIA, Role.JOURNALIST, 0L, 20_000_000L, null),
                new Appointment("AP-EDITOR", EDITOR, MEDIA, Role.EDITOR, 0L, 20_000_000L, null),
                new Appointment("AP-OTHER", OTHER_EDITOR, OTHER_MEDIA, Role.EDITOR, 0L,
                        20_000_000L, null)));
    }

    @Test
    @DisplayName("冻结策略：换组织/阈值/耗时/回忆参数一律明确拒绝且不改状态")
    void policyCannotBeSwappedPerCall() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(),
                access, REPORTER, new MediaModel.ProposeRequest("S-POL", MediaModel.TemplateKind.WIRE,
                        "策略", 3_000L)).state();
        RoleView.ReadingRef evidence = ref(info, REPORTER, "D-CO", 1);

        MediaModel.BridgeOutcome otherOrg = MediaModel.submit(media, policyOf(OTHER_MEDIA), access,
                info, REPORTER, new MediaModel.SubmitRequest("S-POL", 0, "标题", "正文", "来源",
                        List.of(evidence), 4_000L, 5_000L), List.of());
        assertFalse(otherOrg.accepted(), "换媒体组织必须拒绝");
        assertEquals(MediaModel.REASON_POLICY_MISMATCH, otherOrg.reason());
        assertEquals(media, otherOrg.state());
        assertEquals(info, otherOrg.information());

        MediaModel.Policy weirder = new MediaModel.Policy(MEDIA,
                Map.of(MediaModel.TemplateKind.WIRE, 2),
                WRITE, REVIEW, PUBLISH, DRAFT_READ, PUBLIC_READ, recall());
        MediaModel.BridgeOutcome otherThreshold = MediaModel.submit(media, weirder, access, info,
                REPORTER, new MediaModel.SubmitRequest("S-POL", 0, "标题", "正文", "来源",
                        List.of(evidence), 4_000L, 5_000L), List.of());
        assertFalse(otherThreshold.accepted(), "换来源阈值必须拒绝");
        assertEquals(MediaModel.REASON_POLICY_MISMATCH, otherThreshold.reason());

        MediaModel.Policy slower = new MediaModel.Policy(MEDIA,
                Map.of(MediaModel.TemplateKind.WIRE, 1),
                WRITE + 1, REVIEW, PUBLISH, DRAFT_READ, PUBLIC_READ, recall());
        MediaModel.BridgeOutcome otherDuration = MediaModel.submit(media, slower, access, info,
                REPORTER, new MediaModel.SubmitRequest("S-POL", 0, "标题", "正文", "来源",
                        List.of(evidence), 4_000L, 5_000L), List.of());
        assertFalse(otherDuration.accepted(), "换耗时必须拒绝");
        assertEquals(MediaModel.REASON_POLICY_MISMATCH, otherDuration.reason());

        MediaModel.Policy otherRecall = new MediaModel.Policy(MEDIA,
                Map.of(MediaModel.TemplateKind.WIRE, 1),
                WRITE, REVIEW, PUBLISH, DRAFT_READ, PUBLIC_READ,
                new CognitionRecall.Policy(2_000_000L, 1, 100, 1_000_000L));
        MediaModel.Outcome proposeOtherRecall = MediaModel.propose(media, otherRecall, access,
                REPORTER, new MediaModel.ProposeRequest("S-POL2", MediaModel.TemplateKind.WIRE,
                        "策略2", 3_500L));
        assertFalse(proposeOtherRecall.accepted(), "换回忆参数必须拒绝");
        assertEquals(MediaModel.REASON_POLICY_MISMATCH, proposeOtherRecall.reason());

        // 冻结策略原样随状态流转
        MediaModel.BridgeOutcome ok = MediaModel.submit(media, policy(), access, info, REPORTER,
                new MediaModel.SubmitRequest("S-POL", 0, "标题", "正文", "来源", List.of(evidence),
                        4_000L, 5_000L), List.of());
        assertTrue(ok.accepted(), ok.reason());
        assertEquals(policy(), ok.state().policy());
    }

    @Test
    @DisplayName("写作期间任期结束或撤职：完成时点核不到任职就拒绝")
    void writerAuthorityCheckedAtStartAndCompletion() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(),
                access, REPORTER, new MediaModel.ProposeRequest("S-WEX", MediaModel.TemplateKind.WIRE,
                        "写作任期", 3_000L)).state();
        RoleView.ReadingRef evidence = ref(info, REPORTER, "D-CO", 1);

        RoleAccess.State expiring = new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-REPORTER", REPORTER, MEDIA, Role.JOURNALIST, 0L, 4_500L, null),
                new Appointment("AP-EDITOR", EDITOR, MEDIA, Role.EDITOR, 0L, END, null)));
        MediaModel.BridgeOutcome expired = MediaModel.submit(media, policy(), expiring, info,
                REPORTER, new MediaModel.SubmitRequest("S-WEX", 0, "标题", "正文", "来源",
                        List.of(evidence), 4_000L, 5_000L), List.of());
        assertFalse(expired.accepted(), "写作中途到期不能提交");
        assertEquals(MediaModel.REASON_NOT_AUTHORIZED, expired.reason());
        assertEquals(media, expired.state());

        RoleAccess.State revoked = RoleAccess.revoke(access, "AP-REPORTER", 4_500L);
        MediaModel.BridgeOutcome revokedSubmit = MediaModel.submit(media, policy(), revoked, info,
                REPORTER, new MediaModel.SubmitRequest("S-WEX", 0, "标题", "正文", "来源",
                        List.of(evidence), 4_000L, 5_000L), List.of());
        assertFalse(revokedSubmit.accepted(), "写作中途撤职不能提交");
        assertEquals(MediaModel.REASON_NOT_AUTHORIZED, revokedSubmit.reason());
    }

    @Test
    @DisplayName("审阅期间任期结束：完成时点核不到任职就拒绝")
    void editorAuthorityCheckedAtReviewCompletion() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(),
                access, REPORTER, new MediaModel.ProposeRequest("S-EEX", MediaModel.TemplateKind.WIRE,
                        "审阅任期", 3_000L)).state();
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-EEX", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, "D-CO", 1)), 4_000L, 5_000L), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        MediaModel.Story current = story(submitted.state(), "S-EEX");

        RoleAccess.State expiring = new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-REPORTER", REPORTER, MEDIA, Role.JOURNALIST, 0L, END, null),
                new Appointment("AP-EDITOR", EDITOR, MEDIA, Role.EDITOR, 0L, 5_500L, null)));
        InformationModel.State readInfo = read(submitted.information(), expiring, EDITOR,
                current.draftDocId(), current.draftVersion(), 5_100L);
        MediaModel.Outcome expired = MediaModel.review(submitted.state(), policy(), expiring,
                readInfo, EDITOR, new MediaModel.ReviewRequest("S-EEX", 1, true, "", 5_400L, 7_400L));
        assertFalse(expired.accepted(), "审阅中途到期不能批准");
        assertEquals(MediaModel.REASON_NOT_AUTHORIZED, expired.reason());
        assertEquals(submitted.state(), expired.state());
    }

    @Test
    @DisplayName("发布期间任期结束：完成时点核不到任职就拒绝")
    void publisherAuthorityCheckedAtPublishCompletion() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(),
                access, REPORTER, new MediaModel.ProposeRequest("S-PEX", MediaModel.TemplateKind.WIRE,
                        "发布任期", 3_000L)).state();
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-PEX", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, "D-CO", 1)), 4_000L, 5_000L), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        MediaModel.Story current = story(submitted.state(), "S-PEX");
        info = read(submitted.information(), access, EDITOR, current.draftDocId(),
                current.draftVersion(), 5_100L);
        MediaModel.Outcome approved = MediaModel.review(submitted.state(), policy(), access, info,
                EDITOR, new MediaModel.ReviewRequest("S-PEX", 1, true, "", 5_400L, 7_400L));
        assertTrue(approved.accepted(), approved.reason());

        RoleAccess.State expiring = new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-REPORTER", REPORTER, MEDIA, Role.JOURNALIST, 0L, END, null),
                new Appointment("AP-EDITOR", EDITOR, MEDIA, Role.EDITOR, 0L, 8_000L, null)));
        MediaModel.BridgeOutcome expired = MediaModel.publish(approved.state(), policy(), expiring,
                info, EDITOR, new MediaModel.PublishRequest("S-PEX", 1, 7_500L, 10_500L));
        assertFalse(expired.accepted(), "发布中途到期不能发布");
        assertEquals(MediaModel.REASON_NOT_AUTHORIZED, expired.reason());
        assertEquals(approved.state(), expired.state());
        assertEquals(info, expired.information());
    }

    @Test
    @DisplayName("视图按冻结组织裁剪：别的媒体编辑与基金研究员都看不到")
    void viewIsScopedToFrozenOrganization() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(),
                access, REPORTER, new MediaModel.ProposeRequest("S-ORG", MediaModel.TemplateKind.WIRE,
                        "机构", 3_000L)).state();

        assertTrue(MediaModel.view(media, access, info, "other-editor", 4_000L).isEmpty(),
                "另一个媒体的编辑看不到");
        assertTrue(MediaModel.view(media, access, info, "outsider", 4_000L).isEmpty());
        assertEquals(1, MediaModel.view(media, access, info, REPORTER, 4_000L).size(),
                "本机构记者看得到自己的稿件");
        assertEquals(1, MediaModel.view(media, access, info, EDITOR, 4_000L).size(),
                "本机构编辑看得到待审稿件");
    }

    @Test
    @DisplayName("作者离任后不再看到当前未公开工作流")
    void departedAuthorLosesCurrentWorkflow() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(),
                access, REPORTER, new MediaModel.ProposeRequest("S-GONE", MediaModel.TemplateKind.WIRE,
                        "离任", 3_000L)).state();

        RoleAccess.State departed = new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-REPORTER", REPORTER, MEDIA, Role.JOURNALIST, 0L, 5L, null),
                new Appointment("AP-EDITOR", EDITOR, MEDIA, Role.EDITOR, 0L, END, null)));
        assertTrue(MediaModel.view(media, departed, info, REPORTER, 6L).isEmpty(),
                "离任记者不能凭作者标识继续看未公开稿件");
        assertEquals(1, MediaModel.view(media, access, info, REPORTER, 4_000L).size(),
                "在职时仍然可见");
    }

    @Test
    @DisplayName("遗忘后视图不再返回草稿全文")
    void forgottenDraftBodyIsNotProjected() {
        RoleAccess.State access = longTerm();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(),
                access, REPORTER, new MediaModel.ProposeRequest("S-FORGET2",
                        MediaModel.TemplateKind.WIRE, "遗忘草稿", 3_000L)).state();
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-FORGET2", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, "D-CO", 1)), 4_000L, 5_000L), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        MediaModel.Story current = story(submitted.state(), "S-FORGET2");
        InformationModel.State readInfo = read(submitted.information(), access, EDITOR,
                current.draftDocId(), current.draftVersion(), 5_100L);

        List<MediaModel.StoryView> fresh = MediaModel.view(submitted.state(), access, readInfo,
                EDITOR, 6_000L);
        assertEquals("正文", fresh.get(0).draftBody(), "刚读完时能看正文");

        // 12_000_000 时半衰期已过十轮，回忆结果为空
        assertTrue(CognitionRecall.recall(readInfo, EDITOR, 12_000_000L, policy().recallPolicy())
                .selected().isEmpty(), "控件：此刻编辑已记不起草稿");
        List<MediaModel.StoryView> old = MediaModel.view(submitted.state(), access, readInfo,
                EDITOR, 12_000_000L);
        assertNull(old.get(0).draftBody(), "遗忘后不能继续返回全文");
        assertFalse(old.get(0).draftRead());
    }

    @Test
    @DisplayName("遗忘后不能凭曾经读完就批准")
    void forgottenDraftCannotBeApproved() {
        RoleAccess.State access = longTerm();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(),
                access, REPORTER, new MediaModel.ProposeRequest("S-FORGET3",
                        MediaModel.TemplateKind.WIRE, "遗忘批准", 3_000L)).state();
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-FORGET3", 0, "标题", "正文", "来源",
                        List.of(ref(info, REPORTER, "D-CO", 1)), 4_000L, 5_000L), List.of());
        assertTrue(submitted.accepted(), submitted.reason());
        MediaModel.Story current = story(submitted.state(), "S-FORGET3");
        InformationModel.State readInfo = read(submitted.information(), access, EDITOR,
                current.draftDocId(), current.draftVersion(), 5_100L);

        MediaModel.Outcome tooLate = MediaModel.review(submitted.state(), policy(), access, readInfo,
                EDITOR, new MediaModel.ReviewRequest("S-FORGET3", 1, true, "",
                        12_000_000L, 12_000_000L + REVIEW));
        assertFalse(tooLate.accepted(), "记忆丢失后不能批准");
        assertEquals(MediaModel.REASON_DRAFT_NOT_RECALLABLE, tooLate.reason());
        assertEquals(submitted.state(), tooLate.state());
    }

    @Test
    @DisplayName("派生文档标识带媒体组织，不同媒体同名稿件不撞车")
    void derivedDocumentIdsCarryOrganization() {
        String a = MediaModel.draftDocId(policyOf(MEDIA), "same-story");
        String b = MediaModel.draftDocId(policyOf(OTHER_MEDIA), "same-story");
        assertFalse(a.equals(b), "不同媒体的草稿标识必须不同");
        assertTrue(a.contains(MEDIA) && b.contains(OTHER_MEDIA));
        String publicA = MediaModel.publicDocId(policyOf(MEDIA), "same-story");
        String publicB = MediaModel.publicDocId(policyOf(OTHER_MEDIA), "same-story");
        assertFalse(publicA.equals(publicB));
        assertFalse(publicA.equals(a), "公开标识与草稿标识必须不同");
    }

    // ---------------------------------------------------------------- 序列化与别名

    @Test
    @DisplayName("JSON 往返稳定；输入列表被外部修改不影响状态")
    void jsonRoundTripAndDefensiveCopies() {
        RoleAccess.State access = roles();
        InformationModel.State info = primary(information(), "D-CO", CHAIR, 1_000L);
        info = read(info, access, REPORTER, "D-CO", 1, 2_000L);
        List<RoleView.ReadingRef> evidence = new ArrayList<>(List.of(ref(info, REPORTER, "D-CO",
                1)));
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy(), registry()), policy(), access, REPORTER,
                new MediaModel.ProposeRequest("S-JSON", MediaModel.TemplateKind.WIRE, "序列化",
                        3_000L)).state();
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                REPORTER, new MediaModel.SubmitRequest("S-JSON", 0, "标题", "正文", "来源", evidence,
                        4_000L, 4_000L + WRITE), List.of());
        assertTrue(submitted.accepted(), submitted.reason());

        evidence.clear();
        assertEquals(1, story(submitted.state(), "S-JSON").evidence().size(),
                "状态持有自己的副本，不受入参列表后续修改影响");

        String json = MediaModel.toJson(submitted.state());
        assertEquals(submitted.state(), MediaModel.fromJson(json));
        assertEquals(json, MediaModel.toJson(MediaModel.fromJson(json)), "再次序列化字节稳定");
    }

    // ---------------------------------------------------------------- 工具

    private static InformationModel.Document document(InformationModel.State info, String docId) {
        for (Document document : info.documents()) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        throw new IllegalStateException("资料不存在：" + docId);
    }

    /** 提交 + 编辑读完 + 批准 + 发布，返回媒体与资料两个新状态。 */
    private static Published publishWith(MediaModel.State media, InformationModel.State info,
                                         RoleAccess.State access, String storyId, long at,
                                         List<RoleView.ReadingRef> evidence) {
        MediaModel.Story current = story(media, storyId);
        MediaModel.BridgeOutcome submitted = MediaModel.submit(media, policy(), access, info,
                current.authorPartyId(), new MediaModel.SubmitRequest(storyId, current.revisionNo(),
                        "标题 " + storyId, "正文 " + storyId, "据公开材料", evidence, at, at + WRITE), List.of());
        assertTrue(submitted.accepted(), "提交应成功：" + submitted.reason());
        info = submitted.information();
        media = submitted.state();
        MediaModel.Story after = story(media, storyId);
        info = read(info, access, EDITOR, after.draftDocId(), after.draftVersion(), at + WRITE + 10);
        long reviewAt = at + WRITE + 200;
        MediaModel.Outcome approved = MediaModel.review(media, policy(), access, info, EDITOR,
                new MediaModel.ReviewRequest(storyId, after.revisionNo(), true, "", reviewAt,
                        reviewAt + REVIEW));
        assertTrue(approved.accepted(), "批准应成功：" + approved.reason());
        long publishAt = reviewAt + REVIEW + 100;
        MediaModel.BridgeOutcome published = MediaModel.publish(approved.state(), policy(), access,
                info, EDITOR, new MediaModel.PublishRequest(storyId,
                        story(approved.state(), storyId).revisionNo(), publishAt,
                        publishAt + PUBLISH));
        assertTrue(published.accepted(), "发布应成功：" + published.reason());
        return new Published(published.state(), published.information());
    }

    /** 媒体与资料的同批候选。 */
    private record Published(MediaModel.State media, InformationModel.State information) {
    }

    // ---------------------------------------------------------------- 授权增量
    @Test
    @DisplayName("授权增量：媒体岗位只拿到自己的动作，旧四岗不受影响")
    void mediaRolesOnlyPermitTheirActions() {
        RoleAccess.State access = roles();
        assertEquals(Decision.ALLOW, RoleAccess.authorize(access, REPORTER, Action.WRITE_STORY,
                Target.organization(MEDIA), 1_000L));
        assertEquals(Decision.NOT_AUTHORIZED, RoleAccess.authorize(access, REPORTER,
                Action.REVIEW_STORY, Target.organization(MEDIA), 1_000L));
        assertEquals(Decision.ALLOW, RoleAccess.authorize(access, EDITOR, Action.REVIEW_STORY,
                Target.organization(MEDIA), 1_000L));
        assertEquals(Decision.ALLOW, RoleAccess.authorize(access, EDITOR, Action.PUBLISH_STORY,
                Target.organization(MEDIA), 1_000L));
        assertEquals(Decision.NOT_AUTHORIZED, RoleAccess.authorize(access, EDITOR,
                Action.WRITE_STORY, Target.organization(MEDIA), 1_000L));
        assertEquals(Decision.NOT_AUTHORIZED, RoleAccess.authorize(access, REPORTER,
                Action.WRITE_STORY, Target.organization(OTHER_MEDIA), 1_000L),
                "跨组织不能用记者权限");
        // 旧四岗：研究员不能写稿
        RoleAccess.State fund = new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-R", REPORTER, COMPANY, Role.RESEARCHER, 0L, END, null)));
        assertEquals(Decision.NOT_AUTHORIZED, RoleAccess.authorize(fund, REPORTER,
                Action.WRITE_STORY, Target.organization(COMPANY), 1_000L));
        assertEquals(Decision.ALLOW, RoleAccess.authorize(fund, REPORTER, Action.WRITE_RESEARCH,
                Target.organization(COMPANY), 1_000L));
    }

    @Test
    @DisplayName("同机构不可兼记者与编辑：自批在任职层就被拒绝")
    void sameOrganizationCannotHoldBothMediaRoles() {
        assertThrows(IllegalArgumentException.class, () -> new RoleAccess.State(
                List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-1", REPORTER, MEDIA, Role.JOURNALIST, 0L, 5_000L, null),
                new Appointment("AP-2", REPORTER, MEDIA, Role.EDITOR, 3_000L, 8_000L, null))));
    }
}
