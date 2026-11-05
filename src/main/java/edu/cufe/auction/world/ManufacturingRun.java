package edu.cufe.auction.world;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 制造经营子集：唯一世界日志里的不可变运行包装。
 *
 * <p>本类型只承载三样东西：证券代码、纯核 {@link ManufacturingModel.State} 台账，以及按 taskId
 * 索引的管理审计报告。现金余额仍然唯一保存在 {@link CashLedger} 的账户表里，这里<b>不</b>保存
 * 第二份现金、余额子集或按市值派生的权益；报表一律由台账加同批账户表现算（见
 * {@link ManufacturingModel#report}）。</p>
 *
 * <p>报告是<b>管理审计</b>：记录某个待办在哪个提交位置、以什么结果执行、动用了哪个关联业务标识。
 * 它不是已投递的角色消息，也不表示任何角色授权已经实现。报告一经提交不可删改，同一 taskId
 * 只有一条。</p>
 *
 * @param symbol  本档案唯一绑定的证券代码（公司股票）
 * @param ledger  纯核制造台账（不可变）
 * @param reports 管理审计报告，按 taskId 索引
 */
public record ManufacturingRun(String symbol, ManufacturingModel.State ledger,
                               Map<String, ReportEntry> reports) {

    /** 校验非空与集合深不可变。 */
    public ManufacturingRun {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("制造子集的证券代码不能为空");
        }
        Objects.requireNonNull(ledger, "制造台账");
        reports = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(reports, "制造报告")));
        for (Map.Entry<String, ReportEntry> entry : reports.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().taskId())) {
                throw new IllegalArgumentException("制造报告索引键与任务标识不一致："
                        + entry.getKey() + " / " + entry.getValue().taskId());
            }
        }
    }

    /**
     * 开局配置：把纯核账户绑定与证券代码固定到新档案。
     *
     * <p>这是显式构造参数而不是默认推断：有 manufacturing 模块就必须给出配置，没有模块就不得携带。
     * 配置绑进第一批开局内容，因此换一套配置重开同一日志会在写盘前被拒绝。</p>
     *
     * @param accounting 纯核开局账户绑定（公司所有者、经营账户与三个对手账户）
     * @param symbol     公司证券代码，必须与公司财产主体所属组织一致
     */
    public record Config(ManufacturingModel.Opening accounting, String symbol) {

        /** 校验绑定与证券代码。 */
        public Config {
            Objects.requireNonNull(accounting, "经营账户绑定");
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("证券代码不能为空");
            }
        }
    }

    /**
     * 单条管理审计报告。
     *
     * <p>{@code commandId} 是外部原始命令；内部自动子任务保留父动作的命令关联。系统自有动作
     * （如强制关期）不伪造外部命令，使用 {@code system:} 前缀的稳定标识，因此系统结果与外部
     * 原 command 可以清楚区分。</p>
     *
     * @param taskId     产生该结果的待办（已登记到世界已用任务表）
     * @param commandId  外部原始命令；系统动作为 {@code system:} 前缀
     * @param kind       待办种类
     * @param accepted   业务是否成功
     * @param reason     结果代码
     * @param businessId 关联业务标识（采购单/批次/合同/应收/费用账单）；无关联时为空串
     * @param amount     本次动作实际转移的现金，精确到分；没有现金动作时为零
     * @param timeMillis 执行时点（取待办到期时间）
     * @param position   结果所在的提交位置
     */
    public record ReportEntry(String taskId, String commandId, String kind, boolean accepted,
                              String reason, String businessId, BigDecimal amount,
                              long timeMillis, long position) {

        /** 校验标识、金额与位置。 */
        public ReportEntry {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("报告任务标识不能为空");
            }
            if (commandId == null || commandId.isBlank()) {
                throw new IllegalArgumentException("报告命令标识不能为空");
            }
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("报告任务种类不能为空");
            }
            if (businessId == null) {
                businessId = "";
            }
            amount = CashLedger.money(Objects.requireNonNull(amount, "报告金额"));
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("报告金额不能为负：" + amount);
            }
            if (timeMillis < 0 || position <= 0) {
                throw new IllegalArgumentException("报告时点或提交位置非法：" + timeMillis
                        + " / " + position);
            }
        }
    }

    /** @return 该待办的管理报告；不存在返回 null */
    public ReportEntry report(String taskId) {
        return reports.get(taskId);
    }

    /**
     * 追加一条报告，不改动已有条目。
     *
     * @param entry 新报告
     * @return 追加后的新子集
     * @throws IllegalArgumentException 同一 taskId 已有不同报告
     */
    public ManufacturingRun withReport(ReportEntry entry) {
        Objects.requireNonNull(entry, "报告");
        ReportEntry existing = reports.get(entry.taskId());
        if (existing != null) {
            if (!existing.equals(entry)) {
                throw new IllegalArgumentException("同一任务只能有一条管理报告：" + entry.taskId());
            }
            return this;
        }
        Map<String, ReportEntry> next = new LinkedHashMap<>(reports);
        next.put(entry.taskId(), entry);
        return new ManufacturingRun(symbol, ledger, next);
    }

    /**
     * 用同符号的新台账替换当前台账。
     *
     * @param next 候选台账
     * @return 替换后的新子集
     */
    public ManufacturingRun withLedger(ManufacturingModel.State next) {
        Objects.requireNonNull(next, "候选台账");
        return new ManufacturingRun(symbol, next, reports);
    }
}
