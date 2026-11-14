package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 独审 D4 探针：在真实 {@code task:mfg-close:1}（MANUFACTURING_PERIOD_CLOSE_V1，工程档案里
 * 不产生治理变化）及其后所有快照里，只改 snapshot + 帧 SHA，给 CONVENED 的 cufe 任免会议
 * 插入无因 appointmentProposal（或伪造一条治理回报）；恢复必须拒绝。
 *
 * <p>事件、命令原文和原待办都不改；这是“白名单命中即放行”被绕过的真实反例。</p>
 */
public final class GovernanceProposalTamperProbe {

    /** 篡改种类：无因任免提案、伪造治理回报。 */
    public enum Kind {
        /** 给 CONVENED 任免会议插入合法构造的 appointmentProposal。 */
        PROPOSAL,
        /** 给制造关期批次伪造一条“已接受”的治理回报。 */
        REPORT
    }

    private static final long P = PeriodicModel.PERIOD_MILLIS;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GovernanceProposalTamperProbe() {
    }

    /**
     * 构造真实日志、按 kind 篡改并打印重开结果。
     *
     * @param args 可选输出目录
     * @throws Exception 构建失败
     */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/fix6-probe");
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
                        .board().meeting("appt-2");
                System.out.println(kind + " REOPEN=ACCEPTED status=" + meeting.status()
                        + " proposal=" + (meeting.appointmentProposal() == null
                        ? "null" : "present")
                        + " reports=" + reopened.boardOfCompany("cufe").orElseThrow()
                        .reports().size());
            } catch (Exception rejected) {
                System.out.println(kind + " REOPEN=REJECTED "
                        + rejected.getClass().getSimpleName() + ": " + rejected.getMessage());
            }
        }
        System.out.println("PROBE_DONE");
    }

    /** 真实开局：cufe appt-2 召集 + s1/s2/s3 到会，并跑到第 2 个制造关期批次。 */
    static void build(Path file) throws Exception {
        WorldRuntime world = WorldWebScenario.openEngineering(file).runtime();
        try {
            world.advanceTo(200L);
            world.scheduleEngineeringAppointmentConvene("c2", "c2-t", P - 1_000L, "cufe",
                    "alice", "appt-2", P + 1_000L, P + 2_000L, P + 3_000L);
            world.scheduleEngineeringAttend("b1", "b1-t", P - 900L, "cufe", "alice", "appt-2",
                    false);
            world.scheduleEngineeringAttend("b2", "b2-t", P - 800L, "cufe", "bob", "appt-2",
                    false);
            world.scheduleEngineeringAttend("b3", "b3-t", P - 700L, "cufe", "carol", "appt-2",
                    false);
            world.advanceTo(2L * P + 5L);
        } finally {
            world.close();
        }
    }

    private static String describe(Path file) throws Exception {
        try (WorldRuntime world = WorldWebScenario.openEngineering(file).runtime()) {
            BoardModel.Meeting meeting = world.boardOfCompany("cufe").orElseThrow().board()
                    .meeting("appt-2");
            return meeting.status() + " attendees=" + meeting.attendeeSeatIds()
                    + " proposal=" + (meeting.appointmentProposal() == null ? "null" : "present");
        }
    }

    /**
     * 从真实 {@code task:mfg-close:1} 起一致重写快照并重算帧 SHA。
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
            if ("task:mfg-close:1".equals(commit.path("commandId").asText())) {
                started = true;
            }
            if (started) {
                boolean mutated = false;
                for (JsonNode event : commit.path("events")) {
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

    private static String tamperState(String stateJson, Kind kind) {
        if (kind == Kind.PROPOSAL) {
            int appt = stateJson.indexOf("\"appt-2\":");
            if (appt < 0) {
                return stateJson;
            }
            int failure = stateJson.indexOf("\"failureReason\":", appt);
            int close = failure < 0 ? -1 : stateJson.indexOf('}', failure);
            if (close <= failure) {
                return stateJson;
            }
            String proposal = ", \"appointmentProposal\": {\"request\": "
                    + "{\"appointmentId\": \"fake-mgr\", \"personPartyId\": \"dave\", "
                    + "\"companyPartyId\": \"cufe\", \"role\": \"COMPANY_MANAGER\", "
                    + "\"action\": \"APPOINT\", \"effectiveAt\": " + (P + 4_000L)
                    + ", \"endsAt\": 77760000000}, \"proposerSeatId\": \"s1\", "
                    + "\"relatedSeatIds\": [], \"proposedAtMillis\": " + P + "}";
            return stateJson.substring(0, close) + proposal + stateJson.substring(close);
        }
        // REPORT：在治理回报里插入一条与真实任务标识一致、字段合法的“已接受”回报。
        int[] governance = objectScope(stateJson, "\"governance\":{");
        if (governance == null) {
            return stateJson;
        }
        String within = stateJson.substring(governance[0], governance[1]);
        int[] reports = objectScope(within, "\"reports\":{");
        if (reports == null) {
            return stateJson;
        }
        String report = "\"mfg-close:1\":{\"taskId\":\"mfg-close:1\","
                + "\"commandId\":\"system:close-period:1\","
                + "\"kind\":\"MANUFACTURING_PERIOD_CLOSE_V1\",\"meetingId\":\"appt-2\","
                + "\"accepted\":true,\"reason\":\"OK\",\"timeMillis\":" + P
                + ",\"position\":4},";
        int insertAt = governance[0] + reports[0] + 1;
        return stateJson.substring(0, insertAt) + report + stateJson.substring(insertAt);
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
