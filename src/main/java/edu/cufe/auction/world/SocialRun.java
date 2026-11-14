package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一对一消息的运行状态：冻结配置、运行层工时、受信联系入口、纯核社交状态与任务报告。
 *
 * <p>正文唯一权威是投递完成时的 {@link InformationModel} DIRECT 文档；本类只保存元数据、
 * 屏蔽事实、命令裁决与报告，不保存正文、标题副本、阅读账或余额。旧 schema ≤16 该字段为 null
 * 且不序列化。</p>
 */
public record SocialRun(SocialMessageModel.Config config, long blockMillis,
                        List<InterviewRun.ContactEntry> contacts,
                        SocialMessageModel.State state,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SocialReport> reports) {

    /** barrier 到达后开始写信的门任务。 */
    public static final String KIND_BEGIN = PendingTask.SOCIAL_BEGIN_V1;
    /** 写信完成结算任务。 */
    public static final String KIND_FINISH = PendingTask.SOCIAL_FINISH_V1;
    /** 计划投递任务。 */
    public static final String KIND_DELIVER = PendingTask.SOCIAL_DELIVER_V1;
    /** 屏蔽偏好动作。 */
    public static final String KIND_BLOCK = PendingTask.SOCIAL_BLOCK_V1;
    /** 解除屏蔽动作。 */
    public static final String KIND_UNBLOCK = PendingTask.SOCIAL_UNBLOCK_V1;

    /** 本模块全部任务种类（只进本模块校验，不混入媒体/采访流）。 */
    public static final Set<String> KINDS = Set.of(KIND_BEGIN, KIND_FINISH, KIND_DELIVER,
            KIND_BLOCK, KIND_UNBLOCK);

    /** 业务成功码。 */
    public static final String REASON_OK = "OK";

    /**
     * 一条任务报告：拒绝也留下，供管理员审计；position 只进管理视图。
     *
     * @param taskId    内部任务标识
     * @param person    相关人物（发件人或屏蔽发起人）
     * @param kind      任务种类
     * @param accepted  业务是否成功
     * @param reason    原因码（见 api.md；UNREACHABLE 属业务拒绝，非 TECH_FAIL）
     * @param messageId 相关消息；屏蔽类动作为空串
     * @param atMillis  业务时点（到期时刻）
     * @param position  提交位置；只给管理端
     */
    public record SocialReport(String taskId, String person, String kind, boolean accepted,
                               String reason, String messageId, long atMillis, long position) {

        /** 校验字段。 */
        public SocialReport {
            requireText(taskId, "任务标识");
            requireText(person, "人物");
            requireText(kind, "任务种类");
            requireText(reason, "原因码");
            if (messageId == null) {
                messageId = "";
            }
            if (atMillis < 0 || position < 0) {
                throw new IllegalArgumentException("报告时点或位置非法：" + atMillis + "/" + position);
            }
        }
    }

    /** 冻结配置、工时并校验。 */
    public SocialRun {
        Objects.requireNonNull(config, "社交配置");
        if (config.sendMillis() < 1 || config.deliveryMillis() < 1
                || config.readDurationMillis() < 1) {
            throw new IllegalArgumentException("社交工时必须为正");
        }
        if (blockMillis < 1) {
            throw new IllegalArgumentException("屏蔽工时必须为正：" + blockMillis);
        }
        List<InterviewRun.ContactEntry> frozen = new ArrayList<>(
                contacts == null ? List.of() : contacts);
        for (InterviewRun.ContactEntry entry : frozen) {
            Objects.requireNonNull(entry, "联系入口");
        }
        frozen.sort(Comparator.comparing(InterviewRun.ContactEntry::targetPartyId)
                .thenComparing(InterviewRun.ContactEntry::channel)
                .thenComparing(InterviewRun.ContactEntry::docId)
                .thenComparingLong(InterviewRun.ContactEntry::version));
        Set<String> keys = new LinkedHashSet<>();
        for (InterviewRun.ContactEntry entry : frozen) {
            String key = entry.targetPartyId() + '\u0000' + entry.channel() + '\u0000'
                    + entry.docId() + '\u0000' + entry.version();
            if (!keys.add(key)) {
                throw new IllegalArgumentException("重复的联系入口：" + key);
            }
        }
        contacts = List.copyOf(frozen);
        Objects.requireNonNull(state, "社交纯核状态");
        List<SocialReport> copy = new ArrayList<>(reports == null ? List.of() : reports);
        copy.sort(Comparator.comparing(SocialReport::atMillis)
                .thenComparing(SocialReport::taskId));
        Set<String> tasks = new LinkedHashSet<>();
        for (SocialReport report : copy) {
            if (!tasks.add(report.taskId())) {
                throw new IllegalArgumentException("同一任务只能有一条社交报告：" + report.taskId());
            }
        }
        reports = List.copyOf(copy);
    }

    /**
     * 开局：空纯核状态。
     *
     * @param config      冻结的纯核配置
     * @param blockMillis 屏蔽/解除屏蔽的模拟工时
     * @param contacts    受信联系入口
     * @return 空社交运行状态
     */
    public static SocialRun open(SocialMessageModel.Config config, long blockMillis,
                                 List<InterviewRun.ContactEntry> contacts) {
        return new SocialRun(config, blockMillis, contacts, SocialMessageModel.open(), List.of());
    }

    /**
     * 本次动作的受信上下文：每次现建，不持久化。
     *
     * @param registry 开局登记
     * @param recall   回忆参数
     * @return 纯核上下文
     */
    public SocialMessageModel.Context context(OpeningRegistry registry,
                                              CognitionRecall.Policy recall) {
        return new SocialMessageModel.Context(registry, recall, config, contacts);
    }

    /**
     * 替换纯核状态。
     *
     * @param next 新状态
     * @return 新运行状态
     */
    public SocialRun withState(SocialMessageModel.State next) {
        return new SocialRun(config, blockMillis, contacts, next, reports);
    }

    /**
     * 追加一条报告；同一任务重复且不同内容直接拒绝。
     *
     * @param report 报告
     * @return 新运行状态
     */
    public SocialRun withReport(SocialReport report) {
        Objects.requireNonNull(report, "社交报告");
        for (SocialReport existing : reports) {
            if (existing.taskId().equals(report.taskId())) {
                if (!existing.equals(report)) {
                    throw new IllegalArgumentException("同一任务只能有一条社交报告："
                            + report.taskId());
                }
                return this;
            }
        }
        List<SocialReport> copy = new ArrayList<>(reports);
        copy.add(report);
        return new SocialRun(config, blockMillis, contacts, state, copy);
    }

    /**
     * 某任务的报告（管理员审计用）。
     *
     * @param taskId 任务标识
     * @return 报告；没有返回 null
     */
    public SocialReport reportOf(String taskId) {
        for (SocialReport report : reports) {
            if (report.taskId().equals(taskId)) {
                return report;
            }
        }
        return null;
    }

    /**
     * 本人可见社交投影：只含本人 inbox/outbox 与合法联系人，不含他人消息与正文。
     *
     * @param personPartyId 人物
     * @return 投影
     */
    public SocialView viewOf(String personPartyId) {
        return new SocialView(personPartyId, SocialMessageModel.inbox(state, personPartyId),
                SocialMessageModel.outbox(state, personPartyId), contacts);
    }

    /**
     * 人物可见社交视图。
     *
     * @param personPartyId 人物
     * @param inbox         本人已送达信封（无正文）
     * @param outbox        本人已写消息的投递结果
     * @param contacts      受信联系入口（本阶段全局清单；读取时由运行层按回忆过滤）
     */
    public record SocialView(String personPartyId,
                             List<SocialMessageModel.EnvelopeView> inbox,
                             List<SocialMessageModel.OutboxView> outbox,
                             List<InterviewRun.ContactEntry> contacts,
                             java.util.Set<String> readMessageIds) {

        /** 旧四参：没有任何已读信封。 */
        public SocialView(String personPartyId, List<SocialMessageModel.EnvelopeView> inbox,
                          List<SocialMessageModel.OutboxView> outbox,
                          List<InterviewRun.ContactEntry> contacts) {
            this(personPartyId, inbox, outbox, contacts, java.util.Set.of());
        }

        /** 冻结集合。 */
        public SocialView {
            requireText(personPartyId, "人物");
            inbox = List.copyOf(inbox == null ? List.of() : inbox);
            outbox = List.copyOf(outbox == null ? List.of() : outbox);
            contacts = List.copyOf(contacts == null ? List.of() : contacts);
            readMessageIds = readMessageIds == null ? java.util.Set.of()
                    : java.util.Set.copyOf(readMessageIds);
        }

        /** @param messageId 消息标识 @return 本人是否已真实读完该信封 */
        public boolean read(String messageId) {
            return readMessageIds.contains(messageId);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
