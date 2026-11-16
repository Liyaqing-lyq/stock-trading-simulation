package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 真实决定收件箱与调度器的群组完成凭据回归；确定性回执不发起 provider 请求。 */
class GroupsDecisionCompletionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POST = "{\"tool\":\"platform.post\",\"args\":{"
            + "\"title\":\"决定平台帖\",\"body\":\"冻结的公开正文\",\"cites\":[]}}";

    private static Path journal(String name) throws Exception {
        Path dir = Path.of("target", "groups-decision-completion-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static DecisionRun.ActionRef decide(WorldRuntime world, String person,
                                                String action, String body) throws Exception {
        world.beginDecisionSession("session-" + action);
        world.openDecisions("open-" + action, List.of(new DecisionRun.Request(person, action,
                "wake", new DecisionModel.Route("governance", "key-1", "deepseek-flash",
                        1L, 20, 1_000_000, 1_024, 1))));
        DecisionRun.Call call = world.dispatchLiveDecision("dispatch-" + action, action).call();
        assertNotNull(call);
        DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body,
                DecisionModel.Usage.unknownCost(1, 100, 30), "test:scripted");
        assertEquals(WorldRuntime.CompletionOffer.QUEUED, world.offerLiveDecisionCompletion(
                LiveCompletionInbox.Completion.business(reply)));
        assertTrue(world.drainDecisionCallbacks() >= 1);
        DecisionRun.ActionRef ref = world.snapshot().decisions().orElseThrow().actionRef(action);
        assertNotNull(ref, "回执必须排出真实动作");
        PendingTask pending = world.snapshot().pending().stream()
                .filter(task -> task.taskId().equals(ref.taskId())).findFirst().orElseThrow();
        assertEquals(GroupsRun.KIND_BEGIN, pending.kind());
        assertEquals(ref.dueMillis(), pending.dueTimeMillis());
        return ref;
    }

    private static GroupsRun.GroupReport report(WorldRuntime world, String taskId) throws Exception {
        return world.groupsRun().reports().stream().filter(r -> r.taskId().equals(taskId))
                .findFirst().orElseThrow();
    }

    private static PendingTask nextTask(WorldRuntime world, String kind) {
        return world.snapshot().pending().stream().filter(task -> task.kind().equals(kind))
                .findFirst().orElseThrow();
    }

    @Test
    void decisionPlatformPostRequiresBeginFinishAndPublishAndStrictReopen() throws Exception {
        Path file = journal("platform");
        DecisionRun.ActionRef ref;
        GroupsRun finalGroups;
        InformationRun finalInformation;
        long position;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            ref = decide(world, "alice", "a-platform", POST);
            assertTrue(world.groupsRun().publication().platform().posts().isEmpty());
            world.advanceTo(ref.dueMillis());
            GroupsRun.GroupReport begin = report(world, ref.taskId());
            assertTrue(begin.accepted());
            assertEquals("alice", begin.person());
            assertEquals(GroupsRun.KIND_BEGIN, begin.kind());
            assertEquals("PLATFORM_POST", begin.action());
            assertEquals(ref.dueMillis(), begin.atMillis());
            assertTrue(world.groupsRun().publication().platform().posts().isEmpty(),
                    "BEGIN 只建立草稿，不得冒充发布");
            assertTrue(world.snapshot().information().orElseThrow().information().documents()
                    .stream().noneMatch(doc -> doc.publisherPartyId().equals("alice")));
            PendingTask finish = nextTask(world, GroupsRun.KIND_FINISH);
            assertEquals(ref.taskId() + ":finish", finish.taskId());
            assertEquals(ref.dueMillis() + WorldWebScenario.GROUPS_PLATFORM_POST_MILLIS,
                    finish.dueTimeMillis());
            world.advanceTo(finish.dueTimeMillis());
            assertTrue(report(world, finish.taskId()).accepted());
            PlatformPostModel.Post queued = world.groupsRun().publication().platform().posts().get(0);
            assertEquals(PlatformPostModel.PostStatus.QUEUED, queued.status());
            PendingTask publish = nextTask(world, GroupsRun.KIND_PUBLISH);
            assertEquals(queued.publishAt(), publish.dueTimeMillis());
            world.advanceTo(publish.dueTimeMillis());
            assertTrue(report(world, publish.taskId()).accepted());
            PlatformPostModel.Post published = world.groupsRun().publication().platform().posts().get(0);
            assertEquals(PlatformPostModel.PostStatus.PUBLISHED, published.status());
            InformationModel.Document doc = world.snapshot().information().orElseThrow()
                    .information().documents().stream().filter(d -> d.docId().equals(published.docId()))
                    .findFirst().orElseThrow();
            assertEquals(InformationModel.AudienceKind.PUBLIC, doc.audience().kind());
            assertEquals("alice", doc.publisherPartyId());
            assertEquals("决定平台帖", doc.versions().get(0).title());
            assertEquals("冻结的公开正文", doc.versions().get(0).body());
            assertTrue(world.openRoleSession("bob").view().profile().catalog().stream()
                    .anyMatch(entry -> entry.docId().equals(doc.docId())));
            assertEquals(ref, world.snapshot().decisions().orElseThrow().actionRef(ref.actionId()),
                    "原引用始终指向 BEGIN，不得替换为派生任务");
            assertNull(world.snapshot().trading().orElseThrow().report(ref.taskId()), "不得伪造交易回报");
            finalGroups = world.groupsRun();
            finalInformation = world.snapshot().information().orElseThrow();
            position = world.snapshot().position();
        }
        try (WorldRuntime reopened = WorldWebScenario.groupsRuntime(file)) {
            assertEquals(position, reopened.snapshot().position());
            assertEquals(finalGroups, reopened.groupsRun());
            assertEquals(finalInformation, reopened.snapshot().information().orElseThrow());
            assertEquals(ref, reopened.snapshot().decisions().orElseThrow().actionRef(ref.actionId()));
        }
    }

    @Test
    void decisionGroupJoinUsesSameCompletionBridge() throws Exception {
        Path file = journal("join");
        DecisionRun.ActionRef ref;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            ref = decide(world, "dave", "a-join", "{\"tool\":\"group.join\",\"args\":{"
                    + "\"groupId\":\"" + WorldWebScenario.GROUPS_OPEN + "\"}}");
            world.advanceTo(ref.dueMillis());
            assertTrue(report(world, ref.taskId()).accepted());
            assertTrue(world.groupsRun().membershipAt(WorldWebScenario.GROUPS_OPEN, "dave",
                    ref.dueMillis()).isEmpty(), "BEGIN 尚未完成加入");
            PendingTask finish = nextTask(world, GroupsRun.KIND_FINISH);
            world.advanceTo(finish.dueTimeMillis());
            assertTrue(report(world, finish.taskId()).accepted());
            assertTrue(world.groupsRun().membershipAt(WorldWebScenario.GROUPS_OPEN, "dave",
                    finish.dueTimeMillis()).isPresent());
            assertEquals(ref, world.snapshot().decisions().orElseThrow().actionRef(ref.actionId()));
        }
        try (WorldRuntime reopened = WorldWebScenario.groupsRuntime(file)) {
            assertTrue(report(reopened, ref.taskId()).accepted());
        }
    }

    @Test
    void rejectedDecisionBeginIsTruthfulCompletionWithoutPostOrWorldFault() throws Exception {
        Path file = journal("rejected");
        DecisionRun.ActionRef ref;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            ref = decide(world, "dave", "a-rejected", "{\"tool\":\"group.post\",\"args\":{"
                    + "\"groupId\":\"" + WorldWebScenario.GROUPS_OPEN + "\","
                    + "\"title\":\"无权发帖\",\"body\":\"不得发布\",\"cites\":[]}}");
            world.advanceTo(ref.dueMillis());
            GroupsRun.GroupReport rejected = report(world, ref.taskId());
            assertFalse(rejected.accepted());
            assertNotEquals(GroupsRun.REASON_OK, rejected.reason());
            assertEquals(GroupsRun.KIND_BEGIN, rejected.kind());
            assertEquals(ref.dueMillis(), rejected.atMillis());
            assertTrue(world.groupsRun().state().posts().isEmpty());
            assertTrue(world.groupsRun().publication().platform().posts().isEmpty());
            assertTrue(world.snapshot().pending().stream().noneMatch(t -> GroupsRun.KINDS.contains(t.kind())));
            assertFalse(world.isPaused());
            assertEquals(ref, world.snapshot().decisions().orElseThrow().actionRef(ref.actionId()));
        }
        try (WorldRuntime reopened = WorldWebScenario.groupsRuntime(file)) {
            assertFalse(report(reopened, ref.taskId()).accepted());
            assertTrue(reopened.groupsRun().state().posts().isEmpty());
        }
    }

    @Test
    void activeReadingRejectsDecisionBeginLiveAndDuringStrictReplay() throws Exception {
        Path file = journal("active-reading");
        DecisionRun.ActionRef ref;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            long now = world.snapshot().timeMillis();
            world.schedulePublishDocument("publish-long", "publish-long-t", now + 1,
                    "long-read", "alice", InformationModel.Audience.publicAudience(),
                    "长阅读", "占用真实阅读焦点", "机制夹具", 1_000_000L, List.of());
            world.advanceTo(now + 1);
            world.scheduleStartReading("read-long", "read-long-t", now + 2,
                    "dave", "long-read", 1L);
            world.advanceTo(now + 2);
            assertTrue(world.snapshot().information().orElseThrow().information().active().stream()
                    .anyMatch(reading -> reading.personPartyId().equals("dave")));
            ref = decide(world, "dave", "a-busy", POST);
            world.advanceTo(ref.dueMillis());
            GroupsRun.GroupReport rejected = report(world, ref.taskId());
            assertFalse(rejected.accepted());
            assertEquals("BUSY", rejected.reason());
            assertTrue(world.groupsRun().publication().platform().posts().isEmpty());
            assertFalse(world.isPaused());
        }
        try (WorldRuntime reopened = WorldWebScenario.groupsRuntime(file)) {
            assertFalse(report(reopened, ref.taskId()).accepted());
            assertEquals("BUSY", report(reopened, ref.taskId()).reason());
            assertTrue(reopened.snapshot().information().orElseThrow().information().active().stream()
                    .anyMatch(reading -> reading.personPartyId().equals("dave")));
        }
    }

    @ParameterizedTest(name = "coherent report tamper: {0}")
    @ValueSource(strings = {"taskId", "person", "kind", "atMillis", "missing", "accepted", "reason", "action"})
    void coherentDecisionBeginReportTamperIsRejected(String field) throws Exception {
        Path file = journal("tamper-" + field);
        DecisionRun.ActionRef ref;
        try (WorldRuntime world = WorldWebScenario.groupsRuntime(file)) {
            ref = decide(world, "alice", "a-tamper", POST);
            world.advanceTo(ref.dueMillis());
            assertTrue(report(world, ref.taskId()).accepted());
        }
        int index = JournalTamper.lastIndexContaining(file, "STATE_SNAPSHOT_V1");
        JournalTamper.rewriteSnapshot(file, index, json -> {
            try {
                ObjectNode state = (ObjectNode) MAPPER.readTree(json);
                String originalGroups = state.get("groups").toString();
                assertTrue(json.contains(originalGroups));
                ArrayNode reports = (ArrayNode) state.get("groups").get("reports");
                assertEquals(1, reports.size());
                ObjectNode report = (ObjectNode) reports.get(0);
                assertEquals(ref.taskId(), report.get("taskId").asText());
                switch (field) {
                    case "taskId" -> report.put(field, ref.taskId() + ":unrelated");
                    case "person" -> report.put(field, "bob");
                    case "kind" -> report.put(field, GroupsRun.KIND_FINISH);
                    case "atMillis" -> report.put(field, ref.dueMillis() + 1);
                    case "missing" -> reports.removeAll();
                    case "accepted" -> report.put(field, false);
                    case "reason" -> report.put(field, "FORGED");
                    case "action" -> report.put(field, "JOIN");
                    default -> throw new IllegalArgumentException(field);
                }
                // 内部状态仍可按正式格式解析；外部帧长与 SHA 由 JournalTamper 重算。
                MAPPER.treeToValue(state.get("groups"), GroupsRun.class);
                // 仅替换群组片段，保持其它模块 BigDecimal 字面量与 scale 原样。
                return json.replace(originalGroups, state.get("groups").toString());
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        Exception failure = assertThrows(Exception.class,
                () -> WorldWebScenario.groupsRuntime(file).close());
        String message = String.valueOf(failure.getMessage());
        System.out.println("GROUP-DECISION-TAMPER " + field + ": " + message);
        if (List.of("taskId", "person", "kind", "atMillis", "missing").contains(field)) {
            assertTrue(message.contains("已记录意图缺少待办或准确完成回报"), message);
        } else {
            assertTrue(message.contains("群组任务帧不得自行改写群组状态")
                    || message.contains("群组子集与逐帧重算不一致"), message);
        }
    }
}
