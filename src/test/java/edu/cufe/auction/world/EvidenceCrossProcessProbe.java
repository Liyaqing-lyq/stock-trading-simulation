package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;

import java.nio.file.Path;
import java.util.List;

/**
 * 真跨进程探针：两次独立 JVM 先后打开同一 schema16 日志，各自完成一次真实共享。
 *
 * <p>只经真实运行入口：读私证 → 共享 → 第二进程读共享材料 → 二次共享。用于证明恢复与
 * 跨机构绑定链不依赖单进程内存。</p>
 */
public final class EvidenceCrossProcessProbe {

    private EvidenceCrossProcessProbe() {
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
        try (WorldRuntime world = WorldWebScenario.openEvidence(journal).runtime()) {
            if ("phase1".equals(phase)) {
                RoleView.ReadingRef ref = readDocument(world, "rita",
                        WorldWebScenario.EVIDENCE_PRIVATE_DOC, 1L);
                long now = world.snapshot().timeMillis();
                world.openRoleSession("rita").shareEvidence("xp-share-1", "xp-task-1",
                        now + WorldWebScenario.EVIDENCE_SHARE_MILLIS,
                        new WorldRoleSession.MediaShareDraft(ref.docId(), ref.version(), "sam",
                                "跨进程"));
                world.advanceTo(now + WorldWebScenario.EVIDENCE_SHARE_MILLIS);
                System.out.println("PHASE1 position=" + world.snapshot().position()
                        + " shares=" + world.evidenceRun().stateOf("wire").shares().size()
                        + " evidence=" + world.snapshot().evidence().isPresent());
            } else {
                MediaEvidence.IdentityShare incoming = incoming(world);
                long now = world.snapshot().timeMillis();
                world.openRoleSession("sam").startReading("xp-read", "xp-read-task",
                        now + 5_000L, incoming.sharingDocId(), incoming.sharingVersion());
                world.advanceTo(now + 10_000L);
                now = world.snapshot().timeMillis();
                world.openRoleSession("sam").shareEvidence("xp-share-2", "xp-task-2",
                        now + WorldWebScenario.EVIDENCE_SHARE_MILLIS,
                        new WorldRoleSession.MediaShareDraft(incoming.sharingDocId(),
                                incoming.sharingVersion(), "bea", "转达"));
                world.advanceTo(now + WorldWebScenario.EVIDENCE_SHARE_MILLIS);
                System.out.println("PHASE2 position=" + world.snapshot().position()
                        + " investigative="
                        + world.evidenceRun().stateOf("investigative").shares().size()
                        + " wire=" + world.evidenceRun().stateOf("wire").shares().size());
            }
        }
        try (WorldRuntime reopened = WorldWebScenario.openEvidence(journal).runtime()) {
            System.out.println("REOPEN " + phase + " position=" + reopened.snapshot().position()
                    + " evidence=" + reopened.snapshot().evidence().isPresent()
                    + " wire=" + reopened.evidenceRun().stateOf("wire").shares().size()
                    + " investigative="
                    + reopened.evidenceRun().stateOf("investigative").shares().size());
        }
    }

    private static MediaEvidence.IdentityShare incoming(WorldRuntime world) throws Exception {
        List<MediaEvidence.IdentityShare> shares = world.evidenceRun().stateOf("wire").shares();
        if (shares.isEmpty()) {
            throw new IllegalStateException("上一进程没有留下真实共享");
        }
        return shares.get(0);
    }

    private static RoleView.ReadingRef readDocument(WorldRuntime world, String person, String docId,
                                                    long version) throws Exception {
        long now = world.snapshot().timeMillis();
        world.openRoleSession(person).startReading("cpx-read-" + person, "cpx-task-" + person,
                now + 5_000L, docId, version);
        world.advanceTo(now + 10_000L);
        for (RoleView.Experience experience : world.roleView(person).observations()) {
            if (experience.ref().docId().equals(docId)) {
                return experience.ref();
            }
        }
        throw new IllegalStateException("没有读到资料：" + docId);
    }
}
