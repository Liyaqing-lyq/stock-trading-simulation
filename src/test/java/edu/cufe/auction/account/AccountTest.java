package edu.cufe.auction.account;

import edu.cufe.auction.model.Side;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 账户与绩效统计的单元测试（冻结、结算、权益、收益率、胜率、最大回撤）。 */
class AccountTest {

    private static final BigDecimal AAPL = new BigDecimal("150.00");

    @Test
    @DisplayName("买入冻结按限价、结算按成交价，价格改善的差额退回可用现金")
    void reserveThenSettleBuyWithPriceImprovement() {
        Account account = new Account("a", "A", new BigDecimal("100000.00"));
        assertTrue(account.reserve(1L, "AAPL", Side.BUY, new BigDecimal("152.00"), 100));

        assertEquals(0, new BigDecimal("84800.00").compareTo(account.getAvailableCash()));
        assertEquals(0, new BigDecimal("15200.00").compareTo(account.getFrozenCash()));
        assertEquals(0, new BigDecimal("15200.00").compareTo(account.getReservedAmount(1L)));

        account.settleBuy(1L, "AAPL", AAPL, 100);

        assertEquals(0, new BigDecimal("85000.00").compareTo(account.getAvailableCash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getFrozenCash()));
        assertEquals(100L, account.getQuantity("AAPL"));
        assertEquals(0, AAPL.compareTo(account.getPosition("AAPL").getAverageCost()));
    }

    @Test
    @DisplayName("卖出结算计入现金与已实现盈亏，并更新胜率")
    void sellSettlesCashAndPnl() {
        Account account = new Account("a", "A", BigDecimal.ZERO);
        account.seedPosition("AAPL", 100, new BigDecimal("100.00"));
        assertTrue(account.reserve(1L, "AAPL", Side.SELL, null, 100));

        BigDecimal pnl = account.settleSell(1L, "AAPL", new BigDecimal("120.00"), 100);

        assertEquals(0, new BigDecimal("2000.00").compareTo(pnl));
        assertEquals(0, new BigDecimal("12000.00").compareTo(account.getAvailableCash()));
        assertEquals(0L, account.getQuantity("AAPL"));
        assertEquals(1, account.getPerformance().getClosedTradeCount());
        assertEquals(0, BigDecimal.ONE.compareTo(account.getPerformance().getWinRate()));
    }

    @Test
    @DisplayName("总权益 = 现金（含冻结） + 持仓市值，收益率以初始权益为基准")
    void equityAndReturnRate() {
        Account account = new Account("a", "A", new BigDecimal("10000.00"));
        account.seedPosition("AAPL", 100, new BigDecimal("100.00"));
        account.reserve(1L, "AAPL", Side.BUY, new BigDecimal("120.00"), 50);

        Map<String, BigDecimal> prices = Map.of("AAPL", new BigDecimal("120.00"));
        assertEquals(0, new BigDecimal("22000.00").compareTo(account.equity(prices)),
                "现金 10000-6000 + 冻结 6000 + 持仓 100×120");
        assertEquals(0, new BigDecimal("20000.00").compareTo(account.getInitialEquity()),
                "初始权益 = 初始资金 10000 + 初始持仓成本 10000");
        assertEquals(0, new BigDecimal("0.1").compareTo(account.returnRate(prices)),
                "权益 22000 / 初始权益 20000 = +10%");
    }

    @Test
    @DisplayName("注入初始持仓不会把股票成本误算成收益")
    void seededHoldingsDoNotInflateReturnRate() {
        Account marketMaker = new Account("mm", "做市", new BigDecimal("80000.00"));
        marketMaker.seedPosition("AAPL", 800, new BigDecimal("175.50"));

        assertEquals(0, new BigDecimal("220400.00").compareTo(marketMaker.getInitialEquity()));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                        marketMaker.returnRate(Map.of("AAPL", new BigDecimal("175.50")))),
                "价格没变时收益率必须为 0");
    }

    @Test
    @DisplayName("最大回撤按权益曲线峰值到谷值计算")
    void maxDrawdownTracked() {
        Account account = new Account("a", "A", new BigDecimal("10000.00"));
        Map<String, BigDecimal> p10000 = Map.of("AAPL", new BigDecimal("100.00"));
        Map<String, BigDecimal> p12000 = Map.of("AAPL", new BigDecimal("120.00"));
        Map<String, BigDecimal> p9000 = Map.of("AAPL", new BigDecimal("90.00"));

        account.seedPosition("AAPL", 100, new BigDecimal("100.00"));
        account.getPerformance().markEquity(new BigDecimal("10000.00"));
        account.getPerformance().markEquity(new BigDecimal("12000.00"));
        account.getPerformance().markEquity(new BigDecimal("9000.00"));

        assertEquals(0, new BigDecimal("25.0000").compareTo(account.getPerformance().getMaxDrawdown()),
                "从 12000 回撤到 9000 = 25%");
        assertTrue(p10000.containsKey("AAPL") && p12000.containsKey("AAPL") && p9000.containsKey("AAPL"));
    }

    @Test
    @DisplayName("撤单释放剩余冻结资金")
    void releaseReturnsFrozenCash() {
        Account account = new Account("a", "A", new BigDecimal("20000.00"));
        assertTrue(account.reserve(7L, "AAPL", Side.BUY, new BigDecimal("150.00"), 100));
        account.settleBuy(7L, "AAPL", new BigDecimal("150.00"), 40);
        account.release(7L);

        assertEquals(0, new BigDecimal("14000.00").compareTo(account.getAvailableCash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getFrozenCash()));
        assertEquals(40L, account.getQuantity("AAPL"));
    }

    @Test
    @DisplayName("资金/持仓不足时 reserve 返回 false，且不改变任何状态")
    void reserveFailsWithoutSideEffects() {
        Account account = new Account("a", "A", new BigDecimal("100.00"));
        assertFalse(account.reserve(1L, "AAPL", Side.BUY, new BigDecimal("150.00"), 10));
        assertEquals(0, new BigDecimal("100.00").compareTo(account.getAvailableCash()));

        assertFalse(account.reserve(2L, "AAPL", Side.SELL, null, 10));
        assertEquals(0L, account.getAvailableQuantity("AAPL"));
    }

    @Test
    @DisplayName("重复注入初始持仓被拒绝")
    void duplicateSeedRejected() {
        Account account = new Account("a", "A", BigDecimal.ZERO);
        account.seedPosition("AAPL", 100, new BigDecimal("100.00"));
        assertThrows(IllegalStateException.class,
                () -> account.seedPosition("AAPL", 100, new BigDecimal("100.00")));
        assertThrows(IllegalArgumentException.class,
                () -> account.seedPosition("AAPL", 0, new BigDecimal("100.00")));
    }

    @Test
    @DisplayName("账户管理器生成按收益率降序的排行榜")
    void rankingSortedByReturnRate() {
        AccountManager manager = new AccountManager();
        Account winner = manager.createAccount("w", "赢家", new BigDecimal("10000.00"));
        winner.seedPosition("AAPL", 100, new BigDecimal("100.00"));
        Account loser = manager.createAccount("l", "输家", new BigDecimal("10000.00"));
        loser.seedPosition("AAPL", 100, new BigDecimal("100.00"));
        loser.reserve(1L, "AAPL", Side.SELL, null, 100);
        loser.settleSell(1L, "AAPL", new BigDecimal("90.00"), 100);

        var ranks = manager.ranking(Map.of("AAPL", new BigDecimal("120.00")));
        assertEquals(2, ranks.size());
        assertEquals("w", ranks.get(0).getAgentId());
        assertTrue(ranks.get(0).getReturnRate().compareTo(ranks.get(1).getReturnRate()) > 0);
        assertEquals(1, ranks.get(0).getRank());
    }
}
