package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.SceneRoutes;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.LiveCompletionInbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 无旧单制造董事会时，网页仍能列出并实际派发独立公司的治理场景。 */
class BaselineGovernanceLiveTest {
    @TempDir Path dir;
    @Test void independentBoardCanStartExplicitGovernanceRound() throws Exception {
        try(var routes=SceneRoutes.open(dir)) {
            routes.replace(routes.view().revision(),routes.view().routes().stream().map(r->
                    new SceneRoutes.Route(r.scene(),r.slotId(),r.model(),
                            r.scene()==SceneRoutes.Scene.GOVERNANCE,1,100000,1000,1)).toList());
            var arrived=new CountDownLatch(1);
            var dispatched=new AtomicReference<DecisionRun.Call>();
            try(var host=new WorldWebHost(()->{
                var world=ComposedIndustryScenario.openBaseline(dir.resolve("world.journal"));
                assertTrue(world.snapshot().governance().isEmpty(),"回归必须确实没有旧单制造董事会");
                return new WorldWebHost.Opened(world,world.profile().manifest().runId());
            },routes,(WorldWebHost.DecisionProvider)(call,timeout)->{
                dispatched.set(call);arrived.countDown();
                return LiveCompletionInbox.Completion.failure("TEST_PROVIDER",new DecisionModel.Reply(
                        call.runId(),call.actionId(),call.attemptId(),call.attemptNo(),
                        call.recoveryGeneration(),"",null,"LOCAL_TEST"));
            })) {
                var capabilities=host.liveCapabilities();
                assertTrue(capabilities.supportedScenes().contains("governance"));
                var person=capabilities.governancePersons().stream()
                        .filter(p->p.personPartyId().equals("digital-company-director-1")).findFirst().orElseThrow();
                assertEquals(List.of("digital-company"),person.governanceCompanies());
                assertFalse(capabilities.governancePersons().stream().anyMatch(p->p.personPartyId().equals("digital-manager")),
                        "未任命的经理候选不应因修复入口得到权限");
                host.startLiveRound(Long.parseLong(capabilities.position()),routes.view().revision(),
                        List.of(person.personPartyId()),"governance","digital-company");
                assertTrue(arrived.await(15,TimeUnit.SECONDS),"必须真的到达提供方，不能只改网页标签");
                assertEquals("governance",dispatched.get().plan().route().sceneId());
            }
        }
    }
}
