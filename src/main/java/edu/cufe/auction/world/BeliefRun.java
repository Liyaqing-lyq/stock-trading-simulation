package edu.cufe.auction.world;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * M4g.2 信念子集：纯核判断状态、可信结构化声明表、事实捕获进度与管理审计报告。
 *
 * <p><b>不复制</b>资金、证券、整份 {@link InformationModel} 或角色视图；金额与持仓仍从原世界账派生。
 * 声明表是可信适配层输入：本类只保存“谁在何时发布了什么”，不证明账本数值。</p>
 *
 * <p><b>捕获与发布分离</b>：{@link CapturedFact} 记录在 {@code asOf} 的 OBSERVATION 阶段冻结的公司合同数量与
 * 提交位置；下一毫秒的 DELIVERY 阶段只读取这份冻结值发布，<b>不再回读当前台账</b>。</p>
 *
 * @param beliefs     纯核不可变状态
 * @param sourceClaims 结构化来源预测
 * @param factClaims  程序派生事实声明
 * @param captured    已捕获待发布的事实
 * @param reports     按 taskId 索引的认知动作报告
 */
public record BeliefRun(BeliefModel.State beliefs,
                        List<BeliefModel.SourceClaim> sourceClaims,
                        List<BeliefModel.FactClaim> factClaims,
                        List<CapturedFact> captured,
                        Map<String, Report> reports) {

    /** 空子集：新档案开局使用。 */
    public static BeliefRun empty() {
        return new BeliefRun(new BeliefModel.State(List.of(), List.of(), List.of(), 1),
                List.of(), List.of(), List.of(), Map.of());
    }

    /** 深拷贝并校验标识唯一与索引一致。 */
    public BeliefRun {
        Objects.requireNonNull(beliefs, "信念状态");
        sourceClaims = List.copyOf(Objects.requireNonNull(sourceClaims, "来源主张"));
        factClaims = List.copyOf(Objects.requireNonNull(factClaims, "事实声明"));
        captured = List.copyOf(Objects.requireNonNull(captured, "已捕获事实"));
        Map<String, Report> copy = new LinkedHashMap<>(Objects.requireNonNull(reports, "认知报告"));
        for (Map.Entry<String, Report> entry : copy.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().taskId())) {
                throw new IllegalArgumentException("认知报告索引键与任务标识不一致："
                        + entry.getKey() + " / " + entry.getValue().taskId());
            }
        }
        reports = Collections.unmodifiableMap(copy);
    }

    /**
     * 一条已捕获、待发布的事实。
     *
     * @param factTaskId        捕获任务标识（同时是发布任务的稳定子任务标识来源）
     * @param publisherPartyId  核验发布者（当时章程主席）
     * @param companyId         公司主体
     * @param metric            指标
     * @param asOfMillis        事实时点
     * @param capturedPosition  捕获批次的已确认提交位置
     * @param value             该时点已确认合同数量
     * @param factId            发布后的事实标识
     * @param docId             发布文档标识
     */
    public record CapturedFact(String factTaskId, String publisherPartyId, String companyId,
                               String metric, long asOfMillis, long capturedPosition, long value,
                               String factId, String docId) {

        /** 校验字段。 */
        public CapturedFact {
            requireText(factTaskId, "捕获任务");
            requireText(publisherPartyId, "核验发布者");
            requireText(companyId, "事实公司");
            requireText(metric, "事实指标");
            requireText(factId, "事实标识");
            requireText(docId, "事实文档");
            if (asOfMillis <= 0 || capturedPosition <= 0 || value < 0) {
                throw new IllegalArgumentException("捕获时点/位置/数量非法：" + asOfMillis + " / "
                        + capturedPosition + " / " + value);
            }
        }
    }

    /**
     * 一次认知动作的管理报告（与阅读报告同构，不是投递给角色的消息）。
     *
     * @param taskId          产生该结果的待办
     * @param personPartyId   发起人物
     * @param clientCommandId 外部原始命令
     * @param kind            动作种类
     * @param accepted        是否受理
     * @param reason          结果代码
     * @param subjectId       关联对象（判断/主张/事实）
     * @param timeMillis      执行时点
     * @param position        结果所在提交位置
     */
    public record Report(String taskId, String personPartyId, String clientCommandId, String kind,
                         boolean accepted, String reason, String subjectId, long timeMillis,
                         long position) {

        /** 校验字段。 */
        public Report {
            requireText(taskId, "报告任务");
            requireText(personPartyId, "报告人物");
            requireText(clientCommandId, "报告命令");
            requireText(kind, "报告种类");
            requireText(reason, "报告结果代码");
            if (subjectId == null) {
                subjectId = "";
            }
            if (timeMillis < 0 || position <= 0) {
                throw new IllegalArgumentException("报告时点或位置非法：" + timeMillis + " / "
                        + position);
            }
        }
    }

    // ------------------------------------------------------------------ 查询

    /** @param claimId 来源主张 @return 主张；不存在返回 null */
    public BeliefModel.SourceClaim sourceClaim(String claimId) {
        for (BeliefModel.SourceClaim claim : sourceClaims) {
            if (claim.claimId().equals(claimId)) {
                return claim;
            }
        }
        return null;
    }

    /** @param docId 文档 @param version 版本 @return 该版本唯一的声明（Source 或 Fact）；不存在返回 null */
    public Object claimOn(String docId, long version) {
        for (BeliefModel.SourceClaim claim : sourceClaims) {
            if (claim.docId().equals(docId) && claim.version() == version) {
                return claim;
            }
        }
        for (BeliefModel.FactClaim fact : factClaims) {
            if (fact.docId().equals(docId) && fact.version() == version) {
                return fact;
            }
        }
        return null;
    }

    /** @return 供纯核使用的声明表快照 */
    public BeliefModel.Claims claims() {
        return new BeliefModel.Claims(sourceClaims, factClaims);
    }

    /** @param taskId 任务 @return 报告；不存在返回 null */
    public Report report(String taskId) {
        return reports.get(taskId);
    }

    /** @param factTaskId 捕获任务 @return 已捕获事实；不存在返回 null */
    public CapturedFact captured(String factTaskId) {
        for (CapturedFact fact : captured) {
            if (fact.factTaskId().equals(factTaskId)) {
                return fact;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 写入

    /** @param next 新纯核状态 @return 替换后的子集（声明与捕获不变） */
    public BeliefRun withBeliefs(BeliefModel.State next) {
        return new BeliefRun(next, sourceClaims, factClaims, captured, reports);
    }

    /** @param claim 新来源主张 @return 追加后的子集 */
    public BeliefRun withSourceClaim(BeliefModel.SourceClaim claim) {
        Objects.requireNonNull(claim, "来源主张");
        if (sourceClaim(claim.claimId()) != null) {
            throw new IllegalArgumentException("来源主张标识重复：" + claim.claimId());
        }
        if (claimOn(claim.docId(), claim.version()) != null) {
            throw new IllegalArgumentException("同一版本只能有一条声明：" + claim.docId());
        }
        List<BeliefModel.SourceClaim> next = new ArrayList<>(sourceClaims);
        next.add(claim);
        return new BeliefRun(beliefs, next, factClaims, captured, reports);
    }

    /** @param fact 新事实声明 @return 追加后的子集 */
    public BeliefRun withFactClaim(BeliefModel.FactClaim fact) {
        Objects.requireNonNull(fact, "事实声明");
        for (BeliefModel.FactClaim existing : factClaims) {
            if (existing.factId().equals(fact.factId())) {
                throw new IllegalArgumentException("事实标识重复：" + fact.factId());
            }
        }
        if (claimOn(fact.docId(), fact.version()) != null) {
            throw new IllegalArgumentException("同一版本只能有一条声明：" + fact.docId());
        }
        for (BeliefModel.FactClaim existing : factClaims) {
            if (existing.companyId().equals(fact.companyId())
                    && existing.metric().equals(fact.metric())
                    && existing.asOfMillis() == fact.asOfMillis()
                    && existing.verifierPartyId().equals(fact.verifierPartyId())
                    && fact.supersedesFactId() == null) {
                throw new IllegalArgumentException("同一公司/指标/时点/核验者只允许一个初始事实："
                        + fact.factId());
            }
        }
        List<BeliefModel.FactClaim> next = new ArrayList<>(factClaims);
        next.add(fact);
        return new BeliefRun(beliefs, sourceClaims, next, captured, reports);
    }

    /** @param fact 已捕获事实 @return 追加后的子集 */
    public BeliefRun withCaptured(CapturedFact fact) {
        Objects.requireNonNull(fact, "已捕获事实");
        if (captured(fact.factTaskId()) != null) {
            throw new IllegalArgumentException("捕获任务重复：" + fact.factTaskId());
        }
        for (CapturedFact existing : captured) {
            if (existing.factId().equals(fact.factId())) {
                throw new IllegalArgumentException("事实标识重复：" + fact.factId());
            }
        }
        List<CapturedFact> next = new ArrayList<>(captured);
        next.add(fact);
        return new BeliefRun(beliefs, sourceClaims, factClaims, next, reports);
    }

    /** @param taskId 捕获任务 @return 移除该捕获后的子集（发布完成时使用） */
    public BeliefRun withoutCaptured(String taskId) {
        List<CapturedFact> next = new ArrayList<>();
        boolean removed = false;
        for (CapturedFact fact : captured) {
            if (fact.factTaskId().equals(taskId)) {
                removed = true;
            } else {
                next.add(fact);
            }
        }
        if (!removed) {
            return this;
        }
        return new BeliefRun(beliefs, sourceClaims, factClaims, next, reports);
    }

    /**
     * 追加一条报告；同一 taskId 同内容幂等，不同内容拒绝。
     *
     * @param entry 报告
     * @return 追加后的子集
     */
    public BeliefRun withReport(Report entry) {
        Objects.requireNonNull(entry, "认知报告");
        Report existing = reports.get(entry.taskId());
        if (existing != null) {
            if (!existing.equals(entry)) {
                throw new IllegalArgumentException("同一任务只能有一条认知报告：" + entry.taskId());
            }
            return this;
        }
        Map<String, Report> next = new TreeMap<>(reports);
        next.put(entry.taskId(), entry);
        return new BeliefRun(beliefs, sourceClaims, factClaims, captured, next);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
