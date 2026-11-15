package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 冻结参数的真实跨期探针：没有预读、经营脚本或规则替代，保存全部原输入与回执。 */
public final class AutonomousLifecycleProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** @param mode ACCOUNTABLE为治理控制，OPERATING为有来源需求实验；seed只接续已完整结束的同版本档。 */
    public record Config(String mode, int maxHttpCalls, int wallMinutes, long targetMillis,
                         long stepMillis, String seed, String sourceCommit) {
        /** 约束试跑边界，额度是上限，不是调用承诺或经营验收。 */
        public Config {
            if (!java.util.Set.of("ACCOUNTABLE", "OPERATING").contains(mode)
                    || maxHttpCalls < 1 || maxHttpCalls > 3000 || wallMinutes < 1 || wallMinutes > 60
                    || targetMillis <= 0 || targetMillis > 180L * 86400000
                    || stepMillis <= 0 || stepMillis > 86400000
                    || sourceCommit == null || sourceCommit.isBlank()) throw new IllegalArgumentException("无效试跑参数");
        }
    }
    /** @param args 独立新输出目录、五槽凭据文件、冻结配置JSON路径 */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("需要输出目录、凭据文件和参数文件");
        Path out = Path.of(args[0]);
        Config config = JSON.readValue(Files.readAllBytes(Path.of(args[2])), Config.class);
        Files.createDirectory(out);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("config.json").toFile(), config);
        Files.writeString(out.resolve("system-prompt.txt"), LiveDecisionProvider.DELIBERATION_SYSTEM_TEXT);
        if (config.seed() != null) {
            Path seed = Path.of(config.seed());
            if (!Files.exists(seed.resolve("result.json"))) throw new IllegalArgumentException("种子运行尚未完整结束");
            try (var paths = Files.list(seed)) {
                for (Path p : paths.filter(p -> p.getFileName().toString().matches("call-[0-9]+[.]json")).toList())
                    if (!Files.exists(seed.resolve(p.getFileName().toString().replace("call-", "reply-"))))
                        throw new IllegalArgumentException("种子存在结果未知的请求");
            }
            var result = JSON.readTree(Files.readString(seed.resolve("result.json")));
            if (result.path("live").path("physicalInFlight").asInt(-1) != 0)
                throw new IllegalArgumentException("种子尚有未结算的物理请求");
            Files.copy(seed.resolve("world.journal"), out.resolve("world.journal"));
            Files.copy(seed.resolve(SceneRoutes.FILE_NAME), out.resolve(SceneRoutes.FILE_NAME));
        }
        var provider = new LiveDecisionProvider(KeySlots.load(Path.of(args[1])));
        var issued = new AtomicInteger();
        try (var routes = SceneRoutes.open(out)) {
            if (routes.view().revision() == 0) routes.replace(0, routes.view().routes().stream().map(r ->
                    new SceneRoutes.Route(r.scene(), r.slotId(), "deepseek-flash", true,
                            2500, 35_000_000, 2048, 4, "lifecycle-v1")).toList());
            try (var host = new WorldWebHost(() -> {
                var world = config.mode().equals("OPERATING")
                        ? ComposedIndustryScenario.openOperatingAutonomous(out.resolve("world.journal"))
                        : ComposedIndustryScenario.openAccountableAutonomous(out.resolve("world.journal"));
                return new WorldWebHost.Opened(world, world.profile().manifest().runId());
            }, routes, (call, timeout) -> {
                int index = issued.incrementAndGet();
                if (index > config.maxHttpCalls()) return LiveCompletionInbox.Completion.failure("PROBE_CALL_LIMIT",
                        new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                                call.recoveryGeneration(), "", null, "PROBE_LIMIT"));
                try {
                    JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("call-" + index + ".json").toFile(), call);
                    var completion = provider.evaluate(call, timeout);
                    JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("reply-" + index + ".json").toFile(), completion);
                    System.out.println("completed=" + index + " action=" + call.actionId());
                    return completion;
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            })) {
                var initial = host.autonomyStatus();
                host.startAutonomy(Long.parseLong(initial.position()), routes.view().revision(), config.targetMillis(), config.stepMillis());
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(config.wallMinutes());
                while (host.autonomyStatus().running() && issued.get() < config.maxHttpCalls()
                        && System.nanoTime() < deadline && !Files.exists(out.resolve("STOP"))) {
                    Thread.sleep(500);
                    JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("status.json").toFile(), host.autonomyStatus());
                }
                String stop = !host.autonomyStatus().running() ? "RUNTIME_STOP"
                        : Files.exists(out.resolve("STOP")) ? "OPERATOR_STOP"
                        : issued.get() >= config.maxHttpCalls() ? "HTTP_CALL_LIMIT" : "WALL_CLOCK_LIMIT";
                if (host.autonomyStatus().running()) host.stopAutonomy();
                long drain = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (host.liveStatus().physicalInFlight() > 0 && System.nanoTime() < drain) Thread.sleep(100);
                var result = JSON.createObjectNode().put("probeStop", stop)
                        .put("httpCalls", Math.min(issued.get(), config.maxHttpCalls())).put("costStatus", "UNKNOWN");
                result.set("config", JSON.valueToTree(config));
                result.set("autonomy", JSON.valueToTree(host.autonomyStatus()));
                result.set("live", JSON.valueToTree(host.liveStatus()));
                var companies = result.putObject("companyGovernance");
                for (String company : host.liveCapabilities().governancePersons().stream()
                        .flatMap(p -> p.governanceCompanies().stream()).distinct().sorted().toList())
                    companies.set(company, JSON.valueToTree(host.engineeringView(company)));
                JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("result.json").toFile(), result);
                System.out.println("finished=" + host.autonomyStatus().reason() + " http=" + Math.min(issued.get(), config.maxHttpCalls()));
            }
        }
    }
}
