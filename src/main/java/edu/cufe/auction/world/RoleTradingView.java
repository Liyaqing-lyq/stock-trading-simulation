package edu.cufe.auction.world;

import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.RoleTradingModel.Status;
import edu.cufe.auction.world.WorldRoleSession.ChildRef;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 四岗人物在本次观察内的只读视图：由同一份已确认世界快照派生，不是管理端原始状态。
 *
 * <p><b>不包含</b>：全局订单号、入簿序号、提交位置、他人账户/持仓/研究/回报、研究引用的内部
 * 来源标识。研究正文只来自<b>本人真正读完</b>的固定版本；未读条目只给标题元数据。</p>
 *
 * <p><b>工具口径</b>：{@link #tools()} 只列本单元真正接入、且本次观察有效岗位允许的动作；
 * 没有实现的能力（改他人目标、查全局订单、直接改账）不出现。</p>
 *
 * @param observedAtMillis 会话固定观察时点
 * @param timeMillis       本视图派生时的世界时点（≥ 观察时点）
 * @param personPartyId    视图所属人物
 * @param profile          原 {@link RoleView}（schema6 语义保持不变）
 * @param appointments     本次观察固定的组织→任职映射
 * @param research         授权产品内的组织研究报告目录
 * @param targets          本组织产品的母任务进度
 * @param children         本人子单进度
 * @param notices          本人 client 回报
 * @param tools            本次观察可执行的工具名
 */
public record RoleTradingView(long observedAtMillis, long timeMillis, String personPartyId,
                              RoleView profile, List<Appointment> appointments,
                              List<ResearchView> research, List<TargetView> targets,
                              List<ChildView> children, List<Notice> notices, List<String> tools) {

    /** 阅读工具：开始读一个明确版本。 */
    public static final String TOOL_READING_START = "role.reading.start";
    /** 阅读工具：取消本人在读的一单。 */
    public static final String TOOL_READING_CANCEL = "role.reading.cancel";
    /** 引用工具：判定本人能否引用固定版本。 */
    public static final String TOOL_CITE = "role.cite";
    /** 研究员工具：提交组织研究报告。 */
    public static final String TOOL_RESEARCH_SUBMIT = "role.research.submit";
    /** 经理工具：设置或修改绝对目标。 */
    public static final String TOOL_TARGET_SET = "role.target.set";
    /** 风控工具：给出显式审核。 */
    public static final String TOOL_TARGET_REVIEW = "role.target.review";
    /** 风控工具：有期限的暂停。 */
    public static final String TOOL_TARGET_HALT = "role.target.halt";
    /** 经理工具：确认获批版本。 */
    public static final String TOOL_TARGET_CONFIRM = "role.target.confirm";
    /** 经理工具：取消目标。 */
    public static final String TOOL_TARGET_CANCEL = "role.target.cancel";
    /** 执行员工具：提交子单。 */
    public static final String TOOL_CHILD_SUBMIT = "role.child.submit";
    /** 执行员工具：撤销本人子单。 */
    public static final String TOOL_CHILD_CANCEL = "role.child.cancel";

    /** 认知工具：形成/重申一条主观判断。 */
    public static final String TOOL_BELIEF_ASSERT = "belief.assert";

    /** 认知工具：发布公开结构化来源预测。 */
    public static final String TOOL_FORECAST_PUBLISH = "forecast.publish";

    /** 认知工具：主席请求公司订单事实公告。 */
    public static final String TOOL_COMPANY_REPORT_ORDERS = "company.reportOrders";

    /** 冻结全部集合。 */
    public RoleTradingView {
        if (observedAtMillis < 0 || timeMillis < 0) {
            throw new IllegalArgumentException("视图时点不能为负");
        }
        requireText(personPartyId, "视图人物");
        Objects.requireNonNull(profile, "角色视图");
        appointments = List.copyOf(appointments);
        research = List.copyOf(research);
        targets = List.copyOf(targets);
        children = List.copyOf(children);
        notices = List.copyOf(notices);
        tools = List.copyOf(tools);
    }

    /**
     * 本次观察固定的任职。
     *
     * @param appointmentId       任职标识
     * @param organizationPartyId 组织
     * @param role                岗位
     * @param endsAt              有效结束时刻（不含）
     */
    public record Appointment(String appointmentId, String organizationPartyId,
                              RoleAccess.Role role, long endsAt) {
    }

    /**
     * 组织研究报告目录条目。
     *
     * @param researchId     研究标识
     * @param organizationPartyId 组织
     * @param productOwnerId 产品
     * @param symbol         标的
     * @param docId          报告文档
     * @param docVersion     固定版本
     * @param title          标题
     * @param body           正文；本人未读完该版本时为空
     * @param read           本人是否已读完该版本
     */
    public record ResearchView(String researchId, String organizationPartyId,
                               String productOwnerId, String symbol, String docId, long docVersion,
                               String title, String body, boolean read) {
    }

    /**
     * 一个执行版本。
     *
     * @param version        版本号
     * @param absoluteTarget 绝对目标股数
     * @param baseQuantity   建立基点时的真实持仓
     * @param changeQuantity 相对基点的最大变动量
     * @param side           方向；已达到目标为空
     * @param priceLimit     价格边界
     * @param deadlineMillis 截止
     */
    public record Version(int version, long absoluteTarget, long baseQuantity, long changeQuantity,
                          Side side, BigDecimal priceLimit, long deadlineMillis) {
    }

    /**
     * 母任务进度。
     *
     * @param targetId    目标
     * @param organizationPartyId 组织
     * @param productOwnerId 产品
     * @param accountId   账户
     * @param symbol      标的
     * @param status      当前阶段
     * @param haltedUntilMillis 暂停截止；未暂停为 0
     * @param current     当前执行版本
     * @param versions    全部版本
     * @param review      当前版本最新审核；无审核为空
     * @param filled      当前版本真实成交量
     * @param leaves      当前版本活动剩余量
     * @param remaining   目标绝对差额中尚未被执行的部分
     */
    public record TargetView(String targetId, String organizationPartyId, String productOwnerId,
                             String accountId, String symbol, Status status,
                             long haltedUntilMillis, Version current, List<Version> versions,
                             ReviewView review, long filled, long leaves, long remaining) {
    }

    /**
     * 当前版本最新审核。
     *
     * @param revision      审核版本
     * @param approved      是否批准
     * @param maxTotal      批准总量
     * @param maxChild      单笔上限
     * @param priceLimit    价格边界
     * @param validUntil    有效期（不含）
     * @param reason        理由
     */
    public record ReviewView(int revision, boolean approved, long maxTotal, long maxChild,
                             BigDecimal priceLimit, long validUntil, String reason) {
    }

    /**
     * 本人子单进度；不含全局订单号。
     *
     * @param ref        本人范围引用
     * @param parentVersion 所属执行版本
     * @param quantity   原数量
     * @param filled     累计成交
     * @param leaves     活动剩余
     * @param limitPrice 限价
     * @param status     订单状态名
     */
    public record ChildView(ChildRef ref, int parentVersion, long quantity, long filled,
                            long leaves, BigDecimal limitPrice, String status) {
    }

    /**
     * 本人 client 回报。
     *
     * @param clientCommandId 本人命令标识
     * @param clientTaskId    本人任务标识
     * @param kind            动作种类
     * @param accepted        是否受理
     * @param reason          结果代码
     * @param timeMillis      执行时点
     */
    public record Notice(String clientCommandId, String clientTaskId, String kind,
                         boolean accepted, String reason, long timeMillis) {
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
