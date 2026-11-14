package edu.cufe.auction.web;

import edu.cufe.auction.world.BranchOrigin;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * 测试夹具：一个**独立 JVM** 充当"父进程"，起父世界 + 一个真实分支子进程，然后把端口与 PID
 * 打到 stdout，等人来杀它。
 *
 * <p>用途只有一个：证明"父进程被单独终止（不是杀整棵进程树）后，子进程靠 stdin 管道 EOF
 * 自行收尾"。所以这里刻意不做任何额外清理——被杀时应当什么都不做。</p>
 *
 * <p>由 {@code WorldBranchRunHttpTest} 启动，不面向生产。参数：
 * {@code <workDir> <port> <childRunId>}；classpath 由 {@code -Dbranch.fixture.classpath} 或
 * 本进程 {@code java.class.path} 给出。</p>
 */
public final class BranchParentFixtureMain {

    private BranchParentFixtureMain() {
    }

    /**
     * 入口。
     *
     * @param args {@code <workDir> <port> <childRunId>}
     * @throws Exception 世界/子进程启动失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("用法：<workDir> <port> <childRunId>");
        }
        Path work = Path.of(args[0]).toAbsolutePath();
        int port = Integer.parseInt(args[1]);
        String childRunId = args[2];
        String classpath = System.getProperty("branch.fixture.classpath",
                System.getProperty("java.class.path"));

        Path keyFile = work.resolve("dummy-keys.txt");
        KeySlots keys = KeySlots.load(keyFile);
        SceneRoutes routes = SceneRoutes.open(work);
        Path journal = work.resolve("world-groups.journal");
        WorldWebHost host = new WorldWebHost(() -> WorldWebScenario.openGroups(journal));
        WorldWebServer server = new WorldWebServer(host, keys, routes, port, "fixture-parent", null,
                message -> { }, host.runId(), null);
        RunLauncher launcher = new RunLauncher(new RunLauncher.Config(work, work.resolve("runs"),
                keyFile, 18100, 18109, 2, 30_000L, classpath), host::branchProfile);
        server.attachLauncher(launcher);
        server.start();

        host.createBranch(childRunId, 1L, BranchOrigin.Kind.OPERATOR_BRANCH, "父进程退出夹具");
        RunLauncher.View view = launcher.start(childRunId);
        // 单行 JSON：测试只读这一行，不需要解析别的输出。
        System.out.println("FIXTURE_READY {\"parentPort\":" + server.port()
                + ",\"childPort\":" + view.port() + ",\"childPid\":" + view.pid()
                + ",\"runId\":\"" + childRunId + "\"}");
        System.out.flush();
        // 等着被杀；被杀时不跑任何清理（那正是要验证的场景）。
        new CountDownLatch(1).await();
    }
}
