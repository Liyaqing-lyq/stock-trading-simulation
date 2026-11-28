package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.bank.BankCrisis;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CrisisOfficeObjectivesTest {
    @TempDir Path dir;
    static final String RESCUE = "appointment:rescue-representative", RESOLUTION = "appointment:resolution-officer";

    private static Set<String> goals(JsonNode input) {
        var ids = new HashSet<String>();
        for (var row : input.at("/engineering/deliberation/objectives"))
            assertTrue(ids.add(row.required("id").textValue()), "同一职责目标不应重复");
        return ids;
    }

    @Test void openingObjectivesMatchChartersAndEveryActiveAppointmentForAllSixtyOnePeople() throws Exception {
        try (var world = ComposedIndustryScenario.openPersonalTenCompanyStudy(dir.resolve("opening.journal"))) {
            var registry = world.openingRegistry().orElseThrow();
            var snapshot = world.snapshot();
            var offices = snapshot.roles().orElseThrow().access().appointments();
            int people = 0, seats = 0, appointments = 0;
            for (var party : registry.parties()) {
                if (party.kind() != OpeningRegistry.PartyKind.PERSON) continue;
                String person = party.partyId();
                var actual = goals(world.autonomyRoleObservation(person));
                if (WorldWebScenario.RESEARCH_SYSTEM_PUBLISHER.equals(person)) {
                    assertTrue(actual.stream().noneMatch(id -> id.startsWith("appointment:") || id.startsWith("director:") || id.startsWith("manager:")));
                    continue;
                }
                people++;
                var expected = new HashSet<>(Set.of("person:" + person));
                for (var security : registry.securities()) {
                    String company = security.issuerOrganizationId();
                    if (world.boardOfCompany(company).orElseThrow().board().charter().seatOf(person) != null) {
                        expected.add("director:" + company);
                        seats++;
                    }
                    if (world.isActiveManager(company, person, snapshot.timeMillis())) expected.add("manager:" + company);
                }
                for (var office : offices) {
                    if (!person.equals(office.personPartyId()) || !office.activeAt(snapshot.timeMillis())) continue;
                    appointments++;
                    if (office.role() == RoleAccess.Role.COMPANY_MANAGER
                            && world.boardOfCompany(office.organizationPartyId()).isPresent()) {
                        expected.add("manager:" + office.organizationPartyId());
                    } else {
                        expected.add("appointment:" + office.id());
                    }
                }
                assertEquals(expected, actual, person);
            }
            assertEquals(61, people);
            assertEquals(50, seats);
            assertEquals(22, appointments);
            assertEquals(snapshot.position(), world.snapshot().position());
            assertTrue(world.snapshot().decisions().orElseThrow().state().decisions().isEmpty());
        }
    }

    private static String bank(WorldRuntime w, String person, BankActions.Kind kind, String org,
                               String amount, String loanId) throws Exception {
        long due = w.snapshot().timeMillis() + 3;
        String id = "office-" + w.snapshot().position();
        var draft = new WorldRoleSession.BankActionDraft(id, kind, "north", org, new BigDecimal(amount),
                20_000, IssuerCreditDecisionTest.TERM, "营运", "", "", loanId);
        w.openRoleSession(person).bankAction(id, id, due, 1, draft);
        w.advanceTo(due);
        return w.taskCommitResult(WorldRuntime.roleTaskId(person, id) + ":finish");
    }

    static void appoint(WorldRuntime w, String company, String manager) throws Exception {
        var charter = w.boardOfCompany(company).orElseThrow().board().charter();
        String chair = charter.seats().stream().filter(s -> s.seatId().equals(charter.chairSeatId())).findFirst().orElseThrow().partyId();
        var voters = charter.seats().stream().map(BoardModel.Seat::partyId).filter(p -> !manager.equals(p)).toList();
        String document = "company-governance:" + company + ":1", meeting = "appoint-" + company;
        long start = w.snapshot().timeMillis() + 10;
        for (var voter : voters) w.scheduleStartReading(meeting + voter, meeting + "-read-" + voter,
                start, voter, document, 1);
        long t = start + 3_600_000L;
        w.advanceTo(t);
        w.scheduleCompanyBoardConvene(meeting, meeting + "-call", t + 10, company, chair, meeting, t + 100, t + 200, t + 3_000, true);
        for (var voter : voters) w.scheduleCompanyBoardAttend(meeting + "-a-" + voter, meeting + "-attend-" + voter, t + 30, company, voter, meeting);
        w.scheduleCompanyBoardMaterial(meeting + "-m", meeting + "-material", t + 50, company, chair, meeting, "m", document, 1);
        w.scheduleCompanyBoardAppointmentProposal(meeting + "-p", meeting + "-proposal", t + 70, company, chair, meeting,
                new BoardModel.AppointmentRequest(meeting + "-term", manager, company, RoleAccess.Role.COMPANY_MANAGER,
                        BoardModel.AppointmentAction.APPOINT, t + 5_000, t + 86_400_000));
        for (var voter : voters) w.scheduleCompanyBoardVote(meeting + "-v-" + voter, meeting + "-vote-" + voter,
                t + 1000, company, voter, meeting, BoardModel.Vote.YES, 100);
        w.scheduleCompanyBoardImplement(meeting + "-i", meeting + "-implement", t + 4_000, company, chair, meeting);
        w.advanceTo(t + 5_000);
        assertTrue(w.isActiveManager(company, manager, w.snapshot().timeMillis()));
    }

    @Test void officeMandatesRemainIndependentOfDistressAndFollowBothRevocations() throws Exception {
        var file = dir.resolve("offices.journal");
        try (var w = ComposedIndustryScenario.openPersonalTenCompanyStudy(file)) {
            var initial = w.snapshot().accounts();
            assertTrue(goals(w.autonomyRoleObservation("aged-manager")).contains(RESCUE));
            assertTrue(goals(w.autonomyRoleObservation("kate")).contains(RESOLUTION));
            assertEquals("OK", bank(w, "aged-manager", BankActions.Kind.FACILITY_AUTHORIZE, BankCrisis.PROVIDER, "12.34", "line"));
            assertEquals(initial, w.snapshot().accounts());
            assertEquals("RESOLUTION_TRIGGER_ABSENT", bank(w, "kate", BankActions.Kind.RESOLUTION_OPEN, BankCrisis.AUTHORITY, "0", ""));
            String borrower = IssuerCreditDecisionTest.BORROWER;
            appoint(w, "north", "erin");
            appoint(w, "mfg-tech", borrower);
            assertTrue(goals(w.autonomyRoleObservation("erin")).contains("manager:north"));
            for (var kind : java.util.List.of(BankActions.Kind.LOAN_REQUEST, BankActions.Kind.LOAN_ACCEPT,
                    BankActions.Kind.LOAN_APPROVE, BankActions.Kind.LOAN_DRAW)) {
                assertEquals("OK", bank(w, kind == BankActions.Kind.LOAN_APPROVE ? "erin" : borrower,
                        kind, "mfg-tech", "4000", "operating-credit"));
            }
            var bankState = w.bankOf().orElseThrow().decode(w.snapshot().accounts()).state();
            String reserve = bankState.chart().reserveAccountOf("north");
            assertEquals("OK", bank(w, borrower, BankActions.Kind.WITHDRAW, "mfg-tech",
                    w.snapshot().accounts().get(reserve).cash().toPlainString(), ""));
            assertEquals("OK", bank(w, "kate", BankActions.Kind.RESOLUTION_OPEN, BankCrisis.AUTHORITY, "0", ""));
            assertTrue(w.autonomyRoleObservation("kate").at("/engineering/issuerCredit/crisisOffices/0/resolutionOpen").booleanValue());
            assertTrue(goals(w.autonomyRoleObservation("kate")).contains(RESOLUTION));
            assertTrue(goals(w.autonomyRoleObservation("aged-manager")).contains(RESCUE));
            assertNotEquals("OK", bank(w, "aged-manager", BankActions.Kind.FACILITY_AUTHORIZE, BankCrisis.PROVIDER, "1", "late-line"));
            long due = w.snapshot().timeMillis() + 1;
            w.scheduleRevokeAppointment("end-rescue", "end-rescue", due, "rescue-representative");
            w.scheduleRevokeAppointment("end-resolution", "end-resolution", due, "resolution-officer");
            w.advanceTo(due);
        }
        try (var reopened = ComposedIndustryScenario.openPersonalTenCompanyStudy(file)) {
            for (String person : java.util.List.of("kate", "aged-manager")) {
                var view = reopened.autonomyRoleObservation(person);
                assertFalse(goals(view).contains(person.equals("kate") ? RESOLUTION : RESCUE));
                assertFalse(IssuerCreditDecisionTest.tool(view, "bank.crisis.act"));
                assertTrue(goals(view).stream().anyMatch(id -> id.startsWith("appointment:editor:")));
            }
        }
    }

    @Test void readingCanUseThePublishedRescueObjectiveAndPriorWorldStillHasOnlyItsTwoGoals() throws Exception {
        var file = dir.resolve("read.journal");
        String person = "aged-manager", doc = "company-governance:north:1";
        try (var old = ComposedIndustryScenario.openEditorialTenCompanyStudy(dir.resolve("older.journal"))) {
            assertEquals(Set.of("person:aged-manager", "appointment:editor:cross-border-desk"),
                    goals(old.autonomyRoleObservation(person)));
        }
        try (var w = ComposedIndustryScenario.openPersonalTenCompanyStudy(file)) {
            var call = PharmaDecisionToolTest.open(w, person, "rescue-read");
            var input = IssuerCreditDecisionTest.JSON.readTree(call.inputText());
            assertTrue(goals(input).contains(RESCUE));
            var reply = IssuerCreditDecisionTest.JSON.createObjectNode().put("tool", "role.reading.start");
            reply.putObject("args").put("docId", doc).put("version", 1);
            reply.putObject("decision").put("objective", RESCUE).put("basis", "先读现有章程，再评估本职风险").put("reviewAfterMillis", 90_000L);
            var response = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                    call.recoveryGeneration(), reply.toString(), DecisionModel.Usage.unknownCost(1, 100, 30), "fixture:role-objective");
            assertEquals("QUEUED", w.offerDecisionCallback(response));
            assertEquals(1, w.drainDecisionCallbacks());
            w.advanceTo(86_400_000L);
            assertTrue(w.roleView(person).observations().stream().anyMatch(o -> o.ref().docId().equals(doc)));
        }
        try (var w = ComposedIndustryScenario.openPersonalTenCompanyStudy(file)) {
            assertTrue(w.roleView(person).observations().stream().anyMatch(o -> o.ref().docId().equals(doc)));
            assertEquals(1, w.snapshot().decisions().orElseThrow().state().decisions().size());
        }
    }
}
