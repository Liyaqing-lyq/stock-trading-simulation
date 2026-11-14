package edu.cufe.auction.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 可信本机运行仓储：只按运行索引解析"某一条已登记运行"的子档与封存父前缀。
 *
 * <p>为什么需要它：分支打开需要的两样东西——封存父前缀与可信画像——都不能由调用方随手给一个
 * 任意路径或自报一份画像。这里的规则是：</p>
 * <ol>
 *   <li>子档与封存件都必须落在本仓储的 {@code runsDir} 之下（父目录真实路径相等、非符号链接）；</li>
 *   <li>只接受索引里已登记的（{@code runId}、封存路径、字节数、画像摘要四元组一致）；</li>
 *   <li>画像仍由宿主提供（索引只存摘要），打开时摘要必须与索引和分支元数据同时相符。</li>
 * </ol>
 *
 * <p>本类不启动进程、不分配端口、不碰 Web；它是 B2 启动器与 B1 核心之间的可信边界。</p>
 */
public final class BranchRepository {

    private final Path runsDir;

    /**
     * @param runsDir 运行目录（父档同级的 {@code runs/}）
     */
    public BranchRepository(Path runsDir) {
        this.runsDir = runsDir.toAbsolutePath();
    }

    /** @return 运行目录绝对路径 */
    public Path runsDir() {
        return runsDir;
    }

    /**
     * 读取本仓储已登记的全部运行。
     *
     * @return 不可变条目列表
     * @throws IOException 索引不可读
     */
    public List<BranchRunIndex.Entry> runs() throws IOException {
        return BranchRunIndex.read(runsDir);
    }

    /**
     * 按运行标识查索引条目。
     *
     * @param runId 运行标识
     * @return 条目；未登记时为空
     * @throws IOException 索引不可读
     */
    public Optional<BranchRunIndex.Entry> entry(String runId) throws IOException {
        for (BranchRunIndex.Entry entry : runs()) {
            if (entry.runId().equals(runId)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * 打开一条已登记的分支子档。
     *
     * @param childRunId 子运行标识（必须已登记）
     * @param profile    宿主持有的可信画像
     * @return 已恢复的子运行
     * @throws IOException 未登记、路径越界、封存件或画像不符、派生不等
     */
    public WorldRuntime open(String childRunId, BranchProfile profile) throws IOException {
        BranchRunIndex.Entry entry = entry(childRunId).orElseThrow(
                () -> new BranchRejectedException(WorldRuntime.BRANCH_SEAL_MISSING,
                        "运行索引没有登记该子档：" + childRunId));
        Path childFile = requireInside(entry.file(), "子档");
        if (entry.sealedPrefix().isBlank()) {
            throw new BranchRejectedException(WorldRuntime.BRANCH_SEAL_MISSING,
                    "索引条目缺少封存父前缀：" + childRunId);
        }
        Path seal = requireInside(entry.sealedPrefix(), "封存父前缀");
        if (!Files.isRegularFile(childFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(seal, LinkOption.NOFOLLOW_LINKS)) {
            throw new BranchRejectedException(WorldRuntime.BRANCH_SEAL_MISSING,
                    "索引登记的文件不存在或不是普通文件：" + childRunId);
        }
        return WorldRuntime.openBranch(childFile,
                parentRunId -> parentRunId.equals(entry.parentRunId()) ? seal : null,
                profile, entry.profileDigest());
    }

    /**
     * 校验路径确实在本仓储运行目录之下。
     *
     * @param raw 索引里的绝对路径
     * @param what 说明，只用于报错
     * @return 规范化的绝对路径
     * @throws IOException 越界
     */
    private Path requireInside(String raw, String what) throws IOException {
        Path dir = runsDir.toRealPath();
        Path candidate = Path.of(raw).toAbsolutePath().normalize();
        Path parent = candidate.getParent();
        if (parent == null || !parent.toRealPath().equals(dir)) {
            throw new BranchRejectedException(WorldRuntime.BRANCH_IDENTITY_INVALID,
                    what + "路径不在可信运行目录内：" + candidate);
        }
        return candidate;
    }
}
