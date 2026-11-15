package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.DecisionModel;
import edu.cufe.auction.world.LiveCompletionInbox;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 新档52人真实链路探针：不预读、不代作动作，最多300次HTTP请求和二十分钟墙钟。 */
public final class RoleDeliberationLiveProbe {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** @param args 独立输出目录、五槽凭据文件；第三参resume接续无缺失回执实验，seed=目录复制已结束实验。 */
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        boolean resume = args.length == 3 && args[2].equals("resume");
        int previous = 0;
        if (resume) {
            if (Files.exists(out.resolve("result.json"))) throw new IllegalArgumentException("完整实验不得覆盖");
            while (Files.exists(out.resolve("call-" + (previous + 1) + ".json"))) {
                previous++;
                if (!Files.exists(out.resolve("reply-" + previous + ".json")))
                    throw new IllegalStateException("存在结果未知请求，禁止继续付费");
            }
        } else {
            Files.createDirectory(out);
            if (args.length == 3 && args[2].startsWith("seed=")) {
                Path seed = Path.of(args[2].substring(5));
                if (!Files.exists(seed.resolve("result.json"))) throw new IllegalArgumentException("来源实验尚未结束");
                Files.copy(seed.resolve("world.journal"), out.resolve("world.journal"));
                Files.copy(seed.resolve(SceneRoutes.FILE_NAME), out.resolve(SceneRoutes.FILE_NAME));
            }
        }
        var live = new LiveDecisionProvider(KeySlots.load(Path.of(args[1])));
        var calls = new AtomicInteger(previous);
        try (var routes = SceneRoutes.open(out)) {
            if (routes.view().revision() == 0) routes.replace(routes.view().revision(), routes.view().routes().stream().map(r ->
                    new SceneRoutes.Route(r.scene(), r.slotId(), "deepseek-flash", true,
                            500, 10_000_000, 2048, 4, "lifecycle-v1")).toList());
            try (var host = new WorldWebHost(() -> {
                var world = ComposedIndustryScenario.openDeliberativeAutonomous(out.resolve("world.journal"));
                return new WorldWebHost.Opened(world, world.profile().manifest().runId());
            }, routes, (call, timeout) -> {
                int index = calls.incrementAndGet();
                if (index > 300) return LiveCompletionInbox.Completion.failure("PROBE_CALL_LIMIT",
                        new DecisionModel.Reply(call.runId(), call.actionId(), call.attemptId(), call.attemptNo(),
                                call.recoveryGeneration(), "", null, "PROBE_LIMIT"));
                // 完整输入、计划和原回执各自落盘；凭据从不在Call或Completion中。
                try {
                    JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("call-" + index + ".json").toFile(), call);
                    var result = live.evaluate(call, timeout);
                    JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("reply-" + index + ".json").toFile(), result);
                    System.out.println("completed=" + index + " action=" + call.actionId());
                    return result;
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            })) {
                var status = host.autonomyStatus();
                host.startAutonomy(Long.parseLong(status.position()), routes.view().revision(), 86_400_000L, 3_600_000L);
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
                while (host.autonomyStatus().running() && calls.get() < 300 && System.nanoTime() < deadline) {
                    Thread.sleep(500);
                    JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("status.json").toFile(), host.autonomyStatus());
                }
                String limit = host.autonomyStatus().running()
                        ? (calls.get() >= 300 ? "HTTP_CALL_LIMIT" : "WALL_CLOCK_LIMIT") : "RUNTIME_STOP";
                if (host.autonomyStatus().running()) host.stopAutonomy();
                long drain = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (host.liveStatus().physicalInFlight() > 0 && System.nanoTime() < drain) Thread.sleep(100);
                var result = JSON.createObjectNode();
                result.put("probeStop", limit).put("httpLimit", 300).put("wallClockLimitSeconds", 1200);
                result.set("autonomy", JSON.valueToTree(host.autonomyStatus()));
                result.set("live", JSON.valueToTree(host.liveStatus()));
                var companies = result.putObject("companyGovernance");
                for (String company : host.liveCapabilities().governancePersons().stream()
                        .flatMap(p -> p.governanceCompanies().stream()).distinct().sorted().toList()) {
                    companies.set(company, JSON.valueToTree(host.engineeringView(company)));
                }
                JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("result.json").toFile(), result);
                System.out.println("finished=" + host.autonomyStatus().reason() + " calls=" + calls.get());
            }
        }
    }
}
