package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界级反例：本次会议回避、截止同刻、遗忘者批准、不足三席，都不得被评分或披露链放过。
 */
class EngineeringPeriodicNegativeTest {

    private static final String A = WorldWebScenario.ENGINE_A;
    private static final String[] DIRECTORS = {"alice", "bob", "carol", "dave", "erin"};
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-periodic-negative-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static WorldRuntime open(Path file) throws Exception {
        return WorldWebScenario.engineeringRuntime(file, WorldWebScenario.engineeringConfig());
    }

    private static EngineeringRun.Report report(WorldRuntime world, String prefix) {
        EngineeringRun.Report latest = null;
        for (EngineeringRun.Report entry : world.snapshot().engineering().orElseThrow()
                .reports().values()) {
            if (entry.taskId().startsWith(prefix)
                    && (PendingTask.ENGINEERING_PERIODIC_V1.equals(entry.kind())
                    || PendingTask.ENGINEERING_BEGIN_V1.equals(entry.kind()))
                    && (latest == null || entry.position() > latest.position())) {
                latest = entry;
            }
        }
        if (latest == null) {
            throw new AssertionError("缺少回报：" + prefix);
        }
        return latest;
    }

    private static void act(WorldRuntime world, String command, long due, String person,
            String action, long period, String draftId, List<PeriodicActionPayload.PeriodicMetric> metrics,
            String note) throws Exception {
        world.schedulePeriodicAction(command, command + "-task", due, A, person, action, period,
                draftId, 1L, "", 0L, "", metrics, note, 100L);
    }

    private static void appointManager(WorldRuntime world) throws Exception {
        world.schedulePublishDocument("pub-budget", "pub-budget-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        for (int i = 0; i < DIRECTORS.length; i++) {
            world.scheduleStartReading("read-mat-" + DIRECTORS[i],
                    "read-mat-" + DIRECTORS[i] + "-task", 20L + i * 2L, DIRECTORS[i],
                    "b-material", 1L);
            world.scheduleStartReading("read-appt-" + DIRECTORS[i],
                    "read-appt-" + DIRECTORS[i] + "-task", 60L + i * 2L, DIRECTORS[i],
                    "appt-material", 1L);
        }
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
                        700L, 900L * 86_400_000L));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("mgr-v-" + i, "mgr-v-" + i + "-task", 520L + i, A,
                    DIRECTORS[i], "mgr-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("mgr-i", "mgr-i-task", 600L, A, "alice",
                "mgr-meeting");
        world.advanceTo(700L);
    }

    /** 采样 + 五位董事到会（erin 自报回避）+ 经理准备材料，返回开会时刻。 */
    private static long prepareMeeting(WorldRuntime world) throws Exception {
        appointManager(world);
        world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);
        PeriodicModel.Snapshot snapshot = world.periodicOf(A).orElseThrow().snapshots().get(0);
        BoardModel.Meeting meeting = world.boardOfCompany(A).orElseThrow().board()
                .meeting(snapshot.meetingId());
        for (int i = 0; i < DIRECTORS.length; i++) {
            boolean recuse = "erin".equals(DIRECTORS[i]);
            act(world, "att-" + i, snapshot.capturedAt() + 1_000L + i, DIRECTORS[i], "ATTEND",
                    1L, "", List.of(), recuse ? "RECUSE" : "");
        }
        long prep = meeting.opensAt() + 1_000L;
        act(world, "prep", prep, "dave", "PREPARE", 1L, "", List.of(), "");
        world.advanceTo(prep + 100L);
        return meeting.opensAt();
    }

    private static String draftDoc(WorldRuntime world) {
        return world.periodicOf(A).orElseThrow().drafts().get(0).docId();
    }

    @Test
    void recusedSeatExactCloseAndTwoSeatsAreRejected() throws Exception {
        Path file = journal("guards");
        WorldRuntime world = open(file);
        long opensAt = prepareMeeting(world);
        PeriodicModel.Snapshot snapshot = world.periodicOf(A).orElseThrow().snapshots().get(0);
        BoardModel.Meeting meeting = world.boardOfCompany(A).orElseThrow().board()
                .meeting(snapshot.meetingId());
        long readBase = opensAt + 2_000L;
        for (String person : List.of("alice", "bob", "carol", "erin")) {
            world.scheduleStartReading("rd-" + person, "rd-" + person + "-task", readBase,
                    person, draftDoc(world), 1L);
            readBase += 2L;
        }
        long scoreAt = opensAt + 2_000L + PeriodicWorldEngine.MATERIAL_READ_MILLIS + 1_000L;
        world.advanceTo(scoreAt);
        List<PeriodicActionPayload.PeriodicMetric> cash = List.of(
                new PeriodicActionPayload.PeriodicMetric("CASH_SOLVENCY", new BigDecimal("90"),
                        "account:ENG-A", snapshot.capturedAt()));
        // 本次会议已回避的席位不能评分。
        act(world, "erin-score", scoreAt + 200L, "erin", "SCORE", 1L, "", cash, "");
        // 截止同刻（closesAt）的评分必须被拒（若关会先到则计票已闭，同样不计入）。
        act(world, "late-score", meeting.closesAt(), "alice", "SCORE", 1L, "", cash, "");
        // 只有两席评分：汇总必须缺省，不出总分。
        act(world, "s1", scoreAt + 400L, "alice", "SCORE", 1L, "", cash, "");
        act(world, "s2", scoreAt + 500L, "bob", "SCORE", 1L, "", cash, "");
        world.advanceTo(meeting.closesAt() + 10L);
        assertEquals("SEAT_RECUSED", report(world, "erin-score").reason());
        EngineeringRun.Report late = report(world, "late-score");
        assertTrue(!late.accepted(), "截止同刻的评分不得被接受");
        assertTrue("REVIEW_WINDOW_CLOSED".equals(late.reason())
                || "BALLOTS_CLOSED".equals(late.reason())
                || "NOT_READ_EXACT_VERSION".equals(late.reason()), "拒绝原因：" + late.reason());
        assertEquals(2, world.periodicOf(A).orElseThrow().ballots().size());
        PeriodicModel.Score score = world.periodicOf(A).orElseThrow().scores().stream()
                .filter(row -> row.period() == 1L).findFirst().orElseThrow();
        assertEquals(BoardModel.ReviewStatus.INCOMPLETE, score.status());
        assertEquals(0, score.seatCount());
        assertTrue(score.total() == null);
        world.close();
    }

    @Test
    void forgottenApproverIsRejectedButPastApprovalsStay() throws Exception {
        Path file = journal("forget");
        WorldRuntime world = open(file);
        long opensAt = prepareMeeting(world);
        String docId = draftDoc(world);
        String draftId = world.periodicOf(A).orElseThrow().drafts().get(0).draftId();
        world.scheduleStartReading("rd-bob", "rd-bob-task", opensAt + 2_000L, "bob", docId, 1L);
        long readDone = opensAt + 2_000L + PeriodicWorldEngine.MATERIAL_READ_MILLIS;
        world.advanceTo(readDone);
        act(world, "early-approve", readDone + 1_000L, "bob", "APPROVE", 1L, draftId,
                List.of(), "");
        world.advanceTo(readDone + 2_000L);
        assertTrue(report(world, "early-approve").accepted());
        // 越过回忆阈值（14 日半衰期、100‰）后，同一人不能再批准新稿。
        long later = readDone + 60L * 86_400_000L;
        act(world, "forgot-prep", later, "dave", "PREPARE", 1L, "", List.of(), "更正稿");
        world.advanceTo(later + 100L);
        String corrected = world.periodicOf(A).orElseThrow().drafts().stream()
                .filter(row -> !row.draftId().equals(draftId)).findFirst().orElseThrow()
                .draftId();
        String correctedDoc = world.periodicOf(A).orElseThrow().drafts().stream()
                .filter(row -> row.draftId().equals(corrected)).findFirst().orElseThrow()
                .docId();
        world.scheduleStartReading("rd-corr", "rd-corr-task", later + 500L, "bob",
                correctedDoc, 1L);
        long readCorrected = later + 500L + PeriodicWorldEngine.MATERIAL_READ_MILLIS;
        world.advanceTo(readCorrected);
        long forgotAt = readCorrected + 60L * 86_400_000L;
        act(world, "forgot-approve", forgotAt + 1_000L, "bob", "APPROVE", 1L, corrected,
                List.of(), "");
        world.advanceTo(forgotAt + 2_000L);
        assertEquals("NOT_RECALL_ELIGIBLE", report(world, "forgot-approve").reason());
        // 已发生的旧批准不被之后的遗忘抹掉。
        assertTrue(world.periodicOf(A).orElseThrow().approvals().stream()
                .anyMatch(row -> row.draftId().equals(draftId)));
        world.close();
    }
}
