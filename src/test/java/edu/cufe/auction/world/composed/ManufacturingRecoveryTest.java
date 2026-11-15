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
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真跨进程、分支隔离以及保持原命令与正确帧校验和的领域篡改检验。 */
public class ManufacturingRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @TempDir Path dir;

    @Test
    void threeJvmsRecoverPendingWorkAndBranchWithoutChangingParent() throws Exception {
        Set<String> pids = new HashSet<>();
        for (String step : List.of("build", "begin", "finish")) {
            Path log = dir.resolve(step + ".log");
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    ManufacturingRecoveryTest.class.getName(), dir.toString(), step)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("独立JVM超时: " + step);
            }
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            String pid = output.lines().filter(line -> line.startsWith("MFG_JVM_OK "))
                    .findFirst().orElseThrow();
            pids.add(pid);
        }
        assertEquals(3, pids.size(), "三个阶段必须属于三个实际进程");
        assertTrue(Files.exists(dir.resolve("branch-verified")));
    }

    /** 独立进程的唯一测试入口；不修改内核状态，只调用真实世界命令。 */
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]);
        Path journal = folder.resolve("world.journal");
        Path checkpoint = folder.resolve("snapshot.json");
        String step = args[1];
        try (WorldRuntime world = open(journal)) {
            if (step.equals("build")) {
                OperationsFixture.grantBudgetAndAppoint(world, TECH, TECH_DIRECTORS,
                        TECH_MANAGER, "jvm-govern", List.of(0L, 1L));
                order(world, "jvm-order", 10L);
            } else {
                var expected = JSON.readTree(Files.readString(checkpoint));
                var actual = JSON.readTree(JSON.writeValueAsString(snapshotTree(world)));
                List<String> differences = new ArrayList<>();
                expected.fieldNames().forEachRemaining(key -> {
                    if (!expected.get(key).equals(actual.get(key))) differences.add(key);
                });
                assertTrue(differences.isEmpty(), "恢复快照存在差异: " + differences);
                if (step.equals("begin")) {
                    var task = world.snapshot().pending().stream()
                            .filter(t -> t.taskId().equals("jvm-order-task")).findFirst().orElseThrow();
                    world.advanceTo(task.dueTimeMillis());
                    assertTrue(world.operationsRun().orElseThrow().company(TECH).ledger().purchases().isEmpty());
                } else {
                    var task = world.snapshot().pending().stream()
                            .filter(t -> t.taskId().equals("jvm-order-task:finish")).findFirst().orElseThrow();
                    world.advanceTo(task.dueTimeMillis());
                    var purchase = world.operationsRun().orElseThrow().company(TECH).ledger()
                            .purchases().values().iterator().next();
                    world.advanceTo(purchase.arrivalAt());
                    assertEquals("11880.00", world.snapshot().accounts().get(TECH_CASH).cash().toPlainString());
                    var parent = world.snapshot();
                    var branch = world.createBranch("mfg-child", parent.position(),
                            BranchOrigin.Kind.OPERATOR_BRANCH, "经营分支隔离验证");
                    var profile = world.profile();
                    world.close();
                    byte[] before = Files.readAllBytes(journal);
                    try (WorldRuntime child = WorldRuntime.openBranch(branch.childFile(), id -> branch.sealedPrefix(), profile)) {
                        order(child, "child-order", 5L);
                        child.advanceTo(child.snapshot().timeMillis() + 5000L);
                        var extra = child.operationsRun().orElseThrow().company(TECH).ledger().purchases().values()
                                .stream().filter(p -> p.status() == ManufacturingTemplateModel.PurchaseStatus.ORDERED)
                                .findFirst().orElseThrow();
                        child.advanceTo(extra.arrivalAt());
                        assertEquals("11820.00", child.snapshot().accounts().get(TECH_CASH).cash().toPlainString());
                    }
                    try (WorldRuntime child = WorldRuntime.openBranch(branch.childFile(), id -> branch.sealedPrefix(), profile)) {
                        assertEquals("11820.00", child.snapshot().accounts().get(TECH_CASH).cash().toPlainString());
                    }
                    assertArrayEquals(before, Files.readAllBytes(journal));
                    try (WorldRuntime restored = open(journal)) { assertEquals(parent, restored.snapshot()); }
                    Files.writeString(folder.resolve("branch-verified"), "true");
                    System.out.println("MFG_JVM_OK " + ProcessHandle.current().pid());
                    return;
                }
            }
            Files.writeString(checkpoint, JSON.writeValueAsString(snapshotTree(world)));
        }
        System.out.println("MFG_JVM_OK " + ProcessHandle.current().pid());
    }

    @Test
    void coherentOperatingReportTamperCannotOverrideOriginalCommand() throws Exception {
        Path journal = dir.resolve("tamper.journal");
        try (WorldRuntime world = open(journal)) {
            OperationsFixture.grantBudgetAndAppoint(world, TECH, TECH_DIRECTORS,
                    TECH_MANAGER, "tamper-govern", List.of(0L));
            order(world, "tamper-order", 10L);
            world.advanceTo(world.snapshot().timeMillis() + 5000L);
        }
        try (WorldRuntime clean = open(journal)) {
            assertNotNull(clean.operationsRun().orElseThrow().report("tamper-order-task:finish"));
        }
        byte[] bytes = Files.readAllBytes(journal);
        List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < bytes.length;) {
            int n = ByteBuffer.wrap(bytes, offset, 4).getInt();
            frames.add(Arrays.copyOfRange(bytes, offset + 8, offset + 8 + n));
            offset += 8 + n + 32;
        }
        int last = frames.size() - 1;
        ObjectNode frame = (ObjectNode) JSON.readTree(frames.get(last));
        String originalCommand = frame.path("commandContent").asText();
        boolean changed = false;
        for (var event : frame.withArray("events")) {
            if (event.path("type").asText().equals("STATE_SNAPSHOT_V1")) {
                ObjectNode state = (ObjectNode) JSON.readTree(event.path("payload").asText());
                ObjectNode report = (ObjectNode) state.path("companyOperations").path("reports")
                        .get("tamper-order-task:finish");
                assertNotNull(report);
                report.put("accepted", false);
                report.put("reason", "INVENTED_DENIAL");
                ((ObjectNode) event).put("payload", JSON.writeValueAsString(state));
                changed = true;
            } else if (event.path("payload").asText().contains("MATERIAL_ORDERED")) {
                ((ObjectNode) event).put("payload", event.path("payload").asText()
                        .replace("MATERIAL_ORDERED", "INVENTED_DENIAL"));
            }
        }
        assertTrue(changed);
        assertEquals(originalCommand, frame.path("commandContent").asText());
        frames.set(last, JSON.writeValueAsBytes(frame));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] payload : frames) {
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length).array());
            out.write(payload);
            out.write(MessageDigest.getInstance("SHA-256").digest(payload));
        }
        Files.write(journal, out.toByteArray());
        Exception rejected = assertThrows(Exception.class, () -> open(journal));
        String message = String.valueOf(rejected.getMessage());
        assertFalse(message.contains("校验和"), message);
        assertFalse(message.contains("不可解析"), message);
        assertTrue(message.contains("不一致") || message.contains("重演") || message.contains("不得"), message);
    }

    static com.fasterxml.jackson.databind.JsonNode snapshotTree(WorldRuntime world) throws Exception {
        return JSON.valueToTree(canonical(Map.of("snapshot", world.snapshot(),
                "companyGovernance", world.companyGovernance().orElseThrow(),
                "bank", world.bankOf().orElseThrow(), "bankActions", world.bankBook())));
    }

    private static Object canonical(Object value) throws Exception {
        if (value == null) return null;
        if (value instanceof Optional<?> optional) return canonical(optional.orElse(null));
        if (value instanceof java.math.BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new TreeMap<>();
            for (var entry : map.entrySet()) copy.put(String.valueOf(entry.getKey()), canonical(entry.getValue()));
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>();
            for (Object item : collection) copy.add(canonical(item));
            if (value instanceof Set<?>) copy.sort(Comparator.comparing(item -> JSON.valueToTree(item).toString()));
            return copy;
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> copy = new TreeMap<>();
            for (var field : value.getClass().getRecordComponents()) {
                var getter = field.getAccessor();
                getter.setAccessible(true);
                copy.put(field.getName(), canonical(getter.invoke(value)));
            }
            return copy;
        }
        return value;
    }

    private static void order(WorldRuntime world, String id, long quantity) throws Exception {
        world.scheduleOperationsAction(id, id + "-task", world.snapshot().timeMillis() + 5000L,
                TECH, TECH_MANAGER, CompanyOperationsRun.Action.ORDER_MATERIAL.name(),
                OperationsFixture.op().material("MAT-A", quantity).build(), 2000L);
    }
}
