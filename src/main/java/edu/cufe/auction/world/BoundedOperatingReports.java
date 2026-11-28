package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** bounded-history-v1：仅裁剪已授权的经理报告投影，完整账本不变。 */
final class BoundedOperatingReports {
    static final String VERSION = "bounded-history-v1";
    private static final Comparator<JsonNode> ORDER = Comparator
            .comparingLong((JsonNode row) -> row.path("position").asLong())
            .thenComparing(row -> row.path("taskId").asText());
    private BoundedOperatingReports() { }

    static void project(ObjectNode engineering) {
        for (String name : List.of("hydroOperations", "resourceOperations", "digitalOperations", "agedOperations"))
            if (engineering.get(name) instanceof ObjectNode view) projectView(view);
        for (JsonNode view : engineering.path("companyOperations")) projectView((ObjectNode) view);
    }

    private static void projectView(ObjectNode view) {
        List<JsonNode> full = new ArrayList<>();
        view.path("reports").forEach(full::add);
        full.sort(ORDER);
        Map<String, JsonNode> retained = new LinkedHashMap<>();
        retainTail(retained, full, 12);
        retainTail(retained, full.stream().filter(row -> !"SETTLE".equals(row.path("action").asText())).toList(), 6);
        retainTail(retained, full.stream().filter(row -> !row.path("accepted").asBoolean()).toList(), 6);
        List<JsonNode> shown = retained.values().stream().sorted(ORDER).toList();
        var reports = view.putArray("reports");
        shown.forEach(reports::add); // 保留可信当前行的字段和值类型，不使用历史输入或重写金额。
        var metadata = view.putObject("reportsHistory");
        metadata.put("policy", VERSION);
        metadata.put("selection", "按(position,taskId)取最新12条、最新6条非SETTLE、最新6条拒绝的并集，按taskId去重；最多24条");
        metadata.put("fullTotal", full.size());
        metadata.put("shown", shown.size());
        metadata.put("omitted", full.size() - shown.size());
        if (shown.isEmpty()) {
            for (String key : List.of("retainedMinPosition", "retainedMaxPosition", "retainedMinTimeMillis", "retainedMaxTimeMillis")) metadata.putNull(key);
        } else {
            metadata.set("retainedMinPosition", shown.get(0).path("position"));
            metadata.set("retainedMaxPosition", shown.get(shown.size() - 1).path("position"));
            metadata.set("retainedMinTimeMillis", shown.stream().min(Comparator.comparingLong(row -> row.path("timeMillis").asLong())).orElseThrow().path("timeMillis"));
            metadata.set("retainedMaxTimeMillis", shown.stream().max(Comparator.comparingLong(row -> row.path("timeMillis").asLong())).orElseThrow().path("timeMillis"));
        }
        metadata.put("scope", "仅本公司报告窗口；遗漏可含更早失败，非SETTLE不等于人为动作。完整记录仍保留在运行审计，本输入未提供历史查询工具。资料和合同列表仍可能使输入超限。");
    }

    private static void retainTail(Map<String, JsonNode> retained, List<JsonNode> rows, int count) {
        for (int i = Math.max(0, rows.size() - count); i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            retained.put(row.path("taskId").asText(), row);
        }
    }
}
