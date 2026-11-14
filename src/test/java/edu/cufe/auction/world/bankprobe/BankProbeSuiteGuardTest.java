package edu.cufe.auction.world.bankprobe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 已接受银行三档自检的薄 JUnit 守卫：让正式 CI 也能复跑作者外部 main，而不只在本机手工跑。
 *
 * <p>三条自检（world / roles / funds）各自是独立 Java 进程，输出重定向到临时文件、有界等待；
 * 超时只强杀本进程。通过条件是实际退出码 0、唯一统计行 {@code PASS=n FAIL=m} 的 FAIL 为 0、
 * 没有 {@code FAILED:} 明细，且通过数与已接受基线一致——不是只看“进程没崩”。</p>
 *
 * <p>它不重复实现业务断言；业务期望仍在各自 main 里。计数是这三条自检的真实断言数：
 * world 25（含两条 tamper 的 clean control）、roles 62、funds 8。</p>
 */
class BankProbeSuiteGuardTest {

    private static final Pattern SUMMARY = Pattern.compile("PASS=(\\d+) FAIL=(\\d+)");
    private static final long TIMEOUT_SECONDS = 300L;

    @Test
    void acceptedBankSelfTestsPassInOwnProcesses() throws Exception {
        Path root = Files.createTempDirectory("bank-probe-guard");
        runSuite(root, "BankWorldSelfTest",
                "edu.cufe.auction.world.bankprobe.BankWorldSelfTest", 27,
                root.resolve("world-run").toString());
        runSuite(root, "BankRoleWorldSelfTest",
                "edu.cufe.auction.world.bankprobe.BankRoleWorldSelfTest", 62,
                root.resolve("roles-run").toString());
        runSuite(root, "BankFundsWorldSelfTest",
                "edu.cufe.auction.world.bankprobe.BankFundsWorldSelfTest", 8);
    }

    /**
     * 在独立 JVM 里跑一条自检并断言真实统计。
     *
     * @param root     本次测试的临时根目录
     * @param label    报告用短名
     * @param mainClass 自检主类
     * @param expected 已接受基线的通过断言数
     * @param args     传给主类的参数（运行目录；funds 自检无参）
     * @throws IOException          启动/读取失败
     * @throws InterruptedException 等待被中断
     */
    private static void runSuite(Path root, String label, String mainClass, int expected,
                                 String... args) throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(List.of(args));
        Path output = root.resolve(label + ".log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            fail(label + " 超过 " + TIMEOUT_SECONDS + " 秒未结束，已只终止本进程 pid="
                    + process.pid() + "，输出尾部：\n" + tail(output));
        }
        int exit = process.exitValue();
        String text = readText(output);

        Matcher summary = SUMMARY.matcher(text);
        assertTrue(summary.find(), label + " 没有打印 PASS/FAIL 统计行（可能在统计前异常退出），"
                + "exit=" + exit + "，输出尾部：\n" + tail(output));
        int passed = Integer.parseInt(summary.group(1));
        int failed = Integer.parseInt(summary.group(2));
        assertFalse(summary.find(), label + " 出现了多条统计行，不能确定真实计数");
        assertFalse(text.contains("FAILED:"), label + " 报告了失败明细：\n" + tail(output));
        assertEquals(0, failed, label + " 的实际失败数必须为 0：\n" + tail(output));
        assertEquals(expected, passed, label + " 的通过断言数偏离已接受基线：\n" + tail(output));
        assertEquals(0, exit, label + " 的退出码必须为 0：\n" + tail(output));
    }

    /** 失败时只带尾部，避免把整份日志塞进断言消息。 */
    private static String tail(Path output) {
        try {
            String text = readText(output);
            return text.length() <= 6000 ? text : text.substring(text.length() - 6000);
        } catch (IOException failure) {
            return "<无法读取 " + output + "：" + failure.getMessage() + ">";
        }
    }

    /**
     * 读取子进程输出：优先严格 UTF-8，遇到平台编码（Windows GBK）时回退 GBK。
     *
     * <p>子进程重定向到文件时，{@code System.out} 使用平台编码；统计行是 ASCII，但失败尾部含中文，
     * 所以不能假设必定 UTF-8，也不能因此让守卫本身崩掉。</p>
     */
    private static String readText(Path output) throws IOException {
        byte[] bytes = Files.readAllBytes(output);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }
}
