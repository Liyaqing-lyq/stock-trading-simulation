package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.ManufacturingModel.Inventory;
import edu.cufe.auction.world.bank.FundState;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 三类制造模板（v2）纯核：客户认证、技术代际、BOM 物耗、期间产能配额、
 * 冻结材料报价、资本支出与爬坡、失败残余资产与有限买方处置。
 *
 * <p><b>能力边界</b>：本类只做纯计算，不持日志、不调度、不提交、不做权限投影；
 * 现金只存在于调用方传入的 {@link CashLedger.CashAccount} 账户表，银行与托管只以
 * 显式 {@link FundState} 传递。旧的 {@link ManufacturingModel} 与既有档位原样保留，
 * 本类只在显式启用新模板的调用方使用。</p>
 *
 * <p><b>金额与数量</b>：金额 scale 2（HALF_UP），数量/容量/时间 long，成本单价只作
 * 4 位展示派生值。批次耗料按账面金额比例分配，最后一批精确结尽尾差，复用
 * {@link ManufacturingModel#allocate(Inventory, long)}。</p>
 *
 * <p><b>认证</b>：结果与就绪时点由冻结试验计划按“客户+产品+代际+轮次”唯一绑定；
 * 模型不能选计划、不能重抽。申请必须有已类型化的发布/实读证据、正工时与正费用；
 * 费用未付或完工时点未到不得生成认证。新代际必须独立认证，旧认证不继承。</p>
 *
 * <p><b>时间</b>：状态带 watermark；不允许回退；未关期间不得被后续动作跨过。
 * 扩产产能按整数 ramp 公式逐期间生效，不追溯此前期间已占用量。</p>
 */
public final class ManufacturingTemplateModel {

    /** 一个实验日毫秒数。 */
    public static final long DAY_MILLIS = 86_400_000L;
    /** 一个实验期间毫秒数（30 日）。 */
    public static final long PERIOD_MILLIS = 30L * DAY_MILLIS;
    /** 当前采购合同的固定到货提前期；执行与经理公开条款共用。 */
    public static final long MATERIAL_LEAD_MILLIS = DAY_MILLIS;
    /** 当前销售合同从实际交付起计算的应收账期；不表示付款保证。 */
    public static final long PAYMENT_TERM_MILLIS = 15L * DAY_MILLIS;

    /** 结果：成功（无具体业务码时的通用成功）。 */
    public static final String OK = "OK";
    /** 结果：发起人不是公司财产主体。 */
    public static final String NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 结果：命令标识同内容重放，返回原回报。 */
    public static final String REPLAYED = "REPLAYED";
    /** 结果：同一命令标识对应不同内容。 */
    public static final String COMMAND_CONTENT_CONFLICT = "COMMAND_CONTENT_CONFLICT";
    /** 结果：资金层已有该标识记录而业务未记。 */
    public static final String FUND_ID_ALREADY_RECORDED = "FUND_ID_ALREADY_RECORDED";
    /** 结果：业务已记成功而资金层缺成功记录。 */
    public static final String FUND_RECORD_DIVERGED = "FUND_RECORD_DIVERGED";
    /** 结果：付款方/收款方真实主体不符。 */
    public static final String FUND_OWNER_MISMATCH = "FUND_OWNER_MISMATCH";
    /** 结果：现金不足。 */
    public static final String INSUFFICIENT_CASH = "INSUFFICIENT_CASH";
    /** 结果：时间回退。 */
    public static final String TIME_REWIND = "TIME_REWIND";
    /** 结果：必须先把前一个期间关账。 */
    public static final String PERIOD_NOT_CLOSED = "PERIOD_NOT_CLOSED";
    /** 结果：期间尚未结束。 */
    public static final String PERIOD_NOT_ENDED = "PERIOD_NOT_ENDED";
    /** 结果：该期间已关账。 */
    public static final String PERIOD_ALREADY_CLOSED = "PERIOD_ALREADY_CLOSED";
    /** 结果：未知物料。 */
    public static final String UNKNOWN_MATERIAL = "UNKNOWN_MATERIAL";
    /** 结果：无可用报价。 */
    public static final String NO_QUOTE = "NO_QUOTE";
    /** 结果：超过供应商当期容量。 */
    public static final String SUPPLIER_CAPACITY_EXCEEDED = "SUPPLIER_CAPACITY_EXCEEDED";
    /** 结果：数量非法。 */
    public static final String INVALID_QUANTITY = "INVALID_QUANTITY";
    /** 结果：未知采购单。 */
    public static final String UNKNOWN_PURCHASE_ORDER = "UNKNOWN_PURCHASE_ORDER";
    /** 结果：采购单状态不允许。 */
    public static final String PURCHASE_ORDER_STATE_INVALID = "PURCHASE_ORDER_STATE_INVALID";
    /** 结果：采购未到货。 */
    public static final String PURCHASE_NOT_DUE = "PURCHASE_NOT_DUE";
    /** 结果：到货付不起；不增库存、不记应付。 */
    public static final String FAILED_NO_CASH = "FAILED_NO_CASH";
    /** 结果：未知产品/代际。 */
    public static final String UNKNOWN_PRODUCT = "UNKNOWN_PRODUCT";
    /** 结果：物料不足。 */
    public static final String MATERIAL_SHORT = "MATERIAL_SHORT";
    /** 结果：超过当期产能配额。 */
    public static final String CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED";
    /** 结果：扩产合计会让产能超过 long 可表示范围；拒绝且不扣钱。 */
    public static final String CAPACITY_OVERFLOW = "CAPACITY_OVERFLOW";
    /** 结果：未知批次。 */
    public static final String UNKNOWN_BATCH = "UNKNOWN_BATCH";
    /** 结果：批次状态不允许。 */
    public static final String BATCH_STATE_INVALID = "BATCH_STATE_INVALID";
    /** 结果：批次未完工。 */
    public static final String BATCH_NOT_DUE = "BATCH_NOT_DUE";
    /** 结果：该产品不需要认证（模板显式豁免）。 */
    public static final String CERT_NOT_REQUIRED = "CERT_NOT_REQUIRED";
    /** 结果：证据与试验绑定不符或未完成阅读。 */
    public static final String EVIDENCE_INVALID = "EVIDENCE_INVALID";
    /** 结果：冻结计划中没有该轮次。 */
    public static final String NO_CERT_PLAN = "NO_CERT_PLAN";
    /** 结果：该轮次已申请过，不能重抽。 */
    public static final String TRIAL_ALREADY_REQUESTED = "TRIAL_ALREADY_REQUESTED";
    /** 结果：认证工时未完成。 */
    public static final String CERT_NOT_DUE = "CERT_NOT_DUE";
    /** 结果：试验失败，不产生认证。 */
    public static final String CERT_FAILED = "CERT_FAILED";
    /** 结果：未知试验。 */
    public static final String UNKNOWN_TRIAL = "UNKNOWN_TRIAL";
    /** 结果：未持有有效认证。 */
    public static final String NOT_CERTIFIED = "NOT_CERTIFIED";
    /** 结果：客户/产品不在该模板。 */
    public static final String UNKNOWN_CUSTOMER = "UNKNOWN_CUSTOMER";
    /** 结果：超过客户当期需求。 */
    public static final String DEMAND_EXCEEDED = "DEMAND_EXCEEDED";
    /** 结果：超过客户当期预算。 */
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    /** 结果：价格超过模板上限。 */
    public static final String PRICE_ABOVE_CAP = "PRICE_ABOVE_CAP";
    /** 结果：交付时间窗口非法。 */
    public static final String DELIVERY_WINDOW = "DELIVERY_WINDOW";
    /** 结果：未知合同。 */
    public static final String UNKNOWN_CONTRACT = "UNKNOWN_CONTRACT";
    /** 结果：合同状态不允许。 */
    public static final String CONTRACT_STATE_INVALID = "CONTRACT_STATE_INVALID";
    /** 结果：逾期后不得再交付。 */
    public static final String CONTRACT_OVERDUE = "CONTRACT_OVERDUE";
    /** 结果：超过合同未交数量。 */
    public static final String DELIVERY_EXCEEDS_CONTRACT = "DELIVERY_EXCEEDS_CONTRACT";
    /** 结果：成品库存不足。 */
    public static final String INSUFFICIENT_FINISHED_GOODS = "INSUFFICIENT_FINISHED_GOODS";
    /** 结果：未知应收。 */
    public static final String UNKNOWN_RECEIVABLE = "UNKNOWN_RECEIVABLE";
    /** 结果：应收未到期。 */
    public static final String RECEIVABLE_NOT_DUE = "RECEIVABLE_NOT_DUE";
    /** 结果：应收已结清或已核销。 */
    public static final String RECEIVABLE_CLOSED = "RECEIVABLE_CLOSED";
    /** 结果：坏账宽限期未到。 */
    public static final String BAD_DEBT_NOT_DUE = "BAD_DEBT_NOT_DUE";
    /** 结果：未知扩产项目。 */
    public static final String UNKNOWN_EXPANSION = "UNKNOWN_EXPANSION";
    /** 结果：扩产状态不允许。 */
    public static final String EXPANSION_STATE_INVALID = "EXPANSION_STATE_INVALID";
    /** 结果：扩产未到完工时点。 */
    public static final String EXPANSION_NOT_DUE = "EXPANSION_NOT_DUE";
    /** 结果：没有可售残余资产。 */
    public static final String NO_RESIDUAL = "NO_RESIDUAL";
    /** 结果：买方不是独立外部主体。 */
    public static final String BUYER_INVALID = "BUYER_INVALID";
    /** 结果：买方资金不足。 */
    public static final String BUYER_CASH_SHORT = "BUYER_CASH_SHORT";
    /** 结果：标识已使用。 */
    public static final String ID_REUSED = "ID_REUSED";

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private ManufacturingTemplateModel() {
    }

    // ---------------------------------------------------------------- 规格

    /**
     * 产品规格：一代产品一条，BOM 与人工只属于该代。
     *
     * @param productId    产品标识
     * @param generation   代际（正整数）
     * @param bom          每单位成品消耗的物料数量
     * @param laborPerUnit 每单位人工（EXP）
     * @param cycleMillis  投产到完工的模拟毫秒
     * @param certRequired 该代是否必须客户认证
     */
    public record ProductSpec(String productId, int generation, Map<String, Long> bom,
                              BigDecimal laborPerUnit, long cycleMillis, boolean certRequired) {

        /** 校验标识、代际、BOM、人工与周期。 */
        public ProductSpec {
            requireText(productId, "产品");
            if (generation <= 0) {
                throw new IllegalArgumentException("代际必须为正");
            }
            Map<String, Long> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : Objects.requireNonNull(bom, "BOM").entrySet()) {
                requireText(entry.getKey(), "物料");
                if (entry.getValue() == null || entry.getValue() <= 0) {
                    throw new IllegalArgumentException("BOM 单耗必须为正");
                }
                copy.put(entry.getKey(), entry.getValue());
            }
            bom = Collections.unmodifiableMap(copy);
            laborPerUnit = money(laborPerUnit);
            if (laborPerUnit.signum() < 0 || cycleMillis <= 0) {
                throw new IllegalArgumentException("人工不能为负且周期必须为正");
            }
        }

        /** @return 产品+代际键 */
        public String key() {
            return productId + ":" + generation;
        }
    }

    /**
     * 客户规格：有限需求、可选需求窗口与预算。
     *
     * @param customerId      客户标识
     * @param demandPerPeriod 每期需求上限（件）
     * @param demandWindow    允许签约的期间集合；null 表示不限
     * @param budgetPerPeriod 每期预算上限
     */
    public record CustomerSpec(String customerId, long demandPerPeriod, Set<Long> demandWindow,
                               BigDecimal budgetPerPeriod) {

        /** 校验客户、需求、窗口与预算。 */
        public CustomerSpec {
            requireText(customerId, "客户");
            if (demandPerPeriod < 0) {
                throw new IllegalArgumentException("需求不能为负");
            }
            demandWindow = demandWindow == null ? null : Set.copyOf(demandWindow);
            budgetPerPeriod = money(budgetPerPeriod);
            if (budgetPerPeriod.signum() < 0) {
                throw new IllegalArgumentException("预算不能为负");
            }
        }

        /** @param period 期间 @return 该期是否允许签约 */
        public boolean allows(long period) {
            return demandWindow == null || demandWindow.contains(period);
        }
    }

    /**
     * 材料报价：外部供应商发布，按 asOf 冻结。
     *
     * @param materialId        物料标识
     * @param supplierId        供应商标识
     * @param publishedAt       发布时点
     * @param asOf              生效时点
     * @param validTo           失效时点（开区间）
     * @param price             单价（EXP）
     * @param supplyCapPerPeriod 供应商每期容量
     */
    public record MaterialQuote(String materialId, String supplierId, long publishedAt, long asOf,
                                long validTo, BigDecimal price, long supplyCapPerPeriod) {

        /** 校验时间、价格与容量。 */
        public MaterialQuote {
            requireText(materialId, "物料");
            requireText(supplierId, "供应商");
            price = money(price);
            if (publishedAt < 0 || asOf < 0 || validTo <= asOf || supplyCapPerPeriod < 0
                    || price.signum() <= 0) {
                throw new IllegalArgumentException("报价时间/价格/容量非法");
            }
        }

        /** @param now 当前时点 @return 当前可见且生效 */
        public boolean visibleAt(long now) {
            return publishedAt <= now && asOf <= now && now < validTo;
        }
    }

    /**
     * 冻结认证计划：同一“客户+产品+代际+轮次”只允许一条，结果不可重抽。
     *
     * @param customerId 客户
     * @param productId  产品
     * @param generation 代际
     * @param round      试验轮次（从 1 开始）
     * @param pass       该轮是否通过
     * @param readyAt    结果就绪时点
     */
    public record CertPlan(String customerId, String productId, int generation, int round,
                           boolean pass, long readyAt) {

        /** 校验键与就绪时点。 */
        public CertPlan {
            requireText(customerId, "客户");
            requireText(productId, "产品");
            if (generation <= 0 || round <= 0 || readyAt < 0) {
                throw new IllegalArgumentException("认证计划键或时点非法");
            }
        }

        /** @return 唯一键 */
        public String key() {
            return customerId + "|" + productId + "|" + generation + "|" + round;
        }
    }

    /**
     * 类型化认证证据：pure 层只接受“已发布且实际读完”的输入，真实人物核验由 World 负责。
     *
     * @param evidenceId  证据标识
     * @param customerId  客户
     * @param productId   产品
     * @param generation  代际
     * @param publishedAt 发布时点
     * @param readAt      实际读完时点
     * @param readBy      阅读人标识
     */
    public record CertEvidence(String evidenceId, String customerId, String productId,
                               int generation, long publishedAt, long readAt, String readBy) {

        /** 校验标识、绑定与阅读时间。 */
        public CertEvidence {
            requireText(evidenceId, "证据");
            requireText(customerId, "客户");
            requireText(productId, "产品");
            requireText(readBy, "阅读人");
            if (generation <= 0 || publishedAt < 0 || readAt < publishedAt) {
                throw new IllegalArgumentException("证据发布时间或阅读时间非法");
            }
        }

        /** @param now 当前时点 @return 证据已发布且已读完 */
        public boolean readableAt(long now) {
            return publishedAt <= now && readAt <= now;
        }
    }

    /**
     * 模板规格：一家公司一份，三个行业由三份显式规格表达而不是三个标签。
     *
     * @param templateId             模板标识
     * @param products               产品/代际表
     * @param customers              客户表
     * @param quotes                 冻结报价序列
     * @param plans                  冻结认证试验计划
     * @param baseCapacityPerPeriod  基础产能（每期件数）
     * @param priceCap               合同单价上限
     * @param periodExpense          每期固定费用
     * @param depreciationPerPeriod  每期折旧上限
     * @param certFee                每次认证费用（启用认证时必须为正）
     * @param certWorkMillis         认证正工时（启用认证时必须为正）
     * @param certValidityMillis     认证有效期
     * @param residualRate           建设失败时保留的残余资产比例（EXP）
     * @param badDebtGraceMillis     应收到期后的坏账宽限期
     */
    public record TemplateSpec(String templateId, List<ProductSpec> products,
                               List<CustomerSpec> customers, List<MaterialQuote> quotes,
                               List<CertPlan> plans,
                               long baseCapacityPerPeriod, BigDecimal priceCap,
                               BigDecimal periodExpense, BigDecimal depreciationPerPeriod,
                               BigDecimal certFee, long certWorkMillis, long certValidityMillis,
                               BigDecimal residualRate, long badDebtGraceMillis) {

        /** 校验并建立不可变索引；启用认证时费用与工时必须为正。 */
        public TemplateSpec {
            requireText(templateId, "模板");
            List<ProductSpec> productList = List.copyOf(Objects.requireNonNull(products, "产品表"));
            if (productList.isEmpty()) {
                throw new IllegalArgumentException("模板至少一个产品");
            }
            Map<String, ProductSpec> byKey = new LinkedHashMap<>();
            for (ProductSpec product : productList) {
                if (byKey.put(product.key(), product) != null) {
                    throw new IllegalArgumentException("产品/代际重复：" + product.key());
                }
            }
            products = List.copyOf(byKey.values());
            customers = List.copyOf(Objects.requireNonNull(customers, "客户表"));
            quotes = List.copyOf(Objects.requireNonNull(quotes, "报价表"));
            List<CertPlan> planList = List.copyOf(Objects.requireNonNull(plans, "认证计划"));
            Set<String> planKeys = new LinkedHashSet<>();
            for (CertPlan plan : planList) {
                if (!planKeys.add(plan.key())) {
                    throw new IllegalArgumentException("认证计划键重复：" + plan.key());
                }
            }
            plans = planList;
            if (baseCapacityPerPeriod <= 0 || certValidityMillis < 0 || badDebtGraceMillis < 0) {
                throw new IllegalArgumentException("产能必须为正，有效期/宽限期不能为负");
            }
            priceCap = money(priceCap);
            periodExpense = money(periodExpense);
            depreciationPerPeriod = money(depreciationPerPeriod);
            certFee = money(certFee);
            residualRate = money(residualRate);
            if (priceCap.signum() <= 0 || periodExpense.signum() < 0
                    || depreciationPerPeriod.signum() < 0 || certFee.signum() < 0
                    || residualRate.signum() < 0 || residualRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("价格/费用/残值比例非法");
            }
            boolean anyCert = productList.stream().anyMatch(ProductSpec::certRequired);
            if (anyCert && (certFee.signum() <= 0 || certWorkMillis <= 0
                    || certValidityMillis <= 0)) {
                throw new IllegalArgumentException("启用认证的模板必须配置正费用、正工时与正有效期");
            }
        }

        /** @param productId 产品 @param generation 代际 @return 规格或 null */
        public ProductSpec product(String productId, int generation) {
            for (ProductSpec product : products) {
                if (product.productId().equals(productId) && product.generation() == generation) {
                    return product;
                }
            }
            return null;
        }

        /** @param customerId 客户 @return 规格或 null */
        public CustomerSpec customer(String customerId) {
            for (CustomerSpec customer : customers) {
                if (customer.customerId().equals(customerId)) {
                    return customer;
                }
            }
            return null;
        }
    }

    /**
     * 账户绑定：公司、运营、认证方、承包方、处置买方，以及客户/供应商账户表。
     *
     * @param companyOwnerId     公司财产主体
     * @param companyAccountId   公司经营账户
     * @param opsAccountId       人工/费用账户
     * @param certifierAccountId 认证方账户
     * @param contractorAccountId 建设承包方账户
     * @param disposalBuyerAccountId 残余资产处置买方账户
     * @param customerAccounts   客户 → 账户
     * @param supplierAccounts   供应商 → 账户
     */
    public record Accounts(String companyOwnerId, String companyAccountId, String opsAccountId,
                           String certifierAccountId, String contractorAccountId,
                           String disposalBuyerAccountId, Map<String, String> customerAccounts,
                           Map<String, String> supplierAccounts) {

        /** 校验必填项与账户唯一性。 */
        public Accounts {
            requireText(companyOwnerId, "公司所有者");
            requireText(companyAccountId, "公司账户");
            requireText(opsAccountId, "运营账户");
            requireText(certifierAccountId, "认证账户");
            requireText(contractorAccountId, "承包方账户");
            requireText(disposalBuyerAccountId, "处置买方账户");
            customerAccounts = Map.copyOf(Objects.requireNonNull(customerAccounts, "客户账户"));
            supplierAccounts = Map.copyOf(Objects.requireNonNull(supplierAccounts, "供应商账户"));
            Set<String> ids = new LinkedHashSet<>(List.of(companyAccountId, opsAccountId,
                    certifierAccountId, contractorAccountId, disposalBuyerAccountId));
            ids.addAll(customerAccounts.values());
            ids.addAll(supplierAccounts.values());
            if (ids.size() != 5 + customerAccounts.size() + supplierAccounts.size()) {
                throw new IllegalArgumentException("账户标识必须互不相同");
            }
        }
    }

    // ---------------------------------------------------------------- 业务记录

    /** 采购单状态。 */
    public enum PurchaseStatus {
        /** 已下单。 */ ORDERED,
        /** 已到货并入账。 */ RECEIVED,
        /** 到货时现金不足。 */ FAILED_NO_CASH
    }

    /** 批次状态。 */
    public enum BatchStatus {
        /** 在制。 */ IN_PROGRESS,
        /** 已完工转成品。 */ FINISHED
    }

    /** 合同状态。 */
    public enum ContractStatus {
        /** 有效。 */ ACTIVE,
        /** 逾期未交且已释放需求。 */ OVERDUE,
        /** 取消且已释放需求。 */ CANCELLED,
        /** 全部交付。 */ FULFILLED
    }

    /** 应收状态。 */
    public enum ReceivableStatus {
        /** 未收。 */ OPEN,
        /** 已收。 */ COLLECTED,
        /** 坏账核销。 */ WRITTEN_OFF
    }

    /** 认证试验状态。 */
    public enum TrialStatus {
        /** 已付款、工时中。 */ IN_PROGRESS,
        /** 通过并生成认证。 */ PASSED,
        /** 失败。 */ FAILED
    }

    /** 扩产状态。 */
    public enum ExpansionStatus {
        /** 建设中。 */ BUILDING,
        /** 已投产。 */ ONLINE,
        /** 建设失败、留残余资产。 */ FAILED
    }

    /**
     * 采购单：单价在可见报价中锁定，不随之后报价重估。
     *
     * @param purchaseOrderId 标识
     * @param materialId      物料
     * @param supplierId      供应商
     * @param quantity        数量
     * @param unitPrice       锁定单价
     * @param orderedAt       下单时点
     * @param arrivalAt       到货时点
     * @param status          状态
     */
    public record PurchaseOrder(String purchaseOrderId, String materialId, String supplierId,
                                long quantity, BigDecimal unitPrice, long orderedAt, long arrivalAt,
                                PurchaseStatus status) {

        /** 校验数量、价格与时间。 */
        public PurchaseOrder {
            requireText(purchaseOrderId, "采购单");
            requireText(materialId, "物料");
            requireText(supplierId, "供应商");
            if (quantity <= 0 || arrivalAt <= orderedAt) {
                throw new IllegalArgumentException("采购数量或时间非法");
            }
            unitPrice = money(unitPrice);
            Objects.requireNonNull(status, "采购状态");
        }
    }

    /**
     * 生产批次：材料与人工成本资本化，完工转成品。
     *
     * @param batchId      标识
     * @param productId    产品
     * @param generation   代际
     * @param quantity     数量
     * @param startedAt    投产时点
     * @param completesAt  完工时点
     * @param materialCost 账面材料成本
     * @param laborCost    人工成本
     * @param status       状态
     */
    public record Batch(String batchId, String productId, int generation, long quantity,
                        long startedAt, long completesAt, BigDecimal materialCost,
                        BigDecimal laborCost, BatchStatus status) {

        /** 校验数量、时间与成本。 */
        public Batch {
            requireText(batchId, "批次");
            requireText(productId, "产品");
            if (generation <= 0 || quantity <= 0 || completesAt <= startedAt) {
                throw new IllegalArgumentException("批次数量/代际/时间非法");
            }
            materialCost = money(materialCost);
            laborCost = money(laborCost);
            Objects.requireNonNull(status, "批次状态");
        }

        /** @return 批次总成本 */
        public BigDecimal totalCost() {
            return materialCost.add(laborCost);
        }
    }

    /**
     * 认证试验：唯一绑定客户+产品+代际+轮次；费用已付、工时完成后才能出结果。
     *
     * @param trialId   标识
     * @param customerId 客户
     * @param productId 产品
     * @param generation 代际
     * @param round     轮次
     * @param requestedAt 申请时点
     * @param finishAt  最早可出结果时点
     * @param fee       已付费用
     * @param evidenceId 证据标识
     * @param status    状态
     */
    public record CertTrial(String trialId, String customerId, String productId, int generation,
                            int round, long requestedAt, long finishAt, BigDecimal fee,
                            String evidenceId, TrialStatus status) {

        /** 校验绑定与费用。 */
        public CertTrial {
            requireText(trialId, "试验");
            requireText(customerId, "客户");
            requireText(productId, "产品");
            requireText(evidenceId, "证据");
            if (generation <= 0 || round <= 0 || finishAt <= requestedAt) {
                throw new IllegalArgumentException("试验键或时点非法");
            }
            fee = money(fee);
            Objects.requireNonNull(status, "试验状态");
        }

        /** @return 唯一绑定键 */
        public String key() {
            return customerId + "|" + productId + "|" + generation + "|" + round;
        }
    }

    /**
     * 客户认证：绑定客户+产品+代际，到期后不得新签。
     *
     * @param certId     标识
     * @param customerId 客户
     * @param productId  产品
     * @param generation 代际
     * @param trialId    来源试验
     * @param issuedAt   签发时点
     * @param expiresAt  失效时点（开区间）
     */
    public record Cert(String certId, String customerId, String productId, int generation,
                       String trialId, long issuedAt, long expiresAt) {

        /** 校验有效期。 */
        public Cert {
            requireText(certId, "认证");
            requireText(customerId, "客户");
            requireText(productId, "产品");
            requireText(trialId, "试验");
            if (generation <= 0 || expiresAt <= issuedAt) {
                throw new IllegalArgumentException("认证有效期非法");
            }
        }

        /** @param now 时点 @return 在有效期内 */
        public boolean validAt(long now) {
            return issuedAt <= now && now < expiresAt;
        }
    }

    /**
     * 客户合同：需求/预算在签约时占用，逾期或取消只释放一次。
     *
     * @param contractId 标识
     * @param customerId 客户
     * @param productId  产品
     * @param generation 代际
     * @param quantity   数量
     * @param delivered  已交付
     * @param reserved   当前预约量（等于未交量，释放后为 0）
     * @param unitPrice  单价
     * @param signedAt   签约时点
     * @param promisedAt 承诺交付时点
     * @param expiresAt  逾期时点
     * @param period     签约期间
     * @param status     状态
     * @param released   未交需求承诺是否已释放
     */
    public record Contract(String contractId, String customerId, String productId, int generation,
                           long quantity, long delivered, long reserved, BigDecimal unitPrice,
                           long signedAt,
                           long promisedAt, long expiresAt, long period, ContractStatus status,
                           boolean released) {

        /** 校验数量、价格与状态；预约量必须等于未交付量或已释放为 0。 */
        public Contract {
            requireText(contractId, "合同");
            requireText(customerId, "客户");
            requireText(productId, "产品");
            if (generation <= 0 || quantity <= 0 || delivered < 0 || delivered > quantity
                    || promisedAt <= signedAt || expiresAt <= promisedAt) {
                throw new IllegalArgumentException("合同数量/时间非法");
            }
            if (reserved < 0 || reserved > quantity - delivered) {
                throw new IllegalArgumentException("合同预约量非法：" + reserved);
            }
            if (released && reserved != 0) {
                throw new IllegalArgumentException("已释放合同预约量必须为 0");
            }
            unitPrice = money(unitPrice);
            Objects.requireNonNull(status, "合同状态");
        }

        /** @return 未交数量 */
        public long remaining() {
            return quantity - delivered;
        }
    }

    /**
     * 应收：交付时确认；收款只冲减，不再确认收入；坏账按最小实验政策核销。
     *
     * @param receivableId 标识
     * @param contractId   合同
     * @param customerId   客户
     * @param quantity     数量
     * @param amount       确认收入
     * @param cost         结转成本
     * @param recognizedAt 确认时点
     * @param dueAt        到期时点
     * @param outstanding  未收金额
     * @param status       状态
     */
    public record Receivable(String receivableId, String contractId, String customerId,
                             long quantity, BigDecimal amount, BigDecimal cost, long recognizedAt,
                             long dueAt, BigDecimal outstanding, ReceivableStatus status) {

        /** 校验金额与状态一致性。 */
        public Receivable {
            requireText(receivableId, "应收");
            requireText(contractId, "合同");
            requireText(customerId, "客户");
            if (quantity <= 0 || dueAt < recognizedAt) {
                throw new IllegalArgumentException("应收数量或时间非法");
            }
            amount = money(amount);
            cost = money(cost);
            outstanding = money(outstanding);
            if (amount.signum() <= 0 || cost.signum() < 0 || outstanding.signum() < 0
                    || outstanding.compareTo(amount) > 0) {
                throw new IllegalArgumentException("应收金额非法");
            }
            Objects.requireNonNull(status, "应收状态");
            boolean closed = status != ReceivableStatus.OPEN;
            if (closed != (outstanding.signum() == 0)) {
                throw new IllegalArgumentException("应收状态与未收金额不一致");
            }
        }
    }

    /**
     * 扩产项目：capex 先入 CIP，建成后按 ramp 逐期释放产能；失败只保留残值资产。
     *
     * @param projectId   标识
     * @param capex       资本支出
     * @param startedAt   开工时点
     * @param completesAt 计划完工时点
     * @param rampPeriods 爬坡期间数
     * @param addCapacity 满产后新增产能
     * @param status      状态
     * @param onlinePeriod 建成所在期间（未建成为 -1）
     * @param residual    失败时保留的残余资产账面值
     */
    public record Expansion(String projectId, BigDecimal capex, long startedAt, long completesAt,
                            int rampPeriods, long addCapacity, ExpansionStatus status,
                            long onlinePeriod, BigDecimal residual) {

        /** 校验金额、时间与 ramp。 */
        public Expansion {
            requireText(projectId, "扩产项目");
            capex = money(capex);
            residual = money(residual);
            if (capex.signum() <= 0 || completesAt <= startedAt || rampPeriods <= 0
                    || addCapacity <= 0) {
                throw new IllegalArgumentException("扩产参数非法");
            }
            Objects.requireNonNull(status, "扩产状态");
        }
    }

    /**
     * 期末费用账单：发生与付款分开，未付成应付。
     *
     * @param period 期间
     * @param amount 确认费用
     * @param paid   已付
     */
    public record ExpenseBill(long period, BigDecimal amount, BigDecimal paid) {

        /** 校验费用。 */
        public ExpenseBill {
            amount = money(amount);
            paid = money(paid);
            if (period < 0 || amount.signum() <= 0 || paid.signum() < 0
                    || paid.compareTo(amount) > 0) {
                throw new IllegalArgumentException("费用账单非法");
            }
        }

        /** @return 未付金额 */
        public BigDecimal outstanding() {
            return amount.subtract(paid);
        }
    }

    /**
     * 不可变状态。
     *
     * @param spec            模板
     * @param accounts        账户绑定
     * @param openingOwners   开局冻结的账户→业务主体映射
     * @param initialEquity   初始权益
     * @param initialWorldCash 开局世界现金
     * @param watermark       已见时间轴
     * @param materials       物料库存
     * @param products        产品库存（产品:代际）
     * @param purchases       采购单
     * @param batches         批次
     * @param trials          认证试验
     * @param certs           认证
     * @param contracts       合同
     * @param receivables     应收
     * @param expansions      扩产
     * @param residualAssets  失败残余资产（项目 → 账面值）
     * @param capacityUsed    期间 → 已占产能
     * @param bills           期间 → 费用账单
     * @param closedPeriods   已连续关账期间数
     * @param commandContents 命令内容（幂等）
     * @param commandReplies  命令回报（幂等）
     * @param disposalResult  残余处置损益（正为收益）
     * @param badDebtLoss     坏账损失
     * @param usedIds         已用标识
     */
    public record State(TemplateSpec spec, Accounts accounts, Map<String, String> openingOwners,
                        BigDecimal initialEquity,
                        BigDecimal initialWorldCash, long watermark,
                        Map<String, Inventory> materials, Map<String, Inventory> products,
                        Map<String, PurchaseOrder> purchases, Map<String, Batch> batches,
                        Map<String, CertTrial> trials, Map<String, Cert> certs,
                        Map<String, Contract> contracts, Map<String, Receivable> receivables,
                        Map<String, Expansion> expansions, Map<String, BigDecimal> residualAssets,
                        Map<Long, Long> capacityUsed, Map<Long, ExpenseBill> bills,
                        long closedPeriods, Map<String, String> commandContents,
                        Map<String, Reply> commandReplies, BigDecimal disposalResult,
                        BigDecimal badDebtLoss, BigDecimal certExpense,
                        BigDecimal accumulatedDepreciation,
                        Set<String> usedIds) {

        /** 防御性复制与基础校验。 */
        public State {
            Objects.requireNonNull(spec, "模板");
            Objects.requireNonNull(accounts, "账户");
            openingOwners = Map.copyOf(Objects.requireNonNull(openingOwners, "开局主体映射"));
            initialEquity = money(initialEquity);
            initialWorldCash = money(initialWorldCash);
            disposalResult = money(disposalResult);
            badDebtLoss = money(badDebtLoss);
            certExpense = money(certExpense);
            accumulatedDepreciation = money(accumulatedDepreciation);
            materials = Map.copyOf(materials);
            products = Map.copyOf(products);
            purchases = Map.copyOf(purchases);
            batches = Map.copyOf(batches);
            trials = Map.copyOf(trials);
            certs = Map.copyOf(certs);
            contracts = Map.copyOf(contracts);
            receivables = Map.copyOf(receivables);
            expansions = Map.copyOf(expansions);
            residualAssets = Map.copyOf(residualAssets);
            capacityUsed = Map.copyOf(capacityUsed);
            bills = Map.copyOf(bills);
            commandContents = Map.copyOf(commandContents);
            commandReplies = Map.copyOf(commandReplies);
            usedIds = Set.copyOf(usedIds);
            if (watermark < 0 || closedPeriods < 0) {
                throw new IllegalArgumentException("时间轴或关账数不能为负");
            }
        }

        /**
         * 开局冻结的账户主体；不存在返回 null。
         *
         * @param accountId 账户
         * @return 业务主体
         */
        public String openingOwnerOf(String accountId) {
            return accountId == null ? null : openingOwners.get(accountId);
        }
    }

    /** 幂等回报。 */
    public record Reply(boolean accepted, String reason, boolean paid) {
    }

    /**
     * 现金候选结果。
     *
     * @param accepted 业务是否成功
     * @param reason   结果码
     * @param state    候选状态
     * @param accounts 候选现金账户表
     */
    public record Outcome(boolean accepted, String reason, State state,
                          Map<String, CashAccount> accounts) {
    }

    /**
     * 显式资金候选结果。
     *
     * @param accepted 业务是否成功
     * @param reason   结果码
     * @param state    候选状态
     * @param funds    候选资金三态
     */
    public record FundOutcome(boolean accepted, String reason, State state, FundState funds) {
    }

    /**
     * 报表：与旧制造报表同口径增加 CIP、残余资产、产品库存与处置/坏账损益。
     *
     * @param companyCash 公司现金
     * @param materials   物料账面
     * @param products    成品账面
     * @param wip         在制成本
     * @param receivables 应收未收
     * @param equipmentNet 设备净值
     * @param cip         在建工程
     * @param residual    残余资产
     * @param payables    应付未付
     * @param assets      资产合计
     * @param liabilities 负债合计
     * @param equity      权益
     * @param revenue     累计收入
     * @param cogs        累计成本
     * @param expense     累计费用
     * @param depreciation 累计折旧
     * @param disposalResult 处置损益
     * @param badDebtLoss 坏账损失
     * @param balanced    资产=负债+权益且世界现金守恒
     * @param worldCash   世界现金
     */
    public record Report(BigDecimal companyCash, BigDecimal materials, BigDecimal products,
                         BigDecimal wip, BigDecimal receivables, BigDecimal equipmentNet,
                         BigDecimal cip, BigDecimal residual, BigDecimal payables, BigDecimal assets,
                         BigDecimal liabilities, BigDecimal equity, BigDecimal revenue,
                         BigDecimal cogs, BigDecimal expense, BigDecimal certificationExpense,
                         BigDecimal depreciation,
                         BigDecimal disposalResult, BigDecimal badDebtLoss, boolean balanced,
                         BigDecimal worldCash) {
    }

    /** 内部结算结果：现金与资金三态最多一个非空。 */
    private record Settlement(State state, boolean accepted, String reason,
                              Map<String, CashAccount> accounts, FundState funds) {
    }

    /** 一次付款腿。 */
    private record Leg(Map<String, CashAccount> accounts, FundState funds, FundPayer payer) {
    }

    /** 付款结果。 */
    private record Paid(boolean accepted, String reason, Map<String, CashAccount> accounts,
                        FundState funds, boolean moved) {
    }

    // ---------------------------------------------------------------- 开局

    /**
     * 建立开局状态：初始权益 = 公司账户现金（设备另行由 CAPEX 形成）。
     *
     * @param spec     模板
     * @param accounts 账户绑定
     * @param cash     现金账户表
     * @return 初始状态
     */
    public static State open(TemplateSpec spec, Accounts accounts,
                             Map<String, CashAccount> cash) {
        Objects.requireNonNull(spec, "模板");
        Objects.requireNonNull(accounts, "账户");
        Objects.requireNonNull(cash, "现金账户");
        Map<String, Inventory> materials = new LinkedHashMap<>();
        for (MaterialQuote quote : spec.quotes()) {
            materials.putIfAbsent(quote.materialId(), new Inventory(0, ZERO));
        }
        Set<String> ids = new LinkedHashSet<>();
        ids.add(accounts.companyAccountId());
        ids.add(accounts.opsAccountId());
        ids.add(accounts.certifierAccountId());
        ids.add(accounts.contractorAccountId());
        ids.add(accounts.disposalBuyerAccountId());
        ids.addAll(accounts.customerAccounts().values());
        ids.addAll(accounts.supplierAccounts().values());
        for (String id : ids) {
            CashAccount account = cash.get(id);
            if (account == null) {
                throw new IllegalArgumentException("缺少开局账户：" + id);
            }
            if (account.ownerId() == null || account.ownerId().isBlank()) {
                throw new IllegalArgumentException("开局账户缺少所有者：" + id);
            }
        }
        for (CustomerSpec customer : spec.customers()) {
            String accountId = accounts.customerAccounts().get(customer.customerId());
            if (accountId == null || !cash.containsKey(accountId)) {
                throw new IllegalArgumentException("规格客户缺少开户账户："
                        + customer.customerId());
            }
        }
        for (MaterialQuote quote : spec.quotes()) {
            String accountId = accounts.supplierAccounts().get(quote.supplierId());
            if (accountId == null || !cash.containsKey(accountId)) {
                throw new IllegalArgumentException("规格供应商缺少结算账户："
                        + quote.supplierId());
            }
        }
        if (!cash.get(accounts.companyAccountId()).ownerId().equals(accounts.companyOwnerId())) {
            throw new IllegalArgumentException("公司账户所有者与开局主体不一致");
        }
        Map<String, String> owners = new LinkedHashMap<>();
        for (String id : ids) {
            owners.put(id, cash.get(id).ownerId());
        }
        BigDecimal world = worldCash(cash);
        return new State(spec, accounts, owners, money(cash.get(accounts.companyAccountId()).cash()),
                world, 0L, materials, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0L, Map.of(), Map.of(),
                ZERO, ZERO, ZERO, ZERO, Set.of());
    }

    // ---------------------------------------------------------------- 采购

    /**
     * 采购下单：锁当前可见报价的单价并占供应商当期容量；不动现金。
     *
     * @param state    状态
     * @param now      当前时点
     * @param actor    发起人，必须是公司
     * @param commandId 命令
     * @param poId     采购单
     * @param materialId 物料
     * @param quantity 数量
     * @param cash     账户表
     * @return 候选
     */
    public static Outcome orderMaterial(State state, long now, String actor, String commandId,
                                        String poId, String materialId, long quantity,
                                        Map<String, CashAccount> cash) {
        return orderMaterial(state, now, actor, commandId, poId, materialId, quantity, cash, MATERIAL_LEAD_MILLIS);
    }

    static Outcome orderMaterial(State state, long now, String actor, String commandId, String poId,
                                 String materialId, long quantity, Map<String, CashAccount> cash, long leadMillis) {
        Settlement s = orderMaterialCore(state, now, actor, commandId, poId, materialId,
                quantity, new Leg(cash, null, null), leadMillis);
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载：下单本身不动钱，保留同形接口以便后继统一。 */
    public static FundOutcome orderMaterial(State state, long now, String actor, String commandId,
                                            String poId, String materialId, long quantity,
                                            FundState funds, FundPayer payer) {
        Settlement s = orderMaterialCore(state, now, actor, commandId, poId, materialId,
                quantity, new Leg(null, funds, payer), MATERIAL_LEAD_MILLIS);
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement orderMaterialCore(State state, long now, String actor,
                                                String commandId, String poId, String materialId,
                                                long quantity, Leg leg, long leadMillis) {
        String content = "ORDER|" + poId + "|" + materialId + "|" + quantity;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        if (quantity <= 0) {
            return reject(state, INVALID_QUANTITY, leg);
        }
        if (!state.materials().containsKey(materialId)) {
            return reject(state, UNKNOWN_MATERIAL, leg);
        }
        if (state.usedIds().contains(poId)) {
            return reject(state, ID_REUSED, leg);
        }
        MaterialQuote quote = latestQuote(state.spec(), materialId, now);
        if (quote == null) {
            return reject(state, NO_QUOTE, leg);
        }
        long used = 0;
        for (PurchaseOrder po : state.purchases().values()) {
            if (po.supplierId().equals(quote.supplierId()) && po.orderedAt() / PERIOD_MILLIS
                    == now / PERIOD_MILLIS) {
                used = Math.addExact(used, po.quantity());
            }
        }
        if (quantity > quote.supplyCapPerPeriod() - used) {
            return reject(state, SUPPLIER_CAPACITY_EXCEEDED, leg);
        }
        Draft draft = new Draft(state, now);
        draft.purchases.put(poId, new PurchaseOrder(poId, materialId, quote.supplierId(), quantity,
                quote.price(), now, Math.addExact(now, leadMillis), PurchaseStatus.ORDERED));
        draft.usedIds.add(poId);
        return accept(draft, REASON_ORDERED, leg, commandId, content);
    }

    /** 采购到货：按锁定价付款入账；付不起只标失败。 */
    public static Outcome receiveMaterial(State state, long now, String actor, String commandId,
                                          String poId, Map<String, CashAccount> cash) {
        Settlement s = receiveCore(state, now, actor, commandId, poId,
                new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载。 */
    public static FundOutcome receiveMaterial(State state, long now, String actor,
                                              String commandId, String poId, FundState funds,
                                              FundPayer payer) {
        Settlement s = receiveCore(state, now, actor, commandId, poId,
                new Leg(null, funds, payer));
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement receiveCore(State state, long now, String actor, String commandId,
                                          String poId, Leg leg) {
        String content = "RECEIVE|" + poId;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        PurchaseOrder po = state.purchases().get(poId);
        if (po == null) {
            return reject(state, UNKNOWN_PURCHASE_ORDER, leg);
        }
        if (po.status() != PurchaseStatus.ORDERED) {
            return reject(state, PURCHASE_ORDER_STATE_INVALID, leg);
        }
        if (now < po.arrivalAt()) {
            return reject(state, PURCHASE_NOT_DUE, leg);
        }
        BigDecimal amount = money(po.unitPrice().multiply(BigDecimal.valueOf(po.quantity())));
        Paid paid = pay(leg, commandId, state.accounts().companyAccountId(),
                state.accounts().supplierAccounts().get(po.supplierId()), amount, state);
        if (!paid.accepted()) {
            Draft failed = new Draft(state, now);
            failed.purchases.put(poId, new PurchaseOrder(po.purchaseOrderId(), po.materialId(),
                    po.supplierId(), po.quantity(), po.unitPrice(), po.orderedAt(), po.arrivalAt(),
                    PurchaseStatus.FAILED_NO_CASH));
            return new Settlement(failed.build(), false, paid.reason(), paid.accounts(),
                    paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.purchases.put(poId, new PurchaseOrder(po.purchaseOrderId(), po.materialId(),
                po.supplierId(), po.quantity(), po.unitPrice(), po.orderedAt(), po.arrivalAt(),
                PurchaseStatus.RECEIVED));
        Inventory inv = draft.materials.getOrDefault(po.materialId(), new Inventory(0, ZERO));
        draft.materials.put(po.materialId(), new Inventory(inv.quantity() + po.quantity(),
                inv.amount().add(amount)));
        return accept(draft, REASON_RECEIVED, paid, commandId, content);
    }

    // ---------------------------------------------------------------- 生产

    /** 投产：耗 BOM 物料、付人工、占当期产能。 */
    public static Outcome startBatch(State state, long now, String actor, String commandId,
                                     String batchId, String productId, int generation, long quantity,
                                     Map<String, CashAccount> cash) {
        Settlement s = startBatchCore(state, now, actor, commandId, batchId, productId,
                generation, quantity, new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载。 */
    public static FundOutcome startBatch(State state, long now, String actor, String commandId,
                                         String batchId, String productId, int generation,
                                         long quantity, FundState funds, FundPayer payer) {
        Settlement s = startBatchCore(state, now, actor, commandId, batchId, productId,
                generation, quantity, new Leg(null, funds, payer));
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement startBatchCore(State state, long now, String actor, String commandId,
                                             String batchId, String productId, int generation,
                                             long quantity, Leg leg) {
        String content = "START|" + batchId + "|" + productId + "|" + generation + "|" + quantity;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        if (quantity <= 0) {
            return reject(state, INVALID_QUANTITY, leg);
        }
        if (state.usedIds().contains(batchId)) {
            return reject(state, ID_REUSED, leg);
        }
        ProductSpec product = state.spec().product(productId, generation);
        if (product == null) {
            return reject(state, UNKNOWN_PRODUCT, leg);
        }
        long period = now / PERIOD_MILLIS;
        long used = state.capacityUsed().getOrDefault(period, 0L);
        BigInteger required = BigInteger.valueOf(used).add(BigInteger.valueOf(quantity));
        if (required.compareTo(capacityAt(state, period)) > 0) {
            return reject(state, CAPACITY_EXCEEDED, leg);
        }
        Map<String, Inventory> nextMaterials = new LinkedHashMap<>(state.materials());
        BigDecimal materialCost = ZERO;
        for (Map.Entry<String, Long> need : product.bom().entrySet()) {
            long needQty;
            try {
                needQty = Math.multiplyExact(need.getValue(), quantity);
            } catch (ArithmeticException overflow) {
                return reject(state, INVALID_QUANTITY, leg);
            }
            Inventory inv = nextMaterials.get(need.getKey());
            if (inv == null || inv.quantity() < needQty) {
                return reject(state, MATERIAL_SHORT, leg);
            }
            BigDecimal cost = ManufacturingModel.allocate(inv, needQty);
            materialCost = materialCost.add(cost);
            nextMaterials.put(need.getKey(), new Inventory(inv.quantity() - needQty,
                    inv.amount().subtract(cost)));
        }
        BigDecimal labor = money(product.laborPerUnit().multiply(BigDecimal.valueOf(quantity)));
        Paid paid = pay(leg, commandId, state.accounts().companyAccountId(),
                state.accounts().opsAccountId(), labor, state);
        if (!paid.accepted()) {
            return new Settlement(state, false, paid.reason(), paid.accounts(), paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.materials = nextMaterials;
        draft.capacityUsed.put(period, Math.addExact(used, quantity));
        draft.batches.put(batchId, new Batch(batchId, productId, generation, quantity, now,
                now + product.cycleMillis(), money(materialCost), labor, BatchStatus.IN_PROGRESS));
        draft.usedIds.add(batchId);
        return accept(draft, REASON_STARTED, paid, commandId, content);
    }

    /** 完工：到期把成本全额转入该产品库存；产能本期不释放。 */
    public static Outcome completeBatch(State state, long now, String actor, String commandId,
                                        String batchId, Map<String, CashAccount> cash) {
        Settlement s = completeCore(state, now, actor, commandId, batchId,
                new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载（不动钱，仅为接口同形）。 */
    public static FundOutcome completeBatch(State state, long now, String actor, String commandId,
                                            String batchId, FundState funds, FundPayer payer) {
        Settlement s = completeCore(state, now, actor, commandId, batchId,
                new Leg(null, funds, payer));
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement completeCore(State state, long now, String actor, String commandId,
                                           String batchId, Leg leg) {
        String content = "COMPLETE|" + batchId;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        Batch batch = state.batches().get(batchId);
        if (batch == null) {
            return reject(state, UNKNOWN_BATCH, leg);
        }
        if (batch.status() != BatchStatus.IN_PROGRESS) {
            return reject(state, BATCH_STATE_INVALID, leg);
        }
        if (now < batch.completesAt()) {
            return reject(state, BATCH_NOT_DUE, leg);
        }
        String key = batch.productId() + ":" + batch.generation();
        Draft draft = new Draft(state, now);
        Inventory inv = draft.products.getOrDefault(key, new Inventory(0, ZERO));
        draft.products.put(key, new Inventory(inv.quantity() + batch.quantity(),
                inv.amount().add(batch.totalCost())));
        draft.batches.put(batchId, new Batch(batch.batchId(), batch.productId(),
                batch.generation(), batch.quantity(), batch.startedAt(), batch.completesAt(),
                batch.materialCost(), batch.laborCost(), BatchStatus.FINISHED));
        return accept(draft, REASON_COMPLETED, leg, commandId, content);
    }

    // ---------------------------------------------------------------- 认证

    /** 申请认证：证据必须已发布且已读完；费用真实支付；工时随后开始。 */
    public static Outcome requestCert(State state, long now, String actor, String commandId,
                                      String trialId, String customerId, String productId,
                                      int generation, int round, CertEvidence evidence,
                                      Map<String, CashAccount> cash) {
        Settlement s = requestCertCore(state, now, actor, commandId, trialId, customerId,
                productId, generation, round, evidence, new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载。 */
    public static FundOutcome requestCert(State state, long now, String actor, String commandId,
                                          String trialId, String customerId, String productId,
                                          int generation, int round, CertEvidence evidence,
                                          FundState funds, FundPayer payer) {
        Settlement s = requestCertCore(state, now, actor, commandId, trialId, customerId,
                productId, generation, round, evidence, new Leg(null, funds, payer));
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement requestCertCore(State state, long now, String actor,
                                              String commandId, String trialId, String customerId,
                                              String productId, int generation, int round,
                                              CertEvidence evidence, Leg leg) {
        String content = "CERT_REQ|" + trialId + "|" + customerId + "|" + productId + "|"
                + generation + "|" + round + "|" + evidence.evidenceId();
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        ProductSpec product = state.spec().product(productId, generation);
        if (product == null) {
            return reject(state, UNKNOWN_PRODUCT, leg);
        }
        if (state.spec().customer(customerId) == null) {
            return reject(state, UNKNOWN_CUSTOMER, leg);
        }
        if (!product.certRequired()) {
            return reject(state, CERT_NOT_REQUIRED, leg);
        }
        if (!evidence.customerId().equals(customerId)
                || !evidence.productId().equals(productId)
                || evidence.generation() != generation || !evidence.readableAt(now)) {
            return reject(state, EVIDENCE_INVALID, leg);
        }
        String key = customerId + "|" + productId + "|" + generation + "|" + round;
        if (state.trials().containsKey(key)) {
            return reject(state, TRIAL_ALREADY_REQUESTED, leg);
        }
        CertPlan plan = plan(state.spec(), key);
        if (plan == null) {
            return reject(state, NO_CERT_PLAN, leg);
        }
        if (state.usedIds().contains(trialId)) {
            return reject(state, ID_REUSED, leg);
        }
        BigDecimal fee = state.spec().certFee();
        Paid paid = pay(leg, commandId, state.accounts().companyAccountId(),
                state.accounts().certifierAccountId(), fee, state);
        if (!paid.accepted()) {
            return new Settlement(state, false, paid.reason(), paid.accounts(), paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.trials.put(key, new CertTrial(trialId, customerId, productId, generation, round,
                now, now + state.spec().certWorkMillis(), fee, evidence.evidenceId(),
                TrialStatus.IN_PROGRESS));
        draft.certExpense = money(draft.certExpense.add(fee));
        draft.usedIds.add(trialId);
        return accept(draft, REASON_CERT_REQUESTED, paid, commandId, content);
    }

    /** 完成认证：工时到点后才按冻结计划出结果；失败不生成认证。 */
    public static Outcome finishCert(State state, long now, String actor, String commandId,
                                     String trialId, Map<String, CashAccount> cash) {
        Settlement s = finishCertCore(state, now, actor, commandId, trialId,
                new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载（不动钱，仅为接口同形）。 */
    public static FundOutcome finishCert(State state, long now, String actor, String commandId,
                                         String trialId, FundState funds, FundPayer payer) {
        Settlement s = finishCertCore(state, now, actor, commandId, trialId,
                new Leg(null, funds, payer));
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement finishCertCore(State state, long now, String actor,
                                             String commandId, String trialId, Leg leg) {
        String content = "CERT_FINISH|" + trialId;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        CertTrial trial = null;
        String key = null;
        for (Map.Entry<String, CertTrial> entry : state.trials().entrySet()) {
            if (entry.getValue().trialId().equals(trialId)) {
                trial = entry.getValue();
                key = entry.getKey();
                break;
            }
        }
        if (trial == null) {
            return reject(state, UNKNOWN_TRIAL, leg);
        }
        if (trial.status() != TrialStatus.IN_PROGRESS) {
            return reject(state, TRIAL_ALREADY_REQUESTED, leg);
        }
        if (now < trial.finishAt()) {
            return reject(state, CERT_NOT_DUE, leg);
        }
        CertPlan plan = plan(state.spec(), trial.key());
        if (plan == null) {
            return reject(state, NO_CERT_PLAN, leg);
        }
        if (now < plan.readyAt()) {
            return reject(state, CERT_NOT_DUE, leg);
        }
        Draft draft = new Draft(state, now);
        if (!plan.pass()) {
            draft.trials.put(key, new CertTrial(trial.trialId(), trial.customerId(),
                    trial.productId(), trial.generation(), trial.round(), trial.requestedAt(),
                    trial.finishAt(), trial.fee(), trial.evidenceId(), TrialStatus.FAILED));
            return accept(draft, CERT_FAILED, leg, commandId, content);
        }
        draft.trials.put(key, new CertTrial(trial.trialId(), trial.customerId(),
                trial.productId(), trial.generation(), trial.round(), trial.requestedAt(),
                trial.finishAt(), trial.fee(), trial.evidenceId(), TrialStatus.PASSED));
        String certId = "cert:" + trial.trialId();
        draft.certs.put(certId, new Cert(certId, trial.customerId(), trial.productId(),
                trial.generation(), trialId, now, now + state.spec().certValidityMillis()));
        return accept(draft, REASON_CERTIFIED, leg, commandId, content);
    }

    // ---------------------------------------------------------------- 合同

    /** 签约：认证有效、需求窗口/预算/价格上限都满足；不动现金。 */
    public static Outcome signContract(State state, long now, String actor, String commandId,
                                       String contractId, String customerId, String productId,
                                       int generation, long quantity, BigDecimal unitPrice,
                                       long promisedAt, long expiresAt,
                                       Map<String, CashAccount> cash) {
        Settlement s = signCore(state, now, actor, commandId, contractId, customerId, productId,
                generation, quantity, unitPrice, promisedAt, expiresAt,
                new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载（不动钱）。 */
    public static FundOutcome signContract(State state, long now, String actor, String commandId,
                                           String contractId, String customerId, String productId,
                                           int generation, long quantity, BigDecimal unitPrice,
                                           long promisedAt, long expiresAt, FundState funds,
                                           FundPayer payer) {
        Settlement s = signCore(state, now, actor, commandId, contractId, customerId, productId,
                generation, quantity, unitPrice, promisedAt, expiresAt,
                new Leg(null, funds, payer));
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement signCore(State state, long now, String actor, String commandId,
                                       String contractId, String customerId, String productId,
                                       int generation, long quantity, BigDecimal unitPrice,
                                       long promisedAt, long expiresAt, Leg leg) {
        String content = "SIGN|" + contractId + "|" + customerId + "|" + productId + "|"
                + generation + "|" + quantity + "|" + unitPrice + "|" + promisedAt + "|"
                + expiresAt;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        ProductSpec product = state.spec().product(productId, generation);
        if (product == null) {
            return reject(state, UNKNOWN_PRODUCT, leg);
        }
        CustomerSpec customer = state.spec().customer(customerId);
        if (customer == null) {
            return reject(state, UNKNOWN_CUSTOMER, leg);
        }
        if (quantity <= 0) {
            return reject(state, INVALID_QUANTITY, leg);
        }
        BigDecimal price;
        try {
            price = money(unitPrice);
        } catch (IllegalArgumentException invalid) {
            return reject(state, PRICE_ABOVE_CAP, leg);
        }
        if (price.signum() <= 0 || price.compareTo(state.spec().priceCap()) > 0) {
            return reject(state, PRICE_ABOVE_CAP, leg);
        }
        long period = now / PERIOD_MILLIS;
        if (!customer.allows(period)) {
            return reject(state, DELIVERY_WINDOW, leg);
        }
        if (promisedAt <= now || expiresAt <= promisedAt) {
            return reject(state, DELIVERY_WINDOW, leg);
        }
        if (state.usedIds().contains(contractId)) {
            return reject(state, ID_REUSED, leg);
        }
        if (product.certRequired() && !hasCert(state, customerId, productId, generation, now)) {
            return reject(state, NOT_CERTIFIED, leg);
        }
        BigInteger occupied = BigInteger.ZERO;
        BigDecimal budget = ZERO;
        for (Contract contract : state.contracts().values()) {
            if (contract.customerId().equals(customerId) && contract.period() == period) {
                long effective = Math.addExact(contract.delivered(), contract.reserved());
                occupied = occupied.add(BigInteger.valueOf(effective));
                budget = budget.add(money(contract.unitPrice()
                        .multiply(BigDecimal.valueOf(effective))));
            }
        }
        if (occupied.add(BigInteger.valueOf(quantity))
                .compareTo(BigInteger.valueOf(customer.demandPerPeriod())) > 0) {
            return reject(state, DEMAND_EXCEEDED, leg);
        }
        BigDecimal value = money(price.multiply(BigDecimal.valueOf(quantity)));
        if (budget.add(value).compareTo(customer.budgetPerPeriod()) > 0) {
            return reject(state, BUDGET_EXCEEDED, leg);
        }
        Draft draft = new Draft(state, now);
        draft.contracts.put(contractId, new Contract(contractId, customerId, productId,
                generation, quantity, 0, quantity, price, now, promisedAt, expiresAt, period,
                ContractStatus.ACTIVE, false));
        draft.usedIds.add(contractId);
        return accept(draft, REASON_SIGNED, leg, commandId, content);
    }

    /** 交付：到期且未逾期；确认收入/成本/应收；已签合同不受认证过期影响。 */
    public static Outcome deliver(State state, long now, String actor, String commandId,
                                  String contractId, long quantity, String receivableId,
                                  Map<String, CashAccount> cash) {
        Settlement s = deliverCore(state, now, actor, commandId, contractId, quantity,
                receivableId, new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载（不动钱）。 */
    public static FundOutcome deliver(State state, long now, String actor, String commandId,
                                      String contractId, long quantity, String receivableId,
                                      FundState funds, FundPayer payer) {
        Settlement s = deliverCore(state, now, actor, commandId, contractId, quantity,
                receivableId, new Leg(null, funds, payer));
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement deliverCore(State state, long now, String actor, String commandId,
                                          String contractId, long quantity, String receivableId,
                                          Leg leg) {
        String content = "DELIVER|" + contractId + "|" + quantity + "|" + receivableId;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return reject(state, UNKNOWN_CONTRACT, leg);
        }
        if (contract.status() == ContractStatus.OVERDUE
                || contract.status() == ContractStatus.CANCELLED) {
            return reject(state, CONTRACT_OVERDUE, leg);
        }
        if (contract.status() != ContractStatus.ACTIVE) {
            return reject(state, CONTRACT_STATE_INVALID, leg);
        }
        if (now < contract.promisedAt() || now > contract.expiresAt()) {
            return reject(state, DELIVERY_WINDOW, leg);
        }
        if (quantity <= 0 || quantity > contract.remaining()) {
            return reject(state, DELIVERY_EXCEEDS_CONTRACT, leg);
        }
        if (state.usedIds().contains(receivableId)) {
            return reject(state, ID_REUSED, leg);
        }
        String key = contract.productId() + ":" + contract.generation();
        Inventory inv = state.products().getOrDefault(key, new Inventory(0, ZERO));
        if (inv.quantity() < quantity) {
            return reject(state, INSUFFICIENT_FINISHED_GOODS, leg);
        }
        BigDecimal cost = ManufacturingModel.allocate(inv, quantity);
        BigDecimal amount = money(contract.unitPrice().multiply(BigDecimal.valueOf(quantity)));
        Draft draft = new Draft(state, now);
        draft.products.put(key, new Inventory(inv.quantity() - quantity,
                inv.amount().subtract(cost)));
        long delivered = contract.delivered() + quantity;
        ContractStatus status = delivered == contract.quantity()
                ? ContractStatus.FULFILLED : ContractStatus.ACTIVE;
        draft.contracts.put(contractId, new Contract(contract.contractId(), contract.customerId(),
                contract.productId(), contract.generation(), contract.quantity(), delivered,
                contract.released() ? 0 : contract.quantity() - delivered,
                contract.unitPrice(), contract.signedAt(), contract.promisedAt(),
                contract.expiresAt(), contract.period(), status, contract.released()));
        draft.receivables.put(receivableId, new Receivable(receivableId, contractId,
                contract.customerId(), quantity, amount, cost, now, now + PAYMENT_TERM_MILLIS,
                amount, ReceivableStatus.OPEN));
        draft.usedIds.add(receivableId);
        return accept(draft, REASON_DELIVERED, leg, commandId, content);
    }

    /** 逾期标记：释放未交需求承诺，只能释放一次。 */
    public static Outcome markOverdue(State state, long now, String actor, String commandId,
                                      String contractId, Map<String, CashAccount> cash) {
        Settlement s = markOverdueCore(state, now, actor, commandId, contractId,
                new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    private static Settlement markOverdueCore(State state, long now, String actor,
                                              String commandId, String contractId, Leg leg) {
        String content = "OVERDUE|" + contractId;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return reject(state, UNKNOWN_CONTRACT, leg);
        }
        if (contract.status() != ContractStatus.ACTIVE) {
            return reject(state, CONTRACT_STATE_INVALID, leg);
        }
        if (now <= contract.expiresAt()) {
            return reject(state, DELIVERY_WINDOW, leg);
        }
        Draft draft = new Draft(state, now);
        draft.contracts.put(contractId, new Contract(contract.contractId(), contract.customerId(),
                contract.productId(), contract.generation(), contract.quantity(),
                contract.delivered(), 0, contract.unitPrice(), contract.signedAt(),
                contract.promisedAt(), contract.expiresAt(), contract.period(),
                ContractStatus.OVERDUE, true));
        return accept(draft, REASON_OVERDUE, leg, commandId, content);
    }

    /** 取消：释放未交需求承诺，只能释放一次。 */
    public static Outcome cancelContract(State state, long now, String actor, String commandId,
                                         String contractId, Map<String, CashAccount> cash) {
        Settlement s = cancelCore(state, now, actor, commandId, contractId,
                new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    private static Settlement cancelCore(State state, long now, String actor, String commandId,
                                         String contractId, Leg leg) {
        String content = "CANCEL|" + contractId;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return reject(state, UNKNOWN_CONTRACT, leg);
        }
        if (contract.status() != ContractStatus.ACTIVE
                && contract.status() != ContractStatus.OVERDUE) {
            return reject(state, CONTRACT_STATE_INVALID, leg);
        }
        Draft draft = new Draft(state, now);
        draft.contracts.put(contractId, new Contract(contract.contractId(), contract.customerId(),
                contract.productId(), contract.generation(), contract.quantity(),
                contract.delivered(), 0, contract.unitPrice(), contract.signedAt(),
                contract.promisedAt(), contract.expiresAt(), contract.period(),
                ContractStatus.CANCELLED, true));
        return accept(draft, REASON_CANCELLED, leg, commandId, content);
    }

    /** 收款：只冲应收，不再确认收入。 */
    public static Outcome collect(State state, long now, String actor, String commandId,
                                  String receivableId, Map<String, CashAccount> cash) {
        Settlement s = collectCore(state, now, actor, commandId, receivableId,
                new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载。 */
    public static FundOutcome collect(State state, long now, String actor, String commandId,
                                      String receivableId, FundState funds, FundPayer payer) {
        Settlement s = collectCore(state, now, actor, commandId, receivableId,
                new Leg(null, funds, payer));
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement collectCore(State state, long now, String actor, String commandId,
                                          String receivableId, Leg leg) {
        String content = "COLLECT|" + receivableId;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        Receivable receivable = state.receivables().get(receivableId);
        if (receivable == null) {
            return reject(state, UNKNOWN_RECEIVABLE, leg);
        }
        if (receivable.status() != ReceivableStatus.OPEN) {
            return reject(state, RECEIVABLE_CLOSED, leg);
        }
        if (now < receivable.dueAt()) {
            return reject(state, RECEIVABLE_NOT_DUE, leg);
        }
        String customerAccount = state.accounts().customerAccounts().get(receivable.customerId());
        Paid paid = pay(leg, commandId, customerAccount,
                state.accounts().companyAccountId(), receivable.outstanding(), state);
        if (!paid.accepted()) {
            return new Settlement(state, false, paid.reason(), paid.accounts(), paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.receivables.put(receivableId, new Receivable(receivable.receivableId(),
                receivable.contractId(), receivable.customerId(), receivable.quantity(),
                receivable.amount(), receivable.cost(), receivable.recognizedAt(),
                receivable.dueAt(), ZERO, ReceivableStatus.COLLECTED));
        return accept(draft, REASON_COLLECTED, paid, commandId, content);
    }

    /** 坏账核销：到期 + 宽限期后按最小实验政策确认损失。 */
    public static Outcome writeOffReceivable(State state, long now, String actor,
                                             String commandId, String receivableId,
                                             Map<String, CashAccount> cash) {
        Settlement s = writeOffCore(state, now, actor, commandId, receivableId,
                new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    private static Settlement writeOffCore(State state, long now, String actor, String commandId,
                                           String receivableId, Leg leg) {
        String content = "WRITE_OFF|" + receivableId;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        Receivable receivable = state.receivables().get(receivableId);
        if (receivable == null) {
            return reject(state, UNKNOWN_RECEIVABLE, leg);
        }
        if (receivable.status() != ReceivableStatus.OPEN) {
            return reject(state, RECEIVABLE_CLOSED, leg);
        }
        if (now < receivable.dueAt() + state.spec().badDebtGraceMillis()) {
            return reject(state, BAD_DEBT_NOT_DUE, leg);
        }
        Draft draft = new Draft(state, now);
        draft.receivables.put(receivableId, new Receivable(receivable.receivableId(),
                receivable.contractId(), receivable.customerId(), receivable.quantity(),
                receivable.amount(), receivable.cost(), receivable.recognizedAt(),
                receivable.dueAt(), ZERO, ReceivableStatus.WRITTEN_OFF));
        draft.badDebtLoss = money(draft.badDebtLoss.add(receivable.outstanding()));
        return accept(draft, REASON_WRITTEN_OFF, leg, commandId, content);
    }

    // ---------------------------------------------------------------- 扩产

    /** 扩产开工：capex 入 CIP，不立即形成设备或产能。 */
    public static Outcome startExpansion(State state, long now, String actor, String commandId,
                                         String projectId, BigDecimal capex, long buildMillis,
                                         int rampPeriods, long addCapacity,
                                         Map<String, CashAccount> cash) {
        Settlement s = startExpansionCore(state, now, actor, commandId, projectId, capex,
                buildMillis, rampPeriods, addCapacity, new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载。 */
    public static FundOutcome startExpansion(State state, long now, String actor,
                                             String commandId, String projectId, BigDecimal capex,
                                             long buildMillis, int rampPeriods, long addCapacity,
                                             FundState funds, FundPayer payer) {
        Settlement s = startExpansionCore(state, now, actor, commandId, projectId, capex,
                buildMillis, rampPeriods, addCapacity, new Leg(null, funds, payer));
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement startExpansionCore(State state, long now, String actor,
                                                 String commandId, String projectId,
                                                 BigDecimal capex, long buildMillis,
                                                 int rampPeriods, long addCapacity, Leg leg) {
        String content = "EXPAND|" + projectId + "|" + capex + "|" + buildMillis + "|"
                + rampPeriods + "|" + addCapacity;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        BigDecimal amount = money(capex);
        if (amount.signum() <= 0 || buildMillis <= 0 || rampPeriods <= 0 || addCapacity <= 0) {
            return reject(state, INVALID_QUANTITY, leg);
        }
        if (state.expansions().containsKey(projectId)) {
            return reject(state, ID_REUSED, leg);
        }
        BigInteger totalCapacity = BigInteger.valueOf(state.spec().baseCapacityPerPeriod());
        for (Expansion existing : state.expansions().values()) {
            if (existing.status() != ExpansionStatus.FAILED) {
                totalCapacity = totalCapacity.add(BigInteger.valueOf(existing.addCapacity()));
            }
        }
        totalCapacity = totalCapacity.add(BigInteger.valueOf(addCapacity));
        if (totalCapacity.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return reject(state, CAPACITY_OVERFLOW, leg);
        }
        Paid paid = pay(leg, commandId, state.accounts().companyAccountId(),
                state.accounts().contractorAccountId(), amount, state);
        if (!paid.accepted()) {
            return new Settlement(state, false, paid.reason(), paid.accounts(), paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.expansions.put(projectId, new Expansion(projectId, amount, now, now + buildMillis,
                rampPeriods, addCapacity, ExpansionStatus.BUILDING, -1L, ZERO));
        draft.usedIds.add(projectId);
        return accept(draft, REASON_BUILDING, paid, commandId, content);
    }

    /** 扩产完工：成功转设备并按 ramp 生效；失败留残值并确认损失。 */
    public static Outcome completeExpansion(State state, long now, String actor,
                                            String commandId, String projectId, boolean failed,
                                            Map<String, CashAccount> cash) {
        Settlement s = completeExpansionCore(state, now, actor, commandId, projectId, failed,
                new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    private static Settlement completeExpansionCore(State state, long now, String actor,
                                                    String commandId, String projectId,
                                                    boolean failed, Leg leg) {
        String content = "EXPAND_DONE|" + projectId + "|" + failed;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        Expansion expansion = state.expansions().get(projectId);
        if (expansion == null) {
            return reject(state, UNKNOWN_EXPANSION, leg);
        }
        if (expansion.status() != ExpansionStatus.BUILDING) {
            return reject(state, EXPANSION_STATE_INVALID, leg);
        }
        if (now < expansion.completesAt()) {
            return reject(state, EXPANSION_NOT_DUE, leg);
        }
        Draft draft = new Draft(state, now);
        if (failed) {
            BigDecimal residual = money(expansion.capex()
                    .multiply(state.spec().residualRate()));
            draft.expansions.put(projectId, new Expansion(expansion.projectId(),
                    expansion.capex(), expansion.startedAt(), expansion.completesAt(),
                    expansion.rampPeriods(), expansion.addCapacity(), ExpansionStatus.FAILED,
                    -1L, residual));
            draft.residualAssets.put(projectId, residual);
            draft.disposalResult = money(draft.disposalResult.subtract(
                    expansion.capex().subtract(residual)));
            return accept(draft, REASON_BUILD_FAILED, leg, commandId, content);
        }
        long online = expansion.completesAt() / PERIOD_MILLIS;
        draft.expansions.put(projectId, new Expansion(expansion.projectId(), expansion.capex(),
                expansion.startedAt(), expansion.completesAt(), expansion.rampPeriods(),
                expansion.addCapacity(), ExpansionStatus.ONLINE, online, ZERO));
        return accept(draft, REASON_ONLINE, leg, commandId, content);
    }

    /** 残余资产处置：真实外部买方付款；买方不足则保留残值。 */
    public static Outcome sellResidual(State state, long now, String actor, String commandId,
                                       String projectId, BigDecimal price,
                                       Map<String, CashAccount> cash) {
        Settlement s = sellResidualCore(state, now, actor, commandId, projectId, price,
                new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载。 */
    public static FundOutcome sellResidual(State state, long now, String actor, String commandId,
                                           String projectId, BigDecimal price, FundState funds,
                                           FundPayer payer) {
        Settlement s = sellResidualCore(state, now, actor, commandId, projectId, price,
                new Leg(null, funds, payer));
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement sellResidualCore(State state, long now, String actor,
                                               String commandId, String projectId,
                                               BigDecimal price, Leg leg) {
        String content = "SELL_RESIDUAL|" + projectId + "|" + price;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        BigDecimal residual = state.residualAssets().get(projectId);
        if (residual == null || residual.signum() <= 0) {
            return reject(state, NO_RESIDUAL, leg);
        }
        BigDecimal amount = money(price);
        if (amount.signum() <= 0) {
            return reject(state, INVALID_QUANTITY, leg);
        }
        String buyer = state.accounts().disposalBuyerAccountId();
        Paid paid = pay(leg, commandId, buyer, state.accounts().companyAccountId(), amount, state);
        if (!paid.accepted()) {
            return new Settlement(state, false, paid.reason(), paid.accounts(), paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.residualAssets.remove(projectId);
        draft.disposalResult = money(draft.disposalResult.add(amount.subtract(residual)));
        return accept(draft, REASON_SOLD, paid, commandId, content);
    }

    // ---------------------------------------------------------------- 关期

    /** 期末关账：期间费用（不足成应付）与设备折旧；必须按期连续。 */
    public static Outcome closePeriod(State state, long now, String actor, String commandId,
                                      long period, Map<String, CashAccount> cash) {
        Settlement s = closeCore(state, now, actor, commandId, period,
                new Leg(cash, null, null));
        return new Outcome(s.accepted(), s.reason(), s.state(), s.accounts());
    }

    /** 显式资金重载。 */
    public static FundOutcome closePeriod(State state, long now, String actor, String commandId,
                                          long period, FundState funds, FundPayer payer) {
        Settlement s = closeCore(state, now, actor, commandId, period,
                new Leg(null, funds, payer));
        return new FundOutcome(s.accepted(), s.reason(), s.state(), s.funds());
    }

    private static Settlement closeCore(State state, long now, String actor, String commandId,
                                        long period, Leg leg) {
        String content = "CLOSE|" + period;
        Settlement replay = replay(state, commandId, content, leg);
        if (replay != null) {
            return replay;
        }
        String deny = guardActorTime(state, now, actor);
        if (deny != null) {
            return reject(state, deny, leg);
        }
        if (period != state.closedPeriods()) {
            return reject(state, period < state.closedPeriods()
                    ? PERIOD_ALREADY_CLOSED : PERIOD_NOT_CONTINUOUS, leg);
        }
        if (now < (period + 1) * PERIOD_MILLIS) {
            return reject(state, PERIOD_NOT_ENDED, leg);
        }
        BigDecimal expense = state.spec().periodExpense();
        BigDecimal canPay;
        if (leg.payer() == null) {
            CashAccount account = leg.accounts().get(state.accounts().companyAccountId());
            canPay = account.cash();
        } else {
            canPay = leg.payer().available(leg.funds());
        }
        BigDecimal paidAmount = money(expense.min(canPay));
        Map<String, CashAccount> accounts = leg.accounts();
        FundState funds = leg.funds();
        if (paidAmount.signum() > 0) {
            Paid paid = pay(leg, commandId, state.accounts().companyAccountId(),
                    state.accounts().opsAccountId(), paidAmount, state);
            if (!paid.accepted()) {
                return new Settlement(state, false, paid.reason(), paid.accounts(), paid.funds());
            }
            accounts = paid.accounts();
            funds = paid.funds();
        }
        Draft draft = new Draft(state, now);
        if (expense.signum() > 0) {
            draft.bills.put(period, new ExpenseBill(period, expense, paidAmount));
        }
        BigDecimal net = equipmentOriginal(state).subtract(state.accumulatedDepreciation());
        BigDecimal depreciation = money(state.spec().depreciationPerPeriod().min(
                net.signum() > 0 ? net : ZERO));
        draft.accumulatedDepreciation = money(state.accumulatedDepreciation()
                .add(depreciation));
        draft.closedPeriods = state.closedPeriods() + 1;
        return accept(draft, REASON_PERIOD_CLOSED,
                new Paid(true, OK, accounts, funds, paidAmount.signum() > 0),
                commandId, content);
    }

    // ---------------------------------------------------------------- 报表

    /**
     * 派生报表，不改状态。
     *
     * @param state 状态
     * @param cash  当前现金账户表
     * @return 报表
     */
    public static Report report(State state, Map<String, CashAccount> cash) {
        Objects.requireNonNull(state, "状态");
        Objects.requireNonNull(cash, "现金账户");
        BigDecimal companyCash = cash.get(state.accounts().companyAccountId()).cash();
        BigDecimal materials = ZERO;
        for (Inventory inv : state.materials().values()) {
            materials = materials.add(inv.amount());
        }
        BigDecimal products = ZERO;
        for (Inventory inv : state.products().values()) {
            products = products.add(inv.amount());
        }
        BigDecimal wip = ZERO;
        for (Batch batch : state.batches().values()) {
            if (batch.status() == BatchStatus.IN_PROGRESS) {
                wip = wip.add(batch.totalCost());
            }
        }
        BigDecimal receivables = ZERO;
        BigDecimal revenue = ZERO;
        BigDecimal cogs = ZERO;
        for (Receivable r : state.receivables().values()) {
            receivables = receivables.add(r.outstanding());
            revenue = revenue.add(r.amount());
            cogs = cogs.add(r.cost());
        }
        BigDecimal expense = ZERO;
        BigDecimal payables = ZERO;
        for (ExpenseBill bill : state.bills().values()) {
            expense = expense.add(bill.amount());
            payables = payables.add(bill.outstanding());
        }
        BigDecimal equipmentNet = equipmentNet(state);
        BigDecimal cip = ZERO;
        BigDecimal residual = ZERO;
        for (Expansion e : state.expansions().values()) {
            if (e.status() == ExpansionStatus.BUILDING) {
                cip = cip.add(e.capex());
            }
        }
        for (BigDecimal value : state.residualAssets().values()) {
            residual = residual.add(value);
        }
        BigDecimal depreciation = depreciation(state);
        BigDecimal assets = money(companyCash.add(materials).add(products).add(wip)
                .add(receivables).add(equipmentNet).add(cip).add(residual));
        BigDecimal liabilities = money(payables);
        BigDecimal certification = money(state.certExpense());
        BigDecimal profit = money(revenue.subtract(cogs).subtract(expense)
                .subtract(certification).subtract(depreciation)
                .add(state.disposalResult()).subtract(state.badDebtLoss()));
        BigDecimal equity = money(state.initialEquity().add(profit));
        BigDecimal world = worldCash(cash);
        boolean balanced = assets.compareTo(liabilities.add(equity)) == 0
                && world.compareTo(state.initialWorldCash()) == 0;
        return new Report(money(companyCash), money(materials), money(products), money(wip),
                money(receivables), equipmentNet, money(cip), money(residual), liabilities,
                assets, liabilities, equity, money(revenue), money(cogs), money(expense),
                certification, depreciation, money(state.disposalResult()),
                money(state.badDebtLoss()), balanced, world);
    }

    /**
     * 校验报表平衡；不平衡抛异常。
     *
     * @param state 状态
     * @param cash  账户表
     * @return 已校验报表
     */
    public static Report validate(State state, Map<String, CashAccount> cash) {
        Report report = report(state, cash);
        if (!report.balanced()) {
            throw new IllegalStateException("模板账不平衡：资产=" + report.assets()
                    + "，负债=" + report.liabilities() + "，权益=" + report.equity()
                    + "，世界现金=" + report.worldCash());
        }
        return report;
    }

    // ---------------------------------------------------------------- 内部

    /** 结果码常量（内部复用）。 */
    private static final String REASON_ORDERED = "ORDERED";
    private static final String REASON_RECEIVED = "GOODS_RECEIVED";
    private static final String REASON_STARTED = "PRODUCTION_STARTED";
    private static final String REASON_COMPLETED = "PRODUCTION_COMPLETED";
    private static final String REASON_CERT_REQUESTED = "CERT_REQUESTED";
    private static final String REASON_CERTIFIED = "CERTIFIED";
    private static final String REASON_SIGNED = "SIGNED";
    private static final String REASON_DELIVERED = "DELIVERED";
    private static final String REASON_COLLECTED = "PAYMENT_RECEIVED";
    private static final String REASON_OVERDUE = "OVERDUE_MARKED";
    private static final String REASON_CANCELLED = "CANCELLED";
    private static final String REASON_WRITTEN_OFF = "WRITTEN_OFF";
    private static final String REASON_BUILDING = "BUILDING";
    private static final String REASON_BUILD_FAILED = "BUILD_FAILED";
    private static final String REASON_ONLINE = "ONLINE";
    private static final String REASON_SOLD = "RESIDUAL_SOLD";
    private static final String REASON_PERIOD_CLOSED = "PERIOD_CLOSED";
    private static final String PERIOD_NOT_CONTINUOUS = "PERIOD_NOT_CONTINUOUS";

    private static String guard(State state, long now, String actor) {
        String base = guardActorTime(state, now, actor);
        if (base != null) {
            return base;
        }
        long period = now / PERIOD_MILLIS;
        if (period > state.closedPeriods()) {
            return PERIOD_NOT_CLOSED;
        }
        return null;
    }

    private static String guardActorTime(State state, long now, String actor) {
        if (!state.accounts().companyOwnerId().equals(actor)) {
            return NOT_AUTHORIZED;
        }
        if (now < state.watermark()) {
            return TIME_REWIND;
        }
        return null;
    }

    private static MaterialQuote latestQuote(TemplateSpec spec, String materialId, long now) {
        MaterialQuote best = null;
        for (MaterialQuote quote : spec.quotes()) {
            if (!quote.materialId().equals(materialId) || !quote.visibleAt(now)) {
                continue;
            }
            if (best == null || quote.asOf() > best.asOf()
                    || (quote.asOf() == best.asOf() && quote.publishedAt() > best.publishedAt())) {
                best = quote;
            }
        }
        return best;
    }

    private static CertPlan plan(TemplateSpec spec, String key) {
        for (CertPlan plan : spec.plans()) {
            if (plan.key().equals(key)) {
                return plan;
            }
        }
        return null;
    }

    private static boolean hasCert(State state, String customerId, String productId,
                                   int generation, long now) {
        for (Cert cert : state.certs().values()) {
            if (cert.customerId().equals(customerId) && cert.productId().equals(productId)
                    && cert.generation() == generation && cert.validAt(now)) {
                return true;
            }
        }
        return false;
    }

    /** 包内共享真实爬坡产能，网页投影与生产约束使用同一计算。 */
    static BigInteger capacityAt(State state, long period) {
        BigInteger capacity = BigInteger.valueOf(state.spec().baseCapacityPerPeriod());
        for (Expansion e : state.expansions().values()) {
            if (e.status() != ExpansionStatus.ONLINE) {
                continue;
            }
            long k = period - e.onlinePeriod();
            if (k <= 0) {
                continue;
            }
            BigInteger add = BigInteger.valueOf(e.addCapacity());
            if (k >= e.rampPeriods()) {
                capacity = capacity.add(add);
            } else {
                capacity = capacity.add(add.multiply(BigInteger.valueOf(k))
                        .divide(BigInteger.valueOf(e.rampPeriods())));
            }
        }
        return capacity;
    }

    private static BigDecimal equipmentOriginal(State state) {
        BigDecimal original = ZERO;
        for (Expansion e : state.expansions().values()) {
            if (e.status() == ExpansionStatus.ONLINE) {
                original = original.add(e.capex());
            }
        }
        return money(original);
    }

    private static BigDecimal equipmentNet(State state) {
        BigDecimal net = equipmentOriginal(state).subtract(state.accumulatedDepreciation());
        return money(net.signum() < 0 ? ZERO : net);
    }

    private static BigDecimal depreciation(State state) {
        return money(state.accumulatedDepreciation());
    }

    private static BigDecimal worldCash(Map<String, CashAccount> cash) {
        BigDecimal total = ZERO;
        for (CashAccount account : cash.values()) {
            total = total.add(account.cash());
        }
        return money(total);
    }

    private static Settlement replay(State state, String commandId, String content, Leg leg) {
        String stored = state.commandContents().get(commandId);
        if (stored == null) {
            return null;
        }
        if (!stored.equals(content)) {
            throw new IllegalArgumentException(COMMAND_CONTENT_CONFLICT + ":" + commandId);
        }
        Reply reply = state.commandReplies().get(commandId);
        if (leg.payer() != null && reply.accepted() && reply.paid()
                && !leg.payer().hasAcceptedRecord(commandId, leg.funds())) {
            return new Settlement(state, false, FUND_RECORD_DIVERGED, null, leg.funds());
        }
        return new Settlement(state, reply.accepted(), REPLAYED + ":" + reply.reason(),
                leg.payer() == null ? leg.accounts() : null,
                leg.payer() == null ? null : leg.funds());
    }

    private static Settlement reject(State state, String reason, Leg leg) {
        return new Settlement(state, false, reason,
                leg.payer() == null ? leg.accounts() : null,
                leg.payer() == null ? null : leg.funds());
    }

    private static Settlement accept(Draft draft, String reason, Leg leg, String commandId,
                                     String content) {
        return accept(draft, reason, new Paid(true, OK, leg.accounts(), leg.funds(), false),
                commandId, content);
    }

    private static Settlement accept(Draft draft, String reason, Paid paid, String commandId,
                                     String content) {
        draft.commandContents.put(commandId, content);
        draft.commandReplies.put(commandId, new Reply(true, reason, paid.moved()));
        return new Settlement(draft.build(), true, reason,
                paid.accounts(), paid.funds());
    }

    /**
     * 执行一次付款腿：现金走 CashLedger，显式资金走 FundPayer；主体不符拒绝。
     *
     * <p>期望主体来自开局冻结的业务主体映射（{@code openingOwners}），不从当前账户表
     * 自证；当前账户 owner 漂移一律 {@code TRANSFER_NOT_ALLOWED}，不进入现金账本。</p>
     */
    private static Paid pay(Leg leg, String commandId, String srcAccount, String dstAccount,
                            BigDecimal amount, State state) {
        BigDecimal value = money(amount);
        if (value.signum() <= 0) {
            return new Paid(true, OK, leg.accounts(), leg.funds(), false);
        }
        if (srcAccount == null || dstAccount == null) {
            return new Paid(false, UNKNOWN_CUSTOMER, leg.accounts(), leg.funds(), false);
        }
        String expectedSrc = state.openingOwnerOf(srcAccount);
        String expectedDst = state.openingOwnerOf(dstAccount);
        if (expectedSrc == null || expectedDst == null) {
            return new Paid(false, UNKNOWN_CUSTOMER, leg.accounts(), leg.funds(), false);
        }
        if (leg.payer() == null) {
            CashAccount from = leg.accounts().get(srcAccount);
            CashAccount to = leg.accounts().get(dstAccount);
            if (from == null || to == null || !expectedSrc.equals(from.ownerId())
                    || !expectedDst.equals(to.ownerId())) {
                return new Paid(false, "TRANSFER_NOT_ALLOWED", leg.accounts(), null, false);
            }
            CashLedger.TransferOutcome transfer = CashLedger.computeTransfer(leg.accounts(),
                    expectedSrc, srcAccount, dstAccount, value);
            return new Paid(transfer.accepted(), transfer.reason(), transfer.accounts(), null,
                    transfer.accepted());
        }
        FundState funds = leg.funds();
        if (!expectedSrc.equals(funds.ownerIdOf(leg.payer().source()))
                || !expectedDst.equals(funds.ownerIdOf(leg.payer().target()))) {
            return new Paid(false, FUND_OWNER_MISMATCH, null, funds, false);
        }
        if (leg.payer().hasRecord(commandId, funds)) {
            return new Paid(false, FUND_ID_ALREADY_RECORDED, null, funds, false);
        }
        BigDecimal available = leg.payer().available(funds);
        if (available.compareTo(value) < 0) {
            return new Paid(false, INSUFFICIENT_CASH, null, funds, false);
        }
        FundPayer.Result result = leg.payer().pay(commandId, value, funds);
        return new Paid(result.accepted(), result.reason(), null, result.funds(),
                result.accepted());
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("金额不能为空");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    /** 在不可变状态上做候选修改。 */
    private static final class Draft {
        private final State base;
        private Map<String, Inventory> materials;
        private Map<String, Inventory> products;
        private final Map<String, PurchaseOrder> purchases;
        private final Map<String, Batch> batches;
        private final Map<String, CertTrial> trials;
        private final Map<String, Cert> certs;
        private final Map<String, Contract> contracts;
        private final Map<String, Receivable> receivables;
        private final Map<String, Expansion> expansions;
        private final Map<String, BigDecimal> residualAssets;
        private final Map<Long, Long> capacityUsed;
        private final Map<Long, ExpenseBill> bills;
        private final Map<String, String> commandContents;
        private final Map<String, Reply> commandReplies;
        private final Set<String> usedIds;
        private BigDecimal disposalResult;
        private BigDecimal badDebtLoss;
        private BigDecimal certExpense;
        private BigDecimal accumulatedDepreciation;
        private long closedPeriods;
        private long watermark;

        private Draft(State base, long now) {
            this.base = base;
            this.materials = new LinkedHashMap<>(base.materials());
            this.products = new LinkedHashMap<>(base.products());
            this.purchases = new LinkedHashMap<>(base.purchases());
            this.batches = new LinkedHashMap<>(base.batches());
            this.trials = new LinkedHashMap<>(base.trials());
            this.certs = new LinkedHashMap<>(base.certs());
            this.contracts = new LinkedHashMap<>(base.contracts());
            this.receivables = new LinkedHashMap<>(base.receivables());
            this.expansions = new LinkedHashMap<>(base.expansions());
            this.residualAssets = new LinkedHashMap<>(base.residualAssets());
            this.capacityUsed = new LinkedHashMap<>(base.capacityUsed());
            this.bills = new LinkedHashMap<>(base.bills());
            this.commandContents = new LinkedHashMap<>(base.commandContents());
            this.commandReplies = new LinkedHashMap<>(base.commandReplies());
            this.usedIds = new LinkedHashSet<>(base.usedIds());
            this.disposalResult = base.disposalResult();
            this.badDebtLoss = base.badDebtLoss();
            this.certExpense = base.certExpense();
            this.accumulatedDepreciation = base.accumulatedDepreciation();
            this.closedPeriods = base.closedPeriods();
            this.watermark = Math.max(base.watermark(), now);
        }

        private State build() {
            return new State(base.spec(), base.accounts(), base.openingOwners(),
                    base.initialEquity(), base.initialWorldCash(), watermark, materials, products,
                    purchases, batches, trials, certs, contracts, receivables, expansions,
                    residualAssets, capacityUsed, bills, closedPeriods, commandContents,
                    commandReplies, disposalResult, badDebtLoss, certExpense,
                    accumulatedDepreciation, usedIds);
        }
    }
}
