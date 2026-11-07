package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6b：真实世界入口上的账户规模、表现指数与回撤历史。
 *
 * <p>覆盖正式施工单 §3 验收 1–3 的运行层部分：初始净资产 → 注资 → 真实成交价格变化 → 提取；
 * 真实分红登记/除息应收/到账不重复记收益；同毫秒多确认；只读查询不改日志/时钟/预算；
 * 旧档案拒绝；重开后同值。</p>
 *
 * <p>全部夹具由真实 {@link WorldRuntime} 公开入口驱动（排程 → 到期 → 撮合 → 分红），
 * 期望值按项目口径手算：净资产 = 现金（含冻结不重复加）+ 持仓 × 最后有效成交价 +
 * 分红应收 − 分红应付。</p>
 */
@DisplayName("WorldRuntime：规模/指数/回撤历史（真实入口）")
class WorldRuntimePerformanceHistoryTest {

    private static final String RUN = "performance-history-runtime";
    private static final long SEED = 23L;
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

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 规模、TWR 与资金流
    // ------------------------------------------------------------------

    @Test
    @DisplayName("初始 2000 → 注资 500 → 12 元成交 → 提取 100：规模 2500/2900/2800，TWR 16%")
    void scaleIndexAndDrawdownFollowRealCapitalMovement() throws IOException {
        try (WorldRuntime runtime = open("scale.journal")) {
            runtime.advanceTo(T0);
            long start = runtime.snapshot().position();
            runtime.scheduleCapitalFlow("c-in", "t-in", T0 + 10, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1, "追加资本");
            runtime.advanceTo(T0 + 10);
            trade(runtime, "p-sell", "p-buy", "12.00", 20L, T0 + 20);
            runtime.advanceTo(T0 + 21);
            runtime.scheduleCapitalFlow("c-out", "t-out", T0 + 30, INV_PARTY, ACC_INV, "SUP",
                    new BigDecimal("100.00"), 1, "提取资本");
            runtime.advanceTo(T0 + 30);
            long end = runtime.snapshot().position();

            PerformanceModel.History history = runtime.performanceHistory(INV_PARTY, start, end);

            assertEquals(PerformanceModel.Status.COMPLETE, history.performance().status());
            assertEquals(end - start + 1, history.series().size(),
                    "每个确认帧恰好一个采样点，不重复也不跳过");
            assertEquals(new BigDecimal("2000.00"), history.series().get(0).point().navAfterFlow(),
                    "初始净资产 = 200 股 × 10.00，没有现金");
            assertEquals(new BigDecimal("2800.00"),
                    history.series().get(history.series().size() - 1).point().navAfterFlow(),
                    "640 现金 + 180 股 × 12.00 = 2800");
            assertEquals(new BigDecimal("0.160000000000"), history.performance().returnRate(),
                    "注资与提取被剥离：2900/2000 − 1 = 16%");
            assertEquals(new BigDecimal("1.160000000000"), history.performance().index());
            assertEquals(new BigDecimal("0.000000000000"), history.performance().maxDrawdown(),
                    "单调上行期间没有回撤");

            PerformanceModel.Series contribution = pointWithFlow(history,
                    PerformanceModel.FlowKind.CAPITAL_CONTRIBUTION);
            assertEquals(new BigDecimal("2000.00"), contribution.point().navBeforeFlow());
            assertEquals(new BigDecimal("2500.00"), contribution.point().navAfterFlow(),
                    "注资 500 使规模变化，但不改变指数");
            assertEquals(new BigDecimal("1.000000000000"), contribution.index());

            PerformanceModel.Series withdrawal = pointWithFlow(history,
                    PerformanceModel.FlowKind.CAPITAL_WITHDRAWAL);
            assertEquals(new BigDecimal("2900.00"), withdrawal.point().navBeforeFlow());
            assertEquals(new BigDecimal("2800.00"), withdrawal.point().navAfterFlow());
            assertEquals(new BigDecimal("1.160000000000"), withdrawal.index(),
                    "提取不是亏损");
            assertEquals(history.performance().index(),
                    history.series().get(history.series().size() - 1).index());

            List<WorldRuntime.CapitalFlowReport> flows = runtime.capitalFlows(INV_PARTY);
            assertEquals(2, flows.size());
            assertEquals(0, flows.get(0).amount().compareTo(new BigDecimal("500.00")));
            assertEquals("追加资本", flows.get(0).evidenceRef());
            assertEquals(0, flows.get(1).amount().compareTo(new BigDecimal("100.00")));
        }
    }

    @Test
    @DisplayName("旧 performance(owner,start,end) 在新实现后逐字段不变")
    void legacyPerformanceIsUnchanged() throws IOException {
        try (WorldRuntime runtime = open("legacy-output.journal")) {
            runtime.advanceTo(T0);
            long start = runtime.snapshot().position();
            runtime.scheduleCapitalFlow("c-in", "t-in", T0 + 10, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1, "追加资本");
            runtime.advanceTo(T0 + 10);
            trade(runtime, "p-sell", "p-buy", "12.00", 20L, T0 + 20);
            runtime.advanceTo(T0 + 21);
            long end = runtime.snapshot().position();

            PerformanceModel.Performance legacy = runtime.performance(INV_PARTY, start, end);
            PerformanceModel.History history = runtime.performanceHistory(INV_PARTY, start, end);

            assertEquals(legacy, history.performance(), "同一个区间必须给出同一个 Performance");
            assertEquals(legacy.periodId(), history.performance().periodId(),
                    "periodId 只由 run/owner/start 派生，新增接口不得改变它");
            assertEquals(legacy.segments().size(), history.series().size() - 1,
                    "旧 segments 数必须是点数减一，历史接口不得改变帧采样");
        }
    }

    @Test
    @DisplayName("先跌后涨：逐点当前回撤 10% → 0.5%，汇总仍是 10%")
    void pointwiseDrawdownDiffersFromTheMaximum() throws IOException {
        try (WorldRuntime runtime = open("drawdown.journal")) {
            runtime.advanceTo(T0);
            long start = runtime.snapshot().position();
            trade(runtime, "d-sell", "d-buy", "9.00", 10L, T0 + 10);
            runtime.advanceTo(T0 + 11);
            trade(runtime, "u-sell", "u-buy", "10.00", 10L, T0 + 20);
            runtime.advanceTo(T0 + 21);
            long end = runtime.snapshot().position();

            PerformanceModel.History history = runtime.performanceHistory(INV_PARTY, start, end);

            assertEquals(PerformanceModel.Status.COMPLETE, history.performance().status());
            assertEquals(new BigDecimal("0.100000000000"), history.performance().maxDrawdown(),
                    "200 股从 10 跌到 9 = 10%");
            BigDecimal lowest = history.series().stream()
                    .map(PerformanceModel.Series::drawdown).filter(java.util.Objects::nonNull)
                    .max(BigDecimal::compareTo).orElseThrow();
            assertEquals(new BigDecimal("0.100000000000"), lowest);
            BigDecimal recovered = history.series().get(history.series().size() - 1).drawdown();
            assertNotNull(recovered);
            assertTrue(recovered.compareTo(lowest) < 0,
                    "回升后当前回撤必须小于期间最大回撤，否则不是逐点回撤");
            assertEquals(new BigDecimal("0.005000000000"), recovered,
                    "1990/2000 之后当前回撤 = (1 − 0.995) / 1 = 0.5%");
        }
    }

    @Test
    @DisplayName("同毫秒两笔资本确认：两个不同位置的点，指数仍为 1，规模 2500 → 2700")
    void sameMillisConfirmationsStayDistinctPoints() throws IOException {
        try (WorldRuntime runtime = open("same-millis.journal")) {
            runtime.advanceTo(T0 - 1);
            long start = runtime.snapshot().position();
            runtime.scheduleCapitalFlow("c1", "t1", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1, "第一笔");
            runtime.scheduleCapitalFlow("c2", "t2", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("200.00"), 1, "第二笔");
            runtime.advanceTo(T0);
            long end = runtime.snapshot().position();

            PerformanceModel.History history = runtime.performanceHistory(INV_PARTY, start, end);
            List<PerformanceModel.Series> withFlows = history.series().stream()
                    .filter(series -> !series.point().flows().isEmpty()).toList();
            assertEquals(2, withFlows.size(), "两笔确认各有自己的点，不合并");
            assertEquals(withFlows.get(0).point().atMillis(), withFlows.get(1).point().atMillis(),
                    "两笔确实落在同一模拟毫秒（用于验证横轴按位置而不是按时间）");
            assertTrue(withFlows.get(0).point().position() < withFlows.get(1).point().position());
            assertEquals(new BigDecimal("2500.00"), withFlows.get(0).point().navAfterFlow());
            assertEquals(new BigDecimal("2700.00"), withFlows.get(1).point().navAfterFlow());
            for (PerformanceModel.Series series : history.series()) {
                assertEquals(new BigDecimal("1.000000000000"), series.index(),
                        "两笔注资都不得产生收益");
            }
        }
    }

    // ------------------------------------------------------------------
    // 分红：登记 / 除息应收 / 到账都不重复记收益
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实分红：应收计入但净资产不变，到账后仍不变，全程收益为 0 且无资本流")
    void dividendIsNeverCountedAsReturnOrFlow() throws IOException {
        try (WorldRuntime runtime = open("dividend.journal")) {
            runDividendToSettled(runtime);
            long end = runtime.snapshot().position();
            PerformanceModel.History history = runtime.performanceHistory(INV_PARTY, 1L, end);

            assertEquals(PerformanceModel.Status.COMPLETE, history.performance().status());
            assertEquals(new BigDecimal("0.000000000000"), history.performance().returnRate(),
                    "分红只是资产形态变化");
            assertEquals(new BigDecimal("0.000000000000"), history.performance().maxDrawdown());
            assertTrue(runtime.capitalFlows(INV_PARTY).isEmpty(), "分红不得登记为外部资本流");

            for (PerformanceModel.Series series : history.series()) {
                assertEquals(new BigDecimal("2000.00"), series.point().navAfterFlow(),
                        "除息参考价下降与应收增加相抵，净值始终 2000：" + series.point().position());
            }

            ValuationModel.Value afterPayment = runtime.valuation(INV_PARTY);
            assertEquals(0, afterPayment.dividendReceivable().signum(), "到账后应收清零");
            assertEquals(0, afterPayment.netWorth().compareTo(new BigDecimal("2000.00")),
                    "到账只是应收转现金，不得再加一次");
        }
    }

    @Test
    @DisplayName("除息当日：应收 200 已计入，净资产仍 2000（不提前算收益）")
    void exDateReceivableIsNotYetReturn() throws IOException {
        try (WorldRuntime runtime = open("ex-date.journal")) {
            runDividendToEx(runtime);
            ValuationModel.Value atEx = runtime.valuation(INV_PARTY);
            assertEquals(0, atEx.dividendReceivable().compareTo(new BigDecimal("200.00")),
                    "200 股 × 每股 1.00");
            assertEquals(0, atEx.netWorth().compareTo(new BigDecimal("2000.00")));

            PerformanceModel.History history = runtime.performanceHistory(INV_PARTY, 1L,
                    runtime.snapshot().position());
            assertEquals(new BigDecimal("0.000000000000"), history.performance().returnRate());
        }
    }

    // ------------------------------------------------------------------
    // 只读：日志、时钟、预算都不变
    // ------------------------------------------------------------------

    @Test
    @DisplayName("历史查询只读：快照全字段、决定预算与 journal 字节都不变")
    void historyQueryChangesNothing() throws IOException {
        Path file = tempDir.resolve("readonly.journal");
        long start;
        long end;
        try (WorldRuntime runtime = open(file)) {
            runtime.advanceTo(T0);
            runtime.scheduleCapitalFlow("c-in", "t-in", T0 + 10, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1, "追加资本");
            runtime.advanceTo(T0 + 10);
            runtime.advanceTo(T0 + 20);
            start = 1L;
            end = runtime.snapshot().position();
        }
        // Windows 上运行中的 journal 持有独占锁，不能边写边读；因此在关闭后取字节指纹，
        // 再开一次只做查询，关闭后比对——若查询追加了任何帧，这里必然不同。
        String before = sha256(Files.readAllBytes(file));
        try (WorldRuntime runtime = open(file)) {
            WorldRuntime.Snapshot snapshotBefore = runtime.snapshot();
            long position = snapshotBefore.position();
            int pendingBefore = snapshotBefore.pending().size();
            int deliveredBefore = snapshotBefore.delivered().size();
            String budgetBefore = snapshotBefore.decisions().map(Object::toString).orElse("none");
            int reportsBefore = runtime.dividendReports().size();

            PerformanceModel.History first = runtime.performanceHistory(INV_PARTY, start, end);
            PerformanceModel.History second = runtime.performanceHistory(INV_PARTY, start, end);

            assertEquals(first, second, "同一区间两次查询必须同值，不得依赖查询顺序");
            assertEquals(snapshotBefore, runtime.snapshot(), "快照全字段（含时钟与预算）不得变化");
            assertEquals(position, runtime.snapshot().position());
            assertEquals(pendingBefore, runtime.snapshot().pending().size());
            assertEquals(deliveredBefore, runtime.snapshot().delivered().size());
            assertEquals(budgetBefore,
                    runtime.snapshot().decisions().map(Object::toString).orElse("none"),
                    "查询不得改变调用预算与用量");
            assertEquals(reportsBefore, runtime.dividendReports().size());
            assertEquals(end, position);
        }
        assertEquals(before, sha256(Files.readAllBytes(file)),
                "开档 + 两次历史查询 + 关闭，不得向 journal 追加任何字节");
    }

    @Test
    @DisplayName("重开同一日志：历史逐字段同值，不重复开局也不丢点")
    void reopenProducesTheSameHistory() throws IOException {
        Path file = tempDir.resolve("reopen.journal");
        PerformanceModel.History before;
        long start;
        long end;
        try (WorldRuntime runtime = open(file)) {
            runtime.advanceTo(T0);
            start = runtime.snapshot().position();
            runtime.scheduleCapitalFlow("c-in", "t-in", T0 + 10, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1, "追加资本");
            runtime.advanceTo(T0 + 10);
            trade(runtime, "r-sell", "r-buy", "12.00", 20L, T0 + 20);
            runtime.advanceTo(T0 + 21);
            end = runtime.snapshot().position();
            before = runtime.performanceHistory(INV_PARTY, start, end);
        }
        try (WorldRuntime again = open(file)) {
            assertEquals(end, again.snapshot().position(), "重开不得追加开局帧");
            assertEquals(before, again.performanceHistory(INV_PARTY, start, end));
        }
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未知主体、倒置区间、未确认终点、零/负起点都拒绝")
    void boundariesAreRejected() throws IOException {
        try (WorldRuntime runtime = open("bounds.journal")) {
            runtime.advanceTo(T0);
            long position = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.performanceHistory("nobody", 1L, position));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.performanceHistory(INV_PARTY, position, 1L));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.performanceHistory(INV_PARTY, 1L, position + 1));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.performanceHistory(INV_PARTY, 0L, position));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.performanceHistory(" ", 1L, position));
        }
    }

    @Test
    @DisplayName("旧 cash 档案：历史接口与旧表现接口同样拒绝")
    void legacyProfileRejectsHistory() throws IOException {
        try (WorldRuntime runtime = openLegacy("legacy.journal")) {
            runtime.advanceTo(T0);
            long position = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.performanceHistory(INV_PARTY, 1L, position));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.performance(INV_PARTY, 1L, position));
        }
    }

    @Test
    @DisplayName("组织主体：资本事实保留但曲线为空，不填 0")
    void organizationHasNoCurve() throws IOException {
        try (WorldRuntime runtime = open("org.journal")) {
            runtime.advanceTo(T0);
            long start = runtime.snapshot().position();
            runtime.scheduleCapitalFlow("c1", "t1", T0 + 10, "customer", "CUS", "OTHER",
                    new BigDecimal("500.00"), 1, "其它组织");
            runtime.advanceTo(T0 + 10);
            PerformanceModel.History history = runtime.performanceHistory("other-org", start,
                    runtime.snapshot().position());

            assertNotNull(history.performance().status());
            assertFalse(history.performance().status() == PerformanceModel.Status.COMPLETE);
            assertNull(history.performance().index());
            for (PerformanceModel.Series series : history.series()) {
                assertNull(series.index());
                assertNull(series.drawdown());
            }
            assertEquals(1, runtime.capitalFlows("other-org").size(),
                    "资本事实必须保留，不因估值不支持而丢失");
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static PerformanceModel.Series pointWithFlow(PerformanceModel.History history,
                                                         PerformanceModel.FlowKind kind) {
        return history.series().stream()
                .filter(series -> series.point().flows().stream()
                        .anyMatch(flow -> flow.kind() == kind))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 " + kind + " 采样点"));
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder text = new StringBuilder();
            for (byte value : digest.digest(bytes)) {
                text.append(String.format("%02x", value));
            }
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void trade(WorldRuntime runtime, String sellTask, String buyTask,
                              String price, long quantity, long at) throws IOException {
        runtime.scheduleOrder(sellTask, sellTask, at, INV_PARTY, ACC_INV, SYM,
                edu.cufe.auction.model.Side.SELL, edu.cufe.auction.model.OrderType.LIMIT,
                new BigDecimal(price), quantity);
        runtime.scheduleOrder(buyTask, buyTask, at + 1, "customer", "CUS", SYM,
                edu.cufe.auction.model.Side.BUY, edu.cufe.auction.model.OrderType.LIMIT,
                new BigDecimal(price), quantity);
    }

    private void runDividendToEx(WorldRuntime runtime) throws IOException {
        runtime.scheduleBoardMeeting("c-meeting", "t-meeting", T0, "s1", "m1",
                T0 + 5_000, T0 + 10_000, CLOSES, List.of("s1", "s2", "s3"), List.of(),
                BoardModel.Topic.DIVIDEND_AUTHORIZATION);
        runtime.scheduleDividendProposal("c-proposal", "t-proposal", T0 + 1, "s1", "m1",
                List.of(), new DividendModel.SchemeRequest("S1", SYM, COMPANY_OWNER, COMPANY_ACC,
                        new BigDecimal("1.00"), RECORD, EX, PAY));
        for (String seat : List.of("s1", "s2", "s3")) {
            runtime.scheduleBoardVote("c-vote-" + seat, "t-vote-" + seat, T0 + 10_100, seat,
                    "m1", BoardModel.Vote.YES);
        }
        runtime.advanceTo(CLOSES);
        runtime.scheduleDividendAuthorization("c-auth", "t-auth", AUTH, "s1", "m1");
        runtime.advanceTo(EX);
    }

    private void runDividendToSettled(WorldRuntime runtime) throws IOException {
        runDividendToEx(runtime);
        runtime.advanceTo(PAY);
    }

    private static final long CLOSES = T0 + 20_000;
    private static final long AUTH = CLOSES + 1;
    private static final long RECORD = AUTH + 2;
    private static final long EX = RECORD + 1_000;
    private static final long PAY = EX + 1_000;

    private WorldRuntime open(String name) throws IOException {
        return open(tempDir.resolve(name));
    }

    private WorldRuntime open(Path file) throws IOException {
        return new WorldRuntime(file, manifest(), openings(), registry(), MFG, charter(), roles(),
                information(), decisions());
    }

    private WorldRuntime openLegacy(String name) throws IOException {
        return new WorldRuntime(tempDir.resolve(name), legacyManifest(), openings(), registry(),
                MFG, charter(), roles(), information(), decisions());
    }

    private RunManifest manifest() {
        Map<String, Integer> modules = modules();
        modules.put("dividend", 1);
        return new RunManifest(RUN, SEED, "dividend-world-experiment-v1", modules,
                Map.of("fixture", "performance-history-v1"), RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private RunManifest legacyManifest() {
        return new RunManifest(RUN, SEED, RunManifest.RULES_DECISION_EXPERIMENT_V1, modules(),
                Map.of("fixture", "performance-history-legacy-v1"),
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
                new OpeningAccount("OTHER", "other-org", new BigDecimal("0.00"), "独立组织"),
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
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted:performance-v1",
                "scripted-v1", Map.of("wake", 1L), Map.of(),
                new DecisionModel.Limits(2, 20_000, 20_000, 2_000, 2_000, 4, 2, 60_000),
                new DecisionModel.Budget(zero, caps));
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(
                List.of(new OpeningRegistry.Party(COMPANY_PARTY, PartyKind.ORGANIZATION,
                                "中央财经制造公司"),
                        new OpeningRegistry.Party("other-org", PartyKind.ORGANIZATION, "其它组织"),
                        new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                        new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                        new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                        new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                        new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
                        new OpeningRegistry.Party(INV_PARTY, PartyKind.PERSON, "外部投资者")),
                List.of(new OpeningRegistry.PropertyOwner("other-org",
                                OpeningRegistry.OwnerKind.ORGANIZATION, "other-org"),
                        new OpeningRegistry.PropertyOwner(ALICE, OpeningRegistry.OwnerKind.PERSON,
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
                List.of(new OpeningRegistry.Security(SYM, COMPANY_PARTY, 200L,
                        new BigDecimal("10.00"))),
                List.of(new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT)));
    }
}
