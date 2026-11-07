package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 双边采访协调纯核：只决定"谁在什么时间窗内约到了谁"，不产生正文、阅读、信念、联系人经历、
 * 现金或工时。
 *
 * <p>与运行层完全解耦：不读时钟、不写 journal、不排待办、不调用模型。时间都是模拟毫秒，
 * 时长都是实验参数。缺席时刻统一用 {@code -1} 表示，因此零时点是合法事实。</p>
 *
 * <p><b>请求标识带发起人命名空间。</b>请一律用 {@link #requestId(String, String)} 生成标识：
 * 它把发起人的长度与标识写在前面，因此不同人物的同名客户端标识不会碰撞，也无法互相冒充。
 * 运行层负责用人物自己的命令调用该 helper；模型不能自己拼前缀。</p>
 *
 * <p><b>授权与可达性只能来自受信上下文。</b>{@link Context} 由运行适配器构造；模型不能声明权限
 * 或可达性。邀请、接受、开始、继续发言四处都复核发起人的采访权限。历史不因当前撤职改写。</p>
 */
public final class InterviewModel {

    /** 成功。 */
    public static final String REASON_OK = "OK";
    /** 发起人在该媒体组织没有采访写稿授权。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 对象不在本人可达渠道内；未知人物与不可达人物同码。 */
    public static final String REASON_UNREACHABLE = "UNREACHABLE";
    /** 请求或会话不存在，或查询者不是参与者。 */
    public static final String REASON_UNKNOWN = "UNKNOWN";
    /** 请求标识不属于本发起人（命名空间不符）。 */
    public static final String REASON_ID_SCOPE = "ID_SCOPE";
    /** 不能邀请自己。 */
    public static final String REASON_SELF = "SELF";
    /** 窗口非法、已过去、放不下时长，或开始时刻不在窗口内。 */
    public static final String REASON_WINDOW = "WINDOW";
    /** 时长非正或超过策略上限。 */
    public static final String REASON_DURATION = "DURATION";
    /** 该时段任一方已有预约或外部忙碌。 */
    public static final String REASON_CONFLICT = "CONFLICT";
    /** 当前状态不允许该转换，或动作早于该对象已发生的事实。 */
    public static final String REASON_STATE = "STATE";
    /** 邀请已过期。 */
    public static final String REASON_EXPIRED = "EXPIRED";
    /** 相同标识已存在且参数不同（仅对原发起人）。 */
    public static final String REASON_ID_TAKEN = "ID_TAKEN";
    /** 时间相加溢出长整型。 */
    public static final String REASON_TIME_OVERFLOW = "TIME_OVERFLOW";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final String SESSION_PREFIX = "SESSION-";
    private static final long NONE = -1L;

    private InterviewModel() {
    }

    /** 邀请生命周期。 */
    public enum Status {
        /** 已提出，等待对象回应。 */
        PROPOSED,
        /** 对象已接受，等待约定时刻开始。 */
        ACCEPTED,
        /** 对象拒绝。 */
        DECLINED,
        /** 无人回应而过期。 */
        EXPIRED,
        /** 任一方在开始前取消。 */
        CANCELED,
        /** 已到约定时刻但未开始，预约作废。 */
        MISSED,
        /** 会话进行中。 */
        ACTIVE,
        /** 走完预约时长正常结束。 */
        COMPLETED,
        /** 一方提前结束；剩余时段立即释放。 */
        ENDED_EARLY
    }

    /** 会话结束方式。 */
    public enum CloseKind {
        /** 走完预约时长。 */
        COMPLETED,
        /** 一方提前结束。 */
        EARLY
    }

    /**
     * 生成带发起人命名空间的请求标识。
     *
     * <p>格式为 {@code 长度:发起人:客户端标识}，长度前缀避免发起人标识里的分隔符造成碰撞。
     * 客户端标识不得为空。</p>
     *
     * @param fromPartyId 发起人
     * @param clientId    客户端在本人物内的标识
     * @return 确定性的请求标识
     * @throws IllegalArgumentException 任一为空
     */
    public static String requestId(String fromPartyId, String clientId) {
        requireText(fromPartyId, "发起人");
        requireText(clientId, "客户端请求标识");
        return fromPartyId.length() + ":" + fromPartyId + ":" + clientId;
    }

    /**
     * 冻结的实验策略。
     *
     * @param mediaOrganizationPartyId 采访权限所属的媒体组织（必须是已登记组织）
     * @param inviteTtlMillis          邀请未被回应的有效期，正数
     * @param minLeadMillis            邀请开始时刻距创建时刻的最小提前量，非负
     * @param maxDurationMillis        单场时长上限，正数
     */
    public record Policy(String mediaOrganizationPartyId, long inviteTtlMillis,
                         long minLeadMillis, long maxDurationMillis) {

        /** 校验字段。 */
        public Policy {
            requireText(mediaOrganizationPartyId, "媒体组织");
            requirePositive(inviteTtlMillis, "邀请有效期");
            requireNonNegative(minLeadMillis, "最小提前量");
            requirePositive(maxDurationMillis, "时长上限");
        }
    }

    /**
     * 半开时间区间 {@code [from, to)}；紧邻不算冲突。
     *
     * @param fromMillis 起始（含）
     * @param toMillis   结束（不含）
     */
    public record Interval(long fromMillis, long toMillis) {

        /** 校验非负且非空。 */
        public Interval {
            requireNonNegative(fromMillis, "区间起点");
            if (toMillis <= fromMillis) {
                throw new IllegalArgumentException("区间必须为非空左闭右开：" + fromMillis + ".." + toMillis);
            }
        }

        /**
         * 是否与另一区间重叠（半开）。
         *
         * @param other 另一区间
         * @return 重叠返回 true
         */
        public boolean overlaps(Interval other) {
            return fromMillis < other.toMillis && other.fromMillis < toMillis;
        }
    }

    /**
     * 受信上下文：当前授权快照、本人可达对象、真实活动派生的忙碌区间。
     *
     * @param access    当前 {@link RoleAccess} 授权快照
     * @param reachable 人物 → 其可见渠道能联系到的对象集合
     * @param busy      人物 → 外部活动占用的区间
     */
    public record Context(RoleAccess.State access, Map<String, Set<String>> reachable,
                          Map<String, List<Interval>> busy) {

        /** 冻结映射，拒绝空授权。 */
        public Context {
            Objects.requireNonNull(access, "授权快照");
            Map<String, Set<String>> reachableCopy = new TreeMap<>();
            if (reachable != null) {
                for (Map.Entry<String, Set<String>> entry : reachable.entrySet()) {
                    requireText(entry.getKey(), "可达关系的人物");
                    reachableCopy.put(entry.getKey(),
                            java.util.Collections.unmodifiableSet(new TreeSet<>(
                                    entry.getValue() == null ? Set.of() : entry.getValue())));
                }
            }
            Map<String, List<Interval>> busyCopy = new TreeMap<>();
            if (busy != null) {
                for (Map.Entry<String, List<Interval>> entry : busy.entrySet()) {
                    requireText(entry.getKey(), "忙碌区间的人物");
                    busyCopy.put(entry.getKey(), List.copyOf(
                            entry.getValue() == null ? List.of() : entry.getValue()));
                }
            }
            reachable = java.util.Collections.unmodifiableMap(reachableCopy);
            busy = java.util.Collections.unmodifiableMap(busyCopy);
        }

        /** 某人能否联系到某对象。 */
        boolean canReach(String person, String target) {
            Set<String> targets = reachable.get(person);
            return targets != null && targets.contains(target);
        }

        /** 某人由外部活动占用的区间。 */
        List<Interval> busyOf(String person) {
            return busy.getOrDefault(person, List.of());
        }
    }

    /**
     * 新邀请请求。
     *
     * @param requestId         由 {@link #requestId(String, String)} 生成的标识
     * @param toPartyId         对象标识
     * @param topic             主题，仅双方可见
     * @param windowStartMillis 窗口起点（含）
     * @param windowEndMillis   窗口终点（不含），必须能容纳时长
     * @param durationMillis    预约时长
     */
    public record InviteRequest(String requestId, String toPartyId, String topic,
                                long windowStartMillis, long windowEndMillis,
                                long durationMillis) {

        /** 校验字段。 */
        public InviteRequest {
            requireText(requestId, "请求标识");
            requireText(toPartyId, "对象");
            requireText(topic, "主题");
            requireNonNegative(windowStartMillis, "窗口起点");
            requireNonNegative(windowEndMillis, "窗口终点");
            requirePositive(durationMillis, "预约时长");
        }
    }

    /**
     * 对象的回应。
     *
     * @param accept      是否接受；拒绝时为 false
     * @param startMillis 接受时选定的开始时刻；拒绝时为 {@code -1}
     */
    public record Decision(boolean accept, long startMillis) {

        /** 校验字段；开始时刻用 {@code -1} 表示缺席。 */
        public Decision {
            if (accept && startMillis < 0) {
                throw new IllegalArgumentException("接受必须给出非负的开始时刻");
            }
            if (!accept && startMillis != NONE) {
                throw new IllegalArgumentException("拒绝不携带开始时刻");
            }
        }

        /**
         * 接受。
         *
         * @param startMillis 开始时刻
         * @return 接受决定
         */
        public static Decision accept(long startMillis) {
            return new Decision(true, startMillis);
        }

        /**
         * 拒绝。
         *
         * @return 拒绝决定
         */
        public static Decision decline() {
            return new Decision(false, NONE);
        }
    }

    /**
     * 一条邀请。时间缺席统一记 {@code -1}。
     *
     * @param requestId           带发起人命名空间的标识
     * @param fromPartyId         发起人
     * @param toPartyId           对象
     * @param organizationPartyId 采访权限所属媒体组织
     * @param topic               主题
     * @param windowStartMillis   窗口起点
     * @param windowEndMillis     窗口终点
     * @param durationMillis      预约时长
     * @param status              生命周期状态
     * @param createdAtMillis     创建时刻
     * @param expiresAtMillis     未回应的过期时刻
     * @param decidedAtMillis     对象回应时刻；未回应为 {@code -1}
     * @param startMillis         约定开始时刻；未接受为 {@code -1}
     * @param transitionAtMillis  终态转移时刻；未进入终态为 {@code -1}
     * @param reason              最近一次业务原因码；成功为 {@code OK}
     */
    public record Invite(String requestId, String fromPartyId, String toPartyId,
                         String organizationPartyId, String topic, long windowStartMillis,
                         long windowEndMillis, long durationMillis, Status status,
                         long createdAtMillis, long expiresAtMillis, long decidedAtMillis,
                         long startMillis, long transitionAtMillis, String reason) {

        /** 校验字段、命名空间、状态一致性与时间单调。 */
        public Invite {
            requireText(requestId, "请求标识");
            requireText(fromPartyId, "发起人");
            requireText(toPartyId, "对象");
            requireText(organizationPartyId, "媒体组织");
            requireText(topic, "主题");
            Objects.requireNonNull(status, "邀请状态");
            if (fromPartyId.equals(toPartyId)) {
                throw new IllegalArgumentException("不能邀请自己：" + requestId);
            }
            if (!inScope(requestId, fromPartyId)) {
                throw new IllegalArgumentException("请求标识不属于该发起人：" + requestId);
            }
            if (windowStartMillis < 0 || windowEndMillis <= windowStartMillis) {
                throw new IllegalArgumentException("邀请窗口必须为非空区间：" + requestId);
            }
            if (durationMillis <= 0 || durationMillis > windowEndMillis - windowStartMillis) {
                throw new IllegalArgumentException("时长必须为正且放得进窗口：" + requestId);
            }
            requireNonNegative(createdAtMillis, "创建时刻");
            requireNonNegative(expiresAtMillis, "过期时刻");
            if (expiresAtMillis <= createdAtMillis || expiresAtMillis > windowEndMillis) {
                throw new IllegalArgumentException("过期时刻必须晚于创建且不晚于窗口终点：" + requestId);
            }
            if (reason == null) {
                reason = REASON_OK;
            }
            if (decidedAtMillis != NONE && decidedAtMillis < createdAtMillis) {
                throw new IllegalArgumentException("回应时刻不能早于创建：" + requestId);
            }
            if (startMillis != NONE) {
                if (startMillis < windowStartMillis
                        || startMillis > windowEndMillis - durationMillis) {
                    throw new IllegalArgumentException("约定时刻必须落在窗口内：" + requestId);
                }
            }
            switch (status) {
                case PROPOSED -> {
                    if (decidedAtMillis != NONE || startMillis != NONE
                            || transitionAtMillis != NONE) {
                        throw new IllegalArgumentException("待回应邀请不得有回应、约定或转移时刻："
                                + requestId);
                    }
                }
                case ACCEPTED, ACTIVE -> {
                    if (decidedAtMillis == NONE || startMillis == NONE
                            || transitionAtMillis != NONE) {
                        throw new IllegalArgumentException("已接受邀请必须有回应与约定时刻且未终结："
                                + requestId);
                    }
                    if (startMillis < decidedAtMillis) {
                        throw new IllegalArgumentException("约定时刻不能早于接受：" + requestId);
                    }
                }
                case COMPLETED, ENDED_EARLY -> {
                    if (decidedAtMillis == NONE || startMillis == NONE
                            || transitionAtMillis == NONE) {
                        throw new IllegalArgumentException("已结束邀请必须有回应、约定与转移时刻："
                                + requestId);
                    }
                    if (startMillis < decidedAtMillis || transitionAtMillis < startMillis) {
                        throw new IllegalArgumentException("时间顺序错误：" + requestId);
                    }
                }
                case MISSED -> {
                    if (decidedAtMillis == NONE || startMillis == NONE
                            || transitionAtMillis == NONE) {
                        throw new IllegalArgumentException("错过的预约必须有回应、约定与转移时刻："
                                + requestId);
                    }
                    if (transitionAtMillis <= startMillis) {
                        throw new IllegalArgumentException("错过必须发生在约定时刻之后：" + requestId);
                    }
                }
                case EXPIRED -> {
                    if (startMillis != NONE || decidedAtMillis != NONE
                            || transitionAtMillis == NONE) {
                        throw new IllegalArgumentException("过期邀请不得有回应或约定时刻：" + requestId);
                    }
                    if (transitionAtMillis < expiresAtMillis) {
                        throw new IllegalArgumentException("过期转移不能早于过期时刻：" + requestId);
                    }
                }
                case DECLINED -> {
                    if (decidedAtMillis == NONE || startMillis != NONE
                            || transitionAtMillis == NONE) {
                        throw new IllegalArgumentException("拒绝必须有回应与转移时刻：" + requestId);
                    }
                    if (transitionAtMillis != decidedAtMillis) {
                        throw new IllegalArgumentException("拒绝的转移时刻必须等于回应时刻：" + requestId);
                    }
                }
                case CANCELED -> {
                    if (transitionAtMillis == NONE) {
                        throw new IllegalArgumentException("取消必须有转移时刻：" + requestId);
                    }
                    if (decidedAtMillis != NONE && startMillis != NONE
                            && startMillis < decidedAtMillis) {
                        throw new IllegalArgumentException("约定时刻不能早于接受：" + requestId);
                    }
                    if (decidedAtMillis == NONE && startMillis != NONE) {
                        throw new IllegalArgumentException("未接受的邀请不得保留约定时刻：" + requestId);
                    }
                }
                default -> throw new IllegalArgumentException("未知状态：" + status);
            }
        }

        /** 预约的计划结束时刻。 */
        long scheduledEnd() {
            try {
                return Math.addExact(startMillis, durationMillis);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(REASON_TIME_OVERFLOW, overflow);
            }
        }

        /**
         * 生成同一请求的新状态（不可变）。
         *
         * @param next       新状态
         * @param decided    回应时刻
         * @param start      约定开始时刻
         * @param transition 终态转移时刻
         * @param reason     原因码
         * @return 新的邀请
         */
        Invite withStatus(Status next, long decided, long start, long transition, String reason) {
            return new Invite(requestId, fromPartyId, toPartyId, organizationPartyId, topic,
                    windowStartMillis, windowEndMillis, durationMillis, next, createdAtMillis,
                    expiresAtMillis, decided, start, transition, reason);
        }

        /**
         * 生成只改状态的邀请，保留回应与约定时刻。
         *
         * @param next       新状态
         * @param transition 转移时刻
         * @return 新的邀请
         */
        Invite withStatusKeep(Status next, long transition) {
            return withStatus(next, decidedAtMillis, startMillis, transition, REASON_OK);
        }
    }

    /**
     * 一次采访会话。
     *
     * @param sessionId       会话标识，恒为 {@code SESSION-<requestId>}
     * @param requestId       对应请求
     * @param startedAtMillis 开始时刻
     * @param endedAtMillis   实际结束时刻；进行中为 {@code -1}
     * @param close           结束方式；进行中为 null
     */
    public record Session(String sessionId, String requestId, long startedAtMillis,
                          long endedAtMillis, CloseKind close) {

        /** 校验字段。 */
        public Session {
            requireText(sessionId, "会话标识");
            requireText(requestId, "会话对应的请求");
            requireNonNegative(startedAtMillis, "会话开始时刻");
            if (!sessionId.equals(sessionIdOf(requestId))) {
                throw new IllegalArgumentException("会话标识必须由请求唯一确定：" + sessionId);
            }
            if (endedAtMillis == NONE) {
                if (close != null) {
                    throw new IllegalArgumentException("进行中的会话不能带结束方式：" + sessionId);
                }
            } else if (endedAtMillis < startedAtMillis || close == null) {
                throw new IllegalArgumentException("已结束会话必须有结束方式且不早于开始：" + sessionId);
            }
        }

        /**
         * 是否仍在进行。
         * @return 尚未结束返回 true
         */
        public boolean open() {
            return endedAtMillis == NONE;
        }
    }

    /**
     * 协调状态。
     *
     * @param invites  邀请，按标识排序
     * @param sessions 会话，按标识排序
     * @param persons  开局冻结的合法人物集合，按标识排序
     * @param policy   冻结策略
     */
    public record State(List<Invite> invites, List<Session> sessions, List<String> persons,
                        Policy policy) {

        /** 冻结、排序并校验全部一致性；不一致的构造与恢复一律拒绝。 */
        public State {
            Objects.requireNonNull(policy, "冻结策略");
            List<Invite> inviteList = new ArrayList<>(invites == null ? List.of() : invites);
            inviteList.sort(Comparator.comparing(Invite::requestId));
            List<Session> sessionList = new ArrayList<>(sessions == null ? List.of() : sessions);
            sessionList.sort(Comparator.comparing(Session::sessionId));
            List<String> personList = new ArrayList<>(persons == null ? List.of() : persons);
            personList.sort(Comparator.naturalOrder());

            Set<String> personSet = new LinkedHashSet<>(personList);
            if (personSet.size() != personList.size()) {
                throw new IllegalArgumentException("人物集合重复");
            }
            Map<String, Invite> byRequest = new LinkedHashMap<>();
            for (Invite invite : inviteList) {
                if (byRequest.putIfAbsent(invite.requestId(), invite) != null) {
                    throw new IllegalArgumentException("重复请求标识：" + invite.requestId());
                }
                if (!personSet.contains(invite.fromPartyId())
                        || !personSet.contains(invite.toPartyId())) {
                    throw new IllegalArgumentException("邀请引用了未登记人物：" + invite.requestId());
                }
                if (!policy.mediaOrganizationPartyId().equals(invite.organizationPartyId())) {
                    throw new IllegalArgumentException("邀请不属于冻结媒体组织：" + invite.requestId());
                }
                if (invite.durationMillis() > policy.maxDurationMillis()) {
                    throw new IllegalArgumentException("时长超过冻结上限：" + invite.requestId());
                }
                if (invite.windowStartMillis() - invite.createdAtMillis() < policy.minLeadMillis()) {
                    throw new IllegalArgumentException("窗口早于策略要求的最小提前量："
                            + invite.requestId());
                }
            }
            Map<String, Session> sessionByRequest = new LinkedHashMap<>();
            Set<String> sessionIds = new LinkedHashSet<>();
            for (Session session : sessionList) {
                if (!sessionIds.add(session.sessionId())) {
                    throw new IllegalArgumentException("重复会话标识：" + session.sessionId());
                }
                Invite invite = byRequest.get(session.requestId());
                if (invite == null) {
                    throw new IllegalArgumentException("会话引用了未知请求：" + session.sessionId());
                }
                if (sessionByRequest.putIfAbsent(session.requestId(), session) != null) {
                    throw new IllegalArgumentException("同一请求对应多个会话：" + session.requestId());
                }
                if (session.startedAtMillis() != invite.startMillis()) {
                    throw new IllegalArgumentException("会话开始与邀请记录不一致：" + session.sessionId());
                }
                if (!session.open()) {
                    long scheduledEnd = invite.scheduledEnd();
                    if (session.close() == CloseKind.COMPLETED
                            && session.endedAtMillis() != scheduledEnd) {
                        throw new IllegalArgumentException("正常结束必须正好落在预约终点："
                                + session.sessionId());
                    }
                    if (session.close() == CloseKind.EARLY
                            && session.endedAtMillis() > scheduledEnd) {
                        throw new IllegalArgumentException("提前结束不能晚于预约终点："
                                + session.sessionId());
                    }
                    if (invite.transitionAtMillis() != session.endedAtMillis()) {
                        throw new IllegalArgumentException("邀请的转移时刻必须等于会话结束："
                                + invite.requestId());
                    }
                }
            }
            for (Invite invite : inviteList) {
                Session session = sessionByRequest.get(invite.requestId());
                boolean needsSession = invite.status() == Status.ACTIVE
                        || invite.status() == Status.COMPLETED
                        || invite.status() == Status.ENDED_EARLY;
                if (needsSession != (session != null)) {
                    throw new IllegalArgumentException("邀请与会话关系不一致：" + invite.requestId());
                }
                if (session != null && session.open() != (invite.status() == Status.ACTIVE)) {
                    throw new IllegalArgumentException("进行中标记与会话不一致：" + invite.requestId());
                }
                if (invite.status() == Status.COMPLETED
                        && session.close() != CloseKind.COMPLETED) {
                    throw new IllegalArgumentException("正常结束必须记 COMPLETED：" + invite.requestId());
                }
                if (invite.status() == Status.ENDED_EARLY && session.close() != CloseKind.EARLY) {
                    throw new IllegalArgumentException("提前结束必须记 EARLY：" + invite.requestId());
                }
            }
            Map<String, List<Interval>> occupied = new TreeMap<>();
            for (Invite invite : inviteList) {
                Interval interval = occupiedInterval(invite, sessionByRequest.get(invite.requestId()));
                if (interval == null) {
                    continue;
                }
                for (String person : List.of(invite.fromPartyId(), invite.toPartyId())) {
                    for (Interval other : occupied.getOrDefault(person, List.of())) {
                        if (other.overlaps(interval)) {
                            throw new IllegalArgumentException("同一人物的预约重叠：" + person);
                        }
                    }
                    occupied.computeIfAbsent(person, key -> new ArrayList<>()).add(interval);
                }
            }
            invites = List.copyOf(inviteList);
            sessions = List.copyOf(sessionList);
            persons = List.copyOf(personList);
        }

        /**
         * 人物是否在开局冻结集合内。
         * @param personPartyId 人物标识
         * @return 属于开局冻结集合返回 true
         */
        public boolean hasPerson(String personPartyId) {
            return persons.contains(personPartyId);
        }
    }

    /**
     * 操作结果；被拒绝时返回的状态与入参同一实例。
     *
     * @param accepted 是否成功
     * @param reason   原因码
     * @param state    结果状态
     */
    public record Outcome(boolean accepted, String reason, State state) {
    }

    /**
     * 发言许可结果。
     *
     * @param allowed 是否允许继续发言
     * @param reason  原因码
     */
    public record Speak(boolean allowed, String reason) {
    }

    /**
     * 本人可见的邀请投影。
     *
     * @param requestId           完整确定标识
     * @param fromPartyId         发起人
     * @param toPartyId           对象
     * @param topic               主题
     * @param status              状态
     * @param windowStartMillis   窗口起点
     * @param windowEndMillis     窗口终点
     * @param durationMillis      时长
     * @param startMillis         约定开始；未接受为 {@code -1}
     * @param expiresAtMillis     过期时刻
     * @param transitionAtMillis  终态转移时刻；未终结为 {@code -1}
     */
    public record InviteView(String requestId, String fromPartyId, String toPartyId, String topic,
                             Status status, long windowStartMillis, long windowEndMillis,
                             long durationMillis, long startMillis, long expiresAtMillis,
                             long transitionAtMillis) {
    }

    /**
     * 本人可见的会话投影。
     *
     * @param sessionId       完整确定标识
     * @param requestId       对应请求
     * @param startedAtMillis 开始时刻
     * @param endedAtMillis   结束时刻；进行中为 {@code -1}
     * @param close           结束方式；进行中为 null
     */
    public record SessionView(String sessionId, String requestId, long startedAtMillis,
                              long endedAtMillis, CloseKind close) {
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 开局：冻结策略与合法人物集合，并核对媒体组织确实是已登记组织。
     *
     * @param policy   实验策略
     * @param registry 开局登记
     * @return 空状态
     * @throws IllegalArgumentException 登记为空、媒体组织未登记或不是组织
     */
    public static State open(Policy policy, OpeningRegistry registry) {
        Objects.requireNonNull(policy, "冻结策略");
        if (registry == null) {
            throw new IllegalArgumentException("开局登记不能为空");
        }
        boolean organizationFound = false;
        List<String> persons = new ArrayList<>();
        for (OpeningRegistry.Party party : registry.parties()) {
            if (party.kind() == OpeningRegistry.PartyKind.ORGANIZATION
                    && party.partyId().equals(policy.mediaOrganizationPartyId())) {
                organizationFound = true;
            }
            if (party.kind() == OpeningRegistry.PartyKind.PERSON) {
                persons.add(party.partyId());
            }
        }
        if (!organizationFound) {
            throw new IllegalArgumentException("媒体组织未登记或不是组织："
                    + policy.mediaOrganizationPartyId());
        }
        return new State(List.of(), List.of(), persons, policy);
    }

    /**
     * 提出邀请。标识必须属于本发起人；对象须在本人可达范围内。
     *
     * @param state   当前状态
     * @param context 受信上下文
     * @param from    发起人
     * @param request 邀请参数
     * @param now     当前模拟时刻
     * @return 结果
     */
    public static Outcome invite(State state, Context context, String from,
                                 InviteRequest request, long now) {
        requireState(state);
        requireContext(context);
        requireText(from, "发起人");
        Objects.requireNonNull(request, "邀请参数");
        requireNonNegative(now, "当前时刻");
        Policy policy = state.policy();

        // 命名空间先于任何"是否已存在"的判断，避免用重复/不存在区分出他人标识
        if (!inScope(request.requestId(), from)) {
            return new Outcome(false, REASON_ID_SCOPE, state);
        }
        Invite existing = find(state, request.requestId());
        if (existing != null) {
            if (sameRequest(existing, from, request, policy)) {
                return new Outcome(true, REASON_OK, state);
            }
            return new Outcome(false, REASON_ID_TAKEN, state);
        }
        if (from.equals(request.toPartyId())) {
            return new Outcome(false, REASON_SELF, state);
        }
        if (!state.hasPerson(from) || !mayInterview(context, policy, from, now)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state);
        }
        if (!context.canReach(from, request.toPartyId())
                || !state.hasPerson(request.toPartyId())) {
            return new Outcome(false, REASON_UNREACHABLE, state);
        }
        if (request.durationMillis() > policy.maxDurationMillis()) {
            return new Outcome(false, REASON_DURATION, state);
        }
        long earliestStartable;
        long latestStartable;
        long expiry;
        try {
            earliestStartable = Math.addExact(now, policy.minLeadMillis());
            latestStartable = Math.subtractExact(request.windowEndMillis(),
                    request.durationMillis());
            expiry = Math.min(Math.addExact(now, policy.inviteTtlMillis()),
                    request.windowEndMillis());
        } catch (ArithmeticException overflow) {
            return new Outcome(false, REASON_TIME_OVERFLOW, state);
        }
        if (request.windowStartMillis() > latestStartable
                || request.windowStartMillis() < earliestStartable
                || request.windowEndMillis() <= now
                || request.windowStartMillis() < now) {
            return new Outcome(false, REASON_WINDOW, state);
        }
        if (expiry <= now) {
            return new Outcome(false, REASON_WINDOW, state);
        }
        Invite invite = new Invite(request.requestId(), from, request.toPartyId(),
                policy.mediaOrganizationPartyId(), request.topic(), request.windowStartMillis(),
                request.windowEndMillis(), request.durationMillis(), Status.PROPOSED, now, expiry,
                NONE, NONE, NONE, REASON_OK);
        List<Invite> invites = new ArrayList<>(state.invites());
        invites.add(invite);
        return new Outcome(true, REASON_OK,
                new State(invites, state.sessions(), state.persons(), policy));
    }

    /**
     * 对象回应邀请。接受时复核发起人授权、时间窗口与双方占用。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param person    回应者
     * @param requestId 完整请求标识
     * @param decision  接受或拒绝
     * @param now       当前模拟时刻
     * @return 结果；重复相同回应不改变状态
     */
    public static Outcome respond(State state, Context context, String person, String requestId,
                                  Decision decision, long now) {
        requireState(state);
        requireContext(context);
        requireText(person, "回应者");
        requireText(requestId, "请求标识");
        Objects.requireNonNull(decision, "回应");
        requireNonNegative(now, "当前时刻");

        Invite invite = find(state, requestId);
        if (invite == null || !invite.toPartyId().equals(person)) {
            return new Outcome(false, REASON_UNKNOWN, state);
        }
        if (invite.status() == Status.ACCEPTED && decision.accept()
                && invite.startMillis() == decision.startMillis()) {
            return new Outcome(true, REASON_OK, state);
        }
        if (invite.status() == Status.DECLINED && !decision.accept()) {
            return new Outcome(true, REASON_OK, state);
        }
        if (invite.status() != Status.PROPOSED) {
            return new Outcome(false, REASON_STATE, state);
        }
        if (now < invite.createdAtMillis()) {
            return new Outcome(false, REASON_STATE, state);
        }
        if (now >= invite.expiresAtMillis()) {
            return new Outcome(false, REASON_EXPIRED, state);
        }
        if (!decision.accept()) {
            return new Outcome(true, REASON_OK, replace(state, invite.withStatus(Status.DECLINED,
                    now, NONE, now, REASON_OK)));
        }
        if (!mayInterview(context, state.policy(), invite.fromPartyId(), now)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state);
        }
        long start = decision.startMillis();
        long end;
        try {
            end = Math.addExact(start, invite.durationMillis());
        } catch (ArithmeticException overflow) {
            return new Outcome(false, REASON_TIME_OVERFLOW, state);
        }
        if (start < now || start < invite.windowStartMillis() || end > invite.windowEndMillis()) {
            return new Outcome(false, REASON_WINDOW, state);
        }
        Interval slot = new Interval(start, end);
        if (isBusy(state, context, invite.fromPartyId(), slot, invite.requestId())
                || isBusy(state, context, invite.toPartyId(), slot, invite.requestId())) {
            return new Outcome(false, REASON_CONFLICT, state);
        }
        return new Outcome(true, REASON_OK, replace(state, invite.withStatus(Status.ACCEPTED, now,
                start, NONE, REASON_OK)));
    }

    /**
     * 取消邀请。不得早于该邀请已记录的回应或创建时刻；已开始只能结束。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param person    操作者
     * @param requestId 完整请求标识
     * @param now       当前模拟时刻
     * @return 结果
     */
    public static Outcome cancel(State state, Context context, String person, String requestId,
                                 long now) {
        requireState(state);
        requireContext(context);
        requireText(person, "操作者");
        requireText(requestId, "请求标识");
        requireNonNegative(now, "当前时刻");
        Invite invite = find(state, requestId);
        if (invite == null || !isParticipant(invite, person)) {
            return new Outcome(false, REASON_UNKNOWN, state);
        }
        if (invite.status() == Status.CANCELED) {
            return new Outcome(true, REASON_OK, state);
        }
        if (invite.status() != Status.PROPOSED && invite.status() != Status.ACCEPTED) {
            return new Outcome(false, REASON_STATE, state);
        }
        long earliest = invite.decidedAtMillis() == NONE ? invite.createdAtMillis()
                : invite.decidedAtMillis();
        if (now < earliest) {
            return new Outcome(false, REASON_STATE, state);
        }
        if (invite.status() == Status.ACCEPTED && now >= invite.startMillis()) {
            return new Outcome(false, REASON_STATE, state);
        }
        return new Outcome(true, REASON_OK, replace(state, invite.withStatus(Status.CANCELED,
                invite.decidedAtMillis(), invite.startMillis(), now, REASON_OK)));
    }

    /**
     * 推进到期：未回应过期、错过开始转 {@code MISSED}、到点会话严格在预约终点结束。
     *
     * @param state 当前状态
     * @param now   当前模拟时刻
     * @return 结果；没有变化时返回原状态
     */
    public static Outcome expire(State state, long now) {
        requireState(state);
        requireNonNegative(now, "当前时刻");
        List<Invite> invites = new ArrayList<>(state.invites());
        List<Session> sessions = new ArrayList<>(state.sessions());
        boolean changed = false;
        for (int index = 0; index < invites.size(); index++) {
            Invite invite = invites.get(index);
            if (now < invite.createdAtMillis()) {
                continue;
            }
            if (invite.status() == Status.PROPOSED && now >= invite.expiresAtMillis()) {
                invites.set(index, invite.withStatus(Status.EXPIRED, NONE, NONE, now,
                        REASON_EXPIRED));
                changed = true;
                continue;
            }
            if (invite.status() == Status.ACCEPTED && now > invite.startMillis()) {
                invites.set(index, invite.withStatus(Status.MISSED, invite.decidedAtMillis(),
                        invite.startMillis(), now, REASON_EXPIRED));
                changed = true;
                continue;
            }
            if (invite.status() != Status.ACTIVE) {
                continue;
            }
            long scheduledEnd = invite.scheduledEnd();
            if (now < scheduledEnd) {
                continue;
            }
            for (int sessionIndex = 0; sessionIndex < sessions.size(); sessionIndex++) {
                Session session = sessions.get(sessionIndex);
                if (session.requestId().equals(invite.requestId()) && session.open()) {
                    sessions.set(sessionIndex, new Session(session.sessionId(),
                            session.requestId(), session.startedAtMillis(), scheduledEnd,
                            CloseKind.COMPLETED));
                }
            }
            invites.set(index, invite.withStatus(Status.COMPLETED, invite.decidedAtMillis(),
                    invite.startMillis(), scheduledEnd, REASON_OK));
            changed = true;
        }
        if (!changed) {
            return new Outcome(true, REASON_OK, state);
        }
        return new Outcome(true, REASON_OK,
                new State(invites, sessions, state.persons(), state.policy()));
    }

    /**
     * 到点开始会话。仅约定时刻当帧可开始；复核发起人授权与双方占用。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param person    发起人或对象
     * @param requestId 完整请求标识
     * @param now       当前模拟时刻
     * @return 结果；任何复核失败都返回原状态
     */
    public static Outcome start(State state, Context context, String person, String requestId,
                                long now) {
        requireState(state);
        requireContext(context);
        requireText(person, "操作者");
        requireText(requestId, "请求标识");
        requireNonNegative(now, "当前时刻");
        Invite invite = find(state, requestId);
        if (invite == null || !isParticipant(invite, person)) {
            return new Outcome(false, REASON_UNKNOWN, state);
        }
        if (invite.status() == Status.ACTIVE) {
            return new Outcome(true, REASON_OK, state);
        }
        if (invite.status() != Status.ACCEPTED) {
            return new Outcome(false, REASON_STATE, state);
        }
        if (now != invite.startMillis()) {
            return new Outcome(false, REASON_STATE, state);
        }
        if (!state.hasPerson(invite.fromPartyId()) || !state.hasPerson(invite.toPartyId())
                || !mayInterview(context, state.policy(), invite.fromPartyId(), now)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state);
        }
        long end;
        try {
            end = Math.addExact(invite.startMillis(), invite.durationMillis());
        } catch (ArithmeticException overflow) {
            return new Outcome(false, REASON_TIME_OVERFLOW, state);
        }
        Interval slot = new Interval(invite.startMillis(), end);
        if (isBusy(state, context, invite.fromPartyId(), slot, invite.requestId())
                || isBusy(state, context, invite.toPartyId(), slot, invite.requestId())) {
            return new Outcome(false, REASON_CONFLICT, state);
        }
        List<Session> sessions = new ArrayList<>(state.sessions());
        sessions.add(new Session(sessionIdOf(invite.requestId()), invite.requestId(), now, NONE,
                null));
        return new Outcome(true, REASON_OK, new State(
                replaceList(state, invite.withStatusKeep(Status.ACTIVE, NONE)), sessions,
                state.persons(), state.policy()));
    }

    /**
     * 结束会话：提前结束只占实际区间；迟到结束严格截在预约终点。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param person    操作者
     * @param sessionId 完整会话标识
     * @param now       当前模拟时刻
     * @return 结果
     */
    public static Outcome end(State state, Context context, String person, String sessionId,
                              long now) {
        requireState(state);
        requireContext(context);
        requireText(person, "操作者");
        requireText(sessionId, "会话标识");
        requireNonNegative(now, "当前时刻");
        Session session = findSession(state, sessionId);
        if (session == null) {
            return new Outcome(false, REASON_UNKNOWN, state);
        }
        Invite invite = find(state, session.requestId());
        if (invite == null || !isParticipant(invite, person)) {
            return new Outcome(false, REASON_UNKNOWN, state);
        }
        if (!session.open()) {
            return new Outcome(true, REASON_OK, state);
        }
        if (now < session.startedAtMillis()) {
            return new Outcome(false, REASON_STATE, state);
        }
        long scheduledEnd = invite.scheduledEnd();
        long actualEnd = Math.min(now, scheduledEnd);
        CloseKind close = actualEnd >= scheduledEnd ? CloseKind.COMPLETED : CloseKind.EARLY;
        Status status = close == CloseKind.EARLY ? Status.ENDED_EARLY : Status.COMPLETED;
        List<Session> sessions = new ArrayList<>(state.sessions());
        for (int index = 0; index < sessions.size(); index++) {
            if (sessions.get(index).sessionId().equals(sessionId)) {
                sessions.set(index, new Session(session.sessionId(), session.requestId(),
                        session.startedAtMillis(), actualEnd, close));
            }
        }
        return new Outcome(true, REASON_OK, new State(
                replaceList(state, invite.withStatus(status, invite.decidedAtMillis(),
                        invite.startMillis(), actualEnd, REASON_OK)), sessions, state.persons(),
                state.policy()));
    }

    /**
     * 进行中会话能否继续发言。
     *
     * @param state     当前状态
     * @param context   受信上下文
     * @param person    发言者
     * @param sessionId 完整会话标识
     * @param now       当前模拟时刻
     * @return 许可结果
     */
    public static Speak canSpeak(State state, Context context, String person, String sessionId,
                                 long now) {
        requireState(state);
        requireContext(context);
        requireText(person, "发言者");
        requireText(sessionId, "会话标识");
        requireNonNegative(now, "当前时刻");
        Session session = findSession(state, sessionId);
        Invite invite = session == null ? null : find(state, session.requestId());
        if (session == null || invite == null || !isParticipant(invite, person)) {
            return new Speak(false, REASON_UNKNOWN);
        }
        if (!session.open()) {
            return new Speak(false, REASON_STATE);
        }
        if (now < session.startedAtMillis()) {
            return new Speak(false, REASON_STATE);
        }
        long scheduledEnd;
        try {
            scheduledEnd = Math.addExact(invite.startMillis(), invite.durationMillis());
        } catch (ArithmeticException overflow) {
            return new Speak(false, REASON_TIME_OVERFLOW);
        }
        if (now >= scheduledEnd) {
            return new Speak(false, REASON_STATE);
        }
        if (!mayInterview(context, state.policy(), invite.fromPartyId(), now)) {
            return new Speak(false, REASON_NOT_AUTHORIZED);
        }
        return new Speak(true, REASON_OK);
    }

    /**
     * 本人可见的邀请列表；非参与者得不到任何条目。
     *
     * @param state   当前状态
     * @param context 受信上下文
     * @param person  查询者
     * @param now     当前模拟时刻
     * @return 只含本人参与、且创建时刻不晚于当前时刻的条目
     */
    public static List<InviteView> inbox(State state, Context context, String person, long now) {
        requireState(state);
        requireContext(context);
        requireText(person, "查询者");
        requireNonNegative(now, "当前时刻");
        List<InviteView> views = new ArrayList<>();
        for (Invite invite : state.invites()) {
            if (!isParticipant(invite, person) || invite.createdAtMillis() > now) {
                continue;
            }
            views.add(new InviteView(invite.requestId(), invite.fromPartyId(), invite.toPartyId(),
                    invite.topic(), invite.status(), invite.windowStartMillis(),
                    invite.windowEndMillis(), invite.durationMillis(), invite.startMillis(),
                    invite.expiresAtMillis(), invite.transitionAtMillis()));
        }
        return List.copyOf(views);
    }

    /**
     * 本人可见的会话列表。
     *
     * @param state   当前状态
     * @param context 受信上下文
     * @param person  查询者
     * @param now     当前模拟时刻
     * @return 只含本人参与的会话
     */
    public static List<SessionView> sessions(State state, Context context, String person, long now) {
        requireState(state);
        requireContext(context);
        requireText(person, "查询者");
        requireNonNegative(now, "当前时刻");
        List<SessionView> views = new ArrayList<>();
        for (Session session : state.sessions()) {
            Invite invite = find(state, session.requestId());
            if (invite == null || !isParticipant(invite, person)
                    || session.startedAtMillis() > now) {
                continue;
            }
            views.add(new SessionView(session.sessionId(), session.requestId(),
                    session.startedAtMillis(), session.endedAtMillis(), session.close()));
        }
        return List.copyOf(views);
    }

    /**
     * 只读实际占用投影：该人物所有真实预约/会话已经占住的时间区间。
     *
     * <p>供运行层跨机构拼接 {@code Context.busy} 使用，避免第二份占用规则实现漂移。规则与本类
     * 内部校验完全一致：{@code ACCEPTED/ACTIVE/COMPLETED} 占 {@code [start, start+duration)}，
     * {@code ENDED_EARLY} 只占 {@code [start, ended)}（零长度不占），
     * {@code PROPOSED/DECLINED/EXPIRED/CANCELED/MISSED} 不占。返回区间按起点、终点升序。</p>
     *
     * <p>本方法不修改状态、不产生账本、不判断授权；未知人物返回空表。</p>
     *
     * @param state         当前协调状态
     * @param personPartyId 人物标识
     * @return 该人物实际占用的半开区间，升序且只读
     * @throws IllegalArgumentException 状态为空或人物标识为空
     */
    public static List<Interval> occupancy(State state, String personPartyId) {
        requireState(state);
        requireText(personPartyId, "人物标识");
        List<Interval> intervals = new ArrayList<>();
        for (Invite invite : state.invites()) {
            if (!isParticipant(invite, personPartyId)) {
                continue;
            }
            Interval occupied = occupiedInterval(invite,
                    findSession(state, sessionIdOf(invite.requestId())));
            if (occupied != null) {
                intervals.add(occupied);
            }
        }
        intervals.sort(Comparator.comparingLong(Interval::fromMillis)
                .thenComparingLong(Interval::toMillis));
        return List.copyOf(intervals);
    }

    /**
     * 管理员审计视图：全部邀请与会话。
     *
     * @param state 当前状态
     * @return 邀请与会话的不可变副本
     */
    public static List<Object> audit(State state) {
        requireState(state);
        List<Object> views = new ArrayList<>();
        views.addAll(state.invites());
        views.addAll(state.sessions());
        return List.copyOf(views);
    }

    /**
     * 序列化为 JSON。
     *
     * @param state 当前状态
     * @return JSON 文本
     */
    public static String toJson(State state) {
        requireState(state);
        try {
            return MAPPER.writeValueAsString(state);
        } catch (Exception failure) {
            throw new IllegalStateException("协调状态序列化失败", failure);
        }
    }

    /**
     * 从 JSON 恢复；结构或时间不一致一律拒绝。
     *
     * @param json JSON 文本
     * @return 恢复后的状态
     * @throws IllegalArgumentException 文本非法或结构不一致
     */
    public static State fromJson(String json) {
        requireText(json, "协调状态 JSON");
        try {
            State state = MAPPER.readValue(json, State.class);
            if (state == null) {
                throw new IllegalArgumentException("协调状态 JSON 为空");
            }
            return state;
        } catch (IllegalArgumentException semantic) {
            throw semantic;
        } catch (Exception failure) {
            throw new IllegalArgumentException("协调状态 JSON 无法解析", failure);
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 标识是否属于该发起人（长度前缀 + 分隔符 + 非空客户端部分）。 */
    private static boolean inScope(String requestId, String fromPartyId) {
        if (requestId == null || fromPartyId == null) {
            return false;
        }
        String prefix = fromPartyId.length() + ":" + fromPartyId + ":";
        return requestId.startsWith(prefix) && requestId.length() > prefix.length();
    }

    private static String sessionIdOf(String requestId) {
        return SESSION_PREFIX + requestId;
    }

    /** 该邀请实际占用的区间；取消、过期、错过与零长度会话都不占用。 */
    private static Interval occupiedInterval(Invite invite, Session session) {
        if (invite.startMillis() == NONE) {
            return null;
        }
        switch (invite.status()) {
            case ACCEPTED, ACTIVE, COMPLETED -> {
                long end;
                try {
                    end = Math.addExact(invite.startMillis(), invite.durationMillis());
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException(REASON_TIME_OVERFLOW, overflow);
                }
                return new Interval(invite.startMillis(), end);
            }
            case ENDED_EARLY -> {
                if (session == null || session.open()
                        || session.endedAtMillis() <= invite.startMillis()) {
                    return null;
                }
                return new Interval(invite.startMillis(), session.endedAtMillis());
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean mayInterview(Context context, Policy policy, String person, long now) {
        try {
            return RoleAccess.authorize(context.access(), person, RoleAccess.Action.WRITE_STORY,
                    RoleAccess.Target.organization(policy.mediaOrganizationPartyId()), now)
                    == RoleAccess.Decision.ALLOW;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private static boolean isBusy(State state, Context context, String person, Interval slot,
                                  String ignoredRequestId) {
        for (Interval interval : context.busyOf(person)) {
            if (interval.overlaps(slot)) {
                return true;
            }
        }
        for (Invite invite : state.invites()) {
            if (invite.requestId().equals(ignoredRequestId) || !isParticipant(invite, person)) {
                continue;
            }
            Interval occupied = occupiedInterval(invite,
                    findSession(state, sessionIdOf(invite.requestId())));
            if (occupied != null && occupied.overlaps(slot)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isParticipant(Invite invite, String person) {
        return invite.fromPartyId().equals(person) || invite.toPartyId().equals(person);
    }

    private static boolean sameRequest(Invite invite, String from, InviteRequest request,
                                       Policy policy) {
        return invite.fromPartyId().equals(from)
                && invite.toPartyId().equals(request.toPartyId())
                && invite.organizationPartyId().equals(policy.mediaOrganizationPartyId())
                && invite.topic().equals(request.topic())
                && invite.windowStartMillis() == request.windowStartMillis()
                && invite.windowEndMillis() == request.windowEndMillis()
                && invite.durationMillis() == request.durationMillis();
    }

    private static Invite find(State state, String requestId) {
        for (Invite invite : state.invites()) {
            if (invite.requestId().equals(requestId)) {
                return invite;
            }
        }
        return null;
    }

    private static Session findSession(State state, String sessionId) {
        for (Session session : state.sessions()) {
            if (session.sessionId().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    private static State replace(State state, Invite updated) {
        return new State(replaceList(state, updated), state.sessions(), state.persons(),
                state.policy());
    }

    private static List<Invite> replaceList(State state, Invite updated) {
        List<Invite> invites = new ArrayList<>(state.invites());
        for (int index = 0; index < invites.size(); index++) {
            if (invites.get(index).requestId().equals(updated.requestId())) {
                invites.set(index, updated);
            }
        }
        return invites;
    }

    private static void requireState(State state) {
        Objects.requireNonNull(state, "协调状态");
    }

    private static void requireContext(Context context) {
        Objects.requireNonNull(context, "受信上下文");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    private static void requirePositive(long value, String name) {
        requireNonNegative(value, name);
        if (value == 0) {
            throw new IllegalArgumentException(name + "必须为正：" + value);
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + "不能为负：" + value);
        }
    }
}
