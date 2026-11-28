package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InterviewExecutionWindowTest {
    @TempDir Path temp;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void proposalUsesCompletionClockAndExplainsOwnWindowReject() throws Exception {
        for (long start : new long[]{252401, 254400, 254401}) {
            Path journal = temp.resolve(start + ".journal");
            try (var world = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(journal)) {
                String actor = "digital-manager", doc = WorldWebScenario.INTERVIEW_CONTACT_DOC;
                long readAt=world.snapshot().timeMillis()+1;
                world.openRoleSession(actor).startReading("read", "read", readAt, doc, 1);
                world.advanceTo(readAt);
                world.advanceTo(world.roleView(actor).readings().get(0).dueAt());
                world.advanceTo(192401);
                var observation = world.autonomyRoleObservation(actor);
                var clock = observation.at("/interview/inviteWindow");
                assertEquals("cross-border-desk", clock.required("organizationPartyId").asText());
                assertEquals(194401, clock.required("executionMillis").longValue());
                assertEquals(254401, clock.required("earliestWindowStartMillis").longValue());
                assertTrue(world.autonomyRoleObservation("alice").at("/interview/inviteWindow").isMissingNode());
                assertEquals("markets-desk",world.autonomyRoleObservation("investor").at("/interview/inviteWindow/organizationPartyId").asText());
                var args = JSON.createObjectNode().put("requestClientId", "edge").put("toPartyId", "investor")
                        .put("topic", "核查试算").put("windowStartMillis", start).put("windowEndMillis", 7452401)
                        .put("durationMillis", 300000);
                var ref = PersonalMarketTest.decide(world, actor, "decision","interview.invite",args);
                assertEquals(194401, ref.dueMillis());
                world.advanceTo(ref.dueMillis());
                var row = world.autonomyRoleObservation(actor).at("/engineering/deliberation/lastActionResult");
                assertEquals(start == 254401, row.required("accepted").asBoolean());
                if (start < 254401) {
                    assertEquals("WINDOW", row.required("reason").asText());
                    assertEquals(254401, row.at("/windowTiming/earliestStartMillis").longValue());
                    assertEquals(start, row.at("/windowTiming/requestedStartMillis").longValue());
                    assertEquals(254401 - start, row.at("/windowTiming/outsideByMillis").longValue());
                    assertTrue(row.required("reasonDetail").asText().contains("执行时刻"));
                } else {
                    assertEquals(1, world.interviewView("investor").invites().size());
                }
            }
            try (var reopened = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(journal)) {
                assertEquals(194401, reopened.snapshot().timeMillis());
                assertEquals(start == 254401, reopened.autonomyRoleObservation("digital-manager")
                        .at("/engineering/deliberation/lastActionResult/accepted").booleanValue());
            }
        }
        try (var old=ComposedIndustryScenario.openDigitalWindDownTenCompanyStudy(temp.resolve("old.journal"))) {
            assertTrue(old.autonomyRoleObservation("digital-manager").at("/interview/inviteWindow").isMissingNode());
            assertNull(old.personalInterviewControls("digital-manager"));
        }
    }
}
