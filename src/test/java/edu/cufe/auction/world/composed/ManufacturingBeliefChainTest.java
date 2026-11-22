package edu.cufe.auction.world.composed;

import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static edu.cufe.auction.world.composed.ComposedManufacturingThreeScenario.*;
import static org.junit.jupiter.api.Assertions.*;

class ManufacturingBeliefChainTest {
    private static final String INVESTOR = "investor";
    @TempDir Path dir;

    @Test void readsCompanyOrderFactsFormsPredictionThenLearnsOnlyAfterReadingLaterDisclosure() throws Exception {
        Path journal=dir.resolve("belief.journal");
        String chair=TECH_DIRECTORS.get(0);
        WorldRuntime.Snapshot end;
        try(var world=ComposedIndustryScenario.openPersonalMarketOperating(journal)) {
            long now=world.snapshot().timeMillis();
            long firstCutoff=now+100;
            world.openRoleSession(chair).reportOrders("announce","announce-task",now+2,
                    new WorldRoleSession.ReportDraft(TECH,firstCutoff));
            world.advanceTo(firstCutoff+1);
            var fact=world.beliefAudit(INVESTOR).orElseThrow().factClaims().get(0);
            assertEquals(TECH,fact.companyId());
            assertEquals(0,fact.value());
            var evidence=read(world,fact.docId());
            long started=world.snapshot().timeMillis(), target=started+10000;
            var session=world.openRoleSession(INVESTOR);
            session.assertBelief("form","form-task",started+1,
                    new WorldRoleSession.BeliefDraft("orders", TECH,BeliefModel.Comparator.AT_LEAST,
                            1,target,null,List.of(evidence),"我预计下一次披露时至少有一笔订单。"));
            world.advanceTo(started+1);
            var audit=world.beliefAudit(INVESTOR).orElseThrow();
            var report=world.snapshot().trading().orElseThrow().reports().values().stream()
                    .filter(r->"orders".equals(r.subjectId())).findFirst().orElseThrow();
            assertTrue(report.accepted(), report.reason());
            assertEquals(chair,audit.state().beliefs().get(0).verificationPublisherPartyId());
            world.openRoleSession(INVESTOR).publishForecast("forecast","forecast-task",started+3,
                    new WorldRoleSession.ForecastDraft("claim","forecast-orders","订单预测","预计至少一笔。",
                            "投资者个人预测",5,TECH,BeliefModel.Comparator.AT_LEAST,1,target));
            world.advanceTo(started+3);
            var claim=world.beliefAudit(INVESTOR).orElseThrow().sourceClaims().get(0);
            assertEquals(INVESTOR,claim.authorPartyId());
            assertEquals(chair,claim.verificationPublisherPartyId());
            world.openRoleSession(chair).reportOrders("verify","verify-task",started+10,
                    new WorldRoleSession.ReportDraft(TECH,target));
            world.advanceTo(target+1);
            var beforeReading=world.beliefAudit(INVESTOR).orElseThrow();
            assertNull(BeliefModel.verdict(beforeReading.state(),"orders"));
            var later=beforeReading.factClaims().stream().filter(f->f.asOfMillis()==target).findFirst().orElseThrow();
            assertEquals(0,later.value());
            read(world,later.docId());
            assertEquals(BeliefModel.Verdict.MISS,BeliefModel.verdict(world.beliefAudit(INVESTOR).orElseThrow().state(),"orders"));
            end=world.snapshot();
        }
        try(var reopened=ComposedIndustryScenario.openPersonalMarketOperating(journal)) {
            assertEquals(end,reopened.snapshot());
            assertEquals(BeliefModel.Verdict.MISS,BeliefModel.verdict(reopened.beliefAudit(INVESTOR).orElseThrow().state(),"orders"));
        }
    }

    @Test void unknownCompanyAndNonManufacturingIssuerCannotAcquireAnOrderCountVerifier() throws Exception {
        try(var world=ComposedIndustryScenario.openPersonalMarketOperating(dir.resolve("scope.journal"))) {
            for(String company:List.of("unknown-company",HydroIndustryConfig.COMPANY)) {
                long now=world.snapshot().timeMillis();
                world.openRoleSession(INVESTOR).publishForecast("forecast-"+company,"task-"+company,now+1,
                        new WorldRoleSession.ForecastDraft("claim-"+company,"doc-"+company,"预测","目标为一笔。",
                                "个人预测",5,company,BeliefModel.Comparator.AT_LEAST,1,now+100));
                world.advanceTo(now+1);
                assertTrue(world.snapshot().trading().orElseThrow().reports().values().stream().anyMatch(r->
                        r.subjectId().equals("claim-"+company) && !r.accepted() && r.reason().equals("UNKNOWN_COMPANY")));
            }
            assertTrue(world.beliefAudit(INVESTOR).orElseThrow().sourceClaims().isEmpty());
        }
    }

    private static RoleView.ReadingRef read(WorldRuntime world,String doc) throws Exception {
        long start=world.snapshot().timeMillis()+1;
        world.scheduleStartReading("read-"+doc,"read-task-"+doc,start,INVESTOR,doc,1);
        world.advanceTo(start+5);
        return world.roleView(INVESTOR).observations().stream().filter(e->e.ref().docId().equals(doc))
                .findFirst().orElseThrow().ref();
    }
}
