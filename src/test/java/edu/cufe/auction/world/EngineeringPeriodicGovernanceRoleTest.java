package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独审 F1/F2/F3 的修复验收：章程公司（制造）的周期链从本人 v6 输入完成；回避按草稿所属会议
 * 精确判定；无成员身份的人物投影不泄露他公司内容而 God 审计完整。
 *
 * <p>所有周期动作都经真实回执分派与 BEGIN→FINISH；参数只从本人输入取，不用 God 状态直注。</p>
 */
class EngineeringPeriodicGovernanceRoleTest {

    private static final long DAY = 86_400_000L;
    private static final long PERIOD = PeriodicModel.PERIOD_MILLIS;
    private static final String C = WorldWebScenario.COMPANY;
    private static final String A = WorldWebScenario.ENGINE_A;
    private static final String[] CUFE_SEATS = {"alice", "bob", "carol"};
    private static final String[] ENG_SEATS = {"alice", "bob", "carol", "dave", "erin"};

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "engineering-periodic-governance-role-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    /** 章程公司 cufe：erin 真实任职为经理（席位预算由 18 档配置补齐）。 */
    private static WorldRuntime openCharter(Path file) throws Exception {
        return WorldWebScenario.engineeringRuntimeWithAppointments(file,
                WorldWebScenario.engineeringConfig(),
                List.of(new RoleAccess.Appointment("CUFE-MGR", "erin", C,
                        RoleAccess.Role.COMPANY_MANAGER, 0L, 900L * DAY, null)));
    }

    private static WorldRuntime openEngineering(Path file) throws Exception {
        return WorldWebScenario.engineeringRuntime(file, WorldWebScenario.engineeringConfig());
    }

    private static DecisionRun.Call openCall(WorldRuntime world, String person, String requestId)
            throws Exception {
        world.beginDecisionSession("open-" + requestId);
        String opened = world.openDecisions("dec-" + requestId, List.of(new DecisionRun.Request(person,
                requestId, "wake", new DecisionModel.Route("governance", "key-1",
                        "deepseek-flash", 1L, 20, 1_000_000, 1_024, 1))));
        WorldRuntime.DispatchResult dispatched = world.dispatchLiveDecision("cmd-" + requestId,
                requestId);
        assertNotNull(dispatched.call(), "派发失败：" + dispatched.verdict() + "/"
                + dispatched.reason() + " open=" + opened + " person=" + person + " decisions="
                + world.snapshot().decisions().map(run -> run.state().decisions().stream()
                        .map(d -> d.actionId() + ":" + d.status() + ":" + d.outcome()).toList())
                .orElse(List.of("no-run")));
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

    private static void tool(WorldRuntime world, String person, String requestId, String tool,
            String argsJson) throws Exception {
        reply(world, openCall(world, person, requestId),
                "{\"tool\":\"" + tool + "\",\"args\":" + argsJson + "}");
    }

    private static JsonNode input(String text) throws Exception {
        JsonNode root = new ObjectMapper().readTree(text);
        assertEquals("role-decision-tools-v6", root.path("toolContractVersion").asText());
        return root;
    }

    /** 从本人输入里取某公司的董事/经理节点；没有则返回 null。 */
    private static JsonNode projectionOf(String text, String section, String company)
            throws Exception {
        for (JsonNode node : input(text).path("engineering").path(section)) {
            if (company.equals(node.path("companyPartyId").asText())) {
                return node;
            }
        }
        return null;
    }

    private static JsonNode rowOf(JsonNode projection, long period) {
        assertNotNull(projection, "本人输入缺少该公司投影");
        for (JsonNode row : projection.path("periodic")) {
            if (row.path("period").asLong() == period) {
                return row;
            }
        }
        throw new AssertionError("本人输入缺少本期行：" + projection);
    }

    private static List<String> reasons(WorldRuntime world, String commandFragment) {
        return world.snapshot().engineering().orElseThrow().reports().values().stream()
                .filter(entry -> PendingTask.ENGINEERING_PERIODIC_V1.equals(entry.kind()))
                .filter(entry -> entry.commandId().contains(commandFragment))
                .map(EngineeringRun.Report::reason).toList();
    }

    private static void scheduleReading(WorldRuntime world, String person, String docId, long at)
            throws Exception {
        world.scheduleStartReading("rd-" + person, "rd-" + person + "-task", at, person, docId, 1L);
    }

    @Test
    void charterCompanyRunsFullV6ChainWithDesignatedPublisher() throws Exception {
        Path file = journal("cufe-chain");
        WorldRuntime world = openCharter(file);
        world.advanceTo(PERIOD + 5L);
        PeriodicModel.Snapshot snapshot = world.periodicOf(C).orElseThrow().snapshots().get(0);
        BoardModel.Meeting meeting = world.boardOfCompany(C).orElseThrow().board()
                .meeting(snapshot.meetingId());
        for (int i = 0; i < CUFE_SEATS.length; i++) {
            world.schedulePeriodicAction("c-at-" + i, "c-at-" + i + "-task",
                    snapshot.capturedAt() + 1_000L + i, C, CUFE_SEATS[i], "ATTEND", 1L, "", 0L,
                    "", 0L, "", List.of(), "", 100L);
        }
        world.advanceTo(meeting.opensAt() + 500L);

        // 经理 erin 只从本人输入取公司/期间/材料，完成真实准备。
        DecisionRun.Call prep = openCall(world, "erin", "c-prep");
        JsonNode manager = projectionOf(prep.inputText(), "managerships", C);
        JsonNode managerRow = rowOf(manager, 1L);
        assertEquals(snapshot.capturedAt(), managerRow.path("capturedAt").asLong());
        assertEquals("periodic-material:" + C + ":1", managerRow.path("materialDocId").asText());
        String evidence = "";
        for (JsonNode entry : managerRow.path("evidence")) {
            if (entry.path("dimensions").toString().contains("CASH_SOLVENCY")) {
                evidence = entry.path("ref").asText();
            }
        }
        assertTrue(evidence.startsWith("account:"), "制造周期证据必须是真实账户引用：" + managerRow);
        assertTrue(prep.allowedTools().contains(RoleTradingView.TOOL_PERIODIC_PREPARE));
        assertTrue(prep.allowedTools().contains(RoleTradingView.TOOL_PERIODIC_AUTHORIZE));
        reply(world, prep, "{\"tool\":\"periodic.prepare\",\"args\":{\"companyPartyId\":\""
                + manager.path("companyPartyId").asText() + "\",\"period\":1,\"note\":\"\"}}");

        String docId = managerRow.path("materialDocId").asText();
        long readBase = world.snapshot().timeMillis() + 1_000L;
        for (int i = 0; i < CUFE_SEATS.length; i++) {
            scheduleReading(world, CUFE_SEATS[i], docId, readBase + i * 2L);
        }
        world.advanceTo(readBase + PeriodicWorldEngine.MATERIAL_READ_MILLIS + 1_000L);

        // 三位董事从本人输入取草稿/证据/期间并真实评分。
        for (int i = 0; i < CUFE_SEATS.length; i++) {
            DecisionRun.Call call = openCall(world, CUFE_SEATS[i], "c-sc-" + CUFE_SEATS[i]);
            JsonNode node = projectionOf(call.inputText(), "directorships", C);
            JsonNode row = rowOf(node, 1L);
            assertEquals(docId, row.path("myDraft").path("docId").asText());
            String ref = "";
            for (JsonNode entry : row.path("evidence")) {
                if (entry.path("dimensions").toString().contains("CASH_SOLVENCY")) {
                    ref = entry.path("ref").asText();
                }
            }
            reply(world, call, "{\"tool\":\"periodic.score\",\"args\":{\"companyPartyId\":\""
                    + node.path("companyPartyId").asText() + "\",\"period\":1,\"metrics\":["
                    + "{\"dimension\":\"CASH_SOLVENCY\",\"score\":85,\"sourceRef\":\"" + ref
                    + "\",\"asOf\":" + row.path("capturedAt").asLong() + "}]}}");
        }
        assertEquals(3, world.periodicOf(C).orElseThrow().ballots().size(),
                "三位董事必须能用本人输入评分：" + reasons(world, "c-sc-"));

        world.advanceTo(meeting.closesAt() + 10L);
        for (int i = 0; i < CUFE_SEATS.length; i++) {
            DecisionRun.Call call = openCall(world, CUFE_SEATS[i], "c-ap-" + CUFE_SEATS[i]);
            JsonNode node = projectionOf(call.inputText(), "directorships", C);
            JsonNode row = rowOf(node, 1L);
            reply(world, call, "{\"tool\":\"periodic.approve\",\"args\":{\"companyPartyId\":\""
                    + node.path("companyPartyId").asText() + "\",\"period\":1,\"draftId\":\""
                    + row.path("myDraft").path("draftId").asText() + "\",\"draftVersion\":1}}");
        }
        assertEquals(3, world.periodicOf(C).orElseThrow().approvals().size());

        // 经理指定非经理董事 bob 发布；bob 之后只从本人输入取授权并发布。
        long expiresAt = world.snapshot().timeMillis() + DAY;
        DecisionRun.Call auth = openCall(world, "erin", "c-auth");
        JsonNode authRow = rowOf(projectionOf(auth.inputText(), "managerships", C), 1L);
        reply(world, auth, "{\"tool\":\"periodic.authorize\",\"args\":{\"companyPartyId\":\""
                + C + "\",\"period\":1,\"draftId\":\""
                + authRow.path("myDraft").path("draftId").asText() + "\",\"draftVersion\":1,"
                + "\"publisherPersonId\":\"bob\",\"expiresAt\":" + expiresAt + "}}");
        assertEquals(1, world.periodicOf(C).orElseThrow().authorizations().size());
        world.close();

        try (WorldRuntime reopened = openCharter(file)) {
            DecisionRun.Call call = openCall(reopened, "bob", "c-pub");
            JsonNode node = projectionOf(call.inputText(), "directorships", C);
            JsonNode row = rowOf(node, 1L);
            JsonNode grant = row.path("pendingAuthorization");
            assertTrue(grant.isObject(), "指定发布人必须从本人输入拿到授权：" + row);
            assertEquals("bob", grant.path("publisherPersonId").asText());
            reply(reopened, call, "{\"tool\":\"periodic.publish\",\"args\":{\"companyPartyId\":\""
                    + node.path("companyPartyId").asText() + "\",\"period\":1,\"draftId\":\""
                    + grant.path("draftId").asText() + "\",\"draftVersion\":1,"
                    + "\"authorizationId\":\"" + grant.path("authorizationId").asText() + "\"}}");
            assertEquals(1, reopened.periodicOf(C).orElseThrow().releases().size(),
                    "制造披露链必须在重开后仍能从本人输入完成："
                            + reasons(reopened, "c-pub"));
            InformationModel.DocumentVersion publicDoc = InformationModel.version(
                    reopened.snapshot().information().orElseThrow().information(),
                    PeriodicWorldEngine.publicDocId(C, 1L), 1L).orElseThrow();
            InformationModel.DocumentVersion material = InformationModel.version(
                    reopened.snapshot().information().orElseThrow().information(),
                    row.path("myDraft").path("docId").asText(), 1L).orElseThrow();
            assertEquals(material.body(), publicDoc.body(), "PUBLIC 正文必须逐字等于被批私稿");
        }
    }

    @Test
    void recusedDirectorCannotApproveAndThreeOthersStillAuthorize() throws Exception {
        Path file = journal("recuse-authorize");
        WorldRuntime world = openEngineering(file);
        appointManager(world);
        world.advanceTo(PERIOD + 5L);
        PeriodicModel.Snapshot snapshot = world.periodicOf(A).orElseThrow().snapshots().get(0);
        BoardModel.Meeting meeting = world.boardOfCompany(A).orElseThrow().board()
                .meeting(snapshot.meetingId());
        for (int i = 0; i < ENG_SEATS.length; i++) {
            boolean recused = "erin".equals(ENG_SEATS[i]);
            // Web 的 board.attend 使用此公开入口，必须保存真正的自主回避。
            world.scheduleEngineeringAttend("at-" + i, "at-" + i + "-task",
                    snapshot.capturedAt() + 1_000L + i, A, ENG_SEATS[i],
                    snapshot.meetingId(), recused);
        }
        world.advanceTo(meeting.opensAt() + 500L);
        assertEquals(List.of("ea5"), world.boardOfCompany(A).orElseThrow().board()
                .meeting(snapshot.meetingId()).recusedSeatIds(), "erin 必须在本会登记回避；回报="
                + world.snapshot().engineering().orElseThrow().reports().values().stream()
                .filter(entry -> entry.commandId().contains("at-"))
                .map(entry -> entry.commandId() + "/" + entry.kind() + "/" + entry.reason())
                .toList());
        world.schedulePeriodicAction("prep", "prep-task", world.snapshot().timeMillis() + 1_000L,
                A, "dave", "PREPARE", 1L, "", 0L, "", 0L, "", List.of(), "", 100L);
        world.advanceTo(world.snapshot().timeMillis() + 1_000L);
        String docId = world.periodicOf(A).orElseThrow().drafts().get(0).docId();
        long readBase = world.snapshot().timeMillis() + 1_000L;
        for (int i = 0; i < 4; i++) {
            scheduleReading(world, ENG_SEATS[i], docId, readBase + i * 2L);
        }
        world.advanceTo(readBase + PeriodicWorldEngine.MATERIAL_READ_MILLIS + 1_000L);
        world.advanceTo(meeting.closesAt() + 10L);

        String draftId = world.periodicOf(A).orElseThrow().drafts().get(0).draftId();
        tool(world, "erin", "ap-erin", "periodic.approve",
                "{\"companyPartyId\":\"eng-a\",\"period\":1,\"draftId\":\"" + draftId
                        + "\",\"draftVersion\":1}");
        assertEquals("SEAT_RECUSED", reasons(world, "ap-erin").get(0),
                "本会已回避的董事不得在会后批准");
        for (String person : List.of("alice", "bob", "carol")) {
            tool(world, person, "ap-" + person, "periodic.approve",
                    "{\"companyPartyId\":\"eng-a\",\"period\":1,\"draftId\":\"" + draftId
                            + "\",\"draftVersion\":1}");
        }
        assertEquals(3, world.periodicOf(A).orElseThrow().approvals().size());
        long expiresAt = world.snapshot().timeMillis() + DAY;
        tool(world, "dave", "auth-1", "periodic.authorize",
                "{\"companyPartyId\":\"eng-a\",\"period\":1,\"draftId\":\"" + draftId
                        + "\",\"draftVersion\":1,\"publisherPersonId\":\"bob\",\"expiresAt\":"
                        + expiresAt + "}");
        assertEquals(1, world.periodicOf(A).orElseThrow().authorizations().size(),
                "回避董事不计入后仍有 3 名合法董事，授权应成立");
        assertEquals(List.of("alice", "bob", "carol"),
                world.periodicOf(A).orElseThrow().authorizations().get(0).approverPersonIds());
        world.close();
    }

    @Test
    void recusalFromAnotherMeetingDoesNotBlockThisMeeting() throws Exception {
        Path file = journal("recuse-other");
        WorldRuntime world = openEngineering(file);
        appointManager(world);
        world.advanceTo(PERIOD + 5L);
        PeriodicModel.Snapshot snapshot = world.periodicOf(A).orElseThrow().snapshots().get(0);
        BoardModel.Meeting meeting = world.boardOfCompany(A).orElseThrow().board()
                .meeting(snapshot.meetingId());
        // 另一场并行会议在批准时点仍然开着；alice 只在那场回避。
        long sideOpens = meeting.opensAt() + 1L;
        long sideCloses = meeting.closesAt() + DAY;
        world.scheduleEngineeringConvene("side-c", "side-c-task",
                world.snapshot().timeMillis() + 100L, A, "alice", "side-meeting",
                sideOpens - 1_000L, sideOpens, sideCloses);
        world.scheduleEngineeringAttend("side-a", "side-a-task", sideOpens + 1L, A, "alice",
                "side-meeting", true);
        world.schedulePeriodicAction("prep", "prep-task", sideOpens + 1_000L, A, "dave",
                "PREPARE", 1L, "", 0L, "", 0L, "", List.of(), "", 100L);
        world.advanceTo(sideOpens + 2_000L);
        String docId = world.periodicOf(A).orElseThrow().drafts().get(0).docId();
        long readBase = world.snapshot().timeMillis() + 1_000L;
        scheduleReading(world, "alice", docId, readBase);
        world.advanceTo(readBase + PeriodicWorldEngine.MATERIAL_READ_MILLIS + 1_000L);
        world.advanceTo(meeting.closesAt() + 10L);

        String draftId = world.periodicOf(A).orElseThrow().drafts().get(0).draftId();
        tool(world, "alice", "ap-side", "periodic.approve",
                "{\"companyPartyId\":\"eng-a\",\"period\":1,\"draftId\":\"" + draftId
                        + "\",\"draftVersion\":1}");
        assertEquals(1, world.periodicOf(A).orElseThrow().approvals().size(),
                "只在另一场会议回避，不得误伤本会批准：" + reasons(world, "ap-side"));
        world.close();
    }

    @Test
    void fewerThanThreeApprovalsCannotAuthorize() throws Exception {
        Path file = journal("two-approvals");
        WorldRuntime world = openCharter(file);
        world.advanceTo(PERIOD + 5L);
        PeriodicModel.Snapshot snapshot = world.periodicOf(C).orElseThrow().snapshots().get(0);
        BoardModel.Meeting meeting = world.boardOfCompany(C).orElseThrow().board()
                .meeting(snapshot.meetingId());
        world.schedulePeriodicAction("at-a", "at-a-task", snapshot.capturedAt() + 1_000L, C, "alice",
                "ATTEND", 1L, "", 0L, "", 0L, "", List.of(), "RECUSE", 100L);
        world.schedulePeriodicAction("at-b", "at-b-task", snapshot.capturedAt() + 1_001L, C, "bob",
                "ATTEND", 1L, "", 0L, "", 0L, "", List.of(), "", 100L);
        world.schedulePeriodicAction("at-c", "at-c-task", snapshot.capturedAt() + 1_002L, C, "carol",
                "ATTEND", 1L, "", 0L, "", 0L, "", List.of(), "", 100L);
        world.advanceTo(meeting.opensAt() + 500L);
        world.schedulePeriodicAction("prep", "prep-task", world.snapshot().timeMillis() + 1_000L,
                C, "erin", "PREPARE", 1L, "", 0L, "", 0L, "", List.of(), "", 100L);
        world.advanceTo(world.snapshot().timeMillis() + 1_000L);
        String docId = world.periodicOf(C).orElseThrow().drafts().get(0).docId();
        long readBase = world.snapshot().timeMillis() + 1_000L;
        for (int i = 0; i < 3; i++) {
            scheduleReading(world, CUFE_SEATS[i], docId, readBase + i * 2L);
        }
        world.advanceTo(readBase + PeriodicWorldEngine.MATERIAL_READ_MILLIS + 1_000L);
        world.advanceTo(meeting.closesAt() + 10L);

        String draftId = world.periodicOf(C).orElseThrow().drafts().get(0).draftId();
        tool(world, "alice", "ap-recused", "periodic.approve",
                "{\"companyPartyId\":\"cufe\",\"period\":1,\"draftId\":\"" + draftId
                        + "\",\"draftVersion\":1}");
        assertEquals("SEAT_RECUSED", reasons(world, "ap-recused").get(0));
        for (String person : List.of("bob", "carol")) {
            tool(world, person, "ap-" + person, "periodic.approve",
                    "{\"companyPartyId\":\"cufe\",\"period\":1,\"draftId\":\"" + draftId
                            + "\",\"draftVersion\":1}");
        }
        assertEquals(2, world.periodicOf(C).orElseThrow().approvals().size());
        tool(world, "erin", "auth-two", "periodic.authorize",
                "{\"companyPartyId\":\"cufe\",\"period\":1,\"draftId\":\"" + draftId
                        + "\",\"draftVersion\":1,\"publisherPersonId\":\"bob\",\"expiresAt\":"
                        + (world.snapshot().timeMillis() + DAY) + "}");
        assertTrue(world.periodicOf(C).orElseThrow().authorizations().isEmpty(),
                "不足三名合法董事不得签发披露授权");
        assertEquals("DRAFT_NOT_APPROVED", reasons(world, "auth-two").get(0));
        world.close();
    }

    @Test
    void projectionRequiresMembershipButGodAuditStaysComplete() throws Exception {
        Path file = journal("projection-scope");
        WorldRuntime world = openEngineering(file);
        world.schedulePublishDocument("pub-news", "pub-news-task", 10L, "public-news",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "公开公告", "公众可见正文", "实验材料", 10L, List.of());
        world.advanceTo(PERIOD + 5L);

        assertTrue(world.periodicRoleView("rita", A).isEmpty(), "他公司董事不得看到 eng-a 周期内容");
        assertTrue(world.periodicRoleView("investor", A).isEmpty(), "普通人不得看到周期内容");
        assertFalse(world.periodicRoleView("alice", A).isEmpty(), "本公司董事必须看到自己的周期行");
        java.util.Map<String, Object> god = world.periodicGodView(A);
        assertTrue(god.containsKey("periods"), "God 审计必须保留期间行");
        assertTrue(god.containsKey("godPeriods"), "God 审计必须保留 position/摘要行");

        // 公众文档与真实阅读仍沿 Information，不因投影收紧被封禁。
        assertTrue(InformationModel.version(world.snapshot().information().orElseThrow()
                .information(), "public-news", 1L).isPresent());
        world.scheduleStartReading("inv-read", "inv-read-task", world.snapshot().timeMillis() + 1_000L,
                "investor", "public-news", 1L);
        world.advanceTo(world.snapshot().timeMillis() + 10L + 60_000L);
        assertTrue(world.snapshot().information().orElseThrow().information().closed().stream()
                        .anyMatch(closed -> closed.personPartyId().equals("investor")
                                && closed.docId().equals("public-news")
                                && closed.reason() == InformationModel.CloseReason.COMPLETED),
                "公众人物对公开文档的真实阅读必须保持可用");
        world.close();
    }

    /** dave 自 700 起为 ENG-A 经理（真实预算+任免链，与既有工具链测试一致）。 */
    private static void appointManager(WorldRuntime world) throws Exception {
        world.schedulePublishDocument("pub-budget", "pub-budget-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "预算材料", "实验预算材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        for (int i = 0; i < ENG_SEATS.length; i++) {
            world.scheduleStartReading("read-mat-" + ENG_SEATS[i],
                    "read-mat-" + ENG_SEATS[i] + "-task", 20L + i * 2L, ENG_SEATS[i],
                    "b-material", 1L);
            world.scheduleStartReading("read-appt-" + ENG_SEATS[i],
                    "read-appt-" + ENG_SEATS[i] + "-task", 60L + i * 2L, ENG_SEATS[i],
                    "appt-material", 1L);
        }
        world.scheduleEngineeringAppointmentConvene("mgr-c", "mgr-c-task", 400L, A, "alice",
                "mgr-meeting", 450L, 500L, 600L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("mgr-a-" + i, "mgr-a-" + i + "-task", 410L + i, A,
                    ENG_SEATS[i], "mgr-meeting", false);
        }
        world.scheduleEngineeringMaterial("mgr-m", "mgr-m-task", 430L, A, "alice",
                "mgr-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("mgr-p", "mgr-p-task", 440L, A, "alice",
                "mgr-meeting", List.of(), new BoardModel.AppointmentRequest("mgr-a", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        700L, 900L * DAY));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("mgr-v-" + i, "mgr-v-" + i + "-task", 520L + i, A,
                    ENG_SEATS[i], "mgr-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("mgr-i", "mgr-i-task", 600L, A, "alice",
                "mgr-meeting");
        world.advanceTo(700L);
    }
}
