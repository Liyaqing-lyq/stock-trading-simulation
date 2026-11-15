package edu.cufe.auction.web;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class WorldPlanningAutonomyTest {
 @TempDir Path dir;
 @Test void oneBadPersonCannotFreezeOtherReviewsAndOwnFailureDoesNotWakeOnRestart()throws Exception{
  var json=new ObjectMapper();var calls=new ConcurrentHashMap<String,CopyOnWriteArrayList<Long>>();
  for(int reopen=0;reopen<3;reopen++){
   final int round=reopen;
   try(var routes=SceneRoutes.open(dir)){
    if(reopen==0)routes.replace(routes.view().revision(),routes.view().routes().stream().map(r->new SceneRoutes.Route(r.scene(),r.slotId(),r.model(),true,500,5000000,512,4,"lifecycle-v1")).toList());
    try(var host=new WorldWebHost(()->{
     var w=ComposedIndustryScenario.openPlanningAutonomous(dir.resolve("failure.journal"));
     if(round==2){w.scheduleEngineeringConvene("new-fact","new-fact-task",17000,"mfg-tech","mfgtech-d1","external-meeting",200000,210000,240000);w.advanceTo(17000);}
     return new WorldWebHost.Opened(w,w.profile().manifest().runId());
    },routes,(call,timeout)->{
     var input=assertDoesNotThrow(()->json.readTree(call.inputText()));String p=input.path("person").asText();long now=input.path("time").asLong();calls.computeIfAbsent(p,k->new CopyOnWriteArrayList<>()).add(now);
     String body="{\"tool\":\"role.wait\"}";
     if(!p.equals("mfgtech-d2")){
      var n=json.createObjectNode().put("tool","role.wait");n.putObject("decision").put("objective",input.path("engineering").path("deliberation").path("objectives").get(0).path("id").asText()).put("basis","调度测试，等待未来复查。").put("reviewAfterMillis",10000);body=n.toString();
     }
     return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),call.attemptNo(),call.recoveryGeneration(),body,DecisionModel.Usage.unknownCost(1,10,30),"TEST"));
    })){
     int before=calls.getOrDefault("mfgtech-d2",new CopyOnWriteArrayList<>()).size();var status=host.autonomyStatus();host.startAutonomy(Long.parseLong(status.position()),routes.view().revision(),reopen==0?15000:reopen==1?16000:28000,3600000);
     long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(host.autonomyStatus().running()&&System.nanoTime()<end)Thread.sleep(25);
     assertEquals("TARGET_REACHED",host.autonomyStatus().reason());
     if(reopen==0){for(var person:calls.entrySet())if(!person.getKey().equals("mfgtech-d2")){assertEquals(2,person.getValue().size(),person.toString());assertEquals(10000L,person.getValue().get(1)-person.getValue().get(0),person.toString());}assertTrue(host.autonomyStatus().failures()>0);assertTrue(calls.get("mfgtech-d1").size()>=2,"other person must reach future review");assertTrue(calls.get("mfgtech-d2").size()<=2,"strict parser retries are bounded");}
     if(reopen==1)assertEquals(before,calls.get("mfgtech-d2").size(),"same facts after restart must not retry");
     if(reopen==2){assertTrue(calls.get("mfgtech-d2").size()>before,"new actual meeting may wake failed person");assertTrue(calls.get("mfgtech-d2").size()<=before+2);}
    }
   }
  }
 }
}
