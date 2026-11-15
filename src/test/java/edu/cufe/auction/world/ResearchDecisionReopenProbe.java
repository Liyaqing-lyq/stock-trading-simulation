package edu.cufe.auction.world;

import edu.cufe.auction.world.composed.ComposedResearchScenario;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 真实进程边界核对研发模型输入顺序、待办恢复与分支付款隔离。 */
public final class ResearchDecisionReopenProbe {
    private ResearchDecisionReopenProbe() { }
    /** @param args 工作目录和步骤 @throws Exception 运行或断言失败 */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int step = Integer.parseInt(args[1]);
        try (WorldRuntime world = ComposedResearchScenario.openDebug(dir.resolve("research.journal"))) {
            if (step == 0) {
                ResearchCommercialWorldTest.prepared(world);
                long finish = ResearchDecisionToolTest.reply(world, ResearchDecisionToolTest.open(world, "register"),
                        "REGISTER", "p1", "", "");
                world.advanceTo(finish - 500);
                Files.writeString(dir.resolve("finish.txt"), Long.toString(finish));
            } else if (step == 1) {
                world.advanceTo(Long.parseLong(Files.readString(dir.resolve("finish.txt"))));
                assertNotNull(world.researchRun().orElseThrow().state().projects().get("p1"));
                long finish = ResearchDecisionToolTest.reply(world, ResearchDecisionToolTest.open(world, "begin"),
                        "BEGIN", "p1", "RESEARCH", "");
                world.advanceTo(finish - 500);
                Files.writeString(dir.resolve("finish.txt"), Long.toString(finish));
            } else if (step == 2) {
                world.advanceTo(Long.parseLong(Files.readString(dir.resolve("finish.txt"))) + 86_400_000);
                assertEquals(new BigDecimal("4800.00"), world.snapshot().accounts().get("ENG-A").cash());
                var branch = world.createBranch("research-model-child", world.snapshot().position(),
                        BranchOrigin.Kind.OPERATOR_BRANCH, "研发模型分支继续开发");
                var before = world.snapshot();
                try (WorldRuntime child = WorldRuntime.openBranch(branch.childFile(), id -> branch.sealedPrefix(), world.profile())) {
                    long finish = ResearchDecisionToolTest.reply(child, ResearchDecisionToolTest.open(child, "development"),
                            "BEGIN", "p1", "DEVELOPMENT", "");
                    child.advanceTo(finish);
                    assertEquals(new BigDecimal("4500.00"), child.snapshot().accounts().get("ENG-A").cash());
                }
                assertEquals(before, world.snapshot());
            } else throw new IllegalArgumentException("未知步骤");
            System.out.println("RESEARCH_DECISION_JVM_OK step=" + step);
        }
    }
}
