package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** v3本人动作反馈：只沿已绑定动作及精确命令读取权威回报，不从待办消失推断成功。 */
public final class RoleActionFeedback {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RoleActionFeedback() { }
    private record Result(String taskId, String kind, boolean accepted, String reason, long time, long position) { }

    /**
     * 校正已按本人过滤的历史行，并补充实际董事程序合同；不修改世界或业务回报。
     * @param root 已投影的工程输入 @param person 本人 @param run 决定台账 @param pending 待办
     * @param engineering 工程权威回报 @param operations 经营权威回报
     * @param information 阅读权威回报 @param trading 本人岗位动作回报
     */
    public static void append(ObjectNode root, String person, DecisionRun run, List<PendingTask> pending,
                              EngineeringRun engineering, CompanyOperationsRun operations,
                              InformationRun information, RoleTradingRun trading) {
        String procedure = "只有chair=true的主席可召集。召集成功只创建会议，不会自动到会、生成提案或添加材料；等待也不会生成它们。到会须在opensAt之前完成；添加材料、提出任命或预算提案须先到会且未回避，普通董事也可操作，不是主席专权。材料和提案须在CONVENED状态且不晚于materialDeadline完成；任命与预算是分开议题。只有已到会、未回避且读完实际材料的合格席位可在投票窗口独立投票；PASSED还须另行implement。会议时间须容纳思考、到会、阅读、提案和表决工时。候选人、金额、选票和时间仍由本人独立判断。";
        for (var director : root.path("directorships")) {
            ((ObjectNode) director).put("procedure", procedure);
            ((ObjectNode) director).put("conveneRight", director.path("chair").asBoolean() ? "CHAIR" : "NO_CONVENE_RIGHT");
            for (var meeting : director.path("meetings")) ((ObjectNode) meeting).put("procedure", procedure);
        }
        if (root.path("decisionGuide") instanceof ObjectNode guide) {
            guide.put("decisionCadence", "本人可见新事实、本人动作完成或声明的nextReviewAt到期会重新评估；每次一个动作。没有固定每天一次限制。严格格式失败耗尽重试后，本人相同事实暂停调用，真实新事实可重新唤醒；其他人继续运行。");
            guide.put("waitContract", "等待输出{tool:role.wait,decision:{objective:本人目标id,basis:条件或缺项,nextReviewAt:未来毫秒}}，不得带args。必须遵守deliberation.replyContract；等待不会执行任何后继动作。");
        }
        if (run == null) return;
        var delib = root.path("deliberation");
        for (var row : delib.path("recentDecisions")) project((ObjectNode) row, person, run, pending, engineering, operations, information, trading);
        if (delib.path("lastActionResult") instanceof ObjectNode last)
            project(last, person, run, pending, engineering, operations, information, trading);
    }

    private static void project(ObjectNode row, String person, DecisionRun run, List<PendingTask> pending,
                                EngineeringRun engineering, CompanyOperationsRun operations,
                                InformationRun information, RoleTradingRun trading) {
        String action = row.path("actionId").asText();
        var decision = run.state().decisions().stream().filter(d -> d.actionId().equals(action)
                && d.personPartyId().equals(person)).findFirst().orElse(null);
        if (decision == null) return;
        var attempt = decision.attempts().get(decision.attempts().size()-1);
        row.put("attemptId", attempt.attemptId());
        String command = DecisionRun.actionCommandId(action);
        row.put("commandId", command);
        var ref = run.actionRef(action);
        if (ref == null) {
            if (row.has("businessReject")) row.put("execution", "REJECTED").put("accepted", false);
            return;
        }
        row.put("boundTaskId", ref.taskId());
        // 必须覆盖旧v2推断；找不到终态证据时明确未知。
        row.put("execution", "UNKNOWN");
        var reports = new ArrayList<Result>();
        if (engineering != null) for (var r : engineering.reports().values())
            if (r.commandId().equals(command) && (r.taskId().equals(ref.taskId()) || r.taskId().equals(ref.taskId()+":finish")))
                reports.add(new Result(r.taskId(), r.kind(), r.accepted(), r.reason(), r.timeMillis(), r.position()));
        if (operations != null) for (var r : operations.reports().values())
            if (r.commandId().equals(command) && (r.taskId().equals(ref.taskId()) || r.taskId().equals(ref.taskId()+":finish")))
                reports.add(new Result(r.taskId(), r.action(), r.accepted(), r.reason(), r.timeMillis(), r.position()));
        if (trading != null) for (var r : trading.reports().values())
            if (r.taskId().equals(ref.taskId()) && r.personPartyId().equals(person) && r.clientCommandId().equals(action) && r.clientTaskId().equals(action))
                reports.add(new Result(r.taskId(), r.kind(), r.accepted(), r.reason(), r.timeMillis(), r.position()));
        // 阅读完成帧有独立readId，沿原命令且限定读者匹配，不能借同资料的另一轮阅读报喜。
        if (information != null) for (var r : information.reports().values())
            if (r.commandId().equals(command) && r.readerPartyId().equals(person))
                reports.add(new Result(r.taskId(), r.kind(), r.accepted(), r.reason(), r.timeMillis(), r.position()));
        reports.sort(Comparator.comparingLong(Result::position));
        Result latest = reports.isEmpty() ? null : reports.get(reports.size()-1);
        boolean originalPending = pending.stream().anyMatch(p -> p.taskId().equals(ref.taskId()));
        boolean childPending = pending.stream().anyMatch(p -> !p.taskId().equals(ref.taskId()) && command.equals(commandOf(p)));
        if (latest != null) {
            row.put("resultTaskId", latest.taskId()).put("resultKind", latest.kind())
                    .put("resultAt", latest.time()).put("resultPosition", latest.position()).put("reason", latest.reason());
            if (!latest.accepted()) row.put("execution", "REJECTED").put("accepted", false).put("businessReject", latest.reason());
            else if ("STARTED".equals(latest.reason())) row.put("execution", childPending ? "IN_PROGRESS" : "UNKNOWN");
            else row.put("execution", "APPLIED").put("accepted", true);
        } else if (originalPending) row.put("execution", "PENDING");
    }

    private static String commandOf(PendingTask task) {
        try {
            JsonNode payload = JSON.readTree(task.payload());
            if (payload.path("commandId").isTextual()) return payload.path("commandId").asText();
            if (payload.path("payload").isTextual()) return JSON.readTree(payload.path("payload").asText()).path("commandId").asText();
        } catch (java.io.IOException ignored) { /* 不推测无法绑定的子任务。 */ }
        return "";
    }
}
