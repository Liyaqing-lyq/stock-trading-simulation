package edu.cufe.auction.world;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 测试用日志改写器：按真实帧格式解出 payload，做一处语义篡改后重算帧 SHA 并整体写回。
 *
 * <p>只用于验证"重算 SHA 的自洽篡改仍被真实命令重演拒绝"，不参与生产路径。</p>
 */
final class JournalTamper {

    private JournalTamper() {
    }

    /** 一帧的 payload。 */
    record Frame(byte[] payload) {
    }

    static List<Frame> frames(Path file) throws Exception {
        byte[] all = Files.readAllBytes(file);
        List<Frame> frames = new ArrayList<>();
        int offset = 0;
        while (offset < all.length) {
            int length = ByteBuffer.wrap(all, offset, 4).getInt();
            offset += 8;
            byte[] payload = new byte[length];
            System.arraycopy(all, offset, payload, 0, length);
            offset += length + 32;
            frames.add(new Frame(payload));
        }
        return frames;
    }

    static int lastIndexContaining(Path file, String needle) throws Exception {
        List<Frame> frames = frames(file);
        for (int i = frames.size() - 1; i >= 0; i--) {
            String json = new String(frames.get(i).payload(), StandardCharsets.UTF_8);
            if (json.contains(needle)) {
                return i;
            }
        }
        throw new IllegalStateException("日志中没有包含 " + needle + " 的帧");
    }

    /** 对指定帧做一处篡改，重算该帧 SHA 后整体写回；mutation 未改变内容视为测试错误。 */
    static void rewrite(Path file, int index, UnaryOperator<String> mutation) throws Exception {
        List<Frame> frames = frames(file);
        String json = new String(frames.get(index).payload(), StandardCharsets.UTF_8);
        String mutated = mutation.apply(json);
        if (mutated.equals(json)) {
            throw new IllegalStateException("篡改未生效：帧 " + index);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < frames.size(); i++) {
            byte[] payload = i == index ? mutated.getBytes(StandardCharsets.UTF_8)
                    : frames.get(i).payload();
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length)
                    .array());
            out.write(payload);
            out.write(MessageDigest.getInstance("SHA-256").digest(payload));
        }
        Files.write(file, out.toByteArray());
    }

    /** 把 64 位十六进制摘要换成全 f（仍是合法摘要形状）。 */
    static String replaceDigest(String json, String digest) {
        String replacement = HexFormat.of().formatHex(new byte[32]).replace('0', 'f');
        return json.replace(digest, replacement);
    }

    /**
     * 改写某帧内最后一个 STATE_SNAPSHOT_V1 事件的<b>内部状态 JSON</b>，再重算帧 SHA。
     *
     * <p>快照事件把整份状态作为字符串负载，直接对帧文本做转义替换既脆弱又容易漏改；
     * 这里解出内层 JSON、按语义改一处，再放回同一提交，模拟"自洽篡改"。</p>
     */
    static void rewriteSnapshot(Path file, int index, UnaryOperator<String> mutation)
            throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        List<Frame> frames = frames(file);
        com.fasterxml.jackson.databind.node.ObjectNode commit =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                        frames.get(index).payload());
        com.fasterxml.jackson.databind.node.ArrayNode events =
                (com.fasterxml.jackson.databind.node.ArrayNode) commit.get("events");
        int target = -1;
        for (int i = 0; i < events.size(); i++) {
            if ("STATE_SNAPSHOT_V1".equals(events.get(i).get("type").asText())) {
                target = i;
            }
        }
        if (target < 0) {
            throw new IllegalStateException("帧 " + index + " 没有状态快照事件");
        }
        String inner = events.get(target).get("payload").asText();
        String mutated = mutation.apply(inner);
        if (mutated.equals(inner)) {
            throw new IllegalStateException("快照篡改未生效：帧 " + index);
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) events.get(target))
                .put("payload", mutated);
        byte[] payload = mapper.writeValueAsBytes(commit);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < frames.size(); i++) {
            byte[] bytes = i == index ? payload : frames.get(i).payload();
            out.write(ByteBuffer.allocate(8).putInt(bytes.length).putInt(~bytes.length).array());
            out.write(bytes);
            out.write(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        Files.write(file, out.toByteArray());
    }
}
