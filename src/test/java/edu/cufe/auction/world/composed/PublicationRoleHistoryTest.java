package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cufe.auction.world.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static edu.cufe.auction.world.composed.MediaRoleHistoryTest.look;

class PublicationRoleHistoryTest {
    @TempDir Path dir;
    private static final String SECTION = "publications";
    private static final String GROUP = "group:open";

    private static String post(WorldRuntime w,boolean platform,String id) throws Exception {
        var who = w.openRoleSession("bob");
        var group = w.groupsRun();
        long at = w.snapshot().timeMillis() + (platform ? group.platformConfig().postMillis() : group.config().postMillis());
        if (platform) who.postToPlatform(id,id,at,new WorldRoleSession.PlatformPostDraft("平台核对","平台正文。",List.of()));
        else who.postToGroup(id,id,at,new WorldRoleSession.GroupPostDraft(GROUP,"组内核对","私有段核对原文。",List.of()));
        w.advanceTo(at);
        var rows = w.autonomyRoleObservation("bob").at(platform ? "/groups/myPlatformPosts" : "/groups/myPosts");
        String doc = null;
        for (var row : rows) if ("QUEUED".equals(row.path("status").asText())) doc=row.path("docId").asText();
        assertNotNull(doc,"在办投递须保留");
        w.advanceTo(at+(platform ? group.platformConfig().deliveryMillis() : group.config().deliveryMillis()));
        return doc;
    }

    @Test void ownPostsKeepExactEditionsAndOldGroupMembershipStillControlsCorrectionAndReading() throws Exception {
        for (boolean latest : List.of(false,true)) {
            var file = dir.resolve("posts-"+latest+".journal");
            JsonNode terminal;
            try (var w = latest ? ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(file)
                    : ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(file)) {
                w.advanceTo(34_200_000);
                var groups = new ArrayList<String>();
                var platform = new ArrayList<String>();
                for (int i=0;i<7;i++) { groups.add(post(w,false,"g-"+i)); platform.add(post(w,true,"p-"+i)); }
                var start = w.autonomyRoleObservation("bob");
                assertEquals(latest ? 4 : 7,start.at("/groups/myPosts").size());
                assertEquals(latest ? 4 : 7,start.at("/groups/myPlatformPosts").size());
                if (latest) {
                    var recent=look(w,"bob","latest-posts",SECTION,null,null).at("/engineering/historyLookup/lastResult");
                    var earlier=look(w,"bob","older-posts",SECTION,"before",recent.required("nextBefore").asText())
                            .at("/engineering/historyLookup/lastResult/ids");
                    assertEquals(4,earlier.size());
                    for (var id : recent.path("ids")) assertFalse(earlier.toString().contains(id.asText()));
                    var recovered=look(w,"bob","recover-post",SECTION,"id",groups.get(0));
                    assertEquals(groups.get(0)+"@1",recovered.at("/engineering/historyLookup/lastResult/ids/0").asText());
                    assertTrue(recovered.at("/groups/myPosts").toString().contains(groups.get(0)));
                    assertFalse(recovered.toString().contains("私有段核对原文。"));
                    assertTrue(look(w,"investor","other-post",SECTION,"id",groups.get(0)).at("/engineering/historyLookup/lastResult/ids").isEmpty());
                    long due=w.snapshot().timeMillis()+w.groupsRun().config().postMillis();
                    assertTrue(w.openRoleSession("bob").correctGroupPost("edit-original","edit-original",due,
                            new WorldRoleSession.GroupCorrectDraft(groups.get(0),1,"更正依据","更正后的组内说明。",List.of())).startsWith("SCHEDULED"));
                    w.advanceTo(due+w.groupsRun().config().deliveryMillis());
                    var last=look(w,"bob","latest-edition",SECTION,"id",groups.get(0));
                    assertEquals(groups.get(0)+"@2",last.at("/engineering/historyLookup/lastResult/ids/0").asText());
                    assertEquals(groups.get(0)+"@1",look(w,"bob","original-edition",SECTION,"id",groups.get(0)+"@1")
                            .at("/engineering/historyLookup/lastResult/ids/0").asText());
                    long leave=w.snapshot().timeMillis()+w.groupsRun().config().leaveMillis();
                    w.openRoleSession("bob").leaveGroup("leave","leave",leave,new WorldRoleSession.GroupLeaveDraft(GROUP));
                    w.advanceTo(leave);
                    assertTrue(w.roleView("bob").catalog().stream().noneMatch(row -> groups.get(0).equals(row.docId())));
                    var ownAfterExit=look(w,"bob","own-history-after-exit",SECTION,"id",groups.get(0)+"@2");
                    assertEquals(groups.get(0)+"@2",ownAfterExit.at("/engineering/historyLookup/lastResult/ids/0").asText());
                    assertFalse(ownAfterExit.toString().contains("更正后的组内说明。"));
                    assertFalse(w.openRoleSession("bob").correctGroupPost("forbidden","forbidden",w.snapshot().timeMillis()+w.groupsRun().config().postMillis(),
                            new WorldRoleSession.GroupCorrectDraft(groups.get(0),2,"离岗更正","无此权限",List.of())).startsWith("SCHEDULED"));
                    var prior=look(w,"bob","old-platform",SECTION,"id",platform.get(0));
                    assertEquals(platform.get(0)+"@1",prior.at("/engineering/historyLookup/lastResult/ids/0").asText());
                    assertTrue(prior.at("/groups/myPlatformPosts").toString().contains(platform.get(0)));
                }
                terminal=w.autonomyRoleObservation("bob");
            }
            var raw=Files.readAllBytes(file);
            try (var w = latest ? ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(file)
                    : ComposedIndustryScenario.openDividendAmendmentTenCompanyStudy(file)) { assertEquals(terminal,w.autonomyRoleObservation("bob")); }
            assertArrayEquals(raw,Files.readAllBytes(file));
        }
    }

    @Test void arrivalBelongsToTheSubscriberAndPriorDeliverySurvivesUnsubscriptionWithoutARead() throws Exception {
        try(var w=ComposedIndustryScenario.openOnsiteDecisionClockTenCompanyStudy(dir.resolve("delivery.journal"))) {
            w.advanceTo(34_200_000);
            w.scheduleMediaSubscription("nina","JOIN","wire","trial"); w.advanceTo(w.snapshot().timeMillis()+1000);
            var cite=EditorialReviewFeedbackTest.read(w,"rita","engineering-opportunity:A1",1);
            var docs=new ArrayList<String>();
            for(int i=0;i<7;i++) {
                String s=MediaRoleHistoryTest.issue(w,cite,"newsletter-"+i,"合同核对第"+i+"期");
                docs.add(EditorialReviewFeedbackTest.row(w,"ed",s).publicDocId());
            }
            assertEquals(7,((List<?>)w.mediaSubscriptionsOf("nina").get("deliveries")).size());
            var start=w.autonomyRoleObservation("nina");
            assertEquals(4,start.at("/engineering/mediaSubscriptions/deliveries").size());
            w.scheduleMediaSubscription("nina","LEAVE","wire","leave"); w.advanceTo(w.snapshot().timeMillis()+1000);
            var old=look(w,"nina","newsletter-first",SECTION,"id",docs.get(0));
            assertEquals(docs.get(0)+"@1",old.at("/engineering/historyLookup/lastResult/ids/0").asText());
            assertTrue(old.at("/engineering/mediaSubscriptions/deliveries").toString().contains(docs.get(0)));
            assertFalse(w.canCite("nina",docs.get(0),1));
            assertTrue(look(w,"investor","not-a-subscriber",SECTION,"id",docs.get(0)).at("/engineering/historyLookup/lastResult/ids").isEmpty());
            EditorialReviewFeedbackTest.read(w,"nina",docs.get(0),1);
            assertTrue(w.canCite("nina",docs.get(0),1));
        }
    }
}
