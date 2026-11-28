package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cufe.auction.world.WorldRuntime;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static edu.cufe.auction.world.composed.IssuerCreditDecisionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CrisisActionArgumentsTest {
    @TempDir Path dir;
    private static final String TOOL="bank.crisis.act", PROVIDER="aged-manager";

    private static JsonNode office(WorldRuntime world,String actor) throws Exception {
        return credit(world,actor).at("/crisisOffices/0");
    }

    private static ObjectNode args(String kind) {
        return JSON.createObjectNode().put("bank","north").put("kind",kind);
    }

    private static JsonNode menu(WorldRuntime world,String actor) throws Exception {
        for (var tool:view(world,actor).path("tools")) if (TOOL.equals(tool.path("name").asText())) return tool;
        return JSON.missingNode();
    }

    @Test void legacyNullLineFailureAndOriginalMenuRemainReproducible() throws Exception {
        try(var w=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(dir.resolve("v19.journal"))) {
            w.advanceTo(32_476_600L);
            assertFalse(menu(w,PROVIDER).has("argumentSource"));
            assertFalse(office(w,PROVIDER).has("actionArguments"));
            assertTrue(office(w,PROVIDER).required("lines").isEmpty());
            assertNull(decide(w,PROVIDER,"old47",TOOL,args("FACILITY_AUTHORIZE").putNull("lineId").put("amount","300.00")));
            var result=view(w,PROVIDER).at("/engineering/deliberation/recentDecisions/0");
            assertEquals("TECHNICAL_FAILED",result.required("outcome").asText());
            assertTrue(result.required("protocolReason").asText().contains("与危机动作不符的金额或融资编号"));
            assertTrue(office(w,PROVIDER).required("lines").isEmpty());
        }
    }

    @Test void officersCanDistinguishANewAuthorizationFromDrawAndPaymentInTheirOwnMenu() throws Exception {
        Path file=dir.resolve("facility.journal");
        try(var w=ComposedIndustryScenario.openCrisisArgumentsTenCompanyStudy(file)) {
            w.advanceTo(32_476_600L);
            var bank=office(w,PROVIDER);
            assertEquals(1,bank.required("actionArguments").size());
            assertTrue(bank.at("/actionArguments/FACILITY_AUTHORIZE/lineId").asText().contains("自拟"));
            assertTrue(bank.at("/actionArguments/FACILITY_AUTHORIZE/lineId").asText().contains("没有借款申请"));
            assertTrue(menu(w,PROVIDER).required("argumentSource").asText().contains("actionArguments"));
            assertTrue(menu(w,"nina").isMissingNode());
            assertEquals("1000.00",bank.required("providerAvailable").asText());
            var offer=args("FACILITY_AUTHORIZE").put("lineId","own-new-line").put("amount","300.00");
            assertTrue(bank.required("lines").isEmpty());
            applied(w,PROVIDER,"authorize300",TOOL,offer);
            assertEquals("300.00",office(w,PROVIDER).at("/lines/0/limit").asText());
            assertEquals("0.00",office(w,PROVIDER).at("/lines/0/outstanding").asText());
            assertEquals("1000.00",office(w,PROVIDER).required("providerAvailable").asText());
            var duplicate=execute(w,PROVIDER,"reuse-id",TOOL,offer);
            assertEquals("REJECTED",duplicate.required("execution").asText());
            assertTrue(duplicate.required("businessReject").asText().contains("DUPLICATE"));
            // 批准上限可以高于当下出资现金，提款时才实际核款。
            applied(w,PROVIDER,"authorize1600",TOOL,args("FACILITY_AUTHORIZE").put("lineId","second-line").put("amount","1600.00"));
            CrisisOfficeObjectivesTest.appoint(w,"north","erin");
            var borrower=office(w,"erin");
            assertEquals(2,borrower.required("actionArguments").size());
            assertTrue(borrower.at("/actionArguments/FACILITY_DRAW/lineId").asText().contains("本行 lines"));
            assertTrue(borrower.at("/actionArguments/FACILITY_PAY/amount").asText().contains("outstanding"));
            assertFalse(borrower.has("providerAvailable"));
            var oversized=execute(w,"erin","insufficient",TOOL,args("FACILITY_DRAW").put("lineId","second-line").put("amount","1100.00"));
            assertEquals("REJECTED",oversized.required("execution").asText());
            applied(w,"erin","draw200",TOOL,args("FACILITY_DRAW").put("lineId","own-new-line").put("amount","200.00"));
            var excessPay=execute(w,"erin","excess-pay",TOOL,args("FACILITY_PAY").put("lineId","own-new-line").put("amount","201.00"));
            assertEquals("REJECTED",excessPay.required("execution").asText());
            applied(w,"erin","pay50",TOOL,args("FACILITY_PAY").put("lineId","own-new-line").put("amount","50.00"));
        }
        try(var w=ComposedIndustryScenario.openCrisisArgumentsTenCompanyStudy(file)) {
            var lender=office(w,PROVIDER);
            assertEquals("850.00",lender.required("providerAvailable").asText());
            assertTrue(java.util.stream.StreamSupport.stream(lender.required("lines").spliterator(),false)
                    .anyMatch(line->"own-new-line".equals(line.path("lineId").asText()) && "150.00".equals(line.path("outstanding").asText())));
        }
    }

    @Test void resolutionOfficeUsesNoAmountOrIdentifierAndActualTriggerStillApplies() throws Exception {
        try(var w=ComposedIndustryScenario.openCrisisArgumentsTenCompanyStudy(dir.resolve("authority.journal"))) {
            w.advanceTo(32_476_600L);
            var authority=office(w,"kate");
            assertEquals(2,authority.required("actionArguments").size());
            assertFalse(authority.has("providerAvailable"));
            for(var kind:java.util.List.of("RESOLUTION_OPEN","RESOLUTION_DISTRIBUTE"))
                assertTrue(authority.path("actionArguments").path(kind).path("amount").asText().contains("null"));
            assertNull(decide(w,"kate","wrong-fields",TOOL,args("RESOLUTION_OPEN").put("amount","1.00").putNull("lineId")));
            var open=execute(w,"kate","omit-fields",TOOL,args("RESOLUTION_OPEN"));
            assertEquals("REJECTED",open.required("execution").asText());
            assertEquals("RESOLUTION_TRIGGER_ABSENT",open.required("businessReject").asText());
            var distribute=execute(w,"kate","null-fields",TOOL,args("RESOLUTION_DISTRIBUTE").putNull("amount").putNull("lineId"));
            assertEquals("REJECTED",distribute.required("execution").asText());
            assertFalse(office(w,"kate").required("resolutionOpen").asBoolean());
        }
    }
}
