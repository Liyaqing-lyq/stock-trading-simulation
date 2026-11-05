package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扩展世界的现金权利子账：唯一写入者形成候选，持久提交后才发布完整快照。
 *
 * <p>仅验证开局、双边转移和恢复协议，不包含银行资产负债表或旧证券账户迁移。
 * 调用方必须从可信身份解析财产主体；不得直接采用客户端自报的 ownerId。
 * 全量快照仅供管理观察，角色查询须另经权限投影。</p>
 */
public final class CashLedger implements AutoCloseable {
    private static final String GENESIS = "__genesis__";
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorldJournal journal;
    private final Map<String, WorldCommit> commands = new LinkedHashMap<>();
    private volatile Snapshot current;
    private boolean closed;

    /**
     * 开局现金权利；历史禀赋必须标来源，不能伪装成刚发生的募资。
     * @param accountId 账户标识
     * @param ownerId 财产主体标识，与人物/任职分开
     * @param cash 非负开局余额，精确到分
     * @param source 历史来源说明
     */
    public record OpeningAccount(String accountId, String ownerId, BigDecimal cash, String source) {
        /** 校验开局必要字段与金额。 */
        public OpeningAccount {
            requireText(accountId);
            requireText(ownerId);
            requireText(source);
            cash = money(cash);
            if (cash.signum() < 0) {
                throw new IllegalArgumentException("开局现金不能为负");
            }
        }
    }

    /**
     * 不可变现金权利。
     * @param ownerId 财产主体
     * @param cash 当前余额
     * @param source 开局来源
     */
    public record CashAccount(String ownerId, BigDecimal cash, String source) {}

    /**
     * 同一完整提交位置的管理端快照。
     * @param position 最后持久提交位置
     * @param accounts 账户状态，不可变
     */
    public record Snapshot(long position, Map<String, CashAccount> accounts) {
        /** 防御性复制账户表。 */
        public Snapshot {
            accounts = Map.copyOf(accounts);
        }
    }

    /**
     * 已保存的业务回报，拒绝也占用一个提交位置。
     * @param accepted 是否转账成功
     * @param reason 结果代码
     * @param position 结果提交位置
     */
    public record TransferResult(boolean accepted, String reason, long position) {}

    private record TransferCommand(String actorOwnerId, String fromAccountId,
                                   String toAccountId, BigDecimal amount) {}
    private record Prepared(Snapshot state, TransferResult result, List<WorldEvent> events) {}

    /**
     * 新建或恢复子账。既有日志必须与给定开局清单一致，不重新发放初始资金。
     * @param file 运行专用日志
     * @param runId 运行标识
     * @param openings 有来源的开局清单
     * @throws IOException 日志不可用、开局不符或记录业务语义损坏
     */
    public CashLedger(Path file, String runId, List<OpeningAccount> openings) throws IOException {
        List<OpeningAccount> ordered = new ArrayList<>(openings);
        ordered.sort(Comparator.comparing(OpeningAccount::accountId));
        Map<String, CashAccount> accounts = new LinkedHashMap<>();
        for (OpeningAccount opening : ordered) {
            if (accounts.putIfAbsent(opening.accountId(),
                    new CashAccount(opening.ownerId(), opening.cash(), opening.source())) != null) {
                throw new IllegalArgumentException("重复开局账户：" + opening.accountId());
            }
        }
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("开局账户不能为空");
        }
        String genesisContent = mapper.writeValueAsString(ordered);
        List<WorldEvent> genesisEvents = List.of(new WorldEvent(GENESIS + ":accounts",
                "CASH_GENESIS_V1", genesisContent));
        journal = new WorldJournal(file, runId);
        try {
            if (journal.commits().isEmpty()) {
                journal.commit(GENESIS, genesisContent, "OPENED", genesisEvents);
            }
            List<WorldCommit> history = journal.commits();
            WorldCommit first = history.get(0);
            if (!GENESIS.equals(first.commandId()) || !genesisContent.equals(first.commandContent())
                    || !"OPENED".equals(first.result()) || !genesisEvents.equals(first.events())) {
                throw new IOException("现金子账开局清单与持久记录不一致");
            }
            current = new Snapshot(1, accounts);
            commands.put(GENESIS, first);
            for (WorldCommit batch : history.subList(1, history.size())) {
                TransferCommand command = mapper.readValue(batch.commandContent(), TransferCommand.class);
                validateAmount(command.amount());
                Prepared prepared = prepare(batch.commandId(), command, batch.position());
                if (!mapper.writeValueAsString(prepared.result()).equals(batch.result())
                        || !prepared.events().equals(batch.events())) {
                    throw new IOException("现金子账事件与实际回报不一致，位置 " + batch.position());
                }
                current = prepared.state();
                commands.put(batch.commandId(), batch);
            }
        } catch (IOException | RuntimeException failure) {
            journal.close();
            if (failure instanceof RuntimeException) {
                throw new IOException("现金子账恢复失败", failure);
            }
            throw failure;
        }
    }

    /**
     * 由财产主体发起双边转移。未知与无权目标使用一致错误，避免泄露隐藏账户。
     * @param commandId 运行内幂等标识
     * @param actorOwnerId 由可信调用方解析的财产主体
     * @param fromAccountId 付款账户
     * @param toAccountId 收款账户
     * @param amount 正数、精确到分的金额
     * @return 持久保存的结果；重试返回原结果
     * @throws IOException 持久提交失败或已关闭，内存快照不发布
     */
    public synchronized TransferResult transfer(String commandId, String actorOwnerId,
                                                String fromAccountId, String toAccountId,
                                                BigDecimal amount) throws IOException {
        if (closed) {
            throw new IOException("现金子账已关闭");
        }
        requireText(commandId);
        if (GENESIS.equals(commandId)) {
            throw new IllegalArgumentException("开局命令标识不可复用");
        }
        validateAmount(amount);
        TransferCommand command = new TransferCommand(actorOwnerId, fromAccountId, toAccountId, money(amount));
        String content = mapper.writeValueAsString(command);
        WorldCommit previous = commands.get(commandId);
        if (previous != null) {
            if (!content.equals(previous.commandContent())) {
                throw new IllegalArgumentException("同一命令标识不能对应不同内容");
            }
            return mapper.readValue(previous.result(), TransferResult.class);
        }
        Prepared prepared = prepare(commandId, command, current.position() + 1);
        WorldCommit batch = journal.commit(commandId, content,
                mapper.writeValueAsString(prepared.result()), prepared.events());
        current = prepared.state();
        commands.put(commandId, batch);
        return prepared.result();
    }

    /** @return 最近完整提交的全量管理端快照，不适用于未经权限投影的角色入口 */
    public Snapshot snapshot() {
        return current;
    }

    /** 关闭写入并释放文件资源；已发布快照仍可读取。 */
    @Override
    public synchronized void close() throws IOException {
        closed = true;
        journal.close();
    }

    private Prepared prepare(String commandId, TransferCommand command, long position) throws IOException {
        Map<String, CashAccount> accounts = current.accounts();
        CashAccount from = command.fromAccountId() == null ? null : accounts.get(command.fromAccountId());
        CashAccount to = command.toAccountId() == null ? null : accounts.get(command.toAccountId());
        String reason = "TRANSFERRED";
        if (from == null || to == null || !from.ownerId().equals(command.actorOwnerId())
                || command.fromAccountId().equals(command.toAccountId())) {
            reason = "TRANSFER_NOT_ALLOWED";
        } else if (from.cash().compareTo(command.amount()) < 0) {
            reason = "INSUFFICIENT_CASH";
        }
        boolean accepted = "TRANSFERRED".equals(reason);
        List<WorldEvent> events = List.of();
        if (accepted) {
            Map<String, CashAccount> next = new LinkedHashMap<>(accounts);
            next.put(command.fromAccountId(), new CashAccount(from.ownerId(),
                    from.cash().subtract(command.amount()), from.source()));
            next.put(command.toAccountId(), new CashAccount(to.ownerId(),
                    to.cash().add(command.amount()), to.source()));
            accounts = next;
            events = List.of(new WorldEvent(commandId + ":transfer", "CASH_TRANSFERRED_V1",
                    mapper.writeValueAsString(command)));
        }
        return new Prepared(new Snapshot(position, accounts), new TransferResult(accepted, reason, position), events);
    }

    private static void validateAmount(BigDecimal amount) {
        if (money(amount).signum() <= 0) {
            throw new IllegalArgumentException("转账金额必须为正");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("金额不能为空");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("金额必须精确到分", failure);
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("标识或来源不能为空");
        }
    }
}
