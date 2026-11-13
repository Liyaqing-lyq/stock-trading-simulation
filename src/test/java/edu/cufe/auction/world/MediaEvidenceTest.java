package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 私有证据纯核的私有定向测试。 */
class MediaEvidenceTest {

    private static final String MEDIA_A = "media-a";
    private static final String RITA = "rita";
    private static final String SAM = "sam";
    private static final String ED = "ed";
    private static final String CO = "co";

    private static OpeningRegistry registry() {
        return new OpeningRegistry(List.of(
                new OpeningRegistry.Party(RITA, OpeningRegistry.PartyKind.PERSON, "记者"),
                new OpeningRegistry.Party(SAM, OpeningRegistry.PartyKind.PERSON, "信源"),
                new OpeningRegistry.Party(ED, OpeningRegistry.PartyKind.PERSON, "编辑"),
                new OpeningRegistry.Party(CO, OpeningRegistry.PartyKind.PERSON, "公司"),
                new OpeningRegistry.Party(MEDIA_A, OpeningRegistry.PartyKind.ORGANIZATION, "媒体")),
                List.of(), List.of(), List.of());
    }

    private static MediaModel.Policy policy() {
        return new MediaModel.Policy(MEDIA_A, Map.of(MediaModel.TemplateKind.WIRE, 1),
                1_000L, 1_000L, 1_000L, 100L, 100L,
                new CognitionRecall.Policy(10_000_000L, 0, 100, 100_000L));
    }

    private static RoleAccess.State access() {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new RoleAccess.Appointment("ap-1", RITA, MEDIA_A, RoleAccess.Role.JOURNALIST,
                        0L, 1_000_000L, null),
                new RoleAccess.Appointment("ap-2", ED, MEDIA_A, RoleAccess.Role.EDITOR,
                        0L, 1_000_000L, null)));
    }

    /** 世界夹具：公开披露 + 一条私有采访记录，记者已真实读完两者。 */
    private record Fixture(InformationModel.State information, RoleAccess.State access,
                           MediaModel.State media, MediaModel.Policy policy) {
    }

    private static Fixture fixture(int thresholdPermille, long halfLife) {
        MediaModel.Policy policy = new MediaModel.Policy(MEDIA_A,
                Map.of(MediaModel.TemplateKind.WIRE, 1), 1_000L, 1_000L, 1_000L, 100L, 100L,
                new CognitionRecall.Policy(halfLife, thresholdPermille, 100, 100_000L));
        InformationModel.State info = InformationModel.open(registry(),
                List.of(RITA, SAM, ED, CO));
        info = InformationModel.publish(info, "pub-1", CO,
                InformationModel.Audience.publicAudience(),
                new InformationModel.VersionDraft("公开披露", "收入 100", "", 100L, 10L, List.of()));
        info = InformationModel.publish(info, "iv-1", SAM,
                InformationModel.Audience.direct(RITA),
                new InformationModel.VersionDraft("采访记录", "我看到订单减少", "", 200L, 10L,
                        List.of()));
        info = read(info, "pub-1", 1L, 1_000L);
        info = read(info, "iv-1", 1L, 1_100L);
        MediaModel.State media = MediaModel.open(policy, registry());
        return new Fixture(info, access(), media, policy);
    }

    private static InformationModel.State read(InformationModel.State info, String docId,
                                               long version, long now) {
        InformationModel.Outcome started = InformationModel.startRead(info, access(), RITA, docId,
                version, now);
        assertEquals(InformationModel.Status.STARTED, started.status());
        String readId = started.state().active().get(started.state().active().size() - 1).readId();
        InformationModel.Outcome done = InformationModel.completeRead(started.state(), access(),
                RITA, readId, now + 10L);
        assertEquals(InformationModel.Status.COMPLETED, done.status(), String.valueOf(done.status()));
        return done.state();
    }

    private static RoleView.ReadingRef ref(String docId, long version, int index) {
        return new RoleView.ReadingRef(RITA, docId, version, index);
    }

    private static MediaModel.SubmitRequest submitRequest(String storyId, List<RoleView.ReadingRef> evidence) {
        return new MediaModel.SubmitRequest(storyId, 0, "标题", "正文",
                "信源说明", evidence, 1_000L, 2_000L);
    }

    private static MediaModel.State proposed(Fixture fixture, String storyId) {
        MediaModel.Outcome proposed = MediaModel.propose(fixture.media(), fixture.policy(),
                fixture.access(), RITA, new MediaModel.ProposeRequest(storyId,
                        MediaModel.TemplateKind.WIRE, "选题", 1_000L));
        assertTrue(proposed.accepted(), proposed.reason());
        return proposed.state();
    }

    @Test
    void purePrivateEvidenceBecomesStoryWithPrivateRoot() {
        Fixture fixture = fixture(1, 10_000_000L);
        MediaModel.State state = proposed(fixture, "S1");
        MediaModel.Ref root = new MediaModel.Ref("iv-1", 1L);
        MediaModel.EvidenceContext context = new MediaModel.EvidenceContext(List.of(ref("iv-1", 1L, 2)),
                MediaEvidence.empty(),
                List.of(new MediaEvidence.AliasBinding("S1", 1, root, "一位业内人士")));

        MediaModel.EvidenceBridgeOutcome outcome = MediaModel.submitWithEvidence(state,
                fixture.policy(), fixture.access(), fixture.information(), RITA,
                submitRequest("S1", List.of()), List.of(), context);

        assertTrue(outcome.accepted(), outcome.reason());
        MediaModel.Story story = outcome.state().stories().get(0);
        assertEquals(1, story.roots().size());
        assertEquals(root, story.roots().get(0));
        assertEquals(1, story.sourceGroups());
        assertEquals(1, story.evidence().size());
        assertEquals("一位业内人士", MediaEvidence.labelOf(outcome.evidence(), "S1", 1, root));
        assertFalse(MediaModel.view(outcome.state(), fixture.access(), outcome.information(), RITA,
                2_000L).isEmpty());
    }

    @Test
    void mixedPublicAndPrivateEvidenceCountsPublishersOnce() {
        Fixture fixture = fixture(1, 10_000_000L);
        MediaModel.State state = proposed(fixture, "S2");
        MediaModel.EvidenceContext context = new MediaModel.EvidenceContext(
                List.of(ref("iv-1", 1L, 2)), MediaEvidence.empty(),
                List.of(new MediaEvidence.AliasBinding("S2", 1, new MediaModel.Ref("iv-1", 1L), "匿名"),
                        new MediaEvidence.AliasBinding("S2", 1, new MediaModel.Ref("pub-1", 1L), "公司")));

        MediaModel.EvidenceBridgeOutcome outcome = MediaModel.submitWithEvidence(state,
                fixture.policy(), fixture.access(), fixture.information(), RITA,
                submitRequest("S2", List.of(ref("pub-1", 1L, 1))), List.of(), context);

        assertTrue(outcome.accepted(), outcome.reason());
        MediaModel.Story story = outcome.state().stories().get(0);
        assertEquals(2, story.sourceGroups());
        assertEquals(2, story.roots().size());
    }

    @Test
    void aliasMustPointAtRootOfThisRevision() {
        Fixture fixture = fixture(1, 10_000_000L);
        MediaModel.State state = proposed(fixture, "S3");
        MediaModel.EvidenceContext context = new MediaModel.EvidenceContext(
                List.of(ref("iv-1", 1L, 2)), MediaEvidence.empty(),
                List.of(new MediaEvidence.AliasBinding("S3", 1, new MediaModel.Ref("pub-1", 1L), "错根")));

        MediaModel.EvidenceBridgeOutcome outcome = MediaModel.submitWithEvidence(state,
                fixture.policy(), fixture.access(), fixture.information(), RITA,
                submitRequest("S3", List.of()), List.of(), context);

        assertFalse(outcome.accepted());
        assertEquals(MediaModel.REASON_ALIAS_ROOT, outcome.reason());
    }

    @Test
    void unreadPrivateEvidenceIsRejected() {
        Fixture fixture = fixture(1, 10_000_000L);
        MediaModel.State state = proposed(fixture, "S4");
        MediaModel.EvidenceContext context = new MediaModel.EvidenceContext(
                List.of(ref("iv-1", 1L, 1)), MediaEvidence.empty(), List.of());

        MediaModel.EvidenceBridgeOutcome outcome = MediaModel.submitWithEvidence(state,
                fixture.policy(), fixture.access(), fixture.information(), RITA,
                submitRequest("S4", List.of()), List.of(), context);

        assertFalse(outcome.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_INDEX, outcome.reason());
    }

    @Test
    void publicOnlySubmitKeepsOldBehaviour() {
        Fixture fixture = fixture(1, 10_000_000L);
        MediaModel.State state = proposed(fixture, "S5");
        MediaModel.BridgeOutcome outcome = MediaModel.submit(state, fixture.policy(),
                fixture.access(), fixture.information(), RITA,
                submitRequest("S5", List.of(ref("pub-1", 1L, 1))), List.of());
        assertTrue(outcome.accepted(), outcome.reason());
        assertEquals(1, outcome.state().stories().get(0).sourceGroups());
    }

    @Test
    void sharePublishesDirectDocumentFromSharerAndReadingIsSeparate() {
        Fixture fixture = fixture(1, 10_000_000L);
        MediaModel.Ref root = new MediaModel.Ref("iv-1", 1L);
        MediaEvidence.ShareRequest request = new MediaEvidence.ShareRequest("c1", root, ED, "", 2_000L);
        MediaEvidence.ShareOutcome shared = MediaEvidence.share(MediaEvidence.empty(),
                fixture.media(), fixture.policy(), fixture.access(), fixture.information(), RITA,
                request);

        assertTrue(shared.accepted(), shared.reason());
        assertEquals(1, shared.evidence().shares().size());
        String docId = shared.evidence().shares().get(0).sharingDocId();
        InformationModel.Document sharedDoc = document(shared.information(), docId);
        assertNotNull(sharedDoc);
        assertEquals(RITA, sharedDoc.publisherPartyId());
        assertEquals(InformationModel.AudienceKind.DIRECT, sharedDoc.audience().kind());
        assertEquals(ED, sharedDoc.audience().partyId());
        assertEquals("身份共享材料", sharedDoc.at(1L).title());
        assertTrue(sharedDoc.at(1L).body().contains(SAM), "身份由根 publisher 派生");
        assertFalse(InformationModel.canCite(shared.information(), ED, docId, 1L, 2_100L),
                "未读不等于知道");

        InformationModel.Outcome started = InformationModel.startRead(shared.information(),
                fixture.access(), ED, docId, 1L, 2_100L);
        assertEquals(InformationModel.Status.STARTED, started.status());
        String readId = started.state().active().get(0).readId();
        InformationModel.Outcome done = InformationModel.completeRead(started.state(),
                fixture.access(), ED, readId, 2_210L);
        assertEquals(InformationModel.Status.COMPLETED, done.status());
        assertTrue(InformationModel.canCite(done.state(), ED, docId, 1L, 2_220L));
    }

    @Test
    void sameCommandIsIdempotentButDifferentCommandMayReshare() {
        Fixture fixture = fixture(1, 10_000_000L);
        MediaModel.Ref root = new MediaModel.Ref("iv-1", 1L);
        MediaEvidence.ShareRequest request = new MediaEvidence.ShareRequest("c1", root, ED, "", 2_000L);
        MediaEvidence.ShareOutcome first = MediaEvidence.share(MediaEvidence.empty(),
                fixture.media(), fixture.policy(), fixture.access(), fixture.information(), RITA,
                request);
        assertTrue(first.accepted(), first.reason());

        MediaEvidence.ShareOutcome retry = MediaEvidence.share(first.evidence(), fixture.media(),
                fixture.policy(), fixture.access(), first.information(), RITA, request);
        assertTrue(retry.accepted());
        assertSame(first.evidence(), retry.evidence());

        MediaEvidence.ShareOutcome changed = MediaEvidence.share(first.evidence(), fixture.media(),
                fixture.policy(), fixture.access(), first.information(), RITA,
                new MediaEvidence.ShareRequest("c1", new MediaModel.Ref("pub-1", 1L), ED, "", 2_000L));
        assertFalse(changed.accepted());
        assertEquals(MediaEvidence.REASON_ID_TAKEN, changed.reason());

        MediaEvidence.ShareOutcome again = MediaEvidence.share(first.evidence(), fixture.media(),
                fixture.policy(), fixture.access(), first.information(), RITA,
                new MediaEvidence.ShareRequest("c2", root, ED, "", 2_000L));
        assertTrue(again.accepted(), again.reason());
        assertEquals(2, again.evidence().shares().size());
    }

    @Test
    void shareRejectsMissingAuthorityAndForgottenRoot() {
        Fixture fixture = fixture(1, 10_000_000L);
        MediaModel.Ref root = new MediaModel.Ref("iv-1", 1L);
        MediaEvidence.ShareOutcome outsider = MediaEvidence.share(MediaEvidence.empty(),
                fixture.media(), fixture.policy(), fixture.access(), fixture.information(), SAM,
                new MediaEvidence.ShareRequest("x1", root, ED, "", 2_000L));
        assertFalse(outsider.accepted());
        assertEquals(MediaEvidence.REASON_NOT_AUTHORIZED, outsider.reason());

        Fixture strict = fixture(1, 1L);
        MediaEvidence.ShareOutcome forgotten = MediaEvidence.share(MediaEvidence.empty(),
                strict.media(), strict.policy(), strict.access(), strict.information(), RITA,
                new MediaEvidence.ShareRequest("c9", root, ED, "", 900_000L));
        assertFalse(forgotten.accepted());
        assertEquals(MediaEvidence.REASON_NOT_RECALLABLE, forgotten.reason());
    }

    @Test
    void shareIdIsPerPersonAndRejectsBlank() {
        String a = MediaEvidence.shareId(RITA, "c1");
        String b = MediaEvidence.shareId(ED, "c1");
        assertFalse(a.equals(b));
        assertTrue(a.endsWith(":c1"));
        assertThrows(IllegalArgumentException.class, () -> MediaEvidence.shareId(RITA, " "));
        assertNull(MediaEvidence.labelOf(MediaEvidence.empty(), "S1", 1,
                new MediaModel.Ref("iv-1", 1L)));
    }

    private static InformationModel.Document document(InformationModel.State info, String docId) {
        for (InformationModel.Document document : info.documents()) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        return null;
    }
}
