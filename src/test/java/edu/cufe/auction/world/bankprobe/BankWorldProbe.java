package edu.cufe.auction.world.bankprobe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.WorldRuntime;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;

/**
 * 跨 JVM 探针：在独立进程里打开/恢复银行档案并做一笔既有世界动作，打印摘要供父进程比对。
 *
 * <p>只走公开 API；每个阶段都有自己的退出码，父进程按退出码与摘要判定。</p>
 */
public final class BankWorldProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BankWorldProbe() {
    }

    /**
     * 入口。
     *
     * @param args 第一个参数为阶段（open/resume/verify），第二个为运行目录
     * @throws Exception 任何阶段失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法：<open|resume|verify> <dir>");
        }
        System.out.println("PID=" + ProcessHandle.current().pid());
        System.out.println("PHASE=" + args[0]);
        java.nio.file.Path dir = java.nio.file.Path.of(args[1]);
        Files.createDirectories(dir);
        java.nio.file.Path file = dir.resolve("world.log");
        if ("open".equals(args[0])) {
            try (WorldRuntime runtime = BankWorldFixture.open(file)) {
                transfer(runtime, "probe-open", 60_000L);
                runtime.advanceTo(60_000L);
                print(BankWorldFixture.digests(runtime));
            }
        } else if ("resume".equals(args[0])) {
            try (WorldRuntime runtime = BankWorldFixture.open(file)) {
                print(BankWorldFixture.digests(runtime));
                transfer(runtime, "probe-resume", 120_000L);
                runtime.advanceTo(120_000L);
                System.out.println("AFTER " + summary(BankWorldFixture.digests(runtime)));
            }
        } else if ("verify".equals(args[0])) {
            try (WorldRuntime runtime = BankWorldFixture.open(file)) {
                print(BankWorldFixture.digests(runtime));
            }
        } else {
            throw new IllegalArgumentException("未知阶段：" + args[0]);
        }
    }

    private static void transfer(WorldRuntime runtime, String tag, long due) throws IOException {
        runtime.scheduleTransfer("cmd:" + tag, "task:" + tag, due, "acme-owner", "acme-cash",
                "firm-cash", new BigDecimal("10.00"), 0L);
        String receipt = runtime.scheduleTransfer("cmd:" + tag, "task:" + tag, due, "acme-owner",
                "acme-cash", "firm-cash", new BigDecimal("10.00"), 0L);
        System.out.println("SCHEDULED " + tag + " " + receipt);
    }

    private static void print(BankWorldFixture.Digests digests) {
        System.out.println(summary(digests));
    }

    private static String summary(BankWorldFixture.Digests digests) {
        return "CONFIG=" + digests.config() + " BANK=" + digests.bank() + " ACCOUNTS="
                + digests.accounts() + " CUSTODY=" + digests.custody() + " AUDIT="
                + digests.audit() + " POSITION=" + digests.position() + " TIME="
                + digests.timeMillis();
    }

    /**
     * 供自测使用的 JSON 工具（当前未使用，保留给下一步帧级篡改脚本）。
     *
     * @param json JSON 文本
     * @return 对象节点
     * @throws IOException 解析失败
     */
    static ObjectNode parse(String json) throws IOException {
        return (ObjectNode) MAPPER.readTree(json);
    }
}
