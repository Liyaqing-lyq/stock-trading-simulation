package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FundOfficeRoutingTest {
    @TempDir Path dir;
    @Test void replacementResearcherRetainsPersonalTradingAndOriginalChairKeepsOnlyGovernance() throws Exception {
        Path file=dir.resolve("desk.journal");
        try(var w=ComposedIndustryScenario.openFundOfficeRoutingOperating(file)) {
            w.scheduleRevokeAppointment("leave","leave-task",2,"A-1");
            w.scheduleAppointment("hire","hire-task",3,"new-researcher","investor","cufe",RoleAccess.Role.RESEARCHER,3,1000000);
            w.advanceTo(3);
        }
        var seen=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var owned=new AtomicReference<WorldRuntime>();
        var json=new ObjectMapper();
        try(var routes=SceneRoutes.open(dir)) {
            routes.replace(0,AutonomousLifecycleProbe.probeRoutes(routes.view(),"PERSONAL_PROFILES_OPERATING"));
            try(var host=new WorldWebHost(()->{
                var w=ComposedIndustryScenario.openFundOfficeRoutingOperating(file);owned.set(w);
                return new WorldWebHost.Opened(w,w.profile().manifest().runId());
            },routes,(call,timeout)->{
                seen.add(call.plan().route().sceneId());
                var reply=json.createObjectNode().put("tool","role.wait");
                reply.putObject("decision").put("objective","appointment:new-researcher")
                        .put("basis","等待已核实的产品资料。").put("reviewAfterMillis",1000);
                return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),
                        call.attemptNo(),call.recoveryGeneration(),reply.toString(),DecisionModel.Usage.unknownCost(1,40,25),"fixture"));
            })) {
                var caps=host.liveCapabilities();
                var newcomer=caps.persons().stream().filter(p->p.personPartyId().equals("investor")).findFirst().orElseThrow();
                assertEquals("research",newcomer.sceneId());
                assertTrue(newcomer.scenes().containsAll(List.of("research","trading")));
                assertTrue(caps.persons().stream().noneMatch(p->p.personPartyId().equals("alice")&&p.scenes().contains("research")));
                assertTrue(caps.governancePersons().stream().anyMatch(p->p.personPartyId().equals("alice")&&p.governanceCompanies().contains("eng-a")));
                for(var scene:List.of("research","trading")){
                    host.startLiveRound(host.call(()->owned.get().snapshot().position()),routes.view().revision(),List.of("investor"),scene,null);
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                    while(!host.liveStatus().phase().equals("IDLE") && System.nanoTime()<deadline) Thread.sleep(10);
                    assertEquals("IDLE",host.liveStatus().phase());
                    assertTrue(host.liveStatus().skips().isEmpty());
                }
                assertEquals(List.of("research","trading"),seen);
            }
        }
    }
}
