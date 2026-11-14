package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;
import edu.cufe.auction.world.bank.BankingProfile;
import edu.cufe.auction.world.bank.FundState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * M5a 工程回款纯核：签约、预收、履约投入、验收、阶段开票、到期回款、供应商支付、
 * 减值、部分核销、核销后回收与独立豁免。
 *
 * <p><b>冻结的实验合同条款</b>（不概括所有工程合同，也不声称行业惯例或会计准则）：</p>
 * <ul>
 *   <li>质保留款只采用 {@link RetentionMode#TIME_ONLY}：阶段金额验收并开票后即取得无条件付款权，
 *       质保部分是<b>未到期应收</b>，到期前不得强收，但可因真实违约评估计提减值。</li>
 *   <li>未验收或尚未取得无条件付款权的金额是合同资产，单列；不搬入应收，也不搬入备查。</li>
 *   <li>附加质量条件模式在构造 {@link Terms} 时显式拒绝，不静默降级为 TIME_ONLY。</li>
 * </ul>
 *
 * <p>金额一律 {@link BigDecimal}（2 位；成本单价 4 位），数量与时间为 {@code long}。
 * 现金只存在于 {@link CashLedger.CashAccount} 账户表：本类不保存余额，只通过
 * {@link CashLedger#computeTransfer} 取得候选账户表并随结果返回。业务时间由调用方显式传入，
 * 无 IO、无随机、无系统时钟。</p>
 *
 * <p>幂等：每个动作携带 {@code commandId} 与规范化内容；同 ID 同内容返回原结果且不重复记账，
 * 同 ID 不同内容抛 {@link IllegalArgumentException}。授权检查先于标识存在性检查，
 * 非公司所有者无法探测合同/任务是否存在。</p>
 */
public final class EngineeringModel {

    /** 一个实验日的模拟毫秒数；不读取系统日历。 */
    public static final long DAY_MILLIS = 86_400_000L;

    /** 成功签约。 */
    public static final String REASON_SIGNED = "SIGNED";
    /** 收到预收。 */
    public static final String REASON_ADVANCE_RECEIVED = "ADVANCE_RECEIVED";
    /** 履约投入已排程。 */
    public static final String REASON_WORK_STARTED = "WORK_STARTED";
    /** 履约投入完成。 */
    public static final String REASON_WORK_COMPLETED = "WORK_COMPLETED";
    /** 验收确认收入。 */
    public static final String REASON_ACCEPTED = "ACCEPTED";
    /** 开票形成应收。 */
    public static final String REASON_INVOICED = "INVOICED";
    /** 到期应收回款。 */
    public static final String REASON_COLLECTED = "COLLECTED";
    /** 质保到期回款。 */
    public static final String REASON_RETENTION_COLLECTED = "RETENTION_COLLECTED";
    /** 计提减值。 */
    public static final String REASON_IMPAIRED = "IMPAIRED";
    /** 核销已减值应收。 */
    public static final String REASON_WRITTEN_OFF = "WRITTEN_OFF";
    /** 核销后真实回收。 */
    public static final String REASON_RECOVERED = "RECOVERED";
    /** 显式豁免。 */
    public static final String REASON_WAIVED = "WAIVED";
    /** 终止剩余履约。 */
    public static final String REASON_TERMINATED = "TERMINATED";
    /** 支付供应商。 */
    public static final String REASON_SUPPLIER_PAID = "SUPPLIER_PAID";

    /** 发起人不是公司所有者。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 标识已被使用。 */
    public static final String REASON_ID_REUSED = "ID_REUSED";
    /** 合同不存在。 */
    public static final String REASON_UNKNOWN_CONTRACT = "UNKNOWN_CONTRACT";
    /** 合同状态不允许该动作。 */
    public static final String REASON_CONTRACT_NOT_ACTIVE = "CONTRACT_NOT_ACTIVE";
    /** 金额或数量非法。 */
    public static final String REASON_INVALID_AMOUNT = "INVALID_AMOUNT";
    /** 数量非法。 */
    public static final String REASON_INVALID_QUANTITY = "INVALID_QUANTITY";
    /** 开工任务不存在。 */
    public static final String REASON_UNKNOWN_WORK = "UNKNOWN_WORK";
    /** 开工任务尚未到期。 */
    public static final String REASON_WORK_NOT_DUE = "WORK_NOT_DUE";
    /** 开工任务状态不允许该动作。 */
    public static final String REASON_WORK_STATE_INVALID = "WORK_STATE_INVALID";
    /** 超过合同剩余可投入数量。 */
    public static final String REASON_EXCEEDS_CONTRACT_UNITS = "EXCEEDS_CONTRACT_UNITS";
    /** 预收超过条款上限。 */
    public static final String REASON_ADVANCE_EXCEEDS_TERMS = "ADVANCE_EXCEEDS_TERMS";
    /** 可用现金不足。 */
    public static final String REASON_INSUFFICIENT_CASH = "INSUFFICIENT_CASH";
    /** 验收数量超过已完工未验收数量。 */
    public static final String REASON_EXCEEDS_COMPLETED = "EXCEEDS_COMPLETED";
    /** 验收必须按阶段整段进行。 */
    public static final String REASON_TRANCHE_MISMATCH = "TRANCHE_MISMATCH";
    /** 开票期次不连续或已开票。 */
    public static final String REASON_INVOICE_ORDER = "INVOICE_ORDER";
    /** 该期尚未验收。 */
    public static final String REASON_NOT_ACCEPTED = "NOT_ACCEPTED";
    /** 应收尚未到期。 */
    public static final String REASON_NOT_DUE = "NOT_DUE";
    /** 回款超过未收金额。 */
    public static final String REASON_OVER_COLLECT = "OVER_COLLECT";
    /** 减值超过应收毛额。 */
    public static final String REASON_IMPAIR_EXCEEDS_GROSS = "IMPAIR_EXCEEDS_GROSS";
    /** 核销超过已计提减值。 */
    public static final String REASON_WRITE_OFF_EXCEEDS_ALLOWANCE = "WRITE_OFF_EXCEEDS_ALLOWANCE";
    /** 回收超过备查权利。 */
    public static final String REASON_RECOVERY_EXCEEDS_MEMO = "RECOVERY_EXCEEDS_MEMO";
    /** 豁免超过客户义务。 */
    public static final String REASON_WAIVE_EXCEEDS_OBLIGATION = "WAIVE_EXCEEDS_OBLIGATION";
    /** 支付超过应付。 */
    public static final String REASON_PAYMENT_EXCEEDS_PAYABLE = "PAYMENT_EXCEEDS_PAYABLE";
    /** 计算溢出。 */
    public static final String REASON_OVERFLOW = "OVERFLOW";
    /** 合同级成本/人工尾差无法闭合成非负值。 */
    public static final String REASON_ALLOCATION_INVALID = "ALLOCATION_INVALID";

    /** 资金腿出款主体与业务配置的公司所有者不一致。 */
    public static final String REASON_FUND_OWNER_MISMATCH = "FUND_OWNER_MISMATCH";

    /** 资金腿收款主体与业务配置的供应商所有者不一致。 */
    public static final String REASON_PAYEE_MISMATCH = "PAYEE_MISMATCH";

    /** 资金层已有同标识记录而业务层没有：受控拒绝，不把零移动当新付款。 */
    public static final String REASON_FUND_ID_ALREADY_RECORDED = "FUND_ID_ALREADY_RECORDED";

    /** 业务层与资金层对同标识的成功/失败不一致：明示分叉，不返回成功。 */
    public static final String REASON_FUND_RECORD_DIVERGED = "FUND_RECORD_DIVERGED";

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private EngineeringModel() {
    }

    /** 质保留款条件模式；纯核只支持 {@link #TIME_ONLY}。 */
    public enum RetentionMode {
        /** 到期即付，无额外质量条件。 */
        TIME_ONLY,
        /** 需要最终验收/质量条件；本版显式拒绝。 */
        FINAL_ACCEPTANCE_AND_TIME
    }

    /** 合同状态。 */
    public enum ContractStatus {
        /** 履约中。 */
        ACTIVE,
        /** 已终止剩余履约；已验收阶段应收保留。 */
        TERMINATED
    }

    /** 开工任务状态。 */
    public enum WorkStatus {
        /** 进行中。 */
        IN_PROGRESS,
        /** 已完工。 */
        COMPLETED,
        /** 合同终止时关闭的未完工任务。 */
        CANCELLED
    }

    /**
     * 合同条款（冻结实验参数）。
     *
     * @param advanceBp        预收比例，0..10000
     * @param retentionBp      质保比例，0..10000；与预收合计不超过 10000
     * @param netDays          开票后到期天数
     * @param retentionNetDays 质保到期天数，不早于 netDays
     * @param mode             条件模式，只接受 TIME_ONLY
     * @param trancheUnits     阶段单位数，必须为正且求和等于合同总量
     */
    public record Terms(int advanceBp, int retentionBp, int netDays, int retentionNetDays,
                        RetentionMode mode, long[] trancheUnits) {

        /** 校验比例、天数、模式与阶段表；拒绝附加条件模式。 */
        public Terms {
            if (mode != RetentionMode.TIME_ONLY) {
                throw new IllegalArgumentException("RETENTION_CONDITION_UNSUPPORTED");
            }
            if (advanceBp < 0 || advanceBp > 10000 || retentionBp < 0 || retentionBp > 10000) {
                throw new IllegalArgumentException("比例必须在 0..10000");
            }
            if ((long) advanceBp + retentionBp > 10000L) {
                throw new IllegalArgumentException("TERMS_NOT_CLOSED");
            }
            if (netDays < 0 || retentionNetDays < netDays) {
                throw new IllegalArgumentException("到期天数不合法");
            }
            Objects.requireNonNull(trancheUnits, "阶段单位表");
            if (trancheUnits.length == 0) {
                throw new IllegalArgumentException("阶段单位表不能为空");
            }
            long sum = 0;
            for (long units : trancheUnits) {
                if (units <= 0) {
                    throw new IllegalArgumentException("阶段单位必须为正");
                }
                try {
                    sum = Math.addExact(sum, units);
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("阶段单位求和溢出", overflow);
                }
            }
            trancheUnits = trancheUnits.clone();
        }

        /** @return 阶段单位表的防御性副本 */
        @Override
        public long[] trancheUnits() {
            return trancheUnits.clone();
        }

        /** 按条款与阶段数值比较，持久化重建的数组仍代表同一份条款。 */
        @Override
        public boolean equals(Object other) {
            return other instanceof Terms terms && advanceBp == terms.advanceBp
                    && retentionBp == terms.retentionBp && netDays == terms.netDays
                    && retentionNetDays == terms.retentionNetDays && mode == terms.mode
                    && Arrays.equals(trancheUnits, terms.trancheUnits);
        }

        /** @return 与条款内容相等语义一致的散列值 */
        @Override
        public int hashCode() {
            return 31 * Objects.hash(advanceBp, retentionBp, netDays, retentionNetDays, mode)
                    + Arrays.hashCode(trancheUnits);
        }

        /** @return 阶段单位总数 */
        public long totalUnits() {
            long sum = 0;
            for (long units : trancheUnits) {
                sum = Math.addExact(sum, units);
            }
            return sum;
        }
    }

    /**
     * 公司账户绑定与实验参数。
     *
     * @param companyOwnerId    公司财产主体
     * @param companyAccountId  公司经营账户
     * @param customerAccountId 客户聚合账户
     * @param supplierAccountId 分包/供应商聚合账户
     * @param opsAccountId      作业方账户
     * @param unitMillis        每个标准工日的模拟毫秒
     * @param laborBp           履约成本中现场付现比例（其余计分包应付）
     * @param paramSource       参数来源说明，必须非空
     */
    public record Config(String companyOwnerId, String companyAccountId, String customerAccountId,
                         String supplierAccountId, String opsAccountId, long unitMillis,
                         int laborBp, String paramSource) {

        /** 校验标识、账户与实验参数。 */
        public Config {
            requireText(companyOwnerId, "公司所有者");
            requireText(companyAccountId, "公司账户");
            requireText(customerAccountId, "客户账户");
            requireText(supplierAccountId, "供应商账户");
            requireText(opsAccountId, "作业方账户");
            requireText(paramSource, "参数来源");
            if (Set.of(companyAccountId, customerAccountId, supplierAccountId, opsAccountId)
                    .size() != 4) {
                throw new IllegalArgumentException("四个账户标识必须互不相同");
            }
            if (unitMillis <= 0) {
                throw new IllegalArgumentException("单位工日必须为正");
            }
            if (laborBp < 0 || laborBp > 10000) {
                throw new IllegalArgumentException("人工比例必须在 0..10000");
            }
        }
    }

    /**
     * 签约输入。
     *
     * @param contractId 合同标识，运行内不复用
     * @param unitPrice  合同单价，精确到分且为正
     * @param unitCost   单位履约成本，最多 4 位且非负
     * @param terms      冻结条款
     */
    public record ContractSpec(String contractId, BigDecimal unitPrice, BigDecimal unitCost,
                               Terms terms) {

        /** 校验标识、单价成本与条款。 */
        public ContractSpec {
            requireText(contractId, "合同标识");
            unitPrice = CashLedger.money(unitPrice);
            if (unitPrice.signum() <= 0) {
                throw new IllegalArgumentException("合同单价必须为正");
            }
            unitCost = requireCost(unitCost);
            Objects.requireNonNull(terms, "合同条款");
        }

        /** @return 合同总价（单价×总量，精确到分） */
        public BigDecimal contractValue() {
            return CashLedger.money(unitPrice.multiply(
                    BigDecimal.valueOf(terms.totalUnits())));
        }
    }

    /**
     * 单张开票。
     *
     * @param trancheIndex        期次，从 0 开始
     * @param units               该期单位数
     * @param gross               该期毛额（收入）
     * @param applied             验收时已冲抵的预收
     * @param retention           该期扣留质保
     * @param dueAt               到期时点
     * @param retentionDueAt      质保到期时点
     * @param currentOutstanding  到期未收
     * @param retentionOutstanding 质保未收
     */
    public record Invoice(int trancheIndex, long units, BigDecimal gross, BigDecimal applied,
                          BigDecimal retention, long dueAt, long retentionDueAt,
                          BigDecimal currentOutstanding, BigDecimal retentionOutstanding) {
    }

    /**
     * 合同状态与阶段进度。
     *
     * @param contractId        合同标识
     * @param unitPrice         单价
     * @param unitCost          单位成本
     * @param terms             冻结条款
     * @param signedAt          签约时点
     * @param completedUnits    已完工单位
     * @param acceptedUnits     已验收单位（等于已验收阶段单位之和）
     * @param invoicedTranches  已开票期数
     * @param advanceReceived   已收预收
     * @param retentionWithheld 已扣留质保
     * @param appliedByTranche  各期验收时冲抵的预收
     * @param invoices          已开票记录
     * @param status            状态
     */
    public record Contract(String contractId, BigDecimal unitPrice, BigDecimal unitCost, Terms terms,
                           long signedAt, long completedUnits, long acceptedUnits,
                           int invoicedTranches, BigDecimal advanceReceived,
                           BigDecimal retentionWithheld, List<BigDecimal> appliedByTranche,
                           List<Invoice> invoices, ContractStatus status) {

        /** 校验进度、金额与集合不可变。 */
        public Contract {
            requireText(contractId, "合同标识");
            Objects.requireNonNull(terms, "合同条款");
            Objects.requireNonNull(status, "合同状态");
            appliedByTranche = List.copyOf(appliedByTranche);
            invoices = List.copyOf(invoices);
            if (completedUnits < 0 || acceptedUnits < 0 || acceptedUnits > completedUnits
                    || completedUnits > terms.totalUnits()) {
                throw new IllegalArgumentException("合同进度不合法");
            }
            if (invoicedTranches < 0 || invoicedTranches > terms.trancheUnits().length) {
                throw new IllegalArgumentException("开票期数不合法");
            }
            if (appliedByTranche.size() > terms.trancheUnits().length
                    || appliedByTranche.size() < invoicedTranches) {
                throw new IllegalArgumentException("冲抵记录必须覆盖已开票期次且不超过阶段数");
            }
            long prefix = 0;
            for (int i = 0; i < appliedByTranche.size(); i++) {
                prefix = Math.addExact(prefix, terms.trancheUnits()[i]);
            }
            if (acceptedUnits != prefix) {
                throw new IllegalArgumentException("已验收单位必须等于已验收阶段前缀和");
            }
            if (invoices.size() != invoicedTranches) {
                throw new IllegalArgumentException("开票记录数必须等于已开票期数");
            }
        }

        /** @return 该期单位数 */
        public long trancheUnits(int index) {
            return terms.trancheUnits()[index];
        }

        /** @return 前 index 期（含）累计单位数 */
        public long cumulativeUnits(int index) {
            long sum = 0;
            long[] units = terms.trancheUnits();
            for (int i = 0; i <= index; i++) {
                sum = Math.addExact(sum, units[i]);
            }
            return sum;
        }

        /** @return 尚未投入/完工的单位数 */
        public long remainingUnits() {
            return terms.totalUnits() - completedUnits;
        }
    }

    /**
     * 履约投入任务。
     *
     * @param workId        任务标识，运行内不复用
     * @param contractId    合同标识
     * @param units         投入单位
     * @param startedAt     开始模拟毫秒
     * @param completesAt   完工模拟毫秒（开始 + 单位数×unitMillis）
     * @param totalCost     本任务履约成本
     * @param laborCost     其中付现部分
     * @param acceptedUnits 已被验收消耗的单位
     * @param remainingCost 尚未结转的账面成本
     * @param status        状态
     */
    public record WorkOrder(String workId, String contractId, long units, long startedAt,
                            long completesAt, BigDecimal totalCost, BigDecimal laborCost,
                            long acceptedUnits, BigDecimal remainingCost, WorkStatus status) {

        /** 校验数量、时间、成本与消耗进度。 */
        public WorkOrder {
            requireText(workId, "开工任务标识");
            requireText(contractId, "合同标识");
            if (units <= 0 || startedAt < 0 || completesAt <= startedAt) {
                throw new IllegalArgumentException("开工数量或时间不合法");
            }
            totalCost = CashLedger.money(totalCost);
            laborCost = CashLedger.money(laborCost);
            remainingCost = CashLedger.money(remainingCost);
            if (totalCost.signum() < 0 || laborCost.signum() < 0
                    || laborCost.compareTo(totalCost) > 0 || remainingCost.signum() < 0
                    || remainingCost.compareTo(totalCost) > 0) {
                throw new IllegalArgumentException("开工成本不合法");
            }
            if (acceptedUnits < 0 || acceptedUnits > units) {
                throw new IllegalArgumentException("已验收单位不合法");
            }
            Objects.requireNonNull(status, "开工状态");
            if (status == WorkStatus.IN_PROGRESS && acceptedUnits != 0) {
                throw new IllegalArgumentException("未完工任务不得已被验收");
            }
            if (status == WorkStatus.CANCELLED && (acceptedUnits != 0
                    || remainingCost.signum() != 0)) {
                throw new IllegalArgumentException("取消任务不得保留已验收单位或未结转成本");
            }
        }
    }

    /**
     * 单合同债权与损益分层。
     *
     * @param contractAsset      未开票合同资产
     * @param contractLiability  合同负债（预收）
     * @param contractCost       未结转履约成本
     * @param arCurrent          到期应收
     * @param arRetention        未到期质保应收
     * @param allowance          减值准备
     * @param memo               备查权利（账面 0）
     * @param revenue            累计收入
     * @param cogs               累计销售成本
     * @param impairmentLoss     累计减值损失
     * @param recoveryIncome     累计收回收益
     * @param terminationLoss    累计终止损失
     * @param waiverLoss         累计豁免损失
     */
    public record Claim(BigDecimal contractAsset, BigDecimal contractLiability,
                        BigDecimal contractCost, BigDecimal arCurrent, BigDecimal arRetention,
                        BigDecimal allowance, BigDecimal memo, BigDecimal revenue,
                        BigDecimal cogs, BigDecimal impairmentLoss, BigDecimal recoveryIncome,
                        BigDecimal terminationLoss, BigDecimal waiverLoss) {

        /** 全零债权。 */
        public static Claim empty() {
            return new Claim(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO,
                    ZERO, ZERO, ZERO);
        }

        /** 校验金额非负、精确到分。 */
        public Claim {
            contractAsset = CashLedger.money(contractAsset);
            contractLiability = CashLedger.money(contractLiability);
            contractCost = CashLedger.money(contractCost);
            arCurrent = CashLedger.money(arCurrent);
            arRetention = CashLedger.money(arRetention);
            allowance = CashLedger.money(allowance);
            memo = CashLedger.money(memo);
            revenue = CashLedger.money(revenue);
            cogs = CashLedger.money(cogs);
            impairmentLoss = CashLedger.money(impairmentLoss);
            recoveryIncome = CashLedger.money(recoveryIncome);
            terminationLoss = CashLedger.money(terminationLoss);
            waiverLoss = CashLedger.money(waiverLoss);
            if (contractAsset.signum() < 0 || contractLiability.signum() < 0
                    || contractCost.signum() < 0 || arCurrent.signum() < 0
                    || arRetention.signum() < 0 || allowance.signum() < 0
                    || memo.signum() < 0 || revenue.signum() < 0 || cogs.signum() < 0
                    || impairmentLoss.signum() < 0 || recoveryIncome.signum() < 0
                    || terminationLoss.signum() < 0 || waiverLoss.signum() < 0) {
                throw new IllegalArgumentException("债权金额不能为负");
            }
            if (allowance.compareTo(arCurrent.add(arRetention)) > 0) {
                throw new IllegalArgumentException("减值准备不得超过应收毛额");
            }
        }

        /** @return 应收毛额 */
        public BigDecimal arGross() {
            return CashLedger.money(arCurrent.add(arRetention));
        }

        /** @return 净债权（毛额 − 减值准备 + 合同资产） */
        public BigDecimal netClaim() {
            return CashLedger.money(arGross().subtract(allowance).add(contractAsset));
        }
    }

    /**
     * 不可变纯核状态。
     *
     * @param config          账户绑定与实验参数
     * @param contracts       合同，按标识索引
     * @param workOrders      开工任务，按标识索引
     * @param claims          单合同债权分层
     * @param payable         供应商应付
     * @param initialEquity   开局权益
     * @param initialWorldCash 开局世界现金
     * @param usedIds         合同/任务标识集合
     * @param commandContents 命令内容索引
     * @param commandOutcomes 命令结果索引
     */
    public record State(Config config, Map<String, Contract> contracts,
                        Map<String, WorkOrder> workOrders, Map<String, Claim> claims,
                        BigDecimal payable, BigDecimal initialEquity, BigDecimal initialWorldCash,
                        Set<String> usedIds, Map<String, String> commandContents,
                        Map<String, String> commandOutcomes) {

        /** 深拷贝集合并校验索引一致性。 */
        public State {
            Objects.requireNonNull(config, "配置");
            contracts = Collections.unmodifiableMap(new LinkedHashMap<>(contracts));
            workOrders = Collections.unmodifiableMap(new LinkedHashMap<>(workOrders));
            claims = Collections.unmodifiableMap(new LinkedHashMap<>(claims));
            payable = CashLedger.money(payable);
            initialEquity = CashLedger.money(initialEquity);
            initialWorldCash = CashLedger.money(initialWorldCash);
            // 集合语义：已用标识按字典序保存，序列化与逐帧重演比较不依赖插入/哈希顺序
            usedIds = Collections.unmodifiableSet(new TreeSet<>(usedIds));
            commandContents = Collections.unmodifiableMap(new TreeMap<>(commandContents));
            commandOutcomes = Collections.unmodifiableMap(new TreeMap<>(commandOutcomes));
            if (!contracts.keySet().equals(claims.keySet())) {
                throw new IllegalArgumentException("合同与债权索引不一致");
            }
            Set<String> expected = new LinkedHashSet<>(contracts.keySet());
            for (Map.Entry<String, WorkOrder> entry : workOrders.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().workId())) {
                    throw new IllegalArgumentException("开工任务索引键不一致");
                }
                if (!contracts.containsKey(entry.getValue().contractId())) {
                    throw new IllegalArgumentException("开工任务指向未知合同");
                }
                expected.add(entry.getKey());
            }
            for (Map.Entry<String, Contract> entry : contracts.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().contractId())) {
                    throw new IllegalArgumentException("合同索引键不一致");
                }
            }
            if (!expected.equals(usedIds)) {
                throw new IllegalArgumentException("已用标识必须与合同/任务标识一致");
            }
            if (!commandContents.keySet().equals(commandOutcomes.keySet())) {
                throw new IllegalArgumentException("命令索引不一致");
            }
            for (Map.Entry<String, Contract> entry : contracts.entrySet()) {
                Contract contract = entry.getValue();
                Claim claim = claims.get(entry.getKey());
                BigDecimal outstandingCurrent = ZERO;
                BigDecimal outstandingRetention = ZERO;
                for (Invoice invoice : contract.invoices()) {
                    outstandingCurrent = outstandingCurrent.add(invoice.currentOutstanding());
                    outstandingRetention = outstandingRetention
                            .add(invoice.retentionOutstanding());
                }
                if (outstandingCurrent.compareTo(claim.arCurrent()) != 0
                        || outstandingRetention.compareTo(claim.arRetention()) != 0) {
                    throw new IllegalArgumentException("开票未收与应收科目不一致");
                }
                BigDecimal remainingCost = ZERO;
                boolean inProgress = false;
                for (WorkOrder order : workOrders.values()) {
                    if (!order.contractId().equals(entry.getKey())) {
                        continue;
                    }
                    remainingCost = remainingCost.add(order.remainingCost());
                    inProgress |= order.status() == WorkStatus.IN_PROGRESS;
                }
                if (remainingCost.compareTo(claim.contractCost()) != 0) {
                    throw new IllegalArgumentException("履约成本与工单未结转成本不一致");
                }
                if (contract.status() == ContractStatus.TERMINATED
                        && (inProgress || claim.contractCost().signum() != 0)) {
                    throw new IllegalArgumentException("已终止合同不得留有在途任务或履约成本");
                }
            }
            if (payable.signum() < 0 || initialEquity.signum() < 0
                    || initialWorldCash.signum() < 0) {
                throw new IllegalArgumentException("公司级金额不能为负");
            }
        }
    }

    /**
     * 纯候选结果；拒绝时业务状态与账户表均保持原样（仅记录命令索引以便重放）。
     *
     * @param accepted 业务是否成功
     * @param reason   结果代码
     * @param state    候选状态
     * @param accounts 候选账户表
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
     * <p>业务状态与资金三态来自同一次候选：拒绝时三态经济字段与入参相同（资金层只可能多一条
     * 拒绝回执），调用方不得只提交其中一部分，也不得把旧三态与本次业务状态拼在一起。</p>
     *
     * @param accepted 业务是否成功
     * @param reason   结果代码
     * @param state    候选业务状态
     * @param funds    候选资金三态（现金 + 银行 + 托管明细）
     */
    public record FundOutcome(boolean accepted, String reason, State state, FundState funds) {

        /** 校验结果码与两者非空。 */
        public FundOutcome {
            Objects.requireNonNull(reason, "结果代码");
            Objects.requireNonNull(state, "候选状态");
            Objects.requireNonNull(funds, "候选资金三态");
        }
    }

    /**
     * 经营报表。
     *
     * @param companyCash     公司现金
     * @param arCurrent       到期应收
     * @param arRetention     未到期质保应收
     * @param arGross         应收毛额
     * @param allowance       减值准备
     * @param contractAsset   合同资产
     * @param contractCost    履约成本
     * @param contractLiability 合同负债
     * @param payable         应付
     * @param memo            备查权利
     * @param netClaim        净债权
     * @param obligation      推导的客户合同义务（毛额+合同资产+备查）
     * @param revenue         收入
     * @param cogs            销售成本
     * @param impairmentLoss  减值损失
     * @param recoveryIncome  收回收益
     * @param terminationLoss 终止损失
     * @param waiverLoss      豁免损失
     * @param profit          累计损益
     * @param equity          期末权益
     * @param worldCash       世界现金合计
     * @param balanced        资产=负债+权益且世界现金守恒
     */
    public record Report(BigDecimal companyCash, BigDecimal arCurrent, BigDecimal arRetention,
                         BigDecimal arGross, BigDecimal allowance, BigDecimal contractAsset,
                         BigDecimal contractCost, BigDecimal contractLiability,
                         BigDecimal payable, BigDecimal memo, BigDecimal netClaim,
                         BigDecimal obligation, BigDecimal revenue, BigDecimal cogs,
                         BigDecimal impairmentLoss, BigDecimal recoveryIncome,
                         BigDecimal terminationLoss, BigDecimal waiverLoss, BigDecimal profit,
                         BigDecimal equity, BigDecimal worldCash, boolean balanced) {
    }

    /**
     * 外部资本与分红的只读调整上下文。
     *
     * <p>本类型不持久、不建立第二事实表、不重设开局权益；未来桥必须从真实
     * {@code CAPITAL_FLOW_V1} 与分红事件逐帧重算后传入，纯核不据此授予划款权。</p>
     *
     * @param netExternalCapital 净外部资本（净注资为正、净退还为负）
     * @param declaredDividends  累计已宣告分红，非负
     * @param unpaidDividends    未付分红，非负且不超过已宣告
     * @param externalAssets     组合派生资产（存款 + 应收存款利息 + 证券成本）；旧档为 0
     * @param externalLiabilities 组合派生负债（借款本金 + 应计未付利息）；旧档为 0
     * @param externalEquity     组合派生权益（已实现投资损益 + 利息净额 + 开局证券成本）；旧档为 0
     */
    public record ReportContext(BigDecimal netExternalCapital, BigDecimal declaredDividends,
                                BigDecimal unpaidDividends, BigDecimal externalAssets,
                                BigDecimal externalLiabilities, BigDecimal externalEquity) {

        /**
         * 旧三参上下文：无组合派生项，口径与历史逐字一致。
         *
         * @param netExternalCapital 净外部资本
         * @param declaredDividends  累计已宣告分红
         * @param unpaidDividends    未付分红
         */
        public ReportContext(BigDecimal netExternalCapital, BigDecimal declaredDividends,
                             BigDecimal unpaidDividends) {
            this(netExternalCapital, declaredDividends, unpaidDividends, ZERO, ZERO, ZERO);
        }

        /** 校验金额符号、精度与未付不超过宣告。 */
        public ReportContext {
            netExternalCapital = CashLedger.money(netExternalCapital);
            declaredDividends = CashLedger.money(declaredDividends);
            unpaidDividends = CashLedger.money(unpaidDividends);
            externalAssets = CashLedger.money(externalAssets);
            externalLiabilities = CashLedger.money(externalLiabilities);
            externalEquity = CashLedger.money(externalEquity);
            if (declaredDividends.signum() < 0 || unpaidDividends.signum() < 0) {
                throw new IllegalArgumentException("宣告分红与未付分红不能为负");
            }
            if (unpaidDividends.compareTo(declaredDividends) > 0) {
                throw new IllegalArgumentException("未付分红不得超过已宣告分红");
            }
        }

        /** @return 无外部资本、无分红的零上下文 */
        public static ReportContext none() {
            return new ReportContext(ZERO, ZERO, ZERO);
        }
    }

    // ------------------------------------------------------------------ 纯核动作

    /**
     * 开局：校验账户结构并记录初始权益与世界现金。
     *
     * @param config   配置
     * @param accounts 账户表
     * @return 初始状态
     * @throws IllegalArgumentException 账户结构不合法
     */
    public static State open(Config config, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(config, "配置");
        requireAccounts(config, accounts);
        BigDecimal cash = accounts.get(config.companyAccountId()).cash();
        return new State(config, Map.of(), Map.of(), Map.of(), ZERO, cash, worldCash(accounts),
                Set.of(), Map.of(), Map.of());
    }

    /**
     * 开局（受信纯数字，trusted）：存款余额由调用方给出，模型无法证明它与银行账簿一致。
     *
     * <p>旧两参 {@link #open(Config, Map)} 完全保留（只认现金）；本重载只用于纯数字夹具。
     * 接 World 时必须改用 {@link #open(Config, Map, FundState)}，从真实账簿派生，
     * 否则公司其它银行存款会被漏计。后续存取款、付款都不重置初始权益。</p>
     *
     * @param config         配置
     * @param accounts       账户表
     * @param openingDeposit 调用方声明的存款余额（非负、两位小数；无存款传 0）
     * @return 初始状态
     * @throws IllegalArgumentException 账户结构或存款金额不合法
     */
    public static State open(Config config, Map<String, CashAccount> accounts,
                             BigDecimal openingDeposit) {
        Objects.requireNonNull(config, "配置");
        requireAccounts(config, accounts);
        BigDecimal deposit = CashLedger.money(openingDeposit);
        if (deposit.signum() < 0) {
            throw new IllegalArgumentException("开局存款不能为负");
        }
        BigDecimal cash = accounts.get(config.companyAccountId()).cash();
        return new State(config, Map.of(), Map.of(), Map.of(), ZERO,
                CashLedger.money(cash.add(deposit)), worldCash(accounts), Set.of(), Map.of(),
                Map.of());
    }

    /**
     * 开局（从真实账簿派生）：现金、公司在本行**全部**存款、作为客户持有的托管受益权各计一次。
     *
     * <p>与受信纯数字版本的区别只在数据来源：这里按公司 ownerId 在档案里找到唯一主体 key，
     * 再逐行累加所有银行的存款科目（不是只数某个付款桶），并叠加它在各托管经营人处的客户明细；
     * 托管经营人自己的池存款属于该经营人，绝不重复计成客户的受益权。存款/存取款/付款都不重置
     * 初始权益。</p>
     *
     * @param config   配置
     * @param accounts 账户表
     * @param funds    当前资金三态（真实账簿）
     * @return 初始状态
     * @throws IllegalArgumentException 账户结构不合法
     */
    public static State open(Config config, Map<String, CashAccount> accounts, FundState funds) {
        Objects.requireNonNull(config, "配置");
        Objects.requireNonNull(funds, "资金三态");
        requireAccounts(config, accounts);
        BigDecimal cash = accounts.get(config.companyAccountId()).cash();
        BigDecimal deposits = depositsOf(funds, config.companyOwnerId());
        BigDecimal beneficial = beneficialOf(funds, config.companyOwnerId());
        return new State(config, Map.of(), Map.of(), Map.of(), ZERO,
                CashLedger.money(cash.add(deposits).add(beneficial)), worldCash(accounts),
                Set.of(), Map.of(), Map.of());
    }

    /**
     * 公司在本行**全部**银行存款合计（含它作为托管经营人持有的池存款）。
     *
     * @param funds        资金三态
     * @param companyOwnerId 公司真实财产主体
     * @return 存款合计；档案里找不到公司主体时为零
     */
    private static BigDecimal depositsOf(FundState funds, String companyOwnerId) {
        String partyKey = companyPartyOf(funds, companyOwnerId);
        if (partyKey == null) {
            return ZERO;
        }
        BankingProfile.PartyRef party =
                funds.bank().chart().profile().parties().get(partyKey);
        if (party.depositGl() == null) {
            return ZERO;
        }
        BigDecimal total = ZERO;
        for (String bankKey : funds.bank().chart().banks()) {
            total = total.add(funds.deposit(bankKey, partyKey));
        }
        return CashLedger.money(total);
    }

    /**
     * 公司作为客户在各托管经营人处的受益权合计（池资产属于经营人，不计成客户受益权）。
     *
     * @param funds        资金三态
     * @param companyOwnerId 公司真实财产主体
     * @return 受益权合计；档案里找不到公司主体时为零
     */
    private static BigDecimal beneficialOf(FundState funds, String companyOwnerId) {
        String partyKey = companyPartyOf(funds, companyOwnerId);
        if (partyKey == null) {
            return ZERO;
        }
        BigDecimal total = ZERO;
        for (String custodian : funds.bank().chart().profile().custodians().keySet()) {
            total = total.add(funds.book().detailOf(custodian, partyKey));
        }
        return CashLedger.money(total);
    }

    /** 由 ownerId 反查档案里唯一的主体 key（档案保证 owner 唯一）；找不到返回 null。 */
    private static String companyPartyOf(FundState funds, String companyOwnerId) {
        for (Map.Entry<String, BankingProfile.PartyRef> entry
                : new TreeMap<>(funds.bank().chart().profile().parties()).entrySet()) {
            if (entry.getValue().ownerId().equals(companyOwnerId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 签约：只登记合同，不动任何金额科目。
     *
     * @param state     当前状态
     * @param commandId 命令标识
     * @param actorOwnerId 发起人
     * @param spec      合同输入
     * @param now       当前模拟毫秒
     * @param accounts  账户表
     * @return 候选结果
     */
    public static Outcome sign(State state, String commandId, String actorOwnerId,
                               ContractSpec spec, long now, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        Objects.requireNonNull(spec, "合同输入");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("SIGN", commandId, spec.contractId(), spec.unitPrice(),
                spec.unitCost(), spec.terms().advanceBp(), spec.terms().retentionBp(),
                spec.terms().netDays(), spec.terms().retentionNetDays(),
                spec.terms().mode(), joinUnits(spec.terms().trancheUnits()));
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        if (state.usedIds().contains(spec.contractId())) {
            return finish(draft, false, REASON_ID_REUSED, accounts, commandId, content);
        }
        Contract contract = new Contract(spec.contractId(), spec.unitPrice(), spec.unitCost(),
                spec.terms(), now, 0, 0, 0, ZERO, ZERO, List.of(), List.of(),
                ContractStatus.ACTIVE);
        draft.contracts.put(spec.contractId(), contract);
        draft.claims.put(spec.contractId(), Claim.empty());
        draft.usedIds.add(spec.contractId());
        return finish(draft, true, REASON_SIGNED, accounts, commandId, content);
    }

    /**
     * 收预收：现金双边转移并形成合同负债；超过条款上限拒绝。
     *
     * @param state     当前状态
     * @param commandId 命令标识
     * @param actorOwnerId 发起人
     * @param contractId 合同标识
     * @param amount    金额
     * @param now       当前模拟毫秒
     * @param accounts  账户表
     * @return 候选结果
     */
    public static Outcome receiveAdvance(State state, String commandId, String actorOwnerId,
                                         String contractId, BigDecimal amount, long now,
                                         Map<String, CashAccount> accounts) {
        return advance(state, commandId, actorOwnerId, contractId, amount, now,
                Funding.cash(accounts)).outcome();
    }

    /**
     * 客户预收（显式资金腿）：钱从客户真实资金桶进公司真实资金桶，义务与收入口径不变。
     *
     * <p>与现金版 {@link #receiveAdvance(State, String, String, String, BigDecimal, long, Map)}
     * 的差别只有“钱怎么走”：合同必须存在且生效、金额为正、累计预收不超过合同条款上限，
     * 收到即增加合同负债（**不确认收入**）、同时累加合同的已收预收额。付款方必须是配置里
     * 客户账户的真实主体，收款方必须是公司；两者都由当前资金三态派生，不采信任何自报 owner。</p>
     *
     * @param state        当前状态
     * @param commandId    命令标识（同时是资金腿幂等标识）
     * @param actorOwnerId 发起人
     * @param contractId   合同标识
     * @param amount       预收金额
     * @param now          当前模拟毫秒
     * @param funds        当前资金三态（显式传入，不得复用旧结果）
     * @param payer        资金腿（只含路由与档案，不含账簿）
     * @return 候选结果（业务状态 + 资金三态）
     */
    public static FundOutcome receiveAdvance(State state, String commandId, String actorOwnerId,
                                             String contractId, BigDecimal amount, long now,
                                             FundState funds, FundPayer payer) {
        return advance(state, commandId, actorOwnerId, contractId, amount, now,
                Funding.explicit(funds, payer)).funded();
    }

    private static Settlement advance(State state, String commandId, String actorOwnerId,
                                      String contractId, BigDecimal amount, long now,
                                      Funding funding) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return funding.at(state, false, REASON_NOT_AUTHORIZED);
        }
        requireAccounts(state.config(), funding.accounts());
        String content = funding.content("ADVANCE", commandId, contractId, amount);
        Settlement replay = funding.replay(state, commandId, content);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return finishLeg(funding, draft, false, REASON_UNKNOWN_CONTRACT, null, commandId,
                    content);
        }
        BigDecimal money = moneyOrNull(amount);
        if (money == null || money.signum() <= 0) {
            return finishLeg(funding, draft, false, REASON_INVALID_AMOUNT, null, commandId, content);
        }
        if (contract.status() != ContractStatus.ACTIVE) {
            return finishLeg(funding, draft, false, REASON_CONTRACT_NOT_ACTIVE, null,
                    commandId, content);
        }
        BigDecimal cap = CashLedger.money(contractValue(contract)
                .multiply(BigDecimal.valueOf(contract.terms().advanceBp()))
                .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP));
        BigDecimal received = contract.advanceReceived().add(money);
        if (received.compareTo(cap) > 0) {
            return finishLeg(funding, draft, false, REASON_ADVANCE_EXCEEDS_TERMS, null,
                    commandId, content);
        }
        Leg leg = funding.pay(commandId, money,
                funding.accounts().get(state.config().customerAccountId()).ownerId(),
                state.config().customerAccountId(), state.config().companyOwnerId(),
                state.config().companyAccountId());
        if (!leg.accepted()) {
            return finishLeg(funding, draft, false, leg.reason(), null, commandId, content);
        }
        Claim claim = state.claims().get(contractId);
        draft.claims.put(contractId, new Claim(claim.contractAsset(),
                claim.contractLiability().add(money), claim.contractCost(),
                claim.arCurrent(), claim.arRetention(), claim.allowance(), claim.memo(),
                claim.revenue(), claim.cogs(), claim.impairmentLoss(),
                claim.recoveryIncome(), claim.terminationLoss(), claim.waiverLoss()));
        draft.contracts.put(contractId, copyContract(contract, contract.completedUnits(),
                contract.acceptedUnits(), contract.invoicedTranches(), received,
                contract.retentionWithheld(), contract.appliedByTranche(),
                contract.invoices(), contract.status()));
        return finishLeg(funding, draft, true, REASON_ADVANCE_RECEIVED, leg, commandId, content);
    }

    /**
     * 履约投入：按正耗时排程并立即付现人工、计提分包应付；不动收入。
     *
     * @param state     当前状态
     * @param commandId 命令标识
     * @param actorOwnerId 发起人
     * @param contractId 合同标识
     * @param workId    开工任务标识
     * @param units     投入单位
     * @param now       当前模拟毫秒
     * @param accounts  账户表
     * @return 候选结果
     */
    public static Outcome investWork(State state, String commandId, String actorOwnerId,
                                     String contractId, String workId, long units, long now,
                                     Map<String, CashAccount> accounts) {
        return invest(state, commandId, actorOwnerId, contractId, workId, units, now,
                Funding.cash(accounts)).outcome();
    }

    /**
     * 履约投入（显式资金腿）：现场人工从真实资金桶付给作业方，分包部分仍计应付。
     *
     * <p>与现金版 {@link #investWork(State, String, String, String, String, long, long, Map)}
     * 的差别只有人工怎么付：单位上限、合同状态、排程、成本/人工尾差分配、WIP 资本化、
     * 应付累加与命令记录全部逐字相同。人工为零时不动任何桶（不调资金腿）。</p>
     *
     * @param state     当前状态
     * @param commandId 命令标识（同时是资金腿幂等标识）
     * @param actorOwnerId 发起人
     * @param contractId 合同标识
     * @param workId    开工任务标识
     * @param units     投入单位
     * @param now       当前模拟毫秒
     * @param funds     当前资金三态（显式传入，不得复用旧结果）
     * @param payer     资金腿（只含路由与档案，不含账簿）
     * @return 候选结果（业务状态 + 资金三态）
     */
    public static FundOutcome investWork(State state, String commandId, String actorOwnerId,
                                         String contractId, String workId, long units, long now,
                                         FundState funds, FundPayer payer) {
        return invest(state, commandId, actorOwnerId, contractId, workId, units, now,
                Funding.explicit(funds, payer)).funded();
    }

    private static Settlement invest(State state, String commandId, String actorOwnerId,
                                     String contractId, String workId, long units, long now,
                                     Funding funding) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireText(workId, "开工任务标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return funding.at(state, false, REASON_NOT_AUTHORIZED);
        }
        requireAccounts(state.config(), funding.accounts());
        String content = funding.content("WORK", commandId, contractId, workId, units);
        Settlement replay = funding.replay(state, commandId, content);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return finishLeg(funding, draft, false, REASON_UNKNOWN_CONTRACT, null, commandId,
                    content);
        }
        if (contract.status() != ContractStatus.ACTIVE) {
            return finishLeg(funding, draft, false, REASON_CONTRACT_NOT_ACTIVE, null, commandId,
                    content);
        }
        if (units <= 0) {
            return finishLeg(funding, draft, false, REASON_INVALID_QUANTITY, null, commandId,
                    content);
        }
        if (state.usedIds().contains(workId)) {
            return finishLeg(funding, draft, false, REASON_ID_REUSED, null, commandId, content);
        }
        long inProgress = 0;
        for (WorkOrder order : state.workOrders().values()) {
            if (order.contractId().equals(contractId)
                    && order.status() == WorkStatus.IN_PROGRESS) {
                inProgress = Math.addExact(inProgress, order.units());
            }
        }
        long available = contract.terms().totalUnits() - contract.completedUnits() - inProgress;
        if (units > available) {
            return finishLeg(funding, draft, false, REASON_EXCEEDS_CONTRACT_UNITS, null,
                    commandId, content);
        }
        long completesAt;
        BigDecimal cost;
        BigDecimal labor;
        try {
            completesAt = Math.addExact(now,
                    Math.multiplyExact(units, state.config().unitMillis()));
            long cumulativeUnits = units;
            BigDecimal allocatedCost = ZERO;
            BigDecimal allocatedLabor = ZERO;
            for (WorkOrder order : state.workOrders().values()) {
                if (order.contractId().equals(contractId)) {
                    cumulativeUnits = Math.addExact(cumulativeUnits, order.units());
                    allocatedCost = allocatedCost.add(order.totalCost());
                    allocatedLabor = allocatedLabor.add(order.laborCost());
                }
            }
            BigDecimal targetCost = round2(contract.unitCost()
                    .multiply(BigDecimal.valueOf(cumulativeUnits)));
            cost = targetCost.subtract(allocatedCost);
            BigDecimal targetLabor = round2(targetCost
                    .multiply(BigDecimal.valueOf(state.config().laborBp()))
                    .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP));
            labor = targetLabor.subtract(allocatedLabor);
        } catch (ArithmeticException overflow) {
            return finishLeg(funding, draft, false, REASON_OVERFLOW, null, commandId, content);
        }
        if (cost.signum() < 0 || labor.signum() < 0 || labor.compareTo(cost) > 0) {
            return finishLeg(funding, draft, false, REASON_ALLOCATION_INVALID, null, commandId,
                    content);
        }
        Leg leg;
        if (labor.signum() > 0) {
            leg = funding.pay(commandId, labor, state.config().companyOwnerId(),
                    state.config().companyAccountId(),
                    funding.accounts().get(state.config().opsAccountId()).ownerId(),
                    state.config().opsAccountId());
            if (!leg.accepted()) {
                return finishLeg(funding, draft, false, leg.reason(), null, commandId, content);
            }
        } else {
            leg = funding.untouched();
        }
        WorkOrder order = new WorkOrder(workId, contractId, units, now, completesAt, cost,
                labor, 0, cost, WorkStatus.IN_PROGRESS);
        draft.workOrders.put(workId, order);
        draft.usedIds.add(workId);
        Claim claim = state.claims().get(contractId);
        draft.claims.put(contractId, new Claim(claim.contractAsset(),
                claim.contractLiability(), claim.contractCost().add(cost), claim.arCurrent(),
                claim.arRetention(), claim.allowance(), claim.memo(), claim.revenue(),
                claim.cogs(), claim.impairmentLoss(), claim.recoveryIncome(),
                claim.terminationLoss(), claim.waiverLoss()));
        draft.payable = state.payable().add(cost.subtract(labor));
        return finishLeg(funding, draft, true, REASON_WORK_STARTED, leg, commandId, content);
    }

    /**
     * 完工：到达正耗时终点后把单位计入合同已完工数量。
     *
     * @param state     当前状态
     * @param commandId 命令标识
     * @param actorOwnerId 发起人
     * @param workId    开工任务标识
     * @param now       当前模拟毫秒
     * @param accounts  账户表
     * @return 候选结果
     */
    public static Outcome completeWork(State state, String commandId, String actorOwnerId,
                                       String workId, long now, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("COMPLETE", commandId, workId);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        WorkOrder order = state.workOrders().get(workId);
        if (order == null) {
            return finish(draft, false, REASON_UNKNOWN_WORK, accounts, commandId, content);
        }
        Contract contract = state.contracts().get(order.contractId());
        if (contract.status() != ContractStatus.ACTIVE) {
            return finish(draft, false, REASON_CONTRACT_NOT_ACTIVE, accounts, commandId, content);
        }
        if (order.status() != WorkStatus.IN_PROGRESS) {
            return finish(draft, false, REASON_WORK_STATE_INVALID, accounts, commandId, content);
        }
        if (now < order.completesAt()) {
            return finish(draft, false, REASON_WORK_NOT_DUE, accounts, commandId, content);
        }
        draft.workOrders.put(workId, new WorkOrder(order.workId(), order.contractId(),
                order.units(), order.startedAt(), order.completesAt(), order.totalCost(),
                order.laborCost(), order.acceptedUnits(), order.remainingCost(),
                WorkStatus.COMPLETED));
        draft.contracts.put(contract.contractId(), copyContract(contract,
                Math.addExact(contract.completedUnits(), order.units()),
                contract.acceptedUnits(), contract.invoicedTranches(),
                contract.advanceReceived(), contract.retentionWithheld(),
                contract.appliedByTranche(), contract.invoices(), contract.status()));
        return finish(draft, true, REASON_WORK_COMPLETED, accounts, commandId, content);
    }

    /**
     * 验收：按阶段整段确认收入，先冲减该合同合同负债，剩余进入合同资产。
     *
     * <p>下一待验收阶段由已验收单位与该合同已记录阶段派生，独立于开票游标；
     * 允许先验收多个阶段，再按顺序开票。`acceptanceRef` 只是受信桥传入的引用，
     * 本类不验证其真实性，调用方不得把本方法直接暴露给经理或模型。</p>
     *
     * @param state        当前状态
     * @param commandId    命令标识
     * @param actorOwnerId 发起人
     * @param contractId   合同标识
     * @param units        验收单位，必须等于下一阶段单位数
     * @param acceptanceRef 由受信调用者（桥）在核验客户/规则事件后传入的引用；纯核只记录，
     *                      不认证其来源，也不得直接暴露给经理或模型
     * @param now          当前模拟毫秒
     * @param accounts     账户表
     * @return 候选结果
     */
    public static Outcome accept(State state, String commandId, String actorOwnerId,
                                 String contractId, long units, String acceptanceRef, long now,
                                 Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireText(acceptanceRef, "验收凭据");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("ACCEPT", commandId, contractId, units, acceptanceRef);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return finish(draft, false, REASON_UNKNOWN_CONTRACT, accounts, commandId, content);
        }
        if (contract.status() != ContractStatus.ACTIVE) {
            return finish(draft, false, REASON_CONTRACT_NOT_ACTIVE, accounts, commandId, content);
        }
        long[] plan = contract.terms().trancheUnits();
        int index = contract.appliedByTranche().size();
        if (index >= plan.length) {
            return finish(draft, false, REASON_TRANCHE_MISMATCH, accounts, commandId, content);
        }
        long prefix = index == 0 ? 0 : contract.cumulativeUnits(index - 1);
        if (contract.acceptedUnits() != prefix || units != plan[index]) {
            return finish(draft, false, REASON_TRANCHE_MISMATCH, accounts, commandId, content);
        }
        long remainingCompleted = contract.completedUnits() - contract.acceptedUnits();
        if (units > remainingCompleted) {
            return finish(draft, false, REASON_EXCEEDS_COMPLETED, accounts, commandId, content);
        }
        BigDecimal gross = round2(contract.unitPrice().multiply(BigDecimal.valueOf(units)));
        BigDecimal cogs = ZERO;
        long toTake = units;
        List<String> orderIds = new ArrayList<>(state.workOrders().keySet());
        for (String orderId : orderIds) {
            if (toTake == 0) {
                break;
            }
            WorkOrder order = draft.workOrders.get(orderId);
            if (order == null || !order.contractId().equals(contractId)
                    || order.status() != WorkStatus.COMPLETED) {
                continue;
            }
            long left = order.units() - order.acceptedUnits();
            if (left <= 0) {
                continue;
            }
            long take = Math.min(left, toTake);
            BigDecimal cost = take == left ? order.remainingCost()
                    : round2(order.remainingCost().multiply(BigDecimal.valueOf(take))
                    .divide(BigDecimal.valueOf(left), 2, RoundingMode.HALF_UP));
            cogs = cogs.add(cost);
            draft.workOrders.put(orderId, new WorkOrder(order.workId(), order.contractId(),
                    order.units(), order.startedAt(), order.completesAt(), order.totalCost(),
                    order.laborCost(), Math.addExact(order.acceptedUnits(), take),
                    order.remainingCost().subtract(cost), order.status()));
            toTake -= take;
        }
        if (toTake != 0) {
            return finish(draft, false, REASON_EXCEEDS_COMPLETED, accounts, commandId, content);
        }
        Claim claim = state.claims().get(contractId);
        BigDecimal applied = claim.contractLiability().min(gross);
        List<BigDecimal> appliedList = new ArrayList<>(contract.appliedByTranche());
        appliedList.add(applied);
        draft.claims.put(contractId, new Claim(
                claim.contractAsset().add(gross.subtract(applied)),
                claim.contractLiability().subtract(applied),
                claim.contractCost().subtract(cogs),
                claim.arCurrent(), claim.arRetention(), claim.allowance(), claim.memo(),
                claim.revenue().add(gross), claim.cogs().add(cogs),
                claim.impairmentLoss(), claim.recoveryIncome(), claim.terminationLoss(),
                claim.waiverLoss()));
        draft.contracts.put(contractId, copyContract(contract, contract.completedUnits(),
                Math.addExact(contract.acceptedUnits(), units),
                contract.invoicedTranches(), contract.advanceReceived(),
                contract.retentionWithheld(), appliedList, contract.invoices(),
                contract.status()));
        return finish(draft, true, REASON_ACCEPTED, accounts, commandId, content);
    }

    /**
     * 阶段开票：把已无条件金额转为应收；质保部分列未到期应收（TIME_ONLY）。
     *
     * @param state     当前状态
     * @param commandId 命令标识
     * @param actorOwnerId 发起人
     * @param contractId 合同标识
     * @param now       当前模拟毫秒
     * @param accounts  账户表
     * @return 候选结果
     */
    public static Outcome invoice(State state, String commandId, String actorOwnerId,
                                  String contractId, long now, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("INVOICE", commandId, contractId);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return finish(draft, false, REASON_UNKNOWN_CONTRACT, accounts, commandId, content);
        }
        if (contract.status() != ContractStatus.ACTIVE) {
            return finish(draft, false, REASON_CONTRACT_NOT_ACTIVE, accounts, commandId, content);
        }
        int index = contract.invoicedTranches();
        long[] plan = contract.terms().trancheUnits();
        if (index >= plan.length || index >= contract.appliedByTranche().size()) {
            return finish(draft, false, REASON_NOT_ACCEPTED, accounts, commandId, content);
        }
        if (contract.acceptedUnits() < contract.cumulativeUnits(index)) {
            return finish(draft, false, REASON_NOT_ACCEPTED, accounts, commandId, content);
        }
        Claim claim = state.claims().get(contractId);
        long units = plan[index];
        BigDecimal gross = round2(contract.unitPrice().multiply(BigDecimal.valueOf(units)));
        BigDecimal applied = contract.appliedByTranche().get(index);
        BigDecimal uncond = gross.subtract(applied);
        if (uncond.signum() < 0 || claim.contractAsset().compareTo(uncond) < 0) {
            return finish(draft, false, REASON_INVOICE_ORDER, accounts, commandId, content);
        }
        BigDecimal retentionCap = CashLedger.money(contractValue(contract)
                .multiply(BigDecimal.valueOf(contract.terms().retentionBp()))
                .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP));
        BigDecimal retentionLeft = retentionCap.subtract(contract.retentionWithheld());
        BigDecimal retention = uncond.min(retentionLeft.max(ZERO));
        long dueAt;
        long retentionDueAt;
        try {
            dueAt = Math.addExact(now,
                    Math.multiplyExact((long) contract.terms().netDays(), DAY_MILLIS));
            retentionDueAt = Math.addExact(now,
                    Math.multiplyExact((long) contract.terms().retentionNetDays(), DAY_MILLIS));
        } catch (ArithmeticException overflow) {
            return finish(draft, false, REASON_OVERFLOW, accounts, commandId, content);
        }
        Invoice invoice = new Invoice(index, units, gross, applied, retention, dueAt,
                retentionDueAt, uncond.subtract(retention), retention);
        List<Invoice> invoices = new ArrayList<>(contract.invoices());
        invoices.add(invoice);
        draft.claims.put(contractId, new Claim(
                claim.contractAsset().subtract(uncond), claim.contractLiability(),
                claim.contractCost(), claim.arCurrent().add(uncond.subtract(retention)),
                claim.arRetention().add(retention), claim.allowance(), claim.memo(),
                claim.revenue(), claim.cogs(), claim.impairmentLoss(),
                claim.recoveryIncome(), claim.terminationLoss(), claim.waiverLoss()));
        draft.contracts.put(contractId, copyContract(contract, contract.completedUnits(),
                contract.acceptedUnits(), index + 1, contract.advanceReceived(),
                contract.retentionWithheld().add(retention), contract.appliedByTranche(),
                invoices, contract.status()));
        return finish(draft, true, REASON_INVOICED, accounts, commandId, content);
    }

    /**
     * 到期应收回款；未到期拒绝，超额拒绝，付款方现金不足整笔不变。
     *
     * @param state      当前状态
     * @param commandId  命令标识
     * @param actorOwnerId 发起人
     * @param contractId 合同标识
     * @param trancheIndex 期次
     * @param amount     金额
     * @param now        当前模拟毫秒
     * @param accounts   账户表
     * @return 候选结果
     */
    public static Outcome collectDue(State state, String commandId, String actorOwnerId,
                                     String contractId, int trancheIndex, BigDecimal amount,
                                     long now, Map<String, CashAccount> accounts) {
        return collect(state, commandId, actorOwnerId, contractId, trancheIndex, amount, now,
                Funding.cash(accounts), false).outcome();
    }

    /**
     * 到期应收回款（显式资金腿）：钱从客户真实资金桶进公司真实资金桶。
     *
     * <p>收入已在验收时确认，回款只做资产置换（AR↓、现金/存款↑），**不再确认收入**。
     * 未到期、超额、主体不符一律整笔拒绝且一分不动。</p>
     *
     * @param state        当前状态
     * @param commandId    命令标识（同时是资金腿幂等标识）
     * @param actorOwnerId 发起人
     * @param contractId   合同标识
     * @param trancheIndex 期次
     * @param amount       金额
     * @param now          当前模拟毫秒
     * @param funds        当前资金三态（显式传入，不得复用旧结果）
     * @param payer        资金腿（只含路由与档案，不含账簿）
     * @return 候选结果（业务状态 + 资金三态）
     */
    public static FundOutcome collectDue(State state, String commandId, String actorOwnerId,
                                         String contractId, int trancheIndex, BigDecimal amount,
                                         long now, FundState funds, FundPayer payer) {
        return collect(state, commandId, actorOwnerId, contractId, trancheIndex, amount, now,
                Funding.explicit(funds, payer), false).funded();
    }

    /**
     * 质保到期回款；到期前拒绝。
     *
     * @param state      当前状态
     * @param commandId  命令标识
     * @param actorOwnerId 发起人
     * @param contractId 合同标识
     * @param trancheIndex 期次
     * @param amount     金额
     * @param now        当前模拟毫秒
     * @param accounts   账户表
     * @return 候选结果
     */
    public static Outcome collectRetention(State state, String commandId, String actorOwnerId,
                                           String contractId, int trancheIndex, BigDecimal amount,
                                           long now, Map<String, CashAccount> accounts) {
        return collect(state, commandId, actorOwnerId, contractId, trancheIndex, amount, now,
                Funding.cash(accounts), true).outcome();
    }

    /**
     * 质保到期回款（显式资金腿）：同应收回款，只动质保段，收入不二次确认。
     *
     * @param state        当前状态
     * @param commandId    命令标识（同时是资金腿幂等标识）
     * @param actorOwnerId 发起人
     * @param contractId   合同标识
     * @param trancheIndex 期次
     * @param amount       金额
     * @param now          当前模拟毫秒
     * @param funds        当前资金三态（显式传入，不得复用旧结果）
     * @param payer        资金腿（只含路由与档案，不含账簿）
     * @return 候选结果（业务状态 + 资金三态）
     */
    public static FundOutcome collectRetention(State state, String commandId, String actorOwnerId,
                                               String contractId, int trancheIndex,
                                               BigDecimal amount, long now, FundState funds,
                                               FundPayer payer) {
        return collect(state, commandId, actorOwnerId, contractId, trancheIndex, amount, now,
                Funding.explicit(funds, payer), true).funded();
    }

    private static Settlement collect(State state, String commandId, String actorOwnerId,
                                      String contractId, int trancheIndex, BigDecimal amount,
                                      long now, Funding funding, boolean retention) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return funding.at(state, false, REASON_NOT_AUTHORIZED);
        }
        requireAccounts(state.config(), funding.accounts());
        String content = funding.content(retention ? "COLLECT_RETENTION" : "COLLECT_DUE",
                commandId, contractId, trancheIndex, amount);
        Settlement replay = funding.replay(state, commandId, content);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return finishLeg(funding, draft, false, REASON_UNKNOWN_CONTRACT, null, commandId,
                    content);
        }
        Invoice invoice = invoiceAt(contract, trancheIndex);
        if (invoice == null) {
            return finishLeg(funding, draft, false, REASON_INVOICE_ORDER, null, commandId, content);
        }
        BigDecimal money = moneyOrNull(amount);
        if (money == null || money.signum() <= 0) {
            return finishLeg(funding, draft, false, REASON_INVALID_AMOUNT, null, commandId, content);
        }
        long dueAt = retention ? invoice.retentionDueAt() : invoice.dueAt();
        if (now < dueAt) {
            return finishLeg(funding, draft, false, REASON_NOT_DUE, null, commandId, content);
        }
        BigDecimal outstanding = retention ? invoice.retentionOutstanding()
                : invoice.currentOutstanding();
        if (money.compareTo(outstanding) > 0) {
            return finishLeg(funding, draft, false, REASON_OVER_COLLECT, null, commandId, content);
        }
        Leg leg = funding.pay(commandId, money,
                funding.accounts().get(state.config().customerAccountId()).ownerId(),
                state.config().customerAccountId(), state.config().companyOwnerId(),
                state.config().companyAccountId());
        if (!leg.accepted()) {
            return finishLeg(funding, draft, false, leg.reason(), null, commandId, content);
        }
        Claim claim = state.claims().get(contractId);
        draft.claims.put(contractId, new Claim(claim.contractAsset(),
                claim.contractLiability(), claim.contractCost(),
                retention ? claim.arCurrent() : claim.arCurrent().subtract(money),
                retention ? claim.arRetention().subtract(money) : claim.arRetention(),
                claim.allowance(), claim.memo(), claim.revenue(), claim.cogs(),
                claim.impairmentLoss(), claim.recoveryIncome(), claim.terminationLoss(),
                claim.waiverLoss()));
        List<Invoice> invoices = new ArrayList<>(contract.invoices());
        invoices.set(trancheIndex, new Invoice(invoice.trancheIndex(), invoice.units(),
                invoice.gross(), invoice.applied(), invoice.retention(), invoice.dueAt(),
                invoice.retentionDueAt(),
                retention ? invoice.currentOutstanding()
                        : invoice.currentOutstanding().subtract(money),
                retention ? invoice.retentionOutstanding().subtract(money)
                        : invoice.retentionOutstanding()));
        draft.contracts.put(contractId, copyContract(contract, contract.completedUnits(),
                contract.acceptedUnits(), contract.invoicedTranches(),
                contract.advanceReceived(), contract.retentionWithheld(),
                contract.appliedByTranche(), invoices, contract.status()));
        return finishLeg(funding, draft, true,
                retention ? REASON_RETENTION_COLLECTED : REASON_COLLECTED, leg, commandId, content);
    }

    /**
     * 计提减值：只改公司侧准备与损失，不改客户义务。
     *
     * @param state      当前状态
     * @param commandId  命令标识
     * @param actorOwnerId 发起人
     * @param contractId 合同标识
     * @param amount     金额
     * @param now        当前模拟毫秒
     * @param accounts   账户表
     * @return 候选结果
     */
    public static Outcome impair(State state, String commandId, String actorOwnerId,
                                 String contractId, BigDecimal amount, long now,
                                 Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("IMPAIR", commandId, contractId, amount);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return finish(draft, false, REASON_UNKNOWN_CONTRACT, accounts, commandId, content);
        }
        BigDecimal money = moneyOrNull(amount);
        if (money == null || money.signum() <= 0) {
            return finish(draft, false, REASON_INVALID_AMOUNT, accounts, commandId, content);
        }
        Claim claim = state.claims().get(contractId);
        if (claim.allowance().add(money).compareTo(claim.arGross()) > 0) {
            return finish(draft, false, REASON_IMPAIR_EXCEEDS_GROSS, accounts,
                    commandId, content);
        }
        draft.claims.put(contractId, new Claim(claim.contractAsset(),
                claim.contractLiability(), claim.contractCost(), claim.arCurrent(),
                claim.arRetention(), claim.allowance().add(money), claim.memo(),
                claim.revenue(), claim.cogs(), claim.impairmentLoss().add(money),
                claim.recoveryIncome(), claim.terminationLoss(), claim.waiverLoss()));
        return finish(draft, true, REASON_IMPAIRED, accounts, commandId, content);
    }

    /**
     * 核销已减值应收：毛额与准备同减、备查增加；不得超过已计提减值。
     *
     * @param state      当前状态
     * @param commandId  命令标识
     * @param actorOwnerId 发起人
     * @param contractId 合同标识
     * @param amount     金额
     * @param now        当前模拟毫秒
     * @param accounts   账户表
     * @return 候选结果
     */
    public static Outcome writeOff(State state, String commandId, String actorOwnerId,
                                   String contractId, BigDecimal amount, long now,
                                   Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("WRITE_OFF", commandId, contractId, amount);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return finish(draft, false, REASON_UNKNOWN_CONTRACT, accounts, commandId, content);
        }
        BigDecimal money = moneyOrNull(amount);
        if (money == null || money.signum() <= 0) {
            return finish(draft, false, REASON_INVALID_AMOUNT, accounts, commandId, content);
        }
        Claim claim = state.claims().get(contractId);
        if (money.compareTo(claim.allowance()) > 0
                || money.compareTo(claim.arGross()) > 0) {
            return finish(draft, false, REASON_WRITE_OFF_EXCEEDS_ALLOWANCE, accounts,
                    commandId, content);
        }
        BigDecimal fromCurrent = money.min(claim.arCurrent());
        BigDecimal fromRetention = money.subtract(fromCurrent);
        List<Invoice> invoices = reduceInvoices(contract.invoices(), fromCurrent, fromRetention);
        draft.claims.put(contractId, new Claim(claim.contractAsset(),
                claim.contractLiability(), claim.contractCost(),
                claim.arCurrent().subtract(fromCurrent),
                claim.arRetention().subtract(fromRetention),
                claim.allowance().subtract(money), claim.memo().add(money),
                claim.revenue(), claim.cogs(), claim.impairmentLoss(),
                claim.recoveryIncome(), claim.terminationLoss(), claim.waiverLoss()));
        draft.contracts.put(contractId, copyContract(contract, contract.completedUnits(),
                contract.acceptedUnits(), contract.invoicedTranches(),
                contract.advanceReceived(), contract.retentionWithheld(),
                contract.appliedByTranche(), invoices, contract.status()));
        return finish(draft, true, REASON_WRITTEN_OFF, accounts, commandId, content);
    }

    /**
     * 核销后真实回收：现金双边成对，备查与客户义务同减，确认收回收益但不重复确认收入。
     *
     * @param state      当前状态
     * @param commandId  命令标识
     * @param actorOwnerId 发起人
     * @param contractId 合同标识
     * @param amount     金额
     * @param now        当前模拟毫秒
     * @param accounts   账户表
     * @return 候选结果
     */
    public static Outcome recover(State state, String commandId, String actorOwnerId,
                                  String contractId, BigDecimal amount, long now,
                                  Map<String, CashAccount> accounts) {
        return recoverFrom(state, commandId, actorOwnerId, contractId, amount, now,
                Funding.cash(accounts)).outcome();
    }

    /**
     * 已核销备查部分回款（显式资金腿）：钱从客户真实资金桶进公司真实资金桶。
     *
     * <p>只冲减备查金额、累加追回收益（与现金版逐字相同），不重开已核销的应收。
     * 超额与主体不符整笔拒绝，一分不动。</p>
     *
     * @param state        当前状态
     * @param commandId    命令标识（同时是资金腿幂等标识）
     * @param actorOwnerId 发起人
     * @param contractId   合同标识
     * @param amount       金额
     * @param now          当前模拟毫秒
     * @param funds        当前资金三态（显式传入，不得复用旧结果）
     * @param payer        资金腿（只含路由与档案，不含账簿）
     * @return 候选结果（业务状态 + 资金三态）
     */
    public static FundOutcome recover(State state, String commandId, String actorOwnerId,
                                      String contractId, BigDecimal amount, long now,
                                      FundState funds, FundPayer payer) {
        return recoverFrom(state, commandId, actorOwnerId, contractId, amount, now,
                Funding.explicit(funds, payer)).funded();
    }

    private static Settlement recoverFrom(State state, String commandId, String actorOwnerId,
                                          String contractId, BigDecimal amount, long now,
                                          Funding funding) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return funding.at(state, false, REASON_NOT_AUTHORIZED);
        }
        requireAccounts(state.config(), funding.accounts());
        String content = funding.content("RECOVER", commandId, contractId, amount);
        Settlement replay = funding.replay(state, commandId, content);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return finishLeg(funding, draft, false, REASON_UNKNOWN_CONTRACT, null, commandId,
                    content);
        }
        BigDecimal money = moneyOrNull(amount);
        if (money == null || money.signum() <= 0) {
            return finishLeg(funding, draft, false, REASON_INVALID_AMOUNT, null, commandId, content);
        }
        Claim claim = state.claims().get(contractId);
        if (money.compareTo(claim.memo()) > 0) {
            return finishLeg(funding, draft, false, REASON_RECOVERY_EXCEEDS_MEMO, null,
                    commandId, content);
        }
        Leg leg = funding.pay(commandId, money,
                funding.accounts().get(state.config().customerAccountId()).ownerId(),
                state.config().customerAccountId(), state.config().companyOwnerId(),
                state.config().companyAccountId());
        if (!leg.accepted()) {
            return finishLeg(funding, draft, false, leg.reason(), null, commandId, content);
        }
        draft.claims.put(contractId, new Claim(claim.contractAsset(),
                claim.contractLiability(), claim.contractCost(), claim.arCurrent(),
                claim.arRetention(), claim.allowance(), claim.memo().subtract(money),
                claim.revenue(), claim.cogs(), claim.impairmentLoss(),
                claim.recoveryIncome().add(money), claim.terminationLoss(),
                claim.waiverLoss()));
        return finishLeg(funding, draft, true, REASON_RECOVERED, leg, commandId, content);
    }

    /**
     * 显式豁免：独立动作，只解除客户义务；备查部分无损益，应收部分按账面净值计损失。
     *
     * @param state      当前状态
     * @param commandId  命令标识
     * @param actorOwnerId 发起人
     * @param contractId 合同标识
     * @param amount     金额
     * @param reason     豁免原因，必须非空
     * @param now        当前模拟毫秒
     * @param accounts   账户表
     * @return 候选结果
     */
    public static Outcome waive(State state, String commandId, String actorOwnerId,
                                String contractId, BigDecimal amount, String reason, long now,
                                Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireText(reason, "豁免原因");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("WAIVE", commandId, contractId, amount, reason);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return finish(draft, false, REASON_UNKNOWN_CONTRACT, accounts, commandId, content);
        }
        BigDecimal money = moneyOrNull(amount);
        if (money == null || money.signum() <= 0) {
            return finish(draft, false, REASON_INVALID_AMOUNT, accounts, commandId, content);
        }
        Claim claim = state.claims().get(contractId);
        BigDecimal obligation = claim.arGross().add(claim.contractAsset()).add(claim.memo());
        if (money.compareTo(obligation) > 0) {
            return finish(draft, false, REASON_WAIVE_EXCEEDS_OBLIGATION, accounts,
                    commandId, content);
        }
        BigDecimal fromMemo = money.min(claim.memo());
        BigDecimal rest = money.subtract(fromMemo);
        BigDecimal fromAr = rest.min(claim.arGross());
        BigDecimal fromCurrent = fromAr.min(claim.arCurrent());
        BigDecimal fromRetention = fromAr.subtract(fromCurrent);
        BigDecimal fromAsset = rest.subtract(fromAr);
        BigDecimal released = fromAr.min(claim.allowance());
        BigDecimal loss = fromAr.subtract(released).add(fromAsset);
        List<Invoice> invoices = reduceInvoices(contract.invoices(), fromCurrent, fromRetention);
        draft.claims.put(contractId, new Claim(claim.contractAsset().subtract(fromAsset),
                claim.contractLiability(), claim.contractCost(),
                claim.arCurrent().subtract(fromCurrent),
                claim.arRetention().subtract(fromRetention),
                claim.allowance().subtract(released), claim.memo().subtract(fromMemo),
                claim.revenue(), claim.cogs(), claim.impairmentLoss(),
                claim.recoveryIncome(), claim.terminationLoss(),
                claim.waiverLoss().add(loss)));
        draft.contracts.put(contractId, copyContract(contract, contract.completedUnits(),
                contract.acceptedUnits(), contract.invoicedTranches(),
                contract.advanceReceived(), contract.retentionWithheld(),
                contract.appliedByTranche(), invoices, contract.status()));
        return finish(draft, true, REASON_WAIVED, accounts, commandId, content);
    }

    /**
     * 终止剩余履约：未结转履约成本转损失；已验收阶段应收保留，不搬入备查。
     *
     * @param state      当前状态
     * @param commandId  命令标识
     * @param actorOwnerId 发起人
     * @param contractId 合同标识
     * @param reason     终止原因，必须非空
     * @param now        当前模拟毫秒
     * @param accounts   账户表
     * @return 候选结果
     */
    public static Outcome terminate(State state, String commandId, String actorOwnerId,
                                    String contractId, String reason, long now,
                                    Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireText(reason, "终止原因");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("TERMINATE", commandId, contractId, reason);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        Contract contract = state.contracts().get(contractId);
        if (contract == null) {
            return finish(draft, false, REASON_UNKNOWN_CONTRACT, accounts, commandId, content);
        }
        if (contract.status() != ContractStatus.ACTIVE) {
            return finish(draft, false, REASON_CONTRACT_NOT_ACTIVE, accounts, commandId, content);
        }
        Claim claim = state.claims().get(contractId);
        for (Map.Entry<String, WorkOrder> entry : state.workOrders().entrySet()) {
            WorkOrder order = entry.getValue();
            if (order.contractId().equals(contractId)) {
                draft.workOrders.put(entry.getKey(), new WorkOrder(order.workId(),
                        order.contractId(), order.units(), order.startedAt(),
                        order.completesAt(), order.totalCost(), order.laborCost(),
                        order.acceptedUnits(), ZERO,
                        order.status() == WorkStatus.IN_PROGRESS
                                ? WorkStatus.CANCELLED : order.status()));
            }
        }
        draft.claims.put(contractId, new Claim(claim.contractAsset(),
                claim.contractLiability(), ZERO, claim.arCurrent(), claim.arRetention(),
                claim.allowance(), claim.memo(), claim.revenue(), claim.cogs(),
                claim.impairmentLoss(), claim.recoveryIncome(),
                claim.terminationLoss().add(claim.contractCost()), claim.waiverLoss()));
        draft.contracts.put(contractId, copyContract(contract, contract.completedUnits(),
                contract.acceptedUnits(), contract.invoicedTranches(),
                contract.advanceReceived(), contract.retentionWithheld(),
                contract.appliedByTranche(), contract.invoices(), ContractStatus.TERMINATED));
        return finish(draft, true, REASON_TERMINATED, accounts, commandId, content);
    }

    /**
     * 支付供应商：只减现金与应付，不再计费用。
     *
     * @param state      当前状态
     * @param commandId  命令标识
     * @param actorOwnerId 发起人
     * @param amount     金额
     * @param now        当前模拟毫秒
     * @param accounts   账户表
     * @return 候选结果
     */
    public static Outcome paySupplier(State state, String commandId, String actorOwnerId,
                                      BigDecimal amount, long now,
                                      Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("PAY_SUPPLIER", commandId, amount);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        Draft draft = new Draft(state);
        BigDecimal money = moneyOrNull(amount);
        if (money == null || money.signum() <= 0) {
            return finish(draft, false, REASON_INVALID_AMOUNT, accounts, commandId, content);
        }
        if (money.compareTo(state.payable()) > 0) {
            return finish(draft, false, REASON_PAYMENT_EXCEEDS_PAYABLE, accounts,
                    commandId, content);
        }
        CashLedger.TransferOutcome transfer = CashLedger.computeTransfer(accounts,
                state.config().companyOwnerId(), state.config().companyAccountId(),
                state.config().supplierAccountId(), money);
        if (!transfer.accepted()) {
            return finish(draft, false, transfer.reason(), accounts, commandId, content);
        }
        draft.payable = state.payable().subtract(money);
        return finish(draft, true, REASON_SUPPLIER_PAID, transfer.accounts(),
                commandId, content);
    }

    /**
     * 供应商付款（显式资金腿，v2）：付款来自真实资金桶（例如同一家银行的存款）。
     *
     * <p>与现金版 {@link #paySupplier(State, String, String, BigDecimal, long, Map)} 的差别只有
     * “钱从哪来”：业务前置条件（授权、金额、应付上限）完全相同，业务状态推进也相同（应付减少）。
     * 存款付款不需要先取现，因此本行准备金为 0 时同样成立；也不会把存款复制成现金账户。</p>
     *
     * <p>幂等与两表一致性：同一标识必须同时绑定本业务的规范内容与资金源/目标桶。业务层已有记录时
     * 只在两层成功/失败一致的情况下重放当前候选；资金层已有记录而业务层没有，则受控拒绝，
     * 绝不把零移动当新付款；两层结论相反则明示分叉（{@link #REASON_FUND_RECORD_DIVERGED}）。
     * 原命令真实性重演由 World 负责，此处不另建历史账。</p>
     *
     * <p>付款方必须是公司所有者、收款方必须是配置里供应商账户的所有者；两者的真实主体都由
     * 当前资金三态派生（现金看账户 owner，存款/托管看档案），任何自报 owner 都不参与判断。</p>
     *
     * @param state        当前状态
     * @param commandId    命令标识（同一标识同时用于资金腿幂等）
     * @param actorOwnerId 发起人
     * @param amount       付款金额
     * @param now          当前模拟毫秒
     * @param funds        当前资金三态（显式传入，不得复用旧结果）
     * @param payer        资金腿（只含路由与档案，不含账簿）
     * @return 候选结果（业务状态 + 资金三态）
     */
    public static FundOutcome paySupplier(State state, String commandId, String actorOwnerId,
                                          BigDecimal amount, long now, FundState funds,
                                          FundPayer payer) {
        Objects.requireNonNull(state, "状态");
        Objects.requireNonNull(funds, "资金三态");
        Objects.requireNonNull(payer, "资金腿");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new FundOutcome(false, REASON_NOT_AUTHORIZED, state, funds);
        }
        requireAccounts(state.config(), funds.accounts());
        String content = content("PAY_SUPPLIER_FUND", commandId, amount,
                payer.source().token(), payer.target().token());
        String existingContent = state.commandContents().get(commandId);
        if (existingContent != null) {
            if (!existingContent.equals(content)) {
                throw new IllegalArgumentException("COMMAND_CONTENT_CONFLICT");
            }
            String recorded = state.commandOutcomes().get(commandId);
            boolean recordedAccepted = recorded != null && recorded.startsWith("1|");
            String recordedReason = recorded == null ? "REPLAYED" : recorded.substring(2);
            if (recordedAccepted != payer.hasAcceptedRecord(commandId, funds)) {
                return new FundOutcome(false, REASON_FUND_RECORD_DIVERGED, state, funds);
            }
            return new FundOutcome(recordedAccepted, recordedReason, state, funds);
        }
        if (payer.hasRecord(commandId, funds)) {
            // 资金层已有同标识记录、业务层没有：候选在提交前丢了，不能当新付款继续
            return new FundOutcome(false, REASON_FUND_ID_ALREADY_RECORDED, state, funds);
        }
        Draft draft = new Draft(state);
        BigDecimal money = moneyOrNull(amount);
        if (money == null || money.signum() <= 0) {
            return finishFunded(draft, false, REASON_INVALID_AMOUNT, funds, commandId, content);
        }
        if (money.compareTo(state.payable()) > 0) {
            return finishFunded(draft, false, REASON_PAYMENT_EXCEEDS_PAYABLE, funds, commandId,
                    content);
        }
        String sourceOwner = funds.ownerIdOf(payer.source());
        if (!state.config().companyOwnerId().equals(sourceOwner)) {
            return finishFunded(draft, false, REASON_FUND_OWNER_MISMATCH, funds, commandId,
                    content);
        }
        String supplierOwner = funds.accounts().get(state.config().supplierAccountId()).ownerId();
        String targetOwner = funds.ownerIdOf(payer.target());
        if (targetOwner == null || !targetOwner.equals(supplierOwner)) {
            return finishFunded(draft, false, REASON_PAYEE_MISMATCH, funds, commandId, content);
        }
        FundPayer.Result paid = payer.pay(commandId, money, funds);
        if (!paid.accepted()) {
            // 资金腿拒绝：业务经济字段原样，资金层只多一条拒绝回执（随候选提交以便审计）
            return finishFunded(draft, false, paid.reason(), paid.funds(), commandId, content);
        }
        draft.payable = state.payable().subtract(money);
        return finishFunded(draft, true, REASON_SUPPLIER_PAID, paid.funds(), commandId, content);
    }

    // ------------------------------------------------------------------ 报表与校验

    /**
     * 派生报表（零外部资本、零分红），不修改状态。
     *
     * @param state    状态
     * @param accounts 账户表
     * @return 报表
     */
    public static Report report(State state, Map<String, CashAccount> accounts) {
        return report(state, accounts, ReportContext.none());
    }

    /**
     * 带只读外部资本/分红上下文的派生报表，不修改状态。
     *
     * <p>权益 = 初始权益 + 经营利润 + 净外部资本 − 累计宣告分红；
     * 负债 = 供应商应付 + 合同负债 + 未付分红；现金仍取唯一账户表。
     * 宣告与付款不进入经营利润，负权益如实返回。</p>
     *
     * @param state    状态
     * @param accounts 账户表
     * @param context  外部资本/分红只读上下文；null 拒绝
     * @return 报表
     * @throws IllegalArgumentException 账户结构或上下文不合法
     */
    public static Report report(State state, Map<String, CashAccount> accounts,
                                ReportContext context) {
        Objects.requireNonNull(state, "状态");
        if (context == null) {
            throw new IllegalArgumentException("外部资本上下文不能为空");
        }
        requireAccounts(state.config(), accounts);
        BigDecimal arCurrent = ZERO;
        BigDecimal arRetention = ZERO;
        BigDecimal allowance = ZERO;
        BigDecimal asset = ZERO;
        BigDecimal cost = ZERO;
        BigDecimal liability = ZERO;
        BigDecimal memo = ZERO;
        BigDecimal revenue = ZERO;
        BigDecimal cogs = ZERO;
        BigDecimal impairment = ZERO;
        BigDecimal recovery = ZERO;
        BigDecimal termination = ZERO;
        BigDecimal waiver = ZERO;
        for (Claim claim : state.claims().values()) {
            arCurrent = arCurrent.add(claim.arCurrent());
            arRetention = arRetention.add(claim.arRetention());
            allowance = allowance.add(claim.allowance());
            asset = asset.add(claim.contractAsset());
            cost = cost.add(claim.contractCost());
            liability = liability.add(claim.contractLiability());
            memo = memo.add(claim.memo());
            revenue = revenue.add(claim.revenue());
            cogs = cogs.add(claim.cogs());
            impairment = impairment.add(claim.impairmentLoss());
            recovery = recovery.add(claim.recoveryIncome());
            termination = termination.add(claim.terminationLoss());
            waiver = waiver.add(claim.waiverLoss());
        }
        BigDecimal cash = accounts.get(state.config().companyAccountId()).cash();
        BigDecimal gross = CashLedger.money(arCurrent.add(arRetention));
        BigDecimal netClaim = CashLedger.money(gross.subtract(allowance).add(asset));
        BigDecimal obligation = CashLedger.money(gross.add(asset).add(memo));
        BigDecimal assets = CashLedger.money(cash.add(gross).subtract(allowance)
                .add(asset).add(cost).add(context.externalAssets()));
        BigDecimal liabilities = CashLedger.money(state.payable().add(liability)
                .add(context.unpaidDividends()).add(context.externalLiabilities()));
        BigDecimal profit = CashLedger.money(revenue.subtract(cogs).subtract(impairment)
                .add(recovery).subtract(termination).subtract(waiver));
        BigDecimal equity = CashLedger.money(state.initialEquity().add(profit)
                .add(context.netExternalCapital()).subtract(context.declaredDividends())
                .add(context.externalEquity()));
        BigDecimal world = worldCash(accounts);
        boolean balanced = assets.compareTo(liabilities.add(equity)) == 0
                && world.compareTo(state.initialWorldCash()) == 0;
        return new Report(CashLedger.money(cash), CashLedger.money(arCurrent),
                CashLedger.money(arRetention), gross, CashLedger.money(allowance),
                CashLedger.money(asset), CashLedger.money(cost), CashLedger.money(liability),
                CashLedger.money(state.payable()), CashLedger.money(memo), netClaim, obligation,
                CashLedger.money(revenue), CashLedger.money(cogs),
                CashLedger.money(impairment), CashLedger.money(recovery),
                CashLedger.money(termination), CashLedger.money(waiver), profit, equity,
                world, balanced);
    }

    /**
     * 带存款与托管受益权的派生报表：现金、全部银行存款、客户受益权分列，只各计一次。
     *
     * <p>旧 {@link Report#companyCash()} 仍只表示真实现金，语义不变。本类型把公司在本行
     * **全部**银行存款（含它作为托管经营人持有的池存款）与它作为客户持有的受益权单列；
     * 池资产与客户受益权分属两个主体，不会给同一客户计两次。托管经营人若同时是主体，
     * 还需另列客户明细负债，本档案模型不允许经营人与主体同名，故这里不出现该行。</p>
     *
     * <p><b>来源互斥</b>：存款与受益权只从资金三态派生；{@link ReportContext} 里的组合派生项
     * 必须<b>不含</b>这些已由 funds 计入的余额（例如不能把同一笔银行存款既写进
     * {@code externalAssets} 又通过 funds 计入）。本类型不做去重，两个来源各加一次；
     * 组合层若重复传入就会双计，因此 World 只能择一：存款走 funds，其它独立外部资产负债
     * （证券成本、借款本金、已实现损益等）走 context。</p>
     *
     * @param cash        公司真实现金
     * @param deposits    公司全部银行存款
     * @param beneficial  公司作为客户持有的托管受益权
     * @param assets      资产合计（现金 + 存款 + 受益权 + 应收 − 准备 + 合同资产 + 履约成本 + 组合派生资产）
     * @param liabilities 负债合计（供应商应付 + 合同负债 + 未付分红 + 组合派生负债）
     * @param equity      期末权益（来自状态与只读上下文，不重设；已含组合派生权益）
     * @param worldCash   世界现金合计
     * @param balanced    资产=负债+权益且世界现金守恒
     */
    public record FundedReport(BigDecimal cash, BigDecimal deposits, BigDecimal beneficial,
                               BigDecimal assets, BigDecimal liabilities, BigDecimal equity,
                               BigDecimal worldCash, boolean balanced) {
    }

    /**
     * 派生“现金 + 存款 + 托管受益权”报表；不修改状态，也不改旧报表的现金口径。
     *
     * @param state          状态
     * @param accounts       账户表
     * @param context        外部资本/分红只读上下文
     * @param depositBalance 调用方声明的存款余额（受信纯数字；接 World 用三态版本）
     * @return 带存款的派生报表（受益权恒为零）
     */
    public static FundedReport fundedReport(State state, Map<String, CashAccount> accounts,
                                            ReportContext context, BigDecimal depositBalance) {
        Report base = report(state, accounts, context);
        BigDecimal deposit = CashLedger.money(depositBalance);
        if (deposit.signum() < 0) {
            throw new IllegalArgumentException("存款余额不能为负");
        }
        return build(base, state, context, deposit, ZERO);
    }

    /**
     * 从真实资金三态派生报表：存款覆盖公司全部银行，受益权只算它作为客户的部分。
     *
     * @param state   状态
     * @param funds   当前资金三态
     * @param context 外部资本/分红只读上下文
     * @return 带存款与受益权的派生报表
     */
    public static FundedReport fundedReport(State state, FundState funds, ReportContext context) {
        Objects.requireNonNull(funds, "资金三态");
        Report base = report(state, funds.accounts(), context);
        return build(base, state, context, depositsOf(funds, state.config().companyOwnerId()),
                beneficialOf(funds, state.config().companyOwnerId()));
    }

    private static FundedReport build(Report base, State state, ReportContext context,
                                      BigDecimal deposits, BigDecimal beneficial) {
        BigDecimal assets = CashLedger.money(base.companyCash().add(deposits).add(beneficial)
                .add(base.arGross()).subtract(base.allowance())
                .add(base.contractAsset()).add(base.contractCost())
                .add(context.externalAssets()));
        BigDecimal liabilities = CashLedger.money(base.payable().add(base.contractLiability())
                .add(context.unpaidDividends()).add(context.externalLiabilities()));
        boolean balanced = assets.compareTo(liabilities.add(base.equity())) == 0
                && base.worldCash().compareTo(state.initialWorldCash()) == 0;
        return new FundedReport(CashLedger.money(base.companyCash()), deposits, beneficial, assets,
                liabilities, base.equity(), base.worldCash(), balanced);
    }

    /**
     * 校验并返回报表（零外部资本、零分红）；不平衡时抛异常。
     *
     * @param state    状态
     * @param accounts 账户表
     * @return 已通过校验的报表
     * @throws IllegalStateException    资产、负债、权益或世界现金不守恒
     * @throws IllegalArgumentException 账户结构不合法
     */
    public static Report validate(State state, Map<String, CashAccount> accounts) {
        return validate(state, accounts, ReportContext.none());
    }

    /**
     * 带只读外部资本/分红上下文校验并返回报表；不平衡时抛异常。
     *
     * @param state    状态
     * @param accounts 账户表
     * @param context  外部资本/分红只读上下文；null 拒绝
     * @return 已通过校验的报表
     * @throws IllegalStateException    资产、负债、权益或世界现金不守恒
     * @throws IllegalArgumentException 账户结构或上下文不合法
     */
    public static Report validate(State state, Map<String, CashAccount> accounts,
                                  ReportContext context) {
        Report report = report(state, accounts, context);
        if (!report.balanced()) {
            throw new IllegalStateException("工程账不平衡：资产=" + report.companyCash()
                    + "，负债=" + report.payable()
                    + "，权益=" + report.equity()
                    + "，世界现金=" + report.worldCash()
                    + "，初始世界现金=" + state.initialWorldCash());
        }
        return report;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 一次业务候选的资金上下文：旧现金路径只带账户表；显式路径带当前三态与资金腿。
     *
     * <p>两条路径共用同一份业务算法（校验顺序、阈值、状态推进、命令记录内容），差别只在钱
     * 怎么走：旧路径仍走 {@link CashLedger#computeTransfer}（唯一现金权威，行为逐字不变），
     * 显式路径走 {@link FundPayer#pay}（严格按出款桶/收款桶，不跨桶补足）。这样不会出现
     * 两套经营规则各自漂移。</p>
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
         * 命令内容：两种路径的标签与业务字段相同，显式路径额外绑定实际源/目标桶。
         *
         * <p>因此同标识换桶（甚至现金版换桶版）都会在旧记录上撞成
         * {@code COMMAND_CONTENT_CONFLICT}，而不是被当成同一条命令重放。</p>
         *
         * @param tag   命令标签
         * @param parts 业务字段
         * @return 规范化内容
         */
        String content(String tag, Object... parts) {
            int extra = explicit() ? 2 : 0;
            Object[] all = new Object[parts.length + 1 + extra];
            all[0] = tag;
            System.arraycopy(parts, 0, all, 1, parts.length);
            if (explicit()) {
                all[parts.length + 1] = payer.source().token();
                all[parts.length + 2] = payer.target().token();
            }
            return EngineeringModel.content(all);
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
         * 业务/资金两层一致性检查。
         *
         * @param state     当前状态
         * @param commandId 命令标识
         * @param content   本次命令内容（含桶绑定）
         * @return 需要原样返回的候选；返回 null 表示这是新命令，可以继续推进业务
         */
        Settlement replay(State state, String commandId, String content) {
            String existing = state.commandContents().get(commandId);
            if (existing != null) {
                if (!existing.equals(content)) {
                    throw new IllegalArgumentException("COMMAND_CONTENT_CONFLICT");
                }
                String outcome = state.commandOutcomes().get(commandId);
                boolean accepted = outcome != null && outcome.startsWith("1|");
                String reason = outcome == null ? "REPLAYED" : outcome.substring(2);
                if (explicit() && accepted != payer.hasAcceptedRecord(commandId, funds)) {
                    return at(state, false, REASON_FUND_RECORD_DIVERGED);
                }
                return at(state, accepted, reason);
            }
            if (explicit() && payer.hasRecord(commandId, funds)) {
                // 资金层已有同标识记录、业务层没有：候选在提交前丢了，不能当新付款继续
                return at(state, false, REASON_FUND_ID_ALREADY_RECORDED);
            }
            return null;
        }

        /**
         * 执行一条腿。
         *
         * <p>旧路径按原样调用 {@link CashLedger#computeTransfer}（主体不符由现金账本自己拒绝）；
         * 显式路径先核对<b>真实</b>出款/收款主体（来自当前三态，不采信自报），不符即拒绝且不动钱。</p>
         *
         * @param commandId     命令标识（同时是资金腿幂等标识）
         * @param amount        金额
         * @param sourceOwner   业务认定的出款主体
         * @param sourceAccount 旧路径出款账户
         * @param targetOwner   业务认定的收款主体
         * @param targetAccount 旧路径收款账户
         * @return 腿结果；拒绝时经济字段不变
         */
        Leg pay(String commandId, BigDecimal amount, String sourceOwner, String sourceAccount,
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
            FundPayer.Result result = payer.pay(commandId, amount, funds);
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

    private static Settlement finishLeg(Funding funding, Draft draft, boolean accepted,
                                        String reason, Leg leg, String commandId, String content) {
        draft.commandContents.put(commandId, content);
        draft.commandOutcomes.put(commandId, (accepted ? "1|" : "0|") + reason);
        Map<String, CashAccount> accounts = leg == null ? funding.accounts() : leg.accounts();
        FundState funds = leg == null ? funding.funds() : leg.funds();
        return new Settlement(accepted, reason, draft.build(), accounts, funds);
    }

    private static final class Draft {
        private final Config config;
        private final BigDecimal initialEquity;
        private final BigDecimal initialWorldCash;
        private final Map<String, Contract> contracts;
        private final Map<String, WorkOrder> workOrders;
        private final Map<String, Claim> claims;
        private final Set<String> usedIds;
        private final Map<String, String> commandContents;
        private final Map<String, String> commandOutcomes;
        private BigDecimal payable;

        Draft(State state) {
            config = state.config();
            initialEquity = state.initialEquity();
            initialWorldCash = state.initialWorldCash();
            contracts = new LinkedHashMap<>(state.contracts());
            workOrders = new LinkedHashMap<>(state.workOrders());
            claims = new LinkedHashMap<>(state.claims());
            usedIds = new LinkedHashSet<>(state.usedIds());
            commandContents = new TreeMap<>(state.commandContents());
            commandOutcomes = new TreeMap<>(state.commandOutcomes());
            payable = state.payable();
        }

        State build() {
            return new State(config, contracts, workOrders, claims, payable, initialEquity,
                    initialWorldCash, usedIds, commandContents, commandOutcomes);
        }
    }

    private static Outcome finish(Draft draft, boolean accepted, String reason,
                                  Map<String, CashAccount> accounts, String commandId,
                                  String content) {
        draft.commandContents.put(commandId, content);
        draft.commandOutcomes.put(commandId, (accepted ? "1|" : "0|") + reason);
        return new Outcome(accepted, reason, draft.build(), accounts);
    }

    private static Outcome replay(State state, String commandId, String content,
                                  Map<String, CashAccount> accounts) {
        String existing = state.commandContents().get(commandId);
        if (existing == null) {
            return null;
        }
        if (!existing.equals(content)) {
            throw new IllegalArgumentException("COMMAND_CONTENT_CONFLICT");
        }
        String outcome = state.commandOutcomes().get(commandId);
        boolean accepted = outcome != null && outcome.startsWith("1|");
        String reason = outcome == null ? "REPLAYED" : outcome.substring(2);
        return new Outcome(accepted, reason, state, accounts);
    }

    private static FundOutcome finishFunded(Draft draft, boolean accepted, String reason,
                                            FundState funds, String commandId, String content) {
        draft.commandContents.put(commandId, content);
        draft.commandOutcomes.put(commandId, (accepted ? "1|" : "0|") + reason);
        return new FundOutcome(accepted, reason, draft.build(), funds);
    }

    private static boolean authorized(State state, String actorOwnerId) {
        return state.config().companyOwnerId().equals(actorOwnerId);
    }

    private static Contract copyContract(Contract contract, long completed, long accepted,
                                         int invoiced, BigDecimal advance,
                                         BigDecimal retentionWithheld,
                                         List<BigDecimal> applied, List<Invoice> invoices,
                                         ContractStatus status) {
        return new Contract(contract.contractId(), contract.unitPrice(), contract.unitCost(),
                contract.terms(), contract.signedAt(), completed, accepted, invoiced,
                advance, retentionWithheld, applied, invoices, status);
    }

    private static Invoice invoiceAt(Contract contract, int index) {
        if (index < 0 || index >= contract.invoices().size()) {
            return null;
        }
        return contract.invoices().get(index);
    }

    private static List<Invoice> reduceInvoices(List<Invoice> invoices, BigDecimal fromCurrent,
                                                BigDecimal fromRetention) {
        BigDecimal current = fromCurrent;
        BigDecimal retention = fromRetention;
        List<Invoice> out = new ArrayList<>();
        for (Invoice invoice : invoices) {
            BigDecimal takeCurrent = current.min(invoice.currentOutstanding());
            current = current.subtract(takeCurrent);
            BigDecimal takeRetention = retention.min(invoice.retentionOutstanding());
            retention = retention.subtract(takeRetention);
            out.add(new Invoice(invoice.trancheIndex(), invoice.units(), invoice.gross(),
                    invoice.applied(), invoice.retention(), invoice.dueAt(),
                    invoice.retentionDueAt(),
                    invoice.currentOutstanding().subtract(takeCurrent),
                    invoice.retentionOutstanding().subtract(takeRetention)));
        }
        return out;
    }

    private static BigDecimal contractValue(Contract contract) {
        return CashLedger.money(contract.unitPrice().multiply(
                BigDecimal.valueOf(contract.terms().totalUnits())));
    }

    private static BigDecimal requireCost(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("成本不能为空");
        }
        BigDecimal scaled = value.setScale(4, RoundingMode.UNNECESSARY);
        if (scaled.signum() < 0) {
            throw new IllegalArgumentException("成本不能为负");
        }
        return scaled;
    }

    private static BigDecimal moneyOrNull(BigDecimal value) {
        try {
            return CashLedger.money(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static BigDecimal round2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static void requireAccounts(Config config, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(accounts, "账户表");
        for (Map.Entry<String, CashAccount> entry : accounts.entrySet()) {
            if (config.companyOwnerId().equals(entry.getValue().ownerId())
                    && !config.companyAccountId().equals(entry.getKey())) {
                throw new IllegalArgumentException(
                        "公司所有者只能有一个经营现金账户：" + entry.getKey());
            }
        }
        List<String> ids = List.of(config.companyAccountId(), config.customerAccountId(),
                config.supplierAccountId(), config.opsAccountId());
        for (String accountId : ids) {
            CashAccount account = accounts.get(accountId);
            if (account == null) {
                throw new IllegalArgumentException("缺少账户：" + accountId);
            }
            requireText(account.ownerId(), "账户所有者");
            if (CashLedger.money(account.cash()).signum() < 0) {
                throw new IllegalArgumentException("账户现金不能为负：" + accountId);
            }
        }
        if (!accounts.get(config.companyAccountId()).ownerId().equals(config.companyOwnerId())) {
            throw new IllegalArgumentException("公司账户所有者与配置不一致");
        }
        Set<String> owners = new LinkedHashSet<>();
        for (String accountId : ids.subList(1, ids.size())) {
            String owner = accounts.get(accountId).ownerId();
            if (owner.equals(config.companyOwnerId()) || !owners.add(owner)) {
                throw new IllegalArgumentException("对手方所有者必须互不相同且不同于公司");
            }
        }
    }

    private static BigDecimal worldCash(Map<String, CashAccount> accounts) {
        BigDecimal total = ZERO;
        for (CashAccount account : accounts.values()) {
            total = total.add(account.cash());
        }
        return CashLedger.money(total);
    }

    private static String joinUnits(long[] units) {
        StringBuilder text = new StringBuilder();
        for (long unit : units) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(unit);
        }
        return text.toString();
    }

    private static String content(Object... parts) {
        StringBuilder text = new StringBuilder();
        for (Object part : parts) {
            if (text.length() > 0) {
                text.append('\u0001');
            }
            text.append(part instanceof BigDecimal money ? money.toPlainString()
                    : String.valueOf(part));
        }
        return text.toString();
    }

    private static void requireTime(long now) {
        if (now < 0) {
            throw new IllegalArgumentException("模拟毫秒不能为负：" + now);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
