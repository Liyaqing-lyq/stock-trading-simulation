package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;

/** 将本人私信决定与写作、投递回执关联，不读取收件人观察或其屏蔽偏好。 */
final class SocialActionFeedback {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String,String> KINDS = Map.of(RoleTradingView.TOOL_SOCIAL_SEND, SocialRun.KIND_BEGIN,
            RoleTradingView.TOOL_SOCIAL_BLOCK, SocialRun.KIND_BLOCK, RoleTradingView.TOOL_SOCIAL_UNBLOCK, SocialRun.KIND_UNBLOCK);

    private SocialActionFeedback() { }

    static void apply(ObjectNode row, String person, DecisionRun decisions, SocialRun social,
                      Map<String,String> commands, long position, long time) {
        String tool = row.path("tool").asText();
        String kind = KINDS.get(tool);
        if (kind == null || social == null || decisions == null) return;
        String action = row.path("actionId").asText();
        var ref = decisions.actionRef(action);
        var binding = decisions.binding(action);
        if (ref == null || binding == null || !action.equals(ref.actionId()) || !action.equals(binding.actionId())
                || !person.equals(binding.personPartyId()) || !tool.equals(ref.tool())) return;
        var first = social.reportOf(ref.taskId());
        if (first == null || !ref.taskId().equals(first.taskId()) || !person.equals(first.person())
                || !kind.equals(first.kind()) || first.atMillis() < ref.dueMillis()
                || first.atMillis() > time || first.position() > position) return;
        if (!SocialRun.KIND_BEGIN.equals(kind)) {
            write(row, first, first.accepted() ? "APPLIED" : "REJECTED", first.reason());
            return;
        }
        if (!first.accepted()) {
            String reason = first.reason().equals("UNREACHABLE") ? "UNDELIVERED" : first.reason();
            write(row, first, "REJECTED", reason);
            row.put("messageStatus", "UNDELIVERED");
            return;
        }
        String command = DecisionRun.actionCommandId(action);
        String id = SocialMessageModel.messageId(person, command);
        if (!id.equals(first.messageId())) return;
        String recipient = recipient(commands.get(command));
        if (recipient == null) return;
        for (var draft : social.state().drafts()) {
            if (id.equals(draft.messageId()) && command.equals(draft.commandId()) && person.equals(draft.from())
                    && recipient.equals(draft.to()) && first.atMillis() == draft.startedAt()) {
                write(row, first, "IN_PROGRESS", first.reason());
                row.put("messageId", id).put("toPartyId", recipient).put("messageStatus", "WRITING");
                return;
            }
        }
        for (var message : social.state().messages()) {
            if (!id.equals(message.messageId()) || !command.equals(message.commandId()) || !person.equals(message.from())
                    || !recipient.equals(message.to()) || message.sentAt() < first.atMillis() || message.statusAt() > time) continue;
            var status = SocialMessageModel.outboxStatus(message.status());
            boolean finishedWriting = message.status() == SocialMessageModel.MessageStatus.QUEUED
                    || message.status() == SocialMessageModel.MessageStatus.ABORTED;
            String stage = finishedWriting ? SocialRun.KIND_FINISH : SocialRun.KIND_DELIVER;
            for (var report : social.reports()) {
                if (id.equals(report.messageId()) && person.equals(report.person()) && stage.equals(report.kind())
                        && report.atMillis() == message.statusAt() && report.position() > first.position()
                        && report.position() <= position && report.accepted() == (status != SocialMessageModel.OutboxStatus.UNDELIVERED)) {
                    String execution = switch (status) {
                        case QUEUED -> "IN_PROGRESS";
                        case DELIVERED -> "APPLIED";
                        case UNDELIVERED -> "REJECTED";
                    };
                    write(row, report, execution, status.name());
                    row.put("messageId", id).put("toPartyId", recipient).put("messageStatus", status.name());
                    return;
                }
            }
            return;
        }
    }

    private static String recipient(String placedCommand) {
        if (placedCommand == null) return null;
        try {
            var task = JSON.readTree(placedCommand);
            var begin = JSON.readTree(task.required("body").textValue());
            return JSON.readTree(begin.required("draftJson").textValue()).required("toPartyId").textValue();
        } catch (IOException invalid) {
            throw new IllegalStateException("已确认的私信命令无法读取", invalid);
        }
    }

    private static void write(ObjectNode row, SocialRun.SocialReport report, String execution, String reason) {
        row.put("execution", execution).put("reason", reason).put("resultKind", report.kind()).put("resultAt", report.atMillis());
        if ("APPLIED".equals(execution)) row.put("accepted", true);
        else if ("REJECTED".equals(execution)) row.put("accepted", false).put("businessReject", reason);
    }
}
