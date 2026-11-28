package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class InterviewOfficeBudgetViewTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    private WorldRuntime.OfficeWorkBudget own(WorldRuntime w, String who) throws Exception {
        var budget = w.interviewView(who).officeWorkBudgets();
        assertNotNull(budget);
        assertEquals(1, budget.offices().size());
        assertEquals(JSON.writeValueAsString(budget), w.autonomyRoleObservation(who).at("/interview/officeWorkBudgets").toString());
        return budget.offices().get(0);
    }

    private void absent(WorldRuntime w, String who) throws Exception {
        var api = JSON.valueToTree(w.interviewView(who));
        assertFalse(api.has("officeWorkBudgets"));
        assertTrue(w.autonomyRoleObservation(who).at("/interview/officeWorkBudgets").isMissingNode());
    }

    @Test void eightOfficesSeeOnlyTheirOwnActiveAppointmentsIncludingAfterReopen() throws Exception {
        var file = dir.resolve("v7.journal");
        try (var w = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            var pairs = List.of(List.of("wire", "rita", "ed"), List.of("investigative", "sam", "bea"),
                    List.of("markets-desk", "investor", "kate"), List.of("aggregator-desk", "nina", "omar"),
                    List.of("independent-desk", "mfgversa-mgr", "erin"), List.of("vertical-desk", "mfgtech-mgr", "mfgstruct-mgr"),
                    List.of("local-desk", "hydro-manager", "resource-manager"), List.of("cross-border-desk", "digital-manager", "aged-manager"));
            for (var pair : pairs) {
                var office = pair.get(0);
                var policy = w.interviewRun().stateOf(office).policy();
                var authoritative = w.snapshot().media().orElseThrow().budgetOf(office);
                for (String person : pair.subList(1, 3)) {
                    var b = own(w, person);
                    assertEquals(office, b.organizationPartyId());
                    assertEquals(authoritative.totalMillis(), b.totalMillis());
                    assertEquals(b.totalMillis(), b.availableMillis());
                    assertEquals(policy.maxDurationMillis(), b.maxDurationMillis());
                    assertEquals(policy.minLeadMillis(), b.minLeadMillis());
                    assertEquals(policy.inviteTtlMillis(), b.inviteTtlMillis());
                }
            }
            absent(w, "bob");
            var appt = w.openRoleSession("rita").view().appointments().stream()
                    .filter(a -> a.role() == RoleAccess.Role.JOURNALIST).findFirst().orElseThrow();
            long due = w.snapshot().timeMillis()+1;
            w.scheduleRevokeAppointment("remove", "remove-task", due, appt.appointmentId());
            w.advanceTo(due);
            absent(w, "rita");
            assertEquals("wire", own(w, "ed").organizationPartyId());
        }
        byte[] bytes = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            absent(w, "rita");
            assertEquals("wire", own(w, "ed").organizationPartyId());
        }
        assertArrayEquals(bytes, Files.readAllBytes(file));
        try (var w = ComposedIndustryScenario.openPersonalTenCompanyStudy(dir.resolve("v6.journal"))) {
            absent(w, "sam");
        }
    }

    private String invite(WorldRuntime w, String id, long time, long duration) throws Exception {
        var config = w.interviewRun().config();
        long due = w.snapshot().timeMillis() + config.inviteMillis();
        w.openRoleSession("sam").inviteInterview("invite-"+id, "task-invite-"+id, due,
                new WorldRoleSession.InterviewInviteDraft(id, "investor", "采访预约", time, time+duration+1000, duration));
        w.advanceTo(due);
        String request = InterviewModel.requestId("sam", id);
        assertEquals("PROPOSED", w.interviewView("investor").invites().stream().filter(i->i.requestId().equals(request)).findFirst().orElseThrow().status());
        return request;
    }

    private InterviewRun.Coordination accept(WorldRuntime w, String id, String request, long start) throws Exception {
        long due = w.snapshot().timeMillis()+w.interviewRun().config().respondMillis();
        w.openRoleSession("investor").respondInterview("accept-"+id, "task-accept-"+id, due,
                new WorldRoleSession.InterviewRespondDraft(request, true, start));
        w.advanceTo(due);
        return w.interviewRun().coordinations().values().stream()
                .filter(r->r.requestId().equals(request) && r.timeMillis()==due).findFirst().orElseThrow();
    }

    @Test void calendarReleasesOnlyEarnedShiftsWhileMediaAndInterviewsShareReservations() throws Exception {
        var file = dir.resolve("calendar.journal");
        long day = 86_400_000L;
        long appointment = day + 90_000L;
        long reserved;
        try (var w = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            var initial = own(w, "sam");
            assertEquals(480_000L, initial.totalMillis());
            assertEquals(260, initial.schedule().contractedShifts());
            assertEquals(124_800_000L, initial.schedule().contractedMillis());
            assertEquals(1, initial.schedule().releasedShifts());
            long readAt = w.snapshot().timeMillis() + 1;
            w.openRoleSession("sam").startReading("read", "read", readAt, "contact:investor-public", 1);
            w.advanceTo(readAt);
            w.advanceTo(w.roleView("sam").readings().get(0).dueAt());
            w.advanceTo(day - 160_000);
            var excessive = invite(w, "too-large", appointment, 600_000);
            assertEquals("BUDGET", accept(w, "too-large", excessive, appointment).reason());
            long topicMillis = w.snapshot().media().orElseThrow().config().proposeMillis();
            reserved = own(w, "sam").availableMillis() - w.interviewRun().config().inviteMillis() - (topicMillis - 1);
            var request = invite(w, "book", appointment, reserved);
            assertTrue(accept(w, "book", request, appointment).accepted());
            assertEquals(topicMillis - 1, own(w, "bea").availableMillis());
            long due = w.snapshot().timeMillis() + topicMillis;
            w.openRoleSession("sam").proposeStory("compete", "compete", due,
                    new WorldRoleSession.MediaProposeDraft("compete", "INVESTIGATIVE", "核对次日交付"));
            w.advanceTo(due);
            assertTrue(w.snapshot().media().orElseThrow().reports().values().stream().anyMatch(r -> "BUDGET".equals(r.reason())));
            assertEquals(reserved, own(w, "bea").interviewReservedMillis());

            w.advanceTo(day - 1);
            w.openRoleSession("rita").proposeStory("overnight", "overnight", day - 1 + topicMillis,
                    new WorldRoleSession.MediaProposeDraft("overnight", "WIRE", "跨日报价记录"));
            var pending = own(w, "ed");
            assertEquals(topicMillis, pending.mediaReservedMillis());
            w.advanceTo(day);
            assertEquals(480_000L - topicMillis, own(w, "ed").availableMillis());
            assertEquals(2, own(w, "ed").schedule().releasedShifts());
            assertEquals(960_000L, own(w, "sam").totalMillis());
            assertEquals(reserved, own(w, "sam").interviewReservedMillis());
        }
        try (var w = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            var held = own(w, "bea");
            assertEquals(reserved, held.interviewReservedMillis());
            w.advanceTo(appointment + 60_000);
            assertEquals(0, own(w, "sam").interviewReservedMillis());
            assertEquals(held.spentMillis() + 60_000, own(w, "sam").spentMillis());
            assertEquals(0, own(w, "ed").mediaReservedMillis());
            assertEquals(w.snapshot().media().orElseThrow().config().proposeMillis(), own(w, "ed").spentMillis());
            w.advanceTo(4 * day);
            var friday = own(w, "sam");
            w.advanceTo(6 * day);
            assertEquals(friday, own(w, "sam"));
            w.advanceTo(7 * day);
            assertEquals(friday.totalMillis() + 480_000, own(w, "sam").totalMillis());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {60_000L, 120_000L})
    void reservedWorkUsesExistingLedgerAndOutsiderNeverAcquiresTheInvitersBudget(long duration) throws Exception {
        Path file = dir.resolve("reservation");
        long appointment;
        WorldRuntime.OfficeWorkBudget settled;
        try (var w = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            long now=w.snapshot().timeMillis(), propose=w.snapshot().media().orElseThrow().config().proposeMillis();
            var initial=own(w,"sam");
            w.openRoleSession("sam").proposeStory("topic","topic-task",now+propose,
                    new WorldRoleSession.MediaProposeDraft("topic", "INVESTIGATIVE", "研发收款"));
            var pending=own(w,"bea");
            assertEquals(propose,pending.mediaReservedMillis());
            assertEquals(initial.availableMillis()-propose,pending.availableMillis());
            w.advanceTo(now+propose);
            var charged=own(w,"sam");
            assertEquals(0,charged.mediaReservedMillis());
            assertEquals(propose,charged.spentMillis());

            long readStart=w.snapshot().timeMillis()+1;
            w.openRoleSession("sam").startReading("read-contact","read-task",readStart,"contact:investor-public",1);
            w.advanceTo(readStart);
            w.advanceTo(w.roleView("sam").readings().get(0).dueAt());
            appointment = w.snapshot().timeMillis() + 1_000_000;
            var tooLong=invite(w,"long",appointment,1_800_000);
            var beforeRejection=own(w,"sam");
            assertEquals("BUDGET",accept(w,"long",tooLong,appointment).reason());
            assertEquals(beforeRejection,own(w,"bea"));
            assertEquals("markets-desk",own(w,"investor").organizationPartyId());

            var shorter=invite(w,"short",appointment,duration);
            var before=own(w,"sam");
            assertTrue(accept(w,"short",shorter,appointment).accepted());
            var after=own(w,"bea");
            assertEquals(duration,after.interviewReservedMillis());
            assertEquals(before.availableMillis()-duration,after.availableMillis());
            assertEquals(before.spentMillis(),after.spentMillis());
            assertEquals(0,own(w,"investor").interviewReservedMillis());
            assertTrue(w.interviewView("investor").invites().stream().anyMatch(i->i.requestId().equals(shorter)&&i.status().equals("ACCEPTED")));
            w.advanceTo(appointment + 60_000);
            settled = own(w, "sam");
            assertEquals(0, settled.interviewReservedMillis());
            assertEquals(initial.totalMillis(), settled.totalMillis());
            assertEquals(before.spentMillis() + 60_000L, settled.spentMillis());
            assertEquals(settled.totalMillis() - settled.spentMillis(), settled.availableMillis());
            if (duration > 60_000L) {
                assertTrue(w.interviewView("sam").invites().stream().anyMatch(i ->
                        i.requestId().equals(shorter) && i.status().equals("ENDED_EARLY")));
            }
        }
        try (var w = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            assertEquals(settled, own(w, "bea"));
            w.advanceTo(appointment + duration + 1);
            var works = w.snapshot().media().orElseThrow().worksOf("investigative").stream()
                    .filter(work -> work.kind().equals(MediaRun.KIND_INTERVIEW_SESSION)).toList();
            assertEquals(1, works.size());
            assertEquals(60_000L, works.get(0).chargedMillis());
            assertEquals(settled, own(w, "sam"));
            w.advanceTo(86_400_001L);
            assertEquals(settled, own(w, "bea"));
        }
    }
}
