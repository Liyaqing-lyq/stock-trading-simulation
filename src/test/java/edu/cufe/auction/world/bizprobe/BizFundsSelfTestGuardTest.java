package edu.cufe.auction.world.bizprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * {@link BizFundsSelfTest} 的薄 JUnit 守卫：断言真实失败数为 0，而不是只看退出码。
 *
 * <p>探针本身是独立 main，失败时既打印 {@code FAILED:} 明细也 {@code System.exit(1)}；
 * 这里再在子进程里跑一遍，解析它自己打印的 {@code PASS=n FAIL=m}，因此</p>
 * <ul>
 *   <li>探针尚未跑到统计行就异常退出时，解析失败会让本测试失败，而不是被当“没有失败”；</li>
 *   <li>只有退出码 0、{@code FAIL=0} 且没有任何 {@code FAILED:} 行同时成立才算通过。</li>
 * </ul>
 *
 * <p>它不重复实现业务断言，只把“作者计数”变成可被 CI 复核的断言；业务期望仍以探针为准。</p>
 */
class BizFundsSelfTestGuardTest {

    /** 已接受基线的用例计数：40 项全部真实业务链断言。 */
    private static final int EXPECTED_PASS = 40;

    private static final Pattern SUMMARY = Pattern.compile("PASS=(\\d+) FAIL=(\\d+)");

    /**
     * 跑探针并断言实际失败数为 0。
     *
     * @throws IOException          子进程无法启动或输出读取失败
     * @throws InterruptedException 等待子进程时被中断
     */
    @Test
    void probeReportsNoFailure() throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String classpath = System.getProperty("java.class.path");
        Process process = new ProcessBuilder(List.of(java.toString(), "-cp", classpath,
                BizFundsSelfTest.class.getName()))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "探针 180 秒内未结束");
        int exit = process.exitValue();

        Matcher summary = SUMMARY.matcher(output);
        assertTrue(summary.find(), "探针没有打印 PASS/FAIL 统计行，输出尾部："
                + tail(output));
        int passed = Integer.parseInt(summary.group(1));
        int failed = Integer.parseInt(summary.group(2));
        assertTrue(!output.contains("FAILED:"), "探针报告了失败明细：\n" + tail(output));
        assertEquals(0, failed, "探针实际失败数必须为 0：\n" + tail(output));
        assertEquals(EXPECTED_PASS, passed, "通过的断言数偏离已接受基线：\n" + tail(output));
        assertEquals(0, exit, "探针退出码必须为 0：\n" + tail(output));
    }

    /** 取输出尾部便于失败时定位（避免把上万字全塞进断言消息）。 */
    private static String tail(String output) {
        return output.length() <= 4000 ? output : output.substring(output.length() - 4000);
    }
}
