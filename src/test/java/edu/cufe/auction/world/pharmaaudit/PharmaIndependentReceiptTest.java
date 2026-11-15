package edu.cufe.auction.world.pharmaaudit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.EngineeringRun;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedPharmaScenario;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 独审第一组：新独立公司（pharma-debug-v1）BEGIN/FINISH 的<b>准确回执</b>与其反例。
 *
 * <p>正向：真实模型工具注入（确定性回执，不访问外部模型）产生的董事 BEGIN 与 FINISH 都必须
 * 在工程回报表里保存真实公司、成功标志、原因码与帧位置；公司必须由真实待办解析，而不是写死。
 * 反向：在原命令与帧格式完全不变、只同步改写最后一帧快照并<b>重算每帧校验和</b>的前提下，
 * 伪造成功状态、伪造公司、改现金、改预算、插额外回执都必须由领域重演拒绝；拒绝信息不得来自
 * 校验和/帧头/JSON 解析。干净未改副本必须能打开（证明拒绝不是「什么新档都打不开」）。
 */
class PharmaIndependentReceiptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BEGIN_TASK =
            edu.cufe.auction.world.DecisionRun.actionTaskId("audit-convene");
    private static final String FINISH_TASK = BEGIN_TASK + ":finish";

    @TempDir
    Path dir;

    // ------------------------------------------------------------ 正向：准确回执

    @Test
    void directorBeginAndFinishReceiptsAreSavedWithTrueCompany() throws Exception {
        Path journal = directorFrameJournal(dir);
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            EngineeringRun engineering = world.snapshot().engineering().orElseThrow();
            EngineeringRun.Report begin = engineering.reports().get(BEGIN_TASK);
            EngineeringRun.Report finish = engineering.reports().get(FINISH_TASK);
            assertNotNull(begin, "BEGIN 回执必须保存到工程回报表：" + engineering.reports().keySet());
            assertNotNull(finish, "FINISH 回执必须保存到工程回报表：" + engineering.reports().keySet());
            assertEquals(ComposedPharmaScenario.PHARMA, begin.companyPartyId(),
                    "BEGIN 回执公司必须由真实待办解析为 pharma");
            assertEquals(ComposedPharmaScenario.PHARMA, finish.companyPartyId(),
                    "FINISH 回执公司必须由真实待办解析为 pharma");
            assertTrue(finish.accepted(), "真实董事召集必须被接受");
            assertEquals("OK", finish.reason());
            assertTrue(finish.position() > begin.position(), "完成帧位置必须前进");
            assertEquals("ACCEPTED", world.taskResultForTest(FINISH_TASK));
            assertTrue(world.boardOfCompany(ComposedPharmaScenario.PHARMA).orElseThrow()
                    .board().meetings().containsKey("audit-probe-meeting"));
            assertTrue(world.boardOfCompany("north").orElseThrow().board().meetings().isEmpty(),
                    "pharma 的董事动作不得落到 north");
        }
    }

    // ------------------------------------------------------------ 反向：干净对照

    @Test
    void cleanCopyOfDirectorFrameStillOpens() throws Exception {
        Path base = directorFrameJournal(dir);
        Path copy = dir.resolve("clean-copy.journal");
        Files.copy(base, copy, StandardCopyOption.REPLACE_EXISTING);
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(copy)) {
            assertEquals(ComposedPharmaScenario.PHARMA,
                    world.snapshot().engineering().orElseThrow().reports()
                            .get(FINISH_TASK).companyPartyId());
        }
    }

    // ------------------------------------------------------------ 反向：同步伪造

    @Test
    void forgedSuccessStateOnRealTaskIsRejected() throws Exception {
        Path attacked = tampered("state", (snapshot) -> {
            ObjectNode report = reportOf(snapshot, FINISH_TASK);
            if (report == null) {
                return false;
            }
            report.put("accepted", !report.path("accepted").asBoolean());
            report.put("reason", "FORGED_OK");
            return true;
        });
        assertDomainRejection(attacked);
    }

    @Test
    void forgedCompanyOnReceiptIsRejected() throws Exception {
        Path attacked = tampered("company", (snapshot) -> {
            ObjectNode report = reportOf(snapshot, FINISH_TASK);
            if (report == null) {
                return false;
            }
            report.put("companyPartyId", "north");
            return true;
        });
        assertDomainRejection(attacked);
    }

    @Test
    void forgedCashOnDirectorFrameIsRejected() throws Exception {
        Path attacked = tampered("cash", (snapshot) -> {
            JsonNode accounts = snapshot.path("accounts").path("RND-PHARMA");
            if (!accounts.isObject()) {
                return false;
            }
            ((ObjectNode) accounts).put("cash", "9999.00");
            return true;
        });
        assertDomainRejection(attacked);
    }

    @Test
    void forgedBudgetOnDirectorFrameIsRejected() throws Exception {
        Path attacked = tamperedOn(budgetChainJournal(dir), "budget", (snapshot) -> {
            JsonNode budgets = snapshot.path("companyGovernance").path("boards")
                    .path("pharma").path("board").path("budgets");
            if (!budgets.isArray() || budgets.isEmpty()) {
                return false;
            }
            ObjectNode first = (ObjectNode) budgets.get(0);
            assertTrue(first.has("cap"), "预算对象必须有 cap：" + first);
            first.put("cap", "999999.00");
            return true;
        });
        assertDomainRejection(attacked);
    }

    @Test
    void injectedExtraReceiptIsRejected() throws Exception {
        Path attacked = tampered("extra", (snapshot) -> {
            JsonNode reports = snapshot.path("engineering").path("reports");
            if (!reports.isObject()) {
                return false;
            }
            ObjectNode extra = MAPPER.createObjectNode();
            extra.put("taskId", "decT:5:fake:5:finish");
            extra.put("commandId", "dec:5:fake:5");
            extra.put("kind", "ENGINEERING_BOARD_V1");
            extra.put("companyPartyId", ComposedPharmaScenario.PHARMA);
            extra.put("accepted", true);
            extra.put("reason", "OK");
            extra.put("timeMillis", snapshot.path("timeMillis").asLong());
            extra.put("position", snapshot.path("position").asLong());
            ((ObjectNode) reports).set("decT:5:fake:5:finish", extra);
            return true;
        });
        assertDomainRejection(attacked);
    }

    // ------------------------------------------------------------ 工具

    /** 最后帧是「额外公司董事召集完成帧」的新 Debug 档。 */
    private Path directorFrameJournal(Path dir) throws Exception {
        Path journal = dir.resolve("director.journal");
        if (Files.exists(journal) && Files.size(journal) > 0L) {
            return journal;
        }
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            long due = PharmaAuditHarness.reply(world,
                    PharmaAuditHarness.open(world, "frank", "audit-convene"),
                    "board.convene", Map.of("companyPartyId", ComposedPharmaScenario.PHARMA,
                            "meetingId", "audit-probe-meeting", "topic", "MANAGER_APPOINTMENT",
                            "materialDeadline", 20_000, "opensAt", 30_000, "closesAt", 60_000));
            world.advanceTo(due);
            assertEquals("ACCEPTED", world.taskResultForTest(FINISH_TASK));
        }
        return journal;
    }

    /** 真实董事预算 + 真实经理任免都实施完成后的 Debug 档（最后一帧含真实预算）。 */
    private Path budgetChainJournal(Path dir) throws Exception {
        Path journal = dir.resolve("budget.journal");
        if (Files.exists(journal) && Files.size(journal) > 0L) {
            return journal;
        }
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            PharmaAuditHarness.approveBudgetAndAppoint(world, "ivan");
            assertFalse(world.boardOfCompany(ComposedPharmaScenario.PHARMA).orElseThrow()
                    .board().budgets().isEmpty(), "真实预算决议必须写入预算表");
            assertTrue(world.isActiveManager(ComposedPharmaScenario.PHARMA, "ivan",
                    world.snapshot().timeMillis()), "真实任免必须产生有效经理");
        }
        return journal;
    }

    private static ObjectNode reportOf(JsonNode snapshot, String taskId) {
        JsonNode reports = snapshot.path("engineering").path("reports");
        if (!reports.isObject()) {
            return null;
        }
        JsonNode report = reports.get(taskId);
        return report != null && report.isObject() ? (ObjectNode) report : null;
    }

    /** 复制日志并只在最后一帧的 STATE_SNAPSHOT_V1 上改写，随后重算每帧长度与 SHA-256。 */
    private Path tampered(String tag, SnapshotMutation mutation) throws Exception {
        return tamperedOn(directorFrameJournal(dir), tag, mutation);
    }

    private Path tamperedOn(Path base, String tag, SnapshotMutation mutation) throws Exception {
        Path target = dir.resolve(tag + "-attacked.journal");
        Files.copy(base, target, StandardCopyOption.REPLACE_EXISTING);
        List<byte[]> frames = readFrames(target);
        int index = frames.size() - 1;
        ObjectNode batch = (ObjectNode) MAPPER.readTree(frames.get(index));
        ArrayNode events = (ArrayNode) batch.get("events");
        boolean touched = false;
        for (JsonNode event : events) {
            if (!"STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                continue;
            }
            ObjectNode snapshot = (ObjectNode) MAPPER.readTree(event.get("payload").asText());
            if (!mutation.apply(snapshot)) {
                continue;
            }
            ((ObjectNode) event).put("payload", MAPPER.writeValueAsString(snapshot));
            touched = true;
        }
        assertTrue(touched, tag + " 没有可篡改的快照内容");
        frames.set(index, MAPPER.writeValueAsBytes(batch));
        Files.write(target, frameBytes(frames));
        return target;
    }

    private static void assertDomainRejection(Path attacked) {
        Exception failure = assertThrows(Exception.class,
                () -> ComposedPharmaScenario.openDebug(attacked));
        String message = String.valueOf(failure.getMessage());
        assertNotNull(message);
        for (String format : List.of("校验和", "帧头", "不可解析", "无法解析")) {
            assertFalse(message.contains(format), "拒绝不能只是格式错误：" + message);
        }
        boolean domain = message.contains("不一致") || message.contains("重演")
                || message.contains("待办") || message.contains("现金")
                || message.contains("预算") || message.contains("研发")
                || message.contains("董事会") || message.contains("任务帧")
                || message.contains("工程");
        assertTrue(domain, "拒绝必须来自领域重演，实际：" + message);
    }

    private static List<byte[]> readFrames(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            int length = ByteBuffer.wrap(bytes, offset, 4).getInt();
            int inverse = ByteBuffer.wrap(bytes, offset + 4, 4).getInt();
            if (length <= 0 || inverse != ~length || offset + 8 + length + 32 > bytes.length) {
                throw new IllegalStateException("帧格式不合法：" + offset);
            }
            frames.add(Arrays.copyOfRange(bytes, offset + 8, offset + 8 + length));
            offset += 8 + length + 32;
        }
        return frames;
    }

    private static byte[] frameBytes(List<byte[]> frames) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] payload : frames) {
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length)
                    .array());
            out.write(payload);
            out.write(MessageDigest.getInstance("SHA-256").digest(payload));
        }
        return out.toByteArray();
    }

    @Test
    void coherentReportCompanyAndBusinessEventForgeryIsRejected() throws Exception {
        assertDomainRejection(coherentReportForgery(true));
    }

    @Test
    void coherentReportReasonAndBusinessEventForgeryIsRejected() throws Exception {
        assertDomainRejection(coherentReportForgery(false));
    }

    /** 同时改业务回报事件与持久状态中的回报；原命令、帧格式与重新计算的校验和均合法。 */
    private Path coherentReportForgery(boolean changeCompany) throws Exception {
        List<byte[]> frames = readFrames(directorFrameJournal(dir));
        int last = frames.size() - 1;
        ObjectNode batch = (ObjectNode) MAPPER.readTree(frames.get(last));
        boolean eventChanged = false;
        boolean reportChanged = false;
        for (JsonNode event : batch.path("events")) {
            if ("COMPANY_BOARD_REPORT_V1".equals(event.path("type").asText())) {
                ObjectNode payload = (ObjectNode) MAPPER.readTree(event.path("payload").asText());
                assertEquals(FINISH_TASK, payload.path("taskId").asText());
                payload.put(changeCompany ? "company" : "reason", changeCompany ? "north" : "FORGED_OK");
                ((ObjectNode) event).put("payload", MAPPER.writeValueAsString(payload));
                eventChanged = true;
            }
            if ("STATE_SNAPSHOT_V1".equals(event.path("type").asText())) {
                ObjectNode snapshot = (ObjectNode) MAPPER.readTree(event.path("payload").asText());
                ObjectNode report = reportOf(snapshot, FINISH_TASK);
                assertNotNull(report);
                report.put(changeCompany ? "companyPartyId" : "reason", changeCompany ? "north" : "FORGED_OK");
                ((ObjectNode) event).put("payload", MAPPER.writeValueAsString(snapshot));
                reportChanged = true;
            }
        }
        assertTrue(eventChanged && reportChanged, "攻击必须同时覆盖业务事件与回报状态");
        frames.set(last, MAPPER.writeValueAsBytes(batch));
        Path attacked = dir.resolve("coherent-" + changeCompany + ".journal");
        Files.write(attacked, frameBytes(frames));
        return attacked;
    }

    private interface SnapshotMutation {
        boolean apply(ObjectNode snapshot);
    }
}
