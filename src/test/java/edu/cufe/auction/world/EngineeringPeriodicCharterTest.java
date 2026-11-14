package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章程公司（制造）进入同一周期对象：复用原治理董事会与真实制造报表，三期实际路径、
 * 公司间证据隔离、真实准备/实读/批准/授权/发布与跨 JVM 续接。
 */
class EngineeringPeriodicCharterTest {

    private static final long DAY = 86_400_000L;
    private static final String C = WorldWebScenario.COMPANY;
    private static final String A = WorldWebScenario.ENGINE_A;
    private static final String[] SEATS = {"alice", "bob", "carol"};
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-periodic-charter-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static WorldRuntime open(Path file) throws Exception {
        return WorldWebScenario.engineeringRuntimeWithAppointments(file,
                WorldWebScenario.engineeringConfig(),
                List.of(new RoleAccess.Appointment("CUFE-MGR", "erin", C,
                        RoleAccess.Role.COMPANY_MANAGER, 0L, 900L * DAY, null)));
    }

    private static PeriodicModel.Snapshot snapshot(WorldRuntime world, String company, long period) {
        return world.periodicOf(company).orElseThrow().snapshots().stream()
                .filter(row -> row.period() == period).findFirst().orElseThrow();
    }

    private static EngineeringRun.Report report(WorldRuntime world, String prefix) {
        EngineeringRun.Report latest = null;
        for (EngineeringRun.Report entry : world.snapshot().engineering().orElseThrow()
                .reports().values()) {
            if (entry.taskId().startsWith(prefix)
                    && PendingTask.ENGINEERING_PERIODIC_V1.equals(entry.kind())
                    && (latest == null || entry.position() > latest.position())) {
                latest = entry;
            }
        }
        if (latest == null) {
            throw new AssertionError("缺少回报：" + prefix);
        }
        return latest;
    }

    private static String latestDraft(WorldRuntime world, String company, long period) {
        String found = "";
        for (PeriodicModel.Draft draft : world.periodicOf(company).orElseThrow().drafts()) {
            if (draft.period() == period) {
                found = draft.draftId();
            }
        }
        assertFalse(found.isBlank(), "缺少草稿");
        return found;
    }

    private static void act(WorldRuntime world, String command, long due, String company,
            String person, String action, long period, String draftId, String publisher,
            long expiresAt, List<PeriodicActionPayload.PeriodicMetric> metrics, String note)
            throws Exception {
        world.schedulePeriodicAction(command, command + "-task", due, company, person, action,
                period, draftId, 1L, publisher, expiresAt, "", metrics, note, 100L);
    }

    @Test
    void charterCompanyRunsOnItsOwnBoardAndReport() throws Exception {
        Path file = journal("charter");
        WorldRuntime world = open(file);
        assertTrue(world.isActiveManager(C, "erin", 0L));

        // 制造关期与采样同刻分阶段：30d 的 RULES 关期先于 OBSERVATION 采样。
        world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);
        assertEquals(1, world.snapshot().manufacturing().orElseThrow().ledger().closedPeriods());
        PeriodicModel.Snapshot first = snapshot(world, C, 1L);
        BoardModel.Meeting meeting = world.boardOfCompany(C).orElseThrow().board()
                .meeting(first.meetingId());
        assertNotNull(meeting, "章程公司必须在原治理董事会里建周期会议");
        assertEquals(BoardModel.CHARTER_AUTO, meeting.convenerSeatId());
        assertEquals(BoardModel.Topic.OPERATING_REVIEW, meeting.topic());

        for (int i = 0; i < SEATS.length; i++) {
            act(world, "c-att-" + i, first.capturedAt() + 1_000L + i, C, SEATS[i], "ATTEND", 1L,
                    "", "", 0L, List.of(), "");
        }
        long prepareAt = meeting.opensAt() + 1_000L;
        act(world, "c-prep-1", prepareAt, C, "erin", "PREPARE", 1L, "", "", 0L, List.of(), "");
        world.advanceTo(prepareAt + 100L);
        PeriodicModel.Draft draft = world.periodicOf(C).orElseThrow().drafts().stream()
                .filter(row -> row.period() == 1L).findFirst().orElseThrow();
        assertEquals("erin", draft.preparedBy());
        InformationModel.DocumentVersion material = InformationModel.version(
                world.snapshot().information().orElseThrow().information(), draft.docId(), 1L)
                .orElseThrow();
        assertTrue(material.body().contains("公司=" + C), "材料正文必须是真实制造报表");
        assertTrue(material.body().contains("设备净值="), "制造报表必须含真实设备净值");
        assertEquals(first.statementDigest(), PeriodicWorldEngine.sha256(material.body()));

        long readAt = prepareAt + 2_000L;
        for (int i = 0; i < SEATS.length; i++) {
            world.scheduleStartReading("c-rd-" + i, "c-rd-" + i + "-task", readAt + i * 2L,
                    SEATS[i], draft.docId(), 1L);
        }
        long scoreAt = readAt + PeriodicWorldEngine.MATERIAL_READ_MILLIS + 1_000L;
        world.advanceTo(scoreAt);
        // 制造公司没有工程合同/工单：经营与风险维不可评；披露在期 1 也尚无成熟事实。
        act(world, "c-bad-op", scoreAt + 200L, C, "bob", "SCORE", 1L, "", "", 0L,
                List.of(new PeriodicActionPayload.PeriodicMetric("OPERATING_COMMITMENT",
                        new BigDecimal("80"), "contract:A1", first.capturedAt())), "");
        world.advanceTo(scoreAt + 400L);
        assertEquals("EVIDENCE_NOT_ELIGIBLE", report(world, "c-bad-op").reason());
        for (int i = 0; i < SEATS.length; i++) {
            act(world, "c-score-" + i, scoreAt + 600L + i * 10L, C, SEATS[i], "SCORE", 1L,
                    "", "", 0L,
                    List.of(new PeriodicActionPayload.PeriodicMetric("CASH_SOLVENCY",
                            new BigDecimal("85"), "account:CO", first.capturedAt())), "");
        }
        world.advanceTo(scoreAt + 900L);
        assertEquals(3, world.periodicOf(C).orElseThrow().ballots().size());
        world.advanceTo(meeting.closesAt() + 10L);
        PeriodicModel.Score score = world.periodicOf(C).orElseThrow().scores().stream()
                .filter(row -> row.period() == 1L).findFirst().orElseThrow();
        assertEquals(BoardModel.ReviewStatus.INCOMPLETE, score.status());
        assertEquals("NO_QUORUM", score.reason());
        assertEquals(0, score.seatCount());

        long approveBase = meeting.closesAt() + 1_000L;
        for (int i = 0; i < SEATS.length; i++) {
            world.scheduleStartReading("c-ap-rd-" + i, "c-ap-rd-" + i + "-task",
                    approveBase + i * 2L, SEATS[i], draft.docId(), 1L);
        }
        long afterRead = approveBase + PeriodicWorldEngine.MATERIAL_READ_MILLIS;
        world.advanceTo(afterRead);
        for (int i = 0; i < SEATS.length; i++) {
            act(world, "c-ap-" + i, afterRead + 1_000L + i * 10L, C, SEATS[i], "APPROVE", 1L,
                    draft.draftId(), "", 0L, List.of(), "");
        }
        world.advanceTo(afterRead + 2_000L);
        assertEquals(3, world.periodicOf(C).orElseThrow().approvals().size());
        long authorizeAt = afterRead + 3_000L;
        act(world, "c-auth", authorizeAt, C, "erin", "AUTHORIZE", 1L, draft.draftId(), "alice",
                authorizeAt + DAY, List.of(), "");
        long publishAt = authorizeAt + 1_000L;
        act(world, "c-pub", publishAt, C, "alice", "PUBLISH", 1L, draft.draftId(), "", 0L,
                List.of(), "");
        world.advanceTo(publishAt + 10L);
        assertEquals(1, world.periodicOf(C).orElseThrow().releases().size());
        InformationModel.DocumentVersion publicV1 = InformationModel.version(
                world.snapshot().information().orElseThrow().information(),
                PeriodicWorldEngine.publicDocId(C, 1L), 1L).orElseThrow();
        assertEquals(material.body(), publicV1.body());

        // 制造三期实际路径：p2 的披露维可用 p1 的真实发布事实，且必须逐字绑定发布时间。
        long secondCapture = PeriodicModel.PERIOD_MILLIS * 2L;
        world.advanceTo(secondCapture + 5L);
        PeriodicModel.Snapshot second = snapshot(world, C, 2L);
        assertTrue(second.evidenceRefs().contains("disclosure:" + C + ":1:release:1"));
        BoardModel.Meeting secondMeeting = world.boardOfCompany(C).orElseThrow().board()
                .meeting(second.meetingId());
        for (int i = 0; i < SEATS.length; i++) {
            act(world, "c2-att-" + i, second.capturedAt() + 1_000L + i, C, SEATS[i], "ATTEND", 2L,
                    "", "", 0L, List.of(), "");
        }
        long prep2 = secondMeeting.opensAt() + 1_000L;
        act(world, "c-prep-2", prep2, C, "erin", "PREPARE", 2L, "", "", 0L, List.of(), "");
        world.advanceTo(prep2 + 100L);
        PeriodicModel.Draft draft2 = world.periodicOf(C).orElseThrow().drafts().stream()
                .filter(row -> row.period() == 2L).findFirst().orElseThrow();
        world.scheduleStartReading("c2-rd", "c2-rd-task", prep2 + 1_000L, "bob", draft2.docId(),
                1L);
        long score2 = prep2 + 1_000L + PeriodicWorldEngine.MATERIAL_READ_MILLIS + 500L;
        world.advanceTo(score2);
        long releaseOneAt = world.periodicOf(C).orElseThrow().releases().get(0).publishedAt();
        act(world, "c2-score", score2 + 200L, C, "bob", "SCORE", 2L, "", "", 0L,
                List.of(new PeriodicActionPayload.PeriodicMetric("DISCLOSURE",
                        new BigDecimal("70"), "disclosure:" + C + ":1:release:1",
                        releaseOneAt)), "");
        world.advanceTo(score2 + 400L);
        assertEquals(1, world.periodicOf(C).orElseThrow().ballots().stream()
                .filter(row -> row.period() == 2L).count());
        // 公司隔离：工程公司的账户引用不能给制造评分，反之亦然。
        act(world, "c2-cross", score2 + 600L, C, "bob", "SCORE", 2L, "", "", 0L,
                List.of(new PeriodicActionPayload.PeriodicMetric("CASH_SOLVENCY",
                        new BigDecimal("50"), "account:ENG-A", second.capturedAt())), "");
        act(world, "a-cross", score2 + 800L, A, "alice", "SCORE", 1L, "", "", 0L,
                List.of(new PeriodicActionPayload.PeriodicMetric("CASH_SOLVENCY",
                        new BigDecimal("50"), "account:CO", second.capturedAt())), "");
        world.advanceTo(score2 + 1_000L);
        assertEquals("EVIDENCE_NOT_ELIGIBLE", report(world, "c2-cross").reason());
        // eng-a 期 1 的引用集合不含 CO，同样被拒（该席也未实读，但引用先于维度被拒）。
        assertFalse(report(world, "a-cross").accepted());

        world.advanceTo(PeriodicModel.PERIOD_MILLIS * 3L + 5L);
        assertEquals(3, world.periodicOf(C).orElseThrow().snapshots().size());
        assertEquals(3, world.periodicOf(A).orElseThrow().snapshots().size());
        world.close();

        try (WorldRuntime reopened = open(file)) {
            assertEquals(3, reopened.periodicOf(C).orElseThrow().snapshots().size());
            assertEquals(1, reopened.periodicOf(C).orElseThrow().releases().size());
            assertTrue(reopened.snapshot().manufacturing().orElseThrow().ledger()
                    .closedPeriods() >= 3);
        }
    }
}
