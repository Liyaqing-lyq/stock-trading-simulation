package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic authority/projection tests; injected replies are not model behavior evidence. */
class RoleGovernanceOutcomeContextTest {
    @TempDir Path dir;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String COMPANY = "mfg-tech", CHAIR = "mfgtech-d1", OTHER = "mfgtech-d2";
    static WorldRuntime open(Path path) throws Exception {
        return ComposedIndustryScenario.openContextualInformedOperating(path);
    }
    static List<DecisionRun.Call> implement(WorldRuntime w, String meeting, String... persons) throws Exception {
        return implement(w,meeting,new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20, 1000000, 1024, 4),persons);
    }
    static List<DecisionRun.Call> implement(WorldRuntime w,String meeting,DecisionModel.Route route,String...persons) throws Exception {
        w.beginDecisionSession("session-"+meeting+"-"+w.snapshot().position());
        var requests = new ArrayList<DecisionRun.Request>();
        for (String person : persons) requests.add(new DecisionRun.Request(person, "implement-"+meeting+"-"+person, "wake",
                route));
        w.openDecisions("open-"+meeting, requests);
        var calls = new ArrayList<DecisionRun.Call>();
        for (var request : requests) calls.add(w.dispatchLiveDecision("dispatch-"+request.actionId(), request.actionId()).call());
        for (var call : calls) {
            assertNotNull(call);
            var in=JSON.readTree(call.inputText());var reply=JSON.createObjectNode().put("tool","board.implement");
            reply.putObject("args").put("companyPartyId", COMPANY).put("meetingId", meeting);
            reply.putObject("decision").put("objective","director:"+COMPANY).put("basis","fixture only").put("reviewAfterMillis",60000);
            w.offerDecisionCallback(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),call.attemptNo(),call.recoveryGeneration(),reply.toString(),DecisionModel.Usage.unknownCost(1,50,30),"TEST"));
        }
        w.drainDecisionCallbacks();
        w.advanceTo(w.snapshot().timeMillis()+2000);
        return calls;
    }
    static JsonNode result(WorldRuntime w,String p)throws Exception{return w.autonomyRoleObservation(p).path("engineering").path("deliberation").path("lastActionResult");}
    @Test void duplicateImplementationKeepsOwnRejectionAndShowsActualFutureThenActiveAppointment() throws Exception {
        try(var w=open(dir.resolve("duplicate.journal"))) {
            long closes=RolePlanningTest.approvedAppointment(w,10000);
            var calls=implement(w,"appt",CHAIR,OTHER);
            for(var call:calls)assertEquals("ADOPTED",JSON.readTree(call.inputText()).path("engineering").path("directorships").get(0).path("meetings").get(0).path("status").asText());
            assertEquals(1,w.snapshot().roles().orElseThrow().access().appointments().stream().filter(a->a.id().equals("test-manager")).count());
            var results=List.of(result(w,CHAIR),result(w,OTHER));
            assertEquals(1,results.stream().filter(r->r.path("accepted").asBoolean()).count());
            var failed=results.stream().filter(r->!r.path("accepted").asBoolean()).findFirst().orElseThrow();
            assertEquals("NOT_ADOPTED",failed.path("reason").asText());
            assertEquals("REJECTED",failed.path("execution").asText());
            var ctx=failed.path("targetMeetingContext");
            assertEquals("IMPLEMENTED",ctx.path("currentStatus").asText(),"missing target context");
            assertEquals("NOT_EFFECTIVE_YET",ctx.path("currentEffect").path("appointmentStatus").asText());
            w.advanceTo(closes+10000);
            assertEquals("ACTIVE",result(w,CHAIR).path("targetMeetingContext").path("currentEffect").path("appointmentStatus").asText());
        }
    }
    static ObjectNode reproject(WorldRuntime w,ObjectNode engineering,String person,long now,DecisionRun run,RoleRun roles) throws Exception {
        var method=Class.forName("edu.cufe.auction.world.RoleGovernanceOutcomeContext").getDeclaredMethod("append",ObjectNode.class,String.class,long.class,DecisionRun.class,EngineeringRun.class,RoleRun.class,java.util.function.Function.class);
        method.setAccessible(true);
        java.util.function.Function<String,BoardRun> boards=c->w.boardOfCompany(c).orElse(null);
        method.invoke(null,engineering,person,now,run,w.snapshot().engineering().orElseThrow(),roles,boards);
        return engineering;
    }
    static ObjectNode eng(WorldRuntime w) throws Exception{return (ObjectNode)w.autonomyRoleObservation(CHAIR).path("engineering");}
    static JsonNode context(ObjectNode eng){return eng.path("deliberation").path("lastActionResult").path("targetMeetingContext");}
    @Test void originalV5CommandsAndRawRepliesRecoverExactlyWithRelativeReviews() throws Exception {
        Path path=dir.resolve("recover.journal");String before;DecisionRun decisions;long position;
        try(var w=open(path)){
            RolePlanningTest.approvedAppointment(w,10000);implement(w,"appt",CHAIR,OTHER);
            decisions=w.snapshot().decisions().orElseThrow();position=w.snapshot().position();
            before=w.autonomyRoleObservation(CHAIR).toString();
            var last=JSON.readTree(before).path("engineering").path("deliberation").path("recentDecisions").get(0);
            assertEquals(60000,last.path("reviewAfterMillis").asLong());
            assertEquals(last.path("derivedReviewAt").asLong(),w.autonomyNextReviewAt(CHAIR,-1));
        }
        try(var w=open(path)){
            assertEquals(position,w.snapshot().position());assertEquals(decisions,w.snapshot().decisions().orElseThrow());
            assertEquals(before,w.autonomyRoleObservation(CHAIR).toString());
            assertEquals("IMPLEMENTED",context(eng(w)).path("currentStatus").asText());
        }
        assertThrows(Exception.class,()->{try(var wrong=ComposedIndustryScenario.openInformedOperating(path)){} });
    }
    @Test void exactEffectBoundariesMissingRecordAndActualRevocation() throws Exception {
        try(var w=open(dir.resolve("boundaries.journal"))){
            long closes=RolePlanningTest.approvedAppointment(w,10000);implement(w,"appt",CHAIR);
            var run=w.snapshot().decisions().orElseThrow();var roles=w.snapshot().roles().orElseThrow();
            for(long[] pair:new long[][]{{closes+9999,0},{closes+10000,1},{closes+89999999,1},{closes+90000000,2}}){
                var projected=reproject(w,eng(w),CHAIR,pair[0],run,roles);
                assertEquals(new String[]{"NOT_EFFECTIVE_YET","ACTIVE","EXPIRED"}[(int)pair[1]],context(projected).path("currentEffect").path("appointmentStatus").asText());
            }
            var access=roles.access();
            var missing=new RoleRun(new RoleAccess.State(access.accounts(),access.personAccounts(),access.mandates(),access.appointments().stream().filter(a->!a.id().equals("test-manager")).toList()),roles.reports());
            assertEquals("UNKNOWN",context(reproject(w,eng(w),CHAIR,closes+10000,run,missing)).path("currentEffect").path("appointmentStatus").asText());
            w.scheduleRevokeAppointment("revoke","revoke-task",closes+10001,"test-manager");w.advanceTo(closes+10001);
            assertEquals("REVOKED",context(eng(w)).path("currentEffect").path("appointmentStatus").asText());
        }
    }
    @Test void implementedRevocationRequiresTheActualRevokedRecord() throws Exception {
        try(var w=open(dir.resolve("board-revoke.journal"))){
            long first=RolePlanningTest.approvedAppointment(w,10000);implement(w,"appt",CHAIR);w.advanceTo(first+10000);
            long b=w.snapshot().timeMillis()+1000,opens=b+1000,closes=b+10000;
            var voters=ComposedManufacturingThreeScenario.TECH_DIRECTORS.subList(0,4);
            w.scheduleCompanyBoardConvene("rc","rct",b,COMPANY,CHAIR,"revocation",b+500,opens,closes,true);
            for(int i=0;i<4;i++)w.scheduleCompanyBoardAttend("ra"+i,"rat"+i,b+10+i,COMPANY,voters.get(i),"revocation");
            w.scheduleCompanyBoardMaterial("rm","rmt",b+30,COMPANY,CHAIR,"revocation","m1","planning-appt-doc",1);
            w.scheduleCompanyBoardAppointmentProposal("rp","rpt",b+40,COMPANY,CHAIR,"revocation",new BoardModel.AppointmentRequest("test-manager","mfgtech-mgr",COMPANY,RoleAccess.Role.COMPANY_MANAGER,BoardModel.AppointmentAction.REVOKE,closes+10000,null));
            for(int i=0;i<4;i++)w.scheduleCompanyBoardVote("rv"+i,"rvt"+i,opens+1000+i,COMPANY,voters.get(i),"revocation",BoardModel.Vote.YES,1000);
            w.advanceTo(closes);var before=w.snapshot().roles().orElseThrow();implement(w,"revocation",CHAIR,OTHER);
            var current=context(eng(w));assertEquals("IMPLEMENTED",current.path("currentStatus").asText());
            assertEquals("REVOKED",current.path("currentEffect").path("appointmentStatus").asText());
            var revoked=w.snapshot().roles().orElseThrow().access().appointments().stream().filter(a->a.id().equals("test-manager")).findFirst().orElseThrow();
            assertEquals(revoked.revokedAt().longValue(),current.path("currentEffect").path("revokedAt").asLong());
            var missingRealRevoke=reproject(w,eng(w),CHAIR,w.snapshot().timeMillis(),w.snapshot().decisions().orElseThrow(),before);
            assertEquals("UNKNOWN",context(missingRealRevoke).path("currentEffect").path("appointmentStatus").asText());
        }
    }
    @Test void prematureImplementationIsStillConvenedAndDoesNotInventAnEffect() throws Exception {
        try(var w=open(dir.resolve("premature.journal"))){
            w.scheduleCompanyBoardConvene("c","ct",1000,COMPANY,CHAIR,"early",100000,110000,120000,true);w.advanceTo(2000);
            implement(w,"early",CHAIR);
            var row=result(w,CHAIR);assertEquals("NOT_ADOPTED",row.path("reason").asText());assertFalse(row.path("accepted").asBoolean());
            assertEquals("CONVENED",row.path("targetMeetingContext").path("currentStatus").asText());
            assertFalse(row.path("targetMeetingContext").path("currentEffect").path("resolutionImplemented").asBoolean());
            assertFalse(row.path("targetMeetingContext").path("currentEffect").has("appointmentStatus"));
        }
    }
    @Test void concurrentBudgetImplementationsSettleOnceAndPreserveBothOriginalReports() throws Exception {
        try(var w=open(dir.resolve("concurrent-budget.journal"))){
            String meetingId="budget-contention";
            var voters=ComposedManufacturingThreeScenario.TECH_DIRECTORS.subList(0,4);
            OperationsFixture.publishAndRead(w,"contention-budget-doc",voters,"budget-read");
            long convenes=w.snapshot().timeMillis()+1000,opens=convenes+1000,closes=convenes+10000;
            long period=closes/ManufacturingTemplateModel.PERIOD_MILLIS;
            var procurement=new java.math.BigDecimal("3000.00");
            var labor=new java.math.BigDecimal("1200.00");
            var proposed=List.of(new BoardModel.BudgetLine(period,BoardModel.BudgetCategory.PROCUREMENT,procurement),
                    new BoardModel.BudgetLine(period,BoardModel.BudgetCategory.PRODUCTION_LABOR,labor));
            w.scheduleCompanyBoardConvene("budget-c","budget-ct",convenes,COMPANY,CHAIR,meetingId,convenes+500,opens,closes,false);
            for(int i=0;i<4;i++)w.scheduleCompanyBoardAttend("budget-a"+i,"budget-at"+i,convenes+10+i,COMPANY,voters.get(i),meetingId);
            w.scheduleCompanyBoardMaterial("budget-m","budget-mt",convenes+30,COMPANY,CHAIR,meetingId,"m1","contention-budget-doc",1);
            w.scheduleCompanyBoardBudgetProposal("budget-p","budget-pt",convenes+40,COMPANY,CHAIR,meetingId,proposed);
            for(int i=0;i<4;i++)w.scheduleCompanyBoardVote("budget-v"+i,"budget-vt"+i,opens+1000+i,COMPANY,voters.get(i),meetingId,BoardModel.Vote.YES,1000);
            w.advanceTo(closes);
            var adopted=w.boardOfCompany(COMPANY).orElseThrow().board();
            assertEquals(BoardModel.MeetingStatus.ADOPTED,adopted.meeting(meetingId).status());
            assertTrue(adopted.budgets().isEmpty(),"no authorization may precede the two accepted implementation intents");
            var calls=implement(w,meetingId,CHAIR,OTHER);
            var snap=w.snapshot();var run=snap.decisions().orElseThrow();
            var own=run.state().decisions().stream().filter(d->calls.stream().anyMatch(c->c.actionId().equals(d.actionId()))).toList();
            assertEquals(2,own.size());
            assertEquals(own.get(0).cutoffPosition(),own.get(1).cutoffPosition());
            assertEquals(own.get(0).cutoffTimeMillis(),own.get(1).cutoffTimeMillis());
            var proof=JSON.createObjectNode().put("case","same-ADOPTED-budget-two-directors");
            var frozen=proof.putArray("frozenInputs");
            for(var decision:own){
                assertEquals(DecisionModel.Status.RECORDED,decision.status());
                assertEquals(DecisionModel.Outcome.INTENT,decision.outcome());
                assertEquals("board.implement",decision.recordedTool());
                assertTrue(decision.lastAttempt().settledDone());assertTrue(decision.lastAttempt().replyReceived());
                assertEquals(DecisionModel.Outcome.INTENT,decision.lastAttempt().outcome());
                assertEquals("board.implement",run.actionRef(decision.actionId()).tool());
                var input=JSON.readTree(decision.inputText());
                assertEquals(closes,input.path("time").asLong());
                var seen=input.path("engineering").path("directorships").get(0).path("meetings").get(0);
                assertEquals(meetingId,seen.path("meetingId").asText());assertEquals("BUDGET",seen.path("topic").asText());assertEquals("ADOPTED",seen.path("status").asText());
                frozen.addObject().put("person",decision.personPartyId()).put("actionId",decision.actionId())
                        .put("cutoffPosition",decision.cutoffPosition()).put("cutoffTimeMillis",decision.cutoffTimeMillis())
                        .put("inputDigest",decision.inputDigest()).set("meeting",seen.deepCopy());
            }
            var board=w.boardOfCompany(COMPANY).orElseThrow().board();
            assertEquals(BoardModel.MeetingStatus.IMPLEMENTED,board.meeting(meetingId).status());
            assertEquals(2,board.budgets().size());
            assertEquals(Set.of(new BoardModel.Budget(period,BoardModel.BudgetCategory.PROCUREMENT,procurement),
                    new BoardModel.Budget(period,BoardModel.BudgetCategory.PRODUCTION_LABOR,labor)),new HashSet<>(board.budgets()));
            var expectedCaps=JSON.createArrayNode();
            for(var budget:board.budgets())expectedCaps.addObject().put("period",period).put("category",budget.category().name()).put("cap",budget.cap().toPlainString());
            var results=new ArrayList<JsonNode>();var reports=new ArrayList<EngineeringRun.Report>();
            for(String person:List.of(CHAIR,OTHER)){
                var engineering=(ObjectNode)w.autonomyRoleObservation(person).path("engineering");
                var row=(ObjectNode)engineering.path("deliberation").path("lastActionResult");results.add(row);
                var ctx=row.path("targetMeetingContext");
                assertEquals(COMPANY,ctx.path("companyPartyId").asText());assertEquals(meetingId,ctx.path("meetingId").asText());
                assertEquals("IMPLEMENTED",ctx.path("currentStatus").asText());assertEquals(snap.timeMillis(),ctx.path("asOfMillis").asLong());
                assertTrue(ctx.path("currentEffect").path("resolutionImplemented").asBoolean());
                assertEquals(period,ctx.path("currentEffect").path("currentBudgetPeriod").asLong(-1));
                assertEquals(expectedCaps.toString(),ctx.path("currentEffect").path("authorizedBudgets").toString());
                var report=snap.engineering().orElseThrow().reports().get(row.path("resultTaskId").asText());
                assertNotNull(report);reports.add(report);
                assertEquals(report.commandId(),row.path("commandId").asText());assertEquals(report.kind(),row.path("resultKind").asText());
                assertEquals(report.accepted(),row.path("accepted").asBoolean());assertEquals(report.reason(),row.path("reason").asText());
                assertEquals(report.timeMillis(),row.path("resultAt").asLong());assertEquals(report.position(),row.path("resultPosition").asLong());
                var original=engineering.deepCopy();
                for(var history:original.path("deliberation").path("recentDecisions"))((ObjectNode)history).remove("targetMeetingContext");
                ((ObjectNode)original.path("deliberation").path("lastActionResult")).remove("targetMeetingContext");
                RoleActionFeedback.append(original,person,run,snap.pending(),snap.engineering().orElseThrow(),null,null,null);
                var withoutContext=row.deepCopy();withoutContext.remove("targetMeetingContext");
                assertEquals(original.path("deliberation").path("lastActionResult").toString(),withoutContext.toString(),"every original feedback field must survive contextual projection");
            }
            assertEquals(1,reports.stream().filter(EngineeringRun.Report::accepted).count());
            assertEquals(1,results.stream().filter(r->r.path("accepted").asBoolean()).count());
            var rejected=results.stream().filter(r->!r.path("accepted").asBoolean()).findFirst().orElseThrow();
            assertEquals("NOT_ADOPTED",rejected.path("reason").asText());assertEquals("NOT_ADOPTED",rejected.path("businessReject").asText());assertEquals("REJECTED",rejected.path("execution").asText());
            assertEquals("APPLIED",results.stream().filter(r->r.path("accepted").asBoolean()).findFirst().orElseThrow().path("execution").asText());
            assertEquals(snap,w.snapshot(),"projection must not alter authoritative caps or reports");
            proof.set("authorizedBudgets",expectedCaps);proof.set("originalReports",JSON.valueToTree(reports));proof.set("contextualResults",JSON.valueToTree(results));
            System.out.println("BUDGET_CONTENTION_PROOF "+proof);
        }
    }
    @Test void budgetEffectSeparatesHistoricalImplementationFromCurrentPeriodCaps() throws Exception {
        try(var w=open(dir.resolve("budget.journal"))){
            OperationsFixture.grantBudgetAndAppoint(w,COMPANY,ComposedManufacturingThreeScenario.TECH_DIRECTORS,ComposedManufacturingThreeScenario.TECH_MANAGER,"b",List.of(0L));
            implement(w,"b-budget-meeting",CHAIR);
            assertEquals("NOT_ADOPTED",result(w,CHAIR).path("reason").asText());
            var effect=context(eng(w)).path("currentEffect");assertTrue(effect.path("resolutionImplemented").asBoolean());assertEquals(2,effect.path("authorizedBudgets").size());
            for(var cap:effect.path("authorizedBudgets")){assertEquals(0,cap.path("period").asLong(-1));assertEquals("1000000.00",cap.path("cap").asText());}
            var later=context(reproject(w,eng(w),CHAIR,ManufacturingTemplateModel.PERIOD_MILLIS,w.snapshot().decisions().orElseThrow(),w.snapshot().roles().orElseThrow())).path("currentEffect");
            assertTrue(later.path("resolutionImplemented").asBoolean());assertEquals(1,later.path("currentBudgetPeriod").asLong());assertTrue(later.path("authorizedBudgets").isEmpty());
        }
    }
    @Test void noAssociationFromMissingBindingForeignPersonTamperedRowsOrModelClaims() throws Exception {
        try(var w=open(dir.resolve("binding.journal"))){
            RolePlanningTest.approvedAppointment(w,10000);implement(w,"appt",CHAIR);
            var run=w.snapshot().decisions().orElseThrow();var roles=w.snapshot().roles().orElseThrow();long now=w.snapshot().timeMillis();
            assertFalse(context(eng(w)).isMissingNode());
            for(var missing:List.of(new DecisionRun(run.state(),run.bindings(),Map.of(),run.archives(),run.dispatches()),new DecisionRun(run.state(),Map.of(),run.actionRefs(),run.archives(),run.dispatches())))
                assertTrue(context(reproject(w,eng(w),CHAIR,now,missing,roles)).isMissingNode());
            assertTrue(context(reproject(w,eng(w),OTHER,now,run,roles)).isMissingNode());
            for(String field:List.of("attemptId","boundTaskId","commandId","resultTaskId","reason")){
                var e=eng(w);((ObjectNode)e.path("deliberation").path("lastActionResult")).put(field,"untrusted");
                assertTrue(context(reproject(w,e,CHAIR,now,run,roles)).isMissingNode(),field);
            }
            var e=eng(w);((ObjectNode)e.path("directorships").get(0)).put("companyPartyId","mfg-struct");
            assertTrue(context(reproject(w,e,CHAIR,now,run,roles)).isMissingNode(),"same meeting id in another company is not authority");
            for(String damage:List.of("foreign-args","missing-meeting")){
                var state=(ObjectNode)JSON.valueToTree(run.state());var d=(ObjectNode)state.path("decisions").get(0);
                {
                    var attempt=(ObjectNode)d.path("attempts").get(0);var reply=(ObjectNode)JSON.readTree(attempt.path("replyBody").asText());
                    if(damage.equals("foreign-args"))((ObjectNode)reply.path("args")).put("companyPartyId","mfg-struct");
                    else ((ObjectNode)reply.path("args")).put("meetingId","missing");
                    attempt.put("replyBody",reply.toString());
                }
                var changed=run.withState(JSON.treeToValue(state,DecisionModel.State.class));
                assertTrue(context(reproject(w,eng(w),CHAIR,now,changed,roles)).isMissingNode(),damage);
            }
            var plain=eng(w);plain.put("roleContextVersion","deliberation-v4");
            ((ObjectNode)plain.path("deliberation").path("lastActionResult")).remove("targetMeetingContext");
            assertTrue(context(reproject(w,plain,CHAIR,now,run,roles)).isMissingNode());
        }
    }
    @Test void technicalFailureWithSuccessClaimDoesNotManufactureTargetFacts() throws Exception {
        try(var w=open(dir.resolve("technical.journal"))){
            var call=PharmaDecisionToolTest.open(w,CHAIR,"failed-claim");
            w.offerDecisionCallback(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),call.attemptNo(),call.recoveryGeneration(),"{\"tool\":\"board.implement\",\"basis\":\"mfg-tech appt IMPLEMENTED\"}",DecisionModel.Usage.unknownCost(1,50,30),"TEST"));w.drainDecisionCallbacks();
            var decision=w.snapshot().decisions().orElseThrow().state().decisions().get(0);
            assertEquals(DecisionModel.Outcome.TECHNICAL_FAILED,decision.outcome());
            assertNull(w.snapshot().decisions().orElseThrow().actionRef(call.actionId()));
            assertFalse(w.autonomyRoleObservation(CHAIR).toString().contains("targetMeetingContext"));
        }
    }
    @Test void factoryIsExplicitV5AndOldFactoryRemainsV4() throws Exception {
        try(var w=open(dir.resolve("new.journal"));var old=ComposedIndustryScenario.openInformedOperating(dir.resolve("old.journal"))) {
            assertEquals("deliberation-v5",w.profile().manifest().dataVersions().get("role-context"));
            assertEquals("web-informed-operating-v2",w.profile().manifest().runId());
            assertTrue(RoleDeliberation.isPlanning("deliberation-v4"));assertTrue(RoleDeliberation.isPlanning("deliberation-v5"));
            assertFalse(RoleDeliberation.isPlanning("deliberation-v50"));assertFalse(RoleDeliberation.isPlanning(null));
            var manifest=w.profile().manifest();
            for(String key:List.of("role-context","operating-information","state-storage","role-input-policy","dispatch-policy")){
                var data=new TreeMap<>(manifest.dataVersions());data.put(key,"invalid");
                assertThrows(IllegalArgumentException.class,()->new RunManifest(manifest.runId(),manifest.seed(),manifest.rulesVersion(),manifest.moduleVersions(),data,manifest.modelConfigVersion()),key);
            }
            assertEquals("deliberation-v4",old.profile().manifest().dataVersions().get("role-context"));
            assertEquals("web-informed-operating-v1",old.profile().manifest().runId());
        }
    }
    @Test void existingBudgetProposalBlocksOnlyV5() throws Exception {
        try(var w=ComposedIndustryScenario.openInformedOperating(dir.resolve("planning.journal"))) {
            var input=(ObjectNode)w.autonomyRoleObservation(CHAIR);
            var eng=(ObjectNode)input.path("engineering");
            var d=(ObjectNode)eng.path("directorships").get(0);
            var m=d.putArray("meetings").addObject().put("topic","BUDGET").put("status","CONVENED").put("attended",true).put("recused",false).put("materialDeadline",100000).put("opensAt",110000).put("closesAt",120000);
            m.putArray("proposedLines").addObject().put("period",0).put("category","PROCUREMENT").put("amount","30.00");
            RolePlanningContext.append(input,"wake",w.snapshot().decisions().orElseThrow().state().config());
            assertFalse(m.path("planningPrerequisites").path("board.proposeBudget").path("blockers").toString().contains("PROPOSAL_EXISTS"));
            eng.put("roleContextVersion","deliberation-v5");
            RolePlanningContext.append(input,"wake",w.snapshot().decisions().orElseThrow().state().config());
            assertTrue(m.path("planningPrerequisites").path("board.proposeBudget").path("blockers").toString().contains("PROPOSAL_EXISTS"),"missing existing budget blocker");
            m.putArray("proposedLines");RolePlanningContext.append(input,"wake",w.snapshot().decisions().orElseThrow().state().config());
            assertFalse(m.path("planningPrerequisites").path("board.proposeBudget").path("blockers").toString().contains("PROPOSAL_EXISTS"));
        }
    }
}
