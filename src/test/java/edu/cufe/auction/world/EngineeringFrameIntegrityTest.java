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

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * F1：无工程动作的帧不得凭自洽 snapshot 改写工程子集（initialEquity/initialWorldCash/board/合同）。
 *
 * <p>测试直接重写真实 journal 的最后一帧并重算帧摘要，模拟“自洽篡改”；重开必须拒绝。
 * 首帧篡改同样拒绝（开局精确绑定）。</p>
 */
class EngineeringFrameIntegrityTest {

    private static Path journal(String name) throws IOException {
        Path dir = Path.of("target", "engineering-frame-test");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        return file;
    }

    private static Path baseline() throws Exception {
        Path file = journal("baseline");
        WorldRuntime runtime = WorldWebScenario.openEngineering(file).runtime();
        runtime.scheduleCapitalFlow("cap-f1", "cap-f1-task", 1_000L, "fund-a",
                "ACC-FUND", "INV", new BigDecimal("10.00"), 0L, "F1 基线");
        runtime.advanceTo(2_000L);
        runtime.close();
        return file;
    }

    @Test
    void laterFrameTamperingRejected() throws Exception {
        Path base = baseline();
        for (String field : List.of("initialEquity", "initialWorldCash", "board", "contract")) {
            Path variant = journal("tamper-" + field);
            Files.copy(base, variant);
            tamperLastSnapshot(variant, field);
            assertThrows(IOException.class, () -> WorldWebScenario.openEngineering(variant),
                    "自洽篡改必须拒绝：" + field);
        }
    }

    @Test
    void openingFrameTamperingRejected() throws Exception {
        Path base = baseline();
        Path variant = journal("tamper-opening");
        Files.copy(base, variant);
        tamperSnapshotAt(variant, 0, "initialEquity");
        assertThrows(IOException.class, () -> WorldWebScenario.openEngineering(variant));
    }

    private static void tamperLastSnapshot(Path file, String field) throws IOException {
        tamperSnapshotAt(file, -1, field);
    }

    private static void tamperSnapshotAt(Path file, int frameIndex, String field) throws IOException {
        List<byte[]> frames = readFrames(file);
        int index = frameIndex < 0 ? frames.size() - 1 : frameIndex;
        if (index < 0 || index >= frames.size()) {
            throw new IllegalArgumentException("帧不存在：" + index);
        }
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode batch = (ObjectNode) mapper.readTree(frames.get(index));
        ArrayNode events = (ArrayNode) batch.get("events");
        boolean touched = false;
        for (JsonNode event : events) {
            if (!"STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                continue;
            }
            ObjectNode snapshot = (ObjectNode) mapper.readTree(event.get("payload").asText());
            ObjectNode engineering = (ObjectNode) snapshot.get("engineering");
            if (engineering == null) {
                throw new IllegalArgumentException("该帧没有工程子集");
            }
            switch (field) {
                case "initialEquity" -> ((ObjectNode) engineering.get("companies").get("eng-a"))
                        .put("initialEquity", new BigDecimal("1.00"));
                case "initialWorldCash" -> ((ObjectNode) engineering.get("companies").get("eng-a"))
                        .put("initialWorldCash", new BigDecimal("1.00"));
                case "board" -> ((ObjectNode) engineering.get("boards").get("eng-a")
                        .get("board").get("charter").get("seats").get(0))
                        .put("partyId", "nina");
                case "contract" -> ((ObjectNode) engineering.get("config").get("companies")
                        .get(0).get("contracts").get(0)).put("unitPrice", new BigDecimal("999.00"));
                default -> throw new IllegalArgumentException("未知字段：" + field);
            }
            ((ObjectNode) event).put("payload", mapper.writeValueAsString(snapshot));
            touched = true;
        }
        if (!touched) {
            throw new IllegalArgumentException("该帧没有状态快照");
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
