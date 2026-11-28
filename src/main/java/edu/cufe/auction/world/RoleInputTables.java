package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** 仅在本人输入超过原限制时，用有列名的表表示目录、持仓和行情，并按名称列出工具参数类型。 */
public final class RoleInputTables {
    /** 运行清单中的角色表格策略字段。 */
    public static final String POLICY = "role-input-tables";
    /** 仅在超限时改变显示方式的档案版本。 */
    public static final String VERSION = "tables-on-overflow-v1";
    /** 十公司档在原表格仍超过本人输入限额时，将会议动作的逐项条件显示成行表。 */
    public static final String BOARD_VERSION = "board-tables-on-overflow-v2";
    /** 已确认的输入不变，仅在十公司新档剩余超长时允许行情表包含自述字段的例外行。 */
    static final String MIXED_MARKET_FORMAT = "board-tables-mixed-market-overflow-v1";
    private static final List<String> TABLES = List.of("catalog", "holdings", "market");
    private static final List<String> CONDITION_FIELDS = List.of("timing", "blockers", "unknown", "status", "executorRevalidates");
    private static final List<String> TIMING_FIELDS = List.of("actionMillis", "earliestCompletionMillis");
    private static final List<String> BOARD_COLUMNS = List.of("tool", "timing.actionMillis", "timing.earliestCompletionMillis",
            "blockers", "unknown", "status", "executorRevalidates");

    private RoleInputTables() { }

    /** @return 是否启用带列名的本人数据表 */
    public static boolean enabled(String version) {
        return VERSION.equals(version) || BOARD_VERSION.equals(version) || MIXED_MARKET_FORMAT.equals(version);
    }

    static void compact(ObjectNode input) {
        if (input.get("profile") instanceof ObjectNode profile) {
            for (String field : TABLES) {
                if (!(profile.get(field) instanceof ArrayNode rows) || rows.size() < 2 || !rows.get(0).isObject()) continue;
                List<String> names = keys(rows.get(0));
                if (names.isEmpty() || !sameColumns(rows, names)) continue;
                ObjectNode table = profile.objectNode();
                ArrayNode columns = table.putArray("columns");
                names.forEach(columns::add);
                ArrayNode values = table.putArray("rows");
                for (JsonNode row : rows) {
                    ArrayNode cells = values.addArray();
                    names.forEach(name -> cells.add(row.get(name)));
                }
                profile.set(field, table);
            }
        }
        for (JsonNode tool : input.path("tools")) {
            if (!(tool instanceof ObjectNode definition) || !(tool.get("parameters") instanceof ArrayNode parameters)
                    || parameters.isEmpty()) continue;
            ObjectNode types = definition.objectNode();
            for (JsonNode parameter : parameters) {
                if (!keys(parameter).equals(List.of("name", "type")) || !parameter.path("name").isTextual()
                        || !parameter.path("type").isTextual() || types.has(parameter.get("name").textValue())) break;
                types.set(parameter.get("name").textValue(), parameter.get("type"));
            }
            if (types.size() == parameters.size()) definition.set("parameters", types);
        }
    }

    /**
     * 调度比较可见事实时还原原有记录形状，避免单纯的显示格式变化产生唤醒。
     * @param input 运行层生成的本人输入副本
     */
    public static void expand(ObjectNode input) {
        expandPlanningAndHistory(input);
        expandBoardConditions(input);
        if (input.get("profile") instanceof ObjectNode profile) {
            for (String field : List.of("catalog", "holdings", "market", "readExperiences")) {
                if (!(profile.get(field) instanceof ObjectNode table)) continue;
                ArrayNode records = profile.arrayNode();
                JsonNode columns = table.required("columns");
                for (JsonNode cells : table.required("rows")) {
                    if (cells.isObject()) { records.add(cells); continue; }
                    ObjectNode row = records.addObject();
                    for (int i = 0; i < columns.size(); i++) row.set(columns.get(i).asText(), cells.required(i));
                }
                profile.set(field, records);
            }
        }
        for (JsonNode tool : input.path("tools")) {
            if (!(tool instanceof ObjectNode definition) || !(tool.get("parameters") instanceof ObjectNode types)) continue;
            ArrayNode parameters = definition.putArray("parameters");
            types.properties().forEach(entry ->
                    parameters.addObject().put("name", entry.getKey()).set("type", entry.getValue()));
        }
    }

    /** 共享最常见的行情行格式；少数含额外公开字段的证券按原对象展示，保留缺失、空值与字段顺序。 */
    static void compactMixedMarket(ObjectNode input) {
        if (input.get("profile") instanceof ObjectNode profile) compactRecords(profile, "market");
    }

    private static void compactRecords(ObjectNode profile, String field) {
        if (!(profile.get(field) instanceof ArrayNode rows)) return;
        var counts = new java.util.LinkedHashMap<List<String>,Integer>();
        for (var row : rows) {
            if (!row.isObject()) return;
            counts.merge(keys(row), 1, Integer::sum);
        }
        List<String> names = List.of();
        int most = 1;
        for (var entry : counts.entrySet()) if (entry.getValue() > most) {
            names = entry.getKey();
            most = entry.getValue();
        }
        if (names.isEmpty()) return;
        var table = profile.objectNode();
        var columns = table.putArray("columns");
        names.forEach(columns::add);
        var values = table.putArray("rows");
        for (var row : rows) {
            if (!keys(row).equals(names)) { values.add(row); continue; }
            var cells = values.addArray();
            for (String name : names) cells.add(row.get(name));
        }
        profile.set(field, table);
    }

    static void compactPlanningAndHistory(ObjectNode input) {
        if (input.get("profile") instanceof ObjectNode profile) compactRecords(profile, "readExperiences");
        if (input.at("/engineering/planning") instanceof ObjectNode planning
                && planning.get("actions") instanceof ObjectNode actions && actions.size() >= 2
                && actions.properties().stream().allMatch(entry -> keys(entry.getValue()).equals(TIMING_FIELDS))) {
            ObjectNode table = planning.putObject("actions");
            var columns = table.putArray("columns").add("tool");
            TIMING_FIELDS.forEach(columns::add);
            var rows = table.putArray("rows");
            actions.properties().forEach(entry -> {
                var cells = rows.addArray().add(entry.getKey());
                TIMING_FIELDS.forEach(field -> cells.add(entry.getValue().get(field)));
            });
        }
        if (input.at("/engineering/deliberation") instanceof ObjectNode history
                && history.get("lastActionResult") instanceof ObjectNode last
                && history.get("recentDecisions") instanceof ArrayNode decisions) {
            for (int i = 0; i < decisions.size(); i++) {
                if (last.toString().equals(decisions.get(i).toString())) {
                    history.putObject("lastActionResult").put("$ref", "#/engineering/deliberation/recentDecisions/" + i);
                    break;
                }
            }
        }
        if (input.at("/engineering/decisionGuide") instanceof ObjectNode guide
                && guide.path("waitContract").isTextual()
                && guide.get("waitContract").equals(input.at("/engineering/deliberation/replyContract"))) {
            guide.putObject("waitContract").put("$ref", "#/engineering/deliberation/replyContract");
        }
    }

    private static void expandPlanningAndHistory(ObjectNode input) {
        if (input.at("/engineering/planning") instanceof ObjectNode planning
                && planning.get("actions") instanceof ObjectNode table && table.path("columns").isArray()
                && table.get("rows") instanceof ArrayNode rows) {
            var actions = planning.putObject("actions");
            for (var cells : rows) {
                var timing = actions.putObject(cells.required(0).asText());
                for (int i = 0; i < TIMING_FIELDS.size(); i++) timing.set(TIMING_FIELDS.get(i), cells.required(i + 1));
            }
        }
        for (String path : List.of("/engineering/deliberation/lastActionResult", "/engineering/decisionGuide/waitContract")) {
            JsonNode value = input.at(path);
            if (!value.isObject() || value.size() != 1 || !value.path("$ref").isTextual()) continue;
            String ref = value.get("$ref").textValue();
            if (!ref.startsWith("#/engineering/deliberation/")) continue;
            int split = path.lastIndexOf('/');
            ((ObjectNode) input.at(path.substring(0, split))).set(path.substring(split + 1), input.requiredAt(ref.substring(1)).deepCopy());
        }
    }

    static void compactBoardConditions(ObjectNode input) {
        for (JsonNode director : input.path("engineering").path("directorships")) {
            for (JsonNode meeting : director.path("meetings")) {
                if (!(meeting.get("planningPrerequisites") instanceof ObjectNode facts)) continue;
                List<String> fields = keys(facts);
                if (fields.size() < 4 || !fields.subList(0, 2).equals(List.of("currentPhase", "completionWindow"))) continue;
                List<String> tools = fields.subList(2, fields.size());
                if (!tools.stream().allMatch(tool -> tool.startsWith("board.")
                        && keys(facts.get(tool)).equals(CONDITION_FIELDS)
                        && keys(facts.get(tool).path("timing")).equals(TIMING_FIELDS))) continue;
                var table = facts.putObject("actions");
                var columns = table.putArray("columns");
                BOARD_COLUMNS.forEach(columns::add);
                var rows = table.putArray("rows");
                for (String tool : tools) {
                    JsonNode original = facts.remove(tool);
                    var cells = rows.addArray().add(tool);
                    TIMING_FIELDS.forEach(name -> cells.add(original.get("timing").get(name)));
                    CONDITION_FIELDS.subList(1, CONDITION_FIELDS.size()).forEach(name -> cells.add(original.get(name)));
                }
            }
        }
    }

    static void expandBoardConditions(ObjectNode input) {
        for (JsonNode director : input.path("engineering").path("directorships")) {
            for (JsonNode meeting : director.path("meetings")) {
                if (!(meeting.get("planningPrerequisites") instanceof ObjectNode facts)
                        || !(facts.get("actions") instanceof ObjectNode table)) continue;
                facts.remove("actions");
                for (JsonNode cells : table.required("rows")) {
                    var original = facts.putObject(cells.required(0).textValue());
                    var timing = original.putObject("timing");
                    for (int i=0; i<TIMING_FIELDS.size(); i++) timing.set(TIMING_FIELDS.get(i), cells.required(i+1));
                    for (int i=1; i<CONDITION_FIELDS.size(); i++) original.set(CONDITION_FIELDS.get(i), cells.required(i+2));
                }
            }
        }
    }

    private static boolean sameColumns(ArrayNode rows, List<String> names) {
        for (JsonNode row : rows) {
            if (!row.isObject() || !keys(row).equals(names)) return false;
        }
        return true;
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
