package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * 18c 周期治理 Web 组合：真实回环 HTTP 走完 prepare → 本人读 → 3 人 approve →
 * authorize → publish，并核对 SCHEDULED/FINISH 语义、公开正文、更正/迟报/MISSED、
 * 制造与工程公司权限以及人物投影不看他人原始评分/私有授权。
 *
 * <p>所有数据由本测试用真实 {@link WorldRuntime} 开局、排程与推进产生；不 mock 服务响应，
 * 不反射改状态，不读外部服务。</p>
 */
class EngineeringPeriodicWebTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String BOOTSTRAP = "engineering-periodic-web-bootstrap";
    private static final long DAY = 86_400_000L;
    private static final List<String> DUMMY_KEYS = List.of("sk-test-0000000000000001",
            "sk-test-0000000000000002", "sk-test-0000000000000003",
            "sk-test-0000000000000004", "sk-test-0000000000000005");

    private Path workDir;
    private WorldWebHost host;
    private WorldWebServer server;
    private String base;
    private String cookie;
    private String adminBootstrap = BOOTSTRAP;
    private final List<String> consoleLines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path root = Path.of(System.getProperty("web.periodic.work",
                "target/web-periodic-tests"));
        workDir = root.resolve("periodic-" + System.nanoTime());
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

    private static List<RoleAccess.Appointment> managers() {
        return List.of(
                new RoleAccess.Appointment("WEB-MGR-A", "dave", WorldWebScenario.ENGINE_A,
                        RoleAccess.Role.COMPANY_MANAGER, 0L, 900L * DAY, null),
                new RoleAccess.Appointment("WEB-MGR-MFG", "erin", WorldWebScenario.COMPANY,
                        RoleAccess.Role.COMPANY_MANAGER, 0L, 900L * DAY, null));
    }

    private void seed(Path journal) throws Exception {
        try (WorldRuntime world = WorldWebScenario.engineeringRuntimeWithAppointments(
                journal, WorldWebScenario.engineeringConfig(), managers())) {
            world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5L);
            assertTrue(world.periodicOf(WorldWebScenario.ENGINE_A).isPresent(),
                    "期 1 采样必须已发生");
        }
    }

    private void start(Path journal) throws Exception {
        Path keys = workDir.resolve("keys-" + System.nanoTime() + ".txt");
        Files.writeString(keys, String.join("\n", DUMMY_KEYS), StandardCharsets.UTF_8);
        host = new WorldWebHost(() -> new WorldWebHost.Opened(
                WorldWebScenario.engineeringRuntimeWithAppointments(journal,
                        WorldWebScenario.engineeringConfig(), managers()),
                WorldWebScenario.engineeringManifest().runId()));
        server = new WorldWebServer(host, KeySlots.load(keys), SceneRoutes.open(workDir),
                0, BOOTSTRAP, null, consoleLines::add);
        server.start();
        base = server.baseUrl();
        cookie = null;
    }

    // ------------------------------------------------------------ 主链

    @Test
    @DisplayName("HTTP 主链：SCHEDULED→BEGIN/FINISH、正文一致、更正链与迟报")
    void fullHttpChainKeepsScheduledAndRealFinishSeparate() throws Exception {
        Path journal = workDir.resolve("chain.journal");
        seed(journal);
        start(journal);
        loginAdmin();

        JsonNode god = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        JsonNode period1 = god.path("periodic").path("periods").get(0);
        String capturedAt = period1.path("capturedAt").asText();
        assertEquals("MONTHLY", period1.path("kind").asText());
        JsonNode meeting = period1.path("meeting");
        long opensAt = meeting.path("opensAt").asLong();
        long closesAt = meeting.path("closesAt").asLong();
        long deadline = period1.path("deadline").asLong();
        assertEquals(0, period1.path("publication").path("versions").asInt(),
                "采样时还没有公开版本");
        assertTrue(period1.path("score").path("closed").asBoolean() == false);

        // 出席：due 在开会前，三席真实登记；返回回执只是已排程。
        for (String person : List.of("alice", "bob", "carol")) {
            loginAdmin();
            personAt(person);
            JsonNode receipt = postJson("/api/me/engineering/action",
                    periodicBody(personPosition(), "eng-a", "periodic.attend", "1",
                            Long.toString(Long.parseLong(capturedAt) + 1000)));
            assertEquals("periodic.attend", receipt.path("tool").asText());
        }
        loginAdmin();
        advanceTo(Long.toString(Long.parseLong(capturedAt) + 1000));

        // 准备：真实 FINISH 后才出现草稿与 BOARD 材料。
        personAt("dave");
        JsonNode beforePrepare = getJson("/api/me/engineering");
        JsonNode daveCard = cardOf(beforePrepare, "eng-a");
        assertTrue(daveCard.path("periodic").path("available").asBoolean());
        assertTrue(daveCard.path("periodic").path("seat").asBoolean(), "dave 同时是董事");
        assertEquals(0, daveCard.path("periodic").path("periods").get(0)
                .path("drafts").size());
        postJson("/api/me/engineering/action", periodicBody(personPosition(), "eng-a",
                "periodic.prepare", "1", Long.toString(opensAt + 2000)));
        JsonNode scheduled = getJson("/api/me/engineering");
        JsonNode draftAfterSchedule = cardOf(scheduled, "eng-a").path("periodic")
                .path("periods").get(0);
        assertEquals(0, draftAfterSchedule.path("drafts").size(),
                "排程不等于已准备，FINISH 前不能出现草稿");
        assertTrue(draftAfterSchedule.path("pendingActions").size() >= 1,
                "必须显示为待执行：" + draftAfterSchedule);
        loginAdmin();
        advanceTo(Long.toString(opensAt + 2000));

        JsonNode afterPrepare = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}");
        JsonNode prepared = afterPrepare.path("periodic").path("periods").get(0);
        assertEquals(1, prepared.path("drafts").size(), "FINISH 后必须出现草稿");
        String draftId = prepared.path("latestDraft").path("draftId").asText();
        String draftDocId = prepared.path("latestDraft").path("docId").asText();
        String draftVersion = prepared.path("latestDraft").path("version").asText();
        assertFalse(prepared.path("latestDraft").path("preparedAt").asText().isBlank());

        // 本人读：未读前人物投影不给正文，读完才变成已读；评分前未读的会被真实拒绝。
        personAt("alice");
        JsonNode aliceBefore = cardOf(getJson("/api/me/engineering"), "eng-a")
                .path("periodic").path("periods").get(0);
        assertFalse(aliceBefore.path("myReadMaterial").asBoolean(), "还没读");
        assertTrue(aliceBefore.path("releases").size() == 0);
        for (String person : List.of("alice", "bob", "carol")) {
            loginAdmin();
            personAt(person);
            postJson("/api/me/read", readBody(personPosition(), draftDocId, draftVersion,
                    Long.toString(opensAt + 4000)));
        }
        loginAdmin();
        advanceTo(Long.toString(opensAt + 4000 + 3_600_000L + 1000L));
        personAt("alice");
        JsonNode aliceAfter = cardOf(getJson("/api/me/engineering"), "eng-a")
                .path("periodic").path("periods").get(0);
        assertTrue(aliceAfter.path("myReadMaterial").asBoolean(), "真实读完必须变为已读");
        assertTrue(aliceAfter.path("myRecallEligible").asBoolean());

        // 评分：三席只用本人可见的 account: 证据；只提交部分维度，汇总不得出假总分。
        for (String person : List.of("alice", "bob", "carol")) {
            loginAdmin();
            personAt(person);
            JsonNode card = cardOf(getJson("/api/me/engineering"), "eng-a");
            JsonNode row = card.path("periodic").path("periods").get(0);
            String ref = evidenceRef(row, "CASH_SOLVENCY");
            assertTrue(ref.startsWith("account:"), "可见证据必须是真实账户引用");
            String asOf = evidenceAsOf(row, ref);
            assertEquals(capturedAt, asOf, "现金证据时点为期末截至");
            long due = personTime() + 1000;
            postJson("/api/me/engineering/action", scoreBody(personPosition(), "eng-a", ref, due));
        }
        loginAdmin();
        advanceTo(Long.toString(closesAt + 10));
        JsonNode closed = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}")
                .path("periodic").path("periods").get(0);
        assertTrue(closed.path("score").path("closed").asBoolean());
        assertEquals("INCOMPLETE", closed.path("score").path("status").asText());
        assertEquals("NO_QUORUM", closed.path("score").path("reason").asText());
        assertTrue(closed.path("score").path("total").isNull(), "缺维不得补 0 出总分");
        assertEquals(3, closed.path("score").path("ballots").size(), "God 审计看全部三席");

        // 人物页只给本人选票与已允许的进度：其他三席原票/理由不出现。
        loginAdmin();
        personAt("alice");
        JsonNode aliceScoreView = cardOf(getJson("/api/me/engineering"), "eng-a")
                .path("periodic").path("periods").get(0);
        assertEquals(1, aliceScoreView.path("score").path("ballots").size(),
                "人物页只应看到本人一张选票");
        assertEquals("alice", aliceScoreView.path("score").path("ballots").get(0)
                .path("personPartyId").asText());
        for (JsonNode authorization : aliceScoreView.path("authorizations")) {
            assertFalse(authorization.has("approvers"), "不得显示他人批准集合");
        }
        assertTrue(aliceScoreView.path("releases").size() == 0);

        // 批准：三席按确切版本；准备者 dave 不批准自己的稿。
        long approveDue = 0;
        for (String person : List.of("alice", "bob", "carol")) {
            loginAdmin();
            personAt(person);
            approveDue = personTime() + 1000;
            postJson("/api/me/engineering/action", draftBody(personPosition(), "eng-a",
                    "periodic.approve", "1", draftId, draftVersion, approveDue));
        }
        loginAdmin();
        advanceTo(Long.toString(approveDue));
        JsonNode afterApprovals = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}")
                .path("periodic").path("periods").get(0);
        assertEquals(3, afterApprovals.path("latestDraft").path("approvalCount").asInt());
        assertEquals("3", afterApprovals.path("latestDraft").path("requiredApprovals").asText());

        // 授权：先发一条很快过期的授权，验证过期后本人看不到、也不可执行。
        personAt("dave");
        long authDue = personTime() + 1000;
        postJson("/api/me/engineering/action", authorizeBody(personPosition(), "eng-a",
                draftId, draftVersion, "alice", authDue, Long.toString(authDue + 10)));
        loginAdmin();
        advanceTo(Long.toString(authDue));
        JsonNode authorized = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}")
                .path("periodic").path("periods").get(0);
        assertEquals("PENDING", authorized.path("authorizations").get(0).path("status").asText());
        assertEquals(0, authorized.path("publication").path("versions").asInt(),
                "授权不等于发布");

        // 指定非经理发布人 alice 只从本人响应里拿可执行授权与草稿绑定（不读 God 状态）。
        personAt("alice");
        JsonNode aliceGrantView = cardOf(getJson("/api/me/engineering"), "eng-a")
                .path("periodic").path("periods").get(0);
        assertEquals(1, aliceGrantView.path("authorizations").size(), "本人只有一条可执行授权");
        JsonNode firstGrant = aliceGrantView.path("authorizations").get(0);
        assertTrue(firstGrant.path("mine").asBoolean(), "本人必须能认出这是给自己的授权");
        assertEquals(draftId, firstGrant.path("draftId").asText(), "授权必须带真实草稿绑定");
        assertEquals(draftVersion, firstGrant.path("draftVersion").asText());
        String expiredAuthId = firstGrant.path("authorizationId").asText();

        // 另一个董事看不到这条授权；他公司经理也看不到。
        loginAdmin();
        personAt("bob");
        assertEquals(0, cardOf(getJson("/api/me/engineering"), "eng-a").path("periodic")
                .path("periods").get(0).path("authorizations").size(),
                "其他董事不得看到别人的授权");
        loginAdmin();
        personAt("erin");
        for (JsonNode row : cardOf(getJson("/api/me/engineering"), "eng-a").path("periodic")
                .path("periods")) {
            assertEquals(0, row.path("authorizations").size(),
                    "他公司经理在 A 公司不得看到授权");
        }

        // 过期后：本人看不到可执行授权；拿旧授权真正发布会被拒绝，公开版本仍为 0。
        loginAdmin();
        advanceTo(Long.toString(authDue + 20));
        personAt("alice");
        assertEquals(0, cardOf(getJson("/api/me/engineering"), "eng-a").path("periodic")
                .path("periods").get(0).path("authorizations").size(),
                "过期授权不再作为可执行授权出现");
        long expiredPublishDue = personTime() + 1000;
        postJson("/api/me/engineering/action", draftBody(personPosition(), "eng-a",
                "periodic.publish", "1", draftId, draftVersion, expiredPublishDue)
                .replace("}", ",\"authorizationId\":\"" + expiredAuthId + "\"}"));
        loginAdmin();
        advanceTo(Long.toString(expiredPublishDue));
        assertEquals(0, postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}")
                .path("periodic").path("periods").get(0)
                .path("publication").path("versions").asInt(), "过期授权不得发布");

        // 重新签发有效授权：仍指定非经理发布人 alice，只由她本人执行。
        personAt("dave");
        long validAuthDue = personTime() + 1000;
        postJson("/api/me/engineering/action", authorizeBody(personPosition(), "eng-a",
                draftId, draftVersion, "alice", validAuthDue, Long.toString(validAuthDue + DAY)));
        loginAdmin();
        advanceTo(Long.toString(validAuthDue));
        personAt("alice");
        JsonNode ownGrant = cardOf(getJson("/api/me/engineering"), "eng-a")
                .path("periodic").path("periods").get(0).path("authorizations");
        assertEquals(1, ownGrant.size(), "有效授权应只给本人一条");
        String grantId = ownGrant.get(0).path("authorizationId").asText();
        String grantDraft = ownGrant.get(0).path("draftId").asText();
        String grantDraftVersion = ownGrant.get(0).path("draftVersion").asText();
        assertNotEquals(expiredAuthId, grantId, "过期授权不能被复用");

        // 另一董事拿别人的授权发一次：排程成功但到期必须被拒，不产生公开版本。
        loginAdmin();
        personAt("bob");
        long stealDue = personTime() + 1000;
        postJson("/api/me/engineering/action", draftBody(personPosition(), "eng-a",
                "periodic.publish", "1", grantDraft, grantDraftVersion, stealDue)
                .replace("}", ",\"authorizationId\":\"" + grantId + "\"}"));
        loginAdmin();
        advanceTo(Long.toString(stealDue));
        assertEquals(0, postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}")
                .path("periodic").path("periods").get(0)
                .path("publication").path("versions").asInt(), "他人授权不能被另一董事执行");
        personAt("alice");
        assertEquals(1, cardOf(getJson("/api/me/engineering"), "eng-a").path("periodic")
                .path("periods").get(0).path("authorizations").size(),
                "他人失败不得消费本人的授权");

        // 本人执行：草稿、版本与授权标识全部取自本人响应。
        long publishDue = personTime() + 1000;
        postJson("/api/me/engineering/action", draftBody(personPosition(), "eng-a",
                "periodic.publish", "1", grantDraft, grantDraftVersion, publishDue)
                .replace("}", ",\"authorizationId\":\"" + grantId + "\"}"));
        loginAdmin();
        advanceTo(Long.toString(publishDue));
        JsonNode published = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}")
                .path("periodic").path("periods").get(0);
        assertEquals(1, published.path("publication").path("versions").asInt());
        assertFalse(published.path("publication").path("late").asBoolean(), "按期发布不迟报");
        assertEquals("1", published.path("releases").get(0).path("publicVersion").asText(),
                "公开版本号必须等于真实文档版本");
        assertEquals("1", published.path("releases").get(0).path("draftVersion").asText(),
                "草稿版本是另一件事，按发布记录原样给出");
        String bodyV1 = published.path("releases").get(0).path("body").asText();
        String publicDocIdV1 = published.path("publication").path("publicDocId").asText();
        String publishedAtV1 = published.path("releases").get(0).path("publishedAt").asText();
        assertTrue(bodyV1.contains("周期报表"), bodyV1);
        assertTrue(bodyV1.contains("累计收入="), bodyV1);
        JsonNode consumedAuth = null;
        for (JsonNode row : published.path("authorizations")) {
            if (grantId.equals(row.path("authorizationId").asText())) {
                consumedAuth = row;
            }
        }
        assertNotNull(consumedAuth, "God 审计必须保留被消费的那条授权");
        assertEquals("CONSUMED", consumedAuth.path("status").asText());
        assertNotEquals(0, consumedAuth.path("consumedAt").asLong());

        // 消费后本人不再有任何可执行授权。
        loginAdmin();
        personAt("alice");
        assertEquals(0, cardOf(getJson("/api/me/engineering"), "eng-a").path("periodic")
                .path("periods").get(0).path("authorizations").size(),
                "授权消费后本人就没有可执行授权");

        // 人物看到“已发布 v1”但没读过公开文档：只能拿标题/版本与阅读入口，不得自动给全文。
        loginAdmin();
        personAt("alice");
        JsonNode alicePublished = cardOf(getJson("/api/me/engineering"), "eng-a")
                .path("periodic").path("periods").get(0);
        assertEquals(1, alicePublished.path("releases").size());
        assertTrue(alicePublished.path("releases").get(0).path("body").isNull()
                        || alicePublished.path("releases").get(0).path("body").asText().isEmpty(),
                "未实际读完公开版本不得返回正文");
        assertTrue(alicePublished.path("releases").get(0).path("requiresReading").asBoolean());

        // 真实读完公开 v1 后，才允许看到同一公开正文；正文仍与 God 原文一致。
        long publicReadDue = personTime() + 1000;
        postJson("/api/me/read", readBody(personPosition(), publicDocIdV1,
                "1", Long.toString(publicReadDue)));
        loginAdmin();
        advanceTo(Long.toString(publicReadDue + 3_600_000L + 1000L));
        personAt("alice");
        JsonNode aliceReadPublic = cardOf(getJson("/api/me/engineering"), "eng-a")
                .path("periodic").path("periods").get(0).path("releases").get(0);
        assertFalse(aliceReadPublic.path("requiresReading").asBoolean());
        assertEquals(bodyV1, aliceReadPublic.path("body").asText(),
                "真实读完公开版本后必须显示同一后端原文");

        // 更正：过了披露截止后再准备新版 → 新草稿/新批准/新公开版本，且 v2 迟报。
        loginAdmin();
        advanceTo(Long.toString(deadline + 10));
        personAt("dave");
        JsonNode correctionReceipt = postJson("/api/me/engineering/action",
                periodicBody(personPosition(), "eng-a", "periodic.prepare", "1",
                        Long.toString(deadline + 1000))
                        .replace("}", ",\"note\":\"补充披露说明\"}"));
        assertEquals("periodic.prepare", correctionReceipt.path("tool").asText());
        loginAdmin();
        advanceTo(Long.toString(deadline + 1000));
        JsonNode correctedGod = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}")
                .path("periodic").path("periods").get(0);
        assertEquals(2, correctedGod.path("drafts").size(), "更正必须保留原草稿并新增版本");
        // 未发表的草稿修订不增加 PUBLIC 版本：真实可达的最小例子。
        assertEquals(1, correctedGod.path("publication").path("versions").asInt(),
                "未发表的草稿修订不得增加公开版本");
        assertEquals(1, correctedGod.path("releases").size());
        assertEquals("1", correctedGod.path("releases").get(0).path("publicVersion").asText());
        JsonNode firstDraft = correctedGod.path("drafts").get(0);
        JsonNode secondDraft = correctedGod.path("drafts").get(1);
        assertNotEquals(firstDraft.path("docId").asText(), secondDraft.path("docId").asText(),
                "每份草稿是独立文档，不是同一文档的新版本");
        assertEquals("1", secondDraft.path("version").asText(),
                "草稿版本按草稿文档自身版本，不因为它是更正的草稿就变成 2");
        JsonNode correction = correctedGod.path("latestDraft");
        String correctionId = correction.path("draftId").asText();
        String correctionDoc = correction.path("docId").asText();
        for (String person : List.of("alice", "bob", "carol")) {
            loginAdmin();
            personAt(person);
            postJson("/api/me/read", readBody(personPosition(), correctionDoc, "1",
                    Long.toString(deadline + 2000)));
        }
        loginAdmin();
        advanceTo(Long.toString(deadline + 2000 + 3_600_000L + 1000L));
        long correctionApproveDue = 0;
        for (String person : List.of("alice", "bob", "carol")) {
            loginAdmin();
            personAt(person);
            correctionApproveDue = personTime() + 1000;
            postJson("/api/me/engineering/action", draftBody(personPosition(), "eng-a",
                    "periodic.approve", "1", correctionId, "1", correctionApproveDue));
        }
        loginAdmin();
        advanceTo(Long.toString(correctionApproveDue));
        personAt("dave");
        long correctionAuthDue = personTime() + 1000;
        postJson("/api/me/engineering/action", authorizeBody(personPosition(), "eng-a",
                correctionId, "1", "alice", correctionAuthDue,
                Long.toString(deadline + DAY)));
        loginAdmin();
        advanceTo(Long.toString(correctionAuthDue));
        personAt("alice");
        long correctionPublishDue = personTime() + 1000;
        postJson("/api/me/engineering/action", draftBody(personPosition(), "eng-a",
                "periodic.publish", "1", correctionId, "1", correctionPublishDue));
        loginAdmin();
        advanceTo(Long.toString(correctionPublishDue));
        JsonNode finalView = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}")
                .path("periodic").path("periods").get(0);
        assertEquals(2, finalView.path("publication").path("versions").asInt());
        assertTrue(finalView.path("publication").path("late").asBoolean(), "过期更正必须记迟报");
        assertFalse(finalView.path("publication").path("missed").asBoolean(),
                "已按期发布过首版，不应再记 MISSED");
        String bodyV2 = finalView.path("releases").get(1).path("body").asText();
        assertTrue(bodyV2.contains("更正说明：补充披露说明"), bodyV2);
        assertTrue(finalView.path("corrections").asText().equals("1"));
        assertEquals("2", finalView.path("releases").get(1).path("publicVersion").asText(),
                "第二次真实发布对应公开文档版本 2");
        assertEquals("1", finalView.path("releases").get(0).path("publicVersion").asText(),
                "第一次发布仍是公开文档版本 1，不随更正重编号");

        // 遗忘与重读：同一个公开发布版本，历史已读保留、当前不可回忆、重读后恢复。
        loginAdmin();
        personAt("alice");
        JsonNode beforeForget = cardOf(getJson("/api/me/engineering"), "eng-a")
                .path("periodic").path("periods").get(0);
        assertTrue(beforeForget.path("releases").get(0).path("recallable").asBoolean(),
                "刚读完时公开 v1 必须可回忆");
        loginAdmin();
        advanceTo(Long.toString(publicReadDue + 57L * DAY));
        personAt("alice");
        JsonNode forgottenRow = cardOf(getJson("/api/me/engineering"), "eng-a")
                .path("periodic").path("periods").get(0);
        assertTrue(forgottenRow.path("myReadMaterial").asBoolean(), "历史已读标识必须保留");
        assertFalse(forgottenRow.path("myRecallEligible").asBoolean(),
                "超过四个半衰期后本期材料不再可回忆");
        JsonNode forgottenRelease = forgottenRow.path("releases").get(0);
        assertTrue(forgottenRelease.path("read").asBoolean(), "历史已读必须与当前可用分开");
        assertFalse(forgottenRelease.path("recallable").asBoolean());
        assertTrue(forgottenRelease.path("forgotten").asBoolean());
        assertTrue(forgottenRelease.path("requiresReading").asBoolean());
        assertTrue(forgottenRelease.path("body").isNull(), "遗忘后不得再返回正文");
        assertFalse(forgottenRelease.path("bodyAvailable").asBoolean());
        assertEquals(publicDocIdV1, forgottenRelease.path("publicDocId").asText(),
                "重新阅读入口仍需真实资料标识");
        assertEquals("1", forgottenRelease.path("publicVersion").asText(),
                "重新阅读入口仍需真实公开版本号");
        // 未读过的公开 v2 一直是未读，不会被遗忘逻辑冒充成"读过"。
        assertFalse(forgottenRow.path("releases").get(1).path("read").asBoolean());

        long rereadDue = personTime() + 1000;
        postJson("/api/me/read", readBody(personPosition(), publicDocIdV1,
                forgottenRelease.path("publicVersion").asText(), Long.toString(rereadDue)));
        loginAdmin();
        advanceTo(Long.toString(rereadDue + 3_600_000L + 1000L));
        personAt("alice");
        JsonNode rereadRelease = cardOf(getJson("/api/me/engineering"), "eng-a")
                .path("periodic").path("periods").get(0).path("releases").get(0);
        assertTrue(rereadRelease.path("recallable").asBoolean(), "合法重读同一版本后恢复可回忆");
        assertFalse(rereadRelease.path("forgotten").asBoolean());
        assertFalse(rereadRelease.path("requiresReading").asBoolean());
        assertEquals(bodyV1, rereadRelease.path("body").asText(), "重读后正文仍是同一后端原文");

        // 披露维 asOf：前期真实已发布的 release 才是下一期可用的成熟证据。
        loginAdmin();
        JsonNode period2 = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}")
                .path("periodic").path("periods").get(1);
        assertEquals("2", period2.path("period").asText());
        boolean matured = false;
        for (JsonNode entry : period2.path("evidence")) {
            if ("disclosure:eng-a:1:release:1".equals(entry.path("ref").asText())) {
                assertEquals(publishedAtV1, entry.path("asOf").asText(),
                        "披露证据 asOf 必须是该 release 的真实发布时刻");
                matured = true;
            }
        }
        assertTrue(matured, "第二期必须能引用第一期真实已发布的披露证据：" + period2);

        // 关服后从真实 journal 读同一正文：公开 v1/v2 与 BOARD 草稿逐字一致且摘要匹配。
        String publicDocId = finalView.path("publication").path("publicDocId").asText();
        closeServer();
        try (WorldRuntime world = WorldWebScenario.engineeringRuntimeWithAppointments(
                journal, WorldWebScenario.engineeringConfig(), managers())) {
            InformationModel.State info = world.snapshot().information().orElseThrow()
                    .information();
            String draft1 = documentBody(info, draftDocId, 1);
            String draft2 = documentBody(info, correctionDoc, 1);
            String public1 = documentBody(info, publicDocId, 1);
            String public2 = documentBody(info, publicDocId, 2);
            assertEquals(draft1, public1, "公开 v1 必须逐字等于已批准草稿");
            assertEquals(draft2, public2, "公开 v2 必须逐字等于更正草稿");
            assertEquals(sha256(public1), finalView.path("releases").get(0)
                    .path("bodyDigest").asText());
            assertEquals(sha256(public2), finalView.path("releases").get(1)
                    .path("bodyDigest").asText());
            // 公开版本号映射核查：不是数发布条数，而是真实文档版本按真实发布时刻对上。
            java.util.List<InformationModel.DocumentVersion> versions =
                    documentVersions(info, publicDocId);
            assertEquals(2, versions.size(), "公开文档只该有两次真实发布");
            for (int index = 0; index < 2; index++) {
                JsonNode releaseRow = finalView.path("releases").get(index);
                long publishedAt = Long.parseLong(releaseRow.path("publishedAt").asText());
                long mapped = -1L;
                for (InformationModel.DocumentVersion version : versions) {
                    if (version.publishedAt() == publishedAt) {
                        mapped = version.version();
                    }
                }
                assertEquals(mapped, Long.parseLong(
                                releaseRow.path("publicVersion").asText()),
                        "视图公开版本号必须等于真实文档中该发布时刻的版本");
            }
        }
    }

    @Test
    @DisplayName("错过截止保留 MISSED；制造与工程都可查；人物投影不看他人原票与私有授权")
    void missedAndCompanyScopesHold() throws Exception {
        Path journal = workDir.resolve("scope.journal");
        seed(journal);
        start(journal);
        loginAdmin();

        long deadline = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}")
                .path("periodic").path("periods").get(0).path("deadline").asLong();
        advanceTo(Long.toString(deadline + 10));
        JsonNode missed = postJson("/api/admin/engineering", "{\"companyId\":\"eng-a\"}")
                .path("periodic").path("periods").get(0);
        assertTrue(missed.path("publication").path("missed").asBoolean());
        assertEquals(0, missed.path("publication").path("versions").asInt(),
                "MISSED 不能倒填公开版本");
        assertFalse(missed.path("publication").path("late").asBoolean());

        JsonNode cufe = postJson("/api/admin/engineering", "{\"companyId\":\"cufe\"}");
        assertEquals("MANUFACTURING", cufe.path("template").asText());
        assertTrue(cufe.path("periodic").path("available").asBoolean(), "制造必须有周期投影");
        JsonNode engB = postJson("/api/admin/engineering", "{\"companyId\":\"eng-b\"}");
        assertEquals("ENGINEERING", engB.path("template").asText());
        assertTrue(engB.path("periodic").path("available").asBoolean());
        assertEquals(0, engB.path("periodic").path("periods").get(0)
                .path("score").path("ballots").size(), "B 公司不得混入 A 的票");

        assertEquals(400, post("/api/admin/engineering", "{\"companyId\":\"nope\"}").statusCode());

        personAt("dave");
        JsonNode dave = cardOf(getJson("/api/me/engineering"), "eng-a").path("periodic");
        assertTrue(dave.path("god").asBoolean() == false);
        for (JsonNode row : dave.path("periods")) {
            for (JsonNode ballot : row.path("score").path("ballots")) {
                assertEquals("dave", ballot.path("personPartyId").asText(),
                        "人物页只能看本人原始评分");
            }
            for (JsonNode authorization : row.path("authorizations")) {
                assertFalse(authorization.has("approvers"),
                        "人物页不得显示他人批准集合");
            }
        }
        // dave 没有实际读过公开文档：不得自动拿全文。
        for (JsonNode row : dave.path("periods")) {
            for (JsonNode release : row.path("releases")) {
                assertTrue(release.path("body").isNull() || release.path("body").asText().isEmpty(),
                        "未读公开版本不得返回正文");
            }
        }
        assertEquals(403, post("/api/admin/engineering",
                "{\"companyId\":\"eng-a\"}").statusCode(),
                "人物会话不能读 God 工程接口");
        assertEquals(400, post("/api/me/engineering/action",
                periodicBody(personPosition(), "eng-b", "periodic.prepare", "1",
                        Long.toString(personTime() + 1000))).statusCode(),
                "没有 B 公司岗位必须拒绝");
        assertEquals(400, post("/api/me/read",
                readBody(personPosition(), "periodic-material:eng-a:1", "1", "999999")
                        .replace("{\"expectedPosition\"",
                                "{\"personPartyId\":\"alice\",\"expectedPosition\"}"))
                .statusCode(), "body 不能伪造阅读人");

        cookie = null;
        assertEquals(401, get("/api/me/engineering").statusCode());
        assertEquals(401, post("/api/me/read", readBody("1", "x", "1", "2")).statusCode());

        loginAdmin();
        personAt("investor");
        assertEquals(0, getJson("/api/me/engineering").path("companies").size(),
                "外部普通人没有董事/经理岗位，不得拿到周期材料");
    }

    // ------------------------------------------------------------ HTTP 工具

    private JsonNode cardOf(JsonNode view, String companyId) {
        for (JsonNode card : view.path("companies")) {
            if (companyId.equals(card.path("companyId").asText())) {
                return card;
            }
        }
        throw new AssertionError("缺少公司卡片 " + companyId + "：" + view);
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

    private static String periodicBody(String position, String company, String tool,
            String period, String due) {
        String suffix = tool.replace('.', '-') + "-" + System.nanoTime();
        return "{\"expectedPosition\":\"" + position + "\",\"commandId\":\"web-" + suffix
                + "\",\"taskId\":\"web-" + suffix + "-task\",\"dueTimeMillis\":\"" + due
                + "\",\"companyId\":\"" + company + "\",\"tool\":\"" + tool
                + "\",\"period\":\"" + period + "\"}";
    }

    private static String scoreBody(String position, String company, String ref, long due) {
        String suffix = "periodic-score-" + System.nanoTime();
        return "{\"expectedPosition\":\"" + position + "\",\"commandId\":\"web-" + suffix
                + "\",\"taskId\":\"web-" + suffix + "-task\",\"dueTimeMillis\":\"" + due
                + "\",\"companyId\":\"" + company + "\",\"tool\":\"periodic.score\","
                + "\"period\":\"1\",\"metrics\":[{\"dimension\":\"CASH_SOLVENCY\","
                + "\"score\":\"90\",\"sourceRef\":\"" + ref + "\"}]}";
    }

    private static String draftBody(String position, String company, String tool,
            String period, String draftId, String draftVersion, long due) {
        String suffix = tool.replace('.', '-') + "-" + System.nanoTime();
        return "{\"expectedPosition\":\"" + position + "\",\"commandId\":\"web-" + suffix
                + "\",\"taskId\":\"web-" + suffix + "-task\",\"dueTimeMillis\":\"" + due
                + "\",\"companyId\":\"" + company + "\",\"tool\":\"" + tool
                + "\",\"period\":\"" + period + "\",\"draftId\":\"" + draftId
                + "\",\"draftVersion\":\"" + draftVersion + "\"}";
    }

    private static String authorizeBody(String position, String company, String draftId,
            String draftVersion, String publisher, long due, String expiresAt) {
        return draftBody(position, company, "periodic.authorize", "1", draftId, draftVersion,
                due).replace("}", ",\"publisherPersonId\":\"" + publisher
                        + "\",\"expiresAt\":\"" + expiresAt + "\"}");
    }

    private static String readBody(String position, String docId, String version, String due) {
        String suffix = "read-" + System.nanoTime();
        return "{\"expectedPosition\":\"" + position + "\",\"commandId\":\"web-" + suffix
                + "\",\"taskId\":\"web-" + suffix + "-task\",\"dueTimeMillis\":\"" + due
                + "\",\"docId\":\"" + docId + "\",\"version\":\"" + version + "\"}";
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

    private String positionNow() throws Exception {
        return getJson("/api/admin/view").path("position").asText();
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
                .timeout(Duration.ofSeconds(20)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
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

    private static String documentBody(InformationModel.State info, String docId, long version) {
        for (InformationModel.Document document : info.documents()) {
            if (document.docId().equals(docId)) {
                InformationModel.DocumentVersion found = document.at(version);
                return found == null ? null : found.body();
            }
        }
        return null;
    }

    private static java.util.List<InformationModel.DocumentVersion> documentVersions(
            InformationModel.State info, String docId) {
        for (InformationModel.Document document : info.documents()) {
            if (document.docId().equals(docId)) {
                return document.versions();
            }
        }
        return java.util.List.of();
    }

    private static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
