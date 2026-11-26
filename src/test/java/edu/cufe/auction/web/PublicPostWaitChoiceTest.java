package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.RoleDeliberation;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PublicPostWaitChoiceTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void onlyTheNewWaitContractAcceptsAChoiceAboutPublicPosts() {
        var input = JSON.createObjectNode().put("time", 100);
        var engineering = input.putObject("engineering").put("roleContextVersion", "deliberation-v5");
        engineering.putObject("deliberation").putArray("objectives").addObject().put("id", "person:one");
        var reply = JSON.createObjectNode().put("tool", "role.wait");
        var decision = reply.putObject("decision").put("objective", "person:one").put("basis", "等到回款时复查")
                .put("reviewAfterMillis", 120000).put("publicPostWake", "ON_REVIEW");
        assertThrows(IllegalArgumentException.class, () -> RoleDeliberation.validatedAction(input.toString(), reply));
        engineering.put("publicPostWakePolicy", "role-choice-v1");
        assertEquals("role.wait", RoleDeliberation.validatedAction(input.toString(), reply).path("tool").asText());
        decision.put("publicPostWake", "IMMEDIATE");
        assertFalse(RoleDeliberation.validatedAction(input.toString(), reply).has("decision"));
        reply.put("tool", "social.send");
        assertThrows(IllegalArgumentException.class, () -> RoleDeliberation.validatedAction(input.toString(), reply));
        reply.put("tool", "role.wait");
        decision.put("publicPostWake", "IGNORE_ALL");
        assertThrows(IllegalArgumentException.class, () -> RoleDeliberation.validatedAction(input.toString(), reply));
        decision.remove("publicPostWake");
        assertFalse(RoleDeliberation.validatedAction(input.toString(), reply).has("decision"));
    }

    @Test void savedWaitDefersSeveralUnaddressedPostsUntilReviewButADirectDocumentWakesImmediately() throws Exception {
        Path journal = directory.resolve("chosen-wake.journal");
        String person = "investor";
        long last;
        long review;
        String frozenInput;
        try (var world = ComposedIndustryScenario.openContinuingRolesOperating(journal)) {
            last = world.snapshot().timeMillis();
            review = last + 60000;
            world.beginDecisionSession("wait-session");
            var route = new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20, 1_000_000, 1024, 1);
            world.openDecisions("open", List.of(new DecisionRun.Request(person, "wait", "wake", route)));
            var call = world.dispatchLiveDecision("dispatch", "wait").call();
            frozenInput = call.inputText();
            var input = JSON.readTree(frozenInput);
            var reply = JSON.createObjectNode().put("tool", "role.wait");
            reply.putObject("decision").put("objective", input.at("/engineering/deliberation/objectives/0/id").asText())
                    .put("basis", "在约定复查时阅读广场。").put("reviewAfterMillis", 60000).put("publicPostWake", "ON_REVIEW");
            assertEquals("QUEUED", world.offerDecisionCallback(new DecisionModel.Reply(call.runId(), call.actionId(),
                    call.attemptId(), call.attemptNo(), call.recoveryGeneration(), reply.toString(),
                    DecisionModel.Usage.unknownCost(1, 50, 30), "fixture")));
            assertEquals(1, world.drainDecisionCallbacks());
            world.advanceTo(last + 1100);
            assertEquals(review, world.autonomyNextReviewAt(person, -1));
            for (String author : List.of("sam", "rita")) {
                var config = world.groupsRun().platformConfig();
                long end = world.snapshot().timeMillis() + config.postMillis();
                world.openRoleSession(author).postToPlatform("post-" + author, "task-" + author, end,
                        new WorldRoleSession.PlatformPostDraft("公开札记 " + author, "公开观察。", List.of()));
                world.advanceTo(end + config.deliveryMillis());
            }
        }

        var opened = new AtomicReference<WorldRuntime>();
        try (var host = new WorldWebHost(() -> {
            var world = ComposedIndustryScenario.openContinuingRolesOperating(journal);
            opened.set(world);
            return new WorldWebHost.Opened(world, world.profile().manifest().runId());
        })) {
            host.adminSnapshot();
            host.call(() -> {
                var world = opened.get();
                var observed = WorldWebHost.class.getDeclaredMethod("wakeFacts", JsonNode.class);
                observed.setAccessible(true);
                var wakes = WorldWebHost.class.getDeclaredMethod("newFactsRequireWake", JsonNode.class, JsonNode.class,
                        long.class, long.class);
                wakes.setAccessible(true);
                var previous = observed.invoke(host, JSON.readTree(frozenInput));
                var current = (ObjectNode) world.autonomyRoleObservation(person);
                assertEquals(2, current.at("/groups/feed").size(), "延期期间仍然可以看到两帖");
                assertEquals("ON_REVIEW", current.at("/engineering/deliberation/recentDecisions/0/ownStatement/publicPostWake").textValue());
                assertEquals(false, wakes.invoke(host, current, previous, last, review));

                // 与冻结状态的对照：相同广场帖在默认订阅、联系人发出或到期时均不延后。
                var immediate = current.deepCopy();
                ((ObjectNode) immediate.at("/engineering/deliberation/recentDecisions/0/ownStatement")).remove("publicPostWake");
                assertEquals(true, wakes.invoke(host, immediate, previous, last, review));
                var contact = current.deepCopy();
                contact.withArray("/social/contacts").addObject().put("targetPartyId", "sam");
                assertEquals(true, wakes.invoke(host, contact, previous, last, review));
                assertEquals(true, wakes.invoke(host, current.deepCopy().put("time", review), previous, last, review));

                long due = world.snapshot().timeMillis() + 1;
                world.schedulePublishDocument("delivery", "delivery-task", due, "direct-update", "rita",
                        InformationModel.Audience.direct(person), "定向回款通知", "请查收", "模拟往来", 1000L, List.of());
                world.advanceTo(due);
                var withDelivery = world.autonomyRoleObservation(person);
                assertTrue(withDelivery.at("/profile/catalog").toString().contains("direct-update"));
                assertEquals(true, wakes.invoke(host, withDelivery, previous, last, review));
                return null;
            });
        }
    }
}
