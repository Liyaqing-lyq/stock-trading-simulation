package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 陈酿库存与有限渠道需求：纯函数子状态（EXP-AGED-2）。
 *
 * <p><b>边界</b>：本类不持日志、不调度、不提交、不做权限投影；所有动作都是
 * {@code (State, accounts) -> Outcome} 的纯计算，通过后才返回候选状态与候选账户表。
 * 现金只走唯一 {@link CashLedger} 账户表与其双腿语义 {@link CashLedger#computeTransfer}，
 * 不另建账户体系，也不凭数字加减现金。</p>
 *
 * <p><b>口径</b>（见 {@code aged-inventory-pure-author/contract.md}）：</p>
 * <ul>
 *   <li>寄售：发出后所有权仍归公司，渠道只是代理；发货不确认收入，终端售出才确认。</li>
 *   <li>预收按发货 lot 总额分摊到单位，售出按累计比例分摊、末次取精确余额。</li>
 *   <li>数量 {@code long}；金额 scale2；单价 scale4 只用于显示。</li>
 *   <li>到期/报损后未抵扣预收转退款应付；已确认应收不因剩余货物坏掉而消失。</li>
 *   <li>所有不变量在返回候选前检查；失败返回原状态，不回滚已完成动作。</li>
 * </ul>
 */
public final class AgedInventoryModel {

    /** 本实现使用的实验参数标识；换参数即换实验，不能与旧结果合并。 */
    public static final String EXP_LABEL = "EXP-AGED-2";

    // ---------------------------------------------------------------- 结果码
    /** 动作被接受且候选通过全部不变量。 */
    public static final String ACCEPTED = "ACCEPTED";
    /** 调用方不是本公司的（EXP 替身）经理。 */
    public static final String NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 配置引用的账户在真实账户表中不存在。 */
    public static final String COUNTERPARTY_INVALID = "COUNTERPARTY_INVALID";
    /** 未知渠道。 */
    public static final String UNKNOWN_CHANNEL = "UNKNOWN_CHANNEL";
    /** 未知采购单。 */
    public static final String UNKNOWN_ORDER = "UNKNOWN_ORDER";
    /** 数量必须为正。 */
    public static final String INVALID_QUANTITY = "INVALID_QUANTITY";
    /** 原料不足，整笔拒绝且不消耗原料。 */
    public static final String MATERIAL_SHORT = "MATERIAL_SHORT";
    /** 超过每期产能，整笔拒绝且不占产能。 */
    public static final String CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED";
    /** 公司现金不足。 */
    public static final String CASH_SHORT = "CASH_SHORT";
    /** 渠道现金不足以支付本次预收。 */
    public static final String CHANNEL_CASH_SHORT = "CHANNEL_CASH_SHORT";
    /** 终端消费者聚合账户现金不足以支付本次成交。 */
    public static final String TERMINAL_CASH_SHORT = "TERMINAL_CASH_SHORT";
    /** 批次未成熟。 */
    public static final String NOT_MATURE = "NOT_MATURE";
    /** 批次已过货架期。 */
    public static final String EXPIRED_LOT = "EXPIRED_LOT";
    /** 渠道没有可售寄售库存。 */
    public static final String NO_CHANNEL_STOCK = "NO_CHANNEL_STOCK";
    /** 本期冻结需求已用尽。 */
    public static final String DEMAND_EXHAUSTED = "DEMAND_EXHAUSTED";
    /** 超过渠道每期需求上限。 */
    public static final String CHANNEL_QUOTA_EXCEEDED = "CHANNEL_QUOTA_EXCEEDED";
    /** 价格低于已冻结的 EXP 底价。 */
    public static final String PRICE_BELOW_FLOOR = "PRICE_BELOW_FLOOR";
    /** 没有可收回应收。 */
    public static final String NOTHING_TO_COLLECT = "NOTHING_TO_COLLECT";
    /** 没有可退预收。 */
    public static final String NOTHING_TO_REFUND = "NOTHING_TO_REFUND";
    /** 没有可付固定费。 */
    public static final String NOTHING_TO_PAY = "NOTHING_TO_PAY";
    /** 期间尚未结束，不能关期。 */
    public static final String PERIOD_NOT_ENDED = "PERIOD_NOT_ENDED";
    /** 该期间已关闭，不能再发生交易。 */
    public static final String PERIOD_CLOSED = "PERIOD_CLOSED";
    /** 本期末应完成的动作（到期采购）尚未结算。 */
    public static final String UNSETTLED_ACTIONS = "UNSETTLED_ACTIONS";
    /** 标识已被使用。 */
    public static final String ID_REUSED = "ID_REUSED";
    /** 候选状态违反不变量；前缀后接具体编号。 */
    public static final String IDENTITY_BROKEN = "IDENTITY_BROKEN:";

    // ---------------------------------------------------------------- 状态

    /**
     * 渠道规格。
     *
     * @param channelId       渠道标识
     * @param accountId       渠道在 {@link CashLedger} 中的真实账户
     * @param quotaPerPeriod  每期终端需求上限（不是发货上限）
     * @param openingPrice    开局寄售结算价（EXP）
     * @param priceFloor      已冻结的 EXP 底价
     */
    public record ChannelSpec(String channelId, String accountId, long quotaPerPeriod,
                              BigDecimal openingPrice, BigDecimal priceFloor) {

        /** 校验必要字段。 */
        public ChannelSpec {
            requireText(channelId, "渠道");
            requireText(accountId, "渠道账户");
            if (quotaPerPeriod < 0) {
                throw new IllegalArgumentException("渠道配额不能为负");
            }
            money(openingPrice);
            money(priceFloor);
            if (priceFloor.signum() < 0) {
                throw new IllegalArgumentException("底价不能为负");
            }
        }
    }

    /**
     * 冻结的外部需求条目：某期某渠道的终端需求上限（与价格无关）。
     *
     * @param period    期间序号（从 0 开始）
     * @param channelId 渠道
     * @param units     该期需求上限
     */
    public record DemandQuote(long period, String channelId, long units) {

        /** 校验。 */
        public DemandQuote {
            if (period < 0 || units < 0) {
                throw new IllegalArgumentException("需求条目不能为负");
            }
            requireText(channelId, "渠道");
        }
    }

    /**
     * 陈酿子状态配置（全部为 EXP 参数，未按现实校准）。
     *
     * @param companyPartyId       公司主体
     * @param managerPersonId      经理人物（EXP 替身；World 后继接真实任职）
     * @param companyAccountId     公司现金账户
     * @param supplierAccountId    原料供应商账户
     * @param conversionAccountId  转产费收款账户
     * @param fixedAccountId       固定费收款账户
     * @param terminalAccountId    终端消费者聚合账户
     * @param rawUnitCost          采购单价（EXP 常量）
     * @param conversionPerUnit    单位转产费
     * @param fixedPerPeriod       每期固定费用
     * @param periodMillis         每期毫秒数
     * @param capacityPerPeriod    每期产能上限
     * @param agingPeriods         生产完成到可销售的期间数
     * @param shelfPeriods         可销售窗口期间数（届满全额报废）
     * @param spoilageBp           成熟库存每期报损万分比
     * @param prepayBp             发货预收万分比
     * @param channels             渠道清单
     * @param demand               冻结需求序列
     * @param expLabel             EXP 标识
     */
    public record Config(String companyPartyId, String managerPersonId, String companyAccountId,
                         String supplierAccountId, String conversionAccountId,
                         String fixedAccountId, String terminalAccountId,
                         BigDecimal rawUnitCost, BigDecimal conversionPerUnit,
                         BigDecimal fixedPerPeriod, long periodMillis,
                         long capacityPerPeriod, long agingPeriods, long shelfPeriods,
                         int spoilageBp, int prepayBp, List<ChannelSpec> channels,
                         List<DemandQuote> demand, String expLabel) {

        /** 校验并把集合冻结为不可变。 */
        public Config {
            requireText(companyPartyId, "公司");
            requireText(managerPersonId, "经理");
            requireText(companyAccountId, "公司账户");
            requireText(supplierAccountId, "供应商账户");
            requireText(conversionAccountId, "转产账户");
            requireText(fixedAccountId, "固定费账户");
            requireText(terminalAccountId, "终端账户");
            money(rawUnitCost);
            money(conversionPerUnit);
            money(fixedPerPeriod);
            if (rawUnitCost.signum() < 0 || conversionPerUnit.signum() < 0
                    || fixedPerPeriod.signum() < 0) {
                throw new IllegalArgumentException("成本与费用不能为负");
            }
            if (periodMillis <= 0 || capacityPerPeriod <= 0 || agingPeriods < 0
                    || shelfPeriods <= 0) {
                throw new IllegalArgumentException("期间/产能/陈酿参数不合法");
            }
            if (spoilageBp < 0 || spoilageBp > 10000 || prepayBp < 0 || prepayBp > 10000) {
                throw new IllegalArgumentException("万分比越界");
            }
            requireText(expLabel, "EXP 标识");
            channels = List.copyOf(Objects.requireNonNull(channels, "渠道"));
            demand = List.copyOf(Objects.requireNonNull(demand, "需求"));
            if (channels.isEmpty()) {
                throw new IllegalArgumentException("至少需要一个渠道");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (ChannelSpec channel : channels) {
                if (!ids.add(channel.channelId())) {
                    throw new IllegalArgumentException("渠道重复");
                }
            }
        }

        /**
         * 期间序号（从 0 开始）。
         *
         * @param now 世界时点毫秒
         * @return 期间序号
         */
        public long periodOf(long now) {
            return Math.max(0L, now) / periodMillis;
        }

        /**
         * 期间结束时刻（不含）。
         *
         * @param period 期间序号
         * @return 结束毫秒
         */
        public long periodEnd(long period) {
            return Math.multiplyExact(period + 1, periodMillis);
        }

        /**
         * 查渠道规格。
         *
         * @param channelId 渠道
         * @return 规格；未知为 null
         */
        public ChannelSpec channel(String channelId) {
            for (ChannelSpec channel : channels) {
                if (channel.channelId().equals(channelId)) {
                    return channel;
                }
            }
            return null;
        }

        /**
         * 某期某渠道的冻结需求上限。
         *
         * @param period    期间
         * @param channelId 渠道
         * @return 需求单位；未配置为 0
         */
        public long demandOf(long period, String channelId) {
            long total = 0L;
            for (DemandQuote quote : demand) {
                if (quote.period() == period && quote.channelId().equals(channelId)) {
                    total = Math.addExact(total, quote.units());
                }
            }
            return total;
        }
    }

    /** 已下单未到货的采购。 */
    public record PurchaseOrder(String orderId, long units, BigDecimal unitCost,
                                long placedAtPeriod, long duePeriod) {

        /** 校验。 */
        public PurchaseOrder {
            requireText(orderId, "采购单");
            if (units <= 0) {
                throw new IllegalArgumentException("采购数量必须为正");
            }
            money(unitCost);
        }
    }

    /** 原料 lot；{@code remainingCost} 是权威余额，逐 lot FIFO 消耗。 */
    public record RawLot(String lotId, long units, BigDecimal totalCost,
                         BigDecimal remainingCost, long receivedAtPeriod, long remaining) {

        /** 校验守恒与余额。 */
        public RawLot {
            requireText(lotId, "原料 lot");
            money(totalCost);
            money(remainingCost);
            if (units <= 0 || remaining < 0 || remaining > units) {
                throw new IllegalArgumentException("原料 lot 数量不合法");
            }
            if (remainingCost.signum() < 0 || remainingCost.compareTo(totalCost) > 0) {
                throw new IllegalArgumentException("原料 lot 成本余额不合法");
            }
        }
    }

    /**
     * 生产批次 lot；成本按总额权威，部分转出/报损按累计比例分摊。
     *
     * @param batchId         批次
     * @param units           产量
     * @param producedAtPeriod 投产期
     * @param matureAtPeriod  可销售起始期
     * @param totalCost       批次总成本
     * @param costOut         已转出到寄售 lot 的成本
     * @param costSpoiled     已报损成本
     * @param remaining       仓库剩余单位
     * @param shipped         累计发出单位
     * @param spoiled         累计报损单位
     */
    public record BatchLot(String batchId, long units, long producedAtPeriod, long matureAtPeriod,
                           BigDecimal rawCost, BigDecimal totalCost, BigDecimal costOut,
                           BigDecimal costSpoiled, long remaining, long shipped, long spoiled) {

        /** 校验数量守恒与成本不超总额。 */
        public BatchLot {
            requireText(batchId, "批次");
            money(rawCost);
            money(totalCost);
            money(costOut);
            money(costSpoiled);
            if (units <= 0 || remaining < 0 || shipped < 0 || spoiled < 0
                    || remaining + shipped + spoiled != units) {
                throw new IllegalArgumentException("批次数量不守恒");
            }
            if (rawCost.compareTo(totalCost) > 0) {
                throw new IllegalArgumentException("批次原料成本超过总成本");
            }
            if (costOut.add(costSpoiled).compareTo(totalCost) > 0) {
                throw new IllegalArgumentException("批次转出成本超过总成本");
            }
        }

        /** @return 仓库剩余成本 */
        public BigDecimal remainingCost() {
            return totalCost.subtract(costOut).subtract(costSpoiled);
        }

        /** @return 已发生转产费（总成本 − 原料成本） */
        public BigDecimal conversionCost() {
            return totalCost.subtract(rawCost);
        }
    }

    /**
     * 寄售 lot：发出后所有权仍归公司，价格与预收总额固定。
     *
     * @param shipmentId      发货标识（一次发货可拆多条 lot 共用同一标识）
     * @param channelId       渠道
     * @param batchId         来源批次
     * @param units           发出单位
     * @param totalCost       发出成本（来自批次分摊）
     * @param totalPrepaid    本次发出实际收到的预收总额
     * @param prepaidApplied  已随售出抵扣的预收
     * @param consignmentPrice 寄售结算价（成交价）
     * @param matureAtPeriod  来源批次的可销售起始期
     * @param remaining       渠道未售单位
     * @param sold            已售单位
     * @param spoiled         已报损单位
     * @param atPeriod        发出期
     */
    public record ShipmentLot(String shipmentId, String channelId, String batchId, long units,
                              BigDecimal totalCost, BigDecimal totalPrepaid,
                              BigDecimal prepaidApplied, BigDecimal consignmentPrice,
                              long matureAtPeriod, long remaining, long sold, long spoiled,
                              long atPeriod) {

        /** 校验数量守恒与预收不超收。 */
        public ShipmentLot {
            requireText(shipmentId, "发货");
            requireText(channelId, "渠道");
            requireText(batchId, "批次");
            money(totalCost);
            money(totalPrepaid);
            money(prepaidApplied);
            money(consignmentPrice);
            if (units <= 0 || remaining < 0 || sold < 0 || spoiled < 0
                    || remaining + sold + spoiled != units) {
                throw new IllegalArgumentException("寄售 lot 数量不守恒");
            }
            if (prepaidApplied.compareTo(totalPrepaid) > 0) {
                throw new IllegalArgumentException("已抵预收超过预收总额");
            }
        }

        /** @return 未抵扣预收（合同负债） */
        public BigDecimal unappliedPrepaid() {
            return totalPrepaid.subtract(prepaidApplied);
        }

        /** @return 累计消耗成本（售出+报损，按累计比例） */
        public BigDecimal consumedCost() {
            return AgedInventoryModel.allocate(totalCost, units, 0, consumedUnits(),
                    BigDecimal.ZERO);
        }

        /** @return 渠道未售部分成本 */
        public BigDecimal remainingCost() {
            return totalCost.subtract(consumedCost());
        }

        /** @return 累计消耗单位（售出+报损） */
        public long consumedUnits() {
            return sold + spoiled;
        }
    }

    /**
     * 一次成交（按 lot 逐条记录，跨 lot 的成交拆成多条）。
     *
     * @param saleId         成交标识
     * @param shipmentId     来源发货
     * @param channelId      渠道
     * @param units          成交单位
     * @param salePrice      成交价（= 该 lot 的寄售价）
     * @param revenue        成交额
     * @param prepaidApplied 本次抵扣预收
     * @param cogs           本次成本
     * @param atPeriod       成交期
     */
    public record Sale(String saleId, String shipmentId, String channelId, long units,
                       BigDecimal salePrice, BigDecimal revenue, BigDecimal prepaidApplied,
                       BigDecimal cogs, long atPeriod) {

        /** 校验。 */
        public Sale {
            requireText(saleId, "成交");
            money(salePrice);
            money(revenue);
            money(prepaidApplied);
            money(cogs);
            if (units <= 0) {
                throw new IllegalArgumentException("成交数量必须为正");
            }
        }
    }

    /**
     * 报损/过期事实。
     *
     * @param lotId     批次或发货 lot
     * @param channelId 渠道（仓库报损为空串）
     * @param units     单位
     * @param cost      成本
     * @param reason    {@code SPOIL} 或 {@code EXPIRED}
     * @param atPeriod  期间
     */
    public record Spoilage(String lotId, String channelId, long units, BigDecimal cost,
                           String reason, long atPeriod) {

        /** 校验。 */
        public Spoilage {
            requireText(lotId, "lot");
            money(cost);
            requireText(reason, "原因");
            if (units <= 0) {
                throw new IllegalArgumentException("报损单位必须为正");
            }
        }
    }

    /**
     * 分渠道 gross 余额与累计流量；net 只在报表作 memo，不作为恒等式。
     *
     * @param channelId          渠道
     * @param receivable         gross 应收
     * @param contractLiability  gross 合同负债（未抵扣预收）
     * @param refundPayable      退款应付
     * @param prepaidReceived    累计预收实收
     * @param collected          累计回款
     * @param refundPaid         累计退款
     * @param prepaidExtraApplied 累计抵扣预收
     */
    public record ChannelLedger(String channelId, BigDecimal receivable,
                                BigDecimal contractLiability, BigDecimal refundPayable,
                                BigDecimal prepaidReceived, BigDecimal collected,
                                BigDecimal refundPaid, BigDecimal prepaidExtraApplied) {

        /** 校验非负。 */
        public ChannelLedger {
            requireText(channelId, "渠道");
            for (BigDecimal value : List.of(receivable, contractLiability, refundPayable,
                    prepaidReceived, collected, refundPaid, prepaidExtraApplied)) {
                money(value);
                if (value.signum() < 0) {
                    throw new IllegalArgumentException("渠道余额不能为负");
                }
            }
        }

        /** @return 同渠道空账 */
        public static ChannelLedger zero(String channelId) {
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            return new ChannelLedger(channelId, zero, zero, zero, zero, zero, zero, zero);
        }
    }

    /**
     * 期末事实。
     *
     * @param period             期间
     * @param fixedAccrued       累计应计固定费
     * @param fixedPaid          累计实付
     * @param fixedPayableAfter  期末未付
     */
    public record PeriodClose(long period, BigDecimal fixedAccrued, BigDecimal fixedPaid,
                              BigDecimal fixedPayableAfter) {

        /** 校验。 */
        public PeriodClose {
            money(fixedAccrued);
            money(fixedPaid);
            money(fixedPayableAfter);
        }
    }

    /**
     * 动作回执（只记被接受的动作；拒绝不改变状态）。
     *
     * @param seq           序号
     * @param atPeriod      期间
     * @param action        动作名
     * @param actorPersonId 发起人
     * @param reason        结果码
     * @param refId         关联标识
     */
    public record Report(long seq, long atPeriod, String action, String actorPersonId,
                         String reason, String refId) { }

    /**
     * 陈酿子状态。
     *
     * @param config               配置
     * @param openingCompanyCash   开局公司现金
     * @param openingInventoryCost 开局原料存货成本
     * @param openingEquity        开局权益（公司现金 + 开局存货）
     * @param openingTotalCash     全主体开局现金合计（守恒基准）
     * @param closedPeriods        已关闭期间数
     * @param orders               已下单未到货
     * @param rawLots              原料 lot
     * @param batches              生产批次
     * @param shipmentLots         寄售 lot
     * @param sales                成交
     * @param spoilage             报损
     * @param channelLedgers       分渠道账
     * @param currentPrices        渠道当前寄售价（只影响未来发货）
     * @param fixedAccrued         累计应计固定费
     * @param fixedPaid            累计实付固定费
     * @param fixedPayable         未付固定费
     * @param closes               期末事实
     * @param reports              已接受动作回执
     */
    public record State(Config config, BigDecimal openingCompanyCash,
                        BigDecimal openingInventoryCost, BigDecimal openingEquity,
                        BigDecimal openingTotalCash, long closedPeriods,
                        List<PurchaseOrder> orders, List<RawLot> rawLots, List<BatchLot> batches,
                        List<ShipmentLot> shipmentLots, List<Sale> sales, List<Spoilage> spoilage,
                        List<ChannelLedger> channelLedgers, Map<String, BigDecimal> currentPrices,
                        BigDecimal fixedAccrued, BigDecimal fixedPaid, BigDecimal fixedPayable,
                        List<PeriodClose> closes, List<Report> reports) {

        /** 冻结全部集合。 */
        public State {
            money(openingCompanyCash);
            money(openingInventoryCost);
            money(openingEquity);
            money(openingTotalCash);
            money(fixedAccrued);
            money(fixedPaid);
            money(fixedPayable);
            orders = List.copyOf(orders);
            rawLots = List.copyOf(rawLots);
            batches = List.copyOf(batches);
            shipmentLots = List.copyOf(shipmentLots);
            sales = List.copyOf(sales);
            spoilage = List.copyOf(spoilage);
            channelLedgers = List.copyOf(channelLedgers);
            currentPrices = Map.copyOf(currentPrices);
            closes = List.copyOf(closes);
            reports = List.copyOf(reports);
        }
    }

    /**
     * 动作结果：接受时返回候选状态与候选账户表；拒绝时原样返回入参。
     *
     * @param accepted 是否接受
     * @param reason   结果码
     * @param state    结果状态
     * @param accounts 结果账户表
     */
    public record Outcome(boolean accepted, String reason, State state,
                          Map<String, CashLedger.CashAccount> accounts) { }

    /**
     * 交叉核对用的账面汇总（全部由状态派生，不含缓存）。
     *
     * @param companyCash        公司现金
     * @param inventoryCost      期末存货成本（原料+仓库+寄售）
     * @param receivable         gross 应收合计
     * @param contractLiability  gross 合同负债合计
     * @param refundPayable      退款应付合计
     * @param fixedPayable       未付固定费
     * @param revenue            累计收入
     * @param cogs               累计成本
     * @param spoilageLoss       累计报损
     * @param fixedAccrued       累计应计固定费
     * @param profit             累计利润
     * @param assets             公司资产
     * @param liabilities        公司负债
     * @param equity             账面权益（开局权益+利润）
     * @param totalCash          全主体现金合计
     * @param producedUnits      累计产量
     * @param immatureUnits      未成熟单位
     * @param matureWarehouseUnits 成熟仓库单位
     * @param consignmentUnits   寄售未售单位
     * @param soldUnits          已售单位
     * @param spoiledUnits       报损/过期单位
     * @param netReceivableMemo  净应收（memo，不作恒等式）
     */
    public record Books(BigDecimal companyCash, BigDecimal inventoryCost, BigDecimal receivable,
                        BigDecimal contractLiability, BigDecimal refundPayable,
                        BigDecimal fixedPayable, BigDecimal revenue, BigDecimal cogs,
                        BigDecimal spoilageLoss, BigDecimal fixedAccrued, BigDecimal profit,
                        BigDecimal assets, BigDecimal liabilities, BigDecimal equity,
                        BigDecimal totalCash, long producedUnits, long immatureUnits,
                        long matureWarehouseUnits, long consignmentUnits, long soldUnits,
                        long spoiledUnits, BigDecimal netReceivableMemo) { }

    private AgedInventoryModel() {
    }

    // ---------------------------------------------------------------- 开局

    /**
     * 开局：校验所有对手账户存在，建立开局原料 lot 与权益基准。
     *
     * @param config  配置
     * @param accounts 真实账户表
     * @param seeds   开局原料 lot（可为空）
     * @return 结果
     */
    public static Outcome open(Config config, Map<String, CashLedger.CashAccount> accounts,
                               List<RawLot> seeds) {
        Objects.requireNonNull(config, "配置");
        Objects.requireNonNull(accounts, "账户");
        for (String accountId : List.of(config.companyAccountId(), config.supplierAccountId(),
                config.conversionAccountId(), config.fixedAccountId(),
                config.terminalAccountId())) {
            if (!accounts.containsKey(accountId)) {
                return rejected(null, accounts, COUNTERPARTY_INVALID);
            }
        }
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        List<ChannelLedger> ledgers = new ArrayList<>();
        // F4：禁止会自付自收的配置——公司账户不得与任何外部收款账户重合，渠道账户不得与终端账户重合。
        // 两个外部收款角色共用同一账户是允许的（每条现金腿两端仍不同），此处只禁会自成交的组合。
        for (String other : List.of(config.supplierAccountId(), config.conversionAccountId(),
                config.fixedAccountId(), config.terminalAccountId())) {
            if (other.equals(config.companyAccountId())) {
                return rejected(null, accounts, COUNTERPARTY_INVALID);
            }
        }
        for (ChannelSpec channel : config.channels()) {
            if (channel.accountId().equals(config.companyAccountId())
                    || channel.accountId().equals(config.terminalAccountId())) {
                return rejected(null, accounts, COUNTERPARTY_INVALID);
            }
            if (!accounts.containsKey(channel.accountId())) {
                return rejected(null, accounts, COUNTERPARTY_INVALID);
            }
            prices.put(channel.channelId(), money(channel.openingPrice()));
            ledgers.add(ChannelLedger.zero(channel.channelId()));
        }
        List<RawLot> lots = new ArrayList<>(seeds == null ? List.of() : seeds);
        BigDecimal openingInventory = BigDecimal.ZERO.setScale(2);
        for (RawLot lot : lots) {
            openingInventory = openingInventory.add(lot.remainingCost());
        }
        BigDecimal companyCash = accounts.get(config.companyAccountId()).cash();
        BigDecimal totalCash = BigDecimal.ZERO.setScale(2);
        for (CashLedger.CashAccount account : accounts.values()) {
            totalCash = totalCash.add(account.cash());
        }
        State state = new State(config, money(companyCash), money(openingInventory),
                money(companyCash.add(openingInventory)), money(totalCash), 0L, List.of(), lots,
                List.of(), List.of(), List.of(), List.of(), ledgers, prices, zero(), zero(), zero(),
                List.of(), List.of());
        return accepted(state, accounts, "OPEN", null);
    }

    // ---------------------------------------------------------------- 动作

    /**
     * 下单（不付现，到货期由 {@link #receiveOrder} 付款建 lot）。
     *
     * @param state   当前状态
     * @param actor   发起人
     * @param units   数量
     * @param orderId 采购单标识
     * @param now     世界时点
     * @return 结果
     */
    public static Outcome orderRaw(State state, String actor, long units, String orderId, long now,
                                   Map<String, CashLedger.CashAccount> accounts) {
        String reason = authorize(state, actor);
        if (reason != null) {
            return rejected(state, accounts, reason);
        }
        long period = state.config().periodOf(now);
        if (period < state.closedPeriods()) {
            return rejected(state, accounts, PERIOD_CLOSED);
        }
        if (units <= 0) {
            return rejected(state, accounts, INVALID_QUANTITY);
        }
        if (used(state, orderId)) {
            return rejected(state, accounts, ID_REUSED);
        }
        List<PurchaseOrder> orders = new ArrayList<>(state.orders());
        orders.add(new PurchaseOrder(orderId, units, money(state.config().rawUnitCost()),
                period, period + 1));
        State candidate = copy(state).orders(orders).report(period, "ORDER_RAW", actor, orderId);
        return accepted(candidate, accounts, "ORDER_RAW", orderId);
    }

    /**
     * 采购到货：按当前 EXP 采购价付款并建原料 lot。
     *
     * @param state   状态
     * @param actor   发起人
     * @param orderId 采购单
     * @param now     世界时点
     * @param accounts 账户表
     * @return 结果
     */
    public static Outcome receiveOrder(State state, String actor, String orderId, long now,
                                       Map<String, CashLedger.CashAccount> accounts) {
        String reason = authorize(state, actor);
        if (reason != null) {
            return rejected(state, accounts, reason);
        }
        long period = state.config().periodOf(now);
        if (period < state.closedPeriods()) {
            return rejected(state, accounts, PERIOD_CLOSED);
        }
        PurchaseOrder order = order(state, orderId);
        if (order == null) {
            return rejected(state, accounts, UNKNOWN_ORDER);
        }
        if (period < order.duePeriod()) {
            return rejected(state, accounts, PERIOD_NOT_ENDED);
        }
        BigDecimal amount = money(BigDecimal.valueOf(order.units()).multiply(order.unitCost()));
        Map<String, CashLedger.CashAccount> next = transfer(accounts,
                accounts.get(state.config().companyAccountId()).ownerId(),
                state.config().companyAccountId(), state.config().supplierAccountId(), amount);
        if (next == null) {
            return rejected(state, accounts, CASH_SHORT);
        }
        List<PurchaseOrder> orders = new ArrayList<>(state.orders());
        orders.remove(order);
        List<RawLot> lots = new ArrayList<>(state.rawLots());
        lots.add(new RawLot(orderId, order.units(), amount, amount, period, order.units()));
        State candidate = copy(state).orders(orders).rawLots(lots)
                .report(period, "RECEIVE_RAW", actor, orderId);
        return accepted(candidate, next, "RECEIVE_RAW", orderId);
    }

    /**
     * 投产：原料按 lot FIFO 消耗、付转产费、建批次。
     *
     * @param state   状态
     * @param actor   发起人
     * @param units   数量
     * @param batchId 批次标识
     * @param now     世界时点
     * @param accounts 账户表
     * @return 结果
     */
    public static Outcome produce(State state, String actor, long units, String batchId, long now,
                                  Map<String, CashLedger.CashAccount> accounts) {
        String reason = authorize(state, actor);
        if (reason != null) {
            return rejected(state, accounts, reason);
        }
        long period = state.config().periodOf(now);
        if (period < state.closedPeriods()) {
            return rejected(state, accounts, PERIOD_CLOSED);
        }
        if (units <= 0) {
            return rejected(state, accounts, INVALID_QUANTITY);
        }
        // 每期产能是**累计**上限：本期已投产（同 generated period 的批次）也占额度。
        long producedThisPeriod = 0L;
        for (BatchLot batch : state.batches()) {
            if (batch.producedAtPeriod() == period) {
                producedThisPeriod = Math.addExact(producedThisPeriod, batch.units());
            }
        }
        if (producedThisPeriod + units > state.config().capacityPerPeriod()) {
            return rejected(state, accounts, CAPACITY_EXCEEDED);
        }
        if (used(state, batchId)) {
            return rejected(state, accounts, ID_REUSED);
        }
        long available = 0L;
        for (RawLot lot : state.rawLots()) {
            available = Math.addExact(available, lot.remaining());
        }
        if (available < units) {
            return rejected(state, accounts, MATERIAL_SHORT);
        }
        List<RawLot> lots = new ArrayList<>(state.rawLots());
        long need = units;
        BigDecimal rawCost = zero();
        for (int index = 0; index < lots.size() && need > 0; index++) {
            RawLot lot = lots.get(index);
            if (lot.remaining() == 0) {
                continue;
            }
            long take = Math.min(need, lot.remaining());
            long consumedBefore = lot.units() - lot.remaining();
            BigDecimal allocated = lot.totalCost().subtract(lot.remainingCost());
            BigDecimal cost = allocate(lot.totalCost(), lot.units(), consumedBefore, take, allocated);
            lots.set(index, new RawLot(lot.lotId(), lot.units(), lot.totalCost(),
                    lot.remainingCost().subtract(cost), lot.receivedAtPeriod(),
                    lot.remaining() - take));
            rawCost = rawCost.add(cost);
            need -= take;
        }
        BigDecimal conversion = money(BigDecimal.valueOf(units)
                .multiply(state.config().conversionPerUnit()));
        Map<String, CashLedger.CashAccount> next = transfer(accounts,
                accounts.get(state.config().companyAccountId()).ownerId(),
                state.config().companyAccountId(), state.config().conversionAccountId(),
                conversion);
        if (next == null) {
            return rejected(state, accounts, CASH_SHORT);
        }
        List<BatchLot> batches = new ArrayList<>(state.batches());
        batches.add(new BatchLot(batchId, units, period, period + state.config().agingPeriods(),
                money(rawCost), money(rawCost.add(conversion)), zero(), zero(), units, 0L, 0L));
        State candidate = copy(state).rawLots(lots).batches(batches)
                .report(period, "PRODUCE", actor, batchId);
        return accepted(candidate, next, "PRODUCE", batchId);
    }

    /**
     * 调价：只写未来发货价，不改既有 lot/成交。
     *
     * @param state   状态
     * @param actor   发起人
     * @param channelId 渠道
     * @param price   新寄售价
     * @param now     世界时点
     * @return 结果
     */
    public static Outcome setPrice(State state, String actor, String channelId, BigDecimal price,
                                   long now, Map<String, CashLedger.CashAccount> accounts) {
        String reason = authorize(state, actor);
        if (reason != null) {
            return rejected(state, accounts, reason);
        }
        ChannelSpec channel = state.config().channel(channelId);
        if (channel == null) {
            return rejected(state, accounts, UNKNOWN_CHANNEL);
        }
        long period = state.config().periodOf(now);
        if (period < state.closedPeriods()) {
            return rejected(state, accounts, PERIOD_CLOSED);
        }
        BigDecimal value = money(price);
        if (value.compareTo(channel.priceFloor()) < 0) {
            return rejected(state, accounts, PRICE_BELOW_FLOOR);
        }
        Map<String, BigDecimal> prices = new LinkedHashMap<>(state.currentPrices());
        prices.put(channelId, value);
        State candidate = copy(state).currentPrices(prices)
                .report(period, "SET_PRICE", actor, channelId);
        return accepted(candidate, accounts, "SET_PRICE", channelId);
    }

    /**
     * 发货（寄售）：成熟批次 FIFO 拆寄售 lot，渠道真实支付预收；任一条件不足整笔拒绝。
     *
     * @param state      状态
     * @param actor      发起人
     * @param channelId  渠道
     * @param units      数量
     * @param shipmentId 发货标识
     * @param now        世界时点
     * @param accounts   账户表
     * @return 结果
     */
    public static Outcome ship(State state, String actor, String channelId, long units,
                               String shipmentId, long now,
                               Map<String, CashLedger.CashAccount> accounts) {
        String reason = authorize(state, actor);
        if (reason != null) {
            return rejected(state, accounts, reason);
        }
        ChannelSpec channel = state.config().channel(channelId);
        if (channel == null) {
            return rejected(state, accounts, UNKNOWN_CHANNEL);
        }
        long period = state.config().periodOf(now);
        if (period < state.closedPeriods()) {
            return rejected(state, accounts, PERIOD_CLOSED);
        }
        if (units <= 0) {
            return rejected(state, accounts, INVALID_QUANTITY);
        }
        if (used(state, shipmentId)) {
            return rejected(state, accounts, ID_REUSED);
        }
        List<BatchLot> batches = new ArrayList<>(state.batches());
        List<Integer> sources = new ArrayList<>();
        long available = 0L;
        for (int index = 0; index < batches.size(); index++) {
            BatchLot batch = batches.get(index);
            if (batch.remaining() == 0) {
                continue;
            }
            if (period < batch.matureAtPeriod()) {
                continue;
            }
            if (period >= batch.matureAtPeriod() + state.config().shelfPeriods()) {
                continue;
            }
            sources.add(index);
            available = Math.addExact(available, batch.remaining());
            if (available >= units) {
                break;
            }
        }
        if (available < units) {
            return rejected(state, accounts, NO_CHANNEL_STOCK);
        }
        BigDecimal price = state.currentPrices().get(channelId);
        if (price == null || price.compareTo(channel.priceFloor()) < 0) {
            return rejected(state, accounts, PRICE_BELOW_FLOOR);
        }
        BigDecimal requiredPrepay = prepayOf(units, price, state.config().prepayBp());
        Map<String, CashLedger.CashAccount> next = accounts;
        if (requiredPrepay.signum() > 0) {
            if (accounts.get(channel.accountId()).cash().compareTo(requiredPrepay) < 0) {
                return rejected(state, accounts, CHANNEL_CASH_SHORT);
            }
            next = transfer(accounts, accounts.get(channel.accountId()).ownerId(),
                    channel.accountId(), state.config().companyAccountId(), requiredPrepay);
            if (next == null) {
                return rejected(state, accounts, CHANNEL_CASH_SHORT);
            }
        }
        List<ShipmentLot> lots = new ArrayList<>(state.shipmentLots());
        long need = units;
        long allocated = 0L;
        BigDecimal prepaidAllocated = zero();
        for (int index : sources) {
            if (need == 0) {
                break;
            }
            BatchLot batch = batches.get(index);
            long take = Math.min(need, batch.remaining());
            long shippedBefore = batch.units() - batch.remaining();
            BigDecimal cost = allocate(batch.totalCost(), batch.units(), shippedBefore, take,
                    batch.costOut().add(batch.costSpoiled()));
            BigDecimal prepaid = allocate(requiredPrepay, units, allocated, take, prepaidAllocated);
            batches.set(index, new BatchLot(batch.batchId(), batch.units(),
                    batch.producedAtPeriod(), batch.matureAtPeriod(), batch.rawCost(),
                    batch.totalCost(), batch.costOut().add(cost), batch.costSpoiled(),
                    batch.remaining() - take, batch.shipped() + take, batch.spoiled()));
            lots.add(new ShipmentLot(shipmentId, channelId, batch.batchId(), take, cost, prepaid,
                    zero(), price, batch.matureAtPeriod(), take, 0L, 0L, period));
            allocated += take;
            prepaidAllocated = prepaidAllocated.add(prepaid);
            need -= take;
        }
        List<ChannelLedger> ledgers = adjustLedger(state, channelId,
                zero(), requiredPrepay, zero(), requiredPrepay, zero(), zero());
        State candidate = copy(state).batches(batches).shipmentLots(lots)
                .channelLedgers(ledgers).report(period, "SHIP", actor, shipmentId);
        return accepted(candidate, next, "SHIP", shipmentId);
    }

    /**
     * 终端成交（sell-through）：终端真实付款给渠道，才确认收入与应收。
     *
     * @param state     状态
     * @param channelId 渠道
     * @param units     数量
     * @param saleId    成交标识
     * @param now       世界时点
     * @param accounts  账户表
     * @return 结果
     */
    public static Outcome sellThrough(State state, String channelId, long units, String saleId,
                                      long now, Map<String, CashLedger.CashAccount> accounts) {
        ChannelSpec channel = state.config().channel(channelId);
        if (channel == null) {
            return rejected(state, accounts, UNKNOWN_CHANNEL);
        }
        long period = state.config().periodOf(now);
        if (period < state.closedPeriods()) {
            return rejected(state, accounts, PERIOD_CLOSED);
        }
        if (units <= 0) {
            return rejected(state, accounts, INVALID_QUANTITY);
        }
        if (used(state, saleId)) {
            return rejected(state, accounts, ID_REUSED);
        }
        long demand = state.config().demandOf(period, channelId);
        long soldThisPeriod = 0L;
        for (Sale sale : state.sales()) {
            if (sale.atPeriod() == period && sale.channelId().equals(channelId)) {
                soldThisPeriod = Math.addExact(soldThisPeriod, sale.units());
            }
        }
        long demandLeft = Math.max(0L, demand - soldThisPeriod);
        long quotaLeft = Math.max(0L, channel.quotaPerPeriod() - soldThisPeriod);
        if (demandLeft < units) {
            return rejected(state, accounts, DEMAND_EXHAUSTED);
        }
        if (quotaLeft < units) {
            return rejected(state, accounts, CHANNEL_QUOTA_EXCEEDED);
        }
        List<ShipmentLot> lots = new ArrayList<>(state.shipmentLots());
        List<Integer> sources = new ArrayList<>();
        long available = 0L;
        boolean expiredStock = false;
        for (int index = 0; index < lots.size(); index++) {
            ShipmentLot lot = lots.get(index);
            if (!lot.channelId().equals(channelId) || lot.remaining() == 0) {
                continue;
            }
            // 货架期届满但尚未关期：不得继续成交（不能靠不关期卖过期货）。
            if (period >= lot.matureAtPeriod() + state.config().shelfPeriods()) {
                expiredStock = true;
                continue;
            }
            sources.add(index);
            available = Math.addExact(available, lot.remaining());
            if (available >= units) {
                break;
            }
        }
        if (available < units) {
            return rejected(state, accounts, expiredStock ? EXPIRED_LOT : NO_CHANNEL_STOCK);
        }
        // 先算出成交总额（按各 lot 自己的寄售价），再核对终端现金。
        BigDecimal totalRevenue = zero();
        long need = units;
        for (int index : sources) {
            if (need == 0) {
                break;
            }
            ShipmentLot lot = lots.get(index);
            long take = Math.min(need, lot.remaining());
            totalRevenue = totalRevenue.add(money(BigDecimal.valueOf(take)
                    .multiply(lot.consignmentPrice())));
            need -= take;
        }
        String terminalAccount = state.config().terminalAccountId();
        BigDecimal terminalCash = accounts.get(terminalAccount).cash();
        if (terminalCash.compareTo(totalRevenue) < 0) {
            return rejected(state, accounts, TERMINAL_CASH_SHORT);
        }
        Map<String, CashLedger.CashAccount> next = transfer(accounts,
                accounts.get(terminalAccount).ownerId(), terminalAccount,
                channel.accountId(), totalRevenue);
        if (next == null) {
            return rejected(state, accounts, TERMINAL_CASH_SHORT);
        }
        List<Sale> sales = new ArrayList<>(state.sales());
        BigDecimal revenueSum = zero();
        BigDecimal appliedSum = zero();
        BigDecimal cogsSum = zero();
        need = units;
        int part = 0;
        for (int index : sources) {
            if (need == 0) {
                break;
            }
            ShipmentLot lot = lots.get(index);
            long take = Math.min(need, lot.remaining());
            long consumedBefore = lot.units() - lot.remaining();
            BigDecimal applied = allocate(lot.totalPrepaid(), lot.units(), consumedBefore, take,
                    lot.prepaidApplied());
            BigDecimal cogs = costIncrement(lot, lot.consumedUnits(), take);
            BigDecimal revenue = money(BigDecimal.valueOf(take).multiply(lot.consignmentPrice()));
            String partId = part == 0 ? saleId : saleId + "#" + part;
            part++;
            sales.add(new Sale(partId, lot.shipmentId(), channelId, take,
                    lot.consignmentPrice(), revenue, applied, cogs, period));
            lots.set(index, new ShipmentLot(lot.shipmentId(), lot.channelId(), lot.batchId(),
                    lot.units(), lot.totalCost(), lot.totalPrepaid(),
                    lot.prepaidApplied().add(applied), lot.consignmentPrice(),
                    lot.matureAtPeriod(), lot.remaining() - take, lot.sold() + take,
                    lot.spoiled(), lot.atPeriod()));
            revenueSum = revenueSum.add(revenue);
            appliedSum = appliedSum.add(applied);
            cogsSum = cogsSum.add(cogs);
            need -= take;
        }
        List<ChannelLedger> ledgers = adjustLedger(state, channelId,
                revenueSum.subtract(appliedSum), appliedSum.negate(), zero(),
                zero(), zero(), appliedSum);
        State candidate = copy(state).shipmentLots(lots).sales(sales)
                .channelLedgers(ledgers)
                .report(period, "SELL_THROUGH", "terminal", saleId);
        return accepted(candidate, next, "SELL_THROUGH", saleId);
    }

    /**
     * 回款：渠道把货款汇给公司，只减应收、不确认收入。
     *
     * @param state     状态
     * @param actor     发起人
     * @param channelId 渠道
     * @param amount    请求金额
     * @param now       世界时点
     * @param accounts  账户表
     * @return 结果
     */
    public static Outcome collect(State state, String actor, String channelId, BigDecimal amount,
                                  long now, Map<String, CashLedger.CashAccount> accounts) {
        String reason = authorize(state, actor);
        if (reason != null) {
            return rejected(state, accounts, reason);
        }
        ChannelSpec channel = state.config().channel(channelId);
        if (channel == null) {
            return rejected(state, accounts, UNKNOWN_CHANNEL);
        }
        long period = state.config().periodOf(now);
        if (period < state.closedPeriods()) {
            return rejected(state, accounts, PERIOD_CLOSED);
        }
        BigDecimal requested = money(amount);
        if (requested.signum() <= 0) {
            return rejected(state, accounts, INVALID_QUANTITY);
        }
        ChannelLedger ledger = ledger(state, channelId);
        BigDecimal actual = requested.min(ledger.receivable());
        if (actual.signum() <= 0) {
            return rejected(state, accounts, NOTHING_TO_COLLECT);
        }
        if (accounts.get(channel.accountId()).cash().compareTo(actual) < 0) {
            return rejected(state, accounts, CHANNEL_CASH_SHORT);
        }
        Map<String, CashLedger.CashAccount> next = transfer(accounts,
                accounts.get(channel.accountId()).ownerId(), channel.accountId(),
                state.config().companyAccountId(), actual);
        if (next == null) {
            return rejected(state, accounts, CHANNEL_CASH_SHORT);
        }
        List<ChannelLedger> ledgers = adjustLedger(state, channelId, actual.negate(), zero(), zero(),
                zero(), actual, zero());
        State candidate = copy(state).channelLedgers(ledgers)
                .report(period, "COLLECT", actor, channelId);
        return accepted(candidate, next, "COLLECT", channelId);
    }

    /**
     * 退还未抵扣预收：公司真实现金退给渠道，不足保留应付。
     *
     * @param state     状态
     * @param actor     发起人
     * @param channelId 渠道
     * @param amount    请求金额
     * @param now       世界时点
     * @param accounts  账户表
     * @return 结果
     */
    public static Outcome payRefund(State state, String actor, String channelId, BigDecimal amount,
                                    long now, Map<String, CashLedger.CashAccount> accounts) {
        String reason = authorize(state, actor);
        if (reason != null) {
            return rejected(state, accounts, reason);
        }
        ChannelSpec channel = state.config().channel(channelId);
        if (channel == null) {
            return rejected(state, accounts, UNKNOWN_CHANNEL);
        }
        long period = state.config().periodOf(now);
        if (period < state.closedPeriods()) {
            return rejected(state, accounts, PERIOD_CLOSED);
        }
        ChannelLedger ledger = ledger(state, channelId);
        if (ledger.refundPayable().signum() <= 0) {
            return rejected(state, accounts, NOTHING_TO_REFUND);
        }
        BigDecimal requested = money(amount);
        BigDecimal actual = requested.min(ledger.refundPayable())
                .min(accounts.get(state.config().companyAccountId()).cash());
        if (actual.signum() <= 0) {
            return rejected(state, accounts, CASH_SHORT);
        }
        Map<String, CashLedger.CashAccount> next = transfer(accounts,
                accounts.get(state.config().companyAccountId()).ownerId(),
                state.config().companyAccountId(), channel.accountId(), actual);
        if (next == null) {
            return rejected(state, accounts, CASH_SHORT);
        }
        List<ChannelLedger> ledgers = adjustLedger(state, channelId, zero(), zero(),
                actual.negate(), zero(), zero(), zero());
        State candidate = copy(state).channelLedgers(ledgers)
                .report(period, "PAY_REFUND", actor, channelId);
        return accepted(candidate, next, "PAY_REFUND", channelId);
    }

    /**
     * 支付未付固定费。
     *
     * @param state   状态
     * @param actor   发起人
     * @param amount  请求金额
     * @param now     世界时点
     * @param accounts 账户表
     * @return 结果
     */
    public static Outcome payFixed(State state, String actor, BigDecimal amount, long now,
                                   Map<String, CashLedger.CashAccount> accounts) {
        String reason = authorize(state, actor);
        if (reason != null) {
            return rejected(state, accounts, reason);
        }
        long period = state.config().periodOf(now);
        if (period < state.closedPeriods()) {
            return rejected(state, accounts, PERIOD_CLOSED);
        }
        if (state.fixedPayable().signum() <= 0) {
            return rejected(state, accounts, NOTHING_TO_PAY);
        }
        BigDecimal actual = money(amount).min(state.fixedPayable())
                .min(accounts.get(state.config().companyAccountId()).cash());
        if (actual.signum() <= 0) {
            return rejected(state, accounts, CASH_SHORT);
        }
        Map<String, CashLedger.CashAccount> next = transfer(accounts,
                accounts.get(state.config().companyAccountId()).ownerId(),
                state.config().companyAccountId(), state.config().fixedAccountId(), actual);
        if (next == null) {
            return rejected(state, accounts, CASH_SHORT);
        }
        State candidate = copy(state)
                .fixed(state.fixedAccrued(), state.fixedPaid().add(actual),
                        state.fixedPayable().subtract(actual))
                .report(period, "PAY_FIXED", actor, "fixed");
        return accepted(candidate, next, "PAY_FIXED", "fixed");
    }

    /**
     * 关期：应计固定费、实付/应付、报损与过期、账龄，然后在候选上校验全部不变量。
     *
     * @param state   状态
     * @param now     世界时点
     * @param accounts 账户表
     * @return 结果
     */
    public static Outcome closePeriod(State state, long now,
                                      Map<String, CashLedger.CashAccount> accounts) {
        long period = state.closedPeriods();
        if (now < state.config().periodEnd(period)) {
            return rejected(state, accounts, PERIOD_NOT_ENDED);
        }
        for (PurchaseOrder order : state.orders()) {
            if (order.duePeriod() <= period) {
                return rejected(state, accounts, UNSETTLED_ACTIONS);
            }
        }
        BigDecimal accrued = state.fixedAccrued().add(money(state.config().fixedPerPeriod()));
        BigDecimal unpaid = accrued.subtract(state.fixedPaid());
        BigDecimal cash = accounts.get(state.config().companyAccountId()).cash();
        BigDecimal paid = unpaid.min(cash).max(zero());
        Map<String, CashLedger.CashAccount> next = accounts;
        if (paid.signum() > 0) {
            next = transfer(accounts, accounts.get(state.config().companyAccountId()).ownerId(),
                    state.config().companyAccountId(), state.config().fixedAccountId(), paid);
            if (next == null) {
                return rejected(state, accounts, CASH_SHORT);
            }
        }
        BigDecimal fixedPaid = state.fixedPaid().add(paid);
        BigDecimal payable = accrued.subtract(fixedPaid);

        List<BatchLot> batches = new ArrayList<>(state.batches());
        List<ShipmentLot> lots = new ArrayList<>(state.shipmentLots());
        List<Spoilage> spoilage = new ArrayList<>(state.spoilage());
        List<ChannelLedger> ledgers = new ArrayList<>(state.channelLedgers());
        long end = state.config().periodEnd(period);
        // 仓库批次：先过期全额，再按比例报损。
        for (int index = 0; index < batches.size(); index++) {
            BatchLot batch = batches.get(index);
            if (batch.remaining() == 0 || period < batch.matureAtPeriod()) {
                continue;
            }
            boolean expired = period >= batch.matureAtPeriod() + state.config().shelfPeriods();
            long take = expired ? batch.remaining()
                    : Math.min(batch.remaining(),
                    spoilUnits(batch.remaining(), state.config().spoilageBp()));
            if (take <= 0) {
                continue;
            }
            long consumedBefore = batch.units() - batch.remaining();
            BigDecimal cost = allocate(batch.totalCost(), batch.units(), consumedBefore, take,
                    batch.costOut().add(batch.costSpoiled()));
            batches.set(index, new BatchLot(batch.batchId(), batch.units(),
                    batch.producedAtPeriod(), batch.matureAtPeriod(), batch.rawCost(),
                    batch.totalCost(), batch.costOut(), batch.costSpoiled().add(cost),
                    batch.remaining() - take, batch.shipped(), batch.spoiled() + take));
            spoilage.add(new Spoilage(batch.batchId(), "", take, cost,
                    expired ? "EXPIRED" : "SPOIL", period));
        }
        // 寄售 lot：同一报损政策；过期时未抵扣预收转退款应付。
        for (int index = 0; index < lots.size(); index++) {
            ShipmentLot lot = lots.get(index);
            if (lot.remaining() == 0 || period < lot.matureAtPeriod()) {
                continue;
            }
            boolean expired = period >= lot.matureAtPeriod() + state.config().shelfPeriods();
            long take = expired ? lot.remaining()
                    : Math.min(lot.remaining(),
                    spoilUnits(lot.remaining(), state.config().spoilageBp()));
            if (take <= 0) {
                continue;
            }
            long consumedBefore = lot.consumedUnits();
            BigDecimal cost = costIncrement(lot, consumedBefore, take);
            BigDecimal unapplied = zero();
            if (expired) {
                BigDecimal appliedNow = allocate(lot.totalPrepaid(), lot.units(), consumedBefore,
                        take, lot.prepaidApplied());
                unapplied = lot.totalPrepaid().subtract(lot.prepaidApplied()).min(appliedNow);
            }
            lots.set(index, new ShipmentLot(lot.shipmentId(), lot.channelId(), lot.batchId(),
                    lot.units(), lot.totalCost(), lot.totalPrepaid(),
                    lot.prepaidApplied().add(unapplied), lot.consignmentPrice(),
                    lot.matureAtPeriod(), lot.remaining() - take, lot.sold(),
                    lot.spoiled() + take, lot.atPeriod()));
            spoilage.add(new Spoilage(lot.shipmentId() + "/" + lot.batchId(), lot.channelId(),
                    take, cost, expired ? "EXPIRED" : "SPOIL", period));
            if (unapplied.signum() > 0) {
                for (int ledgerIndex = 0; ledgerIndex < ledgers.size(); ledgerIndex++) {
                    ChannelLedger ledger = ledgers.get(ledgerIndex);
                    if (!ledger.channelId().equals(lot.channelId())) {
                        continue;
                    }
                    ledgers.set(ledgerIndex, new ChannelLedger(ledger.channelId(),
                            ledger.receivable(), ledger.contractLiability().subtract(unapplied),
                            ledger.refundPayable().add(unapplied), ledger.prepaidReceived(),
                            ledger.collected(), ledger.refundPaid(),
                            ledger.prepaidExtraApplied().add(unapplied)));
                }
            }
        }
        List<PeriodClose> closes = new ArrayList<>(state.closes());
        closes.add(new PeriodClose(period, accrued, fixedPaid, payable));
        State candidate = copy(state).closedPeriods(period + 1).batches(batches)
                .shipmentLots(lots).spoilage(spoilage).channelLedgers(ledgers)
                .fixed(accrued, fixedPaid, payable)
                .closes(closes).report(period, "CLOSE_PERIOD", "system", Long.toString(period));
        return accepted(candidate, next, "CLOSE_PERIOD", Long.toString(period));
    }

    // ---------------------------------------------------------------- 账面汇总与校验

    /**
     * 由状态派生账面汇总（不缓存、不含任何外部输入）。
     *
     * @param state    状态
     * @param accounts 账户表
     * @return 汇总
     */
    public static Books books(State state, Map<String, CashLedger.CashAccount> accounts) {
        BigDecimal rawCost = zero();
        for (RawLot lot : state.rawLots()) {
            rawCost = rawCost.add(lot.remainingCost());
        }
        BigDecimal batchCost = zero();
        long immature = 0L;
        long matureWarehouse = 0L;
        for (BatchLot batch : state.batches()) {
            batchCost = batchCost.add(batch.remainingCost());
            if (batch.remaining() == 0) {
                continue;
            }
            if (batch.matureAtPeriod() <= state.closedPeriods()) {
                matureWarehouse += batch.remaining();
            } else {
                immature += batch.remaining();
            }
        }
        BigDecimal consignmentCost = zero();
        long consignmentUnits = 0L;
        for (ShipmentLot lot : state.shipmentLots()) {
            consignmentCost = consignmentCost.add(lot.remainingCost());
            consignmentUnits += lot.remaining();
        }
        BigDecimal receivable = zero();
        BigDecimal contractLiability = zero();
        BigDecimal refundPayable = zero();
        for (ChannelLedger ledger : state.channelLedgers()) {
            receivable = receivable.add(ledger.receivable());
            contractLiability = contractLiability.add(ledger.contractLiability());
            refundPayable = refundPayable.add(ledger.refundPayable());
        }
        BigDecimal revenue = zero();
        BigDecimal cogs = zero();
        long sold = 0L;
        for (Sale sale : state.sales()) {
            revenue = revenue.add(sale.revenue());
            cogs = cogs.add(sale.cogs());
            sold += sale.units();
        }
        BigDecimal spoilageCost = zero();
        long spoiled = 0L;
        for (Spoilage item : state.spoilage()) {
            spoilageCost = spoilageCost.add(item.cost());
            spoiled += item.units();
        }
        long produced = 0L;
        for (BatchLot batch : state.batches()) {
            produced += batch.units();
        }
        BigDecimal companyCash = accounts.get(state.config().companyAccountId()).cash();
        BigDecimal totalCash = zero();
        for (CashLedger.CashAccount account : accounts.values()) {
            totalCash = totalCash.add(account.cash());
        }
        BigDecimal inventory = rawCost.add(batchCost).add(consignmentCost);
        BigDecimal profit = revenue.subtract(cogs).subtract(spoilageCost)
                .subtract(state.fixedAccrued());
        BigDecimal assets = companyCash.add(inventory).add(receivable);
        BigDecimal liabilities = contractLiability.add(refundPayable).add(state.fixedPayable());
        BigDecimal equity = state.openingEquity().add(profit);
        return new Books(money(companyCash), money(inventory), money(receivable),
                money(contractLiability), money(refundPayable), money(state.fixedPayable()),
                money(revenue), money(cogs), money(spoilageCost), money(state.fixedAccrued()),
                money(profit), money(assets), money(liabilities), money(equity), money(totalCash),
                produced, immature, matureWarehouse, consignmentUnits, sold, spoiled,
                money(receivable.subtract(contractLiability)));
    }

    /**
     * 在候选状态上校验全部不变量（提交前唯一入口）。
     *
     * @param state    候选状态
     * @param accounts 候选账户表
     * @return 违反的编号；全部通过为 null
     */
    public static String validate(State state, Map<String, CashLedger.CashAccount> accounts) {
        Books books = books(state, accounts);
        // U2 数量守恒
        long produced = books.producedUnits();
        long accounted = books.immatureUnits() + books.matureWarehouseUnits()
                + books.consignmentUnits() + books.soldUnits() + books.spoiledUnits();
        if (produced != accounted) {
            return "U2";
        }
        // C1 成本闭合：期初存货 + 已耗原料 + 已付转产 = COGS + 报损 + 期末存货
        // 已耗原料与转产都由批次实际值给出（批次总成本 − 原料成本 = 转产费），不用常量冒充。
        BigDecimal rawConsumed = zero();
        BigDecimal conversionPaid = zero();
        for (BatchLot batch : state.batches()) {
            rawConsumed = rawConsumed.add(batch.rawCost());
            conversionPaid = conversionPaid.add(batch.totalCost().subtract(batch.rawCost()));
        }
        BigDecimal rawRemaining = zero();
        BigDecimal rawTotal = zero();
        for (RawLot lot : state.rawLots()) {
            rawRemaining = rawRemaining.add(lot.remainingCost());
            rawTotal = rawTotal.add(lot.totalCost());
        }
        if (rawTotal.subtract(rawRemaining).compareTo(rawConsumed) != 0) {
            return "U1";
        }
        BigDecimal incurred = rawTotal.add(conversionPaid);
        BigDecimal closedOut = books.cogs().add(books.spoilageLoss()).add(books.inventoryCost());
        if (incurred.compareTo(closedOut) != 0) {
            return "C1";
        }
        // K1 现金守恒
        if (books.totalCash().compareTo(state.openingTotalCash()) != 0) {
            return "K1";
        }
        // R1 收入
        BigDecimal revenue = zero();
        for (Sale sale : state.sales()) {
            revenue = revenue.add(sale.revenue());
        }
        if (revenue.compareTo(books.revenue()) != 0) {
            return "R1";
        }
        // AR / CL / 退款应付（分渠道 gross）
        for (ChannelLedger ledger : state.channelLedgers()) {
            BigDecimal expectedAr = zero();
            BigDecimal expectedApplied = zero();
            BigDecimal expectedCogs = zero();
            for (Sale sale : state.sales()) {
                if (sale.channelId().equals(ledger.channelId())) {
                    expectedAr = expectedAr.add(sale.revenue()).subtract(sale.prepaidApplied());
                    expectedApplied = expectedApplied.add(sale.prepaidApplied());
                    expectedCogs = expectedCogs.add(sale.cogs());
                }
            }
            BigDecimal ar = expectedAr.subtract(ledger.collected());
            if (ar.compareTo(ledger.receivable()) != 0) {
                return "AR:" + ledger.channelId();
            }
            BigDecimal cl = ledger.prepaidReceived().subtract(ledger.prepaidExtraApplied());
            if (cl.compareTo(ledger.contractLiability()) != 0) {
                return "CL:" + ledger.channelId();
            }
            // 累计转退款 = 预收实收 − 已随售出抵扣 − 剩余合同负债；它必须严格等于
            // 累计已退 + 剩余应付。不得用“累计已退 vs 剩余应付”这种跨口径比较。
            BigDecimal appliedTotal = zero();
            for (Sale sale : state.sales()) {
                if (sale.channelId().equals(ledger.channelId())) {
                    appliedTotal = appliedTotal.add(sale.prepaidApplied());
                }
            }
            BigDecimal movedToRefund = ledger.prepaidReceived().subtract(appliedTotal)
                    .subtract(ledger.contractLiability());
            if (movedToRefund.signum() < 0) {
                return "CL:" + ledger.channelId();
            }
            if (ledger.refundPaid().add(ledger.refundPayable()).compareTo(movedToRefund) != 0) {
                return "REFUND:" + ledger.channelId();
            }
            if (expectedCogs.signum() < 0) {
                return "COGS:" + ledger.channelId();
            }
        }
        // 固定费
        if (state.fixedAccrued().compareTo(state.fixedPaid().add(state.fixedPayable())) != 0) {
            return "FIXED";
        }
        // 平衡表与负现金
        if (books.assets().subtract(books.liabilities()).compareTo(books.equity()) != 0) {
            return "BALANCE";
        }
        for (CashLedger.CashAccount account : accounts.values()) {
            if (account.cash().signum() < 0) {
                return "NEGATIVE_CASH:" + account.ownerId();
            }
        }
        if (books.inventoryCost().signum() < 0) {
            return "INVENTORY";
        }
        return null;
    }

    // ---------------------------------------------------------------- 内部工具

    private static BigDecimal accountsOpeningCompanyCash(State state,
                                                         Map<String, CashLedger.CashAccount> accounts) {
        // 期初公司现金 = 期初权益 − 期初存货成本；期初存货成本由开局原料 lot 反推不可靠，
        // 因此用当前现金 + 已流出 − 已流入的方式在本模型中不可得，改由 openingEquity 定义直接给出：
        // openingEquity = 期初公司现金 + 期初存货成本，而期初存货成本在本实现中等于
        // 期初原料 lot 的剩余成本（开局后未变），故这里用「当前原料 lot 总额 − 期间采购总额」不可行。
        // 简化且可验证的做法：期初存货成本 = openingEquity − 开局公司现金，开局公司现金在 open()
        // 时已等于 accounts 中公司账户余额，且开局后所有现金腿都改变余额，所以此处需要单独保存。
        // 见 open(): openingEquity 与 openingTotalCash 之外，期初公司现金存放在 State 里的
        // openingCompanyCash 字段（为保持最小状态，本方法改为读取该字段）。
        return state.openingCompanyCash();
    }

    private static String authorize(State state, String actor) {
        if (actor == null || !actor.equals(state.config().managerPersonId())) {
            return NOT_AUTHORIZED;
        }
        return null;
    }

    private static PurchaseOrder order(State state, String orderId) {
        for (PurchaseOrder order : state.orders()) {
            if (order.orderId().equals(orderId)) {
                return order;
            }
        }
        return null;
    }

    private static ChannelLedger ledger(State state, String channelId) {
        for (ChannelLedger ledger : state.channelLedgers()) {
            if (ledger.channelId().equals(channelId)) {
                return ledger;
            }
        }
        return ChannelLedger.zero(channelId);
    }

    private static boolean used(State state, String id) {
        if (id == null || id.isBlank()) {
            return true;
        }
        for (PurchaseOrder order : state.orders()) {
            if (order.orderId().equals(id)) {
                return true;
            }
        }
        for (RawLot lot : state.rawLots()) {
            if (lot.lotId().equals(id)) {
                return true;
            }
        }
        for (BatchLot batch : state.batches()) {
            if (batch.batchId().equals(id)) {
                return true;
            }
        }
        for (ShipmentLot lot : state.shipmentLots()) {
            if (lot.shipmentId().equals(id)) {
                return true;
            }
        }
        for (Sale sale : state.sales()) {
            if (sale.saleId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 调整单渠道账：应收/合同负债/退款应付的增量，以及累计流量增量。
     *
     * @param state        状态
     * @param channelId    渠道
     * @param arDelta      应收增量
     * @param clDelta      合同负债增量
     * @param refundDelta  退款应付增量
     * @param prepaidDelta 累计预收增量
     * @param collectedDelta 累计回款增量
     * @param appliedDelta 累计抵扣增量
     * @return 新的渠道账列表
     */
    private static List<ChannelLedger> adjustLedger(State state, String channelId,
                                                    BigDecimal arDelta, BigDecimal clDelta,
                                                    BigDecimal refundDelta,
                                                    BigDecimal prepaidDelta,
                                                    BigDecimal collectedDelta,
                                                    BigDecimal appliedDelta) {
        List<ChannelLedger> ledgers = new ArrayList<>(state.channelLedgers());
        for (int index = 0; index < ledgers.size(); index++) {
            ChannelLedger ledger = ledgers.get(index);
            if (!ledger.channelId().equals(channelId)) {
                continue;
            }
            ledgers.set(index, new ChannelLedger(channelId,
                    ledger.receivable().add(arDelta),
                    ledger.contractLiability().add(clDelta),
                    ledger.refundPayable().add(refundDelta),
                    ledger.prepaidReceived().add(prepaidDelta),
                    ledger.collected().add(collectedDelta),
                    ledger.refundPaid().add(refundDelta.signum() < 0 ? refundDelta.negate() : zero()),
                    ledger.prepaidExtraApplied().add(appliedDelta)));
            break;
        }
        return ledgers;
    }

    /**
     * 累计比例分摊：本次移出 = round(总额 × 累计比例) − 已分配；末次取精确余额。
     *
     * @param total           lot 总额
     * @param totalUnits      lot 总单位
     * @param consumedBefore  本次之前的累计消耗
     * @param take            本次消耗
     * @param allocated       已分配金额
     * @return 本次应移出金额（scale2）
     */
    static BigDecimal allocate(BigDecimal total, long totalUnits, long consumedBefore, long take,
                               BigDecimal allocated) {
        if (take <= 0 || totalUnits <= 0) {
            return zero();
        }
        long after = consumedBefore + take;
        if (after >= totalUnits) {
            return money(total.subtract(allocated));
        }
        BigDecimal target = total.multiply(BigDecimal.valueOf(after))
                .divide(BigDecimal.valueOf(totalUnits), 2, RoundingMode.HALF_UP);
        return money(target.subtract(allocated));
    }

    private static BigDecimal prepayOf(long units, BigDecimal price, int prepayBp) {
        return money(BigDecimal.valueOf(units).multiply(price)
                .multiply(BigDecimal.valueOf(prepayBp))
                .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP));
    }

    private static long spoilUnits(long units, int spoilageBp) {
        return BigDecimal.valueOf(units).multiply(BigDecimal.valueOf(spoilageBp))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.FLOOR).longValueExact();
    }

    private static Map<String, CashLedger.CashAccount> transfer(
            Map<String, CashLedger.CashAccount> accounts, String actorOwnerId, String fromAccountId,
            String toAccountId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return null;
        }
        CashLedger.TransferOutcome outcome = CashLedger.computeTransfer(accounts, actorOwnerId,
                fromAccountId, toAccountId, amount);
        return outcome.accepted() ? outcome.accounts() : null;
    }

    private static Outcome accepted(State state, Map<String, CashLedger.CashAccount> accounts,
                                    String action, String refId) {
        if (accounts == null) {
            return new Outcome(true, ACCEPTED, state, null);
        }
        String violation = validate(state, accounts);
        if (violation != null) {
            return new Outcome(false, IDENTITY_BROKEN + violation, state, accounts);
        }
        return new Outcome(true, ACCEPTED, state, accounts);
    }

    /** 接受拷贝器构造的候选：先落地为不可变状态，再做同一套不变量校验。 */
    private static Outcome accepted(Copy copy, Map<String, CashLedger.CashAccount> accounts,
                                    String action, String refId) {
        return accepted(copy.build(), accounts, action, refId);
    }

    /** 按累计消耗位置计算本次结转成本：C(before+take) − C(before)，末次自动取精确余额。 */
    private static BigDecimal costIncrement(ShipmentLot lot, long consumedBefore, long take) {
        BigDecimal before = allocate(lot.totalCost(), lot.units(), 0, consumedBefore, zero());
        BigDecimal after = allocate(lot.totalCost(), lot.units(), 0, consumedBefore + take, zero());
        return money(after.subtract(before));
    }

    private static Outcome rejected(State state, Map<String, CashLedger.CashAccount> accounts,
                                    String reason) {
        return new Outcome(false, reason, state, accounts);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("金额不能为空");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }

    /** 便捷拷贝构造（记录不可变，逐字段替换）。 */
    private static Copy copy(State state) {
        return new Copy(state);
    }

    /** 逐字段拷贝器，避免长参数构造调用。 */
    private static final class Copy {
        private final State origin;
        private long closedPeriods;
        private List<PurchaseOrder> orders;
        private List<RawLot> rawLots;
        private List<BatchLot> batches;
        private List<ShipmentLot> shipmentLots;
        private List<Sale> sales;
        private List<Spoilage> spoilage;
        private List<ChannelLedger> ledgers;
        private Map<String, BigDecimal> prices;
        private BigDecimal accrued;
        private BigDecimal paid;
        private BigDecimal payable;
        private List<PeriodClose> closes;
        private List<Report> reports;

        private Copy(State origin) {
            this.origin = origin;
            this.closedPeriods = origin.closedPeriods();
            this.orders = origin.orders();
            this.rawLots = origin.rawLots();
            this.batches = origin.batches();
            this.shipmentLots = origin.shipmentLots();
            this.sales = origin.sales();
            this.spoilage = origin.spoilage();
            this.ledgers = origin.channelLedgers();
            this.prices = origin.currentPrices();
            this.accrued = origin.fixedAccrued();
            this.paid = origin.fixedPaid();
            this.payable = origin.fixedPayable();
            this.closes = origin.closes();
            this.reports = origin.reports();
        }

        private Copy orders(List<PurchaseOrder> value) {
            this.orders = value;
            return this;
        }

        private Copy rawLots(List<RawLot> value) {
            this.rawLots = value;
            return this;
        }

        private Copy batches(List<BatchLot> value) {
            this.batches = value;
            return this;
        }

        private Copy shipmentLots(List<ShipmentLot> value) {
            this.shipmentLots = value;
            return this;
        }

        private Copy sales(List<Sale> value) {
            this.sales = value;
            return this;
        }

        private Copy spoilage(List<Spoilage> value) {
            this.spoilage = value;
            return this;
        }

        private Copy channelLedgers(List<ChannelLedger> value) {
            this.ledgers = value;
            return this;
        }

        private Copy currentPrices(Map<String, BigDecimal> value) {
            this.prices = value;
            return this;
        }

        private Copy fixed(BigDecimal accruedValue, BigDecimal paidValue, BigDecimal payableValue) {
            this.accrued = accruedValue;
            this.paid = paidValue;
            this.payable = payableValue;
            return this;
        }

        private Copy closes(List<PeriodClose> value) {
            this.closes = value;
            return this;
        }

        private State report(long period, String action, String actor, String refId) {
            List<Report> next = new ArrayList<>(reports);
            next.add(new Report(next.size() + 1L, period, action, actor, ACCEPTED, refId));
            this.reports = next;
            return build();
        }

        private Copy closedPeriods(long value) {
            this.closedPeriods = value;
            return this;
        }

        private State build() {
            return new State(origin.config(), origin.openingCompanyCash(),
                    origin.openingInventoryCost(), origin.openingEquity(),
                    origin.openingTotalCash(), closedPeriods, orders, rawLots, batches,
                    shipmentLots, sales, spoilage, ledgers, prices, accrued, paid, payable, closes,
                    reports);
        }
    }
}
