package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.BranchRepository;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class NewsletterHttpTest {
    @TempDir Path dir;
    private static final String PATH = "/api/me/newsletters";
    private String registeredWireName;
    private final AtomicReference<String> entry = new AtomicReference<>("newsletter-test-entry");

    private void login(String person) throws Exception {
        cookie = null;
        var admin = post("/api/session", JSON.createObjectNode().put("bootstrap", entry.get()).toString());
        assertEquals(200, admin.statusCode());
        remember(admin);
        assertEquals(403, get(PATH).statusCode());
        var selected = post("/api/session/person", JSON.createObjectNode().put("personId", person).toString());
        assertEquals(200, selected.statusCode());
        remember(selected);
    }

    private WorldWebServer server(WorldWebHost host, Path keys, SceneRoutes routes) throws Exception {
        return new WorldWebServer(host, KeySlots.load(keys), routes, 0, entry.get(), null, line -> {
            if (line.contains("引导凭据")) entry.set(line.substring(line.indexOf('：') + 1).trim());
        });
    }

    private JsonNode view() throws Exception {
        var result = get(PATH);
        assertEquals(200, result.statusCode(), result.body());
        return JSON.readTree(result.body());
    }

    private boolean subscribed(JsonNode page) {
        for (var row : page.required("offers")) if ("wire".equals(row.required("organization").asText())) {
            assertEquals(registeredWireName, row.required("name").asText());
            return row.required("subscribed").asBoolean();
        }
        return fail("缺少通讯社");
    }

    @Test void trialDeliveryControlsBelongToTheAuthenticatedPersonOnTheMarkedBranch() throws Exception {
        var parentFile = dir.resolve("parent.journal");
        var keys = dir.resolve("keys");
        Files.write(keys, List.of("sk-newsletter-demo-00001", "sk-newsletter-demo-00002", "sk-newsletter-demo-00003", "sk-newsletter-demo-00004", "sk-newsletter-demo-00005"));
        var repo = new BranchRepository(dir.resolve("runs"));
        String branch = "newsletter-child";
        BranchProfile profile;
        try (var world = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(parentFile)) {
            registeredWireName = world.openingRegistry().orElseThrow().parties().stream()
                    .filter(p -> p.partyId().equals("wire")).findFirst().orElseThrow().name();
            world.createBranch(branch, world.snapshot().position(), BranchOrigin.Kind.GOD_TAKEOVER, "试刊操作");
            profile = world.profile();
        }
        byte[] original = Files.readAllBytes(parentFile);
        try (var routes = SceneRoutes.open(dir);
             var host = new WorldWebHost(() -> {
                 var w = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(parentFile);
                 return new WorldWebHost.Opened(w, w.profile().manifest().runId());
             });
             var web = server(host, keys, routes)) {
            web.start(); base = web.baseUrl(); cookie = null;
            assertEquals(401, get(PATH).statusCode());
            login("nina");
            assertFalse(subscribed(view()));
            assertEquals(403, post(PATH, "{}").statusCode());
        } finally { cookie = null; }
        try (var routes = SceneRoutes.open(dir);
             var host = new WorldWebHost(() -> new WorldWebHost.Opened(repo.open(branch, profile), branch));
             var web = server(host, keys, routes)) {
            web.start(); base = web.baseUrl();
            login("nina");
            var start = view();
            var join = JSON.createObjectNode().put("action","JOIN").put("target","wire")
                    .put("commandId","trial-1").put("observedMillis", start.required("observedMillis").asText());
            assertEquals(400, post(PATH, join.deepCopy().put("person", "omar").toString()).statusCode());
            var reply = post(PATH, join.toString());
            assertEquals(200, reply.statusCode(), reply.body());
            assertFalse(subscribed(view()));
            long position = host.adminSnapshot().position();
            assertEquals(reply.body(), post(PATH, join.toString()).body());
            assertEquals(position, host.adminSnapshot().position());
            host.advance(position, start.required("observedMillis").asLong() + 1000L);
            assertTrue(subscribed(view()));
            assertEquals(reply.body(), post(PATH, join.toString()).body());
            login("omar");
            assertFalse(subscribed(view()));
            login("nina");
            var leave = join.deepCopy().put("action","LEAVE").put("commandId","trial-2")
                    .put("observedMillis",view().required("observedMillis").asText());
            assertEquals(200, post(PATH, leave.toString()).statusCode());
            host.advance(host.adminSnapshot().position(), leave.required("observedMillis").asLong() + 1000L);
            assertFalse(subscribed(view()));
        } finally { cookie = null; }
        assertArrayEquals(original, Files.readAllBytes(parentFile));
        try (var child = repo.open(branch, profile)) {
            assertEquals(2, child.snapshot().trading().orElseThrow().reports().values().stream()
                    .filter(r -> r.personPartyId().equals("nina") && r.clientCommandId().startsWith("web-news:4:nina:") && r.accepted()).count());
        }
    }
}
