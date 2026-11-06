package edu.cufe.auction.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 四岗交易子集：唯一世界日志里的不可变运行包装。
 *
 * <p>本类型只承载两样东西：纯核 {@link RoleTradingModel.State} 与按任务标识索引的管理回报。
 * <b>不保存任何现金、持仓、成交、订单号或角色记忆</b>；额度、成交与冻结一律由同批的
 * {@link WorldExchange.State} 和 {@link SecuritiesLedger.State} 现算。</p>
 *
 * <p>回报里的 {@code clientCommandId}/{@code clientTaskId} 是人物自己的命名空间标识，
 * 内部标识（含人物前缀的编码）只出现在 {@code taskId} 字段，供管理端审计与恢复；
 * 人物视图只投影自己的 client 标识。</p>
 *
 * @param trading 纯核工作流（不可变）
 * @param reports 管理回报，按内部任务标识索引
 */
public record RoleTradingRun(RoleTradingModel.State trading, Map<String, Report> reports) {

    /** 校验非空与索引一致性。 */
    public RoleTradingRun {
        Objects.requireNonNull(trading, "交易工作流");
        Objects.requireNonNull(reports, "交易回报");
        Map<String, Report> copy = new LinkedHashMap<>(reports);
        for (Map.Entry<String, Report> entry : copy.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().taskId())) {
                throw new IllegalArgumentException("交易回报索引键与任务标识不一致："
                        + entry.getKey() + " / " + entry.getValue().taskId());
            }
        }
        reports = Collections.unmodifiableMap(copy);
    }

    /** @return 空工作流、无回报的开局子集 */
    public static RoleTradingRun opening() {
        return new RoleTradingRun(RoleTradingModel.open(), Map.of());
    }

    /**
     * 单条交易动作回报。
     *
     * @param taskId          内部任务标识（含人物前缀）
     * @param personPartyId   发起人物
     * @param clientCommandId 人物自己的命令标识
     * @param clientTaskId    人物自己的任务标识
     * @param kind            待办种类
     * @param accepted        业务是否受理
     * @param reason          结果代码
     * @param subjectId       关联业务对象（目标/子单/资料）；无关联为空串
     * @param timeMillis      执行时点（待办到期时间）
     * @param position        结果所在提交位置
     */
    public record Report(String taskId, String personPartyId, String clientCommandId,
                         String clientTaskId, String kind, boolean accepted, String reason,
                         String subjectId, long timeMillis, long position) {

        /** 校验字段。 */
        public Report {
            requireText(taskId, "回报任务标识");
            requireText(personPartyId, "回报人物");
            requireText(clientCommandId, "回报命令标识");
            requireText(clientTaskId, "回报任务标识");
            requireText(kind, "回报种类");
            requireText(reason, "回报结果代码");
            if (subjectId == null) {
                subjectId = "";
            }
            if (timeMillis < 0 || position <= 0) {
                throw new IllegalArgumentException("回报时点或提交位置非法：" + timeMillis
                        + " / " + position);
            }
        }
    }

    /**
     * 取某待办的管理回报。
     *
     * @param taskId 内部任务标识
     * @return 回报；不存在返回 null
     */
    public Report report(String taskId) {
        return reports.get(taskId);
    }

    /**
     * 某人物自己的回报，按提交位置排序。
     *
     * @param personPartyId 人物
     * @return 不可变列表
     */
    public List<Report> reportsOf(String personPartyId) {
        List<Report> found = new ArrayList<>();
        for (Report report : reports.values()) {
            if (report.personPartyId().equals(personPartyId)) {
                found.add(report);
            }
        }
        found.sort((left, right) -> Long.compare(left.position(), right.position()));
        return List.copyOf(found);
    }

    /**
     * 追加一条回报；同任务同内容幂等，不同内容拒绝。
     *
     * @param entry 新回报
     * @return 追加后的子集
     */
    public RoleTradingRun withReport(Report entry) {
        Objects.requireNonNull(entry, "交易回报");
        Report existing = reports.get(entry.taskId());
        if (existing != null) {
            if (!existing.equals(entry)) {
                throw new IllegalArgumentException("同一任务只能有一条交易回报：" + entry.taskId());
            }
            return this;
        }
        Map<String, Report> next = new LinkedHashMap<>(reports);
        next.put(entry.taskId(), entry);
        return new RoleTradingRun(trading, next);
    }

    /**
     * 用候选工作流替换当前状态。
     *
     * @param next 候选工作流
     * @return 替换后的子集
     */
    public RoleTradingRun withTrading(RoleTradingModel.State next) {
        return new RoleTradingRun(Objects.requireNonNull(next, "候选交易工作流"), reports);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
