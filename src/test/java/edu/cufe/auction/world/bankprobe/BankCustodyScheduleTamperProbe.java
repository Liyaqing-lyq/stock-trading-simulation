package edu.cufe.auction.world.bankprobe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.world.WorldExchange;
import edu.cufe.auction.world.WorldRuntime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * B7b 排程帧反例：{@code cmd:} 与 pending 的托管订单必须由原命令重演，
 * 改数量 / 改托管资金桶 / 改结果都必须被拒；control 必须能开并真实入簿。
 */
public final class BankCustodyScheduleTamperProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long T = BankCustodyFixture.T0;
    private static final String CST = BankCustodyFixture.CST;
    private static int pass;
    private static int fail;

    private BankCustodyScheduleTamperProbe() {
    }

    /**
     * 入口。
     *
     * @param args {@code <dir>}
     * @throws Exception 读写失败
     */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "_build/b7b/schedule-tamper");
        Files.createDirectories(dir);
        Path control = build(dir.resolve("control.log"));
        check("control 可开", opens(control), "control 被拒");
        Path advanced = copy(control, dir.resolve("control-advanced.log"));
        try (WorldRuntime runtime = BankCustodyFixture.open(advanced)) {
            runtime.advanceTo(T + 60_000L);
            List<WorldExchange.Order> orders = runtime.snapshot().exchange().orElseThrow()
                    .orders();
            boolean ok = orders.size() == 1 && orders.get(0).quantity() == 50L
                    && orders.get(0).fundingKey()
                    .equals(BankCustodyFixture.custodyToken(CST, "acme"));
            check("control 入簿：数量 50、托管桶 cst|acme", ok,
                    "orders=" + orders + " qty=" + (orders.isEmpty() ? "-" : orders.get(0).quantity()));
        }

        Path pendingQty = copy(control, dir.resolve("attack-pending-qty.log"));
        tamper(pendingQty, state -> {
            ArrayNode pending = pendingArray(state);
            ObjectNode task = (ObjectNode) pending.get(0);
            ObjectNode payload = (ObjectNode) MAPPER.readTree(task.get("payload").asText());
            payload.put("quantity", 10L);
            task.put("payload", payload.toString());
            return true;
        });
        check("改 pending 数量 50→10 被拒", !opens(pendingQty), "被接受");

        Path pendingFunding = copy(control, dir.resolve("attack-pending-funding.log"));
        tamper(pendingFunding, state -> {
            ArrayNode pending = pendingArray(state);
            ObjectNode task = (ObjectNode) pending.get(0);
            ObjectNode payload = (ObjectNode) MAPPER.readTree(task.get("payload").asText());
            payload.put("fundingToken", BankCustodyFixture.custodyToken(CST, "firm"));
            task.put("payload", payload.toString());
            return true;
        });
        check("改 pending 托管桶为他人被拒", !opens(pendingFunding), "被接受");

        Path commandQty = copy(control, dir.resolve("attack-command-qty.log"));
        tamper(commandQty, state -> {
            ObjectNode contents = (ObjectNode) state.get("commandContents");
            if (contents == null) {
                throw new IOException("快照没有命令正文");
            }
            var fields = contents.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String text = entry.getValue().asText();
                if (!text.contains("quantity")) {
                    continue;
                }
                ObjectNode command = (ObjectNode) MAPPER.readTree(text);
                command.put("quantity", 10L);
                contents.put(entry.getKey(), command.toString());
                return true;
            }
            throw new IOException("没有订单命令正文");
        });
        check("改命令正文数量 50→10 被拒", !opens(commandQty), "被接受");

        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static Path build(Path file) throws Exception {
        Files.deleteIfExists(file);
        try (WorldRuntime runtime = BankCustodyFixture.open(file)) {
            runtime.scheduleOrder("c-pending", "t-pending", T + 60_000L, "acme-owner",
                    "acme-cash", BankCustodyFixture.SYMBOL, Side.BUY, OrderType.LIMIT,
                    BankCustodyFixture.money("10.00"), 50L,
                    BankCustodyFixture.custodyToken(CST, "acme"));
        }
        return file;
    }

    private static boolean opens(Path file) {
        try (WorldRuntime ignored = BankCustodyFixture.open(file)) {
            return true;
        } catch (Exception rejected) {
            return false;
        }
    }

    private static Path copy(Path from, Path to) throws IOException {
        Files.write(to, Files.readAllBytes(from));
        return to;
    }

    private static ArrayNode pendingArray(ObjectNode state) throws IOException {
        ArrayNode pending = (ArrayNode) state.get("pending");
        if (pending == null || pending.isEmpty()) {
            throw new IOException("快照没有待办");
        }
        return pending;
    }

    // ------------------------------------------------------------------ 帧改写

    private interface Mutation {
        boolean apply(ObjectNode stateNode) throws IOException;
    }

    private static void tamper(Path file, Mutation mutation) throws IOException {
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
        Files.write(file, out.toByteArray());
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

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " :: " + detail);
        }
    }
}
