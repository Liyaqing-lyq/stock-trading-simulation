package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema18b 定向：人物焦点双向互斥、BEGIN/FINISH 复核、同命令幂等与异体冲突、
 * 早回执不跳过更早他人任务、v6 角色投影与工具真实落世界、跨 JVM 重开与逐批单任务。
 *
 * <p>只使用真实 WorldRuntime/WorldJournal 与真实 BoardModel 状态；不使用真实 provider。</p>
 */
class EngineeringFocusInputTest {

    private static final String A = WorldWebScenario.ENGINE_A;
    private static final String B = WorldWebScenario.ENGINE_B;

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-focus-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    /** Alice 同时在 A、B 两公司任职董事，用于跨公司同人焦点。 */
    private static EngineeringRun.Config config() {
        EngineeringRun.Config base = WorldWebScenario.engineeringConfig();
        EngineeringRun.CompanySpec a = base.specOf(A);
        EngineeringRun.CompanySpec b = base.specOf(B);
        BoardModel.Charter aCharter = charter(A, "ea", List.of("alice", "bob", "carol",
                "dave", "erin"));
        BoardModel.Charter bCharter = charter(B, "eb", List.of("alice", "rita", "ed", "sam",
                "bea"));
        EngineeringRun.CompanySpec aSpec = new EngineeringRun.CompanySpec(a.companyPartyId(),
                a.companyOwnerId(), a.companyAccountId(), a.customerAccountId(),
                a.supplierAccountId(), a.opsAccountId(), a.unitMillis(), a.laborBp(), aCharter,
                List.of(plan("A1")));
        EngineeringRun.CompanySpec bSpec = new EngineeringRun.CompanySpec(b.companyPartyId(),
                b.companyOwnerId(), b.companyAccountId(), b.customerAccountId(),
                b.supplierAccountId(), b.opsAccountId(), b.unitMillis(), b.laborBp(), bCharter,
                List.of(plan("B1")));
        return new EngineeringRun.Config("18b 焦点实验参数", List.of(aSpec, bSpec));
    }

    private static BoardModel.Charter charter(String company, String prefix, List<String> people) {
        List<BoardModel.Seat> seats = new ArrayList<>();
        for (int i = 0; i < people.size(); i++) {
            seats.add(new BoardModel.Seat(prefix + (i + 1), people.get(i)));
        }
        return new BoardModel.Charter(company, seats, prefix + "1", BoardModel.QUORUM);
    }

    private static EngineeringRun.ContractPlan plan(String id) {
        return new EngineeringRun.ContractPlan(id, new BigDecimal("100.00"),
                new BigDecimal("40.0000"), 0, 0, 0, 0, List.of(10L));
    }

    private static void publish(WorldRuntime world, String docId) throws Exception {
        world.schedulePublishDocument("pub-" + docId, "pub-" + docId + "-task", 10L, docId,
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "资料 " + docId, "实验资料 " + docId, "实验材料", 10L, List.of());
    }

    @Test
    void focusIsSharedAcrossCompaniesButNotAcrossPeople() throws Exception {
        Path file = journal("cross");
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config())) {
            // alice 在 A 公司占用 [2000,3000)
            world.scheduleEngineeringVote("a-vote", "a-vote-t", 3_000L, A, "alice", "m-a",
                    BoardModel.Vote.YES, 1_000L);
            // 同一人在 B 公司的重叠窗口必须被拒绝（跨公司共享同一焦点）
            IllegalArgumentException cross = assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("b-vote", "b-vote-t", 3_500L, B,
                            "alice", "m-b", BoardModel.Vote.YES, 1_000L));
            assertTrue(cross.getMessage().contains("焦点"), cross.getMessage());
            // 同一会议也不豁免：同一人同一会议的重叠正工时同样拒绝
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("a-vote2", "a-vote2-t", 4_000L, A,
                            "alice", "m-a", BoardModel.Vote.NO, 1_500L));
            // 不同人物同刻不互阻
            assertEquals("SCHEDULED:b-vote-ok-t", world.scheduleEngineeringVote("b-vote-ok",
                    "b-vote-ok-t", 3_500L, B, "rita", "m-b", BoardModel.Vote.YES, 1_000L));
            // 未声明工时的行政登记也有最小正工时，同一时点的两个动作会冲突
            assertEquals("SCHEDULED:a-attend-t", world.scheduleEngineeringAttend("a-attend",
                    "a-attend-t", 3_050L, A, "alice", "m-a", false));
        }
    }

    @Test
    void boardActionAfterReadingEndsIsAllowed() throws Exception {
        Path file = journal("after-reading");
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config())) {
            publish(world, "doc-after");
            world.advanceTo(10L);
            world.scheduleStartReading("read-a", "read-a-t", 2_000L, "alice", "doc-after", 1L);
            world.advanceTo(2_000L);
            // 阅读仍在进行 [2000,2010)：重叠窗口必须拒绝
            IllegalArgumentException overlap = assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("v-while-read", "v-while-read-t", 2_105L,
                            A, "alice", "m-a", BoardModel.Vote.YES, 100L));
            assertTrue(overlap.getMessage().contains("焦点"), overlap.getMessage());
            // 阅读结束后：同样的董事动作必须允许（不能把未来不重叠预约当成占用）
            world.advanceTo(2_020L);
            assertEquals("SCHEDULED:v-after-read-t", world.scheduleEngineeringVote("v-after-read",
                    "v-after-read-t", 2_200L, A, "alice", "m-a", BoardModel.Vote.YES, 100L));
        }
    }

    @Test
    void focusIsBidirectionalWithReading() throws Exception {
        Path file = journal("reading");
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config())) {
            publish(world, "doc-focus");
            world.advanceTo(10L);
            // 阅读到期即真正开始读：活动区间 [2000,2010)
            world.scheduleStartReading("read-1", "read-1-t", 2_000L, "alice", "doc-focus", 1L);
            world.advanceTo(2_000L);
            IllegalArgumentException busy = assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("v-overlap", "v-overlap-t", 2_050L, A,
                            "alice", "m-a", BoardModel.Vote.YES, 50L));
            assertTrue(busy.getMessage().contains("焦点"), busy.getMessage());
            // 与阅读结束点衔接之后可以排
            world.scheduleEngineeringVote("v-after", "v-after-t", 2_600L, A, "alice", "m-a",
                    BoardModel.Vote.YES, 500L);
            // 反向：本人已有董事正工时 [2100,2600)，此时不能开始新的阅读
            IllegalArgumentException reverse = assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleStartReading("read-2", "read-2-t", 2_500L, "alice",
                            "doc-focus", 1L));
            assertTrue(reverse.getMessage().contains("焦点"), reverse.getMessage());
            // 不同人物不受影响
            world.scheduleStartReading("read-3", "read-3-t", 2_500L, "bob", "doc-focus", 1L);
        }
    }

    @Test
    void sameCommandIsIdempotentAndDifferentBodyConflicts() throws Exception {
        Path file = journal("idempotent");
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config())) {
            assertEquals("SCHEDULED:dup-t", world.scheduleEngineeringVote("dup", "dup-t",
                    5_000L, A, "bob", "m-a", BoardModel.Vote.YES, 250L));
            assertEquals("SCHEDULED:dup-t", world.scheduleEngineeringVote("dup", "dup-t",
                    5_000L, A, "bob", "m-a", BoardModel.Vote.YES, 250L),
                    "同命令同内容必须幂等");
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("dup", "dup-t", 5_000L, A, "bob",
                            "m-a", BoardModel.Vote.NO, 250L), "同命令异内容必须拒绝");
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("dup", "dup-t", 5_100L, A, "bob",
                            "m-a", BoardModel.Vote.YES, 250L), "同命令换到期必须拒绝");
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("dup", "dup-other", 5_000L, A, "bob",
                            "m-a", BoardModel.Vote.YES, 250L), "同命令换任务标识必须拒绝");
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("dup", "dup-t", 5_000L, A, "bob",
                            "m-a", BoardModel.Vote.YES, 251L), "同命令换声明工时必须拒绝");
        }
    }

    @Test
    void zeroDurationPublicEntriesAreRejected() throws Exception {
        Path file = journal("zero-public");
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config())) {
            // 公开入口的 0 工时等于“不占焦点”，外部/模型不能借此把动作塞进别人的窗口
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("zero-v", "zero-v-t", 5_000L, A, "bob",
                            "m-a", BoardModel.Vote.YES, 0L), "公开投票入口必须拒绝 0 工时");
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringAction("zero-a", "zero-a-t", 5_000L, A,
                            "dave", "SIGN", "A1", "", 0, 0, BigDecimal.ZERO, "", 0, 0L),
                    "公开经理入口必须拒绝 0 工时");
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("neg-v", "neg-v-t", 5_000L, A, "bob",
                            "m-a", BoardModel.Vote.YES, -1L), "工时不得为负");
            // 外部不能借“系统口径”绕过：公开经理入口不接受空人物
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringAction("blank-person", "blank-person-t",
                            5_000L, A, "", "COMPLETE_WORK", "A1", "W-A1", 0, 0,
                            BigDecimal.ZERO, "", 0), "空人物不得走公开经营入口");
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringAction("blank-person2", "blank-person2-t",
                            5_000L, A, " ", "COMPLETE_WORK", "A1", "W-A1", 0, 0,
                            BigDecimal.ZERO, "", 0), "空白人物不得走公开经营入口");
            // 默认公开重载仍带最小正工时，动作照常落成 BEGIN 屏障
            assertEquals("SCHEDULED:default-v-t", world.scheduleEngineeringVote("default-v",
                    "default-v-t", 5_000L, A, "carol", "m-a", BoardModel.Vote.YES));
            PendingTask scheduled = world.snapshot().pending().stream()
                    .filter(task -> task.taskId().equals("default-v-t")).findFirst().orElseThrow();
            assertEquals(PendingTask.ENGINEERING_BEGIN_V1, scheduled.kind(),
                    "默认入口也必须先落真实 BEGIN 屏障");
            assertEquals(4_999L, scheduled.dueTimeMillis(), "默认最小正工时为 1 毫秒");
        }
    }

    @Test
    void earlyReceiptKeepsEarlierTaskOfAnotherPerson() throws Exception {
        Path file = journal("order");
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config())) {
            world.scheduleEngineeringVote("t4001", "t4001-task", 4_011L, A, "bob", "m-a",
                    BoardModel.Vote.YES, 10L);
            world.scheduleEngineeringVote("t4500", "t4500-task", 4_510L, A, "carol", "m-a",
                    BoardModel.Vote.YES, 10L);
            world.advanceTo(4_510L);
            List<String> order = new ArrayList<>(
                    world.snapshot().engineering().orElseThrow().reports().keySet());
            assertTrue(order.indexOf("t4001-task") < order.indexOf("t4500-task"),
                    "更早到期的他人任务必须先执行：" + order);
            assertEquals(4_001L, world.snapshot().engineering().orElseThrow()
                    .reports().get("t4001-task").timeMillis(),
                    "真实开始时点不得被他人任务改写");
            assertEquals(4_500L, world.snapshot().engineering().orElseThrow()
                    .reports().get("t4500-task").timeMillis());
            // BEGIN 报告落在真实开始（到期−工时），FINISH 报告才落到期；两者都不被改写
            assertEquals(4_011L, world.snapshot().engineering().orElseThrow()
                    .reports().get("t4001-task:finish").timeMillis());
            assertEquals(4_510L, world.snapshot().engineering().orElseThrow()
                    .reports().get("t4500-task:finish").timeMillis());
        }
    }

    @Test
    void v6InputIsRoleScopedAndToolActuallyLandsInWorld() throws Exception {
        Path file = journal("v6");
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config());
        publish(world, "doc-read");
        publish(world, "doc-unread");
        world.advanceTo(10L);
        // alice 只真实读完 doc-read；doc-unread 保持未读
        world.scheduleStartReading("read-mat", "read-mat-t", 60L, "alice", "doc-read", 1L);
        world.advanceTo(60L);
        world.scheduleEngineeringConvene("cv", "cv-t", 1_000L, A, "alice", "m1", 8_000L,
                9_000L, 10_000L);
        world.advanceTo(1_000L);
        world.scheduleEngineeringAttend("att-alice", "att-alice-t", 1_050L, A, "alice",
                "m1", false);
        world.advanceTo(1_050L);
        world.scheduleEngineeringMaterial("mat-1", "mat-1-t", 1_100L, A, "alice", "m1", "mm1",
                "doc-read", 1L);
        world.scheduleEngineeringMaterial("mat-2", "mat-2-t", 1_200L, A, "alice", "m1", "mm2",
                "doc-unread", 1L);
        world.advanceTo(1_200L);

        world.beginDecisionSession("open-1");
        world.openDecisions("d-1", List.of(new DecisionRun.Request("bob", "r-attend",
                "wake", new DecisionModel.Route("governance", "key-1", "deepseek-flash",
                1L, 20, 1_000_000, 1_024, 1))));
        DecisionRun.Call call = world.dispatchLiveDecision("cmd-attend", "r-attend").call();
        String input = call.inputText();
        assertTrue(input.contains("role-decision-tools-v6"), "18 档必须是 v6");
        assertTrue(input.contains("\"engineering\""), "v6 必须输出工程投影");
        assertTrue(input.contains("\"seatId\":\"ea2\""), "董事只看到本人席位：" + input);
        assertTrue(input.contains("\"companyPartyId\":\"eng-a\""));
        assertTrue(input.contains("\"managerships\":[]"), "非经理不得拿到经营投影");
        assertFalse(input.contains("\"ballots\""), "不得泄露他人选票");
        assertTrue(input.contains("\"ownBallot\":\"\""), "本人未投票时自己的票为空");
        // v5 工具合同不出现任何工程工具参数
        assertTrue(DecisionRun.toolParameters(RoleTradingView.TOOL_BOARD_VOTE,
                DecisionRun.TOOL_CONTRACT_VERSION_V5).isEmpty());
        assertFalse(DecisionRun.toolParameters(RoleTradingView.TOOL_BOARD_VOTE,
                DecisionRun.TOOL_CONTRACT_VERSION_V6).isEmpty());

        // 用真实 live 收件箱注入一次确定性回执（0 真实 provider 调用），验证工具真的落世界
        String body = "{\"tool\":\"board.attend\",\"args\":{\"companyPartyId\":\"eng-a\","
                + "\"meetingId\":\"m1\"}}";
        DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, 60, 20), "scripted:focus-v6");
        LiveCompletionInbox inbox;
        try {
            java.lang.reflect.Field field = WorldRuntime.class.getDeclaredField("liveInbox");
            field.setAccessible(true);
            inbox = (LiveCompletionInbox) field.get(world);
        } catch (ReflectiveOperationException unreadable) {
            throw new IllegalStateException("无法访问 live 收件箱", unreadable);
        }
        assertEquals(LiveCompletionInbox.RegisterResult.REGISTERED,
                inbox.register(LiveCompletionInbox.AttemptKey.of(call),
                        LiveCompletionInbox.Completion.business(reply)));
        assertEquals(1, world.drainDecisionCallbacks());
        DecisionRun.ActionRef ref = world.snapshot().decisions().orElseThrow()
                .actionRef("r-attend");
        assertTrue(ref != null, "工具请求必须留下任务引用：status="
                + DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),
                "r-attend").map(d -> d.outcome() + "/" + d.lastAttempt().reason()).orElse("none")
                + " dispatch=" + world.snapshot().decisions().orElseThrow().dispatch(
                call.attemptId()));
        PendingTask scheduled = world.snapshot().pending().stream()
                .filter(task -> task.taskId().equals(ref.taskId())).findFirst().orElseThrow();
        assertEquals(PendingTask.ENGINEERING_BEGIN_V1, scheduled.kind(),
                "回执只落意图：先排的是真实 BEGIN 屏障，不是完成帧");
        long barrier = DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),
                "r-attend").orElseThrow().barrierAtMillis();
        assertEquals(barrier, scheduled.dueTimeMillis(), "BEGIN 就在屏障时点");

        // 屏障前不得提前落董事会状态；到屏障才真实开始并按工时排完成帧
        world.advanceTo(barrier);
        EngineeringRun.Report begin = world.snapshot().engineering().orElseThrow()
                .reports().get(scheduled.taskId());
        assertTrue(begin != null && begin.accepted(),
                "屏障必须留下真实开始报告：" + begin);
        assertEquals(barrier, begin.timeMillis(), "开始报告的时点就是屏障");
        PendingTask finish = world.snapshot().pending().stream()
                .filter(task -> task.taskId().equals(scheduled.taskId() + ":finish"))
                .findFirst().orElseThrow();
        assertEquals(barrier + WorldWebScenario.ENGINEERING_ACTION_MILLIS,
                finish.dueTimeMillis(), "完成帧到期 = 屏障 + 真实工时");
        assertEquals(PendingTask.ENGINEERING_BOARD_V1, finish.kind());
        assertFalse(world.boardOfCompany(A).orElseThrow().board().meeting("m1")
                .attendeeSeatIds().contains("ea2"), "开始不等于已完成，屏障时不得提前落状态");

        world.advanceTo(finish.dueTimeMillis());
        assertTrue(world.boardOfCompany(A).orElseThrow().board().meeting("m1")
                .attendeeSeatIds().contains("ea2"), "到会必须真实落进董事会状态");
        long position = world.snapshot().position();
        world.close();

        // 跨 JVM 重开：同一 journal 逐帧复原，位置与会议一致
        try (WorldJournal scan = new WorldJournal(file,
                WorldWebScenario.engineeringManifest().runId())) {
            int batches = 0;
            for (WorldCommit commit : scan.commits()) {
                long completed = commit.events().stream()
                        .filter(event -> "TASK_COMPLETED_V1".equals(event.type())).count();
                assertTrue(completed <= 1, "真实写入方每批最多完成一个任务：" + commit.position());
                batches++;
            }
            assertTrue(batches > 10, "日志批次数量异常：" + batches);
        }
        try (WorldRuntime reopened = WorldWebScenario.engineeringRuntime(file, config())) {
            assertEquals(position, reopened.snapshot().position());
            assertTrue(reopened.boardOfCompany(A).orElseThrow().board().meeting("m1")
                    .attendeeSeatIds().contains("ea2"));
        }
    }

    @Test
    void v6ProjectionShowsReadFlagAndHidesUnreadOpportunity() throws Exception {
        Path file = journal("projection");
        try (WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config())) {
            publish(world, "doc-a");
            world.advanceTo(10L);
            world.scheduleStartReading("read-a", "read-a-t", 60L, "alice", "doc-a", 1L);
            world.advanceTo(60L);
            world.scheduleEngineeringConvene("cv2", "cv2-t", 1_000L, A, "alice", "m2", 8_000L,
                    9_000L, 10_000L);
            world.advanceTo(1_000L);
            world.scheduleEngineeringAttend("att-a2", "att-a2-t", 1_050L, A, "alice",
                    "m2", false);
            world.advanceTo(1_050L);
            world.scheduleEngineeringMaterial("mat-a", "mat-a-t", 1_100L, A, "alice", "m2",
                    "mm-a", "doc-a", 1L);
            world.scheduleEngineeringMaterial("mat-b", "mat-b-t", 1_200L, A, "alice", "m2",
                    "mm-b", "doc-never", 1L);
            world.advanceTo(1_200L);
            world.beginDecisionSession("open-2");
            world.openDecisions("d-2", List.of(new DecisionRun.Request("alice", "r-meter",
                    "wake", new DecisionModel.Route("governance", "key-1", "deepseek-flash",
                    1L, 20, 1_000_000, 1_024, 1))));
            String input = world.dispatchLiveDecision("cmd-meter", "r-meter").call().inputText();
            assertTrue(input.contains("\"chair\":true"), "主席席位必须可见：" + input);
            assertTrue(input.contains("{\"materialId\":\"mm-a\",\"docId\":\"doc-a\","
                    + "\"version\":1,\"read\":true,\"recalled\":true}"),
                    "已读材料必须标为已读可回忆：" + input);
            assertTrue(input.contains("{\"materialId\":\"mm-b\",\"docId\":\"doc-never\","
                    + "\"version\":1,\"read\":false,\"recalled\":false}"),
                    "未读材料不得被当成读过：" + input);
            assertTrue(input.contains("\"managerships\":[]"), "董事不是经理，不得有经营投影");
        }
    }
}
