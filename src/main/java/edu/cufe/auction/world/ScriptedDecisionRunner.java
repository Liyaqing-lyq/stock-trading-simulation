package edu.cufe.auction.world;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 具体 scripted 决定提供方：按固定脚本返回回执，不联网、不读世界领域对象。
 *
 * <p>脚本只提供回执正文、声明用量、来源与真实墙钟延迟；<b>不提供</b>人物、任职、尝试标识、
 * 恢复代次、命令/任务标识或模拟时间——这些一律由运行桥按冻结状态绑定。脚本费用恒为
 * {@code 0.00}（见 {@link DecisionModel.Usage#cost()}）。</p>
 *
 * <p><b>一次执行保证</b>：同一完整尝试身份（{@link AttemptKey}）在<b>排程前</b>就被占用，
 * 因此重复 {@code deliverAsync} 不会排出第二个任务；脚本缺失、脚本抛错与中断也各只产生一份
 * 不可变结果并被缓存，重投只重用同一份结果，绝不重新调用提供方、也不二次计费。</p>
 *
 * <p><b>交付语义</b>：投递队列满时做有界等待；等待后仍满，结果保留在
 * {@link #pendingDeliveries()} 里，由宿主调用 {@link #retryPending()} 显式重投。
 * 异步工作线程绝不把回执或失败候选当作异常丢弃。</p>
 *
 * <p><b>线程</b>：外部 {@link Executor} 归调用者所有；{@code parallelism} 构造只创建一个内部
 * 线程池，并由 {@link #close()} 关闭，关闭后 JVM 可正常退出。异步任务只产生结果并投递队列，
 * 世界结算始终发生在调用者的写入线程；工作线程不得调用 {@code dispatchDecision}。</p>
 */
public final class ScriptedDecisionRunner implements AutoCloseable {

    /** 一条脚本文本：正文、声明用量、来源与现代价墙钟延迟（毫秒）。 */
    public record ScriptedReply(String body, DecisionModel.Usage usage, String usageSource,
                                long delayMillis) {

        /** 校验字段；脚本费用必须为零元。 */
        public ScriptedReply {
            Objects.requireNonNull(body, "回执正文");
            Objects.requireNonNull(usage, "声明用量");
            usageSource = usageSource == null ? "" : usageSource;
            if (delayMillis < 0) {
                throw new IllegalArgumentException("脚本延迟不能为负：" + delayMillis);
            }
            if (usage.cost().signum() != 0) {
                throw new IllegalArgumentException("scripted 脚本费用必须为 0.00："
                        + usage.cost());
            }
        }
    }

    /** 完整尝试身份；用 record 而不是拼接字符串，避免分隔符歧义。 */
    private record AttemptKey(String runId, String actionId, String attemptId, int attemptNo,
                              long recoveryGeneration) {

        static AttemptKey of(DecisionRun.Call call) {
            return new AttemptKey(call.runId(), call.actionId(), call.attemptId(),
                    call.attemptNo(), call.recoveryGeneration());
        }

        String actionIdOf() {
            return actionId;
        }
    }

    /** 一次已冻结的结果：成功回执或不可变失败候选，二者互斥。 */
    private record Outcome(DecisionModel.Reply reply, WorldRuntime.DecisionFailure failure,
                           String reason) {

        static Outcome reply(DecisionModel.Reply reply) {
            return new Outcome(Objects.requireNonNull(reply, "回执"), null, "");
        }

        static Outcome failure(DecisionRun.Call call, String reason) {
            return new Outcome(null, new WorldRuntime.DecisionFailure(call.actionId(),
                    call.attemptNo(), call.attemptId(), call.recoveryGeneration(), reason),
                    reason);
        }

        boolean failed() {
            return failure != null;
        }
    }

    /** 单次投递的有界等待上限（毫秒）；超时后结果保留给宿主重投。 */
    private static final long OFFER_WAIT_MILLIS = 500L;

    /** 有界等待的轮询间隔（毫秒）。 */
    private static final long OFFER_PAUSE_MILLIS = 1L;

    private final WorldRuntime runtime;
    private final Map<String, ScriptedReply> script;
    private final Executor executor;
    private final boolean ownsExecutor;
    private final Map<AttemptKey, Outcome> outcomes = new ConcurrentHashMap<>();
    private final Set<AttemptKey> claimed = ConcurrentHashMap.newKeySet();
    private final Map<AttemptKey, Outcome> undelivered = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * 同步 runner：{@link #deliver(String)} 在调用线程完成脚本求值与入队。
     *
     * @param runtime 运行桥
     * @param script  动作标识 → 脚本文本
     */
    public ScriptedDecisionRunner(WorldRuntime runtime, Map<String, ScriptedReply> script) {
        this(runtime, script, null, false);
    }

    /**
     * 异步 runner：{@link #deliverAsync(String)} 把脚本求值放到给定的外部执行器。
     *
     * @param runtime  运行桥
     * @param script   动作标识 → 脚本文本
     * @param executor 外部执行器；归调用者所有，本类不关闭
     */
    public ScriptedDecisionRunner(WorldRuntime runtime, Map<String, ScriptedReply> script,
                                  Executor executor) {
        this(runtime, script, Objects.requireNonNull(executor, "执行器"), false);
    }

    /**
     * 自建线程池的异步 runner：内部只创建一个固定线程池，{@link #close()} 负责关闭它。
     *
     * @param runtime     运行桥
     * @param script      动作标识 → 脚本文本
     * @param parallelism 内部线程数（至少 1）
     */
    public ScriptedDecisionRunner(WorldRuntime runtime, Map<String, ScriptedReply> script,
                                  int parallelism) {
        this(runtime, script, newPool(parallelism), true);
    }

    private ScriptedDecisionRunner(WorldRuntime runtime, Map<String, ScriptedReply> script,
                                   Executor executor, boolean ownsExecutor) {
        this.runtime = Objects.requireNonNull(runtime, "运行桥");
        this.script = Collections.unmodifiableMap(new TreeMap<>(
                Objects.requireNonNull(script, "脚本")));
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** 只创建一个命名线程池，便于关闭与排障。 */
    private static ExecutorService newPool(int parallelism) {
        AtomicInteger index = new AtomicInteger();
        return Executors.newFixedThreadPool(Math.max(1, parallelism), runnable -> {
            Thread thread = new Thread(runnable, "scripted-decisions-" + index.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
    }

    /**
     * 由派发描述生成回执：身份逐项取自调用，正文与用量取自脚本。
     *
     * @param call 派发描述
     * @return 提供方回执
     * @throws IllegalArgumentException 脚本缺少该动作
     */
    public DecisionModel.Reply replyFor(DecisionRun.Call call) {
        Objects.requireNonNull(call, "调用描述");
        ScriptedReply scripted = script.get(call.actionId());
        if (scripted == null) {
            throw new IllegalArgumentException("脚本缺少动作：" + call.actionId());
        }
        return new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                call.attemptNo(), call.recoveryGeneration(), scripted.body(), scripted.usage(),
                scripted.usageSource());
    }

    /**
     * 同步派发一次：脚本最多求值一次，结果立即投递。
     *
     * @param actionId 动作标识
     * @return {@code QUEUED}、{@code QUEUE_FULL}（结果已保留，可重投）、
     *         {@code IN_FLIGHT}（同一尝试正在执行）或
     *         {@code TECHNICAL_FAILED:<reason>}
     * @throws IOException 运行桥派发或持久化失败
     */
    public String deliver(String actionId) throws IOException {
        requireUsable();
        DecisionRun.Call call = runtime.dispatchDecision(actionId);
        AttemptKey key = AttemptKey.of(call);
        Outcome outcome = outcomes.get(key);
        if (outcome == null) {
            if (!claimed.add(key)) {
                return "IN_FLIGHT";
            }
            outcome = compute(call);
            outcomes.put(key, outcome);
        }
        if (deliverNow(outcome)) {
            undelivered.remove(key);
            return outcome.failed() ? "TECHNICAL_FAILED:" + outcome.reason() : "QUEUED";
        }
        undelivered.put(key, outcome);
        return "QUEUE_FULL";
    }

    /**
     * 异步派发一次：脚本求值与真实延迟在执行器里完成，结果投递或保留待重投。
     *
     * <p>同一尝试重复调用不会排出第二个任务，也不会重新执行脚本。</p>
     *
     * @param actionId 动作标识
     * @throws IOException                运行桥派发失败
     * @throws IllegalStateException       runner 已关闭或未配置执行器
     * @throws RejectedExecutionException 执行器拒绝；此时尝试身份立即释放
     */
    public void deliverAsync(String actionId) throws IOException {
        requireUsable();
        Executor pool = executor;
        if (pool == null) {
            throw new IllegalStateException("未配置执行器，不能异步派发");
        }
        DecisionRun.Call call = runtime.dispatchDecision(actionId);
        AttemptKey key = AttemptKey.of(call);
        if (!claimed.add(key)) {
            // 同一尝试已经排过或已完成：绝不排第二个实际调用。
            return;
        }
        try {
            pool.execute(() -> executeAsync(call, key));
        } catch (RuntimeException rejected) {
            // 执行器拒绝不算占用：释放身份，宿主可重新派发。
            claimed.remove(key);
            throw rejected;
        }
    }

    /** 工作线程：只产生结果并投递，绝不调用 dispatch，也不把交付失败抛成异常。 */
    private void executeAsync(DecisionRun.Call call, AttemptKey key) {
        Outcome outcome = outcomes.get(key);
        if (outcome == null) {
            ScriptedReply scripted = script.get(call.actionId());
            if (scripted != null && scripted.delayMillis() > 0) {
                try {
                    Thread.sleep(scripted.delayMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    outcome = Outcome.failure(call, "SCRIPT_INTERRUPTED");
                }
            }
            if (outcome == null) {
                outcome = compute(call);
            }
            Outcome existing = outcomes.putIfAbsent(key, outcome);
            outcome = existing == null ? outcome : existing;
        }
        if (deliverNow(outcome)) {
            undelivered.remove(key);
        } else {
            undelivered.put(key, outcome);
        }
    }

    /** 脚本求值一次；缺失与异常都冻结成不可变失败结果。 */
    private Outcome compute(DecisionRun.Call call) {
        ScriptedReply scripted = script.get(call.actionId());
        if (scripted == null) {
            return Outcome.failure(call, "SCRIPT_MISSING");
        }
        try {
            return Outcome.reply(new DecisionModel.Reply(call.runId(), call.actionId(),
                    call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                    scripted.body(), scripted.usage(), scripted.usageSource()));
        } catch (RuntimeException failure) {
            return Outcome.failure(call, "SCRIPT_ERROR");
        }
    }

    /** 有界等待投递：成功返回 true；超时返回 false 并由调用方保留结果。 */
    private boolean deliverNow(Outcome outcome) {
        long deadline = System.nanoTime() + OFFER_WAIT_MILLIS * 1_000_000L;
        while (true) {
            if (offer(outcome)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(OFFER_PAUSE_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** 单次投递；只把 {@code QUEUE_FULL} 当作"还没送达"。 */
    private boolean offer(Outcome outcome) {
        String code = outcome.failed()
                ? runtime.offerDecisionFailure(outcome.failure())
                : runtime.offerDecisionCallback(outcome.reply());
        return !"QUEUE_FULL".equals(code);
    }

    /**
     * 宿主重投已保留结果：只重发同一份回执或用同一份失败候选，不重新执行脚本。
     *
     * @return 本次成功投递的条数
     */
    public int retryPending() {
        requireUsable();
        int delivered = 0;
        for (Map.Entry<AttemptKey, Outcome> entry : pendingEntries()) {
            if (offer(entry.getValue())) {
                undelivered.remove(entry.getKey());
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * 已产生但尚未投递的结果数量；大于 0 表示宿主应调用 {@link #retryPending()}。
     *
     * @return 待重投条数
     */
    public int pendingDeliveries() {
        return undelivered.size();
    }

    /** 待重投条目按动作标识与尝试序号稳定排序，便于可复现的重投顺序。 */
    private List<Map.Entry<AttemptKey, Outcome>> pendingEntries() {
        List<Map.Entry<AttemptKey, Outcome>> entries = new ArrayList<>(undelivered.entrySet());
        entries.sort((left, right) -> {
            int byAction = left.getKey().actionIdOf().compareTo(right.getKey().actionIdOf());
            return byAction != 0 ? byAction
                    : Integer.compare(left.getKey().attemptNo(), right.getKey().attemptNo());
        });
        return entries;
    }

    /** @return 已实际执行过的尝试数（成功与失败结果都算一次执行） */
    public int executedCount() {
        return outcomes.size();
    }

    /** @return 已执行过的动作标识集合（审计用，不含身份细节） */
    public Set<String> deliveredActions() {
        Set<String> actions = new LinkedHashSet<>();
        for (AttemptKey key : outcomes.keySet()) {
            actions.add(key.actionIdOf());
        }
        return Set.copyOf(actions);
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException("runner 已关闭");
        }
    }

    /** 关闭：只在自建线程池时关闭它；外部执行器保持不动。 */
    @Override
    public void close() {
        closed = true;
        if (ownsExecutor && executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }
}
