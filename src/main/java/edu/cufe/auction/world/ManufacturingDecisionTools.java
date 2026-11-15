package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 制造经理的有界决定工具；身份、对手和工作时长由世界绑定，不由模型声明。 */
public final class ManufacturingDecisionTools {
    private ManufacturingDecisionTools() { }
    /** 制造经营工具名。 */
    public static final String ACT = "manufacturing.act";
    /** 当前实验动作工时；物理到货与生产周期另计。 */
    public static final long ACTION_MILLIS = 1_000;

    /**
     * @param action SIGN/PURCHASE_ORDER/PRODUCTION/DELIVERY/COLLECT/PAY_BILL
     * @param businessId 业务编号
     * @param resultId 交付应收编号，其余动作留空
     * @param quantity 签约、采购、生产或交付数量
     * @param unitPrice 签约单价，其余动作填零
     * @param promisedDeliveryAt 签约交付时间，其余动作填零
     * @param period 费用所属期，其余动作填零
     * @param fundSource 经营现金或本公司银行存款；无付款动作留空
     */
    public record Draft(String action, String businessId, String resultId, long quantity,
                        BigDecimal unitPrice, long promisedDeliveryAt, long period, String fundSource) {
        /** 拒绝未知动作和缺失的明确字段。 */
        public Draft {
            if (action == null || !Set.of("SIGN", "PURCHASE_ORDER", "PRODUCTION", "DELIVERY",
                    "COLLECT", "PAY_BILL").contains(action) || businessId == null || resultId == null
                    || unitPrice == null || fundSource == null || quantity < 0 || period < 0
                    || promisedDeliveryAt < 0) throw new IllegalArgumentException("制造工具参数非法");
        }
    }

    /** @param base 其它工具原冻结配置 @return 仅显式新场景启用的制造工具配置 */
    public static DecisionModel.Config config(DecisionModel.Config base) {
        Map<String, Long> tools = new TreeMap<>(base.actionMillisByTool());
        tools.put(ACT, ACTION_MILLIS);
        return new DecisionModel.Config(base.providerKind(), base.providerId(), base.pricingVersion(),
                base.thinkMillisByWake(), tools, base.limits(), base.budget(), base.profile());
    }

    /**
     * 构造经理本公司经营观察；不包含客户内部余额、未来结果、全局位置或他人回报。
     * @param ledger 本公司唯一经营状态
     * @param accounts 本帧账户
     * @param board 本公司董事会
     * @param now 当前世界时点
     * @param completeTerms 是否为显式 v2 观察画像
     * @return 固定键和顺序的经营观察
     */
    static Map<String, Object> observation(ManufacturingModel.State ledger,
            Map<String, CashLedger.CashAccount> accounts, BoardRun board, long now, boolean completeTerms) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("companyId", board.board().charter().companyPartyId());
        view.put("cash", accounts.get(ledger.opening().companyAccountId()).cash().toPlainString());
        view.put("cashFund", ManufacturingFunding.cashToken(ledger.opening().companyAccountId()));
        view.put("rawQuantity", Long.toString(ledger.rawMaterials().quantity()));
        view.put("finishedQuantity", Long.toString(ledger.finishedGoods().quantity()));
        view.put("actions", List.of("SIGN", "PURCHASE_ORDER", "PRODUCTION", "DELIVERY", "COLLECT", "PAY_BILL"));
        if (completeTerms) {
            view.put("newBusinessIds", new TreeMap<>(Map.of("purchaseOrder", nextId("manager-po-", ledger.usedIds()),
                    "productionBatch", nextId("manager-batch-", ledger.usedIds()),
                    "salesContract", nextId("manager-contract-", ledger.usedIds()),
                    "receivable", nextId("manager-receivable-", ledger.usedIds()))));
            Map<String, String> terms = new LinkedHashMap<>();
            terms.put("rawUnitPrice", ManufacturingModel.RAW_UNIT_PRICE.toPlainString());
            terms.put("supplierDailyCapacity", Long.toString(ManufacturingModel.SUPPLIER_DAILY_CAPACITY));
            terms.put("rawLeadMillis", Long.toString(ManufacturingModel.DAY_MILLIS));
            terms.put("laborUnitPrice", ManufacturingModel.LABOR_UNIT_PRICE.toPlainString());
            terms.put("productionMillis", Long.toString(ManufacturingModel.DAY_MILLIS));
            terms.put("batchQuantityOptions", "10,20");
            terms.put("customerPeriodDemandLimit", Long.toString(ManufacturingModel.CUSTOMER_PERIOD_DEMAND));
            terms.put("customerPeriodContractValueLimit", ManufacturingModel.CUSTOMER_PERIOD_BUDGET.toPlainString());
            terms.put("contractUnitPriceCap", ManufacturingModel.CONTRACT_PRICE_CAP.toPlainString());
            terms.put("receivableTermMillis", Long.toString(ManufacturingModel.RECEIVABLE_TERM_MILLIS));
            terms.put("periodExpense", ManufacturingModel.PERIOD_EXPENSE.toPlainString());
            terms.put("periodDepreciationLimit", ManufacturingModel.PERIOD_DEPRECIATION.toPlainString());
            long period = now / ManufacturingModel.PERIOD_MILLIS;
            terms.put("period", Long.toString(period));
            long periodEnd = Math.multiplyExact(period + 1, ManufacturingModel.PERIOD_MILLIS);
            terms.put("deliveryDeadlineExclusive", Long.toString(periodEnd));
            terms.put("suggestedPromisedDeliveryAt", Long.toString(Math.min(periodEnd - 1,
                    Math.addExact(now, 7 * ManufacturingModel.DAY_MILLIS))));
            view.put("operatingTerms", terms);
        }
        view.put("purchaseOrders", new TreeMap<>(ledger.purchaseOrders()));
        view.put("batches", new TreeMap<>(ledger.productionBatches()));
        view.put("contracts", new TreeMap<>(ledger.contracts()));
        view.put("receivables", new TreeMap<>(ledger.receivables()));
        view.put("bills", new TreeMap<>(ledger.expenseBills()));
        Map<String, String> budgets = new TreeMap<>();
        Map<String, String> available = new TreeMap<>();
        for (var category : List.of(BoardModel.BudgetCategory.PROCUREMENT, BoardModel.BudgetCategory.PRODUCTION_LABOR)) {
            var budget = board.board().budget(now / ManufacturingModel.PERIOD_MILLIS, category);
            if (budget != null) {
                budgets.put(category.name(), budget.cap().toPlainString());
                available.put(category.name(), BoardModel.availableBudget(board.board(),
                        now / ManufacturingModel.PERIOD_MILLIS, category).toPlainString());
            }
        }
        view.put("approvedBudgetCaps", budgets);
        view.put("remainingBudgets", available);
        view.put("rules", "金额与数量按精确值处理；签约不收款，采购到货才付款，生产开始付人工。"
                + "预算上限不是可用余额；实际付款时再次检查预算、可用资金和权限。只可选择本公司资金，不可自报收款方。"
                + (completeTerms ? "newBusinessIds是当前可用编号建议，仍会在执行时检查冲突；只有新增业务使用新编号，其它动作引用已有业务。"
                + "operatingTerms是本公司可见的实验供应/客户条款，不是对方内部余额或未来付款保证。"
                + "生产每件消耗一件原料、只许一批在制。合同限本期交付；建议交期不代表已有产能承诺。" : "")
                + "未用字段填空串或零。可等待；不保证订单、资金或客户付款成功。");
        return view;
    }

    private static String nextId(String prefix, Set<String> used) {
        long sequence = 1;
        while (used.contains(prefix + sequence)) sequence++;
        return prefix + sequence;
    }
}
