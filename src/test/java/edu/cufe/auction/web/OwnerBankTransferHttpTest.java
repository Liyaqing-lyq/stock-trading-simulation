package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class OwnerBankTransferHttpTest {
    @TempDir Path dir;
    private final AtomicReference<String> bootstrap = new AtomicReference<>("owner-transfer-bootstrap");
    private int command;

    @Test void onlyAuthorizedPersonAndProductMayMoveMoneyInAndOutOfTheirCustody() throws Exception {
        Path journal = dir.resolve("world.journal");
        Path draft = Path.of("src/main/resources/world/opening-copies-example.json");
        long revoke;
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(journal, draft)) {
            revoke = w.snapshot().timeMillis() + 120;
            w.scheduleRevokeAppointment("withdraw-mandate", "withdraw-mandate-task", revoke, "A-2");
        }
        Path keys = dir.resolve("test-keys");
        Files.write(keys, List.of("sk-owner-bank-fixture-001", "sk-owner-bank-fixture-002", "sk-owner-bank-fixture-003",
                "sk-owner-bank-fixture-004", "sk-owner-bank-fixture-005"));
        try (var routes = SceneRoutes.open(dir);
             var host = new WorldWebHost(() -> new WorldWebHost.Opened(
                     ComposedIndustryScenario.openOwnerBankValuationOperating(journal, null), RunManifest.OWNER_BANK_VALUATION_RUN_ID));
             var server = new WorldWebServer(host, KeySlots.load(keys), routes, 0, bootstrap.get(), null, line -> {
                 if (line.contains("引导凭据")) bootstrap.set(line.substring(line.indexOf('：') + 1).trim());
             })) {
            server.start();
            base = server.baseUrl();
            try {
                person("investor");
                String personal = "CUSTODY|cst|investor", fund = "CUSTODY|cst|fund-a";
                assertTrue(bank().at("/options/custody").toString().contains(personal));
                assertFalse(bank().path("options").toString().contains(fund));
                advance(host, action("", "PAY", "CASH|INV", personal, "80", 2, 200));
                balance("investor", "4920.00", "0.00", "80.00");
                advance(host, action("", "PAY", personal, "DEPOSIT|north|investor", "30", 2, 200));
                balance("investor", "4920.00", "30.00", "50.00");
                action("", "PAY", personal, fund, "1", 2, 400);
                action("", "PAY", fund, personal, "1", 2, 400);

                person("bob");
                var subject = bank().at("/options/subjects");
                assertTrue(subject.toString().contains("fund-a"));
                assertTrue(java.util.stream.StreamSupport.stream(subject.spliterator(), false)
                        .anyMatch(row -> row.path("token").asText().equals("fund-a") && row.path("internalOnly").asBoolean()));
                assertTrue(bank().at("/options/payees").toString().contains(fund));
                advance(host, action("fund-a", "DEPOSIT", "CASH|ACC-FUND", "", "120", 2, 200));
                advance(host, action("fund-a", "PAY", "DEPOSIT|north|fund-a", fund, "70", 2, 200));
                balance("fund-a", "9880.00", "50.00", "70.00");
                advance(host, action("fund-a", "PAY", fund, "CASH|ACC-FUND", "25", 2, 200));
                balance("fund-a", "9905.00", "50.00", "45.00");
                action("", "PAY", fund, "CASH|ACC-FUND", "1", 2, 400);
                action("fund-a", "PAY", fund, personal, "1", 2, 400);

                for (String other : List.of("alice", "carol", "dave")) {
                    person(other);
                    assertFalse(bank().at("/options/subjects").toString().contains("fund-a"));
                    assertFalse(bank().at("/options/custody").toString().contains(fund));
                    action("fund-a", "PAY", fund, "CASH|ACC-FUND", "1", 2, 400);
                }
                person("bob");
                host.advance(host.adminSnapshot().position(), revoke - 5);
                advance(host, action("fund-a", "PAY", fund, "CASH|ACC-FUND", "1", 10, 200));
                assertFalse(bank().path("options").toString().contains(fund));
                action("fund-a", "PAY", fund, "CASH|ACC-FUND", "1", 2, 400);
            } finally {
                cookie = null;
            }
        }
        try (var w = ComposedIndustryScenario.openOwnerBankValuationOperating(journal, null)) {
            assertEquals("45.00", w.valuation("fund-a").bankClaims().custodyBeneficial().toPlainString());
            assertEquals("50.00", w.valuation("investor").bankClaims().custodyBeneficial().toPlainString());
            assertEquals("15750.00", w.valuation("fund-a").netWorth().toPlainString());
            assertEquals("11350.00", w.valuation("investor").netWorth().toPlainString());
        }
    }

    private void person(String id) throws Exception {
        cookie = null;
        var admin = post("/api/session", JSON.createObjectNode().put("bootstrap", bootstrap.get()).toString());
        assertEquals(200, admin.statusCode(), admin.body());
        remember(admin);
        var role = post("/api/session/person", JSON.createObjectNode().put("personId", id).toString());
        assertEquals(200, role.statusCode(), role.body());
        remember(role);
    }

    private JsonNode bank() throws Exception {
        var response = get("/api/me/bank");
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private void balance(String owner, String cash, String deposits, String custody) throws Exception {
        for (var row : bank().path("owners")) if (row.path("ownerId").asText().equals(owner)) {
            assertEquals(cash, row.path("cash").asText());
            assertEquals(deposits, row.path("deposits").asText());
            assertEquals(custody, row.path("beneficial").asText());
            return;
        }
        fail("本人页面未显示已授权属主 " + owner);
    }

    private long action(String subject, String kind, String source, String payee, String amount, long work, int status) throws Exception {
        String id = "move-" + (++command);
        var body = JSON.createObjectNode().put("commandId", id).put("taskId", id + "-task").put("kind", kind)
                .put("workMillis", work).put("amount", amount).put("sourceToken", source);
        if (!subject.isEmpty()) body.put("organizationPartyId", subject);
        if (!payee.isEmpty()) body.put("payeeToken", payee);
        var response = post("/api/me/bank/action", body.toString());
        assertEquals(status, response.statusCode(), response.body());
        return JSON.readTree(response.body()).path("dueTimeMillis").asLong();
    }

    private static void advance(WorldWebHost host, long due) throws Exception {
        host.advance(host.adminSnapshot().position(), due);
    }
}
