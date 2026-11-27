package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.OpeningRegistry;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QuietClockWakeTest {
    @TempDir Path dir;

    @Test void schedulerChecksAtOpeningLunchAndIndividualHaltBoundaries() {
        long minute=60_000L, day=1440*minute;
        for (long t : List.of(570L,630L,660L,690L,780L,900L)) {
            long edge=t*minute;
            assertEquals(edge,edu.cufe.auction.world.MarketSessions.nextBoundary(edge-1,edge+minute));
            assertEquals(edge+minute,edu.cufe.auction.world.MarketSessions.nextBoundary(edge,edge+minute));
        }
        long saturday=5*day;
        assertEquals(saturday,edu.cufe.auction.world.MarketSessions.nextBoundary(saturday-1,saturday+minute));
        assertEquals(saturday+600*minute,edu.cufe.auction.world.MarketSessions.nextBoundary(saturday+560*minute,saturday+600*minute));
    }

    @Test void onePersonWaitsThroughOtherPeoplesMailAndThroughReopenUntilAgreedReview() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var calls = new CopyOnWriteArrayList<Long>();
        var file = dir.resolve("single-person.journal");
        long start = 1_200_000L;
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(file)) {
            world.advanceTo(start);
            for (int i=1; i<=4; i++)
                world.schedulePublishDocument("mail"+i,"task-mail"+i,start+i*12_000,"letter"+i,"nina",
                        InformationModel.Audience.direct("omar"),"私人笔记","只寄给 Omar","私人通信",1000L,List.of());
        }
        try (var routes = SceneRoutes.open(dir)) {
            routes.replace(0,AutonomousLifecycleProbe.probeRoutes(routes.view(),TenCompanyAutonomyTest.MODE));
            for (long target : List.of(start+30_000,start+90_000)) {
                try (var host = new WorldWebHost(() -> {
                    var world=ComposedIndustryScenario.openTenCompanyAutonomy(file);
                    return new WorldWebHost.Opened(world,world.profile().manifest().runId());
                },routes,(call,timeout) -> {
                    try { calls.add(json.readTree(call.inputText()).path("time").asLong()); }
                    catch (java.io.IOException malformed) { throw new AssertionError(malformed); }
                    var reply=json.createObjectNode().put("tool","role.wait");
                    reply.putObject("args");
                    reply.putObject("decision").put("objective","person:investor").put("basis","等账单约定的复核时点").put("reviewAfterMillis",60_000L);
                    return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(),call.actionId(),
                            call.attemptId(),call.attemptNo(),call.recoveryGeneration(),reply.toString(),DecisionModel.Usage.unknownCost(1,40,20),"fixture"));
                })) {
                    long pos=Long.parseLong(host.liveCapabilities().position());
                    host.startAutonomy(pos,routes.view().revision(),target,3_600_000,Set.of("investor"));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
                    while (host.autonomyStatus().running() && System.nanoTime()<deadline) Thread.sleep(10);
                    assertEquals("TARGET_REACHED",host.autonomyStatus().reason());
                    assertEquals(target==start+30_000 ? List.of(start) : List.of(start,start+60_000),calls);
                }
            }
        }
    }

    @Test void busyReaderReceivesNoDecisionUntilCompletionIncludingAfterRestart() throws Exception {
        var journal = dir.resolve("busy-reader.journal");
        long starts = 1_200_002, finishes = starts + 5_000;
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(journal)) {
            world.schedulePublishDocument("private-receipt", "publish", starts - 1, "receipt", "omar",
                    InformationModel.Audience.direct("nina"), "核对一笔收款", "到账金额十八元", "私信", 5_000, List.of());
            world.advanceTo(starts - 1);
            world.scheduleStartReading("read-receipt", "read", starts, "nina", "receipt", 1);
            world.advanceTo(starts);
            assertTrue(world.autonomyRoleBusy("nina"));
            assertFalse(world.autonomyRoleBusy("investor"));
        }
        var calls = new CopyOnWriteArrayList<JsonNode>();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var routes = SceneRoutes.open(dir)) {
            routes.replace(0, AutonomousLifecycleProbe.probeRoutes(routes.view(), TenCompanyAutonomyTest.MODE));
            for (long target : List.of(finishes - 1, finishes + 1)) {
                try (var host = new WorldWebHost(() -> {
                    var world = ComposedIndustryScenario.openTenCompanyAutonomy(journal);
                    return new WorldWebHost.Opened(world, world.profile().manifest().runId());
                }, routes, (call, timeout) -> {
                    var input = assertDoesNotThrow(() -> json.readTree(call.inputText()));
                    calls.add(input);
                    var reply = json.createObjectNode().put("tool", "role.wait");
                    reply.putObject("decision").put("objective", "person:" + input.path("person").asText())
                            .put("basis", "稍后核对").put("reviewAfterMillis", 60_000L);
                    return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(), call.actionId(),
                            call.attemptId(), call.attemptNo(), call.recoveryGeneration(), reply.toString(),
                            DecisionModel.Usage.unknownCost(1, 40, 20), "fixture"));
                })) {
                    host.startAutonomy(Long.parseLong(host.autonomyStatus().position()), routes.view().revision(),
                            target, 3_600_000, Set.of("nina", "investor"));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                    while (host.autonomyStatus().running() && System.nanoTime() < deadline) Thread.sleep(10);
                    assertEquals("TARGET_REACHED", host.autonomyStatus().reason());
                    assertEquals(0, host.autonomyStatus().failures());
                    assertEquals(target < finishes ? 1 : 2, calls.size());
                }
            }
        }
        assertEquals("investor", calls.get(0).path("person").asText());
        assertEquals(starts, calls.get(0).path("time").asLong());
        assertFalse(calls.get(0).at("/profile/catalog").toString().contains("核对一笔收款"));
        assertEquals("nina", calls.get(1).path("person").asText());
        assertEquals(finishes, calls.get(1).path("time").asLong());
        assertTrue(calls.get(1).at("/profile/readExperiences").toString().contains("receipt"));
    }

    @Test void quietMillisecondDoesNotWakeAnyoneButPrivateDeliveryAndMarketOpeningDo() throws Exception {
        var ref = new AtomicReference<WorldRuntime>();
        try (var host = new WorldWebHost(() -> {
            var world = ComposedIndustryScenario.openOpenFundOperating(dir.resolve("world.journal"));
            ref.set(world);
            return new WorldWebHost.Opened(world,world.profile().manifest().runId());
        })) {
            host.adminSnapshot();
            host.call(() -> {
                var world = ref.get();
                long start = 1_200_000, review = start + 86_400_000;
                world.advanceTo(start);
                var previous = new HashMap<String,JsonNode>();
                for (var person : world.openingRegistry().orElseThrow().parties())
                    if (person.kind() == OpeningRegistry.PartyKind.PERSON
                            && !WorldWebScenario.RESEARCH_SYSTEM_PUBLISHER.equals(person.partyId()))
                        previous.put(person.partyId(), host.wakeFacts(world.autonomyRoleObservation(person.partyId())));
                assertEquals(61,previous.size());
                world.advanceTo(start + 1);
                for (var entry : previous.entrySet()) {
                    var input = world.autonomyRoleObservation(entry.getKey());
                    assertEquals(start+1,input.at("/engineering/exchangeSessions/nowMillis").longValue());
                    assertFalse(host.newFactsRequireWake(input,entry.getValue(),start,review),entry.getKey());
                }
                long delivered = start + 2;
                world.schedulePublishDocument("private-proof","private-proof-task",delivered,"direct-only","nina",
                        InformationModel.Audience.direct("investor"),"托我核对的报价","成交单附注","定向信件",1000L,List.of());
                world.advanceTo(delivered);
                var received = world.autonomyRoleObservation("investor");
                assertTrue(received.at("/profile/catalog").toString().contains("direct-only"));
                assertTrue(host.newFactsRequireWake(received,previous.get("investor"),start,review));
                assertFalse(host.newFactsRequireWake(world.autonomyRoleObservation("omar"),previous.get("omar"),start,review));

                long opens = 9 * 3_600_000L + 30 * 60_000;
                world.advanceTo(opens-1);
                var closed = world.autonomyRoleObservation("investor");
                assertFalse(closed.at("/engineering/exchangeSessions/sessionOpen").booleanValue());
                world.advanceTo(opens);
                var open = world.autonomyRoleObservation("investor");
                assertTrue(open.at("/engineering/exchangeSessions/sessionOpen").booleanValue());
                assertTrue(host.newFactsRequireWake(open,host.wakeFacts(closed),opens-1,review));
                return null;
            });
        }
    }
}
