package edu.cufe.auction.engine;

import edu.cufe.auction.account.AccountManager;
import edu.cufe.auction.market.MarketDataPublisher;
import edu.cufe.auction.model.InstrumentProfile;
import edu.cufe.auction.model.OrderRequest;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceLimitValidationTest {

    @Test
    void derivesDailyLimitsAndSupportsDifferentBoards() {
        InstrumentProfile mainBoard = profile("V001", "100.00", "10");
        InstrumentProfile growthBoard = profile("G001", "100.00", "20");

        assertEquals(new BigDecimal("90.00"), mainBoard.getLowerLimit());
        assertEquals(new BigDecimal("110.00"), mainBoard.getUpperLimit());
        assertEquals(new BigDecimal("80.00"), growthBoard.getLowerLimit());
        assertEquals(new BigDecimal("120.00"), growthBoard.getUpperLimit());
        assertTrue(mainBoard.isOnTick(new BigDecimal("100.01")));
        assertFalse(mainBoard.isOnTick(new BigDecimal("100.001")));
    }

    @Test
    void rejectsPricesOutsideLimitOrOffTickAndAllowsBoundary() {
        AccountManager accounts = new AccountManager();
        accounts.createAccount("buyer", "buyer", new BigDecimal("5000.00"));
        accounts.createAccount("seller", "seller", new BigDecimal("5000.00"))
                .seedPosition("V001", 100, new BigDecimal("100.00"));
        MatchingEngine engine = new MatchingEngine(accounts, new MarketDataPublisher(),
                List.of(profile("V001", "100.00", "10")));

        OrderResult above = engine.submit(OrderRequest.limit("buyer", "V001", Side.BUY,
                new BigDecimal("110.01"), 10));
        OrderResult below = engine.submit(OrderRequest.limit("seller", "V001", Side.SELL,
                new BigDecimal("89.99"), 10));
        OrderResult offTick = engine.submit(OrderRequest.limit("buyer", "V001", Side.BUY,
                new BigDecimal("100.001"), 10));
        OrderResult upperBoundary = engine.submit(OrderRequest.limit("buyer", "V001", Side.BUY,
                new BigDecimal("110.00"), 10));
        OrderResult lowerBoundary = engine.submit(OrderRequest.limit("seller", "V001", Side.SELL,
                new BigDecimal("90.00"), 10));

        assertFalse(above.isAccepted());
        assertTrue(above.getReason().startsWith("PRICE_OUT_OF_LIMIT"));
        assertFalse(below.isAccepted());
        assertTrue(below.getReason().startsWith("PRICE_OUT_OF_LIMIT"));
        assertFalse(offTick.isAccepted());
        assertTrue(offTick.getReason().startsWith("INVALID_PRICE_TICK"));
        assertTrue(upperBoundary.isAccepted(), "涨停价本身应允许排队申报");
        assertTrue(lowerBoundary.isAccepted(), "跌停价本身应允许排队申报");
        assertEquals(new BigDecimal("90.00"), engine.marketSnapshot("V001", 1).getLowerLimit());
        assertEquals(new BigDecimal("110.00"), engine.marketSnapshot("V001", 1).getUpperLimit());
    }

    private static InstrumentProfile profile(String symbol, String close, String limit) {
        return new InstrumentProfile(symbol, new BigDecimal(close), new BigDecimal(limit),
                new BigDecimal("0.01"));
    }
}
