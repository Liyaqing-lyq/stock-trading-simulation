package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.WorldRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ListingDisclosureTest {
    @TempDir Path directory;

    private static String filing(WorldRuntime world, String company) {
        return world.snapshot().information().orElseThrow().information().documents().stream()
                .filter(document -> document.docId().equals(CompanyOpeningDocuments.documentId(company)))
                .findFirst().orElseThrow().versions().get(0).body();
    }

    @Test void publicRegistrationsAreDisclosedByIssuerAndReachOnlyReaders() throws Exception {
        Path file = directory.resolve("listing.journal");
        String issuer = "north";
        String document = CompanyOpeningDocuments.documentId(issuer);
        String expected;
        JsonNode investor;
        try (var world = ComposedIndustryScenario.openCompanyInformationOperating(file)) {
            var register = world.openingRegistry().orElseThrow();
            assertEquals(10, register.securities().size());
            for (var security : register.securities()) {
                String source = filing(world, security.issuerOrganizationId());
                assertTrue(source.contains("代码=" + security.symbol() + "，已发行股数=" + security.issuedShares()
                        + "，每股开局参考估值=" + security.initialReferencePrice().toPlainString() + " 元"));
                assertEquals(1, source.lines().filter(line -> line.startsWith("开局证券登记")).count());
            }
            expected = filing(world, issuer);
            assertFalse(world.autonomyRoleObservation("investor").toString().contains("每股开局参考估值"));
            long start = world.snapshot().timeMillis() + 100;
            world.openRoleSession("investor").startReading("read-north", "read-north-task", start, document, 1);
            world.advanceTo(start + CompanyOpeningDocuments.READ_MILLIS - 1);
            assertFalse(world.autonomyRoleObservation("investor").toString().contains("每股开局参考估值"));
            world.advanceTo(start + CompanyOpeningDocuments.READ_MILLIS);
            investor = world.autonomyRoleObservation("investor");
            assertEquals(expected, investor.at("/profile/readExperiences/0/body").asText());
            assertTrue(InformationModel.canCite(world.snapshot().information().orElseThrow().information(),
                    "investor", document, 1, world.snapshot().timeMillis()));
            assertFalse(world.autonomyRoleObservation("nina").toString().contains("每股开局参考估值"));
            for (var quote : investor.at("/profile/market")) {
                assertTrue(quote.path("lastPrice").isNull());
                assertTrue(quote.path("bestBid").isNull());
                assertTrue(quote.path("bestAsk").isNull());
            }
            assertTrue(world.snapshot().exchange().orElseThrow().orders().isEmpty());
        }
        byte[] original = Files.readAllBytes(file);
        try (var world = ComposedIndustryScenario.openCompanyInformationOperating(file)) {
            assertEquals(investor, world.autonomyRoleObservation("investor"));
            assertEquals(expected, filing(world, issuer));
        }
        assertArrayEquals(original, Files.readAllBytes(file));
        assertThrows(Exception.class, () -> ComposedIndustryScenario.openEngineeringCustomerOperating(file));
    }

    @Test void existingEngineeringFilingRetainsItsOriginalBody() throws Exception {
        Path file = directory.resolve("engineering.journal");
        try (var world = ComposedIndustryScenario.openEngineeringCustomerOperating(file)) {
            assertFalse(world.profile().manifest().dataVersions().containsKey("listing-disclosure"));
            assertFalse(filing(world, "north").contains("每股开局参考估值"));
        }
        assertThrows(Exception.class, () -> ComposedIndustryScenario.openCompanyInformationOperating(file));
    }
}
