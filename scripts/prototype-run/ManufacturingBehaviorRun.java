import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** 独立整改样本；仅初始化真实董事任命，经营全部调用 Web 共用状态规则，无日历交易脚本。 */
public final class ManufacturingBehaviorRun {
    /** 参数：全新工作目录、实验日数；保留原季度。 */
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]); int days = Integer.parseInt(args[1]);
        if (days < 1 || days > 90) throw new IllegalArgumentException("实验日须在1至90之间");
        Files.createDirectories(folder);
        Path journal = folder.resolve("world-industry-behavior-v1.journal");
        if (Files.exists(journal)) throw new IllegalArgumentException("禁止覆盖已有实验");
        var json = new ObjectMapper();
        try (var world = ComposedIndustryScenario.openBehavior(journal)) {
            PrototypeGovernance.grantBudgetAndAppoint(world, ComposedManufacturingThreeScenario.TECH,
                    ComposedManufacturingThreeScenario.TECH_DIRECTORS, ComposedManufacturingThreeScenario.TECH_MANAGER,
                    "behavior-opening", List.of(0L, 1L, 2L));
            Files.writeString(folder.resolve("opening-report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(ComposedCompanyReport.of(world, "mfg-tech")));
            for (int day = 1; day <= days; day++) {
                ManufacturingBehaviorLoop.advanceTo(world, day * ManufacturingTemplateModel.DAY_MILLIS);
                System.out.println("DAY=" + day + " POSITION=" + world.snapshot().position());
            }
            Files.writeString(folder.resolve("behavior-audit.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(ManufacturingBehaviorLoop.audit(world)));
            Files.writeString(folder.resolve("final-report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(ComposedCompanyReport.of(world, "mfg-tech")));
            Files.writeString(folder.resolve("final-accounts.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(world.snapshot().accounts()));
        }
        try (var world = ComposedIndustryScenario.openBehavior(journal)) {
            Files.writeString(folder.resolve("recovered.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "position", world.snapshot().position(), "timeMillis", world.snapshot().timeMillis(),
                    "report", ComposedCompanyReport.of(world, "mfg-tech"), "decisions", ManufacturingBehaviorLoop.audit(world).size())));
        }
    }
}
