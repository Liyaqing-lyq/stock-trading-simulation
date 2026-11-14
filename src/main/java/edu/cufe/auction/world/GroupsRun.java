package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 群组与平台发布的运行状态：冻结配置、纯核群状态、平台版本与呈现帧、任务报告。
 *
 * <p><b>权威边界</b>：帖子/平台帖的正文与阅读经历仍唯一保存在 {@link InformationModel}；本类只保存
 * 群定义、成员段、邀请、帖子元数据、平台版本元数据、规范来源根与呈现事实，不复制正文、标题副本、
 * 阅读账或余额。旧 schema ≤19 该字段为 null 且不序列化。</p>
 *
 * <p><b>冻结</b>：群历史策略、群/平台工时、发布等待与平台目录上限在开局随第一批提交冻结，恢复时
 * 必须逐字段相等；换一套参数重开同一日志会被拒绝。目录顺序规则（发布时间倒序、docId 升序、
 * 版本降序）由 {@link PublicationRun#feed} 固定，不随配置变化。</p>
 *
 * <p><b>呈现不等于看见</b>：呈现帧只记录某次真实决定输入实际包含的条目，绑定真实动作、尝试与
 * 输入摘要；目录可访问、页面查询、God 查看都不写帧。读完仍唯一在 {@link InformationModel}，
 * 信念与行动另走既有模型。</p>
 *
 * @param config         群组冻结配置（预置群与各类正工时）
 * @param platformConfig 平台写帖冻结配置
 * @param feedLimit      平台目录上限（1..200）
 * @param readMillis     群/平台帖的固定阅读耗时（正毫秒）
 * @param state          群组纯核状态
 * @param publication    平台版本与呈现帧
 * @param reports        任务报告（拒绝也保留）
 */
public record GroupsRun(GroupModel.Config config, PlatformPostModel.Config platformConfig,
                        int feedLimit, long readMillis, GroupModel.State state,
                        PublicationRun.Run publication,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<GroupReport> reports) {

    /** 群/平台动作开始门：barrier 到达后才真正开始（COMPLETION）。 */
    public static final String KIND_BEGIN = PendingTask.GROUP_BEGIN_V1;
    /** 群/平台动作完成：正工时到期结算（COMPLETION）。 */
    public static final String KIND_FINISH = PendingTask.GROUP_FINISH_V1;
    /** 系统投递：按冻结发布时刻落正文，不占人物焦点（COMPLETION）。 */
    public static final String KIND_PUBLISH = PendingTask.GROUP_PUBLISH_V1;

    /** 本模块全部任务种类（只进本模块校验，不混入媒体/社交流）。 */
    public static final Set<String> KINDS = Set.of(KIND_BEGIN, KIND_FINISH, KIND_PUBLISH);

    /** 业务成功原因码。 */
    public static final String REASON_OK = "OK";

    /**
     * 一条任务报告：拒绝也留下，供管理员审计；position 只进管理视图。
     *
     * @param taskId   内部任务标识
     * @param person   相关人物
     * @param kind     任务种类
     * @param action   业务动作（JOIN/INVITE/RESPOND/LEAVE/POST/CORRECTION/PLATFORM_POST/
     *                 PLATFORM_CORRECTION/PUBLISH）
     * @param accepted 业务是否成功
     * @param reason   原因码
     * @param subject  业务对象（群、邀请或文档）；可为空串
     * @param atMillis 业务时点
     * @param position 提交位置；只给管理端
     */
    public record GroupReport(String taskId, String person, String kind, String action,
                              boolean accepted, String reason, String subject, long atMillis,
                              long position) {

        /** 校验字段。 */
        public GroupReport {
            requireText(taskId, "任务标识");
            requireText(person, "人物");
            requireText(kind, "任务种类");
            requireText(action, "业务动作");
            requireText(reason, "原因码");
            if (subject == null) {
                subject = "";
            }
            if (atMillis < 0 || position < 0) {
                throw new IllegalArgumentException("报告时点或位置非法：" + atMillis + "/" + position);
            }
        }
    }

    /** 校验冻结参数、排序与唯一性。 */
    public GroupsRun {
        Objects.requireNonNull(config, "群组配置");
        Objects.requireNonNull(platformConfig, "平台配置");
        if (feedLimit < 1 || feedLimit > 200) {
            throw new IllegalArgumentException("平台目录上限必须在 1..200：" + feedLimit);
        }
        if (readMillis <= 0) {
            throw new IllegalArgumentException("群/平台帖阅读耗时必须为正：" + readMillis);
        }
        Objects.requireNonNull(state, "群组纯核状态");
        Objects.requireNonNull(publication, "平台发布状态");
        if (!state.groups().equals(config.groups())) {
            throw new IllegalArgumentException("群定义与冻结配置不一致");
        }
        List<GroupReport> copy = new ArrayList<>(reports == null ? List.of() : reports);
        copy.sort(Comparator.comparing(GroupReport::atMillis).thenComparing(GroupReport::taskId));
        Set<String> tasks = new LinkedHashSet<>();
        for (GroupReport report : copy) {
            if (!tasks.add(report.taskId())) {
                throw new IllegalArgumentException("同一任务只能有一条群组报告：" + report.taskId());
            }
        }
        reports = List.copyOf(copy);
    }

    /**
     * 开局：按冻结配置建立群主与初始成员段，平台状态只冻结登记人物与时刻。
     *
     * <p>初始成员是明确的<b>开局历史禀赋</b>，不是伪造的加入动作：成员段使用系统命令标识，
     * 不占人物工时，也不生成用户命令记录。</p>
     *
     * @param config         群组冻结配置
     * @param platformConfig 平台写帖冻结配置
     * @param feedLimit      平台目录上限
     * @param readMillis     群/平台帖固定阅读耗时
     * @param persons        开局登记人物（含所有群主与初始成员以外的人）
     * @param clock          开局时刻
     * @return 空运行状态
     */
    public static GroupsRun open(GroupModel.Config config, PlatformPostModel.Config platformConfig,
                                 int feedLimit, long readMillis, List<String> persons,
                                 long clock) {
        Objects.requireNonNull(config, "群组配置");
        GroupModel.State state = GroupModel.open(config, persons);
        PublicationRun.Run publication = new PublicationRun.Run(
                PlatformPostModel.open(persons, clock), List.of());
        return new GroupsRun(config, platformConfig, feedLimit, readMillis, state, publication,
                List.of());
    }

    /**
     * 新状态（报告沿用）。
     *
     * @param next 新群组纯核状态
     * @return 同配置新状态
     */
    public GroupsRun withState(GroupModel.State next) {
        return new GroupsRun(config, platformConfig, feedLimit, readMillis, next, publication,
                reports);
    }

    /**
     * 新平台状态（报告沿用）。
     *
     * @param next 新平台发布状态
     * @return 同配置新状态
     */
    public GroupsRun withPublication(PublicationRun.Run next) {
        return new GroupsRun(config, platformConfig, feedLimit, readMillis, state, next, reports);
    }

    /**
     * 追加一条任务报告。
     *
     * @param report 报告
     * @return 新状态
     */
    public GroupsRun withReport(GroupReport report) {
        List<GroupReport> next = new ArrayList<>(reports);
        next.add(report);
        return new GroupsRun(config, platformConfig, feedLimit, readMillis, state, publication,
                next);
    }

    /**
     * 本运行受信群上下文：引用资格走真实阅读经历，版本来源走真实群/平台版本记录。
     *
     * <p>版本来源只回答「已知帖的某版本声明了哪些直接来源」；规范闭包、成环与时间校验由纯核完成。
     * 元数据缺失必须返回空，不能猜成 v1 的根。</p>
     *
     * @param runtime 所属运行（提供真实阅读资格）
     * @return 受信上下文
     */
    public GroupModel.Context context(InformationModel.State info, WorldRuntime runtime) {
        Objects.requireNonNull(runtime, "运行");
        GroupModel.CitationOracle citations = (person, docId, version, at) ->
                runtime.citationAllowed(info, person, docId, version, at);
        return new GroupModel.Context(citations, versionOrigins(
                Objects.requireNonNull(info, "信息状态")), config);
    }

    /**
     * 平台受信上下文：额外带上当前群状态，用于展开直接引用的群帖谱系。
     *
     * @param runtime 所属运行
     * @return 受信上下文
     */
    public PlatformPostModel.Context platformContext(InformationModel.State info,
                                                      WorldRuntime runtime) {
        Objects.requireNonNull(runtime, "运行");
        GroupModel.CitationOracle citations = (person, docId, version, at) ->
                runtime.citationAllowed(info, person, docId, version, at);
        return new PlatformPostModel.Context(citations, versionOrigins(info), state, platformConfig);
    }

    /**
     * 以真实信息状态校验的版本来源：群/平台帖的声明引用必须先通过
     * {@link PublicationBinding#verify}，再由 {@link PostOrigins} 展开；缺失谱系返回空。
     *
     * @param info 本次提交使用的信息状态
     * @return 受信版本来源
     */
    public GroupModel.VersionOrigins versionOrigins(InformationModel.State info) {
        Objects.requireNonNull(info, "信息状态");
        PostOrigins origins = new PostOrigins(state, publication.platform());
        return (docId, version) -> origins.originsOf(info, docId, version);
    }

    /**
     * 已知帖版本的冻结来源。
     *
     * @return 版本来源解析
     */
    public GroupModel.VersionOrigins versionOrigins() {
        return (docId, version) -> originsOf(docId, version);
    }

    /**
     * 某文档版本在纯核记录里冻结的直接来源。
     *
     * @param docId   文档
     * @param version 版本
     * @return 直接来源；不是本模块已知帖版本时为空
     */
    public Optional<List<GroupModel.Ref>> originsOf(String docId, long version) {
        for (GroupModel.Post post : state.posts()) {
            if (post.docId().equals(docId) && version == 1) {
                return Optional.of(post.declaredRefs());
            }
        }
        for (GroupModel.PostVersion corrected : state.postVersions()) {
            if (corrected.docId().equals(docId) && corrected.version() == version) {
                return Optional.of(corrected.declaredRefs());
            }
        }
        if (version == 1) {
            for (PlatformPostModel.Post post : publication.platform().posts()) {
                if (post.docId().equals(docId)) {
                    return Optional.of(convert(post.declaredRefs()));
                }
            }
        }
        for (PlatformPostModel.Post post : publication.platform().posts()) {
            if (post.docId().equals(docId) && post.version() == version) {
                return Optional.of(convert(post.declaredRefs()));
            }
        }
        return Optional.empty();
    }

    /**
     * 某群某时刻的开放成员段。
     *
     * @param groupId 群
     * @param person  人物
     * @param at      时刻
     * @return 开放段
     */
    public Optional<GroupModel.Membership> membershipAt(String groupId, String person, long at) {
        return GroupModel.membershipAt(state, groupId, person, at);
    }

    /**
     * 群标识（供统一读取门使用）。
     *
     * @param groupId 群
     * @return 群定义；未知为空
     */
    public Optional<GroupModel.Group> group(String groupId) {
        for (GroupModel.Group group : state.groups()) {
            if (group.groupId().equals(groupId)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    private static List<GroupModel.Ref> convert(List<PlatformPostModel.Ref> refs) {
        List<GroupModel.Ref> converted = new ArrayList<>();
        for (PlatformPostModel.Ref ref : refs == null ? List.<PlatformPostModel.Ref>of() : refs) {
            converted.add(new GroupModel.Ref(ref.docId(), ref.version()));
        }
        return converted;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
