package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 非董事经理的真实期末编制、董事实读评分与批准、授权、公开披露。 */
class PharmaPeriodicDecisionTest {
    @TempDir Path dir;

    @Test void completePeriodUsesIndependentCompanyAndModelReceipts() throws Exception {
        Path file = dir.resolve("period.journal");
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(file)) {
            ComposedPharmaWorldTest.grantBudgetAndAppointManager(world, "kate");
            world.advanceTo(PeriodicModel.PERIOD_MILLIS + 5);
            var snapshot = world.periodicOf("pharma").orElseThrow().snapshots().get(0);
            var meeting = world.boardOfCompany("pharma").orElseThrow().board().meeting(snapshot.meetingId());
            List<String> directors = List.of("frank", "grace", "heidi");
            for (int i = 0; i < directors.size(); i++) world.scheduleEngineeringAttend("att-" + i, "att-t-" + i,
                    snapshot.capturedAt() + 10 + i, "pharma", directors.get(i), snapshot.meetingId(), false);
            world.advanceTo(meeting.opensAt() + 1_000);
            tool(world, "kate", "prepare", "periodic.prepare", Map.of("companyPartyId", "pharma", "period", 1));
            assertEquals(1, world.periodicOf("pharma").orElseThrow().drafts().size());
            var draft = world.periodicOf("pharma").orElseThrow().drafts().get(0);
            long start = world.snapshot().timeMillis();
            for (int i = 0; i < directors.size(); i++) world.scheduleStartReading("read-" + i, "read-t-" + i,
                    start + 100 + i, directors.get(i), draft.docId(), draft.version());
            world.advanceTo(start + 2_000);
            var metrics = List.of(Map.of("dimension", "CASH_SOLVENCY", "score", 90,
                    "sourceRef", "account:RND-PHARMA", "asOf", snapshot.capturedAt()));
            for (String person : directors) tool(world, person, "score-" + person, "periodic.score",
                    Map.of("companyPartyId", "pharma", "period", 1, "metrics", metrics));
            assertEquals(3, world.periodicOf("pharma").orElseThrow().ballots().size());
            for (String person : directors) tool(world, person, "approve-" + person, "periodic.approve",
                    Map.of("companyPartyId", "pharma", "period", 1, "draftId", draft.draftId(), "draftVersion", 1));
            assertEquals(3, world.periodicOf("pharma").orElseThrow().approvals().size());
            world.advanceTo(meeting.closesAt() + 10);
            tool(world, "kate", "authorize", "periodic.authorize", Map.of("companyPartyId", "pharma", "period", 1,
                    "draftId", draft.draftId(), "draftVersion", 1, "publisherPersonId", "frank",
                    "expiresAt", world.snapshot().timeMillis() + 86_400_000));
            assertEquals(1, world.periodicOf("pharma").orElseThrow().authorizations().size());
            tool(world, "frank", "publish", "periodic.publish", Map.of("companyPartyId", "pharma", "period", 1,
                    "draftId", draft.draftId(), "draftVersion", 1));
            assertEquals(1, world.periodicOf("pharma").orElseThrow().releases().size());
            assertTrue(world.snapshot().information().orElseThrow().information().documents().stream()
                    .anyMatch(doc -> doc.docId().equals("periodic-public:pharma:1")
                            && doc.audience().kind() == InformationModel.AudienceKind.PUBLIC
                            && doc.versions().stream().anyMatch(version -> version.version() == 1)));
            assertTrue(world.periodicOf("north").orElseThrow().releases().isEmpty());
        }
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(file)) {
            assertEquals(1, world.periodicOf("pharma").orElseThrow().releases().size());
            assertTrue(ComposedCompanyReport.of(world, "pharma").balanced());
        }
    }

    private static void tool(WorldRuntime world, String person, String id, String tool, Map<String, ?> args) throws Exception {
        world.advanceTo(PharmaDecisionToolTest.reply(world, PharmaDecisionToolTest.open(world, person, id), tool, args));
        String result = world.taskResultForTest(DecisionRun.actionTaskId(id) + ":finish");
        assertEquals("ACCEPTED", result, tool + " " + id);
    }
}
