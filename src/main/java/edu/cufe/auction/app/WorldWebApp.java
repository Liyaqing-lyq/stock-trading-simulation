package edu.cufe.auction.app;

import edu.cufe.auction.web.KeySlots;
import edu.cufe.auction.web.SceneRoutes;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.web.WorldWebServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;

/**
 * 本机世界工作台入口：唯一 writer 世界 + 受限回环 HTTP。
 *
 * <p>启动顺序刻意先初始化世界再监听：工厂失败时不会留下"半活"的端口。
 * 凭据只加载到服务端内存并只用于遮罩/显式查看，控制台只打印一次性管理员引导凭据，
 * 绝不打印 API key。本类不发起任何真实模型请求。</p>
 *
 * <p>用法：{@code WorldWebApp [工作目录] [key 文件] [端口]}；默认
 * {@code logs/web-workbench}、{@code C:/Users/JXGU/api-key/deepseek/java-stock-trade.txt}、
 * 端口 18080（被占用则明确失败，不会杀掉已有服务）。</p>
 */
public final class WorldWebApp {

    /** 默认工作目录。 */
    public static final String DEFAULT_WORK_DIR = "logs/web-workbench";
    /** 默认凭据文件（用户指定的本机文件，不入库）。 */
    public static final String DEFAULT_KEY_FILE =
            "C:/Users/JXGU/api-key/deepseek/java-stock-trade.txt";
    /** 默认端口。 */
    public static final int DEFAULT_PORT = 18080;

    private WorldWebApp() {
    }

    /**
     * 启动并阻塞，直至进程结束或启动失败。
     *
     * @param args 工作目录 / key 文件 / 端口，均可省略
     * @throws Exception 启动失败（凭据、目录、端口、世界）
     */
    public static void main(String[] args) throws Exception {
        Path workDir = Path.of(args.length > 0 ? args[0] : DEFAULT_WORK_DIR);
        Path keyFile = Path.of(args.length > 1 ? args[1] : DEFAULT_KEY_FILE);
        int port = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_PORT;
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 0 到 65535 之间");
        }

        Files.createDirectories(workDir);

        KeySlots keys;
        try {
            keys = KeySlots.load(keyFile);
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("无法加载本机凭据文件：" + failure.getMessage());
            System.err.println("请确认文件恰好五行、每行形如 sk-...，且当前用户可读。");
            throw new IOException("Web 启动失败：无法加载本机凭据");
        }

        SceneRoutes routes = SceneRoutes.open(workDir);
        WorldWebHost host = new WorldWebHost(
                () -> WorldWebScenario.open(workDir.resolve("world.journal")));

        try {
            // 先确认世界真的能打开（失败即退出，不监听端口）
            host.adminSnapshot();
        } catch (Exception failure) {
            System.err.println("世界初始化失败，未启动 Web 服务：" + failure.getMessage());
            host.close();
            routes.close();
            throw new IOException("Web 启动失败：世界初始化失败");
        }

        String bootstrap = newBootstrap();
        WorldWebServer server = new WorldWebServer(host, keys, routes, port, bootstrap);
        try {
            server.start();
        } catch (IOException failure) {
            System.err.println("端口 " + port + " 监听失败（可能已被占用）：" + failure.getMessage());
            System.err.println("未终止任何已有进程；请换端口或先自行释放。");
            server.close();
            host.close();
            routes.close();
            throw new IOException("Web 启动失败：端口不可用");
        }

        System.out.println("本机世界工作台已启动：" + server.baseUrl());
        System.out.println("工作目录：" + workDir.toAbsolutePath().normalize());
        System.out.println("场景配置：" + workDir.resolve(SceneRoutes.FILE_NAME).toAbsolutePath()
                .normalize());
        System.out.println("本机管理员一次性引导凭据（仅本次启动有效）：" + bootstrap);
        System.out.println("提示：页面显示脚本运行表示尚未接入真实模型；未发起任何模型请求。");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            host.close();
            routes.close();
            stopped.countDown();
        }, "world-web-shutdown"));
        stopped.await();
    }

    private static String newBootstrap() {
        byte[] material = new byte[24];
        new SecureRandom().nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }
}
