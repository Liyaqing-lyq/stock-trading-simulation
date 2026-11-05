package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.ManufacturingModel.ReceivableStatus;
import edu.cufe.auction.world.ManufacturingRun.ReportEntry;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * M3a.2：制造经营接入唯一世界日志。
 *
 * <p>覆盖施工单的八组验收：开局档案/配置/组织映射与公司资产边界；17–20 日采购→付款入料→投产→
 * 完工→交付与失败 COD；30 日强制关期、35 日自动回款与已排应付；客户付款冻结导致逾期并暂停后
 * 重试；外部命令幂等、内部任务/账单标识碰撞；真实经济完成帧中断与半帧恢复；期间唯一/连续与
 * 折旧封顶、收盘不影响经营；同 seed 乱序与旧档案兼容。</p>
 *
 * <p>这里的"公司"是受信管理夹具，不是已实现角色授权或董事会；断言只看该档案自己的账与日志。</p>
 */
@DisplayName("WorldRuntime：制造经营接入唯一世界日志")
class WorldRuntimeManufacturingTest {

    private static final long SEED = 20261106L;
    private static final String RUN = "run-mfg";
    private static final String SYM = "CUFE1";
    private static final long DAY = ManufacturingModel.DAY_MILLIS;
    private static final ManufacturingRun.Config CONFIG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), SYM);

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 组 1：开局档案、配置、组织映射与公司未建模资产
    // ------------------------------------------------------------------

    @Test
    @DisplayName("开局：缺配置/多配置/非组织/发行人错位/公司第二账户/公司持股均在写盘前拒绝")
    void openingRejectsInvalidConfigurationBeforeAnyFileIsWritten() throws IOException {
        Path missing = tempDir.resolve("missing-config.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(missing,
                companyManifest(RUN, SEED), openings(), registry()), "制造档案必须给配置");
        assertFalse(Files.exists(missing), "开局错误不得创建日志文件");

        Path extra = tempDir.resolve("extra-config.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(extra,
                matchingManifest(RUN, SEED), openings(), registry(), CONFIG),
                "非制造档案不得携带制造配置");
        assertFalse(Files.exists(extra));

        Path person = tempDir.resolve("person-owner.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(person,
                companyManifest(RUN, SEED), openings(), registryWithOwner(
                        new OpeningRegistry.PropertyOwner("company", OwnerKind.PERSON, "investor"),
                        List.of()), CONFIG), "公司财产主体必须是 ORGANIZATION");
        assertFalse(Files.exists(person));

        Path issuer = tempDir.resolve("issuer-mismatch.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(issuer,
                companyManifest(RUN, SEED), openings(), registryIssuedBy("other"), CONFIG),
                "证券发行人必须就是公司所属组织");
        assertFalse(Files.exists(issuer));

        List<OpeningAccount> secondAccount = new ArrayList<>(openings());
        secondAccount.add(new OpeningAccount("CO2", "company", bd("0.00"), "实验开局历史禀赋"));
        Path second = tempDir.resolve("second-account.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(second,
                companyManifest(RUN, SEED), secondAccount, registry(), CONFIG),
                "公司第二现金账户即使余额为 0 也必须拒绝");
        assertFalse(Files.exists(second));

        Path holding = tempDir.resolve("company-holding.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(holding,
                companyManifest(RUN, SEED), openings(), registryWithOwner(null,
                        List.of(new OpeningRegistry.ShareAllocation("CO", SYM, 10, bd("10.0000"),
                                ShareClass.FLOAT))), CONFIG),
                "公司持股是未建模资产，制造档案不得开局");
        assertFalse(Files.exists(holding));

        Path file = tempDir.resolve("company.journal");
        try (WorldRuntime runtime = open(file)) {
            ManufacturingRun run = manufacturing(runtime);
            assertEquals(SYM, run.symbol());
            assertEquals("company", run.ledger().opening().companyOwnerId());
            assertEquals(0, run.ledger().initialEquity().compareTo(bd("2000.00")),
                    "初始权益 = 公司现金 1000 + 设备原值 1000");
            assertEquals(0, run.ledger().initialWorldCash().compareTo(bd("3600.00")));
            assertTrue(run.reports().isEmpty());
            List<PendingTask> closes = pending(runtime, PendingTask.MANUFACTURING_PERIOD_CLOSE_V1);
            assertEquals(1, closes.size(), "开局必须自带期间 0 关期任务");
            assertEquals(30 * DAY, closes.get(0).dueTimeMillis());
            assertEquals(WorldScheduler.Phase.RULES, closes.get(0).phase());
        }

        // 换一套配置重开同一日志：开局内容绑定配置，必须在恢复期拒绝
        ManufacturingRun.Config other = new ManufacturingRun.Config(
                new ManufacturingModel.Opening("company", "CO", "OPS", "CUS", "SUP"), SYM);
        assertThrows(IOException.class, () -> new WorldRuntime(file,
                companyManifest(RUN, SEED), openings(), registry(), other));
    }

    // ------------------------------------------------------------------
    // 组 2：17→20 日主线与失败 COD
    // ------------------------------------------------------------------

    @Test
    @DisplayName("17 日采购→18 日付款入料→投产 300/WIP800→19 日完工→20 日交付 AR1200；额外 5 件 COD 失败不动资产")
    void mainLineRunsThroughAutomaticChildTasks() throws IOException {
        Path file = tempDir.resolve("main-line.journal");
        try (WorldRuntime runtime = open(file)) {
            scheduleContract(runtime, "c-contract", "contract-1", 16 * DAY, 10, "120.00", 20 * DAY);
            runtime.schedulePurchaseOrder("c-po", "po-1", 17 * DAY, "company", "po-1", 10);
            runtime.scheduleProduction("c-batch", "batch-1", 18 * DAY + 1, "company", "batch-1", 10);
            runtime.scheduleDelivery("c-deliver", "delivery-1", 20 * DAY, "company",
                    "contract-1", 10, "ar-1");

            runtime.advanceTo(17 * DAY);
            assertTrue(report(runtime, "po-1").accepted(), report(runtime, "po-1").reason());
            assertEquals(0, cash(runtime, "CO").compareTo(bd("1000.00")), "下单不动现金");
            List<PendingTask> receipts = pending(runtime, PendingTask.MANUFACTURING_GOODS_RECEIPT_V1);
            assertEquals(1, receipts.size(), "下单成功后同批自动排到货");
            assertEquals(18 * DAY, receipts.get(0).dueTimeMillis(), "到货 = 到期 + 1 日");

            runtime.advanceTo(18 * DAY);
            PendingTask receipt = receipts.get(0);
            assertTrue(report(runtime, receipt.taskId()).accepted(),
                    report(runtime, receipt.taskId()).reason());
            assertEquals(0, cash(runtime, "CO").compareTo(bd("500.00")), "到货同批付款 10×50");
            assertEquals(0, cash(runtime, "SUP").compareTo(bd("600.00")));
            assertEquals(10L, manufacturing(runtime).ledger().rawMaterials().quantity());
            assertEquals(0, manufacturing(runtime).ledger().rawMaterials().amount()
                    .compareTo(bd("500.00")));

            runtime.advanceTo(18 * DAY + 1);
            List<PendingTask> completions =
                    pending(runtime, PendingTask.MANUFACTURING_BATCH_COMPLETION_V1);
            assertEquals(1, completions.size(), "投产成功后同批自动排完工");
            assertEquals(19 * DAY + 1, completions.get(0).dueTimeMillis());
            assertEquals(0, cash(runtime, "CO").compareTo(bd("200.00")), "投产付人工 10×30");
            assertEquals(0, cash(runtime, "OPS").compareTo(bd("300.00")));
            assertEquals(0, manufacturing(runtime).ledger().workInProgress().amount()
                    .compareTo(bd("800.00")));

            runtime.advanceTo(19 * DAY + 1);
            assertTrue(report(runtime, completions.get(0).taskId()).accepted());
            assertEquals(10L, manufacturing(runtime).ledger().finishedGoods().quantity());
            assertEquals(0, manufacturing(runtime).ledger().workInProgress().amount().signum());

            runtime.advanceTo(20 * DAY);
            ManufacturingModel.Report report = book(runtime);
            assertTrue(report(runtime, "delivery-1").accepted(),
                    report(runtime, "delivery-1").reason());
            assertEquals("ar-1", report(runtime, "delivery-1").businessId());
            assertEquals(0, report.receivableOutstanding().compareTo(bd("1200.00")));
            assertEquals(0, report.revenue().compareTo(bd("1200.00")));
            assertEquals(0, report.costOfGoodsSold().compareTo(bd("800.00")));
            assertEquals(0, report.cumulativeProfit().compareTo(bd("400.00")));
            assertEquals(0, report.companyCash().compareTo(bd("200.00")));
            assertEquals(0, report.totalAssets().compareTo(bd("2400.00")));
            assertEquals(0, report.worldCash().compareTo(bd("3600.00")));
            List<PendingTask> collections =
                    pending(runtime, PendingTask.MANUFACTURING_AUTO_COLLECTION_V1);
            assertEquals(1, collections.size(), "交付成功后同批自动排 15 日收款");
            assertEquals(35 * DAY, collections.get(0).dueTimeMillis());

            // 额外 5 件 COD：现金 200 < 250，到期失败但保留 FAILED_NO_CASH 元数据
            runtime.schedulePurchaseOrder("c-extra", "po-extra", 20 * DAY + 1, "company",
                    "po-extra", 5);
            runtime.advanceTo(21 * DAY);
            assertTrue(report(runtime, "po-extra").accepted(), "下单本身成功");
            List<PendingTask> extraReceipts =
                    pending(runtime, PendingTask.MANUFACTURING_GOODS_RECEIPT_V1);
            assertEquals(1, extraReceipts.size());
            String extraReceipt = extraReceipts.get(0).taskId();
            runtime.advanceTo(22 * DAY);

            ReportEntry failed = report(runtime, extraReceipt);
            assertFalse(failed.accepted());
            assertEquals(ManufacturingModel.REASON_INSUFFICIENT_CASH, failed.reason());
            assertEquals(ManufacturingModel.PurchaseOrderStatus.FAILED_NO_CASH,
                    manufacturing(runtime).ledger().purchaseOrders().get("po-extra").status(),
                    "失败结果也必须落盘，不能丢候选");
            assertEquals(0, book(runtime).totalAssets().compareTo(bd("2400.00")), "资产零变化");
            assertEquals(0, cash(runtime, "CO").compareTo(bd("200.00")));
            assertTrue(pending(runtime, PendingTask.MANUFACTURING_GOODS_RECEIPT_V1).isEmpty(),
                    "失败的到货不再留待办");
        }
    }

    // ------------------------------------------------------------------
    // 组 3：强制关期、自动回款、已排应付
    // ------------------------------------------------------------------

    @Test
    @DisplayName("30 日关期付 200/应付 50/折旧 100→利润 50；35 日自动收 1200，再付 50→现金 1150/设备 900")
    void forcedPeriodCloseAndCollectionsReachExpectedBalanceSheet() throws IOException {
        Path file = tempDir.resolve("close.journal");
        try (WorldRuntime runtime = open(file)) {
            buildDeliveredCompany(runtime);
            runtime.schedulePayExpenseBill("c-pay", "pay-bill-0", 36 * DAY, "company", 0L);

            runtime.advanceTo(30 * DAY);
            ManufacturingRun run = manufacturing(runtime);
            assertEquals(1L, run.ledger().closedPeriods(), "关期由系统任务自动完成，无手动入口");
            ManufacturingModel.ExpenseBill bill = run.ledger().expenseBills().get("bill:0");
            assertNotNull(bill, "账单标识由到期时空闲标识决定并记录：" + run.ledger().expenseBills());
            assertEquals(0, bill.paid().compareTo(bd("200.00")), "现金不足时按可用现金实付");
            assertEquals(0, bill.outstanding().compareTo(bd("50.00")));
            ReportEntry closeReport = report(runtime, "mfg-close:0");
            assertTrue(closeReport.accepted(), closeReport.reason());
            assertEquals("bill:0", closeReport.businessId());
            assertEquals(0, closeReport.amount().compareTo(bd("200.00")));
            assertEquals(0, cash(runtime, "OPS").compareTo(bd("500.00")));

            ManufacturingModel.Report closed = book(runtime);
            assertEquals(0, closed.companyCash().signum());
            assertEquals(0, closed.payableOutstanding().compareTo(bd("50.00")));
            assertEquals(0, closed.equipmentNet().compareTo(bd("900.00")));
            assertEquals(0, closed.periodExpense().compareTo(bd("250.00")));
            assertEquals(0, closed.depreciation().compareTo(bd("100.00")));
            assertEquals(0, closed.cumulativeProfit().compareTo(bd("50.00")));
            assertEquals(0, closed.totalAssets().compareTo(bd("2100.00")));
            assertEquals(0, closed.totalLiabilities().compareTo(bd("50.00")));
            assertEquals(0, closed.equity().compareTo(bd("2050.00")));
            assertEquals(0, closed.worldCash().compareTo(bd("3600.00")));

            List<PendingTask> nextClose =
                    pending(runtime, PendingTask.MANUFACTURING_PERIOD_CLOSE_V1);
            assertEquals(1, nextClose.size(), "关期后自动排下一期间，不暴露手动关期");
            assertEquals(60 * DAY, nextClose.get(0).dueTimeMillis());

            runtime.advanceTo(35 * DAY);
            assertEquals(0, manufacturing(runtime).ledger().receivables().get("ar-1")
                    .outstanding().signum(), "到期自动回款");
            assertEquals(0, cash(runtime, "CO").compareTo(bd("1200.00")));
            assertEquals(0, cash(runtime, "CUS").compareTo(bd("800.00")));

            runtime.advanceTo(36 * DAY);
            ReportEntry paid = report(runtime, "pay-bill-0");
            assertTrue(paid.accepted(), paid.reason());
            assertEquals(0, paid.amount().compareTo(bd("50.00")));
            ManufacturingModel.Report settled = book(runtime);
            assertEquals(0, settled.companyCash().compareTo(bd("1150.00")));
            assertEquals(0, settled.payableOutstanding().signum());
            assertEquals(0, settled.totalAssets().compareTo(bd("2050.00")));
            assertEquals(0, settled.equity().compareTo(bd("2050.00")));
            assertEquals(0, settled.worldCash().compareTo(bd("3600.00")));
            assertEquals(0, cash(runtime, "OPS").compareTo(bd("550.00")));
        }
    }

    // ------------------------------------------------------------------
    // 组 4：客户买单冻结导致逾期、取消后只收一次；公司不得绕过分录
    // ------------------------------------------------------------------

    @Test
    @DisplayName("客户 gross2000 但买单冻结 1000：35 日回款逾期，撤单后重试只收一次；公司转账/下单入口拒绝")
    void frozenCustomerCashCausesOverdueUntilOrderCancelled() throws IOException {
        Path file = tempDir.resolve("freeze-collect.journal");
        try (WorldRuntime runtime = open(file)) {
            buildDeliveredCompany(runtime);
            runtime.scheduleOrder("c-buy", "cus-buy", 21 * DAY, "customer", "CUS", SYM,
                    Side.BUY, OrderType.LIMIT, bd("10.00"), 100);
            runtime.advanceTo(21 * DAY);
            assertEquals(0, WorldExchange.frozenCash(exchange(runtime), "CUS")
                    .compareTo(bd("1000.00")));

            runtime.advanceTo(35 * DAY);
            ReportEntry overdue = report(runtime, "mfg-collect:ar-1");
            assertFalse(overdue.accepted());
            assertEquals(ManufacturingModel.REASON_OVERDUE, overdue.reason(),
                    "false 结果也必须带逾期元数据");
            assertEquals(ReceivableStatus.OVERDUE,
                    manufacturing(runtime).ledger().receivables().get("ar-1").status());
            assertEquals(0, manufacturing(runtime).ledger().receivables().get("ar-1")
                    .outstanding().compareTo(bd("1200.00")), "逾期不动资产");
            assertEquals(0, cash(runtime, "CO").signum(), "30 日关期已按可用现金付清 200");

            runtime.scheduleCollectReceivable("c-retry", "retry-1", 36 * DAY, "company", "ar-1");
            runtime.advanceTo(36 * DAY);
            assertFalse(report(runtime, "retry-1").accepted(), "冻结未释放前仍逾期");
            assertEquals(0, cash(runtime, "CO").signum());

            runtime.scheduleCancelOrder("c-cancel", "cancel-buy", 37 * DAY, "customer",
                    orderId(runtime, "cus-buy"));
            runtime.advanceTo(37 * DAY);
            assertEquals(0, WorldExchange.freeCash(exchange(runtime),
                    runtime.snapshot().accounts(), "CUS").compareTo(bd("2000.00")));

            runtime.scheduleCollectReceivable("c-retry2", "retry-2", 38 * DAY, "company", "ar-1");
            runtime.advanceTo(38 * DAY);
            assertTrue(report(runtime, "retry-2").accepted(), report(runtime, "retry-2").reason());
            assertEquals(0, cash(runtime, "CO").compareTo(bd("1200.00")), "只收一次 1200");

            runtime.scheduleCollectReceivable("c-retry3", "retry-3", 39 * DAY, "company", "ar-1");
            runtime.advanceTo(39 * DAY);
            assertFalse(report(runtime, "retry-3").accepted());
            assertEquals(ManufacturingModel.REASON_RECEIVABLE_ALREADY_COLLECTED,
                    report(runtime, "retry-3").reason());
            assertEquals(0, cash(runtime, "CO").compareTo(bd("1200.00")), "重复回款不得再加钱");

            long position = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleTransfer(
                    "c-tx", "tx-1", 40 * DAY, "company", "CO", "SUP", bd("10.00"), 0),
                    "公司经营账户不得被普通转账绕过");
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleOrder(
                    "c-ord", "ord-1", 40 * DAY, "company", "CO", SYM, Side.BUY,
                    OrderType.LIMIT, bd("10.00"), 10), "公司证券下单必须入口拒绝");
            assertEquals(position, runtime.snapshot().position(), "入口拒绝不写批次");
        }
    }

    // ------------------------------------------------------------------
    // 组 5：命令幂等与内部标识碰撞
    // ------------------------------------------------------------------

    @Test
    @DisplayName("外部命令重发不重复实体/子任务；用户抢占内部任务与账单标识后强制任务仍推进")
    void retriesAndInternalIdCollisionsStayRecoverable() throws IOException {
        Path file = tempDir.resolve("idempotent.journal");
        try (WorldRuntime runtime = open(file)) {
            // 用户提前占名：一个会成为内部到货标识的任务，以及一个会成为账单标识的合同
            runtime.scheduleTransfer("c-occupy", "mfg-receipt:po-1", 90 * DAY, "investor",
                    "INV", "CUS", bd("1.00"), 0);
            scheduleContract(runtime, "c-contract", "bill:0", 16 * DAY, 10, "120.00", 20 * DAY);

            String first = runtime.schedulePurchaseOrder("c-po", "po-1", 17 * DAY, "company",
                    "po-1", 10);
            assertEquals("SCHEDULED:po-1", first);
            int pendingAfter = runtime.snapshot().pending().size();
            assertEquals(first, runtime.schedulePurchaseOrder("c-po", "po-1", 17 * DAY,
                    "company", "po-1", 10), "同一命令重发返回原回报");
            assertEquals(pendingAfter, runtime.snapshot().pending().size(), "重发不得重复排程");
            assertThrows(IllegalArgumentException.class, () -> runtime.schedulePurchaseOrder(
                    "c-po", "po-1", 17 * DAY, "company", "po-1", 20),
                    "同命令标识不同内容必须拒绝");

            runtime.scheduleProduction("c-batch", "batch-1", 18 * DAY + 1, "company",
                    "batch-1", 10);
            runtime.scheduleDelivery("c-deliver", "delivery-1", 20 * DAY, "company",
                    "bill:0", 10, "ar-1");
            runtime.advanceTo(17 * DAY);

            List<PendingTask> receipts = pending(runtime, PendingTask.MANUFACTURING_GOODS_RECEIPT_V1);
            assertEquals(1, receipts.size());
            String receiptId = receipts.get(0).taskId();
            assertNotEquals("mfg-receipt:po-1", receiptId, "内部标识必须避开用户已用标识");
            assertTrue(runtime.snapshot().usedTaskIds().contains("mfg-receipt:po-1"));

            runtime.advanceTo(20 * DAY);
            assertEquals(1, manufacturing(runtime).ledger().purchaseOrders().size(),
                    "重发不得产生第二张采购单");
            assertEquals(1, manufacturing(runtime).ledger().productionBatches().size());
            assertEquals(1, manufacturing(runtime).ledger().receivables().size());

            runtime.advanceTo(30 * DAY);
            ManufacturingRun run = manufacturing(runtime);
            assertEquals(1L, run.ledger().closedPeriods());
            ManufacturingModel.ExpenseBill bill = billOf(run, 0);
            assertNotNull(bill, "账单标识必须避开用户已用的 bill:0：" + run.ledger().expenseBills());
            assertNotEquals("bill:0", bill.billId());
            assertTrue(report(runtime, "mfg-close:0").accepted());
            assertEquals(0, bill.amount().compareTo(bd("250.00")));
            assertTrue(runtime.snapshot().usedTaskIds().contains("mfg-close:0"));

            // 费用与付款不重复
            runtime.advanceTo(36 * DAY);
            assertEquals(1L, manufacturing(runtime).ledger().closedPeriods());
            assertEquals(1, manufacturing(runtime).ledger().expenseBills().size());
        }
    }

    // ------------------------------------------------------------------
    // 组 6：真实经济完成帧中断与半帧
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已有 PO/WIP/应收后关期帧中断：账/待办/报告保持旧版并暂停；恢复只关一次，半帧同理")
    void interruptedAndTruncatedFramesRecoverOnce() throws IOException {
        Path file = tempDir.resolve("interrupt-close.journal");
        long position;
        try (WorldRuntime runtime = open(file)) {
            buildDeliveredCompany(runtime);
            assertEquals(1, manufacturing(runtime).ledger().purchaseOrders().size());
            assertEquals(1, manufacturing(runtime).ledger().productionBatches().size());
            assertEquals(1, manufacturing(runtime).ledger().receivables().size());
            position = runtime.snapshot().position();

            try {
                Thread.currentThread().interrupt();
                assertThrows(IOException.class, () -> runtime.advanceTo(30 * DAY),
                        "线程中断应让关期帧真实写入失败");
            } finally {
                Thread.interrupted();
            }
            assertTrue(runtime.isPaused());
            assertEquals(position, runtime.snapshot().position(), "失败批次不得发布");
            assertEquals(0L, manufacturing(runtime).ledger().closedPeriods());
            assertTrue(manufacturing(runtime).ledger().expenseBills().isEmpty(), "不得留下半张账单");
            assertEquals(0, cash(runtime, "CO").compareTo(bd("200.00")), "费用不得被提前扣走");
            assertEquals(0, manufacturing(runtime).ledger().equipment().accumulatedDepreciation()
                    .signum(), "折旧不得半量入账");
            assertEquals(1, pending(runtime, PendingTask.MANUFACTURING_PERIOD_CLOSE_V1).size(),
                    "未确认的关期任务仍在待办");

            assertThrows(IllegalStateException.class, () -> runtime.schedulePurchaseOrder(
                    "p1", "px1", 40 * DAY, "company", "px1", 5));
            assertThrows(IllegalStateException.class, () -> runtime.scheduleProduction(
                    "p2", "px2", 40 * DAY, "company", "px2", 10));
            assertThrows(IllegalStateException.class, () -> scheduleContract(runtime, "p3",
                    "px3", 40 * DAY, 10, "120.00", 41 * DAY));
            assertThrows(IllegalStateException.class, () -> runtime.scheduleDelivery(
                    "p4", "px4", 40 * DAY, "company", "contract-1", 10, "px-ar"));
            assertThrows(IllegalStateException.class, () -> runtime.scheduleCollectReceivable(
                    "p5", "px5", 40 * DAY, "company", "ar-1"));
            assertThrows(IllegalStateException.class, () -> runtime.schedulePayExpenseBill(
                    "p6", "px6", 40 * DAY, "company", 0L));
        }

        long boundary;
        try (WorldRuntime runtime = open(file)) {
            runtime.advanceTo(30 * DAY);
            assertEquals(1L, manufacturing(runtime).ledger().closedPeriods(), "恢复后只关一次");
            assertEquals(1, manufacturing(runtime).ledger().expenseBills().size());
            assertEquals(0, cash(runtime, "CO").signum());
            boundary = Files.size(file);
            runtime.advanceTo(35 * DAY);
            assertEquals(0, manufacturing(runtime).ledger().receivables().get("ar-1")
                    .outstanding().signum());
            assertTrue(Files.size(file) > boundary + 20, "回款帧必须有足够字节可截半");
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(boundary + 20);
            channel.force(true);
        }
        try (WorldRuntime runtime = open(file)) {
            assertEquals(0, manufacturing(runtime).ledger().receivables().get("ar-1")
                    .outstanding().compareTo(bd("1200.00")), "未确认的回款帧不得生效");
            assertEquals(0, cash(runtime, "CO").signum());
            assertEquals(1, pending(runtime, PendingTask.MANUFACTURING_AUTO_COLLECTION_V1).size());
            runtime.advanceTo(35 * DAY);
            assertEquals(0, manufacturing(runtime).ledger().receivables().get("ar-1")
                    .outstanding().signum());
            assertEquals(0, cash(runtime, "CO").compareTo(bd("1200.00")));
        }
        try (WorldRuntime runtime = open(file)) {
            runtime.advanceTo(35 * DAY);
            assertEquals(0, cash(runtime, "CO").compareTo(bd("1200.00")), "重开不得重复回款");
            assertEquals(1L, manufacturing(runtime).ledger().closedPeriods());
        }
    }

    // ------------------------------------------------------------------
    // 组 7：期间唯一/连续、折旧封顶、收盘不影响经营
    // ------------------------------------------------------------------

    @Test
    @DisplayName("关期不可提前/跳过且自动连续；跨 11 期折旧封顶 1000；股票收盘后经营任务继续")
    void periodChainAccumulatesAndDepreciationIsCapped() throws IOException {
        Path file = tempDir.resolve("periods.journal");
        try (WorldRuntime runtime = open(file)) {
            buildDeliveredCompany(runtime);
            runtime.scheduleCloseMarket("c-close", "close-1", 25 * DAY);

            runtime.advanceTo(25 * DAY);
            assertTrue(exchange(runtime).closed(), "股票市场已收盘");
            runtime.advanceTo(29 * DAY);
            assertEquals(0L, manufacturing(runtime).ledger().closedPeriods(), "不允许提前关期");

            runtime.advanceTo(330 * DAY);
            ManufacturingRun run = manufacturing(runtime);
            assertEquals(11L, run.ledger().closedPeriods(), "期间自动连续累计");
            for (long period = 0; period < 11; period++) {
                ManufacturingModel.ExpenseBill bill = billOf(run, period);
                assertNotNull(bill, "缺少期间 " + period + " 的账单");
                assertEquals(period, bill.period());
            }
            assertEquals(0, run.ledger().equipment().accumulatedDepreciation()
                    .compareTo(bd("1000.00")), "折旧不得超过原值");
            assertEquals(0, run.ledger().equipment().net().signum());
            ManufacturingModel.Report book = book(runtime);
            assertTrue(book.balanced(), "资产=负债+权益且世界现金守恒");
            assertEquals(0, book.totalAssets().compareTo(book.totalLiabilities().add(book.equity())));
            assertEquals(0, book.worldCash().compareTo(bd("3600.00")));
            List<PendingTask> closes = pending(runtime, PendingTask.MANUFACTURING_PERIOD_CLOSE_V1);
            assertEquals(1, closes.size(), "始终只有一个下一期关期任务");
            assertEquals(360 * DAY, closes.get(0).dueTimeMillis());
        }
    }

    // ------------------------------------------------------------------
    // 组 8：同 seed 乱序与旧档案兼容
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同 seed 下乱序排程得到同一合法结果；旧 schema1/3 档案不含制造字段且仍可续写")
    void sameSeedOutOfOrderMatchesAndLegacyArchivesStayWritable() throws IOException {
        Path forward = tempDir.resolve("forward.journal");
        Path reverse = tempDir.resolve("reverse.journal");
        ManufacturingModel.State forwardLedger;
        Map<String, CashLedger.CashAccount> forwardAccounts;
        try (WorldRuntime runtime = open(forward)) {
            scheduleContract(runtime, "c1", "contract-1", 16 * DAY, 10, "120.00", 20 * DAY);
            runtime.schedulePurchaseOrder("c2", "po-1", 17 * DAY, "company", "po-1", 10);
            runtime.scheduleProduction("c3", "batch-1", 18 * DAY + 1, "company", "batch-1", 10);
            runtime.scheduleDelivery("c4", "delivery-1", 20 * DAY, "company",
                    "contract-1", 10, "ar-1");
            runtime.advanceTo(20 * DAY);
            forwardLedger = manufacturing(runtime).ledger();
            forwardAccounts = runtime.snapshot().accounts();
        }
        try (WorldRuntime runtime = open(reverse)) {
            runtime.scheduleDelivery("c4", "delivery-1", 20 * DAY, "company",
                    "contract-1", 10, "ar-1");
            runtime.scheduleProduction("c3", "batch-1", 18 * DAY + 1, "company", "batch-1", 10);
            runtime.schedulePurchaseOrder("c2", "po-1", 17 * DAY, "company", "po-1", 10);
            scheduleContract(runtime, "c1", "contract-1", 16 * DAY, 10, "120.00", 20 * DAY);
            runtime.advanceTo(20 * DAY);
            assertEquals(forwardLedger, manufacturing(runtime).ledger(), "乱序不得改变合法结果");
            assertEquals(forwardAccounts, runtime.snapshot().accounts());
        }

        Path legacy = tempDir.resolve("legacy-matching.journal");
        try (WorldRuntime runtime = new WorldRuntime(legacy, matchingManifest("run-legacy", SEED),
                openings(), registry())) {
            assertTrue(runtime.snapshot().manufacturing().isEmpty(), "旧档案没有制造子集");
            assertEquals("SCHEDULED:t1", runtime.scheduleTransfer("c1", "t1", 10 * DAY,
                    "company", "CO", "SUP", bd("10.00"), 0));
            runtime.advanceTo(10 * DAY);
            assertEquals(0, cash(runtime, "CO").compareTo(bd("990.00")),
                    "旧档案不施加公司限制");
        }
        String payload = snapshotPayload(legacy, 1);
        assertFalse(payload.contains("\"manufacturing\""), "旧档案新增字段必须是省略而不是 null");

        Path cashOnly = tempDir.resolve("legacy-cash.journal");
        try (WorldRuntime runtime = new WorldRuntime(cashOnly,
                cashManifest("run-cash", SEED), onlyInvestor())) {
            assertTrue(runtime.snapshot().manufacturing().isEmpty());
            runtime.scheduleTransfer("c1", "t1", 5 * DAY, "investor", "INV", "SUP",
                    bd("100.00"), 0);
            runtime.advanceTo(5 * DAY);
            assertEquals(0, cash(runtime, "INV").compareTo(bd("400.00")));
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private WorldRuntime open(Path file) throws IOException {
        return new WorldRuntime(file, companyManifest(RUN, SEED), openings(), registry(), CONFIG);
    }

    /** 主线的公共前缀：16 日签约、17 日采购、18 日投产、20 日交付，推进到 20 日。 */
    private void buildDeliveredCompany(WorldRuntime runtime) throws IOException {
        scheduleContract(runtime, "c-contract", "contract-1", 16 * DAY, 10, "120.00", 20 * DAY);
        runtime.schedulePurchaseOrder("c-po", "po-1", 17 * DAY, "company", "po-1", 10);
        runtime.scheduleProduction("c-batch", "batch-1", 18 * DAY + 1, "company", "batch-1", 10);
        runtime.scheduleDelivery("c-deliver", "delivery-1", 20 * DAY, "company",
                "contract-1", 10, "ar-1");
        runtime.advanceTo(20 * DAY);
    }

    private String scheduleContract(WorldRuntime runtime, String commandId, String contractId,
                                    long due, long quantity, String price, long promised)
            throws IOException {
        return runtime.scheduleSalesContract(commandId, contractId, due, "company", contractId,
                quantity, bd(price), promised);
    }

    private static ManufacturingRun manufacturing(WorldRuntime runtime) {
        return runtime.snapshot().manufacturing()
                .orElseThrow(() -> new AssertionError("缺少制造子集"));
    }

    private static ManufacturingModel.Report book(WorldRuntime runtime) {
        return ManufacturingModel.report(manufacturing(runtime).ledger(),
                runtime.snapshot().accounts());
    }

    private static ReportEntry report(WorldRuntime runtime, String taskId) {
        ReportEntry found = manufacturing(runtime).reports().get(taskId);
        assertTrue(found != null, "缺少制造报告：" + taskId + "，已有 "
                + manufacturing(runtime).reports().keySet());
        return found;
    }

    private static List<PendingTask> pending(WorldRuntime runtime, String kind) {
        List<PendingTask> found = new ArrayList<>();
        for (PendingTask task : runtime.snapshot().pending()) {
            if (task.kind().equals(kind)) {
                found.add(task);
            }
        }
        return found;
    }

    private static ManufacturingModel.ExpenseBill billOf(ManufacturingRun run, long period) {
        for (ManufacturingModel.ExpenseBill bill : run.ledger().expenseBills().values()) {
            if (bill.period() == period) {
                return bill;
            }
        }
        return null;
    }

    private static long orderId(WorldRuntime runtime, String taskId) {
        long found = runtime.snapshot().exchange().orElseThrow().reports().get(taskId).orderId();
        assertTrue(found > 0, "缺少订单号：" + taskId);
        return found;
    }

    private static WorldExchange.State exchange(WorldRuntime runtime) {
        return runtime.snapshot().exchange().orElseThrow(() -> new AssertionError("缺少交易状态"));
    }

    private static BigDecimal cash(WorldRuntime runtime, String accountId) {
        return runtime.snapshot().accounts().get(accountId).cash();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static String snapshotPayload(Path file, long position) throws IOException {
        try (WorldJournal journal = new WorldJournal(file, "run-legacy")) {
            for (WorldCommit commit : journal.commits()) {
                if (commit.position() == position) {
                    for (WorldEvent event : commit.events()) {
                        if ("STATE_SNAPSHOT_V1".equals(event.type())) {
                            return event.payload();
                        }
                    }
                }
            }
        }
        throw new AssertionError("缺少位置 " + position + " 的状态快照");
    }

    // ------------------------------------------------------------------
    // 开局夹具：公司 owner=company（party=cufe，两者不同字符串）、发行人也是 cufe
    // ------------------------------------------------------------------

    private static List<OpeningAccount> openings() {
        return List.of(
                new OpeningAccount("CO", "company", bd("1000.00"), "实验开局历史禀赋"),
                new OpeningAccount("SUP", "supplier", bd("100.00"), "实验开局历史禀赋"),
                new OpeningAccount("CUS", "customer", bd("2000.00"), "实验开局历史禀赋"),
                new OpeningAccount("OPS", "ops", bd("0.00"), "实验开局历史禀赋"),
                new OpeningAccount("INV", "investor", bd("500.00"), "实验开局历史禀赋"));
    }

    private static List<OpeningAccount> onlyInvestor() {
        return List.of(new OpeningAccount("INV", "investor", bd("500.00"), "实验开局历史禀赋"),
                new OpeningAccount("SUP", "supplier", bd("0.00"), "实验开局历史禀赋"));
    }

    private static OpeningRegistry registry() {
        return registryWithOwner(null, List.of());
    }

    private static OpeningRegistry registryIssuedBy(String issuer) {
        return registryWithOwner(null, List.of(), issuer);
    }

    /**
     * 组一张开局的登记：可替换公司财产主体、追加公司持股分配，或改证券发行人。
     */
    private static OpeningRegistry registryWithOwner(OpeningRegistry.PropertyOwner companyOwner,
                                                     List<OpeningRegistry.ShareAllocation> extra) {
        return registryWithOwner(companyOwner, extra, "cufe");
    }

    private static OpeningRegistry registryWithOwner(OpeningRegistry.PropertyOwner companyOwner,
                                                     List<OpeningRegistry.ShareAllocation> extra,
                                                     String issuer) {
        List<OpeningRegistry.Party> parties = new ArrayList<>(List.of(
                new OpeningRegistry.Party("cufe", PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party("investor", PartyKind.PERSON, "投资者"),
                new OpeningRegistry.Party("other", PartyKind.ORGANIZATION, "其他组织")));
        List<OpeningRegistry.PropertyOwner> owners = new ArrayList<>(List.of(
                companyOwner == null
                        ? new OpeningRegistry.PropertyOwner("company", OwnerKind.ORGANIZATION, "cufe")
                        : companyOwner,
                new OpeningRegistry.PropertyOwner("supplier", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("customer", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("ops", OwnerKind.AGGREGATE, null),
                new OpeningRegistry.PropertyOwner("investor", OwnerKind.PERSON, "investor")));
        List<OpeningRegistry.ShareAllocation> allocations = new ArrayList<>();
        allocations.add(new OpeningRegistry.ShareAllocation("INV", SYM, 200 - extraQuantity(extra),
                bd("10.0000"), ShareClass.FLOAT));
        allocations.addAll(extra);
        return new OpeningRegistry(parties, owners,
                List.of(new OpeningRegistry.Security(SYM, issuer, 200, bd("10.00"))), allocations);
    }

    private static long extraQuantity(List<OpeningRegistry.ShareAllocation> extra) {
        long total = 0L;
        for (OpeningRegistry.ShareAllocation allocation : extra) {
            total = Math.addExact(total, allocation.quantity());
        }
        return total;
    }

    private static RunManifest companyManifest(String runId, long seed) {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        modules.put("manufacturing", 1);
        return new RunManifest(runId, seed, RunManifest.RULES_STOCK_COMPANY_EXPERIMENT_V1,
                modules, Map.of(), RunManifest.MODEL_NONE);
    }

    private static RunManifest matchingManifest(String runId, long seed) {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        modules.put("registry", 1);
        modules.put("securities", 1);
        modules.put("matching", 1);
        return new RunManifest(runId, seed,
                RunManifest.RULES_SECURITIES_MATCHING_EXPERIMENT_V1, modules, Map.of(),
                RunManifest.MODEL_NONE);
    }

    private static RunManifest cashManifest(String runId, long seed) {
        Map<String, Integer> modules = new TreeMap<>();
        modules.put("cash", 1);
        modules.put("scheduler", 1);
        return new RunManifest(runId, seed, RunManifest.RULES_CASH_WORLD_V1, modules, Map.of(),
                RunManifest.MODEL_NONE);
    }

    @Test
    void contractWithUnrepresentablePeriodIsRejectedBeforeScheduling() throws IOException {
        try (WorldRuntime runtime = open(tempDir.resolve("period-overflow.journal"))) {
            long due = Long.MAX_VALUE / ManufacturingModel.PERIOD_MILLIS
                    * ManufacturingModel.PERIOD_MILLIS;
            long position = runtime.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> runtime.scheduleSalesContract(
                    "overflow-contract", "overflow-contract", due, "company",
                    "far-contract", 10, bd("120.00"), due + 1));
            assertEquals(position, runtime.snapshot().position());
        }
    }

    @Test
    void manufacturingReportMustMatchItsCommitSnapshot() throws IOException {
        Path source = tempDir.resolve("report-source.journal");
        Path broken = tempDir.resolve("report-mismatch.journal");
        try (WorldRuntime runtime = open(source)) {
            runtime.schedulePurchaseOrder("report-po", "report-po", 17 * DAY,
                    "company", "report-po", 10);
            runtime.advanceTo(17 * DAY);
        }
        ObjectMapper json = new ObjectMapper();
        boolean changed = false;
        try (WorldJournal original = new WorldJournal(source, RUN);
             WorldJournal rewritten = new WorldJournal(broken, RUN)) {
            for (WorldCommit commit : original.commits()) {
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    if ("MANUFACTURING_REPORT_V1".equals(event.type())) {
                        ReportEntry report = json.readValue(event.payload(), ReportEntry.class);
                        ReportEntry mismatch = new ReportEntry(report.taskId(), report.commandId(),
                                report.kind(), report.accepted(), "DIFFERENT_REASON",
                                report.businessId(), report.amount(), report.timeMillis(),
                                report.position());
                        events.add(new WorldEvent(event.eventId(), event.type(),
                                json.writeValueAsString(mismatch)));
                        changed = true;
                    } else {
                        events.add(event);
                    }
                }
                rewritten.commit(commit.commandId(), commit.commandContent(), commit.result(), events);
            }
        }
        assertTrue(changed, "必须实际改到报告事件，而非只生成同一份存档");
        IOException mismatch = assertThrows(IOException.class, () -> open(broken).close());
        assertTrue(mismatch.getMessage().contains("制造结果与状态快照不一致"), mismatch.getMessage());
    }
}
