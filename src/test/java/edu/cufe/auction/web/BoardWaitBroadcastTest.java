package edu.cufe.auction.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoardWaitBroadcastTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode previous() {
        var facts = JSON.createObjectNode().put("person", "voter");
        facts.putObject("profile").putArray("catalog");
        facts.putObject("groups").putArray("feed");
        return facts;
    }

    private static ObjectNode article(ObjectNode previous) {
        var facts = previous.deepCopy();
        facts.withArray("/groups/feed").addObject().put("docId","article").put("version",1)
                .put("publisher","editor").put("title","公开消息").put("kind","PLATFORM_POST");
        facts.withArray("/profile/catalog").addObject().put("docId","article").put("version",1).put("title","公开消息");
        return facts;
    }

    private static ObjectNode input() {
        var input = JSON.createObjectNode().put("time",1100);
        var engineering = input.putObject("engineering");
        var decision = engineering.putObject("deliberation");
        decision.putArray("objectives").addObject().put("id","director:company");
        decision.putArray("recentDecisions").addObject().put("outcome","WAIT").put("tool","role.wait")
                .put("observedAt",1000).putObject("ownStatement").put("objective","director:company");
        engineering.putArray("directorships").addObject().put("companyPartyId","company").putArray("meetings")
                .addObject().put("window","BEFORE_OPEN").put("status","CONVENED").put("opensAt",1200)
                .put("attended",true).put("recused",false).put("proposalPresent",true)
                .putArray("materials").addObject().put("docId","board-material").put("read",true).put("recalled",true);
        return input;
    }

    @Test void oneNewPlatformPostAndItsOwnCatalogRowCanWaitOnlyBeforeTheChosenReviewOrOpening() {
        var old = previous(); var news = article(old); var input = input();
        assertTrue(BoardWaitBroadcast.defer(old,news,input,1000,1300));
        assertTrue(news.at("/groups/feed/0/docId").isTextual());
        assertFalse(BoardWaitBroadcast.defer(old,news,input,1000,1100));
        assertFalse(BoardWaitBroadcast.defer(old,news,input.put("time",1200),1000,1300));
        assertFalse(BoardWaitBroadcast.defer(old,news,input(),1000,31001));
    }

    @Test void compactReadingHistoryUsesTheSameDocumentIdsAsTheOriginalArray() {
        for (String document : List.of("board-material", "outside-the-meeting")) {
            var old = previous();
            old.withArray("/profile/readExperiences").addObject().put("docId", document);
            var raw = input();
            var table = raw.withObject("/profile/readExperiences");
            table.putArray("columns").add("docId");
            table.putArray("rows").addArray().add(document);
            assertEquals(document.equals("board-material"), BoardWaitBroadcast.defer(old, article(old), raw, 1000, 1300));
        }
    }

    @Test void directEventsOfficialDocumentsAndExistingInterestRemainImmediate() {
        var old = previous();
        for (String path : List.of("/social/inbox","/interview/invites","/profile/activeReadings","/profile/market")) {
            var news=article(old); news.withArray(path).addObject().put("id","fresh");
            assertFalse(BoardWaitBroadcast.defer(old,news,input(),1000,1300), path);
        }
        var disclosure = article(old);
        disclosure.withArray("/profile/catalog").addObject().put("docId","signed-company-report");
        assertFalse(BoardWaitBroadcast.defer(old,disclosure,input(),1000,1300));
        for(String path:List.of("/profile/accounts","/cognition/claims","/appointments","/groups/memberships")) {
            var input=input();input.withArray(path).addObject().put("id","ongoing");
            assertFalse(BoardWaitBroadcast.defer(old,article(old),input,1000,1300),path);
        }
        var read=input();read.withArray("/profile/readExperiences").addObject().put("docId","outside-the-meeting");
        assertFalse(BoardWaitBroadcast.defer(old,article(old),read,1000,1300));
        var otherMeeting=input();otherMeeting.withArray("/engineering/directorships/0/meetings").addObject().put("status","ADOPTED");
        assertFalse(BoardWaitBroadcast.defer(old,article(old),otherMeeting,1000,1300));
    }
}
