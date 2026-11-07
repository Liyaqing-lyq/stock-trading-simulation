package edu.cufe.auction.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** InterviewDialogue 行为测试：只从正常纯核入口构造，不手造非法中间态。 */
class InterviewDialogueTest {

    static final String R = "r";
    static final String G = "g";
    static final String X = "x";
    static final String M = "m";

    static OpeningRegistry registry() {
        return new OpeningRegistry(List.of(
                new OpeningRegistry.Party(R, OpeningRegistry.PartyKind.PERSON, "记者"),
                new OpeningRegistry.Party(G, OpeningRegistry.PartyKind.PERSON, "对象"),
                new OpeningRegistry.Party(X, OpeningRegistry.PartyKind.PERSON, "旁人"),
                new OpeningRegistry.Party(M, OpeningRegistry.PartyKind.ORGANIZATION, "媒体")),
                List.of(), List.of(), List.of());
    }

    static RoleAccess.State access() {
        return new RoleAccess.State(List.of(), List.of(), List.of(),
                List.of(new RoleAccess.Appointment("ap", R, M, RoleAccess.Role.JOURNALIST,
                        0, Long.MAX_VALUE, null)));
    }

    static InterviewModel.Context context() {
        return new InterviewModel.Context(access(), Map.of(R, Set.of(G)), Map.of());
    }

    static InterviewModel.Policy policy() {
        return new InterviewModel.Policy(M, 1000, 0, 1000);
    }

    static InterviewModel.State interviews(long startAt) {
        String requestId = InterviewModel.requestId(R, "a");
        InterviewModel.State state = InterviewModel.open(policy(), registry());
        state = ok(InterviewModel.invite(state, context(), R,
                new InterviewModel.InviteRequest(requestId, G, "topic", startAt,
                        startAt + 400, 200), startAt));
        state = ok(InterviewModel.respond(state, context(), G, requestId,
                InterviewModel.Decision.accept(startAt), startAt));
        return ok(InterviewModel.start(state, context(), R, requestId, startAt));
    }

    static String sessionId() {
        return "SESSION-" + InterviewModel.requestId(R, "a");
    }

    static InterviewModel.State ok(InterviewModel.Outcome outcome) {
        if (!outcome.accepted()) {
            throw new AssertionError(outcome.reason());
        }
        return outcome.state();
    }

    static InformationModel.State information() {
        return InformationModel.open(registry(), List.of(R, G, X));
    }

    static InterviewDialogue.Config config(int maxTurns, long deadline) {
        return new InterviewDialogue.Config(maxTurns, deadline, 5,
                new CognitionRecall.Policy(1_000_000L, 1, 50, 100_000));
    }

    static InterviewDialogue.SpeakRequest speak(String turnId, String session, String title,
                                                String body, long startedAt) {
        return new InterviewDialogue.SpeakRequest(turnId, session, title, body, 7, startedAt);
    }

    static InterviewDialogue.Outcome send(InterviewDialogue.State dialogue,
                                          InformationModel.State information,
                                          InterviewModel.State interviews, String speaker,
                                          InterviewDialogue.SpeakRequest request, long now) {
        return InterviewDialogue.send(dialogue, information, interviews, context(),
                config(4, 50), speaker, request, now);
    }

    @Test
    void firstTurnMustBeJournalistThenAlternates() {
        var interviews = interviews(100);
        var info = information();
        var dialogue = InterviewDialogue.open();
        var wrong = send(dialogue, info, interviews, G, speak("t1", sessionId(), "A", "a", 100), 105);
        assertFalse(wrong.accepted());
        assertEquals(InterviewDialogue.REASON_STATE, wrong.reason());
        var first = send(dialogue, info, interviews, R, speak("t1", sessionId(), "Q", "q", 100), 105);
        assertTrue(first.accepted(), first.reason());
        var again = send(first.dialogue(), first.information(), interviews, R,
                speak("t2", sessionId(), "Q2", "q2", 105), 110);
        assertFalse(again.accepted());
        assertEquals(InterviewDialogue.REASON_STATE, again.reason());
    }

    @Test
    void thirdPartyAndUnknownSessionAreUnknown() {
        var interviews = interviews(100);
        var info = information();
        var dialogue = InterviewDialogue.open();
        var stranger = send(dialogue, info, interviews, X, speak("t1", sessionId(), "A", "a", 100), 105);
        assertFalse(stranger.accepted());
        assertEquals(InterviewDialogue.REASON_UNKNOWN, stranger.reason());
        var unknown = send(dialogue, info, interviews, R,
                speak("t1", "SESSION-nope", "A", "a", 100), 105);
        assertFalse(unknown.accepted());
        assertEquals(InterviewDialogue.REASON_UNKNOWN, unknown.reason());
    }

    @Test
    void sendPublishesDirectDocumentWithoutAutoReading() {
        var info = information();
        var result = send(InterviewDialogue.open(), info, interviews(100), R,
                speak("t1", sessionId(), "Q", "question", 100), 105);
        assertTrue(result.accepted(), result.reason());
        assertTrue(result.information().observations().isEmpty());
        var doc = result.information().documents().stream()
                .filter(d -> d.docId().equals(InterviewDialogue.turnDocId(sessionId(), 1)))
                .findFirst().orElseThrow();
        assertEquals(R, doc.publisherPartyId());
        assertEquals(InformationModel.AudienceKind.DIRECT, doc.audience().kind());
        assertEquals(G, doc.audience().partyId());
        assertEquals(105, doc.at(1).publishedAt());
    }

    @Test
    void replyRequiresActualCompletedRead() {
        var info = information();
        var first = send(InterviewDialogue.open(), info, interviews(100), R,
                speak("t1", sessionId(), "Q", "question", 100), 105);
        var premature = send(first.dialogue(), first.information(), interviews(100), G,
                speak("t2", sessionId(), "A", "answer", 105), 110);
        assertFalse(premature.accepted());
        assertEquals(InterviewDialogue.REASON_NOT_READ, premature.reason());
    }

    @Test
    void replyAfterReadAndRecallSucceeds() {
        var info = information();
        var first = send(InterviewDialogue.open(), info, interviews(100), R,
                speak("t1", sessionId(), "Q", "question", 100), 105);
        String docId = InterviewDialogue.turnDocId(sessionId(), 1);
        var started = InformationModel.startRead(first.information(), access(), G, docId, 1, 110);
        assertEquals(InformationModel.Status.STARTED, started.status());
        String readId = started.state().active().get(0).readId();
        var read = InformationModel.completeRead(started.state(), access(), G, readId, 117);
        assertEquals(InformationModel.Status.COMPLETED, read.status());
        var reply = send(first.dialogue(), read.state(), interviews(100), G,
                speak("t2", sessionId(), "A", "answer", 117), 122);
        assertTrue(reply.accepted(), reply.reason());
        var doc = reply.information().documents().stream()
                .filter(d -> d.docId().equals(InterviewDialogue.turnDocId(sessionId(), 2)))
                .findFirst().orElseThrow();
        assertEquals(G, doc.publisherPartyId());
        assertEquals(R, doc.audience().partyId());
    }

    @Test
    void limitAndDeadlineAreDerivedFromFacts() {
        var info = information();
        var cfg = config(1, 50);
        var first = InterviewDialogue.send(InterviewDialogue.open(), info, interviews(100),
                context(), cfg, R, speak("t1", sessionId(), "Q", "q", 100), 105);
        assertTrue(first.accepted(), first.reason());
        assertEquals(InterviewDialogue.CloseReason.TURN_LIMIT,
                InterviewDialogue.dueReason(first.dialogue(), first.information(),
                        interviews(100), cfg, sessionId(), 106).orElseThrow());
        var over = InterviewDialogue.send(first.dialogue(), first.information(), interviews(100),
                context(), cfg, G, speak("t2", sessionId(), "A", "a", 106), 111);
        assertFalse(over.accepted());
        assertEquals(InterviewDialogue.REASON_LIMIT, over.reason());

        var fresh = InterviewDialogue.open();
        assertTrue(InterviewDialogue.dueReason(fresh, info, interviews(100), config(4, 50),
                sessionId(), 149).isEmpty());
        assertEquals(InterviewDialogue.CloseReason.DEADLINE_NO_REPLY,
                InterviewDialogue.dueReason(fresh, info, interviews(100), config(4, 50),
                        sessionId(), 150).orElseThrow());
    }

    @Test
    void closeOnlyAfterRealSessionEndedAndDeadlineAtSameInstantWins() {
        var interviews = interviews(100);
        var info = information();
        var active = InterviewDialogue.close(InterviewDialogue.open(), info, interviews,
                config(4, 50), sessionId(), 120);
        assertFalse(active.accepted());
        assertEquals(InterviewDialogue.REASON_STATE, active.reason());

        var partyEnd = ok(InterviewModel.end(interviews, context(), G, sessionId(), 120));
        var party = InterviewDialogue.close(InterviewDialogue.open(), info, partyEnd,
                config(4, 50), sessionId(), 120);
        assertTrue(party.accepted(), party.reason());
        assertEquals(InterviewDialogue.CloseReason.CLOSED_BY_PARTY,
                party.dialogue().closeOf(sessionId()).orElseThrow().reason());

        var deadlineEnd = ok(InterviewModel.end(interviews, context(), G, sessionId(), 150));
        var deadline = InterviewDialogue.close(InterviewDialogue.open(), info, deadlineEnd,
                config(4, 50), sessionId(), 150);
        assertTrue(deadline.accepted(), deadline.reason());
        assertEquals(InterviewDialogue.CloseReason.DEADLINE_NO_REPLY,
                deadline.dialogue().closeOf(sessionId()).orElseThrow().reason());
    }

    @Test
    void afterCloseNoNewSpeakAndOldReadingStillCompletes() {
        var interviews = interviews(100);
        var info = information();
        var first = send(InterviewDialogue.open(), info, interviews, R,
                speak("t1", sessionId(), "Q", "q", 100), 105);
        var ended = ok(InterviewModel.end(interviews, context(), G, sessionId(), 108));
        var closed = InterviewDialogue.close(first.dialogue(), first.information(), ended,
                config(4, 50), sessionId(), 108);
        assertTrue(closed.accepted(), closed.reason());
        var blocked = send(closed.dialogue(), first.information(), ended, G,
                speak("t2", sessionId(), "A", "a", 108), 113);
        assertFalse(blocked.accepted());
        assertEquals(InterviewDialogue.REASON_STATE, blocked.reason());

        String docId = InterviewDialogue.turnDocId(sessionId(), 1);
        var started = InformationModel.startRead(first.information(), access(), G, docId, 1, 106);
        assertEquals(InformationModel.Status.STARTED, started.status());
        var read = InformationModel.completeRead(started.state(), access(), G,
                started.state().active().get(0).readId(), 113);
        assertEquals(InformationModel.Status.COMPLETED, read.status());
    }

    @Test
    void idempotentRetryDoesNotCreateSecondDocument() {
        var info = information();
        var cfg = config(4, 50);
        var first = InterviewDialogue.send(InterviewDialogue.open(), info, interviews(100),
                context(), cfg, R, speak("t1", sessionId(), "Q", "q", 100), 105);
        assertTrue(first.accepted(), first.reason());
        var retry = InterviewDialogue.send(first.dialogue(), first.information(), interviews(100),
                context(), cfg, R, speak("t1", sessionId(), "Q", "q", 100), 105);
        assertTrue(retry.accepted(), retry.reason());
        assertEquals(first.information().documents().size(), retry.information().documents().size());
        var changed = InterviewDialogue.send(first.dialogue(), first.information(), interviews(100),
                context(), cfg, R, speak("t1", sessionId(), "Q", "other", 100), 105);
        assertFalse(changed.accepted());
        assertEquals(InterviewDialogue.REASON_DUPLICATE, changed.reason());
    }

    @Test
    void timeWindowStrictlyEnforced() {
        var info = information();
        var interviews = interviews(100);
        var wrongSpan = send(InterviewDialogue.open(), info, interviews, R,
                speak("t1", sessionId(), "Q", "q", 100), 106);
        assertEquals(InterviewDialogue.REASON_WINDOW, wrongSpan.reason());
        var beforeSession = send(InterviewDialogue.open(), info, interviews, R,
                speak("t1", sessionId(), "Q", "q", 95), 100);
        assertEquals(InterviewDialogue.REASON_WINDOW, beforeSession.reason());
        var first = send(InterviewDialogue.open(), info, interviews, R,
                speak("t1", sessionId(), "Q", "q", 100), 105);
        var notLater = send(first.dialogue(), first.information(), interviews, G,
                speak("t2", sessionId(), "A", "a", 100), 105);
        assertFalse(notLater.accepted());
        assertEquals(InterviewDialogue.REASON_NOT_READ, notLater.reason());
    }

    @Test
    void zeroTimePointAndJsonRoundTrip() {
        var interviews = interviews(0);
        var info = information();
        var first = send(InterviewDialogue.open(), info, interviews, R,
                speak("t1", sessionId(), "Q", "q", 0), 5);
        assertTrue(first.accepted(), first.reason());
        var restored = InterviewDialogue.fromJson(InterviewDialogue.toJson(first.dialogue()));
        assertEquals(first.dialogue(), restored);
        InterviewDialogue.validate(restored, interviews, first.information(), config(4, 50));
    }

    @Test
    void overflowAndZeroConfigAreRejectedWithoutWrapping() {
        var recall = new CognitionRecall.Policy(1_000_000L, 1, 50, 100_000);
        assertThrows(IllegalArgumentException.class,
                () -> new InterviewDialogue.Config(0, 50, 5, recall));
        assertThrows(IllegalArgumentException.class,
                () -> new InterviewDialogue.Config(4, 0, 5, recall));
        assertThrows(IllegalArgumentException.class,
                () -> new InterviewDialogue.Config(4, 50, 0, recall));
        assertThrows(IllegalArgumentException.class,
                () -> new InterviewDialogue.SpeakRequest("t", sessionId(), "T", "B", 0, 0));
        var huge = InterviewDialogue.send(InterviewDialogue.open(), information(), interviews(100),
                context(), config(4, 50), R,
                new InterviewDialogue.SpeakRequest("t1", sessionId(), "Q", "q", 7, Long.MAX_VALUE),
                Long.MAX_VALUE);
        assertFalse(huge.accepted());
        assertTrue(InterviewDialogue.dueReason(InterviewDialogue.open(), information(),
                interviews(100), new InterviewDialogue.Config(4, Long.MAX_VALUE, 5, recall),
                sessionId(), Long.MAX_VALUE).isEmpty());
    }

    @Test
    void lateSpeakAtOrAfterDeadlineRejectedWithoutStateChange() {
        var info0 = information();
        var interviews = interviews(100);
        var cfg = config(4, 50);
        var first = InterviewDialogue.send(InterviewDialogue.open(), info0, interviews, context(),
                cfg, R, speak("t1", sessionId(), "Q", "q", 100), 105);
        assertTrue(first.accepted(), first.reason());
        var read = readTurn(first.information(), G, InterviewDialogue.turnDocId(sessionId(), 1), 105);
        assertEquals(InterviewDialogue.CloseReason.DEADLINE_NO_REPLY,
                InterviewDialogue.dueReason(first.dialogue(), read, interviews, cfg, sessionId(), 155)
                        .orElseThrow());
        var atCut = InterviewDialogue.send(first.dialogue(), read, interviews, context(), cfg, G,
                speak("t2", sessionId(), "A", "a", 150), 155);
        assertFalse(atCut.accepted());
        assertEquals(first.information().documents().size(), atCut.information().documents().size());
        var late = InterviewDialogue.send(first.dialogue(), read, interviews, context(), cfg, G,
                speak("t2", sessionId(), "A", "a", 160), 165);
        assertFalse(late.accepted());
        assertEquals(first.information().documents().size(), late.information().documents().size());
    }

    @Test
    void scheduledEndDiffersFromEarlyAndDeadline() {
        var info = information();
        var scheduled = interviews(100);
        var endedFull = ok(InterviewModel.end(scheduled, context(), R, sessionId(), 300));
        var full = InterviewDialogue.close(InterviewDialogue.open(), info, endedFull,
                config(4, 500), sessionId(), 300);
        assertTrue(full.accepted(), full.reason());
        assertEquals(InterviewDialogue.CloseReason.SCHEDULE_END,
                full.dialogue().closeOf(sessionId()).orElseThrow().reason());

        var early = InterviewDialogue.close(InterviewDialogue.open(), info,
                ok(InterviewModel.end(scheduled, context(), G, sessionId(), 120)),
                config(4, 500), sessionId(), 120);
        assertEquals(InterviewDialogue.CloseReason.CLOSED_BY_PARTY,
                early.dialogue().closeOf(sessionId()).orElseThrow().reason());

        var deadline = InterviewDialogue.close(InterviewDialogue.open(), info,
                ok(InterviewModel.end(scheduled, context(), G, sessionId(), 150)),
                config(4, 50), sessionId(), 150);
        assertEquals(InterviewDialogue.CloseReason.DEADLINE_NO_REPLY,
                deadline.dialogue().closeOf(sessionId()).orElseThrow().reason());
    }

    @Test
    void validateRejectsOverLimitNonAlternatingAndAfterEnd() {
        var interviews = interviews(100);
        var info0 = information();
        var cfg = config(4, 50);
        var first = InterviewDialogue.send(InterviewDialogue.open(), info0, interviews, context(),
                cfg, R, speak("t1", sessionId(), "Q", "q", 100), 105);
        var read = readTurn(first.information(), G, InterviewDialogue.turnDocId(sessionId(), 1), 105);
        var second = InterviewDialogue.send(first.dialogue(), read, interviews, context(), cfg, G,
                speak("t2", sessionId(), "A", "a", 107), 112);
        assertTrue(second.accepted(), second.reason());

        assertThrows(IllegalArgumentException.class, () -> InterviewDialogue.validate(
                second.dialogue(), interviews, second.information(), config(1, 50)));

        var info2 = InformationModel.publish(first.information(),
                InterviewDialogue.turnDocId(sessionId(), 2), R,
                InformationModel.Audience.direct(G),
                new InformationModel.VersionDraft("Q2", "q2", "", 112, 7, List.of()));
        var nonAlternating = new InterviewDialogue.State(List.of(
                new InterviewDialogue.Turn("t1", sessionId(), 1, R,
                        InterviewDialogue.turnDocId(sessionId(), 1), 1),
                new InterviewDialogue.Turn("t2", sessionId(), 2, R,
                        InterviewDialogue.turnDocId(sessionId(), 2), 1)), List.of());
        assertThrows(IllegalArgumentException.class, () -> InterviewDialogue.validate(
                nonAlternating, interviews, info2, cfg));

        var ended = ok(InterviewModel.end(interviews, context(), G, sessionId(), 130));
        var info3 = InformationModel.publish(second.information(),
                InterviewDialogue.turnDocId(sessionId(), 3), R,
                InformationModel.Audience.direct(G),
                new InformationModel.VersionDraft("Q3", "q3", "", 140, 7, List.of()));
        var afterEnd = new InterviewDialogue.State(List.of(
                new InterviewDialogue.Turn("t1", sessionId(), 1, R,
                        InterviewDialogue.turnDocId(sessionId(), 1), 1),
                new InterviewDialogue.Turn("t2", sessionId(), 2, G,
                        InterviewDialogue.turnDocId(sessionId(), 2), 1),
                new InterviewDialogue.Turn("t3", sessionId(), 3, R,
                        InterviewDialogue.turnDocId(sessionId(), 3), 1)), List.of());
        assertThrows(IllegalArgumentException.class, () -> InterviewDialogue.validate(
                afterEnd, ended, info3, cfg));
    }

    @Test
    void idempotentRetryRejectsDifferentStartTime() {
        var info = information();
        var cfg = config(4, 50);
        var first = InterviewDialogue.send(InterviewDialogue.open(), info, interviews(100),
                context(), cfg, R, speak("t1", sessionId(), "Q", "q", 100), 105);
        assertTrue(first.accepted(), first.reason());
        var changedStart = InterviewDialogue.send(first.dialogue(), first.information(),
                interviews(100), context(), cfg, R, speak("t1", sessionId(), "Q", "q", 99), 105);
        assertFalse(changedStart.accepted());
        assertEquals(InterviewDialogue.REASON_DUPLICATE, changedStart.reason());
    }

    static InformationModel.State readTurn(InformationModel.State info, String person,
                                           String docId, long startAt) {
        var started = InformationModel.startRead(info, access(), person, docId, 1, startAt);
        assertEquals(InformationModel.Status.STARTED, started.status());
        return InformationModel.completeRead(started.state(), access(), person,
                started.state().active().get(0).readId(),
                started.state().active().get(0).dueAt()).state();
    }

    @Test
    void crossStateValidationRejectsTamperedDocument() {
        var interviews = interviews(100);
        var info = information();
        var first = send(InterviewDialogue.open(), info, interviews, R,
                speak("t1", sessionId(), "Q", "q", 100), 105);
        var bad = new InterviewDialogue.State(List.of(new InterviewDialogue.Turn("t1", sessionId(),
                1, R, "dialogue-turn:wrong", 1)), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> InterviewDialogue.validate(bad, interviews, first.information(), config(4, 50)));
        assertThrows(IllegalArgumentException.class,
                () -> new InterviewDialogue.State(List.of(
                        new InterviewDialogue.Turn("t1", sessionId(), 1, R, "x", 1),
                        new InterviewDialogue.Turn("t1", sessionId(), 2, G, "y", 1)), List.of()));
    }
}
