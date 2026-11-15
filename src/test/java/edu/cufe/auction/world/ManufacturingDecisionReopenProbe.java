package edu.cufe.auction.world;

import edu.cufe.auction.world.composed.ComposedResearchScenario;
import edu.cufe.auction.world.composed.ComposedWorldSelfTest;
import edu.cufe.auction.world.composed.ManufacturingFundingChainTest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 三个真正独立进程恢复模型经营意图，再从同一确认点分支生产。 */
public final class ManufacturingDecisionReopenProbe {
    private ManufacturingDecisionReopenProbe() { }
    /** @param args 工作目录和步骤 @throws Exception 恢复、操作或断言失败 */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int step = Integer.parseInt(args[1]);
        try (WorldRuntime world = ComposedResearchScenario.openDebug(dir.resolve("world.journal"))) {
            if (step == 0) {
                ComposedWorldSelfTest.appoint(world, 10, "cufe", "erin", "manager", "material");
                ManufacturingFundingChainTest.budgetMeeting(world, "cufe", "budget", "10000.00", "10000.00");
                long finish = ManufacturingDecisionToolTest.reply(world, ManufacturingDecisionToolTest.open(world, "buy"),
                        "PURCHASE_ORDER", "JVM-PO", "CASH|CO");
                world.advanceTo(finish - 500);
                Files.writeString(dir.resolve("arrival.txt"), Long.toString(finish + 86_400_000));
            } else if (step == 1) {
                world.advanceTo(Long.parseLong(Files.readString(dir.resolve("arrival.txt"))));
                assertEquals(10, world.snapshot().manufacturing().orElseThrow().ledger().rawMaterials().quantity());
                var branch = world.createBranch("manufacturing-model-child", world.snapshot().position(),
                        BranchOrigin.Kind.OPERATOR_BRANCH, "模型经理采购后分支生产");
                Files.write(dir.resolve("branch.txt"), List.of(branch.childFile().toString(), branch.sealedPrefix().toString()));
            } else if (step == 2) {
                var before = world.snapshot();
                var paths = Files.readAllLines(dir.resolve("branch.txt"));
                try (WorldRuntime child = WorldRuntime.openBranch(Path.of(paths.get(0)), id -> Path.of(paths.get(1)), world.profile())) {
                    long finish = ManufacturingDecisionToolTest.reply(child, ManufacturingDecisionToolTest.open(child, "produce"),
                            "PRODUCTION", "CHILD-BATCH", "CASH|CO");
                    child.advanceTo(finish + 86_400_000);
                    assertEquals(10, child.snapshot().manufacturing().orElseThrow().ledger().finishedGoods().quantity());
                    assertEquals(new BigDecimal("200.00"), child.snapshot().accounts().get("CO").cash());
                }
                assertEquals(before, world.snapshot());
                assertEquals(new BigDecimal("500.00"), world.snapshot().accounts().get("CO").cash());
                assertEquals(0, world.snapshot().manufacturing().orElseThrow().ledger().finishedGoods().quantity());
            } else throw new IllegalArgumentException("未知步骤");
            System.out.println("MANUFACTURING_DECISION_JVM_OK step=" + step);
        }
    }
}
