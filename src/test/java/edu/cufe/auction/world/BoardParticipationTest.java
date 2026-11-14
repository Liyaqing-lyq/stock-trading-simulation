package edu.cufe.auction.world;

import edu.cufe.auction.world.BoardModel.AppointmentAction;
import edu.cufe.auction.world.BoardModel.AppointmentRequest;
import edu.cufe.auction.world.BoardModel.Charter;
import edu.cufe.auction.world.BoardModel.DividendProposal;
import edu.cufe.auction.world.BoardModel.MeetingRequest;
import edu.cufe.auction.world.BoardModel.MeetingStatus;
import edu.cufe.auction.world.BoardModel.Seat;
import edu.cufe.auction.world.BoardModel.State;
import edu.cufe.auction.world.BoardModel.Topic;
import edu.cufe.auction.world.BoardModel.Vote;
import edu.cufe.auction.world.DividendModel.SchemeRequest;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.PropertyOwner;
import edu.cufe.auction.world.RoleAccess.Role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 异步参与登记：空名单真实到会、任免/分红关联自动回避、时间窗与 JSON 往返。 */
class BoardParticipationTest {

    private static final long MATERIAL_DEADLINE = 100L;
    private static final long OPENS_AT = 200L;
    private static final long CLOSES_AT = 300L;

    @Test
    @DisplayName("空名单召集→三人登记→3 票通过；两人不足延期")
    void emptyListThenThreeActualVotes() {
        State s = appointmentMeeting("m1");
        s = ok(BoardModel.proposeManagerAppointment(s, 10, "m1", "s1", List.of(),
                appointment("a1", "p6")));
        s = attend(s, 10, "m1", "s1", false);
        s = attend(s, 20, "m1", "s2", false);
        s = attend(s, 30, "m1", "s3", false);
        assertEquals(List.of("s1", "s2", "s3"), s.meeting("m1").attendeeSeatIds());
        s = vote(s, "m1", List.of("s1", "s2", "s3"));
        assertEquals(MeetingStatus.ADOPTED, s.meeting("m1").status());

        State two = appointmentMeeting("m2");
        two = ok(BoardModel.proposeManagerAppointment(two, 10, "m2", "s1", List.of(),
                appointment("a2", "p6")));
        two = attend(two, 10, "m2", "s1", false);
        two = attend(two, 20, "m2", "s2", false);
        two = ok(BoardModel.freezeMaterials(two, OPENS_AT, "m2"));
        two = ok(BoardModel.startVoting(two, OPENS_AT, "m2"));
        two = ok(BoardModel.closeVoting(two, CLOSES_AT, "m2"));
        assertEquals(MeetingStatus.ADJOURNED_NO_QUORUM, two.meeting("m2").status());
    }

    @Test
    @DisplayName("任免候选人晚登记自动回避；提案/材料字段保留；自主不回避不能覆盖")
    void candidateSeatLateJoinForcedRecusal() {
        State s = appointmentMeeting("m1");
        AppointmentRequest request = new AppointmentRequest("a1", "p2", "org-company",
                Role.COMPANY_MANAGER, AppointmentAction.APPOINT, 400, 1000L);
        s = ok(BoardModel.proposeManagerAppointment(s, 10, "m1", "s1", List.of(), request));
        s = attend(s, 20, "m1", "s2", false);
        assertTrue(s.meeting("m1").recusedSeatIds().contains("s2"));
        assertEquals(request, s.meeting("m1").appointmentProposal().request());
        s = attend(s, 21, "m1", "s2", false);
        assertTrue(s.meeting("m1").recusedSeatIds().contains("s2"), "重复不得清回避");
        s = attend(s, 22, "m1", "s1", false);
        s = attend(s, 23, "m1", "s3", false);
        s = attend(s, 24, "m1", "s4", false);
        s = vote(s, "m1", List.of("s1", "s3", "s4"));
        assertEquals(MeetingStatus.ADOPTED, s.meeting("m1").status());
        assertEquals(request, s.meeting("m1").appointmentProposal().request());
    }

    @Test
    @DisplayName("任免 related 晚登记自动回避；分红 related 同样")
    void relatedSeatLateJoinForcedRecusal() {
        State s = appointmentMeeting("m1");
        AppointmentRequest request = new AppointmentRequest("a1", "p6", "org-company",
                Role.COMPANY_MANAGER, AppointmentAction.APPOINT, 400, 1000L);
        s = ok(BoardModel.proposeManagerAppointment(s, 10, "m1", "s1", List.of("s3"), request));
        s = attend(s, 20, "m1", "s3", false);
        assertTrue(s.meeting("m1").recusedSeatIds().contains("s3"));

        State dividend = ok(BoardModel.convene(opened(), 0, new MeetingRequest("d1",
                Topic.DIVIDEND_AUTHORIZATION, "s1", MATERIAL_DEADLINE, OPENS_AT, CLOSES_AT,
                List.of(), List.of())));
        SchemeRequest scheme = new SchemeRequest("scheme-1", "SYM", "org-company", "CO",
                new BigDecimal("1.00"), 400L, 500L, 600L);
        dividend = ok(BoardModel.proposeDividendAuthorization(dividend, 10, "d1", "s1",
                List.of("s4"), scheme));
        dividend = attend(dividend, 20, "d1", "s4", false);
        assertTrue(dividend.meeting("d1").recusedSeatIds().contains("s4"));
        assertTrue(dividend.meeting("d1").dividendProposal().relatedSeatIds().contains("s4"));
    }

    @Test
    @DisplayName("时间窗/终态/未知/跨会议：非法登记拒绝且状态不变")
    void illegalAttendanceRejectedWithoutChange() {
        State s = emptyMeeting("m1");
        State before = s;
        assertEquals(BoardModel.REASON_UNKNOWN_MEETING,
                BoardModel.attend(s, 10, "nope", "s1", false).reason());
        assertEquals(BoardModel.REASON_INVALID_ATTENDANCE,
                BoardModel.attend(s, 10, "m1", "ghost", false).reason());
        State past = emptyMeeting("m3", 10L);
        assertEquals(BoardModel.REASON_NOT_DUE,
                BoardModel.attend(past, 9, "m3", "s1", false).reason());
        assertEquals(BoardModel.REASON_NOT_DUE,
                BoardModel.attend(s, OPENS_AT, "m1", "s1", false).reason());
        assertEquals(s, before);
        s = attend(s, 10, "m1", "s1", false);
        State frozen = ok(BoardModel.freezeMaterials(s, OPENS_AT, "m1"));
        assertEquals(BoardModel.REASON_INVALID_STATE,
                BoardModel.attend(frozen, 201, "m1", "s2", false).reason());
        State other = emptyMeeting("m2");
        other = attend(other, 10, "m2", "s3", false);
        assertNull(other.meeting("m1"));
        assertEquals(List.of("s3"), other.meeting("m2").attendeeSeatIds());
    }

    @Test
    @DisplayName("JSON 往返保持名单/回避/任免提案；重复不重复席位")
    void jsonRoundTripAndIdempotentRepeat() {
        State s = appointmentMeeting("m1");
        AppointmentRequest request = new AppointmentRequest("a1", "p2", "org-company",
                Role.COMPANY_MANAGER, AppointmentAction.APPOINT, 400, 1000L);
        s = ok(BoardModel.proposeManagerAppointment(s, 10, "m1", "s1", List.of(), request));
        s = attend(s, 20, "m1", "s2", false);
        s = attend(s, 30, "m1", "s3", false);
        State restored = BoardModel.fromJson(BoardModel.toJson(s));
        assertEquals(s, restored);
        assertEquals(s.meeting("m1").appointmentProposal(), restored.meeting("m1")
                .appointmentProposal());
        assertTrue(restored.meeting("m1").recusedSeatIds().contains("s2"));
        State repeated = attend(restored, 40, "m1", "s2", false);
        assertEquals(restored.meeting("m1").attendeeSeatIds(),
                repeated.meeting("m1").attendeeSeatIds());
        assertTrue(repeated.meeting("m1").recusedSeatIds().contains("s2"));
    }

    @Test
    @DisplayName("自主回避先登记为 true，后续 false 不清除")
    void selfRecusalCannotBeCleared() {
        State s = emptyMeeting("m1");
        s = attend(s, 10, "m1", "s1", true);
        assertTrue(s.meeting("m1").recusedSeatIds().contains("s1"));
        s = attend(s, 20, "m1", "s1", false);
        assertTrue(s.meeting("m1").recusedSeatIds().contains("s1"));
    }

    // ---------------- 夹具 ----------------

    private static AppointmentRequest appointment(String id, String person) {
        return new AppointmentRequest(id, person, "org-company", Role.COMPANY_MANAGER,
                AppointmentAction.APPOINT, 400, 1000L);
    }

    private static State emptyMeeting(String id) {
        return emptyMeeting(id, 0L, Topic.BUDGET);
    }

    private static State emptyMeeting(String id, long createdAt) {
        return emptyMeeting(id, createdAt, Topic.BUDGET);
    }

    private static State appointmentMeeting(String id) {
        return emptyMeeting(id, 0L, Topic.MANAGER_APPOINTMENT);
    }

    private static State emptyMeeting(String id, long createdAt, Topic topic) {
        return ok(BoardModel.convene(opened(), createdAt, new MeetingRequest(id, topic,
                "s1", MATERIAL_DEADLINE, OPENS_AT, CLOSES_AT, List.of(), List.of())));
    }

    private static State attend(State state, long now, String meetingId, String seat,
                                boolean recused) {
        return ok(BoardModel.attend(state, now, meetingId, seat, recused));
    }

    private static State vote(State state, String meetingId, List<String> yes) {
        State s = ok(BoardModel.freezeMaterials(state, OPENS_AT, meetingId));
        s = ok(BoardModel.startVoting(s, OPENS_AT, meetingId));
        for (String seat : yes) {
            s = ok(BoardModel.castVote(s, 210, meetingId, seat, Vote.YES));
        }
        return ok(BoardModel.closeVoting(s, CLOSES_AT, meetingId));
    }

    private static State opened() {
        return BoardModel.open(new OpeningRegistry(
                List.of(new Party("p1", PartyKind.PERSON, "甲"),
                        new Party("p2", PartyKind.PERSON, "乙"),
                        new Party("p3", PartyKind.PERSON, "丙"),
                        new Party("p4", PartyKind.PERSON, "丁"),
                        new Party("p5", PartyKind.PERSON, "戊"),
                        new Party("p6", PartyKind.PERSON, "己"),
                        new Party("org-company", PartyKind.ORGANIZATION, "实验公司")),
                List.of(new PropertyOwner("owner-company", OwnerKind.ORGANIZATION,
                        "org-company")), List.of(), List.of()),
                new Charter("org-company", List.of(
                        new Seat("s1", "p1"), new Seat("s2", "p2"), new Seat("s3", "p3"),
                        new Seat("s4", "p4"), new Seat("s5", "p5")), "s1", 3));
    }

    private static State ok(BoardModel.Outcome outcome) {
        assertTrue(outcome.accepted(), "应接受，实际拒绝：" + outcome.reason());
        return outcome.state();
    }
}
