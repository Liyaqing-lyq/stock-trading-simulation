package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4i.1 双边采访协调纯核的私有定向测试：全部从正常纯核入口产生状态，
 * 标识一律用 {@link InterviewModel#requestId(String, String)}。
 */
class InterviewModelTest {

    private static final String MEDIA = "media-a";
    private static final String RITA = "rita";
    private static final String SAM = "sam";
    private static final String TOM = "tom";
    private static final long DURATION = 30_000L;

    private static String id(String clientId) {
        return InterviewModel.requestId(RITA, clientId);
    }

    private static String sessionOf(String clientId) {
        return "SESSION-" + id(clientId);
    }

    private static OpeningRegistry registry() {
        return new OpeningRegistry(List.of(
                new OpeningRegistry.Party(RITA, OpeningRegistry.PartyKind.PERSON, "记者"),
                new OpeningRegistry.Party(SAM, OpeningRegistry.PartyKind.PERSON, "对象"),
                new OpeningRegistry.Party(TOM, OpeningRegistry.PartyKind.PERSON, "第三人"),
                new OpeningRegistry.Party(MEDIA, OpeningRegistry.PartyKind.ORGANIZATION, "媒体")),
                List.of(), List.of(), List.of());
    }

    private static InterviewModel.Policy policy() {
        return new InterviewModel.Policy(MEDIA, 600_000L, 0L, 3_600_000L);
    }

    private static RoleAccess.State access(long startsAt, long endsAt) {
        return new RoleAccess.State(List.of(), List.of(), List.of(),
                List.of(new RoleAccess.Appointment("AP-1", RITA, MEDIA, RoleAccess.Role.JOURNALIST,
                        startsAt, endsAt, null)));
    }

    private static RoleAccess.State journalist() {
        return access(0L, 10_000_000L);
    }

    private static InterviewModel.Context context(RoleAccess.State access) {
        return new InterviewModel.Context(access, Map.of(RITA, Set.of(SAM)), Map.of());
    }

    private static InterviewModel.Context context(RoleAccess.State access,
                                                  Map<String, List<InterviewModel.Interval>> busy) {
        return new InterviewModel.Context(access, Map.of(RITA, Set.of(SAM)), busy);
    }

    private static InterviewModel.State opened() {
        return InterviewModel.open(policy(), registry());
    }

    private static InterviewModel.InviteRequest request(String clientId, long windowStart,
                                                        long windowEnd, long duration) {
        return new InterviewModel.InviteRequest(id(clientId), SAM, "业绩与订单", windowStart,
                windowEnd, duration);
    }

    private static InterviewModel.State invite(InterviewModel.State state, String clientId,
                                               long now) {
        InterviewModel.Outcome outcome = InterviewModel.invite(state, context(journalist()),
                RITA, request(clientId, 10_000L, 100_000L, DURATION), now);
        assertTrue(outcome.accepted(), outcome.reason());
        return outcome.state();
    }

    private static InterviewModel.State acceptedAt(InterviewModel.State state, String clientId,
                                                   long start, long now) {
        InterviewModel.Outcome outcome = InterviewModel.respond(state, context(journalist()), SAM,
                id(clientId), InterviewModel.Decision.accept(start), now);
        assertTrue(outcome.accepted(), outcome.reason());
        return outcome.state();
    }

    private static InterviewModel.State startedAt(InterviewModel.State state, String clientId,
                                                  long now) {
        InterviewModel.Outcome outcome = InterviewModel.start(state, context(journalist()), RITA,
                id(clientId), now);
        assertTrue(outcome.accepted(), outcome.reason());
        return outcome.state();
    }

    @Test
    void fullChainInviteAcceptStartEarlyEnd() {
        InterviewModel.State state = invite(opened(), "R1", 1_000L);
        assertEquals(InterviewModel.Status.PROPOSED, state.invites().get(0).status());

        state = acceptedAt(state, "R1", 20_000L, 1_000L);
        assertEquals(InterviewModel.Status.ACCEPTED, state.invites().get(0).status());
        assertEquals(20_000L, state.invites().get(0).startMillis());

        state = startedAt(state, "R1", 20_000L);
        assertEquals(InterviewModel.Status.ACTIVE, state.invites().get(0).status());
        assertEquals(sessionOf("R1"), state.sessions().get(0).sessionId());
        assertTrue(InterviewModel.canSpeak(state, context(journalist()), SAM, sessionOf("R1"),
                21_000L).allowed());

        InterviewModel.Outcome ended = InterviewModel.end(state, context(journalist()), SAM,
                sessionOf("R1"), 25_000L);
        assertTrue(ended.accepted(), ended.reason());
        state = ended.state();
        assertEquals(InterviewModel.Status.ENDED_EARLY, state.invites().get(0).status());
        assertEquals(InterviewModel.CloseKind.EARLY, state.sessions().get(0).close());
        assertEquals(25_000L, state.sessions().get(0).endedAtMillis());
        assertEquals(25_000L, state.invites().get(0).transitionAtMillis());
        assertFalse(InterviewModel.canSpeak(state, context(journalist()), SAM, sessionOf("R1"),
                26_000L).allowed());
    }

    @Test
    void declineIsTerminal() {
        InterviewModel.State state = invite(opened(), "R1", 1_000L);
        InterviewModel.Outcome declined = InterviewModel.respond(state, context(journalist()), SAM,
                id("R1"), InterviewModel.Decision.decline(), 2_000L);
        assertTrue(declined.accepted(), declined.reason());
        assertEquals(InterviewModel.Status.DECLINED, declined.state().invites().get(0).status());
        assertFalse(InterviewModel.respond(declined.state(), context(journalist()), SAM, id("R1"),
                InterviewModel.Decision.accept(20_000L), 2_000L).accepted());
    }

    @Test
    void unansweredInviteExpiresOnce() {
        InterviewModel.State state = invite(opened(), "R1", 1_000L);
        InterviewModel.Outcome expired = InterviewModel.expire(state, 200_000L);
        assertTrue(expired.accepted());
        InterviewModel.Invite invite = expired.state().invites().get(0);
        assertEquals(InterviewModel.Status.EXPIRED, invite.status());
        assertEquals(200_000L, invite.transitionAtMillis());
        assertSame(expired.state(), InterviewModel.expire(expired.state(), 300_000L).state());
    }

    @Test
    void cancelReleasesBookingButKeepsRecord() {
        InterviewModel.State state = acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L,
                1_000L);
        InterviewModel.Outcome canceled = InterviewModel.cancel(state, context(journalist()), RITA,
                id("R1"), 5_000L);
        assertTrue(canceled.accepted(), canceled.reason());
        assertEquals(1, canceled.state().invites().size());
        assertEquals(InterviewModel.Status.CANCELED, canceled.state().invites().get(0).status());
        assertEquals(0, canceled.state().sessions().size());

        InterviewModel.Outcome again = InterviewModel.invite(canceled.state(),
                context(journalist()), RITA, request("R2", 10_000L, 100_000L, DURATION), 5_000L);
        assertTrue(again.accepted(), again.reason());
        assertTrue(InterviewModel.respond(again.state(), context(journalist()), SAM, id("R2"),
                InterviewModel.Decision.accept(20_000L), 5_000L).accepted(), "取消后不再占用未来时段");
    }

    @Test
    void cancelCannotPredateAcceptance() {
        InterviewModel.State state = acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L,
                20_000L);
        InterviewModel.Outcome canceled = InterviewModel.cancel(state, context(journalist()), SAM,
                id("R1"), 15_000L);
        assertFalse(canceled.accepted());
        assertEquals(InterviewModel.REASON_STATE, canceled.reason());
        assertSame(state, canceled.state());
    }

    @Test
    void respondPastStartIsBusinessRejection() {
        InterviewModel.State state = invite(opened(), "R1", 1_000L);
        InterviewModel.Outcome outcome = InterviewModel.respond(state, context(journalist()), SAM,
                id("R1"), InterviewModel.Decision.accept(5_000L), 150_000L);
        assertFalse(outcome.accepted());
        assertEquals(InterviewModel.REASON_EXPIRED, outcome.reason());
        assertSame(state, outcome.state());

        InterviewModel.State fresh = InterviewModel.open(policy(), registry());
        InterviewModel.Outcome late = InterviewModel.respond(
                InterviewModel.invite(fresh, context(journalist()), RITA,
                        new InterviewModel.InviteRequest(id("L"), SAM, "主题", 100_000L, 900_000L,
                                DURATION), 1_000L).state(),
                context(journalist()), SAM, id("L"), InterviewModel.Decision.accept(50_000L),
                60_000L);
        assertFalse(late.accepted());
        assertEquals(InterviewModel.REASON_WINDOW, late.reason());
    }

    @Test
    void currentBusyDoesNotBlockFutureSlot() {
        InterviewModel.State state = invite(opened(), "R1", 1_000L);
        InterviewModel.Context busyNow = context(journalist(),
                Map.of(RITA, List.of(new InterviewModel.Interval(0L, 15_000L))));
        assertTrue(InterviewModel.respond(state, busyNow, SAM, id("R1"),
                InterviewModel.Decision.accept(20_000L), 1_000L).accepted());

        InterviewModel.Context busyAtSlot = context(journalist(),
                Map.of(RITA, List.of(new InterviewModel.Interval(19_000L, 21_000L))));
        InterviewModel.Outcome conflicted = InterviewModel.respond(state, busyAtSlot, SAM,
                id("R1"), InterviewModel.Decision.accept(20_000L), 1_000L);
        assertFalse(conflicted.accepted());
        assertEquals(InterviewModel.REASON_CONFLICT, conflicted.reason());
        assertSame(state, conflicted.state());
    }

    @Test
    void startRechecksExternalBusyAppearingAfterAccept() {
        InterviewModel.State state = acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L,
                1_000L);
        InterviewModel.Context busy = context(journalist(),
                Map.of(SAM, List.of(new InterviewModel.Interval(19_000L, 25_000L))));
        InterviewModel.Outcome started = InterviewModel.start(state, busy, RITA, id("R1"),
                20_000L);
        assertFalse(started.accepted());
        assertEquals(InterviewModel.REASON_CONFLICT, started.reason());
        assertSame(state, started.state());
    }

    @Test
    void acceptIsRejectedWhenJournalistRevoked() {
        InterviewModel.State state = invite(opened(), "R1", 1_000L);
        InterviewModel.Outcome accepted = InterviewModel.respond(state, context(access(0L, 500L)),
                SAM, id("R1"), InterviewModel.Decision.accept(20_000L), 1_000L);
        assertFalse(accepted.accepted());
        assertEquals(InterviewModel.REASON_NOT_AUTHORIZED, accepted.reason());
        assertSame(state, accepted.state());
    }

    @Test
    void backToBackSlotsDoNotConflict() {
        InterviewModel.Outcome first = InterviewModel.invite(opened(), context(journalist()), RITA,
                request("R1", 10_000L, 100_000L, 10_000L), 1_000L);
        assertTrue(first.accepted(), first.reason());
        InterviewModel.State state = acceptedAt(first.state(), "R1", 10_000L, 1_000L);
        InterviewModel.Outcome second = InterviewModel.invite(state, context(journalist()), RITA,
                request("R2", 10_000L, 100_000L, 10_000L), 1_000L);
        assertTrue(second.accepted(), second.reason());
        assertTrue(InterviewModel.respond(second.state(), context(journalist()), SAM, id("R2"),
                InterviewModel.Decision.accept(20_000L), 1_000L).accepted());
    }

    @Test
    void doubleBookingIsRejected() {
        InterviewModel.State state = acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L,
                1_000L);
        state = invite(state, "R2", 1_000L);
        InterviewModel.Outcome conflicted = InterviewModel.respond(state, context(journalist()),
                SAM, id("R2"), InterviewModel.Decision.accept(25_000L), 1_000L);
        assertFalse(conflicted.accepted());
        assertEquals(InterviewModel.REASON_CONFLICT, conflicted.reason());
    }

    @Test
    void startFailsWhenJournalistRevokedAndStateUnchanged() {
        InterviewModel.State state = acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L,
                1_000L);
        InterviewModel.Outcome started = InterviewModel.start(state, context(access(0L, 19_000L)),
                RITA, id("R1"), 20_000L);
        assertFalse(started.accepted());
        assertEquals(InterviewModel.REASON_NOT_AUTHORIZED, started.reason());
        assertSame(state, started.state());
        assertEquals(InterviewModel.Status.ACCEPTED, started.state().invites().get(0).status());
    }

    @Test
    void historySurvivesRevocationWhileSpeechStops() {
        InterviewModel.State state = acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L,
                1_000L);
        state = startedAt(state, "R1", 20_000L);

        RoleAccess.State revoked = access(0L, 21_000L);
        InterviewModel.Speak blocked = InterviewModel.canSpeak(state, context(revoked), RITA,
                sessionOf("R1"), 22_000L);
        assertFalse(blocked.allowed());
        assertEquals(InterviewModel.REASON_NOT_AUTHORIZED, blocked.reason());

        InterviewModel.State ended = InterviewModel.end(state, context(journalist()), SAM,
                sessionOf("R1"), 25_000L).state();
        assertEquals(1, InterviewModel.sessions(ended, context(revoked), SAM, 30_000L).size());
        assertEquals(1, InterviewModel.inbox(ended, context(revoked), SAM, 30_000L).size());
    }

    @Test
    void unreachableAndUnknownAreIndistinguishable() {
        InterviewModel.State state = opened();
        InterviewModel.Outcome ghost = InterviewModel.invite(state, context(journalist()), RITA,
                new InterviewModel.InviteRequest(id("G"), "ghost", "主题", 10_000L, 100_000L,
                        DURATION), 1_000L);
        InterviewModel.Outcome unreachable = InterviewModel.invite(state, context(journalist()),
                RITA, new InterviewModel.InviteRequest(id("T"), TOM, "主题", 10_000L, 100_000L,
                        DURATION), 1_000L);
        assertEquals(InterviewModel.REASON_UNREACHABLE, ghost.reason());
        assertEquals(InterviewModel.REASON_UNREACHABLE, unreachable.reason());
        assertSame(state, ghost.state());
    }

    @Test
    void requestIdScopeIsEnforced() {
        InterviewModel.State state = invite(opened(), "R1", 1_000L);
        InterviewModel.Context tomContext = new InterviewModel.Context(journalist(),
                Map.of(TOM, Set.of(RITA, SAM)), Map.of());
        InterviewModel.Outcome guessed = InterviewModel.invite(state, tomContext, TOM,
                new InterviewModel.InviteRequest(id("R1"), SAM, "主题", 100_000L, 200_000L,
                        DURATION), 1_000L);
        InterviewModel.Outcome guessedMissing = InterviewModel.invite(state, tomContext, TOM,
                new InterviewModel.InviteRequest(InterviewModel.requestId(RITA, "nope"), SAM,
                        "主题", 100_000L, 200_000L, DURATION), 1_000L);
        assertEquals(InterviewModel.REASON_ID_SCOPE, guessed.reason());
        assertEquals(guessed.reason(), guessedMissing.reason());
        assertSame(state, guessed.state());

        InterviewModel.Outcome own = InterviewModel.invite(state, tomContext, TOM,
                new InterviewModel.InviteRequest(InterviewModel.requestId(TOM, "R1"), SAM, "主题",
                        100_000L, 200_000L, DURATION), 1_000L);
        assertFalse(own.accepted(), "汤姆没有采访授权");
        assertEquals(InterviewModel.REASON_NOT_AUTHORIZED, own.reason());
    }

    @Test
    void nonParticipantSeesNothingAndCannotAct() {
        InterviewModel.State state = invite(opened(), "R1", 1_000L);
        InterviewModel.Context tomContext = new InterviewModel.Context(journalist(),
                Map.of(TOM, Set.of(RITA)), Map.of());
        assertTrue(InterviewModel.inbox(state, tomContext, TOM, 1_000L).isEmpty());
        assertTrue(InterviewModel.sessions(state, tomContext, TOM, 1_000L).isEmpty());
        assertEquals(InterviewModel.REASON_UNKNOWN, InterviewModel.respond(state, tomContext, TOM,
                id("R1"), InterviewModel.Decision.accept(20_000L), 1_000L).reason());
        assertEquals(InterviewModel.REASON_UNKNOWN, InterviewModel.cancel(state, tomContext, TOM,
                id("R1"), 1_000L).reason());
    }

    @Test
    void retryIsIdempotentAcrossStatesAndOnlyOwnerSeesIdConflict() {
        InterviewModel.Context context = context(journalist());
        InterviewModel.Outcome first = InterviewModel.invite(opened(), context, RITA,
                request("R1", 10_000L, 100_000L, DURATION), 1_000L);
        assertTrue(first.accepted());
        InterviewModel.Outcome retry = InterviewModel.invite(first.state(), context, RITA,
                request("R1", 10_000L, 100_000L, DURATION), 5_000L);
        assertTrue(retry.accepted());
        assertSame(first.state(), retry.state());

        InterviewModel.Outcome accepted = InterviewModel.respond(first.state(), context, SAM,
                id("R1"), InterviewModel.Decision.accept(20_000L), 1_000L);
        InterviewModel.Outcome afterAccept = InterviewModel.invite(accepted.state(), context, RITA,
                request("R1", 10_000L, 100_000L, DURATION), 30_000L);
        assertTrue(afterAccept.accepted(), "接受后原参数重试仍幂等");
        assertSame(accepted.state(), afterAccept.state());

        InterviewModel.Outcome changed = InterviewModel.invite(accepted.state(), context, RITA,
                request("R1", 10_000L, 100_000L, 20_000L), 30_000L);
        assertFalse(changed.accepted());
        assertEquals(InterviewModel.REASON_ID_TAKEN, changed.reason());
        assertSame(accepted.state(), changed.state());
    }

    @Test
    void actionsCannotGoBackBeforeCreation() {
        InterviewModel.State state = invite(opened(), "R1", 1_000L);
        assertEquals(InterviewModel.REASON_STATE, InterviewModel.respond(state,
                context(journalist()), SAM, id("R1"), InterviewModel.Decision.accept(20_000L),
                0L).reason());
        assertEquals(InterviewModel.REASON_STATE, InterviewModel.cancel(state,
                context(journalist()), RITA, id("R1"), 0L).reason());
        assertEquals(InterviewModel.REASON_STATE, InterviewModel.start(state,
                context(journalist()), RITA, id("R1"), 0L).reason());
    }

    @Test
    void zeroTimestampIsAValidFact() {
        InterviewModel.Outcome created = InterviewModel.invite(opened(), context(journalist()),
                RITA, request("Z", 0L, 500L, 200L), 0L);
        assertTrue(created.accepted(), created.reason());
        assertEquals(0L, created.state().invites().get(0).createdAtMillis());
        InterviewModel.State accepted = InterviewModel.respond(created.state(),
                context(journalist()), SAM, id("Z"), InterviewModel.Decision.accept(0L), 0L).state();
        assertEquals(0L, accepted.invites().get(0).startMillis());
        InterviewModel.State active = InterviewModel.start(accepted, context(journalist()), RITA,
                id("Z"), 0L).state();
        assertTrue(active.sessions().get(0).open());
        InterviewModel.State ended = InterviewModel.end(active, context(journalist()), SAM,
                sessionOf("Z"), 0L).state();
        assertEquals(0L, ended.sessions().get(0).endedAtMillis());
        assertEquals(InterviewModel.CloseKind.EARLY, ended.sessions().get(0).close());
        assertEquals(InterviewModel.Status.ENDED_EARLY, ended.invites().get(0).status());
    }

    @Test
    void missedBookingTerminatesWithoutSession() {
        InterviewModel.State state = acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L,
                1_000L);
        InterviewModel.Outcome missed = InterviewModel.expire(state, 20_001L);
        InterviewModel.Invite invite = missed.state().invites().get(0);
        assertEquals(InterviewModel.Status.MISSED, invite.status());
        assertEquals(20_001L, invite.transitionAtMillis());
        assertTrue(missed.state().sessions().isEmpty());
        assertEquals(InterviewModel.REASON_STATE, InterviewModel.start(missed.state(),
                context(journalist()), RITA, id("R1"), 20_001L).reason());
    }

    @Test
    void lateEndClampsToScheduledBoundary() {
        InterviewModel.State state = startedAt(
                acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L, 1_000L), "R1", 20_000L);
        long scheduledEnd = 20_000L + DURATION;
        InterviewModel.Outcome ended = InterviewModel.end(state, context(journalist()), SAM,
                sessionOf("R1"), 90_000L);
        assertTrue(ended.accepted(), ended.reason());
        assertEquals(scheduledEnd, ended.state().sessions().get(0).endedAtMillis());
        assertEquals(InterviewModel.CloseKind.COMPLETED, ended.state().sessions().get(0).close());
        assertEquals(InterviewModel.Status.COMPLETED, ended.state().invites().get(0).status());
    }

    @Test
    void earlyEndReleasesRemainingIntervalOnly() {
        InterviewModel.State state = startedAt(
                acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L, 1_000L), "R1", 20_000L);
        state = InterviewModel.end(state, context(journalist()), SAM, sessionOf("R1"), 25_000L)
                .state();
        InterviewModel.Outcome next = InterviewModel.invite(state, context(journalist()), RITA,
                new InterviewModel.InviteRequest(id("R2"), SAM, "下一场", 26_000L, 90_000L, 10_000L),
                25_000L);
        assertTrue(next.accepted(), next.reason());
        InterviewModel.Outcome second = InterviewModel.respond(next.state(),
                context(journalist()), SAM, id("R2"),
                InterviewModel.Decision.accept(26_000L), 25_000L);
        assertTrue(second.accepted(), second.reason());
        assertEquals(1, second.state().sessions().size(), "提前结束仍保留会话事实");
        assertEquals(25_000L, second.state().sessions().get(0).endedAtMillis());
    }

    @Test
    void noSpeechOutsideSessionWindow() {
        InterviewModel.State state = acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L,
                1_000L);
        assertFalse(InterviewModel.canSpeak(state, context(journalist()), SAM, sessionOf("R1"),
                19_999L).allowed(), "未开始的会话不能发言");

        state = startedAt(state, "R1", 20_000L);
        assertFalse(InterviewModel.canSpeak(state, context(journalist()), SAM, sessionOf("R1"),
                20_000L + DURATION).allowed(), "到预约终点不能再发言");
    }

    @Test
    void windowAndDurationAreValidated() {
        InterviewModel.State state = opened();
        InterviewModel.Context context = context(journalist());
        assertEquals(InterviewModel.REASON_WINDOW, InterviewModel.invite(state, context, RITA,
                request("past", 1_000L, 100_000L, DURATION), 5_000L).reason());
        assertEquals(InterviewModel.REASON_WINDOW, InterviewModel.invite(state, context, RITA,
                request("tight", 10_000L, 20_000L, DURATION), 5_000L).reason());
        assertEquals(InterviewModel.REASON_DURATION, InterviewModel.invite(state, context, RITA,
                new InterviewModel.InviteRequest(id("long"), SAM, "主题", 10_000L, 10_000_000L,
                        3_600_001L), 5_000L).reason());
        assertThrows(IllegalArgumentException.class, () -> new InterviewModel.InviteRequest(
                id("zero"), SAM, "主题", 10_000L, 100_000L, 0L));

        InterviewModel.Outcome accepted = InterviewModel.invite(state, context, RITA,
                request("R1", 10_000L, 100_000L, DURATION), 5_000L);
        assertTrue(accepted.accepted(), accepted.reason());
        assertEquals(InterviewModel.REASON_WINDOW, InterviewModel.respond(accepted.state(), context,
                SAM, id("R1"), InterviewModel.Decision.accept(1_000L), 5_000L).reason());
        assertEquals(InterviewModel.REASON_WINDOW, InterviewModel.respond(accepted.state(), context,
                SAM, id("R1"), InterviewModel.Decision.accept(100_000L), 5_000L).reason());
    }

    @Test
    void timeOverflowIsRejectedOnWindowTtlAndMinLead() {
        InterviewModel.Outcome ttlOverflow = InterviewModel.invite(
                InterviewModel.open(new InterviewModel.Policy(MEDIA, Long.MAX_VALUE, 0L,
                        3_600_000L), registry()),
                context(journalist()), RITA, request("ttl", 10_000L, 100_000L, DURATION), 1_000L);
        assertEquals(InterviewModel.REASON_TIME_OVERFLOW, ttlOverflow.reason());

        InterviewModel.State leaky = InterviewModel.open(
                new InterviewModel.Policy(MEDIA, 600_000L, Long.MAX_VALUE, 3_600_000L), registry());
        InterviewModel.Outcome leadOverflow = InterviewModel.invite(leaky, context(journalist()),
                RITA, request("lead", 10_000L, 100_000L, DURATION), 1_000L);
        assertEquals(InterviewModel.REASON_TIME_OVERFLOW, leadOverflow.reason());
        assertSame(leaky, leadOverflow.state());
    }

    @Test
    void plainPersonAcceptsWithoutMediaAppointment() {
        InterviewModel.State state = invite(opened(), "R1", 1_000L);
        RoleAccess.State onlyJournalist = journalist();
        InterviewModel.Outcome accepted = InterviewModel.respond(state, context(onlyJournalist),
                SAM, id("R1"), InterviewModel.Decision.accept(20_000L), 1_000L);
        assertTrue(accepted.accepted(), accepted.reason());
        assertFalse(onlyJournalist.appointments().stream()
                .anyMatch(appointment -> appointment.personPartyId().equals(SAM)));
    }

    @Test
    void jsonRoundTripPreservesStateAndRejectsSemanticTampering() throws Exception {
        InterviewModel.State state = startedAt(
                acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L, 1_000L), "R1", 20_000L);
        String json = InterviewModel.toJson(state);
        assertEquals(state, InterviewModel.fromJson(json));

        assertRejectedJson(json, root -> invite(root).put("endedAtMillis", 25_000));
        assertRejectedJson(json, root -> invite(root).put("requestId", InterviewModel
                .requestId("sam", "R1")));
        assertRejectedJson(json, root -> session(root).put("sessionId", "SESSION-unrelated"));

        InterviewModel.State expired = InterviewModel.expire(invite(opened(), "R1", 1_000L), 200_000L)
                .state();
        assertRejectedJson(InterviewModel.toJson(expired),
                root -> invite(root).put("transitionAtMillis", 20L));

        InterviewModel.State missed = InterviewModel.expire(
                acceptedAt(invite(opened(), "R1", 1_000L), "R1", 20_000L, 1_000L), 20_001L).state();
        assertRejectedJson(InterviewModel.toJson(missed),
                root -> invite(root).put("transitionAtMillis", 50L));

        InterviewModel.State completed = InterviewModel.end(state, context(journalist()), SAM,
                sessionOf("R1"), 90_000L).state();
        assertRejectedJson(InterviewModel.toJson(completed),
                root -> session(root).put("endedAtMillis", 60_000L));
    }

    private static void assertRejectedJson(String json,
                                           java.util.function.Consumer<ObjectNode> change)
            throws Exception {
        ObjectNode root = (ObjectNode) new ObjectMapper().readTree(json);
        change.accept(root);
        assertThrows(IllegalArgumentException.class, () -> InterviewModel.fromJson(root.toString()));
    }

    private static ObjectNode invite(ObjectNode root) {
        return (ObjectNode) root.get("invites").get(0);
    }

    private static ObjectNode session(ObjectNode root) {
        return (ObjectNode) root.get("sessions").get(0);
    }

    @Test
    void constructorRejectsInconsistentRelationsOverlapsAndOrdering() {
        InterviewModel.Policy policy = policy();
        String first = id("R1");
        String second = InterviewModel.requestId(SAM, "R2");
        InterviewModel.Invite accepted = new InterviewModel.Invite(first, RITA, SAM, MEDIA, "主题",
                10_000L, 100_000L, DURATION, InterviewModel.Status.ACCEPTED, 1_000L, 100_000L,
                2_000L, 20_000L, -1L, InterviewModel.REASON_OK);
        InterviewModel.Invite overlapping = new InterviewModel.Invite(second, SAM, TOM, MEDIA,
                "主题", 10_000L, 100_000L, DURATION, InterviewModel.Status.ACCEPTED, 1_000L, 100_000L,
                2_000L, 25_000L, -1L, InterviewModel.REASON_OK);
        assertThrows(IllegalArgumentException.class, () -> new InterviewModel.State(
                List.of(accepted, overlapping), List.of(), List.of(RITA, SAM, TOM), policy));

        assertThrows(IllegalArgumentException.class, () -> new InterviewModel.State(
                List.of(accepted),
                List.of(new InterviewModel.Session("SESSION-x", "R404", 20_000L, -1L, null)),
                List.of(RITA, SAM), policy));

        assertThrows(IllegalArgumentException.class, () -> new InterviewModel.State(
                List.of(accepted),
                List.of(new InterviewModel.Session(first, first, 20_000L, -1L,
                        InterviewModel.CloseKind.EARLY)),
                List.of(RITA, SAM), policy));

        assertThrows(IllegalArgumentException.class, () -> new InterviewModel.State(
                List.of(new InterviewModel.Invite(first, RITA, SAM, MEDIA, "主题", 10_000L, 100_000L,
                        DURATION, InterviewModel.Status.ACCEPTED, 1_000L, 100_000L, 20_000L,
                        10_000L, -1L, InterviewModel.REASON_OK)),
                List.of(), List.of(RITA, SAM), policy));

        assertThrows(IllegalArgumentException.class, () -> new InterviewModel.State(
                List.of(new InterviewModel.Invite(id("R8"), RITA, RITA, MEDIA, "主题", 10_000L,
                        100_000L, DURATION, InterviewModel.Status.PROPOSED, 1_000L, 100_000L,
                        -1L, -1L, -1L, InterviewModel.REASON_OK)),
                List.of(), List.of(RITA), policy));

        assertThrows(IllegalArgumentException.class, () -> new InterviewModel.State(
                List.of(new InterviewModel.Invite(InterviewModel.requestId(SAM, "x"), RITA, SAM,
                        MEDIA, "主题", 10_000L, 100_000L, DURATION, InterviewModel.Status.PROPOSED,
                        1_000L, 100_000L, -1L, -1L, -1L, InterviewModel.REASON_OK)),
                List.of(), List.of(RITA, SAM), policy));
    }

    @Test
    void auditIsAdminOnlyViewOfEverything() {
        InterviewModel.State state = invite(opened(), "R1", 1_000L);
        assertEquals(1, InterviewModel.audit(state).size());
        assertNotNull(InterviewModel.audit(state).get(0));
    }
}
