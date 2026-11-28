package edu.cufe.auction.world;

import edu.cufe.auction.model.Side;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/** 从真实活动限价单推导公开最优价位，数额是该价位全部订单尚未成交数量之和。 */
record BestQuoteDepth(BigDecimal bid, BigInteger bidQuantity, BigDecimal ask, BigInteger askQuantity) {
    static final String KEY = "role-market-depth", VERSION = "best-level-remaining-v1";

    static BestQuoteDepth of(List<WorldExchange.Order> orders, String symbol) {
        BigDecimal bid = null, ask = null;
        BigInteger buy = BigInteger.ZERO, sell = BigInteger.ZERO;
        for (var order : orders) {
            var price = order.limitPrice();
            if (!order.live() || !symbol.equals(order.symbol()) || price == null) continue;
            var remaining = BigInteger.valueOf(order.remaining());
            if (order.side() == Side.BUY) {
                int comparison = bid == null ? 1 : price.compareTo(bid);
                if (comparison > 0) { bid = price; buy = remaining; }
                else if (comparison == 0) buy = buy.add(remaining);
            } else {
                int comparison = ask == null ? -1 : price.compareTo(ask);
                if (comparison < 0) { ask = price; sell = remaining; }
                else if (comparison == 0) sell = sell.add(remaining);
            }
        }
        return new BestQuoteDepth(bid, buy, ask, sell);
    }
}
