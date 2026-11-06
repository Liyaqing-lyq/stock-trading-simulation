package edu.cufe.auction.world;

import edu.cufe.auction.world.BoardModel.BudgetCategory;
import edu.cufe.auction.world.BoardModel.BudgetLine;
import edu.cufe.auction.world.BoardModel.Charter;
import edu.cufe.auction.world.BoardModel.Dimension;
import edu.cufe.auction.world.BoardModel.EvidenceMetric;
import edu.cufe.auction.world.BoardModel.MaterialRef;
import edu.cufe.auction.world.BoardModel.Meeting;
import edu.cufe.auction.world.BoardModel.MeetingRequest;
import edu.cufe.auction.world.BoardModel.ReviewStatus;
import edu.cufe.auction.world.BoardModel.Seat;
import edu.cufe.auction.world.BoardModel.State;
import edu.cufe.auction.world.BoardModel.Topic;
import edu.cufe.auction.world.BoardModel.Vote;
import edu.cufe.auction.world.OpeningRegistry.Party;
import edu.cufe.auction.world.OpeningRegistry.PartyKind;
import edu.cufe.auction.world.OpeningRegistry.PropertyOwner;
import edu.cufe.auction.world.OpeningRegistry.OwnerKind;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 董事会纯核测试：章程、表决、预算实施、考核完整性与 JSON 可复现。
 *
 * <p>只验证纯核规则，不涉及世界运行时、角色授权、分红、融资、任免或真实法律。</p>
 */
class BoardModelTest {

    private static final long MATERIAL_DEADLINE = 100L;
    private static final long OPENS_AT = 200L;
    private static final long CLOSES_AT = 300L;

    // ---------- 夹具 ----------

    private static OpeningRegistry registry() {
        return new OpeningRegistry(
                List.of(
                        new Party("p1", PartyKind.PERSON, "甲"),
                        new Party("p2", PartyKind.PERSON, "乙"),
                        new Party("p3", PartyKind.PERSON, "丙"),
                        new Party("p4", PartyKind.PERSON, "丁"),
                        new Party("p5", PartyKind.PERSON, "戊"),
                        new Party("p6", PartyKind.PERSON, "己"),
                        new Party("org-company", PartyKind.ORGANIZATION, "实验公司"),
                        new Party("org-other", PartyKind.ORGANIZATION, "另一组织")),
                List.of(new PropertyOwner("owner-company", OwnerKind.ORGANIZATION, "org-company")),
                List.of(),
                List.of());
    }

    private static List<Seat> seats() {
        return List.of(
                new Seat("s1", "p1"),
                new Seat("s2", "p2"),
                new Seat("s3", "p3"),
                new Seat("s4", "p4"),
                new Seat("s5", "p5"));
    }

    private static Charter charter() {
        return new Charter("org-company", seats(), "s1", 3);
    }

    private static State opened() {
        return BoardModel.open(registry(), charter());
    }

    private static State ok(BoardModel.Outcome outcome) {
        assertTrue(outcome.accepted(), "应接受，实际拒绝：" + outcome.reason());
        return outcome.state();
    }

    private static BoardModel.Outcome rejected(BoardModel.Outcome outcome, String reason) {
        assertFalse(outcome.accepted(), "应拒绝，实际接受：" + outcome.reason());
        assertEquals(reason, outcome.reason());
        return outcome;
    }

    private static List<String> allSeats() {
        return List.of("s1", "s2", "s3", "s4", "s5");
    }

    private static MeetingRequest meeting(String id, Topic topic, List<String> attendees,
                                          List<String> recused) {
        return new MeetingRequest(id, topic, "s1", MATERIAL_DEADLINE, OPENS_AT, CLOSES_AT,
                attendees, recused);
    }

    /** 开会 → 材料/预算请求 → 材料冻结 → 进入表决。 */
    private static State inVoting(State base, String id, Topic topic, List<BudgetLine> lines,
                                  List<String> attendees, List<String> recused) {
        State state = ok(BoardModel.convene(base, 0L, meeting(id, topic, attendees, recused)));
        if (topic == Topic.BUDGET) {
            state = ok(BoardModel.proposeBudget(state, 50L, id, lines));
        } else {
            state = ok(BoardModel.addMaterial(state, 50L, id,
                    new MaterialRef("mat-1", "src-ledger", 1L, 40L)));
        }
        state = ok(BoardModel.freezeMaterials(state, OPENS_AT, id));
        return ok(BoardModel.startVoting(state, OPENS_AT, id));
    }

    private static State readyBudget(String id, String cap) {
        return inVoting(opened(), id, Topic.BUDGET,
                List.of(new BudgetLine(0L, BudgetCategory.PROCUREMENT, new BigDecimal(cap))),
                allSeats(), List.of());
    }

    private static State implementBudget(String id, String cap) {
        State state = readyBudget(id, cap);
        state = ok(BoardModel.castVote(state, 210L, id, "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, id, "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 212L, id, "s3", Vote.YES));
        state = ok(BoardModel.closeVoting(state, CLOSES_AT, id));
        return ok(BoardModel.executeAdoptedResolution(state, CLOSES_AT, id));
    }

    // ---------- 第 1 组：开局章程 ----------

    @Test
    void opensWithFiveBoundSeatsAndChair() {
        State state = opened();
        assertEquals(5, state.charter().seats().size());
        assertEquals("s1", state.charter().chairSeatId());
        assertEquals(3, state.charter().quorum());
        assertTrue(state.meetings().isEmpty());
        assertTrue(state.budgets().isEmpty());
    }

    @Test
    void rejectsCompanyThatIsNotRegisteredOrganization() {
        Charter charter = new Charter("nobody", seats(), "s1", 3);
        assertThrows(IllegalArgumentException.class, () -> BoardModel.open(registry(), charter));
    }

    @Test
    void rejectsCompanyIdentityUsedThroughPropertyOwnerId() {
        Charter charter = new Charter("owner-company", seats(), "s1", 3);
        assertThrows(IllegalArgumentException.class, () -> BoardModel.open(registry(), charter));
    }

    @Test
    void rejectsSeatBoundToOrganization() {
        List<Seat> seats = List.of(
                new Seat("s1", "p1"), new Seat("s2", "p2"), new Seat("s3", "p3"),
                new Seat("s4", "p4"), new Seat("s5", "org-other"));
        Charter charter = new Charter("org-company", seats, "s1", 3);
        assertThrows(IllegalArgumentException.class, () -> BoardModel.open(registry(), charter));
    }

    @Test
    void rejectsDuplicatePersonOrDuplicateSeat() {
        List<Seat> duplicatePerson = List.of(
                new Seat("s1", "p1"), new Seat("s2", "p2"), new Seat("s3", "p3"),
                new Seat("s4", "p4"), new Seat("s5", "p4"));
        assertThrows(IllegalArgumentException.class, () -> BoardModel.open(registry(),
                new Charter("org-company", duplicatePerson, "s1", 3)));

        List<Seat> duplicateSeat = List.of(
                new Seat("s1", "p1"), new Seat("s1", "p2"), new Seat("s3", "p3"),
                new Seat("s4", "p4"), new Seat("s5", "p5"));
        assertThrows(IllegalArgumentException.class, () -> BoardModel.open(registry(),
                new Charter("org-company", duplicateSeat, "s1", 3)));
    }

    @Test
    void rejectsChairOutsideSeatsAndWrongSeatCount() {
        assertThrows(IllegalArgumentException.class, () -> BoardModel.open(registry(),
                new Charter("org-company", seats(), "s9", 3)));
        assertThrows(IllegalArgumentException.class, () -> BoardModel.open(registry(),
                new Charter("org-company", seats().subList(0, 4), "s1", 3)));
    }

    @Test
    void rejectsQuorumOtherThanThree() {
        assertThrows(IllegalArgumentException.class, () -> BoardModel.open(registry(),
                new Charter("org-company", seats(), "s1", 1)));
        assertThrows(IllegalArgumentException.class, () -> BoardModel.open(registry(),
                new Charter("org-company", seats(), "s1", 4)));
    }

    // ---------- 第 2 组：表决 ----------

    @Test
    void ordinaryBudgetWithThreeYesIsAdopted() {
        State state = readyBudget("m1", "600.00");
        state = ok(BoardModel.castVote(state, 210L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 212L, "m1", "s3", Vote.YES));
        state = ok(BoardModel.closeVoting(state, CLOSES_AT, "m1"));
        assertEquals(BoardModel.MeetingStatus.ADOPTED, state.meetings().get("m1").status());
        assertTrue(state.budgets().isEmpty(), "通过决议本身不得改动预算");
    }

    @Test
    void ordinaryBudgetWithTwoYesIsRejected() {
        State state = readyBudget("m1", "600.00");
        state = ok(BoardModel.castVote(state, 210L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 212L, "m1", "s3", Vote.NO));
        state = ok(BoardModel.closeVoting(state, CLOSES_AT, "m1"));
        assertEquals(BoardModel.MeetingStatus.REJECTED, state.meetings().get("m1").status());
    }

    @Test
    void specialBudgetWithThreeYesIsRejectedAndFourYesAdopted() {
        List<BudgetLine> lines = List.of(
                new BudgetLine(0L, BudgetCategory.PROCUREMENT, new BigDecimal("900.00")),
                new BudgetLine(0L, BudgetCategory.PRODUCTION_LABOR, new BigDecimal("200.00")));

        State three = inVoting(opened(), "m1", Topic.BUDGET, lines, allSeats(), List.of());
        three = ok(BoardModel.castVote(three, 210L, "m1", "s1", Vote.YES));
        three = ok(BoardModel.castVote(three, 211L, "m1", "s2", Vote.YES));
        three = ok(BoardModel.castVote(three, 212L, "m1", "s3", Vote.YES));
        three = ok(BoardModel.closeVoting(three, CLOSES_AT, "m1"));
        assertEquals(BoardModel.MeetingStatus.REJECTED, three.meetings().get("m1").status());

        State four = inVoting(opened(), "m1", Topic.BUDGET, lines, allSeats(), List.of());
        four = ok(BoardModel.castVote(four, 210L, "m1", "s1", Vote.YES));
        four = ok(BoardModel.castVote(four, 211L, "m1", "s2", Vote.YES));
        four = ok(BoardModel.castVote(four, 212L, "m1", "s3", Vote.YES));
        four = ok(BoardModel.castVote(four, 213L, "m1", "s4", Vote.YES));
        four = ok(BoardModel.closeVoting(four, CLOSES_AT, "m1"));
        assertEquals(BoardModel.MeetingStatus.ADOPTED, four.meetings().get("m1").status());
    }

    @Test
    void twoAttendeesAdjournEvenIfBothVoteYes() {
        State state = inVoting(opened(), "m1", Topic.BUDGET,
                List.of(new BudgetLine(0L, BudgetCategory.PROCUREMENT, new BigDecimal("600.00"))),
                List.of("s1", "s2"), List.of());
        state = ok(BoardModel.castVote(state, 210L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.closeVoting(state, CLOSES_AT, "m1"));
        assertEquals(BoardModel.MeetingStatus.ADJOURNED_NO_QUORUM,
                state.meetings().get("m1").status());
        assertTrue(state.budgets().isEmpty());
    }

    @Test
    void recusalCannotLowerQuorumToOne() {
        // 3 人到会、其中 1 人回避 → 有效 2 人，低于固定法定人数 3 → 延期，不能按剩余席位通过
        State state = inVoting(opened(), "m1", Topic.BUDGET,
                List.of(new BudgetLine(0L, BudgetCategory.PROCUREMENT, new BigDecimal("600.00"))),
                List.of("s1", "s2", "s3"), List.of("s3"));
        state = ok(BoardModel.castVote(state, 210L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, "m1", "s2", Vote.YES));
        BoardModel.Outcome recused = BoardModel.castVote(state, 212L, "m1", "s3", Vote.YES);
        rejected(recused, BoardModel.REASON_NOT_ELIGIBLE_VOTER);
        state = ok(BoardModel.closeVoting(state, CLOSES_AT, "m1"));
        assertEquals(BoardModel.MeetingStatus.ADJOURNED_NO_QUORUM,
                state.meetings().get("m1").status());
    }

    @Test
    void recusedSeatStillCountsAgainstVotingOutcomeButNotQuorum() {
        // 4 人到会、1 人回避 → 有效 3 人达到法定人数；回避者不计票
        State state = inVoting(opened(), "m1", Topic.BUDGET,
                List.of(new BudgetLine(0L, BudgetCategory.PROCUREMENT, new BigDecimal("600.00"))),
                List.of("s1", "s2", "s3", "s4"), List.of("s4"));
        state = ok(BoardModel.castVote(state, 210L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 212L, "m1", "s3", Vote.YES));
        state = ok(BoardModel.closeVoting(state, CLOSES_AT, "m1"));
        assertEquals(BoardModel.MeetingStatus.ADOPTED, state.meetings().get("m1").status());
    }

    @Test
    void onlyAttendingNonRecusedSeatsMayVote() {
        State state = inVoting(opened(), "m1", Topic.BUDGET,
                List.of(new BudgetLine(0L, BudgetCategory.PROCUREMENT, new BigDecimal("600.00"))),
                List.of("s1", "s2", "s3", "s4"), List.of("s4"));
        rejected(BoardModel.castVote(state, 210L, "m1", "s5", Vote.YES),
                BoardModel.REASON_NOT_ELIGIBLE_VOTER);
        rejected(BoardModel.castVote(state, 210L, "m1", "s4", Vote.YES),
                BoardModel.REASON_NOT_ELIGIBLE_VOTER);
        rejected(BoardModel.castVote(state, 210L, "m1", "nobody", Vote.YES),
                BoardModel.REASON_NOT_ELIGIBLE_VOTER);
    }

    @Test
    void duplicateVoteDoesNotOverwriteOriginal() {
        State state = readyBudget("m1", "600.00");
        state = ok(BoardModel.castVote(state, 210L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 212L, "m1", "s3", Vote.YES));
        BoardModel.Outcome duplicate = BoardModel.castVote(state, 213L, "m1", "s1", Vote.NO);
        rejected(duplicate, BoardModel.REASON_ALREADY_VOTED);
        assertEquals(3, state.meetings().get("m1").ballots().size());
        assertEquals(Vote.YES, state.meetings().get("m1").ballots().get("s1").vote());
        state = ok(BoardModel.closeVoting(state, CLOSES_AT, "m1"));
        assertEquals(BoardModel.MeetingStatus.ADOPTED, state.meetings().get("m1").status());
    }

    @Test
    void noAndAbstainBallotsAreKept() {
        State state = readyBudget("m1", "600.00");
        state = ok(BoardModel.castVote(state, 210L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 212L, "m1", "s3", Vote.YES));
        state = ok(BoardModel.castVote(state, 213L, "m1", "s4", Vote.NO));
        state = ok(BoardModel.castVote(state, 214L, "m1", "s5", Vote.ABSTAIN));
        Map<String, BoardModel.Ballot> ballots = state.meetings().get("m1").ballots();
        assertEquals(5, ballots.size());
        assertEquals(Vote.NO, ballots.get("s4").vote());
        assertEquals(Vote.ABSTAIN, ballots.get("s5").vote());
    }

    @Test
    void cannotCloseBeforeDeadlineNorVoteOutsideWindow() {
        State state = readyBudget("m1", "600.00");
        rejected(BoardModel.closeVoting(state, CLOSES_AT - 1, "m1"), BoardModel.REASON_NOT_DUE);

        State early = ok(BoardModel.convene(opened(), 0L, meeting("m2", Topic.BUDGET,
                allSeats(), List.of())));
        early = ok(BoardModel.proposeBudget(early, 50L, "m2",
                List.of(new BudgetLine(0L, BudgetCategory.PROCUREMENT, new BigDecimal("600.00")))));
        early = ok(BoardModel.freezeMaterials(early, OPENS_AT, "m2"));
        early = ok(BoardModel.startVoting(early, OPENS_AT, "m2"));
        rejected(BoardModel.castVote(early, OPENS_AT - 1, "m2", "s1", Vote.YES),
                BoardModel.REASON_VOTING_CLOSED);
        rejected(BoardModel.castVote(early, CLOSES_AT, "m2", "s1", Vote.YES),
                BoardModel.REASON_VOTING_CLOSED);
    }

    // ---------- 第 3 组：时点、材料与顺序无关 ----------

    @Test
    void materialsAfterDeadlineOrAfterFreezeAreRejectedWithoutOverwriting() {
        State state = ok(BoardModel.convene(opened(), 0L,
                meeting("m1", Topic.OPERATING_REVIEW, allSeats(), List.of())));
        state = ok(BoardModel.addMaterial(state, 50L, "m1",
                new MaterialRef("mat-1", "src-ledger", 1L, 40L)));
        rejected(BoardModel.addMaterial(state, MATERIAL_DEADLINE + 1, "m1",
                new MaterialRef("mat-2", "src-late", 1L, 90L)), BoardModel.REASON_MATERIALS_CLOSED);
        rejected(BoardModel.addMaterial(state, 60L, "m1",
                new MaterialRef("mat-3", "src-future", 1L, 61L)), BoardModel.REASON_FUTURE_EVIDENCE);
        assertEquals(1, state.meetings().get("m1").materials().size());

        state = ok(BoardModel.freezeMaterials(state, OPENS_AT, "m1"));
        rejected(BoardModel.addMaterial(state, OPENS_AT, "m1",
                new MaterialRef("mat-4", "src-after-open", 1L, OPENS_AT)),
                BoardModel.REASON_MATERIALS_FROZEN);
        assertEquals(1, state.meetings().get("m1").materials().size());
    }

    @Test
    void rejectsMeetingWithInvertedTimeline() {
        BoardModel.Outcome inverted = BoardModel.convene(opened(), 0L, new MeetingRequest(
                "m1", Topic.BUDGET, "s1", MATERIAL_DEADLINE, MATERIAL_DEADLINE - 1, CLOSES_AT,
                allSeats(), List.of()));
        rejected(inverted, BoardModel.REASON_INVALID_TIMELINE);

        BoardModel.Outcome closedWindow = BoardModel.convene(opened(), 0L, new MeetingRequest(
                "m1", Topic.BUDGET, "s1", MATERIAL_DEADLINE, OPENS_AT, OPENS_AT,
                allSeats(), List.of()));
        rejected(closedWindow, BoardModel.REASON_INVALID_TIMELINE);

        BoardModel.Outcome lateConvene = BoardModel.convene(opened(), MATERIAL_DEADLINE + 1,
                meeting("m1", Topic.BUDGET, allSeats(), List.of()));
        rejected(lateConvene, BoardModel.REASON_INVALID_TIMELINE);
    }

    @Test
    void convenerMustBeChair() {
        BoardModel.Outcome outcome = BoardModel.convene(opened(), 0L, new MeetingRequest(
                "m1", Topic.BUDGET, "s2", MATERIAL_DEADLINE, OPENS_AT, CLOSES_AT,
                allSeats(), List.of()));
        rejected(outcome, BoardModel.REASON_NOT_CHAIR);
    }

    @Test
    void duplicateMeetingIdAndDuplicateBudgetLineRejected() {
        State state = ok(BoardModel.convene(opened(), 0L,
                meeting("m1", Topic.BUDGET, allSeats(), List.of())));
        rejected(BoardModel.convene(state, 0L, meeting("m1", Topic.BUDGET, allSeats(), List.of())),
                BoardModel.REASON_MEETING_EXISTS);
        rejected(BoardModel.proposeBudget(state, 10L, "m1", List.of(
                new BudgetLine(0L, BudgetCategory.PROCUREMENT, new BigDecimal("100.00")),
                new BudgetLine(0L, BudgetCategory.PROCUREMENT, new BigDecimal("200.00")))),
                BoardModel.REASON_DUPLICATE_BUDGET_LINE);
    }

    @Test
    void sameBallotsInDifferentInsertionOrderYieldEqualStates() {
        State a = readyBudget("m1", "600.00");
        a = ok(BoardModel.castVote(a, 210L, "m1", "s1", Vote.YES));
        a = ok(BoardModel.castVote(a, 211L, "m1", "s2", Vote.YES));
        a = ok(BoardModel.castVote(a, 212L, "m1", "s3", Vote.YES));
        a = ok(BoardModel.castVote(a, 213L, "m1", "s4", Vote.NO));
        a = ok(BoardModel.closeVoting(a, CLOSES_AT, "m1"));

        State b = readyBudget("m1", "600.00");
        b = ok(BoardModel.castVote(b, 213L, "m1", "s4", Vote.NO));
        b = ok(BoardModel.castVote(b, 212L, "m1", "s3", Vote.YES));
        b = ok(BoardModel.castVote(b, 211L, "m1", "s2", Vote.YES));
        b = ok(BoardModel.castVote(b, 210L, "m1", "s1", Vote.YES));
        b = ok(BoardModel.closeVoting(b, CLOSES_AT, "m1"));

        assertEquals(a, b);
        assertEquals(BoardModel.toJson(a), BoardModel.toJson(b));
    }

    @Test
    void resolutionCannotBeForcedPassedByCallerFlag() {
        State state = readyBudget("m1", "600.00");
        state = ok(BoardModel.castVote(state, 210L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 212L, "m1", "s3", Vote.NO));
        state = ok(BoardModel.closeVoting(state, CLOSES_AT, "m1"));
        assertEquals(BoardModel.MeetingStatus.REJECTED, state.meetings().get("m1").status());
        // 未通过时实施必须被拒绝，且不产生预算
        rejected(BoardModel.executeAdoptedResolution(state, CLOSES_AT, "m1"),
                BoardModel.REASON_NOT_ADOPTED);
        assertTrue(state.budgets().isEmpty());
    }

    // ---------- 第 4 组：通过 ≠ 已实施 ----------

    @Test
    void adoptedResolutionChangesNothingUntilExecutedOnce() {
        State state = readyBudget("m1", "500.00");
        state = ok(BoardModel.castVote(state, 210L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 212L, "m1", "s3", Vote.YES));
        State adopted = ok(BoardModel.closeVoting(state, CLOSES_AT, "m1"));
        assertEquals(BoardModel.MeetingStatus.ADOPTED, adopted.meetings().get("m1").status());
        assertTrue(adopted.budgets().isEmpty(), "ADOPTED 不得直接改预算");

        State implemented = ok(BoardModel.executeAdoptedResolution(adopted, CLOSES_AT, "m1"));
        assertEquals(BoardModel.MeetingStatus.IMPLEMENTED,
                implemented.meetings().get("m1").status());
        assertEquals(new BigDecimal("500.00"),
                BoardModel.availableBudget(implemented, 0L, BudgetCategory.PROCUREMENT));

        BigDecimal available = BoardModel.availableBudget(implemented, 0L,
                BudgetCategory.PROCUREMENT);
        rejected(BoardModel.executeAdoptedResolution(implemented, CLOSES_AT, "m1"),
                BoardModel.REASON_NOT_ADOPTED);
        assertEquals(available, BoardModel.availableBudget(implemented, 0L,
                BudgetCategory.PROCUREMENT), "重复实施不得加钱");
    }

    @Test
    void implementationBelowSpentPlusCommittedFailsAndKeepsOldCap() {
        State state = implementBudget("m1", "500.00");
        state = ok(BoardModel.reserveBudget(state, 320L, "c1", 0L,
                BudgetCategory.PROCUREMENT, new BigDecimal("300.00")));
        state = ok(BoardModel.spendBudget(state, 321L, "c1", new BigDecimal("100.00")));
        // 已花 100 + 活动承诺 200 = 300；新上限 200 低于底线 → 失败
        State second = inVoting(state, "m2", Topic.BUDGET,
                List.of(new BudgetLine(0L, BudgetCategory.PROCUREMENT, new BigDecimal("200.00"))),
                allSeats(), List.of());
        second = ok(BoardModel.castVote(second, 210L, "m2", "s1", Vote.YES));
        second = ok(BoardModel.castVote(second, 211L, "m2", "s2", Vote.YES));
        second = ok(BoardModel.castVote(second, 212L, "m2", "s3", Vote.YES));
        second = ok(BoardModel.closeVoting(second, CLOSES_AT, "m2"));
        BoardModel.Outcome failed = BoardModel.executeAdoptedResolution(second, CLOSES_AT, "m2");
        assertFalse(failed.accepted());
        assertEquals(BoardModel.REASON_BUDGET_BELOW_COMMITTED, failed.reason());
        Meeting meeting = failed.state().meetings().get("m2");
        assertEquals(BoardModel.MeetingStatus.IMPLEMENTATION_FAILED, meeting.status());
        assertEquals(BoardModel.REASON_BUDGET_BELOW_COMMITTED, meeting.failureReason());
        assertEquals(new BigDecimal("500.00"),
                BoardModel.availableBudget(failed.state(), 0L, BudgetCategory.PROCUREMENT).add(
                        new BigDecimal("300.00")));
        // 票与决议原文保持不变
        assertEquals(3, meeting.ballots().size());
        assertEquals(new BigDecimal("200.00"),
                meeting.proposedLines().get(0).newLimit());
    }

    // ---------- 第 5 组：预算占用、花费与释放 ----------

    @Test
    void reservationConsumesOnlyPartOfCap() {
        State state = implementBudget("m1", "500.00");
        state = ok(BoardModel.reserveBudget(state, 320L, "c1", 0L,
                BudgetCategory.PROCUREMENT, new BigDecimal("300.00")));
        assertEquals(new BigDecimal("200.00"),
                BoardModel.availableBudget(state, 0L, BudgetCategory.PROCUREMENT));
        assertEquals(new BigDecimal("300.00"),
                BoardModel.outstandingCommitment(state, "c1"));
    }

    @Test
    void duplicateCommitmentIdRejectedEvenAfterRelease() {
        State state = implementBudget("m1", "500.00");
        state = ok(BoardModel.reserveBudget(state, 320L, "c1", 0L,
                BudgetCategory.PROCUREMENT, new BigDecimal("300.00")));
        rejected(BoardModel.reserveBudget(state, 321L, "c1", 0L,
                BudgetCategory.PROCUREMENT, new BigDecimal("10.00")),
                BoardModel.REASON_COMMITMENT_EXISTS);

        state = ok(BoardModel.releaseBudget(state, 322L, "c1", new BigDecimal("300.00")));
        rejected(BoardModel.reserveBudget(state, 323L, "c1", 0L,
                BudgetCategory.PROCUREMENT, new BigDecimal("10.00")),
                BoardModel.REASON_COMMITMENT_EXISTS);
        assertEquals(1, state.commitments().size());
    }

    @Test
    void spendAndReleaseKeepBudgetIdentity() {
        State state = implementBudget("m1", "500.00");
        state = ok(BoardModel.reserveBudget(state, 320L, "c1", 0L,
                BudgetCategory.PROCUREMENT, new BigDecimal("300.00")));
        state = ok(BoardModel.spendBudget(state, 321L, "c1", new BigDecimal("100.00")));
        assertEquals(new BigDecimal("200.00"),
                BoardModel.availableBudget(state, 0L, BudgetCategory.PROCUREMENT));

        state = ok(BoardModel.releaseBudget(state, 322L, "c1", new BigDecimal("200.00")));
        assertEquals(new BigDecimal("400.00"),
                BoardModel.availableBudget(state, 0L, BudgetCategory.PROCUREMENT));
        assertEquals(new BigDecimal("100.00"), BoardModel.spentBudget(state, 0L,
                BudgetCategory.PROCUREMENT));
        assertEquals(new BigDecimal("0.00"), BoardModel.outstandingCommitment(state, "c1"));
        assertEquals(BoardModel.CommitmentStatus.CLOSED,
                state.commitments().get(0).status());
    }

    @Test
    void overspendOverReleaseAndNonPositiveAmountsRejected() {
        State state = implementBudget("m1", "500.00");
        state = ok(BoardModel.reserveBudget(state, 320L, "c1", 0L,
                BudgetCategory.PROCUREMENT, new BigDecimal("300.00")));
        rejected(BoardModel.spendBudget(state, 321L, "c1", new BigDecimal("300.01")),
                BoardModel.REASON_EXCEEDS_COMMITMENT);
        rejected(BoardModel.releaseBudget(state, 322L, "c1", new BigDecimal("300.01")),
                BoardModel.REASON_EXCEEDS_COMMITMENT);
        rejected(BoardModel.spendBudget(state, 323L, "c1", new BigDecimal("0.00")),
                BoardModel.REASON_INVALID_AMOUNT);
        rejected(BoardModel.releaseBudget(state, 324L, "c1", new BigDecimal("-1.00")),
                BoardModel.REASON_INVALID_AMOUNT);
        rejected(BoardModel.reserveBudget(state, 325L, "c2", 0L,
                BudgetCategory.PROCUREMENT, new BigDecimal("200.01")),
                BoardModel.REASON_BUDGET_EXCEEDED);
    }

    @Test
    void budgetIsIsolatedPerPeriodAndCategory() {
        State state = inVoting(opened(), "m1", Topic.BUDGET, List.of(
                new BudgetLine(0L, BudgetCategory.PROCUREMENT, new BigDecimal("500.00")),
                new BudgetLine(1L, BudgetCategory.PROCUREMENT, new BigDecimal("400.00")),
                new BudgetLine(0L, BudgetCategory.PRODUCTION_LABOR, new BigDecimal("100.00"))),
                allSeats(), List.of());
        state = ok(BoardModel.castVote(state, 210L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 212L, "m1", "s3", Vote.YES));
        state = ok(BoardModel.closeVoting(state, CLOSES_AT, "m1"));
        state = ok(BoardModel.executeAdoptedResolution(state, CLOSES_AT, "m1"));

        state = ok(BoardModel.reserveBudget(state, 320L, "c1", 0L,
                BudgetCategory.PROCUREMENT, new BigDecimal("300.00")));
        assertEquals(new BigDecimal("200.00"),
                BoardModel.availableBudget(state, 0L, BudgetCategory.PROCUREMENT));
        assertEquals(new BigDecimal("400.00"),
                BoardModel.availableBudget(state, 1L, BudgetCategory.PROCUREMENT));
        assertEquals(new BigDecimal("100.00"),
                BoardModel.availableBudget(state, 0L, BudgetCategory.PRODUCTION_LABOR));

        rejected(BoardModel.reserveBudget(state, 321L, "c2", 2L,
                BudgetCategory.PROCUREMENT, new BigDecimal("10.00")),
                BoardModel.REASON_BUDGET_NOT_SET);
    }

    @Test
    void stateCarriesNoCashOrProfitFields() {
        List<String> components = Arrays.stream(State.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("charter", "meetings", "budgets", "commitments", "reviews"),
                components);
    }

    @Test
    void collectionsAreDeeplyImmutable() {
        State state = implementBudget("m1", "500.00");
        state = ok(BoardModel.reserveBudget(state, 320L, "c1", 0L,
                BudgetCategory.PROCUREMENT, new BigDecimal("300.00")));
        final State frozen = state;
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.budgets().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.commitments().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.meetings().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.meetings().get("m1").ballots().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.meetings().get("m1").proposedLines().clear());
    }

    // ---------- 第 6 组：考核完整性 ----------

    private static State reviewReady() {
        State state = ok(BoardModel.convene(opened(), 0L,
                meeting("r1", Topic.OPERATING_REVIEW, allSeats(), List.of())));
        state = ok(BoardModel.addMaterial(state, 10L, "r1",
                new MaterialRef("mat-1", "src-ledger", 1L, 5L)));
        state = ok(BoardModel.addMaterial(state, 11L, "r1",
                new MaterialRef("mat-2", "src-disclosure", 1L, 6L)));
        return ok(BoardModel.freezeMaterials(state, OPENS_AT, "r1"));
    }

    private static EvidenceMetric metric(Dimension dimension, String score, String source) {
        return new EvidenceMetric(dimension, new BigDecimal(score), source, 5L);
    }

    private static List<EvidenceMetric> fullEvidence() {
        return List.of(
                metric(Dimension.OPERATING_COMMITMENT, "80.00", "src-ledger"),
                metric(Dimension.CASH_SOLVENCY, "60.00", "src-ledger"),
                metric(Dimension.RISK, "100.00", "src-ledger"),
                metric(Dimension.DISCLOSURE, "40.00", "src-disclosure"),
                metric(Dimension.LONG_TERM_INVESTMENT, "20.00", "src-ledger"));
    }

    @Test
    void fullEvidenceScoresSixtySix() {
        State state = reviewReady();
        state = ok(BoardModel.calculateReview(state, OPENS_AT, "r1", fullEvidence()));
        BoardModel.ReviewResult review = state.reviews().get(0);
        assertEquals(ReviewStatus.COMPLETE, review.status());
        assertEquals(new BigDecimal("66.00"), review.total());
        assertTrue(review.missingDimensions().isEmpty());
    }

    @Test
    void missingDisclosureGivesIncompleteWithoutZeroFill() {
        State state = reviewReady();
        List<EvidenceMetric> partial = List.of(
                metric(Dimension.OPERATING_COMMITMENT, "80.00", "src-ledger"),
                metric(Dimension.CASH_SOLVENCY, "60.00", "src-ledger"),
                metric(Dimension.RISK, "100.00", "src-ledger"),
                metric(Dimension.LONG_TERM_INVESTMENT, "20.00", "src-ledger"));
        state = ok(BoardModel.calculateReview(state, OPENS_AT, "r1", partial));
        BoardModel.ReviewResult review = state.reviews().get(0);
        assertEquals(ReviewStatus.INCOMPLETE, review.status());
        assertNull(review.total());
        assertEquals(List.of(Dimension.DISCLOSURE), review.missingDimensions());
    }

    @Test
    void trueZeroScoreIsValidAndNotTreatedAsMissing() {
        State state = reviewReady();
        List<EvidenceMetric> evidence = List.of(
                metric(Dimension.OPERATING_COMMITMENT, "80.00", "src-ledger"),
                metric(Dimension.CASH_SOLVENCY, "60.00", "src-ledger"),
                metric(Dimension.RISK, "100.00", "src-ledger"),
                metric(Dimension.DISCLOSURE, "0.00", "src-disclosure"),
                metric(Dimension.LONG_TERM_INVESTMENT, "20.00", "src-ledger"));
        state = ok(BoardModel.calculateReview(state, OPENS_AT, "r1", evidence));
        BoardModel.ReviewResult review = state.reviews().get(0);
        assertEquals(ReviewStatus.COMPLETE, review.status());
        assertEquals(new BigDecimal("60.00"), review.total());
    }

    @Test
    void futureOrUnregisteredEvidenceRejected() {
        State state = reviewReady();
        BoardModel.Outcome future = BoardModel.calculateReview(state, OPENS_AT, "r1", List.of(
                new EvidenceMetric(Dimension.OPERATING_COMMITMENT, new BigDecimal("80.00"),
                        "src-ledger", MATERIAL_DEADLINE + 1)));
        rejected(future, BoardModel.REASON_FUTURE_EVIDENCE);

        BoardModel.Outcome unknownSource = BoardModel.calculateReview(state, OPENS_AT, "r1",
                List.of(new EvidenceMetric(Dimension.OPERATING_COMMITMENT,
                        new BigDecimal("80.00"), "src-unsourced", 5L)));
        rejected(unknownSource, BoardModel.REASON_UNKNOWN_SOURCE);

        BoardModel.Outcome outOfRange = BoardModel.calculateReview(state, OPENS_AT, "r1",
                List.of(new EvidenceMetric(Dimension.OPERATING_COMMITMENT,
                        new BigDecimal("100.01"), "src-ledger", 5L)));
        rejected(outOfRange, BoardModel.REASON_INVALID_SCORE);
    }

    @Test
    void reviewRequiresFrozenMaterialsOfReviewTopic() {
        State convened = ok(BoardModel.convene(opened(), 0L,
                meeting("r1", Topic.OPERATING_REVIEW, allSeats(), List.of())));
        convened = ok(BoardModel.addMaterial(convened, 10L, "r1",
                new MaterialRef("mat-1", "src-ledger", 1L, 5L)));
        rejected(BoardModel.calculateReview(convened, OPENS_AT, "r1", fullEvidence()),
                BoardModel.REASON_MATERIALS_NOT_FROZEN);

        State budget = readyBudget("b1", "100.00");
        rejected(BoardModel.calculateReview(budget, OPENS_AT, "b1", fullEvidence()),
                BoardModel.REASON_WRONG_TOPIC);
    }

    @Test
    void duplicateReviewOrDuplicateDimensionRejected() {
        State state = reviewReady();
        state = ok(BoardModel.calculateReview(state, OPENS_AT, "r1", fullEvidence()));
        rejected(BoardModel.calculateReview(state, OPENS_AT, "r1", fullEvidence()),
                BoardModel.REASON_REVIEW_EXISTS);

        State fresh = reviewReady();
        rejected(BoardModel.calculateReview(fresh, OPENS_AT, "r1", List.of(
                metric(Dimension.RISK, "10.00", "src-ledger"),
                metric(Dimension.RISK, "20.00", "src-ledger"))),
                BoardModel.REASON_DUPLICATE_DIMENSION);
    }

    // ---------- 第 7 组：JSON 可复现 ----------

    @Test
    void jsonRoundTripIsStableAndRejectsLoweredQuorum() {
        State state = implementBudget("m1", "500.00");
        state = ok(BoardModel.reserveBudget(state, 320L, "c1", 0L,
                BudgetCategory.PROCUREMENT, new BigDecimal("300.00")));
        state = ok(BoardModel.spendBudget(state, 321L, "c1", new BigDecimal("100.00")));

        String json = BoardModel.toJson(state);
        assertEquals(json, BoardModel.toJson(state));
        State restored = BoardModel.fromJson(json);
        assertEquals(state, restored);
        assertEquals(json, BoardModel.toJson(restored));

        String tampered = json.replace("\"quorum\":3", "\"quorum\":1");
        assertNotNull(tampered);
        assertFalse(tampered.equals(json), "夹具应能篡改法定人数");
        assertThrows(IllegalArgumentException.class, () -> BoardModel.fromJson(tampered));
    }

    @Test
    void jsonRoundTripPreservesIncompleteReview() {
        State state = reviewReady();
        state = ok(BoardModel.calculateReview(state, OPENS_AT, "r1", List.of(
                metric(Dimension.OPERATING_COMMITMENT, "80.00", "src-ledger"))));
        State restored = BoardModel.fromJson(BoardModel.toJson(state));
        assertEquals(state, restored);
        assertNull(restored.reviews().get(0).total());
        assertEquals(Set.of(Dimension.CASH_SOLVENCY, Dimension.RISK, Dimension.DISCLOSURE,
                        Dimension.LONG_TERM_INVESTMENT),
                Set.copyOf(restored.reviews().get(0).missingDimensions()));
    }

    // ---------- 第 8 组：审查回归（精确加权求和、时间边界、空决议、考核终态） ----------

    /** 指定会议标识的考核会议：两份材料 + 已冻结。 */
    private static State reviewMeeting(String id) {
        State state = ok(BoardModel.convene(opened(), 0L,
                meeting(id, Topic.OPERATING_REVIEW, allSeats(), List.of())));
        state = ok(BoardModel.addMaterial(state, 10L, id,
                new MaterialRef("mat-1", "src-ledger", 1L, 5L)));
        state = ok(BoardModel.addMaterial(state, 11L, id,
                new MaterialRef("mat-2", "src-disclosure", 1L, 6L)));
        return ok(BoardModel.freezeMaterials(state, OPENS_AT, id));
    }

    private static List<EvidenceMetric> sameScore(String score) {
        return List.of(
                metric(Dimension.OPERATING_COMMITMENT, score, "src-ledger"),
                metric(Dimension.CASH_SOLVENCY, score, "src-ledger"),
                metric(Dimension.RISK, score, "src-ledger"),
                metric(Dimension.DISCLOSURE, score, "src-disclosure"),
                metric(Dimension.LONG_TERM_INVESTMENT, score, "src-ledger"));
    }

    @Test
    void equalScoresSurviveWeightingWithoutPerItemRounding() {
        for (String score : List.of("0.01", "0.10", "0.03")) {
            State state = reviewMeeting("rr-" + score);
            state = ok(BoardModel.calculateReview(state, OPENS_AT, "rr-" + score,
                    sameScore(score)));
            assertEquals(new BigDecimal(score), state.reviews().get(0).total(),
                    "五项同分 " + score + " 的加权总分必须仍是 " + score);
        }
    }

    @Test
    void mixedDecimalsUseOneFinalRound() {
        State state = reviewMeeting("rm");
        state = ok(BoardModel.calculateReview(state, OPENS_AT, "rm", List.of(
                metric(Dimension.OPERATING_COMMITMENT, "0.01", "src-ledger"),
                metric(Dimension.CASH_SOLVENCY, "0.03", "src-ledger"),
                metric(Dimension.RISK, "0.10", "src-ledger"),
                metric(Dimension.DISCLOSURE, "33.33", "src-disclosure"),
                metric(Dimension.LONG_TERM_INVESTMENT, "55.55", "src-ledger"))));
        // Σ(score×weight)=0.35+0.75+1.50+499.95+555.50=1058.05 → 10.5805 → 10.58（逐项先舍入会得到 10.59）
        assertEquals(new BigDecimal("10.58"), state.reviews().get(0).total());
    }

    @Test
    void missingDimensionStillHasNoTotalWithDecimalScores() {
        State state = reviewMeeting("ri");
        state = ok(BoardModel.calculateReview(state, OPENS_AT, "ri", List.of(
                metric(Dimension.OPERATING_COMMITMENT, "0.01", "src-ledger"),
                metric(Dimension.CASH_SOLVENCY, "0.03", "src-ledger"),
                metric(Dimension.RISK, "0.10", "src-ledger"),
                metric(Dimension.LONG_TERM_INVESTMENT, "55.55", "src-ledger"))));
        assertNull(state.reviews().get(0).total());
        assertEquals(ReviewStatus.INCOMPLETE, state.reviews().get(0).status());
    }

    @Test
    void negativeNowIsRejectedByProposalAndMaterialEntries() {
        State state = ok(BoardModel.convene(opened(), 0L,
                meeting("m1", Topic.BUDGET, allSeats(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> BoardModel.proposeBudget(state, -1L,
                "m1", List.of(new BudgetLine(0L, BudgetCategory.PROCUREMENT,
                        new BigDecimal("100.00")))));
        assertThrows(IllegalArgumentException.class, () -> BoardModel.addMaterial(state, -1L,
                "m1", new MaterialRef("mat-1", "src-ledger", 1L, 5L)));
    }

    @Test
    void proposalAndMaterialCannotPredateMeetingCreation() {
        State state = ok(BoardModel.convene(opened(), 50L,
                meeting("m1", Topic.BUDGET, allSeats(), List.of())));
        rejected(BoardModel.proposeBudget(state, 10L, "m1",
                List.of(new BudgetLine(0L, BudgetCategory.PROCUREMENT,
                        new BigDecimal("100.00")))), BoardModel.REASON_INVALID_TIMELINE);
        rejected(BoardModel.addMaterial(state, 10L, "m1",
                new MaterialRef("mat-1", "src-ledger", 1L, 5L)),
                BoardModel.REASON_INVALID_TIMELINE);
        assertTrue(state.meetings().get("m1").proposedLines().isEmpty());
        assertTrue(state.meetings().get("m1").materials().isEmpty());
    }

    @Test
    void executionCannotRunBeforeVotingDeadline() {
        State state = readyBudget("m1", "500.00");
        state = ok(BoardModel.castVote(state, 210L, "m1", "s1", Vote.YES));
        state = ok(BoardModel.castVote(state, 211L, "m1", "s2", Vote.YES));
        state = ok(BoardModel.castVote(state, 212L, "m1", "s3", Vote.YES));
        State adopted = ok(BoardModel.closeVoting(state, CLOSES_AT, "m1"));
        rejected(BoardModel.executeAdoptedResolution(adopted, CLOSES_AT - 1, "m1"),
                BoardModel.REASON_NOT_DUE);
        assertTrue(adopted.budgets().isEmpty(), "未到截止不得实施");
        assertTrue(ok(BoardModel.executeAdoptedResolution(adopted, CLOSES_AT, "m1"))
                .budgets().size() == 1);
    }

    @Test
    void reviewCannotRunBeforeMeetingOpens() {
        State state = reviewMeeting("r1");
        rejected(BoardModel.calculateReview(state, OPENS_AT - 1, "r1", fullEvidence()),
                BoardModel.REASON_NOT_DUE);
        assertTrue(state.reviews().isEmpty());
    }

    @Test
    void budgetMeetingWithoutProposalCannotEnterVotingOrBeImplemented() {
        State state = ok(BoardModel.convene(opened(), 0L,
                meeting("m1", Topic.BUDGET, allSeats(), List.of())));
        state = ok(BoardModel.freezeMaterials(state, OPENS_AT, "m1"));
        rejected(BoardModel.startVoting(state, OPENS_AT, "m1"),
                BoardModel.REASON_EMPTY_PROPOSAL);
        assertEquals(BoardModel.MeetingStatus.MATERIALS_FROZEN,
                state.meetings().get("m1").status());
        assertTrue(state.budgets().isEmpty());
    }

    @Test
    void freezingAfterVotingDeadlineIsRejected() {
        State state = ok(BoardModel.convene(opened(), 0L,
                meeting("m1", Topic.BUDGET, allSeats(), List.of())));
        state = ok(BoardModel.proposeBudget(state, 10L, "m1",
                List.of(new BudgetLine(0L, BudgetCategory.PROCUREMENT,
                        new BigDecimal("100.00")))));
        rejected(BoardModel.freezeMaterials(state, CLOSES_AT, "m1"),
                BoardModel.REASON_VOTING_CLOSED);
        assertEquals(BoardModel.MeetingStatus.CONVENED, state.meetings().get("m1").status());
    }

    @Test
    void frozenBudgetMeetingWithoutProposalClosesRejectedAtDeadline() {
        State state = opened();
        state = ok(BoardModel.convene(state, 0L, meeting("m-empty", Topic.BUDGET, allSeats(),
                List.of())));
        state = ok(BoardModel.freezeMaterials(state, OPENS_AT, "m-empty"));
        rejected(BoardModel.startVoting(state, OPENS_AT, "m-empty"),
                BoardModel.REASON_EMPTY_PROPOSAL);
        rejected(BoardModel.closeVoting(state, CLOSES_AT - 1, "m-empty"),
                BoardModel.REASON_NOT_DUE);

        BoardModel.Outcome closed = BoardModel.closeVoting(state, CLOSES_AT, "m-empty");
        assertTrue(closed.accepted(), "到期必须形成明确终态，而不是永久冻结：" + closed.reason());
        assertEquals(BoardModel.REASON_EMPTY_PROPOSAL, closed.reason());
        assertEquals(BoardModel.MeetingStatus.REJECTED,
                closed.state().meetings().get("m-empty").status());
        assertTrue(closed.state().budgets().isEmpty());
    }

    @Test
    void reviewResultRejectsTotalThatDisagreesWithItsEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new BoardModel.ReviewResult(
                "r1", ReviewStatus.COMPLETE, new BigDecimal("99.00"), List.of(),
                fullEvidence()));
        // 只有四项却声称完整
        assertThrows(IllegalArgumentException.class, () -> new BoardModel.ReviewResult(
                "r1", ReviewStatus.COMPLETE, new BigDecimal("60.00"), List.of(),
                List.of(
                        metric(Dimension.OPERATING_COMMITMENT, "80.00", "src-ledger"),
                        metric(Dimension.CASH_SOLVENCY, "60.00", "src-ledger"),
                        metric(Dimension.RISK, "100.00", "src-ledger"),
                        metric(Dimension.LONG_TERM_INVESTMENT, "20.00", "src-ledger"))));
    }

    @Test
    void reviewResultRejectsMissingListThatDisagreesWithEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new BoardModel.ReviewResult(
                "r1", ReviewStatus.INCOMPLETE, null, List.of(Dimension.RISK),
                List.of(
                        metric(Dimension.OPERATING_COMMITMENT, "80.00", "src-ledger"),
                        metric(Dimension.CASH_SOLVENCY, "60.00", "src-ledger"),
                        metric(Dimension.RISK, "100.00", "src-ledger"),
                        metric(Dimension.LONG_TERM_INVESTMENT, "20.00", "src-ledger"))));
    }

    @Test
    void jsonDecodeRejectsTamperedReviewTotal() {
        State state = reviewMeeting("r1");
        state = ok(BoardModel.calculateReview(state, OPENS_AT, "r1", fullEvidence()));
        String json = BoardModel.toJson(state);
        assertTrue(json.contains("\"total\":66.00"), "夹具有总分字段：" + json);
        assertThrows(IllegalArgumentException.class,
                () -> BoardModel.fromJson(json.replace("\"total\":66.00", "\"total\":99.00")));
        assertEquals(state, BoardModel.fromJson(json));
    }

    @Test
    void successfulReviewMarksMeetingReviewed() {
        State state = reviewMeeting("r1");
        state = ok(BoardModel.calculateReview(state, OPENS_AT, "r1", fullEvidence()));
        assertEquals(BoardModel.MeetingStatus.REVIEWED, state.meetings().get("r1").status());
        assertEquals(1, state.reviews().size());

        State partial = reviewMeeting("r2");
        partial = ok(BoardModel.calculateReview(partial, OPENS_AT, "r2", List.of(
                metric(Dimension.OPERATING_COMMITMENT, "80.00", "src-ledger"))));
        assertEquals(BoardModel.MeetingStatus.REVIEWED, partial.meetings().get("r2").status());
        assertEquals(ReviewStatus.INCOMPLETE, partial.reviews().get(0).status());
    }

    @Test
    void reviewWithoutQuorumAdjournsAndProducesNoScore() {
        State state = ok(BoardModel.convene(opened(), 0L, meeting("r1",
                Topic.OPERATING_REVIEW, List.of("s1", "s2", "s3"), List.of("s3"))));
        state = ok(BoardModel.addMaterial(state, 10L, "r1",
                new MaterialRef("mat-1", "src-ledger", 1L, 5L)));
        state = ok(BoardModel.addMaterial(state, 11L, "r1",
                new MaterialRef("mat-2", "src-disclosure", 1L, 6L)));
        state = ok(BoardModel.freezeMaterials(state, OPENS_AT, "r1"));

        BoardModel.Outcome outcome = BoardModel.calculateReview(state, OPENS_AT, "r1",
                fullEvidence());
        assertFalse(outcome.accepted());
        assertEquals(BoardModel.REASON_NO_QUORUM, outcome.reason());
        assertEquals(BoardModel.MeetingStatus.ADJOURNED_NO_QUORUM,
                outcome.state().meetings().get("r1").status());
        assertTrue(outcome.state().reviews().isEmpty());
    }

    @Test
    void reviewResultRejectsScoresOutsideTheDeclaredRange() {
        List<EvidenceMetric> scores = Arrays.stream(Dimension.values())
                .map(dimension -> metric(dimension, "100.01", "src-ledger"))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> new BoardModel.ReviewResult(
                "out-of-range", ReviewStatus.COMPLETE, new BigDecimal("100.01"),
                List.of(), scores));
        assertThrows(IllegalArgumentException.class, () -> new BoardModel.ReviewResult(
                "partial-out-of-range", ReviewStatus.INCOMPLETE, null,
                Arrays.stream(Dimension.values()).filter(dimension -> dimension != Dimension.RISK)
                        .toList(),
                List.of(metric(Dimension.RISK, "100.01", "src-ledger"))));
    }

    @Test
    void incompleteReviewResultMustActuallyHaveMissingEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new BoardModel.ReviewResult(
                "complete-labelled-incomplete", ReviewStatus.INCOMPLETE, null,
                List.of(), fullEvidence()));
    }
}
