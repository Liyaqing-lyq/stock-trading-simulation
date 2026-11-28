package edu.cufe.auction.app;

import edu.cufe.auction.world.RunManifest;
import edu.cufe.auction.world.BranchOrigin;
import edu.cufe.auction.world.BranchRepository;
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

    @Test void seventhVersionReopensEarlierFilesWithoutChangingThem() throws Exception {
        Path latest = dir.resolve("world-ten-company-autonomy-v6.journal");
        Path seventhFile = dir.resolve("world-ten-company-autonomy-v7.journal");
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
        byte[] sixthBytes = Files.readAllBytes(latest);
        try (var sixth = ComposedIndustryScenario.openInterviewTenCompanyStudy(WorldWebApp.tenCompanyJournal(dir));
             var seventh = ComposedIndustryScenario.openInterviewTenCompanyStudy(seventhFile)) {
            var previous = sixth.profile().manifest();
            var manifest = seventh.profile().manifest();
            assertEquals(RunManifest.PERSONAL_TEN_COMPANY_RUN_ID, previous.runId());
            assertEquals(RunManifest.INTERVIEW_TEN_COMPANY_RUN_ID, manifest.runId());
            var expected = new TreeMap<>(previous.dataVersions());
            expected.put("branch-profile-run", manifest.runId());
            expected.put(RunManifest.INTERVIEW_RECEIPTS, RunManifest.ATTRIBUTED_INTERVIEW_RESULTS);
            expected.put(RunManifest.SOCIAL_RECEIPTS, RunManifest.ATTRIBUTED_SOCIAL_RESULTS);
            expected.put(RunManifest.PERIODIC_RECEIPTS, RunManifest.ATTRIBUTED_PERIOD_RESULTS);
            assertEquals(expected, manifest.dataVersions());
            assertEquals(sixth.openingRegistry(), seventh.openingRegistry());
            for (String key : java.util.List.of(RunManifest.INTERVIEW_RECEIPTS, RunManifest.SOCIAL_RECEIPTS, RunManifest.PERIODIC_RECEIPTS)) {
                var missing = new TreeMap<>(expected);
                missing.remove(key);
                assertThrows(IllegalArgumentException.class, () -> withPolicies(manifest, missing));
                var grafted = new TreeMap<>(previous.dataVersions());
                grafted.put(key, expected.get(key));
                assertThrows(IllegalArgumentException.class, () -> withPolicies(previous, grafted));
            }
        }
        assertEquals(seventhFile, WorldWebApp.tenCompanyJournal(dir));
        assertArrayEquals(sixthBytes, Files.readAllBytes(latest));
    }

    @Test void eighthVersionAddsOnlyReadablePeriodMaterialsAndBranchesKeepTheirOpeningPolicy() throws Exception {
        Path eighthFile = dir.resolve("world-ten-company-autonomy-v8.journal");
        Path seventhFile = dir.resolve("world-ten-company-autonomy-v7.journal");
        try (var seventh = ComposedIndustryScenario.openInterviewTenCompanyStudy(seventhFile)) {
            assertFalse(seventh.profile().manifest().dataVersions().containsKey(RunManifest.PERIODIC_MEETING_VIEW));
        }
        byte[] seventhBytes = Files.readAllBytes(seventhFile);
        assertEquals(seventhFile, WorldWebApp.tenCompanyJournal(dir));
        var branches = new BranchRepository(dir.resolve("runs"));
        String childId = "meeting-material-child";
        try (var prior = ComposedIndustryScenario.openMeetingTenCompanyStudy(seventhFile);
             var eighth = ComposedIndustryScenario.openMeetingTenCompanyStudy(eighthFile)) {
            var old = prior.profile().manifest();
            var current = eighth.profile().manifest();
            assertEquals(RunManifest.INTERVIEW_TEN_COMPANY_RUN_ID, old.runId());
            assertEquals(RunManifest.MEETING_TEN_COMPANY_RUN_ID, current.runId());
            var expected = new TreeMap<>(old.dataVersions());
            expected.put("branch-profile-run", current.runId());
            expected.put(RunManifest.PERIODIC_MEETING_VIEW, RunManifest.READABLE_PERIOD_MATERIAL);
            assertEquals(expected, current.dataVersions());
            assertEquals(prior.openingRegistry(), eighth.openingRegistry());
            assertEquals(prior.snapshot().accounts(), eighth.snapshot().accounts());
            expected.remove(RunManifest.PERIODIC_MEETING_VIEW);
            assertThrows(IllegalArgumentException.class, () -> withPolicies(current, expected));
            expected.put(RunManifest.PERIODIC_MEETING_VIEW, "invalid");
            assertThrows(IllegalArgumentException.class, () -> withPolicies(current, expected));
            var grafted = new TreeMap<>(old.dataVersions());
            grafted.put(RunManifest.PERIODIC_MEETING_VIEW, RunManifest.READABLE_PERIOD_MATERIAL);
            assertThrows(IllegalArgumentException.class, () -> withPolicies(old, grafted));
            eighth.createBranch(childId, eighth.snapshot().position(), BranchOrigin.Kind.OPERATOR_BRANCH, "期末材料");
        }
        assertArrayEquals(seventhBytes, Files.readAllBytes(seventhFile));
        assertEquals(eighthFile, WorldWebApp.tenCompanyJournal(dir));
        byte[] eighthBytes = Files.readAllBytes(eighthFile);
        try (var recovered = ComposedIndustryScenario.openMeetingTenCompanyStudy(eighthFile);
             var child = branches.open(childId, recovered.profile())) {
            assertEquals(childId, child.profile().manifest().runId());
            assertEquals(RunManifest.MEETING_TEN_COMPANY_RUN_ID, child.profile().manifest().policyRunId());
            assertEquals(recovered.profile().manifest().dataVersions(), child.profile().manifest().dataVersions());
        }
        assertArrayEquals(eighthBytes, Files.readAllBytes(eighthFile));
    }

    @Test void digitalCessationIsFrozenOnlyForNewWorldsAndTheirBranches() throws Exception {
        Path ninth = dir.resolve("world-ten-company-autonomy-v9.journal");
        Path eighth = dir.resolve("world-ten-company-autonomy-v8.journal");
        try (var prior = ComposedIndustryScenario.openMeetingTenCompanyStudy(eighth)) {
            assertEquals(RunManifest.MEETING_TEN_COMPANY_RUN_ID, prior.profile().manifest().runId());
        }
        byte[] archived = Files.readAllBytes(eighth);
        try (var prior = ComposedIndustryScenario.openDigitalWindDownTenCompanyStudy(WorldWebApp.tenCompanyJournal(dir));
             var world = ComposedIndustryScenario.openDigitalWindDownTenCompanyStudy(ninth)) {
            var old = prior.profile().manifest();
            var current = world.profile().manifest();
            assertEquals(RunManifest.DIGITAL_WIND_DOWN_RUN_ID, current.runId());
            var expected = new TreeMap<>(old.dataVersions());
            expected.put("branch-profile-run", current.runId());
            expected.put(RunManifest.DIGITAL_CESSATION, RunManifest.BOARD_DIGITAL_WIND_DOWN);
            assertEquals(expected, current.dataVersions());
            assertEquals(prior.openingRegistry(), world.openingRegistry());
            assertEquals(prior.snapshot().accounts(), world.snapshot().accounts());
            expected.remove(RunManifest.DIGITAL_CESSATION);
            assertThrows(IllegalArgumentException.class, () -> withPolicies(current, expected));
            var grafted = new TreeMap<>(old.dataVersions());
            grafted.put(RunManifest.DIGITAL_CESSATION, RunManifest.BOARD_DIGITAL_WIND_DOWN);
            assertThrows(IllegalArgumentException.class, () -> withPolicies(old, grafted));
            world.createBranch("digital-cessation-child", world.snapshot().position(), BranchOrigin.Kind.OPERATOR_BRANCH, "数字公司停接");
        }
        assertArrayEquals(archived, Files.readAllBytes(eighth));
        assertEquals(ninth, WorldWebApp.tenCompanyJournal(dir));
        var branches = new BranchRepository(dir.resolve("runs"));
        try (var reopened = ComposedIndustryScenario.openDigitalWindDownTenCompanyStudy(ninth);
             var child = branches.open("digital-cessation-child", reopened.profile())) {
            assertEquals(RunManifest.DIGITAL_WIND_DOWN_RUN_ID, child.profile().manifest().policyRunId());
            assertEquals(reopened.profile().manifest().dataVersions(), child.profile().manifest().dataVersions());
        }
    }

    @Test void newFeedbackRulesBelongOnlyToTheNewOpeningAndItsBranches() throws Exception {
        Path newest = dir.resolve("world-ten-company-autonomy-v10.journal");
        Path ninth = dir.resolve("world-ten-company-autonomy-v9.journal");
        try (var prior = ComposedIndustryScenario.openDigitalWindDownTenCompanyStudy(ninth)) {
            assertEquals(RunManifest.DIGITAL_WIND_DOWN_RUN_ID, prior.profile().manifest().runId());
        }
        byte[] archived = Files.readAllBytes(ninth);
        try (var prior = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(WorldWebApp.tenCompanyJournal(dir));
             var next = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(newest)) {
            var old = prior.profile().manifest();
            var current = next.profile().manifest();
            assertEquals(RunManifest.EDITORIAL_FEEDBACK_RUN_ID, current.runId());
            var expected = new TreeMap<>(old.dataVersions());
            expected.put("branch-profile-run", current.runId());
            expected.put(RunManifest.EDITORIAL_REVIEW_FEEDBACK, RunManifest.PRIVATE_REVISION_READING);
            expected.put(RunManifest.INTERVIEW_WINDOW_BASIS, RunManifest.EXECUTION_CLOCK_WINDOW);
            expected.put(RunManifest.PERSON_TAKEOVER_POLICY, RunManifest.MARKED_TAKEOVER_BRANCH);
            assertEquals(expected, current.dataVersions());
            assertEquals(prior.openingRegistry(), next.openingRegistry());
            assertEquals(prior.snapshot().accounts(), next.snapshot().accounts());
            for (String key : java.util.List.of(RunManifest.EDITORIAL_REVIEW_FEEDBACK, RunManifest.INTERVIEW_WINDOW_BASIS,
                    RunManifest.PERSON_TAKEOVER_POLICY)) {
                var missing = new TreeMap<>(expected);
                missing.remove(key);
                assertThrows(IllegalArgumentException.class, () -> withPolicies(current, missing));
                var grafted = new TreeMap<>(old.dataVersions());
                grafted.put(key, expected.get(key));
                assertThrows(IllegalArgumentException.class, () -> withPolicies(old, grafted));
            }
            next.createBranch("editorial-child", next.snapshot().position(), BranchOrigin.Kind.OPERATOR_BRANCH, "采编反馈");
        }
        assertArrayEquals(archived, Files.readAllBytes(ninth));
        assertEquals(newest, WorldWebApp.tenCompanyJournal(dir));
        try (var restored = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(newest);
             var child = new BranchRepository(dir.resolve("runs")).open("editorial-child", restored.profile())) {
            assertEquals(RunManifest.EDITORIAL_FEEDBACK_RUN_ID, child.profile().manifest().policyRunId());
            assertEquals(restored.profile().manifest().dataVersions(), child.profile().manifest().dataVersions());
        }
    }

    @Test void personalNewsPoliciesAreFrozenForTheNewOpeningAndInheritedByBranches() throws Exception {
        Path tenth = dir.resolve("world-ten-company-autonomy-v10.journal");
        Path newest = dir.resolve("world-ten-company-autonomy-v11.journal");
        try (var previous = ComposedIndustryScenario.openEditorialFeedbackTenCompanyStudy(tenth)) {
            assertEquals(RunManifest.EDITORIAL_FEEDBACK_RUN_ID, previous.profile().manifest().runId());
        }
        byte[] saved = Files.readAllBytes(tenth);
        assertEquals(tenth, WorldWebApp.tenCompanyJournal(dir));
        try (var previous = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(tenth);
             var world = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(newest)) {
            var old = previous.profile().manifest();
            var current = world.profile().manifest();
            var expected = new TreeMap<>(old.dataVersions());
            expected.put("branch-profile-run", current.runId());
            var additions = Map.of(RunManifest.PERSONAL_NEWS_RANKING, RunManifest.VISIBLE_INTEREST_RANKING,
                    RunManifest.NEWS_EXPLORATION_SLOT, "4",
                    RunManifest.MARKET_SESSION_WAKE, RunManifest.ROLE_EXPOSURE_CHOICE,
                    RunManifest.MEDIA_SUBSCRIPTIONS, RunManifest.CONSENTED_INTERVIEW_DELIVERY,
                    RunManifest.CROSS_BORDER_EDITING, RunManifest.DUAL_CLOCK_EDITING,
                    RunManifest.PHARMA_LICENSING, RunManifest.APPROVED_USE_CONTRACT,
                    RunManifest.HYDRO_INFLOW_PATH, RunManifest.FINITE_1080_DAYS);
            expected.putAll(additions);
            assertEquals(RunManifest.PERSONAL_NEWS_RUN_ID, current.runId());
            assertEquals(expected, current.dataVersions());
            assertEquals(previous.openingRegistry(), world.openingRegistry());
            assertEquals(previous.snapshot().accounts(), world.snapshot().accounts());
            for (var key : additions.keySet()) {
                var missing = new TreeMap<>(expected);
                missing.remove(key);
                assertThrows(IllegalArgumentException.class, () -> withPolicies(current, missing));
                var grafted = new TreeMap<>(old.dataVersions());
                grafted.put(key, expected.get(key));
                assertThrows(IllegalArgumentException.class, () -> withPolicies(old, grafted));
            }
            world.createBranch("news-child", world.snapshot().position(), BranchOrigin.Kind.OPERATOR_BRANCH, "个人新闻研究");
        }
        assertArrayEquals(saved, Files.readAllBytes(tenth));
        assertEquals(newest, WorldWebApp.tenCompanyJournal(dir));
        try (var restored = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(newest);
             var child = new BranchRepository(dir.resolve("runs")).open("news-child", restored.profile())) {
            assertEquals(RunManifest.PERSONAL_NEWS_RUN_ID, child.profile().manifest().policyRunId());
            assertEquals(restored.profile().manifest().dataVersions(), child.profile().manifest().dataVersions());
        }
    }

    @Test void activeInterviewDecisionsAreFrozenOnlyForTheNewWorldAndItsBranches() throws Exception {
        Path current = dir.resolve("world-ten-company-autonomy-v12.journal");
        Path previous = dir.resolve("world-ten-company-autonomy-v11.journal");
        try (var old = ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(previous)) {
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.INTERVIEW_AUTONOMY_POLICY));
        }
        verifyVersionedPolicy(current, previous, RunManifest.INTERVIEW_AUTONOMY_RUN_ID,
                RunManifest.INTERVIEW_AUTONOMY_POLICY, RunManifest.INTERVIEW_AUTONOMY_VERSION);
    }

    @Test void issuerDecisionsAreFrozenOnlyForTheNewWorldAndItsBranches() throws Exception {
        Path current = dir.resolve("world-ten-company-autonomy-v13.journal");
        Path previous = dir.resolve("world-ten-company-autonomy-v12.journal");
        try (var old = ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(previous)) {
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.ISSUER_DECISIONS_POLICY));
        }
        verifyVersionedPolicy(current, previous, RunManifest.ISSUER_DECISIONS_RUN_ID,
                RunManifest.ISSUER_DECISIONS_POLICY, RunManifest.ISSUER_DECISIONS_VERSION);
    }

    @Test void publishedStoryAndClaimFollowupsAreFrozenOnTheNewOpening() throws Exception {
        var current = dir.resolve("world-ten-company-autonomy-v14.journal");
        var previous = dir.resolve("world-ten-company-autonomy-v13.journal");
        try (var old = ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(previous)) {
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.MEDIA_WITHDRAWAL_POLICY));
        }
        verifyVersionedPolicy(current, previous, RunManifest.MEDIA_WITHDRAWAL_RUN_ID,
                RunManifest.MEDIA_WITHDRAWAL_POLICY, RunManifest.MEDIA_WITHDRAWAL_VERSION);
    }

    @Test void ordinaryLoanLossAndTradingWindowsBelongOnlyToTheNewVersion() throws Exception {
        var current = dir.resolve("world-ten-company-autonomy-v15.journal");
        var previous = dir.resolve("world-ten-company-autonomy-v14.journal");
        try (var old = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(previous)) {
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.BANK_LOAN_LOSS_POLICY));
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.MATERIAL_TRADING_POLICY));
        }
        verifyVersionedPolicy(current, previous, RunManifest.BANK_LOAN_LOSS_RUN_ID,
                RunManifest.BANK_LOAN_LOSS_POLICY, RunManifest.BANK_LOAN_LOSS_VERSION);
    }

    @Test void personalBeliefContractBelongsOnlyToTheNewOpening() throws Exception {
        var current = dir.resolve("world-ten-company-autonomy-v16.journal");
        var previous = dir.resolve("world-ten-company-autonomy-v15.journal");
        try (var old = ComposedIndustryScenario.openLoanLossTenCompanyStudy(previous)) {
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.PERSONAL_BELIEF_POLICY));
        }
        verifyVersionedPolicy(current, previous, RunManifest.PERSONAL_BELIEF_RUN_ID,
                RunManifest.PERSONAL_BELIEF_POLICY, RunManifest.PERSONAL_BELIEF_VERSION);
    }

    @Test void interviewTimeContractBelongsOnlyToTheNewOpening() throws Exception {
        var current = dir.resolve("world-ten-company-autonomy-v17.journal");
        var previous = dir.resolve("world-ten-company-autonomy-v16.journal");
        try (var old = ComposedIndustryScenario.openPersonalBeliefTenCompanyStudy(previous)) {
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.INTERVIEW_TIME_POLICY));
        }
        verifyVersionedPolicy(current, previous, RunManifest.INTERVIEW_TIME_RUN_ID,
                RunManifest.INTERVIEW_TIME_POLICY, RunManifest.INTERVIEW_TIME_VERSION);
    }

    @Test void dividendAmendmentsBelongOnlyToTheNewOpening() throws Exception {
        var current = dir.resolve("world-ten-company-autonomy-v18.journal");
        var previous = dir.resolve("world-ten-company-autonomy-v17.journal");
        try (var old = ComposedIndustryScenario.openInterviewTimeTenCompanyStudy(previous)) {
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.DIVIDEND_AMENDMENT_POLICY));
        }
        verifyVersionedPolicy(current, previous, RunManifest.DIVIDEND_AMENDMENT_RUN_ID,
                RunManifest.DIVIDEND_AMENDMENT_POLICY, RunManifest.DIVIDEND_AMENDMENT_VERSION);
    }

    @Test void onsiteDecisionClockIsFrozenForNewWorldsAndTheirBranches() throws Exception {
        var current = dir.resolve("world-ten-company-autonomy-v19.journal");
        var previous = dir.resolve("world-ten-company-autonomy-v18.journal");
        try (var old = ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(previous)) {
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.ONSITE_DECISION_CLOCK_POLICY));
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.DISCOVERY_CATALOG_POLICY));
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.PARTICIPANT_HISTORY_POLICY));
        }
        verifyVersionedPolicy(current, previous, RunManifest.ONSITE_DECISION_CLOCK_RUN_ID,
                RunManifest.ONSITE_DECISION_CLOCK_POLICY, RunManifest.ONSITE_DECISION_CLOCK_VERSION);
    }

    @Test void crisisOperationsDeclareTheirArgumentsWithoutChangingExistingWorlds() throws Exception {
        var current = WorldWebApp.tenCompanyJournal(dir);
        assertEquals(dir.resolve("world-ten-company-autonomy-v20.journal"), current);
        var previous = dir.resolve("world-ten-company-autonomy-v19.journal");
        try (var old = ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(previous)) {
            assertFalse(old.profile().manifest().dataVersions().containsKey(RunManifest.CRISIS_ARGUMENTS_POLICY));
        }
        verifyVersionedPolicy(current, previous, RunManifest.CRISIS_ARGUMENTS_STUDY_RUN_ID,
                RunManifest.CRISIS_ARGUMENTS_POLICY, RunManifest.CRISIS_ARGUMENTS_VERSION);
    }

    private void verifyVersionedPolicy(Path current, Path previous, String expectedId, String key, String value) throws Exception {
        assertEquals(previous, WorldWebApp.tenCompanyJournal(dir));
        byte[] saved = Files.readAllBytes(previous);
        if (RunManifest.INTERVIEW_AUTONOMY_RUN_ID.equals(expectedId)) {
            try (var created = ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(current)) {
                assertEquals(expectedId, created.profile().manifest().runId());
            }
        }
        if (RunManifest.ISSUER_DECISIONS_RUN_ID.equals(expectedId)) {
            try (var created = ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(current)) {
                assertEquals(expectedId, created.profile().manifest().runId());
            }
        }
        if (RunManifest.MEDIA_WITHDRAWAL_RUN_ID.equals(expectedId)) {
            try (var created = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(current)) {
                assertEquals(expectedId, created.profile().manifest().runId());
            }
        }
        if (RunManifest.BANK_LOAN_LOSS_RUN_ID.equals(expectedId)) {
            try (var created = ComposedIndustryScenario.openLoanLossTenCompanyStudy(current)) {
                assertEquals(expectedId, created.profile().manifest().runId());
            }
        }
        if (RunManifest.PERSONAL_BELIEF_RUN_ID.equals(expectedId)) {
            try (var created = ComposedIndustryScenario.openPersonalBeliefTenCompanyStudy(current)) {
                assertEquals(expectedId, created.profile().manifest().runId());
            }
        }
        if (RunManifest.INTERVIEW_TIME_RUN_ID.equals(expectedId)) {
            try (var created = ComposedIndustryScenario.openInterviewTimeTenCompanyStudy(current)) {
                assertEquals(expectedId, created.profile().manifest().runId());
            }
        }
        if (RunManifest.DIVIDEND_AMENDMENT_RUN_ID.equals(expectedId)) {
            try (var created = ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(current)) {
                assertEquals(expectedId, created.profile().manifest().runId());
            }
        }
        if (RunManifest.ONSITE_DECISION_CLOCK_RUN_ID.equals(expectedId)) {
            try (var created = ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(current)) {
                assertEquals(expectedId, created.profile().manifest().runId());
            }
        }
        try (var old = ComposedIndustryScenario.openCrisisArgumentsTenCompanyStudy(previous);
             var world = ComposedIndustryScenario.openCrisisArgumentsTenCompanyStudy(current)) {
            var manifest = world.profile().manifest();
            var oldManifest = old.profile().manifest();
            assertEquals(expectedId, manifest.runId());
            var expected = new TreeMap<>(oldManifest.dataVersions());
            expected.put("branch-profile-run", manifest.runId());
            expected.put(key, value);
            if (RunManifest.CRISIS_ARGUMENTS_STUDY_RUN_ID.equals(expectedId)) {
                expected.put(RunManifest.BOARD_RESEARCH_ARGUMENTS_POLICY, RunManifest.BOARD_RESEARCH_ARGUMENTS_VERSION);
                var missing = new TreeMap<>(expected);
                missing.remove(RunManifest.BOARD_RESEARCH_ARGUMENTS_POLICY);
                assertThrows(IllegalArgumentException.class, () -> withPolicies(manifest, missing));
            }
            if (RunManifest.ONSITE_DECISION_CLOCK_RUN_ID.equals(expectedId)) {
                expected.put(RunManifest.DISCOVERY_CATALOG_POLICY, RunManifest.DISCOVERY_CATALOG_VERSION);
                expected.put(RunManifest.PARTICIPANT_HISTORY_POLICY, RunManifest.PARTICIPANT_HISTORY_VERSION);
                for (String policy : java.util.List.of(RunManifest.DISCOVERY_CATALOG_POLICY, RunManifest.PARTICIPANT_HISTORY_POLICY)) {
                    var invalid = new TreeMap<>(expected);
                    invalid.remove(policy);
                    assertThrows(IllegalArgumentException.class, () -> withPolicies(manifest, invalid));
                }
            }
            if (RunManifest.ISSUER_DECISIONS_RUN_ID.equals(expectedId)) {
                expected.put(edu.cufe.auction.world.PersonalMarket.MARKET_POLICY, edu.cufe.auction.world.PersonalMarket.MARKET_VERSION);
                var withoutMarket = new TreeMap<>(expected);
                withoutMarket.remove(edu.cufe.auction.world.PersonalMarket.MARKET_POLICY);
                assertThrows(IllegalArgumentException.class, () -> withPolicies(manifest, withoutMarket));
            }
            if (RunManifest.BANK_LOAN_LOSS_RUN_ID.equals(expectedId)) {
                expected.put(RunManifest.MATERIAL_TRADING_POLICY, RunManifest.MATERIAL_TRADING_VERSION);
                expected.put(RunManifest.CUSTOMER_PERSONAL_TOOLS, RunManifest.PERSONAL_COMMUNICATION);
                for (var added : java.util.List.of(RunManifest.MATERIAL_TRADING_POLICY, RunManifest.CUSTOMER_PERSONAL_TOOLS)) {
                    var missing = new TreeMap<>(expected);
                    missing.remove(added);
                    assertThrows(IllegalArgumentException.class, () -> withPolicies(manifest, missing));
                }
            }
            if (RunManifest.MEDIA_WITHDRAWAL_RUN_ID.equals(expectedId)) {
                expected.put(RunManifest.MEDIA_WORK_CALENDAR, RunManifest.FINITE_BUSINESS_SHIFTS);
                expected.put(RunManifest.PERSONAL_ATTENTION_POLICY, RunManifest.PERSONAL_ATTENTION_VERSION);
                expected.put(RunManifest.DIVIDEND_CLAIMS_POLICY, RunManifest.DIVIDEND_CLAIMS_VERSION);
                for (String added : java.util.List.of(RunManifest.MEDIA_WORK_CALENDAR, RunManifest.DIVIDEND_CLAIMS_POLICY, RunManifest.PERSONAL_ATTENTION_POLICY)) {
                    var missing = new TreeMap<>(expected);
                    missing.remove(added);
                    assertThrows(IllegalArgumentException.class, () -> withPolicies(manifest, missing));
                }
            }
            assertEquals(expected, manifest.dataVersions());
            assertEquals(old.openingRegistry(), world.openingRegistry());
            assertEquals(old.snapshot().accounts(), world.snapshot().accounts());
            expected.remove(key);
            assertThrows(IllegalArgumentException.class, () -> withPolicies(manifest, expected));
            expected.put("branch-profile-run", oldManifest.runId());
            expected.put(key, value);
            assertThrows(IllegalArgumentException.class, () -> withPolicies(oldManifest, expected));
            world.createBranch("policy-child", world.snapshot().position(), BranchOrigin.Kind.OPERATOR_BRANCH, "开局政策");
        }
        assertArrayEquals(saved, Files.readAllBytes(previous));
        assertEquals(current, WorldWebApp.tenCompanyJournal(dir));
        try (var restored = ComposedIndustryScenario.openCrisisArgumentsTenCompanyStudy(current);
             var child = new BranchRepository(dir.resolve("runs")).open("policy-child", restored.profile())) {
            assertEquals(expectedId, child.profile().manifest().policyRunId());
            assertEquals(restored.profile().manifest().dataVersions(), child.profile().manifest().dataVersions());
        }
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
