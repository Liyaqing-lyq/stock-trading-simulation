package edu.cufe.auction.world.composed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生产托管档案必须跨真实进程恢复并继续业务，不能仅在同一 JVM 内重开。 */
class ComposedCustodyCrossJvmTest {
    @TempDir Path dir;

    @Test
    void threeProcessesRestoreTradeAndPreserveAllState() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (int step = 0; step < 3; step++) {
            Path log = dir.resolve("step-" + step + ".log");
            Process process = new ProcessBuilder(java, "-cp", cp,
                    ComposedCustodyReopenProbe.class.getName(), dir.resolve("world.journal").toString(),
                    Integer.toString(step), dir.resolve("digest-" + (step - 1)).toString(),
                    dir.resolve("digest-" + step).toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            assertTrue(finished, "独立进程超时：" + step);
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("CUSTODY_REOPEN_OK step=" + step));
        }
        assertEquals(Files.readString(dir.resolve("digest-1")),
                Files.readString(dir.resolve("digest-2")), "最后只读重开不得改变任何状态");
    }
}
