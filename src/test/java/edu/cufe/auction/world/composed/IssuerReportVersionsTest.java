package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.world.EngineeringRun;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.PeriodicModel;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.TECH;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.TECH_DIRECTORS;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.TECH_MANAGER;
import static org.junit.jupiter.api.Assertions.*;

class IssuerReportVersionsTest {
    @TempDir Path directory;
    private static final List<String> REVIEWERS = TECH_DIRECTORS.subList(0, 3);
    private static final String INVESTOR = "investor";
    private static final String NOTE = "补充说明：本表收入以本期期末确认金额为准。";

    @Test void issuerPublishesAndCorrectsTheSamePeriodAfterSeparateReviews() throws Exception {
        Path journal = directory.resolve("issuer.journal");
        WorldRuntime.Snapshot finalState;
        InformationModel.DocumentVersion original;
        String publicId;
        try (var world = ComposedIndustryScenario.openIssuerReportOperating(journal)) {
            openReportingWindow(world);
            var statement = world.confirmedPeriodStatement(TECH, 1);

            assertEquals("OK", act(world, TECH_MANAGER, "PREPARE", null, "", "", ""));
            var draft = periods(world).drafts().get(0);
            assertEquals(TECH_MANAGER, draft.preparedBy());
            assertEquals(statement.body(), version(world, draft.docId(), draft.version()).body());
            assertSource(version(world, draft.docId(), draft.version()).publicSourceDescription(), TECH_MANAGER);
            assertFalse(world.autonomyRoleObservation(INVESTOR).at("/profile/catalog")
                    .findValuesAsText("docId").contains(draft.docId()));

            String firstPublisher = REVIEWERS.get(0);
            var first = reviewAndPublish(world, draft, firstPublisher);
            publicId = periodInput(world, TECH_MANAGER).required("publicDocId").asText();
            assertEquals("company-statement:" + TECH + ":1", publicId);
            assertEquals(1L, first.version());
            original = version(world, publicId, 1);
            assertEquals(statement.body(), original.body());
            assertSource(original.publicSourceDescription(), firstPublisher);
            for (String actor : List.of(TECH_MANAGER, firstPublisher)) {
                var row = periodInput(world, actor);
                assertEquals(publicId, row.required("publicDocId").asText());
                assertEquals(1, row.required("publishedVersions").asInt());
            }
            assertFalse(canCite(world, publicId, 1));
            read(world, INVESTOR, publicId, 1);
            assertEquals(original.body(), readExperience(world, 1, publicId).required("body").asText());
            assertSource(readExperience(world, 1, publicId).required("source").asText(), firstPublisher);

            assertEquals("DRAFT_UNCHANGED", act(world, TECH_MANAGER, "PREPARE", null, "", "", ""));
            assertEquals(1, periods(world).drafts().size());
            assertEquals("OK", act(world, TECH_MANAGER, "PREPARE", null, "", "", NOTE));
            var correction = periods(world).drafts().stream().filter(d -> !d.draftId().equals(draft.draftId()))
                    .findFirst().orElseThrow();
            var privateVersion = version(world, correction.docId(), correction.version());
            assertTrue(privateVersion.body().contains(statement.body()));
            assertTrue(privateVersion.body().contains(NOTE));
            assertSource(privateVersion.publicSourceDescription(), TECH_MANAGER);
            for (String director : REVIEWERS) {
                var row = periodInput(world, director);
                assertEquals(correction.draftId(), row.required("myDraft").required("draftId").asText());
                assertFalse(row.required("myApproval").asBoolean(), director);
            }
            assertNotEquals("OK", act(world, firstPublisher, "APPROVE", correction, "", "", ""));
            assertNotEquals("OK", act(world, firstPublisher, "PUBLISH", correction, "",
                    first.authorizationId(), ""));
            assertEquals(1, periods(world).releases().size());

            String nextPublisher = REVIEWERS.get(1);
            var second = reviewAndPublish(world, correction, nextPublisher);
            assertEquals(correction.docId(), second.docId());
            assertEquals(2L, version(world, publicId, 2).version());
            assertNotEquals(first.authorizationId(), second.authorizationId());
            assertEquals(original, version(world, publicId, 1));
            assertEquals(privateVersion.body(), version(world, publicId, 2).body());
            assertSource(version(world, publicId, 2).publicSourceDescription(), nextPublisher);
            assertTrue(canCite(world, publicId, 1));
            assertFalse(canCite(world, publicId, 2));
            assertEquals(2, periodInput(world, nextPublisher).required("publishedVersions").asInt());
            assertEquals(publicId, periodInput(world, TECH_MANAGER).required("publicDocId").asText());
            read(world, INVESTOR, publicId, 2);
            var seen = readExperience(world, 2, publicId);
            assertEquals(privateVersion.body(), seen.required("body").asText());
            assertSource(seen.required("source").asText(), nextPublisher);
            assertTrue(canCite(world, publicId, 2));

            // 公司可以同时保留尚未批准的草稿。第十稿按文档编号排序时会排在第二稿之前。
            for (int i = 3; i <= 10; i++) {
                assertEquals("OK", act(world, TECH_MANAGER, "PREPARE", null, "", "", "补充稿 " + i));
            }
            assertEquals(10, periods(world).drafts().size());
            var tenth = periods(world).drafts().stream()
                    .max(java.util.Comparator.comparingLong(PeriodicModel.Draft::position)).orElseThrow();
            assertEquals(tenth.draftId(), periodInput(world, firstPublisher).required("myDraft")
                    .required("draftId").asText());
            assertFalse(periodInput(world, firstPublisher).required("myApproval").asBoolean());
            var third = reviewAndPublish(world, tenth, firstPublisher);
            assertEquals(tenth.draftId(), third.draftId());
            assertEquals(3, periodInput(world, TECH_MANAGER).required("publishedVersions").asInt());
            assertTrue(version(world, publicId, 3).body().contains("补充稿 10"));
            assertEquals("DRAFT_UNCHANGED", act(world, TECH_MANAGER, "PREPARE", null, "", "", "补充稿 10"));
            assertEquals(10, periods(world).drafts().size());
            finalState = world.snapshot();
        }
        long length = Files.size(journal);
        try (var reopened = ComposedIndustryScenario.openIssuerReportOperating(journal)) {
            assertEquals(finalState, reopened.snapshot());
            assertEquals(original, version(reopened, publicId, 1));
            assertTrue(readExperience(reopened, 2, publicId).required("body").asText().contains(NOTE));
        }
        assertEquals(length, Files.size(journal));
    }

    @Test void webLinksUseActualVersionsAndTheCurrentReadersCompletedCopy() throws Exception {
        Path journal = directory.resolve("public-reports.journal");
        String doc = "company-statement:" + TECH + ":1";
        String firstBody;
        long firstReadAt;
        long firstDuration;
        long correctedDuration;
        try (var world = ComposedIndustryScenario.openIssuerReportOperating(journal)) {
            openReportingWindow(world);
            assertEquals("OK", act(world, TECH_MANAGER, "PREPARE", null, "", "", ""));
            var draft = periods(world).drafts().get(0);
            reviewAndPublish(world, draft, REVIEWERS.get(0));
            read(world, INVESTOR, doc, 1);
            firstReadAt = world.snapshot().timeMillis();
            firstDuration = version(world, doc, 1).readDurationMillis();
            firstBody = version(world, doc, 1).body();
            assertEquals("OK", act(world, TECH_MANAGER, "PREPARE", null, "", "", NOTE));
            var correction = periods(world).drafts().get(1);
            reviewAndPublish(world, correction, REVIEWERS.get(1));
            correctedDuration = version(world, doc, 2).readDurationMillis();
        }
        try (var host = new WorldWebHost(() -> {
            var world = ComposedIndustryScenario.openIssuerReportOperating(journal);
            return new WorldWebHost.Opened(world, world.profile().manifest().runId());
        })) {
            var json = new ObjectMapper();
            var companies = json.valueToTree(host.worldOverview()).required("companies");
            var company = java.util.stream.StreamSupport.stream(companies.spliterator(), false)
                    .filter(c -> TECH.equals(c.required("id").asText())).findFirst().orElseThrow();
            var publications = company.required("publications");
            assertEquals(2, publications.size());
            assertEquals(doc, publications.get(0).required("docId").asText());
            assertEquals(2, publications.get(0).required("version").asInt());
            assertEquals(REVIEWERS.get(1), publications.get(0).required("publisher").asText());
            assertEquals(REVIEWERS.get(0), publications.get(1).required("publisher").asText());
            assertTrue(publications.get(0).required("body").asText().contains(NOTE));
            assertTrue(publications.get(0).required("reads").isEmpty());
            assertEquals(INVESTOR, publications.get(1).required("reads").get(0).required("person").asText());
            var rows = host.personPage(INVESTOR).publicReports();
            assertEquals(2, rows.size());
            assertEquals(doc, rows.get(0).get("docId"));
            assertEquals(2L, rows.get(0).get("version"));
            assertEquals(Long.toString(correctedDuration), rows.get(0).get("readDurationMillis"));
            assertEquals(Long.toString(firstDuration), rows.get(1).get("readDurationMillis"));
            assertEquals(REVIEWERS.get(1), rows.get(0).get("publisherPersonId"));
            assertFalse(rows.get(0).containsKey("body"));
            assertFalse(rows.get(0).containsKey("lastReadAt"));
            assertEquals(firstBody, rows.get(1).get("body"));
            assertEquals(Long.toString(firstReadAt), rows.get(1).get("lastReadAt"));
            var otherReader = host.personPage("nina").publicReports();
            assertEquals(2, otherReader.size());
            for (var row : otherReader) {
                assertEquals(doc, row.get("docId"));
                assertFalse(row.containsKey("lastReadAt"));
                assertFalse(row.containsKey("body"));
            }
        }
    }

    private static void openReportingWindow(WorldRuntime world) throws Exception {
        OperationsFixture.grantBudgetAndAppoint(world, TECH, TECH_DIRECTORS, TECH_MANAGER,
                "issuer", List.of(0L));
        world.advanceTo(PeriodicModel.PERIOD_MILLIS);
        var captured = periods(world).snapshots().get(0);
        var meeting = world.boardOfCompany(TECH).orElseThrow().board().meeting(captured.meetingId());
        for (int i = 0; i < REVIEWERS.size(); i++) {
            String id = "attend-" + i;
            world.scheduleEngineeringAttend(id, id, world.snapshot().timeMillis() + 2_000L + i,
                    TECH, REVIEWERS.get(i), meeting.meetingId(), false);
        }
        world.advanceTo(meeting.opensAt() + 10_000L);
    }

    private static PeriodicModel.Release reviewAndPublish(WorldRuntime world, PeriodicModel.Draft draft,
            String publisher) throws Exception {
        for (int i = 0; i < REVIEWERS.size(); i++) {
            String director = REVIEWERS.get(i);
            read(world, director, draft.docId(), draft.version());
            assertEquals("OK", act(world, director, "APPROVE", draft, "", "", ""));
            assertTrue(periodInput(world, director).required("myApproval").asBoolean());
            if (i == 1) {
                assertNotEquals("OK", act(world, TECH_MANAGER, "AUTHORIZE", draft, publisher, "", ""));
                assertTrue(periods(world).authorizations().stream()
                        .noneMatch(a -> a.draftId().equals(draft.draftId())));
            }
        }
        assertEquals("OK", act(world, TECH_MANAGER, "AUTHORIZE", draft, publisher, "", ""));
        var grant = periods(world).authorizations().stream()
                .filter(a -> a.draftId().equals(draft.draftId())).findFirst().orElseThrow();
        assertEquals(REVIEWERS, grant.approverPersonIds());
        assertEquals(publisher, grant.publisherPersonId());
        assertEquals("OK", act(world, publisher, "PUBLISH", draft, "", grant.authorizationId(), ""));
        var release = periods(world).releases().stream()
                .filter(r -> r.authorizationId().equals(grant.authorizationId())).findFirst().orElseThrow();
        assertEquals(draft.bodyDigest(), release.bodyDigest());
        assertEquals(publisher, release.publisherPersonId());
        return release;
    }

    private static String act(WorldRuntime world, String who, String action, PeriodicModel.Draft draft,
            String publisher, String authorization, String note) throws Exception {
        long due = world.snapshot().timeMillis() + 3_000L;
        String id = action + "-" + due;
        world.schedulePeriodicAction(id, id, due, TECH, who, action, 1,
                draft == null ? "" : draft.draftId(), draft == null ? 0L : draft.version(), publisher,
                publisher.isEmpty() ? 0L : due + OperationsFixture.DAY, authorization, List.of(), note, 2_000L);
        world.advanceTo(due + 1);
        return world.snapshot().engineering().orElseThrow().reports().values().stream()
                .filter(r -> TECH.equals(r.companyPartyId()) && r.timeMillis() == due)
                .max(java.util.Comparator.comparingLong(EngineeringRun.Report::position)).orElseThrow().reason();
    }

    private static void read(WorldRuntime world, String person, String doc, long number) throws Exception {
        long start = world.snapshot().timeMillis() + 100L;
        String id = "read-" + start;
        world.openRoleSession(person).startReading(id, id, start, doc, number);
        world.advanceTo(start + version(world, doc, number).readDurationMillis());
    }

    private static PeriodicModel.CompanyPeriods periods(WorldRuntime world) {
        return world.periodicOf(TECH).orElseThrow();
    }

    private static InformationModel.DocumentVersion version(WorldRuntime world, String doc, long number) {
        return world.snapshot().information().orElseThrow().information().documents().stream()
                .filter(d -> d.docId().equals(doc)).flatMap(d -> d.versions().stream())
                .filter(v -> v.version() == number).findFirst().orElseThrow();
    }

    private static boolean canCite(WorldRuntime world, String doc, long number) {
        return InformationModel.canCite(world.snapshot().information().orElseThrow().information(), INVESTOR,
                doc, number, world.snapshot().timeMillis());
    }

    private static JsonNode readExperience(WorldRuntime world, long number, String doc) throws IOException {
        for (var row : world.autonomyRoleObservation(INVESTOR).at("/profile/readExperiences")) {
            if (doc.equals(row.path("docId").asText()) && number == row.path("version").asLong()) return row;
        }
        throw new AssertionError("投资者没有该版本的已读记录 " + doc + " v" + number);
    }

    private static JsonNode periodInput(WorldRuntime world, String person) throws IOException {
        String role = TECH_MANAGER.equals(person) ? "managerships" : "directorships";
        for (var row : world.autonomyRoleObservation(person).at("/engineering/" + role + "/0/periodic")) {
            if (row.path("period").asLong() == 1) return row;
        }
        throw new AssertionError("本人观察缺少科技公司的第 1 期财报 " + person);
    }

    private static void assertSource(String source, String person) {
        assertTrue(source.contains(TECH), source);
        assertTrue(source.contains(person), source);
    }
}
