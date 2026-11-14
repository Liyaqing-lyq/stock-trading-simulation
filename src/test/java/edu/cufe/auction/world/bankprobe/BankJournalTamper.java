package edu.cufe.auction.world.bankprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * 帧级篡改工具（只用于负例）：把某帧快照里的银行 JSON 改成“自洽但不同”，并按日志格式重算校验和。
 *
 * <p>目的是证明恢复不是只比结构：即使攻击者能让帧通过世界日志自带的 SHA-256 与 JSON 校验，
 * 银行三态也会因为无法由原命令重算而被拒绝。</p>
 */
final class BankJournalTamper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BankJournalTamper() {
    }

    /**
     * 篡改最后一帧：把快照里 bank.stateJson 的某个 origin 来源改成 TAMPERED。
     *
     * @param file 世界日志
     * @return 被改写的字节数
     * @throws IOException 读写或格式失败
     */
    static int tamperLastSnapshotBankOrigin(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        java.util.List<byte[]> frames = new java.util.ArrayList<>();
        int offset = 0;
        while (offset + 8 <= all.length) {
            ByteBuffer header = ByteBuffer.wrap(all, offset, 8);
            int length = header.getInt();
            int check = header.getInt();
            if (length <= 0 || check != ~length || all.length - offset - 8 < length + 32) {
                break;
            }
            byte[] payload = new byte[length];
            System.arraycopy(all, offset + 8, payload, 0, length);
            frames.add(payload);
            offset += 8 + length + 32;
        }
        if (frames.isEmpty()) {
            throw new IOException("日志没有完整帧");
        }
        frames.set(frames.size() - 1, mutate(frames.get(frames.size() - 1)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] payload : frames) {
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length).array());
            out.write(payload);
            out.write(sha(payload));
        }
        byte[] result = out.toByteArray();
        Files.write(file, result);
        return result.length;
    }

    /**
     * 针对“bank: 前缀豁免”的攻击：把最后一帧的 commandId 改成 {@code bank:probe}（内容仍是原
     * 合法请求），同时把银行账簿里两个客户存款自洽挪动 100、并伪造一条 accepted 的银行回执，
     * 最后重算帧 SHA-256。经济上仍满足银行不变量与现金背书，只有“必须由原命令重算”能拦。
     *
     * @param file      世界日志
     * @param commandId 伪造的提交标识（通常 {@code bank:probe}）
     * @return 改写后的文件字节数
     * @throws IOException 读写或格式失败
     */
    static int tamperDepositAndBankCommand(Path file, String commandId) throws IOException {
        byte[] all = Files.readAllBytes(file);
        java.util.List<byte[]> frames = readFrames(all);
        if (frames.isEmpty()) {
            throw new IOException("日志没有完整帧");
        }
        frames.set(frames.size() - 1, mutateDeposit(frames.get(frames.size() - 1), commandId));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] payload : frames) {
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length).array());
            out.write(payload);
            out.write(sha(payload));
        }
        byte[] result = out.toByteArray();
        Files.write(file, result);
        return result.length;
    }

    private static java.util.List<byte[]> readFrames(byte[] all) {
        java.util.List<byte[]> frames = new java.util.ArrayList<>();
        int offset = 0;
        while (offset + 8 <= all.length) {
            ByteBuffer header = ByteBuffer.wrap(all, offset, 8);
            int length = header.getInt();
            int check = header.getInt();
            if (length <= 0 || check != ~length || all.length - offset - 8 < length + 32) {
                break;
            }
            byte[] payload = new byte[length];
            System.arraycopy(all, offset + 8, payload, 0, length);
            frames.add(payload);
            offset += 8 + length + 32;
        }
        return frames;
    }

    private static byte[] mutateDeposit(byte[] payload, String commandId) throws IOException {
        ObjectNode commit = (ObjectNode) MAPPER.readTree(payload);
        commit.put("commandId", commandId);
        ArrayNode events = (ArrayNode) commit.get("events");
        for (int index = 0; index < events.size(); index++) {
            ObjectNode event = (ObjectNode) events.get(index);
            if (!"STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                continue;
            }
            ObjectNode state = (ObjectNode) MAPPER.readTree(event.get("payload").asText());
            ObjectNode bank = (ObjectNode) state.get("bank");
            if (bank == null) {
                throw new IOException("快照没有银行三态");
            }
            ObjectNode bankState = (ObjectNode) MAPPER.readTree(bank.get("stateJson").asText());
            ObjectNode north = (ObjectNode) ((ObjectNode) bankState.get("glByBank")).get("north");
            java.math.BigDecimal acme = new java.math.BigDecimal(
                    north.get("dep-acme").asText());
            java.math.BigDecimal firm = north.has("dep-firm")
                    ? new java.math.BigDecimal(north.get("dep-firm").asText())
                    : java.math.BigDecimal.ZERO.setScale(2);
            north.put("dep-acme", acme.add(TAMPER_MOVE).toPlainString());
            north.put("dep-firm", firm.subtract(TAMPER_MOVE).toPlainString());
            ObjectNode commands = (ObjectNode) bankState.get("commands");
            ObjectNode record = MAPPER.createObjectNode();
            record.put("body", "PAYMENT\namount=100.00\ndst=DEPOSIT|north|firm\npayer=firm\n"
                    + "src=DEPOSIT|north|acme");
            record.put("receipt", "accepted=true;reason=ACCEPTED");
            record.put("accepted", true);
            commands.set(commandId, record);
            bank.put("stateJson", bankState.toString());
            event.put("payload", state.toString());
            return commit.toString().getBytes(StandardCharsets.UTF_8);
        }
        throw new IOException("没有快照事件");
    }

    private static final java.math.BigDecimal TAMPER_MOVE =
            new java.math.BigDecimal("100.00").setScale(2);

    private static byte[] mutate(byte[] payload) throws IOException {
        ObjectNode commit = (ObjectNode) MAPPER.readTree(payload);
        ArrayNode events = (ArrayNode) commit.get("events");
        for (int index = 0; index < events.size(); index++) {
            ObjectNode event = (ObjectNode) events.get(index);
            if (!"STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                continue;
            }
            ObjectNode state = (ObjectNode) MAPPER.readTree(event.get("payload").asText());
            ObjectNode bank = (ObjectNode) state.get("bank");
            if (bank == null) {
                throw new IOException("快照没有银行三态");
            }
            ObjectNode bankState = (ObjectNode) MAPPER.readTree(bank.get("stateJson").asText());
            ObjectNode origins = (ObjectNode) bankState.get("origins");
            if (origins == null || origins.isEmpty()) {
                throw new IOException("银行账簿没有可篡改的 origin");
            }
            String key = origins.fieldNames().next();
            ((ObjectNode) origins.get(key)).put("source", "TAMPERED");
            bank.put("stateJson", bankState.toString());
            event.put("payload", state.toString());
            return commit.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new IOException("没有快照事件");
    }

    private static byte[] sha(byte[] payload) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (Exception failure) {
            throw new IOException("缺少 SHA-256", failure);
        }
    }
}
