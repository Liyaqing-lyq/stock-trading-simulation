package edu.cufe.auction.world.pharmaaudit;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.web.WorldWebScenario;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 独审自建的驱动与夹具：不调用作者测试里的辅助方法，直接用公开入口构造真实董事会任免/预算
 * 与真实模型回执（确定性注入，不访问外部模型）。
 */
final class PharmaAuditHarness {

    static final String PHARMA = "pharma";
    static final String CASH = "RND-PHARMA";
    static final long DAY = 86_400_000L;
    static final ObjectMapper JSON = new ObjectMapper();

    private PharmaAuditHarness() {
    }

    /** 打开一次真实决定会话并取到待回答的调用。 */
    static DecisionRun.Call open(WorldRuntime world, String person, String id) throws Exception {
        world.beginDecisionSession("audit-session-" + id);
        world.openDecisions("audit-open-" + id, List.of(new DecisionRun.Request(person, id, "wake",
                new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20,
                        1_000_000, 1_024, 1))));
        var dispatch = world.dispatchLiveDecision("audit-dispatch-" + id, id);
        if (dispatch.call() == null) {
            throw new IllegalStateException("没有待回答调用：" + id + " " + dispatch);
        }
        return dispatch.call();
    }

    /** 注入一次确定性回执并返回动作到期时刻（+1s 便于推进）。 */
    static long reply(WorldRuntime world, DecisionRun.Call call, String tool,
            Map<String, ?> args) throws Exception {
        String body = JSON.writeValueAsString(Map.of("tool", tool, "args", args));
        var response = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                call.attemptNo(), call.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, 60, 20), "scripted:audit");
        if (!"QUEUED".equals(world.offerDecisionCallback(response))) {
            throw new IllegalStateException("回执未入队：" + tool);
        }
        world.drainDecisionCallbacks();
        var action = world.snapshot().decisions().orElseThrow().actionRef(call.actionId());
        if (action == null) {
            throw new IllegalStateException("回执没有产生动作：" + tool);
        }
        return action.dueMillis() + 1_000L;
    }

    /**
     * 回执 + 推进，返回完成帧的真实结果（可能为空：动作在受理阶段就被拒绝时不会有完成帧）。
     * 期望由调用方声明，避免把不同结果码混为一谈。
     */
    static String tool(WorldRuntime world, String person, String id, String tool,
            Map<String, ?> args) throws Exception {
        long due = reply(world, open(world, person, id), tool, args);
        world.advanceTo(due);
        String result = world.taskResultForTest(DecisionRun.actionTaskId(id) + ":finish");
        return result == null ? "" : result;
    }

    /**
     * 真实董事预算（10,000 / PROCUREMENT）+ 真实经理任免（90 天，须回避）；
     * 全部走公开排程入口，读完两份公开材料后开会。
     */
    static void approveBudgetAndAppoint(WorldRuntime world, String manager) throws Exception {
        world.schedulePublishDocument("audit-pub-budget", "audit-pub-budget-task", 10L,
                "audit-budget-doc", WorldWebScenario.INVESTOR,
                edu.cufe.auction.world.InformationModel.Audience.publicAudience(),
                "预算材料", "研发预算实验材料", "研发预算实验材料", 10L, List.of());
        world.schedulePublishDocument("audit-pub-appt", "audit-pub-appt-task", 10L,
                "audit-appt-doc", WorldWebScenario.INVESTOR,
                edu.cufe.auction.world.InformationModel.Audience.publicAudience(),
                "任免材料", "研发任免实验材料", "研发任免实验材料", 10L, List.of());
        List<String> voters = List.of("frank", "grace", "heidi", "ivan");
        List<String> readers = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(
                List.of("frank", "grace", "heidi", "ivan", "judy", manager)));
        int index = 0;
        for (String reader : readers) {
            world.scheduleStartReading("aud-b-" + reader, "aud-b-" + reader + "-task",
                    20L + index * 2L, reader, "audit-budget-doc", 1L);
            world.scheduleStartReading("aud-a-" + reader, "aud-a-" + reader + "-task",
                    60L + index * 2L, reader, "audit-appt-doc", 1L);
            index++;
        }
        world.scheduleCompanyBoardConvene("aud-budget-c", "aud-budget-c-task", 100L, PHARMA,
                "frank", "audit-budget-meeting", 150L, 200L, 4_000L, false);
        for (int i = 0; i < voters.size(); i++) {
            world.scheduleCompanyBoardAttend("aud-budget-a-" + i, "aud-budget-a-" + i + "-task",
                    110L + i, PHARMA, voters.get(i), "audit-budget-meeting");
        }
        world.scheduleCompanyBoardMaterial("aud-budget-m", "aud-budget-m-task", 130L, PHARMA,
                "frank", "audit-budget-meeting", "m1", "audit-budget-doc", 1L);
        world.scheduleCompanyBoardBudgetProposal("aud-budget-p", "aud-budget-p-task", 140L, PHARMA,
                "frank", "audit-budget-meeting", List.of(new BoardModel.BudgetLine(0,
                        BoardModel.BudgetCategory.PROCUREMENT, new BigDecimal("10000.00"))));
        for (int i = 0; i < voters.size(); i++) {
            world.scheduleCompanyBoardVote("aud-budget-v-" + i, "aud-budget-v-" + i + "-task",
                    3_000L + i, PHARMA, voters.get(i), "audit-budget-meeting",
                    BoardModel.Vote.YES, 1_000L);
        }
        world.scheduleCompanyBoardImplement("aud-budget-i", "aud-budget-i-task", 5_000L, PHARMA,
                "frank", "audit-budget-meeting");

        world.scheduleCompanyBoardConvene("aud-appt-c", "aud-appt-c-task", 6_000L, PHARMA,
                "frank", "audit-appt-meeting", 6_500L, 7_000L, 10_000L, true);
        for (int i = 0; i < voters.size(); i++) {
            world.scheduleCompanyBoardAttend("aud-appt-a-" + i, "aud-appt-a-" + i + "-task",
                    6_100L + i, PHARMA, voters.get(i), "audit-appt-meeting",
                    manager.equals(voters.get(i)));
        }
        world.scheduleCompanyBoardMaterial("aud-appt-m", "aud-appt-m-task", 6_300L, PHARMA,
                "frank", "audit-appt-meeting", "m1", "audit-appt-doc", 1L);
        world.scheduleCompanyBoardAppointmentProposal("aud-appt-p", "aud-appt-p-task", 6_400L,
                PHARMA, "frank", "audit-appt-meeting", new BoardModel.AppointmentRequest(
                        "mgr-" + manager, manager, PHARMA, RoleAccess.Role.COMPANY_MANAGER,
                        BoardModel.AppointmentAction.APPOINT, 12_000L, 90L * DAY));
        for (int i = 0; i < voters.size(); i++) {
            if (manager.equals(voters.get(i))) {
                continue;
            }
            world.scheduleCompanyBoardVote("aud-appt-v-" + i, "aud-appt-v-" + i + "-task",
                    9_000L + i, PHARMA, voters.get(i), "audit-appt-meeting",
                    BoardModel.Vote.YES, 1_000L);
        }
        world.scheduleCompanyBoardImplement("aud-appt-i", "aud-appt-i-task", 11_000L, PHARMA,
                "frank", "audit-appt-meeting");
        world.advanceTo(12_000L);
    }
}
