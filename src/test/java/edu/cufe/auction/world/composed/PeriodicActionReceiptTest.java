package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.WorldRuntime;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

class PeriodicActionReceiptTest {
    @TempDir Path directory;
    private static final long DAY = 86_400_000L;
    private static final String DIRECTOR = STRUCT_DIRECTORS.get(1);

    private static JsonNode history(WorldRuntime world, String person, String id) throws Exception {
        var view = world.autonomyRoleObservation(person);
        for (var row : view.at("/engineering/deliberation/recentDecisions"))
            if (id.equals(row.path("actionId").textValue())) return row;
        throw new AssertionError(id);
    }

    private static DecisionRun.ActionRef choose(WorldRuntime w, String person, String id, String tool,
                                               String objective, ObjectNode args) throws Exception {
        var call = PharmaDecisionToolTest.open(w, person, id);
        var body = IssuerCreditDecisionTest.JSON.createObjectNode().put("tool", tool);
        body.set("args", args);
        body.putObject("decision").put("objective", objective).put("basis", "检查本次期间动作的本人回执").put("reviewAfterMillis", 60_000);
        var reply = new DecisionModel.Reply(call.runId(), id, call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                body.toString(), DecisionModel.Usage.unknownCost(1, 50, 10), "fixture:period-receipt");
        assertEquals("QUEUED", w.offerDecisionCallback(reply));
        assertEquals(1, w.drainDecisionCallbacks());
        var ref = w.snapshot().decisions().orElseThrow().actionRef(id);
        assertNotNull(ref, id);
        return ref;
    }

    private static JsonNode finish(WorldRuntime w, String person, String id, DecisionRun.ActionRef ref) throws Exception {
        assertEquals("PENDING", history(w, person, id).path("execution").textValue());
        w.advanceTo(ref.dueMillis());
        assertEquals("IN_PROGRESS", history(w, person, id).path("execution").textValue());
        var pending = w.snapshot().pending().stream().filter(t -> t.taskId().equals(ref.taskId()+":finish")).findFirst().orElseThrow();
        w.advanceTo(pending.dueTimeMillis());
        return history(w, person, id);
    }

    @Test void onlyBoundCompanyAndPersonalActionCanSupplyAFinishReceipt() throws Exception {
        var file = directory.resolve("receipts.journal");
        try (var w = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            IssuerPeriodEvidenceTest.govern(w);
            w.advanceTo(30 * DAY);
            var cut = w.periodicOf(STRUCT).orElseThrow().snapshots().get(0);
            var meeting = w.boardOfCompany(STRUCT).orElseThrow().board().meeting(cut.meetingId());
            w.scheduleCompanyBoardAttend("attend", "attend-task", 30*DAY+100, STRUCT, DIRECTOR, cut.meetingId());
            w.advanceTo(meeting.opensAt()+1);
            ObjectNode period = IssuerCreditDecisionTest.JSON.createObjectNode().put("companyPartyId", STRUCT).put("period", 1);
            var prep = choose(w, STRUCT_MANAGER, "prepare", "periodic.prepare", "manager:"+STRUCT, period);
            var prepared = finish(w, STRUCT_MANAGER, "prepare", prep);
            assertEquals("APPLIED", prepared.path("execution").textValue(), prepared.toString());
            var version = w.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(doc -> cut.materialDocId().equals(doc.docId())).findFirst().orElseThrow().at(1);
            long readAt = w.snapshot().timeMillis()+1;
            w.scheduleStartReading("read", "read-task", readAt, DIRECTOR, cut.materialDocId(), 1);
            w.advanceTo(readAt+version.readDurationMillis());
            var draft = w.periodicOf(STRUCT).orElseThrow().drafts().get(0);
            ObjectNode approve = period.deepCopy().put("draftId", draft.draftId()).put("draftVersion", draft.version());
            var first = choose(w, DIRECTOR, "first-approval", "periodic.approve", "director:"+STRUCT, approve);
            var approved = finish(w, DIRECTOR, "first-approval", first);
            assertEquals("APPLIED", approved.path("execution").textValue(), approved.toString());
            assertEquals("OK", approved.path("reason").textValue());
            var second = choose(w, DIRECTOR, "second-approval", "periodic.approve", "director:"+STRUCT, approve);
            var duplicate = finish(w, DIRECTOR, "second-approval", second);
            assertEquals("REJECTED", duplicate.path("execution").textValue(), duplicate.toString());
            assertEquals("ALREADY_APPROVED", duplicate.path("reason").textValue());
            assertEquals(approved, history(w, DIRECTOR, "first-approval"));
            assertEquals(1, w.periodicOf(STRUCT).orElseThrow().approvals().size());
            assertTrue(w.periodicOf("north").orElseThrow().approvals().isEmpty());
            assertEquals(prepared, history(w, STRUCT_MANAGER, "prepare"));
            assertFalse(w.autonomyRoleObservation(STRUCT_DIRECTORS.get(2)).at("/engineering/deliberation/recentDecisions").toString().contains("first-approval"));
            var wrongIssuer = choose(w, DIRECTOR, "wrong-company", "periodic.approve", "director:"+STRUCT,
                    approve.deepCopy().put("companyPartyId", "north"));
            w.advanceTo(wrongIssuer.dueMillis());
            assertEquals("REJECTED", history(w, DIRECTOR, "wrong-company").path("execution").textValue());
            assertEquals(approved, history(w, DIRECTOR, "first-approval"));
        }
        try (var w = ComposedIndustryScenario.openInterviewTenCompanyStudy(file)) {
            assertEquals("APPLIED", history(w, DIRECTOR, "first-approval").path("execution").textValue());
            assertEquals("ALREADY_APPROVED", history(w, DIRECTOR, "second-approval").path("reason").textValue());
            assertTrue(w.periodicOf("north").orElseThrow().approvals().isEmpty());
        }
    }

    @Test void priorVersionKeepsHistoricalCommandAndFeedback() throws Exception {
        var file = directory.resolve("prior.journal");
        try (var w = ComposedIndustryScenario.openPersonalTenCompanyStudy(file)) {
            IssuerPeriodEvidenceTest.govern(w);
            w.advanceTo(31 * DAY);
            var args = IssuerCreditDecisionTest.JSON.createObjectNode().put("companyPartyId", STRUCT).put("period", 1);
            var ref = choose(w, STRUCT_MANAGER, "prepare", "periodic.prepare", "manager:"+STRUCT, args);
            var row = finish(w, STRUCT_MANAGER, "prepare", ref);
            assertEquals("UNKNOWN", row.path("execution").textValue());
            assertEquals("system:periodic-action", w.snapshot().engineering().orElseThrow().reports().get(ref.taskId()+":finish").commandId());
        }
        try (var w = ComposedIndustryScenario.openPersonalTenCompanyStudy(file)) {
            assertEquals("UNKNOWN", history(w, STRUCT_MANAGER, "prepare").path("execution").textValue());
        }
    }
}
