package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.InformationModel;
import edu.cufe.auction.world.WorldRuntime;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static edu.cufe.auction.world.composed.IssuerCreditDecisionTest.view;
import static org.junit.jupiter.api.Assertions.*;

class ResearchBoardArgumentsTest {
    @TempDir Path dir;
    private static final String COMPANY="pharma", MEETING="commercial-check", PRIVATE="private-operations-note";

    private static JsonNode tool(JsonNode input) {
        for (var row:input.path("tools")) if ("board.research".equals(row.path("name").asText())) return row;
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static JsonNode meeting(JsonNode input) {
        for(var seat:input.at("/engineering/directorships")) for(var row:seat.path("meetings"))
            if(MEETING.equals(row.path("meetingId").asText())) return row;
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    @Test void onlyAuthorizedResearchDirectorsReceiveUsableReferencesWithoutANewReadGrant() throws Exception {
        Path file=dir.resolve("committee.journal");
        try(var w=ComposedIndustryScenario.openCrisisArgumentsTenCompanyStudy(file)) {
            long at=34_200_000L;
            w.schedulePublishDocument("publish-private","publish-private",10,PRIVATE,"frank",InformationModel.Audience.direct("frank"),
                    "内部核对","未读取的材料原文","内部记录",1000,List.of());
            w.advanceTo(at);
            w.scheduleCommercializationConvene("convene","convene",at+2000,COMPANY,"frank",MEETING,at+60_000,at+65_000,at+75_000);
            w.scheduleEngineeringAttend("attend","attend",at+4000,COMPANY,"frank",MEETING,false);
            w.scheduleEngineeringMaterial("shared","shared",at+6000,COMPANY,"frank",MEETING,"shared-slot","company-governance:pharma:1",1);
            w.scheduleEngineeringMaterial("private","private",at+8000,COMPANY,"frank",MEETING,"private-slot",PRIVATE,1);
            w.advanceTo(at+8000);
            var first=view(w,"frank");assertTrue(tool(first).path("argumentSource").asText().contains("PROPOSE"));
            assertTrue(tool(first).at("/arrayItems/evidence/sourceRef").asText().contains("docId"));
            var materials=meeting(first).required("materials");assertEquals(2,materials.size());
            for(var row:materials){
                var nativeRef=w.boardOfCompany(COMPANY).orElseThrow().board().meeting(MEETING).materials().stream()
                        .filter(m->m.materialId().equals(row.required("materialId").asText())).findFirst().orElseThrow();
                assertEquals(nativeRef.sourceRef(),row.required("docId").asText());assertEquals(nativeRef.asOf(),row.required("asOf").asLong());
                assertFalse(row.required("read").asBoolean());
            }
            var peer=view(w,"judy");assertEquals(1,meeting(peer).required("materials").size());
            assertFalse(peer.toString().contains(PRIVATE));assertFalse(peer.toString().contains("private-slot"));
            var outsider=view(w,"nina");assertTrue(tool(outsider).isMissingNode());assertFalse(outsider.toString().contains(PRIVATE));
        }
        try(var w=ComposedIndustryScenario.openCrisisArgumentsTenCompanyStudy(file)) {
            assertEquals(1,meeting(view(w,"judy")).required("materials").size());
        }
    }

    @Test void priorStudyKeepsItsOriginalResearchToolFields() throws Exception {
        try(var old=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(dir.resolve("old.journal"))) {
            old.advanceTo(34_200_000L);var original=view(old,"frank");
            assertFalse(tool(original).has("argumentSource"));assertFalse(tool(original).has("arrayItems"));
            assertFalse(original.at("/engineering/directorships/0").has("researchBoardProcedure"));
        }
    }
}
