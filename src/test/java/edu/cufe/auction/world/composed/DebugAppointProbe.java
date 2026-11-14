package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.BoardRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 调试：逐任务打印 cufe 会议链的接受/拒绝原因。 */
public final class DebugAppointProbe {

    private static final long DAY = 86_400_000L;

    private DebugAppointProbe() {
    }

    /** @param args 无参 */
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbg-appoint").resolve("world.log");
        try (WorldRuntime runtime = ComposedWorldScenario.open(dir)) {
            long start = runtime.snapshot().timeMillis();
            appoint(runtime, start, "cufe", "dave", "cufe", "cufe-mgr");
        }
    }

    private static void appoint(WorldRuntime runtime, long start, String company, String manager,
            String tag, String docId) throws Exception {
        runtime.schedulePublishDocument("pub-" + tag, "pub-" + tag + "-t", start + 1, docId,
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料 " + tag, "真实任免材料 " + tag, "实验材料", 10L, List.of());
        String meeting = "mtg-" + tag;
        runtime.scheduleCompanyBoardConvene("cv-" + tag, "cvt-" + tag, start + 2, company,
                "alice", meeting, start + 12, start + 14, start + 40, true);
        runtime.advanceTo(start + 3);
        for (String person : List.of("alice", "bob", "carol", "dave")) {
            runtime.scheduleCompanyBoardAttend("at-" + tag + "-" + person,
                    "att-" + tag + "-" + person, start + 5, company, person, meeting);
        }
        runtime.advanceTo(start + 6);
        runtime.scheduleCompanyBoardMaterial("mt-" + tag, "mtt-" + tag, start + 8, company,
                "alice", meeting, "mat-" + tag, docId, 1L);
        for (String person : List.of("bob", "carol", "dave")) {
            runtime.scheduleStartReading("rd-" + tag + "-" + person,
                    "rdt-" + tag + "-" + person, start + 7, person, docId, 1L);
        }
        runtime.advanceTo(start + 8);
        BoardModel.AppointmentRequest request = new BoardModel.AppointmentRequest(
                "app-" + tag, manager, company, RoleAccess.Role.COMPANY_MANAGER,
                BoardModel.AppointmentAction.APPOINT, start + 43, start + 900 * DAY);
        runtime.scheduleCompanyBoardAppointmentProposal("pp-" + tag, "ppt-" + tag, start + 10,
                company, "alice", meeting, request);
        runtime.advanceTo(start + 10);
        dump(runtime, "after proposal");
        BoardModel.State now = runtime.snapshot().governance().orElseThrow().board();
        BoardModel.Outcome trial = BoardModel.proposeManagerAppointment(now, start + 10, meeting,
                "s1", List.of(), request);
        System.out.println("  [trial-proposal] accepted=" + trial.accepted() + " reason="
                + trial.reason() + " proposalExists="
                + (now.meeting(meeting) != null && now.meeting(meeting).appointmentProposal() != null));
        runtime.advanceTo(start + 16);
        dump(runtime, "after open");
        for (String person : List.of("bob", "carol", "dave")) {
            runtime.scheduleCompanyBoardVote("vt-" + tag + "-" + person,
                    "vtt-" + tag + "-" + person, start + 18, company, person, meeting,
                    BoardModel.Vote.YES, 1L);
        }
        runtime.advanceTo(start + 20);
        dump(runtime, "after votes");
        runtime.advanceTo(start + 40);
        dump(runtime, "after close");
        runtime.scheduleCompanyBoardImplement("im-" + tag, "imt-" + tag, start + 42, company,
                "alice", meeting);
        runtime.advanceTo(start + 44);
        dump(runtime, "after implement");
    }

    private static void dump(WorldRuntime runtime, String label) {
        System.out.println("== " + label + " t=" + runtime.snapshot().timeMillis());
        BoardRun gov = runtime.snapshot().governance().orElse(null);
        if (gov != null) {
            gov.reports().values().forEach(r -> System.out.println(
                    "  [gov-report] " + r.taskId() + " " + r.kind() + " " + r.accepted() + " "
                            + r.reason()));
            gov.board().meetings().values().forEach(m -> System.out.println(
                    "  [gov-meeting] " + m.meetingId() + " " + m.status() + " ballots="
                            + m.ballots() + " materials=" + m.materials().size()));
        }
        var engOpt = runtime.snapshot().engineering();
        if (engOpt.isEmpty()) {
            return;
        }
        engOpt.get().reports().values().stream()
                .filter(r -> r.companyPartyId().equals("cufe"))
                .forEach(r -> System.out.println("  [eng-report] " + r.taskId() + " " + r.kind()
                        + " " + r.accepted() + " " + r.reason()));
        runtime.snapshot().information().ifPresent(i -> i.information().observations()
                .forEach(o -> System.out.println("  [read] " + o.personPartyId() + " "
                        + o.docId() + " v" + o.version() + " at " + o.completedAt())));
    }
}
