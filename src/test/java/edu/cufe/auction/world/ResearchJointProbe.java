package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import java.nio.file.Path;

/**
 * 跨 JVM 研发续跑探针：打开既有 schema22 日志，继续 p1 到指定阶段并打印结果。
 *
 * <p>只走真实公开入口与真实 journal 恢复；不注入状态、不读取 seed/未来结果。</p>
 */
public final class ResearchJointProbe {

    private ResearchJointProbe() {
    }

    /**
     * @param args [0]=日志路径 [1]=要完成的阶段数(1..3)
     * @throws Exception 恢复/写入失败
     */
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int stages = Integer.parseInt(args[1]);
        WorldRuntime world = WorldWebScenario.openResearch(file).runtime();
        int completed = 0;
        for (int i = 0; i < 3 && completed < stages; i++) {
            ResearchRun run = world.researchRun().orElseThrow();
            ResearchModel.Stage stage = ResearchModel.Stage.values()[i];
            ResearchModel.StageStatus status = run.state().projects().get("p1")
                    .stages().get(i).status();
            if (status == ResearchModel.StageStatus.COMPLETED) {
                completed++;
                continue;
            }
            long current = world.snapshot().timeMillis();
            long duration = run.project("p1").stages().get(i).durationMillis();
            long resultAt;
            if (status == ResearchModel.StageStatus.IN_PROGRESS) {
                resultAt = current + duration;
            } else {
                long finish = current + 2_000L;
                world.scheduleResearchBegin("joint-b:" + i, "joint-b-task:" + i, finish,
                        WorldWebScenario.ENGINE_A, "dave", "p1", stage, 1_000L);
                world.advanceTo(finish);
                resultAt = finish + duration;
            }
            world.advanceTo(resultAt);
            completed++;
        }
        ResearchRun run = world.researchRun().orElseThrow();
        StringBuilder sb = new StringBuilder("P1=").append(run.state().projects().get("p1").status());
        for (int i = 0; i < 3; i++) {
            sb.append(" S").append(i).append('=')
                    .append(run.state().projects().get("p1").stages().get(i).status());
        }
        System.out.println(sb);
        world.close();
    }
}
