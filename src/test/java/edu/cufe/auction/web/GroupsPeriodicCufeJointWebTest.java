package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.PeriodicModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认 engineering 新档的章程公司（cufe）真实 HTTP 链：主席用已有任免机制任命经理，
 * 再由经理准备、三名其它董事读/评/批、授权非经理本人精确发布。
 *
 * <p>开局就是 {@link WorldWebScenario.openGroups(Path)}（应用真实默认入口），
 * 不注入任何测试专用任职；所有动作只经回环 HTTP 与会话身份，参数取自本人响应。
 * 另含一次真正的新 JVM 续跑：另一进程打开同一日志，用已落盘授权完成发布。</p>
 */
class GroupsPeriodicCufeJointWebTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "cufe-board-web-bootstrap";
    private static final String COMPANY = WorldWebScenario.COMPANY;
    private static final String CHAIR = WorldWebScenario.CHAIR;
    private static final long DAY = 86_400_000L;
    private static final long PERIOD_ONE_CAPTURE = PeriodicModel.PERIOD_MILLIS;
    private static final long PERIOD_ONE_OPENS = PERIOD_ONE_CAPTURE + 86_400_000L;
    private static final long READ_MILLIS = 3_600_000L;
    private static final List<String> DUMMY_KEYS = List.of(
            "sk-dummy0000000000000001", "sk-dummy0000000000000002",
            "sk-dummy0000000000000003", "sk-dummy0000000000000004",
            "sk-dummy0000000000000005");

    private Path workDir;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;
    private String adminBootstrap = BOOTSTRAP;
    private final List<String> consoleLines = new ArrayList<>();
    private int sequence = 0;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("web.cufe.work", "target/web-cufe-tests"));
        workDir = root.resolve("cufe-" + System.nanoTime());
        Files.createDirectories(workDir);
    }

    @AfterEach
    void tearDown() {
        closeServer();
    }

    private void closeServer() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (host != null) {
            host.close();
            host = null;
        }
    }

    // ------------------------------------------------------------ 夹具

    /** 只开真实默认入口；不追加任何测试专用任职。 */
    private void start(Path journal) throws Exception {
        Path keys = workDir.resolve("keys-" + System.nanoTime() + ".txt");
        Files.writeString(keys, String.join("\n", DUMMY_KEYS), StandardCharsets.UTF_8);
        host = new WorldWebHost(() -> WorldWebScenario.openGroups(journal));
        server = new WorldWebServer(host, KeySlots.load(keys), SceneRoutes.open(workDir),
                0, BOOTSTRAP, null, consoleLines::add);
        server.start();
        base = server.baseUrl();
        cookie = null;
    }

    private void openDefaultWorld(Path journal) throws Exception {
        start(journal);
        loginAdmin();
        advanceTo(Long.toString(PERIOD_ONE_CAPTURE + 1L));
    }

    // ------------------------------------------------------------ 主链

    @Test
    @DisplayName("默认新档：cufe 主席任免出真实经理，再走完整周期披露链")
    void defaultArchiveAppointsManagerThenPublishesWithOwnGrant() throws Exception {
        Path journal = workDir.resolve("cufe-chain.journal");
        openDefaultWorld(journal);

        // ① 复现起点：期 1 已采样，唯一董事会可用，God 证据与人物证据一致。
        JsonNode god = god(COMPANY);
        JsonNode period1 = god.path("periodic").path("periods").get(0);
        assertEquals("1", period1.path("period").asText());
        assertEquals(Long.toString(PERIOD_ONE_CAPTURE), period1.path("capturedAt").asText());
        assertEquals("5", god.path("periodic").path("seatCount").asText());
        assertEquals("OPERATING_REVIEW", meetingRow(god, "periodic-meeting:cufe:1")
                .path("topic").asText(), "期 1 必须已由章程自动建会");
        JsonNode cashState = dimensionState(period1, "CASH_SOLVENCY");
        assertEquals("AWAITING_SCORE", cashState.path("state").asText(),
                "有 account:CO 证据但还没有人评分：" + period1);
        assertTrue(cashState.path("hasEvidence").asBoolean());
        assertEquals("DIMENSION_NOT_AVAILABLE",
                dimensionState(period1, "LONG_TERM_INVESTMENT").path("state").asText());
        for (JsonNode missing : period1.path("unavailableDimensions")) {
            assertNotEquals("CASH_SOLVENCY", missing.path("dimension").asText(),
                    "God 不得把已有现金证据的维度报成无合格证据");
        }

        // ② 人物侧同一事实 + 经理空缺是真实状态；工具表不列执行不了的授权工具。
        loginAdmin();
        personAt("erin");
        JsonNode erinCard = cardOf(getJson("/api/me/engineering"), COMPANY);
        assertEquals("MANUFACTURING", erinCard.path("template").asText());
        assertEquals("s5", erinCard.path("seatId").asText());
        assertTrue(erinCard.path("managerVacant").asBoolean(), "默认新档没有公司经理");
        List<String> erinTools = tools(erinCard);
        for (String required : List.of("board.convene", "board.attend", "board.addMaterial",
                "board.vote", "board.proposeAppointment", "board.implement")) {
            assertTrue(erinTools.contains(required), "缺可用董事会工具 " + required + "：" + erinTools);
        }
        assertFalse(erinTools.contains("board.proposeWaiver"),
                "制造章程公司没有工程合同豁免：" + erinTools);
        assertFalse(erinTools.contains("periodic.prepare"), "空缺经理不应拿到准备工具");
        JsonNode erinPeriod = erinCard.path("periodic").path("periods").get(0);
        String evidenceRef = evidenceRef(erinPeriod, "CASH_SOLVENCY");
        assertEquals("account:CO", evidenceRef);
        String evidenceAsOf = evidenceAsOf(erinPeriod, evidenceRef);
        assertEquals(Long.toString(PERIOD_ONE_CAPTURE), evidenceAsOf);

        // ②b 同档联合：同人（alice，董事+群主）先真实群读，再由非董事（rita）与退群边界对照。
        loginAdmin();
        personAt("alice");
        JsonNode groupPage = getJson("/api/me/groups");
        long groupPostDue = dueOf(groupPage, "POST");
        postJson("/api/me/groups/action", groupBody(groupPage, "POST",
                "{\"groupId\":\"" + WorldWebScenario.GROUPS_OPEN
                        + "\",\"title\":\"董事群内通报\",\"body\":\"董事同时在群里的发言\","
                        + "\"dueTimeMillis\":\"" + groupPostDue + "\"}"));
        loginAdmin();
        advanceTo(Long.toString(groupPostDue));
        advanceTo(Long.toString(groupPostDue + 5));
        loginAdmin();
        personAt("alice");
        JsonNode aliceGroups = getJson("/api/me/groups");
        String groupDoc = aliceGroups.path("posts").get(0).path("docId").asText();
        long groupReadDue = Long.parseLong(aliceGroups.path("timeMillis").asText()) + 1;
        postJson("/api/me/read", "{\"expectedPosition\":\""
                + aliceGroups.path("position").asText() + "\",\"commandId\":\"joint-read-"
                + System.nanoTime() + "\",\"taskId\":\"joint-read-task-" + System.nanoTime()
                + "\",\"dueTimeMillis\":\"" + groupReadDue + "\",\"docId\":\"" + groupDoc
                + "\",\"version\":\"1\"}");
        loginAdmin();
        advanceTo(Long.toString(groupReadDue + 20));
        loginAdmin();
        personAt("alice");
        assertEquals("董事同时在群里的发言",
                postOf(getJson("/api/me/groups"), groupDoc).path("body").asText(),
                "同人必须能真实读完群帖");
        assertTrue(!cardOf(getJson("/api/me/engineering"), COMPANY).path("seatId").asText().isEmpty(),
                "同一人仍是真实董事席位");

        // 非 cufe 董事（媒体记者 rita，非群成员）：页面不列 cufe，也无群帖；更正与未知同答
        loginAdmin();
        personAt("rita");
        JsonNode ritaView = getJson("/api/me/engineering");
        boolean hasCufe = false;
        for (JsonNode company : ritaView.path("companies")) {
            if (company.path("companyId").asText().equals(COMPANY)) {
                hasCufe = true;
            }
        }
        assertFalse(hasCufe, "rita 不是 cufe 董事，页面不得列出该公司的周期材料");
        JsonNode ritaGroups = getJson("/api/me/groups");
        assertEquals(0, ritaGroups.path("posts").size(), "非成员不得看到群帖");
        long ritaDue = dueOf(ritaGroups, "CORRECTION");
        String ritaExisting = postJson("/api/me/groups/action", groupBody(ritaGroups,
                "CORRECTION", "{\"docId\":\"" + groupDoc + "\",\"basedOnVersion\":\"1\","
                        + "\"title\":\"x\",\"body\":\"y\",\"dueTimeMillis\":\"" + ritaDue + "\"}"))
                .path("receipt").asText();
        JsonNode ritaGroups2 = getJson("/api/me/groups");
        long ritaUnknownDue = Long.parseLong(ritaGroups2.path("timeMillis").asText())
                + Long.parseLong(ritaGroups2.path("actionMillis").path("CORRECTION").asText());
        String ritaUnknown = postJson("/api/me/groups/action", groupBody(ritaGroups2,
                "CORRECTION", "{\"docId\":\"GDOC-forged\",\"basedOnVersion\":\"1\","
                        + "\"title\":\"x\",\"body\":\"y\",\"dueTimeMillis\":\""
                        + ritaUnknownDue + "\"}")).path("receipt").asText();
        assertEquals("NOT_AVAILABLE", ritaExisting);
        assertEquals(ritaExisting, ritaUnknown, "非成员不得区分帖子是否存在");

        // 退群边界：alice 退群后群帖不可见，但董事周期资格不受影响
        loginAdmin();
        personAt("alice");
        JsonNode beforeLeave = getJson("/api/me/groups");
        long leaveDue = dueOf(beforeLeave, "LEAVE");
        postJson("/api/me/groups/action", groupBody(beforeLeave, "LEAVE",
                "{\"groupId\":\"" + WorldWebScenario.GROUPS_OPEN
                        + "\",\"dueTimeMillis\":\"" + leaveDue + "\"}"));
        loginAdmin();
        advanceTo(Long.toString(leaveDue));
        loginAdmin();
        personAt("alice");
        assertEquals(0, getJson("/api/me/groups").path("posts").size(),
                "退群后不得再访问群帖");
        assertTrue(!cardOf(getJson("/api/me/engineering"), COMPANY).path("seatId").asText().isEmpty(),
                "退群不影响董事周期资格");

        loginAdmin();
        long now = now();
        loginAdmin();
        personAt(CHAIR);
        assertTrue(tools(cardOf(getJson("/api/me/engineering"), COMPANY))
                .contains("board.conveneAppointment"), "主席才拿到任免召集入口");

        // ③ 主席召集任免会议：排程不等于已开会。
        long conveneDue = now + 1_000L;
        long materialDeadline = now + 15_000L;
        long opensAt = now + 20_000L;
        long closesAt = now + 30_000L;
        JsonNode receipt = act("board.conveneAppointment", COMPANY, conveneDue,
                "\"meetingId\":\"mgr-appt-1\",\"materialDeadline\":\"" + materialDeadline
                        + "\",\"opensAt\":\"" + opensAt + "\",\"closesAt\":\"" + closesAt + "\"");
        assertEquals("board.conveneAppointment", receipt.path("tool").asText());
        loginAdmin();
        JsonNode scheduledTasks = god(COMPANY).path("tasks").path("rows");
        boolean scheduled = false;
        for (JsonNode task : scheduledTasks) {
            if ("CONVENE_APPOINTMENT".equals(task.path("tool").asText())
                    && "ENGINEERING_BOARD_V1".equals(task.path("wrappedKind").asText())
                    && "SCHEDULED".equals(task.path("state").asText())) {
                scheduled = true;
            }
        }
        assertTrue(scheduled, "排程后必须以 SCHEDULED 出现在待办里（不是已完成）：" + scheduledTasks);
        assertEquals(-1, meetingIndexByName(god(COMPANY), "mgr-appt-1"),
                "排程阶段不能已经有会议");
        advanceTo(Long.toString(conveneDue));
        JsonNode convened = meetingRow(god(COMPANY), "mgr-appt-1");
        assertEquals("MANAGER_APPOINTMENT", convened.path("topic").asText());
        assertEquals("CONVENED", convened.path("status").asText());
        assertEquals("s1", convened.path("convenerSeatId").asText());

        // ④ 四名董事到会（候选人 dave 不到会，避免自审）：任免材料对所有董事可读。
        for (String person : List.of(CHAIR, "bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            act("board.attend", COMPANY, now + 5_000L,
                    "\"meetingId\":\"mgr-appt-1\",\"recused\":false");
        }
        loginAdmin();
        advanceTo(Long.toString(now + 5_000L));
        assertEquals("s1,s2,s3,s5", meetingRow(god(COMPANY), "mgr-appt-1")
                .path("attendees").asText());

        // ⑤ 真实材料 + 已读：材料是本公司的开局治理说明（从本人卡片取真实 doc/version）。
        loginAdmin();
        personAt("bob");
        JsonNode bobCard = cardOf(getJson("/api/me/engineering"), COMPANY);
        String noteDoc = bobCard.path("governanceDocId").asText();
        String noteVersion = bobCard.path("governanceDocVersion").asText();
        assertEquals(WorldWebScenario.GOVERNANCE_NOTE_DOC, noteDoc,
                "本公司治理说明必须是真实发布的文档：" + bobCard);
        assertEquals("1", noteVersion);
        assertTrue(bobCard.path("governanceDocTitle").asText().contains("治理"));
        assertFalse(noteDoc.startsWith("engineering-opportunity:"),
                "任免材料不能拿工程机会文档充数");
        act("board.addMaterial", COMPANY, now + 6_000L,
                "\"meetingId\":\"mgr-appt-1\",\"materialId\":\"mgr-appt-mat-1\","
                        + "\"docId\":\"" + noteDoc + "\",\"docVersion\":\"" + noteVersion + "\"");
        loginAdmin();
        advanceTo(Long.toString(now + 6_000L));
        assertEquals("1", meetingRow(god(COMPANY), "mgr-appt-1")
                .path("materialCount").asText());

        for (String person : List.of("bob", "carol")) {
            loginAdmin();
            personAt(person);
            postJson("/api/me/read", readBody(personPosition(), noteDoc,
                    noteVersion, Long.toString(now + 7_000L)));
        }
        loginAdmin();
        advanceTo(Long.toString(now + 8_000L));

        loginAdmin();
        personAt("bob");
        act("board.proposeAppointment", COMPANY, now + 10_000L,
                "\"meetingId\":\"mgr-appt-1\",\"appointmentId\":\"cufe-mgr-1\","
                        + "\"appointPersonId\":\"dave\",\"appointRole\":\"COMPANY_MANAGER\","
                        + "\"appointAction\":\"APPOINT\",\"effectiveAt\":\"" + (now + 60_000L)
                        + "\",\"endsAt\":\"" + (now + 60_000L + 60L * DAY) + "\"");
        loginAdmin();
        advanceTo(Long.toString(now + 10_000L));

        // ⑥ 未读表决被真实拒绝：erin 还没读过材料。
        for (String person : List.of("bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            act("board.vote", COMPANY, now + 25_000L,
                    "\"meetingId\":\"mgr-appt-1\",\"vote\":\"YES\"");
        }
        loginAdmin();
        advanceTo(Long.toString(now + 25_000L));
        String ballotsAfterUnread = meetingRow(god(COMPANY), "mgr-appt-1")
                .path("ballots").asText();
        assertTrue(ballotsAfterUnread.contains("s2=YES"), ballotsAfterUnread);
        assertTrue(ballotsAfterUnread.contains("s3=YES"), ballotsAfterUnread);
        assertFalse(ballotsAfterUnread.contains("s5"),
                "未读材料的 erin 不能形成有效票：" + ballotsAfterUnread);

        // ⑦ erin 真读完再投票；关会通过；实施后 dave 才成为真实经理。
        loginAdmin();
        loginAdmin();
        personAt("erin");
        postJson("/api/me/read", readBody(personPosition(), noteDoc, noteVersion,
                Long.toString(now + 26_000L)));
        loginAdmin();
        advanceTo(Long.toString(now + 27_000L));
        loginAdmin();
        personAt("erin");
        act("board.vote", COMPANY, now + 28_000L,
                "\"meetingId\":\"mgr-appt-1\",\"vote\":\"YES\"");
        loginAdmin();
        advanceTo(Long.toString(now + 28_000L));
        assertTrue(meetingRow(god(COMPANY), "mgr-appt-1").path("ballots").asText()
                .contains("s5=YES"), "读完后补票必须有效");
        advanceTo(Long.toString(now + 30_000L));
        assertEquals("ADOPTED", meetingRow(god(COMPANY), "mgr-appt-1")
                .path("status").asText());

        loginAdmin();
        personAt("bob");
        act("board.implement", COMPANY, now + 31_000L, "\"meetingId\":\"mgr-appt-1\"");
        loginAdmin();
        advanceTo(Long.toString(now + 31_000L));
        assertEquals("IMPLEMENTED", meetingRow(god(COMPANY), "mgr-appt-1")
                .path("status").asText());
        advanceTo(Long.toString(now + 60_000L));
        loginAdmin();
        personAt("dave");
        JsonNode daveCard = cardOf(getJson("/api/me/engineering"), COMPANY);
        assertTrue(daveCard.path("manager").asBoolean(), "任免实施后 dave 才是真实经理");
        assertFalse(daveCard.path("managerVacant").asBoolean());
        assertTrue(tools(daveCard).contains("periodic.prepare"));

        // ⑧ 期 1 真实到会：主席自报回避，Root5 的 ATTEND_RECUSED 必须保留。
        for (String person : List.of("bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            act("periodic.attend", COMPANY, now + 61_000L, "\"period\":\"1\",\"recused\":false");
        }
        loginAdmin();
        loginAdmin();
        personAt(CHAIR);
        act("periodic.attend", COMPANY, now + 61_000L, "\"period\":\"1\",\"recused\":true");
        loginAdmin();
        advanceTo(Long.toString(now + 62_000L));
        JsonNode periodicMeeting = meetingRow(god(COMPANY), "periodic-meeting:cufe:1");
        assertTrue(periodicMeeting.path("attendees").asText().contains("s1"));
        assertTrue(periodicMeeting.path("recused").asText().contains("s1"),
                "回避必须在董事会记录里真实保留：" + periodicMeeting);

        // ⑨ 经理准备：排程前不出现草稿，FINISH 后才有。
        advanceTo(Long.toString(PERIOD_ONE_OPENS + 1_000L));
        loginAdmin();
        personAt("dave");
        JsonNode beforePrepare = cardOf(getJson("/api/me/engineering"), COMPANY)
                .path("periodic").path("periods").get(0);
        assertEquals(0, beforePrepare.path("drafts").size());
        act("periodic.prepare", COMPANY, PERIOD_ONE_OPENS + 2_000L, "\"period\":\"1\"");
        JsonNode stillScheduled = cardOf(getJson("/api/me/engineering"), COMPANY)
                .path("periodic").path("periods").get(0);
        assertEquals(0, stillScheduled.path("drafts").size(), "排程不等于已准备");
        loginAdmin();
        advanceTo(Long.toString(PERIOD_ONE_OPENS + 2_000L));
        JsonNode draftGod = god(COMPANY).path("periodic").path("periods").get(0);
        assertEquals(1, draftGod.path("drafts").size());
        assertEquals("dave", draftGod.path("latestDraft").path("preparedBy").asText());

        // ⑩ 三名其它董事读材料 → 评分 → 批准（准备者本人不批准自己的稿）。
        String materialDoc = null;
        String materialVersion = null;
        loginAdmin();
        long readDue = now() + 1_000L;
        for (String person : List.of("bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            JsonNode row = cardOf(getJson("/api/me/engineering"), COMPANY)
                    .path("periodic").path("periods").get(0);
            materialDoc = row.path("materialDocId").asText();
            materialVersion = row.path("materialVersion").asText();
            postJson("/api/me/read", readBody(personPosition(), materialDoc, materialVersion,
                    Long.toString(readDue)));
        }
        loginAdmin();
        advanceTo(Long.toString(readDue + READ_MILLIS + 1_000L));
        long scoreDue = 0;
        for (String person : List.of("bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            JsonNode row = cardOf(getJson("/api/me/engineering"), COMPANY)
                    .path("periodic").path("periods").get(0);
            assertTrue(row.path("myReadMaterial").asBoolean(), person + " 必须真实读完");
            String ref = evidenceRef(row, "CASH_SOLVENCY");
            scoreDue = personTime() + 1_000L;
            postJson("/api/me/engineering/action", scoreBody(COMPANY, personPosition(), ref,
                    "90", scoreDue));
        }
        loginAdmin();
        advanceTo(Long.toString(scoreDue));

        long approveDue = 0;
        for (String person : List.of("bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            JsonNode draft = cardOf(getJson("/api/me/engineering"), COMPANY)
                    .path("periodic").path("periods").get(0).path("latestDraft");
            approveDue = personTime() + 1_000L;
            act("periodic.approve", COMPANY, approveDue,
                    "\"period\":\"1\",\"draftId\":\"" + draft.path("draftId").asText()
                            + "\",\"draftVersion\":\"" + draft.path("version").asText() + "\"");
        }
        loginAdmin();
        advanceTo(Long.toString(approveDue));
        JsonNode approved = god(COMPANY).path("periodic").path("periods").get(0);
        assertEquals(3, approved.path("latestDraft").path("approvalCount").asInt());
        assertEquals("3", approved.path("latestDraft").path("requiredApprovals").asText());

        // ⑪ 经理授权非经理发布人；只有本人看到可执行授权。
        loginAdmin();
        personAt("dave");
        JsonNode managerDraft = cardOf(getJson("/api/me/engineering"), COMPANY)
                .path("periodic").path("periods").get(0).path("latestDraft");
        long authorizeDue = personTime() + 1_000L;
        act("periodic.authorize", COMPANY, authorizeDue,
                "\"period\":\"1\",\"draftId\":\"" + managerDraft.path("draftId").asText()
                        + "\",\"draftVersion\":\"" + managerDraft.path("version").asText()
                        + "\",\"publisherPersonId\":\"" + CHAIR + "\",\"expiresAt\":\""
                        + (authorizeDue + DAY) + "\"");
        loginAdmin();
        advanceTo(Long.toString(authorizeDue));
        loginAdmin();
        personAt(CHAIR);
        JsonNode grant = cardOf(getJson("/api/me/engineering"), COMPANY)
                .path("periodic").path("periods").get(0).path("authorizations");
        assertEquals(1, grant.size(), "本人应看到唯一可执行授权");
        assertTrue(grant.get(0).path("mine").asBoolean());
        String authorizationId = grant.get(0).path("authorizationId").asText();
        String grantDraft = grant.get(0).path("draftId").asText();
        String grantVersion = grant.get(0).path("draftVersion").asText();
        loginAdmin();
        loginAdmin();
        personAt("bob");
        assertEquals(0, cardOf(getJson("/api/me/engineering"), COMPANY).path("periodic")
                .path("periods").get(0).path("authorizations").size(),
                "别的董事看不到这条授权");

        // ⑫ 未读公开版本前没有正文；本人用自己响应里的授权发布；读完才见正文。
        loginAdmin();
        loginAdmin();
        personAt(CHAIR);
        long publishDue = personTime() + 1_000L;
        act("periodic.publish", COMPANY, publishDue,
                "\"period\":\"1\",\"draftId\":\"" + grantDraft + "\",\"draftVersion\":\""
                        + grantVersion + "\",\"authorizationId\":\"" + authorizationId + "\"");
        loginAdmin();
        advanceTo(Long.toString(publishDue));
        JsonNode published = god(COMPANY).path("periodic").path("periods").get(0);
        assertEquals(1, published.path("publication").path("versions").asInt());
        assertEquals("1", published.path("releases").get(0).path("publicVersion").asText(),
                "D1：God 必须给出真实公开版本 1");
        assertEquals("1", published.path("publication").path("latestVersion").asText());
        assertTrue(published.path("releases").get(0).path("bodyAvailable").asBoolean(),
                "D1：God 必须能从真实 Info 取到已发布正文");
        String publicBody = published.path("releases").get(0).path("body").asText();
        assertFalse(publicBody.isBlank(), "D1：已发布正文不能为空");
        assertTrue(publicBody.contains("累计收入="), publicBody);
        String publicDoc = published.path("publication").path("publicDocId").asText();
        assertEquals("company-statement:cufe:1", publicDoc);

        loginAdmin();
        personAt(CHAIR);
        JsonNode beforeRead = cardOf(getJson("/api/me/engineering"), COMPANY)
                .path("periodic").path("periods").get(0).path("releases").get(0);
        assertTrue(beforeRead.path("requiresReading").asBoolean());
        assertTrue(beforeRead.path("body").isNull());
        long publicReadDue = personTime() + 1_000L;
        postJson("/api/me/read", readBody(personPosition(), publicDoc,
                beforeRead.path("publicVersion").asText(), Long.toString(publicReadDue)));
        loginAdmin();
        advanceTo(Long.toString(publicReadDue + READ_MILLIS + 1_000L));
        loginAdmin();
        personAt(CHAIR);
        JsonNode afterRead = cardOf(getJson("/api/me/engineering"), COMPANY)
                .path("periodic").path("periods").get(0).path("releases").get(0);
        assertFalse(afterRead.path("requiresReading").asBoolean());
        assertEquals(publicBody, afterRead.path("body").asText());

        // ⑬ 关服后从真实日志核账：治理会议、任职、公开文档版本都对得上。
        closeServer();
        try (WorldRuntime world = WorldWebScenario.openGroups(journal).runtime()) {
            var snapshot = world.snapshot();
            var governance = snapshot.governance().orElseThrow();
            BoardModel.Meeting appointment = governance.board().meeting("mgr-appt-1");
            assertNotNull(appointment);
            assertEquals(BoardModel.MeetingStatus.IMPLEMENTED, appointment.status());
            assertEquals(java.util.Set.of("s2", "s3", "s5"),
                    java.util.Set.copyOf(appointment.ballots().keySet()),
                    "只有已读的三席能留下有效票");
            BoardModel.Meeting review = governance.board().meeting("periodic-meeting:cufe:1");
            assertTrue(review.recusedSeatIds().contains("s1"), "主席回避必须持久化");
            RoleAccess.Appointment manager = null;
            for (RoleAccess.Appointment candidate : snapshot.roles().orElseThrow()
                    .access().appointments()) {
                if (candidate.personPartyId().equals("dave")
                        && candidate.role() == RoleAccess.Role.COMPANY_MANAGER
                        && candidate.organizationPartyId().equals(COMPANY)) {
                    manager = candidate;
                }
            }
            assertNotNull(manager, "任免必须落成真实任职记录");
            assertEquals("cufe-mgr-1", manager.id());
            assertTrue(manager.activeAt(Long.parseLong(
                    published.path("releases").get(0).path("publishedAt").asText())));
            assertEquals(now + 60_000L + 60L * DAY, manager.effectiveEnd(),
                    "任职期限来自任免请求原文");
            InformationModel.State information = snapshot.information().orElseThrow()
                    .information();
            InformationModel.Document document = null;
            for (InformationModel.Document candidate : information.documents()) {
                if (candidate.docId().equals(publicDoc)) {
                    document = candidate;
                }
            }
            assertNotNull(document);
            assertEquals(1, document.versions().size());
            assertEquals(Long.parseLong(published.path("releases").get(0)
                    .path("publishedAt").asText()), document.versions().get(0).publishedAt());
            assertEquals(publicBody, document.versions().get(0).body());
            assertNotEquals(null, snapshot.engineering(), "工程子集必须仍在");
        }
    }

    // ------------------------------------------------------------ 负向与边界

    @Test
    @DisplayName("非主席/未知公司/空缺经理/人物越权都被真实拒绝")
    void rejectionsHoldOnDefaultArchive() throws Exception {
        Path journal = workDir.resolve("cufe-negative.journal");
        openDefaultWorld(journal);

        loginAdmin();
        long due = now() + 1_000L;
        loginAdmin();
        personAt("bob");
        // 非主席拿到普通召集工具，但核心按章程主席拒绝。
        HttpResponse<String> notChair = post("/api/me/engineering/action",
                actionBody("board.convene", COMPANY, due,
                        "\"meetingId\":\"mgr-appt-x\",\"materialDeadline\":\"" + (due + 500L)
                                + "\",\"opensAt\":\"" + (due + 1_000L) + "\",\"closesAt\":\""
                                + (due + 2_000L) + "\""));
        assertEquals(400, notChair.statusCode(), "非主席不能召集会议：" + notChair.body());
        assertTrue(notChair.body().contains("主席"), notChair.body());
        // 主席专属的任免召集入口根本不在非主席岗位表里。
        HttpResponse<String> notChairAppointment = post("/api/me/engineering/action",
                actionBody("board.conveneAppointment", COMPANY, due,
                        "\"meetingId\":\"mgr-appt-y\",\"materialDeadline\":\"" + (due + 500L)
                                + "\",\"opensAt\":\"" + (due + 1_000L) + "\",\"closesAt\":\""
                                + (due + 2_000L) + "\""));
        assertEquals(400, notChairAppointment.statusCode(),
                "非主席不得拿到任免召集：" + notChairAppointment.body());

        loginAdmin();
        long prepareDue = now() + 1_000L;
        loginAdmin();
        personAt("dave");
        HttpResponse<String> vacant = post("/api/me/engineering/action",
                actionBody("periodic.prepare", COMPANY, prepareDue, "\"period\":\"1\""));
        assertEquals(400, vacant.statusCode(), "没有经理时不能准备财报：" + vacant.body());

        loginAdmin();
        long unknownDue = now() + 1_000L;
        loginAdmin();
        personAt(CHAIR);
        HttpResponse<String> unknown = post("/api/me/engineering/action",
                actionBody("board.convene", "eng-zz", unknownDue,
                        "\"meetingId\":\"m-zz\",\"materialDeadline\":\"" + (unknownDue + 1_000L)
                                + "\",\"opensAt\":\"" + (unknownDue + 2_000L) + "\",\"closesAt\":\""
                                + (unknownDue + 3_000L) + "\""));
        assertEquals(400, unknown.statusCode(), "未知公司必须拒绝：" + unknown.body());

        assertEquals(403, post("/api/admin/engineering",
                "{\"companyId\":\"" + COMPANY + "\"}").statusCode(),
                "人物会话不能读 God 工程接口");
        HttpResponse<String> forged = post("/api/me/engineering/action",
                actionBody("board.convene", COMPANY, unknownDue,
                        "\"meetingId\":\"m-body\"").replace(
                        "{\"expectedPosition\"",
                        "{\"personPartyId\":\"alice\",\"expectedPosition\""));
        assertEquals(400, forged.statusCode(), "body 不能自带人物字段：" + forged.body());
    }

    // ------------------------------------------------------------ 治理说明真实阅读/回忆

    @Test
    @DisplayName("治理说明：读前无正文 → 读后可回忆见正文 → 遗忘需重读 → 重读恢复")
    void governanceNoteFollowsRealReadingAndRecall() throws Exception {
        Path journal = workDir.resolve("cufe-note.journal");
        openDefaultWorld(journal);
        long now = now();

        loginAdmin();
        personAt("erin");
        JsonNode before = cardOf(getJson("/api/me/engineering"), COMPANY);
        assertEquals(WorldWebScenario.GOVERNANCE_NOTE_DOC,
                before.path("governanceDocId").asText());
        assertEquals("1", before.path("governanceDocVersion").asText());
        assertTrue(before.path("governanceDocTitle").asText().contains("治理"));
        assertFalse(before.path("governanceDocRead").asBoolean(), "还没读过");
        assertFalse(before.path("governanceDocRecallable").asBoolean());
        assertTrue(before.path("governanceDocBody").isNull(),
                "未读时不得返回正文：" + before);

        // 人物切换不串：bob 也没读过，同样没有正文。
        loginAdmin();
        loginAdmin();
        personAt("bob");
        assertEquals("company-governance:cufe:1",
                cardOf(getJson("/api/me/engineering"), COMPANY).path("governanceDocId").asText());
        assertTrue(cardOf(getJson("/api/me/engineering"), COMPANY)
                .path("governanceDocBody").isNull());

        // 真实阅读：标识只取本人卡片，due 由页面给出世界时点。
        loginAdmin();
        loginAdmin();
        personAt("erin");
        JsonNode card = cardOf(getJson("/api/me/engineering"), COMPANY);
        String noteDoc = card.path("governanceDocId").asText();
        String noteVersion = card.path("governanceDocVersion").asText();
        long readDue = personTime() + 1_000L;
        postJson("/api/me/read", readBody(personPosition(), noteDoc, noteVersion,
                Long.toString(readDue)));
        loginAdmin();
        advanceTo(Long.toString(readDue + WorldWebScenario.GOVERNANCE_NOTE_READ_MILLIS + 1_000L));

        loginAdmin();
        personAt("erin");
        JsonNode after = cardOf(getJson("/api/me/engineering"), COMPANY);
        assertTrue(after.path("governanceDocRead").asBoolean(), "真实读完必须变为已读");
        assertTrue(after.path("governanceDocRecallable").asBoolean());
        String body = after.path("governanceDocBody").asText();
        assertTrue(body.contains("模拟冻结配置"), body);
        assertTrue(body.contains("公司经理=空缺"), body);
        assertFalse(body.contains("开局组织任职"), "公开说明不得含开局机构任职列表：" + body);
        assertFalse(body.contains("预算") || body.contains("余额") || body.contains("评分"),
                "公开说明不得含私预算/余额/评分：" + body);

        // 越过 4 个回忆半衰期：历史已读仍在，但当前不可回忆、正文隐藏。
        loginAdmin();
        advanceTo(Long.toString(readDue + 57L * DAY));
        loginAdmin();
        personAt("erin");
        JsonNode forgotten = cardOf(getJson("/api/me/engineering"), COMPANY);
        assertTrue(forgotten.path("governanceDocRead").asBoolean(), "历史已读必须保留");
        assertFalse(forgotten.path("governanceDocRecallable").asBoolean(),
                "超过四个半衰期后应不可回忆");
        assertTrue(forgotten.path("governanceDocBody").isNull(), "遗忘后不得回显正文");

        // 合法重读同一版本后恢复。
        long rereadDue = personTime() + 1_000L;
        postJson("/api/me/read", readBody(personPosition(), noteDoc, noteVersion,
                Long.toString(rereadDue)));
        loginAdmin();
        advanceTo(Long.toString(rereadDue + WorldWebScenario.GOVERNANCE_NOTE_READ_MILLIS
                + 1_000L));
        loginAdmin();
        personAt("erin");
        JsonNode reread = cardOf(getJson("/api/me/engineering"), COMPANY);
        assertTrue(reread.path("governanceDocRecallable").asBoolean(), "重读后恢复可回忆");
        assertEquals(body, reread.path("governanceDocBody").asText(), "重读后正文一致");

        // 公司切换：工程公司卡片没有这份治理说明。
        loginAdmin();
        loginAdmin();
        personAt("bob");
        JsonNode engineering = cardOf(getJson("/api/me/engineering"), WorldWebScenario.ENGINE_A);
        assertFalse(engineering.has("governanceDocId"), "工程公司不得出现制造治理说明");
    }

    // ------------------------------------------------------------ 旧档重开

    @Test
    @DisplayName("重开已存在的时间 0 旧档不得偷偷追加开局治理说明")
    void reopeningExistingArchiveDoesNotAddGovernanceNote() throws Exception {
        Path journal = workDir.resolve("legacy-time-zero.journal");
        // 用不含开局说明的旧入口造一个世界时点仍为 0 的已存在档案。
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(journal)) {
            assertEquals(0L, world.snapshot().timeMillis());
        }
        assertTrue(Files.size(journal) > 0L, "档案必须已持久存在");

        try (WorldRuntime world = WorldWebScenario.openGroups(journal).runtime()) {
            assertFalse(hasGovernanceNote(world), "重开旧档不得新增开局说明");
            world.advanceTo(1_000L);
            assertFalse(hasGovernanceNote(world), "推进后也不得凭空出现开局说明");
        }
    }

    private static boolean hasGovernanceNote(WorldRuntime world) {
        var information = world.snapshot().information().orElse(null);
        if (information == null) {
            return false;
        }
        for (InformationModel.Document document : information.information().documents()) {
            if (document.docId().equals(WorldWebScenario.GOVERNANCE_NOTE_DOC)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 真跨进程

    @Test
    @DisplayName("新 JVM 用已落盘的经理任职与授权继续发布")
    void newProcessContinuesFromStoredGrant() throws Exception {
        Path journal = workDir.resolve("cufe-resume.journal");
        openDefaultWorld(journal);
        String authorizationId = appointAndAuthorizeForResume(journal);
        assertNotNull(authorizationId);
        closeServer();

        String output = runProbe(journal, CHAIR);
        assertTrue(output.contains("MANAGER=true"), output);
        assertTrue(output.contains("AUTHORIZATION=" + authorizationId), output);
        assertTrue(output.contains("PERIOD_ONE=true"), output);
        assertTrue(output.contains("RELEASES=1"), output);
        assertTrue(output.contains("GRANT_GONE=true"), "发布后授权必须被消费：" + output);

        // 本进程只做只读复核：另一个进程写入的公开版本与草稿逐字一致。
        String digest = null;
        for (String line : output.split("\n")) {
            if (line.startsWith("BODY_DIGEST=")) {
                digest = line.substring("BODY_DIGEST=".length()).trim();
            }
        }
        assertNotNull(digest, output);
        try (WorldRuntime world = WorldWebScenario.openGroups(journal).runtime()) {
            InformationModel.State information = world.snapshot().information().orElseThrow()
                    .information();
            InformationModel.Document publicDoc = null;
            for (InformationModel.Document candidate : information.documents()) {
                if (candidate.docId().equals("company-statement:cufe:1")) {
                    publicDoc = candidate;
                }
            }
            assertNotNull(publicDoc);
            assertEquals(1, publicDoc.versions().size());
            assertEquals(digest, sha256(publicDoc.versions().get(0).body()));
            assertTrue(world.isActiveManager(COMPANY, "dave",
                    world.snapshot().timeMillis()), "跨进程续跑必须看到已落盘经理");
        }
    }

    /** 走到“已授权未发布”为止：任免 + 期 1 准备/读/评/批/授权，全部经 HTTP。 */
    private String appointAndAuthorizeForResume(Path journal) throws Exception {
        long now = now();
        long conveneDue = now + 1_000L;
        long materialDeadline = now + 15_000L;
        long opensAt = now + 20_000L;
        long closesAt = now + 30_000L;
        loginAdmin();
        personAt(CHAIR);
        act("board.conveneAppointment", COMPANY, conveneDue,
                "\"meetingId\":\"mgr-appt-1\",\"materialDeadline\":\"" + materialDeadline
                        + "\",\"opensAt\":\"" + opensAt + "\",\"closesAt\":\"" + closesAt + "\"");
        loginAdmin();
        advanceTo(Long.toString(conveneDue));
        for (String person : List.of(CHAIR, "bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            act("board.attend", COMPANY, now + 5_000L,
                    "\"meetingId\":\"mgr-appt-1\",\"recused\":false");
        }
        loginAdmin();
        advanceTo(Long.toString(now + 5_000L));
        loginAdmin();
        personAt("bob");
        JsonNode noteCard = cardOf(getJson("/api/me/engineering"), COMPANY);
        String noteDoc = noteCard.path("governanceDocId").asText();
        String noteVersion = noteCard.path("governanceDocVersion").asText();
        assertFalse(noteDoc.isBlank(), "开局治理说明必须真实可读：" + noteCard);
        act("board.addMaterial", COMPANY, now + 6_000L,
                "\"meetingId\":\"mgr-appt-1\",\"materialId\":\"mgr-appt-mat-1\","
                        + "\"docId\":\"" + noteDoc + "\",\"docVersion\":\"" + noteVersion + "\"");
        loginAdmin();
        advanceTo(Long.toString(now + 6_000L));
        for (String person : List.of("bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            postJson("/api/me/read", readBody(personPosition(), noteDoc, noteVersion,
                    Long.toString(now + 7_000L)));
        }
        loginAdmin();
        loginAdmin();
        personAt("erin");
        JsonNode erinNote = cardOf(getJson("/api/me/engineering"), COMPANY);
        assertEquals(noteDoc, erinNote.path("governanceDocId").asText(),
                "只有董事身份、没有机构任职的 erin 也必须能从自己的目录拿到这份说明："
                        + erinNote);
        assertEquals(noteVersion, erinNote.path("governanceDocVersion").asText());
        loginAdmin();
        advanceTo(Long.toString(now + 8_000L));
        loginAdmin();
        personAt("bob");
        act("board.proposeAppointment", COMPANY, now + 10_000L,
                "\"meetingId\":\"mgr-appt-1\",\"appointmentId\":\"cufe-mgr-1\","
                        + "\"appointPersonId\":\"dave\",\"appointRole\":\"COMPANY_MANAGER\","
                        + "\"appointAction\":\"APPOINT\",\"effectiveAt\":\"" + (now + 60_000L)
                        + "\",\"endsAt\":\"" + (now + 60_000L + 60L * DAY) + "\"");
        loginAdmin();
        advanceTo(Long.toString(now + 10_000L));
        for (String person : List.of("bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            act("board.vote", COMPANY, now + 25_000L,
                    "\"meetingId\":\"mgr-appt-1\",\"vote\":\"YES\"");
        }
        loginAdmin();
        advanceTo(Long.toString(now + 25_000L));
        loginAdmin();
        personAt("bob");
        act("board.implement", COMPANY, now + 31_000L, "\"meetingId\":\"mgr-appt-1\"");
        loginAdmin();
        advanceTo(Long.toString(now + 31_000L));
        advanceTo(Long.toString(now + 60_000L));

        for (String person : List.of("bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            act("periodic.attend", COMPANY, now + 61_000L, "\"period\":\"1\",\"recused\":false");
        }
        loginAdmin();
        advanceTo(Long.toString(now + 62_000L));
        advanceTo(Long.toString(PERIOD_ONE_OPENS + 1_000L));
        loginAdmin();
        personAt("dave");
        act("periodic.prepare", COMPANY, PERIOD_ONE_OPENS + 2_000L, "\"period\":\"1\"");
        loginAdmin();
        advanceTo(Long.toString(PERIOD_ONE_OPENS + 2_000L));
        loginAdmin();
        long readDue = now() + 1_000L;
        for (String person : List.of("bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            JsonNode row = cardOf(getJson("/api/me/engineering"), COMPANY)
                    .path("periodic").path("periods").get(0);
            postJson("/api/me/read", readBody(personPosition(),
                    row.path("materialDocId").asText(), row.path("materialVersion").asText(),
                    Long.toString(readDue)));
        }
        loginAdmin();
        advanceTo(Long.toString(readDue + READ_MILLIS + 1_000L));
        long scoreDue = 0;
        for (String person : List.of("bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            JsonNode row = cardOf(getJson("/api/me/engineering"), COMPANY)
                    .path("periodic").path("periods").get(0);
            String ref = evidenceRef(row, "CASH_SOLVENCY");
            scoreDue = personTime() + 1_000L;
            postJson("/api/me/engineering/action", scoreBody(COMPANY, personPosition(), ref,
                    "80", scoreDue));
        }
        loginAdmin();
        advanceTo(Long.toString(scoreDue));
        long approveDue = 0;
        for (String person : List.of("bob", "carol", "erin")) {
            loginAdmin();
            personAt(person);
            JsonNode draft = cardOf(getJson("/api/me/engineering"), COMPANY)
                    .path("periodic").path("periods").get(0).path("latestDraft");
            approveDue = personTime() + 1_000L;
            act("periodic.approve", COMPANY, approveDue,
                    "\"period\":\"1\",\"draftId\":\"" + draft.path("draftId").asText()
                            + "\",\"draftVersion\":\"" + draft.path("version").asText() + "\"");
        }
        loginAdmin();
        advanceTo(Long.toString(approveDue));
        loginAdmin();
        personAt("dave");
        JsonNode draft = cardOf(getJson("/api/me/engineering"), COMPANY)
                .path("periodic").path("periods").get(0).path("latestDraft");
        long authorizeDue = personTime() + 1_000L;
        act("periodic.authorize", COMPANY, authorizeDue,
                "\"period\":\"1\",\"draftId\":\"" + draft.path("draftId").asText()
                        + "\",\"draftVersion\":\"" + draft.path("version").asText()
                        + "\",\"publisherPersonId\":\"" + CHAIR + "\",\"expiresAt\":\""
                        + (authorizeDue + DAY) + "\"");
        loginAdmin();
        advanceTo(Long.toString(authorizeDue));
        loginAdmin();
        personAt(CHAIR);
        JsonNode grant = cardOf(getJson("/api/me/engineering"), COMPANY)
                .path("periodic").path("periods").get(0).path("authorizations");
        assertEquals(1, grant.size(), "授权应当只给本人可见");
        return grant.get(0).path("authorizationId").asText();
    }

    @Test
    @DisplayName("三个实际 JVM 分段恢复：经理任免 + 周期快照 + 群帖读")
    void threeProcessesRecoverJointChain() throws Exception {
        Path journal = workDir.resolve("joint-3jvm.journal");
        String phase1 = runJoint(journal, "phase1");
        assertTrue(phase1.contains("PHASE1 manager=true"), phase1);
        assertTrue(phase1.contains("periods=1"), "自动周期采样必须存在：" + phase1);
        assertTrue(phase1.contains("post=PUBLISHED"), "群帖应已完成投递：" + phase1);
        String phase2 = runJoint(journal, "phase2");
        assertTrue(phase2.contains("PHASE2 manager=true"), phase2);
        assertTrue(phase2.contains("period=true"), "第二进程必须看到周期 1：" + phase2);
        assertTrue(phase2.contains("groupRead=true"), "第二进程必须看到已读完群帖：" + phase2);
        String verify = runJoint(journal, "verify");
        assertTrue(verify.contains("VERIFY manager=true published=true seats=5"), verify);
    }

    /** 真跨进程：另起 JVM 跑联合探针。 */
    private static String runJoint(Path journal, String phase) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"),
                "edu.cufe.auction.world.GroupsPeriodicJointProbe", journal.toString(), phase);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "子进程失败：" + output);
        return output;
    }

    /** 真跨进程：另起 JVM 跑探针，返回标准输出。 */
    private static String runProbe(Path journal, String publisher) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"),
                "edu.cufe.auction.web.CufeResumePublishProbe", journal.toString(), publisher,
                "groups");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "子进程失败：" + output);
        return output;
    }

    // ------------------------------------------------------------ HTTP 工具

    private static long dueOf(JsonNode page, String action) {
        return Long.parseLong(page.path("timeMillis").asText())
                + Long.parseLong(page.path("actionMillis").path(action).asText());
    }

    private static String groupBody(JsonNode page, String action, String fields) {
        String suffix = "joint-g-" + action + "-" + System.nanoTime();
        return "{\"expectedPosition\":\"" + page.path("position").asText()
                + "\",\"commandId\":\"" + suffix + "\",\"taskId\":\"" + suffix + "-task\","
                + "\"action\":\"" + action + "\"," + fields.substring(1);
    }

    private static JsonNode postOf(JsonNode page, String docId) {
        for (JsonNode row : page.path("posts")) {
            if (row.path("docId").asText().equals(docId)) {
                return row;
            }
        }
        throw new AssertionError("页面没有群帖：" + docId);
    }

    private JsonNode god(String company) throws Exception {
        return postJson("/api/admin/engineering", "{\"companyId\":\"" + company + "\"}");
    }

    private static int meetingIndexByName(JsonNode god, String meetingId) {
        JsonNode rows = god.path("meetings").path("rows");
        for (int index = 0; index < rows.size(); index++) {
            if (meetingId.equals(rows.get(index).path("meetingId").asText())) {
                return index;
            }
        }
        return -1;
    }

    private static JsonNode meetingRow(JsonNode god, String meetingId) {
        JsonNode rows = god.path("meetings").path("rows");
        for (JsonNode row : rows) {
            if (meetingId.equals(row.path("meetingId").asText())) {
                return row;
            }
        }
        throw new AssertionError("缺少会议 " + meetingId + "：" + rows);
    }

    private static JsonNode dimensionState(JsonNode row, String dimension) {
        for (JsonNode entry : row.path("dimensionStates")) {
            if (dimension.equals(entry.path("dimension").asText())) {
                return entry;
            }
        }
        throw new AssertionError("缺少维度状态 " + dimension + "：" + row);
    }

    private JsonNode cardOf(JsonNode view, String companyId) {
        for (JsonNode card : view.path("companies")) {
            if (companyId.equals(card.path("companyId").asText())) {
                return card;
            }
        }
        throw new AssertionError("缺少公司卡片 " + companyId + "：" + view);
    }

    private static List<String> tools(JsonNode card) {
        List<String> list = new ArrayList<>();
        for (JsonNode tool : card.path("tools")) {
            list.add(tool.asText());
        }
        return list;
    }

    private static String evidenceRef(JsonNode row, String dimension) {
        for (JsonNode entry : row.path("evidence")) {
            for (JsonNode name : entry.path("dimensions")) {
                if (dimension.equals(name.asText())) {
                    return entry.path("ref").asText();
                }
            }
        }
        throw new AssertionError("缺少维度证据 " + dimension + "：" + row);
    }

    private static String evidenceAsOf(JsonNode row, String ref) {
        for (JsonNode entry : row.path("evidence")) {
            if (ref.equals(entry.path("ref").asText())) {
                return entry.path("asOf").asText();
            }
        }
        return "";
    }

    private JsonNode act(String tool, String company, long due, String extra)
            throws Exception {
        return postJson("/api/me/engineering/action",
                actionBody(tool, company, due, extra));
    }

    private String actionBody(String tool, String company, long due, String extra) {
        sequence += 1;
        String suffix = tool.replace('.', '-') + "-" + sequence + "-" + System.nanoTime();
        return "{\"expectedPosition\":\"" + personPositionSafe() + "\",\"commandId\":\"web-"
                + suffix + "\",\"taskId\":\"web-" + suffix + "-task\",\"dueTimeMillis\":\""
                + due + "\",\"companyId\":\"" + company + "\",\"tool\":\"" + tool + "\""
                + (extra == null || extra.isBlank() ? "" : "," + extra) + "}";
    }

    private String personPositionSafe() {
        try {
            return personPosition();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String scoreBody(String company, String position, String ref, String score, long due) {
        sequence += 1;
        String suffix = "periodic-score-" + sequence + "-" + System.nanoTime();
        return "{\"expectedPosition\":\"" + position + "\",\"commandId\":\"web-" + suffix
                + "\",\"taskId\":\"web-" + suffix + "-task\",\"dueTimeMillis\":\"" + due
                + "\",\"companyId\":\"" + company + "\",\"tool\":\"periodic.score\","
                + "\"period\":\"1\",\"metrics\":[{\"dimension\":\"CASH_SOLVENCY\","
                + "\"score\":\"" + score + "\",\"sourceRef\":\"" + ref + "\"}]}";
    }

    private static String readBody(String position, String docId, String version, String due) {
        String suffix = "read-" + System.nanoTime();
        return "{\"expectedPosition\":\"" + position + "\",\"commandId\":\"web-" + suffix
                + "\",\"taskId\":\"web-" + suffix + "-task\",\"dueTimeMillis\":\"" + due
                + "\",\"docId\":\"" + docId + "\",\"version\":\"" + version + "\"}";
    }

    private long now() throws Exception {
        return Long.parseLong(getJson("/api/admin/view").path("timeMillis").asText());
    }

    private void loginAdmin() throws Exception {
        HttpResponse<String> response = post("/api/session",
                "{\"bootstrap\":\"" + adminBootstrap + "\"}");
        assertEquals(200, response.statusCode(), "管理员登录应成功：" + response.body());
        cookie = cookieOf(response);
        adminBootstrap = rotatedBootstrap();
    }

    private String rotatedBootstrap() {
        for (int index = consoleLines.size() - 1; index >= 0; index--) {
            String line = consoleLines.get(index);
            int mark = line.indexOf('：');
            if (line.contains("引导凭据") && mark > 0) {
                return line.substring(mark + 1).trim();
            }
        }
        return adminBootstrap;
    }

    private void advanceTo(String target) throws Exception {
        JsonNode view = getJson("/api/admin/view");
        long current = Long.parseLong(view.path("timeMillis").asText());
        long wanted = Long.parseLong(target);
        long effective = Math.max(wanted, current + 1);
        HttpResponse<String> response = post("/api/admin/advance",
                "{\"expectedPosition\":\"" + view.path("position").asText()
                        + "\",\"targetMillis\":\"" + effective + "\"}");
        assertEquals(200, response.statusCode(), "推进应成功：" + response.body());
    }

    private void personAt(String personId) throws Exception {
        HttpResponse<String> response = post("/api/session/person",
                "{\"personId\":\"" + personId + "\"}");
        assertEquals(200, response.statusCode(), "切换到人物 " + personId + " 应成功");
        cookie = cookieOf(response);
    }

    private String personPosition() throws Exception {
        return getJson("/api/me/engineering").path("position").asText();
    }

    private long personTime() throws Exception {
        return Long.parseLong(getJson("/api/me/engineering").path("timeMillis").asText());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertEquals(200, response.statusCode(), path + " 应返回 200：" + response.body());
        return JSON.readTree(response.body());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpResponse<String> response = post(path, body);
        assertEquals(200, response.statusCode(), path + " 应返回 200：" + response.body());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header("Origin", base)
                .header("X-World-Request", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookieOf(HttpResponse<String> response) {
        String raw = response.headers().firstValue("set-cookie").orElseThrow(
                () -> new AssertionError("缺少 set-cookie"));
        return raw.split(";", 2)[0];
    }

    private static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
