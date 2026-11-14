package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.bank.FundState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * M3a 制造经营实验核：不可变状态、固定实验规则与纯候选结果。
 *
 * <p><b>能力边界</b>：本类只做制造公司的采购、生产、交付、回款、期末费用与折旧的纯计算，
 * 不包含 I/O、世界日志、运行时钟、运行时桥、董事会、银行、分红、角色授权或证券撮合。
 * 它不是完整公司，也不是已连通股市的公司—交易链；调用方必须在世界日志持久确认之后才发布候选状态。</p>
 *
 * <p><b>现金唯一权威</b>：现金余额只存在于调用方传入的 {@code Map<String, CashAccount>}；
 * 本类不保存第二份现金、预付账款、信用卡或应付工资。每一步资金转移都调用同包的
 * {@link CashLedger#computeTransfer}，因此世界现金总额在已提交状态之间保持不变。</p>
 *
 * <p><b>可用现金</b>：所有付款动作都接收调用方注入的 {@code availableCash}（未来运行时从同一截点
 * 的证券订单冻结派生），并要求 {@code 0 <= availableCash <= 账户账面余额}。纯核测试可传账面余额或更小值；
 * 付款不会花掉被证券订单冻结的钱，也不会透支。</p>
 *
 * <p><b>时间口径</b>：实验日固定为 {@code 86_400_000} 模拟毫秒，期间固定为 30 个实验日；
 * 所有到期时间都由传入的模拟毫秒精确相加得到，不读取系统日历、时区或真实交易日。</p>
 *
 * <p><b>成本口径</b>：存货以「数量 + 账面总额」为权威；出库按账面金额与数量比例分配、
 * 保留 2 位并 HALF_UP，最后一次出清精确结尽尾差。单位成本只作为 4 位派生展示值，
 * 不用目录价覆盖历史成本。采购到货、人工与客户合同都是固定实验参数，不是原型公司财务数据。</p>
 *
 * <p><b>失败语义</b>：业务拒绝返回 {@code accepted=false}，且候选状态通常与原状态相等；
 * 但到货付不起会留下 {@code FAILED_NO_CASH}、回款不足会留下 {@code OVERDUE} 元数据。
 * 这两种候选的资产与现金都不变，桥接层不能因为 {@code accepted=false} 就丢弃候选状态。</p>
 *
 * <p><b>授权夹具</b>：动作的 {@code actorOwnerId} 必须等于开局公司所有者；客户聚合、
 * 供应商容量与自动接受合同属于可信实验夹具，不代表角色授权已经实现。</p>
 */
public final class ManufacturingModel {

    /** 一个实验日的模拟毫秒数；不读取系统日历。 */
    public static final long DAY_MILLIS = 86_400_000L;

    /** 一个期间的模拟毫秒数（固定 30 个实验日）。 */
    public static final long PERIOD_MILLIS = 30L * DAY_MILLIS;

    /** 客户信用回款期限（交付后 15 个实验日）。 */
    public static final long RECEIVABLE_TERM_MILLIS = 15L * DAY_MILLIS;

    /** 供应商原料目录单价。 */
    public static final BigDecimal RAW_UNIT_PRICE = new BigDecimal("50.00");

    /** 供应商每个实验日的容量（件）。 */
    public static final long SUPPLIER_DAILY_CAPACITY = 20L;

    /** 生产人工单价（每件，投产时支付并资本化）。 */
    public static final BigDecimal LABOR_UNIT_PRICE = new BigDecimal("30.00");

    /** 客户每个期间的成品需求上限（件）。 */
    public static final long CUSTOMER_PERIOD_DEMAND = 50L;

    /** 客户每个期间的采购预算上限。 */
    public static final BigDecimal CUSTOMER_PERIOD_BUDGET = new BigDecimal("1500.00");

    /** 客户合同单价上限。 */
    public static final BigDecimal CONTRACT_PRICE_CAP = new BigDecimal("130.00");

    /** 每期固定经营费用（人力与场地）。 */
    public static final BigDecimal PERIOD_EXPENSE = new BigDecimal("250.00");

    /** 每期折旧上限；同时不超过设备剩余净值。 */
    public static final BigDecimal PERIOD_DEPRECIATION = new BigDecimal("100.00");

    /** 开局设备原值；作为历史禀赋一次性录入，不在动作里发钱。 */
    public static final BigDecimal EQUIPMENT_ORIGINAL_COST = new BigDecimal("1000.00");

    /** 采购下单成功。 */
    public static final String REASON_PURCHASE_ORDER_PLACED = "PURCHASE_ORDER_PLACED";
    /** 到货并按现金交货成功。 */
    public static final String REASON_GOODS_RECEIVED = "GOODS_RECEIVED";
    /** 投产成功。 */
    public static final String REASON_PRODUCTION_STARTED = "PRODUCTION_STARTED";
    /** 完工成功。 */
    public static final String REASON_PRODUCTION_COMPLETED = "PRODUCTION_COMPLETED";
    /** 合同签约成功。 */
    public static final String REASON_CONTRACT_SIGNED = "CONTRACT_SIGNED";
    /** 交付成功。 */
    public static final String REASON_DELIVERED = "DELIVERED";
    /** 回款成功。 */
    public static final String REASON_PAYMENT_RECEIVED = "PAYMENT_RECEIVED";
    /** 期末关账成功。 */
    public static final String REASON_PERIOD_CLOSED = "PERIOD_CLOSED";
    /** 费用账单付清成功。 */
    public static final String REASON_EXPENSE_BILL_PAID = "EXPENSE_BILL_PAID";
    /** 动作发起人不是公司所有者。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 标识在本模块历史中已被使用。 */
    public static final String REASON_ID_REUSED = "ID_REUSED";
    /** 数量非法。 */
    public static final String REASON_INVALID_QUANTITY = "INVALID_QUANTITY";
    /** 超过供应商当日剩余容量。 */
    public static final String REASON_SUPPLIER_CAPACITY_EXCEEDED = "SUPPLIER_CAPACITY_EXCEEDED";
    /** 没有该采购单。 */
    public static final String REASON_UNKNOWN_PURCHASE_ORDER = "UNKNOWN_PURCHASE_ORDER";
    /** 采购单状态不允许该操作。 */
    public static final String REASON_PURCHASE_ORDER_STATE_INVALID = "PURCHASE_ORDER_STATE_INVALID";
    /** 采购单尚未到货。 */
    public static final String REASON_PURCHASE_ORDER_NOT_DUE = "PURCHASE_ORDER_NOT_DUE";
    /** 可用现金不足。 */
    public static final String REASON_INSUFFICIENT_CASH = "INSUFFICIENT_CASH";
    /** 生产批量不是 10 的倍数或超过 20。 */
    public static final String REASON_INVALID_BATCH_SIZE = "INVALID_BATCH_SIZE";
    /** 同刻已有在制批次。 */
    public static final String REASON_BATCH_IN_PROGRESS = "BATCH_IN_PROGRESS";
    /** 原料不足。 */
    public static final String REASON_INSUFFICIENT_MATERIAL = "INSUFFICIENT_MATERIAL";
    /** 没有该生产批次。 */
    public static final String REASON_UNKNOWN_BATCH = "UNKNOWN_BATCH";
    /** 生产批次状态不允许该操作。 */
    public static final String REASON_BATCH_STATE_INVALID = "BATCH_STATE_INVALID";
    /** 生产批次尚未完工。 */
    public static final String REASON_BATCH_NOT_DUE = "BATCH_NOT_DUE";
    /** 合同单价非法。 */
    public static final String REASON_INVALID_PRICE = "INVALID_PRICE";
    /** 合同单价超过客户上限。 */
    public static final String REASON_PRICE_ABOVE_CAP = "PRICE_ABOVE_CAP";
    /** 承诺交付时点必须晚于签约时刻。 */
    public static final String REASON_INVALID_PROMISED_DELIVERY = "INVALID_PROMISED_DELIVERY";
    /** 首档案不支持跨期间交付。 */
    public static final String REASON_CONTRACT_CROSS_PERIOD_UNSUPPORTED = "CONTRACT_CROSS_PERIOD_UNSUPPORTED";
    /** 超过客户当期需求。 */
    public static final String REASON_DEMAND_EXCEEDED = "DEMAND_EXCEEDED";
    /** 超过客户当期预算。 */
    public static final String REASON_BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    /** 没有该合同。 */
    public static final String REASON_UNKNOWN_CONTRACT = "UNKNOWN_CONTRACT";
    /** 尚未到承诺交付时点。 */
    public static final String REASON_DELIVERY_NOT_DUE = "DELIVERY_NOT_DUE";
    /** 合同所在期间已结束，首档案不允许跨期间交付。 */
    public static final String REASON_DELIVERY_PERIOD_ENDED = "DELIVERY_PERIOD_ENDED";
    /** 交付数量超过合同未交付数量。 */
    public static final String REASON_DELIVERY_EXCEEDS_CONTRACT = "DELIVERY_EXCEEDS_CONTRACT";
    /** 产成品库存不足。 */
    public static final String REASON_INSUFFICIENT_FINISHED_GOODS = "INSUFFICIENT_FINISHED_GOODS";
    /** 没有该应收。 */
    public static final String REASON_UNKNOWN_RECEIVABLE = "UNKNOWN_RECEIVABLE";
    /** 应收尚未到期。 */
    public static final String REASON_RECEIVABLE_NOT_DUE = "RECEIVABLE_NOT_DUE";
    /** 应收已收清，拒绝重复收款。 */
    public static final String REASON_RECEIVABLE_ALREADY_COLLECTED = "RECEIVABLE_ALREADY_COLLECTED";
    /** 客户可用现金不足，应收标记逾期。 */
    public static final String REASON_OVERDUE = "OVERDUE";
    /** 没有该费用账单。 */
    public static final String REASON_UNKNOWN_EXPENSE_BILL = "UNKNOWN_EXPENSE_BILL";
    /** 费用账单已付清。 */
    public static final String REASON_EXPENSE_BILL_ALREADY_PAID = "EXPENSE_BILL_ALREADY_PAID";
    /** 该期间已经关账。 */
    public static final String REASON_PERIOD_ALREADY_CLOSED = "PERIOD_ALREADY_CLOSED";
    /** 期间不连续。 */
    public static final String REASON_PERIOD_NOT_CONTINUOUS = "PERIOD_NOT_CONTINUOUS";
    /** 尚未到该期间期末。 */
    public static final String REASON_PERIOD_NOT_ENDED = "PERIOD_NOT_ENDED";

    /** 资金腿出款主体与业务认定的付款方不一致（真实的钱不在这个主体名下）。 */
    public static final String REASON_FUND_OWNER_MISMATCH = "FUND_OWNER_MISMATCH";

    /** 资金腿收款主体与业务认定的收款方不一致（真实的钱进不了这个主体名下）。 */
    public static final String REASON_PAYEE_MISMATCH = "PAYEE_MISMATCH";

    /** 资金层已有该业务事实的付款记录，而业务状态仍待办：受控拒绝，不把零移动当新付款。 */
    public static final String REASON_FUND_ID_ALREADY_RECORDED = "FUND_ID_ALREADY_RECORDED";

    /** 业务已结算而资金层没有对应成功记录：明示分叉，不当成功重放。 */
    public static final String REASON_FUND_RECORD_DIVERGED = "FUND_RECORD_DIVERGED";

    /** 制造资金腿标识前缀：由业务事实确定性派生，同一事实不可能付第二次。 */
    private static final String LEG_RECEIVE = "mfg-receive:";

    private static final String LEG_LABOR = "mfg-labor:";

    private static final String LEG_COLLECT = "mfg-collect:";

    private static final String LEG_EXPENSE = "mfg-expense:";

    private static final String LEG_BILL = "mfg-bill:";

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private ManufacturingModel() {
    }

    /** 采购单状态。 */
    public enum PurchaseOrderStatus {
        /** 已下单，等待到货。 */
        ORDERED,
        /** 已到货，原料入库且已现金付款。 */
        RECEIVED,
        /** 到期时可用现金不足；不增原料、不记应付。 */
        FAILED_NO_CASH
    }

    /** 生产批次状态。 */
    public enum ProductionStatus {
        /** 在制。 */
        IN_PROGRESS,
        /** 已完工并转入产成品。 */
        FINISHED
    }

    /** 应收账款状态。 */
    public enum ReceivableStatus {
        /** 未到期或到期未提示逾期。 */
        OPEN,
        /** 已到期但客户可用现金不足。 */
        OVERDUE,
        /** 已收清，不可再次收款。 */
        COLLECTED
    }

    /**
     * 开局账户绑定：公司所有者与四个对手方账户。
     *
     * @param companyOwnerId   公司财产主体标识
     * @param companyAccountId 公司经营现金账户
     * @param supplierAccountId 供应商聚合账户
     * @param customerAccountId 客户聚合账户
     * @param opsAccountId     人力与场地账户
     */
    public record Opening(String companyOwnerId, String companyAccountId, String supplierAccountId,
                          String customerAccountId, String opsAccountId) {

        /** 校验标识非空且四个账户互不相同。 */
        public Opening {
            requireText(companyOwnerId, "公司所有者");
            requireText(companyAccountId, "公司账户");
            requireText(supplierAccountId, "供应商账户");
            requireText(customerAccountId, "客户账户");
            requireText(opsAccountId, "人力与场地账户");
            if (Set.of(companyAccountId, supplierAccountId, customerAccountId, opsAccountId).size() != 4) {
                throw new IllegalArgumentException("开局账户标识必须互不相同");
            }
        }
    }

    /**
     * 存货：数量与账面总额是权威，单位成本只是派生展示值。
     *
     * @param quantity 数量，非负
     * @param amount   账面总额，非负且精确到分；数量为 0 时必须为 0
     */
    public record Inventory(long quantity, BigDecimal amount) {

        /** 校验数量与金额；数量归零时账面金额必须同步结清。 */
        public Inventory {
            if (quantity < 0) {
                throw new IllegalArgumentException("存货数量不能为负：" + quantity);
            }
            amount = CashLedger.money(amount);
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("存货金额不能为负：" + amount);
            }
            if (quantity == 0 && amount.signum() != 0) {
                throw new IllegalArgumentException("数量为 0 时存货金额必须为 0：" + amount);
            }
        }

        /**
         * 派生单位成本（4 位，仅展示，不作为记账依据）。
         *
         * @return 数量为 0 时返回 0.0000
         */
        public BigDecimal unitCost() {
            if (quantity == 0) {
                return new BigDecimal("0.0000");
            }
            return amount.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
        }
    }

    /**
     * 设备：原值与累计折旧，净值由两者派生。
     *
     * @param originalCost            原值
     * @param accumulatedDepreciation 累计折旧，不超过原值
     */
    public record Equipment(BigDecimal originalCost, BigDecimal accumulatedDepreciation) {

        /** 校验原值、累计折旧与净值边界。 */
        public Equipment {
            originalCost = CashLedger.money(originalCost);
            accumulatedDepreciation = CashLedger.money(accumulatedDepreciation);
            if (originalCost.signum() < 0) {
                throw new IllegalArgumentException("设备原值不能为负");
            }
            if (accumulatedDepreciation.signum() < 0
                    || accumulatedDepreciation.compareTo(originalCost) > 0) {
                throw new IllegalArgumentException("累计折旧必须在 0 与原值之间");
            }
        }

        /**
         * 设备净值。
         *
         * @return 原值减累计折旧
         */
        public BigDecimal net() {
            return originalCost.subtract(accumulatedDepreciation);
        }
    }

    /**
     * 采购单。
     *
     * @param purchaseOrderId 唯一标识，运行内不复用
     * @param quantity        数量
     * @param unitPrice       下单时锁定的目录单价
     * @param orderedAt       下单模拟毫秒
     * @param arrivalAt       到货模拟毫秒（下单时刻 + 1 日）
     * @param status          状态
     */
    public record PurchaseOrder(String purchaseOrderId, long quantity, BigDecimal unitPrice,
                                long orderedAt, long arrivalAt, PurchaseOrderStatus status) {

        /** 校验数量、单价、时间与状态。 */
        public PurchaseOrder {
            requireText(purchaseOrderId, "采购单标识");
            if (quantity <= 0) {
                throw new IllegalArgumentException("采购数量必须为正");
            }
            unitPrice = CashLedger.money(unitPrice);
            if (unitPrice.signum() <= 0) {
                throw new IllegalArgumentException("采购单价必须为正");
            }
            if (orderedAt < 0 || arrivalAt <= orderedAt) {
                throw new IllegalArgumentException("采购时间不合法");
            }
            Objects.requireNonNull(status, "采购单状态");
        }
    }

    /**
     * 生产批次；完工后保留标识与成本来源，不能换任务重复产出。
     *
     * @param batchId      唯一标识，运行内不复用
     * @param quantity     批量
     * @param startedAt    投产模拟毫秒
     * @param completesAt  完工模拟毫秒（投产时刻 + 1 日）
     * @param materialCost 按账面金额分配的材料成本
     * @param laborCost    资本化的人工成本
     * @param status       状态
     */
    public record ProductionBatch(String batchId, long quantity, long startedAt, long completesAt,
                                  BigDecimal materialCost, BigDecimal laborCost,
                                  ProductionStatus status) {

        /** 校验批量、时间、成本与状态。 */
        public ProductionBatch {
            requireText(batchId, "生产批次标识");
            if (quantity <= 0) {
                throw new IllegalArgumentException("生产批量必须为正");
            }
            if (startedAt < 0 || completesAt <= startedAt) {
                throw new IllegalArgumentException("生产时间不合法");
            }
            materialCost = CashLedger.money(materialCost);
            laborCost = CashLedger.money(laborCost);
            if (materialCost.signum() < 0 || laborCost.signum() < 0) {
                throw new IllegalArgumentException("生产成本不能为负");
            }
            Objects.requireNonNull(status, "生产批次状态");
        }

        /**
         * 批次总成本（材料 + 人工）。
         *
         * @return 精确到分的总成本
         */
        public BigDecimal totalCost() {
            return materialCost.add(laborCost);
        }
    }

    /**
     * 客户合同：签约只占用需求与预算，不动现金、库存或收入。
     *
     * @param contractId          唯一标识，运行内不复用
     * @param quantity            合同数量
     * @param unitPrice           合同单价
     * @param signedAt            签约模拟毫秒
     * @param promisedDeliveryAt  承诺交付模拟毫秒，必须晚于签约且在同一期间内
     * @param period              签约所在实验期间序号
     * @param deliveredQuantity   已交付数量
     */
    public record SalesContract(String contractId, long quantity, BigDecimal unitPrice, long signedAt,
                                long promisedDeliveryAt, long period, long deliveredQuantity) {

        /** 校验合同时间、期间、单价与已交付数量。 */
        public SalesContract {
            requireText(contractId, "合同标识");
            if (quantity <= 0) {
                throw new IllegalArgumentException("合同数量必须为正");
            }
            unitPrice = CashLedger.money(unitPrice);
            if (unitPrice.signum() <= 0) {
                throw new IllegalArgumentException("合同单价必须为正");
            }
            if (signedAt < 0 || period < 0 || promisedDeliveryAt <= signedAt
                    || promisedDeliveryAt >= (period + 1) * PERIOD_MILLIS
                    || signedAt / PERIOD_MILLIS != period) {
                throw new IllegalArgumentException("合同时间与期间不合法");
            }
            if (deliveredQuantity < 0 || deliveredQuantity > quantity) {
                throw new IllegalArgumentException("已交付数量必须在 0 与合同数量之间");
            }
        }

        /**
         * 合同未交付数量。
         *
         * @return 合同数量减已交付数量
         */
        public long remainingQuantity() {
            return quantity - deliveredQuantity;
        }
    }

    /**
     * 应收账款：交付时按全额收入确认，回款只冲减未收金额。
     *
     * @param receivableId 唯一标识（交付标识），运行内不复用
     * @param contractId   来源合同
     * @param quantity     本次交付数量
     * @param amount       本次交付确认的对价（收入）
     * @param cost         本次交付结转的账面成本（COGS）
     * @param recognizedAt 交付确认模拟毫秒
     * @param dueAt        到期模拟毫秒（交付时刻 + 15 日）
     * @param period       交付所在期间
     * @param outstanding  未收金额
     * @param status       状态
     */
    public record Receivable(String receivableId, String contractId, long quantity,
                             BigDecimal amount, BigDecimal cost, long recognizedAt, long dueAt,
                             long period, BigDecimal outstanding, ReceivableStatus status) {

        /** 校验数量、金额、时间与状态一致性。 */
        public Receivable {
            requireText(receivableId, "应收标识");
            requireText(contractId, "合同标识");
            if (quantity <= 0) {
                throw new IllegalArgumentException("交付数量必须为正");
            }
            amount = CashLedger.money(amount);
            cost = CashLedger.money(cost);
            outstanding = CashLedger.money(outstanding);
            if (amount.signum() <= 0 || cost.signum() < 0) {
                throw new IllegalArgumentException("应收金额必须为正且成本不能为负");
            }
            if (recognizedAt < 0 || dueAt <= recognizedAt || period < 0) {
                throw new IllegalArgumentException("应收时间不合法");
            }
            if (outstanding.signum() < 0 || outstanding.compareTo(amount) > 0) {
                throw new IllegalArgumentException("未收金额必须在 0 与应收金额之间");
            }
            Objects.requireNonNull(status, "应收状态");
            if ((outstanding.signum() == 0) != (status == ReceivableStatus.COLLECTED)) {
                throw new IllegalArgumentException("应收状态与未收金额不一致");
            }
        }
    }

    /**
     * 期末费用账单：费用发生与付款分开，未付部分形成应付。
     *
     * @param billId   唯一标识，运行内不复用
     * @param period   所属期间
     * @param amount   本期确认费用
     * @param paid     已付金额，不超过确认费用
     * @param closedAt 关账模拟毫秒
     */
    public record ExpenseBill(String billId, long period, BigDecimal amount, BigDecimal paid,
                              long closedAt) {

        /** 校验期间、金额与已付金额。 */
        public ExpenseBill {
            requireText(billId, "费用账单标识");
            if (period < 0) {
                throw new IllegalArgumentException("期间不能为负");
            }
            amount = CashLedger.money(amount);
            paid = CashLedger.money(paid);
            if (amount.signum() <= 0 || paid.signum() < 0 || paid.compareTo(amount) > 0) {
                throw new IllegalArgumentException("费用金额与已付金额不合法");
            }
            if (closedAt < 0) {
                throw new IllegalArgumentException("关账时间不能为负");
            }
        }

        /**
         * 未付金额。
         *
         * @return 确认费用减已付金额
         */
        public BigDecimal outstanding() {
            return amount.subtract(paid);
        }
    }

    /**
     * 不可变制造状态；集合在构造时深拷贝为不可修改视图。
     *
     * @param opening           开局账户绑定
     * @param initialEquity     初始权益（公司开局现金 + 设备原值）
     * @param initialWorldCash  开局世界现金总额
     * @param equipment         设备
     * @param rawMaterials      原材料
     * @param finishedGoods     产成品
     * @param purchaseOrders    采购单，按标识索引
     * @param productionBatches 生产批次，按标识索引
     * @param contracts         合同，按标识索引
     * @param receivables       应收，按标识索引
     * @param expenseBills      费用账单，按标识索引
     * @param usedIds           历史已用标识（与所有记录标识完全一致）
     */
    public record State(Opening opening, BigDecimal initialEquity, BigDecimal initialWorldCash,
                        Equipment equipment, Inventory rawMaterials, Inventory finishedGoods,
                        Map<String, PurchaseOrder> purchaseOrders,
                        Map<String, ProductionBatch> productionBatches,
                        Map<String, SalesContract> contracts,
                        Map<String, Receivable> receivables,
                        Map<String, ExpenseBill> expenseBills,
                        Set<String> usedIds) {

        /** 校验集合深不可变、标识唯一、期间连续与在制批次上限。 */
        public State {
            Objects.requireNonNull(opening, "开局");
            initialEquity = CashLedger.money(initialEquity);
            initialWorldCash = CashLedger.money(initialWorldCash);
            if (initialEquity.signum() < 0 || initialWorldCash.signum() < 0) {
                throw new IllegalArgumentException("初始权益与世界现金不能为负");
            }
            Objects.requireNonNull(equipment, "设备");
            Objects.requireNonNull(rawMaterials, "原材料");
            Objects.requireNonNull(finishedGoods, "产成品");
            purchaseOrders = Map.copyOf(purchaseOrders);
            productionBatches = Map.copyOf(productionBatches);
            contracts = Map.copyOf(contracts);
            receivables = Map.copyOf(receivables);
            expenseBills = Map.copyOf(expenseBills);
            // 旧规则契约：保持原来的集合表示（含 JVM 相关的迭代序），不做全局排序；
            // 需要规范序的只有新组合规则（由运行层按显式规则写快照，不改本记录）。
            usedIds = Set.copyOf(usedIds);

            Set<String> ids = new LinkedHashSet<>();
            for (Map.Entry<String, PurchaseOrder> entry : purchaseOrders.entrySet()) {
                requireSameKey(entry.getKey(), entry.getValue().purchaseOrderId());
                requireUnique(ids, entry.getKey());
            }
            long running = 0;
            for (Map.Entry<String, ProductionBatch> entry : productionBatches.entrySet()) {
                requireSameKey(entry.getKey(), entry.getValue().batchId());
                requireUnique(ids, entry.getKey());
                if (entry.getValue().status() == ProductionStatus.IN_PROGRESS) {
                    running++;
                }
            }
            if (running > 1) {
                throw new IllegalArgumentException("同一时刻最多一个在制批次");
            }
            for (Map.Entry<String, SalesContract> entry : contracts.entrySet()) {
                requireSameKey(entry.getKey(), entry.getValue().contractId());
                requireUnique(ids, entry.getKey());
            }
            for (Map.Entry<String, Receivable> entry : receivables.entrySet()) {
                requireSameKey(entry.getKey(), entry.getValue().receivableId());
                requireUnique(ids, entry.getKey());
            }
            List<Long> periods = new ArrayList<>();
            for (Map.Entry<String, ExpenseBill> entry : expenseBills.entrySet()) {
                requireSameKey(entry.getKey(), entry.getValue().billId());
                requireUnique(ids, entry.getKey());
                periods.add(entry.getValue().period());
            }
            periods.sort(Long::compareTo);
            for (int i = 0; i < periods.size(); i++) {
                if (periods.get(i) != i) {
                    throw new IllegalArgumentException("费用账单期间必须从 0 连续");
                }
            }
            if (!ids.equals(usedIds)) {
                throw new IllegalArgumentException("已用标识必须与全部记录标识一致");
            }
        }

        /**
         * 在制存货（数量与账面成本）。
         *
         * @return 派生在制品汇总
         */
        public Inventory workInProgress() {
            long quantity = 0;
            BigDecimal amount = ZERO;
            for (ProductionBatch batch : productionBatches.values()) {
                if (batch.status() == ProductionStatus.IN_PROGRESS) {
                    quantity = Math.addExact(quantity, batch.quantity());
                    amount = amount.add(batch.totalCost());
                }
            }
            return new Inventory(quantity, amount);
        }

        /**
         * 已关账期间数量；期间由费用账单派生且保证连续。
         *
         * @return 已关账期间数
         */
        public long closedPeriods() {
            return expenseBills.size();
        }

        private static void requireSameKey(String key, String id) {
            if (!key.equals(id)) {
                throw new IllegalArgumentException("索引键与记录标识不一致：" + key + " / " + id);
            }
        }

        private static void requireUnique(Set<String> ids, String id) {
            if (!ids.add(id)) {
                throw new IllegalArgumentException("标识跨类型重复：" + id);
            }
        }
    }

    /**
     * 纯候选结果：无论接受与否都携带候选状态与候选账户表。
     *
     * @param accepted 业务是否成功
     * @param reason   结果代码
     * @param state    候选制造状态；零副作用拒绝时与原状态相同
     * @param accounts 候选账户表；非现金动作原样返回
     */
    public record Outcome(boolean accepted, String reason, State state,
                          Map<String, CashAccount> accounts) {

        /** 防御性复制账户表。 */
        public Outcome {
            Objects.requireNonNull(reason, "结果代码");
            Objects.requireNonNull(state, "候选状态");
            accounts = Map.copyOf(accounts);
        }
    }

    /**
     * 带资金腿的纯候选结果。
     *
     * <p>业务状态与资金三态来自同一次候选：拒绝时资金三态经济字段与入参相同（资金层只可能多
     * 一条拒绝回执），调用方不得只提交其中一部分。本类不持有账簿，也不把存款复制成现金。</p>
     *
     * @param accepted 业务是否成功
     * @param reason   结果代码
     * @param state    候选制造状态
     * @param funds    候选资金三态（现金 + 银行 + 托管明细）
     */
    public record FundOutcome(boolean accepted, String reason, State state, FundState funds) {

        /** 校验结果码与两者非空。 */
        public FundOutcome {
            requireText(reason, "结果代码");
            Objects.requireNonNull(state, "候选状态");
            Objects.requireNonNull(funds, "候选资金三态");
        }
    }

    /**
     * 公司分红义务在经营报表中的投影：已宣告面值与仍未付的合同面值。
     *
     * <p>两个金额都精确到分且非负，未付不得超过已宣告。{@link #none()} 表示没有分红义务，
     * 与旧两参报表入口等价。持有人侧减值不进入本记录，公司合同未付面值不因投资者减值而减少。</p>
     *
     * @param declaredFace 已到 EX_BOOKED 或 SETTLED 的累计宣告面值
     * @param unpaidFace   其中尚未支付的合同面值
     */
    public record DividendPosition(BigDecimal declaredFace, BigDecimal unpaidFace) {

        /** 校验金额精确 2 位、非负且未付不超过宣告。 */
        public DividendPosition {
            declaredFace = CashLedger.money(declaredFace);
            unpaidFace = CashLedger.money(unpaidFace);
            if (declaredFace.signum() < 0 || unpaidFace.signum() < 0) {
                throw new IllegalArgumentException("分红金额不能为负："
                        + declaredFace.toPlainString() + " / " + unpaidFace.toPlainString());
            }
            if (unpaidFace.compareTo(declaredFace) > 0) {
                throw new IllegalArgumentException("未付分红不得超过已宣告："
                        + unpaidFace.toPlainString() + " > " + declaredFace.toPlainString());
            }
        }

        /**
         * 没有分红义务。
         *
         * @return 0.00 / 0.00
         */
        public static DividendPosition none() {
            return new DividendPosition(ZERO, ZERO);
        }
    }

    /**
     * 经营报表与核对结果。
     *
     * @param companyCash           公司经营现金
     * @param rawQuantity           原材料数量
     * @param rawAmount             原材料账面金额
     * @param rawUnitCost           原材料派生单位成本（4 位）
     * @param wipQuantity           在制数量
     * @param wipAmount             在制账面成本
     * @param finishedQuantity      产成品数量
     * @param finishedAmount        产成品账面金额
     * @param finishedUnitCost      产成品派生单位成本（4 位）
     * @param receivableOutstanding 应收未收总额
     * @param equipmentNet          设备净值
     * @param payableOutstanding    应付未付总额
     * @param totalAssets           公司资产合计
     * @param totalLiabilities      公司负债合计
     * @param initialEquity         初始权益
     * @param revenue               累计收入
     * @param costOfGoodsSold       累计销售成本
     * @param periodExpense         累计期间费用
     * @param depreciation          累计折旧
     * @param cumulativeProfit      累计利润
     * @param equity                期末权益（初始权益 + 累计利润）
     * @param worldCash             世界现金合计
     * @param balanced              资产 = 负债 + 权益且世界现金守恒
     */
    public record Report(BigDecimal companyCash, long rawQuantity, BigDecimal rawAmount,
                         BigDecimal rawUnitCost, long wipQuantity, BigDecimal wipAmount,
                         long finishedQuantity, BigDecimal finishedAmount,
                         BigDecimal finishedUnitCost, BigDecimal receivableOutstanding,
                         BigDecimal equipmentNet, BigDecimal payableOutstanding,
                         BigDecimal totalAssets, BigDecimal totalLiabilities,
                         BigDecimal initialEquity, BigDecimal revenue, BigDecimal costOfGoodsSold,
                         BigDecimal periodExpense, BigDecimal depreciation,
                         BigDecimal cumulativeProfit, BigDecimal equity,
                         BigDecimal worldCash, boolean balanced) {
    }

    /**
     * 由开户账户表建立开局状态；初始权益 = 公司开局现金 + 设备原值。
     *
     * <p>公司所有者只能有唯一指定的经营现金账户；同一所有者的其他账户（即使余额为 0）
     * 会被拒绝，避免公司现金在报表外分流。</p>
     *
     * @param opening  开局账户绑定
     * @param accounts 当前账户表，必须包含开局四个账户
     * @return 初始制造状态
     * @throws IllegalArgumentException 账户缺失、所有者不符、公司存在第二个账户或现金非法
     */
    public static State open(Opening opening, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(opening, "开局");
        requireAccounts(opening, accounts);
        BigDecimal companyCash = accounts.get(opening.companyAccountId()).cash();
        return new State(opening,
                companyCash.add(EQUIPMENT_ORIGINAL_COST),
                worldCash(accounts),
                new Equipment(EQUIPMENT_ORIGINAL_COST, ZERO),
                new Inventory(0, ZERO),
                new Inventory(0, ZERO),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }

    /**
     * 采购下单：只形成承诺并占用供应商当日容量，不动现金、不记应付、不入库。
     *
     * @param state    当前状态
     * @param now      当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param purchaseOrderId 采购单标识，运行内不复用
     * @param quantity 数量，必须为正且不超过供应商当日剩余容量
     * @param accounts 当前账户表
     * @return 候选结果
     */
    public static Outcome placePurchaseOrder(State state, long now, String actorOwnerId,
                                             String purchaseOrderId, long quantity,
                                             Map<String, CashAccount> accounts) {
        requireState(state);
        requireTime(now);
        requireAccounts(state.opening(), accounts);
        if (!state.opening().companyOwnerId().equals(actorOwnerId)) {
            return rejected(state, accounts, REASON_NOT_AUTHORIZED);
        }
        requireText(purchaseOrderId, "采购单标识");
        if (state.usedIds().contains(purchaseOrderId)) {
            return rejected(state, accounts, REASON_ID_REUSED);
        }
        if (quantity <= 0) {
            return rejected(state, accounts, REASON_INVALID_QUANTITY);
        }
        long orderedToday = 0;
        for (PurchaseOrder order : state.purchaseOrders().values()) {
            if (order.orderedAt() / DAY_MILLIS == now / DAY_MILLIS) {
                orderedToday = Math.addExact(orderedToday, order.quantity());
            }
        }
        if (quantity > SUPPLIER_DAILY_CAPACITY - orderedToday) {
            return rejected(state, accounts, REASON_SUPPLIER_CAPACITY_EXCEEDED);
        }
        Draft draft = new Draft(state);
        draft.purchaseOrders.put(purchaseOrderId, new PurchaseOrder(purchaseOrderId, quantity,
                RAW_UNIT_PRICE, now, Math.addExact(now, DAY_MILLIS), PurchaseOrderStatus.ORDERED));
        draft.usedIds.add(purchaseOrderId);
        return accepted(draft.build(), accounts, REASON_PURCHASE_ORDER_PLACED);
    }

    /**
     * 采购到货：到期且可用现金足够时同批付款并把原料按全额采购成本入库；否则记
     * {@code FAILED_NO_CASH}，不增原料、不记应付。
     *
     * @param state    当前状态
     * @param now      当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param purchaseOrderId 采购单标识
     * @param availableCash 公司可用现金，必须在 0 与账面余额之间
     * @param accounts 当前账户表
     * @return 候选结果
     */
    public static Outcome receivePurchaseOrder(State state, long now, String actorOwnerId,
                                               String purchaseOrderId, BigDecimal availableCash,
                                               Map<String, CashAccount> accounts) {
        return receive(state, now, actorOwnerId, purchaseOrderId, availableCash,
                Funding.cash(accounts)).outcome();
    }

    /**
     * 收货付款（显式资金腿）：到期货款从公司真实资金桶付给供应商真实资金桶，同批原料入库。
     *
     * <p>与现金版 {@link #receivePurchaseOrder(State, long, String, String, BigDecimal, Map)}
     * 的差别只有两处：钱从哪个桶出，以及“够不够钱”改用当前三态减受信冻结派生（不再由调用方
     * 自报）。到货时点、容量、货款金额、失败关单（{@code FAILED_NO_CASH}，不增原料、不记应付）
     * 与入库资本化全部按原规则。资金腿标识由采购单标识确定性派生，同一采购单不可能付两次。</p>
     *
     * @param state          当前状态
     * @param now            当前模拟毫秒
     * @param actorOwnerId   动作发起人，必须是公司所有者
     * @param purchaseOrderId 采购单标识
     * @param funds          当前资金三态（显式传入，不得复用旧结果）
     * @param payer          资金腿（只含路由与档案，不含账簿）
     * @return 候选结果（业务状态 + 资金三态）
     */
    public static FundOutcome receivePurchaseOrder(State state, long now, String actorOwnerId,
                                                  String purchaseOrderId, FundState funds,
                                                  FundPayer payer) {
        return receive(state, now, actorOwnerId, purchaseOrderId, null,
                Funding.explicit(funds, payer)).funded();
    }

    private static Settlement receive(State state, long now, String actorOwnerId,
                                      String purchaseOrderId, BigDecimal availableCash,
                                      Funding funding) {
        requireState(state);
        requireTime(now);
        requireAccounts(state.opening(), funding.accounts());
        if (!state.opening().companyOwnerId().equals(actorOwnerId)) {
            return funding.at(state, false, REASON_NOT_AUTHORIZED);
        }
        BigDecimal available = funding.available(state.opening().companyAccountId(),
                availableCash);
        PurchaseOrder order = state.purchaseOrders().get(purchaseOrderId);
        if (order == null) {
            return funding.at(state, false, REASON_UNKNOWN_PURCHASE_ORDER);
        }
        String legId = LEG_RECEIVE + purchaseOrderId;
        if (order.status() != PurchaseOrderStatus.ORDERED) {
            return funding.settled(state, legId, order.status() == PurchaseOrderStatus.RECEIVED,
                    REASON_PURCHASE_ORDER_STATE_INVALID);
        }
        if (now < order.arrivalAt()) {
            return funding.at(state, false, REASON_PURCHASE_ORDER_NOT_DUE);
        }
        BigDecimal amount = CashLedger.money(
                order.unitPrice().multiply(BigDecimal.valueOf(order.quantity())));
        if (available.compareTo(amount) < 0) {
            Draft failed = new Draft(state);
            failed.purchaseOrders.put(purchaseOrderId, copy(order, PurchaseOrderStatus.FAILED_NO_CASH));
            return new Settlement(false, REASON_INSUFFICIENT_CASH, failed.build(),
                    funding.accounts(), funding.funds());
        }
        Settlement recorded = funding.guard(state, legId);
        if (recorded != null) {
            return recorded;
        }
        Leg leg = funding.pay(legId, amount, state.opening().companyOwnerId(),
                state.opening().companyAccountId(),
                funding.accounts().get(state.opening().supplierAccountId()).ownerId(),
                state.opening().supplierAccountId());
        if (!leg.accepted()) {
            return new Settlement(false, leg.reason(), state, funding.accounts(), funding.funds());
        }
        Draft draft = new Draft(state);
        draft.purchaseOrders.put(purchaseOrderId, copy(order, PurchaseOrderStatus.RECEIVED));
        draft.rawMaterials = new Inventory(Math.addExact(draft.rawMaterials.quantity(), order.quantity()),
                draft.rawMaterials.amount().add(amount));
        return new Settlement(true, REASON_GOODS_RECEIVED, draft.build(), leg.accounts(),
                leg.funds());
    }

    /**
     * 投产：消耗按账面金额分配的原料、支付并资本化人工，形成在制批次。
     *
     * @param state    当前状态
     * @param now      当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param batchId  批次标识，运行内不复用
     * @param quantity 批量，必须是 10 的倍数且不超过 20
     * @param availableCash 公司可用现金，必须在 0 与账面余额之间
     * @param accounts 当前账户表
     * @return 候选结果
     */
    public static Outcome startProduction(State state, long now, String actorOwnerId,
                                          String batchId, long quantity, BigDecimal availableCash,
                                          Map<String, CashAccount> accounts) {
        return start(state, now, actorOwnerId, batchId, quantity, availableCash,
                Funding.cash(accounts)).outcome();
    }

    /**
     * 投产（显式资金腿）：人工从公司真实资金桶付给人力账户，原料按账面金额转在制。
     *
     * <p>与现金版 {@link #startProduction(State, long, String, String, long, BigDecimal, Map)}
     * 的差别只有付款桶与可用额来源：批量与在制上限、原料足够性、人工金额、出库成本分配与
     * 在制资本化全部按原规则；人工付现仍是投产的一部分（不是无资金动作）。资金腿标识由批次
     * 标识确定性派生。</p>
     *
     * @param state   当前状态
     * @param now     当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param batchId 批次标识
     * @param quantity 批量，必须是 10 的倍数且不超过 20
     * @param funds   当前资金三态（显式传入，不得复用旧结果）
     * @param payer   资金腿（只含路由与档案，不含账簿）
     * @return 候选结果（业务状态 + 资金三态）
     */
    public static FundOutcome startProduction(State state, long now, String actorOwnerId,
                                              String batchId, long quantity, FundState funds,
                                              FundPayer payer) {
        return start(state, now, actorOwnerId, batchId, quantity, null,
                Funding.explicit(funds, payer)).funded();
    }

    private static Settlement start(State state, long now, String actorOwnerId,
                                    String batchId, long quantity, BigDecimal availableCash,
                                    Funding funding) {
        requireState(state);
        requireTime(now);
        requireAccounts(state.opening(), funding.accounts());
        if (!state.opening().companyOwnerId().equals(actorOwnerId)) {
            return funding.at(state, false, REASON_NOT_AUTHORIZED);
        }
        requireText(batchId, "生产批次标识");
        String legId = LEG_LABOR + batchId;
        if (state.usedIds().contains(batchId)) {
            return funding.settled(state, legId,
                    state.productionBatches().containsKey(batchId),
                    REASON_ID_REUSED);
        }
        if (quantity <= 0 || quantity % 10 != 0 || quantity > 20) {
            return funding.at(state, false, REASON_INVALID_BATCH_SIZE);
        }
        if (state.workInProgress().quantity() > 0) {
            return funding.at(state, false, REASON_BATCH_IN_PROGRESS);
        }
        if (state.rawMaterials().quantity() < quantity) {
            return funding.at(state, false, REASON_INSUFFICIENT_MATERIAL);
        }
        BigDecimal available = funding.available(state.opening().companyAccountId(),
                availableCash);
        BigDecimal labor = CashLedger.money(LABOR_UNIT_PRICE.multiply(BigDecimal.valueOf(quantity)));
        if (available.compareTo(labor) < 0) {
            return funding.at(state, false, REASON_INSUFFICIENT_CASH);
        }
        BigDecimal material = allocate(state.rawMaterials(), quantity);
        Settlement recorded = funding.guard(state, legId);
        if (recorded != null) {
            return recorded;
        }
        Leg leg = funding.pay(legId, labor, state.opening().companyOwnerId(),
                state.opening().companyAccountId(),
                funding.accounts().get(state.opening().opsAccountId()).ownerId(),
                state.opening().opsAccountId());
        if (!leg.accepted()) {
            return new Settlement(false, leg.reason(), state, funding.accounts(), funding.funds());
        }
        Draft draft = new Draft(state);
        draft.rawMaterials = new Inventory(state.rawMaterials().quantity() - quantity,
                state.rawMaterials().amount().subtract(material));
        draft.productionBatches.put(batchId, new ProductionBatch(batchId, quantity, now,
                Math.addExact(now, DAY_MILLIS), material, labor, ProductionStatus.IN_PROGRESS));
        draft.usedIds.add(batchId);
        return new Settlement(true, REASON_PRODUCTION_STARTED, draft.build(), leg.accounts(),
                leg.funds());
    }

    /**
     * 完工：到期把在制批次成本全额转入产成品；重复完成不再产出。
     *
     * @param state   当前状态
     * @param now     当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param batchId 批次标识
     * @param accounts 当前账户表
     * @return 候选结果
     */
    public static Outcome completeProduction(State state, long now, String actorOwnerId,
                                             String batchId, Map<String, CashAccount> accounts) {
        requireState(state);
        requireTime(now);
        requireAccounts(state.opening(), accounts);
        if (!state.opening().companyOwnerId().equals(actorOwnerId)) {
            return rejected(state, accounts, REASON_NOT_AUTHORIZED);
        }
        ProductionBatch batch = state.productionBatches().get(batchId);
        if (batch == null) {
            return rejected(state, accounts, REASON_UNKNOWN_BATCH);
        }
        if (batch.status() != ProductionStatus.IN_PROGRESS) {
            return rejected(state, accounts, REASON_BATCH_STATE_INVALID);
        }
        if (now < batch.completesAt()) {
            return rejected(state, accounts, REASON_BATCH_NOT_DUE);
        }
        Draft draft = new Draft(state);
        draft.productionBatches.put(batchId, new ProductionBatch(batch.batchId(), batch.quantity(),
                batch.startedAt(), batch.completesAt(), batch.materialCost(), batch.laborCost(),
                ProductionStatus.FINISHED));
        draft.finishedGoods = new Inventory(
                Math.addExact(draft.finishedGoods.quantity(), batch.quantity()),
                draft.finishedGoods.amount().add(batch.totalCost()));
        return accepted(draft.build(), accounts, REASON_PRODUCTION_COMPLETED);
    }

    /**
     * 客户签约：占用当期需求与预算，不改现金、库存或收入；首档案只允许同期间交付。
     *
     * @param state     当前状态
     * @param now       当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param contractId 合同标识，运行内不复用
     * @param quantity  合同数量
     * @param unitPrice 合同单价，不超过客户上限
     * @param promisedDeliveryAt 承诺交付模拟毫秒，必须晚于当前且在同一期间内
     * @param accounts  当前账户表
     * @return 候选结果
     */
    public static Outcome signContract(State state, long now, String actorOwnerId,
                                       String contractId, long quantity, BigDecimal unitPrice,
                                       long promisedDeliveryAt, Map<String, CashAccount> accounts) {
        requireState(state);
        requireTime(now);
        requireAccounts(state.opening(), accounts);
        if (!state.opening().companyOwnerId().equals(actorOwnerId)) {
            return rejected(state, accounts, REASON_NOT_AUTHORIZED);
        }
        requireText(contractId, "合同标识");
        if (state.usedIds().contains(contractId)) {
            return rejected(state, accounts, REASON_ID_REUSED);
        }
        if (quantity <= 0) {
            return rejected(state, accounts, REASON_INVALID_QUANTITY);
        }
        BigDecimal price;
        try {
            price = CashLedger.money(unitPrice);
        } catch (IllegalArgumentException invalid) {
            return rejected(state, accounts, REASON_INVALID_PRICE);
        }
        if (price.signum() <= 0) {
            return rejected(state, accounts, REASON_INVALID_PRICE);
        }
        if (price.compareTo(CONTRACT_PRICE_CAP) > 0) {
            return rejected(state, accounts, REASON_PRICE_ABOVE_CAP);
        }
        long period = now / PERIOD_MILLIS;
        if (promisedDeliveryAt <= now) {
            return rejected(state, accounts, REASON_INVALID_PROMISED_DELIVERY);
        }
        if (promisedDeliveryAt >= periodEnd(period)) {
            return rejected(state, accounts, REASON_CONTRACT_CROSS_PERIOD_UNSUPPORTED);
        }
        long demand = 0;
        BigDecimal budget = ZERO;
        for (SalesContract contract : state.contracts().values()) {
            if (contract.period() == period) {
                demand = Math.addExact(demand, contract.quantity());
                budget = budget.add(CashLedger.money(
                        contract.unitPrice().multiply(BigDecimal.valueOf(contract.quantity()))));
            }
        }
        if (demand + quantity > CUSTOMER_PERIOD_DEMAND) {
            return rejected(state, accounts, REASON_DEMAND_EXCEEDED);
        }
        BigDecimal value = CashLedger.money(price.multiply(BigDecimal.valueOf(quantity)));
        if (budget.add(value).compareTo(CUSTOMER_PERIOD_BUDGET) > 0) {
            return rejected(state, accounts, REASON_BUDGET_EXCEEDED);
        }
        Draft draft = new Draft(state);
        draft.contracts.put(contractId, new SalesContract(contractId, quantity, price, now,
                promisedDeliveryAt, period, 0));
        draft.usedIds.add(contractId);
        return accepted(draft.build(), accounts, REASON_CONTRACT_SIGNED);
    }

    /**
     * 交付：到承诺时点且仍在同一期间内，扣减成品账面成本并同批确认全额收入、COGS 与应收。
     *
     * @param state      当前状态
     * @param now        当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param contractId 合同标识
     * @param quantity   本次交付数量，不超过合同余量与成品库存
     * @param deliveryId 交付/应收标识，运行内不复用
     * @param accounts   当前账户表
     * @return 候选结果
     */
    public static Outcome deliver(State state, long now, String actorOwnerId, String contractId,
                                  long quantity, String deliveryId,
                                  Map<String, CashAccount> accounts) {
        requireState(state);
        requireTime(now);
        requireAccounts(state.opening(), accounts);
        if (!state.opening().companyOwnerId().equals(actorOwnerId)) {
            return rejected(state, accounts, REASON_NOT_AUTHORIZED);
        }
        SalesContract contract = state.contracts().get(contractId);
        if (contract == null) {
            return rejected(state, accounts, REASON_UNKNOWN_CONTRACT);
        }
        requireText(deliveryId, "交付标识");
        if (state.usedIds().contains(deliveryId)) {
            return rejected(state, accounts, REASON_ID_REUSED);
        }
        if (now >= periodEnd(contract.period())) {
            return rejected(state, accounts, REASON_DELIVERY_PERIOD_ENDED);
        }
        if (now < contract.promisedDeliveryAt()) {
            return rejected(state, accounts, REASON_DELIVERY_NOT_DUE);
        }
        if (quantity <= 0) {
            return rejected(state, accounts, REASON_INVALID_QUANTITY);
        }
        if (quantity > contract.remainingQuantity()) {
            return rejected(state, accounts, REASON_DELIVERY_EXCEEDS_CONTRACT);
        }
        if (state.finishedGoods().quantity() < quantity) {
            return rejected(state, accounts, REASON_INSUFFICIENT_FINISHED_GOODS);
        }
        BigDecimal cost = allocate(state.finishedGoods(), quantity);
        BigDecimal amount = CashLedger.money(
                contract.unitPrice().multiply(BigDecimal.valueOf(quantity)));
        Draft draft = new Draft(state);
        draft.finishedGoods = new Inventory(state.finishedGoods().quantity() - quantity,
                state.finishedGoods().amount().subtract(cost));
        draft.contracts.put(contractId, new SalesContract(contract.contractId(), contract.quantity(),
                contract.unitPrice(), contract.signedAt(), contract.promisedDeliveryAt(),
                contract.period(), Math.addExact(contract.deliveredQuantity(), quantity)));
        draft.receivables.put(deliveryId, new Receivable(deliveryId, contractId, quantity, amount,
                cost, now, Math.addExact(now, RECEIVABLE_TERM_MILLIS), contract.period(),
                amount, ReceivableStatus.OPEN));
        draft.usedIds.add(deliveryId);
        return accepted(draft.build(), accounts, REASON_DELIVERED);
    }

    /**
     * 回款：只核客户真实可用现金与未收金额，不再扣预算；不足只标逾期并可明确重试一次。
     *
     * @param state       当前状态
     * @param now         当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param receivableId 应收标识
     * @param availableCash 客户可用现金，必须在 0 与客户账面余额之间
     * @param accounts    当前账户表
     * @return 候选结果
     */
    public static Outcome collectReceivable(State state, long now, String actorOwnerId,
                                            String receivableId, BigDecimal availableCash,
                                            Map<String, CashAccount> accounts) {
        return collectFrom(state, now, actorOwnerId, receivableId, availableCash,
                Funding.cash(accounts)).outcome();
    }

    /**
     * 应收回款（显式资金腿）：客户从真实资金桶付进公司真实资金桶。
     *
     * <p>与现金版 {@link #collectReceivable(State, long, String, String, BigDecimal, Map)}
     * 的差别只有付款桶与可用额来源：到期判断、逾期标记（客户可用额不足即标 {@code OVERDUE}）、
     * 已收清不可再收、只减应收不重记收入全部按原规则。资金腿标识由应收标识确定性派生。</p>
     *
     * @param state        当前状态
     * @param now          当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param receivableId 应收标识
     * @param funds        当前资金三态（显式传入，不得复用旧结果）
     * @param payer        资金腿：源是客户桶、目标是公司桶
     * @return 候选结果（业务状态 + 资金三态）
     */
    public static FundOutcome collectReceivable(State state, long now, String actorOwnerId,
                                               String receivableId, FundState funds,
                                               FundPayer payer) {
        return collectFrom(state, now, actorOwnerId, receivableId, null,
                Funding.explicit(funds, payer)).funded();
    }

    private static Settlement collectFrom(State state, long now, String actorOwnerId,
                                          String receivableId, BigDecimal availableCash,
                                          Funding funding) {
        requireState(state);
        requireTime(now);
        requireAccounts(state.opening(), funding.accounts());
        if (!state.opening().companyOwnerId().equals(actorOwnerId)) {
            return funding.at(state, false, REASON_NOT_AUTHORIZED);
        }
        Receivable receivable = state.receivables().get(receivableId);
        if (receivable == null) {
            return funding.at(state, false, REASON_UNKNOWN_RECEIVABLE);
        }
        String legId = LEG_COLLECT + receivableId;
        if (receivable.status() == ReceivableStatus.COLLECTED) {
            return funding.settled(state, legId, true, REASON_RECEIVABLE_ALREADY_COLLECTED);
        }
        if (now < receivable.dueAt()) {
            return funding.at(state, false, REASON_RECEIVABLE_NOT_DUE);
        }
        BigDecimal available = funding.available(state.opening().customerAccountId(),
                availableCash);
        if (available.compareTo(receivable.outstanding()) < 0) {
            Draft overdue = new Draft(state);
            overdue.receivables.put(receivableId, new Receivable(receivable.receivableId(),
                    receivable.contractId(), receivable.quantity(), receivable.amount(),
                    receivable.cost(), receivable.recognizedAt(), receivable.dueAt(),
                    receivable.period(), receivable.outstanding(), ReceivableStatus.OVERDUE));
            return new Settlement(false, REASON_OVERDUE, overdue.build(), funding.accounts(),
                    funding.funds());
        }
        Settlement recorded = funding.guard(state, legId);
        if (recorded != null) {
            return recorded;
        }
        Leg leg = funding.pay(legId, receivable.outstanding(),
                funding.accounts().get(state.opening().customerAccountId()).ownerId(),
                state.opening().customerAccountId(), state.opening().companyOwnerId(),
                state.opening().companyAccountId());
        if (!leg.accepted()) {
            return new Settlement(false, leg.reason(), state, funding.accounts(), funding.funds());
        }
        Draft draft = new Draft(state);
        draft.receivables.put(receivableId, new Receivable(receivable.receivableId(),
                receivable.contractId(), receivable.quantity(), receivable.amount(),
                receivable.cost(), receivable.recognizedAt(), receivable.dueAt(),
                receivable.period(), ZERO, ReceivableStatus.COLLECTED));
        return new Settlement(true, REASON_PAYMENT_RECEIVED, draft.build(), leg.accounts(),
                leg.funds());
    }

    /**
     * 期末关账：必须到达该期末且期间连续；确认固定费用与折旧，现金不足部分形成应付。
     *
     * @param state    当前状态
     * @param now      当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param period   期间序号，必须等于已连续关账数
     * @param billId   费用账单标识，运行内不复用
     * @param availableCash 公司可用现金，必须在 0 与账面余额之间
     * @param accounts 当前账户表
     * @return 候选结果
     */
    public static Outcome closePeriod(State state, long now, String actorOwnerId, long period,
                                      String billId, BigDecimal availableCash,
                                      Map<String, CashAccount> accounts) {
        return close(state, now, actorOwnerId, period, billId, availableCash,
                Funding.cash(accounts)).outcome();
    }

    /**
     * 期末关账（显式资金腿）：固定费用按真实可用额当期货款，不足部分仍形成应付。
     *
     * <p>与现金版 {@link #closePeriod(State, long, String, long, String, BigDecimal, Map)}
     * 的差别只有两处：钱从哪个桶付给人力账户，以及“可用额”由当前三态减受信冻结派生。
     * 期间连续性、期末到达、账单标识不复用、折旧计提、`paid = min(可用, 250)` 与未付成
     * {@code ExpenseBill} 应付全部按原规则。资金腿标识由账单标识确定性派生；可用额为零时
     * 不动任何桶（全额转应付）。</p>
     *
     * @param state    当前状态
     * @param now      当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param period   期间序号，必须等于已连续关账数
     * @param billId   费用账单标识，运行内不复用
     * @param funds    当前资金三态（显式传入，不得复用旧结果）
     * @param payer    资金腿：源是公司桶、目标是人力桶
     * @return 候选结果（业务状态 + 资金三态）
     */
    public static FundOutcome closePeriod(State state, long now, String actorOwnerId, long period,
                                          String billId, FundState funds, FundPayer payer) {
        return close(state, now, actorOwnerId, period, billId, null,
                Funding.explicit(funds, payer)).funded();
    }

    private static Settlement close(State state, long now, String actorOwnerId, long period,
                                    String billId, BigDecimal availableCash, Funding funding) {
        requireState(state);
        requireTime(now);
        requireAccounts(state.opening(), funding.accounts());
        if (!state.opening().companyOwnerId().equals(actorOwnerId)) {
            return funding.at(state, false, REASON_NOT_AUTHORIZED);
        }
        if (period < 0) {
            throw new IllegalArgumentException("期间不能为负");
        }
        long next = state.closedPeriods();
        if (period < next) {
            return funding.at(state, false, REASON_PERIOD_ALREADY_CLOSED);
        }
        if (period > next) {
            return funding.at(state, false, REASON_PERIOD_NOT_CONTINUOUS);
        }
        if (now < periodEnd(period)) {
            return funding.at(state, false, REASON_PERIOD_NOT_ENDED);
        }
        requireText(billId, "费用账单标识");
        String legId = LEG_EXPENSE + billId;
        if (state.usedIds().contains(billId)) {
            return funding.settled(state, legId, state.expenseBills().containsKey(billId),
                    REASON_ID_REUSED);
        }
        BigDecimal available = funding.available(state.opening().companyAccountId(),
                availableCash);
        BigDecimal paid = available.min(PERIOD_EXPENSE);
        Leg leg;
        if (paid.signum() > 0) {
            Settlement recorded = funding.guard(state, legId);
            if (recorded != null) {
                return recorded;
            }
            leg = funding.pay(legId, paid, state.opening().companyOwnerId(),
                    state.opening().companyAccountId(),
                    funding.accounts().get(state.opening().opsAccountId()).ownerId(),
                    state.opening().opsAccountId());
            if (!leg.accepted()) {
                return new Settlement(false, leg.reason(), state, funding.accounts(),
                        funding.funds());
            }
        } else {
            leg = funding.untouched();
        }
        BigDecimal depreciation = PERIOD_DEPRECIATION.min(state.equipment().net());
        Draft draft = new Draft(state);
        draft.equipment = new Equipment(state.equipment().originalCost(),
                state.equipment().accumulatedDepreciation().add(depreciation));
        draft.expenseBills.put(billId, new ExpenseBill(billId, period, PERIOD_EXPENSE, paid, now));
        draft.usedIds.add(billId);
        return new Settlement(true, REASON_PERIOD_CLOSED, draft.build(), leg.accounts(),
                leg.funds());
    }

    /**
     * 支付费用账单：只结清已确认未付金额，需要真实可用现金足够；付款不再记费用。
     *
     * @param state    当前状态
     * @param now      当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param period   费用账单所属期间
     * @param availableCash 公司可用现金，必须在 0 与账面余额之间
     * @param accounts 当前账户表
     * @return 候选结果
     */
    public static Outcome payExpenseBill(State state, long now, String actorOwnerId, long period,
                                         BigDecimal availableCash,
                                         Map<String, CashAccount> accounts) {
        return payBill(state, now, actorOwnerId, period, availableCash,
                Funding.cash(accounts)).outcome();
    }

    /**
     * 支付费用账单（显式资金腿）：只结清已确认未付金额，付款不再记费用。
     *
     * <p>与现金版 {@link #payExpenseBill(State, long, String, long, BigDecimal, Map)}
     * 的差别只有付款桶与可用额来源：期间账单必须存在、未付必须为正、可用额必须足够全部未付
     * （不足整笔拒绝，不部分支付），付清后不再重复计费。资金腿标识由账单标识确定性派生，
     * 与关账时的当期付款前缀不同，因此两个动作各自独立幂等。</p>
     *
     * @param state    当前状态
     * @param now      当前模拟毫秒
     * @param actorOwnerId 动作发起人，必须是公司所有者
     * @param period   费用账单所属期间
     * @param funds    当前资金三态（显式传入，不得复用旧结果）
     * @param payer    资金腿：源是公司桶、目标是人力桶
     * @return 候选结果（业务状态 + 资金三态）
     */
    public static FundOutcome payExpenseBill(State state, long now, String actorOwnerId,
                                             long period, FundState funds, FundPayer payer) {
        return payBill(state, now, actorOwnerId, period, null,
                Funding.explicit(funds, payer)).funded();
    }

    private static Settlement payBill(State state, long now, String actorOwnerId, long period,
                                      BigDecimal availableCash, Funding funding) {
        requireState(state);
        requireTime(now);
        requireAccounts(state.opening(), funding.accounts());
        if (!state.opening().companyOwnerId().equals(actorOwnerId)) {
            return funding.at(state, false, REASON_NOT_AUTHORIZED);
        }
        ExpenseBill bill = null;
        for (ExpenseBill candidate : state.expenseBills().values()) {
            if (candidate.period() == period) {
                bill = candidate;
                break;
            }
        }
        if (bill == null) {
            return funding.at(state, false, REASON_UNKNOWN_EXPENSE_BILL);
        }
        String legId = LEG_BILL + bill.billId();
        BigDecimal outstanding = bill.outstanding();
        if (outstanding.signum() == 0) {
            return funding.settled(state, legId, true, REASON_EXPENSE_BILL_ALREADY_PAID);
        }
        BigDecimal available = funding.available(state.opening().companyAccountId(),
                availableCash);
        if (available.compareTo(outstanding) < 0) {
            return funding.at(state, false, REASON_INSUFFICIENT_CASH);
        }
        Settlement recorded = funding.guard(state, legId);
        if (recorded != null) {
            return recorded;
        }
        Leg leg = funding.pay(legId, outstanding, state.opening().companyOwnerId(),
                state.opening().companyAccountId(),
                funding.accounts().get(state.opening().opsAccountId()).ownerId(),
                state.opening().opsAccountId());
        if (!leg.accepted()) {
            return new Settlement(false, leg.reason(), state, funding.accounts(), funding.funds());
        }
        Draft draft = new Draft(state);
        draft.expenseBills.put(bill.billId(), new ExpenseBill(bill.billId(), bill.period(),
                bill.amount(), bill.amount(), bill.closedAt()));
        return new Settlement(true, REASON_EXPENSE_BILL_PAID, draft.build(), leg.accounts(),
                leg.funds());
    }

    /**
     * 派生经营报表，不修改状态。
     *
     * @param state    当前状态
     * @param accounts 当前账户表
     * @return 经营报表
     * @throws IllegalArgumentException 账户结构与开局不符
     */
    public static Report report(State state, Map<String, CashAccount> accounts) {
        return report(state, accounts, DividendPosition.none());
    }

    /**
     * 带公司分红义务的经营报表：未付分红计入负债，已宣告面值冲减权益。
     *
     * <p>资产、经营费用、利润、现金与物量口径完全沿用原规则，分红不进入费用或利润。
     * 已宣告面值大于初始权益加利润时如实给出负权益，不夹到 0。</p>
     *
     * @param state     制造状态
     * @param accounts  当前账户表
     * @param dividends 公司分红义务；null 拒绝
     * @return 经营报表
     * @throws IllegalArgumentException 账户结构、分红位置或金额不合法
     */
    public static Report report(State state, Map<String, CashAccount> accounts,
                                DividendPosition dividends) {
        return report(state, accounts, dividends, ZERO, ZERO, ZERO);
    }

    /**
     * 带组合派生财务头寸的经营报表：资产、负债、权益三侧分别并入真实派生项。
     *
     * <p>制造子账本只记本业现金与经营资产负债。组合世界里同一笔财富可以以银行存款、应收存款
     * 利息或证券持仓存在，也可以形成真实借款义务；因此平衡式必须三侧同时并入派生项，
     * 而不是把一个“净差额”塞进资产侧。旧档调用三参版本，三侧均传 0，口径逐字不变。</p>
     *
     * @param state                   制造状态
     * @param accounts                当前账户表
     * @param dividends               公司分红义务
     * @param externalAssets          组合派生资产（存款 + 应收利息 + 证券成本）
     * @param externalLiabilities     组合派生负债（借款本金 + 应计未付利息）
     * @param externalEquity          组合派生权益（已实现投资损益 + 利息净额 + 开局证券成本）
     * @return 经营报表
     */
    public static Report report(State state, Map<String, CashAccount> accounts,
                                DividendPosition dividends, BigDecimal externalAssets,
                                BigDecimal externalLiabilities, BigDecimal externalEquity) {
        requireState(state);
        if (dividends == null) {
            throw new IllegalArgumentException("分红位置不能为空");
        }
        BigDecimal externalA = CashLedger.money(externalAssets == null ? ZERO : externalAssets);
        BigDecimal externalL = CashLedger.money(
                externalLiabilities == null ? ZERO : externalLiabilities);
        BigDecimal externalE = CashLedger.money(externalEquity == null ? ZERO : externalEquity);
        Opening opening = state.opening();
        requireAccounts(opening, accounts);
        BigDecimal companyCash = accounts.get(opening.companyAccountId()).cash();
        Inventory wip = state.workInProgress();

        BigDecimal receivables = ZERO;
        BigDecimal revenue = ZERO;
        BigDecimal cogs = ZERO;
        for (Receivable receivable : state.receivables().values()) {
            receivables = receivables.add(receivable.outstanding());
            revenue = revenue.add(receivable.amount());
            cogs = cogs.add(receivable.cost());
        }
        BigDecimal payables = ZERO;
        BigDecimal expense = ZERO;
        for (ExpenseBill bill : state.expenseBills().values()) {
            payables = payables.add(bill.outstanding());
            expense = expense.add(bill.amount());
        }
        BigDecimal depreciation = state.equipment().accumulatedDepreciation();
        BigDecimal profit = CashLedger.money(
                revenue.subtract(cogs).subtract(expense).subtract(depreciation));
        BigDecimal assets = CashLedger.money(companyCash
                .add(state.rawMaterials().amount())
                .add(wip.amount())
                .add(state.finishedGoods().amount())
                .add(receivables)
                .add(state.equipment().net())
                .add(externalA));
        BigDecimal liabilities = CashLedger.money(payables.add(dividends.unpaidFace())
                .add(externalL));
        BigDecimal equity = CashLedger.money(state.initialEquity().add(profit)
                .subtract(dividends.declaredFace()).add(externalE));
        BigDecimal world = worldCash(accounts);
        boolean balanced = assets.compareTo(liabilities.add(equity)) == 0
                && world.compareTo(state.initialWorldCash()) == 0;
        return new Report(CashLedger.money(companyCash),
                state.rawMaterials().quantity(), state.rawMaterials().amount(),
                state.rawMaterials().unitCost(),
                wip.quantity(), wip.amount(),
                state.finishedGoods().quantity(), state.finishedGoods().amount(),
                state.finishedGoods().unitCost(),
                CashLedger.money(receivables), state.equipment().net(), CashLedger.money(payables),
                assets, liabilities, state.initialEquity(), CashLedger.money(revenue),
                CashLedger.money(cogs), CashLedger.money(expense), CashLedger.money(depreciation),
                profit, equity, world, balanced);
    }

    /**
     * 校验公司资产负债关系与世界现金守恒；不平衡时抛出异常而不是返回可疑报表。
     *
     * @param state    当前状态
     * @param accounts 当前账户表
     * @return 已通过校验的经营报表
     * @throws IllegalStateException    资产、负债、权益或世界现金不守恒
     * @throws IllegalArgumentException 账户结构与开局不符
     */
    public static Report validate(State state, Map<String, CashAccount> accounts) {
        return validate(state, accounts, DividendPosition.none());
    }

    /**
     * 带公司分红义务的校验：不平衡或世界现金不守恒时抛出异常。
     *
     * @param state     制造状态
     * @param accounts  当前账户表
     * @param dividends 公司分红义务；null 拒绝
     * @return 已通过校验的经营报表
     * @throws IllegalStateException    资产、负债、权益或世界现金不守恒
     * @throws IllegalArgumentException 账户结构、分红位置或金额不合法
     */
    public static Report validate(State state, Map<String, CashAccount> accounts,
                                  DividendPosition dividends) {
        return validate(state, accounts, dividends, ZERO, ZERO, ZERO);
    }

    /**
     * 带组合派生财务头寸的校验：不平衡或世界现金不守恒时抛出异常。
     *
     * @param state               制造状态
     * @param accounts            当前账户表
     * @param dividends           公司分红义务
     * @param externalAssets      组合派生资产；旧档传 0
     * @param externalLiabilities 组合派生负债；旧档传 0
     * @param externalEquity      组合派生权益；旧档传 0
     * @return 已通过校验的经营报表
     * @throws IllegalStateException    资产、负债、权益或世界现金不守恒
     * @throws IllegalArgumentException 账户结构、分红位置或金额不合法
     */
    public static Report validate(State state, Map<String, CashAccount> accounts,
                                  DividendPosition dividends, BigDecimal externalAssets,
                                  BigDecimal externalLiabilities, BigDecimal externalEquity) {
        Report report = report(state, accounts, dividends, externalAssets, externalLiabilities,
                externalEquity);
        if (!report.balanced()) {
            throw new IllegalStateException("制造账不平衡：资产=" + report.totalAssets()
                    + "，负债=" + report.totalLiabilities()
                    + "，权益=" + report.equity()
                    + "，世界现金=" + report.worldCash()
                    + "，初始世界现金=" + state.initialWorldCash());
        }
        return report;
    }

    // ------------------------------------------------------------------ 内部

    private static State requireState(State state) {
        return Objects.requireNonNull(state, "状态");
    }

    private static void requireTime(long now) {
        if (now < 0) {
            throw new IllegalArgumentException("模拟毫秒不能为负：" + now);
        }
    }

    private static long periodEnd(long period) {
        return Math.multiplyExact(period + 1, PERIOD_MILLIS);
    }

    private static void requireAccounts(Opening opening, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(accounts, "账户表");
        for (Map.Entry<String, CashAccount> entry : accounts.entrySet()) {
            if (opening.companyOwnerId().equals(entry.getValue().ownerId())
                    && !opening.companyAccountId().equals(entry.getKey())) {
                throw new IllegalArgumentException(
                        "公司所有者只能有一个经营现金账户：" + entry.getKey());
            }
        }
        List<String> ids = List.of(opening.companyAccountId(), opening.supplierAccountId(),
                opening.customerAccountId(), opening.opsAccountId());
        for (String accountId : ids) {
            CashAccount account = accounts.get(accountId);
            if (account == null) {
                throw new IllegalArgumentException("缺少开局账户：" + accountId);
            }
            requireText(account.ownerId(), "账户所有者");
            BigDecimal cash = CashLedger.money(account.cash());
            if (cash.signum() < 0) {
                throw new IllegalArgumentException("账户现金不能为负：" + accountId);
            }
        }
        if (!accounts.get(opening.companyAccountId()).ownerId().equals(opening.companyOwnerId())) {
            throw new IllegalArgumentException("公司账户所有者与开局主体不一致");
        }
        Set<String> counterpartyOwners = new LinkedHashSet<>();
        for (String accountId : ids.subList(1, ids.size())) {
            String owner = accounts.get(accountId).ownerId();
            if (owner.equals(opening.companyOwnerId()) || !counterpartyOwners.add(owner)) {
                throw new IllegalArgumentException("对手方所有者必须互不相同且不同于公司");
            }
        }
    }

    private static BigDecimal requireAvailable(Map<String, CashAccount> accounts, String accountId,
                                               BigDecimal availableCash) {
        BigDecimal available = CashLedger.money(availableCash);
        BigDecimal gross = accounts.get(accountId).cash();
        if (available.signum() < 0 || available.compareTo(gross) > 0) {
            throw new IllegalArgumentException("可用现金必须在 0 与账面余额之间："
                    + available + " / " + gross);
        }
        return available;
    }

    private static BigDecimal worldCash(Map<String, CashAccount> accounts) {
        BigDecimal total = ZERO;
        for (CashAccount account : accounts.values()) {
            total = total.add(account.cash());
        }
        return CashLedger.money(total);
    }

    /**
     * 按账面金额和数量比例分配出库成本；最后一次出清精确结尽尾差。
     */
    private static BigDecimal allocate(Inventory inventory, long quantity) {
        if (quantity <= 0 || quantity > inventory.quantity()) {
            throw new IllegalArgumentException("出库数量必须在 0 与库存数量之间");
        }
        if (quantity == inventory.quantity()) {
            return inventory.amount();
        }
        return inventory.amount()
                .multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(inventory.quantity()), 2, RoundingMode.HALF_UP);
    }

    private static PurchaseOrder copy(PurchaseOrder order, PurchaseOrderStatus status) {
        return new PurchaseOrder(order.purchaseOrderId(), order.quantity(), order.unitPrice(),
                order.orderedAt(), order.arrivalAt(), status);
    }

    /**
     * 一次制造候选的资金上下文：旧现金路径只带账户表；显式路径带当前三态与资金腿。
     *
     * <p>两条路径共用同一份经营规则（顺序、容量、供需、应收/账单状态推进），差别只在钱怎么走：
     * 旧路径仍走 {@link CashLedger#computeTransfer}，显式路径走 {@link FundPayer#pay}，
     * 可用额也由同一个冻结快照派生。</p>
     *
     * @param accounts 当前现金账户表
     * @param funds    当前资金三态；旧现金路径为 null
     * @param payer    资金腿；旧现金路径为 null
     */
    private record Funding(Map<String, CashAccount> accounts, FundState funds, FundPayer payer) {

        static Funding cash(Map<String, CashAccount> accounts) {
            Objects.requireNonNull(accounts, "账户表");
            return new Funding(accounts, null, null);
        }

        static Funding explicit(FundState funds, FundPayer payer) {
            Objects.requireNonNull(funds, "资金三态");
            Objects.requireNonNull(payer, "资金腿");
            return new Funding(funds.accounts(), funds, payer);
        }

        /** @return 是否显式资金腿路径 */
        boolean explicit() {
            return funds != null;
        }

        /**
         * 不移动任何钱时的候选：显式路径保持入参三态，旧路径保持入参账户表。
         *
         * @param state    业务状态
         * @param accepted 是否成功
         * @param reason   结果码
         * @return 候选
         */
        Settlement at(State state, boolean accepted, String reason) {
            return new Settlement(accepted, reason, state, accounts, funds);
        }

        /** @return 业务成功但本次不需要动钱（金额为零）的腿结果 */
        Leg untouched() {
            return new Leg(true, null, accounts, funds);
        }

        /**
         * 资金层已有该业务事实的记录，而业务状态仍待办：受控拒绝，不把零移动当新付款。
         *
         * @param state  当前业务状态
         * @param legId  由业务事实派生的资金腿标识
         * @return 需要原样返回的候选；null 表示可以继续
         */
        Settlement guard(State state, String legId) {
            if (explicit() && payer.hasRecord(legId, funds)) {
                return at(state, false, REASON_FUND_ID_ALREADY_RECORDED);
            }
            return null;
        }

        /**
         * 业务事实已结算时的候选。
         *
         * <p>显式路径若发现业务已经收货/收款/付清，而资金层没有对应成功记录，就明示分叉
         * （{@link #REASON_FUND_RECORD_DIVERGED}），不把这种状态当正常的“已完成重放”。</p>
         *
         * @param state          当前业务状态
         * @param legId          由业务事实派生的资金腿标识
         * @param businessDone   业务是否真的已经结算（而不是失败关单）
         * @param businessReason 业务层原本要返回的拒绝码
         * @return 候选
         */
        Settlement settled(State state, String legId, boolean businessDone, String businessReason) {
            if (businessDone && explicit() && !payer.hasAcceptedRecord(legId, funds)) {
                return at(state, false, REASON_FUND_RECORD_DIVERGED);
            }
            return at(state, false, businessReason);
        }

        /**
         * 出款可用额。
         *
         * <p>旧路径沿用调用方声明并经 `0..账面余额` 校验；显式路径由当前三态与 payer 绑定的
         * 受信冻结快照派生（余额减该桶冻结），因此业务判断与资金层用同一个数。</p>
         *
         * @param accountId 旧路径出款账户
         * @param declared  旧路径调用方声明的可用额
         * @return 可用额
         */
        BigDecimal available(String accountId, BigDecimal declared) {
            if (!explicit()) {
                return requireAvailable(accounts, accountId, declared);
            }
            return payer.available(funds);
        }

        /**
         * 执行一条腿。
         *
         * @param legId         资金腿标识（由业务事实派生）
         * @param amount        金额
         * @param sourceOwner   业务认定的出款主体
         * @param sourceAccount 旧路径出款账户
         * @param targetOwner   业务认定的收款主体
         * @param targetAccount 旧路径收款账户
         * @return 腿结果；拒绝时经济字段不变
         */
        Leg pay(String legId, BigDecimal amount, String sourceOwner, String sourceAccount,
                String targetOwner, String targetAccount) {
            if (!explicit()) {
                CashLedger.TransferOutcome transfer = CashLedger.computeTransfer(accounts,
                        sourceOwner, sourceAccount, targetAccount, amount);
                return new Leg(transfer.accepted(),
                        transfer.accepted() ? null : transfer.reason(), transfer.accounts(), null);
            }
            if (!sourceOwner.equals(funds.ownerIdOf(payer.source()))) {
                return new Leg(false, REASON_FUND_OWNER_MISMATCH, accounts, funds);
            }
            if (!targetOwner.equals(funds.ownerIdOf(payer.target()))) {
                return new Leg(false, REASON_PAYEE_MISMATCH, accounts, funds);
            }
            FundPayer.Result result = payer.pay(legId, amount, funds);
            return new Leg(result.accepted(), result.reason(), result.funds().accounts(),
                    result.funds());
        }
    }

    /**
     * 一条资金腿的结果。
     *
     * @param accepted 是否完成
     * @param reason   资金腿结果码；旧路径成功时为 null（业务用自己的成功码）
     * @param accounts 候选现金账户表
     * @param funds    候选资金三态；旧现金路径为 null
     */
    private record Leg(boolean accepted, String reason, Map<String, CashAccount> accounts,
                       FundState funds) {
    }

    /**
     * 内部候选：业务状态 + 资金视图。
     *
     * @param accepted 是否成功
     * @param reason   结果码
     * @param state    候选业务状态
     * @param accounts 候选现金账户表
     * @param funds    候选资金三态；旧现金路径为 null
     */
    private record Settlement(boolean accepted, String reason, State state,
                              Map<String, CashAccount> accounts, FundState funds) {

        /** @return 旧现金接口的结果 */
        Outcome outcome() {
            return new Outcome(accepted, reason, state, accounts);
        }

        /** @return 显式资金腿接口的结果 */
        FundOutcome funded() {
            Objects.requireNonNull(funds, "显式路径候选缺少资金三态");
            return new FundOutcome(accepted, reason, state, funds);
        }
    }

    private static Outcome accepted(State state, Map<String, CashAccount> accounts, String reason) {
        return new Outcome(true, reason, state, accounts);
    }

    private static Outcome rejected(State state, Map<String, CashAccount> accounts, String reason) {
        return new Outcome(false, reason, state, accounts);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    /** 在不可变状态之上做局部候选修改，构造时再统一复制与校验。 */
    private static final class Draft {
        private final State base;
        private Equipment equipment;
        private Inventory rawMaterials;
        private Inventory finishedGoods;
        private final Map<String, PurchaseOrder> purchaseOrders;
        private final Map<String, ProductionBatch> productionBatches;
        private final Map<String, SalesContract> contracts;
        private final Map<String, Receivable> receivables;
        private final Map<String, ExpenseBill> expenseBills;
        private final Set<String> usedIds;

        private Draft(State base) {
            this.base = base;
            this.equipment = base.equipment();
            this.rawMaterials = base.rawMaterials();
            this.finishedGoods = base.finishedGoods();
            this.purchaseOrders = new LinkedHashMap<>(base.purchaseOrders());
            this.productionBatches = new LinkedHashMap<>(base.productionBatches());
            this.contracts = new LinkedHashMap<>(base.contracts());
            this.receivables = new LinkedHashMap<>(base.receivables());
            this.expenseBills = new LinkedHashMap<>(base.expenseBills());
            this.usedIds = new LinkedHashSet<>(base.usedIds());
        }

        private State build() {
            return new State(base.opening(), base.initialEquity(), base.initialWorldCash(),
                    equipment, rawMaterials, finishedGoods, purchaseOrders, productionBatches,
                    contracts, receivables, expenseBills, usedIds);
        }
    }
}
