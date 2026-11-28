package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.SceneRoutes;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 新输入策略仍服从全局预算/配置/故障/在途屏障，纯观察时点不是经营新事实。 */
class InformedHostBoundaryTest {
    @TempDir Path dir;
    static final ObjectMapper JSON=new ObjectMapper();
    private void enable(SceneRoutes routes,int calls) throws Exception {routes.replace(routes.view().revision(),routes.view().routes().stream().map(r->new SceneRoutes.Route(r.scene(),r.slotId(),r.model(),true,calls,5_000_000,512,Math.min(4,calls),"lifecycle-v1")).toList());}
    private LiveCompletionInbox.Completion waitReply(DecisionRun.Call c) {
        try {
            var input=JSON.readTree(c.inputText());var reply=JSON.createObjectNode().put("tool","role.wait");
            reply.putObject("decision").put("objective",input.path("engineering").path("deliberation").path("objectives").get(0).path("id").asText()).put("basis","边界机制测试").put("reviewAfterMillis",86_400_000);
            return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(c.runId(),c.actionId(),c.attemptId(),c.attemptNo(),c.recoveryGeneration(),reply.toString(),DecisionModel.Usage.unknownCost(1,10,30),"TEST"));
        }catch(Exception e){throw new AssertionError(e);}
    }
    private WorldWebHost.AutonomyStatus finish(WorldWebHost host) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(host.autonomyStatus().running()&&System.nanoTime()<end)Thread.sleep(25);
        assertFalse(host.autonomyStatus().running());return host.autonomyStatus();
    }
    private void start(WorldWebHost host,SceneRoutes routes,long target) throws Exception {host.startAutonomy(Long.parseLong(host.autonomyStatus().position()),routes.view().revision(),target,3_600_000);}

    @Test void managerObservationTimestampDoesNotWakeButActualNewMaterialDoes() throws Exception {
        var times=new CopyOnWriteArrayList<Long>();var actual=new AtomicReference<WorldRuntime>();var base=new AtomicLong();
        try(var routes=SceneRoutes.open(dir)) {
            enable(routes,500);
            try(var host=new WorldWebHost(()->{
                var w=ComposedIndustryScenario.openInformedOperating(dir.resolve("wake.journal"));actual.set(w);
                OperationsFixture.grantBudgetAndAppoint(w,ComposedManufacturingThreeScenario.TECH,ComposedManufacturingThreeScenario.TECH_DIRECTORS,ComposedManufacturingThreeScenario.TECH_MANAGER,"wake",List.of(0L));
                base.set(w.snapshot().timeMillis());
                w.schedulePublishDocument("noise","noise-task",base.get()+40000,"other-private","alice",InformationModel.Audience.direct("alice"),"其他人资料","短","fixture",10,List.of());
                w.schedulePublishDocument("own","own-task",base.get()+130000,"manager-new-fact","alice",InformationModel.Audience.direct(ComposedManufacturingThreeScenario.TECH_MANAGER),"新事实","短","fixture",10,List.of());
                return new WorldWebHost.Opened(w,w.profile().manifest().runId());
            },routes,(c,t)->{try{var in=JSON.readTree(c.inputText());if(in.path("person").asText().equals(ComposedManufacturingThreeScenario.TECH_MANAGER))times.add(in.path("time").asLong());}catch(Exception e){throw new AssertionError(e);}return waitReply(c);})) {
                host.autonomyStatus();start(host,routes,base.get()+120000);assertEquals("TARGET_REACHED",finish(host).reason());
                assertEquals(1,times.size(),"unrelated events and current asOfMillis must not trigger paid manager observations");
                assertEquals(base.get()+120000,actual.get().autonomyRoleObservation(ComposedManufacturingThreeScenario.TECH_MANAGER).path("engineering").path("companyOperations").get(0).path("asOfMillis").asLong());
                start(host,routes,base.get()+150000);assertEquals("TARGET_REACHED",finish(host).reason());assertEquals(2,times.size());assertTrue(times.get(1)>=base.get()+130000);
            }
        }
    }

    @Test void finiteSceneBudgetStillStopsGlobally() throws Exception {
        var calls=new AtomicInteger();
        try(var routes=SceneRoutes.open(dir)) {
            enable(routes,1);
            try(var host=new WorldWebHost(()->{var w=ComposedIndustryScenario.openInformedOperating(dir.resolve("budget.journal"));return new WorldWebHost.Opened(w,w.profile().manifest().runId());},routes,(c,t)->{calls.incrementAndGet();return waitReply(c);})) {
                long initial=host.adminSnapshot().timeMillis();start(host,routes,3600000);var result=finish(host);
                assertEquals("BUDGET_EXHAUSTED",result.reason());assertEquals(initial,host.adminSnapshot().timeMillis());assertEquals(0,result.cycles());assertTrue(calls.get()<=4);
            }
        }
    }

    @Test void inFlightPreventsAdvancementAndConfigurationChangeStillStopsGlobally() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var revisions = new CopyOnWriteArrayList<Long>();
        var actual = new AtomicReference<WorldRuntime>();
        try (var routes = SceneRoutes.open(dir)) {
            routes.replace(routes.view().revision(), routes.view().routes().stream().map(r ->
                    new SceneRoutes.Route(r.scene(), r.slotId(), r.model(), true, 500,
                            5_000_000, 512, 1, "lifecycle-v1")).toList());
            try (var host = new WorldWebHost(() -> {
                var w = ComposedIndustryScenario.openInformedOperating(dir.resolve("config.journal"));
                actual.set(w);
                return new WorldWebHost.Opened(w, w.profile().manifest().runId());
            }, routes, (c, t) -> {
                calls.incrementAndGet();
                revisions.add(c.plan().route().revision());
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("provider latch timeout");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return waitReply(c);
            })) {
                try {
                    long initial = host.adminSnapshot().timeMillis();
                    start(host, routes, 3_600_000);
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    var before = host.liveStatus();
                    assertEquals(1, before.physicalInFlight());
                    assertFalse(before.pendingPersons().isEmpty(), "fixture must leave actual pending people");
                    assertEquals(initial, host.adminSnapshot().timeMillis());
                    long originalRevision = routes.view().revision();
                    enable(routes, 500);
                    assertEquals("CONFIGURATION_CHANGED", finish(host).reason());
                    var stopping = host.liveStatus();
                    assertEquals("STOPPING", stopping.phase());
                    assertTrue(stopping.pendingPersons().isEmpty());
                    assertEquals(1, stopping.physicalInFlight());
                    assertEquals(1, calls.get());
                    release.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!host.liveStatus().phase().equals("IDLE") && System.nanoTime() < deadline)
                        Thread.sleep(25);
                    var settled = host.liveStatus();
                    assertEquals("IDLE", settled.phase());
                    assertEquals(0, settled.physicalInFlight());
                    assertEquals(0, settled.completionSlots());
                    assertEquals("1", settled.runBudget().usedCalls());
                    assertEquals("0", settled.runBudget().reservedCalls());
                    assertEquals("10", settled.runBudget().usedInputTokens());
                    assertEquals("30", settled.runBudget().usedOutputTokens());
                    var decisions = actual.get().snapshot().decisions().orElseThrow().state().decisions();
                    assertEquals(1, decisions.size());
                    assertEquals(DecisionModel.Status.RECORDED, decisions.get(0).status());
                    assertEquals(DecisionModel.Outcome.WAIT, decisions.get(0).outcome());
                    // A second writer pump after drain cannot revive the canceled pending queue.
                    host.liveStatus();
                    assertEquals(1, calls.get());
                    assertEquals(List.of(originalRevision), revisions);
                    assertEquals(initial, host.adminSnapshot().timeMillis());
                    assertEquals("CONFIGURATION_CHANGED", host.autonomyStatus().reason());
                } finally { release.countDown(); }
            }
        }
    }

    private WorldRuntime votingBoundary(Path journal, int titleLength) throws Exception {
        var world = ComposedIndustryScenario.openInformedOperating(journal);
        world.schedulePublishDocument("padding", "padding-task", 3, "padding-document", "alice",
                InformationModel.Audience.direct("mfgtech-d2"), "A".repeat(titleLength), "short", "fixture", 10, List.of());
        world.scheduleCompanyBoardConvene("convene", "convene-task", 1000,
                ComposedManufacturingThreeScenario.TECH, "mfgtech-d1", "cache-meeting",
                199000, 200000, 300000, false);
        world.advanceTo(197999);
        return world;
    }

    @Test void inputShrinksAcrossActualVotePrerequisiteBoundaryEvenWhenPaidWakeFactsStayEqual() throws Exception {
        String person = "mfgtech-d2";
        var raw = WorldRuntime.class.getDeclaredMethod("autonomyRoleInputText", String.class);
        raw.setAccessible(true);
        var wake = WorldWebHost.class.getDeclaredMethod("wakeFacts", com.fasterxml.jackson.databind.JsonNode.class);
        wake.setAccessible(true);
        int padding;
        try (var calibration = votingBoundary(dir.resolve("calibration.journal"), 1)) {
            padding = 32769 - ((String) raw.invoke(calibration, person)).length() + 1;
        }
        var actual = new AtomicReference<WorldRuntime>();
        var calls = new ConcurrentHashMap<String, AtomicInteger>();
        try (var routes = SceneRoutes.open(dir)) {
            enable(routes, 500);
            try (var host = new WorldWebHost(() -> {
                try {
                    var world = votingBoundary(dir.resolve("prerequisite.journal"), padding);
                    actual.set(world);
                    return new WorldWebHost.Opened(world, world.profile().manifest().runId());
                } catch (Exception e) { throw new java.io.IOException(e); }
            }, routes, (call, timeout) -> {
                try {
                    String who = JSON.readTree(call.inputText()).path("person").asText();
                    calls.computeIfAbsent(who, key -> new AtomicInteger()).incrementAndGet();
                    return waitReply(call);
                } catch (Exception e) { throw new AssertionError(e); }
            })) {
                host.autonomyStatus();
                var before = host.call(() -> actual.get().autonomyRoleObservation(person));
                var violation = host.call(() -> actual.get().autonomyInputLimitViolation(person).orElseThrow());
                assertEquals(32769, violation.actualChars());
                host.startLiveRound(Long.parseLong(host.liveStatus().position()), routes.view().revision(),
                        List.of(person), "governance", ComposedManufacturingThreeScenario.TECH);
                long idleBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!host.liveStatus().phase().equals("IDLE") && System.nanoTime() < idleBy) Thread.sleep(25);
                assertEquals("INPUT_TOO_LARGE", host.liveStatus().skips().get(0).reason());
                var stable = actual.get().snapshot();
                for (int i = 0; i < 3; i++) host.liveStatus();
                assertEquals(stable, actual.get().snapshot());
                assertTrue(calls.isEmpty());
                var after = host.call(() -> {
                    actual.get().advanceTo(198000);
                    return actual.get().autonomyRoleObservation(person);
                });
                assertEquals(wake.invoke(host, before), wake.invoke(host, after), "paid wake facts stay equal");
                assertEquals(32755, ((String) raw.invoke(actual.get(), person)).length());
                assertTrue(actual.get().autonomyInputLimitViolation(person).isEmpty());
                assertTrue(host.liveStatus().skips().stream().noneMatch(skip -> skip.personPartyId().equals(person)),
                        "a free size recheck must clear the stale block when only ignored prerequisite text shrinks");
                assertTrue(calls.isEmpty(), "free status rechecks must never dispatch a provider request");
                assertTrue(actual.get().snapshot().decisions().orElseThrow().state().decisions().isEmpty());
                start(host, routes, 198001);
                assertEquals("TARGET_REACHED", finish(host).reason());
                assertEquals(1, calls.get(person).get(), "the previously blocked person can receive its first actual dispatch");
                var settled = actual.get().snapshot();
                for (int i = 0; i < 3; i++) host.liveStatus();
                assertEquals(settled, actual.get().snapshot());
                assertEquals(1, calls.get(person).get());
            }
        }
    }

    @Test void realJournalWriteFailureStillStopsGlobally() throws Exception {
        var actual=new AtomicReference<WorldRuntime>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var routes=SceneRoutes.open(dir)) {
            enable(routes,500);
            try(var host=new WorldWebHost(()->{var w=ComposedIndustryScenario.openInformedOperating(dir.resolve("fault.journal"));actual.set(w);return new WorldWebHost.Opened(w,w.profile().manifest().runId());},routes,(c,t)->{entered.countDown();try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("provider latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();return waitReply(c);}return waitReply(c);})) {
                try {
                    start(host,routes,3600000);assertTrue(entered.await(10,TimeUnit.SECONDS));
                    assertThrows(Exception.class,()->host.call(()->{
                        var w=actual.get();var journalField=WorldRuntime.class.getDeclaredField("journal");journalField.setAccessible(true);var journal=journalField.get(w);
                        var channelField=journal.getClass().getDeclaredField("channel");channelField.setAccessible(true);var original=(FileChannel)channelField.get(journal);
                        channelField.set(journal,new FundedProbes.FailingChannel(original,FundedProbes.FailingChannel.Mode.WRITE,0));
                        return w.schedulePublishDocument("fail-write","fail-write-task",10000,"io-fault","alice",InformationModel.Audience.direct("alice"),"标题","正文","fixture",10,List.of());
                    }));
                    assertEquals("WORLD_FAULT",finish(host).reason());assertTrue(actual.get().isPaused());assertEquals("FAULT",host.liveStatus().phase());
                } finally {release.countDown();}
            }
        }
    }
}
