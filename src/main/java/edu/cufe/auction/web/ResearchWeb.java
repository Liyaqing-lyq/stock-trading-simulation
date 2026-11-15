package edu.cufe.auction.web;

import edu.cufe.auction.world.BoardModel;
import edu.cufe.auction.world.ResearchModel;
import edu.cufe.auction.world.RoleAccess;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedCompanyReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 研发网页投影与动作；人物来自已认证会话，结果真值仅供上帝审计。 */
final class ResearchWeb {
    private ResearchWeb() {
    }

    /** 本人研发请求；不会接受调用方自报人物、任职或批准引用。 */
    record Command(long expectedPosition, String commandId, String taskId, long dueTimeMillis,
                   String companyId, String action, String projectId, String stage,
                   String meetingId, long durationMillis, long materialDeadline,
                   long opensAt, long closesAt, List<String> relatedSeats,
                   List<BoardModel.MaterialRef> materials) {
        Command {
            relatedSeats = relatedSeats == null ? List.of() : List.copyOf(relatedSeats);
            materials = materials == null ? List.of() : List.copyOf(materials);
        }
    }

    static Map<String, Object> view(WorldRuntime world, String person) {
        var snapshot = world.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("position", Long.toString(snapshot.position()));
        result.put("timeMillis", Long.toString(snapshot.timeMillis()));
        var run = world.researchRun().orElse(null);
        result.put("enabled", run != null);
        if (run == null) {
            result.put("note", "当前档案未启用研发；不会补建或迁移旧档。");
            return result;
        }
        String company = run.companyPartyId();
        List<String> actions = person == null ? List.of() : actions(world, person, company);
        if (person != null && actions.isEmpty()) {
            result.put("enabled", false);
            result.put("note", "本人没有该研发公司的经理任职或董事席位。");
            return result;
        }
        result.put("companyId", company);
        result.put("companyName", world.openingRegistry().orElseThrow().parties().stream()
                .filter(party -> party.partyId().equals(company)).map(party -> party.name())
                .findFirst().orElse(company));
        result.put("actions", actions);
        List<Map<String, Object>> projects = new ArrayList<>();
        for (var spec : new TreeMap<>(run.projects()).values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectId", spec.projectId());
            row.put("budget", spec.budget().toPlainString());
            row.put("amortizationDays", spec.amortizationDays());
            row.put("stages", spec.stages().stream().map(stage -> Map.of(
                    "stage", stage.stage().name(), "cost", stage.cost().toPlainString(),
                    "durationMillis", Long.toString(stage.durationMillis()))).toList());
            // 人物只能看到冻结项目方案，不能把系统已生成的阶段结果当作自己已读到。
            if (person == null) {
                var project = run.state().projects().get(spec.projectId());
                row.put("status", project == null ? "UNREGISTERED" : project.status().name());
                if (project != null) {
                    row.put("outcomes", project.stages().stream().map(stage -> Map.of(
                            "stage", stage.plan().stage().name(), "status", stage.status().name(),
                            "beganAt", Long.toString(stage.beganAt()),
                            "completesAt", Long.toString(stage.completesAt()),
                            "evidenceRef", stage.evidence() == null ? "" : stage.evidence().reference())).toList());
                    row.put("approvalRef", project.approvalRef());
                    row.put("approvedAt", Long.toString(project.approvedAt()));
                    row.put("amortized", project.amortized().toPlainString());
                }
            }
            projects.add(row);
        }
        result.put("projects", projects);
        if (person == null) {
            result.put("capacity", run.maxActiveStages());
            result.put("activeStages", run.activeStages());
            if (world.profile().manifest().hasComposedBankWorld()) {
                var report = ComposedCompanyReport.of(world, company);
                result.put("companyReport", Map.of("assets", amounts(report.assets()),
                        "liabilities", amounts(report.liabilities()), "equity", amounts(report.equity()),
                        "income", amounts(report.income()), "expense", amounts(report.expense()),
                        "sources", report.sources(), "unsupported", report.unsupported()));
            }
        }
        result.put("note", person == null
                ? "上帝真值：阶段结果、失败与批准都来自已确认记录。批准不等于已取得商业收入。"
                : "这里只列冻结方案和本人岗位工具。结果证据须本人实际阅读；已排程不等于完成。");
        return result;
    }

    private static Map<String, String> amounts(Map<String, java.math.BigDecimal> amounts) {
        Map<String, String> result = new TreeMap<>();
        amounts.forEach((name, amount) -> result.put(name, amount.toPlainString()));
        return result;
    }

    static List<String> actions(WorldRuntime world, String person, String company) {
        var run = world.researchRun().orElse(null);
        if (run == null || !run.companyPartyId().equals(company)) return List.of();
        var snapshot = world.snapshot();
        List<String> actions = new ArrayList<>();
        boolean manager = snapshot.roles().stream().flatMap(roles -> roles.access().appointments().stream())
                .anyMatch(appointment -> appointment.personPartyId().equals(person)
                        && appointment.organizationPartyId().equals(company)
                        && appointment.role() == RoleAccess.Role.COMPANY_MANAGER
                        && appointment.activeAt(snapshot.timeMillis()));
        if (manager) actions.addAll(List.of("REGISTER", "BEGIN", "APPROVE"));
        var board = world.boardOfCompany(company).orElse(null);
        if (board != null) {
            String seat = board.board().charter().seatOf(person);
            if (seat != null) actions.add("PROPOSE");
            if (seat != null && seat.equals(board.board().charter().chairSeatId())) {
                actions.addAll(List.of("CONVENE", "IMPLEMENT"));
            }
        }
        return List.copyOf(actions);
    }

    static String schedule(WorldRuntime world, String person, Command request) throws Exception {
        if (world.snapshot().position() != request.expectedPosition()) {
            throw new WorldWebHost.PositionMismatch(world.snapshot().position());
        }
        if (!actions(world, person, request.companyId()).contains(request.action())) {
            throw new IllegalArgumentException("本人当前岗位没有该研发动作权限");
        }
        var session = world.openRoleSession(person);
        return switch (request.action()) {
            case "REGISTER" -> session.registerResearch(request.commandId(), request.taskId(),
                    request.dueTimeMillis(), request.companyId(), request.projectId(), request.durationMillis());
            case "BEGIN" -> session.beginResearch(request.commandId(), request.taskId(),
                    request.dueTimeMillis(), request.companyId(), request.projectId(),
                    ResearchModel.Stage.valueOf(request.stage()), request.durationMillis());
            case "APPROVE" -> session.approveCommercialization(request.commandId(), request.taskId(),
                    request.dueTimeMillis(), request.companyId(), request.projectId(),
                    request.meetingId(), request.durationMillis());
            case "CONVENE" -> world.scheduleCommercializationConvene(request.commandId(), request.taskId(),
                    request.dueTimeMillis(), request.companyId(), person, request.meetingId(),
                    request.materialDeadline(), request.opensAt(), request.closesAt());
            case "PROPOSE" -> world.scheduleCommercializationProposal(request.commandId(), request.taskId(),
                    request.dueTimeMillis(), request.companyId(), person, request.meetingId(),
                    request.relatedSeats(), new BoardModel.CommercializationRequest(request.companyId(),
                            request.projectId(), request.materials()), request.durationMillis());
            case "IMPLEMENT" -> world.scheduleCommercializationImplement(request.commandId(), request.taskId(),
                    request.dueTimeMillis(), request.companyId(), person, request.meetingId());
            default -> throw new IllegalArgumentException("未知研发动作");
        };
    }
}
