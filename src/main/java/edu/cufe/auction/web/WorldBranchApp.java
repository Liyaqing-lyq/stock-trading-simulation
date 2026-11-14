package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.BranchRepository;
import edu.cufe.auction.world.BranchRunIndex;
import edu.cufe.auction.world.WorldRuntime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * 一条分支子运行的本机进程入口：只从父进程的 stdin 拿身份与可信画像，绝不接命令行凭据。
 *
 * <p>安全边界：</p>
 * <ul>
 *   <li>入参只有 {@code --run/--dir/--port/--keys} 这类路径与端口（由父进程的服务端配置给出），
 *       bootstrap、handoff 与画像全部走 stdin；</li>
 *   <li>打开子档只用 {@link BranchRepository}：必须是运行索引已登记的条目，路径不得越出运行目录，
 *       画像摘要与索引、分支元数据三方一致才继续；</li>
 *   <li>这是一个子档专用入口：普通开局日志会被拒绝（首帧必须是分支首帧）；</li>
 *   <li>构造世界在监听之前完成，失败即退出，不留半活端口；不打印任何凭据。</li>
 * </ul>
 *
 * <p>stdout 只输出一行 {@code BRANCH_READY runId=... port=...} 供人核对；该行不含凭据。</p>
 */
public final class WorldBranchApp {

    /** stdin 上限：画像 JSON 实测约 15KB，这里留足余量但仍严格有界。 */
    private static final int MAX_STDIN_BYTES = 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorldBranchApp() {
    }

    /**
     * 子进程入口（由 {@link RunLauncher} 启动，不面向人手工使用）。
     *
     * @param args {@code --run <runId> --dir <workDir> --port <port> --keys <keyFile>}
     * @throws Exception 任一环节不满足即退出，不启动服务
     */
    public static void main(String[] args) throws Exception {
        String runId = null;
        Path workDir = null;
        Path keyFile = null;
        int port = -1;
        for (int index = 0; index < args.length; index++) {
            String flag = args[index];
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("参数缺少取值：" + flag);
            }
            String value = args[index + 1];
            index++;
            switch (flag) {
                case "--run" -> runId = value;
                case "--dir" -> workDir = Path.of(value);
                case "--port" -> port = Integer.parseInt(value);
                case "--keys" -> keyFile = Path.of(value);
                default -> throw new IllegalArgumentException("未知参数：" + flag);
            }
        }
        if (runId == null || runId.isBlank() || workDir == null || keyFile == null) {
            throw new IllegalArgumentException("用法：--run <runId> --dir <workDir> --port <port> "
                    + "--keys <keyFile>");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口不合法：" + port);
        }
        Files.createDirectories(workDir);

        // 只读第一行配置就停：父进程写完配置后**保持管道打开**，这条管道同时是存活信号。
        BufferedReader parentPipe = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String configLine = parentPipe.readLine();
        if (configLine == null || configLine.isBlank()) {
            throw new IllegalArgumentException("stdin 在读到配置前就关闭了");
        }
        if (configLine.length() > MAX_STDIN_BYTES) {
            throw new IllegalArgumentException("stdin 配置超过上限 " + MAX_STDIN_BYTES + " 字节");
        }
        JsonNode handoff = MAPPER.readTree(configLine);
        String stdinRun = handoff.path("runId").asText("");
        if (!runId.equals(stdinRun)) {
            throw new IllegalArgumentException("stdin 运行标识与参数不一致");
        }
        String bootstrap = handoff.path("bootstrap").asText("");
        String handoffToken = handoff.path("handoff").asText("");
        String expectedDigest = handoff.path("profileDigest").asText("");
        if (bootstrap.isBlank() || handoffToken.isBlank() || expectedDigest.isBlank()) {
            throw new IllegalArgumentException("stdin 缺少引导凭据或画像摘要");
        }
        BranchProfile profile = RunLauncher.readProfile(handoff.get("profile"));
        if (!profile.digest().equals(expectedDigest)) {
            throw new IllegalStateException("可信画像往返后摘要不一致，拒绝打开子档");
        }

        BranchRepository repository = new BranchRepository(workDir.resolve("runs"));
        var found = repository.entry(runId);
        if (found.isEmpty()) {
            throw new IllegalStateException("运行索引没有登记该子档：" + runId);
        }
        BranchRunIndex.Entry entry = found.get();
        if (entry.parentRunId().isBlank()) {
            throw new IllegalStateException("目标不是分支子档：" + runId);
        }

        KeySlots keys = KeySlots.load(keyFile);
        SceneRoutes routes = SceneRoutes.open(workDir.resolve("run-state").resolve(runId));
        // 先只验一次：能开出可信子档、位置与来源正确；失败即退出，不留半活端口。
        // 注意必须关掉这一份：真正对外服务的 Runtime 只能在宿主自己的写入线程里创建，
        // 否则任何业务写入都会因“不是创建线程”被拒绝。
        try (WorldRuntime probe = repository.open(runId, profile)) {
            long position = probe.snapshot().position();
            if (position < entry.parentPosition() + 1L) {
                throw new IllegalStateException("子档位置早于首帧：" + position);
            }
        }
        final BranchRepository repo = repository;
        final BranchProfile trusted = profile;
        final String targetRun = runId;
        WorldWebHost host = new WorldWebHost(
                () -> new WorldWebHost.Opened(repo.open(targetRun, trusted), targetRun),
                routes, new LiveDecisionProvider(keys));
        WorldWebServer server = new WorldWebServer(host, keys, routes, port, bootstrap, null,
                message -> { }, runId, handoffToken);
        try {
            server.start();
        } catch (IOException bindFailure) {
            server.close();
            host.close();
            routes.close();
            throw new IOException("端口 " + port + " 监听失败（可能已被占用）", bindFailure);
        }

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            host.close();
            routes.close();
            stopped.countDown();
        }, "world-branch-shutdown"));

        // 父进程存活看门狗：父正常 stop 会关它那一端，父意外退出时由操作系统关管道。
        // 两种情况这里都读到 EOF，子进程自行关服务/宿主/路由后退出——不需要任何控制协议。
        Thread parentWatch = new Thread(() -> {
            try {
                while (parentPipe.read() >= 0) {
                    // 父进程不会再发指令；读到内容就丢弃，继续等 EOF。
                }
            } catch (IOException ignored) {
                // 管道断开与 EOF 等价
            }
            server.close();
            host.close();
            routes.close();
            stopped.countDown();
        }, "world-branch-parent-watch");
        parentWatch.setDaemon(true);
        parentWatch.start();

        System.out.println("BRANCH_READY runId=" + runId + " port=" + server.port()
                + " parentRunId=" + entry.parentRunId() + " parentPosition="
                + entry.parentPosition());
        System.out.flush();
        stopped.await();
        // 资源已关闭；显式退出，避免非守护线程把进程留在半死状态。
        System.exit(0);
    }
}
