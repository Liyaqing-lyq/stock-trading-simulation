package edu.cufe.auction.web;

import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.RoleView;
import edu.cufe.auction.world.ValuationModel;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.WorldRuntime;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 唯一 writer 宿主：在自有单线程里创建 {@link WorldRuntime}，并把全部读写任务投递到该线程。
 *
 * <p>为什么所有读也走同一线程：`WorldRuntime` 的写入口只接受创建它的线程
 * （{@code requireWriter}），而"同一截止快照"要求快照与派生估值来自同一个已确认状态，
 * 因此读任务也在 writer 线程串行执行，避免跨快照拼接。</p>
 *
 * <p>有界队列固定 32；满时立即抛 {@link BusyException}，不阻塞调用方、不无限排队。</p>
 */
public final class WorldWebHost implements AutoCloseable {

    /** 有界队列容量。 */
    public static final int QUEUE_CAPACITY = 32;

    /**
     * 世界打开结果：运行时与它真实使用的运行标识（同一份 manifest）。
     *
     * @param runtime 运行实例
     * @param runId   manifest 里的运行标识
     */
    public record Opened(WorldRuntime runtime, String runId) {
        public Opened {
            if (runtime == null || runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("世界打开结果不合法");
            }
        }
    }

    /** 创建运行的可信工厂；只会被 writer 线程调用一次。 */
    public interface IOSupplier {
        Opened open() throws Exception;
    }

    /** 队列已满。 */
    public static final class BusyException extends Exception {
        BusyException() {
            super("BUSY");
        }
    }

    /**
     * 单次管理快照（所有 Optional 显式 orElse(null) 后再取布尔标记）。
     *
     * @param position       已确认位置
     * @param timeMillis     世界时点
     * @param phase          最后阶段
     * @param paused         是否暂停
     * @param status         状态码：READY / PAUSED / CLOSED
     * @param accounts       现金账户
     * @param pendingTasks   待办数量
     * @param deliveredNotices 已投递通知数量
     * @param hasSecurities  是否有证券子集
     * @param hasExchange    是否有撮合子集
     * @param hasManufacturing 是否有制造子集
     * @param hasGovernance  是否有治理子集
     * @param hasRoles       是否有任职子集
     * @param hasInformation 是否有阅读子集
     * @param hasTrading     是否有交易子集
     * @param hasDecisions   是否有决定子集
     * @param hasDividends   是否有分红子集
     */
    public record AdminSnapshot(long position, long timeMillis, String phase, boolean paused,
                                String status, List<AccountView> accounts, int pendingTasks,
                                int deliveredNotices, boolean hasSecurities, boolean hasExchange,
                                boolean hasManufacturing, boolean hasGovernance, boolean hasRoles,
                                boolean hasInformation, boolean hasTrading, boolean hasDecisions,
                                boolean hasDividends) {
    }

    /**
     * 账户视图。
     *
     * @param accountId 账户标识
     * @param ownerId   财产主体
     * @param cash      现金
     */
    public record AccountView(String accountId, String ownerId, BigDecimal cash) {
    }

    /**
     * 某主体的估值（与快照同一 writer 任务内取得）。
     *
     * @param ownerId    财产主体
     * @param position   位置
     * @param atMillis   时点
     * @param status     估值状态
     * @param netWorth   净资产；不支持/缺价时为 null
     * @param cash       现金
     * @param holdings   持仓市值
     */
    public record OwnerValue(String ownerId, long position, long atMillis, String status,
                             BigDecimal netWorth, BigDecimal cash, BigDecimal holdings) {
    }

    private record Task<T>(Callable<T> action, CompletableFuture<T> future) {
    }

    private static final Task<Void> POISON = new Task<>(null, new CompletableFuture<>());

    private final IOSupplier supplier;
    private final ArrayBlockingQueue<Task<?>> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final CompletableFuture<WorldRuntime> ready = new CompletableFuture<>();
    private final Thread writer;
    private volatile String runId;
    private final Object gate = new Object();
    private volatile boolean closed;
    private volatile WorldRuntime runtime;

    /**
     * 启动 writer 线程并在其中创建运行。
     *
     * <p>运行标识只有一处来源：工厂返回的 {@link Opened#runId()}（即真实 manifest 的 runId）。</p>
     *
     * @param supplier 可信工厂
     */
    public WorldWebHost(IOSupplier supplier) {
        if (supplier == null) {
            throw new IllegalArgumentException("宿主参数不合法");
        }
        this.supplier = supplier;
        this.writer = new Thread(this::loop, "world-web-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /** @return 运行标识 */
    public String runId() {
        return runId;
    }

    /** @return 当前是否已关闭 */
    public boolean isClosed() {
        synchronized (gate) {
            return closed;
        }
    }

    /** @return writer 线程是否存活（测试用） */
    public boolean writerAlive() {
        return writer.isAlive();
    }

    /** @return 队列当前长度（测试/观测用） */
    public int queueSize() {
        return queue.size();
    }

    /**
     * 在 writer 线程执行一次读任务。
     *
     * @param action 任务
     * @param <T>    结果类型
     * @return 结果
     * @throws BusyException 队列已满
     * @throws Exception     任务本身抛出的异常（原样上抛）
     */
    public <T> T call(Callable<T> action) throws BusyException, Exception {
        Task<T> task = new Task<>(action, new CompletableFuture<>());
        synchronized (gate) {
            if (closed) {
                throw new IllegalStateException("宿主已关闭");
            }
            if (!queue.offer(task)) {
                throw new BusyException();
            }
        }
        return awaitResult(task);
    }

    /**
     * 等待真实终态：调用方被中断时只记标记、继续等待，拿到结果后再恢复标记。
     * 这样不会出现"报告失败但操作随后仍执行"的假失败。
     */
    private <T> T awaitResult(Task<T> task) throws Exception {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return task.future().get();
                } catch (InterruptedException stop) {
                    interrupted = true;
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw new IllegalStateException("writer 任务失败", cause);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 管理快照：一次任务内取一份 {@code Snapshot} 并派生全部字段。
     *
     * @return 快照 DTO
     * @throws BusyException 队列满
     * @throws Exception     运行读取失败
     */
    public AdminSnapshot adminSnapshot() throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            List<AccountView> accounts = new ArrayList<>();
            snapshot.accounts().forEach((accountId, account) -> accounts.add(new AccountView(
                    accountId, account.ownerId(), account.cash())));
            accounts.sort((left, right) -> left.accountId().compareTo(right.accountId()));
            boolean paused = current.isPaused();
            return new AdminSnapshot(snapshot.position(), snapshot.timeMillis(),
                    snapshot.lastPhase(), paused, paused ? "PAUSED" : "READY", accounts,
                    snapshot.pending().size(), snapshot.delivered().size(),
                    snapshot.securities().isPresent(), snapshot.exchange().isPresent(),
                    snapshot.manufacturing().isPresent(), snapshot.governance().isPresent(),
                    snapshot.roles().isPresent(), snapshot.information().isPresent(),
                    snapshot.trading().isPresent(), snapshot.decisions().isPresent(),
                    snapshot.dividends().isPresent());
        });
    }

    /**
     * 人物角色视图（同一 writer 任务内取）。
     *
     * @param personId 服务端绑定的人物
     * @return 角色视图
     * @throws BusyException 队列满
     * @throws Exception     人物不可见或模块未启用
     */
    public RoleView roleView(String personId) throws BusyException, Exception {
        return call(() -> readyNow().roleView(personId));
    }

    /** 工作台账户行（金额为精确字符串）。 */
    public record ViewAccount(String accountId, String ownerId, String cash) {
    }

    /** 工作台待办行（时点为精确字符串）。 */
    public record ViewPending(String taskId, String dueTimeMillis, String phase, String kind) {
    }

    /** 工作台真实成交行。 */
    public record ViewTrade(String tradeId, String symbol, String price, String quantity,
                            String timeMillis, String buyAccountId, String sellAccountId) {
    }

    /** 工作台分红报告行。 */
    public record ViewDividendReport(String taskId, String kind, String schemeId,
                                     String position, String atMillis, boolean accepted,
                                     String reason) {
    }

    /** 工作台主体净值行（估值取不到时金额为 null，不冒充 0）。 */
    public record ViewOwner(String ownerId, String status, String netWorth, String cash,
                            String holdings) {
    }

    /**
     * 工作台视图：在**同一个** writer 任务内取一份快照并派生全部区块，因此所有区块共享同一
     * {@code position} 与同一世界时点；数字一律用精确字符串，避免浏览器 JS 精度丢失。
     *
     * @param runId            运行标识
     * @param position         已确认位置（字符串）
     * @param timeMillis       世界时点（字符串）
     * @param phase            最后阶段
     * @param paused           是否暂停
     * @param status           READY / PAUSED
     * @param providerKind     决定提供方（如实显示 SCRIPTED 或真实种类）
     * @param accounts         现金账户
     * @param pending          待办
     * @param trades           真实成交
     * @param dividendReports  分红报告
     * @param owners           主体净值
     * @param valuationsAvailable 估值是否可用（不可用时不显示净值而非显示 0）
     * @param barrierPending   是否存在未决决定屏障
     * @param pendingCount     待办数量
     * @param deliveredCount   已投递通知数量
     */
    public record AdminView(String runId, String position, String timeMillis, String phase,
                            boolean paused, String status, String providerKind,
                            List<ViewAccount> accounts, List<ViewPending> pending,
                            List<ViewTrade> trades, List<ViewDividendReport> dividendReports,
                            List<ViewOwner> owners, boolean valuationsAvailable,
                            boolean barrierPending, int pendingCount, int deliveredCount) {
    }

    /**
     * 工作台视图（单 writer 任务、单快照）。
     *
     * @return 视图
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public AdminView adminView() throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            List<ViewAccount> accounts = new ArrayList<>();
            snapshot.accounts().forEach((accountId, account) -> accounts.add(new ViewAccount(
                    accountId, account.ownerId(), account.cash().toPlainString())));
            accounts.sort((left, right) -> left.accountId().compareTo(right.accountId()));
            List<ViewPending> pending = new ArrayList<>();
            for (PendingTask task : snapshot.pending()) {
                pending.add(new ViewPending(task.taskId(), Long.toString(task.dueTimeMillis()),
                        task.phase().name(), task.kind()));
            }
            List<ViewTrade> trades = new ArrayList<>();
            if (snapshot.exchange().isPresent()) {
                for (WorldExchange.Trade trade : snapshot.exchange().get().trades()) {
                    trades.add(new ViewTrade(Long.toString(trade.tradeId()), trade.symbol(),
                            trade.price().toPlainString(), Long.toString(trade.quantity()),
                            Long.toString(trade.timeMillis()), trade.buyAccountId(),
                            trade.sellAccountId()));
                }
            }
            List<ViewDividendReport> reports = new ArrayList<>();
            boolean dividendsOn = snapshot.dividends().isPresent();
            if (dividendsOn) {
                for (WorldRuntime.DividendReport report : current.dividendReports()) {
                    reports.add(new ViewDividendReport(report.taskId(), report.kind(),
                            report.schemeId(), Long.toString(report.position()),
                            Long.toString(report.atMillis()), report.accepted(), report.reason()));
                }
            }
            boolean valuationsAvailable = dividendsOn;
            List<ViewOwner> owners = new ArrayList<>();
            if (dividendsOn) {
                java.util.TreeSet<String> ownerIds = new java.util.TreeSet<>();
                for (ViewAccount account : accounts) {
                    ownerIds.add(account.ownerId());
                }
                for (String ownerId : ownerIds) {
                    ValuationModel.Value value = current.valuation(ownerId);
                    owners.add(new ViewOwner(ownerId, value.status().name(),
                            value.netWorth() == null ? null : value.netWorth().toPlainString(),
                            value.cash().toPlainString(),
                            value.holdings().toPlainString()));
                }
            }
            String providerKind = "UNAVAILABLE";
            if (snapshot.decisions().isPresent()) {
                DecisionRun run = snapshot.decisions().get();
                providerKind = run.state().config().providerKind();
            }
            boolean paused = current.isPaused();
            return new AdminView(runId, Long.toString(snapshot.position()),
                    Long.toString(snapshot.timeMillis()), snapshot.lastPhase(), paused,
                    paused ? "PAUSED" : "READY", providerKind, accounts, pending, trades,
                    reports, owners, valuationsAvailable,
                    snapshot.decisions().isPresent() && current.pendingBarrier().isPresent(), pending.size(),
                    snapshot.delivered().size());
        });
    }

    /**
     * 主体估值（同一 writer 任务内取，避免跨快照）。
     *
     * @param ownerId 财产主体
     * @return 估值 DTO
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public OwnerValue valuation(String ownerId) throws BusyException, Exception {
        return call(() -> {
            ValuationModel.Value value = readyNow().valuation(ownerId);
            return new OwnerValue(ownerId, value.position(), value.atMillis(),
                    value.status().name(), value.netWorth(), value.cash(), value.holdings());
        });
    }

    /**
     * 以期望位置为前提推进世界；位置不符立即拒绝，避免同请求重复推进。
     *
     * @param expectedPosition 调用方看到的位置
     * @param targetMillis     目标时点
     * @return 推进后的位置
     * @throws BusyException      队列满
     * @throws PositionMismatch   期望位置与当前不一致
     * @throws Exception          推进失败
     */
    public long advance(long expectedPosition, long targetMillis)
            throws BusyException, PositionMismatch, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            long position = current.snapshot().position();
            if (position != expectedPosition) {
                throw new PositionMismatch(position);
            }
            current.advanceTo(targetMillis);
            return current.snapshot().position();
        });
    }

    /** 位置不匹配（携带当前真实位置）。 */
    public static final class PositionMismatch extends Exception {
        private final long position;

        PositionMismatch(long position) {
            super("POSITION_MISMATCH");
            this.position = position;
        }

        /** @return 当前真实位置 */
        public long position() {
            return position;
        }
    }

    /**
     * 关闭：在同一把锁内标记关闭并投递结束标记，然后在锁外等待 writer 退出。
     *
     * <p>若由 writer 线程自身调用（任务内部关闭宿主），只标记与投递，不自 join。</p>
     */
    @Override
    public void close() {
        boolean self = Thread.currentThread() == writer;
        boolean interrupted = false;
        synchronized (gate) {
            if (!closed) {
                closed = true;
                boolean queued = queue.offer(POISON);
                if (!queued) {
                    // 队列满：失败所有等待者（它们不可能再被执行），再投递结束标记
                    failPending(new IllegalStateException("宿主已关闭"));
                    queue.offer(POISON);
                }
            }
        }
        if (self) {
            return;
        }
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (writer.isAlive() && System.nanoTime() < deadline) {
                try {
                    writer.join(100L);
                } catch (InterruptedException stop) {
                    interrupted = true;
                }
            }
            if (writer.isAlive()) {
                writer.interrupt();
                writer.join(1_000L);
            }
        } catch (InterruptedException stop) {
            interrupted = true;
            writer.interrupt();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private WorldRuntime readyNow() {
        return runtime == null ? ready.join() : runtime;
    }

    private void loop() {
        WorldRuntime created = null;
        try {
            Opened opened = supplier.open();
            created = opened.runtime();
            // 展示用 runId 一律取实际打开的 manifest，不额外接受第二份参数
            runId = opened.runId();
            runtime = created;
            ready.complete(created);
            while (true) {
                Task<?> task = queue.take();
                if (task == POISON) {
                    break;
                }
                complete(task);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ready.completeExceptionally(new IllegalStateException("writer 被中断"));
            fatalDrain(new IllegalStateException("writer 被中断"));
        } catch (Throwable fatal) {
            ready.completeExceptionally(fatal);
            fatalDrain(fatal);
        } finally {
            fatalDrain(new IllegalStateException("宿主已关闭"));
            if (created != null) {
                try {
                    created.close();
                } catch (RuntimeException ignored) {
                    // 关闭失败不掩盖原始退出原因
                }
            }
        }
    }

    /** 同一把锁内标记关闭并失败所有等待者（工厂失败、writer 中断、退出兜底共用）。 */
    private void fatalDrain(Throwable fatal) {
        synchronized (gate) {
            closed = true;
            failPending(fatal);
        }
    }

    private <T> void complete(Task<T> task) {
        try {
            task.future().complete(task.action().call());
        } catch (Throwable failure) {
            task.future().completeExceptionally(failure);
        }
    }

    private void failPending(Throwable fatal) {
        Task<?> pending;
        while ((pending = queue.poll()) != null) {
            if (pending != POISON) {
                pending.future().completeExceptionally(fatal);
            }
        }
    }
}
