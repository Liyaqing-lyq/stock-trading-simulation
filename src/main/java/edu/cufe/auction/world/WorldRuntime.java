package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.SecuritiesLedger.ActionReport;
import edu.cufe.auction.world.SecuritiesLedger.SellReservation;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 可恢复的世界运行：单一日志、可序列化待办、现金子集。
 *
 * <p><b>提交边界</b>：每个命令/任务形成一个批次，批次里同时保存领域事件与**完整不可变子集状态**
 * （时钟、现金账户、待办、已投递回报、已用任务标识、命令回报）。只有
 * {@link WorldJournal#commit} 强制落盘成功后，运行才发布新状态；失败则保留旧快照、
 * 保留待办并暂停运行。</p>
 *
 * <p><b>恢复</b>：只解码并校验已提交状态，不重跑任务处理、不询问模型、不重新发放开局。
 * 恢复出来的是“待办数据”，执行器由 {@link #advanceTo(long)} 按 kind 解析。</p>
 *
 * <p><b>能力边界</b>：当前只启用现金转账、回报投递，以及可选的开局登记与证券清算夹具
 * （批次、卖出冻结、撤销、同批清算成交）。证券部分只有受信的系统夹具，没有订单簿、撮合、
 * 角色交易授权、费用或价格限制。规则、模块、模型版本在构造期校验，未启用能力在提交前即被拒绝。
 * 快照是管理端只读视图，不面向不可信角色。</p>
 */
public final class WorldRuntime implements AutoCloseable {

    private static final String OPEN_COMMAND = "__open__";
    private static final String SNAPSHOT_TYPE = "STATE_SNAPSHOT_V1";
    /** 纯现金/登记档案的世界状态版本（保持原样）。 */
    private static final int STATE_SCHEMA = 1;
    /** 含完整证券子集状态的世界状态版本（M2.3）。 */
    private static final int STATE_SCHEMA_SECURITIES = 2;

    /** 同一受益主体的两个账户不能通过清算夹具自成交。 */
    public static final String REASON_SELF_TRADE_PREVENTED = "SELF_TRADE_PREVENTED";
    /** 买方可用现金不足。 */
    public static final String REASON_INSUFFICIENT_CASH = "INSUFFICIENT_CASH";
    /** 调用方不是该账户的财产主体。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 账户或证券未登记。 */
    public static final String REASON_UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RunManifest manifest;
    private final OpeningRegistry registry;
    private final long seed;
    private final WorldJournal journal;
    private final Thread writerThread = Thread.currentThread();
    private WorldState state;
    private volatile boolean paused;
    private boolean closed;

    /**
     * 已投递的业务回报（只读管理视图）。
     *
     * @param noticeId          回报标识
     * @param taskId            产生该回报的任务
     * @param commandId         原始命令
     * @param accepted          转账是否成功
     * @param reason            结果代码
     * @param fromId            付款账户
     * @param toId              收款账户
     * @param amount            金额
     * @param deliveredAtMillis 投递的模拟毫秒
     */
    public record Notice(String noticeId, String taskId, String commandId, boolean accepted,
                         String reason, String fromId, String toId, BigDecimal amount,
                         long deliveredAtMillis) {
    }

    /**
     * 管理端只读快照：同一完整提交位置的全部子集状态。
     *
     * <p>{@code securities} 是同一截止位置的证券子集（批次、预留、业务结果），只对启用了证券清算
     * 夹具的运行存在；{@code openingRegistry()} 仍然只表示开局分配，不是交易后的实时持仓。</p>
     *
     * @param position    最后完整提交位置
     * @param timeMillis  当前模拟毫秒
     * @param lastPhase   最后完成的阶段名
     * @param accounts    现金账户（不可变）
     * @param securities  证券子集；未启用时为空
     * @param pending     未完成待办（不可变）
     * @param delivered   已投递回报（不可变）
     * @param usedTaskIds 已用任务标识（不可变）
     */
    public record Snapshot(long position, long timeMillis, String lastPhase,
                           Map<String, CashLedger.CashAccount> accounts,
                           Optional<SecuritiesLedger.State> securities,
                           List<PendingTask> pending, List<Notice> delivered,
                           Set<String> usedTaskIds) {
        /** 防御性冻结所有集合。 */
        public Snapshot {
            accounts = Collections.unmodifiableMap(new TreeMap<>(accounts));
            securities = Objects.requireNonNull(securities, "securities");
            pending = List.copyOf(pending);
            delivered = List.copyOf(delivered);
            usedTaskIds = Collections.unmodifiableSet(new LinkedHashSet<>(usedTaskIds));
        }
    }

    /** 排程命令的规范化内容，用于幂等比对。 */
    private record ScheduleCommand(String commandId, String taskId, long dueTimeMillis,
                                   String actorOwnerId, String fromId, String toId,
                                   BigDecimal amount, long receiptDelayMillis) {
    }

    /** 转账任务参数；回报到期时点在排程阶段算好并持久化，完成时不再做可能溢出的加法。 */
    private record TransferPayload(String commandId, String actorOwnerId, String fromId, String toId,
                                   BigDecimal amount, long receiptDelayMillis, long noticeDueMillis) {
    }

    /** 回报投递任务参数；sourceTaskId 指向产生回报的原任务。 */
    private record NoticePayload(String noticeId, String sourceTaskId, String commandId,
                                 String fromId, String toId, BigDecimal amount, boolean accepted,
                                 String reason) {
    }

    /** 预留卖出的规范化命令内容（幂等比对用）。 */
    private record ReserveCommand(String commandId, String taskId, long dueTimeMillis,
                                  String actorOwnerId, String accountId, String symbol,
                                  String reservationId, long quantity) {
    }

    /** 预留卖出任务参数。 */
    private record ReservePayload(String commandId, String actorOwnerId, String accountId,
                                  String symbol, String reservationId, long quantity) {
    }

    /** 撤销预留的规范化命令内容。 */
    private record CancelCommand(String commandId, String taskId, long dueTimeMillis,
                                 String actorOwnerId, String reservationId) {
    }

    /** 撤销预留任务参数。 */
    private record CancelPayload(String commandId, String actorOwnerId, String reservationId) {
    }

    /** 系统清算夹具成交的规范化命令内容。 */
    private record FillCommand(String commandId, String taskId, long dueTimeMillis,
                               String reservationId, String buyerAccountId, BigDecimal price,
                               long quantity) {
    }

    /** 系统清算夹具成交任务参数。 */
    private record FillPayload(String commandId, String reservationId, String buyerAccountId,
                               BigDecimal price, long quantity) {
    }

    /** 完整不可变子集状态；每批提交保存一份，恢复逐批校验后取最后一份。 */
    private record WorldState(int schemaVersion, String runId, long position, long timeMillis,
                              String lastPhase, Map<String, CashLedger.CashAccount> accounts,
                              List<PendingTask> pending, List<Notice> delivered,
                              Set<String> usedTaskIds, Map<String, String> commandContents,
                              Map<String, String> commandOutcomes,
                              @JsonInclude(JsonInclude.Include.NON_NULL)
                              SecuritiesLedger.State securities) {

        WorldState {
            accounts = Collections.unmodifiableMap(new TreeMap<>(accounts));
            pending = List.copyOf(pending);
            delivered = List.copyOf(delivered);
            usedTaskIds = Collections.unmodifiableSet(new LinkedHashSet<>(usedTaskIds));
            commandContents = Collections.unmodifiableMap(new TreeMap<>(commandContents));
            commandOutcomes = Collections.unmodifiableMap(new TreeMap<>(commandOutcomes));
        }
    }

    /**
     * 打开或恢复一次现金子集运行。
     *
     * @param file     运行专用日志（本类独占）
     * @param manifest 运行清单
     * @param openings 开局现金清单，必须带历史来源
     * @throws IOException 日志不可用、清单或开局与持久记录不一致
     */
    public WorldRuntime(Path file, RunManifest manifest, List<OpeningAccount> openings)
            throws IOException {
        this(file, manifest, openings, null);
    }

    /**
     * 打开或恢复一次带开局登记的运行。
     *
     * <p>登记与清单必须成对：registry 清单缺登记、或现金清单多带登记，都在创建任何文件之前拒绝。
     * 登记先与现有现金账户对齐（属主可解析、持股账户存在、库存股归发行组织），再写入第一批。</p>
     *
     * @param file     运行日志（本类独占）
     * @param manifest 运行清单
     * @param openings 现金开局清单
     * @param registry 开局登记；现金模式为 null
     * @throws IOException 日志不可用、清单/开局/登记与持久记录不一致
     */
    public WorldRuntime(Path file, RunManifest manifest, List<OpeningAccount> openings,
                        OpeningRegistry registry) throws IOException {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        if (manifest.hasRegistry() && registry == null) {
            throw new IllegalArgumentException("规则档案 " + manifest.rulesVersion()
                    + " 必须提供开局登记");
        }
        if (!manifest.hasRegistry() && registry != null) {
            throw new IllegalArgumentException("现金模式运行不接受开局登记");
        }
        this.registry = registry;
        this.seed = manifest.seed();
        List<OpeningAccount> ordered = new ArrayList<>(Objects.requireNonNull(openings, "openings"));
        ordered.sort(Comparator.comparing(OpeningAccount::accountId));
        Map<String, CashLedger.CashAccount> accounts = new TreeMap<>();
        for (OpeningAccount opening : ordered) {
            if (accounts.putIfAbsent(opening.accountId(),
                    new CashLedger.CashAccount(opening.ownerId(), opening.cash(), opening.source()))
                    != null) {
                throw new IllegalArgumentException("重复开局账户：" + opening.accountId());
            }
        }
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("开局账户不能为空");
        }
        if (registry != null) {
            // 在创建日志文件之前完成对齐：属主可解析、持股账户存在、库存股归发行组织
            registry.validateAgainst(ordered);
        }
        WorldState initial = initialState(accounts);
        this.journal = new WorldJournal(file, manifest.runId());
        try {
            if (journal.commits().isEmpty()) {
                String content = openingContent(ordered);
                List<WorldEvent> events = new ArrayList<>();
                events.add(new WorldEvent(OPEN_COMMAND + ":manifest", "RUN_MANIFEST_V1", content));
                if (registry != null) {
                    events.add(new WorldEvent(OPEN_COMMAND + ":registry", "OPENING_REGISTRY_V1",
                            mapper.writeValueAsString(registry)));
                }
                events.add(snapshotEvent(initial));
                journal.commit(OPEN_COMMAND, content, "OPENED", events);
            }
            this.state = restore(ordered, initial);
        } catch (IOException | RuntimeException failure) {
            journal.close();
            throw failure;
        }
    }

    /**
     * 排程一次现金转账：排程本身持久提交，到期才结算。
     *
     * @param commandId          外部命令标识，重发返回原回报
     * @param taskId             任务标识，运行内不复用
     * @param dueTimeMillis      到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId       可信调用方解析的财产主体
     * @param fromId             付款账户
     * @param toId               收款账户
     * @param amount             正数、精确到分
     * @param receiptDelayMillis 回报渠道延迟，非负
     * @return 规范化回报文本；重发返回同一文本
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力或时间非法
     */
    public synchronized String scheduleTransfer(String commandId, String taskId, long dueTimeMillis,
                                                String actorOwnerId, String fromId, String toId,
                                                BigDecimal amount, long receiptDelayMillis)
            throws IOException {
        requireOpen();
        requireWriter();
        if (paused) {
            throw new IllegalStateException("运行已暂停，不接受新排程；须先恢复");
        }
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(actorOwnerId, "财产主体");
        requireText(fromId, "付款账户");
        requireText(toId, "收款账户");
        if (receiptDelayMillis < 0) {
            throw new IllegalArgumentException("回报延迟不能为负");
        }
        BigDecimal normalized = CashLedger.money(amount);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("转账金额必须为正");
        }
        long noticeDueMillis;
        try {
            noticeDueMillis = Math.addExact(dueTimeMillis, Math.max(1L, receiptDelayMillis));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("到期时间与回报延迟组合溢出，无法表达回报时点", overflow);
        }
        ScheduleCommand command = new ScheduleCommand(commandId, taskId, dueTimeMillis,
                actorOwnerId, fromId, toId, normalized, receiptDelayMillis);
        String content = mapper.writeValueAsString(command);
        String previousContent = state.commandContents().get(commandId);
        if (previousContent != null) {
            if (!previousContent.equals(content)) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return state.commandOutcomes().get(commandId);
        }
        if (dueTimeMillis <= state.timeMillis()) {
            throw new IllegalArgumentException("到期时间必须晚于当前时点：" + state.timeMillis());
        }
        if (state.usedTaskIds().contains(taskId)) {
            throw new IllegalArgumentException("任务标识运行内不复用：" + taskId);
        }
        CashLedger.CashAccount from = state.accounts().get(fromId);
        if (from == null || !from.ownerId().equals(actorOwnerId)) {
            throw new IllegalArgumentException("该财产主体无权动用账户：" + fromId);
        }
        if (!state.accounts().containsKey(toId)) {
            throw new IllegalArgumentException("未知收款账户：" + toId);
        }

        TransferPayload payload = new TransferPayload(commandId, actorOwnerId, fromId, toId,
                normalized, receiptDelayMillis, noticeDueMillis);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, PendingTask.CASH_TRANSFER_V1,
                mapper.writeValueAsString(payload));
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.add(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(taskId);
        Map<String, String> contents = new TreeMap<>(state.commandContents());
        contents.put(commandId, content);
        Map<String, String> outcomes = new TreeMap<>(state.commandOutcomes());
        String outcome = "SCHEDULED:" + taskId;
        outcomes.put(commandId, outcome);

        WorldState candidate = next(state.timeMillis(), state.lastPhase(), state.accounts(),
                pending, state.delivered(), used, contents, outcomes, state.securities());
        commitAndApply("cmd:" + commandId, content, outcome,
                List.of(new WorldEvent("task-scheduled:" + taskId, "TASK_SCHEDULED_V1",
                        mapper.writeValueAsString(task))), candidate);
        return outcome;
    }

    /**
     * 排程一次预留卖出：只允许管理端的系统清算夹具调用，冻结合格可卖批次。
     *
     * <p>排程本身持久提交，到期才按最新持仓复核；因此这里只做结构性校验（属主、账户、证券、
     * 数量、时点、标识唯一）。数量不足等业务原因在执行期以 {@link ActionReport} 记录，
     * 不改动资产。重发同一命令返回原 {@code SCHEDULED} 回报，不会因时点已过再排一次。</p>
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId  可信调用方解析的财产主体
     * @param accountId     卖方账户
     * @param symbol        证券代码
     * @param reservationId 预留标识，运行内不复用
     * @param quantity      预留数量，必须为正
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、权属、能力或时间非法
     */
    public synchronized String scheduleReserveSell(String commandId, String taskId,
                                                   long dueTimeMillis, String actorOwnerId,
                                                   String accountId, String symbol,
                                                   String reservationId, long quantity)
            throws IOException {
        requireSecuritiesEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(actorOwnerId, "财产主体");
        requireText(accountId, "账户");
        requireText(symbol, "证券代码");
        requireText(reservationId, "预留标识");
        if (quantity <= 0) {
            throw new IllegalArgumentException("预留数量必须为正：" + quantity);
        }
        ReserveCommand command = new ReserveCommand(commandId, taskId, dueTimeMillis, actorOwnerId,
                accountId, symbol, reservationId, quantity);
        String content = mapper.writeValueAsString(command);
        String replay = state.commandOutcomes().get(commandId);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return replay;
        }
        requireSchedulable(dueTimeMillis, taskId);
        CashLedger.CashAccount account = state.accounts().get(accountId);
        if (account == null || !account.ownerId().equals(actorOwnerId)) {
            throw new IllegalArgumentException("该财产主体无权动用账户：" + accountId);
        }
        if (registry.security(symbol) == null) {
            throw new IllegalArgumentException("未知证券：" + symbol);
        }
        if (state.securities().usedReservationIds().contains(reservationId)) {
            throw new IllegalArgumentException("预留标识已使用，运行内不复用：" + reservationId);
        }
        ReservePayload payload = new ReservePayload(commandId, actorOwnerId, accountId, symbol,
                reservationId, quantity);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, PendingTask.SELL_RESERVE_V1,
                mapper.writeValueAsString(payload));
        return scheduleSecuritiesTask(task, commandId, content);
    }

    /**
     * 排程一次撤销预留：只释放该预留尚未成交的数量。
     *
     * <p>预留可能在排程之后才由另一任务创建，因此属主与存在性在到期时复核；未知预留是业务拒绝，
     * 不是技术异常。</p>
     *
     * @param commandId     外部命令标识
     * @param taskId        任务标识，运行内不复用
     * @param dueTimeMillis 到期模拟毫秒，必须晚于当前时点
     * @param actorOwnerId  可信调用方解析的财产主体
     * @param reservationId 预留标识
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力或时间非法
     */
    public synchronized String scheduleCancelSell(String commandId, String taskId,
                                                  long dueTimeMillis, String actorOwnerId,
                                                  String reservationId) throws IOException {
        requireSecuritiesEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(actorOwnerId, "财产主体");
        requireText(reservationId, "预留标识");
        CancelCommand command = new CancelCommand(commandId, taskId, dueTimeMillis, actorOwnerId,
                reservationId);
        String content = mapper.writeValueAsString(command);
        String replay = state.commandOutcomes().get(commandId);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return replay;
        }
        requireSchedulable(dueTimeMillis, taskId);
        CancelPayload payload = new CancelPayload(commandId, actorOwnerId, reservationId);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, PendingTask.SELL_CANCEL_V1,
                mapper.writeValueAsString(payload));
        return scheduleSecuritiesTask(task, commandId, content);
    }

    /**
     * 排程一次同批清算成交：受信的系统夹具，不是买方下单接口。
     *
     * <p>到期时复核预留是否仍活动、买卖双方是否为不同财产受益主体、买方现金是否足够；
     * 成功时现金、股份、预留、报告与全量状态在同一个批次持久确认后才发布。</p>
     *
     * @param commandId      外部命令标识
     * @param taskId         任务标识，运行内不复用
     * @param dueTimeMillis  到期模拟毫秒，必须晚于当前时点
     * @param reservationId  卖方预留标识
     * @param buyerAccountId 买方账户
     * @param price          成交价，精确到分且为正
     * @param quantity       成交数量，必须为正
     * @return 规范化回报文本 {@code SCHEDULED:taskId}
     * @throws IOException              持久提交失败
     * @throws IllegalArgumentException 参数、能力或时间非法
     */
    public synchronized String scheduleFixtureFill(String commandId, String taskId,
                                                   long dueTimeMillis, String reservationId,
                                                   String buyerAccountId, BigDecimal price,
                                                   long quantity) throws IOException {
        requireSecuritiesEnabled();
        requireOpen();
        requireWriter();
        requireNotPaused();
        requireText(commandId, "命令标识");
        requireText(taskId, "任务标识");
        requireText(reservationId, "预留标识");
        requireText(buyerAccountId, "买方账户");
        if (quantity <= 0) {
            throw new IllegalArgumentException("成交数量必须为正：" + quantity);
        }
        BigDecimal normalized = CashLedger.money(price);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("成交价必须为正");
        }
        FillCommand command = new FillCommand(commandId, taskId, dueTimeMillis, reservationId,
                buyerAccountId, normalized, quantity);
        String content = mapper.writeValueAsString(command);
        String replay = state.commandOutcomes().get(commandId);
        if (state.commandContents().containsKey(commandId)) {
            if (!content.equals(state.commandContents().get(commandId))) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return replay;
        }
        requireSchedulable(dueTimeMillis, taskId);
        if (!state.accounts().containsKey(buyerAccountId)) {
            throw new IllegalArgumentException("未知买方账户：" + buyerAccountId);
        }
        FillPayload payload = new FillPayload(commandId, reservationId, buyerAccountId,
                normalized, quantity);
        PendingTask task = new PendingTask(taskId, dueTimeMillis,
                WorldScheduler.Phase.COMPLETION, PendingTask.FILL_SETTLE_V1,
                mapper.writeValueAsString(payload));
        return scheduleSecuritiesTask(task, commandId, content);
    }

    /**
     * 推进到目标模拟毫秒：按公平顺序执行到期待办，再为时钟边界落一个完整批次。
     *
     * @param inclusiveMillis 目标模拟毫秒，不得早于当前时间
     * @throws IOException 持久提交失败；此时运行暂停、状态不前进
     */
    public synchronized void advanceTo(long inclusiveMillis) throws IOException {
        requireOpen();
        requireWriter();
        if (paused) {
            throw new IllegalStateException("运行已暂停，须恢复后继续");
        }
        if (inclusiveMillis < state.timeMillis()) {
            throw new IllegalArgumentException("世界时间不能倒退：" + state.timeMillis());
        }
        try {
            while (true) {
                PendingTask next = peek(inclusiveMillis);
                if (next == null) {
                    break;
                }
                switch (next.kind()) {
                    case PendingTask.CASH_TRANSFER_V1 -> completeTransfer(next);
                    case PendingTask.TRANSFER_NOTICE_V1 -> deliverNotice(next);
                    case PendingTask.SELL_RESERVE_V1 -> completeReserve(next);
                    case PendingTask.SELL_CANCEL_V1 -> completeCancel(next);
                    case PendingTask.FILL_SETTLE_V1 -> completeFill(next);
                    default -> throw new IllegalStateException("未启用的任务种类：" + next.kind());
                }
            }
            if (inclusiveMillis > state.timeMillis()) {
                String content = "{\"timeMillis\":" + inclusiveMillis + "}";
                WorldState candidate = next(inclusiveMillis,
                        WorldScheduler.Phase.OBSERVATION.name(), state.accounts(), state.pending(),
                        state.delivered(), state.usedTaskIds(), state.commandContents(),
                        state.commandOutcomes(), state.securities());
                commitAndApply("clock:" + inclusiveMillis, content, "ADVANCED",
                        List.of(new WorldEvent("clock:" + inclusiveMillis, "CLOCK_ADVANCED_V1",
                                content)), candidate);
            }
        } catch (RuntimeException failure) {
            // 内部执行期（含 ID 冲突、序列化、溢出）失败同样进入暂停，保留旧快照
            paused = true;
            throw failure;
        }
    }

    /** @return 当前管理端快照（只读、同一提交位置） */
    public synchronized Snapshot snapshot() {
        return new Snapshot(state.position(), state.timeMillis(), state.lastPhase(),
                state.accounts(), Optional.ofNullable(state.securities()), state.pending(),
                state.delivered(), state.usedTaskIds());
    }

    /**
     * 开局登记的管理端视图。
     *
     * <p>这是**开局分配**的只读快照，不是实时持仓：股份的后续变动只体现在
     * {@link Snapshot#securities()} 的批次台账里。两者必须分开读，不能用开局分配冒充当前持股，
     * 也不能用当前批次反推开局时的历史归属。</p>
     *
     * @return 现金模式为空；带登记的运行返回开局分配
     */
    public Optional<OpeningRegistry> openingRegistry() {
        return Optional.ofNullable(registry);
    }

    /** @return 是否因提交失败而暂停 */
    public boolean isPaused() {
        return paused;
    }

    /** 关闭日志；已发布快照仍可读取。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            journal.close();
        } catch (IOException ignored) {
            // 关闭失败不影响已发布快照；日志锁由进程退出回收
        }
    }

    // ------------------------------------------------------------------
    // 内部：均要求持有本对象监视器（synchronized 方法内调用）
    // ------------------------------------------------------------------

    private void completeTransfer(PendingTask task) throws IOException {
        TransferPayload payload = mapper.readValue(task.payload(), TransferPayload.class);
        CashLedger.TransferOutcome outcome = CashLedger.computeTransfer(state.accounts(),
                payload.actorOwnerId(), payload.fromId(), payload.toId(), payload.amount());

        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        if (outcome.accepted()) {
            events.add(new WorldEvent("cash:" + task.taskId(), "CASH_TRANSFERRED_V1",
                    mapper.writeValueAsString(payload)));
        }

        String noticeTaskId = freeNoticeTaskId(task.taskId(), pending, used);
        String noticeId = "notice:" + task.taskId();
        PendingTask noticeTask = new PendingTask(noticeTaskId, payload.noticeDueMillis(),
                WorldScheduler.Phase.DELIVERY, PendingTask.TRANSFER_NOTICE_V1,
                mapper.writeValueAsString(new NoticePayload(noticeId, task.taskId(),
                        payload.commandId(), payload.fromId(), payload.toId(), payload.amount(),
                        outcome.accepted(), outcome.reason())));
        pending.add(noticeTask);
        used.add(noticeTaskId);
        events.add(new WorldEvent("task-scheduled:" + noticeTaskId, "TASK_SCHEDULED_V1",
                mapper.writeValueAsString(noticeTask)));

        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(),
                outcome.accounts(), pending, state.delivered(), used, state.commandContents(),
                state.commandOutcomes(), state.securities());
        commitAndApply("task:" + task.taskId(), task.payload(), outcome.reason(), events, candidate);
    }

    private void deliverNotice(PendingTask task) throws IOException {
        NoticePayload payload = mapper.readValue(task.payload(), NoticePayload.class);
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<Notice> delivered = new ArrayList<>(state.delivered());
        delivered.add(new Notice(payload.noticeId(), payload.sourceTaskId(), payload.commandId(),
                payload.accepted(), payload.reason(), payload.fromId(), payload.toId(),
                payload.amount(), task.dueTimeMillis()));
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), state.accounts(),
                pending, delivered, used, state.commandContents(), state.commandOutcomes(),
                state.securities());
        commitAndApply("task:" + task.taskId(), task.payload(), "DELIVERED",
                List.of(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                                task.payload()),
                        new WorldEvent("notice:" + payload.noticeId(), "NOTICE_DELIVERED_V1",
                                task.payload())),
                candidate);
    }

    /**
     * 预留卖出：到期按最新持仓复核；业务拒绝也保存报告，不改变资产。
     */
    private void completeReserve(PendingTask task) throws IOException {
        ReservePayload payload = mapper.readValue(task.payload(), ReservePayload.class);
        long now = task.dueTimeMillis();
        CashLedger.CashAccount account = state.accounts().get(payload.accountId());
        String reason;
        boolean accepted = false;
        SecuritiesLedger.State candidate = state.securities();
        if (account == null) {
            reason = REASON_UNKNOWN_ACCOUNT;
        } else if (!account.ownerId().equals(payload.actorOwnerId())) {
            reason = REASON_NOT_AUTHORIZED;
        } else if (registry.security(payload.symbol()) == null) {
            reason = REASON_UNKNOWN_ACCOUNT;
        } else {
            SecuritiesLedger.Outcome outcome = SecuritiesLedger.reserve(state.securities(),
                    payload.reservationId(), payload.accountId(), payload.symbol(),
                    payload.quantity(), SecuritiesLedger.day(now));
            accepted = outcome.accepted();
            reason = outcome.reason();
            candidate = outcome.state();
        }
        ActionReport report = new ActionReport(task.taskId(), payload.commandId(),
                PendingTask.SELL_RESERVE_V1, accepted, reason, now, state.position() + 1);
        finishSecuritiesTask(task, state.accounts(), candidate.withReport(report), report,
                List.of());
    }

    /**
     * 撤销预留：只释放未成交冻结；未知或越权是业务拒绝。
     */
    private void completeCancel(PendingTask task) throws IOException {
        CancelPayload payload = mapper.readValue(task.payload(), CancelPayload.class);
        long now = task.dueTimeMillis();
        SellReservation reservation = SecuritiesLedger.reservation(state.securities(),
                payload.reservationId());
        String reason;
        boolean accepted = false;
        SecuritiesLedger.State candidate = state.securities();
        if (reservation == null) {
            reason = SecuritiesLedger.REASON_UNKNOWN_RESERVATION;
        } else {
            CashLedger.CashAccount account = state.accounts().get(reservation.accountId());
            if (account == null) {
                reason = REASON_UNKNOWN_ACCOUNT;
            } else if (!account.ownerId().equals(payload.actorOwnerId())) {
                reason = REASON_NOT_AUTHORIZED;
            } else {
                SecuritiesLedger.Outcome outcome = SecuritiesLedger.cancel(state.securities(),
                        payload.reservationId());
                accepted = outcome.accepted();
                reason = outcome.reason();
                candidate = outcome.state();
            }
        }
        ActionReport report = new ActionReport(task.taskId(), payload.commandId(),
                PendingTask.SELL_CANCEL_V1, accepted, reason, now, state.position() + 1);
        finishSecuritiesTask(task, state.accounts(), candidate.withReport(report), report,
                List.of());
    }

    /**
     * 同批清算成交：现金、股份、预留、报告与全量状态必须在同一批次持久确认后才发布。
     *
     * <p>同一受益主体的两个账户拒绝成交（不把同一组织的不同产品合并成一个主体）；
     * 买方现金不足、预留不足或预留已结束时都不留下单边效果。</p>
     */
    private void completeFill(PendingTask task) throws IOException {
        FillPayload payload = mapper.readValue(task.payload(), FillPayload.class);
        long now = task.dueTimeMillis();
        SellReservation reservation = SecuritiesLedger.reservation(state.securities(),
                payload.reservationId());
        String reason;
        boolean accepted = false;
        SecuritiesLedger.State securitiesCandidate = state.securities();
        Map<String, CashLedger.CashAccount> accountsCandidate = state.accounts();
        List<WorldEvent> effects = new ArrayList<>();
        if (reservation == null) {
            reason = SecuritiesLedger.REASON_UNKNOWN_RESERVATION;
        } else {
            CashLedger.CashAccount seller = state.accounts().get(reservation.accountId());
            CashLedger.CashAccount buyer = state.accounts().get(payload.buyerAccountId());
            if (seller == null || buyer == null) {
                reason = REASON_UNKNOWN_ACCOUNT;
            } else if (seller.ownerId().equals(buyer.ownerId())) {
                reason = REASON_SELF_TRADE_PREVENTED;
            } else if (registry.security(reservation.symbol()) == null) {
                reason = REASON_UNKNOWN_ACCOUNT;
            } else {
                SecuritiesLedger.FillOutcome filled = SecuritiesLedger.fill(state.securities(),
                        task.taskId(), payload.reservationId(), payload.buyerAccountId(),
                        payload.price(), payload.quantity(), now);
                if (!filled.accepted()) {
                    reason = filled.reason();
                } else {
                    CashLedger.TransferOutcome cash = CashLedger.computeTransfer(
                            state.accounts(), buyer.ownerId(), payload.buyerAccountId(),
                            reservation.accountId(), filled.amount());
                    if (!cash.accepted()) {
                        reason = cash.reason();
                    } else {
                        accepted = true;
                        reason = filled.reason();
                        securitiesCandidate = filled.state();
                        accountsCandidate = cash.accounts();
                        Map<String, Object> effect = new LinkedHashMap<>();
                        effect.put("reservationId", payload.reservationId());
                        effect.put("sellerAccountId", reservation.accountId());
                        effect.put("buyerAccountId", payload.buyerAccountId());
                        effect.put("price", payload.price());
                        effect.put("quantity", payload.quantity());
                        effect.put("amount", filled.amount());
                        effects.add(new WorldEvent("cash:" + task.taskId(),
                                "SECURITIES_CASH_TRANSFERRED_V1", mapper.writeValueAsString(effect)));
                        effects.add(new WorldEvent("securities:" + task.taskId(),
                                "SECURITIES_FILLED_V1", mapper.writeValueAsString(effect)));
                    }
                }
            }
        }
        ActionReport report = new ActionReport(task.taskId(), payload.commandId(),
                PendingTask.FILL_SETTLE_V1, accepted, reason, now, state.position() + 1);
        finishSecuritiesTask(task, accountsCandidate, securitiesCandidate.withReport(report),
                report, effects);
    }

    /**
     * 清算夹具任务的统一收尾：完成待办、追加报告、保存全量状态并在持久确认后发布。
     */
    private void finishSecuritiesTask(PendingTask task,
                                      Map<String, CashLedger.CashAccount> accounts,
                                      SecuritiesLedger.State securities,
                                      ActionReport report, List<WorldEvent> effects)
            throws IOException {
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.remove(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        List<WorldEvent> events = new ArrayList<>();
        events.add(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                task.payload()));
        events.addAll(effects);
        events.add(new WorldEvent("securities-report:" + task.taskId(), "SECURITIES_REPORT_V1",
                mapper.writeValueAsString(report)));
        WorldState candidate = next(task.dueTimeMillis(), task.phase().name(), accounts, pending,
                state.delivered(), used, state.commandContents(), state.commandOutcomes(),
                securities);
        commitAndApply("task:" + task.taskId(), task.payload(), report.reason(), events, candidate);
    }

    private void commitAndApply(String commandId, String content, String result,
                                List<WorldEvent> domainEvents, WorldState candidate)
            throws IOException {
        try {
            validate(candidate, candidate.position(), state);
        } catch (IOException invalid) {
            paused = true;
            throw invalid;
        }
        List<WorldEvent> events = new ArrayList<>(domainEvents);
        events.add(snapshotEvent(candidate));
        try {
            journal.commit(commandId, content, result, events);
        } catch (IOException | RuntimeException failure) {
            // 提交期任何失败（含重复事件 ID、超大帧）都暂停并保留旧快照
            paused = true;
            throw failure;
        }
        state = candidate;
    }

    private PendingTask peek(long inclusiveMillis) {
        Comparator<PendingTask> order = Comparator
                .comparingLong(PendingTask::dueTimeMillis)
                .thenComparingInt(task -> task.phase().ordinal())
                .thenComparing(task -> WorldScheduler.fairOrder(seed, task.taskId()))
                .thenComparing(PendingTask::taskId);
        PendingTask best = null;
        for (PendingTask task : state.pending()) {
            if (task.dueTimeMillis() <= inclusiveMillis
                    && (best == null || order.compare(task, best) < 0)) {
                best = task;
            }
        }
        return best;
    }

    private void validate(WorldState candidate, long expectedPosition, WorldState previous)
            throws IOException {
        if (candidate.schemaVersion() != expectedSchema()) {
            throw new IOException("状态版本不支持：" + candidate.schemaVersion()
                    + "（本次运行需要 " + expectedSchema() + "）");
        }
        if (!manifest.runId().equals(candidate.runId())) {
            throw new IOException("状态运行标识不匹配");
        }
        if (candidate.position() != expectedPosition) {
            throw new IOException("状态位置不连续：" + candidate.position());
        }
        if (previous != null) {
            if (candidate.timeMillis() < previous.timeMillis()) {
                throw new IOException("状态时间倒退");
            }
            if (!candidate.accounts().keySet().equals(previous.accounts().keySet())) {
                throw new IOException("状态账户集合被改变");
            }
            for (Map.Entry<String, CashLedger.CashAccount> entry : candidate.accounts().entrySet()) {
                CashLedger.CashAccount before = previous.accounts().get(entry.getKey());
                CashLedger.CashAccount now = entry.getValue();
                if (!before.ownerId().equals(now.ownerId())) {
                    throw new IOException("状态账户权属被改变：" + entry.getKey());
                }
                if (!before.source().equals(now.source())) {
                    throw new IOException("状态开局来源被改变：" + entry.getKey());
                }
            }
            if (total(candidate).compareTo(total(previous)) != 0) {
                throw new IOException("状态现金总额不守恒");
            }
            if (!candidate.usedTaskIds().containsAll(previous.usedTaskIds())) {
                throw new IOException("状态已用任务标识丢失");
            }
            if (!candidate.commandContents().keySet().containsAll(previous.commandContents().keySet())) {
                throw new IOException("状态命令记录丢失");
            }
            if (candidate.delivered().size() < previous.delivered().size()) {
                throw new IOException("状态已投递回报丢失");
            }
            for (int i = 0; i < previous.delivered().size(); i++) {
                if (!candidate.delivered().get(i).equals(previous.delivered().get(i))) {
                    throw new IOException("状态已投递回报被改写：" + i);
                }
            }
            if (!candidate.commandOutcomes().keySet().equals(candidate.commandContents().keySet())) {
                throw new IOException("状态命令内容与回报的键不一致");
            }
            for (Map.Entry<String, String> entry : previous.commandContents().entrySet()) {
                if (!entry.getValue().equals(candidate.commandContents().get(entry.getKey()))) {
                    throw new IOException("状态旧命令内容被改写：" + entry.getKey());
                }
            }
            for (Map.Entry<String, String> entry : previous.commandOutcomes().entrySet()) {
                if (!entry.getValue().equals(candidate.commandOutcomes().get(entry.getKey()))) {
                    throw new IOException("状态旧命令回报被改写：" + entry.getKey());
                }
            }
        }
        WorldScheduler.Phase phase;
        try {
            phase = WorldScheduler.Phase.valueOf(candidate.lastPhase());
        } catch (RuntimeException invalid) {
            throw new IOException("状态阶段名非法：" + candidate.lastPhase(), invalid);
        }
        if (previous != null && candidate.timeMillis() == previous.timeMillis()
                && phase.ordinal() < WorldScheduler.Phase.valueOf(previous.lastPhase()).ordinal()) {
            throw new IOException("同一模拟时点的状态阶段倒退");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (CashLedger.CashAccount account : candidate.accounts().values()) {
            if (account.cash().signum() < 0) {
                throw new IOException("状态出现负余额：" + account.ownerId());
            }
        }
        for (PendingTask task : candidate.pending()) {
            if (!ids.add(task.taskId())) {
                throw new IOException("状态待办重复：" + task.taskId());
            }
            if (!candidate.usedTaskIds().contains(task.taskId())) {
                throw new IOException("状态待办未登记到已用任务：" + task.taskId());
            }
            if (task.dueTimeMillis() < candidate.timeMillis()) {
                throw new IOException("状态待办到期早于快照时点：" + task.taskId());
            }
            if (task.dueTimeMillis() == candidate.timeMillis() && task.phase().ordinal() < phase.ordinal()) {
                throw new IOException("状态待办落在同一时点已结束的阶段：" + task.taskId());
            }
        }
        validateSecurities(candidate, previous);
    }

    /**
     * 校验证券子集：版本与档案一致、引用有效、逐证券守恒、冻结不超过批次剩余，
     * 以及相对上一批次的不可逆性（报告与已用标识只增不改、批次只减）。
     */
    private void validateSecurities(WorldState candidate, WorldState previous) throws IOException {
        SecuritiesLedger.State securities = candidate.securities();
        if (expectedSchema() == STATE_SCHEMA) {
            if (securities != null) {
                throw new IOException("现金/登记档案不得包含证券子集状态");
            }
            return;
        }
        if (securities == null) {
            throw new IOException("证券档案缺少持仓子集状态");
        }
        for (SecuritiesLedger.Lot lot : securities.lots()) {
            if (!candidate.accounts().containsKey(lot.accountId())) {
                throw new IOException("持仓批次引用了未登记账户：" + lot.accountId());
            }
            if (registry.security(lot.symbol()) == null) {
                throw new IOException("持仓批次引用了未登记证券：" + lot.symbol());
            }
        }
        Map<String, Long> totals = new TreeMap<>();
        for (SecuritiesLedger.Lot lot : securities.lots()) {
            Long previousTotal = totals.get(lot.symbol());
            try {
                totals.put(lot.symbol(), previousTotal == null ? lot.remaining()
                        : Math.addExact(previousTotal, lot.remaining()));
            } catch (ArithmeticException overflow) {
                throw new IOException("证券批次数量累计溢出：" + lot.symbol(), overflow);
            }
        }
        for (OpeningRegistry.Security security : registry.securities()) {
            Long total = totals.getOrDefault(security.symbol(), 0L);
            if (total != security.issuedShares()) {
                throw new IOException("证券总股数与已发行不一致：" + security.symbol()
                        + " 发行 " + security.issuedShares() + "，批次 " + total);
            }
        }
        if (totals.size() != registry.securities().size()) {
            throw new IOException("证券子集包含未登记的证券代码：" + totals.keySet());
        }
        for (SellReservation reservation : securities.reservations()) {
            if (!candidate.accounts().containsKey(reservation.accountId())) {
                throw new IOException("活动预留引用了未登记账户：" + reservation.accountId());
            }
        }
        for (ActionReport report : securities.reports().values()) {
            if (report.position() > candidate.position()) {
                throw new IOException("业务结果出现在未来的提交位置：" + report.taskId());
            }
            if (report.timeMillis() > candidate.timeMillis()) {
                throw new IOException("业务结果时点晚于快照时点：" + report.taskId());
            }
            if (!candidate.usedTaskIds().contains(report.taskId())) {
                throw new IOException("业务结果缺少对应的已用任务：" + report.taskId());
            }
        }
        if (previous == null || previous.securities() == null) {
            return;
        }
        SecuritiesLedger.State before = previous.securities();
        for (ActionReport older : before.reports().values()) {
            ActionReport now = securities.reports().get(older.taskId());
            if (now == null || !now.equals(older)) {
                throw new IOException("状态旧证券结果被删除或改写：" + older.taskId());
            }
        }
        if (!securities.usedReservationIds().containsAll(before.usedReservationIds())) {
            throw new IOException("状态已用预留标识丢失");
        }
        Map<String, Long> beforeTotals = new TreeMap<>();
        for (SecuritiesLedger.Lot lot : before.lots()) {
            Long sum = beforeTotals.get(lot.symbol());
            try {
                beforeTotals.put(lot.symbol(), sum == null ? lot.remaining()
                        : Math.addExact(sum, lot.remaining()));
            } catch (ArithmeticException overflow) {
                throw new IOException("恢复的证券批次数量累计溢出：" + lot.symbol(), overflow);
            }
        }
        if (!beforeTotals.equals(totals)) {
            throw new IOException("证券总股数在同一运行内改变");
        }
        Map<String, SecuritiesLedger.Lot> current = new LinkedHashMap<>();
        for (SecuritiesLedger.Lot lot : securities.lots()) {
            current.put(lot.lotId(), lot);
        }
        for (SecuritiesLedger.Lot lot : before.lots()) {
            SecuritiesLedger.Lot now = current.get(lot.lotId());
            if (now == null) {
                continue;
            }
            if (!now.accountId().equals(lot.accountId()) || !now.symbol().equals(lot.symbol())
                    || now.eligibleFromDay() != lot.eligibleFromDay()
                    || now.acquiredAtMillis() != lot.acquiredAtMillis()
                    || now.acquiredPosition() != lot.acquiredPosition()
                    || now.costPrice().compareTo(lot.costPrice()) != 0
                    || now.classification() != lot.classification()) {
                throw new IOException("持仓批次的归属或成本被改写：" + lot.lotId());
            }
            if (now.remaining() > lot.remaining()) {
                throw new IOException("持仓批次的剩余数量被增加：" + lot.lotId());
            }
        }
    }

    private WorldState restore(List<OpeningAccount> ordered, WorldState expectedInitial)
            throws IOException {
        List<WorldCommit> commits = journal.commits();
        String expected = openingContent(ordered);
        WorldCommit first = commits.get(0);
        if (!OPEN_COMMAND.equals(first.commandId()) || !expected.equals(first.commandContent())
                || !"OPENED".equals(first.result())) {
            throw new IOException("运行清单或开局现金与持久记录不一致");
        }
        WorldState previous = null;
        for (WorldCommit commit : commits) {
            WorldState snapshot = decodeSnapshot(commit);
            validate(snapshot, commit.position(), previous);
            if (previous == null && !expectedInitial.equals(snapshot)) {
                throw new IOException("开局快照与期望开局状态不一致（必须等于清单绑定的初始资产）");
            }
            previous = snapshot;
        }
        return previous;
    }

    private WorldState decodeSnapshot(WorldCommit commit) throws IOException {
        WorldState found = null;
        List<ActionReport> reported = new ArrayList<>();
        for (WorldEvent event : commit.events()) {
            if (SNAPSHOT_TYPE.equals(event.type())) {
                if (found != null) {
                    throw new IOException("批次 " + commit.position() + " 含多个状态快照");
                }
                found = mapper.readValue(event.payload(), WorldState.class);
            } else if ("SECURITIES_REPORT_V1".equals(event.type())) {
                reported.add(mapper.readValue(event.payload(), ActionReport.class));
            }
        }
        if (found == null) {
            throw new IOException("批次 " + commit.position() + " 缺少状态快照");
        }
        // 批次内声明的结果事件必须在快照中存在且一致；旧结果由状态链校验保护。
        for (ActionReport report : reported) {
            ActionReport stored = found.securities() == null ? null
                    : found.securities().reports().get(report.taskId());
            if (!report.equals(stored)) {
                throw new IOException("批次 " + commit.position()
                        + " 的证券结果与状态快照不一致：" + report.taskId());
            }
        }
        return found;
    }

    private WorldEvent snapshotEvent(WorldState snapshot) throws IOException {
        return new WorldEvent("state:" + snapshot.position(), SNAPSHOT_TYPE,
                mapper.writeValueAsString(snapshot));
    }

    private String openingContent(List<OpeningAccount> ordered) throws IOException {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("manifest", manifest);
        content.put("openings", ordered);
        if (registry != null) {
            // 只有登记模式才加这个键：旧现金日志的开局内容保持逐字节不变
            content.put("registry", registry);
        }
        return mapper.writeValueAsString(content);
    }

    private static BigDecimal total(WorldState state) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CashLedger.CashAccount account : state.accounts().values()) {
            sum = sum.add(account.cash());
        }
        return sum;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("运行已关闭");
        }
    }

    /**
     * 排程与推进只能由创建本运行的线程执行；文件/网络线程不得改变世界顺序。
     */
    private void requireWriter() {
        if (Thread.currentThread() != writerThread) {
            throw new IllegalStateException("只有创建运行的世界写入线程可以排程或推进");
        }
    }

    /** 暂停的运行不接受新排程，必须先恢复。 */
    private void requireNotPaused() {
        if (paused) {
            throw new IllegalStateException("运行已暂停，不接受新排程；须先恢复");
        }
    }

    /** 新待办的到期必须晚于当前时点，且任务标识运行内不复用。 */
    private void requireSchedulable(long dueTimeMillis, String taskId) {
        if (dueTimeMillis <= state.timeMillis()) {
            throw new IllegalArgumentException("到期时间必须晚于当前时点：" + state.timeMillis());
        }
        if (state.usedTaskIds().contains(taskId)) {
            throw new IllegalArgumentException("任务标识运行内不复用：" + taskId);
        }
    }

    /** 证券清算接口只对声明了 securities 模块的运行开放。 */
    private void requireSecuritiesEnabled() {
        if (!manifest.hasSecurities()) {
            throw new IllegalStateException("当前运行未启用证券清算模块：" + manifest.rulesVersion());
        }
        if (state.securities() == null) {
            throw new IllegalStateException("当前运行没有证券子集状态");
        }
    }

    /** 排程一个清算夹具待办：待办、已用标识、命令内容与回报同一批持久提交。 */
    private String scheduleSecuritiesTask(PendingTask task, String commandId, String content)
            throws IOException {
        List<PendingTask> pending = new ArrayList<>(state.pending());
        pending.add(task);
        Set<String> used = new LinkedHashSet<>(state.usedTaskIds());
        used.add(task.taskId());
        Map<String, String> contents = new TreeMap<>(state.commandContents());
        contents.put(commandId, content);
        Map<String, String> outcomes = new TreeMap<>(state.commandOutcomes());
        String outcome = "SCHEDULED:" + task.taskId();
        outcomes.put(commandId, outcome);
        WorldState candidate = next(state.timeMillis(), state.lastPhase(), state.accounts(),
                pending, state.delivered(), used, contents, outcomes, state.securities());
        commitAndApply("cmd:" + commandId, content, outcome,
                List.of(new WorldEvent("task-scheduled:" + task.taskId(), "TASK_SCHEDULED_V1",
                        mapper.writeValueAsString(task))), candidate);
        return outcome;
    }

    private WorldState initialState(Map<String, CashLedger.CashAccount> accounts) {
        return new WorldState(expectedSchema(), manifest.runId(), 1, 0L,
                WorldScheduler.Phase.OBSERVATION.name(), accounts, List.of(), List.of(), Set.of(),
                Map.of(), Map.of(),
                manifest.hasSecurities() ? SecuritiesLedger.opening(registry) : null);
    }

    /** @return 当前规则档案对应的世界状态版本 */
    private int expectedSchema() {
        return manifest.hasSecurities() ? STATE_SCHEMA_SECURITIES : STATE_SCHEMA;
    }

    /**
     * 由当前状态派生下一个提交位置的状态；只替换显式给出的部分。
     */
    private WorldState next(long timeMillis, String lastPhase,
                            Map<String, CashLedger.CashAccount> accounts,
                            List<PendingTask> pending, List<Notice> delivered,
                            Set<String> usedTaskIds, Map<String, String> commandContents,
                            Map<String, String> commandOutcomes,
                            SecuritiesLedger.State securities) {
        return new WorldState(state.schemaVersion(), state.runId(), state.position() + 1,
                timeMillis, lastPhase, accounts, pending, delivered, usedTaskIds,
                commandContents, commandOutcomes, securities);
    }

    /**
     * 为内部回报任务找一个未被占用的标识：从 {@code notice:<源任务>} 起，被占用就再套一层前缀。
     *
     * <p>外部 taskId 不保留任何前缀，因此不能用“禁止某前缀”来隔离；这里以确定性探测保证
     * 内部标识既不与已排程任务冲突，也不与已用标识冲突。</p>
     *
     * @param sourceTaskId 产生回报的原任务标识
     * @param pending      当前待办（含本次尚未加入的内部任务之前的全部）
     * @param used         已用任务标识
     * @return 未被占用的内部任务标识
     */
    private static String freeNoticeTaskId(String sourceTaskId, List<PendingTask> pending,
                                           Set<String> used) {
        Set<String> pendingIds = new LinkedHashSet<>();
        pending.forEach(task -> pendingIds.add(task.taskId()));
        String candidate = "notice:" + sourceTaskId;
        while (used.contains(candidate) || pendingIds.contains(candidate)) {
            candidate = "notice:" + candidate;
        }
        return candidate;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
