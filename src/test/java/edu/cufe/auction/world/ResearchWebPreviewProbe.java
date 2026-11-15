package edu.cufe.auction.world;

import edu.cufe.auction.world.composed.ComposedResearchScenario;
import java.nio.file.Files;
import java.nio.file.Path;

/** 网页检查预设：通过真实任免、预算与阶段任务建立调试档，不直接写世界状态。 */
public final class ResearchWebPreviewProbe {
    private ResearchWebPreviewProbe() {
    }

    /**
     * 生成一次独立调试档，已有档案明确拒绝，避免重放夹具扰动用户运行。
     * @param args 唯一参数为新日志文件
     * @throws Exception 文件已存在或真实工作流失败
     */
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        if (Files.exists(file)) throw new IllegalArgumentException("调试日志已存在，拒绝覆盖");
        try (WorldRuntime world = ComposedResearchScenario.open(file)) {
            ResearchCommercialWorldTest.buildCompletedProject(world);
            System.out.println("PREVIEW_READY position=" + world.snapshot().position()
                    + " timeMillis=" + world.snapshot().timeMillis()
                    + " research=" + world.researchRun().orElseThrow().state().projects().get("p1").status());
        }
    }
}
