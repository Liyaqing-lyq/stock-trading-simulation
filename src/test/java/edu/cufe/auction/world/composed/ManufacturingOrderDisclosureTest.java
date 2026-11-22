package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** 同时点两家公司各自披露已签合同，披露与投资者读到该消息是两个独立事件。 */
class ManufacturingOrderDisclosureTest {
    @TempDir Path dir;

    @Test void onlyManufacturingChairsSeeTheToolAndCannotReportForAnotherCompany() throws Exception {
        try (var w = ComposedIndustryScenario.openFundedThinkingOperating(dir.resolve("scope.journal"))) {
            for (var chair : List.of(TECH_DIRECTORS.get(0), STRUCT_DIRECTORS.get(0), VERSA_DIRECTORS.get(0)))
                assertTrue(hasReportTool(w, chair), chair);
            for (var person : List.of(TECH_DIRECTORS.get(1), TECH_MANAGER, HydroIndustryConfig.DIRECTORS.get(0), WorldWebScenario.INVESTOR))
                assertFalse(hasReportTool(w, person), person);
            long now = w.snapshot().timeMillis();
            request(w, TECH_DIRECTORS.get(0), STRUCT, "cross", now + 10, now + 30);
            request(w, HydroIndustryConfig.DIRECTORS.get(0), HydroIndustryConfig.COMPANY, "hydro", now + 11, now + 30);
            w.advanceTo(now + 31);
            var reports = w.snapshot().trading().orElseThrow().reports().values();
            assertTrue(reports.stream().anyMatch(r -> r.subjectId().equals(STRUCT) && !r.accepted() && r.reason().equals("NOT_AUTHORIZED")));
            assertTrue(reports.stream().anyMatch(r -> r.subjectId().equals(HydroIndustryConfig.COMPANY) && !r.accepted() && r.reason().equals("UNKNOWN_COMPANY")));
            assertTrue(w.beliefAudit(WorldWebScenario.INVESTOR).orElseThrow().factClaims().isEmpty());
        }
        try (var legacy = ComposedIndustryScenario.openThinkingInformedOperating(dir.resolve("legacy.journal"))) {
            assertTrue(hasReportTool(legacy, TECH_DIRECTORS.get(1)));
            long now = legacy.snapshot().timeMillis();
            request(legacy, TECH_DIRECTORS.get(0), TECH, "old", now + 10, now + 30);
            legacy.advanceTo(now + 31);
            assertTrue(legacy.snapshot().trading().orElseThrow().reports().values().stream().anyMatch(r -> !r.accepted() && r.reason().equals("UNKNOWN_COMPANY")));
        }
    }

    @Test void frozenCompanyCountsPublishSeparatelyAndReachInvestorOnlyAfterActualReading() throws Exception {
        Path journal = dir.resolve("two-companies.journal");
        long cutoff;
        try (var w = ComposedIndustryScenario.openFundedThinkingOperating(journal)) {
            OperationsFixture.grantBudgetAndAppoint(w, TECH, TECH_DIRECTORS, TECH_MANAGER, "a", List.of(0L));
            OperationsFixture.grantBudgetAndAppoint(w, STRUCT, STRUCT_DIRECTORS, STRUCT_MANAGER, "b", List.of(0L));
            long start = w.snapshot().timeMillis();
            sign(w, TECH, TECH_MANAGER, "auto-oem", "a1", start+2000);
            sign(w, STRUCT, STRUCT_MANAGER, "builder-a", "b1", start+2000);
            w.advanceTo(start+2000);
            sign(w, STRUCT, STRUCT_MANAGER, "builder-a", "b2", start+4000);
            w.advanceTo(start+4000);
            cutoff = start+10000;
            request(w, TECH_DIRECTORS.get(0), TECH, "tech", start+6000, cutoff);
            request(w, STRUCT_DIRECTORS.get(0), STRUCT, "struct", start+6000, cutoff);
            sign(w, TECH, TECH_MANAGER, "auto-oem", "after-cut", cutoff+1);
            w.advanceTo(cutoff);
            assertEquals(2,w.snapshot().pending().stream().filter(t -> PendingTask.INFORMATION_BELIEF_PUBLISH_FACT_V1.equals(t.kind())).count());
            assertTrue(w.beliefAudit(WorldWebScenario.INVESTOR).orElseThrow().factClaims().isEmpty());
        }
        WorldRuntime.Snapshot end;
        try (var w = ComposedIndustryScenario.openFundedThinkingOperating(journal)) {
            w.advanceTo(cutoff+1);
            var facts = w.beliefAudit(WorldWebScenario.INVESTOR).orElseThrow().factClaims();
            assertEquals(2, facts.size());
            assertEquals(Map.of(TECH, 1L, STRUCT, 2L), facts.stream().collect(Collectors.toMap(BeliefModel.FactClaim::companyId, BeliefModel.FactClaim::value)));
            assertEquals(2,facts.stream().map(BeliefModel.FactClaim::docId).distinct().count());
            assertTrue(facts.stream().allMatch(f -> f.asOfMillis() == cutoff));
            assertEquals(2,w.operationsRun().orElseThrow().company(TECH).ledger().contracts().size());
            long readAt=cutoff+10;
            for (var fact : facts) {
                assertFalse(investorRead(w, fact.docId()));
                assertTrue(w.roleView(WorldWebScenario.INVESTOR).cognition().claims().stream().noneMatch(c -> c.docId().equals(fact.docId())));
                var doc = w.snapshot().information().orElseThrow().information().documents().stream()
                        .filter(d -> d.docId().equals(fact.docId())).findFirst().orElseThrow();
                assertEquals(InformationModel.Audience.publicAudience(),doc.audience());
                assertEquals(fact.verifierPartyId(),doc.publisherPartyId());
                assertTrue(doc.at(1).body().contains("累计签订销售合同 " + fact.value() + " 笔"));
                w.scheduleStartReading("read-"+fact.companyId(),"read-task-"+fact.companyId(),readAt,
                        WorldWebScenario.INVESTOR,fact.docId(),1);
                w.advanceTo(readAt+5);
                assertTrue(investorRead(w, fact.docId()));
                assertTrue(w.roleView(WorldWebScenario.INVESTOR).cognition().claims().stream()
                        .anyMatch(c -> c.docId().equals(fact.docId()) && c.companyId().equals(fact.companyId())
                                && c.value() == fact.value() && c.asOfMillis() == cutoff));
                readAt+=10;
            }
            end=w.snapshot();
        }
        byte[] bytes = Files.readAllBytes(journal);
        try (var w = ComposedIndustryScenario.openFundedThinkingOperating(journal)) { assertEquals(end,w.snapshot()); }
        assertArrayEquals(bytes,Files.readAllBytes(journal));
    }

    private static boolean investorRead(WorldRuntime w,String docId) {
        return InformationModel.observations(w.snapshot().information().orElseThrow().information(),WorldWebScenario.INVESTOR)
                .stream().anyMatch(observation -> observation.docId().equals(docId));
    }
    private static boolean hasReportTool(WorldRuntime w,String person) throws Exception {
        for (var tool:w.autonomyRoleObservation(person).path("tools"))
            if (RoleTradingView.TOOL_COMPANY_REPORT_ORDERS.equals(tool.path("name").asText())) return true;
        return false;
    }
    private static void request(WorldRuntime world,String person,String company,String id,long at,long cutoff) throws Exception {
        world.openRoleSession(person).reportOrders(id,id+"-task",at,new WorldRoleSession.ReportDraft(company,cutoff));
    }
    private static void sign(WorldRuntime w,String company,String actor,String customer,String id,long due) throws Exception {
        w.scheduleOperationsAction(id,id+"-task",due,company,actor,"SIGN_CONTRACT",
                OperationsFixture.op().contract(id, customer, "P1", 1, 1, new BigDecimal("80.00"),due+10000,due+20000).build(),1000);
    }
}
