package edu.cufe.auction.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 分支身份与目标路径的严格约束：只接受小写标识，并拒绝会落到同一个文件的写法。
 *
 * <p>为什么这么严：运行标识直接进文件名，而 Windows 与 macOS 常见大小写不敏感卷。
 * 若允许 {@code Run-A} 与 {@code run-a}，两个"不同"运行会落到同一个文件；若允许
 * {@code con}、{@code nul} 之类保留名，创建会失败或落到设备上；若允许路径分隔符，
 * 目标就跑到运行目录之外。因此这里是白名单而不是黑名单，且目标路径必须在
 * {@code runsDir} 的真实路径之下、不得经过符号链接。</p>
 */
final class BranchIdentity {

    /** 运行标识白名单：小写字母开头，仅小写字母、数字与连字符，长度 3..32。 */
    static final String PATTERN = "[a-z][a-z0-9-]{2,31}";

    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private BranchIdentity() {
    }

    /**
     * 校验运行标识形状；不触碰文件系统。
     *
     * @param runId 候选标识
     * @throws BranchRejectedException 形状非法或命中保留名
     */
    static void requireValidId(String runId) throws BranchRejectedException {
        if (runId == null || runId.isEmpty()) {
            throw new BranchRejectedException("BRANCH_IDENTITY_INVALID", "运行标识不能为空");
        }
        if (!runId.matches(PATTERN)) {
            throw new BranchRejectedException("BRANCH_IDENTITY_INVALID",
                    "运行标识必须匹配 " + PATTERN + "（全小写），收到 " + runId);
        }
        if (".".equals(runId) || "..".equals(runId)) {
            throw new BranchRejectedException("BRANCH_IDENTITY_INVALID", "运行标识不能是点号：" + runId);
        }
        if (runId.endsWith(".") || runId.endsWith(" ")) {
            throw new BranchRejectedException("BRANCH_IDENTITY_INVALID",
                    "运行标识不能以点或空格结尾：" + runId);
        }
        String lowered = runId.toLowerCase(Locale.ROOT);
        if (RESERVED.contains(lowered)) {
            throw new BranchRejectedException("BRANCH_IDENTITY_RESERVED",
                    "运行标识是 Windows 保留名：" + runId);
        }
        // 带扩展名形式（con.txt）在白名单下已不可能出现；这里只防"保留名 + 扩展名"的等价判定
        int dot = lowered.indexOf('.');
        if (dot > 0 && RESERVED.contains(lowered.substring(0, dot))) {
            throw new BranchRejectedException("BRANCH_IDENTITY_RESERVED",
                    "运行标识是 Windows 保留名：" + runId);
        }
    }

    /**
     * 解析可发布的子档目标文件：必须在运行目录真实路径之下，且与目录内已有条目大小写不冲突。
     *
     * @param runsDir 运行目录（不存在时创建）
     * @param runId   已校验形状的运行标识
     * @return 目标文件的规范化绝对路径
     * @throws IOException 目录不可用
     * @throws BranchRejectedException 已有同名（含大小写不同）条目
     */
    static Path requirePublishTarget(Path runsDir, String runId) throws IOException {
        requireValidId(runId);
        Path dir = runsDir.toAbsolutePath();
        Files.createDirectories(dir);
        Path real = dir.toRealPath();
        Path target = real.resolve(runId + ".journal").normalize();
        Path parent = target.getParent();
        if (parent == null || !parent.equals(real)) {
            throw new BranchRejectedException("BRANCH_IDENTITY_INVALID",
                    "目标路径越出运行目录：" + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new BranchRejectedException("BRANCH_EXISTS", "运行文件已存在：" + target);
        }
        String wanted = target.getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> clashes;
        try (Stream<Path> entries = Files.list(real)) {
            clashes = entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).equals(wanted))
                    .toList();
        }
        if (!clashes.isEmpty()) {
            throw new BranchRejectedException("BRANCH_IDENTITY_COLLISION",
                    "运行目录已有大小写不同的同名文件：" + clashes);
        }
        return target;
    }

    /**
     * 校验一条已存在文件的绝对路径确实在指定运行目录之下（用于失败清理，绝不误删父档）。
     *
     * @param runsDir 运行目录
     * @param file    待清理文件
     * @return 可以安全删除时返回 true
     */
    static boolean isInside(Path runsDir, Path file) {
        Path real = runsDir.toAbsolutePath();
        Path candidate = file.toAbsolutePath().normalize();
        return candidate.getParent() != null && candidate.getParent().equals(real.normalize());
    }
}
