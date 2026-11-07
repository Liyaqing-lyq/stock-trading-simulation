package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立验收：M4i.2a 最终 DecisionRun 修订的三组真实边界。
 *
 * <p>1) 已完成采访的历史在记者离任后恢复时不得按当前权限倒改；2) 观察帧无权但后获权不得追认，
 * 观察合法但完成前离任不得产生新采访，控制动作仍可释放；3) 在途动作不得重叠，已完成邀请
 * 仍 PROPOSED 时允许非重叠第二条，实测记录时间与待办变化。只使用公开 API 与脚本回执。</p>
 */
class InterviewDecisionBoundaryIndependentTest {

    private static final String DOC = WorldWebScenario.INTERVIEW_CONTACT_DOC;
    private static final String WAKE = "wake";
    private static final DecisionModel.Route ROUTE = new DecisionModel.Route(
            "media", "key-1", "deepseek-flash", 1L, 4, 100_000, 2_000, 2);

    private static void publishAndRead(WorldRuntime runtime) throws Exception {
        long now = runtime.snapshot().timeMillis();
        long publish = now + 1_000L;
        runtime.schedulePublishDocument("cmd-contact", "task-contact", publish, DOC,
                "investor", InformationModel.Audience.publicAudience(), "公开联系说明",
                "模拟：工作时段可通过公开投关热线预约采访", "公司公开资料", 1_000L, List.of());
        runtime.advanceTo(publish);
        long start = publish + 500L;
        runtime.openRoleSession("rita").startReading("cmd-read", "task-read", start, DOC, 1L);
        runtime.advanceTo(start);
        runtime.advanceTo(start + 1_000L);
    }

    private static void decide(WorldRuntime runtime, String command, String person,
                               String actionId, String body) throws Exception {
        runtime.beginDecisionSession("session-" + command);
        runtime.openDecisions(command, List.of(
                new DecisionRun.Request(person, actionId, WAKE, ROUTE)));
        WorldRuntime.DispatchResult dispatched = runtime.dispatchLiveDecision(
                "dispatch-" + command, actionId);
        assertNotNull(dispatched.call(), "派发失败：" + dispatched.verdict()
                + "/" + dispatched.reason());
        DecisionRun.Call call = dispatched.call();
        DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, 900, 200), "test:scripted");
        assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                runtime.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
        assertTrue(runtime.drainDecisionCallbacks() >= 1);
    }

    private static String inviteBody(String clientId, long cutoff) {
        return "{\"tool\":\"interview.invite\",\"args\":{\"requestClientId\":\"" + clientId
                + "\",\"toPartyId\":\"investor\",\"topic\":\"边界采访\",\"windowStartMillis\":"
                + (cutoff + 120_000L) + ",\"windowEndMillis\":" + (cutoff + 3_000_000L)
                + ",\"durationMillis\":60000}}";
    }

    private static String respondBody(String requestId, boolean accept, long start) {
        return "{\"tool\":\"interview.respond\",\"args\":{\"requestId\":\"" + requestId
                + "\",\"accept\":" + accept + ",\"startMillis\":" + start + "}}";
    }

    private static String endBody(String requestId) {
        return "{\"tool\":\"interview.end\",\"args\":{\"requestId\":\"" + requestId + "\"}}";
    }

    private static long dueOf(WorldRuntime runtime, String actionId) {
        return DecisionModel.actionDueMillis(
                runtime.snapshot().decisions().orElseThrow().state(), actionId);
    }

    private static boolean hasInterviewCommandPending(WorldRuntime runtime) {
        return runtime.snapshot().pending().stream().anyMatch(task ->
                InterviewRun.COMMAND_KINDS.contains(task.kind()));
    }

    /** 记者离任：撤销快讯社 MEDIA-0（rita 的记者任职）。 */
    private static void resignRita(WorldRuntime runtime, String command, long due)
            throws Exception {
        runtime.scheduleRevokeAppointment(command, "task-" + command, due, "MEDIA-0");
        runtime.advanceTo(due);
    }

    /** 边界 1：合法完成的采访 + 后续在途邀请，记者离任后重开仍恢复历史，不按当前权限倒改。 */
    @Test
    void completedHistorySurvivesResignationAndReopen(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("history.journal");
        String endedRequest;
        try (WorldRuntime runtime = WorldWebScenario.openInterview(journal).runtime()) {
            publishAndRead(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            decide(runtime, "invite1", "rita", "a-invite1", inviteBody("req-h1", cutoff));
            runtime.advanceTo(dueOf(runtime, "a-invite1"));
            endedRequest = runtime.interviewAudit().invites().get(0).requestId();
            assertEquals("PROPOSED", runtime.interviewAudit().invites().get(0).status());

            long acceptAt = runtime.snapshot().timeMillis();
            long start = acceptAt + 200_000L;
            decide(runtime, "respond1", "investor", "a-respond1",
                    respondBody(endedRequest, true, start));
            runtime.advanceTo(dueOf(runtime, "a-respond1"));
            assertEquals("ACCEPTED", runtime.interviewAudit().invites().get(0).status());
            runtime.advanceTo(start);
            assertEquals("ACTIVE", runtime.interviewAudit().invites().get(0).status());
            decide(runtime, "end1", "investor", "a-end1", endBody(endedRequest));
            runtime.advanceTo(dueOf(runtime, "a-end1"));
            assertEquals("ENDED_EARLY", runtime.interviewAudit().invites().get(0).status());

            // 后续另有在途待办：第二条邀请已完成（PROPOSED），其门任务仍在排队
            long cutoff2 = runtime.snapshot().timeMillis();
            decide(runtime, "invite2", "rita", "a-invite2", inviteBody("req-h2", cutoff2));
            runtime.advanceTo(dueOf(runtime, "a-invite2"));
            assertEquals(2, runtime.interviewAudit().invites().size());
            assertEquals("PROPOSED", runtime.interviewAudit().invites().get(1).status());
            assertFalse(runtime.snapshot().pending().isEmpty(), "后续门任务应在排队");

            resignRita(runtime, "revoke-rita", runtime.snapshot().timeMillis() + 500L);
            assertEquals(2, runtime.interviewAudit().invites().size(), "离任不得改写历史邀请");
        }

        // 重开：从同一日志恢复，逐字核对历史，不因今天权限重判
        try (WorldRuntime reopened = WorldWebScenario.openInterview(journal).runtime()) {
            var audit = reopened.interviewAudit();
            assertEquals(2, audit.invites().size());
            assertEquals("ENDED_EARLY", audit.invites().get(0).status());
            assertEquals(endedRequest, audit.invites().get(0).requestId());
            assertEquals("PROPOSED", audit.invites().get(1).status());
            assertEquals(1, audit.sessions().size(), "唯一会话历史必须保留");
            long sessionWorks = audit.works().stream()
                    .filter(work -> work.kind().equals(MediaRun.KIND_INTERVIEW_SESSION))
                    .count();
            assertEquals(1L, sessionWorks, "会话工时历史只结算一次且可恢复");
            assertFalse(audit.invites().get(0).status().equals("CANCELED"),
                    "离任后恢复不得把已完成采访改成取消");
        }
    }

    /** 边界 2a：观察与计划合法，完成前离任 → 不产生新的授权采访，协调结果记拒绝。 */
    @Test
    void resignationBeforeCompletionBlocksNewInvite(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(
                dir.resolve("resign.journal")).runtime()) {
            publishAndRead(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            decide(runtime, "invite", "rita", "a-invite", inviteBody("req-r1", cutoff));
            long inviteDue = dueOf(runtime, "a-invite");
            assertEquals(0, runtime.interviewAudit().invites().size(), "完成前不应已有邀请");

            resignRita(runtime, "revoke-before", cutoff + 500L);
            assertEquals(0, runtime.interviewAudit().invites().size());
            runtime.advanceTo(inviteDue);

            var audit = runtime.interviewAudit();
            assertEquals(0, audit.invites().size(), "离任后完成不得产生新的授权采访");
            assertFalse(hasInterviewCommandPending(runtime), "被拒动作不得留在待办");
            var coordination = runtime.interviewRun().coordinations().values().stream()
                    .filter(item -> item.kind().equals(PendingTask.INTERVIEW_INVITE_V1))
                    .findFirst().orElseThrow();
            assertFalse(coordination.accepted(), "协调结果必须是拒绝");
            assertEquals(InterviewModel.REASON_NOT_AUTHORIZED, coordination.reason());
        }
    }

    /** 边界 2b：观察帧无权但回执前获权不得追认；拒绝/结束控制仍可无现职释放。 */
    @Test
    void unauthorizedObservationCannotGainAuthorityLater(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(
                dir.resolve("observe.journal")).runtime()) {
            publishAndRead(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            resignRita(runtime, "revoke-1", cutoff + 500L);
            long after = runtime.snapshot().timeMillis();

            // 观察帧：rita 已无任职，工具清单里不得出现采访邀请
            runtime.beginDecisionSession("session-late");
            runtime.openDecisions("late", List.of(
                    new DecisionRun.Request("rita", "a-late", WAKE, ROUTE)));
            DecisionModel.Decision observed = DecisionModel.decision(
                    runtime.snapshot().decisions().orElseThrow().state(), "a-late").orElseThrow();
            assertFalse(observed.allowedTools().contains(RoleTradingView.TOOL_INTERVIEW_INVITE),
                    "观察帧无任职时不得给出采访工具：" + observed.allowedTools());

            // 重新任职后（完成帧已获权）再提交同一观察帧的回执
            long reappoint = after + 300L;
            long end = 30L * 86_400_000L;
            runtime.scheduleAppointment("cmd-reappoint", "task-reappoint", reappoint,
                    "MEDIA-R2", "rita", "wire", RoleAccess.Role.JOURNALIST, reappoint, end);
            runtime.advanceTo(reappoint);

            WorldRuntime.DispatchResult again = runtime.dispatchLiveDecision("dispatch-late",
                    "a-late");
            DecisionRun.Call real = again.call() == null ? null : again.call();
            assertNotNull(real, "派发失败：" + again.verdict() + "/" + again.reason());
            DecisionModel.Reply reply = new DecisionModel.Reply(real.runId(), real.actionId(),
                    real.attemptId(), real.attemptNo(), real.recoveryGeneration(),
                    inviteBody("req-late", after + 600_000L),
                    DecisionModel.Usage.unknownCost(1, 900, 200), "test:scripted");
            assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                    runtime.offerLiveDecisionCompletion(
                            LiveCompletionInbox.Completion.business(reply)));
            runtime.drainDecisionCallbacks();

            assertEquals(0, runtime.interviewAudit().invites().size(),
                    "观察帧无权的回执不得因完成帧获权而被追认");
            assertFalse(hasInterviewCommandPending(runtime), "不得排出采访待办");

            // 反证：完成帧已获权时，新的观察帧才给出邀请工具 → 拒绝确由观察帧权限决定
            runtime.beginDecisionSession("session-fresh");
            runtime.openDecisions("fresh", List.of(
                    new DecisionRun.Request("rita", "a-fresh", WAKE, ROUTE)));
            DecisionModel.Decision fresh = DecisionModel.decision(
                    runtime.snapshot().decisions().orElseThrow().state(), "a-fresh").orElseThrow();
            assertTrue(fresh.allowedTools().contains(RoleTradingView.TOOL_INTERVIEW_INVITE),
                    "复职后的新观察帧应给出邀请工具：" + fresh.allowedTools());
        }
    }

    /** 边界 2c：邀请已 PROPOSED 或已 ACTIVE 时记者离任，拒绝与结束仍可释放资源。 */
    @Test
    void controlsReleaseWithoutCurrentOffice(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(
                dir.resolve("release.journal")).runtime()) {
            publishAndRead(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            decide(runtime, "invite", "rita", "a-invite", inviteBody("req-rel", cutoff));
            runtime.advanceTo(dueOf(runtime, "a-invite"));
            String requestId = runtime.interviewAudit().invites().get(0).requestId();

            long acceptAt = runtime.snapshot().timeMillis();
            long start = acceptAt + 300_000L;
            decide(runtime, "respond", "investor", "a-respond",
                    respondBody(requestId, true, start));
            runtime.advanceTo(dueOf(runtime, "a-respond"));
            assertEquals("ACCEPTED", runtime.interviewAudit().invites().get(0).status());

            resignRita(runtime, "revoke-active", runtime.snapshot().timeMillis() + 500L);
            assertEquals("ACCEPTED", runtime.interviewAudit().invites().get(0).status(),
                    "离任不得自动改写已接受预约");

            // 到点开始门：离任后不得假造 ACTIVE 谈话
            runtime.advanceTo(start);
            assertEquals("ACCEPTED", runtime.interviewAudit().invites().get(0).status(),
                    "离任后到点开始门必须拒绝真正开始，实际："
                            + runtime.interviewAudit().invites().get(0).status());
            assertEquals(0, runtime.interviewAudit().sessions().size(), "不得产生会话");
            runtime.advanceTo(start + 1L);
            assertEquals("MISSED", runtime.interviewAudit().invites().get(0).status(),
                    "错过开始时点必须落到确定终态，实际："
                            + runtime.interviewAudit().invites().get(0).status());
        }
    }

    /** 边界 2c：邀请已 PROPOSED 时记者离任，被邀请方拒绝仍可释放。 */
    @Test
    void declineStillReleasesAfterResignation(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(
                dir.resolve("decline.journal")).runtime()) {
            publishAndRead(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            decide(runtime, "invite", "rita", "a-invite", inviteBody("req-dec", cutoff));
            runtime.advanceTo(dueOf(runtime, "a-invite"));
            String requestId = runtime.interviewAudit().invites().get(0).requestId();
            assertEquals("PROPOSED", runtime.interviewAudit().invites().get(0).status());

            resignRita(runtime, "revoke-proposed", runtime.snapshot().timeMillis() + 500L);
            decide(runtime, "decline", "investor", "a-decline",
                    respondBody(requestId, false, -1L));
            runtime.advanceTo(dueOf(runtime, "a-decline"));
            assertEquals("DECLINED", runtime.interviewAudit().invites().get(0).status(),
                    "离任不得阻止被邀请方拒绝并释放");
            assertFalse(hasInterviewCommandPending(runtime), "拒绝后不得残留采访待办");
        }
    }

    /** 边界 2d：会话已 ACTIVE 后记者离任，结束仍可释放并只结算一次实际区间。 */
    @Test
    void endStillReleasesActiveSessionAfterResignation(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(
                dir.resolve("end.journal")).runtime()) {
            publishAndRead(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            decide(runtime, "invite", "rita", "a-invite", inviteBody("req-end", cutoff));
            runtime.advanceTo(dueOf(runtime, "a-invite"));
            String requestId = runtime.interviewAudit().invites().get(0).requestId();

            long acceptAt = runtime.snapshot().timeMillis();
            long start = acceptAt + 300_000L;
            decide(runtime, "respond", "investor", "a-respond",
                    respondBody(requestId, true, start));
            runtime.advanceTo(dueOf(runtime, "a-respond"));
            runtime.advanceTo(start);
            assertEquals("ACTIVE", runtime.interviewAudit().invites().get(0).status());

            resignRita(runtime, "revoke-active", runtime.snapshot().timeMillis() + 500L);
            assertEquals("ACTIVE", runtime.interviewAudit().invites().get(0).status(),
                    "离任不得自动改写已开始会话");

            decide(runtime, "end", "investor", "a-end", endBody(requestId));
            runtime.advanceTo(dueOf(runtime, "a-end"));
            var audit = runtime.interviewAudit();
            assertEquals("ENDED_EARLY", audit.invites().get(0).status(),
                    "离任后结束必须仍可释放，实际：" + audit.invites().get(0).status());
            long sessions = audit.works().stream()
                    .filter(work -> work.kind().equals(MediaRun.KIND_INTERVIEW_SESSION))
                    .count();
            assertEquals(1L, sessions, "只结算一次实际会话区间");
            assertTrue(audit.budgets().stream().allMatch(budget -> budget.reservedMillis() == 0L),
                    "结束后不得残留工时预留");
        }
    }

    /** 边界 3：在途动作不可重叠；已完成邀请仍 PROPOSED 时允许非重叠第二条，时间与待办如实变化。 */
    @Test
    void overlapRejectedAndSequentialAllowedWithRecordedTiming(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(
                dir.resolve("overlap.journal")).runtime()) {
            publishAndRead(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            decide(runtime, "inviteA", "rita", "a-inviteA", inviteBody("req-o1", cutoff));
            assertTrue(hasInterviewCommandPending(runtime), "第一条采访动作在途");

            long timeBefore = runtime.snapshot().timeMillis();
            int pendingBefore = runtime.snapshot().pending().size();
            int usedBefore = runtime.snapshot().usedTaskIds().size();
            decide(runtime, "inviteB", "rita", "a-inviteB", inviteBody("req-o2", cutoff));
            assertEquals(timeBefore, runtime.snapshot().timeMillis(), "重叠被拒不得推进时间");
            assertEquals(pendingBefore, runtime.snapshot().pending().size(),
                    "重叠被拒不得新增待办");
            assertEquals(usedBefore, runtime.snapshot().usedTaskIds().size(),
                    "重叠被拒不得占用新任务标识");
            assertEquals(0, runtime.interviewAudit().invites().size());

            // 第一条完成（PROPOSED）后，非重叠的第二条必须允许
            long firstDue = dueOf(runtime, "a-inviteA");
            runtime.advanceTo(firstDue);
            assertEquals(1, runtime.interviewAudit().invites().size());
            assertEquals("PROPOSED", runtime.interviewAudit().invites().get(0).status());
            long timeAfterFirst = runtime.snapshot().timeMillis();
            int pendingAfterFirst = runtime.snapshot().pending().size();
            assertTrue(timeAfterFirst > timeBefore, "第一条完成必须真实推进时间");

            long cutoff2 = runtime.snapshot().timeMillis();
            decide(runtime, "inviteC", "rita", "a-inviteC", inviteBody("req-o3", cutoff2));
            assertTrue(runtime.snapshot().pending().size() > pendingAfterFirst,
                    "第二条非重叠邀请必须排出新待办");
            runtime.advanceTo(dueOf(runtime, "a-inviteC"));
            var audit = runtime.interviewAudit();
            assertEquals(2, audit.invites().size(), "第二条必须实际落账");
            assertEquals("PROPOSED", audit.invites().get(0).status(),
                    "第一条仍 PROPOSED，不得被改写或被代答");
            assertEquals("PROPOSED", audit.invites().get(1).status());
            assertEquals(0, audit.sessions().size(), "任何人不得替对方接受邀请");
        }
    }
}
