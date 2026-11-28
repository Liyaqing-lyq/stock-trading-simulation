package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.RoleTradingModel;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.IssuerCreditDecisionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FundReviewAndBudgetArgumentsTest {
    @TempDir Path dir;

    private static WorldRuntime open(Path file, boolean current) throws Exception {
        return current ? ComposedIndustryScenario.openRoleArgumentsTenCompanyStudy(file)
                : ComposedIndustryScenario.openCrisisArgumentsTenCompanyStudy(file);
    }

    private static JsonNode menu(WorldRuntime w, String person, String tool) throws Exception {
        for (var row : view(w, person).required("tools"))
            if (tool.equals(row.required("name").asText())) return row;
        throw new AssertionError(tool);
    }

    @Test void aVetoCanUseZeroQuantitiesButNeedsTheTargetsPriceAndTimeRules() throws Exception {
        String tool = "role.target.review", mandate = "fund-mandate:cufe:fund-a";
        var file = dir.resolve("risk.journal");
        try (var w = open(file, true)) {
            w.advanceTo(34_200_000L);
            for (var person : List.of("alice", "bob", "carol"))
                EditorialReviewFeedbackTest.read(w, person, mandate, 1);
            long at = w.snapshot().timeMillis() + 1;
            w.openRoleSession("alice").submitResearch("note", "note", at, new WorldRoleSession.ResearchDraft(
                    "R", "cufe", "fund-a", "DIGITAL", "analysis", "核对", "已读产品委托。",
                    List.of(new WorldRoleSession.Citation(mandate, 1))));
            w.advanceTo(at);
            EditorialReviewFeedbackTest.read(w, "bob", "analysis", 1);
            long held = RoleTradingModel.held(w.snapshot().securities().orElseThrow(), "ACC-FUND", "DIGITAL");
            at = w.snapshot().timeMillis() + 1;
            long deadline = at + 4_000_000;
            w.openRoleSession("bob").setTarget("target", "target", at, new WorldRoleSession.TargetDraft("T",
                    0, "cufe", "fund-a", "ACC-FUND", "DIGITAL", held + 8, new BigDecimal("10.10"), deadline, "R"));
            w.advanceTo(at);
            var shape = menu(w, "carol", tool).required("reviewArguments");
            assertTrue(shape.required("rejected").asText().contains("同时为 0"));
            assertTrue(shape.required("priceLimit").asText().contains("正价格"));
            assertTrue(shape.required("validUntilMillis").asText().contains("deadlineMillis"));
            var args = JSON.createObjectNode().put("targetId","T").put("expectedVersion",1).put("approved",false)
                    .put("maxTotalQuantity",0).put("maxChildQuantity",0).put("reason","风险过高")
                    .put("priceLimit","10.10").put("validUntilMillis",deadline);
            assertNull(decide(w,"carol","bad-price",tool,args.deepCopy().put("priceLimit","0.00")));
            assertNull(decide(w,"carol","bad-time",tool,args.deepCopy().put("validUntilMillis",0)));
            assertEquals("INVALID_LIMITS",execute(w,"carol","past-target",tool,args.deepCopy()
                    .put("validUntilMillis",deadline + 1)).required("reason").asText());
            applied(w,"carol","veto",tool,args);
        }
        try (var w = open(file, true)) {
            var reviews = w.snapshot().trading().orElseThrow().trading().reviews();
            assertEquals(1, reviews.size());
            assertFalse(reviews.get(0).approved());
            assertTrue(w.snapshot().trading().orElseThrow().trading().children().isEmpty());
        }
    }

    @Test void missingBudgetFieldsStayHistoricalAndAnExplicitZeroStillProposesZero() throws Exception {
        String tool = "board.proposeBudget", company = "eng-a", doc = CompanyOpeningDocuments.documentId(company);
        for (boolean current : List.of(false, true)) {
            var file = dir.resolve("budget-" + current + ".journal");
            try (var w = open(file, current)) {
                w.advanceTo(34_200_000L);
                EditorialReviewFeedbackTest.read(w, "alice", doc, 1);
                long at = w.snapshot().timeMillis() + 2;
                ConnectedWorldBootstrap.convene(w,"call","call",at,company,"alice","M",at+500_000,at+600_000,at+900_000,false);
                ConnectedWorldBootstrap.attend(w,"attend","attend",at+3,company,"alice","M");
                ConnectedWorldBootstrap.material(w,"attach","attach",at+6,company,"alice","M","doc",doc,1);
                w.advanceTo(at+10);
                assertEquals(current, menu(w,"alice",tool).has("lines"));
                ObjectNode args = JSON.createObjectNode().put("companyPartyId",company).put("meetingId","M");
                var line = args.putArray("lines").addObject().put("category","PROCUREMENT");
                if (current) {
                    assertNull(decide(w,"alice","missing-both",tool,args));
                    line.put("period",0);
                    assertNull(decide(w,"alice","missing-amount",tool,args));
                    int sample = 0;
                    for (String bad : List.of("{}", "[]", "false", "null", "\"bad\"", "-0.01", "0.001")) {
                        line.set("amount", JSON.readTree(bad));
                        assertNull(decide(w,"alice","bad-amount-" + sample++,tool,args));
                        var reason = view(w,"alice").at("/engineering/deliberation/lastActionResult/protocolReason");
                        assertTrue(reason.asText().contains("amount 须为非负金额，精确到分"), reason.asText());
                    }
                    line.put("amount","0.00");
                }
                applied(w,"alice","propose",tool,args);
            }
            try (var w = open(file,current)) {
                var meeting = view(w,"alice").at("/engineering/directorships/0/meetings/0");
                var line = meeting.required("proposedLines").get(0);
                assertEquals("0.00",line.required("amount").asText());
                assertEquals(0,line.required("period").asInt());
                assertTrue(meeting.required("proposalPresent").asBoolean());
            }
        }
    }
}
