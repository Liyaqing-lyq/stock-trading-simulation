package edu.cufe.auction.world.pharmaaudit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.PeriodicModel;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedPharmaScenario;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 独审第四组：独立公司期末链条的 SCORE 与拒绝反例。
 *
 * <p>正向：经理编制（首份材料必须用冻结快照的 {@code materialDocId}）→ 董事实读后逐席评分 →
 * 窗口内批准 → 经理授权 → 董事发布，公开文档真实出现。</p>
 *
 * <p><b>评分口径按实际证据</b>：pharma 只有独立公司现金与研发在研资产，没有工程合同/施工/
 * 发票，也没有已发生的披露或长期投资证据，因此每席只提交真实可得的现金维（{@code account:}
 * 前缀）。{@code Ballot.reviewStatus()} 只在五维齐全时才是 {@code COMPLETE}，所以这里断言的是
 * <b>真实发生的</b>结果：三席均 {@code INCOMPLETE}（明确缺维），聚合 {@code seatCount=0}、
 * {@code NO_QUORUM}、各维和为 0、总分为 null —— 不补零、不伪造证据、不为凑五维编造合同。</p>
 *
 * <p>反向：未读评分、其它公司证据、未来证据都在窗口内被拒绝且不增评分席位、关闭前不产生业绩；
 * 晚于会议关闭的批准被拒绝；用一份<b>真实有效</b>的授权去发布别的公司被拒绝且不产生任何公开
 * 文档，同一授权发布本公司则成功（对照）。</p>
 */
class PharmaPeriodicScoreAuditTest {

    private static final String PHARMA = ComposedPharmaScenario.PHARMA;
    private static final List<String> DIRECTORS = List.of("frank", "grace", "heidi");

    @TempDir
    Path dir;

    @Test
    void prepareUsesFrozenMaterialAndOnlyRealReadBallotsAreAccepted() throws Exception {
        Path journal = dir.resolve("period.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            var opened = openReviewWindow(world, List.of("frank", "grace", "heidi", "judy"));
            PeriodicModel.Snapshot snapshot = opened.snapshot();
            String result = PharmaAuditHarness.tool(world, "kate", "p-prepare",
                    "periodic.prepare", Map.of("companyPartyId", PHARMA, "period", 1));
            assertEquals("ACCEPTED", result);
            var periods = world.periodicOf(PHARMA).orElseThrow();
            assertEquals(1, periods.drafts().size());
            PeriodicModel.Draft draft = periods.drafts().get(0);
            assertEquals(snapshot.materialDocId(), draft.docId(),
                    "首份准备材料必须用冻结快照的真实材料编号");

            // 只有 frank/grace/heidi 读草稿；judy 出席但没读。
            readDraft(world, draft, DIRECTORS);

            // 未读的董事评分：拒绝码必须是真实的逐版本未读。
            assertEquals("NOT_READ_EXACT_VERSION", PharmaAuditHarness.tool(world, "judy",
                    "p-judy", "periodic.score", Map.of("companyPartyId", PHARMA, "period", 1,
                            "metrics", cashOnly(80, snapshot.capturedAt()))),
                    "未读草稿不得评分");
            assertEquals(0, world.periodicOf(PHARMA).orElseThrow().ballots().size(),
                    "拒绝不得制造评分席位");

            // 其它公司证据（工程公司账户 / 银行准备金账户）：两路都必须拒绝。
            assertEquals("EVIDENCE_NOT_ELIGIBLE", PharmaAuditHarness.tool(world, "heidi",
                    "p-heidi-enga", "periodic.score", Map.of("companyPartyId", PHARMA,
                            "period", 1, "metrics", metrics("CASH_SOLVENCY", 80,
                                    "account:ENG-A", snapshot.capturedAt()))),
                    "他公司工程账户不得作为期末证据");
            assertEquals("EVIDENCE_NOT_ELIGIBLE", PharmaAuditHarness.tool(world, "heidi",
                    "p-heidi-bank", "periodic.score", Map.of("companyPartyId", PHARMA,
                            "period", 1, "metrics", metrics("CASH_SOLVENCY", 80,
                                    "account:RSV-NORTH", snapshot.capturedAt()))),
                    "他公司银行账户不得作为期末证据");

            // 未来证据：asOf 晚于冻结快照时刻。
            assertEquals("FUTURE_EVIDENCE", PharmaAuditHarness.tool(world, "frank",
                    "p-frank-future", "periodic.score", Map.of("companyPartyId", PHARMA,
                            "period", 1, "metrics", metrics("CASH_SOLVENCY", 80,
                                    "account:RND-PHARMA", snapshot.capturedAt() + 1L))),
                    "未来证据必须被拒绝");
            assertEquals(0, world.periodicOf(PHARMA).orElseThrow().ballots().size(),
                    "三次攻击后仍不得有评分席位");
            assertEquals(0, world.periodicOf(PHARMA).orElseThrow().scores().size(),
                    "窗口内攻击不得制造业绩");

            // 真实评分：三席都读过、来源与截至时刻正确；每席只有真实可得的现金维。
            for (String director : DIRECTORS) {
                assertEquals("ACCEPTED", PharmaAuditHarness.tool(world, director,
                        "p-score-" + director, "periodic.score", Map.of(
                                "companyPartyId", PHARMA, "period", 1,
                                "metrics", cashOnly(90, snapshot.capturedAt()))),
                        director + " 的真实评分必须被接受");
            }
            var scored = world.periodicOf(PHARMA).orElseThrow();
            assertEquals(3, scored.ballots().size(), "三个真实席位各一条评分");
            List<String> seats = List.of("p1", "p2", "p3");
            for (int i = 0; i < scored.ballots().size(); i++) {
                PeriodicModel.Ballot ballot = scored.ballots().get(i);
                assertEquals(DIRECTORS.get(i), ballot.personPartyId());
                assertEquals(seats.get(i), ballot.seatId());
                assertEquals(BoardModel.ReviewStatus.INCOMPLETE, ballot.reviewStatus(),
                        "只提交真实现金维，席位必须明确是缺维而不是假装完整");
                assertEquals(1, ballot.metrics().size(),
                        "不得为凑五维伪造证据：" + ballot.metrics());
                assertEquals(BoardModel.Dimension.CASH_SOLVENCY,
                        ballot.metrics().get(0).dimension());
                assertEquals(0, ballot.metrics().get(0).score()
                        .compareTo(new BigDecimal("90.00")));
                assertEquals("account:RND-PHARMA", ballot.metrics().get(0).sourceRef());
                assertEquals(snapshot.materialDocId(), ballot.materialDocId());
                assertEquals(snapshot.materialVersion(), ballot.materialVersion());
            }

            for (String director : DIRECTORS) {
                assertEquals("ACCEPTED", PharmaAuditHarness.tool(world, director,
                        "p-approve-" + director, "periodic.approve", Map.of(
                                "companyPartyId", PHARMA, "period", 1, "draftId",
                                draft.draftId(), "draftVersion", 1)));
            }
            world.advanceTo(opened.meeting().closesAt() + 10L);

            // 聚合按真实证据：没有五维齐全的完整席，就没有凑数业绩。
            var closed = world.periodicOf(PHARMA).orElseThrow();
            assertEquals(1, closed.scores().size(), "关会时确实写了一条聚合记录");
            PeriodicModel.Score score = closed.scores().get(0);
            assertEquals(0, score.seatCount(),
                    "只统计五维齐全的完整席；缺维席位不得计入");
            assertEquals(BoardModel.ReviewStatus.INCOMPLETE, score.status());
            assertEquals(PeriodicModel.REASON_NO_QUORUM, score.reason());
            assertNull(score.total(), "INCOMPLETE 不得给出总分");
            assertEquals(BoardModel.Dimension.values().length, score.sums().size());
            for (BigDecimal sum : score.sums()) {
                assertEquals(0, sum.compareTo(BigDecimal.ZERO),
                        "缺维不补零：未计入的席位不得贡献任何分数");
            }
            assertEquals("ACCEPTED", PharmaAuditHarness.tool(world, "kate", "p-authorize",
                    "periodic.authorize", Map.of("companyPartyId", PHARMA, "period", 1,
                            "draftId", draft.draftId(), "draftVersion", 1,
                            "publisherPersonId", "frank",
                            "expiresAt", world.snapshot().timeMillis() + 86_400_000L)));
            assertEquals("ACCEPTED", PharmaAuditHarness.tool(world, "frank", "p-publish",
                    "periodic.publish", Map.of("companyPartyId", PHARMA, "period", 1,
                            "draftId", draft.draftId(), "draftVersion", 1)));
            var released = world.periodicOf(PHARMA).orElseThrow();
            assertEquals(1, released.releases().size());
            assertTrue(world.snapshot().information().orElseThrow().information().documents()
                    .stream().anyMatch(doc -> doc.docId()
                            .equals("periodic-public:pharma:1")), "真实发布必须产生公开文档");
            assertTrue(world.periodicOf("north").orElseThrow().releases().isEmpty(),
                    "pharma 的发布不得写到 north");
        }
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            assertEquals(1, world.periodicOf(PHARMA).orElseThrow().releases().size(),
                    "发布必须跨重开保持");
            assertEquals(3, world.periodicOf(PHARMA).orElseThrow().ballots().size());
            assertEquals(0, world.periodicOf(PHARMA).orElseThrow().scores().get(0).seatCount(),
                    "聚合口径必须跨重开保持（缺维不计席）");
        }
    }

    @Test
    void approvalAfterMeetingClosesIsRejected() throws Exception {
        Path journal = dir.resolve("late.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            var opened = openReviewWindow(world, List.of("frank", "grace", "heidi"));
            assertEquals("ACCEPTED", PharmaAuditHarness.tool(world, "kate", "l-prepare",
                    "periodic.prepare", Map.of("companyPartyId", PHARMA, "period", 1)));
            var draft = world.periodicOf(PHARMA).orElseThrow().drafts().get(0);
            readDraft(world, draft, DIRECTORS);
            for (String director : DIRECTORS) {
                assertEquals("ACCEPTED", PharmaAuditHarness.tool(world, director,
                        "l-score-" + director, "periodic.score", Map.of(
                                "companyPartyId", PHARMA, "period", 1,
                                "metrics", cashOnly(90, opened.snapshot().capturedAt()))));
            }
            world.advanceTo(opened.meeting().closesAt() + 1_000L);
            assertEquals("REVIEW_WINDOW_CLOSED", PharmaAuditHarness.tool(world, "frank",
                    "l-approve-late", "periodic.approve", Map.of("companyPartyId", PHARMA,
                            "period", 1, "draftId", draft.draftId(), "draftVersion", 1)),
                    "会议关闭后的批准必须被拒绝");
            assertTrue(world.periodicOf(PHARMA).orElseThrow().approvals().isEmpty(),
                    "拒绝不得留下批准记录");
            assertTrue(world.periodicOf(PHARMA).orElseThrow().releases().isEmpty());
        }
    }

    @Test
    void foreignCompanyPeriodicPublishIsRejectedWhileSameAuthorizationPublishesOwnCompany()
            throws Exception {
        Path journal = dir.resolve("foreign-publish.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            var opened = openReviewWindow(world, List.of("frank", "grace", "heidi"));
            assertEquals("ACCEPTED", PharmaAuditHarness.tool(world, "kate", "f-prepare",
                    "periodic.prepare", Map.of("companyPartyId", PHARMA, "period", 1)));
            var draft = world.periodicOf(PHARMA).orElseThrow().drafts().get(0);
            readDraft(world, draft, DIRECTORS);
            for (String director : DIRECTORS) {
                assertEquals("ACCEPTED", PharmaAuditHarness.tool(world, director,
                        "f-score-" + director, "periodic.score", Map.of(
                                "companyPartyId", PHARMA, "period", 1,
                                "metrics", cashOnly(90, opened.snapshot().capturedAt()))));
                assertEquals("ACCEPTED", PharmaAuditHarness.tool(world, director,
                        "f-approve-" + director, "periodic.approve", Map.of(
                                "companyPartyId", PHARMA, "period", 1,
                                "draftId", draft.draftId(), "draftVersion", 1)));
            }
            world.advanceTo(opened.meeting().closesAt() + 10L);
            assertEquals("ACCEPTED", PharmaAuditHarness.tool(world, "kate", "f-authorize",
                    "periodic.authorize", Map.of("companyPartyId", PHARMA, "period", 1,
                            "draftId", draft.draftId(), "draftVersion", 1,
                            "publisherPersonId", "frank",
                            "expiresAt", world.snapshot().timeMillis() + 86_400_000L)),
                    "先建立一份真实有效的授权，伪发布才有意义");
            assertEquals(1, world.periodicOf(PHARMA).orElseThrow().authorizations().size());

            // pharma 董事 frank 拿着 pharma 的有效授权，试图把同一草稿发布成 north 的期末。
            // 实际形态：动作在受理阶段被拒绝，因此没有完成帧（结果为空），不是事后才失败。
            String forged = PharmaAuditHarness.tool(world, "frank", "f-publish-north",
                    "periodic.publish", Map.of("companyPartyId", "north", "period", 1,
                            "draftId", draft.draftId(), "draftVersion", 1));
            assertEquals("", forged, "跨公司伪发布没有完成帧（受理阶段即拒绝）");
            assertTrue(world.periodicOf("north").orElseThrow().releases().isEmpty(),
                    "north 不得出现发布记录");
            assertFalse(world.snapshot().information().orElseThrow().information().documents()
                    .stream().anyMatch(doc -> doc.docId().startsWith("periodic-public:north")),
                    "被拒绝的伪发布不得产生公开文档");
            assertTrue(world.periodicOf(PHARMA).orElseThrow().releases().isEmpty(),
                    "伪发布也不得顺带发布本公司");

            // 对照：同一份授权、同一草稿，只把公司换回 pharma 就能真实发布。
            assertEquals("ACCEPTED", PharmaAuditHarness.tool(world, "frank", "f-publish-own",
                    "periodic.publish", Map.of("companyPartyId", PHARMA, "period", 1,
                            "draftId", draft.draftId(), "draftVersion", 1)),
                    "对照必须成功，证明拒绝只针对被换掉的发行主体");
            assertEquals(1, world.periodicOf(PHARMA).orElseThrow().releases().size());
            assertTrue(world.snapshot().information().orElseThrow().information().documents()
                    .stream().anyMatch(doc -> doc.docId()
                            .equals("periodic-public:pharma:1")));
            assertEquals(0, world.periodicOf("north").orElseThrow().releases().size(),
                    "对照成功后 north 仍然没有任何发布");
        }
    }

    // ------------------------------------------------------------ 夹具

    private record Opened(PeriodicModel.Snapshot snapshot,
            edu.cufe.auction.world.BoardModel.Meeting meeting) {
    }

    /** 真实预算+任免 → 推进到期中 → 董事到会 → 会议开启。 */
    private Opened openReviewWindow(WorldRuntime world, List<String> attendees) throws Exception {
        PharmaAuditHarness.approveBudgetAndAppoint(world, "kate");
        world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);
        var snapshot = world.periodicOf(PHARMA).orElseThrow().snapshots().get(0);
        var board = world.boardOfCompany(PHARMA).orElseThrow().board();
        var meeting = board.meeting(snapshot.meetingId());
        assertNotNull(meeting, "期末采样必须产生真实考核会议：" + snapshot.meetingId());
        for (int i = 0; i < attendees.size(); i++) {
            world.scheduleEngineeringAttend("pa-att-" + i, "pa-att-t-" + i,
                    snapshot.capturedAt() + 10L + i, PHARMA, attendees.get(i),
                    snapshot.meetingId(), false);
        }
        world.advanceTo(meeting.opensAt() + 1_000L);
        return new Opened(snapshot, meeting);
    }

    /** 真实读完草稿（读完才能评分）。 */
    private void readDraft(WorldRuntime world, PeriodicModel.Draft draft, List<String> readers)
            throws Exception {
        long start = world.snapshot().timeMillis();
        for (int i = 0; i < readers.size(); i++) {
            world.scheduleStartReading("p-read-" + i, "p-read-t-" + i, start + 100L + i,
                    readers.get(i), draft.docId(), draft.version());
        }
        world.advanceTo(start + 2_000L);
    }

    /** 只提交真实可得的现金维：pharma 没有合同/施工/发票，不伪造其它四维。 */
    private static List<Map<String, Object>> cashOnly(int score, long asOf) {
        return metrics("CASH_SOLVENCY", score, "account:RND-PHARMA", asOf);
    }

    private static List<Map<String, Object>> metrics(String dimension, int score,
            String sourceRef, long asOf) {
        return List.of(Map.of("dimension", dimension, "score", score,
                "sourceRef", sourceRef, "asOf", asOf));
    }
}
