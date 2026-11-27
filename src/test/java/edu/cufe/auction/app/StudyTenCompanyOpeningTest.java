package edu.cufe.auction.app;

import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.WorldEvent;
import edu.cufe.auction.world.WorldJournal;
import edu.cufe.auction.world.WorldJournalScanner;
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

    @Test void thirdVersionAddsOnlyReadingClockAndBothFactoriesRecoverFrozenWorlds() throws Exception {
        Path second = dir.resolve("previous.journal");
        Path third = dir.resolve("world-ten-company-autonomy-v3.journal");
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

    @Test void fourthVersionFreezesBothNewPoliciesAndWebReopensTheOriginalArchives() throws Exception {
        Path current = dir.resolve("world-ten-company-autonomy-v4.journal");
        Path old = dir.resolve("world-ten-company-autonomy-v3.journal");
        try (var third = ComposedIndustryScenario.openStudyTenCompanyAutonomy(old)) {
            var oldManifest = third.profile().manifest();
            var expected = new TreeMap<>(oldManifest.dataVersions());
            expected.put("branch-profile-run", RunManifest.GOVERNANCE_TEN_COMPANY_RUN_ID);
            expected.put(RunManifest.EDITORIAL_TEMPLATE_GUIDE, RunManifest.OFFICE_TEMPLATE_KEYS);
            expected.put(RunManifest.ISSUER_PERIOD_METRICS, RunManifest.OPERATING_PERIOD_EVIDENCE);
            try (var fourth = ComposedIndustryScenario.openCurrentTenCompanyStudy(current)) {
                var manifest = fourth.profile().manifest();
                assertEquals(RunManifest.GOVERNANCE_TEN_COMPANY_RUN_ID, manifest.runId());
                assertEquals(expected, manifest.dataVersions());
                assertEquals(third.snapshot().accounts(), fourth.snapshot().accounts());
                assertEquals(third.openingRegistry(), fourth.openingRegistry());
                for (String key : java.util.List.of(RunManifest.EDITORIAL_TEMPLATE_GUIDE, RunManifest.ISSUER_PERIOD_METRICS)) {
                    var missing = new TreeMap<>(expected);
                    missing.remove(key);
                    assertThrows(IllegalArgumentException.class, () -> withPolicies(manifest, missing));
                    var grafted = new TreeMap<>(oldManifest.dataVersions());
                    grafted.put(key, expected.get(key));
                    assertThrows(IllegalArgumentException.class, () -> withPolicies(oldManifest, grafted));
                }
            }
        }
        assertEquals(current, WorldWebApp.tenCompanyJournal(dir));
        byte[] previousBytes = Files.readAllBytes(old);
        try (var reopened = ComposedIndustryScenario.openCurrentTenCompanyStudy(old)) {
            assertEquals(RunManifest.STUDY_TEN_COMPANY_AUTONOMY_RUN_ID, reopened.profile().manifest().runId());
        }
        assertArrayEquals(previousBytes, Files.readAllBytes(old));
    }

    @Test void standardEntryStartsSixthVersionAndReopensEarlierFilesWithoutChangingThem() throws Exception {
        Path latest = WorldWebApp.tenCompanyJournal(dir);
        assertEquals(dir.resolve("world-ten-company-autonomy-v6.journal"), latest);
        Path newWorld = dir.resolve("world-ten-company-autonomy-v5.journal");
        Path oldWorld = dir.resolve("world-ten-company-autonomy-v4.journal");
        try (var fourth = ComposedIndustryScenario.openCurrentTenCompanyStudy(oldWorld)) {
            assertEquals(RunManifest.GOVERNANCE_TEN_COMPANY_RUN_ID, fourth.profile().manifest().runId());
        }
        byte[] archive = Files.readAllBytes(oldWorld);
        try (var fourth = ComposedIndustryScenario.openEditorialTenCompanyStudy(WorldWebApp.tenCompanyJournal(dir));
             var fifth = ComposedIndustryScenario.openEditorialTenCompanyStudy(newWorld)) {
            assertEquals(RunManifest.GOVERNANCE_TEN_COMPANY_RUN_ID, fourth.profile().manifest().runId());
            var manifest = fifth.profile().manifest();
            assertEquals(RunManifest.READABLE_DRAFT_TEN_COMPANY_RUN_ID, manifest.runId());
            var expected = new TreeMap<>(fourth.profile().manifest().dataVersions());
            expected.put("branch-profile-run", manifest.runId());
            expected.put("editorial-draft-visibility", "submitted-document-v1");
            assertEquals(expected, manifest.dataVersions());
            assertEquals(fourth.openingRegistry(), fifth.openingRegistry());
        }
        assertArrayEquals(archive, Files.readAllBytes(oldWorld));
        assertEquals(newWorld, WorldWebApp.tenCompanyJournal(dir));
        byte[] fifthBytes = Files.readAllBytes(newWorld);
        try (var fifth = ComposedIndustryScenario.openPersonalTenCompanyStudy(WorldWebApp.tenCompanyJournal(dir));
             var sixth = ComposedIndustryScenario.openPersonalTenCompanyStudy(latest)) {
            assertEquals(RunManifest.READABLE_DRAFT_TEN_COMPANY_RUN_ID, fifth.profile().manifest().runId());
            var manifest = sixth.profile().manifest();
            assertEquals(RunManifest.PERSONAL_TEN_COMPANY_RUN_ID, manifest.runId());
            var expected = new TreeMap<>(fifth.profile().manifest().dataVersions());
            expected.put("branch-profile-run", manifest.runId());
            expected.put(RunManifest.CRISIS_OFFICE_OBJECTIVES, RunManifest.ACTIVE_APPOINTMENT_GOALS);
            expected.put(RunManifest.PERSONAL_NOTE_CONTRACT, RunManifest.EXPLICIT_NOTE_REFERENCE);
            expected.put(RunManifest.DECISION_ENVELOPE_GUIDE, RunManifest.TYPED_REVIEW_FIELDS);
            assertEquals(expected, manifest.dataVersions());
            assertEquals(fifth.openingRegistry(), sixth.openingRegistry());
            for (String key : java.util.List.of(RunManifest.CRISIS_OFFICE_OBJECTIVES, RunManifest.PERSONAL_NOTE_CONTRACT,
                    RunManifest.DECISION_ENVELOPE_GUIDE)) {
                var missing = new TreeMap<>(expected);
                missing.remove(key);
                assertThrows(IllegalArgumentException.class, () -> withPolicies(manifest, missing));
                var grafted = new TreeMap<>(fifth.profile().manifest().dataVersions());
                grafted.put(key, expected.get(key));
                assertThrows(IllegalArgumentException.class, () -> withPolicies(fifth.profile().manifest(), grafted));
            }
        }
        assertArrayEquals(fifthBytes, Files.readAllBytes(newWorld));
        assertEquals(latest, WorldWebApp.tenCompanyJournal(dir));
    }

    @Test void historicalNamesUseNonemptyArchiveAndRejectSwappedOrIndependentWorlds() throws Exception {
        Path first = dir.resolve("world-ten-company-autonomy-v1.journal");
        Path second = dir.resolve("world-ten-company-autonomy-v2.journal");
        Path third = dir.resolve("world-ten-company-autonomy-v3.journal");
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
        try (var study = ComposedIndustryScenario.openStudyTenCompanyAutonomy(third)) {
            assertEquals(RunManifest.STUDY_TEN_COMPANY_AUTONOMY_RUN_ID, study.profile().manifest().runId());
        }
        assertEquals(third, WorldWebApp.tenCompanyJournal(dir));
        Path fourth = dir.resolve("world-ten-company-autonomy-v4.journal");
        Files.write(fourth, firstBytes);
        assertThrows(IOException.class, () -> WorldWebApp.tenCompanyJournal(dir));
        assertArrayEquals(firstBytes, Files.readAllBytes(fourth));
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

    @Test void strictRestoreRequiresOpeningDeclarationsToMatchTheConfirmedCommand() throws Exception {
        Path original = dir.resolve("source.journal");
        try (var world = ComposedIndustryScenario.openCurrentTenCompanyStudy(original)) {
            assertEquals(RunManifest.GOVERNANCE_TEN_COMPANY_RUN_ID, world.profile().manifest().runId());
        }
        var first = WorldJournalScanner.readPrefix(original, 1, null).first();
        for (String type : java.util.List.of("RUN_MANIFEST_V1", "OPENING_REGISTRY_V1")) {
            Path inconsistent = dir.resolve(type + ".journal");
            var events = first.events().stream().map(event -> event.type().equals(type)
                    ? new WorldEvent(event.eventId(), type, "{}") : event).toList();
            try (var journal = new WorldJournal(inconsistent, first.runId())) {
                journal.commit(first.commandId(), first.commandContent(), first.result(), events);
            }
            byte[] before = Files.readAllBytes(inconsistent);
            var failure = assertThrows(IOException.class, () -> ComposedIndustryScenario.openCurrentTenCompanyStudy(inconsistent));
            assertTrue(failure.getMessage().contains("开局事件"), failure.getMessage());
            assertArrayEquals(before, Files.readAllBytes(inconsistent));
        }
    }
}
