package edu.cufe.auction.engine;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.account.AccountManager;
import edu.cufe.auction.market.MarketDataPublisher;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderResult;
import edu.cufe.auction.model.OrderStatus;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.model.Trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试夹具：搭一套最小可用的引擎 + 账户 + 行情总线，供各测试类复用。
 */
final class EngineFixture {

    static final String AAPL = "AAPL";
    static final String TSLA = "TSLA";

    final AccountManager accounts = new AccountManager();
    final MarketDataPublisher publisher = new MarketDataPublisher();
    final MatchingEngine engine;
    final List<Trade> observedTrades = new ArrayList<>();

    EngineFixture() {
        Map<String, BigDecimal> reference = new LinkedHashMap<>();
        reference.put(AAPL, new BigDecimal("175.50"));
        reference.put(TSLA, new BigDecimal("245.30"));
        engine = new MatchingEngine(accounts, publisher, reference);
        publisher.subscribe(new edu.cufe.auction.market.MarketDataListener() {
            @Override
            public void onTrade(Trade trade) {
                observedTrades.add(trade);
            }

            @Override
            public void onMarketSnapshot(MarketSnapshot snapshot) {
                // 本夹具不关心行情快照
            }
        });
    }

    Account account(String id, String balance) {
        return accounts.createAccount(id, id, new BigDecimal(balance));
    }

    OrderResult buyLimit(String agentId, String symbol, String price, long quantity) {
        return engine.submit(edu.cufe.auction.model.OrderRequest.limit(
                agentId, symbol, Side.BUY, new BigDecimal(price), quantity));
    }

    OrderResult sellLimit(String agentId, String symbol, String price, long quantity) {
        return engine.submit(edu.cufe.auction.model.OrderRequest.limit(
                agentId, symbol, Side.SELL, new BigDecimal(price), quantity));
    }

    OrderResult buyMarket(String agentId, String symbol, long quantity) {
        return engine.submit(edu.cufe.auction.model.OrderRequest.market(
                agentId, symbol, Side.BUY, quantity));
    }

    OrderResult sellMarket(String agentId, String symbol, long quantity) {
        return engine.submit(edu.cufe.auction.model.OrderRequest.market(
                agentId, symbol, Side.SELL, quantity));
    }

    static Order order(OrderResult result) {
        if (!result.isAccepted()) {
            throw new AssertionError("订单被拒：" + result.getReason());
        }
        return result.getOrder();
    }

    static void assertStatus(OrderResult result, OrderStatus expected) {
        if (result.getStatus() != expected) {
            throw new AssertionError("期望状态 " + expected + "，实际 " + result.getStatus());
        }
    }

    static void assertType(Order order, OrderType expected) {
        if (order.getType() != expected) {
            throw new AssertionError("期望类型 " + expected + "，实际 " + order.getType());
        }
    }
}
