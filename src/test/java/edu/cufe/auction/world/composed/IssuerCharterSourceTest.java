package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.MediaModel;
import edu.cufe.auction.world.MediaRun;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.WorldJournalScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static edu.cufe.auction.world.RoleTradingView.TOOL_MEDIA_PROPOSE;
import static edu.cufe.auction.world.RoleTradingView.TOOL_MEDIA_SUBMIT;
import static org.junit.jupiter.api.Assertions.*;

class IssuerCharterSourceTest {
    @TempDir Path directory;
    private final ComposedEditorialChainTest editorial = new ComposedEditorialChainTest();

    private MediaRun.Report submit(WorldRuntime world, String topic, JsonNode one, JsonNode two) throws Exception {
        return editorial.result(world, "sam", TOOL_MEDIA_SUBMIT, Map.of("storyId", topic, "expectedRevision", 0,
                "title", "章程来源比较", "body", "分别核对签发时的原始材料。", "publicSourceDescription", "按已读版本核验",
                "evidence", List.of(one, two)));
    }

    @Test void publishedChartersOfTwoIssuersRemainSeparateEvenWhenTheChairAndFilerAreTheSame() throws Exception {
        var file = directory.resolve("sources.journal");
        String eng = CompanyOpeningDocuments.documentId("eng-a"), bank = CompanyOpeningDocuments.documentId("north");
        String topic;
        try (var world = ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(file)) {
            world.advanceTo(32_400_000);
            String wire = editorial.publish(world, "rita", "ed", "bea", "WIRE", List.of(eng), "工程章程", "工程董事会登记。");
            String local = editorial.publish(world, "hydro-manager", "resource-manager", "bea", "LOCAL",
                    List.of(bank), "银行章程", "银行董事会登记。");
            String copy = editorial.publish(world, "nina", "omar", "bea", "AGGREGATOR", List.of(wire),
                    "通讯稿摘编", "转引原来的工程章程。");
            var proposed = editorial.result(world, "sam", TOOL_MEDIA_PROPOSE,
                    Map.of("storyId", "compare", "template", "INVESTIGATIVE", "topic", "调查两家公司"));
            assertTrue(proposed.accepted()); topic = proposed.storyId();
            var wireRef = editorial.read(world, "sam", wire);
            var sameRef = editorial.read(world, "sam", copy);
            assertEquals(MediaModel.REASON_INSUFFICIENT_SOURCES, submit(world, topic, wireRef, sameRef).reason());
            var independent = submit(world, topic, wireRef, editorial.read(world, "sam", local));
            assertTrue(independent.accepted(), independent.reason());
            for (var id : List.of(eng,bank)) {
                var original = world.snapshot().information().orElseThrow().information().documents().stream()
                        .filter(doc -> doc.docId().equals(id)).findFirst().orElseThrow();
                assertEquals("investor", original.publisherPartyId());
                assertEquals(InformationModel.AudienceKind.PUBLIC, original.audience().kind());
            }
        }
        byte[] saved = Files.readAllBytes(file);
        try (var reopened = ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(file)) {
            var item = reopened.snapshot().media().orElseThrow().stateOf("investigative").stories().stream()
                    .filter(row -> row.storyId().equals(topic)).findFirst().orElseThrow();
            assertEquals(2, item.sourceGroups());
            assertEquals(List.of(bank,eng).stream().sorted().toList(), item.roots().stream().map(MediaModel.Ref::docId).sorted().toList());
        }
        assertArrayEquals(saved,Files.readAllBytes(file));
    }

    @Test void exactIssuerIdAndTaskCannotReplaceTheRealOpeningMaterialEvenAtTimeZero() throws Exception {
        String issuer = "eng-a", name = CompanyOpeningDocuments.documentId(issuer);
        var source = directory.resolve("complete-opening.journal");
        InformationModel.DocumentVersion charter;
        try (var world = ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(source)) {
            charter = world.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(doc -> name.equals(doc.docId())).findFirst().orElseThrow().at(1);
        }
        var history = WorldJournalScanner.scan(source);
        var order = history.commits().stream().filter(c -> c.commandId().equals("cmd:opening-note:" + issuer))
                .findFirst().orElseThrow();
        Path prefix = Files.copy(source, directory.resolve("before-charter.journal"));
        try (var file = FileChannel.open(prefix, StandardOpenOption.WRITE)) {
            file.truncate(history.endOffsetOf(order.position() - 1).orElseThrow());
        }
        try (var world = ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(prefix)) {
            assertEquals(0, world.snapshot().timeMillis());
            long position = world.snapshot().position();
            assertThrows(IllegalArgumentException.class, () -> world.schedulePublishDocument("opening-note:" + issuer,
                    "opening-note-task:" + issuer, 1, name, "investor", InformationModel.Audience.publicAudience(),
                    charter.title(), "代换后的席位", charter.publicSourceDescription(), CompanyOpeningDocuments.READ_MILLIS,
                    charter.internalSourceIds()));
            assertEquals(position, world.snapshot().position());
            world.schedulePublishDocument("opening-note:" + issuer, "opening-note-task:" + issuer, 1, name,
                    "investor", InformationModel.Audience.publicAudience(), charter.title(), charter.body(),
                    charter.publicSourceDescription(), CompanyOpeningDocuments.READ_MILLIS, charter.internalSourceIds());
            world.advanceTo(1);
            var receipt = world.snapshot().information().orElseThrow().report("opening-note-task:" + issuer);
            assertTrue(receipt.accepted());
            assertEquals(name, receipt.subjectId());
            long completed = world.snapshot().position();
            world.schedulePublishDocument("opening-note:" + issuer, "opening-note-task:" + issuer, 1, name,
                    "investor", InformationModel.Audience.publicAudience(), charter.title(), charter.body(),
                    charter.publicSourceDescription(), CompanyOpeningDocuments.READ_MILLIS, charter.internalSourceIds());
            assertEquals(completed, world.snapshot().position());

        }
    }

    @Test void copiedLabelsAndLaterVersionsCannotSpeakForTheOpeningIssuer() throws Exception {
        var file = directory.resolve("versions.journal");
        String charter = CompanyOpeningDocuments.documentId("eng-a");
        String lookalike = CompanyOpeningDocuments.documentId("eng-a:copy");
        try (var world = ComposedIndustryScenario.openEditorialSourceTenCompanyStudy(file)) {
            world.advanceTo(32_400_000);
            var original = world.snapshot().information().orElseThrow().information().documents().stream()
                    .filter(doc -> doc.docId().equals(charter)).findFirst().orElseThrow().at(1);
            assertThrows(IllegalArgumentException.class, () -> world.schedulePublishDocument(
                    "other", "other", 32_400_001, charter, "investor", InformationModel.Audience.publicAudience(),
                    original.title(), original.body(), original.publicSourceDescription(), 1000, List.of()));
            world.schedulePublishDocument("ordinary-copy", "ordinary-copy", 32_400_001, lookalike, "investor",
                    InformationModel.Audience.publicAudience(), original.title(), original.body(),
                    original.publicSourceDescription(), 1000, List.of("issuer:eng-a"));
            world.schedulePublishDocumentVersion("later", "later", 32_400_002, charter,
                    original.title(), original.body(), original.publicSourceDescription(), 1000, List.of("issuer:eng-a"));
            world.schedulePublishDocument("holder-note", "holder-note", 32_400_003, "holder-note", "investor",
                    InformationModel.Audience.publicAudience(), "持有人自述", "我亲自参与了这次登记。",
                    "持有人本人的独立说明", 1000, List.of());
            world.advanceTo(32_400_003);
            var proposed = editorial.result(world, "sam", TOOL_MEDIA_PROPOSE,
                    Map.of("storyId", "copies", "template", "INVESTIGATIVE", "topic", "区分经办人和机构"));
            assertTrue(proposed.accepted());
            var fake = editorial.read(world, "sam", lookalike);
            var later = editorial.read(world, "sam", charter, 2);
            assertEquals(MediaModel.REASON_INSUFFICIENT_SOURCES, submit(world, proposed.storyId(), fake, later).reason());
            var first = editorial.read(world, "sam", charter, 1);
            assertEquals(MediaModel.REASON_INSUFFICIENT_SOURCES, submit(world, proposed.storyId(), first, fake).reason());
            assertEquals(MediaModel.REASON_INSUFFICIENT_SOURCES, submit(world, proposed.storyId(), first, later).reason());
            var independent = submit(world, proposed.storyId(), first, editorial.read(world, "sam", "holder-note"));
            assertTrue(independent.accepted(), independent.reason());
        }
    }
}
