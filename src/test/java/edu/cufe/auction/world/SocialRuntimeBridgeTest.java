package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4j.2 schema17 一对一消息运行桥：真实读入口→发信→延迟投递→收件人只见信封→实读→回复，
 * 以及业务拒绝保留 INTENT、屏蔽只在到期生效、单焦点双向、恢复与跨进程。
 */
class SocialRuntimeBridgeTest {

    private static final String NINA = "nina";
    private static final String OMAR = "omar";
    private static final String CONTACT = WorldWebScenario.SOCIAL_CONTACT_DOC;
    private static final String CHANNEL = "私信";
    private static final String WAKE = "wake";
    private static final long SEND = WorldWebScenario.SOCIAL_SEND_MILLIS;
    private static final long DELIVERY = WorldWebScenario.SOCIAL_DELIVERY_MILLIS;
    private static final long BLOCK = WorldWebScenario.SOCIAL_BLOCK_MILLIS;
    private static final DecisionModel.Route ROUTE = new DecisionModel.Route(
            "media", "key-1", "deepseek-flash", 1L, 4, 100_000, 2_000, 2);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------------------------------------------------------------- 夹具

    private static WorldRuntime open(Path journal) throws Exception {
        return WorldWebScenario.openSocial(journal).runtime();
    }

    private static void advance(WorldRuntime world, long to) throws Exception {
        if (to > world.snapshot().timeMillis()) {
            world.advanceTo(to);
        }
    }

    /** 真实读完公开联系说明。 */
    private static RoleView.ReadingRef readContact(WorldRuntime world, String person)
            throws Exception {
        // 开局发布任务在 1000ms 到期：先让文档真实存在再读
        advance(world, 1_000L);
        long now = world.snapshot().timeMillis();
        long read = WorldWebScenario.SOCIAL_READ_MILLIS;
        // 命令标识必须逐次唯一：同 command 换 due 属异内容，会被（正确地）拒绝
        String tag = person + "-" + now;
        world.openRoleSession(person).startReading("read-" + tag, "read-task-" + tag,
                now + read, CONTACT, 1L);
        advance(world, now + 2 * read);
        for (RoleView.Experience experience : world.roleView(person).observations()) {
            if (experience.ref().docId().equals(CONTACT)) {
                return experience.ref();
            }
        }
        throw new IllegalStateException("没有读到联系说明：" + person);
    }

    private static String decide(WorldRuntime world, String command, String person,
                                 String actionId, java.util.function.Function<String, String> body)
            throws Exception {
        world.beginDecisionSession("session-" + command);
        world.openDecisions(command, List.of(
                new DecisionRun.Request(person, actionId, WAKE, ROUTE)));
        WorldRuntime.DispatchResult dispatched = world.dispatchLiveDecision(
                "dispatch-" + command, actionId);
        assertNotNull(dispatched.call(), "派发失败：" + dispatched.verdict() + "/"
                + dispatched.reason());
        DecisionRun.Call call = dispatched.call();
        DecisionModel.Reply reply = new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                body.apply(call.inputText()),
                DecisionModel.Usage.unknownCost(1, 900, 200), "test:scripted");
        assertEquals(WorldRuntime.CompletionOffer.QUEUED,
                world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
        assertTrue(world.drainDecisionCallbacks() >= 1);
        return call.inputText();
    }

    /** 从实际输入里取本人可达的第一条联系入口（不猜名字、不硬编码渠道）。 */
    private static JsonNode contactFromInput(String inputText) throws Exception {
        JsonNode contacts = MAPPER.readTree(inputText).path("social").path("contacts");
        if (!contacts.isArray() || contacts.size() == 0) {
            throw new IllegalStateException("输入里没有可达联系入口：" + inputText);
        }
        return contacts.get(0);
    }

    private static String sendBody(JsonNode contact, String title, String text) {
        return "{\"tool\":\"social.send\",\"args\":{"
                + "\"toPartyId\":\"" + contact.path("targetPartyId").asText() + "\","
                + "\"channel\":\"" + contact.path("channel").asText() + "\","
                + "\"title\":\"" + title + "\",\"body\":\"" + text + "\"}}";
    }

    private static long sendDue(WorldRuntime world, String actionId) throws Exception {
        return DecisionModel.actionDueMillis(
                world.snapshot().decisions().orElseThrow().state(), actionId);
    }

    private static SocialRun social(WorldRuntime world) {
        return world.snapshot().social().orElseThrow();
    }

    private static SocialRun.SocialReport reportOfKind(WorldRuntime world, String kind) {
        for (SocialRun.SocialReport report : social(world).reports()) {
            if (report.kind().equals(kind)) {
                return report;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void sendAfterRealReadDeliversEnvelopeOnlyThenReadAndReply(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("social.journal"))) {
            readContact(world, NINA);
            readContact(world, OMAR);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);

            String input = decide(world, "send-1", NINA, "a-send", text -> {
                try {
                    return sendBody(contactFromInput(text), "私人问候", "这是一条未经核实的个人陈述");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            JsonNode contact = contactFromInput(input);
            assertEquals(OMAR, contact.path("targetPartyId").asText(),
                    "收件人来自本人真实读过的联系入口");
            advance(world, sendDue(world, "a-send"));

            SocialRun afterFinish = social(world);
            assertTrue(afterFinish.state().messages().size() == 1, "写完必须产生一条排队消息；报告=" + afterFinish.reports() + " drafts=" + afterFinish.state().drafts() + " pending=" + world.snapshot().pending());
            SocialMessageModel.Message message = afterFinish.state().messages().get(0);
            assertEquals(SocialMessageModel.MessageStatus.QUEUED, message.status());
            // 写完只是排队：收件人此刻没有任何信封
            assertTrue(SocialMessageModel.inbox(afterFinish.state(), OMAR).isEmpty());
            assertTrue(SocialMessageModel.outbox(afterFinish.state(), NINA).stream()
                    .anyMatch(entry -> entry.status() == SocialMessageModel.OutboxStatus.QUEUED));

            // 延迟投递：到点才有 DIRECT 文档
            advance(world, message.dueAt());
            SocialRun delivered = social(world);
            SocialMessageModel.Message settled = delivered.state().messages().get(0);
            assertEquals(SocialMessageModel.MessageStatus.DELIVERED, settled.status());
            List<SocialMessageModel.EnvelopeView> envelope =
                    SocialMessageModel.inbox(delivered.state(), OMAR);
            assertEquals(1, envelope.size());
            InformationModel.State info = world.snapshot().information().orElseThrow()
                    .information();
            assertFalse(InformationModel.canCite(info, OMAR, envelope.get(0).docId(),
                    envelope.get(0).version(), world.snapshot().timeMillis()),
                    "投递不等于已读：正文在真实读完前不可引用");
            assertFalse(InformationModel.canCite(info, NINA, envelope.get(0).docId(),
                    envelope.get(0).version(), world.snapshot().timeMillis()),
                    "他人信封对第三人不可见");

            // 收件人真实读信封后才有正文
            long read = WorldWebScenario.SOCIAL_READ_MILLIS;
            now = world.snapshot().timeMillis();
            world.openRoleSession(OMAR).startReading("read-env", "read-env-task", now + read,
                    envelope.get(0).docId(), envelope.get(0).version());
            advance(world, now + 2 * read);
            RoleView.Experience experience = world.roleView(OMAR).observations().stream()
                    .filter(item -> item.ref().docId().equals(envelope.get(0).docId()))
                    .findFirst().orElseThrow();
            assertTrue(experience.body().contains("未经核实"));
            // 发件人看不到已读回执：outbox 仍是 DELIVERED，且没有 read 标记
            SocialRun.SocialView ninaView = world.socialView(NINA);
            assertEquals(SocialMessageModel.OutboxStatus.DELIVERED,
                    ninaView.outbox().get(0).status());
            assertFalse(ninaView.read(envelope.get(0).messageId()));
            assertTrue(ninaView.inbox().isEmpty());
        }
    }

    @Test
    void businessRejectKeepsModelIntentAndRecordsSeparateRejection(@TempDir Path dir)
            throws Exception {
        try (WorldRuntime world = open(dir.resolve("reject.journal"))) {
            // nina 没读任何联系入口：send 在派发批被真实拒绝
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            decide(world, "send-unread", NINA, "a-reject", text ->
                    "{\"tool\":\"social.send\",\"args\":{\"toPartyId\":\"omar\","
                            + "\"channel\":\"" + CHANNEL + "\",\"title\":\"hi\",\"body\":\"x\"}}");
            DecisionRun run = world.snapshot().decisions().orElseThrow();
            DecisionModel.Decision decision = DecisionModel.decision(run.state(), "a-reject")
                    .orElseThrow();
            assertEquals(DecisionModel.Outcome.INTENT, decision.outcome(),
                    "模型选择必须保持 INTENT，不能改写为 WAIT");
            assertTrue(decision.lastAttempt().replyBody().contains("social.send"),
                    "原回执必须原样保留");
            // 回执早于 barrier：此刻只有 future BEGIN 预约，尚未开始也可能尚未被拒
            long beginAt = DecisionModel.actionDueMillis(run.state(), "a-reject") - SEND;
            advance(world, beginAt);
            assertTrue(run.actionRefs().size() == 1, "意图必须绑定真实的开始任务");
            assertTrue(world.snapshot().pending().stream()
                            .noneMatch(task -> PendingTask.SOCIAL_FINISH_V1.equals(task.kind())),
                    "业务拒绝不得产生假完成帧");
            assertTrue(social(world).reports().stream().anyMatch(report ->
                            report.kind().equals(PendingTask.SOCIAL_BEGIN_V1)
                                    && "UNREACHABLE".equals(report.reason())
                                    && !report.accepted()),
                    "必须有真实的业务拒绝报告");
            // 不得伪装成技术失败
            assertFalse(world.snapshot().decisions().orElseThrow().state().decisions().get(0)
                    .lastAttempt().settledDone()
                    && decision.outcome() == DecisionModel.Outcome.TECHNICAL_FAILED);
        }
    }

    @Test
    void blockTakesEffectOnlyWhenDueAndKeepsDeliveredHistory(@TempDir Path dir)
            throws Exception {
        try (WorldRuntime world = open(dir.resolve("block.journal"))) {
            readContact(world, NINA);
            readContact(world, OMAR);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            decide(world, "send-pre", NINA, "a-pre", text -> {
                try {
                    return sendBody(contactFromInput(text), "第一封", "投递前");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            advance(world, sendDue(world, "a-pre"));
            SocialMessageModel.Message first = social(world).state().messages().get(0);
            advance(world, first.dueAt());
            assertEquals(SocialMessageModel.MessageStatus.DELIVERED,
                    social(world).state().messages().get(0).status());

            // 屏蔽只排正工时任务：到期前偏好不变，仍可再发
            now = world.snapshot().timeMillis();
            world.openRoleSession(NINA).blockPerson("block-1", "block-task-1", now + BLOCK, OMAR);
            assertTrue(social(world).state().blocks().isEmpty(), "排程期不得改偏好");
            advance(world, now + BLOCK);
            assertFalse(social(world).state().blocks().isEmpty(), "到期才生效");
            assertTrue(social(world).reports().stream().anyMatch(report ->
                    report.kind().equals(PendingTask.SOCIAL_BLOCK_V1) && report.accepted()));

            // 已投递文档不因屏蔽撤销：DIRECT 文档仍在信息状态里
            List<SocialMessageModel.EnvelopeView> stillThere =
                    SocialMessageModel.inbox(social(world).state(), OMAR);
            assertEquals(1, stillThere.size());
            boolean documentExists = world.snapshot().information().orElseThrow().information()
                    .documents().stream()
                    .anyMatch(document -> document.docId().equals(stillThere.get(0).docId())
                            && document.at(stillThere.get(0).version()) != null);
            assertTrue(documentExists, "屏蔽不撤回已投递文档");

            // 到期后发送被拒（统一 UNREACHABLE，不暴露被屏蔽）
            long at = world.snapshot().timeMillis();
            advance(world, at + 1L);
            decide(world, "send-blocked", NINA, "a-blocked", text -> {
                try {
                    return sendBody(contactFromInput(text), "第二封", "被屏蔽后");
                } catch (Exception failure) {
                    throw new IllegalStateException(
                            "屏蔽后输入不应还给出可达入口：" + failure.getMessage());
                }
            });
            DecisionRun run = world.snapshot().decisions().orElseThrow();
            DecisionModel.Decision decision = DecisionModel.decision(run.state(), "a-blocked")
                    .orElseThrow();
            long beginBlocked = DecisionModel.actionDueMillis(run.state(), "a-blocked") - SEND;
            advance(world, beginBlocked);
            assertTrue(social(world).reports().stream().anyMatch(report ->
                            report.kind().equals(PendingTask.SOCIAL_BEGIN_V1)
                                    && "UNREACHABLE".equals(report.reason())),
                    "被屏蔽后的意图必须在开始帧按当时状态拒绝");
            assertEquals(1, social(world).state().messages().size(), "被拒消息不得入账");
        }
    }

    @Test
    void singleFocusIsBidirectionalButOwnDispatchIsNotBusy(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("focus.journal"))) {
            readContact(world, NINA);
            readContact(world, OMAR);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            // 自己的派发不受自身预留影响：第一封正常进入 SCHEDULED
            String status = world.openRoleSession(NINA).sendMessage("f-1", "f-task-1",
                    now + SEND, new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "题目",
                            "正文"));
            assertEquals("SCHEDULED:f-task-1", status);
            // 写信中：阅读被拒（反向门）
            long at = world.snapshot().timeMillis();
            assertThrows(IllegalArgumentException.class, () -> world.openRoleSession(NINA)
                    .startReading("r-1", "r-task-1", at + 100L, CONTACT, 1L));
            // 再发第二封被拒（同人单焦点）
            assertThrows(IllegalArgumentException.class, () -> world.openRoleSession(NINA)
                    .sendMessage("f-2", "f-task-2", at + SEND,
                            new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "题目2", "正文2")));
            // 另一个人同刻不受影响
            assertEquals("SCHEDULED:o-task-1", world.openRoleSession(OMAR).sendMessage("o-1",
                    "o-task-1", at + SEND,
                    new WorldRoleSession.SocialSendDraft(NINA, CHANNEL, "回信", "正文3")));
            // 完成写信后阅读放行（会话排程的到期就是 now + sendMillis）
            advance(world, now + SEND);
            long after = world.snapshot().timeMillis();
            world.openRoleSession(NINA).startReading("r-2", "r-task-2", after + 100L, CONTACT, 1L);
        }
    }

    @Test
    void tamperedMessageStatusIsRejectedOnRecovery(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("tamper.journal");
        try (WorldRuntime world = open(journal)) {
            readContact(world, NINA);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            world.openRoleSession(NINA).sendMessage("t-1", "t-task-1", now + SEND,
                    new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "题目", "正文"));
            advance(world, now + SEND);
        }
        Path tampered = dir.resolve("tampered.journal");
        boolean changed = rewrite(journal, tampered, root -> {
            JsonNode reports = root.path("social").path("reports");
            if (!reports.isArray() || reports.size() == 0) {
                return;
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) reports.get(0))
                    .put("accepted", false);
        });
        assertTrue(changed, "夹具必须真的改到社交报告");
        Exception rejected = assertThrows(Exception.class, () -> {
            try (WorldRuntime ignored = open(tampered)) {
                // 打开即应失败
            }
        });
        assertTrue(String.valueOf(rejected.getMessage()).contains("社交"),
                "拒绝必须来自社交逐帧重算，实际：" + rejected.getMessage());
    }

    @Test
    void olderSchemaInputHasNoSocialNode(@TempDir Path dir) throws Exception {
        try (WorldRuntime evidence = WorldWebScenario.openEvidence(dir.resolve("v16.journal"))
                .runtime()) {
            long now = evidence.snapshot().timeMillis();
            evidence.advanceTo(now + 1L);
            evidence.beginDecisionSession("s16");
            evidence.openDecisions("d16", List.of(
                    new DecisionRun.Request("rita", "a16", WAKE, ROUTE)));
            WorldRuntime.DispatchResult dispatched = evidence.dispatchLiveDecision("dd16", "a16");
            assertNotNull(dispatched.call());
            String input = dispatched.call().inputText();
            assertEquals(DecisionRun.TOOL_CONTRACT_VERSION_V4,
                    MAPPER.readTree(input).path("toolContractVersion").asText());
            assertFalse(MAPPER.readTree(input).has("social"),
                    "v1–v4 输入不得出现 social 节点");
        }
    }

    @Test
    void socialSurvivesRealCrossProcessRecovery(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("cross.journal");
        String phase1 = runProcess(journal, "phase1");
        assertTrue(phase1.contains("PHASE1"), phase1);
        assertTrue(phase1.contains("DELIVERED"), phase1);
        String phase2 = runProcess(journal, "phase2");
        assertTrue(phase2.contains("PHASE2"), phase2);
        assertTrue(phase2.contains("messages=2"), phase2);
        try (WorldRuntime world = open(journal)) {
            assertEquals(2, social(world).state().messages().size());
        }
    }

    /** 真跨进程：另起 JVM 跑探针。 */
    private static String runProcess(Path journal, String phase) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8",
                "-Djava.io.tmpdir=out/tmpdir", "-cp", System.getProperty("java.class.path"),
                "edu.cufe.auction.world.SocialCrossProcessProbe", journal.toString(), phase);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "子进程失败：" + output);
        return output;
    }

    @Test
    void publicSocialViewMatchesRoleViewAndForgetsContacts(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("view.journal"))) {
            advance(world, 1_000L);
            // 未读入口：普通人没有联系人、没有消息
            assertTrue(world.socialView(NINA).contacts().isEmpty(), "未读入口不得暴露全局联系人");
            assertTrue(world.socialView(NINA).inbox().isEmpty());
            readContact(world, NINA);
            readContact(world, OMAR);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            assertEquals(1, world.socialView(NINA).contacts().size(), "读后才有可达入口");
            assertEquals("omar", world.socialView(NINA).contacts().get(0).targetPartyId(),
                    "读后可达入口是真实联系对象");
            assertEquals(world.socialView(NINA).contacts(), world.socialView(NINA).contacts(),
                    "公开访问器稳定同源");
            // 发信并投递
            world.openRoleSession(NINA).sendMessage("v-1", "v-task-1", now + SEND,
                    new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "题目", "正文"));
            advance(world, now + SEND);
            SocialMessageModel.Message message = social(world).state().messages().get(0);
            advance(world, message.dueAt());
            assertFalse(world.socialView(OMAR).read(message.messageId()),
                    "投递不等于已读");
            long read = WorldWebScenario.SOCIAL_READ_MILLIS;
            long at = world.snapshot().timeMillis();
            world.openRoleSession(OMAR).startReading("v-read", "v-read-task", at + read,
                    message.docId(), 1L);
            advance(world, at + 2 * read);
            assertTrue(world.socialView(OMAR).read(message.messageId()),
                    "真实读完后来信必须标记已读（公开访问器与角色视图同源）");
            assertFalse(world.socialView(NINA).read(message.messageId()),
                    "发件人不得看到收件人读回执");
            assertTrue(world.socialView(NINA).inbox().isEmpty(), "第三人/发件人无该信");
            // 远超过回忆半衰期后，联系人按真实回忆状态收回
            advance(world, world.snapshot().timeMillis() + 120L * 86_400_000L);
            assertTrue(world.socialView(NINA).contacts().isEmpty(),
                    "忘记后联系人必须收回");
        }
    }

    @Test
    void sameCommandWithOriginalAbsoluteDueReplaysWithoutNewState(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("replay.journal"))) {
            readContact(world, NINA);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            long due = now + SEND;
            String first = world.openRoleSession(NINA).sendMessage("rf-1", "rf-task-1", due,
                    new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "题目", "正文"));
            assertEquals("SCHEDULED:rf-task-1", first);
            advance(world, due);
            // 时间前进后，用同 command/同正文/原绝对 due 重放：返回原结果，无新状态
            advance(world, world.snapshot().timeMillis() + 5_000L);
            int messages = social(world).state().messages().size();
            int pending = world.snapshot().pending().size();
            String replay = world.openRoleSession(NINA).sendMessage("rf-1", "rf-task-1", due,
                    new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "题目", "正文"));
            assertEquals(first, replay, "重放必须返回原回执");
            assertEquals(messages, social(world).state().messages().size());
            assertEquals(pending, world.snapshot().pending().size());
            // 同 command 换 due 属不同内容：明确拒绝
            long other = world.snapshot().timeMillis() + SEND;
            assertThrows(IllegalArgumentException.class, () -> world.openRoleSession(NINA)
                    .sendMessage("rf-1", "rf-task-1", other,
                            new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "题目", "正文")));
        }
    }

    @Test
    void blockThroughRealDecisionPathAtDueBlocksQueuedMessageAndSurvivesReopen(@TempDir Path dir)
            throws Exception {
        Path journal = dir.resolve("dec-block.journal");
        try (WorldRuntime world = open(journal)) {
            readContact(world, NINA);
            readContact(world, OMAR);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            // 真实决定：nina 发信给 omar（先排队，未到投递）
            decide(world, "send", NINA, "a-send", text -> {
                try {
                    return sendBody(contactFromInput(text), "排队信", "正文");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            advance(world, sendDue(world, "a-send"));
            SocialMessageModel.Message queued = social(world).state().messages().get(0);
            assertEquals(SocialMessageModel.MessageStatus.QUEUED, queued.status());

            // 真实决定：nina 屏蔽 omar（正工时，到期才生效）
            long blockAt = world.snapshot().timeMillis();
            decide(world, "block", NINA, "a-block",
                    text -> "{\"tool\":\"social.block\",\"args\":{\"targetPartyId\":\"omar\"}}");
            advance(world, DecisionModel.actionDueMillis(
                    world.snapshot().decisions().orElseThrow().state(), "a-block"));
            assertFalse(social(world).state().blocks().isEmpty(), "决定驱动的屏蔽必须到期生效");
            long blockDue = DecisionModel.actionDueMillis(
                    world.snapshot().decisions().orElseThrow().state(), "a-block");
            advance(world, blockDue);
            // 排队信到期：判 BLOCKED，不生成文档
            advance(world, queued.dueAt());
            SocialMessageModel.Message blocked = social(world).state().messages().get(0);
            assertEquals(SocialMessageModel.MessageStatus.BLOCKED, blocked.status());
            assertTrue(world.snapshot().information().orElseThrow().information().documents()
                    .stream().noneMatch(document -> document.docId().equals(blocked.docId())),
                    "被屏蔽的排队信不得投递成文档");
            assertTrue(world.snapshot().media().isPresent());
        }
        try (WorldRuntime reopened = open(journal)) {
            assertEquals(SocialMessageModel.MessageStatus.BLOCKED,
                    social(reopened).state().messages().get(0).status(),
                    "重开日志仍是同一结果");
            // 解除屏蔽（真实决定），旧信不复活，新信可投递
            readContact(reopened, NINA);
            long unblockAt = reopened.snapshot().timeMillis();
            decide(reopened, "unblock", NINA, "a-unblock",
                    text -> "{\"tool\":\"social.unblock\",\"args\":{\"targetPartyId\":\"omar\"}}");
            advance(reopened, DecisionModel.actionDueMillis(
                    reopened.snapshot().decisions().orElseThrow().state(), "a-unblock"));
            assertTrue(social(reopened).state().blocks().isEmpty(), "解除屏蔽必须到期生效");
            assertEquals(SocialMessageModel.MessageStatus.BLOCKED,
                    social(reopened).state().messages().get(0).status(), "解除不复活旧信");
            long sendAt = reopened.snapshot().timeMillis();
            decide(reopened, "send2", NINA, "a-send2", text -> {
                try {
                    return sendBody(contactFromInput(text), "新信", "正文2");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            advance(reopened, sendDue(reopened, "a-send2"));
            SocialMessageModel.Message second = social(reopened).state().messages().stream()
                    .filter(message -> message.status() != SocialMessageModel.MessageStatus.BLOCKED)
                    .findFirst().orElseThrow();
            advance(reopened, second.dueAt());
            assertEquals(SocialMessageModel.MessageStatus.DELIVERED,
                    social(reopened).state().messages().stream()
                            .filter(message -> message.messageId().equals(second.messageId()))
                            .findFirst().orElseThrow().status());
        }
    }

    @Test
    void earlyReplyWaitsForBarrierWhileOthersStillAct(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("early.journal"))) {
            readContact(world, NINA);
            readContact(world, OMAR);
            long beforeReply = world.snapshot().timeMillis();
            advance(world, beforeReply + 1L);
            // nina 的决定回执早于 barrier：只落意图与 BEGIN 预约，不写社交 clock、不建 Draft
            decide(world, "early-send", NINA, "a-early", text -> {
                try {
                    return sendBody(contactFromInput(text), "早回执", "正文");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            long barrier = DecisionModel.actionDueMillis(
                    world.snapshot().decisions().orElseThrow().state(), "a-early")
                    - SEND;
            assertTrue(barrier > world.snapshot().timeMillis(), "该场景回执确实早于 barrier");
            assertTrue(social(world).state().drafts().isEmpty(), "回执早于 barrier 不得开始写作");
            assertTrue(social(world).state().clock() < barrier, "不得把未来时点写进已发生状态");
            assertTrue(social(world).state().messages().isEmpty());
            // 同刻另一个人的合法动作不受影响
            long now = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:o-early-task", world.openRoleSession(OMAR).sendMessage("o-early",
                    "o-early-task", now + SEND,
                    new WorldRoleSession.SocialSendDraft(NINA, CHANNEL, "同时发", "正文2")),
                    "未来意图不得阻断他人当下合法动作");
            // 到 barrier：真实 begin，随后 finish 与 delivery
            advance(world, barrier);
            assertEquals(1, social(world).state().drafts().size(), "barrier 到达才落 Draft");
            long finishDue = -1L;
            for (PendingTask task : world.snapshot().pending()) {
                if (PendingTask.SOCIAL_FINISH_V1.equals(task.kind())) {
                    finishDue = task.dueTimeMillis();
                }
            }
            assertEquals(barrier + SEND, finishDue, "finish 到期必须固定为 begin+正工时");
            advance(world, finishDue);
            SocialMessageModel.Message message = social(world).state().messages().stream()
                    .filter(item -> item.from().equals(NINA)).findFirst().orElseThrow();
            assertEquals(SocialMessageModel.MessageStatus.QUEUED, message.status());
            assertEquals(finishDue, message.sentAt(), "写完时刻等于真实 finish 帧");
            advance(world, message.dueAt());
            assertEquals(SocialMessageModel.MessageStatus.DELIVERED,
                    social(world).state().messages().stream()
                            .filter(item -> item.messageId().equals(message.messageId()))
                            .findFirst().orElseThrow().status());
        }
    }

    @Test
    void futureBeginRechecksAtBarrierAndStaysConsistentOnReopen(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("recheck.journal");
        long barrier;
        try (WorldRuntime world = open(journal)) {
            readContact(world, NINA);
            readContact(world, OMAR);
            long at = world.snapshot().timeMillis() + 1L;
            advance(world, at);
            decide(world, "recheck-send", NINA, "a-recheck", text -> {
                try {
                    return sendBody(contactFromInput(text), "待核", "正文");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            barrier = DecisionModel.actionDueMillis(
                    world.snapshot().decisions().orElseThrow().state(), "a-recheck") - SEND;
            // barrier 之前 omar 屏蔽 nina：开始帧必须按当时状态重核并拒绝
            long blockAt = world.snapshot().timeMillis();
            world.openRoleSession(OMAR).blockPerson("b-pre", "b-pre-task", blockAt + BLOCK, NINA);
            advance(world, blockAt + BLOCK);
        }
        // 重开：pending BEGIN 唯一，且不会提前开始
        try (WorldRuntime world = open(journal)) {
            long begins = world.snapshot().pending().stream()
                    .filter(task -> PendingTask.SOCIAL_BEGIN_V1.equals(task.kind())).count();
            assertEquals(1, begins, "重开后 future 预约唯一保留在 pending");
            assertTrue(social(world).state().drafts().isEmpty(), "重开不得提前开始");
            advance(world, barrier);
            assertTrue(social(world).state().drafts().isEmpty(), "开始帧按当时屏蔽重核即拒绝");
            assertTrue(social(world).reports().stream().anyMatch(report ->
                            report.kind().equals(PendingTask.SOCIAL_BEGIN_V1)
                                    && "UNREACHABLE".equals(report.reason())),
                    "拒绝必须留真实业务报告");
            assertTrue(world.snapshot().pending().stream()
                            .noneMatch(task -> PendingTask.SOCIAL_FINISH_V1.equals(task.kind())),
                    "拒绝后不得产生假 finish");
            DecisionRun run = world.snapshot().decisions().orElseThrow();
            DecisionModel.Decision decision = DecisionModel.decision(run.state(), "a-recheck")
                    .orElseThrow();
            assertEquals(DecisionModel.Outcome.INTENT, decision.outcome(), "原回执与 INTENT 保留");
        }
    }

    @Test
    void pendingBeginKeepsSamePersonBusyButNotOthers(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("focus.journal"))) {
            readContact(world, NINA);
            readContact(world, OMAR);
            long at = world.snapshot().timeMillis() + 1L;
            advance(world, at);
            decide(world, "focus-send", NINA, "a-focus", text -> {
                try {
                    return sendBody(contactFromInput(text), "预约", "正文");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            long barrier = DecisionModel.actionDueMillis(
                    world.snapshot().decisions().orElseThrow().state(), "a-focus") - SEND;
            assertTrue(barrier > world.snapshot().timeMillis());
            // 同人：session 发送/阅读/屏蔽/新决定都必须被拒
            long now = world.snapshot().timeMillis();
            assertThrows(IllegalArgumentException.class, () -> world.openRoleSession(NINA)
                    .sendMessage("f-2", "f-t2", now + SEND,
                            new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "第二", "x")));
            assertThrows(IllegalArgumentException.class, () -> world.openRoleSession(NINA)
                    .startReading("f-r", "f-rt", now + WorldWebScenario.SOCIAL_READ_MILLIS, WorldWebScenario.SOCIAL_CONTACT_DOC, 1L));
            assertThrows(IllegalArgumentException.class, () -> world.openRoleSession(NINA)
                    .blockPerson("f-b", "f-bt", now + BLOCK, OMAR));
            world.beginDecisionSession("focus-second");
            assertThrows(IllegalArgumentException.class, () -> world.openDecisions("focus-second",
                    List.of(new DecisionRun.Request(NINA, "a-focus-2", "wake", ROUTE))));
            // 他人同刻不受影响
            assertEquals("SCHEDULED:o-focus-task", world.openRoleSession(OMAR).sendMessage("o-focus",
                    "o-focus-task", now + SEND,
                    new WorldRoleSession.SocialSendDraft(NINA, CHANNEL, "同时", "y")));
            // 到 barrier：自身豁免，正常开始
            advance(world, barrier);
            assertTrue(social(world).reports().stream().anyMatch(report ->
                            report.kind().equals(PendingTask.SOCIAL_BEGIN_V1)
                                    && "STARTED".equals(report.reason())),
                    "BEGIN 不得因自身在 pending 而 BUSY");
            // 写完（FINISH 完成、只剩系统投递）后同人恢复可动作
            long finishDue = -1L;
            for (PendingTask task : world.snapshot().pending()) {
                if (PendingTask.SOCIAL_FINISH_V1.equals(task.kind())) {
                    finishDue = task.dueTimeMillis();
                }
            }
            advance(world, finishDue);
            long after = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:n-again-task", world.openRoleSession(NINA).sendMessage(
                    "n-again", "n-again-task", after + SEND,
                    new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "再发", "z")),
                    "写完只剩系统投递时，本人不得被永久判 BUSY");
        }
    }

    @Test
    void beginIsBusyWhenPersonIsReadingAtBarrier(@TempDir Path dir) throws Exception {
        try (WorldRuntime world = open(dir.resolve("focus-read.journal"))) {
            readContact(world, NINA);
            readContact(world, OMAR);
            long at = world.snapshot().timeMillis() + 1L;
            advance(world, at);
            // 先开始一段长阅读（阅读是既有动作，不被社交预约阻止），再让其跨越 barrier
            long span = 8_000L;
            world.openRoleSession(NINA).startReading("span-read", "span-task", at + span,
                    WorldWebScenario.SOCIAL_CONTACT_DOC, 1L);
            decide(world, "read-begin", NINA, "a-rb", text -> {
                try {
                    return sendBody(contactFromInput(text), "预约", "正文");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            long barrier = DecisionModel.actionDueMillis(
                    world.snapshot().decisions().orElseThrow().state(), "a-rb") - SEND;
            assertTrue(barrier < at + span, "阅读必须跨越 barrier");
            advance(world, barrier);
            assertTrue(social(world).reports().stream().anyMatch(report ->
                            report.kind().equals(PendingTask.SOCIAL_BEGIN_V1)
                                    && "BUSY".equals(report.reason())),
                    "开始帧必须按当时阅读占用判 BUSY");
            assertTrue(social(world).state().drafts().isEmpty(), "BUSY 时不得落 Draft");
        }
    }

    @Test
    void finishCommandDoesNotCollideWithUserCommandEitherOrder(@TempDir Path dir) throws Exception {
        // 顺序一：先用 "a" 发信，再用 "a:finish" 发信
        try (WorldRuntime world = open(dir.resolve("order1.journal"))) {
            readContact(world, NINA);
            readContact(world, OMAR);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:t1", world.openRoleSession(NINA).sendMessage("a", "t1",
                    now + SEND, new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "第一", "正文")));
            advance(world, now + SEND);
            long afterFirst = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:t2", world.openRoleSession(NINA).sendMessage("a:finish", "t2",
                    afterFirst + SEND,
                    new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "第二", "正文2")));
            advance(world, afterFirst + SEND);
            assertEquals(2, social(world).state().messages().size());
            long afterSecond = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:t3", world.openRoleSession(NINA).sendMessage("a3", "t3",
                    afterSecond + SEND,
                    new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "第三", "正文3")),
                    "后续合法发送仍能完成，不会永久 BUSY");
        }
        // 顺序二：先用 "b:finish" 发信，再用 "b" 发信
        try (WorldRuntime world = open(dir.resolve("order2.journal"))) {
            readContact(world, NINA);
            readContact(world, OMAR);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:t1", world.openRoleSession(NINA).sendMessage("b:finish", "t1",
                    now + SEND, new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "第一", "正文")));
            advance(world, now + SEND);
            long afterFirst = world.snapshot().timeMillis();
            assertEquals("SCHEDULED:t2", world.openRoleSession(NINA).sendMessage("b", "t2",
                    afterFirst + SEND,
                    new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "第二", "正文2")));
            advance(world, afterFirst + SEND);
            assertEquals(2, social(world).state().messages().size());
        }
    }

    @Test
    void forgedReportUnderDecisionPrefixedCommitIsRejected(@TempDir Path dir) throws Exception {
        Path journal = sentJournal(dir, "dec-forge-base.journal");
        Path forged = dir.resolve("dec-forge.journal");
        boolean changed = rewriteLastCommit(journal, forged, "dec-forged-batch", root -> {
            JsonNode reports = root.path("social").path("reports");
            if (!reports.isArray() || reports.size() == 0) {
                return;
            }
            var report = ((com.fasterxml.jackson.databind.node.ObjectNode) reports.get(0))
                    .deepCopy();
            report.put("taskId", "forged-report");
            report.put("reason", "OK");
            report.put("accepted", true);
            ((com.fasterxml.jackson.databind.node.ArrayNode) reports).add(report);
        });
        assertTrue(changed, "夹具必须真的插入伪造报告");
        Exception rejected = assertThrows(Exception.class, () -> {
            try (WorldRuntime ignored = open(forged)) {
                // 前缀伪装成决定批次也不能免检
            }
        });
        assertTrue(String.valueOf(rejected.getMessage()).contains("社交"),
                "拒绝必须来自社交重算，实际：" + rejected.getMessage());
    }

    @Test
    void forgedReportWithoutDecisionEventIsRejected(@TempDir Path dir) throws Exception {
        Path journal = sentJournal(dir, "plain-forge-base.journal");
        Path forged = dir.resolve("plain-forge.journal");
        boolean changed = rewriteLastCommit(journal, forged, "pub-forged-batch", root -> {
            JsonNode reports = root.path("social").path("reports");
            if (!reports.isArray() || reports.size() == 0) {
                return;
            }
            var report = ((com.fasterxml.jackson.databind.node.ObjectNode) reports.get(0))
                    .deepCopy();
            report.put("taskId", "forged-plain");
            report.put("reason", "OK");
            report.put("accepted", true);
            ((com.fasterxml.jackson.databind.node.ArrayNode) reports).add(report);
        });
        assertTrue(changed, "夹具必须真的插入伪造报告");
        assertThrows(Exception.class, () -> {
            try (WorldRuntime ignored = open(forged)) {
                // 无决定事件的普通批次同样必须被拒
            }
        });
    }

    /** 造一份"已写完一封"的基础日志。 */
    private static Path sentJournal(Path dir, String name) throws Exception {
        Path journal = dir.resolve(name);
        try (WorldRuntime world = open(journal)) {
            readContact(world, NINA);
            long now = world.snapshot().timeMillis();
            advance(world, now + 1L);
            now = world.snapshot().timeMillis();
            world.openRoleSession(NINA).sendMessage("base-1", "base-task-1", now + SEND,
                    new WorldRoleSession.SocialSendDraft(OMAR, CHANNEL, "题目", "正文"));
            advance(world, now + SEND);
        }
        return journal;
    }

    /** 复制日志，改写最后一个提交的命令标识并允许改最后一帧快照。 */
    private static boolean rewriteLastCommit(Path source, Path target, String commandId,
                                             java.util.function.Consumer<
                                                     com.fasterxml.jackson.databind.node.ObjectNode> mutator)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature
                        .USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.setNodeFactory(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
        boolean[] changed = {false};
        try (WorldJournal from = new WorldJournal(source, WorldWebScenario.SOCIAL_RUN_ID);
             WorldJournal to = new WorldJournal(target, WorldWebScenario.SOCIAL_RUN_ID)) {
            List<WorldCommit> commits = new ArrayList<>(from.commits());
            for (int index = 0; index < commits.size(); index++) {
                WorldCommit commit = commits.get(index);
                boolean last = index == commits.size() - 1;
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    if (!"STATE_SNAPSHOT_V1".equals(event.type())) {
                        events.add(event);
                        continue;
                    }
                    com.fasterxml.jackson.databind.node.ObjectNode root =
                            (com.fasterxml.jackson.databind.node.ObjectNode)
                                    mapper.readTree(event.payload());
                    String before = mapper.writeValueAsString(root);
                    if (last) {
                        mutator.accept(root);
                    }
                    String after = mapper.writeValueAsString(root);
                    if (!before.equals(after)) {
                        changed[0] = true;
                    }
                    events.add(new WorldEvent(event.eventId(), event.type(), after));
                }
                String id = last ? commandId : commit.commandId();
                to.commit(id, commit.commandContent(), last ? "FORGED" : commit.result(), events);
            }
        }
        return changed[0];
    }

    /** 逐提交复制日志，只在最后一帧应用改写。 */
    private static boolean rewrite(Path source, Path target,
                                   java.util.function.Consumer<
                                           com.fasterxml.jackson.databind.node.ObjectNode> mutator)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature
                        .USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.setNodeFactory(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
        boolean[] changed = {false};
        try (WorldJournal from = new WorldJournal(source, WorldWebScenario.SOCIAL_RUN_ID);
             WorldJournal to = new WorldJournal(target, WorldWebScenario.SOCIAL_RUN_ID)) {
            List<WorldCommit> commits = new ArrayList<>(from.commits());
            for (int index = 0; index < commits.size(); index++) {
                WorldCommit commit = commits.get(index);
                boolean last = index == commits.size() - 1;
                List<WorldEvent> events = new ArrayList<>();
                for (WorldEvent event : commit.events()) {
                    if (!"STATE_SNAPSHOT_V1".equals(event.type())) {
                        events.add(event);
                        continue;
                    }
                    com.fasterxml.jackson.databind.node.ObjectNode root =
                            (com.fasterxml.jackson.databind.node.ObjectNode)
                                    mapper.readTree(event.payload());
                    String before = mapper.writeValueAsString(root);
                    if (last) {
                        mutator.accept(root);
                    }
                    String after = mapper.writeValueAsString(root);
                    if (!before.equals(after)) {
                        changed[0] = true;
                    }
                    events.add(new WorldEvent(event.eventId(), event.type(), after));
                }
                to.commit(commit.commandId(), commit.commandContent(), commit.result(), events);
            }
        }
        return changed[0];
    }
}
