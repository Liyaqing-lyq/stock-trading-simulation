package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.DividendModel;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.RoleInputTables;
import edu.cufe.auction.world.RoleTradingModel;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.CompanyOpeningDocuments;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 固定模型回复走本人正式决定入口。公司会议、读件、投票及支付均由实际业务执行。 */
class AutonomousIssuerDividendTest {
    @TempDir Path dir;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "eng-a", MEETING = "income-distribution", SCHEME = "eng-a-income";
    private static final String CONVENE = "issuer.dividend.convene", PROPOSE = "issuer.dividend.propose", AUTHORIZE = "issuer.dividend.authorize";
    private static final List<String> VOTERS = List.of("alice", "bob", "carol");

    private static boolean offers(JsonNode input, String name) {
        for (var value : input.path("tools")) if (name.equals(value.path("name").asText())) return true;
        return false;
    }

    private static ObjectNode terms() {
        return JSON.createObjectNode().put("companyPartyId", ISSUER).put("meetingId", MEETING);
    }

    static DecisionRun.Call call(WorldRuntime w, String actor, String id) throws Exception {
        w.beginDecisionSession("session-" + id);
        String scene = switch (actor) {
            case "alice" -> "research";
            case "bob", "carol" -> "trading";
            default -> "media";
        };
        var route = new DecisionModel.Route(scene, "key-1", "deepseek-flash", 1L, 40, 1_000_000, 2_048, 1);
        w.openDecisions("open-" + id, List.of(new DecisionRun.Request(actor, id, "wake", route)));
        var sent = w.dispatchLiveDecision("dispatch-" + id, id).call();
        assertNotNull(sent, id);
        return sent;
    }

    static DecisionRun.ActionRef reply(WorldRuntime w, DecisionRun.Call call, String actor,
                                                String tool, ObjectNode args) throws Exception {
        var body = JSON.createObjectNode().put("tool", tool);
        body.set("args", args);
        body.putObject("decision").put("objective", "person:" + actor)
                .put("basis", "本次受控验证沿本人可选动作处理本公司议案").put("reviewAfterMillis",60_000);
        assertEquals("QUEUED", w.offerDecisionCallback(new DecisionModel.Reply(call.runId(), call.actionId(),
                call.attemptId(), call.attemptNo(), call.recoveryGeneration(), body.toString(),
                DecisionModel.Usage.unknownCost(1, 50, 16), "fixture:issuer-dividend")));
        assertEquals(1, w.drainDecisionCallbacks());
        return w.snapshot().decisions().orElseThrow().actionRef(call.actionId());
    }

    private static JsonNode choose(WorldRuntime w, String actor, String id, String tool, ObjectNode args) throws Exception {
        var sent = call(w, actor, id);
        var input = (ObjectNode) JSON.readTree(sent.inputText());
        RoleInputTables.expand(input);
        assertTrue(sent.allowedTools().contains(tool) && offers(input, tool), id);
        var ref = reply(w, sent, actor, tool, args);
        assertNotNull(ref, id);
        w.advanceTo(ref.dueMillis());
        var finish = w.snapshot().pending().stream().filter(t -> t.taskId().equals(ref.taskId()+":finish")).findFirst();
        if (finish.isPresent()) w.advanceTo(finish.get().dueTimeMillis());
        if (List.of(CONVENE, PROPOSE, AUTHORIZE).contains(tool)) {
            var receipt = w.autonomyRoleObservation(actor).at("/engineering/deliberation/lastActionResult");
            assertEquals(id, receipt.path("actionId").textValue(), receipt::toString);
            assertEquals("APPLIED", receipt.path("execution").textValue(), receipt::toString);
            assertTrue(receipt.path("accepted").asBoolean());
            assertEquals(w.snapshot().timeMillis(), receipt.required("resultAt").asLong());
        }
        return input;
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void threeDirectorsReadProposeVoteAndTheCompanyPaysOnlyIfFunded(boolean funded) throws Exception {
        Path file = dir.resolve(funded ? "paid.journal" : "unpaid.journal");
        long recordAt, exAt, payAt;
        BigDecimal perShare = new BigDecimal(funded ? "0.01" : "999.00");
        var doc = CompanyOpeningDocuments.documentId(ISSUER);
        try (var w = ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(file)) {
            var issuer = w.snapshot().engineering().orElseThrow().config().specOf(ISSUER);
            var symbol = w.openingRegistry().orElseThrow().securities().stream()
                    .filter(s -> ISSUER.equals(s.issuerOrganizationId())).findFirst().orElseThrow();
            var lots = w.snapshot().securities().orElseThrow().lots();
            long treasury = lots.stream().filter(lot -> symbol.symbol().equals(lot.symbol())
                    && lot.classification() == OpeningRegistry.ShareClass.TREASURY).mapToLong(lot -> lot.remaining()).sum();
            assertEquals(400, treasury);
            long eligible = symbol.issuedShares() - treasury;
            assertEquals(600, eligible);
            BigDecimal total = perShare.multiply(BigDecimal.valueOf(eligible));
            var openingCash = w.snapshot().accounts().get(issuer.companyAccountId()).cash();
            for (var voter : VOTERS) {
                choose(w, voter, "read-"+voter, "role.reading.start", JSON.createObjectNode().put("docId", doc).put("version",1));
                w.advanceTo(w.roleView(voter).readings().get(0).dueAt());
                assertTrue(w.canCite(voter, doc, 1));
            }
            long start = w.snapshot().timeMillis(), material = start+120_000, opens = start+150_000, closes = start+240_000;
            recordAt = closes+60_000; exAt = recordAt+10_000; payAt = exAt+10_000;
            choose(w, "alice", "convene", CONVENE, terms().put("materialDeadline",material).put("opensAt",opens).put("closesAt",closes));
            assertEquals(BoardModel.Topic.DIVIDEND_AUTHORIZATION, w.boardOfCompany(ISSUER).orElseThrow().board().meeting(MEETING).topic());
            for (var voter : VOTERS) choose(w, voter, "attend-"+voter, "board.attend", terms());
            choose(w, "alice", "material", "board.addMaterial", terms().put("materialId","charter").put("sourceRef",doc).put("version",1));
            choose(w, "bob", "propose", PROPOSE, terms().put("schemeId",SCHEME).put("perShare",perShare.toPlainString())
                    .put("recordAtMillis",recordAt).put("exAtMillis",exAt).put("payAtMillis",payAt));
            assertEquals(DividendModel.Phase.PROPOSED, w.snapshot().dividends().orElseThrow().schemes().get(SCHEME).phase());
            w.advanceTo(opens);
            for (var voter : VOTERS) {
                var receipt = choose(w, voter, "vote-"+voter, "board.vote", terms().put("vote","YES"));
                var proposals = receipt.get("engineering").findValues("dividendRequest").stream()
                        .filter(row -> SCHEME.equals(row.path("schemeId").textValue())).toList();
                assertEquals(1, proposals.size(), "当事人应看到所投方案");
                var readable = proposals.get(0);
                assertEquals(perShare, new BigDecimal(readable.required("perShare").asText()));
                assertEquals(recordAt, readable.required("recordAtMillis").asLong());
                assertEquals(exAt, readable.required("exAtMillis").asLong());
                assertEquals(payAt, readable.required("payAtMillis").asLong());
                assertTrue(readable.required("proposedAtMillis").asLong() <= opens);
            }
            w.advanceTo(closes);
            choose(w, "alice", "authorize", AUTHORIZE, terms());
            assertEquals(DividendModel.Phase.AUTHORIZED, w.snapshot().dividends().orElseThrow().schemes().get(SCHEME).phase());
            w.advanceTo(recordAt);
            assertEquals(DividendModel.Phase.REGISTERED, w.snapshot().dividends().orElseThrow().schemes().get(SCHEME).phase());
            assertTrue(DividendModel.claims(w.snapshot().dividends().orElseThrow(), SCHEME).isEmpty());
            w.advanceTo(exAt);
            var booked = w.snapshot().dividends().orElseThrow();
            assertEquals(DividendModel.Phase.EX_BOOKED, booked.schemes().get(SCHEME).phase());
            var claims = DividendModel.claims(booked,SCHEME);
            assertEquals(total, claims.stream().map(DividendModel.Claim::face).reduce(new BigDecimal("0.00"), BigDecimal::add));
            assertEquals(total, w.engineeringContext(ISSUER).orElseThrow().unpaidDividends());
            var before = w.snapshot();
            for (var claim : claims) {
                assertEquals(RoleTradingModel.held(before.securities().orElseThrow(), claim.accountId(), symbol.symbol()), claim.quantity());
                assertEquals(perShare.multiply(BigDecimal.valueOf(claim.quantity())), claim.face());
                assertEquals(claim.face(), DividendModel.holderNetReceivable(claim));
            }
            w.advanceTo(payAt);
            assertEquals(funded ? openingCash.subtract(total) : openingCash, w.snapshot().accounts().get(issuer.companyAccountId()).cash());
            for (var paid : DividendModel.claims(w.snapshot().dividends().orElseThrow(), SCHEME)) {
                var expectedCash = funded ? paid.face() : new BigDecimal("0.00");
                assertEquals(before.accounts().get(paid.accountId()).cash().add(expectedCash), w.snapshot().accounts().get(paid.accountId()).cash());
                assertEquals(expectedCash, paid.paid());
                assertEquals(paid.face().subtract(expectedCash), DividendModel.holderNetReceivable(paid));
            }
            assertEquals(funded ? new BigDecimal("0.00") : total, w.engineeringContext(ISSUER).orElseThrow().unpaidDividends());
            if (!funded) assertTrue(w.dividendReports().stream().anyMatch(r -> SCHEME.equals(r.schemeId())
                    && !r.accepted() && "INSUFFICIENT_CASH".equals(r.reason())));
            assertTrue(w.snapshot().exchange().orElseThrow().trades().isEmpty());
            assertEquals(new BigDecimal("447500.00"), w.snapshot().accounts().values().stream()
                    .map(account -> account.cash()).reduce(new BigDecimal("0.00"), BigDecimal::add));
        }
        var bytes = Files.readAllBytes(file);
        try (var w = ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(file)) {
            assertEquals(funded ? DividendModel.Phase.SETTLED : DividendModel.Phase.EX_BOOKED,
                    w.snapshot().dividends().orElseThrow().schemes().get(SCHEME).phase());
            assertEquals(payAt, w.snapshot().timeMillis());
        }
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void onlyEligibleSeatsSeeTheToolsAndOthersCannotSubmitThem() throws Exception {
        try (var w = ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(dir.resolve("roles.journal"))) {
            var chair = w.autonomyRoleObservation("alice");
            for (String tool : List.of(CONVENE, PROPOSE, AUTHORIZE)) assertTrue(offers(chair,tool));
            var member = w.autonomyRoleObservation("bob");
            assertTrue(offers(member,PROPOSE)); assertFalse(offers(member,CONVENE)); assertFalse(offers(member,AUTHORIZE));
            var outsider = call(w,"investor","unauthorized");
            assertFalse(outsider.allowedTools().contains(PROPOSE));
            assertNull(reply(w, outsider, "investor", PROPOSE, terms().put("schemeId",SCHEME).put("perShare","0.01")
                    .put("recordAtMillis",30000).put("exAtMillis",40000).put("payAtMillis",50000)));
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED, w.snapshot().decisions().orElseThrow().state().decisions().get(0).outcome());
            var otherIssuer = call(w, "alice", "other-issuer");
            assertTrue(otherIssuer.allowedTools().contains(CONVENE));
            var request = terms().put("companyPartyId","north").put("materialDeadline",60_000)
                    .put("opensAt",90_000).put("closesAt",150_000);
            assertNull(reply(w, otherIssuer, "alice", CONVENE, request));
            assertTrue(w.boardOfCompany("north").orElseThrow().board().meetings().isEmpty());
            assertTrue(w.snapshot().dividends().orElseThrow().schemes().isEmpty());
        }
        var oldFile = dir.resolve("old.journal");
        try (var old = ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(oldFile)) {
            for (var tool : List.of(CONVENE, PROPOSE, AUTHORIZE)) assertFalse(offers(old.autonomyRoleObservation("alice"),tool));
        }
        var oldBytes = Files.readAllBytes(oldFile);
        try (var w = ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(oldFile)) {
            assertFalse(offers(w.autonomyRoleObservation("alice"), PROPOSE));
        }
        assertArrayEquals(oldBytes, Files.readAllBytes(oldFile));
    }

    @Test void theOrdinaryConveningToolCannotSilentlySubstituteABudgetForADividend() throws Exception {
        try (var w = ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(dir.resolve("wrong-topic.journal"))) {
            var ordinary = call(w,"alice","wrong-topic");
            assertTrue(ordinary.allowedTools().contains("board.convene"));
            reply(w, ordinary, "alice", "board.convene", terms().put("topic","DIVIDEND_AUTHORIZATION")
                    .put("materialDeadline",60_000).put("opensAt",90_000).put("closesAt",150_000));
            w.advanceTo(20_000);
            assertFalse(w.boardOfCompany(ISSUER).orElseThrow().board().meetings().containsKey(MEETING));
        }
    }
}
