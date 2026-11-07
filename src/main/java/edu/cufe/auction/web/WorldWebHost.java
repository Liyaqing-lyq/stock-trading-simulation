package edu.cufe.auction.web;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.BeliefModel;
import edu.cufe.auction.world.BoardRun;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.DividendModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.InformationRun;
import edu.cufe.auction.world.ManufacturingModel;
import edu.cufe.auction.world.ManufacturingRun;
import edu.cufe.auction.world.MediaModel;
import edu.cufe.auction.world.MediaRun;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.PendingTask;
import edu.cufe.auction.world.PerformanceModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleRun;
import edu.cufe.auction.world.RoleTradingView;
import edu.cufe.auction.world.RoleView;
import edu.cufe.auction.world.ValuationModel;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.WorldScheduler;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final LiveDecisionProvider provider;
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

    /** 可参与本轮模型决定的真实人物及其已接入场景。 */
    public record LivePerson(String personPartyId, String name, String sceneId) { }

    /** 当前档案的实际执行能力；五个配置槽位不等于五类场景都已接入。 */
    public record LiveCapabilities(String position, String timeMillis, boolean mediaAvailable,
                                   List<String> supportedScenes, List<LivePerson> persons) { }

    /**
     * 查询同一截止的场景及人物能力，不调用提供方。
     * @return 已接入场景和当前有效岗位人物
     * @throws Exception 世界读取失败
     */
    public LiveCapabilities liveCapabilities() throws Exception {
        return call(() -> {
            WorldRuntime.Snapshot snapshot = readyNow().snapshot();
            List<String> scenes = workers == null || !liveRuntime() ? List.of()
                    : snapshot.media().isPresent() ? List.of("research", "trading", "media")
                    : List.of("research", "trading");
            return new LiveCapabilities(num(snapshot.position()), num(snapshot.timeMillis()),
                    snapshot.media().isPresent(), scenes,
                    scenes.isEmpty() ? List.of() : livePeople(snapshot));
        });
    }

    private List<LivePerson> livePeople(WorldRuntime.Snapshot snapshot) {
        OpeningRegistry registry = runtime.openingRegistry().orElse(null);
        Map<String, LivePerson> people = new LinkedHashMap<>();
        for (String person : LIVE_ROLE_PERSONS) {
            people.put(person, new LivePerson(person, partyName(registry, person),
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
                        new LivePerson(appointment.personPartyId(),
                                partyName(registry, appointment.personPartyId()), "media"));
            }
        }
        return List.copyOf(people.values());
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
     */
    public record LiveStatus(String runId, String position, String timeMillis, String phase,
                             boolean paused, String fault, List<String> pendingPersons,
                             int physicalInFlight, int completionSlots, BudgetUsage runBudget,
                             List<PersonBudget> personBudgets, List<SceneState> scenes,
                             List<SkipReason> skips, List<DecisionView> decisions) {
    }

    /** 一轮的 writer 私有状态。 */
    private static final class LiveRound {
        private final String roundId;
        private final LinkedHashSet<String> pending;
        private final Map<String, String> skipped = new LinkedHashMap<>();
        private final Map<String, InputSize> inputSizes = new LinkedHashMap<>();
        private boolean stopping;

        private LiveRound(String roundId, List<String> persons) {
            this.roundId = roundId;
            this.pending = new LinkedHashSet<>(persons);
        }
    }

    /**
     * 显式开始一次有界角色轮次；这是本进程唯一的模型调用入口。
     *
     * @param expectedPosition 调用方看到的世界位置
     * @param expectedRevision 调用方看到的场景配置修订
     * @param persons          1..4 个不同四岗人物
     * @throws BusyException            队列满
     * @throws IllegalArgumentException 位置/修订/人物非法（无副作用）
     * @throws IllegalStateException    Host 未接线 live、暂停、故障、已有轮次或有屏障
     * @throws Exception                世界读写失败
     */
    public void startLiveRound(long expectedPosition, long expectedRevision, List<String> persons)
            throws BusyException, Exception {
        call(() -> {
            startLiveRoundOnWriter(expectedPosition, expectedRevision, persons);
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
                                        List<String> persons)
            throws IOException, PositionMismatch, SceneRoutes.RevisionMismatch {
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
        List<String> allowed = livePeople(current.snapshot()).stream()
                .map(LivePerson::personPartyId).toList();
        if (persons == null || persons.isEmpty() || persons.size() > allowed.size()) {
            throw new IllegalArgumentException("本轮人数必须在 1.." + allowed.size());
        }
        Set<String> distinct = new LinkedHashSet<>(persons);
        if (distinct.size() != persons.size() || !allowed.containsAll(distinct)) {
            throw new IllegalArgumentException("人物必须来自当前档案已接入的不同岗位人物");
        }
        if (liveRound != null && (!liveRound.pending.isEmpty()
                || !physicalInFlight.isEmpty())) {
            throw new IllegalStateException("已有活动轮次或未结算调用");
        }
        String roundId = "live:" + expectedPosition + ":" + expectedRevision;
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
                fairOrder(current.snapshot().decisions().orElseThrow().state().seed(), persons));
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
            String scene = sceneFor(person);
            if (scene == null) {
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
                personBudgets, scenes, skips, decisions);
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
}
