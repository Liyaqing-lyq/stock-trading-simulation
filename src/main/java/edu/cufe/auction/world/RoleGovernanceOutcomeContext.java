package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.function.Function;

/** v5只读上下文：本人调用回报不变，另列当前有权看到的目标会议效果。 */
final class RoleGovernanceOutcomeContext {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL = "board.implement";
    private RoleGovernanceOutcomeContext() { }

    static void append(ObjectNode root, String person, long now, DecisionRun run,
                       EngineeringRun engineering, RoleRun roles, Function<String, BoardRun> boards) {
        if (!RoleDeliberation.CONTEXTUAL_VERSION.equals(root.path("roleContextVersion").asText())
                || run == null || engineering == null || roles == null) return;
        var deliberation = root.path("deliberation");
        for (var row : deliberation.path("recentDecisions"))
            project((ObjectNode) row, root, person, now, run, engineering, roles, boards);
        if (deliberation.path("lastActionResult") instanceof ObjectNode row)
            project(row, root, person, now, run, engineering, roles, boards);
    }

    private static void project(ObjectNode row, ObjectNode root, String person, long now,
                                DecisionRun run, EngineeringRun engineering, RoleRun roles,
                                Function<String, BoardRun> boards) {
        // Reprojection must never retain a prior annotation after a binding/authorization disappears.
        row.remove("targetMeetingContext");
        String actionId = row.path("actionId").asText();
        var decision = run.state().decisions().stream().filter(d -> d.actionId().equals(actionId)
                && d.personPartyId().equals(person)).findFirst().orElse(null);
        var ref = run.actionRef(actionId);
        var binding = run.binding(actionId);
        if (decision == null || decision.outcome() != DecisionModel.Outcome.INTENT
                || !TOOL.equals(decision.recordedTool()) || decision.attempts().isEmpty()
                || ref == null || !TOOL.equals(ref.tool()) || !actionId.equals(ref.actionId())
                || binding == null || !person.equals(binding.personPartyId())
                || !TOOL.equals(row.path("tool").asText())
                || !ref.taskId().equals(row.path("boundTaskId").asText())) return;
        var attempt = decision.attempts().get(decision.attempts().size() - 1);
        if (!attempt.settledDone() || !attempt.replyReceived() || attempt.outcome() != DecisionModel.Outcome.INTENT
                || !attempt.attemptId().equals(row.path("attemptId").asText())) return;
        String command = DecisionRun.actionCommandId(actionId);
        if (!command.equals(row.path("commandId").asText())) return;
        try {
            JsonNode accepted = RoleDeliberation.validatedAction(decision.inputText(), JSON.readTree(attempt.replyBody()));
            JsonNode args = accepted.path("args");
            if (!accepted.isObject() || accepted.size() != 2 || !TOOL.equals(accepted.path("tool").asText())
                    || !args.isObject() || args.size() != 2 || !text(args, "companyPartyId") || !text(args, "meetingId")) return;
            String company = args.path("companyPartyId").textValue(), meetingId = args.path("meetingId").textValue();
            boolean visible = false;
            for (var director : root.path("directorships"))
                if (company.equals(director.path("companyPartyId").asText()))
                    for (var meeting : director.path("meetings"))
                        if (meetingId.equals(meeting.path("meetingId").asText())) visible = true;
            if (!visible) return;
            var report = engineering.reports().values().stream()
                    .filter(r -> command.equals(r.commandId()) && (ref.taskId().equals(r.taskId())
                            || (ref.taskId()+":finish").equals(r.taskId())))
                    .max(Comparator.comparingLong(EngineeringRun.Report::position)).orElse(null);
            if (report == null || !company.equals(report.companyPartyId())
                    || !report.taskId().equals(row.path("resultTaskId").asText())
                    || report.position() != row.path("resultPosition").asLong(-1)
                    || !report.reason().equals(row.path("reason").asText())) return;
            BoardRun boardRun = boards.apply(company);
            if (boardRun == null || boardRun.board().charter().seatOf(person) == null) return;
            var board = boardRun.board();
            var meeting = board.meeting(meetingId);
            if (meeting == null) return;
            var context = row.putObject("targetMeetingContext").put("companyPartyId", company)
                    .put("meetingId", meetingId).put("asOfMillis", now)
                    .put("currentStatus", meeting.status().name())
                    .put("historicalInvocationScope", "THIS_COMMAND_ONLY");
            var effect = context.putObject("currentEffect");
            boolean implemented = meeting.status() == BoardModel.MeetingStatus.IMPLEMENTED;
            effect.put("resolutionImplemented", implemented);
            if (!implemented) return;
            if (meeting.topic() == BoardModel.Topic.MANAGER_APPOINTMENT)
                appointmentEffect(effect, meeting, roles, company, now);
            else if (meeting.topic() == BoardModel.Topic.BUDGET) {
                long period = now / ManufacturingTemplateModel.PERIOD_MILLIS;
                effect.put("currentBudgetPeriod", period);
                var caps = effect.putArray("authorizedBudgets");
                for (var budget : board.budgets()) if (budget.period() == period)
                    caps.addObject().put("period", period).put("category", budget.category().name())
                            .put("cap", budget.cap().toPlainString());
                effect.put("budgetScope", "当前期间的真实授权上限；不是新增现金，也不表示历史议案额度当前仍可用。");
            }
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            // An unbound/malformed command never grants access or manufactures a result.
        }
    }

    private static boolean text(JsonNode args, String field) {
        return args.path(field).isTextual() && !args.path(field).textValue().isBlank();
    }

    private static void appointmentEffect(ObjectNode effect, BoardModel.Meeting meeting, RoleRun roles,
                                          String company, long now) {
        effect.put("appointmentStatus", "UNKNOWN");
        if (meeting.appointmentProposal() == null || meeting.appointmentResolution() == null) return;
        var request = meeting.appointmentProposal().request();
        var appointment = roles.access().appointments().stream().filter(a -> a.id().equals(request.appointmentId())
                && a.organizationPartyId().equals(company) && a.personPartyId().equals(request.personPartyId())
                && a.role() == request.role()).findFirst().orElse(null);
        if (appointment == null) return;
        if (request.action() == BoardModel.AppointmentAction.APPOINT) {
            if (appointment.startsAt() != request.effectiveAt() || request.endsAt() == null
                    || appointment.endsAt() != request.endsAt()) return;
        } else if (appointment.revokedAt() == null
                || appointment.revokedAt() != meeting.appointmentResolution().issuedAtMillis()) return;
        String status = appointment.revokedAt() != null && now >= appointment.revokedAt() ? "REVOKED"
                : now < appointment.startsAt() ? "NOT_EFFECTIVE_YET"
                : appointment.activeAt(now) ? "ACTIVE" : "EXPIRED";
        effect.put("appointmentStatus", status).put("appointmentId", appointment.id())
                .put("effectiveAt", appointment.startsAt()).put("endsAt", appointment.endsAt())
                .put("effectiveEnd", appointment.effectiveEnd());
        if (appointment.revokedAt() != null) effect.put("revokedAt", appointment.revokedAt());
    }
}
