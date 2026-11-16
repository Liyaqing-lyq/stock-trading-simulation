package edu.cufe.auction.world.composed;
import com.fasterxml.jackson.databind.*;
import edu.cufe.auction.web.*;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class ContextualHostBoundaryTest {
 @TempDir Path dir;
 @Test void contextualTimestampCannotWakePaidCallAndRelativeReviewStillDoes() throws Exception {
  var json=new ObjectMapper();var actual=new AtomicReference<WorldRuntime>();var times=new CopyOnWriteArrayList<Long>();var start=new AtomicLong();
  try(var routes=SceneRoutes.open(dir)){
   routes.replace(0,routes.view().routes().stream().map(r->new SceneRoutes.Route(r.scene(),r.slotId(),r.model(),true,500,5000000,512,4,"lifecycle-v1")).toList());
   try(var host=new WorldWebHost(()->{
    var w=ComposedIndustryScenario.openContextualInformedOperating(dir.resolve("host.journal"));
    RolePlanningTest.approvedAppointment(w,1000000);
    var r=routes.view().routes().stream().filter(x->x.scene().id().equals("governance")).findFirst().orElseThrow();
    var route=new DecisionModel.Route("governance",r.slotId(),r.model(),routes.view().revision(),r.maxCalls(),r.maxTotalTokens(),r.maxOutputTokens(),r.maxConcurrency(),r.budgetPolicy());
    RoleGovernanceOutcomeContextTest.implement(w,"appt",route,"mfgtech-d1","mfgtech-d2");
    start.set(w.snapshot().timeMillis());actual.set(w);
    w.schedulePublishDocument("noise","noise-task",start.get()+5000,"private-other","alice",InformationModel.Audience.direct("alice"),"标题","短","fixture",10,List.of());
    return new WorldWebHost.Opened(w,w.profile().manifest().runId());
   },routes,(call,timeout)->{
    var in=assertDoesNotThrow(()->json.readTree(call.inputText()));if(in.path("person").asText().equals("mfgtech-d1"))times.add(in.path("time").asLong());
    var system=assertDoesNotThrow(()->LiveDecisionProvider.class.getDeclaredMethod("systemText",DecisionRun.Call.class));system.setAccessible(true);
    assertEquals(LiveDecisionProvider.PLANNING_SYSTEM_TEXT,assertDoesNotThrow(()->system.invoke(null,call)));
    var reply=json.createObjectNode().put("tool","role.wait");reply.putObject("decision").put("objective",in.path("engineering").path("deliberation").path("objectives").get(0).path("id").asText()).put("basis","bounded fixture").put("reviewAfterMillis",60000);
    return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),call.attemptNo(),call.recoveryGeneration(),reply.toString(),DecisionModel.Usage.unknownCost(1,10,30),"TEST"));
   })){
    host.autonomyStatus();
    var wake=WorldWebHost.class.getDeclaredMethod("wakeFacts",JsonNode.class);wake.setAccessible(true);
    var before=host.call(()->actual.get().autonomyRoleObservation("mfgtech-d1"));
    var later=before.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)later.path("engineering").path("deliberation").path("lastActionResult").path("targetMeetingContext")).put("asOfMillis",start.get()+1000);
    assertEquals(wake.invoke(host,before),wake.invoke(host,later));
    for(long target:new long[]{start.get()+15000,start.get()+60001}){
     host.startAutonomy(Long.parseLong(host.autonomyStatus().position()),routes.view().revision(),target,3600000);
     long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(host.autonomyStatus().running()&&System.nanoTime()<limit)Thread.sleep(25);
     assertEquals("TARGET_REACHED",host.autonomyStatus().reason());
     assertEquals(target<start.get()+60000?1:2,times.size());
    }
    assertEquals(60000,times.get(1)-times.get(0));
   }
  }
 }
}
