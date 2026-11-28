package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 超限人物隔离属于免费输入检查，不是模型失败或付费决定。 */
class WorldLocalInputAutonomyTest {
    @TempDir Path dir;
    static final String BLOCKED="mfgtech-d2", READER="mfgversa-d1";
    static final ObjectMapper JSON=new ObjectMapper();
    private static void enable(SceneRoutes routes,int maxCalls) throws Exception {
        routes.replace(routes.view().revision(),routes.view().routes().stream().map(r->new SceneRoutes.Route(r.scene(),r.slotId(),r.model(),true,maxCalls,5_000_000,512,4,"lifecycle-v1")).toList());
    }
    private static void seed(WorldRuntime w) throws Exception {
        w.schedulePublishDocument("private-large","private-large-task",3,"private-input","alice",InformationModel.Audience.direct(BLOCKED),"超".repeat(40000),"私有短正文","fixture",10,List.of());
        w.schedulePublishDocument("control-small","control-small-task",3,"control-read","alice",InformationModel.Audience.direct(READER),"正常材料","其他角色真实阅读","fixture",1000,List.of());
        w.schedulePublishDocumentVersion("private-shrink","private-shrink-task",17000,"private-input","短标题","新的短正文","fixture",10,List.of());
        w.advanceTo(4);
    }
    private static LiveCompletionInbox.Completion reply(DecisionRun.Call call, boolean read) throws Exception {
        var input=JSON.readTree(call.inputText());var body=JSON.createObjectNode().put("tool",read?"role.reading.start":"role.wait");
        if(read)body.putObject("args").put("docId","control-read").put("version",1);
        body.putObject("decision").put("objective",input.path("engineering").path("deliberation").path("objectives").get(0).path("id").asText()).put("basis","输入隔离机制测试").put("reviewAfterMillis",10000);
        return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),call.attemptNo(),call.recoveryGeneration(),body.toString(),DecisionModel.Usage.unknownCost(1,10,30),"TEST"));
    }
    private static void finish(WorldWebHost host) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(host.autonomyStatus().running()&&System.nanoTime()<deadline)Thread.sleep(25);
        assertFalse(host.autonomyStatus().running(),"autonomy must terminate at its finite target");
    }
    @Test void onePrivateOversizeRoleIsFreeAcrossRestartThenRecoversWhenCurrentInputShrinks() throws Exception {
        var calls=new ConcurrentHashMap<String,CopyOnWriteArrayList<Long>>();Path file=dir.resolve("isolated.journal");
        for(int round=0;round<3;round++) {
            int n=round;var actual=new java.util.concurrent.atomic.AtomicReference<WorldRuntime>();
            try(var routes=SceneRoutes.open(dir)) {
                if(n==0)enable(routes,500);
                try(var host=new WorldWebHost(()->{var w=ComposedIndustryScenario.openInformedOperating(file);if(n==0)seed(w);actual.set(w);return new WorldWebHost.Opened(w,w.profile().manifest().runId());},routes,(call,timeout)->{
                    try {var in=JSON.readTree(call.inputText());String person=in.path("person").asText();calls.computeIfAbsent(person,p->new CopyOnWriteArrayList<>()).add(in.path("time").asLong());
                        boolean read=person.equals(READER)&&in.path("profile").path("readExperiences").findValuesAsText("docId").stream().noneMatch("control-read"::equals);
                        return reply(call,read);
                    }catch(Exception e){throw new AssertionError(e);}
                })) {
                    assertFalse(host.autonomyStatus().running());int before=calls.values().stream().mapToInt(List::size).sum();
                    host.liveStatus();host.liveStatus();assertEquals(before,calls.values().stream().mapToInt(List::size).sum(),"queries after restart cannot call provider");
                    var status=host.autonomyStatus();long target=n==0?15000:n==1?16000:30000;
                    host.startAutonomy(Long.parseLong(status.position()),routes.view().revision(),target,3600000);finish(host);
                    assertEquals("TARGET_REACHED",host.autonomyStatus().reason(),host.liveStatus().skips().toString());
                    assertEquals(target,host.adminSnapshot().timeMillis());var live=host.liveStatus();
                    if(n<2) {
                        assertFalse(calls.containsKey(BLOCKED));
                        assertTrue(actual.get().snapshot().decisions().orElseThrow().state().decisions().stream().noneMatch(d->d.personPartyId().equals(BLOCKED)));
                        var skip=live.skips().stream().filter(s->s.personPartyId().equals(BLOCKED)).findFirst().orElseThrow();assertEquals("INPUT_TOO_LARGE",skip.reason());
                        assertTrue(Long.parseLong(skip.inputSize().actualChars())>40000);assertEquals("32768",skip.inputSize().maxChars());
                        assertFalse(JSON.writeValueAsString(skip).contains("超"));
                        assertEquals("0",live.personBudgets().stream().filter(p->p.personPartyId().equals(BLOCKED)).findFirst().orElseThrow().budget().reservedCalls());
                        assertTrue(InformationModel.observations(actual.get().snapshot().information().orElseThrow().information(),READER).stream().anyMatch(r->r.docId().equals("control-read")));
                    } else {assertTrue(calls.get(BLOCKED).get(0)>=17000);assertTrue(live.skips().stream().noneMatch(s->s.personPartyId().equals(BLOCKED)));}
                    assertEquals(0,live.physicalInFlight());assertEquals(0,live.completionSlots());
                }
            }
        }
    }

    @Test void oldPlanningModeKeepsGlobalOversizeStop() throws Exception {
        try(var routes=SceneRoutes.open(dir)) {
            enable(routes,500);
            try(var host=new WorldWebHost(()->{var w=ComposedIndustryScenario.openPlanningOperating(dir.resolve("old.journal"));seed(w);return new WorldWebHost.Opened(w,w.profile().manifest().runId());},routes,(c,t)->{try{return reply(c,false);}catch(Exception e){throw new AssertionError(e);}})) {
                var before=host.autonomyStatus();host.startAutonomy(Long.parseLong(before.position()),routes.view().revision(),15000,3600000);finish(host);
                assertEquals("ROLE_DISPATCH_REJECTED",host.autonomyStatus().reason());assertTrue(host.liveStatus().skips().stream().anyMatch(x->x.personPartyId().equals(BLOCKED)&&x.reason().equals("INPUT_TOO_LARGE")));
            }
        }
    }
}
