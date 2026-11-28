package edu.cufe.auction.world.composed;

import edu.cufe.auction.web.SceneRoutes;
import edu.cufe.auction.web.WorldWebHost;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.WorldRoleSession;
import edu.cufe.auction.world.RoleEvidenceDocuments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FundedOfficeRoutingTest {
    @TempDir Path dir;

    @Test void chairAndManagerKeepGovernanceRouteAfterBusinessCorrespondence() throws Exception {
        var company = ComposedManufacturingThreeScenario.TECH;
        var chair = ComposedManufacturingThreeScenario.TECH_DIRECTORS.get(0);
        var manager = ComposedManufacturingThreeScenario.TECH_MANAGER;
        var journal = dir.resolve("world.journal");
        try (var world = ComposedIndustryScenario.openFundedThinkingOperating(journal)) {
            assertTrue(world.snapshot().social().orElseThrow().contacts().stream()
                    .anyMatch(entry -> entry.targetPartyId().equals(chair)
                            && entry.docId().equals(RoleEvidenceDocuments.documentId(company))));
            OperationsFixture.grantBudgetAndAppoint(world, company,
                    ComposedManufacturingThreeScenario.TECH_DIRECTORS, manager, "appoint", List.of(0L));
            long start = world.snapshot().timeMillis() + 1;
            world.scheduleStartReading("read", "read-task", start, manager,
                    RoleEvidenceDocuments.documentId(company), 1);
            world.advanceTo(start + 10_000);
            long send = world.snapshot().timeMillis() + WorldWebScenario.SOCIAL_SEND_MILLIS;
            world.openRoleSession(manager).sendMessage("letter", "letter-task", send,
                    new WorldRoleSession.SocialSendDraft(chair, RoleEvidenceDocuments.CHAIR_CONTACT_CHANNEL,
                            "经营工作联系", "希望讨论采购预算。"));
            world.advanceTo(send + 10_000);
            assertTrue(world.snapshot().social().orElseThrow().state().messages().stream()
                    .anyMatch(message -> message.from().equals(manager) && message.to().equals(chair)));
        }
        try (var routes = SceneRoutes.open(dir);
             var host = new WorldWebHost(() -> {
                 var world = ComposedIndustryScenario.openFundedThinkingOperating(journal);
                 return new WorldWebHost.Opened(world, world.profile().manifest().runId());
             }, routes, (WorldWebHost.DecisionProvider) (call, timeout) -> {
                 throw new AssertionError("只读取路由不能调用模型");
             })) {
            var caps = host.liveCapabilities();
            assertTrue(caps.governancePersons().stream().anyMatch(row ->
                    row.personPartyId().equals(chair) && row.governanceCompanies().contains(company)));
            assertFalse(caps.persons().stream().anyMatch(row ->
                    row.personPartyId().equals(chair) && row.sceneId().equals("media")),
                    "仅因董事会工作联系方式存在，主席不能失去自动治理路由");
            assertTrue(caps.governancePersons().stream().anyMatch(row ->
                    row.personPartyId().equals(manager) && row.governanceCompanies().contains(company)));
            assertFalse(caps.persons().stream().anyMatch(row ->
                    row.personPartyId().equals(manager) && row.sceneId().equals("media")),
                    "发送经营信函不能将经理派到媒体场景");
            assertTrue(caps.persons().stream().anyMatch(row ->
                    row.personPartyId().equals("dave") && row.sceneId().equals("trading")));
        }
    }
}
