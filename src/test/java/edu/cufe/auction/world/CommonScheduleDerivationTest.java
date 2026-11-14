package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.model.OrderType;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公共排程恢复：新增待办必须由本批原命令派生。
 *
 * <p>每个用例都先用真实入口写出合法日志，再把副本里的「新增待办 + TASK_SCHEDULED 事件」一起改成
 * 另一组数值而保持原命令正文不变（自洽篡改，重算帧摘要），因此「事件与快照一致」不再能证明来源。
 * 修复前这些日志可以打开并按伪待办执行：订单 100 变 10 会真的挂出 10 股、制造采购数量 10 变 99
 * 会按伪数量执行；对应修复前证据见
 * {@code logs/implementation-20261106-continued/common-schedule-author/_build/probe-before.log}。</p>
 *
 * <p>拒绝本身是强断言：必须抛 {@link IOException}、消息精确命中对应校验，且被打开的副本字节不变
 * （恢复失败不得修写日志）。修复后的证据见同目录 {@code probe-after.log}。</p>
 */
@DisplayName("公共排程恢复：待办由原命令派生")
class CommonScheduleDerivationTest {

    private static final String SYMBOL = WorldWebScenario.SYMBOL;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // 反例：原命令 quantity=100，待办与事件被改成 10
    // ------------------------------------------------------------------

    @Test
    @DisplayName("订单待办数量 100→10：原命令不变也必须拒绝重开")
    void orderQuantityTamperRejected() throws Exception {
        Path base = tempDir.resolve("order-base.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(base)) {
            assertEquals("SCHEDULED:t-o1", runtime.scheduleOrder("o1", "t-o1", 100L, "fund-a",
                    "ACC-FUND", SYMBOL, Side.BUY, OrderType.LIMIT, new BigDecimal("10.00"), 100L));
        }
        Path attack = tamper(base, "cmd:o1", "t-o1",
                "\"quantity\":100", "\"quantity\":10");
        assertOpenRejected(attack, "排程帧与按原命令重演的状态不一致：t-o1 [pending]");
    }

    @Test
    @DisplayName("订单待办额外夹带一个任务：数量上限与追加一致性必须拒绝")
    void orderExtraPendingRejected() throws Exception {
        Path base = tempDir.resolve("order-extra-base.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(base)) {
            runtime.scheduleOrder("o1", "t-o1", 100L, "fund-a", "ACC-FUND", SYMBOL, Side.BUY,
                    OrderType.LIMIT, new BigDecimal("10.00"), 100L);
        }
        Path attack = tempDir.resolve("order-extra.journal");
        Files.copy(base, attack, StandardCopyOption.REPLACE_EXISTING);
        int index = frameWithCommand(attack, "cmd:o1");
        String extra = "{\"taskId\":\"t-extra\",\"dueTimeMillis\":100,"
                + "\"phase\":\"COMPLETION\",\"kind\":\"EXCHANGE_ORDER_V1\",\"payload\":\"{}\"}";
        JournalTamper.rewrite(attack, index, json -> appendPending(json, extra, "t-extra"));
        assertOpenRejected(attack, "非执行帧必须只追加排程任务，不得删除或改写旧待办：cmd:o1");
    }

    @Test
    @DisplayName("订单待办改种类：待办种类与命令正文不符必须拒绝")
    void orderKindTamperRejected() throws Exception {
        Path base = tempDir.resolve("order-kind-base.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(base)) {
            runtime.scheduleOrder("o1", "t-o1", 100L, "fund-a", "ACC-FUND", SYMBOL, Side.BUY,
                    OrderType.LIMIT, new BigDecimal("10.00"), 100L);
        }
        Path attack = tamper(base, "cmd:o1", "t-o1",
                "\"kind\":\"EXCHANGE_ORDER_V1\"", "\"kind\":\"EXCHANGE_CANCEL_V1\"");
        assertOpenRejected(attack, "排程帧无法由上一帧状态和原命令重算：t-o1");
    }

    // ------------------------------------------------------------------
    // 反例：制造命令记录型入口（ManufacturingCommand → ManufacturingPayload）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("制造采购数量 10→99：命令正文不变也必须拒绝重开")
    void manufacturingQuantityTamperRejected() throws Exception {
        Path base = tempDir.resolve("purchase-base.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(base)) {
            runtime.schedulePurchaseOrder("po-1", "t-po-1", 100L, "company", "purchase-1", 10L);
        }
        Path attack = tamper(base, "cmd:po-1", "t-po-1",
                "\"quantity\":10", "\"quantity\":99");
        assertOpenRejected(attack, "排程帧与按原命令重演的状态不一致：t-po-1 [pending]");
    }

    // ------------------------------------------------------------------
    // 反例：工程 BEGIN 屏障的提前量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("工程 BEGIN 待办到期改早：与原命令工时不符必须拒绝")
    void engineeringBeginLeadTamperRejected() throws Exception {
        Path base = tempDir.resolve("board-material-base.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(base)) {
            runtime.scheduleEngineeringConvene("conv", "t-conv", 100L, WorldWebScenario.ENGINE_A,
                    "alice", "meeting-eng", 150L, 200L, 300L);
        }
        // BEGIN 待办到期 = 动作到期 - 工时(1ms)：改成 98 与命令冻结的工时不再相符
        Path attack = tamper(base, "cmd:conv", "t-conv", "\"dueTimeMillis\":99",
                "\"dueTimeMillis\":98");
        assertOpenRejected(attack, "工程 BEGIN 提前量与原命令工时不一致：t-conv");
    }

    @Test
    @DisplayName("工程 BEGIN 目标种类改成到会：与正文动作不符必须拒绝")
    void engineeringTargetKindAttendSwapRejected() throws Exception {
        Path base = tempDir.resolve("eng-kind-attend-base.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(base)) {
            runtime.scheduleEngineeringConvene("conv", "t-conv", 100L, WorldWebScenario.ENGINE_A,
                    "alice", "meeting-eng", 150L, 200L, 300L);
        }
        Path attack = tamper(base, "cmd:conv", "t-conv",
                "\"targetKind\":\"ENGINEERING_BOARD_V1\"", "\"targetKind\":\"BOARD_ATTEND_V1\"");
        assertOpenRejected(attack, "工程 BEGIN 目标种类与到会动作不一致：CONVENE");
    }

    @Test
    @DisplayName("工程 BEGIN 目标种类改成经营：正文类型不符必须拒绝，不能在推进时崩溃")
    void engineeringTargetKindActionSwapRejected() throws Exception {
        Path base = tempDir.resolve("eng-kind-action-base.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(base)) {
            runtime.scheduleEngineeringConvene("conv", "t-conv", 100L, WorldWebScenario.ENGINE_A,
                    "alice", "meeting-eng", 150L, 200L, 300L);
        }
        Path attack = tamper(base, "cmd:conv", "t-conv",
                "\"targetKind\":\"ENGINEERING_BOARD_V1\"", "\"targetKind\":\"ENGINEERING_ACTION_V1\"");
        assertOpenRejected(attack, "工程 BEGIN 目标种类与经营正文类型不一致");
    }

    // ------------------------------------------------------------------
    // 反例：治理与角色动作（待办参数就是命令正文）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("治理会议待办改开会时刻：与原命令不符必须拒绝")
    void governancePayloadTamperRejected() throws Exception {
        Path base = tempDir.resolve("meeting-base.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(base)) {
            runtime.scheduleBoardMeeting("m1", "t-m1", 100L, "s1", "meeting:1", 200L, 300L, 400L,
                    List.of("s1", "s2", "s3", "s4", "s5"), List.of());
        }
        Path attack = tamper(base, "cmd:m1", "t-m1", "\"opensAt\":300", "\"opensAt\":350");
        assertOpenRejected(attack, "排程待办参数与原命令不一致：t-m1");
    }

    @Test
    @DisplayName("角色阅读待办改资料：原命令不变必须拒绝")
    void roleReadPayloadTamperRejected() throws Exception {
        Path base = tempDir.resolve("read-base.journal");
        try (WorldRuntime runtime = WorldWebScenario.openSocial(base).runtime()) {
            runtime.openRoleSession("nina").startReading("rd-1", "rd-t1",
                    runtime.snapshot().timeMillis() + 500L,
                    WorldWebScenario.SOCIAL_CONTACT_DOC, 1L);
        }
        Path attack = tempDir.resolve("read-attack.journal");
        Files.copy(base, attack, StandardCopyOption.REPLACE_EXISTING);
        int index = frameWithCommand(attack, "cmd:roleT:4:nina:rd-1");
        String taskId = WorldRuntime.roleTaskId("nina", "rd-t1");
        JournalTamper.rewrite(attack, index,
                json -> tamperFrame(json, taskId,
                        new String[]{"\"docId\":\"" + WorldWebScenario.SOCIAL_CONTACT_DOC + "\"",
                                "\"docId\":\"social:other\""}, true));
        assertOpenRejected(attack, "排程待办参数与原命令不一致：" + taskId, SOCIAL_OPENER);
    }

    // ------------------------------------------------------------------
    // 正例：合法排程不得被误杀，且到期按原命令执行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("合法订单/采购/会议/阅读排程照常打开并推进")
    void legitimateSchedulesStillOpen() throws Exception {
        Path order = tempDir.resolve("order-ok.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(order)) {
            runtime.scheduleOrder("o1", "t-o1", 100L, "fund-a", "ACC-FUND", SYMBOL, Side.BUY,
                    OrderType.LIMIT, new BigDecimal("10.00"), 100L);
        }
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(order)) {
            assertEquals(2L, runtime.snapshot().position(), "排程帧必须正常重开");
            runtime.advanceTo(100L);
            WorldExchange.State exchange = runtime.snapshot().exchange().orElseThrow();
            assertEquals(1, exchange.orders().size(), "到期必须按原命令挂出一单");
            assertEquals(100L, exchange.orders().get(0).quantity(), "挂出数量必须等于原命令 100");
        }

        Path purchase = tempDir.resolve("purchase-ok.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(purchase)) {
            runtime.schedulePurchaseOrder("po-1", "t-po-1", 100L, "company", "purchase-1", 10L);
        }
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(purchase)) {
            runtime.advanceTo(100L);
            assertTrue(runtime.snapshot().manufacturing().orElseThrow().reports()
                    .containsKey("t-po-1"), "采购到期必须留下回报");
        }

        Path meeting = tempDir.resolve("meeting-ok.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(meeting)) {
            runtime.scheduleBoardMeeting("m1", "t-m1", 100L, "s1", "meeting:1", 200L, 300L, 400L,
                    List.of("s1", "s2", "s3", "s4", "s5"), List.of());
        }
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(meeting)) {
            assertEquals(2L, runtime.snapshot().position());
        }

        Path read = tempDir.resolve("read-ok.journal");
        try (WorldRuntime runtime = WorldWebScenario.openSocial(read).runtime()) {
            runtime.openRoleSession("nina").startReading("rd-1", "rd-t1",
                    runtime.snapshot().timeMillis() + 500L,
                    WorldWebScenario.SOCIAL_CONTACT_DOC, 1L);
        }
        try (WorldRuntime runtime = WorldWebScenario.openSocial(read).runtime()) {
            assertEquals(4L, runtime.snapshot().position(), "阅读排程必须正常重开");
        }
    }

    // ------------------------------------------------------------------
    // 夹具：真实命令生成 → 复制 → 自洽篡改 → 只用副本重开
    // ------------------------------------------------------------------

    /** 复制基础日志并对待办与其排程事件做同一处替换，原命令正文保持不变。 */
    private Path tamper(Path base, String commandId, String taskId, String... pairs)
            throws Exception {
        Path attack = base.resolveSibling(base.getFileName().toString()
                .replace("-base.journal", "-attack.journal"));
        Files.copy(base, attack, StandardCopyOption.REPLACE_EXISTING);
        int index = frameWithCommand(attack, commandId);
        JournalTamper.rewrite(attack, index, json -> tamperFrame(json, taskId, pairs, true));
        return attack;
    }

    /** 打开副本：必须构造期拒绝，且副本字节不变。 */
    private void assertOpenRejected(Path attack, String expected) throws Exception {
        assertOpenRejected(attack, expected, GROUPS_OPENER);
    }

    private void assertOpenRejected(Path attack, String expected, Opener opener) throws Exception {
        Path copy = tempDir.resolve("open-" + System.nanoTime() + ".journal");
        Files.copy(attack, copy, StandardCopyOption.REPLACE_EXISTING);
        byte[] before = Files.readAllBytes(copy);
        IOException failure = assertThrows(IOException.class,
                () -> opener.open(copy),
                "被篡改的排程帧必须在构造期恢复校验里拒绝：" + expected);
        assertTrue(failure.getMessage().contains(expected),
                "拒绝原因必须精确命中，实际：" + failure.getMessage());
        assertArrayEquals(before, Files.readAllBytes(copy), "恢复失败不得修写日志");
    }

    /** 打开方式：档案类型不同，不能用错档案重开（那会先撞档案绑定校验）。 */
    private interface Opener {
        WorldRuntime open(Path file) throws Exception;
    }

    private static final Opener GROUPS_OPENER = WorldWebScenario::groupsRuntime;

    private static final Opener SOCIAL_OPENER = file ->
            WorldWebScenario.openSocial(file).runtime();

    private static String tamperFrame(String frameJson, String taskId, String[] pairs,
                                      boolean syncEvent) {
        try {
            ObjectNode commit = (ObjectNode) JSON.readTree(frameJson);
            boolean pending = false;
            boolean event = false;
            for (JsonNode node : commit.get("events")) {
                String type = node.get("type").asText();
                String payload = node.get("payload").asText();
                if ("STATE_SNAPSHOT_V1".equals(type)) {
                    String updated = editPending(payload, taskId, pairs);
                    if (!updated.equals(payload)) {
                        ((ObjectNode) node).put("payload", updated);
                        pending = true;
                    }
                } else if (syncEvent && "TASK_SCHEDULED_V1".equals(type)
                        && payload.contains("\"taskId\":\"" + taskId + "\"")) {
                    String updated = replace(payload, pairs);
                    if (!updated.equals(payload)) {
                        ((ObjectNode) node).put("payload", updated);
                        event = true;
                    }
                }
            }
            if (!pending || (syncEvent && !event)) {
                throw new IllegalStateException("篡改未命中：pending=" + pending
                        + " event=" + event + " / " + taskId);
            }
            return JSON.writeValueAsString(commit);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String replace(String text, String[] pairs) {
        String out = text;
        for (int i = 0; i < pairs.length; i += 2) {
            boolean replaced = false;
            String from = pairs[i];
            String to = pairs[i + 1];
            for (int level = 0; level <= 2 && !replaced; level++) {
                if (out.contains(from)) {
                    out = out.replace(from, to);
                    replaced = true;
                }
                from = escape(from);
                to = escape(to);
            }
            if (!replaced) {
                throw new IllegalStateException("字段未命中：" + pairs[i]);
            }
        }
        return out;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 只在 pending 数组里 taskId 对应的任务对象内替换。 */
    private static String editPending(String snapshot, String taskId, String[] pairs) {
        int at = snapshot.indexOf("\"pending\":[");
        if (at < 0) {
            throw new IllegalStateException("待办集合未命中");
        }
        int start = snapshot.indexOf('{', at);
        while (start >= 0) {
            int end = matchingBrace(snapshot, start);
            String object = snapshot.substring(start, end + 1);
            if (object.contains("\"taskId\":\"" + taskId + "\"")) {
                return snapshot.substring(0, start) + replace(object, pairs)
                        + snapshot.substring(end + 1);
            }
            start = snapshot.indexOf('{', end + 1);
        }
        throw new IllegalStateException("待办对象未命中：" + taskId);
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && text.charAt(i - 1) != '\\') {
                quoted = !quoted;
            }
            if (quoted) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("任务对象未闭合");
    }

    private static String appendPending(String frameJson, String taskJson, String taskId) {
        try {
            ObjectNode commit = (ObjectNode) JSON.readTree(frameJson);
            for (JsonNode node : commit.get("events")) {
                if (!"STATE_SNAPSHOT_V1".equals(node.get("type").asText())) {
                    continue;
                }
                String snapshot = node.get("payload").asText();
                int at = snapshot.indexOf("\"pending\":[");
                int close = snapshot.indexOf(']', at);
                String updated = snapshot.substring(0, close) + "," + taskJson
                        + snapshot.substring(close);
                updated = updated.contains("\"usedTaskIds\":[]")
                        ? updated.replace("\"usedTaskIds\":[]",
                                "\"usedTaskIds\":[\"" + taskId + "\"]")
                        : updated.replace("\"usedTaskIds\":[",
                                "\"usedTaskIds\":[\"" + taskId + "\",");
                ((ObjectNode) node).put("payload", updated);
            }
            return JSON.writeValueAsString(commit);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int frameWithCommand(Path file, String commandId) throws Exception {
        List<JournalTamper.Frame> frames = JournalTamper.frames(file);
        for (int i = frames.size() - 1; i >= 0; i--) {
            String json = new String(frames.get(i).payload(), StandardCharsets.UTF_8);
            JsonNode commit = JSON.readTree(json);
            if (commandId.equals(commit.get("commandId").asText())) {
                return i;
            }
        }
        throw new IllegalStateException("没有命令帧：" + commandId);
    }
}
