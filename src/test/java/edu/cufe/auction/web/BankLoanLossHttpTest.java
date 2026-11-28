package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.BranchRepository;
import edu.cufe.auction.world.composed.BankLoanLossDecisionTest;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BankLoanLossHttpTest {
    @TempDir Path dir;
    static final String PAGE = "/api/me/bank", ACTION = PAGE + "/action";

    private static String request(String id, String kind, String amount) {
        return JSON.createObjectNode().put("commandId", id).put("taskId", id + "-task")
                .put("kind", kind).put("loanId", "working-capital").put("amount", amount).put("workMillis", 60_000).toString();
    }

    private static JsonNode own() throws Exception {
        var page = get(PAGE);
        assertEquals(200, page.statusCode(), page.body());
        return JSON.readTree(page.body());
    }

    private static void person(String token, String who) throws Exception {
        cookie = null;
        var admin = post("/api/session", JSON.createObjectNode().put("bootstrap", token).toString());
        assertEquals(200, admin.statusCode()); remember(admin);
        var selected = post("/api/session/person", JSON.createObjectNode().put("personId", who).toString());
        assertEquals(200, selected.statusCode()); remember(selected);
    }

    @Test void takeoverUsesSameSignedOneMinuteActionsAndOnlyTheManagerSeesLossPortfolio() throws Exception {
        String child = "human-bank";
        var journal = dir.resolve("world.journal");
        BranchProfile profile;
        try (var w = ComposedIndustryScenario.openLoanLossTenCompanyStudy(journal)) {
            BankLoanLossDecisionTest.appointBanker(w);
            w.createBranch(child, w.snapshot().position(), BranchOrigin.Kind.GOD_TAKEOVER, "人工接管人物 erin");
            profile = w.profile();
        }
        var source = Files.readAllBytes(journal);
        var keys = dir.resolve("keys");
        Files.write(keys, List.of("sk-bank-fixture-001","sk-bank-fixture-002","sk-bank-fixture-003","sk-bank-fixture-004","sk-bank-fixture-005"));
        var token = new AtomicReference<>("loan-loss-http");
        try (var routes = SceneRoutes.open(dir);
             var host = new WorldWebHost(() -> new WorldWebHost.Opened(new BranchRepository(dir.resolve("runs")).open(child, profile), child));
             var server = new WorldWebServer(host, KeySlots.load(keys), routes, 0, token.get(), null, line -> {
                 if (line.contains("引导凭据")) token.set(line.substring(line.indexOf('：') + 1).trim());
             })) {
            server.start(); base = server.baseUrl();
            person(token.get(), "nina");
            assertFalse(own().has("lossExposures"));
            assertEquals(400, post(ACTION, request("forged", "LOAN_IMPAIR", "1.00")).statusCode());
            person(token.get(), "erin");
            assertEquals("working-capital", own().at("/lossExposures/0/loanId").asText());
            for (String kind : List.of("LOAN_IMPAIR", "LOAN_WRITE_OFF")) {
                String body = request(kind, kind, "10.00");
                var result = post(ACTION, body);
                assertEquals(200, result.statusCode(), result.body());
                long position = host.adminSnapshot().position();
                assertEquals(result.body(), post(ACTION, body).body());
                assertEquals(position, host.adminSnapshot().position());
                host.advance(position, JSON.readTree(result.body()).required("dueTimeMillis").asLong());
                assertTrue(own().required("actions").findValuesAsText("stage").contains("DONE"));
                assertEquals(result.body(), post(ACTION, body).body());
            }
            var finalRisk = own().at("/lossExposures/0");
            assertEquals("190.00", finalRisk.required("onbook").asText());
            assertEquals("10.00", finalRisk.required("writtenOff").asText());
            assertEquals("0.00", finalRisk.required("portfolioAllowance").asText());
            person(token.get(), "nina");
            var sameNumber = JSON.createObjectNode().put("kind", "DEPOSIT").put("commandId", "LOAN_IMPAIR")
                    .put("taskId", "my-deposit").put("amount", "1.00").put("workMillis", 1000);
            var response = post(ACTION, sameNumber.toString());
            assertEquals(200, response.statusCode(), response.body());
            host.advance(host.adminSnapshot().position(), JSON.readTree(response.body()).required("dueTimeMillis").asLong());
            var rejected = java.util.stream.StreamSupport.stream(own().required("actions").spliterator(), false)
                    .filter(a -> "COMMAND_ID_CONFLICT".equals(a.path("reason").asText())).findFirst().orElseThrow();
            assertFalse(rejected.required("accepted").booleanValue());
            assertEquals("", rejected.required("receipt").asText());
            assertEquals("nina", rejected.required("customerKey").asText());
            assertFalse(rejected.toString().contains("mfg-tech"));
            assertFalse(rejected.toString().contains("10.00"));
        } finally { cookie = null; }
        assertArrayEquals(source, Files.readAllBytes(journal));
    }
}
