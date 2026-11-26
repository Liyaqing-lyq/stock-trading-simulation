package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cufe.auction.world.bank.BankActions;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersonDecisionPauseTest {
    @TempDir Path dir;
    private static final Path DRAFT=Path.of("src/main/resources/world/opening-copies-example.json");
    private static final String PERSON="investor";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static DecisionRun.Request request(String person,String id) {
        return new DecisionRun.Request(person,id,"wake",new DecisionModel.Route("trading","key-1","deepseek-flash",1L,25,1_000_000,2048,1));
    }

    @Test void validlyEncodedDecisionFrameStillRequiresAnActivePerson() throws Exception {
        Path source=dir.resolve("active.journal"), edited=dir.resolve("paused.journal");
        try(var world=ComposedIndustryScenario.openPersonPauseOperating(source,DRAFT)) {
            world.beginDecisionSession("live");
            world.setPersonDecisionPause(new WorldRuntime.PersonPauseRequest(world.snapshot().position(),"nina",true));
            world.openDecisions("wake-investor",List.of(request(PERSON,"decision")));
        }
        var scan=WorldJournalScanner.scan(source);
        // 保留原始决定输入和已确认时点，把暂停正式指向 investor，并重新编码每一帧。
        String actual=null, modified=null;
        try(var writer=new WorldJournal(edited,scan.runId())) {
            for(var commit:scan.commits()) {
                var events=new java.util.ArrayList<WorldEvent>();
                String content=commit.commandContent();
                if(commit.commandId().startsWith("person-decision-pause:"))
                    content=content.replace("\"nina\"","\"investor\"");
                for(var event:commit.events()) {
                    if(event.type().equals(StateDeltaCodec.TYPE)) {
                        actual=StateDeltaCodec.apply(scan.runId(),commit.position(),actual,event.payload());
                        var next=actual.replace("\"pausedPersons\":[\"nina\"]","\"pausedPersons\":[\"investor\"]");
                        String delta=StateDeltaCodec.encode(scan.runId(),commit.position(),modified,next);
                        events.add(new WorldEvent(event.eventId(),event.type(),delta));
                        modified=next;
                    } else if(event.type().equals("PERSON_DECISION_PAUSE_V1")) {
                        events.add(new WorldEvent(event.eventId(),event.type(),content));
                    } else events.add(event);
                }
                writer.commit(commit.commandId(),content,commit.result(),events);
            }
        }
        assertTrue(modified.contains("\"pausedPersons\":[\"investor\"]"));
        var rejected=assertThrows(IOException.class,()->ComposedIndustryScenario.openPersonPauseOperating(edited,null));
        var reasons=new java.util.ArrayList<String>();
        for(Throwable cause=rejected;cause!=null;cause=cause.getCause()) reasons.add(cause.getMessage());
        assertTrue(reasons.stream().anyMatch(message->message.contains("暂停人物不能新开决定")),reasons.toString());
    }

    @Test void singleActorPausePersistsAndDoesNotUnwindAlreadyCommittedObligations() throws Exception {
        Path journal=dir.resolve("pause.journal");
        long pausedCut, due;
        try(var w=ComposedIndustryScenario.openPersonPauseOperating(journal,DRAFT)) {
            assertNotNull(w.resolutionStatus("BNK1"));
            due=w.snapshot().timeMillis()+5;
            w.openRoleSession(PERSON).bankAction("before","before",due,3,new WorldRoleSession.BankActionDraft("before",
                    BankActions.Kind.DEPOSIT,"north","",new BigDecimal("11.00"),0,0,"","","",""));
            w.advanceTo(due-3);
            var before=w.snapshot();
            var pause=new WorldRuntime.PersonPauseRequest(before.position(),PERSON,true);
            assertThrows(IllegalArgumentException.class,()->w.setPersonDecisionPause(new WorldRuntime.PersonPauseRequest(before.position(),"research-system",true)));
            assertThrows(IllegalArgumentException.class,()->w.setPersonDecisionPause(new WorldRuntime.PersonPauseRequest(before.position(),"north",true)));
            pausedCut=w.setPersonDecisionPause(pause);
            assertEquals(pausedCut,w.setPersonDecisionPause(pause));
            assertEquals(pausedCut,w.snapshot().position());
            assertThrows(IllegalStateException.class,()->w.setPersonDecisionPause(new WorldRuntime.PersonPauseRequest(before.position(),"nina",true)));
            assertEquals(before.accounts(),w.snapshot().accounts());
            assertEquals(before.pending(),w.snapshot().pending());
            assertEquals(before.roles().orElseThrow().access(),w.snapshot().roles().orElseThrow().access());
        }
        try(var w=ComposedIndustryScenario.openPersonPauseOperating(journal,null)) {
            assertEquals(pausedCut,w.snapshot().position());
            assertTrue(w.personDecisionsPaused(PERSON));
            w.advanceTo(due);
            assertEquals("OK",w.taskCommitResult(WorldRuntime.roleTaskId(PERSON,"before")+":finish"));
            assertEquals("11.00",w.valuation(PERSON).bankClaims().deposits().toPlainString());
            assertEquals("11350.00",w.valuation(PERSON).netWorth().toPlainString());
            w.beginDecisionSession("resume-session");
            long beforeRequest=w.snapshot().position();
            assertThrows(IllegalStateException.class,()->w.openDecisions("paused",List.of(request("nina","would-run"),request(PERSON,"blocked"))));
            assertEquals(beforeRequest,w.snapshot().position());
            assertNull(DecisionModel.decision(w.snapshot().decisions().orElseThrow().state(),"would-run").orElse(null));
            PersonalMarketTest.decide(w,"nina","other-person",DecisionModel.TOOL_WAIT,JSON.createObjectNode());
            assertEquals(1,w.snapshot().decisions().orElseThrow().state().runUsage().attempts());
            w.setPersonDecisionPause(new WorldRuntime.PersonPauseRequest(w.snapshot().position(),PERSON,false));
            PersonalMarketTest.decide(w,PERSON,"after-resume",DecisionModel.TOOL_WAIT,JSON.createObjectNode());
            var model=w.snapshot().decisions().orElseThrow().state();
            assertEquals(2,model.runUsage().attempts());
            assertEquals(DecisionModel.Outcome.WAIT,DecisionModel.decision(model,"after-resume").orElseThrow().outcome());
            w.openDecisions("inflight",List.of(request(PERSON,"inflight")));
            assertNotNull(w.dispatchLiveDecision("dispatch-inflight","inflight").call());
            long busy=w.snapshot().position();
            assertThrows(IllegalStateException.class,()->w.setPersonDecisionPause(new WorldRuntime.PersonPauseRequest(busy,PERSON,true)));
            assertEquals(busy,w.snapshot().position());
        }
        var scan=WorldJournalScanner.scan(journal);
        var changes=scan.commits().stream().flatMap(c->c.events().stream()).filter(e->e.type().equals("PERSON_DECISION_PAUSE_V1")).toList();
        assertEquals(2,changes.size());
        try(var w=ComposedIndustryScenario.openPersonPauseOperating(journal,null)) {assertFalse(w.personDecisionsPaused(PERSON));}
        var forged=dir.resolve("missing-person.journal");
        try(var writer=new WorldJournal(forged,scan.runId())) {
            for(var c:scan.commits()) writer.commit(c.commandId(),c.commandContent(),c.result(),c.events().stream()
                    .filter(e->!e.type().equals("PERSON_DECISION_PAUSE_V1")).toList());
        }
        assertThrows(IOException.class,()->ComposedIndustryScenario.openPersonPauseOperating(forged,null));
        var oldFile=dir.resolve("old.journal");
        try(var old=ComposedIndustryScenario.openIssuerResolutionOperating(oldFile,DRAFT)) {
            assertThrows(IllegalArgumentException.class,()->old.setPersonDecisionPause(new WorldRuntime.PersonPauseRequest(old.snapshot().position(),PERSON,true)));
        }
        var first=WorldJournalScanner.scan(oldFile).commits().get(0);
        var delta=first.events().stream().filter(e->e.type().equals(StateDeltaCodec.TYPE)).findFirst().orElseThrow();
        var oldOpening=StateDeltaCodec.checkedTree(first.runId(),first.position(),null,delta.payload());
        assertFalse(oldOpening.required("roles").has("pausedPersons"),"旧档首帧摘要必须保持历史字节");
    }
}
