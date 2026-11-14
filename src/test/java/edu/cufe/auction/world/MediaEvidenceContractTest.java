package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Root 静态线索的复现与回归：幂等内容、已有共享文档碰撞、旧拒绝理由次序。 */
class MediaEvidenceContractTest {

    private static final String MEDIA_A = "media-a";
    private static final String RITA = "rita";
    private static final String SAM = "sam";
    private static final String ED = "ed";

    private static OpeningRegistry registry() {
        return new OpeningRegistry(List.of(
                new OpeningRegistry.Party(RITA, OpeningRegistry.PartyKind.PERSON, "记者"),
                new OpeningRegistry.Party(SAM, OpeningRegistry.PartyKind.PERSON, "信源"),
                new OpeningRegistry.Party(ED, OpeningRegistry.PartyKind.PERSON, "编辑"),
                new OpeningRegistry.Party(MEDIA_A, OpeningRegistry.PartyKind.ORGANIZATION, "媒体")),
                List.of(), List.of(), List.of());
    }

    private static RoleAccess.State access() {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new RoleAccess.Appointment("ap-1", RITA, MEDIA_A, RoleAccess.Role.JOURNALIST,
                        0L, 1_000_000L, null),
                new RoleAccess.Appointment("ap-2", ED, MEDIA_A, RoleAccess.Role.EDITOR,
                        0L, 1_000_000L, null)));
    }

    private static MediaModel.Policy policy() {
        return new MediaModel.Policy(MEDIA_A, Map.of(MediaModel.TemplateKind.WIRE, 1),
                1_000L, 1_000L, 1_000L, 100L, 100L,
                new CognitionRecall.Policy(10_000_000L, 1, 100, 100_000L));
    }

    private static InformationModel.State world() {
        InformationModel.State info = InformationModel.open(registry(), List.of(RITA, SAM, ED));
        info = InformationModel.publish(info, "iv-1", SAM,
                InformationModel.Audience.direct(RITA),
                new InformationModel.VersionDraft("采访记录", "订单减少", "", 100L, 10L, List.of()));
        InformationModel.Outcome started = InformationModel.startRead(info, access(), RITA, "iv-1",
                1L, 1_000L);
        String readId = started.state().active().get(0).readId();
        return InformationModel.completeRead(started.state(), access(), RITA, readId, 1_010L).state();
    }

    private static MediaModel.State media() {
        return MediaModel.open(policy(), registry());
    }

    @Test
    void sameCommandWithDifferentNoteIsRejected() {
        InformationModel.State info = world();
        MediaModel.Ref root = new MediaModel.Ref("iv-1", 1L);
        MediaEvidence.ShareOutcome first = MediaEvidence.share(MediaEvidence.empty(), media(),
                policy(), access(), info, RITA,
                new MediaEvidence.ShareRequest("c1", root, ED, "", 2_000L));
        assertTrue(first.accepted(), first.reason());

        MediaEvidence.ShareOutcome changed = MediaEvidence.share(first.evidence(), media(),
                policy(), access(), first.information(), RITA,
                new MediaEvidence.ShareRequest("c1", root, ED, "补充身份", 2_500L));
        assertFalse(changed.accepted(), "同命令换内容必须拒绝");
        assertEquals(MediaEvidence.REASON_ID_TAKEN, changed.reason());
    }

    @Test
    void retryAtLaterTimeIsStillIdempotent() {
        InformationModel.State info = world();
        MediaModel.Ref root = new MediaModel.Ref("iv-1", 1L);
        MediaEvidence.ShareOutcome first = MediaEvidence.share(MediaEvidence.empty(), media(),
                policy(), access(), info, RITA,
                new MediaEvidence.ShareRequest("c1", root, ED, "", 2_000L));
        assertTrue(first.accepted(), first.reason());
        MediaEvidence.ShareOutcome retry = MediaEvidence.share(first.evidence(), media(), policy(),
                access(), first.information(), RITA,
                new MediaEvidence.ShareRequest("c1", root, ED, "", 3_000L));
        assertTrue(retry.accepted(), "重试时点不同不算内容变化");
        assertEquals(1, retry.evidence().shares().size());
    }

    @Test
    void preexistingIdentityDocWithoutBindingIsNotOverwritten() {
        InformationModel.State info = world();
        MediaModel.Ref root = new MediaModel.Ref("iv-1", 1L);
        String shareId = MediaEvidence.shareId(RITA, "c1");
        info = InformationModel.publish(info, "identity-share:" + shareId, RITA,
                InformationModel.Audience.direct(ED),
                new InformationModel.VersionDraft("旧的无关文档", "别的正文", "", 1_500L, 10L,
                        List.of()));

        MediaEvidence.ShareOutcome outcome = MediaEvidence.share(MediaEvidence.empty(), media(),
                policy(), access(), info, RITA,
                new MediaEvidence.ShareRequest("c1", root, ED, "", 2_000L));
        assertFalse(outcome.accepted(), "已有同标识文档不得被覆盖");
        assertEquals(MediaEvidence.REASON_STATE, outcome.reason());
        assertEquals(1, document(info, "identity-share:" + shareId).versions().size(),
                "旧文档版本不变");
    }

    @Test
    void oldPublicEvidenceRejectionOrderIsPreserved() {
        InformationModel.State info = world();
        info = InformationModel.publish(info, "iv-unread", SAM,
                InformationModel.Audience.direct(RITA),
                new InformationModel.VersionDraft("未读记录", "内容", "", 1_200L, 10L, List.of()));
        MediaModel.State state = MediaModel.open(policy(), registry());
        state = MediaModel.propose(state, policy(), access(), RITA,
                new MediaModel.ProposeRequest("S9", MediaModel.TemplateKind.WIRE, "选题", 1_000L))
                .state();
        MediaModel.BridgeOutcome outcome = MediaModel.submit(state, policy(), access(), info, RITA,
                new MediaModel.SubmitRequest("S9", 0, "标题", "正文", "来源",
                        List.of(new RoleView.ReadingRef(RITA, "iv-unread", 1L, 1)), 1_000L, 2_000L),
                List.of());
        assertFalse(outcome.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_NOT_PUBLIC, outcome.reason(),
                "未读的非公开证据在旧入口仍先报 NOT_PUBLIC，而不是 NOT_READ");
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
