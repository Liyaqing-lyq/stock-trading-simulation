package edu.cufe.auction.app;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.SecuritiesLedger;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.WorldRuntime;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 世界订单撮合的正式可复跑演示：不需要测试框架，也不改课程 {@code data} 目录。
 *
 * <p>用法：{@code WorldExchangeDemo <dir> [prepare|full]}。{@code dir} 省略时使用
 * {@code logs/world-exchange-demo}（课程验收会整体清理 {@code data}，世界存档须放在其外）；
 * 模式省略时按 {@code full} 恢复。</p>
 *
 * <ul>
 *   <li>{@code prepare}：挂 A 卖 100 股 @10 并确认，再排 B 买限 10.20×60（到期 20），
 *       在模拟时间 10 退出。磁盘上留下活动卖单、100 股预留和一条未执行待办。</li>
 *   <li>{@code full}：恢复同一日志，推进到 30：成交 60@10，A 现金 1600、B 400，
 *       持股 A 40 / B 160，剩余卖单 40 与预留 40；随后以完全相同的命令重发并再次推进到 30，
 *       验证不多成交、不加提交位置。可重复执行同一模式验证幂等。</li>
 * </ul>
 *
 * <p>这是 model=none 的确定性管理夹具，不调用真实模型、不读取课程配置。</p>
 */
public final class WorldExchangeDemo {

    private static final String RUN = "world-exchange-demo";
    private static final long SEED = 20261106L;
    private static final String SYMBOL = "DEMO1";
    private static final String SELL_COMMAND = "demo:sell-a";
    private static final String SELL_TASK = "demo-sell-a";
    private static final String BUY_COMMAND = "demo:buy-b";
    private static final String BUY_TASK = "demo-buy-b";

    private WorldExchangeDemo() {
    }

    /**
     * 运行演示。
     *
     * @param args 可选目录与模式（{@code prepare} / {@code full}）
     * @throws Exception 日志不可用或世界状态校验失败
     */
    public static void main(String[] args) throws Exception {
        Path dir = args.length > 0 ? Path.of(args[0])
                : Path.of("logs", "world-exchange-demo");
        String mode = args.length > 1 ? args[1] : "full";
        if (!"prepare".equals(mode) && !"full".equals(mode)) {
            System.out.println("[WorldExchangeDemo] 用法: WorldExchangeDemo <dir> [prepare|full]");
            return;
        }
        Files.createDirectories(dir);
        Path file = dir.resolve("exchange.journal");
        System.out.println("[WorldExchangeDemo] mode=" + mode + " journal=" + file.toAbsolutePath());

        try (WorldRuntime runtime = new WorldRuntime(file, manifest(), openings(), registry())) {
            if ("prepare".equals(mode)) {
                prepare(runtime);
            } else {
                full(runtime);
            }
            report(runtime, mode);
        }
    }

    private static void prepare(WorldRuntime runtime) throws Exception {
        System.out.println("[WorldExchangeDemo] " + runtime.scheduleOrder(SELL_COMMAND, SELL_TASK,
                10, "alice", "A", SYMBOL, Side.SELL, OrderType.LIMIT, money("10.00"), 100));
        runtime.advanceTo(10);
        System.out.println("[WorldExchangeDemo] " + runtime.scheduleOrder(BUY_COMMAND, BUY_TASK,
                20, "bob", "B", SYMBOL, Side.BUY, OrderType.LIMIT, money("10.20"), 60));
        System.out.println("[WorldExchangeDemo] prepare 退出于 time=" + runtime.snapshot().timeMillis()
                + "，活动卖单、预留与买单待办已持久");
    }

    private static void full(WorldRuntime runtime) throws Exception {
        // 重发原命令：已排程的命令返回原 SCHEDULED，不因时间推进再排一次
        System.out.println("[WorldExchangeDemo] " + runtime.scheduleOrder(SELL_COMMAND, SELL_TASK,
                10, "alice", "A", SYMBOL, Side.SELL, OrderType.LIMIT, money("10.00"), 100));
        System.out.println("[WorldExchangeDemo] " + runtime.scheduleOrder(BUY_COMMAND, BUY_TASK,
                20, "bob", "B", SYMBOL, Side.BUY, OrderType.LIMIT, money("10.20"), 60));
        runtime.advanceTo(30);
        long position = runtime.snapshot().position();
        long trades = exchange(runtime).trades().size();

        // 同一进程内再次重发同命令、推进到同一目标时间：不得新增成交或提交
        runtime.scheduleOrder(SELL_COMMAND, SELL_TASK, 10, "alice", "A", SYMBOL, Side.SELL,
                OrderType.LIMIT, money("10.00"), 100);
        runtime.scheduleOrder(BUY_COMMAND, BUY_TASK, 20, "bob", "B", SYMBOL, Side.BUY,
                OrderType.LIMIT, money("10.20"), 60);
        runtime.advanceTo(30);
        System.out.println("[WorldExchangeDemo] replay position " + position + " -> "
                + runtime.snapshot().position() + ", trades " + trades + " -> "
                + exchange(runtime).trades().size());
    }

    private static void report(WorldRuntime runtime, String mode) {
        WorldRuntime.Snapshot snapshot = runtime.snapshot();
        WorldExchange.State exchange = snapshot.exchange().orElseThrow();
        System.out.println("[WorldExchangeDemo] mode=" + mode
                + " time=" + snapshot.timeMillis()
                + " position=" + snapshot.position()
                + " orders=" + exchange.orders().size()
                + " trades=" + exchange.trades().size()
                + " pending=" + snapshot.pending().size()
                + " paused=" + runtime.isPaused());
        System.out.println("[WorldExchangeDemo] cash A=" + snapshot.accounts().get("A").cash()
                + " B=" + snapshot.accounts().get("B").cash()
                + " frozenB=" + WorldExchange.frozenCash(exchange, "B")
                + " freeB=" + WorldExchange.freeCash(exchange, snapshot.accounts(), "B"));
        System.out.println("[WorldExchangeDemo] shares A=" + held(snapshot, "A")
                + " B=" + held(snapshot, "B"));
        for (WorldExchange.Order order : exchange.orders()) {
            System.out.println("[WorldExchangeDemo] order " + order.orderId()
                    + " " + order.side() + " " + order.type()
                    + " limit=" + order.limitPrice()
                    + " qty=" + order.quantity()
                    + " filled=" + order.filled()
                    + " remaining=" + order.remaining()
                    + " status=" + order.status());
        }
        for (SecuritiesLedger.SellReservation reservation
                : snapshot.securities().orElseThrow().reservations()) {
            System.out.println("[WorldExchangeDemo] reservation " + reservation.reservationId()
                    + " qty=" + reservation.quantity()
                    + " allocations=" + reservation.allocations());
        }
    }

    private static long held(WorldRuntime.Snapshot snapshot, String accountId) {
        long total = 0L;
        for (SecuritiesLedger.Lot lot : snapshot.securities().orElseThrow().lots()) {
            if (lot.accountId().equals(accountId) && lot.symbol().equals(SYMBOL)) {
                total = Math.addExact(total, lot.remaining());
            }
        }
        return total;
    }

    private static WorldExchange.State exchange(WorldRuntime runtime) {
        return runtime.snapshot().exchange().orElseThrow();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        return new RunManifest(RUN, SEED, RunManifest.RULES_SECURITIES_MATCHING_EXPERIMENT_V1,
                modules, Map.of("fixture", "world-exchange-demo-v1"), RunManifest.MODEL_NONE);
    }

    private static List<OpeningAccount> openings() {
        return List.of(
                new OpeningAccount("A", "alice", money("1000.00"), "演示开局历史禀赋"),
                new OpeningAccount("B", "bob", money("1000.00"), "演示开局历史禀赋"));
    }

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party("alice", OpeningRegistry.PartyKind.PERSON, "Alice"),
                new OpeningRegistry.Party("bob", OpeningRegistry.PartyKind.PERSON, "Bob"),
                new OpeningRegistry.Party("acme", OpeningRegistry.PartyKind.ORGANIZATION, "Acme"));
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>();
        owners.add(new OpeningRegistry.PropertyOwner("alice",
                OpeningRegistry.OwnerKind.PERSON, "alice"));
        owners.add(new OpeningRegistry.PropertyOwner("bob",
                OpeningRegistry.OwnerKind.PERSON, "bob"));
        owners.add(new OpeningRegistry.PropertyOwner("acme",
                OpeningRegistry.OwnerKind.ORGANIZATION, "acme"));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYMBOL, "acme", 200, money("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = new ArrayList<>();
        allocations.add(new OpeningRegistry.ShareAllocation("A", SYMBOL, 100,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        allocations.add(new OpeningRegistry.ShareAllocation("B", SYMBOL, 100,
                new BigDecimal("10.0000"), OpeningRegistry.ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }
}
