package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import edu.cufe.auction.world.CashLedger.OpeningAccount;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.ShareClass;
import edu.cufe.auction.world.RoleAccess.Role;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4f 回忆运行桥合同：真实 WorldRuntime/InformationModel/WorldJournal 上的有界回忆。
 *
 * <p>本类不构造玩具状态、不替代模型、不发任何提供方请求；所有经历都由真实
 * {@code schedulePublishDocument}/{@code scheduleStartReading}/{@code advanceTo} 产生，
 * 恢复一律重新打开同一日志文件。</p>
 */
class WorldRuntimeRecallTest {

    private static final String RUN = "cognition-runtime-test";
    private static final long SEED = 11L;
    private static final long DAY = 86_400_000L;
    private static final long END = 30 * DAY;
    private static final long THINK = 1_000L;
    private static final long ACTION = 2_000L;
    private static final long READ_MILLIS = 500L;
    private static final String WAKE = "SCHEDULED";

    private static final String ORG_F = "ORG-F";
    private static final String PRODUCT = "fund-a";
    private static final String ACC_FUND = "ACC-FUND";
    private static final String ACC_INV = "INV";
    private static final String INV = "investor";
    private static final String SYM = "CUFE1";

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String DAVE = "dave";
    private static final String ERIN = "erin";

    /** 快速衰减的实验参数：1 秒半衰期、阈值 500 → 2 秒后分数 250 即遗忘。 */
    private static final CognitionRecall.Policy FAST =
            new CognitionRecall.Policy(1_000L, 500, 2, 100_000L);

    Path tempDir;

    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true)).build();

    @BeforeEach
    void createWorkspaceDirectory() throws IOException {
        Path base = Path.of("target", "cognition-runtime-tests");
        Files.createDirectories(base);
        tempDir = Files.createTempDirectory(base, "case-");
    }

    // ------------------------------------------------------------------
    // 1. 档案、模块与 Policy 成对
    // ------------------------------------------------------------------

    @Test
    @DisplayName("回忆档案必须含全量 live 模块与 cognition-recall:1，模型版本仍 live")
    void cognitionManifestIsExplicitAndStrict() {
        RunManifest manifest = cognitionManifest();
        assertTrue(manifest.hasCognitionRecall(), "新档案必须显式回忆");
        assertTrue(manifest.hasLiveDecisions(), "回忆建立在 live 决定之上");
        assertEquals(1, manifest.moduleVersions().get("cognition-recall"));

        Map<String, Integer> missingRecall = liveModules();
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_COGNITION_RECALL_EXPERIMENT_V1, missingRecall, Map.of(),
                RunManifest.LIVE_DECISIONS_V1), "缺 cognition-recall:1 必须拒绝");

        Map<String, Integer> plainLive = liveModules();
        plainLive.put("cognition-recall", 1);
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1, plainLive, Map.of(),
                RunManifest.LIVE_DECISIONS_V1), "schema10 规则不得夹带回忆模块");

        assertThrows(IllegalArgumentException.class, () -> new RunManifest(RUN, SEED,
                RunManifest.RULES_COGNITION_RECALL_EXPERIMENT_V1, cognitionModules(), Map.of(),
                RunManifest.SCRIPTED_DECISIONS_V1), "回忆档案必须是 live 模型版本");
    }

    @Test
    @DisplayName("配置成对：新档必须非空 Policy，旧 live 档必须为空；先拒绝再开日志")
    void policyPairsWithModuleBeforeOpeningJournal() throws IOException {
        Path newFile = tempDir.resolve("no-policy.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(newFile,
                cognitionManifest(), OPENINGS, registry(), MFG, charter(), roles(),
                new InformationRun.Config(READERS), liveConfig()),
                "回忆档案缺 Policy 必须拒绝");
        assertFalse(Files.exists(newFile), "拒绝必须发生在创建任何文件之前");

        Path oldFile = tempDir.resolve("old-with-policy.journal");
        assertThrows(IllegalArgumentException.class, () -> new WorldRuntime(oldFile,
                liveManifest(), OPENINGS, registry(), MFG, charter(), roles(),
                new InformationRun.Config(READERS, FAST), liveConfig()),
                "schema10 档案不得携带回忆 Policy");
        assertFalse(Files.exists(oldFile));
    }

    @Test
    @DisplayName("新档案写入 schema11；旧 live 档仍 schema10")
    void schemaVersionIsElevenOnlyForCognition() throws IOException {
        Path cognition = tempDir.resolve("schema11.journal");
        try (WorldRuntime rt = open(cognition)) {
            assertNotNull(rt.snapshot());
        }
        assertEquals(11, persistedSchema(cognition), "回忆档案必须是 schema11");

        Path live = tempDir.resolve("schema10.journal");
        try (WorldRuntime rt = new WorldRuntime(live, liveManifest(), OPENINGS, registry(),
                MFG, charter(), roles(), new InformationRun.Config(READERS), liveConfig())) {
            assertNotNull(rt.snapshot());
        }
        assertEquals(10, persistedSchema(live), "旧 live 档保持 schema10");
    }

    // ------------------------------------------------------------------
    // 2. 旧档案：视图与输入逐字保持
    // ------------------------------------------------------------------

    @Test
    @DisplayName("旧 live 档：视图无 recall 字段，输入键集与旧版完全相同")
    void oldProfileKeepsViewAndInputShape() throws IOException {
        Path file = tempDir.resolve("old-shape.journal");
        try (WorldRuntime rt = new WorldRuntime(file, liveManifest(), OPENINGS, registry(),
                MFG, charter(), roles(), new InformationRun.Config(READERS), liveConfig())) {
            rt.beginDecisionSession("sess");
            publish(rt, "d-1", "旧档资料", 1_000L);
            read(rt, ALICE, "d-1", 1_500L);
            RoleView view = rt.roleView(ALICE);
            assertNull(view.recall(), "旧档视图不得出现回忆摘要");
            assertEquals(1, view.observations().size());
            assertNull(view.observations().get(0).recall(), "旧档经历不得出现回忆来源");

            RoleTradingView trading = rt.roleTradingView(rt.openRoleSession(ALICE));
            String input = DecisionRun.inputText(trading, WAKE, rt.snapshot().timeMillis(),
                    List.of(), trading.tools(), liveConfig());
            JsonNode root = read(input);
            assertEquals(Set.of("toolContractVersion", "person", "wakeReason", "observedAt",
                    "time", "appointments", "profile", "research", "targets", "children",
                    "notices", "pendingActions", "tools"), keys(root),
                    "旧档输入顶层键集不得变化");
            assertEquals(Set.of("accounts", "holdings", "market", "catalog", "readExperiences",
                            "activeReadings"), keys(root.get("profile")),
                    "旧档 profile 键集不得变化");
            assertEquals(Set.of("docId", "version", "title", "body", "source", "publishedAt",
                            "completedAt"), keys(root.get("profile").get("readExperiences").get(0)),
                    "旧档经历条目键集不得变化");
            assertFalse(input.contains("recallTruncated"), "旧档不得出现回忆标志");
            assertFalse(input.contains("permissions"), "旧档不得出现权限块");
        }
    }

    // ------------------------------------------------------------------
    // 3. 新档案：视图、输入与遗忘
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新档：只给选中经历并附本人 scope 来源；输入无 RAW READ-n/Policy/score")
    void newProfileScopesRecallAndKeepsCalibrationOut() throws IOException {
        Path file = tempDir.resolve("new-shape.journal");
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("sess");
            publish(rt, "d-1", "新档资料", 1_000L);
            read(rt, ALICE, "d-1", 1_500L);

            RoleView view = rt.roleView(ALICE);
            assertNotNull(view.recall(), "新档视图必须有回忆摘要");
            assertFalse(view.recall().budgetTruncated());
            assertEquals(1, view.observations().size());
            RoleView.Experience experience = view.observations().get(0);
            assertNotNull(experience.recall(), "新档经历必须记录最近获取");
            assertEquals(1, experience.recall().lastReadRef().index(), "首个本人阅读序号为 1");
            assertEquals(experience.completedAt(), experience.recall().lastReadAt());

            RoleTradingView trading = rt.roleTradingView(rt.openRoleSession(ALICE));
            String input = DecisionRun.inputText(trading, WAKE, rt.snapshot().timeMillis(),
                    List.of(), trading.tools(), liveConfig());
            JsonNode root = read(input);

            JsonNode item = root.get("profile").get("readExperiences").get(0);
            assertEquals(Set.of("docId", "version", "title", "body", "source", "publishedAt",
                            "completedAt", "lastReadRef", "lastReadAt"), keys(item),
                    "新档经历条目只多本人引用与最近获取");
            assertEquals(ALICE, root.get("person").asText());
            assertEquals(Set.of("docId", "version", "index"), keys(item.get("lastReadRef")),
                    "lastReadRef 只含本人可见的三项");
            assertTrue(root.has("recallTruncated"), "新档必须给出回忆截断标志");
            assertTrue(root.has("permissions"), "新档必须给出当前准确权限");
            assertTrue(root.get("permissions").size() > 0);

            assertFalse(input.contains("READ-"), "不得输出全局阅读序号");
            assertFalse(input.contains("\"score\""), "不得输出衰减分数");
            assertFalse(input.contains("halfLife"), "不得输出实验半衰期");
            assertFalse(input.contains("policy"), "不得输出实验 Policy");
            assertFalse(input.contains("internalSource"), "不得输出内部来源");
        }
    }

    @Test
    @DisplayName("真实完成→遗忘→重读未到期不恢复→完成恢复，原 Observation 不变")
    void forgetThenRealRereadRefreshesWithoutRewritingObservation() throws IOException {
        Path file = tempDir.resolve("forget.journal");
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("sess");
            publish(rt, "d-1", "会被遗忘的资料", 1_000L);
            read(rt, ALICE, "d-1", 1_500L);
            assertEquals(1, rt.roleView(ALICE).observations().size());
            InformationModel.ReadObservation original = observation(rt, ALICE, "d-1");

            // 遗忘：1 秒半衰期、阈值 500 → 2 秒后 250
            long now = rt.snapshot().timeMillis();
            rt.advanceTo(now + 2_000L);
            assertEquals(0, rt.roleView(ALICE).observations().size(), "阈值以下必须不可回忆");
            assertTrue(rt.canCite(ALICE, "d-1", 1), "遗忘不改变历史引用资格");

            // 重新阅读：开始但未到期，仍不可回忆
            long start = rt.snapshot().timeMillis() + 100L;
            rt.scheduleStartReading("r-2", "rt-2", start, ALICE, "d-1", 1);
            rt.advanceTo(start);
            assertTrue(Files.exists(file));
            assertEquals(0, rt.roleView(ALICE).observations().size(), "未到期不得恢复回忆");
            assertTrue(rt.canCite(ALICE, "d-1", 1), "未到期仍只有旧引用资格");

            rt.advanceTo(start + READ_MILLIS);
            RoleView after = rt.roleView(ALICE);
            assertEquals(1, after.observations().size(), "真实完成后恢复");
            RoleView.Experience experience = after.observations().get(0);
            assertTrue(experience.recall().lastReadAt() > original.completedAt(),
                    "最近获取必须是新的真实完成时刻");
            assertEquals(original.readId(), experience.ref().index() == 1 ? original.readId() : "",
                    "原 Observation 身份不变");
            assertEquals(original, observation(rt, ALICE, "d-1"),
                    "重读不得改写原 Observation 内容与完成时刻");
        }
    }

    @Test
    @DisplayName("本人 scoped 引用稳定：他人多读不改变我的序号")
    void otherPersonsReadsDoNotShiftMyRefs() throws IOException {
        Path file = tempDir.resolve("scope.journal");
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("sess");
            publish(rt, "d-1", "共同可见资料", 1_000L);
            read(rt, ALICE, "d-1", 1_500L);
            for (int i = 0; i < 2; i++) {
                long start = rt.snapshot().timeMillis() + 100L;
                rt.scheduleStartReading("rb-" + i, "rtb-" + i, start, BOB, "d-1", 1);
                rt.advanceTo(start + READ_MILLIS);
            }
            RoleView aliceView = rt.roleView(ALICE);
            assertEquals(1, aliceView.observations().get(0).ref().index());
            assertEquals(1, aliceView.observations().get(0).recall().lastReadRef().index(),
                    "他人阅读次数不得进入本人引用");
            assertEquals(ALICE, aliceView.observations().get(0).recall().lastReadRef().personPartyId());
        }
    }

    // ------------------------------------------------------------------
    // 4. research 目录正文旁路
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已读 research 正文受同一回忆约束：read 仍 true，body 变空")
    void researchBodyCannotBypassForgetting() throws IOException {
        Path file = tempDir.resolve("research.journal");
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("sess");
            publish(rt, "d-1", "研究底稿", 1_000L);
            read(rt, ALICE, "d-1", 1_500L);
            submitResearch(rt, "res-1", "r-1");

            long bobStart = rt.snapshot().timeMillis() + 100L;
            rt.scheduleStartReading("rb-r-1", "rtb-r-1", bobStart, BOB, "r-1", 1);
            rt.advanceTo(bobStart + 60_001L);
            RoleTradingView readView = rt.roleTradingView(rt.openRoleSession(BOB));
            assertEquals(1, readView.research().size());
            RoleTradingView.ResearchView note = readView.research().get(0);
            assertTrue(note.read(), "读完后 read 必须为 true");
            assertNotNull(note.body(), "同 cut 选中时必须给正文");

            long now = rt.snapshot().timeMillis();
            rt.advanceTo(now + 2_000L);
            RoleTradingView forgotten = rt.roleTradingView(rt.openRoleSession(BOB));
            RoleTradingView.ResearchView gone = forgotten.research().get(0);
            assertTrue(gone.read(), "遗忘不得把历史 read 改成 false");
            assertNull(gone.body(), "遗忘后正文必须为空，不能旁路");

            String input = DecisionRun.inputText(forgotten, WAKE, rt.snapshot().timeMillis(),
                    List.of(), forgotten.tools(), liveConfig());
            JsonNode research = read(input).get("research").get(0);
            assertFalse(research.has("body"), "输入不得带已忘正文");
            assertFalse(research.get("bodyAvailable").asBoolean(true),
                    "新档必须给出 bodyAvailable=false");
        }
    }

    // ------------------------------------------------------------------
    // 5. 输入预算与整批原子性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("字符超限抛 typed 诊断且整批无提交；第二人超限不留第一人决定")
    void oversizedInputFailsTypedWithoutPartialBatch() throws IOException {
        Path file = tempDir.resolve("too-large.journal");
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("sess");
            String huge = "字".repeat(40_000);
            publish(rt, "d-big", huge, 1_000L);
            read(rt, BOB, "d-big", 1_500L);
            publish(rt, "d-small", "短文", 1_000L);
            read(rt, ALICE, "d-small", 1_500L);

            long position = rt.snapshot().position();
            long time = rt.snapshot().timeMillis();
            WorldRuntime.DecisionInputTooLargeException failure = assertThrows(
                    WorldRuntime.DecisionInputTooLargeException.class,
                    () -> rt.openDecisions("too-large", List.of(
                            new DecisionRun.Request(ALICE, "a-1", WAKE, route(1)),
                            new DecisionRun.Request(BOB, "a-2", WAKE, route(1)))),
                    "第二人超限必须让整批失败");
            assertEquals(WorldRuntime.DecisionInputTooLargeException.CODE, failure.code());
            assertTrue(failure.actualChars() > failure.maxChars());
            assertTrue(failure.actualBytes() > 0);
            assertEquals(position, rt.snapshot().position(), "失败批次不得提交");
            assertEquals(time, rt.snapshot().timeMillis());
            assertEquals(0, rt.snapshot().decisions().orElseThrow().state().decisions().size(),
                    "第一人也不得留下决定");
            assertEquals(1, rt.snapshot().decisions().orElseThrow().state().nextAttemptSequence(),
                    "不得留下任何尝试或预留");
            assertTrue(rt.snapshot().decisions().orElseThrow().state().personUsage().isEmpty(),
                    "不得留下逐人用量");
        }
    }

    @Test
    @DisplayName("未超限时 ProviderPlan 字节仍等于实际冻结输入")
    void planBytesMatchFrozenInput() throws IOException {
        Path file = tempDir.resolve("plan.journal");
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("sess");
            publish(rt, "d-1", "正常长度资料", 1_000L);
            read(rt, ALICE, "d-1", 1_500L);
            assertEquals("DECIDING:1", rt.openDecisions("ok",
                    List.of(new DecisionRun.Request(ALICE, "a-1", WAKE, route(1)))));
            DecisionModel.Decision decision = DecisionModel.decision(
                    rt.snapshot().decisions().orElseThrow().state(), "a-1").orElseThrow();
            assertEquals(decision.inputText().getBytes(StandardCharsets.UTF_8).length,
                    decision.plan().inputBytes());
        }
    }

    // ------------------------------------------------------------------
    // 6. 管理员审计与恢复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("recallAudit：旧档 empty；新档同 cut 返回 Policy 与完整结果")
    void recallAuditIsAdminOnlyAndCutBound() throws IOException {
        Path oldFile = tempDir.resolve("audit-old.journal");
        try (WorldRuntime rt = new WorldRuntime(oldFile, liveManifest(), OPENINGS, registry(),
                MFG, charter(), roles(), new InformationRun.Config(READERS), liveConfig())) {
            assertEquals(Optional.empty(), rt.recallAudit(ALICE), "旧档没有回忆审计");
        }

        Path file = tempDir.resolve("audit.journal");
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("sess");
            publish(rt, "d-1", "审计资料", 1_000L);
            read(rt, ALICE, "d-1", 1_500L);
            WorldRuntime.RecallAudit audit = rt.recallAudit(ALICE).orElseThrow();
            assertEquals(RUN, audit.runId());
            assertEquals(rt.snapshot().position(), audit.position());
            assertEquals(rt.snapshot().timeMillis(), audit.timeMillis());
            assertEquals(ALICE, audit.personPartyId());
            assertEquals(FAST, audit.policy());
            assertEquals(1, audit.result().selected().size());
            assertEquals(1, audit.result().eligibleCount());
            assertEquals(0, audit.result().forgottenCount());

            assertThrows(IllegalArgumentException.class, () -> rt.recallAudit("nobody"),
                    "未登记人物不接受审计查询");
        }
    }

    @Test
    @DisplayName("恢复：同 Policy 重开输入一致；换 Policy 必须拒绝")
    void restoreReplaysFrozenInputAndRejectsNewPolicy() throws IOException {
        Path file = tempDir.resolve("restore.journal");
        String before;
        long bytes;
        try (WorldRuntime rt = open(file)) {
            rt.beginDecisionSession("sess");
            publish(rt, "d-1", "恢复资料", 1_000L);
            read(rt, ALICE, "d-1", 1_500L);
            rt.openDecisions("o-1", List.of(new DecisionRun.Request(ALICE, "a-1", WAKE,
                    route(1))));
            DecisionModel.Decision decision = DecisionModel.decision(
                    rt.snapshot().decisions().orElseThrow().state(), "a-1").orElseThrow();
            before = decision.inputText();
            bytes = decision.plan().inputBytes();
        }

        try (WorldRuntime rt = open(file)) {
            DecisionModel.Decision decision = DecisionModel.decision(
                    rt.snapshot().decisions().orElseThrow().state(), "a-1").orElseThrow();
            assertEquals(before, decision.inputText(), "恢复必须重放同一冻结输入");
            assertEquals(bytes, decision.plan().inputBytes());
        }

        assertThrows(IOException.class, () -> new WorldRuntime(file, cognitionManifest(),
                OPENINGS, registry(), MFG, charter(), roles(),
                new InformationRun.Config(READERS,
                        new CognitionRecall.Policy(2_000L, 500, 2, 100_000L)), liveConfig()),
                "同一日志换 Policy 必须拒绝");
    }

    // ------------------------------------------------------------------
    // 7. Web 实验档案
    // ------------------------------------------------------------------

    @Test
    @DisplayName("openCognition 复用真实经济开局，冻结 14 日/100/8/12000")
    void webScenarioCognitionProfileIsExplicit() throws Exception {
        RunManifest manifest = edu.cufe.auction.web.WorldWebScenario.cognitionManifest();
        assertTrue(manifest.hasCognitionRecall());
        assertEquals(RunManifest.RULES_COGNITION_RECALL_EXPERIMENT_V1, manifest.rulesVersion());
        assertEquals(RunManifest.LIVE_DECISIONS_V1, manifest.modelConfigVersion());

        Path journal = tempDir.resolve("web-cognition.journal");
        WorldRuntime rt = edu.cufe.auction.web.WorldWebScenario.openCognition(journal).runtime();
        try {
            WorldRuntime.RecallAudit audit = rt.recallAudit(ALICE).orElseThrow();
            assertEquals(14L * DAY, audit.policy().halfLifeMillis());
            assertEquals(100, audit.policy().thresholdPermille());
            assertEquals(8, audit.policy().maxItems());
            assertEquals(12_000L, audit.policy().maxContentChars());
        } finally {
            rt.close();
        }
        assertEquals(11, persistedSchema(journal, edu.cufe.auction.web.WorldWebScenario.COGNITION_RUN_ID));
    }

    // ------------------------------------------------------------------
    // 夹具与工具
    // ------------------------------------------------------------------

    private WorldRuntime open(Path file) throws IOException {
        return new WorldRuntime(file, cognitionManifest(), OPENINGS, registry(), MFG, charter(),
                roles(), new InformationRun.Config(READERS, FAST), liveConfig());
    }

    private void publish(WorldRuntime rt, String docId, String body, long due) throws IOException {
        long now = rt.snapshot().timeMillis();
        rt.schedulePublishDocument("pub-" + docId, "p-" + docId, now + due, docId, ALICE,
                InformationModel.Audience.publicAudience(), "标题-" + docId, body,
                "公开来源", READ_MILLIS, List.of("internal-" + docId));
        rt.advanceTo(now + due);
    }

    private void read(WorldRuntime rt, String person, String docId, long due) throws IOException {
        long start = Math.max(due, rt.snapshot().timeMillis() + 100L);
        rt.scheduleStartReading("rd-" + person + "-" + docId, "r-" + person + "-" + docId,
                start, person, docId, 1);
        rt.advanceTo(start + READ_MILLIS);
    }

    private void submitResearch(WorldRuntime rt, String researchId, String docId)
            throws IOException {
        String args = "{\"tool\":\"" + RoleTradingView.TOOL_RESEARCH_SUBMIT + "\",\"args\":{"
                + "\"researchId\":\"" + researchId + "\",\"organizationPartyId\":\""
                + ORG_F + "\",\"productOwnerId\":\"" + PRODUCT + "\",\"symbol\":\"" + SYM
                + "\",\"docId\":\"" + docId + "\",\"title\":\"研究-" + researchId
                + "\",\"body\":\"研究正文-" + researchId + "\",\"citations\":[{\"docId\":\"d-1\","
                + "\"version\":1}]}}";
        String actionId = "act-" + researchId;
        rt.openDecisions("open-" + researchId, List.of(new DecisionRun.Request(ALICE, actionId,
                WAKE, route(1))));
        WorldRuntime.DispatchResult dispatched = rt.dispatchLiveDecision("disp-" + researchId,
                actionId);
        assertEquals("DISPATCHED", dispatched.verdict(), dispatched.reason());
        DecisionRun.Call call = dispatched.call();
        assertEquals(WorldRuntime.CompletionOffer.QUEUED, rt.offerLiveDecisionCompletion(
                LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),
                        call.actionId(), call.attemptId(), call.attemptNo(),
                        call.recoveryGeneration(), args,
                        DecisionModel.Usage.unknownCost(1, 200, 40), "provider:test"))));
        assertEquals(1, rt.drainDecisionCallbacks());
        rt.advanceTo(rt.snapshot().timeMillis() + THINK + ACTION + 1L);
        if (rt.snapshot().trading().orElseThrow().trading().research().isEmpty()) {
            StringBuilder why = new StringBuilder("研究未建立：");
            for (RoleTradingRun.Report report
                    : rt.snapshot().trading().orElseThrow().reports().values()) {
                why.append('[').append(report.kind()).append('/').append(report.accepted())
                        .append('/').append(report.reason()).append(']');
            }
            for (DecisionModel.Decision decision
                    : rt.snapshot().decisions().orElseThrow().state().decisions()) {
                why.append("{dec=").append(decision.actionId()).append('/')
                        .append(decision.status()).append('/').append(decision.outcome())
                        .append("/attempts=").append(decision.attempts().size()).append('}');
            }
            why.append("pending=").append(rt.snapshot().pending().size());
            throw new AssertionError(why.toString());
        }
    }

    private static InformationModel.ReadObservation observation(WorldRuntime rt, String person,
                                                               String docId) {
        for (InformationModel.ReadObservation item
                : rt.snapshot().information().orElseThrow().information().observations()) {
            if (item.personPartyId().equals(person) && item.docId().equals(docId)) {
                return item;
            }
        }
        throw new AssertionError("缺少真实经历：" + person + "/" + docId);
    }

    private static DecisionModel.Route route(long revision) {
        return new DecisionModel.Route("research", "key-1", "deepseek-flash", revision, 20,
                1_000_000, 1_024, 2);
    }

    private JsonNode read(String text) throws IOException {
        return json.readTree(text);
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            names.add(iterator.next());
        }
        return names;
    }

    private static int persistedSchema(Path file) throws IOException {
        return persistedSchema(file, RUN);
    }

    private static int persistedSchema(Path file, String runId) throws IOException {
        try (WorldJournal reader = new WorldJournal(file, runId)) {
            int schema = -1;
            for (WorldCommit commit : reader.commits()) {
                for (WorldEvent event : commit.events()) {
                    if (!"STATE_SNAPSHOT_V1".equals(event.type())) {
                        continue;
                    }
                    JsonNode node = new JsonMapper().readTree(event.payload());
                    if (node.has("schemaVersion")) {
                        schema = node.get("schemaVersion").asInt();
                    }
                }
            }
            return schema;
        }
    }

    private static final List<String> READERS =
            List.of(ALICE, BOB, CAROL, DAVE, ERIN);

    private static Map<String, Integer> cognitionModules() {
        Map<String, Integer> modules = liveModules();
        modules.put("cognition-recall", 1);
        return modules;
    }

    private static Map<String, Integer> liveModules() {
        Map<String, Integer> modules = new TreeMap<>();
        for (String name : List.of("cash", "scheduler", "registry", "securities", "matching",
                "manufacturing", "governance", "roles", "information", "trading", "decisions",
                "dividend", "live-decisions")) {
            modules.put(name, 1);
        }
        return modules;
    }

    private static RunManifest cognitionManifest() {
        return new RunManifest(RUN, SEED,
                RunManifest.RULES_COGNITION_RECALL_EXPERIMENT_V1, cognitionModules(),
                Map.of("fixture", "cognition-runtime-test"), RunManifest.LIVE_DECISIONS_V1);
    }

    private static RunManifest liveManifest() {
        return new RunManifest(RUN, SEED,
                RunManifest.RULES_LIVE_DECISION_WORLD_EXPERIMENT_V1, liveModules(),
                Map.of("fixture", "cognition-runtime-test"), RunManifest.LIVE_DECISIONS_V1);
    }

    private static RoleRun.Config roles() {
        List<RoleAccess.ProductMandate> mandates = List.of(
                new RoleAccess.ProductMandate(ORG_F, PRODUCT, List.of(ACC_FUND)));
        List<RoleAccess.Appointment> appointments = new ArrayList<>(List.of(
                new RoleAccess.Appointment("A-1", ALICE, ORG_F, Role.RESEARCHER, 0L, END, null),
                new RoleAccess.Appointment("A-2", BOB, ORG_F, Role.PORTFOLIO_MANAGER, 0L, END,
                        null),
                new RoleAccess.Appointment("A-3", CAROL, ORG_F, Role.RISK_OFFICER, 0L, END, null),
                new RoleAccess.Appointment("A-4", DAVE, ORG_F, Role.EXECUTION_TRADER, 0L, END,
                        null)));
        return new RoleRun.Config(mandates, appointments);
    }

    private static BoardModel.Charter charter() {
        return new BoardModel.Charter("cufe", List.of(
                new BoardModel.Seat("s1", ALICE), new BoardModel.Seat("s2", BOB),
                new BoardModel.Seat("s3", CAROL), new BoardModel.Seat("s4", DAVE),
                new BoardModel.Seat("s5", ERIN)), "s1", BoardModel.QUORUM);
    }

    private static DecisionModel.Config liveConfig() {
        DecisionModel.Usage cap = DecisionModel.Usage.unknownCost(500, 20_000_000, 5_000_000);
        Map<String, DecisionModel.Usage> persons = new TreeMap<>();
        for (String person : READERS) {
            persons.put(person, DecisionModel.Usage.unknownCost(100, 4_000_000, 1_000_000));
        }
        Map<String, Long> actions = new TreeMap<>();
        for (String tool : List.of(RoleTradingView.TOOL_READING_START,
                RoleTradingView.TOOL_READING_CANCEL, RoleTradingView.TOOL_RESEARCH_SUBMIT,
                RoleTradingView.TOOL_TARGET_SET, RoleTradingView.TOOL_TARGET_REVIEW,
                RoleTradingView.TOOL_TARGET_HALT, RoleTradingView.TOOL_TARGET_CONFIRM,
                RoleTradingView.TOOL_TARGET_CANCEL, RoleTradingView.TOOL_CHILD_SUBMIT,
                RoleTradingView.TOOL_CHILD_CANCEL)) {
            actions.put(tool, ACTION);
        }
        return new DecisionModel.Config(DecisionModel.PROVIDER_DEEPSEEK_HTTP,
                "deepseek:cognition-test", DecisionModel.ESTIMATE_VERSION,
                Map.of(WAKE, THINK), actions,
                new DecisionModel.Limits(1, 32_768, 65_536, 60_000, 8_192, 4, 2, 60_000L),
                new DecisionModel.Budget(cap, persons), DecisionModel.Profile.LIVE_HTTP_V1);
    }

    private static final ManufacturingRun.Config MFG = new ManufacturingRun.Config(
            new ManufacturingModel.Opening("company", "CO", "SUP", "CUS", "OPS"), SYM);

    private static final List<OpeningAccount> OPENINGS = List.of(
            new OpeningAccount(ACC_FUND, PRODUCT, new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount(ACC_INV, INV, new BigDecimal("0.00"), "实验开局历史禀赋"),
            new OpeningAccount("CO", "company", new BigDecimal("1000.00"), "实验开局历史禀赋"),
            new OpeningAccount("SUP", "supplier", new BigDecimal("100.00"), "实验开局历史禀赋"),
            new OpeningAccount("CUS", "customer", new BigDecimal("2000.00"), "实验开局历史禀赋"),
            new OpeningAccount("OPS", "ops", new BigDecimal("0.00"), "实验开局历史禀赋"));

    private static OpeningRegistry registry() {
        List<OpeningRegistry.Party> parties = List.of(
                new OpeningRegistry.Party(ORG_F, PartyKind.ORGANIZATION, "中央财经资管"),
                new OpeningRegistry.Party("cufe", PartyKind.ORGANIZATION, "中央财经制造公司"),
                new OpeningRegistry.Party(ALICE, PartyKind.PERSON, "甲"),
                new OpeningRegistry.Party(BOB, PartyKind.PERSON, "乙"),
                new OpeningRegistry.Party(CAROL, PartyKind.PERSON, "丙"),
                new OpeningRegistry.Party(DAVE, PartyKind.PERSON, "丁"),
                new OpeningRegistry.Party(ERIN, PartyKind.PERSON, "戊"),
                new OpeningRegistry.Party(INV, PartyKind.PERSON, "外部投资者"));
        List<OpeningRegistry.PropertyOwner> owners = List.of(
                new OpeningRegistry.PropertyOwner(PRODUCT, OpeningRegistry.OwnerKind.PRODUCT,
                        null),
                new OpeningRegistry.PropertyOwner("orgf", OpeningRegistry.OwnerKind.ORGANIZATION,
                        ORG_F),
                new OpeningRegistry.PropertyOwner(ALICE, OpeningRegistry.OwnerKind.PERSON, ALICE),
                new OpeningRegistry.PropertyOwner(BOB, OpeningRegistry.OwnerKind.PERSON, BOB),
                new OpeningRegistry.PropertyOwner(CAROL, OpeningRegistry.OwnerKind.PERSON, CAROL),
                new OpeningRegistry.PropertyOwner(DAVE, OpeningRegistry.OwnerKind.PERSON, DAVE),
                new OpeningRegistry.PropertyOwner(ERIN, OpeningRegistry.OwnerKind.PERSON, ERIN),
                new OpeningRegistry.PropertyOwner(INV, OpeningRegistry.OwnerKind.PERSON, INV),
                new OpeningRegistry.PropertyOwner("company",
                        OpeningRegistry.OwnerKind.ORGANIZATION, "cufe"),
                new OpeningRegistry.PropertyOwner("supplier", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("customer", OpeningRegistry.OwnerKind.AGGREGATE,
                        null),
                new OpeningRegistry.PropertyOwner("ops", OpeningRegistry.OwnerKind.AGGREGATE,
                        null));
        List<OpeningRegistry.Security> securities = List.of(
                new OpeningRegistry.Security(SYM, "cufe", 200L, new BigDecimal("10.00")));
        List<OpeningRegistry.ShareAllocation> allocations = List.of(
                new OpeningRegistry.ShareAllocation(ACC_INV, SYM, 200L,
                        new BigDecimal("10.0000"), ShareClass.FLOAT));
        return new OpeningRegistry(parties, owners, securities, allocations);
    }
}
