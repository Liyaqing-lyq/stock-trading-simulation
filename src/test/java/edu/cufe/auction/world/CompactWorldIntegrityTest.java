package edu.cufe.auction.world;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import edu.cufe.auction.world.composed.ComposedIndustryScenario;
import static org.junit.jupiter.api.Assertions.*;
class CompactWorldIntegrityTest {
 private Path directory() throws IOException { return Files.createTempDirectory(Path.of("logs/operating-cycle"),"delta-integrity-"); }
 @Test void decisionInputRawReplyAndMoneySurviveReopenWithoutByteChanges() throws Exception {
  Path dir=directory();WorldRuntime.Snapshot saved;String input;
  try(var legacy=ComposedIndustryScenario.openAccountableAutonomous(dir.resolve("old.journal"));var world=ComposedIndustryScenario.openCompactAccountableAutonomous(dir.resolve("new.journal"))) {
   var route=new DecisionModel.Route("governance","key-1","deepseek-flash",1,500,5000000,512,4,"lifecycle-v1");
   for(var runtime:List.of(legacy,world)) {runtime.beginDecisionSession("session");runtime.openDecisions("open",List.of(new DecisionRun.Request("mfgtech-d1","action",runtime.snapshot().decisions().orElseThrow().state().config().thinkMillisByWake().keySet().iterator().next(),route)));}
   var oldCall=legacy.dispatchLiveDecision("dispatch","action").call();var call=world.dispatchLiveDecision("dispatch","action").call();
   assertNotNull(call);assertEquals(oldCall.inputText(),call.inputText());input=call.inputText();
   var json=new ObjectMapper();var root=json.readTree(input);
   var body=json.createObjectNode().put("tool","role.wait");body.putObject("decision").put("objective",root.path("engineering").path("deliberation").path("objectives").get(0).path("id").asText()).put("basis","原文保留 中文与\\n换行").put("nextReviewAt",10000);
   for(var runtime:List.of(legacy,world)) {var selected=runtime==world?call:oldCall;var reply=new DecisionModel.Reply(selected.runId(),selected.actionId(),selected.attemptId(),selected.attemptNo(),selected.recoveryGeneration(),body.toString(),DecisionModel.Usage.unknownCost(1,10,30),"TEST");assertEquals(WorldRuntime.CompletionOffer.QUEUED,runtime.offerLiveDecisionCompletion(LiveCompletionInbox.Completion.business(reply)));assertEquals(1,runtime.drainDecisionCallbacks());}
   saved=world.snapshot();assertEquals(legacy.snapshot().accounts(),saved.accounts());
   assertEquals(legacy.decisionAudits().get(0).body(),world.decisionAudits().get(0).body());
  }
  try(var world=ComposedIndustryScenario.openCompactAccountableAutonomous(dir.resolve("new.journal"))) {assertEquals(saved,world.snapshot());assertEquals(input,DecisionModel.decision(world.snapshot().decisions().orElseThrow().state(),"action").orElseThrow().inputText());}
 }
 @Test void validFrameHashesCannotHideTamperedOrMissingDeltaAndPartialTailKeepsPrefix() throws Exception {
  Path dir=directory(), source=dir.resolve("source.journal");String run;
  try(var world=ComposedIndustryScenario.openCompactAccountableAutonomous(source)){world.advanceTo(1);world.advanceTo(2);run=world.profile().manifest().runId();}
  List<WorldCommit> commits;long priorEnd;
  try(var journal=new WorldJournal(source,run)){commits=journal.commits();priorEnd=journal.endOffsetOf(commits.size()-1);}
  ObjectMapper json=new ObjectMapper();
  for(String mutation:List.of("base","digest","version","foreign","missing","duplicate","patch")) {
   Path target=dir.resolve(mutation+".journal");
   try(var journal=new WorldJournal(target,run)) {for(var commit:commits) {
    List<WorldEvent> events=new ArrayList<>(commit.events());
    if(commit.position()==commits.size()) {for(int i=0;i<events.size();i++) {var event=events.get(i);if(!StateDeltaCodec.TYPE.equals(event.type()))continue;
     ObjectNode delta=(ObjectNode)json.readTree(event.payload());
     switch(mutation) {case "base" -> delta.put("basePosition",0);case "digest" -> delta.put("resultDigest","0".repeat(64));case "version" -> delta.put("version","delta-v99");case "foreign" -> delta.put("runId","foreign");case "missing" -> {events.remove(i);i--;}case "duplicate" -> events.add(new WorldEvent("duplicate-state",StateDeltaCodec.TYPE,event.payload()));case "patch" -> ((ObjectNode)delta.path("patch").path("changes")).remove("timeMillis");}
     if(!mutation.equals("missing")) events.set(i,new WorldEvent(event.eventId(),event.type(),delta.toString()));break;
    }}
    journal.commit(commit.commandId(),commit.commandContent(),commit.result(),events);
   }}
   assertThrows(IOException.class,()->ComposedIndustryScenario.openCompactAccountableAutonomous(target),mutation);
  }
  Path forged=dir.resolve("valid-digests-invalid-command.journal");String previous=null;
  try(var journal=new WorldJournal(forged,run)) { for(var commit:commits) {
   List<WorldEvent> events=new ArrayList<>(commit.events());
   for(int i=0;i<events.size();i++) {var event=events.get(i);if(!StateDeltaCodec.TYPE.equals(event.type()))continue;
    String materialized=StateDeltaCodec.apply(run,commit.position(),previous,event.payload());
    if(commit.position()==commits.size()) {ObjectNode fake=(ObjectNode)json.readTree(materialized);fake.put("timeMillis",3);
     events.set(i,new WorldEvent(event.eventId(),event.type(),StateDeltaCodec.encode(run,commit.position(),previous,fake.toString())));}
    previous=materialized;
   }
   journal.commit(commit.commandId(),commit.commandContent(),commit.result(),events);
  }}
  assertThrows(IOException.class,()->ComposedIndustryScenario.openCompactAccountableAutonomous(forged),"valid patch and SHA cannot bypass original clock command replay");
  Path tail=dir.resolve("tail.journal");byte[] bytes=Files.readAllBytes(source);Files.write(tail,Arrays.copyOf(bytes,bytes.length-7));
  try(var world=ComposedIndustryScenario.openCompactAccountableAutonomous(tail)){assertEquals(1,world.snapshot().timeMillis());assertEquals(priorEnd,Files.size(tail));world.advanceTo(2);assertEquals(2,world.snapshot().timeMillis());}
 }
}
