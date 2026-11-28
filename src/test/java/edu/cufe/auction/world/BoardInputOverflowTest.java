package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.composed.BaselineIndustryConfig;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import edu.cufe.auction.world.composed.ConnectedWorldBootstrap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BoardInputOverflowTest {
    @TempDir Path dir;
    private static final long LIMIT = 32_768;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void tenCompanyDirectorUsesSecondTableOnlyOnOverflowAndReopensSameFrozenInput() throws Exception {
        Path journal = dir.resolve("ten.journal");
        String chair = BaselineIndustryConfig.directors(AgedIndustryConfig.COMPANY).get(0);
        String input;
        try (var world = ComposedIndustryScenario.openOriginalTenCompanyAutonomy(journal)) {
            ConnectedWorldBootstrap.grantBudgetAndAppoint(world, AgedIndustryConfig.COMPANY,
                    BaselineIndustryConfig.directors(AgedIndustryConfig.COMPANY), AgedIndustryConfig.MANAGER_PERSON, "term", List.of(0L));
            world.beginDecisionSession("one-director");
            var small = (ObjectNode) world.autonomyRoleObservation(chair);
            assertEquals(small.toString(), DecisionRun.renderInput(small.deepCopy(), RoleInputTables.BOARD_VERSION, LIMIT));
            assertTrue(small.at("/engineering/directorships/0/meetings/0/planningPrerequisites/board.vote").isObject());

            var sizeWithFirstTable = small.deepCopy();
            RoleInputTables.compact(sizeWithFirstTable);
            int padding = Math.toIntExact(LIMIT - sizeWithFirstTable.toString().length() + 120);
            assertTrue(padding > 0);
            long due = world.snapshot().timeMillis() + 1;
            world.schedulePublishDocument("publish-size", "threshold-task", due, "director-size", "nina",
                    InformationModel.Audience.publicAudience(), "A".repeat(padding), "测试公开材料", "输入边界回归", 1000, List.of());
            world.advanceTo(due);
            var packed = (ObjectNode) world.autonomyRoleObservation(chair);
            var expanded = packed.deepCopy();
            RoleInputTables.expand(expanded);
            String old = DecisionRun.renderInput(expanded.deepCopy(), RoleInputTables.VERSION, LIMIT);
            assertTrue(old.length() > LIMIT);
            assertTrue(old.contains("\"board.vote\":{"));
            assertTrue(packed.at("/engineering/directorships/0/meetings/0/planningPrerequisites/actions/columns").isArray());
            assertTrue(packed.toString().length() <= LIMIT);
            assertTrue(packed.toString().getBytes(StandardCharsets.UTF_8).length <= DecisionModel.MAX_INPUT_BYTES);
            assertTrue(world.autonomyInputLimitViolation(chair).isEmpty());
            var oldForm = packed.deepCopy();
            RoleInputTables.expandBoardConditions(oldForm);
            assertEquals(old, oldForm.toString());
            assertEquals(packed.toString(), DecisionRun.renderInput(expanded.deepCopy(),RoleInputTables.BOARD_VERSION,LIMIT));

            var route = new DecisionModel.Route("governance","key-1","deepseek-flash",1L,25,1_000_000,2048,1);
            world.openDecisions("actual", List.of(new DecisionRun.Request(chair,"board-overflow","wake",route)));
            input = DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),"board-overflow").orElseThrow().inputText();
            assertEquals(packed, JSON.readTree(input));
        }
        try (var world = ComposedIndustryScenario.openTenCompanyAutonomy(journal)) {
            assertEquals(input, DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),
                    "board-overflow").orElseThrow().inputText());
            assertEquals(0, world.snapshot().decisions().orElseThrow().state().runUsage().attempts());
        }
    }
}
