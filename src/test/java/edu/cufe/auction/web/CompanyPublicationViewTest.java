package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.BeliefModel;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.WorldRuntime;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanyPublicationViewTest {
    @TempDir Path dir;

    @Test void privateMonthEndCanBeVerifiedBeforeAnyPublicRelease() throws Exception {
        try(var world=ComposedIndustryScenario.openPersonalMarketOperating(dir.resolve("monthly.journal"))) {
            assertThrows(IllegalArgumentException.class,()->world.confirmedPeriodStatement("mfg-tech",1));
            world.advanceTo(30L*86_400_000+1);
            long current=world.snapshot().position();
            for (String company : List.of("mfg-tech","eng-a","pharma","north")) {
                var history=world.periodicOf(company).orElseThrow();
                assertTrue(history.releases().isEmpty());
                var stored=history.snapshots().get(0);
                var report=world.confirmedPeriodStatement(company,1);
                var sha=java.security.MessageDigest.getInstance("SHA-256");
                assertEquals(stored.statementDigest(),java.util.HexFormat.of().formatHex(
                        sha.digest(report.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                assertEquals(stored.position(),report.position());
                assertEquals(30L*86_400_000,report.capturedAt());
                assertThrows(IllegalArgumentException.class,()->world.confirmedPeriodStatement(company,2));
            }
            assertEquals(current,world.snapshot().position());
        }
    }

    @Test void recentAndOlderBankAndResearchStatementsKeepTheirFrozenValuesOnReopen() throws Exception {
        Path journal = dir.resolve("three-months.journal");
        var statements = new java.util.HashMap<String, String>();
        var companies = List.of("north", "pharma");
        try (var world = ComposedIndustryScenario.openPersonalMarketOperating(journal)) {
            for (int period = 1; period <= 3; period++) {
                world.advanceTo(period * 30L * 86_400_000);
                for (String company : companies) {
                    statements.put(company + period, world.confirmedPeriodStatement(company, period).body());
                }
            }
        }
        byte[] before = java.nio.file.Files.readAllBytes(journal);
        try (var world = ComposedIndustryScenario.openPersonalMarketOperating(journal)) {
            long finalPosition = world.snapshot().position();
            for (int period : List.of(3, 1, 2, 3)) {
                for (String company : companies) {
                    assertEquals(statements.get(company + period), world.confirmedPeriodStatement(company, period).body());
                }
            }
            assertEquals(finalPosition, world.snapshot().position());
        }
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(journal));
    }

    @Test void publicSourceKeepsItsCompanyVersionAndActualReadersOnReopen() throws Exception {
        Path journal=dir.resolve("company.journal");
        JsonNode finalRows;
        try(var world=ComposedIndustryScenario.openPersonalMarketOperating(journal)) {
            long now=world.snapshot().timeMillis(), cutoff=now+100;
            world.openRoleSession(TECH_DIRECTORS.get(0)).reportOrders("announce","publish-orders",now+2,
                    new WorldRoleSession.ReportDraft(TECH,cutoff));
            world.advanceTo(cutoff);
            assertTrue(publications(world,TECH).isEmpty(),"captured but unpublished");
            world.advanceTo(cutoff+1);
            var publication=publications(world,TECH).get(0);
            assertEquals("订单公告",publication.path("kind").asText());
            assertEquals(1,publication.path("version").asInt());
            assertTrue(publication.path("reads").isEmpty());
            String doc=publication.path("docId").asText();
            world.scheduleStartReading("read","read-task",cutoff+2,"investor",doc,1);
            world.advanceTo(cutoff+4);
            var read=world.roleView("investor").observations().stream().filter(row->row.ref().docId().equals(doc)).findFirst().orElseThrow();
            world.openRoleSession("investor").assertBelief("form","form-task",cutoff+5,
                    new WorldRoleSession.BeliefDraft("judgment",TECH,BeliefModel.Comparator.AT_MOST,0,
                            cutoff+1000,null,List.of(read.ref()),null));
            world.advanceTo(cutoff+5);
            finalRows=publications(world,TECH);
            assertEquals("investor",finalRows.get(0).path("reads").get(0).path("person").asText());
            assertEquals("judgment",finalRows.get(0).path("beliefs").get(0).path("id").asText());
            assertTrue(publications(world,STRUCT).isEmpty());
        }
        try(var world=ComposedIndustryScenario.openPersonalMarketOperating(journal)) {
            assertEquals(finalRows,publications(world,TECH));
            assertTrue(publications(world,STRUCT).isEmpty());
        }
    }

    private static JsonNode publications(WorldRuntime world,String id) throws Exception {
        JsonNode companies=new ObjectMapper().valueToTree(WorldOverviewView.of(world).get("companies"));
        for (var row : companies) if (row.path("id").asText().equals(id)) return row.path("publications");
        throw new AssertionError("missing company "+id);
    }
}
