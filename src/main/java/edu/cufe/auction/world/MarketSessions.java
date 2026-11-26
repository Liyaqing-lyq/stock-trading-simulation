package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 简化交易周历的冻结规则。第零日为周一，模拟钟以当地日内时间计。 */
public final class MarketSessions {
    /** 使用本实验周历的运行与清单版本。 */
    public static final String RUN_ID = "web-market-sessions-operating-v1", KEY = "exchange-sessions", VERSION = "weekday-hy1-v1";
    static final long MINUTE = 60_000L, DAY = 1_440 * MINUTE;
    static final long OPEN = 570 * MINUTE, BREAK = 690 * MINUTE, AFTERNOON = 780 * MINUTE, CLOSE = 900 * MINUTE;
    static final String SYMBOL = "HY1", END = "SESSION_END", HALT = "SECURITY_HALTED";
    static final long HALT_AT = 630 * MINUTE, RESUME_AT = 660 * MINUTE;
    private static final java.util.List<Long> WEEKDAY_BOUNDARIES = java.util.List.of(OPEN,BREAK,AFTERNOON,CLOSE);
    private MarketSessions() { }

    static String rejection(String symbol, long now) {
        return switch (status(symbol, now, false)) {
            case "OPEN" -> null;
            case HALT -> HALT;
            default -> "SESSION_CLOSED";
        };
    }

    /** @param symbol 选择的证券，空字符串表示全市场 @param now 模拟毫秒 @param permanent 是否已被行政收盘
     * @return 选中证券此时的公开交易状态 */
    public static String status(String symbol, long now, boolean permanent) {
        if (permanent) return "PERMANENTLY_CLOSED";
        if (now / DAY % 7 >= 5) return "WEEKEND";
        long clock = now % DAY;
        if (clock < OPEN) return "PREOPEN";
        if (clock >= CLOSE) return "AFTER_CLOSE";
        if (clock >= BREAK && clock < AFTERNOON) return "LUNCH_BREAK";
        return SYMBOL.equals(symbol) && now >= HALT_AT && now < RESUME_AT ? HALT : "OPEN";
    }

    /** 自动推进时对齐交易日、开闭市与已登记的临时停复牌时点。
     * @param now 已确认的世界时点
     * @param limit 本次推进的上界
     * @return 不跨过下一次交易时段变化的目标
     */
    public static long nextBoundary(long now, long limit) {
        long clock = now % DAY;
        long distance = DAY - clock;
        if (now / DAY % 7 < 5) {
            for (long boundary : WEEKDAY_BOUNDARIES)
                if (boundary > clock) { distance = boundary - clock; break; }
        }
        if (now < HALT_AT) distance = Math.min(distance,HALT_AT-now);
        else if (now < RESUME_AT) distance = Math.min(distance,RESUME_AT-now);
        return distance < limit-now ? now + distance : limit;
    }

    static long firstExpiry(WorldExchange.State exchange) {
        long first = Long.MAX_VALUE;
        for (var order : exchange.orders()) {
            if (!order.live()) continue;
            long dayStart = order.acceptedAtMillis() / DAY * DAY;
            long due = Math.addExact(dayStart, CLOSE);
            if (SYMBOL.equals(order.symbol()) && order.acceptedAtMillis() < HALT_AT) due = Math.min(HALT_AT, due);
            first = Math.min(first, due);
        }
        return first;
    }

    static String scope(long due) { return due == HALT_AT ? SYMBOL : END; }

    static void describe(ObjectNode root, long now, boolean permanent) {
        var rules = root.putObject("exchangeSessions").put("version", VERSION)
                .put("calendar", "EXP 第0日周一，工作日09:30–11:30、13:00–15:00接受申报，右端点闭市。午休保留挂单，每日收市撤销未成交量。")
                .put("nowMillis", now).put("sessionOpen", !permanent && rejection("", now) == null);
        rules.putObject("scheduledHalt").put("symbol", SYMBOL).put("fromInclusive", HALT_AT)
                .put("untilExclusive", RESUME_AT).put("active", now >= HALT_AT && now < RESUME_AT)
                .put("treatment", "暂停开始时撤销该证券剩余挂单。恢复后须重新申报。");
    }
}
