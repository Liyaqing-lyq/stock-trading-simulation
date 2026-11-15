package edu.cufe.auction.world;

import edu.cufe.auction.world.bank.FundState;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 数字技术服务公司（两阶段研究 + 付费服务合同）的纯函数子状态：EXP-DIGITAL-1。
 *
 * <p><b>边界</b>：不持日志、不调度、不提交、不做权限投影，也不持有现金。世界权威现金只通过
 * 调用方每次传入的 {@link FundState} 进入，付款只走 {@link FundPayer#pay(String, BigDecimal, FundState)}；
 * 传入旧三态会让资金层记录与业务层对不上（{@code FUND_RECORD_DIVERGED}），因此不得复用旧快照。</p>
 *
 * <p><b>为什么不用 {@code ResearchModel}</b>：现有通用研究框架的公开 API 把阶段固定成
 * {@code RESEARCH → DEVELOPMENT → REGISTRATION} 三段（{@code ProjectSpec} 构造期要求正好三个
 * {@code StagePlan}，且订单顺序必须与枚举一致），而本合同只启用两个 EXP 验证门
 * （PILOT 费用化、VALIDATION 资本化后摊销），并明确不启用 {@code REGISTRATION}。用 0 成本假
 * REGISTRATION 凑够三段会把“没有发生的阶段”写进状态，因此本类只写<b>本域最小两阶段</b>状态：
 * 阶段成本、正耗时、冻结证据、失败即停（无重试抽奖）、批准引用，均在本类内自证；通用研究框架
 * 一行未改。</p>
 *
 * <p><b>口径</b>（见 {@code remaining-company-contract/v2/contract.md} 第 2 节与 Root 复核第 4/5 点）：</p>
 * <ul>
 *   <li>PILOT 在开工时一次性费用化；VALIDATION 先计入在研，验证通过转平台资产、按期摊销，
 *       失败转减值损失，两者都不产生收入。</li>
 *   <li>只做付费服务合同：签约收预收 → 合同负债（退款义务），签约不确认收入；交付时按实际服务量
 *       确认收入与服务成本，按比例冲减退款义务，未收部分转应收；收款只冲应收。</li>
 *   <li>终止只释放未交付承诺，不恢复已交付额度，不抹掉未赚预收；实际退款必须有足额权威现金，
 *       付不出就保留负债（逾期可见），付成功才结清。</li>
 *   <li>不设 license milestone、不设 royalty（留给药企模板），同一义务不重复确认。</li>
 *   <li>客户需求、客户预算、服务容量都在 {@link Config} 冻结；已交付量只增不减，收款、坏账、
 *       终止都不复活它。</li>
 * </ul>
 *
 * <p>金额 {@code BigDecimal}（金额 2 位、单价 4 位，HALF_UP），数量 {@code long}；数量乘加用
 * {@link BigInteger} 校验，超 {@code long} 受控拒绝。拒绝返回原状态（不半写）；付款失败时合同、
 * 阶段、收入、义务一律不动。</p>
 */
public final class DigitalServiceModel {

    /** 一个日班。 */
    public static final long DAY_MILLIS = 86_400_000L;

    /** 计价期间长度：30 个日班。 */
    public static final long PERIOD_MILLIS = 30L * DAY_MILLIS;

    // ---------------------------------------------------------------- 结果码

    /** 动作被接受。 */
    public static final String OK = "OK";
    /** 调用方不是本公司主体。 */
    public static final String NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 时间轴回退。 */
    public static final String TIME_REWIND = "TIME_REWIND";
    /** 期间未关账，不能进入下一期。 */
    public static final String PERIOD_NOT_CLOSED = "PERIOD_NOT_CLOSED";
    /** 该期间已关闭。 */
    public static final String PERIOD_CLOSED = "PERIOD_CLOSED";
    /** 期间尚未结束。 */
    public static final String PERIOD_NOT_ENDED = "PERIOD_NOT_ENDED";
    /** 关账必须连续。 */
    public static final String PERIOD_NOT_CONTINUOUS = "PERIOD_NOT_CONTINUOUS";
    /** 数量非法。 */
    public static final String INVALID_QUANTITY = "INVALID_QUANTITY";
    /** 数量累加超出 long。 */
    public static final String QUANTITY_OVERFLOW = "QUANTITY_OVERFLOW";
    /** 标识被复用。 */
    public static final String ID_REUSED = "ID_REUSED";
    /** 同一 commandId 换了内容。 */
    public static final String COMMAND_CONTENT_CONFLICT = "COMMAND_CONTENT_CONFLICT";
    /** 资金层已有该标识记录。 */
    public static final String FUND_ID_ALREADY_RECORDED = "FUND_ID_ALREADY_RECORDED";
    /** 业务已成功但资金层缺成功记录。 */
    public static final String FUND_RECORD_DIVERGED = "FUND_RECORD_DIVERGED";
    /** 资金桶真实主体与冻结主体不符。 */
    public static final String FUND_OWNER_MISMATCH = "FUND_OWNER_MISMATCH";
    /** 账户未知。 */
    public static final String UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
    /** 付款方现金不足。 */
    public static final String INSUFFICIENT_CASH = "INSUFFICIENT_CASH";
    /** 客户现金不足。 */
    public static final String CUSTOMER_CASH_SHORT = "CUSTOMER_CASH_SHORT";
    /** 未知阶段。 */
    public static final String UNKNOWN_STAGE = "UNKNOWN_STAGE";
    /** 阶段顺序不允许（跳级或重复）。 */
    public static final String STAGE_ORDER = "STAGE_ORDER";
    /** 该阶段已经跑过。 */
    public static final String STAGE_ALREADY_RUN = "STAGE_ALREADY_RUN";
    /** 研究已失败，不能重试。 */
    public static final String RESEARCH_FAILED = "RESEARCH_FAILED";
    /** 阶段尚未到期。 */
    public static final String STAGE_NOT_DUE = "STAGE_NOT_DUE";
    /** 阶段不在运行中。 */
    public static final String STAGE_STATE_INVALID = "STAGE_STATE_INVALID";
    /** 证据不合法（缺失、时点越界）。 */
    public static final String EVIDENCE_INVALID = "EVIDENCE_INVALID";
    /** 尚未通过 VALIDATION。 */
    public static final String NOT_VALIDATED = "NOT_VALIDATED";
    /** 缺批准引用。 */
    public static final String NO_APPROVAL = "NO_APPROVAL";
    /** 批准引用与冻结授权不符。 */
    public static final String APPROVAL_INVALID = "APPROVAL_INVALID";
    /** 未知合同。 */
    public static final String UNKNOWN_CONTRACT = "UNKNOWN_CONTRACT";
    /** 合同状态不允许该动作。 */
    public static final String CONTRACT_STATE_INVALID = "CONTRACT_STATE_INVALID";
    /** 超出客户当期冻结需求。 */
    public static final String DEMAND_EXCEEDED = "DEMAND_EXCEEDED";
    /** 超出客户当期冻结预算。 */
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    /** 超出当期服务容量。 */
    public static final String SERVICE_CAPACITY = "SERVICE_CAPACITY";
    /** 交付超过合同剩余量。 */
    public static final String DELIVERY_EXCEEDS_CONTRACT = "DELIVERY_EXCEEDS_CONTRACT";
    /** 未知应收。 */
    public static final String UNKNOWN_RECEIVABLE = "UNKNOWN_RECEIVABLE";
    /** 应收已结清或已核销。 */
    public static final String RECEIVABLE_CLOSED = "RECEIVABLE_CLOSED";
    /** 应收未到期。 */
    public static final String RECEIVABLE_NOT_DUE = "RECEIVABLE_NOT_DUE";
    /** 未逾期不能核销。 */
    public static final String BAD_DEBT_NOT_DUE = "BAD_DEBT_NOT_DUE";
    /** 退款金额超过未赚预收。 */
    public static final String REFUND_EXCEEDS_LIABILITY = "REFUND_EXCEEDS_LIABILITY";
    /** 预收超过合同总价。 */
    public static final String PREPAID_EXCEEDS_CONTRACT = "PREPAID_EXCEEDS_CONTRACT";
    /** 只有终止后的合同才能退款。 */
    public static final String CONTRACT_NOT_TERMINATED = "CONTRACT_NOT_TERMINATED";

    /** 批准种类：商业化。 */
    public static final String APPROVAL_COMMERCIAL = "COMMERCIAL";

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal FOUR_ZERO = new BigDecimal("0.0000");
    private static final Set<String> APPROVAL_KINDS = Set.of(APPROVAL_COMMERCIAL);

    private DigitalServiceModel() {
    }

    // ---------------------------------------------------------------- 冻结配置

    /**
     * 一条冻结的授权事实。
     *
     * <p>纯模型只核“引用在冻结配置里、种类对、时点已到”。真正的董事会资格由后继 World 在绑定
     * 阶段解析后写进配置，字符串本身不是资格证明。</p>
     *
     * @param approvalId 授权引用
     * @param kind       种类（当前只启用 {@link #APPROVAL_COMMERCIAL}）
     * @param approvedAt 批准时点
     */
    public record Approval(String approvalId, String kind, long approvedAt) {

        /** 校验引用、种类与时点。 */
        public Approval {
            requireText(approvalId, "授权引用");
            if (!APPROVAL_KINDS.contains(kind)) {
                throw new IllegalArgumentException("未知授权种类：" + kind);
            }
            if (approvedAt < 0) {
                throw new IllegalArgumentException("批准时点不能为负");
            }
        }
    }

    /**
     * 冻结配置：主体、账户、有限需求/预算/容量、两阶段参数与冻结的验证结果。
     *
     * @param companyOwnerId              公司主体
     * @param companyAccountId            公司现金账户
     * @param opsAccountId                运营账户（固定费用去向）
     * @param customerId                  服务客户
     * @param customerAccountId           客户结算账户（预收来源、退款去向）
     * @param contractorId                研究承包方
     * @param contractorAccountId         承包方账户
     * @param capitalSponsorId            外部注资方（EXP）
     * @param capitalSponsorAccountId     注资方账户
     * @param servicePricePerUnit         服务单价（4 位）
     * @param serviceUnitCost             单位服务成本（2 位）
     * @param customerDemandUnitsPerPeriod 客户当期冻结需求（件/期）
     * @param customerBudgetPerPeriod     客户当期冻结预算
     * @param serviceCapacityUnitsPerPeriod 当期服务容量（件/期）
     * @param pilotDurationMillis         PILOT 正耗时
     * @param pilotCost                   PILOT 成本（开工即费用化）
     * @param validationDurationMillis    VALIDATION 正耗时
     * @param validationCost              VALIDATION 成本（先资本化为在研）
     * @param pilotPasses                 冻结的 PILOT 结果
     * @param validationPasses            冻结的 VALIDATION 结果
     * @param fixedExpensePerPeriod       每期固定费用
     * @param amortizationPerPeriod       每期平台资产摊销
     * @param approvals                   冻结授权事实
     */
    public record Config(String companyOwnerId, String companyAccountId, String opsAccountId,
                         String customerId, String customerAccountId,
                         String contractorId, String contractorAccountId,
                         String capitalSponsorId, String capitalSponsorAccountId,
                         BigDecimal servicePricePerUnit, BigDecimal serviceUnitCost,
                         long customerDemandUnitsPerPeriod,
                         BigDecimal customerBudgetPerPeriod,
                         long serviceCapacityUnitsPerPeriod,
                         long pilotDurationMillis, BigDecimal pilotCost,
                         long validationDurationMillis, BigDecimal validationCost,
                         boolean pilotPasses, boolean validationPasses,
                         BigDecimal fixedExpensePerPeriod, BigDecimal amortizationPerPeriod,
                         Map<String, Approval> approvals) {

        /** 校验主体、账户分工、有限上限与阶段参数。 */
        public Config {
            requireText(companyOwnerId, "公司主体");
            requireText(companyAccountId, "公司账户");
            requireText(opsAccountId, "运营账户");
            requireText(customerId, "客户");
            requireText(customerAccountId, "客户账户");
            requireText(contractorId, "承包方");
            requireText(contractorAccountId, "承包方账户");
            requireText(capitalSponsorId, "注资方");
            requireText(capitalSponsorAccountId, "注资方账户");
            List<String> accounts = List.of(companyAccountId, opsAccountId, customerAccountId,
                    contractorAccountId, capitalSponsorAccountId);
            if (new LinkedHashSet<>(accounts).size() != accounts.size()) {
                throw new IllegalArgumentException("账户角色不得共用");
            }
            servicePricePerUnit = cost(servicePricePerUnit);
            if (servicePricePerUnit.signum() <= 0) {
                throw new IllegalArgumentException("服务单价必须为正");
            }
            serviceUnitCost = money(serviceUnitCost);
            if (serviceUnitCost.signum() < 0) {
                throw new IllegalArgumentException("单位服务成本不能为负");
            }
            if (customerDemandUnitsPerPeriod < 0 || serviceCapacityUnitsPerPeriod <= 0) {
                throw new IllegalArgumentException("需求不能为负、容量必须为正");
            }
            customerBudgetPerPeriod = money(customerBudgetPerPeriod);
            if (customerBudgetPerPeriod.signum() < 0) {
                throw new IllegalArgumentException("客户预算不能为负");
            }
            if (pilotDurationMillis <= 0 || validationDurationMillis <= 0) {
                throw new IllegalArgumentException("阶段必须正耗时");
            }
            pilotCost = money(pilotCost);
            validationCost = money(validationCost);
            if (pilotCost.signum() <= 0 || validationCost.signum() <= 0) {
                throw new IllegalArgumentException("阶段成本必须为正");
            }
            fixedExpensePerPeriod = money(fixedExpensePerPeriod);
            amortizationPerPeriod = money(amortizationPerPeriod);
            if (fixedExpensePerPeriod.signum() < 0 || amortizationPerPeriod.signum() < 0) {
                throw new IllegalArgumentException("费用与摊销不能为负");
            }
            Objects.requireNonNull(approvals, "授权事实");
            for (Map.Entry<String, Approval> entry : approvals.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().approvalId())) {
                    throw new IllegalArgumentException("授权引用与键不一致：" + entry.getKey());
                }
            }
            approvals = Map.copyOf(approvals);
        }
    }

    // ---------------------------------------------------------------- 状态元素

    /** 两阶段。 */
    public enum Stage {
        /** 试点：费用化。 */
        PILOT,
        /** 验证：资本化后摊销。 */
        VALIDATION
    }

    /** 阶段状态。 */
    public enum StageStatus {
        /** 进行中。 */
        RUNNING,
        /** 已通过。 */
        PASSED,
        /** 已失败（不可重试）。 */
        FAILED
    }

    /**
     * 一次阶段运行记录。
     *
     * @param stage       阶段
     * @param cost        成本
     * @param capitalised 是否资本化（PILOT 为 false）
     * @param beganAt     开工时点
     * @param completesAt 应完成时点（正耗时）
     * @param status      状态
     * @param evidenceId  冻结证据引用（未完成时为空串）
     */
    public record StageRecord(Stage stage, BigDecimal cost, boolean capitalised, long beganAt,
                              long completesAt, StageStatus status, String evidenceId) {

        /** 校验阶段记录。 */
        public StageRecord {
            Objects.requireNonNull(stage, "阶段");
            cost = money(cost);
            Objects.requireNonNull(status, "阶段状态");
            if (cost.signum() < 0 || completesAt <= beganAt) {
                throw new IllegalArgumentException("阶段记录非法");
            }
            if (evidenceId == null) {
                evidenceId = "";
            }
        }
    }

    /**
     * 冻结证据：只认引用与观察时点，不接受“重试再抽一次”。
     *
     * @param evidenceId 证据标识
     * @param reference  证据出处（发布/读完/观察记录）
     * @param observedAt 观察时点
     */
    public record Evidence(String evidenceId, String reference, long observedAt) {

        /** 校验证据。 */
        public Evidence {
            requireText(evidenceId, "证据标识");
            requireText(reference, "证据出处");
            if (observedAt < 0) {
                throw new IllegalArgumentException("观察时点不能为负");
            }
        }
    }

    /** 服务合同状态。 */
    public enum ContractStatus {
        /** 履行中。 */
        ACTIVE,
        /** 已交付完毕。 */
        COMPLETED,
        /** 已终止（未赚预收仍为退款义务）。 */
        TERMINATED
    }

    /**
     * 付费服务合同。
     *
     * @param contractId     合同标识
     * @param customerId     客户
     * @param quantity       合同总量
     * @param delivered      已交付量
     * @param unitPrice      合同单价
     * @param prepaid        已收预收
     * @param prepaidApplied 已随交付转为收入的预收
     * @param refunded       已实际退款
     * @param signedAt       签约时点
     * @param period         签约期间
     * @param status         状态
     * @param terminatedAt   终止时点；未终止为 -1
     */
    public record Contract(String contractId, String customerId, long quantity, long delivered,
                           BigDecimal unitPrice, BigDecimal prepaid, BigDecimal prepaidApplied,
                           BigDecimal refunded, long signedAt, long period,
                           ContractStatus status, long terminatedAt) {

        /** 校验合同。 */
        public Contract {
            requireText(contractId, "合同标识");
            requireText(customerId, "客户");
            Objects.requireNonNull(status, "合同状态");
            unitPrice = cost(unitPrice);
            prepaid = money(prepaid);
            prepaidApplied = money(prepaidApplied);
            refunded = money(refunded);
            if (quantity <= 0 || delivered < 0 || delivered > quantity) {
                throw new IllegalArgumentException("合同数量非法");
            }
            if (unitPrice.signum() <= 0 || prepaid.signum() < 0 || prepaidApplied.signum() < 0
                    || refunded.signum() < 0) {
                throw new IllegalArgumentException("合同金额非法");
            }
            if (prepaidApplied.add(refunded).compareTo(prepaid) > 0) {
                throw new IllegalArgumentException("已转收入与已退款之和不得超过预收");
            }
        }

        /** @return 未交付量 */
        public long remaining() {
            return quantity - delivered;
        }

        /** @return 未赚预收（退款义务） */
        public BigDecimal unearned() {
            return money(prepaid.subtract(prepaidApplied).subtract(refunded));
        }
    }

    /**
     * 应收：交付时确认收入与成本，未收部分在这里，收款只冲它。
     *
     * @param receivableId 应收标识
     * @param contractId   来源合同
     * @param customerId   客户
     * @param units        本次交付量
     * @param amount       本次未收金额（AR）
     * @param revenue      本次确认收入
     * @param cost         本次服务成本
     * @param recognizedAt 确认时点
     * @param dueAt        到期时点
     * @param paid         已收金额
     * @param status       状态
     */
    public record Receivable(String receivableId, String contractId, String customerId, long units,
                             BigDecimal amount, BigDecimal revenue, BigDecimal cost,
                             long recognizedAt, long dueAt, BigDecimal paid,
                             ReceivableStatus status) {

        /** 校验应收。 */
        public Receivable {
            requireText(receivableId, "应收标识");
            requireText(contractId, "来源合同");
            requireText(customerId, "客户");
            Objects.requireNonNull(status, "应收状态");
            amount = money(amount);
            revenue = money(revenue);
            cost = money(cost);
            paid = money(paid);
            if (units <= 0 || amount.signum() < 0 || revenue.signum() < 0 || cost.signum() < 0
                    || paid.signum() < 0 || paid.compareTo(amount) > 0) {
                throw new IllegalArgumentException("应收金额非法");
            }
        }

        /** @return 未收金额 */
        public BigDecimal outstanding() {
            return money(amount.subtract(paid));
        }
    }

    /** 应收状态。 */
    public enum ReceivableStatus {
        /** 未收。 */
        OPEN,
        /** 已收（或本就无需收）。 */
        PAID,
        /** 已核销（坏账）。 */
        WRITTEN_OFF
    }

    /**
     * 期间费用账单。
     *
     * @param period 期间
     * @param amount 应计
     * @param paid   已付
     */
    public record ExpenseBill(long period, BigDecimal amount, BigDecimal paid) {

        /** 校验账单。 */
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
            return money(amount.subtract(paid));
        }
    }

    /**
     * 不可变状态（不含现金）。
     *
     * @param config               冻结配置
     * @param openingOwners        开局冻结账户→主体
     * @param initialEquity        初始权益
     * @param initialWorldCash     开局世界现金
     * @param watermark            已见时间轴
     * @param stages               阶段记录
     * @param researchFailed       研究是否已失败（失败即停）
     * @param commercialApproved   是否已获商业化批准
     * @param commercialApprovalId 批准引用
     * @param platformCost         平台资产原值（VALIDATION 通过后转入）
     * @param amortized            累计摊销
     * @param pilotExpense         累计试点费用
     * @param impairment           累计减值（失败损失）
     * @param badDebtLoss          累计坏账
     * @param fixedExpenses        累计期间费用
     * @param capitalContributions 累计外部注资
     * @param contracts            合同
     * @param receivables          应收
     * @param bills                期间费用
     * @param closedPeriods        已连续关账期间数
     * @param commandContents      命令内容（幂等）
     * @param commandReplies       命令回报（幂等）
     * @param usedIds              已用标识
     */
    public record State(Config config, Map<String, String> openingOwners, BigDecimal initialEquity,
                        BigDecimal initialWorldCash, long watermark, Map<Stage, StageRecord> stages,
                        boolean researchFailed, boolean commercialApproved,
                        String commercialApprovalId, BigDecimal platformCost, BigDecimal amortized,
                        BigDecimal pilotExpense, BigDecimal impairment, BigDecimal badDebtLoss,
                        BigDecimal fixedExpenses, BigDecimal capitalContributions,
                        Map<String, Contract> contracts, Map<String, Receivable> receivables,
                        Map<Long, ExpenseBill> bills, long closedPeriods,
                        Map<String, String> commandContents, Map<String, Reply> commandReplies,
                        Set<String> usedIds) {

        /** 防御性复制与基础校验。 */
        public State {
            Objects.requireNonNull(config, "配置");
            openingOwners = Map.copyOf(Objects.requireNonNull(openingOwners, "开局主体映射"));
            initialEquity = money(initialEquity);
            initialWorldCash = money(initialWorldCash);
            stages = Map.copyOf(Objects.requireNonNull(stages, "阶段记录"));
            if (commercialApprovalId == null) {
                commercialApprovalId = "";
            }
            platformCost = money(platformCost);
            amortized = money(amortized);
            pilotExpense = money(pilotExpense);
            impairment = money(impairment);
            badDebtLoss = money(badDebtLoss);
            fixedExpenses = money(fixedExpenses);
            capitalContributions = money(capitalContributions);
            contracts = Map.copyOf(contracts);
            receivables = Map.copyOf(receivables);
            bills = Map.copyOf(bills);
            commandContents = Map.copyOf(commandContents);
            commandReplies = Map.copyOf(commandReplies);
            usedIds = Set.copyOf(usedIds);
            if (watermark < 0 || closedPeriods < 0) {
                throw new IllegalArgumentException("时间轴或关账数不能为负");
            }
            if (amortized.compareTo(platformCost) > 0) {
                throw new IllegalArgumentException("累计摊销不得超过平台资产原值");
            }
        }

        /**
         * 开局冻结账户主体。
         *
         * @param accountId 账户
         * @return 主体；未知 null
         */
        public String openingOwnerOf(String accountId) {
            return accountId == null ? null : openingOwners.get(accountId);
        }

        /**
         * 某阶段记录。
         *
         * @param stage 阶段
         * @return 记录；未跑过返回 null
         */
        public StageRecord stageOf(Stage stage) {
            return stages.get(stage);
        }

        /**
         * 平台资产净值。
         *
         * @return 原值减累计摊销
         */
        public BigDecimal platformNet() {
            return money(platformCost.subtract(amortized));
        }

        /**
         * 在研：资本化但尚未通过验证的阶段成本。
         *
         * @return 在研余额
         */
        public BigDecimal workInProgress() {
            BigDecimal wip = ZERO;
            for (StageRecord record : stages.values()) {
                if (record.capitalised() && record.status() == StageStatus.RUNNING) {
                    wip = wip.add(record.cost());
                }
            }
            return money(wip);
        }

        /**
         * 退款义务合计（未赚预收）。
         *
         * @return 合计
         */
        public BigDecimal refundObligation() {
            BigDecimal total = ZERO;
            for (Contract contract : contracts.values()) {
                total = total.add(contract.unearned());
            }
            return money(total);
        }

        /**
         * 已终止合同上尚未退的预收（因终止即到期，因此就是逾期部分）。
         *
         * @return 合计
         */
        public BigDecimal refundOverdue() {
            BigDecimal total = ZERO;
            for (Contract contract : contracts.values()) {
                if (contract.status() == ContractStatus.TERMINATED) {
                    total = total.add(contract.unearned());
                }
            }
            return money(total);
        }

        /**
         * 某期间已交付服务量（含已收款/已核销的交付，永不回退）。
         *
         * @param period 期间
         * @return 件数
         */
        public long deliveredIn(long period) {
            long total = 0L;
            for (Receivable receivable : receivables.values()) {
                if (receivable.recognizedAt() / PERIOD_MILLIS == period) {
                    total = Math.addExact(total, receivable.units());
                }
            }
            return total;
        }

        /**
         * 某期间已交付收入（用于预算占用）。
         *
         * @param period 期间
         * @return 金额
         */
        public BigDecimal deliveredRevenueIn(long period) {
            BigDecimal total = ZERO;
            for (Receivable receivable : receivables.values()) {
                if (receivable.recognizedAt() / PERIOD_MILLIS == period) {
                    total = total.add(receivable.revenue());
                }
            }
            return money(total);
        }

        /**
         * 某期间仍被履行中合同占用的需求量（签约承诺，终止即释放）。
         *
         * @param period 期间
         * @return 件数
         */
        public long reservedIn(long period) {
            long total = 0L;
            for (Contract contract : contracts.values()) {
                if (contract.status() == ContractStatus.ACTIVE && contract.period() == period) {
                    total = Math.addExact(total, contract.remaining());
                }
            }
            return total;
        }

        /**
         * 某期间仍被履行中合同占用的预算。
         *
         * @param period 期间
         * @return 金额
         */
        public BigDecimal reservedBudgetIn(long period) {
            BigDecimal total = ZERO;
            for (Contract contract : contracts.values()) {
                if (contract.status() == ContractStatus.ACTIVE && contract.period() == period) {
                    total = total.add(money(contract.unitPrice()
                            .multiply(BigDecimal.valueOf(contract.remaining()))));
                }
            }
            return money(total);
        }
    }

    /** 幂等回报。 */
    public record Reply(boolean accepted, String reason, boolean paid) {
    }

    /**
     * 一次动作的候选结果。
     *
     * @param accepted 业务是否成功
     * @param reason   结果码
     * @param state    候选状态（拒绝时为原状态）
     * @param funds    候选资金三态
     */
    public record Outcome(boolean accepted, String reason, State state, FundState funds) {
    }

    /**
     * 报表：A=L+E，收入只来自交付。
     *
     * @param companyCash       公司现金
     * @param receivables       应收未收
     * @param platformNet       平台资产净值
     * @param wip               在研
     * @param payables          应付未付
     * @param refundObligation  退款义务（未赚预收）
     * @param refundOverdue     其中已终止合同的逾期部分
     * @param assets            资产合计
     * @param liabilities       负债合计
     * @param equity            权益
     * @param revenue           累计服务收入
     * @param serviceCogs       累计服务成本
     * @param pilotExpense      累计试点费用
     * @param amortization      累计摊销
     * @param impairment        累计减值
     * @param badDebtLoss       累计坏账
     * @param fixedExpense      累计期间费用
     * @param capitalContributions 累计外部注资
     * @param validated         是否已通过 VALIDATION
     * @param balanced          A=L+E 且世界现金守恒
     * @param worldCash         世界现金
     */
    public record Report(BigDecimal companyCash, BigDecimal receivables, BigDecimal platformNet,
                         BigDecimal wip, BigDecimal payables, BigDecimal refundObligation,
                         BigDecimal refundOverdue, BigDecimal assets, BigDecimal liabilities,
                         BigDecimal equity, BigDecimal revenue, BigDecimal serviceCogs,
                         BigDecimal pilotExpense, BigDecimal amortization, BigDecimal impairment,
                         BigDecimal badDebtLoss, BigDecimal fixedExpense,
                         BigDecimal capitalContributions, boolean validated, boolean balanced,
                         BigDecimal worldCash) {
    }

    // ---------------------------------------------------------------- 开局

    /**
     * 建立开局状态：初始权益 = 公司现金。
     *
     * @param config 冻结配置
     * @param funds  开局资金三态
     * @return 初始状态
     */
    public static State open(Config config, FundState funds) {
        Objects.requireNonNull(config, "配置");
        Objects.requireNonNull(funds, "资金三态");
        Map<String, String> owners = new LinkedHashMap<>();
        Set<String> accounts = new LinkedHashSet<>(List.of(config.companyAccountId(),
                config.opsAccountId(), config.customerAccountId(), config.contractorAccountId(),
                config.capitalSponsorAccountId()));
        for (String accountId : accounts) {
            CashLedger.CashAccount account = funds.accounts().get(accountId);
            if (account == null) {
                throw new IllegalArgumentException("缺少开局账户：" + accountId);
            }
            if (account.ownerId() == null || account.ownerId().isBlank()) {
                throw new IllegalArgumentException("开局账户缺少所有者：" + accountId);
            }
            owners.put(accountId, account.ownerId());
        }
        if (!config.companyOwnerId().equals(owners.get(config.companyAccountId()))) {
            throw new IllegalArgumentException("公司账户主体与配置不一致");
        }
        return new State(config, owners, funds.cash(config.companyAccountId()), worldCash(funds),
                0L, Map.of(), false, false, "", ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO,
                Map.of(), Map.of(), Map.of(), 0L, Map.of(), Map.of(), Set.of());
    }

    // ---------------------------------------------------------------- 研究阶段

    /**
     * 开工一个阶段：付承包方；PILOT 立即费用化，VALIDATION 计入在研；正耗时按冻结时长。
     *
     * @param state     状态
     * @param now       当前时点
     * @param actor     发起人
     * @param commandId 命令标识
     * @param stage     阶段
     * @param funds     当前资金三态
     * @param payer     资金腿（公司 → 承包方）
     * @return 候选
     */
    public static Outcome beginStage(State state, long now, String actor, String commandId,
                                     Stage stage, FundState funds, FundPayer payer) {
        Objects.requireNonNull(stage, "阶段");
        String content = "BEGIN|" + stage;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        if (state.researchFailed()) {
            return reject(state, RESEARCH_FAILED, funds);
        }
        if (state.stages().containsKey(stage)) {
            return reject(state, STAGE_ALREADY_RUN, funds);
        }
        if (stage == Stage.VALIDATION) {
            StageRecord pilot = state.stages().get(Stage.PILOT);
            if (pilot == null || pilot.status() != StageStatus.PASSED) {
                return reject(state, STAGE_ORDER, funds);
            }
        }
        long duration = stage == Stage.PILOT
                ? state.config().pilotDurationMillis() : state.config().validationDurationMillis();
        BigDecimal cost = stage == Stage.PILOT
                ? state.config().pilotCost() : state.config().validationCost();
        Paid paid = pay(payer, commandId, state.config().companyAccountId(),
                state.config().contractorAccountId(), cost, funds, state);
        if (!paid.accepted()) {
            return new Outcome(false, paid.reason(), state, paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.stages.put(stage, new StageRecord(stage, cost, stage == Stage.VALIDATION, now,
                now + duration, StageStatus.RUNNING, ""));
        if (stage == Stage.PILOT) {
            draft.pilotExpense = money(state.pilotExpense().add(cost));
        }
        return accept(draft, "STAGE_BEGUN", paid, commandId, content);
    }

    /**
     * 完成阶段：到期 + 冻结证据；PILOT 通过只解锁下一阶段，VALIDATION 通过才形成平台资产。
     *
     * @param state     状态
     * @param now       当前时点
     * @param actor     发起人
     * @param commandId 命令标识
     * @param stage     阶段
     * @param evidence  冻结证据
     * @param funds     当前资金三态（不动钱）
     * @return 候选
     */
    public static Outcome completeStage(State state, long now, String actor, String commandId,
                                        Stage stage, Evidence evidence, FundState funds) {
        Objects.requireNonNull(stage, "阶段");
        String content = "COMPLETE|" + stage + "|" + evidence.evidenceId() + "|"
                + evidence.reference() + "|" + evidence.observedAt();
        Outcome replay = replay(state, commandId, content, funds, null);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        StageRecord record = state.stages().get(stage);
        if (record == null) {
            return reject(state, UNKNOWN_STAGE, funds);
        }
        if (record.status() != StageStatus.RUNNING) {
            return reject(state, STAGE_STATE_INVALID, funds);
        }
        if (now < record.completesAt()) {
            return reject(state, STAGE_NOT_DUE, funds);
        }
        if (evidence.observedAt() < record.beganAt() || evidence.observedAt() > now) {
            return reject(state, EVIDENCE_INVALID, funds);
        }
        boolean pass = stage == Stage.PILOT
                ? state.config().pilotPasses() : state.config().validationPasses();
        Draft draft = new Draft(state, now);
        draft.stages.put(stage, new StageRecord(stage, record.cost(), record.capitalised(),
                record.beganAt(), record.completesAt(),
                pass ? StageStatus.PASSED : StageStatus.FAILED, evidence.evidenceId()));
        if (!pass) {
            draft.researchFailed = true;
            if (record.capitalised()) {
                draft.impairment = money(state.impairment().add(record.cost()));
            }
        } else if (record.capitalised()) {
            draft.platformCost = money(state.platformCost().add(record.cost()));
        }
        return accept(draft, pass ? "STAGE_PASSED" : "STAGE_FAILED", Paid.noop(funds),
                commandId, content);
    }

    /**
     * 记录商业化批准：只是授权事实，不产生合同、订单或收入。
     *
     * @param state       状态
     * @param now         当前时点
     * @param actor       发起人
     * @param commandId   命令标识
     * @param approvalRef 批准引用
     * @param funds       当前资金三态（不动钱）
     * @return 候选
     */
    public static Outcome approveCommercialization(State state, long now, String actor,
                                                   String commandId, String approvalRef,
                                                   FundState funds) {
        String content = "APPROVE|" + approvalRef;
        Outcome replay = replay(state, commandId, content, funds, null);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        if (approvalRef == null || approvalRef.isBlank()) {
            return reject(state, NO_APPROVAL, funds);
        }
        Approval approval = state.config().approvals().get(approvalRef);
        if (approval == null || !APPROVAL_COMMERCIAL.equals(approval.kind())
                || approval.approvedAt() > now) {
            return reject(state, APPROVAL_INVALID, funds);
        }
        if (state.commercialApproved()) {
            return reject(state, ID_REUSED, funds);
        }
        Draft draft = new Draft(state, now);
        draft.commercialApproved = true;
        draft.commercialApprovalId = approvalRef;
        return accept(draft, "APPROVED", Paid.noop(funds), commandId, content);
    }

    // ---------------------------------------------------------------- 服务合同

    /**
     * 签约：需 VALIDATION 通过 + 商业化批准；客户预收真实现金 → 合同负债；不确认收入。
     *
     * <p>预收可以少于合同总价（差额在交付时转应收），但不得超过总价；需求与预算按合同总价
     * 占用，因此签约先占满客户当期额度。</p>
     *
     * @param state      状态
     * @param now        当前时点
     * @param actor      发起人
     * @param commandId  命令标识
     * @param contractId 合同标识
     * @param quantity   合同量
     * @param prepaid    本次预收（0 到合同总价之间）
     * @param funds      当前资金三态
     * @param payer      资金腿（客户 → 公司）
     * @return 候选
     */
    public static Outcome signServiceContract(State state, long now, String actor,
                                              String commandId, String contractId, long quantity,
                                              BigDecimal prepaid, FundState funds,
                                              FundPayer payer) {
        String content = "SIGN|" + contractId + "|" + quantity + "|" + prepaid;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        if (quantity <= 0) {
            return reject(state, INVALID_QUANTITY, funds);
        }
        StageRecord validation = state.stages().get(Stage.VALIDATION);
        if (validation == null || validation.status() != StageStatus.PASSED) {
            return reject(state, NOT_VALIDATED, funds);
        }
        if (!state.commercialApproved()) {
            return reject(state, NO_APPROVAL, funds);
        }
        if (state.contracts().containsKey(contractId)) {
            return reject(state, ID_REUSED, funds);
        }
        long period = now / PERIOD_MILLIS;
        BigInteger demanded = BigInteger.valueOf(state.reservedIn(period))
                .add(BigInteger.valueOf(state.deliveredIn(period)))
                .add(BigInteger.valueOf(quantity));
        if (demanded.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return reject(state, QUANTITY_OVERFLOW, funds);
        }
        if (demanded.compareTo(BigInteger.valueOf(
                state.config().customerDemandUnitsPerPeriod())) > 0) {
            return reject(state, DEMAND_EXCEEDED, funds);
        }
        BigDecimal value = money(state.config().servicePricePerUnit()
                .multiply(BigDecimal.valueOf(quantity)));
        BigDecimal advance = money(prepaid);
        if (advance.signum() < 0 || advance.compareTo(value) > 0) {
            return reject(state, PREPAID_EXCEEDS_CONTRACT, funds);
        }
        BigDecimal budget = state.reservedBudgetIn(period)
                .add(state.deliveredRevenueIn(period)).add(value);
        if (budget.compareTo(state.config().customerBudgetPerPeriod()) > 0) {
            return reject(state, BUDGET_EXCEEDED, funds);
        }
        Paid paid = pay(payer, commandId, state.config().customerAccountId(),
                state.config().companyAccountId(), advance, funds, state);
        if (!paid.accepted()) {
            String reason = INSUFFICIENT_CASH.equals(paid.reason())
                    ? CUSTOMER_CASH_SHORT : paid.reason();
            return new Outcome(false, reason, state, paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.contracts.put(contractId, new Contract(contractId, state.config().customerId(),
                quantity, 0L, state.config().servicePricePerUnit(), advance, ZERO, ZERO, now,
                period, ContractStatus.ACTIVE, -1L));
        return accept(draft, "SIGNED", paid, commandId, content);
    }

    /**
     * 交付：按实际服务量确认收入与成本（服务成本真实现金支付），按比例冲减退款义务，
     * 未收部分转应收。
     *
     * <p><b>当期额度在交付时也要复核</b>：客户需求与预算按期间冻结，签约占用记在签约期间；
     * 跨期交付会把上一期合同的剩余量真正落到当期，所以这里按“当期已交付 + 当期仍开放合同占用 +
     * 本次新增”再核一次。同一期间的合同交付只是把预约转成已交付，净占用不变（不重复占一次），
     * 因此 {@code delta=0}；跨期旧合同本次交付才新增当期占用。拒绝时合同、预收义务与退款义务
     * 原样保留，不自动释放旧债。</p>
     *
     * @param state        状态
     * @param now          当前时点
     * @param actor        发起人
     * @param commandId    命令标识
     * @param contractId   合同
     * @param units        交付量
     * @param receivableId 应收标识（本次未收为 0 时也占位，便于追溯）
     * @param funds        当前资金三态
     * @param payer        资金腿（公司 → 运营，服务成本）
     * @return 候选
     */
    public static Outcome deliverService(State state, long now, String actor, String commandId,
                                         String contractId, long units, String receivableId,
                                         FundState funds, FundPayer payer) {
        String content = "DELIVER|" + contractId + "|" + units + "|" + receivableId;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return reject(state, UNKNOWN_CONTRACT, funds);
        }
        if (contract.status() != ContractStatus.ACTIVE) {
            return reject(state, CONTRACT_STATE_INVALID, funds);
        }
        if (units <= 0 || units > contract.remaining()) {
            return reject(state, DELIVERY_EXCEEDS_CONTRACT, funds);
        }
        long period = now / PERIOD_MILLIS;
        boolean samePeriod = contract.period() == period;
        BigInteger committedDemand = BigInteger.valueOf(state.deliveredIn(period))
                .add(BigInteger.valueOf(state.reservedIn(period)))
                .add(samePeriod ? BigInteger.ZERO : BigInteger.valueOf(units));
        if (committedDemand.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return reject(state, QUANTITY_OVERFLOW, funds);
        }
        if (committedDemand.compareTo(BigInteger.valueOf(
                state.config().customerDemandUnitsPerPeriod())) > 0) {
            return reject(state, DEMAND_EXCEEDED, funds);
        }
        BigDecimal revenue = money(contract.unitPrice().multiply(BigDecimal.valueOf(units)));
        BigDecimal committedBudget = state.deliveredRevenueIn(period)
                .add(state.reservedBudgetIn(period))
                .add(samePeriod ? ZERO : revenue);
        if (committedBudget.compareTo(state.config().customerBudgetPerPeriod()) > 0) {
            return reject(state, BUDGET_EXCEEDED, funds);
        }
        BigInteger delivered = BigInteger.valueOf(state.deliveredIn(period))
                .add(BigInteger.valueOf(units));
        if (delivered.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return reject(state, QUANTITY_OVERFLOW, funds);
        }
        if (delivered.compareTo(BigInteger.valueOf(
                state.config().serviceCapacityUnitsPerPeriod())) > 0) {
            return reject(state, SERVICE_CAPACITY, funds);
        }
        if (state.usedIds().contains(receivableId)) {
            return reject(state, ID_REUSED, funds);
        }
        BigDecimal applied = appliedPrepaid(contract, units);
        BigDecimal cost = money(state.config().serviceUnitCost()
                .multiply(BigDecimal.valueOf(units)));
        Paid paid = pay(payer, commandId, state.config().companyAccountId(),
                state.config().opsAccountId(), cost, funds, state);
        if (!paid.accepted()) {
            return new Outcome(false, paid.reason(), state, paid.funds());
        }
        BigDecimal receivableAmount = money(revenue.subtract(applied));
        long deliveredTotal = contract.delivered() + units;
        ContractStatus status = deliveredTotal == contract.quantity()
                ? ContractStatus.COMPLETED : ContractStatus.ACTIVE;
        Draft draft = new Draft(state, now);
        draft.contracts.put(contractId, new Contract(contract.contractId(), contract.customerId(),
                contract.quantity(), deliveredTotal, contract.unitPrice(), contract.prepaid(),
                money(contract.prepaidApplied().add(applied)), contract.refunded(),
                contract.signedAt(), contract.period(), status, contract.terminatedAt()));
        draft.receivables.put(receivableId, new Receivable(receivableId, contractId,
                contract.customerId(), units, receivableAmount, revenue, cost, now,
                now + 15L * DAY_MILLIS, ZERO,
                receivableAmount.signum() == 0 ? ReceivableStatus.PAID : ReceivableStatus.OPEN));
        draft.usedIds.add(receivableId);
        return accept(draft, "DELIVERED", paid, commandId, content);
    }

    /**
     * 终止：释放未交付承诺，已交付额度不恢复；未赚预收继续列为退款义务（逾期）。
     *
     * @param state      状态
     * @param now        当前时点
     * @param actor      发起人
     * @param commandId  命令标识
     * @param contractId 合同
     * @param funds      当前资金三态（不动钱）
     * @return 候选
     */
    public static Outcome terminateContract(State state, long now, String actor, String commandId,
                                            String contractId, FundState funds) {
        String content = "TERMINATE|" + contractId;
        Outcome replay = replay(state, commandId, content, funds, null);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return reject(state, UNKNOWN_CONTRACT, funds);
        }
        if (contract.status() != ContractStatus.ACTIVE) {
            return reject(state, CONTRACT_STATE_INVALID, funds);
        }
        Draft draft = new Draft(state, now);
        draft.contracts.put(contractId, new Contract(contract.contractId(), contract.customerId(),
                contract.quantity(), contract.delivered(), contract.unitPrice(), contract.prepaid(),
                contract.prepaidApplied(), contract.refunded(), contract.signedAt(),
                contract.period(), ContractStatus.TERMINATED, now));
        return accept(draft, "TERMINATED", Paid.noop(funds), commandId, content);
    }

    /**
     * 退款：只退终止合同上尚未赚取的预收，必须有足额权威现金；付不出时负债原样保留。
     *
     * @param state      状态
     * @param now        当前时点
     * @param actor      发起人
     * @param commandId  命令标识
     * @param contractId 合同
     * @param amount     退款额
     * @param funds      当前资金三态
     * @param payer      资金腿（公司 → 客户）
     * @return 候选
     */
    public static Outcome refund(State state, long now, String actor, String commandId,
                                 String contractId, BigDecimal amount, FundState funds,
                                 FundPayer payer) {
        String content = "REFUND|" + contractId + "|" + amount;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return reject(state, UNKNOWN_CONTRACT, funds);
        }
        if (contract.status() != ContractStatus.TERMINATED) {
            return reject(state, CONTRACT_NOT_TERMINATED, funds);
        }
        BigDecimal value = money(amount);
        if (value.signum() <= 0) {
            return reject(state, INVALID_QUANTITY, funds);
        }
        if (value.compareTo(contract.unearned()) > 0) {
            return reject(state, REFUND_EXCEEDS_LIABILITY, funds);
        }
        Paid paid = pay(payer, commandId, state.config().companyAccountId(),
                state.config().customerAccountId(), value, funds, state);
        if (!paid.accepted()) {
            return new Outcome(false, paid.reason(), state, paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.contracts.put(contractId, new Contract(contract.contractId(), contract.customerId(),
                contract.quantity(), contract.delivered(), contract.unitPrice(), contract.prepaid(),
                contract.prepaidApplied(), money(contract.refunded().add(value)),
                contract.signedAt(), contract.period(), ContractStatus.TERMINATED,
                contract.terminatedAt()));
        return accept(draft, "REFUNDED", paid, commandId, content);
    }

    // ---------------------------------------------------------------- 收款/坏账/注资

    /**
     * 收款：只冲应收，不动收入。
     *
     * @param state        状态
     * @param now          当前时点
     * @param actor        发起人
     * @param commandId    命令标识
     * @param receivableId 应收
     * @param funds        当前资金三态
     * @param payer        资金腿（客户 → 公司）
     * @return 候选
     */
    public static Outcome collect(State state, long now, String actor, String commandId,
                                  String receivableId, FundState funds, FundPayer payer) {
        String content = "COLLECT|" + receivableId;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        Receivable receivable = state.receivables().get(receivableId);
        if (receivable == null) {
            return reject(state, UNKNOWN_RECEIVABLE, funds);
        }
        if (receivable.status() != ReceivableStatus.OPEN) {
            return reject(state, RECEIVABLE_CLOSED, funds);
        }
        if (now < receivable.dueAt()) {
            return reject(state, RECEIVABLE_NOT_DUE, funds);
        }
        Paid paid = pay(payer, commandId, state.config().customerAccountId(),
                state.config().companyAccountId(), receivable.outstanding(), funds, state);
        if (!paid.accepted()) {
            String reason = INSUFFICIENT_CASH.equals(paid.reason())
                    ? CUSTOMER_CASH_SHORT : paid.reason();
            return new Outcome(false, reason, state, paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.receivables.put(receivableId, new Receivable(receivable.receivableId(),
                receivable.contractId(), receivable.customerId(), receivable.units(),
                receivable.amount(), receivable.revenue(), receivable.cost(),
                receivable.recognizedAt(), receivable.dueAt(), receivable.outstanding(),
                ReceivableStatus.PAID));
        return accept(draft, "PAYMENT_RECEIVED", paid, commandId, content);
    }

    /**
     * 坏账核销：逾期后核销，收入不倒回。
     *
     * @param state        状态
     * @param now          当前时点
     * @param actor        发起人
     * @param commandId    命令标识
     * @param receivableId 应收
     * @param funds        当前资金三态（不动钱）
     * @return 候选
     */
    public static Outcome writeOff(State state, long now, String actor, String commandId,
                                   String receivableId, FundState funds) {
        String content = "WRITE_OFF|" + receivableId;
        Outcome replay = replay(state, commandId, content, funds, null);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        Receivable receivable = state.receivables().get(receivableId);
        if (receivable == null) {
            return reject(state, UNKNOWN_RECEIVABLE, funds);
        }
        if (receivable.status() != ReceivableStatus.OPEN) {
            return reject(state, RECEIVABLE_CLOSED, funds);
        }
        if (now <= receivable.dueAt()) {
            return reject(state, BAD_DEBT_NOT_DUE, funds);
        }
        Draft draft = new Draft(state, now);
        draft.receivables.put(receivableId, new Receivable(receivable.receivableId(),
                receivable.contractId(), receivable.customerId(), receivable.units(),
                receivable.amount(), receivable.revenue(), receivable.cost(),
                receivable.recognizedAt(), receivable.dueAt(), receivable.paid(),
                ReceivableStatus.WRITTEN_OFF));
        draft.badDebtLoss = money(state.badDebtLoss().add(receivable.outstanding()));
        return accept(draft, "WRITTEN_OFF", Paid.noop(funds), commandId, content);
    }

    /**
     * EXP 外部资本注入：真实账户间转移，世界现金不变，权益增加。
     *
     * @param state     状态
     * @param now       当前时点
     * @param actor     发起人
     * @param commandId 命令标识
     * @param amount    金额
     * @param funds     当前资金三态
     * @param payer     资金腿（注资方 → 公司）
     * @return 候选
     */
    public static Outcome injectCapital(State state, long now, String actor, String commandId,
                                        BigDecimal amount, FundState funds, FundPayer payer) {
        String content = "CAPITAL|" + amount;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        String deny = guard(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        BigDecimal value = money(amount);
        if (value.signum() <= 0) {
            return reject(state, INVALID_QUANTITY, funds);
        }
        Paid paid = pay(payer, commandId, state.config().capitalSponsorAccountId(),
                state.config().companyAccountId(), value, funds, state);
        if (!paid.accepted()) {
            return new Outcome(false, paid.reason(), state, paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.capitalContributions = money(state.capitalContributions().add(value));
        return accept(draft, "CAPITAL_INJECTED", paid, commandId, content);
    }

    // ---------------------------------------------------------------- 关期

    /**
     * 期末关账：付固定费用（不足成应付）+ 平台资产按期摊销；必须连续且期间已结束。
     *
     * @param state     状态
     * @param now       当前时点
     * @param actor     发起人
     * @param commandId 命令标识
     * @param period    期间
     * @param funds     当前资金三态
     * @param payer     资金腿（公司 → 运营）
     * @return 候选
     */
    public static Outcome closePeriod(State state, long now, String actor, String commandId,
                                      long period, FundState funds, FundPayer payer) {
        String content = "CLOSE|" + period;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        if (!state.config().companyOwnerId().equals(actor)) {
            return reject(state, NOT_AUTHORIZED, funds);
        }
        if (now < state.watermark()) {
            return reject(state, TIME_REWIND, funds);
        }
        if (period != state.closedPeriods()) {
            return reject(state, period < state.closedPeriods()
                    ? PERIOD_CLOSED : PERIOD_NOT_CONTINUOUS, funds);
        }
        if (now < (period + 1) * PERIOD_MILLIS) {
            return reject(state, PERIOD_NOT_ENDED, funds);
        }
        if (payer == null) {
            return reject(state, NOT_AUTHORIZED, funds);
        }
        BigDecimal expense = state.config().fixedExpensePerPeriod();
        BigDecimal paying = money(expense.min(payer.available(funds)));
        Paid paid = Paid.noop(funds);
        if (paying.signum() > 0) {
            paid = pay(payer, commandId, state.config().companyAccountId(),
                    state.config().opsAccountId(), paying, funds, state);
            if (!paid.accepted()) {
                return new Outcome(false, paid.reason(), state, paid.funds());
            }
        }
        BigDecimal amortization = money(state.config().amortizationPerPeriod()
                .min(state.platformNet().signum() > 0 ? state.platformNet() : ZERO));
        Draft draft = new Draft(state, now);
        if (expense.signum() > 0) {
            draft.bills.put(period, new ExpenseBill(period, expense, paying));
        }
        draft.amortized = money(state.amortized().add(amortization));
        draft.fixedExpenses = money(state.fixedExpenses().add(expense));
        draft.closedPeriods = state.closedPeriods() + 1;
        return accept(draft, "PERIOD_CLOSED", paid, commandId, content);
    }

    // ---------------------------------------------------------------- 报表

    /**
     * 派生报表，不改状态。
     *
     * @param state 状态
     * @param funds 当前资金三态
     * @return 报表
     */
    public static Report report(State state, FundState funds) {
        Objects.requireNonNull(state, "状态");
        Objects.requireNonNull(funds, "资金三态");
        BigDecimal cash = funds.cash(state.config().companyAccountId());
        BigDecimal receivables = ZERO;
        BigDecimal revenue = ZERO;
        BigDecimal cogs = ZERO;
        for (Receivable receivable : state.receivables().values()) {
            if (receivable.status() == ReceivableStatus.OPEN) {
                receivables = receivables.add(receivable.outstanding());
            }
            revenue = revenue.add(receivable.revenue());
            cogs = cogs.add(receivable.cost());
        }
        BigDecimal payables = ZERO;
        for (ExpenseBill bill : state.bills().values()) {
            payables = payables.add(bill.outstanding());
        }
        BigDecimal platform = state.platformNet();
        BigDecimal wip = state.workInProgress();
        BigDecimal assets = money(cash.add(receivables).add(platform).add(wip));
        BigDecimal liabilities = money(payables.add(state.refundObligation()));
        BigDecimal profit = money(revenue.subtract(cogs).subtract(state.pilotExpense())
                .subtract(state.amortized()).subtract(state.impairment())
                .subtract(state.badDebtLoss()).subtract(state.fixedExpenses()));
        BigDecimal equity = money(state.initialEquity().add(state.capitalContributions())
                .add(profit));
        BigDecimal world = worldCash(funds);
        boolean balanced = assets.compareTo(liabilities.add(equity)) == 0
                && world.compareTo(state.initialWorldCash()) == 0;
        StageRecord validation = state.stages().get(Stage.VALIDATION);
        boolean validated = validation != null && validation.status() == StageStatus.PASSED;
        return new Report(money(cash), money(receivables), platform, wip, payables,
                state.refundObligation(), state.refundOverdue(), assets, liabilities, equity,
                money(revenue), money(cogs), money(state.pilotExpense()),
                money(state.amortized()), money(state.impairment()), money(state.badDebtLoss()),
                money(state.fixedExpenses()), money(state.capitalContributions()), validated,
                balanced, world);
    }

    /**
     * 校验报表平衡；不平衡抛异常。
     *
     * @param state 状态
     * @param funds 资金三态
     * @return 已校验报表
     */
    public static Report validate(State state, FundState funds) {
        Report report = report(state, funds);
        if (!report.balanced()) {
            throw new IllegalStateException("数字服务账不平衡：资产=" + report.assets()
                    + "，负债=" + report.liabilities() + "，权益=" + report.equity()
                    + "，世界现金=" + report.worldCash());
        }
        return report;
    }

    // ---------------------------------------------------------------- 内部

    /** 一次资金腿结果。 */
    private record Paid(boolean accepted, String reason, FundState funds, boolean moved) {

        private static Paid noop(FundState funds) {
            return new Paid(true, OK, funds, false);
        }
    }

    /** 按合同量分摊本次应冲减的预收；最后一批精确结尽。 */
    private static BigDecimal appliedPrepaid(Contract contract, long units) {
        BigDecimal unearned = contract.unearned();
        if (units == contract.remaining()) {
            return unearned;
        }
        BigDecimal proportional = contract.prepaid()
                .multiply(BigDecimal.valueOf(units))
                .divide(BigDecimal.valueOf(contract.quantity()), 2, RoundingMode.HALF_UP);
        return money(proportional.min(unearned));
    }

    private static String guard(State state, long now, String actor) {
        if (!state.config().companyOwnerId().equals(actor)) {
            return NOT_AUTHORIZED;
        }
        if (now < state.watermark()) {
            return TIME_REWIND;
        }
        long period = now / PERIOD_MILLIS;
        if (period < state.closedPeriods()) {
            return PERIOD_CLOSED;
        }
        if (period > state.closedPeriods()) {
            return PERIOD_NOT_CLOSED;
        }
        return null;
    }

    private static Outcome replay(State state, String commandId, String content, FundState funds,
                                  FundPayer payer) {
        String stored = state.commandContents().get(commandId);
        if (stored == null) {
            return null;
        }
        if (!stored.equals(content)) {
            return new Outcome(false, COMMAND_CONTENT_CONFLICT, state, funds);
        }
        Reply reply = state.commandReplies().get(commandId);
        if (reply.paid() && payer != null && !payer.hasAcceptedRecord(commandId, funds)) {
            return new Outcome(false, FUND_RECORD_DIVERGED, state, funds);
        }
        return new Outcome(reply.accepted(), "REPLAYED:" + reply.reason(), state, funds);
    }

    private static Outcome reject(State state, String reason, FundState funds) {
        return new Outcome(false, reason, state, funds);
    }

    private static Outcome accept(Draft draft, String reason, Paid paid, String commandId,
                                  String content) {
        draft.commandContents.put(commandId, content);
        draft.commandReplies.put(commandId, new Reply(true, reason, paid.moved()));
        return new Outcome(true, reason, draft.build(), paid.funds());
    }

    /**
     * 执行一次付款腿：主体来自开局冻结映射；先查可用额再真付款。
     */
    private static Paid pay(FundPayer payer, String commandId, String srcAccountId,
                            String dstAccountId, BigDecimal amount, FundState funds, State state) {
        BigDecimal value = money(amount);
        if (value.signum() <= 0) {
            return Paid.noop(funds);
        }
        if (payer == null) {
            return new Paid(false, NOT_AUTHORIZED, funds, false);
        }
        String expectedSrc = state.openingOwnerOf(srcAccountId);
        String expectedDst = state.openingOwnerOf(dstAccountId);
        if (expectedSrc == null || expectedDst == null) {
            return new Paid(false, UNKNOWN_ACCOUNT, funds, false);
        }
        if (!expectedSrc.equals(funds.ownerIdOf(payer.source()))
                || !expectedDst.equals(funds.ownerIdOf(payer.target()))) {
            return new Paid(false, FUND_OWNER_MISMATCH, funds, false);
        }
        if (payer.hasRecord(commandId, funds)) {
            return new Paid(false, FUND_ID_ALREADY_RECORDED, funds, false);
        }
        if (payer.available(funds).compareTo(value) < 0) {
            return new Paid(false, INSUFFICIENT_CASH, funds, false);
        }
        FundPayer.Result result = payer.pay(commandId, value, funds);
        return new Paid(result.accepted(), result.reason(), result.funds(), result.accepted());
    }

    private static BigDecimal worldCash(FundState funds) {
        BigDecimal total = ZERO;
        for (CashLedger.CashAccount account : funds.accounts().values()) {
            total = total.add(account.cash());
        }
        return money(total);
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("金额不能为空");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal cost(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("单价不能为空");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    /** 在不可变状态上做候选修改。 */
    private static final class Draft {
        private final State base;
        private final Map<Stage, StageRecord> stages;
        private final Map<String, Contract> contracts;
        private final Map<String, Receivable> receivables;
        private final Map<Long, ExpenseBill> bills;
        private final Map<String, String> commandContents;
        private final Map<String, Reply> commandReplies;
        private final Set<String> usedIds;
        private boolean researchFailed;
        private boolean commercialApproved;
        private String commercialApprovalId;
        private BigDecimal platformCost;
        private BigDecimal amortized;
        private BigDecimal pilotExpense;
        private BigDecimal impairment;
        private BigDecimal badDebtLoss;
        private BigDecimal fixedExpenses;
        private BigDecimal capitalContributions;
        private long closedPeriods;
        private long watermark;

        private Draft(State base, long now) {
            this.base = base;
            this.stages = new LinkedHashMap<>(base.stages());
            this.contracts = new LinkedHashMap<>(base.contracts());
            this.receivables = new LinkedHashMap<>(base.receivables());
            this.bills = new LinkedHashMap<>(base.bills());
            this.commandContents = new LinkedHashMap<>(base.commandContents());
            this.commandReplies = new LinkedHashMap<>(base.commandReplies());
            this.usedIds = new LinkedHashSet<>(base.usedIds());
            this.researchFailed = base.researchFailed();
            this.commercialApproved = base.commercialApproved();
            this.commercialApprovalId = base.commercialApprovalId();
            this.platformCost = base.platformCost();
            this.amortized = base.amortized();
            this.pilotExpense = base.pilotExpense();
            this.impairment = base.impairment();
            this.badDebtLoss = base.badDebtLoss();
            this.fixedExpenses = base.fixedExpenses();
            this.capitalContributions = base.capitalContributions();
            this.closedPeriods = base.closedPeriods();
            this.watermark = Math.max(base.watermark(), now);
        }

        private State build() {
            return new State(base.config(), base.openingOwners(), base.initialEquity(),
                    base.initialWorldCash(), watermark, stages, researchFailed,
                    commercialApproved, commercialApprovalId, platformCost, amortized,
                    pilotExpense, impairment, badDebtLoss, fixedExpenses, capitalContributions,
                    contracts, receivables, bills, closedPeriods, commandContents,
                    commandReplies, usedIds);
        }
    }
}
