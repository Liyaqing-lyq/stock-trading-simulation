package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankActions;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 跨 JVM 探针：在独立进程里打开/恢复银行人物动作档案并继续真实动作，打印摘要供父进程比对。
 *
 * <p>只走公开 API（会话 + 任务推进）；每个阶段有独立退出码，父进程按退出码与摘要判定。</p>
 */
public final class BankRoleProbe {

    private BankRoleProbe() {
    }

    /**
     * 入口。
     *
     * @param args 阶段（open/resume/verify）与运行目录
     * @throws Exception 任何阶段失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法：<open|resume|verify> <dir>");
        }
        System.out.println("PID=" + ProcessHandle.current().pid());
        System.out.println("PHASE=" + args[0]);
        Path dir = Path.of(args[1]);
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        switch (args[0]) {
            case "open" -> {
                try (WorldRuntime runtime = BankRoleFixture.open(file)) {
                    deposit(runtime, "acme", "probe-open", 60_000L);
                    runtime.advanceTo(60_000L);
                    print(BankRoleFixture.digests(runtime));
                }
            }
            case "resume" -> {
                try (WorldRuntime runtime = BankRoleFixture.open(file)) {
                    print(BankRoleFixture.digests(runtime));
                    pay(runtime, "acme", "probe-resume", 120_000L);
                    runtime.advanceTo(120_000L);
                    System.out.println("AFTER " + summary(BankRoleFixture.digests(runtime)));
                }
            }
            case "verify" -> {
                try (WorldRuntime runtime = BankRoleFixture.open(file)) {
                    print(BankRoleFixture.digests(runtime));
                }
            }
            default -> throw new IllegalArgumentException("未知阶段：" + args[0]);
        }
    }

    private static void deposit(WorldRuntime runtime, String person, String tag, long due)
            throws Exception {
        WorldRoleSession session = runtime.openRoleSession(person);
        String receipt = session.bankAction(tag, tag, due, 10_000L,
                new WorldRoleSession.BankActionDraft(tag, BankActions.Kind.DEPOSIT, "north", "",
                        BankRoleFixture.money("100.00"), 0L, 0L, "", "", "", ""));
        System.out.println("SCHEDULED " + tag + " " + receipt);
    }

    private static void pay(WorldRuntime runtime, String person, String tag, long due)
            throws Exception {
        WorldRoleSession session = runtime.openRoleSession(person);
        String receipt = session.bankAction(tag, tag, due, 10_000L,
                new WorldRoleSession.BankActionDraft(tag, BankActions.Kind.PAY, "north", "",
                        BankRoleFixture.money("10.00"), 0L, 0L, "", "DEPOSIT|north|acme",
                        "DEPOSIT|north|firm", ""));
        System.out.println("SCHEDULED " + tag + " " + receipt);
    }

    private static void print(BankRoleFixture.Digests digests) {
        System.out.println(summary(digests));
    }

    private static String summary(BankRoleFixture.Digests digests) {
        return "CONFIG=" + digests.config() + " BANK=" + digests.bank() + " ACCOUNTS="
                + digests.accounts() + " CUSTODY=" + digests.custody() + " AUDIT="
                + digests.audit() + " LOANS=" + digests.loans() + " POSITION="
                + digests.position() + " TIME=" + digests.timeMillis();
    }

    /** 供脚本读取的金额常量。 */
    static final BigDecimal PROBE_AMOUNT = BankRoleFixture.money("100.00");
}
