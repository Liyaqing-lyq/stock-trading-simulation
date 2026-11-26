package edu.cufe.auction.world;

import edu.cufe.auction.world.ManufacturingModel.Inventory;
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
 * 一体化资源公司（煤矿 + 电厂 + 共享运力）的纯函数子状态：EXP-RESOURCE-1。
 *
 * <p><b>边界</b>：本类不持日志、不调度、不提交、不做权限投影，也不持有任何现金。世界权威现金
 * 只通过调用方每次显式传入的 {@link FundState} 进入，付款只走
 * {@link FundPayer#pay(String, BigDecimal, FundState)}；本类既没有第二份现金字段，也不缓存
 * 资金三态。每次命令都必须传入<b>当前</b>三态，传旧快照会让资金层记录与业务层对不上
 * （重放时由 {@code FUND_RECORD_DIVERGED} 暴露），因此调用方不得复用旧 {@code FundState}。</p>
 *
 * <p><b>口径</b>（见 {@code remaining-company-contract/v2/contract.md} 与 Root 复核六点）：</p>
 * <ul>
 *   <li>矿端煤与厂内燃料是两处<b>并存</b>的合并资产：转移只把原成本随量携带，未燃烧的厂内燃料
 *       不进 COGS；首版<b>不建在途科目</b>（在途恒为 0，运输只是运力约束，无单独现金成本）。</li>
 *   <li>储量是开局 EXP 物量禀赋，账面价值 0、只减不增（不确认采矿权、不做折耗）。</li>
 *   <li>电力不可储存：发电与对外交付是<b>同一个</b>动作，只燃烧本次真实交付量，没有电库存，
 *       也不存在“先发电、后等卖”。</li>
 *   <li>客户需求、客户预算、运力全部在 {@link Config} 里冻结；期内已发生的交付量、收入额与运力
 *       占用只增不减，收款、坏账、退出都不恢复它们。</li>
 *   <li>内部价只用于段统计：它改变煤段/电段利润，但合并现金、合并库存、合并权益与合并利润
 *       不变；期末未实现内部利润按 (内部价 − 厂内燃料原成本) × 未燃量精确抵销。</li>
 *   <li>开采与发电都是**两段正耗时作业**：{@code begin*} 冻结作业标识、数量、成本/转换率、客户、
 *       到期时点与产能/需求期间，付现成本进 WIP、预留储量/燃料/需求/预算/出力；{@code finish*}
 *       核作业身份与真实到期时间，之前不产生任何可卖煤、已交电量、收入或应收；同一时刻只允许
 *       一个在制作业（{@code ACTION_BUSY}），没有“即时入账 + 冷却”旁路。</li>
 * </ul>
 *
 * <p>金额 {@code BigDecimal}（金额 2 位、成本价 4 位，均 HALF_UP），数量 {@code long}；所有
 * 数量乘加都用 {@link BigInteger} 中间计算，超 {@code long} 一律 {@code QUANTITY_OVERFLOW} /
 * {@code MINE_CAPACITY} / {@code CAPACITY_OVERFLOW} 受控拒绝，不静默溢出。拒绝一律返回原状态
 * （不半写），付款失败时物量、需求、预算、运力、设备都不动。</p>
 */
public final class IntegratedResourcesModel {

    /** 一个日班（开采/发电的正耗时）。 */
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
    /** 主体已退出，不得再经营。 */
    public static final String CLOSED = "CLOSED";
    /** 当前已有正耗时动作在跑。 */
    public static final String ACTION_BUSY = "ACTION_BUSY";
    /** 数量必须为正。 */
    public static final String INVALID_QUANTITY = "INVALID_QUANTITY";
    /** 中间计算超出 long 范围，整笔拒绝。 */
    public static final String QUANTITY_OVERFLOW = "QUANTITY_OVERFLOW";
    /** 同一标识已被占用（不同动作）。 */
    public static final String ID_REUSED = "ID_REUSED";
    /** 作业标识非法。 */
    public static final String INVALID_JOB = "INVALID_JOB";
    /** 引用标识（如应收标识）非法。 */
    public static final String INVALID_ID = "INVALID_ID";
    /** 未知作业。 */
    public static final String UNKNOWN_JOB = "UNKNOWN_JOB";
    /** 作业种类与 finish 动作不符。 */
    public static final String JOB_KIND_MISMATCH = "JOB_KIND_MISMATCH";
    /** 作业状态不允许该动作（已完成后重复 finish、失败后重来）。 */
    public static final String JOB_STATE_INVALID = "JOB_STATE_INVALID";
    /** 作业未到期。 */
    public static final String JOB_NOT_DUE = "JOB_NOT_DUE";
    /** 作业所属的完成期间已关账，不能再回写。 */
    public static final String JOB_PERIOD_CLOSED = "JOB_PERIOD_CLOSED";
    /** 尚有在制作业，不能退出。 */
    public static final String JOB_IN_PROGRESS = "JOB_IN_PROGRESS";
    /** 有在制交付会把账记回待关闭期间，不能关期。 */
    public static final String PERIOD_HAS_RUNNING_DELIVERY = "PERIOD_HAS_RUNNING_DELIVERY";
    /** 同一 commandId 换了内容。 */
    public static final String COMMAND_CONTENT_CONFLICT = "COMMAND_CONTENT_CONFLICT";
    /** 资金层已有该标识的记录。 */
    public static final String FUND_ID_ALREADY_RECORDED = "FUND_ID_ALREADY_RECORDED";
    /** 业务已记成功但资金层缺成功记录。 */
    public static final String FUND_RECORD_DIVERGED = "FUND_RECORD_DIVERGED";
    /** 资金桶的真实主体与开局冻结主体不符。 */
    public static final String FUND_OWNER_MISMATCH = "FUND_OWNER_MISMATCH";
    /** 付款账户不存在或不属于开局冻结主体。 */
    public static final String UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
    /** 付款账户现金不足。 */
    public static final String INSUFFICIENT_CASH = "INSUFFICIENT_CASH";
    /** 本期未关账，不能进入下一个期间。 */
    public static final String PERIOD_NOT_CLOSED = "PERIOD_NOT_CLOSED";
    /** 该期间已关闭。 */
    public static final String PERIOD_CLOSED = "PERIOD_CLOSED";
    /** 期间尚未结束。 */
    public static final String PERIOD_NOT_ENDED = "PERIOD_NOT_ENDED";
    /** 关账必须按期连续。 */
    public static final String PERIOD_NOT_CONTINUOUS = "PERIOD_NOT_CONTINUOUS";
    /** 未知矿脉。 */
    public static final String UNKNOWN_SEAM = "UNKNOWN_SEAM";
    /** 超出该矿脉剩余储量。 */
    public static final String OVER_RESERVE = "OVER_RESERVE";
    /** 超出当期采矿能力。 */
    public static final String MINE_CAPACITY = "MINE_CAPACITY";
    /** 矿端煤不足。 */
    public static final String COAL_SHORT = "COAL_SHORT";
    /** 超出当期运力。 */
    public static final String TRANSPORT_CAPACITY = "TRANSPORT_CAPACITY";
    /** 超出客户当期冻结需求。 */
    public static final String DEMAND_EXCEEDED = "DEMAND_EXCEEDED";
    /** 超出客户当期冻结预算。 */
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    /** 厂内燃料不足。 */
    public static final String FUEL_SHORT = "FUEL_SHORT";
    /** 超出机组当期出力。 */
    public static final String GENERATOR_CAPACITY = "GENERATOR_CAPACITY";
    /** 未知应收。 */
    public static final String UNKNOWN_RECEIVABLE = "UNKNOWN_RECEIVABLE";
    /** 应收已结清或已核销。 */
    public static final String RECEIVABLE_CLOSED = "RECEIVABLE_CLOSED";
    /** 应收未到期。 */
    public static final String RECEIVABLE_NOT_DUE = "RECEIVABLE_NOT_DUE";
    /** 未逾期不能核销。 */
    public static final String BAD_DEBT_NOT_DUE = "BAD_DEBT_NOT_DUE";
    /** 对手方现金不足（付款失败，AR 原样保留）。 */
    public static final String CUSTOMER_CASH_SHORT = "CUSTOMER_CASH_SHORT";
    /** 缺批准引用。 */
    public static final String NO_APPROVAL = "NO_APPROVAL";
    /** 批准引用与冻结授权不符（种类/主体/时点）。 */
    public static final String APPROVAL_INVALID = "APPROVAL_INVALID";
    /** 合计产能超出 long。 */
    public static final String CAPACITY_OVERFLOW = "CAPACITY_OVERFLOW";
    /** 未知扩产项目。 */
    public static final String UNKNOWN_PROJECT = "UNKNOWN_PROJECT";
    /** 扩产项目状态不允许该动作。 */
    public static final String PROJECT_STATE_INVALID = "PROJECT_STATE_INVALID";
    /** 扩产未到期。 */
    public static final String PROJECT_NOT_DUE = "PROJECT_NOT_DUE";
    /** 尚有在建扩产，不能一次性退出。 */
    public static final String EXPANSION_IN_PROGRESS = "EXPANSION_IN_PROGRESS";
    /** 没有可处置的失败残值。 */
    public static final String NO_RESIDUAL = "NO_RESIDUAL";
    /** 残值买方现金不足。 */
    public static final String BUYER_CASH_SHORT = "BUYER_CASH_SHORT";
    /** 已退出。 */
    public static final String ALREADY_EXITED = "ALREADY_EXITED";

    /** 批准种类：资本开支。 */
    public static final String APPROVAL_CAPEX = "CAPEX";
    /** 批准种类：退出。 */
    public static final String APPROVAL_EXIT = "EXIT";
    /** 批准种类：商业化（资源公司预留，尚未使用）。 */
    public static final String APPROVAL_COMMERCIAL = "COMMERCIAL";

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal FOUR_ZERO = new BigDecimal("0.0000");
    private static final Set<String> APPROVAL_KINDS =
            Set.of(APPROVAL_CAPEX, APPROVAL_EXIT, APPROVAL_COMMERCIAL);

    private IntegratedResourcesModel() {
    }

    // ---------------------------------------------------------------- 冻结配置

    /**
     * 一条冻结的授权事实：程序只核“有这个授权、种类对、时点已到”，不凭文本改账。
     *
     * <p>纯模型不认识董事会/阅读资格本体；真实资格由后继 World 在绑定阶段解析，然后把
     * 已经成立的授权引用冻结进 {@link Config}。字符串本身不是资格证明。</p>
     *
     * @param approvalId 授权引用（命令里引用的就是它）
     * @param kind       种类（{@link #APPROVAL_CAPEX} / {@link #APPROVAL_EXIT} / {@link #APPROVAL_COMMERCIAL}）
     * @param approvedAt 批准时点（必须不晚于动作时点）
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
     * 一条 EXP 矿脉：开局物量禀赋与单位开采现金成本。
     *
     * <p>储量是物量禀赋，账面价值 0；开采时按本矿脉的单位成本付现，成本随煤进入矿端库存，
     * 因此不同矿脉会形成不同成本的煤批次（用于非整除分摊与内部抵销的精确性）。</p>
     *
     * @param seamId             矿脉标识
     * @param reserveKg          开局剩余储量（kg）
     * @param extractionCostPerKg 单位开采现金成本（≥0，4 位）
     */
    public record Seam(String seamId, long reserveKg, BigDecimal extractionCostPerKg) {

        /** 校验矿脉。 */
        public Seam {
            requireText(seamId, "矿脉标识");
            if (reserveKg < 0) {
                throw new IllegalArgumentException("储量不能为负");
            }
            extractionCostPerKg = cost(extractionCostPerKg);
            if (extractionCostPerKg.signum() < 0) {
                throw new IllegalArgumentException("开采成本不能为负");
            }
        }
    }

    /**
     * 冻结配置：主体、账户绑定、有限需求/预算/运力、价格与批准事实。
     *
     * <p>这些上限不能由调用方每次换：它们只在开局冻结一次，之后任何动作都读同一份配置。
     * 改变参数就是另一个实验实例。</p>
     *
     * @param companyOwnerId              公司主体（动作发起人必须是它）
     * @param companyAccountId            公司现金账户（唯一付款来源）
     * @param opsAccountId                运营收款账户（开采成本去向）
     * @param coalCustomerId              煤客户
     * @param coalCustomerAccountId       煤客户结算账户
     * @param gridOperatorId              电网
     * @param gridAccountId               电网结算账户
     * @param converterId                 转换成本收款方
     * @param converterAccountId          转换成本收款账户
     * @param contractorId                扩产承包方
     * @param contractorAccountId         扩产承包账户
     * @param disposalBuyerId             失败残值买方
     * @param disposalBuyerAccountId      失败残值买方账户（现金有限）
     * @param capitalSponsorId            外部注资方（EXP 资本注入）
     * @param capitalSponsorAccountId     注资方账户
     * @param openingEquipmentCost        开局设备原值（由初始资本形成）
     * @param internalPricePerKg          内部转移价（只进段统计）
     * @param coalPricePerKg              外销煤单价
     * @param powerPricePerMwh            上网电价
     * @param coalKgPerMwh                每 MWh 耗煤（kg，整数）
     * @param conversionCostPerMwh        单位转换现金成本
     * @param baseMineCapacityKgPerPeriod 基础采矿能力（kg/期）
     * @param generatorCapacityMwhPerPeriod 机组出力（MWh/期）
     * @param transportCapacityKgPerPeriod 共享运力（kg/期，外销煤与内部送煤共用）
     * @param coalCustomerDemandKgPerPeriod 客户当期冻结需求（kg）
     * @param coalCustomerBudgetPerPeriod   客户当期冻结预算
     * @param gridDemandMwhPerPeriod        电网当期冻结需求（MWh）
     * @param gridBudgetPerPeriod           电网当期冻结预算
     * @param fixedExpensePerPeriod         每期固定费用
     * @param depreciationPerPeriod         每期折旧
     * @param residualRate                  扩产失败残值率（0..1）
     * @param seams                         矿脉清单
     * @param approvals                     冻结授权事实（按引用索引）
     */
    public record Config(String companyOwnerId, String companyAccountId, String opsAccountId,
                         String coalCustomerId, String coalCustomerAccountId,
                         String gridOperatorId, String gridAccountId,
                         String converterId, String converterAccountId,
                         String contractorId, String contractorAccountId,
                         String disposalBuyerId, String disposalBuyerAccountId,
                         String capitalSponsorId, String capitalSponsorAccountId,
                         BigDecimal openingEquipmentCost,
                         BigDecimal internalPricePerKg, BigDecimal coalPricePerKg,
                         BigDecimal powerPricePerMwh, long coalKgPerMwh,
                         BigDecimal conversionCostPerMwh,
                         long baseMineCapacityKgPerPeriod, long generatorCapacityMwhPerPeriod,
                         long transportCapacityKgPerPeriod,
                         long coalCustomerDemandKgPerPeriod,
                         BigDecimal coalCustomerBudgetPerPeriod,
                         long gridDemandMwhPerPeriod, BigDecimal gridBudgetPerPeriod,
                         BigDecimal fixedExpensePerPeriod, BigDecimal depreciationPerPeriod,
                         BigDecimal residualRate, List<Seam> seams,
                         Map<String, Approval> approvals) {

        /** 校验主体、账户分工、有限上限、价格、残值率、矿脉与授权。 */
        public Config {
            requireText(companyOwnerId, "公司主体");
            requireText(companyAccountId, "公司账户");
            requireText(opsAccountId, "运营账户");
            requireText(coalCustomerId, "煤客户");
            requireText(coalCustomerAccountId, "煤客户账户");
            requireText(gridOperatorId, "电网");
            requireText(gridAccountId, "电网账户");
            requireText(converterId, "转换成本收款方");
            requireText(converterAccountId, "转换成本账户");
            requireText(contractorId, "承包方");
            requireText(contractorAccountId, "承包方账户");
            requireText(disposalBuyerId, "残值买方");
            requireText(disposalBuyerAccountId, "残值买方账户");
            requireText(capitalSponsorId, "注资方");
            requireText(capitalSponsorAccountId, "注资方账户");
            List<String> accounts = List.of(companyAccountId, opsAccountId, coalCustomerAccountId,
                    gridAccountId, converterAccountId, contractorAccountId,
                    disposalBuyerAccountId, capitalSponsorAccountId);
            if (new LinkedHashSet<>(accounts).size() != accounts.size()) {
                throw new IllegalArgumentException("账户角色不得共用（每笔现金腿两端必须不同）");
            }
            openingEquipmentCost = money(openingEquipmentCost);
            if (openingEquipmentCost.signum() < 0) {
                throw new IllegalArgumentException("开局设备原值不能为负");
            }
            internalPricePerKg = cost(internalPricePerKg);
            if (internalPricePerKg.signum() < 0) {
                throw new IllegalArgumentException("内部价不能为负");
            }
            coalPricePerKg = cost(coalPricePerKg);
            powerPricePerMwh = money(powerPricePerMwh);
            conversionCostPerMwh = money(conversionCostPerMwh);
            if (coalPricePerKg.signum() <= 0 || powerPricePerMwh.signum() <= 0) {
                throw new IllegalArgumentException("对外单价必须为正");
            }
            if (conversionCostPerMwh.signum() < 0) {
                throw new IllegalArgumentException("转换成本不能为负");
            }
            if (coalKgPerMwh <= 0) {
                throw new IllegalArgumentException("单位耗煤必须为正");
            }
            if (baseMineCapacityKgPerPeriod <= 0 || generatorCapacityMwhPerPeriod <= 0
                    || transportCapacityKgPerPeriod <= 0) {
                throw new IllegalArgumentException("能力与运力必须为正");
            }
            if (coalCustomerDemandKgPerPeriod < 0 || gridDemandMwhPerPeriod < 0) {
                throw new IllegalArgumentException("需求不能为负");
            }
            coalCustomerBudgetPerPeriod = money(coalCustomerBudgetPerPeriod);
            gridBudgetPerPeriod = money(gridBudgetPerPeriod);
            if (coalCustomerBudgetPerPeriod.signum() < 0 || gridBudgetPerPeriod.signum() < 0) {
                throw new IllegalArgumentException("预算不能为负");
            }
            fixedExpensePerPeriod = money(fixedExpensePerPeriod);
            depreciationPerPeriod = money(depreciationPerPeriod);
            if (fixedExpensePerPeriod.signum() < 0 || depreciationPerPeriod.signum() < 0) {
                throw new IllegalArgumentException("费用与折旧不能为负");
            }
            residualRate = cost(residualRate);
            if (residualRate.signum() < 0 || residualRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("残值率必须在 0 与 1 之间");
            }
            Objects.requireNonNull(seams, "矿脉清单");
            if (seams.isEmpty()) {
                throw new IllegalArgumentException("矿脉清单不能为空");
            }
            Set<String> seamIds = new LinkedHashSet<>();
            BigInteger totalReserve = BigInteger.ZERO;
            for (Seam seam : seams) {
                if (!seamIds.add(seam.seamId())) {
                    throw new IllegalArgumentException("矿脉标识重复：" + seam.seamId());
                }
                totalReserve = totalReserve.add(BigInteger.valueOf(seam.reserveKg()));
            }
            if (totalReserve.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException("储量合计超出 long");
            }
            seams = List.copyOf(seams);
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

    /** 扩产项目状态。 */
    public enum ExpansionStatus {
        /** 在建（CIP）。 */
        BUILDING,
        /** 已上线（设备，按 ramp 生效）。 */
        ONLINE,
        /** 已失败（残值资产）。 */
        FAILED
    }

    /**
     * 扩产项目。
     *
     * @param projectId              项目标识
     * @param capex                  已付资本开支（CIP→设备原值）
     * @param startedAt              开工时点
     * @param completesAt            应完工时点（时滞）
     * @param rampPeriods            爬坡期数
     * @param addCapacityKgPerPeriod 新增采矿能力（kg/期）
     * @param status                 状态
     * @param onlinePeriod           上线期间；未上线为 -1
     * @param residual               失败残值账面值
     */
    public record Expansion(String projectId, BigDecimal capex, long startedAt, long completesAt,
                            int rampPeriods, long addCapacityKgPerPeriod,
                            ExpansionStatus status, long onlinePeriod, BigDecimal residual) {

        /** 校验项目字段。 */
        public Expansion {
            requireText(projectId, "扩产项目");
            capex = money(capex);
            Objects.requireNonNull(status, "扩产状态");
            residual = money(residual);
            if (capex.signum() <= 0 || completesAt <= startedAt || rampPeriods <= 0
                    || addCapacityKgPerPeriod <= 0) {
                throw new IllegalArgumentException("扩产参数非法");
            }
            if (residual.signum() < 0) {
                throw new IllegalArgumentException("残值不能为负");
            }
        }
    }

    /** 应收状态。 */
    public enum ReceivableStatus {
        /** 未收。 */
        OPEN,
        /** 已收。 */
        PAID,
        /** 已核销（坏账）。 */
        WRITTEN_OFF
    }

    /**
     * 一条交付形成的应收：交付时确认收入与成本，收款只冲本应收。
     *
     * @param receivableId 应收标识
     * @param customerId   客户
     * @param basis        计量口径（{@code COAL_KG} / {@code POWER_MWH}）
     * @param units        交付数量（按 basis 计）
     * @param amount       确认收入
     * @param cost         确认成本
     * @param recognizedAt 确认时点
     * @param dueAt        到期时点
     * @param paid         已收金额
     * @param status       状态
     */
    public record Receivable(String receivableId, String customerId, String basis, long units,
                             BigDecimal amount, BigDecimal cost, long recognizedAt, long dueAt,
                             BigDecimal paid, ReceivableStatus status) {

        /** 校验应收。 */
        public Receivable {
            requireText(receivableId, "应收标识");
            requireText(customerId, "客户");
            requireText(basis, "计量口径");
            if (units <= 0) {
                throw new IllegalArgumentException("应收数量必须为正");
            }
            amount = money(amount);
            cost = money(cost);
            paid = money(paid);
            Objects.requireNonNull(status, "应收状态");
            if (amount.signum() < 0 || cost.signum() < 0 || paid.signum() < 0
                    || paid.compareTo(amount) > 0) {
                throw new IllegalArgumentException("应收金额非法");
            }
        }

        /** @return 未收金额 */
        public BigDecimal outstanding() {
            return money(amount.subtract(paid));
        }
    }

    /**
     * 一个期间内只增不减的累计量：需求、预算、运力、产能占用与在制作业的预留。
     *
     * <p>产能（采矿/出力）按**开始期间**占用；交付需求与预算按**完成期间**预留，
     * 完成时把预留转为已交付。开始期间与完成期间都在 begin 时冻结进作业，跨期不会漂移。</p>
     *
     * @param extractedKg          本期已开工占用的采矿能力（kg）
     * @param coalDeliveredKg      本期外销煤量
     * @param coalRevenue          本期外销收入
     * @param powerDeliveredMwh    本期已交电量
     * @param powerRevenue         本期售电收入
     * @param powerReservedMwh     本期为在制作业预留的交付需求（MWh）
     * @param powerReservedRevenue 本期为在制作业预留的预算
     * @param powerCapacityUsedMwh 本期已开工占用的机组出力（MWh）
     * @param transportUsedKg      本期已占运力
     */
    public record PeriodUse(long extractedKg, long coalDeliveredKg, BigDecimal coalRevenue,
                            long powerDeliveredMwh, BigDecimal powerRevenue,
                            long powerReservedMwh, BigDecimal powerReservedRevenue,
                            long powerCapacityUsedMwh, long transportUsedKg) {

        /** 校验非负。 */
        public PeriodUse {
            if (extractedKg < 0 || coalDeliveredKg < 0 || powerDeliveredMwh < 0
                    || powerReservedMwh < 0 || powerCapacityUsedMwh < 0
                    || transportUsedKg < 0) {
                throw new IllegalArgumentException("期间累计量不能为负");
            }
            coalRevenue = money(coalRevenue);
            powerRevenue = money(powerRevenue);
            powerReservedRevenue = money(powerReservedRevenue);
            if (coalRevenue.signum() < 0 || powerRevenue.signum() < 0
                    || powerReservedRevenue.signum() < 0) {
                throw new IllegalArgumentException("期间收入不能为负");
            }
        }

        /** 空期间。 */
        public static PeriodUse empty() {
            return new PeriodUse(0L, 0L, ZERO, 0L, ZERO, 0L, ZERO, 0L, 0L);
        }

        /**
         * 累加本期开采量（begin 时占用产能）。
         *
         * @param kg 增量（正）
         * @return 新累计
         */
        public PeriodUse plusExtracted(long kg) {
            return new PeriodUse(Math.addExact(extractedKg, kg), coalDeliveredKg, coalRevenue,
                    powerDeliveredMwh, powerRevenue, powerReservedMwh, powerReservedRevenue,
                    powerCapacityUsedMwh, transportUsedKg);
        }

        /**
         * 累加本期外销交付（同时占运力）。
         *
         * @param kg        增量
         * @param revenue   本次收入
         * @param transport 本次运力占用
         * @return 新累计
         */
        public PeriodUse plusCoal(long kg, BigDecimal revenue, long transport) {
            return new PeriodUse(extractedKg, Math.addExact(coalDeliveredKg, kg),
                    money(coalRevenue.add(revenue)), powerDeliveredMwh, powerRevenue,
                    powerReservedMwh, powerReservedRevenue, powerCapacityUsedMwh,
                    Math.addExact(transportUsedKg, transport));
        }

        /**
         * 累加本期运力占用。
         *
         * @param kg 增量
         * @return 新累计
         */
        public PeriodUse plusTransport(long kg) {
            return new PeriodUse(extractedKg, coalDeliveredKg, coalRevenue,
                    powerDeliveredMwh, powerRevenue, powerReservedMwh, powerReservedRevenue,
                    powerCapacityUsedMwh, Math.addExact(transportUsedKg, kg));
        }

        /**
         * 占本期机组出力（begin 时）。
         *
         * @param mwh 增量
         * @return 新累计
         */
        public PeriodUse plusPowerCapacity(long mwh) {
            return new PeriodUse(extractedKg, coalDeliveredKg, coalRevenue,
                    powerDeliveredMwh, powerRevenue, powerReservedMwh, powerReservedRevenue,
                    Math.addExact(powerCapacityUsedMwh, mwh), transportUsedKg);
        }

        /**
         * 为在制作业预留本期交付需求与预算。
         *
         * @param mwh    预留电量
         * @param budget 预留预算（= 电量 × 冻结电价）
         * @return 新累计
         */
        public PeriodUse reservePower(long mwh, BigDecimal budget) {
            return new PeriodUse(extractedKg, coalDeliveredKg, coalRevenue,
                    powerDeliveredMwh, powerRevenue, Math.addExact(powerReservedMwh, mwh),
                    money(powerReservedRevenue.add(budget)), powerCapacityUsedMwh,
                    transportUsedKg);
        }

        /**
         * 释放预留（作业失败）；只释放未交付的预留，已交付量不回退。
         *
         * @param mwh    释放电量
         * @param budget 释放预算
         * @return 新累计
         */
        public PeriodUse releasePower(long mwh, BigDecimal budget) {
            return new PeriodUse(extractedKg, coalDeliveredKg, coalRevenue,
                    powerDeliveredMwh, powerRevenue,
                    Math.subtractExact(powerReservedMwh, mwh),
                    money(powerReservedRevenue.subtract(budget)), powerCapacityUsedMwh,
                    transportUsedKg);
        }

        /**
         * 完成交付：预留转已交付并确认本期收入。
         *
         * @param mwh     交付电量
         * @param revenue 本次收入（同时等于释放的预算）
         * @return 新累计
         */
        public PeriodUse deliverPower(long mwh, BigDecimal revenue) {
            return new PeriodUse(extractedKg, coalDeliveredKg, coalRevenue,
                    Math.addExact(powerDeliveredMwh, mwh), money(powerRevenue.add(revenue)),
                    Math.subtractExact(powerReservedMwh, mwh),
                    money(powerReservedRevenue.subtract(revenue)), powerCapacityUsedMwh,
                    transportUsedKg);
        }

        /**
         * 本期已承诺电量（已交付 + 在制预留）。
         *
         * @return MWh
         */
        public long committedPowerMwh() {
            return Math.addExact(powerDeliveredMwh, powerReservedMwh);
        }
    }

    /**
     * 累计口径（跨期）：段统计与合并损益的拆分依据。
     *
     * @param coalRevenue     外销煤累计收入
     * @param coalCogs        外销煤累计成本
     * @param transferRevenue 内部转移累计内部收入
     * @param transferCost    内部转移累计原成本
     * @param powerRevenue    售电累计收入
     * @param burnedFuelCost  已燃烧厂内燃料累计原成本
     * @param conversionCost  转换累计现金成本
     * @param fixedExpense    累计期间费用
     * @param transferredCoalKg 累计内部送煤量（用于派生已燃烧量）
     */
    public record Totals(BigDecimal coalRevenue, BigDecimal coalCogs, BigDecimal transferRevenue,
                         BigDecimal transferCost, BigDecimal powerRevenue,
                         BigDecimal burnedFuelCost, BigDecimal conversionCost,
                         BigDecimal fixedExpense, long transferredCoalKg) {

        /** 校验非负。 */
        public Totals {
            coalRevenue = money(coalRevenue);
            coalCogs = money(coalCogs);
            transferRevenue = money(transferRevenue);
            transferCost = money(transferCost);
            powerRevenue = money(powerRevenue);
            burnedFuelCost = money(burnedFuelCost);
            conversionCost = money(conversionCost);
            fixedExpense = money(fixedExpense);
            for (BigDecimal value : List.of(coalRevenue, coalCogs, transferRevenue, transferCost,
                    powerRevenue, burnedFuelCost, conversionCost, fixedExpense)) {
                if (value.signum() < 0) {
                    throw new IllegalArgumentException("累计口径不能为负");
                }
            }
            if (transferredCoalKg < 0) {
                throw new IllegalArgumentException("累计送煤量不能为负");
            }
        }

        /** 空累计。 */
        public static Totals empty() {
            return new Totals(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, 0L);
        }
    }

    /**
     * 期间费用账单。
     *
     * @param period 期间
     * @param amount 应计金额
     * @param paid   已付金额
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

    /** 作业种类。 */
    public enum JobKind {
        /** 开采：begin 占储量与采矿能力，finish 产出矿端煤。 */
        EXTRACT,
        /** 发电并交付：begin 占燃料/出力/交付需求，finish 确认电量与收入。 */
        POWER
    }

    /** 作业状态：只有一个真实终态，失败不可重来。 */
    public enum JobStatus {
        /** 在制（WIP 资产）。 */
        RUNNING,
        /** 已完成（资产已转出）。 */
        DONE,
        /** 真实失败终态（成本计损失，残留资源按种类退回或报废）。 */
        FAILED
    }

    /**
     * 在制作业：begin 时冻结全部参数与期间归属，finish 只按作业身份核销。
     *
     * <p>作业里冻结的是**事实**而不是承诺：开始/完成期间、到期时点、数量、成本、转换成本、
     * 燃料量与原成本、客户、预留收入、应收标识。finish 不接受任何可篡改的业务参数，
     * 因此“提前完成”“换数量”“换客户”在类型上就不可能通过。</p>
     *
     * @param jobId            作业标识（begin 分配，finish/失败只能引用它）
     * @param kind             作业种类
     * @param seamId           矿脉（POWER 为空串）
     * @param quantityKg       开采量（POWER 为 0）
     * @param mwh              交付电量（EXTRACT 为 0）
     * @param fuelKg           已预留并移出燃料库存的燃料量（EXTRACT 为 0）
     * @param fuelCost         预留燃料的原成本（EXTRACT 为 0）
     * @param paidCost         begin 实付现金（开采成本 / 转换成本），即在制余额
     * @param customerId       交付客户（POWER 为电网；EXTRACT 为空串）
     * @param referenceId      交付时使用的应收标识（EXTRACT 为空串）
     * @param beganAt          开始时点
     * @param dueAt            到期时点（正耗时）
     * @param capacityPeriod   占用产能的期间（开始期间，冻结）
     * @param deliveryPeriod   交付需求/预算归属期间（完成期间，冻结；EXTRACT 为 -1）
     * @param revenueReserved  预留的交付收入（= 电量 × 冻结电价；EXTRACT 为 0）
     * @param status           状态
     * @param closedAt         完成/失败时点；在制为 -1
     */
    public record Job(String jobId, JobKind kind, String seamId, long quantityKg, long mwh,
                      long fuelKg, BigDecimal fuelCost, BigDecimal paidCost, String customerId,
                      String referenceId, long beganAt, long dueAt, long capacityPeriod,
                      long deliveryPeriod, BigDecimal revenueReserved, JobStatus status,
                      long closedAt) {

        /** 校验作业字段与期间归属。 */
        public Job {
            requireText(jobId, "作业标识");
            Objects.requireNonNull(kind, "作业种类");
            Objects.requireNonNull(status, "作业状态");
            if (seamId == null) {
                seamId = "";
            }
            if (customerId == null) {
                customerId = "";
            }
            if (referenceId == null) {
                referenceId = "";
            }
            fuelCost = money(fuelCost);
            paidCost = money(paidCost);
            revenueReserved = money(revenueReserved);
            if (dueAt <= beganAt) {
                throw new IllegalArgumentException("作业必须正耗时");
            }
            if (capacityPeriod < 0) {
                throw new IllegalArgumentException("作业产能期间不能为负");
            }
            if (quantityKg < 0 || mwh < 0 || fuelKg < 0) {
                throw new IllegalArgumentException("作业数量不能为负");
            }
            if (fuelCost.signum() < 0 || paidCost.signum() < 0
                    || revenueReserved.signum() < 0) {
                throw new IllegalArgumentException("作业金额不能为负");
            }
            if (kind == JobKind.EXTRACT && quantityKg <= 0) {
                throw new IllegalArgumentException("开采作业数量必须为正");
            }
            if (kind == JobKind.POWER && (mwh <= 0 || fuelKg <= 0 || deliveryPeriod < 0)) {
                throw new IllegalArgumentException("发电作业参数非法");
            }
        }

        /**
         * 在制余额：已付现金 + 预留燃料原成本。
         *
         * @return 账面金额（非在制时为 0）
         */
        public BigDecimal workInProgress() {
            return status == JobStatus.RUNNING ? money(paidCost.add(fuelCost)) : ZERO;
        }
    }

    /**
     * 不可变状态（不含任何现金字段）。
     *
     * @param config                冻结配置
     * @param openingOwners         开局冻结的账户→业务主体
     * @param initialEquity         初始权益（现金 + 开局设备）
     * @param initialWorldCash      开局世界现金
     * @param watermark             已见时间轴
     * @param jobs                  在制/已完成/已失败作业（begin 冻结）
     * @param jobLoss               失败作业的损失累计
     * @param mineCoal              矿端煤（数量 + 原成本）
     * @param plantFuel             厂内燃料（数量 + 原成本；未燃仍为合并资产）
     * @param seamRemaining         矿脉剩余储量（只减不增）
     * @param periodUse             期间累计量
     * @param totals                累计口径
     * @param receivables           应收
     * @param expansions            扩产
     * @param residualAssets        失败残值（项目 → 账面值）
     * @param bills                 期间费用
     * @param closedPeriods         已连续关账期间数
     * @param accumulatedDepreciation 累计折旧
     * @param retiredEquipment    已退出设备净值（从资产中移出，避免与退出损失重复）
     * @param capitalContributions  外部资本注入累计
     * @param exited                是否已退出
     * @param exitLoss              退出损失累计
     * @param disposalResult        处置损益（正为收益）
     * @param badDebtLoss           坏账损失
     * @param commandContents       命令内容（幂等）
     * @param commandReplies        命令回报（幂等）
     * @param usedIds               已用标识
     */
    public record State(Config config, Map<String, String> openingOwners, BigDecimal initialEquity,
                        BigDecimal initialWorldCash, long watermark, Map<String, Job> jobs,
                        BigDecimal jobLoss,
                        Inventory mineCoal, Inventory plantFuel, Map<String, Long> seamRemaining,
                        Map<Long, PeriodUse> periodUse, Totals totals,
                        Map<String, Receivable> receivables, Map<String, Expansion> expansions,
                        Map<String, BigDecimal> residualAssets, Map<Long, ExpenseBill> bills,
                        long closedPeriods, BigDecimal accumulatedDepreciation,
                        BigDecimal retiredEquipment, BigDecimal capitalContributions,
                        boolean exited, BigDecimal exitLoss,
                        BigDecimal disposalResult, BigDecimal badDebtLoss,
                        Map<String, String> commandContents, Map<String, Reply> commandReplies,
                        Set<String> usedIds) {

        /** 防御性复制与基础校验。 */
        public State {
            Objects.requireNonNull(config, "配置");
            openingOwners = Map.copyOf(Objects.requireNonNull(openingOwners, "开局主体映射"));
            initialEquity = money(initialEquity);
            initialWorldCash = money(initialWorldCash);
            Objects.requireNonNull(mineCoal, "矿端煤");
            Objects.requireNonNull(plantFuel, "厂内燃料");
            jobs = Map.copyOf(Objects.requireNonNull(jobs, "作业"));
            jobLoss = money(jobLoss);
            seamRemaining = Map.copyOf(seamRemaining);
            periodUse = Map.copyOf(periodUse);
            Objects.requireNonNull(totals, "累计口径");
            receivables = Map.copyOf(receivables);
            expansions = Map.copyOf(expansions);
            residualAssets = Map.copyOf(residualAssets);
            bills = Map.copyOf(bills);
            accumulatedDepreciation = money(accumulatedDepreciation);
            retiredEquipment = money(retiredEquipment);
            capitalContributions = money(capitalContributions);
            exitLoss = money(exitLoss);
            disposalResult = money(disposalResult);
            badDebtLoss = money(badDebtLoss);
            commandContents = Map.copyOf(commandContents);
            commandReplies = Map.copyOf(commandReplies);
            usedIds = Set.copyOf(usedIds);
            if (watermark < 0 || closedPeriods < 0) {
                throw new IllegalArgumentException("时间轴或关账数不能为负");
            }
            if (jobLoss.signum() < 0) {
                throw new IllegalArgumentException("作业损失不能为负");
            }
            if (retiredEquipment.signum() < 0) {
                throw new IllegalArgumentException("已退出设备净值不能为负");
            }
        }

        /**
         * 开局冻结的账户主体。
         *
         * @param accountId 账户
         * @return 业务主体；未知返回 null
         */
        public String openingOwnerOf(String accountId) {
            return accountId == null ? null : openingOwners.get(accountId);
        }

        /**
         * 某期间累计量。
         *
         * @param period 期间
         * @return 累计量；无记录返回空
         */
        public PeriodUse useOf(long period) {
            return periodUse.getOrDefault(period, PeriodUse.empty());
        }

        /**
         * 某矿脉剩余储量。
         *
         * @param seamId 矿脉
         * @return 剩余 kg；未知返回 0
         */
        public long reserveOf(String seamId) {
            return seamRemaining.getOrDefault(seamId, 0L);
        }

        /**
         * 某作业。
         *
         * @param jobId 作业标识
         * @return 作业；未知返回 null
         */
        public Job jobOf(String jobId) {
            return jobId == null ? null : jobs.get(jobId);
        }

        /**
         * 当前在制作业（任意一个）；没有返回 null。
         *
         * @return 在制作业
         */
        public Job runningJob() {
            for (Job job : jobs.values()) {
                if (job.status() == JobStatus.RUNNING) {
                    return job;
                }
            }
            return null;
        }

        /**
         * 在制资产合计（在制作业的已付现金 + 预留燃料原成本）。
         *
         * @return WIP 金额
         */
        public BigDecimal workInProgress() {
            BigDecimal total = ZERO;
            for (Job job : jobs.values()) {
                total = total.add(job.workInProgress());
            }
            return money(total);
        }

        /**
         * 设备原值：开局设备 + 已上线扩产。
         *
         * @return 原值
         */
        public BigDecimal equipmentOriginal() {
            BigDecimal original = config.openingEquipmentCost();
            for (Expansion expansion : expansions.values()) {
                if (expansion.status() == ExpansionStatus.ONLINE) {
                    original = original.add(expansion.capex());
                }
            }
            return money(original);
        }

        /**
         * 设备净值：原值 − 累计折旧 − 已退出净值。
         *
         * @return 净值（不小于 0）
         */
        public BigDecimal equipmentNet() {
            BigDecimal net = equipmentOriginal().subtract(accumulatedDepreciation)
                    .subtract(retiredEquipment);
            return money(net.signum() < 0 ? ZERO : net);
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
     * @param funds    候选资金三态（拒绝且未付款时与入参相同）
     */
    public record Outcome(boolean accepted, String reason, State state, FundState funds) {
    }

    /**
     * 段报表：内部价只影响这里，不影响合并。
     *
     * @param coalSegmentProfit       煤段利润（含内部转移收入与原成本）
     * @param powerSegmentProfit      电段利润（燃料按内部价计成本）
     * @param unrealizedInternalProfit 期末未燃厂内燃料的内部未实现利润
     * @param consolidatedGrossProfit 合并毛利（段合计 − 未实现）
     */
    public record SegmentReport(BigDecimal coalSegmentProfit, BigDecimal powerSegmentProfit,
                                BigDecimal unrealizedInternalProfit,
                                BigDecimal consolidatedGrossProfit) {
    }

    /**
     * 报表：A=L+E 与分段/合并双口径。
     *
     * @param companyCash     公司现金（来自资金三态）
     * @param mineCoal        矿端煤账面
     * @param mineCoalKg      矿端煤数量
     * @param plantFuel       厂内燃料账面
     * @param plantFuelKg     厂内燃料数量
     * @param receivables     应收未收
     * @param equipmentNet    设备净值
     * @param cip             在建工程
     * @param residual        失败残值
     * @param wip             在制作业资产（已付现金 + 预留燃料原成本）
     * @param payables        应付未付
     * @param assets          资产合计
     * @param liabilities     负债合计
     * @param equity          权益
     * @param revenue         累计对外收入
     * @param cogs            累计合并成本
     * @param fixedExpense    累计期间费用
     * @param depreciation    累计折旧
     * @param exitLoss        退出损失
     * @param jobLoss         失败作业损失
     * @param disposalResult  处置损益
     * @param badDebtLoss     坏账损失
     * @param coalDeliveredKg 累计外销煤量（跨期合计）
     * @param powerDeliveredMwh 累计售电量（跨期合计）
     * @param transportUsedKg 累计运力占用（跨期合计）
     * @param reserveKg       剩余储量合计
     * @param openJobs        在制作业数
     * @param balanced        A=L+E 且世界现金守恒
     * @param worldCash       世界现金
     * @param segments        段报表
     */
    public record Report(BigDecimal companyCash, BigDecimal mineCoal, long mineCoalKg,
                         BigDecimal plantFuel, long plantFuelKg, BigDecimal receivables,
                         BigDecimal equipmentNet, BigDecimal cip, BigDecimal residual,
                         BigDecimal wip, BigDecimal payables, BigDecimal assets,
                         BigDecimal liabilities, BigDecimal equity, BigDecimal revenue,
                         BigDecimal cogs, BigDecimal fixedExpense, BigDecimal depreciation,
                         BigDecimal exitLoss, BigDecimal jobLoss, BigDecimal disposalResult,
                         BigDecimal badDebtLoss, long coalDeliveredKg, long powerDeliveredMwh,
                         long transportUsedKg, long reserveKg, long openJobs, boolean balanced,
                         BigDecimal worldCash, SegmentReport segments) {
    }

    // ---------------------------------------------------------------- 开局

    /**
     * 建立开局状态：初始权益 = 公司现金 + 开局设备原值；储量只是物量禀赋（账面 0）。
     *
     * @param config 冻结配置
     * @param funds  开局资金三态（所有角色账户必须在场且带主体）
     * @return 初始状态
     */
    public static State open(Config config, FundState funds) {
        Objects.requireNonNull(config, "配置");
        Objects.requireNonNull(funds, "资金三态");
        Map<String, String> owners = new LinkedHashMap<>();
        Set<String> accounts = new LinkedHashSet<>(List.of(config.companyAccountId(),
                config.opsAccountId(), config.coalCustomerAccountId(), config.gridAccountId(),
                config.converterAccountId(), config.contractorAccountId(),
                config.disposalBuyerAccountId(), config.capitalSponsorAccountId()));
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
        Map<String, Long> remaining = new LinkedHashMap<>();
        for (Seam seam : config.seams()) {
            remaining.put(seam.seamId(), seam.reserveKg());
        }
        BigDecimal cash = funds.cash(config.companyAccountId());
        BigDecimal equity = money(cash.add(config.openingEquipmentCost()));
        return new State(config, owners, equity, worldCash(funds), 0L, Map.of(), ZERO,
                new Inventory(0L, ZERO), new Inventory(0L, ZERO), remaining, Map.of(),
                Totals.empty(), Map.of(), Map.of(), Map.of(), Map.of(), 0L, ZERO, ZERO, ZERO,
                false, ZERO, ZERO, ZERO, Map.of(), Map.of(), Set.of());
    }

    // ---------------------------------------------------------------- 开采作业

    /**
     * 开采开工（正耗时 1 个日班）：冻结作业身份与全部参数，预付开采成本形成 WIP，
     * 同时占用矿脉储量与**开始期间**的采矿能力；此时不产生任何可卖煤。
     *
     * @param state      状态
     * @param now        当前时点
     * @param actor      发起人（必须是公司主体）
     * @param commandId  命令标识（幂等键）
     * @param jobId      作业标识（finish 只能引用它）
     * @param seamId     矿脉
     * @param quantityKg 数量（kg）
     * @param funds      当前资金三态
     * @param payer      资金腿（公司 → 运营）
     * @return 候选
     */
    public static Outcome beginExtraction(State state, long now, String actor, String commandId,
                                          String jobId, String seamId, long quantityKg,
                                          FundState funds, FundPayer payer) {
        String content = "EXTRACT_BEGIN|" + jobId + "|" + seamId + "|" + quantityKg;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        String deny = guardOperating(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        if (jobId == null || jobId.isBlank()) {
            return reject(state, INVALID_JOB, funds);
        }
        if (quantityKg <= 0) {
            return reject(state, INVALID_QUANTITY, funds);
        }
        Seam seam = seam(state.config(), seamId);
        if (seam == null) {
            return reject(state, UNKNOWN_SEAM, funds);
        }
        if (state.jobs().containsKey(jobId) || state.usedIds().contains(jobId)) {
            return reject(state, ID_REUSED, funds);
        }
        if (state.runningJob() != null) {
            return reject(state, ACTION_BUSY, funds);
        }
        if (quantityKg > state.reserveOf(seamId)) {
            return reject(state, OVER_RESERVE, funds);
        }
        long period = now / PERIOD_MILLIS;
        BigInteger wanted = BigInteger.valueOf(state.useOf(period).extractedKg())
                .add(BigInteger.valueOf(quantityKg));
        if (wanted.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return reject(state, QUANTITY_OVERFLOW, funds);
        }
        if (wanted.compareTo(capacityAt(state, period)) > 0) {
            return reject(state, MINE_CAPACITY, funds);
        }
        BigDecimal cost = money(seam.extractionCostPerKg()
                .multiply(BigDecimal.valueOf(quantityKg)));
        Paid paid = pay(payer, commandId, state.config().companyAccountId(),
                state.config().opsAccountId(), cost, funds, state);
        if (!paid.accepted()) {
            return new Outcome(false, paid.reason(), state, paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.jobs.put(jobId, new Job(jobId, JobKind.EXTRACT, seamId, quantityKg, 0L, 0L, ZERO,
                cost, "", "", now, now + DAY_MILLIS, period, -1L, ZERO, JobStatus.RUNNING,
                -1L));
        draft.seamRemaining.put(seamId, state.reserveOf(seamId) - quantityKg);
        draft.periodUse.put(period, draft.use(period).plusExtracted(quantityKg));
        return accept(draft, "EXTRACTION_BEGUN", paid, commandId, content);
    }

    /**
     * 开采完成：只能按 begin 登记的作业身份核销；到期前拒绝且账不变；完成只发生一次。
     *
     * <p>WIP（已付开采成本）转成矿端煤的原成本，数量同时进入矿端库存；此前矿端煤为 0，
     * 因此 finished 之前无法外销、也无收入可确认。</p>
     *
     * @param state     状态
     * @param now       当前时点
     * @param actor     发起人
     * @param commandId 命令标识
     * @param jobId     作业标识
     * @param funds     当前资金三态（finish 不动钱）
     * @return 候选
     */
    public static Outcome finishExtraction(State state, long now, String actor, String commandId,
                                           String jobId, FundState funds) {
        String content = "EXTRACT_FINISH|" + jobId;
        Outcome replay = replay(state, commandId, content, funds, null);
        if (replay != null) {
            return replay;
        }
        String deny = guardOperating(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        Job job = state.jobOf(jobId);
        if (job == null) {
            return reject(state, UNKNOWN_JOB, funds);
        }
        if (job.kind() != JobKind.EXTRACT) {
            return reject(state, JOB_KIND_MISMATCH, funds);
        }
        if (job.status() != JobStatus.RUNNING) {
            return reject(state, JOB_STATE_INVALID, funds);
        }
        if (now < job.dueAt()) {
            return reject(state, JOB_NOT_DUE, funds);
        }
        Draft draft = new Draft(state, now);
        draft.jobs.put(jobId, new Job(job.jobId(), job.kind(), job.seamId(), job.quantityKg(),
                job.mwh(), job.fuelKg(), job.fuelCost(), job.paidCost(), job.customerId(),
                job.referenceId(), job.beganAt(), job.dueAt(), job.capacityPeriod(),
                job.deliveryPeriod(), job.revenueReserved(), JobStatus.DONE, now));
        draft.mineCoal = new Inventory(state.mineCoal().quantity() + job.quantityKg(),
                money(state.mineCoal().amount().add(job.paidCost())));
        return accept(draft, "EXTRACTED", Paid.noop(funds), commandId, content);
    }

    // ---------------------------------------------------------------- 外销煤

    /**
     * 外销煤：交付即确认收入/成本/应收，不动现金；占客户当期需求、预算与共享运力。
     *
     * @param state        状态
     * @param now          当前时点
     * @param actor        发起人
     * @param commandId    命令标识
     * @param deliveryId   交付标识
     * @param quantityKg   数量
     * @param receivableId 应收标识
     * @param funds        当前资金三态（本动作不动钱）
     * @return 候选
     */
    public static Outcome sellCoal(State state, long now, String actor, String commandId,
                                   String deliveryId, long quantityKg, String receivableId,
                                   FundState funds) {
        String content = "SELL_COAL|" + deliveryId + "|" + quantityKg + "|" + receivableId;
        Outcome replay = replay(state, commandId, content, funds, null);
        if (replay != null) {
            return replay;
        }
        String deny = guardOperating(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        if (quantityKg <= 0) {
            return reject(state, INVALID_QUANTITY, funds);
        }
        if (quantityKg > state.mineCoal().quantity()) {
            return reject(state, COAL_SHORT, funds);
        }
        long period = now / PERIOD_MILLIS;
        PeriodUse use = state.useOf(period);
        BigInteger transported = BigInteger.valueOf(use.transportUsedKg())
                .add(BigInteger.valueOf(quantityKg));
        if (transported.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return reject(state, QUANTITY_OVERFLOW, funds);
        }
        if (transported.compareTo(BigInteger.valueOf(
                state.config().transportCapacityKgPerPeriod())) > 0) {
            return reject(state, TRANSPORT_CAPACITY, funds);
        }
        BigInteger demanded = BigInteger.valueOf(use.coalDeliveredKg())
                .add(BigInteger.valueOf(quantityKg));
        if (demanded.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return reject(state, QUANTITY_OVERFLOW, funds);
        }
        if (demanded.compareTo(BigInteger.valueOf(
                state.config().coalCustomerDemandKgPerPeriod())) > 0) {
            return reject(state, DEMAND_EXCEEDED, funds);
        }
        BigDecimal amount = money(state.config().coalPricePerKg()
                .multiply(BigDecimal.valueOf(quantityKg)));
        if (use.coalRevenue().add(amount)
                .compareTo(state.config().coalCustomerBudgetPerPeriod()) > 0) {
            return reject(state, BUDGET_EXCEEDED, funds);
        }
        if (state.usedIds().contains(receivableId)) {
            return reject(state, ID_REUSED, funds);
        }
        BigDecimal cost = ManufacturingModel.allocate(state.mineCoal(), quantityKg);
        Draft draft = new Draft(state, now);
        draft.mineCoal = new Inventory(state.mineCoal().quantity() - quantityKg,
                state.mineCoal().amount().subtract(cost));
        draft.periodUse.put(period, use.plusCoal(quantityKg, amount, quantityKg));
        draft.receivables.put(receivableId, new Receivable(receivableId,
                state.config().coalCustomerId(), "COAL_KG", quantityKg, amount, cost, now,
                now + 15L * DAY_MILLIS, ZERO, ReceivableStatus.OPEN));
        draft.usedIds.add(receivableId);
        Totals totals = state.totals();
        draft.totals = new Totals(totals.coalRevenue().add(amount),
                totals.coalCogs().add(cost), totals.transferRevenue(), totals.transferCost(),
                totals.powerRevenue(), totals.burnedFuelCost(), totals.conversionCost(),
                totals.fixedExpense(), totals.transferredCoalKg());
        return accept(draft, "COAL_DELIVERED", Paid.noop(funds), commandId, content);
    }

    // ---------------------------------------------------------------- 内部送煤

    /**
     * 内部送煤：矿端 → 厂内燃料，原成本随量携带；无收入、无现金，只占运力。
     *
     * @param state      状态
     * @param now        当前时点
     * @param actor      发起人
     * @param commandId  命令标识
     * @param transferId 转移标识
     * @param quantityKg 数量
     * @param funds      当前资金三态（不动钱）
     * @return 候选
     */
    public static Outcome transferToPlant(State state, long now, String actor, String commandId,
                                          String transferId, long quantityKg, FundState funds) {
        String content = "TRANSFER|" + transferId + "|" + quantityKg;
        Outcome replay = replay(state, commandId, content, funds, null);
        if (replay != null) {
            return replay;
        }
        String deny = guardOperating(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        if (quantityKg <= 0) {
            return reject(state, INVALID_QUANTITY, funds);
        }
        if (state.usedIds().contains(transferId)) {
            return reject(state, ID_REUSED, funds);
        }
        if (quantityKg > state.mineCoal().quantity()) {
            return reject(state, COAL_SHORT, funds);
        }
        long period = now / PERIOD_MILLIS;
        PeriodUse use = state.useOf(period);
        BigInteger transported = BigInteger.valueOf(use.transportUsedKg())
                .add(BigInteger.valueOf(quantityKg));
        if (transported.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return reject(state, QUANTITY_OVERFLOW, funds);
        }
        if (transported.compareTo(BigInteger.valueOf(
                state.config().transportCapacityKgPerPeriod())) > 0) {
            return reject(state, TRANSPORT_CAPACITY, funds);
        }
        BigDecimal cost = ManufacturingModel.allocate(state.mineCoal(), quantityKg);
        BigDecimal internalRevenue = money(state.config().internalPricePerKg()
                .multiply(BigDecimal.valueOf(quantityKg)));
        Draft draft = new Draft(state, now);
        draft.mineCoal = new Inventory(state.mineCoal().quantity() - quantityKg,
                state.mineCoal().amount().subtract(cost));
        draft.plantFuel = new Inventory(state.plantFuel().quantity() + quantityKg,
                state.plantFuel().amount().add(cost));
        draft.periodUse.put(period, use.plusTransport(quantityKg));
        Totals totals = state.totals();
        draft.totals = new Totals(totals.coalRevenue(), totals.coalCogs(),
                totals.transferRevenue().add(internalRevenue),
                totals.transferCost().add(cost), totals.powerRevenue(),
                totals.burnedFuelCost(), totals.conversionCost(), totals.fixedExpense(),
                Math.addExact(totals.transferredCoalKg(), quantityKg));
        draft.usedIds.add(transferId);
        return accept(draft, "TRANSFERRED", Paid.noop(funds), commandId, content);
    }

    // ---------------------------------------------------------------- 发电并交付

    /**
     * 发电并交付开工（正耗时 1 个日班）：先校验需求/预算/燃料/出力，冻结作业（电量、燃料量与原成本、
     * 转换成本、客户、应收标识、开始/完成期间），预付转换成本形成 WIP，把燃料移出燃料库存并预留
     * **完成期间**的交付需求与预算、**开始期间**的机组出力。此时没有任何收入或应收。
     *
     * @param state        状态
     * @param now          当前时点
     * @param actor        发起人
     * @param commandId    命令标识
     * @param jobId        作业标识
     * @param mwh          交付电量（MWh）
     * @param receivableId 交付时使用的应收标识（begin 冻结）
     * @param funds        当前资金三态
     * @param payer        资金腿（公司 → 转换成本收款方）
     * @return 候选
     */
    public static Outcome beginPowerDelivery(State state, long now, String actor,
                                             String commandId, String jobId, long mwh,
                                             String receivableId, FundState funds,
                                             FundPayer payer) {
        String content = "POWER_BEGIN|" + jobId + "|" + mwh + "|" + receivableId;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        String deny = guardOperating(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        if (jobId == null || jobId.isBlank()) {
            return reject(state, INVALID_JOB, funds);
        }
        if (receivableId == null || receivableId.isBlank()) {
            return reject(state, INVALID_ID, funds);
        }
        if (mwh <= 0) {
            return reject(state, INVALID_QUANTITY, funds);
        }
        if (state.jobs().containsKey(jobId) || state.usedIds().contains(jobId)
                || state.usedIds().contains(receivableId)
                || state.receivables().containsKey(receivableId)) {
            return reject(state, ID_REUSED, funds);
        }
        if (state.runningJob() != null) {
            return reject(state, ACTION_BUSY, funds);
        }
        long fuelNeeded;
        try {
            fuelNeeded = Math.multiplyExact(mwh, state.config().coalKgPerMwh());
        } catch (ArithmeticException overflow) {
            return reject(state, QUANTITY_OVERFLOW, funds);
        }
        long period = now / PERIOD_MILLIS;
        long deliveryPeriod = (now + DAY_MILLIS) / PERIOD_MILLIS;
        PeriodUse capacity = state.useOf(period);
        BigInteger capacityUsed = BigInteger.valueOf(capacity.powerCapacityUsedMwh())
                .add(BigInteger.valueOf(mwh));
        if (capacityUsed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return reject(state, QUANTITY_OVERFLOW, funds);
        }
        if (capacityUsed.compareTo(BigInteger.valueOf(
                state.config().generatorCapacityMwhPerPeriod())) > 0) {
            return reject(state, GENERATOR_CAPACITY, funds);
        }
        PeriodUse delivery = state.useOf(deliveryPeriod);
        BigInteger committed = BigInteger.valueOf(delivery.committedPowerMwh())
                .add(BigInteger.valueOf(mwh));
        if (committed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return reject(state, QUANTITY_OVERFLOW, funds);
        }
        if (committed.compareTo(BigInteger.valueOf(
                state.config().gridDemandMwhPerPeriod())) > 0) {
            return reject(state, DEMAND_EXCEEDED, funds);
        }
        BigDecimal amount = money(state.config().powerPricePerMwh()
                .multiply(BigDecimal.valueOf(mwh)));
        BigDecimal budget = delivery.powerRevenue().add(delivery.powerReservedRevenue())
                .add(amount);
        if (budget.compareTo(state.config().gridBudgetPerPeriod()) > 0) {
            return reject(state, BUDGET_EXCEEDED, funds);
        }
        if (fuelNeeded > state.plantFuel().quantity()) {
            return reject(state, FUEL_SHORT, funds);
        }
        BigDecimal conversion = money(state.config().conversionCostPerMwh()
                .multiply(BigDecimal.valueOf(mwh)));
        Paid paid = pay(payer, commandId, state.config().companyAccountId(),
                state.config().converterAccountId(), conversion, funds, state);
        if (!paid.accepted()) {
            return new Outcome(false, paid.reason(), state, paid.funds());
        }
        BigDecimal fuelCost = ManufacturingModel.allocate(state.plantFuel(), fuelNeeded);
        Draft draft = new Draft(state, now);
        draft.plantFuel = new Inventory(state.plantFuel().quantity() - fuelNeeded,
                money(state.plantFuel().amount().subtract(fuelCost)));
        draft.jobs.put(jobId, new Job(jobId, JobKind.POWER, "", 0L, mwh, fuelNeeded, fuelCost,
                conversion, state.config().gridOperatorId(), receivableId, now,
                now + DAY_MILLIS, period, deliveryPeriod, amount, JobStatus.RUNNING, -1L));
        draft.periodUse.put(period, draft.use(period).plusPowerCapacity(mwh));
        draft.periodUse.put(deliveryPeriod,
                draft.use(deliveryPeriod).reservePower(mwh, amount));
        return accept(draft, "POWER_BEGUN", paid, commandId, content);
    }

    /**
     * 发电并交付完成：只能按 begin 登记的作业核销；到期前拒绝且账不变；只完成一次。
     *
     * <p>完成时把 WIP（预留燃料原成本 + 已付转换成本）结转为 COGS，按 begin 冻结的客户与
     * 应收标识确认收入与应收，并把**完成期间**的预留转为已交付。没有电库存。</p>
     *
     * <p><b>迟完成不跨已关期间</b>：只要作业冻结的完成期间仍未关账，就可以在更晚的当前期间完成，
     * 账仍记入该未关期间（不沿用“当前期间必须等于已关期数”的普通守卫，否则跨期在制会被永久锁死）；
     * 若该期间已经关账，则返回 {@link #JOB_PERIOD_CLOSED}，不做任何回写。</p>
     *
     * @param state     状态
     * @param now       当前时点
     * @param actor     发起人
     * @param commandId 命令标识
     * @param jobId     作业标识
     * @param funds     当前资金三态（finish 不动钱）
     * @return 候选
     */
    public static Outcome finishPowerDelivery(State state, long now, String actor,
                                              String commandId, String jobId, FundState funds) {
        String content = "POWER_FINISH|" + jobId;
        Outcome replay = replay(state, commandId, content, funds, null);
        if (replay != null) {
            return replay;
        }
        String deny = guardActorTime(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        Job job = state.jobOf(jobId);
        if (job == null) {
            return reject(state, UNKNOWN_JOB, funds);
        }
        if (job.kind() != JobKind.POWER) {
            return reject(state, JOB_KIND_MISMATCH, funds);
        }
        if (job.status() != JobStatus.RUNNING) {
            return reject(state, JOB_STATE_INVALID, funds);
        }
        String periodDeny = guardDeliveryPeriod(state, job);
        if (periodDeny != null) {
            return reject(state, periodDeny, funds);
        }
        if (now < job.dueAt()) {
            return reject(state, JOB_NOT_DUE, funds);
        }
        BigDecimal revenue = job.revenueReserved();
        BigDecimal cogs = money(job.fuelCost().add(job.paidCost()));
        Draft draft = new Draft(state, now);
        draft.jobs.put(jobId, new Job(job.jobId(), job.kind(), job.seamId(), job.quantityKg(),
                job.mwh(), job.fuelKg(), job.fuelCost(), job.paidCost(), job.customerId(),
                job.referenceId(), job.beganAt(), job.dueAt(), job.capacityPeriod(),
                job.deliveryPeriod(), job.revenueReserved(), JobStatus.DONE, now));
        draft.periodUse.put(job.deliveryPeriod(),
                draft.use(job.deliveryPeriod()).deliverPower(job.mwh(), revenue));
        draft.receivables.put(job.referenceId(), new Receivable(job.referenceId(),
                job.customerId(), "POWER_MWH", job.mwh(), revenue, cogs, now,
                now + 15L * DAY_MILLIS, ZERO, ReceivableStatus.OPEN));
        draft.usedIds.add(job.referenceId());
        Totals totals = state.totals();
        draft.totals = new Totals(totals.coalRevenue(), totals.coalCogs(),
                totals.transferRevenue(), totals.transferCost(),
                totals.powerRevenue().add(revenue),
                totals.burnedFuelCost().add(job.fuelCost()),
                totals.conversionCost().add(job.paidCost()), totals.fixedExpense(),
                totals.transferredCoalKg());
        return accept(draft, "POWER_DELIVERED", Paid.noop(funds), commandId, content);
    }

    /**
     * 作业真实失败终态：一次性、不可重来；成本计损失，残留资源按种类处理，不静默产出或退款。
     *
     * <p>开采失败：储量已耗、无煤产出，已付开采成本计为作业损失（报废有解释）。
     * 发电失败：预留燃料按原成本退回厂内燃料（仍是资产），已付转换成本计损失，
     * 并释放完成期间的交付需求与预算预留（未交付过，不产生收入/应收）。发电作业即使已过期，
     * 只要其冻结的完成期间仍未关账就可以失败收尾；已关账则 {@link #JOB_PERIOD_CLOSED}。</p>
     *
     * @param state     状态
     * @param now       当前时点
     * @param actor     发起人
     * @param commandId 命令标识
     * @param jobId     作业标识
     * @param funds     当前资金三态（不动钱）
     * @return 候选
     */
    public static Outcome abortJob(State state, long now, String actor, String commandId,
                                   String jobId, FundState funds) {
        String content = "ABORT|" + jobId;
        Outcome replay = replay(state, commandId, content, funds, null);
        if (replay != null) {
            return replay;
        }
        String deny = guardActorTime(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        Job job = state.jobOf(jobId);
        if (job == null) {
            return reject(state, UNKNOWN_JOB, funds);
        }
        if (job.status() != JobStatus.RUNNING) {
            return reject(state, JOB_STATE_INVALID, funds);
        }
        String periodDeny = job.kind() == JobKind.POWER
                ? guardDeliveryPeriod(state, job) : guardPeriod(state, now);
        if (periodDeny != null) {
            return reject(state, periodDeny, funds);
        }
        Draft draft = new Draft(state, now);
        draft.jobs.put(jobId, new Job(job.jobId(), job.kind(), job.seamId(), job.quantityKg(),
                job.mwh(), job.fuelKg(), job.fuelCost(), job.paidCost(), job.customerId(),
                job.referenceId(), job.beganAt(), job.dueAt(), job.capacityPeriod(),
                job.deliveryPeriod(), job.revenueReserved(), JobStatus.FAILED, now));
        if (job.kind() == JobKind.POWER) {
            draft.plantFuel = new Inventory(state.plantFuel().quantity() + job.fuelKg(),
                    money(state.plantFuel().amount().add(job.fuelCost())));
            draft.periodUse.put(job.deliveryPeriod(),
                    draft.use(job.deliveryPeriod())
                            .releasePower(job.mwh(), job.revenueReserved()));
        }
        draft.jobLoss = money(state.jobLoss().add(job.paidCost()));
        return accept(draft, "JOB_FAILED", Paid.noop(funds), commandId, content);
    }

    // ---------------------------------------------------------------- 扩产

    /**
     * 扩产开工：核批准引用、付承包方、capex 入 CIP；不立即形成设备或产能。
     *
     * @param state          状态
     * @param now            当前时点
     * @param actor          发起人
     * @param commandId      命令标识
     * @param projectId      项目标识
     * @param capex          资本开支
     * @param buildMillis    建设时长（时滞）
     * @param rampPeriods    爬坡期数
     * @param addCapacityKg  新增能力（kg/期）
     * @param approvalRef    批准引用
     * @param funds          当前资金三态
     * @param payer          资金腿（公司 → 承包方）
     * @return 候选
     */
    public static Outcome startExpansion(State state, long now, String actor, String commandId,
                                         String projectId, BigDecimal capex, long buildMillis,
                                         int rampPeriods, long addCapacityKg, String approvalRef,
                                         FundState funds, FundPayer payer) {
        String content = "EXPAND|" + projectId + "|" + capex + "|" + buildMillis + "|"
                + rampPeriods + "|" + addCapacityKg + "|" + approvalRef;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        String deny = guardOperating(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        if (approvalRef == null || approvalRef.isBlank()) {
            return reject(state, NO_APPROVAL, funds);
        }
        Approval approval = state.config().approvals().get(approvalRef);
        if (approval == null || !APPROVAL_CAPEX.equals(approval.kind())
                || approval.approvedAt() > now) {
            return reject(state, APPROVAL_INVALID, funds);
        }
        BigDecimal amount = money(capex);
        if (amount.signum() <= 0 || buildMillis <= 0 || rampPeriods <= 0 || addCapacityKg <= 0) {
            return reject(state, INVALID_QUANTITY, funds);
        }
        if (state.expansions().containsKey(projectId)) {
            return reject(state, ID_REUSED, funds);
        }
        BigInteger total = BigInteger.valueOf(state.config().baseMineCapacityKgPerPeriod());
        for (Expansion existing : state.expansions().values()) {
            if (existing.status() != ExpansionStatus.FAILED) {
                total = total.add(BigInteger.valueOf(existing.addCapacityKgPerPeriod()));
            }
        }
        total = total.add(BigInteger.valueOf(addCapacityKg));
        if (total.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return reject(state, CAPACITY_OVERFLOW, funds);
        }
        Paid paid = pay(payer, commandId, state.config().companyAccountId(),
                state.config().contractorAccountId(), amount, funds, state);
        if (!paid.accepted()) {
            return new Outcome(false, paid.reason(), state, paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.expansions.put(projectId, new Expansion(projectId, amount, now, now + buildMillis,
                rampPeriods, addCapacityKg, ExpansionStatus.BUILDING, -1L, ZERO));
        draft.usedIds.add(projectId);
        return accept(draft, "BUILDING", paid, commandId, content);
    }

    /**
     * 扩产完工：到期后成功转设备（按 ramp 生效）或失败留残值并确认损失。
     *
     * @param state     状态
     * @param now       当前时点
     * @param actor     发起人
     * @param commandId 命令标识
     * @param projectId 项目
     * @param failed    是否失败（由冻结的轮次/证据结果决定，本动作不自作判断）
     * @param funds     当前资金三态（不动钱）
     * @return 候选
     */
    public static Outcome completeExpansion(State state, long now, String actor, String commandId,
                                            String projectId, boolean failed, FundState funds) {
        String content = "EXPAND_DONE|" + projectId + "|" + failed;
        Outcome replay = replay(state, commandId, content, funds, null);
        if (replay != null) {
            return replay;
        }
        String deny = guardOperating(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        Expansion expansion = state.expansions().get(projectId);
        if (expansion == null) {
            return reject(state, UNKNOWN_PROJECT, funds);
        }
        if (expansion.status() != ExpansionStatus.BUILDING) {
            return reject(state, PROJECT_STATE_INVALID, funds);
        }
        if (now < expansion.completesAt()) {
            return reject(state, PROJECT_NOT_DUE, funds);
        }
        Draft draft = new Draft(state, now);
        if (failed) {
            BigDecimal residual = money(expansion.capex()
                    .multiply(state.config().residualRate()));
            draft.expansions.put(projectId, new Expansion(expansion.projectId(),
                    expansion.capex(), expansion.startedAt(), expansion.completesAt(),
                    expansion.rampPeriods(), expansion.addCapacityKgPerPeriod(),
                    ExpansionStatus.FAILED, -1L, residual));
            draft.residualAssets.put(projectId, residual);
            draft.disposalResult = money(state.disposalResult()
                    .subtract(expansion.capex().subtract(residual)));
            return accept(draft, "BUILD_FAILED", Paid.noop(funds), commandId, content);
        }
        long online = expansion.completesAt() / PERIOD_MILLIS;
        draft.expansions.put(projectId, new Expansion(expansion.projectId(),
                expansion.capex(), expansion.startedAt(), expansion.completesAt(),
                expansion.rampPeriods(), expansion.addCapacityKgPerPeriod(),
                ExpansionStatus.ONLINE, online, ZERO));
        return accept(draft, "ONLINE", Paid.noop(funds), commandId, content);
    }

    /**
     * 失败残值处置：必须由有限买方用真实现金付款；买方不足则残值原样保留。
     *
     * @param state     状态
     * @param now       当前时点
     * @param actor     发起人
     * @param commandId 命令标识
     * @param projectId 项目
     * @param price     成交价
     * @param funds     当前资金三态
     * @param payer     资金腿（买方 → 公司）
     * @return 候选
     */
    public static Outcome sellResidual(State state, long now, String actor, String commandId,
                                       String projectId, BigDecimal price, FundState funds,
                                       FundPayer payer) {
        String content = "SELL_RESIDUAL|" + projectId + "|" + price;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        String deny = guardLedger(state, now, actor);
        if (deny != null) {
            return reject(state, deny, funds);
        }
        BigDecimal residual = state.residualAssets().get(projectId);
        if (residual == null || residual.signum() <= 0) {
            return reject(state, NO_RESIDUAL, funds);
        }
        BigDecimal amount = money(price);
        if (amount.signum() <= 0) {
            return reject(state, INVALID_QUANTITY, funds);
        }
        Paid paid = pay(payer, commandId, state.config().disposalBuyerAccountId(),
                state.config().companyAccountId(), amount, funds, state);
        if (!paid.accepted()) {
            String reason = INSUFFICIENT_CASH.equals(paid.reason())
                    ? BUYER_CASH_SHORT : paid.reason();
            return new Outcome(false, reason, state, paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.residualAssets.remove(projectId);
        draft.disposalResult = money(state.disposalResult().add(amount.subtract(residual)));
        return accept(draft, "RESIDUAL_SOLD", paid, commandId, content);
    }

    // ---------------------------------------------------------------- 退出

    /**
     * 一次性退出：核批准引用，设备净值转退出损失，库存保留为资产；不可逆。
     *
     * @param state       状态
     * @param now         当前时点
     * @param actor       发起人
     * @param commandId   命令标识
     * @param approvalRef 批准引用
     * @param funds       当前资金三态（不动钱）
     * @return 候选
     */
    public static Outcome exitBusiness(State state, long now, String actor, String commandId,
                                       String approvalRef, FundState funds) {
        return exitBusiness(state, now, actor, commandId, approvalRef, state.config().approvals().get(approvalRef == null ? "" : approvalRef), funds);
    }

    static Outcome exitBusiness(State state, long now, String actor, String commandId,
                                String approvalRef, Approval approval, FundState funds) {
        String content = "EXIT|" + approvalRef;
        Outcome replay = replay(state, commandId, content, funds, null);
        if (replay != null) {
            return replay;
        }
        if (!state.config().companyOwnerId().equals(actor)) {
            return reject(state, NOT_AUTHORIZED, funds);
        }
        if (now < state.watermark()) {
            return reject(state, TIME_REWIND, funds);
        }
        if (state.exited()) {
            return reject(state, ALREADY_EXITED, funds);
        }
        if (approvalRef == null || approvalRef.isBlank()) {
            return reject(state, NO_APPROVAL, funds);
        }
        if (approval == null || !approvalRef.equals(approval.approvalId()) || !APPROVAL_EXIT.equals(approval.kind())
                || approval.approvedAt() > now) {
            return reject(state, APPROVAL_INVALID, funds);
        }
        for (Expansion expansion : state.expansions().values()) {
            if (expansion.status() == ExpansionStatus.BUILDING) {
                return reject(state, EXPANSION_IN_PROGRESS, funds);
            }
        }
        if (state.runningJob() != null) {
            return reject(state, JOB_IN_PROGRESS, funds);
        }
        Draft draft = new Draft(state, now);
        BigDecimal net = state.equipmentNet();
        draft.retiredEquipment = money(state.retiredEquipment().add(net));
        draft.exitLoss = money(state.exitLoss().add(net));
        draft.exited = true;
        return accept(draft, "EXITED", Paid.noop(funds), commandId, content);
    }

    /** 按原应付费用债权人实际付款；不再次确认费用，也不新增预算承诺。 */
    static Outcome settleBill(State state, long now, String commandId, long period, FundState funds, FundPayer payer) {
        String content = "SETTLE_BILL|" + period;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) return replay;
        String denied = guardLedger(state, now, state.config().companyOwnerId());
        if (denied != null) return reject(state, denied, funds);
        var bill = state.bills().get(period);
        if (bill == null || bill.outstanding().signum() == 0) return reject(state, "NO_OUTSTANDING_BILL", funds);
        BigDecimal payment = money(payer.available(funds).min(bill.outstanding()));
        if (payment.signum() <= 0) return reject(state, INSUFFICIENT_CASH, funds);
        var paid = pay(payer, commandId, state.config().companyAccountId(), state.config().opsAccountId(), payment, funds, state);
        if (!paid.accepted()) return new Outcome(false, paid.reason(), state, paid.funds());
        var draft = new Draft(state, now);
        draft.bills.put(period, new ExpenseBill(period, bill.amount(), bill.paid().add(payment)));
        return accept(draft, "BILL_SETTLED", paid, commandId, content);
    }

    // ---------------------------------------------------------------- 收款/坏账/注资

    /**
     * 收款：只冲应收，不再确认收入，也不恢复已交付需求/预算/运力。
     *
     * @param state         状态
     * @param now           当前时点
     * @param actor         发起人
     * @param commandId     命令标识
     * @param receivableId  应收
     * @param funds         当前资金三态
     * @param payer         资金腿（客户 → 公司）
     * @return 候选
     */
    public static Outcome collect(State state, long now, String actor, String commandId,
                                  String receivableId, FundState funds, FundPayer payer) {
        String content = "COLLECT|" + receivableId;
        Outcome replay = replay(state, commandId, content, funds, payer);
        if (replay != null) {
            return replay;
        }
        String deny = guardLedger(state, now, actor);
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
        String customerAccount = "COAL_KG".equals(receivable.basis())
                ? state.config().coalCustomerAccountId() : state.config().gridAccountId();
        Paid paid = pay(payer, commandId, customerAccount, state.config().companyAccountId(),
                receivable.outstanding(), funds, state);
        if (!paid.accepted()) {
            String reason = INSUFFICIENT_CASH.equals(paid.reason())
                    ? CUSTOMER_CASH_SHORT : paid.reason();
            return new Outcome(false, reason, state, paid.funds());
        }
        Draft draft = new Draft(state, now);
        draft.receivables.put(receivableId, new Receivable(receivable.receivableId(),
                receivable.customerId(), receivable.basis(), receivable.units(),
                receivable.amount(), receivable.cost(), receivable.recognizedAt(),
                receivable.dueAt(), receivable.outstanding(), ReceivableStatus.PAID));
        return accept(draft, "PAYMENT_RECEIVED", paid, commandId, content);
    }

    /**
     * 坏账核销：逾期后才能核销；收入不倒回，只把未收金额计为坏账损失。
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
        String deny = guardLedger(state, now, actor);
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
                receivable.customerId(), receivable.basis(), receivable.units(),
                receivable.amount(), receivable.cost(), receivable.recognizedAt(),
                receivable.dueAt(), receivable.paid(), ReceivableStatus.WRITTEN_OFF));
        draft.badDebtLoss = money(state.badDebtLoss().add(receivable.outstanding()));
        return accept(draft, "WRITTEN_OFF", Paid.noop(funds), commandId, content);
    }

    /**
     * EXP 外部资本注入：真实从注资方账户转入公司账户，世界现金不变，权益增加。
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
        String deny = guardLedger(state, now, actor);
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
     * 期末关账：付本期固定费用（不足成应付）、计提折旧；必须按期连续且期间已结束。
     *
     * <p><b>不吞在制交付</b>：若仍有在制发电作业，且其冻结的完成期间不晚于待关闭期间
     * （{@code job.deliveryPeriod() <= period}），关账会返回 {@link #PERIOD_HAS_RUNNING_DELIVERY}，
     * 账上一分不动。否则关账后再 finish 就会把交付量与收入写回已关期间。跨期在制仍受支持：
     * 作业完成期间在待关闭期间之后（如 29 日开工、30 日到期 → 完成期间 1）时，关 0 期照常允许；
     * 到期后先 finish/abort、或在未关期间内迟完成，都能让关账继续。开采作业（完成期间 -1）
     * 不阻塞关账。</p>
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
        return closePeriod(state, now, actor, commandId, period, funds, payer, null);
    }

    static Outcome closePeriod(State state, long now, String actor, String commandId,
                               long period, FundState funds, FundPayer payer, Long ceasedAt) {
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
        for (Job job : state.jobs().values()) {
            if (job.status() == JobStatus.RUNNING && job.kind() == JobKind.POWER
                    && job.deliveryPeriod() <= period) {
                return reject(state, PERIOD_HAS_RUNNING_DELIVERY, funds);
            }
        }
        if (payer == null) {
            return reject(state, NOT_AUTHORIZED, funds);
        }
        BigDecimal expense = ceasedAt != null && period > ceasedAt / PERIOD_MILLIS ? ZERO : state.config().fixedExpensePerPeriod();
        BigDecimal canPay = payer.available(funds);
        BigDecimal paying = money(expense.min(canPay));
        Paid paid = Paid.noop(funds);
        if (paying.signum() > 0) {
            paid = pay(payer, commandId, state.config().companyAccountId(),
                    state.config().opsAccountId(), paying, funds, state);
            if (!paid.accepted()) {
                return new Outcome(false, paid.reason(), state, paid.funds());
            }
        }
        BigDecimal net = state.equipmentNet();
        BigDecimal depreciation = money(state.config().depreciationPerPeriod()
                .min(net.signum() > 0 ? net : ZERO));
        Draft draft = new Draft(state, now);
        if (expense.signum() > 0) {
            draft.bills.put(period, new ExpenseBill(period, expense, paying));
        }
        draft.accumulatedDepreciation = money(state.accumulatedDepreciation()
                .add(depreciation));
        draft.closedPeriods = state.closedPeriods() + 1;
        Totals totals = state.totals();
        draft.totals = new Totals(totals.coalRevenue(), totals.coalCogs(),
                totals.transferRevenue(), totals.transferCost(), totals.powerRevenue(),
                totals.burnedFuelCost(), totals.conversionCost(),
                totals.fixedExpense().add(expense), totals.transferredCoalKg());
        return accept(draft, "PERIOD_CLOSED", paid, commandId, content);
    }

    // ---------------------------------------------------------------- 报表

    /**
     * 派生报表（A=L+E 与分段/合并双口径），不改状态。
     *
     * @param state 状态
     * @param funds 当前资金三态（现金唯一来源）
     * @return 报表
     */
    public static Report report(State state, FundState funds) {
        Objects.requireNonNull(state, "状态");
        Objects.requireNonNull(funds, "资金三态");
        Config config = state.config();
        BigDecimal cash = funds.cash(config.companyAccountId());
        BigDecimal receivables = ZERO;
        long coalDelivered = 0L;
        long powerDelivered = 0L;
        long transportUsed = 0L;
        for (Receivable receivable : state.receivables().values()) {
            if (receivable.status() == ReceivableStatus.OPEN) {
                receivables = receivables.add(receivable.outstanding());
            }
        }
        for (PeriodUse use : state.periodUse().values()) {
            coalDelivered = Math.addExact(coalDelivered, use.coalDeliveredKg());
            powerDelivered = Math.addExact(powerDelivered, use.powerDeliveredMwh());
            transportUsed = Math.addExact(transportUsed, use.transportUsedKg());
        }
        BigDecimal payables = ZERO;
        for (ExpenseBill bill : state.bills().values()) {
            payables = payables.add(bill.outstanding());
        }
        BigDecimal cip = ZERO;
        for (Expansion expansion : state.expansions().values()) {
            if (expansion.status() == ExpansionStatus.BUILDING) {
                cip = cip.add(expansion.capex());
            }
        }
        BigDecimal residual = ZERO;
        for (BigDecimal value : state.residualAssets().values()) {
            residual = residual.add(value);
        }
        long reserve = 0L;
        for (Long remaining : state.seamRemaining().values()) {
            reserve = Math.addExact(reserve, remaining);
        }
        long openJobs = 0L;
        for (Job job : state.jobs().values()) {
            if (job.status() == JobStatus.RUNNING) {
                openJobs++;
            }
        }
        BigDecimal wip = state.workInProgress();
        Totals totals = state.totals();
        BigDecimal revenue = money(totals.coalRevenue().add(totals.powerRevenue()));
        BigDecimal cogs = money(totals.coalCogs().add(totals.burnedFuelCost())
                .add(totals.conversionCost()));
        BigDecimal assets = money(cash.add(state.mineCoal().amount())
                .add(state.plantFuel().amount()).add(receivables)
                .add(state.equipmentNet()).add(cip).add(residual).add(wip));
        BigDecimal liabilities = money(payables);
        BigDecimal profit = money(revenue.subtract(cogs).subtract(totals.fixedExpense())
                .subtract(state.accumulatedDepreciation()).add(state.disposalResult())
                .subtract(state.badDebtLoss()).subtract(state.exitLoss())
                .subtract(state.jobLoss()));
        BigDecimal equity = money(state.initialEquity().add(state.capitalContributions())
                .add(profit));
        BigDecimal world = worldCash(funds);
        boolean balanced = assets.compareTo(liabilities.add(equity)) == 0
                && world.compareTo(state.initialWorldCash()) == 0;

        long inFlightKg = 0L;
        BigDecimal inFlightCost = ZERO;
        for (Job job : state.jobs().values()) {
            if (job.status() == JobStatus.RUNNING && job.kind() == JobKind.POWER) {
                inFlightKg = Math.addExact(inFlightKg, job.fuelKg());
                inFlightCost = money(inFlightCost.add(job.fuelCost()));
            }
        }
        BigDecimal coalSegment = money(totals.coalRevenue().add(totals.transferRevenue())
                .subtract(totals.coalCogs()).subtract(totals.transferCost()));
        long burnedKg = totals.transferredCoalKg() - state.plantFuel().quantity()
                - inFlightKg;
        if (burnedKg < 0) {
            throw new IllegalStateException("厂内燃料账不自洽：已转 " + totals.transferredCoalKg()
                    + "kg，库存 " + state.plantFuel().quantity() + "kg，在制 " + inFlightKg + "kg");
        }
        BigDecimal burnedInternalCost = money(config.internalPricePerKg()
                .multiply(BigDecimal.valueOf(burnedKg)));
        BigDecimal powerSegment = money(totals.powerRevenue().subtract(burnedInternalCost)
                .subtract(totals.conversionCost()));
        BigDecimal unrealized = money(config.internalPricePerKg()
                .multiply(BigDecimal.valueOf(state.plantFuel().quantity() + inFlightKg))
                .subtract(state.plantFuel().amount().add(inFlightCost)));
        BigDecimal consolidated = money(coalSegment.add(powerSegment).subtract(unrealized));
        return new Report(money(cash), money(state.mineCoal().amount()),
                state.mineCoal().quantity(), money(state.plantFuel().amount()),
                state.plantFuel().quantity(), money(receivables), state.equipmentNet(),
                money(cip), money(residual), money(wip), liabilities, assets, liabilities,
                equity, revenue, cogs, money(totals.fixedExpense()),
                money(state.accumulatedDepreciation()), money(state.exitLoss()),
                money(state.jobLoss()), money(state.disposalResult()),
                money(state.badDebtLoss()), coalDelivered, powerDelivered, transportUsed,
                reserve, openJobs, balanced, world,
                new SegmentReport(coalSegment, powerSegment, unrealized, consolidated));
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
            throw new IllegalStateException("资源账不平衡：资产=" + report.assets()
                    + "，负债=" + report.liabilities() + "，权益=" + report.equity()
                    + "，世界现金=" + report.worldCash());
        }
        return report;
    }

    // ---------------------------------------------------------------- 内部

    /** 一次付款腿的结果。 */
    private record Paid(boolean accepted, String reason, FundState funds, boolean moved) {

        private static Paid noop(FundState funds) {
            return new Paid(true, OK, funds, false);
        }
    }
    private static String guardOperating(State state, long now, String actor) {
        String base = guardActorTime(state, now, actor);
        if (base != null) {
            return base;
        }
        if (state.exited()) {
            return CLOSED;
        }
        return guardPeriod(state, now);
    }

    private static String guardLedger(State state, long now, String actor) {
        String base = guardActorTime(state, now, actor);
        return base != null ? base : guardPeriod(state, now);
    }

    private static String guardActorTime(State state, long now, String actor) {
        if (!state.config().companyOwnerId().equals(actor)) {
            return NOT_AUTHORIZED;
        }
        if (now < state.watermark()) {
            return TIME_REWIND;
        }
        return null;
    }

    private static String guardPeriod(State state, long now) {
        long period = now / PERIOD_MILLIS;
        if (period < state.closedPeriods()) {
            return PERIOD_CLOSED;
        }
        if (period > state.closedPeriods()) {
            return PERIOD_NOT_CLOSED;
        }
        return null;
    }

    /**
     * 发电作业的结算期间守卫：只要求作业冻结的完成期间**尚未关账**。
     *
     * <p>这样迟完成/迟失败仍写回那个未关期间（不写已关期间），跨期在制也不会被
     * “当前期间必须等于已关期数”卡成永远结束不了的活作业；而 closePeriod 会拒绝关闭
     * 仍被在制交付占用的期间，两者共同保证已关期间不被回写。</p>
     *
     * @param state 状态
     * @param job   在制发电作业
     * @return 拒绝码；可结算返回 null
     */
    private static String guardDeliveryPeriod(State state, Job job) {
        if (state.exited()) {
            return CLOSED;
        }
        return job.deliveryPeriod() < state.closedPeriods() ? JOB_PERIOD_CLOSED : null;
    }

    private static Seam seam(Config config, String seamId) {
        for (Seam seam : config.seams()) {
            if (seam.seamId().equals(seamId)) {
                return seam;
            }
        }
        return null;
    }

    /** 给世界提供与开采约束相同的实际爬坡产能，不复制计算口径。 */
    public static long miningCapacityAt(State state, long period) {
        if (period < 0) throw new IllegalArgumentException("期间为负");
        return capacityAt(state, period).longValueExact();
    }

    private static BigInteger capacityAt(State state, long period) {
        BigInteger capacity = BigInteger.valueOf(state.config().baseMineCapacityKgPerPeriod());
        for (Expansion expansion : state.expansions().values()) {
            if (expansion.status() != ExpansionStatus.ONLINE) {
                continue;
            }
            long elapsed = period - expansion.onlinePeriod();
            if (elapsed <= 0) {
                continue;
            }
            BigInteger add = BigInteger.valueOf(expansion.addCapacityKgPerPeriod());
            if (elapsed >= expansion.rampPeriods()) {
                capacity = capacity.add(add);
            } else {
                capacity = capacity.add(add.multiply(BigInteger.valueOf(elapsed))
                        .divide(BigInteger.valueOf(expansion.rampPeriods())));
            }
        }
        return capacity;
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
     * 执行一次付款腿：主体来自开局冻结映射，不采信自报 owner；先查可用额再真付款。
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
        return new Paid(result.accepted(), result.reason(), result.funds(),
                result.accepted());
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
            throw new IllegalArgumentException("成本价不能为空");
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
        private Inventory mineCoal;
        private Inventory plantFuel;
        private final Map<String, Job> jobs;
        private final Map<String, Long> seamRemaining;
        private final Map<Long, PeriodUse> periodUse;
        private Totals totals;
        private final Map<String, Receivable> receivables;
        private final Map<String, Expansion> expansions;
        private final Map<String, BigDecimal> residualAssets;
        private final Map<Long, ExpenseBill> bills;
        private final Map<String, String> commandContents;
        private final Map<String, Reply> commandReplies;
        private final Set<String> usedIds;
        private BigDecimal accumulatedDepreciation;
        private BigDecimal retiredEquipment;
        private BigDecimal capitalContributions;
        private BigDecimal exitLoss;
        private BigDecimal disposalResult;
        private BigDecimal badDebtLoss;
        private BigDecimal jobLoss;
        private long closedPeriods;
        private long watermark;
        private boolean exited;

        private Draft(State base, long now) {
            this.base = base;
            this.mineCoal = base.mineCoal();
            this.plantFuel = base.plantFuel();
            this.jobs = new LinkedHashMap<>(base.jobs());
            this.seamRemaining = new LinkedHashMap<>(base.seamRemaining());
            this.periodUse = new LinkedHashMap<>(base.periodUse());
            this.totals = base.totals();
            this.receivables = new LinkedHashMap<>(base.receivables());
            this.expansions = new LinkedHashMap<>(base.expansions());
            this.residualAssets = new LinkedHashMap<>(base.residualAssets());
            this.bills = new LinkedHashMap<>(base.bills());
            this.commandContents = new LinkedHashMap<>(base.commandContents());
            this.commandReplies = new LinkedHashMap<>(base.commandReplies());
            this.usedIds = new LinkedHashSet<>(base.usedIds());
            this.accumulatedDepreciation = base.accumulatedDepreciation();
            this.retiredEquipment = base.retiredEquipment();
            this.capitalContributions = base.capitalContributions();
            this.exitLoss = base.exitLoss();
            this.disposalResult = base.disposalResult();
            this.badDebtLoss = base.badDebtLoss();
            this.jobLoss = base.jobLoss();
            this.closedPeriods = base.closedPeriods();
            this.watermark = Math.max(base.watermark(), now);
            this.exited = base.exited();
        }

        private PeriodUse use(long period) {
            return periodUse.getOrDefault(period, PeriodUse.empty());
        }

        private State build() {
            return new State(base.config(), base.openingOwners(), base.initialEquity(),
                    base.initialWorldCash(), watermark, jobs, jobLoss, mineCoal, plantFuel,
                    seamRemaining, periodUse, totals, receivables, expansions, residualAssets,
                    bills, closedPeriods, accumulatedDepreciation, retiredEquipment,
                    capitalContributions, exited, exitLoss, disposalResult, badDebtLoss,
                    commandContents, commandReplies, usedIds);
        }
    }
}
