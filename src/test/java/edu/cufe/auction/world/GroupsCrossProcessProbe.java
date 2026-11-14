package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;

import java.nio.file.Path;
import java.util.List;

/**
 * 真跨进程探针：三个独立 JVM 依次打开同一 schema20 日志，完成"发帖未投递→续投并读→只读验证"。
 */
public final class GroupsCrossProcessProbe {

    private GroupsCrossProcessProbe() {
    }

    /**
     * 入口。
     *
     * @param args {@code <journal> <post|publish|verify>}
     * @throws Exception 运行失败
     */
    public static void main(String[] args) throws Exception {
        Path journal = Path.of(args[0]);
        String phase = args[1];
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(journal)) {
            if ("post".equals(phase)) {
                WorldRoleSession alice = world.openRoleSession("alice");
                long now = world.snapshot().timeMillis();
                alice.postToGroup("xp-post", "xp-post-t", now + 20,
                        new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN,
                                "跨进程标题", "跨进程正文", List.of()));
                world.advanceTo(now + 20);
                GroupModel.Post post = world.groupsRun().state().posts().get(0);
                System.out.println("PHASE1 status=" + post.status() + " doc=" + post.docId()
                        + " publishAt=" + post.publishAt() + " pending="
                        + world.snapshot().pending().size());
            } else if ("publish".equals(phase)) {
                GroupModel.Post post = world.groupsRun().state().posts().get(0);
                world.advanceTo(post.publishAt());
                long t = world.snapshot().timeMillis();
                world.scheduleStartReading("xp-read", "xp-read-t", t + 10, "bob",
                        post.docId(), 1L);
                world.advanceTo(t + 20);
                GroupModel.Post after = world.groupsRun().state().posts().get(0);
                System.out.println("PHASE2 status=" + after.status() + " doc=" + after.docId()
                        + " observations=" + InformationModel.observations(
                        world.snapshot().information().orElseThrow().information(), "bob").size());
            } else {
                GroupModel.Post post = world.groupsRun().state().posts().get(0);
                int observations = InformationModel.observations(
                        world.snapshot().information().orElseThrow().information(), "bob").size();
                boolean inCatalog = world.openRoleSession("bob").view().profile().catalog()
                        .stream().anyMatch(entry -> entry.docId().equals(post.docId()));
                System.out.println("PHASE3 position=" + world.snapshot().position() + " status="
                        + post.status() + " observations=" + observations + " catalog="
                        + inCatalog);
                if (post.status() != GroupModel.PostStatus.PUBLISHED || observations < 1
                        || !inCatalog) {
                    throw new IllegalStateException("第三进程验证失败");
                }
            }
        }
    }
}
