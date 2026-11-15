package edu.cufe.auction.world.composed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 独立研发公司跨进程探针：三个真实 JVM 依次「建链 → 重开续写 → 只读比对」。
 *
 * <p>用法：{@code <world.log> <build|continue|readonly> <digestIn> <digestOut>}。每个阶段都打印
 * {@code PHARMA_REOPEN_OK step=<阶段>} 作为完成声明；只读阶段还会核对文件 SHA 在重开前后不变。</p>
 */
public final class ComposedPharmaReopenProbe {

    private ComposedPharmaReopenProbe() {
    }

    /**
     * @param args 世界文件、阶段、输入摘要、输出摘要
     * @throws Exception 打开、推进或比对失败
     */
    public static void main(String[] args) throws Exception {
        Path world = Path.of(args[0]);
        String step = args[1];
        Path digestIn = Path.of(args[2]);
        Path digestOut = Path.of(args[3]);
        if ("build".equals(step)) {
            ComposedPharmaWorldTest.buildApprovedChain(world);
            try (var runtime = ComposedPharmaScenario.open(world)) {
                Map<String, String> components = FundedProbes.components(runtime);
                writeDigest(digestOut, "step0", components, FundedProbes.stateDigest(runtime));
                System.out.println("DETAIL " + String.join(" ", ComposedPharmaFaultTest
                        .describe(runtime)));
            }
            System.out.println("PHARMA_REOPEN_OK step=" + step);
            return;
        }
        Map<String, String> expected = readDigest(digestIn);
        if ("continue".equals(step)) {
            try (var runtime = ComposedPharmaScenario.open(world)) {
                requireMatch(expected, FundedProbes.components(runtime), step);
                runtime.advanceTo(90L * 86_400_000L);
                Map<String, String> components = FundedProbes.components(runtime);
                writeDigest(digestOut, "step1", components, FundedProbes.stateDigest(runtime));
                System.out.println("DETAIL " + String.join(" ", ComposedPharmaFaultTest
                        .describe(runtime)));
            }
            System.out.println("PHARMA_REOPEN_OK step=" + step);
            return;
        }
        if ("readonly".equals(step)) {
            String before = sha256(world);
            try (var runtime = ComposedPharmaScenario.open(world)) {
                requireMatch(expected, FundedProbes.components(runtime), step);
                System.out.println("DETAIL " + String.join(" ", ComposedPharmaFaultTest
                        .describe(runtime)));
            }
            String after = sha256(world);
            if (!before.equals(after)) {
                System.out.println("RESULT=BAD（只读重开改变了世界字节）");
                System.exit(1);
            }
            writeDigest(digestOut, "step2", expected, before);
            System.out.println("PHARMA_REOPEN_OK step=" + step);
            return;
        }
        throw new IllegalArgumentException("未知阶段：" + step);
    }

    private static void requireMatch(Map<String, String> expected, Map<String, String> actual,
            String step) {
        for (String key : expected.keySet()) {
            if (!expected.get(key).equals(actual.get(key))) {
                System.out.println("RESULT=BAD（" + step + " 分量不一致：" + key + "）");
                System.exit(1);
            }
        }
    }

    private static void writeDigest(Path file, String tag, Map<String, String> components,
            String fullDigest) throws Exception {
        StringBuilder builder = new StringBuilder();
        builder.append(tag).append(':').append(fullDigest).append('\n');
        for (Map.Entry<String, String> entry
                : new java.util.TreeMap<>(components).entrySet()) {
            builder.append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        }
        Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> readDigest(Path file) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || !line.contains(" ")) {
                continue;
            }
            int space = line.indexOf(' ');
            map.put(line.substring(0, space), line.substring(space + 1));
        }
        if (map.isEmpty()) {
            throw new IllegalStateException("摘要文件没有分量：" + file);
        }
        return map;
    }

    private static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(file)));
    }
}
