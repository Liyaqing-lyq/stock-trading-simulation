package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * 资料阅读子集：唯一世界日志里的不可变运行包装。
 *
 * <p>本类型只承载两样东西：纯核 {@link InformationModel.State} 资料台账，以及按 taskId 索引的
 * 管理审计报告。<b>它不保存第二份现金、账户余额或角色记忆</b>；正文、固定版本、读者经历都由
 * 纯核按冻结内容维护，运行层只负责在到期时点提交一次。</p>
 *
 * <p>{@link Config} 冻结首批登记读者（人物标识清单）；人物在世界开局一次登记，本单元不提供
 * 运行中新增人物的入口。配置随第一批开局内容持久化，换一套可运行配置重开同一日志会被拒绝。</p>
 *
 * @param information 纯核信息状态（不可变）
 * @param reports     管理审计报告，按 taskId 索引
 */
public record InformationRun(InformationModel.State information, Map<String, Report> reports) {

    /** 校验非空与报告索引一致性。 */
    public InformationRun {
        Objects.requireNonNull(information, "信息状态");
        Objects.requireNonNull(reports, "阅读报告");
        Map<String, Report> copy = new LinkedHashMap<>(reports);
        for (Map.Entry<String, Report> entry : copy.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().taskId())) {
                throw new IllegalArgumentException("阅读报告索引键与任务标识不一致："
                        + entry.getKey() + " / " + entry.getValue().taskId());
            }
        }
        reports = Collections.unmodifiableMap(copy);
    }

    /**
     * 开局配置：首批登记读者与可选的回忆参数。
     *
     * <p>{@code recallPolicy} 只在 {@code cognition-recall-experiment-v1} 档案出现；为空时该键不落
     * JSON，因此旧档案的序列化字节与旧构造完全不变。参数随第一批开局内容冻结，换任何参数重开同一
     * 日志都会被拒绝。</p>
     *
     * @param readers      读者人物标识清单；允许为空表，但每个标识必须是已登记 PERSON
     * @param recallPolicy 冻结的回忆实验参数；旧档案为 null
     */
    public record Config(List<String> readers,
                         @JsonInclude(JsonInclude.Include.NON_NULL)
                         CognitionRecall.Policy recallPolicy,
                         @JsonInclude(JsonInclude.Include.NON_NULL)
                         BeliefModel.Policy beliefPolicy) {

        /** 深拷贝、去空白并排序。 */
        public Config {
            List<String> list = new ArrayList<>(Objects.requireNonNull(readers, "登记读者清单"));
            Set<String> distinct = new LinkedHashSet<>();
            for (String reader : list) {
                if (reader == null || reader.isBlank()) {
                    throw new IllegalArgumentException("登记读者不能为空");
                }
                if (!distinct.add(reader)) {
                    throw new IllegalArgumentException("登记读者重复：" + reader);
                }
            }
            List<String> ordered = new ArrayList<>(distinct);
            ordered.sort(Comparator.naturalOrder());
            readers = List.copyOf(ordered);
        }

        /**
         * 旧一参构造：没有回忆参数，保持 schema1..10 的开局内容字节与源码兼容。
         *
         * @param readers 读者人物标识清单
         */
        public Config(List<String> readers) {
            this(readers, null, null);
        }

        /**
         * 二参构造：只有回忆参数，保持 schema11 的开局内容字节与源码兼容。
         *
         * @param readers      读者人物标识清单
         * @param recallPolicy 回忆参数；旧档案为 null
         */
        public Config(List<String> readers, CognitionRecall.Policy recallPolicy) {
            this(readers, recallPolicy, null);
        }
    }

    /**
     * 单条管理审计报告。
     *
     * <p>报告是管理端审计，不是已投递的角色消息。{@code subjectId} 是该动作关联的资料或
     * 阅读标识；{@code readerPartyId} 是阅读相关动作的读者，非阅读动作为空串。</p>
     *
     * @param taskId        产生该结果的待办
     * @param commandId     外部原始命令；系统自动完成任务使用 {@code system:} 前缀
     * @param kind          待办种类
     * @param accepted      业务是否成功
     * @param reason        结果代码
     * @param subjectId     关联资料/阅读标识；无关联时为空串
     * @param readerPartyId 关联读者；无关联时为空串
     * @param timeMillis    执行时点（取待办到期时间）
     * @param position      结果所在提交位置
     */
    public record Report(String taskId, String commandId, String kind, boolean accepted,
                         String reason, String subjectId, String readerPartyId,
                         long timeMillis, long position) {

        /** 校验字段。 */
        public Report {
            requireText(taskId, "报告任务标识");
            requireText(commandId, "报告命令标识");
            requireText(kind, "报告任务种类");
            requireText(reason, "报告结果代码");
            if (subjectId == null) {
                subjectId = "";
            }
            if (readerPartyId == null) {
                readerPartyId = "";
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
    public InformationRun withReport(Report entry) {
        Objects.requireNonNull(entry, "阅读报告");
        Report existing = reports.get(entry.taskId());
        if (existing != null) {
            if (!existing.equals(entry)) {
                throw new IllegalArgumentException("同一任务只能有一条管理报告：" + entry.taskId());
            }
            return this;
        }
        Map<String, Report> next = new LinkedHashMap<>(reports);
        next.put(entry.taskId(), entry);
        return new InformationRun(information, next);
    }

    /**
     * 用新信息状态替换当前状态。
     *
     * @param next 候选信息状态
     * @return 替换后的新子集
     */
    public InformationRun withInformation(InformationModel.State next) {
        return new InformationRun(Objects.requireNonNull(next, "候选信息状态"), reports);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
