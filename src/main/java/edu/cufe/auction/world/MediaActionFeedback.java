package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import static edu.cufe.auction.world.RoleTradingView.*;

/** 只用本人决定绑定的正式媒体任务回执确认其结果，不从稿件的当前状态倒推。 */
final class MediaActionFeedback {
    private static final Map<String,String> KINDS = Map.of(
            TOOL_MEDIA_PROPOSE, MediaRun.KIND_PROPOSE, TOOL_MEDIA_SUBMIT, MediaRun.KIND_SUBMIT,
            TOOL_MEDIA_REVIEW, MediaRun.KIND_REVIEW, TOOL_MEDIA_PUBLISH, MediaRun.KIND_PUBLISH,
            TOOL_MEDIA_CORRECTION, MediaRun.KIND_CORRECTION, TOOL_MEDIA_CANCEL, MediaRun.KIND_CANCEL,
            TOOL_MEDIA_EVIDENCE_SHARE, MediaRun.KIND_EVIDENCE_SHARE, TOOL_MEDIA_WITHDRAW, MediaRun.KIND_WITHDRAW);

    private MediaActionFeedback() { }

    static void apply(ObjectNode row, String person, DecisionRun decisions, MediaRun media, long position, long time) {
        if (decisions == null || media == null) return;
        String action = row.path("actionId").asText();
        var ref = decisions.actionRef(action);
        var binding = decisions.binding(action);
        if (ref == null || binding == null || !action.equals(ref.actionId()) || !person.equals(binding.personPartyId())
                || !ref.tool().equals(row.path("tool").asText())) return;
        String kind = KINDS.get(ref.tool());
        if (kind == null) return;
        var report = media.reports().get(ref.taskId());
        if (report != null && report.taskId().equals(ref.taskId()) && person.equals(report.personPartyId())
                && kind.equals(report.kind()) && ref.dueMillis() == report.timeMillis() && report.timeMillis() <= time
                && report.position() <= position) {
            row.put("execution", report.accepted() ? "APPLIED" : "REJECTED").put("accepted", report.accepted())
                    .put("reason",report.reason()).put("resultKind",kind).put("resultAt",report.timeMillis());
            if (!report.accepted()) row.put("businessReject",report.reason());
            else if (!report.storyId().isEmpty()) row.put("storyId",report.storyId());
        } else if (report == null) {
            for (var work : media.works()) {
                if (work.taskId().equals(ref.taskId()) && person.equals(work.personPartyId()) && kind.equals(work.kind())
                        && MediaRun.OUTCOME_CANCELED.equals(work.outcome()) && work.completedAtMillis() <= time
                        && work.completedAtMillis() <= ref.dueMillis()
                        && work.startedAtMillis() == ref.dueMillis() - MediaRun.durationOf(media.config(),kind,work.organizationPartyId())) {
                    row.put("execution", "CANCELED").put("reason",work.outcome()).put("resultKind",kind)
                            .put("resultAt",work.completedAtMillis());
                    break;
                }
            }
        }
    }
}
