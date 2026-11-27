package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.model.Side;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 个人交易实验档的观察、授权范围与交易所回报对应关系。 */
public final class PersonalMarket {
    /** 独立运行标识。 */
    public static final String RUN_ID = "web-personal-market-operating-v1";
    /** 冻结清单中的政策键和版本。 */
    public static final String POLICY = "personal-market-policy", VERSION = "v1";
    /** 登记本人在空簿也可申报自定限价的观察合同。 */
    public static final String LIMIT_RULES = "personal-limit-view", LIMIT_RULES_VERSION = "self-funded-v1";

    /** 本人限价报单与本人现存挂单撤销。 */
    public static final String SUBMIT = "personal.order.limit", CANCEL = "personal.order.cancel";

    /** 普通个人在成交簿上使用实验市价单的工具。 */
    public static final String MARKET = "personal.order.market";
    /** 即时撮合、未成交余量撤销的版本。 */
    public static final String MARKET_POLICY = "personal-market-orders", MARKET_VERSION = "immediate-unfilled-cancel-v1";

    static boolean marketOrders(RunManifest manifest) {
        return MARKET_VERSION.equals(manifest.dataVersions().get(MARKET_POLICY));
    }

    private PersonalMarket() { }

    record Submit(String accountId, String symbol, Side side, BigDecimal limitPrice, long quantity) { }
    record MarketSubmit(String accountId, String symbol, Side side, long quantity) { }
    record Cancel(String accountId, String orderRef) { }

    static List<String> accounts(RoleRun roles, String person) {
        return roles.access().personAccounts().stream().filter(a -> a.personPartyId().equals(person))
                .map(RoleAccess.PersonAccount::accountId).toList();
    }

    // 序号只在本账户内累计，包括已终态订单。撮合状态按不可复用的 orderId 升序保存。
    static Map<String, WorldExchange.Order> ownOrders(WorldExchange.State exchange, String account) {
        var result = new LinkedHashMap<String, WorldExchange.Order>();
        for (var order : exchange.orders()) if (order.accountId().equals(account))
            result.put(account + "/" + (result.size() + 1), order);
        return result;
    }

    static boolean observedAccount(JsonNode observation, String account) {
        for (var entry : observation.path("profile").path("accounts"))
            if (account.equals(entry.path("accountId").asText()) && "PERSONAL".equals(entry.path("kind").asText())) return true;
        return false;
    }

    static boolean observed(JsonNode observation, String account, String ref) {
        for (var entry : observation.path("engineering").path("personalMarket").path("openOrders"))
            if (account.equals(entry.path("accountId").asText()) && ref.equals(entry.path("orderRef").asText())) return true;
        return false;
    }

    static boolean matches(DecisionRun.ActionRef ref, WorldExchange.OrderReport report, long position) {
        String kind = SUBMIT.equals(ref.tool()) || MARKET.equals(ref.tool()) ? PendingTask.EXCHANGE_ORDER_V1
                : CANCEL.equals(ref.tool()) ? PendingTask.EXCHANGE_CANCEL_V1 : null;
        return kind != null && report != null && report.taskId().equals(ref.taskId())
                && report.commandId().equals(DecisionRun.actionCommandId(ref.actionId())) && kind.equals(report.kind())
                && report.timeMillis() == ref.dueMillis() && report.position() <= position;
    }

    static void append(ObjectNode root, List<String> accounts, WorldExchange.State exchange,
                       DecisionRun run, String person, long position, boolean explicitLimitRules) {
        if (accounts.isEmpty()) return;
        var market = root.putObject("personalMarket");
        market.put("policy", VERSION);
        market.put("contract", explicitLimitRules
                ? "你可用本人自有账户申报限价买单或卖单，也可等待。申报不要求已有报价、成交价或基金产品授权。限价与数量由你自行决定。买单需足额可用现金，卖单需足额可卖持仓。只有价格相容的真实对手单才能成交。未成交余量留簿，买单按限价冻结现金，卖单冻结对应持仓，直至成交、本人撤销或收盘。撤单需引用本人有效挂单，结果以执行回报为准。"
                : "可用个人账户的可用现金或可卖持仓申报限价单。价格相容的真实对手单可成交。未成交余量留簿冻结，直至成交、本人撤销或收盘。撤单需本人活动单引用，结果以执行回报为准。");
        var open = market.putArray("openOrders");
        for (String account : accounts) ownOrders(exchange, account).forEach((ref, order) -> {
            if (order.status() == WorldExchange.OrderStatus.ACTIVE)
                open.addObject().put("orderRef", ref).put("accountId", account).put("symbol", order.symbol())
                        .put("side", order.side().name()).put("limitPrice", order.limitPrice().toPlainString())
                        .put("quantity", order.quantity()).put("filled", order.filled()).put("remaining", order.remaining());
        });
        var deliberation = root.path("deliberation");
        for (var row : deliberation.path("recentDecisions")) feedback((ObjectNode) row, accounts, run, person, exchange, position);
        if (deliberation.path("lastActionResult") instanceof ObjectNode last) feedback(last, accounts, run, person, exchange, position);
    }

    private static void feedback(ObjectNode row, List<String> accounts, DecisionRun run, String person,
                                 WorldExchange.State exchange, long position) {
        var ref = run.actionRef(row.path("actionId").asText());
        if (ref == null || !person.equals(run.binding(ref.actionId()).personPartyId())) return;
        var report = exchange.reports().get(ref.taskId());
        if (!matches(ref, report, position)) return;
        row.put("resultTaskId", report.taskId()).put("resultKind", report.kind()).put("resultAt", report.timeMillis())
                .put("resultPosition", report.position()).put("reason", report.reason()).put("accepted", report.accepted())
                .put("execution", report.accepted() ? "APPLIED" : "REJECTED");
        if (!report.accepted()) row.put("businessReject", report.reason());
        if (report.orderId() == null) return;
        var order = exchange.order(report.orderId());
        if (order != null && accounts.contains(order.accountId())) {
            row.put("orderStatus", order.status().name()).put("symbol", order.symbol())
                    .put("filled", order.filled()).put("remaining", order.live() ? order.remaining() : 0);
            if (order.status() == WorldExchange.OrderStatus.CANCELLED) row.put("cancelledQuantity", order.remaining());
        }
    }
}
