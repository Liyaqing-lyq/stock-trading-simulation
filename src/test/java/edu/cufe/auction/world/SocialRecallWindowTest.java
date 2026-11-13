package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阅读历史、回忆资格与输入窗口的区分（联调条款 A/B）以及屏蔽归属（G2b 方向）。
 */
class SocialRecallWindowTest {

    private static final String NINA = "nina";
    private static final String OMAR = "omar";
    private static final String CHANNEL = "私信";
    private static final long SEND = WorldWebScenario.SOCIAL_SEND_MILLIS;
    private static final long READ = WorldWebScenario.SOCIAL_READ_MILLIS;

    private static WorldRuntime open(Path journal) throws Exception {
        return WorldWebScenario.openSocial(journal).runtime();
    }

    private static void advance(WorldRuntime world, long to) throws Exception {
        if (to > world.snapshot().timeMillis()) {
            world.advanceTo(to);
        }
    }

    private static void read(WorldRuntime world, String person, String docId, String tag)
            throws Exception {
        long now = world.snapshot().timeMillis();
        world.openRoleSession(person).startReading("r-" + tag, "rt-" + tag, now + READ, docId, 1L);
        advance(world, now + 2 * READ);
    }

    @Test
    void nineLettersStayReadAndContactsFollowRecallNotWindow(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("window.journal"))) {
            advance(world, 1_000L);
            read(world, NINA, WorldWebScenario.SOCIAL_CONTACT_DOC, "nina-c");
            read(world, OMAR, WorldWebScenario.SOCIAL_CONTACT_DOC, "omar-c");
            List<String> messageIds = new ArrayList<>();
            List<String> docIds = new ArrayList<>();
            for (int index = 1; index <= 9; index += 1) {
                long now = world.snapshot().timeMillis();
                world.openRoleSession(NINA).sendMessage("w-" + index, "wt-" + index, now + SEND,
                        new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "第" + index + "封",
                                "正文" + index));
                advance(world, now + SEND);
                SocialMessageModel.Message message = world.snapshot().social().orElseThrow()
                        .state().messages().get(index - 1);
                advance(world, message.dueAt());
                messageIds.add(message.messageId());
                docIds.add(message.docId());
            }
            for (int index = 0; index < 9; index += 1) {
                read(world, OMAR, docIds.get(index), "omar-m" + index);
            }
            SocialRun.SocialView view = world.socialView(OMAR);
            for (int index = 0; index < 9; index += 1) {
                assertTrue(view.read(messageIds.get(index)),
                        "9 封都真读后，已读不得因 maxItems=8 回退：" + index);
            }
            assertFalse(view.contacts().isEmpty(),
                    "联系入口仍在回忆阈内，不得因展示预算被隐藏");
            assertTrue(world.recalledSocialBody(OMAR, messageIds.get(0)).isPresent(),
                    "窗口外的第 1 封仍可回忆正文");
            assertTrue(world.recalledSocialBody(OMAR, messageIds.get(8)).isPresent());
            // 发件人与第三人不得取正文
            assertTrue(world.recalledSocialBody(NINA, messageIds.get(0)).isEmpty(),
                    "发件人不能把自己的 outbox 当收件者权限");
            assertTrue(world.recalledSocialBody("alice", messageIds.get(0)).isEmpty(),
                    "第三人不得读取他人来信正文");
            // 真遗忘：正文与地址资格取消，但已读事实保留
            advance(world, world.snapshot().timeMillis() + 120L * 86_400_000L);
            assertTrue(world.recalledSocialBody(OMAR, messageIds.get(0)).isEmpty(),
                    "真正遗忘后正文不再返回");
            assertTrue(world.socialView(OMAR).read(messageIds.get(0)),
                    "遗忘不能抹去已发生的阅读事实");
            assertTrue(world.socialView(OMAR).contacts().isEmpty(),
                    "真正遗忘后地址资格取消");
        }
    }

    @Test
    void onlyOwnerUnblockLiftsOwnPreference(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("owner.journal"))) {
            advance(world, 1_000L);
            read(world, NINA, WorldWebScenario.SOCIAL_CONTACT_DOC, "nina-c");
            read(world, OMAR, WorldWebScenario.SOCIAL_CONTACT_DOC, "omar-c");
            long now = world.snapshot().timeMillis();
            world.openRoleSession(NINA).sendMessage("o-1", "o-t1", now + SEND,
                    new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "排队信", "正文"));
            advance(world, now + SEND);
            SocialMessageModel.Message queued = world.snapshot().social().orElseThrow()
                    .state().messages().get(0);
            // omar 屏蔽 nina（正工时）
            long blockAt = world.snapshot().timeMillis();
            world.openRoleSession(OMAR).blockPerson("b-1", "b-t1", blockAt + 250L, NINA);
            advance(world, blockAt + 250L);
            assertFalse(world.snapshot().social().orElseThrow().state().blocks().isEmpty());
            advance(world, queued.dueAt());
            assertEquals(SocialMessageModel.MessageStatus.BLOCKED,
                    world.snapshot().social().orElseThrow().state().messages().get(0).status());
            // nina 试图解除 omar 的屏蔽：不得影响他人偏好
            long wrongAt = world.snapshot().timeMillis();
            world.openRoleSession(NINA).unblockPerson("u-wrong", "u-t1", wrongAt + 250L, OMAR);
            advance(world, wrongAt + 250L);
            assertFalse(world.snapshot().social().orElseThrow().state().blocks().isEmpty(),
                    "错误 owner 不得删除他人的屏蔽");
            // omar 自己解除：旧信不复活，新信可投递
            long ownAt = world.snapshot().timeMillis();
            world.openRoleSession(OMAR).unblockPerson("u-own", "u-t2", ownAt + 250L, NINA);
            advance(world, ownAt + 250L);
            assertTrue(world.snapshot().social().orElseThrow().state().blocks().isEmpty(),
                    "owner 解除后偏好清空");
            assertEquals(SocialMessageModel.MessageStatus.BLOCKED,
                    world.snapshot().social().orElseThrow().state().messages().get(0).status(),
                    "解除不复活旧信");
            long sendAt = world.snapshot().timeMillis();
            world.openRoleSession(NINA).sendMessage("o-2", "o-t2", sendAt + SEND,
                    new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "新信", "正文2"));
            advance(world, sendAt + SEND);
            SocialMessageModel.Message second = world.snapshot().social().orElseThrow()
                    .state().messages().get(1);
            advance(world, second.dueAt());
            assertEquals(SocialMessageModel.MessageStatus.DELIVERED,
                    world.snapshot().social().orElseThrow().state().messages().get(1).status());
        }
    }
}
