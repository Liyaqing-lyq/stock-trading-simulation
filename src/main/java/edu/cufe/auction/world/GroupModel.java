package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 群组与平台帖的纯核：成员有效段、邀请、发帖元数据与来源根。
 *
 * <p><b>权威边界</b>：本类不保存帖子正文、版本内容、阅读记录、经历或回忆；正文与版本仍由
 * {@code InformationModel} 持有。这里只保存群定义、成员加入/退出段、邀请、帖子元数据、规范来源根
 * 和命令幂等记录。</p>
 *
 * <p><b>读取门</b>：{@link #canRead} 同时看当前开放成员段与该版本文档的发布时间；JOIN_TIME 要求
 * 文档版本不早于本段加入时刻，ALL_HISTORY 不设该门。退出后没有开放段，不能新读取；已经发生的
 * 阅读经历与回忆不由本类撤销。</p>
 *
 * <p><b>来源口径</b>：已知帖 v1 继承其 {@code originRefs}；更正版（版本 ≥2）必须由受信
 * {@link VersionOrigins} 提供该版本冻结来源。缺失返回 {@link PostBeginStatus#NO_VERSION_SOURCE}，
 * 不得猜成 v1 的根或新的独立根；明确空列表表示该版本自身没有来源。来源元数据由世界桥从唯一
 * 版本谱系读取，模型不能自填来源字符串。</p>
 *
 * <p><b>加载校验</b>：{@link #fromJson} 会重算可复算的命令摘要并核对成员段/邀请/命令的对应：
 * 非 seed 成员段必须有匹配 JOIN/RESPOND 完成命令，seed 只对应群主与群创建时刻；邀请创建与
 * 处理命令必须与受邀人、群、决定一致。纯核不做签名，也不假装能拒绝完全自洽的历史改写；
 * 完整逐帧重演仍由世界负责。</p>
 *
 * <p><b>发帖</b>：begin 与 finish 核作者成员资格，finish 以逻辑完成时刻 {@code finishedAt}
 * 判定；finish 合法后作者退出不撤销投递，publish 只按冻结的 {@code publishAt} 发布，不再核作者
 * 成员资格。作者在 finish 前退出则帖子终止，不生成文档。</p>
 *
 * <p><b>时间与幂等</b>：所有时长必须为正，所有时刻单调；{@code (caller, commandId)} 绑定动作种类、
 * 对象和完整语义摘要，同内容重放返回原裁决与当前状态，异内容返回冲突。任何失败都不产生半条记录。</p>
 */
public final class GroupModel {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final String POST_PREFIX = "GPOST-";
    private static final String DOC_PREFIX = "GDOC-";
    private static final String INVITE_PREFIX = "GINV-";
    private static final long OPEN = -1L;

    private GroupModel() {
    }

    /** 入群策略：公开加入或仅凭邀请加入。 */
    public enum JoinPolicy {
        /** 任何已登记人物都可直接加入。 */
        OPEN,
        /** 只有持有效邀请的受邀人可接受加入。 */
        INVITE_ONLY
    }

    /** 历史可见策略。 */
    public enum HistoryPolicy {
        /** 只能读本成员段加入之后发布的版本。 */
        JOIN_TIME,
        /** 群显式允许读取加入前的历史版本。 */
        ALL_HISTORY
    }

    /** 邀请状态。 */
    public enum InvitationStatus {
        /** 等待受邀人处理。 */
        PENDING,
        /** 受邀人已接受并建立成员段。 */
        ACCEPTED,
        /** 受邀人已拒绝。 */
        DECLINED
    }

    /** 帖子生命周期。 */
    public enum PostStatus {
        /** 已写完，等待按 publishAt 发布。 */
        QUEUED,
        /** 已发布，文档可被合资格成员读取。 */
        PUBLISHED,
        /** 完成帧发现作者已不是成员，未生成文档。 */
        ABORTED
    }

    /** 命令种类。 */
    public enum DraftKind {
        /** 公开加入。 */
        JOIN,
        /** 创建邀请。 */
        INVITE,
        /** 接受或拒绝邀请。 */
        RESPOND,
        /** 退出。 */
        LEAVE,
        /** 发帖或结构化转发。 */
        POST,
        /** 对本人已发布帖追加更正版本。 */
        POST_VERSION
    }

    /** 加入结果。 */
    public enum JoinStatus {
        /** 已排程，等待到期完成。 */
        STARTED,
        /** 已完成，建立成员段。 */
        JOINED,
        /** 已是开放成员。 */
        ALREADY_MEMBER,
        /** INVITE_ONLY 群缺少有效邀请。 */
        NOT_INVITED,
        /** 群不存在、未创建或调用者不可见。 */
        NOT_AVAILABLE,
        /** 尚未到期。 */
        NOT_DUE,
        /** 动作尚未开始或已被其他动作取代。 */
        NOT_STARTED,
        /** 时间倒退。 */
        TIME_REVERSED,
        /** 时刻加法溢出。 */
        TIME_OVERFLOW,
        /** 同一 commandId 绑定了不同语义。 */
        COMMAND_CONFLICT,
        /** 本人还有未完成的群组动作。 */
        BUSY
    }

    /** 邀请创建结果。 */
    public enum InviteStatus {
        /** 已排程。 */
        STARTED,
        /** 已创建待处理邀请。 */
        INVITED,
        /** 群内已有该受邀人的待处理邀请。 */
        ALREADY_INVITED,
        /** 受邀人已是成员。 */
        ALREADY_MEMBER,
        /** 邀请人不是该群开放成员，或对象/群不可见。 */
        NOT_AUTHORIZED,
        /** 对象或群不可见。 */
        NOT_AVAILABLE,
        /** 尚未到期。 */
        NOT_DUE,
        /** 尚未开始。 */
        NOT_STARTED,
        /** 时间倒退。 */
        TIME_REVERSED,
        /** 时刻加法溢出。 */
        TIME_OVERFLOW,
        /** 命令冲突。 */
        COMMAND_CONFLICT,
        /** 本人还有未完成动作。 */
        BUSY
    }

    /** 接受/拒绝邀请结果。 */
    public enum RespondStatus {
        /** 已排程。 */
        STARTED,
        /** 已接受并建立成员段。 */
        ACCEPTED,
        /** 已拒绝。 */
        DECLINED,
        /** 邀请不存在、非本人或已处理。 */
        NOT_INVITED,
        /** 已是开放成员。 */
        ALREADY_MEMBER,
        /** 邀请或群不可见。 */
        NOT_AVAILABLE,
        /** 尚未到期。 */
        NOT_DUE,
        /** 尚未开始。 */
        NOT_STARTED,
        /** 时间倒退。 */
        TIME_REVERSED,
        /** 时刻加法溢出。 */
        TIME_OVERFLOW,
        /** 命令冲突。 */
        COMMAND_CONFLICT,
        /** 本人还有未完成动作。 */
        BUSY
    }

    /** 退出结果。 */
    public enum LeaveStatus {
        /** 已排程。 */
        STARTED,
        /** 已关闭成员段。 */
        LEFT,
        /** 没有开放成员段；未知群同样返回该码。 */
        NOT_MEMBER,
        /** 尚未到期。 */
        NOT_DUE,
        /** 尚未开始。 */
        NOT_STARTED,
        /** 时间倒退。 */
        TIME_REVERSED,
        /** 时刻加法溢出。 */
        TIME_OVERFLOW,
        /** 命令冲突。 */
        COMMAND_CONFLICT,
        /** 本人还有未完成动作。 */
        BUSY
    }

    /** 发帖开始结果。 */
    public enum PostBeginStatus {
        /** 已排程。 */
        STARTED,
        /** 已完成，等待发布（重放可见）。 */
        QUEUED,
        /** 已完成但作者在完成帧已非成员（重放可见）。 */
        ABORTED,
        /** 已按计划发布（重放可见）。 */
        PUBLISHED,
        /** 群不存在、未创建或当前不是成员。 */
        NOT_AVAILABLE,
        /** 直接来源不可由本人引用。 */
        NOT_CITABLE,
        /** 已知帖的更正版缺少可信版本来源；不得猜成 v1 或独立根。 */
        NO_VERSION_SOURCE,
        /** 本人还有未完成动作。 */
        BUSY,
        /** 时间倒退。 */
        TIME_REVERSED,
        /** 时刻加法溢出。 */
        TIME_OVERFLOW,
        /** 命令冲突。 */
        COMMAND_CONFLICT
    }

    /** 发帖完成结果。 */
    public enum PostFinishStatus {
        /** 已写完并等待发布。 */
        QUEUED,
        /** 完成帧作者已不是成员。 */
        ABORTED,
        /** 已完成发布（重放可见）。 */
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
        /** 尚未到 publishAt。 */
        NOT_DUE,
        /** 帖子不存在。 */
        NOT_AVAILABLE,
        /** 帖子已终止。 */
        ABORTED,
        /** 已经发布过。 */
        ALREADY_PUBLISHED,
        /** 发布帧发现文档版本已被他人推进：原子拒绝，不覆盖旧版本。 */
        STALE_VERSION
    }

    /** 更正开始结果。 */
    public enum CorrectionBeginStatus {
        /** 已排程。 */
        STARTED,
        /** 文档不存在。 */
        NOT_AVAILABLE,
        /** 调用者不是原帖作者。 */
        NOT_AUTHOR,
        /** 作者在开始时刻已不是该群当前成员。 */
        NOT_MEMBER,
        /** basedOnVersion 不等于当前版本。 */
        STALE_VERSION,
        /** 本人已有未完成动作。 */
        BUSY,
        /** 时间倒退。 */
        TIME_REVERSED,
        /** 时刻溢出。 */
        TIME_OVERFLOW,
        /** 引用的直接来源无法引用其对应版本。 */
        NOT_CITABLE,
        /** 来源谱系缺失，必须拒绝而不是猜。 */
        NO_VERSION_SOURCE,
        /** 同一命令标识绑定了不同语义。 */
        COMMAND_CONFLICT
    }

    /** 更正完成结果。 */
    public enum CorrectionFinishStatus {
        /** 已写完并等待发布。 */
        QUEUED,
        /** 完成帧作者已不是成员或已不是作者。 */
        ABORTED,
        /** 已完成发布（重放可见）。 */
        PUBLISHED,
        /** 尚未到期。 */
        NOT_DUE,
        /** 尚未开始。 */
        NOT_STARTED,
        /** 状态不一致。 */
        NOT_AVAILABLE
    }

    /**
     * 群定义；配置预置，运行期不创建、不修改。
     *
     * <p>{@code initialMembers} 是开局历史禀赋：这些人物在群创建时刻已合法在群内，由系统种子段表达，
     * 不伪造加入工时或加入命令。运行期新增成员仍必须真实加入/接受邀请。</p>
     *
     * @param groupId        群标识
     * @param ownerPartyId   群主
     * @param joinPolicy     加入策略
     * @param historyPolicy  历史可见策略
     * @param createdAt      群创建时刻
     * @param initialMembers 除群主外的开局初始成员
     */
    public record Group(String groupId, String ownerPartyId, JoinPolicy joinPolicy,
                        HistoryPolicy historyPolicy, long createdAt, List<String> initialMembers) {

        /** 兼容构造：没有额外初始成员。 */
        public Group(String groupId, String ownerPartyId, JoinPolicy joinPolicy,
                     HistoryPolicy historyPolicy, long createdAt) {
            this(groupId, ownerPartyId, joinPolicy, historyPolicy, createdAt, List.of());
        }

        /** 校验字段。 */
        public Group {
            requireText(groupId, "群标识");
            requireText(ownerPartyId, "群主");
            Objects.requireNonNull(joinPolicy, "加入策略");
            Objects.requireNonNull(historyPolicy, "历史策略");
            requireNonNegative(createdAt, "群创建时刻");
            List<String> members = new ArrayList<>(initialMembers == null
                    ? List.of() : initialMembers);
            HashSet<String> seen = new HashSet<>();
            for (String member : members) {
                requireText(member, "初始成员");
                if (member.equals(ownerPartyId)) {
                    throw new IllegalArgumentException("初始成员不能重复列群主："
                            + groupId + "/" + member);
                }
                if (!seen.add(member)) {
                    throw new IllegalArgumentException("初始成员重复：" + groupId + "/" + member);
                }
            }
            initialMembers = List.copyOf(members);
        }
    }

    /**
     * 文档引用；版本从 1 开始。
     *
     * @param docId   资料标识
     * @param version 版本
     */
    public record Ref(String docId, long version) implements Comparable<Ref> {

        /** 校验字段。 */
        public Ref {
            requireText(docId, "资料标识");
            if (version < 1) {
                throw new IllegalArgumentException("资料版本必须从 1 开始：" + version);
            }
        }

        @Override
        public int compareTo(Ref other) {
            int byDoc = docId.compareTo(other.docId);
            return byDoc != 0 ? byDoc : Long.compare(version, other.version);
        }
    }

    /**
     * 成员段；每次加入一段，退出关闭当前段。
     *
     * @param groupId        群
     * @param person         人物
     * @param joinedAt       加入完成时刻
     * @param leftAt         退出完成时刻；开放段为 -1
     * @param joinCommandId  建立该段的命令
     * @param leaveCommandId 关闭该段的命令；开放段为空串
     * @param inviteId       接受邀请的标识；公开加入为空串
     */
    public record Membership(String groupId, String person, long joinedAt, long leftAt,
                             String joinCommandId, String leaveCommandId, String inviteId) {

        /** 校验字段与开放/关闭的一致性。 */
        public Membership {
            requireText(groupId, "群");
            requireText(person, "人物");
            requireNonNegative(joinedAt, "加入时刻");
            if (leftAt != OPEN && leftAt <= joinedAt) {
                throw new IllegalArgumentException("退出时刻必须晚于加入：" + groupId + "/" + person);
            }
            requireText(joinCommandId, "加入命令");
            if (leaveCommandId == null) {
                leaveCommandId = "";
            }
            if (inviteId == null) {
                inviteId = "";
            }
            if (leftAt == OPEN && !leaveCommandId.isEmpty()) {
                throw new IllegalArgumentException("开放成员段不能有退出命令");
            }
            if (leftAt != OPEN && leaveCommandId.isEmpty()) {
                throw new IllegalArgumentException("关闭成员段必须有退出命令");
            }
        }

        /**
         * 是否仍开放。
         *
         * @return 开放返回 true
         */
        public boolean open() {
            return leftAt == OPEN;
        }
    }

    /**
     * 一条邀请；每个 (群, 受邀人) 至多一条待处理邀请。
     *
     * @param inviteId        确定性邀请标识
     * @param groupId         群
     * @param inviter         邀请人
     * @param invitee         受邀人
     * @param invitedAt       邀请生效时刻
     * @param status          状态
     * @param decidedAt       受邀人处理时刻；待处理为 -1
     * @param createCommandId 创建命令
     * @param respondCommandId 接受/拒绝命令；待处理为空串
     */
    public record Invitation(String inviteId, String groupId, String inviter, String invitee,
                             long invitedAt, InvitationStatus status, long decidedAt,
                             String createCommandId, String respondCommandId) {

        /** 校验字段。 */
        public Invitation {
            requireText(inviteId, "邀请标识");
            requireText(groupId, "群");
            requireText(inviter, "邀请人");
            requireText(invitee, "受邀人");
            if (inviter.equals(invitee)) {
                throw new IllegalArgumentException("不能邀请自己：" + invitee);
            }
            requireNonNegative(invitedAt, "邀请时刻");
            Objects.requireNonNull(status, "邀请状态");
            requireText(createCommandId, "创建命令");
            if (respondCommandId == null) {
                respondCommandId = "";
            }
            if (status == InvitationStatus.PENDING) {
                if (decidedAt != -1 || !respondCommandId.isEmpty()) {
                    throw new IllegalArgumentException("待处理邀请不能有处理时刻或处理命令");
                }
            } else if (decidedAt < invitedAt || respondCommandId.isEmpty()) {
                throw new IllegalArgumentException("已处理邀请必须有合法处理时刻与命令");
            }
        }
    }

    /**
     * 帖子元数据；正文不在本类。
     *
     * @param postId        确定性帖子标识
     * @param docId         派生文档标识
     * @param groupId       群
     * @param author        作者
     * @param contentDigest 标题/正文摘要
     * @param declaredRefs  作者实际引用的直接版本；原创为空。发布后不可变，不从规范根反推
     * @param originRefs    规范来源根；原创为空
     * @param startedAt     开始发帖时刻
     * @param finishedAt    逻辑完成时刻
     * @param publishAt     计划发布时刻
     * @param publishedAt   实际发布时刻；未发布为 -1
     * @param status        生命周期状态
     * @param commandId     发帖命令
     */
    public record Post(String postId, String docId, String groupId, String author,
                       String contentDigest, List<Ref> declaredRefs, List<Ref> originRefs,
                       long startedAt, long finishedAt, long publishAt, long publishedAt,
                       PostStatus status, String commandId) {

        /** 校验字段、派生标识与状态时点。 */
        public Post {
            requireText(postId, "帖子标识");
            requireText(docId, "文档标识");
            if (!docId.equals(docIdFor(postId))) {
                throw new IllegalArgumentException("文档标识必须由帖子标识派生：" + postId);
            }
            requireText(groupId, "群");
            requireText(author, "作者");
            requireText(contentDigest, "正文摘要");
            requireText(commandId, "发帖命令");
            if (declaredRefs == null) {
                throw new IllegalArgumentException("帖子缺少直接来源字段，旧候选 JSON 不得伪造来源：" + postId);
            }
            declaredRefs = canonicalRefs(declaredRefs);
            originRefs = canonicalRefs(originRefs);
            requireNonNegative(startedAt, "开始发帖时刻");
            if (finishedAt < startedAt) {
                throw new IllegalArgumentException("完成时刻不能早于开始：" + postId);
            }
            Objects.requireNonNull(status, "帖子状态");
            switch (status) {
                case ABORTED -> {
                    if (publishAt != finishedAt || publishedAt != OPEN) {
                        throw new IllegalArgumentException("终止帖子不得安排发布：" + postId);
                    }
                }
                case QUEUED -> {
                    if (publishAt <= finishedAt || publishedAt != OPEN) {
                        throw new IllegalArgumentException("在途帖子必须有未来发布时刻：" + postId);
                    }
                }
                case PUBLISHED -> {
                    if (publishAt <= finishedAt || publishedAt != publishAt) {
                        throw new IllegalArgumentException("已发布帖子的发布时刻必须等于计划：" + postId);
                    }
                }
                default -> throw new IllegalStateException("未知帖子状态：" + status);
            }
        }
    }

    /**
     * 群帖更正版本；v1 仍由 {@link Post} 唯一持有，本记录只描述 version &gt; 1。
     *
     * <p>只存元数据，不存标题与正文：正文、阅读与回忆仍唯一在 InformationModel。作者、群与受众
     * 继承原帖；记录不得覆盖旧版本，也不得从规范根反推直接来源。</p>
     *
     * @param docId         原帖文档标识
     * @param version       本版本号，从 2 开始且与文档版本连续
     * @param basedOnVersion 起草时的当前版本，必须等于 version - 1
     * @param author        作者，必须等于原帖作者
     * @param contentDigest 标题/正文/直接来源摘要
     * @param declaredRefs  本版作者直接引用的版本
     * @param originRefs    本版规范来源根
     * @param groupId       群，必须等于原帖群
     * @param startedAt     开始写作时刻
     * @param finishedAt    逻辑完成时刻
     * @param publishAt     计划发布时刻
     * @param publishedAt   实际发布时刻；未发布为 -1
     * @param status        生命周期状态
     * @param commandId     更正命令
     */
    public record PostVersion(String docId, long version, long basedOnVersion, String author,
                              String contentDigest, List<Ref> declaredRefs, List<Ref> originRefs,
                              String groupId, long startedAt, long finishedAt, long publishAt,
                              long publishedAt, PostStatus status, String commandId) {

        /** 校验版本连续性、来源字段与状态时点。 */
        public PostVersion {
            requireText(docId, "文档标识");
            if (version < 2) {
                throw new IllegalArgumentException("更正版本必须从 2 开始：" + docId);
            }
            if (basedOnVersion != version - 1) {
                throw new IllegalArgumentException("更正必须紧接上一版本：" + docId
                        + "#" + version);
            }
            requireText(author, "作者");
            requireText(contentDigest, "正文摘要");
            requireText(groupId, "群");
            requireText(commandId, "更正命令");
            if (declaredRefs == null) {
                throw new IllegalArgumentException("更正版本缺少直接来源字段：" + docId);
            }
            declaredRefs = canonicalRefs(declaredRefs);
            originRefs = canonicalRefs(originRefs);
            requireNonNegative(startedAt, "开始更正时刻");
            if (finishedAt < startedAt) {
                throw new IllegalArgumentException("完成时刻不能早于开始：" + docId);
            }
            Objects.requireNonNull(status, "更正状态");
            switch (status) {
                case ABORTED -> {
                    if (publishAt != finishedAt || publishedAt != OPEN) {
                        throw new IllegalArgumentException("终止更正不得安排发布：" + docId);
                    }
                }
                case QUEUED -> {
                    if (publishAt <= finishedAt || publishedAt != OPEN) {
                        throw new IllegalArgumentException("在途更正必须有未来发布时刻：" + docId);
                    }
                }
                case PUBLISHED -> {
                    if (publishAt <= finishedAt || publishedAt != publishAt) {
                        throw new IllegalArgumentException("已发布更正的发布时刻必须等于计划：" + docId);
                    }
                }
                default -> throw new IllegalStateException("未知更正状态：" + status);
            }
        }
    }

    /**
     * 进行中的群组动作；正文与标题不在此持久化。
     *
     * @param commandId 用户命令
     * @param kind      动作种类
     * @param caller    发起人
     * @param groupId   群
     * @param target    邀请的受邀人；其他动作为发起人
     * @param inviteId  邀请标识；非邀请/接受动作为空串
     * @param postId    帖子标识；仅发帖动作非空
     * @param digest    完整语义摘要
     * @param declaredRefs 作者直接引用的版本；仅发帖与更正动作非空
     * @param originRefs 规范来源根；仅发帖与更正动作非空
     * @param docId     被更正的文档；仅更正动作非空
     * @param basedOnVersion 更正所基于的当前版本；仅更正动作非零
     * @param accept    接受为 true，拒绝为 false；仅接受/拒绝动作有效
     * @param startedAt 开始时刻
     * @param dueAt     逻辑完成时刻
     * @param publishAt 计划发布时刻；非发帖/更正为 -1
     */
    public record Draft(String commandId, DraftKind kind, String caller, String groupId,
                        String target, String inviteId, String postId, String digest,
                        List<Ref> declaredRefs, List<Ref> originRefs, String docId,
                        long basedOnVersion, boolean accept,
                        long startedAt, long dueAt, long publishAt) {

        /** 校验字段与动作种类。 */
        public Draft {
            requireText(commandId, "命令标识");
            Objects.requireNonNull(kind, "动作种类");
            requireText(caller, "发起人");
            requireText(groupId, "群");
            requireText(target, "目标");
            requireText(digest, "语义摘要");
            requireNonNegative(startedAt, "开始时刻");
            if (dueAt <= startedAt) {
                throw new IllegalArgumentException("完成时刻必须晚于开始：" + commandId);
            }
            if (inviteId == null) {
                inviteId = "";
            }
            if (postId == null) {
                postId = "";
            }
            if (docId == null) {
                docId = "";
            }
            if (declaredRefs == null) {
                throw new IllegalArgumentException("动作缺少直接来源字段：" + commandId);
            }
            declaredRefs = canonicalRefs(declaredRefs);
            originRefs = canonicalRefs(originRefs);
            if (kind == DraftKind.POST || kind == DraftKind.POST_VERSION) {
                if (publishAt <= dueAt) {
                    throw new IllegalArgumentException("发布时刻必须晚于逻辑完成：" + commandId);
                }
                if (kind == DraftKind.POST) {
                    requireText(postId, "帖子标识");
                    if (!docId.isEmpty() || basedOnVersion != 0) {
                        throw new IllegalArgumentException("新帖不得携带更正目标：" + commandId);
                    }
                } else {
                    requireText(docId, "更正文档");
                    if (basedOnVersion < 1) {
                        throw new IllegalArgumentException("更正必须基于已有版本：" + commandId);
                    }
                    if (!postId.isEmpty()) {
                        throw new IllegalArgumentException("更正不得携带新帖标识：" + commandId);
                    }
                }
            } else {
                if (publishAt != OPEN || !postId.isEmpty() || !docId.isEmpty()
                        || basedOnVersion != 0 || !originRefs.isEmpty()
                        || !declaredRefs.isEmpty()) {
                    throw new IllegalArgumentException("非发帖动作不得携带帖子、文档或发布时刻："
                            + commandId);
                }
                if (kind == DraftKind.INVITE || kind == DraftKind.RESPOND) {
                    requireText(inviteId, "邀请标识");
                } else if (!inviteId.isEmpty()) {
                    throw new IllegalArgumentException("非邀请动作不得携带邀请标识：" + commandId);
                }
            }
        }
    }

    /**
     * 命令记录：绑定完整语义与裁决，供重放与审计。
     *
     * @param caller    发起人
     * @param commandId 用户命令
     * @param kind      动作种类
     * @param subject   对象（群、邀请或帖子）
     * @param digest    语义摘要
     * @param result    命令记录的当前裁决；begin 成功写 STARTED，finish 可更新终态；不是不可变原始回执
     * @param at        首次受理时刻
     */
    public record Command(String caller, String commandId, DraftKind kind, String subject,
                          String digest, String result, long at) {

        /** 校验字段。 */
        public Command {
            requireText(caller, "发起人");
            requireText(commandId, "命令标识");
            Objects.requireNonNull(kind, "动作种类");
            requireText(subject, "对象");
            requireText(digest, "语义摘要");
            requireText(result, "裁决");
            requireNonNegative(at, "受理时刻");
        }
    }

    /**
     * 冻结配置：预置群与六类正时长。
     *
     * @param groups          预置群
     * @param joinMillis      公开加入正工时
     * @param inviteMillis    创建邀请正工时
     * @param respondMillis   接受/拒绝邀请正工时
     * @param leaveMillis     退出正工时
     * @param postMillis      发帖正工时
     * @param deliveryMillis  完成到发布的等待，必须为正
     */
    public record Config(List<Group> groups, long joinMillis, long inviteMillis, long respondMillis,
                         long leaveMillis, long postMillis, long deliveryMillis) {

        /** 校验群唯一与所有时长。 */
        public Config {
            List<Group> copy = new ArrayList<>(groups == null ? List.of() : groups);
            for (Group group : copy) {
                Objects.requireNonNull(group, "群");
            }
            copy.sort(Comparator.comparing(Group::groupId));
            Set<String> ids = new HashSet<>();
            for (Group group : copy) {
                if (!ids.add(group.groupId())) {
                    throw new IllegalArgumentException("群标识重复：" + group.groupId());
                }
            }
            groups = List.copyOf(copy);
            requirePositive(joinMillis, "加入工时");
            requirePositive(inviteMillis, "邀请工时");
            requirePositive(respondMillis, "处理邀请工时");
            requirePositive(leaveMillis, "退出工时");
            requirePositive(postMillis, "发帖工时");
            requirePositive(deliveryMillis, "发布等待");
        }
    }

    /** 引用判定；由桥接层用真实阅读经历实现，纯核不伪造。 */
    public interface CitationOracle {

        /**
         * 判断某人在某时刻能否引用某文档版本。
         *
         * @param person 人物
         * @param docId  文档
         * @param version 版本
         * @param at     时刻
         * @return 可引用返回 true
         */
        boolean canCite(String person, String docId, long version, long at);
    }

    /**
     * 已知帖子版本的冻结来源解析；由受信世界桥从唯一版本谱系实现。
     *
     * <p>只对已在 {@code posts} 中找到的帖子版本调用。返回 {@code Optional.empty()} 表示该版本的
     * 来源元数据不可得，调用方必须拒绝，不能猜成 v1 的根或新的独立根；返回空列表表示该版本
     * 明确没有来源（自身就是独立陈述）。不得把来源字符串交给模型自填。</p>
     */
    public interface VersionOrigins {

        /** 无可信版本来源；已知帖的更正版引用会被拒绝。 */
        VersionOrigins NONE = (docId, version) -> Optional.empty();

        /**
         * 查询某文档版本冻结的来源根。
         *
         * @param docId   文档标识
         * @param version 版本
         * @return 来源根；元数据缺失返回空
         */
        Optional<List<Ref>> originsOf(String docId, long version);
    }

    /**
     * 本次动作的受信上下文。
     *
     * @param citations     引用判定
     * @param versionOrigins 已知帖版本的冻结来源
     * @param config        冻结配置
     */
    public record Context(CitationOracle citations, VersionOrigins versionOrigins, Config config) {

        /** 校验非空。 */
        public Context {
            Objects.requireNonNull(citations, "引用判定");
            Objects.requireNonNull(versionOrigins, "版本来源");
            Objects.requireNonNull(config, "配置");
        }

        /**
         * 旧两参构造：版本来源按 {@link VersionOrigins#NONE}，已知帖的更正版引用默认拒绝。
         *
         * @param citations 引用判定
         * @param config    冻结配置
         */
        public Context(CitationOracle citations, Config config) {
            this(citations, VersionOrigins.NONE, config);
        }
    }

    /**
     * 群组纯核状态。
     *
     * @param persons     开局登记人物
     * @param groups      预置群
     * @param memberships 成员段
     * @param invitations 邀请
     * @param posts       帖子元数据
     * @param postVersions 更正版本元数据；v1 仍在 posts
     * @param drafts      进行中动作
     * @param commands    命令记录
     * @param clock       本模块最后变更时刻；不是世界 now，查询可用更晚的 at
     */
    public record State(List<String> persons, List<Group> groups, List<Membership> memberships,
                        List<Invitation> invitations, List<Post> posts,
                        List<PostVersion> postVersions, List<Draft> drafts,
                        List<Command> commands, long clock) {

        /** 固定顺序并校验所有不变量。 */
        public State {
            persons = sortedUniqueText(persons, "已登记人物");
            groups = sortedGroups(groups);
            memberships = sortedCopy(memberships, Comparator.comparing(Membership::groupId)
                    .thenComparing(Membership::person).thenComparingLong(Membership::joinedAt));
            invitations = sortedCopy(invitations, Comparator.comparing(Invitation::inviteId));
            posts = sortedCopy(posts, Comparator.comparing(Post::postId));
            postVersions = sortedCopy(postVersions, Comparator.comparing(PostVersion::docId)
                    .thenComparingLong(PostVersion::version));
            drafts = sortedCopy(drafts, Comparator.comparing(Draft::caller)
                    .thenComparing(Draft::commandId));
            commands = sortedCopy(commands, Comparator.comparing(Command::caller)
                    .thenComparing(Command::commandId));
            requireNonNegative(clock, "世界时刻");
            validate(persons, groups, memberships, invitations, posts, postVersions, drafts,
                    commands, clock);
        }
    }

    /**
     * 开局：把配置中的群冻结进状态，并为每个群主建立一个开放成员段。
     *
     * <p>配置预置群必须有初始邀请人，否则 INVITE_ONLY 群无法产生第一条邀请；群主段使用系统命令
     * 标识，不占用人物工时，也不生成用户命令记录。</p>
     *
     * @param config  冻结配置
     * @param persons 开局登记人物
     * @return 空状态（含群主成员段）
     */
    public static State open(Config config, List<String> persons) {
        Objects.requireNonNull(config, "配置");
        List<String> people = sortedUniqueText(persons, "已登记人物");
        List<Membership> seeded = new ArrayList<>();
        long clock = 0L;
        for (Group group : config.groups()) {
            if (!people.contains(group.ownerPartyId())) {
                throw new IllegalArgumentException("群主未登记：" + group.ownerPartyId());
            }
            seeded.add(new Membership(group.groupId(), group.ownerPartyId(), group.createdAt(),
                    OPEN, "system:seed", "", ""));
            for (String member : group.initialMembers()) {
                if (!people.contains(member)) {
                    throw new IllegalArgumentException("初始成员未登记：" + member);
                }
                seeded.add(new Membership(group.groupId(), member, group.createdAt(),
                        OPEN, "system:endowed", "", ""));
            }
            clock = Math.max(clock, group.createdAt());
        }
        return new State(people, config.groups(), seeded, List.of(), List.of(), List.of(),
                List.of(), List.of(), clock);
    }

    /**
     * 统一读取门：当前成员段 + 历史策略 + 版本发布时间。
     *
     * @param state          当前状态
     * @param groupId        群
     * @param person         读者
     * @param docPublishedAt 所读版本的发布时刻
     * @param at             读取时刻
     * @return 允许新读取返回 true；未知、非成员或历史不允许返回 false
     */
    public static boolean canRead(State state, String groupId, String person,
                                  long docPublishedAt, long at) {
        requireState(state);
        requireText(groupId, "群");
        requireText(person, "人物");
        requireNonNegative(docPublishedAt, "版本发布时刻");
        requireNonNegative(at, "读取时刻");
        Group group = groupOf(state, groupId);
        if (group == null || !state.persons().contains(person)) {
            return false;
        }
        if (docPublishedAt < group.createdAt() || docPublishedAt > at) {
            return false;
        }
        Optional<Membership> segment = membershipAt(state, groupId, person, at);
        if (segment.isEmpty()) {
            return false;
        }
        if (group.historyPolicy() == HistoryPolicy.ALL_HISTORY) {
            return true;
        }
        return docPublishedAt >= segment.get().joinedAt();
    }

    /**
     * 某时刻的开放成员段。
     *
     * @param state   状态
     * @param groupId 群
     * @param person  人物
     * @param at      时刻
     * @return 开放段；没有返回空
     */
    public static Optional<Membership> membershipAt(State state, String groupId, String person,
                                                    long at) {
        requireState(state);
        requireText(groupId, "群");
        requireText(person, "人物");
        requireNonNegative(at, "时刻");
        for (Membership membership : state.memberships()) {
            if (membership.groupId().equals(groupId) && membership.person().equals(person)
                    && membership.joinedAt() <= at
                    && (membership.leftAt() == OPEN || at < membership.leftAt())) {
                return Optional.of(membership);
            }
        }
        return Optional.empty();
    }

    /**
     * 开始公开加入。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param caller    本人
     * @param groupId   群
     * @param commandId 命令
     * @param now       开始时刻
     * @return 结果
     */
    public static JoinOutcome beginJoin(State state, Context context, String caller, String groupId,
                                        String commandId, long now) {
        requireState(state);
        requireContext(context);
        requireText(caller, "本人");
        requireText(groupId, "群");
        requireText(commandId, "命令标识");
        requireNonNegative(now, "开始时刻");
        String digest = digest("JOIN", caller, groupId);
        Command recorded = commandOf(state, caller, commandId);
        if (recorded != null) {
            if (recorded.kind() != DraftKind.JOIN || !recorded.subject().equals(groupId)
                    || !recorded.digest().equals(digest)) {
                return new JoinOutcome(JoinStatus.COMMAND_CONFLICT, state);
            }
            return new JoinOutcome(JoinStatus.valueOf(recorded.result()), state);
        }
        if (!state.persons().contains(caller)) {
            return new JoinOutcome(JoinStatus.NOT_AVAILABLE, state);
        }
        if (now < state.clock()) {
            return new JoinOutcome(JoinStatus.TIME_REVERSED, state);
        }
        Group group = groupOf(state, groupId);
        if (group == null || now < group.createdAt()) {
            return new JoinOutcome(JoinStatus.NOT_AVAILABLE,
                    archive(state, caller, commandId, DraftKind.JOIN, groupId, digest,
                            JoinStatus.NOT_AVAILABLE, now));
        }
        if (membershipAt(state, groupId, caller, now).isPresent()) {
            return new JoinOutcome(JoinStatus.ALREADY_MEMBER,
                    archive(state, caller, commandId, DraftKind.JOIN, groupId, digest,
                            JoinStatus.ALREADY_MEMBER, now));
        }
        if (group.joinPolicy() == JoinPolicy.INVITE_ONLY) {
            return new JoinOutcome(JoinStatus.NOT_INVITED,
                    archive(state, caller, commandId, DraftKind.JOIN, groupId, digest,
                            JoinStatus.NOT_INVITED, now));
        }
        if (hasDraft(state, caller)) {
            return new JoinOutcome(JoinStatus.BUSY,
                    archive(state, caller, commandId, DraftKind.JOIN, groupId, digest,
                            JoinStatus.BUSY, now));
        }
        long due = safeAdd(now, context.config().joinMillis());
        if (due < 0) {
            return new JoinOutcome(JoinStatus.TIME_OVERFLOW,
                    archive(state, caller, commandId, DraftKind.JOIN, groupId, digest,
                            JoinStatus.TIME_OVERFLOW, now));
        }
        Draft draft = new Draft(commandId, DraftKind.JOIN, caller, groupId, caller, "", "",
                digest, List.of(), List.of(), "", 0L, false, now, due, OPEN);
        Command command = new Command(caller, commandId, DraftKind.JOIN, groupId, digest,
                JoinStatus.STARTED.name(), now);
        return new JoinOutcome(JoinStatus.STARTED,
                derive(state, state.memberships(), state.invitations(), state.posts(),
                        plusDraft(state, draft), plusCommand(state, command), now));
    }

    /**
     * 完成公开加入；到 due 才建立成员段。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param caller    本人
     * @param commandId 命令
     * @param now       处理时刻
     * @return 结果
     */
    public static JoinOutcome finishJoin(State state, Context context, String caller,
                                         String commandId, long now) {
        requireState(state);
        requireContext(context);
        requireText(caller, "本人");
        requireText(commandId, "命令标识");
        requireNonNegative(now, "处理时刻");
        Command command = commandOf(state, caller, commandId);
        if (command == null || command.kind() != DraftKind.JOIN) {
            return new JoinOutcome(JoinStatus.NOT_STARTED, state);
        }
        if (!command.result().equals(JoinStatus.STARTED.name())) {
            return new JoinOutcome(JoinStatus.valueOf(command.result()), state);
        }
        Draft draft = draftOf(state, caller, commandId, DraftKind.JOIN);
        if (draft == null) {
            return new JoinOutcome(JoinStatus.NOT_STARTED, state);
        }
        if (now < draft.dueAt()) {
            return new JoinOutcome(JoinStatus.NOT_DUE, state);
        }
        Group group = groupOf(state, draft.groupId());
        if (group == null || group.joinPolicy() != JoinPolicy.OPEN) {
            return new JoinOutcome(JoinStatus.NOT_INVITED,
                    finishMutation(state, draft, JoinStatus.NOT_INVITED.name(), now,
                            state.memberships(), state.invitations(), state.posts()));
        }
        if (membershipAt(state, draft.groupId(), caller, draft.dueAt()).isPresent()) {
            return new JoinOutcome(JoinStatus.ALREADY_MEMBER,
                    finishMutation(state, draft, JoinStatus.ALREADY_MEMBER.name(), now,
                            state.memberships(), state.invitations(), state.posts()));
        }
        Membership membership = new Membership(draft.groupId(), caller, draft.dueAt(), OPEN,
                commandId, "", "");
        List<Membership> memberships = new ArrayList<>(state.memberships());
        memberships.add(membership);
        return new JoinOutcome(JoinStatus.JOINED,
                finishMutation(state, draft, JoinStatus.JOINED.name(), now,
                        memberships, state.invitations(), state.posts()));
    }

    /**
     * 开始创建邀请。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param inviter   邀请人
     * @param groupId   群
     * @param invitee   受邀人
     * @param commandId 命令
     * @param now       开始时刻
     * @return 结果
     */
    public static InviteOutcome beginInvite(State state, Context context, String inviter,
                                            String groupId, String invitee, String commandId,
                                            long now) {
        requireState(state);
        requireContext(context);
        requireText(inviter, "邀请人");
        requireText(groupId, "群");
        requireText(invitee, "受邀人");
        requireText(commandId, "命令标识");
        requireNonNegative(now, "开始时刻");
        String inviteId = inviteId(inviter, commandId);
        String digest = digest("INVITE", inviter, groupId, invitee, inviteId);
        Command recorded = commandOf(state, inviter, commandId);
        if (recorded != null) {
            if (recorded.kind() != DraftKind.INVITE || !recorded.subject().equals(inviteId)
                    || !recorded.digest().equals(digest)) {
                return new InviteOutcome(InviteStatus.COMMAND_CONFLICT, state, "");
            }
            return new InviteOutcome(InviteStatus.valueOf(recorded.result()), state, inviteId);
        }
        if (!state.persons().contains(inviter)) {
            return new InviteOutcome(InviteStatus.NOT_AVAILABLE, state, "");
        }
        if (now < state.clock()) {
            return new InviteOutcome(InviteStatus.TIME_REVERSED, state, "");
        }
        Group group = groupOf(state, groupId);
        if (group == null || now < group.createdAt()
                || membershipAt(state, groupId, inviter, now).isEmpty()) {
            return new InviteOutcome(InviteStatus.NOT_AVAILABLE,
                    archive(state, inviter, commandId, DraftKind.INVITE, inviteId, digest,
                            InviteStatus.NOT_AVAILABLE, now), "");
        }
        if (!state.persons().contains(invitee) || inviter.equals(invitee)) {
            return new InviteOutcome(InviteStatus.NOT_AVAILABLE,
                    archive(state, inviter, commandId, DraftKind.INVITE, inviteId, digest,
                            InviteStatus.NOT_AVAILABLE, now), "");
        }
        if (membershipAt(state, groupId, invitee, now).isPresent()) {
            return new InviteOutcome(InviteStatus.ALREADY_MEMBER,
                    archive(state, inviter, commandId, DraftKind.INVITE, inviteId, digest,
                            InviteStatus.ALREADY_MEMBER, now), "");
        }
        if (pendingInvitation(state, groupId, invitee) != null) {
            return new InviteOutcome(InviteStatus.ALREADY_INVITED,
                    archive(state, inviter, commandId, DraftKind.INVITE, inviteId, digest,
                            InviteStatus.ALREADY_INVITED, now), "");
        }
        if (hasDraft(state, inviter)) {
            return new InviteOutcome(InviteStatus.BUSY,
                    archive(state, inviter, commandId, DraftKind.INVITE, inviteId, digest,
                            InviteStatus.BUSY, now), "");
        }
        long due = safeAdd(now, context.config().inviteMillis());
        if (due < 0) {
            return new InviteOutcome(InviteStatus.TIME_OVERFLOW,
                    archive(state, inviter, commandId, DraftKind.INVITE, inviteId, digest,
                            InviteStatus.TIME_OVERFLOW, now), "");
        }
        Draft draft = new Draft(commandId, DraftKind.INVITE, inviter, groupId, invitee, inviteId,
                "", digest, List.of(), List.of(), "", 0L, false, now, due, OPEN);
        Command command = new Command(inviter, commandId, DraftKind.INVITE, inviteId, digest,
                InviteStatus.STARTED.name(), now);
        return new InviteOutcome(InviteStatus.STARTED,
                derive(state, state.memberships(), state.invitations(), state.posts(),
                        plusDraft(state, draft), plusCommand(state, command), now), inviteId);
    }

    /**
     * 完成邀请创建；到期再核邀请人仍是开放成员。
     *
     * <p>begin 失败后 finish 返回当前状态码而非 begin 原裁决（例如 begin 的 NOT_AVAILABLE 与
     * finish 的 NOT_AUTHORIZED 不互相冒充）；原始 begin 回执由调用方/世界任务保存。</p>
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param inviter   邀请人
     * @param commandId 命令
     * @param now       处理时刻
     * @return 结果
     */
    public static InviteOutcome finishInvite(State state, Context context, String inviter,
                                             String commandId, long now) {
        requireState(state);
        requireContext(context);
        requireText(inviter, "邀请人");
        requireText(commandId, "命令标识");
        requireNonNegative(now, "处理时刻");
        Command command = commandOf(state, inviter, commandId);
        if (command == null || command.kind() != DraftKind.INVITE) {
            return new InviteOutcome(InviteStatus.NOT_STARTED, state, "");
        }
        if (!command.result().equals(InviteStatus.STARTED.name())) {
            return new InviteOutcome(InviteStatus.valueOf(command.result()), state,
                    command.subject());
        }
        Draft draft = draftOf(state, inviter, commandId, DraftKind.INVITE);
        if (draft == null) {
            return new InviteOutcome(InviteStatus.NOT_STARTED, state, command.subject());
        }
        if (now < draft.dueAt()) {
            return new InviteOutcome(InviteStatus.NOT_DUE, state, draft.inviteId());
        }
        if (membershipAt(state, draft.groupId(), inviter, draft.dueAt()).isEmpty()) {
            return new InviteOutcome(InviteStatus.NOT_AUTHORIZED,
                    finishMutation(state, draft, InviteStatus.NOT_AUTHORIZED.name(), now,
                            state.memberships(), state.invitations(), state.posts()),
                    draft.inviteId());
        }
        if (membershipAt(state, draft.groupId(), draft.target(), draft.dueAt()).isPresent()) {
            return new InviteOutcome(InviteStatus.ALREADY_MEMBER,
                    finishMutation(state, draft, InviteStatus.ALREADY_MEMBER.name(), now,
                            state.memberships(), state.invitations(), state.posts()),
                    draft.inviteId());
        }
        if (pendingInvitation(state, draft.groupId(), draft.target()) != null) {
            return new InviteOutcome(InviteStatus.ALREADY_INVITED,
                    finishMutation(state, draft, InviteStatus.ALREADY_INVITED.name(), now,
                            state.memberships(), state.invitations(), state.posts()),
                    draft.inviteId());
        }
        Invitation invitation = new Invitation(draft.inviteId(), draft.groupId(), inviter,
                draft.target(), draft.dueAt(), InvitationStatus.PENDING, -1, commandId, "");
        List<Invitation> invitations = new ArrayList<>(state.invitations());
        invitations.add(invitation);
        return new InviteOutcome(InviteStatus.INVITED,
                finishMutation(state, draft, InviteStatus.INVITED.name(), now,
                        state.memberships(), invitations, state.posts()), draft.inviteId());
    }

    /**
     * 开始接受或拒绝邀请。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param caller    受邀人本人
     * @param inviteId  邀请标识
     * @param accept    true 接受，false 拒绝
     * @param commandId 命令
     * @param now       开始时刻
     * @return 结果
     */
    public static RespondOutcome beginRespond(State state, Context context, String caller,
                                              String inviteId, boolean accept, String commandId,
                                              long now) {
        requireState(state);
        requireContext(context);
        requireText(caller, "受邀人");
        requireText(inviteId, "邀请标识");
        requireText(commandId, "命令标识");
        requireNonNegative(now, "开始时刻");
        String digest = digest("RESPOND", caller, inviteId, accept ? "ACCEPT" : "DECLINE");
        Command recorded = commandOf(state, caller, commandId);
        if (recorded != null) {
            if (recorded.kind() != DraftKind.RESPOND || !recorded.subject().equals(inviteId)
                    || !recorded.digest().equals(digest)) {
                return new RespondOutcome(RespondStatus.COMMAND_CONFLICT, state);
            }
            return new RespondOutcome(RespondStatus.valueOf(recorded.result()), state);
        }
        if (!state.persons().contains(caller)) {
            return new RespondOutcome(RespondStatus.NOT_AVAILABLE, state);
        }
        if (now < state.clock()) {
            return new RespondOutcome(RespondStatus.TIME_REVERSED, state);
        }
        Invitation invitation = invitationOf(state, inviteId);
        if (invitation == null || invitation.status() != InvitationStatus.PENDING
                || !invitation.invitee().equals(caller)) {
            return new RespondOutcome(RespondStatus.NOT_INVITED,
                    archive(state, caller, commandId, DraftKind.RESPOND, inviteId, digest,
                            RespondStatus.NOT_INVITED, now));
        }
        if (accept && membershipAt(state, invitation.groupId(), caller, now).isPresent()) {
            return new RespondOutcome(RespondStatus.ALREADY_MEMBER,
                    archive(state, caller, commandId, DraftKind.RESPOND, inviteId, digest,
                            RespondStatus.ALREADY_MEMBER, now));
        }
        if (hasDraft(state, caller)) {
            return new RespondOutcome(RespondStatus.BUSY,
                    archive(state, caller, commandId, DraftKind.RESPOND, inviteId, digest,
                            RespondStatus.BUSY, now));
        }
        long due = safeAdd(now, context.config().respondMillis());
        if (due < 0) {
            return new RespondOutcome(RespondStatus.TIME_OVERFLOW,
                    archive(state, caller, commandId, DraftKind.RESPOND, inviteId, digest,
                            RespondStatus.TIME_OVERFLOW, now));
        }
        Draft draft = new Draft(commandId, DraftKind.RESPOND, caller, invitation.groupId(), caller,
                inviteId, "", digest, List.of(), List.of(), "", 0L, accept, now, due, OPEN);
        Command command = new Command(caller, commandId, DraftKind.RESPOND, inviteId, digest,
                RespondStatus.STARTED.name(), now);
        return new RespondOutcome(RespondStatus.STARTED,
                derive(state, state.memberships(), state.invitations(), state.posts(),
                        plusDraft(state, draft), plusCommand(state, command), now));
    }

    /**
     * 完成接受或拒绝邀请。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param caller    受邀人本人
     * @param commandId 命令
     * @param now       处理时刻
     * @return 结果
     */
    public static RespondOutcome finishRespond(State state, Context context, String caller,
                                               String commandId, long now) {
        requireState(state);
        requireContext(context);
        requireText(caller, "受邀人");
        requireText(commandId, "命令标识");
        requireNonNegative(now, "处理时刻");
        Command command = commandOf(state, caller, commandId);
        if (command == null || command.kind() != DraftKind.RESPOND) {
            return new RespondOutcome(RespondStatus.NOT_STARTED, state);
        }
        if (!command.result().equals(RespondStatus.STARTED.name())) {
            return new RespondOutcome(RespondStatus.valueOf(command.result()), state);
        }
        Draft draft = draftOf(state, caller, commandId, DraftKind.RESPOND);
        if (draft == null) {
            return new RespondOutcome(RespondStatus.NOT_STARTED, state);
        }
        if (now < draft.dueAt()) {
            return new RespondOutcome(RespondStatus.NOT_DUE, state);
        }
        Invitation invitation = invitationOf(state, draft.inviteId());
        if (invitation == null || invitation.status() != InvitationStatus.PENDING
                || !invitation.invitee().equals(caller)) {
            return new RespondOutcome(RespondStatus.NOT_INVITED,
                    finishMutation(state, draft, RespondStatus.NOT_INVITED.name(), now,
                            state.memberships(), state.invitations(), state.posts()));
        }
        if (draft.accept()) {
            if (membershipAt(state, invitation.groupId(), caller, draft.dueAt()).isPresent()) {
                return new RespondOutcome(RespondStatus.ALREADY_MEMBER,
                        finishMutation(state, draft, RespondStatus.ALREADY_MEMBER.name(), now,
                                state.memberships(), state.invitations(), state.posts()));
            }
            Membership membership = new Membership(invitation.groupId(), caller, draft.dueAt(),
                    OPEN, commandId, "", invitation.inviteId());
            List<Membership> memberships = new ArrayList<>(state.memberships());
            memberships.add(membership);
            List<Invitation> invitations = replaceInvitation(state.invitations(), invitation,
                    new Invitation(invitation.inviteId(), invitation.groupId(), invitation.inviter(),
                            invitation.invitee(), invitation.invitedAt(), InvitationStatus.ACCEPTED,
                            draft.dueAt(), invitation.createCommandId(), commandId));
            return new RespondOutcome(RespondStatus.ACCEPTED,
                    finishMutation(state, draft, RespondStatus.ACCEPTED.name(), now,
                            memberships, invitations, state.posts()));
        }
        List<Invitation> invitations = replaceInvitation(state.invitations(), invitation,
                new Invitation(invitation.inviteId(), invitation.groupId(), invitation.inviter(),
                        invitation.invitee(), invitation.invitedAt(), InvitationStatus.DECLINED,
                        draft.dueAt(), invitation.createCommandId(), commandId));
        return new RespondOutcome(RespondStatus.DECLINED,
                finishMutation(state, draft, RespondStatus.DECLINED.name(), now,
                        state.memberships(), invitations, state.posts()));
    }

    /**
     * 开始退出。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param caller    本人
     * @param groupId   群
     * @param commandId 命令
     * @param now       开始时刻
     * @return 结果
     */
    public static LeaveOutcome beginLeave(State state, Context context, String caller,
                                          String groupId, String commandId, long now) {
        requireState(state);
        requireContext(context);
        requireText(caller, "本人");
        requireText(groupId, "群");
        requireText(commandId, "命令标识");
        requireNonNegative(now, "开始时刻");
        String digest = digest("LEAVE", caller, groupId);
        Command recorded = commandOf(state, caller, commandId);
        if (recorded != null) {
            if (recorded.kind() != DraftKind.LEAVE || !recorded.subject().equals(groupId)
                    || !recorded.digest().equals(digest)) {
                return new LeaveOutcome(LeaveStatus.COMMAND_CONFLICT, state);
            }
            return new LeaveOutcome(LeaveStatus.valueOf(recorded.result()), state);
        }
        if (!state.persons().contains(caller)) {
            return new LeaveOutcome(LeaveStatus.NOT_MEMBER, state);
        }
        if (now < state.clock()) {
            return new LeaveOutcome(LeaveStatus.TIME_REVERSED, state);
        }
        if (groupOf(state, groupId) == null || membershipAt(state, groupId, caller, now).isEmpty()) {
            return new LeaveOutcome(LeaveStatus.NOT_MEMBER,
                    archive(state, caller, commandId, DraftKind.LEAVE, groupId, digest,
                            LeaveStatus.NOT_MEMBER, now));
        }
        if (hasDraft(state, caller)) {
            return new LeaveOutcome(LeaveStatus.BUSY,
                    archive(state, caller, commandId, DraftKind.LEAVE, groupId, digest,
                            LeaveStatus.BUSY, now));
        }
        long due = safeAdd(now, context.config().leaveMillis());
        if (due < 0) {
            return new LeaveOutcome(LeaveStatus.TIME_OVERFLOW,
                    archive(state, caller, commandId, DraftKind.LEAVE, groupId, digest,
                            LeaveStatus.TIME_OVERFLOW, now));
        }
        Draft draft = new Draft(commandId, DraftKind.LEAVE, caller, groupId, caller, "", "", digest,
                List.of(), List.of(), "", 0L, false, now, due, OPEN);
        Command command = new Command(caller, commandId, DraftKind.LEAVE, groupId, digest,
                LeaveStatus.STARTED.name(), now);
        return new LeaveOutcome(LeaveStatus.STARTED,
                derive(state, state.memberships(), state.invitations(), state.posts(),
                        plusDraft(state, draft), plusCommand(state, command), now));
    }

    /**
     * 完成退出；到 due 才关闭成员段。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param caller    本人
     * @param commandId 命令
     * @param now       处理时刻
     * @return 结果
     */
    public static LeaveOutcome finishLeave(State state, Context context, String caller,
                                           String commandId, long now) {
        requireState(state);
        requireContext(context);
        requireText(caller, "本人");
        requireText(commandId, "命令标识");
        requireNonNegative(now, "处理时刻");
        Command command = commandOf(state, caller, commandId);
        if (command == null || command.kind() != DraftKind.LEAVE) {
            return new LeaveOutcome(LeaveStatus.NOT_STARTED, state);
        }
        if (!command.result().equals(LeaveStatus.STARTED.name())) {
            return new LeaveOutcome(LeaveStatus.valueOf(command.result()), state);
        }
        Draft draft = draftOf(state, caller, commandId, DraftKind.LEAVE);
        if (draft == null) {
            return new LeaveOutcome(LeaveStatus.NOT_STARTED, state);
        }
        if (now < draft.dueAt()) {
            return new LeaveOutcome(LeaveStatus.NOT_DUE, state);
        }
        Optional<Membership> current = membershipAt(state, draft.groupId(), caller, draft.dueAt());
        if (current.isEmpty() || draft.dueAt() <= current.get().joinedAt()) {
            return new LeaveOutcome(LeaveStatus.NOT_MEMBER,
                    finishMutation(state, draft, LeaveStatus.NOT_MEMBER.name(), now,
                            state.memberships(), state.invitations(), state.posts()));
        }
        Membership closed = new Membership(current.get().groupId(), current.get().person(),
                current.get().joinedAt(), draft.dueAt(), current.get().joinCommandId(), commandId,
                current.get().inviteId());
        List<Membership> memberships = replaceMembership(state.memberships(), current.get(), closed);
        return new LeaveOutcome(LeaveStatus.LEFT,
                finishMutation(state, draft, LeaveStatus.LEFT.name(), now,
                        memberships, state.invitations(), state.posts()));
    }

    /**
     * 开始发帖或结构化转发。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param caller    作者
     * @param groupId   群
     * @param commandId 命令
     * @param title     标题
     * @param body      正文；只进待办 payload，不进状态
     * @param originRefs 直接引用；原创为空
     * @param now       开始时刻
     * @return 结果与待发布 payload
     */
    public static PostBeginOutcome beginPost(State state, Context context, String caller,
                                             String groupId, String commandId, String title,
                                             String body, List<Ref> originRefs, long now) {
        requireState(state);
        requireContext(context);
        requireText(caller, "作者");
        requireText(groupId, "群");
        requireText(commandId, "命令标识");
        requireText(title, "标题");
        requireText(body, "正文");
        requireNonNegative(now, "开始时刻");
        List<Ref> direct = canonicalRefs(originRefs);
        String postId = postId(caller, commandId);
        String docId = docIdFor(postId);
        String digest = digest("POST", caller, groupId, title, body, refsKey(direct));
        Command recorded = commandOf(state, caller, commandId);
        if (recorded != null) {
            if (recorded.kind() != DraftKind.POST || !recorded.subject().equals(postId)
                    || !recorded.digest().equals(digest)) {
                return new PostBeginOutcome(PostBeginStatus.COMMAND_CONFLICT, state, null);
            }
            PostBeginStatus replay = switch (recorded.result()) {
                case "STARTED" -> PostBeginStatus.STARTED;
                case "QUEUED" -> PostBeginStatus.QUEUED;
                case "ABORTED" -> PostBeginStatus.ABORTED;
                case "PUBLISHED" -> PostBeginStatus.PUBLISHED;
                case "NOT_AVAILABLE" -> PostBeginStatus.NOT_AVAILABLE;
                case "NOT_CITABLE" -> PostBeginStatus.NOT_CITABLE;
                case "NO_VERSION_SOURCE" -> PostBeginStatus.NO_VERSION_SOURCE;
                case "BUSY" -> PostBeginStatus.BUSY;
                case "TIME_OVERFLOW" -> PostBeginStatus.TIME_OVERFLOW;
                default -> PostBeginStatus.COMMAND_CONFLICT;
            };
            Draft replayDraft = replay == PostBeginStatus.STARTED
                    ? draftOf(state, caller, commandId, DraftKind.POST) : null;
            PendingPayload payload = replayDraft == null ? null
                    : new PendingPayload(postId, docId, groupId, caller, title, body,
                            replayDraft.declaredRefs(), replayDraft.originRefs());
            return new PostBeginOutcome(replay, state, payload);
        }
        if (!state.persons().contains(caller)) {
            return new PostBeginOutcome(PostBeginStatus.NOT_AVAILABLE, state, null);
        }
        if (now < state.clock()) {
            return new PostBeginOutcome(PostBeginStatus.TIME_REVERSED, state, null);
        }
        Group group = groupOf(state, groupId);
        if (group == null || now < group.createdAt()
                || membershipAt(state, groupId, caller, now).isEmpty()) {
            return new PostBeginOutcome(PostBeginStatus.NOT_AVAILABLE,
                    archive(state, caller, commandId, DraftKind.POST, postId, digest,
                            PostBeginStatus.NOT_AVAILABLE, now), null);
        }
        if (hasDraft(state, caller)) {
            return new PostBeginOutcome(PostBeginStatus.BUSY,
                    archive(state, caller, commandId, DraftKind.POST, postId, digest,
                            PostBeginStatus.BUSY, now), null);
        }
        for (Ref ref : direct) {
            if (!context.citations().canCite(caller, ref.docId(), ref.version(), now)) {
                return new PostBeginOutcome(PostBeginStatus.NOT_CITABLE,
                        archive(state, caller, commandId, DraftKind.POST, postId, digest,
                                PostBeginStatus.NOT_CITABLE, now), null);
            }
        }
        List<Ref> roots;
        try {
            roots = inheritedRoots(state, context, direct);
        } catch (MissingVersionOrigins missing) {
            return new PostBeginOutcome(PostBeginStatus.NO_VERSION_SOURCE,
                    archive(state, caller, commandId, DraftKind.POST, postId, digest,
                            PostBeginStatus.NO_VERSION_SOURCE, now), null);
        }
        long finishedAt = safeAdd(now, context.config().postMillis());
        if (finishedAt < 0) {
            return new PostBeginOutcome(PostBeginStatus.TIME_OVERFLOW,
                    archive(state, caller, commandId, DraftKind.POST, postId, digest,
                            PostBeginStatus.TIME_OVERFLOW, now), null);
        }
        long publishAt = safeAdd(finishedAt, context.config().deliveryMillis());
        if (publishAt < 0) {
            return new PostBeginOutcome(PostBeginStatus.TIME_OVERFLOW,
                    archive(state, caller, commandId, DraftKind.POST, postId, digest,
                            PostBeginStatus.TIME_OVERFLOW, now), null);
        }
        Draft draft = new Draft(commandId, DraftKind.POST, caller, groupId, caller, "", postId,
                digest, direct, roots, "", 0L, false, now, finishedAt, publishAt);
        Command command = new Command(caller, commandId, DraftKind.POST, postId, digest,
                PostBeginStatus.STARTED.name(), now);
        PendingPayload payload = new PendingPayload(postId, docId, groupId, caller, title, body,
                direct, roots);
        return new PostBeginOutcome(PostBeginStatus.STARTED,
                derive(state, state.memberships(), state.invitations(), state.posts(),
                        plusDraft(state, draft), plusCommand(state, command), now), payload);
    }

    /**
     * 完成发帖；在 finishedAt 复核作者成员资格。
     *
     * <p>若 begin 已失败，finish 返回当前映射状态（如 NOT_AVAILABLE），不重放 begin 原裁决；
     * 原始 begin 回执需由调用方/世界任务保存。合法 begin 的 finish 才返回 QUEUED/ABORTED。</p>
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param caller    作者
     * @param commandId 命令
     * @param now       处理时刻
     * @return 结果
     */
    public static PostFinishOutcome finishPost(State state, Context context, String caller,
                                               String commandId, long now) {
        requireState(state);
        requireContext(context);
        requireText(caller, "作者");
        requireText(commandId, "命令标识");
        requireNonNegative(now, "处理时刻");
        Command command = commandOf(state, caller, commandId);
        if (command == null || command.kind() != DraftKind.POST) {
            return new PostFinishOutcome(PostFinishStatus.NOT_STARTED, state, null);
        }
        if (!command.result().equals(PostBeginStatus.STARTED.name())) {
            try {
                PostFinishStatus replay = switch (PostFinishStatus.valueOf(command.result())) {
                    case QUEUED, ABORTED, PUBLISHED -> PostFinishStatus.valueOf(command.result());
                    default -> PostFinishStatus.NOT_AVAILABLE;
                };
                return new PostFinishOutcome(replay, state, null);
            } catch (IllegalArgumentException invalid) {
                return new PostFinishOutcome(PostFinishStatus.NOT_AVAILABLE, state, null);
            }
        }
        Draft draft = draftOf(state, caller, commandId, DraftKind.POST);
        if (draft == null) {
            return new PostFinishOutcome(PostFinishStatus.NOT_STARTED, state, null);
        }
        if (now < draft.dueAt()) {
            return new PostFinishOutcome(PostFinishStatus.NOT_DUE, state, null);
        }
        Group group = groupOf(state, draft.groupId());
        if (group == null) {
            return new PostFinishOutcome(PostFinishStatus.NOT_AVAILABLE,
                    finishMutation(state, draft, PostFinishStatus.NOT_AVAILABLE.name(), now,
                            state.memberships(), state.invitations(), state.posts()), null);
        }
        boolean memberAtFinish = membershipAt(state, draft.groupId(), caller, draft.dueAt())
                .isPresent();
        PostStatus status = memberAtFinish ? PostStatus.QUEUED : PostStatus.ABORTED;
        Post post = new Post(draft.postId(), docIdFor(draft.postId()), draft.groupId(), caller,
                draft.digest(), draft.declaredRefs(), draft.originRefs(), draft.startedAt(),
                draft.dueAt(), memberAtFinish ? draft.publishAt() : draft.dueAt(), OPEN, status,
                commandId);
        PostFinishStatus finish = memberAtFinish ? PostFinishStatus.QUEUED
                : PostFinishStatus.ABORTED;
        return new PostFinishOutcome(finish,
                finishMutation(state, draft, finish.name(), now, state.memberships(),
                        state.invitations(), plusPost(state, post)), post);
    }

    /**
     * 按计划发布；不再核作者成员资格，只执行冻结意图。
     *
     * @param state  当前状态
     * @param postId 帖子
     * @param now    处理时刻
     * @return 结果
     */
    public static PublishOutcome publishPost(State state, String postId, long now) {
        requireState(state);
        requireText(postId, "帖子标识");
        requireNonNegative(now, "处理时刻");
        Post post = postOf(state, postId);
        if (post == null) {
            return new PublishOutcome(PublishStatus.NOT_AVAILABLE, state);
        }
        if (post.status() == PostStatus.PUBLISHED) {
            return new PublishOutcome(PublishStatus.ALREADY_PUBLISHED, state);
        }
        if (post.status() == PostStatus.ABORTED) {
            return new PublishOutcome(PublishStatus.ABORTED, state);
        }
        if (now < post.publishAt()) {
            return new PublishOutcome(PublishStatus.NOT_DUE, state);
        }
        Post published = new Post(post.postId(), post.docId(), post.groupId(), post.author(),
                post.contentDigest(), post.declaredRefs(), post.originRefs(), post.startedAt(),
                post.finishedAt(), post.publishAt(), post.publishAt(), PostStatus.PUBLISHED,
                post.commandId());
        List<Post> posts = replacePost(state.posts(), post, published);
        List<Command> commands = replaceCommandResult(state.commands(), post.author(),
                post.commandId(), PostBeginStatus.PUBLISHED.name());
        return new PublishOutcome(PublishStatus.PUBLISHED,
                derive(state, state.memberships(), state.invitations(), posts, state.drafts(),
                        commands, now));
    }

    /**
     * 开始更正本人已发布的群帖：追加新版本，不改旧版本与旧经历。
     *
     * <p>作者必须等于原帖作者，且在本帧是当前群成员；{@code basedOnVersion} 必须等于当前版本，
     * 否则返回 {@link CorrectionBeginStatus#STALE_VERSION} 并且不改任何状态。合法开始后，完成帧
     * 在 {@code due} 再复核作者与成员；合法完成后离群不影响 {@code publishAt} 的投递。</p>
     *
     * @param state          当前状态
     * @param context        受信上下文
     * @param caller         本人（作者）
     * @param docId          原帖文档
     * @param basedOnVersion 起草时看到的当前版本
     * @param commandId      命令
     * @param title          标题
     * @param body           正文
     * @param declaredRefs   本版直接引用的版本
     * @param now            开始时刻
     * @return 结果；被接受时带待提交负载
     */
    public static CorrectionBeginOutcome beginCorrection(State state, Context context,
                                                         String caller, String docId,
                                                         long basedOnVersion, String commandId,
                                                         String title, String body,
                                                         List<Ref> declaredRefs, long now) {
        requireState(state);
        requireContext(context);
        requireText(caller, "作者");
        requireText(docId, "文档标识");
        requireText(commandId, "命令标识");
        requireText(title, "标题");
        requireText(body, "正文");
        requireNonNegative(basedOnVersion, "基准版本");
        requireNonNegative(now, "开始时刻");
        List<Ref> direct = canonicalRefs(declaredRefs);
        long version = basedOnVersion + 1;
        Post post = postByDocId(state, docId);
        String groupId = post == null ? "" : post.groupId();
        String digest = versionDigest("POST_VERSION", caller, groupId, version, basedOnVersion,
                title, body, direct);
        Command recorded = commandOf(state, caller, commandId);
        if (recorded != null) {
            if (recorded.kind() != DraftKind.POST_VERSION || !recorded.subject().equals(docId)
                    || !recorded.digest().equals(digest)) {
                return new CorrectionBeginOutcome(CorrectionBeginStatus.COMMAND_CONFLICT, state,
                        null);
            }
            CorrectionBeginStatus replay = correctionBeginReplay(recorded.result());
            Draft replayDraft = replay == CorrectionBeginStatus.STARTED
                    ? draftOf(state, caller, commandId, DraftKind.POST_VERSION) : null;
            PendingCorrection payload = replayDraft == null ? null
                    : new PendingCorrection(docId, basedOnVersion + 1, replayDraft.groupId(),
                            caller, title, body, replayDraft.declaredRefs(),
                            replayDraft.originRefs());
            return new CorrectionBeginOutcome(replay, state, payload);
        }
        if (!state.persons().contains(caller)) {
            return new CorrectionBeginOutcome(CorrectionBeginStatus.NOT_AVAILABLE, state, null);
        }
        if (now < state.clock()) {
            return new CorrectionBeginOutcome(CorrectionBeginStatus.TIME_REVERSED, state, null);
        }
        if (post == null) {
            // 未知文档不写命令记录：避免为猜测的标识留下可被误读的审计行
            return new CorrectionBeginOutcome(CorrectionBeginStatus.NOT_AVAILABLE, state, null);
        }
        if (!post.author().equals(caller)) {
            return new CorrectionBeginOutcome(CorrectionBeginStatus.NOT_AUTHOR,
                    archive(state, caller, commandId, DraftKind.POST_VERSION, docId, digest,
                            CorrectionBeginStatus.NOT_AUTHOR, now), null);
        }
        long current = currentVersionOf(state, docId);
        if (basedOnVersion != current) {
            return new CorrectionBeginOutcome(CorrectionBeginStatus.STALE_VERSION,
                    archive(state, caller, commandId, DraftKind.POST_VERSION, docId, digest,
                            CorrectionBeginStatus.STALE_VERSION, now), null);
        }
        if (membershipAt(state, post.groupId(), caller, now).isEmpty()) {
            return new CorrectionBeginOutcome(CorrectionBeginStatus.NOT_MEMBER,
                    archive(state, caller, commandId, DraftKind.POST_VERSION, docId, digest,
                            CorrectionBeginStatus.NOT_MEMBER, now), null);
        }
        if (hasDraft(state, caller)) {
            return new CorrectionBeginOutcome(CorrectionBeginStatus.BUSY,
                    archive(state, caller, commandId, DraftKind.POST_VERSION, docId, digest,
                            CorrectionBeginStatus.BUSY, now), null);
        }
        for (Ref ref : direct) {
            if (!context.citations().canCite(caller, ref.docId(), ref.version(), now)) {
                return new CorrectionBeginOutcome(CorrectionBeginStatus.NOT_CITABLE,
                        archive(state, caller, commandId, DraftKind.POST_VERSION, docId, digest,
                                CorrectionBeginStatus.NOT_CITABLE, now), null);
            }
        }
        java.util.Optional<List<Ref>> roots;
        try {
            roots = rootsFor(state, context, direct);
        } catch (MissingVersionOrigins missing) {
            roots = java.util.Optional.empty();
        }
        if (roots.isEmpty()) {
            return new CorrectionBeginOutcome(CorrectionBeginStatus.NO_VERSION_SOURCE,
                    archive(state, caller, commandId, DraftKind.POST_VERSION, docId, digest,
                            CorrectionBeginStatus.NO_VERSION_SOURCE, now), null);
        }
        long finishedAt = safeAdd(now, context.config().postMillis());
        long publishAt = finishedAt < 0 ? -1L : safeAdd(finishedAt,
                context.config().deliveryMillis());
        if (finishedAt < 0 || publishAt < 0) {
            return new CorrectionBeginOutcome(CorrectionBeginStatus.TIME_OVERFLOW,
                    archive(state, caller, commandId, DraftKind.POST_VERSION, docId, digest,
                            CorrectionBeginStatus.TIME_OVERFLOW, now), null);
        }
        Draft draft = new Draft(commandId, DraftKind.POST_VERSION, caller, post.groupId(), caller,
                "", "", digest, direct, roots.get(), docId, basedOnVersion, false, now,
                finishedAt, publishAt);
        Command command = new Command(caller, commandId, DraftKind.POST_VERSION, docId, digest,
                CorrectionBeginStatus.STARTED.name(), now);
        PendingCorrection payload = new PendingCorrection(docId, version, post.groupId(), caller,
                title, body, direct, roots.get());
        return new CorrectionBeginOutcome(CorrectionBeginStatus.STARTED,
                derive(state, state.memberships(), state.invitations(), state.posts(),
                        plusDraft(state, draft), plusCommand(state, command), now), payload);
    }

    /**
     * 完成更正：在 {@code due} 复核作者身份与当前成员资格；通过则排队等待发布。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param caller    本人（作者）
     * @param commandId 命令
     * @param now       处理时刻
     * @return 结果
     */
    public static CorrectionFinishOutcome finishCorrection(State state, Context context,
                                                           String caller, String commandId,
                                                           long now) {
        requireState(state);
        requireContext(context);
        requireText(caller, "作者");
        requireText(commandId, "命令标识");
        requireNonNegative(now, "处理时刻");
        Command command = commandOf(state, caller, commandId);
        if (command == null || command.kind() != DraftKind.POST_VERSION) {
            return new CorrectionFinishOutcome(CorrectionFinishStatus.NOT_STARTED, state, null);
        }
        if (!command.result().equals(CorrectionBeginStatus.STARTED.name())) {
            // 不重放 begin 原裁决，也不把不存在于完成枚举的名字当完成状态
            return new CorrectionFinishOutcome(CorrectionFinishStatus.NOT_AVAILABLE, state, null);
        }
        Draft draft = draftOf(state, caller, commandId, DraftKind.POST_VERSION);
        if (draft == null) {
            return new CorrectionFinishOutcome(CorrectionFinishStatus.NOT_STARTED, state, null);
        }
        if (now < draft.dueAt()) {
            return new CorrectionFinishOutcome(CorrectionFinishStatus.NOT_DUE, state, null);
        }
        Post post = postByDocId(state, draft.docId());
        boolean eligible = post != null && post.author().equals(caller)
                && membershipAt(state, post.groupId(), caller, draft.dueAt()).isPresent();
        PostStatus status = eligible ? PostStatus.QUEUED : PostStatus.ABORTED;
        PostVersion version = new PostVersion(draft.docId(), draft.basedOnVersion() + 1,
                draft.basedOnVersion(), caller, draft.digest(), draft.declaredRefs(),
                draft.originRefs(), draft.groupId(), draft.startedAt(), draft.dueAt(),
                eligible ? draft.publishAt() : draft.dueAt(), OPEN, status, commandId);
        CorrectionFinishStatus finish = eligible ? CorrectionFinishStatus.QUEUED
                : CorrectionFinishStatus.ABORTED;
        return new CorrectionFinishOutcome(finish,
                finishCorrectionMutation(state, draft, finish.name(), now, version), version);
    }

    /**
     * 按计划发布更正版本；在真正发布帧原子核对版本基准，冲突则终止且不覆盖任何旧版本。
     *
     * <p>{@code documentVersionAtFrame} 由调用方从唯一 InformationModel 的真实文档读出（没有人直接
     * 提供 roots 或版本号）；只有等于 {@link PostVersion#basedOnVersion()} 才发布。冲突时该记录转
     * {@link PostStatus#ABORTED}，保留开始/完成时刻与原命令，便于审计已耗工时。</p>
     *
     * @param state                   当前状态
     * @param docId                   文档
     * @param version                 更正版本号
     * @param documentVersionAtFrame  发布帧的真实文档版本；文档不存在传 0
     * @param now                     处理时刻
     * @return 结果
     */
    public static CorrectionPublishOutcome publishCorrection(State state, String docId,
                                                             long version,
                                                             long documentVersionAtFrame,
                                                             long now) {
        requireState(state);
        requireText(docId, "文档标识");
        requireNonNegative(now, "处理时刻");
        PostVersion current = postVersionOf(state, docId, version);
        if (current == null) {
            return new CorrectionPublishOutcome(PublishStatus.NOT_AVAILABLE, state, null);
        }
        if (current.status() == PostStatus.PUBLISHED) {
            return new CorrectionPublishOutcome(PublishStatus.ALREADY_PUBLISHED, state, current);
        }
        if (current.status() == PostStatus.ABORTED) {
            return new CorrectionPublishOutcome(PublishStatus.ABORTED, state, current);
        }
        if (now < current.publishAt()) {
            return new CorrectionPublishOutcome(PublishStatus.NOT_DUE, state, current);
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
        PostVersion next = new PostVersion(current.docId(), current.version(),
                current.basedOnVersion(), current.author(), current.contentDigest(),
                current.declaredRefs(), current.originRefs(), current.groupId(),
                current.startedAt(), current.finishedAt(), publishAt, publishedAt, status,
                current.commandId());
        List<PostVersion> versions = replacePostVersion(state.postVersions(), current, next);
        List<Command> commands = replaceCommandResult(state.commands(), current.author(),
                current.commandId(), result);
        return new CorrectionPublishOutcome(PublishStatus.valueOf(result),
                deriveVersions(state, versions, state.drafts(), commands, now), next);
    }

    /**
     * 确定性帖子标识。
     *
     * @param caller    作者
     * @param commandId 命令
     * @return 帖子标识
     */
    public static String postId(String caller, String commandId) {
        requireText(caller, "作者");
        requireText(commandId, "命令标识");
        return POST_PREFIX + caller.length() + ":" + caller + ":" + commandId.length() + ":"
                + commandId;
    }

    /**
     * 由帖子标识派生文档标识。
     *
     * @param postId 帖子
     * @return 文档标识
     */
    public static String docIdFor(String postId) {
        requireText(postId, "帖子标识");
        return DOC_PREFIX + postId;
    }

    /**
     * 确定性邀请标识。
     *
     * @param inviter   邀请人
     * @param commandId 命令
     * @return 邀请标识
     */
    public static String inviteId(String inviter, String commandId) {
        requireText(inviter, "邀请人");
        requireText(commandId, "命令标识");
        return INVITE_PREFIX + inviter.length() + ":" + inviter + ":" + commandId.length() + ":"
                + commandId;
    }

    /**
     * 冻结为稳定 JSON。
     *
     * @param state 状态
     * @return JSON 文本
     */
    public static String toJson(State state) {
        requireState(state);
        try {
            return MAPPER.writeValueAsString(state);
        } catch (Exception failure) {
            throw new IllegalStateException("无法序列化群组状态", failure);
        }
    }

    /**
     * 从 JSON 恢复并重新校验全部不变量。
     *
     * @param json JSON 文本
     * @return 状态
     */
    public static State fromJson(String json) {
        requireText(json, "JSON");
        try {
            return MAPPER.readValue(json, State.class);
        } catch (Exception failure) {
            throw new IllegalArgumentException("群组状态 JSON 不合法", failure);
        }
    }

    // ---------------------------------------------------------------- 结果

    /**
     * 加入结果。
     *
     * @param status 加入状态
     * @param state  新状态
     */
    public record JoinOutcome(JoinStatus status, State state) {
        /**
         * 校验非空。
         *
         * @param status 加入状态
         * @param state  新状态
         */
        public JoinOutcome {
            Objects.requireNonNull(status, "加入状态");
            Objects.requireNonNull(state, "状态");
        }
    }

    /**
     * 邀请创建结果。
     *
     * @param status   邀请状态
     * @param state    新状态
     * @param inviteId 邀请标识
     */
    public record InviteOutcome(InviteStatus status, State state, String inviteId) {
        /**
         * 校验非空。
         *
         * @param status   邀请状态
         * @param state    新状态
         * @param inviteId 邀请标识
         */
        public InviteOutcome {
            Objects.requireNonNull(status, "邀请状态");
            Objects.requireNonNull(state, "状态");
            if (inviteId == null) {
                inviteId = "";
            }
        }
    }

    /**
     * 接受/拒绝结果。
     *
     * @param status 处理状态
     * @param state  新状态
     */
    public record RespondOutcome(RespondStatus status, State state) {
        /**
         * 校验非空。
         *
         * @param status 处理状态
         * @param state  新状态
         */
        public RespondOutcome {
            Objects.requireNonNull(status, "处理状态");
            Objects.requireNonNull(state, "状态");
        }
    }

    /**
     * 退出结果。
     *
     * @param status 退出状态
     * @param state  新状态
     */
    public record LeaveOutcome(LeaveStatus status, State state) {
        /**
         * 校验非空。
         *
         * @param status 退出状态
         * @param state  新状态
         */
        public LeaveOutcome {
            Objects.requireNonNull(status, "退出状态");
            Objects.requireNonNull(state, "状态");
        }
    }

    /**
     * 发帖开始结果。
     *
     * @param status  发帖状态
     * @param state   新状态
     * @param payload 待发布内容；未开始或重放时为 null
     */
    public record PostBeginOutcome(PostBeginStatus status, State state, PendingPayload payload) {
        /**
         * 校验状态非空；payload 仅成功时可能为 null。
         *
         * @param status  发帖状态
         * @param state   新状态
         * @param payload 待发布内容；未开始或重放时为 null
         */
        public PostBeginOutcome {
            Objects.requireNonNull(status, "发帖状态");
            Objects.requireNonNull(state, "状态");
        }
    }

    /**
     * 发帖完成结果。
     *
     * @param status 完成状态
     * @param state  新状态
     * @param post   帖子元数据；未完成时可能为 null
     */
    public record PostFinishOutcome(PostFinishStatus status, State state, Post post) {
        /**
         * 校验状态非空。
         *
         * @param status 完成状态
         * @param state  新状态
         * @param post   帖子元数据；未完成时可能为 null
         */
        public PostFinishOutcome {
            Objects.requireNonNull(status, "完成状态");
            Objects.requireNonNull(state, "状态");
        }
    }

    /**
     * 发布结果。
     *
     * @param status 发布状态
     * @param state  新状态
     */
    public record PublishOutcome(PublishStatus status, State state) {
        /**
         * 校验非空。
         *
         * @param status 发布状态
         * @param state  新状态
         */
        public PublishOutcome {
            Objects.requireNonNull(status, "发布状态");
            Objects.requireNonNull(state, "状态");
        }
    }

    /**
     * 更正开始结果。
     *
     * @param status  开始状态
     * @param state   新状态
     * @param payload 被接受时的待提交负载；其他情况为 null
     */
    public record CorrectionBeginOutcome(CorrectionBeginStatus status, State state,
                                         PendingCorrection payload) {
        /** 校验字段。 */
        public CorrectionBeginOutcome {
            Objects.requireNonNull(status, "更正开始状态");
            Objects.requireNonNull(state, "状态");
            if (status != CorrectionBeginStatus.STARTED && payload != null) {
                throw new IllegalArgumentException("未开始的更正不得携带负载");
            }
        }
    }

    /**
     * 更正完成结果。
     *
     * @param status  完成状态
     * @param state   新状态
     * @param version 已排队的更正版本；未通过为 null
     */
    public record CorrectionFinishOutcome(CorrectionFinishStatus status, State state,
                                          PostVersion version) {
        /** 校验字段。 */
        public CorrectionFinishOutcome {
            Objects.requireNonNull(status, "更正完成状态");
            Objects.requireNonNull(state, "状态");
            if (status == CorrectionFinishStatus.QUEUED && version == null) {
                throw new IllegalArgumentException("排队更正缺少版本记录");
            }
        }
    }

    /**
     * 更正发布结果。
     *
     * @param status  发布状态，含 STALE_VERSION
     * @param state   新状态
     * @param version 发布或终止后的版本记录；未知版本为 null
     */
    public record CorrectionPublishOutcome(PublishStatus status, State state,
                                           PostVersion version) {
        /** 校验字段。 */
        public CorrectionPublishOutcome {
            Objects.requireNonNull(status, "更正发布状态");
            Objects.requireNonNull(state, "状态");
        }
    }

    /**
     * 更正待提交负载；标题与正文只交给桥接层，不进本模块状态。
     *
     * @param docId        原帖文档
     * @param version      新版本号
     * @param groupId      群
     * @param author       作者
     * @param title        标题
     * @param body         正文
     * @param declaredRefs 直接来源
     * @param originRefs   规范根
     */
    public record PendingCorrection(String docId, long version, String groupId, String author,
                                    String title, String body, List<Ref> declaredRefs,
                                    List<Ref> originRefs) {
        /** 校验字段并冻结根集合。 */
        public PendingCorrection {
            requireText(docId, "文档标识");
            if (version < 2) {
                throw new IllegalArgumentException("更正负载版本必须从 2 开始");
            }
            requireText(groupId, "群");
            requireText(author, "作者");
            requireText(title, "标题");
            requireText(body, "正文");
            if (declaredRefs == null) {
                throw new IllegalArgumentException("更正负载缺少直接来源");
            }
            declaredRefs = canonicalRefs(declaredRefs);
            originRefs = canonicalRefs(originRefs);
        }
    }

    /**
     * 待发布 payload；只交给桥接层，不持久化第二份正文。
     *
     * @param postId     帖子
     * @param docId      文档
     * @param groupId    群
     * @param author     作者
     * @param title      标题
     * @param body       正文
     * @param declaredRefs 作者直接引用的版本
     * @param originRefs 规范来源根
     */
    public record PendingPayload(String postId, String docId, String groupId, String author,
                                 String title, String body, List<Ref> declaredRefs,
                                 List<Ref> originRefs) {

        /** 校验并冻结根集合。 */
        public PendingPayload {
            requireText(postId, "帖子标识");
            requireText(docId, "文档标识");
            requireText(groupId, "群");
            requireText(author, "作者");
            requireText(title, "标题");
            requireText(body, "正文");
            if (declaredRefs == null) {
                throw new IllegalArgumentException("待发布负载缺少直接来源");
            }
            declaredRefs = canonicalRefs(declaredRefs);
            originRefs = canonicalRefs(originRefs);
        }
    }

    // ---------------------------------------------------------------- 内部

    private static void validate(List<String> persons, List<Group> groups,
                                 List<Membership> memberships, List<Invitation> invitations,
                                 List<Post> posts, List<PostVersion> postVersions,
                                 List<Draft> drafts, List<Command> commands,
                                 long clock) {
        Set<String> people = new HashSet<>(persons);
        Map<String, Group> groupById = new HashMap<>();
        for (Group group : groups) {
            if (groupById.put(group.groupId(), group) != null) {
                throw new IllegalArgumentException("群标识重复：" + group.groupId());
            }
            if (!people.contains(group.ownerPartyId())) {
                throw new IllegalArgumentException("群主未登记：" + group.ownerPartyId());
            }
        }
        Map<String, Membership> lastByKey = new HashMap<>();
        Map<String, Membership> membershipByInvite = new HashMap<>();
        Map<String, Membership> membershipByJoinCommand = new HashMap<>();
        Map<String, Membership> membershipByLeaveCommand = new HashMap<>();
        for (Membership membership : memberships) {
            Group group = groupById.get(membership.groupId());
            if (group == null) {
                throw new IllegalArgumentException("成员段引用未知群：" + membership.groupId());
            }
            if (!people.contains(membership.person())) {
                throw new IllegalArgumentException("成员段人物未登记：" + membership.person());
            }
            if (membership.joinedAt() < group.createdAt()) {
                throw new IllegalArgumentException("成员段早于群创建：" + membership.groupId());
            }
            String key = membership.groupId() + '\u0000' + membership.person();
            Membership previous = lastByKey.put(key, membership);
            if (previous != null && (previous.leftAt() == OPEN
                    || previous.leftAt() > membership.joinedAt())) {
                throw new IllegalArgumentException("成员段重叠：" + key);
            }
            if (membership.open() && membership.joinedAt() > clock) {
                throw new IllegalArgumentException("开放成员段晚于当前时刻：" + key);
            }
            if (!membership.open() && membership.leftAt() > clock) {
                throw new IllegalArgumentException("关闭成员段晚于当前时刻：" + key);
            }
            if (!membership.inviteId().isEmpty()
                    && membershipByInvite.put(membership.inviteId(), membership) != null) {
                throw new IllegalArgumentException("同一邀请建立了多个成员段："
                        + membership.inviteId());
            }
            String joinKey = membership.joinCommandId().equals("system:seed")
                    || membership.joinCommandId().equals("system:endowed")
                    ? "seed" + '\u0000' + membership.groupId() + '\u0000' + membership.person()
                    : "join" + '\u0000' + membership.person() + '\u0000'
                    + membership.joinCommandId();
            if (membershipByJoinCommand.put(joinKey, membership) != null) {
                throw new IllegalArgumentException("同一加入/接受命令产生了多个成员段："
                        + membership.person() + "/" + membership.joinCommandId());
            }
            if (!membership.leaveCommandId().isEmpty()) {
                String leaveKey = "leave" + '\u0000' + membership.person() + '\u0000'
                        + membership.leaveCommandId();
                if (membershipByLeaveCommand.put(leaveKey, membership) != null) {
                    throw new IllegalArgumentException("同一退出命令关闭了多个成员段："
                            + membership.person() + "/" + membership.leaveCommandId());
                }
            }
        }
        Set<String> pendingKeys = new HashSet<>();
        Map<String, Invitation> invitationById = new HashMap<>();
        for (Invitation invitation : invitations) {
            Group group = groupById.get(invitation.groupId());
            if (group == null) {
                throw new IllegalArgumentException("邀请引用未知群：" + invitation.groupId());
            }
            if (!people.contains(invitation.inviter()) || !people.contains(invitation.invitee())) {
                throw new IllegalArgumentException("邀请人物未登记：" + invitation.inviteId());
            }
            if (invitationById.put(invitation.inviteId(), invitation) != null) {
                throw new IllegalArgumentException("邀请标识重复：" + invitation.inviteId());
            }
            if (!invitation.inviteId().equals(inviteId(invitation.inviter(),
                    invitation.createCommandId()))) {
                throw new IllegalArgumentException("邀请标识未按命令派生："
                        + invitation.inviteId());
            }
            if (invitation.invitedAt() < group.createdAt() || invitation.invitedAt() > clock) {
                throw new IllegalArgumentException("邀请时刻非法：" + invitation.inviteId());
            }
            if (invitation.decidedAt() > clock) {
                throw new IllegalArgumentException("邀请处理时刻晚于当前：" + invitation.inviteId());
            }
            if (invitation.status() == InvitationStatus.PENDING) {
                String key = invitation.groupId() + '\u0000' + invitation.invitee();
                if (!pendingKeys.add(key)) {
                    throw new IllegalArgumentException("同一群同一受邀人有两条待处理邀请：" + key);
                }
            } else if (invitation.status() == InvitationStatus.ACCEPTED) {
                Membership membership = membershipByInvite.get(invitation.inviteId());
                if (membership == null || membership.joinedAt() != invitation.decidedAt()
                        || !membership.person().equals(invitation.invitee())
                        || !membership.groupId().equals(invitation.groupId())) {
                    throw new IllegalArgumentException("已接受邀请与成员段不一致："
                            + invitation.inviteId());
                }
            } else if (membershipByInvite.containsKey(invitation.inviteId())) {
                throw new IllegalArgumentException("已拒绝邀请仍有成员段："
                        + invitation.inviteId());
            }
            Command create = commandOf(commands, invitation.inviter(),
                    invitation.createCommandId());
            if (create == null || create.kind() != DraftKind.INVITE
                    || !create.subject().equals(invitation.inviteId())
                    || !create.result().equals(InviteStatus.INVITED.name())) {
                throw new IllegalArgumentException("邀请缺少创建命令：" + invitation.inviteId());
            }
            String expectedCreate = digest("INVITE", invitation.inviter(), invitation.groupId(),
                    invitation.invitee(), invitation.inviteId());
            if (!create.digest().equals(expectedCreate)) {
                throw new IllegalArgumentException("邀请创建命令与受邀人/群不一致："
                        + invitation.inviteId());
            }
            if (invitation.status() != InvitationStatus.PENDING) {
                Command respond = commandOf(commands, invitation.invitee(),
                        invitation.respondCommandId());
                if (respond == null || respond.kind() != DraftKind.RESPOND
                        || !respond.subject().equals(invitation.inviteId())
                        || !respond.result().equals(invitation.status().name())) {
                    throw new IllegalArgumentException("邀请缺少处理命令："
                            + invitation.inviteId());
                }
                String decision = invitation.status() == InvitationStatus.ACCEPTED
                        ? "ACCEPT" : "DECLINE";
                String expectedRespond = digest("RESPOND", invitation.invitee(),
                        invitation.inviteId(), decision);
                if (!respond.digest().equals(expectedRespond)) {
                    throw new IllegalArgumentException("邀请处理命令与受邀人/决定不一致："
                            + invitation.inviteId());
                }
            }
        }
        for (Membership membership : memberships) {
            if (membership.inviteId().isEmpty()) {
                continue;
            }
            Invitation invitation = invitationById.get(membership.inviteId());
            if (invitation == null || invitation.status() != InvitationStatus.ACCEPTED
                    || invitation.decidedAt() != membership.joinedAt()
                    || !invitation.invitee().equals(membership.person())
                    || !invitation.groupId().equals(membership.groupId())) {
                throw new IllegalArgumentException("成员段引用的邀请不合法："
                        + membership.inviteId());
            }
        }
        for (Membership membership : memberships) {
            Group group = groupById.get(membership.groupId());
            if (!membership.inviteId().isEmpty()) {
                Invitation invitation = invitationById.get(membership.inviteId());
                if (invitation == null || !invitation.status().equals(InvitationStatus.ACCEPTED)
                        || !membership.joinCommandId().equals(invitation.respondCommandId())) {
                    throw new IllegalArgumentException("接受邀请的成员段缺少匹配 RESPOND："
                            + membership.inviteId());
                }
                Command respond = commandOf(commands, membership.person(),
                        invitation.respondCommandId());
                if (respond == null || respond.kind() != DraftKind.RESPOND
                        || !respond.subject().equals(invitation.inviteId())
                        || !respond.result().equals(RespondStatus.ACCEPTED.name())) {
                    throw new IllegalArgumentException("接受邀请的成员段命令不匹配："
                            + membership.inviteId());
                }
                String expected = digest("RESPOND", membership.person(),
                        invitation.inviteId(), "ACCEPT");
                if (!respond.digest().equals(expected)) {
                    throw new IllegalArgumentException("接受邀请命令摘要不匹配："
                            + membership.inviteId());
                }
            } else if (membership.joinCommandId().equals("system:seed")) {
                if (!membership.person().equals(group.ownerPartyId())
                        || membership.joinedAt() != group.createdAt()) {
                    throw new IllegalArgumentException("非法群主种子成员段："
                            + membership.groupId() + "/" + membership.person());
                }
            } else if (membership.joinCommandId().equals("system:endowed")) {
                // 开局历史禀赋：只允许配置里明确列出的初始成员，且必须与群创建同刻
                if (!group.initialMembers().contains(membership.person())
                        || membership.joinedAt() != group.createdAt()) {
                    throw new IllegalArgumentException("非法初始成员种子段："
                            + membership.groupId() + "/" + membership.person());
                }
            } else {
                Command join = commandOf(commands, membership.person(),
                        membership.joinCommandId());
                if (join == null || join.kind() != DraftKind.JOIN
                        || !join.subject().equals(membership.groupId())
                        || !join.result().equals(JoinStatus.JOINED.name())) {
                    throw new IllegalArgumentException("直接加入的成员段缺少匹配 JOIN："
                            + membership.joinCommandId());
                }
                String expected = digest("JOIN", membership.person(), membership.groupId());
                if (!join.digest().equals(expected)) {
                    throw new IllegalArgumentException("直接加入命令摘要不匹配："
                            + membership.joinCommandId());
                }
            }
            if (!membership.leaveCommandId().isEmpty()) {
                Command leave = commandOf(commands, membership.person(),
                        membership.leaveCommandId());
                if (leave == null || leave.kind() != DraftKind.LEAVE
                        || !leave.subject().equals(membership.groupId())
                        || !leave.result().equals(LeaveStatus.LEFT.name())) {
                    throw new IllegalArgumentException("关闭成员段缺少匹配 LEAVE："
                            + membership.leaveCommandId());
                }
                String expected = digest("LEAVE", membership.person(), membership.groupId());
                if (!leave.digest().equals(expected)) {
                    throw new IllegalArgumentException("退出命令摘要不匹配："
                            + membership.leaveCommandId());
                }
            }
        }
        Set<String> postIds = new HashSet<>();
        Set<String> docIds = new HashSet<>();
        for (Post post : posts) {
            if (groupById.get(post.groupId()) == null) {
                throw new IllegalArgumentException("帖子引用未知群：" + post.groupId());
            }
            if (!people.contains(post.author())) {
                throw new IllegalArgumentException("帖子作者未登记：" + post.author());
            }
            if (!postIds.add(post.postId()) || !docIds.add(post.docId())) {
                throw new IllegalArgumentException("帖子或文档标识重复：" + post.postId());
            }
            if (!post.postId().equals(postId(post.author(), post.commandId()))) {
                throw new IllegalArgumentException("帖子标识未按命令派生：" + post.postId());
            }
            if (post.startedAt() > clock) {
                throw new IllegalArgumentException("帖子开始晚于当前时刻：" + post.postId());
            }
            if (post.status() == PostStatus.PUBLISHED && post.publishedAt() > clock) {
                throw new IllegalArgumentException("帖子发布晚于当前时刻：" + post.postId());
            }
            Command postCommand = commandOf(commands, post.author(), post.commandId());
            String expectedResult = switch (post.status()) {
                case QUEUED -> PostFinishStatus.QUEUED.name();
                case PUBLISHED -> PostBeginStatus.PUBLISHED.name();
                case ABORTED -> PostFinishStatus.ABORTED.name();
            };
            if (postCommand == null || postCommand.kind() != DraftKind.POST
                    || !postCommand.subject().equals(post.postId())
                    || !postCommand.result().equals(expectedResult)
                    || !postCommand.digest().equals(post.contentDigest())) {
                throw new IllegalArgumentException("帖子缺少匹配命令：" + post.postId()
                        + " status=" + post.status() + " expected=" + expectedResult
                        + " command=" + (postCommand == null ? "null"
                        : postCommand.kind() + "/" + postCommand.result() + "/"
                        + postCommand.subject()));
            }
        }
        Set<String> versionKeys = new HashSet<>();
        Set<String> occupiedKeys = new HashSet<>();
        for (PostVersion version : postVersions) {
            Post base = postByDocId(posts, version.docId());
            if (base == null) {
                throw new IllegalArgumentException("更正版本引用未知文档：" + version.docId());
            }
            if (!base.author().equals(version.author()) || !base.groupId().equals(version.groupId())) {
                throw new IllegalArgumentException("更正版本必须继承原帖作者与群：" + version.docId());
            }
            if (!people.contains(version.author())) {
                throw new IllegalArgumentException("更正作者未登记：" + version.author());
            }
            if (!versionKeys.add(version.docId() + '#' + version.version() + '#'
                    + version.commandId())) {
                throw new IllegalArgumentException("更正版本重复：" + version.docId());
            }
            if (version.status() != PostStatus.ABORTED
                    && !occupiedKeys.add(version.docId() + '#' + version.version())) {
                throw new IllegalArgumentException("同一版本存在两条未终止记录：" + version.docId());
            }
            long previous = currentVersionOf(posts, postVersions, version.docId(), version.version());
            if (previous != version.basedOnVersion()) {
                throw new IllegalArgumentException("更正版本不连续：" + version.docId()
                        + "#" + version.version());
            }
            if (version.startedAt() > clock) {
                throw new IllegalArgumentException("更正开始晚于当前时刻：" + version.docId());
            }
            if (version.status() == PostStatus.PUBLISHED && version.publishedAt() > clock) {
                throw new IllegalArgumentException("更正发布晚于当前时刻：" + version.docId());
            }
            Command versionCommand = commandOf(commands, version.author(), version.commandId());
            String expected = switch (version.status()) {
                case QUEUED -> CorrectionFinishStatus.QUEUED.name();
                case PUBLISHED -> PublishStatus.PUBLISHED.name();
                case ABORTED -> versionCommand != null
                        && PublishStatus.STALE_VERSION.name().equals(versionCommand.result())
                        ? PublishStatus.STALE_VERSION.name()
                        : CorrectionFinishStatus.ABORTED.name();
            };
            if (versionCommand == null || versionCommand.kind() != DraftKind.POST_VERSION
                    || !versionCommand.subject().equals(version.docId())
                    || !versionCommand.result().equals(expected)
                    || !versionCommand.digest().equals(version.contentDigest())) {
                throw new IllegalArgumentException("更正版本缺少匹配命令：" + version.docId()
                        + "#" + version.version());
            }
        }
        Set<String> draftKeys = new HashSet<>();
        for (Draft draft : drafts) {
            if (!people.contains(draft.caller())) {
                throw new IllegalArgumentException("动作发起人未登记：" + draft.caller());
            }
            if (groupById.get(draft.groupId()) == null) {
                throw new IllegalArgumentException("动作引用未知群：" + draft.groupId());
            }
            if (draft.startedAt() > clock) {
                throw new IllegalArgumentException("动作开始晚于当前时刻：" + draft.commandId());
            }
            String key = draft.caller() + '\u0000' + draft.commandId();
            if (!draftKeys.add(key)) {
                throw new IllegalArgumentException("同一命令存在多条动作：" + key);
            }
            Command command = commandOf(commands, draft.caller(), draft.commandId());
            if (command == null || command.kind() != draft.kind()
                    || !command.digest().equals(draft.digest())
                    || !command.result().equals("STARTED")
                    || !command.subject().equals(subjectOf(draft))) {
                throw new IllegalArgumentException("动作与命令记录不一致：" + key);
            }
            switch (draft.kind()) {
                case JOIN, LEAVE -> {
                    if (!draft.target().equals(draft.caller()) || draft.accept()
                            || !draft.inviteId().isEmpty()) {
                        throw new IllegalArgumentException("入退群动作字段非法：" + key);
                    }
                    String expected = digest(draft.kind().name(), draft.caller(),
                            draft.groupId());
                    if (!draft.digest().equals(expected)) {
                        throw new IllegalArgumentException("入退群动作摘要不匹配：" + key);
                    }
                }
                case INVITE -> {
                    if (!people.contains(draft.target())) {
                        throw new IllegalArgumentException("邀请动作目标未登记：" + key);
                    }
                    if (!draft.inviteId().equals(inviteId(draft.caller(), draft.commandId()))) {
                        throw new IllegalArgumentException("邀请动作标识未按命令派生：" + key);
                    }
                    String expected = digest("INVITE", draft.caller(), draft.groupId(),
                            draft.target(), draft.inviteId());
                    if (!draft.digest().equals(expected)) {
                        throw new IllegalArgumentException("邀请动作摘要不匹配：" + key);
                    }
                    if (invitationById.containsKey(draft.inviteId())) {
                        throw new IllegalArgumentException("邀请动作与已有邀请冲突："
                                + draft.inviteId());
                    }
                }
                case RESPOND -> {
                    Invitation invitation = invitationById.get(draft.inviteId());
                    if (invitation == null || invitation.status() != InvitationStatus.PENDING
                            || !invitation.invitee().equals(draft.caller())
                            || !invitation.groupId().equals(draft.groupId())) {
                        throw new IllegalArgumentException("处理邀请动作缺少待处理邀请：" + key);
                    }
                    String expected = digest("RESPOND", draft.caller(), draft.inviteId(),
                            draft.accept() ? "ACCEPT" : "DECLINE");
                    if (!draft.digest().equals(expected)) {
                        throw new IllegalArgumentException("处理邀请动作摘要不匹配：" + key);
                    }
                }
                case POST -> {
                    if (!draft.postId().equals(postId(draft.caller(), draft.commandId()))) {
                        throw new IllegalArgumentException("发帖动作标识未按命令派生：" + key);
                    }
                    if (postIds.contains(draft.postId())) {
                        throw new IllegalArgumentException("发帖动作与已有帖子冲突："
                                + draft.postId());
                    }
                }
                case POST_VERSION -> {
                    Post base = postByDocId(posts, draft.docId());
                    if (base == null || !base.author().equals(draft.caller())
                            || !base.groupId().equals(draft.groupId())) {
                        throw new IllegalArgumentException("更正动作缺少本人原帖：" + key);
                    }
                    if (currentVersionOf(posts, postVersions, draft.docId(), Long.MAX_VALUE)
                            != draft.basedOnVersion()) {
                        throw new IllegalArgumentException("更正动作基准版本不是当前版本：" + key);
                    }
                    if (!draft.postId().isEmpty()
                            || !draft.inviteId().isEmpty() || draft.accept()) {
                        throw new IllegalArgumentException("更正动作字段非法：" + key);
                    }
                }
                default -> throw new IllegalStateException("未知动作：" + draft.kind());
            }
        }
        Set<String> commandKeys = new HashSet<>();
        for (Command command : commands) {
            if (!people.contains(command.caller())) {
                throw new IllegalArgumentException("命令发起人未登记：" + command.caller());
            }
            if (command.at() > clock) {
                throw new IllegalArgumentException("命令时刻晚于当前：" + command.commandId());
            }
            String key = command.caller() + '\u0000' + command.commandId();
            if (!commandKeys.add(key)) {
                throw new IllegalArgumentException("命令标识重复：" + key);
            }
            if (command.result().equals("STARTED") && !draftKeys.contains(key)) {
                throw new IllegalArgumentException("STARTED 命令缺少动作：" + key);
            }
            if (!command.result().equals("STARTED") && draftKeys.contains(key)) {
                throw new IllegalArgumentException("已裁决命令仍有动作：" + key);
            }
            try {
                switch (command.kind()) {
                    case JOIN -> {
                        JoinStatus status = JoinStatus.valueOf(command.result());
                        if (status == JoinStatus.JOINED
                                && membershipForJoin(memberships, command.caller(),
                                command.commandId()) == null) {
                            throw new IllegalArgumentException("JOINED 命令缺少成员段");
                        }
                        if (!command.digest().equals(digest("JOIN", command.caller(),
                                command.subject()))) {
                            throw new IllegalArgumentException("加入命令摘要不匹配");
                        }
                    }
                    case INVITE -> {
                        if (!command.subject().equals(inviteId(command.caller(),
                                command.commandId()))) {
                            throw new IllegalArgumentException("邀请命令对象未按命令派生");
                        }
                        InviteStatus status = InviteStatus.valueOf(command.result());
                        if (status == InviteStatus.INVITED
                                && invitationForCreate(invitations, command.caller(),
                                command.commandId()) == null) {
                            throw new IllegalArgumentException("INVITED 命令缺少邀请");
                        }
                    }
                    case RESPOND -> {
                        RespondStatus status = RespondStatus.valueOf(command.result());
                        if ((status == RespondStatus.ACCEPTED || status == RespondStatus.DECLINED)
                                && invitationForRespond(invitations, command.caller(),
                                command.commandId()) == null) {
                            throw new IllegalArgumentException("RESPOND 命令缺少邀请处理");
                        }
                    }
                    case LEAVE -> {
                        LeaveStatus status = LeaveStatus.valueOf(command.result());
                        if (status == LeaveStatus.LEFT
                                && membershipForLeave(memberships, command.caller(),
                                command.commandId()) == null) {
                            throw new IllegalArgumentException("LEFT 命令缺少关闭成员段");
                        }
                        if (!command.digest().equals(digest("LEAVE", command.caller(),
                                command.subject()))) {
                            throw new IllegalArgumentException("退出命令摘要不匹配");
                        }
                    }
                    case POST -> {
                        if (!command.subject().equals(postId(command.caller(),
                                command.commandId()))) {
                            throw new IllegalArgumentException("发帖命令对象未按命令派生");
                        }
                        PostBeginStatus.valueOf(command.result());
                    }
                    case POST_VERSION -> {
                        if (postByDocId(posts, command.subject()) == null) {
                            throw new IllegalArgumentException("更正命令引用未知文档");
                        }
                        requireCorrectionResult(command.result());
                    }
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("命令裁决不属于该动作：" + key
                        + " -> " + command.result(), invalid);
            }
        }
    }

    private static Membership membershipForJoin(List<Membership> memberships, String caller,
                                                String commandId) {
        for (Membership membership : memberships) {
            if (membership.person().equals(caller)
                    && membership.joinCommandId().equals(commandId)) {
                return membership;
            }
        }
        return null;
    }

    private static Membership membershipForLeave(List<Membership> memberships, String caller,
                                                 String commandId) {
        for (Membership membership : memberships) {
            if (membership.person().equals(caller)
                    && membership.leaveCommandId().equals(commandId)) {
                return membership;
            }
        }
        return null;
    }

    private static Invitation invitationForCreate(List<Invitation> invitations, String caller,
                                                  String commandId) {
        for (Invitation invitation : invitations) {
            if (invitation.inviter().equals(caller)
                    && invitation.createCommandId().equals(commandId)) {
                return invitation;
            }
        }
        return null;
    }

    private static Invitation invitationForRespond(List<Invitation> invitations, String caller,
                                                   String commandId) {
        for (Invitation invitation : invitations) {
            if (invitation.invitee().equals(caller)
                    && invitation.respondCommandId().equals(commandId)) {
                return invitation;
            }
        }
        return null;
    }

    private static String subjectOf(Draft draft) {
        return switch (draft.kind()) {
            case JOIN, LEAVE -> draft.groupId();
            case INVITE, RESPOND -> draft.inviteId();
            case POST -> draft.postId();
            case POST_VERSION -> draft.docId();
        };
    }

    private static Command commandOf(List<Command> commands, String caller, String commandId) {
        for (Command command : commands) {
            if (command.caller().equals(caller) && command.commandId().equals(commandId)) {
                return command;
            }
        }
        return null;
    }

    private static Command commandOf(State state, String caller, String commandId) {
        return commandOf(state.commands(), caller, commandId);
    }

    private static Draft draftOf(State state, String caller, String commandId, DraftKind kind) {
        for (Draft draft : state.drafts()) {
            if (draft.caller().equals(caller) && draft.commandId().equals(commandId)
                    && draft.kind() == kind) {
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

    private static Group groupOf(State state, String groupId) {
        for (Group group : state.groups()) {
            if (group.groupId().equals(groupId)) {
                return group;
            }
        }
        return null;
    }

    private static Invitation invitationOf(State state, String inviteId) {
        for (Invitation invitation : state.invitations()) {
            if (invitation.inviteId().equals(inviteId)) {
                return invitation;
            }
        }
        return null;
    }

    private static Invitation pendingInvitation(State state, String groupId, String invitee) {
        for (Invitation invitation : state.invitations()) {
            if (invitation.groupId().equals(groupId) && invitation.invitee().equals(invitee)
                    && invitation.status() == InvitationStatus.PENDING) {
                return invitation;
            }
        }
        return null;
    }

    private static Post postOf(State state, String postId) {
        for (Post post : state.posts()) {
            if (post.postId().equals(postId)) {
                return post;
            }
        }
        return null;
    }

    private static Post postByDocId(State state, String docId) {
        for (Post post : state.posts()) {
            if (post.docId().equals(docId)) {
                return post;
            }
        }
        return null;
    }

    private static Post postByDocId(List<Post> posts, String docId) {
        for (Post post : posts) {
            if (post.docId().equals(docId)) {
                return post;
            }
        }
        return null;
    }

    /**
     * 按同一继承实现展开直接来源，供群帖、群更正与平台帖共用。
     *
     * <p>只展开本模块能确认的群帖谱系；媒体成稿等外部版本由调用方自己的来源解析负责，本方法不
     * 声称已完成 Media 最终根闭包。返回空 {@link Optional} 表示谱系缺失，调用方必须拒绝。</p>
     *
     * @param state  当前状态
     * @param context 受信上下文
     * @param direct 直接引用
     * @return 规范根；谱系缺失为空
     */
    public static Optional<List<Ref>> rootsFor(State state, Context context, List<Ref> direct) {
        requireState(state);
        requireContext(context);
        try {
            return Optional.of(inheritedRoots(state, context, canonicalRefs(direct)));
        } catch (MissingVersionOrigins missing) {
            return Optional.empty();
        }
    }

    /**
     * 平台帖首版内容摘要；与群帖/更正使用不同域，跨类型伪造不会相等。
     *
     * @param author   作者
     * @param title    标题
     * @param body     正文
     * @param declared 直接来源
     * @return 摘要
     */
    static String platformDigest(String author, String title, String body,
                                 List<Ref> declared) {
        requireText(author, "作者");
        requireText(title, "标题");
        requireText(body, "正文");
        return digest("PLATFORM_POST", author, title, body, refsKey(declared));
    }

    /**
     * 群帖首版内容摘要；与既有拼法逐字相同，旧帖摘要不受本次扩展影响。
     *
     * @param author   作者
     * @param groupId  群
     * @param title    标题
     * @param body     正文
     * @param declared 直接来源
     * @return 摘要
     */
    static String postDigest(String author, String groupId, String title, String body,
                             List<Ref> declared) {
        requireText(author, "作者");
        requireText(groupId, "群");
        requireText(title, "标题");
        requireText(body, "正文");
        return digest("POST", author, groupId, title, body, refsKey(declared));
    }

    /**
     * 更正版本内容摘要；域与首版不同，跨类型伪造不会相等。
     *
     * @param domain         摘要域，群更正用 POST_VERSION，平台用 PLATFORM
     * @param author         作者
     * @param groupId        群；平台传空串
     * @param version        版本号
     * @param basedOnVersion 起草基准
     * @param title          标题
     * @param body           正文
     * @param declared       直接来源
     * @return 摘要
     */
    static String versionDigest(String domain, String author, String groupId, long version,
                                long basedOnVersion, String title, String body,
                                List<Ref> declared) {
        requireText(domain, "摘要域");
        requireText(author, "作者");
        requireText(title, "标题");
        requireText(body, "正文");
        if (version < 2 || basedOnVersion != version - 1) {
            throw new IllegalArgumentException("更正摘要版本必须连续");
        }
        return digest(domain, author, groupId == null ? "" : groupId, Long.toString(version),
                Long.toString(basedOnVersion), title, body, refsKey(declared));
    }

    /**
     * 从真实直接来源与版本来源复算全部缓存根，校验没有被改写。
     *
     * <p>缓存的 {@code originRefs} 不是第二权威图：加载或恢复后必须调用本方法，用同一继承实现与
     * 真实直接引用重新展开；任何不一致都拒绝，而不是悄悄接受被篡改的根。</p>
     *
     * @param state   状态
     * @param context 受信上下文（版本来源）
     * @throws IllegalArgumentException 某条记录的缓存根与复算结果不一致
     */
    public static void verifyRoots(State state, Context context) {
        requireState(state);
        requireContext(context);
        for (Post post : state.posts()) {
            List<Ref> expected = rootsFor(state, context, post.declaredRefs())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "帖子来源谱系缺失，不能恢复：" + post.docId()));
            if (!expected.equals(post.originRefs())) {
                throw new IllegalArgumentException("帖子缓存根与复算不一致：" + post.docId());
            }
        }
        for (PostVersion version : state.postVersions()) {
            List<Ref> expected = rootsFor(state, context, version.declaredRefs())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "更正来源谱系缺失，不能恢复：" + version.docId()));
            if (!expected.equals(version.originRefs())) {
                throw new IllegalArgumentException("更正缓存根与复算不一致：" + version.docId());
            }
        }
    }

    /**
     * 当前可见版本号：没有更正的帖子为 1，否则为最大更正版本。
     *
     * @param state 状态
     * @param docId 文档
     * @return 版本号；文档不存在返回 0
     */
    public static long currentVersionOf(State state, String docId) {
        requireState(state);
        requireText(docId, "文档标识");
        if (postByDocId(state, docId) == null) {
            return 0L;
        }
        return currentVersionOf(state.posts(), state.postVersions(), docId, Long.MAX_VALUE);
    }

    private static long currentVersionOf(List<Post> posts, List<PostVersion> versions,
                                         String docId, long beforeVersion) {
        long current = postByDocId(posts, docId) == null ? 0L : 1L;
        for (PostVersion version : versions) {
            if (version.docId().equals(docId) && version.version() < beforeVersion
                    && version.status() != PostStatus.ABORTED) {
                current = Math.max(current, version.version());
            }
        }
        return current;
    }

    /** 优先返回真正占用版本号的记录；只有全部记录都终止时才返回终止记录供审计/重放。 */
    private static PostVersion postVersionOf(State state, String docId, long version) {
        PostVersion aborted = null;
        for (PostVersion candidate : state.postVersions()) {
            if (candidate.docId().equals(docId) && candidate.version() == version) {
                if (candidate.status() != PostStatus.ABORTED) {
                    return candidate;
                }
                aborted = candidate;
            }
        }
        return aborted;
    }

    private static List<PostVersion> replacePostVersion(List<PostVersion> versions,
                                                        PostVersion old, PostVersion replacement) {
        List<PostVersion> copy = new ArrayList<>(versions);
        copy.set(copy.indexOf(old), replacement);
        return copy;
    }

    private static State deriveVersions(State base, List<PostVersion> versions, List<Draft> drafts,
                                        List<Command> commands, long now) {
        return new State(base.persons(), base.groups(), base.memberships(), base.invitations(),
                base.posts(), versions, drafts, commands, Math.max(base.clock(), now));
    }

    private static State finishCorrectionMutation(State state, Draft draft, String result,
                                                  long now, PostVersion version) {
        List<Draft> drafts = new ArrayList<>(state.drafts());
        drafts.remove(draft);
        List<Command> commands = new ArrayList<>(state.commands());
        boolean replaced = false;
        for (int index = 0; index < commands.size(); index++) {
            Command command = commands.get(index);
            if (command.caller().equals(draft.caller())
                    && command.commandId().equals(draft.commandId())) {
                commands.set(index, new Command(command.caller(), command.commandId(),
                        command.kind(), command.subject(), command.digest(), result, command.at()));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            throw new IllegalStateException("更正完成缺少命令记录：" + draft.commandId());
        }
        List<PostVersion> versions = new ArrayList<>(state.postVersions());
        versions.add(version);
        return deriveVersions(state, versions, drafts, commands, now);
    }

    private static CorrectionBeginStatus correctionBeginReplay(String result) {
        try {
            return CorrectionBeginStatus.valueOf(result);
        } catch (IllegalArgumentException unknown) {
            return CorrectionBeginStatus.COMMAND_CONFLICT;
        }
    }

    private static void requireCorrectionResult(String result) {
        try {
            CorrectionBeginStatus.valueOf(result);
            return;
        } catch (IllegalArgumentException ignored) {
            // 继续尝试完成与发布枚举
        }
        try {
            CorrectionFinishStatus.valueOf(result);
            return;
        } catch (IllegalArgumentException ignored) {
            // 继续尝试发布枚举
        }
        PublishStatus published = PublishStatus.valueOf(result);
        if (published != PublishStatus.PUBLISHED && published != PublishStatus.STALE_VERSION) {
            throw new IllegalArgumentException("更正命令裁决不属于更正动作：" + result);
        }
    }

    private static List<Ref> inheritedRoots(State state, Context context, List<Ref> direct) {
        List<Ref> roots = new ArrayList<>();
        for (Ref ref : direct) {
            Post source = postByDocId(state, ref.docId());
            if (source == null) {
                roots.add(ref);
                continue;
            }
            if (ref.version() == 1) {
                if (!source.originRefs().isEmpty()) {
                    roots.addAll(source.originRefs());
                } else {
                    roots.add(ref);
                }
                continue;
            }
            Optional<List<Ref>> versionRoots = context.versionOrigins()
                    .originsOf(ref.docId(), ref.version());
            if (versionRoots.isEmpty()) {
                throw new MissingVersionOrigins();
            }
            List<Ref> resolved = canonicalRefs(versionRoots.get());
            if (resolved.isEmpty()) {
                roots.add(ref);
            } else {
                if (resolved.contains(ref)) {
                    throw new MissingVersionOrigins();
                }
                roots.addAll(resolved);
            }
        }
        return canonicalRefs(roots);
    }

    /** 已知帖的更正版缺少可信版本来源。 */
    private static final class MissingVersionOrigins extends RuntimeException {
        private static final long serialVersionUID = 1L;
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

    private static String refsKey(List<Ref> refs) {
        StringBuilder builder = new StringBuilder();
        for (Ref ref : canonicalRefs(refs)) {
            builder.append(ref.docId().length()).append(':').append(ref.docId()).append('@')
                    .append(ref.version()).append(';');
        }
        return builder.toString();
    }

    private static List<Membership> replaceMembership(List<Membership> memberships,
                                                      Membership old, Membership replacement) {
        List<Membership> copy = new ArrayList<>(memberships);
        copy.set(copy.indexOf(old), replacement);
        return copy;
    }

    private static List<Invitation> replaceInvitation(List<Invitation> invitations,
                                                      Invitation old, Invitation replacement) {
        List<Invitation> copy = new ArrayList<>(invitations);
        copy.set(copy.indexOf(old), replacement);
        return copy;
    }

    private static List<Post> replacePost(List<Post> posts, Post old, Post replacement) {
        List<Post> copy = new ArrayList<>(posts);
        copy.set(copy.indexOf(old), replacement);
        return copy;
    }

    private static List<Post> plusPost(State state, Post post) {
        List<Post> posts = new ArrayList<>(state.posts());
        posts.add(post);
        return posts;
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

    private static State derive(State base, List<Membership> memberships,
                                List<Invitation> invitations, List<Post> posts,
                                List<Draft> drafts, List<Command> commands, long now) {
        return new State(base.persons(), base.groups(), memberships, invitations, posts,
                base.postVersions(), drafts, commands, Math.max(base.clock(), now));
    }

    private static List<Command> replaceCommandResult(List<Command> commands, String caller,
                                                      String commandId, String result) {
        List<Command> copy = new ArrayList<>(commands);
        for (int index = 0; index < copy.size(); index++) {
            Command command = copy.get(index);
            if (command.caller().equals(caller) && command.commandId().equals(commandId)) {
                copy.set(index, new Command(command.caller(), command.commandId(),
                        command.kind(), command.subject(), command.digest(), result, command.at()));
                return copy;
            }
        }
        throw new IllegalStateException("缺少命令记录：" + commandId);
    }

    private static State archive(State state, String caller, String commandId, DraftKind kind,
                                 String subject, String digest, Enum<?> result, long now) {
        Command command = new Command(caller, commandId, kind, subject, digest, result.name(), now);
        return derive(state, state.memberships(), state.invitations(), state.posts(),
                state.drafts(), plusCommand(state, command), now);
    }

    private static State finishMutation(State state, Draft draft, String result, long now,
                                        List<Membership> memberships,
                                        List<Invitation> invitations, List<Post> posts) {
        List<Draft> drafts = new ArrayList<>(state.drafts());
        drafts.remove(draft);
        List<Command> commands = new ArrayList<>(state.commands());
        boolean replaced = false;
        for (int index = 0; index < commands.size(); index++) {
            Command command = commands.get(index);
            if (command.caller().equals(draft.caller())
                    && command.commandId().equals(draft.commandId())) {
                commands.set(index, new Command(command.caller(), command.commandId(),
                        command.kind(), command.subject(), command.digest(), result, command.at()));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            throw new IllegalStateException("完成动作缺少命令记录：" + draft.commandId());
        }
        return derive(state, memberships, invitations, posts, drafts, commands, now);
    }

    private static long safeAdd(long base, long delta) {
        long sum = base + delta;
        return sum < base ? -1L : sum;
    }

    static String digest(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                sha.update((byte) (bytes.length >>> 24));
                sha.update((byte) (bytes.length >>> 16));
                sha.update((byte) (bytes.length >>> 8));
                sha.update((byte) bytes.length);
                sha.update(bytes);
            }
            byte[] hash = sha.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 不可用", missing);
        }
    }

    private static List<String> sortedUniqueText(List<String> values, String name) {
        List<String> copy = new ArrayList<>(values == null ? List.of() : values);
        for (String value : copy) {
            requireText(value, name);
        }
        copy.sort(Comparator.naturalOrder());
        List<String> unique = new ArrayList<>();
        for (String value : copy) {
            if (unique.isEmpty() || !unique.get(unique.size() - 1).equals(value)) {
                unique.add(value);
            }
        }
        return List.copyOf(unique);
    }

    private static <T> List<T> sortedCopy(List<T> values, Comparator<? super T> comparator) {
        List<T> copy = new ArrayList<>(values == null ? List.of() : values);
        for (T value : copy) {
            Objects.requireNonNull(value, "状态元素");
        }
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    private static List<Group> sortedGroups(List<Group> groups) {
        List<Group> copy = new ArrayList<>(groups == null ? List.of() : groups);
        for (Group group : copy) {
            Objects.requireNonNull(group, "群");
        }
        copy.sort(Comparator.comparing(Group::groupId));
        return List.copyOf(copy);
    }

    private static void requireState(State state) {
        Objects.requireNonNull(state, "群组状态");
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

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + "必须为正：" + value);
        }
    }
}
