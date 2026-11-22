package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在本人已获准的董事资料内，给完全相同的流程条文生成可解析的 JSON Pointer 引用。 */
final class BoardProcedureView {
    static final String VERSION = "shared-rules-v1";

    private BoardProcedureView() { }

    static void project(ObjectNode input) {
        if (!(input.path("engineering") instanceof ObjectNode engineering)) return;
        Map<String, List<ObjectNode>> procedures = new LinkedHashMap<>();
        Map<String, List<ObjectNode>> windows = new LinkedHashMap<>();
        for (JsonNode director : engineering.path("directorships")) {
            collect(procedures, director, "procedure");
            for (JsonNode meeting : director.path("meetings")) {
                collect(procedures, meeting, "procedure");
                collect(windows, meeting.path("planningPrerequisites"), "completionWindow");
            }
        }
        ObjectNode rules = engineering.objectNode();
        share(rules, procedures, "procedure");
        share(rules, windows, "completionWindow");
        if (!rules.isEmpty()) engineering.set("boardRules", rules);
    }

    private static void collect(Map<String, List<ObjectNode>> byText, JsonNode row, String field) {
        if (row instanceof ObjectNode object && object.path(field).isTextual()) {
            byText.computeIfAbsent(object.get(field).textValue(), ignored -> new ArrayList<>()).add(object);
        }
    }

    private static void share(ObjectNode rules, Map<String, List<ObjectNode>> byText, String field) {
        int number = 0;
        for (var entry : byText.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            number++;
            String name = number == 1 ? field : field + number;
            rules.put(name, entry.getKey());
            String pointer = "#/engineering/boardRules/" + name;
            for (ObjectNode row : entry.getValue()) row.putObject(field).put("$ref", pointer);
        }
    }
}
