package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoleDeliberationReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void onlyTheNewWaitContractAcceptsAnEmptyParameterObject() throws Exception {
        String old = """
                {"time":10,"engineering":{"roleContextVersion":"deliberation-v4",
                "deliberation":{"objectives":[{"id":"person:investor"}]}}}
                """;
        String current = old.replace("\"deliberation\":", "\"waitArgsPolicy\":\"allow-empty-object-v1\",\"deliberation\":");
        var reply = JSON.createObjectNode().put("tool", "role.wait");
        reply.putObject("decision").put("objective", "person:investor").put("basis", "等复尺确认").put("reviewAfterMillis", 86400000);
        reply.putObject("args");
        assertTrue(RoleDeliberation.validatedAction(old, reply).has("args"));
        assertEquals(JSON.readTree("{\"tool\":\"role.wait\"}"), RoleDeliberation.validatedAction(current, reply));
        assertTrue(reply.has("args"), "原回执保留空对象");
        reply.withObject("/args").put("quantity", 1);
        assertTrue(RoleDeliberation.validatedAction(current, reply).has("args"));
        reply.putObject("args");
        reply.put("tool", "role.library.search");
        assertTrue(RoleDeliberation.validatedAction(current, reply).has("args"));
    }

    @Test void sameFrozenInputStillChecksEachReplyAndDifferentInputsWithTheSameHashKeepTheirOwnObjectives()
            throws Exception {
        String first = """
                {"time":10,"engineering":{"roleContextVersion":"deliberation-v2",
                "deliberation":{"objectives":[{"id":"Aa"}]}}}
                """;
        String second = first.replace("Aa", "BB");
        assertEquals(first.hashCode(), second.hashCode());
        var reply = JSON.createObjectNode().put("tool", "role.wait");
        var declaration = reply.putObject("decision").put("objective", "Aa")
                .put("basis", "等待交付").put("nextReviewAt", 11);

        try (var scope = new RoleDeliberation.ReplayInputs()) {
            assertEquals("role.wait", RoleDeliberation.validatedAction(first, reply).required("tool").asText());
            assertFalse(RoleDeliberation.validatedAction(first, reply).has("decision"));
            assertThrows(IllegalArgumentException.class, () -> RoleDeliberation.validatedAction(second, reply));
            declaration.put("objective", "BB");
            assertThrows(IllegalArgumentException.class, () -> RoleDeliberation.validatedAction(first, reply));
            assertDoesNotThrow(() -> RoleDeliberation.validatedAction(second, reply));
            declaration.put("nextReviewAt", 10);
            assertThrows(IllegalArgumentException.class, () -> RoleDeliberation.validatedAction(second, reply));
            assertSame(reply, RoleDeliberation.validatedAction("{}", reply));
        }
    }

    @Test void boundedScopeDropsPrivateInputsAndValidatesBeyondItsLimits() throws Exception {
        var inputs = RoleDeliberation.ReplayInputs.class.getDeclaredField("inputs");
        var characters = RoleDeliberation.ReplayInputs.class.getDeclaredField("characters");
        inputs.setAccessible(true);
        characters.setAccessible(true);
        var scope = new RoleDeliberation.ReplayInputs();
        var stored = (java.util.Map<?, ?>) inputs.get(scope);
        String base = """
                {"time":10,"engineering":{"roleContextVersion":"deliberation-v2",
                "deliberation":{"objectives":[{"id":"person"}]}},"padding":"%s","serial":%d}
                """;
        var reply = JSON.createObjectNode().put("tool", "role.wait");
        var declaration = reply.putObject("decision").put("objective", "person").put("basis", "等候")
                .put("nextReviewAt", 11);
        try (scope) {
            String padding = "x".repeat(59_000);
            for (int i = 0; i < 625; i++) {
                RoleDeliberation.validatedAction(base.formatted(padding, i), reply);
            }
            int charged = characters.getInt(scope);
            assertTrue(charged > 32 * 1024 * 1024 - 60_000 && charged <= 32 * 1024 * 1024);
            String over = base.formatted(padding, 625);
            assertFalse(stored.containsKey(over));
            assertDoesNotThrow(() -> RoleDeliberation.validatedAction(over, reply));
            declaration.put("objective", "other");
            assertThrows(IllegalArgumentException.class, () -> RoleDeliberation.validatedAction(over, reply));
            assertEquals(charged, characters.getInt(scope));
        }
        assertTrue(stored.isEmpty());
        assertEquals(0, characters.getInt(scope));
    }
}
