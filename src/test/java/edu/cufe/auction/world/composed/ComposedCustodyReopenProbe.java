package edu.cufe.auction.world.composed;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.WorldRuntime;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

/** 独立进程探针：生产托管开局、重开后继续成交、再次重开核对完整世界摘要。 */
public final class ComposedCustodyReopenProbe {
    private ComposedCustodyReopenProbe() { }

    /**
     * 执行一个独立 JVM 阶段；任一恢复、金额或报表不一致即失败。
     * @param args 日志、阶段（0/1/2）、前一阶段摘要文件、当前摘要文件
     * @throws Exception 恢复或验收失败
     */
    public static void main(String[] args) throws Exception {
        int step = Integer.parseInt(args[1]);
        try (WorldRuntime world = ComposedCustodyScenario.open(Path.of(args[0]))) {
            if (step > 0 && !Files.readString(Path.of(args[2]))
                    .equals(ComposedBranchBusinessProbe.digest(world))) {
                throw new AssertionError("跨进程完整状态摘要改变");
            }
            if (step < 2) {
                long now = world.snapshot().timeMillis();
                long quantity = step == 0 ? 50L : 5L;
                world.scheduleOrder("sell-" + step, "sell-t-" + step, now + 1000,
                        ComposedCustodyScenario.SELLER_OWNER, "INV", "CUFE-A", Side.SELL,
                        OrderType.LIMIT, new BigDecimal("10.00"), quantity, "CUSTODY|cst|investor");
                world.scheduleOrder("buy-" + step, "buy-t-" + step, now + 2000,
                        "eng-a", "ENG-A", "CUFE-A", Side.BUY,
                        OrderType.LIMIT, new BigDecimal("10.00"), quantity, "CUSTODY|cst|eng-a");
                world.advanceTo(now + 3000);
            }
            var decoded = world.bankOf().orElseThrow().decode(world.snapshot().accounts());
            BigDecimal expected = new BigDecimal(step == 0 ? "100.00" : "50.00");
            if (decoded.book().detailOf("cst", "eng-a").compareTo(expected) != 0
                    || decoded.book().detailOf("cst", "investor").compareTo(
                            new BigDecimal("1000.00").subtract(expected)) != 0
                    || !ComposedCompanyReport.of(world, "eng-a").balanced()) {
                throw new AssertionError("真实成交资金或公司报表不一致");
            }
            Files.writeString(Path.of(args[3]), ComposedBranchBusinessProbe.digest(world));
            System.out.println("CUSTODY_REOPEN_OK step=" + step);
        }
    }
}
