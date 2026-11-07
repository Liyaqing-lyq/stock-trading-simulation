package edu.cufe.auction.world;

import edu.cufe.auction.world.InformationModel.Audience;
import edu.cufe.auction.world.InformationModel.CloseReason;
import edu.cufe.auction.world.InformationModel.ReadObservation;
import edu.cufe.auction.world.InformationModel.VersionDraft;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.PropertyOwner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4g.1 信念、来源可靠性与慢习惯纯核合同测试。
 *
 * <p>所有资料状态都由真实 {@link InformationModel} 的 publish/startRead/cancelRead/completeRead 产生，
 * 不使用平行玩具状态；时间全部显式模拟毫秒，不读墙钟。证据序号按既有“本人全部阅读（含取消/拒绝）
 * 的序号”规则在测试内独立推算，再从实现核对。</p>
 */
class BeliefModelTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";
    private static final String COMPANY = "cufe";
    private static final String METRIC = BeliefModel.METRIC_ORDERS_CONFIRMED;

    private static final long MIN = 60_000L;
    private static final long DAY = 86_400_000L;
    private static final long T0 = 10 * DAY;
    private static final long DUR = 30 * MIN;
    private static final long HORIZON = 30 * DAY;

    private static final BeliefModel.Policy POLICY =
            new BeliefModel.Policy(200, 8, HORIZON, 20, 200, 3, 30 * DAY);
    private static final CognitionRecall.Policy RECALL =
            new CognitionRecall.Policy(100 * DAY, 1, 100, 1_000_000L);

    private static final OpeningRegistry REGISTRY = registry();
    private static final RoleAccess.State ACCESS = RoleAccess.open(REGISTRY, List.of(), List.of());

    // ---------------------------------------------------------------- 夹具

    @Test
    @DisplayName("真实发布→开始→完成阅读后形成判断：字段、置信、公开来源组与证据序号全部来自真实状态")
    void formsFromRealReadingChain() {
        Fixture f = new Fixture()
                .publish("E-A", CAROL, "公司公告A", T0 - 3_000L)
                .read(ALICE, "E-A", T0);
        long formedAt = f.now();
        BeliefModel.Outcome outcome = f.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST, 3,
                formedAt + 5 * DAY, CAROL, List.of("E-A"));
        assertTrue(outcome.accepted(), outcome.reason());
        BeliefModel.Belief belief = belief(outcome.state(), "B-1");
        assertEquals(COMPANY, belief.companyId());
        assertEquals(METRIC, belief.metric());
        assertEquals(formedAt + 5 * DAY, belief.targetAsOfMillis());
        assertEquals(CAROL, belief.verificationPublisherPartyId());
        assertEquals(500, belief.confidencePermille(), "自拟判断置信恒为 500");
        assertNull(belief.adoptedClaimId());
        assertNull(belief.sourcePartyId());
        assertEquals(List.of("公司公告A"), belief.publicSourceGroups());
        assertEquals(1, belief.evidence().size());
        assertEquals(1, belief.evidence().get(0).index(), "首次完成阅读的本人序号为 1");
        assertEquals(ALICE, belief.evidence().get(0).personPartyId());
    }

    @Test
    @DisplayName("目标时点必须在未来且不超过 maxHorizon；负时刻拒绝")
    void rejectsInvalidTarget() {
        Fixture f = new Fixture().publish("E-A", CAROL, "来源A", T0 - 3_000L).read(ALICE, "E-A", T0);
        long now = f.now();
        assertEquals(BeliefModel.REASON_TARGET, f.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST,
                3, now, CAROL, List.of("E-A")).reason(), "等于当前时刻不是未来");
        assertEquals(BeliefModel.REASON_TARGET, f.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST,
                3, now + HORIZON + 1, CAROL, List.of("E-A")).reason(), "超过硬上限");
        assertEquals(BeliefModel.REASON_TARGET, f.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST,
                3, -1, CAROL, List.of("E-A")).reason(), "负时点");
        assertTrue(f.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST, 3, now + HORIZON, CAROL,
                List.of("E-A")).accepted(), "恰好等于上限可接受");
    }

    @Test
    @DisplayName("未完成/取消/拒绝/他人/伪造序号/未登记文档都不能作为证据")
    void rejectsInvalidEvidence() {
        Fixture f = new Fixture()
                .publish("E-A", CAROL, "来源A", T0 - 3_000L)
                .publish("E-B", CAROL, "来源B", T0 - 3_000L)
                .read(ALICE, "E-A", T0);
        long now = f.now();
        // 已开始但未完成
        InformationModel.Outcome started = InformationModel.startRead(f.info, ACCESS, ALICE,
                "E-B", 1, now);
        assertEquals(InformationModel.Status.STARTED, started.status());
        Fixture unread = f.withInfo(started.state());
        assertEquals(BeliefModel.REASON_EVIDENCE, unread.formRefs(ALICE, "B-1",
                BeliefModel.Comparator.AT_LEAST, 3, now + DAY, CAROL,
                List.of(new RoleView.ReadingRef(ALICE, "E-B", 1, 2))).reason());
        // 取消后的阅读不产生经历，同样不能作证据
        assertEquals(BeliefModel.REASON_EVIDENCE, unread.formRefs(ALICE, "B-1b",
                BeliefModel.Comparator.AT_MOST, 3, now + DAY, CAROL,
                List.of(new RoleView.ReadingRef(ALICE, "E-B", 1, 1))).reason());
        // 伪造序号
        assertEquals(BeliefModel.REASON_EVIDENCE, f.formRefs(ALICE, "B-2",
                BeliefModel.Comparator.AT_LEAST, 3, now + DAY, CAROL,
                List.of(new RoleView.ReadingRef(ALICE, "E-A", 1, 9))).reason());
        // 他人证据
        assertEquals(BeliefModel.REASON_EVIDENCE, f.formRefs(ALICE, "B-3",
                BeliefModel.Comparator.AT_LEAST, 3, now + DAY, CAROL,
                List.of(new RoleView.ReadingRef(BOB, "E-A", 1, 1))).reason());
        // 未读过
        assertEquals(BeliefModel.REASON_EVIDENCE, f.formRefs(ALICE, "B-4",
                BeliefModel.Comparator.AT_LEAST, 3, now + DAY, CAROL,
                List.of(new RoleView.ReadingRef(ALICE, "E-B", 1, 2))).reason());
        // 空证据
        assertEquals(BeliefModel.REASON_EVIDENCE, f.form(ALICE, "B-5",
                BeliefModel.Comparator.AT_LEAST, 3, now + DAY, CAROL, List.of()).reason());
    }

    @Test
    @DisplayName("遗忘后不能凭旧 Observation 重新形成；取消阅读仍占用本人序号")
    void forgetsAndKeepsCancelledOrdinals() {
        Fixture f = new Fixture().publish("E-A", CAROL, "来源A", T0 - 3_000L);
        f.info = InformationModel.startRead(f.info, ACCESS, ALICE, "E-A", 1, T0).state();
        String readId = f.info.active().get(0).readId();
        f.info = InformationModel.cancelRead(f.info, ALICE, readId, T0 + MIN).state();
        assertEquals(InformationModel.CloseReason.CANCELLED, f.info.closed().get(0).reason());
        f.read(ALICE, "E-A", T0 + 2 * MIN);
        RoleView.ReadingRef second = ref(f.info, ALICE, "E-A");
        assertEquals(2, second.index(), "取消的阅读占用序号 1");
        long now = f.now();
        assertTrue(f.formRefs(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST, 3, now + DAY, CAROL,
                List.of(new RoleView.ReadingRef(ALICE, "E-A", 1, 2))).accepted());

        CognitionRecall.Policy shortLife = new CognitionRecall.Policy(MIN, 500, 100, 1_000_000L);
        long later = now + 3 * MIN;
        assertEquals(0, CognitionRecall.recall(f.info, ALICE, later, shortLife).selected().size(),
                "前置：已经遗忘");
        assertEquals(BeliefModel.REASON_NOT_RECALLABLE, f.formRefs(ALICE, "B-2",
                BeliefModel.Comparator.AT_LEAST, 3, later + DAY, CAROL,
                List.of(new RoleView.ReadingRef(ALICE, "E-A", 1, 2)), shortLife, later).reason());
    }

    @Test
    @DisplayName("同 beliefId 同内容幂等；不同内容拒绝；同目标/比较/阈值重复自拟判断拒绝")
    void idempotencyAndDuplicatePrediction() {
        Fixture f = new Fixture().publish("E-A", CAROL, "来源A", T0 - 3_000L).read(ALICE, "E-A", T0);
        long now = f.now();
        long target = now + DAY;
        BeliefModel.Outcome first = f.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST, 3, target,
                CAROL, List.of("E-A"));
        assertTrue(first.accepted());
        BeliefModel.Outcome again = f.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST, 3, target,
                CAROL, List.of("E-A"));
        assertFalse(again.accepted());
        assertEquals("DUPLICATE", again.reason());
        assertEquals(first.state().beliefs(), again.state().beliefs());

        BeliefModel.Outcome conflict = f.form(ALICE, "B-1", BeliefModel.Comparator.AT_MOST, 3, target,
                CAROL, List.of("E-A"));
        assertEquals(BeliefModel.REASON_ID_CONFLICT, conflict.reason());

        assertEquals(BeliefModel.REASON_DUPLICATE_PREDICTION, f.form(ALICE, "B-2",
                BeliefModel.Comparator.AT_LEAST, 3, target, CAROL, List.of("E-A")).reason());
        assertTrue(f.form(BOB, "B-3", BeliefModel.Comparator.AT_LEAST, 3, target, CAROL,
                List.of()).accepted() == false, "他人不能借同阈值形成（bob 未读任何材料）");
    }

    @Test
    @DisplayName("明确采纳：字段全匹配、作者确为发布者、本人已读且可回忆，才形成并冻结来源")
    void adoptionRequiresExactMatch() {
        Fixture f = new Fixture()
                .publish("S-1", BOB, "行业快讯B", T0 - 3_000L)
                .read(ALICE, "S-1", T0)
                .sourceClaim("SC-1", "S-1", BOB, BeliefModel.Comparator.AT_LEAST, 3, T0 + 5 * DAY);
        long now = f.now();
        BeliefModel.Outcome outcome = f.formAdopted(ALICE, "B-1", "SC-1", CAROL, List.of("S-1"),
                T0 + 5 * DAY);
        assertTrue(outcome.accepted(), outcome.reason());
        BeliefModel.Belief belief = belief(outcome.state(), "B-1");
        assertEquals("SC-1", belief.adoptedClaimId());
        assertEquals(BOB, belief.sourcePartyId());
        assertEquals(500, belief.confidencePermille(), "无历史时 competence=500");
    }

    @Test
    @DisplayName("采纳拒绝：字段不一致、作者与发布者不符、claim 所在文档未读、重复采纳同一 claim")
    void adoptionRejections() {
        Fixture f = new Fixture()
                .publish("S-1", BOB, "行业快讯B", T0 - 3_000L)
                .publish("S-2", DAVE, "另一来源", T0 - 3_000L)
                .read(ALICE, "S-1", T0)
                .read(ALICE, "S-2", T0 + 2 * MIN)
                .sourceClaim("SC-1", "S-1", BOB, BeliefModel.Comparator.AT_LEAST, 3, T0 + 5 * DAY);
        long target = T0 + 5 * DAY;
        // 阈值不同
        assertEquals(BeliefModel.REASON_ADOPTION,
                f.formAdopted(ALICE, "B-1", "SC-1", CAROL, List.of("S-1"), target, 4).reason());
        // 比较方向不同
        assertEquals(BeliefModel.REASON_ADOPTION,
                f.formAdopted(ALICE, "B-2", "SC-1", CAROL, List.of("S-1"), target,
                        BeliefModel.Comparator.AT_MOST, 3).reason());
        // 目标时点不同
        assertEquals(BeliefModel.REASON_ADOPTION,
                f.formAdopted(ALICE, "B-3", "SC-1", CAROL, List.of("S-1"), T0 + 6 * DAY).reason());
        // 作者与发布者不符：SC-X 声称 BOB 写，但 S-2 由 DAVE 发布
        Fixture wrong = f.copy().sourceClaim("SC-X", "S-2", BOB, BeliefModel.Comparator.AT_LEAST,
                3, target);
        assertEquals(BeliefModel.REASON_ADOPTION,
                wrong.formAdopted(ALICE, "B-4", "SC-X", CAROL, List.of("S-2"), target).reason());
        // claim 所在文档不在证据里（只读了 S-2）
        assertEquals(BeliefModel.REASON_ADOPTION,
                f.formAdopted(ALICE, "B-5", "SC-1", CAROL, List.of("S-2"), target).reason());
        // 正常形成一次
        assertTrue(f.formAdopted(ALICE, "B-6", "SC-1", CAROL, List.of("S-1"), target).accepted());
        // 重复采纳同一 claim
        assertEquals(BeliefModel.REASON_ADOPTION,
                f.formAdopted(ALICE, "B-7", "SC-1", CAROL, List.of("S-1"), target).reason());
    }

    // ---------------------------------------------------------------- 评估

    @Test
    @DisplayName("只有精确 targetAsOf 的事实才能评估；±1 毫秒都不更新")
    void evaluationRequiresExactAsOf() {
        Fixture f = basePredicting(3, T0 + 5 * DAY);
        long target = T0 + 5 * DAY;
        f.publishFact("F-EARLY", CAROL, target - 1, 5, null);
        f.read(ALICE, "F-EARLY", T0 + 6 * DAY);
        assertEquals(0, f.evaluate(ALICE, "F-EARLY").appended(), "早 1 毫秒不算");
        f.publishFact("F-LATE", CAROL, target + 1, 5, null);
        f.read(ALICE, "F-LATE", T0 + 7 * DAY);
        assertEquals(0, f.evaluate(ALICE, "F-LATE").appended(), "晚 1 毫秒不算");
        f.publishFact("F-OK", CAROL, target, 5, null);
        f.read(ALICE, "F-OK", T0 + 8 * DAY);
        assertEquals(1, f.evaluate(ALICE, "F-OK").appended());
        assertEquals(BeliefModel.Verdict.HIT, BeliefModel.verdict(f.state, "B-1"));
    }

    @Test
    @DisplayName("未读、读到别的文档、只读目录都不评估；无关阅读后状态实例不变")
    void evaluationRequiresActualReadOfThatVersion() {
        Fixture f = basePredicting(3, T0 + 5 * DAY);
        f.publishFact("F-1", CAROL, T0 + 5 * DAY, 1, null);
        f.publish("E-X", CAROL, "无关材料", T0 - 3_000L);
        f.read(ALICE, "E-X", T0 + 6 * DAY);
        BeliefModel.EvaluationOutcome unrelated = f.evaluate(ALICE, "E-X");
        assertEquals(0, unrelated.appended());
        assertTrue(unrelated.state() == f.state, "无关阅读必须返回同一实例，零变化");
        assertEquals(0, f.evaluate(ALICE, "F-1").appended(), "没读核验材料不评估");
        assertNull(BeliefModel.verdict(f.state, "B-1"), "仍为 OPEN");
    }

    @Test
    @DisplayName("验证发布者不符、自证（核验文档就是形成证据）都不评估")
    void evaluationRejectsWrongVerifierAndSelfVerification() {
        Fixture f = basePredicting(3, T0 + 5 * DAY);
        f.publishFact("F-DAVE", DAVE, T0 + 5 * DAY, 5, null);
        f.read(ALICE, "F-DAVE", T0 + 6 * DAY);
        assertEquals(0, f.evaluate(ALICE, "F-DAVE").appended(), "验证发布者必须等于形成时冻结值");

        Fixture self = new Fixture().publish("E-A", CAROL, "公司公告A", T0 - 3_000L).read(ALICE, "E-A", T0);
        long now = self.now();
        self.factClaim("F-SELF", "E-A", CAROL, now + DAY, 5, null);
        assertTrue(self.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST, 3, now + DAY, CAROL,
                List.of("E-A")).accepted());
        self.info = self.info; // 同一版本既是证据又是核验
        assertEquals(0, self.evaluate(ALICE, "E-A").appended(), "不得用形成证据自证");
    }

    @Test
    @DisplayName("无匹配声明保持 OPEN，不写 UNVERIFIABLE、不把超期当错")
    void staysOpenWithoutMatch() {
        Fixture f = basePredicting(3, T0 + 5 * DAY);
        f.publish("E-PLAIN", CAROL, "普通公告", T0 - 3_000L);
        f.read(ALICE, "E-PLAIN", T0 + 40 * DAY);
        BeliefModel.EvaluationOutcome out = f.evaluate(ALICE, "E-PLAIN");
        assertEquals(0, out.appended());
        assertNull(BeliefModel.verdict(out.state(), "B-1"));
        assertEquals(0, out.state().evaluations().size(), "没有 UNVERIFIABLE 记录");
    }

    @Test
    @DisplayName("首次匹配记 HIT/MISS；重读同一 fact 不重复；后来相反普通声明不改判")
    void firstMatchIsStable() {
        Fixture f = basePredicting(3, T0 + 5 * DAY);
        f.publishFact("F-MISS", CAROL, T0 + 5 * DAY, 1, null);
        f.read(ALICE, "F-MISS", T0 + 6 * DAY);
        assertEquals(1, f.evaluate(ALICE, "F-MISS").appended());
        assertEquals(BeliefModel.Verdict.MISS, BeliefModel.verdict(f.state, "B-1"));
        assertEquals(0, f.evaluate(ALICE, "F-MISS").appended(), "同一次重试不重复");

        f.read(ALICE, "F-MISS", T0 + 7 * DAY);
        assertEquals(0, f.evaluate(ALICE, "F-MISS").appended(), "重读同一 fact 不重复");

        f.publishFact("F-CONTRARY", CAROL, T0 + 5 * DAY, 9, null);
        f.read(ALICE, "F-CONTRARY", T0 + 8 * DAY);
        assertEquals(0, f.evaluate(ALICE, "F-CONTRARY").appended(), "普通相反声明不得改判");
        assertEquals(BeliefModel.Verdict.MISS, BeliefModel.verdict(f.state, "B-1"));
    }

    @Test
    @DisplayName("更正：必须精确 supersedes 当前有效 factId、同目标同验证人，追加评估并保留旧评估")
    void correctionAppendsSupersedingEvaluation() {
        Fixture f = basePredicting(3, T0 + 5 * DAY);
        f.publishFact("F-1", CAROL, T0 + 5 * DAY, 1, null);
        f.read(ALICE, "F-1", T0 + 6 * DAY);
        f.evaluate(ALICE, "F-1");
        String oldFact = "F-1";

        f.publishFact("F-2", CAROL, T0 + 5 * DAY, 5, oldFact);
        // 未读之前不变
        assertEquals(0, f.evaluate(ALICE, "F-2").appended(), "未读到更正不能更新");
        f.read(ALICE, "F-2", T0 + 9 * DAY);
        BeliefModel.EvaluationOutcome out = f.evaluate(ALICE, "F-2");
        assertEquals(1, out.superseded());
        assertEquals(2, out.state().evaluations().size(), "旧评估永久保留");
        assertEquals(BeliefModel.Verdict.HIT, BeliefModel.verdict(out.state(), "B-1"));
        String effective = BeliefModel.effectiveEvaluation(out.state(), "B-1").evaluationId();
        String first = out.state().evaluations().get(0).evaluationId();
        assertNotEquals(first, effective);

        // 跳链：F-3 supersedes F-1（已失效），必须拒绝
        f.publishFact("F-3", CAROL, T0 + 5 * DAY, 0, oldFact);
        f.read(ALICE, "F-3", T0 + 10 * DAY);
        assertEquals(0, f.evaluate(ALICE, "F-3").appended(), "不能跳过中间更正覆写已评结果");
        assertEquals(BeliefModel.Verdict.HIT, BeliefModel.verdict(f.state, "B-1"));

        // 更正验证人不同也拒绝
        Fixture other = basePredicting(3, T0 + 5 * DAY);
        other.publishFact("F-1", CAROL, T0 + 5 * DAY, 1, null);
        other.read(ALICE, "F-1", T0 + 6 * DAY);
        other.evaluate(ALICE, "F-1");
        other.publishFact("F-2B", DAVE, T0 + 5 * DAY, 5, "F-1");
        other.read(ALICE, "F-2B", T0 + 9 * DAY);
        assertEquals(0, other.evaluate(ALICE, "F-2B").appended(), "更正验证人必须一致");
    }

    // ---------------------------------------------------------------- 信任与习惯

    @Test
    @DisplayName("来源可靠性只随明确采纳的预测更新；自拟判断失败不归咎任何来源；重复阅读不再加分")
    void reliabilityOnlyForAdopted() {
        Fixture f = basePredicting(3, T0 + 5 * DAY);
        f.publishFact("F-1", CAROL, T0 + 5 * DAY, 1, null);
        f.read(ALICE, "F-1", T0 + 6 * DAY);
        f.evaluate(ALICE, "F-1");
        assertEquals(BeliefModel.NEUTRAL_PERMILLE, BeliefModel.competencePermille(f.state, ALICE, BOB, POLICY),
                "自拟判断失败不改任何来源");
        assertTrue(f.state.evaluations().get(0).sourcePartyId() == null);

        Fixture adopted = adoptedPredicting(3, T0 + 5 * DAY);
        adopted.publishFact("F-1", CAROL, T0 + 5 * DAY, 5, null);
        adopted.read(ALICE, "F-1", T0 + 6 * DAY);
        adopted.evaluate(ALICE, "F-1");
        assertEquals(520, BeliefModel.competencePermille(adopted.state, ALICE, BOB, POLICY), "HIT +1 步");
        adopted.read(ALICE, "F-1", T0 + 7 * DAY);
        adopted.evaluate(ALICE, "F-1");
        assertEquals(520, BeliefModel.competencePermille(adopted.state, ALICE, BOB, POLICY), "重复阅读不再加");
    }

    @Test
    @DisplayName("上下夹紧后更正按历史重算，不在夹紧值上做逆运算")
    void competenceRecomputedAfterCorrection() {
        BeliefModel.Policy clampPolicy = new BeliefModel.Policy(200, 8, HORIZON, 200, 200, 3, 30 * DAY);
        Fixture f = new Fixture(clampPolicy);
        long now = T0;
        for (int i = 1; i <= 4; i++) {
            f.publish("S-" + i, BOB, "行业快讯B", T0 - 3_000L).read(ALICE, "S-" + i, now + i * MIN);
            f.sourceClaim("SC-" + i, "S-" + i, BOB, BeliefModel.Comparator.AT_LEAST, i, now + i * DAY);
            assertTrue(f.formAdopted(ALICE, "B-" + i, "SC-" + i, CAROL,
                    List.of("S-" + i), now + i * DAY, i).accepted(), "形成 B-" + i);
        }
        for (int i = 1; i <= 4; i++) {
            f.publishFact("F-" + i, CAROL, now + i * DAY, 0, null);
            f.read(ALICE, "F-" + i, now + 10 * DAY + i);
            assertEquals(1, f.evaluate(ALICE, "F-" + i).appended());
        }
        assertEquals(0, BeliefModel.competencePermille(f.state, ALICE, BOB, clampPolicy),
                "四次 MISS 后下夹紧到 0");

        f.publishFact("F-C", CAROL, now + 4 * DAY, 5, "F-4");
        f.read(ALICE, "F-C", now + 20 * DAY);
        assertEquals(1, f.evaluate(ALICE, "F-C").superseded());
        assertEquals(100, BeliefModel.competencePermille(f.state, ALICE, BOB, clampPolicy),
                "重算：500 + 200*(+1-3) = 100；在夹紧值上做逆运算会得到 200");
    }

    @Test
    @DisplayName("习惯：K-1/K 边界、窗口到期降级、更正撤销支持、HIT 不支持、HabitRevision 记录支持集合")
    void habitsWindowThresholdAndRevision() {
        BeliefModel.Policy p = new BeliefModel.Policy(200, 8, HORIZON, 20, 200, 2, 10 * DAY);
        Fixture f = new Fixture(p);
        f.publish("E-A", CAROL, "唯一来源", T0 - 3_000L).read(ALICE, "E-A", T0);
        long now = T0;
        for (int i = 1; i <= 2; i++) {
            assertTrue(f.form(ALICE, "B-" + i, BeliefModel.Comparator.AT_LEAST, 10 + i,
                    now + i * DAY, CAROL, List.of("E-A")).accepted(), "形成 B-" + i);
        }
        for (int i = 1; i <= 2; i++) {
            f.publishFact("F-" + i, CAROL, now + i * DAY, 0, null);
            f.read(ALICE, "F-" + i, now + 2 * DAY + i);
            f.evaluate(ALICE, "F-" + i);
        }
        long t2 = now + 2 * DAY + 2 + DUR;
        assertEquals(1, BeliefModel.habitLevel(f.state, ALICE, BeliefModel.HabitKind.VERIFY_BEFORE_ACT, p,
                t2), "K=2，两条 MISS -> 1 级");
        assertEquals(1, BeliefModel.habitLevel(f.state, ALICE, BeliefModel.HabitKind.SEEK_SECOND_SOURCE, p,
                t2), "单公开来源组支持第二条习惯");
        assertFalse(f.state.habitRevisions().isEmpty(), "支持集合变化追加修订");
        BeliefModel.HabitRevision lastRevision = f.state.habitRevisions()
                .get(f.state.habitRevisions().size() - 1);
        assertEquals(1, lastRevision.toLevel());
        assertEquals(List.of("B-1", "B-2"), lastRevision.supportBeliefIds());
        int revisionsAfterBoth = f.state.habitRevisions().size();

        long later = t2 + 20 * DAY;
        assertEquals(1, BeliefModel.habitLevel(f.state, ALICE, BeliefModel.HabitKind.VERIFY_BEFORE_ACT, p,
                later), "查询更晚时点不得自行降级，等级来自已提交版本");
        assertEquals(revisionsAfterBoth, f.state.habitRevisions().size(), "查询不得追加版本");

        // 新的真实核验批次：窗口外的支持到期，重算并追加版本
        assertTrue(f.form(ALICE, "B-3", BeliefModel.Comparator.AT_LEAST, 30, now + 15 * DAY,
                CAROL, List.of("E-A")).accepted());
        f.publishFact("F-3", CAROL, now + 15 * DAY, 0, null);
        f.read(ALICE, "F-3", now + 15 * DAY + 1);
        f.evaluate(ALICE, "F-3");
        long t3 = now + 15 * DAY + 1 + DUR;
        assertEquals(0, BeliefModel.habitLevel(f.state, ALICE, BeliefModel.HabitKind.VERIFY_BEFORE_ACT, p,
                t3), "旧支持超出窗口后由新批次降级");
        assertTrue(f.state.habitRevisions().size() > revisionsAfterBoth);
        BeliefModel.HabitRevision expired = f.state.habitRevisions()
                .get(f.state.habitRevisions().size() - 1);
        assertEquals(List.of("B-3"), expired.supportBeliefIds());
        int revisionsAfterExpiry = f.state.habitRevisions().size();

        f.publishFact("F-C", CAROL, now + 15 * DAY, 99, "F-3");
        f.read(ALICE, "F-C", now + 16 * DAY);
        f.evaluate(ALICE, "F-C");
        long t4 = now + 16 * DAY + DUR;
        assertEquals(0, BeliefModel.habitLevel(f.state, ALICE, BeliefModel.HabitKind.VERIFY_BEFORE_ACT, p,
                t4), "更正为 HIT 后撤销该支持");
        assertTrue(f.state.habitRevisions().size() > revisionsAfterExpiry);
        assertTrue(f.state.habitRevisions().get(f.state.habitRevisions().size() - 1)
                .supportBeliefIds().isEmpty());
    }

    @Test
    @DisplayName("HIT 不提供习惯支持；其他人物不受影响")
    void hitNeverSupports() {
        BeliefModel.Policy p = new BeliefModel.Policy(200, 8, HORIZON, 20, 200, 2, 10 * DAY);
        Fixture f = new Fixture(p);
        f.publish("E-A", CAROL, "来源A", T0 - 3_000L).read(ALICE, "E-A", T0);
        long now = f.now();
        assertTrue(f.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST, 3, now + DAY, CAROL,
                List.of("E-A")).accepted());
        f.publishFact("F-1", CAROL, now + DAY, 9, null);
        f.read(ALICE, "F-1", now + 2 * DAY);
        f.evaluate(ALICE, "F-1");
        assertEquals(0, BeliefModel.habitLevel(f.state, ALICE, BeliefModel.HabitKind.VERIFY_BEFORE_ACT, p,
                now + 2 * DAY + DUR), "HIT 不给支持");
        assertEquals(0, BeliefModel.habitLevel(f.state, BOB, BeliefModel.HabitKind.VERIFY_BEFORE_ACT, p,
                now + 2 * DAY + DUR), "他人没有该经历");
    }

    @Test
    @DisplayName("公开来源组：空白不增加独立组；改隐藏 internalSourceIds 不改变学习")
    void publicGroupsIgnoreHiddenMapping() {
        Fixture f = new Fixture()
                .publish("E-BLANK", CAROL, "", T0 - 3_000L)
                .publish("E-A", CAROL, "来源A", T0 - 3_000L)
                .read(ALICE, "E-BLANK", T0)
                .read(ALICE, "E-A", T0 + MIN);
        long now = f.now();
        BeliefModel.Outcome outcome = f.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST, 3,
                now + DAY, CAROL, List.of("E-BLANK", "E-A"));
        assertTrue(outcome.accepted(), outcome.reason());
        assertEquals(List.of("来源A"), belief(outcome.state(), "B-1").publicSourceGroups(),
                "空白来源不进入公开组");

        // 同一逻辑内容、只改内部审计源：学习结果必须一致
        Fixture hidden = new Fixture()
                .publishWithInternal("E-A2", CAROL, "来源A", "HIDDEN-X", T0 - 3_000L)
                .read(ALICE, "E-A2", T0 + MIN);
        BeliefModel.Outcome other = hidden.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST, 3,
                hidden.now() + DAY, CAROL, List.of("E-A2"));
        assertEquals(belief(outcome.state(), "B-1").publicSourceGroups(),
                belief(other.state(), "B-1").publicSourceGroups());
    }

    @Test
    @DisplayName("容量只拒新增：达到上限后拒绝且不删除任何旧判断/评估")
    void limitsRejectWithoutDeleting() {
        BeliefModel.Policy small = new BeliefModel.Policy(2, 2, HORIZON, 20, 200, 3, 30 * DAY);
        Fixture f = new Fixture(small);
        f.publish("E-A", CAROL, "来源A", T0 - 3_000L).read(ALICE, "E-A", T0);
        long now = f.now();
        assertTrue(f.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST, 1, now + DAY, CAROL,
                List.of("E-A")).accepted());
        assertTrue(f.form(ALICE, "B-2", BeliefModel.Comparator.AT_LEAST, 2, now + DAY, CAROL,
                List.of("E-A")).accepted());
        BeliefModel.Outcome third = f.form(ALICE, "B-3", BeliefModel.Comparator.AT_LEAST, 3,
                now + DAY, CAROL, List.of("E-A"));
        assertFalse(third.accepted());
        assertEquals(BeliefModel.REASON_LIMIT, third.reason());
        assertEquals(2, f.state.beliefs().size(), "旧判断不删");
    }

    @Test
    @DisplayName("确定性：同一输入重复调用结果逐字段相同；输入状态不被修改")
    void deterministicAndImmutable() {
        Fixture f = basePredicting(3, T0 + 5 * DAY);
        BeliefModel.State before = f.state;
        List<BeliefModel.Belief> snapshot = new ArrayList<>(before.beliefs());
        f.publishFact("F-1", CAROL, T0 + 5 * DAY, 5, null);
        f.read(ALICE, "F-1", T0 + 6 * DAY);
        BeliefModel.EvaluationOutcome first = f.evaluate(ALICE, "F-1");
        assertEquals(snapshot, before.beliefs(), "输入状态未被修改");
        BeliefModel.EvaluationOutcome again = BeliefModel.evaluate(
                f.stateBeforeEvaluation, f.info, f.claims, f.policy, ALICE, "F-1", 1,
                1_000_000L, f.lastReadAt);
        assertEquals(first.state().evaluations(), again.state().evaluations());
        assertEquals(first.state().habitRevisions(), again.state().habitRevisions());
        assertThrows(UnsupportedOperationException.class,
                () -> first.state().beliefs().add(first.state().beliefs().get(0)));
    }

    @Test
    @DisplayName("本人投影：OPEN 优先、形成时间降序、条数截断、无 Policy/数值/内部映射/全局序号")
    void projectionIsBoundedAndLeakFree() {
        Fixture f = new Fixture();
        f.publish("E-A", CAROL, "来源A", T0 - 3_000L).read(ALICE, "E-A", T0);
        long now = f.now();
        for (int i = 1; i <= 3; i++) {
            assertTrue(f.form(ALICE, "B-" + i, BeliefModel.Comparator.AT_LEAST, i, now + i * DAY,
                    CAROL, List.of("E-A"), RECALL, now + i).accepted());
        }
        f.publishFact("F-1", CAROL, now + DAY, 0, null);
        f.read(ALICE, "F-1", now + 10 * DAY);
        f.evaluate(ALICE, "F-1");
        long cut = now + 10 * DAY + DUR;

        BeliefModel.Projection projection = BeliefModel.project(f.state, f.policy, ALICE,
                cut, 2);
        assertTrue(projection.truncated());
        assertEquals(2, projection.beliefs().size());
        assertTrue(projection.beliefs().get(0).open(), "OPEN 优先");
        assertEquals("B-3", projection.beliefs().get(0).beliefId(), "同为 OPEN 时形成时间新者在前");
        assertEquals("HIGH".equals(projection.beliefs().get(0).confidenceBand())
                || "MID".equals(projection.beliefs().get(0).confidenceBand())
                || "LOW".equals(projection.beliefs().get(0).confidenceBand()), true);
        assertEquals(2, projection.habits().size());
        assertTrue(projection.sources().isEmpty(), "自拟判断不产生来源 band");

        String rendered = projection.toString();
        assertFalse(rendered.contains("Policy"), "投影不得含 Policy");
        assertFalse(rendered.contains("confidencePermille"), "投影不得含数值置信");
        assertFalse(rendered.contains("READ-"), "投影不得含全局阅读序号");
        assertFalse(rendered.contains("internal"), "投影不得含内部映射");
        assertThrows(IllegalArgumentException.class,
                () -> BeliefModel.project(f.state, f.policy, ALICE, cut, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BeliefModel.project(f.state, f.policy, ALICE, cut, 101));
    }

    @Test
    @DisplayName("非法 Policy 全部拒绝")
    void invalidPolicyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BeliefModel.Policy(0, 1, DAY, 20, 200, 3, DAY));
        assertThrows(IllegalArgumentException.class,
                () -> new BeliefModel.Policy(2, 3, DAY, 20, 200, 3, DAY));
        assertThrows(IllegalArgumentException.class,
                () -> new BeliefModel.Policy(10, 2, 0, 20, 200, 3, DAY));
        assertThrows(IllegalArgumentException.class,
                () -> new BeliefModel.Policy(10, 2, DAY, 0, 200, 3, DAY));
        assertThrows(IllegalArgumentException.class,
                () -> new BeliefModel.Policy(10, 2, DAY, 20, 501, 3, DAY));
        assertThrows(IllegalArgumentException.class,
                () -> new BeliefModel.Policy(10, 2, DAY, 20, 200, 1, DAY));
        assertThrows(IllegalArgumentException.class,
                () -> new BeliefModel.Policy(10, 2, DAY, 20, 200, 3, 0));
    }

    // ---------------------------------------------------------------- 夹具实现

    private static BeliefModel.Belief belief(BeliefModel.State state, String id) {
        return state.beliefs().stream().filter(b -> b.beliefId().equals(id)).findFirst().orElseThrow();
    }

    private static RoleView.ReadingRef ref(InformationModel.State info, String person, String docId) {
        ReadObservation observation = null;
        for (ReadObservation item : info.observations()) {
            if (item.personPartyId().equals(person) && item.docId().equals(docId)) {
                observation = item;
            }
        }
        assertNotNull(observation, "缺少真实经历：" + docId);
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
        ids.sort(Comparator.comparingLong(BeliefModelTest::sequence)
                .thenComparing(Comparator.naturalOrder()));
        int index = ids.indexOf(observation.readId()) + 1;
        assertTrue(index > 0, "经历必须来自本人的阅读记录");
        return new RoleView.ReadingRef(person, docId, 1, index);
    }

    private static long sequence(String readId) {
        if (!readId.startsWith("READ-")) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(readId.substring("READ-".length()));
        } catch (NumberFormatException notNumeric) {
            return Long.MAX_VALUE;
        }
    }

    private static OpeningRegistry registry() {
        List<Party> parties = List.of(
                new Party(COMPANY, PartyKind.ORGANIZATION, "中央财经制造"),
                new Party(ALICE, PartyKind.PERSON, "甲"),
                new Party(BOB, PartyKind.PERSON, "乙"),
                new Party(CAROL, PartyKind.PERSON, "丙"),
                new Party(DAVE, PartyKind.PERSON, "丁"));
        List<PropertyOwner> owners = List.of(
                new PropertyOwner(ALICE, OpeningRegistry.OwnerKind.PERSON, ALICE),
                new PropertyOwner(BOB, OpeningRegistry.OwnerKind.PERSON, BOB),
                new PropertyOwner(CAROL, OpeningRegistry.OwnerKind.PERSON, CAROL),
                new PropertyOwner(DAVE, OpeningRegistry.OwnerKind.PERSON, DAVE),
                new PropertyOwner("co", OpeningRegistry.OwnerKind.ORGANIZATION, COMPANY));
        return new OpeningRegistry(parties, owners, List.of(), List.of());
    }

    private static final class Fixture {
        final BeliefModel.Policy policy;
        InformationModel.State info;
        BeliefModel.Claims claims = new BeliefModel.Claims(List.of(), List.of());
        BeliefModel.State state;
        BeliefModel.State stateBeforeEvaluation;
        long lastReadAt = -1;

        Fixture() {
            this(POLICY);
        }

        Fixture(BeliefModel.Policy policy) {
            this.policy = policy;
            this.info = InformationModel.open(REGISTRY, List.of(ALICE, BOB, CAROL, DAVE));
            this.state = new BeliefModel.State(List.of(), List.of(), List.of(), 1);
        }

        Fixture copy() {
            Fixture copy = new Fixture(policy);
            copy.info = info;
            copy.claims = claims;
            copy.state = state;
            return copy;
        }

        Fixture withInfo(InformationModel.State next) {
            this.info = next;
            return this;
        }

        long now() {
            return latest(info) + DUR;
        }

        Fixture publish(String docId, String publisher, String source, long publishedAt) {
            return publishWithInternal(docId, publisher, source, "AUDIT-" + docId, publishedAt);
        }

        Fixture publishWithInternal(String docId, String publisher, String source, String internal,
                                    long publishedAt) {
            info = InformationModel.publish(info, docId, publisher, Audience.publicAudience(),
                    new VersionDraft("标题-" + docId, "正文-" + docId, source, publishedAt, DUR,
                            List.of(internal)));
            return this;
        }

        Fixture factClaim(String factId, String docId, String verifier, long asOf, long value,
                          String supersedes) {
            List<BeliefModel.FactClaim> facts = new ArrayList<>(claims.factClaims());
            facts.add(new BeliefModel.FactClaim(factId, docId, 1, COMPANY, METRIC, asOf, value,
                    verifier, 1, supersedes));
            claims = new BeliefModel.Claims(claims.sourceClaims(), facts);
            return this;
        }

        Fixture sourceClaim(String claimId, String docId, String author,
                            BeliefModel.Comparator comparator, long threshold, long target) {
            List<BeliefModel.SourceClaim> sources = new ArrayList<>(claims.sourceClaims());
            sources.add(new BeliefModel.SourceClaim(claimId, docId, 1, author, COMPANY, METRIC,
                    comparator, threshold, target, CAROL));
            claims = new BeliefModel.Claims(sources, claims.factClaims());
            return this;
        }

        Fixture publishFact(String factId, String verifier, long asOf, long value, String supersedes) {
            String docId = factId;
            info = InformationModel.publish(info, docId, verifier, Audience.publicAudience(),
                    new VersionDraft("事实-" + factId, "公告正文-" + factId, "公司公告", asOf, DUR,
                            List.of("AUDIT-" + factId)));
            factClaim(factId, docId, verifier, asOf, value, supersedes);
            return this;
        }

        Fixture read(String person, String docId, long startAt) {
            InformationModel.Outcome started = InformationModel.startRead(info, ACCESS, person,
                    docId, 1, startAt);
            assertEquals(InformationModel.Status.STARTED, started.status(),
                    "前置：阅读必须开始 doc=" + docId + " at=" + startAt);
            String readId = null;
            for (InformationModel.Reading reading : started.state().active()) {
                if (reading.personPartyId().equals(person)) {
                    readId = reading.readId();
                }
            }
            InformationModel.Outcome completed = InformationModel.completeRead(started.state(),
                    ACCESS, person, readId, startAt + DUR);
            assertEquals(InformationModel.Status.COMPLETED, completed.status(), "前置：阅读必须完成");
            info = completed.state();
            lastReadAt = startAt + DUR;
            return this;
        }

        BeliefModel.Outcome form(String person, String beliefId, BeliefModel.Comparator comparator,
                                 long threshold, long target, String verifier, List<String> docs) {
            return form(person, beliefId, comparator, threshold, target, verifier, docs, RECALL,
                    now());
        }

        BeliefModel.Outcome form(String person, String beliefId, BeliefModel.Comparator comparator,
                                 long threshold, long target, String verifier, List<String> docs,
                                 CognitionRecall.Policy recall, long at) {
            List<RoleView.ReadingRef> refs = new ArrayList<>();
            for (String doc : docs) {
                refs.add(ref(info, person, doc));
            }
            stateBeforeEvaluation = state;
            BeliefModel.Outcome outcome = BeliefModel.form(state, info, claims, policy, recall, at,
                    new BeliefModel.FormRequest(beliefId, person, COMPANY, comparator, threshold,
                            target, verifier, refs, null, "我的判断"));
            state = outcome.state();
            return outcome;
        }

        BeliefModel.Outcome formRefs(String person, String beliefId,
                                     BeliefModel.Comparator comparator, long threshold, long target,
                                     String verifier, List<RoleView.ReadingRef> refs) {
            return formRefs(person, beliefId, comparator, threshold, target, verifier, refs,
                    RECALL, now());
        }

        BeliefModel.Outcome formRefs(String person, String beliefId,
                                     BeliefModel.Comparator comparator, long threshold, long target,
                                     String verifier, List<RoleView.ReadingRef> refs,
                                     CognitionRecall.Policy recall, long at) {
            BeliefModel.Outcome outcome = BeliefModel.form(state, info, claims, policy, recall,
                    at, new BeliefModel.FormRequest(beliefId, person, COMPANY, comparator,
                            threshold, target, verifier, refs, null, "我的判断"));
            state = outcome.state();
            return outcome;
        }

        BeliefModel.Outcome formAdopted(String person, String beliefId, String claimId,
                                        String verifier, List<String> docs, long target) {
            return formAdopted(person, beliefId, claimId, verifier, docs, target,
                    BeliefModel.Comparator.AT_LEAST, 3);
        }

        BeliefModel.Outcome formAdopted(String person, String beliefId, String claimId,
                                        String verifier, List<String> docs, long target,
                                        long threshold) {
            return formAdopted(person, beliefId, claimId, verifier, docs, target,
                    BeliefModel.Comparator.AT_LEAST, threshold);
        }

        BeliefModel.Outcome formAdopted(String person, String beliefId, String claimId,
                                        String verifier, List<String> docs, long target,
                                        BeliefModel.Comparator comparator, long threshold) {
            List<RoleView.ReadingRef> refs = new ArrayList<>();
            for (String doc : docs) {
                refs.add(ref(info, person, doc));
            }
            BeliefModel.Outcome outcome = BeliefModel.form(state, info, claims, policy, RECALL,
                    now(), new BeliefModel.FormRequest(beliefId, person, COMPANY, comparator,
                            threshold, target, verifier, refs, claimId, "我采纳这个判断"));
            state = outcome.state();
            return outcome;
        }

        BeliefModel.EvaluationOutcome evaluate(String person, String docId) {
            stateBeforeEvaluation = state;
            BeliefModel.EvaluationOutcome outcome = BeliefModel.evaluate(state, info, claims, policy,
                    person, docId, 1, 1_000_000L, lastReadAt);
            state = outcome.state();
            return outcome;
        }
    }

    /** 一个已经形成 B-1 的夹具：读 E-A 作为证据。 */
    private static Fixture basePredicting(long threshold, long target) {
        Fixture f = new Fixture().publish("E-A", CAROL, "来源A", T0 - 3_000L).read(ALICE, "E-A", T0);
        assertTrue(f.form(ALICE, "B-1", BeliefModel.Comparator.AT_LEAST, threshold, target, CAROL,
                List.of("E-A")).accepted());
        return f;
    }

    /** 采纳 BOB 的 SC-1 形成 B-1。 */
    private static Fixture adoptedPredicting(long threshold, long target) {
        Fixture f = new Fixture()
                .publish("S-1", BOB, "行业快讯B", T0 - 3_000L)
                .read(ALICE, "S-1", T0)
                .sourceClaim("SC-1", "S-1", BOB, BeliefModel.Comparator.AT_LEAST, threshold, target);
        assertTrue(f.formAdopted(ALICE, "B-1", "SC-1", CAROL, List.of("S-1"), target).accepted());
        return f;
    }

    private static long latest(InformationModel.State info) {
        long latest = 0L;
        for (InformationModel.Reading reading : info.active()) {
            latest = Math.max(latest, reading.startedAt());
        }
        for (InformationModel.ClosedReading reading : info.closed()) {
            latest = Math.max(latest, reading.closedAt());
        }
        return latest;
    }
}
