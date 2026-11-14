package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;

import java.nio.file.Path;
import java.util.List;

/**
 * 三进程联合探针：经理任免 + 周期快照 + 群帖读，分段在新 JVM 继续与验证。
 */
public final class GroupsPeriodicJointProbe {

    private static final String A = WorldWebScenario.ENGINE_A;
    private static final List<String> DIRECTORS = List.of("alice", "bob", "carol");

    private GroupsPeriodicJointProbe() {
    }

    /**
     * 入口。
     *
     * @param args {@code <journal> <phase1|phase2|verify>}
     * @throws Exception 失败
     */
    public static void main(String[] args) throws Exception {
        Path journal = Path.of(args[0]);
        String phase = args[1];
        try (WorldRuntime world = WorldWebScenario.openGroups(journal).runtime()) {
            if ("phase1".equals(phase)) {
                world.advanceTo(PeriodicModel.PERIOD_MILLIS + 1L);
                publish(world, "b-material", "预算材料");
                publish(world, "appt-material", "任免材料");
                for (int i = 0; i < 3; i++) {
                    world.advanceTo(world.snapshot().timeMillis() + 1);
                }
                long now = world.snapshot().timeMillis();
                world.scheduleEngineeringAppointmentConvene("mgr-c", "mgr-c-task", now + 200,
                        WorldWebScenario.COMPANY, "alice", "mgr-meeting", now + 250, now + 260,
                        now + 300);
                for (int i = 0; i < 3; i++) {
                    world.scheduleEngineeringAttend("mgr-a-" + i, "mgr-a-" + i + "-task",
                            now + 210 + i, WorldWebScenario.COMPANY, DIRECTORS.get(i),
                            "mgr-meeting", false);
                }
                world.scheduleEngineeringMaterial("mgr-m", "mgr-m-task", now + 230,
                        WorldWebScenario.COMPANY, "alice", "mgr-meeting", "mm", "appt-material",
                        1L);
                world.scheduleEngineeringAppointmentProposal("mgr-p", "mgr-p-task", now + 240,
                        WorldWebScenario.COMPANY, "alice", "mgr-meeting", List.of(),
                        new BoardModel.AppointmentRequest("mgr-a", "dave",
                                WorldWebScenario.COMPANY, RoleAccess.Role.COMPANY_MANAGER,
                                BoardModel.AppointmentAction.APPOINT, now + 500, 900L * 86_400_000L));
                for (int i = 0; i < 3; i++) {
                    world.scheduleEngineeringVote("mgr-v-" + i, "mgr-v-" + i + "-task",
                            now + 270 + i, WorldWebScenario.COMPANY, DIRECTORS.get(i),
                            "mgr-meeting", BoardModel.Vote.YES);
                }
                world.scheduleEngineeringImplement("mgr-i", "mgr-i-task", now + 400,
                        WorldWebScenario.COMPANY, "alice", "mgr-meeting");
                world.advanceTo(now + 520);
                long g = world.snapshot().timeMillis();
                world.openRoleSession("alice").postToGroup("joint-post", "joint-post-task",
                        g + 20, new WorldRoleSession.GroupPostDraft(
                                WorldWebScenario.GROUPS_OPEN, "联合帖", "经理已任免后的群内发言",
                                List.of()));
                world.advanceTo(g + 20);
                world.advanceTo(world.snapshot().timeMillis() + 5);
                long r = world.snapshot().timeMillis();
                world.scheduleStartReading("joint-read", "joint-read-task", r + 10, "bob",
                        world.groupsRun().state().posts().get(0).docId(), 1L);
                world.advanceTo(r + 20);
                System.out.println("PHASE1 manager=" + world.isActiveManager(
                        WorldWebScenario.COMPANY, "dave", world.snapshot().timeMillis())
                        + " periods=" + world.periodicOf(WorldWebScenario.COMPANY)
                        .map(state -> state.snapshots().size()).orElse(0)
                        + " post=" + world.groupsRun().state().posts().get(0).status());
            } else if ("phase2".equals(phase)) {
                GroupModel.Post post = world.groupsRun().state().posts().get(0);
                boolean observed = InformationModel.observations(world.snapshot()
                        .information().orElseThrow().information(), "bob").stream()
                        .anyMatch(item -> item.docId().equals(post.docId()));
                System.out.println("PHASE2 manager=" + world.isActiveManager(
                        WorldWebScenario.COMPANY, "dave", world.snapshot().timeMillis())
                        + " period=" + (world.periodicRoleView("alice",
                        WorldWebScenario.COMPANY).get("periods") instanceof java.util.List<?> list
                        && !list.isEmpty())
                        + " groupRead=" + observed);
            } else {
                GroupModel.Post post = world.groupsRun().state().posts().get(0);
                long seats = world.boardOfCompany(WorldWebScenario.COMPANY)
                        .map(run -> (long) run.board().charter().seats().size()).orElse(0L);
                System.out.println("VERIFY manager=" + world.isActiveManager(
                        WorldWebScenario.COMPANY, "dave", world.snapshot().timeMillis())
                        + " published=" + (post.status() == GroupModel.PostStatus.PUBLISHED)
                        + " seats=" + seats + " position=" + world.snapshot().position());
                if (!world.isActiveManager(WorldWebScenario.COMPANY, "dave",
                        world.snapshot().timeMillis())
                        || post.status() != GroupModel.PostStatus.PUBLISHED || seats != 5) {
                    throw new IllegalStateException("第三进程验证失败");
                }
            }
        }
    }

    private static void publish(WorldRuntime world, String docId, String title) throws Exception {
        long now = world.snapshot().timeMillis();
        world.schedulePublishDocument("pub-" + docId, "pub-task-" + docId, now + 1, docId,
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(), title,
                title + "正文", "实验材料", 10L, List.of());
        world.advanceTo(now + 1);
        for (String person : DIRECTORS) {
            long due = world.snapshot().timeMillis() + 10;
            world.scheduleStartReading("read-" + docId + "-" + person,
                    "read-task-" + docId + "-" + person, due, person, docId, 1L);
            world.advanceTo(due + 20);
        }
    }
}
