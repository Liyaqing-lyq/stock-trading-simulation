package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 独立有限预算、严格回执及语义恢复的回归。 */
class DecisionModelAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WAIT = "{\"tool\":\"role.wait\"}";
    private static final String INTENT = "{\"tool\":\"role.target.set\",\"args\":{\"quantity\":3}}";

    private static DecisionModel.Usage usage(long calls, long input, long output) {
        return new DecisionModel.Usage(calls, input, output, new BigDecimal("0.00"));
    }

    private static DecisionModel.State opened() {
        var config = new DecisionModel.Config("SCRIPTED", "audit-script", "audit-zero-v1",
                Map.of("WAKE", 10L), Map.of("role.target.set", 20L),
                new DecisionModel.Limits(2, 1000, 1000, 100, 20, 4, 2, 1000),
                new DecisionModel.Budget(usage(20, 2000, 400),
                        Map.of("alice", usage(10, 1000, 200),
                                "bob", usage(10, 1000, 200))));
        var state = DecisionModel.beginSession(DecisionModel.open("audit-run", 89, config)).state();
        state = open(state, "a", "alice");
        return open(state, "b", "bob");
    }

    private static DecisionModel.State open(DecisionModel.State state, String id, String person) {
        var result = DecisionModel.openDecision(state,
                new DecisionModel.OpenRequest("audit-run", id, person, "WAKE", 100, 7, 100,
                        "audit-tools-v1", List.of("role.wait", "role.target.set"), "fixed input"),
                7, 100);
        assertEquals(DecisionModel.Verdict.ACCEPTED, result.verdict());
        return result.state();
    }

    private static DecisionModel.Reply reply(DecisionModel.State state, String body,
                                             DecisionModel.Usage usage) {
        var attempt = DecisionModel.decision(state, "a").orElseThrow().lastAttempt();
        return new DecisionModel.Reply("audit-run", "a", attempt.attemptId(), attempt.attemptNo(),
                attempt.recoveryGeneration(), body, usage, "scripted:audit-v1");
    }

    private static ObjectNode object(ObjectNode root, String path) {
        return (ObjectNode) root.at(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"barrier", "wake", "future-generation", "zero-generation",
            "unsettled-usage", "reservation", "duplicate-attempt", "over-budget",
            "two-unsettled", "wait-body", "terminal-outcome", "intent-args",
            "due-overflow", "input-limit", "reply-limit", "settled-above-reservation",
            "sequence-gap", "attempt-limit", "script-budget-cost", "sequence-alias",
            "fractional-attempt", "fractional-reservation"})
    void restoredStateMustSatisfyTheFrozenContract(String mutation) throws Exception {
        var state = opened();
        if (List.of("wait-body", "terminal-outcome", "reply-limit",
                "settled-above-reservation").contains(mutation)) {
            state = DecisionModel.submitReply(state, reply(state, WAIT, usage(1, 10, 2))).state();
        } else if (List.of("intent-args", "due-overflow").contains(mutation)) {
            state = DecisionModel.submitReply(state, reply(state, INTENT, usage(1, 10, 2))).state();
        } else if (mutation.equals("attempt-limit")) {
            state = DecisionModel.submitReply(state,
                    reply(state, "bad-json", usage(1, 10, 2))).state();
        }
        String legal = DecisionModel.toJson(state);
        assertEquals(state, DecisionModel.fromJson(legal), "合法对照必须先通过");
        ObjectNode root = (ObjectNode) JSON.readTree(legal);
        ObjectNode decision = object(root, "/decisions/0");
        ObjectNode attempt = object(root, "/decisions/0/attempts/0");
        switch (mutation) {
            case "barrier" -> decision.put("barrierAtMillis", 111);
            case "wake" -> decision.put("wakeReason", "UNCONFIGURED");
            case "future-generation" -> attempt.put("recoveryGeneration", 2);
            case "zero-generation" -> root.put("recoveryGeneration", 0);
            case "unsettled-usage" -> object(attempt, "/settled").put("inputTokens", 1);
            case "reservation" -> object(attempt, "/reserved").put("inputTokens", 99);
            case "duplicate-attempt" -> object(root, "/decisions/1/attempts/0")
                    .put("attemptId", attempt.get("attemptId").asText());
            case "over-budget" -> object(root, "/config/budget/runCap").put("inputTokens", 199);
            case "two-unsettled" -> {
                ObjectNode second = attempt.deepCopy();
                second.put("attemptId", "ATT-3");
                second.put("attemptNo", 2);
                ((ArrayNode) decision.get("attempts")).add(second);
                root.put("nextAttemptSequence", 4);
            }
            case "wait-body" -> attempt.put("replyBody", INTENT);
            case "terminal-outcome" -> attempt.put("outcome", "TECHNICAL_FAILED");
            case "intent-args" -> attempt.put("replyBody",
                    "{\"tool\":\"role.target.set\",\"args\":[]}");
            case "due-overflow" -> object(root, "/config/actionMillisByTool")
                    .put("role.target.set", Long.MAX_VALUE);
            case "input-limit" -> object(root, "/config/limits").put("maxInputChars", 1);
            case "reply-limit" -> object(root, "/config/limits").put("maxReplyChars", 1);
            case "settled-above-reservation" -> {
                object(attempt, "/settled").put("inputTokens", 101);
                object(root, "/runUsage").put("inputTokens", 101);
                object(root, "/personUsage/alice").put("inputTokens", 101);
            }
            case "sequence-gap" -> root.put("nextAttemptSequence", 50);
            case "sequence-alias" -> attempt.put("attemptId", "ATT-01");
            case "fractional-attempt" -> attempt.put("attemptNo", 1.9);
            case "fractional-reservation" -> object(attempt, "/reserved").put("inputTokens", 100.9);
            case "attempt-limit" -> object(root, "/config/limits").put("maxAttemptsPerDecision", 1);
            case "script-budget-cost" -> object(root, "/config/budget/runCap")
                    .put("cost", new BigDecimal("0.01"));
            default -> throw new AssertionError(mutation);
        }
        assertThrows(IllegalArgumentException.class, () -> DecisionModel.fromJson(root.toString()),
                mutation);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"tool\":\"role.wait\",\"tool\":\"role.target.set\",\"args\":{}}",
            "{\"tool\":\"role.wait\"}{\"tool\":\"role.target.set\",\"args\":{}}",
            "{\"tool\":\"role.wait\"} trailing"})
    void ambiguousOrTrailingReplyIsTechnicalFailure(String body) {
        var state = opened();
        var result = DecisionModel.submitReply(state, reply(state, body, usage(1, 10, 2)));
        assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, result.decision().outcome());
        assertEquals(usage(1, 10, 2), result.state().runUsage());
    }

    @Test
    void declaredOverLimitReceiptReplaysExactlyWithoutAnotherCharge() {
        var state = opened();
        var receipt = reply(state, WAIT, usage(1, 101, 2));
        var failed = DecisionModel.submitReply(state, receipt);
        assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, failed.decision().outcome());
        assertEquals(usage(1, 100, 20), failed.state().runUsage());
        var again = DecisionModel.submitReply(failed.state(), receipt);
        assertEquals(DecisionModel.Verdict.DUPLICATE, again.verdict());
        assertEquals(failed.state(), again.state());
    }

    @Test
    void unknownUsageStillRetainsTheExactReceivedBodyAndSource() {
        var state = opened();
        var receipt = reply(state, WAIT, null);
        var failed = DecisionModel.submitReply(state, receipt);
        var attempt = failed.decision().attempts().get(0);
        assertEquals(WAIT, attempt.replyBody());
        assertEquals(receipt.usageSource(), attempt.usageSource());
        assertEquals(usage(1, 100, 20), attempt.settled());
        assertEquals(DecisionModel.Verdict.DUPLICATE,
                DecisionModel.submitReply(failed.state(), receipt).verdict());
    }

    @Test
    void duplicateMustMatchTheOriginalGeneration() {
        var state = opened();
        var receipt = reply(state, WAIT, usage(1, 10, 2));
        var recorded = DecisionModel.submitReply(state, receipt);
        var forged = new DecisionModel.Reply(receipt.runId(), receipt.actionId(), receipt.attemptId(),
                receipt.attemptNo(), 999, receipt.body(), receipt.usage(), receipt.usageSource());
        assertEquals(DecisionModel.Verdict.REJECTED,
                DecisionModel.submitReply(recorded.state(), forged).verdict());
        assertEquals(DecisionModel.Verdict.DUPLICATE,
                DecisionModel.submitReply(recorded.state(), receipt).verdict());
    }

    @Test
    void stateDocumentRejectsTrailingJson() {
        var state = opened();
        assertThrows(IllegalArgumentException.class,
                () -> DecisionModel.fromJson(DecisionModel.toJson(state) + " {}"));
    }

    @Test
    void anUnknownUsageFailureIsNotAReceivedEmptyReply() {
        var state = opened();
        var before = DecisionModel.decision(state, "a").orElseThrow().lastAttempt();
        var failed = DecisionModel.failAttempt(state, "a", before.attemptNo(), before.attemptId(),
                before.recoveryGeneration(), "TIMEOUT");
        var fabricated = new DecisionModel.Reply("audit-run", "a", before.attemptId(),
                before.attemptNo(), before.recoveryGeneration(), "", null, "");
        assertEquals(DecisionModel.Verdict.REJECTED,
                DecisionModel.submitReply(failed.state(), fabricated).verdict());
        assertEquals(usage(1, 100, 20), failed.state().runUsage());
    }

    @Test
    void failureAndTheBudgetReasonStoppingItsRetryAreBothPreserved() throws Exception {
        ObjectNode data = (ObjectNode) JSON.readTree(DecisionModel.toJson(opened()));
        object(data, "/config/budget/personCap/alice").put("attempts", 1);
        var state = DecisionModel.fromJson(data.toString());
        var attempt = DecisionModel.decision(state, "a").orElseThrow().lastAttempt();
        var failed = DecisionModel.failAttempt(state, "a", attempt.attemptNo(), attempt.attemptId(),
                attempt.recoveryGeneration(), "TIMEOUT");
        assertEquals(DecisionModel.Status.EXPIRED, failed.decision().status());
        assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, failed.decision().outcome());
        assertEquals(DecisionModel.REASON_NO_RETRY_BUDGET, failed.reason());
        assertTrue(failed.decision().lastAttempt().reason().endsWith(":NO_RETRY_BUDGET"));
        assertEquals(failed.state(), DecisionModel.fromJson(DecisionModel.toJson(failed.state())));
    }

    @Test
    void partialUsageThenRetryReleasesUnusedReservation() {
        var state = opened();
        var failed = DecisionModel.submitReply(state, reply(state, "bad-json", usage(1, 10, 1)));
        assertEquals(usage(8, 890, 179), DecisionModel.headroom(failed.state(), "alice"));
        var recorded = DecisionModel.submitReply(failed.state(),
                reply(failed.state(), WAIT, usage(1, 20, 3)));
        assertEquals(usage(2, 30, 4), recorded.state().runUsage());
        assertEquals(usage(8, 970, 196), DecisionModel.headroom(recorded.state(), "alice"));
        assertEquals(recorded.state(), DecisionModel.fromJson(DecisionModel.toJson(recorded.state())));
    }
}
