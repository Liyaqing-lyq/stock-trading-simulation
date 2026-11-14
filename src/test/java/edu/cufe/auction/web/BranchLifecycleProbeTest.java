package edu.cufe.auction.web;

import edu.cufe.auction.app.WorldWebApp;
import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 独审 B2 生命周期探针：子进程在 RUNNING 后异常退出，清单是否仍报 RUNNING。 */
class BranchLifecycleProbeTest {

    @Test
    @DisplayName("RUNNING 子进程异常退出后不得继续显示 RUNNING / 不得阻止重启")
    void staleRunningAfterChildExit() throws Exception {
        Path root = Path.of("target", "branch-lifecycle-probe");
        Files.createDirectories(root);
        Path work = Files.createTempDirectory(root, "case-");
        Path journal = work.resolve("world-groups.journal");
        Path keyFile = work.resolve("dummy-keys.txt");
        List<String> keys = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            keys.add("sk-fake-" + "0".repeat(20) + i);
        }
        Files.writeString(keyFile, String.join("\n", keys), StandardCharsets.UTF_8);
        KeySlots slots = KeySlots.load(keyFile);
        SceneRoutes routes = SceneRoutes.open(work);
        WorldWebHost host = new WorldWebHost(() -> WorldWebScenario.openGroups(journal));
        try {
            host.adminSnapshot();
            WorldRuntime.BranchReceipt receipt = host.createBranch("child-alpha", 1L,
                    BranchOrigin.Kind.OPERATOR_BRANCH, "stale running probe");
            RunLauncher launcher = new RunLauncher(new RunLauncher.Config(work.toAbsolutePath(),
                    work.resolve("runs").toAbsolutePath(), keyFile.toAbsolutePath(), 18100, 18109,
                    2, 30_000L, System.getProperty("surefire.test.class.path",
                            System.getProperty("java.class.path"))), host::branchProfile);
            try {
                RunLauncher.View running = launcher.start(receipt.childRunId());
                assertEquals("RUNNING", running.status());
                long pid = running.pid();
                assertTrue(pid > 0);
                ProcessHandle handle = ProcessHandle.of(pid).orElseThrow();
                handle.destroyForcibly();
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (handle.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(50L);
                }
                assertFalse(handle.isAlive(), "测试子进程必须真的退出");
                RunLauncher.View afterExit = launcher.list().stream()
                        .filter(view -> view.runId().equals(receipt.childRunId()))
                        .findFirst().orElseThrow();
                System.out.println("[stale-running] after child exit status=" + afterExit.status()
                        + " reason=" + afterExit.reason() + " managed=" + afterExit.managed());
                assertFalse("RUNNING".equals(afterExit.status()),
                        "子进程已退出但清单仍报 RUNNING（半活假运行）");
                assertEquals("RUNNING", launcher.start(receipt.childRunId()).status(),
                        "异常退出后应能重新启动同一条运行");
            } finally {
                launcher.close();
            }
        } finally {
            host.close();
            routes.close();
        }
    }

    @Test
    @DisplayName("bank/groups20 组合 profile 不能借用普通 mode 启动")
    void bankModeIsNotAnAcceptedMode() throws Exception {
        Path root = Path.of("target", "branch-lifecycle-probe");
        Files.createDirectories(root);
        Path work = Files.createTempDirectory(root, "bank-");
        Path key = work.resolve("keys.txt");
        Files.writeString(key, String.join("\n", List.of("sk-fake-" + "0".repeat(20) + 1,
                "sk-fake-" + "0".repeat(20) + 2, "sk-fake-" + "0".repeat(20) + 3,
                "sk-fake-" + "0".repeat(20) + 4, "sk-fake-" + "0".repeat(20) + 5)),
                StandardCharsets.UTF_8);
        try {
            WorldWebApp.main(new String[]{work.toString(), key.toString(), "0", "bank"});
            throw new AssertionError("bank 模式必须 fail closed，不能借用 groups20 启动");
        } catch (IllegalArgumentException expected) {
            System.out.println("[bank-mode] rejected=" + expected.getMessage());
        }
    }
}
