package edu.cufe.auction.world;

import edu.cufe.auction.world.composed.ComposedResearchScenario;
import edu.cufe.auction.world.composed.ComposedWorldSelfTest;
import edu.cufe.auction.world.composed.ManufacturingFundingChainTest;
import java.nio.file.Files;
import java.nio.file.Path;

/** 以真实任免、预算与研发动作准备独立网页调试档，不修改已有世界或直接注入账目。 */
public final class OperatingWebPreviewProbe {
    private OperatingWebPreviewProbe() { }

    /**
     * @param args 唯一参数为全新日志路径
     * @throws Exception 路径已存在或准备动作失败
     */
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        if (Files.exists(file)) throw new IllegalArgumentException("调试日志已存在，拒绝覆盖");
        try (WorldRuntime world = ComposedResearchScenario.openFunded(file)) {
            ResearchCommercialWorldTest.buildCompletedProject(world);
            ComposedWorldSelfTest.appoint(world, world.snapshot().timeMillis() + 10,
                    "cufe", "erin", "preview-manager", "preview-manager-material");
            ManufacturingFundingChainTest.budgetMeeting(world, "cufe", "preview-budget", "10000.00", "10000.00");
            System.out.println("OPERATING_PREVIEW_READY position=" + world.snapshot().position()
                    + " timeMillis=" + world.snapshot().timeMillis()
                    + " research=" + world.researchRun().orElseThrow().state().projects().get("p1").status()
                    + " manufacturingManager=" + world.isActiveManager("cufe", "erin", world.snapshot().timeMillis()));
        }
    }
}
