package edu.cufe.auction.world.bankprobe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧 B7b 独立探针的薄 JUnit 守卫：每个探针在<b>有界独立子进程</b>里重跑，输出重定向到临时文件，
 * 先 {@code waitFor(timeout)} 再读文件；超时只杀本进程，不把“读输出阻塞”当成失败。
 *
 * <p>断言只认探针自己的 PASS/FAIL/exit，不重新发明业务断言，也不虚增计数；真实条数由探针输出
 * 透传在失败消息里。</p>
 */
class BankCustodyProbeGuardTest {

    private static final Pattern SUMMARY = Pattern.compile("PASS=(\\d+) FAIL=(\\d+)");
    private static final long TIMEOUT_SECONDS = 180L;

    @Test
    void coreSelfTestIsGreen(@TempDir Path dir) throws Exception {
        String out = run(dir, "BankCustodyCoreSelfTest");
        assertGreen(out, "BankCustodyCoreSelfTest");
    }

    @Test
    void worldSelfTestIsGreen(@TempDir Path dir) throws Exception {
        String out = run(dir, "BankCustodyWorldSelfTest");
        assertGreen(out, "BankCustodyWorldSelfTest");
    }

    @Test
    void portFreezeProbeIsGreen(@TempDir Path dir) throws Exception {
        String out = run(dir, "BankCustodyPortFreezeProbe");
        assertGreen(out, "BankCustodyPortFreezeProbe");
    }

    @Test
    void scheduleTamperProbeIsGreen(@TempDir Path dir) throws Exception {
        String out = run(dir, "BankCustodyScheduleTamperProbe", dir.resolve("tamper").toString());
        assertGreen(out, "BankCustodyScheduleTamperProbe");
    }

    @Test
    void frameAttacksAreRejectedAndControlOpens(@TempDir Path dir) throws Exception {
        Path attack = dir.resolve("attack");
        run(dir, "BankCustodyAttack", "build", attack.toString());
        String out = run(dir, "BankCustodyAttack", "reopen",
                attack.resolve("control.log").toString(),
                attack.resolve("attack-custody.log").toString(),
                attack.resolve("attack-trade.log").toString(),
                attack.resolve("attack-report.log").toString());
        assertTrue(out.contains("control.log => OPENED"), "control 必须可开：" + out);
        for (String name : List.of("attack-custody", "attack-trade", "attack-report")) {
            assertTrue(out.contains(name + ".log => REJECTED"),
                    "篡改帧必须被拒：" + name + "\n" + out);
        }
    }

    @Test
    void writeAndForceFailuresAreAtomic(@TempDir Path dir) throws Exception {
        for (String mode : List.of("WRITE", "FORCE")) {
            String out = run(dir, "BankCustodyCommitFailureProbe", mode,
                    dir.resolve("fail-" + mode).toString());
            assertTrue(out.contains("RESULT=OK"), mode + " 注入必须无半应用：" + out);
        }
    }

    @Test
    void threeJvmOpenResumeVerifyStayIdentical(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("jvm3");
        String open = run(dir, "BankCustodyWorldSelfTest", "open", journal.toString());
        String resume = run(dir, "BankCustodyWorldSelfTest", "resume", journal.toString());
        String verify = run(dir, "BankCustodyWorldSelfTest", "verify", journal.toString());
        String first = digestOf(open, "DIGEST=");
        assertEquals(first, digestOf(resume, "DIGEST="), "恢复摘要必须与开档一致");
        assertEquals(digestOf(resume, "AFTER="), digestOf(verify, "DIGEST="),
                "第三进程只读摘要必须与续写后一致");
    }

    private static String digestOf(String output, String prefix) {
        for (String line : output.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        throw new AssertionError("缺少 " + prefix + " 行：\n" + output);
    }

    private static void assertGreen(String output, String probe) {
        Matcher matcher = SUMMARY.matcher(output);
        assertTrue(matcher.find(), probe + " 没有 PASS/FAIL 汇总：\n" + output);
        int pass = Integer.parseInt(matcher.group(1));
        int fail = Integer.parseInt(matcher.group(2));
        assertEquals(0, fail, probe + " 出现失败：PASS=" + pass + " FAIL=" + fail + "\n" + output);
        assertTrue(pass > 0, probe + " 没有通过项：\n" + output);
    }

    private static String run(Path dir, String simpleName, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("edu.cufe.auction.world.bankprobe." + simpleName);
        command.addAll(List.of(args));
        Path log = Files.createTempFile(dir, simpleName + "-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError(simpleName + " 超时 " + TIMEOUT_SECONDS + " 秒，日志：" + log);
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), simpleName + " 退出码非 0：\n" + output);
        return output;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
