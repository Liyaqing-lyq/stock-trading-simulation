package edu.cufe.auction.world.pharmaaudit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 独审第二组：新药企 Debug 档必须是<b>三个真实独立 JVM</b>依次「建链 → 恢复并完成阶段付款 →
 * 再恢复并推进下一阶段」，而不是同进程 reopen 冒充。
 *
 * <p>每个阶段断言：进程退出码 0、自己的完成声明、自身 PID 互不相同（真跨进程）；进入下一阶段
 * 之前日志文件 SHA 未变（只有读打开，没有偷偷写）；恢复后逐分量（本公司现金、非本公司现金、
 * 预算、有效经理、待办集合、研发阶段、角色输入摘要）与上一阶段一致（输入由新进程重新生成）；
 * 阶段付款只从 RND-PHARMA 出账，ENG-A 全程不变。</p>
 */
class PharmaDebugCrossJvmTest {

    @TempDir
    Path dir;

    @Test
    void threeRealJvmsBuildCompleteAndAdvanceIndependentPharma() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        Path journal = dir.resolve("world.journal");
        Path digest0 = dir.resolve("digest-0");
        Path digest1 = dir.resolve("digest-1");
        Path digest2 = dir.resolve("digest-2");
        Set<String> pids = new LinkedHashSet<>();

        pids.add(run(java, cp, journal, "build", digest0, digest0));
        String shaAfterBuild = sha256(journal);
        assertEquals(shaAfterBuild, sha256(journal), "build 之后文件不得被本测试以外改写");

        pids.add(run(java, cp, journal, "continue", digest0, digest1));
        String shaAfterContinue = sha256(journal);
        assertNotEquals(shaAfterBuild, shaAfterContinue, "完成阶段必须真实推进日志");

        pids.add(run(java, cp, journal, "finish", digest1, digest2));
        assertNotEquals(shaAfterContinue, sha256(journal), "推进下一阶段必须真实写日志");

        assertEquals("true", value(digest2, "branchParentUnchanged"));
        assertEquals("1100.00", value(digest2, "branchCash"));
        assertEquals(3, pids.size(), "三个阶段必须是三个不同 PID：" + pids);

        assertEquals("2000.00", value(digest0, "pharmaCash"), "build 后仍是历史资本");
        assertEquals("1800.00", value(digest1, "pharmaCash"), "第一阶段 200 只从本公司账户出账");
        assertEquals("1500.00", value(digest2, "pharmaCash"), "第二阶段再出 300");
        for (Path digest : List.of(digest0, digest1, digest2)) {
            assertEquals("5000.00", value(digest, "engACash"), "工程公司现金全程不变：" + digest);
            assertEquals("pharma", value(digest, "researchCompany"));
            assertEquals("true", value(digest, "managerActive"));
        }
        assertEquals("500.00", value(digest0, "contractorCash"));
        assertEquals("1000.00", value(digest2, "contractorCash"), "承包方收款只来自本公司付款");
    }

    /** 跑一个阶段进程，断言成功声明，返回它的 PID。 */
    private String run(String java, String cp, Path journal, String step, Path digestIn,
            Path digestOut) throws Exception {
        Path log = dir.resolve("step-" + step + ".log");
        Process process = new ProcessBuilder(java, "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", cp,
                PharmaDebugCrossJvmProbe.class.getName(), journal.toString(), step,
                digestIn.toString(), digestOut.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "独立进程超时：" + step);
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), step + " 进程失败：" + output);
        assertTrue(output.contains("PHARMA_JVM_OK step=" + step), "缺少完成声明：" + output);
        String pid = null;
        for (String line : output.split("\\R")) {
            if (line.startsWith("PID ")) {
                pid = line.substring(4).trim();
            }
        }
        assertTrue(pid != null, "进程必须声明自身 PID：" + output);
        return pid;
    }

    private static String value(Path digest, String key) throws Exception {
        for (String line : Files.readAllLines(digest, StandardCharsets.UTF_8)) {
            if (line.startsWith(key + " ")) {
                return line.substring(key.length() + 1);
            }
        }
        throw new AssertionError("摘要缺少分量 " + key + "：" + digest);
    }

    private static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
