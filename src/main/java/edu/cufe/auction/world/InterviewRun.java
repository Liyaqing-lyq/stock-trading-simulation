package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * M4i.2a 采访预约协调子集：唯一世界日志里的不可变运行包装。
 *
 * <p><b>不复制任何业务真相</b>：邀请、会话与占用都在各媒体组织的 {@link InterviewModel.State} 里；
 * 本类型只保存"冻结配置 + 每机构一份纯核状态 + 协调结果"。<b>它不保存忙碌表、已读集合、轮次计数
 * 或第二份余额</b>：忙碌由运行层按 {@link InterviewModel#occupancy} 与真实待办现算，工时只记在
 * {@link MediaRun.Budget}。</p>
 *
 * <p><b>一个机构一份纯核状态</b>：{@link InterviewModel.Policy} 仍是单机构，所以跨机构同人双约要靠
 * 运行层把其他机构的占用拼进 {@code Context.busy}；本类型只保证 {@code states} 与
 * {@code config.policies()} 一一对应，不在这里做第二次占用规则实现。</p>
 *
 * <p><b>协调结果</b>：{@link Coordination} 只记"谁在什么时刻对哪个请求做了什么决定"。受访者通常没有
 * 媒体任职与机构工时账，因此不能借用 {@link MediaRun.Report} 的机构口径；本类型因此自带最小结果表。</p>
 *
 * @param config        冻结配置（每机构策略、四个动作时长、有出处的联系入口）
 * @param states        每机构一份纯核协调状态，按机构标识排序
 * @param coordinations 协调结果，按任务标识索引
 */
public record InterviewRun(Config config, List<InterviewModel.State> states,
                           Map<String, Coordination> coordinations,
                           @JsonInclude(JsonInclude.Include.NON_NULL)
                           Map<String, InterviewDialogue.State> dialogues) {

    /** 旧 14 档案构造：没有逐轮对话状态。 */
    public InterviewRun(Config config, List<InterviewModel.State> states,
                        Map<String, Coordination> coordinations) {
        this(config, states, coordinations, null);
    }

    /** 本子集登记的全部任务种类。 */
    public static final Set<String> KINDS = Set.of(
            PendingTask.INTERVIEW_INVITE_V1, PendingTask.INTERVIEW_RESPOND_V1,
            PendingTask.INTERVIEW_CANCEL_V1, PendingTask.INTERVIEW_END_V1,
            PendingTask.INTERVIEW_SPEAK_V1,
            PendingTask.INTERVIEW_START_GATE_V1, PendingTask.INTERVIEW_MISS_GATE_V1,
            PendingTask.INTERVIEW_END_GATE_V1, PendingTask.INTERVIEW_TTL_GATE_V1,
            PendingTask.INTERVIEW_DIALOGUE_DEADLINE_V1);

    /** 由人物命令发起的四种动作。 */
    public static final Set<String> COMMAND_KINDS = Set.of(
            PendingTask.INTERVIEW_INVITE_V1, PendingTask.INTERVIEW_RESPOND_V1,
            PendingTask.INTERVIEW_CANCEL_V1, PendingTask.INTERVIEW_END_V1,
            PendingTask.INTERVIEW_SPEAK_V1);

    /** 由真实先前状态派生的系统门任务。 */
    public static final Set<String> GATE_KINDS = Set.of(
            PendingTask.INTERVIEW_START_GATE_V1, PendingTask.INTERVIEW_MISS_GATE_V1,
            PendingTask.INTERVIEW_END_GATE_V1, PendingTask.INTERVIEW_TTL_GATE_V1,
            PendingTask.INTERVIEW_DIALOGUE_DEADLINE_V1);

    /**
     * 冻结配置：每机构纯核策略、四个动作的模拟耗时与有出处的联系入口。
     *
     * <p>动作耗时是实验参数，只决定排程到期的先后；<b>只有发起采访的记者机构</b>按动作实际占用计费，
     * 控制动作（取消/结束）与受访者动作永远 0 组织工时。</p>
     *
     * @param policies      每机构一条纯核策略，必须唯一且非空
     * @param inviteMillis  发起邀请的模拟耗时，正数
     * @param respondMillis 回应邀请的模拟耗时，正数
     * @param cancelMillis  取消预约的模拟耗时，正数
     * @param endMillis     结束会话的模拟耗时，正数
     * @param contacts      有出处的联系入口，可为空表
     */
    public record Config(List<InterviewModel.Policy> policies, long inviteMillis,
                         long respondMillis, long cancelMillis, long endMillis,
                         List<ContactEntry> contacts) {

        /** 校验机构唯一、时长全为正、联系入口字段合法。 */
        public Config {
            List<InterviewModel.Policy> list = new ArrayList<>(
                    Objects.requireNonNull(policies, "采访策略"));
            if (list.isEmpty()) {
                throw new IllegalArgumentException("采访运行至少需要一个媒体组织");
            }
            Set<String> organizations = new LinkedHashSet<>();
            for (InterviewModel.Policy policy : list) {
                Objects.requireNonNull(policy, "采访策略");
                if (!organizations.add(policy.mediaOrganizationPartyId())) {
                    throw new IllegalArgumentException("同一媒体组织只能有一条采访策略："
                            + policy.mediaOrganizationPartyId());
                }
            }
            list.sort(Comparator.comparing(InterviewModel.Policy::mediaOrganizationPartyId));
            policies = List.copyOf(list);
            if (inviteMillis < 1 || respondMillis < 1 || cancelMillis < 1 || endMillis < 1) {
                throw new IllegalArgumentException("采访动作时长必须为正毫秒");
            }
            List<ContactEntry> entries = new ArrayList<>(
                    Objects.requireNonNull(contacts, "联系入口"));
            List<ContactEntry> copies = new ArrayList<>();
            for (ContactEntry entry : entries) {
                copies.add(Objects.requireNonNull(entry, "联系入口"));
            }
            copies.sort(Comparator.comparing(ContactEntry::targetPartyId)
                    .thenComparing(ContactEntry::channel)
                    .thenComparing(ContactEntry::docId)
                    .thenComparingLong(ContactEntry::version));
            contacts = List.copyOf(copies);
        }

        /**
         * 该组织的冻结策略。
         *
         * @param organizationPartyId 媒体组织
         * @return 对应策略；不存在返回 null
         */
        public InterviewModel.Policy policyOf(String organizationPartyId) {
            for (InterviewModel.Policy policy : policies) {
                if (policy.mediaOrganizationPartyId().equals(organizationPartyId)) {
                    return policy;
                }
            }
            return null;
        }
    }

    /**
     * 一条有出处的联系入口：谁（被联系者）通过哪个渠道可以被联系，依据是哪份资料的哪一版。
     *
     * <p>{@code docId/version} 可以指向开局时尚未发布、稍后才发布的公开模拟联系说明：未发布或发起人
     * 尚未真实读完时不进入候选，但不会因此让整个运行打不开。{@code targetPartyId} 必须是在开局登记里
     * 存在的人物，模型不能自报联系人。</p>
     *
     * @param channel       渠道名，例如公开投关电话或公开邮箱入口
     * @param targetPartyId 被联系的人物标识
     * @param description   对发起人可见的联系说明
     * @param docId         锚定资料标识
     * @param version       锚定资料版本，从 1 开始
     */
    public record ContactEntry(String channel, String targetPartyId, String description,
                               String docId, long version) {

        /** 校验字段。 */
        public ContactEntry {
            requireText(channel, "联系渠道");
            requireText(targetPartyId, "被联系人物");
            requireText(description, "联系说明");
            requireText(docId, "锚定资料");
            if (version < 1) {
                throw new IllegalArgumentException("锚定资料版本必须从 1 开始：" + version);
            }
        }
    }

    /**
     * 一次采访动作的协调结果。
     *
     * <p>既覆盖记者也覆盖普通受访者；受访者没有机构，因此这里有意不放组织字段，也不进
     * {@link MediaRun.Budget}。同一任务标识只能有一条结果。</p>
     *
     * @param taskId        内部任务标识
     * @param personPartyId 执行人
     * @param kind          动作种类
     * @param accepted      业务是否成功
     * @param reason        结果代码
     * @param requestId     关联请求标识；无关联为空串
     * @param timeMillis    完成时点
     * @param position      提交位置
     */
    public record Coordination(String taskId, String personPartyId, String kind, boolean accepted,
                               String reason, String requestId, long timeMillis, long position) {

        /** 校验字段与种类。 */
        public Coordination {
            requireText(taskId, "协调结果任务");
            requireText(personPartyId, "协调结果执行人");
            if (!KINDS.contains(kind)) {
                throw new IllegalArgumentException("未登记的采访任务种类：" + kind);
            }
            requireText(reason, "协调结果原因");
            if (requestId == null) {
                requestId = "";
            }
            if (timeMillis < 0 || position < 1) {
                throw new IllegalArgumentException("协调结果时点或位置非法：" + taskId);
            }
        }
    }

    /** 深拷贝、排序并校验配置与状态的一一对应、结果索引一致性。 */
    public InterviewRun {
        Objects.requireNonNull(config, "采访配置");
        List<InterviewModel.State> stateList = new ArrayList<>(
                Objects.requireNonNull(states, "采访状态"));
        stateList.sort(Comparator.comparing(state -> state.policy().mediaOrganizationPartyId()));
        Map<String, InterviewModel.State> byOrganization = new LinkedHashMap<>();
        for (InterviewModel.State state : stateList) {
            String organization = state.policy().mediaOrganizationPartyId();
            if (byOrganization.put(organization, state) != null) {
                throw new IllegalArgumentException("同一媒体组织只能有一个采访状态：" + organization);
            }
            InterviewModel.Policy expected = config.policyOf(organization);
            if (expected == null || !expected.equals(state.policy())) {
                throw new IllegalArgumentException("采访状态策略与冻结配置不一致：" + organization);
            }
        }
        if (byOrganization.size() != config.policies().size()) {
            throw new IllegalArgumentException("采访状态必须覆盖全部配置组织");
        }
        Map<String, InterviewDialogue.State> dialogueMap = null;
        if (dialogues != null) {
            dialogueMap = new TreeMap<>();
            for (Map.Entry<String, InterviewDialogue.State> entry : dialogues.entrySet()) {
                if (!byOrganization.containsKey(entry.getKey())) {
                    throw new IllegalArgumentException("对话状态引用未配置组织：" + entry.getKey());
                }
                dialogueMap.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), "对话状态"));
            }
            dialogueMap = Collections.unmodifiableMap(dialogueMap);
        }
        dialogues = dialogueMap;
        Map<String, Coordination> resultMap = new LinkedHashMap<>(
                Objects.requireNonNull(coordinations, "协调结果"));
        Map<String, Coordination> sorted = new TreeMap<>(resultMap);
        for (Map.Entry<String, Coordination> entry : sorted.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().taskId())) {
                throw new IllegalArgumentException("协调结果索引键与任务标识不一致："
                        + entry.getKey());
            }
        }
        states = List.copyOf(stateList);
        coordinations = Collections.unmodifiableMap(sorted);
    }

    /**
     * 开局：按冻结配置为每个媒体组织建立空的纯核协调状态。
     *
     * @param config   冻结配置
     * @param registry 固定开局登记
     * @return 空采访子集
     * @throws IllegalArgumentException 配置非法或机构未登记
     */
    public static InterviewRun open(Config config, OpeningRegistry registry) {
        Objects.requireNonNull(config, "采访配置");
        List<InterviewModel.State> states = new ArrayList<>();
        for (InterviewModel.Policy policy : config.policies()) {
            states.add(InterviewModel.open(policy, registry));
        }
        return new InterviewRun(config, states, Map.of());
    }

    /**
     * 该组织的纯核状态。
     *
     * @param organizationPartyId 媒体组织
     * @return 对应状态；不存在返回 null
     */
    public InterviewModel.State stateOf(String organizationPartyId) {
        for (InterviewModel.State state : states) {
            if (state.policy().mediaOrganizationPartyId().equals(organizationPartyId)) {
                return state;
            }
        }
        return null;
    }

    /**
     * 替换某组织的纯核状态。
     *
     * @param organizationPartyId 媒体组织
     * @param next                新状态，必须沿用同一冻结策略
     * @return 替换后的新子集
     * @throws IllegalArgumentException 未配置的组织或策略不一致
     */
    public InterviewRun withState(String organizationPartyId, InterviewModel.State next) {
        Objects.requireNonNull(next, "新采访状态");
        List<InterviewModel.State> copy = new ArrayList<>(states);
        for (int index = 0; index < copy.size(); index++) {
            if (copy.get(index).policy().mediaOrganizationPartyId().equals(organizationPartyId)) {
                copy.set(index, next);
                return new InterviewRun(config, copy, coordinations, dialogues);
            }
        }
        throw new IllegalArgumentException("未配置的媒体组织：" + organizationPartyId);
    }

    /**
     * 追加一条协调结果；同一任务重复且内容不同直接拒绝，内容相同幂等返回。
     *
     * @param result 协调结果
     * @return 追加后的新子集
     */
    public InterviewRun withCoordination(Coordination result) {
        Objects.requireNonNull(result, "协调结果");
        Coordination existing = coordinations.get(result.taskId());
        if (existing != null) {
            if (!existing.equals(result)) {
                throw new IllegalArgumentException("同一采访任务只能有一条协调结果："
                        + result.taskId());
            }
            return this;
        }
        Map<String, Coordination> copy = new TreeMap<>(coordinations);
        copy.put(result.taskId(), result);
        return new InterviewRun(config, states, copy, dialogues);
    }

    /** 替换某组织的对话状态；未配置组织拒绝。 */
    public InterviewRun withDialogues(String organizationPartyId, InterviewDialogue.State next) {
        Objects.requireNonNull(next, "对话状态");
        if (stateOf(organizationPartyId) == null) {
            throw new IllegalArgumentException("未配置的媒体组织：" + organizationPartyId);
        }
        Map<String, InterviewDialogue.State> copy = new TreeMap<>(
                dialogues == null ? Map.of() : dialogues);
        copy.put(organizationPartyId, next);
        return new InterviewRun(config, states, coordinations, copy);
    }

    /** @param organizationPartyId 媒体组织 @return 该组织对话状态；没有返回空状态 */
    public InterviewDialogue.State dialoguesOf(String organizationPartyId) {
        return dialogues == null ? InterviewDialogue.open()
                : dialogues.getOrDefault(organizationPartyId, InterviewDialogue.open());
    }

    /**
     * 该机构当前的采访在途预留：所有 {@code ACCEPTED/ACTIVE} 预约的<b>完整</b>区间之和。
     *
     * <p>已消耗但尚未结算的工时绝不提前释放，因此这里<b>不</b>随当前时刻递减；会话结束时会话状态
     * 离开 {@code ACCEPTED/ACTIVE}，整条预留一次性移除，只释放未用余量。</p>
     *
     * @param organizationPartyId 媒体组织
     * @return 完整预约区间之和（毫秒）
     */
    public long reservedMillis(String organizationPartyId) {
        InterviewModel.State state = stateOf(organizationPartyId);
        if (state == null) {
            return 0L;
        }
        long reserved = 0L;
        for (InterviewModel.Invite invite : state.invites()) {
            if (invite.status() != InterviewModel.Status.ACCEPTED
                    && invite.status() != InterviewModel.Status.ACTIVE) {
                continue;
            }
            long end = invite.scheduledEnd();
            if (end > invite.startMillis()) {
                reserved += end - invite.startMillis();
            }
        }
        return reserved;
    }

    /**
     * 某机构全部在途预留之和。
     *
     * @return 所有机构完整预约区间之和
     */
    public long totalReservedMillis() {
        long total = 0L;
        for (InterviewModel.State state : states) {
            total += reservedMillis(state.policy().mediaOrganizationPartyId());
        }
        return total;
    }

    /**
     * 该动作的冻结模拟耗时；门任务不按动作时长计费，返回 0。
     *
     * @param kind 任务种类
     * @return 模拟耗时毫秒
     * @throws IllegalArgumentException 未登记种类
     */
    public long actionMillis(String kind) {
        if (PendingTask.INTERVIEW_INVITE_V1.equals(kind)) {
            return config.inviteMillis();
        }
        if (PendingTask.INTERVIEW_RESPOND_V1.equals(kind)) {
            return config.respondMillis();
        }
        if (PendingTask.INTERVIEW_CANCEL_V1.equals(kind)) {
            return config.cancelMillis();
        }
        if (PendingTask.INTERVIEW_END_V1.equals(kind)) {
            return config.endMillis();
        }
        if (GATE_KINDS.contains(kind)) {
            return 0L;
        }
        throw new IllegalArgumentException("未登记的采访任务种类：" + kind);
    }

    /**
     * 指向某个人物的联系入口。
     *
     * @param targetPartyId 被联系人物
     * @return 该人物的全部入口，按渠道/资料排序
     */
    public List<ContactEntry> contactsFor(String targetPartyId) {
        List<ContactEntry> found = new ArrayList<>();
        for (ContactEntry entry : config.contacts()) {
            if (entry.targetPartyId().equals(targetPartyId)) {
                found.add(entry);
            }
        }
        return List.copyOf(found);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
