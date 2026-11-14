package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 平台呈现纯核：冻结目录顺序、把"实际进入决定材料"的条目记成呈现事实。
 *
 * <p><b>呈现不等于看见</b>：本类只记录某次决定输入真的包含了哪些版本/标题，命名是"纳入决定材料"。
 * 目录可访问、HTTP 查询、God 查看都<b>不</b>写呈现；网络发送成功、标题可见也不等于读完或相信。
 * 读完仍唯一在 {@link InformationModel}，信念与行动另走既有模型。</p>
 *
 * <p><b>顺序</b>：首版只按发布时间倒序，平手用 docId 升序、version 降序，完全相同输入给完全相同
 * 输出；不做个性化、热度或作者选择。只选择 {@code at} 时点已发表且 {@code PUBLIC} 的当前版本，
 * 未来版本不会泄漏；旧版本仍在信息模型历史里，不混进当前目录。</p>
 *
 * <p><b>持久化位置</b>：呈现帧与平台状态放在同一个 {@link PublicationRun} 里（Root 裁定：由
 * PlatformRun/PublicationRun 的同一 state 持久化），不是世界状态里的裸表。同一 (actionId, attemptId,
 * inputDigest) 重试只保留一帧；同 attempt 不同 inputDigest 视为冲突。</p>
 *
 * <p><b>信任前置</b>：{@link #feed} 的候选由受信适配器从真实 {@link PlatformPostModel.State}、
 * {@link MediaModel.State} 与 {@link InformationModel.State} 构造；纯核测试用构造输入不代表 world
 * 已接线。被输入窗口截掉的条目不能传给 {@link #record}。</p>
 */
public final class PublicationRun {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private PublicationRun() {
    }

    /** 目录条目来源种类。 */
    public enum FeedKind {
        /** 平台公开帖。 */
        PLATFORM_POST,
        /** 媒体公开发布的成稿。 */
        MEDIA_STORY
    }

    /**
     * 一条目录条目；rank 从 1 开始且连续。
     *
     * @param rank             序号
     * @param docId            文档
     * @param version          版本
     * @param title            标题（该版本的真实标题）
     * @param publisherPartyId 发布者
     * @param publishedAt      发布时刻
     * @param kind             来源种类
     */
    public record FeedEntry(int rank, String docId, long version, String title,
                            String publisherPartyId, long publishedAt, FeedKind kind) {

        /** 校验字段。 */
        public FeedEntry {
            if (rank < 1) {
                throw new IllegalArgumentException("目录序号必须从 1 开始");
            }
            Objects.requireNonNull(docId, "文档标识");
            Objects.requireNonNull(title, "标题");
            Objects.requireNonNull(publisherPartyId, "发布者");
            Objects.requireNonNull(kind, "来源种类");
            if (version < 1 || publishedAt < 0) {
                throw new IllegalArgumentException("目录版本或时刻非法：" + docId);
            }
        }
    }

    /**
     * 一次呈现事实：完全对应一次决定输入里实际包含的条目。
     *
     * @param actionId    真实决定动作
     * @param attemptId   真实尝试
     * @param inputDigest 该次输入摘要（64 位小写十六进制）
     * @param observedAt  该帧观察截止时刻
     * @param entries     实际纳入的条目
     */
    public record PresentationFrame(String actionId, String attemptId, String inputDigest,
                                    long observedAt, List<FeedEntry> entries) {

        /** 校验身份、摘要与条目顺序。 */
        public PresentationFrame {
            requireText(actionId, "动作");
            requireText(attemptId, "尝试");
            if (inputDigest == null || !DIGEST.matcher(inputDigest).matches()) {
                throw new IllegalArgumentException("输入摘要必须是 64 位小写十六进制");
            }
            if (observedAt < 0) {
                throw new IllegalArgumentException("观察时刻不能为负");
            }
            entries = List.copyOf(Objects.requireNonNull(entries, "条目"));
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("空输入不得生成呈现帧");
            }
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index).rank() != index + 1) {
                    throw new IllegalArgumentException("呈现条目必须是连续前缀：rank="
                            + entries.get(index).rank());
                }
            }
        }
    }

    /**
     * 呈现写入结果。
     *
     * @param status 结果码
     * @param run    新运行状态；未写入时为原状态
     */
    public record PresentationOutcome(Status status, Run run) {

        /** 校验字段。 */
        public PresentationOutcome {
            Objects.requireNonNull(status, "结果");
            Objects.requireNonNull(run, "运行状态");
        }
    }

    /** 呈现写入结果码。 */
    public enum Status {
        /** 已记录一帧。 */
        RECORDED,
        /** 同一尝试同一输入已存在，未重复写。 */
        DUPLICATE,
        /** 同一次尝试绑定了不同输入摘要。 */
        CONFLICT,
        /** 没有条目：不写、不造热度。 */
        NO_ENTRIES,
        /** 条目不是所给目录的连续前缀（被窗口截掉或顺序被改）。 */
        NOT_PREFIX,
        /** 参数非法。 */
        INVALID
    }

    /**
     * 发布运行状态：平台帖与呈现帧同一状态持久化。
     *
     * @param platform 平台帖状态
     * @param frames   呈现帧，按 (actionId, attemptId) 唯一
     */
    public record Run(PlatformPostModel.State platform, List<PresentationFrame> frames) {

        /** 固定顺序并校验唯一性。 */
        public Run {
            Objects.requireNonNull(platform, "平台状态");
            frames = List.copyOf(Objects.requireNonNull(frames, "呈现帧"));
            Set<String> keys = new HashSet<>();
            for (PresentationFrame frame : frames) {
                if (!keys.add(frame.actionId() + '\u0000' + frame.attemptId())) {
                    throw new IllegalArgumentException("同一次尝试出现多帧呈现：" + frame.actionId());
                }
            }
        }
    }

    /**
     * 从已接受的状态构造目录：平台公开帖 + 媒体公开发布成稿，只取 at 时点已发表的当前版本。
     *
     * <p>标题、发布者与时刻都从唯一 {@link InformationModel} 的真实版本读；信息状态里找不到对应
     * PUBLIC 版本时该候选直接丢弃（宁缺不造）。群帖、直投、组织受众一律不进目录。</p>
     *
     * @param platform 平台状态
     * @param media    媒体状态
     * @param info     唯一信息状态
     * @param at       观察截止时刻
     * @param limit    上限，必须 1..200
     * @return 冻结顺序的目录
     */
    public static List<FeedEntry> feed(PlatformPostModel.State platform, MediaModel.State media,
                                       InformationModel.State info, long at, int limit) {
        Objects.requireNonNull(platform, "平台状态");
        Objects.requireNonNull(info, "信息状态");
        if (at < 0) {
            throw new IllegalArgumentException("观察时刻不能为负");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("目录上限必须在 1..200：" + limit);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (PlatformPostModel.Post post : platform.posts()) {
            if (post.status() == PlatformPostModel.PostStatus.PUBLISHED
                    && post.publishedAt() <= at) {
                addCandidate(candidates, post.docId(), post.version(), FeedKind.PLATFORM_POST, info);
            }
        }
        if (media != null) {
            for (MediaModel.Story story : media.stories()) {
                if (story.publicDocId().isEmpty()) {
                    continue;
                }
                long version = mediaVersionAt(media, story, at);
                if (version > 0) {
                    addCandidate(candidates, story.publicDocId(), version,
                            FeedKind.MEDIA_STORY, info);
                }
            }
        }
        return rank(selectCurrent(candidates, at), limit);
    }

    /**
     * 记录一次呈现事实。
     *
     * @param run             当前运行状态
     * @param actionId        真实决定动作
     * @param attemptId       真实尝试
     * @param inputDigest     该次输入摘要
     * @param observedAt      观察截止时刻
     * @param included        实际纳入的条目
     * @param expectedFeed    调用方构造该输入时使用的目录；必须与 included 前缀一致
     * @return 结果
     */
    public static PresentationOutcome record(Run run, String actionId, String attemptId,
                                             String inputDigest, long observedAt,
                                             List<FeedEntry> included,
                                             List<FeedEntry> expectedFeed) {
        Objects.requireNonNull(run, "运行状态");
        if (actionId == null || actionId.isBlank() || attemptId == null || attemptId.isBlank()
                || inputDigest == null || !DIGEST.matcher(inputDigest).matches()
                || observedAt < 0 || included == null || expectedFeed == null) {
            return new PresentationOutcome(Status.INVALID, run);
        }
        if (included.isEmpty()) {
            return new PresentationOutcome(Status.NO_ENTRIES, run);
        }
        if (included.size() > expectedFeed.size()) {
            return new PresentationOutcome(Status.NOT_PREFIX, run);
        }
        for (int index = 0; index < included.size(); index++) {
            if (!included.get(index).equals(expectedFeed.get(index))) {
                return new PresentationOutcome(Status.NOT_PREFIX, run);
            }
        }
        PresentationFrame existing = frameOf(run, actionId, attemptId);
        if (existing != null) {
            return new PresentationOutcome(existing.inputDigest().equals(inputDigest)
                    ? Status.DUPLICATE : Status.CONFLICT, run);
        }
        PresentationFrame frame = new PresentationFrame(actionId, attemptId, inputDigest,
                observedAt, included);
        List<PresentationFrame> frames = new ArrayList<>(run.frames());
        frames.add(frame);
        return new PresentationOutcome(Status.RECORDED, new Run(run.platform(), frames));
    }

    /**
     * 某文档版本被纳入决定材料的次数；God/UI 只读，不写任何记录。
     *
     * @param run     运行状态
     * @param docId   文档
     * @param version 版本
     * @return 次数
     */
    public static int projectedTo(Run run, String docId, long version) {
        Objects.requireNonNull(run, "运行状态");
        int count = 0;
        for (PresentationFrame frame : run.frames()) {
            for (FeedEntry entry : frame.entries()) {
                if (entry.docId().equals(docId) && entry.version() == version) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 冻结序列化；呈现帧与平台状态一起持久化。
     *
     * @param run 运行状态
     * @return JSON
     */
    public static String toJson(Run run) {
        Objects.requireNonNull(run, "运行状态");
        try {
            return MAPPER.writeValueAsString(run);
        } catch (Exception failure) {
            throw new IllegalStateException("无法序列化发布运行状态", failure);
        }
    }

    /**
     * 从 JSON 恢复并重新校验；只校自身一致性。
     *
     * @param json JSON
     * @return 运行状态
     */
    public static Run fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("运行状态 JSON 不能为空");
        }
        try {
            return MAPPER.readValue(json, Run.class);
        } catch (Exception failure) {
            throw new IllegalArgumentException("发布运行状态 JSON 不合法", failure);
        }
    }

    // ------------------------------------------------------------- 内部

    private record Candidate(String docId, long version, String title, String publisherPartyId,
                             long publishedAt, FeedKind kind) {
    }

    /**
     * 稿件的公开文档在 {@code at} 时点的当前已发表版本。
     *
     * <p>只看真实发布记录：{@link MediaModel.Revision} 的 PUBLISHED 行给出“该文档哪一版在何时被
     * 真正发表”。当前 story 的 {@code publicVersion} 可能已经是未来版本，不能用它反推历史；若某版
     * 尚未发表则继续用上一版，而不是把整篇藏起来。</p>
     *
     * @param media 媒体状态
     * @param story 稿件
     * @param at    观察截止时刻
     * @return 版本号；该时点尚无任何已发表版本返回 0
     */
    private static long mediaVersionAt(MediaModel.State media, MediaModel.Story story, long at) {
        long best = 0L;
        for (MediaModel.Revision revision : media.revisions()) {
            if (!revision.storyId().equals(story.storyId())
                    || revision.status() != MediaModel.Status.PUBLISHED
                    || !story.publicDocId().equals(revision.docId())
                    || revision.docVersion() < 1
                    || revision.atMillis() > at) {
                continue;
            }
            best = Math.max(best, revision.docVersion());
        }
        return best;
    }

    private static void addCandidate(List<Candidate> candidates, String docId, long version,
                                     FeedKind kind, InformationModel.State info) {
        PublicationBinding.fromInformation(info, docId, version).ifPresent(doc -> {
            if (!"PUBLIC".equals(doc.audienceKind())) {
                return;
            }
            candidates.add(new Candidate(docId, version, doc.title(), doc.publisherPartyId(),
                    doc.publishedAt(), kind));
        });
    }

    private static List<Candidate> selectCurrent(List<Candidate> candidates, long at) {
        List<Candidate> visible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.publishedAt() <= at) {
                visible.add(candidate);
            }
        }
        List<Candidate> current = new ArrayList<>();
        Set<String> docs = new HashSet<>();
        visible.sort(Comparator.comparingLong(Candidate::publishedAt).reversed()
                .thenComparing(Candidate::docId)
                .thenComparing(Comparator.comparingLong(Candidate::version).reversed()));
        for (Candidate candidate : visible) {
            if (docs.add(candidate.docId())) {
                current.add(candidate);
            }
        }
        return current;
    }

    private static List<FeedEntry> rank(List<Candidate> ordered, int limit) {
        List<FeedEntry> entries = new ArrayList<>();
        int rank = 1;
        for (Candidate candidate : ordered) {
            if (rank > limit) {
                break;
            }
            entries.add(new FeedEntry(rank, candidate.docId(), candidate.version(),
                    candidate.title(), candidate.publisherPartyId(), candidate.publishedAt(),
                    candidate.kind()));
            rank++;
        }
        return List.copyOf(entries);
    }

    private static PresentationFrame frameOf(Run run, String actionId, String attemptId) {
        for (PresentationFrame frame : run.frames()) {
            if (frame.actionId().equals(actionId) && frame.attemptId().equals(attemptId)) {
                return frame;
            }
        }
        return null;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
