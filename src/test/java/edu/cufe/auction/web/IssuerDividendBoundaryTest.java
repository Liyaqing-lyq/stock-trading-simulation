package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.RoleInputTables;
import edu.cufe.auction.world.RoleTradingView;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class IssuerDividendBoundaryTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode terms(String meeting, String scheme) {
        return JSON.createObjectNode().put("companyPartyId", "eng-a").put("meetingId", meeting)
                .put("schemeId", scheme).put("perShare", "0.01").put("recordAtMillis", 400_000)
                .put("exAtMillis", 500_000).put("payAtMillis", 600_000);
    }

    private static void convene(WorldRuntime world, String meeting) throws Exception {
        long due = world.snapshot().timeMillis() + 10;
        world.openRoleSession("alice").issuerDividend("meet-" + meeting, "meet-task-" + meeting, due,
                "CONVENE", meeting, 100_000, 200_000, 300_000, null);
        world.advanceTo(due);
        assertNotNull(world.boardOfCompany("eng-a").orElseThrow().board().meeting(meeting));
    }

    @Test void meetingCreatedAfterTheActualDispatchCannotBeUsedByGuessingItsName() throws Exception {
        var file = dir.resolve("cutoff.journal");
        try (var world = ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(file)) {
            var old = AutonomousIssuerDividendTest.call(world, "bob", "old");
            assertFalse(old.inputText().contains("meeting-after-dispatch"));
            convene(world, "meeting-after-dispatch");
            assertNull(AutonomousIssuerDividendTest.reply(world, old, "bob", RoleTradingView.TOOL_ISSUER_DIVIDEND_PROPOSE,
                    terms("meeting-after-dispatch", "guessed")));
            assertEquals(BoardModel.REASON_UNKNOWN_MEETING, world.snapshot().decisions().orElseThrow()
                    .dispatch(old.attemptId()).businessReject());
            var receipt = world.autonomyRoleObservation("bob").at("/engineering/deliberation/lastActionResult");
            assertEquals("REJECTED", receipt.required("execution").asText());
            assertEquals(BoardModel.REASON_UNKNOWN_MEETING, receipt.required("reason").asText());
            assertTrue(world.snapshot().dividends().orElseThrow().schemes().isEmpty());
        }
        try (var world = ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(file)) {
            assertTrue(world.snapshot().dividends().orElseThrow().schemes().isEmpty());
            assertTrue(world.boardOfCompany("eng-a").orElseThrow().board().meetings().containsKey("meeting-after-dispatch"));
        }
    }

    @Test void positiveProposalTimeBelongsOnlyToItsDirectorAndEndsAtTheActualReceipt() throws Exception {
        var file = dir.resolve("focus.journal");
        long due;
        try (var world = ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(file)) {
            convene(world, "distribution");
            var call = AutonomousIssuerDividendTest.call(world, "bob", "write");
            var ref = AutonomousIssuerDividendTest.reply(world, call, "bob", RoleTradingView.TOOL_ISSUER_DIVIDEND_PROPOSE,
                    terms("distribution", "proposal"));
            assertNotNull(ref);
            due = ref.dueMillis();
            world.advanceTo(due - 1);
            assertTrue(world.autonomyRoleBusy("bob"));
            assertFalse(world.autonomyRoleBusy("carol"));
            assertEquals("IN_PROGRESS", world.autonomyRoleObservation("bob").at("/engineering/deliberation/lastActionResult/execution").asText());
            assertTrue(world.snapshot().dividends().orElseThrow().schemes().isEmpty());
        }
        try (var world = ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(file)) {
            assertTrue(world.autonomyRoleBusy("bob"));
            world.advanceTo(due);
            assertFalse(world.autonomyRoleBusy("bob"));
            var receipt = world.autonomyRoleObservation("bob").at("/engineering/deliberation/lastActionResult");
            assertEquals("APPLIED", receipt.required("execution").asText());
            assertEquals(due, receipt.required("resultAt").asLong());
            assertTrue(world.snapshot().dividends().orElseThrow().schemes().containsKey("proposal"));
            var view = (ObjectNode) world.autonomyRoleObservation("bob");
            RoleInputTables.expand(view);
            var meeting = view.findParents("dividendRequest").get(0);
            assertTrue(meeting.required("proposalPresent").asBoolean());
            assertEquals("WRONG_TOPIC", meeting.at("/planningPrerequisites/board.implement/blockers/0").asText());
        }
    }
}
