package edu.cufe.auction.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 运行可读的已确认提交事实源：位置升序、位置唯一、不重叠。
 *
 * <p>普通运行与今日逐字节等价（就是自己那条日志）；独立分支子档则是
 * "父运行已验前缀 {@code [1..p]} + 子档自有帧 {@code [p+1..]}的只读合并视图"。
 * 财务链（资本流、分红报告、逐帧估值）、命令去重、审计、观察帧都改读同一处，
 * 避免"只修资本流"式的半修：同一笔现金只在它的确认位置出现一次，既不丢也不双计。</p>
 */
interface CommitSource {

    /** @return 合并后的不可变批次列表，按位置升序 */
    List<WorldCommit> commits();

    /** @param position 确认位置 @return 该位置批次；不存在时为空 */
    Optional<WorldCommit> commitAt(long position);

    /**
     * @param afterPosition 严格大于该位置
     * @param limit         最多条数，必须为正
     * @return 一页批次
     */
    List<WorldCommit> commitsAfter(long afterPosition, int limit);
}

/**
 * 自有日志事实源：普通运行的唯一实现，行为与直接读 {@link WorldJournal} 完全一致。
 */
final class JournalCommitSource implements CommitSource {

    private final WorldJournal journal;

    /**
     * @param journal 本运行持有的日志
     */
    JournalCommitSource(WorldJournal journal) {
        this.journal = journal;
    }

    @Override
    public List<WorldCommit> commits() {
        return journal.commits();
    }

    @Override
    public Optional<WorldCommit> commitAt(long position) {
        return journal.commitAt(position);
    }

    @Override
    public List<WorldCommit> commitsAfter(long afterPosition, int limit) {
        return journal.commitsAfter(afterPosition, limit);
    }
}

/**
 * 固定列表事实源：只用于父前缀验证期，数据就是扫描器刚读出的那一份列表。
 *
 * <p>与恢复期用同一批 {@code WorldCommit} 核对，不重读文件、不构造第二份扫描结果。</p>
 */
final class ListCommitSource implements CommitSource {

    private final List<WorldCommit> frames;

    /**
     * @param frames 已按位置升序的完整帧
     */
    ListCommitSource(List<WorldCommit> frames) {
        List<WorldCommit> copy = List.copyOf(frames);
        requireContinuous(copy, "验证前缀");
        this.frames = copy;
    }

    @Override
    public List<WorldCommit> commits() {
        return frames;
    }

    @Override
    public Optional<WorldCommit> commitAt(long position) {
        int index = (int) (position - 1L);
        if (index < 0 || index >= frames.size()) {
            return Optional.empty();
        }
        WorldCommit candidate = frames.get(index);
        return candidate.position() == position ? Optional.of(candidate) : Optional.empty();
    }

    @Override
    public List<WorldCommit> commitsAfter(long afterPosition, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("分页条数必须为正：" + limit);
        }
        List<WorldCommit> page = new ArrayList<>();
        for (WorldCommit commit : frames) {
            if (commit.position() > afterPosition && page.size() < limit) {
                page.add(commit);
            }
        }
        return List.copyOf(page);
    }

    private static void requireContinuous(List<WorldCommit> frames, String owner) {
        long expected = 1L;
        for (WorldCommit commit : frames) {
            if (commit.position() != expected) {
                throw new IllegalArgumentException(owner + "位置不连续：期望 " + expected
                        + "，实际 " + commit.position());
            }
            expected++;
        }
    }
}

/**
 * 分支子档事实源：父前缀只读 + 子档自有日志，两段位置不重叠、中间无缺口。
 */
final class BranchCommitSource implements CommitSource {

    private final List<WorldCommit> prefix;
    private final WorldJournal journal;

    /**
     * @param prefix 父运行 {@code [1..p]} 已逐帧验证的批次
     * @param journal 子档自有日志，首帧位置必须是 {@code p+1}
     */
    BranchCommitSource(List<WorldCommit> prefix, WorldJournal journal) {
        this.prefix = List.copyOf(prefix);
        this.journal = journal;
        List<WorldCommit> own = journal.commits();
        long base = journal.base();
        if (this.prefix.isEmpty()) {
            throw new IllegalArgumentException("分支父前缀不能为空");
        }
        long lastPrefix = this.prefix.get(this.prefix.size() - 1).position();
        if (base != lastPrefix) {
            throw new IllegalArgumentException("分支日志基准与父前缀末位不一致：" + base
                    + "/" + lastPrefix);
        }
        if (!own.isEmpty() && own.get(0).position() != base + 1L) {
            throw new IllegalArgumentException("子档首帧位置必须等于父位置加一："
                    + own.get(0).position());
        }
        long expected = 1L;
        for (WorldCommit commit : this.prefix) {
            if (commit.position() != expected) {
                throw new IllegalArgumentException("分支父前缀位置不连续：期望 " + expected
                        + "，实际 " + commit.position());
            }
            expected++;
        }
    }

    @Override
    public List<WorldCommit> commits() {
        List<WorldCommit> own = journal.commits();
        List<WorldCommit> merged = new ArrayList<>(prefix.size() + own.size());
        merged.addAll(prefix);
        merged.addAll(own);
        return List.copyOf(merged);
    }

    @Override
    public Optional<WorldCommit> commitAt(long position) {
        if (position >= 1 && position <= prefix.size()) {
            WorldCommit candidate = prefix.get((int) (position - 1));
            return candidate.position() == position ? Optional.of(candidate) : Optional.empty();
        }
        return journal.commitAt(position);
    }

    @Override
    public List<WorldCommit> commitsAfter(long afterPosition, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("分页条数必须为正：" + limit);
        }
        List<WorldCommit> page = new ArrayList<>();
        for (WorldCommit commit : prefix) {
            if (commit.position() > afterPosition && page.size() < limit) {
                page.add(commit);
            }
        }
        if (page.size() < limit) {
            page.addAll(journal.commitsAfter(afterPosition, limit - page.size()));
        }
        return List.copyOf(page);
    }
}
