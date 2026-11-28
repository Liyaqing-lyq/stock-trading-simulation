package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.WorldRuntime;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

class PeriodicMeetingMaterialViewTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DAY = 86_400_000L;
    private static final String DIRECTOR = STRUCT_DIRECTORS.get(1);
    @TempDir Path directory;

    private static String prepare(WorldRuntime world) throws Exception {
        IssuerPeriodEvidenceTest.govern(world);
        world.advanceTo(30 * DAY);
        var snapshot = world.periodicOf(STRUCT).orElseThrow().snapshots().get(0);
        var meeting = world.boardOfCompany(STRUCT).orElseThrow().board().meeting(snapshot.meetingId());
        world.scheduleCompanyBoardAttend("attend-period", "attend-task", 30*DAY+100, STRUCT, DIRECTOR, snapshot.meetingId());
        world.advanceTo(meeting.opensAt()+1);
        long finish = world.snapshot().timeMillis()+2000;
        world.schedulePeriodicAction("prepare", "prepare-task", finish, STRUCT, STRUCT_MANAGER,
                "PREPARE", 1, "", 0, "", 0, "", List.of(), "", 1000);
        world.advanceTo(finish);
        return snapshot.materialDocId();
    }

    private static JsonNode meeting(JsonNode view, String id) {
        for (var director : view.at("/engineering/directorships"))
            for (var row : director.path("meetings")) if (id.equals(row.path("meetingId").textValue())) return row;
        return null;
    }

    @Test void periodMeetingUsesPublishedVersionThroughoutItsReadersOwnHistory() throws Exception {
        var file = directory.resolve("current.journal");
        String id = "periodic-meeting:"+STRUCT+":1";
        try (var world = ComposedIndustryScenario.openMeetingTenCompanyStudy(file)) {
            String docId = prepare(world);
            var call = PharmaDecisionToolTest.open(world, DIRECTOR, "read-period-material");
            var input = JSON.readTree(call.inputText());
            var period = meeting(input, id);
            var material = period.at("/materials/0");
            assertTrue(input.at("/engineering/directorships/0/periodic/0/materialReadable").booleanValue());
            assertEquals(docId, material.required("docId").textValue());
            assertTrue(material.required("readable").booleanValue());
            assertFalse(material.required("read").booleanValue());
            assertEquals("PERIOD_REVIEW", period.required("window").textValue());
            assertTrue(period.required("periodReviewProcedure").textValue().contains("periodic.score"));
            assertEquals(JSON.readTree("[\"WRONG_TOPIC\"]"), period.at("/planningPrerequisites/board.vote/blockers"));
            assertEquals("BLOCKED", period.at("/planningPrerequisites/board.implement/status").textValue());
            var ordinary = meeting(input, "appointment");
            assertFalse(ordinary.has("periodReviewProcedure"));
            assertEquals("m1", ordinary.at("/materials/0/materialId").textValue());
            assertEquals(CompanyOpeningDocuments.documentId(STRUCT), ordinary.at("/materials/0/docId").textValue());
            assertFalse(ordinary.at("/materials/0").has("readable"));
            assertTrue(world.canReadDocumentVersion(DIRECTOR, docId, 1));
            assertFalse(world.canReadDocumentVersion("alice", docId, 1));
            assertNull(meeting(world.autonomyRoleObservation("alice"), id));
            var document = world.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(v -> docId.equals(v.docId())).findFirst().orElseThrow().at(1);
            var body = JSON.createObjectNode().put("tool", "role.reading.start");
            body.putObject("args").put("docId", material.required("docId").textValue()).put("version", material.required("version").longValue());
            body.putObject("decision").put("objective", "director:"+STRUCT).put("basis", "核对会议已登记的本期材料").put("reviewAfterMillis", 60000);
            var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                    body.toString(), DecisionModel.Usage.unknownCost(1, 0, 0), "fixture:period-material");
            assertEquals("QUEUED", world.offerDecisionCallback(reply));
            assertEquals(1, world.drainDecisionCallbacks());
            var action = world.snapshot().decisions().orElseThrow().actionRef(call.actionId());
            assertNotNull(action);
            world.advanceTo(action.dueMillis()+document.readDurationMillis());
            var after = meeting(world.autonomyRoleObservation(DIRECTOR), id).at("/materials/0");
            assertTrue(after.required("read").booleanValue());
            assertTrue(after.required("recalled").booleanValue());
            assertFalse(meeting(world.autonomyRoleObservation(STRUCT_DIRECTORS.get(2)), id).at("/materials/0/read").booleanValue());
            world.advanceTo(110*DAY);
            var forgotten = meeting(world.autonomyRoleObservation(DIRECTOR), id).at("/materials/0");
            assertTrue(forgotten.required("read").booleanValue());
            assertFalse(forgotten.required("recalled").booleanValue());
        }
        try (var world = ComposedIndustryScenario.openMeetingTenCompanyStudy(file)) {
            var ref = meeting(world.autonomyRoleObservation(DIRECTOR), id).at("/materials/0");
            assertTrue(ref.required("readable").booleanValue() && ref.required("read").booleanValue());
            assertFalse(ref.required("recalled").booleanValue());
        }
    }

    @Test void priorMeetingKeepsItsOriginalObservationOnReopen() throws Exception {
        var file = directory.resolve("prior.journal");
        String id = "periodic-meeting:"+STRUCT+":1";
        JsonNode original;
        try (var world = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            prepare(world);
            original = meeting(world.autonomyRoleObservation(DIRECTOR), id);
            assertEquals("periodic-source:"+STRUCT+":1", original.at("/materials/0/docId").textValue());
            assertFalse(original.at("/materials/0").has("readable"));
            assertEquals("VOTING", original.required("window").textValue());
        }
        try (var world = ComposedIndustryScenario.openMeetingTenCompanyStudy(file)) {
            assertEquals(original, meeting(world.autonomyRoleObservation(DIRECTOR), id));
        }
    }
}
