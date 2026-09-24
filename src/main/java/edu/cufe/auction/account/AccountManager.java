package edu.cufe.auction.account;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账户容器：创建/查询账户、计算权益、生成排行榜。
 *
 * <p>线程安全：底层用 {@link ConcurrentHashMap}，排序与统计在快照上完成。</p>
 */
public final class AccountManager {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    /**
     * 创建账户；同一 agentId 重复创建会抛异常，避免初始化阶段出现静默覆盖。
     *
     * @param agentId        agent 标识
     * @param displayName    展示名
     * @param initialBalance 初始资金
     * @return 新建账户
     */
    public Account createAccount(String agentId, String displayName, BigDecimal initialBalance) {
        Account account = new Account(agentId, displayName, initialBalance);
        Account previous = accounts.putIfAbsent(agentId, account);
        if (previous != null) {
            throw new IllegalStateException("账户已存在：" + agentId);
        }
        return account;
    }

    /**
     * 获取账户。
     *
     * @param agentId agent 标识
     * @return 账户
     * @throws IllegalArgumentException 账户不存在
     */
    public Account getAccount(String agentId) {
        Account account = accounts.get(agentId);
        if (account == null) {
            throw new IllegalArgumentException("账户不存在：" + agentId);
        }
        return account;
    }

    /**
     * 查找账户。
     *
     * @param agentId agent 标识
     * @return 可能为空的账户
     */
    public Optional<Account> find(String agentId) {
        return Optional.ofNullable(accounts.get(agentId));
    }

    /** @return 是否存在该账户 */
    public boolean contains(String agentId) {
        return accounts.containsKey(agentId);
    }

    /** @return 全部账户（按 agentId 排序） */
    public Collection<Account> all() {
        List<Account> list = new ArrayList<>(accounts.values());
        list.sort(Comparator.comparing(Account::getAgentId));
        return list;
    }

    /** @return 账户数量 */
    public int size() {
        return accounts.size();
    }

    /**
     * 更新所有账户的权益曲线（用于最大回撤）。
     *
     * @param lastPrices 各标的最新价
     */
    public void markAllEquity(Map<String, BigDecimal> lastPrices) {
        accounts.values().forEach(a -> a.markEquity(lastPrices));
    }

    /**
     * 生成排行榜（按收益率降序）。
     *
     * @param lastPrices 各标的最新价
     * @return 排名列表，名次从 1 开始
     */
    public List<PerformanceRank> ranking(Map<String, BigDecimal> lastPrices) {
        List<Account> sorted = new ArrayList<>(accounts.values());
        sorted.sort(Comparator.comparing((Account a) -> a.returnRate(lastPrices)).reversed());

        List<PerformanceRank> result = new ArrayList<>(sorted.size());
        int rank = 1;
        for (Account a : sorted) {
            PerformanceTracker p = a.getPerformance();
            result.add(new PerformanceRank(rank++, a.getAgentId(), a.getDisplayName(),
                    a.getInitialEquity(), a.equity(lastPrices), a.returnRate(lastPrices),
                    p.getRealizedPnl(), p.getMaxDrawdown(), p.getClosedTradeCount(), p.getWinRate()));
        }
        return result;
    }
}
