package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MixedMarketInputTest {
    @Test void exceptionRowsKeepMissingNullAndFieldOrderAndAlreadyValidInputStaysIdentical() throws Exception {
        var mapper=new ObjectMapper();
        var raw=mapper.createObjectNode();var market=raw.putObject("profile").putArray("market");
        for(int i=0;i<10;i++){
            var row=market.addObject().put("symbol","S"+i).putNull("lastPrice").put("bestBidQuantity","0");
            if(i==1)row.putObject("issuerResolution").put("status","ACTIVE");
            if(i==4)row.putNull("issuerResolution");
            row.put("referenceSource","OPENING_REFERENCE").put("referencePrice","10.00");
        }
        String original=raw.toString();
        assertEquals(original,DecisionRun.renderInput(raw.deepCopy(),RoleInputTables.MIXED_MARKET_FORMAT,original.length()));
        assertEquals(original,DecisionRun.renderInput(raw.deepCopy(),RoleInputTables.BOARD_VERSION,original.length()-1));
        String packed=DecisionRun.renderInput(raw.deepCopy(),RoleInputTables.MIXED_MARKET_FORMAT,original.length()-1);
        assertTrue(packed.length()<original.length());
        var copy=(ObjectNode)mapper.readTree(packed);
        assertTrue(copy.at("/profile/market/rows/0").isArray());
        assertEquals("ACTIVE",copy.at("/profile/market/rows/1/issuerResolution/status").asText());
        assertTrue(copy.at("/profile/market/rows/4/issuerResolution").isNull());
        RoleInputTables.expand(copy);assertEquals(original,copy.toString());
    }
    @Test void planningReadingsAndReferencesKeepAllValuesAndOrder() throws Exception {
        var mapper = new ObjectMapper();
        var raw = mapper.createObjectNode();
        var reads = raw.putObject("profile").putArray("readExperiences");
        for (int i=0; i<6; i++) reads.addObject().put("docId", "D"+i).put("body", "exact"+i).putNull("source");
        var eng=raw.putObject("engineering");var history=eng.putObject("deliberation");
        history.put("replyContract","verbatim".repeat(40));eng.putObject("decisionGuide").put("waitContract",history.get("replyContract").asText());
        var decisions=history.putArray("recentDecisions");
        decisions.addObject().put("action","first");var latest=decisions.addObject().put("result","receipt".repeat(75)).putNull("value");
        history.set("lastActionResult",latest.deepCopy());
        var actions=eng.putObject("planning").putObject("actions");
        for (int i=0; i<5; i++) actions.putObject("tool."+i).put("actionMillis", 2500+i).put("earliestCompletionMillis",Long.MAX_VALUE-i);
        String normal=raw.toString();String packed=DecisionRun.renderInput(raw.deepCopy(),RoleInputTables.MIXED_MARKET_FORMAT,normal.length()-1);
        var view=(ObjectNode)mapper.readTree(packed);
        assertTrue(view.at("/profile/readExperiences/rows/0").isArray());
        assertEquals(Long.MAX_VALUE,view.at("/engineering/planning/actions/rows/0/2").longValue());
        assertEquals("#/engineering/deliberation/recentDecisions/1",view.at("/engineering/deliberation/lastActionResult/$ref").asText());
        assertEquals("#/engineering/deliberation/replyContract",view.at("/engineering/decisionGuide/waitContract/$ref").asText());
        RoleInputTables.expand(view);assertEquals(normal,view.toString());
        assertEquals(normal,DecisionRun.renderInput(raw.deepCopy(),RoleInputTables.MIXED_MARKET_FORMAT,normal.length()));
        history.putObject("lastActionResult").put("different","receipt");
        eng.withObject("/decisionGuide").put("waitContract","different");
        RoleInputTables.compactPlanningAndHistory(raw);
        assertEquals("receipt",history.at("/lastActionResult/different").textValue());
        assertEquals("different",eng.at("/decisionGuide/waitContract").textValue());
    }
}
