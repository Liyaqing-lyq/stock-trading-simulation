package edu.cufe.auction.web;

import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class DisclosedLoanHttpTest {
    @TempDir Path dir;
    private static final String API = "/api/admin/operations/loan-promotion";

    @Test void onlyAdministratorCanReclassifyTheRecordedContract() throws Exception {
        var journal = dir.resolve("promotion.journal");
        var keys = dir.resolve("fixture.keys");
        Files.writeString(keys, String.join("\n", java.util.List.of("sk-fixture-slot-1-0000001", "sk-fixture-slot-2-0000002",
                "sk-fixture-slot-3-0000003", "sk-fixture-slot-4-0000004", "sk-fixture-slot-5-0000005")));
        try (var routes = SceneRoutes.open(dir);
             var host = new WorldWebHost(() -> {
                 var world = ComposedIndustryScenario.openLoanPromotionOperating(journal);
                 return new WorldWebHost.Opened(world, world.profile().manifest().runId());
             }, routes, new LiveDecisionProvider(slot -> { throw new AssertionError("测试不发送模型请求"); }));
             var server = new WorldWebServer(host, KeySlots.load(keys), routes, 0, "promotion-fixture", null, ignored -> {})) {
            server.start();
            base = server.baseUrl();
            var login = post("/api/session", "{\"bootstrap\":\"promotion-fixture\"}");
            assertEquals(200, login.statusCode());
            remember(login);
            var response = get(API);
            assertEquals(200, response.statusCode(), response.body());
            var before = JSON.readTree(response.body());
            assertEquals("AGGREGATE", before.required("status").asText());
            var id = before.required("loanId").asText();
            var form = JSON.createObjectNode().put("loanId", id).put("expectedPosition", before.required("position").asText());
            var falseAmount = form.deepCopy().put("amount", "1000000");
            assertEquals(400, post(API, falseAmount.toString()).statusCode());
            assertEquals(400, post(API, form.deepCopy().put("loanId", "unrelated").toString()).statusCode());
            assertEquals(409, post(API, form.deepCopy().put("expectedPosition", before.required("position").asLong() + 1).toString()).statusCode());
            assertEquals(before, JSON.readTree(get(API).body()));
            var applied = post(API, form.toString());
            assertEquals(200, applied.statusCode(), applied.body());
            var retry = post(API, form.toString());
            assertEquals(200, retry.statusCode(), retry.body());
            assertEquals(applied.body(), retry.body());
            var after = JSON.readTree(get(API).body());
            assertEquals("DETAILED", after.required("status").asText());
            for (var key : java.util.List.of("loanId", "bankId", "borrower", "nominal", "onBook", "sourceDigest")) {
                assertEquals(before.required(key), after.required(key), key);
            }
            assertEquals(after, JSON.readTree(get("/api/admin/operations").body()).required("loanPromotion"));
            var personal = post("/api/session/person", "{\"personId\":\"nina\"}");
            assertEquals(200, personal.statusCode());
            remember(personal);
            assertEquals(403, get(API).statusCode());
            assertEquals(403, post(API, form.toString()).statusCode());
            assertFalse(JSON.readTree(get("/api/me/operations").body()).has("loanPromotion"));
        }
        try (var restored = ComposedIndustryScenario.openLoanPromotionOperating(journal)) {
            assertEquals("DETAILED", restored.disclosedLoan().get("status"));
            assertTrue(restored.snapshot().decisions().orElseThrow().state().decisions().isEmpty());
        }
    }
}
