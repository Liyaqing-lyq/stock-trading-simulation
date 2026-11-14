package edu.cufe.auction.web;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.BeliefModel;
import edu.cufe.auction.world.BoardRun;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.SecuritiesLedger;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.DividendModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.EngineeringModel;
import edu.cufe.auction.world.EngineeringRun;
import edu.cufe.auction.world.EvidenceRun;
import edu.cufe.auction.world.GroupModel;
import edu.cufe.auction.world.GroupsRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.PlatformPostModel;
import edu.cufe.auction.world.PublicationRun;
import edu.cufe.auction.world.MediaEvidence;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.InterviewDialogue;
import edu.cufe.auction.world.InterviewModel;
import edu.cufe.auction.world.InterviewRun;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.MediaModel;
import edu.cufe.auction.world.MediaRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.PerformanceModel;
import edu.cufe.auction.world.PeriodicActionPayload;
import edu.cufe.auction.world.PeriodicModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RoleTradingView;
import edu.cufe.auction.world.RoleView;
import edu.cufe.auction.world.SocialMessageModel;
import edu.cufe.auction.world.SocialRun;
import edu.cufe.auction.world.ValuationModel;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.WorldScheduler;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
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

    /** 场景路由；只有 live Host 组合才非空。 */
    private final SceneRoutes routes;
    /** 提供方适配器；只有 live Host 组合才非空。 */
    private final DecisionProvider provider;
    /** 有界 worker 池；非 live 组合为 null，不创建任何线程。 */
    private final ExecutorService workers;
    /** 物理在途：只在 worker 真正持有该次调用时占位；value 为场景标识，用于同场景物理并发。 */
    private final Map<LiveCompletionInbox.AttemptKey, String> physicalInFlight =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 当前轮次（writer 私有状态）。 */
    private LiveRound liveRound;
    /** Host 级暂停标记；与 Runtime 自身的故障暂停分开。 */
    private boolean livePaused;
    /** Host 级持久故障：只由 IO 失败置位，resume 不能清除。 */
    private String liveFault;
    /** 本进程是否已显式接续过决定会话。 */
    private boolean sessionBegun;
    /** 上一轮结束时的跳过原因（有界，保留给状态页）。 */
    private List<SkipReason> lastSkips = List.of();
    /** 最近一次显式开始的轮次摘要（本轮场景/人员/公司）；未开始时为空。 */
    private String lastRoundScene;
    private List<String> lastRoundPersons = List.of();
    private String lastRoundCompany;

    /**
     * 启动 writer 线程并在其中创建运行。
     *
     * <p>运行标识只有一处来源：工厂返回的 {@link Opened#runId()}（即真实 manifest 的 runId）。</p>
     *
     * @param supplier 可信工厂
     */
    public WorldWebHost(IOSupplier supplier) {
        this(supplier, null, null);
    }

    /**
     * live 生产构造：只有「场景路由 + 提供方 + live Runtime」三者同时成立才创建执行器；
     * 构造本身不接续会话、不发网络请求。
     *
     * @param supplier 可信工厂（返回 live schema10 Runtime）
     * @param routes   本 Host 独占的场景路由存储；非 live 传 null
     * @param provider 同步提供方适配器；非 live 传 null
     */
    public WorldWebHost(IOSupplier supplier, SceneRoutes routes, LiveDecisionProvider provider) {
        this(supplier, routes, (DecisionProvider) provider);
    }

    /**
     * 决定提供方 seam：生产用 {@link LiveDecisionProvider}；进程内测试可注入脚本实现，
     * 由真实 {@link DecisionRun.Call} 输入文本分派，不联网。
     */
    public interface DecisionProvider {
        /**
         * 计算一次冻结调用。
         *
         * @param call          冻结调用（含真实 inputText 与路由计划）
         * @param timeoutMillis 墙钟上限
         * @return 完成回执
         */
        LiveCompletionInbox.Completion evaluate(DecisionRun.Call call, long timeoutMillis);
    }

    /**
     * 提供方无关构造：测试注入 {@link DecisionProvider}；其余行为与生产构造一致。
     *
     * @param supplier 可信工厂
     * @param routes   场景路由
     * @param provider 提供方（可为脚本实现）
     */
    public WorldWebHost(IOSupplier supplier, SceneRoutes routes, DecisionProvider provider) {
        if (supplier == null) {
            throw new IllegalArgumentException("宿主参数不合法");
        }
        if ((routes == null) != (provider == null)) {
            throw new IllegalArgumentException("场景路由与提供方必须同时提供");
        }
        this.supplier = supplier;
        this.routes = routes;
        this.provider = provider;
        this.workers = provider == null ? null : new ThreadPoolExecutor(2, 2, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "world-web-live-worker");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
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

    /** 个人页面在同一截止取角色投影与可选采编数据；旧档案media为空。 */
    public record PersonPage(RoleView view, Map<String, Object> media) { }

    /**
     * 读取指定已验证人物的个人页面；正文仅取实际可回忆经历，不查管理端原件。
     * @param personId 会话绑定的真实人物
     * @return 同一截止、服务器裁剪的个人内容
     * @throws Exception 人物无效或世界读取失败
     */
    public PersonPage personPage(String personId) throws Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            var snapshot = current.snapshot();
            if (snapshot.media().isEmpty()) return new PersonPage(current.roleView(personId), null);
            RoleTradingView trading = current.openRoleSession(personId).view();
            RoleView view = trading.profile();
            List<Map<String, Object>> stories = new ArrayList<>();
            Set<String> visibleStories = new LinkedHashSet<>();
            for (RoleTradingView.MediaStory story : trading.media()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("storyId", story.storyId()); row.put("template", story.template());
                row.put("status", story.status()); row.put("revisionNo", num(story.revisionNo()));
                row.put("topic", story.topic()); row.put("authorPartyId", story.authorPartyId());
                row.put("own", story.own()); row.put("canReview", story.canReview());
                row.put("canPublish", story.canPublish()); row.put("sourceGroups", num(story.sourceGroups()));
                row.put("updatedAtMillis", num(story.updatedAtMillis())); stories.add(row);
                visibleStories.add(story.storyId());
            }
            List<Map<String, Object>> reports = new ArrayList<>();
            for (MediaRun.Report report : snapshot.media().orElseThrow().reports().values()) {
                if (!report.personPartyId().equals(personId)
                        || (!report.storyId().isEmpty() && !visibleStories.contains(report.storyId()))) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", report.kind()); row.put("accepted", report.accepted());
                row.put("reason", report.reason()); row.put("storyId", report.storyId());
                row.put("timeMillis", num(report.timeMillis())); reports.add(row);
            }
            reports.sort(Comparator.comparingLong((Map<String, Object> row) ->
                    parseLong(row.get("timeMillis"))).reversed());
            List<Map<String, Object>> documents = new ArrayList<>();
            for (RoleView.Experience experience : view.observations()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("docId", experience.ref().docId()); row.put("version", num(experience.ref().version()));
                row.put("index", num(experience.ref().index())); row.put("title", experience.title());
                row.put("body", experience.body()); row.put("sourceDescription", experience.publicSourceDescription());
                row.put("publishedAtMillis", num(experience.publishedAt()));
                row.put("completedAtMillis", num(experience.completedAt())); documents.add(row);
            }
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("timeMillis", num(view.timeMillis()));
            media.put("stories", bounded(stories)); media.put("reports", bounded(reports));
            media.put("documents", bounded(documents));
            media.put("tools", trading.tools());
            return new PersonPage(view, java.util.Collections.unmodifiableMap(media));
        });
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
     * 历史索引行：位置与条数为精确字符串，命令与回报为当时的原文。
     *
     * @param position   确认位置
     * @param commandId  当时的命令标识
     * @param result     当时的回报原文
     * @param eventCount 该批次事件条数
     */
    public record HistoryRow(String position, String commandId, String result, String eventCount) {
    }

    /**
     * 历史索引页：只列记录元数据，不展开正文，也不改世界。
     *
     * @param runId       运行标识
     * @param cutPosition 读取时的确认截止位置
     * @param entries     本页索引行（按位置升序）
     * @param nextAfter   下一页起点；本页为空时为 -1
     */
    public record HistoryIndexView(String runId, String cutPosition, List<HistoryRow> entries,
                                   String nextAfter) {

        /** 冻结索引行。 */
        public HistoryIndexView {
            entries = List.copyOf(entries);
        }
    }

    /**
     * 固定位置的历史只读视图：全部分区来自同一份已解码快照。
     *
     * @param runId        运行标识
     * @param position     所选确认位置
     * @param cutPosition  读取时的确认截止位置
     * @param timeMillis   该位置的模拟时点
     * @param lastPhase    该位置最后完成的阶段
     * @param historical   所选位置是否早于当前确认位置
     * @param overview     运行总览
     * @param accounts     现金账户
     * @param securities   证券批次与卖出预留
     * @param pending      该位置的待办
     * @param roles        任职
     * @param information  资料索引（只给元数据，不给正文）
     * @param engineering  按公司的工程经营
     * @param groups       群组与平台事实
     * @param unsupported  本位置没有的模块（明确列出，不显示当前值）
     * @param notes        口径说明
     */
    public record HistoryView(String runId, String position, String cutPosition, String timeMillis,
                              String lastPhase, boolean historical, Section overview,
                              Section accounts, Section securities, Section pending,
                              Section roles, Section information, Section engineering,
                              Section groups, List<String> unsupported, List<String> notes) {

        /** 冻结无权限/缺失说明。 */
        public HistoryView {
            unsupported = List.copyOf(unsupported);
            notes = List.copyOf(notes);
        }
    }

    /**
     * 历史索引（管理端只读、单 writer 任务）。
     *
     * @param afterPosition 只返回位置严格大于此值的批次
     * @param limit         单页条数，1..{@link WorldRuntime#MAX_HISTORY_PAGE}
     * @return 索引页
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public HistoryIndexView historyIndex(long afterPosition, int limit)
            throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            long cut = current.snapshot().position();
            List<HistoryRow> rows = new ArrayList<>();
            long next = -1L;
            for (WorldRuntime.HistoryEntry entry : current.historyIndex(afterPosition, limit)) {
                rows.add(new HistoryRow(Long.toString(entry.position()), entry.commandId(),
                        entry.result(), Integer.toString(entry.eventCount())));
                next = entry.position();
            }
            return new HistoryIndexView(runId, Long.toString(cut), rows,
                    next < 0L ? "-1" : Long.toString(next));
        });
    }

    /**
     * 固定确认位置的历史只读视图。
     *
     * <p>只解码该批次自带的快照；不调用估值、回忆、表现区间等实时 accessor，
     * 因此不会把当前值冒充历史值，也不产生阅读/回忆/预算消耗。</p>
     *
     * @param position 确认位置
     * @return 历史视图
     * @throws BusyException 队列满
     * @throws Exception     位置未确认或解码失败
     */
    public HistoryView historyView(long position) throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            long cut = current.snapshot().position();
            WorldRuntime.HistoryFrame frame = current.historyFrame(position);
            WorldRuntime.Snapshot snapshot = frame.snapshot();

            List<String> unsupported = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            notes.add("本页只读取位置 " + position + " 的已确认快照；不推进世界、不写日志、不调用模型。");
            notes.add("金额、数量、位置、时点均为精确字符串；净资产、表现指数、回忆正文等实时派生项本页不提供。");

            List<Map<String, Object>> overviewRows = new ArrayList<>();
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("runId", runId);
            head.put("position", num(snapshot.position()));
            head.put("cutPosition", num(cut));
            head.put("timeMillis", num(snapshot.timeMillis()));
            head.put("lastPhase", snapshot.lastPhase());
            head.put("accounts", Integer.toString(snapshot.accounts().size()));
            BigDecimal totalCash = BigDecimal.ZERO;
            for (CashLedger.CashAccount account : snapshot.accounts().values()) {
                totalCash = totalCash.add(account.cash());
            }
            head.put("totalCash", dec(totalCash));
            head.put("pending", Integer.toString(snapshot.pending().size()));
            head.put("delivered", Integer.toString(snapshot.delivered().size()));
            head.put("usedTaskIds", Integer.toString(snapshot.usedTaskIds().size()));
            List<String> modules = new ArrayList<>();
            if (snapshot.securities().isPresent()) {
                modules.add("securities");
            }
            if (snapshot.exchange().isPresent()) {
                modules.add("matching");
            }
            if (snapshot.manufacturing().isPresent()) {
                modules.add("manufacturing");
            }
            if (snapshot.governance().isPresent()) {
                modules.add("governance");
            }
            if (snapshot.roles().isPresent()) {
                modules.add("roles");
            }
            if (snapshot.information().isPresent()) {
                modules.add("information");
            }
            if (snapshot.engineering().isPresent()) {
                modules.add("engineering");
            }
            if (frame.groups().isPresent()) {
                modules.add("groups");
            }
            head.put("modules", String.join(",", modules));
            overviewRows.add(head);
            if (snapshot.securities().isEmpty()) {
                unsupported.add("本运行未启用证券子账，证券分区不显示。");
            }
            if (snapshot.roles().isEmpty()) {
                unsupported.add("本运行未启用任职授权，任职分区不显示。");
            }
            if (snapshot.information().isEmpty()) {
                unsupported.add("本运行未启用资料阅读，资料分区不显示。");
            }
            if (snapshot.engineering().isEmpty()) {
                unsupported.add("本运行未启用按公司工程经营，工程分区不显示。");
            }
            if (frame.groups().isEmpty()) {
                unsupported.add("本运行未启用群组与平台，群组分区不显示。");
            }

            List<Map<String, Object>> accountRows = new ArrayList<>();
            for (Map.Entry<String, CashLedger.CashAccount> entry : snapshot.accounts().entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("accountId", entry.getKey());
                row.put("ownerId", entry.getValue().ownerId());
                row.put("cash", dec(entry.getValue().cash()));
                row.put("source", entry.getValue().source());
                accountRows.add(row);
            }

            List<Map<String, Object>> securitiesRows = new ArrayList<>();
            snapshot.securities().ifPresent(state -> {
                for (SecuritiesLedger.Lot lot : state.lots()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("kind", "LOT");
                    row.put("lotId", lot.lotId());
                    row.put("accountId", lot.accountId());
                    row.put("symbol", lot.symbol());
                    row.put("remaining", num(lot.remaining()));
                    row.put("costPrice", dec(lot.costPrice()));
                    row.put("eligibleFromDay", num(lot.eligibleFromDay()));
                    row.put("acquiredPosition", num(lot.acquiredPosition()));
                    securitiesRows.add(row);
                }
                for (SecuritiesLedger.SellReservation reservation : state.reservations()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("kind", "RESERVATION");
                    row.put("lotId", reservation.reservationId());
                    row.put("accountId", reservation.accountId());
                    row.put("symbol", reservation.symbol());
                    row.put("remaining", num(reservation.allocations().stream()
                            .mapToLong(SecuritiesLedger.LotQuantity::quantity).sum()));
                    securitiesRows.add(row);
                }
            });

            List<Map<String, Object>> pendingRows = new ArrayList<>();
            for (PendingTask task : snapshot.pending()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskId", task.taskId());
                row.put("dueTimeMillis", num(task.dueTimeMillis()));
                row.put("phase", task.phase().name());
                row.put("kind", task.kind());
                pendingRows.add(row);
            }

            List<Map<String, Object>> roleRows = new ArrayList<>();
            snapshot.roles().ifPresent(run -> {
                for (RoleAccess.Appointment appointment : run.access().appointments()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("appointmentId", appointment.id());
                    row.put("personPartyId", appointment.personPartyId());
                    row.put("organizationPartyId", appointment.organizationPartyId());
                    row.put("role", appointment.role().name());
                    row.put("startsAt", num(appointment.startsAt()));
                    row.put("endsAt", num(appointment.endsAt()));
                    row.put("revokedAt", appointment.revokedAt() == null
                            ? "" : num(appointment.revokedAt()));
                    roleRows.add(row);
                }
            });

            List<Map<String, Object>> informationRows = new ArrayList<>();
            snapshot.information().ifPresent(run -> {
                for (InformationModel.Document document : run.information().documents()) {
                    InformationModel.DocumentVersion latest =
                            document.versions().get(document.versions().size() - 1);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("docId", document.docId());
                    row.put("publisherPartyId", document.publisherPartyId());
                    row.put("audience", document.audience().kind().name());
                    row.put("audiencePartyId", document.audience().partyId() == null
                            ? "" : document.audience().partyId());
                    row.put("version", num(latest.version()));
                    row.put("title", latest.title());
                    row.put("publishedAtMillis", num(latest.publishedAt()));
                    row.put("readDurationMillis", num(latest.readDurationMillis()));
                    informationRows.add(row);
                }
            });

            List<Map<String, Object>> engineeringRows = new ArrayList<>();
            snapshot.engineering().ifPresent(run -> {
                for (Map.Entry<String, EngineeringModel.State> entry : run.companies().entrySet()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("companyPartyId", entry.getKey());
                    row.put("contracts", Integer.toString(entry.getValue().contracts().size()));
                    row.put("workOrders", Integer.toString(entry.getValue().workOrders().size()));
                    row.put("claims", Integer.toString(entry.getValue().claims().size()));
                    row.put("payable", dec(entry.getValue().payable()));
                    row.put("hasBoard", Boolean.toString(run.boards().containsKey(entry.getKey())));
                    engineeringRows.add(row);
                }
            });

            List<Map<String, Object>> groupRows = new ArrayList<>();
            frame.groups().ifPresent(run -> {
                GroupModel.State state = run.state();
                for (GroupModel.Group group : state.groups()) {
                    long openMembers = state.memberships().stream()
                            .filter(membership -> membership.groupId().equals(group.groupId()))
                            .filter(GroupModel.Membership::open).count();
                    long posts = state.posts().stream()
                            .filter(post -> post.groupId().equals(group.groupId())).count();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("groupId", group.groupId());
                    row.put("ownerPartyId", group.ownerPartyId());
                    row.put("joinPolicy", group.joinPolicy().name());
                    row.put("historyPolicy", group.historyPolicy().name());
                    row.put("createdAt", num(group.createdAt()));
                    row.put("openMembers", Long.toString(openMembers));
                    row.put("posts", Long.toString(posts));
                    groupRows.add(row);
                }
            });

            return new HistoryView(runId, num(snapshot.position()), num(cut),
                    num(snapshot.timeMillis()), snapshot.lastPhase(), snapshot.position() < cut,
                    bounded(overviewRows), bounded(accountRows), bounded(securitiesRows),
                    bounded(pendingRows), bounded(roleRows), bounded(informationRows),
                    bounded(engineeringRows), bounded(groupRows), unsupported, notes);
        });
    }

    /**
     * 导出所选确认位置的检查点信封（管理端只读）。
     *
     * @param position 确认位置
     * @return 信封
     * @throws BusyException 队列满
     * @throws Exception     位置未确认或序列化失败
     */
    public WorldRuntime.Checkpoint checkpoint(long position) throws BusyException, Exception {
        return call(() -> readyNow().checkpoint(position));
    }

    /**
     * 核验检查点信封：只对照本运行同一确认位置的原始批次。
     *
     * @param envelopeJson 信封 JSON 原文
     * @return 核验结论
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public WorldRuntime.CheckpointVerdict verifyCheckpoint(String envelopeJson)
            throws BusyException, Exception {
        return call(() -> readyNow().verifyCheckpoint(envelopeJson));
    }

    /**
     * 市场历史柱（金额/数量/价格全部为精确字符串）。
     *
     * @param startMillis   桶起点
     * @param endMillis     桶终点；溢出时为 null
     * @param complete      是否在截止前结束
     * @param firstTradeId  首笔成交号
     * @param lastTradeId   末笔成交号
     * @param count         成交笔数
     * @param open          开盘价
     * @param high          最高价
     * @param low           最低价
     * @param close         收盘价
     * @param volume        成交量；溢出为 null
     * @param volumeStatus  OK / OVERFLOW
     * @param amount        成交额
     */
    public record MarketBarView(String startMillis, String endMillis, boolean complete,
                                String firstTradeId, String lastTradeId, String count,
                                String open, String high, String low, String close,
                                String volume, String volumeStatus, String amount) {
    }

    /**
     * 相邻保留柱之间的空档。
     *
     * @param fromMillis  前一根柱的 endExclusive
     * @param toMillis    后一根柱的 start
     * @param missingBars 缺失桶数
     */
    public record MarketGapView(String fromMillis, String toMillis, String missingBars) {
    }

    /**
     * 真实成交 K 线响应；同一快照的截止位置与时点。
     *
     * @param runId          运行标识
     * @param position       已确认位置
     * @param timeMillis     截止模拟时点
     * @param lastPhase      最后阶段
     * @param available      是否有撮合子集
     * @param marketClosed   撮合是否已收盘
     * @param symbols        真实证券候选
     * @param selectedSymbol 选中证券；无候选为 null
     * @param period         周期
     * @param basis          价格口径
     * @param bars           有成交柱（升序，最多 500）
     * @param gaps           相邻保留柱之间的空档
     * @param truncated      是否裁剪
     * @param totalBars      裁剪前有成交桶数
     */
    public record MarketHistoryView(String runId, String position, String timeMillis,
                                    String lastPhase, boolean available, boolean marketClosed,
                                    List<String> symbols, String selectedSymbol, String period,
                                    String basis, List<MarketBarView> bars,
                                    List<MarketGapView> gaps, boolean truncated,
                                    String totalBars) {
        /** 冻结集合，避免调用方修改响应。 */
        public MarketHistoryView {
            symbols = List.copyOf(symbols);
            bars = List.copyOf(bars);
            gaps = List.copyOf(gaps);
        }
    }

    /**
     * 市场历史（单 writer 任务、单快照；只读，不推进世界）。
     *
     * @param symbol     证券；空字符串表示按真实候选稳定选择首个
     * @param periodText minute/day/week
     * @return K 线响应
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public MarketHistoryView marketHistory(String symbol, String periodText)
            throws BusyException, Exception {
        if (symbol == null) {
            throw new IllegalArgumentException("证券不能为空");
        }
        MarketHistory.Period period = MarketHistory.Period.fromId(periodText);
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            OpeningRegistry registry = current.openingRegistry().orElse(null);
            List<String> symbols = new ArrayList<>();
            if (registry != null) {
                for (OpeningRegistry.Security security : registry.securities()) {
                    if (!symbols.contains(security.symbol())) {
                        symbols.add(security.symbol());
                    }
                }
            }
            String selected;
            if (symbol.isEmpty()) {
                selected = symbols.isEmpty() ? null : symbols.get(0);
            } else if (symbols.contains(symbol)) {
                selected = symbol;
            } else {
                throw new IllegalArgumentException("未知证券");
            }
            boolean available = snapshot.exchange().isPresent();
            boolean marketClosed = snapshot.exchange()
                    .map(WorldExchange.State::closed).orElse(false);
            List<WorldExchange.Trade> trades = snapshot.exchange()
                    .map(WorldExchange.State::trades).orElse(List.of());
            MarketHistory.Result result = selected == null
                    ? new MarketHistory.Result("", period, snapshot.timeMillis(),
                            snapshot.position(), List.of(), List.of(), false, 0L)
                    : MarketHistory.aggregate(trades, selected, period, snapshot.timeMillis(),
                            snapshot.position());
            List<MarketBarView> bars = new ArrayList<>();
            for (MarketHistory.Bar bar : result.bars()) {
                bars.add(new MarketBarView(Long.toString(bar.startMillis()),
                        bar.endMillis() == null ? null : Long.toString(bar.endMillis()),
                        bar.complete(), Long.toString(bar.firstTradeId()),
                        Long.toString(bar.lastTradeId()), Long.toString(bar.count()),
                        bar.open().toPlainString(), bar.high().toPlainString(),
                        bar.low().toPlainString(), bar.close().toPlainString(),
                        bar.volume() == null ? null : Long.toString(bar.volume()),
                        bar.volumeStatus(), bar.amount().toPlainString()));
            }
            List<MarketGapView> gaps = new ArrayList<>();
            for (MarketHistory.Gap gap : result.gaps()) {
                gaps.add(new MarketGapView(Long.toString(gap.fromMillis()),
                        Long.toString(gap.toMillis()), Long.toString(gap.missingBars())));
            }
            return new MarketHistoryView(runId, Long.toString(snapshot.position()),
                    Long.toString(snapshot.timeMillis()), snapshot.lastPhase(), available,
                    marketClosed, List.copyOf(symbols), selected, period.id(),
                    "UNADJUSTED_EXPERIMENT", bars, gaps, result.truncated(),
                    Long.toString(result.totalBars()));
        });
    }

    // ================================================================ 管理详情页（只读）

    /** 有界列表：真实总量 + 本次返回的前若干行。 */
    public record Section(int total, List<Map<String, Object>> rows) {
        /** 冻结行集合。 */
        public Section {
            rows = List.copyOf(rows);
        }
    }

    /**
     * 公司经营与治理视图（管理端只读，单 writer 任务内取同一确认时点）。
     *
     * @param runId                 运行标识
     * @param position              已确认位置（字符串）
     * @param timeMillis            世界时点（字符串）
     * @param manufacturingAvailable 是否启用制造子集
     * @param symbol                 制造档案绑定的证券代码；未启用为 null
     * @param companyPartyId         公司组织 partyId；无法判定为 null
     * @param companyName            公司展示名；登记表缺失为 null
     * @param operatingReport        经营报表（金额均为精确字符串）
     * @param reportError            报表派生失败原因；成功为 null
     * @param inventories            原材料/在制/产成品
     * @param purchaseOrders         采购单（新到旧）
     * @param productionBatches      生产批次（新到旧）
     * @param contracts              客户合同（新到旧）
     * @param receivables            应收（新到旧）
     * @param expenseBills           期间费用账单（新到旧）
     * @param governanceAvailable    是否启用董事会子集
     * @param charter                章程与席位
     * @param meetings               会议（新到旧）
     * @param budgets                已生效预算上限
     * @param commitments            预算承诺
     * @param reviews                考核结果
     * @param periodEvidence         期间截止证据
     * @param managementReports      制造与治理的管理审计报告（新到旧）
     * @param rules                  本档案实际使用的实验规则常量
     * @param notes                  明确标注的未实现项
     */
    public record CompanyView(String runId, String position, String timeMillis,
                              boolean manufacturingAvailable, String symbol, String companyPartyId,
                              String companyName, Map<String, Object> operatingReport,
                              String reportError, Section inventories, Section purchaseOrders,
                              Section productionBatches, Section contracts, Section receivables,
                              Section expenseBills, boolean governanceAvailable,
                              Map<String, Object> charter, Section meetings, Section budgets,
                              Section commitments, Section reviews, Section periodEvidence,
                              Section managementReports, List<Map<String, Object>> rules,
                              List<String> notes) {
    }

    /**
     * 人物与任职视图（管理端只读）。
     *
     * @param runId            运行标识
     * @param position         已确认位置（字符串）
     * @param timeMillis       世界时点（字符串）
     * @param parties          登记人物/组织
     * @param owners           财产主体
     * @param appointments     任职（含撤销与有效期判定）
     * @param mandates         产品管理授权
     * @param managementReports 任职管理审计报告
     * @param notes            口径说明
     */
    public record PeopleView(String runId, String position, String timeMillis, Section parties,
                             Section owners, Section appointments, Section mandates,
                             Section managementReports, List<String> notes) {
    }

    /**
     * 信息与阅读视图（管理端只读）。
     *
     * @param runId            运行标识
     * @param position         已确认位置（字符串）
     * @param timeMillis       世界时点（字符串）
     * @param documents        资料与版本（新到旧）
     * @param activeReadings   进行中的阅读
     * @param closedReadings   已终结的阅读
     * @param observations     实际完成阅读产生的经历
     * @param readers          已登记读者
     * @param managementReports 阅读管理审计报告
     * @param notes            口径说明
     */
    public record InformationView(String runId, String position, String timeMillis,
                                  Section documents, Section activeReadings,
                                  Section closedReadings, Section observations, Section readers,
                                  Section managementReports, List<String> notes) {
    }

    /**
     * 账户表现与历史视图（管理端只读，表现只取 {@link PerformanceModel} 口径）。
     *
     * @param runId                运行标识
     * @param position             已确认位置（字符串）
     * @param timeMillis           世界时点（字符串）
     * @param performanceAvailable 本页是否计算期间表现（确认帧过长时不计算）
     * @param accounts             现金账户
     * @param owners               主体估值、期间表现与资本流条数
     * @param holdings             估值明细行
     * @param flows                真实资本流（新到旧）
     * @param notes                口径说明
     */
    public record PerformanceView(String runId, String position, String timeMillis,
                                  boolean performanceAvailable, Section accounts, Section owners,
                                  Section holdings, Section flows, List<String> notes) {
    }

    /**
     * 一个点的曲线行；所有数值为精确字符串，前端只做坐标缩放。
     *
     * @param position        确认位置
     * @param timeMillis      该确认帧的模拟时点
     * @param valuationStatus 该点估值状态
     * @param navBeforeFlow   资本流发生前净资产；不可用为空
     * @param navAfterFlow    资本流发生后净资产（帧末规模）；不可用为空
     * @param index           该点的表现指数；不可用为空
     * @param drawdown        该点的当前回撤；不可用为空
     * @param flows           该点登记的真实资本流
     */
    public record PerformanceCurvePointView(String position, String timeMillis,
                                            String valuationStatus,
                                            String navBeforeFlow, String navAfterFlow,
                                            String index, String drawdown,
                                            List<PerformanceFlowView> flows) {
        /** 冻结集合。 */
        public PerformanceCurvePointView {
            flows = List.copyOf(flows);
        }
    }

    /**
     * 一个点内登记的真实资本流（方向沿用 PerformanceModel 的种类）。
     *
     * @param flowId      资本流标识
     * @param kind        CAPITAL_CONTRIBUTION / CAPITAL_WITHDRAWAL
     * @param amount      金额（字符串）
     * @param position    该点确认位置
     * @param atMillis    该点模拟时点
     * @param evidenceRef 证据引用
     */
    public record PerformanceFlowView(String flowId, String kind, String amount,
                                      String position, String atMillis, String evidenceRef) {
    }

    /**
     * 期间汇总投影（与 {@link PerformanceModel.Performance} 同值，字段保持字符串）。
     *
     * @param periodId     表现期间
     * @param status       期间状态
     * @param returnRate   期间收益率；不可用为空
     * @param index        期间指数；不可用为空
     * @param maxDrawdown  期间最大回撤；不可用为空
     * @param segmentCount 相邻区间数
     */
    public record PerformancePeriodView(String periodId, String status, String returnRate,
                                        String index, String maxDrawdown, String segmentCount) {
    }

    /**
     * 账户规模、表现指数与回撤历史响应。
     *
     * <p>{@code position}/{@code timeMillis} 是当前实时截止，{@code startPosition}/
     * {@code endPosition}/{@code startTimeMillis}/{@code endTimeMillis} 是所选区间；
     * 两者分开，绝不把区间终点冒充实时世界时间。</p>
     *
     * @param runId           当前运行
     * @param position        当前实时截止确认位置
     * @param timeMillis      当前实时截止模拟时点
     * @param ownerId         所选财产主体
     * @param ownerName       主体名称
     * @param startPosition   所选区间实际起点
     * @param endPosition     所选区间终点
     * @param startTimeMillis 起点确认帧模拟时点
     * @param endTimeMillis   终点确认帧模拟时点
     * @param totalPoints     真实总点数
     * @param framesLimit     本次允许的最大帧数
     * @param performance     期间汇总
     * @param points          逐点曲线
     * @param flows           区间内真实资本流（新→旧）
     * @param notes           口径说明
     */
    public record PerformanceHistoryView(String runId, String position, String timeMillis,
                                         String ownerId, String ownerName,
                                         String startPosition, String endPosition,
                                         String startTimeMillis, String endTimeMillis,
                                         String totalPoints, String framesLimit,
                                         PerformancePeriodView performance,
                                         List<PerformanceCurvePointView> points,
                                         Section flows, List<String> notes) {
        /** 冻结集合。 */
        public PerformanceHistoryView {
            points = List.copyOf(points);
            notes = List.copyOf(notes);
        }
    }

    /** 单页最多返回的行数；总量另行给出，不截断计数。 */
    public static final int MAX_ROWS = 50;

    /** 本页允许计算期间表现的最大确认帧数；超过时不计算，避免长日志拖死只读请求。 */
    public static final long MAX_PERFORMANCE_FRAMES = 400;

    /**
     * 公司经营与治理（单 writer 任务、单快照）。
     *
     * @return 视图
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public CompanyView companyView() throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            OpeningRegistry registry = current.openingRegistry().orElse(null);
            ManufacturingRun manufacturing = snapshot.manufacturing().orElse(null);
            BoardRun governance = snapshot.governance().orElse(null);

            String symbol = manufacturing == null ? null : manufacturing.symbol();
            String companyPartyId = governance != null
                    ? governance.board().charter().companyPartyId()
                    : organizationPartyOf(registry, manufacturing == null ? null
                            : manufacturing.ledger().opening().companyOwnerId());
            String companyName = partyName(registry, companyPartyId);

            Map<String, Object> reportRow = new LinkedHashMap<>();
            String reportError = null;
            if (manufacturing != null) {
                try {
                    ManufacturingModel.Report report = ManufacturingModel.report(
                            manufacturing.ledger(), snapshot.accounts(),
                            dividendPosition(snapshot.dividends().orElse(null)));
                    reportRow.put("companyCash", dec(report.companyCash()));
                    reportRow.put("rawQuantity", num(report.rawQuantity()));
                    reportRow.put("rawAmount", dec(report.rawAmount()));
                    reportRow.put("rawUnitCost", dec(report.rawUnitCost()));
                    reportRow.put("wipQuantity", num(report.wipQuantity()));
                    reportRow.put("wipAmount", dec(report.wipAmount()));
                    reportRow.put("finishedQuantity", num(report.finishedQuantity()));
                    reportRow.put("finishedAmount", dec(report.finishedAmount()));
                    reportRow.put("finishedUnitCost", dec(report.finishedUnitCost()));
                    reportRow.put("receivableOutstanding", dec(report.receivableOutstanding()));
                    reportRow.put("equipmentNet", dec(report.equipmentNet()));
                    reportRow.put("payableOutstanding", dec(report.payableOutstanding()));
                    reportRow.put("totalAssets", dec(report.totalAssets()));
                    reportRow.put("totalLiabilities", dec(report.totalLiabilities()));
                    reportRow.put("initialEquity", dec(report.initialEquity()));
                    reportRow.put("revenue", dec(report.revenue()));
                    reportRow.put("costOfGoodsSold", dec(report.costOfGoodsSold()));
                    reportRow.put("periodExpense", dec(report.periodExpense()));
                    reportRow.put("depreciation", dec(report.depreciation()));
                    reportRow.put("cumulativeProfit", dec(report.cumulativeProfit()));
                    reportRow.put("equity", dec(report.equity()));
                    reportRow.put("worldCash", dec(report.worldCash()));
                    reportRow.put("balanced", report.balanced());
                } catch (RuntimeException invalid) {
                    reportError = invalid.getMessage() == null
                            ? invalid.getClass().getSimpleName() : invalid.getMessage();
                }
            }

            List<Map<String, Object>> inventories = new ArrayList<>();
            if (manufacturing != null) {
                ManufacturingModel.State ledger = manufacturing.ledger();
                inventories.add(inventoryRow("原材料", ledger.rawMaterials()));
                inventories.add(inventoryRow("在制", ledger.workInProgress()));
                inventories.add(inventoryRow("产成品", ledger.finishedGoods()));
            }

            List<ManufacturingModel.PurchaseOrder> orders = new ArrayList<>();
            List<ManufacturingModel.ProductionBatch> batches = new ArrayList<>();
            List<ManufacturingModel.SalesContract> contracts = new ArrayList<>();
            List<ManufacturingModel.Receivable> receivables = new ArrayList<>();
            List<ManufacturingModel.ExpenseBill> bills = new ArrayList<>();
            if (manufacturing != null) {
                ManufacturingModel.State ledger = manufacturing.ledger();
                orders.addAll(ledger.purchaseOrders().values());
                batches.addAll(ledger.productionBatches().values());
                contracts.addAll(ledger.contracts().values());
                receivables.addAll(ledger.receivables().values());
                bills.addAll(ledger.expenseBills().values());
            }
            orders.sort(Comparator.comparingLong(ManufacturingModel.PurchaseOrder::orderedAt)
                    .thenComparing(ManufacturingModel.PurchaseOrder::purchaseOrderId).reversed());
            batches.sort(Comparator.comparingLong(ManufacturingModel.ProductionBatch::startedAt)
                    .thenComparing(ManufacturingModel.ProductionBatch::batchId).reversed());
            contracts.sort(Comparator.comparingLong(ManufacturingModel.SalesContract::signedAt)
                    .thenComparing(ManufacturingModel.SalesContract::contractId).reversed());
            receivables.sort(Comparator.comparingLong(ManufacturingModel.Receivable::recognizedAt)
                    .thenComparing(ManufacturingModel.Receivable::receivableId).reversed());
            bills.sort(Comparator.comparingLong(ManufacturingModel.ExpenseBill::period)
                    .thenComparing(ManufacturingModel.ExpenseBill::billId).reversed());

            List<Map<String, Object>> orderRows = new ArrayList<>();
            for (ManufacturingModel.PurchaseOrder order : orders) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("purchaseOrderId", order.purchaseOrderId());
                row.put("quantity", num(order.quantity()));
                row.put("unitPrice", dec(order.unitPrice()));
                row.put("orderedAt", num(order.orderedAt()));
                row.put("arrivalAt", num(order.arrivalAt()));
                row.put("status", order.status().name());
                orderRows.add(row);
            }
            List<Map<String, Object>> batchRows = new ArrayList<>();
            for (ManufacturingModel.ProductionBatch batch : batches) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("batchId", batch.batchId());
                row.put("quantity", num(batch.quantity()));
                row.put("startedAt", num(batch.startedAt()));
                row.put("completesAt", num(batch.completesAt()));
                row.put("materialCost", dec(batch.materialCost()));
                row.put("laborCost", dec(batch.laborCost()));
                row.put("totalCost", dec(batch.totalCost()));
                row.put("status", batch.status().name());
                batchRows.add(row);
            }
            List<Map<String, Object>> contractRows = new ArrayList<>();
            for (ManufacturingModel.SalesContract contract : contracts) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("contractId", contract.contractId());
                row.put("quantity", num(contract.quantity()));
                row.put("unitPrice", dec(contract.unitPrice()));
                row.put("signedAt", num(contract.signedAt()));
                row.put("promisedDeliveryAt", num(contract.promisedDeliveryAt()));
                row.put("period", num(contract.period()));
                row.put("deliveredQuantity", num(contract.deliveredQuantity()));
                row.put("remainingQuantity", num(contract.remainingQuantity()));
                contractRows.add(row);
            }
            List<Map<String, Object>> receivableRows = new ArrayList<>();
            for (ManufacturingModel.Receivable receivable : receivables) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("receivableId", receivable.receivableId());
                row.put("contractId", receivable.contractId());
                row.put("quantity", num(receivable.quantity()));
                row.put("amount", dec(receivable.amount()));
                row.put("cost", dec(receivable.cost()));
                row.put("outstanding", dec(receivable.outstanding()));
                row.put("recognizedAt", num(receivable.recognizedAt()));
                row.put("dueAt", num(receivable.dueAt()));
                row.put("status", receivable.status().name());
                receivableRows.add(row);
            }
            List<Map<String, Object>> billRows = new ArrayList<>();
            for (ManufacturingModel.ExpenseBill bill : bills) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("billId", bill.billId());
                row.put("period", num(bill.period()));
                row.put("amount", dec(bill.amount()));
                row.put("paid", dec(bill.paid()));
                row.put("closedAt", num(bill.closedAt()));
                billRows.add(row);
            }

            Map<String, Object> charter = new LinkedHashMap<>();
            List<Map<String, Object>> seatRows = new ArrayList<>();
            List<Map<String, Object>> meetingRows = new ArrayList<>();
            List<Map<String, Object>> budgetRows = new ArrayList<>();
            List<Map<String, Object>> commitmentRows = new ArrayList<>();
            List<Map<String, Object>> reviewRows = new ArrayList<>();
            List<Map<String, Object>> evidenceRows = new ArrayList<>();
            if (governance != null) {
                BoardModel.State board = governance.board();
                BoardModel.Charter value = board.charter();
                charter.put("companyPartyId", value.companyPartyId());
                charter.put("companyName", partyName(registry, value.companyPartyId()));
                charter.put("chairSeatId", value.chairSeatId());
                charter.put("quorum", Integer.toString(value.quorum()));
                for (BoardModel.Seat seat : value.seats()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("seatId", seat.seatId());
                    row.put("partyId", seat.partyId());
                    row.put("partyName", partyName(registry, seat.partyId()));
                    row.put("chair", seat.seatId().equals(value.chairSeatId()));
                    seatRows.add(row);
                }
                charter.put("seats", seatRows);

                List<BoardModel.Meeting> meetings = new ArrayList<>(board.meetings().values());
                meetings.sort(Comparator.comparingLong(BoardModel.Meeting::createdAt)
                        .thenComparing(BoardModel.Meeting::meetingId).reversed());
                for (BoardModel.Meeting meeting : meetings) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("meetingId", meeting.meetingId());
                    row.put("topic", meeting.topic().name());
                    row.put("convenerSeatId", meeting.convenerSeatId());
                    row.put("createdAt", num(meeting.createdAt()));
                    row.put("materialDeadline", num(meeting.materialDeadline()));
                    row.put("opensAt", num(meeting.opensAt()));
                    row.put("closesAt", num(meeting.closesAt()));
                    row.put("status", meeting.status().name());
                    row.put("attendees", String.join(",", meeting.attendeeSeatIds()));
                    row.put("recused", String.join(",", meeting.recusedSeatIds()));
                    row.put("materialCount", Integer.toString(meeting.materials().size()));
                    row.put("proposedLines", proposedLines(meeting.proposedLines()));
                    row.put("ballots", ballots(meeting.ballots()));
                    row.put("failureReason", meeting.failureReason());
                    row.put("dividendSchemeId", meeting.dividendProposal() == null
                            ? null : meeting.dividendProposal().request().schemeId());
                    meetingRows.add(row);
                }
                for (BoardModel.Budget budget : board.budgets()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("period", num(budget.period()));
                    row.put("category", budget.category().name());
                    row.put("cap", dec(budget.cap()));
                    budgetRows.add(row);
                }
                for (BoardModel.Commitment commitment : board.commitments()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("commitmentId", commitment.commitmentId());
                    row.put("period", num(commitment.period()));
                    row.put("category", commitment.category().name());
                    row.put("reserved", dec(commitment.reserved()));
                    row.put("spent", dec(commitment.spent()));
                    row.put("released", dec(commitment.released()));
                    row.put("outstanding", dec(commitment.outstanding()));
                    row.put("status", commitment.status().name());
                    commitmentRows.add(row);
                }
                for (BoardModel.ReviewResult review : board.reviews()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("reviewId", review.reviewId());
                    row.put("status", review.status().name());
                    row.put("total", dec(review.total()));
                    List<String> missing = new ArrayList<>();
                    for (BoardModel.Dimension dimension : review.missingDimensions()) {
                        missing.add(dimension.name());
                    }
                    row.put("missingDimensions", String.join(",", missing));
                    row.put("metrics", metrics(review.metrics()));
                    reviewRows.add(row);
                }
                List<BoardRun.PeriodEvidence> evidence =
                        new ArrayList<>(governance.periodEvidence().values());
                evidence.sort(Comparator.comparingLong(BoardRun.PeriodEvidence::period).reversed());
                for (BoardRun.PeriodEvidence period : evidence) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("period", num(period.period()));
                    row.put("capturedAt", num(period.capturedAt()));
                    row.put("position", num(period.position()));
                    row.put("reviewMeetingId", period.reviewMeetingId());
                    row.put("sourceRef", period.sourceRef());
                    row.put("operatingCommitment", dec(period.operatingCommitment()));
                    row.put("cashSolvency", dec(period.cashSolvency()));
                    row.put("risk", dec(period.risk()));
                    row.put("contractIds", String.join(",", period.contractIds()));
                    row.put("receivableIds", String.join(",", period.receivableIds()));
                    evidenceRows.add(row);
                }
            }

            List<Map<String, Object>> reports = new ArrayList<>();
            if (manufacturing != null) {
                for (ManufacturingRun.ReportEntry entry : manufacturing.reports().values()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("source", "MANUFACTURING");
                    row.put("taskId", entry.taskId());
                    row.put("commandId", entry.commandId());
                    row.put("kind", entry.kind());
                    row.put("accepted", entry.accepted());
                    row.put("reason", entry.reason());
                    row.put("subjectId", entry.businessId());
                    row.put("amount", dec(entry.amount()));
                    row.put("timeMillis", num(entry.timeMillis()));
                    row.put("position", num(entry.position()));
                    reports.add(row);
                }
            }
            if (governance != null) {
                for (BoardRun.Report entry : governance.reports().values()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("source", "GOVERNANCE");
                    row.put("taskId", entry.taskId());
                    row.put("commandId", entry.commandId());
                    row.put("kind", entry.kind());
                    row.put("accepted", entry.accepted());
                    row.put("reason", entry.reason());
                    row.put("subjectId", entry.meetingId());
                    row.put("amount", null);
                    row.put("timeMillis", num(entry.timeMillis()));
                    row.put("position", num(entry.position()));
                    reports.add(row);
                }
            }
            sortReportsNewestFirst(reports);

            List<String> notes = new ArrayList<>();
            notes.add("经营报表由制造台账与同一截止的现金账户派生，已宣告分红计入应付；balanced 为账平衡式校验结果。");
            notes.add("采购下单只形成承诺，到货才付款入库；签约不等于收入，交付才确认应收与收入；期间费用与折旧在关期时入账。");
            notes.add("董事会考核权重事前冻结；本档案尚未实现披露质量与长期投入证据，因此考核总分在证据不全时保持空值，不补零。");
            notes.add("公司经营净资产不支持账户估值口径（组织完整经营净资产未接入），公司现金与经营回报请看本页，不要与股价混用。");

            return new CompanyView(runId, Long.toString(snapshot.position()),
                    Long.toString(snapshot.timeMillis()), manufacturing != null, symbol,
                    companyPartyId, companyName, reportRow, reportError,
                    bounded(inventories), bounded(orderRows), bounded(batchRows),
                    bounded(contractRows), bounded(receivableRows), bounded(billRows),
                    governance != null, charter, bounded(meetingRows), bounded(budgetRows),
                    bounded(commitmentRows), bounded(reviewRows), bounded(evidenceRows),
                    bounded(reports), companyRules(), notes);
        });
    }

    /**
     * 人物与任职（单 writer 任务、单快照）。
     *
     * @return 视图
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public PeopleView peopleView() throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            OpeningRegistry registry = current.openingRegistry().orElse(null);
            RoleRun roles = snapshot.roles().orElse(null);
            long now = snapshot.timeMillis();

            List<Map<String, Object>> partyRows = new ArrayList<>();
            if (registry != null) {
                for (OpeningRegistry.Party party : registry.parties()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("partyId", party.partyId());
                    row.put("kind", party.kind().name());
                    row.put("name", party.name());
                    List<String> ownerIds = new ArrayList<>();
                    for (OpeningRegistry.PropertyOwner owner : registry.owners()) {
                        if (party.partyId().equals(owner.partyId())) {
                            ownerIds.add(owner.ownerId());
                        }
                    }
                    row.put("ownerIds", String.join(",", ownerIds));
                    partyRows.add(row);
                }
                partyRows.sort(Comparator.comparing(
                        (Map<String, Object> row) -> String.valueOf(row.get("partyId"))));
            }

            List<Map<String, Object>> ownerRows = new ArrayList<>();
            if (registry != null) {
                for (OpeningRegistry.PropertyOwner owner : registry.owners()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ownerId", owner.ownerId());
                    row.put("kind", owner.kind().name());
                    row.put("partyId", owner.partyId());
                    row.put("partyName", partyName(registry, owner.partyId()));
                    List<String> accountIds = new ArrayList<>();
                    BigDecimal cash = new BigDecimal("0.00");
                    for (Map.Entry<String, edu.cufe.auction.world.CashLedger.CashAccount> entry
                            : snapshot.accounts().entrySet()) {
                        if (entry.getValue().ownerId().equals(owner.ownerId())) {
                            accountIds.add(entry.getKey());
                            cash = cash.add(entry.getValue().cash());
                        }
                    }
                    row.put("accountIds", String.join(",", accountIds));
                    row.put("cash", cash.toPlainString());
                    ownerRows.add(row);
                }
                ownerRows.sort(Comparator.comparing(
                        (Map<String, Object> row) -> String.valueOf(row.get("ownerId"))));
            }

            List<Map<String, Object>> appointmentRows = new ArrayList<>();
            List<Map<String, Object>> mandateRows = new ArrayList<>();
            List<Map<String, Object>> reportRows = new ArrayList<>();
            if (roles != null) {
                RoleAccess.State access = roles.access();
                for (RoleAccess.Appointment appointment : access.appointments()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("appointmentId", appointment.id());
                    row.put("personPartyId", appointment.personPartyId());
                    row.put("personName", partyName(registry, appointment.personPartyId()));
                    row.put("organizationPartyId", appointment.organizationPartyId());
                    row.put("organizationName", partyName(registry,
                            appointment.organizationPartyId()));
                    row.put("role", appointment.role().name());
                    row.put("startsAt", num(appointment.startsAt()));
                    row.put("endsAt", num(appointment.endsAt()));
                    row.put("effectiveEnd", num(appointment.effectiveEnd()));
                    row.put("revokedAt", appointment.revokedAt() == null
                            ? null : num(appointment.revokedAt()));
                    row.put("active", appointment.activeAt(now));
                    row.put("status", appointmentStatus(appointment, now));
                    appointmentRows.add(row);
                }
                appointmentRows.sort(Comparator.comparing(
                        row -> String.valueOf(row.get("appointmentId"))));
                for (RoleAccess.ProductMandate mandate : access.mandates()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("productOwnerId", mandate.productOwnerId());
                    row.put("productName", partyName(registry, organizationPartyOf(registry,
                            mandate.productOwnerId())));
                    row.put("organizationPartyId", mandate.organizationPartyId());
                    row.put("organizationName", partyName(registry,
                            mandate.organizationPartyId()));
                    row.put("accountIds", String.join(",", mandate.accountIds()));
                    mandateRows.add(row);
                }
                for (RoleRun.Report report : roles.reports().values()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("taskId", report.taskId());
                    row.put("commandId", report.commandId());
                    row.put("kind", report.kind());
                    row.put("accepted", report.accepted());
                    row.put("reason", report.reason());
                    row.put("subjectId", report.subjectId());
                    row.put("timeMillis", num(report.timeMillis()));
                    row.put("position", num(report.position()));
                    reportRows.add(row);
                }
                sortReportsNewestFirst(reportRows);
            }

            List<String> notes = new ArrayList<>();
            notes.add("人物、组织与财产主体来自开局登记表；任职来自任职子集的实际记录，撤销只结束授权，不抹除本人经历。");
            notes.add("岗位权限由任职与产品管理授权共同决定，本页只展示记录，不提供任免、授权或代操作入口。");
            notes.add("个人财产主体的现金合计来自账户表；产品主体与聚合主体不是人物，也不等于其管理机构的自有资产。");
            notes.add("产品财产主体在登记表里没有独立名称（名称只挂在人物/组织上），因此产品名称显示为空，不编造名称。");

            return new PeopleView(runId, Long.toString(snapshot.position()),
                    Long.toString(snapshot.timeMillis()), bounded(partyRows), bounded(ownerRows),
                    bounded(appointmentRows), bounded(mandateRows), bounded(reportRows), notes);
        });
    }

    /**
     * 管理员回忆审计；策略、计数及全文只在此可信入口公开，所有长整数编码为字符串。
     *
     * @param personPartyId 已登记人物
     * @return 同一writer截止的回忆选择与实验策略；旧档available为false
     * @throws BusyException 队列满
     * @throws Exception 读取失败或人物无效
     */
    public Map<String, Object> recallView(String personPartyId) throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            var snapshot = current.snapshot();
            var audit = current.recallAudit(personPartyId);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("runId", runId);
            view.put("position", Long.toString(snapshot.position()));
            view.put("timeMillis", Long.toString(snapshot.timeMillis()));
            view.put("personPartyId", personPartyId);
            view.put("available", audit.isPresent());
            if (audit.isEmpty()) return view;
            var policy = audit.orElseThrow().policy();
            var result = audit.orElseThrow().result();
            view.put("policy", Map.of("halfLifeMillis", Long.toString(policy.halfLifeMillis()),
                    "thresholdPermille", policy.thresholdPermille(), "maxItems", policy.maxItems(),
                    "maxContentChars", Long.toString(policy.maxContentChars())));
            view.put("eligibleCount", result.eligibleCount());
            view.put("forgottenCount", result.forgottenCount());
            view.put("budgetTruncated", result.budgetTruncated());
            view.put("contentChars", Long.toString(result.contentChars()));
            List<Map<String, Object>> selected = new ArrayList<>();
            for (var item : result.selected()) {
                var observation = item.observation();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("docId", observation.docId());
                row.put("version", Long.toString(observation.version()));
                row.put("title", observation.title());
                row.put("body", observation.body());
                row.put("publicSourceDescription", observation.publicSourceDescription());
                row.put("completedAt", Long.toString(observation.completedAt()));
                row.put("lastReadId", item.lastReadId());
                row.put("lastReadAt", Long.toString(item.lastReadAt()));
                row.put("score", item.score());
                selected.add(row);
            }
            view.put("selected", List.copyOf(selected));
            return view;
        });
    }

    /**
     * 管理员查询同一确认位置的判断及学习历史；读取不触发学习。
     * @param personPartyId 已登记人物
     * @return 有界审计数据；旧档案的available为false
     * @throws BusyException 查询队列已满
     * @throws Exception 人物无效或世界读取失败
     */
    public Map<String, Object> beliefView(String personPartyId) throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            var snapshot = current.snapshot();
            var audit = current.beliefAudit(personPartyId);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("runId", runId);
            view.put("position", Long.toString(snapshot.position()));
            view.put("timeMillis", Long.toString(snapshot.timeMillis()));
            view.put("personPartyId", personPartyId);
            view.put("available", audit.isPresent());
            if (audit.isPresent()) {
                var learning = audit.orElseThrow();
                view.putAll(beliefDetails(learning.state(),
                        new BeliefModel.Claims(learning.sourceClaims(), learning.factClaims()),
                        learning.policy(), personPartyId, snapshot.timeMillis()));
            }
            return view;
        });
    }

    /** 本人认知审计的有界网页数据；精确整数转字符串，不修改历史。 */
    private static Map<String, Object> beliefDetails(BeliefModel.State learning,
                                                     BeliefModel.Claims claims,
                                                     BeliefModel.Policy policy,
                                                     String person, long now) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policy", Map.of("maxBeliefsPerPerson", Long.toString(policy.maxBeliefsPerPerson()),
                "maxOpenBeliefs", policy.maxOpenBeliefs(), "maxHorizonMillis", Long.toString(policy.maxHorizonMillis()),
                "trustStep", policy.trustStep(), "confidenceWeight", policy.confidenceWeight(),
                "habitThreshold", policy.habitThreshold(), "habitWindowMillis", Long.toString(policy.habitWindowMillis())));
        var projection = BeliefModel.project(learning, policy, person, now, MAX_ROWS);
        Map<String, BeliefModel.Belief> mine = new LinkedHashMap<>();
        for (var belief : learning.beliefs()) {
            if (belief.personPartyId().equals(person)) mine.put(belief.beliefId(), belief);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var card : projection.beliefs()) {
            var belief = mine.get(card.beliefId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("beliefId", belief.beliefId()); row.put("companyId", belief.companyId());
            row.put("metric", belief.metric()); row.put("comparator", belief.comparator().name());
            row.put("threshold", Long.toString(belief.threshold()));
            row.put("formedAtMillis", Long.toString(belief.formedAtMillis()));
            row.put("targetAsOfMillis", Long.toString(belief.targetAsOfMillis()));
            row.put("verificationPublisherPartyId", belief.verificationPublisherPartyId());
            row.put("adoptedClaimId", belief.adoptedClaimId()); row.put("sourcePartyId", belief.sourcePartyId());
            row.put("confidencePermille", belief.confidencePermille()); row.put("confidenceBand", card.confidenceBand());
            row.put("rationale", belief.rationale()); row.put("verdict", card.open() ? "OPEN" : card.verdict());
            row.put("evidence", belief.evidence().stream().map(ref -> Map.of(
                    "docId", ref.docId(), "version", Long.toString(ref.version()), "index", Long.toString(ref.index()))).toList());
            rows.add(row);
        }
        result.put("beliefs", new Section(mine.size(), rows));
        List<Map<String, Object>> sources = new ArrayList<>();
        for (var source : projection.sources()) {
            sources.add(Map.of("sourcePartyId", source.sourcePartyId(), "band", source.competenceBand(),
                    "competencePermille", BeliefModel.competencePermille(learning, person, source.sourcePartyId(), policy)));
        }
        result.put("sources", sources);
        List<Map<String, Object>> habits = new ArrayList<>();
        for (var habit : projection.habits()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", habit.kind().name()); row.put("level", habit.level());
            row.put("lastUpdatedMillis", habit.lastUpdatedMillis() < 0 ? null : Long.toString(habit.lastUpdatedMillis()));
            habits.add(row);
        }
        result.put("habits", habits);
        Map<String, BeliefModel.FactClaim> facts = new LinkedHashMap<>();
        for (var fact : claims.factClaims()) facts.put(fact.factId(), fact);
        List<Map<String, Object>> evaluations = new ArrayList<>();
        for (var evaluation : learning.evaluations()) {
            if (!evaluation.personPartyId().equals(person)) continue;
            var fact = java.util.Objects.requireNonNull(facts.get(evaluation.factId()), "核验缺少持久声明");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("evaluationId", evaluation.evaluationId()); row.put("beliefId", evaluation.beliefId());
            row.put("factId", evaluation.factId()); row.put("docId", fact.docId());
            row.put("version", Long.toString(fact.version()));
            row.put("verdict", evaluation.verdict().name()); row.put("factValue", Long.toString(evaluation.factValue()));
            row.put("maturedAtMillis", Long.toString(evaluation.maturedAtMillis()));
            row.put("supersedesEvaluationId", evaluation.supersedesEvaluationId()); evaluations.add(row);
        }
        java.util.Collections.reverse(evaluations); result.put("evaluations", bounded(evaluations));
        List<Map<String, Object>> revisions = new ArrayList<>();
        for (var revision : learning.habitRevisions()) {
            if (!revision.personPartyId().equals(person)) continue;
            revisions.add(Map.of("revisionId", revision.revisionId(), "kind", revision.kind().name(),
                    "fromLevel", revision.fromLevel(), "toLevel", revision.toLevel(),
                    "atMillis", Long.toString(revision.atMillis()), "supportBeliefIds", revision.supportBeliefIds()));
        }
        java.util.Collections.reverse(revisions); result.put("habitRevisions", bounded(revisions));
        return result;
    }

    /**
     * 信息与阅读（单 writer 任务、单快照）。
     *
     * @return 视图
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public InformationView informationView() throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            OpeningRegistry registry = current.openingRegistry().orElse(null);
            InformationRun information = snapshot.information().orElse(null);

            List<Map<String, Object>> documentRows = new ArrayList<>();
            List<Map<String, Object>> activeRows = new ArrayList<>();
            List<Map<String, Object>> closedRows = new ArrayList<>();
            List<Map<String, Object>> observationRows = new ArrayList<>();
            List<Map<String, Object>> readerRows = new ArrayList<>();
            List<Map<String, Object>> reportRows = new ArrayList<>();

            if (information != null) {
                InformationModel.State state = information.information();
                for (InformationModel.Document document : state.documents()) {
                    InformationModel.DocumentVersion latest =
                            document.versions().get(document.versions().size() - 1);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("docId", document.docId());
                    row.put("title", latest.title());
                    row.put("publisherPartyId", document.publisherPartyId());
                    row.put("publisherName", partyName(registry, document.publisherPartyId()));
                    row.put("audienceKind", document.audience().kind().name());
                    row.put("audiencePartyId", document.audience().partyId());
                    row.put("audiencePartyName", partyName(registry, document.audience().partyId()));
                    row.put("latestVersion", num(latest.version()));
                    row.put("versionCount", Integer.toString(document.versions().size()));
                    row.put("publishedAt", num(latest.publishedAt()));
                    row.put("readDurationMillis", num(latest.readDurationMillis()));
                    row.put("bodyLength", Integer.toString(latest.body().length()));
                    row.put("bodyPreview", preview(latest.body(), 240));
                    List<String> versions = new ArrayList<>();
                    for (InformationModel.DocumentVersion version : document.versions()) {
                        versions.add(version.version() + "@" + version.publishedAt());
                    }
                    row.put("versions", String.join(",", versions));
                    documentRows.add(row);
                }
                documentRows.sort(Comparator.comparingLong(
                                (Map<String, Object> row) -> parseLong(row.get("publishedAt")))
                        .thenComparing(row -> String.valueOf(row.get("docId"))).reversed());

                for (InformationModel.Reading reading : state.active()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("readId", reading.readId());
                    row.put("personPartyId", reading.personPartyId());
                    row.put("personName", partyName(registry, reading.personPartyId()));
                    row.put("docId", reading.docId());
                    row.put("version", num(reading.version()));
                    row.put("startedAt", num(reading.startedAt()));
                    row.put("dueAt", num(reading.dueAt()));
                    row.put("status", "READING");
                    activeRows.add(row);
                }
                activeRows.sort(Comparator.comparing(
                        (Map<String, Object> row) -> String.valueOf(row.get("readId"))));

                for (InformationModel.ClosedReading reading : state.closed()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("readId", reading.readId());
                    row.put("personPartyId", reading.personPartyId());
                    row.put("personName", partyName(registry, reading.personPartyId()));
                    row.put("docId", reading.docId());
                    row.put("version", num(reading.version()));
                    row.put("startedAt", num(reading.startedAt()));
                    row.put("dueAt", num(reading.dueAt()));
                    row.put("closedAt", num(reading.closedAt()));
                    row.put("status", reading.reason().name());
                    closedRows.add(row);
                }
                closedRows.sort(Comparator.comparingLong(
                                (Map<String, Object> row) -> parseLong(row.get("closedAt")))
                        .thenComparing(row -> String.valueOf(row.get("readId"))).reversed());

                for (InformationModel.ReadObservation observation : state.observations()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("readId", observation.readId());
                    row.put("personPartyId", observation.personPartyId());
                    row.put("personName", partyName(registry, observation.personPartyId()));
                    row.put("docId", observation.docId());
                    row.put("version", num(observation.version()));
                    row.put("title", observation.title());
                    row.put("publishedAt", num(observation.publishedAt()));
                    row.put("completedAt", num(observation.completedAt()));
                    row.put("bodyPreview", preview(observation.body(), 240));
                    observationRows.add(row);
                }
                observationRows.sort(Comparator.comparingLong(
                                (Map<String, Object> row) -> parseLong(row.get("completedAt")))
                        .thenComparing(row -> String.valueOf(row.get("readId"))).reversed());

                for (String person : state.persons()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("partyId", person);
                    row.put("name", partyName(registry, person));
                    int active = 0;
                    for (InformationModel.Reading reading : state.active()) {
                        if (reading.personPartyId().equals(person)) {
                            active++;
                        }
                    }
                    int completed = 0;
                    for (InformationModel.ReadObservation observation : state.observations()) {
                        if (observation.personPartyId().equals(person)) {
                            completed++;
                        }
                    }
                    row.put("activeReadings", Integer.toString(active));
                    row.put("completedReadings", Integer.toString(completed));
                    readerRows.add(row);
                }

                for (InformationRun.Report report : information.reports().values()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("taskId", report.taskId());
                    row.put("commandId", report.commandId());
                    row.put("kind", report.kind());
                    row.put("accepted", report.accepted());
                    row.put("reason", report.reason());
                    row.put("subjectId", report.subjectId());
                    row.put("readerPartyId", report.readerPartyId());
                    row.put("timeMillis", num(report.timeMillis()));
                    row.put("position", num(report.position()));
                    reportRows.add(row);
                }
                sortReportsNewestFirst(reportRows);
            }

            List<String> notes = new ArrayList<>();
            notes.add("资料含发布者、冻结受众与按序追加的版本；更正通过新版本发布，旧版本原文保留。");
            notes.add("阅读完成才形成本人经历；未到期不交付全文，取消与到期复核拒绝都不产生经历。");
            notes.add("正文预览最多 240 字并标注截断，完整原文以版本记录为准；本页是管理端只读观察，不是任何角色的阅读入口。");

            return new InformationView(runId, Long.toString(snapshot.position()),
                    Long.toString(snapshot.timeMillis()), bounded(documentRows),
                    bounded(activeRows), bounded(closedRows), bounded(observationRows),
                    bounded(readerRows), bounded(reportRows), notes);
        });
    }

    /**
     * 账户表现与历史（单 writer 任务、单快照；表现只来自 {@link PerformanceModel}）。
     *
     * @return 视图
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public PerformanceView performanceView() throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            OpeningRegistry registry = current.openingRegistry().orElse(null);
            long position = snapshot.position();
            boolean performanceAvailable = position <= MAX_PERFORMANCE_FRAMES;

            List<Map<String, Object>> accountRows = new ArrayList<>();
            snapshot.accounts().forEach((accountId, account) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("accountId", accountId);
                row.put("ownerId", account.ownerId());
                row.put("ownerName", ownerName(registry, account.ownerId()));
                row.put("cash", account.cash().toPlainString());
                row.put("source", account.source());
                accountRows.add(row);
            });
            accountRows.sort(Comparator.comparing(
                    (Map<String, Object> row) -> String.valueOf(row.get("accountId"))));

            List<Map<String, Object>> ownerRows = new ArrayList<>();
            List<Map<String, Object>> holdingRows = new ArrayList<>();
            // 同一笔资本流会同时出现在转出与转入两个主体的查询结果里；按 flowId 去重后再展示。
            Map<String, Map<String, Object>> flowById = new LinkedHashMap<>();
            List<String> ownerIds = new ArrayList<>();
            if (registry != null) {
                for (OpeningRegistry.PropertyOwner owner : registry.owners()) {
                    ownerIds.add(owner.ownerId());
                }
            }
            ownerIds.sort(Comparator.naturalOrder());
            for (String ownerId : ownerIds) {
                ValuationModel.Value value = current.valuation(ownerId);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ownerId", ownerId);
                row.put("ownerName", ownerName(registry, ownerId));
                row.put("kind", ownerKind(registry, ownerId));
                row.put("valuationStatus", value.status().name());
                row.put("cash", dec(value.cash()));
                row.put("holdings", dec(value.holdings()));
                row.put("dividendReceivable", dec(value.dividendReceivable()));
                row.put("dividendPayable", dec(value.dividendPayable()));
                row.put("netWorth", dec(value.netWorth()));
                row.put("missingSymbols", String.join(",", value.missingSymbols()));
                for (ValuationModel.HoldingLine line : value.lines()) {
                    Map<String, Object> holding = new LinkedHashMap<>();
                    holding.put("ownerId", ownerId);
                    holding.put("accountId", line.accountId());
                    holding.put("symbol", line.symbol());
                    holding.put("quantity", num(line.quantity()));
                    holding.put("priceSource", line.price().source().name());
                    holding.put("price", dec(line.price().price()));
                    holding.put("value", dec(line.value()));
                    holdingRows.add(holding);
                }
                List<WorldRuntime.CapitalFlowReport> flows = current.capitalFlows(ownerId);
                row.put("capitalFlowCount", Integer.toString(flows.size()));
                for (WorldRuntime.CapitalFlowReport flow : flows) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("flowId", flow.flowId());
                    item.put("position", num(flow.position()));
                    item.put("atMillis", num(flow.atMillis()));
                    item.put("fromOwnerId", flow.fromOwnerId());
                    item.put("fromAccountId", flow.fromAccountId());
                    item.put("toOwnerId", flow.toOwnerId());
                    item.put("toAccountId", flow.toAccountId());
                    item.put("amount", dec(flow.amount()));
                    item.put("evidenceRef", flow.evidenceRef());
                    flowById.putIfAbsent(flow.flowId(), item);
                }
                if (performanceAvailable) {
                    try {
                        PerformanceModel.Performance performance =
                                current.performance(ownerId, 1L, position);
                        row.put("performanceStatus", performance.status().name());
                        row.put("returnRate", dec(performance.returnRate()));
                        row.put("index", dec(performance.index()));
                        row.put("maxDrawdown", dec(performance.maxDrawdown()));
                        row.put("segmentCount", Integer.toString(performance.segments().size()));
                        row.put("performanceNote", null);
                    } catch (RuntimeException invalid) {
                        row.put("performanceStatus", "UNAVAILABLE");
                        row.put("returnRate", null);
                        row.put("index", null);
                        row.put("maxDrawdown", null);
                        row.put("segmentCount", null);
                        row.put("performanceNote", invalid.getMessage() == null
                                ? invalid.getClass().getSimpleName() : invalid.getMessage());
                    }
                } else {
                    row.put("performanceStatus", "NOT_COMPUTED");
                    row.put("returnRate", null);
                    row.put("index", null);
                    row.put("maxDrawdown", null);
                    row.put("segmentCount", null);
                    row.put("performanceNote", "确认帧 " + position + " 超过本页上限 "
                            + MAX_PERFORMANCE_FRAMES + "，未计算期间表现");
                }
                ownerRows.add(row);
            }
            List<Map<String, Object>> flowRows = new ArrayList<>(flowById.values());
            flowRows.sort(Comparator.comparingLong(
                            (Map<String, Object> row) -> parseLong(row.get("atMillis")))
                    .thenComparingLong(row -> parseLong(row.get("position"))).reversed());
            holdingRows.sort(Comparator.comparing((Map<String, Object> row) ->
                            String.valueOf(row.get("ownerId")))
                    .thenComparing(row -> String.valueOf(row.get("accountId")))
                    .thenComparing(row -> String.valueOf(row.get("symbol"))));

            List<String> notes = new ArrayList<>();
            notes.add("净资产 = 现金（含冻结，不重复相加）+ 原始价格估值持仓 + 已确认分红应收 − 分红应付；缺价时净资产保持空，不填 0。");
            notes.add("期间收益率、指数与最大回撤全部来自 PerformanceModel：真实资本流前后估值几何链接，DECIMAL128 中间计算、12 位 HALF_EVEN 输出。");
            notes.add("少于两个确认帧、存在缺价或非正权益时，期间表现显示为不适用；本页不另算一套收益率，也不把资本流当收益。");
            notes.add("资本流只列真实确认的转移事实，按主体任一侧过滤；课程排行榜的初始权益口径不在此页混用。");

            return new PerformanceView(runId, Long.toString(position),
                    Long.toString(snapshot.timeMillis()), performanceAvailable,
                    bounded(accountRows), bounded(ownerRows), bounded(holdingRows),
                    bounded(flowRows), notes);
        });
    }

    /**
     * 账户规模、表现指数与回撤历史（单 writer 任务、单快照；只读，不推进世界）。
     *
     * <p>先取当前世界 cut，再查所选区间；区间终点与实时 cut 在响应里分别是两个字段。
     * 超过 {@link #MAX_PERFORMANCE_FRAMES} 直接拒绝，不静默截断、不改起点。</p>
     *
     * @param ownerId       财产主体
     * @param startPosition 起点确认位置
     * @param endPosition   终点确认位置
     * @return 历史响应
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public PerformanceHistoryView performanceHistory(String ownerId, long startPosition,
                                                      long endPosition)
            throws BusyException, Exception {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("未知财产主体：" + ownerId);
        }
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            OpeningRegistry registry = current.openingRegistry().orElse(null);
            long cut = snapshot.position();
            if (startPosition <= 0 || endPosition < startPosition || endPosition > cut) {
                throw new IllegalArgumentException("历史区间必须落在已确认位置内："
                        + startPosition + "-" + endPosition);
            }
            long frames = endPosition - startPosition + 1;
            if (frames > MAX_PERFORMANCE_FRAMES) {
                throw new IllegalArgumentException("历史区间 " + startPosition + "-" + endPosition
                        + " 共 " + frames + " 帧，超过上限 " + MAX_PERFORMANCE_FRAMES);
            }
            PerformanceModel.History history =
                    current.performanceHistory(ownerId, startPosition, endPosition);
            PerformanceModel.Performance performance = history.performance();

            List<PerformanceCurvePointView> pointRows = new ArrayList<>();
            for (PerformanceModel.Series series : history.series()) {
                PerformanceModel.Point point = series.point();
                List<PerformanceFlowView> pointFlows = new ArrayList<>();
                for (PerformanceModel.Flow flow : point.flows()) {
                    pointFlows.add(new PerformanceFlowView(flow.flowId(), flow.kind().name(),
                            dec(flow.amount()), Long.toString(point.position()),
                            Long.toString(point.atMillis()), flow.evidenceRef()));
                }
                pointRows.add(new PerformanceCurvePointView(Long.toString(point.position()),
                        Long.toString(point.atMillis()), point.valuationStatus().name(),
                        dec(point.navBeforeFlow()), dec(point.navAfterFlow()),
                        dec(series.index()), dec(series.drawdown()), pointFlows));
            }

            List<Map<String, Object>> flowRows = new ArrayList<>();
            for (WorldRuntime.CapitalFlowReport flow : current.capitalFlows(ownerId)) {
                if (flow.position() < startPosition || flow.position() > endPosition) {
                    continue;
                }
                boolean incoming = flow.toOwnerId().equals(ownerId);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("flowId", flow.flowId());
                row.put("kind", incoming ? PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION.name()
                        : PerformanceModel.FlowKind.CAPITAL_WITHDRAWAL.name());
                row.put("position", num(flow.position()));
                row.put("atMillis", num(flow.atMillis()));
                row.put("fromOwnerId", flow.fromOwnerId());
                row.put("fromAccountId", flow.fromAccountId());
                row.put("toOwnerId", flow.toOwnerId());
                row.put("toAccountId", flow.toAccountId());
                row.put("amount", dec(flow.amount()));
                row.put("evidenceRef", flow.evidenceRef());
                flowRows.add(row);
            }
            flowRows.sort(Comparator.comparingLong(
                            (Map<String, Object> row) -> parseLong(row.get("atMillis")))
                    .thenComparingLong(row -> parseLong(row.get("position"))).reversed());

            String startTime = pointRows.isEmpty() ? null : pointRows.get(0).timeMillis();
            String endTime = pointRows.isEmpty() ? null
                    : pointRows.get(pointRows.size() - 1).timeMillis();

            List<String> notes = new ArrayList<>();
            notes.add("资金规模 = 该确认帧的净资产（现金含冻结不重复相加 + 持仓按最后有效成交价 + 分红应收 − 分红应付）；缺价点断开，不填 0。");
            notes.add("表现指数以 1 为基数，按真实资本流前后的估值几何链接；当前回撤相对该点之前的高精度峰值。两者与期间汇总来自同一遍计算，前端不重算收益。");
            notes.add("横轴是确认记录位置，不是均匀时间；同一毫秒可以有多个确认点。每点的模拟时刻另列。");
            notes.add("区间超过 " + MAX_PERFORMANCE_FRAMES + " 帧会被拒绝；响应总带实际起点，" +
                    "\"最近 " + MAX_PERFORMANCE_FRAMES + " 帧\"只是默认请求值，不代表全期。");
            notes.add("注资与提取不画成盈利；分红登记、除息应收与到账只是资产形态变化，不重复记收益。课程排行榜的初始权益口径不在此页混用。");

            return new PerformanceHistoryView(runId, num(cut), num(snapshot.timeMillis()),
                    ownerId, ownerName(registry, ownerId), num(startPosition),
                    num(endPosition), startTime, endTime, num(pointRows.size()),
                    num(MAX_PERFORMANCE_FRAMES),
                    new PerformancePeriodView(performance.periodId(),
                            performance.status().name(), dec(performance.returnRate()),
                            dec(performance.index()), dec(performance.maxDrawdown()),
                            num(performance.segments().size())),
                    pointRows, bounded(flowRows), notes);
        });
    }

    /** 有界包装：保留真实总量，只返回前 {@link #MAX_ROWS} 行。 */
    private static Section bounded(List<Map<String, Object>> rows) {
        int total = rows.size();
        return new Section(total, rows.subList(0, Math.min(total, MAX_ROWS)));
    }

    /** 管理回报统一按「时点新→旧、同位按提交位置新→旧」排序（三个子集共用一个口径）。 */
    private static void sortReportsNewestFirst(List<Map<String, Object>> rows) {
        rows.sort(Comparator.comparingLong(
                        (Map<String, Object> row) -> parseLong(row.get("timeMillis")))
                .thenComparingLong(row -> parseLong(row.get("position"))).reversed());
    }

    /** 金额 → 精确十进制字符串。 */
    private static String dec(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** long → 精确十进制字符串。 */
    private static String num(long value) {
        return Long.toString(value);
    }

    /** 字符串 → long；非数字返回 0（只用于已由本类生成的排序键）。 */
    private static long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            return 0L;
        }
    }

    private static String partyName(OpeningRegistry registry, String partyId) {
        if (registry == null || partyId == null || partyId.isBlank()) {
            return null;
        }
        for (OpeningRegistry.Party party : registry.parties()) {
            if (party.partyId().equals(partyId)) {
                return party.name();
            }
        }
        return null;
    }

    private static String organizationPartyOf(OpeningRegistry registry, String ownerId) {
        if (registry == null || ownerId == null) {
            return null;
        }
        OpeningRegistry.PropertyOwner owner = registry.owner(ownerId);
        return owner == null ? null : owner.partyId();
    }

    private static String ownerName(OpeningRegistry registry, String ownerId) {
        String partyId = organizationPartyOf(registry, ownerId);
        String name = partyName(registry, partyId);
        return name == null ? null : name;
    }

    private static String ownerKind(OpeningRegistry registry, String ownerId) {
        OpeningRegistry.PropertyOwner owner = registry == null ? null : registry.owner(ownerId);
        return owner == null ? null : owner.kind().name();
    }

    private static Map<String, Object> inventoryRow(String label,
                                                    ManufacturingModel.Inventory inventory) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("quantity", num(inventory.quantity()));
        row.put("amount", dec(inventory.amount()));
        row.put("unitCost", dec(inventory.unitCost()));
        return row;
    }

    private static String proposedLines(List<BoardModel.BudgetLine> lines) {
        List<String> parts = new ArrayList<>();
        for (BoardModel.BudgetLine line : lines) {
            parts.add(line.period() + "/" + line.category().name() + "="
                    + line.newLimit().toPlainString());
        }
        return String.join(";", parts);
    }

    private static String ballots(Map<String, BoardModel.Ballot> ballots) {
        List<String> parts = new ArrayList<>();
        for (BoardModel.Ballot ballot : ballots.values()) {
            parts.add(ballot.seatId() + "=" + ballot.vote().name());
        }
        return String.join(";", parts);
    }

    private static String metrics(List<BoardModel.EvidenceMetric> metrics) {
        List<String> parts = new ArrayList<>();
        for (BoardModel.EvidenceMetric metric : metrics) {
            parts.add(metric.dimension().name() + "=" + metric.score().toPlainString());
        }
        return String.join(";", parts);
    }

    private static String appointmentStatus(RoleAccess.Appointment appointment, long now) {
        if (appointment.revokedAt() != null && appointment.revokedAt() <= now) {
            return "REVOKED";
        }
        if (now < appointment.startsAt()) {
            return "SCHEDULED";
        }
        return appointment.activeAt(now) ? "ACTIVE" : "EXPIRED";
    }

    private static String preview(String body, int limit) {
        if (body == null) {
            return null;
        }
        if (body.length() <= limit) {
            return body;
        }
        return body.substring(0, limit) + "…（已截断）";
    }

    /** 本档案实际使用的制造与治理实验常量（全部取真实常量，不另设数字）。 */
    private static List<Map<String, Object>> companyRules() {
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule("实验期间长度（毫秒）", Long.toString(ManufacturingModel.PERIOD_MILLIS)));
        rules.add(rule("模拟日长度（毫秒）", Long.toString(ManufacturingModel.DAY_MILLIS)));
        rules.add(rule("应收账期（毫秒）", Long.toString(ManufacturingModel.RECEIVABLE_TERM_MILLIS)));
        rules.add(rule("供应商目录单价", ManufacturingModel.RAW_UNIT_PRICE.toPlainString()));
        rules.add(rule("供应商每日容量", Long.toString(ManufacturingModel.SUPPLIER_DAILY_CAPACITY)));
        rules.add(rule("人工单价", ManufacturingModel.LABOR_UNIT_PRICE.toPlainString()));
        rules.add(rule("客户期间需求", Long.toString(ManufacturingModel.CUSTOMER_PERIOD_DEMAND)));
        rules.add(rule("客户期间预算", ManufacturingModel.CUSTOMER_PERIOD_BUDGET.toPlainString()));
        rules.add(rule("合同价格上限", ManufacturingModel.CONTRACT_PRICE_CAP.toPlainString()));
        rules.add(rule("期间费用", ManufacturingModel.PERIOD_EXPENSE.toPlainString()));
        rules.add(rule("期间折旧", ManufacturingModel.PERIOD_DEPRECIATION.toPlainString()));
        rules.add(rule("设备原值", ManufacturingModel.EQUIPMENT_ORIGINAL_COST.toPlainString()));
        rules.add(rule("董事会法定人数", Integer.toString(BoardModel.QUORUM)));
        return rules;
    }

    private static Map<String, Object> rule(String label, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", value);
        return row;
    }

    /**
     * 由同一确认快照的 D0 派生制造报表所需的分红位置。
     *
     * <p>与 {@code WorldRuntime} 内部同名派生同口径：只用 {@link DividendModel} 的公共函数，
     * 不新增任何记账规则；这里只读，不回写。</p>
     */
    private static ManufacturingModel.DividendPosition dividendPosition(DividendModel.State dividends) {
        if (dividends == null) {
            return ManufacturingModel.DividendPosition.none();
        }
        BigDecimal declared = new BigDecimal("0.00");
        BigDecimal unpaid = new BigDecimal("0.00");
        for (DividendModel.Scheme scheme : dividends.schemes().values()) {
            if (scheme.phase() != DividendModel.Phase.EX_BOOKED
                    && scheme.phase() != DividendModel.Phase.SETTLED) {
                continue;
            }
            for (DividendModel.Claim claim : DividendModel.claims(dividends, scheme.schemeId())) {
                declared = declared.add(claim.face());
                unpaid = unpaid.add(DividendModel.faceUnpaid(claim));
            }
        }
        return new ManufacturingModel.DividendPosition(declared, unpaid);
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
            if (livePaused) {
                throw new IllegalStateException("Host 已暂停，不能推进世界");
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
     * 显式场景开始被拒（无副作用）：原因只含服务端生成的固定文案与请求中已校验的人物标识，
     * 不回显公司标识原文，也不含任何凭据。
     */
    public static final class SceneRejected extends Exception {
        /** @param reason 可公开展示的中文原因 */
        public SceneRejected(String reason) {
            super(reason);
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
        if (workers != null) {
            workers.shutdownNow();
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
                Task<?> task = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (task == POISON) {
                    break;
                }
                if (task == null) {
                    safePump();
                    continue;
                }
                complete(task);
                safePump();
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

    // ================================================================ live 角色轮次

    /** writer 空闲轮询间隔：唯一 pump 的真实触发节奏。 */
    private static final long POLL_MILLIS = 250L;

    /** 预览上限：准确输入与回执各最多 4000 字符。 */
    private static final int PREVIEW_LIMIT = 4_000;

    /** 允许发起角色的四岗（固定公平顺序）。 */
    public static final List<String> LIVE_ROLE_PERSONS =
            List.of("alice", "bob", "carol", "dave");

    /**
     * 可参与本轮模型决定的真实人物。
     *
     * @param personPartyId      人物
     * @param name               展示名
     * @param sceneId            未指定本轮场景时的旧自动路由（逐字保持既有行为）
     * @param scenes             本人可显式选择的场景：旧自动路由 + 具备真实资格时的公司治理
     * @param governanceCompanies 本人此刻真正可治理的公司（只来自真实席位/经理任职）
     */
    public record LivePerson(String personPartyId, String name, String sceneId,
                             List<String> scenes, List<String> governanceCompanies) { }

    /** 当前档案的实际执行能力；五个配置槽位不等于五类场景都已接入。 */
    public record LiveCapabilities(String position, String timeMillis, boolean mediaAvailable,
                                   List<String> supportedScenes, List<LivePerson> persons,
                                   List<LivePerson> governancePersons) { }

    /** 治理场景标识（与 {@link SceneRoutes.Scene#GOVERNANCE} 一致）。 */
    public static final String SCENE_GOVERNANCE = "governance";

    /**
     * 查询同一截止的场景及人物能力，不调用提供方。
     *
     * <p>只列出真正具备执行器的场景：治理场景要求同一档案同时有工程子集（真实任务/命令执行）
     * 与治理董事会；评估场景没有执行器，因此永不出现。人物可选场景与 God 视图共用
     * {@link WorldRuntime#governanceCompanies(String)} 这一唯一资格源。</p>
     *
     * @return 已接入场景和当前有效岗位人物
     * @throws Exception 世界读取失败
     */
    public LiveCapabilities liveCapabilities() throws Exception {
        return call(() -> {
            WorldRuntime.Snapshot snapshot = readyNow().snapshot();
            List<String> scenes = workers == null || !liveRuntime() ? List.of()
                    : wiredScenes(snapshot);
            List<LivePerson> persons = scenes.isEmpty() ? List.of() : livePeople(snapshot);
            List<LivePerson> governancePersons = scenes.contains(SCENE_GOVERNANCE)
                    ? governancePersons(snapshot) : List.of();
            return new LiveCapabilities(num(snapshot.position()), num(snapshot.timeMillis()),
                    snapshot.media().isPresent(), scenes, persons, governancePersons);
        });
    }

    /**
     * 公司治理场景的可选人物：从真实登记自然人 + 当前真实董事席位/有效经理任职生成，
     * 与 {@link #livePeople(WorldRuntime.Snapshot)} 的自动路由名单相互独立。
     *
     * <p>纯董事（如 erin：真实席位、没有四岗任职）只出现在这里；旧三字段自动路由仍只认
     * {@code livePeople}，不会因为进入本名单而被自动派发。这里不新增任职、不伪造机构，
     * 每个人的公司都来自 {@link WorldRuntime#governanceCompanies(String)}。</p>
     */
    private List<LivePerson> governancePersons(WorldRuntime.Snapshot snapshot) {
        OpeningRegistry registry = runtime.openingRegistry().orElse(null);
        Map<String, LivePerson> legacy = new LinkedHashMap<>();
        for (LivePerson row : livePeople(snapshot)) {
            legacy.put(row.personPartyId(), row);
        }
        List<LivePerson> rows = new ArrayList<>();
        List<OpeningRegistry.Party> parties = registry == null ? List.of() : registry.parties();
        for (OpeningRegistry.Party party : parties) {
            if (party.kind() != OpeningRegistry.PartyKind.PERSON) {
                continue;
            }
            LivePerson known = legacy.get(party.partyId());
            if (known != null) {
                if (!known.governanceCompanies().isEmpty() && !known.scenes().isEmpty()) {
                    rows.add(known);
                }
                continue;
            }
            List<String> companies = runtime.governanceCompanies(party.partyId());
            if (companies.isEmpty()) {
                continue;
            }
            // 没有四岗任职的人没有旧自动路由：sceneId 保持为 null，只提供显式治理选择。
            rows.add(new LivePerson(party.partyId(), partyName(registry, party.partyId()), null,
                    List.of(SCENE_GOVERNANCE), companies));
        }
        rows.sort(java.util.Comparator.comparing(LivePerson::personPartyId));
        return List.copyOf(rows);
    }

    /** 本档案真正具备执行器的场景（顺序固定）；评估场景没有执行器，永不在此出现。 */
    private static List<String> wiredScenes(WorldRuntime.Snapshot snapshot) {
        List<String> wired = new ArrayList<>();
        wired.add("research");
        wired.add("trading");
        if (snapshot.media().isPresent()) {
            wired.add("media");
        }
        if (snapshot.engineering().isPresent() && snapshot.governance().isPresent()) {
            wired.add(SCENE_GOVERNANCE);
        }
        return List.copyOf(wired);
    }

    private List<LivePerson> livePeople(WorldRuntime.Snapshot snapshot) {
        OpeningRegistry registry = runtime.openingRegistry().orElse(null);
        Map<String, LivePerson> people = new LinkedHashMap<>();
        for (String person : LIVE_ROLE_PERSONS) {
            people.put(person, livePersonOf(registry, person,
                    person.equals("alice") ? "research" : "trading"));
        }
        if (snapshot.media().isPresent() && snapshot.roles().isPresent()) {
            var media = snapshot.media().orElseThrow();
            for (RoleAccess.Appointment appointment : snapshot.roles().orElseThrow()
                    .access().appointments()) {
                if (!appointment.activeAt(snapshot.timeMillis())
                        || media.config().policyOf(appointment.organizationPartyId()) == null
                        || (appointment.role() != RoleAccess.Role.JOURNALIST
                            && appointment.role() != RoleAccess.Role.EDITOR)) continue;
                // 既有四岗的路由不因新增媒体任职被静默替换。
                people.putIfAbsent(appointment.personPartyId(),
                        livePersonOf(registry, appointment.personPartyId(), "media"));
            }
        }
        // schema15：已配置联系目标与真实预约参与者（含尚未接受）走 media 技术路由；
        // 仅自然人，不授予媒体任职或媒体工具，既有 research/trading 路由优先。
        var interviews = runtime.interviews();
        if (interviews != null) {
            for (var entry : interviews.config().contacts()) {
                addDialoguePerson(people, registry, entry.targetPartyId());
            }
            for (var state : interviews.states()) {
                for (var session : state.sessions()) {
                    if (!session.open()) {
                        continue;
                    }
                    var invite = state.invites().stream()
                            .filter(i -> i.requestId().equals(session.requestId()))
                            .findFirst().orElse(null);
                    if (invite != null) {
                        addDialoguePerson(people, registry, invite.fromPartyId());
                        addDialoguePerson(people, registry, invite.toPartyId());
                    }
                }
            }
        }
        SocialRun social = snapshot.social().orElse(null);
        if (social != null) {
            for (InterviewRun.ContactEntry entry : social.contacts()) {
                addDialoguePerson(people, registry, entry.targetPartyId());
            }
            for (SocialMessageModel.Message message : social.state().messages()) {
                addDialoguePerson(people, registry, message.from());
                addDialoguePerson(people, registry, message.to());
            }
        }
        return List.copyOf(people.values());
    }

    private void addDialoguePerson(Map<String, LivePerson> people, OpeningRegistry registry,
                                   String person) {
        if (person == null) {
            return;
        }
        boolean natural = registry.parties().stream()
                .anyMatch(p -> p.partyId().equals(person)
                        && p.kind() == OpeningRegistry.PartyKind.PERSON);
        if (natural) {
            people.putIfAbsent(person, livePersonOf(registry, person, "media"));
        }
    }

    /**
     * 人物能力行：旧自动场景逐字保留，另给本人真实可选的场景与可治理公司。
     *
     * <p>可选场景来自唯一资格源 {@link WorldRuntime#governanceCompanies(String)}；没有真实席位或
     * 当前经理任职的人不会得到治理选项，这里也不因任何界面输入而授予身份。</p>
     */
    private LivePerson livePersonOf(OpeningRegistry registry, String person, String autoScene) {
        List<String> companies = runtime.governanceCompanies(person);
        List<String> scenes = new ArrayList<>();
        scenes.add(autoScene);
        if (!companies.isEmpty() && !scenes.contains(SCENE_GOVERNANCE)) {
            scenes.add(SCENE_GOVERNANCE);
        }
        return new LivePerson(person, partyName(registry, person), autoScene,
                List.copyOf(scenes), companies);
    }

    /** 媒体上帝审计视图：所有分区来自同一确认截止，条数真实且明确限制展示量。 */
    public record MediaAuditView(String runId, String position, String timeMillis, boolean available,
                                 Section organizations, Section stories, Section works,
                                 Section reports, Section pending, List<String> notes) { }

    /** 单篇稿件的真实文档版本与证据，不用最新正文覆盖旧版。 */
    public record MediaStoryView(String runId, String position, String timeMillis,
                                 String organizationPartyId, String storyId,
                                 Section versions, Section citations, Section roots) { }

    /**
     * 采访预约审计：上帝视角看全部双方、机构、时间窗、真实工时与预留。
     *
     * @return 同一确认截止的采访审计
     * @throws Exception 世界读取失败
     */
    public WorldRuntime.InterviewAudit interviewAudit() throws Exception {
        return call(() -> readyNow().interviewAudit());
    }

    /**
     * 本人预约投影：只含本人参与的协调事实与本人可达联系入口。
     *
     * @param personId 已认证人物
     * @return 本人投影
     * @throws Exception 世界读取失败
     */
    public WorldRuntime.InterviewView interviewView(String personId) throws Exception {
        return call(() -> readyNow().interviewView(personId));
    }

    /** 上帝视角一条真实轮次；阅读状态来自真实完成阅读，不看声明或回执。 */
    public record DialogueTurnRow(String sessionId, long sequence, String turnId,
                                  String speakerPartyId, String peerPartyId, String docId,
                                  long docVersion, long publishedAtMillis,
                                  boolean speakerRead, boolean peerRead,
                                  String title, String body) { }

    /** 上帝视角的对话关闭原因（只存不能从会话终态推断的部分）。 */
    public record DialogueCloseRow(String sessionId, String reason) { }

    /** 逐轮对话审计视图：轮次、关闭原因、双方实际阅读状态与采访动作回执。 */
    public record DialogueAuditView(String runId, String position, String timeMillis,
                                    boolean available, Section sessions, Section turns,
                                    Section closes, Section reports,
                                    java.util.List<String> notes) { }

    /** 本人逐轮访谈投影：只含本人参与的会话；未读轮次不返回正文。 */
    public record DialoguePersonView(String personPartyId, String timeMillis, boolean available,
                                     Section sessions, Section turns,
                                     java.util.List<String> notes) { }

    /**
     * 上帝视角逐轮访谈审计：真实轮次、关闭原因、双方实际阅读状态。
     *
     * @return 同一确认截止的对话审计
     * @throws Exception 世界读取失败
     */
    public DialogueAuditView dialogueAudit() throws Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            var snapshot = current.snapshot();
            List<Map<String, Object>> sessions = new ArrayList<>();
            List<Map<String, Object>> turns = new ArrayList<>();
            List<Map<String, Object>> closes = new ArrayList<>();
            List<Map<String, Object>> reports = new ArrayList<>();
            var interviews = current.interviews();
            if (interviews == null || snapshot.information().isEmpty()) {
                return new DialogueAuditView(runId, num(snapshot.position()),
                        num(snapshot.timeMillis()), false, bounded(sessions), bounded(turns),
                        bounded(closes), bounded(reports),
                        java.util.List.of("当前档案未启用逐轮访谈。"));
            }
            for (InterviewRun.Coordination coordination : interviews.coordinations().values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskId", coordination.taskId());
                row.put("personPartyId", coordination.personPartyId());
                row.put("kind", coordination.kind());
                row.put("accepted", coordination.accepted());
                row.put("reason", coordination.reason());
                row.put("requestId", coordination.requestId());
                row.put("timeMillis", num(coordination.timeMillis()));
                reports.add(row);
            }
            InformationModel.State info = snapshot.information().orElseThrow().information();
            long now = snapshot.timeMillis();
            for (InterviewModel.State state : interviews.states()) {
                InterviewDialogue.State dialogue = interviews.dialoguesOf(
                        state.policy().mediaOrganizationPartyId());
                for (InterviewModel.Session session : state.sessions()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sessionId", session.sessionId());
                    row.put("requestId", session.requestId());
                    row.put("startedAtMillis", num(session.startedAtMillis()));
                    row.put("endedAtMillis", num(session.endedAtMillis()));
                    row.put("open", session.open());
                    row.put("organizationPartyId", state.policy().mediaOrganizationPartyId());
                    sessions.add(row);
                }
                for (InterviewDialogue.Turn turn : dialogue.turns()) {
                    String[] pair = peersOf(state, turn.sessionId());
                    boolean speakerRed = pair != null
                            && InformationModel.canCite(info, turn.speakerPartyId(),
                                    turn.docId(), turn.docVersion(), now);
                    boolean peerRead = pair != null
                            && InformationModel.canCite(info, otherOf(pair, turn.speakerPartyId()),
                                    turn.docId(), turn.docVersion(), now);
                    InformationModel.DocumentVersion version = versionOf(info, turn.docId(),
                            turn.docVersion());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sessionId", turn.sessionId());
                    row.put("sequence", num(turn.sequence()));
                    row.put("turnId", turn.turnId());
                    row.put("speakerPartyId", turn.speakerPartyId());
                    row.put("peerPartyId", pair == null ? "" : otherOf(pair, turn.speakerPartyId()));
                    row.put("docId", turn.docId());
                    row.put("docVersion", num(turn.docVersion()));
                    row.put("publishedAtMillis", version == null ? "" : num(version.publishedAt()));
                    row.put("speakerRead", speakerRed);
                    row.put("peerRead", peerRead);
                    row.put("title", version == null ? "" : version.title());
                    row.put("body", version == null ? "" : version.body());
                    turns.add(row);
                }
                for (InterviewDialogue.DialogueClose close : dialogue.closes()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sessionId", close.sessionId());
                    row.put("reason", close.reason().name());
                    closes.add(row);
                }
            }
            return new DialogueAuditView(runId, num(snapshot.position()), num(snapshot.timeMillis()),
                    true, bounded(sessions), bounded(turns), bounded(closes), bounded(reports),
                    java.util.List.of("轮次正文只来自真实 DIRECT 文档；阅读状态取完成的阅读记录。",
                            "回执表来自真实采访命令的结果，不做推断。",
                            "关闭原因记录触发结束的真实规则，不推断模型动机。"));
        });
    }

    /**
     * 本人逐轮访谈投影：只含本人参与会话；未实际读完的轮次只给投递元数据。
     *
     * @param personId 已认证人物
     * @return 本人投影
     * @throws Exception 世界读取失败
     */
    public DialoguePersonView dialogueView(String personId) throws Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            var snapshot = current.snapshot();
            List<Map<String, Object>> sessions = new ArrayList<>();
            List<Map<String, Object>> turns = new ArrayList<>();
            var interviews = current.interviews();
            if (interviews == null || snapshot.information().isEmpty()) {
                return new DialoguePersonView(personId, num(snapshot.timeMillis()), false,
                        bounded(sessions), bounded(turns),
                        java.util.List.of("当前档案未启用逐轮访谈。"));
            }
            InformationModel.State info = snapshot.information().orElseThrow().information();
            long now = snapshot.timeMillis();
            for (InterviewModel.State state : interviews.states()) {
                InterviewDialogue.State dialogue = interviews.dialoguesOf(
                        state.policy().mediaOrganizationPartyId());
                for (InterviewModel.Session session : state.sessions()) {
                    String[] pair = peersOf(state, session.sessionId());
                    if (pair == null || (!pair[0].equals(personId) && !pair[1].equals(personId))) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sessionId", session.sessionId());
                    row.put("requestId", session.requestId());
                    row.put("peerPartyId", otherOf(pair, personId));
                    row.put("startedAtMillis", num(session.startedAtMillis()));
                    row.put("endedAtMillis", num(session.endedAtMillis()));
                    row.put("open", session.open());
                    row.put("closeReason", dialogue.closeOf(session.sessionId())
                            .map(close -> close.reason().name()).orElse(""));
                    sessions.add(row);
                    for (InterviewDialogue.Turn turn : dialogue.turnsOf(session.sessionId())) {
                        boolean mine = InformationModel.canCite(info, personId, turn.docId(),
                                turn.docVersion(), now);
                        boolean peerRead = InformationModel.canCite(info,
                                otherOf(pair, personId), turn.docId(), turn.docVersion(), now);
                        InformationModel.DocumentVersion version = versionOf(info, turn.docId(),
                                turn.docVersion());
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("sessionId", turn.sessionId());
                        item.put("sequence", num(turn.sequence()));
                        item.put("turnId", turn.turnId());
                        item.put("speakerPartyId", turn.speakerPartyId());
                        item.put("mine", turn.speakerPartyId().equals(personId));
                        item.put("docId", turn.docId());
                        item.put("docVersion", num(turn.docVersion()));
                        item.put("readByMe", mine);
                        item.put("readByPeer", peerRead);
                        item.put("publishedAtMillis", version == null ? "" : num(version.publishedAt()));
                        item.put("title", mine && version != null ? version.title() : "");
                        item.put("body", mine && version != null ? version.body() : "");
                        turns.add(item);
                    }
                }
            }
            return new DialoguePersonView(personId, num(snapshot.timeMillis()), true,
                    bounded(sessions), bounded(turns),
                    java.util.List.of("只显示本人参与的会话；未实际读完的轮次只给投递元数据。",
                            "正文来自真实文档，不随声明或模型文本生成。"));
        });
    }

    /** 会话双方：优先从邀请取 from/to，退回会话本身。 */
    private static String[] peersOf(InterviewModel.State state, String sessionId) {
        for (InterviewModel.Session session : state.sessions()) {
            if (!session.sessionId().equals(sessionId)) {
                continue;
            }
            for (InterviewModel.Invite invite : state.invites()) {
                if (invite.requestId().equals(session.requestId())) {
                    return new String[] {invite.fromPartyId(), invite.toPartyId()};
                }
            }
        }
        return null;
    }

    private static String otherOf(String[] pair, String person) {
        return pair[0].equals(person) ? pair[1] : pair[0];
    }

    private static InformationModel.DocumentVersion versionOf(InformationModel.State info,
                                                              String docId, long version) {
        for (InformationModel.Document document : info.documents()) {
            if (!document.docId().equals(docId)) {
                continue;
            }
            for (InformationModel.DocumentVersion candidate : document.versions()) {
                if (candidate.version() == version) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** 管理员看到的一条真实证据根（去重后的 docId/版本）。 */
    public record EvidenceRootRow(String organizationPartyId, String docId, long version,
                                  int aliasCount, int shareCount) { }

    /** 管理员看到的展示别名：公开标签与它真实绑定的根。 */
    public record EvidenceAliasRow(String organizationPartyId, String storyId, int revisionNo,
                                   String docId, long version, String publicLabel,
                                   String publicDocId, long publicVersion) { }

    /** 管理员看到的一条身份共享；接收者是否真的读完单独由阅读记录给出。 */
    public record EvidenceShareRow(String organizationPartyId, String shareId, String docId,
                                   long version, String recipientPartyId, String sharingDocId,
                                   long sharingVersion, boolean recipientRead) { }

    /** 证据审计视图：实际根、别名与共享绑定；三者在同一确认截止下读取。 */
    public record EvidenceAuditView(String runId, String position, String timeMillis,
                                    boolean available, Section roots, Section aliases,
                                    Section shares, java.util.List<String> notes) { }

    /** 人物证据视图：只含本人实际读到的公开标签，以及本人实际读完的共享说明。 */
    public record EvidencePersonView(String personPartyId, String timeMillis, boolean available,
                                     Section labels, Section shares,
                                     java.util.List<String> notes) { }

        /** 资料版本键：用类型而不是 `docId#version` 字符串解码，避免合法含 # 的标识被误拆。 */
    private record DocRef(String docId, long version) { }

    /** 本人当前可回忆的已读资料；走既有阅读/回忆投影，因此遗忘后不再出现。 */
    private static java.util.Set<DocRef> recallable(WorldRuntime current, String person) {
        java.util.Set<DocRef> refs = new java.util.LinkedHashSet<>();
        for (RoleView.Experience experience : current.roleView(person).observations()) {
            refs.add(new DocRef(experience.ref().docId(), experience.ref().version()));
        }
        return refs;
    }

    /** 某次提交对应的真实发表记录；未发表返回 null，不假设提交次数等于公开版本。 */
    private static MediaModel.Revision publishedRevision(MediaRun media, String organization,
                                                         String storyId, int revisionNo) {
        if (media == null) {
            return null;
        }
        for (MediaModel.State state : media.states()) {
            if (!state.policy().mediaOrganizationPartyId().equals(organization)) {
                continue;
            }
            for (MediaModel.Revision revision : state.revisions()) {
                if (revision.storyId().equals(storyId) && revision.revisionNo() == revisionNo
                        && revision.status() == MediaModel.Status.PUBLISHED) {
                    return revision;
                }
            }
        }
        return null;
    }

    /**
     * 上帝视角证据审计：真实根、别名绑定与身份共享。
     *
     * @return 同一确认截止的证据审计
     * @throws Exception 世界读取失败
     */
    public EvidenceAuditView evidenceAudit() throws Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            var snapshot = current.snapshot();
            List<Map<String, Object>> roots = new ArrayList<>();
            List<Map<String, Object>> aliases = new ArrayList<>();
            List<Map<String, Object>> shares = new ArrayList<>();
            if (snapshot.evidence().isEmpty() || snapshot.information().isEmpty()) {
                return new EvidenceAuditView(runId, num(snapshot.position()),
                        num(snapshot.timeMillis()), false, bounded(roots), bounded(aliases),
                        bounded(shares), java.util.List.of("当前档案未启用证据模块。"));
            }
            EvidenceRun run = snapshot.evidence().orElseThrow();
            MediaRun media = snapshot.media().orElse(null);
            java.util.Map<DocRef, int[]> rootCounts = new LinkedHashMap<>();
            java.util.Map<DocRef, String> rootOrg = new LinkedHashMap<>();
            for (var entry : run.states().entrySet()) {
                String organization = entry.getKey();
                MediaEvidence.State state = entry.getValue();
                for (MediaEvidence.AliasBinding alias : state.aliases()) {
                    MediaModel.Revision published = publishedRevision(media, organization,
                            alias.storyId(), alias.revisionNo());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("organizationPartyId", organization);
                    row.put("storyId", alias.storyId());
                    row.put("revisionNo", alias.revisionNo());
                    row.put("docId", alias.root().docId());
                    row.put("version", alias.root().version());
                    row.put("publicLabel", alias.publicLabel());
                    row.put("published", published != null);
                    row.put("publicDocId", published == null ? "" : published.docId());
                    row.put("publicVersion", published == null ? "" : published.docVersion());
                    aliases.add(row);
                    countRoot(rootCounts, new DocRef(alias.root().docId(), alias.root().version()),
                            1, 0, rootOrg, organization);
                }
                for (MediaEvidence.IdentityShare share : state.shares()) {
                    boolean read = recallable(current, share.recipientPartyId())
                            .contains(new DocRef(share.sharingDocId(), share.sharingVersion()));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("organizationPartyId", organization);
                    row.put("shareId", share.shareId());
                    row.put("docId", share.root().docId());
                    row.put("version", share.root().version());
                    row.put("recipientPartyId", share.recipientPartyId());
                    row.put("sharingDocId", share.sharingDocId());
                    row.put("sharingVersion", share.sharingVersion());
                    row.put("recipientRead", read);
                    shares.add(row);
                    countRoot(rootCounts, new DocRef(share.root().docId(), share.root().version()),
                            0, 1, rootOrg, organization);
                }
            }
            for (var entry : rootCounts.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("organizationPartyId", rootOrg.getOrDefault(entry.getKey(), ""));
                row.put("docId", entry.getKey().docId());
                row.put("version", entry.getKey().version());
                row.put("aliasCount", entry.getValue()[0]);
                row.put("shareCount", entry.getValue()[1]);
                roots.add(row);
            }
            return new EvidenceAuditView(runId, num(snapshot.position()), num(snapshot.timeMillis()),
                    true, bounded(roots), bounded(aliases), bounded(shares),
                    java.util.List.of("别名只在其提交被真实发表后才有公开文章与版本。",
                            "共享接收者是否已读按该人当前可回忆的阅读记录判定。",
                            "别名是公开标签，不等同于对正文的自动审查。"));
        });
    }

    /**
     * 本人证据投影：只给本人当前可回忆的公开标签与实际读完的共享说明。
     *
     * <p>标签映射走既有发表记录（提交次 → 公开文档与版本），不假设两者相等；
     * 未发表的重投/修订不产生公开标签。私证根标识不进入本投影。</p>
     *
     * @param personId 已认证人物
     * @return 本人投影
     * @throws Exception 世界读取失败
     */
    public EvidencePersonView evidenceView(String personId) throws Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            var snapshot = current.snapshot();
            List<Map<String, Object>> labels = new ArrayList<>();
            List<Map<String, Object>> shares = new ArrayList<>();
            if (snapshot.evidence().isEmpty() || snapshot.information().isEmpty()
                    || snapshot.media().isEmpty()) {
                return new EvidencePersonView(personId, num(snapshot.timeMillis()), false,
                        bounded(labels), bounded(shares),
                        java.util.List.of("当前档案未启用证据模块。"));
            }
            EvidenceRun run = snapshot.evidence().orElseThrow();
            InformationModel.State info = snapshot.information().orElseThrow().information();
            MediaRun media = snapshot.media().orElseThrow();
            java.util.Set<DocRef> readable = recallable(current, personId);
            for (var entry : run.states().entrySet()) {
                String organization = entry.getKey();
                for (MediaEvidence.AliasBinding alias : entry.getValue().aliases()) {
                    MediaModel.Revision published = publishedRevision(media, organization,
                            alias.storyId(), alias.revisionNo());
                    if (published == null) {
                        continue; // 该次提交未发表：旧标签不冒充已公开
                    }
                    DocRef publicRef = new DocRef(published.docId(), published.docVersion());
                    if (!readable.contains(publicRef)) {
                        continue; // 未实际读到并仍可回忆，不出现（不泄存在性）
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("storyId", alias.storyId());
                    row.put("publicDocId", published.docId());
                    row.put("publicVersion", published.docVersion());
                    row.put("publicLabel", alias.publicLabel());
                    labels.add(row);
                }
                for (MediaEvidence.IdentityShare share : entry.getValue().shares()) {
                    if (!share.recipientPartyId().equals(personId)) {
                        continue;
                    }
                    if (!readable.contains(new DocRef(share.sharingDocId(),
                            share.sharingVersion()))) {
                        continue; // 未读或已遗忘的共享不出现
                    }
                    InformationModel.DocumentVersion version = versionOf(info,
                            share.sharingDocId(), share.sharingVersion());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("shareId", share.shareId());
                    row.put("sharingDocId", share.sharingDocId());
                    row.put("sharingVersion", share.sharingVersion());
                    row.put("title", version == null ? "" : version.title());
                    row.put("body", version == null ? "" : version.body());
                    shares.add(row);
                }
            }
            return new EvidencePersonView(personId, num(snapshot.timeMillis()), true,
                    bounded(labels), bounded(shares),
                    java.util.List.of("只显示本人当前可回忆的公开标签与共享说明；未读或已遗忘不出现。",
                            "共享说明只展示实际读到的正文，不授予原始资料的访问权。"));
        });
    }

    /** 管理员消息行：真实状态、真实拒因与收件人实际阅读状态（仅管理端）。 */
    public record SocialMessageRow(String messageId, String from, String to, String channel,
                                   String status, String docId, long version, long sentAt,
                                   long dueAt, long statusAt, String title,
                                   boolean readByReceiver, String rejectReason) { }

    /** 管理员屏蔽行（真实屏蔽方，仅管理端可见）。 */
    public record SocialBlockRow(String ownerPartyId, String targetPartyId, long since,
                                 String commandId) { }

    /** 管理员任务报告行：业务拒绝也留痕，reason 为真实原因码。 */
    public record SocialReportRow(String taskId, String person, String kind, boolean accepted,
                                  String reason, String messageId, long atMillis, long position) { }

    /** 受信联系入口行（不是全世界人员清单）。 */
    public record SocialContactRow(String targetPartyId, String channel, String description,
                                   String docId, long version) { }

    /** 消息审计视图：同一确认截止下的消息、屏蔽、报告与联系入口。 */
    public record SocialAuditView(String runId, String position, String timeMillis,
                                  boolean available, long clock, Section messages, Section blocks,
                                  Section reports, Section contacts,
                                  java.util.List<String> notes) { }

    /** 人物收件箱行：已送达信封；正文只在本人真正读完并仍可回忆时出现。 */
    public record SocialInboxRow(String messageId, String from, String channel, String title,
                                 String docId, long version, long deliveredAt, boolean read,
                                 String body) { }

    /** 人物发件箱行：未送达对外统一 UNDELIVERED，无屏蔽原因、无对方已读。 */
    public record SocialOutboxRow(String messageId, String to, String channel, long sentAt,
                                  long dueAt, String status) { }

    /** 人物消息视图：只本人 inbox/outbox 与合法联系人。 */
    public record SocialPersonView(String personPartyId, String timeMillis, boolean available,
                                   Section inbox, Section outbox, Section contacts,
                                   java.util.List<String> notes) { }

    /**
     * 上帝视角消息审计：真实送达/屏蔽/业务拒绝与收件人阅读状态。
     *
     * @return 同一确认截止的审计
     * @throws Exception 世界读取失败
     */
    public SocialAuditView socialAudit() throws Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            var snapshot = current.snapshot();
            List<Map<String, Object>> messages = new ArrayList<>();
            List<Map<String, Object>> blocks = new ArrayList<>();
            List<Map<String, Object>> reports = new ArrayList<>();
            List<Map<String, Object>> contacts = new ArrayList<>();
            SocialRun run = current.socialRun();
            if (run == null || snapshot.information().isEmpty()) {
                return new SocialAuditView(runId, num(snapshot.position()),
                        num(snapshot.timeMillis()), false, -1L, bounded(messages), bounded(blocks),
                        bounded(reports), bounded(contacts),
                        java.util.List.of("当前档案未启用消息模块。"));
            }
            InformationModel.State info = snapshot.information().orElseThrow().information();
            for (SocialMessageModel.Message message : run.state().messages()) {
                boolean read = InformationModel.canCite(info, message.to(), message.docId(),
                        1L, snapshot.timeMillis());
                String reject = "";
                for (SocialRun.SocialReport report : run.reports()) {
                    if (!report.accepted() && report.messageId().equals(message.messageId())) {
                        reject = report.reason();
                    }
                }
                InformationModel.DocumentVersion version = versionOf(info, message.docId(), 1L);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("messageId", message.messageId());
                row.put("from", message.from());
                row.put("to", message.to());
                row.put("channel", message.channel());
                row.put("status", message.status().name());
                row.put("docId", message.docId());
                row.put("version", 1L);
                row.put("sentAt", message.sentAt());
                row.put("dueAt", message.dueAt());
                row.put("statusAt", message.statusAt());
                row.put("title", version == null ? "" : version.title());
                row.put("readByReceiver", read);
                row.put("rejectReason", reject);
                messages.add(row);
            }
            for (SocialMessageModel.Block block : run.state().blocks()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ownerPartyId", block.ownerPartyId());
                row.put("targetPartyId", block.targetPartyId());
                row.put("since", block.since());
                row.put("commandId", block.commandId());
                blocks.add(row);
            }
            for (SocialRun.SocialReport report : run.reports()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskId", report.taskId());
                row.put("person", report.person());
                row.put("kind", report.kind());
                row.put("accepted", report.accepted());
                row.put("reason", report.reason());
                row.put("messageId", report.messageId());
                row.put("atMillis", report.atMillis());
                row.put("position", report.position());
                reports.add(row);
            }
            for (InterviewRun.ContactEntry entry : run.contacts()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("targetPartyId", entry.targetPartyId());
                row.put("channel", entry.channel());
                row.put("description", entry.description());
                row.put("docId", entry.docId());
                row.put("version", entry.version());
                contacts.add(row);
            }
            return new SocialAuditView(runId, num(snapshot.position()),
                    num(snapshot.timeMillis()), true, run.state().clock(),
                    bounded(messages), bounded(blocks),
                    bounded(reports), bounded(contacts),
                    java.util.List.of("送达状态与拒因来自真实报告；收件人是否已读按其可回忆的阅读记录。",
                            "未送达对外统一 UNDELIVERED，管理端保留真实原因。"));
        });
    }

    /**
     * 本人消息视图：已送达信封、本人发件结果与合法联系人。
     *
     * @param personId 已认证人物
     * @return 本人投影
     * @throws Exception 世界读取失败
     */
    // ---------------------------------------------------------------- 群组与平台（schema20）

    /** 本人可见群（开放成员段）。 */
    public record GroupSummaryView(String groupId, String joinPolicy, String historyPolicy,
                                   String joinedAt, boolean owner) {
    }

    /** 发给本人的待处理邀请。 */
    public record GroupInviteView(String inviteId, String groupId, String inviter,
                                  String invitedAt) {
    }

    /** 本人有权看到的群帖/平台帖；正文只在真实读完且可回忆时下发。 */
    public record GroupPostView(String docId, String version, String groupId, String author,
                                String title, String body, String publishedAt, boolean mine,
                                boolean read, List<String> refs) {
    }

    /** 公开平台目录条目（不含群内帖）。 */
    public record GroupFeedView(String docId, String version, String title, String publisher,
                                String publishedAt, String kind) {
    }

    /** 本人群组页：只含本人有权限的群、邀请、帖子与公开目录。 */
    public record GroupPageView(String runId, String position, String timeMillis, String personId, boolean enabled,
                                List<GroupSummaryView> groups, List<GroupInviteView> invitations,
                                List<GroupPostView> posts, List<GroupFeedView> feed,
                                Map<String, String> actionMillis) {
    }

    /** 群组动作回执：只表示已排程，完成与否以世界记录为准。 */
    public record GroupActionView(String runId, String position, String timeMillis, String action,
                                  String receipt) {
    }

    /** 上帝审计：群、成员段、邀请、帖子版本、呈现帧与报告（含拒绝细分原因）。 */
    public record GroupAuditView(String runId, String position, String timeMillis,
                                 boolean enabled, List<Map<String, Object>> groups,
                                 List<Map<String, Object>> memberships,
                                 List<Map<String, Object>> invitations,
                                 List<Map<String, Object>> posts,
                                 List<Map<String, Object>> versions,
                                 List<Map<String, Object>> platformPosts,
                                 List<Map<String, Object>> frames,
                                 List<Map<String, Object>> pending,
                                 List<Map<String, Object>> reports) {
    }

    /**
     * 本人群组页：只列本人开放成员段、发给本人的待处理邀请、本人可读的群帖与公开平台目录。
     *
     * @param personId 会话绑定人物
     * @return 本人视图；未启用 groups 时 enabled=false 且各表为空
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public GroupPageView groupPage(String personId) throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            GroupsRun run = current.groupsRun();
            if (run == null) {
                return new GroupPageView(runId, num(snapshot.position()), num(snapshot.timeMillis()), personId, false,
                        List.of(), List.of(), List.of(), List.of(), Map.of());
            }
            long now = snapshot.timeMillis();
            List<GroupSummaryView> groups = new ArrayList<>();
            for (GroupModel.Membership segment : run.state().memberships()) {
                if (!segment.person().equals(personId) || !segment.open()) {
                    continue;
                }
                run.group(segment.groupId()).ifPresent(group -> groups.add(
                        new GroupSummaryView(group.groupId(), group.joinPolicy().name(),
                                group.historyPolicy().name(), num(segment.joinedAt()),
                                group.ownerPartyId().equals(personId))));
            }
            List<GroupInviteView> invitations = new ArrayList<>();
            for (GroupModel.Invitation invitation : run.state().invitations()) {
                if (invitation.invitee().equals(personId)
                        && invitation.status() == GroupModel.InvitationStatus.PENDING) {
                    invitations.add(new GroupInviteView(invitation.inviteId(),
                            invitation.groupId(), invitation.inviter(),
                            num(invitation.invitedAt())));
                }
            }
            Set<String> mine = new HashSet<>();
            for (GroupSummaryView group : groups) {
                mine.add(group.groupId());
            }
            RoleView view = current.roleView(personId);
            Set<String> visible = new HashSet<>();
            for (InformationModel.CatalogEntry entry : view.catalog()) {
                visible.add(entry.docId() + "#" + entry.version());
            }
            Map<String, RoleView.Experience> experiences = new HashMap<>();
            for (RoleView.Experience experience : view.observations()) {
                experiences.put(experience.ref().docId() + "#" + experience.ref().version(),
                        experience);
            }
            List<GroupPostView> posts = new ArrayList<>();
            for (InformationModel.CatalogEntry entry : view.catalog()) {
                InformationModel.Document document = documentOf(snapshot, entry.docId());
                if (document == null
                        || document.audience().kind() != InformationModel.AudienceKind.GROUP
                        || !mine.contains(document.audience().partyId())) {
                    continue;
                }
                RoleView.Experience experience = experiences.get(
                        entry.docId() + "#" + entry.version());
                boolean read = experience != null;
                GroupModel.Post post = null;
                for (GroupModel.Post candidate : run.state().posts()) {
                    if (candidate.docId().equals(entry.docId())) {
                        post = candidate;
                    }
                }
                List<String> refs = new ArrayList<>();
                if (post != null) {
                    for (GroupModel.Ref ref : post.declaredRefs()) {
                        // 只公开读者本人此刻可访问/可引用的来源表示；God 审计另列真实根
                        String key = ref.docId() + "#" + ref.version();
                        if (visible.contains(key)) {
                            refs.add(key);
                        }
                    }
                }
                posts.add(new GroupPostView(entry.docId(), num(entry.version()),
                        document.audience().partyId(), document.publisherPartyId(), entry.title(),
                        read ? experience.body() : null, num(document.versions()
                                .get(document.versions().size() - 1).publishedAt()),
                        document.publisherPartyId().equals(personId), read, List.copyOf(refs)));
            }
            List<GroupFeedView> feed = new ArrayList<>();
            for (PublicationRun.FeedEntry entry : PublicationRun.feed(
                    run.publication().platform(), mediaForFeed(snapshot), infoOf(snapshot), now,
                    run.feedLimit())) {
                feed.add(new GroupFeedView(entry.docId(), num(entry.version()), entry.title(),
                        entry.publisherPartyId(), num(entry.publishedAt()), entry.kind().name()));
            }
            return new GroupPageView(runId, num(snapshot.position()), num(snapshot.timeMillis()), personId, true,
                    List.copyOf(groups), List.copyOf(invitations), List.copyOf(posts),
                    List.copyOf(feed), Map.of(
                    "JOIN", num(run.config().joinMillis()),
                    "INVITE", num(run.config().inviteMillis()),
                    "RESPOND", num(run.config().respondMillis()),
                    "LEAVE", num(run.config().leaveMillis()),
                    "POST", num(run.config().postMillis()),
                    "CORRECTION", num(run.config().postMillis()),
                    "PLATFORM_POST", num(run.platformConfig().postMillis()),
                    "PLATFORM_CORRECTION", num(run.platformConfig().postMillis())));
        });
    }

    /**
     * 会话人物执行一次群/平台动作：人物只来自会话，正工时与资格由世界复核。
     *
     * @param personId 会话绑定人物
     * @param request  严格解析的动作请求
     * @return 排程回执
     * @throws BusyException       队列满
     * @throws PositionMismatch    客户端位置已过期
     * @throws Exception           世界读取/持久失败
     */
    public GroupActionView groupAction(String personId, GroupActionRequest request)
            throws BusyException, Exception {
        if (request == null) {
            throw new IllegalArgumentException("群组动作不能为空");
        }
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            if (snapshot.position() != request.expectedPosition()) {
                throw new PositionMismatch(snapshot.position());
            }
            WorldRoleSession session = current.openRoleSession(personId);
            List<WorldRoleSession.Citation> cites = new ArrayList<>();
            for (GroupRef ref : request.cites()) {
                cites.add(new WorldRoleSession.Citation(ref.docId(), ref.version()));
            }
            String receipt = switch (request.action()) {
                case "JOIN" -> session.joinGroup(request.commandId(), request.taskId(),
                        request.dueTimeMillis(),
                        new WorldRoleSession.GroupJoinDraft(request.groupId()));
                case "INVITE" -> session.inviteToGroup(request.commandId(), request.taskId(),
                        request.dueTimeMillis(),
                        new WorldRoleSession.GroupInviteDraft(request.groupId(),
                                request.inviteePartyId()));
                case "RESPOND" -> session.respondInvitation(request.commandId(), request.taskId(),
                        request.dueTimeMillis(),
                        new WorldRoleSession.GroupRespondDraft(request.inviteId(),
                                request.accept()));
                case "LEAVE" -> session.leaveGroup(request.commandId(), request.taskId(),
                        request.dueTimeMillis(),
                        new WorldRoleSession.GroupLeaveDraft(request.groupId()));
                case "POST" -> session.postToGroup(request.commandId(), request.taskId(),
                        request.dueTimeMillis(),
                        new WorldRoleSession.GroupPostDraft(request.groupId(), request.title(),
                                request.body(), cites));
                case "CORRECTION" -> session.correctGroupPost(request.commandId(),
                        request.taskId(), request.dueTimeMillis(),
                        new WorldRoleSession.GroupCorrectDraft(request.docId(),
                                request.basedOnVersion(), request.title(), request.body(),
                                cites));
                case "PLATFORM_POST" -> session.postToPlatform(request.commandId(),
                        request.taskId(), request.dueTimeMillis(),
                        new WorldRoleSession.PlatformPostDraft(request.title(), request.body(),
                                cites));
                case "PLATFORM_CORRECTION" -> session.correctPlatformPost(request.commandId(),
                        request.taskId(), request.dueTimeMillis(),
                        new WorldRoleSession.PlatformCorrectDraft(request.docId(),
                                request.basedOnVersion(), request.title(), request.body(),
                                cites));
                default -> throw new IllegalArgumentException("未知群组动作：" + request.action());
            };
            return new GroupActionView(runId, num(snapshot.position()),
                    num(snapshot.timeMillis()), request.action(), receipt);
        });
    }

    /**
     * 上帝审计：群定义、成员段、邀请、帖子与版本、呈现帧、系统待办与报告。
     *
     * <p>只有管理员路由可调用；人物视图不含报告细分原因与来源根缓存。</p>
     */
    public GroupAuditView groupAudit() throws BusyException, Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            GroupsRun run = current.groupsRun();
            if (run == null) {
                return new GroupAuditView(runId, num(snapshot.position()),
                        num(snapshot.timeMillis()), false, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            }
            List<Map<String, Object>> groups = new ArrayList<>();
            for (GroupModel.Group group : run.state().groups()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("groupId", group.groupId());
                row.put("owner", group.ownerPartyId());
                row.put("joinPolicy", group.joinPolicy().name());
                row.put("historyPolicy", group.historyPolicy().name());
                row.put("initialMembers", group.initialMembers());
                groups.add(row);
            }
            List<Map<String, Object>> memberships = new ArrayList<>();
            for (GroupModel.Membership segment : run.state().memberships()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("groupId", segment.groupId());
                row.put("person", segment.person());
                row.put("joinedAt", num(segment.joinedAt()));
                row.put("leftAt", segment.open() ? "" : num(segment.leftAt()));
                row.put("inviteId", segment.inviteId());
                memberships.add(row);
            }
            List<Map<String, Object>> invitations = new ArrayList<>();
            for (GroupModel.Invitation invitation : run.state().invitations()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("inviteId", invitation.inviteId());
                row.put("groupId", invitation.groupId());
                row.put("inviter", invitation.inviter());
                row.put("invitee", invitation.invitee());
                row.put("status", invitation.status().name());
                row.put("invitedAt", num(invitation.invitedAt()));
                row.put("decidedAt", invitation.decidedAt() < 0 ? ""
                        : num(invitation.decidedAt()));
                invitations.add(row);
            }
            List<Map<String, Object>> posts = new ArrayList<>();
            for (GroupModel.Post post : run.state().posts()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("postId", post.postId());
                row.put("docId", post.docId());
                row.put("groupId", post.groupId());
                row.put("author", post.author());
                row.put("status", post.status().name());
                row.put("publishedAt", num(post.publishedAt()));
                row.put("declaredRefs", refs(post.declaredRefs()));
                row.put("originRefs", refs(post.originRefs()));
                row.put("contentDigest", post.contentDigest());
                posts.add(row);
            }
            List<Map<String, Object>> versions = new ArrayList<>();
            for (GroupModel.PostVersion version : run.state().postVersions()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("docId", version.docId());
                row.put("version", num(version.version()));
                row.put("basedOnVersion", num(version.basedOnVersion()));
                row.put("author", version.author());
                row.put("status", version.status().name());
                row.put("publishedAt", num(version.publishedAt()));
                row.put("declaredRefs", refs(version.declaredRefs()));
                row.put("originRefs", refs(version.originRefs()));
                versions.add(row);
            }
            List<Map<String, Object>> platformPosts = new ArrayList<>();
            for (PlatformPostModel.Post post : run.publication().platform().posts()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("docId", post.docId());
                row.put("version", num(post.version()));
                row.put("author", post.author());
                row.put("status", post.status().name());
                row.put("publishedAt", num(post.publishedAt()));
                row.put("originRefs", refs(post.originRefs()));
                platformPosts.add(row);
            }
            List<Map<String, Object>> frames = new ArrayList<>();
            for (PublicationRun.PresentationFrame frame : run.publication().frames()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("actionId", frame.actionId());
                row.put("attemptId", frame.attemptId());
                row.put("inputDigest", frame.inputDigest());
                row.put("observedAt", num(frame.observedAt()));
                List<String> entries = new ArrayList<>();
                for (PublicationRun.FeedEntry entry : frame.entries()) {
                    entries.add(entry.rank() + ":" + entry.docId() + "#" + entry.version());
                }
                row.put("entries", entries);
                frames.add(row);
            }
            List<Map<String, Object>> pending = new ArrayList<>();
            for (PendingTask task : snapshot.pending()) {
                if (!GroupsRun.KINDS.contains(task.kind())) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskId", task.taskId());
                row.put("kind", task.kind());
                row.put("dueTimeMillis", num(task.dueTimeMillis()));
                pending.add(row);
            }
            List<Map<String, Object>> reports = new ArrayList<>();
            for (GroupsRun.GroupReport report : run.reports()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskId", report.taskId());
                row.put("person", report.person());
                row.put("kind", report.kind());
                row.put("action", report.action());
                row.put("accepted", report.accepted());
                row.put("reason", report.reason());
                row.put("subject", report.subject());
                row.put("atMillis", num(report.atMillis()));
                reports.add(row);
            }
            return new GroupAuditView(runId, num(snapshot.position()),
                    num(snapshot.timeMillis()), true, List.copyOf(groups),
                    List.copyOf(memberships), List.copyOf(invitations), List.copyOf(posts),
                    List.copyOf(versions), List.copyOf(platformPosts), List.copyOf(frames),
                    List.copyOf(pending), List.copyOf(reports));
        });
    }

    private static List<String> refs(List<?> values) {
        List<String> out = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof GroupModel.Ref ref) {
                out.add(ref.docId() + "#" + ref.version());
            } else if (value instanceof PlatformPostModel.Ref ref) {
                out.add(ref.docId() + "#" + ref.version());
            }
        }
        return out;
    }

    private static InformationModel.State infoOf(WorldRuntime.Snapshot snapshot) {
        return snapshot.information().orElseThrow().information();
    }

    private static MediaModel.State mediaForFeed(WorldRuntime.Snapshot snapshot) {
        if (snapshot.media().isEmpty() || snapshot.media().orElseThrow().states().isEmpty()) {
            return null;
        }
        return snapshot.media().orElseThrow().states().get(0);
    }

    private static InformationModel.Document documentOf(WorldRuntime.Snapshot snapshot,
                                                        String docId) {
        for (InformationModel.Document document : infoOf(snapshot).documents()) {
            if (document.docId().equals(docId)) {
                return document;
            }
        }
        return null;
    }

    /** 一人可见的引用版本。 */
    public record GroupRef(String docId, long version) {
    }

    /** 严格解析的群组动作请求；人物不在请求里。 */
    public record GroupActionRequest(long expectedPosition, String commandId, String taskId,
                                     long dueTimeMillis, String action, String groupId,
                                     String inviteePartyId, String inviteId, boolean accept,
                                     String docId, long basedOnVersion, String title, String body,
                                     List<GroupRef> cites) {
    }

    public SocialPersonView socialView(String personId) throws Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            var snapshot = current.snapshot();
            List<Map<String, Object>> inbox = new ArrayList<>();
            List<Map<String, Object>> outbox = new ArrayList<>();
            List<Map<String, Object>> contacts = new ArrayList<>();
            if (snapshot.social().isEmpty() || snapshot.information().isEmpty()) {
                return new SocialPersonView(personId, num(snapshot.timeMillis()), false,
                        bounded(inbox), bounded(outbox), bounded(contacts),
                        java.util.List.of("当前档案未启用消息模块。"));
            }
            InformationModel.State info = snapshot.information().orElseThrow().information();
            SocialRun run = snapshot.social().orElseThrow();
            SocialRun.SocialView view = current.socialView(personId);
            for (SocialMessageModel.EnvelopeView envelope : view.inbox()) {
                boolean read = view.read(envelope.messageId());
                java.util.Optional<String> recalled = read
                        ? current.recalledSocialBody(personId, envelope.messageId())
                        : java.util.Optional.empty();
                InformationModel.DocumentVersion version = versionOf(info, envelope.docId(),
                        envelope.version());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("messageId", envelope.messageId());
                row.put("from", envelope.from());
                row.put("channel", envelope.channel());
                row.put("title", version == null ? "" : version.title());
                row.put("docId", envelope.docId());
                row.put("version", envelope.version());
                row.put("deliveredAt", envelope.deliveredAt());
                row.put("read", read);
                row.put("bodyAvailable", recalled.isPresent());
                row.put("body", recalled.orElse(""));
                inbox.add(row);
            }
            for (SocialMessageModel.OutboxView item : view.outbox()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("messageId", item.messageId());
                row.put("to", item.to());
                row.put("channel", item.channel());
                row.put("sentAt", item.sentAt());
                row.put("dueAt", item.dueAt());
                row.put("status", item.status() == SocialMessageModel.OutboxStatus.DELIVERED
                        ? "DELIVERED" : item.status() == SocialMessageModel.OutboxStatus.QUEUED
                        ? "QUEUED" : "UNDELIVERED");
                outbox.add(row);
            }
            for (InterviewRun.ContactEntry entry : view.contacts()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("targetPartyId", entry.targetPartyId());
                row.put("channel", entry.channel());
                row.put("description", entry.description());
                row.put("docId", entry.docId());
                row.put("version", entry.version());
                contacts.add(row);
            }
            return new SocialPersonView(personId, num(snapshot.timeMillis()), true,
                    bounded(inbox), bounded(outbox), bounded(contacts),
                    java.util.List.of("只显示本人已送达信封、发件结果与合法联系人；正文需本人实际读完。"));
        });
    }

    private static void countRoot(java.util.Map<DocRef, int[]> counts, DocRef key, int aliases,
                                  int shares, java.util.Map<DocRef, String> org,
                                  String organization) {
        int[] row = counts.computeIfAbsent(key, ignored -> new int[2]);
        row[0] += aliases;
        row[1] += shares;
        org.putIfAbsent(key, organization);
    }

    /**
     * 读取媒体机构、工时和工作流审计；仅供服务器管理员路由调用。
     * @return 同一确认截止的审计数据
     * @throws Exception 世界读取失败
     */
    public MediaAuditView mediaAudit() throws Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            List<Map<String, Object>> organizations = new ArrayList<>();
            List<Map<String, Object>> stories = new ArrayList<>();
            List<Map<String, Object>> works = new ArrayList<>();
            List<Map<String, Object>> reports = new ArrayList<>();
            List<Map<String, Object>> pending = new ArrayList<>();
            if (snapshot.media().isEmpty()) {
                return new MediaAuditView(runId, num(snapshot.position()), num(snapshot.timeMillis()),
                        false, bounded(organizations), bounded(stories), bounded(works),
                        bounded(reports), bounded(pending), List.of("当前档案未启用采编。"));
            }
            MediaRun run = snapshot.media().orElseThrow();
            WorldRuntime.MediaWorkAudit audit = current.mediaWorkAudit().orElseThrow();
            OpeningRegistry registry = current.openingRegistry().orElse(null);
            for (WorldRuntime.MediaBudgetBalance balance : audit.budgets()) {
                MediaModel.Policy policy = run.config().policyOf(balance.organizationPartyId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("organizationPartyId", balance.organizationPartyId());
                row.put("name", partyName(registry, balance.organizationPartyId()));
                row.put("totalMillis", num(balance.totalMillis()));
                row.put("spentMillis", num(balance.spentMillis()));
                row.put("reservedMillis", num(balance.reservedMillis()));
                row.put("availableMillis", num(balance.availableMillis()));
                row.put("templates", policy.enabledTemplates());
                row.put("writeMillis", num(policy.writeMillis()));
                row.put("reviewMillis", num(policy.reviewMillis()));
                row.put("publishMillis", num(policy.publishMillis()));
                organizations.add(row);
            }
            for (MediaModel.State state : run.states()) {
                for (MediaModel.Story story : state.stories()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("organizationPartyId", state.policy().mediaOrganizationPartyId());
                    row.put("storyId", story.storyId());
                    row.put("template", story.template().name());
                    row.put("authorPartyId", story.authorPartyId());
                    row.put("topic", story.topic());
                    row.put("status", story.status().name());
                    row.put("revisionNo", num(story.revisionNo()));
                    row.put("draftVersion", num(story.draftVersion()));
                    row.put("publicVersion", num(story.publicVersion()));
                    row.put("sourceGroups", num(story.sourceGroups()));
                    row.put("approvedBy", story.approvedBy());
                    row.put("updatedAtMillis", num(story.updatedAtMillis()));
                    stories.add(row);
                }
            }
            for (MediaRun.Work work : run.works()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskId", work.taskId()); row.put("personPartyId", work.personPartyId());
                row.put("organizationPartyId", work.organizationPartyId());
                row.put("kind", work.kind()); row.put("storyId", work.storyId());
                row.put("startedAtMillis", num(work.startedAtMillis()));
                row.put("completedAtMillis", num(work.completedAtMillis()));
                row.put("chargedMillis", num(work.chargedMillis()));
                row.put("outcome", work.outcome()); works.add(row);
            }
            for (MediaRun.Report report : run.reports().values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskId", report.taskId()); row.put("personPartyId", report.personPartyId());
                row.put("kind", report.kind()); row.put("accepted", report.accepted());
                row.put("reason", report.reason()); row.put("storyId", report.storyId());
                row.put("timeMillis", num(report.timeMillis()));
                row.put("position", num(report.position())); reports.add(row);
            }
            sortReportsNewestFirst(reports);
            for (WorldRuntime.MediaWorkInFlight work : audit.pending()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskId", work.taskId()); row.put("personPartyId", work.personPartyId());
                row.put("organizationPartyId", work.organizationPartyId());
                row.put("kind", work.kind()); row.put("dueTimeMillis", num(work.dueTimeMillis()));
                pending.add(row);
            }
            return new MediaAuditView(runId, num(snapshot.position()), num(snapshot.timeMillis()),
                    true, bounded(organizations), bounded(stories), bounded(works), bounded(reports),
                    bounded(pending), List.of("工时为本次运行的实验总预算，没有每日自动重置。",
                    "取消控制不另扣采编工时；被取消工作按实际已过时间收费。",
                    "发布不会自动变成人物已读经历。采访、匿名与社交传播尚未接入。"));
        });
    }

    /**
     * 查询指定机构稿件的原始版本与引用；仅供管理员使用。
     * @param organizationPartyId 真实媒体机构标识
     * @param storyId 稿件标识
     * @param expectedPosition 页面所见确认截止，变化时拒绝跨截止拼接
     * @return 同截止的原始版本及证据
     * @throws Exception 稿件不存在或世界读取失败
     */
    public MediaStoryView mediaStory(String organizationPartyId, String storyId, long expectedPosition) throws Exception {
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            if (snapshot.position() != expectedPosition) throw new PositionMismatch(snapshot.position());
            MediaRun run = snapshot.media().orElseThrow(() -> new IllegalArgumentException("当前档案未启用采编"));
            MediaModel.State state = run.stateOf(organizationPartyId);
            if (state == null) throw new IllegalArgumentException("没有此媒体机构");
            MediaModel.Story story = state.stories().stream().filter(s -> s.storyId().equals(storyId))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("没有此稿件"));
            List<Map<String, Object>> versions = new ArrayList<>();
            for (InformationModel.Document document : snapshot.information().orElseThrow().information().documents()) {
                boolean draft = document.docId().equals(story.draftDocId());
                if (!draft && !document.docId().equals(story.publicDocId())) continue;
                for (InformationModel.DocumentVersion version : document.versions()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("kind", draft ? "DRAFT" : "PUBLIC"); row.put("docId", document.docId());
                    row.put("publisherPartyId", document.publisherPartyId());
                    row.put("version", num(version.version())); row.put("title", version.title());
                    row.put("body", version.body()); row.put("sourceDescription", version.publicSourceDescription());
                    row.put("publishedAtMillis", num(version.publishedAt()));
                    row.put("readDurationMillis", num(version.readDurationMillis())); versions.add(row);
                }
            }
            List<Map<String, Object>> citations = new ArrayList<>();
            for (RoleView.ReadingRef ref : story.evidence()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("personPartyId", ref.personPartyId()); row.put("docId", ref.docId());
                row.put("version", num(ref.version())); row.put("index", num(ref.index())); citations.add(row);
            }
            List<Map<String, Object>> roots = new ArrayList<>();
            for (MediaModel.Ref ref : story.roots()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("docId", ref.docId()); row.put("version", num(ref.version())); roots.add(row);
            }
            return new MediaStoryView(runId, num(snapshot.position()), num(snapshot.timeMillis()),
                    organizationPartyId, storyId, bounded(versions), bounded(citations), bounded(roots));
        });
    }

    /** 单场景累计视图；数字均为精确字符串，来源 DecisionModel。 */
    public record SceneState(String sceneId, String slotId, String model, String revision,
                             boolean enabled, String usedCalls, String reservedCalls,
                             String capCalls, String usedInputTokens, String reservedInputTokens,
                             String usedOutputTokens, String reservedOutputTokens,
                             String capTotalTokens) {
    }

    /** 输入超限的安全数值诊断，不含输入正文或提供方凭据。 */
    public record InputSize(String actualChars, String actualBytes, String maxChars, String maxBytes) {
    }

    /** 本轮被跳过的人物与固定原因；仅超限时附数值诊断。 */
    public record SkipReason(String personPartyId, String reason,
                             @com.fasterxml.jackson.annotation.JsonInclude(
                                     com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             InputSize inputSize) {
        /** 保留原两参构造与非超限JSON形状。 */
        public SkipReason(String personPartyId, String reason) {
            this(personPartyId, reason, null);
        }
    }

    /** 预算视图：已结算 + 未结算预留，与冻结上限分开；全部精确字符串。 */
    public record BudgetUsage(String usedCalls, String reservedCalls, String capCalls,
                              String usedInputTokens, String reservedInputTokens,
                              String capInputTokens, String usedOutputTokens,
                              String reservedOutputTokens, String capOutputTokens) {
    }

    /** 单人预算视图。 */
    public record PersonBudget(String personPartyId, BudgetUsage budget) {
    }

    /** 最近一次决定/尝试的只读视图；未知值保持 null。 */
    public record DecisionView(String personPartyId, String actionId, String status, String outcome,
                               String sceneId, String slotId, String model, String revision,
                               String declaredInputTokens, String declaredOutputTokens,
                               String costStatus, String reason, String inputPreview,
                               String inputLength, boolean inputTruncated, String replyPreview,
                               String replyLength, boolean replyTruncated) {
    }

    /**
     * live 运行状态（同 writer 采样、同一确认截止）。
     *
     * @param runId           运行标识
     * @param position        已确认位置（精确字符串）
     * @param timeMillis      世界时点（精确字符串）
     * @param phase           SCRIPTED / IDLE / RUNNING / STOPPING / PAUSED / FAULT
     * @param paused          是否 Host 暂停
     * @param fault           持久故障原因；无故障为 null
     * @param pendingPersons  本轮尚未派发的人物
     * @param physicalInFlight 物理 HTTP 仍在执行的调用数（worker 持有）
     * @param completionSlots  Runtime 完成槽占用数（含超时未返回、已登记未归档）
     * @param runBudget       运行累计预算
     * @param personBudgets   四岗逐人预算
     * @param scenes          五场景累计用量与上限
     * @param skips           本轮跳过原因
     * @param decisions       最近最多 10 条决定
     * @param roundScene      最近一次开始的本轮显式场景；按人物原路由开始或未开始时为 null
     * @param roundPersons    本轮参与人物（本轮开始时冻结）；未开始时为空
     * @param roundCompany    公司治理轮绑定的真实公司；其它轮为 null
     */
    public record LiveStatus(String runId, String position, String timeMillis, String phase,
                             boolean paused, String fault, List<String> pendingPersons,
                             int physicalInFlight, int completionSlots, BudgetUsage runBudget,
                             List<PersonBudget> personBudgets, List<SceneState> scenes,
                             List<SkipReason> skips, List<DecisionView> decisions,
                             String roundScene, List<String> roundPersons, String roundCompany) {
    }

    /** 一轮的 writer 私有状态。 */
    private static final class LiveRound {
        private final String roundId;
        private final LinkedHashSet<String> pending;
        private final Map<String, String> skipped = new LinkedHashMap<>();
        private final Map<String, InputSize> inputSizes = new LinkedHashMap<>();
        /** 本轮显式场景；null 表示沿人物原自动路由。 */
        private final String scene;
        /** 治理轮绑定的真实公司；其它轮为 null。 */
        private final String company;
        private boolean stopping;

        private LiveRound(String roundId, List<String> persons) {
            this(roundId, persons, null, null);
        }

        private LiveRound(String roundId, List<String> persons, String scene, String company) {
            this.roundId = roundId;
            this.pending = new LinkedHashSet<>(persons);
            this.scene = scene;
            this.company = company;
        }
    }

    /**
     * 显式开始一次有界角色轮次；这是本进程唯一的模型调用入口。
     *
     * <p>等价于 {@code startLiveRound(pos, rev, persons, null, null)}：不指定本轮场景，逐字沿用
     * 人物原有自动路由。</p>
     *
     * @param expectedPosition 调用方看到的世界位置
     * @param expectedRevision 调用方看到的场景配置修订
     * @param persons          1..4 个不同四岗人物
     * @throws BusyException            队列满
     * @throws SceneRejected            场景/公司/资格拒绝（本重载不触发）
     * @throws IllegalArgumentException 位置/修订/人物非法（无副作用）
     * @throws IllegalStateException    Host 未接线 live、暂停、故障、已有轮次或有屏障
     * @throws Exception                世界读写失败
     */
    public void startLiveRound(long expectedPosition, long expectedRevision, List<String> persons)
            throws BusyException, SceneRejected, Exception {
        startLiveRound(expectedPosition, expectedRevision, persons, null, null);
    }

    /**
     * 显式指定本轮场景开始一次有界角色轮次。
     *
     * <p>场景只决定本轮 attempt 的模型、Key 槽位与预算配置；人物信息与工具权限仍由真实观察与
     * BEGIN/FINISH 资格决定。{@code scene} 为空时逐字沿用旧自动路由。</p>
     *
     * @param expectedPosition 调用方看到的世界位置
     * @param expectedRevision 调用方看到的场景配置修订
     * @param persons          1..4 个不同人物
     * @param scene            本轮场景；null 表示按人物原路由
     * @param companyId        公司治理轮绑定的真实公司；其它场景必须为 null
     * @throws BusyException            队列满
     * @throws SceneRejected            未知/未接入/未启用的场景、缺公司或公司/资格不符（无副作用）
     * @throws IllegalArgumentException 位置/修订/人物非法（无副作用）
     * @throws IllegalStateException    Host 未接线 live、暂停、故障、已有轮次或有屏障
     * @throws Exception                世界读写失败
     */
    public void startLiveRound(long expectedPosition, long expectedRevision, List<String> persons,
                               String scene, String companyId)
            throws BusyException, SceneRejected, Exception {
        call(() -> {
            startLiveRoundOnWriter(expectedPosition, expectedRevision, persons, scene, companyId);
            return null;
        });
    }

    /**
     * 停止当前轮次：清空未开始名单，已在途继续结算；不宣称提供方未收费。
     *
     * @param expectedPosition 调用方看到的世界位置
     * @throws BusyException            队列满
     * @throws IllegalArgumentException 位置不一致
     * @throws Exception                世界读写失败
     */
    public void stopLiveRound(long expectedPosition) throws BusyException, Exception {
        call(() -> {
            WorldRuntime current = readyNow();
            if (current.snapshot().position() != expectedPosition) {
                throw new PositionMismatch(current.snapshot().position());
            }
            if (liveRound != null) {
                liveRound.stopping = true;
                liveRound.pending.clear();
            }
            return null;
        });
    }

    /**
     * 暂停本轮调度：阻止推进、新派发与 pump；worker 仍只登记完成。
     *
     * @param expectedPosition 调用方看到的世界位置，必须在同一 writer 任务内匹配
     * @throws BusyException   队列满
     * @throws PositionMismatch 位置已变化（无副作用）
     * @throws Exception       读取失败
     */
    public void pauseLive(long expectedPosition) throws BusyException, Exception {
        call(() -> {
            WorldRuntime current = readyNow();
            if (current.snapshot().position() != expectedPosition) {
                throw new PositionMismatch(current.snapshot().position());
            }
            livePaused = true;
            return null;
        });
    }

    /**
     * 解除 Host 暂停；不解除 Runtime 自身的故障暂停，也不清除持久故障。
     *
     * @param expectedPosition 调用方看到的世界位置，必须在同一 writer 任务内匹配
     * @throws BusyException   队列满
     * @throws PositionMismatch 位置已变化（无副作用）
     * @throws IllegalStateException 持久故障尚未通过重启解除
     * @throws Exception       读取失败
     */
    public void resumeLive(long expectedPosition) throws BusyException, Exception {
        call(() -> {
            WorldRuntime current = readyNow();
            if (current.snapshot().position() != expectedPosition) {
                throw new PositionMismatch(current.snapshot().position());
            }
            if (liveFault != null) {
                throw new IllegalStateException("live 故障需要重启，不能靠恢复解除");
            }
            livePaused = false;
            safePump();
            return null;
        });
    }

    /**
     * 保存场景配置：只针对本 Host 持有的 {@link SceneRoutes}，修订检查先于任何轮次副作用。
     *
     * @param expectedRevision 调用方看到的修订号
     * @param updated          五条完整路由
     * @throws BusyException   队列满
     * @throws IllegalArgumentException 路由不合法
     * @throws Exception       写入失败或修订不一致（由 SceneRoutes 抛出）
     */
    public void replaceRoutes(long expectedRevision, List<SceneRoutes.Route> updated)
            throws BusyException, Exception {
        call(() -> {
            if (routes == null) {
                throw new IllegalStateException("当前 Host 不持有场景配置");
            }
            routes.replace(expectedRevision, updated);
            return null;
        });
    }

    /**
     * 读取 live 状态。
     *
     * @return 同 writer、同一确认截止的状态
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public LiveStatus liveStatus() throws BusyException, Exception {
        return call(this::buildLiveStatus);
    }

    // ---------------------------------------------------------------- writer 内部

    private void startLiveRoundOnWriter(long expectedPosition, long expectedRevision,
                                        List<String> persons, String scene, String companyId)
            throws IOException, PositionMismatch, SceneRejected,
            SceneRoutes.RevisionMismatch {
        WorldRuntime current = readyNow();
        if (workers == null || !liveRuntime()) {
            throw new IllegalStateException("当前 Host 未接线 live 场景");
        }
        if (liveFault != null) {
            throw new IllegalStateException("live 故障需要重启");
        }
        if (livePaused) {
            throw new IllegalStateException("Host 已暂停，先恢复再开始");
        }
        if (current.isPaused()) {
            throw new IllegalStateException("世界已因故障暂停，需要重启");
        }
        if (current.snapshot().position() != expectedPosition) {
            throw new PositionMismatch(current.snapshot().position());
        }
        long revision = routes.view().revision();
        if (revision != expectedRevision) {
            throw new SceneRoutes.RevisionMismatch(revision);
        }
        // 可选人物按本轮场景取：显式治理认“真实登记 + 席位/有效经理任职”，其余（含旧三字段
        // 自动路由）逐字沿用原四岗/媒体/社交名单；纯董事不会因为治理名单而进入自动路由。
        boolean governanceScene = SCENE_GOVERNANCE.equals(scene);
        List<String> allowed = (governanceScene ? governancePersons(current.snapshot())
                : livePeople(current.snapshot())).stream()
                .map(LivePerson::personPartyId).toList();
        if (persons == null || persons.isEmpty() || persons.size() > allowed.size()) {
            throw new IllegalArgumentException("本轮人数必须在 1.." + allowed.size());
        }
        Set<String> distinct = new LinkedHashSet<>(persons);
        if (distinct.size() != persons.size()) {
            throw new IllegalArgumentException("同一人物不能在一个轮次里出现两次");
        }
        for (String person : distinct) {
            if (allowed.contains(person)) {
                continue;
            }
            if (governanceScene) {
                throw new SceneRejected(
                        "该人物当前没有任何可治理公司（无真实董事席位或经理任职）：" + person);
            }
            throw new IllegalArgumentException(
                    "该人物不在当前档案的自动路由名单（纯董事只能显式选择公司治理）：" + person);
        }
        if (liveRound != null && (!liveRound.pending.isEmpty()
                || !physicalInFlight.isEmpty())) {
            throw new IllegalStateException("已有活动轮次或未结算调用");
        }
        // 本轮显式场景只决定本次 attempt 的模型/Key/预算配置；人物信息与工具仍由真实观察与
        // BEGIN/FINISH 资格决定，因此这里只校验“配置可用 + 人物真实资格”，不授予任何身份。
        String roundScene = null;
        String roundCompany = null;
        if (scene != null) {
            SceneRoutes.Snapshot view = routes.view();
            SceneRoutes.Route chosen = routeOf(view, scene);
            if (chosen == null) {
                throw new SceneRejected("未知场景");
            }
            if (!wiredScenes(current.snapshot()).contains(scene)) {
                throw new SceneRejected("当前档案没有该场景的执行器");
            }
            if (!chosen.enabled()) {
                throw new SceneRejected("该场景尚未启用");
            }
            if (SCENE_GOVERNANCE.equals(scene)) {
                if (companyId == null || companyId.isBlank()) {
                    throw new SceneRejected("公司治理场景必须绑定真实公司");
                }
                for (String person : distinct) {
                    if (!current.governanceCompanies(person).contains(companyId)) {
                        throw new SceneRejected(
                                "该人物当前没有此公司的董事席位或经理任职：" + person);
                    }
                }
                roundCompany = companyId;
            } else if (companyId != null) {
                throw new SceneRejected("只有公司治理场景可以绑定公司");
            } else {
                for (String person : distinct) {
                    if (!scene.equals(sceneFor(person))) {
                        throw new SceneRejected(
                                "该场景不是此人物当前的模型路由：" + person);
                    }
                }
            }
            roundScene = scene;
        } else if (companyId != null) {
            throw new SceneRejected("未指定本轮场景时不能绑定公司");
        }
        String roundId = "live:" + expectedPosition + ":" + expectedRevision
                + (roundScene == null ? ""
                : ":" + roundScene + (roundCompany == null ? "" : ":" + roundCompany));
        lastSkips = List.of();
        // 先接续旧代次（保守结算），再检查剩余业务屏障与残留工作：
        // 新进程的旧未结算决定不得永久挡住显式接续。
        if (!sessionBegun) {
            current.beginDecisionSession("session:" + roundId);
            sessionBegun = true;
        }
        if (hasUnsettledWork()) {
            throw new IllegalStateException("仍有未结算调用，先等待结算");
        }
        if (current.pendingBarrier().isPresent()) {
            throw new IllegalStateException("仍有未解决屏障");
        }
        liveRound = new LiveRound(roundId,
                fairOrder(current.snapshot().decisions().orElseThrow().state().seed(), persons),
                roundScene, roundCompany);
        lastRoundScene = roundScene;
        lastRoundPersons = List.copyOf(persons);
        lastRoundCompany = roundCompany;
        pumpLive();
    }

    /** 按冻结种子与人物标识的稳定公平顺序排列本轮人物，不采用客户端数组顺序。 */
    private static List<String> fairOrder(long seed, List<String> persons) {
        List<String> ordered = new ArrayList<>(persons);
        ordered.sort(Comparator
                .comparing((String person) -> WorldScheduler.fairOrder(seed, person))
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(ordered);
    }

    /** 是否有任何未结算工作：物理请求、完成槽、逻辑未结算或未派完名单。 */
    private boolean hasUnsettledWork() {
        if (!physicalInFlight.isEmpty()) {
            return true;
        }
        if (runtime != null && runtime.liveCompletionSlots() > 0) {
            return true;
        }
        if (unsettledCount() > 0) {
            return true;
        }
        return liveRound != null && (!liveRound.pending.isEmpty() || liveRound.stopping);
    }

    /** writer 线程唯一的结算与派发入口；异常转为持久故障。 */
    private void safePump() {
        if (workers == null || runtime == null) {
            return;
        }
        try {
            pumpLive();
        } catch (IOException io) {
            liveFault = "IO_FAILURE";
        } catch (RuntimeException unexpected) {
            liveFault = "INTERNAL_FAILURE";
        }
    }

    private void pumpLive() throws IOException {
        WorldRuntime current = readyNow();
        if (closed || workers == null || !liveRuntime() || livePaused || liveFault != null
                || current.isPaused()) {
            return;
        }
        if (!sessionBegun) {
            return;
        }
        current.drainDecisionCallbacks();
        current.expireDecisionTimeouts();
        LiveRound round = liveRound;
        if (round == null) {
            return;
        }
        if (round.pending.isEmpty() && physicalInFlight.isEmpty()
                && current.liveCompletionSlots() == 0 && unsettledCount() == 0) {
            lastSkips = collectSkips(round);
            liveRound = null;
            return;
        }
        int capacity = current.snapshot().decisions().isPresent()
                ? decisionConfigOf(current).limits().maxInFlight() : 0;
        if (capacity < 1) {
            return;
        }
        SceneRoutes.Snapshot snapshot = routes.view();
        Iterator<String> iterator = round.pending.iterator();
        while (iterator.hasNext()) {
            if (physicalInFlight.size() >= capacity
                    || current.liveCompletionSlots() >= capacity
                    || unsettledCount() >= capacity) {
                break;
            }
            String person = iterator.next();
            String scene = round.scene == null ? sceneFor(person) : round.scene;
            if (scene == null) {
                round.skipped.put(person, "PERSON_NOT_ELIGIBLE");
                iterator.remove();
                continue;
            }
            if (SCENE_GOVERNANCE.equals(round.scene)
                    && !current.governanceCompanies(person).contains(round.company)) {
                // 开始后失席/经理任职到期：派发前用当前快照复核，不派发也不重试。
                round.skipped.put(person, "PERSON_NOT_ELIGIBLE");
                iterator.remove();
                continue;
            }
            SceneRoutes.Route route = routeOf(snapshot, scene);
            if (route == null || !route.enabled()) {
                round.skipped.put(person, "SCENE_DISABLED");
                iterator.remove();
                continue;
            }
            if (scenePhysical(scene) >= route.maxConcurrency()) {
                continue;
            }
            String actionId = round.roundId + ":act:" + person;
            String commandId = round.roundId + ":open:" + person;
            DecisionRun.Call call;
            String openResult;
            try {
                openResult = current.openDecisions(commandId,
                        List.of(new DecisionRun.Request(person, actionId, "wake",
                                decisionRoute(route, snapshot.revision()))));
            } catch (WorldRuntime.DecisionInputTooLargeException tooLarge) {
                round.skipped.put(person, "INPUT_TOO_LARGE");
                round.inputSizes.put(person, new InputSize(Long.toString(tooLarge.actualChars()),
                        Long.toString(tooLarge.actualBytes()), Long.toString(tooLarge.maxChars()),
                        Long.toString(tooLarge.maxBytes())));
                iterator.remove();
                continue;
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                // 状态/资格前置拒绝：不留下任何已开未派决定，也不重试。
                round.skipped.put(person, "OPEN_REJECTED");
                iterator.remove();
                continue;
            }
            DecisionModel.Decision opened = DecisionModel.decision(
                    current.snapshot().decisions().orElseThrow().state(), actionId).orElseThrow();
            if (opened.status() != DecisionModel.Status.OPEN) {
                // 预算拒绝已经是持久终态；直接展示原结果，不再派发成 EMPTY。
                round.skipped.put(person, opened.outcome().name());
                iterator.remove();
                continue;
            }
            try {
                WorldRuntime.DispatchResult dispatch =
                        current.dispatchLiveDecision(commandId, actionId);
                if (!"DISPATCHED".equals(dispatch.verdict()) || dispatch.call() == null) {
                    String reason = dispatch.reason() == null || dispatch.reason().isBlank()
                            ? dispatch.verdict()
                            : dispatch.verdict() + ":" + dispatch.reason();
                    round.skipped.put(person, reason);
                    iterator.remove();
                    continue;
                }
                call = dispatch.call();
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                round.skipped.put(person, openResult);
                iterator.remove();
                continue;
            }
            iterator.remove();
            submitCall(call, decisionConfigOf(current).limits().wallTimeoutMillis());
        }
    }

    private void submitCall(DecisionRun.Call call, long timeoutMillis) {
        String scene = call.plan() == null ? "unknown" : call.plan().route().sceneId();
        LiveCompletionInbox.AttemptKey key = LiveCompletionInbox.AttemptKey.of(call);
        physicalInFlight.put(key, scene);
        try {
            workers.execute(() -> {
                try {
                    LiveCompletionInbox.Completion completion =
                            provider.evaluate(call, timeoutMillis);
                    runtime.offerLiveDecisionCompletion(completion);
                } catch (RuntimeException local) {
                    runtime.offerLiveDecisionCompletion(localFailure(call,
                            "LOCAL_PROVIDER_ERROR"));
                } finally {
                    physicalInFlight.remove(key);
                }
            });
        } catch (RejectedExecutionException rejected) {
            physicalInFlight.remove(key);
            runtime.offerLiveDecisionCompletion(localFailure(call, "LOCAL_EXECUTOR_REJECTED"));
        }
    }

    /** 固定形状的本地技术失败：身份取 Call，正文为空，usage 未知。 */
    private static LiveCompletionInbox.Completion localFailure(DecisionRun.Call call,
                                                              String code) {
        return LiveCompletionInbox.Completion.failure(code, new DecisionModel.Reply(
                call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                call.recoveryGeneration(), "", null, "LOCAL_HOST"));
    }

    private boolean liveRuntime() {
        if (runtime == null) {
            return false;
        }
        WorldRuntime.Snapshot snapshot = runtime.snapshot();
        return snapshot.decisions().isPresent() && snapshot.decisions().get().state().config().live();
    }

    private DecisionModel.Config decisionConfigOf(WorldRuntime current) {
        return current.snapshot().decisions().orElseThrow().state().config();
    }

    private int unsettledCount() {
        WorldRuntime current = runtime;
        if (current == null) {
            return 0;
        }
        WorldRuntime.Snapshot snapshot = current.snapshot();
        if (snapshot.decisions().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (DecisionModel.Decision decision : snapshot.decisions().get().state().decisions()) {
            for (DecisionModel.Attempt attempt : decision.attempts()) {
                if (!attempt.settledDone()) {
                    count++;
                }
            }
        }
        return count;
    }

    private String sceneFor(String person) {
        return livePeople(runtime.snapshot()).stream()
                .filter(row -> row.personPartyId().equals(person)).map(LivePerson::sceneId)
                .findFirst().orElse(null);
    }

    private static SceneRoutes.Route routeOf(SceneRoutes.Snapshot snapshot, String sceneId) {
        for (SceneRoutes.Route route : snapshot.routes()) {
            if (route.scene().id().equals(sceneId)) {
                return route;
            }
        }
        return null;
    }

    private static DecisionModel.Route decisionRoute(SceneRoutes.Route route, long revision) {
        return new DecisionModel.Route(route.scene().id(), route.slotId(), route.model(),
                revision, route.maxCalls(), route.maxTotalTokens(), route.maxOutputTokens(),
                route.maxConcurrency());
    }

    private LiveStatus buildLiveStatus() {
        WorldRuntime current = readyNow();
        WorldRuntime.Snapshot snapshot = current.snapshot();
        boolean live = workers != null && liveRuntime();
        String phase;
        if (!live) {
            phase = "SCRIPTED";
        } else if (liveFault != null || current.isPaused()) {
            phase = "FAULT";
        } else if (livePaused) {
            phase = "PAUSED";
        } else if (liveRound != null && liveRound.stopping) {
            phase = "STOPPING";
        } else if (liveRound != null) {
            phase = "RUNNING";
        } else {
            phase = "IDLE";
        }
        List<String> pending = liveRound == null ? List.of()
                : List.copyOf(liveRound.pending);
        List<SkipReason> skips = liveRound != null ? collectSkips(liveRound) : lastSkips;
        List<SceneState> scenes = new ArrayList<>();
        if (snapshot.decisions().isPresent() && routes != null) {
            SceneRoutes.Snapshot view = routes.view();
            DecisionModel.State model = snapshot.decisions().get().state();
            for (SceneRoutes.Route route : view.routes()) {
                DecisionModel.Usage used = DecisionModel.sceneUsage(model, route.scene().id());
                DecisionModel.Usage reserved =
                        DecisionModel.sceneReserved(model, route.scene().id());
                scenes.add(new SceneState(route.scene().id(), route.slotId(), route.model(),
                        Long.toString(view.revision()), route.enabled(),
                        Long.toString(used.attempts()), Long.toString(reserved.attempts()),
                        Integer.toString(route.maxCalls()), Long.toString(used.inputTokens()),
                        Long.toString(reserved.inputTokens()), Long.toString(used.outputTokens()),
                        Long.toString(reserved.outputTokens()),
                        Integer.toString(route.maxTotalTokens())));
            }
        }
        List<DecisionView> decisions = new ArrayList<>();
        BudgetUsage runBudget = null;
        List<PersonBudget> personBudgets = List.of();
        if (snapshot.decisions().isPresent()) {
            DecisionModel.State model = snapshot.decisions().get().state();
            Map<String, String> failureOutcomes = new java.util.HashMap<>();
            for (WorldRuntime.DecisionAudit audit : current.decisionAudits()) {
                if (audit.outcome().startsWith("TECHNICAL_FAILED:")) {
                    failureOutcomes.put(audit.attemptId(), audit.outcome());
                }
            }
            DecisionModel.Usage runReserved = DecisionModel.Usage.zero();
            Map<String, DecisionModel.Usage> personReserved = new java.util.TreeMap<>();
            for (DecisionModel.Decision decision : model.decisions()) {
                for (DecisionModel.Attempt attempt : decision.attempts()) {
                    if (attempt.settledDone()) {
                        continue;
                    }
                    runReserved = runReserved.plus(attempt.reserved());
                    personReserved.merge(decision.personPartyId(), attempt.reserved(),
                            DecisionModel.Usage::plus);
                }
            }
            runBudget = budgetUsage(model.runUsage(), runReserved,
                    model.config().budget().runCap());
            List<PersonBudget> budgets = new ArrayList<>();
            for (String person : model.config().budget().personCap().keySet().stream().sorted().toList()) {
                DecisionModel.Usage used = model.personUsage().getOrDefault(person,
                        DecisionModel.Usage.zero());
                DecisionModel.Usage cap = model.config().budget().personCap().getOrDefault(person,
                        DecisionModel.Usage.zero());
                budgets.add(new PersonBudget(person, budgetUsage(used,
                        personReserved.getOrDefault(person, DecisionModel.Usage.zero()), cap)));
            }
            personBudgets = List.copyOf(budgets);
            List<DecisionModel.Decision> all =
                    snapshot.decisions().get().state().decisions();
            int from = Math.max(0, all.size() - 10);
            for (int index = all.size() - 1; index >= from; index--) {
                DecisionModel.Decision decision = all.get(index);
                String attemptId = decision.attempts().isEmpty()
                        ? null : decision.lastAttempt().attemptId();
                String auditOutcome = attemptId == null ? null : failureOutcomes.get(attemptId);
                decisions.add(decisionView(decision, auditOutcome));
            }
        }
        return new LiveStatus(runId, Long.toString(snapshot.position()),
                Long.toString(snapshot.timeMillis()), phase, livePaused, liveFault,
                pending, physicalInFlight.size(), current.liveCompletionSlots(), runBudget,
                personBudgets, scenes, skips, decisions,
                lastRoundScene, lastRoundPersons, lastRoundCompany);
    }

    /** 预算视图：已结算 + 未结算预留 + 冻结上限。 */
    private static BudgetUsage budgetUsage(DecisionModel.Usage used, DecisionModel.Usage reserved,
                                           DecisionModel.Usage cap) {
        return new BudgetUsage(Long.toString(used.attempts()),
                Long.toString(reserved.attempts()), Long.toString(cap.attempts()),
                Long.toString(used.inputTokens()), Long.toString(reserved.inputTokens()),
                Long.toString(cap.inputTokens()), Long.toString(used.outputTokens()),
                Long.toString(reserved.outputTokens()), Long.toString(cap.outputTokens()));
    }

    /** 同场景物理 HTTP 并发数（只看 worker 仍持有者）。 */
    private int scenePhysical(String sceneId) {
        int count = 0;
        for (String scene : physicalInFlight.values()) {
            if (scene.equals(sceneId)) {
                count++;
            }
        }
        return count;
    }

    private static DecisionView decisionView(DecisionModel.Decision decision,
                                             String failureOutcome) {
        DecisionModel.Attempt attempt = decision.attempts().isEmpty()
                ? null : decision.lastAttempt();
        String scene = decision.plan() == null ? null : decision.plan().route().sceneId();
        String slot = decision.plan() == null ? null : decision.plan().route().slotId();
        String model = decision.plan() == null ? null : decision.plan().route().model();
        String revision = decision.plan() == null ? null
                : Long.toString(decision.plan().route().revision());
        DecisionModel.Usage declared = attempt == null ? null : attempt.declaredUsage();
        String input = decision.inputText();
        String reply = attempt == null ? "" : attempt.replyBody();
        String reason = attempt == null
                ? (decision.outcome() == DecisionModel.Outcome.NONE
                ? failureOutcome : decision.outcome().name())
                : (attempt.reason().isBlank() && failureOutcome == null ? null
                : (failureOutcome != null && (attempt.reason().isBlank()
                || "UNKNOWN_USAGE".equals(attempt.reason())) ? failureOutcome
                : attempt.reason()));
        return new DecisionView(decision.personPartyId(), decision.actionId(),
                decision.status().name(), attempt == null ? decision.outcome().name()
                : attempt.outcome().name(), scene, slot, model, revision,
                declared == null ? null : Long.toString(declared.inputTokens()),
                declared == null ? null : Long.toString(declared.outputTokens()),
                declared == null || declared.costStatus() == null ? null
                        : declared.costStatus().name(),
                reason,
                preview(input), Integer.toString(input.length()), input.length() > PREVIEW_LIMIT,
                preview(reply), Integer.toString(reply.length()), reply.length() > PREVIEW_LIMIT);
    }

    private static List<SkipReason> collectSkips(LiveRound round) {
        List<SkipReason> skips = new ArrayList<>();
        round.skipped.forEach((person, reason) -> skips.add(new SkipReason(person, reason,
                round.inputSizes.get(person))));
        return List.copyOf(skips);
    }

    private static String preview(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= PREVIEW_LIMIT ? value : value.substring(0, PREVIEW_LIMIT);
    }

    // ================================================================ 18c 工程经营（Web 第一切片）

    /** 旧档（schema ≤17 或未加载工程子集）的统一说明：本页不显示任何样例数据。 */
    public static final String ENGINEERING_DISABLED_NOTE =
            "此运行未启用工程经营（schema ≤17 或未加载工程子集）：本页不显示样例数据，也不补零。";

    /**
     * 周期会议出席工具：运行层公开入口已接受 ATTEND，但 v6 目录未单独列出；
     * Web 只为真实董事补充该入口，权限仍由 BEGIN 时的席位/会议状态复核。
     */
    public static final String PERIODIC_ATTEND_TOOL = "periodic.attend";
    /**
     * 章程公司主席专用的经理任免召集入口：与工程董事会同一套任免机制，
     * 只在真实治理董事会里开一次任免议题会议，不新建角色或无限期任职。
     */
    public static final String BOARD_CONVENE_APPOINTMENT_TOOL = "board.conveneAppointment";

    /**
     * 只读解析持久化待办载荷用的映射器；不持有世界状态。
     * BEGIN 包装只当作「待执行等待中的包装」，绝不当作已完成；种类常量取核心真值
     * {@link PendingTask#ENGINEERING_BEGIN_V1}，不在 Web 侧重复声明字符串。
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper PAYLOAD_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * BEGIN 包装的严格只读视图：字段缺失或类型不符就返回 null（未知即未知，不做猜测）。
     *
     * @param targetKind  包装内层真实任务种类
     * @param dueMillis   包装声明的到期时点
     * @param payload     内层原始 JSON 字符串
     */
    record BeginWrapper(String targetKind, long dueMillis, String payload) {
    }

    /** 页面只读：一次选择一家公司；公司列表与所选公司来自同一快照。 */
    public record EngineeringOption(String companyId, String name, String template,
                                    String managerPersonId) {
    }

    /**
     * 工程/制造公司 God 视图：一个 writer 任务、一份快照内派生全部区块。
     *
     * @param runId          运行标识
     * @param position       已确认位置（字符串）
     * @param timeMillis     世界时点（字符串）
     * @param phase          最后阶段
     * @param capturedAtMillis 本机墙钟读取时刻（不是世界时间）
     * @param enabled        当前运行是否有工程子集
     * @param note           未启用说明；启用时为 null
     * @param companies      真实可选公司（制造 + 工程）
     * @param companyId      当前选择
     * @param companyName    当前公司名称
     * @param template       ENGINEERING / MANUFACTURING
     * @param report         报表口径（金额为精确字符串；未实现字段为 null）
     * @param seats          董事会席位
     * @param appointments   该公司真实任职
     * @param contracts      合同（含逐合同 claims）
     * @param workOrders     真实工单（开工会留下）
     * @param budgets        已生效预算上限
     * @param commitments    预算占用
     * @param meetings       真实董事会会议
     * @param requests       经理客户请求（意图）
     * @param responses      客户回应（外部事实）
     * @param payments       真实付款（到账）
     * @param waivers        具体债权豁免（授权与消耗审计）
     * @param tasks          待执行工程待办（到期才落账）
     * @param reports        真实回报
     * @param periodic       本公司周期治理只读投影（采样、评分、披露、更正与迟报）
     * @param sources        每个区块的真实来源
     * @param notes          口径说明
     */
    public record EngineeringView(String runId, String position, String timeMillis, String phase,
                                  String capturedAtMillis, boolean enabled, String note,
                                  List<EngineeringOption> companies, String companyId,
                                  String companyName, String template,
                                  Map<String, Object> report, Section seats,
                                  Section appointments, Section contracts, Section workOrders,
                                  Section budgets, Section commitments, Section meetings,
                                  Section requests, Section responses, Section payments,
                                  Section waivers, Section tasks, Section reports,
                                  Map<String, Object> periodic,
                                  Map<String, String> sources, List<String> notes) {
        /** 冻结集合，避免调用方改响应。 */
        public EngineeringView {
            companies = List.copyOf(companies);
            sources = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            notes = List.copyOf(notes);
        }
    }

    /** 个人视角：只含本人在任/占席的公司；其他公司、God 审计与未来计划不出现。 */
    public record PersonEngineeringView(String personPartyId, String position, String timeMillis,
                                        String capturedAtMillis, boolean enabled, String note,
                                        List<Map<String, Object>> companies, List<String> notes) {
        /** 冻结集合。 */
        public PersonEngineeringView {
            companies = List.copyOf(companies);
            notes = List.copyOf(notes);
        }
    }

    /**
     * 一条由可信会话提交的工程命令；actor 不在字段里，只由会话身份决定。
     *
     * @param expectedPosition  客户端看到的位置
     * @param commandId         幂等命令标识
     * @param taskId            任务标识
     * @param dueTimeMillis     到期时点
     * @param companyId         目标工程公司
     * @param tool              真实工具标识（RoleTradingView 常量或 board.conveneWaiver）
     * @param contractId        合同
     * @param workId            开工标识
     * @param trancheIndex      期次
     * @param units             数量
     * @param amount            金额（十进制字符串）
     * @param purpose           用途/证据引用
     * @param authorizationRef  董事会豁免授权引用
     * @param meetingId         会议
     * @param materialId        会议内材料标识
     * @param docId             材料来源资料
     * @param docVersion        材料固定版本
     * @param vote              YES / NO / ABSTAIN
     * @param recused           是否自主回避
     * @param appointmentId     任免记录标识
     * @param appointPersonId   被任免人
     * @param appointRole       岗位
     * @param appointAction     APPOINT / REVOKE
     * @param effectiveAt       生效时点
     * @param endsAt            期限（不含）；撤销为 0
     * @param waiverId          豁免请求标识
     * @param customerPartyId   客户主体
     * @param laborLimit        人工预算上限（字符串）
     * @param procurementLimit  采购预算上限（字符串）
     * @param period            预算期间
     * @param materialDeadline  材料截止
     * @param opensAt           开会时点
     * @param closesAt          表决截止
     * @param durationMillis    声明工时；null 表示用内核默认
     */
    public record EngineeringCommand(long expectedPosition, String commandId, String taskId,
                                     long dueTimeMillis, String companyId, String tool,
                                     String contractId, String workId, int trancheIndex,
                                     long units, String amount, String purpose,
                                     String authorizationRef, String meetingId,
                                     String materialId, String docId, long docVersion,
                                     String vote, boolean recused, String appointmentId,
                                     String appointPersonId, String appointRole,
                                     String appointAction, long effectiveAt, long endsAt,
                                     String waiverId, String customerPartyId,
                                     String laborLimit, String procurementLimit, long period,
                                     long materialDeadline, long opensAt, long closesAt,
                                     Long durationMillis, String draftId, long draftVersion,
                                     String publisherPersonId, long expiresAt,
                                     String authorizationId, String note,
                                     List<PeriodicMetricInput> metrics) {
    }

    /**
     * 周期评分的一维输入：维度、分数（十进制字符串）与本期真实证据引用。
     * asOf 一律由服务端取采样截至时点，页面不能自填“全局位置”。
     */
    public record PeriodicMetricInput(String dimension, String score, String sourceRef) {
    }

    /** 排程回执：只是受理，不等于已执行。 */
    public record EngineeringActionView(String runId, String position, String timeMillis,
                                        String companyId, String tool, String receipt) {
    }

    /** 每个区块的真实来源说明（God 与个人视图共用）。 */
    private static Map<String, String> engineeringSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("report", "唯一现金账户表 + EngineeringModel.Report（纯核派生，不二次加总）");
        sources.put("contracts", "EngineeringModel.State.contracts 与同合同 claims");
        sources.put("workOrders", "EngineeringModel.State.workOrders（真实开工工单）");
        sources.put("budgets", "boardOfCompany(...).board().budgets（真实董事议决上限）");
        sources.put("commitments", "boardOfCompany(...).board().commitments（真实预算占用）");
        sources.put("meetings", "boardOfCompany(...).board().meetings（真实会议与表决）");
        sources.put("requests", "EngineeringRun.customer.requests（经理请求＝意图）");
        sources.put("responses", "EngineeringRun.customer.responses（客户外部回应）");
        sources.put("payments", "EngineeringRun.customer.payments（真实双边付款）");
        sources.put("waivers", "EngineeringRun.waivers（唯一授权与消耗权威）");
        sources.put("tasks", "Snapshot.pending（到期才落账；排程不等于已执行）");
        sources.put("reports", "EngineeringRun.reports + BoardRun.reports（accepted/reason 原样）");
        sources.put("periodic", "EngineeringRun.periodic（真实采样/评分/草稿/批准/授权/发布/MISSED）"
                + " + InformationModel 公开版本正文");
        sources.put("time", "position/timeMillis 是世界位置与时点；capturedAtMillis 是本机墙钟读取时刻");
        return java.util.Collections.unmodifiableMap(sources);
    }

    private static BigDecimal zeroMoney() {
        return BigDecimal.ZERO.setScale(2);
    }

    /**
     * God 工程视图（单 writer 任务、单快照）。
     *
     * @param requestedCompanyId 选择的公司；空字符串表示按真实候选稳定取首个
     * @return 视图
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public EngineeringView engineeringView(String requestedCompanyId)
            throws BusyException, Exception {
        String requested = requestedCompanyId == null ? "" : requestedCompanyId;
        return call(() -> engineeringViewOf(readyNow(), requested));
    }

    private EngineeringView engineeringViewOf(WorldRuntime current, String requested) {
        WorldRuntime.Snapshot snapshot = current.snapshot();
        OpeningRegistry registry = current.openingRegistry().orElse(null);
        EngineeringRun run = snapshot.engineering().orElse(null);
        String captured = num(System.currentTimeMillis());
        String position = num(snapshot.position());
        String time = num(snapshot.timeMillis());

        List<EngineeringOption> options = new ArrayList<>();
        String manufacturingCompany = snapshot.governance()
                .map(board -> board.board().charter().companyPartyId()).orElse(null);
        if (manufacturingCompany != null) {
            options.add(new EngineeringOption(manufacturingCompany,
                    partyName(registry, manufacturingCompany), "MANUFACTURING", null));
        }
        if (run != null) {
            for (String companyId : run.companyIds()) {
                options.add(new EngineeringOption(companyId, partyName(registry, companyId),
                        "ENGINEERING", managerOf(snapshot, companyId)));
            }
        }
        String selected = requested;
        if (selected.isEmpty() && !options.isEmpty()) {
            selected = options.get(0).companyId();
        }
        EngineeringOption option = null;
        for (EngineeringOption candidate : options) {
            if (candidate.companyId().equals(selected)) {
                option = candidate;
                break;
            }
        }
        if (option == null) {
            throw new IllegalArgumentException("没有这家公司，或当前运行未登记它");
        }

        List<String> notes = new ArrayList<>();
        if (run == null) {
            notes.add(ENGINEERING_DISABLED_NOTE);
        }
        notes.add("金额并排显示，不能相加当收入：现金是账户余额，AR 当期与质保是应收，收入只取合同 claims。");
        notes.add("客户请求只是意图；客户回应是外部事实；只有 payments 才是真实到账。");
        notes.add("董事会授权不等于已执行：waivers 里 authorized 与 consumed 分列。");
        notes.add("开工不等于完工：工单状态与合同 completedUnits/acceptedUnits/invoicedTranches 分列。");
        notes.add("供应商应付为聚合口径（累计发生−累计支付），不按供应商拆分。");
        notes.add("周期治理只显示真实采样、评分、草稿、批准、授权与公开版本；已排程不等于已执行，"
                + "未到期/未发生的截止与披露不补。");
        notes.add("公开财报正文原样取自已发布版本；累计收入/累计成本是开运以来口径，不是单期发生额。");

        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> seatRows = new ArrayList<>();
        List<Map<String, Object>> appointmentRows = new ArrayList<>();
        List<Map<String, Object>> contractRows = new ArrayList<>();
        List<Map<String, Object>> workOrderRows = new ArrayList<>();
        List<Map<String, Object>> budgetRows = new ArrayList<>();
        List<Map<String, Object>> commitmentRows = new ArrayList<>();
        List<Map<String, Object>> meetingRows = new ArrayList<>();
        List<Map<String, Object>> requestRows = new ArrayList<>();
        List<Map<String, Object>> responseRows = new ArrayList<>();
        List<Map<String, Object>> paymentRows = new ArrayList<>();
        List<Map<String, Object>> waiverRows = new ArrayList<>();
        List<Map<String, Object>> taskRows = new ArrayList<>();
        List<Map<String, Object>> reportRows = new ArrayList<>();

        // ---- 董事会（制造来自 governance 单例，工程来自 engineering.boards：同一个统一查询）
        BoardRun board = current.boardOfCompany(selected).orElse(null);
        if (board != null) {
            BoardModel.Charter charter = board.board().charter();
            for (BoardModel.Seat seat : charter.seats()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seatId", seat.seatId());
                row.put("partyId", seat.partyId());
                row.put("partyName", partyName(registry, seat.partyId()));
                row.put("chair", seat.seatId().equals(charter.chairSeatId()));
                seatRows.add(row);
            }
            List<BoardModel.Meeting> meetings = new ArrayList<>(board.board().meetings().values());
            meetings.sort(Comparator.comparingLong(BoardModel.Meeting::createdAt)
                    .thenComparing(BoardModel.Meeting::meetingId).reversed());
            for (BoardModel.Meeting meeting : meetings) {
                meetingRows.add(meetingRow(meeting));
            }
            for (BoardModel.Budget budget : board.board().budgets()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("period", num(budget.period()));
                row.put("category", budget.category().name());
                row.put("cap", dec(budget.cap()));
                budgetRows.add(row);
            }
            for (BoardModel.Commitment commitment : board.board().commitments()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("commitmentId", commitment.commitmentId());
                row.put("period", num(commitment.period()));
                row.put("category", commitment.category().name());
                row.put("reserved", dec(commitment.reserved()));
                row.put("spent", dec(commitment.spent()));
                row.put("released", dec(commitment.released()));
                row.put("outstanding", dec(commitment.outstanding()));
                row.put("status", commitment.status().name());
                commitmentRows.add(row);
            }
            for (BoardRun.Report entry : board.reports().values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", "GOVERNANCE");
                row.put("taskId", entry.taskId());
                row.put("commandId", entry.commandId());
                row.put("kind", entry.kind());
                row.put("accepted", entry.accepted());
                row.put("reason", entry.reason());
                row.put("subjectId", entry.meetingId());
                row.put("timeMillis", num(entry.timeMillis()));
                row.put("position", num(entry.position()));
                reportRows.add(row);
            }
            report.put("charterQuorum", Integer.toString(charter.quorum()));
            report.put("chairSeatId", charter.chairSeatId());
            report.put("meetingCount", Integer.toString(meetings.size()));
        }

        // ---- 该公司真实任职（RoleAccess，撤销只结束授权）
        RoleRun roles = snapshot.roles().orElse(null);
        if (roles != null) {
            for (RoleAccess.Appointment appointment : roles.access().appointments()) {
                if (!appointment.organizationPartyId().equals(selected)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("appointmentId", appointment.id());
                row.put("personPartyId", appointment.personPartyId());
                row.put("personName", partyName(registry, appointment.personPartyId()));
                row.put("role", appointment.role().name());
                row.put("startsAt", num(appointment.startsAt()));
                row.put("endsAt", num(appointment.endsAt()));
                row.put("effectiveEnd", num(appointment.effectiveEnd()));
                row.put("revokedAt", appointment.revokedAt() == null
                        ? null : num(appointment.revokedAt()));
                row.put("active", appointment.activeAt(snapshot.timeMillis()));
                row.put("status", appointmentStatus(appointment, snapshot.timeMillis()));
                appointmentRows.add(row);
            }
            appointmentRows.sort(Comparator.comparing(row -> String.valueOf(row.get("appointmentId"))));
        }

        EngineeringModel.State state = run == null ? null : run.stateOf(selected);
        if ("ENGINEERING".equals(option.template()) && state != null) {
            WorldRuntime.CompanyAudit audit = current.companyAudit(selected).orElse(null);
            WorldRuntime.CustomerAudit customer = current.customerAudit(selected).orElse(null);
            if (audit != null) {
                report.put("companyCash", dec(audit.companyCash()));
                report.put("arCurrent", dec(audit.arCurrent()));
                report.put("arRetention", dec(audit.arRetention()));
                report.put("payable", dec(audit.payable()));
                report.put("allowance", dec(audit.allowance()));
                report.put("obligation", dec(audit.obligation()));
                report.put("equity", dec(audit.equity()));
                report.put("balanced", audit.balanced());
                report.put("contractCount", Integer.toString(audit.contractCount()));
                report.put("workOrderCount", Integer.toString(audit.workOrderCount()));
            }
            BigDecimal revenue = zeroMoney();
            BigDecimal cogs = zeroMoney();
            BigDecimal impairment = zeroMoney();
            BigDecimal recovery = zeroMoney();
            BigDecimal termination = zeroMoney();
            BigDecimal waiverLoss = zeroMoney();
            for (EngineeringModel.Claim claim : state.claims().values()) {
                revenue = revenue.add(claim.revenue());
                cogs = cogs.add(claim.cogs());
                impairment = impairment.add(claim.impairmentLoss());
                recovery = recovery.add(claim.recoveryIncome());
                termination = termination.add(claim.terminationLoss());
                waiverLoss = waiverLoss.add(claim.waiverLoss());
            }
            report.put("revenue", dec(revenue));
            report.put("cogs", dec(cogs));
            report.put("impairmentLoss", dec(impairment));
            report.put("recoveryIncome", dec(recovery));
            report.put("terminationLoss", dec(termination));
            report.put("waiverLoss", dec(waiverLoss));
            report.put("payableScope", "AGGREGATE_SUPPLIER");
            if (customer != null) {
                report.put("customerAccountId", customer.customerAccountId());
                report.put("period", num(customer.period()));
                report.put("periodBudgetLeft", dec(customer.periodBudgetLeft()));
                report.put("periodClosed", customer.periodClosed());
                report.put("memo", dec(customer.memo()));
                for (EngineeringRun.Customer.Request request : customer.requests()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("requestId", request.requestId());
                    row.put("contractId", request.contractId());
                    row.put("trancheIndex", Integer.toString(request.trancheIndex()));
                    row.put("kind", request.kind());
                    row.put("atMillis", num(request.atMillis()));
                    row.put("personPartyId", request.personPartyId());
                    row.put("answered", request.answered());
                    row.put("answeredByEventId", request.answeredByEventId());
                    row.put("answeredAtMillis", request.answeredAtMillis() == 0
                            ? null : num(request.answeredAtMillis()));
                    requestRows.add(row);
                }
                for (EngineeringRun.Customer.Response response : customer.responses()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("eventId", response.eventId());
                    row.put("contractId", response.contractId());
                    row.put("trancheIndex", Integer.toString(response.trancheIndex()));
                    row.put("kind", response.kind());
                    row.put("outcome", response.outcome());
                    row.put("requestId", response.requestId());
                    row.put("atMillis", num(response.atMillis()));
                    row.put("amount", dec(response.amount()));
                    row.put("currentOutstanding", dec(response.currentOutstanding()));
                    row.put("retentionOutstanding", dec(response.retentionOutstanding()));
                    row.put("reason", response.reason());
                    responseRows.add(row);
                }
                for (EngineeringRun.Customer.Payment payment : customer.payments()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("eventId", payment.eventId());
                    row.put("poolAccountId", payment.poolAccountId());
                    row.put("period", num(payment.period()));
                    row.put("contractId", payment.contractId());
                    row.put("trancheIndex", Integer.toString(payment.trancheIndex()));
                    row.put("kind", payment.kind());
                    row.put("amount", dec(payment.amount()));
                    row.put("atMillis", num(payment.atMillis()));
                    paymentRows.add(row);
                }
                for (WorldRuntime.WaiverView waiver : customer.waivers()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("waiverId", waiver.waiverId());
                    row.put("contractId", waiver.contractId());
                    row.put("customerPartyId", waiver.customerPartyId());
                    row.put("amount", dec(waiver.amount()));
                    row.put("purpose", waiver.purpose());
                    row.put("authorized", waiver.authorized());
                    row.put("consumed", waiver.consumed());
                    row.put("authorizationRef", waiver.authorizationRef());
                    row.put("meetingId", waiver.meetingId());
                    waiverRows.add(row);
                }
            }
            for (EngineeringModel.Contract contract : state.contracts().values()) {
                EngineeringModel.Claim claim = state.claims().get(contract.contractId());
                long totalUnits = 0L;
                for (long units : contract.terms().trancheUnits()) {
                    totalUnits += units;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("contractId", contract.contractId());
                row.put("unitPrice", dec(contract.unitPrice()));
                row.put("unitCost", dec(contract.unitCost()));
                row.put("advanceBp", Integer.toString(contract.terms().advanceBp()));
                row.put("retentionBp", Integer.toString(contract.terms().retentionBp()));
                row.put("netDays", Integer.toString(contract.terms().netDays()));
                row.put("retentionNetDays", Integer.toString(contract.terms().retentionNetDays()));
                row.put("totalUnits", num(totalUnits));
                row.put("completedUnits", num(contract.completedUnits()));
                row.put("acceptedUnits", num(contract.acceptedUnits()));
                row.put("invoicedTranches", Integer.toString(contract.invoicedTranches()));
                row.put("advanceReceived", dec(contract.advanceReceived()));
                row.put("retentionWithheld", dec(contract.retentionWithheld()));
                row.put("signedAt", num(contract.signedAt()));
                row.put("status", contract.status().name());
                if (claim != null) {
                    row.put("revenue", dec(claim.revenue()));
                    row.put("cogs", dec(claim.cogs()));
                    row.put("contractAsset", dec(claim.contractAsset()));
                    row.put("contractLiability", dec(claim.contractLiability()));
                    row.put("arCurrent", dec(claim.arCurrent()));
                    row.put("arRetention", dec(claim.arRetention()));
                    row.put("allowance", dec(claim.allowance()));
                    row.put("memo", dec(claim.memo()));
                }
                contractRows.add(row);
            }
            contractRows.sort(Comparator.comparing(
                    (Map<String, Object> row) -> String.valueOf(row.get("contractId"))));
            for (EngineeringModel.WorkOrder order : state.workOrders().values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("workId", order.workId());
                row.put("contractId", order.contractId());
                row.put("units", num(order.units()));
                row.put("startedAt", num(order.startedAt()));
                row.put("completesAt", num(order.completesAt()));
                row.put("totalCost", dec(order.totalCost()));
                row.put("laborCost", dec(order.laborCost()));
                row.put("remainingCost", dec(order.remainingCost()));
                row.put("acceptedUnits", num(order.acceptedUnits()));
                row.put("status", order.status().name());
                workOrderRows.add(row);
            }
            for (EngineeringRun.Report entry : run.reports().values()) {
                if (!entry.companyPartyId().equals(selected)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", "ENGINEERING");
                row.put("taskId", entry.taskId());
                row.put("commandId", entry.commandId());
                row.put("kind", entry.kind());
                row.put("accepted", entry.accepted());
                row.put("reason", entry.reason());
                row.put("subjectId", entry.companyPartyId());
                row.put("timeMillis", num(entry.timeMillis()));
                row.put("position", num(entry.position()));
                reportRows.add(row);
            }
            Set<String> engineeringKinds = Set.of(PendingTask.ENGINEERING_BOARD_V1,
                    PendingTask.BOARD_ATTEND_V1, PendingTask.ENGINEERING_ACTION_V1,
                    PendingTask.CUSTOMER_EVENT_V1);
            boolean[] unattributed = {false};
            taskRows.addAll(pendingTaskRows(snapshot, selected, engineeringKinds, unattributed));
            if (unattributed[0]) {
                notes.add("另有 " + unattributed[0] + " 条工程待办没有可归属的公司字段，未在本页显示。");
            }
        } else {
            // 制造模板：经营明细沿用制造台账，工程专属区块明确标注未实现。
            ManufacturingRun manufacturing = snapshot.manufacturing().orElse(null);
            if (manufacturing != null) {
                try {
                    ManufacturingModel.Report value = ManufacturingModel.report(
                            manufacturing.ledger(), snapshot.accounts(),
                            dividendPosition(snapshot.dividends().orElse(null)));
                    report.put("companyCash", dec(value.companyCash()));
                    report.put("arCurrent", dec(value.receivableOutstanding()));
                    report.put("payable", dec(value.payableOutstanding()));
                    report.put("revenue", dec(value.revenue()));
                    report.put("cogs", dec(value.costOfGoodsSold()));
                    report.put("equity", dec(value.equity()));
                    report.put("balanced", value.balanced());
                    report.put("templateNote", "MANUFACTURING_TEMPLATE");
                } catch (RuntimeException invalid) {
                    notes.add("制造报表派生失败：" + (invalid.getMessage() == null
                            ? invalid.getClass().getSimpleName() : invalid.getMessage()));
                }
                List<ManufacturingModel.SalesContract> contracts =
                        new ArrayList<>(manufacturing.ledger().contracts().values());
                contracts.sort(Comparator.comparingLong(ManufacturingModel.SalesContract::signedAt)
                        .thenComparing(ManufacturingModel.SalesContract::contractId).reversed());
                for (ManufacturingModel.SalesContract contract : contracts) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("contractId", contract.contractId());
                    row.put("quantity", num(contract.quantity()));
                    row.put("unitPrice", dec(contract.unitPrice()));
                    row.put("signedAt", num(contract.signedAt()));
                    row.put("deliveredQuantity", num(contract.deliveredQuantity()));
                    row.put("remainingQuantity", num(contract.remainingQuantity()));
                    row.put("period", num(contract.period()));
                    contractRows.add(row);
                }
                List<ManufacturingModel.Receivable> receivables =
                        new ArrayList<>(manufacturing.ledger().receivables().values());
                receivables.sort(Comparator.comparingLong(ManufacturingModel.Receivable::recognizedAt)
                        .thenComparing(ManufacturingModel.Receivable::receivableId).reversed());
                for (ManufacturingModel.Receivable receivable : receivables) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("source", "MANUFACTURING_RECEIVABLE");
                    row.put("receivableId", receivable.receivableId());
                    row.put("contractId", receivable.contractId());
                    row.put("amount", dec(receivable.amount()));
                    row.put("outstanding", dec(receivable.outstanding()));
                    row.put("dueAt", num(receivable.dueAt()));
                    row.put("status", receivable.status().name());
                    paymentRows.add(row);
                }
            }
            report.put("workOrderScope", "NOT_APPLICABLE");
            report.put("retentionScope", "NOT_APPLICABLE");
            report.put("memoScope", "NOT_APPLICABLE");
            report.put("customerScope", "NOT_APPLICABLE");
            // 制造公司的董事会待办同样真实可见：已排程不等于已完成。
            Set<String> pendingKinds = Set.of(PendingTask.ENGINEERING_BOARD_V1,
                    PendingTask.BOARD_ATTEND_V1, PendingTask.ENGINEERING_ACTION_V1,
                    PendingTask.CUSTOMER_EVENT_V1);
            boolean[] pendingUnattributed = {false};
            taskRows.addAll(pendingTaskRows(snapshot, selected, pendingKinds,
                    pendingUnattributed));
            if (pendingUnattributed[0]) {
                notes.add("另有 " + pendingUnattributed[0] + " 条待办没有可归属的公司字段，未在本页显示。");
            }
            notes.add("制造模板没有工程工单、质保与客户池：这些区块标为该经营模板不适用，不显示数字也不写 0 冒充。");
            notes.add("制造经营明细（采购/生产/费用）请看「公司经营与治理」页，本页只做同公司并排对照。");
        }

        sortReportsNewestFirst(reportRows);
        Section seats = bounded(seatRows);
        Section appointments = bounded(appointmentRows);
        Section contracts = bounded(contractRows);
        Section workOrders = bounded(workOrderRows);
        Section budgets = bounded(budgetRows);
        Section commitments = bounded(commitmentRows);
        Section meetings = bounded(meetingRows);
        Section requests = bounded(requestRows);
        Section responses = bounded(responseRows);
        Section payments = bounded(paymentRows);
        Section waivers = bounded(waiverRows);
        Section tasks = bounded(taskRows);
        Section reports = bounded(reportRows);
        Map<String, Object> periodic = periodicViewOf(current, snapshot, registry, selected, "", true);
        return new EngineeringView(runId, position, time, snapshot.lastPhase(), captured,
                run != null, run == null ? ENGINEERING_DISABLED_NOTE : null, List.copyOf(options),
                selected, partyName(registry, selected), option.template(), report, seats,
                appointments, contracts, workOrders, budgets, commitments, meetings, requests,
                responses, payments, waivers, tasks, reports, periodic, engineeringSources(), notes);
    }

    /**
     * 该公司开局治理说明的已发布版本：只按固定文档标识在该帧 Information 里取最新已发布版本，
     * 没有就返回 null（不猜、不造）。
     */
    private static InformationModel.DocumentVersion governanceNoteOf(
            InformationModel.State information, String companyPartyId, long now) {
        if (information == null || companyPartyId == null || companyPartyId.isBlank()) {
            return null;
        }
        String docId = "company-governance:" + companyPartyId + ":1";
        for (InformationModel.Document document : information.documents()) {
            if (!document.docId().equals(docId)) {
                continue;
            }
            InformationModel.DocumentVersion latest = null;
            for (InformationModel.DocumentVersion version : document.versions()) {
                if (version.publishedAt() <= now) {
                    latest = version;
                }
            }
            return latest;
        }
        return null;
    }

    /**
     * 该公司已排程/在办的董事与经营待办（只读投影，state 恒为 SCHEDULED）。
     *
     * <p>章程公司（制造）也走同一投影：真实待办不因公司类型被隐藏；BEGIN 屏障以其内层动作
     * 归属公司，无法归属的只计数，不在页面猜测归属。</p>
     *
     * @param snapshot       同一确认快照
     * @param companyPartyId 公司
     * @param kinds          直接归属该公司的任务种类
     * @param unattributed   出参：无可归属公司字段的条数
     * @return 待办行
     */
    private static List<Map<String, Object>> pendingTaskRows(WorldRuntime.Snapshot snapshot,
            String companyPartyId, Set<String> kinds, boolean[] unattributed) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PendingTask task : snapshot.pending()) {
            String owner;
            String tool;
            String wrappedKind = null;
            String wrapperDue = null;
            if (kinds.contains(task.kind())) {
                owner = payloadCompany(task.payload());
                tool = payloadText(task.payload(), "action");
            } else if (PendingTask.ENGINEERING_BEGIN_V1.equals(task.kind())) {
                BeginWrapper wrapper = beginWrapper(task.payload());
                if (wrapper == null) {
                    unattributed[0] = true;
                    continue;
                }
                owner = payloadCompany(wrapper.payload());
                tool = payloadText(wrapper.payload(), "action");
                wrappedKind = wrapper.targetKind();
                wrapperDue = num(wrapper.dueMillis());
            } else {
                continue;
            }
            if (!companyPartyId.equals(owner)) {
                if (owner == null || owner.isEmpty()) {
                    unattributed[0] = true;
                }
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", task.taskId());
            row.put("dueTimeMillis", num(task.dueTimeMillis()));
            row.put("phase", task.phase().name());
            row.put("kind", task.kind());
            row.put("wrappedKind", wrappedKind);
            row.put("wrapperDueMillis", wrapperDue);
            row.put("tool", tool);
            row.put("state", "SCHEDULED");
            rows.add(row);
        }
        return rows;
    }

    /**
     * 周期治理只读投影：把同一帧里真实的采样、会议、评分、草稿、批准、授权、公开版本与
     * MISSED 原样组成页面区块。God 与董事/经理共用同一实现，差异只在 God 额外拿到
     * 世界位置/摘要与全部席位的原始评分；角色视图仍只统计本人动作，不凭空补 0 或总分。
     *
     * @param current         当前世界
     * @param snapshot        同一确认快照
     * @param registry        开局登记表（姓名显示）
     * @param companyPartyId  公司
     * @param personPartyId   会话人物；God 传空串
     * @param god             是否 God 审计
     * @return 只读区块；没有周期状态时 available=false
     */
    private Map<String, Object> periodicViewOf(WorldRuntime current, WorldRuntime.Snapshot snapshot,
            OpeningRegistry registry, String companyPartyId, String personPartyId, boolean god) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("companyPartyId", companyPartyId);
        view.put("companyName", displayName(registry, companyPartyId));
        java.util.Optional<PeriodicModel.CompanyPeriods> found = current.periodicOf(companyPartyId);
        if (found.isEmpty()) {
            view.put("available", false);
            view.put("reason", "NO_PERIODIC_STATE");
            return view;
        }
        PeriodicModel.CompanyPeriods periods = found.get();
        BoardRun board = current.boardOfCompany(companyPartyId).orElse(null);
        BoardModel.Charter charter = board == null ? null : board.board().charter();
        Map<String, String> seatPerson = new LinkedHashMap<>();
        Map<String, String> personSeat = new LinkedHashMap<>();
        if (charter != null) {
            for (BoardModel.Seat seat : charter.seats()) {
                seatPerson.put(seat.seatId(), seat.partyId());
                personSeat.put(seat.partyId(), seat.seatId());
            }
        }
        String me = personPartyId == null ? "" : personPartyId;
        String mySeat = me.isBlank() ? null : personSeat.get(me);
        boolean manager = !me.isBlank() && current.isActiveManager(companyPartyId, me,
                snapshot.timeMillis());
        boolean seatHolder = mySeat != null;
        PeriodicModel.Access access = current.periodicAccess();
        InformationModel.State information = snapshot.information()
                .map(run -> run.information()).orElse(null);
        Map<Long, List<Map<String, Object>>> evidenceByPeriod = new LinkedHashMap<>();
        Map<Long, String> publicDocByPeriod = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> roleRowByPeriod = new LinkedHashMap<>();
        // God 审计走 periodicGodView 的自有行：人物投影对无席/无经理者按设计返回空表，
        // 不能拿它当 God 的证据来源（否则会误报“无合格证据”）。人物仍只消费自己的投影。
        Map<String, Object> periodicSource = god
                ? current.periodicGodView(companyPartyId)
                : current.periodicRoleView(me, companyPartyId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roleRows = (List<Map<String, Object>>) periodicSource
                .getOrDefault("periods", List.of());
        for (Map<String, Object> roleRow : roleRows) {
            Object period = roleRow.get("period");
            if (!(period instanceof Number number)) {
                continue;
            }
            roleRowByPeriod.put(number.longValue(), roleRow);
            Object evidence = roleRow.get("evidence");
            if (evidence instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
                evidenceByPeriod.put(number.longValue(), rows);
            }
            Object publicDoc = roleRow.get("publicDocId");
            if (publicDoc instanceof String doc && !doc.isBlank()) {
                publicDocByPeriod.put(number.longValue(), doc);
            }
        }

        view.put("available", true);
        view.put("god", god);
        view.put("seat", seatHolder);
        view.put("manager", manager);
        view.put("mySeatId", mySeat);
        view.put("chair", mySeat != null && charter != null
                && mySeat.equals(charter.chairSeatId()));
        view.put("seatCount", charter == null ? null : Integer.toString(charter.seats().size()));
        view.put("quorum", charter == null ? null : Integer.toString(charter.quorum()));
        List<Map<String, Object>> seatView = new ArrayList<>();
        if (charter != null) {
            for (BoardModel.Seat seat : charter.seats()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seatId", seat.seatId());
                row.put("personPartyId", seat.partyId());
                row.put("personName", displayName(registry, seat.partyId()));
                row.put("chair", seat.seatId().equals(charter.chairSeatId()));
                seatView.add(row);
            }
        }
        view.put("seats", seatView);
        view.put("companyPartyId", companyPartyId);
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("policyId", periods.policy().policyId());
        policy.put("periodMillis", num(periods.policy().periodMillis()));
        policy.put("reviewEveryPeriods", Integer.toString(periods.policy().reviewEveryPeriods()));
        policy.put("formalEveryPeriods", Integer.toString(periods.policy().formalEveryPeriods()));
        policy.put("materialLeadMillis", num(periods.policy().materialLeadMillis()));
        policy.put("reviewWindowMillis", num(periods.policy().reviewWindowMillis()));
        policy.put("disclosureWindowMillis", num(periods.policy().disclosureWindowMillis()));
        policy.put("minCompleteSeats", Integer.toString(PeriodicModel.MIN_COMPLETE_SEATS));
        view.put("policy", policy);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PeriodicModel.Snapshot snapshotRow : periods.snapshots()) {
            long period = snapshotRow.period();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", num(period));
            row.put("kind", period % PeriodicModel.FORMAL_EVERY_PERIODS == 0
                    ? "QUARTERLY" : "MONTHLY");
            row.put("capturedAt", num(snapshotRow.capturedAt()));
            long deadline = -1L;
            try {
                deadline = Math.addExact(snapshotRow.capturedAt(),
                        periods.policy().disclosureWindowMillis());
            } catch (ArithmeticException overflow) {
                deadline = -1L;
            }
            row.put("deadline", deadline < 0 ? null : num(deadline));
            row.put("materialDocId", snapshotRow.materialDocId());
            row.put("materialVersion", num(snapshotRow.materialVersion()));
            if (god) {
                row.put("position", num(snapshotRow.position()));
                row.put("basisDigest", snapshotRow.basisDigest());
                row.put("statementDigest", snapshotRow.statementDigest());
            }
            if (seatHolder) {
                row.put("myReadMaterial", access.hasRead(me, snapshotRow.materialDocId(),
                        snapshotRow.materialVersion(), snapshot.timeMillis()));
                row.put("myRecallEligible", access.recallEligible(me, snapshotRow.materialDocId(),
                        snapshotRow.materialVersion(), snapshot.timeMillis()));
            }

            Map<String, Long> asOfByRef = periodicEvidenceAsOf(periods, companyPartyId, period,
                    snapshotRow.capturedAt());
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (Map<String, Object> entry : evidenceByPeriod.getOrDefault(period, List.of())) {
                Object refValue = entry.get("ref");
                if (!(refValue instanceof String ref) || ref.isBlank()) {
                    continue;
                }
                Long exact = asOfByRef.get(ref);
                if (ref.startsWith("disclosure:") && exact == null) {
                    // 后端人物投影没有给出该披露证据的成熟时点：宁可不提供，也不猜历史。
                    continue;
                }
                Map<String, Object> copy = new LinkedHashMap<>();
                copy.put("ref", ref);
                copy.put("dimensions", entry.get("dimensions"));
                copy.put("asOf", num(exact == null ? snapshotRow.capturedAt() : exact));
                evidence.add(copy);
            }
            row.put("evidence", evidence);
            List<Map<String, Object>> unavailable = new ArrayList<>();
            for (BoardModel.Dimension dimension : BoardModel.Dimension.values()) {
                boolean covered = false;
                for (Map<String, Object> entry : evidence) {
                    Object dimensions = entry.get("dimensions");
                    if (dimensions instanceof List<?> list
                            && list.contains(dimension.name())) {
                        covered = true;
                    }
                }
                if (!covered) {
                    Map<String, Object> miss = new LinkedHashMap<>();
                    miss.put("dimension", dimension.name());
                    miss.put("reason", dimension == BoardModel.Dimension.LONG_TERM_INVESTMENT
                            ? "DIMENSION_NOT_AVAILABLE" : "NO_ELIGIBLE_EVIDENCE");
                    unavailable.add(miss);
                }
            }
            row.put("unavailableDimensions", unavailable);
            // 区分“无合格证据”（真缺项）和“有证据但还没人评分”（进度）：未评分不等于不存在。
            // God 统计全席；人物只统计本人，避免把“别人已评分”说成自己的进度。
            Set<String> scoredDimensions = new LinkedHashSet<>();
            for (PeriodicModel.Ballot ballot : periods.ballots()) {
                if (ballot.period() != period) {
                    continue;
                }
                if (!god && !ballot.personPartyId().equals(me)) {
                    continue;
                }
                for (BoardModel.EvidenceMetric metric : ballot.metrics()) {
                    scoredDimensions.add(metric.dimension().name());
                }
            }
            List<Map<String, Object>> dimensionStates = new ArrayList<>();
            for (BoardModel.Dimension dimension : BoardModel.Dimension.values()) {
                boolean hasEvidence = false;
                for (Map<String, Object> entry : evidence) {
                    Object dimensions = entry.get("dimensions");
                    if (dimensions instanceof List<?> list && list.contains(dimension.name())) {
                        hasEvidence = true;
                    }
                }
                boolean scored = scoredDimensions.contains(dimension.name());
                String state;
                if (hasEvidence) {
                    state = scored ? "SCORED" : "AWAITING_SCORE";
                } else if (dimension == BoardModel.Dimension.LONG_TERM_INVESTMENT) {
                    state = "DIMENSION_NOT_AVAILABLE";
                } else {
                    state = "NO_ELIGIBLE_EVIDENCE";
                }
                Map<String, Object> stateRow = new LinkedHashMap<>();
                stateRow.put("dimension", dimension.name());
                stateRow.put("hasEvidence", hasEvidence);
                stateRow.put("scored", scored);
                stateRow.put("state", state);
                dimensionStates.add(stateRow);
            }
            row.put("dimensionStates", dimensionStates);
            row.put("dimensionScope", god ? "ALL_SEATS" : "MYSELF");

            BoardModel.Meeting meeting = board == null ? null
                    : board.board().meeting(snapshotRow.meetingId());
            if (meeting != null) {
                Map<String, Object> meetingView = new LinkedHashMap<>();
                meetingView.put("meetingId", meeting.meetingId());
                meetingView.put("topic", meeting.topic().name());
                meetingView.put("status", meeting.status().name());
                meetingView.put("convenerSeatId", meeting.convenerSeatId());
                meetingView.put("createdAt", num(meeting.createdAt()));
                meetingView.put("materialDeadline", num(meeting.materialDeadline()));
                meetingView.put("opensAt", num(meeting.opensAt()));
                meetingView.put("closesAt", num(meeting.closesAt()));
                meetingView.put("attendees", seatRows(meeting.attendeeSeatIds(), seatPerson,
                        registry));
                meetingView.put("recused", seatRows(meeting.recusedSeatIds(), seatPerson,
                        registry));
                meetingView.put("attendeeCount",
                        Integer.toString(meeting.attendeeSeatIds().size()));
                row.put("meeting", meetingView);
            }

            Map<String, Object> roleRow = roleRowByPeriod.getOrDefault(period, Map.of());
            List<Map<String, Object>> draftRows = new ArrayList<>();
            Map<String, Object> latestDraft = null;
            if (god) {
                List<PeriodicModel.Draft> drafts = new ArrayList<>();
                for (PeriodicModel.Draft draft : periods.drafts()) {
                    if (draft.period() == period) {
                        drafts.add(draft);
                    }
                }
                for (PeriodicModel.Draft draft : drafts) {
                    Map<String, Object> draftView = new LinkedHashMap<>();
                    draftView.put("draftId", draft.draftId());
                    draftView.put("docId", draft.docId());
                    draftView.put("version", num(draft.version()));
                    draftView.put("preparedBy", draft.preparedBy());
                    draftView.put("preparedByName", displayName(registry, draft.preparedBy()));
                    draftView.put("preparedAt", num(draft.preparedAt()));
                    draftView.put("position", num(draft.position()));
                    draftView.put("snapshotPosition", num(draft.snapshotPosition()));
                    draftView.put("bodyDigest", draft.bodyDigest());
                    List<Map<String, Object>> approvalRows = new ArrayList<>();
                    for (PeriodicModel.Approval approval : periods.approvals()) {
                        if (!approval.draftId().equals(draft.draftId())
                                || approval.draftVersion() != draft.version()) {
                            continue;
                        }
                        Map<String, Object> approvalView = new LinkedHashMap<>();
                        approvalView.put("approverPersonId", approval.approverPersonId());
                        approvalView.put("approverName",
                                displayName(registry, approval.approverPersonId()));
                        approvalView.put("approverSeatId", approval.approverSeatId());
                        approvalView.put("approvedAt", num(approval.approvedAt()));
                        approvalRows.add(approvalView);
                    }
                    draftView.put("approvals", approvalRows);
                    draftView.put("approvalCount", Integer.toString(approvalRows.size()));
                    draftView.put("requiredApprovals",
                            Integer.toString(PeriodicModel.MIN_COMPLETE_SEATS));
                    draftView.put("mine", !me.isBlank() && draft.preparedBy().equals(me));
                    draftRows.add(draftView);
                    latestDraft = draftView;
                }
            } else if (roleRow.get("myDraft") instanceof Map<?, ?> myDraft) {
                // 人物页只消费后端 periodicRoleView 给出的本人可见草稿投影；不列他人批准明细。
                Map<String, Object> draftView = new LinkedHashMap<>();
                Object preparedBy = myDraft.get("preparedBy");
                draftView.put("draftId", myDraft.get("draftId"));
                draftView.put("docId", myDraft.get("docId"));
                draftView.put("version", myDraft.get("version"));
                draftView.put("preparedBy", preparedBy);
                draftView.put("preparedByName", displayName(registry,
                        preparedBy == null ? null : preparedBy.toString()));
                draftView.put("preparedAt", myDraft.get("preparedAt"));
                draftView.put("approvalCount", String.valueOf(myDraft.get("approvals")));
                draftView.put("requiredApprovals",
                        Integer.toString(PeriodicModel.MIN_COMPLETE_SEATS));
                draftView.put("mine", !me.isBlank() && me.equals(preparedBy));
                draftRows.add(draftView);
                latestDraft = draftView;
            }
            row.put("drafts", draftRows);
            if (latestDraft != null) {
                row.put("latestDraft", latestDraft);
            }
            boolean myApproval = false;
            if (god) {
                for (PeriodicModel.Approval approval : periods.approvals()) {
                    if (approval.period() == period && approval.approverPersonId().equals(me)) {
                        myApproval = true;
                    }
                }
            } else {
                myApproval = Boolean.TRUE.equals(roleRow.get("myApproval"));
            }
            row.put("myApproval", myApproval);

            Map<String, Object> scoreView = new LinkedHashMap<>();
            PeriodicModel.Score aggregate = null;
            for (PeriodicModel.Score score : periods.scores()) {
                if (score.period() == period) {
                    aggregate = score;
                }
            }
            List<Map<String, Object>> ballotRows = new ArrayList<>();
            for (PeriodicModel.Ballot ballot : periods.ballots()) {
                if (ballot.period() != period) {
                    continue;
                }
                if (!god && !ballot.personPartyId().equals(me)) {
                    // 人物页不看其他董事的原始评分/理由；只看后端允许的本人选票与汇总。
                    continue;
                }
                Map<String, Object> ballotView = new LinkedHashMap<>();
                ballotView.put("seatId", ballot.seatId());
                ballotView.put("personPartyId", ballot.personPartyId());
                ballotView.put("personName", displayName(registry, ballot.personPartyId()));
                ballotView.put("submittedAt", num(ballot.submittedAt()));
                ballotView.put("status", ballot.reviewStatus().name());
                Set<BoardModel.Dimension> present = new LinkedHashSet<>();
                List<Map<String, Object>> metrics = new ArrayList<>();
                for (BoardModel.EvidenceMetric metric : ballot.metrics()) {
                    present.add(metric.dimension());
                    Map<String, Object> metricView = new LinkedHashMap<>();
                    metricView.put("dimension", metric.dimension().name());
                    metricView.put("score", dec(metric.score()));
                    metricView.put("sourceRef", metric.sourceRef());
                    metricView.put("asOf", num(metric.asOf()));
                    metrics.add(metricView);
                }
                List<String> missing = new ArrayList<>();
                for (BoardModel.Dimension dimension : BoardModel.Dimension.values()) {
                    if (!present.contains(dimension)) {
                        missing.add(dimension.name());
                    }
                }
                ballotView.put("missingDimensions", missing);
                ballotView.put("metrics", metrics);
                ballotView.put("rationale", ballot.rationale());
                ballotView.put("mine", !me.isBlank() && ballot.personPartyId().equals(me));
                ballotRows.add(ballotView);
            }
            scoreView.put("closed", aggregate != null);
            if (aggregate != null) {
                scoreView.put("status", aggregate.status().name());
                scoreView.put("reason", aggregate.reason());
                scoreView.put("seatCount", Integer.toString(aggregate.seatCount()));
                scoreView.put("total", aggregate.total() == null ? null : dec(aggregate.total()));
            } else {
                scoreView.put("seatCount", god ? Integer.toString(ballotRows.size()) : null);
                scoreView.put("total", null);
            }
            scoreView.put("ballots", ballotRows);
            row.put("score", scoreView);

            List<Map<String, Object>> authorizationRows = new ArrayList<>();
            if (god) {
                for (PeriodicModel.Authorization authorization : periods.authorizations()) {
                    if (authorization.period() != period) {
                        continue;
                    }
                    Map<String, Object> authorizationView = new LinkedHashMap<>();
                    authorizationView.put("authorizationId", authorization.authorizationId());
                    authorizationView.put("draftId", authorization.draftId());
                    authorizationView.put("draftVersion", num(authorization.draftVersion()));
                    authorizationView.put("publisherPersonId", authorization.publisherPersonId());
                    authorizationView.put("publisherName",
                            displayName(registry, authorization.publisherPersonId()));
                    authorizationView.put("issuedAt", num(authorization.issuedAt()));
                    authorizationView.put("expiresAt", num(authorization.expiresAt()));
                    authorizationView.put("consumedAt", num(authorization.consumedAt()));
                    String status;
                    if (authorization.consumedAt() > 0) {
                        status = "CONSUMED";
                    } else if (snapshot.timeMillis() >= authorization.expiresAt()) {
                        status = "EXPIRED";
                    } else {
                        status = "PENDING";
                    }
                    authorizationView.put("status", status);
                    authorizationView.put("approvers", new ArrayList<>(
                            authorization.approverPersonIds()));
                    authorizationRows.add(authorizationView);
                }
            } else if (roleRow.get("pendingAuthorization") instanceof Map<?, ?> pending) {
                // 只显示 periodicRoleView 已允许给本人的未消费授权；不列他人的历史 grant。
                // 后端已给出可执行所需的草稿绑定（draftId/draftVersion）：原样保留给发布表单，
                // 页面不再从 God 状态或自己的草稿列表猜这些值。
                Map<String, Object> authorizationView = new LinkedHashMap<>();
                authorizationView.put("authorizationId", pending.get("authorizationId"));
                authorizationView.put("draftId", pending.get("draftId"));
                authorizationView.put("draftVersion", pending.get("draftVersion"));
                authorizationView.put("publisherPersonId", pending.get("publisherPersonId"));
                Object publisher = pending.get("publisherPersonId");
                authorizationView.put("publisherName", displayName(registry,
                        publisher == null ? null : publisher.toString()));
                authorizationView.put("expiresAt", pending.get("expiresAt"));
                authorizationView.put("status", "PENDING");
                // 经理也会看到本公司本期授权；只有指定发布人本人能真正消费它。
                authorizationView.put("mine", !me.isBlank() && me.equals(
                        publisher == null ? null : publisher.toString()));
                authorizationRows.add(authorizationView);
            }
            row.put("authorizations", authorizationRows);

            List<Map<String, Object>> releaseRows = new ArrayList<>();
            PeriodicModel.Release latestRelease = null;
            Long latestPublicVersion = null;
            String releaseDocId = publicDocByPeriod.get(period);
            for (PeriodicModel.Release release : periods.releases()) {
                if (release.period() != period) {
                    continue;
                }
                // 公开版本号取自已发布资料的真实文档版本：按该次发布的真实发布时刻定位，并与
                // 被发草稿正文核对；定位不到就明确标未定位，不按发行条数或草稿次数推断。
                InformationModel.DocumentVersion publicDoc =
                        publicVersionOf(information, releaseDocId, release);
                Long publicVersion = publicDoc == null ? null : publicDoc.version();
                Map<String, Object> releaseView = new LinkedHashMap<>();
                releaseView.put("publicDocId", releaseDocId);
                releaseView.put("version", publicVersion == null ? null : num(publicVersion));
                releaseView.put("publicVersion", publicVersion == null ? null : num(publicVersion));
                releaseView.put("versionResolved", publicVersion != null);
                // 草稿版本来自发布记录本身，与公开文档版本是两个真实概念，不互相代填。
                releaseView.put("draftVersion", num(release.draftVersion()));
                releaseView.put("publishedAt", num(release.publishedAt()));
                releaseView.put("late", release.late());
                releaseView.put("publisherPersonId", release.publisherPersonId());
                releaseView.put("publisherName",
                        displayName(registry, release.publisherPersonId()));
                releaseView.put("title", publicDoc == null ? null : publicDoc.title());
                if (god) {
                    releaseView.put("authorizationId", release.authorizationId());
                    releaseView.put("bodyDigest", release.bodyDigest());
                    releaseView.put("bodyAvailable", publicDoc != null);
                    releaseView.put("body", publicDoc == null ? null : publicDoc.body());
                } else {
                    // 历史已读（hasRead，永久）与当前可回忆（recallEligible，走冻结回忆阈值）
                    // 分开表达：正文只在当前可回忆时给出，遗忘后需要重新阅读，不新造遗忘政策。
                    boolean read = publicVersion != null && !me.isBlank()
                            && access.hasRead(me, releaseDocId, publicVersion,
                            snapshot.timeMillis());
                    boolean recallable = read && access.recallEligible(me, releaseDocId,
                            publicVersion, snapshot.timeMillis());
                    releaseView.put("read", read);
                    releaseView.put("recallable", recallable);
                    releaseView.put("forgotten", read && !recallable);
                    releaseView.put("bodyAvailable", recallable && publicDoc != null);
                    releaseView.put("body", recallable && publicDoc != null
                            ? publicDoc.body() : null);
                    releaseView.put("requiresReading", !recallable);
                }
                releaseRows.add(releaseView);
                latestRelease = release;
                latestPublicVersion = publicVersion;
            }
            row.put("releases", releaseRows);
            Map<String, Object> publication = new LinkedHashMap<>();
            publication.put("publicDocId", publicDocByPeriod.get(period));
            publication.put("released", !releaseRows.isEmpty());
            // 这里是真实 release 记录条数；公开文档版本号另由 latestVersion/各版本行的
            // publicVersion 给出，两者不互相代填。
            publication.put("versions", Integer.toString(releaseRows.size()));
            publication.put("latestVersion",
                    latestPublicVersion == null ? null : num(latestPublicVersion));
            publication.put("latestPublishedAt",
                    latestRelease == null ? null : num(latestRelease.publishedAt()));
            boolean late = false;
            for (PeriodicModel.Release release : periods.releases()) {
                if (release.period() == period && release.late()) {
                    late = true;
                }
            }
            publication.put("late", late);
            boolean missed = false;
            long missedAt = 0L;
            for (PeriodicModel.Missed marker : periods.missed()) {
                if (marker.period() == period) {
                    missed = true;
                    missedAt = marker.missedAt();
                }
            }
            publication.put("missed", missed);
            publication.put("missedAt", missedAt == 0L ? null : num(missedAt));
            row.put("publication", publication);
            row.put("corrections", Integer.toString(
                    draftRows.size() > 1 || releaseRows.size() > 1 ? 1 : 0));

            List<Map<String, Object>> pending = periodicPending(snapshot, companyPartyId, period,
                    me, god);
            row.put("pendingActions", pending);
            rows.add(row);
        }
        view.put("periods", rows);
        List<String> notes = new ArrayList<>();
        notes.add("已排程动作只在 pendingActions 里显示为待执行；到期 FINISH 后同一期间行才会出现草稿/评分/公开版本。");
        notes.add("缺维按每席实际提交的维度计算；不足三名完整有效席时不形成总分，不补 0。");
        notes.add("公开正文原样取自已发布版本；更正会形成新草稿/新批准/新公开版本并保留旧版本。");
        notes.add("公开版本号取自已发布资料的文档版本；草稿修订未发表不会增加公开版本，"
                + "草稿版本与公开版本是两个概念。");
        notes.add("本人正文按既有阅读与回忆口径给出：历史已读保留为标识，遗忘后需重新阅读才再次显示正文。");
        view.put("notes", notes);
        return view;
    }

    /** 席位清单转显示行；空席位保持空表，不补虚构席。 */
    private static List<Map<String, Object>> seatRows(List<String> seatIds,
            Map<String, String> seatPerson, OpeningRegistry registry) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String seatId : seatIds) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seatId", seatId);
            String person = seatPerson.get(seatId);
            row.put("personPartyId", person);
            row.put("personName", displayName(registry, person));
            rows.add(row);
        }
        return rows;
    }

    /** 登记姓名优先，没有登记时回退到稳定 partyId，不让页面出现空空白。 */
    private static String displayName(OpeningRegistry registry, String partyId) {
        String name = partyName(registry, partyId);
        return name == null || name.isBlank() ? partyId : name;
    }

    /** 该期间仍未完成的周期任务；只显示真实排程，不把 SCHEDULED 当已完成。 */
    private static List<Map<String, Object>> periodicPending(WorldRuntime.Snapshot snapshot,
            String companyPartyId, long period, String personPartyId, boolean god) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PendingTask task : snapshot.pending()) {
            PeriodicActionPayload payload = null;
            if (PendingTask.ENGINEERING_PERIODIC_V1.equals(task.kind())) {
                payload = periodicPayload(task.payload());
            } else if (PendingTask.ENGINEERING_BEGIN_V1.equals(task.kind())) {
                BeginWrapper wrapper = beginWrapper(task.payload());
                if (wrapper != null
                        && PendingTask.ENGINEERING_PERIODIC_V1.equals(wrapper.targetKind())) {
                    payload = periodicPayload(wrapper.payload());
                }
            }
            if (payload == null || !payload.companyPartyId().equals(companyPartyId)
                    || payload.period() != period) {
                continue;
            }
            if (!god && !payload.personPartyId().equals(personPartyId)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", task.taskId());
            row.put("dueTimeMillis", num(task.dueTimeMillis()));
            row.put("kind", task.kind());
            row.put("action", payload.action());
            row.put("personPartyId", payload.personPartyId());
            row.put("state", "SCHEDULED");
            rows.add(row);
        }
        return rows;
    }

    private static PeriodicActionPayload periodicPayload(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return PAYLOAD_MAPPER.readValue(text, PeriodicActionPayload.class);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * 该次发布对应的公开文档真实版本；找不到返回 null（不按条数或草稿次数推断版本号）。
     *
     * <p>同一个公开文档的版本发布时间严格递增，因此用 release 的真实 {@code publishedAt}
     * 定位是精确的；再用公开版本正文与所发草稿的 Information 正文核对一次，正文对不上就不认
     * 这条映射。两者都来自真实文档记录，不读任何私有值。</p>
     */
    private static InformationModel.DocumentVersion publicVersionOf(
            InformationModel.State information, String publicDocId,
            PeriodicModel.Release release) {
        if (information == null || publicDocId == null || publicDocId.isBlank()
                || release == null) {
            return null;
        }
        String draftBody = publicBody(information, release.docId(), release.version());
        for (InformationModel.Document document : information.documents()) {
            if (!document.docId().equals(publicDocId)) {
                continue;
            }
            for (InformationModel.DocumentVersion version : document.versions()) {
                if (version.publishedAt() != release.publishedAt()) {
                    continue;
                }
                if (draftBody != null && !draftBody.equals(version.body())) {
                    return null;
                }
                return version;
            }
        }
        return null;
    }

    /**
     * 该期证据引用 → 可提交的准确 asOf：披露维必须用对应 release 的真实发布时刻或
     * MISSED 记录时刻；其余维度以该期采样截至时点为上界（账/合同/工单均已结算到期末）。
     * 后端人物投影目前不逐条给证据时点，因此这里按同一公开事实重建；找不到就返回空，
     * 调用方不得猜历史。
     */
    private static Map<String, Long> periodicEvidenceAsOf(PeriodicModel.CompanyPeriods periods,
            String companyPartyId, long period, long capturedAt) {
        Map<String, Long> times = new LinkedHashMap<>();
        Map<Long, Integer> order = new LinkedHashMap<>();
        for (PeriodicModel.Release release : periods.releases()) {
            int index = order.merge(release.period(), 1, Integer::sum);
            if (release.period() < period && release.publishedAt() <= capturedAt) {
                times.put("disclosure:" + companyPartyId + ":" + release.period()
                        + ":release:" + index, release.publishedAt());
            }
        }
        for (PeriodicModel.Missed marker : periods.missed()) {
            if (marker.period() < period && marker.missedAt() <= capturedAt) {
                times.put("disclosure:" + companyPartyId + ":" + marker.period() + ":missed",
                        marker.missedAt());
            }
        }
        return times;
    }

    /** 公开版本正文：只从 InformationModel 的真实版本取，找不到返回 null（不编造）。 */
    private static String publicBody(InformationModel.State information, String docId,
            long version) {
        if (information == null || docId == null) {
            return null;
        }
        for (InformationModel.Document document : information.documents()) {
            if (document.docId().equals(docId)) {
                InformationModel.DocumentVersion found = document.at(version);
                return found == null ? null : found.body();
            }
        }
        return null;
    }

    private static Map<String, Object> meetingRow(BoardModel.Meeting meeting) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("meetingId", meeting.meetingId());
        row.put("topic", meeting.topic().name());
        row.put("convenerSeatId", meeting.convenerSeatId());
        row.put("createdAt", num(meeting.createdAt()));
        row.put("materialDeadline", num(meeting.materialDeadline()));
        row.put("opensAt", num(meeting.opensAt()));
        row.put("closesAt", num(meeting.closesAt()));
        row.put("status", meeting.status().name());
        row.put("attendees", String.join(",", meeting.attendeeSeatIds()));
        row.put("recused", String.join(",", meeting.recusedSeatIds()));
        row.put("materialCount", Integer.toString(meeting.materials().size()));
        row.put("proposedLines", proposedLines(meeting.proposedLines()));
        row.put("ballots", ballots(meeting.ballots()));
        row.put("failureReason", meeting.failureReason());
        return row;
    }

    /**
     * 本人视角的工程/经营事实（单 writer 任务、单快照、只按真实岗位裁剪）。
     *
     * @param personPartyId 会话绑定人物
     * @return 个人视图
     * @throws BusyException 队列满
     * @throws Exception     读取失败
     */
    public PersonEngineeringView personEngineering(String personPartyId)
            throws BusyException, Exception {
        return call(() -> personEngineeringOf(readyNow(), personPartyId));
    }

    private PersonEngineeringView personEngineeringOf(WorldRuntime current, String personPartyId) {
        WorldRuntime.Snapshot snapshot = current.snapshot();
        OpeningRegistry registry = current.openingRegistry().orElse(null);
        EngineeringRun run = snapshot.engineering().orElse(null);
        String position = num(snapshot.position());
        String time = num(snapshot.timeMillis());
        String captured = num(System.currentTimeMillis());
        List<Map<String, Object>> companies = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (run == null) {
            return new PersonEngineeringView(personPartyId, position, time, captured, false,
                    ENGINEERING_DISABLED_NOTE, List.of(), List.of(ENGINEERING_DISABLED_NOTE));
        }
        // 章程公司（制造）与工程公司共用同一周期状态；旧选择器只遍历工程公司会把制造周期挡掉。
        String manufacturingCompany = snapshot.governance()
                .map(board -> board.board().charter().companyPartyId()).orElse(null);
        if (manufacturingCompany != null) {
            Map<String, Object> card = manufacturingPersonCard(current, snapshot, registry,
                    manufacturingCompany, personPartyId);
            if (card != null) {
                companies.add(card);
            }
        }
        for (String companyId : run.companyIds()) {
            WorldRuntime.CompanyRoleView role = current.companyView(personPartyId, companyId)
                    .orElse(null);
            if (role == null) {
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("companyId", companyId);
            card.put("companyName", partyName(registry, companyId));
            card.put("template", "ENGINEERING");
            card.put("manager", role.manager());
            card.put("tools", role.tools());
            card.put("companyCash", dec(role.companyCash()));
            card.put("arCurrent", dec(role.arCurrent()));
            card.put("payable", dec(role.payable()));
            card.put("contracts", role.contractIds());
            if (role.manager()) {
                WorldRuntime.CustomerRoleView customer =
                        current.companyCustomerView(personPartyId, companyId).orElse(null);
                if (customer != null) {
                    Map<String, Object> view = new LinkedHashMap<>();
                    // 外部客户的期间序号、预算余量、闭期状态与共享池账户标识都属于对方内部信息：
                    // 本岗位没有披露依据，服务端直接不产出这些字段（不是发给浏览器再隐藏，也不补 0）。
                    view.put("companyCash", dec(customer.companyCash()));
                    view.put("arCurrent", dec(customer.arCurrent()));
                    view.put("arRetention", dec(customer.arRetention()));
                    view.put("memo", dec(customer.memo()));
                    view.put("obligation", dec(customer.obligation()));
                    // 外部客户的期间预算余量与闭期状态属于对方内部信息：本岗位没有披露依据，不发给浏览器。
                    List<Map<String, Object>> requests = new ArrayList<>();
                    for (EngineeringRun.Customer.Request request : customer.requests()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("requestId", request.requestId());
                        row.put("contractId", request.contractId());
                        row.put("trancheIndex", Integer.toString(request.trancheIndex()));
                        row.put("kind", request.kind());
                        row.put("atMillis", num(request.atMillis()));
                        row.put("answered", request.answered());
                        requests.add(row);
                    }
                    List<Map<String, Object>> responses = new ArrayList<>();
                    for (EngineeringRun.Customer.Response response : customer.responses()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("eventId", response.eventId());
                        row.put("contractId", response.contractId());
                        row.put("trancheIndex", Integer.toString(response.trancheIndex()));
                        row.put("kind", response.kind());
                        row.put("outcome", response.outcome());
                        row.put("amount", dec(response.amount()));
                        row.put("currentOutstanding", dec(response.currentOutstanding()));
                        row.put("retentionOutstanding", dec(response.retentionOutstanding()));
                        row.put("atMillis", num(response.atMillis()));
                        row.put("reason", response.reason());
                        responses.add(row);
                    }
                    // 只给本公司实际到账，不给客户池账户标识，也不含其它公司的用款。
                    List<Map<String, Object>> payments = new ArrayList<>();
                    for (EngineeringRun.Customer.Payment payment
                            : run.customer().paymentsOf(companyId)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("eventId", payment.eventId());
                        row.put("contractId", payment.contractId());
                        row.put("trancheIndex", Integer.toString(payment.trancheIndex()));
                        row.put("kind", payment.kind());
                        row.put("amount", dec(payment.amount()));
                        row.put("atMillis", num(payment.atMillis()));
                        payments.add(row);
                    }
                    view.put("requests", requests);
                    view.put("responses", responses);
                    view.put("payments", payments);
                    card.put("customer", view);
                }
                List<Map<String, Object>> waivers = new ArrayList<>();
                for (BoardModel.DebtWaiver waiver : run.waiversOf(companyId)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("waiverId", waiver.request().waiverId());
                    row.put("contractId", waiver.request().contractId());
                    row.put("amount", dec(waiver.request().amount()));
                    row.put("purpose", waiver.request().purpose());
                    row.put("authorized", waiver.authorized());
                    row.put("consumed", waiver.consumed());
                    row.put("authorizationRef", waiver.authorizationRef());
                    row.put("meetingId", waiver.meetingId());
                    waivers.add(row);
                }
                card.put("waivers", waivers);
            }
            BoardRun board = current.boardOfCompany(companyId).orElse(null);
            if (board != null) {
                BoardModel.Charter charter = board.board().charter();
                String ownSeat = null;
                for (BoardModel.Seat seat : charter.seats()) {
                    if (seat.partyId().equals(personPartyId)) {
                        ownSeat = seat.seatId();
                    }
                }
                if (ownSeat != null) {
                    card.put("seatId", ownSeat);
                    card.put("chair", ownSeat.equals(charter.chairSeatId()));
                    card.put("seatCount", Integer.toString(charter.seats().size()));
                    card.put("quorum", Integer.toString(charter.quorum()));
                    List<String> withAttend = new ArrayList<>(role.tools());
                    if (!withAttend.contains(PERIODIC_ATTEND_TOOL)) {
                        withAttend.add(PERIODIC_ATTEND_TOOL);
                    }
                    card.put("tools", List.copyOf(withAttend));
                    List<Map<String, Object>> meetings = new ArrayList<>();
                    List<BoardModel.Meeting> all = new ArrayList<>(board.board().meetings().values());
                    all.sort(Comparator.comparingLong(BoardModel.Meeting::createdAt)
                            .thenComparing(BoardModel.Meeting::meetingId).reversed());
                    for (BoardModel.Meeting meeting : all) {
                        boolean involved = meeting.convenerSeatId().equals(ownSeat)
                                || meeting.attendeeSeatIds().contains(ownSeat)
                                || meeting.recusedSeatIds().contains(ownSeat);
                        if (!involved) {
                            continue;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("meetingId", meeting.meetingId());
                        row.put("topic", meeting.topic().name());
                        row.put("status", meeting.status().name());
                        row.put("opensAt", num(meeting.opensAt()));
                        row.put("closesAt", num(meeting.closesAt()));
                        row.put("materialDeadline", num(meeting.materialDeadline()));
                        row.put("role", meeting.convenerSeatId().equals(ownSeat) ? "CONVENER"
                                : meeting.recusedSeatIds().contains(ownSeat) ? "RECUSED"
                                : "ATTENDEE");
                        BoardModel.Ballot ballot = meeting.ballots().get(ownSeat);
                        row.put("myVote", ballot == null ? null : ballot.vote().name());
                        row.put("myVoteAt", ballot == null ? null : num(ballot.castAt()));
                        int yes = 0;
                        int no = 0;
                        int abstain = 0;
                        for (BoardModel.Ballot each : meeting.ballots().values()) {
                            switch (each.vote()) {
                                case YES -> yes++;
                                case NO -> no++;
                                default -> abstain++;
                            }
                        }
                        row.put("tally", "yes=" + yes + ",no=" + no + ",abstain=" + abstain);
                        row.put("materials", Integer.toString(meeting.materials().size()));
                        row.put("proposedLines", proposedLines(meeting.proposedLines()));
                        row.put("failureReason", meeting.failureReason());
                        meetings.add(row);
                    }
                    card.put("meetings", meetings);
                }
            }
            card.put("periodic", periodicViewOf(current, snapshot, registry, companyId,
                    personPartyId, false));
            companies.add(card);
        }
        notes.add("只列出本人在任经理或占董事席位的公司；未任/未占席的公司不返回，也不提示是否存在。");
        notes.add("章程公司（制造）与工程公司一样有独立周期；制造只在本人任经理或占董事席位时出现。");
        notes.add("经理只看本公司的合同请求、客户回应与本公司实际到账；客户内部期间预算/闭期、"
                + "别家公司用款、未读机会与客户未来计划都不进入本视图，也不补 0。");
        notes.add("董事只看到自己参与或召集的会议与自己的选票，其他席位只给赞成/反对/弃权计数。");
        notes.add("页面按钮只调用真实排程入口；排程成功也只是“已排程”，到期才落账。");
        return new PersonEngineeringView(personPartyId, position, time, captured, true, null,
                companies, notes);
    }

    /**
     * 制造（章程公司）的人物卡片：只给本人真实董事席位/经理任职与周期投影，
     * 不复制制造账或工程合同；金额与经营明细仍在既有公司页面查看。
     */
    private Map<String, Object> manufacturingPersonCard(WorldRuntime current,
            WorldRuntime.Snapshot snapshot, OpeningRegistry registry, String companyPartyId,
            String personPartyId) {
        BoardRun board = snapshot.governance().orElse(null);
        if (board == null) {
            return null;
        }
        BoardModel.Charter charter = board.board().charter();
        String seat = charter.seatOf(personPartyId);
        boolean manager = current.isActiveManager(companyPartyId, personPartyId,
                snapshot.timeMillis());
        if (seat == null && !manager) {
            return null;
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("companyId", companyPartyId);
        card.put("companyName", partyName(registry, companyPartyId));
        card.put("template", "MANUFACTURING");
        card.put("manager", manager);
        if (seat != null) {
            card.put("seatId", seat);
            card.put("chair", seat.equals(charter.chairSeatId()));
            card.put("seatCount", Integer.toString(charter.seats().size()));
            card.put("quorum", Integer.toString(charter.quorum()));
        }
        List<String> tools = new ArrayList<>();
        if (seat != null) {
            tools.addAll(List.of(RoleTradingView.TOOL_BOARD_CONVENE,
                    RoleTradingView.TOOL_BOARD_ATTEND, RoleTradingView.TOOL_BOARD_MATERIAL,
                    RoleTradingView.TOOL_BOARD_VOTE, RoleTradingView.TOOL_BOARD_PROPOSE_BUDGET,
                    RoleTradingView.TOOL_BOARD_PROPOSE_APPOINTMENT,
                    RoleTradingView.TOOL_BOARD_IMPLEMENT,
                    RoleTradingView.TOOL_PERIODIC_SCORE,
                    RoleTradingView.TOOL_PERIODIC_APPROVE,
                    RoleTradingView.TOOL_PERIODIC_PUBLISH,
                    PERIODIC_ATTEND_TOOL));
            if (seat.equals(charter.chairSeatId())) {
                // 只有主席能召集任免议题；该入口在运行层复用同一任免机制，不新建角色。
                tools.add(BOARD_CONVENE_APPOINTMENT_TOOL);
            }
        }
        if (manager) {
            for (String tool : List.of(RoleTradingView.TOOL_PERIODIC_PREPARE,
                    RoleTradingView.TOOL_PERIODIC_AUTHORIZE,
                    RoleTradingView.TOOL_PERIODIC_PUBLISH)) {
                if (!tools.contains(tool)) {
                    tools.add(tool);
                }
            }
        }
        card.put("tools", List.copyOf(tools));
        // 没有在任经理是真实状态，不是缺数据：页面要明确说明需要董事会任命。
        boolean managerVacant = !manager;
        card.put("managerVacant", managerVacant);
        // 本公司开局治理说明是公开模拟说明：真实发布后所有读者都能从自己的目录取用。
        InformationModel.State noteInformation = snapshot.information()
                .map(run -> run.information()).orElse(null);
        InformationModel.DocumentVersion note = governanceNoteOf(noteInformation, companyPartyId,
                snapshot.timeMillis());
        if (note != null) {
            // 历史已读与当前可回忆分开；正文只在本人真实读完且仍可回忆时给出（同一 Recall 判据）。
            PeriodicModel.Access access = current.periodicAccess();
            String noteDocId = "company-governance:" + companyPartyId + ":1";
            boolean noteRead = access.hasRead(personPartyId, noteDocId, note.version(),
                    snapshot.timeMillis());
            boolean noteRecallable = access.recallEligible(personPartyId, noteDocId,
                    note.version(), snapshot.timeMillis());
            card.put("governanceDocId", noteDocId);
            card.put("governanceDocVersion", num(note.version()));
            card.put("governanceDocTitle", note.title());
            card.put("governanceDocPublishedAt", num(note.publishedAt()));
            card.put("governanceDocRead", noteRead);
            card.put("governanceDocRecallable", noteRecallable);
            card.put("governanceDocBody", noteRead && noteRecallable ? note.body() : null);
        }
        List<String> cardNotes = new ArrayList<>();
        if (managerVacant) {
            cardNotes.add("本公司当前没有在任公司经理：需要董事会按已有任免流程任命后才能准备周期财报。");
        }
        card.put("notes", cardNotes);
        card.put("periodic", periodicViewOf(current, snapshot, registry, companyPartyId,
                personPartyId, false));
        return card;
    }

    /**
     * 以会话人物排程一次资料阅读：只接受本人、真实资料与固定版本；
     * 是否属于可读受众仍由信息模块在到期时复核，页面不能代替权限。
     *
     * @param personPartyId 会话绑定人物
     * @param command       严格解析的阅读命令
     * @return 已排程回执
     * @throws BusyException 队列满
     * @throws Exception     世界读取/持久失败
     */
    public ReadingActionView scheduleReading(String personPartyId, ReadingCommand command)
            throws BusyException, Exception {
        if (command == null) {
            throw new IllegalArgumentException("阅读命令不能为空");
        }
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            if (snapshot.position() != command.expectedPosition()) {
                throw new PositionMismatch(snapshot.position());
            }
            String receipt = current.scheduleStartReading(command.commandId(), command.taskId(),
                    command.dueTimeMillis(), personPartyId, command.docId(), command.version());
            return new ReadingActionView(runId, num(snapshot.position()),
                    num(snapshot.timeMillis()), command.docId(),
                    Long.toString(command.version()), receipt);
        });
    }

    /**
     * 阅读命令；阅读者不在字段里，只由会话身份决定。
     *
     * @param expectedPosition 客户端看到的位置
     * @param commandId        幂等命令标识
     * @param taskId           任务标识
     * @param dueTimeMillis    开始阅读时点
     * @param docId            资料
     * @param version          固定版本
     */
    public record ReadingCommand(long expectedPosition, String commandId, String taskId,
                                 long dueTimeMillis, String docId, long version) {
    }

    /** 阅读排程回执：只是已排程，读完与否以世界记录为准。 */
    public record ReadingActionView(String runId, String position, String timeMillis,
                                    String docId, String version, String receipt) {
    }

    /**
     * 用可信会话人物提交一条工程/周期命令；岗位工具表与真实状态在 writer 线程内核对。
     *
     * @param personPartyId 会话绑定人物（不接受 body 自填身份）
     * @param command       已严格解析的命令
     * @return 排程回执
     * @throws BusyException       队列满
     * @throws PositionMismatch    客户端位置已过期
     * @throws Exception           世界读取/持久失败
     */
    public EngineeringActionView scheduleEngineering(String personPartyId,
            EngineeringCommand command) throws BusyException, Exception {
        if (command == null) {
            throw new IllegalArgumentException("命令不能为空");
        }
        return call(() -> {
            WorldRuntime current = readyNow();
            WorldRuntime.Snapshot snapshot = current.snapshot();
            if (snapshot.engineering().isEmpty()) {
                throw new IllegalArgumentException(ENGINEERING_DISABLED_NOTE);
            }
            if (snapshot.position() != command.expectedPosition()) {
                throw new PositionMismatch(snapshot.position());
            }
            String company = command.companyId();
            EngineeringRun.CompanySpec spec = snapshot.engineering().orElseThrow()
                    .config().specOf(company);
            String charterCompany = snapshot.governance()
                    .map(board -> board.board().charter().companyPartyId()).orElse(null);
            boolean charterTarget = company.equals(charterCompany);
            if (spec == null && !charterTarget) {
                throw new IllegalArgumentException("没有这家公司，或当前运行未登记它");
            }
            String tool = command.tool();
            List<String> allowedTools;
            if (spec != null) {
                WorldRuntime.CompanyRoleView role = current.companyView(personPartyId, company)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "本人在该公司没有现任经理任职，也没有董事席位"));
                List<String> tools = new ArrayList<>(role.tools());
                boolean director = current.boardOfCompany(company)
                        .map(board -> board.board().charter().seatOf(personPartyId) != null)
                        .orElse(false);
                if (director && !tools.contains(PERIODIC_ATTEND_TOOL)) {
                    tools.add(PERIODIC_ATTEND_TOOL);
                }
                allowedTools = List.copyOf(tools);
            } else {
                allowedTools = manufacturingTools(snapshot, company, personPartyId);
                if (allowedTools.isEmpty()) {
                    throw new IllegalArgumentException("本人在该公司没有现任经理任职，也没有董事席位");
                }
            }
            boolean chair = isChair(current, company, personPartyId);
            boolean canConvene = allowedTools.contains(RoleTradingView.TOOL_BOARD_CONVENE);
            // 豁免会议召集沿用普通召集权限：必须既是本公司董事且持有召集权，又实际是章程主席。
            // 核心仍会再次复核主席，这里不新增权限，也不允许仅凭主席身份绕过岗位工具表。
            if (!allowedTools.contains(tool)
                    && !("board.conveneWaiver".equals(tool) && chair && canConvene)) {
                throw new IllegalArgumentException("当前岗位不具备该动作：" + tool);
            }
            String receipt = dispatchEngineering(current, personPartyId, command);
            WorldRuntime.Snapshot after = current.snapshot();
            return new EngineeringActionView(runId, num(after.position()),
                    num(after.timeMillis()), company, tool, receipt);
        });
    }

    private static boolean isChair(WorldRuntime current, String companyId, String personPartyId) {
        return current.boardOfCompany(companyId)
                .map(board -> board.board().charter())
                .map(charter -> charter.seats().stream()
                        .anyMatch(seat -> seat.seatId().equals(charter.chairSeatId())
                                && seat.partyId().equals(personPartyId)))
                .orElse(false);
    }

    /** 章程公司的人物工具表：与人物卡片同源，只给真实席位/经理岗位。 */
    private List<String> manufacturingTools(WorldRuntime.Snapshot snapshot, String companyPartyId,
            String personPartyId) {
        BoardRun board = snapshot.governance().orElse(null);
        if (board == null) {
            return List.of();
        }
        BoardModel.Charter charter = board.board().charter();
        String seat = charter.seatOf(personPartyId);
        boolean manager = isActiveManager(snapshot, companyPartyId, personPartyId);
        if (seat == null && !manager) {
            return List.of();
        }
        List<String> tools = new ArrayList<>();
        if (seat != null) {
            tools.addAll(List.of(RoleTradingView.TOOL_BOARD_CONVENE,
                    RoleTradingView.TOOL_BOARD_ATTEND, RoleTradingView.TOOL_BOARD_MATERIAL,
                    RoleTradingView.TOOL_BOARD_VOTE, RoleTradingView.TOOL_BOARD_PROPOSE_BUDGET,
                    RoleTradingView.TOOL_BOARD_PROPOSE_APPOINTMENT,
                    RoleTradingView.TOOL_BOARD_IMPLEMENT,
                    RoleTradingView.TOOL_PERIODIC_SCORE,
                    RoleTradingView.TOOL_PERIODIC_APPROVE,
                    RoleTradingView.TOOL_PERIODIC_PUBLISH,
                    PERIODIC_ATTEND_TOOL));
            if (seat.equals(charter.chairSeatId())) {
                tools.add(BOARD_CONVENE_APPOINTMENT_TOOL);
            }
        }
        if (manager) {
            for (String tool : List.of(RoleTradingView.TOOL_PERIODIC_PREPARE,
                    RoleTradingView.TOOL_PERIODIC_AUTHORIZE,
                    RoleTradingView.TOOL_PERIODIC_PUBLISH)) {
                if (!tools.contains(tool)) {
                    tools.add(tool);
                }
            }
        }
        return List.copyOf(tools);
    }

    /** 从快照所属运行判断经理任职；与 periodicViewOf 同一世界时点。 */
    private boolean isActiveManager(WorldRuntime.Snapshot snapshot, String companyPartyId,
            String personPartyId) {
        return snapshot.roles().map(roles -> {
            for (RoleAccess.Appointment appointment : roles.access().appointments()) {
                if (appointment.personPartyId().equals(personPartyId)
                        && appointment.organizationPartyId().equals(companyPartyId)
                        && appointment.role() == RoleAccess.Role.COMPANY_MANAGER
                        && appointment.activeAt(snapshot.timeMillis())) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }

    private String dispatchEngineering(WorldRuntime current, String person,
            EngineeringCommand command) throws IOException {
        String company = command.companyId();
        String cid = command.commandId();
        String tid = command.taskId();
        long due = command.dueTimeMillis();
        String contract = command.contractId();
        String meeting = command.meetingId();
        switch (command.tool()) {
            case RoleTradingView.TOOL_BOARD_CONVENE:
            case "board.conveneWaiver":
                return command.tool().equals(RoleTradingView.TOOL_BOARD_CONVENE)
                        ? current.scheduleEngineeringConvene(cid, tid, due, company, person,
                                meeting, command.materialDeadline(), command.opensAt(),
                                command.closesAt())
                        : current.scheduleEngineeringWaiverConvene(cid, tid, due, company, person,
                                meeting, command.materialDeadline(), command.opensAt(),
                                command.closesAt());
            case BOARD_CONVENE_APPOINTMENT_TOOL:
                // 任免议题召集：与工程董事会同一入口，章程公司用其唯一治理董事会。
                return current.scheduleEngineeringAppointmentConvene(cid, tid, due, company,
                        person, meeting, command.materialDeadline(), command.opensAt(),
                        command.closesAt());
            case RoleTradingView.TOOL_BOARD_ATTEND:
                return current.scheduleEngineeringAttend(cid, tid, due, company, person, meeting,
                        command.recused());
            case RoleTradingView.TOOL_BOARD_MATERIAL:
                return current.scheduleEngineeringMaterial(cid, tid, due, company, person,
                        meeting, command.materialId(), command.docId(), command.docVersion());
            case RoleTradingView.TOOL_BOARD_PROPOSE_BUDGET:
                return current.scheduleEngineeringBudgetProposal(cid, tid, due, company, person,
                        meeting, budgetLines(command));
            case RoleTradingView.TOOL_BOARD_PROPOSE_APPOINTMENT:
                return current.scheduleEngineeringAppointmentProposal(cid, tid, due, company,
                        person, meeting, List.of(), appointmentRequest(company, command));
            case RoleTradingView.TOOL_BOARD_PROPOSE_WAIVER:
                return current.scheduleEngineeringWaiverProposal(cid, tid, due, company, person,
                        meeting, new BoardModel.DebtWaiverRequest(command.waiverId(), company,
                                contract, command.customerPartyId(), moneyOf(command.amount()),
                                command.purpose()));
            case RoleTradingView.TOOL_BOARD_VOTE:
                if (command.durationMillis() == null) {
                    return current.scheduleEngineeringVote(cid, tid, due, company, person,
                            meeting, voteOf(command.vote()));
                }
                return current.scheduleEngineeringVote(cid, tid, due, company, person, meeting,
                        voteOf(command.vote()), command.durationMillis());
            case RoleTradingView.TOOL_BOARD_IMPLEMENT:
                return current.scheduleEngineeringImplement(cid, tid, due, company, person, meeting);
            case RoleTradingView.TOOL_ENGINEERING_SIGN:
                return engineeringAction(current, person, command, "SIGN", contract, "", 0, 0L,
                        zeroMoney(), "");
            case RoleTradingView.TOOL_ENGINEERING_START_WORK:
                return engineeringAction(current, person, command, "START_WORK", contract,
                        command.workId(), 0, command.units(), zeroMoney(), "");
            case RoleTradingView.TOOL_ENGINEERING_INVOICE:
                return engineeringAction(current, person, command, "INVOICE", contract, "", 0, 0L,
                        zeroMoney(), "");
            case RoleTradingView.TOOL_ENGINEERING_REQUEST_ACCEPTANCE:
                return engineeringAction(current, person, command, "REQUEST_ACCEPTANCE", contract,
                        "", command.trancheIndex(), command.units(), zeroMoney(), "");
            case RoleTradingView.TOOL_ENGINEERING_REQUEST_RETENTION:
                return engineeringAction(current, person, command, "REQUEST_RETENTION", contract,
                        "", command.trancheIndex(), 0L, zeroMoney(), "");
            case RoleTradingView.TOOL_ENGINEERING_REQUEST_PAYMENT:
                return engineeringAction(current, person, command, "REQUEST_PAYMENT", contract, "",
                        command.trancheIndex(), 0L, zeroMoney(), "");
            case RoleTradingView.TOOL_ENGINEERING_IMPAIR:
                return engineeringAction(current, person, command, "IMPAIR", contract, "", 0, 0L,
                        moneyOf(command.amount()), command.purpose());
            case RoleTradingView.TOOL_ENGINEERING_WRITE_OFF:
                return engineeringAction(current, person, command, "WRITE_OFF", contract, "", 0, 0L,
                        moneyOf(command.amount()), command.purpose());
            case RoleTradingView.TOOL_ENGINEERING_RECOVER:
                return engineeringAction(current, person, command, "RECOVER", contract, "", 0, 0L,
                        moneyOf(command.amount()), command.purpose());
            case RoleTradingView.TOOL_ENGINEERING_PAY_SUPPLIER:
                return engineeringAction(current, person, command, "PAY_SUPPLIER", "", "", 0, 0L,
                        moneyOf(command.amount()), command.purpose());
            case RoleTradingView.TOOL_ENGINEERING_TERMINATE:
                return engineeringAction(current, person, command, "TERMINATE", contract, "", 0, 0L,
                        zeroMoney(), command.purpose());
            case RoleTradingView.TOOL_ENGINEERING_WAIVE:
                if (command.durationMillis() == null) {
                    throw new IllegalArgumentException("WAIVE 必须给出声明工时 durationMillis");
                }
                return current.scheduleEngineeringWaive(cid, tid, due, company, person, contract,
                        moneyOf(command.amount()), command.purpose(), command.authorizationRef(),
                        command.period(), command.durationMillis());
            case RoleTradingView.TOOL_PERIODIC_PREPARE:
                return periodicAction(current, person, command, "PREPARE");
            case RoleTradingView.TOOL_PERIODIC_SCORE:
                return periodicAction(current, person, command, "SCORE");
            case RoleTradingView.TOOL_PERIODIC_APPROVE:
                return periodicAction(current, person, command, "APPROVE");
            case RoleTradingView.TOOL_PERIODIC_AUTHORIZE:
                return periodicAction(current, person, command, "AUTHORIZE");
            case RoleTradingView.TOOL_PERIODIC_PUBLISH:
                return periodicAction(current, person, command, "PUBLISH");
            case "periodic.attend":
                return periodicAction(current, person, command, "ATTEND");
            default:
                throw new IllegalArgumentException("本切片未接线的工程工具：" + command.tool());
        }
    }

    /**
     * 周期人物动作的统一排程：只传递显式参数，身份/席位/实读/回忆仍由运行层复核；
     * 评分证据的 asOf 取该期真实采样截至时点，页面不提供、也不能改写。
     */
    private String periodicAction(WorldRuntime current, String person, EngineeringCommand command,
            String action) throws IOException {
        String company = command.companyId();
        long period = command.period();
        String note = command.note() == null ? "" : command.note();
        if ("ATTEND".equals(action)) {
            note = command.recused() ? "RECUSE" : "";
        }
        List<PeriodicActionPayload.PeriodicMetric> metrics = new ArrayList<>();
        if ("SCORE".equals(action)) {
            long capturedAt = -1L;
            PeriodicModel.CompanyPeriods periods = null;
            java.util.Optional<PeriodicModel.CompanyPeriods> found = current.periodicOf(company);
            if (found.isPresent()) {
                periods = found.get();
                for (PeriodicModel.Snapshot snapshot : periods.snapshots()) {
                    if (snapshot.period() == period) {
                        capturedAt = snapshot.capturedAt();
                    }
                }
            }
            if (periods == null || capturedAt < 0) {
                throw new IllegalArgumentException("没有该期采样，不能提交评分：第 " + period + " 期");
            }
            Map<String, Long> asOfByRef = periodicEvidenceAsOf(periods, company, period, capturedAt);
            for (PeriodicMetricInput input : command.metrics()) {
                String dimension = input.dimension() == null ? "" : input.dimension();
                try {
                    BoardModel.Dimension.valueOf(dimension);
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("未知评分维度：" + dimension);
                }
                String sourceRef = input.sourceRef() == null ? "" : input.sourceRef();
                Long exact = asOfByRef.get(sourceRef);
                if (sourceRef.startsWith("disclosure:") && exact == null) {
                    throw new IllegalArgumentException("披露证据缺少准确成熟时点，不能提交：" + sourceRef);
                }
                BigDecimal score;
                try {
                    score = new BigDecimal(input.score());
                } catch (RuntimeException invalid) {
                    throw new IllegalArgumentException("评分必须是十进制数字：" + dimension);
                }
                metrics.add(new PeriodicActionPayload.PeriodicMetric(dimension, score,
                        sourceRef, exact == null ? capturedAt : exact));
            }
            if (metrics.isEmpty()) {
                throw new IllegalArgumentException("评分至少需要一个完整维度");
            }
        }
        long duration = command.durationMillis() == null ? 1L : command.durationMillis();
        return current.schedulePeriodicAction(command.commandId(), command.taskId(),
                command.dueTimeMillis(), company, person, action, period,
                command.draftId() == null ? "" : command.draftId(), command.draftVersion(),
                command.publisherPersonId() == null ? "" : command.publisherPersonId(),
                command.expiresAt(), command.authorizationId() == null ? "" :
                        command.authorizationId(), metrics, note, duration);
    }

    private static String engineeringAction(WorldRuntime current, String person,
            EngineeringCommand command, String action, String contractId, String workId,
            int trancheIndex, long units, BigDecimal amount, String evidenceRef)
            throws IOException {
        if (command.durationMillis() == null) {
            return current.scheduleEngineeringAction(command.commandId(), command.taskId(),
                    command.dueTimeMillis(), command.companyId(), person, action, contractId,
                    workId, trancheIndex, units, amount, evidenceRef == null ? "" : evidenceRef,
                    command.period());
        }
        return current.scheduleEngineeringAction(command.commandId(), command.taskId(),
                command.dueTimeMillis(), command.companyId(), person, action, contractId, workId,
                trancheIndex, units, amount, evidenceRef == null ? "" : evidenceRef,
                command.period(), command.durationMillis());
    }

    private static List<BoardModel.BudgetLine> budgetLines(EngineeringCommand command) {
        List<BoardModel.BudgetLine> lines = new ArrayList<>();
        if (command.laborLimit() != null && !command.laborLimit().isBlank()) {
            lines.add(new BoardModel.BudgetLine(command.period(),
                    BoardModel.BudgetCategory.PRODUCTION_LABOR, moneyOf(command.laborLimit())));
        }
        if (command.procurementLimit() != null && !command.procurementLimit().isBlank()) {
            lines.add(new BoardModel.BudgetLine(command.period(),
                    BoardModel.BudgetCategory.PROCUREMENT, moneyOf(command.procurementLimit())));
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("预算提案至少需要 laborLimit 或 procurementLimit");
        }
        return List.copyOf(lines);
    }

    private static BoardModel.AppointmentRequest appointmentRequest(String company,
            EngineeringCommand command) {
        if (command.appointPersonId() == null || command.appointPersonId().isBlank()
                || command.appointmentId() == null || command.appointmentId().isBlank()
                || command.appointAction() == null || command.appointAction().isBlank()) {
            throw new IllegalArgumentException(
                    "任免提案需要 appointmentId / appointPersonId / appointAction");
        }
        String roleText = command.appointRole() == null || command.appointRole().isBlank()
                ? RoleAccess.Role.COMPANY_MANAGER.name() : command.appointRole();
        RoleAccess.Role role;
        try {
            role = RoleAccess.Role.valueOf(roleText);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("未知岗位：" + roleText);
        }
        BoardModel.AppointmentAction action;
        try {
            action = BoardModel.AppointmentAction.valueOf(command.appointAction());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("未知任免动作：" + command.appointAction());
        }
        Long ends = action == BoardModel.AppointmentAction.APPOINT
                ? Long.valueOf(command.endsAt()) : null;
        return new BoardModel.AppointmentRequest(command.appointmentId(),
                command.appointPersonId(), company, role, action, command.effectiveAt(), ends);
    }

    private static BoardModel.Vote voteOf(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("投票需要 vote：YES / NO / ABSTAIN");
        }
        try {
            return BoardModel.Vote.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("未知投票取值：" + value);
        }
    }

    private static BigDecimal moneyOf(String value) {
        if (value == null || value.isBlank()) {
            return zeroMoney();
        }
        try {
            return new BigDecimal(value).setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("金额必须是最多两位小数的十进制：" + value);
        }
    }

    private static String managerOf(WorldRuntime.Snapshot snapshot, String companyId) {
        RoleRun roles = snapshot.roles().orElse(null);
        if (roles == null) {
            return null;
        }
        long now = snapshot.timeMillis();
        for (RoleAccess.Appointment appointment : roles.access().appointments()) {
            if (appointment.organizationPartyId().equals(companyId)
                    && appointment.role() == RoleAccess.Role.COMPANY_MANAGER
                    && appointment.activeAt(now)) {
                return appointment.personPartyId();
            }
        }
        return null;
    }

    /** 从持久化待办载荷里只读取出公司标识；解析失败返回 null（不猜）。 */
    private static String payloadCompany(String payload) {
        return payloadText(payload, "companyPartyId");
    }

    private static String payloadText(String payload, String field) {
        com.fasterxml.jackson.databind.JsonNode node = jsonNode(payload);
        if (node == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual()
                ? null : value.textValue();
    }

    /** 严格读取 BEGIN 包装：三字段齐全且类型正确才返回；缺失/类型不符一律 null（未知即未知）。 */
    static BeginWrapper beginWrapper(String payload) {
        com.fasterxml.jackson.databind.JsonNode node = jsonNode(payload);
        if (node == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode kind = node.get("targetKind");
        com.fasterxml.jackson.databind.JsonNode due = node.get("dueMillis");
        com.fasterxml.jackson.databind.JsonNode inner = node.get("payload");
        if (kind == null || !kind.isTextual() || kind.textValue().isBlank()
                || due == null || !due.isIntegralNumber() || !due.canConvertToLong()
                || due.longValue() < 0
                || inner == null || !inner.isTextual() || inner.textValue().isBlank()) {
            return null;
        }
        if (jsonNode(inner.textValue()) == null) {
            return null;
        }
        return new BeginWrapper(kind.textValue(), due.longValue(), inner.textValue());
    }

    private static com.fasterxml.jackson.databind.JsonNode jsonNode(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    PAYLOAD_MAPPER.readTree(payload);
            return node != null && node.isObject() ? node : null;
        } catch (IOException unreadable) {
            return null;
        }
    }
}
