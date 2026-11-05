package edu.cufe.auction.app;

import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.WorldScheduler;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

/**
 * 世界基础模块的可重复调试夹具：模拟时间驱动转账，原命令重试不会重复付款。
 *
 * <p>不是完整股票世界或检查点恢复入口；不运行模型，不改课程 CSV。
 * 同一路径再次运行会重用已保存的业务结果。</p>
 */
public final class WorldFoundationDemo {
    private WorldFoundationDemo() {}

    /**
     * 运行固定夹具并输出可复算余额。
     * @param args 可选第一个参数为独立输出目录，默认 logs/world-foundation
     * @throws Exception 持久记录不可用或夹具不变量失败
     */
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length == 0 ? "logs/world-foundation" : args[0]);
        List<CashLedger.OpeningAccount> opening = List.of(
                new CashLedger.OpeningAccount("A", "alice", new BigDecimal("1000.00"), "实验开局历史禀赋"),
                new CashLedger.OpeningAccount("B", "bob", new BigDecimal("0.00"), "实验开局历史禀赋"));
        try (CashLedger ledger = new CashLedger(directory.resolve("cash.journal"), "foundation-v1", opening)) {
            WorldScheduler scheduler = new WorldScheduler(20261106L);
            scheduler.schedule("payment", 10, WorldScheduler.Phase.COMPLETION, () -> {
                try {
                    ledger.transfer("payment-1", "alice", "A", "B", new BigDecimal("100.00"));
                } catch (Exception failure) {
                    throw new IllegalStateException("夹具付款失败", failure);
                }
                scheduler.scheduleDelivery("notice", 0, () ->
                        System.out.println("[WorldFoundation] deliveryTime=" + scheduler.nowMillis()));
            });
            scheduler.runUntil(20);
            CashLedger.Snapshot snapshot = ledger.snapshot();
            BigDecimal a = snapshot.accounts().get("A").cash();
            BigDecimal b = snapshot.accounts().get("B").cash();
            if (a.compareTo(new BigDecimal("900.00")) != 0
                    || b.compareTo(new BigDecimal("100.00")) != 0 || snapshot.position() != 2) {
                throw new IllegalStateException("夹具余额、守恒或幂等位置错误");
            }
            System.out.printf("[WorldFoundation] time=%d position=%d A=%s B=%s total=%s%n",
                    scheduler.nowMillis(), snapshot.position(), a, b, a.add(b));
        }
    }
}
