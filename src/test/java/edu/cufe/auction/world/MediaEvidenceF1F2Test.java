package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F1（公开转述继承私根）与 F2（共享文档不得洗成新来源）的回归。 */
class MediaEvidenceF1F2Test {

    private static final String MEDIA_A = "media-a";
    private static final String MEDIA_B = "media-b";
    private static final String RITA = "rita";
    private static final String BOB = "bob";
    private static final String SAM = "sam";
    private static final String ED = "ed";
    private static final String IVY = "ivy";
    private static final String MEDIA_C = "media-c";

    /** 最近一次发布链产出的公开文档标识（测试内传递）。 */
    private static String lastPublicDoc;
    private static MediaModel.State lastMediaA;

    private static OpeningRegistry registry() {
        return new OpeningRegistry(List.of(
                new OpeningRegistry.Party(RITA, OpeningRegistry.PartyKind.PERSON, "记者甲"),
                new OpeningRegistry.Party(BOB, OpeningRegistry.PartyKind.PERSON, "记者乙"),
                new OpeningRegistry.Party(SAM, OpeningRegistry.PartyKind.PERSON, "信源"),
                new OpeningRegistry.Party(ED, OpeningRegistry.PartyKind.PERSON, "编辑"),
                new OpeningRegistry.Party(IVY, OpeningRegistry.PartyKind.PERSON, "第三人"),
                new OpeningRegistry.Party(MEDIA_A, OpeningRegistry.PartyKind.ORGANIZATION, "媒体甲"),
                new OpeningRegistry.Party(MEDIA_B, OpeningRegistry.PartyKind.ORGANIZATION, "媒体乙"),
                new OpeningRegistry.Party(MEDIA_C, OpeningRegistry.PartyKind.ORGANIZATION, "媒体丙")),
                List.of(), List.of(), List.of());
    }

    private static MediaModel.Policy policy(String org) {
        return new MediaModel.Policy(org, Map.of(MediaModel.TemplateKind.WIRE, 1,
                MediaModel.TemplateKind.INVESTIGATIVE, 1),
                1_000L, 1_000L, 1_000L, 100L, 100L,
                new CognitionRecall.Policy(10_000_000L, 1, 100, 100_000L));
    }

    private static RoleAccess.State access() {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new RoleAccess.Appointment("a1", RITA, MEDIA_A, RoleAccess.Role.JOURNALIST, 0L,
                        1_000_000L, null),
                new RoleAccess.Appointment("a2", BOB, MEDIA_B, RoleAccess.Role.JOURNALIST, 0L,
                        1_000_000L, null),
                new RoleAccess.Appointment("a4", IVY, MEDIA_C, RoleAccess.Role.JOURNALIST, 0L,
                        1_000_000L, null),
                new RoleAccess.Appointment("a3", ED, MEDIA_A, RoleAccess.Role.EDITOR, 0L,
                        1_000_000L, null)));
    }

    /** 私证 D-PRIV 由 SAM 发布给 RITA；RITA 读完并据此发出一篇公开稿。 */
    private static InformationModel.State world() {
        InformationModel.State info = InformationModel.open(registry(), List.of(RITA, BOB, SAM, ED, IVY));
        info = InformationModel.publish(info, "D-PRIV", SAM,
                InformationModel.Audience.direct(RITA),
                new InformationModel.VersionDraft("内部文件", "订单将下滑", "", 100L, 10L, List.of()));
        InformationModel.Outcome started = InformationModel.startRead(info, access(), RITA, "D-PRIV",
                1L, 1_000L);
        String readId = started.state().active().get(0).readId();
        return InformationModel.completeRead(started.state(), access(), RITA, readId, 1_010L).state();
    }

    private static MediaModel.Ref ref(String docId, long version) {
        return new MediaModel.Ref(docId, version);
    }

    /** 生成一篇带私根的公开文章，返回信息状态（BOB 已读该公开稿）。 */
    private static InformationModel.State publishedWithPrivateRoot() {
        InformationModel.State info = world();
        RoleAccess.State access = access();
        MediaModel.Policy policyA = policy(MEDIA_A);
        MediaModel.State media = MediaModel.open(policyA, registry());
        media = MediaModel.propose(media, policyA, access, RITA,
                new MediaModel.ProposeRequest("S1", MediaModel.TemplateKind.WIRE, "选题", 1_200L))
                .state();
        MediaModel.EvidenceBridgeOutcome submitted = MediaModel.submitWithEvidence(media, policyA,
                access, info, RITA,
                new MediaModel.SubmitRequest("S1", 0, "标题", "正文", "来源",
                        List.of(), 1_200L, 2_200L), List.of(),
                new MediaModel.EvidenceContext(List.of(new RoleView.ReadingRef(RITA, "D-PRIV", 1L, 1)),
                        MediaEvidence.empty(), List.of()));
        assertTrue(submitted.accepted(), submitted.reason());
        String draftDocId = submitted.state().stories().get(0).draftDocId();
        InformationModel.State edRead = read(submitted.information(), access, ED, draftDocId, 2_300L);
        MediaModel.Outcome reviewed = MediaModel.review(submitted.state(), policyA, access,
                edRead, ED,
                new MediaModel.ReviewRequest("S1", 1, true, "", 2_400L, 3_400L));
        assertTrue(reviewed.accepted(), reviewed.reason());
        MediaModel.BridgeOutcome published = MediaModel.publish(reviewed.state(), policyA, access,
                edRead, ED,
                new MediaModel.PublishRequest("S1", 1, 3_600L, 4_600L));
        assertTrue(published.accepted(), published.reason());
        String publicDocId = published.state().stories().get(0).publicDocId();
        lastPublicDoc = publicDocId;
        lastMediaA = published.state();

        InformationModel.Outcome started = InformationModel.startRead(published.information(), access,
                BOB, publicDocId, 1L, 5_000L);
        assertEquals(InformationModel.Status.STARTED, started.status());
        String readId = started.state().active().get(0).readId();
        return InformationModel.completeRead(started.state(), access, BOB, readId, 5_100L).state();
    }

    @Test
    void transcriptionInheritsPrivateRootWithoutAccess() {
        InformationModel.State info = publishedWithPrivateRoot();
        String publicDocId = lastPublicDoc;
        RoleAccess.State access = access();
        MediaModel.Policy policyB = policy(MEDIA_B);
        MediaModel.State mediaB = MediaModel.open(policyB, registry());
        mediaB = MediaModel.propose(mediaB, policyB, access, BOB,
                new MediaModel.ProposeRequest("S2", MediaModel.TemplateKind.WIRE, "转载", 5_200L))
                .state();
        MediaModel.EvidenceBridgeOutcome outcome = MediaModel.submitWithEvidence(mediaB, policyB,
                access, info, BOB,
                new MediaModel.SubmitRequest("S2", 0, "转载标题", "转载正文", "据同行报道",
                        List.of(new RoleView.ReadingRef(BOB, publicDocId, 1L, 1)), 5_200L, 6_200L),
                List.of(lastMediaA), MediaModel.EvidenceContext.none());
        assertTrue(outcome.accepted(), outcome.reason());
        MediaModel.Story story = outcome.state().stories().get(0);
        assertEquals(List.of(ref("D-PRIV", 1L)), story.roots(), "继承原私根");
        assertEquals(1, story.sourceGroups());
    }

    @Test
    void oldSubmitStillRefusesInheritedPrivateRoot() {
        InformationModel.State info = publishedWithPrivateRoot();
        String publicDocId = lastPublicDoc;
        RoleAccess.State access = access();
        MediaModel.Policy policyB = policy(MEDIA_B);
        MediaModel.State mediaB = MediaModel.open(policyB, registry());
        mediaB = MediaModel.propose(mediaB, policyB, access, BOB,
                new MediaModel.ProposeRequest("S3", MediaModel.TemplateKind.WIRE, "转载", 5_200L))
                .state();
        MediaModel.BridgeOutcome outcome = MediaModel.submit(mediaB, policyB, access, info, BOB,
                new MediaModel.SubmitRequest("S3", 0, "转载标题", "转载正文", "据同行报道",
                        List.of(new RoleView.ReadingRef(BOB, publicDocId, 1L, 1)), 5_200L, 6_200L),
                List.of());
        assertFalse(outcome.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_PROVENANCE, outcome.reason());
    }

    @Test
    void twoSharersOfOneRootCountAsOneSource() {
        InformationModel.State info = world();
        RoleAccess.State access = access();
        MediaModel.Policy policy = policy(MEDIA_A);
        MediaModel.State media = MediaModel.open(policy, registry());
        MediaModel.Ref root = ref("D-PRIV", 1L);
        MediaEvidence.ShareOutcome first = MediaEvidence.share(MediaEvidence.empty(), media, policy,
                access, info, RITA,
                new MediaEvidence.ShareRequest("c1", root, IVY, "", 2_000L));
        assertTrue(first.accepted(), first.reason());
        MediaEvidence.ShareOutcome second = MediaEvidence.share(first.evidence(), media, policy,
                access, first.information(), SAM,
                new MediaEvidence.ShareRequest("c2", root, IVY, "", 2_500L));
        assertFalse(second.accepted(), "SAM 无采访任职，不能共享");

        MediaEvidence.ShareOutcome again = MediaEvidence.share(first.evidence(), media, policy,
                access, first.information(), RITA,
                new MediaEvidence.ShareRequest("c2", root, IVY, "", 2_500L));
        assertTrue(again.accepted(), again.reason());
        assertFalse(again.evidence().shares().get(0).sharingDocId()
                .equals(again.evidence().shares().get(1).sharingDocId()));

        InformationModel.State withReads = read(again.information(), access, IVY,
                again.evidence().shares().get(0).sharingDocId(), 2_600L);
        withReads = read(withReads, access, IVY,
                again.evidence().shares().get(1).sharingDocId(), 2_700L);

        MediaModel.Policy policyC = policy(MEDIA_C);
        MediaModel.State mediaC = MediaModel.open(policyC, registry());
        MediaModel.State draft = MediaModel.propose(mediaC, policyC, access, IVY,
                new MediaModel.ProposeRequest("S4", MediaModel.TemplateKind.INVESTIGATIVE, "调查",
                        2_800L)).state();
        MediaModel.EvidenceBridgeOutcome outcome = MediaModel.submitWithEvidence(draft, policyC,
                access, withReads, IVY,
                new MediaModel.SubmitRequest("S4", 0, "调查稿", "正文", "两位消息人",
                        List.of(), 3_000L, 4_000L), List.of(),
                new MediaModel.EvidenceContext(List.of(
                        new RoleView.ReadingRef(IVY, again.evidence().shares().get(0).sharingDocId(),
                                1L, 1),
                        new RoleView.ReadingRef(IVY, again.evidence().shares().get(1).sharingDocId(),
                                1L, 2)),
                        again.evidence(), List.of()));
        assertTrue(outcome.accepted(), outcome.reason());
        assertEquals(List.of(root), outcome.state().stories().get(0).roots(), "回到同一真实根");
        assertEquals(1, outcome.state().stories().get(0).sourceGroups(), "两位分享者仍算一个来源");
    }

    @Test
    void shareDocumentWithoutBindingIsProvenanceError() {
        InformationModel.State info = world();
        RoleAccess.State access = access();
        MediaModel.Policy policy = policy(MEDIA_A);
        MediaModel.State media = MediaModel.open(policy, registry());
        info = InformationModel.publish(info, "identity-share:9:rita:c1", RITA,
                InformationModel.Audience.direct(RITA),
                new InformationModel.VersionDraft("身份共享材料", "身份来源：sam", "", 1_500L, 10L,
                        List.of()));
        info = read(info, access, RITA, "identity-share:9:rita:c1", 1_600L);
        MediaModel.State draft = MediaModel.propose(media, policy, access, RITA,
                new MediaModel.ProposeRequest("S5", MediaModel.TemplateKind.WIRE, "选题", 2_000L))
                .state();
        MediaModel.EvidenceBridgeOutcome outcome = MediaModel.submitWithEvidence(draft, policy,
                access, info, RITA,
                new MediaModel.SubmitRequest("S5", 0, "标题", "正文", "来源", List.of(), 2_000L,
                        3_000L), List.of(),
                new MediaModel.EvidenceContext(List.of(new RoleView.ReadingRef(RITA,
                        "identity-share:9:rita:c1", 1L, 2)), MediaEvidence.empty(), List.of()));
        assertFalse(outcome.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_PROVENANCE, outcome.reason(),
                "无绑定的共享文档不得当作独立来源");
    }

    private static InformationModel.State read(InformationModel.State info, RoleAccess.State access,
                                               String person, String docId, long now) {
        InformationModel.Outcome started = InformationModel.startRead(info, access, person, docId,
                1L, now);
        String readId = started.state().active().get(started.state().active().size() - 1).readId();
        return InformationModel.completeRead(started.state(), access, person, readId, now + 100L)
                .state();
    }
}
