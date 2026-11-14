package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.WorldRuntime;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 真实提交异常探针：沿现有 {@code WorldJournal} 注入写/force 失败，检查
 * ①失败后内存三态仍是提交前；②重开档案不出现半应用。
 *
 * <p>只在本进程内用反射替换日志信道，生产代码不含失败注入开关。</p>
 */
public final class CommitFailureProbe {

    private CommitFailureProbe() {
    }

    /**
     * 入口。
     *
     * @param args 模式（WRITE/FORCE）与运行目录
     * @throws Exception 打开/注入失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法：<WRITE|FORCE> <dir>");
        }
        FailingChannel.Mode mode = FailingChannel.Mode.valueOf(args[0]);
        Path dir = Path.of(args[1]);
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        BankWorldFixture.Digests before;
        BankWorldFixture.Digests afterFailure;
        String failure = "NONE";
        try (WorldRuntime runtime = BankWorldFixture.open(file)) {
            before = BankWorldFixture.digests(runtime);
            inject(runtime, mode);
            try {
                runtime.scheduleTransfer("cmd:fail", "task:fail", 60_000L, "acme-owner",
                        "acme-cash", "firm-cash", new java.math.BigDecimal("10.00"), 0L);
            } catch (IOException expected) {
                failure = expected.getMessage();
            }
            afterFailure = BankWorldFixture.digests(runtime);
        }
        BankWorldFixture.Digests reopened;
        try (WorldRuntime runtime = BankWorldFixture.open(file)) {
            reopened = BankWorldFixture.digests(runtime);
        }
        System.out.println("FAILURE=" + failure);
        System.out.println("BEFORE=" + before);
        System.out.println("AFTER=" + afterFailure);
        System.out.println("REOPENED=" + reopened);
        boolean memoryIntact = before.equals(afterFailure);
        boolean bankIntact = before.bank().equals(reopened.bank())
                && before.audit().equals(reopened.audit());
        boolean noHalfApply = reopened.position() == before.position()
                || reopened.position() == before.position() + 1;
        System.out.println("MEMORY_INTACT=" + memoryIntact);
        System.out.println("BANK_INTACT=" + bankIntact);
        System.out.println("NO_HALF_APPLY=" + noHalfApply);
        if (!"NONE".equals(failure) && memoryIntact && bankIntact && noHalfApply) {
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
