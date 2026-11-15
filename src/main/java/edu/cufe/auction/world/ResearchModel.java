package edu.cufe.auction.world;

import edu.cufe.auction.world.CashLedger.CashAccount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 分阶段研发投入纯核：立项、阶段开工、阶段完成、阶段失败、成果批准与按实验日摊销。
 *
 * <p><b>冻结的实验政策</b>（是模拟假设，不声称现实会计准则或行业惯例）：</p>
 * <ul>
 *   <li>固定三阶段 {@link Stage#RESEARCH}、{@link Stage#DEVELOPMENT}、{@link Stage#REGISTRATION}，
 *       必须按序逐个开工；同一项目同一时刻最多一个在途阶段。</li>
 *   <li>研究阶段投入在开工付款时立即费用化；开发与注册阶段投入在开工付款时形成在研资产，
 *       期间不随调用次数再次费用化。</li>
 *   <li>三阶段全部完成且拿到 {@code approvalRef}（纯核记录的批准事实）之后，在研资产才转为
 *       可摊销的可用资产；批准前不得出现可用资产，也不得摊销。</li>
 *   <li>阶段失败即真实终止：未开工的后续阶段不再付款，已付的开发/注册投入一次性减值，
 *       已费用化的研究投入保留。失败不产生任何退款或虚拟资产。</li>
 *   <li>摊销按「累计应摊额 − 已记摊销」计算，剩余寿命届满时一次结清尾差；重复调用相同
 *       截止日不会重复扣钱。</li>
 * </ul>
 *
 * <p>金额一律 {@link BigDecimal}（精确到分，按 {@link CashLedger#money} 校验），数量与时间使用
 * {@code long}。现金只存在于唯一传入的 {@link CashLedger.CashAccount} 账户表：本类不保存余额，
 * 也不为单个项目复制现金；多个项目共用同一经营账户，余额不足时整体拒绝且不产生单边改变。
 * 业务时间由调用方显式传入，本类无 IO、无系统时钟、无随机数、无独立运行器。</p>
 *
 * <p>幂等：每个动作携带 {@code commandId} 与规范化内容；同 ID 同内容返回原结果且不重复记账，
 * 同 ID 不同内容抛 {@link IllegalArgumentException}。授权检查先于标识存在性检查，非公司所有者
 * 无法探测项目是否存在。被拒绝的业务动作只记录命令索引并推进已见时间轴，不改现金、不改项目
 * 业务状态、不产生单边付款。</p>
 *
 * <p>账户接缝：动作与重放只接受调用方本次传入的账户表，并把它原样返回；本类不缓存历史账户。
 * 迟到的重放保留当前状态与当前账户，不做任何回滚。「调用方传当前权威账户表」是可信接缝：
 * 若调用方传旧账户表，重放会返回 {@code accepted=true} 但账面不再守恒，由 {@link #validate}
 * 检出——正确做法是修调用方，而不是让本类缓存旧账户表来“修复”幂等，那才会造成回滚。</p>
 *
 * <p>权益口径：投资本身不产生收入，也不改期初权益；{@code initialEquity} 一经 {@link #open}
 * 建立便永不重置。外部净资本流与分红不在本类处理，也不能靠改写期初权益来对齐：未来它们必须由
 * 真实 {@code ReportContext}（净资本流/分红事实）进入权益核对，当前纯核尚未接入。因此传入账户表
 * 若出现外部注资或分红，报表会如实判为不平衡——这是接缝未接的证据，不是重算期初权益的理由。</p>
 */
public final class ResearchModel {

    /** 一个实验日的模拟毫秒数；不读取系统日历。 */
    public static final long DAY_MILLIS = 86_400_000L;

    /** 立项成功，未发生任何现金移动。 */
    public static final String REASON_REGISTERED = "REGISTERED";
    /** 阶段开工，已完成真实双边付款。 */
    public static final String REASON_STARTED = "STARTED";
    /** 阶段按到期时间与证据完成。 */
    public static final String REASON_COMPLETED = "STAGE_COMPLETED";
    /** 阶段失败并终止项目。 */
    public static final String REASON_FAILED = "PROJECT_FAILED";
    /** 成果获批，在研资产转可用资产。 */
    public static final String REASON_APPROVED = "APPROVED";
    /** 摊销已记（同截止日重复调用金额为 0）。 */
    public static final String REASON_AMORTIZED = "AMORTIZED";

    /** 发起人不是公司所有者。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 项目标识或阶段记录标识已被使用。 */
    public static final String REASON_ID_REUSED = "ID_REUSED";
    /** 项目不存在或不属于本公司。 */
    public static final String REASON_UNKNOWN_PROJECT = "UNKNOWN_PROJECT";
    /** 批准引用为空。 */
    public static final String REASON_INVALID_APPROVAL = "INVALID_APPROVAL";
    /** 承包方账户缺失、所有者与登记时不一致或就是公司自己。 */
    public static final String REASON_CONTRACTOR_INVALID = "CONTRACTOR_INVALID";
    /** 阶段尚未按顺序轮到或已有在途阶段。 */
    public static final String REASON_STAGE_ORDER = "STAGE_ORDER";
    /** 阶段当前状态不允许该动作。 */
    public static final String REASON_STAGE_STATE_INVALID = "STAGE_STATE_INVALID";
    /** 阶段未到完成时点。 */
    public static final String REASON_NOT_DUE = "NOT_DUE";
    /** 证据不满足冻结的证据要求。 */
    public static final String REASON_EVIDENCE_MISMATCH = "EVIDENCE_MISMATCH";
    /** 证据时点早于阶段开工或晚于当前时刻。 */
    public static final String REASON_INVALID_EVIDENCE = "INVALID_EVIDENCE";
    /** 项目已失败终止，后续阶段不得付款。 */
    public static final String REASON_PROJECT_TERMINATED = "PROJECT_TERMINATED";
    /** 三阶段尚未全部完成，不能批准。 */
    public static final String REASON_PROJECT_INCOMPLETE = "PROJECT_INCOMPLETE";
    /** 项目已批准，不得重复批准。 */
    public static final String REASON_ALREADY_APPROVED = "ALREADY_APPROVED";
    /** 项目未批准，不能摊销。 */
    public static final String REASON_NOT_APPROVED = "NOT_APPROVED";
    /** 业务时间早于已见时间轴，不允许回填。 */
    public static final String REASON_TIME_REWIND = "TIME_REWIND";
    /** 摊销截止日早于批准日或早于上次摊销截止日。 */
    public static final String REASON_AMORTIZATION_REWIND = "AMORTIZATION_REWIND";
    /** 摊销截止日晚于当前时刻。 */
    public static final String REASON_FUTURE_AS_OF = "FUTURE_AS_OF";
    /** 时间或金额计算溢出。 */
    public static final String REASON_OVERFLOW = "OVERFLOW";
    /** 可用现金不足（余额取自唯一账户表，不是项目初始余额）；与 {@link CashLedger} 的拒绝码一致。 */
    public static final String REASON_INSUFFICIENT_CASH = "INSUFFICIENT_CASH";

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private ResearchModel() {
    }

    /** 固定研发阶段顺序；下标即为法定顺序。 */
    public enum Stage {
        /** 研究阶段，投入立即费用化。 */
        RESEARCH,
        /** 开发阶段，投入形成在研资产。 */
        DEVELOPMENT,
        /** 注册阶段，投入形成在研资产。 */
        REGISTRATION
    }

    /** 单阶段状态。 */
    public enum StageStatus {
        /** 未开工，未付款。 */
        PLANNED,
        /** 已开工并已付款，等待到期与结果证据。 */
        IN_PROGRESS,
        /** 已完成并记录了证据。 */
        COMPLETED,
        /** 失败并记录了证据；项目随之终止。 */
        FAILED
    }

    /** 项目整体状态，由阶段记录与批准事实派生。 */
    public enum ProjectStatus {
        /** 尚有阶段未完成。 */
        ACTIVE,
        /** 三阶段全部完成，等待批准。 */
        COMPLETED,
        /** 已批准，在研资产已转可用资产。 */
        APPROVED,
        /** 已失败终止，不得再付款。 */
        FAILED
    }

    /**
     * 账户绑定与既有的非研发资产账面价值。
     *
     * @param companyOwnerId   公司财产主体；唯一有权发起研发动作的身份
     * @param companyAccountId 公司唯一经营现金账户
     * @param otherAssets      既有非研发资产（如厂房设备）账面价值，非负，仅用于权益核对
     */
    public record Config(String companyOwnerId, String companyAccountId, BigDecimal otherAssets) {

        /** 校验标识与既有资产金额。 */
        public Config {
            requireText(companyOwnerId, "公司所有者");
            requireText(companyAccountId, "公司账户");
            otherAssets = CashLedger.money(otherAssets);
            if (otherAssets.signum() < 0) {
                throw new IllegalArgumentException("既有非研发资产不能为负");
            }
        }
    }

    /**
     * 冻结的单个阶段计划：成本、正耗时与证据要求。
     *
     * @param stage              阶段
     * @param cost               阶段成本，正数且精确到分
     * @param durationMillis     阶段耗时，必须为正的模拟毫秒
     * @param evidenceRequirement 结果证据必须满足的要求标识
     */
    public record StagePlan(Stage stage, BigDecimal cost, long durationMillis,
                            String evidenceRequirement) {

        /** 校验阶段、成本、正耗时与证据要求。 */
        public StagePlan {
            Objects.requireNonNull(stage, "阶段");
            cost = CashLedger.money(cost);
            if (cost.signum() <= 0) {
                throw new IllegalArgumentException("阶段成本必须为正");
            }
            if (durationMillis <= 0) {
                throw new IllegalArgumentException("阶段耗时必须为正");
            }
            requireText(evidenceRequirement, "证据要求");
        }
    }

    /**
     * 立项方案：项目预算、承包方账户、摊销寿命与三阶段计划。
     *
     * @param projectId          项目标识，运行内唯一
     * @param budget             项目预算，正数且不小于阶段成本合计
     * @param contractorAccountId 承包方收款账户；所有者从权威账户表解析，不采信自报
     * @param amortizationDays   可用资产摊销寿命（实验日），必须为正
     * @param stages             固定三阶段计划，必须按 {@link Stage} 顺序
     */
    public record ProjectSpec(String projectId, BigDecimal budget, String contractorAccountId,
                              int amortizationDays, List<StagePlan> stages) {

        /** 校验预算、阶段数、阶段顺序与成本合计。 */
        public ProjectSpec {
            requireText(projectId, "项目标识");
            budget = CashLedger.money(budget);
            if (budget.signum() <= 0) {
                throw new IllegalArgumentException("项目预算必须为正");
            }
            requireText(contractorAccountId, "承包方账户");
            if (amortizationDays <= 0) {
                throw new IllegalArgumentException("摊销天数必须为正");
            }
            Objects.requireNonNull(stages, "阶段表");
            if (stages.size() != Stage.values().length) {
                throw new IllegalArgumentException("阶段表必须恰为三阶段");
            }
            List<StagePlan> copied = List.copyOf(stages);
            BigDecimal total = ZERO;
            for (int index = 0; index < copied.size(); index++) {
                StagePlan plan = copied.get(index);
                if (plan.stage() != Stage.values()[index]) {
                    throw new IllegalArgumentException("阶段顺序必须为 " + Stage.values()[index]);
                }
                total = total.add(plan.cost());
            }
            if (total.compareTo(budget) > 0) {
                throw new IllegalArgumentException("阶段成本合计超过项目预算");
            }
            stages = copied;
        }

        /**
         * 阶段成本合计。
         *
         * @return 三阶段成本之和
         */
        public BigDecimal totalCost() {
            BigDecimal total = ZERO;
            for (StagePlan plan : stages) {
                total = total.add(plan.cost());
            }
            return CashLedger.money(total);
        }
    }

    /**
     * 阶段结果证据：由受信运行适配输入，不由角色自报。
     *
     * @param requirementId 对应的证据要求标识
     * @param reference     证据引用（纯核只记录，不据此证明世界来源已核）
     * @param observedAt    证据观察时点（模拟毫秒）
     */
    public record Evidence(String requirementId, String reference, long observedAt) {

        /** 校验要求标识、引用与观察时点。 */
        public Evidence {
            requireText(requirementId, "证据要求标识");
            requireText(reference, "证据引用");
            if (observedAt < 0) {
                throw new IllegalArgumentException("证据观察时点不能为负");
            }
        }
    }

    /**
     * 不可变阶段记录。
     *
     * @param plan       冻结的阶段计划
     * @param status     当前状态
     * @param beganAt    开工时刻；未开工为 -1
     * @param completesAt 理论完成时刻（开工时刻 + 耗时）；未开工为 -1
     * @param evidence   完成或失败时的证据；未结束时为 null
     */
    public record StageRecord(StagePlan plan, StageStatus status, long beganAt, long completesAt,
                              Evidence evidence) {

        /** 校验状态、时间轴与证据的一致性。 */
        public StageRecord {
            Objects.requireNonNull(plan, "阶段计划");
            Objects.requireNonNull(status, "阶段状态");
            switch (status) {
                case PLANNED -> {
                    if (beganAt != -1 || completesAt != -1 || evidence != null) {
                        throw new IllegalArgumentException("未开工阶段不得带时间或证据");
                    }
                }
                case IN_PROGRESS -> {
                    if (beganAt < 0 || completesAt <= beganAt || evidence != null) {
                        throw new IllegalArgumentException("在途阶段时间轴或证据不合法");
                    }
                    requireDuration(plan, beganAt, completesAt);
                }
                case COMPLETED, FAILED -> {
                    if (beganAt < 0 || completesAt <= beganAt || evidence == null) {
                        throw new IllegalArgumentException("已结束阶段必须有证据与正耗时");
                    }
                    requireDuration(plan, beganAt, completesAt);
                }
                default -> throw new IllegalArgumentException("未知阶段状态");
            }
        }

        /**
         * 阶段。
         *
         * @return 阶段
         */
        public Stage stage() {
            return plan.stage();
        }

        /**
         * 阶段成本（精确到分）。
         *
         * @return 成本
         */
        public BigDecimal cost() {
            return plan.cost();
        }

        /**
         * 冻结耗时。
         *
         * @return 模拟毫秒
         */
        public long durationMillis() {
            return plan.durationMillis();
        }

        /**
         * 证据要求标识。
         *
         * @return 要求标识
         */
        public String evidenceRequirement() {
            return plan.evidenceRequirement();
        }

        /**
         * 是否已经付过款（开工及以后）。
         *
         * @return 已付款为 true
         */
        public boolean paid() {
            return status != StageStatus.PLANNED;
        }

        private static void requireDuration(StagePlan plan, long beganAt, long completesAt) {
            if (completesAt - beganAt != plan.durationMillis()) {
                throw new IllegalArgumentException("完成时刻必须等于开工时刻加冻结耗时");
            }
        }
    }

    /**
     * 不可变项目记录；现金不在其中，只由唯一账户表承载。
     *
     * @param projectId          项目标识
     * @param budget             冻结预算
     * @param contractorAccountId 承包方账户
     * @param contractorOwnerId  承包方财产主体，立项时从权威账户表解析
     * @param registeredAt       立项时刻
     * @param amortizationDays   摊销寿命（实验日）
     * @param stages             三阶段记录
     * @param approvalRef        批准事实引用；未批准为 null
     * @param approvedAt         批准时刻；未批准为 -1
     * @param amortized          累计已记摊销
     * @param amortizedAt        上次摊销截止日；从未摊为 -1
     */
    public record Project(String projectId, BigDecimal budget, String contractorAccountId,
                          String contractorOwnerId, long registeredAt, int amortizationDays,
                          List<StageRecord> stages, String approvalRef, long approvedAt,
                          BigDecimal amortized, long amortizedAt) {

        /** 校验标识、阶段序列、批准事实与摊销进度的一致性。 */
        public Project {
            requireText(projectId, "项目标识");
            budget = CashLedger.money(budget);
            if (budget.signum() <= 0) {
                throw new IllegalArgumentException("项目预算必须为正");
            }
            requireText(contractorAccountId, "承包方账户");
            requireText(contractorOwnerId, "承包方所有者");
            if (registeredAt < 0) {
                throw new IllegalArgumentException("立项时刻不能为负");
            }
            if (amortizationDays <= 0) {
                throw new IllegalArgumentException("摊销天数必须为正");
            }
            Objects.requireNonNull(stages, "阶段记录");
            if (stages.size() != Stage.values().length) {
                throw new IllegalArgumentException("阶段记录必须恰为三阶段");
            }
            List<StageRecord> copied = List.copyOf(stages);
            long previousBegan = registeredAt;
            long previousCompleted = registeredAt;
            int running = 0;
            boolean failed = false;
            boolean earlierCompleted = true;
            for (int index = 0; index < copied.size(); index++) {
                StageRecord record = copied.get(index);
                if (record.stage() != Stage.values()[index]) {
                    throw new IllegalArgumentException("阶段记录顺序不合法");
                }
                BigDecimal cost = record.cost();
                if (cost.signum() <= 0) {
                    throw new IllegalArgumentException("阶段成本必须为正");
                }
                if (record.beganAt() >= 0) {
                    if (record.beganAt() < previousBegan) {
                        throw new IllegalArgumentException("阶段开工时刻不得倒退");
                    }
                    previousBegan = record.beganAt();
                }
                if (record.status() == StageStatus.IN_PROGRESS) {
                    running++;
                }
                if (failed && record.paid()) {
                    throw new IllegalArgumentException("失败项目不得继续付款");
                }
                if (record.paid() && !earlierCompleted) {
                    throw new IllegalArgumentException("后续阶段开工前，前面阶段必须已成功完成");
                }
                if (record.status() == StageStatus.COMPLETED) {
                    if (record.completesAt() > previousCompleted) {
                        previousCompleted = record.completesAt();
                    }
                } else {
                    earlierCompleted = false;
                }
                failed |= record.status() == StageStatus.FAILED;
            }
            if (running > 1) {
                throw new IllegalArgumentException("同一项目同时最多一个在途阶段");
            }
            BigDecimal total = ZERO;
            BigDecimal capitalized = ZERO;
            for (StageRecord record : copied) {
                if (record.paid()) {
                    total = total.add(record.cost());
                }
                if (record.stage() != Stage.RESEARCH) {
                    capitalized = capitalized.add(record.cost());
                }
            }
            if (total.compareTo(budget) > 0) {
                throw new IllegalArgumentException("已付阶段成本超过项目预算");
            }
            if (approvalRef != null) {
                if (approvalRef.isBlank()) {
                    throw new IllegalArgumentException("批准引用不能为空白");
                }
                if (failed) {
                    throw new IllegalArgumentException("失败项目不得有批准事实");
                }
                for (StageRecord record : copied) {
                    if (record.status() != StageStatus.COMPLETED) {
                        throw new IllegalArgumentException("未全部完成不得批准");
                    }
                }
                if (approvedAt < previousCompleted) {
                    throw new IllegalArgumentException(
                            "批准时刻不得早于所有已完成阶段的完成时刻");
                }
            } else if (approvedAt != -1) {
                throw new IllegalArgumentException("未批准不得记录批准时刻");
            }
            amortized = CashLedger.money(amortized);
            if (amortized.signum() < 0) {
                throw new IllegalArgumentException("累计摊销不能为负");
            }
            if (amortizedAt != -1 && (approvalRef == null || amortizedAt < approvedAt)) {
                throw new IllegalArgumentException("摊销截止日必须先有批准且不早于批准日");
            }
            if (amortized.signum() > 0 && amortizedAt == -1) {
                throw new IllegalArgumentException("已记摊销必须有摊销截止日");
            }
            if (amortized.compareTo(approvalRef == null ? ZERO : capitalized) > 0) {
                throw new IllegalArgumentException("累计摊销不得超过可用资产原值");
            }
            stages = copied;
        }

        /**
         * 项目整体状态。
         *
         * @return 派生状态
         */
        public ProjectStatus status() {
            boolean failed = false;
            boolean allCompleted = true;
            for (StageRecord record : stages) {
                failed |= record.status() == StageStatus.FAILED;
                allCompleted &= record.status() == StageStatus.COMPLETED;
            }
            if (failed) {
                return ProjectStatus.FAILED;
            }
            if (approvalRef != null) {
                return ProjectStatus.APPROVED;
            }
            return allCompleted ? ProjectStatus.COMPLETED : ProjectStatus.ACTIVE;
        }

        /**
         * 下一个待开工阶段。
         *
         * @return 第一个未付款阶段；全部已付款时为 null
         */
        public Stage nextStage() {
            for (StageRecord record : stages) {
                if (record.status() == StageStatus.PLANNED) {
                    return record.stage();
                }
            }
            return null;
        }

        /**
         * 是否存在在途阶段。
         *
         * @return 有在途阶段为 true
         */
        public boolean hasRunningStage() {
            for (StageRecord record : stages) {
                if (record.status() == StageStatus.IN_PROGRESS) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 取指定阶段记录。
         *
         * @param stage 阶段
         * @return 阶段记录；不存在为 null
         */
        public StageRecord stage(Stage stage) {
            for (StageRecord record : stages) {
                if (record.stage() == stage) {
                    return record;
                }
            }
            return null;
        }

        /**
         * 实际付款累计（已开工阶段成本之和）。
         *
         * @return 精确到分的付款额
         */
        public BigDecimal paid() {
            BigDecimal total = ZERO;
            for (StageRecord record : stages) {
                if (record.paid()) {
                    total = total.add(record.cost());
                }
            }
            return CashLedger.money(total);
        }

        /**
         * 累计费用化：已付款的研究阶段成本。
         *
         * @return 精确到分的费用
         */
        public BigDecimal expensed() {
            BigDecimal total = ZERO;
            for (StageRecord record : stages) {
                if (record.stage() == Stage.RESEARCH && record.paid()) {
                    total = total.add(record.cost());
                }
            }
            return CashLedger.money(total);
        }

        /**
         * 在研资产：未批准且未失败的项目中已付款的开发与注册阶段成本。
         *
         * <p>失败项目的开发与注册投入按冻结政策一次性减值（见 {@link #impairment()}），
         * 不再保留在研资产：同一笔支出不能既减值又计入资产。</p>
         *
         * @return 精确到分的在研资产
         */
        public BigDecimal workInProgress() {
            if (approvalRef != null || status() == ProjectStatus.FAILED) {
                return ZERO;
            }
            BigDecimal total = ZERO;
            for (StageRecord record : stages) {
                if (record.stage() != Stage.RESEARCH
                        && (record.status() == StageStatus.IN_PROGRESS
                        || record.status() == StageStatus.COMPLETED)) {
                    total = total.add(record.cost());
                }
            }
            return CashLedger.money(total);
        }

        /**
         * 可用资产原值：批准后由在研资产转入。
         *
         * @return 精确到分的可用资产原值
         */
        public BigDecimal availableAsset() {
            if (approvalRef == null) {
                return ZERO;
            }
            BigDecimal total = ZERO;
            for (StageRecord record : stages) {
                if (record.stage() != Stage.RESEARCH) {
                    total = total.add(record.cost());
                }
            }
            return CashLedger.money(total);
        }

        /**
         * 累计减值：失败项目中已付款的开发与注册阶段成本。
         *
         * @return 精确到分的减值损失
         */
        public BigDecimal impairment() {
            if (status() != ProjectStatus.FAILED) {
                return ZERO;
            }
            BigDecimal total = ZERO;
            for (StageRecord record : stages) {
                if (record.stage() != Stage.RESEARCH && record.paid()) {
                    total = total.add(record.cost());
                }
            }
            return CashLedger.money(total);
        }
    }

    /**
     * 不可变纯核状态；不含任何现金余额。
     *
     * @param config           账户绑定与既有资产
     * @param initialEquity    开局权益（现金 + 既有非研发资产），一经建立不再重置
     * @param initialWorldCash 开局世界现金合计
     * @param watermark        已见最大业务时刻，用于拒绝回填
     * @param projects         项目，按标识索引
     * @param commandContents  命令内容索引，用于幂等重放
     * @param commandOutcomes  命令结果索引
     */
    public record State(Config config, BigDecimal initialEquity, BigDecimal initialWorldCash,
                        long watermark, Map<String, Project> projects,
                        Map<String, String> commandContents,
                        Map<String, String> commandOutcomes) {

        /** 深拷贝集合、校验索引一致性。 */
        public State {
            Objects.requireNonNull(config, "配置");
            initialEquity = CashLedger.money(initialEquity);
            initialWorldCash = CashLedger.money(initialWorldCash);
            if (initialEquity.signum() < 0 || initialWorldCash.signum() < 0) {
                throw new IllegalArgumentException("开局权益与世界现金不能为负");
            }
            if (watermark < 0) {
                throw new IllegalArgumentException("业务时间轴不能为负");
            }
            Objects.requireNonNull(projects, "项目表");
            projects = Collections.unmodifiableMap(new LinkedHashMap<>(projects));
            for (Map.Entry<String, Project> entry : projects.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().projectId())) {
                    throw new IllegalArgumentException("项目索引键与标识不一致");
                }
            }
            Objects.requireNonNull(commandContents, "命令内容索引");
            Objects.requireNonNull(commandOutcomes, "命令结果索引");
            commandContents = Collections.unmodifiableMap(new TreeMap<>(commandContents));
            commandOutcomes = Collections.unmodifiableMap(new TreeMap<>(commandOutcomes));
            if (!commandContents.keySet().equals(commandOutcomes.keySet())) {
                throw new IllegalArgumentException("命令索引不一致");
            }
        }
    }

    /**
     * 纯候选结果；拒绝时业务状态与账户表均保持原样（仅记录命令索引以便重放）。
     *
     * @param accepted 业务是否成功
     * @param reason   结果代码
     * @param state    候选状态
     * @param accounts 候选账户表；未发生现金动作时为原表
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
     * 研发投入报表；现金取唯一账户表，其余为项目派生值。
     *
     * @param companyCash           公司经营账户现金
     * @param otherAssets           既有非研发资产
     * @param paid                  累计实际付款给承包方
     * @param expense               累计费用化（研究阶段）
     * @param workInProgress        在研资产
     * @param availableAsset        可用资产原值
     * @param accumulatedAmortization 累计摊销
     * @param availableNet          可用资产净额
     * @param rndAsset              研发资产净额（在研 + 可用净额）
     * @param impairment            累计减值
     * @param profit                累计损益（−费用 −摊销 −减值）
     * @param equity                期末权益（期初权益 + 损益）
     * @param worldCash             世界现金合计
     * @param balanced              资产 = 权益且世界现金守恒
     */
    public record Report(BigDecimal companyCash, BigDecimal otherAssets, BigDecimal paid,
                         BigDecimal expense, BigDecimal workInProgress,
                         BigDecimal availableAsset, BigDecimal accumulatedAmortization,
                         BigDecimal availableNet, BigDecimal rndAsset, BigDecimal impairment,
                         BigDecimal profit, BigDecimal equity, BigDecimal worldCash,
                         boolean balanced) {
    }

    // ------------------------------------------------------------------ 纯核动作

    /**
     * 开局：记录期初权益与世界现金，不复制任何项目现金。
     *
     * @param config   账户绑定与既有资产
     * @param accounts 唯一权威账户表
     * @return 初始状态
     * @throws IllegalArgumentException 账户结构或金额不合法
     */
    public static State open(Config config, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(config, "配置");
        requireAccounts(config, accounts);
        BigDecimal cash = accounts.get(config.companyAccountId()).cash();
        return new State(config, CashLedger.money(cash.add(config.otherAssets())),
                worldCash(accounts), 0, Map.of(), Map.of(), Map.of());
    }

    /**
     * 立项：冻结预算、阶段计划、承包方与摊销寿命，不移动任何现金。
     *
     * @param state     当前状态
     * @param commandId 命令标识
     * @param actorOwnerId 发起人
     * @param spec      项目方案
     * @param now       当前模拟毫秒
     * @param accounts  账户表
     * @return 候选结果
     */
    public static Outcome register(State state, String commandId, String actorOwnerId,
                                   ProjectSpec spec, long now, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        Objects.requireNonNull(spec, "项目方案");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("REGISTER", commandId, spec.projectId(), spec.budget(),
                spec.contractorAccountId(), spec.amortizationDays(), stageContents(spec.stages()));
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        if (now < state.watermark()) {
            return new Outcome(false, REASON_TIME_REWIND, state, accounts);
        }
        Draft draft = new Draft(state);
        draft.watermark = now;
        if (state.projects().containsKey(spec.projectId())) {
            return finish(draft, false, REASON_ID_REUSED, accounts, commandId, content);
        }
        CashAccount contractor = accounts.get(spec.contractorAccountId());
        if (contractor == null || contractor.ownerId().equals(state.config().companyOwnerId())) {
            return finish(draft, false, REASON_CONTRACTOR_INVALID, accounts, commandId, content);
        }
        List<StageRecord> stages = new java.util.ArrayList<>();
        for (StagePlan plan : spec.stages()) {
            stages.add(new StageRecord(plan, StageStatus.PLANNED, -1, -1, null));
        }
        draft.projects.put(spec.projectId(), new Project(spec.projectId(), spec.budget(),
                spec.contractorAccountId(), contractor.ownerId(), now, spec.amortizationDays(),
                stages, null, -1, ZERO, -1));
        return finish(draft, true, REASON_REGISTERED, accounts, commandId, content);
    }

    /**
     * 阶段开工：按冻结成本向有真实所有者的承包方双边付款，并按阶段政策费用化或形成在研资产。
     *
     * <p>现金不足时整体拒绝：账户表原样返回，不产生单边改变；同项目已有在途阶段或阶段顺序
     * 不符时拒绝。</p>
     *
     * @param state     当前状态
     * @param commandId 命令标识
     * @param actorOwnerId 发起人
     * @param projectId 项目标识
     * @param stage     要开工的阶段
     * @param now       当前模拟毫秒
     * @param accounts  账户表
     * @return 候选结果
     */
    public static Outcome begin(State state, String commandId, String actorOwnerId,
                                String projectId, Stage stage, long now,
                                Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        Objects.requireNonNull(stage, "阶段");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("BEGIN", commandId, projectId, stage);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        if (now < state.watermark()) {
            return new Outcome(false, REASON_TIME_REWIND, state, accounts);
        }
        Draft draft = new Draft(state);
        draft.watermark = now;
        Project project = projectId == null ? null : state.projects().get(projectId);
        if (project == null) {
            return finish(draft, false, REASON_UNKNOWN_PROJECT, accounts, commandId, content);
        }
        if (project.status() == ProjectStatus.FAILED) {
            return finish(draft, false, REASON_PROJECT_TERMINATED, accounts, commandId, content);
        }
        if (project.status() == ProjectStatus.APPROVED
                || project.status() == ProjectStatus.COMPLETED) {
            return finish(draft, false, REASON_STAGE_STATE_INVALID, accounts, commandId, content);
        }
        StageRecord target = project.stage(stage);
        if (target == null || target.status() != StageStatus.PLANNED) {
            return finish(draft, false, REASON_STAGE_STATE_INVALID, accounts, commandId, content);
        }
        if (project.hasRunningStage() || project.nextStage() != stage) {
            return finish(draft, false, REASON_STAGE_ORDER, accounts, commandId, content);
        }
        CashAccount contractor = accounts.get(project.contractorAccountId());
        if (contractor == null || !contractor.ownerId().equals(project.contractorOwnerId())) {
            return finish(draft, false, REASON_CONTRACTOR_INVALID, accounts, commandId, content);
        }
        long completesAt;
        try {
            completesAt = Math.addExact(now, target.durationMillis());
        } catch (ArithmeticException overflow) {
            return finish(draft, false, REASON_OVERFLOW, accounts, commandId, content);
        }
        CashLedger.TransferOutcome transfer = CashLedger.computeTransfer(accounts,
                state.config().companyOwnerId(), state.config().companyAccountId(),
                project.contractorAccountId(), target.cost());
        if (!transfer.accepted()) {
            return finish(draft, false, transfer.reason(), accounts, commandId, content);
        }
        List<StageRecord> stages = new java.util.ArrayList<>(project.stages());
        stages.set(stage.ordinal(),
                new StageRecord(target.plan(), StageStatus.IN_PROGRESS, now, completesAt, null));
        draft.projects.put(projectId, new Project(project.projectId(), project.budget(),
                project.contractorAccountId(), project.contractorOwnerId(),
                project.registeredAt(), project.amortizationDays(), stages, project.approvalRef(),
                project.approvedAt(), project.amortized(), project.amortizedAt()));
        return finish(draft, true, REASON_STARTED, transfer.accounts(), commandId, content);
    }

    /**
     * 阶段完成：到期后记录结果证据；不移动现金、不记录收入。
     *
     * @param state     当前状态
     * @param commandId 命令标识
     * @param actorOwnerId 发起人
     * @param projectId 项目标识
     * @param stage     阶段
     * @param evidence  结果证据
     * @param now       当前模拟毫秒
     * @param accounts  账户表
     * @return 候选结果
     */
    public static Outcome complete(State state, String commandId, String actorOwnerId,
                                   String projectId, Stage stage, Evidence evidence, long now,
                                   Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        Objects.requireNonNull(stage, "阶段");
        Objects.requireNonNull(evidence, "结果证据");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("COMPLETE", commandId, projectId, stage,
                evidence.requirementId(), evidence.reference(), evidence.observedAt());
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        if (now < state.watermark()) {
            return new Outcome(false, REASON_TIME_REWIND, state, accounts);
        }
        Draft draft = new Draft(state);
        draft.watermark = now;
        Project project = projectId == null ? null : state.projects().get(projectId);
        if (project == null) {
            return finish(draft, false, REASON_UNKNOWN_PROJECT, accounts, commandId, content);
        }
        StageRecord target = project.stage(stage);
        if (target == null || target.status() != StageStatus.IN_PROGRESS) {
            return finish(draft, false, REASON_STAGE_STATE_INVALID, accounts, commandId, content);
        }
        if (now < target.completesAt()) {
            return finish(draft, false, REASON_NOT_DUE, accounts, commandId, content);
        }
        String invalid = checkEvidence(target, evidence, now);
        if (invalid != null) {
            return finish(draft, false, invalid, accounts, commandId, content);
        }
        List<StageRecord> stages = new java.util.ArrayList<>(project.stages());
        stages.set(stage.ordinal(),
                new StageRecord(target.plan(), StageStatus.COMPLETED, target.beganAt(),
                        target.completesAt(), evidence));
        draft.projects.put(projectId, new Project(project.projectId(), project.budget(),
                project.contractorAccountId(), project.contractorOwnerId(),
                project.registeredAt(), project.amortizationDays(), stages, project.approvalRef(),
                project.approvedAt(), project.amortized(), project.amortizedAt()));
        return finish(draft, true, REASON_COMPLETED, accounts, commandId, content);
    }

    /**
     * 阶段失败：记录失败证据并真实终止项目；不付后续阶段，已付开发/注册投入一次性减值。
     *
     * @param state     当前状态
     * @param commandId 命令标识
     * @param actorOwnerId 发起人
     * @param projectId 项目标识
     * @param stage     失败阶段
     * @param evidence  失败证据
     * @param now       当前模拟毫秒
     * @param accounts  账户表
     * @return 候选结果
     */
    public static Outcome fail(State state, String commandId, String actorOwnerId,
                               String projectId, Stage stage, Evidence evidence, long now,
                               Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        Objects.requireNonNull(stage, "阶段");
        Objects.requireNonNull(evidence, "失败证据");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("FAIL", commandId, projectId, stage,
                evidence.requirementId(), evidence.reference(), evidence.observedAt());
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        if (now < state.watermark()) {
            return new Outcome(false, REASON_TIME_REWIND, state, accounts);
        }
        Draft draft = new Draft(state);
        draft.watermark = now;
        Project project = projectId == null ? null : state.projects().get(projectId);
        if (project == null) {
            return finish(draft, false, REASON_UNKNOWN_PROJECT, accounts, commandId, content);
        }
        if (project.status() == ProjectStatus.FAILED) {
            return finish(draft, false, REASON_PROJECT_TERMINATED, accounts, commandId, content);
        }
        StageRecord target = project.stage(stage);
        if (target == null || target.status() != StageStatus.IN_PROGRESS) {
            return finish(draft, false, REASON_STAGE_STATE_INVALID, accounts, commandId, content);
        }
        String invalid = checkEvidence(target, evidence, now);
        if (invalid != null) {
            return finish(draft, false, invalid, accounts, commandId, content);
        }
        List<StageRecord> stages = new java.util.ArrayList<>(project.stages());
        stages.set(stage.ordinal(),
                new StageRecord(target.plan(), StageStatus.FAILED, target.beganAt(),
                        target.completesAt(), evidence));
        draft.projects.put(projectId, new Project(project.projectId(), project.budget(),
                project.contractorAccountId(), project.contractorOwnerId(),
                project.registeredAt(), project.amortizationDays(), stages, null, -1,
                project.amortized(), project.amortizedAt()));
        return finish(draft, true, REASON_FAILED, accounts, commandId, content);
    }

    /**
     * 批准：三阶段全部完成后，凭批准事实把在研资产转为可用资产。
     *
     * <p>{@code approvalRef} 只是纯核记录；是否真有董事预算与权限由 World 另行核对，
     * 本类不把字符串引用当作已授权。</p>
     *
     * @param state       当前状态
     * @param commandId   命令标识
     * @param actorOwnerId 发起人
     * @param projectId   项目标识
     * @param approvalRef 批准事实引用
     * @param now         当前模拟毫秒
     * @param accounts    账户表
     * @return 候选结果
     */
    public static Outcome approve(State state, String commandId, String actorOwnerId,
                                  String projectId, String approvalRef, long now,
                                  Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(now);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("APPROVE", commandId, projectId, approvalRef);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        if (now < state.watermark()) {
            return new Outcome(false, REASON_TIME_REWIND, state, accounts);
        }
        Draft draft = new Draft(state);
        draft.watermark = now;
        Project project = projectId == null ? null : state.projects().get(projectId);
        if (project == null) {
            return finish(draft, false, REASON_UNKNOWN_PROJECT, accounts, commandId, content);
        }
        if (approvalRef == null || approvalRef.isBlank()) {
            return finish(draft, false, REASON_INVALID_APPROVAL, accounts, commandId, content);
        }
        if (project.status() == ProjectStatus.FAILED) {
            return finish(draft, false, REASON_PROJECT_TERMINATED, accounts, commandId, content);
        }
        if (project.approvalRef() != null) {
            return finish(draft, false, REASON_ALREADY_APPROVED, accounts, commandId, content);
        }
        if (project.status() != ProjectStatus.COMPLETED) {
            return finish(draft, false, REASON_PROJECT_INCOMPLETE, accounts, commandId, content);
        }
        draft.projects.put(projectId, new Project(project.projectId(), project.budget(),
                project.contractorAccountId(), project.contractorOwnerId(),
                project.registeredAt(), project.amortizationDays(), project.stages(), approvalRef,
                now, project.amortized(), project.amortizedAt()));
        return finish(draft, true, REASON_APPROVED, accounts, commandId, content);
    }

    /**
     * 摊销：按「累计应摊额 − 已记摊销」计入费用，寿命届满时结清尾差。
     *
     * <p>重复调用同一截止日不会重复扣钱；截止日不得早于批准日、不得早于上次截止日、
     * 也不得晚于当前时刻。摊销不移动现金。</p>
     *
     * @param state     当前状态
     * @param commandId 命令标识
     * @param actorOwnerId 发起人
     * @param projectId 项目标识
     * @param asOf      摊销截止日
     * @param now       当前模拟毫秒
     * @param accounts  账户表
     * @return 候选结果
     */
    public static Outcome amortize(State state, String commandId, String actorOwnerId,
                                   String projectId, long asOf, long now,
                                   Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(now);
        requireTime(asOf);
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts);
        }
        requireAccounts(state.config(), accounts);
        String content = content("AMORTIZE", commandId, projectId, asOf);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        if (now < state.watermark()) {
            return new Outcome(false, REASON_TIME_REWIND, state, accounts);
        }
        Draft draft = new Draft(state);
        draft.watermark = now;
        Project project = projectId == null ? null : state.projects().get(projectId);
        if (project == null) {
            return finish(draft, false, REASON_UNKNOWN_PROJECT, accounts, commandId, content);
        }
        if (project.status() == ProjectStatus.FAILED) {
            return finish(draft, false, REASON_PROJECT_TERMINATED, accounts, commandId, content);
        }
        if (project.approvalRef() == null) {
            return finish(draft, false, REASON_NOT_APPROVED, accounts, commandId, content);
        }
        if (asOf < project.approvedAt() || asOf < project.amortizedAt()) {
            return finish(draft, false, REASON_AMORTIZATION_REWIND, accounts, commandId, content);
        }
        if (asOf > now) {
            return finish(draft, false, REASON_FUTURE_AS_OF, accounts, commandId, content);
        }
        long elapsedDays;
        try {
            elapsedDays = Math.subtractExact(asOf, project.approvedAt()) / DAY_MILLIS;
        } catch (ArithmeticException overflow) {
            return finish(draft, false, REASON_OVERFLOW, accounts, commandId, content);
        }
        BigDecimal target = cumulativeAmortization(project.availableAsset(), elapsedDays,
                project.amortizationDays());
        if (target.compareTo(project.amortized()) < 0) {
            return finish(draft, false, REASON_AMORTIZATION_REWIND, accounts, commandId, content);
        }
        draft.projects.put(projectId, new Project(project.projectId(), project.budget(),
                project.contractorAccountId(), project.contractorOwnerId(),
                project.registeredAt(), project.amortizationDays(), project.stages(),
                project.approvalRef(), project.approvedAt(), target, asOf));
        return finish(draft, true, REASON_AMORTIZED, accounts, commandId, content);
    }

    /**
     * 派生报表：实际付款、费用、在研/可用资产、累计摊销与减值分列，现金取唯一账户表。
     *
     * <p>权益 = 期初权益 − 费用 − 摊销 − 减值；期初权益永不重置，投资本身不产生收入。
     * 未来净资本流与分红须由真实 {@code ReportContext} 承载，当前纯核未接，所以传入账户表出现
     * 外部注资或分红时会如实表现为不平衡，而不是改写期初权益。现金余额不足或承包方所有者与立项
     * 时不一致时抛异常。</p>
     *
     * @param state    状态
     * @param accounts 账户表
     * @return 报表
     * @throws IllegalArgumentException 账户结构或承包方绑定不合法
     */
    public static Report report(State state, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireAccounts(state.config(), accounts);
        BigDecimal paid = ZERO;
        BigDecimal expense = ZERO;
        BigDecimal work = ZERO;
        BigDecimal available = ZERO;
        BigDecimal amortized = ZERO;
        BigDecimal impairment = ZERO;
        for (Project project : state.projects().values()) {
            CashAccount contractor = accounts.get(project.contractorAccountId());
            if (contractor == null || !contractor.ownerId().equals(project.contractorOwnerId())) {
                throw new IllegalArgumentException("承包方账户所有者与立项记录不一致："
                        + project.projectId());
            }
            paid = paid.add(project.paid());
            expense = expense.add(project.expensed());
            work = work.add(project.workInProgress());
            available = available.add(project.availableAsset());
            amortized = amortized.add(project.amortized());
            impairment = impairment.add(project.impairment());
        }
        BigDecimal cash = accounts.get(state.config().companyAccountId()).cash();
        BigDecimal availableNet = CashLedger.money(available.subtract(amortized));
        BigDecimal rnd = CashLedger.money(work.add(availableNet));
        BigDecimal assets = CashLedger.money(cash.add(state.config().otherAssets()).add(rnd));
        BigDecimal profit = CashLedger.money(ZERO.subtract(expense).subtract(amortized)
                .subtract(impairment));
        BigDecimal equity = CashLedger.money(state.initialEquity().add(profit));
        BigDecimal world = worldCash(accounts);
        boolean balanced = assets.compareTo(equity) == 0
                && world.compareTo(state.initialWorldCash()) == 0;
        return new Report(CashLedger.money(cash), CashLedger.money(state.config().otherAssets()),
                CashLedger.money(paid), CashLedger.money(expense), CashLedger.money(work),
                CashLedger.money(available), CashLedger.money(amortized), availableNet, rnd,
                CashLedger.money(impairment), profit, equity, world, balanced);
    }

    /**
     * 校验并返回报表；资产、权益或世界现金不守恒时抛异常。
     *
     * @param state    状态
     * @param accounts 账户表
     * @return 已通过校验的报表
     * @throws IllegalStateException    资产、权益或世界现金不守恒
     * @throws IllegalArgumentException 账户结构或承包方绑定不合法
     */
    public static Report validate(State state, Map<String, CashAccount> accounts) {
        Report report = report(state, accounts);
        if (!report.balanced()) {
            throw new IllegalStateException("研发账不平衡：资产=" + report.companyCash()
                    + "+" + report.otherAssets() + "+" + report.rndAsset()
                    + "，权益=" + report.equity()
                    + "，世界现金=" + report.worldCash()
                    + "，开局世界现金=" + state.initialWorldCash());
        }
        return report;
    }

    // ------------------------------------------------------------------ 内部

    private static final class Draft {
        private final Config config;
        private final BigDecimal initialEquity;
        private final BigDecimal initialWorldCash;
        private final Map<String, Project> projects;
        private final Map<String, String> commandContents;
        private final Map<String, String> commandOutcomes;
        private long watermark;

        Draft(State state) {
            config = state.config();
            initialEquity = state.initialEquity();
            initialWorldCash = state.initialWorldCash();
            watermark = state.watermark();
            projects = new LinkedHashMap<>(state.projects());
            commandContents = new TreeMap<>(state.commandContents());
            commandOutcomes = new TreeMap<>(state.commandOutcomes());
        }

        State build() {
            return new State(config, initialEquity, initialWorldCash, watermark, projects,
                    commandContents, commandOutcomes);
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

    private static String checkEvidence(StageRecord stage, Evidence evidence, long now) {
        if (!stage.evidenceRequirement().equals(evidence.requirementId())) {
            return REASON_EVIDENCE_MISMATCH;
        }
        if (evidence.observedAt() < stage.beganAt() || evidence.observedAt() > now) {
            return REASON_INVALID_EVIDENCE;
        }
        return null;
    }

    private static BigDecimal cumulativeAmortization(BigDecimal cost, long elapsedDays,
                                                     int lifeDays) {
        if (elapsedDays <= 0) {
            return ZERO;
        }
        if (elapsedDays >= lifeDays) {
            return CashLedger.money(cost);
        }
        BigDecimal target = cost.multiply(BigDecimal.valueOf(elapsedDays))
                .divide(BigDecimal.valueOf(lifeDays), 2, RoundingMode.HALF_UP);
        return CashLedger.money(target.min(cost));
    }

    private static boolean authorized(State state, String actorOwnerId) {
        return state.config().companyOwnerId().equals(actorOwnerId);
    }

    private static void requireAccounts(Config config, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(accounts, "账户表");
        CashAccount company = accounts.get(config.companyAccountId());
        if (company == null) {
            throw new IllegalArgumentException("缺少公司账户：" + config.companyAccountId());
        }
        if (!company.ownerId().equals(config.companyOwnerId())) {
            throw new IllegalArgumentException("公司账户所有者与配置不一致");
        }
        for (Map.Entry<String, CashAccount> entry : accounts.entrySet()) {
            CashAccount account = entry.getValue();
            if (account == null) {
                throw new IllegalArgumentException("账户记录不能为空：" + entry.getKey());
            }
            requireText(account.ownerId(), "账户所有者");
            if (CashLedger.money(account.cash()).signum() < 0) {
                throw new IllegalArgumentException("账户现金不能为负：" + entry.getKey());
            }
            if (account.ownerId().equals(config.companyOwnerId())
                    && !entry.getKey().equals(config.companyAccountId())) {
                throw new IllegalArgumentException(
                        "公司所有者只能有一个经营现金账户：" + entry.getKey());
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

    private static String stageContents(List<StagePlan> stages) {
        StringBuilder text = new StringBuilder();
        for (StagePlan plan : stages) {
            if (text.length() > 0) {
                text.append('|');
            }
            text.append(plan.stage()).append(':').append(plan.cost().toPlainString())
                    .append(':').append(plan.durationMillis()).append(':')
                    .append(plan.evidenceRequirement());
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
