package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4i.2a 采访四工具经真实 DecisionRun 的回归：派发 → 回执 → 计划 → 到期 → 业务结算。
 *
 * <p>只使用公开 API 与 dummy 脚本回执，不联网、不读真实密钥；每个用例使用独立日志。</p>
 */
class InterviewDecisionToolChainTest {

    private static final String DOC = WorldWebScenario.INTERVIEW_CONTACT_DOC;
    private static final String WAKE = "wake";
    private static final DecisionModel.Route ROUTE = new DecisionModel.Route(
            "media", "key-1", "deepseek-flash", 1L, 4, 100_000, 2_000, 2);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 发布公开联系说明并让记者真实读完。 */
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

    /** 打开一个决定并按脚本回执结算。 */
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
        return inviteBody(clientId, cutoff, "investor");
    }

    private static String inviteBody(String clientId, long cutoff, String target) {
        return "{\"tool\":\"interview.invite\",\"args\":{"
                + "\"requestClientId\":\"" + clientId + "\",\"toPartyId\":\"" + target + "\","
                + "\"topic\":\"决定链采访\",\"windowStartMillis\":" + (cutoff + 120_000L)
                + ",\"windowEndMillis\":" + (cutoff + 3_000_000L)
                + ",\"durationMillis\":60000}}";
    }

    /** 发布一份来自 alice 的直投资料并让 rita 真正读完：由此 alice 成为可达目标。 */
    private static void makeAliceReachable(WorldRuntime runtime) throws Exception {
        long now = runtime.snapshot().timeMillis();
        long publish = now + 1_000L;
        String docId = "contact:alice-direct";
        runtime.schedulePublishDocument("cmd-alice-doc", "task-alice-doc", publish, docId,
                "alice", InformationModel.Audience.direct("rita"), "alice 的联系方式",
                "模拟：alice 直接把自己的联系方式发给 rita", "个人资料", 1_000L, List.of());
        runtime.advanceTo(publish);
        long start = publish + 500L;
        runtime.openRoleSession("rita").startReading("cmd-alice-read", "task-alice-read", start,
                docId, 1L);
        runtime.advanceTo(start);
        runtime.advanceTo(start + 1_000L);
    }

    /** 顺序邀请：A 已 PROPOSED（动作已完成）、时间不重叠，允许再向另一个可达目标发 B。 */
    @Test
    void sequentialInvitesToDifferentTargetsAllowed(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("seq.journal"))
                .runtime()) {
            publishAndRead(runtime);
            makeAliceReachable(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            decide(runtime, "inviteA", "rita", "a-inviteA", inviteBody("req-a", cutoff));
            runtime.advanceTo(DecisionModel.actionDueMillis(
                    runtime.snapshot().decisions().orElseThrow().state(), "a-inviteA"));
            assertEquals("PROPOSED", runtime.interviewAudit().invites().get(0).status());
            long cutoffB = runtime.snapshot().timeMillis();
            decide(runtime, "inviteB", "rita", "a-inviteB",
                    inviteBody("req-b", cutoffB, "alice"));
            runtime.advanceTo(DecisionModel.actionDueMillis(
                    runtime.snapshot().decisions().orElseThrow().state(), "a-inviteB"));
            var audit = runtime.interviewAudit();
            assertEquals(2, audit.invites().size(), "顺序的第二条邀请必须允许");
            assertTrue(audit.invites().stream().anyMatch(invite ->
                            invite.toPartyId().equals("investor")
                                    && invite.status().equals("PROPOSED")),
                    "第一条仍为 PROPOSED，不得被撤销或改写");
            assertTrue(audit.invites().stream().anyMatch(invite ->
                            invite.toPartyId().equals("alice")
                                    && invite.status().equals("PROPOSED")),
                    "第二条（不同可达目标）也必须 PROPOSED");
        }
    }

    /** 真实动作重叠：第一个采访动作还未到期完成时，第二条必须被拒绝且不排队。 */
    @Test
    void overlappingInFlightInterviewActionRejected(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("overlap.journal"))
                .runtime()) {
            publishAndRead(runtime);
            makeAliceReachable(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            decide(runtime, "inviteA", "rita", "a-inviteA", inviteBody("req-a", cutoff));
            assertTrue(runtime.snapshot().pending().stream()
                            .anyMatch(task -> task.kind().equals("INTERVIEW_INVITE_V1")),
                    "第一个采访动作仍在途");
            long before = runtime.snapshot().timeMillis();
            int pendingBefore = runtime.snapshot().pending().size();
            int invitesBefore = runtime.interviewAudit().invites().size();
            decide(runtime, "inviteB", "rita", "a-inviteB",
                    inviteBody("req-b", cutoff, "alice"));
            assertEquals(before, runtime.snapshot().timeMillis(), "拒绝不得推进模拟时间");
            assertEquals(pendingBefore, runtime.snapshot().pending().size(),
                    "重叠动作被拒后不得排出第二条任务");
            assertEquals(invitesBefore, runtime.interviewAudit().invites().size());
            assertTrue(runtime.snapshot().pending().stream()
                            .noneMatch(task -> task.taskId().contains("a-inviteB")),
                    "第二条任务不得存在");
        }
    }

    @Test
    void fourToolsThroughRealDecisionRun(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("chain.journal"))
                .runtime()) {
            publishAndRead(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            decide(runtime, "invite", "rita", "a-invite", inviteBody("req-a", cutoff));
            long due = DecisionModel.actionDueMillis(
                    runtime.snapshot().decisions().orElseThrow().state(), "a-invite");
            runtime.advanceTo(due);
            assertEquals(1, runtime.interviewAudit().invites().size());
            String requestId = runtime.interviewAudit().invites().get(0).requestId();
            assertEquals("PROPOSED", runtime.interviewAudit().invites().get(0).status());

            long respondCutoff = runtime.snapshot().timeMillis();
            long start = respondCutoff + 200_000L;
            String respondBody = "{\"tool\":\"interview.respond\",\"args\":{\"requestId\":\""
                    + requestId + "\",\"accept\":true,\"startMillis\":" + start + "}}";
            decide(runtime, "respond", "investor", "a-respond", respondBody);
            runtime.advanceTo(DecisionModel.actionDueMillis(
                    runtime.snapshot().decisions().orElseThrow().state(), "a-respond"));
            assertEquals("ACCEPTED", runtime.interviewAudit().invites().get(0).status());

            runtime.advanceTo(start);
            assertEquals("ACTIVE", runtime.interviewAudit().invites().get(0).status());

            String endBody = "{\"tool\":\"interview.end\",\"args\":{\"requestId\":\""
                    + requestId + "\"}}";
            decide(runtime, "end", "investor", "a-end", endBody);
            runtime.advanceTo(DecisionModel.actionDueMillis(
                    runtime.snapshot().decisions().orElseThrow().state(), "a-end"));
            assertEquals("ENDED_EARLY", runtime.interviewAudit().invites().get(0).status());
            long sessions = runtime.interviewAudit().works().stream()
                    .filter(work -> work.kind().equals(MediaRun.KIND_INTERVIEW_SESSION)).count();
            assertEquals(1L, sessions, "会话工时只能结算一次");
        }
    }

    @Test
    void cancelThroughRealDecisionRun(@TempDir Path dir) throws Exception {
        try (WorldRuntime runtime = WorldWebScenario.openInterview(dir.resolve("cancel.journal"))
                .runtime()) {
            publishAndRead(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            decide(runtime, "invite", "rita", "a-invite", inviteBody("req-c", cutoff));
            runtime.advanceTo(DecisionModel.actionDueMillis(
                    runtime.snapshot().decisions().orElseThrow().state(), "a-invite"));
            String requestId = runtime.interviewAudit().invites().get(0).requestId();
            String cancelBody = "{\"tool\":\"interview.cancel\",\"args\":{\"requestId\":\""
                    + requestId + "\"}}";
            decide(runtime, "cancel", "rita", "a-cancel", cancelBody);
            runtime.advanceTo(DecisionModel.actionDueMillis(
                    runtime.snapshot().decisions().orElseThrow().state(), "a-cancel"));
            assertEquals("CANCELED", runtime.interviewAudit().invites().get(0).status());
        }
    }

    /**
     * 安全反例：把真实采访待办改成另一个 decT 标识但保留原 payload（无对应 actionRef），
     * 重算帧长与摘要后重开必须被拒——不能"只要是 decT 前缀"就放行。
     */
    @Test
    void forgedInterviewPendingWithoutDecisionRefIsRejected(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("forged-src.journal");
        try (WorldRuntime runtime = WorldWebScenario.openInterview(source).runtime()) {
            publishAndRead(runtime);
            long cutoff = runtime.snapshot().timeMillis();
            decide(runtime, "invite", "rita", "a-invite", inviteBody("req-f", cutoff));
        }
        Path forged = dir.resolve("forged.journal");
        forgePendingTaskId(source, forged);
        Exception rejected = assertThrows(Exception.class, () -> {
            try (WorldRuntime ignored = WorldWebScenario.openInterview(forged).runtime()) {
                ignored.snapshot();
            }
        });
        String message = String.valueOf(rejected.getMessage());
        assertTrue(message.contains("采访待办缺少真实排程请求")
                        || message.contains("未唯一绑定原决定动作")
                        || message.contains("参数与已记录决定命令不一致"),
                "伪造待办必须命中来源校验，实际：" + message);
    }

    /** 复制日志并把最后快照里第一条采访人物待办改名为另一个 decT 标识。 */
    private static void forgePendingTaskId(Path source, Path target) throws Exception {
        List<byte[]> frames = readFrames(source);
        byte[] last = frames.get(frames.size() - 1);
        com.fasterxml.jackson.databind.JsonNode commit = MAPPER.readTree(last);
        boolean mutated = false;
        for (com.fasterxml.jackson.databind.JsonNode event : commit.get("events")) {
            if (!"STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                continue;
            }
            com.fasterxml.jackson.databind.JsonNode snapshot = MAPPER.readTree(
                    event.get("payload").asText());
            ArrayNode pending = (ArrayNode) snapshot.get("pending");
            for (int index = 0; index < pending.size(); index += 1) {
                ObjectNode task = (ObjectNode) pending.get(index);
                if (task.get("kind").asText().startsWith("INTERVIEW_")
                        && !task.get("kind").asText().endsWith("_GATE_V1")) {
                    ObjectNode clone = task.deepCopy();
                    clone.put("taskId", "decT:9:forged:9");
                    pending.add(clone);
                    mutated = true;
                    break;
                }
            }
            ((ObjectNode) event).put("payload", MAPPER.writeValueAsString(snapshot));
            ArrayNode used = (ArrayNode) snapshot.get("usedTaskIds");
            if (used != null) {
                used.add("decT:9:forged:9");
                ((ObjectNode) event).put("payload", MAPPER.writeValueAsString(snapshot));
            }
        }
        if (!mutated) {
            throw new IllegalStateException("没有可伪造的采访人物待办");
        }
        frames.set(frames.size() - 1, MAPPER.writeValueAsBytes(commit));
        writeFrames(target, frames);
    }

    private static List<byte[]> readFrames(Path file) throws Exception {
        byte[] all = Files.readAllBytes(file);
        List<byte[]> payloads = new ArrayList<>();
        int offset = 0;
        while (offset + 8 <= all.length) {
            int length = ByteBuffer.wrap(all, offset, 4).getInt();
            int complement = ByteBuffer.wrap(all, offset + 4, 4).getInt();
            if (length <= 0 || complement != ~length) {
                throw new IllegalStateException("帧头非法 offset=" + offset);
            }
            int end = offset + 8 + length + 32;
            if (end > all.length) {
                throw new IllegalStateException("帧不完整 offset=" + offset);
            }
            byte[] payload = new byte[length];
            System.arraycopy(all, offset + 8, payload, 0, length);
            payloads.add(payload);
            offset = end;
        }
        return payloads;
    }

    private static void writeFrames(Path file, List<byte[]> payloads) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] payload : payloads) {
            ByteBuffer head = ByteBuffer.allocate(8);
            head.putInt(payload.length).putInt(~payload.length);
            out.write(head.array());
            out.write(payload);
            out.write(sha.digest(payload));
        }
        Files.write(file, out.toByteArray());
    }
}
