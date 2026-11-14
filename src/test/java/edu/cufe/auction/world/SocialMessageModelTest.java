package edu.cufe.auction.world;

import edu.cufe.auction.world.InformationModel.Audience;
import edu.cufe.auction.world.InformationModel.Status;
import edu.cufe.auction.world.InformationModel.VersionDraft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4j.1 一对一消息纯核的定向测试：全部从正常纯核入口产生状态，覆盖合同「最低实测」清单
 * 与独立审查 F1/F2/F3 的真实反例。
 *
 * <p>角色：alice 与 bob 是普通人物，carol 是第三人；接触入口由受信 Context 提供，
 * 正文只存在于调用方 payload 与投递后的 Information DIRECT 文档。</p>
 */
class SocialMessageModelTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String CAROL = "carol";
    private static final String ORG = "org-x";
    private static final String CHANNEL = "私信";
    private static final String CHANNEL_ALICE = "工单";
    private static final String CONTACT_BOB_DOC = "CONTACT-BOB";
    private static final String CONTACT_ALICE_DOC = "CONTACT-ALICE";
    private static final long READ = 1_000L;
    private static final long SEND = 3_000L;
    private static final long DELIVERY = 1_000L;
    private static final long MESSAGE_READ = 2_000L;
    private static final long T0 = 2_000L;

    // ---------------------------------------------------------------- 夹具

    private static OpeningRegistry registry() {
        return new OpeningRegistry(List.of(
                new OpeningRegistry.Party(ALICE, OpeningRegistry.PartyKind.PERSON, "Alice"),
                new OpeningRegistry.Party(BOB, OpeningRegistry.PartyKind.PERSON, "Bob"),
                new OpeningRegistry.Party(CAROL, OpeningRegistry.PartyKind.PERSON, "Carol"),
                new OpeningRegistry.Party(ORG, OpeningRegistry.PartyKind.ORGANIZATION, "机构")),
                List.of(), List.of(), List.of());
    }

    private static SocialMessageModel.Config config() {
        return new SocialMessageModel.Config(SEND, DELIVERY, MESSAGE_READ);
    }

    private static CognitionRecall.Policy policy() {
        return new CognitionRecall.Policy(100_000L, 500, 20, 100_000L);
    }

    private static CognitionRecall.Policy forgetful() {
        return new CognitionRecall.Policy(1_000L, 500, 20, 100_000L);
    }

    private static List<InterviewRun.ContactEntry> contacts() {
        return List.of(
                new InterviewRun.ContactEntry(CHANNEL, BOB, "公开私信入口", CONTACT_BOB_DOC, 1L),
                new InterviewRun.ContactEntry(CHANNEL_ALICE, ALICE, "公开私信入口",
                        CONTACT_ALICE_DOC, 1L));
    }

    private static SocialMessageModel.Context context() {
        return new SocialMessageModel.Context(registry(), policy(), config(), contacts());
    }

    private static SocialMessageModel.Context permissiveContext() {
        return new SocialMessageModel.Context(registry(),
                new CognitionRecall.Policy(Long.MAX_VALUE, 1, 100, 1_000_000L), config(),
                contacts());
    }

    private static RoleAccess.State access() {
        return new RoleAccess.State(List.of(), List.of(), List.of(), List.of());
    }

    private static InformationModel.State baseInfo() {
        InformationModel.State info = InformationModel.open(registry(), List.of(ALICE, BOB, CAROL));
        info = InformationModel.publish(info, CONTACT_BOB_DOC, ALICE, Audience.publicAudience(),
                new VersionDraft("联系卡 Bob", "公开说明：可以给 bob 写私信", "公开", 0L, READ,
                        List.of()));
        return InformationModel.publish(info, CONTACT_ALICE_DOC, BOB, Audience.publicAudience(),
                new VersionDraft("联系卡 Alice", "公开说明：可以给 alice 写私信", "公开", 0L, READ,
                        List.of()));
    }

    private static InformationModel.State readDoc(InformationModel.State info, String person,
                                                  String docId, long startedAt) {
        InformationModel.Outcome started = InformationModel.startRead(info, access(), person, docId,
                1L, startedAt);
        assertEquals(Status.STARTED, started.status());
        String readId = null;
        for (InformationModel.Reading reading : started.state().active()) {
            if (reading.personPartyId().equals(person) && reading.docId().equals(docId)) {
                readId = reading.readId();
            }
        }
        InformationModel.Outcome completed = InformationModel.completeRead(started.state(), access(),
                person, readId, startedAt + READ);
        assertEquals(Status.COMPLETED, completed.status());
        return completed.state();
    }

    private static InformationModel.State aliceReady() {
        return readDoc(baseInfo(), ALICE, CONTACT_BOB_DOC, 0L);
    }

    private static SocialMessageModel.BeginOutcome begin(InformationModel.State info,
                                                         String commandId, long now) {
        return SocialMessageModel.beginSend(SocialMessageModel.open(), info, context(), ALICE,
                commandId, BOB, CHANNEL, "标题 " + commandId, "正文 " + commandId, now);
    }

    private static SocialMessageModel.BeginOutcome begin(SocialMessageModel.State social,
                                                         InformationModel.State info,
                                                         String commandId, long now) {
        return SocialMessageModel.beginSend(social, info, context(), ALICE, commandId, BOB, CHANNEL,
                "标题 " + commandId, "正文 " + commandId, now);
    }

    private static SocialMessageModel.FinishOutcome finish(SocialMessageModel.State social,
                                                           InformationModel.State info,
                                                           String commandId, String messageId,
                                                           long now) {
        return SocialMessageModel.finishSend(social, info, context(), ALICE, commandId, messageId,
                now);
    }

    private static SocialMessageModel.DeliverOutcome deliver(SocialMessageModel.State social,
                                                             InformationModel.State info,
                                                             String commandId,
                                                             SocialMessageModel.PendingPayload payload,
                                                             long now) {
        return SocialMessageModel.deliver(social, info, context(), commandId, payload, now);
    }

    private static SocialMessageModel.BlockOutcome block(SocialMessageModel.State social,
                                                         String caller, String commandId,
                                                         String target, long now) {
        return SocialMessageModel.block(social, registry(), caller, commandId, target, now);
    }

    private static SocialMessageModel.UnblockOutcome unblock(SocialMessageModel.State social,
                                                             String caller, String commandId,
                                                             String target, long now) {
        return SocialMessageModel.unblock(social, registry(), caller, commandId, target, now);
    }

    /** 完整写-完-投递链，返回投递结果。 */
    private static SocialMessageModel.DeliverOutcome fullChain(InformationModel.State info,
                                                               long sendAt) {
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", sendAt);
        assertEquals(SocialMessageModel.BeginStatus.STARTED, begun.status());
        long completedAt = sendAt + SEND;
        SocialMessageModel.FinishOutcome finished = finish(begun.state(), info, "F1",
                begun.payload().messageId(), completedAt);
        assertEquals(SocialMessageModel.FinishStatus.QUEUED, finished.status());
        return deliver(finished.state(), info, "D1", begun.payload(), completedAt + DELIVERY);
    }

    // ---------------------------------------------------------------- 可达

    @Test
    void sendRequiresActuallyReadContactDocument() {
        InformationModel.State info = baseInfo();
        assertEquals(SocialMessageModel.BeginStatus.UNREACHABLE,
                begin(info, "A1", T0).status());
        SocialMessageModel.BeginOutcome started = begin(aliceReady(), "A1", T0);
        assertEquals(SocialMessageModel.BeginStatus.STARTED, started.status());
        assertTrue(started.payload() != null);
    }

    @Test
    void forgottenContactReadIsNotReachable() {
        SocialMessageModel.Context forgetfulContext = new SocialMessageModel.Context(registry(),
                forgetful(), config(), contacts());
        assertEquals(SocialMessageModel.BeginStatus.UNREACHABLE,
                SocialMessageModel.beginSend(SocialMessageModel.open(), aliceReady(),
                        forgetfulContext, ALICE, "A1", BOB, CHANNEL, "标题", "正文", 3_000L)
                        .status());
        // 半衰期内仍可发：completedAt=1000，now=2000 恰好一个半衰期，分数 500 达到阈值
        assertEquals(SocialMessageModel.BeginStatus.STARTED,
                SocialMessageModel.beginSend(SocialMessageModel.open(), aliceReady(),
                        forgetfulContext, ALICE, "A2", BOB, CHANNEL, "标题", "正文", 2_000L)
                        .status());
    }

    @Test
    void unknownTargetAndUnreachableAreIndistinguishable() {
        SocialMessageModel.BlockOutcome blocked = block(SocialMessageModel.open(), BOB, "B1", ALICE,
                1_000L);
        assertEquals(SocialMessageModel.BlockStatus.BLOCKED, blocked.status());
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginStatus unknown = SocialMessageModel.beginSend(blocked.state(), info,
                context(), ALICE, "A1", "ghost", CHANNEL, "标题", "正文", T0).status();
        SocialMessageModel.BeginStatus blockedSend = SocialMessageModel.beginSend(blocked.state(),
                info, context(), ALICE, "A2", BOB, CHANNEL, "标题", "正文", T0).status();
        assertEquals(SocialMessageModel.BeginStatus.UNREACHABLE, unknown);
        assertEquals(unknown, blockedSend);
    }

    // ---------------------------------------------------------------- 全链

    @Test
    void fullChainPublishesDirectDocumentAndEnvelope() {
        SocialMessageModel.DeliverOutcome delivered = fullChain(aliceReady(), T0);
        assertEquals(SocialMessageModel.DeliverStatus.DELIVERED, delivered.status());
        assertEquals(SocialMessageModel.MessageStatus.DELIVERED, delivered.message().status());
        long deliveryAt = T0 + SEND + DELIVERY;
        assertEquals(deliveryAt, delivered.message().statusAt());

        String docId = SocialMessageModel.docIdFor(SocialMessageModel.messageId(ALICE, "A1"));
        InformationModel.Document document = null;
        for (InformationModel.Document candidate : delivered.information().documents()) {
            if (candidate.docId().equals(docId)) {
                document = candidate;
            }
        }
        assertTrue(document != null);
        assertEquals(ALICE, document.publisherPartyId());
        assertEquals(InformationModel.AudienceKind.DIRECT, document.audience().kind());
        assertEquals(BOB, document.audience().partyId());
        InformationModel.DocumentVersion version = document.at(1L);
        assertEquals("标题 A1", version.title());
        assertEquals("正文 A1", version.body());
        assertEquals(SocialMessageModel.ORIGINAL_STATEMENT, version.publicSourceDescription());
        assertEquals(MESSAGE_READ, version.readDurationMillis());
        assertEquals(deliveryAt, version.publishedAt());

        List<SocialMessageModel.EnvelopeView> inbox = SocialMessageModel.inbox(delivered.social(),
                BOB);
        assertEquals(1, inbox.size());
        assertEquals(docId, inbox.get(0).docId());
        assertEquals(ALICE, inbox.get(0).from());
        assertEquals(deliveryAt, inbox.get(0).deliveredAt());
        assertTrue(SocialMessageModel.inbox(delivered.social(), CAROL).isEmpty());
    }

    @Test
    void metadataStateDoesNotCarryBody() {
        SocialMessageModel.DeliverOutcome delivered = fullChain(aliceReady(), T0);
        assertFalse(delivered.social().toString().contains("正文 A1"));
        assertEquals(0, delivered.social().drafts().size());
        assertEquals(SocialMessageModel.OutboxStatus.DELIVERED,
                SocialMessageModel.outbox(delivered.social(), ALICE).get(0).status());
    }

    @Test
    void deliveryDoesNotAutoReadAndReadUsesConfigDuration() {
        SocialMessageModel.DeliverOutcome delivered = fullChain(aliceReady(), T0);
        long deliveryAt = T0 + SEND + DELIVERY;
        assertTrue(delivered.information().active().isEmpty());
        assertEquals(0, InformationModel.observations(delivered.information(), BOB).size());

        String docId = delivered.message().docId();
        InformationModel.Outcome started = InformationModel.startRead(delivered.information(),
                access(), BOB, docId, 1L, deliveryAt);
        assertEquals(Status.STARTED, started.status());
        InformationModel.Reading reading = started.state().active().get(0);
        assertEquals(MESSAGE_READ, reading.dueAt() - reading.startedAt());
        InformationModel.Outcome completed = InformationModel.completeRead(started.state(), access(),
                BOB, reading.readId(), reading.dueAt());
        assertEquals(Status.COMPLETED, completed.status());
        assertEquals("正文 A1", completed.observation().body());
        assertTrue(InformationModel.canCite(completed.state(), BOB, docId, 1L, reading.dueAt()));
    }

    @Test
    void recipientCanReplyOnlyAfterActuallyReading() {
        SocialMessageModel.DeliverOutcome delivered = fullChain(aliceReady(), T0);
        long deliveryAt = T0 + SEND + DELIVERY;
        assertEquals(SocialMessageModel.BeginStatus.UNREACHABLE, SocialMessageModel.beginSend(
                delivered.social(), delivered.information(), context(), BOB, "B0", ALICE, CHANNEL,
                "回信", "读前回复", deliveryAt).status());

        // 只开始未读完仍不可回复
        InformationModel.Outcome started = InformationModel.startRead(delivered.information(),
                access(), BOB, delivered.message().docId(), 1L, deliveryAt);
        assertEquals(SocialMessageModel.BeginStatus.UNREACHABLE, SocialMessageModel.beginSend(
                delivered.social(), started.state(), context(), BOB, "B1", ALICE, CHANNEL,
                "回信", "读中回复", deliveryAt + MESSAGE_READ - 1L).status());

        InformationModel.Outcome completed = InformationModel.completeRead(started.state(), access(),
                BOB, started.state().active().get(0).readId(), deliveryAt + MESSAGE_READ);
        assertEquals(SocialMessageModel.BeginStatus.STARTED, SocialMessageModel.beginSend(
                delivered.social(), completed.state(), context(), BOB, "B2", ALICE, CHANNEL,
                "回信", "读后回复", deliveryAt + MESSAGE_READ + 1L).status());
    }

    // ---------------------------------------------------------------- 时间边界

    @Test
    void finishRejectsEarlyAndAcceptsExactBoundary() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        long completedAt = T0 + SEND;
        SocialMessageModel.FinishOutcome early = finish(begun.state(), info, "F1",
                begun.payload().messageId(), completedAt - 1L);
        assertEquals(SocialMessageModel.FinishStatus.NOT_DUE, early.status());
        assertSame(begun.state(), early.state());
        assertEquals(1, early.state().drafts().size());

        SocialMessageModel.FinishOutcome exact = finish(begun.state(), info, "F1",
                begun.payload().messageId(), completedAt);
        assertEquals(SocialMessageModel.FinishStatus.QUEUED, exact.status());
        assertEquals(0, exact.state().drafts().size());
        assertEquals(completedAt + DELIVERY, exact.state().messages().get(0).dueAt());
        assertEquals(2, exact.state().commands().size());
        assertEquals(completedAt, exact.state().clock());
    }

    @Test
    void deliverRejectsEarlyAndAcceptsExactBoundary() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome finished = finish(begun.state(), info, "F1",
                begun.payload().messageId(), T0 + SEND);
        long dueAt = T0 + SEND + DELIVERY;
        SocialMessageModel.DeliverOutcome early = deliver(finished.state(), info, "D1",
                begun.payload(), dueAt - 1L);
        assertEquals(SocialMessageModel.DeliverStatus.NOT_DUE, early.status());
        assertSame(info, early.information());
        assertEquals(SocialMessageModel.DeliverStatus.DELIVERED, deliver(early.social(), info, "D1",
                begun.payload(), dueAt).status());
    }

    @Test
    void addOverflowRejectedAtBothSteps() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.Context permissive = permissiveContext();
        SocialMessageModel.BeginOutcome overflow = SocialMessageModel.beginSend(
                SocialMessageModel.open(), info, permissive, ALICE, "A1", BOB, CHANNEL, "标题",
                "正文", Long.MAX_VALUE - 500L);
        assertEquals(SocialMessageModel.BeginStatus.TIME_OVERFLOW, overflow.status());
        assertEquals(0, overflow.state().drafts().size());
        assertEquals(Long.MAX_VALUE - 500L, overflow.state().clock());

        SocialMessageModel.BeginOutcome begun = SocialMessageModel.beginSend(
                SocialMessageModel.open(), info, permissive, ALICE, "A1", BOB, CHANNEL, "标题",
                "正文", T0);
        SocialMessageModel.FinishOutcome finishOverflow = SocialMessageModel.finishSend(
                begun.state(), info, permissive, ALICE, "F1", begun.payload().messageId(),
                Long.MAX_VALUE - 500L);
        assertEquals(SocialMessageModel.FinishStatus.TIME_OVERFLOW, finishOverflow.status());
        assertEquals(1, finishOverflow.state().drafts().size());
        assertEquals(2, finishOverflow.state().commands().size());
    }

    // ---------------------------------------------------------------- 送达前不可读

    @Test
    void beforeDeliveryCatalogAndGuessedDocIdAreUnavailable() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome finished = finish(begun.state(), info, "F1",
                begun.payload().messageId(), T0 + SEND);
        long before = T0 + SEND + DELIVERY - 1L;
        String docId = begun.payload().docId();

        for (InformationModel.CatalogEntry entry : InformationModel.catalog(info, access(), BOB,
                before)) {
            assertFalse(entry.docId().equals(docId));
        }
        assertEquals(Status.NOT_AVAILABLE, InformationModel.startRead(info, access(),
                BOB, docId, 1L, before).status());
        assertTrue(SocialMessageModel.inbox(finished.state(), BOB).isEmpty());
        for (InformationModel.CatalogEntry entry : InformationModel.catalog(info, access(), ALICE,
                before)) {
            assertFalse(entry.docId().equals(docId));
        }
    }

    // ---------------------------------------------------------------- 屏蔽

    @Test
    void blockWhileWritingAbortsWithoutPublishing() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.BlockOutcome blocked = block(begun.state(), BOB, "B1", ALICE, T0 + 500L);
        assertEquals(SocialMessageModel.BlockStatus.BLOCKED, blocked.status());
        SocialMessageModel.FinishOutcome finished = finish(blocked.state(), info, "F1",
                begun.payload().messageId(), T0 + SEND);
        assertEquals(SocialMessageModel.FinishStatus.ABORTED, finished.status());
        assertEquals(SocialMessageModel.MessageStatus.ABORTED,
                finished.state().messages().get(0).status());
        assertEquals(0, finished.state().drafts().size());

        SocialMessageModel.DeliverOutcome deliver = deliver(finished.state(), info, "D1",
                begun.payload(), T0 + SEND + 10_000L);
        assertEquals(SocialMessageModel.DeliverStatus.ABORTED, deliver.status());
        assertSame(info, deliver.information());
    }

    @Test
    void blockWhileQueuedBlocksDeliveryWithoutAnyStateHalfChange() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome finished = finish(begun.state(), info, "F1",
                begun.payload().messageId(), T0 + SEND);
        SocialMessageModel.BlockOutcome blocked = block(finished.state(), BOB, "B1", ALICE,
                T0 + SEND + 1L);
        SocialMessageModel.DeliverOutcome deliver = deliver(blocked.state(), info, "D1",
                begun.payload(), T0 + SEND + DELIVERY);
        assertEquals(SocialMessageModel.DeliverStatus.BLOCKED, deliver.status());
        assertSame(info, deliver.information());
        assertEquals(2, info.documents().size());
        assertEquals(info.documents().size(), deliver.information().documents().size());
        assertEquals(SocialMessageModel.MessageStatus.BLOCKED,
                deliver.social().messages().get(0).status());
        assertEquals(SocialMessageModel.OutboxStatus.UNDELIVERED,
                SocialMessageModel.outbox(deliver.social(), ALICE).get(0).status());
        assertEquals(4, deliver.social().commands().size());
        assertEquals("BLOCKED", deliver.social().commands().get(3).result());
    }

    @Test
    void blockAfterDeliveryKeepsOldDocumentButStopsNewMessages() {
        SocialMessageModel.DeliverOutcome delivered = fullChain(aliceReady(), T0);
        long deliveryAt = T0 + SEND + DELIVERY;
        SocialMessageModel.BlockOutcome blocked = block(delivered.social(), BOB, "B1", ALICE,
                deliveryAt + 1L);
        assertEquals(SocialMessageModel.BlockStatus.BLOCKED, blocked.status());
        InformationModel.Outcome started = InformationModel.startRead(delivered.information(), access(),
                BOB, delivered.message().docId(), 1L, deliveryAt + 100L);
        assertEquals(Status.STARTED, started.status());
        assertEquals(SocialMessageModel.BeginStatus.UNREACHABLE, SocialMessageModel.beginSend(
                blocked.state(), delivered.information(), context(), ALICE, "A2", BOB, CHANNEL,
                "再来一封", "正文", deliveryAt + 200L).status());
    }

    @Test
    void unblockRestoresReachability() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BlockOutcome blocked = block(SocialMessageModel.open(), ALICE, "A1", BOB,
                100L);
        SocialMessageModel.BlockOutcome again = block(blocked.state(), ALICE, "A2", BOB, 200L);
        assertEquals(SocialMessageModel.BlockStatus.ALREADY_BLOCKED, again.status());
        assertEquals(1, again.state().blocks().size());
        SocialMessageModel.BlockOutcome reversed = block(again.state(), ALICE, "A3", BOB, 50L);
        assertEquals(SocialMessageModel.BlockStatus.TIME_REVERSED, reversed.status());
        assertSame(again.state(), reversed.state());

        assertEquals(SocialMessageModel.BeginStatus.UNREACHABLE,
                SocialMessageModel.beginSend(again.state(), info, context(), ALICE, "A4", BOB,
                        CHANNEL, "标题", "正文", T0).status());
        SocialMessageModel.UnblockOutcome unblocked = unblock(again.state(), ALICE, "A5", BOB,
                3_000L);
        assertEquals(SocialMessageModel.UnblockStatus.UNBLOCKED, unblocked.status());
        assertEquals(SocialMessageModel.BeginStatus.STARTED,
                SocialMessageModel.beginSend(unblocked.state(), info, context(), ALICE, "A6", BOB,
                        CHANNEL, "标题", "正文", 3_001L).status());
        assertEquals(SocialMessageModel.UnblockStatus.NOT_BLOCKED,
                unblock(unblocked.state(), ALICE, "A7", BOB, 4_000L).status());
    }

    @Test
    void blockUnknownTargetSucceedsWithoutLeakingExistence() {
        SocialMessageModel.BlockOutcome blocked = block(SocialMessageModel.open(), CAROL, "C1",
                "ghost", 10L);
        assertEquals(SocialMessageModel.BlockStatus.BLOCKED, blocked.status());
        assertEquals(SocialMessageModel.BlockStatus.SELF,
                block(SocialMessageModel.open(), CAROL, "C2", CAROL, 10L).status());
    }

    // ---------------------------------------------------------------- 命令绑定与幂等

    @Test
    void repeatedBeginSameContentIsIdempotentAndDifferentContentConflicts() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome first = begin(info, "A1", T0);
        SocialMessageModel.BeginOutcome replay = begin(first.state(), info, "A1", T0 + 10L);
        // 重放返回原业务结果（STARTED）与当前状态，不新增草稿
        assertEquals(SocialMessageModel.BeginStatus.STARTED, replay.status());
        assertSame(first.state(), replay.state());
        assertEquals(first.payload().messageId(), replay.payload().messageId());
        assertEquals(1, replay.state().drafts().size());
        assertEquals(1, replay.state().commands().size());

        SocialMessageModel.BeginOutcome conflict = SocialMessageModel.beginSend(first.state(), info,
                context(), ALICE, "A1", BOB, CHANNEL, "另一个标题", "正文 A1", T0 + 20L);
        assertEquals(SocialMessageModel.BeginStatus.COMMAND_CONFLICT, conflict.status());
        assertSame(first.state(), conflict.state());
        assertEquals(1, conflict.state().drafts().size());
    }

    @Test
    void busyReplayStaysBusyAfterDraftFinished() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome x1 = begin(info, "X1", T0);
        assertEquals(SocialMessageModel.BeginStatus.STARTED, x1.status());
        SocialMessageModel.BeginOutcome y1 = begin(x1.state(), info, "Y1", T0 + 1L);
        assertEquals(SocialMessageModel.BeginStatus.BUSY, y1.status());

        SocialMessageModel.FinishOutcome finished = finish(y1.state(), info, "F1",
                x1.payload().messageId(), T0 + SEND);
        assertEquals(SocialMessageModel.FinishStatus.QUEUED, finished.status());

        SocialMessageModel.BeginOutcome retry = begin(finished.state(), info, "Y1", T0 + SEND + 1L);
        assertEquals(SocialMessageModel.BeginStatus.BUSY, retry.status());
        assertSame(finished.state(), retry.state());
        assertEquals(0, retry.state().drafts().size());
        assertEquals(1, retry.state().messages().size());
    }

    @Test
    void unreachableReplayStaysUnreachableAfterConditionsChange() {
        InformationModel.State info = baseInfo();
        SocialMessageModel.BeginOutcome first = begin(info, "Z1", T0);
        assertEquals(SocialMessageModel.BeginStatus.UNREACHABLE, first.status());

        InformationModel.State read = readDoc(info, ALICE, CONTACT_BOB_DOC, 0L);
        SocialMessageModel.BeginOutcome retry = begin(first.state(), read, "Z1", T0 + 1_000L);
        assertEquals(SocialMessageModel.BeginStatus.UNREACHABLE, retry.status());
        assertSame(first.state(), retry.state());
        assertEquals(0, retry.state().drafts().size());

        assertEquals(SocialMessageModel.BeginStatus.STARTED,
                begin(retry.state(), read, "Z2", T0 + 1_001L).status());
    }

    @Test
    void crossKindCommandIdIsConflict() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "K1", T0);
        SocialMessageModel.FinishOutcome cross = finish(begun.state(), info, "K1",
                begun.payload().messageId(), T0 + SEND);
        assertEquals(SocialMessageModel.FinishStatus.COMMAND_CONFLICT, cross.status());
        assertSame(begun.state(), cross.state());

        SocialMessageModel.BlockOutcome blocked = block(SocialMessageModel.open(), ALICE, "K2", BOB,
                100L);
        SocialMessageModel.UnblockOutcome reversed = unblock(blocked.state(), ALICE, "K2", BOB,
                200L);
        assertEquals(SocialMessageModel.UnblockStatus.COMMAND_CONFLICT, reversed.status());
        assertSame(blocked.state(), reversed.state());
        assertEquals(1, blocked.state().blocks().size());
    }

    @Test
    void sameBlockCommandIdDifferentTargetIsConflict() {
        SocialMessageModel.BlockOutcome first = block(SocialMessageModel.open(), ALICE, "B1", BOB,
                100L);
        SocialMessageModel.BlockOutcome second = block(first.state(), ALICE, "B1", CAROL, 200L);
        assertEquals(SocialMessageModel.BlockStatus.COMMAND_CONFLICT, second.status());
        assertSame(first.state(), second.state());
        assertEquals(1, second.state().blocks().size());
    }

    @Test
    void replayedBlockDoesNotReblockAfterUnblock() {
        SocialMessageModel.BlockOutcome blocked = block(SocialMessageModel.open(), ALICE, "B1", BOB,
                100L);
        SocialMessageModel.UnblockOutcome unblocked = unblock(blocked.state(), ALICE, "U1", BOB,
                200L);
        assertEquals(SocialMessageModel.UnblockStatus.UNBLOCKED, unblocked.status());

        SocialMessageModel.BlockOutcome replay = block(unblocked.state(), ALICE, "B1", BOB, 300L);
        assertEquals(SocialMessageModel.BlockStatus.BLOCKED, replay.status());
        assertSame(unblocked.state(), replay.state());
        assertTrue(replay.state().blocks().isEmpty());
        assertTrue(SocialMessageModel.reachable(replay.state(), aliceReady(), context(), ALICE, BOB,
                CHANNEL, 2_000L));
    }

    @Test
    void replayAcrossLaterActionsReportsCurrentState() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome finished = finish(begun.state(), info, "F1",
                begun.payload().messageId(), T0 + SEND);

        SocialMessageModel.BeginOutcome beginReplay = begin(finished.state(), info, "A1",
                T0 + SEND);
        assertEquals(SocialMessageModel.BeginStatus.STARTED, beginReplay.status());
        assertSame(finished.state(), beginReplay.state());
        assertEquals(SocialMessageModel.MessageStatus.QUEUED,
                beginReplay.state().messages().get(0).status());
        assertEquals(0, beginReplay.state().drafts().size());

        SocialMessageModel.DeliverOutcome delivered = deliver(finished.state(), info, "D1",
                begun.payload(), T0 + SEND + DELIVERY);
        assertEquals(SocialMessageModel.DeliverStatus.DELIVERED, delivered.status());

        SocialMessageModel.FinishOutcome finishReplay = finish(delivered.social(), info, "F1",
                begun.payload().messageId(), T0 + SEND + DELIVERY + 100L);
        assertEquals(SocialMessageModel.FinishStatus.QUEUED, finishReplay.status());
        assertSame(delivered.social(), finishReplay.state());
        assertEquals(SocialMessageModel.MessageStatus.DELIVERED,
                finishReplay.state().messages().get(0).status());

        SocialMessageModel.DeliverOutcome deliverReplay = deliver(delivered.social(),
                delivered.information(), "D1", begun.payload(), T0 + SEND + DELIVERY + 200L);
        assertEquals(SocialMessageModel.DeliverStatus.DELIVERED, deliverReplay.status());
        assertSame(delivered.social(), deliverReplay.social());
        assertSame(delivered.information(), deliverReplay.information());
    }

    @Test
    void replayEarlierThanWatermarkIsReadOnly() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.BlockOutcome blocked = block(begun.state(), BOB, "B1", ALICE, 2_500L);
        assertEquals(2_500L, blocked.state().clock());

        SocialMessageModel.BeginOutcome replay = begin(blocked.state(), info, "A1", 1_000L);
        assertEquals(SocialMessageModel.BeginStatus.STARTED, replay.status());
        assertSame(blocked.state(), replay.state());
        assertEquals(2_500L, replay.state().clock());
        assertEquals(1, replay.state().drafts().size());
    }

    @Test
    void newActionBeforeWatermarkIsRejected() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.DeliverOutcome late = fullChain(info, 100_000L);
        assertEquals(SocialMessageModel.DeliverStatus.DELIVERED, late.status());
        assertEquals(104_000L, late.social().clock());

        SocialMessageModel.BeginOutcome early = begin(late.social(), info, "A9", 1_000L);
        assertEquals(SocialMessageModel.BeginStatus.TIME_REVERSED, early.status());
        assertSame(late.social(), early.state());
        assertEquals(0, early.state().drafts().size());

        SocialMessageModel.BlockOutcome reverseBlock = block(late.social(), ALICE, "B9", BOB, 999L);
        assertEquals(SocialMessageModel.BlockStatus.TIME_REVERSED, reverseBlock.status());
        assertSame(late.social(), reverseBlock.state());
    }

    @Test
    void unknownCallerLeavesNoProbeableRecord() {
        SocialMessageModel.State fresh = SocialMessageModel.open();
        SocialMessageModel.BeginOutcome begin = SocialMessageModel.beginSend(fresh, aliceReady(),
                context(), "mallory", "M1", BOB, CHANNEL, "标题", "正文", T0);
        assertEquals(SocialMessageModel.BeginStatus.UNREACHABLE, begin.status());
        assertSame(fresh, begin.state());
        SocialMessageModel.BlockOutcome blocked = SocialMessageModel.block(fresh, registry(),
                "mallory", "M2", BOB, T0);
        assertEquals(SocialMessageModel.BlockStatus.UNKNOWN_CALLER, blocked.status());
        assertSame(fresh, blocked.state());
        assertTrue(fresh.commands().isEmpty());
        assertEquals(0L, fresh.clock());

        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome real = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome foreign = SocialMessageModel.finishSend(real.state(), info,
                context(), "mallory", "M3", real.payload().messageId(), T0 + SEND);
        assertEquals(SocialMessageModel.FinishStatus.NOT_FOUND, foreign.status());
        assertSame(real.state(), foreign.state());
        assertEquals(1, foreign.state().commands().size());
    }

    @Test
    void sameCommandIdDifferentContentAfterCompletionIsRejected() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome finished = finish(begun.state(), info, "F1",
                begun.payload().messageId(), T0 + SEND);
        SocialMessageModel.BeginOutcome conflict = SocialMessageModel.beginSend(finished.state(), info,
                context(), ALICE, "A1", BOB, CHANNEL, "标题 A1", "换掉的正文", T0 + SEND);
        assertEquals(SocialMessageModel.BeginStatus.COMMAND_CONFLICT, conflict.status());
        assertSame(finished.state(), conflict.state());
        assertEquals(1, conflict.state().messages().size());
    }

    // ---------------------------------------------------------------- 投递终态

    @Test
    void terminalBlockedMessageIsNotRevivedByUnblock() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome finished = finish(begun.state(), info, "F1",
                begun.payload().messageId(), T0 + SEND);
        SocialMessageModel.BlockOutcome blocked = block(finished.state(), BOB, "B1", ALICE,
                T0 + SEND + 1L);
        SocialMessageModel.DeliverOutcome first = deliver(blocked.state(), info, "D1",
                begun.payload(), T0 + SEND + DELIVERY);
        assertEquals(SocialMessageModel.DeliverStatus.BLOCKED, first.status());

        SocialMessageModel.UnblockOutcome unblocked = unblock(first.social(), BOB, "U1", ALICE,
                T0 + SEND + DELIVERY + 100L);
        assertEquals(SocialMessageModel.UnblockStatus.UNBLOCKED, unblocked.status());
        SocialMessageModel.DeliverOutcome again = deliver(unblocked.state(), info, "D2",
                begun.payload(), T0 + SEND + DELIVERY + 200L);
        assertEquals(SocialMessageModel.DeliverStatus.BLOCKED, again.status());
        assertSame(info, again.information());
        assertEquals(SocialMessageModel.MessageStatus.BLOCKED,
                again.social().messages().get(0).status());
        assertEquals(SocialMessageModel.OutboxStatus.UNDELIVERED,
                SocialMessageModel.outbox(again.social(), ALICE).get(0).status());
        assertEquals(6, again.social().commands().size());
    }

    @Test
    void swappedPayloadIsRejectedAndCanStillDeliverOriginal() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome finished = finish(begun.state(), info, "F1",
                begun.payload().messageId(), T0 + SEND);
        long dueAt = T0 + SEND + DELIVERY;
        SocialMessageModel.PendingPayload swapped = new SocialMessageModel.PendingPayload(
                begun.payload().messageId(), begun.payload().docId(), ALICE, BOB, CHANNEL,
                "标题 A1", "被替换的正文");
        SocialMessageModel.DeliverOutcome mismatch = deliver(finished.state(), info, "D1", swapped,
                dueAt);
        assertEquals(SocialMessageModel.DeliverStatus.PAYLOAD_MISMATCH, mismatch.status());
        assertSame(finished.state(), mismatch.social());
        assertSame(info, mismatch.information());
        assertEquals(SocialMessageModel.DeliverStatus.DELIVERED, deliver(mismatch.social(), info,
                "D1", begun.payload(), dueAt).status());
    }

    @Test
    void sameDeliverCommandWithDifferentPayloadIsConflict() {
        SocialMessageModel.DeliverOutcome delivered = fullChain(aliceReady(), T0);
        SocialMessageModel.PendingPayload other = new SocialMessageModel.PendingPayload(
                delivered.message().messageId(), delivered.message().docId(), ALICE, BOB, CHANNEL,
                "标题 A1", "另一份正文");
        SocialMessageModel.DeliverOutcome conflict = deliver(delivered.social(),
                delivered.information(), "D1", other, T0 + SEND + DELIVERY + 100L);
        assertEquals(SocialMessageModel.DeliverStatus.COMMAND_CONFLICT, conflict.status());
        assertSame(delivered.information(), conflict.information());
        assertEquals(3, conflict.information().documents().size());
    }

    @Test
    void forgedRecipientOrDocIdIsRejected() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome finished = finish(begun.state(), info, "F1",
                begun.payload().messageId(), T0 + SEND);
        long dueAt = T0 + SEND + DELIVERY;
        SocialMessageModel.PendingPayload wrongRecipient = new SocialMessageModel.PendingPayload(
                begun.payload().messageId(), begun.payload().docId(), ALICE, CAROL, CHANNEL,
                "标题 A1", "正文 A1");
        assertEquals(SocialMessageModel.DeliverStatus.PAYLOAD_MISMATCH, deliver(finished.state(),
                info, "D1", wrongRecipient, dueAt).status());

        String otherMessage = SocialMessageModel.messageId(ALICE, "A1") + "-copy";
        SocialMessageModel.PendingPayload wrongDoc = new SocialMessageModel.PendingPayload(
                otherMessage, SocialMessageModel.docIdFor(otherMessage), ALICE, BOB, CHANNEL,
                "标题 A1", "正文 A1");
        assertEquals(SocialMessageModel.DeliverStatus.UNKNOWN, deliver(finished.state(), info, "D2",
                wrongDoc, dueAt).status());
    }

    @Test
    void unknownOrForeignDraftCannotBeFinished() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome foreign = SocialMessageModel.finishSend(begun.state(), info,
                context(), BOB, "F1", begun.payload().messageId(), T0 + SEND);
        assertEquals(SocialMessageModel.FinishStatus.NOT_FOUND, foreign.status());
        assertEquals(2, foreign.state().commands().size());
        assertEquals(1, foreign.state().drafts().size());
        assertEquals(SocialMessageModel.FinishStatus.NOT_FOUND,
                finish(foreign.state(), info, "F2", "MSG-gone", T0 + SEND).status());
    }

    @Test
    void malformedArgumentsAreRejected() {
        InformationModel.State info = aliceReady();
        assertThrows(IllegalArgumentException.class, () -> SocialMessageModel.beginSend(
                SocialMessageModel.open(), info, context(), ALICE, "A1", BOB, CHANNEL, " ",
                "正文", T0));
        assertThrows(IllegalArgumentException.class, () -> SocialMessageModel.beginSend(
                SocialMessageModel.open(), info, context(), ALICE, "A1", BOB, CHANNEL, "标题",
                "", T0));
        assertThrows(IllegalArgumentException.class, () -> SocialMessageModel.beginSend(
                SocialMessageModel.open(), info, context(), " ", "A1", BOB, CHANNEL, "标题",
                "正文", T0));
        assertThrows(IllegalArgumentException.class, () -> SocialMessageModel.block(
                SocialMessageModel.open(), registry(), ALICE, "A1", BOB, -1L));
        assertThrows(NullPointerException.class, () -> SocialMessageModel.deliver(
                SocialMessageModel.open(), info, context(), "D1", null, T0));
        assertEquals(SocialMessageModel.BeginStatus.UNREACHABLE,
                SocialMessageModel.beginSend(SocialMessageModel.open(), info, context(), ALICE, "A1",
                        ALICE, CHANNEL, "标题", "正文", T0).status());
    }

    @Test
    void thirdPartySeesNothingOfOtherPeoplesMessages() {
        SocialMessageModel.DeliverOutcome delivered = fullChain(aliceReady(), T0);
        assertTrue(SocialMessageModel.inbox(delivered.social(), CAROL).isEmpty());
        assertTrue(SocialMessageModel.outbox(delivered.social(), CAROL).isEmpty());
        assertFalse(SocialMessageModel.reachable(delivered.social(), delivered.information(),
                context(), CAROL, BOB, CHANNEL, T0 + 10_000L));
        assertEquals(1, SocialMessageModel.inbox(delivered.social(), BOB).size());
        assertEquals(1, SocialMessageModel.outbox(delivered.social(), ALICE).size());
    }

    @Test
    void stateAndInputsAreImmutable() {
        List<InterviewRun.ContactEntry> mutable = new ArrayList<>(contacts());
        SocialMessageModel.Context context = new SocialMessageModel.Context(registry(), policy(),
                config(), mutable);
        InformationModel.State info = aliceReady();
        mutable.clear();
        assertEquals(2, context.contacts().size());
        assertTrue(SocialMessageModel.reachable(SocialMessageModel.open(), info, context, ALICE, BOB,
                CHANNEL, T0));

        SocialMessageModel.BeginOutcome begun = SocialMessageModel.beginSend(SocialMessageModel.open(),
                info, context, ALICE, "A1", BOB, CHANNEL, "标题", "正文", T0);
        assertThrows(UnsupportedOperationException.class, () -> begun.state().drafts().clear());
        assertThrows(UnsupportedOperationException.class, () -> begun.state().messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> begun.state().blocks().clear());
        assertThrows(UnsupportedOperationException.class, () -> begun.state().commands().clear());
    }

    @Test
    void deliveryRequiresRealDocumentsNotJustMetadata() {
        SocialMessageModel.DeliverOutcome delivered = fullChain(aliceReady(), T0);
        // 只有投递成功才新增文档；信封出现在收件箱，但正文必须再付阅读工时
        assertEquals(1, SocialMessageModel.inbox(delivered.social(), BOB).size());
        assertEquals(0, InformationModel.observations(delivered.information(), BOB).size());
        assertEquals(3, delivered.information().documents().size());
    }

    // ---------------------------------------------------------------- G1-G4 / UNKNOWN / 非法状态

    @Test
    void finishReplayOfExistingMessageIsArchivedSoCommandCannotBeReused() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome begun = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome first = finish(begun.state(), info, "F1",
                begun.payload().messageId(), T0 + SEND);
        assertEquals(SocialMessageModel.FinishStatus.QUEUED, first.status());

        SocialMessageModel.FinishOutcome second = finish(first.state(), info, "F2",
                begun.payload().messageId(), T0 + SEND + 1L);
        assertEquals(SocialMessageModel.FinishStatus.REPLAYED, second.status());
        assertEquals(3, second.state().commands().size());

        SocialMessageModel.BlockOutcome reuse = block(second.state(), ALICE, "F2", CAROL,
                T0 + SEND + 2L);
        assertEquals(SocialMessageModel.BlockStatus.COMMAND_CONFLICT, reuse.status());
        assertTrue(reuse.state().blocks().isEmpty());
    }

    @Test
    void deliverTerminalReplayIsArchivedSoCommandCannotBeReusedForAnotherMessage() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.BeginOutcome first = begin(info, "A1", T0);
        SocialMessageModel.FinishOutcome fin1 = finish(first.state(), info, "F1",
                first.payload().messageId(), T0 + SEND);
        SocialMessageModel.DeliverOutcome delivered = deliver(fin1.state(), info, "D1",
                first.payload(), T0 + SEND + DELIVERY);
        assertEquals(SocialMessageModel.DeliverStatus.DELIVERED, delivered.status());

        SocialMessageModel.BeginOutcome second = begin(delivered.social(), info, "A2",
                T0 + SEND + DELIVERY + 1L);
        SocialMessageModel.FinishOutcome fin2 = finish(second.state(), info, "F2",
                second.payload().messageId(), 9_001L);

        SocialMessageModel.DeliverOutcome replay = deliver(fin2.state(), info, "D2",
                first.payload(), 9_002L);
        assertEquals(SocialMessageModel.DeliverStatus.REPLAYED, replay.status());
        assertEquals(6, replay.social().commands().size());

        SocialMessageModel.DeliverOutcome reuse = deliver(replay.social(), info, "D2",
                second.payload(), 9_003L);
        assertEquals(SocialMessageModel.DeliverStatus.COMMAND_CONFLICT, reuse.status());
    }

    @Test
    void finishUnknownMessageIsArchivedSoSameCommandCannotTargetAnotherMessage() {
        InformationModel.State info = aliceReady();
        SocialMessageModel.FinishOutcome missing = finish(SocialMessageModel.open(), info, "FX",
                "MSG-does-not-exist", T0);
        assertEquals(SocialMessageModel.FinishStatus.NOT_FOUND, missing.status());
        assertEquals(1, missing.state().commands().size());

        SocialMessageModel.BeginOutcome begun = begin(missing.state(), info, "A1", T0 + 1_000L);
        assertEquals(SocialMessageModel.BeginStatus.STARTED, begun.status());
        SocialMessageModel.FinishOutcome reuse = finish(begun.state(), info, "FX",
                begun.payload().messageId(), T0 + SEND);
        assertEquals(SocialMessageModel.FinishStatus.COMMAND_CONFLICT, reuse.status());
        assertSame(begun.state(), reuse.state());
        assertEquals(1, reuse.state().drafts().size());
    }

    @Test
    void unknownDeliveryIsArchivedAndCannotSwitchPayload() {
        InformationModel.State info = aliceReady();
        String ghostId = SocialMessageModel.messageId(ALICE, "G1");
        SocialMessageModel.PendingPayload ghost = new SocialMessageModel.PendingPayload(ghostId,
                SocialMessageModel.docIdFor(ghostId), ALICE, BOB, CHANNEL, "标题", "正文");
        SocialMessageModel.DeliverOutcome unknown = deliver(SocialMessageModel.open(), info, "D9",
                ghost, T0);
        assertEquals(SocialMessageModel.DeliverStatus.UNKNOWN, unknown.status());
        assertEquals(1, unknown.social().commands().size());

        SocialMessageModel.BeginOutcome begun = begin(unknown.social(), info, "A1", T0 + 1_000L);
        SocialMessageModel.DeliverOutcome reuse = deliver(begun.state(), info, "D9",
                begun.payload(), T0 + 2_000L);
        assertEquals(SocialMessageModel.DeliverStatus.COMMAND_CONFLICT, reuse.status());
    }

    @Test
    void reservedDeliveryCallerCannotBeRegisteredAsPerson() {
        OpeningRegistry withSystemId = new OpeningRegistry(List.of(
                new OpeningRegistry.Party(ALICE, OpeningRegistry.PartyKind.PERSON, "Alice"),
                new OpeningRegistry.Party(BOB, OpeningRegistry.PartyKind.PERSON, "Bob"),
                new OpeningRegistry.Party(SocialMessageModel.DELIVERY_CALLER,
                        OpeningRegistry.PartyKind.PERSON, "冒名投递")),
                List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> new SocialMessageModel.Context(
                withSystemId, policy(), config(), contacts()));
        SocialMessageModel.State initial = SocialMessageModel.open();
        SocialMessageModel.BlockOutcome block = SocialMessageModel.block(initial, withSystemId,
                SocialMessageModel.DELIVERY_CALLER, "reserved", ALICE, T0);
        assertEquals(SocialMessageModel.BlockStatus.UNKNOWN_CALLER, block.status());
        assertSame(initial, block.state());
        SocialMessageModel.UnblockOutcome unblock = SocialMessageModel.unblock(initial, withSystemId,
                SocialMessageModel.DELIVERY_CALLER, "reserved", ALICE, T0);
        assertEquals(SocialMessageModel.UnblockStatus.UNKNOWN_CALLER, unblock.status());
        assertSame(initial, unblock.state());
    }

    @Test
    void draftWithoutSendCommandIsRejectedAsIllegalState() {
        SocialMessageModel.Draft draft = new SocialMessageModel.Draft("MSG-x", "X1", ALICE, BOB,
                CHANNEL, "digest", 100L, 200L);
        assertThrows(IllegalArgumentException.class, () -> new SocialMessageModel.State(
                List.of(draft), List.of(), List.of(), List.of(), 200L));

        SocialMessageModel.BeginOutcome begun = begin(aliceReady(), "A1", T0);
        assertEquals(SocialMessageModel.CommandKind.SEND, begun.state().commands().get(0).kind());
        assertEquals(begun.state().drafts().get(0).messageId(),
                begun.state().commands().get(0).subject());
    }
}
