package edu.cufe.auction.world.composed;
import edu.cufe.auction.model.*;
import edu.cufe.auction.world.*;
import java.math.BigDecimal;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ResearchCashBoundaryPeerTest {
    @TempDir Path dir;
    @Test void frozenPharmaCashCannotBeSpentTwice() throws Exception {
        Path trace=Path.of(System.getProperty("research.cash.trace",dir.resolve("world.journal").toString()));
        WorldRuntime.Snapshot last;
        try(var w=ComposedIndustryScenario.openEngineeringCustomerOperating(trace)){
            ComposedPharmaWorldTest.grantBudgetAndAppointManager(w,"kate");
            w.openRoleSession("kate").registerResearch("reg","reg-task",14000,"pharma","p1",1000);
            w.advanceTo(14000);
            w.scheduleOrder("reserve","reserve-task",15000,"pharma-owner","RND-PHARMA","MT1",Side.BUY,OrderType.LIMIT,new BigDecimal("1.00"),1950);
            w.advanceTo(15000);
            assertEquals("ACTIVE",w.snapshot().exchange().orElseThrow().orders().get(0).status().name());
            assertEquals(new BigDecimal("2000.00"),w.snapshot().accounts().get("RND-PHARMA").cash());
            w.openRoleSession("kate").beginResearch("begin-frozen","begin-frozen-task",17000,"pharma","p1",ResearchModel.Stage.RESEARCH,1000);
            w.advanceTo(17000);
            assertEquals(17000,w.snapshot().timeMillis());
            assertTrue(w.snapshot().pending().stream().noneMatch(task -> task.taskId().contains("begin-frozen") || task.taskId().startsWith("research-result:pharma:p1:")));
            assertEquals(new BigDecimal("2000.00"),w.snapshot().accounts().get("RND-PHARMA").cash(),"1950 locked, only 50 free versus 200 research cost");
            assertEquals(0,w.researchRun().orElseThrow().state().projects().get("p1").paid().signum());
            assertEquals(new BigDecimal("900.00"),BoardModel.outstandingCommitment(w.boardOfCompany("pharma").orElseThrow().board(),"research:p1"));
            w.scheduleCancelOrder("release","release-task",18000,"pharma-owner",1);w.advanceTo(18000);
            w.openRoleSession("kate").beginResearch("after-release","after-release-task",20000,"pharma","p1",ResearchModel.Stage.RESEARCH,1000);
            w.advanceTo(20000);
            assertEquals(new BigDecimal("1800.00"),w.snapshot().accounts().get("RND-PHARMA").cash());
            assertEquals(new BigDecimal("700.00"),w.snapshot().accounts().get("RESEARCH-CONTRACTOR").cash());
            assertEquals(new BigDecimal("200.00"),w.researchRun().orElseThrow().state().projects().get("p1").paid());
            assertEquals(new BigDecimal("700.00"),BoardModel.outstandingCommitment(w.boardOfCompany("pharma").orElseThrow().board(),"research:p1"));
            last=w.snapshot();
        }
        byte[] saved=Files.readAllBytes(trace);
        try(var w=ComposedIndustryScenario.openEngineeringCustomerOperating(trace)){
            assertEquals(last,w.snapshot());
        }
        assertArrayEquals(saved,Files.readAllBytes(trace));
    }
    @Test void originalPersonalMarketRetainsItsFrozenResearchPolicy() throws Exception {
        Path old=dir.resolve("old.journal");WorldRuntime.Snapshot state;
        try(var w=ComposedIndustryScenario.openPersonalMarketOperating(old)){
            assertFalse(w.profile().manifest().dataVersions().containsKey("research-funding"));
            ComposedPharmaWorldTest.grantBudgetAndAppointManager(w,"kate");
            w.openRoleSession("kate").registerResearch("reg","reg-task",14000,"pharma","p1",1000);
            w.advanceTo(14000);
            w.openRoleSession("kate").beginResearch("begin","begin-task",17000,"pharma","p1",ResearchModel.Stage.RESEARCH,1000);
            w.advanceTo(17000);
            assertEquals(new BigDecimal("1800.00"),w.snapshot().accounts().get("RND-PHARMA").cash());
            state=w.snapshot();
        }
        try(var w=ComposedIndustryScenario.openPersonalMarketOperating(old)){
            assertEquals(state,w.snapshot());
        }
    }
}
