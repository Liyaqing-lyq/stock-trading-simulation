package edu.cufe.auction.world.bankprobe;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankRun;
import edu.cufe.auction.world.bank.BankState;
import edu.cufe.auction.world.bank.Fund;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * B7b World 自测：真实客户托管受益权的同池/跨池/跨行成交、被动价与价格改善、
 * 部分成交与冻结、幂等、负例、三线审计、恢复与逐帧重演。
 *
 * <p>无参跑完整自测；带参数按 {@code open/resume/verify} 做跨 JVM 证据
 * （由 {@code scripts/run-b7b-custody-fill.sh} 串起来）。</p>
 */
public final class BankCustodyWorldSelfTest {

    private static final String SYM = BankCustodyFixture.SYMBOL;
    private static final long T = BankCustodyFixture.T0;
    private static final String CST = BankCustodyFixture.CST;
    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    private BankCustodyWorldSelfTest() {
    }

    /**
     * 入口。
     *
     * @param args 无参跑自测；否则是跨 JVM 模式
     * @throws Exception 运行失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            crossJvm(args);
            return;
        }
        opening();
        samePoolTrade();
        crossBankTrade();
        partialFillFreezeAndCancel();
        rejectedLegKeepsState();
        idempotent();
        negativeRules();
        auditThreeLines();
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

    // ------------------------------------------------------------------ 开局

    private static void opening() throws Exception {
        Path dir = Files.createTempDirectory("b7b-open").resolve("world.log");
        String name = "组合开局：托管明细账随 BankRun 同批落地，pool==Σ明细";
        try (WorldRuntime runtime = BankCustodyFixture.open(dir)) {
            WorldRuntime.Snapshot snap = runtime.snapshot();
            BankRun run = runtime.bankOf().orElseThrow();
            BankRun.Audit audit = runtime.bankAudit().orElseThrow();
            boolean ok = snap.position() == 1L
                    && snap.exchange().isPresent() && snap.securities().isPresent()
                    && run.custodyDetails().get(CST + "|acme") != null
                    && run.custodyDetails().get(CST + "|acme")
                    .compareTo(BankCustodyFixture.money("600.00")) == 0
                    && audit.custodyPools().get(CST)
                    .compareTo(audit.custodyBeneficial().get(CST)) == 0;
            ok(name, ok, "details=" + run.custodyDetails() + " audit=" + audit);
        } catch (Exception invalid) {
            ok(name, false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 同池

    private static void samePoolTrade() throws Exception {
        Path dir = Files.createTempDirectory("b7b-same").resolve("world.log");
        String name = "同池成交（World）：被动价 10.00 而非买方限价 10.20，池存款与准备金不动";
        try (WorldRuntime runtime = BankCustodyFixture.open(dir)) {
            runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash", SYM,
                    Side.SELL, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 100L,
                    BankCustodyFixture.custodyToken(CST, "firm"));
            runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankCustodyFixture.money("10.20"), 30L,
                    BankCustodyFixture.custodyToken(CST, "acme"));
            runtime.advanceTo(T + 120_000L);
            WorldRuntime.Snapshot snap = runtime.snapshot();
            BankRun run = runtime.bankOf().orElseThrow();
            BankRun.Decoded decoded = run.decode(snap.accounts());
            List<WorldExchange.Trade> trades = snap.exchange().orElseThrow().trades();
            boolean ok = trades.size() == 1
                    && trades.get(0).price().compareTo(BankCustodyFixture.money("10.00")) == 0
                    && run.custodyDetails().get(CST + "|acme")
                    .compareTo(BankCustodyFixture.money("300.00")) == 0
                    && run.custodyDetails().get(CST + "|firm")
                    .compareTo(BankCustodyFixture.money("700.00")) == 0
                    && decoded.state().depositOf("north", CST)
                    .compareTo(BankCustodyFixture.money("1000.00")) == 0
                    && snap.accounts().get("rsv-north").cash()
                    .compareTo(BankCustodyFixture.money("1200.00")) == 0;
            ok(name, ok, "trades=" + trades.size() + " details=" + run.custodyDetails()
                    + " pool=" + decoded.state().depositOf("north", CST)
                    + " reserve=" + snap.accounts().get("rsv-north").cash());
        } catch (Exception invalid) {
            ok(name, false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 跨行

    private static void crossBankTrade() throws Exception {
        Path dir = Files.createTempDirectory("b7b-cross").resolve("world.log");
        String name = "跨池跨行成交（World）：north 池 600、south 池 900、准备金 800/400";
        try (WorldRuntime runtime = BankCustodyFixture.open(dir)) {
            runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "co-owner", "co-cash", SYM,
                    Side.SELL, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 100L,
                    BankCustodyFixture.custodyToken(BankCustodyFixture.CST2, "co"));
            runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 40L,
                    BankCustodyFixture.custodyToken(CST, "acme"));
            runtime.advanceTo(T + 120_000L);
            WorldRuntime.Snapshot snap = runtime.snapshot();
            BankRun run = runtime.bankOf().orElseThrow();
            BankState bank = run.decode(snap.accounts()).state();
            boolean ok = bank.depositOf("north", CST)
                    .compareTo(BankCustodyFixture.money("600.00")) == 0
                    && bank.depositOf("south", BankCustodyFixture.CST2)
                    .compareTo(BankCustodyFixture.money("900.00")) == 0
                    && snap.accounts().get("rsv-north").cash()
                    .compareTo(BankCustodyFixture.money("800.00")) == 0
                    && snap.accounts().get("rsv-south").cash()
                    .compareTo(BankCustodyFixture.money("400.00")) == 0;
            ok(name, ok, "northPool=" + bank.depositOf("north", CST)
                    + " southPool=" + bank.depositOf("south", BankCustodyFixture.CST2)
                    + " rsvN=" + snap.accounts().get("rsv-north").cash()
                    + " rsvS=" + snap.accounts().get("rsv-south").cash());
        } catch (Exception invalid) {
            ok(name, false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 部分成交与冻结

    private static void partialFillFreezeAndCancel() throws Exception {
        Path dir = Files.createTempDirectory("b7b-freeze").resolve("world.log");
        String name = "部分成交：剩 40 股冻结 400、第二笔 20 股被拒、撤单后冻结归零";
        try (WorldRuntime runtime = BankCustodyFixture.open(dir)) {
            runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash", SYM,
                    Side.SELL, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 10L,
                    BankCustodyFixture.custodyToken(CST, "firm"));
            runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 50L,
                    BankCustodyFixture.custodyToken(CST, "acme"));
            runtime.advanceTo(T + 120_000L);
            Fund bucket = Fund.parse(BankCustodyFixture.custodyToken(CST, "acme"));
            WorldRuntime.Snapshot snap = runtime.snapshot();
            BigDecimal frozen = WorldExchange.frozenIn(snap.exchange().orElseThrow(), bucket);
            runtime.scheduleOrder("c-buy2", "t-buy2", T + 180_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 20L,
                    BankCustodyFixture.custodyToken(CST, "acme"));
            runtime.advanceTo(T + 180_000L);
            WorldExchange.OrderReport second = runtime.snapshot().exchange().orElseThrow()
                    .reports().get("t-buy2");
            long orderId = 1L;
            for (WorldExchange.Order order : runtime.snapshot().exchange().orElseThrow().orders()) {
                if (order.live() && order.side() == Side.BUY) {
                    orderId = order.orderId();
                }
            }
            runtime.scheduleCancelOrder("c-cancel", "t-cancel", T + 240_000L, "acme-owner",
                    orderId);
            runtime.advanceTo(T + 240_000L);
            BigDecimal after = WorldExchange.frozenIn(
                    runtime.snapshot().exchange().orElseThrow(), bucket);
            boolean ok = frozen.compareTo(BankCustodyFixture.money("400.00")) == 0
                    && second != null && !second.accepted()
                    && second.reason().equals(WorldExchange.REASON_INSUFFICIENT_CASH)
                    && after.signum() == 0;
            ok(name, ok, "frozen=" + frozen + " second="
                    + (second == null ? "null" : second.reason()) + " after=" + after);
        } catch (Exception invalid) {
            ok(name, false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 拒绝不改状态

    private static void rejectedLegKeepsState() throws Exception {
        Path dir = Files.createTempDirectory("b7b-reject").resolve("world.log");
        String name = "跨行准备金不足（World）：整单拒绝，托管/银行/订单逐字不变";
        try (WorldRuntime runtime = BankCustodyFixture.open(dir, "0.00", "1200.00", "0.00")) {
            runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "co-owner", "co-cash", SYM,
                    Side.SELL, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 100L,
                    BankCustodyFixture.custodyToken(BankCustodyFixture.CST2, "co"));
            runtime.advanceTo(T + 60_000L);
            BankRun before = runtime.bankOf().orElseThrow();
            String beforeDetails = new TreeMap<>(before.custodyDetails()).toString();
            runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 40L,
                    BankCustodyFixture.custodyToken(CST, "acme"));
            runtime.advanceTo(T + 120_000L);
            WorldExchange.OrderReport report = runtime.snapshot().exchange().orElseThrow()
                    .reports().get("t-buy");
            BankRun after = runtime.bankOf().orElseThrow();
            boolean ok = report != null && !report.accepted()
                    && report.reason().startsWith(WorldExchange.REASON_RESERVE_INSUFFICIENT)
                    && beforeDetails.equals(new TreeMap<>(after.custodyDetails()).toString())
                    && runtime.snapshot().exchange().orElseThrow().trades().isEmpty();
            ok(name, ok, "reason=" + (report == null ? "null" : report.reason())
                    + " trades=" + runtime.snapshot().exchange().orElseThrow().trades().size());
        } catch (Exception invalid) {
            ok(name, false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 幂等

    private static void idempotent() throws Exception {
        Path dir = Files.createTempDirectory("b7b-idem").resolve("world.log");
        String name = "幂等：同 commandId 同正文返回原回执且不新增批次；不同正文被拒";
        try (WorldRuntime runtime = BankCustodyFixture.open(dir)) {
            String first = runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner",
                    "firm-cash", SYM, Side.SELL, OrderType.LIMIT,
                    BankCustodyFixture.money("10.00"), 40L,
                    BankCustodyFixture.custodyToken(CST, "firm"));
            long position = runtime.snapshot().position();
            String again = runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner",
                    "firm-cash", SYM, Side.SELL, OrderType.LIMIT,
                    BankCustodyFixture.money("10.00"), 40L,
                    BankCustodyFixture.custodyToken(CST, "firm"));
            boolean conflict = false;
            try {
                runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash",
                        SYM, Side.SELL, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 41L,
                        BankCustodyFixture.custodyToken(CST, "firm"));
            } catch (IllegalArgumentException expected) {
                conflict = true;
            }
            boolean ok = first.equals(again) && conflict
                    && runtime.snapshot().position() == position;
            ok(name, ok, "first=" + first + " again=" + again
                    + " position=" + runtime.snapshot().position() + " conflict=" + conflict);
        } catch (Exception invalid) {
            ok(name, false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 负例

    private static void negativeRules() throws Exception {
        Path dir = Files.createTempDirectory("b7b-neg").resolve("world.log");
        String name = "负例：池存款桶/他人托管份额不能下单；B7a 档继续拒绝 CUSTODY";
        try (WorldRuntime runtime = BankCustodyFixture.open(dir)) {
            boolean poolRejected = false;
            try {
                runtime.scheduleOrder("c-pool", "t-pool", T + 60_000L, "acme-owner", "acme-cash",
                        SYM, Side.BUY, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 10L,
                        BankCustodyFixture.token("north", CST));
            } catch (IllegalArgumentException expected) {
                poolRejected = expected.getMessage().contains("托管池存款桶");
            }
            boolean foreignRejected = false;
            try {
                runtime.scheduleOrder("c-foreign", "t-foreign", T + 60_000L, "acme-owner",
                        "acme-cash", SYM, Side.BUY, OrderType.LIMIT,
                        BankCustodyFixture.money("10.00"), 10L,
                        BankCustodyFixture.custodyToken(CST, "firm"));
            } catch (IllegalArgumentException expected) {
                foreignRejected = expected.getMessage().contains("不属于该账户");
            }
            Path b7a = Files.createTempDirectory("b7b-b7a").resolve("world.log");
            boolean b7aRejected;
            String b7aMessage = "";
            try (WorldRuntime old = BankFundsFixture.open(b7a)) {
                b7aRejected = false;
                try {
                    old.scheduleOrder("c-custody", "t-custody", T + 60_000L, "acme-owner",
                            "acme-cash", BankFundsFixture.SYMBOL, Side.BUY, OrderType.LIMIT,
                            BankCustodyFixture.money("10.00"), 10L,
                            BankCustodyFixture.custodyToken(CST, "acme"));
                } catch (IllegalArgumentException expected) {
                    b7aMessage = String.valueOf(expected.getMessage());
                    b7aRejected = b7aMessage.contains("B7b");
                }
            }
            boolean ok = poolRejected && foreignRejected && b7aRejected;
            ok(name, ok, "pool=" + poolRejected + " foreign=" + foreignRejected
                    + " b7a=" + b7aRejected + " b7aMessage=" + b7aMessage);
        } catch (Exception invalid) {
            ok(name, false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 三线审计

    private static void auditThreeLines() throws Exception {
        Path dir = Files.createTempDirectory("b7b-audit").resolve("world.log");
        String name = "三线审计：每池 pool==Σ明细、托管人净值为 0、客户资产只计一份";
        try (WorldRuntime runtime = BankCustodyFixture.open(dir)) {
            runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash", SYM,
                    Side.SELL, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 20L,
                    BankCustodyFixture.custodyToken(CST, "firm"));
            runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 20L,
                    BankCustodyFixture.custodyToken(CST, "acme"));
            runtime.advanceTo(T + 120_000L);
            BankRun.Audit audit = runtime.bankAudit().orElseThrow();
            BankRun.Projection projection = runtime.bankProjection("acme-owner").orElseThrow();
            boolean balanced = true;
            for (String custodian : List.of(CST, BankCustodyFixture.CST2,
                    BankCustodyFixture.CST3)) {
                balanced = balanced && audit.custodyPools().get(custodian)
                        .compareTo(audit.custodyBeneficial().get(custodian)) == 0;
            }
            boolean ok = balanced
                    && projection.beneficial()
                    .compareTo(BankCustodyFixture.money("400.00")) == 0
                    && projection.cash().compareTo(BankCustodyFixture.money("1000.00")) == 0;
            ok(name, ok, "pool=" + audit.custodyPools() + " beneficial="
                    + audit.custodyBeneficial() + " projection=" + projection);
        } catch (Exception invalid) {
            ok(name, false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 恢复

    private static void reopen() throws Exception {
        Path dir = Files.createTempDirectory("b7b-reopen").resolve("world.log");
        String name = "恢复：新进程重开摘要（含托管明细）与上一进程完全一致";
        BankCustodyFixture.Digests before;
        try (WorldRuntime runtime = BankCustodyFixture.open(dir)) {
            runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash", SYM,
                    Side.SELL, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 40L,
                    BankCustodyFixture.custodyToken(CST, "firm"));
            runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash", SYM,
                    Side.BUY, OrderType.LIMIT, BankCustodyFixture.money("10.20"), 30L,
                    BankCustodyFixture.custodyToken(CST, "acme"));
            runtime.advanceTo(T + 120_000L);
            before = BankCustodyFixture.digests(runtime);
        } catch (Exception invalid) {
            ok(name, false, String.valueOf(invalid));
            return;
        }
        try (WorldRuntime runtime = BankCustodyFixture.open(dir)) {
            BankCustodyFixture.Digests after = BankCustodyFixture.digests(runtime);
            ok(name, after.equals(before), "before=" + before + " after=" + after);
        } catch (Exception invalid) {
            ok(name, false, String.valueOf(invalid));
        }
    }

    // ------------------------------------------------------------------ 跨 JVM

    private static void crossJvm(String[] args) throws Exception {
        String mode = args[0];
        Path dir = Path.of(args[1]);
        Files.createDirectories(dir);
        Path file = dir.resolve("world.log");
        if ("open".equals(mode)) {
            try (WorldRuntime runtime = BankCustodyFixture.open(file)) {
                runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash",
                        SYM, Side.SELL, OrderType.LIMIT, BankCustodyFixture.money("10.00"), 10L,
                        BankCustodyFixture.custodyToken(CST, "firm"));
                runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash",
                        SYM, Side.BUY, OrderType.LIMIT, BankCustodyFixture.money("10.20"), 30L,
                        BankCustodyFixture.custodyToken(CST, "acme"));
                runtime.advanceTo(T + 120_000L);
                System.out.println("DIGEST=" + BankCustodyFixture.digests(runtime));
            }
            return;
        }
        if ("resume".equals(mode)) {
            try (WorldRuntime runtime = BankCustodyFixture.open(file)) {
                System.out.println("DIGEST=" + BankCustodyFixture.digests(runtime));
                long orderId = 0L;
                for (WorldExchange.Order order
                        : runtime.snapshot().exchange().orElseThrow().orders()) {
                    if (order.live() && order.side() == Side.BUY) {
                        orderId = order.orderId();
                    }
                }
                runtime.scheduleCancelOrder("c-cancel", "t-cancel", T + 180_000L, "acme-owner",
                        orderId);
                runtime.advanceTo(T + 180_000L);
                System.out.println("AFTER=" + BankCustodyFixture.digests(runtime));
            }
            return;
        }
        if ("verify".equals(mode)) {
            try (WorldRuntime runtime = BankCustodyFixture.open(file)) {
                System.out.println("DIGEST=" + BankCustodyFixture.digests(runtime));
            }
            return;
        }
        throw new IllegalArgumentException("未知模式：" + mode);
    }

    // ------------------------------------------------------------------ 断言

    private static void ok(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed.add(name + " :: " + detail);
            System.out.println("[FAIL] " + name + " :: " + detail);
        }
    }
}
