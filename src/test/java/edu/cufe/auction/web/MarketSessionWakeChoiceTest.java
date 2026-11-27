package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.RoleDeliberation;
import edu.cufe.auction.world.RolePlanningContext;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
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

class MarketSessionWakeChoiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MINUTE = 60_000L;
    private static final long START = 570 * MINUTE - 5000L;
    private static final long REVIEW = START + 2 * 86_400_000L;
    private static final List<String> PEOPLE = List.of("rita", "judy", "sam", "omar", "investor", "dave");
    @TempDir Path directory;

    @Test void personalSubscriptionDeliveriesWakeTheSubscriberAndKeepPrivateSupplementsPrivate() throws Exception {
        Path file = directory.resolve("subscriber.journal");
        var opened = new AtomicReference<WorldRuntime>();
        try (var host = new WorldWebHost(() -> {
            var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file);
            opened.set(w);
            return new WorldWebHost.Opened(w, w.profile().manifest().runId());
        })) {
            host.adminSnapshot();
            host.call(() -> {
                var w = opened.get();
                w.scheduleMediaSubscription("nina", "JOIN", "wire", "trial");
                w.advanceTo(1000L);
                long observed = w.snapshot().timeMillis(), review = observed + 86_400_000L;
                var route = new DecisionModel.Route("trading","key-1","deepseek-flash",1,25,1_000_000,2048,1);
                w.beginDecisionSession("subscriber-wait");
                w.openDecisions("two-waits",List.of("nina","judy").stream().map(p -> new DecisionRun.Request(p,p,"wake",route)).toList());
                for (String person : List.of("nina","judy")) {
                    var c=w.dispatchLiveDecision("local-"+person,person).call();
                    var reply=JSON.createObjectNode().put("tool","role.wait");
                    reply.putObject("decision").put("objective",JSON.readTree(c.inputText()).at("/engineering/deliberation/objectives/0/id").asText())
                            .put("basis","等待本人收到资料").put("marketSessionWake","ON_REVIEW").put("reviewAfterMillis",review-observed);
                    assertEquals("QUEUED",w.offerDecisionCallback(new DecisionModel.Reply(c.runId(),c.actionId(),c.attemptId(),c.attemptNo(),
                            c.recoveryGeneration(),reply.toString(),DecisionModel.Usage.unknownCost(1,20,20),"local")));
                    w.drainDecisionCallbacks();
                }
                w.advanceTo(observed+2000);
                assertEquals(review,w.autonomyNextReviewAt("nina",-1));
                var beforeIssue = host.wakeFacts(w.autonomyRoleObservation("nina"));
                var issue = edu.cufe.auction.world.composed.MediaSubscriptionPublicationTest.publishWire(w);
                var news = w.autonomyRoleObservation("nina");
                var publicNotice = news.at("/engineering/mediaSubscriptions/deliveries/0");
                assertEquals("PUBLIC_NOTICE", publicNotice.path("kind").textValue());
                assertEquals(issue, publicNotice.path("docId").textValue());
                assertTrue(host.newFactsRequireWake(news, beforeIssue, observed, review));
                assertTrue(w.autonomyRoleObservation("judy").at("/engineering/mediaSubscriptions/deliveries").isEmpty());
                var turn = edu.cufe.auction.world.MediaSubscriptionsTest.closedTurn(w);
                var before = host.wakeFacts(w.autonomyRoleObservation("nina"));
                var outsider = host.wakeFacts(w.autonomyRoleObservation("judy"));
                w.scheduleMediaSubscription("rita", "RELEASE_TURN", turn, "consent");
                w.advanceTo(w.snapshot().timeMillis() + 1000L);
                var delivered = w.autonomyRoleObservation("nina");
                assertTrue(delivered.at("/engineering/mediaSubscriptions/deliveries").findValuesAsText("kind").contains("INTERVIEW_SUPPLEMENT"));
                assertTrue(host.newFactsRequireWake(delivered, before, observed, review));
                assertFalse(host.newFactsRequireWake(w.autonomyRoleObservation("judy"), outsider, observed, review));
                return null;
            });
        }
    }

    @Test void restoredWaitHonorsChoiceOrActualExposureAndPreservesOtherEvents() throws Exception {
        Path file = directory.resolve("world.journal");
        try (var world = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            world.advanceTo(START);
            world.beginDecisionSession("market-wait");
            var route = new DecisionModel.Route("media", "key-1", "deepseek-flash", 1L, 20, 1_000_000, 1024, 1);
            world.openDecisions("wait", PEOPLE.stream().map(who -> new DecisionRun.Request(who, who, "wake", route)).toList());
            for (String who : PEOPLE) {
                var call = world.dispatchLiveDecision("dispatch-" + who, who).call();
                var input = JSON.readTree(call.inputText());
                var reply = JSON.createObjectNode().put("tool", "role.wait");
                var decision = reply.putObject("decision").put("objective", input.at("/engineering/deliberation/objectives/0/id").asText())
                        .put("basis", "等到预定复核或本人有新材料。").put("reviewAfterMillis", REVIEW - START);
                if ("sam".equals(who)) decision.put("marketSessionWake", "IMMEDIATE");
                if ("omar".equals(who)) decision.put("marketSessionWake", "ON_REVIEW");
                assertEquals("QUEUED", world.offerDecisionCallback(new DecisionModel.Reply(call.runId(), call.actionId(),
                        call.attemptId(), call.attemptNo(), call.recoveryGeneration(), reply.toString(),
                        DecisionModel.Usage.unknownCost(1, 50, 30), "fixture")));
                assertEquals(1, world.drainDecisionCallbacks());
            }
            world.advanceTo(START + 2000);
            assertEquals(REVIEW, world.autonomyNextReviewAt("omar", -1));
        }
        var opened = new AtomicReference<WorldRuntime>();
        try (var host = new WorldWebHost(() -> {
            var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file);
            opened.set(w);
            return new WorldWebHost.Opened(w, w.profile().manifest().runId());
        })) {
            host.adminSnapshot();
            host.call(() -> {
                var w = opened.get();
                assertEquals("ON_REVIEW", w.autonomyRoleObservation("omar").at(
                        "/engineering/deliberation/recentDecisions/0/ownStatement/marketSessionWake").asText());
                for (int minute : List.of(570, 630, 660, 690, 780, 900)) {
                    long edge = minute * MINUTE;
                    w.advanceTo(edge - 1);
                    var before = new HashMap<String, JsonNode>();
                    for (String who : PEOPLE) before.put(who, host.wakeFacts(w.autonomyRoleObservation(who)));
                    w.advanceTo(edge);
                    for (String who : PEOPLE) {
                        var input = w.autonomyRoleObservation(who);
                        boolean haltChanged = minute == 630 || minute == 660;
                        boolean routineAttention = List.of("sam", "investor", "dave").contains(who);
                        assertEquals(haltChanged || routineAttention,
                                host.newFactsRequireWake(input, before.get(who), START, REVIEW), who + " at " + minute);
                    }
                }
                long delivery = 86_400_000L + 570 * MINUTE;
                w.advanceTo(delivery - 1);
                var previous = host.wakeFacts(w.autonomyRoleObservation("rita"));
                var quiet = host.wakeFacts(w.autonomyRoleObservation("judy"));
                w.schedulePublishDocument("letter", "deliver-letter", delivery, "private-result", "sam",
                        InformationModel.Audience.direct("rita"), "回信", "已经取回原件", "通信", 1000, List.of());
                w.advanceTo(delivery);
                var received = w.autonomyRoleObservation("rita");
                assertTrue(received.at("/profile/catalog").toString().contains("private-result"));
                assertTrue(host.newFactsRequireWake(received, previous, START, REVIEW));
                assertFalse(host.newFactsRequireWake(w.autonomyRoleObservation("judy"), quiet, START, REVIEW));
                // Omar 已选择将普通时段延至复查，仍能收到实际挂单及收市撤除带来的账户变化。
                var beforeOrder = host.wakeFacts(w.autonomyRoleObservation("omar"));
                w.scheduleOrder("my-bid", "my-bid-task", delivery + 1, "omar", "HOUSE-OMAR", "AGED",
                        Side.BUY, OrderType.LIMIT, new BigDecimal("9.50"), 1L);
                w.advanceTo(delivery + 1);
                var withBid = w.autonomyRoleObservation("omar");
                assertEquals(1, withBid.at("/engineering/personalMarket/openOrders").size());
                assertTrue(host.newFactsRequireWake(withBid, beforeOrder, START, REVIEW));
                long close = 86_400_000L + 900 * MINUTE;
                w.advanceTo(close - 1);
                var beforeClose = host.wakeFacts(w.autonomyRoleObservation("omar"));
                w.advanceTo(close);
                var afterClose = w.autonomyRoleObservation("omar");
                assertTrue(afterClose.at("/engineering/personalMarket/openOrders").isEmpty());
                assertTrue(host.newFactsRequireWake(afterClose, beforeClose, START, REVIEW));
                return null;
            });
        }
    }

    @Test void autonomousReporterWaitsAcrossFourBoundariesUntilChosenReview() throws Exception {
        Path file = directory.resolve("reporter.journal");
        long start = 86_400_000L + START;
        long review = 86_400_000L + 900 * MINUTE + 3000;
        try (var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            w.advanceTo(start);
        }
        var calls = new CopyOnWriteArrayList<Long>();
        try (var routes = SceneRoutes.open(directory)) {
            routes.replace(0, AutonomousLifecycleProbe.probeRoutes(routes.view(), "TEN_COMPANY_PERSONAL_NEWS_STUDY"));
            try (var host = new WorldWebHost(() -> {
                var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file);
                return new WorldWebHost.Opened(w, w.profile().manifest().runId());
            }, routes, (call, timeout) -> {
                JsonNode input;
                try { input = JSON.readTree(call.inputText()); }
                catch (java.io.IOException malformed) { throw new AssertionError(malformed); }
                calls.add(input.path("time").asLong());
                var reply = JSON.createObjectNode().put("tool", "role.wait");
                reply.putObject("decision").put("objective", "person:rita").put("basis", "按期复核个人日程")
                        .put("reviewAfterMillis", review - start);
                return LiveCompletionInbox.Completion.business(new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                        call.attemptNo(), call.recoveryGeneration(), reply.toString(), DecisionModel.Usage.unknownCost(1, 30, 20), "fixture"));
            })) {
                host.startAutonomy(Long.parseLong(host.liveCapabilities().position()), routes.view().revision(),
                        review + 4000, 3_600_000L, Set.of("rita"));
                long wallDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (host.autonomyStatus().running() && System.nanoTime() < wallDeadline) Thread.sleep(10);
                assertEquals("TARGET_REACHED", host.autonomyStatus().reason());
                assertEquals(List.of(start, review), calls);
            }
        }
    }

    @Test void defaultsUseActualHoldingsOpenOrdersAndCurrentProductMandates() throws Exception {
        try (var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(directory.resolve("defaults"))) {
            var config = w.snapshot().decisions().orElseThrow().state().config();
            var owner = (ObjectNode) w.autonomyRoleObservation("omar");
            assertEquals("IMMEDIATE", owner.at("/engineering/marketSessionWakeDefault").asText());
            owner.withObject("/profile").putArray("holdings");
            RolePlanningContext.append(owner, "wake", config);
            assertEquals("ON_REVIEW", owner.at("/engineering/marketSessionWakeDefault").asText());
            owner.withArray("/engineering/personalMarket/openOrders").addObject().put("remaining", 1L);
            RolePlanningContext.append(owner, "wake", config);
            assertEquals("IMMEDIATE", owner.at("/engineering/marketSessionWakeDefault").asText());

            var executor = (ObjectNode) w.autonomyRoleObservation("dave");
            executor.withObject("/profile").putArray("holdings");
            RolePlanningContext.append(executor, "wake", config);
            assertEquals("IMMEDIATE", executor.at("/engineering/marketSessionWakeDefault").asText());
            executor.withObject("/engineering").putArray("authorizedProducts");
            RolePlanningContext.append(executor, "wake", config);
            assertEquals("ON_REVIEW", executor.at("/engineering/marketSessionWakeDefault").asText());
        }
    }

    @Test void optionalClockFieldIsConfinedToGeneratedWaitContract() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            try (var world = enabled ? ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(directory.resolve("new"))
                    : ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(directory.resolve("old"))) {
                var input = world.autonomyRoleObservation("rita");
                assertEquals(enabled, input.at("/engineering/deliberation/replyContract").asText().contains("marketSessionWake"));
                var reply = JSON.createObjectNode().put("tool", "role.wait");
                var decision = reply.putObject("decision").put("objective", input.at("/engineering/deliberation/objectives/0/id").asText())
                        .put("basis", "等到复查").put("reviewAfterMillis", 2000).put("publicPostWake", "ON_REVIEW");
                for (String choice : List.of("ON_REVIEW", "IMMEDIATE")) {
                    decision.put("marketSessionWake", choice);
                    if (enabled) assertEquals("role.wait", RoleDeliberation.validatedAction(input.toString(), reply).path("tool").asText());
                    else assertThrows(IllegalArgumentException.class, () -> RoleDeliberation.validatedAction(input.toString(), reply));
                }
                decision.put("marketSessionWake", "IGNORE_ALL");
                assertThrows(IllegalArgumentException.class, () -> RoleDeliberation.validatedAction(input.toString(), reply));
                decision.put("marketSessionWake", "ON_REVIEW");
                reply.put("tool", "role.library.search");
                assertThrows(IllegalArgumentException.class, () -> RoleDeliberation.validatedAction(input.toString(), reply));
            }
        }
    }
}
