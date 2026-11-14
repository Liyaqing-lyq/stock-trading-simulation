package edu.cufe.auction.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static edu.cufe.auction.world.MediaEvidenceFixtures.CHAIR;
import static edu.cufe.auction.world.MediaEvidenceFixtures.REPORTER;
import static edu.cufe.auction.world.MediaEvidenceFixtures.REPORTER2;
import static edu.cufe.auction.world.MediaEvidenceFixtures.REPORTER3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** intake-4 定向增量：只核 share() 新增的“解析后身份 + 保留锚点”语义。 */
class EvidenceV4Test {

    private final MediaModel.Policy policy = MediaEvidenceFixtures.policy();
    private final RoleAccess.State access = MediaEvidenceFixtures.successionRoles();

    @Test
    @DisplayName("共享的共享：正文写真实根发布者，绑定仍存请求锚点，接收者仍读不到原私证")
    void chainedShareUsesCanonicalIdentityButKeepsAnchor() {
        Chain c = chainShare();
        assertEquals(new MediaModel.Ref("D-PRIV", 1), c.plainRoot(),
                "普通共享的锚点就是真实根");
        assertEquals(new MediaModel.Ref(c.shareA(), 1), c.chained.root(),
                "链式共享的绑定保留请求锚点");
        assertTrue(c.chainedBody().contains("身份来源：" + CHAIR), c.chainedBody());
        assertFalse(c.chainedBody().contains("身份来源：" + REPORTER2), "不得写中间人");
        assertFalse(InformationModel.canCite(c.info(), REPORTER3, "D-PRIV", 1, 5_100L),
                "解析不授予中转人对原私证的读取权");
        assertFalse(InformationModel.canCite(c.info(), REPORTER, "D-PRIV", 1, 5_100L),
                "接收者同样没有原私证读取权");
        List<String> anchors = MediaEvidenceFixtures.document(c.info(), c.chained.sharingDocId())
                .versions().get(0).internalSourceIds();
        assertTrue(anchors.contains(c.shareA() + "@1"), "审计源仍是请求锚点：" + anchors);
        assertFalse(anchors.contains("D-PRIV@1"), "不把解析出的真实根写进第二账本");
    }

    @Test
    @DisplayName("锚点缺绑定：拒绝共享，不写错误身份，文档与证据状态不变")
    void missingBindingOnShareFailsClosed() {
        Chain c = chainShare();
        int docs = MediaEvidenceFixtures.docCount(c.info());
        MediaEvidence.ShareOutcome out = MediaEvidence.share(MediaEvidence.empty(),
                MediaModel.open(policy, MediaEvidenceFixtures.registry()), policy, access, c.info(), REPORTER3,
                new MediaEvidence.ShareRequest("cmd-orphan", new MediaModel.Ref(c.shareA(), 1),
                        REPORTER, "", 2_100L));
        assertFalse(out.accepted(), "缺绑定不得写出身份说明");
        assertEquals(MediaEvidence.REASON_STATE, out.reason());
        assertEquals(MediaEvidence.empty(), out.evidence());
        assertEquals(docs, MediaEvidenceFixtures.docCount(out.information()), "拒绝不新增文档");
        assertNull(MediaEvidenceFixtures.document(out.information(),
                "identity-share:" + MediaEvidence.shareId(REPORTER3, "cmd-orphan")));
    }

    @Test
    @DisplayName("锚点成环：拒绝共享而不是写出空/错身份")
    void cyclicAnchorFailsClosed() {
        Chain c = chainShare();
        MediaModel.Ref a = new MediaModel.Ref(c.shareA(), 1);
        MediaModel.Ref b = new MediaModel.Ref(c.chained.sharingDocId(), 1);
        MediaEvidence.State cycle = new MediaEvidence.State(List.of(), List.of(
                new MediaEvidence.IdentityShare("cycle-a", b, REPORTER, c.shareA(), 1),
                new MediaEvidence.IdentityShare("cycle-b", a, REPORTER,
                        c.chained.sharingDocId(), 1)));
        MediaEvidence.ShareOutcome out = MediaEvidence.share(cycle,
                MediaModel.open(policy, MediaEvidenceFixtures.registry()), policy, access, c.info(), REPORTER3,
                new MediaEvidence.ShareRequest("cmd-cycle", a, REPORTER, "", 2_100L));
        assertFalse(out.accepted());
        assertEquals(MediaEvidence.REASON_STATE, out.reason());
        assertEquals(cycle, out.evidence());
    }

    @Test
    @DisplayName("链式共享的幂等：同命令同内容不动账，换说明即 ID_TAKEN")
    void chainedShareIdempotency() {
        Chain c = chainShare();
        MediaEvidence.ShareOutcome replay = MediaEvidence.share(c.evidence,
                MediaModel.open(policy, MediaEvidenceFixtures.registry()), policy, access, c.info(), REPORTER3,
                new MediaEvidence.ShareRequest("cmd-b", new MediaModel.Ref(c.shareA(), 1), REPORTER,
                        "", 2_200L));
        assertTrue(replay.accepted(), replay.reason());
        assertEquals(2, replay.evidence().shares().size(), "重放不新增绑定");
        assertEquals(MediaEvidenceFixtures.docCount(c.info()), MediaEvidenceFixtures.docCount(replay.information()));

        MediaEvidence.ShareOutcome changed = MediaEvidence.share(c.evidence,
                MediaModel.open(policy, MediaEvidenceFixtures.registry()), policy, access, c.info(), REPORTER3,
                new MediaEvidence.ShareRequest("cmd-b", new MediaModel.Ref(c.shareA(), 1), REPORTER,
                        "补充一句", 2_200L));
        assertFalse(changed.accepted(), "同命令换说明不是幂等");
        assertEquals(MediaEvidence.REASON_ID_TAKEN, changed.reason());
    }

    // ---------------------------------------------------------------- 夹具

    private record Chain(MediaEvidence.State evidence, MediaEvidence.IdentityShare chained,
                         InformationModel.State info, String shareA, String chainedBody,
                         MediaModel.Ref plainRoot) {
    }

    /** 甲共享 D-PRIV 给乙；乙读完这份共享文档后再共享给丙。 */
    private Chain chainShare() {
        InformationModel.State info = MediaEvidenceFixtures.internal(MediaEvidenceFixtures.information(), "D-PRIV", CHAIR,
                1_000L);
        info = MediaEvidenceFixtures.read(info, access, REPORTER2, "D-PRIV", 1, 1_000L);
        MediaEvidence.ShareOutcome plain = MediaEvidence.share(MediaEvidence.empty(),
                MediaModel.open(policy, MediaEvidenceFixtures.registry()), policy, access, info, REPORTER2,
                new MediaEvidence.ShareRequest("cmd-a", new MediaModel.Ref("D-PRIV", 1), REPORTER3,
                        "", 1_200L));
        assertTrue(plain.accepted(), plain.reason());
        String shareA = plain.evidence().shares().get(0).sharingDocId();
        InformationModel.State readShare = MediaEvidenceFixtures.read(plain.information(), access, REPORTER3,
                shareA, 1, 1_600L);
        MediaEvidence.ShareOutcome chained = MediaEvidence.share(plain.evidence(),
                MediaModel.open(policy, MediaEvidenceFixtures.registry()), policy, access, readShare, REPORTER3,
                new MediaEvidence.ShareRequest("cmd-b", new MediaModel.Ref(shareA, 1), REPORTER, "",
                        2_000L));
        assertTrue(chained.accepted(), chained.reason());
        MediaEvidence.IdentityShare binding = chained.evidence().shares().stream()
                .filter(share -> share.shareId().equals(MediaEvidence.shareId(REPORTER3, "cmd-b")))
                .findFirst().orElseThrow();
        String body = MediaEvidenceFixtures.document(chained.information(), binding.sharingDocId())
                .versions().get(0).body();
        MediaModel.Ref plainRoot = plain.evidence().shares().get(0).root();
        return new Chain(chained.evidence(), binding, chained.information(), shareA, body, plainRoot);
    }
}
