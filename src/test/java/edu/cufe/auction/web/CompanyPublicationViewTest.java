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
