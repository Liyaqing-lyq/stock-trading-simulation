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
    /** @param mode ACCOUNTABLE/OPERATING保留v3；PLANNING/PLANNING_OPERATING为v4治理/有来源需求实验；INFORMED_OPERATING显式启用经理经营信息与来源审计；seed只接续已完整结束的同版本档。 */
    public record Config(String mode, int maxHttpCalls, int wallMinutes, long targetMillis,
                         long stepMillis, String seed, String sourceCommit) {
        /** 约束试跑边界，额度是上限，不是调用承诺或经营验收。 */
        public Config {
            if (!java.util.Set.of("ACCOUNTABLE", "OPERATING", "PLANNING", "PLANNING_OPERATING", "INFORMED_OPERATING").contains(mode)
                    || maxHttpCalls < 1 || maxHttpCalls > 3000 || wallMinutes < 1 || wallMinutes > 60
                    || targetMillis <= 0 || targetMillis > 180L * 86400000
                    || stepMillis != 3_600_000
                    || sourceCommit == null || sourceCommit.isBlank()) throw new IllegalArgumentException("无效试跑参数");
        }
    }
    /** @param args 独立新输出目录、五槽凭据文件、冻结配置JSON路径 */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("需要输出目录、凭据文件和参数文件");
        Path out = Path.of(args[0]);
        Config config = JSON.readValue(Files.readAllBytes(Path.of(args[2])), Config.class);
        Path seed = config.seed() == null ? null : validateSeed(config);
        Files.createDirectory(out);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("config.json").toFile(), config);
        Files.writeString(out.resolve("system-prompt.txt"), systemPrompt(config));
        boolean informed = config.mode().equals("INFORMED_OPERATING");
        if (informed) ProbeProviderEvidence.freezePolicy(out);
        if (seed != null) {
            Files.copy(seed.resolve("world.journal"), out.resolve("world.journal"));
            Files.copy(seed.resolve(SceneRoutes.FILE_NAME), out.resolve(SceneRoutes.FILE_NAME));
        }
        var provider = new LiveDecisionProvider(KeySlots.load(Path.of(args[1])));
        var issued = new AtomicInteger();
        var evidence = informed ? new ProbeProviderEvidence(out) : null;
        try (var routes = SceneRoutes.open(out)) {
            if (seed == null && routes.view().revision() == 0) routes.replace(0, routes.view().routes().stream().map(r ->
                    new SceneRoutes.Route(r.scene(), r.slotId(), "deepseek-flash", true,
                            2500, 35_000_000, 2048, 4, "lifecycle-v1")).toList());
            try (var host = new WorldWebHost(() -> {
                var world = openWorld(config.mode(), out.resolve("world.journal"));
                return new WorldWebHost.Opened(world, world.profile().manifest().runId());
            }, routes, (call, timeout) -> {
                if (evidence != null && evidence.failed()) return LiveCompletionInbox.Completion.failure("PROBE_EVIDENCE_STOP",
                        new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                                call.recoveryGeneration(), "", null, "PROBE_LIMIT"));
                int index = issued.incrementAndGet();
                if (index > config.maxHttpCalls()) return LiveCompletionInbox.Completion.failure("PROBE_CALL_LIMIT",
                        new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                                call.recoveryGeneration(), "", null, "PROBE_LIMIT"));
                if (evidence != null) {
                    var completion = evidence.evaluate(index, call, timeout, provider);
                    System.out.println("completed=" + index + " action=" + call.actionId());
                    return completion;
                }
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
                        && System.nanoTime() < deadline && !Files.exists(out.resolve("STOP"))
                        && (evidence == null || !evidence.failed())) {
                    Thread.sleep(500);
                    JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("status.json").toFile(), host.autonomyStatus());
                }
                String stop = !host.autonomyStatus().running() ? "RUNTIME_STOP"
                        : Files.exists(out.resolve("STOP")) ? "OPERATOR_STOP"
                        : issued.get() >= config.maxHttpCalls() ? "HTTP_CALL_LIMIT" : "WALL_CLOCK_LIMIT";
                if (host.autonomyStatus().running()) host.stopAutonomy();
                long drain = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (host.liveStatus().physicalInFlight() > 0 && System.nanoTime() < drain) Thread.sleep(100);
                if (evidence != null && evidence.failed()) {
                    stop = ProbeProviderEvidence.WRITE_FAILURE;
                    System.err.println(stop);
                }
                var result = JSON.createObjectNode().put("probeStop", stop)
                        .put("httpCalls", Math.min(issued.get(), config.maxHttpCalls())).put("costStatus", "UNKNOWN");
                if (evidence != null) result.set("providerProvenance",
                        evidence.summary(Math.min(issued.get(), config.maxHttpCalls()), host.liveStatus().physicalInFlight()));
                result.set("config", JSON.valueToTree(config));
                result.set("autonomy", JSON.valueToTree(host.autonomyStatus()));
                result.set("live", JSON.valueToTree(host.liveStatus()));
                var companies = result.putObject("companyGovernance");
                for (String company : host.liveCapabilities().governancePersons().stream()
                        .flatMap(p -> p.governanceCompanies().stream()).distinct().sorted().toList())
                    companies.set(company, JSON.valueToTree(host.engineeringView(company)));
                if (config.mode().startsWith("PLANNING") || informed) {
                    // End-of-run auditor output only; never fed into role observations or model requests.
                    result.set("operations", JSON.valueToTree(host.operationsView(null)));
                    result.set("admin", JSON.valueToTree(host.adminView()));
                }
                JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("result.json").toFile(), result);
                System.out.println("finished=" + host.autonomyStatus().reason() + " http=" + Math.min(issued.get(), config.maxHttpCalls()));
            }
        }
    }

    static edu.cufe.auction.world.WorldRuntime openWorld(String mode, Path journal) throws Exception {
        return switch (mode) {
            case "INFORMED_OPERATING" -> ComposedIndustryScenario.openInformedOperating(journal);
            case "PLANNING_OPERATING" -> ComposedIndustryScenario.openPlanningOperating(journal);
            case "PLANNING" -> ComposedIndustryScenario.openPlanningAutonomous(journal);
            case "OPERATING" -> ComposedIndustryScenario.openOperatingAutonomous(journal);
            default -> ComposedIndustryScenario.openAccountableAutonomous(journal);
        };
    }

    private static String systemPrompt(Config config) {
        return (config.mode().startsWith("PLANNING") || config.mode().equals("INFORMED_OPERATING")) ? LiveDecisionProvider.PLANNING_SYSTEM_TEXT
                : LiveDecisionProvider.DELIBERATION_SYSTEM_TEXT;
    }

    private static Path validateSeed(Config requested) {
        Path seed = Path.of(requested.seed());
        try {
            for (String name : new String[]{"config.json", "result.json", "system-prompt.txt",
                    "world.journal", SceneRoutes.FILE_NAME}) {
                if (!Files.isRegularFile(seed.resolve(name)))
                    throw new IllegalArgumentException("种子缺少完整冻结文件：" + name);
            }
            var seedConfig = JSON.readTree(Files.readString(seed.resolve("config.json")));
            var result = JSON.readTree(Files.readString(seed.resolve("result.json")));
            if (seedConfig == null || !seedConfig.isObject() || result == null || !result.isObject())
                throw new IllegalArgumentException("种子冻结配置不合法");
            Config frozen = JSON.treeToValue(seedConfig, Config.class);
            if (!seedConfig.equals(JSON.readTree(JSON.writeValueAsBytes(frozen))) || !seedConfig.equals(result.path("config")))
                throw new IllegalArgumentException("种子 config.json 与 result.config 必须完整且一致");
            if (!requested.mode().equals(frozen.mode()) || !requested.sourceCommit().equals(frozen.sourceCommit())
                    || !systemPrompt(requested).equals(
                            Files.readString(seed.resolve("system-prompt.txt"))))
                throw new IllegalArgumentException("种子的模式、源码和系统提示词必须与当前试跑完全相同");
            var inFlight = result.path("live").path("physicalInFlight");
            if (!inFlight.isIntegralNumber() || !inFlight.canConvertToInt() || inFlight.intValue() != 0)
                throw new IllegalArgumentException("种子尚有未结算的物理请求");
            try (var paths = Files.list(seed)) {
                for (Path call : paths.filter(p -> p.getFileName().toString().matches("call-[0-9]+[.]json")).toList())
                    if (!Files.isRegularFile(seed.resolve(call.getFileName().toString().replace("call-", "reply-"))))
                        throw new IllegalArgumentException("种子存在结果未知的请求");
            }
            if (requested.mode().equals("INFORMED_OPERATING")) ProbeProviderEvidence.validateSeed(seed, result);
            return seed;
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("种子冻结文件不可读或格式不合法", invalid);
        }
    }

}
