package edu.cufe.auction.web;

import edu.cufe.auction.world.WorldExchange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 从逐笔成交独立复算边界与量额；不依赖页面示例数据。 */
class MarketHistoryTest {
    @Test
    void actualTradesDetermineExactOhlcWithoutFutureOrOtherSymbol() {
        var trades = new ArrayList<>(List.of(trade(9, "A", "10.02", 7, 60000),
                trade(3, "A", "9.99", 5, 59999), trade(1, "B", "99.00", 8, 0),
                trade(7, "A", "12.10", 2, 60000), trade(11, "A", "88.00", 1, 60001)));
        var original = List.copyOf(trades);
        var result = MarketHistory.aggregate(trades, "A", MarketHistory.Period.MINUTE, 60000, 42);
        assertEquals(original, trades);
        assertEquals(2, result.totalBars());
        var before = result.bars().get(0);
        var current = result.bars().get(1);
        assertTrue(before.complete());
        assertFalse(current.complete());
        assertEquals(0, before.startMillis());
        assertEquals(60000, current.startMillis());
        assertEquals(new BigDecimal("49.95"), before.amount());
        assertEquals(new BigDecimal("94.34"), current.amount());
        assertEquals(9L, current.volume());
        assertEquals(7, current.firstTradeId());
        assertEquals(9, current.lastTradeId());
        assertEquals(new BigDecimal("12.10"), current.open());
        assertEquals(new BigDecimal("10.02"), current.close());
        assertEquals(new BigDecimal("12.10"), current.high());
        assertEquals(new BigDecimal("10.02"), current.low());
        assertTrue(result.gaps().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.bars().clear());
    }

    @Test
    void overflowDoesNotTurnExactAmountOrVolumeIntoFalseZero() {
        var result = MarketHistory.aggregate(List.of(
                trade(1, "A", "0.01", Long.MAX_VALUE, Long.MAX_VALUE),
                trade(2, "A", "0.01", 1, Long.MAX_VALUE)),
                "A", MarketHistory.Period.MINUTE, Long.MAX_VALUE, 43);
        var bar = result.bars().get(0);
        assertNull(bar.endMillis());
        assertFalse(bar.complete());
        assertNull(bar.volume());
        assertEquals("OVERFLOW", bar.volumeStatus());
        assertEquals(new BigDecimal("92233720368547758.08"), bar.amount());
    }

    @Test
    void sparseHistoryReportsOneGapAndNeverInventsEmptyPrices() {
        var trades = List.of(trade(1, "A", "1.00", 1, 0),
                trade(2, "A", "1.00", 1, Long.MAX_VALUE));
        var result = MarketHistory.aggregate(trades, "A", MarketHistory.Period.MINUTE, Long.MAX_VALUE, 44);
        assertEquals(2, result.bars().size());
        assertEquals(1, result.gaps().size());
        assertEquals(60000, result.gaps().get(0).fromMillis());
        assertEquals(result.bars().get(1).startMillis(), result.gaps().get(0).toMillis());
        assertTrue(MarketHistory.aggregate(trades, "Z", MarketHistory.Period.MINUTE, Long.MAX_VALUE, 44)
                .bars().isEmpty());
    }

    @Test
    void boundedHistoryKeepsLatestFiveHundredAndReportsOmittedCount() {
        var trades = new ArrayList<WorldExchange.Trade>();
        for (int index = 0; index < 501; index++) {
            trades.add(trade(index + 1, "A", "1.00", 1, index * 60000L));
        }
        var result = MarketHistory.aggregate(trades, "A", MarketHistory.Period.MINUTE, 500 * 60000L, 45);
        assertTrue(result.truncated());
        assertEquals(501, result.totalBars());
        assertEquals(500, result.bars().size());
        assertEquals(60000, result.bars().get(0).startMillis());
    }

    @Test
    void dayAndWeekUseSimulationOriginAndExactBoundary() {
        for (var period : List.of(MarketHistory.Period.DAY, MarketHistory.Period.WEEK)) {
            var result = MarketHistory.aggregate(List.of(
                    trade(1, "A", "1.00", 1, period.millis() - 1),
                    trade(2, "A", "2.00", 1, period.millis())), "A", period, period.millis(), 46);
            assertEquals(2, result.bars().size());
            assertTrue(result.bars().get(0).complete());
            assertFalse(result.bars().get(1).complete());
            assertEquals(period.millis(), result.bars().get(1).startMillis());
        }
    }

    private static WorldExchange.Trade trade(long id, String symbol, String price, long quantity, long time) {
        return new WorldExchange.Trade(id, symbol, new BigDecimal(price), quantity, id * 2, id * 2 + 1,
                "BUYER", "SELLER", time);
    }
}
