package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankRun;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * B7b 真实提交异常探针：沿现有 {@code WorldJournal} 注入写/force 失败，检查
 * ①失败后内存里的托管明细/银行/现金/订单/持仓仍是提交前；②重开档案不出现半应用的托管成交；
 * ③恢复后重新推进，同一成交可正常落账（证明失败只是该批次未提交，不是状态损坏）。
 *
 * <p>只在本进程内用反射替换日志信道；生产代码不含失败注入开关。</p>
 */
public final class BankCustodyCommitFailureProbe {

    private static final long T = BankCustodyFixture.T0;
    private static final String SYM = BankCustodyFixture.SYMBOL;
    private static final String CST = BankCustodyFixture.CST;

    private BankCustodyCommitFailureProbe() {
    }

    /**
     * 入口。
     *
     * @param args 模式（WRITE/FORCE）与运行目录
     * @throws Exception 打开或注入失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法：<WRITE|FORCE> <dir>");
        }
        FailingChannel.Mode mode = FailingChannel.Mode.valueOf(args[0]);
        Path dir = Path.of(args[1]);
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        BankCustodyFixture.Digests before;
        BankCustodyFixture.Digests afterFailure;
        String failure = "NONE";
        try (WorldRuntime runtime = BankCustodyFixture.open(file)) {
            runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash", SYM,
                    Side.SELL, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 10L,
                    BankCustodyFixture.custodyToken(CST, "firm"));
            runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 10L,
                    BankCustodyFixture.custodyToken(CST, "acme"));
            before = BankCustodyFixture.digests(runtime);
            inject(runtime, mode);
            try {
                runtime.advanceTo(T + 120_000L);
            } catch (IOException expected) {
                failure = expected.getMessage();
            }
            afterFailure = BankCustodyFixture.digests(runtime);
        }
        boolean memoryIntact = before.equals(afterFailure);
        BankCustodyFixture.Digests reopened;
        boolean noHalf;
        boolean finalOk;
        String finalDetail;
        try (WorldRuntime runtime = BankCustodyFixture.open(file)) {
            reopened = BankCustodyFixture.digests(runtime);
            // 允许两种合法结果：整帧没落（== before）或那一帧完整落地（位置 +1）。
            noHalf = reopened.equals(before) || reopened.position() == before.position() + 1;
            runtime.advanceTo(T + 120_000L);
            WorldRuntime.Snapshot snap = runtime.snapshot();
            BankRun run = runtime.bankOf().orElseThrow();
            java.util.List<WorldExchange.Trade> trades = snap.exchange().orElseThrow().trades();
            BankRun.Decoded decoded = run.decode(snap.accounts());
            finalOk = trades.size() == 1
                    && run.custodyDetails().get(CST + "|acme")
                    .compareTo(BankCustodyFixture.money("500.00")) == 0
                    && run.custodyDetails().get(CST + "|firm")
                    .compareTo(BankCustodyFixture.money("500.00")) == 0
                    && decoded.state().depositOf("north", CST)
                    .compareTo(BankCustodyFixture.money("1000.00")) == 0;
            finalDetail = "trades=" + trades.size() + " details=" + run.custodyDetails()
                    + " pool=" + decoded.state().depositOf("north", CST);
        }
        System.out.println("FAILURE=" + failure);
        System.out.println("BEFORE=" + before);
        System.out.println("AFTER=" + afterFailure);
        System.out.println("REOPENED=" + reopened);
        System.out.println("MEMORY_INTACT=" + memoryIntact);
        System.out.println("NO_HALF_APPLY=" + noHalf + " FINAL=" + finalDetail);
        if (!"NONE".equals(failure) && memoryIntact && noHalf && finalOk) {
            System.out.println("RESULT=OK");
        } else {
            System.out.println("RESULT=BAD");
            System.exit(1);
        }
    }

    private static void inject(WorldRuntime runtime, FailingChannel.Mode mode) throws Exception {
        Field journalField = WorldRuntime.class.getDeclaredField("journal");
        journalField.setAccessible(true);
        Object journal = journalField.get(runtime);
        Field channelField = journal.getClass().getDeclaredField("channel");
        channelField.setAccessible(true);
        FileChannel real = (FileChannel) channelField.get(journal);
        channelField.set(journal, new FailingChannel(real, mode, 0));
    }
}
