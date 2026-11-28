package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.CashFreeze;
import edu.cufe.auction.world.bank.Fund;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** 简化交易周历下的卖款权利。同一资金桶当日先用卖款再买入，下一工作日零时解限。 */
public final class SettlementCash {
    /** 清单中的现金交收规则；本实验买卖双方交易费用均为零。 */
    public static final String KEY = "exchange-cash-settlement", VERSION = "next-weekday-net-v1";
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private SettlementCash() {}

    /** 单一真实资金桶的交易可用、活动买单冻结、尚未可取的净卖款、可转出额和实际交易费。 */
    public record Amounts(BigDecimal tradingAvailable, BigDecimal orderFrozen, BigDecimal unsettledSaleCash,
                          BigDecimal withdrawableCash, BigDecimal transactionFees) {}

    /** 以原成交的实际金额、订单资金桶和顺序计算当前未到可取日的净卖款。 */
    static Map<String,BigDecimal> unsettled(WorldExchange.State exchange,long now) {
        if (exchange == null || exchange.trades().isEmpty()) return Map.of();
        long day = now / MarketSessions.DAY;
        long start = (day - Math.max(0L,day % 7 - 4)) * MarketSessions.DAY;
        var trades = exchange.trades();
        int first = trades.size();
        while (first > 0 && trades.get(first-1).timeMillis() >= start) first--;
        if (first == trades.size()) return Map.of();
        var result = new HashMap<String,BigDecimal>();
        var orders = new HashMap<Long,String>();
        for (var order : exchange.orders()) orders.put(order.orderId(), order.fundingKey());
        for (int i=first;i<trades.size();i++) {
            var t = trades.get(i);
            if (t.timeMillis() > now) break;
            var buy = orders.get(t.buyOrderId()); var sell = orders.get(t.sellOrderId());
            if (buy.equals(sell)) continue;
            var pending = result.get(buy);
            if (pending != null) result.put(buy,pending.subtract(t.amount()).max(ZERO));
            result.merge(sell,t.amount(),BigDecimal::add);
        }
        return result;
    }

    /** 转出用的保留额；允许未交收卖款先覆盖同桶的有效买单，不重复冻结。 */
    static CashFreeze withdrawalFreeze(WorldExchange.State exchange,long now) {
        var frozen = new HashMap<>(WorldExchange.freezeSnapshot(exchange).frozenByBucket());
        unsettled(exchange,now).forEach((key,amount)->frozen.merge(key,amount,BigDecimal::max));
        return new CashFreeze(frozen,true);
    }

    /** 一个确认截面只扫描一次交易和挂单，可供该截面的所有可见资金桶共同使用。 */
    record Projection(CashFreeze buyOrders, Map<String,BigDecimal> pendingSales) {
        Amounts balance(Fund bucket, BigDecimal balance, boolean restrictedClearing) {
            var frozen = buyOrders.frozenOf(bucket);
            var pending = pendingSales.getOrDefault(bucket.token(), ZERO);
            var available = restrictedClearing ? ZERO : balance.subtract(frozen);
            var withdrawable = restrictedClearing ? ZERO : balance.subtract(frozen.max(pending)).max(ZERO);
            return new Amounts(available, frozen, pending, withdrawable, ZERO);
        }
    }

    static Projection projection(WorldExchange.State exchange, long now) {
        return new Projection(WorldExchange.freezeSnapshot(exchange), unsettled(exchange, now));
    }
}
