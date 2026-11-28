package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;

/** 本人群动作的已确认阶段投影；只沿实际提交的排程边，不猜测任务名字。 */
final class GroupActionFeedback {
    private static final ObjectMapper JSON = new ObjectMapper();
    record Identity(String person, String command, String action) { }
    @FunctionalInterface interface TaskIdentity {
        Identity parse(PendingTask task, String actionId, String person);
    }
    private record Outcome(GroupsRun.GroupReport report, String execution) { }
    private record Stage(PendingTask task, WorldCommit commit) { }
    private GroupActionFeedback() { }

    static void append(ObjectNode root, String person, DecisionRun decisions, List<PendingTask> pending,
                       GroupsRun groups, long position, long time,
                       LongFunction<Optional<WorldCommit>> commits, TaskIdentity parser) {
        if (decisions == null || groups == null) return;
        Map<String, GroupsRun.GroupReport> reports = new HashMap<>();
        for (var report : groups.reports()) if (report.position() > 0 && report.position() <= position
                && report.atMillis() <= time) reports.put(report.taskId(), report);
        Map<String, Optional<Outcome>> memo = new HashMap<>();
        Map<Long, Optional<WorldCommit>> frames = new HashMap<>();
        var deliberation = root.path("deliberation");
        for (var row : deliberation.path("recentDecisions")) project((ObjectNode) row, person,
                decisions, pending, reports, position, time, commits, parser, memo, frames);
        if (deliberation.path("lastActionResult") instanceof ObjectNode row)
            project(row, person, decisions, pending, reports, position, time, commits, parser, memo, frames);
    }

    private static void project(ObjectNode row, String person, DecisionRun decisions,
            List<PendingTask> pending, Map<String, GroupsRun.GroupReport> reports, long position, long time,
            LongFunction<Optional<WorldCommit>> commits, TaskIdentity parser,
            Map<String, Optional<Outcome>> memo, Map<Long, Optional<WorldCommit>> frames) {
        String action = row.path("actionId").asText();
        var outcome = memo.computeIfAbsent(action, key -> resolve(key, person, decisions, pending,
                reports, position, time, commits, parser, frames));
        if (outcome.isEmpty()) return; // 无准确原报告时，不碰旧版任何字段。
        var result = outcome.get();
        var report = result.report();
        row.remove(List.of("accepted", "businessReject"));
        row.put("execution", result.execution()).put("resultTaskId", report.taskId())
                .put("resultKind", report.kind()).put("resultAt", report.atMillis())
                .put("resultPosition", report.position()).put("reason", report.reason());
        if ("REJECTED".equals(result.execution())) row.put("accepted", false).put("businessReject", report.reason());
        else if ("APPLIED".equals(result.execution())) row.put("accepted", true);
    }

    private static Optional<Outcome> resolve(String action, String person, DecisionRun decisions,
            List<PendingTask> pending, Map<String, GroupsRun.GroupReport> reports, long position, long time,
            LongFunction<Optional<WorldCommit>> commits, TaskIdentity parser,
            Map<Long, Optional<WorldCommit>> frames) {
        var ref = decisions.actionRef(action);
        String expectedAction = ref == null ? null : actionOf(ref.tool());
        if (expectedAction == null || decisions.state().decisions().stream().noneMatch(d ->
                d.actionId().equals(action) && d.personPartyId().equals(person))) return Optional.empty();
        var original = reports.get(ref.taskId());
        if (original == null || !GroupsRun.KIND_BEGIN.equals(original.kind())
                || original.atMillis() != ref.dueMillis() || !person.equals(original.person())
                || !expectedAction.equals(original.action())) return Optional.empty();
        Stage stage = stage(original, null, action, person, expectedAction, position, time, commits, parser, frames);
        if (stage == null || !stage.task().taskId().equals(ref.taskId())
                || stage.task().dueTimeMillis() != ref.dueMillis()) return Optional.empty();
        var report = original;
        for (int count = 0; count < 3; count++) {
            if (!report.accepted()) return Optional.of(new Outcome(report, "REJECTED"));
            String nextKind = GroupsRun.KIND_BEGIN.equals(report.kind()) ? GroupsRun.KIND_FINISH
                    : GroupsRun.KIND_FINISH.equals(report.kind()) && publishes(expectedAction) ? GroupsRun.KIND_PUBLISH : null;
            List<WorldEvent> edges = stage.commit().events().stream().filter(e -> "TASK_SCHEDULED_V1".equals(e.type())).toList();
            if (nextKind == null) return Optional.of(new Outcome(report, edges.isEmpty() ? "APPLIED" : "UNKNOWN"));
            if (edges.size() != 1) return Optional.of(new Outcome(report, "UNKNOWN"));
            PendingTask child = task(edges.get(0));
            Identity identity = parser.parse(child, action, person);
            if (!edges.get(0).eventId().equals("schedule:"+child.taskId())
                    || !nextKind.equals(child.kind()) || child.phase() != WorldScheduler.Phase.COMPLETION
                    || child.dueTimeMillis() <= report.atMillis() || !matches(identity, person, action, expectedAction))
                return Optional.of(new Outcome(report, "UNKNOWN"));
            var childReport = reports.get(child.taskId());
            if (childReport == null) return Optional.of(new Outcome(report, pending.contains(child) ? "IN_PROGRESS" : "UNKNOWN"));
            Stage completed = stage(childReport, child, action, person, expectedAction, position, time, commits, parser, frames);
            if (completed == null || childReport.position() <= report.position()
                    || childReport.atMillis() < report.atMillis()) return Optional.of(new Outcome(report, "UNKNOWN"));
            report = childReport; stage = completed;
        }
        return Optional.of(new Outcome(report, "UNKNOWN"));
    }

    private static Stage stage(GroupsRun.GroupReport report, PendingTask scheduled, String action,
            String person, String expectedAction, long position, long time,
            LongFunction<Optional<WorldCommit>> commits, TaskIdentity parser,
            Map<Long, Optional<WorldCommit>> frames) {
        if (report.position() <= 0 || report.position() > position || report.atMillis() > time
                || !person.equals(report.person()) || !expectedAction.equals(report.action())) return null;
        WorldCommit commit = frames.computeIfAbsent(report.position(), commits::apply).orElse(null);
        if (commit == null || commit.position() != report.position()
                || !commit.commandId().equals("task:"+report.taskId())) return null;
        var events = commit.events().stream().filter(e -> "GROUP_TASK_V1".equals(e.type())).toList();
        if (events.size() != 1 || !events.get(0).eventId().equals("group-task:"+report.taskId())) return null;
        PendingTask completed = task(events.get(0));
        if (!completed.taskId().equals(report.taskId()) || !completed.kind().equals(report.kind())
                || completed.phase() != WorldScheduler.Phase.COMPLETION
                || completed.dueTimeMillis() != report.atMillis()
                || !completed.payload().equals(commit.commandContent())
                || scheduled != null && !scheduled.equals(completed)
                || !matches(parser.parse(completed, action, person), person, action, expectedAction)) return null;
        return new Stage(completed, commit);
    }

    private static PendingTask task(WorldEvent event) {
        try { return JSON.readValue(event.payload(), PendingTask.class); }
        catch (IOException failure) { throw new UncheckedIOException("群动作提交任务无法解析", failure); }
    }
    private static boolean matches(Identity identity, String person, String action, String expectedAction) {
        return identity != null && person.equals(identity.person()) && expectedAction.equals(identity.action())
                && DecisionRun.actionCommandId(action).equals(identity.command());
    }
    private static boolean publishes(String action) {
        return List.of("POST","CORRECTION","PLATFORM_POST","PLATFORM_CORRECTION").contains(action);
    }
    private static String actionOf(String tool) {
        return switch (tool) {
            case "group.join" -> "JOIN"; case "group.invite" -> "INVITE";
            case "group.respond" -> "RESPOND"; case "group.leave" -> "LEAVE";
            case "group.post" -> "POST"; case "group.correct" -> "CORRECTION";
            case "platform.post" -> "PLATFORM_POST"; case "platform.correct" -> "PLATFORM_CORRECTION";
            default -> null;
        };
    }
}
