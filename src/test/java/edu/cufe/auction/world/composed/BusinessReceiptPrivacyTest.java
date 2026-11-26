package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.ComposedIndustryScenario.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessReceiptPrivacyTest {
    @TempDir Path dir;

    @Test void unrelatedPrivateDeliveryCannotBeCountedInTheManagersDispatchedReceipts() throws Exception {
        JsonNode baseline = null, originalReceipt = null;
        long previousAudit = -1;
        String originalInput = null;
        for (boolean privateMessage : List.of(false,true)) {
            Path path = dir.resolve("branch-" + privateMessage + ".journal");
            String digest;
            try (var world = openTenCompanyAutonomy(path)) {
                OperationsFixture.grantBudgetAndAppoint(world, RESOURCE, DIRECTORS, MANAGER, "appointment", List.of(0L));
                ResourceCessationTest.denied(world,"declined-sale",ResourceWorldChainTest.request(ResourceOperations.Action.SELL_COAL,
                        "deliveryId","early-sale","receivableId","early-receivable","quantityKg","100"));
                long now = world.snapshot().timeMillis();
                if (privateMessage) {
                    ResourceCessationTest.captureObservation(world,"nina","ninas-unrelated-decision");
                    world.schedulePublishDocument("hidden", "hidden", now+1, "private-source", "alice",
                            InformationModel.Audience.direct("nina"), "Nina的私信","私人抬头","不向资源公司投递",10,List.of());
                }
                world.advanceTo(now + 2);
                var args = IssuerCreditDecisionTest.JSON.createObjectNode().put("action","BEGIN_EXTRACTION");
                args.putObject("arguments").put("jobId","mine").put("seamId","resource-seam-a").put("quantityKg","100");
                IssuerCreditDecisionTest.execute(world, MANAGER, "own-action", ResourceDecisionTools.ACT, args);
                digest = ResourceCessationTest.captureObservation(world, MANAGER,"after-report");
                var decision = DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),"after-report").orElseThrow();
                var input = IssuerCreditDecisionTest.JSON.readTree(decision.inputText());
                var business = input.at("/engineering/resourceOperations");
                var reports = business.required("reports");
                assertTrue(reports.size() >= 2,reports.toString());
                assertFalse(reports.get(0).path("accepted").asBoolean());
                var last = reports.get(reports.size()-1);
                assertTrue(last.path("accepted").asBoolean());
                assertEquals("BEGIN_EXTRACTION", last.path("action").asText());
                assertEquals("100.00",last.path("amount").asText());
                assertFalse(input.toString().contains("private-source"));
                for (String key : List.of("position","taskId","commandId","retainedMinPosition","retainedMaxPosition"))
                    assertTrue(business.findValues(key).isEmpty(),key);
                assertTrue(input.findValues("resultPosition").isEmpty());
                assertTrue(input.findValues("attemptId").isEmpty());
                var result = input.at("/engineering/deliberation/lastActionResult");
                assertEquals("APPLIED",result.path("execution").asText());
                assertEquals(last.path("timeMillis").asLong(),result.path("resultAt").asLong());
                long audit = world.operationsRun().orElseThrow().reports().values().stream()
                        .filter(r -> RESOURCE.equals(r.companyPartyId()) && "BEGIN_EXTRACTION".equals(r.action()))
                        .mapToLong(CompanyOperationsRun.ReportEntry::position).max().orElseThrow();
                if (privateMessage) {
                    assertTrue(audit > previousAudit);
                    assertEquals(originalReceipt, result);
                    assertEquals(originalInput, digest);
                    assertEquals(baseline, business, "业务完全相同，另一人的私事不能改变经理可见报告");
                } else {
                    baseline = business;
                    originalReceipt = result;
                    originalInput = digest;
                    previousAudit = audit;
                }
                assertTrue(world.autonomyRoleObservation("nina").at("/engineering/resourceOperations").isMissingNode());
            }
            byte[] bytes=Files.readAllBytes(path);
            try (var world = openTenCompanyAutonomy(path)) {
                assertEquals(digest,DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),"after-report").orElseThrow().inputDigest());
            }
            assertArrayEquals(bytes,Files.readAllBytes(path));
        }
    }
}
