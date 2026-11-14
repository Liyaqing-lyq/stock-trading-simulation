package edu.cufe.auction.world.bankprobe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 帧级篡改工具（只用于负例）：只改“合法的快照银行/贷款台账”并重算帧 SHA-256。
 *
 * <p>攻击者不改世界原任务、不改命令正文，只让最终状态看起来自洽；恢复必须因为
 * “无法由原任务重算”而拒绝。control 副本不改，必须能正常打开。</p>
 */
final class BankRoleJournalTamper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BankRoleJournalTamper() {
    }

    /**
     * 篡改最后一帧的贷款台账：把某个合同的利率改掉（JSON 仍自洽）。
     *
     * @param file   世界日志
     * @param loanId 合同标识
     * @param deltaU 利率增量（定标 1e6）
     * @return 改写后的字节数
     * @throws IOException 读写或格式失败
     */
    static int tamperLoanRate(Path file, String loanId, long deltaU) throws IOException {
        return tamper(file, state -> {
            ObjectNode actions = (ObjectNode) state.get("bankActions");
            if (actions == null) {
                throw new IOException("快照没有贷款台账");
            }
            ObjectNode loans = (ObjectNode) actions.get("loans");
            if (loans == null || !loans.has(loanId)) {
                throw new IOException("贷款台账里没有合同：" + loanId);
            }
            ObjectNode loan = (ObjectNode) loans.get(loanId);
            loan.put("rateU", loan.get("rateU").asLong() + deltaU);
            return true;
        });
    }

    /**
     * 篡改最后一帧的银行账簿：在两个客户存款之间平移（银行不变量仍成立）。
     *
     * @param file 世界日志
     * @param move 平移金额（两位小数）
     * @return 改写后的字节数
     * @throws IOException 读写或格式失败
     */
    static int tamperDeposits(Path file, String move) throws IOException {
        BigDecimal amount = new BigDecimal(move).setScale(2);
        return tamper(file, state -> {
            ObjectNode bank = (ObjectNode) state.get("bank");
            if (bank == null) {
                throw new IOException("快照没有银行三态");
            }
            ObjectNode bankState = (ObjectNode) MAPPER.readTree(bank.get("stateJson").asText());
            ObjectNode north = (ObjectNode) ((ObjectNode) bankState.get("glByBank")).get("north");
            BigDecimal acme = new BigDecimal(north.get("dep-acme").asText());
            BigDecimal firm = north.has("dep-firm") ? new BigDecimal(north.get("dep-firm").asText())
                    : BigDecimal.ZERO.setScale(2);
            north.put("dep-acme", acme.add(amount).toPlainString());
            north.put("dep-firm", firm.subtract(amount).toPlainString());
            bank.put("stateJson", bankState.toString());
            return true;
        });
    }

    /**
     * 自洽篡改：把某合同的台账本金减 100、同时给某现金账户加 100，并重算帧 SHA。
     *
     * <p>不改世界原任务与命令，只让“看起来自洽”的快照与真实期次/原任务分叉，用于证明
     * 恢复不是只比自身一致性。</p>
     *
     * @param file      世界日志
     * @param loanId    合同标识
     * @param amount    平移金额（两位小数）
     * @param cashAccount 要加钱的现金账户
     * @return 改写后的字节数
     * @throws IOException 读写或格式失败
     */
    static int tamperBookAndCash(Path file, String loanId, String amount, String cashAccount)
            throws IOException {
        BigDecimal move = new BigDecimal(amount).setScale(2);
        return tamper(file, state -> {
            ObjectNode actions = (ObjectNode) state.get("bankActions");
            if (actions == null) {
                throw new IOException("快照没有贷款台账");
            }
            ObjectNode loans = (ObjectNode) actions.get("loans");
            if (loans == null || !loans.has(loanId)) {
                throw new IOException("贷款台账里没有合同：" + loanId);
            }
            ObjectNode loan = (ObjectNode) loans.get(loanId);
            BigDecimal drawn = new BigDecimal(loan.get("drawn").asText());
            loan.put("drawn", drawn.subtract(move).toPlainString());
            ObjectNode accounts = (ObjectNode) state.get("accounts");
            ObjectNode account = (ObjectNode) accounts.get(cashAccount);
            if (account == null) {
                throw new IOException("快照没有现金账户：" + cashAccount);
            }
            BigDecimal cash = new BigDecimal(account.get("cash").asText());
            account.put("cash", cash.add(move).toPlainString());
            return true;
        });
    }

    /**
     * 篡改最后一帧“排程命令正文”里的一个字段（保留真实待办与命令标识）。
     *
     * @param file        世界日志
     * @param needle      命令正文里的原文
     * @param replacement 替换文本
     * @return 改写后的字节数
     * @throws IOException 读写或格式失败
     */
    static int tamperScheduleContent(Path file, String needle, String replacement)
            throws IOException {
        return tamperLastCommand(file, needle, replacement, true);
    }

    /**
     * 篡改最后一帧排程事件里 {@code TASK_SCHEDULED_V1} 的待办参数。
     *
     * @param file        世界日志
     * @param needle      待办参数里的原文
     * @param replacement 替换文本
     * @return 改写后的字节数
     * @throws IOException 读写或格式失败
     */
    static int tamperSchedulePayload(Path file, String needle, String replacement)
            throws IOException {
        return tamperLastCommand(file, needle, replacement, false);
    }

    private static int tamperLastCommand(Path file, String needle, String replacement,
                                         boolean content) throws IOException {
        byte[] all = Files.readAllBytes(file);
        List<byte[]> frames = readFrames(all);
        int last = -1;
        for (int index = frames.size() - 1; index >= 0; index--) {
            ObjectNode commit = (ObjectNode) MAPPER.readTree(frames.get(index));
            if (commit.path("commandId").asText().startsWith("cmd:")) {
                last = index;
                break;
            }
        }
        if (last < 0) {
            throw new IOException("日志没有排程帧");
        }
        ObjectNode commit = (ObjectNode) MAPPER.readTree(frames.get(last));
        boolean changed = false;
        if (content) {
            String text = commit.path("commandContent").asText();
            String next = text.replace(needle, replacement);
            if (!next.equals(text)) {
                commit.put("commandContent", next);
                changed = true;
            }
        } else {
            ArrayNode events = (ArrayNode) commit.get("events");
            for (int index = 0; index < events.size(); index++) {
                ObjectNode event = (ObjectNode) events.get(index);
                if (!"TASK_SCHEDULED_V1".equals(event.path("type").asText())) {
                    continue;
                }
                // 待办事件正文是 PendingTask 的 JSON，其 payload 字段又是一层 JSON 文本。
                ObjectNode task = (ObjectNode) MAPPER.readTree(event.path("payload").asText());
                String body = task.path("payload").asText();
                String next = body.replace(needle, replacement);
                if (!next.equals(body)) {
                    task.put("payload", next);
                    event.put("payload", task.toString());
                    changed = true;
                }
                break;
            }
        }
        if (!changed) {
            throw new IOException("篡改未命中：" + needle);
        }
        frames.set(last, commit.toString().getBytes(StandardCharsets.UTF_8));
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

    private interface Mutation {
        boolean apply(ObjectNode stateNode) throws IOException;
    }

    private static int tamper(Path file, Mutation mutation) throws IOException {
        byte[] all = Files.readAllBytes(file);
        List<byte[]> frames = readFrames(all);
        if (frames.isEmpty()) {
            throw new IOException("日志没有完整帧");
        }
        int last = frames.size() - 1;
        frames.set(last, mutate(frames.get(last), mutation));
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

    private static List<byte[]> readFrames(byte[] all) {
        List<byte[]> frames = new ArrayList<>();
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

    private static byte[] mutate(byte[] payload, Mutation mutation) throws IOException {
        ObjectNode commit = (ObjectNode) MAPPER.readTree(payload);
        ArrayNode events = (ArrayNode) commit.get("events");
        for (int index = 0; index < events.size(); index++) {
            ObjectNode event = (ObjectNode) events.get(index);
            if (!"STATE_SNAPSHOT_V1".equals(event.get("type").asText())) {
                continue;
            }
            ObjectNode state = (ObjectNode) MAPPER.readTree(event.get("payload").asText());
            if (!mutation.apply(state)) {
                throw new IOException("篡改未生效");
            }
            event.put("payload", state.toString());
            return commit.toString().getBytes(StandardCharsets.UTF_8);
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
