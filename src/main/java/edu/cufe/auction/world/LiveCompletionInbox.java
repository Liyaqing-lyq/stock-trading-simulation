package edu.cufe.auction.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * B2c1 有界完成槽：只在内存保存“已持久派发 attempt”的短期完成元数据。
 *
 * <p><b>边界</b>：本类不持有 {@link DecisionModel.State}、{@link WorldRuntime}、经济账或密钥，
 * 不发起 HTTP、不写日志、不做世界计算；它只回答四个问题：</p>
 * <ul>
 *   <li>这次授权的原截止时点是什么（由 writer 在持久 dispatch 成功后给出）；</li>
 *   <li>worker 第一次报告完成时，宿主登记的单调/墙钟时点是什么；</li>
 *   <li>下一次由谁结算：及时回执、超时、还是超时之后才到的迟到回执；</li>
 *   <li>结算成功或失败重试时，同一次 claim 能否保持不变。</li>
 * </ul>
 *
 * <p><b>唯一同步点</b>：{@link #register} 与 {@link #claimDue} 都在同一把 {@code gate} 内完成状态选择；
 * 锁内只读两个时钟并改内存字段，不做 HTTP、journal、JSON 或世界调用。</p>
 *
 * <p><b>时间口径</b>：{@code timeoutNanos = wallTimeoutMillis × 1_000_000}（转换溢出拒绝）。
 * 判定一律用模 2^64 的差值：{@code arrivalNanos - dispatchNanos < timeoutNanos} 为及时，
 * {@code >= timeoutNanos} 为迟到/到期。因此 {@link System#nanoTime()} 的合法负值与
 * {@code Long.MAX_VALUE → Long.MIN_VALUE} 回绕都不会误判；不做绝对纳秒加法，也不要求时点非负。
 * epoch 仍要求非负，仅作审计展示。</p>
 *
 * <p><b>容量</b>：open 前必须检查，满了拒绝且不改状态；超时已结算但仍在等迟到结果的槽继续占容量，
 * 避免物理 HTTP 尚未结束时放出容量造成无界请求。HTTP 本身有 60 秒上限，close 会释放全部元数据。</p>
 */
public final class LiveCompletionInbox implements AutoCloseable {

    /** 完整尝试身份；不使用字符串拼接 key。 */
    public record AttemptKey(String runId, String actionId, String attemptId, int attemptNo,
                             long recoveryGeneration) {

        /** 校验身份字段。 */
        public AttemptKey {
            requireText(runId, "运行标识");
            requireText(actionId, "动作标识");
            requireText(attemptId, "尝试标识");
            if (attemptNo < 1) {
                throw new IllegalArgumentException("尝试序号从 1 开始");
            }
            if (recoveryGeneration < 1) {
                throw new IllegalArgumentException("恢复代次从 1 开始");
            }
        }

        /**
         * 从已持久派发的 Call 取身份。
         *
         * @param call 派发描述
         * @return 完整身份
         */
        public static AttemptKey of(DecisionRun.Call call) {
            Objects.requireNonNull(call, "派发描述");
            return new AttemptKey(call.runId(), call.actionId(), call.attemptId(),
                    call.attemptNo(), call.recoveryGeneration());
        }
    }

    /**
     * 不可变完成候选：业务回执（正文非空、{@code failureCode == null}）或规范化技术失败
     * （正文为空、携带固定失败码与真实 usage）。
     *
     * <p>技术失败的空正文是“提供方响应不可用”的明确标记，不是伪造的观望回执；
     * 真实映射由后续 Runner 完成，本类只保存不可变数据。</p>
     */
    public record Completion(DecisionModel.Reply reply, String failureCode) {

        private static final java.util.regex.Pattern CODE =
                java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

        /** 校验二选一形状与固定失败码。 */
        public Completion {
            Objects.requireNonNull(reply, "回执");
            if (failureCode == null) {
                if (reply.body().isBlank()) {
                    throw new IllegalArgumentException("业务回执必须有正文");
                }
            } else {
                if (!CODE.matcher(failureCode).matches()) {
                    throw new IllegalArgumentException("失败码必须是固定大写枚举");
                }
                if (!reply.body().isEmpty()) {
                    throw new IllegalArgumentException("技术失败不得携带正文");
                }
            }
        }

        /**
         * 业务回执。
         *
         * @param reply 提供方正文回执
         * @return 完成候选
         */
        public static Completion business(DecisionModel.Reply reply) {
            return new Completion(reply, null);
        }

        /**
         * 规范化技术失败。
         *
         * @param code    固定失败码
         * @param failure 规范化回执：正文为空、usage 可为 null（未知）、来源保留
         * @return 完成候选
         */
        public static Completion failure(String code, DecisionModel.Reply failure) {
            return new Completion(failure, code);
        }

        /** @return 是否业务回执 */
        public boolean businessReply() {
            return failureCode == null;
        }
    }

    /** 下一次结算类型。 */
    public enum Kind {
        /** 及时回执。 */
        REPLY,
        /** 无及时回执且已到期。 */
        TIMEOUT,
        /** 超时已持久之后才登记的回执，只归档。 */
        LATE
    }

    /** open 结果。 */
    public enum OpenResult {
        /** 已建立槽。 */
        OPENED,
        /** 同一身份已存在；不更新时间或截止。 */
        DUPLICATE,
        /** 容量已满；状态不变。 */
        FULL,
        /** 已关闭。 */
        CLOSED
    }

    /** register 结果。 */
    public enum RegisterResult {
        /** 首次登记成功，时间已固定。 */
        REGISTERED,
        /** 已有候选或已 claim；不覆盖、不刷新时间。 */
        DUPLICATE,
        /** 没有该身份的槽（未授权、已移除或已超时归档）。 */
        UNKNOWN,
        /** 已关闭。 */
        CLOSED
    }

    /** 结算/重试结果。 */
    public enum AckResult {
        /** 成功结算；REPLY/LATE 移除槽，TIMEOUT 保留等待迟到。 */
        SETTLED,
        /** 失败重试已登记；下一次 claimDue 返回同一 claim。 */
        RETRYING,
        /** claim 已被处理或不再属于该槽。 */
        STALE_CLAIM,
        /** inbox 已关闭。 */
        CLOSED
    }

    /** 由 inbox 发出的不可变结算请求；外部不能构造。 */
    public static final class Claim {
        private final LiveCompletionInbox owner;
        private final AttemptKey key;
        private final Kind kind;
        private final Completion candidate;
        private final long arrivalNanos;
        private final long arrivalEpochMillis;
        private final long deadlineNanos;

        private Claim(LiveCompletionInbox owner, AttemptKey key, Kind kind,
                      Completion candidate, long arrivalNanos, long arrivalEpochMillis,
                      long deadlineNanos) {
            this.owner = owner;
            this.key = key;
            this.kind = kind;
            this.candidate = candidate;
            this.arrivalNanos = arrivalNanos;
            this.arrivalEpochMillis = arrivalEpochMillis;
            this.deadlineNanos = deadlineNanos;
        }

        /** @return 尝试身份 */
        public AttemptKey key() {
            return key;
        }

        /** @return 本次结算类型 */
        public Kind kind() {
            return kind;
        }

        /** @return 候选；TIMEOUT 为 null */
        public Completion candidate() {
            return candidate;
        }

        /** @return 宿主登记时点（单调纳秒）；TIMEOUT 为本次选择时点 */
        public long arrivalNanos() {
            return arrivalNanos;
        }

        /** @return 宿主登记时点（epoch 毫秒）；TIMEOUT 为本次选择时点 */
        public long arrivalEpochMillis() {
            return arrivalEpochMillis;
        }

        /** @return 模 2^64 的截止展示值；判定只用 arrival-dispatch 差值与 timeoutNanos */
        public long deadlineNanos() {
            return deadlineNanos;
        }

        /** 不输出正文、凭据或提供方元数据。 */
        @Override
        public String toString() {
            return "Claim[key=" + key.attemptId() + ", kind=" + kind + "]";
        }
    }

    private static final class Slot {
        private final AttemptKey key;
        private final long dispatchNanos;
        private final long timeoutNanos;
        private Completion candidate;
        private long arrivalNanos;
        private long arrivalEpochMillis;
        private Claim claim;
        private boolean timeoutSettled;
        private boolean retryPending;

        private Slot(AttemptKey key, long dispatchNanos, long timeoutNanos) {
            this.key = key;
            this.dispatchNanos = dispatchNanos;
            this.timeoutNanos = timeoutNanos;
        }

        /** 仅展示/诊断用的模 2^64 截止值；判定一律用差值。 */
        private long deadlineNanos() {
            return dispatchNanos + timeoutNanos;
        }
    }

    private final int capacity;
    private final LongSupplier nanoClock;
    private final LongSupplier epochClock;
    private final Object gate = new Object();
    private final Map<AttemptKey, Slot> slots = new HashMap<>();
    private boolean closed;

    /**
     * 生产构造：真实单调与墙钟。
     *
     * @param capacity 来自 run maxInFlight 的有界容量，必须为正
     */
    public LiveCompletionInbox(int capacity) {
        this(capacity, System::nanoTime, System::currentTimeMillis);
    }

    /**
     * 包内测试构造：可注入两个时钟。
     *
     * @param capacity   容量
     * @param nanoClock  单调纳秒
     * @param epochClock 墙钟毫秒
     */
    LiveCompletionInbox(int capacity, LongSupplier nanoClock, LongSupplier epochClock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("容量必须为正");
        }
        this.capacity = capacity;
        this.nanoClock = Objects.requireNonNull(nanoClock, "单调时钟");
        this.epochClock = Objects.requireNonNull(epochClock, "墙钟");
    }

    /** @return 当前单调纳秒（包内：Runtime 用它给同一授权采样 DispatchMark） */
    long nowNanos() {
        synchronized (gate) {
            return nanoClock.getAsLong();
        }
    }

    /** @return 当前墙钟毫秒（包内） */
    long nowEpochMillis() {
        synchronized (gate) {
            return epochClock.getAsLong();
        }
    }

    /** @return 当前处于失败重试、等待下一次 claimDue 的槽数（包内诊断用） */
    int retryPendingCount() {
        synchronized (gate) {
            int count = 0;
            for (Slot slot : slots.values()) {
                if (slot.retryPending) {
                    count++;
                }
            }
            return count;
        }
    }

    /** @return 容量 */
    public int capacity() {
        return capacity;
    }

    /** @return 当前占用的槽数（含等待迟到结果的超时槽） */
    public int size() {
        synchronized (gate) {
            return slots.size();
        }
    }

    /** @return 是否已关闭 */
    public boolean isClosed() {
        synchronized (gate) {
            return closed;
        }
    }

    /**
     * 在持久 dispatch 成功之后、Call 暴露给 worker 之前建立槽。
     *
     * @param key                完整尝试身份
     * @param dispatchNanos      授权批次的单调纳秒
     * @param dispatchEpochMillis 授权批次的墙钟毫秒
     * @param wallTimeoutMillis  冻结的墙钟超时毫秒，必须为正
     * @return 建立结果；容量满或重复时不改任何既有槽
     */
    public OpenResult open(AttemptKey key, long dispatchNanos, long dispatchEpochMillis,
                           long wallTimeoutMillis) {
        Objects.requireNonNull(key, "尝试身份");
        if (dispatchEpochMillis < 0 || wallTimeoutMillis < 1) {
            throw new IllegalArgumentException("派发墙钟或超时不合法");
        }
        long timeoutNanos;
        try {
            timeoutNanos = Math.multiplyExact(wallTimeoutMillis, 1_000_000L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("超时纳秒溢出", overflow);
        }
        synchronized (gate) {
            if (closed) {
                return OpenResult.CLOSED;
            }
            if (slots.containsKey(key)) {
                return OpenResult.DUPLICATE;
            }
            if (slots.size() >= capacity) {
                return OpenResult.FULL;
            }
            slots.put(key, new Slot(key, dispatchNanos, timeoutNanos));
            return OpenResult.OPENED;
        }
    }

    /**
     * worker 首次报告完成：在 gate 内读取两时钟并一次性登记。
     *
     * @param key       尝试身份
     * @param completion 完成候选
     * @return 登记结果；重复不覆盖、不刷新时间
     */
    public RegisterResult register(AttemptKey key, Completion completion) {
        Objects.requireNonNull(key, "尝试身份");
        Objects.requireNonNull(completion, "完成候选");
        requireIdentity(key, completion.reply());
        synchronized (gate) {
            if (closed) {
                return RegisterResult.CLOSED;
            }
            Slot slot = slots.get(key);
            if (slot == null) {
                return RegisterResult.UNKNOWN;
            }
            if (slot.candidate != null) {
                return RegisterResult.DUPLICATE;
            }
            slot.candidate = completion;
            slot.arrivalNanos = nanoClock.getAsLong();
            slot.arrivalEpochMillis = epochClock.getAsLong();
            return RegisterResult.REGISTERED;
        }
    }

    /**
     * 在 gate 内选出最多 {@code max} 个可结算项：及时回执、到期超时、超时后的迟到回执。
     *
     * <p>已登记但晚于截止的候选不会直接变成 LATE：先给出 TIMEOUT，等超时持久之后再给 LATE。
     * 已发出且重试中的 claim 会原样返回，不重新判定类型。</p>
     *
     * @param max 本次最多返回数量，必须为正
     * @return 不可变 claim 列表；没有可结算项时为空
     */
    public List<Claim> claimDue(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max 必须为正");
        }
        long nowNanos;
        long nowEpoch;
        synchronized (gate) {
            if (closed) {
                return List.of();
            }
            nowNanos = nanoClock.getAsLong();
            nowEpoch = epochClock.getAsLong();
            List<Claim> claims = new ArrayList<>();
            for (Slot slot : slots.values()) {
                if (claims.size() >= max) {
                    break;
                }
                if (slot.claim != null) {
                    if (slot.retryPending) {
                        slot.retryPending = false;
                        claims.add(slot.claim);
                    }
                    continue;
                }
                if (slot.timeoutSettled) {
                    if (slot.candidate != null) {
                        Claim late = new Claim(this, slot.key, Kind.LATE, slot.candidate,
                                slot.arrivalNanos, slot.arrivalEpochMillis,
                                slot.deadlineNanos());
                        slot.claim = late;
                        claims.add(late);
                    }
                    continue;
                }
                if (slot.candidate != null
                        && slot.arrivalNanos - slot.dispatchNanos < slot.timeoutNanos) {
                    Claim reply = new Claim(this, slot.key, Kind.REPLY, slot.candidate,
                            slot.arrivalNanos, slot.arrivalEpochMillis, slot.deadlineNanos());
                    slot.claim = reply;
                    claims.add(reply);
                    continue;
                }
                if (nowNanos - slot.dispatchNanos >= slot.timeoutNanos) {
                    Claim timeout = new Claim(this, slot.key, Kind.TIMEOUT, null, nowNanos,
                            nowEpoch, slot.deadlineNanos());
                    slot.claim = timeout;
                    claims.add(timeout);
                }
            }
            return List.copyOf(claims);
        }
    }

    /**
     * 世界提交成功后的确认：REPLY/LATE 移除槽；TIMEOUT 保留槽等待迟到结果。
     *
     * @param claim 本 inbox 发出的 claim
     * @return 确认结果
     */
    public AckResult settle(Claim claim) {
        requireOwnClaim(claim);
        synchronized (gate) {
            if (closed) {
                return AckResult.CLOSED;
            }
            Slot slot = slots.get(claim.key);
            if (slot == null || slot.claim != claim) {
                return AckResult.STALE_CLAIM;
            }
            slot.claim = null;
            slot.retryPending = false;
            if (claim.kind == Kind.TIMEOUT) {
                slot.timeoutSettled = true;
            } else {
                slots.remove(claim.key);
            }
            return AckResult.SETTLED;
        }
    }

    /**
     * 世界提交失败（IO/暂停）后的重试登记：保持同一个 claim 与同一类型。
     *
     * @param claim 本 inbox 发出的 claim
     * @return 重试结果
     */
    public AckResult retry(Claim claim) {
        requireOwnClaim(claim);
        synchronized (gate) {
            if (closed) {
                return AckResult.CLOSED;
            }
            Slot slot = slots.get(claim.key);
            if (slot == null || slot.claim != claim) {
                return AckResult.STALE_CLAIM;
            }
            slot.retryPending = true;
            return AckResult.RETRYING;
        }
    }

    /**
     * 查询某个身份当前是否仍占槽（诊断用）。
     *
     * @param key 尝试身份
     * @return 占用时返回截止纳秒
     */
    public Optional<Long> deadlineOf(AttemptKey key) {
        Objects.requireNonNull(key, "尝试身份");
        synchronized (gate) {
            Slot slot = slots.get(key);
            return slot == null ? Optional.empty() : Optional.of(slot.deadlineNanos());
        }
    }

    /** 关闭：释放全部元数据；之后的 open/register 返回 CLOSED。 */
    @Override
    public void close() {
        synchronized (gate) {
            closed = true;
            slots.clear();
        }
    }

    private void requireOwnClaim(Claim claim) {
        if (claim == null || claim.owner != this) {
            throw new IllegalArgumentException("claim 不属于本 inbox");
        }
    }

    private static void requireIdentity(AttemptKey key, DecisionModel.Reply reply) {
        if (!key.runId().equals(reply.runId())
                || !key.actionId().equals(reply.actionId())
                || !key.attemptId().equals(reply.attemptId())
                || key.attemptNo() != reply.attemptNo()
                || key.recoveryGeneration() != reply.recoveryGeneration()) {
            throw new IllegalArgumentException("完成候选与尝试身份不一致");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
