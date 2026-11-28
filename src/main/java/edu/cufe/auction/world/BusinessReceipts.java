package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

/** 将已核对和排序的经营回执投影给本人，内部位置仅供审计和绑定。 */
public final class BusinessReceipts {
    private BusinessReceipts() { }

    /** @return 运行是否启用本人业务回执合同 */
    public static boolean enabled(RunManifest manifest) {
        return "business-receipts-v1".equals(manifest.dataVersions().get("role-report-privacy"));
    }

    /** 按本人的决定数生成业务标识，并避开已有人工命令占用的全运行唯一标识。 */
    public static String nextActionId(DecisionModel.State decisions, String person) {
        long number = 1;
        var used = new java.util.HashSet<String>();
        for (var decision : decisions.decisions()) {
            used.add(decision.actionId());
            if (person.equals(decision.personPartyId())) number++;
        }
        String prefix = "person-decision:" + person + ":";
        while (used.contains(prefix + number)) number++;
        return prefix + number;
    }

    /** 只保留本人获准查看的业务字段与发生次序。源行仍保留审计原件。 */
    public static ArrayNode rows(ArrayNode source) {
        ArrayNode result = source.arrayNode();
        StreamSupport.stream(source.spliterator(), false)
                .sorted(Comparator.comparingLong(row -> row.path("position").asLong()))
                .forEach(row -> {
                    var item = result.addObject();
                    for (String name : List.of("companyPartyId", "timeMillis", "action", "businessId", "accepted", "reason", "amount"))
                        item.set(name, row.required(name));
                });
        return result;
    }

    /** 从最终角色回执移除全局截止；董事会效果绑定等内部核对已在此之前完成。 */
    static void project(ObjectNode engineering, String person, DecisionRun decisions, MediaRun media, long position, long time) {
        for (String name : List.of("hydroOperations", "resourceOperations", "digitalOperations", "agedOperations"))
            projectView(engineering.path(name));
        engineering.path("companyOperations").forEach(BusinessReceipts::projectView);
        JsonNode history = engineering.path("deliberation");
        var internal = List.of("resultPosition", "attemptId", "commandId", "boundTaskId", "resultTaskId");
        for (var row : history.path("recentDecisions")) {
            MediaActionFeedback.apply((ObjectNode) row, person, decisions, media, position, time);
            ((ObjectNode) row).remove(internal);
        }
        if (history.path("lastActionResult") instanceof ObjectNode last) {
            MediaActionFeedback.apply(last, person, decisions, media, position, time);
            last.remove(internal);
        }
    }

    private static void projectView(JsonNode node) {
        if (!(node instanceof ObjectNode view) || !(view.get("reports") instanceof ArrayNode reports)) return;
        view.set("reports", rows(reports));
        if (view.get("reportsHistory") instanceof ObjectNode meta) {
            meta.remove(List.of("retainedMinPosition", "retainedMaxPosition"));
            meta.put("selection", "按发生顺序取最新12条、最新6条非SETTLE、最新6条拒绝的并集，每项业务回执只显示一次。");
        }
    }
}
