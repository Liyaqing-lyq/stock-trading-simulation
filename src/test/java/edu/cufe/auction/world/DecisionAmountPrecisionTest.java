package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.composed.CompanyOpeningDocuments;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DecisionAmountPrecisionTest {
    @TempDir Path dir;

    @Test void exactNumericAndTextLimitsSurviveSchedulingAndStrictReopen() throws Exception {
        for (String literal : List.of("123456789012345.68", "\"123456789012345.68\"", "1.2345678901234568e14")) {
            String name = literal.startsWith("\"") ? "text" : literal.contains("e") ? "exponent" : "number";
            assertBudget(true, name, literal, "123456789012345.68");
        }
    }

    @Test void subcentNumericLimitStaysRejectedOnStrictReopen() throws Exception {
        assertBudget(true, "subcent", "12.3400000000000001", null);
        assertBudget(true, "below-cent", "0.0099999999999999999", null);
    }

    @Test void frozenOlderDecisionsKeepTheirOriginalRoundedNumbers() throws Exception {
        assertBudget(false, "historic-cent", "123456789012345.68", "123456789012345.69");
        assertBudget(false, "historic-subcent", "12.3400000000000001", "12.34");
    }

    private void assertBudget(boolean exact, String name, String literal, String expected) throws Exception {
        Path journal = dir.resolve(name + ".journal");
        try (var w = exact ? ComposedIndustryScenario.openExactDecimalTenCompanyStudy(journal)
                : ComposedIndustryScenario.openRoleArgumentsTenCompanyStudy(journal)) {
            openMeeting(w);
            // 数值原话直接交给正常回执入口。中间不经过 JsonNode 或 double。
            String body = """
                    {"tool":"board.proposeBudget","args":{"companyPartyId":"eng-a","meetingId":"M",
                    "lines":[{"period":0,"category":"PROCUREMENT","amount":%s}]},
                    "decision":{"objective":"director:eng-a","basis":"按原金额提交本期采购上限","reviewAfterMillis":60000}}
                    """.formatted(literal);
            var pending = decide(w, "alice", "board.proposeBudget", body);
            if (expected == null) assertNull(pending);
            else {
                assertNotNull(pending);
                w.advanceTo(pending.dueMillis() + 60_000L);
            }
            assertRegistered(w, expected);
        }
        // 最新普通工厂须按磁盘清单选择包括旧版本在内的历史解析语义。
        try (var reopened = ComposedIndustryScenario.openExactDecimalTenCompanyStudy(journal)) {
            assertRegistered(reopened, expected);
        }
    }

    @Test void excessFractionCannotAuthorizeAFundOrderAtTheManagersExactCeiling() throws Exception {
        for (boolean exact : List.of(false, true)) {
            for (String price : List.of("10.10", "10.1000000000000001")) {
                boolean authorized = !exact || "10.10".equals(price);
                Path journal = dir.resolve("fund-" + exact + "-" + price + ".journal");
                BigDecimal cash;
                try (var w = exact ? ComposedIndustryScenario.openExactDecimalTenCompanyStudy(journal)
                        : ComposedIndustryScenario.openRoleArgumentsTenCompanyStudy(journal)) {
                    long deadline = prepareTarget(w);
                    cash = w.snapshot().accounts().get("ACC-FUND").cash();
                    String body = """
                            {"tool":"role.target.review","args":{"targetId":"T","expectedVersion":1,"approved":true,
                            "maxTotalQuantity":8,"maxChildQuantity":3,"priceLimit":%s,"validUntilMillis":%d,
                            "reason":"核对产品条款和母目标。"},
                            "decision":{"objective":"director:eng-a","basis":"按母目标核对产品授权","reviewAfterMillis":60000}}
                            """.formatted(price, deadline);
                    var pending = decide(w, "carol", "role.target.review", body);
                    if (pending != null) w.advanceTo(pending.dueMillis());
                    assertFund(w, authorized, cash);
                }
                try (var reopened = ComposedIndustryScenario.openExactDecimalTenCompanyStudy(journal)) {
                    assertFund(reopened, authorized, cash);
                }
            }
        }
    }

    private static DecisionRun.ActionRef decide(WorldRuntime w, String person, String tool, String body) throws Exception {
        String action = "submitted-decision";
        w.beginDecisionSession("session");
        w.openDecisions("open", List.of(new DecisionRun.Request(person, action, "wake",
                new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1, 25, 1_000_000, 2048, 1))));
        var call = w.dispatchLiveDecision("dispatch", action).call();
        assertNotNull(call);
        assertTrue(call.allowedTools().contains(tool));
        assertEquals(WorldRuntime.CompletionOffer.QUEUED, w.offerLiveDecisionCompletion(
                LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(), action, call.attemptId(),
                        call.attemptNo(), call.recoveryGeneration(), body,
                        DecisionModel.Usage.unknownCost(1, 100, 30), "fixture:raw-amount"))));
        w.drainDecisionCallbacks();
        return w.snapshot().decisions().orElseThrow().actionRef(action);
    }

    private static long prepareTarget(WorldRuntime w) throws Exception {
        w.advanceTo(34_200_000L);
        String mandate = "fund-mandate:cufe:fund-a";
        for (String person : List.of("alice", "bob", "carol")) read(w, person, mandate);
        long at = w.snapshot().timeMillis()+1;
        w.openRoleSession("alice").submitResearch("research", "research", at, new WorldRoleSession.ResearchDraft(
                "R", "cufe", "fund-a", "DIGITAL", "analysis", "数字服务", "本人已阅读产品委托。",
                List.of(new WorldRoleSession.Citation(mandate, 1))));
        w.advanceTo(at);
        read(w, "bob", "analysis");
        long held = RoleTradingModel.held(w.snapshot().securities().orElseThrow(), "ACC-FUND", "DIGITAL");
        at = w.snapshot().timeMillis()+1;
        long deadline = at+4_000_000;
        w.openRoleSession("bob").setTarget("target", "target", at, new WorldRoleSession.TargetDraft("T", 0, "cufe",
                "fund-a", "ACC-FUND", "DIGITAL", held+8, new BigDecimal("10.10"), deadline, "R"));
        w.advanceTo(at);
        return deadline;
    }

    private static void assertFund(WorldRuntime w, boolean authorized, BigDecimal cash) throws Exception {
        var trading = w.snapshot().trading().orElseThrow().trading();
        assertEquals(cash, w.snapshot().accounts().get("ACC-FUND").cash());
        assertTrue(trading.children().isEmpty());
        assertEquals(authorized ? 1 : 0, trading.reviews().size());
        if (authorized) {
            var review = trading.reviews().get(0);
            assertTrue(review.approved());
            assertEquals(new BigDecimal("10.10"), review.priceLimit());
            assertEquals("carol", review.officerPersonPartyId());
        }
    }

    private static void read(WorldRuntime w, String person, String doc) throws Exception {
        String id = "read-" + person + "-" + doc;
        long at = w.snapshot().timeMillis()+1;
        w.openRoleSession(person).startReading(id, id, at, doc, 1);
        w.advanceTo(at);
        var reading = w.roleView(person).readings().stream()
                .filter(row -> row.ref().docId().equals(doc)).findFirst().orElseThrow();
        w.advanceTo(reading.dueAt());
    }

    private static void openMeeting(WorldRuntime w) throws Exception {
        String doc = CompanyOpeningDocuments.documentId("eng-a");
        w.advanceTo(34_200_000L);
        read(w, "alice", doc);
        long at = w.snapshot().timeMillis()+2;
        w.scheduleEngineeringConvene("call","call",at,"eng-a","alice","M",at+500_000,at+600_000,at+900_000);
        w.scheduleEngineeringAttend("attend","attend",at+3,"eng-a","alice","M",false);
        w.scheduleEngineeringMaterial("attach","attach",at+6,"eng-a","alice","M","doc",doc,1);
        w.advanceTo(at+10);
    }

    private static void assertRegistered(WorldRuntime w, String expected) throws Exception {
        JsonNode view = w.autonomyRoleObservation("alice");
        var rows = view.at("/engineering/directorships/0/meetings/0/proposedLines");
        var result = view.at("/engineering/deliberation/lastActionResult");
        if (expected == null) {
            assertTrue(rows.isEmpty());
            assertEquals("TECHNICAL_FAILED", result.required("outcome").asText());
            assertTrue(result.required("protocolReason").asText().contains("精确到分"));
        } else {
            assertEquals("OK", result.required("reason").asText());
            assertEquals(1, rows.size());
            assertEquals(expected, rows.get(0).required("amount").asText());
        }
    }
}
