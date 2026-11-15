package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol/runtime evidence only; injected replies do not establish model business behavior. */
class RolePlanningTest {
 @TempDir Path dir;
 static final ObjectMapper JSON = new ObjectMapper();
 static final String PERSON="mfgtech-d1", COMPANY="mfg-tech";

 @Test void frozenV3InputBaselineCaptureAndReopen() throws Exception {
  Path path=dir.resolve("v3.journal");String original;
  try(var w=ComposedIndustryScenario.openAccountableAutonomous(path)){
   original=w.autonomyRoleObservation(PERSON).toString();
   assertEquals("df2baf6891da76364a04c8014ae4851005e5c0bbc47310532f4d520aa8446c44",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
  }
  try(var w=ComposedIndustryScenario.openAccountableAutonomous(path)){assertEquals(original,w.autonomyRoleObservation(PERSON).toString());}
 }
 static ObjectNode protocolInput(long now,String version) {
  var input=JSON.createObjectNode().put("time",now);
  input.putObject("engineering").put("roleContextVersion",version).putObject("deliberation").putArray("objectives").addObject().put("id","person:one");
  return input;
 }
 static ObjectNode protocolReply(JsonNode duration) {
  var reply=JSON.createObjectNode().put("tool","role.wait");
  reply.putObject("decision").put("objective","person:one").put("basis","need evidence").set("reviewAfterMillis",duration);
  return reply;
 }
 @Test void v4RelativeProtocolIsStrictAtBothBoundsAndRejectsOverflowAndExtras() throws Exception {
  String input=protocolInput(10000,"deliberation-v4").toString();
  for(long valid:List.of(1L,2592000000L))assertEquals("{\"tool\":\"role.wait\"}",RoleDeliberation.validatedAction(input,protocolReply(JSON.valueToTree(valid))).toString());
  for(String invalid:List.of("0","-1","1.5","2592000001","9223372036854775808","\"1\"","null"))
   assertThrows(IllegalArgumentException.class,()->RoleDeliberation.validatedAction(input,protocolReply(JSON.readTree(invalid))),invalid);
  var both=protocolReply(JSON.valueToTree(10));((ObjectNode)both.path("decision")).put("nextReviewAt",10010);
  assertThrows(IllegalArgumentException.class,()->RoleDeliberation.validatedAction(input,both));
  ((ObjectNode)both.path("decision")).remove("reviewAfterMillis");
  assertThrows(IllegalArgumentException.class,()->RoleDeliberation.validatedAction(input,both));
  var extra=protocolReply(JSON.valueToTree(10));((ObjectNode)extra.path("decision")).put("extra",true);
  assertThrows(IllegalArgumentException.class,()->RoleDeliberation.validatedAction(input,extra));
  assertThrows(IllegalArgumentException.class,()->RoleDeliberation.validatedAction(protocolInput(Long.MAX_VALUE,"deliberation-v4").toString(),protocolReply(JSON.valueToTree(1))));
  for(String old:List.of("deliberation-v2","deliberation-v3")) {
   assertThrows(IllegalArgumentException.class,()->RoleDeliberation.validatedAction(protocolInput(10000,old).toString(),protocolReply(JSON.valueToTree(1))));
   assertFalse(RoleDeliberation.validatedAction(protocolInput(10000,old).toString(),both).has("decision"));
  }
 }
 @Test void numericPlanningUsesActualWakeAndToolConfig() throws Exception {
  try(var w=ComposedIndustryScenario.openPlanningAutonomous(dir.resolve("timing.journal"))){
   var in=w.autonomyRoleObservation("mfgtech-d2");var cfg=w.snapshot().decisions().orElseThrow().state().config();var plan=in.path("engineering").path("planning");
   assertEquals(cfg.thinkMillisByWake().get("wake").longValue(),plan.path("thinkMillis").asLong(-1));assertEquals(86400000,plan.path("millisPerDay").asLong());
   for(var tool:in.path("tools")) {
    String name=tool.path("name").asText();long cost=name.equals("role.wait")?0:cfg.actionMillisByTool().get(name);
    assertEquals(cost,plan.path("actions").path(name).path("actionMillis").asLong(-1));
   }
   assertFalse(in.toString().contains("nextReviewAt"));assertFalse(in.toString().contains("PASSED"));
   assertEquals(1,in.path("engineering").path("directorships").size());assertTrue(in.path("engineering").path("managerships").isEmpty());
   assertFalse(in.toString().contains("role-evidence:pharma"));
  }
 }
 static DecisionRun.Call act(WorldRuntime w,String person,String id,String tool,Map<String,?> args) throws Exception {
  var call=PharmaDecisionToolTest.open(w,person,id);var input=JSON.readTree(call.inputText());
  var reply=JSON.createObjectNode().put("tool",tool);if(args!=null)reply.set("args",JSON.valueToTree(args));
  reply.putObject("decision").put("objective",input.path("engineering").path("deliberation").path("objectives").get(0).path("id").asText()).put("basis","injected protocol/runtime test")
   .put(input.path("engineering").path("roleContextVersion").asText().equals("deliberation-v4")?"reviewAfterMillis":"nextReviewAt",input.path("engineering").path("roleContextVersion").asText().equals("deliberation-v4")?60000:input.path("time").asLong()+60000);
  assertEquals("QUEUED",w.offerDecisionCallback(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),call.attemptNo(),call.recoveryGeneration(),reply.toString(),DecisionModel.Usage.unknownCost(1,50,30),"TEST")));w.drainDecisionCallbacks();return call;
 }
 static JsonNode meeting(WorldRuntime w,String person) throws Exception {return w.autonomyRoleObservation(person).path("engineering").path("directorships").get(0).path("meetings").get(0);}
 static JsonNode prerequisite(WorldRuntime w,String person,String tool) throws Exception {return meeting(w,person).path("planningPrerequisites").path(tool);}
 static JsonNode result(WorldRuntime w,String person) throws Exception {return w.autonomyRoleObservation(person).path("engineering").path("deliberation").path("lastActionResult");}
 static long approvedAppointment(WorldRuntime w,long effectiveDelay) throws Exception {
  var voters=ComposedManufacturingThreeScenario.TECH_DIRECTORS.subList(0,4);
  OperationsFixture.publishAndRead(w,"planning-appt-doc",voters,"read");
  long b=w.snapshot().timeMillis()+1000,opens=b+1000,closes=b+10000;
  w.scheduleCompanyBoardConvene("convene","convene-task",b,COMPANY,PERSON,"appt",b+500,opens,closes,true);
  for(int i=0;i<4;i++)w.scheduleCompanyBoardAttend("att-"+i,"att-task-"+i,b+10+i,COMPANY,voters.get(i),"appt");
  w.scheduleCompanyBoardMaterial("mat","mat-task",b+30,COMPANY,PERSON,"appt","m1","planning-appt-doc",1);
  w.scheduleCompanyBoardAppointmentProposal("prop","prop-task",b+40,COMPANY,PERSON,"appt",new BoardModel.AppointmentRequest("test-manager",ComposedManufacturingThreeScenario.TECH_MANAGER,COMPANY,RoleAccess.Role.COMPANY_MANAGER,BoardModel.AppointmentAction.APPOINT,closes+effectiveDelay,closes+90000000));
  for(int i=0;i<4;i++)w.scheduleCompanyBoardVote("vote-"+i,"vote-task-"+i,opens+1000+i,COMPANY,voters.get(i),"appt",BoardModel.Vote.YES,1000);
  w.advanceTo(closes);assertEquals(BoardModel.MeetingStatus.ADOPTED,w.boardOfCompany(COMPANY).orElseThrow().board().meeting("appt").status());return closes;
 }
 @Test void approvedButTooTightV3AppointmentRemainsRejectedAndV4ShowsNumericBlocker() throws Exception {
  for(boolean v4:new boolean[]{false,true})try(var w=v4?ComposedIndustryScenario.openPlanningAutonomous(dir.resolve("tight4.journal")):ComposedIndustryScenario.openAccountableAutonomous(dir.resolve("tight3.journal"))){
   long closes=approvedAppointment(w,1);String ordinary="mfgtech-d5";
   var before=w.autonomyRoleObservation(ordinary);assertFalse(before.path("engineering").path("directorships").get(0).path("chair").asBoolean());
   if(v4){var p=prerequisite(w,ordinary,"board.implement");assertEquals("BLOCKED",p.path("status").asText());assertTrue(p.path("blockers").toString().contains("APPOINTMENT_IMPLEMENTATION_DEADLINE"));assertTrue(p.path("timing").path("earliestCompletionMillis").asLong()>closes+1);}
   else assertTrue(before.path("engineering").path("planning").isMissingNode());
   var call=act(w,ordinary,"implement","board.implement",Map.of("companyPartyId",COMPANY,"meetingId","appt"));
   var ref=w.snapshot().decisions().orElseThrow().actionRef(call.actionId());w.advanceTo(ref.dueMillis());
   var finish=w.snapshot().pending().stream().filter(t->t.taskId().equals(ref.taskId()+":finish")).findFirst().orElseThrow();
   if(v4)assertEquals(JSON.readTree(call.inputText()).path("engineering").path("planning").path("actions").path("board.implement").path("earliestCompletionMillis").asLong(),finish.dueTimeMillis());
   w.advanceTo(finish.dueTimeMillis());assertEquals("INVALID_TIMELINE",result(w,ordinary).path("reason").asText());assertEquals("REJECTED",result(w,ordinary).path("execution").asText());
   assertEquals(BoardModel.MeetingStatus.ADOPTED,w.boardOfCompany(COMPANY).orElseThrow().board().meeting("appt").status());
   assertTrue(result(w,PERSON).isMissingNode(),"foreign decision reports must not appear");
  }
 }
 @Test void exactAppointmentCompletionBoundaryIsInclusiveAndOrdinaryUnattendedDirectorCanImplement() throws Exception {
  Path path=dir.resolve("inclusive.journal");String frozen;
  try(var w=ComposedIndustryScenario.openPlanningAutonomous(path)){
   long closes=approvedAppointment(w,2000);String p="mfgtech-d5";assertFalse(meeting(w,p).path("attended").asBoolean());
   assertEquals("NECESSARY_CONDITIONS_MET",prerequisite(w,p,"board.implement").path("status").asText());
   var call=act(w,p,"inclusive","board.implement",Map.of("companyPartyId",COMPANY,"meetingId","appt"));
   long expected=JSON.readTree(call.inputText()).path("engineering").path("planning").path("actions").path("board.implement").path("earliestCompletionMillis").asLong();assertEquals(closes+2000,expected);
   w.advanceTo(expected);assertEquals("APPLIED",result(w,p).path("execution").asText());assertEquals(expected,result(w,p).path("resultAt").asLong());
   assertEquals(BoardModel.MeetingStatus.IMPLEMENTED,w.boardOfCompany(COMPANY).orElseThrow().board().meeting("appt").status());frozen=w.autonomyRoleObservation(p).toString();
  }
  try(var w=ComposedIndustryScenario.openPlanningAutonomous(path)){assertEquals(frozen,w.autonomyRoleObservation("mfgtech-d5").toString());}
 }
 @Test void relativeWaitRawReplyAndDerivedReviewSurviveRecoveryAndFactoriesAreExplicit() throws Exception {
  Path path=dir.resolve("relative.journal");String frozen;long review;
  try(var w=ComposedIndustryScenario.openPlanningAutonomous(path)){
   w.advanceTo(12345);var call=act(w,PERSON,"wait","role.wait",null);
   var decision=w.snapshot().decisions().orElseThrow().state().decisions().get(0);assertTrue(decision.attempts().get(0).replyBody().contains("reviewAfterMillis"));
   review=72345;assertEquals(review,RoleDeliberation.reviewAt(w.snapshot().decisions().orElseThrow(),PERSON,-1));w.advanceTo(15000);
   assertEquals(review,w.autonomyNextReviewAt(PERSON,-1));
   var row=w.autonomyRoleObservation(PERSON).path("engineering").path("deliberation").path("recentDecisions").get(0);assertEquals(60000,row.path("reviewAfterMillis").asLong());assertEquals(review,row.path("derivedReviewAt").asLong());
   assertEquals("delta-v1",w.profile().manifest().dataVersions().get("state-storage"));assertEquals("deliberation-v4",w.profile().manifest().dataVersions().get("role-context"));frozen=w.autonomyRoleObservation(PERSON).toString();
  }
  try(var w=ComposedIndustryScenario.openPlanningAutonomous(path)){assertEquals(frozen,w.autonomyRoleObservation(PERSON).toString());assertEquals(review,w.autonomyNextReviewAt(PERSON,-1));}
  try(var w=ComposedIndustryScenario.openPlanningOperating(dir.resolve("operating.journal"))){assertEquals("v1",w.profile().manifest().dataVersions().get("external-demand"));assertEquals("delta-v1",w.profile().manifest().dataVersions().get("state-storage"));assertEquals("deliberation-v4",w.profile().manifest().dataVersions().get("role-context"));}
 }
 @Test void attendStrictBeforeAndMaterialInclusiveDeadlinesMatchRealCompletion() throws Exception {
  for(int delta:new int[]{-1,0})try(var w=ComposedIndustryScenario.openPlanningAutonomous(dir.resolve("att-bound-"+delta+".journal"))){
   w.scheduleCompanyBoardConvene("c","ct",1000,COMPANY,PERSON,"m",9000,10000,20000,true);w.advanceTo(8000+delta);
   var p=prerequisite(w,"mfgtech-d2","board.attend");assertEquals(delta<0?"NECESSARY_CONDITIONS_MET":"BLOCKED",p.path("status").asText());
   var call=act(w,"mfgtech-d2","att","board.attend",Map.of("companyPartyId",COMPANY,"meetingId","m"));w.advanceTo(10000+delta);
   assertEquals(delta<0?"APPLIED":"REJECTED",result(w,"mfgtech-d2").path("execution").asText());assertEquals(10000+delta,result(w,"mfgtech-d2").path("resultAt").asLong());
  }
  for(int delta:new int[]{0,1})try(var w=ComposedIndustryScenario.openPlanningAutonomous(dir.resolve("mat-bound-"+delta+".journal"))){
   w.scheduleCompanyBoardConvene("c","ct",1000,COMPANY,PERSON,"m",6000,10000,20000,true);
   w.scheduleCompanyBoardAttend("a","at",1010,COMPANY,PERSON,"m");w.advanceTo(4000+delta);
   var p=prerequisite(w,PERSON,"board.addMaterial");assertEquals(delta==0?"NECESSARY_CONDITIONS_MET":"BLOCKED",p.path("status").asText());
   var call=act(w,PERSON,"mat","board.addMaterial",Map.of("companyPartyId",COMPANY,"meetingId","m","materialId","material","sourceRef","role-evidence:mfg-tech:1","version",1));w.advanceTo(6000+delta);
   assertEquals(delta==0?"APPLIED":"REJECTED",result(w,PERSON).path("execution").asText());assertEquals(6000+delta,result(w,PERSON).path("resultAt").asLong());
  }
 }
 @Test void voteProjectsOwnReadRecallRecusalBallotAndExclusiveClose() throws Exception {
  try(var w=ComposedIndustryScenario.openPlanningAutonomous(dir.resolve("vote-legality.journal"))){
   OperationsFixture.publishAndRead(w,"vote-doc",List.of(PERSON,"mfgtech-d3","mfgtech-d5"),"vd");
   long b=w.snapshot().timeMillis()+1000,opens=b+1000,closes=b+20000;
   w.scheduleCompanyBoardConvene("c","ct",b,COMPANY,PERSON,"m",b+500,opens,closes,true);
   for(int i=0;i<4;i++)w.scheduleCompanyBoardAttend("a"+i,"at"+i,b+10+i,COMPANY,ComposedManufacturingThreeScenario.TECH_DIRECTORS.get(i),"m",i==2);
   w.scheduleCompanyBoardMaterial("m","mt",b+30,COMPANY,PERSON,"m","doc","vote-doc",1);
   w.scheduleCompanyBoardAppointmentProposal("p","pt",b+40,COMPANY,PERSON,"m",new BoardModel.AppointmentRequest("manager",ComposedManufacturingThreeScenario.TECH_MANAGER,COMPANY,RoleAccess.Role.COMPANY_MANAGER,BoardModel.AppointmentAction.APPOINT,closes+10000,closes+90000000));
   w.advanceTo(opens);
   assertTrue(prerequisite(w,"mfgtech-d2","board.vote").path("blockers").toString().contains("REQUIRES_read_true"));
   assertTrue(prerequisite(w,"mfgtech-d3","board.vote").path("blockers").toString().contains("REQUIRES_recused_false"));
   assertTrue(prerequisite(w,"mfgtech-d5","board.vote").path("blockers").toString().contains("REQUIRES_attended_true"));
   assertEquals("NECESSARY_CONDITIONS_MET",prerequisite(w,PERSON,"board.vote").path("status").asText());
   act(w,"mfgtech-d2","unread","board.vote",Map.of("companyPartyId",COMPANY,"meetingId","m","vote","YES"));w.advanceTo(opens+2000);assertEquals("MATERIAL_NOT_READ",result(w,"mfgtech-d2").path("reason").asText());
   act(w,PERSON,"own-vote","board.vote",Map.of("companyPartyId",COMPANY,"meetingId","m","vote","NO"));w.advanceTo(opens+4000);
   assertTrue(prerequisite(w,PERSON,"board.vote").path("blockers").toString().contains("BALLOT_ALREADY_CAST"));
   var input=(ObjectNode)w.autonomyRoleObservation(PERSON).deepCopy();var meeting=(ObjectNode)input.path("engineering").path("directorships").get(0).path("meetings").get(0);
   ((ObjectNode)meeting.path("materials").get(0)).put("recalled",false);
   RolePlanningContext.append(input,"wake",w.snapshot().decisions().orElseThrow().state().config());
   assertTrue(meeting.path("planningPrerequisites").path("board.vote").path("blockers").toString().contains("REQUIRES_recalled_true"));
   ((ObjectNode)meeting.path("materials").get(0)).remove("read");RolePlanningContext.append(input,"wake",w.snapshot().decisions().orElseThrow().state().config());assertTrue(meeting.path("planningPrerequisites").path("board.vote").path("unknown").toString().contains("UNKNOWN_read"));
   w.advanceTo(closes-2001);assertFalse(prerequisite(w,"mfgtech-d4","board.vote").path("blockers").toString().contains("VOTING_CLOSED"));
   w.advanceTo(closes-2000);assertTrue(prerequisite(w,"mfgtech-d4","board.vote").path("blockers").toString().contains("VOTING_CLOSED"));
  }
 }
}
