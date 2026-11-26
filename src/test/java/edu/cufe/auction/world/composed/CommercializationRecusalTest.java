package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.ResearchModel;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommercializationRecusalTest {
    private static final String COMPANY = ComposedPharmaScenario.PHARMA, MEETING = "commercial-review";
    @TempDir Path directory;

    @Test void relatedDirectorCanAttendButCannotSupplyThirdVote() throws Exception {
        exercise(directory.resolve("late-related.journal"), false);
    }

    @Test void explicitlyRecusedDirectorRetainsOriginalAttendanceAndVoteRules() throws Exception {
        exercise(directory.resolve("declared.journal"), true);
    }

    static void exercise(Path journal, boolean selfRecused) throws Exception {
        WorldRuntime.Snapshot end;
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            ComposedPharmaWorldTest.grantBudgetAndAppointManager(world);
            long docsAt = ComposedPharmaWorldTest.completeP1(world);
            ComposedPharmaWorldTest.readEvidence(world, docsAt, List.of("frank", "grace", "heidi"));
            long start = world.snapshot().timeMillis() + 100;
            long opens = start + 12_000, closes = start + 60_000;
            world.scheduleCommercializationConvene("convene", "convene-t", start, COMPANY, "frank",
                    MEETING, start + 10_000, opens, closes);
            world.scheduleEngineeringAttend("chair", "chair-t", start + 100, COMPANY, "frank", MEETING, false);
            world.scheduleEngineeringAttend("peer", "peer-t", start + 100, COMPANY, "grace", MEETING, false);
            var refs = new ArrayList<BoardModel.MaterialRef>();
            for (int i = 0; i < ResearchModel.Stage.values().length; i++) {
                String doc = ComposedPharmaWorldTest.evidenceDoc(i);
                String material = "stage-" + i;
                world.scheduleEngineeringMaterial("mat-" + i, "mat-t-" + i, start + 200 + i,
                        COMPANY, "frank", MEETING, material, doc, 1);
                refs.add(new BoardModel.MaterialRef(material, doc, 1, start));
            }
            String related = world.boardOfCompany(COMPANY).orElseThrow().board().charter().seatOf("heidi");
            world.scheduleCommercializationProposal("project", "project-t", start + 2_000, COMPANY,
                    "frank", MEETING, List.of(related), new BoardModel.CommercializationRequest(COMPANY, "p1", refs), 1_000);
            world.scheduleEngineeringAttend("related", "related-t", start + 4_000, COMPANY, "heidi", MEETING, selfRecused);
            world.advanceTo(opens);
            var meeting = world.boardOfCompany(COMPANY).orElseThrow().board().meeting(MEETING);
            assertEquals(BoardModel.MeetingStatus.VOTING, meeting.status());
            assertTrue(meeting.attendeeSeatIds().contains(related));
            assertEquals(List.of(related), meeting.recusedSeatIds());
            assertEquals(2, meeting.eligibleVoters().size());
            var blockedCall = PharmaDecisionToolTest.open(world, "heidi", "related-vote");
            var own = ownMeeting(new ObjectMapper().readTree(blockedCall.inputText()));
            assertTrue(own.required("attended").asBoolean());
            assertTrue(own.required("recused").asBoolean());
            assertEquals(3, own.required("materials").size());
            own.required("materials").forEach(m -> assertTrue(m.required("read").asBoolean()));
            assertFalse(ownMeeting(world.autonomyRoleObservation("grace")).required("recused").asBoolean());
            long voteAt = PharmaDecisionToolTest.reply(world, blockedCall, "board.vote",
                    Map.of("companyPartyId", COMPANY, "meetingId", MEETING, "vote", "YES"));
            world.advanceTo(voteAt);
            var blockedAction = world.snapshot().decisions().orElseThrow().actionRef(blockedCall.actionId());
            var rejected = world.snapshot().engineering().orElseThrow().reports().get(blockedAction.taskId() + ":finish");
            assertNotNull(rejected);
            assertFalse(rejected.accepted());
            assertEquals(BoardModel.REASON_NOT_ELIGIBLE_VOTER, rejected.reason());
            assertTrue(world.boardOfCompany(COMPANY).orElseThrow().board().meeting(MEETING).ballots().isEmpty());
            for (String who : List.of("frank", "grace")) {
                world.scheduleEngineeringVote("vote-" + who, "vote-t-" + who, voteAt + 2_000,
                        COMPANY, who, MEETING, BoardModel.Vote.YES, 1_000);
            }
            world.advanceTo(closes);
            var closed = world.boardOfCompany(COMPANY).orElseThrow().board().meeting(MEETING);
            assertEquals(2, closed.ballots().size());
            assertFalse(closed.ballots().containsKey(related));
            assertEquals(BoardModel.MeetingStatus.ADJOURNED_NO_QUORUM, closed.status());
            assertNull(closed.commercializationResolution());
            end = world.snapshot();
        }
        byte[] sealed = Files.readAllBytes(journal);
        try (WorldRuntime reopened = ComposedPharmaScenario.openDebug(journal)) {
            assertEquals(end, reopened.snapshot());
            assertTrue(ownMeeting(reopened.autonomyRoleObservation("heidi")).required("recused").asBoolean());
        }
        assertArrayEquals(sealed, Files.readAllBytes(journal));
    }

    private static JsonNode ownMeeting(JsonNode input) {
        for (var board : input.at("/engineering/directorships")) {
            for (var meeting : board.path("meetings")) {
                if (MEETING.equals(meeting.path("meetingId").asText())) return meeting;
            }
        }
        fail("本人治理视图缺少会议 " + MEETING);
        return null;
    }
}
