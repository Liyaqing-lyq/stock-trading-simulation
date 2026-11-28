package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersonalOpeningProfilesTest {
    @TempDir Path dir;
    private static final long DAY = 86_400_000L;

    private static JsonNode goal(WorldRuntime world, String who, String id) throws Exception {
        for (var goal : world.autonomyRoleObservation(who).at("/engineering/deliberation/objectives"))
            if (id.equals(goal.path("id").textValue())) return goal;
        return null;
    }

    private static JsonNode plan(WorldRuntime world, String who) throws Exception {
        var goal = goal(world, who, "person:" + who);
        assertNotNull(goal, who);
        return goal.path("personalPlan");
    }

    @Test void threePrivateStatementsHaveDifferentCashAndTimeConstraintsBackedByTheirOwnBooks() throws Exception {
        Path file = dir.resolve("profiles.journal");
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(file)) {
            var versions = world.profile().manifest().dataVersions();
            assertEquals("household-plans-v1", versions.get("personal-opening-profiles"));
            assertEquals("44159300e893117be16e5109bd7f281a24efb143b842914d37db6ffe92105d3c",
                    versions.get("personal-opening-profiles-sha256"));
            var own = Map.of("investor", "INV", "nina", "HOUSE-NINA", "omar", "HOUSE-OMAR");
            var wanted = Map.of("investor", "4400.00", "nina", "3400.00", "omar", "1200.00");
            var days = Map.of("investor", 14, "nina", 2, "omar", 45);
            var motives = new HashSet<String>();
            var experiences = new HashSet<String>();
            for (var who : own.keySet()) {
                var input = world.autonomyRoleObservation(who);
                var item = goal(world, who, "person:" + who);
                var detail = item.path("personalPlan");
                assertTrue(motives.add(item.path("purpose").asText()));
                assertTrue(experiences.add(detail.path("initialExperience").asText()));
                assertEquals("household-plans-v1/" + who, detail.path("sourceId").textValue());
                assertEquals(wanted.get(who), detail.path("targetLiquidCash").textValue());
                assertEquals(days.get(who) * DAY, detail.path("reviewAt").longValue());
                assertFalse(detail.path("reviewDue").booleanValue());
                assertEquals(1, detail.path("ownedAccounts").size());
                assertEquals(own.get(who), detail.at("/ownedAccounts/0/accountId").asText());
                var account = world.snapshot().accounts().get(own.get(who));
                assertEquals(account.source(), detail.at("/ownedAccounts/0/openingSource").asText());
                assertEquals(account.cash(), new BigDecimal(detail.at("/ownedAccounts/0/openingCash").asText()));
                assertEquals(account.cash(), new BigDecimal(detail.path("availableCash").asText()));
                assertTrue(input.at("/profile/holdings").size() > 0);
                for (var holding : input.at("/profile/holdings"))
                    assertEquals(own.get(who), holding.path("accountId").textValue());
                for (var other : own.keySet())
                    if (!who.equals(other)) assertFalse(input.toString().contains("household-plans-v1/" + other));
            }
            assertEquals("400.00", plan(world, "nina").path("cashShortfall").asText());
            assertTrue(plan(world, "dave").path("availableCash").isMissingNode());
        }
        assertThrows(Exception.class, () -> ComposedIndustryScenario.openContinuingRolesOperating(file));
        try (var old = ComposedIndustryScenario.openContinuingRolesOperating(dir.resolve("old.journal"))) {
            assertFalse(old.profile().manifest().dataVersions().containsKey("personal-opening-profiles"));
            assertTrue(plan(old, "nina").isMissingNode());
        }
    }

    @Test void allResidentsHaveDistinctPrivateProfilesAndTheSystemPublisherHasNone() throws Exception {
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(dir.resolve("roster.journal"))) {
            var experiences = new HashSet<String>();
            var concerns = new HashSet<String>();
            var conditions = new HashSet<String>();
            var questions = new HashSet<String>();
            int withAccount = 0;
            for (var party : world.profile().registry().parties()) {
                if (party.kind() != OpeningRegistry.PartyKind.PERSON) continue;
                String who = party.partyId();
                var input = world.autonomyRoleObservation(who);
                var personalGoals = new java.util.ArrayList<JsonNode>();
                for (var entry : input.at("/engineering/deliberation/objectives"))
                    if (entry.has("personalPlan")) personalGoals.add(entry);
                if (WorldWebScenario.RESEARCH_SYSTEM_PUBLISHER.equals(who)) {
                    assertTrue(personalGoals.isEmpty());
                    continue;
                }
                assertEquals(1, personalGoals.size(), who);
                var own = personalGoals.get(0);
                assertEquals("person:" + who, own.path("id").asText());
                var detail = own.path("personalPlan");
                assertEquals("household-plans-v1/" + who, detail.path("sourceId").asText());
                assertFalse(own.path("purpose").asText().isBlank(), who);
                assertFalse(detail.path("initialExperience").asText().isBlank(), who);
                assertFalse(detail.path("reviewCondition").asText().isBlank(), who);
                assertTrue(concerns.add(own.path("purpose").textValue()), who);
                assertTrue(experiences.add(detail.path("initialExperience").textValue()), who);
                assertFalse(detail.path("worldQuestion").asText().isBlank(), who);
                assertTrue(questions.add(detail.path("worldQuestion").textValue()), who);
                assertTrue(conditions.add(detail.path("reviewCondition").textValue()), who);
                assertFalse(own.path("tradeoff").asText().isBlank(), who);
                assertTrue(world.autonomyInputLimitViolation(who).isEmpty(), who);
                var accounts = world.snapshot().roles().orElseThrow().access().personAccounts();
                boolean funded = accounts.stream().anyMatch(a -> who.equals(a.personPartyId()));
                if (funded) withAccount++;
                assertEquals(funded, detail.has("targetLiquidCash"), who);
                assertEquals(funded, detail.has("availableCash"), who);
                assertEquals(funded, detail.has("cashShortfall"), who);
                assertEquals(funded, detail.has("ownedAccounts"), who);
            }
            assertEquals(61, experiences.size());
            assertEquals(3, withAccount);
        }
    }

    @Test void personalIdentitySurvivesLossOfOfficeAlongsideTwoIndependentDirectorships() throws Exception {
        Path file = dir.resolve("term.journal");
        JsonNode original;
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(file)) {
            original = plan(world, "alice");
            assertNotNull(goal(world, "alice", "director:north"));
            assertNotNull(goal(world, "alice", "director:eng-a"));
            assertNotNull(goal(world, "alice", "appointment:A-1"));
            assertFalse(original.has("availableCash"));
            long at = world.snapshot().timeMillis() + 1;
            world.scheduleRevokeAppointment("end-research", "end-research", at, "A-1");
            world.advanceTo(at);
            assertNull(goal(world, "alice", "appointment:A-1"));
            assertNotNull(goal(world, "alice", "director:north"));
            assertNotNull(goal(world, "alice", "director:eng-a"));
            assertEquals(original, plan(world, "alice"));
        }
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(file)) {
            assertEquals(original, plan(world, "alice"));
            assertNull(goal(world, "alice", "appointment:A-1"));
        }
    }

    @Test void frozenOrderAndReleaseUpdateThePersonalShortfallEvenWhenTheHolderHasAnOffice() throws Exception {
        Path path = dir.resolve("cash.journal");
        long id;
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(path)) {
            long now = world.snapshot().timeMillis() + 1;
            var previous = world.snapshot().roles().orElseThrow().access().appointments().stream()
                    .filter(a -> a.organizationPartyId().equals("cufe") && a.role() == RoleAccess.Role.RESEARCHER && a.activeAt(now))
                    .findFirst().orElseThrow();
            world.scheduleRevokeAppointment("leave", "leave", now, previous.id());
            world.advanceTo(now);
            long hireAt = now + 1;
            world.scheduleAppointment("hire", "hire", hireAt, "second-office", "nina", "cufe",
                    RoleAccess.Role.RESEARCHER, hireAt, hireAt + 3 * DAY);
            world.advanceTo(hireAt);
            var office = goal(world, "nina", "appointment:second-office");
            assertNotNull(office);
            assertTrue(office.path("productOwnerIds").size() > 0);
            var personal = plan(world, "nina");
            assertEquals(1, personal.path("ownedAccounts").size());
            String symbol = world.profile().registry().securities().get(0).symbol();
            world.scheduleOrder("bid", "bid", hireAt + 1, "nina", "HOUSE-NINA", symbol,
                    Side.BUY, OrderType.LIMIT, new BigDecimal("10.00"), 10);
            world.advanceTo(hireAt + 1);
            assertEquals("2900.00", plan(world, "nina").path("availableCash").textValue());
            assertEquals("500.00", plan(world, "nina").path("cashShortfall").textValue());
            assertEquals(new BigDecimal("3000.00"), world.snapshot().accounts().get("HOUSE-NINA").cash());
            id = world.snapshot().exchange().orElseThrow().orders().get(0).orderId();
        }
        try (var world = ComposedIndustryScenario.openPersonalProfilesOperating(path)) {
            assertEquals("500.00", plan(world, "nina").path("cashShortfall").textValue());
            long at = world.snapshot().timeMillis() + 1;
            world.scheduleCancelOrder("cancel", "cancel", at, "nina", id);
            world.advanceTo(at);
            assertEquals("400.00", plan(world, "nina").path("cashShortfall").textValue());
            world.advanceTo(2 * DAY);
            assertTrue(plan(world, "nina").path("reviewDue").booleanValue());
            assertFalse(plan(world, "omar").path("reviewDue").booleanValue());
            assertEquals("3000.00", plan(world, "nina").path("availableCash").textValue());
        }
    }
}
