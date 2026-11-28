package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchProfile;
import edu.cufe.auction.world.BranchRepository;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.composed.CompanyOpeningDocuments;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.web.WorldWebMediaHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

class RoleConditionalFieldsHttpTest {
    @TempDir Path dir;

    @Test void aBudgetPeriodMustBeExplicitInTheNewPersonalPage() throws Exception {
        for (boolean current : List.of(false, true)) exercise(current, true);
    }

    @Test void aGroupInvitationMustReceiveAnExplicitDecision() throws Exception {
        for (boolean current : List.of(false, true)) exercise(current, false);
    }

    private void exercise(boolean current, boolean budget) throws Exception {
        Path root = Files.createDirectory(dir.resolve(current ? "new" : "old"));
        Path journal = root.resolve("world.journal");
        String person = budget ? "alice" : "dave";
        String company = "eng-a", meeting = "meeting", child = "control-http";
        BranchProfile profile;
        try (var w = current ? ComposedIndustryScenario.openRoleArgumentsTenCompanyStudy(journal)
                : ComposedIndustryScenario.openAggregatorEvidenceTenCompanyStudy(journal)) {
            w.advanceTo(34_200_000L);
            if (budget) {
                String doc = CompanyOpeningDocuments.documentId(company);
                long at = w.snapshot().timeMillis() + 1;
                w.openRoleSession(person).startReading("read", "read", at, doc, 1);
                w.advanceTo(at);
                w.advanceTo(w.roleView(person).readings().get(0).dueAt());
                at = w.snapshot().timeMillis() + 2;
                w.scheduleEngineeringConvene("call", "call", at, company, person, meeting, at+500_000, at+600_000, at+900_000);
                w.scheduleEngineeringAttend("attend", "attend", at+3, company, person, meeting, false);
                w.scheduleEngineeringMaterial("attach", "attach", at+6, company, person, meeting, "material", doc, 1);
                w.advanceTo(at+10);
            } else {
                long due = w.snapshot().timeMillis() + WorldWebScenario.GROUPS_INVITE_MILLIS;
                w.openRoleSession("bob").inviteToGroup("invite", "invite", due,
                        new WorldRoleSession.GroupInviteDraft(WorldWebScenario.GROUPS_INVITE_ONLY, person));
                w.advanceTo(due);
            }
            w.createBranch(child, w.snapshot().position(), BranchOrigin.Kind.GOD_TAKEOVER, "人工接管人物 " + person);
            profile = w.profile();
        }
        byte[] parent = Files.readAllBytes(journal);
        Path keys = root.resolve("keys");
        Files.write(keys, List.of("sk-interview-fixture-001","sk-interview-fixture-002","sk-interview-fixture-003","sk-interview-fixture-004","sk-interview-fixture-005"));
        var bootstrap = new AtomicReference<>("local-role-fields");
        var repository = new BranchRepository(root.resolve("runs"));
        String page = budget ? "/api/me/engineering" : "/api/me/groups";
        try (var routes = SceneRoutes.open(root);
             var host = new WorldWebHost(() -> new WorldWebHost.Opened(repository.open(child, profile), child));
             var server = new WorldWebServer(host, KeySlots.load(keys), routes, 0, bootstrap.get(), null, line -> {
                 if (line.contains("引导凭据")) bootstrap.set(line.substring(line.indexOf('：')+1).trim());
             })) {
            server.start(); base = server.baseUrl(); cookie = null;
            var admin = post("/api/session", JSON.createObjectNode().put("bootstrap", bootstrap.get()).toString());
            assertEquals(200, admin.statusCode(), admin.body()); remember(admin);
            var role = post("/api/session/person", JSON.createObjectNode().put("personId", person).toString());
            assertEquals(200, role.statusCode(), role.body()); remember(role);
            var response = get(page);
            assertEquals(200, response.statusCode(), response.body());
            var view = JSON.readTree(response.body());
            ObjectNode body = JSON.createObjectNode().put("expectedPosition", view.required("observationToken").asText())
                    .put("commandId", "respond").put("taskId", "respond");
            long now = host.adminSnapshot().timeMillis();
            long due = now + (budget ? 2 : view.at("/actionMillis/RESPOND").asLong());
            body.put("dueTimeMillis", Long.toString(due));
            String field;
            if (budget) {
                field = "period";
                body.put("companyId", company).put("tool", "board.proposeBudget").put("meetingId", meeting)
                        .put("procurementLimit", "0.00");
            } else {
                field = "accept";
                if (current) {
                    long cut = host.adminSnapshot().position();
                    for (String verb : List.of("POST", "CORRECTION", "PLATFORM_POST", "PLATFORM_CORRECTION")) {
                        var invalid = body.deepCopy().put("action", verb).putNull("cites")
                                .put("dueTimeMillis", now + view.required("actionMillis").required(verb).asLong());
                        invalid.put("groupId", WorldWebScenario.GROUPS_OPEN).put("title", "标题").put("body", "正文")
                                .put("docId", "previous").put("basedOnVersion", 1);
                        var rejected = post(page+"/action", invalid.toString());
                        assertEquals(400, rejected.statusCode(), rejected.body());
                        assertTrue(rejected.body().contains("cites") && rejected.body().contains("数组"), rejected.body());
                        assertEquals(cut, host.adminSnapshot().position());
                    }
                }
                var invite = view.required("invitations").get(0);
                body.put("action", "RESPOND").put("inviteId", invite.required("inviteId").asText());
            }
            if (current) {
                long position = host.adminSnapshot().position();
                for (ObjectNode invalid : List.of(body, body.deepCopy().putNull(field),
                        budget ? body.deepCopy().put(field, false) : body.deepCopy().put(field, 2))) {
                    var rejected = post(page+"/action", invalid.toString());
                    assertEquals(400, rejected.statusCode(), rejected.body());
                    assertTrue(rejected.body().contains(field), rejected.body());
                    assertEquals(position, host.adminSnapshot().position());
                }
                if (budget) body.put(field, 0); else body.put(field, true);
            }
            var accepted = post(page+"/action", body.toString());
            assertEquals(200, accepted.statusCode(), accepted.body());
            assertTrue(JSON.readTree(accepted.body()).required("receipt").asText().startsWith("SCHEDULED"));
            host.advance(host.adminSnapshot().position(), due);
            if (!budget) {
                assertEquals(0, JSON.readTree(get(page).body()).required("invitations").size());
                for (int n = 0; n < 2; n++) {
                    var latest = JSON.readTree(get(page).body());
                    long publish = host.adminSnapshot().timeMillis() + latest.at("/actionMillis/PLATFORM_POST").asLong();
                    var manuscript = JSON.createObjectNode().put("action", "PLATFORM_POST")
                            .put("expectedPosition", latest.required("observationToken").asText())
                            .put("commandId", "post-"+n).put("taskId", "post-"+n).put("dueTimeMillis", publish)
                            .put("title", "无引文"+n).put("body", "自己的看法");
                    if (n == 1) manuscript.putArray("cites"); else if (!current) manuscript.putNull("cites");
                    var result = post(page+"/action", manuscript.toString());
                    assertEquals(200, result.statusCode(), result.body());
                    host.advance(host.adminSnapshot().position(), publish + WorldWebScenario.GROUPS_DELIVERY_MILLIS);
                }
            } else {
                assertEquals(current, view.has("roleToolArguments"));
            }
        } finally { cookie = null; }
        try (var reopened = repository.open(child, profile)) {
            if (budget) {
                var line = reopened.autonomyRoleObservation(person).at("/engineering/directorships/0/meetings/0/proposedLines/0");
                assertEquals("0.00", line.required("amount").asText());
                assertEquals(0, line.required("period").intValue());
            } else {
                var invitation = reopened.groupsRun().state().invitations().get(0);
                assertEquals(current ? "ACCEPTED" : "DECLINED", invitation.status().name());
            }
        }
        assertArrayEquals(parent, Files.readAllBytes(journal));
    }
}
