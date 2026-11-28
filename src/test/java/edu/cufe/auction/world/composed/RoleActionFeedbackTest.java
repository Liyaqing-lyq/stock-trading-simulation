package edu.cufe.auction.world.composed;
import com.fasterxml.jackson.databind.*;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class RoleActionFeedbackTest {
 @TempDir Path dir;
 static final ObjectMapper JSON=new ObjectMapper();
 static DecisionRun.Call act(WorldRuntime w,String person,String id,String tool,Map<String,?> args)throws Exception{
  var c=PharmaDecisionToolTest.open(w,person,id);
  var n=JSON.createObjectNode().put("tool",tool);n.set("args",JSON.valueToTree(args));
  n.putObject("decision").put("objective",JSON.readTree(c.inputText()).path("engineering").path("deliberation").path("objectives").get(0).path("id").asText()).put("basis","协议测试，独立判断。").put("nextReviewAt",w.snapshot().timeMillis()+60000);
  w.offerDecisionCallback(new DecisionModel.Reply(c.runId(),c.actionId(),c.attemptId(),c.attemptNo(),c.recoveryGeneration(),n.toString(),DecisionModel.Usage.unknownCost(1,50,30),"TEST")); w.drainDecisionCallbacks();return c;
 }
 static Map<String,?> meeting(String id,long opens){return Map.of("companyPartyId","mfg-tech","meetingId",id,"topic","MANAGER_APPOINTMENT","materialDeadline",opens-10000,"opensAt",opens,"closesAt",opens+60000);}
 static JsonNode result(WorldRuntime w,String p)throws Exception{return w.autonomyRoleObservation(p).path("engineering").path("deliberation").path("lastActionResult");}
 @Test void v3EmptyMeetingStillExposesExactProcedureAndStrictWaitContract()throws Exception{
  try(var w=ComposedIndustryScenario.openAccountableAutonomous(dir.resolve("procedure.journal"))){
   var in=w.autonomyRoleObservation("mfgtech-d2");var eng=in.path("engineering");var d=eng.path("directorships").get(0);
   assertEquals("deliberation-v3",eng.path("roleContextVersion").asText());assertEquals("NO_CONVENE_RIGHT",d.path("conveneRight").asText());assertTrue(d.path("meetings").isEmpty());
   assertTrue(d.path("procedure").asText().contains("须先到会且未回避"));assertTrue(d.path("procedure").asText().contains("普通董事也可操作"));assertTrue(eng.path("decisionGuide").path("waitContract").asText().contains("nextReviewAt"));assertFalse(eng.path("decisionGuide").path("decisionCadence").asText().contains("每天至多一次"));
  }
 }
 @Test void readingUsesItsOwnCommandThroughStartAndCompletion()throws Exception{
  var path=dir.resolve("reading.journal");String frozen;
  try(var w=ComposedIndustryScenario.openAccountableAutonomous(path)){
   var c=act(w,"mfgtech-d1","read","role.reading.start",Map.of("docId","role-evidence:mfg-tech:1","version",1));var ref=w.snapshot().decisions().orElseThrow().actionRef(c.actionId());
   w.advanceTo(ref.dueMillis());assertEquals("IN_PROGRESS",result(w,"mfgtech-d1").path("execution").asText());
   var child=w.snapshot().pending().stream().filter(t->t.kind().equals(PendingTask.INFORMATION_COMPLETE_READ_V1)).findFirst().orElseThrow();w.advanceTo(child.dueTimeMillis());
   var r=result(w,"mfgtech-d1");assertEquals("APPLIED",r.path("execution").asText());assertEquals("COMPLETED",r.path("reason").asText());assertEquals(child.taskId(),r.path("resultTaskId").asText());assertEquals(DecisionRun.actionCommandId(c.actionId()),r.path("commandId").asText());frozen=w.autonomyRoleObservation("mfgtech-d1").toString();
  }
  try(var w=ComposedIndustryScenario.openAccountableAutonomous(path)){assertEquals(frozen,w.autonomyRoleObservation("mfgtech-d1").toString());}
 }
 @Test void absenceOfBoundReportsCannotProveCompletion()throws Exception{
  try(var w=ComposedIndustryScenario.openAccountableAutonomous(dir.resolve("unknown.journal"))){
   act(w,"mfgtech-d1","unknown","board.convene",meeting("unknown",120000));
   var eng=(com.fasterxml.jackson.databind.node.ObjectNode)w.autonomyRoleObservation("mfgtech-d1").path("engineering").deepCopy();
   RoleActionFeedback.append(eng,"mfgtech-d1",w.snapshot().decisions().orElseThrow(),java.util.List.of(),null,null,null,null);
   assertEquals("UNKNOWN",eng.path("deliberation").path("lastActionResult").path("execution").asText());assertFalse(eng.path("deliberation").path("lastActionResult").has("accepted"));
  }
 }
 @Test void frozenV2InputHashAndReopen()throws Exception{
  Path path=dir.resolve("v2.journal");String original;
  try(var w=ComposedIndustryScenario.openDeliberativeAutonomous(path)){
   original=w.autonomyRoleObservation("mfgtech-d1").toString();assertEquals("8f39802a08ed9fa9124e538ceaf95401d3b662d575b5115a705d24a15d2b1cc2",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
  }
  try(var w=ComposedIndustryScenario.openDeliberativeAutonomous(path)){assertEquals(original,w.autonomyRoleObservation("mfgtech-d1").toString());}
 }
 @Test void operationsUsesOnlyItsBoundManagementReports()throws Exception{
  try(var w=ComposedIndustryScenario.openAccountableAutonomous(dir.resolve("operations.journal"))){
   OperationsFixture.grantBudgetAndAppoint(w,"mfg-tech",ComposedManufacturingThreeScenario.TECH_DIRECTORS,ComposedManufacturingThreeScenario.TECH_MANAGER,"feedback-grant",java.util.List.of(0L));
   String person=ComposedManufacturingThreeScenario.TECH_MANAGER;String material=w.engineeringRoleInput(person).path("companyOperations").get(0).path("visibleQuotes").get(0).path("materialId").asText();
   for(String quantity:java.util.List.of("1","999999999")){
    var c=act(w,person,"order-"+quantity,"operations.act",Map.of("companyId","mfg-tech","action","ORDER_MATERIAL","fields",Map.of("materialId",material,"quantity",quantity)));
    var ref=w.snapshot().decisions().orElseThrow().actionRef(c.actionId());assertNotNull(ref);w.advanceTo(ref.dueMillis()+3000);
    var report=w.operationsRun().orElseThrow().reports().values().stream().filter(r->r.commandId().equals(DecisionRun.actionCommandId(c.actionId()))).max(java.util.Comparator.comparingLong(CompanyOperationsRun.ReportEntry::position)).orElseThrow();
    var result=result(w,person);assertEquals(quantity.equals("1")?"APPLIED":"REJECTED",result.path("execution").asText());assertEquals(report.accepted(),result.path("accepted").asBoolean());assertEquals(report.reason(),result.path("reason").asText());assertEquals(report.taskId(),result.path("resultTaskId").asText());assertEquals(DecisionRun.actionCommandId(c.actionId()),result.path("commandId").asText());
   }
  }
 }
 @Test void beginIsNotCompletionAndTerminalReportReplays()throws Exception{
  var file=dir.resolve("phases.journal");String frozen;
  try(var w=ComposedIndustryScenario.openAccountableAutonomous(file)){
   var c=act(w,"mfgtech-d1","first","board.convene",meeting("first",120000));
   var ref=w.snapshot().decisions().orElseThrow().actionRef(c.actionId());
   assertEquals("PENDING",result(w,"mfgtech-d1").path("execution").asText());
   w.advanceTo(ref.dueMillis());
   assertEquals("IN_PROGRESS",result(w,"mfgtech-d1").path("execution").asText());
   var finish=w.snapshot().pending().stream().filter(t->t.taskId().equals(ref.taskId()+":finish")).findFirst().orElseThrow();
   w.advanceTo(finish.dueTimeMillis());
   assertEquals("APPLIED",result(w,"mfgtech-d1").path("execution").asText());
   assertTrue(result(w,"mfgtech-d1").path("accepted").asBoolean());
   assertEquals(finish.taskId(),result(w,"mfgtech-d1").path("resultTaskId").asText());
   assertEquals(DecisionRun.actionCommandId(c.actionId()),result(w,"mfgtech-d1").path("commandId").asText());
   frozen=w.autonomyRoleObservation("mfgtech-d1").toString();
  }
  try(var w=ComposedIndustryScenario.openAccountableAutonomous(file)){assertEquals(frozen,w.autonomyRoleObservation("mfgtech-d1").toString());}
 }
 @Test void beginRejectIsOwnExactAttemptAndNoMeetingAppears()throws Exception{
  try(var w=ComposedIndustryScenario.openAccountableAutonomous(dir.resolve("reject.journal"))){
   for(int i=0;i<2;i++){
    var c=act(w,"mfgtech-d2","bad"+i,"board.convene",meeting("bad"+i,120000));
    var ref=w.snapshot().decisions().orElseThrow().actionRef(c.actionId());w.advanceTo(ref.dueMillis()+3000);
    var r=result(w,"mfgtech-d2");assertEquals("REJECTED",r.path("execution").asText());assertEquals("NOT_AUTHORIZED",r.path("businessReject").asText());assertFalse(r.path("accepted").asBoolean());assertEquals(DecisionRun.actionCommandId(c.actionId()),r.path("commandId").asText());
    assertTrue(w.boardOfCompany("mfg-tech").orElseThrow().board().meetings().isEmpty());
   }
   assertTrue(result(w,"mfgtech-d1").isMissingNode());
   String ownCommand=result(w,"mfgtech-d2").path("commandId").asText();act(w,"mfgtech-d1","foreign-success","board.convene",meeting("valid",120000));w.advanceTo(w.snapshot().timeMillis()+4000);assertEquals("REJECTED",result(w,"mfgtech-d2").path("execution").asText());assertEquals(ownCommand,result(w,"mfgtech-d2").path("commandId").asText());assertEquals("NOT_AUTHORIZED",result(w,"mfgtech-d2").path("businessReject").asText());
  }
 }
 @Test void attendanceCompletionRejectIsNotStartedSuccess()throws Exception{
  try(var w=ComposedIndustryScenario.openAccountableAutonomous(dir.resolve("late.journal"))){
   act(w,"mfgtech-d1","meeting","board.convene",meeting("late",20000));w.advanceTo(19000);
   var c=act(w,"mfgtech-d2","late","board.attend",Map.of("companyPartyId","mfg-tech","meetingId","late"));
   var ref=w.snapshot().decisions().orElseThrow().actionRef(c.actionId());w.advanceTo(ref.dueMillis());assertEquals("IN_PROGRESS",result(w,"mfgtech-d2").path("execution").asText());
   w.advanceTo(ref.dueMillis()+3000);var r=result(w,"mfgtech-d2");assertEquals("REJECTED",r.path("execution").asText());assertFalse(r.path("accepted").asBoolean());assertEquals(BoardModel.REASON_INVALID_STATE,r.path("businessReject").asText());
  }
 }
}
