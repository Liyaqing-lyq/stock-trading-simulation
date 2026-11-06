package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3c：真实资本流与按确认历史采样的表现（测试先行）。
 *
 * <p>覆盖正式施工单 `docs/superpowers/plans/2026-11-06-capital-flow-performance.md`：
 * 新资本入口与旧普通转账的分工、真实有限现金腿、到期资格与公司专用账、命令幂等、
 * 逐帧 Point 采样（含资本前后估值）、股利与减值不分类为注资、缺价与不支持主体、
 * 只读查询与不可变、恢复期完整重签伪造的反证。</p>
 *
 * <p>夹具全部由真实 {@link WorldRuntime} 公开入口驱动；NAV 期望值用已验收的
 * D1 纯核 {@link ValuationModel} 从同一快照独立派生，不依赖被测的新资本/表现代码。</p>
 */
@DisplayName("WorldRuntime：资本流与按确认历史表现")
class WorldRuntimeCapitalFlowTest {

    private static final String RUN = "capital-runtime";
    private static final long SEED = 17L;
    private static final String SYM = "CUFE1";
    private static final String COMPANY_PARTY = "cufe";
    private static final String COMPANY_OWNER = "company";
    private static final String COMPANY_ACC = "CO";
    private static final String INV_PARTY = "investor";
    private static final String ACC_INV = "INV";
    private static final String ACC_INV2 = "INV2";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";
    private static final String ERIN = "erin";
    private static final String RULES_DIVIDEND = "dividend-world-experiment-v1";

    private static final long T0 = 1_000L;

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 排程期结构错误（早拒）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未知来源账户在排程期就拒绝")
    void unknownSourceAccountIsRejectedEarly() throws IOException {
        try (WorldRuntime runtime = open("unknown-source.journal")) {
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleCapitalFlow(
                    "c-flow", "t-flow", T0, "customer", "NO-SUCH", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资证据"));
        }
    }

    @Test
    @DisplayName("未知收方账户在排程期就拒绝")
    void unknownTargetAccountIsRejectedEarly() throws IOException {
        try (WorldRuntime runtime = open("unknown-target.journal")) {
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleCapitalFlow(
                    "c-flow", "t-flow", T0, "customer", "CUS", "NO-SUCH",
                    new BigDecimal("500.00"), 1L, "入资证据"));
        }
    }

    @Test
    @DisplayName("已登记发起人无权动用来源账户时到期拒绝")
    void actorMustOwnTheSourceAccount() throws IOException {
        try (WorldRuntime runtime = open("wrong-actor.journal")) {
            runtime.scheduleCapitalFlow("c-flow", "t-flow", T0, INV_PARTY, "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资证据");
            runtime.advanceTo(T0 + 1);
            assertTrue(runtime.capitalFlows(INV_PARTY).isEmpty());
            assertEquals(new BigDecimal("2000.00"), cash(runtime, "CUS"));
            assertFalse(runtime.snapshot().delivered().get(0).accepted());
        }
    }

    @Test
    @DisplayName("金额必须精确两位正数")
    void amountMustBeExactPositiveMoney() throws IOException {
        try (WorldRuntime runtime = open("bad-amount.journal")) {
            for (BigDecimal amount : List.of(new BigDecimal("500.005"), new BigDecimal("0.00"),
                    new BigDecimal("-1.00"))) {
                assertThrows(IllegalArgumentException.class, () -> runtime.scheduleCapitalFlow(
                        "c-flow", "t-flow", T0, "customer", "CUS", ACC_INV, amount, 1L, "入资证据"));
            }
        }
    }

    @Test
    @DisplayName("证据引用不能为空")
    void evidenceIsRequired() throws IOException {
        try (WorldRuntime runtime = open("no-evidence.journal")) {
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleCapitalFlow(
                    "c-flow", "t-flow", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, " "));
        }
    }

    @Test
    @DisplayName("未知owner结构早拒且不写日志")
    void unknownActorIsRejectedBeforeScheduling() throws IOException {
        try (WorldRuntime runtime = open("unknown-owner.journal")) {
            long position = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleCapitalFlow(
                    "unknown", "unknown", T0, "not-an-owner", "CUS", ACC_INV,
                    new BigDecimal("10.00"), 1, "资本证据"));
            assertEquals(position, runtime.snapshot().position());
        }
    }

    @Test
    @DisplayName("买单冻结后的余额不能用于资本转出")
    void reservedCashCannotFundCapitalFlow() throws IOException {
        try (WorldRuntime runtime = open("reserved-cash.journal")) {
            runtime.scheduleCapitalFlow("in", "in", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1, "有限资本");
            runtime.advanceTo(T0 + 1);
            runtime.scheduleOrder("reserve", "reserve", T0 + 2, INV_PARTY, ACC_INV, SYM,
                    edu.cufe.auction.model.Side.BUY, edu.cufe.auction.model.OrderType.LIMIT,
                    new BigDecimal("10.00"), 40);
            runtime.advanceTo(T0 + 2);
            runtime.scheduleCapitalFlow("out", "out", T0 + 3, INV_PARTY, ACC_INV, "SUP",
                    new BigDecimal("200.00"), 1, "冻结余额保护");
            runtime.advanceTo(T0 + 4);
            assertEquals(new BigDecimal("500.00"), cash(runtime, ACC_INV));
            assertEquals(1, runtime.capitalFlows(INV_PARTY).size());
            assertTrue(runtime.snapshot().delivered().stream()
                    .anyMatch(notice -> notice.taskId().equals("out") && !notice.accepted()));
        }
    }

    @Test
    @DisplayName("零本金期间不跨注资拼接，新确认正净值可开始新期间")
    void zeroEquityRequiresANewPositivePeriod() throws IOException {
        try (WorldRuntime runtime = open("zero-capital.journal")) {
            runtime.scheduleCapitalFlow("in", "in", T0, "customer", "CUS", "OPS",
                    new BigDecimal("50.00"), 1, "有限来源");
            runtime.advanceTo(T0 + 1);
            long start = runtime.snapshot().position();
            PerformanceModel.Performance first = runtime.performance("ops", 1, start);
            assertEquals(PerformanceModel.Status.NON_POSITIVE_EQUITY, first.status());
            assertEquals(null, first.returnRate());
            runtime.advanceTo(T0 + 2);
            PerformanceModel.Performance next = runtime.performance("ops", start,
                    runtime.snapshot().position());
            assertEquals(new BigDecimal("0.000000000000"), next.returnRate());
            assertNotEquals(first.periodId(), next.periodId());
        }
    }

    @Test
    @DisplayName("以资本确认帧为起点不重复扣除起点资本")
    void startingAtCapitalConfirmationUsesAfterFlowValue() throws IOException {
        try (WorldRuntime runtime = open("flow-start.journal")) {
            runtime.scheduleCapitalFlow("in", "in", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1, "有限来源");
            runtime.advanceTo(T0);
            long start = runtime.capitalFlows(INV_PARTY).get(0).position();
            runtime.advanceTo(T0 + 2);
            var performance = runtime.performance(INV_PARTY, start, runtime.snapshot().position());
            assertEquals(new BigDecimal("0.000000000000"), performance.returnRate());
            assertEquals(runtime.snapshot().position() - start, performance.segments().size());
        }
    }

    @Test
    @DisplayName("同一 owner 内部转移必须走普通入口，资本入口拒绝")
    void sameOwnerMustUseTheOrdinaryEntry() throws IOException {
        try (WorldRuntime runtime = open("same-owner.journal")) {
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleCapitalFlow(
                    "c-flow", "t-flow", T0, INV_PARTY, ACC_INV, ACC_INV2,
                    new BigDecimal("50.00"), 1L, "内部划转"));
        }
    }

    @Test
    @DisplayName("旧档案拒绝资本入口与查询")
    void legacyProfileRejectsCapitalApis() throws IOException {
        try (WorldRuntime runtime = openLegacy("legacy.journal")) {
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleCapitalFlow(
                    "c-flow", "t-flow", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资证据"));
            assertThrows(IllegalArgumentException.class, () -> runtime.capitalFlows(INV_PARTY));
            assertThrows(IllegalArgumentException.class, () -> runtime.performance(INV_PARTY, 1L, 2L));
        }
    }

    @Test
    @DisplayName("同命令同内容返回原 memo，异内容拒绝")
    void commandMemoIsIdempotent() throws IOException {
        try (WorldRuntime runtime = open("memo.journal")) {
            String first = runtime.scheduleCapitalFlow("c-flow", "t-flow", T0, "customer", "CUS",
                    ACC_INV, new BigDecimal("500.00"), 1L, "入资证据");
            assertEquals(first, runtime.scheduleCapitalFlow("c-flow", "t-flow", T0, "customer",
                    "CUS", ACC_INV, new BigDecimal("500.00"), 1L, "入资证据"));
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleCapitalFlow("c-flow",
                    "t-flow", T0, "customer", "CUS", ACC_INV, new BigDecimal("500.00"), 1L,
                    "换一份证据"));
        }
    }

    // ------------------------------------------------------------------
    // 完成：真实现金腿与分类
    // ------------------------------------------------------------------

    @Test
    @DisplayName("500 入资：真实现金从客户转到投资人，规模增加但收益为 0")
    void contributionMovesRealCashAndIsNotReturn() throws IOException {
        Path file = tempDir.resolve("contribution.journal");
        long afterFlow;
        try (WorldRuntime runtime = open(file)) {
            BigDecimal before = nav(runtime, INV_PARTY);
            runtime.scheduleCapitalFlow("c-flow", "t-flow", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资证据");
            runtime.advanceTo(T0);
            afterFlow = runtime.snapshot().position();

            assertEquals(0, cash(runtime, ACC_INV).compareTo(new BigDecimal("500.00")));
            assertEquals(0, cash(runtime, "CUS").compareTo(new BigDecimal("1500.00")));
            assertEquals(0, nav(runtime, INV_PARTY).compareTo(before.add(new BigDecimal("500.00"))));

            List<WorldRuntime.CapitalFlowReport> flows = runtime.capitalFlows(INV_PARTY);
            assertEquals(1, flows.size());
            WorldRuntime.CapitalFlowReport flow = flows.get(0);
            assertEquals("customer", flow.fromOwnerId());
            assertEquals(INV_PARTY, flow.toOwnerId());
            assertEquals(0, flow.amount().compareTo(new BigDecimal("500.00")));
            assertEquals("入资证据", flow.evidenceRef());
            assertEquals("t-flow", flow.taskId());
            assertEquals("c-flow", flow.commandId());
        }
        try (WorldRuntime reopened = open(file)) {
            assertEquals(0, cash(reopened, ACC_INV).compareTo(new BigDecimal("500.00")),
                    "重开只转移一次");
            assertEquals(1, reopened.capitalFlows(INV_PARTY).size());
        }
    }

    @Test
    @DisplayName("100 提取：真实现金反向转移，不是亏损")
    void withdrawalIsNotLoss() throws IOException {
        try (WorldRuntime runtime = open("withdrawal.journal")) {
            runtime.advanceTo(T0);
            long start = runtime.snapshot().position();
            runtime.scheduleCapitalFlow("c-in", "t-in", T0 + 10, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资");
            runtime.advanceTo(T0 + 10);
            BigDecimal beforeWithdraw = nav(runtime, INV_PARTY);
            runtime.scheduleCapitalFlow("c-out", "t-out", T0 + 20, INV_PARTY, ACC_INV, "SUP",
                    new BigDecimal("100.00"), 1L, "提取");
            runtime.advanceTo(T0 + 20);

            assertEquals(0, cash(runtime, ACC_INV).compareTo(new BigDecimal("400.00")));
            assertEquals(0, cash(runtime, "SUP").compareTo(new BigDecimal("200.00")));
            assertEquals(0, nav(runtime, INV_PARTY).compareTo(
                    beforeWithdraw.subtract(new BigDecimal("100.00"))));
            PerformanceModel.Performance performance = runtime.performance(INV_PARTY, start,
                    runtime.snapshot().position());
            assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
            assertEquals(0, performance.returnRate().compareTo(new BigDecimal("0.000000000000")),
                    "注入与提取都不是收益");
        }
    }

    @Test
    @DisplayName("来源现金不足：到期失败、现金不变、不产生资本事实")
    void insufficientSourceCashFailsAtDue() throws IOException {
        try (WorldRuntime runtime = open("short-source.journal")) {
            runtime.scheduleCapitalFlow("c-flow", "t-flow", T0, "supplier", "SUP", ACC_INV,
                    new BigDecimal("500.00"), 1L, "超额入资");
            runtime.advanceTo(T0 + 1);
            assertEquals(0, cash(runtime, ACC_INV).signum());
            assertEquals(0, cash(runtime, "SUP").compareTo(new BigDecimal("100.00")));
            assertTrue(runtime.capitalFlows(INV_PARTY).isEmpty(), "失败不得留下资本事实");
            assertTrue(runtime.snapshot().delivered().stream().anyMatch(notice ->
                    notice.taskId().equals("t-flow") && !notice.accepted()));
        }
    }

    @Test
    @DisplayName("公司经营账户作为来源在到期被专用账拒绝")
    void companyAccountIsRejectedAtDue() throws IOException {
        try (WorldRuntime runtime = open("company-source.journal")) {
            runtime.scheduleCapitalFlow("c-flow", "t-flow", T0, COMPANY_OWNER, COMPANY_ACC, ACC_INV,
                    new BigDecimal("100.00"), 1L, "公司出款");
            runtime.advanceTo(T0 + 1);
            assertEquals(0, cash(runtime, COMPANY_ACC).compareTo(new BigDecimal("1000.00")));
            assertTrue(runtime.capitalFlows(INV_PARTY).isEmpty());
            assertTrue(runtime.snapshot().delivered().stream().anyMatch(notice ->
                    notice.taskId().equals("t-flow") && !notice.accepted()));
        }
    }

    @Test
    @DisplayName("新档案的普通跨 owner 转账仍是无分类拒绝")
    void ordinaryCrossOwnerTransferStaysUnclassified() throws IOException {
        try (WorldRuntime runtime = open("cross-owner.journal")) {
            runtime.scheduleTransfer("c-tr", "t-tr", T0, "supplier", "SUP", ACC_INV,
                    new BigDecimal("50.00"), 1L);
            runtime.advanceTo(T0 + 1);
            assertEquals(0, cash(runtime, ACC_INV).signum());
            assertTrue(runtime.snapshot().delivered().stream().anyMatch(notice ->
                    notice.taskId().equals("t-tr") && !notice.accepted()
                            && "TRANSFER_UNCLASSIFIED".equals(notice.reason())));
            assertTrue(runtime.capitalFlows(INV_PARTY).isEmpty());
        }
    }

    @Test
    @DisplayName("同 owner 内部普通转移成功且不产生资本事实")
    void sameOwnerInternalTransferCreatesNoFlow() throws IOException {
        try (WorldRuntime runtime = open("internal.journal")) {
            runtime.advanceTo(T0);
            runtime.scheduleCapitalFlow("c-in", "t-in", T0 + 10, "customer", "CUS", ACC_INV,
                    new BigDecimal("300.00"), 1L, "入资");
            runtime.advanceTo(T0 + 10);
            runtime.scheduleTransfer("c-tr", "t-tr", T0 + 20, INV_PARTY, ACC_INV, ACC_INV2,
                    new BigDecimal("100.00"), 1L);
            runtime.advanceTo(T0 + 20);
            assertEquals(0, cash(runtime, ACC_INV2).compareTo(new BigDecimal("100.00")));
            assertEquals(1, runtime.capitalFlows(INV_PARTY).size(), "内部划转不是资本流");
        }
    }

    // ------------------------------------------------------------------
    // 查询：只读、过滤、不可变
    // ------------------------------------------------------------------

    @Test
    @DisplayName("资本查询不可变、按两侧 owner 过滤且按确认顺序")
    void capitalFlowsAreImmutableAndFiltered() throws IOException {
        try (WorldRuntime runtime = open("query.journal")) {
            runtime.scheduleCapitalFlow("c1", "t1", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资");
            runtime.scheduleCapitalFlow("c2", "t2", T0 + 10, INV_PARTY, ACC_INV, "SUP",
                    new BigDecimal("100.00"), 1L, "提取");
            runtime.advanceTo(T0 + 10);

            List<WorldRuntime.CapitalFlowReport> flows = runtime.capitalFlows(INV_PARTY);
            assertEquals(2, flows.size());
            assertTrue(flows.get(0).position() < flows.get(1).position());
            assertThrows(UnsupportedOperationException.class, flows::clear);
            assertEquals(1, runtime.capitalFlows("customer").size(), "来源侧也能查到同一事实");
            assertEquals(1, runtime.capitalFlows("supplier").size(), "收方侧能查到提取事实");
            assertThrows(IllegalArgumentException.class, () -> runtime.capitalFlows("nobody"));
        }
    }

    @Test
    @DisplayName("表现查询只读：不推进位置、不产生事件")
    void performanceQueryIsReadOnly() throws IOException {
        try (WorldRuntime runtime = open("readonly.journal")) {
            runtime.scheduleCapitalFlow("c1", "t1", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资");
            runtime.advanceTo(T0);
            long position = runtime.snapshot().position();
            int reports = runtime.dividendReports().size();
            runtime.performance(INV_PARTY, 1L, position);
            assertEquals(position, runtime.snapshot().position());
            assertEquals(reports, runtime.dividendReports().size());
        }
    }

    @Test
    @DisplayName("表现查询边界：未知 owner、倒置区间、未确认起点都拒绝")
    void performanceBoundariesAreRejected() throws IOException {
        try (WorldRuntime runtime = open("bounds.journal")) {
            runtime.advanceTo(T0);
            long position = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.performance("nobody", 1L, position));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.performance(INV_PARTY, position, 1L));
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.performance(INV_PARTY, 1L, position + 99));
        }
    }

    @Test
    @DisplayName("纯资本流期间收益为 0，且每帧都形成采样点")
    void flowOnlyPeriodHasZeroReturn() throws IOException {
        try (WorldRuntime runtime = open("flow-only.journal")) {
            runtime.advanceTo(T0);
            long start = runtime.snapshot().position();
            runtime.scheduleCapitalFlow("c1", "t1", T0 + 10, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资");
            runtime.advanceTo(T0 + 10);
            long end = runtime.snapshot().position();
            PerformanceModel.Performance performance = runtime.performance(INV_PARTY, start, end);
            assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
            assertEquals(0, performance.returnRate().compareTo(new BigDecimal("0.000000000000")));
            assertEquals(end - start, performance.segments().size(), "区间内每个确认帧都是采样点");
        }
    }

    // ------------------------------------------------------------------
    // 价格、分红与减值都不算注资
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实成交带来的价格变化进入收益，资本流被剥离")
    void priceChangeIsReturnButCapitalFlowIsNot() throws IOException {
        try (WorldRuntime runtime = open("price.journal")) {
            runtime.advanceTo(T0);
            long start = runtime.snapshot().position();
            trade(runtime, "t-sell", "t-buy", "12.00", 20L, T0 + 10);
            runtime.advanceTo(T0 + 11);
            BigDecimal afterTrade = nav(runtime, INV_PARTY);
            runtime.scheduleCapitalFlow("c1", "t1", T0 + 20, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资");
            runtime.advanceTo(T0 + 21);
            long end = runtime.snapshot().position();

            PerformanceModel.Performance performance = runtime.performance(INV_PARTY, start, end);
            assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
            assertEquals(new BigDecimal("0.200000000000"), performance.returnRate(),
                    "实际200股的10元参考被真实12元成交更新，资本流剥离");
            assertEquals(0, capitalFlows(runtime, INV_PARTY).get(0).amount()
                    .compareTo(new BigDecimal("500.00")));
            assertNotNull(afterTrade);
        }
    }

    @Test
    @DisplayName("分红登记、除息与到账都不是资本流")
    void dividendStagesAreNeverCapitalFlows() throws IOException {
        try (WorldRuntime runtime = open("dividend.journal")) {
            runDividendToSettled(runtime);
            assertTrue(runtime.capitalFlows(INV_PARTY).isEmpty(),
                    "红利与应收都不分类为注资");
            long end = runtime.snapshot().position();
            PerformanceModel.Performance performance = runtime.performance(INV_PARTY, 1L, end);
            assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
            assertEquals(0, performance.returnRate().compareTo(new BigDecimal("0.000000000000")),
                    "分红只是资产形态变化");
        }
    }

    @Test
    @DisplayName("先跌后涨的逐帧采样保留回撤，起止两点会丢失")
    void perFrameSamplingKeepsTheDrawdown() throws IOException {
        try (WorldRuntime runtime = open("drawdown.journal")) {
            runtime.advanceTo(T0);
            long start = runtime.snapshot().position();
            trade(runtime, "d-sell", "d-buy", "9.00", 10L, T0 + 10);
            runtime.advanceTo(T0 + 10);
            trade(runtime, "u-sell", "u-buy", "10.00", 10L, T0 + 20);
            runtime.advanceTo(T0 + 21);
            long end = runtime.snapshot().position();

            PerformanceModel.Performance performance = runtime.performance(INV_PARTY, start, end);
            assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
            assertEquals(new BigDecimal("0.100000000000"), performance.maxDrawdown(), "实际200股从10跌至9");
            assertTrue(performance.segments().size() >= 2);
        }
    }

    @Test
    @DisplayName("持有人减值后足额付款：回撤按逐帧采样保留")
    void impairmentBeforePaymentKeepsDrawdown() throws IOException {
        try (WorldRuntime runtime = open("impair.journal")) {
            runDividendToEx(runtime);
            String claimId = DividendModel.claims(dividends(runtime), "S1").get(0).claimId();
            runtime.scheduleDividendImpairment("c-imp", "t-imp", EX + 10, INV_PARTY, claimId,
                    new BigDecimal("50.00"), "评估证据");
            runtime.advanceTo(EX + 10);
            long start = 1L;
            runtime.advanceTo(PAY);
            long end = runtime.snapshot().position();
            PerformanceModel.Performance performance = runtime.performance(INV_PARTY, start, end);
            assertEquals(PerformanceModel.Status.COMPLETE, performance.status());
            assertEquals(0, performance.returnRate().compareTo(new BigDecimal("0.000000000000")),
                    "减值与足额付款都不是资本流，期末回到原值");
            assertEquals(new BigDecimal("0.025000000000"), performance.maxDrawdown(),
                    "2000净值减值50后恢复仍保留回撤");
        }
    }

    @Test
    @DisplayName("组织主体的表现边界：保留资本事实但估值不支持")
    void organizationBoundaryKeepsTheFlowButNotNAV() throws IOException {
        try (WorldRuntime runtime = open("org-boundary.journal")) {
            runtime.advanceTo(T0);
            long start = runtime.snapshot().position();
            runtime.scheduleCapitalFlow("c1", "t1", T0 + 10, "customer", "CUS", "OTHER",
                    new BigDecimal("500.00"), 1L, "其它组织资本");
            runtime.advanceTo(T0 + 10);
            PerformanceModel.Performance performance = runtime.performance("other-org", start,
                    runtime.snapshot().position());
            assertNotEquals(PerformanceModel.Status.COMPLETE, performance.status(),
                    "组织主体没有完整 NAV，不得报精确收益");
            assertEquals(1, runtime.capitalFlows("other-org").size(),
                    "资本事实必须保留，不因估值不支持而丢失");
        }
    }

    // ------------------------------------------------------------------
    // 恢复：完整重签反例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重开日志只转移一次：现金与资本事实都不重复")
    void reopenTransfersOnlyOnce() throws IOException {
        Path file = tempDir.resolve("reopen.journal");
        try (WorldRuntime runtime = open(file)) {
            runtime.scheduleCapitalFlow("c1", "t1", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资");
            runtime.advanceTo(T0);
        }
        try (WorldRuntime again = open(file)) {
            assertEquals(0, cash(again, ACC_INV).compareTo(new BigDecimal("500.00")));
            assertEquals(0, cash(again, "CUS").compareTo(new BigDecimal("1500.00")));
            assertEquals(1, again.capitalFlows(INV_PARTY).size());
        }
    }

    @Test
    @DisplayName("伪造资本金额：重签帧后恢复拒绝")
    void forgedCapitalAmountIsRejectedOnRestore() throws IOException {
        rejectCapitalBatch("forged-amount.journal", events -> {
            for (int i = 0; i < events.size(); i++) {
                WorldEvent event = events.get(i);
                if (!event.type().equals("CAPITAL_FLOW_V1")) continue;
                ObjectMapper json = new ObjectMapper();
                ObjectNode flow = (ObjectNode) json.readTree(event.payload());
                flow.put("amount", new BigDecimal("999.00"));
                events.set(i, new WorldEvent(event.eventId(), event.type(), json.writeValueAsString(flow)));
                return;
            }
            throw new AssertionError("真实资本事实不存在");
        });
    }

    @Test
    @DisplayName("删除资本事实但保留现金腿：恢复拒绝")
    void cashLegWithoutCapitalFactIsRejected() throws IOException {
        rejectCapitalBatch("dropped-fact.journal", events ->
                assertTrue(events.removeIf(event -> "CAPITAL_FLOW_V1".equals(event.type()))));
    }

    @Test
    @DisplayName("重复资本事实：恢复拒绝")
    void duplicateCapitalFactIsRejected() throws IOException {
        rejectCapitalBatch("duplicate-fact.journal", events -> {
            WorldEvent flow = events.stream()
                    .filter(event -> "CAPITAL_FLOW_V1".equals(event.type()))
                    .findFirst().orElseThrow();
            events.add(new WorldEvent("duplicate-capital-flow", flow.type(), flow.payload()));
        });
    }

    @Test
    @DisplayName("资本任务 payload 出现未知字段：恢复拒绝")
    void capitalTaskPayloadRejectsUnknownFields() throws IOException {
        rejectCapitalBatch("unknown-field.journal", events -> {
            ObjectMapper json = new ObjectMapper();
            for (int index = 0; index < events.size(); index++) {
                WorldEvent event = events.get(index);
                if (!"TASK_COMPLETED_V1".equals(event.type())) continue;
                ObjectNode payload = (ObjectNode) json.readTree(event.payload());
                payload.put("unknownCapitalField", true);
                events.set(index, new WorldEvent(event.eventId(), event.type(), json.writeValueAsString(payload)));
                return;
            }
            throw new AssertionError("真实资本完成事件不存在");
        });
    }

    @Test
    @DisplayName("失败批次携带资本事实：恢复拒绝")
    void failedBatchWithCapitalFactIsRejected() throws IOException {
        Path source = tempDir.resolve("failed-source.journal");
        try (WorldRuntime runtime = open(source)) {
            runtime.scheduleCapitalFlow("c1", "t1", T0, "supplier", "SUP", ACC_INV,
                    new BigDecimal("500.00"), 1L, "超额入资");
            runtime.advanceTo(T0);
            assertTrue(runtime.capitalFlows(INV_PARTY).isEmpty());
        }
        Path forged = rewriteCommits(source, "failed-forged.journal", false, events ->
                events.add(new WorldEvent("forged-capital-flow", "CAPITAL_FLOW_V1",
                        "{\"flowId\":\"forged\",\"taskId\":\"t1\",\"commandId\":\"c1\","
                                + "\"position\":3,\"atMillis\":1000,\"actorOwnerId\":\"supplier\","
                                + "\"fromAccountId\":\"SUP\",\"toAccountId\":\"INV\","
                                + "\"fromOwnerId\":\"supplier\",\"toOwnerId\":\"investor\","
                                + "\"amount\":500.00,\"evidenceRef\":\"超额入资\"}")));
        assertThrows(IOException.class, () -> open(forged).close());
    }

    @Test
    @DisplayName("旧档案不得携带资本事实")
    void legacyProfileCannotCarryCapitalFact() throws IOException {
        Path source = tempDir.resolve("legacy-source.journal");
        try (WorldRuntime runtime = openLegacy("legacy-source.journal")) {
            runtime.advanceTo(T0);
        }
        Path forged = rewriteCommits(source, "legacy-forged.journal", false, events ->
                events.add(new WorldEvent("forged-legacy-capital", "CAPITAL_FLOW_V1",
                        "{\"flowId\":\"forged\"}")));
        assertThrows(IOException.class, () -> openLegacy(forged).close());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    @Test
    void completedCapitalPhaseMustMatchOriginalTask() throws IOException {
        rejectCapitalBatch("wrong-phase.journal", events -> changeSnapshot(events,
                root -> root.put("lastPhase", "RULES")));
    }

    @Test
    void capitalCompletionCannotRewriteCommandMemo() throws IOException {
        rejectCapitalBatch("wrong-memo.journal", events -> changeSnapshot(events,
                root -> ((ObjectNode) root.path("commandOutcomes")).put("c1", "CHANGED")));
    }

    @Test
    void capitalCompletionCannotEraseDeliveredNotices() throws IOException {
        Path source = tempDir.resolve("delivered-prefix.journal");
        try (WorldRuntime runtime = open(source)) {
            runtime.scheduleCapitalFlow("prior", "prior", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("10.00"), 1, "原回报");
            runtime.advanceTo(T0 + 1);
            runtime.scheduleCapitalFlow("next", "next", T0 + 2, "customer", "CUS", ACC_INV,
                    new BigDecimal("20.00"), 1, "下一笔");
            runtime.advanceTo(T0 + 3);
        }
        rejectSelected(source, "erased-notice.journal", "task:next", events ->
                changeSnapshot(events, root -> root.set("delivered", new ObjectMapper().createArrayNode())));
    }

    @Test
    void actualNoticeCannotDisagreeWithConfirmedPayment() throws IOException {
        Path source = noticeSource("notice-original.journal");
        rejectSelected(source, "wrong-notice.journal", "notice", events -> {
            changeSnapshot(events, root -> ((ObjectNode) root.withArray("delivered").get(0)).put("accepted", false));
            for (int i = 0; i < events.size(); i++) {
                WorldEvent event = events.get(i);
                if (!event.type().equals("NOTICE_DELIVERED_V1")) continue;
                ObjectMapper json = new ObjectMapper();
                ObjectNode value = (ObjectNode) json.readTree(event.payload());
                value.put("accepted", false);
                events.set(i, new WorldEvent(event.eventId(), event.type(), json.writeValueAsString(value)));
            }
        });
    }

    @Test
    void ordinaryClockCannotRewritePendingCapitalNotice() throws IOException {
        Path source = tempDir.resolve("future-notice-source.journal");
        try (WorldRuntime runtime = open(source)) {
            runtime.scheduleCapitalFlow("c1", "t1", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 100, "资本证据");
            runtime.advanceTo(T0 + 50);
        }
        rejectSelected(source, "future-notice-forged.journal", "clock:", events -> changeSnapshot(events, root -> {
            for (var node : root.withArray("pending")) {
                if (node.path("kind").asText().equals("TRANSFER_NOTICE_V1")) {
                    ((ObjectNode) node).put("dueTimeMillis", T0 + 101);
                    return;
                }
            }
            throw new AssertionError("真实待投递回报不存在");
        }));
    }

    @Test
    void capitalScheduleCannotMoveCashBeforeDue() throws IOException {
        Path source = noticeSource("scheduled-cash-source.journal");
        rejectSelected(source, "scheduled-cash-forged.journal", "cmd:c1", events -> changeSnapshot(events, root -> {
            ((ObjectNode) root.path("accounts").path("INV")).put("cash", new BigDecimal("1.00"));
            ((ObjectNode) root.path("accounts").path("CUS")).put("cash", new BigDecimal("1999.00"));
        }));
    }

    @Test
    void capitalScheduleRejectsUnknownFieldsInAllSources() throws IOException {
        Path source = noticeSource("schedule-unknown-source.journal");
        rejectSelected(source, "schedule-unknown-forged.journal", "cmd:c1", events -> {
            ObjectMapper json = new ObjectMapper();
            changeSnapshot(events, root -> {
                ObjectNode task = (ObjectNode) root.withArray("pending").get(0);
                try {
                    ObjectNode payload = (ObjectNode) json.readTree(task.path("payload").asText());
                    payload.put("unrecognized", true);
                    task.put("payload", json.writeValueAsString(payload));
                } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            for (int i = 0; i < events.size(); i++) {
                WorldEvent event = events.get(i);
                if (!event.type().equals("TASK_SCHEDULED_V1")) continue;
                ObjectNode task = (ObjectNode) json.readTree(event.payload());
                ObjectNode payload = (ObjectNode) json.readTree(task.path("payload").asText());
                payload.put("unrecognized", true);
                task.put("payload", json.writeValueAsString(payload));
                events.set(i, new WorldEvent(event.eventId(), event.type(), json.writeValueAsString(task)));
            }
        });
    }

    @Test
    void corruptedNoticeTimeOverflowIsAnIoFailure() throws IOException {
        Path source = noticeSource("overflow-source.journal");
        rejectSelected(source, "overflow-forged.journal", "cmd:c1", events -> {
            ObjectMapper json = new ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            json.setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
            java.util.function.Consumer<ObjectNode> overflow = task -> {
                try {
                    ObjectNode payload = (ObjectNode) json.readTree(task.path("payload").asText());
                    task.put("dueTimeMillis", Long.MAX_VALUE - 5);
                    payload.put("receiptDelayMillis", 10L);
                    payload.put("noticeDueMillis", Long.MAX_VALUE);
                    task.put("payload", json.writeValueAsString(payload));
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            };
            changeSnapshot(events, root -> {
                for (var task : root.withArray("pending")) {
                    if (task.path("kind").asText().equals("CASH_TRANSFER_V1")) {
                        overflow.accept((ObjectNode) task);
                    }
                }
            });
            for (int index = 0; index < events.size(); index++) {
                WorldEvent event = events.get(index);
                if (event.type().equals("TASK_SCHEDULED_V1")) {
                    ObjectNode task = (ObjectNode) json.readTree(event.payload());
                    if (task.path("kind").asText().equals("CASH_TRANSFER_V1")) {
                        overflow.accept(task);
                        events.set(index, new WorldEvent(event.eventId(), event.type(),
                                json.writeValueAsString(task)));
                    }
                }
            }
        });
    }

    private Path noticeSource(String name) throws IOException {
        Path source = tempDir.resolve(name);
        try (WorldRuntime runtime = open(source)) {
            runtime.scheduleCapitalFlow("c1", "t1", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1, "资本证据");
            runtime.advanceTo(T0 + 1);
        }
        return source;
    }

    private void rejectSelected(Path source, String name, String command, EventMutation mutation) throws IOException {
        try (WorldRuntime legal = open(source)) {
            assertTrue(legal.snapshot().position() > 1, "实际合法控制");
        }
        Path target = tempDir.resolve(name);
        boolean applied = false;
        try (WorldJournal original = new WorldJournal(source, RUN); WorldJournal result = new WorldJournal(target, RUN)) {
            for (WorldCommit c : original.commits()) {
                List<WorldEvent> events = new ArrayList<>(c.events());
                boolean selected = command.equals("notice")
                        ? events.stream().anyMatch(e -> e.type().equals("NOTICE_DELIVERED_V1"))
                        : c.commandId().startsWith(command);
                if (!applied && selected) { mutation.change(events); applied = true; }
                result.commit(c.commandId(), c.commandContent(), c.result(), events);
                if (applied) break;
            }
        }
        assertTrue(applied, "实际目标批存在");
        assertThrows(IOException.class, () -> open(target).close());
    }

    private static void changeSnapshot(List<WorldEvent> events,
                                       java.util.function.Consumer<ObjectNode> mutation) throws IOException {
        ObjectMapper json = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        json.setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
        for (int i = 0; i < events.size(); i++) {
            WorldEvent event = events.get(i);
            if (!event.type().equals("STATE_SNAPSHOT_V1")) continue;
            ObjectNode root = (ObjectNode) json.readTree(event.payload());
            mutation.accept(root);
            events.set(i, new WorldEvent(event.eventId(), event.type(), json.writeValueAsString(root)));
            return;
        }
        throw new AssertionError("原快照不存在");
    }

    private WorldRuntime open(String name) throws IOException {
        return open(tempDir.resolve(name), registry());
    }

    private WorldRuntime open(Path file) throws IOException {
        return open(file, registry());
    }

    private WorldRuntime open(Path file, OpeningRegistry registry) throws IOException {
        return new WorldRuntime(file, manifest(), openings(), registry, MFG, charter(), roles(),
                information(), decisions());
    }

    private WorldRuntime openLegacy(String name) throws IOException {
        return openLegacy(tempDir.resolve(name));
    }

    private WorldRuntime openLegacy(Path file) throws IOException {
        return new WorldRuntime(file, legacyManifest(), openings(), registry(),
                MFG, charter(), roles(), information(), decisions());
    }

    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }

    private static List<WorldRuntime.CapitalFlowReport> capitalFlows(WorldRuntime runtime,
                                                                    String ownerId) {
        return runtime.capitalFlows(ownerId);
    }

    /** 用已验收的 D1 纯核从同一快照独立派生 NAV。 */
    private static BigDecimal nav(WorldRuntime runtime, String ownerId) {
        WorldRuntime.Snapshot snapshot = runtime.snapshot();
        ValuationModel.PriceBook book = ValuationModel.prices(snapshot.position(),
                snapshot.timeMillis(), snapshot.exchange().orElse(null), registry(),
                List.of(SYM), snapshot.dividends().orElseThrow());
        ValuationModel.Value value = ValuationModel.value(ownerId, snapshot.position(),
                snapshot.timeMillis(), registry(), snapshot.accounts(),
                snapshot.securities().orElseThrow(), snapshot.dividends().orElseThrow(), book);
        return value.netWorth();
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

    private static void runDividendToEx(WorldRuntime runtime) throws IOException {
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

    private static void runDividendToSettled(WorldRuntime runtime) throws IOException {
        runDividendToEx(runtime);
        runtime.advanceTo(PAY);
    }

    private static DividendModel.State dividends(WorldRuntime runtime) {
        return runtime.snapshot().dividends().orElseThrow();
    }

    private static final long CLOSES = T0 + 20_000;
    private static final long AUTH = CLOSES + 1;
    private static final long RECORD = AUTH + 2;
    private static final long EX = RECORD + 1_000;
    private static final long PAY = EX + 1_000;

    private RunManifest manifest() {
        Map<String, Integer> modules = modules();
        modules.put("dividend", 1);
        return new RunManifest(RUN, SEED, RULES_DIVIDEND, modules,
                Map.of("fixture", "capital-runtime-v1"), RunManifest.SCRIPTED_DECISIONS_V1);
    }

    private RunManifest legacyManifest() {
        return new RunManifest(RUN, SEED, RunManifest.RULES_DECISION_EXPERIMENT_V1, modules(),
                Map.of("fixture", "decision-runtime-v1"), RunManifest.SCRIPTED_DECISIONS_V1);
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
                new OpeningAccount(ACC_INV2, INV_PARTY, new BigDecimal("0.00"), "实验开局历史禀赋"),
                new OpeningAccount("OTHER", "other-org", new BigDecimal("0.00"), "独立组织"),
                new OpeningAccount(COMPANY_ACC, COMPANY_OWNER, new BigDecimal("1000.00"),
                        "实验开局历史禀赋"),
                new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "实验开局历史禀赋"),
                new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "实验开局历史禀赋"),
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
        return new DecisionModel.Config(DecisionModel.PROVIDER_SCRIPTED, "scripted:capital-v1",
                "scripted-v1", Map.of("wake", 1L), Map.of(),
                new DecisionModel.Limits(2, 20_000, 20_000, 2_000, 2_000, 4, 2, 60_000),
                new DecisionModel.Budget(zero, caps));
    }

    private static OpeningRegistry registry() {
        return registry(List.of(new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                new BigDecimal("10.0000"), ShareClass.FLOAT)));
    }

    private static OpeningRegistry registry(List<OpeningRegistry.ShareAllocation> allocations) {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party(COMPANY_PARTY, PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party("other-org", PartyKind.ORGANIZATION, "其它组织"),
                new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INV_PARTY, PartyKind.PERSON, "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner("other-org", OpeningRegistry.OwnerKind.ORGANIZATION, "other-org"),
                new OpeningRegistry.PropertyOwner(ALICE, OpeningRegistry.OwnerKind.PERSON, ALICE),
                new OpeningRegistry.PropertyOwner(BOB, OpeningRegistry.OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CAROL, OpeningRegistry.OwnerKind.PERSON, CAROL),
                new OpeningRegistry.PropertyOwner(DAVE, OpeningRegistry.OwnerKind.PERSON, DAVE),
                new OpeningRegistry.PropertyOwner(ERIN, OpeningRegistry.OwnerKind.PERSON, ERIN),
                new OpeningRegistry.PropertyOwner(INV_PARTY, OpeningRegistry.OwnerKind.PERSON,
                        INV_PARTY),
                new OpeningRegistry.PropertyOwner(COMPANY_OWNER,
                        OpeningRegistry.OwnerKind.ORGANIZATION, COMPANY_PARTY),
                new OpeningRegistry.PropertyOwner("supplier", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("customer", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("ops", OpeningRegistry.OwnerKind.AGGREGATE, null));
        return new OpeningRegistry(parties, owners,
                List.of(new OpeningRegistry.Security(SYM, COMPANY_PARTY, 200L,
                        new BigDecimal("10.00"))),
                allocations);
    }

    // ------------------------------------------------------------------
    // 恢复反例工具：完整重签帧
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface EventMutation {
        void change(List<WorldEvent> events) throws IOException;
    }

    /** 合法控制先通过，再把该资本批重签为反例，恢复必须拒绝。 */
    private void rejectCapitalBatch(String name, EventMutation mutation) throws IOException {
        Path source = tempDir.resolve("capital-source.journal");
        try (WorldRuntime runtime = open(source)) {
            runtime.scheduleCapitalFlow("c1", "t1", T0, "customer", "CUS", ACC_INV,
                    new BigDecimal("500.00"), 1L, "入资");
            runtime.advanceTo(T0);
        }
        try (WorldRuntime legal = open(source)) {
            assertEquals(1, legal.capitalFlows(INV_PARTY).size(), "合法控制必须先通过");
            assertEquals(0, cash(legal, ACC_INV).compareTo(new BigDecimal("500.00")));
        }
        Path forged = rewriteCommits(source, name, true, mutation);
        assertThrows(IOException.class, () -> open(forged).close());
    }

    /** 只对含资本事实的批次（或第一批）应用变异，其余帧原样重签。 */
    private Path rewriteCommits(Path source, String name, boolean onlyCapitalBatch,
                                EventMutation mutation) throws IOException {
        Path target = tempDir.resolve(name);
        boolean applied = false;
        try (WorldJournal original = new WorldJournal(source, RUN);
             WorldJournal rewritten = new WorldJournal(target, RUN)) {
            for (WorldCommit commit : original.commits()) {
                List<WorldEvent> events = new ArrayList<>(commit.events());
                boolean isCapital = events.stream()
                        .anyMatch(event -> "CAPITAL_FLOW_V1".equals(event.type()));
                if (!applied && (isCapital || (!onlyCapitalBatch
                        && (commit.commandId().startsWith("task:") || commit.commandId().startsWith("clock:"))))) {
                    mutation.change(events);
                    applied = true;
                }
                rewritten.commit(commit.commandId(), commit.commandContent(), commit.result(),
                        events);
            }
        }
        assertTrue(applied, "必须找到可变异批次");
        return target;
    }
}
