package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.DecisionRun;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 无HTTP的恢复审计与原回执执行检查；不能当作新的自主模型实验。 */
public final class RoleEvidenceAudit {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** @param args 模式recover或intents、来源目录、独立输出目录。 */
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[1]), out = Path.of(args[2]);
        Files.createDirectory(out);
        if (args[0].equals("recover")) {
            Files.copy(source.resolve("world.journal"), out.resolve("world.journal"));
            try (var routes = SceneRoutes.open(out); var host = new WorldWebHost(() -> {
                var world = ComposedIndustryScenario.openEvidenceAutonomous(out.resolve("world.journal"), false);
                JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("decisions.json").toFile(),
                        world.snapshot().decisions().orElseThrow());
                return new WorldWebHost.Opened(world, world.profile().manifest().runId());
            }, routes, (call, timeout) -> { throw new AssertionError("恢复审计禁止调用模型"); })) {
                var result = JSON.createObjectNode();
                result.set("autonomy", JSON.valueToTree(host.autonomyStatus()));
                result.set("live", JSON.valueToTree(host.liveStatus()));
                var companies = result.putObject("companyGovernance");
                for (String company : host.liveCapabilities().governancePersons().stream()
                        .flatMap(p -> p.governanceCompanies().stream()).distinct().sorted().toList())
                    companies.set(company, JSON.valueToTree(host.engineeringView(company)));
                JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("result.json").toFile(), result);
            }
        } else if (args[0].equals("intents")) {
            var inputs = JSON.readTree(source.resolve("inputs.json").toFile());
            var results = JSON.createArrayNode();
            int index = 0;
            for (var original : JSON.readTree(source.resolve("comparison.json").toFile())) {
                if (!original.path("outcome").asText().equals("COMPLETE")) continue;
                String content = original.path("content").asText();
                if (JSON.readTree(content).path("tool").asText().equals("role.wait")) continue;
                String arm = original.path("arm").asText(), person = original.path("person").asText();
                Path journal = out.resolve("intent-" + ++index + ".journal");
                Files.copy(source.resolve(arm + ".journal"), journal);
                try (var world = ComposedIndustryScenario.openEvidenceAutonomous(journal, arm.equals("authority"))) {
                    if (!world.autonomyRoleObservation(person).equals(inputs.path(arm).path(person)))
                        throw new IllegalStateException("恢复输入与原实验不同，禁止执行原回执");
                    String id = "replay-intent-" + index;
                    world.beginDecisionSession("session-" + id);
                    world.openDecisions("open-" + id, List.of(new DecisionRun.Request(person, id, "wake",
                            new DecisionModel.Route("governance", "key-1", "deepseek-flash", 1L, 20, 1_000_000, 2048, 1))));
                    var call = world.dispatchLiveDecision("dispatch-" + id, id).call();
                    var usage = original.path("usage");
                    world.offerDecisionCallback(new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(),
                            call.attemptNo(), call.recoveryGeneration(), content, DecisionModel.Usage.unknownCost(1,
                            usage.path("promptTokens").asLong(), usage.path("completionTokens").asLong()), "REPLAY:DEEPSEEK_HTTP"));
                    world.drainDecisionCallbacks();
                    var action = world.snapshot().decisions().orElseThrow().actionRef(id);
                    if (action != null) world.advanceTo(action.dueMillis() + 1000L);
                    var row = results.addObject();
                    row.put("arm", arm).put("person", person).put("originalInputMatched", true);
                    row.set("action", JSON.valueToTree(world.snapshot().decisions().orElseThrow().actionRef(id)));
                    row.set("archives", JSON.valueToTree(world.snapshot().decisions().orElseThrow().archives()));
                    var intent = JSON.readTree(content).path("args");
                    row.put("meetingExists", world.boardOfCompany(intent.path("companyPartyId").asText()).orElseThrow()
                            .board().meetings().containsKey(intent.path("meetingId").asText()));
                }
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("results.json").toFile(), results);
        } else throw new IllegalArgumentException("未知审计模式");
    }
}
