package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankState;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B7a World 自测：组合开局、存款成交通道、部分成交与撤单、市价语义、多 maker 原子回滚、
 * 恢复与逐帧重演、幂等重放。
 *
 * <p>入口分两种：无参跑完整自测；带参数按 {@code open/resume/verify/advance} 做跨 JVM 证据
 * （由 {@code scripts/run-b7-bank-funds-world.sh} 串起来）。</p>
 */
public final class BankFundsWorldSelfTest {

    private static final String SYM = BankFundsFixture.SYMBOL;
    private static final long T = BankFundsFixture.T0;
    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    private BankFundsWorldSelfTest() {
    }

    /** @param args 无参跑自测；否则是跨 JVM 模式 */
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            crossJvm(args);
            return;
        }
        opening();
        example3();
        idempotent();
        example4();
        marketOrder();
        multiMakerRollback();
        withdrawHonorsOrderFreeze();
        reopen();
        System.out.println();
        System.out.println("PASS=" + passed + " FAIL=" + failed.size());
        for (String item : failed) {
            System.out.println("  FAILED: " + item);
        }
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 组合开局

    private static void opening() throws Exception {
        Path dir = Files.createTempDirectory("b7a-open").resolve("world.log");
        try (WorldRuntime runtime = BankFundsFixture.open(dir)) {
            WorldRuntime.Snapshot snap = runtime.snapshot();
            boolean ok = runtime.bankOf().isPresent()
                    && snap.exchange().isPresent() && snap.securities().isPresent()
                    && snap.roles().isPresent() && snap.information().isPresent()
                    && snap.position() == 1L
                    && snap.exchange().get().orders().isEmpty()
                    && runtime.bankOf().orElseThrow().decode(snap.accounts()).state()
                    .depositOf("north", "acme").compareTo(BankFundsFixture.money("1200.00")) == 0;
            ok("组合开局：banking+custody+securities+matching+roles+information+funding 同档可开局，"
                    + "订单表为空、acme 存款 1200", ok, "position=" + snap.position());
        } catch (Exception invalid) {
            ok("组合开局：banking+custody+securities+matching+roles+information+funding 同档可开局，"
                    + "订单表为空、acme 存款 1200", false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 例 3

    private static void example3() throws Exception {
        Path dir = Files.createTempDirectory("b7a-e3").resolve("world.log");
        try (WorldRuntime runtime = BankFundsFixture.open(dir)) {
            runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash", SYM,
                    Side.SELL, OrderType.LIMIT, BankFundsFixture.money("10.00"), 100L);
            runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankFundsFixture.money("10.20"), 100L,
                    BankFundsFixture.token("north", "acme"));
            runtime.advanceTo(T + 120_000L);
            WorldRuntime.Snapshot snap = runtime.snapshot();
            BankState bank = runtime.bankOf().orElseThrow().decode(snap.accounts()).state();
            BigDecimal price = snap.exchange().orElseThrow().trades().get(0).price();
            boolean ok = snap.exchange().orElseThrow().trades().size() == 1
                    && price.compareTo(BankFundsFixture.money("10.00")) == 0
                    && bank.depositOf("north", "acme")
                    .compareTo(BankFundsFixture.money("200.00")) == 0
                    && snap.accounts().get("rsv-north").cash()
                    .compareTo(BankFundsFixture.money("200.00")) == 0
                    && snap.accounts().get("firm-cash").cash()
                    .compareTo(BankFundsFixture.money("1400.00")) == 0
                    && bank.depositOf("north", "acme").signum() >= 0;
            ok("例 3（World）：存款 1200 付 1000 给现金卖方 → 被动价 10.00、存款 200、准备金 200、"
                    + "卖方 1400", ok, "price=" + price
                    + " dep=" + bank.depositOf("north", "acme")
                    + " reserve=" + snap.accounts().get("rsv-north").cash()
                    + " seller=" + snap.accounts().get("firm-cash").cash());
        } catch (Exception invalid) {
            ok("例 3（World）：存款 1200 付 1000 给现金卖方 → 被动价 10.00、存款 200、准备金 200、"
                    + "卖方 1400", false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 幂等

    private static void idempotent() throws Exception {
        Path dir = Files.createTempDirectory("b7a-idem").resolve("world.log");
        try (WorldRuntime runtime = BankFundsFixture.open(dir)) {
            String first = runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner",
                    "firm-cash", SYM, Side.SELL, OrderType.LIMIT,
                    BankFundsFixture.money("10.00"), 100L);
            String again = runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner",
                    "firm-cash", SYM, Side.SELL, OrderType.LIMIT,
                    BankFundsFixture.money("10.00"), 100L);
            runtime.advanceTo(T + 60_000L);
            int trades = runtime.snapshot().exchange().orElseThrow().trades().size();
            boolean conflictRejected = false;
            try {
                runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash",
                        SYM, Side.SELL, OrderType.LIMIT, BankFundsFixture.money("10.10"), 100L);
            } catch (IllegalArgumentException expected) {
                conflictRejected = true;
            }
            boolean ok = first.equals(again) && first.startsWith("SCHEDULED:")
                    && trades == 0 && conflictRejected;
            ok("幂等：同 commandId 同内容返回原回报且不重复排程；同 id 换内容明确拒绝", ok,
                    first + " / trades=" + trades + " / conflict=" + conflictRejected);
        } catch (Exception invalid) {
            ok("幂等：同 commandId 同内容返回原回报且不重复排程；同 id 换内容明确拒绝", false,
                    String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 例 4

    private static void example4() throws Exception {
        Path dir = Files.createTempDirectory("b7a-e4").resolve("world.log");
        try (WorldRuntime runtime = BankFundsFixture.open(dir)) {
            runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash", SYM,
                    Side.SELL, OrderType.LIMIT, BankFundsFixture.money("10.00"), 40L);
            runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankFundsFixture.money("10.20"), 100L,
                    BankFundsFixture.token("north", "acme"));
            runtime.advanceTo(T + 120_000L);
            WorldRuntime.Snapshot snap = runtime.snapshot();
            BankState bank = runtime.bankOf().orElseThrow().decode(snap.accounts()).state();
            WorldExchange.State exchange = snap.exchange().orElseThrow();
            WorldExchange.Order buy = null;
            for (WorldExchange.Order order : exchange.orders()) {
                if (order.side() == Side.BUY && order.live()) {
                    buy = order;
                }
            }
            BigDecimal frozen = buy == null ? BigDecimal.ZERO
                    : WorldExchange.frozenIn(exchange, buy.effectiveFunding());
            BigDecimal free = buy == null ? BigDecimal.ZERO
                    : WorldExchange.freeIn(exchange, snap.accounts(), bank, buy.effectiveFunding());
            boolean beforeCancel = buy != null && buy.remaining() == 60L
                    && bank.depositOf("north", "acme")
                    .compareTo(BankFundsFixture.money("800.00")) == 0
                    && frozen.compareTo(BankFundsFixture.money("612.00")) == 0
                    && free.compareTo(BankFundsFixture.money("188.00")) == 0;
            String cancelOutcome = "SKIP";
            BigDecimal afterCancel = BigDecimal.ZERO;
            if (buy != null) {
                cancelOutcome = runtime.scheduleCancelOrder("c-cancel", "t-cancel", T + 180_000L,
                        "acme-owner", buy.orderId());
                runtime.advanceTo(T + 180_000L);
                WorldRuntime.Snapshot after = runtime.snapshot();
                BankState bankAfter = runtime.bankOf().orElseThrow()
                        .decode(after.accounts()).state();
                WorldExchange.Order cancelled = after.exchange().orElseThrow().order(buy.orderId());
                afterCancel = WorldExchange.freeIn(after.exchange().orElseThrow(),
                        after.accounts(), bankAfter, cancelled.effectiveFunding());
            }
            boolean ok = beforeCancel && cancelOutcome.startsWith("SCHEDULED:")
                    && afterCancel.compareTo(BankFundsFixture.money("800.00")) == 0;
            ok("例 4（World）：只成交 40 股 → 存款 800、剩 60、剩冻 612、可用 188；撤单后可用 800",
                    ok, "frozen=" + frozen + " free=" + free + " afterCancel=" + afterCancel);
        } catch (Exception invalid) {
            ok("例 4（World）：只成交 40 股 → 存款 800、剩 60、剩冻 612、可用 188；撤单后可用 800",
                    false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 市价语义

    private static void marketOrder() throws Exception {
        Path dir = Files.createTempDirectory("b7a-market").resolve("world.log");
        try (WorldRuntime runtime = BankFundsFixture.open(dir)) {
            runtime.scheduleOrder("c-mkt-none", "t-mkt-none", T + 60_000L, "acme-owner",
                    "acme-cash", SYM, Side.BUY, OrderType.MARKET, null, 10L,
                    BankFundsFixture.token("north", "acme"));
            runtime.advanceTo(T + 60_000L);
            WorldRuntime.Snapshot snap = runtime.snapshot();
            WorldExchange.State exchange = snap.exchange().orElseThrow();
            boolean noLiquidity = exchange.orders().isEmpty()
                    && exchange.reports().get("t-mkt-none") != null
                    && WorldExchange.REASON_NO_LIQUIDITY.equals(
                    exchange.reports().get("t-mkt-none").reason());
            // 有对手时：市价买按最优对手价冻结并成交，剩余撤销
            runtime.scheduleOrder("c-sell", "t-sell", T + 120_000L, "firm-owner", "firm-cash",
                    SYM, Side.SELL, OrderType.LIMIT, BankFundsFixture.money("10.00"), 40L);
            runtime.scheduleOrder("c-mkt", "t-mkt", T + 180_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.MARKET, null, 100L,
                    BankFundsFixture.token("north", "acme"));
            runtime.advanceTo(T + 180_000L);
            WorldRuntime.Snapshot after = runtime.snapshot();
            WorldExchange.State ex2 = after.exchange().orElseThrow();
            WorldExchange.Order market = null;
            for (WorldExchange.Order order : ex2.orders()) {
                if ("t-mkt".equals(order.taskId())) {
                    market = order;
                }
            }
            BankState bank = runtime.bankOf().orElseThrow().decode(after.accounts()).state();
            boolean ok = noLiquidity && market != null
                    && market.status() == WorldExchange.OrderStatus.CANCELLED
                    && market.filled() == 40L
                    && market.remaining() == 60L
                    && ex2.trades().size() == 1
                    && ex2.trades().get(0).price().compareTo(BankFundsFixture.money("10.00")) == 0
                    && bank.depositOf("north", "acme")
                    .compareTo(BankFundsFixture.money("800.00")) == 0;
            ok("市价语义保持：无对手 → NO_LIQUIDITY 不进簿；有对手 → 按最优对手价冻结成交、"
                            + "剩余撤销（不发明冻结价）", ok,
                    "noLiquidity=" + noLiquidity + " filled="
                            + (market == null ? "-" : market.filled())
                            + " status=" + (market == null ? "-" : market.status())
                            + " dep=" + bank.depositOf("north", "acme"));
        } catch (Exception invalid) {
            ok("市价语义保持：无对手 → NO_LIQUIDITY 不进簿；有对手 → 按最优对手价冻结成交、"
                    + "剩余撤销（不发明冻结价）", false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 多 maker 回滚

    private static void multiMakerRollback() throws Exception {
        Path dir = Files.createTempDirectory("b7a-multi").resolve("world.log");
        try (WorldRuntime runtime = BankFundsFixture.open(dir, "1200.00", "1200.00", "600.00")) {
            runtime.scheduleOrder("c-b1", "t-b1", T + 60_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankFundsFixture.money("10.00"), 40L);
            runtime.scheduleOrder("c-b2", "t-b2", T + 120_000L, "firm-owner", "firm-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankFundsFixture.money("10.00"), 60L,
                    BankFundsFixture.token("south", "firm"));
            runtime.advanceTo(T + 120_000L);
            WorldRuntime.Snapshot before = runtime.snapshot();
            BankFundsFixture.Digests digestsBefore = BankFundsFixture.digests(runtime);
            runtime.scheduleOrder("c-s", "t-s", T + 180_000L, "co-owner", "co-cash", SYM,
                    Side.SELL, OrderType.LIMIT, BankFundsFixture.money("10.00"), 100L);
            runtime.advanceTo(T + 180_000L);
            WorldRuntime.Snapshot after = runtime.snapshot();
            WorldExchange.State exchange = after.exchange().orElseThrow();
            WorldExchange.OrderReport report = exchange.reports().get("t-s");
            boolean ok = report != null && !report.accepted()
                    && report.reason().startsWith(WorldExchange.REASON_RESERVE_INSUFFICIENT)
                    && exchange.trades().isEmpty()
                    && exchange.orders().size() == 2
                    && sameRemaining(before.exchange().orElseThrow(), exchange)
                    && exchange.nextTradeId() == 1L
                    && BankFundsFixture.digests(runtime).bank()
                    .equals(digestsBefore.bank());
            ok("多 maker（World）：第 2 笔资金腿准备金不足 → 整条 submit 拒绝，maker 数量/序号/成交/"
                            + "银行/现金全保留，拒绝回执合法记录且不跳 FIFO", ok,
                    "report=" + (report == null ? "-" : report.reason())
                            + " trades=" + exchange.trades().size()
                            + " orders=" + exchange.orders().size());
        } catch (Exception invalid) {
            ok("多 maker（World）：第 2 笔资金腿准备金不足 → 整条 submit 拒绝，maker 数量/序号/成交/"
                    + "银行/现金全保留，拒绝回执合法记录且不跳 FIFO", false, String.valueOf(invalid));
        }
    }

    private static boolean sameRemaining(WorldExchange.State before, WorldExchange.State after) {
        Map<Long, Long> left = new TreeMap<>();
        for (WorldExchange.Order order : before.orders()) {
            left.put(order.orderId(), order.remaining());
        }
        Map<Long, Long> right = new TreeMap<>();
        for (WorldExchange.Order order : after.orders()) {
            right.put(order.orderId(), order.remaining());
        }
        return left.equals(right);
    }

    // ------------------------------------------------------------------ 银行动作守冻结

    /**
     * 真实角色会话取现必须守同一份订单冻结：存款 1200、活动买单冻结 1020 时，
     * 取 1100 被拒（可用 180），取 150 成功 —— 这条路径走的是运行层派生的真实快照，
     * 不是纯核里手工构造的 {@code CashFreeze}。
     */
    private static void withdrawHonorsOrderFreeze() throws Exception {
        Path dir = Files.createTempDirectory("b7a-withdraw").resolve("world.log");
        try (WorldRuntime runtime = BankFundsFixture.open(dir)) {
            runtime.scheduleOrder("c-buy", "t-buy", T + 60_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankFundsFixture.money("10.20"), 100L,
                    BankFundsFixture.token("north", "acme"));
            runtime.advanceTo(T + 60_000L);
            WorldRoleSession session = runtime.openRoleSession("acme");
            session.bankAction("w-over", "w-over", T + 120_000L, 10_000L,
                    new WorldRoleSession.BankActionDraft("w-over",
                            edu.cufe.auction.world.bank.BankActions.Kind.WITHDRAW, "north", "",
                            BankFundsFixture.money("1100.00"), 0L, 0L, "", "", "", ""));
            runtime.advanceTo(T + 120_000L);
            BankState bank = runtime.bankOf().orElseThrow()
                    .decode(runtime.snapshot().accounts()).state();
            BigDecimal afterReject = bank.depositOf("north", "acme");
            String reason = "";
            boolean accepted = true;
            for (WorldRuntime.BankActionAudit audit : runtime.bankActionAudits()) {
                if (audit.taskId().contains("w-over")) {
                    accepted = audit.accepted();
                    reason = audit.reason();
                }
            }
            boolean rejected = !accepted && reason.startsWith("PAYER_BALANCE_INSUFFICIENT");
            session.bankAction("w-ok", "w-ok", T + 180_000L, 10_000L,
                    new WorldRoleSession.BankActionDraft("w-ok",
                            edu.cufe.auction.world.bank.BankActions.Kind.WITHDRAW, "north", "",
                            BankFundsFixture.money("150.00"), 0L, 0L, "", "", "", ""));
            runtime.advanceTo(T + 180_000L);
            BankState after = runtime.bankOf().orElseThrow()
                    .decode(runtime.snapshot().accounts()).state();
            boolean ok = rejected
                    && afterReject.compareTo(BankFundsFixture.money("1200.00")) == 0
                    && after.depositOf("north", "acme")
                    .compareTo(BankFundsFixture.money("1050.00")) == 0
                    && runtime.snapshot().accounts().get("acme-cash").cash()
                    .compareTo(BankFundsFixture.money("1150.00")) == 0;
            ok("角色会话取现守同一冻结：存款 1200、冻结 1020 时取 1100 被拒（可用 180），"
                            + "取 150 成功 → 存款 1050、现金 1150", ok,
                    "reason=" + reason + " afterReject=" + afterReject
                            + " after=" + after.depositOf("north", "acme")
                            + " cash=" + runtime.snapshot().accounts().get("acme-cash").cash());
        } catch (Exception invalid) {
            ok("角色会话取现守同一冻结：存款 1200、冻结 1020 时取 1100 被拒（可用 180），"
                    + "取 150 成功 → 存款 1050、现金 1150", false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 恢复

    private static void reopen() throws Exception {
        Path dir = Files.createTempDirectory("b7a-reopen").resolve("world.log");
        BankFundsFixture.Digests before;
        try (WorldRuntime runtime = BankFundsFixture.open(dir)) {
            runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash", SYM,
                    Side.SELL, OrderType.LIMIT, BankFundsFixture.money("10.00"), 40L);
            runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankFundsFixture.money("10.20"), 100L,
                    BankFundsFixture.token("north", "acme"));
            runtime.advanceTo(T + 120_000L);
            before = BankFundsFixture.digests(runtime);
        } catch (Exception invalid) {
            ok("恢复：新进程重开摘要与上一进程完全一致（存款/订单/持仓/银行）", false,
                    String.valueOf(invalid));
            return;
        }
        try (WorldRuntime runtime = BankFundsFixture.open(dir)) {
            BankFundsFixture.Digests after = BankFundsFixture.digests(runtime);
            boolean ok = after.equals(before);
            ok("恢复：新进程重开摘要与上一进程完全一致（存款/订单/持仓/银行）", ok,
                    "before=" + before + " after=" + after);
        } catch (Exception invalid) {
            ok("恢复：新进程重开摘要与上一进程完全一致（存款/订单/持仓/银行）", false,
                    String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 跨 JVM

    private static void crossJvm(String[] args) throws Exception {
        String mode = args[0];
        Path dir = Path.of(args[1]);
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        if ("open".equals(mode)) {
            try (WorldRuntime runtime = BankFundsFixture.open(file)) {
                runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash",
                        SYM, Side.SELL, OrderType.LIMIT, BankFundsFixture.money("10.00"), 40L);
                runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash",
                        SYM, Side.BUY, OrderType.LIMIT, BankFundsFixture.money("10.20"), 100L,
                        BankFundsFixture.token("north", "acme"));
                runtime.advanceTo(T + 120_000L);
                System.out.println("DIGEST=" + BankFundsFixture.digests(runtime));
            }
            return;
        }
        if ("resume".equals(mode)) {
            try (WorldRuntime runtime = BankFundsFixture.open(file)) {
                System.out.println("DIGEST=" + BankFundsFixture.digests(runtime));
                runtime.scheduleCancelOrder("c-cancel", "t-cancel", T + 180_000L, "acme-owner",
                        lastLiveBuy(runtime));
                runtime.advanceTo(T + 180_000L);
                System.out.println("AFTER=" + BankFundsFixture.digests(runtime));
            }
            return;
        }
        if ("verify".equals(mode)) {
            try (WorldRuntime runtime = BankFundsFixture.open(file)) {
                System.out.println("DIGEST=" + BankFundsFixture.digests(runtime));
            }
            return;
        }
        if ("advance".equals(mode)) {
            try (WorldRuntime runtime = BankFundsFixture.open(file)) {
                runtime.scheduleOrder("c-sell2", "t-sell2", runtime.snapshot().timeMillis() + 60_000L,
                        "firm-owner", "firm-cash", SYM, Side.SELL, OrderType.LIMIT,
                        BankFundsFixture.money("9.00"), 10L);
                long due = runtime.snapshot().timeMillis() + 60_000L;
                runtime.advanceTo(due);
                System.out.println("DIGEST=" + BankFundsFixture.digests(runtime));
            }
            return;
        }
        throw new IllegalArgumentException("未知模式：" + mode);
    }

    private static long lastLiveBuy(WorldRuntime runtime) {
        long found = -1L;
        for (WorldExchange.Order order : runtime.snapshot().exchange().orElseThrow().orders()) {
            if (order.live() && order.side() == Side.BUY) {
                found = order.orderId();
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ 断言

    private static void ok(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + name + " :: " + detail);
        } else {
            failed.add(name);
            System.out.println("[FAIL] " + name + " :: " + detail);
        }
    }

    /** 只读检查：某账户现金（供脚本诊断用）。 */
    static BigDecimal cashOf(WorldRuntime runtime, String accountId) {
        CashLedger.CashAccount account = runtime.snapshot().accounts().get(accountId);
        return account == null ? BigDecimal.ZERO : account.cash();
    }
}
