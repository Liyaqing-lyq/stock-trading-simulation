package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.web.WorldWebScenario;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 同一次实际现场会谈、差旅与定向阅读不应当互相冒用授权。 */
class ActiveInterviewFocusTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void onsiteTurnCanUseOnlyItsOwnPriorMessageWhileOtherTravelRemainsBusy(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("onsite.journal");
        long start = 500_000;
        String id;
        try (var world = ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(file)) {
            id = OnsiteVisitTest.book(world, start);
            OnsiteVisitTest.act(world, "investor", id, "HOST_CONSENT");
            OnsiteVisitTest.act(world, "rita", id, "DEPART");
            world.advanceTo(start - 5_000);
            assertTrue(world.autonomyRoleBusy("rita"));
            world.advanceTo(start);
            assertEquals("ONSITE", OnsiteVisitTest.row(world).stage());
            assertFalse(world.autonomyRoleBusy("rita"));
            assertTrue(world.autonomyRoleBusy("investor"));
            speak(world, "rita", id, "question");
            var focus = world.autonomyRoleObservation("investor").at("/interview/activeTurn");
            assertEquals("role.reading.start", focus.path("nextTurnTool").asText());
            var guessed = JSON.createObjectNode().put("docId",WorldWebScenario.INTERVIEW_CONTACT_DOC).put("version",1);
            assertNull(PersonalMarketTest.decide(world, "investor", "other-doc", "role.reading.start", guessed));
            var rejected = world.autonomyRoleObservation("investor").at("/engineering/deliberation/lastActionResult");
            assertTrue(rejected.toString().contains("INTERVIEW_FOCUS"), rejected.toString());
            world.advanceTo(DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),
                    "other-doc").orElseThrow().barrierAtMillis());
            readTurn(world, "investor");
            speak(world, "investor", id, "answer");
            readTurn(world, "rita");
            assertFalse(world.autonomyRoleBusy("rita"));
            OnsiteVisitTest.act(world,"rita",id,"RETURN");
            assertTrue(world.autonomyRoleBusy("rita"));
        }
        byte[] before = Files.readAllBytes(file);
        try (var world = ComposedIndustryScenario.openInterviewAutonomyTenCompanyStudy(file)) {
            assertEquals(2,world.interviewRun().dialoguesOf("wire").turnsOf("SESSION-"+id).size());
            assertEquals("RETURNING", OnsiteVisitTest.row(world).stage());
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    private static void speak(WorldRuntime world, String person, String requestId, String title) throws Exception {
        var args = JSON.createObjectNode().put("requestId",requestId).put("title",title)
                .put("body","亲自核实本次会面的定向轮次。").put("readDurationMillis",1_000);
        var action = PersonalMarketTest.decide(world,person,title,"interview.speak",args);
        assertNotNull(action);
        world.advanceTo(action.dueMillis());
        assertTrue(world.interviewRun().dialoguesOf("wire").turnsOf("SESSION-"+requestId).stream()
                .anyMatch(turn -> turn.speakerPartyId().equals(person)));
    }

    private static void readTurn(WorldRuntime world, String person) throws Exception {
        var turn = world.autonomyRoleObservation(person).at("/interview/activeTurn");
        var args = JSON.createObjectNode().put("docId",turn.required("docId").asText())
                .put("version",turn.required("version").asLong());
        var action = PersonalMarketTest.decide(world,person,"read-"+person,"role.reading.start",args);
        assertNotNull(action);
        world.advanceTo(action.dueMillis());
        assertTrue(world.autonomyRoleBusy(person));
        var reading = world.roleView(person).readings().get(0);
        assertTrue(reading.dueAt()>world.snapshot().timeMillis());
        world.advanceTo(reading.dueAt());
        assertEquals("interview.speak",world.autonomyRoleObservation(person).at("/interview/activeTurn/nextTurnTool").asText());
    }
}
