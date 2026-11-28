package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 个人接管交易台。账户取自本人角色视图，成交、冻结与撤销由普通交易所办理。 */
public final class PersonalOrderDesk {
    private PersonalOrderDesk() { }

    /** 一次本人手工委托或撤单申请，账号和活动单引用均属于当前人物。 */
    public record Instruction(String action, String accountId, String symbol, Side side,
                              BigDecimal limitPrice, long quantity, String orderRef) { }

    private static String prefix(String who) {
        return "web-personal-order:" + who.length() + ":" + who + ":";
    }

    private static boolean enabled(WorldRuntime w) {
        var versions = w.profile().manifest().dataVersions();
        return RunManifest.MARKED_TAKEOVER_BRANCH.equals(versions.get(RunManifest.PERSON_TAKEOVER_POLICY))
                && PersonalMarket.VERSION.equals(versions.get(PersonalMarket.POLICY));
    }

    /** 返回本人可操作的自有账户、个人订单别名以及本人的手工操作回执。 */
    public static ObjectNode view(WorldRuntime w, String who) throws IOException {
        var out = JsonNodeFactory.instance.objectNode();
        var accounts = out.putArray("accounts");
        var symbols = out.putArray("symbols");
        var open = out.putArray("openOrders");
        var receipts = out.putArray("receipts");
        var snap = w.snapshot();
        out.put("enabled", false).put("observedMillis", Long.toString(snap.timeMillis()));
        if (!enabled(w)) return out;
        var profile = w.roleView(who);
        var owned = profile.accounts().stream().filter(a -> a.kind() == RoleView.AccountKind.PERSONAL).toList();
        if (owned.isEmpty()) return out;
        out.put("enabled", true);
        if (PersonalMarket.marketOrders(w.profile().manifest())) out.put("marketOrders", true);
        profile.market().forEach(q -> symbols.add(q.symbol()));
        var exchange = snap.exchange().orElseThrow();
        var refs = new LinkedHashMap<Long,String>();
        for (var account : owned) {
            accounts.add(account.accountId());
            PersonalMarket.ownOrders(exchange, account.accountId()).forEach((ref, o) -> {
                refs.put(o.orderId(), ref);
                if (o.live()) open.addObject().put("accountId", o.accountId()).put("orderRef", ref)
                        .put("symbol", o.symbol()).put("side", o.side().name()).put("limitPrice", o.limitPrice().toPlainString())
                        .put("quantity", Long.toString(o.quantity())).put("filled", Long.toString(o.filled()))
                        .put("remaining", Long.toString(o.remaining()));
            });
        }
        String idPrefix = prefix(who);
        var candidates = exchange.reports().values().stream().filter(r -> r.commandId().startsWith(idPrefix))
                .sorted(Comparator.comparingLong(WorldExchange.OrderReport::position).reversed()).toList();
        for (var r : candidates) {
            String action = w.manualOrderAction(who, r);
            if (action == null) continue;
            var row = receipts.addObject().put("commandId", r.commandId().substring(idPrefix.length()))
                    .put("timeMillis", Long.toString(r.timeMillis())).put("accepted", r.accepted()).put("reason", r.reason())
                    .put("action", action);
            Long id = r.orderId() != null ? r.orderId()
                    : r.cancelledOrderIds().isEmpty() ? null : r.cancelledOrderIds().get(0);
            if (id != null && refs.containsKey(id)) {
                var order = exchange.order(id);
                row.put("orderRef", refs.get(id)).put("status", order.status().name())
                        .put("filled", Long.toString(order.filled()))
                        .put("remaining", Long.toString(order.live() ? order.remaining() : 0));
                if ("MARKET".equals(action) && order.status() == WorldExchange.OrderStatus.CANCELLED)
                    row.put("cancelledQuantity", Long.toString(order.remaining()));
            }
            if (receipts.size() == 20) break;
        }
        return out;
    }

    /** 在调用方的单一写入线程上，按冻结的工具耗时排程本人自有账户的真实订单或撤销。 */
    public static Map<String,String> schedule(WorldRuntime w, String who, long observed, String clientId,
                                              Instruction draft) throws IOException {
        if (!enabled(w) || w.branchOrigin().filter(o -> o.kind() == BranchOrigin.Kind.GOD_TAKEOVER).isEmpty())
            throw new IllegalArgumentException("需要人工接管分支");
        if (clientId == null || clientId.isBlank() || clientId.length() > 128)
            throw new IllegalArgumentException("命令标识不合法");
        var view = w.roleView(who);
        var account = view.accounts().stream().filter(a -> a.kind() == RoleView.AccountKind.PERSONAL
                && a.accountId().equals(draft.accountId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前没有可用的个人交易账户"));
        var snap = w.snapshot();
        var exchange = snap.exchange().orElseThrow();
        String command = prefix(who) + clientId;
        String task = command + ":task";
        boolean retry = exchange.reports().containsKey(task) || snap.pending().stream().anyMatch(t -> t.taskId().equals(task));
        if (!retry && observed != snap.timeMillis()) throw new IllegalStateException("本人交易观察时点已变化");
        String tool = switch (draft.action()) {
            case "LIMIT" -> PersonalMarket.SUBMIT;
            case "MARKET" -> {
                if (!PersonalMarket.marketOrders(w.profile().manifest())) throw new IllegalArgumentException("当前档案未开放个人市价委托");
                yield PersonalMarket.MARKET;
            }
            case "CANCEL" -> PersonalMarket.CANCEL;
            default -> throw new IllegalArgumentException("未知交易动作");
        };
        long due = Math.addExact(observed, snap.decisions().orElseThrow().state().config().actionMillisByTool().get(tool));
        String receipt;
        if (!PersonalMarket.CANCEL.equals(tool)) {
            if (view.market().stream().noneMatch(q -> q.symbol().equals(draft.symbol())))
                throw new IllegalArgumentException("当前不可见的证券");
            receipt = w.scheduleOrder(command, task, due, account.ownerId(), account.accountId(), draft.symbol(),
                    draft.side(), OrderType.valueOf(draft.action()), draft.limitPrice(), draft.quantity(), null, who);
        } else {
            var order = PersonalMarket.ownOrders(exchange, account.accountId()).get(draft.orderRef());
            if (order == null || (!retry && !order.live())) throw new IllegalArgumentException("本人没有这笔活动委托");
            receipt = w.scheduleCancelOrder(command, task, due, account.ownerId(), order.orderId(), who);
        }
        return Map.of("receipt", receipt, "dueTimeMillis", Long.toString(due));
    }
}
