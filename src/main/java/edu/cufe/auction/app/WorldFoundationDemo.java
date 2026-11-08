package edu.cufe.auction.app;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 世界现金子集的可恢复调试入口：模拟时间驱动转账，重开进程不重付。
 *
 * <p>用法：{@code WorldFoundationDemo <目录> [schedule|full]}。</p>
 * <ul>
 *   <li>{@code schedule}：只排程到期转账并落盘，随后退出（待办留在日志里）；</li>
 *   <li>{@code full}（默认）：排程（同命令重发返回原回报）→ 推进到 20 毫秒 → 打印余额与位置。</li>
 * </ul>
 *
 * <p>同一目录第二次运行 {@code full} 必须得到相同余额与位置，用于验证“恢复不重付”。
 * 本入口只覆盖现金子集，不运行模型、不写课程 CSV、不冒充完整世界或检查点。</p>
 */
public final class WorldFoundationDemo {

    private static final String RUN_ID = "foundation-v1";
    private static final long SEED = 20261106L;

    private WorldFoundationDemo() {
    }

    /**
     * 运行固定夹具并输出可复算余额。
     *
     * @param args 可选：第一个参数为独立输出目录，第二个为 {@code schedule} 或 {@code full}
     * @throws Exception 持久记录不可用或夹具不变量失败
     */
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length == 0 ? "logs/world-foundation" : args[0]);
        String mode = args.length > 1 ? args[1] : "full";

        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        RunManifest manifest = new RunManifest(RUN_ID, SEED, RunManifest.RULES_CASH_WORLD_V1,
                modules, Map.of(), RunManifest.MODEL_NONE);
        List<OpeningAccount> opening = List.of(
                new OpeningAccount("A", "alice", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("B", "bob", new BigDecimal("0.00"), "实验开局历史禀赋"));

        try (WorldRuntime runtime = new WorldRuntime(directory.resolve("cash-world.journal"),
                manifest, opening)) {
            String outcome = runtime.scheduleTransfer("payment-1", "payment-task", 10,
                    "alice", "A", "B", new BigDecimal("100.00"), 0);
            System.out.printf("[WorldFoundation] mode=%s outcome=%s time=%d pending=%d%n",
                    mode, outcome, runtime.snapshot().timeMillis(),
                    runtime.snapshot().pending().size());
            if ("schedule".equals(mode)) {
                return;
            }

            runtime.advanceTo(20);
            WorldRuntime.Snapshot snapshot = runtime.snapshot();
            BigDecimal a = snapshot.accounts().get("A").cash();
            BigDecimal b = snapshot.accounts().get("B").cash();
            if (a.compareTo(new BigDecimal("900.00")) != 0
                    || b.compareTo(new BigDecimal("100.00")) != 0
                    || a.add(b).compareTo(new BigDecimal("1000.00")) != 0
                    || snapshot.pending().size() != 0
                    || snapshot.delivered().size() != 1) {
                throw new IllegalStateException("夹具余额、守恒、待办或回报数量错误");
            }
            System.out.printf(
                    "[WorldFoundation] time=%d position=%d A=%s B=%s total=%s delivered=%d%n",
                    snapshot.timeMillis(), snapshot.position(), a, b, a.add(b),
                    snapshot.delivered().size());
        }
    }
}
