package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 治理历史篡改探针：在任免会议之后的非工程批次里，自洽重算每一帧长度与 SHA，
 * 给会议追加未真实发生的到会/回避/状态/回报变化；恢复必须拒绝。
 *
 * <p>这是独审 D2 的最小反例：真实到会只有 s1/s2，从某条 clock 起把所有后续快照
 * 一致改成含 s3，不绑定任何真实 BOARD 命令或事件。修复后重开应抛 IOException。</p>
 */
public final class GovernanceTamperProbe {

    /** 篡改种类：到会、回避、伪造实施回执、只改第一帧对照。 */
    public enum Kind {
        /** 追加未真实到会席位。 */
        ATTENDANCE,
        /** 追加未真实回避席位（同时补到会以满足“回避是到会子集”）。 */
        RECUSAL,
        /** 只改第一帧（对照：应被下一帧拒）。 */
        SINGLE_FRAME
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GovernanceTamperProbe() {
    }

    /**
     * 生成真实日志并按 kind 篡改，然后重开打印结果。
     *
     * @param args 可选输出目录
     * @throws Exception 构建失败
     */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/fix5-probe");
        Files.createDirectories(dir);
        for (Kind kind : Kind.values()) {
            Path file = dir.resolve("tamper-" + kind.name().toLowerCase() + ".journal");
            Files.deleteIfExists(file);
            build(file);
            System.out.println(kind + " BEFORE=" + describe(file));
            boolean mutated = tamper(file, kind);
            System.out.println(kind + " MUTATED=" + mutated);
            try (WorldRuntime reopened = WorldWebScenario.openEngineering(file).runtime()) {
                BoardModel.Meeting meeting = reopened.boardOfCompany("cufe").orElseThrow()
                        .board().meeting("appt-1");
                System.out.println(kind + " REOPEN=ACCEPTED status=" + meeting.status()
                        + " attendees=" + meeting.attendeeSeatIds()
                        + " recused=" + meeting.recusedSeatIds());
            } catch (Exception rejected) {
                System.out.println(kind + " REOPEN=REJECTED "
                        + rejected.getClass().getSimpleName() + ": " + rejected.getMessage());
            }
        }
        System.out.println("PROBE_DONE");
    }

    /** 真实开局：召集任免会议 + 真实 s1/s2 到会 + 之后若干非工程批次。 */
    static void build(Path file) throws Exception {
        WorldRuntime world = WorldWebScenario.openEngineering(file).runtime();
        try {
            world.advanceTo(200L);
            world.scheduleEngineeringAppointmentConvene("c", "c-t", 300L, "cufe", "alice",
                    "appt-1", 350L, 400L, 500L);
            world.scheduleEngineeringAttend("a1", "a1-t", 310L, "cufe", "alice", "appt-1", false);
            world.scheduleEngineeringAttend("a2", "a2-t", 320L, "cufe", "bob", "appt-1", false);
            world.advanceTo(350L);
            world.scheduleCapitalFlow("cap", "cap-t", 360L, "fund-a", "ACC-FUND", "ENG-A",
                    new BigDecimal("10.00"), 0L, "fix5-probe");
            world.advanceTo(400L);
            world.advanceTo(450L);
        } finally {
            world.close();
        }
    }

    private static String describe(Path file) throws Exception {
        try (WorldRuntime world = WorldWebScenario.openEngineering(file).runtime()) {
            BoardModel.Meeting meeting = world.boardOfCompany("cufe").orElseThrow()
                    .board().meeting("appt-1");
            return meeting.status() + " attendees=" + meeting.attendeeSeatIds()
                    + " recused=" + meeting.recusedSeatIds();
        }
    }

    /**
     * 从第一条 clock 起（或只改第一帧）一致重写快照并重算 SHA。
     *
     * @return 是否真的改到内容
     */
    static boolean tamper(Path file, Kind kind) throws Exception {
        byte[] all = Files.readAllBytes(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        boolean started = false;
        boolean mutatedAny = false;
        while (offset < all.length) {
            int length = ByteBuffer.wrap(all, offset, 4).getInt();
            byte[] payload = Arrays.copyOfRange(all, offset + 8, offset + 8 + length);
            byte[] digest = Arrays.copyOfRange(all, offset + 8 + length, offset + 8 + length + 32);
            JsonNode commit = MAPPER.readTree(payload);
            String commandId = commit.path("commandId").asText();
            // 只从“任免会后”的非工程时钟批开始：350 之前含会议创建/真实到会批次，
            // 那些批次会被既有工程重演单独拒，不能证明 D2 的跨帧一致篡改。
            if ("clock:350".equals(commandId)) {
                started = true;
            }
            boolean shouldTamper = started && (kind != Kind.SINGLE_FRAME || !mutatedAny);
            if (shouldTamper) {
                boolean mutated = false;
                for (JsonNode event : (ArrayNode) commit.path("events")) {
                    if (!"STATE_SNAPSHOT_V1".equals(event.path("type").asText())) {
                        continue;
                    }
                    String stateJson = event.path("payload").asText();
                    String tampered = tamperState(stateJson, kind);
                    if (!tampered.equals(stateJson)) {
                        ((ObjectNode) event).put("payload", tampered);
                        mutated = true;
                    }
                }
                if (mutated) {
                    payload = MAPPER.writeValueAsBytes(commit);
                    digest = MessageDigest.getInstance("SHA-256").digest(payload);
                    mutatedAny = true;
                }
            }
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length)
                    .array());
            out.write(payload);
            out.write(digest);
            offset += 8 + length + 32;
        }
        Files.write(file, out.toByteArray());
        return mutatedAny;
    }

    /** 只改快照字符串里的定点片段；其它字节保持不变。 */
    private static String tamperState(String stateJson, Kind kind) {
        int[] scope = objectScope(stateJson, "\"appt-1\":{");
        if (scope == null) {
            return stateJson;
        }
        String meeting = stateJson.substring(scope[0], scope[1]);
        String tamperedMeeting = meeting;
        switch (kind) {
            case ATTENDANCE, SINGLE_FRAME -> tamperedMeeting = meeting.replace(
                    "\"attendeeSeatIds\":[\"s1\",\"s2\"]",
                    "\"attendeeSeatIds\":[\"s1\",\"s2\",\"s3\"]");
            case RECUSAL -> tamperedMeeting = meeting
                    .replace("\"attendeeSeatIds\":[\"s1\",\"s2\"]",
                            "\"attendeeSeatIds\":[\"s1\",\"s2\",\"s3\"]")
                    .replace("\"recusedSeatIds\":[]", "\"recusedSeatIds\":[\"s3\"]");
            default -> {
                return stateJson;
            }
        }
        if (tamperedMeeting.equals(meeting)) {
            return stateJson;
        }
        return stateJson.substring(0, scope[0]) + tamperedMeeting + stateJson.substring(scope[1]);
    }

    /** 找到 key 后紧跟对象的位置，返回其花括号范围 [start,end)（含字符串转义处理）。 */
    private static int[] objectScope(String json, String key) {
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return null;
        }
        int start = keyIndex + key.length() - 1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < json.length(); index++) {
            char symbol = json.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (symbol == '\\') {
                    escaped = true;
                } else if (symbol == '"') {
                    inString = false;
                }
                continue;
            }
            if (symbol == '"') {
                inString = true;
            } else if (symbol == '{') {
                depth++;
            } else if (symbol == '}') {
                depth--;
                if (depth == 0) {
                    return new int[]{start, index + 1};
                }
            }
        }
        return null;
    }
}
