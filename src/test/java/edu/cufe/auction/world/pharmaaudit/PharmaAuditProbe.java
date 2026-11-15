package edu.cufe.auction.world.pharmaaudit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedPharmaScenario;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 独审探针（不是测试）：打印新 Debug 药企档「额外公司董事完成帧」的快照结构，
 * 供反例测试精确改写字段。用法：{@code <journal> <outDir>}。
 */
public final class PharmaAuditProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PharmaAuditProbe() {
    }

    /**
     * @param args 日志路径、输出目录
     * @throws Exception 打开或排程失败
     */
    public static void main(String[] args) throws Exception {
        Path journal = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        try (WorldRuntime world = ComposedPharmaScenario.openDebug(journal)) {
            long due = PharmaAuditHarness.reply(world,
                    PharmaAuditHarness.open(world, "frank", "probe"),
                    "board.convene", Map.of("companyPartyId", "pharma",
                            "meetingId", "probe-meeting", "topic", "MANAGER_APPOINTMENT",
                            "materialDeadline", 20_000, "opensAt", 30_000, "closesAt", 60_000));
            world.advanceTo(due);
            for (var ref : world.snapshot().decisions().orElseThrow().actionRefs().values()) {
                System.out.println("ACTION=" + ref.actionId() + " task=" + ref.taskId()
                        + " due=" + ref.dueMillis());
            }
            for (var task : world.snapshot().pending()) {
                System.out.println("PENDING=" + task.taskId() + " kind=" + task.kind()
                        + " due=" + task.dueTimeMillis());
            }
            System.out.println("ENGINEERING_REPORTS=" + world.snapshot().engineering()
                    .orElseThrow().reports());
            System.out.println("MEETINGS=" + world.boardOfCompany("pharma").orElseThrow()
                    .board().meetings().keySet());
        }
        {
            JsonNode snapshot = lastSnapshot(journal);
            Files.writeString(out.resolve("snapshot-keys.txt"),
                    String.join("\n", iterable(snapshot.fieldNames())));
            Files.writeString(out.resolve("snapshot-engineering.json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                            snapshot.path("engineering")));
            Files.writeString(out.resolve("snapshot-company-governance.json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                            snapshot.path("companyGovernance")));
            Files.writeString(out.resolve("snapshot-full.json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
        }
    }

    private static List<String> iterable(java.util.Iterator<String> names) {
        List<String> all = new ArrayList<>();
        names.forEachRemaining(all::add);
        return all;
    }

    /** 读最后一帧里的 STATE_SNAPSHOT_V1 负载。 */
    static JsonNode lastSnapshot(Path file) throws Exception {
        List<byte[]> frames = readFrames(file);
        ObjectNode batch = (ObjectNode) MAPPER.readTree(frames.get(frames.size() - 1));
        JsonNode found = null;
        for (JsonNode event : batch.get("events")) {
            if ("STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                found = MAPPER.readTree(event.get("payload").asText());
            }
        }
        if (found == null) {
            throw new IllegalStateException("最后一帧没有 STATE_SNAPSHOT_V1");
        }
        return found;
    }

    static List<byte[]> readFrames(Path file) throws Exception {
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
}
