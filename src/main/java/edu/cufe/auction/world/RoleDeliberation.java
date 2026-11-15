package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 显式角色目标、本人执行反馈与简短决定依据；不选择候选、选票、预算或订单。 */
public final class RoleDeliberation {
    /** 新决定外壳及输入的冻结标识。 */
    public static final String VERSION = "deliberation-v2";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MONTH = ManufacturingTemplateModel.PERIOD_MILLIS;
    private RoleDeliberation() { }

    /** @param version 角色输入版本 @return 是否具有原evidence-v1资料能力 */
    public static boolean hasEvidence(String version) { return "evidence-v1".equals(version) || VERSION.equals(version); }

    /**
     * 新世界一次冻结的有限运行预算；不重置旧运行的累计用量。
     * @param base 原有工具、人物与时延配置
     * @return 跨期运行预算配置（费用仍为未知）
     */
    public static DecisionModel.Config lifecycleBudget(DecisionModel.Config base) {
        var caps = new java.util.TreeMap<String, DecisionModel.Usage>();
        base.budget().personCap().keySet().forEach(person -> caps.put(person,
                DecisionModel.Usage.unknownCost(300, 3_000_000, 600_000)));
        return new DecisionModel.Config(base.providerKind(), base.providerId(), base.pricingVersion(),
                base.thinkMillisByWake(), base.actionMillisByTool(), base.limits(),
                new DecisionModel.Budget(DecisionModel.Usage.unknownCost(3000, 40_000_000, 6_000_000), caps), base.profile());
    }

    /**
     * 只使用已按本人权限投影的工程输入与本人的历史决定，追加可审计目标和结果。
     * @param root 已完成权限投影的工程输入
     * @param person 本人
     * @param now 当前时点
     * @param run 决定状态
     * @param pending 权威待办（只据本人动作ID判断是否完成，不输出他人的待办）
     */
    public static void append(ObjectNode root, String person, long now, DecisionRun run, List<PendingTask> pending) {
        var delib = root.putObject("deliberation");
        var objectives = delib.putArray("objectives");
        for (var director : root.path("directorships")) {
            String company = director.path("companyPartyId").asText();
            objectives.addObject().put("id", "director:" + company).put("companyPartyId", company)
                    .put("purpose", "独立评估本公司经营连续性、偿付能力与经理问责；及时形成可执行的批准、否决或补充材料决定，不能以股价代替经营质量。")
                    .put("tradeoff", "无人经营仍可能发生固定费用和折旧；任命不适任经理或超现金预算同样可能损害公司。候选自述未经业绩验证，允许有限任期试任、否决或要求补证，不保证成功。")
                    .put("source", "EXP章程董事职责；本人实际席位，不是自动授权或默认赞成票");
            for (var m : director.path("meetings")) {
                var meeting = (ObjectNode) m;
                long opens = meeting.path("opensAt").asLong(), closes = meeting.path("closesAt").asLong();
                meeting.put("window", now < opens ? "BEFORE_OPEN" : now < closes ? "VOTING" : "AFTER_CLOSE");
                meeting.put("attendanceDeadlineExclusive", opens);
                meeting.put("proposalPresent", meeting.has("appointmentRequest") || !meeting.path("proposedLines").isEmpty());
                meeting.put("procedure", "到会必须在opensAt之前完成；材料与提案须在materialDeadline之前完成。开会后不能补到会或补提案，只能由已到会且读完材料的合格席位独立表决。PASSED也须另行实施。召集/材料/提案/投票是不同动作。任命与预算必须分开议题；普通董事不能召集。安排动作须计入本次思考及工具耗时。");
            }
        }
        for (var manager : root.path("managerships")) {
            String company = manager.path("companyPartyId").asText();
            objectives.addObject().put("id", "manager:" + company).put("companyPartyId", company)
                    .put("purpose", "在实际授权、现金、供货和客户预算内安排履约，避免重复承诺；管理库存、交付、回款和跨期资金，按期报告亏损与风险。允许拒单、停产及请求调整授权。");
        }
        if (objectives.isEmpty()) objectives.addObject().put("id", "person:" + person)
                .put("purpose", "履行观察中实际任职，保护本人或受托财产；依据已知资料独立研究、管理风险或交易，没有岗位/证据时说明缺项，不自造权利。");
        delib.put("replyContract", "只输出一个对象：{tool,decision:{objective:上述id,basis:一句话依据或缺项,nextReviewAt:未来毫秒}}；非WAIT再含args。basis为可审计结论摘要，不要求完整推理。等待必须给具体条件/缺项与下次复查时间；遇本人可见新事件会提前唤醒。理由不是世界事实或授权。");
        delib.putObject("reviewBounds").put("afterExclusive", now).put("latestInclusive", Math.addExact(now, MONTH));
        var recent = delib.putArray("recentDecisions");
        if (run == null) return;
        var own = new ArrayList<>(run.state().decisions().stream().filter(d -> d.personPartyId().equals(person) && !d.attempts().isEmpty()).toList());
        Collections.reverse(own);
        boolean actionResult = false;
        for (var d : own) {
            var attempt = d.attempts().get(d.attempts().size() - 1);
            var row = JSON.createObjectNode().put("actionId", d.actionId()).put("observedAt", d.decisionAtMillis())
                    .put("outcome", d.outcome().name()).put("protocolReason", attempt.reason());
            var dispatch = run.dispatch(attempt.attemptId());
            if (dispatch != null && dispatch.businessReject() != null) row.put("businessReject", dispatch.businessReject());
            var ref = run.actionRef(d.actionId());
            if (ref != null) row.put("execution", pending.stream().anyMatch(p -> p.taskId().equals(ref.taskId()))
                    ? "PENDING" : row.has("businessReject") ? "REJECTED" : "FINISHED_SEE_CURRENT_STATE");
            try { var reply = JSON.readTree(attempt.replyBody()); if (reply != null) {
                row.put("tool", reply.path("tool").asText());
                if (reply.has("decision")) row.set("ownStatement", reply.get("decision"));
            }} catch (java.io.IOException ignored) { row.put("invalidReply", true); }
            if (recent.size() < 3) recent.add(row);
            if (!actionResult && d.outcome() != DecisionModel.Outcome.WAIT && d.outcome() != DecisionModel.Outcome.NONE) {
                delib.set("lastActionResult", row); actionResult = true;
            }
            if (recent.size() == 3 && actionResult) break;
        }
    }

    /**
     * 新版只验证决定声明合同并剥离元数据供原工具外壳验证；原回执仍原样持久保存。
     * @param inputText 冻结的程序输入，不能取资料正文作为版本开关
     * @param reply 原回执树
     * @return 原版返回原树，新版返回去掉decision的副本
     * @throws IllegalArgumentException 新版声明非法
     */
    public static JsonNode validatedAction(String inputText, JsonNode reply) {
        JsonNode input;
        try { input = JSON.readTree(inputText); } catch (java.io.IOException bad) { return reply; }
        if (input == null || !VERSION.equals(input.path("engineering").path("roleContextVersion").asText())) return reply;
        if (reply == null || !reply.isObject()) throw new IllegalArgumentException("BAD_DELIBERATION");
        var statement = reply.path("decision");
        Set<String> fields = new java.util.HashSet<>(); statement.fieldNames().forEachRemaining(fields::add);
        if (!statement.isObject() || !fields.equals(Set.of("objective", "basis", "nextReviewAt"))
                || !statement.path("objective").isTextual() || !statement.path("basis").isTextual()
                || statement.path("basis").asText().isBlank() || statement.path("basis").asText().length() > 600
                || !statement.path("nextReviewAt").isIntegralNumber() || !statement.path("nextReviewAt").canConvertToLong())
            throw new IllegalArgumentException("BAD_DELIBERATION");
        boolean own = false;
        for (var goal : input.path("engineering").path("deliberation").path("objectives"))
            if (goal.path("id").asText().equals(statement.path("objective").asText())) own = true;
        long now = input.path("time").asLong(), review = statement.path("nextReviewAt").asLong();
        if (!own || review <= now || review - now > MONTH) throw new IllegalArgumentException("BAD_DELIBERATION");
        var action = (ObjectNode) reply.deepCopy(); action.remove("decision"); return action;
    }

    /** @param run 决定状态 @param person 本人 @param fallback 旧版回访时间 @return 最新合法声明的复查时点 */
    public static long reviewAt(DecisionRun run, String person, long fallback) {
        if (run == null) return fallback;
        var list = run.state().decisions();
        for (int i = list.size() - 1; i >= 0; i--) {
            var d = list.get(i);
            if (!d.personPartyId().equals(person) || d.attempts().isEmpty()) continue;
            if (d.outcome() != DecisionModel.Outcome.WAIT && d.outcome() != DecisionModel.Outcome.INTENT) return fallback;
            try { var body = JSON.readTree(d.attempts().get(d.attempts().size()-1).replyBody());
                return body.path("decision").path("nextReviewAt").asLong(fallback);
            } catch (java.io.IOException bad) { return fallback; }
        }
        return fallback;
    }
}
