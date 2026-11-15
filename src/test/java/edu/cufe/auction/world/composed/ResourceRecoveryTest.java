package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static edu.cufe.auction.world.composed.ComposedIndustryScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** 资源模型输入、同帧资金、跨JVM、分支与自洽改待办的真实来源验证。 */
public class ResourceRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    @Test void threeProcessesRestoreWholeWorldAndIsolatedBranch() throws Exception {
        Set<String> pids = new HashSet<>();
        for (String phase : List.of("write", "finish", "branch")) {
            Path output = dir.resolve(phase + ".log");
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    ResourceRecoveryTest.class.getName(), dir.toString(), phase)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("资源进程超时：" + phase); }
            String result = Files.readString(output); assertEquals(0, process.exitValue(), result);
            pids.add(result.lines().filter(line -> line.startsWith("RESOURCE_JVM_OK ")).findFirst().orElseThrow());
        }
        assertEquals(3, pids.size()); assertTrue(Files.exists(dir.resolve("branch-ok")));
    }

    /** 独立进程只调用真实命令，不改内核状态。 */
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]), journal = folder.resolve("resource.journal"), expected = folder.resolve("snapshot.json");
        try (var world = ComposedIndustryScenario.open(journal)) {
            if (args[1].equals("write")) {
                OperationsFixture.grantBudgetAndAppoint(world, RESOURCE, DIRECTORS, MANAGER, "jvm", List.of(0L));
                var call = PharmaDecisionToolTest.open(world, MANAGER, "mine-model");
                long finish = PharmaDecisionToolTest.reply(world, call, ResourceDecisionTools.ACT,
                        Map.of("action", "BEGIN_EXTRACTION", "arguments", Map.of("jobId", "mine", "seamId", "resource-seam-a", "quantityKg", "100")));
                world.advanceTo(finish - 500); Files.writeString(folder.resolve("finish.txt"), Long.toString(finish));
            } else {
                assertEquals(JSON.readTree(Files.readString(expected)), JSON.readTree(JSON.writeValueAsString(ManufacturingRecoveryTest.snapshotTree(world))), "全快照、治理及银行必须逐字段恢复");
                if (args[1].equals("finish")) {
                    world.advanceTo(Long.parseLong(Files.readString(folder.resolve("finish.txt"))));
                    world.advanceTo(ResourceWorldChainTest.resource(world).jobs().get("mine").dueAt());
                    assertEquals(100L, ResourceWorldChainTest.resource(world).mineCoal().quantity());
                } else {
                    var branch = world.createBranch("resource-child", world.snapshot().position(), BranchOrigin.Kind.OPERATOR_BRANCH, "资源世界分支验证");
                    var profile = world.profile(); world.close(); byte[] parent = Files.readAllBytes(journal);
                    try (var child = WorldRuntime.openBranch(branch.childFile(), id -> branch.sealedPrefix(), profile)) {
                        ResourceWorldChainTest.act(child, "child-mine", ResourceWorldChainTest.request(ResourceOperations.Action.BEGIN_EXTRACTION,
                                "jobId", "child-job", "seamId", "resource-seam-a", "quantityKg", "10"));
                        assertEquals("9890.00", child.snapshot().accounts().get(CASH).cash().toPlainString());
                    }
                    try (var child = WorldRuntime.openBranch(branch.childFile(), id -> branch.sealedPrefix(), profile)) {
                        assertEquals("9890.00", child.snapshot().accounts().get(CASH).cash().toPlainString());
                    }
                    assertArrayEquals(parent, Files.readAllBytes(journal)); Files.writeString(folder.resolve("branch-ok"), "verified");
                    System.out.println("RESOURCE_JVM_OK " + ProcessHandle.current().pid()); return;
                }
            }
            Files.writeString(expected, JSON.writeValueAsString(ManufacturingRecoveryTest.snapshotTree(world)));
        }
        System.out.println("RESOURCE_JVM_OK " + ProcessHandle.current().pid());
    }

    @Test void changedJobAndMatchingChildScheduleCannotRewriteOriginalCommand() throws Exception {
        Path journal = dir.resolve("tamper.journal");
        try (var world = ComposedIndustryScenario.open(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, RESOURCE, DIRECTORS, MANAGER, "tamper", List.of(0L));
            ResourceWorldChainTest.act(world, "extract", ResourceWorldChainTest.request(ResourceOperations.Action.BEGIN_EXTRACTION,
                    "jobId", "mine", "seamId", "resource-seam-a", "quantityKg", "100"));
        }
        try (var control = ComposedIndustryScenario.open(journal)) { assertNotNull(ResourceWorldChainTest.resource(control).jobs().get("mine")); }
        byte[] bytes = Files.readAllBytes(journal); List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < bytes.length;) {
            int size = ByteBuffer.wrap(bytes, offset, 4).getInt();
            frames.add(Arrays.copyOfRange(bytes, offset + 8, offset + 8 + size)); offset += 8 + size + 32;
        }
        int last = frames.size() - 1; ObjectNode frame = (ObjectNode) JSON.readTree(frames.get(last));
        String original = frame.path("commandContent").asText(); int changes = 0;
        String childId = "ops-done:resources:FINISH_EXTRACTION:mine";
        for (var event : frame.withArray("events")) {
            if (event.path("type").asText().equals("STATE_SNAPSHOT_V1")) {
                ObjectNode snapshot = (ObjectNode) JSON.readTree(event.path("payload").asText());
                ObjectNode job = (ObjectNode) snapshot.path("companyOperations").path("resource").path("ledger").path("jobs").path("mine");
                job.put("dueAt", job.path("dueAt").asLong() + 1000); changes++;
                for (var task : snapshot.path("pending")) if (childId.equals(task.path("taskId").asText())) {
                    ((ObjectNode) task).put("dueTimeMillis", task.path("dueTimeMillis").asLong() + 1000); changes++;
                }
                ((ObjectNode) event).put("payload", JSON.writeValueAsString(snapshot));
            } else if (event.path("type").asText().equals("TASK_SCHEDULED_V1")) {
                ObjectNode task = (ObjectNode) JSON.readTree(event.path("payload").asText());
                if (childId.equals(task.path("taskId").asText())) {
                    task.put("dueTimeMillis", task.path("dueTimeMillis").asLong() + 1000);
                    ((ObjectNode) event).put("payload", JSON.writeValueAsString(task)); changes++;
                }
            }
        }
        assertEquals(3, changes); assertEquals(original, frame.path("commandContent").asText());
        frames.set(last, JSON.writeValueAsBytes(frame)); ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] payload : frames) {
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length).array());
            out.write(payload); out.write(MessageDigest.getInstance("SHA-256").digest(payload));
        }
        Files.write(journal, out.toByteArray());
        Exception rejected = assertThrows(Exception.class, () -> ComposedIndustryScenario.open(journal));
        String message = String.valueOf(rejected.getMessage());
        assertFalse(message.contains("校验和"), message); assertFalse(message.contains("不可解析"), message);
        assertTrue(message.contains("不一致") || message.contains("重演") || message.contains("不得"), message);
    }
}
