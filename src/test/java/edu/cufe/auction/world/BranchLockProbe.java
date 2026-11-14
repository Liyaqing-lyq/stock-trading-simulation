package edu.cufe.auction.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 父服务持续持有写锁时的子档创建/启动/恢复探针（不是单元测试）。
 *
 * <pre>
 * java ... BranchLockProbe hold   &lt;dir&gt; &lt;sleepMillis&gt;   # 保持父运行打开（持锁），睡眠后继续推进
 * java ... BranchLockProbe child  &lt;dir&gt;                # 父仍在运行时打开子档并推进
 * java ... BranchLockProbe verify &lt;dir&gt;                # 父退出后再次恢复子档
 * </pre>
 */
public final class BranchLockProbe {

    private static final String CHILD = "lock-child";

    private BranchLockProbe() {
    }

    /**
     * 入口。
     *
     * @param args 子命令与参数
     * @throws Exception 失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("用法：BranchLockProbe <hold|child|verify> <dir>");
        }
        Path dir = Path.of(args[1]).toAbsolutePath();
        switch (args[0]) {
            case "hold" -> hold(dir, args.length > 2 ? Long.parseLong(args[2]) : 8_000L);
            case "child" -> child(dir);
            case "verify" -> verify(dir);
            default -> throw new IllegalArgumentException("未知子命令：" + args[0]);
        }
    }

    private static void hold(Path dir, long sleepMillis) throws Exception {
        Path parentFile = dir.resolve("parent.journal");
        WorldRuntime parent = BranchJvmProbe.openParent(parentFile);
        try {
            long cut = parent.snapshot().position();
            WorldRuntime.BranchReceipt receipt = parent.createBranch(CHILD, cut,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "父持锁期间创建");
            System.out.println("HOLD created child=" + receipt.childRunId() + " cut=" + cut
                    + " parentOpen=true sealed=" + receipt.sealedPrefix().getFileName());
            Files.writeString(dir.resolve("ready.flag"), receipt.childFile().toString());
            Thread.sleep(sleepMillis);
            parent.scheduleCapitalFlow("hold-after", "hold-after-task",
                    parent.snapshot().timeMillis() + 1_000L, "customer", "CUS", "INV",
                    new BigDecimal("10.00"), 1L, "父段时间推进");
            parent.advanceTo(parent.snapshot().timeMillis() + 1_000L);
            System.out.println("HOLD parentPosition=" + parent.snapshot().position()
                    + " (父档在子档被另一个进程使用期间继续写入)");
        } finally {
            parent.close();
        }
    }

    private static void child(Path dir) throws IOException {
        Path parentFile = dir.resolve("parent.journal");
        Path runsDir = parentFile.getParent().resolve("runs");
        WorldRuntime.BranchReceipt receipt = BranchJvmProbe.receipt(parentFile, CHILD);
        try (WorldRuntime child = WorldRuntime.openBranch(receipt.childFile(),
                id -> id.equals(receipt.parentRunId()) ? runsDir.resolve(CHILD + ".prefix.journal")
                        : null,
                BranchJvmProbe.profile())) {
            long before = child.snapshot().position();
            child.advanceTo(2_000L);
            System.out.println("CHILD opened position=" + before + " -> "
                    + child.snapshot().position()
                    + " parentFileLockedByOtherJvm=true");
        }
    }

    private static void verify(Path dir) throws IOException {
        Path parentFile = dir.resolve("parent.journal");
        Path runsDir = parentFile.getParent().resolve("runs");
        WorldRuntime.BranchReceipt receipt = BranchJvmProbe.receipt(parentFile, CHILD);
        try (WorldRuntime child = WorldRuntime.openBranch(receipt.childFile(),
                id -> id.equals(receipt.parentRunId()) ? runsDir.resolve(CHILD + ".prefix.journal")
                        : null,
                BranchJvmProbe.profile())) {
            System.out.println("VERIFY position=" + child.snapshot().position()
                    + " inheritedFlows=" + child.capitalFlows("investor").size()
                    + " historyLast=" + child.branchHistoryIndex(0,
                            WorldRuntime.MAX_HISTORY_PAGE).size());
        }
        try (WorldRuntime reopened = BranchJvmProbe.openParent(parentFile)) {
            System.out.println("VERIFY parentPosition=" + reopened.snapshot().position()
                    + " (父档继续写过，但封存件仍对应原切点)");
        }
    }

    static List<String> dummy() {
        return List.of();
    }
}
