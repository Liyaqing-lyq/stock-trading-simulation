package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.DividendModel;
import edu.cufe.auction.world.composed.CompanyOpeningDocuments;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class IssuerDividendHttpTest {
    @TempDir Path dir;
    private int sequence;
    private static final String ISSUER = "eng-a", MEETING = "annual-distribution";
    private static final String DIVIDEND = "/api/me/issuer-dividend", REGULAR = "/api/me/engineering/action";

    @Test void seatsReadAndVoteThroughThePersonalEndpoints() throws Exception {
        var archive = dir.resolve("web.journal");
        var keys = dir.resolve("fixture.keys");
        Files.writeString(keys, String.join("\n", java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> "sk-unusable-fixture-key-" + i).toList()));
        String chair;
        List<String> voters;
        long start;
        try (var world = ComposedIndustryScenario.openIssuerDividendOperating(archive)) {
            var board = world.boardOfCompany(ISSUER).orElseThrow().board().charter();
            chair = board.seats().stream().filter(s -> s.seatId().equals(board.chairSeatId())).findFirst().orElseThrow().partyId();
            voters = board.seats().stream().map(BoardModel.Seat::partyId).toList();
            start = world.snapshot().timeMillis();
        }
        try (var routes = SceneRoutes.open(dir);
             var host = new WorldWebHost(() -> {
                 var world = ComposedIndustryScenario.openIssuerDividendOperating(archive);
                 return new WorldWebHost.Opened(world, world.profile().manifest().runId());
             }, routes, new LiveDecisionProvider(slot -> { throw new AssertionError("未启动模型"); }));
             var server = new WorldWebServer(host, KeySlots.load(keys), routes, 0, "test-bootstrap", null, ignored -> {})) {
            server.enableLocalAccess();
            server.start();
            base = server.baseUrl();
            person("investor");
            var noSeat = command(start + 3000, "issuer.dividend.authorize");
            assertFalse(view().toString().contains("issuer.dividend"));
            postExpect(DIVIDEND, noSeat, 400);
            String doc = CompanyOpeningDocuments.documentId(ISSUER);
            for (var voter : voters) {
                person(voter);
                postExpect("/api/me/read", envelope(start + 5).put("docId", doc).put("version", "1"), 200);
            }
            advance(start + 2000);
            person(chair);
            assertTrue(view().toString().contains("issuer.dividend.convene"));
            var convene = command(start + 3000, "issuer.dividend.convene")
                    .put("materialDeadline", start + 6000).put("opensAt", start + 7000).put("closesAt", start + 10500);
            postExpect(DIVIDEND, convene.deepCopy().put("companyId", "mfg-tech"), 400);
            postExpect(DIVIDEND, convene.deepCopy().put("issuerOwnerId", "other"), 400);
            postExpect(DIVIDEND, convene, 200);
            postExpect(DIVIDEND, convene, 409);
            advance(start + 3000);
            String other = voters.stream().filter(p -> !chair.equals(p)).findFirst().orElseThrow();
            person(other);
            assertTrue(view().toString().contains("ELIGIBLE"));
            assertFalse(view().toString().contains("issuer.dividend.authorize"));
            postExpect(DIVIDEND, command(start + 3200, "issuer.dividend.authorize"), 400);
            for (var voter : voters) {
                person(voter);
                postExpect(REGULAR, command(start + 4000, "board.attend"), 200);
            }
            person(chair);
            postExpect(REGULAR, command(start + 4500, "board.addMaterial")
                    .put("materialId", "material").put("docId", doc).put("docVersion", "1"), 200);
            person(other);
            postExpect(DIVIDEND, command(start + 5000, "issuer.dividend.propose").put("schemeId", "web-scheme")
                    .put("perShare", "0.01").put("recordAt", start + 20000)
                    .put("exAt", start + 21000).put("payAt", start + 22000), 200);
            advance(start + 5500);
            for (var voter : voters) {
                person(voter);
                assertTrue(view().toString().contains("web-scheme"));
                postExpect(REGULAR, command(start + 9000, "board.vote").put("vote", "YES").put("durationMillis", "1000"), 200);
            }
            person(chair);
            postExpect(DIVIDEND, command(start + 11000, "issuer.dividend.authorize"), 200);
            advance(start + 22000);
        }
        try (var world = ComposedIndustryScenario.openIssuerDividendOperating(archive)) {
            assertEquals(DividendModel.Phase.SETTLED, world.snapshot().dividends().orElseThrow().schemes().get("web-scheme").phase());
            assertTrue(world.snapshot().decisions().orElseThrow().state().decisions().isEmpty());
        }
    }

    private void localAdmin() throws Exception {
        var session = post("/api/session/local", "{}");
        assertEquals(200, session.statusCode(), session.body());
        if (session.headers().firstValue("set-cookie").isPresent()) remember(session);
    }

    private void person(String id) throws Exception {
        localAdmin();
        var login = post("/api/session/person", JSON.createObjectNode().put("personId", id).toString());
        assertEquals(200, login.statusCode(), login.body());
        remember(login);
    }

    private JsonNode view() throws Exception {
        var response = get("/api/me/engineering");
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private ObjectNode envelope(long due) throws Exception {
        sequence++;
        return JSON.createObjectNode().put("expectedPosition", view().required("position").asText())
                .put("commandId", "web-" + sequence).put("taskId", "task-" + sequence).put("dueTimeMillis", due);
    }

    private ObjectNode command(long due, String tool) throws Exception {
        return envelope(due).put("companyId", ISSUER).put("meetingId", MEETING).put("tool", tool);
    }

    private void advance(long target) throws Exception {
        localAdmin();
        var position = JSON.readTree(get("/api/admin/operations").body()).required("position").asText();
        postExpect("/api/admin/advance", JSON.createObjectNode().put("expectedPosition", position).put("targetMillis", target), 200);
    }

    private void postExpect(String path, ObjectNode form, int expected) throws Exception {
        var response = post(path, form.toString());
        assertEquals(expected, response.statusCode(), path + " " + form + " " + response.body());
    }
}
