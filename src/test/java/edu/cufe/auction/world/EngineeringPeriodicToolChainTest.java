package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v6 工具链：真实角色投影 → 模型回执 → BEGIN 屏障 → FINISH 落世界。
 *
 * <p>只注入确定性回执（0 真实 provider 调用），验证 periodic.prepare 真的会走
 * {@code planEngineeringTool}、真实经理复核、BOARD 材料发表与草稿登记。</p>
 */
class EngineeringPeriodicToolChainTest {

    private static final long DAY = 86_400_000L;
    private static final String A = WorldWebScenario.ENGINE_A;
    private static final String[] DIRECTORS = {"alice", "bob", "carol", "dave", "erin"};
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-periodic-tool-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static WorldRuntime open(Path file) throws Exception {
        return WorldWebScenario.engineeringRuntime(file, WorldWebScenario.engineeringConfig());
    }

    /** 真实五席预算 + 任免 → dave 自 700 起为 ENG-A 经理。 */
    private static void appointManager(WorldRuntime world) throws Exception {
        world.schedulePublishDocument("pub-budget", "pub-budget-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        for (int i = 0; i < DIRECTORS.length; i++) {
            String person = DIRECTORS[i];
            world.scheduleStartReading("read-mat-" + person, "read-mat-" + person + "-task",
                    20L + i * 2L, person, "b-material", 1L);
            world.scheduleStartReading("read-appt-" + person, "read-appt-" + person + "-task",
                    60L + i * 2L, person, "appt-material", 1L);
        }
        world.scheduleEngineeringAppointmentConvene("mgr-c", "mgr-c-task", 400L, A, "alice",
                "mgr-meeting", 450L, 500L, 600L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("mgr-a-" + i, "mgr-a-" + i + "-task",
                    410L + i, A, DIRECTORS[i], "mgr-meeting", false);
        }
        world.scheduleEngineeringMaterial("mgr-m", "mgr-m-task", 430L, A, "alice",
                "mgr-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("mgr-p", "mgr-p-task", 440L, A, "alice",
                "mgr-meeting", List.of(), new BoardModel.AppointmentRequest("mgr-a", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        700L, 900L * DAY));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("mgr-v-" + i, "mgr-v-" + i + "-task", 520L + i, A,
                    DIRECTORS[i], "mgr-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("mgr-i", "mgr-i-task", 600L, A, "alice",
                "mgr-meeting");
        world.advanceTo(700L);
    }

    /** 打开一次真实的 v6 决定并立即取回派发回执。 */
    private static DecisionRun.Call openCall(WorldRuntime world, String person, String requestId)
            throws Exception {
        world.beginDecisionSession("open-" + requestId);
        world.openDecisions("dec-" + requestId, List.of(new DecisionRun.Request(person,
                requestId, "wake", new DecisionModel.Route("governance", "key-1",
                        "deepseek-flash", 1L, 20, 1_000_000, 1_024, 1))));
        return world.dispatchLiveDecision("cmd-" + requestId, requestId).call();
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

    /** 回注模型回执并推进到该动作完成（BEGIN 屏障 + FINISH）。 */
    private static void reply(WorldRuntime world, DecisionRun.Call call, String body)
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
            return;
        }
        PendingTask begin = world.snapshot().pending().stream()
                .filter(task -> task.taskId().equals(ref.taskId())).findFirst().orElseThrow();
        world.advanceTo(begin.dueTimeMillis() + WorldWebScenario.ENGINEERING_ACTION_MILLIS
                + 1_000L);
    }

    /** 通过真实 v6 回执分派一次周期工具，并推进到该动作完成。 */
    private static void tool(WorldRuntime world, String person, String requestId, String tool,
            String argsJson) throws Exception {
        DecisionRun.Call call = openCall(world, person, requestId);
        String body = "{\"tool\":\"" + tool + "\",\"args\":" + argsJson + "}";
        reply(world, call, body);
    }

    @Test
    void v6ScoreApproveAuthorizePublishAllLand() throws Exception {
        Path file = journal("v6-full");
        WorldRuntime world = open(file);
        appointManager(world);
        world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);
        PeriodicModel.Snapshot snapshot = world.periodicOf(A).orElseThrow().snapshots().get(0);
        BoardModel.Meeting meeting = world.boardOfCompany(A).orElseThrow().board()
                .meeting(snapshot.meetingId());
        for (int i = 0; i < DIRECTORS.length; i++) {
            world.scheduleEngineeringAttend("pd-a-" + i, "pd-a-" + i + "-task",
                    snapshot.capturedAt() + 10L + i, A, DIRECTORS[i], snapshot.meetingId(),
                    false);
        }
        world.advanceTo(meeting.opensAt() + 1_000L);
        world.schedulePeriodicAction("prep", "prep-task", meeting.opensAt() + 2_000L, A,
                "dave", "PREPARE", 1L, "", 0L, "", 0L, "", List.of(), "", 100L);
        world.advanceTo(meeting.opensAt() + 3_000L);
        String draftId = world.periodicOf(A).orElseThrow().drafts().get(0).draftId();
        String docId = world.periodicOf(A).orElseThrow().drafts().get(0).docId();
        for (int i = 0; i < 3; i++) {
            world.scheduleStartReading("rd-" + i, "rd-" + i + "-task",
                    meeting.opensAt() + 4_000L + i * 2L, DIRECTORS[i], docId, 1L);
        }
        world.advanceTo(meeting.opensAt() + 4_000L
                + PeriodicWorldEngine.MATERIAL_READ_MILLIS + 1_000L);
        String metrics = "[{\"dimension\":\"CASH_SOLVENCY\",\"score\":90,"
                + "\"sourceRef\":\"account:ENG-A\",\"asOf\":" + snapshot.capturedAt() + "}]";
        for (int i = 0; i < 3; i++) {
            tool(world, DIRECTORS[i], "sc-" + i, "periodic.score",
                    "{\"companyPartyId\":\"eng-a\",\"period\":1,\"metrics\":" + metrics + "}");
        }
        assertEquals(3, world.periodicOf(A).orElseThrow().ballots().size());

        world.advanceTo(meeting.closesAt() + 10L);
        for (int i = 0; i < 3; i++) {
            tool(world, DIRECTORS[i], "ap-" + i, "periodic.approve",
                    "{\"companyPartyId\":\"eng-a\",\"period\":1,\"draftId\":\"" + draftId
                            + "\",\"draftVersion\":1}");
        }
        assertEquals(3, world.periodicOf(A).orElseThrow().approvals().size());

        long expiresAt = world.snapshot().timeMillis() + DAY;
        tool(world, "dave", "au-1", "periodic.authorize",
                "{\"companyPartyId\":\"eng-a\",\"period\":1,\"draftId\":\"" + draftId
                        + "\",\"draftVersion\":1,\"publisherPersonId\":\"alice\","
                        + "\"expiresAt\":" + expiresAt + "}");
        assertEquals(1, world.periodicOf(A).orElseThrow().authorizations().size());

        tool(world, "alice", "pu-1", "periodic.publish",
                "{\"companyPartyId\":\"eng-a\",\"period\":1,\"draftId\":\"" + draftId
                        + "\",\"draftVersion\":1}");
        assertEquals(1, world.periodicOf(A).orElseThrow().releases().size());
        assertTrue(InformationModel.version(world.snapshot().information().orElseThrow()
                .information(), PeriodicWorldEngine.publicDocId(A, 1L), 1L).isPresent());
        world.close();
    }

    @Test
    void v6PrepareToolLandsRealBoardMaterial() throws Exception {
        Path file = journal("v6");
        WorldRuntime world = open(file);
        appointManager(world);
        world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);
        PeriodicModel.Snapshot snapshot = world.periodicOf(A).orElseThrow().snapshots().get(0);
        BoardModel.Meeting meeting = world.boardOfCompany(A).orElseThrow().board()
                .meeting(snapshot.meetingId());
        long opensAt = meeting.opensAt();
        for (int i = 0; i < DIRECTORS.length; i++) {
            world.scheduleEngineeringAttend("pd-a-" + i, "pd-a-" + i + "-task",
                    snapshot.capturedAt() + 10L + i, A, DIRECTORS[i], snapshot.meetingId(),
                    false);
        }
        world.advanceTo(opensAt + 1_000L);

        world.beginDecisionSession("open-1");
        world.openDecisions("d-1", List.of(new DecisionRun.Request("dave", "r-prep", "wake",
                new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20,
                        1_000_000, 1_024, 1))));
        DecisionRun.Call call = world.dispatchLiveDecision("cmd-prep", "r-prep").call();
        String input = call.inputText();
        assertTrue(input.contains("role-decision-tools-v6"), "18 档必须是 v6");
        assertTrue(input.contains("periodic.prepare"), "经理投影必须给出周期准备工具");

        String body = "{\"tool\":\"periodic.prepare\",\"args\":{\"companyPartyId\":\"eng-a\","
                + "\"period\":1,\"note\":\"\"}}";
        DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, 60, 20), "scripted:periodic-v6");
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
                .actionRef("r-prep");
        assertNotNull(ref, "工具请求必须留下任务引用");
        PendingTask scheduled = world.snapshot().pending().stream()
                .filter(task -> task.taskId().equals(ref.taskId())).findFirst().orElseThrow();
        assertEquals(PendingTask.ENGINEERING_BEGIN_V1, scheduled.kind(),
                "回执只落意图：先排 BEGIN 屏障");
        long barrier = DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),
                "r-prep").orElseThrow().barrierAtMillis();
        assertEquals(barrier, scheduled.dueTimeMillis());

        world.advanceTo(barrier);
        PendingTask finish = world.snapshot().pending().stream()
                .filter(task -> task.taskId().equals(scheduled.taskId() + ":finish"))
                .findFirst().orElseThrow();
        assertEquals(PendingTask.ENGINEERING_PERIODIC_V1, finish.kind());
        assertFalse(InformationModel.version(world.snapshot().information().orElseThrow()
                .information(), "periodic-material:" + A + ":1", 1L).isPresent(),
                "屏障时不得提前发表材料");

        world.advanceTo(finish.dueTimeMillis());
        assertTrue(InformationModel.version(world.snapshot().information().orElseThrow()
                .information(), "periodic-material:" + A + ":1", 1L).isPresent(),
                "FINISH 必须真实发表 BOARD 材料");
        assertEquals(1, world.periodicOf(A).orElseThrow().drafts().size());
        assertEquals("dave", world.periodicOf(A).orElseThrow().drafts().get(0).preparedBy());
        assertEquals(BoardModel.MeetingStatus.MATERIALS_FROZEN, world.boardOfCompany(A)
                .orElseThrow().board().meeting(snapshot.meetingId()).status());
        world.close();

        // 跨 JVM：工具派生批次也要能逐帧重演。
        try (WorldRuntime reopened = open(file)) {
            assertEquals(1, reopened.periodicOf(A).orElseThrow().drafts().size());
        }
    }

    /** 从真实 v6 输入里取本人可见的周期投影；没有则返回 null。 */
    private static com.fasterxml.jackson.databind.JsonNode periodicOf(String input, String company)
            throws Exception {
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
        assertEquals("role-decision-tools-v6", root.path("toolContractVersion").asText());
        for (com.fasterxml.jackson.databind.JsonNode node
                : root.path("engineering").path("directorships")) {
            if (company.equals(node.path("companyPartyId").asText())) {
                return node.path("periodic");
            }
        }
        return null;
    }

    /** 取某公司的董事投影节点（含 companyPartyId/席位/周期行）。 */
    private static com.fasterxml.jackson.databind.JsonNode directorshipOf(String input,
            String company) throws Exception {
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
        assertEquals("role-decision-tools-v6", root.path("toolContractVersion").asText());
        for (com.fasterxml.jackson.databind.JsonNode node
                : root.path("engineering").path("directorships")) {
            if (company.equals(node.path("companyPartyId").asText())) {
                return node;
            }
        }
        throw new AssertionError("输入缺少本公司董事投影：" + company);
    }

    @Test
    void v6InputCarriesVisiblePeriodEvidenceNotGodValues() throws Exception {
        Path file = journal("v6-input");
        WorldRuntime world = open(file);
        appointManager(world);
        world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);
        PeriodicModel.Snapshot snapshot = world.periodicOf(A).orElseThrow().snapshots().get(0);
        BoardModel.Meeting meeting = world.boardOfCompany(A).orElseThrow().board()
                .meeting(snapshot.meetingId());
        for (int i = 0; i < DIRECTORS.length; i++) {
            world.scheduleEngineeringAttend("pd-a-" + i, "pd-a-" + i + "-task",
                    snapshot.capturedAt() + 10L + i, A, DIRECTORS[i], snapshot.meetingId(), false);
        }
        world.advanceTo(meeting.opensAt() + 1_000L);
        world.schedulePeriodicAction("prep", "prep-task", meeting.opensAt() + 2_000L, A,
                "dave", "PREPARE", 1L, "", 0L, "", 0L, "", List.of(), "", 100L);
        world.advanceTo(meeting.opensAt() + 3_000L);
        String docId = world.periodicOf(A).orElseThrow().drafts().get(0).docId();
        for (int i = 0; i < 2; i++) {
            world.scheduleStartReading("rd-" + i, "rd-" + i + "-task",
                    meeting.opensAt() + 4_000L + i * 2L, DIRECTORS[i], docId, 1L);
        }
        world.advanceTo(meeting.opensAt() + 4_000L
                + PeriodicWorldEngine.MATERIAL_READ_MILLIS + 1_000L);

        // 董事只从自己的 v6 输入取公司/期间/截至/证据引用，不读测试里的 God 真值。
        DecisionRun.Call call = openCall(world, "alice", "sc-in");
        com.fasterxml.jackson.databind.JsonNode periodic = periodicOf(call.inputText(), A);
        assertNotNull(periodic, "v6 输入必须带本人可见的周期投影");
        assertTrue(periodic.isArray() && periodic.size() >= 1, "至少本期一行");
        com.fasterxml.jackson.databind.JsonNode row = periodic.get(0);
        long capturedAtFromInput = row.path("capturedAt").asLong();
        assertEquals(1L, row.path("period").asLong());
        assertEquals(snapshot.capturedAt(), capturedAtFromInput, "截至必须来自真实采样帧");
        assertEquals(docId, row.path("materialDocId").asText());
        assertEquals(BoardModel.MeetingStatus.MATERIALS_FROZEN.name(),
                row.path("meetingStatus").asText());
        String ref = "";
        for (com.fasterxml.jackson.databind.JsonNode evidence : row.path("evidence")) {
            if (evidence.path("dimensions").toString().contains("CASH_SOLVENCY")) {
                ref = evidence.path("ref").asText();
            }
        }
        assertTrue(ref.startsWith("account:"), "投影只给真实账户引用：" + row.path("evidence"));

        String body = "{\"tool\":\"periodic.score\",\"args\":{\"companyPartyId\":\"" + A
                + "\",\"period\":1,\"metrics\":[{\"dimension\":\"CASH_SOLVENCY\",\"score\":90,"
                + "\"sourceRef\":\"" + ref + "\",\"asOf\":" + capturedAtFromInput + "}]}}";
        reply(world, call, body);
        assertEquals(1, world.periodicOf(A).orElseThrow().ballots().size(),
                "只用本人可见的期间与证据就必须能真实提交评分");

        // 角色自造 canonical 私有引用（长期投入机制在本政策中为 none）必须被拒。
        tool(world, "bob", "sc-bad", "periodic.score",
                "{\"companyPartyId\":\"" + A + "\",\"period\":1,\"metrics\":[{\"dimension\":"
                        + "\"LONG_TERM_INVESTMENT\",\"score\":50,"
                        + "\"sourceRef\":\"longterm:plan-1\",\"asOf\":" + capturedAtFromInput
                        + "}]}");
        assertEquals(1, world.periodicOf(A).orElseThrow().ballots().size(),
                "自造引用不得凑维度");
        List<String> reasons = world.snapshot().engineering().orElseThrow().reports().values()
                .stream()
                .filter(entry -> PendingTask.ENGINEERING_PERIODIC_V1.equals(entry.kind()))
                .filter(entry -> entry.commandId().contains("sc-bad"))
                .map(EngineeringRun.Report::reason).toList();
        assertTrue(reasons.contains("EVIDENCE_NOT_ELIGIBLE"),
                "自造引用不在本期冻结集合，必须按证据不合格拒绝：" + reasons);
        world.close();
    }

    /** 从 v6 输入里取某公司某期间的行；没有则返回 null。 */
    private static com.fasterxml.jackson.databind.JsonNode periodicRow(String input, String company,
            long period) throws Exception {
        com.fasterxml.jackson.databind.JsonNode periodic = periodicOf(input, company);
        if (periodic == null || !periodic.isArray()) {
            return null;
        }
        for (com.fasterxml.jackson.databind.JsonNode row : periodic) {
            if (row.path("period").asLong() == period) {
                return row;
            }
        }
        return null;
    }

    /** 采样 → 五席到会 → 经理准备 → 三位其他董事实读/评分/批准；返回草稿标识。 */
    private static String preparedApproved(WorldRuntime world) throws Exception {
        appointManager(world);
        world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);
        PeriodicModel.Snapshot snapshot = world.periodicOf(A).orElseThrow().snapshots().get(0);
        BoardModel.Meeting meeting = world.boardOfCompany(A).orElseThrow().board()
                .meeting(snapshot.meetingId());
        for (int i = 0; i < DIRECTORS.length; i++) {
            world.scheduleEngineeringAttend("pd-a-" + i, "pd-a-" + i + "-task",
                    snapshot.capturedAt() + 10L + i, A, DIRECTORS[i], snapshot.meetingId(), false);
        }
        world.advanceTo(meeting.opensAt() + 1_000L);
        world.schedulePeriodicAction("prep", "prep-task", meeting.opensAt() + 2_000L, A,
                "dave", "PREPARE", 1L, "", 0L, "", 0L, "", List.of(), "", 100L);
        world.advanceTo(meeting.opensAt() + 3_000L);
        String draftId = world.periodicOf(A).orElseThrow().drafts().get(0).draftId();
        String docId = world.periodicOf(A).orElseThrow().drafts().get(0).docId();
        for (int i = 0; i < 3; i++) {
            world.scheduleStartReading("rd-" + i, "rd-" + i + "-task",
                    meeting.opensAt() + 4_000L + i * 2L, DIRECTORS[i], docId, 1L);
        }
        world.advanceTo(meeting.opensAt() + 4_000L
                + PeriodicWorldEngine.MATERIAL_READ_MILLIS + 1_000L);
        String metrics = "[{\"dimension\":\"CASH_SOLVENCY\",\"score\":90,"
                + "\"sourceRef\":\"account:ENG-A\",\"asOf\":" + snapshot.capturedAt() + "}]";
        for (int i = 0; i < 3; i++) {
            tool(world, DIRECTORS[i], "sc-" + i, "periodic.score",
                    "{\"companyPartyId\":\"eng-a\",\"period\":1,\"metrics\":" + metrics + "}");
        }
        world.advanceTo(meeting.closesAt() + 10L);
        for (int i = 0; i < 3; i++) {
            tool(world, DIRECTORS[i], "ap-" + i, "periodic.approve",
                    "{\"companyPartyId\":\"eng-a\",\"period\":1,\"draftId\":\"" + draftId
                            + "\",\"draftVersion\":1}");
        }
        return draftId;
    }

    private static void authorize(WorldRuntime world, String draftId, String publisher,
            long expiresAt) throws Exception {
        tool(world, "dave", "au-1", "periodic.authorize",
                "{\"companyPartyId\":\"eng-a\",\"period\":1,\"draftId\":\"" + draftId
                        + "\",\"draftVersion\":1,\"publisherPersonId\":\"" + publisher
                        + "\",\"expiresAt\":" + expiresAt + "}");
    }

    @Test
    void designatedNonManagerPublisherUsesOwnProjection() throws Exception {
        Path file = journal("publisher-own");
        WorldRuntime world = open(file);
        String draftId = preparedApproved(world);
        assertEquals(3, world.periodicOf(A).orElseThrow().approvals().size());
        authorize(world, draftId, "alice", world.snapshot().timeMillis() + DAY);
        // 经理仍能看到本公司本期的有效未消费授权（同一行投影，签发前复核用）。
        java.util.Map<String, Object> managerView = world.periodicRoleView("dave", A);
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> managerRows =
                (java.util.List<java.util.Map<String, Object>>) managerView.get("periods");
        assertTrue(managerRows.get(0).get("pendingAuthorization") instanceof java.util.Map,
                "经理必须仍能看到本公司有效未消费授权：" + managerRows.get(0));

        // 发布人只看自己的 v6 输入：公司/期间/草稿/授权都必须来自投影。
        DecisionRun.Call call = openCall(world, "alice", "pu-own");
        com.fasterxml.jackson.databind.JsonNode node = directorshipOf(call.inputText(), A);
        com.fasterxml.jackson.databind.JsonNode row = node.path("periodic").get(0);
        assertNotNull(row, "发布人必须有本人周期投影");
        assertTrue(call.allowedTools().contains(RoleTradingView.TOOL_PERIODIC_PUBLISH),
                "发布工具必须在允许列表里");
        com.fasterxml.jackson.databind.JsonNode grant = row.path("pendingAuthorization");
        assertTrue(grant.isObject(), "指定发布人必须在本人输入里看到自己的有效授权：" + row);
        assertEquals("alice", grant.path("publisherPersonId").asText());
        assertEquals(draftId, grant.path("draftId").asText());
        assertEquals(1L, grant.path("draftVersion").asLong());
        String authorizationId = grant.path("authorizationId").asText();
        assertFalse(authorizationId.isBlank(), "授权标识不能为空");

        String body = "{\"tool\":\"periodic.publish\",\"args\":{\"companyPartyId\":\""
                + node.path("companyPartyId").asText()
                + "\",\"period\":1,\"draftId\":\"" + grant.path("draftId").asText()
                + "\",\"draftVersion\":" + grant.path("draftVersion").asLong()
                + ",\"authorizationId\":\"" + authorizationId + "\"}}";
        reply(world, call, body);
        assertEquals(1, world.periodicOf(A).orElseThrow().releases().size(),
                "指定非经理董事必须能用本人输入完成发布；回报="
                        + world.snapshot().engineering().orElseThrow().reports().values().stream()
                        .filter(entry -> entry.commandId().contains("pu-own")
                                || entry.taskId().contains("pu-own"))
                        .map(entry -> entry.kind() + "/" + entry.reason() + "@" + entry.position())
                        .toList());
        String publicDocId = PeriodicWorldEngine.publicDocId(A, 1L);
        InformationModel.DocumentVersion material = InformationModel.version(
                world.snapshot().information().orElseThrow().information(),
                row.path("myDraft").path("docId").asText(), 1L).orElseThrow();
        assertEquals(material.body(), InformationModel.version(
                world.snapshot().information().orElseThrow().information(), publicDocId, 1L)
                .orElseThrow().body(), "PUBLIC 正文必须逐字等于被批私稿");

        // 已消费：本人再打开时不再出现可执行授权。
        DecisionRun.Call again = openCall(world, "alice", "pu-again");
        com.fasterxml.jackson.databind.JsonNode consumedRow = periodicRow(again.inputText(), A, 1L);
        assertFalse(consumedRow.path("pendingAuthorization").isObject(),
                "已消费授权不得再作为可执行授权出现");
        world.close();
    }

    @Test
    void otherSeatAndOtherCompanySeeNoGrant() throws Exception {
        Path file = journal("grant-scope");
        WorldRuntime world = open(file);
        String draftId = preparedApproved(world);
        authorize(world, draftId, "alice", world.snapshot().timeMillis() + DAY);
        String authorizationId = world.periodicOf(A).orElseThrow().authorizations().get(0)
                .authorizationId();

        // 同公司其他董事：看不到别人的授权，也不能拿别人的授权发布。
        DecisionRun.Call bob = openCall(world, "bob", "pu-bob");
        com.fasterxml.jackson.databind.JsonNode bobRow = periodicRow(bob.inputText(), A, 1L);
        assertNotNull(bobRow);
        assertFalse(bobRow.path("pendingAuthorization").isObject(),
                "他董事不得看到别人的授权：" + bobRow);
        reply(world, bob, "{\"tool\":\"periodic.publish\",\"args\":{\"companyPartyId\":\"eng-a\","
                + "\"period\":1,\"draftId\":\"" + draftId + "\",\"draftVersion\":1,"
                + "\"authorizationId\":\"" + authorizationId + "\"}}");
        assertTrue(world.periodicOf(A).orElseThrow().releases().isEmpty(),
                "非指定董事不能借用他人的授权发布");

        // 他公司董事：eng-b 的行里没有任何 eng-a 的授权，跨公司发布同样失败。
        DecisionRun.Call rita = openCall(world, "rita", "pu-rita");
        com.fasterxml.jackson.databind.JsonNode ritaRow = periodicRow(rita.inputText(),
                WorldWebScenario.ENGINE_B, 1L);
        assertNotNull(ritaRow, "eng-b 董事应看到本公司行");
        assertFalse(ritaRow.path("pendingAuthorization").isObject(),
                "他公司不得看到 eng-a 的授权：" + ritaRow);
        reply(world, rita, "{\"tool\":\"periodic.publish\",\"args\":{\"companyPartyId\":\""
                + WorldWebScenario.ENGINE_B + "\",\"period\":1,\"draftId\":\"" + draftId
                + "\",\"draftVersion\":1,\"authorizationId\":\"" + authorizationId + "\"}}");
        assertTrue(world.periodicOf(A).orElseThrow().releases().isEmpty());
        world.close();
    }

    @Test
    void expiredGrantIsNotOfferedNorExecutable() throws Exception {
        Path file = journal("grant-expiry");
        WorldRuntime world = open(file);
        String draftId = preparedApproved(world);
        // 授权在工具 FINISH 时才真正签发，失效时刻必须晚于签发时刻。
        long expiresAt = world.snapshot().timeMillis() + 2L * DAY;
        authorize(world, draftId, "alice", expiresAt);
        String authorizationId = world.periodicOf(A).orElseThrow().authorizations().get(0)
                .authorizationId();
        world.advanceTo(expiresAt + 1L);

        DecisionRun.Call call = openCall(world, "alice", "pu-expired");
        com.fasterxml.jackson.databind.JsonNode row = periodicRow(call.inputText(), A, 1L);
        assertFalse(row.path("pendingAuthorization").isObject(),
                "过期授权不得作为可执行授权提供：" + row);
        reply(world, call, "{\"tool\":\"periodic.publish\",\"args\":{\"companyPartyId\":\"eng-a\","
                + "\"period\":1,\"draftId\":\"" + draftId + "\",\"draftVersion\":1,"
                + "\"authorizationId\":\"" + authorizationId + "\"}}");
        assertTrue(world.periodicOf(A).orElseThrow().releases().isEmpty(),
                "过期授权不得发布");
        List<String> reasons = world.snapshot().engineering().orElseThrow().reports().values()
                .stream()
                .filter(entry -> PendingTask.ENGINEERING_PERIODIC_V1.equals(entry.kind()))
                .filter(entry -> entry.commandId().contains("pu-expired"))
                .map(EngineeringRun.Report::reason).toList();
        assertTrue(reasons.contains("APPROVAL_EXPIRED"), "拒绝原因：" + reasons);
        // 历史记录保留：授权仍在状态里，只是不可执行。
        assertEquals(1, world.periodicOf(A).orElseThrow().authorizations().size());
        world.close();
    }

    @Test
    void authorizedNotPublishedSurvivesReopenForSamePublisher() throws Exception {
        Path file = journal("publisher-reopen");
        WorldRuntime world = open(file);
        String draftId = preparedApproved(world);
        authorize(world, draftId, "alice", world.snapshot().timeMillis() + DAY);
        String authorizationId = world.periodicOf(A).orElseThrow().authorizations().get(0)
                .authorizationId();
        world.close();

        try (WorldRuntime reopened = open(file)) {
            DecisionRun.Call call = openCall(reopened, "alice", "pu-reopen");
            com.fasterxml.jackson.databind.JsonNode row = periodicRow(call.inputText(), A, 1L);
            com.fasterxml.jackson.databind.JsonNode grant = row.path("pendingAuthorization");
            assertTrue(grant.isObject(), "跨 JVM 后仍须给出本人授权：" + row);
            assertEquals(authorizationId, grant.path("authorizationId").asText());
            String body = "{\"tool\":\"periodic.publish\",\"args\":{\"companyPartyId\":\"eng-a\","
                    + "\"period\":1,\"draftId\":\"" + grant.path("draftId").asText()
                    + "\",\"draftVersion\":" + grant.path("draftVersion").asLong()
                    + ",\"authorizationId\":\"" + authorizationId + "\"}}";
            reply(reopened, call, body);
            assertEquals(1, reopened.periodicOf(A).orElseThrow().releases().size(),
                    "重开后续接必须能用本人参数完成发布");
        }
    }
}
