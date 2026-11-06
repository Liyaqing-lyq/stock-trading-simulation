package edu.cufe.auction.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 任职与授权子集：唯一世界日志里的不可变运行包装。
 *
 * <p>本类型只承载两样东西：纯核 {@link RoleAccess.State} 授权元数据，以及按 taskId 索引的
 * 管理审计报告。<b>它不保存任何现金、持仓、日志或角色记忆</b>；角色能看到的资产一律由
 * {@link WorldRuntime#roleView(String)} 从同一快照的现金账户与证券批次现算。</p>
 *
 * <p>{@link Config} 是显式开局配置：产品管理授权与首批任职随运行清单一起冻结进日志第一批，
 * 换一套配置重开同一日志会在恢复期被拒绝。首批任职不得携带撤销记录——撤销只能由运行中的
 * 受信排程写入历史，不能预造。</p>
 *
 * @param access  纯核授权状态（不可变）
 * @param reports 管理审计报告，按 taskId 索引
 */
public record RoleRun(RoleAccess.State access, Map<String, Report> reports) {

    /** 校验非空与报告索引一致性。 */
    public RoleRun {
        Objects.requireNonNull(access, "任职授权状态");
        Objects.requireNonNull(reports, "任职报告");
        Map<String, Report> copy = new LinkedHashMap<>(reports);
        for (Map.Entry<String, Report> entry : copy.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().taskId())) {
                throw new IllegalArgumentException("任职报告索引键与任务标识不一致："
                        + entry.getKey() + " / " + entry.getValue().taskId());
            }
        }
        reports = Collections.unmodifiableMap(copy);
    }

    /**
     * 开局配置：显式产品管理授权与初始任职。
     *
     * <p>两者都随第一批开局内容持久化。初始任职是<b>历史前缀</b>：运行中只能追加新任职，
     * 或给已有任职记录一次合法撤销时刻，不能换人物、组织、岗位、原期限或删除。</p>
     *
     * @param mandates     产品管理授权；可为空表，但每个账户必须显式列出
     * @param appointments 初始任职；不得携带撤销记录
     */
    public record Config(List<RoleAccess.ProductMandate> mandates,
                         List<RoleAccess.Appointment> appointments) {

        /** 深拷贝、排序并拒绝带撤销记录的初始任职。 */
        public Config {
            List<RoleAccess.ProductMandate> mandateList = new ArrayList<>(
                    Objects.requireNonNull(mandates, "产品管理授权"));
            mandateList.sort(Comparator.comparing(RoleAccess.ProductMandate::productOwnerId));
            List<RoleAccess.Appointment> appointmentList = new ArrayList<>(
                    Objects.requireNonNull(appointments, "初始任职"));
            appointmentList.sort(Comparator.comparing(RoleAccess.Appointment::id));
            Set<String> ids = new LinkedHashSet<>();
            for (RoleAccess.Appointment appointment : appointmentList) {
                if (appointment.revokedAt() != null) {
                    throw new IllegalArgumentException("初始任职不得预造撤销记录："
                            + appointment.id());
                }
                if (!ids.add(appointment.id())) {
                    throw new IllegalArgumentException("初始任职标识重复：" + appointment.id());
                }
            }
            mandates = List.copyOf(mandateList);
            appointments = List.copyOf(appointmentList);
        }
    }

    /**
     * 单条管理审计报告。
     *
     * <p>报告是管理端审计，不是已投递的角色消息，也不表示任何角色授权被执行。
     * {@code subjectId} 是该动作关联的任职标识。</p>
     *
     * @param taskId     产生该结果的待办
     * @param commandId  外部原始命令
     * @param kind       待办种类
     * @param accepted   业务是否成功
     * @param reason     结果代码
     * @param subjectId  关联任职标识；无关联时为空串
     * @param timeMillis 执行时点（取待办到期时间）
     * @param position   结果所在提交位置
     */
    public record Report(String taskId, String commandId, String kind, boolean accepted,
                         String reason, String subjectId, long timeMillis, long position) {

        /** 校验字段。 */
        public Report {
            requireText(taskId, "报告任务标识");
            requireText(commandId, "报告命令标识");
            requireText(kind, "报告任务种类");
            requireText(reason, "报告结果代码");
            if (subjectId == null) {
                subjectId = "";
            }
            if (timeMillis < 0 || position <= 0) {
                throw new IllegalArgumentException("报告时点或提交位置非法：" + timeMillis
                        + " / " + position);
            }
        }
    }

    /**
     * 取某待办的管理报告。
     *
     * @param taskId 任务标识
     * @return 报告；不存在返回 null
     */
    public Report report(String taskId) {
        return reports.get(taskId);
    }

    /**
     * 追加一条报告，不改动已有条目。
     *
     * @param entry 新报告
     * @return 追加后的新子集
     * @throws IllegalArgumentException 同一 taskId 已有不同报告
     */
    public RoleRun withReport(Report entry) {
        Objects.requireNonNull(entry, "任职报告");
        Report existing = reports.get(entry.taskId());
        if (existing != null) {
            if (!existing.equals(entry)) {
                throw new IllegalArgumentException("同一任务只能有一条管理报告：" + entry.taskId());
            }
            return this;
        }
        Map<String, Report> next = new LinkedHashMap<>(reports);
        next.put(entry.taskId(), entry);
        return new RoleRun(access, next);
    }

    /**
     * 用新授权状态替换当前状态。
     *
     * @param next 候选授权状态
     * @return 替换后的新子集
     */
    public RoleRun withAccess(RoleAccess.State next) {
        return new RoleRun(Objects.requireNonNull(next, "候选授权状态"), reports);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
