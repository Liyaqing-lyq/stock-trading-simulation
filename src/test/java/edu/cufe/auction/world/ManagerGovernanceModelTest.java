package edu.cufe.auction.world;

import edu.cufe.auction.world.BoardModel.AppointmentAction;
import edu.cufe.auction.world.BoardModel.AppointmentRequest;
import edu.cufe.auction.world.BoardModel.Charter;
import edu.cufe.auction.world.BoardModel.Meeting;
import edu.cufe.auction.world.BoardModel.MeetingRequest;
import edu.cufe.auction.world.BoardModel.MeetingStatus;
import edu.cufe.auction.world.BoardModel.Seat;
import edu.cufe.auction.world.BoardModel.State;
import edu.cufe.auction.world.BoardModel.Topic;
import edu.cufe.auction.world.BoardModel.Vote;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.PropertyOwner;
import edu.cufe.auction.world.RoleAccess.Role;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 公司经理任免纯核：五席表决、回避、授权绑定、撤销与 JSON 抗篡改。 */
class ManagerGovernanceModelTest {

    private static final long MATERIAL_DEADLINE = 100L;
    private static final long OPENS_AT = 200L;
    private static final long CLOSES_AT = 300L;
    private static final long START = 400L;
    private static final long END = 1000L;

    @Test
    @DisplayName("3 票通过：任命生成可重算授权，真实会议状态可验证")
    void appointmentAdoptedAndVerified() {
        State s = meeting("m1", seats("s1", "s2", "s3"), List.of());
        AppointmentRequest request = appointment("a1", "p6", START, END);
        s = ok(BoardModel.proposeManagerAppointment(s, 10, "m1", "s1", List.of(), request));
        s = ok(BoardModel.freezeMaterials(s, OPENS_AT, "m1"));
        s = ok(BoardModel.startVoting(s, OPENS_AT, "m1"));
        s = ok(BoardModel.castVote(s, 250, "m1", "s1", Vote.YES));
        s = ok(BoardModel.castVote(s, 250, "m1", "s2", Vote.YES));
        s = ok(BoardModel.castVote(s, 250, "m1", "s3", Vote.YES));
        s = ok(BoardModel.closeVoting(s, CLOSES_AT, "m1"));
        assertEquals(MeetingStatus.ADOPTED, s.meeting("m1").status());
        s = ok(BoardModel.executeAdoptedManagerAppointment(s, CLOSES_AT, "m1"));
        Meeting meeting = s.meeting("m1");
        assertEquals(MeetingStatus.IMPLEMENTED, meeting.status());
        assertNotNull(meeting.appointmentResolution());
        assertTrue(BoardModel.verifiesManagerAppointmentAuthorization(s,
                meeting.appointmentResolution().authorizationRef(), request));
        assertEquals("a1", meeting.appointmentProposal().request().appointmentId());
    }

    @Test
    @DisplayName("2 票拒绝；有效到会不足 3 延期；撤销不删旧授权")
    void rejectAndAdjourn() {
        State rejected = meeting("m1", seats("s1", "s2", "s3"), List.of());
        rejected = ok(BoardModel.proposeManagerAppointment(rejected, 10, "m1", "s1", List.of(),
                appointment("a1", "p6", START, END)));
        rejected = vote(rejected, "m1", List.of("s1"), List.of("s2", "s3"));
        assertEquals(MeetingStatus.REJECTED, rejected.meeting("m1").status());
        assertEquals(BoardModel.REASON_NOT_ADOPTED,
                BoardModel.executeAdoptedManagerAppointment(rejected, CLOSES_AT, "m1").reason());

        State adjourned = meeting("m2", seats("s1", "s2"), List.of());
        adjourned = ok(BoardModel.proposeManagerAppointment(adjourned, 10, "m2", "s1", List.of(),
                appointment("a2", "p6", START, END)));
        adjourned = ok(BoardModel.freezeMaterials(adjourned, OPENS_AT, "m2"));
        adjourned = ok(BoardModel.startVoting(adjourned, OPENS_AT, "m2"));
        adjourned = ok(BoardModel.closeVoting(adjourned, CLOSES_AT, "m2"));
        assertEquals(MeetingStatus.ADJOURNED_NO_QUORUM, adjourned.meeting("m2").status());
    }

    @Test
    @DisplayName("候选人占席自动关联：到会不回避拒绝，未到会不强制；回避后可通过")
    void candidateSeatAutoRecusal() {
        State absent = meeting("m1", seats("s1", "s3", "s4"), List.of());
        absent = ok(BoardModel.proposeManagerAppointment(absent, 10, "m1", "s1", List.of(),
                appointment("a1", "p2", START, END)));

        State attending = meeting("m2", seats("s1", "s2", "s3"), List.of());
        State s = attending;
        assertEquals(BoardModel.REASON_INVALID_ATTENDANCE,
                BoardModel.proposeManagerAppointment(s, 10, "m2", "s1", List.of(),
                        appointment("a2", "p2", START, END)).reason());

        State recused = meeting("m3", seats("s1", "s2", "s3", "s4"), List.of("s2"));
        recused = ok(BoardModel.proposeManagerAppointment(recused, 10, "m3", "s1", List.of(),
                appointment("a3", "p2", START, END)));
        recused = ok(BoardModel.freezeMaterials(recused, OPENS_AT, "m3"));
        recused = ok(BoardModel.startVoting(recused, OPENS_AT, "m3"));
        recused = ok(BoardModel.castVote(recused, 250, "m3", "s1", Vote.YES));
        recused = ok(BoardModel.castVote(recused, 250, "m3", "s3", Vote.YES));
        recused = ok(BoardModel.castVote(recused, 250, "m3", "s4", Vote.YES));
        recused = ok(BoardModel.closeVoting(recused, CLOSES_AT, "m3"));
        assertEquals(MeetingStatus.ADOPTED, recused.meeting("m3").status());
    }

    @Test
    @DisplayName("跨公司、基金岗位、非主席、时间倒退/晚授权都拒绝")
    void invalidRequestsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AppointmentRequest("a", "p6", "org-company", Role.PORTFOLIO_MANAGER,
                        AppointmentAction.APPOINT, START, END));
        assertThrows(IllegalArgumentException.class,
                () -> new AppointmentRequest("a", "p6", "org-company", Role.COMPANY_MANAGER,
                        AppointmentAction.APPOINT, START, START));

        State s = meeting("m1", seats("s1", "s2", "s3"), List.of());
        assertEquals(BoardModel.REASON_INVALID_ARGUMENT,
                BoardModel.proposeManagerAppointment(s, 10, "m1", "s1", List.of(),
                        new AppointmentRequest("a1", "p6", "org-other",
                                Role.COMPANY_MANAGER, AppointmentAction.APPOINT,
                                START, END)).reason());
        assertEquals(BoardModel.REASON_NOT_CHAIR,
                BoardModel.convene(s, 0, new MeetingRequest("m2", Topic.MANAGER_APPOINTMENT,
                        "s2", MATERIAL_DEADLINE, OPENS_AT, CLOSES_AT, seats("s1", "s2", "s3"),
                        List.of())).reason());
        assertEquals(BoardModel.REASON_INVALID_TIMELINE,
                BoardModel.proposeManagerAppointment(s, 10, "m1", "s1", List.of(),
                        appointment("a2", "p6", CLOSES_AT - 1, END)).reason());

        State adopted = vote(ok(BoardModel.proposeManagerAppointment(s, 10, "m1", "s1", List.of(),
                appointment("a3", "p6", START, END))), "m1", List.of("s1", "s2", "s3"), List.of());
        assertEquals(BoardModel.REASON_INVALID_TIMELINE,
                BoardModel.executeAdoptedManagerAppointment(adopted, START + 1, "m1").reason());
    }

    @Test
    @DisplayName("同公司同岗位活动提案互斥；撤销不被互斥误伤；完整绑定与重复实施")
    void conflictsAndRevocation() {
        State s = meeting("m1", seats("s1", "s2", "s3"), List.of());
        s = ok(BoardModel.proposeManagerAppointment(s, 10, "m1", "s1", List.of(),
                appointment("a1", "p6", START, END)));
        s = ok(BoardModel.convene(s, 0, request("m2", seats("s1", "s2", "s3"), List.of())));
        assertEquals(BoardModel.REASON_INVALID_STATE,
                BoardModel.proposeManagerAppointment(s, 10, "m2", "s1", List.of(),
                        appointment("a2", "p7", START, END)).reason());

        State implemented = adopt(s, "m1", appointment("a1", "p6", START, END));
        State other = ok(BoardModel.convene(implemented, 0,
                request("m3", seats("s1", "s2", "s3"), List.of())));
        other = ok(BoardModel.proposeManagerAppointment(other, 10, "m3", "s1", List.of(),
                appointment("a2", "p7", START, END)));
        State revoke = ok(BoardModel.convene(other, 0,
                request("m4", seats("s1", "s2", "s3"), List.of())));
        AppointmentRequest revokeRequest = new AppointmentRequest("a1", "p6", "org-company",
                Role.COMPANY_MANAGER, AppointmentAction.REVOKE, 500L, null);
        revoke = ok(BoardModel.proposeManagerAppointment(revoke, 10, "m4", "s1", List.of(),
                revokeRequest));
        revoke = adopt(revoke, "m4", revokeRequest);
        assertTrue(BoardModel.verifiesManagerAppointmentAuthorization(revoke,
                revoke.meeting("m4").appointmentResolution().authorizationRef(), revokeRequest));
        assertEquals(MeetingStatus.IMPLEMENTED, revoke.meeting("m1").status());
        assertNotNull(revoke.meeting("m1").appointmentResolution());
        assertFalse(BoardModel.verifiesManagerAppointmentAuthorization(revoke,
                revoke.meeting("m4").appointmentResolution().authorizationRef(),
                new AppointmentRequest("a1", "p7", "org-company", Role.COMPANY_MANAGER,
                        AppointmentAction.REVOKE, 500L, null)));
        assertEquals(BoardModel.REASON_NOT_ADOPTED,
                BoardModel.executeAdoptedManagerAppointment(revoke, CLOSES_AT, "m4").reason());
    }

    @Test
    @DisplayName("JSON 篡改议题/请求/关联席/签发时间/状态与自洽引用都拒绝")
    void jsonTamperingRejected() {
        State s = meeting("m1", seats("s1", "s2", "s3"), List.of());
        s = adopt(ok(BoardModel.proposeManagerAppointment(s, 10, "m1", "s1", List.of(),
                appointment("a1", "p6", START, END))), "m1",
                appointment("a1", "p6", START, END));
        String json = BoardModel.toJson(s);
        String ref = s.meeting("m1").appointmentResolution().authorizationRef();
        assertThrows(IllegalArgumentException.class,
                () -> BoardModel.fromJson(json.replace("MANAGER_APPOINTMENT", "BUDGET")));
        assertThrows(IllegalArgumentException.class,
                () -> BoardModel.fromJson(json.replace("a1", "a2")));
        assertThrows(IllegalArgumentException.class,
                () -> BoardModel.fromJson(json.replace(ref, ref + "x")));
        assertThrows(IllegalArgumentException.class,
                () -> BoardModel.fromJson(json.replace("\"issuedAtMillis\":300",
                        "\"issuedAtMillis\":299")));
        assertThrows(IllegalArgumentException.class,
                () -> BoardModel.fromJson(json.replace("\"IMPLEMENTED\"", "\"ADOPTED\"")));
    }

    @Test
    @DisplayName("旧 16 参构造与旧 JSON 摘要：不写 appointment 字段")
    void oldMeetingJsonUnchanged() {
        Meeting meeting = new Meeting("b1", Topic.BUDGET, "s1", 0L, MATERIAL_DEADLINE,
                OPENS_AT, CLOSES_AT, MeetingStatus.CONVENED, seats("s1", "s2", "s3"),
                List.of(), List.of(), List.of(), java.util.Map.of(), null, null, null);
        State state = new State(opened().charter(), java.util.Map.of("b1", meeting),
                List.of(), List.of(), List.of());
        String json = BoardModel.toJson(state);
        assertFalse(json.contains("appointmentProposal"));
        assertFalse(json.contains("appointmentResolution"));
        assertEquals(state, BoardModel.fromJson(json));
    }

    @Test
    @DisplayName("APPOINT/REVOKE 在提案、ADOPTED、IMPLEMENTED 三阶段 JSON 往返保持精确状态与授权")
    void appointmentJsonRoundTripAtEachStage() {
        State proposal = meeting("m1", seats("s1", "s2", "s3"), List.of());
        proposal = ok(BoardModel.proposeManagerAppointment(proposal, 10, "m1", "s1",
                List.of(), appointment("a1", "p6", START, END)));
        String proposalJson = BoardModel.toJson(proposal);
        assertFalse(proposalJson.contains("\"appointment\""),
                "派生 isAppointment 不得写成 appointment 字段");
        assertTrue(proposalJson.contains("appointmentProposal"));
        assertEquals(proposal, BoardModel.fromJson(proposalJson));

        State adopted = vote(proposal, "m1", List.of("s1", "s2", "s3"), List.of());
        assertEquals(adopted, BoardModel.fromJson(BoardModel.toJson(adopted)));
        assertEquals(MeetingStatus.ADOPTED, adopted.meeting("m1").status());

        State implemented = ok(BoardModel.executeAdoptedManagerAppointment(adopted, CLOSES_AT,
                "m1"));
        State restored = BoardModel.fromJson(BoardModel.toJson(implemented));
        assertEquals(implemented, restored);
        assertEquals(implemented.meeting("m1").appointmentResolution().authorizationRef(),
                restored.meeting("m1").appointmentResolution().authorizationRef());
        assertEquals(implemented.meeting("m1").appointmentProposal(),
                restored.meeting("m1").appointmentProposal());

        State revokeMeeting = meeting("m2", seats("s1", "s2", "s3"), List.of());
        AppointmentRequest revokeRequest = new AppointmentRequest("a1", "p6", "org-company",
                Role.COMPANY_MANAGER, AppointmentAction.REVOKE, 500L, null);
        revokeMeeting = ok(BoardModel.proposeManagerAppointment(revokeMeeting, 10, "m2", "s1",
                List.of(), revokeRequest));
        assertEquals(revokeMeeting, BoardModel.fromJson(BoardModel.toJson(revokeMeeting)));
        State revokeAdopted = vote(revokeMeeting, "m2", List.of("s1", "s2", "s3"), List.of());
        assertEquals(revokeAdopted, BoardModel.fromJson(BoardModel.toJson(revokeAdopted)));
        State revokeImplemented = ok(BoardModel.executeAdoptedManagerAppointment(revokeAdopted,
                CLOSES_AT, "m2"));
        State revokeRestored = BoardModel.fromJson(BoardModel.toJson(revokeImplemented));
        assertEquals(revokeImplemented, revokeRestored);
        assertTrue(BoardModel.verifiesManagerAppointmentAuthorization(revokeRestored,
                revokeRestored.meeting("m2").appointmentResolution().authorizationRef(),
                revokeRequest));
    }

    // ---------------- 夹具 ----------------

    private static State opened() {
        return BoardModel.open(new OpeningRegistry(
                List.of(new Party("p1", PartyKind.PERSON, "甲"),
                        new Party("p2", PartyKind.PERSON, "乙"),
                        new Party("p3", PartyKind.PERSON, "丙"),
                        new Party("p4", PartyKind.PERSON, "丁"),
                        new Party("p5", PartyKind.PERSON, "戊"),
                        new Party("p6", PartyKind.PERSON, "己"),
                        new Party("p7", PartyKind.PERSON, "庚"),
                        new Party("org-company", PartyKind.ORGANIZATION, "实验公司"),
                        new Party("org-other", PartyKind.ORGANIZATION, "另一组织")),
                List.of(new PropertyOwner("owner-company", OwnerKind.ORGANIZATION,
                        "org-company")), List.of(), List.of()),
                new Charter("org-company", List.of(
                        new Seat("s1", "p1"), new Seat("s2", "p2"), new Seat("s3", "p3"),
                        new Seat("s4", "p4"), new Seat("s5", "p5")), "s1", 3));
    }

    private static State meeting(String id, List<String> attendees, List<String> recused) {
        return ok(BoardModel.convene(opened(), 0, request(id, attendees, recused)));
    }

    private static MeetingRequest request(String id, List<String> attendees,
                                          List<String> recused) {
        return new MeetingRequest(id, Topic.MANAGER_APPOINTMENT, "s1", MATERIAL_DEADLINE,
                OPENS_AT, CLOSES_AT, attendees, recused);
    }

    private static List<String> seats(String... ids) {
        return List.of(ids);
    }

    private static AppointmentRequest appointment(String id, String person, long start, long end) {
        return new AppointmentRequest(id, person, "org-company", Role.COMPANY_MANAGER,
                AppointmentAction.APPOINT, start, end);
    }

    private static State adopt(State state, String meetingId, AppointmentRequest request) {
        State s = ok(BoardModel.freezeMaterials(state, OPENS_AT, meetingId));
        s = ok(BoardModel.startVoting(s, OPENS_AT, meetingId));
        List<String> eligible = s.meeting(meetingId).eligibleVoters();
        for (String seat : eligible) {
            s = ok(BoardModel.castVote(s, 250, meetingId, seat,
                    eligible.indexOf(seat) < 3 ? Vote.YES : Vote.NO));
        }
        s = ok(BoardModel.closeVoting(s, CLOSES_AT, meetingId));
        return ok(BoardModel.executeAdoptedManagerAppointment(s, CLOSES_AT, meetingId));
    }

    private static State vote(State state, String meetingId, List<String> yes,
                              List<String> no) {
        State s = ok(BoardModel.freezeMaterials(state, OPENS_AT, meetingId));
        s = ok(BoardModel.startVoting(s, OPENS_AT, meetingId));
        for (String seat : yes) {
            s = ok(BoardModel.castVote(s, 250, meetingId, seat, Vote.YES));
        }
        for (String seat : no) {
            s = ok(BoardModel.castVote(s, 250, meetingId, seat, Vote.NO));
        }
        return ok(BoardModel.closeVoting(s, CLOSES_AT, meetingId));
    }

    private static State ok(BoardModel.Outcome outcome) {
        assertTrue(outcome.accepted(), "应接受，实际拒绝：" + outcome.reason());
        return outcome.state();
    }
}
