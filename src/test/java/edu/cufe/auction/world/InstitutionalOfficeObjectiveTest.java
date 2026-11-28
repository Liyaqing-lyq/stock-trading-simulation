package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InstitutionalOfficeObjectiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    @Test void incumbentCanNameOwnOfficeOrBoardSeatWithoutGainingTradingTools() throws Exception {
        try (var previous = ComposedIndustryScenario.openCompanyInformationOperating(dir.resolve("previous"));
             var next = ComposedIndustryScenario.openForwardBudgetOperating(dir.resolve("next"))) {
            previous.advanceTo(1);
            next.advanceTo(1);
            var offices = Map.of("alice", "RESEARCHER", "bob", "PORTFOLIO_MANAGER",
                    "carol", "RISK_OFFICER", "dave", "EXECUTION_TRADER");
            for (var entry : offices.entrySet()) {
                JsonNode oldInput = previous.autonomyRoleObservation(entry.getKey());
                JsonNode newInput = next.autonomyRoleObservation(entry.getKey());
                JsonNode oldGoals = oldInput.at("/engineering/deliberation/objectives");
                JsonNode newGoals = newInput.at("/engineering/deliberation/objectives");
                assertEquals(2, oldGoals.size());
                assertEquals(3, newGoals.size());
                assertEquals(oldGoals.get(0), newGoals.get(0));
                assertEquals(oldGoals.get(1), newGoals.get(1));
                JsonNode office = newGoals.get(2);
                String id = newInput.at("/appointments/0/appointmentId").textValue();
                assertEquals("appointment:" + id, office.path("id").textValue());
                assertEquals(entry.getValue(), office.path("role").textValue());
                assertEquals("cufe", office.path("organizationPartyId").textValue());
                assertEquals("fund-a", office.at("/productOwnerIds/0").textValue());
                assertEquals(oldInput.path("tools"), newInput.path("tools"));
                assertEquals(oldInput.path("permissions"), newInput.path("permissions"));
                var wait = JSON.createObjectNode().put("tool", "role.wait");
                var decision = wait.putObject("decision");
                decision.put("objective", office.path("id").textValue()).put("basis", "核对可用资料。")
                        .put("reviewAfterMillis", 1000);
                assertFalse(RoleDeliberation.validatedAction(newInput.toString(), wait).has("decision"));
                assertThrows(IllegalArgumentException.class,
                        () -> RoleDeliberation.validatedAction(oldInput.toString(), wait));
                decision.put("objective", "appointment:foreign");
                assertThrows(IllegalArgumentException.class,
                        () -> RoleDeliberation.validatedAction(newInput.toString(), wait));
            }
        }
    }

    @Test void officeRequiresOwnActiveTermAndAnOrganizationalProduct() throws Exception {
        try (var world = ComposedIndustryScenario.openForwardBudgetOperating(dir.resolve("scope"))) {
            var access = world.snapshot().roles().orElseThrow().access();
            var office = access.appointments().stream().filter(a -> a.personPartyId().equals("alice")).findFirst().orElseThrow();
            var revoked = new RoleAccess.Appointment(office.id(), "alice", office.organizationPartyId(),
                    office.role(), 2, 10, 7L);
            var constrained = new RoleAccess.State(access.accounts(), access.personAccounts(), access.mandates(), List.of(revoked));
            assertEquals(1, goals(constrained, "alice", 2).size());
            assertTrue(goals(constrained, "alice", 1).isEmpty());
            assertTrue(goals(constrained, "alice", 7).isEmpty());
            assertTrue(goals(constrained, "bob", 2).isEmpty());
            var noMandate = new RoleAccess.State(access.accounts(), access.personAccounts(), List.of(), List.of(revoked));
            assertTrue(goals(noMandate, "alice", 2).isEmpty());
        }
    }

    private static JsonNode goals(RoleAccess.State access, String person, long at) {
        ObjectNode engineering = JSON.createObjectNode();
        engineering.putObject("deliberation").putArray("objectives");
        RoleDeliberation.appendInvestmentObjectives(engineering, access, person, at);
        return engineering.at("/deliberation/objectives");
    }
}
