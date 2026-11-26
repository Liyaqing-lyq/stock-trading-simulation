package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import edu.cufe.auction.web.WorldWebHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PersonalResearchNotesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOC = "company-governance:eng-a:1";
    @TempDir Path dir;

    @Test void onlyReadEvidenceSupportsAnEditableNoteAcrossLaterDecisionsAndRecovery() throws Exception {
        Path file = dir.resolve("roles.journal");
        try (var world = ComposedIndustryScenario.openContinuingRolesOperating(file)) {
            var note = note("收款时间仍有疑问");
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, waitWithNote(world, "unread", note));
            assertTrue(current(world, "investor").isMissingNode());
            read(world);
            assertEquals(DecisionModel.Outcome.WAIT, waitWithNote(world, "first", note));
            for (int i = 0; i < 4; i++)
                assertEquals(DecisionModel.Outcome.WAIT, waitWithNote(world, "later" + i, null));
            assertEquals(note, current(world, "investor").path("note"));
            assertTrue(current(world, "dave").isMissingNode());
            assertEquals("first", current(world, "investor").path("actionId").asText());
        }
        try (var world = ComposedIndustryScenario.openContinuingRolesOperating(file)) {
            assertEquals("first", current(world, "investor").path("actionId").asText());
            var updated = note("重读后修正对回款期限的判断");
            assertEquals(DecisionModel.Outcome.WAIT, waitWithNote(world, "revision", updated));
            assertEquals(updated, current(world, "investor").path("note"));
            assertEquals(DecisionModel.Outcome.WAIT, waitWithNote(world, "withdraw", JSON.nullNode()));
            assertTrue(current(world, "investor").isMissingNode());
        }
    }

    @Test void aNewPeriodCanUseTheNoteUntilItsSourceIsForgotten() throws Exception {
        try (var world = ComposedIndustryScenario.openContinuingRolesOperating(dir.resolve("period.journal"))) {
            read(world);
            assertEquals(DecisionModel.Outcome.WAIT, waitWithNote(world, "formed", note("关注履约后何时付款")));
            world.advanceTo(world.snapshot().timeMillis() + ManufacturingTemplateModel.PERIOD_MILLIS);
            assertEquals("formed", current(world, "investor").path("actionId").asText());
            world.advanceTo(world.snapshot().timeMillis() + 10 * world.profile().information().recallPolicy().halfLifeMillis());
            assertTrue(current(world, "investor").isMissingNode());
        }
    }

    @Test void revokingAccessHidesTheNoteEvenWhenTheReadingIsStillRemembered() throws Exception {
        try (var world = ComposedIndustryScenario.openContinuingRolesOperating(dir.resolve("private.journal"))) {
            String doc = "fund-internal-research";
            long at = world.snapshot().timeMillis() + 1;
            world.schedulePublishDocument("publish", "publish", at, doc, "dave", InformationModel.Audience.organization("cufe"),
                    "研究资料", "订单交付仍待观察", "机构资料", 1000, List.of());
            world.advanceTo(at);
            var office = world.autonomyRoleObservation("dave").at("/appointments/0/appointmentId").asText();
            world.openRoleSession("dave").startReading("read", "read", at + 1, doc, 1);
            world.advanceTo(at + 1001);
            var note = note("机构原始预测有待复核");
            ((ObjectNode) note.at("/evidence/0")).put("docId", doc);
            assertEquals(DecisionModel.Outcome.WAIT, waitWithNote(world, "dave", "private", note));
            assertEquals(note, current(world, "dave").path("note"));
            long end = world.snapshot().timeMillis() + 1;
            world.scheduleRevokeAppointment("revoke", "revoke", end, office);
            world.advanceTo(end);
            assertTrue(world.roleView("dave").observations().stream().anyMatch(o -> doc.equals(o.ref().docId())));
            assertTrue(current(world, "dave").isMissingNode());
        }
    }

    @Test void oldContractStillRejectsNotesOnAReadDocument() throws Exception {
        try (var world = ComposedIndustryScenario.openPersonalLimitOperating(dir.resolve("old.journal"))) {
            read(world);
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, waitWithNote(world, "old", note("看好订单")));
            assertTrue(world.autonomyRoleObservation("investor").at("/engineering/researchNotes").isMissingNode());
        }
    }

    private static ObjectNode note(String opinion) {
        var note = JSON.createObjectNode().put("judgment", opinion).put("reviewCondition", "取得下期披露时复核");
        note.putArray("evidence").addObject().put("docId", DOC).put("version", 1);
        return note;
    }

    @Test void correctedReportChangesOnlyTheReaderWhoReadItAndItsNoteCanSupportAnOrder() throws Exception {
        Path journal = dir.resolve("personal-revisions.journal");
        String doc = "customer-progress";
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(journal)) {
            long at = world.snapshot().timeMillis() + 1;
            world.schedulePublishDocument("announce", "announce", at, doc, "investor",
                    InformationModel.Audience.publicAudience(), "客户进度", "客户称已完成验收",
                    "受控验收材料", 1000, List.of());
            world.advanceTo(at);
            world.openRoleSession("nina").startReading("read-first", "read-first", at + 1, doc, 1);
            world.advanceTo(at + 1001);
            var first = note("验收已经完成，还要看款项何时到账");
            ((ObjectNode) first.at("/evidence/0")).put("docId", doc);
            assertEquals(DecisionModel.Outcome.WAIT, waitWithNote(world, "nina", "first-view", first));
            long next = world.snapshot().timeMillis() + ManufacturingTemplateModel.PERIOD_MILLIS;
            world.schedulePublishDocumentVersion("correct", "correct", next, doc, "客户更正",
                    "已验收的是首批，余单尚未交付", "受控验收材料", 1000, List.of());
            world.advanceTo(next);
            var revised = note("只对首批的回款作判断").put("revises", "first-view")
                    .put("changeReason", "客户更正了验收范围");
            ((ObjectNode) revised.at("/evidence/0")).put("docId", doc).put("version", 2);
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, waitWithNote(world, "nina", "unread-update", revised));
            assertEquals(first, current(world, "nina").path("note"));
            world.openRoleSession("nina").startReading("read-update", "read-update", next + 60_000, doc, 2);
            world.advanceTo(next + 61_000);
            assertEquals(DecisionModel.Outcome.WAIT, waitWithNote(world, "nina", "changed-view", revised));
            var notes = world.autonomyRoleObservation("nina").at("/engineering/researchNotes");
            assertEquals(first, notes.at("/previous/note"));
            assertEquals(revised, notes.at("/current/note"));
            assertTrue(current(world, "omar").isMissingNode());

            var order = JSON.createObjectNode().put("tool", PersonalMarket.SUBMIT).put("basedOnNote", "changed-view");
            String symbol = world.profile().registry().securities().get(0).symbol();
            order.putObject("args").put("accountId", "HOUSE-NINA").put("symbol", symbol)
                    .put("side", "SELL").put("quantity", 1).put("limitPrice", "10.00");
            assertEquals(DecisionModel.Outcome.INTENT, decide(world, "nina", "act-on-change", order));
            var action = world.snapshot().decisions().orElseThrow().actionRef("act-on-change");
            world.advanceTo(action.dueMillis());
            var feedback = world.autonomyRoleObservation("nina").at("/engineering/deliberation/lastActionResult");
            assertEquals("changed-view", feedback.path("basedOnNote").asText());
            assertEquals("APPLIED", feedback.path("execution").asText());
            assertEquals("ACTIVE", feedback.path("orderStatus").asText());
            assertEquals(1, world.snapshot().exchange().orElseThrow().orders().stream()
                    .filter(o -> "HOUSE-NINA".equals(o.accountId()) && o.live()).count());
            var foreign = JSON.createObjectNode().put("tool", "role.wait").put("basedOnNote", "changed-view");
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, decide(world, "omar", "foreign-note", foreign));
        }
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(journal)) {
            assertEquals("first-view", world.autonomyRoleObservation("nina").at("/engineering/researchNotes/previous/actionId").asText());
            assertEquals("changed-view", current(world, "nina").path("actionId").asText());
            assertEquals(1, world.snapshot().exchange().orElseThrow().orders().size());
        }
        try (var host = new WorldWebHost(() -> new WorldWebHost.Opened(
                ComposedIndustryScenario.openPersonalProfilesOperating(journal), RunManifest.PERSONAL_PROFILES_RUN_ID))) {
            var page = host.personPage("nina");
            var cuts = JSON.valueToTree(page.personalResearch()).path("portfolioHistory");
            String digest = cuts.get(cuts.size() - 1).required("digest").asText();
            var history = host.portfolioHistory("nina", digest);
            assertEquals("3000.00", history.accounts().get(0).get("availableCash"));
            assertEquals(10, history.holdings().size());
            assertTrue(history.holdings().stream().allMatch(h -> "HOUSE-NINA".equals(h.get("accountId"))));
            var prior = history.holdings().stream().filter(h -> h.get("quantity").equals(h.get("available"))).count();
            assertEquals(10, prior);
            assertTrue(page.view().holdings().stream().anyMatch(h -> h.quantity() > h.available()));
            assertThrows(IllegalArgumentException.class, () -> host.portfolioHistory("omar", digest));
        }
    }

    private static JsonNode current(WorldRuntime world, String person) throws Exception {
        return world.autonomyRoleObservation(person).at("/engineering/researchNotes/current");
    }

    private static void read(WorldRuntime world) throws Exception {
        long now = world.snapshot().timeMillis();
        world.openRoleSession("investor").startReading("read-doc", "read-task", now + 10, DOC, 1);
        world.advanceTo(now + 10_000);
        assertTrue(world.roleView("investor").observations().stream().anyMatch(o -> DOC.equals(o.ref().docId())));
    }

    private static DecisionModel.Outcome waitWithNote(WorldRuntime world, String id, JsonNode note) throws Exception {
        return waitWithNote(world, "investor", id, note);
    }

    private static DecisionModel.Outcome waitWithNote(WorldRuntime world, String person, String id, JsonNode note) throws Exception {
        var reply = JSON.createObjectNode().put("tool", "role.wait");
        if (note != null) reply.set("privateNote", note);
        return decide(world, person, id, reply);
    }

    private static DecisionModel.Outcome decide(WorldRuntime world, String person, String id, ObjectNode reply) throws Exception {
        world.beginDecisionSession("session-" + id);
        var route = new DecisionModel.Route("trading", "key-1", "deepseek-flash", 1L, 25, 1_000_000, 2048, 1);
        world.openDecisions("open-" + id, List.of(new DecisionRun.Request(person, id, "wake", route)));
        var call = world.dispatchLiveDecision("dispatch-" + id, id).call();
        assertNotNull(call);
        reply.putObject("decision").put("objective", JSON.readTree(call.inputText()).at("/engineering/deliberation/objectives/0/id").asText()).put("basis", "等待披露").put("reviewAfterMillis", 60_000);
        var typed = new DecisionModel.Reply(call.runId(), id, call.attemptId(), call.attemptNo(), call.recoveryGeneration(),
                reply.toString(), DecisionModel.Usage.unknownCost(1, 100, 30), "fixture:research");
        assertEquals(WorldRuntime.CompletionOffer.QUEUED, world.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(typed)));
        world.drainDecisionCallbacks();
        var decision = DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(), id).orElseThrow();
        world.advanceTo(decision.barrierAtMillis());
        return decision.outcome();
    }
}
