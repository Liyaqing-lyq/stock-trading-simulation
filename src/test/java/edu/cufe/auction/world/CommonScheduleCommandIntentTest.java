package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用排程原意信封（{@code command-intent} 模块）。
 *
 * <p>旧规则的外部排程命令正文只有业务参数，恢复时只能把「待办参数 == 命令正文」当作来源，
 * 因此「保留原命令、同步改待办与排程事件」的自洽篡改无法鉴别。新规则把
 * {@code {intent:1, commandId, taskId, kind, phase, dueTimeMillis, durationMillis, body}}
 * 写进命令记录，待办只由这份原意派生：本类同时验证正例（真实入口排程、推进、重开、同参数重试）
 * 与反例（改 id/到期/种类/正文、工程 BEGIN 提前量、周期工时），并覆盖缺信封、旧规则带信封、
 * 模块表不符、同刻顺序与重演异常后继续。</p>
 */
@DisplayName("通用排程原意信封：新规则冻结身份与时点、旧档不迁移")
class CommonScheduleCommandIntentTest {

    /** 帧改写专用：保留 JSON 小数位（BigDecimal），避免把 40.0000 读成 40.0 后误判配置改写。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 正例：各类外部入口排程 → 推进 → 重开 → 同参数重试
    // ------------------------------------------------------------------

    @Test
    @DisplayName("治理/任职/分红/发布/阅读全部外部入口：写信封、可推进、重开后同参数幂等")
    void externalIntentsAreEnvelopedAndIdempotent() throws Exception {
        Path journal = tempDir.resolve("intents.journal");
        String[] outcomes;
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(journal)) {
            outcomes = scheduleEveryExternalEntry(world);
            world.advanceTo(600L);
            assertEquals(16, outcomes.length);
        }
        // 每个外部命令的正文都是完整原意信封（版本/命令/任务/种类/阶段/到期/工时/正文）
        assertEnvelope(journal, "cmd:m1", "t-m1", PendingTask.GOVERNANCE_MEETING_V1,
                "COMPLETION", 100L, 0L);
        assertEnvelope(journal, "cmd:mat1", "t-mat1", PendingTask.GOVERNANCE_MATERIAL_V1,
                "COMPLETION", 150L, 0L);
        assertEnvelope(journal, "cmd:bud1", "t-bud1", PendingTask.GOVERNANCE_BUDGET_PROPOSAL_V1,
                "COMPLETION", 160L, 0L);
        assertEnvelope(journal, "cmd:v1", "t-v1", PendingTask.GOVERNANCE_VOTE_V1,
                "COMPLETION", 350L, 0L);
        assertEnvelope(journal, "cmd:imp1", "t-imp1", PendingTask.GOVERNANCE_IMPLEMENT_V1,
                "COMPLETION", 500L, 0L);
        assertEnvelope(journal, "cmd:ap1", "t-ap1", PendingTask.ROLE_APPOINT_V1, "RULES", 650L, 0L);
        assertEnvelope(journal, "cmd:rv1", "t-rv1", PendingTask.ROLE_REVOKE_V1, "RULES", 660L, 0L);
        assertEnvelope(journal, "cmd:pub1", "t-pub1", PendingTask.INFORMATION_PUBLISH_V1,
                "COMPLETION", 700L, 0L);
        assertEnvelope(journal, "cmd:rd1", "t-rd1", PendingTask.INFORMATION_START_READ_V1,
                "COMPLETION", 800L, 0L);
        assertEnvelope(journal, "cmd:dm1", "t-dm1", PendingTask.DIVIDEND_MEETING_V1,
                "COMPLETION", 900L, 0L);
        assertEnvelope(journal, "cmd:dp1", "t-dp1", PendingTask.GOVERNANCE_DIVIDEND_PROPOSAL_V1,
                "COMPLETION", 950L, 0L);

        // 重开：整条历史必须能按信封重演通过，随后同参数重试返回原回报（不再新排）
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(journal)) {
            long position = world.snapshot().position();
            String[] again = scheduleEveryExternalEntry(world);
            assertArrayEquals(outcomes, again, "同参数重发必须返回原 SCHEDULED 回报");
            assertEquals(position, world.snapshot().position(), "幂等重试不得新增提交");
        }
    }

    /** 按真实入口排 14 条外部命令，返回各自回报。 */
    private static String[] scheduleEveryExternalEntry(WorldRuntime world) throws Exception {
        List<String> results = new ArrayList<>();
        results.add(world.scheduleBoardMeeting("m1", "t-m1", 100L, "s1", "meeting:1", 200L, 300L,
                400L, List.of("s1", "s2", "s3", "s4", "s5"), List.of()));
        results.add(world.scheduleBoardMaterial("mat1", "t-mat1", 150L, "s2", "meeting:1",
                "doc:material", "src:1", 1L));
        results.add(world.scheduleBudgetProposal("bud1", "t-bud1", 160L, "s2", "meeting:1",
                List.of(new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                        new java.math.BigDecimal("10000.00")))));
        results.add(world.scheduleBoardVote("v1", "t-v1", 350L, "s2", "meeting:1",
                BoardModel.Vote.YES));
        results.add(world.scheduleBudgetImplementation("imp1", "t-imp1", 500L, "s1",
                "meeting:1"));
        results.add(world.scheduleAppointment("ap1", "t-ap1", 650L, "AP-9", "nina",
                WorldWebScenario.ENGINE_A, RoleAccess.Role.RESEARCHER, 650L, 900_000L));
        results.add(world.scheduleRevokeAppointment("rv1", "t-rv1", 660L, "AP-9"));
        results.add(world.schedulePublishDocument("pub1", "t-pub1", 700L, "doc:one",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(), "标题",
                "正文", "实验来源", 1_000L, List.of()));
        results.add(world.schedulePublishDocumentVersion("pub1v", "t-pub1v", 750L, "doc:one",
                "标题二", "正文二", "实验来源", 1_000L, List.of()));
        results.add(world.scheduleStartReading("rd1", "t-rd1", 800L, "alice", "doc:one", 1L));
        results.add(world.scheduleCancelReading("cr1", "t-cr1", 850L, "alice",
                new RoleView.ReadingRef("alice", "doc:one", 1L, 1L)));
        results.add(world.scheduleBoardMeeting("dm1", "t-dm1", 900L, "s1", "meeting:div", 950L,
                1_000L, 1_100L, List.of("s1", "s2", "s3", "s4", "s5"), List.of(),
                BoardModel.Topic.DIVIDEND_AUTHORIZATION));
        results.add(world.scheduleDividendProposal("dp1", "t-dp1", 950L, "s2", "meeting:div",
                List.of(), new DividendModel.SchemeRequest("SC-1", WorldWebScenario.SYMBOL,
                        "CO", "ACC-CO", new java.math.BigDecimal("0.10"), 1_200L, 1_300L, 1_400L)));
        results.add(world.scheduleDividendAuthorization("di1", "t-di1", 960L, "s1",
                "meeting:div"));
        results.add(world.scheduleDividendSettlement("ds1", "t-ds1", 970L, "CO", "SC-1"));
        results.add(world.scheduleDividendImpairment("dm2", "t-dm2", 980L, "CO", "claim:1",
                new java.math.BigDecimal("1.00"), "src:impair"));
        return results.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // 反例：保留原命令，只改待办与排程事件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("改待办标识：与原命令信封不一致必须拒绝重开")
    void taskIdTamperRejected() throws Exception {
        Path base = governanceBase("task-id");
        Path attack = tamperPending(base, "cmd:v1", "t-v1", task -> {
            task.put("taskId", "t-renamed");
        }, event -> event.put("taskId", "t-renamed"));
        assertRejected(attack, "排程待办标识与原命令意图不一致：t-renamed");
    }

    @Test
    @DisplayName("改待办到期：与原命令信封的绝对时点不一致必须拒绝")
    void dueTamperRejected() throws Exception {
        Path base = governanceBase("due");
        Path attack = tamperPending(base, "cmd:v1", "t-v1", task -> {
            task.put("dueTimeMillis", 351L);
        }, event -> event.put("dueTimeMillis", 351L));
        assertRejected(attack, "排程待办到期与原命令意图不一致：t-v1");
    }

    @Test
    @DisplayName("改待办种类：与原命令信封种类不一致必须拒绝")
    void kindTamperRejected() throws Exception {
        Path base = governanceBase("kind");
        Path attack = tamperPending(base, "cmd:v1", "t-v1", task -> {
            task.put("kind", PendingTask.GOVERNANCE_MATERIAL_V1);
        }, event -> event.put("kind", PendingTask.GOVERNANCE_MATERIAL_V1));
        assertRejected(attack, "排程待办种类与原命令意图不一致：t-v1");
    }

    @Test
    @DisplayName("改待办正文：与原命令信封的域请求不一致必须拒绝")
    void bodyTamperRejected() throws Exception {
        Path base = governanceBase("body");
        Path attack = tamperPending(base, "cmd:v1", "t-v1", task -> {
            task.put("payload", task.get("payload").asText()
                    .replace("\"vote\":\"YES\"", "\"vote\":\"NO\""));
        }, event -> event.put("payload", event.get("payload").asText()
                .replace("\"vote\":\"YES\"", "\"vote\":\"NO\"")));
        assertRejected(attack, "排程待办参数与原命令意图不一致：t-v1");
    }

    @Test
    @DisplayName("命令正文不是信封：新规则必须明确拒绝，不能退回按待办直接执行")
    void missingEnvelopeRejected() throws Exception {
        Path base = governanceBase("no-envelope");
        Path attack = tempDir.resolve("no-envelope-attack.journal");
        Files.copy(base, attack, StandardCopyOption.REPLACE_EXISTING);
        int index = frameWithCommand(attack, "cmd:v1");
        JournalTamper.rewrite(attack, index, json -> {
            ObjectNode commit = (ObjectNode) read(json);
            String envelope = commit.get("commandContent").asText();
            String body = read(envelope).get("body").asText();
            commit.put("commandContent", body);
            for (JsonNode node : commit.get("events")) {
                if (!"STATE_SNAPSHOT_V1".equals(node.get("type").asText())) {
                    continue;
                }
                String snapshot = node.get("payload").asText();
                String updated = snapshot.replace("\"v1\":\"" + escape(envelope) + "\"",
                        "\"v1\":\"" + escape(body) + "\"");
                if (updated.equals(snapshot)) {
                    throw new IllegalStateException("命令记录未命中");
                }
                ((ObjectNode) node).put("payload", updated);
            }
            return write(commit);
        });
        assertRejected(attack, "新规则外部排程命令缺少意图信封：cmd:v1");
    }

    @Test
    @DisplayName("旧规则命令正文不得携带信封：模块缺失时显式拒绝，不按新格式执行")
    void legacyRulesRejectEnvelope() throws Exception {
        Path base = tempDir.resolve("legacy-base.journal");
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(base)) {
            world.scheduleBoardVote("v1", "t-v1", 100L, "s2", "meeting:1", BoardModel.Vote.YES);
        }
        assertTrue(commandContent(base, "cmd:v1").contains("\"vote\":\"YES\""),
                "旧规则命令正文必须是原域正文");
        assertTrue(!commandContent(base, "cmd:v1").contains("\"intent\""),
                "旧规则不得写入信封");

        // 旧档续写仍按旧格式（不隐式迁移）
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(base)) {
            world.scheduleBoardMaterial("mat1", "t-mat1", 200L, "s2", "meeting:1", "doc:m",
                    "src:1", 1L);
        }
        assertTrue(!commandContent(base, "cmd:mat1").contains("\"intent\""),
                "旧规则续写也不得出现信封");

        // 把旧档正文替换成同内容信封：旧规则必须拒绝
        Path attack = tempDir.resolve("legacy-attack.journal");
        Files.copy(base, attack, StandardCopyOption.REPLACE_EXISTING);
        int index = frameWithCommand(attack, "cmd:mat1");
        JournalTamper.rewrite(attack, index, json -> {
            ObjectNode commit = (ObjectNode) read(json);
            String body = commit.get("commandContent").asText();
            String envelope = "{\"intent\":1,\"commandId\":\"mat1\",\"taskId\":\"t-mat1\","
                    + "\"kind\":\"" + PendingTask.GOVERNANCE_MATERIAL_V1 + "\","
                    + "\"phase\":\"COMPLETION\",\"dueTimeMillis\":200,\"durationMillis\":0,"
                    + "\"body\":" + JSON.valueToTree(body) + "}";
            commit.put("commandContent", envelope);
            for (JsonNode node : commit.get("events")) {
                if (!"STATE_SNAPSHOT_V1".equals(node.get("type").asText())) {
                    continue;
                }
                String snapshot = node.get("payload").asText();
                String updated = snapshot.replace("\"mat1\":\"" + escape(body) + "\"",
                        "\"mat1\":\"" + escape(envelope) + "\"");
                if (updated.equals(snapshot)) {
                    throw new IllegalStateException("命令记录未命中");
                }
                ((ObjectNode) node).put("payload", updated);
            }
            return write(commit);
        });
        IOException failure = assertThrows(IOException.class,
                () -> WorldWebScenario.groupsRuntime(attack).close());
        assertTrue(chain(failure).contains("旧规则排程命令不得携带意图信封：cmd:mat1"),
                chain(failure));
    }

    @Test
    @DisplayName("模块表与规则严格匹配：缺 command-intent 或旧规则加模块都必须拒绝")
    void manifestModuleMismatchRejected() {
        java.util.Map<String, Integer> modules =
                new java.util.TreeMap<>(WorldWebScenario.commandIntentManifest().moduleVersions());
        modules.remove("command-intent");
        assertThrows(IllegalArgumentException.class,
                () -> new RunManifest(WorldWebScenario.GROUPS_RUN_ID, 11L,
                        RunManifest.RULES_COMMAND_INTENT_EXPERIMENT_V1, modules,
                        WorldWebScenario.commandIntentManifest().dataVersions(),
                        RunManifest.LIVE_DECISIONS_V1),
                "新规则缺 command-intent 模块必须拒绝");

        java.util.Map<String, Integer> extra =
                new java.util.TreeMap<>(WorldWebScenario.groupsManifest().moduleVersions());
        extra.put("command-intent", 1);
        assertThrows(IllegalArgumentException.class,
                () -> new RunManifest(WorldWebScenario.GROUPS_RUN_ID, 11L,
                        RunManifest.RULES_GROUPS_PLATFORM_EXPERIMENT_V1, extra,
                        WorldWebScenario.groupsManifest().dataVersions(),
                        RunManifest.LIVE_DECISIONS_V1),
                "旧规则带 command-intent 模块必须拒绝");
    }

    // ------------------------------------------------------------------
    // 反例：工程 BEGIN 屏障
    // ------------------------------------------------------------------

    @Test
    @DisplayName("工程 BEGIN 只改屏障到期：提前量与原命令工时不符必须拒绝")
    void engineeringLeadTamperRejected() throws Exception {
        Path base = engineeringBase("eng-lead");
        Path attack = tamperPending(base, "cmd:conv", "t-conv", task -> {
            task.put("dueTimeMillis", 98L);
        }, event -> event.put("dueTimeMillis", 98L));
        assertRejected(attack, "工程 BEGIN 提前量与原命令工时不一致：t-conv");
    }

    @Test
    @DisplayName("工程 BEGIN 同步改屏障与包装完成时点：与原命令绝对时点不符必须拒绝")
    void engineeringShiftBothRejected() throws Exception {
        Path base = engineeringBase("eng-shift");
        Path attack = tamperPending(base, "cmd:conv", "t-conv", task -> {
            task.put("dueTimeMillis", 1_099L);
            task.put("payload", task.get("payload").asText()
                    .replace("\"dueMillis\":100", "\"dueMillis\":1100"));
        }, event -> {
            event.put("dueTimeMillis", 1_099L);
            event.put("payload", event.get("payload").asText()
                    .replace("\"dueMillis\":100", "\"dueMillis\":1100"));
        });
        assertRejected(attack, "工程 BEGIN 完成时点与原命令意图不一致：t-conv");
    }

    @Test
    @DisplayName("工程 BEGIN 目标种类改成到会：与原命令信封种类不符必须拒绝")
    void engineeringTargetKindAttendRejected() throws Exception {
        Path base = engineeringBase("eng-kind-attend");
        Path attack = tamperPending(base, "cmd:conv", "t-conv", task -> {
            task.put("payload", task.get("payload").asText()
                    .replace("\"targetKind\":\"ENGINEERING_BOARD_V1\"",
                            "\"targetKind\":\"BOARD_ATTEND_V1\""));
        }, event -> event.put("payload", event.get("payload").asText()
                .replace("\"targetKind\":\"ENGINEERING_BOARD_V1\"",
                        "\"targetKind\":\"BOARD_ATTEND_V1\"")));
        assertRejected(attack, "工程 BEGIN 目标种类与原命令意图不一致：t-conv");
    }

    @Test
    @DisplayName("工程 BEGIN 目标种类改成经营：与原命令信封种类不符必须拒绝，且不在推进期崩溃")
    void engineeringTargetKindActionRejected() throws Exception {
        Path base = engineeringBase("eng-kind-action");
        Path attack = tamperPending(base, "cmd:conv", "t-conv", task -> {
            task.put("payload", task.get("payload").asText()
                    .replace("\"targetKind\":\"ENGINEERING_BOARD_V1\"",
                            "\"targetKind\":\"ENGINEERING_ACTION_V1\""));
        }, event -> event.put("payload", event.get("payload").asText()
                .replace("\"targetKind\":\"ENGINEERING_BOARD_V1\"",
                        "\"targetKind\":\"ENGINEERING_ACTION_V1\"")));
        assertRejected(attack, "工程 BEGIN 目标种类与原命令意图不一致：t-conv");
    }

    @Test
    @DisplayName("工程 BEGIN 改待办标识：与原命令信封任务标识不符必须拒绝")
    void engineeringTaskIdRejected() throws Exception {
        Path base = engineeringBase("eng-task-id");
        Path attack = tamperPending(base, "cmd:conv", "t-conv", task -> {
            task.put("taskId", "t-renamed");
        }, event -> event.put("taskId", "t-renamed"));
        assertRejected(attack, "排程待办标识与原命令意图不一致：t-renamed");
    }

    @Test
    @DisplayName("周期动作工时：同步改屏障与包装时点必须拒绝，工时不能靠待办自证")
    void periodicWorkTamperRejected() throws Exception {
        Path base = tempDir.resolve("periodic-base.journal");
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(base)) {
            world.schedulePeriodicAction("per", "t-per", 5_000L, WorldWebScenario.ENGINE_A,
                    "alice", "SCORE", 1L, "", 0L, "", 0L, "",
                    List.of(new PeriodicActionPayload.PeriodicMetric("RISK",
                            new java.math.BigDecimal("7.00"), "src:1", 1L)), "", 1_000L);
        }
        assertEnvelope(base, "cmd:per", "t-per", PendingTask.ENGINEERING_PERIODIC_V1, "RULES",
                5_000L, 1_000L);
        Path attack = tamperPending(base, "cmd:per", "t-per", task -> {
            task.put("dueTimeMillis", 3_999L);
            task.put("payload", task.get("payload").asText()
                    .replace("\"dueMillis\":5000", "\"dueMillis\":4999"));
        }, event -> {
            event.put("dueTimeMillis", 3_999L);
            event.put("payload", event.get("payload").asText()
                    .replace("\"dueMillis\":5000", "\"dueMillis\":4999"));
        });
        assertRejected(attack, "工程 BEGIN 完成时点与原命令意图不一致：t-per");

        Path lead = tamperPending(base, "cmd:per", "t-per", task -> {
            task.put("dueTimeMillis", 3_999L);
        }, event -> event.put("dueTimeMillis", 3_999L));
        assertRejected(lead, "工程 BEGIN 提前量与原命令工时不一致：t-per");
    }

    @Test
    @DisplayName("待办阶段由种类固定：治理表决不能声明 RULES，结构层直接拒绝")
    void pendingPhaseIsFixedByKind() {
        assertThrows(IllegalArgumentException.class, () -> new PendingTask("t", 100L,
                WorldScheduler.Phase.RULES, PendingTask.GOVERNANCE_VOTE_V1, "{}"),
                "治理表决必须落在 COMPLETION");
        assertThrows(IllegalArgumentException.class, () -> new PendingTask("t", 100L,
                WorldScheduler.Phase.COMPLETION, PendingTask.ROLE_APPOINT_V1, "{}"),
                "任职必须落在 RULES");
    }

    @Test
    @DisplayName("外部命令异参数冲突：同一 commandId 换正文必须拒绝，不能静默返回旧回报")
    void sameCommandDifferentBodyConflicts() throws Exception {
        Path journal = tempDir.resolve("conflict.journal");
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(journal)) {
            world.scheduleBoardMeeting("m1", "t-m1", 50L, "s1", "meeting:1", 100L, 150L, 200L,
                    List.of("s1", "s2", "s3", "s4", "s5"), List.of());
            world.scheduleBoardVote("v1", "t-v1", 100L, "s2", "meeting:1", BoardModel.Vote.YES);
        }
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(journal)) {
            // 同参数重发：返回原回报
            assertEquals("SCHEDULED:t-v1", world.scheduleBoardVote("v1", "t-v1", 100L, "s2",
                    "meeting:1", BoardModel.Vote.YES));
            // 同 commandId 换正文：拒绝
            IllegalArgumentException body = assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleBoardVote("v1", "t-v1", 100L, "s2", "meeting:1",
                            BoardModel.Vote.NO));
            assertTrue(body.getMessage().contains("同一命令标识不能对应不同内容"), body.getMessage());
            // 同 commandId 换到期：身份冲突
            IllegalArgumentException due = assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleBoardVote("v1", "t-v1", 101L, "s2", "meeting:1",
                            BoardModel.Vote.YES));
            assertTrue(due.getMessage().contains("同一命令标识不能对应不同的任务、到期、阶段、种类或参数"),
                    due.getMessage());
        }
    }

    @Test
    @DisplayName("工程排程重试：重开后同参数返回原回报，换到期/工时按异参数冲突拒绝")
    void engineeringRetryAndConflict() throws Exception {
        Path journal = tempDir.resolve("eng-retry.journal");
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(journal)) {
            assertEquals("SCHEDULED:t-conv", world.scheduleEngineeringConvene("conv", "t-conv",
                    100L, WorldWebScenario.ENGINE_A, "alice", "meeting-eng", 150L, 200L, 300L));
        }
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(journal)) {
            assertEquals("SCHEDULED:t-conv", world.scheduleEngineeringConvene("conv", "t-conv",
                    100L, WorldWebScenario.ENGINE_A, "alice", "meeting-eng", 150L, 200L, 300L),
                    "同参数重发必须返回原回报");
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringConvene("conv", "t-conv", 101L,
                            WorldWebScenario.ENGINE_A, "alice", "meeting-eng", 150L, 200L, 300L),
                    "同一命令换到期必须拒绝");
            assertThrows(IllegalArgumentException.class,
                    () -> world.scheduleEngineeringVote("conv", "t-conv", 100L,
                            WorldWebScenario.ENGINE_A, "alice", "meeting-eng", BoardModel.Vote.YES),
                    "同一命令换动作必须拒绝");
        }
    }

    // ------------------------------------------------------------------
    // 同刻顺序与重演异常后继续
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一时点多个外部排程：重开后待办顺序与执行结果保持一致")
    void sameInstantSchedulesKeepOrderAcrossReopen() throws Exception {
        Path journal = tempDir.resolve("same-instant.journal");
        List<PendingTask> before;
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(journal)) {
            world.scheduleBoardMaterial("mat1", "t-mat1", 100L, "s2", "meeting:1", "doc:m",
                    "src:1", 1L);
            world.scheduleBoardVote("v1", "t-v1", 100L, "s3", "meeting:1", BoardModel.Vote.YES);
            world.scheduleBudgetProposal("bud1", "t-bud1", 100L, "s4", "meeting:1", List.of());
            before = world.snapshot().pending();
            assertEquals(7, before.size(), "开局自带系统日程，外部三条排程各自追加一个待办");
        }
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(journal)) {
            assertEquals(before, world.snapshot().pending(), "重开后待办（含顺序）必须一致");
            world.advanceTo(100L);
            List<String> tasks = world.snapshot().governance().orElseThrow().reports().values()
                    .stream().map(BoardRun.Report::taskId).sorted().toList();
            assertEquals(List.of("t-bud1", "t-mat1", "t-v1"), tasks,
                    "同刻三条都必须执行一次");
        }
    }

    @Test
    @DisplayName("受控重演异常后：状态/暂停位/捕获槽复位，世界可继续推进")
    void captureFailureRestoresAndWorldContinues() throws Exception {
        Path journal = tempDir.resolve("capture.journal");
        WorldRuntime world = WorldWebScenario.commandIntentRuntime(journal);
        try {
            world.scheduleBoardVote("v1", "t-v1", 100L, "s2", "meeting:1", BoardModel.Vote.YES);
            long beforePosition = world.snapshot().position();
            long beforeTime = world.snapshot().timeMillis();
            java.lang.reflect.Field stateField = WorldRuntime.class.getDeclaredField("state");
            stateField.setAccessible(true);
            Object previous = stateField.get(world);
            java.lang.reflect.Method require = null;
            for (java.lang.reflect.Method method : WorldRuntime.class.getDeclaredMethods()) {
                if ("requireReplayedSchedule".equals(method.getName())) {
                    require = method;
                }
            }
            assertNotNull(require, "必须仍以整帧重演作为派生证明");
            require.setAccessible(true);
            java.lang.reflect.Field capturingField = WorldRuntime.class
                    .getDeclaredField("capturing");
            capturingField.setAccessible(true);
            PendingTask task = world.snapshot().pending().stream()
                    .filter(p -> PendingTask.GOVERNANCE_VOTE_V1.equals(p.kind()))
                    .findFirst().orElseThrow();
            WorldCommit bogus = new WorldCommit(1, world.snapshot().position() + 1, "web-groups",
                    "cmd:v1", "{\"intent\":1}", "SCHEDULED:t-v1", List.of());
            Throwable failure = null;
            try {
                require.invoke(world, bogus, previous, previous, task);
            } catch (java.lang.reflect.InvocationTargetException wrapped) {
                failure = wrapped.getCause();
            }
            assertNotNull(failure, "重演必须抛出而不是静默通过");
            assertTrue(failure instanceof IOException, failure.toString());
            assertTrue(stateField.get(world) == previous, "异常后状态必须复位");
            assertTrue(!(boolean) capturingField.get(world), "异常后捕获槽必须关闭");
            assertEquals(beforePosition, world.snapshot().position());
            assertEquals(beforeTime, world.snapshot().timeMillis());
            world.advanceTo(100L);
            assertTrue(world.snapshot().position() > beforePosition, "世界必须能继续推进");
            assertTrue(world.snapshot().governance().orElseThrow().reports()
                    .containsKey("t-v1"), "原排程仍按真实入口执行");
        } finally {
            world.close();
        }
    }

    // ------------------------------------------------------------------
    // 跨域可执行链：制造 → 工程会议 → 群帖子 → 阅读
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新规则跨制造/工程会议/群帖子/阅读：真实入口排程后整链可执行")
    void crossDomainChainExecutes() throws Exception {
        Path journal = tempDir.resolve("chain.journal");
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(journal)) {
            // 制造：真实采购（旧完整命令，不写信封）
            assertEquals("SCHEDULED:t-po", world.schedulePurchaseOrder("po", "t-po", 100L,
                    "company", "purchase-1", 10L));
            // 工程会议材料：先开发布（信封），再让五席董事真实阅读（信封）
            world.schedulePublishDocument("pub-conf", "t-pub-conf", 10L, "conf-material",
                    WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                    "会议材料", "实验预算材料", "实验材料", 10L, List.of());
            String[] directors = {"alice", "bob", "carol", "dave", "erin"};
            for (int i = 0; i < directors.length; i++) {
                world.scheduleStartReading("read-" + i, "t-read-" + i, 20L + 2L * i,
                        directors[i], "conf-material", 1L);
            }
            // 工程会议：召集/到会/材料/预算/表决/实施（全部信封）
            world.scheduleEngineeringConvene("conv", "t-conv", 100L, WorldWebScenario.ENGINE_A,
                    "alice", "meeting-eng", 150L, 200L, 300L);
            for (int i = 0; i < directors.length; i++) {
                world.scheduleEngineeringAttend("att-" + i, "t-att-" + i, 110L + i,
                        WorldWebScenario.ENGINE_A, directors[i], "meeting-eng", false);
            }
            world.scheduleEngineeringMaterial("mat", "t-mat", 130L, WorldWebScenario.ENGINE_A,
                    "alice", "meeting-eng", "m1", "conf-material", 1L);
            world.scheduleEngineeringBudgetProposal("bud", "t-bud", 140L,
                    WorldWebScenario.ENGINE_A, "alice", "meeting-eng", List.of(
                            new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                    new java.math.BigDecimal("10000.00")),
                            new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                    new java.math.BigDecimal("10000.00"))));
            for (int i = 0; i < 4; i++) {
                world.scheduleEngineeringVote("vote-" + i, "t-vote-" + i, 220L + i,
                        WorldWebScenario.ENGINE_A, directors[i], "meeting-eng",
                        BoardModel.Vote.YES);
            }
            world.scheduleEngineeringImplement("impl", "t-impl", 320L,
                    WorldWebScenario.ENGINE_A, "alice", "meeting-eng");
            world.advanceTo(400L);

            // 制造采购到期形成回报
            assertTrue(world.snapshot().manufacturing().orElseThrow().reports()
                    .containsKey("t-po"), "制造采购必须真实执行");
            // 工程会议整链：预算决议必须真实生效（预算上限来自真实表决与实施）
            BoardRun board = world.boardOfCompany(WorldWebScenario.ENGINE_A).orElseThrow();
            BoardModel.Budget labor = board.board().budget(0L,
                    BoardModel.BudgetCategory.PRODUCTION_LABOR);
            assertNotNull(labor, "预算实施必须形成真实预算上限，回报=" + board.reports().keySet());
            assertEquals(0, labor.cap().compareTo(new java.math.BigDecimal("10000.00")),
                    "预算上限必须等于提案原文");

            // 群帖子：bob 在公开群发帖（角色命令自身完整，不写信封）
            long now = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:post-t1", world.openRoleSession("bob").postToGroup("post-1",
                    "post-t1", now + 20L,
                    new WorldRoleSession.GroupPostDraft(WorldWebScenario.GROUPS_OPEN, "帖子标题",
                            "帖子正文", List.of())));
            // 阅读：alice 真实读完发布文档（信封）
            world.scheduleStartReading("read-doc", "t-read-doc", now + 100L, "alice",
                    "conf-material", 1L);
            world.advanceTo(now + 200L);

            assertTrue(world.groupsRun().state().posts().stream()
                    .anyMatch(post -> post.groupId().equals(WorldWebScenario.GROUPS_OPEN)
                            && post.author().equals("bob")),
                    "群帖子必须真实发布，实际=" + world.groupsRun().state().posts());
            assertTrue(world.snapshot().information().orElseThrow().reports()
                    .containsKey("t-read-doc"), "阅读必须真实执行并留下回报");
            assertTrue(world.snapshot().information().orElseThrow().reports()
                    .get("t-read-doc").accepted(),
                    "阅读必须成功：" + world.snapshot().information().orElseThrow()
                            .reports().get("t-read-doc").reason());
        }
        // 全链重开：所有信封都按原命令重演通过
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(journal)) {
            assertTrue(world.snapshot().position() > 0L);
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 治理表决基础日志：命令正文为信封。 */
    private Path governanceBase(String name) throws Exception {
        Path base = tempDir.resolve(name + "-base.journal");
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(base)) {
            world.scheduleBoardMeeting("m1", "t-m1", 50L, "s1", "meeting:1", 100L, 150L, 200L,
                    List.of("s1", "s2", "s3", "s4", "s5"), List.of());
            world.scheduleBoardVote("v1", "t-v1", 100L, "s2", "meeting:1", BoardModel.Vote.YES);
        }
        return base;
    }

    /** 工程召集基础日志：正工时 1ms，屏障到期 = 100 - 1。 */
    private Path engineeringBase(String name) throws Exception {
        Path base = tempDir.resolve(name + "-base.journal");
        try (WorldRuntime world = WorldWebScenario.commandIntentRuntime(base)) {
            world.scheduleEngineeringConvene("conv", "t-conv", 100L, WorldWebScenario.ENGINE_A,
                    "alice", "meeting-eng", 150L, 200L, 300L);
        }
        return base;
    }

    /**
     * 复制日志并同步改「待办 + 排程事件」，命令正文（含信封）保持原样。
     *
     * <p>快照只做定点替换（只重写目标任务对象），不整份重排：整份重写会改变未涉及字段的数值
     * 表示，反而掩盖真正要验证的信封绑定。</p>
     */
    private Path tamperPending(Path base, String commandId, String taskId,
                               Consumer<ObjectNode> taskEdit, Consumer<ObjectNode> eventEdit)
            throws Exception {
        Path attack = tempDir.resolve("attack-" + System.nanoTime() + ".journal");
        Files.copy(base, attack, StandardCopyOption.REPLACE_EXISTING);
        int index = frameWithCommand(attack, commandId);
        JournalTamper.rewrite(attack, index, json -> {
            ObjectNode commit = (ObjectNode) read(json);
            boolean hitPending = false;
            boolean hitEvent = false;
            for (JsonNode node : commit.get("events")) {
                String type = node.get("type").asText();
                if ("STATE_SNAPSHOT_V1".equals(type)) {
                    String snapshot = node.get("payload").asText();
                    ObjectNode task = pendingObject(snapshot, taskId);
                    String before = task.path("taskId").asText();
                    taskEdit.accept(task);
                    String after = task.path("taskId").asText();
                    String updated = splicePending(snapshot, taskId, task);
                    if (!before.equals(after)) {
                        updated = spliceUsed(updated, before, after);
                    }
                    ((ObjectNode) node).put("payload", updated);
                    hitPending = true;
                } else if ("TASK_SCHEDULED_V1".equals(type)
                        && node.get("payload").asText().contains("\"taskId\":\"" + taskId + "\"")) {
                    ObjectNode scheduled = (ObjectNode) read(node.get("payload").asText());
                    eventEdit.accept(scheduled);
                    ((ObjectNode) node).put("payload", write(scheduled));
                    hitEvent = true;
                }
            }
            if (!hitPending || !hitEvent) {
                throw new IllegalStateException("篡改未命中：pending=" + hitPending
                        + " event=" + hitEvent);
            }
            return write(commit);
        });
        return attack;
    }

    /** 在快照里取出目标任务对象（只解析这一小段，不动其余字段）。 */
    private static ObjectNode pendingObject(String snapshot, String taskId) {
        String object = pendingJson(snapshot, taskId);
        return (ObjectNode) read(object);
    }

    /** 找到 pending 数组里 taskId 对应的任务对象原文。 */
    private static String pendingJson(String snapshot, String taskId) {
        int at = snapshot.indexOf("\"pending\":[");
        if (at < 0) {
            throw new IllegalStateException("待办集合未命中");
        }
        int start = snapshot.indexOf('{', at);
        while (start >= 0) {
            int end = matchingBrace(snapshot, start);
            String object = snapshot.substring(start, end + 1);
            if (object.contains("\"taskId\":\"" + taskId + "\"")) {
                return object;
            }
            start = snapshot.indexOf('{', end + 1);
        }
        throw new IllegalStateException("待办对象未命中：" + taskId);
    }

    /** 用改写后的任务对象替换快照里的原对象。 */
    private static String splicePending(String snapshot, String taskId, ObjectNode task) {
        String object = pendingJson(snapshot, taskId);
        int at = snapshot.indexOf("\"pending\":[");
        int start = snapshot.indexOf(object, at);
        if (start < 0) {
            throw new IllegalStateException("待办对象位置未命中：" + taskId);
        }
        return snapshot.substring(0, start) + write(task) + snapshot.substring(start + object.length());
    }

    /** 已用任务集合里的标识改名（保持其余顺序）。 */
    private static String spliceUsed(String snapshot, String from, String to) {
        int at = snapshot.indexOf("\"usedTaskIds\":[");
        if (at < 0) {
            throw new IllegalStateException("已用任务集合未命中");
        }
        int end = snapshot.indexOf(']', at);
        String array = snapshot.substring(at, end + 1);
        String updated = array.replace("\"" + from + "\"", "\"" + to + "\"");
        return snapshot.substring(0, at) + updated + snapshot.substring(end + 1);
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && text.charAt(i - 1) != '\\') {
                quoted = !quoted;
            }
            if (quoted) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("任务对象未闭合");
    }

    /** 打开副本必须构造期拒绝且消息精确命中，副本字节不变。 */
    private void assertRejected(Path attack, String expected) throws Exception {
        Path copy = tempDir.resolve("open-" + System.nanoTime() + ".journal");
        Files.copy(attack, copy, StandardCopyOption.REPLACE_EXISTING);
        byte[] before = Files.readAllBytes(copy);
        IOException failure = assertThrows(IOException.class,
                () -> WorldWebScenario.commandIntentRuntime(copy).close(),
                "篡改后的排程帧必须在恢复校验里拒绝：" + expected);
        assertTrue(chain(failure).contains(expected),
                "拒绝原因必须精确命中 " + expected + "，实际：" + chain(failure));
        assertArrayEquals(before, Files.readAllBytes(copy), "拒绝不得修写日志");
    }

    /** 断言某命令的正文是完整信封且字段符合预期。 */
    private void assertEnvelope(Path journal, String commandId, String taskId, String kind,
                                String phase, long due, long duration) throws Exception {
        JsonNode intent = read(commandContent(journal, commandId));
        assertEquals(1, intent.path("intent").asInt(), "信封版本必须是 1：" + commandId);
        assertEquals(commandId.startsWith("cmd:") ? commandId.substring(4) : commandId,
                intent.path("commandId").asText(), "信封原命令标识");
        assertEquals(taskId, intent.path("taskId").asText(), "信封任务标识");
        assertEquals(kind, intent.path("kind").asText(), "信封种类");
        assertEquals(phase, intent.path("phase").asText(), "信封阶段");
        assertEquals(due, intent.path("dueTimeMillis").asLong(), "信封绝对到期");
        assertEquals(duration, intent.path("durationMillis").asLong(), "信封工时");
        assertTrue(intent.path("body").asText().startsWith("{"), "信封必须携带域正文");
    }

    private static String commandContent(Path file, String commandId) throws Exception {
        List<JournalTamper.Frame> frames = JournalTamper.frames(file);
        for (JournalTamper.Frame frame : frames) {
            JsonNode commit = read(new String(frame.payload(), StandardCharsets.UTF_8));
            if (commandId.equals(commit.path("commandId").asText())) {
                return commit.path("commandContent").asText();
            }
        }
        throw new IllegalStateException("没有命令帧：" + commandId);
    }

    private static int frameWithCommand(Path file, String commandId) throws Exception {
        List<JournalTamper.Frame> frames = JournalTamper.frames(file);
        for (int i = frames.size() - 1; i >= 0; i--) {
            JsonNode commit = read(new String(frames.get(i).payload(), StandardCharsets.UTF_8));
            if (commandId.equals(commit.path("commandId").asText())) {
                return i;
            }
        }
        throw new IllegalStateException("没有命令帧：" + commandId);
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    /** 帧内改写的序列化：受检异常统一转成测试失败，不在 lambda 里丢栈。 */
    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String chain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable step = failure;
        int guard = 0;
        while (step != null && guard++ < 10) {
            text.append(step.getClass().getSimpleName()).append(": ")
                    .append(step.getMessage()).append('\n');
            step = step.getCause();
        }
        return text.toString();
    }
}
