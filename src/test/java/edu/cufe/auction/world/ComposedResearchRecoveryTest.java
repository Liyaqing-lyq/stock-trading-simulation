package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.composed.ComposedResearchScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 新组合档自身的跨进程恢复、真实分支与原命令篡改边界。 */
class ComposedResearchRecoveryTest {
    @TempDir Path dir;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void threeIndependentProcessesResumeAndBranch(boolean funded) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (int step = 0; step < 3; step++) {
            Path log = dir.resolve("step-" + step + ".log");
            Process process = new ProcessBuilder(java, "-Xmx768m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", cp,
                    ComposedResearchReopenProbe.class.getName(), dir.toString(), Integer.toString(step), Boolean.toString(funded))
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean finished = process.waitFor(90, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            assertTrue(finished, "独立进程超时：" + step);
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("RESEARCH_COMPOSED_JVM_OK step=" + step));
        }
    }

    @Test
    void startedResearchStillOccupiesThePersonUntilFinish() throws Exception {
        try (WorldRuntime world = ComposedResearchScenario.open(dir.resolve("focus.journal"))) {
            ResearchCommercialWorldTest.buildCompletedProject(world);
            long finish = world.snapshot().timeMillis() + 2_000;
            world.openRoleSession("dave").registerResearch("first", "first-task", finish,
                    "eng-a", "p7", 1_000);
            world.advanceTo(finish - 500);
            assertThrows(IllegalArgumentException.class, () -> world.openRoleSession("dave")
                    .registerResearch("overlap", "overlap-task", finish + 100,
                            "eng-a", "p2", 599), "BEGIN完成后，FINISH待办仍应占据同一人物工时");
            world.advanceTo(finish);
            world.openRoleSession("dave").registerResearch("after", "after-task", finish + 1_001,
                    "eng-a", "p2", 1_000);
            world.advanceTo(finish + 1_001);
            assertTrue(world.researchRun().orElseThrow().state().projects().containsKey("p2"));
        }
    }

    @Test
    void pendingAndEventChangedTogetherCannotReplaceOriginalResearchCommand() throws Exception {
        Path original = dir.resolve("original.journal");
        long begin;
        try (WorldRuntime world = ComposedResearchScenario.open(original)) {
            ResearchCommercialWorldTest.buildCompletedProject(world);
            long due = world.snapshot().timeMillis() + 12_345;
            world.openRoleSession("dave").registerResearch("future", "future-task", due,
                    "eng-a", "p7", 1_000);
            begin = due - 1_000;
        }
        Path control = Files.copy(original, dir.resolve("control.journal"));
        int frame = JournalTamper.frames(control).size() - 1;
        JournalTamper.rewrite(control, frame, json -> json + " ");
        try (WorldRuntime world = ComposedResearchScenario.open(control)) {
            assertTrue(world.snapshot().pending().stream().anyMatch(task -> task.taskId().equals("future-task")));
        }
        Path attack = Files.copy(original, dir.resolve("attack.journal"));
        ObjectMapper mapper = new ObjectMapper();
        JournalTamper.rewrite(attack, frame, json -> {
            try {
                ObjectNode commit = (ObjectNode) mapper.readTree(json);
                String command = commit.get("commandContent").asText();
                int hits = 0;
                for (var event : commit.withArray("events")) {
                    String before = event.get("payload").asText();
                    String after = before.replace("\"dueTimeMillis\":" + begin,
                            "\"dueTimeMillis\":" + (begin + 1));
                    if (!before.equals(after)) {
                        ((ObjectNode) event).put("payload", after);
                        hits++;
                    }
                }
                assertEquals(2, hits, "必须同时改待办快照和排程事件");
                assertEquals(command, commit.get("commandContent").asText());
                return mapper.writeValueAsString(commit);
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
        Exception failure = assertThrows(Exception.class, () -> {
            try (WorldRuntime ignored = ComposedResearchScenario.open(attack)) { }
        });
        assertTrue(failure.getMessage().contains("原命令") || failure.getMessage().contains("到期"),
                "应由语义原意校验拒绝而不是JSON损坏：" + failure);
    }
}
