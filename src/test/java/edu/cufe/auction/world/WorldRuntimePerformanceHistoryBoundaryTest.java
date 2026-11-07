package edu.cufe.auction.world;

import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6b 边界：合法正权益的极端下跌在 12 位显示口径下的表示。
 *
 * <p>真实世界入口（真实开盘持仓、真实订单、真实撮合、真实估值）：1e9 股以 1e33 参考价开盘
 * （净资产 1e42），一笔 1 股 0.01 元的真实成交把价格打到 0.01，净资产变为 10,000,000.00。
 * 精确指数约 1e-35 严格为正、精确回撤小于 1，但 12 位输出必然是
 * {@code index = 0.000000000000}、{@code drawdown = 1.000000000000}。</p>
 *
 * <p>这条回归锁定的正是“舍入值”与“权益状态”的区分：期间必须仍是 {@code COMPLETE}，
 * 曲线点必须可构造，旧 {@code calculate} 结果必须与历史结果逐字段相同；
 * 零权益仍是 {@code NON_POSITIVE_EQUITY} 且不输出曲线点（另有测试）。</p>
 */
@DisplayName("WorldRuntime：极端正权益下跌的 12 位表示边界")
class WorldRuntimePerformanceHistoryBoundaryTest {

    private static final String RUN = "performance-history-boundary";
    private static final long SEED = 29L;
    private static final String SYM = "CUFE1";
    private static final String COMPANY_PARTY = "cufe";
    private static final String COMPANY_OWNER = "company";
    private static final String COMPANY_ACC = "CO";
    private static final String INV_PARTY = "investor";
    private static final String ACC_INV = "INV";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";
    private static final String ERIN = "erin";

    private static final long T0 = 1_000L;

    /** 1e42：1e9 股 × 1e33 参考价。 */
    private static final BigDecimal INITIAL_NAV =
            new BigDecimal("1000000000000000000000000000000000000000000.00");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("1e42 → 1e7 的真实价格崩塌：COMPLETE，指数显示 0、回撤显示 1")
    void positiveEquityThatRoundsToZeroIndexRemainsComplete() throws IOException {
        try (WorldRuntime runtime = open("collapse.journal")) {
            runtime.advanceTo(T0);
            long start = runtime.snapshot().position();
            assertEquals(0, runtime.valuation(INV_PARTY).netWorth().compareTo(INITIAL_NAV),
                    "开盘净资产 = 1e9 股 × 1e33");

            // 真实订单与真实撮合：卖方挂在簿上，1 股以 0.01 成交，最后有效价格变为 0.01。
            runtime.scheduleOrder("s-sell", "t-sell", T0 + 10, INV_PARTY, ACC_INV, SYM,
                    Side.SELL, OrderType.LIMIT, new BigDecimal("0.01"), 1L);
            runtime.scheduleOrder("s-buy", "t-buy", T0 + 11, "customer", "CUS", SYM,
                    Side.BUY, OrderType.LIMIT, new BigDecimal("0.01"), 1L);
            runtime.advanceTo(T0 + 11);
            long end = runtime.snapshot().position();
            assertEquals(1, runtime.snapshot().exchange().orElseThrow().trades().size());

            PerformanceModel.History history = runtime.performanceHistory(INV_PARTY, start, end);
            PerformanceModel.Series last = history.series().get(history.series().size() - 1);

            assertEquals(PerformanceModel.Status.COMPLETE, history.performance().status(),
                    "净资产 (999,999,999 × 0.01 + 0.01) = 10,000,000.00 严格为正");
            assertEquals(new BigDecimal("10000000.00"), last.point().navAfterFlow(),
                    "资金规模仍是真实正值，不是 0");
            assertTrue(last.point().navAfterFlow().signum() > 0);
            assertEquals(new BigDecimal("0.000000000000"), last.index(),
                    "精确指数约 1e-35，12 位输出只能是 0.000000000000");
            assertEquals(new BigDecimal("1.000000000000"), last.drawdown(),
                    "精确回撤 1 − 1e-35，12 位输出只能是 1.000000000000");
            assertEquals(new BigDecimal("-1.000000000000"),
                    history.performance().returnRate());
            assertEquals(new BigDecimal("1.000000000000"),
                    history.performance().maxDrawdown());

            assertEquals(history.performance(), runtime.performance(INV_PARTY, start, end),
                    "旧 calculate 与历史接口必须同值（link 公式未改）");
            assertEquals(end - start + 1, history.series().size());
        }
    }

    @Test
    @DisplayName("同一崩塌之前的高精度峰值不变：回撤 1 与指数 0 出自同一遍链接")
    void maxDrawdownMatchesThePointwiseBoundary() throws IOException {
        try (WorldRuntime runtime = open("collapse-pointwise.journal")) {
            runtime.advanceTo(T0);
            long start = runtime.snapshot().position();
            runtime.scheduleOrder("s-sell", "t-sell", T0 + 10, INV_PARTY, ACC_INV, SYM,
                    Side.SELL, OrderType.LIMIT, new BigDecimal("0.01"), 1L);
            runtime.scheduleOrder("s-buy", "t-buy", T0 + 11, "customer", "CUS", SYM,
                    Side.BUY, OrderType.LIMIT, new BigDecimal("0.01"), 1L);
            runtime.advanceTo(T0 + 11);

            PerformanceModel.History history = runtime.performanceHistory(INV_PARTY, start,
                    runtime.snapshot().position());
            BigDecimal worst = BigDecimal.ZERO.setScale(12);
            for (PerformanceModel.Series series : history.series()) {
                if (series.drawdown() != null && series.drawdown().compareTo(worst) > 0) {
                    worst = series.drawdown();
                }
            }
            assertEquals(history.performance().maxDrawdown(), worst,
                    "逐点回撤的最大值必须等于汇总回撤（含 1.000000000000 的上界）");
        }
    }

    // ------------------------------------------------------------------
    // 夹具：真实世界入口所需的最小注册表与档案
    // ------------------------------------------------------------------

    private WorldRuntime open(String name) throws IOException {
        Path file = tempDir.resolve(name);
        return new WorldRuntime(file, manifest(), openings(), registry(), MFG, charter(), roles(),
                information(), decisions());
    }

    private RunManifest manifest() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions")) {
            modules.put(name, 1);
        }
        modules.put("dividend", 1);
        return new RunManifest(RUN, SEED, "dividend-world-experiment-v1", modules,
                Map.of("fixture", "performance-history-boundary-v1"),
                RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private static Map<String, Integer> modules() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions")) {
            modules.put(name, 1);
        }
        return modules;
    }

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening(COMPANY_OWNER, COMPANY_ACC, "SUP", "CUS", "OPS"), SYM);

    private static List<OpeningAccount> openings() {
        return List.of(
                new OpeningAccount(ACC_INV, INV_PARTY, new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount(COMPANY_ACC, COMPANY_OWNER, new BigDecimal("1000.00"),
                        "实验开局历史禀赋"),
                new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "实验开局历史禀赋"),
                new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"),
                        "实验开局历史禀赋"),
                new OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "实验开局历史禀赋"));
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter(COMPANY_PARTY, List.of(
                new BoardModel.Seat("s1", ALICE), new BoardModel.Seat("s2", BOB),
                new BoardModel.Seat("s3", CAROL), new BoardModel.Seat("s4", DAVE),
                new BoardModel.Seat("s5", ERIN)), "s1", BoardModel.QUORUM);
    }

    private static RoleRun.Config roles() {
        return new RoleRun.Config(List.of(), List.of());
    }

    private static InformationRun.Config information() {
        return new InformationRun.Config(List.of(ALICE, BOB, CAROL, DAVE, ERIN));
    }

    private static DecisionModel.Config decisions() {
        DecisionModel.Usage zero = new DecisionModel.Usage(0, 0, 0, new BigDecimal("0.00"));
        Map<String, DecisionModel.Usage> caps = new TreeMap<>();
        for (String person : List.of(ALICE, BOB, CAROL, DAVE, ERIN)) {
            caps.put(person, zero);
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED,
                "scripted:performance-boundary-v1", "scripted-v1", Map.of("wake", 1L), Map.of(),
                new DecisionModel.Limits(2, 20_000, 20_000, 2_000, 2_000, 4, 2, 60_000),
                new DecisionModel.Budget(zero, caps));
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(
                List.of(new OpeningRegistry.Party(COMPANY_PARTY, PartyKind.ORGANIZATION,
                                "中央财经制造公司"),
                        new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                        new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                        new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                        new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                        new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
                        new OpeningRegistry.Party(INV_PARTY, PartyKind.PERSON, "外部投资者")),
                List.of(new OpeningRegistry.PropertyOwner(ALICE, OpeningRegistry.OwnerKind.PERSON,
                                ALICE),
                        new OpeningRegistry.PropertyOwner(BOB, OpeningRegistry.OwnerKind.PERSON,
                                BOB),
                        new OpeningRegistry.PropertyOwner(CAROL, OpeningRegistry.OwnerKind.PERSON,
                                CAROL),
                        new OpeningRegistry.PropertyOwner(DAVE, OpeningRegistry.OwnerKind.PERSON,
                                DAVE),
                        new OpeningRegistry.PropertyOwner(ERIN, OpeningRegistry.OwnerKind.PERSON,
                                ERIN),
                        new OpeningRegistry.PropertyOwner(INV_PARTY,
                                OpeningRegistry.OwnerKind.PERSON, INV_PARTY),
                        new OpeningRegistry.PropertyOwner(COMPANY_OWNER,
                                OpeningRegistry.OwnerKind.ORGANIZATION, COMPANY_PARTY),
                        new OpeningRegistry.PropertyOwner("supplier",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("customer",
                                OpeningRegistry.OwnerKind.AGGREGATE, null),
                        new OpeningRegistry.PropertyOwner("ops", OpeningRegistry.OwnerKind.AGGREGATE,
                                null)),
                List.of(new OpeningRegistry.Security(SYM, COMPANY_PARTY, 1_000_000_000L,
                        new BigDecimal("1000000000000000000000000000000000.00"))),
                List.of(new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 1_000_000_000L,
                        new BigDecimal("1000000000000000000000000000000000.0000"),
                        ShareClass.FLOAT)));
    }
}
