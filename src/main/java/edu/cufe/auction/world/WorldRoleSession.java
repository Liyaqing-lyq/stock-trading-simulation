package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 绑定人物与本次观察任职的进程内会话。由可信宿主通过
 * {@link WorldRuntime#openRoleSession(String)} 创建，模型拿不到它的 actor 字段。
 *
 * <p><b>观察固定</b>：打开时冻结 {@code observedAt} 与该人物当时<b>全部</b>有效任职的
 * 组织→任职标识映射；同人多组织不会任选第一份。会话不写日志，恢复后必须重新打开。</p>
 *
 * <p><b>拒绝口径</b>：形状合法的动作一律排程并返回 {@code SCHEDULED:clientTaskId}；
 * 未知对象、越权、旧版本在<b>完成期</b>统一产出 {{@code NOT_AUTHORIZED} 或真实业务原因，
 * 排程期不查对象是否存在。静态非法字段与过期 due 抛 {@link IllegalArgumentException}。</p>
 *
 * <p><b>不再要求有效任职</b>：离任者仍可打开会话查看个人资产、已读经历与公开资料；
 * 岗位动作会因观察任职失效而在完成期被拒。</p>
 *
 * @param runtime    所属运行（同包可信边界）
 * @param personPartyId 人物
 * @param observedAt 观察时点
 * @param appointments 组织→任职标识（不可变）
 */
public record WorldRoleSession(WorldRuntime runtime, String personPartyId, long observedAt,
                               Map<String, String> appointments, Long observedPosition) {

    /**
     * 旧四参构造：没有精确观察位置（旧档案）。
     *
     * @param runtime       所属运行
     * @param personPartyId 人物
     * @param observedAt    观察时点
     * @param appointments  组织→任职标识
     */
    public WorldRoleSession(WorldRuntime runtime, String personPartyId, long observedAt,
                            Map<String, String> appointments) {
        this(runtime, personPartyId, observedAt, appointments, null);
    }

    /** 校验字段并冻结映射。 */
    public WorldRoleSession {
        Objects.requireNonNull(runtime, "运行");
        if (personPartyId == null || personPartyId.isBlank()) {
            throw new IllegalArgumentException("会话人物不能为空");
        }
        if (observedAt < 0) {
            throw new IllegalArgumentException("观察时点不能为负");
        }
        appointments = Collections.unmodifiableMap(new TreeMap<>(appointments));
    }

    /** 研究发表意图：业务字段，不含人物。 */
    public record ResearchDraft(String researchId, String organizationPartyId,
                                String productOwnerId, String symbol, String docId, String title,
                                String body, List<Citation> citations) {
        /** 冻结证据列表。 */
        public ResearchDraft {
            requireText(researchId, organizationPartyId, productOwnerId, symbol, docId, title, body);
            if (citations == null || citations.isEmpty() || citations.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("研究须包含有效资料引用");
            }
            citations = List.copyOf(citations);
        }
    }

    /** 已读资料引用：文档 + 定版本。 */
    public record Citation(String docId, long version) {
        /** 校验字段。 */
        public Citation {
            if (docId == null || docId.isBlank() || version < 1) {
                throw new IllegalArgumentException("引用必须带资料与正版本");
            }
        }
    }

    /** 经理建立或修改绝对目标。 */
    public record TargetDraft(String targetId, int expectedVersion, String organizationPartyId,
                              String productOwnerId, String accountId, String symbol,
                              long absoluteTargetQuantity, BigDecimal priceLimit,
                              long deadlineMillis, String sourceResearchId) {
        /** 校验静态字段。 */
        public TargetDraft {
            if (targetId == null || targetId.isBlank() || expectedVersion < 0
                    || absoluteTargetQuantity < 0) {
                throw new IllegalArgumentException("目标草稿标识或数量非法");
            }
            requireText(organizationPartyId, productOwnerId, accountId, symbol, sourceResearchId);
            requirePrice(priceLimit);
            if (deadlineMillis <= 0) {
                throw new IllegalArgumentException("目标截止必须为正");
            }
        }
    }

    /** 风控审核。 */
    public record ReviewDraft(String targetId, int expectedVersion, boolean approved,
                              long maxTotalQuantity, long maxChildQuantity, BigDecimal priceLimit,
                              long validUntilMillis, String reason) {
        /** 校验静态字段。 */
        public ReviewDraft {
            if (targetId == null || targetId.isBlank() || expectedVersion < 1
                    || maxTotalQuantity < 0 || maxChildQuantity < 0) {
                throw new IllegalArgumentException("审核草稿标识或额度非法");
            }
            requirePrice(priceLimit);
            requireText(reason);
            if (validUntilMillis <= 0) {
                throw new IllegalArgumentException("审核期限必须为正");
            }
        }
    }

    /** 风控暂停。 */
    public record HaltDraft(String haltId, String targetId, int expectedVersion, long untilMillis,
                            String reason) {
        /** 校验静态字段。 */
        public HaltDraft {
            if (haltId == null || haltId.isBlank() || targetId == null || targetId.isBlank()
                    || expectedVersion < 1 || untilMillis <= 0) {
                throw new IllegalArgumentException("暂停草稿字段非法");
            }
            requireText(reason);
        }
    }

    /** 执行员子单；账户、标的、方向从父任务取。 */
    public record ChildDraft(String parentId, int parentVersion, int riskRevision, String childId,
                             long quantity, BigDecimal limitPrice) {
        /** 校验静态字段。 */
        public ChildDraft {
            if (parentId == null || parentId.isBlank() || parentVersion < 1 || riskRevision < 1
                    || childId == null || childId.isBlank() || quantity <= 0) {
                throw new IllegalArgumentException("子单草稿字段非法");
            }
            requirePrice(limitPrice);
        }
    }

    /** 本人子单引用：父任务 + 所属人 + 本人子单标识；不含全局订单号。 */
    public record ChildRef(String parentId, String personPartyId, String childId) {
        /** 校验字段。 */
        public ChildRef {
            if (parentId == null || parentId.isBlank() || personPartyId == null
                    || personPartyId.isBlank() || childId == null || childId.isBlank()) {
                throw new IllegalArgumentException("子单引用字段不能为空");
            }
        }
    }

    /** 运行桥传输用：确认参数。人物入口仍用直接参数。 */
    public record ConfirmDraft(String targetId, int expectedVersion, int riskRevision) {
        /** 校验目标及待确认版本。 */
        public ConfirmDraft {
            requireText(targetId);
            if (expectedVersion < 1 || riskRevision < 1) {
                throw new IllegalArgumentException("确认版本必须为正");
            }
        }
    }

    /** 运行桥传输用：取消目标参数。 */
    public record CancelTargetDraft(String targetId, int expectedVersion) {
        /** 校验目标及待取消版本。 */
        public CancelTargetDraft {
            requireText(targetId);
            if (expectedVersion < 1) {
                throw new IllegalArgumentException("取消版本必须为正");
            }
        }
    }

    private static void requireText(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("业务文本字段不能为空");
            }
        }
    }

    private static void requirePrice(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("价格须为正且精确到分");
        }
    }

    // ------------------------------------------------------------------
    // 只读
    // ------------------------------------------------------------------

    /**
     * 本次观察的只读视图。
     *
     * @return 视图；世界时间可能已前进，但任职映射保持打开时的固定观察
     * @throws IOException 读取持久状态失败
     */
    public RoleTradingView view() throws IOException {
        return runtime.roleTradingView(this);
    }

    /**
     * 本人能否引用某资料的固定版本。
     *
     * @param docId   资料
     * @param version 版本
     * @return 已在世界当前时点前读完返回 true
     */
    public boolean canCite(String docId, long version) {
        return runtime.canCite(personPartyId, docId, version);
    }

    // ------------------------------------------------------------------
    // 阅读（复用阅读处理器；组织资料另核对观察任职）
    // ------------------------------------------------------------------

    /**
     * 开始阅读一个明确版本。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒，必须晚于当前时点
     * @param docId           资料
     * @param version         固定版本
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String startReading(String clientCommandId, String clientTaskId, long dueMillis,
                               String docId, long version) throws IOException {
        return runtime.scheduleRoleRead(this, clientCommandId, clientTaskId, dueMillis, docId,
                version);
    }

    /**
     * 取消本人正在读的一单。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param ref             本人范围引用（资料 + 版本 + 本人序号）
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String cancelReading(String clientCommandId, String clientTaskId, long dueMillis,
                                RoleView.ReadingRef ref) throws IOException {
        return runtime.scheduleRoleUnread(this, clientCommandId, clientTaskId, dueMillis, ref);
    }

    // ------------------------------------------------------------------
    // 四个岗位动作
    // ------------------------------------------------------------------

    /**
     * 研究员提交组织研究报告。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           研究草稿
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String submitResearch(String clientCommandId, String clientTaskId, long dueMillis,
                                 ResearchDraft draft) throws IOException {
        return runtime.scheduleRoleAction(this, PendingTask.ROLE_TRADING_RESEARCH_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 经理设置或修改绝对目标。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           目标草稿
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String setTarget(String clientCommandId, String clientTaskId, long dueMillis,
                            TargetDraft draft) throws IOException {
        return runtime.scheduleRoleAction(this, PendingTask.ROLE_TRADING_TARGET_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 风控给出审核。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           审核草稿
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String reviewTarget(String clientCommandId, String clientTaskId, long dueMillis,
                               ReviewDraft draft) throws IOException {
        return runtime.scheduleRoleAction(this, PendingTask.ROLE_TRADING_REVIEW_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 风控发出有期限的暂停。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           暂停草稿
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String haltTarget(String clientCommandId, String clientTaskId, long dueMillis,
                             HaltDraft draft) throws IOException {
        return runtime.scheduleRoleAction(this, PendingTask.ROLE_TRADING_HALT_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 经理确认获批版本。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param targetId        目标
     * @param expectedVersion 执行版本
     * @param riskRevision    审核版本
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String confirmTarget(String clientCommandId, String clientTaskId, long dueMillis,
                                String targetId, int expectedVersion, int riskRevision)
            throws IOException {
        return runtime.scheduleRoleAction(this, PendingTask.ROLE_TRADING_CONFIRM_V1,
                clientCommandId, clientTaskId, dueMillis,
                new ConfirmDraft(targetId, expectedVersion, riskRevision));
    }

    /**
     * 经理取消目标。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param targetId        目标
     * @param expectedVersion 执行版本
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String cancelTarget(String clientCommandId, String clientTaskId, long dueMillis,
                               String targetId, int expectedVersion) throws IOException {
        return runtime.scheduleRoleAction(this, PendingTask.ROLE_TRADING_CANCEL_V1,
                clientCommandId, clientTaskId, dueMillis,
                new CancelTargetDraft(targetId, expectedVersion));
    }

    /**
     * 执行员提交子单。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           子单草稿
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String submitChild(String clientCommandId, String clientTaskId, long dueMillis,
                              ChildDraft draft) throws IOException {
        return runtime.scheduleRoleAction(this, PendingTask.ROLE_TRADING_CHILD_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 执行员撤销本人子单。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param ref             本人子单引用
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String cancelChild(String clientCommandId, String clientTaskId, long dueMillis,
                              ChildRef ref) throws IOException {
        return runtime.scheduleRoleAction(this, PendingTask.ROLE_TRADING_CHILD_CANCEL_V1,
                clientCommandId, clientTaskId, dueMillis, ref);
    }

    /**
     * 形成/重申一条主观判断；person 与核验发布者由会话与观察帧决定，模型不可填。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           判断草稿
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String assertBelief(String clientCommandId, String clientTaskId, long dueMillis,
                               BeliefDraft draft) throws IOException {
        return runtime.scheduleRoleAction(this, PendingTask.ROLE_TRADING_BELIEF_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 发布一条公开结构化来源预测（文档与主张同批）。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           预测草稿
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String publishForecast(String clientCommandId, String clientTaskId, long dueMillis,
                                  ForecastDraft draft) throws IOException {
        return runtime.scheduleRoleAction(this, PendingTask.ROLE_TRADING_FORECAST_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 以当前章程主席身份请求公司在未来精确时点发布订单事实公告。
     *
     * <p>{@code dueMillis} 是这条请求完成的正工时点（必须晚于当前）；真正的事实时点由
     * {@code draft.asOfMillis()} 给出，必须晚于请求完成时刻。捕获发生在 asOf 的 OBSERVATION 阶段，
     * 发布在 asOf+1 毫秒。</p>
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       请求完成时刻（正工时）
     * @param draft           请求草稿（含未来 asOf）
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String reportOrders(String clientCommandId, String clientTaskId, long dueMillis,
                               ReportDraft draft) throws IOException {
        return runtime.scheduleRoleAction(this, PendingTask.ROLE_TRADING_COMPANY_REPORT_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 主观判断草稿：只含模型可见的业务字段。
     *
     * @param beliefId          本人判断标识
     * @param companyId         目标公司
     * @param comparator        比较方向
     * @param threshold         阈值
     * @param targetAsOfMillis  精确目标时点
     * @param adoptedClaimId    明确采纳的来源主张；自拟为空
     * @param evidence          1..3 条本人 scoped 阅读引用
     * @param rationale         本人自述，可空
     */
    public record BeliefDraft(String beliefId, String companyId,
                              BeliefModel.Comparator comparator, long threshold,
                              long targetAsOfMillis, String adoptedClaimId,
                              List<RoleView.ReadingRef> evidence, String rationale) {

        /** 校验静态字段。 */
        public BeliefDraft {
            requireText(beliefId, "判断标识");
            requireText(companyId, "判断公司");
            Objects.requireNonNull(comparator, "比较方向");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "判断证据"));
        }
    }

    /**
     * 公开来源预测草稿：正文只是声明，不被解析成权威事实。
     *
     * @param claimId                稳定主张标识
     * @param docId                  新文档标识
     * @param title                  标题
     * @param body                   正文
     * @param publicSourceDescription 公开来源描述
     * @param readDurationMillis     固定阅读耗时
     * @param companyId              目标公司
     * @param comparator             比较方向
     * @param threshold              阈值
     * @param targetAsOfMillis       精确目标时点
     */
    public record ForecastDraft(String claimId, String docId, String title, String body,
                                String publicSourceDescription, long readDurationMillis,
                                String companyId, BeliefModel.Comparator comparator,
                                long threshold, long targetAsOfMillis) {

        /** 校验静态字段。 */
        public ForecastDraft {
            requireText(claimId, "来源主张标识");
            requireText(docId, "来源主张文档");
            requireText(title, "来源主张标题");
            requireText(body, "来源主张正文");
            Objects.requireNonNull(publicSourceDescription, "公开来源描述");
            requireText(companyId, "来源主张公司");
            Objects.requireNonNull(comparator, "来源主张比较");
            if (readDurationMillis <= 0) {
                throw new IllegalArgumentException("阅读耗时必须为正：" + readDurationMillis);
            }
        }
    }

    /**
     * 公司事实公告请求草稿。
     *
     * @param companyId   公司主体
     * @param asOfMillis  事实时点（未来）
     */
    public record ReportDraft(String companyId, long asOfMillis) {

        /** 校验字段。 */
        public ReportDraft {
            requireText(companyId, "公司主体");
            if (asOfMillis <= 0) {
                throw new IllegalArgumentException("事实时点必须为正：" + asOfMillis);
            }
        }
    }

    // ------------------------------------------------------------------
    // 媒体采编：工具参数只含业务字段，人/组织/策略/工时由会话与冻结配置提供
    // ------------------------------------------------------------------

    /**
     * 提出选题草稿。
     *
     * @param storyId  稿件标识（本人命名空间）
     * @param template 模板名
     * @param topic    选题
     */
    public record MediaProposeDraft(String storyId, String template, String topic) {

        /** 校验字段。 */
        public MediaProposeDraft {
            requireText(storyId, "稿件标识");
            requireText(template, "媒体模板");
            requireText(topic, "选题");
        }
    }

    /**
     * 一条证据引用：只给资料、版本与本人阅读序号，人物由会话绑定。
     *
     * @param docId   资料
     * @param version 固定版本
     * @param index   本人阅读序号
     */
    public record MediaCitation(String docId, long version, long index) {

        /** 校验字段。 */
        public MediaCitation {
            requireText(docId, "证据资料");
            if (version < 1 || index < 1) {
                throw new IllegalArgumentException("证据版本与序号必须从 1 开始");
            }
        }
    }

    /**
     * 提交组织草稿。
     *
     * @param storyId                 稿件
     * @param expectedRevision        期望提交次数
     * @param title                   标题
     * @param body                    正文
     * @param publicSourceDescription 公开来源描述
     * @param evidence                本人范围证据（公开与本人真实读过的非公开材料同列）
     * @param aliases                 本版展示别名；只给根与标签，旧档案省略
     */
    public record MediaSubmitDraft(String storyId, int expectedRevision, String title, String body,
                                   String publicSourceDescription, List<MediaCitation> evidence,
                                   @JsonInclude(JsonInclude.Include.NON_EMPTY)
                                   List<AliasDraft> aliases) {

        /** 旧六参构造：没有别名，序列化与旧 schema 逐字一致。 */
        public MediaSubmitDraft(String storyId, int expectedRevision, String title, String body,
                                String publicSourceDescription, List<MediaCitation> evidence) {
            this(storyId, expectedRevision, title, body, publicSourceDescription, evidence,
                    List.of());
        }

        /** 校验字段并冻结证据。 */
        public MediaSubmitDraft {
            requireText(storyId, "稿件标识");
            requireText(title, "稿件标题");
            requireText(body, "稿件正文");
            Objects.requireNonNull(publicSourceDescription, "公开来源描述");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "证据"));
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("期望提交次数不能为负：" + expectedRevision);
            }
        }
    }

    /**
     * 一条展示别名：只给真实根与公开标签。
     *
     * <p>稿件与提交次数由真实投稿派生（本版真实证据展开出的根），模型不能指定别稿或旧版本。</p>
     *
     * @param rootDocId   真实根资料
     * @param rootVersion 根版本
     * @param label       公开标签
     */
    public record AliasDraft(String rootDocId, long rootVersion, String label) {

        /** 校验字段。 */
        public AliasDraft {
            requireText(rootDocId, "别名根资料");
            requireText(label, "公开标签");
            if (rootVersion < 1) {
                throw new IllegalArgumentException("别名根版本必须从 1 开始：" + rootVersion);
            }
        }
    }

    /**
     * 身份共享：把本人真实读过的材料以身份说明共享给指定接收者。
     *
     * @param rootDocId        本人实际读到的根（可以是他人共享过来的身份材料，按绑定链归原根）
     * @param rootVersion      该根版本
     * @param recipientPartyId 真实接收者
     * @param note             可选补充说明
     */
    public record MediaShareDraft(String rootDocId, long rootVersion, String recipientPartyId,
                                  String note) {

        /** 校验字段。 */
        public MediaShareDraft {
            requireText(rootDocId, "共享根资料");
            requireText(recipientPartyId, "接收者");
            if (rootVersion < 1) {
                throw new IllegalArgumentException("共享根版本必须从 1 开始：" + rootVersion);
            }
            if (note == null) {
                note = "";
            }
        }
    }

    /**
     * 编辑审阅。
     *
     * @param storyId          稿件
     * @param expectedRevision 期望提交次数
     * @param approve          true 批准
     * @param note             退回理由
     */
    public record MediaReviewDraft(String storyId, int expectedRevision, boolean approve,
                                   String note) {

        /** 校验字段。 */
        public MediaReviewDraft {
            requireText(storyId, "稿件标识");
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("审阅必须指向已提交版本：" + expectedRevision);
            }
            if (note == null) {
                note = "";
            }
        }
    }

    /**
     * 发布或开启更正：只给稿件与版本。
     *
     * @param storyId          稿件
     * @param expectedRevision 期望提交次数
     */
    public record MediaStoryDraft(String storyId, int expectedRevision) {

        /** 校验字段。 */
        public MediaStoryDraft {
            requireText(storyId, "稿件标识");
            if (expectedRevision < 1) {
                throw new IllegalArgumentException("必须指向已存在的提交次数：" + expectedRevision);
            }
        }
    }

    /**
     * 记者提出选题。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           选题草稿
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String proposeStory(String clientCommandId, String clientTaskId, long dueMillis,
                               MediaProposeDraft draft) throws IOException {
        return runtime.scheduleMediaStory(this, PendingTask.MEDIA_STORY_PROPOSE_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 记者发起采访邀请：只给对象、主题、时间窗与时长，请求标识由运行层按本人命名空间生成。
     *
     * @param requestClientId   本人客户端请求标识
     * @param toPartyId         采访对象
     * @param topic             主题
     * @param windowStartMillis 时间窗起点
     * @param windowEndMillis   时间窗终点
     * @param durationMillis    预约时长
     */
    public record InterviewInviteDraft(String requestClientId, String toPartyId, String topic,
                                       long windowStartMillis, long windowEndMillis,
                                       long durationMillis) {

        /** 校验字段。 */
        public InterviewInviteDraft {
            requireText(requestClientId, "客户端请求标识");
            requireText(toPartyId, "采访对象");
            requireText(topic, "采访主题");
            if (windowStartMillis < 0 || windowEndMillis <= windowStartMillis) {
                throw new IllegalArgumentException("时间窗必须是合法半开区间");
            }
            if (durationMillis < 1) {
                throw new IllegalArgumentException("预约时长必须为正");
            }
        }
    }

    /**
     * 回应采访邀请：接受时必须给出一个合法开始时刻。
     *
     * @param requestId   完整请求标识（从本人投影得到）
     * @param accept      是否接受
     * @param startMillis 接受时的开始时刻；拒绝时为 {@code -1}
     */
    public record InterviewRespondDraft(String requestId, boolean accept, long startMillis) {

        /** 校验字段。 */
        public InterviewRespondDraft {
            requireText(requestId, "请求标识");
            if (accept && startMillis < 0) {
                throw new IllegalArgumentException("接受必须给出开始时刻");
            }
            if (!accept && startMillis != -1L) {
                throw new IllegalArgumentException("拒绝不携带开始时刻");
            }
        }
    }

    /**
     * 指向一个已有采访请求：用于取消或结束。
     *
     * @param requestId 完整请求标识
     */
    public record InterviewRequestDraft(String requestId) {

        /** 校验字段。 */
        public InterviewRequestDraft {
            requireText(requestId, "请求标识");
        }
    }

    /**
     * 发送一轮访谈正文；轮次标识与开始时刻由运行桥派生，模型不能提供。
     *
     * @param requestId          目标会话的完整请求标识
     * @param title              本轮标题
     * @param body               本轮正文
     * @param readDurationMillis 本轮固定阅读耗时
     */
    public record DialogueSpeakDraft(String requestId, String title, String body,
                                     long readDurationMillis) {

        /** 校验字段。 */
        public DialogueSpeakDraft {
            requireText(requestId, "请求标识");
            requireText(title, "轮次标题");
            requireText(body, "轮次正文");
            if (readDurationMillis < 1) {
                throw new IllegalArgumentException("阅读耗时必须为正：" + readDurationMillis);
            }
        }
    }

    /**
     * 记者发起采访邀请。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           邀请参数
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String inviteInterview(String clientCommandId, String clientTaskId, long dueMillis,
                                  InterviewInviteDraft draft) throws IOException {
        return runtime.scheduleInterview(this, PendingTask.INTERVIEW_INVITE_V1, clientCommandId,
                clientTaskId, dueMillis, draft);
    }

    /**
     * 回应采访邀请；普通受访者无需媒体任职。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           回应参数
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String respondInterview(String clientCommandId, String clientTaskId, long dueMillis,
                                   InterviewRespondDraft draft) throws IOException {
        return runtime.scheduleInterview(this, PendingTask.INTERVIEW_RESPOND_V1, clientCommandId,
                clientTaskId, dueMillis, draft);
    }

    /**
     * 取消尚未开始的采访预约；双方都可取消，撤职也不阻断。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           请求引用
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String cancelInterview(String clientCommandId, String clientTaskId, long dueMillis,
                                  InterviewRequestDraft draft) throws IOException {
        return runtime.scheduleInterview(this, PendingTask.INTERVIEW_CANCEL_V1, clientCommandId,
                clientTaskId, dueMillis, draft);
    }

    /**
     * 结束进行中的采访会话；提前结束只占实际区间。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           请求引用
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String endInterview(String clientCommandId, String clientTaskId, long dueMillis,
                               InterviewRequestDraft draft) throws IOException {
        return runtime.scheduleInterview(this, PendingTask.INTERVIEW_END_V1, clientCommandId,
                clientTaskId, dueMillis, draft);
    }

    /**
     * 发送一轮访谈正文；仅 schema15 且本人在 ACTIVE 会话中可用。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           轮次业务内容
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String speakInterview(String clientCommandId, String clientTaskId, long dueMillis,
                                 DialogueSpeakDraft draft) throws IOException {
        return runtime.scheduleInterview(this, PendingTask.INTERVIEW_SPEAK_V1, clientCommandId,
                clientTaskId, dueMillis, draft);
    }

    /**
     * 作者提交组织草稿。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           提交草稿
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String submitStory(String clientCommandId, String clientTaskId, long dueMillis,
                              MediaSubmitDraft draft) throws IOException {
        return runtime.scheduleMediaStory(this, PendingTask.MEDIA_STORY_SUBMIT_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 以真实身份材料共享给指定接收者；稿件与提交次数不参与，人物由会话绑定。
     *
     * <p>共享是真实媒体动作：占用冻结的共享工时，失败按现有媒体路径记为业务拒绝。</p>
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           共享参数
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String shareEvidence(String clientCommandId, String clientTaskId, long dueMillis,
                                MediaShareDraft draft) throws IOException {
        return runtime.scheduleMediaStory(this, PendingTask.MEDIA_EVIDENCE_SHARE_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 编辑审阅。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           审阅草稿
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String reviewStory(String clientCommandId, String clientTaskId, long dueMillis,
                              MediaReviewDraft draft) throws IOException {
        return runtime.scheduleMediaStory(this, PendingTask.MEDIA_STORY_REVIEW_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 编辑发布已批准版本。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           稿件与版本
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String publishStory(String clientCommandId, String clientTaskId, long dueMillis,
                               MediaStoryDraft draft) throws IOException {
        return runtime.scheduleMediaStory(this, PendingTask.MEDIA_STORY_PUBLISH_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 作者开启一轮更正。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @param draft           稿件与版本
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String startCorrection(String clientCommandId, String clientTaskId, long dueMillis,
                                  MediaStoryDraft draft) throws IOException {
        return runtime.scheduleMediaStory(this, PendingTask.MEDIA_STORY_CORRECTION_V1,
                clientCommandId, clientTaskId, dueMillis, draft);
    }

    /**
     * 取消本人未完成的媒体工作。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param dueMillis       到期模拟毫秒
     * @return {@code SCHEDULED:clientTaskId}
     * @throws IOException 持久提交失败
     */
    public String cancelMediaWork(String clientCommandId, String clientTaskId, long dueMillis)
            throws IOException {
        return runtime.scheduleMediaCancel(this, clientCommandId, clientTaskId, dueMillis);
    }
}
