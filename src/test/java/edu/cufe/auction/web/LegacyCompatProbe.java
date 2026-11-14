package edu.cufe.auction.web;

import edu.cufe.auction.world.WorldRuntime;

import java.nio.file.Path;

/**
 * 旧二进制/新二进制互操作探针（不是单元测试）：同一份日志先由一方写入，再由另一方打开并读出。
 *
 * <p>只使用两代实现都存在的公开入口（{@code WorldWebScenario.groupsRuntime}、
 * {@code snapshot}、{@code historyIndex}、{@code beginDecisionSession}），因此同一个 class
 * 文件可以在两套 class 路径下分别运行，实现"真旧二进制读新档 / 新二进制读旧档"。</p>
 *
 * <pre>
 * java ... LegacyCompatProbe info   &lt;journal&gt;
 * java ... LegacyCompatProbe append &lt;journal&gt;
 * </pre>
 */
public final class LegacyCompatProbe {

    private LegacyCompatProbe() {
    }

    /**
     * 入口。
     *
     * @param args {@code <info|append> <journal>}
     * @throws Exception 打开失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法：LegacyCompatProbe <info|append> <journal>");
        }
        Path journal = Path.of(args[1]).toAbsolutePath();
        switch (args[0]) {
            case "info20" -> info(journal, true);
            case "append20" -> append(journal, true);
            case "info18" -> info(journal, false);
            case "append18" -> append(journal, false);
            default -> throw new IllegalArgumentException("未知子命令：" + args[0]);
        }
    }

    private static WorldRuntime open(Path journal, boolean groups) throws Exception {
        return groups ? WorldWebScenario.groupsRuntime(journal)
                : WorldWebScenario.engineeringRuntime(journal,
                        WorldWebScenario.engineeringConfig());
    }

    private static void info(Path journal, boolean groups) throws Exception {
        try (WorldRuntime runtime = open(journal, groups)) {
            WorldRuntime.Snapshot snapshot = runtime.snapshot();
            int history = runtime.historyIndex(0, WorldRuntime.MAX_HISTORY_PAGE).size();
            System.out.println("INFO position=" + snapshot.position()
                    + " time=" + snapshot.timeMillis()
                    + " accounts=" + snapshot.accounts().size()
                    + " history=" + history
                    + " pending=" + snapshot.pending().size()
                    + " decisions=" + snapshot.decisions().isPresent());
            System.out.println("INFO schema=" + runtime.checkpoint(snapshot.position()).stateJson().length()
                    + " stateJsonChars");
        }
    }

    private static void append(Path journal, boolean groups) throws Exception {
        try (WorldRuntime runtime = open(journal, groups)) {
            long before = runtime.snapshot().position();
            long now = runtime.snapshot().timeMillis();
            String receipt = runtime.scheduleTransfer("legacy-probe-cmd", "legacy-probe-task",
                    now + 1_000L, "supplier", "SUP", "OPS", new java.math.BigDecimal("1.00"), 0L);
            System.out.println("APPEND schema=" + (groups ? 20 : 18)
                    + " before=" + before + " after=" + runtime.snapshot().position()
                    + " receipt=" + receipt);
        }
    }
}
