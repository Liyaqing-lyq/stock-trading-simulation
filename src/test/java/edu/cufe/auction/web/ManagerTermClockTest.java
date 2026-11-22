package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.RoleEvidenceDocuments;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.BaselineIndustryConfig;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ManagerTermClockTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMPANY = "aged-company";
    private static final String MANAGER = "aged-manager";
    private static final long START = 100_000L;
    private static final long TARGET = 200_000L;

    @Test void nativeFullRosterVisitsStartAndEffectiveEndOfALegallyRegisteredTerm() throws Exception {
        runWorld("thirty-seconds", 30_000L, true);
        runWorld("one-tenth-second", 100L, true);
    }

    @Test void oldProfileKeepsItsPreviousClockAndNeverDispatchesThatManager() throws Exception {
        runWorld("legacy", 30_000L, false);
    }

    @Test void actualEndAndEarlierTasksWinAndPastOrNonManagerTermsDoNotSchedule() {
        var revoked = new RoleAccess.Appointment("a", MANAGER, COMPANY, RoleAccess.Role.COMPANY_MANAGER, 100L, 200L, 130L);
        var simultaneous = new RoleAccess.Appointment("b", "hydro-manager", "hydro", RoleAccess.Role.COMPANY_MANAGER, 100L, 120L, null);
        var empty = new RoleAccess.Appointment("c", MANAGER, COMPANY, RoleAccess.Role.COMPANY_MANAGER, 101L, 140L, 101L);
        var researcher = new RoleAccess.Appointment("d", "ivan", COMPANY, RoleAccess.Role.RESEARCHER, 60L, 80L, null);
        var appointments = List.of(revoked, simultaneous, empty, researcher);
        assertEquals(90, WorldWebHost.nextManagerTermBoundary(appointments, 50, 90));
        assertEquals(100, WorldWebHost.nextManagerTermBoundary(appointments, 50, 300));
        assertEquals(120, WorldWebHost.nextManagerTermBoundary(appointments, 100, 300));
        assertEquals(130, WorldWebHost.nextManagerTermBoundary(appointments, 120, 300));
        assertEquals(300, WorldWebHost.nextManagerTermBoundary(appointments, 130, 300));
    }

    @Test void meetingShowsFiledAppointmentAndRevocationSeparatelyFromActiveOffice() throws Exception {
        var runtime = new AtomicReference<WorldRuntime>();
        try (var host = new WorldWebHost(() -> {
            var world = open(dir.resolve("proposal.journal"), true);
            runtime.set(world);
            return new WorldWebHost.Opened(world, world.profile().manifest().runId());
        })) {
            host.adminSnapshot();
            host.call(() -> {
                register(runtime.get(), START + 30_000);
                assertFalse(runtime.get().isActiveManager(COMPANY, MANAGER, 30_000));
                return null;
            });
            JsonNode company = JSON.valueToTree(host.engineeringView(COMPANY).meetings().rows().get(0));
            JsonNode proposal = company.path("appointmentRequest");
            assertEquals("APPOINT", proposal.path("action").asText());
            assertEquals(MANAGER, proposal.path("personPartyId").asText());
            assertEquals("100000", proposal.path("effectiveAt").textValue());
            assertEquals("130000", proposal.path("endsAt").textValue());
            JsonNode chair = JSON.valueToTree(host.personEngineering("aged-company-director-1").companies());
            assertEquals(proposal, chair.findValue("appointmentRequest"));
            JsonNode outsider = JSON.valueToTree(host.personEngineering("investor").companies());
            assertNull(outsider.findValue("appointmentRequest"));

            host.call(() -> {
                var world = runtime.get();
                String meeting = "term-revoke";
                String chairId = BaselineIndustryConfig.directors(COMPANY).get(0);
                world.scheduleCompanyBoardConvene("convene-revoke", "revoke-convene-task", START + 1000,
                        COMPANY, chairId, meeting, START + 2000, START + 3000, START + 4000, true);
                world.scheduleCompanyBoardAttend("attend-revoke", "revoke-attend-task", START + 1001,
                        COMPANY, chairId, meeting);
                world.scheduleCompanyBoardMaterial("revoke-material", "revoke-material-task", START + 1010,
                        COMPANY, chairId, meeting, "m-revoke", RoleEvidenceDocuments.documentId(COMPANY), 1);
                world.scheduleCompanyBoardAppointmentProposal("revoke-proposal", "revoke-proposal-task", START + 1020,
                        COMPANY, chairId, meeting, new BoardModel.AppointmentRequest("term-manager", MANAGER, COMPANY,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.REVOKE, START + 6000, null));
                world.advanceTo(START + 1020);
                assertTrue(world.isActiveManager(COMPANY, MANAGER, START + 1020));
                return null;
            });
            JsonNode revocation = JSON.valueToTree(host.engineeringView(COMPANY).meetings().rows().get(0));
            assertEquals("CONVENED", revocation.path("status").asText());
            assertEquals("REVOKE", revocation.at("/appointmentRequest/action").asText());
            assertEquals("106000", revocation.at("/appointmentRequest/effectiveAt").textValue());
            assertTrue(revocation.at("/appointmentRequest/endsAt").isNull());
        }
    }

    private void runWorld(String label, long duration, boolean tracked) throws Exception {
        Path runDir = Files.createDirectory(dir.resolve(label));
        Path journal = runDir.resolve("world.journal");
        String mode = tracked ? "BOARD_PROCEDURE_OPERATING" : "AGED_ORDER_OPERATING";
        var current = new AtomicReference<WorldRuntime>();
        var calls = new CopyOnWriteArrayList<JsonNode>();
        try (var routes = SceneRoutes.open(runDir)) {
            routes.replace(0, AutonomousLifecycleProbe.probeRoutes(routes.view(), mode));
            try (var host = new WorldWebHost(() -> {
                var world = open(journal, tracked);
                current.set(world);
                return new WorldWebHost.Opened(world, world.profile().manifest().runId());
            }, routes, (call, timeout) -> {
                try {
                    var input = JSON.readTree(call.inputText());
                    calls.add(input);
                    var reply = JSON.createObjectNode();
                    reply.put("tool", "role.wait");
                    reply.putObject("decision").put("objective", input.at("/engineering/deliberation/objectives/0/id").asText())
                            .put("basis", "离线测试：本人选择次日复查").put("reviewAfterMillis", 86_400_000L);
                    return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(), call.actionId(),
                            call.attemptId(), call.attemptNo(), call.recoveryGeneration(), reply.toString(),
                            DecisionModel.Usage.unknownCost(1, 1, 1), "fixture:manager-term-clock"));
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            })) {
                host.adminSnapshot();
                long initialPosition = host.call(() -> {
                    register(current.get(), START + duration);
                    return current.get().snapshot().position();
                });
                host.startAutonomy(initialPosition, routes.view().revision(), TARGET, 3_600_000);
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
                WorldWebHost.AutonomyStatus result;
                do {
                    result = host.autonomyStatus();
                    if (!result.running()) break;
                    assertTrue(System.nanoTime() < deadline, "离线全员轮未到目标");
                    Thread.sleep(15);
                } while (true);
                assertEquals("TARGET_REACHED", result.reason());
                assertEquals(0L, result.failures());
                assertEquals(52, result.persons());
                assertEquals(52, calls.stream().filter(c -> c.path("time").asLong() == 30_000)
                        .map(c -> c.path("person").asText()).distinct().count());
                var own = calls.stream().filter(c -> MANAGER.equals(c.path("person").asText())).toList();
                if (tracked) {
                    assertEquals(3L, result.cycles());
                    assertEquals(List.of(START), own.stream().map(c -> c.path("time").asLong()).toList());
                    assertTrue(own.get(0).path("tools").toString().contains("aged.act"));
                    assertEquals(COMPANY, own.get(0).at("/engineering/managerships/0/companyPartyId").asText());
                } else {
                    assertTrue(own.isEmpty());
                    assertEquals(1L, result.cycles());
                }
            }
        }
        try (var w = open(journal, tracked)) {
            assertEquals(TARGET, w.snapshot().timeMillis());
            assertTrue(w.isActiveManager(COMPANY, MANAGER, START));
            assertFalse(w.isActiveManager(COMPANY, MANAGER, START + duration));
            assertTrue(w.autonomyRoleObservation(MANAGER).at("/engineering/managerships").isEmpty());
        }
    }

    private static WorldRuntime open(Path journal, boolean tracked) throws Exception {
        return tracked ? ComposedIndustryScenario.openBoardProcedureOperating(journal)
                : ComposedIndustryScenario.openAgedOrderOperating(journal);
    }

    private static void register(WorldRuntime w, long end) throws Exception {
        var ds = BaselineIndustryConfig.directors(COMPANY);
        String chair = ds.get(0), doc = RoleEvidenceDocuments.documentId(COMPANY), meeting = "term-meeting";
        for (int i = 0; i < ds.size(); i++)
            w.scheduleStartReading("read-" + i, "read-task-" + i, 100 + i, ds.get(i), doc, 1);
        long t = 16_000L;
        w.scheduleCompanyBoardConvene("convene", "convene-task", t, COMPANY, chair, meeting,
                t + 50, t + 100, t + 3_000, true);
        for (int i = 0; i < ds.size(); i++)
            w.scheduleCompanyBoardAttend("attend-" + i, "attend-task-" + i, t + 10 + i, COMPANY, ds.get(i), meeting);
        w.scheduleCompanyBoardMaterial("material", "material-task", t + 30, COMPANY, chair, meeting, "m", doc, 1);
        w.scheduleCompanyBoardAppointmentProposal("propose", "propose-task", t + 40, COMPANY, chair, meeting,
                new BoardModel.AppointmentRequest("term-manager", MANAGER, COMPANY, RoleAccess.Role.COMPANY_MANAGER,
                        BoardModel.AppointmentAction.APPOINT, START, end));
        for (int i = 0; i < ds.size(); i++)
            w.scheduleCompanyBoardVote("vote-" + i, "vote-task-" + i, t + 2_000 + i, COMPANY, ds.get(i), meeting,
                    BoardModel.Vote.YES, 1_000);
        w.scheduleCompanyBoardImplement("implement", "implement-task", t + 5_000, COMPANY, chair, meeting);
        w.advanceTo(30_000L);
        assertEquals(BoardModel.MeetingStatus.IMPLEMENTED,
                w.boardOfCompany(COMPANY).orElseThrow().board().meetings().get(meeting).status());
    }
}
