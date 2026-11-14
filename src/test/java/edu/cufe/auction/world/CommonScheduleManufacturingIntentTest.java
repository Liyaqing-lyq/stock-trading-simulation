package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.web.WorldWebScenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 制造排程意图（{@code manufacturing-intent} 新规则档）。
 *
 * <p>旧档的 {@code ManufacturingCommand} 没有种类字段，采购与投产这两类同形动作无法从原命令
 * 鉴别，属于明确记录的旧契约限制。新规则档把种类写进命令正文：新档必须带种类、必须等于待办
 * 种类，恢复时按原命令种类重演；旧档继续原格式（JSON 不含 {@code kind}），命令原文不增字段。</p>
 */
@DisplayName("制造排程意图：新规则可鉴别、旧规则不伪造")
class CommonScheduleManufacturingIntentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("新规则档：合法采购与投产各自打开并执行")
    void newRuleLegitimatePurchaseAndProduction() throws Exception {
        Path purchase = tempDir.resolve("intent-purchase.journal");
        try (WorldRuntime runtime = WorldWebScenario.commandIntentRuntime(purchase)) {
            assertEquals("SCHEDULED:t-po", runtime.schedulePurchaseOrder("po", "t-po", 100L,
                    "company", "purchase-1", 10L));
        }
        try (WorldRuntime runtime = WorldWebScenario.commandIntentRuntime(purchase)) {
            runtime.advanceTo(100L);
            assertTrue(runtime.snapshot().manufacturing().orElseThrow().reports()
                    .containsKey("t-po"), "采购到期必须留下回报");
        }
        assertTrue(commandContent(purchase, "cmd:po").contains("\"kind\":\""
                + PendingTask.MANUFACTURING_PURCHASE_ORDER_V1 + "\""),
                "新规则档命令正文必须记录意图种类");

        Path production = tempDir.resolve("intent-production.journal");
        try (WorldRuntime runtime = WorldWebScenario.commandIntentRuntime(production)) {
            runtime.scheduleProduction("pr", "t-pr", 100L, "company", "batch-1", 10L);
        }
        try (WorldRuntime runtime = WorldWebScenario.commandIntentRuntime(production)) {
            runtime.advanceTo(100L);
            assertTrue(runtime.snapshot().manufacturing().orElseThrow().reports()
                    .containsKey("t-pr"), "投产到期必须留下回报");
        }
    }

    @Test
    @DisplayName("新规则档：待办种类改成另一类必须拒绝（旧档同形歧义已可鉴别）")
    void newRuleKindTamperRejected() throws Exception {
        Path base = tempDir.resolve("intent-swap-base.journal");
        try (WorldRuntime runtime = WorldWebScenario.commandIntentRuntime(base)) {
            runtime.schedulePurchaseOrder("po", "t-po", 100L, "company", "purchase-1", 10L);
        }
        Path attack = tamperPendingKind(base, "cmd:po", "t-po",
                PendingTask.MANUFACTURING_PURCHASE_ORDER_V1,
                PendingTask.MANUFACTURING_PRODUCTION_V1);
        Path copy = tempDir.resolve("intent-swap-open.journal");
        Files.copy(attack, copy, StandardCopyOption.REPLACE_EXISTING);
        byte[] before = Files.readAllBytes(copy);
        IOException failure = assertThrows(IOException.class,
                () -> WorldWebScenario.commandIntentRuntime(copy));
        assertTrue(failure.getMessage().contains("排程帧无法由上一帧状态和原命令重算：t-po"),
                chain(failure));
        assertTrue(anyInChain(failure, "制造排程意图与待办种类不一致：t-po"), chain(failure));
        assertArrayEquals(before, Files.readAllBytes(copy), "拒绝不得修写日志");
    }

    /** 只改待办与排程事件的种类，命令正文保持原样：这正是旧档无法鉴别的攻击。 */
    private Path tamperPendingKind(Path base, String commandId, String taskId, String fromKind,
                                   String toKind) throws Exception {
        Path attack = tempDir.resolve("kind-only-" + System.nanoTime() + ".journal");
        Files.copy(base, attack, StandardCopyOption.REPLACE_EXISTING);
        int index = frameWithCommand(attack, commandId);
        JournalTamper.rewrite(attack, index, json -> {
            try {
                ObjectNode commit = (ObjectNode) JSON.readTree(json);
                boolean hitPending = false;
                for (JsonNode node : commit.get("events")) {
                    String type = node.get("type").asText();
                    String payload = node.get("payload").asText();
                    if ("STATE_SNAPSHOT_V1".equals(type)) {
                        String swapped = editPendingKind(payload, taskId, fromKind, toKind);
                        if (!swapped.equals(payload)) {
                            hitPending = true;
                        }
                        ((ObjectNode) node).put("payload", swapped);
                    } else if ("TASK_SCHEDULED_V1".equals(type)
                            && payload.contains("\"taskId\":\"" + taskId + "\"")) {
                        ((ObjectNode) node).put("payload",
                                payload.replace("\"kind\":\"" + fromKind + "\"",
                                        "\"kind\":\"" + toKind + "\""));
                    }
                }
                if (!hitPending) {
                    throw new IllegalStateException("待办未命中：" + taskId);
                }
                return JSON.writeValueAsString(commit);
            } catch (IllegalStateException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        return attack;
    }

    @Test
    @DisplayName("新规则档：命令正文缺意图种类必须拒绝，不能退回按待办分派")
    void newRuleMissingIntentRejected() throws Exception {
        Path base = tempDir.resolve("intent-missing-base.journal");
        try (WorldRuntime runtime = WorldWebScenario.commandIntentRuntime(base)) {
            runtime.schedulePurchaseOrder("po", "t-po", 100L, "company", "purchase-1", 10L);
        }
        Path attack = tempDir.resolve("intent-missing.journal");
        Files.copy(base, attack, StandardCopyOption.REPLACE_EXISTING);
        int index = frameWithCommand(attack, "cmd:po");
        JournalTamper.rewrite(attack, index, json -> {
            try {
                ObjectNode commit = (ObjectNode) JSON.readTree(json);
                String content = commit.get("commandContent").asText();
                String stripped = content.replace(",\"kind\":\""
                        + PendingTask.MANUFACTURING_PURCHASE_ORDER_V1 + "\"", "");
                if (stripped.equals(content)) {
                    throw new IllegalStateException("命令正文未含意图种类");
                }
                commit.put("commandContent", stripped);
                for (JsonNode node : commit.get("events")) {
                    if (!"STATE_SNAPSHOT_V1".equals(node.get("type").asText())) {
                        continue;
                    }
                    String payload = node.get("payload").asText();
                    String updated = payload.replace("\"po\":\"" + escape(content) + "\"",
                            "\"po\":\"" + escape(stripped) + "\"");
                    ((ObjectNode) node).put("payload", updated);
                }
                return JSON.writeValueAsString(commit);
            } catch (IllegalStateException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        byte[] before = Files.readAllBytes(attack);
        IOException failure = assertThrows(IOException.class,
                () -> WorldWebScenario.commandIntentRuntime(attack));
        assertTrue(anyInChain(failure, "新规则制造命令缺少意图种类"),
                chain(failure));
        assertArrayEquals(before, Files.readAllBytes(attack), "拒绝不得修写日志");
    }

    /** 异常链里是否出现指定原因。 */
    private static boolean anyInChain(Throwable failure, String needle) {
        Throwable step = failure;
        int guard = 0;
        while (step != null && guard++ < 8) {
            if (step.getMessage() != null && step.getMessage().contains(needle)) {
                return true;
            }
            step = step.getCause();
        }
        return false;
    }

    @Test
    @DisplayName("旧规则档：真实旧日志命令原文不含种类，旧写→新续→旧读仍可恢复")
    void oldRuleJournalKeepsOriginalFormat() throws Exception {
        Path journal = tempDir.resolve("old-format.journal");
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(journal)) {
            runtime.schedulePurchaseOrder("po-old", "t-po-old", 100L, "company", "purchase-9", 10L);
        }
        String oldContent = commandContent(journal, "cmd:po-old");
        assertFalse(oldContent.contains("\"kind\""), "旧规则命令原文不得出现种类字段：" + oldContent);

        // 新代码在旧档上继续写：仍按旧规则格式，不隐式补种类
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(journal)) {
            runtime.scheduleProduction("pr-old", "t-pr-old", 200L, "company", "batch-9", 10L);
        }
        assertFalse(commandContent(journal, "cmd:pr-old").contains("\"kind\""),
                "旧规则续写也不得补种类字段");
        // 旧规则再次打开仍可恢复并执行
        try (WorldRuntime runtime = WorldWebScenario.groupsRuntime(journal)) {
            runtime.advanceTo(200L);
            assertTrue(runtime.snapshot().manufacturing().orElseThrow().reports()
                    .containsKey("t-pr-old"), "旧档续写后必须可执行");
        }
        // 新规则档不得打开旧规则日志（清单/规则显式绑定）
        assertThrows(IOException.class,
                () -> WorldWebScenario.commandIntentRuntime(journal).close(),
                "新规则档不得读取旧格式日志");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 复制基础日志，只改：命令正文、commandContents 里的同一条、待办与排程事件的种类。 */
    private Path tamper(Path base, String commandId, String taskId, String fromKind,
                        String toKind) throws Exception {
        Path attack = tempDir.resolve("tampered-" + System.nanoTime() + ".journal");
        Files.copy(base, attack, StandardCopyOption.REPLACE_EXISTING);
        int index = frameWithCommand(attack, commandId);
        String mapKey = commandId.startsWith("cmd:") ? commandId.substring(4) : commandId;
        JournalTamper.rewrite(attack, index, json -> {
            try {
                ObjectNode commit = (ObjectNode) JSON.readTree(json);
                String oldContent = commit.get("commandContent").asText();
                String newContent = oldContent.replace("\"kind\":\"" + fromKind + "\"",
                        "\"kind\":\"" + toKind + "\"");
                if (newContent.equals(oldContent)) {
                    throw new IllegalStateException("命令正文未含意图种类");
                }
                commit.put("commandContent", newContent);
                boolean hitPending = false;
                for (JsonNode node : commit.get("events")) {
                    String type = node.get("type").asText();
                    String payload = node.get("payload").asText();
                    if ("STATE_SNAPSHOT_V1".equals(type)) {
                        String updated = payload.replace(
                                "\"" + mapKey + "\":\"" + escape(oldContent) + "\"",
                                "\"" + mapKey + "\":\"" + escape(newContent) + "\"");
                        String swapped = editPendingKind(updated, taskId, fromKind, toKind);
                        if (!swapped.equals(updated)) {
                            hitPending = true;
                        }
                        ((ObjectNode) node).put("payload", swapped);
                    } else if ("TASK_SCHEDULED_V1".equals(type)
                            && payload.contains("\"taskId\":\"" + taskId + "\"")) {
                        ((ObjectNode) node).put("payload",
                                payload.replace("\"kind\":\"" + fromKind + "\"",
                                        "\"kind\":\"" + toKind + "\""));
                    }
                }
                if (!hitPending) {
                    throw new IllegalStateException("待办未命中：" + taskId);
                }
                return JSON.writeValueAsString(commit);
            } catch (IllegalStateException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        return attack;
    }

    /** 只改 pending 数组里目标任务的种类字段。 */
    private static String editPendingKind(String snapshot, String taskId, String from, String to) {
        int at = snapshot.indexOf("\"pending\":[");
        if (at < 0) {
            throw new IllegalStateException("待办集合未命中");
        }
        int start = snapshot.indexOf('{', at);
        while (start >= 0) {
            int end = matchingBrace(snapshot, start);
            String object = snapshot.substring(start, end + 1);
            if (object.contains("\"taskId\":\"" + taskId + "\"")) {
                String updated = object.replace("\"kind\":\"" + from + "\"",
                        "\"kind\":\"" + to + "\"");
                return snapshot.substring(0, start) + updated + snapshot.substring(end + 1);
            }
            start = snapshot.indexOf('{', end + 1);
        }
        return snapshot;
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

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 失败时把整条异常链摊平，便于定位是哪一道校验先拒。 */
    private static String chain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable step = failure;
        int guard = 0;
        while (step != null && guard++ < 8) {
            text.append(step.getClass().getSimpleName()).append(": ")
                    .append(step.getMessage()).append('\n');
            step = step.getCause();
        }
        return text.toString();
    }

    private static List<String> iterable(java.util.Iterator<String> names) {
        List<String> list = new java.util.ArrayList<>();
        names.forEachRemaining(list::add);
        return list;
    }

    private static String commandContent(Path file, String commandId) throws Exception {
        List<JournalTamper.Frame> frames = JournalTamper.frames(file);
        for (JournalTamper.Frame frame : frames) {
            JsonNode commit = JSON.readTree(new String(frame.payload(), StandardCharsets.UTF_8));
            if (commandId.equals(commit.get("commandId").asText())) {
                return commit.get("commandContent").asText();
            }
        }
        throw new IllegalStateException("没有命令帧：" + commandId);
    }

    private static int frameWithCommand(Path file, String commandId) throws Exception {
        List<JournalTamper.Frame> frames = JournalTamper.frames(file);
        for (int i = frames.size() - 1; i >= 0; i--) {
            JsonNode commit = JSON.readTree(new String(frames.get(i).payload(),
                    StandardCharsets.UTF_8));
            if (commandId.equals(commit.get("commandId").asText())) {
                return i;
            }
        }
        throw new IllegalStateException("没有命令帧：" + commandId);
    }
}
