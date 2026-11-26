package edu.cufe.auction.app;

import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StudyTenCompanyOpeningTest {
    @TempDir Path dir;

    @Test void newWebDefaultAddsOnlyReadingClockAndBothFactoriesRecoverFrozenWorlds() throws Exception {
        Path second = dir.resolve("previous.journal");
        Path third = WorldWebApp.tenCompanyJournal(dir);
        assertEquals("world-ten-company-autonomy-v3.journal", third.getFileName().toString());
        try (var old = ComposedIndustryScenario.openTenCompanyAutonomy(second);
             var study = ComposedIndustryScenario.openStudyTenCompanyAutonomy(third)) {
            var oldData = old.profile().manifest().dataVersions();
            assertEquals(RunManifest.TEN_COMPANY_AUTONOMY_RUN_ID, old.profile().manifest().runId());
            assertFalse(oldData.containsKey(RunManifest.DOCUMENT_READING_CLOCK));
            var current = study.profile().manifest();
            assertEquals(RunManifest.STUDY_TEN_COMPANY_AUTONOMY_RUN_ID, current.runId());
            var expected = new TreeMap<>(oldData);
            expected.put("branch-profile-run", current.runId());
            expected.put(RunManifest.DOCUMENT_READING_CLOCK, RunManifest.BODY_PACING_300);
            assertEquals(expected, current.dataVersions());
            assertEquals(old.snapshot().accounts(), study.snapshot().accounts());
            assertEquals(old.openingRegistry().orElseThrow().parties(), study.openingRegistry().orElseThrow().parties());
            expected.remove(RunManifest.DOCUMENT_READING_CLOCK);
            assertThrows(IllegalArgumentException.class, () -> withPolicies(current, expected));
            expected.put(RunManifest.DOCUMENT_READING_CLOCK, "wrong");
            assertThrows(IllegalArgumentException.class, () -> withPolicies(current, expected));
            var grafted = new TreeMap<>(oldData);
            grafted.put(RunManifest.DOCUMENT_READING_CLOCK, RunManifest.BODY_PACING_300);
            assertThrows(IllegalArgumentException.class, () -> withPolicies(old.profile().manifest(), grafted));
            expected.clear();
            expected.putAll(oldData);
            assertThrows(IllegalArgumentException.class, () -> withPolicies(current, expected));
        }
        byte[] oldFile = Files.readAllBytes(second), studyFile = Files.readAllBytes(third);
        try (var old = ComposedIndustryScenario.openStudyTenCompanyAutonomy(second);
             var study = ComposedIndustryScenario.openTenCompanyAutonomy(third)) {
            assertEquals(RunManifest.TEN_COMPANY_AUTONOMY_RUN_ID, old.profile().manifest().runId());
            assertEquals(RunManifest.STUDY_TEN_COMPANY_AUTONOMY_RUN_ID, study.profile().manifest().runId());
        }
        assertArrayEquals(oldFile, Files.readAllBytes(second));
        assertArrayEquals(studyFile, Files.readAllBytes(third));
    }

    @Test void historicalNamesUseNonemptyArchiveAndRejectSwappedOrIndependentWorlds() throws Exception {
        Path first = dir.resolve("world-ten-company-autonomy-v1.journal");
        Path second = dir.resolve("world-ten-company-autonomy-v2.journal");
        Path third = WorldWebApp.tenCompanyJournal(dir);
        Files.createFile(second);
        Files.createFile(third);
        try (var original = ComposedIndustryScenario.openOriginalTenCompanyAutonomy(first)) {
            assertEquals(RunManifest.ORIGINAL_TEN_COMPANY_RUN_ID, original.profile().manifest().runId());
        }
        assertEquals(first, WorldWebApp.tenCompanyJournal(dir));
        byte[] firstBytes = Files.readAllBytes(first);
        try (var restored = ComposedIndustryScenario.openStudyTenCompanyAutonomy(first)) {
            assertEquals(RunManifest.ORIGINAL_TEN_COMPANY_RUN_ID, restored.profile().manifest().runId());
            assertFalse(restored.profile().manifest().dataVersions().containsKey(RunManifest.DOCUMENT_READING_CLOCK));
        }
        assertArrayEquals(firstBytes, Files.readAllBytes(first));
        try (var legacy = ComposedIndustryScenario.openTenCompanyAutonomy(second)) {
            assertEquals(RunManifest.TEN_COMPANY_AUTONOMY_RUN_ID, legacy.profile().manifest().runId());
        }
        assertEquals(second, WorldWebApp.tenCompanyJournal(dir));
        Files.write(third, firstBytes);
        assertThrows(IOException.class, () -> WorldWebApp.tenCompanyJournal(dir));
        assertArrayEquals(firstBytes, Files.readAllBytes(third));
        Path sibling = dir.resolve("independent.journal");
        try (var fund = ComposedIndustryScenario.openOpenFundOperating(sibling)) {
            assertFalse(fund.profile().manifest().dataVersions().containsKey(RunManifest.DOCUMENT_READING_CLOCK));
        }
        byte[] fundBytes = Files.readAllBytes(sibling);
        assertThrows(IOException.class, () -> ComposedIndustryScenario.openStudyTenCompanyAutonomy(sibling));
        assertArrayEquals(fundBytes, Files.readAllBytes(sibling));
    }

    private static RunManifest withPolicies(RunManifest original, Map<String, String> policies) {
        return new RunManifest(original.runId(), original.seed(), original.rulesVersion(), original.moduleVersions(),
                policies, original.modelConfigVersion());
    }
}
