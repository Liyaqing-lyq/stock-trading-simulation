package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema18c 周期写入与披露的世界级验收：真实采样、真实董事到会/实读/评分、三名批准、
 * 一次授权、PUBLIC 发布、更正新版本、MISSED 与迟发、跨 JVM 续接、保留命名空间。
 */
class EngineeringPeriodicChainTest {

    private static final long DAY = 86_400_000L;
    private static final String A = WorldWebScenario.ENGINE_A;
    private static final String[] DIRECTORS = {"alice", "bob", "carol", "dave", "erin"};
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-periodic-chain-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static WorldRuntime open(Path file) throws Exception {
        return WorldWebScenario.engineeringRuntime(file, WorldWebScenario.engineeringConfig());
    }

    private static PeriodicModel.Snapshot snapshot(WorldRuntime world, long period) {
        return world.periodicOf(A).orElseThrow().snapshots().stream()
                .filter(row -> row.period() == period).findFirst().orElseThrow();
    }

    private static BoardModel.Meeting meeting(WorldRuntime world, String meetingId) {
        return world.boardOfCompany(A).orElseThrow().board().meeting(meetingId);
    }

    private static EngineeringRun.Report report(WorldRuntime world, String prefix) {
        List<EngineeringRun.Report> found = new ArrayList<>();
        for (EngineeringRun.Report entry : world.snapshot().engineering().orElseThrow()
                .reports().values()) {
            if (entry.taskId().startsWith(prefix)) {
                found.add(entry);
            }
        }
        found.sort((left, right) -> Long.compare(right.position(), left.position()));
        assertFalse(found.isEmpty(), "缺少回报：" + prefix);
        return found.get(0);
    }

    /** 真实开局：材料与阅读、预算与任免、经理 dave 上任、签 A1、开工。 */
    private static void baseChain(WorldRuntime world) throws Exception {
        world.schedulePublishDocument("pub-budget", "pub-budget-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-opp", "pub-opp-task", 10L,
                "engineering-opportunity:A1", WorldWebScenario.INVESTOR,
                InformationModel.Audience.publicAudience(), "A1 机会", "工程机会 A1",
                "冻结机会", 10L, List.of());
        world.scheduleStartReading("read-opp", "read-opp-task", 100L, "dave",
                "engineering-opportunity:A1", 1L);
        for (int i = 0; i < DIRECTORS.length; i++) {
            String person = DIRECTORS[i];
            world.scheduleStartReading("read-mat-" + person, "read-mat-" + person + "-task",
                    20L + i * 2L, person, "b-material", 1L);
            world.scheduleStartReading("read-appt-" + person, "read-appt-" + person + "-task",
                    60L + i * 2L, person, "appt-material", 1L);
        }
        world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L, A, "alice",
                "budget-meeting", 150L, 200L, 300L);
        for (int i = 0; i < DIRECTORS.length; i++) {
            world.scheduleEngineeringAttend("budget-a-" + i, "budget-a-" + i + "-task",
                    110L + i, A, DIRECTORS[i], "budget-meeting", false);
        }
        world.scheduleEngineeringMaterial("budget-m", "budget-m-task", 130L, A, "alice",
                "budget-meeting", "m1", "b-material", 1L);
        world.scheduleEngineeringBudgetProposal("budget-p", "budget-p-task", 140L, A, "alice",
                "budget-meeting", List.of(
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                new BigDecimal("10000.00")),
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                new BigDecimal("10000.00"))));
        for (int i = 0; i < 4; i++) {
            world.scheduleEngineeringVote("budget-v-" + i, "budget-v-" + i + "-task",
                    220L + i, A, DIRECTORS[i], "budget-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("budget-i", "budget-i-task", 320L, A, "alice",
                "budget-meeting");

        world.scheduleEngineeringAppointmentConvene("mgr-c", "mgr-c-task", 400L, A, "alice",
                "mgr-meeting", 450L, 500L, 600L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("mgr-a-" + i, "mgr-a-" + i + "-task",
                    410L + i, A, DIRECTORS[i], "mgr-meeting", false);
        }
        world.scheduleEngineeringMaterial("mgr-m", "mgr-m-task", 430L, A, "alice",
                "mgr-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("mgr-p", "mgr-p-task", 440L, A, "alice",
                "mgr-meeting", List.of(), new BoardModel.AppointmentRequest("mgr-a", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        700L, 900L * DAY));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("mgr-v-" + i, "mgr-v-" + i + "-task", 520L + i, A,
                    DIRECTORS[i], "mgr-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("mgr-i", "mgr-i-task", 600L, A, "alice",
                "mgr-meeting");
        world.advanceTo(700L);
        world.scheduleEngineeringAction("sign-a1", "sign-a1-task", 1_350L, A, "dave", "SIGN",
                "A1", "", 0, 0, ZERO, "", 0);
        world.scheduleEngineeringAction("start-a1", "start-a1-task", 1_450L, A, "dave",
                "START_WORK", "A1", "W-A1", 0, 60, ZERO, "", 0);
    }

    private static List<PeriodicActionPayload.PeriodicMetric> fourDimensions(long asOf) {
        return List.of(
                new PeriodicActionPayload.PeriodicMetric("OPERATING_COMMITMENT",
                        new BigDecimal("80"), "contract:A1", asOf),
                new PeriodicActionPayload.PeriodicMetric("CASH_SOLVENCY",
                        new BigDecimal("90"), "account:ENG-A", asOf),
                new PeriodicActionPayload.PeriodicMetric("RISK",
                        new BigDecimal("70"), "contract:A1", asOf));
    }

    private static String latestDraft(WorldRuntime world, long period) {
        String found = "";
        for (PeriodicModel.Draft draft : world.periodicOf(A).orElseThrow().drafts()) {
            if (draft.period() == period) {
                found = draft.draftId();
            }
        }
        assertFalse(found.isBlank(), "缺少草稿");
        return found;
    }

    private static void schedule(
            WorldRuntime world, String command, long due, String person, String action,
            long period, String draftId, String publisher, long expiresAt,
            List<PeriodicActionPayload.PeriodicMetric> metrics, String note) throws Exception {
        world.schedulePeriodicAction(command, command + "-task", due, A, person, action, period,
                draftId, 1L, publisher, expiresAt, "", metrics, note, 100L);
    }

    private static List<String> readAndApprove(WorldRuntime world, long base, String materialDocId,
            String draftId, String... readers) throws Exception {
        for (int i = 0; i < readers.length; i++) {
            world.scheduleStartReading("rd-" + base + "-" + i, "rd-" + base + "-" + i + "-task",
                    base + 100L + i * 2L, readers[i], materialDocId, 1L);
        }
        long afterRead = base + 3_600_000L;
        for (int i = 0; i < readers.length; i++) {
            schedule(world, "ap-" + base + "-" + i, afterRead + 1_000L + i * 10L, readers[i],
                    "APPROVE", 1L, draftId, "", 0L, List.of(), "");
        }
        world.advanceTo(afterRead + 2_000L);
        return List.of(readers);
    }

    @Test
    void fullWriteAndDisclosureChain() throws Exception {
        Path file = journal("full");
        WorldRuntime world = open(file);
        baseChain(world);
        world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);

        PeriodicModel.Snapshot snapshot = snapshot(world, 1L);
        BoardModel.Meeting meeting = meeting(world, snapshot.meetingId());
        assertEquals(BoardModel.CHARTER_AUTO, meeting.convenerSeatId());
        long opensAt = meeting.opensAt();

        // 阶段一续接：采样后进行中，真实重启仍能续上。
        world.close();
        world = open(file);
        assertTrue(world.periodicOf(A).orElseThrow().snapshots().size() >= 1);

        for (int i = 0; i < DIRECTORS.length; i++) {
            world.scheduleEngineeringAttend("pd-a-" + i, "pd-a-" + i + "-task",
                    snapshot.capturedAt() + 10L + i, A, DIRECTORS[i], snapshot.meetingId(), false);
        }
        long prepareAt = opensAt + 1_000L;
        schedule(world, "prep-1", prepareAt, "dave", "PREPARE", 1L, "", "", 0L, List.of(), "");
        // 未实读就批准必须被拒（同一董事随后真实读完仍可再批）。
        schedule(world, "bad-approve", prepareAt + 200L, "alice", "APPROVE", 1L,
                "", "", 0L, List.of(), "");
        world.advanceTo(prepareAt + 300L);
        assertEquals("NOT_READ_EXACT_VERSION", report(world, "bad-approve").reason());

        String draftId = latestDraft(world, 1L);
        final String firstDraftId = draftId;
        PeriodicModel.Draft draft = world.periodicOf(A).orElseThrow().drafts().stream()
                .filter(row -> row.draftId().equals(firstDraftId)).findFirst().orElseThrow();
        InformationModel.DocumentVersion material = InformationModel.version(
                world.snapshot().information().orElseThrow().information(), draft.docId(),
                draft.version()).orElseThrow();
        assertEquals(snapshot.statementDigest(),
                PeriodicWorldEngine.sha256(material.body()));

        // 取消的阅读不算实读：erin 开始读但在应完成前取消，之后批准必须被拒。
        long cancelBase = prepareAt + 400L;
        final String firstDocId = draft.docId();
        world.scheduleStartReading("erin-read", "erin-read-task", cancelBase, "erin",
                firstDocId, draft.version());
        world.advanceTo(cancelBase + 100L);
        RoleView.ReadingRef erinRef = world.roleView("erin").readings().stream()
                .filter(row -> row.ref().docId().equals(firstDocId))
                .findFirst().orElseThrow().ref();
        world.scheduleCancelReading("erin-cancel", "erin-cancel-task", cancelBase + 200L,
                "erin", erinRef);
        world.advanceTo(cancelBase + 300L);
        schedule(world, "erin-approve", cancelBase + 500L, "erin", "APPROVE", 1L,
                draftId, "", 0L, List.of(), "");
        world.advanceTo(cancelBase + 600L);
        assertEquals("NOT_READ_EXACT_VERSION", report(world, "erin-approve").reason());

        // 三名董事真实读完并评分；第五维只用真实长期项目/履约证据。
        long scoreReadBase = cancelBase + 1_000L;
        long scoreBase = scoreReadBase + PeriodicWorldEngine.MATERIAL_READ_MILLIS;
        for (int i = 0; i < 3; i++) {
            world.scheduleStartReading("sc-rd-" + i, "sc-rd-" + i + "-task",
                    scoreReadBase + i * 2L, DIRECTORS[i], draft.docId(), draft.version());
        }
        world.advanceTo(scoreBase);
        // 维度证据绑定：长期投入无机制、披露无成熟事实时不许用普通证据凑维度。
        schedule(world, "bad-longterm", scoreBase + 300L, "bob", "SCORE", 1L, "", "", 0L,
                List.of(new PeriodicActionPayload.PeriodicMetric("LONG_TERM_INVESTMENT",
                        new BigDecimal("50"), "work:W-A1", snapshot.capturedAt())), "");
        schedule(world, "bad-disclosure", scoreBase + 500L, "bob", "SCORE", 1L, "", "", 0L,
                List.of(new PeriodicActionPayload.PeriodicMetric("DISCLOSURE",
                        new BigDecimal("60"), "periodic-source:" + A + ":1",
                        snapshot.capturedAt())), "");
        world.advanceTo(scoreBase + 700L);
        assertEquals("DIMENSION_NOT_AVAILABLE", report(world, "bad-longterm").reason());
        assertEquals("EVIDENCE_NOT_ELIGIBLE", report(world, "bad-disclosure").reason());
        for (int i = 0; i < 3; i++) {
            schedule(world, "score-" + i, scoreBase + 1_000L + i * 10L, DIRECTORS[i], "SCORE",
                    1L, "", "", 0L, fourDimensions(snapshot.capturedAt()), "");
        }
        world.advanceTo(scoreBase + 2_000L);
        final WorldRuntime scored = world;
        assertEquals(3, world.periodicOf(A).orElseThrow().ballots().size(),
                () -> "评分回报：" + scored.snapshot().engineering().orElseThrow().reports()
                        .values().stream()
                        .filter(entry -> entry.kind().equals(
                                PendingTask.ENGINEERING_PERIODIC_V1))
                        .toList() + " 证据=" + snapshot(scored, 1L).evidenceRefs());

        // 评分窗口关闭：三席都缺长期投入/披露维，汇总必须如实为 INCOMPLETE，不补零、不出总分。
        world.advanceTo(meeting.closesAt() + 10L);
        PeriodicModel.Score score = world.periodicOf(A).orElseThrow().scores().stream()
                .filter(row -> row.period() == 1L).findFirst().orElseThrow();
        assertEquals(BoardModel.ReviewStatus.INCOMPLETE, score.status());
        assertEquals("NO_QUORUM", score.reason());
        assertEquals(0, score.seatCount());
        assertEquals(null, score.total());
        assertEquals(3, world.periodicOf(A).orElseThrow().ballots().size());

        // 阶段二续接：已批准前必须有真实三席；先续接再批准，证明恢复不丢已读/已评分。
        world.close();
        world = open(file);
        draftId = latestDraft(world, 1L);
        final String secondDraftId = draftId;
        draft = world.periodicOf(A).orElseThrow().drafts().stream()
                .filter(row -> row.draftId().equals(secondDraftId)).findFirst().orElseThrow();
        String materialDocId = draft.docId();
        assertEquals(1, world.periodicOf(A).orElseThrow().scores().size());

        long approveBase = meeting.closesAt() + 20L;
        long afterRead = approveBase + 3_600_000L;
        for (int i = 0; i < 3; i++) {
            world.scheduleStartReading("ap-rd-" + i, "ap-rd-" + i + "-task",
                    approveBase + i * 2L, DIRECTORS[i], materialDocId, draft.version());
        }
        world.advanceTo(afterRead);
        for (int i = 0; i < 3; i++) {
            schedule(world, "approve-" + i, afterRead + 1_000L + i * 10L, DIRECTORS[i],
                    "APPROVE", 1L, draftId, "", 0L, List.of(), "");
        }
        world.advanceTo(afterRead + 2_000L);
        final WorldRuntime approvedWorld = world;
        assertEquals(3, world.periodicOf(A).orElseThrow().approvals().size(),
                () -> "批准回报：" + approvedWorld.snapshot().engineering().orElseThrow()
                        .reports().values().stream()
                        .filter(entry -> entry.commandId().startsWith("approve-"))
                        .toList() + " 阅读=" + approvedWorld.snapshot().information()
                        .orElseThrow().information().closed().stream()
                        .filter(row -> row.docId().equals("periodic-material:" + A + ":1"))
                        .toList());

        // 阶段二续接（已批准、未发布）：重启后批准票与草稿绑定必须原样续上，且仍未发布。
        world.close();
        world = open(file);
        assertEquals(3, world.periodicOf(A).orElseThrow().approvals().size(),
                "批准后重启不得丢票");
        assertTrue(world.periodicOf(A).orElseThrow().releases().isEmpty(),
                "重启不得凭空产生发布");

        // 受信绑定复核：只认 COMPLETED 阅读；取消不算；回忆按传入时点与真实阈值，不受预算截断影响。
        PeriodicModel.Access probe = world.periodicAccess();
        assertTrue(probe.hasRead("alice", materialDocId, draft.version(), afterRead));
        assertFalse(probe.hasRead("investor", materialDocId, draft.version(), afterRead));
        assertFalse(probe.hasRead("erin", materialDocId, draft.version(), afterRead));
        assertTrue(probe.recallEligible("alice", materialDocId, draft.version(), afterRead));
        assertFalse(probe.recallEligible("alice", materialDocId, draft.version(),
                afterRead + 60L * DAY));

        // 经理为指定发布人签发一次授权；由该人真实发布 PUBLIC 版本。
        long authorizeAt = afterRead + 3_000L;
        schedule(world, "authorize-1", authorizeAt, "dave", "AUTHORIZE", 1L, draftId, "alice",
                authorizeAt + DAY, List.of(), "");
        long publishAt = authorizeAt + 1_000L;
        schedule(world, "publish-1", publishAt, "alice", "PUBLISH", 1L, draftId, "", 0L,
                List.of(), "");
        world.advanceTo(publishAt + 10L);

        PeriodicModel.CompanyPeriods periods = world.periodicOf(A).orElseThrow();
        assertEquals(1, periods.releases().size());
        PeriodicModel.Release release = periods.releases().get(0);
        assertFalse(release.late());
        assertEquals(1L, release.version());
        assertTrue(periods.missed().isEmpty());
        String publicDocId = PeriodicWorldEngine.publicDocId(A, 1L);
        InformationModel.DocumentVersion publicV1 = InformationModel.version(
                world.snapshot().information().orElseThrow().information(), publicDocId, 1L)
                .orElseThrow();
        assertEquals(material.body(), publicV1.body());
        assertEquals(InformationModel.Audience.publicAudience(),
                InformationModel.audienceOf(world.snapshot().information().orElseThrow()
                        .information(), publicDocId).orElseThrow());

        // 阶段三续接：已发布后重启，公开版本与私稿绑定不变。
        world.close();
        world = open(file);
        assertEquals(1, InformationModel.version(world.snapshot().information().orElseThrow()
                .information(), publicDocId, 1L).orElseThrow().version());

        // 更正：新私稿 → 新读 → 新批 → 新授权 → PUBLIC v2，正文逐字等于新私稿。
        PeriodicModel.Snapshot current = snapshot(world, 1L);
        long correctAt = publishAt + 5_000L;
        schedule(world, "prep-2", correctAt, "dave", "PREPARE", 1L, "", "", 0L, List.of(),
                "上期应收口径更正");
        world.advanceTo(correctAt + 100L);
        String corrected = latestDraft(world, 1L);
        assertFalse(corrected.equals(draftId));
        PeriodicModel.Draft correctedDraft = world.periodicOf(A).orElseThrow().drafts().stream()
                .filter(row -> row.draftId().equals(corrected)).findFirst().orElseThrow();
        assertEquals(correctedDraft.version(), 1L);
        readAndApprove(world, correctAt + 1_000L, correctedDraft.docId(), corrected,
                "alice", "bob", "carol");
        long authorize2 = correctAt + 1_000L + 3_600_000L + 4_000L;
        schedule(world, "authorize-2", authorize2, "dave", "AUTHORIZE", 1L, corrected, "alice",
                authorize2 + DAY, List.of(), "");
        schedule(world, "publish-2", authorize2 + 1_000L, "alice", "PUBLISH", 1L, corrected, "",
                0L, List.of(), "");
        world.advanceTo(authorize2 + 2_000L);
        assertEquals(2, world.periodicOf(A).orElseThrow().releases().size());
        InformationModel.DocumentVersion publicV2 = InformationModel.version(
                world.snapshot().information().orElseThrow().information(), publicDocId, 2L)
                .orElseThrow();
        assertTrue(publicV2.body().contains("更正说明"));
        assertEquals(correctedDraft.bodyDigest(),
                PeriodicWorldEngine.sha256(publicV2.body()));
        // 旧版本仍保留且正文不变。
        assertEquals(material.body(), InformationModel.version(world.snapshot().information()
                .orElseThrow().information(), publicDocId, 1L).orElseThrow().body());

        // 保留命名空间：普通发表不能占用两组周期文档。
        final WorldRuntime currentWorld = world;
        assertThrows(IllegalArgumentException.class, () -> currentWorld.schedulePublishDocument(
                "sneak", "sneak-task", currentWorld.snapshot().timeMillis() + 1_000L,
                "periodic-material:" + A + ":9", WorldWebScenario.INVESTOR,
                InformationModel.Audience.publicAudience(), "伪造", "伪造", "", 10L, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> currentWorld.schedulePublishDocumentVersion(
                        "sneak2", "sneak2-task", currentWorld.snapshot().timeMillis() + 1_000L,
                        "company-statement:" + A + ":1", "伪造", "伪造", "", 10L, List.of()));

        // 期 2：经理真实准备材料但无人阅读/评分 → 汇总为 INCOMPLETE(NO_QUORUM)，不补 0；
        // 披露截止真实记 MISSED，与“没人评分”解耦且不回填。
        world.advanceTo(PeriodicModel.PERIOD_MILLIS * 2L + 5L);
        BoardModel.Meeting secondMeeting = meeting(world, "periodic-meeting:" + A + ":2");
        long prepareSecond = secondMeeting.opensAt() + 1_000L;
        schedule(world, "prep-p2", prepareSecond, "dave", "PREPARE", 2L, "", "", 0L,
                List.of(), "");
        world.advanceTo(prepareSecond + 100L);
        long secondDeadline = PeriodicModel.PERIOD_MILLIS * 2L
                + PeriodicWorldEngine.DISCLOSURE_WINDOW_MILLIS;
        world.advanceTo(secondDeadline + 10L);
        PeriodicModel.Score second = world.periodicOf(A).orElseThrow().scores().stream()
                .filter(row -> row.period() == 2L).findFirst().orElseThrow();
        assertEquals(BoardModel.ReviewStatus.INCOMPLETE, second.status());
        assertEquals("NO_QUORUM", second.reason());
        assertTrue(world.periodicOf(A).orElseThrow().missed().stream()
                .anyMatch(marker -> marker.period() == 2L));

        // 第 3 期季度考核：真实采样存在，仍不自动评分。
        world.advanceTo(PeriodicModel.PERIOD_MILLIS * 3L + 10L);
        assertEquals(3, world.periodicOf(A).orElseThrow().snapshots().size());
        assertTrue(world.periodicOf(A).orElseThrow().ballots().stream()
                .noneMatch(ballot -> ballot.period() >= 2L));

        // 人物投影不泄露全局 position、私有来源或 God 审计。
        Map<String, Object> view = world.periodicRoleView("alice", A);
        assertTrue(view.containsKey("periods"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) view.get("periods");
        for (Map<String, Object> row : rows) {
            assertFalse(row.containsKey("position"));
            assertFalse(row.containsKey("materialSourceRef"));
            assertFalse(row.containsKey("godPeriods"));
        }
        world.close();
    }
}
