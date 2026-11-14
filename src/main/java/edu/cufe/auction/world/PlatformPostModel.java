package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 平台公开帖纯核：真实作者、正工时、BEGIN/FINISH、版本 CAS 与拒绝结果。
 *
 * <p><b>能力边界</b>：本类不存标题、正文、阅读或经历，也不存成员表；正文与阅读仍唯一在
 * {@link InformationModel}。平台帖受众固定为 {@code PUBLIC}，任何开局登记人物都可以发帖，但不会
 * 因此获得机构身份——机构官方披露仍走既有专门授权。</p>
 *
 * <p><b>来源</b>：每版同时记录作者真实引用的 {@code declaredRefs} 与按
 * {@link GroupModel#rootsFor} 同一实现展开的 {@code originRefs}。roots 只是可复算的缓存，不是第二
 * 权威图；模型不能提交任意根，也没有任何入参接受 roots。未知谱系一律拒绝，不猜成 v1 根。</p>
 *
 * <p><b>状态</b>：纯函数式，输入状态不被修改；无 I/O、无墙钟、无随机、无 provider。时间只前进，
 * 溢出返回 {@code TIME_OVERFLOW} 且不改状态。</p>
 */
public final class PlatformPostModel {

    /** 平台帖文档保留前缀；通用发表入口不得占用。 */
    public static final String DOC_PREFIX = "PDOC-";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POST_PREFIX = "PPOST-";
    private static final long OPEN = -1L;

    private PlatformPostModel() {
    }

    /** 版本状态。 */
    public enum PostStatus {
        /** 已写完，等待计划发布。 */
        QUEUED,
        /** 已发布。 */
        PUBLISHED,
        /** 终止：完成帧不合法，或发布帧版本基准冲突。 */
        ABORTED
    }

    /** 开始结果。 */
    public enum BeginStatus {
        /** 已排程。 */
        STARTED,
        /** 作者未登记或文档不存在。 */
        NOT_AVAILABLE,
        /** 本人已有未完成动作。 */
        BUSY,
        /** 时间倒退。 */
        TIME_REVERSED,
        /** 时刻溢出。 */
        TIME_OVERFLOW,
        /** 直接来源此刻不可引用。 */
        NOT_CITABLE,
        /** 来源谱系缺失。 */
        NO_VERSION_SOURCE,
        /** 同一命令绑定了不同语义。 */
        COMMAND_CONFLICT
    }

    /** 完成结果。 */
    public enum FinishStatus {
        /** 已写完并等待发布。 */
        QUEUED,
        /** 作者已不可用，终止。 */
        ABORTED,
        /** 已发布过（重放）。 */
        PUBLISHED,
        /** 尚未到期。 */
        NOT_DUE,
        /** 尚未开始。 */
        NOT_STARTED,
        /** 状态不一致。 */
        NOT_AVAILABLE
    }

    /** 发布结果。 */
    public enum PublishStatus {
        /** 已发布。 */
        PUBLISHED,
        /** 尚未到计划时刻。 */
        NOT_DUE,
        /** 文档/版本不存在。 */
        NOT_AVAILABLE,
        /** 已终止。 */
        ABORTED,
        /** 已经发布过。 */
        ALREADY_PUBLISHED,
        /** 发布帧文档版本与基准不符：原子拒绝，不覆盖旧版本。 */
        STALE_VERSION
    }

    /**
     * 冻结配置：写帖与投递正工时。
     *
     * @param postMillis     写帖正工时
     * @param deliveryMillis 完成到发布的等待，必须为正
     */
    public record Config(long postMillis, long deliveryMillis) {

        /** 校验正时长。 */
        public Config {
            if (postMillis <= 0 || deliveryMillis <= 0) {
                throw new IllegalArgumentException("平台发帖时长必须为正");
            }
        }
    }

    /**
     * 一版平台帖元数据。
     *
     * @param docId          文档标识，按作者与命令确定性派生
     * @param version        版本号，从 1 开始连续
     * @param basedOnVersion 起草基准；首版为 0
     * @param author         作者
     * @param contentDigest  标题/正文/直接来源摘要
     * @param declaredRefs   作者真实直接引用
     * @param originRefs     可复算的规范根缓存
     * @param startedAt      开始写作时刻
     * @param finishedAt     逻辑完成时刻
     * @param publishAt      计划发布时刻
     * @param publishedAt    实际发布时刻；未发布为 -1
     * @param status         生命周期状态
     * @param commandId      命令
     */
    public record Post(String docId, long version, long basedOnVersion, String author,
                       String contentDigest, List<Ref> declaredRefs, List<Ref> originRefs,
                       long startedAt, long finishedAt, long publishAt, long publishedAt,
                       PostStatus status, String commandId) {

        /** 校验版本、来源与状态时点。 */
        public Post {
            requireText(docId, "文档标识");
            if (version < 1) {
                throw new IllegalArgumentException("平台版本必须从 1 开始：" + docId);
            }
            if (version == 1 ? basedOnVersion != 0 : basedOnVersion != version - 1) {
                throw new IllegalArgumentException("平台版本基准不连续：" + docId);
            }
            requireText(author, "作者");
            requireText(contentDigest, "内容摘要");
            requireText(commandId, "命令");
            if (declaredRefs == null) {
                throw new IllegalArgumentException("平台帖缺少直接来源字段：" + docId);
            }
            declaredRefs = canonicalRefs(declaredRefs);
            originRefs = canonicalRefs(originRefs);
            requireNonNegative(startedAt, "开始时刻");
            if (finishedAt < startedAt) {
                throw new IllegalArgumentException("完成时刻不能早于开始：" + docId);
            }
            Objects.requireNonNull(status, "平台版本状态");
            switch (status) {
                case ABORTED -> {
                    if (publishAt != finishedAt || publishedAt != OPEN) {
                        throw new IllegalArgumentException("终止版本不得安排发布：" + docId);
                    }
                }
                case QUEUED -> {
                    if (publishAt <= finishedAt || publishedAt != OPEN) {
                        throw new IllegalArgumentException("在途版本必须有未来发布时刻：" + docId);
                    }
                }
                case PUBLISHED -> {
                    if (publishAt <= finishedAt || publishedAt != publishAt) {
                        throw new IllegalArgumentException("已发布版本的发布时刻必须等于计划：" + docId);
                    }
                }
                default -> throw new IllegalStateException("未知平台版本状态：" + status);
            }
        }
    }

    /**
     * 进行中的平台动作；标题与正文不在此持久化。
     *
     * @param commandId      命令
     * @param docId          目标文档
     * @param caller         作者
     * @param digest         语义摘要
     * @param declaredRefs   直接来源
     * @param originRefs     规范根
     * @param version        本次要写的版本号
     * @param basedOnVersion 起草基准；首版为 0
     * @param startedAt      开始时刻
     * @param dueAt          完成时刻
     * @param publishAt      计划发布时刻
     */
    public record Draft(String commandId, String docId, String caller, String digest,
                        List<Ref> declaredRefs, List<Ref> originRefs, long version,
                        long basedOnVersion, long startedAt, long dueAt, long publishAt) {

        /** 校验字段与时间。 */
        public Draft {
            requireText(commandId, "命令");
            requireText(docId, "文档标识");
            requireText(caller, "作者");
            requireText(digest, "语义摘要");
            if (declaredRefs == null) {
                throw new IllegalArgumentException("平台动作缺少直接来源");
            }
            declaredRefs = canonicalRefs(declaredRefs);
            originRefs = canonicalRefs(originRefs);
            if (version < 1) {
                throw new IllegalArgumentException("平台动作版本必须从 1 开始");
            }
            requireNonNegative(startedAt, "开始时刻");
            if (dueAt <= startedAt || publishAt <= dueAt) {
                throw new IllegalArgumentException("平台动作时刻顺序非法：" + commandId);
            }
        }
    }

    /**
     * 命令记录：绑定完整语义与当前裁决。
     *
     * @param caller    作者
     * @param commandId 命令
     * @param kind      {@code POST} 或 {@code CORRECTION}
     * @param subject   文档标识
     * @param digest    语义摘要
     * @param result    当前裁决
     * @param at        首次受理时刻
     */
    public record Command(String caller, String commandId, String kind, String subject,
                          String digest, String result, long at) {

        /** 校验字段。 */
        public Command {
            requireText(caller, "作者");
            requireText(commandId, "命令");
            requireText(kind, "动作种类");
            requireText(subject, "对象");
            requireText(digest, "语义摘要");
            requireText(result, "裁决");
            requireNonNegative(at, "受理时刻");
        }
    }

    /**
     * 平台状态。
     *
     * @param persons 开局登记人物
     * @param posts   已写版本
     * @param drafts  进行中的动作
     * @param commands 命令记录
     * @param clock   本模块最后变更时刻
     */
    public record State(List<String> persons, List<Post> posts, List<Draft> drafts,
                        List<Command> commands, long clock) {

        /** 固定顺序并校验不变量。 */
        public State {
            persons = sortedUniqueText(persons, "已登记人物");
            posts = sortedCopy(posts, Comparator.comparing(Post::docId)
                    .thenComparingLong(Post::version));
            drafts = sortedCopy(drafts, Comparator.comparing(Draft::caller)
                    .thenComparing(Draft::commandId));
            commands = sortedCopy(commands, Comparator.comparing(Command::caller)
                    .thenComparing(Command::commandId));
            requireNonNegative(clock, "模块时刻");
            validate(persons, posts, drafts, commands, clock);
        }
    }

    /**
     * 受信上下文：引用资格、版本来源、真实群状态与冻结配置。
     *
     * <p>{@code groups} 只用于展开本模块直接引用的群帖谱系（与 {@link GroupModel#rootsFor} 同一实现），
     * 不是第二份成员表，也不改变任何群状态。</p>
     *
     * @param citations     引用资格
     * @param versionOrigins 已知版本来源
     * @param groups        当前群状态；null 视为无群
     * @param config        冻结配置
     */
    public record Context(GroupModel.CitationOracle citations,
                          GroupModel.VersionOrigins versionOrigins,
                          GroupModel.State groups, Config config) {

        /** 校验非空。 */
        public Context {
            Objects.requireNonNull(citations, "引用资格");
            Objects.requireNonNull(versionOrigins, "版本来源");
            Objects.requireNonNull(config, "配置");
        }
    }

    /**
     * 开始结果。
     *
     * @param status  开始状态
     * @param state   新状态
     * @param payload 被接受时的待提交负载；其他情况为 null
     */
    public record BeginOutcome(BeginStatus status, State state, PendingPost payload) {

        /** 校验字段。 */
        public BeginOutcome {
            Objects.requireNonNull(status, "开始状态");
            Objects.requireNonNull(state, "状态");
            if (status != BeginStatus.STARTED && payload != null) {
                throw new IllegalArgumentException("未开始的平台动作不得携带负载");
            }
        }
    }

    /**
     * 完成结果。
     *
     * @param status 完成状态
     * @param state  新状态
     * @param post   排队的版本；未通过为 null
     */
    public record FinishOutcome(FinishStatus status, State state, Post post) {

        /** 校验字段。 */
        public FinishOutcome {
            Objects.requireNonNull(status, "完成状态");
            Objects.requireNonNull(state, "状态");
        }
    }

    /**
     * 发布结果。
     *
     * @param status 发布状态
     * @param state  新状态
     * @param post   发布或终止后的版本；未知版本为 null
     */
    public record PublishOutcome(PublishStatus status, State state, Post post) {

        /** 校验字段。 */
        public PublishOutcome {
            Objects.requireNonNull(status, "发布状态");
            Objects.requireNonNull(state, "状态");
        }
    }

    /**
     * 待提交负载；正文只交给桥接层，不进本状态。
     *
     * @param docId        文档
     * @param version      版本
     * @param author       作者
     * @param title        标题
     * @param body         正文
     * @param declaredRefs 直接来源
     * @param originRefs   规范根
     */
    public record PendingPost(String docId, long version, String author, String title,
                              String body, List<Ref> declaredRefs, List<Ref> originRefs) {

        /** 校验字段。 */
        public PendingPost {
            requireText(docId, "文档标识");
            if (version < 1) {
                throw new IllegalArgumentException("平台负载版本必须从 1 开始");
            }
            requireText(author, "作者");
            requireText(title, "标题");
            requireText(body, "正文");
            if (declaredRefs == null) {
                throw new IllegalArgumentException("平台负载缺少直接来源");
            }
            declaredRefs = canonicalRefs(declaredRefs);
            originRefs = canonicalRefs(originRefs);
        }
    }

    /**
     * 引用：平台帖与群帖共用同一文档/版本坐标。
     *
     * @param docId   文档标识
     * @param version 版本
     */
    public record Ref(String docId, long version) implements Comparable<Ref> {

        /** 校验字段。 */
        public Ref {
            requireText(docId, "文档标识");
            if (version < 1) {
                throw new IllegalArgumentException("引用版本必须从 1 开始：" + docId);
            }
        }

        @Override
        public int compareTo(Ref other) {
            int byDoc = docId.compareTo(other.docId);
            return byDoc != 0 ? byDoc : Long.compare(version, other.version);
        }
    }

    /**
     * 开局：冻结登记人物与模块时刻。
     *
     * @param persons 登记人物
     * @param clock   起始时刻
     * @return 空状态
     */
    public static State open(List<String> persons, long clock) {
        requireNonNegative(clock, "起始时刻");
        return new State(persons, List.of(), List.of(), List.of(), clock);
    }

    /**
     * 确定性文档标识。
     *
     * @param author    作者
     * @param commandId 命令
     * @return 文档标识
     */
    public static String docIdFor(String author, String commandId) {
        requireText(author, "作者");
        requireText(commandId, "命令");
        return DOC_PREFIX + POST_PREFIX + author.length() + ":" + author + ":"
                + commandId.length() + ":" + commandId;
    }

    /**
     * 开始公开发帖；受众固定 PUBLIC，不读取任何成员表。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param author    作者
     * @param commandId 命令
     * @param title     标题
     * @param body      正文
     * @param declared  直接引用的版本
     * @param now       开始时刻
     * @return 结果
     */
    public static BeginOutcome beginPost(State state, Context context, String author,
                                         String commandId, String title, String body,
                                         List<Ref> declared, long now) {
        requireState(state);
        requireContext(context);
        requireText(author, "作者");
        requireText(commandId, "命令");
        requireText(title, "标题");
        requireText(body, "正文");
        requireNonNegative(now, "开始时刻");
        List<Ref> direct = canonicalRefs(declared);
        String docId = docIdFor(author, commandId);
        String digest = GroupModel.platformDigest(author, title, body, toGroupRefs(direct));
        Command recorded = commandOf(state, author, commandId);
        if (recorded != null) {
            if (!recorded.kind().equals("POST") || !recorded.subject().equals(docId)
                    || !recorded.digest().equals(digest)) {
                return new BeginOutcome(BeginStatus.COMMAND_CONFLICT, state, null);
            }
            BeginStatus replay = beginReplay(recorded.result());
            Draft draft = replay == BeginStatus.STARTED
                    ? draftOf(state, author, commandId) : null;
            PendingPost payload = draft == null ? null
                    : new PendingPost(docId, 1, author, title, body, draft.declaredRefs(),
                            draft.originRefs());
            return new BeginOutcome(replay, state, payload);
        }
        if (!state.persons().contains(author)) {
            return new BeginOutcome(BeginStatus.NOT_AVAILABLE, state, null);
        }
        if (now < state.clock()) {
            return new BeginOutcome(BeginStatus.TIME_REVERSED, state, null);
        }
        if (hasDraft(state, author)) {
            return new BeginOutcome(BeginStatus.BUSY,
                    archive(state, author, commandId, "POST", docId, digest, BeginStatus.BUSY, now),
                    null);
        }
        for (Ref ref : direct) {
            if (!context.citations().canCite(author, ref.docId(), ref.version(), now)) {
                return new BeginOutcome(BeginStatus.NOT_CITABLE,
                        archive(state, author, commandId, "POST", docId, digest,
                                BeginStatus.NOT_CITABLE, now), null);
            }
        }
        Optional<List<Ref>> roots = rootsFor(state, context, direct);
        if (roots.isEmpty()) {
            return new BeginOutcome(BeginStatus.NO_VERSION_SOURCE,
                    archive(state, author, commandId, "POST", docId, digest,
                            BeginStatus.NO_VERSION_SOURCE, now), null);
        }
        long finishedAt = safeAdd(now, context.config().postMillis());
        long publishAt = finishedAt < 0 ? -1L : safeAdd(finishedAt,
                context.config().deliveryMillis());
        if (finishedAt < 0 || publishAt < 0) {
            return new BeginOutcome(BeginStatus.TIME_OVERFLOW,
                    archive(state, author, commandId, "POST", docId, digest,
                            BeginStatus.TIME_OVERFLOW, now), null);
        }
        Draft draft = new Draft(commandId, docId, author, digest, direct, roots.get(), 1, 0,
                now, finishedAt, publishAt);
        Command command = new Command(author, commandId, "POST", docId, digest,
                BeginStatus.STARTED.name(), now);
        return new BeginOutcome(BeginStatus.STARTED,
                derive(state, state.posts(), plusDraft(state, draft),
                        plusCommand(state, command), now),
                new PendingPost(docId, 1, author, title, body, direct, roots.get()));
    }

    /**
     * 完成公开发帖：到期帧只复核作者仍是登记人物。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param author    作者
     * @param commandId 命令
     * @param now       处理时刻
     * @return 结果
     */
    public static FinishOutcome finishPost(State state, Context context, String author,
                                           String commandId, long now) {
        return finishCorrectionOrPost(state, context, author, commandId, now, "POST");
    }

    /**
     * 开始更正本人已发布的平台帖：只能原作者，基于当前版本。
     *
     * @param state          当前状态
     * @param context        受信上下文
     * @param author         作者
     * @param docId          文档
     * @param basedOnVersion 当前版本
     * @param commandId      命令
     * @param title          标题
     * @param body           正文
     * @param declared       直接引用
     * @param now            开始时刻
     * @return 结果
     */
    public static BeginOutcome beginCorrection(State state, Context context, String author,
                                               String docId, long basedOnVersion,
                                               String commandId, String title, String body,
                                               List<Ref> declared, long now) {
        requireState(state);
        requireContext(context);
        requireText(author, "作者");
        requireText(docId, "文档标识");
        requireText(commandId, "命令");
        requireText(title, "标题");
        requireText(body, "正文");
        requireNonNegative(basedOnVersion, "基准版本");
        requireNonNegative(now, "开始时刻");
        List<Ref> direct = canonicalRefs(declared);
        long version = basedOnVersion + 1;
        String digest = GroupModel.versionDigest("PLATFORM", author, "", version, basedOnVersion,
                title, body, toGroupRefs(direct));
        Command recorded = commandOf(state, author, commandId);
        if (recorded != null) {
            if (!recorded.kind().equals("CORRECTION") || !recorded.subject().equals(docId)
                    || !recorded.digest().equals(digest)) {
                return new BeginOutcome(BeginStatus.COMMAND_CONFLICT, state, null);
            }
            BeginStatus replay = beginReplay(recorded.result());
            Draft draft = replay == BeginStatus.STARTED
                    ? draftOf(state, author, commandId) : null;
            PendingPost payload = draft == null ? null
                    : new PendingPost(docId, version, author, title, body,
                            draft.declaredRefs(), draft.originRefs());
            return new BeginOutcome(replay, state, payload);
        }
        if (!state.persons().contains(author)) {
            return new BeginOutcome(BeginStatus.NOT_AVAILABLE, state, null);
        }
        if (now < state.clock()) {
            return new BeginOutcome(BeginStatus.TIME_REVERSED, state, null);
        }
        Post post = postOf(state, docId, basedOnVersion).orElse(null);
        if (post == null) {
            return new BeginOutcome(BeginStatus.NOT_AVAILABLE, state, null);
        }
        if (!post.author().equals(author)) {
            return new BeginOutcome(BeginStatus.NOT_AVAILABLE,
                    archive(state, author, commandId, "CORRECTION", docId, digest,
                            BeginStatus.NOT_AVAILABLE, now), null);
        }
        if (currentVersionOf(state, docId) != basedOnVersion) {
            return new BeginOutcome(BeginStatus.NOT_AVAILABLE,
                    archive(state, author, commandId, "CORRECTION", docId, digest,
                            BeginStatus.NOT_AVAILABLE, now), null);
        }
        if (hasDraft(state, author)) {
            return new BeginOutcome(BeginStatus.BUSY,
                    archive(state, author, commandId, "CORRECTION", docId, digest,
                            BeginStatus.BUSY, now), null);
        }
        for (Ref ref : direct) {
            if (!context.citations().canCite(author, ref.docId(), ref.version(), now)) {
                return new BeginOutcome(BeginStatus.NOT_CITABLE,
                        archive(state, author, commandId, "CORRECTION", docId, digest,
                                BeginStatus.NOT_CITABLE, now), null);
            }
        }
        Optional<List<Ref>> roots = rootsFor(state, context, direct);
        if (roots.isEmpty()) {
            return new BeginOutcome(BeginStatus.NO_VERSION_SOURCE,
                    archive(state, author, commandId, "CORRECTION", docId, digest,
                            BeginStatus.NO_VERSION_SOURCE, now), null);
        }
        long finishedAt = safeAdd(now, context.config().postMillis());
        long publishAt = finishedAt < 0 ? -1L : safeAdd(finishedAt,
                context.config().deliveryMillis());
        if (finishedAt < 0 || publishAt < 0) {
            return new BeginOutcome(BeginStatus.TIME_OVERFLOW,
                    archive(state, author, commandId, "CORRECTION", docId, digest,
                            BeginStatus.TIME_OVERFLOW, now), null);
        }
        Draft draft = new Draft(commandId, docId, author, digest, direct, roots.get(), version,
                basedOnVersion, now, finishedAt, publishAt);
        Command command = new Command(author, commandId, "CORRECTION", docId, digest,
                BeginStatus.STARTED.name(), now);
        return new BeginOutcome(BeginStatus.STARTED,
                derive(state, state.posts(), plusDraft(state, draft),
                        plusCommand(state, command), now),
                new PendingPost(docId, version, author, title, body, direct, roots.get()));
    }

    /**
     * 完成平台更正：到期帧复核作者仍是原作者。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param author    作者
     * @param commandId 命令
     * @param now       处理时刻
     * @return 结果
     */
    public static FinishOutcome finishCorrection(State state, Context context, String author,
                                                 String commandId, long now) {
        return finishCorrectionOrPost(state, context, author, commandId, now, "CORRECTION");
    }

    private static FinishOutcome finishCorrectionOrPost(State state, Context context,
                                                        String author, String commandId,
                                                        long now, String kind) {
        requireState(state);
        requireContext(context);
        requireText(author, "作者");
        requireText(commandId, "命令");
        requireNonNegative(now, "处理时刻");
        Command command = commandOf(state, author, commandId);
        if (command == null || !command.kind().equals(kind)) {
            return new FinishOutcome(FinishStatus.NOT_STARTED, state, null);
        }
        if (!command.result().equals(BeginStatus.STARTED.name())) {
            return new FinishOutcome(FinishStatus.NOT_AVAILABLE, state, null);
        }
        Draft draft = draftOf(state, author, commandId);
        if (draft == null) {
            return new FinishOutcome(FinishStatus.NOT_STARTED, state, null);
        }
        if (now < draft.dueAt()) {
            return new FinishOutcome(FinishStatus.NOT_DUE, state, null);
        }
        boolean eligible = state.persons().contains(author);
        PostStatus status = eligible ? PostStatus.QUEUED : PostStatus.ABORTED;
        Post post = new Post(draft.docId(), draft.version(), draft.basedOnVersion(), author,
                draft.digest(), draft.declaredRefs(), draft.originRefs(), draft.startedAt(),
                draft.dueAt(), eligible ? draft.publishAt() : draft.dueAt(), OPEN, status,
                commandId);
        FinishStatus finish = eligible ? FinishStatus.QUEUED : FinishStatus.ABORTED;
        return new FinishOutcome(finish,
                finishMutation(state, draft, finish.name(), now, post), post);
    }

    /**
     * 按计划发布一版；在发布帧原子核对真实文档版本。
     *
     * <p>{@code documentVersionAtFrame} 必须由调用方从唯一 InformationModel 读出：首版要求 0，
     * 更正要求等于 {@code basedOnVersion}。不符则版本转终止且保留已耗时刻与原命令，绝不覆盖旧版本。</p>
     *
     * @param state                  当前状态
     * @param docId                  文档
     * @param version                版本
     * @param documentVersionAtFrame 发布帧真实文档版本；文档不存在传 0
     * @param now                    处理时刻
     * @return 结果
     */
    public static PublishOutcome publish(State state, String docId, long version,
                                         long documentVersionAtFrame, long now) {
        requireState(state);
        requireText(docId, "文档标识");
        requireNonNegative(documentVersionAtFrame, "文档版本");
        requireNonNegative(now, "处理时刻");
        Post current = postOf(state, docId, version).orElse(null);
        if (current == null) {
            return new PublishOutcome(PublishStatus.NOT_AVAILABLE, state, null);
        }
        if (current.status() == PostStatus.PUBLISHED) {
            return new PublishOutcome(PublishStatus.ALREADY_PUBLISHED, state, current);
        }
        if (current.status() == PostStatus.ABORTED) {
            return new PublishOutcome(PublishStatus.ABORTED, state, current);
        }
        if (now < current.publishAt()) {
            return new PublishOutcome(PublishStatus.NOT_DUE, state, current);
        }
        PostStatus status;
        String result;
        long publishAt;
        long publishedAt;
        if (documentVersionAtFrame == current.basedOnVersion()) {
            status = PostStatus.PUBLISHED;
            result = PublishStatus.PUBLISHED.name();
            publishAt = current.publishAt();
            publishedAt = current.publishAt();
        } else {
            status = PostStatus.ABORTED;
            result = PublishStatus.STALE_VERSION.name();
            publishAt = current.finishedAt();
            publishedAt = OPEN;
        }
        Post next = new Post(current.docId(), current.version(), current.basedOnVersion(),
                current.author(), current.contentDigest(), current.declaredRefs(),
                current.originRefs(), current.startedAt(), current.finishedAt(), publishAt,
                publishedAt, status, current.commandId());
        List<Post> posts = replacePost(state.posts(), current, next);
        List<Command> commands = replaceCommandResult(state.commands(), current.author(),
                current.commandId(), result);
        return new PublishOutcome(PublishStatus.valueOf(result),
                derive(state, posts, state.drafts(), commands, now), next);
    }

    /**
     * 查某文档某版本；同一版本存在终止与新尝试时，只返回真正占用版本号的记录。
     *
     * <p>终止记录保留用于审计与已耗工时，但不能遮挡后续重试。只有在没有有效记录时才返回终止
     * 记录，供重放与报告使用。</p>
     *
     * @param state   状态
     * @param docId   文档
     * @param version 版本
     * @return 版本记录
     */
    public static Optional<Post> postOf(State state, String docId, long version) {
        requireState(state);
        Post aborted = null;
        for (Post post : state.posts()) {
            if (post.docId().equals(docId) && post.version() == version) {
                if (post.status() != PostStatus.ABORTED) {
                    return Optional.of(post);
                }
                aborted = post;
            }
        }
        return Optional.ofNullable(aborted);
    }

    /**
     * 当前版本号；文档不存在返回 0。终止的记录不占版本号（与 GroupModel 同口径）。
     *
     * @param state 状态
     * @param docId 文档
     * @return 版本号
     */
    public static long currentVersionOf(State state, String docId) {
        requireState(state);
        requireText(docId, "文档标识");
        long current = 0L;
        for (Post post : state.posts()) {
            if (post.docId().equals(docId) && post.status() != PostStatus.ABORTED) {
                current = Math.max(current, post.version());
            }
        }
        return current;
    }

    /**
     * 冻结序列化。
     *
     * @param state 状态
     * @return JSON
     */
    public static String toJson(State state) {
        requireState(state);
        try {
            return MAPPER.writeValueAsString(state);
        } catch (Exception failure) {
            throw new IllegalStateException("无法序列化平台状态", failure);
        }
    }

    /**
     * 从 JSON 恢复并重新校验全部不变量；只校自身一致性，不假称已看见 InformationModel。
     *
     * @param json JSON
     * @return 状态
     */
    public static State fromJson(String json) {
        requireText(json, "JSON");
        try {
            return MAPPER.readValue(json, State.class);
        } catch (Exception failure) {
            throw new IllegalArgumentException("平台状态 JSON 不合法", failure);
        }
    }

    // ------------------------------------------------------------- 内部

    private static Optional<List<Ref>> rootsFor(State state, Context context, List<Ref> direct) {
        GroupModel.State groups = context.groups();
        Optional<List<GroupModel.Ref>> expanded = groups == null
                ? Optional.of(List.of())
                : GroupModel.rootsFor(groups, groupContext(context), toGroupRefs(direct));
        if (expanded.isEmpty()) {
            return Optional.empty();
        }
        List<Ref> roots = new ArrayList<>();
        for (GroupModel.Ref ref : expanded.get()) {
            roots.add(new Ref(ref.docId(), ref.version()));
        }
        return Optional.of(canonicalRefs(roots));
    }

    private static GroupModel.Context groupContext(Context context) {
        return new GroupModel.Context(context.citations(), context.versionOrigins(),
                new GroupModel.Config(List.of(), 1L, 1L, 1L, 1L, context.config().postMillis(),
                        context.config().deliveryMillis()));
    }

    private static List<GroupModel.Ref> toGroupRefs(List<Ref> refs) {
        List<GroupModel.Ref> converted = new ArrayList<>();
        for (Ref ref : refs == null ? List.<Ref>of() : refs) {
            converted.add(new GroupModel.Ref(ref.docId(), ref.version()));
        }
        return converted;
    }

    private static State derive(State base, List<Post> posts, List<Draft> drafts,
                                List<Command> commands, long now) {
        return new State(base.persons(), posts, drafts, commands, Math.max(base.clock(), now));
    }

    private static State archive(State state, String caller, String commandId, String kind,
                                 String subject, String digest, Enum<?> result, long now) {
        Command command = new Command(caller, commandId, kind, subject, digest, result.name(), now);
        return derive(state, state.posts(), state.drafts(), plusCommand(state, command), now);
    }

    private static State finishMutation(State state, Draft draft, String result, long now,
                                        Post post) {
        List<Draft> drafts = new ArrayList<>(state.drafts());
        drafts.remove(draft);
        List<Command> commands = replaceCommandResult(state.commands(), draft.caller(),
                draft.commandId(), result);
        List<Post> posts = new ArrayList<>(state.posts());
        posts.add(post);
        return derive(state, posts, drafts, commands, now);
    }

    private static List<Post> replacePost(List<Post> posts, Post old, Post replacement) {
        List<Post> copy = new ArrayList<>(posts);
        copy.set(copy.indexOf(old), replacement);
        return copy;
    }

    private static List<Draft> plusDraft(State state, Draft draft) {
        List<Draft> drafts = new ArrayList<>(state.drafts());
        drafts.add(draft);
        return drafts;
    }

    private static List<Command> plusCommand(State state, Command command) {
        List<Command> commands = new ArrayList<>(state.commands());
        commands.add(command);
        return commands;
    }

    private static List<Command> replaceCommandResult(List<Command> commands, String caller,
                                                      String commandId, String result) {
        List<Command> copy = new ArrayList<>(commands);
        for (int index = 0; index < copy.size(); index++) {
            Command command = copy.get(index);
            if (command.caller().equals(caller) && command.commandId().equals(commandId)) {
                copy.set(index, new Command(command.caller(), command.commandId(),
                        command.kind(), command.subject(), command.digest(), result,
                        command.at()));
                return copy;
            }
        }
        throw new IllegalStateException("缺少平台命令记录：" + commandId);
    }

    private static Command commandOf(State state, String caller, String commandId) {
        return commandOf(state.commands(), caller, commandId);
    }

    private static Command commandOf(List<Command> commands, String caller, String commandId) {
        for (Command command : commands) {
            if (command.caller().equals(caller) && command.commandId().equals(commandId)) {
                return command;
            }
        }
        return null;
    }

    private static Draft draftOf(State state, String caller, String commandId) {
        for (Draft draft : state.drafts()) {
            if (draft.caller().equals(caller) && draft.commandId().equals(commandId)) {
                return draft;
            }
        }
        return null;
    }

    private static boolean hasDraft(State state, String caller) {
        for (Draft draft : state.drafts()) {
            if (draft.caller().equals(caller)) {
                return true;
            }
        }
        return false;
    }

    private static BeginStatus beginReplay(String result) {
        try {
            return BeginStatus.valueOf(result);
        } catch (IllegalArgumentException unknown) {
            return BeginStatus.COMMAND_CONFLICT;
        }
    }

    private static long safeAdd(long base, long delta) {
        long sum = base + delta;
        return sum < base ? -1L : sum;
    }

    private static void validate(List<String> persons, List<Post> posts, List<Draft> drafts,
                                 List<Command> commands, long clock) {
        Set<String> people = new HashSet<>(persons);
        Set<String> keys = new HashSet<>();
        Set<String> occupied = new HashSet<>();
        for (Post post : posts) {
            if (!people.contains(post.author())) {
                throw new IllegalArgumentException("平台作者未登记：" + post.author());
            }
            if (!keys.add(post.docId() + "#" + post.version() + "#" + post.commandId())) {
                throw new IllegalArgumentException("平台版本重复：" + post.docId());
            }
            if (post.status() != PostStatus.ABORTED
                    && !occupied.add(post.docId() + "#" + post.version())) {
                throw new IllegalArgumentException("同一版本存在两条未终止记录：" + post.docId());
            }
            if (post.startedAt() > clock
                    || (post.status() == PostStatus.PUBLISHED && post.publishedAt() > clock)) {
                throw new IllegalArgumentException("平台版本晚于模块时刻：" + post.docId());
            }
            long previous = post.version() == 1 ? 0L : post.version() - 1;
            if (post.version() > 1 && usableOf(posts, post.docId(), previous) == null) {
                throw new IllegalArgumentException("平台版本不连续：" + post.docId());
            }
            Command command = commandOf(commands, post.author(), post.commandId());
            String expected = switch (post.status()) {
                case QUEUED -> FinishStatus.QUEUED.name();
                case PUBLISHED -> PublishStatus.PUBLISHED.name();
                case ABORTED -> command != null
                        && PublishStatus.STALE_VERSION.name().equals(command.result())
                        ? PublishStatus.STALE_VERSION.name() : FinishStatus.ABORTED.name();
            };
            if (command == null || !command.subject().equals(post.docId())
                    || !command.result().equals(expected)
                    || !command.digest().equals(post.contentDigest())) {
                throw new IllegalArgumentException("平台版本缺少匹配命令：" + post.docId());
            }
            String kind = post.version() == 1 ? "POST" : "CORRECTION";
            if (!command.kind().equals(kind)) {
                throw new IllegalArgumentException("平台版本命令种类不符：" + post.docId());
            }
        }
        Set<String> draftKeys = new HashSet<>();
        for (Draft draft : drafts) {
            if (!people.contains(draft.caller())) {
                throw new IllegalArgumentException("平台动作发起人未登记：" + draft.caller());
            }
            if (!draftKeys.add(draft.caller() + '\u0000' + draft.commandId())) {
                throw new IllegalArgumentException("平台动作重复：" + draft.commandId());
            }
            if (draft.startedAt() > clock) {
                throw new IllegalArgumentException("平台动作晚于模块时刻：" + draft.commandId());
            }
            Command command = commandOf(commands, draft.caller(), draft.commandId());
            if (command == null || !command.result().equals(BeginStatus.STARTED.name())
                    || !command.subject().equals(draft.docId())
                    || !command.digest().equals(draft.digest())) {
                throw new IllegalArgumentException("平台动作与命令不一致：" + draft.commandId());
            }
            String kind = draft.version() == 1 ? "POST" : "CORRECTION";
            if (!command.kind().equals(kind)) {
                throw new IllegalArgumentException("平台动作命令种类不符：" + draft.commandId());
            }
        }
        Set<String> commandKeys = new HashSet<>();
        for (Command command : commands) {
            if (!people.contains(command.caller())) {
                throw new IllegalArgumentException("平台命令发起人未登记：" + command.caller());
            }
            if (command.at() > clock) {
                throw new IllegalArgumentException("平台命令晚于模块时刻：" + command.commandId());
            }
            String key = command.caller() + '\u0000' + command.commandId();
            if (!commandKeys.add(key)) {
                throw new IllegalArgumentException("平台命令重复：" + key);
            }
            if (command.result().equals(BeginStatus.STARTED.name())
                    && !draftKeys.contains(key)) {
                throw new IllegalArgumentException("STARTED 平台命令缺少动作：" + key);
            }
            if (!command.result().equals(BeginStatus.STARTED.name())
                    && draftKeys.contains(key)) {
                throw new IllegalArgumentException("已裁决平台命令仍有动作：" + key);
            }
        }
    }

    private static Post postOf(List<Post> posts, String docId, long version) {
        for (Post post : posts) {
            if (post.docId().equals(docId) && post.version() == version) {
                return post;
            }
        }
        return null;
    }

    /** 真正占用版本号的记录；终止记录不算。 */
    private static Post usableOf(List<Post> posts, String docId, long version) {
        for (Post post : posts) {
            if (post.docId().equals(docId) && post.version() == version
                    && post.status() != PostStatus.ABORTED) {
                return post;
            }
        }
        return null;
    }

    private static List<Ref> canonicalRefs(List<Ref> refs) {
        List<Ref> copy = new ArrayList<>(refs == null ? List.of() : refs);
        for (Ref ref : copy) {
            Objects.requireNonNull(ref, "引用");
        }
        copy.sort(Ref::compareTo);
        List<Ref> unique = new ArrayList<>();
        Ref previous = null;
        for (Ref ref : copy) {
            if (previous == null || !previous.equals(ref)) {
                unique.add(ref);
                previous = ref;
            }
        }
        return List.copyOf(unique);
    }

    private static List<String> sortedUniqueText(List<String> values, String name) {
        List<String> copy = new ArrayList<>(Objects.requireNonNull(values, name));
        copy.sort(String::compareTo);
        List<String> unique = new ArrayList<>();
        String previous = null;
        for (String value : copy) {
            requireText(value, name);
            if (!value.equals(previous)) {
                unique.add(value);
                previous = value;
            }
        }
        return List.copyOf(unique);
    }

    private static <T> List<T> sortedCopy(List<T> values, Comparator<? super T> comparator) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(values, "集合"));
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    private static void requireState(State state) {
        Objects.requireNonNull(state, "状态");
    }

    private static void requireContext(Context context) {
        Objects.requireNonNull(context, "上下文");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + "不能为负：" + value);
        }
    }
}
