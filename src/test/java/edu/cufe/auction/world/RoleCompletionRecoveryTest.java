package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 真实撤任完成帧须由原任务重算，重签摘要不能恢复权限或夹带其它变化。 */
class RoleCompletionRecoveryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private Path revoked(String name) throws Exception {
        Path file = directory.resolve(name);
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(file)) {
            runtime.scheduleRevokeAppointment("revoke", "revoke-task", 100, "A-1");
            runtime.advanceTo(100);
        }
        return file;
    }

    private void mutate(Path file, Consumer<ObjectNode> change) throws Exception {
        int index = JournalTamper.frames(file).size() - 1;
        JournalTamper.rewriteSnapshot(file, index, json -> {
            try {
                ObjectNode state = (ObjectNode) mapper.readTree(json);
                change.accept(state);
                return mapper.writeValueAsString(state);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    @Test void realRevokeAndRejectedDuplicateSurviveRecovery() throws Exception {
        Path file = revoked("control");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(file)) {
            assertTrue(runtime.roleView("alice").appointments().isEmpty());
            assertFalse(runtime.openRoleSession("alice").view().tools().contains("role.research.submit"));
            runtime.scheduleRevokeAppointment("again", "again-task", 200, "A-1");
            runtime.advanceTo(200);
        }
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(file)) {
            assertTrue(runtime.roleView("alice").appointments().isEmpty());
            assertEquals(200, runtime.snapshot().timeMillis());
        }
    }

    @Test void firstRevocationCannotBeErasedOrMoved() throws Exception {
        for (boolean erase : new boolean[]{true, false}) {
            Path file = revoked("revocation-" + erase);
            mutate(file, state -> {
                ObjectNode appointment = (ObjectNode) state.path("roles").path("access")
                        .path("appointments").get(0);
                if (erase) appointment.putNull("revokedAt");
                else appointment.put("revokedAt", 99);
            });
            byte[] attacked = Files.readAllBytes(file);
            assertThrows(IOException.class, () -> WorldWebScenario.groupsRuntime(file));
            assertArrayEquals(attacked, Files.readAllBytes(file));
        }
    }

    @Test void completionCannotHideOtherStateChanges() throws Exception {
        for (String field : new String[]{"usedTaskIds", "timeMillis", "commandOutcomes"}) {
            Path file = revoked("metadata-" + field);
            mutate(file, state -> {
                switch (field) {
                    case "usedTaskIds" -> ((ArrayNode) state.get(field)).add("ghost-task");
                    case "timeMillis" -> state.put(field, 101);
                    case "commandOutcomes" -> ((ObjectNode) state.get(field)).put("ghost-command", "OK");
                    default -> throw new AssertionError(field);
                }
            });
            assertThrows(IOException.class, () -> WorldWebScenario.groupsRuntime(file), field);
        }
    }

    @Test void ordinaryClockCannotRevokeSomeone() throws Exception {
        Path file = directory.resolve("clock");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(file)) {
            runtime.advanceTo(100);
        }
        mutate(file, state -> ((ObjectNode) state.path("roles").path("access")
                .path("appointments").get(0)).put("revokedAt", 100));
        assertThrows(IOException.class, () -> WorldWebScenario.groupsRuntime(file));
    }
}
