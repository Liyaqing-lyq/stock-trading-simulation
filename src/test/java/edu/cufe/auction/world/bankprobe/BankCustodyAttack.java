package edu.cufe.auction.world.bankprobe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.WorldRuntime;

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
 * B7b 帧级篡改：只改快照里“看起来自洽”的托管明细/成交价/订单结果，并重算帧 SHA-256。
 *
 * <p>{@code build} 造一个真实托管成交运行 + 三份篡改副本；{@code reopen} 逐个重开，
 * control 必须 {@code OPENED}、三份篡改必须 {@code REJECTED}（必须由“原命令与上一帧重算”
 * 发现，而不是靠 JSON 结构或摘要）。</p>
 */
public final class BankCustodyAttack {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long T = BankCustodyFixture.T0;
    private static final String CST = BankCustodyFixture.CST;

    private BankCustodyAttack() {
    }

    /**
     * 入口。
     *
     * @param args {@code build <dir>} 或 {@code reopen <file>...}
     * @throws Exception 读写失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("用法：build <dir> | reopen <file>...");
        }
        if ("build".equals(args[0])) {
            build(Path.of(args[1]));
            return;
        }
        if ("reopen".equals(args[0])) {
            for (int index = 1; index < args.length; index++) {
                Path file = Path.of(args[index]);
                String verdict;
                try (WorldRuntime ignored = BankCustodyFixture.open(file)) {
                    verdict = "OPENED";
                } catch (Exception rejected) {
                    verdict = "REJECTED";
                }
                System.out.println(file.getFileName() + " => " + verdict);
            }
            return;
        }
        throw new IllegalArgumentException("未知模式：" + args[0]);
    }

    private static void build(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path control = dir.resolve("control.log");
        Files.deleteIfExists(control);
        try (WorldRuntime runtime = BankCustodyFixture.open(control)) {
            runtime.scheduleOrder("c-sell", "t-sell", T + 60_000L, "firm-owner", "firm-cash",
                    BankCustodyFixture.SYMBOL, Side.SELL, OrderType.LIMIT,
                    BankCustodyFixture.money("10.00"), 40L,
                    BankCustodyFixture.custodyToken(CST, "firm"));
            runtime.scheduleOrder("c-buy", "t-buy", T + 120_000L, "acme-owner", "acme-cash",
                    BankCustodyFixture.SYMBOL, Side.BUY, OrderType.LIMIT,
                    BankCustodyFixture.money("10.20"), 30L,
                    BankCustodyFixture.custodyToken(CST, "acme"));
            runtime.advanceTo(T + 120_000L);
        }
        copy(control, dir.resolve("attack-custody.log"));
        copy(control, dir.resolve("attack-trade.log"));
        copy(control, dir.resolve("attack-report.log"));
        tamper(dir.resolve("attack-custody.log"), state -> {
            ObjectNode bank = (ObjectNode) state.get("bank");
            if (bank == null) {
                throw new IOException("快照没有银行三态");
            }
            ObjectNode custody = (ObjectNode) MAPPER.readTree(bank.get("custodyJson").asText());
            ObjectNode details = (ObjectNode) custody.get("details");
            BigDecimal acme = new BigDecimal(details.get(CST + "|acme").asText());
            BigDecimal firm = new BigDecimal(details.get(CST + "|firm").asText());
            details.put(CST + "|acme", acme.subtract(new BigDecimal("100.00")).toPlainString());
            details.put(CST + "|firm", firm.add(new BigDecimal("100.00")).toPlainString());
            bank.put("custodyJson", custody.toString());
            return true;
        });
        tamper(dir.resolve("attack-trade.log"), state -> {
            ObjectNode exchange = (ObjectNode) state.get("exchange");
            if (exchange == null) {
                throw new IOException("快照没有订单子集");
            }
            for (var node : (ArrayNode) exchange.get("trades")) {
                ObjectNode trade = (ObjectNode) node;
                trade.put("price", BankCustodyFixture.money("10.50").toPlainString());
                return true;
            }
            throw new IOException("快照没有成交");
        });
        tamper(dir.resolve("attack-report.log"), state -> {
            ObjectNode exchange = (ObjectNode) state.get("exchange");
            if (exchange == null) {
                throw new IOException("快照没有订单子集");
            }
            ObjectNode reports = (ObjectNode) exchange.get("reports");
            if (reports == null || reports.isEmpty()) {
                throw new IOException("快照没有订单结果");
            }
            var fields = reports.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                ObjectNode report = (ObjectNode) entry.getValue();
                report.put("reason", "INSUFFICIENT_FUNDS");
                return true;
            }
            throw new IOException("快照没有订单结果");
        });
        System.out.println("BUILD=OK dir=" + dir);
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.write(to, Files.readAllBytes(from));
    }

    // ------------------------------------------------------------------ 帧改写

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
