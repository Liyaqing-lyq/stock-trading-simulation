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

/** 时钟推进必须是原状态的纯时间转换，重签摘要不允许制造现金或待办。 */
class ClockFrameRecoveryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private Path clock(String name) throws Exception {
        Path file = directory.resolve(name);
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(file)) {
            runtime.advanceTo(50);
        }
        return file;
    }

    private void change(Path file, boolean snapshot, Consumer<ObjectNode> mutation) throws Exception {
        java.util.function.UnaryOperator<String> rewrite = json -> {
            try {
                ObjectNode value = (ObjectNode) mapper.readTree(json);
                mutation.accept(value);
                return mapper.writeValueAsString(value);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        };
        int index = JournalTamper.frames(file).size() - 1;
        if (snapshot) JournalTamper.rewriteSnapshot(file, index, rewrite);
        else JournalTamper.rewrite(file, index, rewrite);
        byte[] attacked = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> WorldWebScenario.groupsRuntime(file));
        assertArrayEquals(attacked, Files.readAllBytes(file), "拒绝不得修写完整的非法帧");
    }

    @Test void realClockCanResumeAndRunScheduledBusiness() throws Exception {
        Path file = clock("control");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(file)) {
            assertEquals(50, runtime.snapshot().timeMillis());
            runtime.scheduleRevokeAppointment("revoke", "revoke-task", 100, "A-1");
            runtime.advanceTo(101);
            assertTrue(runtime.roleView("alice").appointments().isEmpty());
        }
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(file)) {
            assertEquals(101, runtime.snapshot().timeMillis());
            assertTrue(runtime.roleView("alice").appointments().isEmpty());
        }
    }

    @Test void balancedCashShiftCannotHideInClock() throws Exception {
        change(clock("cash"), true, state -> {
            ObjectNode accounts = (ObjectNode) state.get("accounts");
            ObjectNode fund = (ObjectNode) accounts.get("ACC-FUND");
            ObjectNode company = (ObjectNode) accounts.get("ENG-A");
            fund.put("cash", "900.00");
            company.put("cash", "5100.00");
        });
    }

    @Test void clockCannotChangeTaskIdentityOrDeadline() throws Exception {
        for (String kind : new String[]{"used", "due", "remove"}) {
            change(clock(kind), true, state -> {
                if (kind.equals("used")) ((ArrayNode) state.get("usedTaskIds")).add("ghost");
                else if (kind.equals("remove")) ((ArrayNode) state.get("pending")).remove(0);
                else {
                    ObjectNode task = (ObjectNode) state.path("pending").get(0);
                    task.put("dueTimeMillis", task.path("dueTimeMillis").asLong() + 1);
                }
            });
        }
    }

    @Test void clockEnvelopeMustDescribeTheActualAdvance() throws Exception {
        for (String field : new String[]{"commandId", "commandContent", "result", "event"}) {
            change(clock(field), false, commit -> {
                if (field.equals("event")) ((ArrayNode) commit.get("events")).remove(0);
                else commit.put(field, "FORGED");
            });
        }
    }

    @Test void clockCannotMoveItsSnapshotAwayFromItsCommand() throws Exception {
        change(clock("time"), true, state -> state.put("timeMillis", 51));
    }
}
