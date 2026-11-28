package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.WorldRoleSession;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class InterviewScheduleViewTest {
    @TempDir Path dir;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aScheduledEndRequiresAnAgreedStartInTheNewOpening(boolean current) throws Exception {
        var file = dir.resolve("world.journal");
        try (var world = current ? ComposedIndustryScenario.openInterviewTenCompanyStudy(file)
                : ComposedIndustryScenario.openPersonalTenCompanyStudy(file)) {
            var reporter = world.openRoleSession("rita");
            long readAt = world.snapshot().timeMillis() + 1;
            reporter.startReading("read", "read", readAt, "contact:investor-public", 1);
            world.advanceTo(readAt);
            world.advanceTo(world.roleView("rita").readings().get(0).dueAt());
            long inviteDue = world.snapshot().timeMillis() + 1000;
            long start = inviteDue + 300000;
            world.openRoleSession("rita").inviteInterview("invite", "invite", inviteDue,
                    new WorldRoleSession.InterviewInviteDraft("coverage", "investor", "核实公开说明",
                            start, start + 10000, 5000));
            world.advanceTo(inviteDue);
            JsonNode proposed = world.autonomyRoleObservation("investor").at("/interview/invites/0");
            assertEquals("PROPOSED", proposed.required("status").asText());
            assertEquals(-1L, proposed.required("startMillis").asLong());
            if (current) assertFalse(proposed.has("scheduledEndMillis"));
            else assertEquals(4999L, proposed.required("scheduledEndMillis").asLong());
            world.openRoleSession("investor").respondInterview("respond", "respond", inviteDue + 1000,
                    new WorldRoleSession.InterviewRespondDraft(proposed.required("requestId").asText(), true, start));
            world.advanceTo(inviteDue + 1000);
            for (var person : java.util.List.of("rita", "investor")) {
                var accepted = world.autonomyRoleObservation(person).at("/interview/invites/0");
                assertEquals("ACCEPTED", accepted.required("status").asText());
                assertEquals(start + 5000L, accepted.required("scheduledEndMillis").asLong());
            }
        }
        try (var world = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            assertEquals("ACCEPTED", world.autonomyRoleObservation("investor").at("/interview/invites/0/status").asText());
        }
    }
}
