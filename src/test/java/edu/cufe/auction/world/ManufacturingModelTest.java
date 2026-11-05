package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.ManufacturingModel.Outcome;
import edu.cufe.auction.world.ManufacturingModel.Opening;
import edu.cufe.auction.world.ManufacturingModel.PurchaseOrderStatus;
import edu.cufe.auction.world.ManufacturingModel.ReceivableStatus;
import edu.cufe.auction.world.ManufacturingModel.Report;
import edu.cufe.auction.world.ManufacturingModel.State;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3a：制造经营实验核的纯计算测试。
 *
 * <p>覆盖主 Plan 的手算主线（P0–P8）、采购容量、到货现金失败、生产约束、完工去重、
 * 客户需求/预算/价格与跨期间限制、部分交付与成本尾差、15 日回款与逾期重试、
 * 期末费用/折旧/应付、重复关期、11 期折旧下限、注入可用现金约束、
 * JSON 往返与深不可变、跨类型 ID 复用与越权动作。</p>
 *
 * <p>所有金额比较使用 {@link BigDecimal#compareTo}，同时单独断言金额保留 2 位；
 * 本类不涉及 I/O、日志、运行时桥或证券撮合。</p>
 */
@DisplayName("ManufacturingModel：纯制造经营核")
class ManufacturingModelTest {

    private static final long DAY = ManufacturingModel.DAY_MILLIS;
    private static final long PERIOD = ManufacturingModel.PERIOD_MILLIS;

    private static final String COMPANY = "ORG-MFG-01";
    private static final String SUPPLIER = "SUP-01";
    private static final String CUSTOMER = "CUS-01";
    private static final String OPS = "OPS-01";
    private static final String INVESTOR = "INV-01";

    private static final String MFG_ACC = "ACC-MFG-01";
    private static final String SUP_ACC = "ACC-SUP-01";
    private static final String CUS_ACC = "ACC-CUS-01";
    private static final String OPS_ACC = "ACC-OPS-01";
    private static final String INV_ACC = "ACC-INV-01";

    private static final Opening OPENING =
            new Opening(COMPANY, MFG_ACC, SUP_ACC, CUS_ACC, OPS_ACC);

    private record Fixture(State state, Map<String, CashAccount> accounts) {
    }

    @Test
    @DisplayName("开局是历史禀赋：设备 1000、权益 2000、世界现金 3600")
    void openingCreatesHistoricalEndowment() {
        Fixture f = opening();
        State state = f.state();

        assertMoney("2000.00", state.initialEquity());
        assertMoney("3600.00", state.initialWorldCash());
        assertMoney("1000.00", state.equipment().originalCost());
        assertMoney("0.00", state.equipment().accumulatedDepreciation());
        assertMoney("1000.00", state.equipment().net());
        assertEquals(0L, state.rawMaterials().quantity());
        assertMoney("0.00", state.rawMaterials().amount());
        assertEquals(0L, state.finishedGoods().quantity());
        assertTrue(state.purchaseOrders().isEmpty());
        assertTrue(state.productionBatches().isEmpty());
        assertTrue(state.contracts().isEmpty());
        assertTrue(state.receivables().isEmpty());
        assertTrue(state.expenseBills().isEmpty());
        assertTrue(state.usedIds().isEmpty());
        assertEquals(0L, state.closedPeriods());

        Report report = ManufacturingModel.validate(state, f.accounts());
        assertTrue(report.balanced());
        assertMoney("1000.00", report.companyCash());
        assertMoney("2000.00", report.totalAssets());
        assertMoney("0.00", report.totalLiabilities());
        assertMoney("2000.00", report.equity());
        assertMoney("3600.00", report.worldCash());
        assertMoney("0.00", report.cumulativeProfit());
    }

    @Test
    @DisplayName("开局拒绝缺账户、错所有者、负现金、重复账户与同一对手方所有者")
    void openingRejectsInvalidAccountStructure() {
        Map<String, CashAccount> missing = openingAccounts();
        missing.remove(OPS_ACC);
        assertThrows(IllegalArgumentException.class, () -> ManufacturingModel.open(OPENING, missing));

        Map<String, CashAccount> wrongOwner = openingAccounts();
        wrongOwner.put(MFG_ACC, new CashAccount("OTHER", money("1000.00"), "历史禀赋"));
        assertThrows(IllegalArgumentException.class, () -> ManufacturingModel.open(OPENING, wrongOwner));

        Map<String, CashAccount> negative = openingAccounts();
        negative.put(MFG_ACC, new CashAccount(COMPANY, money("-1.00"), "历史禀赋"));
        assertThrows(IllegalArgumentException.class, () -> ManufacturingModel.open(OPENING, negative));

        assertThrows(IllegalArgumentException.class,
                () -> new Opening(COMPANY, MFG_ACC, SUP_ACC, SUP_ACC, OPS_ACC));

        Map<String, CashAccount> sameOwner = openingAccounts();
        sameOwner.put(CUS_ACC, new CashAccount(SUPPLIER, money("2000.00"), "历史禀赋"));
        assertThrows(IllegalArgumentException.class, () -> ManufacturingModel.open(OPENING, sameOwner));

        Map<String, CashAccount> secondCompanyAccount = openingAccounts();
        secondCompanyAccount.put("ACC-MFG-SECOND", new CashAccount(COMPANY, money("0.00"), "历史禀赋"));
        assertThrows(IllegalArgumentException.class,
                () -> ManufacturingModel.open(OPENING, secondCompanyAccount),
                "公司所有者只允许唯一指定经营现金账户，零余额的第二个账户也必须拒绝");
    }

    @Test
    @DisplayName("手算主线 P0–P8 每一步都平衡，世界现金恒为 3600")
    void mainWalkthroughBalancesEveryStep() {
        Fixture f = opening();
        Map<String, CashAccount> beforeOrders = new LinkedHashMap<>(f.accounts());

        f = accept(f, ManufacturingModel.placePurchaseOrder(
                f.state(), 17 * DAY, COMPANY, "PO-1", 10, f.accounts()));
        assertEquals(PurchaseOrderStatus.ORDERED, f.state().purchaseOrders().get("PO-1").status());
        assertEquals(18 * DAY, f.state().purchaseOrders().get("PO-1").arrivalAt());
        assertMoney("50.00", f.state().purchaseOrders().get("PO-1").unitPrice());
        assertEquals(beforeOrders, f.accounts(), "下单不产生现金或应付");

        f = accept(f, ManufacturingModel.receivePurchaseOrder(
                f.state(), 18 * DAY, COMPANY, "PO-1", money("500.00"), f.accounts()));
        assertEquals(10L, f.state().rawMaterials().quantity());
        assertMoney("500.00", f.state().rawMaterials().amount());
        assertMoney("500.00", cash(f, MFG_ACC));
        assertMoney("600.00", cash(f, SUP_ACC));

        f = accept(f, ManufacturingModel.startProduction(
                f.state(), 18 * DAY, COMPANY, "B-1", 10, money("500.00"), f.accounts()));
        assertEquals(0L, f.state().rawMaterials().quantity());
        assertMoney("0.00", f.state().rawMaterials().amount());
        assertEquals(10L, f.state().workInProgress().quantity());
        assertMoney("800.00", f.state().workInProgress().amount());
        assertMoney("200.00", cash(f, MFG_ACC));
        assertMoney("300.00", cash(f, OPS_ACC));

        f = accept(f, ManufacturingModel.completeProduction(f.state(), 19 * DAY, COMPANY, "B-1", f.accounts()));
        assertEquals(0L, f.state().workInProgress().quantity());
        assertMoney("0.00", f.state().workInProgress().amount());
        assertEquals(10L, f.state().finishedGoods().quantity());
        assertMoney("800.00", f.state().finishedGoods().amount());
        assertMoney("200.00", cash(f, MFG_ACC));

        f = accept(f, ManufacturingModel.signContract(
                f.state(), 19 * DAY, COMPANY, "CON-1", 10, money("120.00"), 20 * DAY, f.accounts()));
        assertEquals(10L, f.state().contracts().get("CON-1").quantity());
        assertEquals(0L, f.state().contracts().get("CON-1").deliveredQuantity());
        assertMoney("200.00", cash(f, MFG_ACC), "签约不动现金");

        f = accept(f, ManufacturingModel.deliver(
                f.state(), 20 * DAY, COMPANY, "CON-1", 10, "AR-1", f.accounts()));
        assertEquals(0L, f.state().finishedGoods().quantity());
        assertMoney("0.00", f.state().finishedGoods().amount());
        assertMoney("1200.00", f.state().receivables().get("AR-1").amount());
        assertMoney("800.00", f.state().receivables().get("AR-1").cost());
        assertEquals(35 * DAY, f.state().receivables().get("AR-1").dueAt());
        assertEquals(ReceivableStatus.OPEN, f.state().receivables().get("AR-1").status());
        assertEquals(10L, f.state().contracts().get("CON-1").deliveredQuantity());

        Report delivered = ManufacturingModel.validate(f.state(), f.accounts());
        assertMoney("1200.00", delivered.revenue());
        assertMoney("800.00", delivered.costOfGoodsSold());
        assertMoney("400.00", delivered.cumulativeProfit());
        assertMoney("200.00", delivered.companyCash(), "利润 400 不等于现金 200");
        assertMoney("2400.00", delivered.totalAssets());
        assertMoney("2400.00", delivered.equity());
        assertMoney("1200.00", delivered.receivableOutstanding());

        f = accept(f, ManufacturingModel.placePurchaseOrder(
                f.state(), 20 * DAY, COMPANY, "PO-2", 5, f.accounts()));
        Outcome noCash = ManufacturingModel.receivePurchaseOrder(
                f.state(), 21 * DAY, COMPANY, "PO-2", money("200.00"), f.accounts());
        assertFalse(noCash.accepted(), "到货需要 250，可用只有 200");
        assertEquals("INSUFFICIENT_CASH", noCash.reason());
        assertEquals(f.accounts(), noCash.accounts(), "失败到货不得扣款或记账");
        assertEquals(PurchaseOrderStatus.FAILED_NO_CASH,
                noCash.state().purchaseOrders().get("PO-2").status());
        assertEquals(0L, noCash.state().rawMaterials().quantity());
        assertMoney("0.00", noCash.state().rawMaterials().amount());
        assertBalance(noCash.state(), noCash.accounts());
        f = new Fixture(noCash.state(), noCash.accounts());

        f = accept(f, ManufacturingModel.closePeriod(
                f.state(), 30 * DAY, COMPANY, 0, "BILL-0", money("200.00"), f.accounts()));
        assertMoney("0.00", cash(f, MFG_ACC));
        assertMoney("500.00", cash(f, OPS_ACC));
        assertEquals(1, f.state().expenseBills().size());
        assertMoney("250.00", f.state().expenseBills().get("BILL-0").amount());
        assertMoney("200.00", f.state().expenseBills().get("BILL-0").paid());
        assertMoney("50.00", f.state().expenseBills().get("BILL-0").outstanding());
        assertMoney("900.00", f.state().equipment().net());

        Report closed = ManufacturingModel.validate(f.state(), f.accounts());
        assertMoney("50.00", closed.cumulativeProfit());
        assertMoney("2100.00", closed.totalAssets());
        assertMoney("50.00", closed.totalLiabilities());
        assertMoney("2050.00", closed.equity());
        assertMoney("250.00", closed.periodExpense());
        assertMoney("100.00", closed.depreciation());
        assertMoney("3600.00", closed.worldCash());

        f = accept(f, ManufacturingModel.collectReceivable(
                f.state(), 35 * DAY, COMPANY, "AR-1", money("2000.00"), f.accounts()));
        assertMoney("1200.00", cash(f, MFG_ACC));
        assertMoney("800.00", cash(f, CUS_ACC));
        assertMoney("0.00", f.state().receivables().get("AR-1").outstanding());
        assertEquals(ReceivableStatus.COLLECTED, f.state().receivables().get("AR-1").status());

        f = accept(f, ManufacturingModel.payExpenseBill(
                f.state(), 35 * DAY, COMPANY, 0, money("1200.00"), f.accounts()));
        assertMoney("1150.00", cash(f, MFG_ACC));
        assertMoney("550.00", cash(f, OPS_ACC));
        assertMoney("0.00", f.state().expenseBills().get("BILL-0").outstanding());

        Report finalReport = ManufacturingModel.validate(f.state(), f.accounts());
        assertMoney("50.00", finalReport.cumulativeProfit(), "付清应付不重复记费用");
        assertMoney("2050.00", finalReport.totalAssets());
        assertMoney("0.00", finalReport.totalLiabilities());
        assertMoney("2050.00", finalReport.equity());
        assertMoney("3600.00", finalReport.worldCash());
        assertMoney("600.00", cash(f, SUP_ACC));
        assertMoney("800.00", cash(f, CUS_ACC));
        assertMoney("550.00", cash(f, OPS_ACC));
        assertMoney("500.00", cash(f, INV_ACC));
    }

    @Test
    @DisplayName("采购容量按同一实验日的已订 PO 派生，次日重置；非法数量零副作用")
    void purchaseCapacityDerivesFromSameDayOrders() {
        Fixture f = opening();

        assertUntouched(f, ManufacturingModel.placePurchaseOrder(
                f.state(), 0, COMPANY, "PO-1", 0, f.accounts()), "INVALID_QUANTITY");
        assertUntouched(f, ManufacturingModel.placePurchaseOrder(
                f.state(), 0, COMPANY, "PO-1", 21, f.accounts()), "SUPPLIER_CAPACITY_EXCEEDED");

        f = accept(f, ManufacturingModel.placePurchaseOrder(
                f.state(), 0, COMPANY, "PO-1", 20, f.accounts()));
        assertUntouched(f, ManufacturingModel.placePurchaseOrder(
                f.state(), 0, COMPANY, "PO-2", 1, f.accounts()), "SUPPLIER_CAPACITY_EXCEEDED");
        assertFalse(f.state().usedIds().contains("PO-2"), "被拒的下单不得占用标识");

        f = accept(f, ManufacturingModel.placePurchaseOrder(
                f.state(), DAY, COMPANY, "PO-3", 20, f.accounts()));
        assertEquals(20L, f.state().purchaseOrders().get("PO-3").quantity());
    }

    @Test
    @DisplayName("到货只按注入的可用现金付款，付不起转 FAILED_NO_CASH 且资产不变")
    void arrivalUsesInjectedAvailableCashAndFailsCleanly() {
        Fixture f = opening();
        Fixture ordered = accept(f, ManufacturingModel.placePurchaseOrder(
                f.state(), 0, COMPANY, "PO-1", 10, f.accounts()));

        BigDecimal gross = cash(ordered, MFG_ACC);
        assertMoney("1000.00", gross);
        assertThrows(IllegalArgumentException.class, () -> ManufacturingModel.receivePurchaseOrder(
                        ordered.state(), DAY, COMPANY, "PO-1", gross.add(money("0.01")), ordered.accounts()),
                "可用现金不能大于账面余额");
        assertThrows(IllegalArgumentException.class, () -> ManufacturingModel.receivePurchaseOrder(
                        ordered.state(), DAY, COMPANY, "PO-1", money("-0.01"), ordered.accounts()),
                "可用现金不能为负");

        assertUntouched(ordered, ManufacturingModel.receivePurchaseOrder(
                ordered.state(), DAY - 1, COMPANY, "PO-1", gross, ordered.accounts()), "PURCHASE_ORDER_NOT_DUE");
        assertUntouched(ordered, ManufacturingModel.receivePurchaseOrder(
                ordered.state(), DAY, COMPANY, "MISSING", gross, ordered.accounts()), "UNKNOWN_PURCHASE_ORDER");

        Fixture paid = accept(ordered, ManufacturingModel.receivePurchaseOrder(
                ordered.state(), DAY, COMPANY, "PO-1", money("500.00"), ordered.accounts()));
        assertMoney("500.00", cash(paid, MFG_ACC));

        assertUntouched(paid, ManufacturingModel.receivePurchaseOrder(
                paid.state(), DAY, COMPANY, "PO-1", money("500.00"), paid.accounts()),
                "PURCHASE_ORDER_STATE_INVALID");
    }

    @Test
    @DisplayName("生产批量为 10 的倍数且不超 20，同刻只有一批，需原料与可用现金")
    void productionRejectsInvalidBatchesAndOneWip() {
        Fixture raw = withRawMaterials();
        State state = raw.state();

        assertUntouched(raw, ManufacturingModel.startProduction(
                state, DAY, COMPANY, "B-1", 7, money("500.00"), raw.accounts()), "INVALID_BATCH_SIZE");
        assertUntouched(raw, ManufacturingModel.startProduction(
                state, DAY, COMPANY, "B-1", 30, money("500.00"), raw.accounts()), "INVALID_BATCH_SIZE");
        assertUntouched(raw, ManufacturingModel.startProduction(
                state, DAY, COMPANY, "B-1", 20, money("500.00"), raw.accounts()), "INSUFFICIENT_MATERIAL");
        assertUntouched(raw, ManufacturingModel.startProduction(
                state, DAY, COMPANY, "B-1", 10, money("299.00"), raw.accounts()), "INSUFFICIENT_CASH");

        Fixture started = accept(raw, ManufacturingModel.startProduction(
                state, DAY, COMPANY, "B-1", 10, money("500.00"), raw.accounts()));
        assertUntouched(started, ManufacturingModel.startProduction(
                started.state(), DAY, COMPANY, "B-1", 10, money("200.00"), started.accounts()), "ID_REUSED");
        assertUntouched(started, ManufacturingModel.startProduction(
                started.state(), 2 * DAY, COMPANY, "B-2", 10, money("200.00"), started.accounts()),
                "BATCH_IN_PROGRESS");
    }

    @Test
    @DisplayName("完工必须到期且只能一次，重复完成不再产出")
    void completionIsDueAndProducesOnce() {
        Fixture running = withProductionInProgress();

        assertUntouched(running, ManufacturingModel.completeProduction(
                running.state(), 2 * DAY - 1, COMPANY, "B-1", running.accounts()), "BATCH_NOT_DUE");
        assertUntouched(running, ManufacturingModel.completeProduction(
                running.state(), 2 * DAY, COMPANY, "MISSING", running.accounts()), "UNKNOWN_BATCH");

        Fixture done = accept(running, ManufacturingModel.completeProduction(
                running.state(), 2 * DAY, COMPANY, "B-1", running.accounts()));
        assertEquals(10L, done.state().finishedGoods().quantity());
        assertMoney("800.00", done.state().finishedGoods().amount());

        assertUntouched(done, ManufacturingModel.completeProduction(
                done.state(), 3 * DAY, COMPANY, "B-1", done.accounts()), "BATCH_STATE_INVALID");
        assertEquals(10L, done.state().finishedGoods().quantity(), "重复完成不得再次产出");
    }

    @Test
    @DisplayName("合同受需求 50、预算 1500、单价 130 与同期间交付约束")
    void contractLimitsDemandBudgetPriceAndPeriod() {
        Fixture f = opening();

        assertUntouched(f, ManufacturingModel.signContract(
                f.state(), 0, COMPANY, "CON-1", 0, money("100.00"), DAY, f.accounts()), "INVALID_QUANTITY");
        assertUntouched(f, ManufacturingModel.signContract(
                f.state(), 0, COMPANY, "CON-1", 10, money("0.00"), DAY, f.accounts()), "INVALID_PRICE");
        assertUntouched(f, ManufacturingModel.signContract(
                f.state(), 0, COMPANY, "CON-1", 10, money("130.01"), DAY, f.accounts()), "PRICE_ABOVE_CAP");
        assertUntouched(f, ManufacturingModel.signContract(
                f.state(), 0, COMPANY, "CON-1", 10, money("120.00"), 30 * DAY, f.accounts()),
                "CONTRACT_CROSS_PERIOD_UNSUPPORTED");
        assertUntouched(f, ManufacturingModel.signContract(
                f.state(), 0, COMPANY, "CON-1", 10, money("120.00"), 0, f.accounts()),
                "INVALID_PROMISED_DELIVERY");

        f = accept(f, ManufacturingModel.signContract(
                f.state(), 0, COMPANY, "CON-1", 10, money("130.00"), DAY, f.accounts()));
        assertMoney("1000.00", cash(f, MFG_ACC), "签约不动现金");
        assertEquals(10L, f.state().contracts().get("CON-1").quantity());

        assertUntouched(f, ManufacturingModel.signContract(
                f.state(), 0, COMPANY, "CON-1", 1, money("10.00"), DAY, f.accounts()), "ID_REUSED");
        assertUntouched(f, ManufacturingModel.signContract(
                f.state(), 0, COMPANY, "CON-2", 10, money("130.00"), DAY, f.accounts()), "BUDGET_EXCEEDED");
        assertUntouched(f, ManufacturingModel.signContract(
                f.state(), 0, COMPANY, "CON-2", 41, money("10.00"), DAY, f.accounts()), "DEMAND_EXCEEDED");
    }

    @Test
    @DisplayName("交付需到承诺时点、同期间、有成品且不超合同余量与成品库存")
    void deliveryNeedsDueContractAndFinishedGoods() {
        Fixture ready = withFinishedGoods();

        assertUntouched(ready, ManufacturingModel.deliver(
                ready.state(), 2 * DAY, COMPANY, "CON-1", 10, "AR-1", ready.accounts()), "DELIVERY_NOT_DUE");
        assertUntouched(ready, ManufacturingModel.deliver(
                ready.state(), 3 * DAY, COMPANY, "CON-1", 11, "AR-1", ready.accounts()), "DELIVERY_EXCEEDS_CONTRACT");
        assertUntouched(ready, ManufacturingModel.deliver(
                ready.state(), 30 * DAY, COMPANY, "CON-1", 10, "AR-1", ready.accounts()), "DELIVERY_PERIOD_ENDED");
        assertUntouched(ready, ManufacturingModel.deliver(
                ready.state(), 3 * DAY, COMPANY, "MISSING", 10, "AR-1", ready.accounts()), "UNKNOWN_CONTRACT");
        assertUntouched(ready, ManufacturingModel.deliver(
                ready.state(), 3 * DAY, COMPANY, "CON-1", 10, "PO-1", ready.accounts()), "ID_REUSED");

        Fixture partial = accept(ready, ManufacturingModel.deliver(
                ready.state(), 3 * DAY, COMPANY, "CON-1", 6, "AR-1", ready.accounts()));
        assertMoney("720.00", partial.state().receivables().get("AR-1").amount());
        assertMoney("480.00", partial.state().receivables().get("AR-1").cost());
        assertEquals(4L, partial.state().finishedGoods().quantity());
        assertMoney("320.00", partial.state().finishedGoods().amount());

        Fixture rest = accept(partial, ManufacturingModel.deliver(
                partial.state(), 4 * DAY, COMPANY, "CON-1", 4, "AR-2", partial.accounts()));
        assertEquals(0L, rest.state().finishedGoods().quantity());
        assertMoney("0.00", rest.state().finishedGoods().amount());
        assertEquals(10L, rest.state().contracts().get("CON-1").deliveredQuantity());

        Report report = ManufacturingModel.validate(rest.state(), rest.accounts());
        assertMoney("1200.00", report.revenue());
        assertMoney("800.00", report.costOfGoodsSold());
        assertMoney("1200.00", report.receivableOutstanding());

        assertUntouched(rest, ManufacturingModel.deliver(
                rest.state(), 5 * DAY, COMPANY, "CON-1", 1, "AR-3", rest.accounts()), "DELIVERY_EXCEEDS_CONTRACT");
    }

    @Test
    @DisplayName("未完工不能交付：成品不足时拒绝且不改变合同与应收")
    void deliveryRequiresFinishedGoods() {
        Fixture running = withProductionInProgress();
        Fixture signed = accept(running, ManufacturingModel.signContract(
                running.state(), DAY, COMPANY, "CON-1", 10, money("120.00"), 3 * DAY, running.accounts()));

        assertUntouched(signed, ManufacturingModel.deliver(
                signed.state(), 3 * DAY, COMPANY, "CON-1", 10, "AR-1", signed.accounts()),
                "INSUFFICIENT_FINISHED_GOODS");
    }

    @Test
    @DisplayName("回款需到期、按注入可用现金；不足只标逾期，重试只收一次")
    void collectionOverdueThenCollectsOnce() {
        Fixture delivered = withDeliveredGoods();

        assertUntouched(delivered, ManufacturingModel.collectReceivable(
                delivered.state(), 18 * DAY - 1, COMPANY, "AR-1", money("2000.00"), delivered.accounts()),
                "RECEIVABLE_NOT_DUE");

        Outcome overdue = ManufacturingModel.collectReceivable(
                delivered.state(), 18 * DAY, COMPANY, "AR-1", money("100.00"), delivered.accounts());
        assertFalse(overdue.accepted());
        assertEquals("OVERDUE", overdue.reason());
        assertEquals(delivered.accounts(), overdue.accounts(), "逾期不改现金");
        assertEquals(ReceivableStatus.OVERDUE, overdue.state().receivables().get("AR-1").status());
        assertMoney("1200.00", overdue.state().receivables().get("AR-1").outstanding());
        assertBalance(overdue.state(), overdue.accounts());

        Fixture paid = accept(new Fixture(overdue.state(), overdue.accounts()),
                ManufacturingModel.collectReceivable(overdue.state(), 18 * DAY, COMPANY,
                        "AR-1", money("1200.00"), overdue.accounts()));
        assertEquals(ReceivableStatus.COLLECTED, paid.state().receivables().get("AR-1").status());
        assertMoney("0.00", paid.state().receivables().get("AR-1").outstanding());
        assertMoney("1400.00", cash(paid, MFG_ACC));

        assertUntouched(paid, ManufacturingModel.collectReceivable(
                paid.state(), 35 * DAY, COMPANY, "AR-1", money("2000.00"), paid.accounts()),
                "RECEIVABLE_ALREADY_COLLECTED");
    }

    @Test
    @DisplayName("收款受注入 freeCash 约束，不能花证券订单冻结的钱")
    void collectionCannotSpendFrozenCash() {
        Fixture delivered = withDeliveredGoods();
        assertMoney("2000.00", cash(delivered, CUS_ACC));

        Outcome overdue = ManufacturingModel.collectReceivable(
                delivered.state(), 18 * DAY, COMPANY, "AR-1", money("1199.99"), delivered.accounts());
        assertFalse(overdue.accepted());
        assertEquals("OVERDUE", overdue.reason());
        assertEquals(delivered.accounts(), overdue.accounts());
        assertMoney("2000.00", cash(delivered, CUS_ACC));

        assertThrows(IllegalArgumentException.class, () -> ManufacturingModel.collectReceivable(
                delivered.state(), 18 * DAY, COMPANY, "AR-1", money("2000.01"), delivered.accounts()));
        assertThrows(IllegalArgumentException.class, () -> ManufacturingModel.collectReceivable(
                delivered.state(), 18 * DAY, COMPANY, "AR-1", money("-0.01"), delivered.accounts()));
    }

    @Test
    @DisplayName("期末无现金也确认费用与折旧，未付形成应付")
    void closePeriodAccruesExpenseAndDepreciationWithoutCash() {
        Fixture delivered = withDeliveredGoods();
        Fixture closed = accept(delivered, ManufacturingModel.closePeriod(
                delivered.state(), 30 * DAY, COMPANY, 0, "BILL-0", money("200.00"), delivered.accounts()));
        assertMoney("0.00", cash(closed, MFG_ACC));
        assertMoney("50.00", closed.state().expenseBills().get("BILL-0").outstanding());
        assertMoney("900.00", closed.state().equipment().net());

        Fixture second = accept(closed, ManufacturingModel.closePeriod(
                closed.state(), 60 * DAY, COMPANY, 1, "BILL-1", money("0.00"), closed.accounts()));
        assertMoney("0.00", cash(second, MFG_ACC));
        assertMoney("300.00", second.state().expenseBills().get("BILL-1").outstanding().add(
                second.state().expenseBills().get("BILL-0").outstanding()));
        assertMoney("800.00", second.state().equipment().net());
        assertMoney("250.00", second.state().expenseBills().get("BILL-1").amount());
        assertMoney("0.00", second.state().expenseBills().get("BILL-1").paid());

        Report report = ManufacturingModel.validate(second.state(), second.accounts());
        assertMoney("-300.00", report.cumulativeProfit());
        assertMoney("2000.00", report.totalAssets());
        assertMoney("300.00", report.totalLiabilities());
        assertMoney("1700.00", report.equity());
    }

    @Test
    @DisplayName("关期必须到期末、连续且每期一次；提前或跳期零副作用")
    void closePeriodRequiresEndContinuityAndRunsOnce() {
        Fixture f = opening();
        assertUntouched(f, ManufacturingModel.closePeriod(
                f.state(), 30 * DAY - 1, COMPANY, 0, "BILL-0", money("250.00"), f.accounts()),
                "PERIOD_NOT_ENDED");
        assertUntouched(f, ManufacturingModel.closePeriod(
                f.state(), 60 * DAY, COMPANY, 1, "BILL-1", money("250.00"), f.accounts()),
                "PERIOD_NOT_CONTINUOUS");

        Fixture closed = accept(f, ManufacturingModel.closePeriod(
                f.state(), 30 * DAY, COMPANY, 0, "BILL-0", money("250.00"), f.accounts()));
        Report afterFirst = ManufacturingModel.validate(closed.state(), closed.accounts());
        assertMoney("250.00", afterFirst.periodExpense());
        assertMoney("100.00", afterFirst.depreciation());

        assertUntouched(closed, ManufacturingModel.closePeriod(
                closed.state(), 60 * DAY, COMPANY, 0, "BILL-0", money("250.00"), closed.accounts()),
                "PERIOD_ALREADY_CLOSED");
        assertUntouched(closed, ManufacturingModel.closePeriod(
                closed.state(), 60 * DAY, COMPANY, 0, "BILL-9", money("250.00"), closed.accounts()),
                "PERIOD_ALREADY_CLOSED");

        Report afterReject = ManufacturingModel.validate(closed.state(), closed.accounts());
        assertMoney("-350.00", afterReject.cumulativeProfit(), "重复关期不得再次记费或折旧");
        assertEquals(1, closed.state().expenseBills().size());
    }

    @Test
    @DisplayName("11 个期间折旧折到 0，之后不再为负；每期仍只关一次")
    void elevenPeriodsDepreciateToFloor() {
        Fixture f = opening();
        for (long period = 0; period <= 10; period++) {
            BigDecimal available = cash(f, MFG_ACC).min(money("250.00"));
            f = accept(f, ManufacturingModel.closePeriod(f.state(), (period + 1) * PERIOD,
                    COMPANY, period, "BILL-" + period, available, f.accounts()));
        }
        assertMoney("0.00", f.state().equipment().net());
        assertMoney("1000.00", f.state().equipment().accumulatedDepreciation());

        f = accept(f, ManufacturingModel.closePeriod(
                f.state(), 12 * PERIOD, COMPANY, 11, "BILL-11", money("0.00"), f.accounts()));
        assertMoney("0.00", f.state().equipment().net(), "折旧不得为负");
        assertMoney("1000.00", f.state().equipment().accumulatedDepreciation());
        assertEquals(12, f.state().expenseBills().size());

        Report report = ManufacturingModel.validate(f.state(), f.accounts());
        assertMoney("1000.00", report.depreciation());
        assertMoney("3000.00", report.periodExpense());
        assertMoney("-4000.00", report.cumulativeProfit());
        assertMoney("0.00", report.totalAssets());
        assertMoney("2000.00", report.totalLiabilities());
        assertMoney("-2000.00", report.equity());
    }

    @Test
    @DisplayName("付清费用账单只减负债，不重复记费用；重复或不足不付款")
    void expenseBillPaymentSettlesWithoutSecondExpense() {
        Fixture delivered = withDeliveredGoods();
        Fixture closed = accept(delivered, ManufacturingModel.closePeriod(
                delivered.state(), 30 * DAY, COMPANY, 0, "BILL-0", money("200.00"), delivered.accounts()));
        Fixture collected = accept(closed, ManufacturingModel.collectReceivable(
                closed.state(), 35 * DAY, COMPANY, "AR-1", money("2000.00"), closed.accounts()));

        assertUntouched(collected, ManufacturingModel.payExpenseBill(
                collected.state(), 35 * DAY, COMPANY, 0, money("0.00"), collected.accounts()), "INSUFFICIENT_CASH");

        Fixture paid = accept(collected, ManufacturingModel.payExpenseBill(
                collected.state(), 35 * DAY, COMPANY, 0, money("1200.00"), collected.accounts()));
        assertMoney("1150.00", cash(paid, MFG_ACC));
        assertMoney("550.00", cash(paid, OPS_ACC));
        assertMoney("0.00", paid.state().expenseBills().get("BILL-0").outstanding());

        Report report = ManufacturingModel.validate(paid.state(), paid.accounts());
        assertMoney("250.00", report.periodExpense(), "付款不得再记费用");
        assertMoney("50.00", report.cumulativeProfit());
        assertMoney("0.00", report.totalLiabilities());

        assertUntouched(paid, ManufacturingModel.payExpenseBill(
                paid.state(), 40 * DAY, COMPANY, 0, money("1200.00"), paid.accounts()),
                "EXPENSE_BILL_ALREADY_PAID");
        assertUntouched(paid, ManufacturingModel.payExpenseBill(
                paid.state(), 40 * DAY, COMPANY, 9, money("1200.00"), paid.accounts()),
                "UNKNOWN_EXPENSE_BILL");
    }

    @Test
    @DisplayName("原料多次采购后按账面金额分配，剩余库存金额不漂移")
    void materialAllocationFollowsBookAmount() {
        Fixture f = withDeliveredGoods();
        f = accept(f, ManufacturingModel.collectReceivable(
                f.state(), 18 * DAY, COMPANY, "AR-1", money("2000.00"), f.accounts()));
        assertMoney("1400.00", cash(f, MFG_ACC));

        f = accept(f, ManufacturingModel.placePurchaseOrder(f.state(), 18 * DAY, COMPANY, "PO-2", 20, f.accounts()));
        f = accept(f, ManufacturingModel.receivePurchaseOrder(
                f.state(), 19 * DAY, COMPANY, "PO-2", money("1400.00"), f.accounts()));
        assertMoney("400.00", cash(f, MFG_ACC));
        assertEquals(20L, f.state().rawMaterials().quantity());
        assertMoney("1000.00", f.state().rawMaterials().amount());

        f = accept(f, ManufacturingModel.startProduction(
                f.state(), 19 * DAY, COMPANY, "B-2", 10, money("400.00"), f.accounts()));
        assertEquals(10L, f.state().rawMaterials().quantity());
        assertMoney("500.00", f.state().rawMaterials().amount());
        assertMoney("800.00", f.state().workInProgress().amount());
        assertMoney("100.00", cash(f, MFG_ACC));

        Report report = ManufacturingModel.validate(f.state(), f.accounts());
        assertUnitCost("50.0000", report.rawUnitCost());
    }

    @Test
    @DisplayName("状态深不可变、动作不改传入账户表，JSON 往返完全相等")
    void stateIsDeeplyImmutableAndJsonRoundTrips() throws Exception {
        Fixture f = withDeliveredGoods();
        State state = f.state();
        Map<String, CashAccount> accountsBefore = new LinkedHashMap<>(f.accounts());

        assertThrows(UnsupportedOperationException.class, () -> state.purchaseOrders().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.usedIds().add("X"));
        assertThrows(UnsupportedOperationException.class, () -> state.contracts().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.receivables().clear());

        ManufacturingModel.Outcome ignored = ManufacturingModel.completeProduction(
                state, 99 * DAY, COMPANY, "MISSING", f.accounts());
        assertFalse(ignored.accepted());
        assertEquals(accountsBefore, f.accounts(), "纯计算不得修改调用方的账户表");
        assertEquals(state, f.state());

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(state);
        State back = mapper.readValue(json, State.class);
        assertEquals(state, back);
        assertEquals(state.usedIds(), back.usedIds());
        assertMoney("1200.00", back.receivables().get("AR-1").amount());
    }

    @Test
    @DisplayName("采购、合同、交付、账单标识跨类型不可复用；越权动作不产生副作用")
    void idsCannotBeReusedAcrossRecordsAndActionsNeedCompanyOwner() {
        Fixture f = opening();

        assertUntouched(f, ManufacturingModel.placePurchaseOrder(
                f.state(), 0, INVESTOR, "PO-1", 10, f.accounts()), "NOT_AUTHORIZED");
        assertUntouched(f, ManufacturingModel.signContract(
                f.state(), 0, INVESTOR, "CON-1", 10, money("120.00"), DAY, f.accounts()), "NOT_AUTHORIZED");

        Fixture ordered = accept(f, ManufacturingModel.placePurchaseOrder(
                f.state(), 0, COMPANY, "ID-1", 10, f.accounts()));
        assertUntouched(ordered, ManufacturingModel.signContract(
                ordered.state(), 0, COMPANY, "ID-1", 10, money("120.00"), DAY, ordered.accounts()), "ID_REUSED");
        assertUntouched(ordered, ManufacturingModel.closePeriod(
                ordered.state(), 30 * DAY, COMPANY, 0, "ID-1", money("250.00"),
                ordered.accounts()), "ID_REUSED");

        Fixture ready = withFinishedGoods();
        assertUntouched(ready, ManufacturingModel.deliver(
                ready.state(), 3 * DAY, COMPANY, "CON-1", 10, "PO-1", ready.accounts()), "ID_REUSED");

        Fixture delivered = withDeliveredGoods();
        assertUntouched(delivered, ManufacturingModel.collectReceivable(
                delivered.state(), 18 * DAY, INVESTOR, "AR-1", money("2000.00"), delivered.accounts()),
                "NOT_AUTHORIZED");
        assertUntouched(delivered, ManufacturingModel.payExpenseBill(
                delivered.state(), 18 * DAY, INVESTOR, 0, money("2000.00"), delivered.accounts()),
                "NOT_AUTHORIZED");
    }

    @Test
    @DisplayName("账户结构被破坏时 validate 拒绝，而不是静默通过")
    void validateRejectsTamperedAccounts() {
        Fixture f = opening();
        Map<String, CashAccount> wrong = new LinkedHashMap<>(f.accounts());
        wrong.put(MFG_ACC, new CashAccount("OTHER", money("1000.00"), "历史禀赋"));
        assertThrows(IllegalArgumentException.class, () -> ManufacturingModel.validate(f.state(), wrong));

        Map<String, CashAccount> missing = new LinkedHashMap<>(f.accounts());
        missing.remove(SUP_ACC);
        assertThrows(IllegalArgumentException.class, () -> ManufacturingModel.validate(f.state(), missing));

        Map<String, CashAccount> secondCompanyAccount = new LinkedHashMap<>(f.accounts());
        secondCompanyAccount.put("ACC-MFG-SECOND", new CashAccount(COMPANY, money("0.00"), "历史禀赋"));
        assertThrows(IllegalArgumentException.class,
                () -> ManufacturingModel.validate(f.state(), secondCompanyAccount),
                "校验绑定时也必须拒绝公司所有者的第二个账户");
    }

    // ---------------------------------------------------------------- helpers

    private static Fixture opening() {
        Map<String, CashAccount> accounts = openingAccounts();
        State state = ManufacturingModel.open(OPENING, accounts);
        return new Fixture(state, accounts);
    }

    private static Map<String, CashAccount> openingAccounts() {
        Map<String, CashAccount> accounts = new LinkedHashMap<>();
        accounts.put(MFG_ACC, new CashAccount(COMPANY, money("1000.00"), "历史禀赋"));
        accounts.put(SUP_ACC, new CashAccount(SUPPLIER, money("100.00"), "历史禀赋"));
        accounts.put(CUS_ACC, new CashAccount(CUSTOMER, money("2000.00"), "历史禀赋"));
        accounts.put(OPS_ACC, new CashAccount(OPS, money("0.00"), "历史禀赋"));
        accounts.put(INV_ACC, new CashAccount(INVESTOR, money("500.00"), "历史禀赋"));
        return accounts;
    }

    private static Fixture withRawMaterials() {
        Fixture f = opening();
        f = accept(f, ManufacturingModel.placePurchaseOrder(f.state(), 0, COMPANY, "PO-1", 10, f.accounts()));
        return accept(f, ManufacturingModel.receivePurchaseOrder(
                f.state(), DAY, COMPANY, "PO-1", money("500.00"), f.accounts()));
    }

    private static Fixture withProductionInProgress() {
        Fixture f = withRawMaterials();
        return accept(f, ManufacturingModel.startProduction(
                f.state(), DAY, COMPANY, "B-1", 10, money("500.00"), f.accounts()));
    }

    private static Fixture withFinishedGoods() {
        Fixture f = withProductionInProgress();
        f = accept(f, ManufacturingModel.completeProduction(f.state(), 2 * DAY, COMPANY, "B-1", f.accounts()));
        return accept(f, ManufacturingModel.signContract(
                f.state(), 2 * DAY, COMPANY, "CON-1", 10, money("120.00"), 3 * DAY, f.accounts()));
    }

    private static Fixture withDeliveredGoods() {
        Fixture f = withFinishedGoods();
        return accept(f, ManufacturingModel.deliver(
                f.state(), 3 * DAY, COMPANY, "CON-1", 10, "AR-1", f.accounts()));
    }

    private static Fixture accept(Fixture f, Outcome outcome) {
        assertTrue(outcome.accepted(), () -> "应接受但被拒绝：" + outcome.reason());
        assertBalance(outcome.state(), outcome.accounts());
        return new Fixture(outcome.state(), outcome.accounts());
    }

    private static void assertUntouched(Fixture f, Outcome outcome, String reason) {
        assertFalse(outcome.accepted(), () -> "应拒绝但被接受：" + reason);
        assertEquals(reason, outcome.reason());
        assertEquals(f.state(), outcome.state(), "拒绝不得改变状态：" + reason);
        assertEquals(f.accounts(), outcome.accounts(), "拒绝不得改变现金：" + reason);
    }

    private static void assertBalance(State state, Map<String, CashAccount> accounts) {
        Report report = ManufacturingModel.validate(state, accounts);
        assertTrue(report.balanced(), "资产负债表必须平衡");
        assertMoney("3600.00", report.worldCash(), "世界现金必须守恒");
    }

    private static BigDecimal cash(Fixture f, String accountId) {
        CashAccount account = f.accounts().get(accountId);
        assertNotNull(account, "缺少账户：" + accountId);
        return account.cash();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertMoney(expected, actual, "");
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message);
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> message + " 金额不符：期望 " + expected + "，实际 " + actual);
        assertEquals(2, actual.scale(), () -> message + " 金额必须保留 2 位：" + actual);
    }

    private static void assertUnitCost(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "单位成本不符：期望 " + expected + "，实际 " + actual);
        assertEquals(4, actual.scale(), () -> "单位成本必须保留 4 位：" + actual);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }
}
