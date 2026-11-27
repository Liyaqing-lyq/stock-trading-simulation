package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** v4 planning facts from the person's existing projection and frozen runtime configuration only. */
public final class RolePlanningContext {
    public static final long MILLIS_PER_DAY = 86_400_000L;
    public static final long MAX_REVIEW_AFTER_MILLIS = 30L * MILLIS_PER_DAY;
    private RolePlanningContext() { }

    /** Append timing after the complete decision root is built; never infer rights from document text. */
    public static void append(ObjectNode input, String wake, DecisionModel.Config config) {
        if (!(input.path("engineering") instanceof ObjectNode eng)
                || !RoleDeliberation.isPlanning(eng.path("roleContextVersion").asText())) return;
        long now = input.path("time").longValue();
        Long think = config.thinkMillisByWake().get(wake);
        var planning = eng.putObject("planning").put("nowMillis", now).put("millisPerDay", MILLIS_PER_DAY);
        if (think != null) planning.put("thinkMillis", think); else planning.put("thinkMillisStatus", "UNKNOWN");
        planning.put("scope", "本人已投影事实的必要条件，不是完整合法性或成功保证；执行器在实际开始及完成时重新校验权限、焦点、状态、参数和时间。阅读/施工等后继任务耗时另见工具与资料事实。");
        var actions = planning.putObject("actions");
        for (var tool : input.path("tools")) {
            String name = tool.path("name").asText();
            var row = actions.putObject(name);
            Long action = config.actionMillisByTool().get(name);
            if ("role.wait".equals(name)) action = 0L;
            if (action == null || think == null) { row.put("timingStatus", "UNKNOWN"); continue; }
            row.put("actionMillis", action);
            try { row.put("earliestCompletionMillis", Math.addExact(Math.addExact(now, think), action)); }
            catch (ArithmeticException overflow) { row.put("timingStatus", "UNKNOWN_OVERFLOW"); }
        }
        var delib = (ObjectNode) eng.path("deliberation");
        delib.put("replyContract", "只输出一个对象：{tool,decision:{objective:上述id,basis:一句话依据或缺项,reviewAfterMillis:正整数毫秒时长}}；非role.wait再含args。decision仅有这三个字段，不得输出绝对复查时点。basis只写可审计结论摘要，不写完整推理；等待说明条件或缺项。复查由本次冻结input.time加时长精确推导，事件可提前唤醒。");
        delib.putObject("reviewBounds").put("durationAfterExclusive", 0L)
                .put("durationLatestInclusive", MAX_REVIEW_AFTER_MILLIS).put("anchorMillis", now);
        if (eng.path("decisionGuide") instanceof ObjectNode guide) {
            guide.put("waitContract", "等待输出{tool:role.wait,decision:{objective:本人目标id,basis:条件或缺项,reviewAfterMillis:正整数毫秒时长}}，不得带args。时长不超过30日，不能使用绝对复查字段；等待不会执行后继动作。");
            guide.put("decisionCadence", "本人可见新事实、本人动作完成或声明的相对复查时长到期会重新评估；每次一个动作。严格格式失败耗尽重试后，本人相同事实暂停调用，真实新事实可重新唤醒，其他人继续运行。");
        }
        if ("role-choice-v1".equals(eng.path("publicPostWakePolicy").asText())) {
            String wakeEffect = "IMMEDIATE（默认）表示新事实提前唤醒。"
                    + "ON_REVIEW表示仅将陌生人的新广场帖留到本次复查。联系人、私信、正式报道、更正及其他事实变化仍可提前唤醒。";
            String contract = "每轮一个JSON对象，包含tool与decision（objective、basis、reviewAfterMillis）。"
                    + "复查时长为从本次input.time起的正整数毫秒，至多30日。等待说明条件或缺项且不带args，其余动作按工具合同提供args。"
                    + "仅role.wait的decision可选publicPostWake。" + wakeEffect + " 可选顶层privateNote记录本人判断，结构见engineering.researchNotes.contract。";
            boolean typed = RunManifest.TYPED_REVIEW_FIELDS.equals(eng.path("decisionEnvelopeGuide").textValue());
            if (typed) {
                contract = "整份回复是单个JSON对象，字段名不得重复，最多" + config.limits().maxReplyChars() + "个UTF-16单元。"
                        + "顶层tool是本次tools中的名称字符串。顶层decision是对象，必填且仅有以下三项，等待另有一项可选字段。"
                        + "objective是字符串，精确取本次engineering.deliberation.objectives中一行的id。"
                        + "basis是含非空白字符的字符串，长度1至" + RoleDeliberation.MAX_BASIS_UTF16_UNITS + "个UTF-16单元。写简短依据，等待时说明条件或缺项。"
                        + "reviewAfterMillis是JSON整数，从input.time起算，范围1至" + MAX_REVIEW_AFTER_MILLIS + "毫秒（含两端），相加须在有符号64位整数范围内。"
                        + "仅当tool为role.wait时，decision.publicPostWake可选，值是字符串IMMEDIATE或ON_REVIEW。" + wakeEffect
                        + "等待通常省略顶层args，本档也接受空对象{}。其他工具必须有顶层args对象，按对应工具合同填写。"
                        + "可选的顶层笔记和联系人评语字段见engineering.researchNotes及engineering.contactHistory，其余字段不接收。";
            }
            delib.put("replyContract", contract);
            if (eng.path("decisionGuide") instanceof ObjectNode guide) {
                guide.put("waitContract", typed ? "等待通常省略args，也接受空对象{}。完整字段见engineering.deliberation.replyContract。" : contract);
            }
        }
        for (var director : eng.path("directorships")) {
            var d = (ObjectNode) director;
            correctProcedure(d);
            d.put("implementationInvocationRight", "本人真实董事席位可调用implement；不要求chair、此前到会或本人投票。调用权与ADOPTED、实施窗口及执行合法性分别复核。");
            for (var node : d.path("meetings")) {
                var m = (ObjectNode) node;
                correctProcedure(m);
                var constraints = m.putObject("planningPrerequisites");
                constraints.put("currentPhase", m.path("status").asText("UNKNOWN"));
                constraints.put("completionWindow", "到会完成 < opensAt；材料/提案完成 <= materialDeadline；投票须当前VOTING且完成在[opensAt,closesAt)；任免实施须当前ADOPTED且完成在[closesAt,effectiveAt]。未来动作还须另花一轮思考和工具工时，不能把通过当作已实施。");
                for (String tool : List.of("board.attend", "board.addMaterial", "board.proposeAppointment", "board.proposeBudget", "board.vote", "board.implement")) {
                    // Actual public tool names are used below; tools absent from the offered set remain unoffered.
                    if (!actions.has(tool)) continue;
                    var row = constraints.putObject(tool);
                    row.set("timing", actions.path(tool).deepCopy());
                    ArrayNode blockers = row.putArray("blockers"), unknown = row.putArray("unknown");
                    JsonNode finish = actions.path(tool).path("earliestCompletionMillis");
                    if (!finish.isIntegralNumber()) unknown.add("UNKNOWN_COMPLETION_TIME");
                    boolean implement = tool.equals("board.implement"), vote = tool.equals("board.vote"), attend = tool.equals("board.attend");
                    requireText(m, "status", implement ? "ADOPTED" : vote ? "VOTING" : "CONVENED", blockers, unknown);
                    if (!implement && !attend) { requireBoolean(m,"attended",true,blockers,unknown); requireBoolean(m,"recused",false,blockers,unknown); }
                    if (attend) { before(finish,m.path("opensAt"),false,"ATTENDANCE_DEADLINE",blockers,unknown); }
                    else if (vote) {
                        if (!m.has("ownBallot")) unknown.add("UNKNOWN_OWN_BALLOT");
                        else if (!m.path("ownBallot").asText().isEmpty()) blockers.add("BALLOT_ALREADY_CAST");
                        if (!m.path("materials").isArray()) unknown.add("UNKNOWN_MATERIALS");
                        else if (m.path("materials").isEmpty()) blockers.add("NO_ACTUAL_MATERIALS");
                        for (var material : m.path("materials")) {
                            requireBoolean(material,"read",true,blockers,unknown);
                            requireBoolean(material,"recalled",true,blockers,unknown);
                        }
                        atOrAfter(finish,m.path("opensAt"),"VOTING_NOT_OPEN",blockers,unknown);
                        before(finish,m.path("closesAt"),false,"VOTING_CLOSED",blockers,unknown);
                    } else if (implement) {
                        atOrAfter(finish,m.path("closesAt"),"IMPLEMENTATION_NOT_DUE",blockers,unknown);
                        if ("MANAGER_APPOINTMENT".equals(m.path("topic").asText())) {
                            before(finish,m.path("appointmentRequest").path("effectiveAt"),true,"APPOINTMENT_IMPLEMENTATION_DEADLINE",blockers,unknown);
                        } else unknown.add("TOPIC_SPECIFIC_IMPLEMENTATION_NOT_PROVEN");
                    } else {
                        before(finish,m.path("materialDeadline"),true,"MATERIAL_DEADLINE",blockers,unknown);
                        if (tool.equals("board.proposeAppointment") && !"MANAGER_APPOINTMENT".equals(m.path("topic").asText())) blockers.add("WRONG_TOPIC");
                        if (tool.equals("board.proposeAppointment") && m.has("appointmentRequest")) blockers.add("PROPOSAL_EXISTS");
                        if (tool.equals("board.proposeBudget") && !"BUDGET".equals(m.path("topic").asText())) blockers.add("WRONG_TOPIC");
                        if (RoleDeliberation.CONTEXTUAL_VERSION.equals(eng.path("roleContextVersion").asText())
                                && tool.equals("board.proposeBudget") && "BUDGET".equals(m.path("topic").asText())
                                && m.path("proposedLines").isArray() && !m.path("proposedLines").isEmpty()) blockers.add("PROPOSAL_EXISTS");
                    }
                    row.put("status", !blockers.isEmpty() ? "BLOCKED" : !unknown.isEmpty() ? "NOT_PROVEN" : "NECESSARY_CONDITIONS_MET");
                    row.put("executorRevalidates", true);
                }
            }
        }
    }
    private static void correctProcedure(ObjectNode row) {
        if (row.path("procedure").isTextual()) row.put("procedure",row.path("procedure").asText().replace("PASSED","ADOPTED"));
    }
    private static void requireBoolean(JsonNode n,String key,boolean expected,ArrayNode blockers,ArrayNode unknown) {
        if (!n.path(key).isBoolean()) unknown.add("UNKNOWN_"+key);
        else if (n.path(key).booleanValue()!=expected) blockers.add("REQUIRES_"+key+"_"+expected);
    }
    private static void requireText(JsonNode n,String key,String expected,ArrayNode blockers,ArrayNode unknown) {
        if (!n.path(key).isTextual()) unknown.add("UNKNOWN_"+key);
        else if (!expected.equals(n.path(key).textValue())) blockers.add("REQUIRES_"+expected);
    }
    private static void before(JsonNode finish,JsonNode end,boolean inclusive,String reason,ArrayNode blockers,ArrayNode unknown) {
        if (!end.isIntegralNumber()) unknown.add("UNKNOWN_"+reason);
        else if (finish.isIntegralNumber() && (inclusive ? finish.longValue()>end.longValue() : finish.longValue()>=end.longValue())) blockers.add(reason);
    }
    private static void atOrAfter(JsonNode finish,JsonNode begin,String reason,ArrayNode blockers,ArrayNode unknown) {
        if (!begin.isIntegralNumber()) unknown.add("UNKNOWN_"+reason);
        else if (finish.isIntegralNumber() && finish.longValue()<begin.longValue()) blockers.add(reason);
    }
}
