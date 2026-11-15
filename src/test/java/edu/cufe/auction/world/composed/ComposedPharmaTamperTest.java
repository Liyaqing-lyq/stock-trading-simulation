package edu.cufe.auction.world.composed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 独立研发公司的同步篡改反例：在<b>原命令完全不变</b>的前提下，只改最后一帧的自洽快照
 * （额外公司现金 / 公司预算 / 研发状态 / 待办），再用同一真实工厂重开。
 *
 * <p>每个攻击副本都必须由「上一帧 + 原待办」的完整原意重演拒绝，而且拒绝不能来自校验和、
 * 帧头或 JSON 解析——那是格式损坏，不是领域拒绝。干净对照必须能正常打开，证明反例不是
 * “任何新档案都开不了”。</p>
 */
class ComposedPharmaTamperTest {

    private static final String PHARMA = ComposedPharmaScenario.PHARMA;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    /** 干净对照：未篡改的同一份日志必须能正常打开并保持金额。 */
    @Test
    void cleanControlOpensWithExactNumbers() throws Exception {
        Path base = dir.resolve("clean.journal");
        ComposedPharmaWorldTest.buildApprovedChain(base);
        try (var world = ComposedPharmaScenario.open(base)) {
            assertTrue(world.researchRun().orElseThrow().state().projects().get("p1")
                    .status() == edu.cufe.auction.world.ResearchModel.ProjectStatus.APPROVED);
            assertTrue(ComposedCompanyReport.of(world, PHARMA).balanced());
        }
    }

    /** 额外公司现金被同步改写：重开必须由现金重演拒绝。 */
    @Test
    void tamperedExtraCompanyCashIsRejected() throws Exception {
        Path target = tampered("cash", (snapshot, mapper) -> {
            ObjectNode accounts = (ObjectNode) snapshot.get("accounts");
            if (accounts == null) {
                return false;
            }
            ObjectNode account = (ObjectNode) accounts.get(
                    ComposedPharmaScenario.PHARMA_CASH);
            if (account == null) {
                return false;
            }
            account.put("cash", "5100.00");
            return true;
        });
        assertDomainRejection(target, "现金");
    }

    /** 公司预算被同步改写：重开必须由董事会重演拒绝（不是按来源白名单放行）。 */
    @Test
    void tamperedCompanyBudgetIsRejected() throws Exception {
        Path target = tampered("budget", (snapshot, mapper) -> {
            JsonNode container = snapshot.get("companyGovernance");
            if (container == null) {
                return false;
            }
            return mutateFirstBudgetLeaf((ObjectNode) container);
        });
        assertDomainRejection(target, "预算");
    }

    /** 研发摊销/批准事实被同步改写：重开必须由研发重演拒绝。 */
    @Test
    void tamperedResearchAmortizationIsRejected() throws Exception {
        Path target = tampered("research", (snapshot, mapper) -> {
            JsonNode project = snapshot.path("research").path("state").path("projects")
                    .path("p1");
            if (!project.isObject()) {
                return false;
            }
            ((ObjectNode) project).put("amortized", "1.00");
            return true;
        });
        assertDomainRejection(target, "研发");
    }

    /** 待办到期被同步改写：重开必须由原命令排程重演拒绝（不是只看事件相等）。 */
    @Test
    void tamperedPendingTaskIsRejected() throws Exception {
        Path target = tampered("pending", (snapshot, mapper) -> {
            JsonNode pending = snapshot.get("pending");
            if (pending == null || !pending.isArray() || pending.isEmpty()) {
                return false;
            }
            ObjectNode first = (ObjectNode) pending.get(0);
            if (!first.hasNonNull("dueTimeMillis")) {
                return false;
            }
            first.put("dueTimeMillis", first.get("dueTimeMillis").asLong() + 1L);
            return true;
        });
        assertDomainRejection(target, "待办");
    }

    // ------------------------------------------------------------------ 工具

    /** 在额外公司董事会节点里找到第一条预算记录并把上限改大，直接改「预算」本身。 */
    private static boolean mutateFirstBudgetLeaf(ObjectNode container) {
        JsonNode budgets = findFirst(container, "budgets");
        if (budgets == null || !budgets.isArray() || budgets.isEmpty()) {
            return false;
        }
        ObjectNode entry = (ObjectNode) budgets.get(0);
        for (String field : List.of("cap", "limit", "newLimit", "amount")) {
            JsonNode value = entry.get(field);
            if (value != null && value.isNumber()) {
                entry.put(field, new java.math.BigDecimal("100000.00"));
                return true;
            }
            if (value != null && value.isTextual()) {
                entry.put(field, "100000.00");
                return true;
            }
        }
        return false;
    }

    /** 深度优先找第一个名为 {@code key} 的对象或非空数组节点。 */
    private static JsonNode findFirst(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(key);
            if (direct != null && ((direct.isObject() && direct.size() > 0)
                    || (direct.isArray() && direct.size() > 0))) {
                return direct;
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode found = findFirst(entry.getValue(), key);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findFirst(child, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 建链 → 复制 → 只改最后一帧快照并重封 SHA-256。 */
    private Path tampered(String tag, SnapshotMutation mutation) throws Exception {
        Path base = dir.resolve(tag + "-base.journal");
        if (!Files.exists(base)) {
            ComposedPharmaWorldTest.buildApprovedChain(base);
        }
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
            if (!mutation.apply(snapshot, MAPPER)) {
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

    /** 攻击副本必须被领域重演拒绝；拒绝信息不得是校验和/帧头/JSON 解析失败。 */
    private void assertDomainRejection(Path attacked, String hint) {
        Exception failure = assertThrows(Exception.class, () -> ComposedPharmaScenario.open(attacked));
        String message = String.valueOf(failure.getMessage());
        assertNotNull(message);
        assertFalse(message.contains("校验和"), "不能只是校验和失败：" + message);
        assertFalse(message.contains("帧头"), "不能只是帧头失败：" + message);
        assertFalse(message.contains("不可解析"), "不能只是 JSON 解析失败：" + message);
        boolean domain = message.contains("不一致") || message.contains("重演")
                || message.contains("待办") || message.contains("现金")
                || message.contains("预算") || message.contains("研发")
                || message.contains("董事会") || message.contains("未采样")
                || message.contains("不得");
        assertTrue(domain, hint + " 的拒绝必须来自领域重演，实际：" + message);
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

    private interface SnapshotMutation {
        boolean apply(ObjectNode snapshot, ObjectMapper mapper);
    }
}
