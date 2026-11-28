package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.WorldRuntime;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 模型取到的召集工具须与该角色所持章程席位一致。 */
class BoardConveneToolScopeTest {
    @TempDir Path dir;

    @Test void ordinaryDirectorCanProposeAndVoteWhileOnlyChairCanConvene() throws Exception {
        var file = dir.resolve("funded.journal");
        try (var world = ComposedIndustryScenario.openFundedThinkingOperating(file)) {
            checkScope(world);
            var call = PharmaDecisionToolTest.open(world, "mfgstruct-d5", "observe-ordinary");
            assertFalse(call.allowedTools().contains("board.convene"));
            assertTrue(call.allowedTools().contains("board.proposeBudget"));
        }
        try (var reopened = ComposedIndustryScenario.openFundedThinkingOperating(file)) {
            checkScope(reopened);
        }
    }

    private static void checkScope(WorldRuntime world) throws Exception {
        var ordinary = world.openRoleSession("mfgstruct-d5").view().tools();
        assertFalse(ordinary.contains("board.convene"), "普通董事的章程没有召集权");
        assertTrue(ordinary.containsAll(List.of("board.proposeBudget", "board.vote", "board.implement")));
        assertTrue(world.openRoleSession("mfgstruct-d1").view().tools().contains("board.convene"));
    }

    @Test void oldThinkingJournalKeepsItsOriginalToolContract() throws Exception {
        try (var world = ComposedIndustryScenario.openThinkingInformedOperating(dir.resolve("old.journal"))) {
            assertTrue(world.openRoleSession("mfgstruct-d5").view().tools().contains("board.convene"));
        }
    }
}

