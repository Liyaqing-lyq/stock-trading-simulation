package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

class PeriodicScoreInputTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PERSON = STRUCT_DIRECTORS.get(0);

    @Test void onlyExplicitMetricsFromTheDirectorsOwnFrozenEvidenceBecomeBallots() throws Exception {
        Path file = dir.resolve("score.journal");
        try (var w = ComposedIndustryScenario.openCurrentTenCompanyStudy(file)) {
            IssuerPeriodEvidenceTest.govern(w);
            long end = PeriodicModel.PERIOD_MILLIS;
            w.advanceTo(end);
            var snap = w.periodicOf(STRUCT).orElseThrow().snapshots().get(0);
            var meeting = w.boardOfCompany(STRUCT).orElseThrow().board().meeting(snap.meetingId());
            w.scheduleCompanyBoardAttend("attend", "attend-t", end + 100, STRUCT, PERSON, snap.meetingId());
            String other = STRUCT_DIRECTORS.get(1);
            w.scheduleCompanyBoardAttend("other-attend", "other-attend-t", end + 101, STRUCT, other, snap.meetingId());
            w.advanceTo(meeting.opensAt() + 1);
            long prepare = w.snapshot().timeMillis() + 2000;
            w.schedulePeriodicAction("prep", "prep-t", prepare, STRUCT, STRUCT_MANAGER, "PREPARE", 1, "", 0, "", 0, "", List.of(), "", 1000);
            w.advanceTo(prepare);
            var material = w.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(d -> d.docId().equals(snap.materialDocId())).findFirst().orElseThrow().at(1);
            w.scheduleStartReading("read", "read-t", prepare + 1, PERSON, snap.materialDocId(), 1);
            w.scheduleStartReading("other-read", "other-read-t", prepare + 2, other, snap.materialDocId(), 1);
            w.advanceTo(prepare + 2 + material.readDurationMillis());
            for (String variant : List.of("missing-score", "wrong-type", "negative", "over-hundred",
                    "fraction", "missing-date", "wrong-date", "wrong-ref", "wrong-dimension", "duplicate", "empty", "accepted")) {
                w.beginDecisionSession("session-" + variant);
                var route = new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1, 60, 1_000_000, 1024, 1, "lifecycle-v1", null);
                w.openDecisions("open-" + variant, List.of(new DecisionRun.Request(PERSON, variant, "wake", route)));
                var call = w.dispatchLiveDecision("dispatch-" + variant, variant).call();
                var input = JSON.readTree(call.inputText());
                assertTrue(call.allowedTools().contains("periodic.score"));
                assertTrue(input.at("/engineering/periodicScoreContract").asText().contains("dimension、score、sourceRef、asOf"));
                JsonNode source = null;
                for (var board : input.at("/engineering/directorships")) if (STRUCT.equals(board.path("companyPartyId").asText()))
                    for (var row : board.path("periodic")) if (row.path("period").asLong() == 1)
                        for (var entry : row.path("evidence"))
                            if (entry.path("sourceRef").asText().startsWith("account:")) source = entry;
                assertNotNull(source);
                assertFalse(source.has("ref"));
                var body = JSON.createObjectNode().put("tool", "periodic.score");
                body.putObject("decision").put("objective", "director:" + STRUCT).put("basis", "按本人读过的期间材料评估偿付").put("reviewAfterMillis", 60000);
                var args = body.putObject("args").put("companyPartyId", STRUCT).put("period", 1).put("rationale", "仅评有依据的一维");
                var metrics = args.putArray("metrics");
                ObjectNode m = metrics.addObject().put("dimension", source.get("dimensions").get(0).asText()).put("sourceRef", source.get("sourceRef").asText())
                        .put("asOf", source.get("asOf").longValue()).put("score", new BigDecimal("0.00"));
                switch (variant) {
                    case "missing-score" -> m.remove("score");
                    case "wrong-type" -> m.put("score", "72");
                    case "negative" -> m.put("score", -1);
                    case "over-hundred" -> m.put("score", new BigDecimal("100.00000000000000000001"));
                    case "fraction" -> m.put("score", new BigDecimal("72.001"));
                    case "missing-date" -> m.remove("asOf");
                    case "wrong-date" -> m.put("asOf", 0);
                    case "wrong-ref" -> m.put("sourceRef", "account:other-owner");
                    case "wrong-dimension" -> m.put("dimension", "LONG_TERM_INVESTMENT");
                    case "duplicate" -> metrics.add(m.deepCopy());
                    case "empty" -> metrics.removeAll();
                }
                var reply = new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                        body.toString(), DecisionModel.Usage.unknownCost(1,60,20), "fixture:score");
                assertEquals(WorldRuntime.CompletionOffer.QUEUED, w.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));
                assertEquals(1, w.drainDecisionCallbacks());
                var decisions = w.snapshot().decisions().orElseThrow();
                var recorded = decisions.state().decisions().get(decisions.state().decisions().size() - 1);
                assertEquals(DecisionModel.Outcome.INTENT, recorded.outcome(), variant);
                var ref = decisions.actionRef(variant);
                if (!variant.equals("accepted")) {
                    assertNull(ref, variant);
                    assertTrue(w.periodicOf(STRUCT).orElseThrow().ballots().isEmpty(), variant);
                } else {
                    assertNotNull(ref);
                    w.advanceTo(DecisionModel.actionDueMillis(decisions.state(), variant));
                    assertEquals("ACCEPTED", w.taskResultForTest(ref.taskId() + ":finish"));
                    var ballot = w.periodicOf(STRUCT).orElseThrow().ballots().get(0);
                    assertEquals(1, ballot.metrics().size());
                    assertEquals(0, ballot.metrics().get(0).score().signum());
                    assertEquals(BoardModel.ReviewStatus.INCOMPLETE, ballot.reviewStatus());
                }
            }
            var booked = w.periodicOf(STRUCT).orElseThrow().ballots().get(0).metrics().get(0);
            var zero = new PeriodicActionPayload.PeriodicMetric(booked.dimension().name(), BigDecimal.ZERO,
                    booked.sourceRef(), booked.asOf());
            long now = w.snapshot().timeMillis();
            long position = w.snapshot().position();
            for (List<PeriodicActionPayload.PeriodicMetric> invalid : List.of(
                    List.<PeriodicActionPayload.PeriodicMetric>of(), List.of(zero, zero),
                    List.of(new PeriodicActionPayload.PeriodicMetric("UNKNOWN", BigDecimal.ONE, zero.sourceRef(), zero.asOf())),
                    List.of(new PeriodicActionPayload.PeriodicMetric(zero.dimension(), new BigDecimal("100.01"), zero.sourceRef(), zero.asOf())),
                    List.of(new PeriodicActionPayload.PeriodicMetric(zero.dimension(), new BigDecimal("-1"), zero.sourceRef(), zero.asOf())),
                    List.of(new PeriodicActionPayload.PeriodicMetric(zero.dimension(), new BigDecimal("0.001"), zero.sourceRef(), zero.asOf())),
                    List.of(zero, zero, zero, zero, zero, zero))) {
                assertThrows(IllegalArgumentException.class, () -> w.schedulePeriodicAction("manual", "manual-t", now + 2000,
                        STRUCT, other, "SCORE", 1, "", 0, "", 0, "", invalid, "核对", 1000));
                assertEquals(position, w.snapshot().position());
            }
            w.schedulePeriodicAction("manual", "manual-t", now + 2000, STRUCT, other, "SCORE", 1, "", 0, "", 0,
                    "", List.of(zero), "明确给零分", 1000);
            w.advanceTo(now + 2000);
            assertEquals("ACCEPTED", w.taskResultForTest("manual-t:finish"));
            assertEquals(0, w.periodicOf(STRUCT).orElseThrow().ballots().get(1).metrics().get(0).score().signum());
        }
        byte[] original = Files.readAllBytes(file);
        try (var restored = ComposedIndustryScenario.openCurrentTenCompanyStudy(file)) {
            assertEquals(2, restored.periodicOf(STRUCT).orElseThrow().ballots().size());
        }
        assertArrayEquals(original, Files.readAllBytes(file));
    }
}
