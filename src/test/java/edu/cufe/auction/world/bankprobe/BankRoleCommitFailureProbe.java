package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankActions;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 真实提交异常探针：沿现有 {@code WorldJournal} 注入写/force 失败，检查银行人物动作
 * ①失败后内存里的现金/银行/台账仍是提交前；②重开档案不出现半应用。
 *
 * <p>只在本进程内用反射替换日志信道；生产代码不含失败注入开关。</p>
 */
public final class BankRoleCommitFailureProbe {

    private BankRoleCommitFailureProbe() {
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
        BankRoleFixture.Digests before;
        BankRoleFixture.Digests afterFailure;
        String failure = "NONE";
        try (WorldRuntime runtime = BankRoleFixture.open(file)) {
            before = BankRoleFixture.digests(runtime);
            inject(runtime, mode);
            try {
                WorldRoleSession session = runtime.openRoleSession("acme");
                session.bankAction("fail", "fail", 60_000L, 10_000L,
                        new WorldRoleSession.BankActionDraft("fail", BankActions.Kind.DEPOSIT,
                                "north", "", BankRoleFixture.money("10.00"), 0L, 0L, "", "", "",
                                ""));
            } catch (IOException expected) {
                failure = expected.getMessage();
            }
            afterFailure = BankRoleFixture.digests(runtime);
        }
        BankRoleFixture.Digests reopened;
        int reopenedPosition;
        BigDecimal settledDeposit;
        BigDecimal depositAfterResume;
        try (WorldRuntime runtime = BankRoleFixture.open(file)) {
            reopened = BankRoleFixture.digests(runtime);
            reopenedPosition = (int) runtime.snapshot().position();
            // 先让任何已完整落盘的排程帧结算：原动作最多应用一次（300 或 310，不能是 320）。
            runtime.advanceTo(60_000L);
            settledDeposit = runtime.bankOf().orElseThrow()
                    .decode(runtime.snapshot().accounts()).state().depositOf("north", "acme");
            // 再只做一次真实动作：若重复应用，存款会多加一笔，ONCE_ONLY 即失败。
            WorldRoleSession session = runtime.openRoleSession("acme");
            session.bankAction("once", "once", 120_000L, 10_000L,
                    new WorldRoleSession.BankActionDraft("once", BankActions.Kind.DEPOSIT, "north",
                            "", BankRoleFixture.money("300.00"), 0L, 0L, "", "", "", ""));
            runtime.advanceTo(120_000L);
            depositAfterResume = runtime.bankOf().orElseThrow()
                    .decode(runtime.snapshot().accounts()).state().depositOf("north", "acme");
        }
        System.out.println("FAILURE=" + failure);
        System.out.println("BEFORE=" + before);
        System.out.println("AFTER=" + afterFailure);
        System.out.println("REOPENED=" + reopened);
        boolean memoryIntact = before.equals(afterFailure);
        boolean bankIntact = before.bank().equals(reopened.bank())
                && before.audit().equals(reopened.audit())
                && before.loans().equals(reopened.loans());
        boolean noHalfApply = reopenedPosition == before.position()
                || reopenedPosition == before.position() + 1;
        // FORCE 后帧可能已完整落盘：此时重开是**完整**的下一帧，而不是半应用。
        boolean fullNextFrame = reopenedPosition == before.position()
                || (reopenedPosition == before.position() + 1);
        boolean onceOnly = settledDeposit.compareTo(BankRoleFixture.money("300.00")) == 0
                || settledDeposit.compareTo(BankRoleFixture.money("310.00")) == 0;
        onceOnly = onceOnly && depositAfterResume.compareTo(
                settledDeposit.add(BankRoleFixture.money("300.00"))) == 0;
        System.out.println("MEMORY_INTACT=" + memoryIntact);
        System.out.println("BANK_INTACT=" + bankIntact);
        System.out.println("NO_HALF_APPLY=" + noHalfApply);
        System.out.println("FULL_NEXT_FRAME=" + fullNextFrame);
        System.out.println("ONCE_ONLY=" + onceOnly + " settled=" + settledDeposit
                + " afterResume=" + depositAfterResume);
        if (!"NONE".equals(failure) && memoryIntact && bankIntact && noHalfApply && onceOnly) {
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
