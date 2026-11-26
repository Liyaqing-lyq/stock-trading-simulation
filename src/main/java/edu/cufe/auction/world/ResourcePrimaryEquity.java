package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.List;

/** 资源上市发行人对具名个人的一次有限认购额度。价格、股份种类和交割规则在开局冻结。 */
public final class ResourcePrimaryEquity {
    public static final String POLICY = "resource-primary-equity", VERSION = "named-subscription-v1";
    public static final String ISSUER = "resources", SYMBOL = "RESOURCE", SUBSCRIBER = "investor", ACCOUNT = "INV";
    public static final long MAX_SHARES = 100, OFFER_LIFE = 30L * SecuritiesLedger.DAY_MILLIS;
    public static final BigDecimal PRICE = new BigDecimal("10.00");
    public static final String TOOL = "equity.subscribe";
    public record Request(String meetingId, long shares) { }
    public static final String TERMS = "RESOURCE_PRIMARY_OFFER：四席同意才生效；具名投资人 investor 可自主用本人 INV 现金认购 RESOURCE 普通流通股，每股10.00元，累计最多100股。要约30个模拟日有效，停业后终止。实付交割同时确认，次日方可卖。";
    private ResourcePrimaryEquity() { }

    /** 实缴与普通股批次在同一确认位置交割。 */
    public record Subscription(String commandId, String meetingId, long shares, BigDecimal paid,
                               long timeMillis, long position) {
        public Subscription {
            if (commandId == null || commandId.isBlank() || meetingId == null || meetingId.isBlank()
                    || shares <= 0 || shares > MAX_SHARES || timeMillis < 0 || position <= 0)
                throw new IllegalArgumentException("认购记录无效");
            paid = CashLedger.money(paid);
            if (!paid.equals(PRICE.multiply(BigDecimal.valueOf(shares)))) throw new IllegalArgumentException("认购款与发行价不符");
        }

        SecuritiesLedger.Lot lot() {
            return new SecuritiesLedger.Lot("resource-primary:" + commandId, ACCOUNT, SYMBOL, shares,
                    Math.addExact(SecuritiesLedger.day(timeMillis), 1), timeMillis, position, PRICE.setScale(4),
                    OpeningRegistry.ShareClass.FLOAT);
        }
    }

    static long subscribed(ResourceCompanyRun run) {
        if (run == null) return 0;
        return run.subscriptions().stream().mapToLong(Subscription::shares).reduce(0L,Math::addExact);
    }

    static List<String> activeOffers(ResourceCompanyRun run, BoardRun board, long now) {
        if (run == null || run.ledger().exited() || board == null || subscribed(run) >= MAX_SHARES) return List.of();
        return board.board().meetings().values().stream().filter(m -> m.topic() == BoardModel.Topic.RESOURCE_PRIMARY_OFFER
                && m.status() == BoardModel.MeetingStatus.ADOPTED && now >= m.closesAt() && now - m.closesAt() < OFFER_LIFE)
                .map(BoardModel.Meeting::meetingId).sorted().toList();
    }
}
