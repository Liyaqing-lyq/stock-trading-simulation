package edu.cufe.auction.world;

import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PersonalCommunicationTimeTest {
    @TempDir Path dir;

    @Test void writingAndDeliveryUseTheTimesActuallyShownToTheWriter() throws Exception {
        Path file = dir.resolve("new.journal");
        long delivered;
        try (var w = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            w.advanceTo(34_200_001L);
            var nina = w.openRoleSession("nina");
            long readStart = w.snapshot().timeMillis() + 1;
            nina.startReading("read-contact","read-contact",readStart,WorldWebScenario.SOCIAL_CONTACT_DOC,1L);
            w.advanceTo(readStart);
            w.advanceTo(w.roleView("nina").readings().get(0).dueAt());
            var actions = w.autonomyRoleObservation("nina").at("/engineering/planning/actions");
            assertEquals(60_000, actions.path("social.send").path("actionMillis").longValue());
            assertEquals(60_000, actions.path("platform.post").path("actionMillis").longValue());
            var draft = new WorldRoleSession.SocialSendDraft("omar","私信","来信","请查收到的账单。");
            long begin = w.snapshot().timeMillis();
            assertThrows(IllegalArgumentException.class, () -> nina.sendMessage("fast","fast",begin+1_000,draft));
            nina.sendMessage("note","note",begin+60_000,draft);
            w.advanceTo(begin+59_999);
            assertTrue(w.socialView("omar").inbox().isEmpty());
            w.advanceTo(begin+60_000);
            assertTrue(w.socialView("omar").inbox().isEmpty());
            delivered = begin+60_000+WorldWebScenario.SOCIAL_DELIVERY_MILLIS;
            w.advanceTo(delivered);
            var letter = w.socialView("omar").inbox().get(0);
            assertEquals(delivered,letter.deliveredAt());
            assertTrue(w.roleView("omar").observations().stream().noneMatch(r -> r.ref().docId().equals(letter.docId())));
            var post = new WorldRoleSession.PlatformPostDraft("工作安排","请读公开版本。",List.of());
            long now = w.snapshot().timeMillis();
            assertThrows(IllegalArgumentException.class, () -> nina.postToPlatform("fast-post","fast-post",now+15,post));
            nina.postToPlatform("post","post",now+60_000,post);
            w.openRoleSession("omar").startReading("read-letter","read-letter",now+1,letter.docId(),letter.version());
            w.advanceTo(now+1);
            var reading = w.roleView("omar").readings().get(0);
            assertEquals(now+30_001,reading.dueAt());
            w.advanceTo(reading.dueAt()-1);
            assertTrue(w.roleView("omar").observations().stream().noneMatch(r -> r.ref().docId().equals(letter.docId())));
            w.advanceTo(reading.dueAt());
            assertTrue(w.roleView("omar").observations().stream().anyMatch(r -> r.ref().docId().equals(letter.docId())));
            w.advanceTo(now+60_000+WorldWebScenario.GROUPS_DELIVERY_MILLIS);
            assertTrue(w.groupsRun().reports().stream().anyMatch(r -> r.accepted() && r.action().equals("PLATFORM_POST")
                    && r.atMillis()==now+60_000));
        }
        try (var reopened = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            assertEquals(delivered,reopened.socialView("omar").inbox().get(0).deliveredAt());
            assertEquals(30_000,reopened.groupsRun().readMillis());
            assertEquals(60_000,reopened.groupsRun().config().postMillis());
        }
    }

    @Test void reopeningThirteenthVersionKeepsTheOriginalActionTimes() throws Exception {
        Path file=dir.resolve("old.journal");
        try (var old=ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(file)) {
            assertEquals(1_000,old.socialRun().config().sendMillis());
        }
        try (var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            assertEquals(15,w.groupsRun().platformConfig().postMillis());
            assertEquals(20,w.groupsRun().config().postMillis());
            assertEquals(1_000,w.socialRun().config().sendMillis());
        }
    }
}
