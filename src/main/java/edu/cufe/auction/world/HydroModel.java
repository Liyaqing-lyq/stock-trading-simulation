package edu.cufe.auction.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 水库式水电经营纯核：来水、蓄水、过机、生态/指定放流与满库溢流五项守恒；过机水量按冻结转换率成为
 * 电量，电量按当刻有限需求与电价形成真实应收；运维与检修形成真实现金与费用。
 *
 * <p><b>冻结的实验政策</b>（是本项目模拟假设，不声称现实水文、电价或会计口径，也不是监管合规证据）：</p>
 * <ul>
 *   <li><b>水量五项守恒</b>：{@code 累计来水 = 蓄水增量 + 累计过机 + 累计生态/指定放流 + 累计满库溢流}。
 *       该式是每个状态的构造期硬不变量；生态/指定放流是独立放流项，不从过机水量里"借用"，
 *       因此三者可在报表里分列核对。</li>
 *   <li><b>冻结离散物理时间格</b>：一格为 {@link #PHYSICS_TICK_MILLIS}（1 模拟分钟）。所有业务时刻
 *       （推进目标、调度、检修、结算）必须落在格边界上；一次 {@code advance} 在内部逐格结算，
 *       与调用方把同一区间切成几次推进无关。每格顺序固定：计入本格来水 → 先扣生态/指定放流 →
 *       再过机发电 → 最后满库溢流。</li>
 *   <li><b>显式先结算</b>：{@code dispatch}/{@code dispatchOutput}/{@code beginMaintenance}/
 *       {@code collectReceivable} 要求 {@code now == watermark}；{@code now > watermark} 返回
 *       {@link #REASON_TIME_NOT_SETTLED} 而不是把时间轴前移。时间只能由 {@code advance} 推进，
 *       因此任何路径都不会跳过中间区间的来水、生态放流、运维或发电。</li>
 *   <li><b>不跨格结转</b>：电力不可储存、负荷需求不可储存、机组未用容量与未执行的目标过机量都不可
 *       结转。每格发电量同时受本格调度流量与本格装机可用水量约束，每格交付量受本格需求上限约束；
 *       某一格没发出的电、没满足的需求都不会在后面的格被超额补发或补交。水库存水是真实物理存量，
 *       可以跨格保留。</li>
 *   <li>电量只由过机水量乘冻结转换率得到：{@code 电量 = 过机 m³ × 转换率(kWh/m³)}；装机
 *       {@code kW × 生成小时} 折算为每格可用过机水量上界做校验，不把 m³ 与 kWh 直接取小。
 *       生态/指定放流、满库溢流与停机时段都不发电。</li>
 *   <li>来水只来自调用方给定的冻结有限序列（受信运行提供），经理无法自报来水；推进越过序列终点
 *       直接拒绝，不用默认流量臆造水体。</li>
 *   <li>生态欠账 = 强制下泄义务累计 − 实际放流累计，只增不减（不假装已经补放）。</li>
 *   <li>运维成本按日历毫秒累计（分/h），推进时先结旧应付再付本期；现金不足转入应付，成本不消灭。
 *       检修在开工时一次性真实付款并当期费用化，期间生成毫秒停止累计（容量为 0）。</li>
 *   <li>折旧按既有设备原值直线累计（含小数实验日），累计一次四舍五入，上限为原值。</li>
 * </ul>
 *
 * <p><b>单位与精度</b>：水量与时间 {@code long}（m³、模拟毫秒）；电量 kWh 为 {@link BigDecimal}
 * 精确到小数点后 6 位；金额为 {@link BigDecimal} 精确到分（内部按分累计）；电价 元/kWh 精确到
 * 小数点后 4 位；转换率 kWh/m³ 精确到小数点后 6 位。禁止 {@code double} 参与记账。
 * 时间原点固定为 0，只有正间隔推进。</p>
 *
 * <p><b>结果三态</b>：业务成功 {@code accepted=true}；业务拒绝 {@code accepted=false} 加原因码
 * （不改水量、现金、应收、应付与权益，只记录命令索引以便重放）；技术失败为非法入参抛
 * {@link IllegalArgumentException}、状态序列化失败抛 {@link IllegalStateException}，都不产生半应用状态。</p>
 *
 * <p><b>幂等</b>：每个动作携带 {@code commandId} 与完整语义；同标识同内容返回原回报且不再推进水量、
 * 不再重复来水与电费，同标识不同内容抛 {@link IllegalArgumentException}（{@code COMMAND_CONTENT_CONFLICT}）。
 * 迟到重放保留当前状态与当前账户，不回滚。</p>
 *
 * <p><b>命令语义与时刻的审计</b>：命令内容只含动作类型、命令标识与业务参数，<b>刻意不含</b>
 * {@code now}——这样迟到重放仍能按原内容匹配并返回原回执；时刻语义改由「{@code now} 必须等于
 * watermark」的入口门闸承担。代价是同 ID 重放对 {@code now} 不敏感（重放返回原回执、不改状态），
 * 因此不能靠改 {@code now} 绕过结算；这是冻结取舍，不是遗漏。</p>
 *
 * <p><b>现金</b>：只有唯一传入的 {@link CashLedger.CashAccount} 账户表承载现金；本类不缓存余额，
 * 也不为电站复制现金。公司、有限购电方与运维承包方各自是独立真实账户。合同触发的购电方付款由受信
 * 运行按结算条款执行，本类不冒充购电方人物授权，也不冒充 World。</p>
 *
 * <p><b>权益</b>：{@code 期初权益 = 开局现金 + 既有设备原值}，一经 {@link #open} 建立永不重置；
 * 外部注资、分红、债务与重资本不在本纯核范围（缺口见 handoff），传入出现外部资本流的账户表时报表
 * 会如实判为不平衡，而不是重算期初权益。</p>
 */
public final class HydroModel {

    /** 冻结物理时间格：1 模拟分钟。所有业务时刻必须落在它的整数倍上。 */
    public static final long PHYSICS_TICK_MILLIS = 60_000L;

    /** 一小时的模拟毫秒数；水与折旧的累计口径都用它，不读系统时钟。 */
    public static final long HOUR_MILLIS = 3_600_000L;

    /** 一个实验日的模拟毫秒数。 */
    public static final long DAY_MILLIS = 86_400_000L;

    /** 电量精度：kWh 保留 6 位小数。 */
    public static final int ENERGY_SCALE = 6;

    /** 调度已设定（未发生水量或现金动作）。 */
    public static final String REASON_DISPATCHED = "DISPATCHED";
    /** 时间已推进，水量、电量、应收与成本按逐格累计结算一次。 */
    public static final String REASON_ADVANCED = "ADVANCED";
    /** 检修已开工，已向承包方真实付款并按费用化处理。 */
    public static final String REASON_MAINTENANCE_STARTED = "MAINTENANCE_STARTED";
    /** 应收已按可付金额部分或全部结算。 */
    public static final String REASON_COLLECTED = "COLLECTED";

    /** 发起人不是公司财产主体。 */
    public static final String REASON_NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 目标时刻早于已见时间轴，不允许回填。 */
    public static final String REASON_TIME_REWIND = "TIME_REWIND";
    /**
     * 目标时刻晚于已见时间轴：除 {@code advance} 外的动作不能自行跳时间，必须先 {@code advance} 结算。
     * 否则会静默丢失中间区间的来水、生态放流、运维与发电。
     */
    public static final String REASON_TIME_NOT_SETTLED = "TIME_NOT_SETTLED";
    /** 目标时刻等于已见时间轴，没有正间隔可推进。 */
    public static final String REASON_NO_ELAPSED_TIME = "NO_ELAPSED_TIME";
    /** 目标时刻超出冻结来水序列终点；不臆造来水。 */
    public static final String REASON_INFLOW_SERIES_ENDED = "INFLOW_SERIES_ENDED";
    /** 调度流量或出力折算后超过装机/最大过机。 */
    public static final String REASON_DISPATCH_OVER_CAPACITY = "DISPATCH_OVER_CAPACITY";
    /** 出力折算为整 m³/h 后为 0，无法表达该出力。 */
    public static final String REASON_DISPATCH_TOO_SMALL = "DISPATCH_TOO_SMALL";
    /** 时间或金额累计溢出 {@code long}。 */
    public static final String REASON_OVERFLOW = "OVERFLOW";
    /** 公司经营账户现金不足以支付本次检修。 */
    public static final String REASON_INSUFFICIENT_CASH = "INSUFFICIENT_CASH";
    /** 旧检修尚未结清，不能开新检修。 */
    public static final String REASON_MAINTENANCE_IN_PROGRESS = "MAINTENANCE_IN_PROGRESS";
    /** 应收为零或购电方无现金，本次结算金额为 0。 */
    public static final String REASON_NOTHING_TO_COLLECT = "NOTHING_TO_COLLECT";
    /** 购电方或承包方账户缺失，或与公司共用同一账户。 */
    public static final String REASON_COUNTERPARTY_INVALID = "COUNTERPARTY_INVALID";

    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");
    private static final ObjectMapper JSON = new ObjectMapper();

    private HydroModel() {
    }

    /**
     * 电站物理参数（EXP 实验设定）。
     *
     * @param capacityKw                  装机容量 kW，必须为正
     * @param kilowattHoursPerCubicMetre  过机水量到电量的冻结转换率 kWh/m³，必须为正、6 位小数
     * @param maxTurbineCubicMetresPerHour 最大过机流量 m³/h，非负且不得超出装机
     * @param deadStorageCubicMetres      死库容 m³，非负；其以下水量不可放流
     * @param maximumStorageCubicMetres   正常蓄水位对应库容 m³，必须大于死库容
     * @param initialStorageCubicMetres   开局蓄水 m³，必须落在 [死库容, 库容] 内
     * @param ecologicalCubicMetresPerHour 生态基流 m³/h，非负；日历时间强制下泄且不发电
     * @param designatedCubicMetresPerHour 指定放流 m³/h，非负；日历时间强制下泄且不发电
     */
    public record PhysicalSpec(long capacityKw, BigDecimal kilowattHoursPerCubicMetre,
                               long maxTurbineCubicMetresPerHour, long deadStorageCubicMetres,
                               long maximumStorageCubicMetres, long initialStorageCubicMetres,
                               long ecologicalCubicMetresPerHour,
                               long designatedCubicMetresPerHour) {

        /** 校验容量、转换率、流量、库容与该组参数的自洽性。 */
        public PhysicalSpec {
            if (capacityKw <= 0) {
                throw new IllegalArgumentException("装机容量必须为正 kW");
            }
            kilowattHoursPerCubicMetre = energyRate(kilowattHoursPerCubicMetre);
            if (maxTurbineCubicMetresPerHour < 0) {
                throw new IllegalArgumentException("最大过机流量不能为负");
            }
            if (deadStorageCubicMetres < 0) {
                throw new IllegalArgumentException("死库容不能为负");
            }
            if (maximumStorageCubicMetres <= deadStorageCubicMetres) {
                throw new IllegalArgumentException("正常蓄水位库容必须大于死库容");
            }
            if (initialStorageCubicMetres < deadStorageCubicMetres
                    || initialStorageCubicMetres > maximumStorageCubicMetres) {
                throw new IllegalArgumentException("开局蓄水必须落在死库容与库容之间");
            }
            if (ecologicalCubicMetresPerHour < 0 || designatedCubicMetresPerHour < 0) {
                throw new IllegalArgumentException("生态基流与指定放流不能为负");
            }
            if (kilowattHoursPerCubicMetre.multiply(
                    BigDecimal.valueOf(maxTurbineCubicMetresPerHour)).compareTo(
                    BigDecimal.valueOf(capacityKw)) > 0) {
                throw new IllegalArgumentException("最大过机流量超出装机容量");
            }
        }

        /**
         * 强制下泄总流量 m³/h（生态基流 + 指定放流）。
         *
         * @return 强制下泄流量
         */
        public long mandatoryCubicMetresPerHour() {
            return Math.addExact(ecologicalCubicMetresPerHour(), designatedCubicMetresPerHour());
        }
    }

    /**
     * 冻结来水序列中的一段：半开区间 [from, until)，单位 m³/h。
     *
     * @param fromMillis          段起始时刻
     * @param untilMillis         段结束时刻，必须大于起始
     * @param cubicMetresPerHour  该段来水流量 m³/h，非负
     */
    public record InflowSegment(long fromMillis, long untilMillis, long cubicMetresPerHour) {

        /** 校验区间与流量。 */
        public InflowSegment {
            if (fromMillis < 0) {
                throw new IllegalArgumentException("来水段起始时刻不能为负");
            }
            if (untilMillis <= fromMillis) {
                throw new IllegalArgumentException("来水段必须是正区间");
            }
            if (cubicMetresPerHour < 0) {
                throw new IllegalArgumentException("来水流量不能为负");
            }
        }
    }

    /**
     * 冻结的有限来水时间序列：必须从时刻 0 起连续覆盖到终点，超出终点的推进被拒绝。
     *
     * @param segments 按时间升序、首尾相接的来水段
     */
    public record InflowSeries(List<InflowSegment> segments) {

        /** 校验非空、从 0 开始且严格首尾相接。 */
        public InflowSeries {
            Objects.requireNonNull(segments, "来水段表");
            if (segments.isEmpty()) {
                throw new IllegalArgumentException("来水序列不能为空");
            }
            List<InflowSegment> copied = List.copyOf(segments);
            long cursor = 0;
            for (InflowSegment segment : copied) {
                if (segment.fromMillis() != cursor) {
                    throw new IllegalArgumentException("来水序列必须从 0 起连续覆盖");
                }
                cursor = segment.untilMillis();
            }
            segments = copied;
        }

        /**
         * 序列终点时刻；推进不得超过它。
         *
         * @return 最后一个来水段的结束时刻
         */
        public long endsAtMillis() {
            return segments.get(segments.size() - 1).untilMillis();
        }

        /**
         * 截至某时刻的累计来水量，按段内累计后向下取整，保证非整除切片与合并推进结果一致。
         *
         * @param untilMillis 统计时刻，须落在 [0, 终点] 内
         * @return 累计来水 m³
         * @throws ArithmeticException 累计溢出 {@code long}
         */
        public long cumulativeCubicMetres(long untilMillis) {
            if (untilMillis < 0 || untilMillis > endsAtMillis()) {
                throw new IllegalArgumentException("累计来水时刻超出冻结序列范围：" + untilMillis);
            }
            long total = 0;
            for (InflowSegment segment : segments) {
                if (segment.fromMillis() >= untilMillis) {
                    break;
                }
                long span = Math.min(segment.untilMillis(), untilMillis) - segment.fromMillis();
                total = Math.addExact(total,
                        Math.multiplyExact(segment.cubicMetresPerHour(), span) / HOUR_MILLIS);
            }
            return total;
        }
    }

    /**
     * 有限购电合同（EXP 实验设定）。
     *
     * @param demandKilowattHoursPerHour 需求上限 kWh/h，非负
     * @param pricePerKilowattHour       ���算电价 元/kWh，正数、4 位小数
     * @param untilMillis                合同止时刻，必须为正
     */
    public record SalesContract(long demandKilowattHoursPerHour,
                                BigDecimal pricePerKilowattHour, long untilMillis) {

        /** 校验需求、电价与合同止。 */
        public SalesContract {
            if (demandKilowattHoursPerHour < 0) {
                throw new IllegalArgumentException("需求上限不能为负");
            }
            if (pricePerKilowattHour == null) {
                throw new IllegalArgumentException("电价不能为空");
            }
            try {
                pricePerKilowattHour = pricePerKilowattHour.setScale(4, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException("电价必须精确到 4 位小数", failure);
            }
            if (pricePerKilowattHour.signum() <= 0) {
                throw new IllegalArgumentException("电价必须为正");
            }
            if (untilMillis <= 0) {
                throw new IllegalArgumentException("合同止必须为正的模拟毫秒");
            }
        }
    }

    /**
     * 经营条款（EXP 实验设定）。
     *
     * @param opexCentsPerHour   运维现金成本 分/h，非负
     * @param equipmentCost      既有设备原值 元，非负
     * @param equipmentLifeDays  直线折旧寿命（实验日），必须为正
     */
    public record OperatingTerms(long opexCentsPerHour, BigDecimal equipmentCost,
                                 int equipmentLifeDays) {

        /** 校验成本、设备原值与寿命。 */
        public OperatingTerms {
            if (opexCentsPerHour < 0) {
                throw new IllegalArgumentException("运维成本不能为负");
            }
            equipmentCost = CashLedger.money(equipmentCost);
            if (equipmentCost.signum() < 0) {
                throw new IllegalArgumentException("设备原值不能为负");
            }
            if (equipmentLifeDays <= 0 || equipmentLifeDays > 1_000_000) {
                throw new IllegalArgumentException("折旧寿命必须为合理的正实验日");
            }
        }
    }

    /**
     * 电站绑定：财产主体、三个真实账户与冻结输入。
     *
     * @param companyOwnerId    公司财产主体；唯一有权发起经营动作的身份
     * @param companyAccountId  公司唯一经营现金账户
     * @param buyerAccountId    有限购电方账户；所有者从权威账户表解析
     * @param contractorAccountId 运维承包方账户；所有者从权威账户表解析
     * @param physical          物理参数
     * @param inflow            冻结来水序列
     * @param contract          有限购电合同
     * @param terms             经营条款
     */
    public record Config(String companyOwnerId, String companyAccountId, String buyerAccountId,
                         String contractorAccountId, PhysicalSpec physical, InflowSeries inflow,
                         SalesContract contract, OperatingTerms terms) {

        /** 校验标识与嵌套参数。 */
        public Config {
            requireText(companyOwnerId, "公司所有者");
            requireText(companyAccountId, "公司账户");
            requireText(buyerAccountId, "购电方账户");
            requireText(contractorAccountId, "承包方账户");
            Objects.requireNonNull(physical, "物理参数");
            Objects.requireNonNull(inflow, "来水序列");
            Objects.requireNonNull(contract, "购电合同");
            Objects.requireNonNull(terms, "经营条款");
            if (buyerAccountId.equals(companyAccountId)
                    || contractorAccountId.equals(companyAccountId)
                    || buyerAccountId.equals(contractorAccountId)) {
                throw new IllegalArgumentException("公司、购电方与承包方必须是不同账户");
            }
        }
    }

    /**
     * 当前调度段：从生成毫秒基点起按给定流量取水。
     *
     * @param cubicMetresPerHour   调度过机流量 m³/h，非负
     * @param baseGeneratingMillis 设定时刻的累计生成毫秒（停机时间不计入）
     * @param baseCubicMetres      设定时刻的累计过机水量 m³
     */
    public record Dispatch(long cubicMetresPerHour, long baseGeneratingMillis,
                           long baseCubicMetres) {

        /** 校验调度段的非负性。 */
        public Dispatch {
            if (cubicMetresPerHour < 0) {
                throw new IllegalArgumentException("调度流量不能为负");
            }
            if (baseGeneratingMillis < 0 || baseCubicMetres < 0) {
                throw new IllegalArgumentException("调度段基点不能为负");
            }
        }
    }

    /**
     * 一次真实检修：区间 [startedAt, until) 内可用容量为 0，成本在开工时已付。
     *
     * @param startedAtMillis 开工时刻
     * @param untilMillis     结束时刻，必须大于开工
     * @param cost            已付检修成本 元，非负
     */
    public record Outage(long startedAtMillis, long untilMillis, BigDecimal cost) {

        /** 校验区间与金额。 */
        public Outage {
            if (startedAtMillis < 0) {
                throw new IllegalArgumentException("检修开工时刻不能为负");
            }
            if (untilMillis <= startedAtMillis) {
                throw new IllegalArgumentException("检修必须是正耗时");
            }
            cost = CashLedger.money(cost);
            if (cost.signum() < 0) {
                throw new IllegalArgumentException("检修成本不能为负");
            }
        }
    }

    /**
     * 不可变电站状态；现金不在其中，只由唯一账户表承载。
     *
     * @param storageCubicMetres          当前蓄水 m³
     * @param inflowCubicMetres           累计来水 m³
     * @param turbineCubicMetres          累计过机 m³
     * @param mandatoryReleaseCubicMetres 累计生态/指定放流 m³（强制下泄，不发电）
     * @param overflowSpillCubicMetres    累计满库溢流 m³
     * @param outageMillisCumulative      累计停机毫秒（生成毫秒的反面）
     * @param generatedKilowattHours      累计发电量 kWh
     * @param deliveredKilowattHours      累计交付电量 kWh
     * @param collectedCash               已从购电方收到的现金 元
     * @param opexAccrued                 累计运维成本 元
     * @param opexPaid                    累计已付运维现金 元
     * @param maintenanceCost             累计检修费用 元
     * @param depreciation                累计折旧 元
     * @param dispatch                    当前调度段
     * @param outage                      在途检修；无则为 {@code null}
     */
    public record Plant(long storageCubicMetres, long inflowCubicMetres, long turbineCubicMetres,
                        long mandatoryReleaseCubicMetres, long overflowSpillCubicMetres,
                        long outageMillisCumulative, BigDecimal generatedKilowattHours,
                        BigDecimal deliveredKilowattHours, BigDecimal collectedCash,
                        BigDecimal opexAccrued, BigDecimal opexPaid, BigDecimal maintenanceCost,
                        BigDecimal depreciation, Dispatch dispatch, Outage outage) {

        /** 校验非负、精度与调度/检修记录。 */
        public Plant {
            if (storageCubicMetres < 0 || inflowCubicMetres < 0 || turbineCubicMetres < 0
                    || mandatoryReleaseCubicMetres < 0 || overflowSpillCubicMetres < 0
                    || outageMillisCumulative < 0) {
                throw new IllegalArgumentException("水量与停机耗时不能为负");
            }
            generatedKilowattHours = energyAmount(generatedKilowattHours);
            deliveredKilowattHours = energyAmount(deliveredKilowattHours);
            collectedCash = CashLedger.money(collectedCash);
            opexAccrued = CashLedger.money(opexAccrued);
            opexPaid = CashLedger.money(opexPaid);
            maintenanceCost = CashLedger.money(maintenanceCost);
            depreciation = CashLedger.money(depreciation);
            if (collectedCash.signum() < 0 || opexAccrued.signum() < 0 || opexPaid.signum() < 0
                    || maintenanceCost.signum() < 0 || depreciation.signum() < 0) {
                throw new IllegalArgumentException("现金与费用累计不能为负");
            }
            if (opexPaid.compareTo(opexAccrued) > 0) {
                throw new IllegalArgumentException("已付运维现金不得超过累计运维成本");
            }
            Objects.requireNonNull(dispatch, "调度段");
        }
    }

    /**
     * 不可变纯核状态：物理水量、费用与命令索引；不含现金余额。
     *
     * @param config           电站绑定与冻结输入
     * @param initialEquity    期初权益（开局现金 + 既有设备原值），永不重置
     * @param initialWorldCash 开局世界现金合计
     * @param watermark        已见最大业务时刻，必须是物理时间格的整数倍
     * @param plant            电站状态
     * @param commandContents  命令内容索引，用于幂等重放
     * @param commandOutcomes  命令结果索引
     */
    public record State(Config config, BigDecimal initialEquity, BigDecimal initialWorldCash,
                        long watermark, Plant plant, Map<String, String> commandContents,
                        Map<String, String> commandOutcomes) {

        /** 深拷贝索引并核验水量、电量、交付、现金与调度的全部硬不变量。 */
        public State {
            Objects.requireNonNull(config, "配置");
            initialEquity = CashLedger.money(initialEquity);
            initialWorldCash = CashLedger.money(initialWorldCash);
            if (initialEquity.signum() < 0 || initialWorldCash.signum() < 0) {
                throw new IllegalArgumentException("期初权益与世界现金不能为负");
            }
            if (watermark < 0 || watermark % PHYSICS_TICK_MILLIS != 0) {
                throw new IllegalArgumentException("业务时间轴必须非负且落在物理时间格上：" + watermark);
            }
            Objects.requireNonNull(plant, "电站状态");
            Objects.requireNonNull(commandContents, "命令内容索引");
            Objects.requireNonNull(commandOutcomes, "命令结果索引");
            commandContents = Collections.unmodifiableMap(new TreeMap<>(commandContents));
            commandOutcomes = Collections.unmodifiableMap(new TreeMap<>(commandOutcomes));
            if (!commandContents.keySet().equals(commandOutcomes.keySet())) {
                throw new IllegalArgumentException("命令索引不一致");
            }
            for (Map.Entry<String, String> entry : commandContents.entrySet()) {
                String content = entry.getValue();
                if (content == null || content.isBlank()) {
                    throw new IllegalArgumentException("命令内容不能为空：" + entry.getKey());
                }
            }
            for (Map.Entry<String, String> entry : commandOutcomes.entrySet()) {
                String outcome = entry.getValue();
                if (outcome == null || outcome.length() < 3 || outcome.indexOf('|') != 1
                        || (outcome.charAt(0) != '0' && outcome.charAt(0) != '1')
                        || outcome.substring(2).isBlank()) {
                    throw new IllegalArgumentException("命令回执格式非法：" + entry.getKey());
                }
            }
            checkPlant(config, plant, watermark);
        }
    }

    /**
     * 一次推进的区间事实，供时间线与 Web 展示；拒绝时为 {@code null}。
     *
     * @param elapsedMillis                  本区间毫秒数
     * @param inflowCubicMetres              本区间来水 m³
     * @param turbineCubicMetres             本区间过机 m³
     * @param mandatoryReleaseCubicMetres    本区间生态/指定放流 m³
     * @param overflowSpillCubicMetres       本区间满库溢流 m³
     * @param ecologicalShortfallCubicMetres 本区间生态欠账 m³
     * @param generatedKilowattHours         本区间发电量 kWh
     * @param deliveredKilowattHours         本区间交付电量 kWh（只受本区间需求上限约束，不跨期补交）
     * @param curtailedKilowattHours         本区间弃电量 kWh
     * @param revenue                        本区间确认收入 元
     * @param operatingCost                  本区间运维成本 元
     * @param operatingCostPaid              本区间实付运维现金 元
     * @param payableAdded                   本区间应付变动 元（为负表示清偿了旧应付）
     * @param depreciation                   本区间折旧 元
     * @param receivable                     推进后应收余额 元
     */
    public record Step(long elapsedMillis, long inflowCubicMetres, long turbineCubicMetres,
                       long mandatoryReleaseCubicMetres, long overflowSpillCubicMetres,
                       long ecologicalShortfallCubicMetres, BigDecimal generatedKilowattHours,
                       BigDecimal deliveredKilowattHours, BigDecimal curtailedKilowattHours,
                       BigDecimal revenue, BigDecimal operatingCost, BigDecimal operatingCostPaid,
                       BigDecimal payableAdded, BigDecimal depreciation, BigDecimal receivable) {
    }

    /**
     * 纯候选结果；拒绝时业务状态与账户表保持原样（仅记录命令索引以便重放）。
     *
     * @param accepted 业务是否成功
     * @param reason   结果代码；见本类常量
     * @param state    候选状态
     * @param accounts 候选账户表；未发生现金动作时为原表
     * @param step     推进的区间事实；非推进命令或拒绝时为 {@code null}
     */
    public record Outcome(boolean accepted, String reason, State state,
                          Map<String, CashAccount> accounts, Step step) {

        /** 防御性复制账户表。 */
        public Outcome {
            Objects.requireNonNull(reason, "结果代码");
            Objects.requireNonNull(state, "候选状态");
            accounts = Map.copyOf(accounts);
        }
    }

    /**
     * 经营报表：物量、售电、现金流、成本、设备净额、应收应付与权益一次可复算。
     *
     * <p>现金取唯一账户表，其余为电站状态与冻结输入的派生值；权益 = 期初权益 + 损益，
     * 期初权益不因付款或未来资本流重置。</p>
     *
     * @param storageCubicMetres             蓄水 m³
     * @param inflowCubicMetres              累计来水 m³
     * @param turbineCubicMetres             累计过机 m³
     * @param mandatoryReleaseCubicMetres    累计生态/指定放流 m³
     * @param overflowSpillCubicMetres       累计满库溢流 m³
     * @param ecologicalShortfallCubicMetres 累计生态欠账 m³（不可回补）
     * @param generatedKilowattHours         累计发电量 kWh
     * @param deliveredKilowattHours         累计交付电量 kWh
     * @param curtailedKilowattHours         累计弃电量 kWh
     * @param revenue                        累计确认收入 元
     * @param collectedCash                  累计已收现金 元
     * @param receivable                     应收余额 元
     * @param operatingCost                  累计运维成本 元
     * @param operatingCostPaid              累计已付运维现金 元
     * @param payable                        应付余额 元
     * @param maintenanceCost                累计检修费用 元
     * @param depreciation                   累计折旧 元
     * @param equipmentNet                   设备净额 元
     * @param companyCash                    公司经营现金 元
     * @param worldCash                      全部账户现金合计 元
     * @param initialEquity                  期初权益 元
     * @param profit                         累计损益 元
     * @param equity                         期末权益 元
     * @param balanced                       资产 = 权益 + 应付，且世界现金守恒
     */
    public record Report(long storageCubicMetres, long inflowCubicMetres, long turbineCubicMetres,
                         long mandatoryReleaseCubicMetres, long overflowSpillCubicMetres,
                         long ecologicalShortfallCubicMetres, BigDecimal generatedKilowattHours,
                         BigDecimal deliveredKilowattHours, BigDecimal curtailedKilowattHours,
                         BigDecimal revenue, BigDecimal collectedCash, BigDecimal receivable,
                         BigDecimal operatingCost, BigDecimal operatingCostPaid, BigDecimal payable,
                         BigDecimal maintenanceCost, BigDecimal depreciation, BigDecimal equipmentNet,
                         BigDecimal companyCash, BigDecimal worldCash, BigDecimal initialEquity,
                         BigDecimal profit, BigDecimal equity, boolean balanced) {
    }

    // ------------------------------------------------------------------ 开局

    /**
     * 开局：建立期初权益与世界现金，不复制任何电站现金，也不预置来水以外的事实。
     *
     * @param config   电站绑定与冻结输入
     * @param accounts 唯一权威账户表
     * @return 初始状态
     * @throws IllegalArgumentException 账户结构或金额不合法
     */
    public static State open(Config config, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(config, "配置");
        requireAccounts(config, accounts);
        BigDecimal cash = accounts.get(config.companyAccountId()).cash();
        Plant plant = new Plant(config.physical().initialStorageCubicMetres(), 0, 0, 0, 0, 0,
                BigDecimal.ZERO.setScale(ENERGY_SCALE), BigDecimal.ZERO.setScale(ENERGY_SCALE),
                ZERO_MONEY, ZERO_MONEY, ZERO_MONEY, ZERO_MONEY, ZERO_MONEY,
                new Dispatch(0, 0, 0), null);
        return new State(config, CashLedger.money(cash.add(config.terms().equipmentCost())),
                worldCash(accounts), 0, plant, Map.of(), Map.of());
    }

    // ------------------------------------------------------------------ 调度

    /**
     * 设定调度过机流量；不移动水量与现金，只登记调度段。
     *
     * @param state           当前状态
     * @param commandId       命令标识
     * @param actorOwnerId    发起人
     * @param cubicMetresPerHour 调度流量 m³/h，非负
     * @param nowMillis       当前模拟毫秒
     * @param accounts        账户表
     * @return 候选结果
     * @throws IllegalArgumentException 流量为负
     */
    public static Outcome dispatch(State state, String commandId, String actorOwnerId,
                                   long cubicMetresPerHour, long nowMillis,
                                   Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(nowMillis, "调度时刻");
        if (cubicMetresPerHour < 0) {
            throw new IllegalArgumentException("调度流量不能为负：" + cubicMetresPerHour);
        }
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts, null);
        }
        requireAccounts(state.config(), accounts);
        String content = content("DISPATCH_FLOW", commandId, cubicMetresPerHour);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        if (nowMillis < state.watermark()) {
            return reject(state, REASON_TIME_REWIND, accounts, commandId, content);
        }
        if (nowMillis > state.watermark()) {
            return reject(state, REASON_TIME_NOT_SETTLED, accounts, commandId, content);
        }
        if (cubicMetresPerHour > state.config().physical().maxTurbineCubicMetresPerHour()) {
            return reject(state, REASON_DISPATCH_OVER_CAPACITY, accounts, commandId, content);
        }
        Plant plant = state.plant();
        Plant next = withDispatch(plant,
                new Dispatch(cubicMetresPerHour, generatingMillis(plant, nowMillis),
                        plant.turbineCubicMetres()));
        return accept(state, next, nowMillis, accounts, commandId, content, REASON_DISPATCHED, null);
    }

    /**
     * 按目标出力设定调度：出力 kW 折算为整 m³/h（向下取整）。
     *
     * @param state        当前状态
     * @param commandId    命令标识
     * @param actorOwnerId 发起人
     * @param outputKw     目标出力 kW，非负
     * @param nowMillis    当前模拟毫秒
     * @param accounts     账户表
     * @return 候选结果
     * @throws IllegalArgumentException 出力为负
     */
    public static Outcome dispatchOutput(State state, String commandId, String actorOwnerId,
                                         long outputKw, long nowMillis,
                                         Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(nowMillis, "调度时刻");
        if (outputKw < 0) {
            throw new IllegalArgumentException("目标出力不能为负：" + outputKw);
        }
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts, null);
        }
        requireAccounts(state.config(), accounts);
        String content = content("DISPATCH_OUTPUT", commandId, outputKw);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        if (nowMillis < state.watermark()) {
            return reject(state, REASON_TIME_REWIND, accounts, commandId, content);
        }
        if (nowMillis > state.watermark()) {
            return reject(state, REASON_TIME_NOT_SETTLED, accounts, commandId, content);
        }
        PhysicalSpec physical = state.config().physical();
        if (outputKw > physical.capacityKw()) {
            return reject(state, REASON_DISPATCH_OVER_CAPACITY, accounts, commandId, content);
        }
        long flow = BigDecimal.valueOf(outputKw)
                .divide(physical.kilowattHoursPerCubicMetre(), 0, RoundingMode.FLOOR)
                .longValueExact();
        if (outputKw > 0 && flow == 0) {
            return reject(state, REASON_DISPATCH_TOO_SMALL, accounts, commandId, content);
        }
        if (flow > physical.maxTurbineCubicMetresPerHour()) {
            return reject(state, REASON_DISPATCH_OVER_CAPACITY, accounts, commandId, content);
        }
        Plant plant = state.plant();
        Plant next = withDispatch(plant,
                new Dispatch(flow, generatingMillis(plant, nowMillis),
                        plant.turbineCubicMetres()));
        return accept(state, next, nowMillis, accounts, commandId, content, REASON_DISPATCHED, null);
    }

    // ------------------------------------------------------------------ 推进

    /**
     * 推进时间：在冻结物理时间格上逐格结算来水、生态/指定放流、过机、满库溢流、电量、交付、
     * 收入、运维与折旧；一次推进与分成多次推进得到同一结果。
     *
     * <p>每格独立：本格未发出的电与未满足的需求都不结转；本格过机同时受本格调度流量与本格装机
     * 可用水量约束，不会把停机或枯水期未用的容量攒到后面超流发电。</p>
     *
     * @param state        当前状态
     * @param commandId    命令标识
     * @param actorOwnerId 发起人
     * @param toMillis     目标时刻，必须大于已见时间、落在物理时间格上且不超过来水序列终点
     * @param accounts     账户表
     * @return 候选结果
     * @throws IllegalArgumentException 时刻为负或未落在物理时间格上
     */
    public static Outcome advance(State state, String commandId, String actorOwnerId,
                                  long toMillis, Map<String, CashAccount> accounts) {
        return advance(state, commandId, actorOwnerId, toMillis, accounts, accounts.get(state.config().companyAccountId()).cash());
    }

    /** 世界资金桥：可付款额度来自同帧资金冻结，未付款的既有运维义务留应付。 */
    public static Outcome advance(State state, String commandId, String actorOwnerId,
                                  long toMillis, Map<String, CashAccount> accounts, BigDecimal availableCash) {
        availableCash = CashLedger.money(availableCash);
        if (availableCash.signum() < 0 || availableCash.compareTo(accounts.get(state.config().companyAccountId()).cash()) > 0)
            throw new IllegalArgumentException("水电可用现金非法");
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(toMillis, "推进时刻");
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts, null);
        }
        Config config = state.config();
        requireAccounts(config, accounts);
        String content = content("ADVANCE", commandId, toMillis);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        long from = state.watermark();
        if (toMillis < from) {
            return reject(state, REASON_TIME_REWIND, accounts, commandId, content);
        }
        if (toMillis == from) {
            return reject(state, REASON_NO_ELAPSED_TIME, accounts, commandId, content);
        }
        if (toMillis > config.inflow().endsAtMillis()) {
            return reject(state, REASON_INFLOW_SERIES_ENDED, accounts, commandId, content);
        }
        try {
            return settle(state, commandId, content, from, toMillis, accounts, availableCash);
        } catch (ArithmeticException overflow) {
            return reject(state, REASON_OVERFLOW, accounts, commandId, content);
        }
    }

    private static Outcome settle(State state, String commandId, String content, long from,
                                  long toMillis, Map<String, CashAccount> accounts, BigDecimal availableCash) {
        Config config = state.config();
        PhysicalSpec physical = config.physical();
        Plant plant = state.plant();
        InflowSeries series = config.inflow();

        long storage = plant.storageCubicMetres();
        long inflowCumulative = plant.inflowCubicMetres();
        long turbineCumulative = plant.turbineCubicMetres();
        long releaseCumulative = plant.mandatoryReleaseCubicMetres();
        long overflowCumulative = plant.overflowSpillCubicMetres();
        BigDecimal generated = plant.generatedKilowattHours();
        BigDecimal delivered = plant.deliveredKilowattHours();

        long tickInflow = 0;
        long tickTurbine = 0;
        long tickRelease = 0;
        long tickOverflow = 0;
        long tickShortfall = 0;
        BigDecimal tickEnergy = BigDecimal.ZERO.setScale(ENERGY_SCALE);
        BigDecimal tickDelivered = BigDecimal.ZERO.setScale(ENERGY_SCALE);

        long generating = generatingMillis(plant, from);
        long generatingPrevious = generating;
        long turbineTargetPrevious = dispatchWaterCubicMetres(
                plant.dispatch().cubicMetresPerHour(), generatingPrevious);
        long capacityPrevious = capacityWaterCubicMetres(config, generatingPrevious);
        long inflowPrevious = series.cumulativeCubicMetres(from);
        long obligationPrevious = obligationCubicMetres(physical, from);
        long demandPrevious = demandCubicMetres(config, from);
        Outage outage = plant.outage();

        for (long tick = from + PHYSICS_TICK_MILLIS; tick <= toMillis;
                tick += PHYSICS_TICK_MILLIS) {
            long previous = tick - PHYSICS_TICK_MILLIS;
            long stopped = 0;
            if (outage != null) {
                long start = Math.max(previous, outage.startedAtMillis());
                long end = Math.min(tick, outage.untilMillis());
                if (end > start) {
                    stopped = end - start;
                }
            }
            generating = Math.addExact(generating, PHYSICS_TICK_MILLIS - stopped);
            long turbineTarget = dispatchWaterCubicMetres(
                    plant.dispatch().cubicMetresPerHour(), generating);
            long capacity = capacityWaterCubicMetres(config, generating);
            long turbineWanted = Math.subtractExact(turbineTarget, turbineTargetPrevious);
            long turbineAllowed = Math.subtractExact(capacity, capacityPrevious);

            long inflow = Math.subtractExact(series.cumulativeCubicMetres(tick), inflowPrevious);
            long obligation = obligationCubicMetres(physical, tick);
            long releaseWanted = Math.subtractExact(obligation, obligationPrevious);
            long demand = demandCubicMetres(config, tick);
            long demandAllowed = Math.subtractExact(demand, demandPrevious);

            long storageAfterInflow = Math.addExact(storage, inflow);
            long aboveDead = Math.max(0,
                    Math.subtractExact(storageAfterInflow, physical.deadStorageCubicMetres()));
            long release = Math.min(releaseWanted, aboveDead);
            long afterRelease = Math.subtractExact(storageAfterInflow, release);
            long remaining = Math.max(0,
                    Math.subtractExact(afterRelease, physical.deadStorageCubicMetres()));
            long turbine = Math.min(Math.min(turbineWanted, turbineAllowed), remaining);
            long afterTurbine = Math.subtractExact(afterRelease, turbine);
            long overflow = Math.max(0,
                    Math.subtractExact(afterTurbine, physical.maximumStorageCubicMetres()));

            storage = Math.subtractExact(afterTurbine, overflow);
            long shortfall = Math.subtractExact(releaseWanted, release);
            BigDecimal energyThisTick = energyOf(turbine, physical);
            BigDecimal demandThisTick = BigDecimal.valueOf(demandAllowed)
                    .setScale(ENERGY_SCALE, RoundingMode.UNNECESSARY);
            BigDecimal deliveredThisTick = energyThisTick.min(demandThisTick);

            inflowCumulative = Math.addExact(inflowCumulative, inflow);
            turbineCumulative = Math.addExact(turbineCumulative, turbine);
            releaseCumulative = Math.addExact(releaseCumulative, release);
            overflowCumulative = Math.addExact(overflowCumulative, overflow);
            generated = generated.add(energyThisTick);
            delivered = delivered.add(deliveredThisTick);

            tickInflow = Math.addExact(tickInflow, inflow);
            tickTurbine = Math.addExact(tickTurbine, turbine);
            tickRelease = Math.addExact(tickRelease, release);
            tickOverflow = Math.addExact(tickOverflow, overflow);
            tickShortfall = Math.addExact(tickShortfall, shortfall);
            tickEnergy = tickEnergy.add(energyThisTick);
            tickDelivered = tickDelivered.add(deliveredThisTick);

            generatingPrevious = generating;
            turbineTargetPrevious = turbineTarget;
            capacityPrevious = capacity;
            inflowPrevious = Math.addExact(inflowPrevious, inflow);
            obligationPrevious = obligation;
            demandPrevious = demand;
        }
        boolean outageActive = outage != null && toMillis < outage.untilMillis();
        Outage nextOutage = outageActive ? outage : null;
        long outageCumulative = plant.outageMillisCumulative();
        if (outage != null && !outageActive) {
            outageCumulative = Math.addExact(outageCumulative,
                    outage.untilMillis() - outage.startedAtMillis());
        }

        long accruedCents = Math.subtractExact(opexCents(config.terms(), toMillis),
                opexCents(config.terms(), from));
        BigDecimal opexDelta = cents(accruedCents);
        BigDecimal need = plant.opexAccrued().subtract(plant.opexPaid()).add(opexDelta);
        BigDecimal companyCash = accounts.get(config.companyAccountId()).cash();
        BigDecimal cashPaid = need.min(companyCash).min(availableCash);
        BigDecimal payableAdded = opexDelta.subtract(cashPaid);
        BigDecimal depreciationDelta = depreciationCumulative(config.terms(), toMillis)
                .subtract(depreciationCumulative(config.terms(), from));
        BigDecimal revenueNew = revenue(config, delivered);
        BigDecimal revenueDelta = revenueNew.subtract(
                revenue(config, plant.deliveredKilowattHours()));

        Plant next = new Plant(storage, inflowCumulative, turbineCumulative, releaseCumulative,
                overflowCumulative, outageCumulative, generated, delivered,
                plant.collectedCash(), plant.opexAccrued().add(opexDelta),
                plant.opexPaid().add(cashPaid), plant.maintenanceCost(),
                plant.depreciation().add(depreciationDelta), plant.dispatch(), nextOutage);
        Map<String, CashAccount> nextAccounts = accounts;
        if (cashPaid.signum() > 0) {
            nextAccounts = moveCash(accounts, config.companyAccountId(),
                    config.contractorAccountId(), cashPaid);
        }
        BigDecimal receivable = CashLedger.money(revenueNew.subtract(next.collectedCash()));
        Step step = new Step(toMillis - from, tickInflow, tickTurbine, tickRelease, tickOverflow,
                tickShortfall, tickEnergy, tickDelivered,
                energyAmount(tickEnergy.subtract(tickDelivered)), revenueDelta,
                CashLedger.money(opexDelta), CashLedger.money(cashPaid),
                CashLedger.money(payableAdded), CashLedger.money(depreciationDelta), receivable);
        return accept(state, next, toMillis, nextAccounts, commandId, content, REASON_ADVANCED,
                step);
    }

    // ------------------------------------------------------------------ 检修与结算

    /**
     * 开工一次检修：向承包方真实付款并当期费用化，区间 [now, now+duration) 内可用容量为 0。
     *
     * @param state          当前状态
     * @param commandId      命令标识
     * @param actorOwnerId   发起人
     * @param durationMillis 检修耗时，必须为正
     * @param cost           检修成本 元，必须为正且精确到分
     * @param nowMillis      当前模拟毫秒
     * @param accounts       账户表
     * @return 候选结果
     * @throws IllegalArgumentException 耗时或金额不合法
     */
    public static Outcome beginMaintenance(State state, String commandId, String actorOwnerId,
                                           long durationMillis, BigDecimal cost, long nowMillis,
                                           Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(nowMillis, "检修时刻");
        if (durationMillis <= 0) {
            throw new IllegalArgumentException("检修耗时必须为正");
        }
        BigDecimal amount = CashLedger.money(cost);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("检修成本必须为正");
        }
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts, null);
        }
        Config config = state.config();
        requireAccounts(config, accounts);
        String content = content("MAINTENANCE", commandId, durationMillis, amount);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        if (nowMillis < state.watermark()) {
            return reject(state, REASON_TIME_REWIND, accounts, commandId, content);
        }
        if (nowMillis > state.watermark()) {
            return reject(state, REASON_TIME_NOT_SETTLED, accounts, commandId, content);
        }
        Plant plant = state.plant();
        Outage outage = plant.outage();
        boolean active = outage != null && nowMillis < outage.untilMillis();
        if (active) {
            return reject(state, REASON_MAINTENANCE_IN_PROGRESS, accounts, commandId, content);
        }
        CashAccount company = accounts.get(config.companyAccountId());
        if (company.cash().compareTo(amount) < 0) {
            return reject(state, REASON_INSUFFICIENT_CASH, accounts, commandId, content);
        }
        long untilMillis;
        try {
            untilMillis = Math.addExact(nowMillis, durationMillis);
        } catch (ArithmeticException overflow) {
            return reject(state, REASON_OVERFLOW, accounts, commandId, content);
        }
        long outageCumulative = outage == null ? plant.outageMillisCumulative()
                : Math.addExact(plant.outageMillisCumulative(),
                        outage.untilMillis() - outage.startedAtMillis());
        Plant next = new Plant(plant.storageCubicMetres(), plant.inflowCubicMetres(),
                plant.turbineCubicMetres(), plant.mandatoryReleaseCubicMetres(),
                plant.overflowSpillCubicMetres(), outageCumulative,
                plant.generatedKilowattHours(), plant.deliveredKilowattHours(),
                plant.collectedCash(), plant.opexAccrued(), plant.opexPaid(),
                plant.maintenanceCost().add(amount), plant.depreciation(), plant.dispatch(),
                new Outage(nowMillis, untilMillis, amount));
        Map<String, CashAccount> nextAccounts = moveCash(accounts, config.companyAccountId(),
                config.contractorAccountId(), amount);
        return accept(state, next, nowMillis, nextAccounts, commandId, content,
                REASON_MAINTENANCE_STARTED, null);
    }

    /**
     * 结算应收：金额取 min(请求额, 应收余额, 购电方现金)，不足即部分结算，不强行足额收现。
     *
     * @param state        当前状态
     * @param commandId    命令标识
     * @param actorOwnerId 发起人
     * @param request      请求结算金额 元，必须为正
     * @param nowMillis    当前模拟毫秒
     * @param accounts     账户表
     * @return 候选结果
     * @throws IllegalArgumentException 请求金额不合法
     */
    public static Outcome collectReceivable(State state, String commandId, String actorOwnerId,
                                            BigDecimal request, long nowMillis,
                                            Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        requireText(commandId, "命令标识");
        requireTime(nowMillis, "结算时刻");
        BigDecimal wanted = CashLedger.money(request);
        if (wanted.signum() <= 0) {
            throw new IllegalArgumentException("结算请求金额必须为正");
        }
        if (!authorized(state, actorOwnerId)) {
            return new Outcome(false, REASON_NOT_AUTHORIZED, state, accounts, null);
        }
        Config config = state.config();
        requireAccounts(config, accounts);
        String content = content("COLLECT", commandId, wanted);
        Outcome replay = replay(state, commandId, content, accounts);
        if (replay != null) {
            return replay;
        }
        if (nowMillis < state.watermark()) {
            return reject(state, REASON_TIME_REWIND, accounts, commandId, content);
        }
        if (nowMillis > state.watermark()) {
            return reject(state, REASON_TIME_NOT_SETTLED, accounts, commandId, content);
        }
        Plant plant = state.plant();
        BigDecimal outstanding = receivable(config, plant);
        BigDecimal buyerCash = accounts.get(config.buyerAccountId()).cash();
        BigDecimal amount = wanted.min(outstanding).min(buyerCash);
        if (amount.signum() <= 0) {
            return reject(state, REASON_NOTHING_TO_COLLECT, accounts, commandId, content);
        }
        Plant next = new Plant(plant.storageCubicMetres(), plant.inflowCubicMetres(),
                plant.turbineCubicMetres(), plant.mandatoryReleaseCubicMetres(),
                plant.overflowSpillCubicMetres(), plant.outageMillisCumulative(),
                plant.generatedKilowattHours(), plant.deliveredKilowattHours(),
                plant.collectedCash().add(amount), plant.opexAccrued(), plant.opexPaid(),
                plant.maintenanceCost(), plant.depreciation(), plant.dispatch(),
                plant.outage());
        Map<String, CashAccount> nextAccounts = moveCash(accounts, config.buyerAccountId(),
                config.companyAccountId(), amount);
        return accept(state, next, nowMillis, nextAccounts, commandId, content,
                REASON_COLLECTED, null);
    }

    // ------------------------------------------------------------------ 报表

    /**
     * 派生报表：物量、售电、现金流、成本、设备净额、应收应付与权益一次列出。
     *
     * <p>权益 = 期初权益 + 收入 − 运维 − 检修 − 折旧；期初权益永不重置。传入了外部资本流或
     * 债务入账的账户表会如实表现为不平衡，属接缝未接的证据，不是重算期初权益的理由。</p>
     *
     * @param state    状态
     * @param accounts 账户表
     * @return 报表
     * @throws IllegalArgumentException 账户结构不合法
     */
    public static Report report(State state, Map<String, CashAccount> accounts) {
        Objects.requireNonNull(state, "状态");
        Config config = state.config();
        requireAccounts(config, accounts);
        Plant plant = state.plant();
        BigDecimal revenue = revenue(config, plant.deliveredKilowattHours());
        BigDecimal receivable = CashLedger.money(revenue.subtract(plant.collectedCash()));
        BigDecimal payable = CashLedger.money(plant.opexAccrued().subtract(plant.opexPaid()));
        BigDecimal equipmentNet = CashLedger.money(
                config.terms().equipmentCost().subtract(plant.depreciation()));
        BigDecimal profit = CashLedger.money(revenue.subtract(plant.opexAccrued())
                .subtract(plant.maintenanceCost()).subtract(plant.depreciation()));
        BigDecimal equity = CashLedger.money(state.initialEquity().add(profit));
        BigDecimal cash = accounts.get(config.companyAccountId()).cash();
        BigDecimal world = worldCash(accounts);
        BigDecimal assets = CashLedger.money(cash.add(equipmentNet).add(receivable));
        boolean balanced = assets.compareTo(CashLedger.money(equity.add(payable))) == 0
                && world.compareTo(state.initialWorldCash()) == 0;
        long obligation = obligationCubicMetres(config.physical(), state.watermark());
        return new Report(plant.storageCubicMetres(), plant.inflowCubicMetres(),
                plant.turbineCubicMetres(), plant.mandatoryReleaseCubicMetres(),
                plant.overflowSpillCubicMetres(),
                Math.max(0, obligation - plant.mandatoryReleaseCubicMetres()),
                plant.generatedKilowattHours(), plant.deliveredKilowattHours(),
                energyAmount(plant.generatedKilowattHours()
                        .subtract(plant.deliveredKilowattHours())),
                revenue, plant.collectedCash(), receivable, plant.opexAccrued(),
                plant.opexPaid(), payable, plant.maintenanceCost(), plant.depreciation(),
                equipmentNet, CashLedger.money(cash), world, state.initialEquity(), profit,
                equity, balanced);
    }

    /**
     * 校验并返回报表；资产、权益、应付款或世界现金不守恒时抛异常。
     *
     * @param state    状态
     * @param accounts 账户表
     * @return 已通过校验的报表
     * @throws IllegalStateException    账面不守恒
     * @throws IllegalArgumentException 账户结构不合法
     */
    public static Report validate(State state, Map<String, CashAccount> accounts) {
        Report report = report(state, accounts);
        if (!report.balanced()) {
            throw new IllegalStateException("水电账不平衡：资产=" + report.companyCash()
                    + "+" + report.equipmentNet() + "+" + report.receivable()
                    + "，权益=" + report.equity() + "，应付=" + report.payable()
                    + "，世界现金=" + report.worldCash()
                    + "，开局世界现金=" + state.initialWorldCash());
        }
        return report;
    }

    // ------------------------------------------------------------------ 持久接口

    /**
     * 把内存状态序列化为 JSON，供合法中间阶段恢复；不含现金账户（现金由 {@link CashLedger} 权威保存）。
     *
     * @param state 状态
     * @return JSON 文本
     * @throws IllegalStateException 序列化失败
     */
    public static String toJson(State state) {
        Objects.requireNonNull(state, "状态");
        try {
            return JSON.writeValueAsString(state);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("水电状态无法序列化", failure);
        }
    }

    /**
     * 从 JSON 还原因果状态；还原后重新执行状态构造期不变量校验。
     *
     * <p>区分两类拒绝：结构无法解析（"状态 JSON 无法解析"）与结构可解析但经济/时间不变量不自洽
     * （"状态 JSON 不自洽"，保留具体不变量原因，便于审计伪造态）。</p>
     *
     * @param json 由 {@link #toJson} 生成的文本
     * @return 状态
     * @throws IllegalArgumentException 文本为空、无法解析或不自洽
     */
    public static State fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("状态 JSON 不能为空");
        }
        try {
            return JSON.readValue(json, State.class);
        } catch (JsonProcessingException failure) {
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("状态 JSON 不自洽：" + invalid.getMessage(),
                            invalid);
                }
            }
            throw new IllegalArgumentException("状态 JSON 无法解析", failure);
        }
    }

    // ------------------------------------------------------------------ 内部计算

    private static Outcome accept(State previous, Plant plant, long watermark,
                                  Map<String, CashAccount> accounts, String commandId,
                                  String content, String reason, Step step) {
        Map<String, String> contents = new TreeMap<>(previous.commandContents());
        Map<String, String> outcomes = new TreeMap<>(previous.commandOutcomes());
        contents.put(commandId, content);
        outcomes.put(commandId, "1|" + reason);
        State next = new State(previous.config(), previous.initialEquity(),
                previous.initialWorldCash(), watermark, plant, contents, outcomes);
        return new Outcome(true, reason, next, accounts, step);
    }

    private static Outcome reject(State previous, String reason,
                                 Map<String, CashAccount> accounts, String commandId,
                                 String content) {
        Map<String, String> contents = new TreeMap<>(previous.commandContents());
        Map<String, String> outcomes = new TreeMap<>(previous.commandOutcomes());
        contents.put(commandId, content);
        outcomes.put(commandId, "0|" + reason);
        State next = new State(previous.config(), previous.initialEquity(),
                previous.initialWorldCash(), previous.watermark(), previous.plant(),
                contents, outcomes);
        return new Outcome(false, reason, next, accounts, null);
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
        String stored = state.commandOutcomes().get(commandId);
        boolean accepted = stored != null && stored.startsWith("1|");
        String reason = stored == null ? "REPLAYED" : stored.substring(2);
        return new Outcome(accepted, reason, state, accounts, null);
    }

    private static Plant withDispatch(Plant plant, Dispatch dispatch) {
        return new Plant(plant.storageCubicMetres(), plant.inflowCubicMetres(),
                plant.turbineCubicMetres(), plant.mandatoryReleaseCubicMetres(),
                plant.overflowSpillCubicMetres(), plant.outageMillisCumulative(),
                plant.generatedKilowattHours(), plant.deliveredKilowattHours(),
                plant.collectedCash(), plant.opexAccrued(), plant.opexPaid(),
                plant.maintenanceCost(), plant.depreciation(), dispatch, plant.outage());
    }

    private static long generatingMillis(Plant plant, long atMillis) {
        long committed = plant.outageMillisCumulative();
        Outage outage = plant.outage();
        if (outage != null) {
            long active = Math.min(Math.max(atMillis - outage.startedAtMillis(), 0),
                    outage.untilMillis() - outage.startedAtMillis());
            committed = Math.addExact(committed, active);
        }
        return Math.subtractExact(atMillis, committed);
    }

    private static long dispatchWaterCubicMetres(long cubicMetresPerHour, long generatingMillis) {
        if (generatingMillis <= 0 || cubicMetresPerHour == 0) {
            return 0;
        }
        return Math.multiplyExact(cubicMetresPerHour, generatingMillis) / HOUR_MILLIS;
    }

    private static long capacityWaterCubicMetres(Config config, long generatingMillis) {
        if (generatingMillis <= 0) {
            return 0;
        }
        BigDecimal divisor = BigDecimal.valueOf(HOUR_MILLIS)
                .multiply(config.physical().kilowattHoursPerCubicMetre());
        return BigDecimal.valueOf(config.physical().capacityKw())
                .multiply(BigDecimal.valueOf(generatingMillis))
                .divide(divisor, 0, RoundingMode.FLOOR)
                .longValueExact();
    }

    private static long obligationCubicMetres(PhysicalSpec physical, long atMillis) {
        return Math.multiplyExact(physical.mandatoryCubicMetresPerHour(), atMillis)
                / HOUR_MILLIS;
    }

    private static long demandCubicMetres(Config config, long atMillis) {
        long until = Math.min(atMillis, config.contract().untilMillis());
        return Math.multiplyExact(config.contract().demandKilowattHoursPerHour(), until)
                / HOUR_MILLIS;
    }

    private static long opexCents(OperatingTerms terms, long atMillis) {
        return Math.multiplyExact(terms.opexCentsPerHour(), atMillis) / HOUR_MILLIS;
    }

    private static BigDecimal depreciationCumulative(OperatingTerms terms, long atMillis) {
        BigDecimal cost = terms.equipmentCost();
        if (cost.signum() == 0) {
            return ZERO_MONEY;
        }
        BigDecimal divisor = BigDecimal.valueOf(terms.equipmentLifeDays())
                .multiply(BigDecimal.valueOf(DAY_MILLIS));
        BigDecimal value = cost.multiply(BigDecimal.valueOf(atMillis))
                .divide(divisor, 2, RoundingMode.HALF_UP);
        return value.min(cost);
    }

    private static BigDecimal revenue(Config config, BigDecimal deliveredKilowattHours) {
        return deliveredKilowattHours.multiply(config.contract().pricePerKilowattHour())
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal receivable(Config config, Plant plant) {
        return CashLedger.money(revenue(config, plant.deliveredKilowattHours())
                .subtract(plant.collectedCash()));
    }

    private static BigDecimal energyOf(long cubicMetres, PhysicalSpec physical) {
        return physical.kilowattHoursPerCubicMetre().multiply(BigDecimal.valueOf(cubicMetres))
                .setScale(ENERGY_SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal cents(long value) {
        return BigDecimal.valueOf(value).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal energyRate(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("转换率不能为空");
        }
        BigDecimal rate;
        try {
            rate = value.setScale(ENERGY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("转换率必须精确到 6 位小数", failure);
        }
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("转换率必须为正");
        }
        return rate;
    }

    private static BigDecimal energyAmount(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("电量不能为空");
        }
        try {
            return value.setScale(ENERGY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("电量必须精确到 6 位小数", failure);
        }
    }

    private static void checkPlant(Config config, Plant plant, long watermark) {
        PhysicalSpec physical = config.physical();
        if (watermark > config.inflow().endsAtMillis()) {
            throw new IllegalArgumentException("时间轴超出冻结来水序列终点：" + watermark
                    + " > " + config.inflow().endsAtMillis());
        }
        long seriesInflow;
        try {
            seriesInflow = config.inflow().cumulativeCubicMetres(watermark);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("冻结来水累计溢出", overflow);
        }
        if (plant.inflowCubicMetres() != seriesInflow) {
            throw new IllegalArgumentException("累计来水与冻结序列不一致：状态="
                    + plant.inflowCubicMetres() + "，序列累计=" + seriesInflow);
        }
        if (plant.storageCubicMetres() < physical.deadStorageCubicMetres()
                || plant.storageCubicMetres() > physical.maximumStorageCubicMetres()) {
            throw new IllegalArgumentException("蓄水必须落在死库容与库容之间："
                    + plant.storageCubicMetres());
        }
        long delta;
        long outflow;
        long expected;
        try {
            delta = Math.subtractExact(plant.storageCubicMetres(),
                    physical.initialStorageCubicMetres());
            outflow = Math.addExact(plant.turbineCubicMetres(),
                    Math.addExact(plant.mandatoryReleaseCubicMetres(),
                            plant.overflowSpillCubicMetres()));
            expected = Math.addExact(delta, outflow);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("水量累计溢出", overflow);
        }
        if (expected != plant.inflowCubicMetres()) {
            throw new IllegalArgumentException("水量五项不守恒：来水=" + plant.inflowCubicMetres()
                    + "，蓄水增量=" + delta + "，过机=" + plant.turbineCubicMetres()
                    + "，生态/指定放流=" + plant.mandatoryReleaseCubicMetres()
                    + "，满库溢流=" + plant.overflowSpillCubicMetres());
        }
        if (plant.generatedKilowattHours()
                .compareTo(energyOf(plant.turbineCubicMetres(), physical)) != 0) {
            throw new IllegalArgumentException("累计电量必须等于累计过机乘转换率");
        }
        long capacity;
        long obligation;
        try {
            capacity = capacityWaterCubicMetres(config, generatingMillis(plant, watermark));
            obligation = obligationCubicMetres(physical, watermark);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("装机可用水量或放流义务折算溢出", overflow);
        }
        if (plant.turbineCubicMetres() > capacity) {
            throw new IllegalArgumentException("累计过机超出装机可用水量：过机="
                    + plant.turbineCubicMetres() + "，装机上限=" + capacity);
        }
        if (plant.mandatoryReleaseCubicMetres() > obligation) {
            throw new IllegalArgumentException("累计生态/指定放流超出强制下泄义务");
        }
        if (plant.deliveredKilowattHours()
                .compareTo(plant.generatedKilowattHours()) > 0) {
            throw new IllegalArgumentException("累计交付电量不得超过累计发电量");
        }
        BigDecimal demand = BigDecimal.valueOf(demandCubicMetres(config, watermark))
                .setScale(ENERGY_SCALE, RoundingMode.UNNECESSARY);
        if (plant.deliveredKilowattHours().compareTo(demand) > 0) {
            throw new IllegalArgumentException("累计交付电量不得超过需求上限累计");
        }
        if (plant.collectedCash().compareTo(
                revenue(config, plant.deliveredKilowattHours())) > 0) {
            throw new IllegalArgumentException("已收现金不得超过累计应收");
        }
        if (plant.depreciation().compareTo(config.terms().equipmentCost()) > 0) {
            throw new IllegalArgumentException("累计折旧不得超过设备原值");
        }
        Dispatch dispatch = plant.dispatch();
        if (dispatch.cubicMetresPerHour() > physical.maxTurbineCubicMetresPerHour()) {
            throw new IllegalArgumentException("调度流量超出最大过机");
        }
        if (dispatch.baseGeneratingMillis() > generatingMillis(plant, watermark)
                || dispatch.baseCubicMetres() > plant.turbineCubicMetres()) {
            throw new IllegalArgumentException("调度段基点晚于当前状态");
        }
        Outage outage = plant.outage();
        if (outage != null && outage.untilMillis() <= outage.startedAtMillis()) {
            throw new IllegalArgumentException("在途检修区间不合法");
        }
    }

    private static Map<String, CashAccount> moveCash(Map<String, CashAccount> accounts,
                                                     String fromAccountId, String toAccountId,
                                                     BigDecimal amount) {
        CashAccount from = accounts.get(fromAccountId);
        CashAccount to = accounts.get(toAccountId);
        if (from == null || to == null) {
            throw new IllegalArgumentException("现金账户缺失");
        }
        Map<String, CashAccount> next = new LinkedHashMap<>(accounts);
        next.put(fromAccountId, new CashAccount(from.ownerId(),
                CashLedger.money(from.cash().subtract(amount)), from.source()));
        next.put(toAccountId, new CashAccount(to.ownerId(),
                CashLedger.money(to.cash().add(amount)), to.source()));
        return Collections.unmodifiableMap(next);
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
        CashAccount buyer = accounts.get(config.buyerAccountId());
        if (buyer == null || buyer.ownerId().equals(config.companyOwnerId())) {
            throw new IllegalArgumentException("购电方账户缺失或与公司同一主体");
        }
        CashAccount contractor = accounts.get(config.contractorAccountId());
        if (contractor == null || contractor.ownerId().equals(config.companyOwnerId())) {
            throw new IllegalArgumentException("承包方账户缺失或与公司同一主体");
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
        BigDecimal total = ZERO_MONEY;
        for (CashAccount account : accounts.values()) {
            total = total.add(account.cash());
        }
        return CashLedger.money(total);
    }

    private static boolean authorized(State state, String actorOwnerId) {
        return state.config().companyOwnerId().equals(actorOwnerId);
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

    private static void requireTime(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + "不能为负：" + value);
        }
        if (value % PHYSICS_TICK_MILLIS != 0) {
            throw new IllegalArgumentException(name + "必须落在冻结物理时间格（"
                    + PHYSICS_TICK_MILLIS + " 毫秒）上：" + value);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
