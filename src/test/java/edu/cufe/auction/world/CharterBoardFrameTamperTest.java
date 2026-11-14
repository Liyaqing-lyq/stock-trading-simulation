package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章程公司（cufe）真实董事任务帧的篡改反例：本批次确实消费了真实
 * {@code ENGINEERING_BOARD_V1} 任务，但快照里被塞进该任务没有产生的治理变化
 * （未真实到会的第三席）时必须被拒绝——证明“把章程公司董事帧接回逐帧重演”没有
 * 放宽成“有董事任务就能任意改治理”。
 *
 * <p>夹具与篡改手法沿用 {@link GovernanceTamperProbe}：同一真实 journal、
 * 同一套帧长度/帧 SHA 自洽重算（门 6 的既定威胁模型），只改
 * {@code STATE_SNAPSHOT_V1} 的 payload 文本。与之不同的是：这里篡改的是
 * <b>真实董事任务帧本人</b>，而不是其后的非工程时钟批；帧身份由该 commit 自己的
 * {@code ENGINEERING_REPORT_V1}/{@code COMPANY_BOARD_REPORT_V1} 与被消费的
 * {@code TASK_COMPLETED_V1} 交叉核对得出（不用 {@code commandId} 前缀猜），
 * 该帧在修复后走 {@code ENGINEERING_REPORT_V1} 触发的整帧重演，
 * 由 {@code replay.governance()/roles()} 逐字段比较拒绝。</p>
 */
class CharterBoardFrameTamperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COMPANY = "cufe";
    /** 实测拒绝来源：validateEngineering 对 replay.governance() 的逐字段比较。 */
    private static final String EXPECTED_REASON = "周期治理董事会变化缺少原任务重演";

    private static Path built(String name) throws Exception {
        Path dir = Path.of("target", "charter-board-frame-tamper");
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".journal");
        Files.deleteIfExists(file);
        GovernanceTamperProbe.build(file);
        return file;
    }

    @Test
    void realBoardFrameWithForgedAttendanceIsRejected() throws Exception {
        Path file = built("board-frame-attendance");
        TamperResult tampered = tamperBoardFrames(file);
        assertNotNull(tampered.firstMutatedCommandId(), "篡改必须真的改到内容");
        assertTrue(tampered.boardFrames() > 0, "夹具里必须存在可识别的真实董事任务帧");
        assertEquals(tampered.expectedFirstMutatedCommandId(), tampered.firstMutatedCommandId(),
                "第一次真正改动必须落在按真实回报/被消费任务识别出的董事任务帧上");
        IOException rejected = assertThrows(IOException.class, () -> {
            try (WorldRuntime world = WorldWebScenario.openEngineering(file).runtime()) {
                world.snapshot();
            }
        }, "真实董事任务帧里伪造未真实发生的到会必须被拒绝");
        assertNotNull(rejected.getMessage(), "拒绝必须带原因");
        assertTrue(rejected.getMessage().startsWith(EXPECTED_REASON),
                "拒绝必须来自治理子集重演比较，实际：" + rejected.getMessage());
        System.out.println("[NEGATIVE-REJECT] " + rejected.getClass().getSimpleName()
                + ": " + rejected.getMessage()
                + " (frame=" + tampered.firstMutatedCommandId() + ")");
    }

    @Test
    void cleanJournalStillReopensWithRealAttendance() throws Exception {
        Path file = built("clean");
        try (WorldRuntime world = WorldWebScenario.openEngineering(file).runtime()) {
            BoardModel.Meeting meeting = world.boardOfCompany(COMPANY).orElseThrow()
                    .board().meeting("appt-1");
            assertEquals(List.of("s1", "s2"), meeting.attendeeSeatIds(),
                    "真实到会只有 s1/s2");
        }
    }

    /** 篡改结果：首个真正改动的帧、按真实回报识别出的首个可篡改董事帧、董事帧总数。 */
    private record TamperResult(String firstMutatedCommandId, String expectedFirstMutatedCommandId,
                                int boardFrames) {
    }

    /** 真实董事任务帧的身份，全部取自该 commit 自身的真实事件。 */
    private record BoardFrame(String commandId, String taskId, String kind, String company) {
    }

    /**
     * 从第一条真实董事任务帧起，把所有后续 STATE_SNAPSHOT 里的会议到会席位一致补上 s3，并重算帧 SHA；
     * 不绑定任何真实到会任务或事件。
     *
     * <p>帧身份来自 {@code ENGINEERING_REPORT_V1}（kind/company/taskId）、
     * {@code COMPANY_BOARD_REPORT_V1}（company/taskId）与 {@code TASK_COMPLETED_V1} 的交叉核对，
     * 并要求 {@code commandId == "task:" + taskId}；三者不一致直接断言失败，不用前缀猜。</p>
     */
    private static TamperResult tamperBoardFrames(Path file) throws Exception {
        byte[] all = Files.readAllBytes(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        boolean started = false;
        String expectedFirst = null;
        String firstMutated = null;
        int boardFrames = 0;
        while (offset < all.length) {
            int length = ByteBuffer.wrap(all, offset, 4).getInt();
            byte[] payload = Arrays.copyOfRange(all, offset + 8, offset + 8 + length);
            byte[] digest = Arrays.copyOfRange(all, offset + 8 + length, offset + 8 + length + 32);
            JsonNode commit = MAPPER.readTree(payload);
            BoardFrame frame = boardFrame(commit);
            boolean charterBoard = frame != null && COMPANY.equals(frame.company());
            if (charterBoard) {
                boardFrames += 1;
                started = true;
            }
            if (started && charterBoard) {
                boolean mutated = false;
                for (JsonNode event : (ArrayNode) commit.path("events")) {
                    if (!"STATE_SNAPSHOT_V1".equals(event.path("type").asText())) {
                        continue;
                    }
                    String stateJson = event.path("payload").asText();
                    String forged = forgeAttendance(stateJson);
                    if (!forged.equals(stateJson)) {
                        ((ObjectNode) event).put("payload", forged);
                        mutated = true;
                        if (expectedFirst == null) {
                            expectedFirst = frame.commandId();
                        }
                    }
                }
                if (mutated) {
                    payload = MAPPER.writeValueAsBytes(commit);
                    digest = MessageDigest.getInstance("SHA-256").digest(payload);
                    if (firstMutated == null) {
                        firstMutated = frame.commandId();
                    }
                }
            }
            out.write(ByteBuffer.allocate(8).putInt(payload.length).putInt(~payload.length)
                    .array());
            out.write(payload);
            out.write(digest);
            offset += 8 + length + 32;
        }
        Files.write(file, out.toByteArray());
        return new TamperResult(firstMutated, expectedFirst, boardFrames);
    }

    /**
     * 只按该 commit 自己的真实事件认定“真实董事任务帧”；不是董事帧返回 null，
     * 是董事帧但身份自相矛盾则断言失败（不允许含糊匹配）。
     */
    private static BoardFrame boardFrame(JsonNode commit) throws IOException {
        JsonNode report = null;
        JsonNode companyReport = null;
        List<String> completed = new ArrayList<>();
        for (JsonNode event : (ArrayNode) commit.path("events")) {
            switch (event.path("type").asText()) {
                case "TASK_COMPLETED_V1" -> {
                    String eventId = event.path("eventId").asText();
                    if (eventId.startsWith("task-completed:")) {
                        completed.add(eventId.substring("task-completed:".length()));
                    }
                }
                case "ENGINEERING_REPORT_V1" -> report = MAPPER
                        .readTree(event.path("payload").asText());
                case "COMPANY_BOARD_REPORT_V1" -> companyReport = MAPPER
                        .readTree(event.path("payload").asText());
                default -> {
                }
            }
        }
        if (report == null) {
            return null;
        }
        String taskId = report.path("taskId").asText();
        String kind = report.path("kind").asText();
        String company = report.path("companyPartyId").asText();
        if (!PendingTask.ENGINEERING_BOARD_V1.equals(kind)
                && !PendingTask.BOARD_ATTEND_V1.equals(kind)) {
            // 非董事会任务（如 BEGIN 屏障）也有工程回报，但不属于本反例的董事帧。
            return null;
        }
        assertTrue(completed.contains(taskId),
                "工程回报必须对应本批真实完成的待办：" + taskId + " in " + completed);
        assertEquals("task:" + taskId, commit.path("commandId").asText(),
                "董事帧的 commandId 必须就是被消费任务的命令");
        assertNotNull(companyReport, "董事帧必须同时有公司董事会回报");
        assertEquals(taskId, companyReport.path("taskId").asText(),
                "两份回报必须指向同一任务");
        assertEquals(company, companyReport.path("company").asText(),
                "两份回报必须指向同一公司");
        return new BoardFrame(commit.path("commandId").asText(), taskId, kind, company);
    }

    /**
     * 只在状态字符串里替换会议到会席位片段：补一个未真实到会的 s3，其它字节一律不动。
     * 与 {@link GovernanceTamperProbe} 同一手法（定点字符串替换），避免整树重序列化
     * 引入与本案无关的差异。
     */
    private static String forgeAttendance(String stateJson) {
        String forged = stateJson.replace("\"attendeeSeatIds\":[\"s1\",\"s2\"]",
                "\"attendeeSeatIds\":[\"s1\",\"s2\",\"s3\"]");
        if (forged.equals(stateJson)) {
            forged = stateJson.replace("\"attendeeSeatIds\":[\"s1\"]",
                    "\"attendeeSeatIds\":[\"s1\",\"s3\"]");
        }
        return forged;
    }
}
