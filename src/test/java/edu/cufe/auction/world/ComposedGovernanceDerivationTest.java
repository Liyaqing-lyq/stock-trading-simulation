package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.composed.ComposedWorldScenario;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章程公司董事帧的治理变化必须由真实任务重演得出：合法帧可重开，只改会议时点的帧必须被拒绝。
 *
 * <p>组合档的章程公司（cufe）董事帧在修复后是<b>工程批次</b>：写路径用
 * {@code EngineeringRun.withReport} 留工程回报并发 {@code ENGINEERING_REPORT_V1}，
 * 恢复期因此走 {@code replayEngineering} 的整帧重演，由 {@code validateEngineering} 对
 * {@code replay.governance()} 的逐字段比较拒绝自洽篡改（确切前缀
 * 「周期治理董事会变化缺少原任务重演」）。</p>
 *
 * <p>本条<b>不</b>声称验证 {@code requireGovernanceDerivableFromConsumedTasks}：那一支只处理
 * <b>没有工程回报</b>的批次，而本夹具的董事帧已带工程回报。把「任意不一致」当作该 guard 的
 * 负例不成立（Root 审查已指出），因此这里如实按实际命中的 guard 断言。</p>
 *
 * <p>篡改手法也只改目标字段本身的文本：定位快照里唯一的 {@code "opensAt":<数字>} 片段做定点
 * 字符串替换（{@code +1} 毫秒），不把整份快照经 ObjectMapper 重新序列化——否则会顺带改动与
 * 本案无关的冻结配置，拒绝原因就不再是治理重演。</p>
 */
class ComposedGovernanceDerivationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 实测拒绝来源：validateEngineering 对 replay.governance() 的逐字段比较。 */
    private static final String EXPECTED_REASON = "周期治理董事会变化缺少原任务重演";

    @Test
    void forgedCharterBoardOpeningTimeIsRejectedByReplay() throws Exception {
        Path dir = Files.createTempDirectory("composed-gov-derive");
        Path file = dir.resolve("world.log");
        build(file);
        Path clean = dir.resolve("clean.log");
        Files.copy(file, clean);

        int index = frameOfCommand(file, "task:cvt-neg:finish");
        long[] before = new long[1];
        long[] after = new long[1];
        JournalTamper.rewriteSnapshot(file, index, json -> forgeOpensAt(json, before, after));

        // 篡改必须真的只改了目标字段：值 +1ms，字段本身仍合法，其它内容逐项相同。
        assertEquals(before[0] + 1L, after[0], "目标字段必须正好加 1 毫秒");
        assertOnlyOpensAtChanged(clean, file, index, after[0]);

        IOException rejected = assertThrows(IOException.class, () -> {
            try (WorldRuntime ignored = ComposedWorldScenario.open(file)) {
                throw new AssertionError("篡改会议时点后重开必须被拒绝");
            }
        }, "只改章程董事帧的会议时点也必须被拒绝");
        String message = String.valueOf(rejected.getMessage());
        assertTrue(message.startsWith(EXPECTED_REASON),
                "拒绝必须来自治理子集重演比较，实际：" + message);
        System.out.println("[NEGATIVE-REJECT] " + rejected.getClass().getSimpleName()
                + ": " + message + " (frame=task:cvt-neg:finish opensAt "
                + before[0] + "->" + after[0] + ")");
    }

    @Test
    void legitimateCharterBoardFrameIsAcceptedAndReopens() throws Exception {
        Path dir = Files.createTempDirectory("composed-gov-derive-ok");
        Path file = dir.resolve("world.log");
        build(file);
        try (WorldRuntime reopened = ComposedWorldScenario.open(file)) {
            assertTrue(reopened.snapshot().position() > 1, "合法董事帧必须可重开");
            assertTrue(reopened.snapshot().governance() != null, "治理子集必须存在");
            assertTrue(reopened.snapshot().engineering() != null, "工程子集必须存在");
            // 合法帧自己也必须留下工程回报：否则恢复期无从按原任务重演这一帧。
            assertTrue(reopened.snapshot().engineering().orElseThrow().reports().values().stream()
                            .anyMatch(report -> PendingTask.ENGINEERING_BOARD_V1
                                    .equals(report.kind())),
                    "章程董事帧必须写回工程回报");
        }
    }

    /** 真实召集帧 + 推进到董事帧收尾；与 CharterBoardFrameTamperTest 同一真实链路与夹具。 */
    private static void build(Path file) throws Exception {
        try (WorldRuntime runtime = ComposedWorldScenario.open(file)) {
            long now = runtime.snapshot().timeMillis();
            BoardModel.Charter charter = ComposedWorldScenario.cufeCharter();
            String chair = charter.seats().stream()
                    .filter(seat -> seat.seatId().equals(charter.chairSeatId()))
                    .findFirst().orElseThrow().partyId();
            runtime.scheduleCompanyBoardConvene("cv-neg", "cvt-neg", now + 2, "cufe", chair,
                    "mtg-neg", now + 12, now + 14, now + 20, true);
            runtime.advanceTo(now + 200);
        }
    }

    /** 按提交命令标识找帧（不是按字符串出现位置猜）。 */
    private static int frameOfCommand(Path file, String commandId) throws Exception {
        var frames = JournalTamper.frames(file);
        for (int i = 0; i < frames.size(); i++) {
            JsonNode node = MAPPER.readTree(new String(frames.get(i).payload(),
                    java.nio.charset.StandardCharsets.UTF_8));
            if (node.hasNonNull("commandId") && commandId.equals(node.get("commandId").asText())) {
                return i;
            }
        }
        throw new IllegalStateException("日志里没有命令帧：" + commandId);
    }

    /**
     * 只把快照里唯一一处 {@code "opensAt":<数字>} 换成 +1 毫秒；其余字节保持原样。
     *
     * <p>若出现多处同名片段则直接失败——宁可夹具报错，也不做「随手改一处」的含糊篡改。
     * 同时把改前/改后的值写回调用者，供断言「确实改了目标字段」。</p>
     */
    private static String forgeOpensAt(String json, long[] before, long[] after) {
        String key = "\"opensAt\":";
        int first = json.indexOf(key);
        if (first < 0) {
            throw new IllegalStateException("快照里没有 opensAt 字段");
        }
        if (json.indexOf(key, first + key.length()) >= 0) {
            throw new IllegalStateException("快照里不止一处 opensAt，定点替换不成立");
        }
        int valueStart = first + key.length();
        int valueEnd = valueStart;
        while (valueEnd < json.length()
                && (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '-')) {
            valueEnd += 1;
        }
        long original = Long.parseLong(json.substring(valueStart, valueEnd));
        before[0] = original;
        after[0] = original + 1L;
        return json.substring(0, valueStart) + after[0] + json.substring(valueEnd);
    }

    /**
     * 证明篡改只落在目标字段：改后帧的快照 JSON 可解析，把该字段还原后与清洁帧快照逐项相等。
     *
     * <p>比较的是解析树（不是字节），因为放回提交时外层包装必须重新序列化；但快照内部本身只做了
     * 定点字符串替换，因此「还原后完全相等」足以证明没有顺带改别的配置。</p>
     */
    private static void assertOnlyOpensAtChanged(Path clean, Path tampered, int index,
                                                 long forgedValue) throws Exception {
        JsonNode cleanSnapshot = snapshotOf(clean, index);
        JsonNode forgedSnapshot = snapshotOf(tampered, index);
        // 比较用 JSON 文本：Jackson 的 IntNode/LongNode 数值相同也不相等，逐字段比较会误报。
        assertNotEquals(cleanSnapshot.toString(), forgedSnapshot.toString(),
                "篡改必须真的改到快照内容");
        JsonNode forgedMeeting = forgedSnapshot.path("governance").path("board")
                .path("meetings").elements().next();
        assertEquals(forgedValue, forgedMeeting.path("opensAt").asLong(),
                "目标会议的开会时点必须是被改的那个值");
        JsonNode cleanMeeting = cleanSnapshot.path("governance").path("board")
                .path("meetings").elements().next();
        ((ObjectNode) forgedMeeting).put("opensAt", cleanMeeting.path("opensAt").asLong());
        assertEquals(cleanSnapshot.toString(), forgedSnapshot.toString(),
                "除目标字段外不得有任何其它差异");
    }

    /** 取指定帧里最后一个 STATE_SNAPSHOT_V1 的内层状态 JSON。 */
    private static JsonNode snapshotOf(Path file, int index) throws Exception {
        var frames = JournalTamper.frames(file);
        JsonNode commit = MAPPER.readTree(new String(frames.get(index).payload(),
                java.nio.charset.StandardCharsets.UTF_8));
        JsonNode snapshot = null;
        for (JsonNode event : commit.path("events")) {
            if ("STATE_SNAPSHOT_V1".equals(event.path("type").asText())) {
                snapshot = MAPPER.readTree(event.path("payload").asText());
            }
        }
        if (snapshot == null) {
            throw new IllegalStateException("帧 " + index + " 没有状态快照事件");
        }
        return snapshot;
    }
}
