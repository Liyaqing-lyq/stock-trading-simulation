package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.RoleInputTables;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PersonalBeliefContractTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACTOR = CustomerRepresentativeCommunicationTest.PERSON;
    private static final String TOOL = "belief.assert";

    private static JsonNode input(DecisionRun.Call call) throws Exception {
        var input = (ObjectNode) JSON.readTree(call.inputText());
        RoleInputTables.expand(input);
        return input;
    }

    private static JsonNode tool(JsonNode input) {
        for (var row : input.path("tools")) if (TOOL.equals(row.path("name").asText())) return row;
        throw new AssertionError("missing " + TOOL);
    }

    private static Set<String> values(JsonNode array) {
        var result = new HashSet<String>();
        array.forEach(v -> result.add(v.asText()));
        return result;
    }

    private static ObjectNode evidence(JsonNode input, String doc) {
        for (var e : input.at("/profile/readExperiences")) {
            var ref = e.path("lastReadRef");
            if (doc.equals(ref.path("docId").asText())) return ((ObjectNode) ref).deepCopy();
        }
        throw new AssertionError("no completed reading in input for " + doc);
    }

    private static void decide(WorldRuntime w, DecisionRun.Call call, String actor, String id,
                               ObjectNode ref, String comparator, boolean shouldForm) throws Exception {
        var args = JSON.createObjectNode().put("beliefId", id).put("companyId", "mfg-tech")
                .put("comparator", comparator).put("threshold", 1).put("targetAsOfMillis", w.snapshot().timeMillis() + 300_000);
        args.putArray("evidence").add(ref);
        var action = AutonomousIssuerDividendTest.reply(w, call, actor, TOOL, args);
        if (shouldForm) assertNotNull(action);
        if (action != null) w.advanceTo(action.dueMillis());
        boolean recorded = false;
        for (var record : w.autonomyRoleObservation(actor).at("/cognition/beliefs")) {
            if (id.equals(record.path("beliefId").asText())) recorded = true;
        }
        assertEquals(shouldForm, recorded, id);
    }

    @Test void previousCallsKeepTheirOriginalToolShapeAndStrictlyReopen() throws Exception {
        var file = dir.resolve("v15.journal");
        String original;
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(file)) {
            String doc = CustomerRepresentativeCommunicationTest.prepareReplyableLetter(w);
            var call = AutonomousIssuerDividendTest.call(w, ACTOR, "old-shape");
            original = call.inputText();
            var old = input(call);
            assertTrue(old.at("/engineering/orderPredictionRules").isMissingNode());
            assertTrue(tool(old).at("/arrayItems/evidence").isMissingNode());
            var ref = evidence(old, doc);
            assertFalse(ref.has("personPartyId"));
            decide(w, call, ACTOR, "old-compact-evidence", ref, "AT_LEAST", false);
        }
        byte[] bytes = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(file)) {
            assertEquals(original, w.snapshot().decisions().orElseThrow().state().decisions().get(0).inputText());
        }
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void scopedReadingAndVisibleEnumCanBeUsedDirectlyWithoutSharingAnExperience() throws Exception {
        var file = dir.resolve("v16.journal");
        try (var w = ComposedIndustryScenario.openPersonalBeliefTenCompanyStudy(file)) {
            String doc = CustomerRepresentativeCommunicationTest.prepareReplyableLetter(w);
            var call = AutonomousIssuerDividendTest.call(w, ACTOR, "own-call");
            var view = input(call);
            assertTrue(call.allowedTools().contains(TOOL));
            assertTrue(call.allowedTools().stream().noneMatch(Set.of("belief.share","belief.read","belief.evaluate")::contains));
            var rules = view.at("/engineering/orderPredictionRules");
            assertEquals("ORDERS_CONFIRMED",rules.path("metric").asText());
            assertEquals(Set.of("mfg-tech","mfg-struct","mfg-versa"), values(rules.path("companyIds")));
            assertEquals(Set.of("AT_LEAST","AT_MOST"), values(rules.path("comparators")));
            assertEquals(w.profile().information().beliefPolicy().maxHorizonMillis(), rules.path("maxHorizonMillis").longValue());
            assertEquals(JSON.createObjectNode().put("docId","string").put("version","long").put("index","long"),
                    tool(view).at("/arrayItems/evidence"));
            var ref = evidence(view, doc);
            assertEquals(3, ref.size());
            decide(w,call,ACTOR,"mine",ref,"AT_LEAST",true);

            var other = AutonomousIssuerDividendTest.call(w,"rita","unread-call");
            assertFalse(input(other).toString().contains(doc));
            assertFalse(w.canCite("rita",doc,1));
            decide(w,other,"rita","unread",ref,"AT_MOST",false);

            var wrong = AutonomousIssuerDividendTest.call(w,ACTOR,"foreign-owner");
            decide(w,wrong,ACTOR,"wrong-owner",ref.deepCopy().put("personPartyId","rita"),"AT_MOST",false);
            var same = AutonomousIssuerDividendTest.call(w,ACTOR,"explicit-self");
            decide(w,same,ACTOR,"own-four",ref.deepCopy().put("personPartyId",ACTOR),"AT_MOST",true);
        }
        byte[] bytes = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openPersonalBeliefTenCompanyStudy(file)) {
            assertEquals(2,w.autonomyRoleObservation(ACTOR).at("/cognition/beliefs").size());
            assertEquals(0,w.autonomyRoleObservation("rita").at("/cognition/beliefs").size());
        }
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    private static void publicVersion(WorldRuntime w, String id, boolean expected) {
        assertEquals(expected, w.beliefAudit("investor").orElseThrow().sourceClaims().stream()
                .anyMatch(c -> id.equals(c.claimId()) && c.docId().equals("forecast-" + id)));
        assertEquals(expected, w.snapshot().information().orElseThrow().information().documents().stream()
                .anyMatch(d -> d.docId().equals("forecast-" + id) && d.versions().stream().anyMatch(v -> v.version() == 1)));
    }

    private static void forecast(WorldRuntime w, String id, long offsetFromCompletion, boolean accepted) throws Exception {
        var call = AutonomousIssuerDividendTest.call(w, "investor", id);
        assertTrue(call.allowedTools().contains("forecast.publish"));
        var decisions = w.snapshot().decisions().orElseThrow().state();
        var choice = decisions.decisions().stream().filter(d -> d.actionId().equals(call.actionId())).findFirst().orElseThrow();
        long due = choice.barrierAtMillis() + decisions.config().actionMillisByTool().get("forecast.publish");
        long target = due + offsetFromCompletion;
        assertTrue(w.snapshot().timeMillis() < target);
        var args = JSON.createObjectNode().put("claimId", id).put("docId", "forecast-" + id)
                .put("title", "订单展望").put("body", "预计至少确认一笔订单。").put("publicSourceDescription", "本人判断")
                .put("readDurationMillis", 1000).put("companyId", "mfg-tech").put("comparator", "AT_LEAST")
                .put("threshold", 1).put("targetAsOfMillis", target);
        var action = AutonomousIssuerDividendTest.reply(w, call, "investor", "forecast.publish", args);
        assertNotNull(action);
        assertEquals(due, action.dueMillis());
        publicVersion(w, id, false);
        w.advanceTo(due);
        var report = w.snapshot().trading().orElseThrow().reports().get(action.taskId());
        assertEquals(accepted, report.accepted());
        assertEquals(accepted ? "OK" : "PAST_ASOF", report.reason());
        publicVersion(w, id, accepted);
    }

    @Test void publicForecastChecksItsDeadlineAtActualPublicationAndPreservesPriorWorlds() throws Exception {
        Path current = dir.resolve("v16-forecast.journal"), previous = dir.resolve("v15-forecast.journal");
        try (var w = ComposedIndustryScenario.openPersonalBeliefTenCompanyStudy(current)) {
            w.advanceTo(CustomerRepresentativeCommunicationTest.MONDAY);
            forecast(w, "due-now", 0, false);
            forecast(w, "already-past", -1, false);
            forecast(w, "still-future", 1, true);
        }
        byte[] bytes = Files.readAllBytes(current);
        try (var w = ComposedIndustryScenario.openPersonalBeliefTenCompanyStudy(current)) {
            publicVersion(w, "due-now", false);
            publicVersion(w, "already-past", false);
            publicVersion(w, "still-future", true);
        }
        assertArrayEquals(bytes, Files.readAllBytes(current));
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(previous)) {
            w.advanceTo(CustomerRepresentativeCommunicationTest.MONDAY);
            forecast(w, "previous-world", -1, true);
        }
        bytes = Files.readAllBytes(previous);
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(previous)) {
            publicVersion(w, "previous-world", true);
        }
        assertArrayEquals(bytes, Files.readAllBytes(previous));
    }
}
