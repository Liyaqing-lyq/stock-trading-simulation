package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentReadingClockTest {
    @TempDir Path dir;

    private static JsonNode catalog(WorldRuntime world, String person, String docId) throws java.io.IOException {
        var input = (ObjectNode) world.autonomyRoleObservation(person).deepCopy();
        RoleInputTables.expand(input);
        return StreamSupport.stream(input.at("/profile/catalog").spliterator(), false)
                .filter(row -> docId.equals(row.path("docId").asText())).findFirst().orElse(null);
    }

    @Test void fixedCostAndPacedArchivesRetainTheirOriginalDocumentDurations() throws Exception {
        for (boolean paced : List.of(false, true)) {
            Path journal = dir.resolve(paced ? "new.journal" : "fixed.journal");
            String document = "test:own-reading";
            long due;
            try (var world = paced ? ComposedIndustryScenario.openStudyTenCompanyAutonomy(journal)
                    : ComposedIndustryScenario.openTenCompanyAutonomy(journal)) {
                // 2 汉字 + 1 个 ASCII 单词 + 1 个整数 + 1 个增补平面汉字。
                world.schedulePublishDocument("p", "publish", 2, document, "investor",
                        InformationModel.Audience.direct("nina"), "收据", "核对 PDFv2 123 𠀀", "个人试验", 500, List.of());
                world.advanceTo(2);
                var visible = catalog(world, "nina", document);
                assertNotNull(visible);
                assertNull(catalog(world, "omar", document));
                assertFalse(visible.has("body"));
                if (paced) assertEquals(1000, visible.path("readDurationMillis").longValue());
                else assertFalse(visible.has("readDurationMillis"));
                world.scheduleStartReading("r", "read", 3, "nina", document, 1);
                world.advanceTo(3);
                var reading = world.snapshot().information().orElseThrow().information().active().get(0);
                due = 3 + (paced ? 1000 : 500);
                assertEquals(due, reading.dueAt());
                world.schedulePublishDocumentVersion("v2", "release-v2", 4, document,
                        "新版", "只", "独立版本", 6000, List.of());
                world.advanceTo(due - 1);
                assertTrue(world.snapshot().information().orElseThrow().information().observations().isEmpty());
                assertEquals(2, catalog(world, "nina", document).path("version").intValue());
                if (paced) assertEquals(6000, catalog(world, "nina", document).path("readDurationMillis").longValue());
            }
            try (var world = ComposedIndustryScenario.openStudyTenCompanyAutonomy(journal)) {
                world.advanceTo(due);
                var observation = world.snapshot().information().orElseThrow().information().observations().get(0);
                assertEquals(1, observation.version());
                assertEquals("核对 PDFv2 123 𠀀", observation.body());
                assertEquals(due, observation.completedAt());
            }
            byte[] sealed = Files.readAllBytes(journal);
            try (var world = ComposedIndustryScenario.openStudyTenCompanyAutonomy(journal)) {
                assertEquals(due, world.snapshot().timeMillis());
                assertEquals(paced ? RunManifest.BODY_PACING_300 : null,
                        world.snapshot().information().orElseThrow().information().readingClock());
            }
            assertArrayEquals(sealed, Files.readAllBytes(journal));
        }
    }
}
