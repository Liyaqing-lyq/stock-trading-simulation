package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;

import java.nio.file.Path;
import java.util.List;

/**
 * 真跨进程探针：两次独立 JVM 先后打开同一 schema17 日志，完成写信/投递与阅读/回复。
 */
public final class SocialCrossProcessProbe {

    private SocialCrossProcessProbe() {
    }

    /**
     * 入口。
     *
     * @param args {@code <journal> <phase1|phase2>}
     * @throws Exception 运行失败
     */
    public static void main(String[] args) throws Exception {
        Path journal = Path.of(args[0]);
        String phase = args[1];
        String contact = WorldWebScenario.SOCIAL_CONTACT_DOC;
        long read = WorldWebScenario.SOCIAL_READ_MILLIS;
        long send = WorldWebScenario.SOCIAL_SEND_MILLIS;
        try (WorldRuntime world = WorldWebScenario.openSocial(journal).runtime()) {
            if (world.snapshot().timeMillis() < 1_000L) {
                world.advanceTo(1_000L);
            }
            if ("phase1".equals(phase)) {
                readDocument(world, "nina", contact, 1L, read);
                long now = world.snapshot().timeMillis();
                world.openRoleSession("nina").sendMessage("xp-1", "xp-task-1", now + send,
                        new WorldRoleSession.SocialSendDraft("omar", "私信", "跨进程", "第一封"));
                world.advanceTo(now + send);
                SocialMessageModel.Message message = world.snapshot().social().orElseThrow()
                        .state().messages().get(0);
                world.advanceTo(message.dueAt());
                System.out.println("PHASE1 position=" + world.snapshot().position() + " status="
                        + world.snapshot().social().orElseThrow().state().messages().get(0)
                        .status());
            } else {
                SocialMessageModel.Message message = world.snapshot().social().orElseThrow()
                        .state().messages().get(0);
                readDocument(world, "omar", message.docId(), 1L, read);
                long now = world.snapshot().timeMillis();
                world.openRoleSession("omar").sendMessage("xp-2", "xp-task-2", now + send,
                        new WorldRoleSession.SocialSendDraft("nina", "私信", "回信", "第二封"));
                world.advanceTo(now + send);
                System.out.println("PHASE2 position=" + world.snapshot().position() + " messages="
                        + world.snapshot().social().orElseThrow().state().messages().size());
            }
        }
        try (WorldRuntime reopened = WorldWebScenario.openSocial(journal).runtime()) {
            System.out.println("REOPEN " + phase + " position=" + reopened.snapshot().position()
                    + " evidence=" + reopened.snapshot().evidence().isPresent()
                    + " social=" + reopened.snapshot().social().isPresent());
        }
    }

    private static void readDocument(WorldRuntime world, String person, String docId, long version,
                                     long read) throws Exception {
        long now = world.snapshot().timeMillis();
        world.openRoleSession(person).startReading("cpx-" + person + "-" + docId,
                "cpx-task-" + person + "-" + docId, now + read, docId, version);
        world.advanceTo(now + 2 * read);
        boolean read2 = world.roleView(person).observations().stream()
                .anyMatch(item -> item.ref().docId().equals(docId));
        if (!read2) {
            throw new IllegalStateException("没有读到资料：" + docId);
        }
    }
}
