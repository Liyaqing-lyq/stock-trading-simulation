package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 未知命令默认拒绝：只有能回到既有入口且来源键真实存在的帧才允许持久化。 */
class CommonFrameOriginTest {

    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();

    private Path clock(String name) throws Exception {
        Path file = directory.resolve(name);
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(file)) {
            runtime.scheduleCapitalFlow("tr1", "t-tr1", 100L, "fund-a", "ACC-FUND", "ENG-A",
                    new BigDecimal("10.00"), 0L, "test-capital");
            runtime.advanceTo(50);
        }
        return file;
    }

    private void rewriteCommit(Path file, Consumer<ObjectNode> mutation) throws Exception {
        int index = JournalTamper.frames(file).size() - 1;
        JournalTamper.rewrite(file, index, json -> {
            try {
                ObjectNode commit = (ObjectNode) mapper.readTree(json);
                mutation.accept(commit);
                return mapper.writeValueAsString(commit);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private void assertRejected(Path file) throws Exception {
        byte[] attacked = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> WorldWebScenario.groupsRuntime(file));
        assertArrayEquals(attacked, Files.readAllBytes(file), "拒绝不得修写完整的非法帧");
    }

    @Test
    void unknownCommandCannotPersistEvenWithoutStateChange() throws Exception {
        Path file = clock("unknown-noop");
        rewriteCommit(file, commit -> {
            commit.put("commandId", "foo:1");
            ((ArrayNode) commit.get("events")).remove(0);
        });
        assertRejected(file);
    }

    @Test
    void unknownCommandCannotBorrowKnownFamilyPrefix() throws Exception {
        for (String forged : new String[]{"cmd:ghost", "task:ghost", "barrier:50",
                "roleT:5:alice:ghost", "div-batch:ghost", "div-record:X", "dec-reply:ghost",
                "dec-fail:ATT-9", "clock-x:50", "__open__"}) {
            Path file = clock("unknown-" + Integer.toHexString(forged.hashCode()));
            rewriteCommit(file, commit -> {
                commit.put("commandId", forged);
                ((ArrayNode) commit.get("events")).remove(0);
            });
            byte[] attacked = Files.readAllBytes(file);
            assertThrows(IOException.class, () -> WorldWebScenario.groupsRuntime(file), forged);
            assertArrayEquals(attacked, Files.readAllBytes(file), "拒绝不得修写： " + forged);
        }
    }

    @Test
    void realBarrierFrameCanResume() throws Exception {
        Path file = directory.resolve("barrier");
        var opened = WorldWebScenario.openCognition(file);
        try (WorldRuntime runtime = opened.runtime()) {
            runtime.beginDecisionSession("s1");
            runtime.openDecisions("o1", List.of(new DecisionRun.Request("alice", "act-1", "wake",
                    new DecisionModel.Route("governance", "key-1", "deepseek-flash",
                            1L, 5, 5_000, 1_000, 1))));
            runtime.advanceTo(2_000L);
        }
        try (WorldRuntime runtime = WorldWebScenario.openCognition(file).runtime()) {
            assertEquals(1_000L, runtime.snapshot().timeMillis());
            assertEquals("COMPLETION", runtime.snapshot().lastPhase());
        }
    }

    @Test
    void barrierCannotJumpOrSmuggleCash() throws Exception {
        Path base = directory.resolve("barrier-base");
        var opened = WorldWebScenario.openCognition(base);
        try (WorldRuntime runtime = opened.runtime()) {
            runtime.beginDecisionSession("s1");
            runtime.openDecisions("o1", List.of(new DecisionRun.Request("alice", "act-1", "wake",
                    new DecisionModel.Route("governance", "key-1", "deepseek-flash",
                            1L, 5, 5_000, 1_000, 1))));
            runtime.advanceTo(2_000L);
        }
        for (String kind : new String[]{"jump", "cash"}) {
            Path file = directory.resolve("barrier-" + kind);
            Files.copy(base, file);
            int index = JournalTamper.frames(file).size() - 1;
            if (kind.equals("jump")) {
                JournalTamper.rewrite(file, index, json -> {
                    try {
                        ObjectNode commit = (ObjectNode) mapper.readTree(json);
                        commit.put("commandId", "barrier:1010");
                        ObjectNode event = (ObjectNode) ((ArrayNode) commit.get("events")).get(0);
                        event.put("eventId", "barrier:1010");
                        event.put("payload", "{\"timeMillis\":1010}");
                        return mapper.writeValueAsString(commit);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
            } else {
                JournalTamper.rewriteSnapshot(file, index, inner -> inner
                        .replace("\"ACC-FUND\":{\"ownerId\":\"fund-a\",\"cash\":1000.00",
                                "\"ACC-FUND\":{\"ownerId\":\"fund-a\",\"cash\":900.00")
                        .replace("\"CO\":{\"ownerId\":\"company\",\"cash\":1000.00",
                                "\"CO\":{\"ownerId\":\"company\",\"cash\":1100.00"));
            }
            assertRejected(file);
        }
    }
}
