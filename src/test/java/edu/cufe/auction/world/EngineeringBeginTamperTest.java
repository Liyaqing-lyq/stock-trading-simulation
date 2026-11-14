package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEGIN 屏障的恢复门：自洽改写开始时间、角色或完成子任务必须被拒；撕裂尾帧只回退到最后完整批次。
 *
 * <p>直接重写真实 journal 的帧并重算帧摘要（模拟完整自洽篡改），不用删测试或放宽断言。</p>
 */
class EngineeringBeginTamperTest {

    private static final String A = WorldWebScenario.ENGINE_A;

    private static Path dir() throws IOException {
        Path dir = Path.of("target", "engineering-begin-tamper");
        Files.createDirectories(dir);
        return dir;
    }

    private static EngineeringRun.Config config() {
        EngineeringRun.Config base = WorldWebScenario.engineeringConfig();
        EngineeringRun.CompanySpec a = base.specOf(WorldWebScenario.ENGINE_A);
        EngineeringRun.CompanySpec b = base.specOf(WorldWebScenario.ENGINE_B);
        EngineeringRun.CompanySpec aSpec = new EngineeringRun.CompanySpec(a.companyPartyId(),
                a.companyOwnerId(), a.companyAccountId(), a.customerAccountId(),
                a.supplierAccountId(), a.opsAccountId(), a.unitMillis(), a.laborBp(),
                a.charter(), List.of(new EngineeringRun.ContractPlan("A1",
                        new BigDecimal("100.00"), new BigDecimal("40.0000"), 0, 0, 0, 0,
                        List.of(10L))));
        EngineeringRun.CompanySpec bSpec = new EngineeringRun.CompanySpec(b.companyPartyId(),
                b.companyOwnerId(), b.companyAccountId(), b.customerAccountId(),
                b.supplierAccountId(), b.opsAccountId(), b.unitMillis(), b.laborBp(),
                b.charter(), List.of(new EngineeringRun.ContractPlan("B1",
                        new BigDecimal("400.00"), new BigDecimal("240.0000"), 0, 0, 0, 0,
                        List.of(5L))));
        return new EngineeringRun.Config("begin-tamper 定向夹具", List.of(aSpec, bSpec));
    }

    /** 准备到“已真实任命经理 + 已读到机会”。 */
    private static WorldRuntime prepared(Path file) throws Exception {
        WorldRuntime world = WorldWebScenario.engineeringRuntime(file, config());
        world.schedulePublishDocument("pub-material", "pub-material-task", 10L, "b-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "材料", "实验材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-appt", "pub-appt-task", 10L, "appt-material",
                WorldWebScenario.INVESTOR, InformationModel.Audience.publicAudience(),
                "任免材料", "实验任免材料", "实验材料", 10L, List.of());
        world.schedulePublishDocument("pub-opp", "pub-opp-task", 10L,
                "engineering-opportunity:A1", WorldWebScenario.INVESTOR,
                InformationModel.Audience.direct("dave"), "机会 A1", "冻结机会", "冻结机会",
                10L, List.of());
        List<String> voters = List.of("alice", "bob", "carol", "dave", "erin");
        int index = 0;
        for (String reader : voters) {
            world.scheduleStartReading("read-mat-" + reader, "read-mat-" + reader + "-task",
                    20L + index * 2L, reader, "b-material", 1L);
            world.scheduleStartReading("read-appt-" + reader, "read-appt-" + reader + "-task",
                    60L + index * 2L, reader, "appt-material", 1L);
            index++;
        }
        world.scheduleStartReading("read-opp", "read-opp-task", 100L, "dave",
                "engineering-opportunity:A1", 1L);
        // 预算会议（真实五席）
        world.scheduleEngineeringConvene("budget-c", "budget-c-task", 100L, A, "alice",
                "budget-meeting", 150L, 200L, 300L);
        for (int i = 0; i < 5; i++) {
            world.scheduleEngineeringAttend("budget-a-" + i, "budget-a-" + i + "-task",
                    110L + i, A, voters.get(i), "budget-meeting", false);
        }
        world.scheduleEngineeringMaterial("budget-m", "budget-m-task", 130L, A, "alice",
                "budget-meeting", "m1", "b-material", 1L);
        world.scheduleEngineeringBudgetProposal("budget-p", "budget-p-task", 140L, A, "alice",
                "budget-meeting", List.of(
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PRODUCTION_LABOR,
                                new BigDecimal("10000.00")),
                        new BoardModel.BudgetLine(0, BoardModel.BudgetCategory.PROCUREMENT,
                                new BigDecimal("10000.00"))));
        for (int i = 0; i < 4; i++) {
            world.scheduleEngineeringVote("budget-v-" + i, "budget-v-" + i + "-task",
                    220L + i, A, voters.get(i), "budget-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("budget-i", "budget-i-task", 320L, A, "alice",
                "budget-meeting");
        // 任免会议（真实授权 → 真任命）
        world.scheduleEngineeringAppointmentConvene("appt-c", "appt-c-task", 400L, A, "alice",
                "appt-meeting", 450L, 500L, 600L);
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringAttend("appt-a-" + i, "appt-a-" + i + "-task", 410L + i, A,
                    voters.get(i), "appt-meeting", false);
        }
        world.scheduleEngineeringMaterial("appt-m", "appt-m-task", 430L, A, "alice",
                "appt-meeting", "m1", "appt-material", 1L);
        world.scheduleEngineeringAppointmentProposal("appt-p", "appt-p-task", 440L, A, "alice",
                "appt-meeting", List.of(),
                new BoardModel.AppointmentRequest("mgr-a", "dave", A,
                        RoleAccess.Role.COMPANY_MANAGER, BoardModel.AppointmentAction.APPOINT,
                        700L, 900_000L));
        for (int i = 0; i < 3; i++) {
            world.scheduleEngineeringVote("appt-v-" + i, "appt-v-" + i + "-task", 520L + i, A,
                    voters.get(i), "appt-meeting", BoardModel.Vote.YES);
        }
        world.scheduleEngineeringImplement("appt-i", "appt-i-task", 620L, A, "alice",
                "appt-meeting");
        world.advanceTo(1_000L);
        assertTrue(world.activeManagerForTest("dave", A), "夹具必须先真实任命经理");
        return world;
    }

    private static Path baselineBeforeBegin() throws Exception {
        Path file = dir().resolve("baseline-before.journal");
        Files.deleteIfExists(file);
        WorldRuntime world = prepared(file);
        // 工时 1000，到期 3000 → BEGIN 尚未发生
        world.scheduleEngineeringAction("sign-a", "sign-a-task", 3_000L, A, "dave", "SIGN",
                "A1", "", 0, 0, BigDecimal.ZERO, "", 0, 1_000L);
        world.advanceTo(1_500L);
        world.close();
        return file;
    }

    private static Path baselineAfterBegin() throws Exception {
        Path file = dir().resolve("baseline-after.journal");
        Files.deleteIfExists(file);
        WorldRuntime world = prepared(file);
        world.scheduleEngineeringAction("sign-a", "sign-a-task", 3_000L, A, "dave", "SIGN",
                "A1", "", 0, 0, BigDecimal.ZERO, "", 0, 1_000L);
        world.advanceTo(2_000L);
        assertNotNull(world.snapshot().engineering().orElseThrow()
                .reports().get("sign-a-task"), "基线必须已真实 BEGIN");
        world.advanceTo(2_500L);
        world.close();
        return file;
    }

    @Test
    void selfConsistentBeginTimeTamperingRejected() throws Exception {
        Path base = baselineBeforeBegin();
        Path variant = dir().resolve("tamper-begin-time.journal");
        Files.copy(base, variant, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        tamperLastSnapshot(variant, (snapshot, mapper) -> {
            for (JsonNode task : (ArrayNode) snapshot.get("pending")) {
                ObjectNode node = (ObjectNode) task;
                if (!"sign-a-task".equals(node.get("taskId").asText())) {
                    continue;
                }
                assertEquals("ENGINEERING_BEGIN_V1", node.get("kind").asText());
                node.put("dueTimeMillis", 1_800L);
                return true;
            }
            return false;
        });
        assertThrows(IOException.class, () -> WorldWebScenario.engineeringRuntime(variant, config()),
                "自洽改写 BEGIN 开始时间必须被拒");
    }

    @Test
    void selfConsistentSubtaskTamperingRejected() throws Exception {
        Path base = baselineAfterBegin();
        for (String mode : List.of("due", "drop")) {
            Path variant = dir().resolve("tamper-subtask-" + mode + ".journal");
            Files.copy(base, variant, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            tamperLastSnapshot(variant, (snapshot, mapper) -> {
                ArrayNode pending = (ArrayNode) snapshot.get("pending");
                for (int i = 0; i < pending.size(); i++) {
                    ObjectNode node = (ObjectNode) pending.get(i);
                    if (!"sign-a-task:finish".equals(node.get("taskId").asText())) {
                        continue;
                    }
                    if ("drop".equals(mode)) {
                        pending.remove(i);
                    } else {
                        node.put("dueTimeMillis", 2_500L);
                    }
                    return true;
                }
                return false;
            });
            assertThrows(IOException.class,
                    () -> WorldWebScenario.engineeringRuntime(variant, config()),
                    "自洽改写/删除 BEGIN 派生完成子任务必须被拒：" + mode);
        }
    }

    @Test
    void selfConsistentRoleTamperingRejected() throws Exception {
        Path base = baselineBeforeBegin();
        Path variant = dir().resolve("tamper-role.journal");
        Files.copy(base, variant, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        tamperLastSnapshot(variant, (snapshot, mapper) -> {
            JsonNode appointments = snapshot.get("roles").get("access").get("appointments");
            for (JsonNode item : appointments) {
                ObjectNode node = (ObjectNode) item;
                if (node.get("role").asText().equals("COMPANY_MANAGER")) {
                    node.put("revokedAt", 900L);
                    return true;
                }
            }
            return false;
        });
        assertThrows(IOException.class, () -> WorldWebScenario.engineeringRuntime(variant, config()),
                "自洽改写任职撤销时刻必须被拒");
    }

    @Test
    void tornTailFrameFallsBackToLastCompleteBatch() throws Exception {
        Path base = baselineAfterBegin();
        byte[] bytes = Files.readAllBytes(base);
        List<byte[]> frames = readFrames(base);
        long framesBefore = frames.size();
        Path torn = dir().resolve("torn.journal");
        // 截掉尾帧最后 10 字节：撕裂写必须退回上一完整批次，而不是接受半帧
        Files.write(torn, Arrays.copyOf(bytes, bytes.length - 10));
        try (WorldRuntime reopened = WorldWebScenario.engineeringRuntime(torn, config())) {
            assertTrue(reopened.snapshot().position() < framesBefore,
                    "撕裂尾帧必须退回最后完整批次");
            assertEquals(reopened.snapshot().timeMillis(),
                    reopened.snapshot().timeMillis());
        }
        // 完整帧内翻转一个字节：校验和不匹配必须拒绝
        Path corrupt = dir().resolve("corrupt.journal");
        byte[] damaged = bytes.clone();
        damaged[damaged.length - 40] = (byte) (damaged[damaged.length - 40] ^ 0xFF);
        Files.write(corrupt, damaged);
        assertThrows(IOException.class,
                () -> WorldWebScenario.engineeringRuntime(corrupt, config()),
                "完整帧校验和不匹配必须拒绝");
    }

    // ---------------------------------------------------------------- 篡改工具

    private interface SnapshotMutation {
        boolean apply(ObjectNode snapshot, ObjectMapper mapper) throws IOException;
    }

    private static void tamperLastSnapshot(Path file, SnapshotMutation mutation) throws IOException {
        List<byte[]> frames = readFrames(file);
        int index = frames.size() - 1;
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode batch = (ObjectNode) mapper.readTree(frames.get(index));
        ArrayNode events = (ArrayNode) batch.get("events");
        boolean touched = false;
        for (JsonNode event : events) {
            if (!"STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                continue;
            }
            ObjectNode snapshot = (ObjectNode) mapper.readTree(event.get("payload").asText());
            if (!mutation.apply(snapshot, mapper)) {
                continue;
            }
            ((ObjectNode) event).put("payload", mapper.writeValueAsString(snapshot));
            touched = true;
        }
        if (!touched) {
            throw new IllegalArgumentException("该帧没有可篡改的快照内容");
        }
        frames.set(index, mapper.writeValueAsBytes(batch));
        Files.write(file, frameBytes(frames));
    }

    private static List<byte[]> readFrames(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            int length = ByteBuffer.wrap(bytes, offset, 4).getInt();
            int inverse = ByteBuffer.wrap(bytes, offset + 4, 4).getInt();
            if (length <= 0 || inverse != ~length || offset + 8 + length + 32 > bytes.length) {
                throw new IOException("帧格式不合法：" + offset);
            }
            frames.add(Arrays.copyOfRange(bytes, offset + 8, offset + 8 + length));
            offset += 8 + length + 32;
        }
        return frames;
    }

    private static byte[] frameBytes(List<byte[]> frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] payload : frames) {
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length).array());
            out.write(payload);
            out.write(sha256(payload));
        }
        return out.toByteArray();
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("缺少 SHA-256", impossible);
        }
    }
}
