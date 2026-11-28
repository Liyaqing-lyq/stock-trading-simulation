package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import edu.cufe.auction.world.composed.MediaSubscriptionPublicationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MediaWithdrawalDecisionTest {
    @TempDir Path dir;
    private static final String TOOL = RoleTradingView.TOOL_MEDIA_WITHDRAW;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static long due(WorldRuntime w, String kind) {
        return w.snapshot().timeMillis() + MediaRun.durationOf(w.snapshot().media().orElseThrow().config(), kind, "wire");
    }
    private static String story(WorldRuntime w) {
        return w.snapshot().media().orElseThrow().stateOf("wire").stories().get(0).storyId();
    }
    private static MediaRun.Report last(WorldRuntime w, String kind) {
        return w.snapshot().media().orElseThrow().reports().values().stream().filter(r -> kind.equals(r.kind()))
                .max(java.util.Comparator.comparingLong(MediaRun.Report::position)).orElseThrow();
    }
    private static WorldRoleSession.MediaWithdrawalDraft draft(String story) {
        return new WorldRoleSession.MediaWithdrawalDraft(story,1,MediaModel.WithdrawalReason.FACTUAL_ERROR);
    }
    @Test void editCanWithdrawTheExactPublicVersionDuringAnUnpublishedCorrection() throws Exception {
        var file = dir.resolve("withdraw.journal");
        String doc;
        try (var w = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            var early = w.openRoleSession("ed");
            w.scheduleMediaSubscription("nina", "JOIN", "wire", "free-trial"); w.advanceTo(w.snapshot().timeMillis()+1000);
            doc = MediaSubscriptionPublicationTest.publishWire(w);
            String story = story(w);
            long d = due(w,MediaRun.KIND_WITHDRAW);
            early.withdrawStory("early","early",d,draft(story)); w.advanceTo(d);
            assertEquals(MediaModel.REASON_VERSION,last(w,MediaRun.KIND_WITHDRAW).reason());
            for(var person:List.of("rita","investor","dave"))
                assertThrows(IllegalArgumentException.class,()->w.openRoleSession(person).withdrawStory("other","other",due(w,MediaRun.KIND_WITHDRAW),draft(story)));
            long readAt=w.snapshot().timeMillis()+1;
            w.openRoleSession("dave").startReading("read-prior","read-prior",readAt,doc,1);
            w.advanceTo(readAt); w.advanceTo(w.roleView("dave").readings().get(0).dueAt());
            assertTrue(w.canCite("dave",doc,1));
            long correction=due(w,MediaRun.KIND_CORRECTION);
            w.openRoleSession("rita").startCorrection("correct","correct",correction,new WorldRoleSession.MediaStoryDraft(story,1));
            w.advanceTo(correction);assertEquals(MediaModel.Status.DRAFT,w.snapshot().media().orElseThrow().stateOf("wire").stories().get(0).status());
            var call=AutonomousIssuerDividendTest.call(w,"ed","withdraw-wire");
            assertTrue(call.allowedTools().contains(TOOL));
            var ref=AutonomousIssuerDividendTest.reply(w,call,"ed",TOOL,
                    JSON.createObjectNode().put("storyId",story).put("expectedPublicVersion",1).put("publicReason","FACTUAL_ERROR"));
            assertNotNull(ref);w.advanceTo(ref.dueMillis());
            assertEquals("WITHDRAWN",last(w,MediaRun.KIND_WITHDRAW).reason());
            var receipt=w.autonomyRoleObservation("ed").at("/engineering/deliberation/lastActionResult");
            assertEquals("APPLIED",receipt.required("execution").asText());
            var state=w.snapshot().media().orElseThrow().stateOf("wire");
            assertEquals(MediaModel.Status.WITHDRAWN,state.stories().get(0).status());
            assertEquals(2,state.stories().get(0).publicVersion());assertEquals(1,state.stories().get(0).revisionNo());
            var info=w.snapshot().information().orElseThrow().information();
            var publicDoc=info.documents().stream().filter(x->doc.equals(x.docId())).findFirst().orElseThrow();
            assertFalse(publicDoc.at(1).title().contains("撤稿"));assertTrue(publicDoc.at(2).title().startsWith("撤稿公告"));
            assertTrue(publicDoc.at(2).body().contains("原稿存在事实错误"));assertTrue(publicDoc.at(2).body().contains("第 1 版"));
            var root=state.lineage().stream().filter(l->doc.equals(l.docId()) && l.version()==2).findFirst().orElseThrow().roots();
            assertEquals(1,root.size());
            var notice=info.documents().stream().filter(x->root.get(0).docId().equals(x.docId())).findFirst().orElseThrow();
            assertEquals("ed",notice.publisherPartyId());assertEquals(InformationModel.AudienceKind.PUBLIC,notice.audience().kind());
            assertTrue(notice.at(1).internalSourceIds().isEmpty());
            assertTrue(state.revisions().stream().anyMatch(r->r.status()==MediaModel.Status.PUBLISHED && r.docVersion()==1));
            assertTrue(w.canCite("dave",doc,1));assertFalse(w.canCite("dave",doc,2));
            var deliveries=JSON.valueToTree(w.mediaSubscriptionsOf("nina")).required("deliveries");
            assertTrue(deliveries.findValues("version").stream().anyMatch(v->v.asLong()==2));
            var feed = w.personalNewsFeed("dave").stream().filter(e -> doc.equals(e.docId())).toList();
            assertEquals(1,feed.size());
            assertEquals(2,feed.get(0).version());
            assertEquals("ed",feed.get(0).publisherPartyId());
            assertTrue(feed.get(0).title().startsWith("撤稿公告"));
            assertFalse(w.canCite("nina",doc,2));
            long begin = w.snapshot().timeMillis() + 1;
            w.openRoleSession("nina").startReading("read-notice","read-notice",begin,doc,2);
            w.advanceTo(begin);
            var flight = w.roleView("nina").readings().stream().filter(r -> r.ref().docId().equals(doc)).findFirst().orElseThrow();
            w.advanceTo(flight.dueAt() - 1);
            assertFalse(w.canCite("nina",doc,2));
            w.advanceTo(flight.dueAt());
            assertTrue(w.canCite("nina",doc,2));
            long stale=due(w,MediaRun.KIND_PUBLISH);
            w.openRoleSession("ed").publishStory("stale","stale",stale,new WorldRoleSession.MediaStoryDraft(story,1));w.advanceTo(stale);
            assertFalse(last(w,MediaRun.KIND_PUBLISH).accepted());
            d=due(w,MediaRun.KIND_WITHDRAW);
            w.openRoleSession("ed").withdrawStory("twice","twice",d,draft(story));w.advanceTo(d);
            assertFalse(last(w,MediaRun.KIND_WITHDRAW).accepted());
        }
        var bytes=Files.readAllBytes(file);
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)){
            assertEquals(2,w.snapshot().media().orElseThrow().stateOf("wire").stories().get(0).publicVersion());
            assertTrue(w.canCite("dave",doc,1));assertFalse(w.canCite("dave",doc,2));
            assertTrue(w.canCite("nina",doc,2));
        }
        assertArrayEquals(bytes,Files.readAllBytes(file));
    }
    @Test void lossOfTheBoundEditorialAppointmentRejectsTheScheduledWithdrawal() throws Exception {
        try(var w=ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(dir.resolve("revoked.journal"))){
            MediaSubscriptionPublicationTest.publishWire(w);String story=story(w);long d=due(w,MediaRun.KIND_WITHDRAW);
            w.openRoleSession("ed").withdrawStory("w","w",d,draft(story));
            String job=w.openRoleSession("ed").view().appointments().stream().filter(a->a.role()==RoleAccess.Role.EDITOR && a.organizationPartyId().equals("wire")).findFirst().orElseThrow().appointmentId();
            w.scheduleRevokeAppointment("revoke","revoke",w.snapshot().timeMillis()+1,job);
            w.advanceTo(d);assertEquals(MediaModel.REASON_NOT_AUTHORIZED,last(w,MediaRun.KIND_WITHDRAW).reason());
            assertEquals(1,w.snapshot().media().orElseThrow().stateOf("wire").stories().get(0).publicVersion());
        }
        try(var w=ComposedIndustryScenario.openIssuerDecisionsTenCompanyStudy(dir.resolve("v13.journal"))){
            assertFalse(w.openRoleSession("ed").view().tools().contains(TOOL));
            assertThrows(IllegalArgumentException.class,()->w.openRoleSession("ed").withdrawStory("w","w",w.snapshot().timeMillis()+1000,draft("guess")));
        }
    }

    @Test void personPageBindsTheExactArticleVersionAndScopedEditorToken() throws Exception {
        Path file = dir.resolve("parent.journal");
        String child = "withdraw-editor", id;
        BranchProfile profile;
        try (var w = ComposedIndustryScenario.openMediaWithdrawalTenCompanyStudy(file)) {
            MediaSubscriptionPublicationTest.publishWire(w);
            id = story(w);
            w.createBranch(child,w.snapshot().position(),BranchOrigin.Kind.GOD_TAKEOVER,"人工接管人物 ed");
            profile = w.profile();
        }
        byte[] original = Files.readAllBytes(file);
        try (var host = new WorldWebHost(() -> new WorldWebHost.Opened(
                new BranchRepository(dir.resolve("runs")).open(child,profile),child))) {
            var page = JSON.valueToTree(host.personPage("ed").media()).required("withdrawal");
            long token = page.required("observationToken").asLong();
            assertEquals(id,page.at("/stories/0/storyId").asText());
            assertEquals("1",page.at("/stories/0/publicVersion").asText());
            assertFalse(host.personPage("dave").media().containsKey("withdrawal"));
            long at = host.adminSnapshot().position();
            assertThrows(WorldWebHost.PositionMismatch.class,() -> host.withdrawPublishedStory("dave",token,"outsider",draft(id)));
            assertThrows(IllegalArgumentException.class,() -> host.withdrawPublishedStory("ed",token,"other-version",
                    new WorldRoleSession.MediaWithdrawalDraft(id,2,MediaModel.WithdrawalReason.FACTUAL_ERROR)));
            assertEquals(at,host.adminSnapshot().position());
            var response = JSON.valueToTree(host.withdrawPublishedStory("ed",token,"withdraw",draft(id)));
            assertEquals("SCHEDULED:withdraw",response.required("receipt").asText());
            host.advance(host.adminSnapshot().position(),response.required("dueTimeMillis").asLong());
            assertTrue(JSON.valueToTree(host.personPage("ed").media()).at("/withdrawal/stories").isEmpty());
            assertThrows(WorldWebHost.PositionMismatch.class,() -> host.withdrawPublishedStory("ed",token,"late",draft(id)));
        }
        assertArrayEquals(original,Files.readAllBytes(file));
    }
}
