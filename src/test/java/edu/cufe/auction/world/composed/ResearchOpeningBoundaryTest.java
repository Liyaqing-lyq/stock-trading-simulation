package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.ResearchModel;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 研发开局必须绑定冻结公司的真实账户，重试不得重新开账。 */
class ResearchOpeningBoundaryTest {
    @TempDir Path dir;

    @Test
    void cannotBindAnotherCompanyOrInventOpeningAssets() throws Exception {
        try (WorldRuntime world = uninitialized(dir.resolve("identity.journal"))) {
            long position = world.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> world.initializeResearch("wrong",
                    "eng-a", new ResearchModel.Config("eng-b", "ENG-B", BigDecimal.ZERO),
                    WorldWebScenario.researchProjects(), 1));
            assertThrows(IllegalArgumentException.class, () -> world.initializeResearch("phantom",
                    "eng-a", new ResearchModel.Config("eng-a", "ENG-A", BigDecimal.ONE),
                    WorldWebScenario.researchProjects(), 1));
            assertEquals(position, world.snapshot().position());
            assertTrue(world.researchRun().isEmpty());
        }
    }

    @Test
    void exactInitializationRetryKeepsOriginalReceiptAndPosition() throws Exception {
        try (WorldRuntime world = uninitialized(dir.resolve("retry.journal"))) {
            var config = new ResearchModel.Config("eng-a", "ENG-A", BigDecimal.ZERO);
            String receipt = world.initializeResearch("init", "eng-a", config,
                    WorldWebScenario.researchProjects(), 1);
            long position = world.snapshot().position();
            assertEquals(receipt, world.initializeResearch("init", "eng-a", config,
                    WorldWebScenario.researchProjects(), 1));
            assertThrows(IllegalArgumentException.class, () -> world.initializeResearch("init",
                    "eng-a", config, WorldWebScenario.researchProjects(), 2));
            assertEquals(position, world.snapshot().position());
        }
    }

    private static WorldRuntime uninitialized(Path file) throws Exception {
        return ComposedWorldScenario.open(file, ComposedResearchScenario.manifest(),
                ComposedResearchScenario.openings(), ComposedResearchScenario.registry(),
                ComposedResearchScenario.bankOpening());
    }
}
