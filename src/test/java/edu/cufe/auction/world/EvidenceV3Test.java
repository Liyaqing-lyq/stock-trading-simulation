package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.InformationModel.Audience;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.RoleAccess.Appointment;
import edu.cufe.auction.world.RoleAccess.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static edu.cufe.auction.world.MediaEvidenceFixtures.CFO;
import static edu.cufe.auction.world.MediaEvidenceFixtures.CHAIR;
import static edu.cufe.auction.world.MediaEvidenceFixtures.EDITOR;
import static edu.cufe.auction.world.MediaEvidenceFixtures.MEDIA;
import static edu.cufe.auction.world.MediaEvidenceFixtures.REPORTER;
import static edu.cufe.auction.world.MediaEvidenceFixtures.REPORTER2;
import static edu.cufe.auction.world.MediaEvidenceFixtures.REPORTER3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * intake-3 增量独立验收：只核 F1/F2 修复引入的新路径与纯核边界。
 *
 * <p>夹具全部用真实文档、真实阅读、真实任职；身份共享状态一律由 {@link MediaEvidence#share} 真实生成，
 * 需要构造异常绑定时才显式构造 {@link MediaEvidence.State}（受信上下文）。</p>
 */
class EvidenceV3Test {

    private final MediaModel.Policy policy = MediaEvidenceFixtures.policy();

    // ---------------------------------------------------------------- 同源两分享

    @Test
    @DisplayName("同一真实根的两份真实共享文档只算一组，调查模板不能靠分享洗成两源")
    void twoSharesOfOneRealRootCountAsOneSource() {
        Scenario s = twoShares();

        MediaModel.EvidenceBridgeOutcome wire = MediaModel.submitWithEvidence(s.media, policy,
                s.access, s.info, REPORTER, submit("S-W1", 3_000L, 4_000L), List.of(), s.context());
        assertTrue(wire.accepted(), wire.reason());
        MediaModel.Story story = MediaEvidenceFixtures.story(wire.state(), "S-W1");
        assertEquals(List.of(new MediaModel.Ref("D-PRIV", 1)), story.roots(),
                "根必须是真实根，不是两份共享文档");
        assertEquals(1, story.sourceGroups(), "两位分享者不是两组来源");
        assertEquals(2, story.evidence().size(), "两份共享文档仍是两条真实阅读记录");
        assertEquals(2, wire.evidence().shares().size(), "共享记录本身保留");
        for (MediaEvidence.IdentityShare share : wire.evidence().shares()) {
            assertEquals(new MediaModel.Ref("D-PRIV", 1), share.root(),
                    "两条绑定指向同一真实根：" + share.sharingDocId());
        }

        MediaModel.State inv = MediaModel.propose(wire.state(), policy, s.access, REPORTER,
                new MediaModel.ProposeRequest("S-W2", MediaModel.TemplateKind.INVESTIGATIVE,
                        "两源调查", 5_000L)).state();
        MediaModel.EvidenceBridgeOutcome denied = MediaModel.submitWithEvidence(inv, policy,
                s.access, wire.information(), REPORTER, submit("S-W2", 6_000L, 7_000L), List.of(),
                s.context());
        assertFalse(denied.accepted(), "只有一份真实来源，不能过两源门槛");
        assertEquals(MediaModel.REASON_INSUFFICIENT_SOURCES, denied.reason());
        assertEquals(inv, denied.state());
    }

    @Test
    @DisplayName("别名必须锚在解析后的真实根上；锚到共享文档本身被拒")
    void aliasMustAnchorResolvedRoot() {
        Scenario s = twoShares();
        MediaModel.Ref real = new MediaModel.Ref("D-PRIV", 1);
        MediaModel.Ref shareDoc = new MediaModel.Ref(s.shareA, 1);

        MediaModel.EvidenceBridgeOutcome bogus = MediaModel.submitWithEvidence(s.media, policy,
                s.access, s.info, REPORTER, submit("S-W1", 3_000L, 4_000L), List.of(),
                new MediaModel.EvidenceContext(s.refs, s.evidence, List.of(
                        new MediaEvidence.AliasBinding("S-W1", 1, shareDoc, "共享文档"))));
        assertFalse(bogus.accepted());
        assertEquals(MediaModel.REASON_ALIAS_ROOT, bogus.reason());
        assertEquals(s.media, bogus.state(), "别名失败不得半写");
        assertEquals(s.evidence, bogus.evidence());

        MediaModel.EvidenceBridgeOutcome ok = MediaModel.submitWithEvidence(s.media, policy,
                s.access, s.info, REPORTER, submit("S-W1", 3_000L, 4_000L), List.of(),
                new MediaModel.EvidenceContext(s.refs, s.evidence, List.of(
                        new MediaEvidence.AliasBinding("S-W1", 1, real, "内部人士"))));
        assertTrue(ok.accepted(), ok.reason());
        assertEquals("内部人士", MediaEvidence.labelOf(ok.evidence(), "S-W1", 1, real));
        assertNull(MediaEvidence.labelOf(ok.evidence(), "S-W1", 1, shareDoc));
    }

    // ---------------------------------------------------------------- 公开转载

    @Test
    @DisplayName("私根经共享成稿再被公开转载：根仍只有一个，转载者仍无读取原私证权限")
    void publicTranscriptionKeepsSingleRootWithoutPrivateAccess() {
        RoleAccess.State access = handover();
        InformationModel.State info = MediaEvidenceFixtures.internal(MediaEvidenceFixtures.information(), "D-PRIV", CHAIR,
                1_000L);
        info = MediaEvidenceFixtures.read(info, access, REPORTER2, "D-PRIV", 1, 1_000L);
        MediaEvidence.ShareOutcome shared = MediaEvidence.share(MediaEvidence.empty(),
                MediaModel.open(policy, MediaEvidenceFixtures.registry()), policy, access, info, REPORTER2,
                new MediaEvidence.ShareRequest("cmd-a", new MediaModel.Ref("D-PRIV", 1), REPORTER3,
                        "", 1_500L));
        assertTrue(shared.accepted(), shared.reason());
        String shareDoc = shared.evidence().shares().get(0).sharingDocId();
        InformationModel.State withRead = MediaEvidenceFixtures.read(shared.information(), access, REPORTER3,
                shareDoc, 1, 4_100L);

        MediaModel.State media = MediaModel.propose(MediaModel.open(policy, MediaEvidenceFixtures.registry()),
                policy, access, REPORTER3, new MediaModel.ProposeRequest("S-P1",
                        MediaModel.TemplateKind.WIRE, "共享稿", 4_500L)).state();
        MediaModel.EvidenceBridgeOutcome first = MediaModel.submitWithEvidence(media, policy,
                access, withRead, REPORTER3, submit("S-P1", 5_000L, 6_000L), List.of(),
                new MediaModel.EvidenceContext(
                        List.of(MediaEvidenceFixtures.ref(withRead, REPORTER3, shareDoc, 1)),
                        shared.evidence(), List.of()));
        assertTrue(first.accepted(), first.reason());
        assertEquals(List.of(new MediaModel.Ref("D-PRIV", 1)),
                MediaEvidenceFixtures.story(first.state(), "S-P1").roots());

        MediaModel.State readForEditor = first.state();
        MediaModel.Story story = MediaEvidenceFixtures.story(readForEditor, "S-P1");
        InformationModel.State editorRead = MediaEvidenceFixtures.read(first.information(), access, EDITOR,
                story.draftDocId(), story.draftVersion(), 6_100L);
        MediaModel.Outcome approved = MediaModel.review(readForEditor, policy, access, editorRead,
                EDITOR, new MediaModel.ReviewRequest("S-P1", 1, true, "", 7_000L, 9_000L));
        assertTrue(approved.accepted(), approved.reason());
        MediaModel.BridgeOutcome published = MediaModel.publish(approved.state(), policy, access,
                editorRead, EDITOR, new MediaModel.PublishRequest("S-P1", 1, 10_000L, 13_000L));
        assertTrue(published.accepted(), published.reason());
        String publicDocId = MediaEvidenceFixtures.story(published.state(), "S-P1").publicDocId();
        InformationModel.State reader = MediaEvidenceFixtures.read(published.information(), access, REPORTER,
                publicDocId, 1, 14_000L);

        assertFalse(InformationModel.canCite(reader, REPORTER, "D-PRIV", 1, 20_000L),
                "转载者没有读过、也不该能读原私证");
        MediaModel.State media3 = MediaModel.propose(published.state(), policy, access, REPORTER,
                new MediaModel.ProposeRequest("S-P2", MediaModel.TemplateKind.WIRE,
                        "转载调查", 14_500L)).state();
        MediaModel.EvidenceBridgeOutcome second = MediaModel.submitWithEvidence(media3, policy,
                access, reader, REPORTER,
                new MediaModel.SubmitRequest("S-P2", 0, "转载", "正文", "据同行稿",
                        List.of(MediaEvidenceFixtures.ref(reader, REPORTER, publicDocId, 1)), 15_000L, 16_000L),
                List.of(), MediaModel.EvidenceContext.none());
        assertTrue(second.accepted(), second.reason());
        MediaModel.Story copied = MediaEvidenceFixtures.story(second.state(), "S-P2");
        assertEquals(List.of(new MediaModel.Ref("D-PRIV", 1)), copied.roots(),
                "转载继承的仍是那一个真实根");
        assertEquals(1, copied.sourceGroups(), "公开转载不新增来源组");
        assertFalse(InformationModel.canCite(second.information(), REPORTER, "D-PRIV", 1, 25_000L),
                "继承根不等于获得读取授权");
    }

    // ---------------------------------------------------------------- 共享的共享

    @Test
    @DisplayName("共享的共享：绑定指向中转文档、正文身份指向前一手，但来源仍只算一组")
    void shareOfShareChainsToSingleSource() {
        RoleAccess.State access = handover();
        InformationModel.State info = MediaEvidenceFixtures.internal(MediaEvidenceFixtures.information(), "D-PRIV", CHAIR,
                1_000L);
        info = MediaEvidenceFixtures.read(info, access, REPORTER2, "D-PRIV", 1, 1_000L);
        MediaEvidence.ShareOutcome first = MediaEvidence.share(MediaEvidence.empty(),
                MediaModel.open(policy, MediaEvidenceFixtures.registry()), policy, access, info, REPORTER2,
                new MediaEvidence.ShareRequest("cmd-a", new MediaModel.Ref("D-PRIV", 1), REPORTER3,
                        "", 1_500L));
        assertTrue(first.accepted(), first.reason());
        String shareA = first.evidence().shares().get(0).sharingDocId();

        InformationModel.State readShareA = MediaEvidenceFixtures.read(first.information(), access, REPORTER3,
                shareA, 1, 4_100L);
        MediaEvidence.ShareOutcome second = MediaEvidence.share(first.evidence(),
                MediaModel.open(policy, MediaEvidenceFixtures.registry()), policy, access, readShareA, REPORTER3,
                new MediaEvidence.ShareRequest("cmd-b", new MediaModel.Ref(shareA, 1), REPORTER, "",
                        5_000L));
        assertTrue(second.accepted(), "以共享文档为根再共享被接受（本轮行为）");
        MediaEvidence.IdentityShare chained = second.evidence().shares().stream()
                .filter(share -> share.shareId().equals(MediaEvidence.shareId(REPORTER3, "cmd-b")))
                .findFirst().orElseThrow();
        assertEquals(new MediaModel.Ref(shareA, 1), chained.root(), "绑定记录的是中转文档，不是真实根");
        String body = MediaEvidenceFixtures.document(second.information(), chained.sharingDocId())
                .versions().get(0).body();
        assertTrue(body.contains("身份来源：" + CHAIN_PUBLISHER), "正文必须写真实来源：" + body);
        assertFalse(body.contains("身份来源：" + REPORTER2), "正文不能写上一手分享者：" + body);
        assertFalse(InformationModel.canCite(second.information(), REPORTER3, "D-PRIV", 1L, 5_100L),
                "沿绑定解析不授予中转人对原私证的读取权");

        // 但提交侧沿链解析：引用这份二手共享文档仍然只有一组、根是真实根
        InformationModel.State readChain = MediaEvidenceFixtures.read(second.information(), access, REPORTER,
                chained.sharingDocId(), 1, 8_100L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy, MediaEvidenceFixtures.registry()),
                policy, access, REPORTER, new MediaModel.ProposeRequest("S-CH",
                        MediaModel.TemplateKind.INVESTIGATIVE, "链式共享", 8_200L)).state();
        MediaModel.EvidenceBridgeOutcome out = MediaModel.submitWithEvidence(media, policy, access,
                readChain, REPORTER,
                new MediaModel.SubmitRequest("S-CH", 0, "标题", "正文", "", List.of(), 8_500L,
                        9_500L), List.of(),
                new MediaModel.EvidenceContext(
                        List.of(MediaEvidenceFixtures.ref(readChain, REPORTER, chained.sharingDocId(), 1)),
                        second.evidence(), List.of()));
        assertEquals(MediaModel.REASON_INSUFFICIENT_SOURCES, out.reason(),
                "二手共享链只回一个真实源，过不了两源门槛");
        MediaModel.State wire = MediaModel.propose(MediaModel.open(policy, MediaEvidenceFixtures.registry()),
                policy, access, REPORTER, new MediaModel.ProposeRequest("S-CH2",
                        MediaModel.TemplateKind.WIRE, "链式共享", 8_200L)).state();
        MediaModel.EvidenceBridgeOutcome wireOut = MediaModel.submitWithEvidence(wire, policy,
                access, readChain, REPORTER,
                new MediaModel.SubmitRequest("S-CH2", 0, "标题", "正文", "", List.of(), 8_500L,
                        9_500L), List.of(),
                new MediaModel.EvidenceContext(
                        List.of(MediaEvidenceFixtures.ref(readChain, REPORTER, chained.sharingDocId(), 1)),
                        second.evidence(), List.of()));
        assertTrue(wireOut.accepted(), wireOut.reason());
        assertEquals(List.of(new MediaModel.Ref("D-PRIV", 1)),
                MediaEvidenceFixtures.story(wireOut.state(), "S-CH2").roots());
        assertEquals(1, MediaEvidenceFixtures.story(wireOut.state(), "S-CH2").sourceGroups());
    }

    private static final String CHAIN_PUBLISHER = "chair";

    // ---------------------------------------------------------------- 绑定边界

    @Test
    @DisplayName("丢弃绑定：已知命名空间但无绑定的共享文档一律 fail closed")
    void droppedBindingFailsClosed() {
        Scenario s = twoShares();
        MediaModel.EvidenceBridgeOutcome lost = MediaModel.submitWithEvidence(s.media, policy,
                s.access, s.info, REPORTER, submit("S-W1", 3_000L, 4_000L), List.of(),
                new MediaModel.EvidenceContext(s.refs, MediaEvidence.empty(), List.of()));
        assertFalse(lost.accepted(), "缺绑定不能当普通私证");
        assertEquals(MediaModel.REASON_EVIDENCE_PROVENANCE, lost.reason());
        assertEquals(s.media, lost.state());
        assertEquals(MediaEvidence.empty(), lost.evidence());

        // 只有别名、没有共享：同样不得凭空解析
        MediaModel.EvidenceBridgeOutcome aliasOnly = MediaModel.submitWithEvidence(s.media, policy,
                s.access, s.info, REPORTER, submit("S-W1", 3_000L, 4_000L), List.of(),
                new MediaModel.EvidenceContext(s.refs, new MediaEvidence.State(List.of(
                        new MediaEvidence.AliasBinding("S-W1", 1,
                                new MediaModel.Ref("D-PRIV", 1), "内部人士")), List.of()),
                        List.of()));
        assertFalse(aliasOnly.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_PROVENANCE, aliasOnly.reason());
    }

    @Test
    @DisplayName("换绑定/未来根：版本不一致或根晚于共享文档一律拒绝")
    void inconsistentBindingFailsClosed() {
        Scenario s = twoShares();
        MediaModel.Ref shareA = new MediaModel.Ref(s.shareA, 1);
        MediaEvidence.IdentityShare real = s.evidence.shares().stream()
                .filter(share -> share.sharingDocId().equals(s.shareA))
                .findFirst().orElseThrow();

        // 版本不一致：绑定写 v2，文档只有 v1
        MediaEvidence.State wrongVersion = new MediaEvidence.State(List.of(), List.of(
                new MediaEvidence.IdentityShare(real.shareId(), real.root(),
                        real.recipientPartyId(), real.sharingDocId(), 2)));
        expectProvenance(s, wrongVersion, "S-W1");

        // 根晚于共享文档：根换成共享之后才发布的文档
        InformationModel.State late = MediaEvidenceFixtures.internal(s.info, "D-LATE", CHAIR, 9_000L);
        MediaEvidence.State futureRoot = new MediaEvidence.State(List.of(), List.of(
                new MediaEvidence.IdentityShare(real.shareId(), new MediaModel.Ref("D-LATE", 1),
                        real.recipientPartyId(), real.sharingDocId(), real.sharingVersion())));
        MediaModel.EvidenceBridgeOutcome out = MediaModel.submitWithEvidence(s.media, policy,
                s.access, late, REPORTER, submit("S-W1", 3_000L, 4_000L), List.of(),
                new MediaModel.EvidenceContext(s.refs, futureRoot, List.of()));
        assertFalse(out.accepted(), "共享文档不能早于它声称的根");
        assertEquals(MediaModel.REASON_EVIDENCE_PROVENANCE, out.reason());

        // 根版本不存在
        MediaEvidence.State missingRoot = new MediaEvidence.State(List.of(), List.of(
                new MediaEvidence.IdentityShare(real.shareId(), new MediaModel.Ref("D-PRIV", 7),
                        real.recipientPartyId(), real.sharingDocId(), real.sharingVersion())));
        MediaModel.EvidenceBridgeOutcome missing = MediaModel.submitWithEvidence(s.media, policy,
                s.access, s.info, REPORTER, submit("S-W1", 3_000L, 4_000L), List.of(),
                new MediaModel.EvidenceContext(s.refs, missingRoot, List.of()));
        assertFalse(missing.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_PROVENANCE, missing.reason());

        // 未来版本：共享文档已发 v2，绑定仍是 v1，引用 v2 必须拒绝
        InformationModel.State withV2 = InformationModel.publishVersion(s.info, s.shareA,
                new InformationModel.VersionDraft("身份共享材料", "第二版", "", 2_750L, 100L,
                        List.of("D-PRIV@1")));
        InformationModel.State readV2 = MediaEvidenceFixtures.read(withV2, s.access, REPORTER, s.shareA, 2,
                2_800L);
        MediaModel.EvidenceBridgeOutcome future = MediaModel.submitWithEvidence(s.media, policy,
                s.access, readV2, REPORTER,
                new MediaModel.SubmitRequest("S-W1", 0, "标题", "正文", "",
                        List.of(), 3_000L, 4_000L), List.of(),
                new MediaModel.EvidenceContext(
                        List.of(MediaEvidenceFixtures.ref(readV2, REPORTER, s.shareA, 2)), s.evidence,
                        List.of()));
        assertFalse(future.accepted(), "绑定只覆盖 v1，引用 v2 不得解析成真实根");
        assertEquals(MediaModel.REASON_EVIDENCE_PROVENANCE, future.reason());
    }

    @Test
    @DisplayName("成环绑定：A→B、B→A 的共享链被拒绝")
    void cyclicBindingFailsClosed() {
        Scenario s = twoShares();
        MediaModel.Ref a = new MediaModel.Ref(s.shareA, 1);
        MediaModel.Ref b = new MediaModel.Ref(s.shareB, 1);
        MediaEvidence.State cycle = new MediaEvidence.State(List.of(), List.of(
                new MediaEvidence.IdentityShare("cycle-a", b, REPORTER, s.shareA, 1),
                new MediaEvidence.IdentityShare("cycle-b", a, REPORTER, s.shareB, 1)));
        expectProvenance(s, cycle, "S-W1");
    }

    @Test
    @DisplayName("未知共享文档：命名空间内但既无绑定也不是真实资料，公开/私下引用都拒绝")
    void unknownShareDocumentFailsClosed() {
        Scenario s = twoShares();
        InformationModel.State withGhost = MediaEvidenceFixtures.direct(s.info, "identity-share:ghost", CHAIR,
                REPORTER, 1_000L);
        InformationModel.State ghostRead = MediaEvidenceFixtures.read(withGhost, s.access, REPORTER,
                "identity-share:ghost", 1, 2_600L);
        MediaModel.EvidenceBridgeOutcome privateCite = MediaModel.submitWithEvidence(s.media, policy,
                s.access, ghostRead, REPORTER, submit("S-W1", 3_000L, 4_000L), List.of(),
                new MediaModel.EvidenceContext(
                        List.of(MediaEvidenceFixtures.ref(ghostRead, REPORTER, "identity-share:ghost", 1)),
                        s.evidence, List.of()));
        assertFalse(privateCite.accepted());
        assertEquals(MediaModel.REASON_EVIDENCE_PROVENANCE, privateCite.reason());

        MediaModel.EvidenceBridgeOutcome publicCite = MediaModel.submitWithEvidence(s.media, policy,
                s.access, ghostRead, REPORTER,
                new MediaModel.SubmitRequest("S-W1", 0, "标题", "正文", "",
                        List.of(MediaEvidenceFixtures.ref(ghostRead, REPORTER, "identity-share:ghost", 1)),
                        3_000L, 4_000L), List.of(),
                new MediaModel.EvidenceContext(List.of(), s.evidence, List.of()));
        assertFalse(publicCite.accepted());
        assertTrue(publicCite.reason().startsWith("EVIDENCE"), publicCite.reason());
    }

    // ---------------------------------------------------------------- 结构可存

    @Test
    @DisplayName("EvidenceState 只凭 record 结构即可被 Jackson 往返，不需新增实现")
    void evidenceStateRoundTripsThroughJackson() throws Exception {
        Scenario s = twoShares();
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(s.evidence);
        MediaEvidence.State restored = mapper.readValue(json, MediaEvidence.State.class);
        assertEquals(s.evidence, restored);
        assertEquals(s.evidence.shares().size(), restored.shares().size());

        MediaEvidence.State withAlias = MediaEvidence.appendAliases(s.evidence, "S-RT", 1,
                List.of(new MediaModel.Ref("D-PRIV", 1)),
                List.of(new MediaEvidence.AliasBinding("S-RT", 1, new MediaModel.Ref("D-PRIV", 1),
                        "内部人士")));
        MediaEvidence.State back = mapper.readValue(mapper.writeValueAsString(withAlias),
                MediaEvidence.State.class);
        assertEquals(withAlias, back);
        assertEquals("内部人士", MediaEvidence.labelOf(back, "S-RT", 1,
                new MediaModel.Ref("D-PRIV", 1)));
        assertEquals(2, back.shares().size(), "共享记录在往返后仍完整");
    }

    // ---------------------------------------------------------------- 夹具

    /** 两位前后任记者各把同一真实根共享给第三任记者。 */
    private Scenario twoShares() {
        RoleAccess.State access = MediaEvidenceFixtures.successionRoles();
        InformationModel.State info = MediaEvidenceFixtures.internal(MediaEvidenceFixtures.information(), "D-PRIV", CHAIR,
                1_000L);
        info = MediaEvidenceFixtures.read(info, access, REPORTER2, "D-PRIV", 1, 1_000L);
        MediaEvidence.ShareOutcome one = MediaEvidence.share(MediaEvidence.empty(),
                MediaModel.open(policy, MediaEvidenceFixtures.registry()), policy, access, info, REPORTER2,
                new MediaEvidence.ShareRequest("cmd-a", new MediaModel.Ref("D-PRIV", 1), REPORTER,
                        "", 1_200L));
        assertTrue(one.accepted(), one.reason());
        InformationModel.State info2 = MediaEvidenceFixtures.read(one.information(), access, REPORTER3,
                "D-PRIV", 1, 1_600L);
        MediaEvidence.ShareOutcome two = MediaEvidence.share(one.evidence(),
                MediaModel.open(policy, MediaEvidenceFixtures.registry()), policy, access, info2, REPORTER3,
                new MediaEvidence.ShareRequest("cmd-b", new MediaModel.Ref("D-PRIV", 1), REPORTER,
                        "", 2_000L));
        assertTrue(two.accepted(), two.reason());
        String shareA = one.evidence().shares().get(0).sharingDocId();
        String shareB = two.evidence().shares().stream()
                .filter(share -> !share.sharingDocId().equals(shareA))
                .findFirst().orElseThrow().sharingDocId();
        InformationModel.State info3 = MediaEvidenceFixtures.read(two.information(), access, REPORTER, shareA, 1,
                2_600L);
        info3 = MediaEvidenceFixtures.read(info3, access, REPORTER, shareB, 1, 2_700L);
        MediaModel.State media = MediaModel.propose(MediaModel.open(policy, MediaEvidenceFixtures.registry()),
                policy, access, REPORTER, new MediaModel.ProposeRequest("S-W1",
                        MediaModel.TemplateKind.WIRE, "共享稿", 2_800L)).state();
        return new Scenario(access, info3, media, two.evidence(), shareA, shareB,
                List.of(MediaEvidenceFixtures.ref(info3, REPORTER, shareA, 1),
                        MediaEvidenceFixtures.ref(info3, REPORTER, shareB, 1)));
    }

    private RoleAccess.State handover() {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of(
                new Appointment("AP-A", REPORTER2, MEDIA, Role.JOURNALIST, 0L, 4_000L, null),
                new Appointment("AP-B", REPORTER3, MEDIA, Role.JOURNALIST, 4_000L, 8_000L, null),
                new Appointment("AP-C", REPORTER, MEDIA, Role.JOURNALIST, 8_000L, MediaEvidenceFixtures.END, null),
                new Appointment("AP-E", EDITOR, MEDIA, Role.EDITOR, 0L, MediaEvidenceFixtures.END, null)));
    }

    private record Scenario(RoleAccess.State access, InformationModel.State info,
                            MediaModel.State media, MediaEvidence.State evidence, String shareA,
                            String shareB, List<RoleView.ReadingRef> refs) {

        MediaModel.EvidenceContext context() {
            return new MediaModel.EvidenceContext(refs, evidence, List.of());
        }
    }

    private MediaModel.SubmitRequest submit(String storyId, long startedAt, long completedAt) {
        return new MediaModel.SubmitRequest(storyId, 0, "标题", "正文", "", List.of(), startedAt,
                completedAt);
    }

    private void expectProvenance(Scenario s, MediaEvidence.State state, String storyId) {
        MediaModel.EvidenceBridgeOutcome out = MediaModel.submitWithEvidence(s.media, policy,
                s.access, s.info, REPORTER, submit(storyId, 3_000L, 4_000L), List.of(),
                new MediaModel.EvidenceContext(s.refs, state, List.of()));
        assertFalse(out.accepted(), "绑定异常必须 fail closed：" + storyId);
        assertEquals(MediaModel.REASON_EVIDENCE_PROVENANCE, out.reason());
        assertEquals(MediaEvidence.State.class, state.getClass());
    }
}
