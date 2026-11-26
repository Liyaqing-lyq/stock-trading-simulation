package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IssuerCreditDecisionTest {
    @TempDir Path dir;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BANK = ComposedWorldScenario.BANK, TECH = ComposedManufacturingThreeScenario.TECH;
    static final String BANKER = "erin", BORROWER = ComposedManufacturingThreeScenario.TECH_MANAGER;
    static final long TERM = 30 * 86_400_000L;

    static JsonNode view(WorldRuntime w, String who) throws Exception { return w.autonomyRoleObservation(who); }
    static JsonNode credit(WorldRuntime w, String who) throws Exception { return view(w,who).at("/engineering/issuerCredit"); }
    static boolean tool(JsonNode v, String name) {
        for (var t : v.path("tools")) if (name.equals(t.path("name").asText())) return true;
        return false;
    }
    static void appoint(WorldRuntime w, String company, String who) throws Exception {
        ComposedWorldSelfTest.appoint(w, w.snapshot().timeMillis()+1,company,who,company,"credit-office-"+company);
    }
    static ObjectNode terms(String id, String amount) {
        return JSON.createObjectNode().put("loanId",id).put("bank",BANK).put("borrower",TECH)
                .put("amount",amount).put("rateU",20000).put("tenorMs",TERM).put("purpose","营运现金周转");
    }
    static ObjectNode loan(String id) { return JSON.createObjectNode().put("loanId",id); }
    static DecisionRun.ActionRef decide(WorldRuntime w,String who,String id,String tool,ObjectNode args) throws Exception {
        w.beginDecisionSession("session-"+id);
        w.openDecisions("open-"+id, List.of(new DecisionRun.Request(who,id,"wake",
                new DecisionModel.Route("governance","key-1","deepseek-flash",1L,25,1_000_000,2048,1))));
        var call=w.dispatchLiveDecision("dispatch-"+id,id).call();
        assertNotNull(call,id);
        var input=JSON.readTree(call.inputText());
        assertTrue(tool(input,tool),tool);
        var reply=JSON.createObjectNode().put("tool",tool);
        reply.set("args",args);
        reply.putObject("decision").put("objective",input.at("/engineering/deliberation/objectives/0/id").asText())
                .put("basis","分项检验授信角色的实际回报").put("reviewAfterMillis",60000);
        w.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),
                call.actionId(),call.attemptId(),call.attemptNo(),call.recoveryGeneration(),reply.toString(),
                DecisionModel.Usage.unknownCost(1,100,30),"fixture:issuer-credit")));
        w.drainDecisionCallbacks();
        return w.snapshot().decisions().orElseThrow().actionRef(id);
    }
    static JsonNode execute(WorldRuntime w,String who,String id,String tool,ObjectNode args) throws Exception {
        var ref=decide(w,who,id,tool,args);
        assertNotNull(ref,id);
        w.advanceTo(ref.dueMillis()+60_000L);
        var result=view(w,who).at("/engineering/deliberation/lastActionResult");
        assertEquals(id,result.path("actionId").asText(),result.toString());
        return result;
    }
    static void applied(WorldRuntime w,String who,String id,String tool,ObjectNode args) throws Exception {
        var result=execute(w,who,id,tool,args);
        assertEquals("APPLIED",result.path("execution").asText(),result.toString());
    }

    @Test void loanDecisionsGateBothSidesAndSettleFromActualDeposit() throws Exception {
        Path file=dir.resolve("model-credit.journal");
        try(var w=ComposedIndustryScenario.openIssuerCreditDecisionsOperating(file)) {
            assertFalse(tool(view(w,BORROWER),"bank.loan.request"));
            appoint(w,TECH,BORROWER); appoint(w,BANK,BANKER);
            assertFalse(tool(view(w,BORROWER),"bank.loan.approve"));
            assertFalse(tool(view(w,BANKER),"bank.loan.request"));
            assertFalse(tool(view(w,"alice"),"bank.loan.approve"));
            assertFalse(credit(w,BANKER).at("/managedBanks/0/riskStatus").asText().isBlank());
            assertNull(decide(w,BORROWER,"cross-company","bank.loan.request",terms("other","200.00").put("borrower","aged-company")));
            applied(w,BORROWER,"apply","bank.loan.request",terms("working","200.00"));
            assertEquals("PROPOSED",credit(w,BANKER).at("/contracts/0/status").asText());
            var early=execute(w,BANKER,"early","bank.loan.approve",loan("working"));
            assertEquals("REJECTED",early.path("execution").asText());
            assertTrue(early.path("businessReject").asText().contains("borrower-not-accepted"));
            applied(w,BORROWER,"accept","bank.loan.accept",loan("working"));
            applied(w,BANKER,"approve","bank.loan.approve",loan("working"));
            assertEquals("0.00",credit(w,BORROWER).at("/managedBorrowers/0/deposit").asText());
            applied(w,BORROWER,"draw","bank.loan.draw",loan("working"));
            assertEquals("200.00",credit(w,BORROWER).at("/managedBorrowers/0/deposit").asText());
            var cash=JSON.createObjectNode().put("bank",BANK).put("borrower",TECH).put("amount","50.00");
            applied(w,BORROWER,"withdraw","bank.cash.withdraw",cash);
            assertEquals("150.00",credit(w,BORROWER).at("/managedBorrowers/0/deposit").asText());
            applied(w,BORROWER,"redeposit","bank.cash.deposit",cash.put("amount","50.33"));
            long maturity=credit(w,BORROWER).at("/contracts/0/maturityAt").longValue();
            w.advanceTo(maturity+1);
            assertEquals("0.33",credit(w,BORROWER).at("/contracts/0/interestDue").asText());
            applied(w,BORROWER,"interest","bank.loan.payInterest",loan("working").put("amount","0.33"));
            applied(w,BORROWER,"repay","bank.loan.repay",loan("working").put("amount","200.00"));
            assertEquals("CLOSED",credit(w,BORROWER).at("/contracts/0/status").asText());
        }
        try(var w=ComposedIndustryScenario.openIssuerCreditDecisionsOperating(file)) {
            assertEquals("0.33",credit(w,BANKER).at("/contracts/0/interestPaid").asText());
            assertEquals("0.00",credit(w,BORROWER).at("/managedBorrowers/0/deposit").asText());
            assertTrue(credit(w,"alice").isMissingNode());
            var report=ComposedCompanyReport.of(w,TECH);
            assertTrue(report.balanced());
            assertEquals(new java.math.BigDecimal("0.33"),report.expense().get("借款利息费用（累计）"));
        }
    }

    @Test void approvedOversizedLoanCanBeRejectedAtDrawWhileOtherIssuersCannotSeeOrAcceptIt() throws Exception {
        try(var w=ComposedIndustryScenario.openIssuerCreditDecisionsOperating(dir.resolve("risk.journal"))) {
            appoint(w,TECH,BORROWER);
            appoint(w,AgedIndustryConfig.COMPANY,AgedIndustryConfig.MANAGER_PERSON);
            appoint(w,BANK,BANKER);
            applied(w,BORROWER,"large-apply","bank.loan.request",terms("large","100000000.00"));
            var other=AgedIndustryConfig.MANAGER_PERSON;
            assertTrue(credit(w,other).path("contracts").isEmpty());
            assertNull(decide(w,other,"try-accept","bank.loan.accept",loan("large")));
            applied(w,BORROWER,"agree","bank.loan.accept",loan("large"));
            applied(w,BANKER,"approve-large","bank.loan.approve",loan("large"));
            var result=execute(w,BORROWER,"draw-large","bank.loan.draw",loan("large"));
            assertEquals("REJECTED",result.path("execution").asText());
            assertTrue(result.path("businessReject").asText().startsWith("CREDIT_BLOCKED_BY_BREACH"),result.toString());
            assertEquals("0.00",credit(w,BORROWER).at("/managedBorrowers/0/deposit").asText());
            assertEquals("0.00",credit(w,BANKER).at("/contracts/0/principalDue").asText());
        }
    }

    @Test void resignationBetweenStartAndCompletionRejectsWithoutMovingFunds() throws Exception {
        try(var w=ComposedIndustryScenario.openIssuerCreditDecisionsOperating(dir.resolve("term.journal"))) {
            appoint(w,TECH,BORROWER);
            var ref=decide(w,BORROWER,"last-job","bank.loan.request",terms("end-term","200.00"));
            assertNotNull(ref);
            w.advanceTo(ref.dueMillis());
            String office=w.snapshot().roles().orElseThrow().access().appointments().stream().filter(a->
                    a.personPartyId().equals(BORROWER) && a.role()==RoleAccess.Role.COMPANY_MANAGER
                    && a.activeAt(w.snapshot().timeMillis())).findFirst().orElseThrow().id();
            w.scheduleRevokeAppointment("end-office","end-office",ref.dueMillis()+1,office);
            w.advanceTo(ref.dueMillis()+60_000L);
            assertTrue(w.bankBook().loans().isEmpty());
            assertFalse(tool(view(w,BORROWER),"bank.loan.request"));
            var result=view(w,BORROWER).at("/engineering/deliberation/lastActionResult");
            assertEquals("REJECTED",result.path("execution").asText(),result.toString());
        }
    }
}
