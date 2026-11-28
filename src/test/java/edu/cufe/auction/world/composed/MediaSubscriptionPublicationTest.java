package edu.cufe.auction.world.composed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static edu.cufe.auction.world.composed.EditorialReviewFeedbackTest.*;

public class MediaSubscriptionPublicationTest {
    @TempDir Path directory;
    static final String BODY="公开报道中所列验收条件须按原始合同核实。";

    /** 用真实证据、初稿、审读与正式发行生成一版 WIRE 稿件。 */
    public static String publishWire(WorldRuntime w) throws Exception {
        var ref=read(w,"rita","engineering-opportunity:A1",1);
        var story=propose(w,"subscription-issue","业主验收条款");
        submit(w,new WorldRoleSession.MediaSubmitDraft(story,0,"业主公告核对",BODY,"业主条款",List.of(ref)));
        review(w,story,1,true,"已查阅。");
        work(w,"ed",MediaRun.KIND_PUBLISH,(s,c,t,d)->s.publishStory(c,t,d,new WorldRoleSession.MediaStoryDraft(story,1)));
        return row(w,"ed",story).publicDocId();
    }
    @Test void genuineIssueNotifiesOnlyCurrentSubscriberAndKeepsPublicOriginalForEveryone() throws Exception {
        Path file=directory.resolve("issue.journal");
        String doc;
        JsonNode beforeClose;
        try(var w=ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {
            w.scheduleMediaSubscription("nina","JOIN","wire","free-trial");
            w.advanceTo(w.snapshot().timeMillis()+1000);
            doc=publishWire(w);
            var received=new ObjectMapper().valueToTree(w.mediaSubscriptionsOf("nina")).required("deliveries");
            assertEquals(1,received.size()); assertEquals("PUBLIC_NOTICE",received.get(0).required("kind").asText());
            assertEquals(doc,received.get(0).required("docId").asText()); assertEquals(1,received.get(0).required("version").asInt());
            assertEquals(List.of(),w.mediaSubscriptionsOf("dave").get("deliveries"));
            for(String person:List.of("nina","dave")) {
                assertTrue(w.roleView(person).catalog().stream().anyMatch(e->doc.equals(e.docId())));
                assertFalse(w.autonomyRoleObservation(person).toString().contains(BODY));
            }
            String reason="本人订阅了刊发此稿的媒体";
            assertEquals(reason,w.personalNewsFeed("nina").stream().filter(e->doc.equals(e.docId())).findFirst().orElseThrow().rankingReason());
            assertNotEquals(reason,w.personalNewsFeed("dave").stream().filter(e->doc.equals(e.docId())).findFirst().orElseThrow().rankingReason());
            read(w,"dave",doc,1);
            assertTrue(w.roleView("dave").observations().stream().anyMatch(e->doc.equals(e.ref().docId()) && e.body().contains(BODY)));
            assertFalse(w.autonomyRoleObservation("nina").toString().contains(BODY));
            w.scheduleMediaSubscription("nina","LEAVE","wire","end-trial");
            w.advanceTo(w.snapshot().timeMillis()+1000);
            assertNotEquals(reason,w.personalNewsFeed("nina").stream().filter(e->doc.equals(e.docId())).findFirst().orElseThrow().rankingReason());
            beforeClose=w.autonomyRoleObservation("nina");
        }
        byte[] original=Files.readAllBytes(file);
        try(var w=ComposedIndustryScenario.openPersonalNewsTenCompanyStudy(file)) {assertEquals(beforeClose,w.autonomyRoleObservation("nina"));}
        assertArrayEquals(original,Files.readAllBytes(file));
    }
}
