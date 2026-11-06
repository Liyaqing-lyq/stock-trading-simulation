package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.CashLedger.OpeningAccount;

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
 * <p><b>能力边界</b>：当前只启用现金转账与回报投递。规则、模块、模型版本在构造期校验，
 * 未启用能力在提交前即被拒绝。快照是管理端只读视图，不面向不可信角色。</p>
 */
public final class WorldRuntime implements AutoCloseable {

    private static final String OPEN_COMMAND = "__open__";
    private static final String SNAPSHOT_TYPE = "STATE_SNAPSHOT_V1";
    private static final int STATE_SCHEMA = 1;

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
     * @param position    最后完整提交位置
     * @param timeMillis  当前模拟毫秒
     * @param lastPhase   最后完成的阶段名
     * @param accounts    现金账户（不可变）
     * @param pending     未完成待办（不可变）
     * @param delivered   已投递回报（不可变）
     * @param usedTaskIds 已用任务标识（不可变）
     */
    public record Snapshot(long position, long timeMillis, String lastPhase,
                           Map<String, CashLedger.CashAccount> accounts,
                           List<PendingTask> pending, List<Notice> delivered,
                           Set<String> usedTaskIds) {
        /** 防御性冻结所有集合。 */
        public Snapshot {
            accounts = Collections.unmodifiableMap(new TreeMap<>(accounts));
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

    /** 完整不可变子集状态；每批提交保存一份，恢复逐批校验后取最后一份。 */
    private record WorldState(int schemaVersion, String runId, long position, long timeMillis,
                              String lastPhase, Map<String, CashLedger.CashAccount> accounts,
                              List<PendingTask> pending, List<Notice> delivered,
                              Set<String> usedTaskIds, Map<String, String> commandContents,
                              Map<String, String> commandOutcomes) {

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

        WorldState candidate = new WorldState(state.schemaVersion(), state.runId(),
                state.position() + 1, state.timeMillis(), state.lastPhase(), state.accounts(),
                pending, state.delivered(), used, contents, outcomes);
        commitAndApply("cmd:" + commandId, content, outcome,
                List.of(new WorldEvent("task-scheduled:" + taskId, "TASK_SCHEDULED_V1",
                        mapper.writeValueAsString(task))), candidate);
        return outcome;
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
                if (PendingTask.CASH_TRANSFER_V1.equals(next.kind())) {
                    completeTransfer(next);
                } else {
                    deliverNotice(next);
                }
            }
            if (inclusiveMillis > state.timeMillis()) {
                String content = "{\"timeMillis\":" + inclusiveMillis + "}";
                WorldState candidate = new WorldState(state.schemaVersion(), state.runId(),
                        state.position() + 1, inclusiveMillis,
                        WorldScheduler.Phase.OBSERVATION.name(), state.accounts(), state.pending(),
                        state.delivered(), state.usedTaskIds(), state.commandContents(),
                        state.commandOutcomes());
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
                state.accounts(), state.pending(), state.delivered(), state.usedTaskIds());
    }

    /**
     * 开局登记的管理端视图。
     *
     * <p>这是**开局分配**的只读快照，不是实时持仓：本单元没有证券交易、解锁或分红动作，
     * 因此持仓数量变化要等 M2.3 引入新的已声明状态版本后再提供。</p>
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

        WorldState candidate = new WorldState(state.schemaVersion(), state.runId(),
                state.position() + 1, task.dueTimeMillis(), task.phase().name(),
                outcome.accounts(), pending, state.delivered(), used, state.commandContents(),
                state.commandOutcomes());
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
        WorldState candidate = new WorldState(state.schemaVersion(), state.runId(),
                state.position() + 1, task.dueTimeMillis(), task.phase().name(), state.accounts(),
                pending, delivered, used, state.commandContents(), state.commandOutcomes());
        commitAndApply("task:" + task.taskId(), task.payload(), "DELIVERED",
                List.of(new WorldEvent("task-completed:" + task.taskId(), "TASK_COMPLETED_V1",
                                task.payload()),
                        new WorldEvent("notice:" + payload.noticeId(), "NOTICE_DELIVERED_V1",
                                task.payload())),
                candidate);
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
        if (candidate.schemaVersion() != STATE_SCHEMA) {
            throw new IOException("状态版本不支持：" + candidate.schemaVersion());
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
        for (WorldEvent event : commit.events()) {
            if (SNAPSHOT_TYPE.equals(event.type())) {
                if (found != null) {
                    throw new IOException("批次 " + commit.position() + " 含多个状态快照");
                }
                found = mapper.readValue(event.payload(), WorldState.class);
            }
        }
        if (found == null) {
            throw new IOException("批次 " + commit.position() + " 缺少状态快照");
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

    private WorldState initialState(Map<String, CashLedger.CashAccount> accounts) {
        return new WorldState(STATE_SCHEMA, manifest.runId(), 1, 0L,
                WorldScheduler.Phase.OBSERVATION.name(), accounts, List.of(), List.of(), Set.of(),
                Map.of(), Map.of());
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
