package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedRoleInputTest {
    @TempDir Path dir;
    private final ObjectMapper json = new ObjectMapper();

    @Test void privateRoleLimitLeavesOtherRolesAvailableAndPersistsTheExactLargeDecision() throws Exception {
        Path file=dir.resolve("world.journal");
        var inputs=new ConcurrentHashMap<String,String>();
        var app=new AtomicReference<WorldRuntime>();
        try (var routes=SceneRoutes.open(dir)) {
            routes.replace(0, AutonomousLifecycleProbe.probeRoutes(routes.view(),"TEN_COMPANY_AUTONOMY"));
            try (var host=new WorldWebHost(()-> {
                var w=ComposedIndustryScenario.openTenCompanyAutonomy(file);long start=w.snapshot().timeMillis();
                w.schedulePublishDocument("open-large","open-large-task",start+1,"D","dave",InformationModel.Audience.direct("alice"),
                        "长".repeat(92_000),"正文","fixture",10,List.of());
                w.schedulePublishDocumentVersion("shrink","shrink-task",start+200_000,"D","长".repeat(34_000),"短正文","fixture",10,List.of());
                w.advanceTo(start+2);app.set(w);return new WorldWebHost.Opened(w,w.profile().manifest().runId());
            },routes,(call,timeout)-> {
                try {
                    var input=json.readTree(call.inputText());String person=input.path("person").asText();
                    inputs.put(person,call.inputText());
                    assertEquals(DecisionModel.EXTENDED_ROLE_INPUT,call.plan().inputPolicy());
                    var body=json.createObjectNode().put("tool","role.wait");
                    body.putObject("decision").put("objective",input.at("/engineering/deliberation/objectives/0/id").asText())
                            .put("basis","输入边界分项检查").put("reviewAfterMillis",2000);
                    return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),call.attemptId(),
                            call.attemptNo(),call.recoveryGeneration(),body.toString(),DecisionModel.Usage.unknownCost(1,10,10),"TEST"));
                } catch(Exception error) {throw new AssertionError(error);}
            })) {
                host.adminSnapshot();
                var w=app.get();assertEquals(DecisionModel.EXTENDED_ROLE_INPUT,w.profile().manifest().dataVersions().get("role-input-limit"));
                host.startLiveRound(host.adminSnapshot().position(),routes.view().revision(),List.of("alice","bob"));
                awaitIdle(host);
                var skip=host.liveStatus().skips().stream().filter(s->s.personPartyId().equals("alice")).findFirst().orElseThrow();
                assertEquals("INPUT_TOO_LARGE",skip.reason());
                assertEquals("131072",skip.inputSize().maxChars());assertEquals("262144",skip.inputSize().maxBytes());
                assertTrue(Long.parseLong(skip.inputSize().actualBytes())>262144);
                assertEquals(Set.of("bob"),inputs.keySet());
                assertEquals("0",host.liveStatus().personBudgets().stream().filter(b->b.personPartyId().equals("alice")).findFirst().orElseThrow().budget().usedCalls());
                host.call(()->{w.advanceTo(w.snapshot().timeMillis()+200_001);return null;});
                host.startLiveRound(host.adminSnapshot().position(),routes.view().revision(),List.of("alice"),"research",null);awaitIdle(host);
                String large=inputs.get("alice");assertNotNull(large);assertTrue(large.length()>32_768);
                assertTrue(large.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60_000);
                assertTrue(large.contains("长".repeat(34_000)));
                assertEquals("2",host.liveStatus().runBudget().usedCalls());
            }
        }
        byte[] raw=Files.readAllBytes(file);
        try(var w=ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            var model=w.snapshot().decisions().orElseThrow().state();
            assertEquals(2,model.decisions().size());
            for(var decision:model.decisions()) {
                assertEquals(inputs.get(decision.personPartyId()),decision.inputText());
                assertEquals(DecisionModel.EXTENDED_ROLE_INPUT,decision.plan().inputPolicy());
                assertEquals(decision.plan().inputBytes()+1024,decision.lastAttempt().reserved().inputTokens());
            }
        }
        assertArrayEquals(raw,Files.readAllBytes(file));
    }

    @Test void independentCharacterCeilingRejectsAsciiWithinByteAllowance() throws Exception {
        try(var world=ComposedIndustryScenario.openTenCompanyAutonomy(dir.resolve("ascii.journal"))) {
            long due=world.snapshot().timeMillis()+1;
            world.schedulePublishDocument("ascii","ascii-task",due,"ASCII","alice",InformationModel.Audience.direct("bob"),
                    "H".repeat(132_000),"正文","fixture",10,List.of());
            world.advanceTo(due);
            var violation=world.autonomyInputLimitViolation("bob").orElseThrow();
            assertTrue(violation.actualChars()>DecisionModel.EXTENDED_INPUT_CHARS);
            assertTrue(violation.actualBytes()<DecisionModel.EXTENDED_INPUT_BYTES);
            assertEquals(0,world.snapshot().decisions().orElseThrow().state().runUsage().attempts());
        }
        var route=new DecisionModel.Route("trading","key-1","deepseek-flash",0,10,1000000,512,1);
        assertThrows(IllegalArgumentException.class,()->new DecisionModel.ProviderPlan(route,60_001L));
        assertThrows(IllegalArgumentException.class,()->new DecisionModel.ProviderPlan(route,262_145L,DecisionModel.EXTENDED_ROLE_INPUT));
        assertEquals(263_168L,new DecisionModel.ProviderPlan(route,262_144L,DecisionModel.EXTENDED_ROLE_INPUT).reservedInputTokens());
    }

    private static void awaitIdle(WorldWebHost host) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime()<until) {
            var st=host.liveStatus();
            if(st.physicalInFlight()==0 && st.completionSlots()==0 && !"RUNNING".equals(st.phase()) && !"STOPPING".equals(st.phase())) return;
            Thread.sleep(20);
        }
        fail("测试未按时完成");
    }
}
