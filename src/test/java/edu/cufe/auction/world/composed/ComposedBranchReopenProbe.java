package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 独立 JVM 重开探针：在另一个进程里只读打开组合档分支子档，打印位置与银行/章程配置指纹。
 *
 * <p>供 {@code ComposedBranchProfileTest} 用 ProcessBuilder 调用，验证"父档写者已关闭、
 * 另一个 JVM 仍能按 v3 画像重开子档"，而不是同一个进程内的对象自证。</p>
 */
public final class ComposedBranchReopenProbe {

    private ComposedBranchReopenProbe() {
    }

    /**
     * @param args args[0]=子档路径，args[1]=父档日志路径（用于解析封存前缀）
     * @throws Exception 打开失败
     */
    public static void main(String[] args) throws Exception {
        Path childFile = Path.of(args[0]);
        Path parentFile = Path.of(args[1]);
        Path sealedPrefix = Path.of(args[2]);
        BranchProfile profile = parentProfile(parentFile);
        try (WorldRuntime child = WorldRuntime.openBranch(childFile, id -> sealedPrefix,
                profile)) {
            StringBuilder out = new StringBuilder();
            out.append("POSITION=").append(child.snapshot().position());
            out.append(" RUN=").append(child.branchOrigin().orElseThrow().childRunId());
            out.append(" ACCOUNTS=").append(child.snapshot().accounts().size());
            out.append(" BANK=").append(child.bankOf().isPresent());
            out.append(" BANK_TOTAL=").append(total(child.snapshot().accounts()));
            out.append(" GOVERNANCE=").append(child.companyGovernance().isPresent());
            out.append(" PROFILE=").append(profile.version());
            out.append(" PROFILE_SHA=").append(profile.digest());
            System.out.println(out);
        }
    }

    /** 用与父档同一份可信画像打开父档并取 profile（只读，不持有写权时也可取）。 */
    private static BranchProfile parentProfile(Path parentFile) throws Exception {
        try (WorldRuntime parent = ComposedWorldScenario.open(parentFile)) {
            return parent.profile();
        }
    }

    private static String total(Map<String, ?> accounts) {
        return String.valueOf(accounts.size());
    }

    /** 供测试复算：给定子档路径与父档路径，返回探针输出行（不含换行）。 */
    public static String describe(Path childFile, Path parentFile) throws Exception {
        try (WorldRuntime child = WorldRuntime.openBranch(childFile, id -> parentFile,
                parentProfile(parentFile))) {
            return "POSITION=" + child.snapshot().position()
                    + " RUN=" + child.branchOrigin().orElseThrow().childRunId()
                    + " ACCOUNTS=" + child.snapshot().accounts().size()
                    + " BANK=" + child.bankOf().isPresent()
                    + " GOVERNANCE=" + child.companyGovernance().isPresent();
        }
    }

    /** @return 文件 SHA-256（十六进制），用于隔离性比较 */
    public static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }

    /** @return 目录下条目名（排序后） */
    public static List<String> entries(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
