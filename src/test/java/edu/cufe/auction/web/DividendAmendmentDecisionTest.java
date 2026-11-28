package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.DividendModel;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.CompanyOpeningDocuments;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static edu.cufe.auction.web.AutonomousIssuerDividendTest.*;
import static org.junit.jupiter.api.Assertions.*;

class DividendAmendmentDecisionTest {
    @TempDir Path dir;
    static final String ISSUER = "eng-a", SCHEME = "div-1", NOTICE = "dividend-public:" + SCHEME;
    static final String AMEND = "issuer.dividend.amend", SIGN = "issuer.dividend.authorize";
    static final List<String> DIRECTORS = List.of("alice", "bob", "carol");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DAY = 86_400_000L;

    static ObjectNode agenda(String meeting) {
        return JSON.createObjectNode().put("companyPartyId", ISSUER).put("meetingId", meeting);
    }

    static void read(WorldRuntime world, String who, String doc, int version, String id) throws Exception {
        choose(world, who, id, "role.reading.start", JSON.createObjectNode().put("docId", doc).put("version", version));
        world.advanceTo(world.roleView(who).readings().get(0).dueAt());
        assertTrue(world.canCite(who, doc, version));
    }

    static void prepareMeeting(WorldRuntime world, String meeting, long start) throws Exception {
        choose(world, "alice", meeting + "-convene", "issuer.dividend.convene", agenda(meeting)
                .put("materialDeadline", start + 120_000).put("opensAt", start + 150_000).put("closesAt", start + 240_000));
        for (String who : DIRECTORS) choose(world, who, meeting + "-attend-" + who, "board.attend", agenda(meeting));
        choose(world, "alice", meeting + "-material", "board.addMaterial", agenda(meeting)
                .put("materialId", "charter").put("sourceRef", CompanyOpeningDocuments.documentId(ISSUER)).put("version", 1));
    }

    static void vote(WorldRuntime world, String meeting, long start, boolean pass) throws Exception {
        world.advanceTo(start + 150_000);
        for (String who : DIRECTORS) choose(world, who, meeting + "-vote-" + who, "board.vote",
                agenda(meeting).put("vote", pass ? "YES" : "NO"));
        world.advanceTo(start + 240_000);
    }

    static void announceOriginal(WorldRuntime world) throws Exception {
        for (String who : DIRECTORS) read(world, who, CompanyOpeningDocuments.documentId(ISSUER), 1, "charter-" + who);
        long start = world.snapshot().timeMillis();
        prepareMeeting(world, "initial", start);
        choose(world, "bob", "proposal", "issuer.dividend.propose", agenda("initial").put("schemeId", SCHEME)
                .put("perShare", "0.01").put("recordAtMillis", DAY + 1000).put("exAtMillis", DAY + 2500)
                .put("payAtMillis", DAY + 4000));
        vote(world, "initial", start, true);
        choose(world, "alice", "initial-sign", SIGN, agenda("initial"));
        world.advanceTo(world.snapshot().timeMillis() + 1);
        assertEquals(DividendModel.Phase.AUTHORIZED, world.snapshot().dividends().orElseThrow().schemes().get(SCHEME).phase());
        assertNotNull(notice(world).at(1));
    }

    static InformationModel.Document notice(WorldRuntime world) {
        return world.snapshot().information().orElseThrow().information().documents().stream()
                .filter(doc -> NOTICE.equals(doc.docId())).findFirst().orElseThrow();
    }

    static ObjectNode change(String meeting, String operation) {
        var args = agenda(meeting).put("schemeId", SCHEME).put("expectedVersion", 1)
                .put("operation", operation).put("reason", "重新核对预计经营支出");
        if ("REPLACE".equals(operation)) args.put("perShare", "0.02").put("recordAtMillis", DAY + 7000)
                .put("exAtMillis", DAY + 8000).put("payAtMillis", DAY + 9000);
        return args;
    }

    @ParameterizedTest @ValueSource(strings = {"CANCEL", "REPLACE", "REJECT"})
    void directorsCanChangeOnlyAnAnnouncedUnregisteredPlan(String outcome) throws Exception {
        Path file = dir.resolve(outcome + ".journal");
        WorldRuntime.Snapshot end;
        try (var world = ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(file)) {
            announceOriginal(world);
            read(world, "investor", NOTICE, 1, "holder-original");
            String v1 = notice(world).at(1).body();
            String account = world.snapshot().engineering().orElseThrow().config().specOf(ISSUER).companyAccountId();
            BigDecimal openingCash = world.snapshot().accounts().get(account).cash();
            long start = world.snapshot().timeMillis();
            prepareMeeting(world, "revision", start);
            String operation = "REJECT".equals(outcome) ? "CANCEL" : outcome;
            choose(world, "bob", "amend", AMEND, change("revision", operation));
            var meeting = world.boardOfCompany(ISSUER).orElseThrow().board().meeting("revision");
            assertEquals(operation, meeting.dividendProposal().request().amendment().operation());
            assertNull(notice(world).at(2));
            vote(world, "revision", start, !"REJECT".equals(outcome));
            if (!"REJECT".equals(outcome)) choose(world, "alice", "sign-revision", SIGN, agenda("revision"));
            world.advanceTo(world.snapshot().timeMillis() + 1);
            assertEquals(v1, notice(world).at(1).body());
            assertFalse(world.canCite("investor", NOTICE, 2));
            if (!"REJECT".equals(outcome)) {
                assertTrue(notice(world).at(2).body().contains("重新核对预计经营支出"));
                read(world, "investor", NOTICE, 2, "holder-revision");
            } else assertNull(notice(world).at(2));
            assertEquals(openingCash, world.snapshot().accounts().get(account).cash());
            world.advanceTo(DAY + 10_000);
            var state = world.snapshot().dividends().orElseThrow();
            var original = state.schemes().get(SCHEME);
            BigDecimal paid = switch (outcome) {
                case "CANCEL" -> new BigDecimal("0.00");
                case "REPLACE" -> new BigDecimal("12.00");
                default -> new BigDecimal("6.00");
            };
            assertEquals("REJECT".equals(outcome) ? DividendModel.Phase.SETTLED : DividendModel.Phase.CANCELLED, original.phase());
            if (original.phase() == DividendModel.Phase.CANCELLED) assertTrue(DividendModel.claims(state, SCHEME).isEmpty());
            assertEquals(paid, openingCash.subtract(world.snapshot().accounts().get(account).cash()));
            assertEquals(paid, state.schemes().keySet().stream().flatMap(id -> DividendModel.claims(state, id).stream())
                    .map(DividendModel.Claim::paid).reduce(new BigDecimal("0.00"), BigDecimal::add));
            assertEquals(0, world.engineeringContext(ISSUER).orElseThrow().unpaidDividends().signum());
            end = world.snapshot();
        }
        byte[] saved = Files.readAllBytes(file);
        try (var world = ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(file)) {
            assertEquals(end, world.snapshot());
        }
        assertArrayEquals(saved, Files.readAllBytes(file));
    }

    @Test void aSecondResolutionUsesTheCurrentSchemeAndKeepsAllThreePublicVersions() throws Exception {
        var file = dir.resolve("second-revision.journal");
        try (var world = ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(file)) {
            announceOriginal(world);
            String scheme = SCHEME;
            for (int version = 1; version <= 2; version++) {
                String meeting = "revision-" + version;
                String operation = version == 1 ? "REPLACE" : "CANCEL";
                long start = world.snapshot().timeMillis();
                prepareMeeting(world, meeting, start);
                var args = change(meeting, operation).put("schemeId", scheme).put("expectedVersion", version);
                choose(world, "bob", "amend-" + version, AMEND, args);
                vote(world, meeting, start, true);
                choose(world, "alice", "sign-" + version, SIGN, agenda(meeting));
                world.advanceTo(world.snapshot().timeMillis() + 1);
                assertEquals(version + 1, notice(world).versions().size());
                var state = world.snapshot().dividends().orElseThrow();
                assertEquals(DividendModel.Phase.CANCELLED, state.schemes().get(scheme).phase());
                if (version == 1) scheme = state.schemes().values().stream()
                        .filter(row -> row.phase() == DividendModel.Phase.AUTHORIZED).findFirst().orElseThrow().schemeId();
            }
            world.advanceTo(DAY + 10_000);
            var state = world.snapshot().dividends().orElseThrow();
            assertTrue(state.schemes().values().stream().allMatch(row -> row.phase() == DividendModel.Phase.CANCELLED));
            assertTrue(state.claims().isEmpty());
        }
        try (var world = ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(file)) {
            assertEquals(3, notice(world).versions().size());
            assertTrue(world.snapshot().dividends().orElseThrow().payments().isEmpty());
        }
    }

    @Test void supersededVersionAndIntradayReplacementDoNotReachTheBallot() throws Exception {
        try (var world = ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(dir.resolve("invalid.journal"))) {
            announceOriginal(world);
            prepareMeeting(world, "correction", world.snapshot().timeMillis());
            var stale = change("correction", "CANCEL").put("expectedVersion", 2);
            var duringSession = change("correction", "REPLACE").put("exAtMillis", DAY + 34_221_000)
                    .put("payAtMillis", DAY + 34_222_000);
            for (var proposal : List.of(stale, duringSession)) {
                String id = "invalid-" + proposal.required("operation").asText();
                var intent = reply(world, call(world, "bob", id), "bob", AMEND, proposal);
                if (intent != null) world.advanceTo(intent.dueMillis());
                assertNull(world.boardOfCompany(ISSUER).orElseThrow().board().meeting("correction").dividendProposal());
                assertNull(notice(world).at(2));
                assertEquals(DividendModel.Phase.AUTHORIZED, world.snapshot().dividends().orElseThrow().schemes().get(SCHEME).phase());
            }
            var original = agenda("correction").put("schemeId", "intraday").put("perShare", "0.01")
                    .put("recordAtMillis", DAY).put("exAtMillis", DAY + 34_221_000).put("payAtMillis", DAY + 34_222_000);
            var intent = reply(world, call(world, "bob", "intraday"), "bob", "issuer.dividend.propose", original);
            if (intent != null) world.advanceTo(intent.dueMillis());
            assertNull(world.boardOfCompany(ISSUER).orElseThrow().board().meeting("correction").dividendProposal());
            assertFalse(world.snapshot().dividends().orElseThrow().schemes().containsKey("intraday"));
        }
    }

    @Test void signatureAtOriginalRecordTimeCannotUndoTheHoldersRights() throws Exception {
        try (var world = ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(dir.resolve("late.journal"))) {
            announceOriginal(world);
            long start = world.snapshot().timeMillis();
            prepareMeeting(world, "late", start);
            choose(world, "bob", "cancel", AMEND, change("late", "CANCEL"));
            vote(world, "late", start, true);
            long record = DAY + 1000;
            world.openRoleSession("alice").issuerDividend("late-sign", "late-sign-task", record,
                    "AUTHORIZE", "late", 0, 0, 0, null);
            world.advanceTo(record);
            assertEquals(DividendModel.Phase.REGISTERED, world.snapshot().dividends().orElseThrow().schemes().get(SCHEME).phase());
            world.advanceTo(DAY + 5000);
            assertEquals(DividendModel.Phase.SETTLED, world.snapshot().dividends().orElseThrow().schemes().get(SCHEME).phase());
            assertNull(notice(world).at(2));
        }
    }

    @Test void outsideShareholderAndOldOpeningCannotSubmitChanges() throws Exception {
        for (boolean old : List.of(true, false)) {
            var path = dir.resolve(old ? "old.journal" : "ordinary.journal");
            try (var world = old ? ComposedIndustryScenario.openInterviewTimeTenCompanyStudy(path)
                    : ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(path)) {
                var who = old ? "bob" : "investor";
                var attempt = call(world, who, "unauthorized");
                assertFalse(attempt.allowedTools().contains(AMEND));
                assertNull(reply(world, attempt, who, AMEND, change("forged", "CANCEL")));
                assertTrue(world.snapshot().dividends().orElseThrow().schemes().isEmpty());
            }
        }
    }
}
