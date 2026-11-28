package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.List;

/** 工程经营档的经理兼任董事时，压缩已过窗的重复治理投影；合同、请求、应收及付款原样保留。 */
final class EngineeringManagerHistory {
    private EngineeringManagerHistory() { }

    static void project(ObjectNode input) {
        JsonNode engineering = input.path("engineering");
        if (!EngineeringCustomerPolicy.VERSION.equals(engineering.path("engineeringCustomerPolicy").asText())) return;
        long now = engineering.path("planning").path("nowMillis").asLong();
        if (!(engineering.path("planning") instanceof ObjectNode planning)) return;
        for (JsonNode row : engineering.path("directorships")) {
            ObjectNode director = (ObjectNode) row;
            JsonNode periods = director.get("periodic");
            if (periods != null) {
                for (JsonNode role : engineering.path("managerships")) {
                    ObjectNode manager = (ObjectNode) role;
                    if (director.path("companyPartyId").equals(manager.path("companyPartyId")) && periods.equals(manager.get("periodic"))) {
                        manager.remove("periodic");
                        manager.put("periodicFromDirectorCompanyId", director.path("companyPartyId").asText());
                        manager.put("duplicatePeriodicRowsOmitted", periods.size());
                    }
                }
                compactPeriods(director, now);
            }
            compactMeetings(director, planning);
        }
        groupTimings(planning);
    }

    private static void groupTimings(ObjectNode planning) {
        if (!(planning.get("actions") instanceof ObjectNode actions)) return;
        var groups = new java.util.LinkedHashMap<JsonNode, java.util.List<String>>();
        actions.fields().forEachRemaining(f -> groups.computeIfAbsent(f.getValue(), ignored -> new java.util.ArrayList<>()).add(f.getKey()));
        if (groups.size() == actions.size()) return;
        var timings = planning.putArray("actionTimingGroups");
        groups.forEach((timing, names) -> {
            var group = timings.addObject();
            group.setAll((ObjectNode)timing);
            var tools = group.putArray("tools");
            names.forEach(tools::add);
        });
        planning.remove("actions");
        planning.put("identicalTimingRowsOmitted",actions.size()-groups.size());
    }

    private static void compactMeetings(ObjectNode director, ObjectNode planning) {
        ArrayNode original = (ArrayNode) director.path("meetings");
        var history = new java.util.ArrayList<ObjectNode>();
        for (JsonNode meeting : original) {
            if (!"AFTER_CLOSE".equals(meeting.path("window").asText())) continue;
            int offered = 0, blocked = 0;
            var fields = meeting.path("planningPrerequisites").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getKey().startsWith("board.")) continue;
                offered++;
                if ("BLOCKED".equals(field.getValue().path("status").asText())) blocked++;
            }
            if (offered > 0 && offered == blocked) history.add((ObjectNode)meeting);
        }
        var latest = history.stream().max(Comparator.comparingLong((ObjectNode m) -> m.path("closesAt").asLong())
                .thenComparing(m -> m.path("meetingId").asText())).orElse(null);
        var retained = director.putArray("meetings");
        for (JsonNode m : original) {
            if (m != latest && history.contains(m)) continue;
            if (m == latest) {
                int count = 0;
                var keys = latest.remove("planningPrerequisites").fieldNames();
                while (keys.hasNext()) if (keys.next().startsWith("board.")) count++;
                latest.put("blockedPlanningRowsOmitted", count);
            }
            var shown = (ObjectNode)m;
            if (director.has("procedure") && director.get("procedure").equals(shown.get("procedure"))) {
                shown.remove("procedure");
                shown.put("procedureFromCompany",true);
            }
            if (shown.get("planningPrerequisites") instanceof ObjectNode constraints) {
                if (shown.path("status").equals(constraints.path("currentPhase"))) constraints.remove("currentPhase");
                JsonNode window = constraints.get("completionWindow");
                if (window != null && (!planning.has("boardCompletionWindow") || window.equals(planning.get("boardCompletionWindow")))) {
                    planning.set("boardCompletionWindow",window);
                    constraints.remove("completionWindow");
                }
                int sharedTiming = 0, repeatedChecks = 0;
                var fields = constraints.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    if (!(entry.getValue() instanceof ObjectNode rule) || !entry.getKey().startsWith("board.")) continue;
                    if (planning.path("actions").path(entry.getKey()).equals(rule.get("timing"))) {
                        rule.remove("timing"); sharedTiming++;
                    }
                    if (rule.path("executorRevalidates").asBoolean()) {
                        rule.remove("executorRevalidates"); repeatedChecks++;
                    }
                }
                if (sharedTiming > 0) constraints.put("timingsInPlanning", sharedTiming);
                if (repeatedChecks > 0) constraints.put("executorRevalidatesRules", repeatedChecks);
            }
            retained.add(m);
        }
        if (!history.isEmpty()) director.put("closedMeetingRowsOmitted", history.size()-1);
    }

    private static void compactPeriods(ObjectNode director, long now) {
        ArrayNode periods = (ArrayNode) director.path("periodic");
        int last = 0;
        for (JsonNode p : periods) last = Math.max(last,p.path("period").asInt());
        int count = 0;
        for (JsonNode p : periods) {
            if (p.path("period").asInt() >= last || !p.path("deadline").canConvertToLong() || p.path("deadline").asLong() >= now) continue;
            // 每期保存原始材料入口和披露状态。仍在审核/待授权的草稿保留完整可执行字段。
            if (p.has("draftId") || p.has("authorizationId")) continue;
            ObjectNode row = (ObjectNode)p;
            int evidenceCount = row.path("evidence").size();
            row.retain(List.of("period","materialDocId","materialVersion","meetingId","deadline","publishedVersions","publicDocId","missed","reviewClosed"));
            row.put("evidenceRefsOmitted", evidenceCount);
            count++;
        }
        if (count > 0) director.put("pastPeriodRowsCondensed",count);
    }
}
