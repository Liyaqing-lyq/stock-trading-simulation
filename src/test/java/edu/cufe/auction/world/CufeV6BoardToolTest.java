package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独审 D3 的语义等价核对：v6 决策链用既有 {@code board.convene} + {@code topic=MANAGER_APPOINTMENT}
 * 就能完成章程公司真实任免召集，不需要新增重复工具；非主席被拒，cufe 的工程豁免在计划期被拒，
 * 而工程公司的合法豁免工具保持可用。
 *
 * <p>全部参数经真实回执分派与 BEGIN→FINISH；不读 God 状态直注，也不改工具契约。</p>
 */
class CufeV6BoardToolTest {

    private static final String C = WorldWebScenario.COMPANY;
    private static final String A = WorldWebScenario.ENGINE_A;
    private static final long DAY = 86_400_000L;

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "fix5-v6-board-tools");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static WorldRuntime open(Path file) throws Exception {
        return WorldWebScenario.openEngineering(file).runtime();
    }

    private static DecisionRun.Call openCall(WorldRuntime world, String person, String requestId)
            throws Exception {
        world.beginDecisionSession("open-" + requestId);
        world.openDecisions("dec-" + requestId, List.of(new DecisionRun.Request(person,
                requestId, "wake", new DecisionModel.Route("governance", "key-1",
                        "deepseek-flash", 1L, 20, 1_000_000, 1_024, 1))));
        WorldRuntime.DispatchResult dispatched = world.dispatchLiveDecision("cmd-" + requestId,
                requestId);
        assertNotNull(dispatched.call(), "派发失败：" + dispatched.verdict() + "/"
                + dispatched.reason());
        return dispatched.call();
    }

    private static LiveCompletionInbox inbox(WorldRuntime world) {
        try {
            java.lang.reflect.Field field = WorldRuntime.class.getDeclaredField("liveInbox");
            field.setAccessible(true);
            return (LiveCompletionInbox) field.get(world);
        } catch (ReflectiveOperationException unreadable) {
            throw new IllegalStateException("无法访问 live 收件箱", unreadable);
        }
    }

    private static void reply(WorldRuntime world, DecisionRun.Call call, String body)
            throws Exception {
        PendingTask begin = replyPlanned(world, call, body);
        if (begin != null) {
            world.advanceTo(begin.dueTimeMillis() + 1_000L);
        }
    }

    /** 只登记回执并返回本批排出的 BEGIN 任务；计划期被拒时返回 null（不推进）。 */
    private static PendingTask replyPlanned(WorldRuntime world, DecisionRun.Call call, String body)
            throws Exception {
        DecisionModel.Reply answer = new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, 60, 20), "scripted:test");
        assertEquals(LiveCompletionInbox.RegisterResult.REGISTERED,
                inbox(world).register(LiveCompletionInbox.AttemptKey.of(call),
                        LiveCompletionInbox.Completion.business(answer)));
        assertEquals(1, world.drainDecisionCallbacks());
        DecisionRun.ActionRef ref = world.snapshot().decisions().orElseThrow()
                .actionRef(call.actionId());
        if (ref == null) {
            return null;
        }
        return world.snapshot().pending().stream()
                .filter(task -> task.taskId().equals(ref.taskId())).findFirst().orElse(null);
    }

    @Test
    void v6ConveneWithManagerAppointmentTopicConvenesCharterBoard() throws Exception {
        Path file = journal("v6-convene");
        try (WorldRuntime world = open(file)) {
            world.advanceTo(200L);
            long now = world.snapshot().timeMillis();
            DecisionRun.Call call = openCall(world, "alice", "v6-convene");
            assertTrue(call.allowedTools().contains(RoleTradingView.TOOL_BOARD_CONVENE),
                    "v6 必须给出既有 board.convene：" + call.allowedTools());
            boolean topicParameter = false;
            for (String[] parameter : DecisionRun.toolParameters(
                    RoleTradingView.TOOL_BOARD_CONVENE,
                    DecisionRun.TOOL_CONTRACT_VERSION_V6)) {
                topicParameter = topicParameter || "topic".equals(parameter[0]);
            }
            assertTrue(topicParameter,
                    "v6 的 board.convene 必须声明 topic 参数（任免议题走同一工具）");
            reply(world, call, "{\"tool\":\"board.convene\",\"args\":{"
                    + "\"companyPartyId\":\"" + C + "\",\"topic\":\"MANAGER_APPOINTMENT\","
                    + "\"meetingId\":\"v6-mgr-1\",\"materialDeadline\":" + (now + 5_000L)
                    + ",\"opensAt\":" + (now + 6_000L) + ",\"closesAt\":" + (now + 7_000L)
                    + "}}");
            BoardModel.Meeting meeting = world.boardOfCompany(C).orElseThrow().board()
                    .meeting("v6-mgr-1");
            assertNotNull(meeting, "v6 的 board.convene+topic 必须真实建会");
            assertEquals(BoardModel.Topic.MANAGER_APPOINTMENT, meeting.topic());
            assertEquals("s1", meeting.convenerSeatId());
        }
    }

    @Test
    void v6NonChairConveneIsRejectedWithoutCreatingMeeting() throws Exception {
        Path file = journal("v6-non-chair");
        try (WorldRuntime world = open(file)) {
            world.advanceTo(200L);
            long now = world.snapshot().timeMillis();
            DecisionRun.Call call = openCall(world, "bob", "v6-non-chair");
            reply(world, call, "{\"tool\":\"board.convene\",\"args\":{"
                    + "\"companyPartyId\":\"" + C + "\",\"topic\":\"MANAGER_APPOINTMENT\","
                    + "\"meetingId\":\"v6-mgr-2\",\"materialDeadline\":" + (now + 5_000L)
                    + ",\"opensAt\":" + (now + 6_000L) + ",\"closesAt\":" + (now + 7_000L)
                    + "}}");
            assertTrue(world.boardOfCompany(C).orElseThrow().board().meeting("v6-mgr-2") == null,
                    "非主席不得建会");
        }
    }

    @Test
    void v6WaiverRejectedForCharterButKeptForEngineeringCompany() throws Exception {
        Path cufeFile = journal("v6-waiver-cufe");
        try (WorldRuntime world = open(cufeFile)) {
            world.advanceTo(200L);
            DecisionRun.Call call = openCall(world, "alice", "v6-waiver-cufe");
            PendingTask planned = replyPlanned(world, call, "{\"tool\":\"board.proposeWaiver\",\"args\":{"
                    + "\"companyPartyId\":\"" + C + "\",\"meetingId\":\"m-w\","
                    + "\"waiverId\":\"v6-waiver-cufe-1\",\"contractId\":\"A1\","
                    + "\"amount\":10.0,\"purpose\":\"test\"}}");
            assertTrue(planned == null, "章程公司不得为工程豁免排任务：" + planned);
        }

        Path engFile = journal("v6-waiver-eng");
        try (WorldRuntime world = open(engFile)) {
            world.advanceTo(200L);
            DecisionRun.Call call = openCall(world, "alice", "v6-waiver-eng");
            PendingTask planned = replyPlanned(world, call, "{\"tool\":\"board.proposeWaiver\",\"args\":{"
                    + "\"companyPartyId\":\"" + A + "\",\"meetingId\":\"m-we\","
                    + "\"waiverId\":\"v6-waiver-eng-1\",\"contractId\":\"A1\","
                    + "\"amount\":10.0,\"purpose\":\"test\"}}");
            assertNotNull(planned, "工程公司的合法豁免工具必须保留，不得被章程公司限制误伤");
            assertTrue(planned.payload().contains("v6-waiver-eng-1"), planned.payload());
        }
    }
}
