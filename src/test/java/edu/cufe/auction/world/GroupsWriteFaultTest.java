package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema20 写故障：排程帧、完成帧、系统投递帧与决定曝光帧被短写时，
 * 世界经济/信息/成员/焦点/曝光候选不得半应用，恢复保持已提交并只重做一次。
 *
 * <p>沿用现有故障工具：真实 journal 帧被截断到半帧，重开由 {@link WorldJournal} 丢弃未确认尾帧。
 * 不另建日志、不替换真实命令。</p>
 */
class GroupsWriteFaultTest {

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "groups-fault-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static void truncateMidFrame(Path file, long boundary) throws Exception {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            assertTrue(channel.size() > boundary + 20, "必须有足够字节可截半");
            channel.truncate(boundary + 20);
            channel.force(true);
        }
    }

    private static long postTransition(WorldRuntime world) throws Exception {
        return world.groupsRun().state().posts().get(0).publishedAt();
    }

    @Test
    void truncatedScheduleFrameKeepsNoHalfMembership() throws Exception {
        Path file = journal("schedule");
        long boundary;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            boundary = Files.size(file);
            long now = world.snapshot().timeMillis();
            world.openRoleSession("dave").joinGroup("join", "join-t", now + 10,
                    new WorldRoleSession.GroupJoinDraft(WorldWebScenario.GROUPS_OPEN));
            assertTrue(Files.size(file) > boundary);
        }
        truncateMidFrame(file, boundary);
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            assertTrue(world.groupsRun().membershipAt(WorldWebScenario.GROUPS_OPEN, "dave",
                    world.snapshot().timeMillis() + 100).isEmpty(),
                    "未确认的排程帧不得留下成员或 Draft");
            assertTrue(world.snapshot().pending().stream()
                    .noneMatch(task -> task.taskId().contains("join-t")), "未确认任务不得残留");
            long now = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:join-t", world.openRoleSession("dave").joinGroup("join",
                    "join-t", now + 10,
                    new WorldRoleSession.GroupJoinDraft(WorldWebScenario.GROUPS_OPEN)),
                    "同命令在新进程可重新执行");
            world.advanceTo(now + 10);
            assertTrue(world.groupsRun().membershipAt(WorldWebScenario.GROUPS_OPEN, "dave",
                    now + 10).isPresent(), "重做后成员段恰好一次");
        }
    }

    @Test
    void truncatedFinishFrameKeepsQueuedOnce() throws Exception {
        Path file = journal("finish");
        long boundary;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            long now = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToGroup("gpost", "gpost-t", now + 20,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN,
                            "标题", "正文", List.of()));
            boundary = Files.size(file);
            world.advanceTo(now + 20);
            assertTrue(Files.size(file) > boundary + 20);
        }
        truncateMidFrame(file, boundary);
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            assertTrue(world.groupsRun().state().posts().isEmpty(),
                    "完成帧未确认时不得留下帖子元数据");
            assertTrue(world.snapshot().pending().stream()
                    .anyMatch(task -> task.taskId().endsWith("gpost-t")), "完成帧待办必须保留");
            world.advanceTo(world.snapshot().timeMillis() + 20);
            assertEquals(GroupModel.PostStatus.QUEUED,
                    world.groupsRun().state().posts().get(0).status(), "重做后只排队一次");
            assertEquals(1, world.groupsRun().state().posts().size());
        }
    }

    @Test
    void truncatedPublishFrameKeepsDocumentUnpublishedOnce() throws Exception {
        Path file = journal("publish");
        long boundary;
        long publishAt;
        String docId;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            long now = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToGroup("gpost", "gpost-t", now + 20,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN,
                            "标题", "正文", List.of()));
            world.advanceTo(now + 20);
            GroupModel.Post post = world.groupsRun().state().posts().get(0);
            publishAt = post.publishAt();
            docId = post.docId();
            boundary = Files.size(file);
            world.advanceTo(publishAt);
            assertTrue(Files.size(file) > boundary + 20);
        }
        truncateMidFrame(file, boundary);
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            assertEquals(GroupModel.PostStatus.QUEUED,
                    world.groupsRun().state().posts().get(0).status(), "投递帧未确认不得发布");
            assertTrue(world.snapshot().information().orElseThrow().information().documents()
                    .stream().noneMatch(doc -> doc.docId().equals(docId)),
                    "未确认投递不得生成正文");
            world.advanceTo(publishAt);
            GroupModel.Post published = world.groupsRun().state().posts().get(0);
            assertEquals(GroupModel.PostStatus.PUBLISHED, published.status());
            assertEquals(1, world.snapshot().information().orElseThrow().information()
                    .documents().stream().filter(doc -> doc.docId().equals(docId)).count(),
                    "重做后只生成一份正文");
        }
    }

    @Test
    void truncatedDecisionExposureFrameWritesNeitherDecisionNorFrame() throws Exception {
        Path file = journal("exposure");
        long boundary;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            // 先发布一条平台帖，供目录
            long now = world.snapshot().timeMillis();
            world.openRoleSession("alice").postToPlatform("ppost", "ppost-t", now + 15,
                    new WorldRoleSession.PlatformPostDraft("公告", "正文", List.of()));
            world.advanceTo(now + 15);
            world.advanceTo(world.snapshot().timeMillis() + 5);
            world.beginDecisionSession("open-x");
            boundary = Files.size(file);
            world.openDecisions("d-x", List.of(new DecisionRun.Request("bob", "r-x", "wake",
                    new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20,
                            1_000_000, 1_024, 1))));
            assertEquals(1, world.groupsRun().publication().frames().size());
        }
        truncateMidFrame(file, boundary);
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            assertTrue(world.snapshot().decisions().isEmpty()
                            || DecisionModel.decision(world.snapshot().decisions().orElseThrow()
                            .state(), "r-x").isEmpty(), "未确认决定批次不得半应用");
            assertEquals(0, world.groupsRun().publication().frames().size(),
                    "曝光帧与决定同批，不得单独出现");
            // 同命令重新执行：一次决定 + 一帧曝光（会话门需在新进程重新接续）
            assertThrows(IllegalStateException.class, () -> world.openDecisions("d-x",
                    List.of(new DecisionRun.Request("bob", "r-x", "wake",
                            new DecisionModel.Route("governance", "key-1", "deepseek-flash",
                                    1L, 20, 1_000_000, 1_024, 1)))),
                    "未接续会话时不得开决定");
            world.beginDecisionSession("open-y");
            world.openDecisions("d-x", List.of(new DecisionRun.Request("bob", "r-x", "wake",
                    new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20,
                            1_000_000, 1_024, 1))));
            assertEquals(1, world.groupsRun().publication().frames().size(),
                    "重做后恰好一帧");
            assertTrue(DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),
                    "r-x").isPresent(), "重做后决定存在");
        }
    }
}
